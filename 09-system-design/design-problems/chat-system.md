# Design a Chat System (WhatsApp / Slack)

A real-time messaging platform supporting 1:1 and group chat with delivery guarantees, presence, read receipts, push notifications, and end-to-end encryption at the scale of **billions of messages per day**.

[← Back to master index](../../README.md) · [← System Design index](../README.md)

---

## 1. Requirements

### Functional
- **1:1 messaging** — user A sends a text/media message to user B; B receives it in (near) real time.
- **Group messaging** — groups of up to ~256 members (WhatsApp) or large channels (Slack, thousands of members).
- **Delivery & ordering** — messages within a conversation appear in a consistent order; no duplicates, no loss.
- **Message status** — *sent* (server received), *delivered* (recipient device received), *read* (recipient opened). Three-tick / blue-tick semantics.
- **Online/offline presence** — show "online", "last seen", "typing…".
- **Push notifications** — deliver to APNs/FCM when the recipient is offline or backgrounded.
- **Media** — images, video, voice notes, files. Sent out-of-band via an object store, not the message pipe.
- **Message history** — sync across devices; load conversation history on demand.
- **End-to-end encryption (E2EE)** — server cannot read message content (WhatsApp model).

### Non-Functional
| Dimension | Target |
|---|---|
| **Scale** | 2B users, 500M DAU, 50B messages/day, 100M concurrent WebSocket connections |
| **Latency** | p99 message delivery < 200 ms (sender ack), < 500 ms end-to-end same region |
| **Availability** | 99.99% (≈52 min/yr downtime). Messaging must survive AZ loss |
| **Durability** | Zero acknowledged-message loss. Persist before acking the sender |
| **Consistency** | Per-conversation ordering must be consistent; cross-conversation can be eventual |

### Clarifying questions a candidate should ask
1. **E2EE required?** This fundamentally changes the design: with E2EE the server can't do server-side search, content moderation, or smart replies. WhatsApp = yes; Slack = no (server stores plaintext for search/compliance).
2. **Group size?** 256-member WhatsApp groups vs. 100k-member Slack channels need different fan-out strategies.
3. **Multi-device?** One account, N devices — affects key management and message sync (companion-device model).
4. **Message retention?** Forever (Slack/compliance) vs. on-device only after delivery (WhatsApp historically deleted from server post-delivery).
5. **Ordering guarantee?** Per-conversation total order, or global? Per-conversation is the realistic target.
6. **Read receipts mandatory or optional?** Affects write amplification significantly.

---

## 2. Capacity Estimation

Assume **500M DAU**, **50B messages/day**.

### QPS
```
Messages/day            = 50,000,000,000
Seconds/day             = 86,400
Average write QPS       = 50e9 / 86,400        ≈ 580,000 msg/s
Peak (3× average)       ≈ 1,700,000 msg/s
```
Reads: each message is read ~1.2× (some unread, group fan-out raises it). Plus status updates (delivered/read) roughly double the event volume. Effective event QPS at peak ≈ **3–4M events/s**.

### Connections / Memory
```
Concurrent connections  = 100,000,000 WebSockets
Per-connection memory   ≈ 10 KB (socket buffers + session state)
Total connection RAM    = 100e6 × 10 KB = 1 TB
Connections per gateway = 100,000 (tuned, epoll/kqueue)
Gateway servers         = 100e6 / 100,000 = 1,000 servers
```
1,000 connection servers is the floor; run ~1,500–2,000 for headroom and rolling deploys.

### Storage
```
Avg message size (metadata + small text, no media) ≈ 300 bytes
Daily message storage   = 50e9 × 300 B = 15 TB/day
Yearly (raw)            = 15 TB × 365 ≈ 5.5 PB/yr
With RF=3 replication    ≈ 16.4 PB/yr
```
Media (images/video) is far larger but lives in object storage (S3/GCS) behind a CDN, not in the message DB. If 10% of messages carry a 200 KB median attachment: `5e9 × 200 KB = 1 PB/day` of media — clearly must be object-store + CDN, decoupled from the hot message path.

### Bandwidth
```
Inbound message bandwidth  = 580k msg/s × 300 B ≈ 175 MB/s (text only)
Fan-out amplification: a message to a 100-member group = 1 write, 100 deliveries.
With avg fan-out factor ~3 (mix of 1:1 and groups):
Outbound delivery bandwidth ≈ 175 MB/s × 3 ≈ 525 MB/s + protocol overhead
```

### Takeaways
- **Connection servers** are a memory/FD-bound fleet (~1–2k nodes) separate from stateless business logic.
- **Message store** must absorb ~1.7M writes/s sustained — this dictates a wide-column store (Cassandra), not a single SQL primary.
- **Media path is fully decoupled** from messaging.

---

## 3. API Design

WebSocket for the real-time bidirectional channel; REST/gRPC for setup, history, and media.

```
# ---- Connection bootstrap (REST) ----
POST /v1/connect
  → { gateway_url, connection_token }     # client then opens WS to gateway_url

# ---- WebSocket frames (after auth handshake) ----
# Client → Server
{ "type": "SEND",      "client_msg_id": "uuid", "conversation_id": "c123",
  "ciphertext": "...", "media_ref": null, "ts": 1718500000 }
{ "type": "ACK",       "message_id": "01H...", "status": "DELIVERED" }
{ "type": "READ",      "conversation_id": "c123", "up_to_message_id": "01H..." }
{ "type": "TYPING",    "conversation_id": "c123" }
{ "type": "PRESENCE",  "status": "ONLINE" }

# Server → Client
{ "type": "MESSAGE",   "message_id": "01H...", "conversation_id": "c123",
  "sender_id": "u9", "ciphertext": "...", "seq": 4471, "ts": 1718500000 }
{ "type": "ACK",       "client_msg_id": "uuid", "message_id": "01H...",
  "status": "SENT", "seq": 4471 }          # server-assigned id + seq
{ "type": "STATUS",    "message_id": "01H...", "status": "READ", "by": "u7" }
{ "type": "PRESENCE",  "user_id": "u7", "status": "ONLINE", "last_seen": null }

# ---- History & media (REST/gRPC) ----
GET  /v1/conversations/{cid}/messages?before_seq=4471&limit=50
POST /v1/media:initUpload  → { upload_url, media_ref }   # pre-signed S3 PUT
GET  /v1/media/{ref}        → 302 redirect to CDN URL
GET  /v1/keys/{user_id}     → prekey bundle (E2EE: identity key, signed prekey, one-time prekey)
```

**Design notes:** the client supplies a `client_msg_id` (UUID) so the server can **dedupe** retries idempotently. The server returns a `message_id` (Snowflake) and a per-conversation `seq` used for ordering and gap detection.

---

## 4. Data Model

Choice: **NoSQL wide-column (Cassandra)** for messages, **Redis** for ephemeral state, a small **relational store** (or DynamoDB) for user/group metadata.

### Why Cassandra for messages
- Write-heavy (1.7M w/s peak), append-mostly, no cross-row transactions needed.
- Natural partition key = conversation; messages ordered by clustering key.
- Linear horizontal scalability, multi-DC replication, no single primary → survives AZ/region loss.
- Tunable consistency (`QUORUM` writes, `QUORUM`/`ONE` reads) lets us trade latency vs. consistency per call.

```sql
-- Messages: partition by conversation, cluster by time-ordered seq (DESC for recent-first reads)
CREATE TABLE messages (
  conversation_id   text,
  bucket            int,          -- time bucket (e.g. yyyymm) to cap partition size
  seq               bigint,       -- per-conversation monotonic sequence
  message_id        bigint,       -- Snowflake (global, sortable)
  sender_id         bigint,
  ciphertext        blob,         -- E2EE payload; server cannot read
  media_ref         text,
  created_at        timestamp,
  PRIMARY KEY ((conversation_id, bucket), seq)
) WITH CLUSTERING ORDER BY (seq DESC);

-- Per-user inbox / mailbox model for fast "what's new for me" sync (esp. groups)
CREATE TABLE user_inbox (
  user_id           bigint,
  conversation_id   text,
  last_seq          bigint,       -- highest seq delivered to this user
  unread_count      int,
  PRIMARY KEY (user_id, conversation_id)
);

-- Message status (delivered/read) — high write volume, optional/aggregatable
CREATE TABLE message_status (
  message_id        bigint,
  user_id           bigint,
  status            text,         -- DELIVERED | READ
  updated_at        timestamp,
  PRIMARY KEY (message_id, user_id)
);
```

### Group & user metadata — relational / DynamoDB
Group membership, profiles, and settings are read-heavy, low-write, and benefit from secondary indexes. A relational store (sharded Postgres) or DynamoDB works; this data is small and cacheable in Redis.
```sql
groups(group_id PK, name, created_at, type)
group_members(group_id, user_id, role, joined_at)   -- PK (group_id, user_id)
users(user_id PK, phone, identity_pubkey, push_token, ...)
```

### Ephemeral state — Redis
- **Presence**: `presence:{user_id}` → status, with TTL; refreshed by heartbeats.
- **Routing table**: `route:{user_id}` → `{gateway_id, conn_id}` so any service can find which connection server holds a user.
- **Typing indicators**: pub/sub only, never persisted.

**Hot-partition guard:** a single very active group could create a giant Cassandra partition. The `bucket` (e.g. `yyyymm`) caps partition size; super-large channels additionally shard by `(conversation_id, bucket, sub_partition)`.

---

## 5. High-Level Architecture

```
                         ┌──────────────────────────────────────────────┐
   Mobile / Web          │                                              │
   clients               ▼                                              │
  ┌────────┐      ┌───────────────┐     ┌──────────────────┐            │
  │ Client │◄────►│  Load Balancer │────►│  Connection /    │   persistent WS
  │ (WS)   │      │  (L4 + sticky) │     │  Gateway Servers  │◄───────────┘
  └────────┘      └───────────────┘     │  (stateful, hold  │
       ▲                                │   100k WS each)   │
       │ APNs/FCM                       └─────────┬─────────┘
       │                                          │ (decode, auth, idempotency)
  ┌──────────┐                                    ▼
  │  Push    │◄────────┐                  ┌──────────────────┐
  │  Service │         │                  │  Chat / Message   │
  └──────────┘         │                  │  Service (stateless)│
       ▲               │                  └───┬───────────┬────┘
       │ offline       │                      │           │
       │               │           ┌──────────▼──┐    ┌───▼────────┐
  ┌──────────────┐     │           │  Sequencer / │    │  Fan-out   │
  │ Presence Svc │     │           │  ID Gen      │    │  Service   │
  │  (Redis)     │     │           │ (Snowflake)  │    └───┬────────┘
  └──────────────┘     │           └──────┬───────┘        │
       ▲               │                  │                │
       │ route lookup  │           ┌──────▼────────────────▼─────┐
  ┌────┴─────────┐     │           │      Kafka (message bus)     │
  │ Routing /    │     └───────────┤  topic per shard, ordered    │
  │ Session Reg  │                 └──────┬───────────────┬───────┘
  │  (Redis)     │                        │               │
  └──────────────┘              ┌─────────▼──┐      ┌──────▼─────────┐
                                │ Cassandra   │      │ Delivery       │
   ┌──────────────┐             │ (messages,  │      │ Workers →      │
   │ Object Store │             │  inbox, RF=3│      │ push to online │
   │  (S3) + CDN  │             │  multi-DC)  │      │ conns via      │
   │  media path  │             └─────────────┘      │ Routing Reg    │
   └──────────────┘                                  └────────────────┘
```

### Component walkthrough
1. **Load balancer** — L4 (or L7 with WS support). Distributes new WS connections; sticky only at connect time. Health-checks gateways.
2. **Connection / Gateway servers** — *stateful*, hold the long-lived WebSockets (~100k each). Authenticate, parse frames, enforce idempotency, and forward business events to the stateless chat service. On connect they register `route:{user_id} → {gateway_id}` in the Routing Registry so others can reach this user. This is the only stateful tier; keep it dumb.
3. **Chat / Message service** — *stateless* business logic: validate, request a Snowflake id + per-conversation `seq`, persist to Cassandra, and publish to Kafka. Horizontally scalable.
4. **Sequencer / ID generation** — assigns globally-sortable `message_id` (Snowflake) and a per-conversation monotonic `seq` for gap detection.
5. **Kafka** — durable, ordered message bus. Partitioned by conversation/shard so a conversation's events stay ordered. Decouples ingestion from delivery and absorbs spikes.
6. **Fan-out service** — for groups, expands one message into N per-recipient deliveries (write to each `user_inbox`, enqueue delivery).
7. **Delivery workers** — consume Kafka, look up each recipient's gateway via Routing Registry, push over the live WS. If the user is offline, hand off to Push.
8. **Presence service** — Redis-backed; tracks online/last-seen via heartbeats with TTL; publishes presence changes to interested subscribers.
9. **Push service** — bridges to APNs/FCM for offline/backgrounded recipients.
10. **Object store + CDN** — media uploaded directly via pre-signed URLs; only a `media_ref` flows through the message pipe.
11. **Cassandra** — durable message + inbox store, RF=3, multi-DC.

---

## 6. Deep Dives

### 6.1 Message delivery flow & guarantees (exactly-once *effect*)
True exactly-once delivery is impossible over a lossy network, so we engineer **at-least-once delivery + idempotent dedup = exactly-once effect**.

```
1. Client SEND { client_msg_id=UUID }  ─► Gateway
2. Gateway → Chat svc: check dedup cache keyed by (sender, client_msg_id)
3. Chat svc: assign message_id + seq, write Cassandra (QUORUM), publish Kafka
4. Chat svc → Gateway → Client:  ACK { status=SENT, message_id, seq }   (sender now shows ✓)
5. Delivery worker consumes Kafka, finds recipient route, pushes MESSAGE
6. Recipient device ACK { status=DELIVERED } ─► writes message_status, notifies sender (✓✓)
7. Recipient opens chat → READ ─► message_status, notifies sender (blue ✓✓)
```
- **Persist before ack:** step 3 finishes the Cassandra `QUORUM` write *before* step 4. The sender never sees ✓ for a message that wasn't durably stored → **no acknowledged loss**.
- **Dedup:** the `(sender_id, client_msg_id)` pair is cached (Redis, short TTL) and is the Cassandra natural key guard. A retried SEND maps to the same `message_id` → no duplicate.
- **Gap detection:** clients track the last `seq` per conversation. A jump (4470 → 4472) triggers a history fetch to fill the hole — recovers from missed pushes without a global resend.

### 6.2 Online presence at scale
Naive presence (every status change broadcast to every contact) is a write storm: 500M users × dozens of contacts = billions of fan-out events on every app foreground/background.
- **Heartbeat + TTL:** client sends a heartbeat every ~30s; Redis key `presence:{uid}` has a 45s TTL. No heartbeat → key expires → offline. No explicit "I'm offline" message needed (handles crashes/network drops).
- **Pull, don't always push:** presence is *pulled* when a user opens a chat list / conversation, rather than pushed to all contacts on every change. Push only for currently-open conversations.
- **Debounce flapping:** suppress online↔offline oscillation on flaky networks with a short grace window.
- **Sharded presence cluster:** Redis Cluster partitioned by `user_id`. At 100M concurrent users and 30s heartbeats that's ~3.3M heartbeat writes/s — sharded across the cluster, each node sees a manageable slice.

### 6.3 Group fan-out: write vs. read amplification
The central group-chat trade-off, analogous to Twitter timeline fan-out.

| Strategy | How | Best for | Cost |
|---|---|---|---|
| **Fan-out on write** | On send, write to every member's `user_inbox` + push to online members | Small groups (≤256, WhatsApp) | N writes per message; bad for huge channels |
| **Fan-out on read** | Store message once; members pull from the shared conversation timeline on open | Huge channels (100k members, Slack) | Read-heavy; needs caching |
| **Hybrid** | Fan-out on write for small/active groups; fan-out on read for large channels | General platform | Threshold-based routing |

**Recommendation:** hybrid with a member-count threshold (e.g. fan-out-on-write under ~1,000 members, fan-out-on-read above). For a 100k-member Slack channel, fan-out-on-write would mean 100k inbox writes + 100k pushes per message — catastrophic; instead store once and let the (typically small) set of *currently-connected* members read from the shared timeline, pushing only to live connections.

### 6.4 Connection management & routing
- **Stateful gateways, stateless everything else.** Gateways own the sockets; business services stay restartable/scalable.
- **Routing Registry (Redis):** `route:{user_id} → {gateway_id, conn_id, last_seen}`. Delivery workers look this up to find which gateway holds a recipient. On reconnect to a different gateway, the entry is overwritten.
- **Reconnection & resume:** client stores `last_seq` per conversation; on reconnect it sends these and the server replays anything newer from Cassandra/Kafka — no full resync.
- **Backpressure:** if a client's send buffer fills (slow consumer), the gateway drops the live push and lets the client recover via history fetch rather than OOM-ing the gateway.
- **Graceful drain:** rolling deploys drain a gateway by signaling clients to reconnect (they hit the LB and land on another node), avoiding a thundering-herd reconnect storm via jittered backoff.

### 6.5 End-to-end encryption (Signal protocol)
WhatsApp uses the **Signal protocol** (X3DH + Double Ratchet). The server is a blind relay for ciphertext + a key-distribution directory.
- **Key directory:** each user uploads an *identity key*, a *signed prekey*, and a batch of *one-time prekeys*. To start a session, the sender fetches the recipient's prekey bundle (`GET /v1/keys/{uid}`).
- **X3DH** establishes a shared secret asynchronously (recipient can be offline). **Double Ratchet** rotates keys per message → forward secrecy + post-compromise security.
- **Group E2EE** uses *sender keys*: each member distributes a sender key once; subsequent group messages are encrypted once with that key rather than pairwise per member (avoids O(N) per-message encryption).
- **Multi-device:** each device has its own key; a message is encrypted separately per device (companion-device model). The server never holds private keys.
- **Trade-off:** E2EE forbids server-side search, smart-reply, and content moderation. Slack deliberately does *not* use E2EE so it can offer full-text search and compliance/eDiscovery — a product decision, not just a technical one.

---

## 7. Scaling, Bottlenecks & Failure Handling

### What breaks first (in order)
1. **Connection servers (FD/memory).** Each holds ~100k sockets; at 100M concurrent you need ~1–2k nodes. Scale horizontally; they're the memory-bound tier. Mitigate with efficient epoll-based servers and lean per-connection state.
2. **Fan-out for large groups.** A celebrity/all-company message multiplies one write into N deliveries. Mitigate with the hybrid write/read strategy (6.3) and rate-limited fan-out workers.
3. **Cassandra hot partitions.** A hyperactive conversation concentrates writes on one partition's token range. Mitigate with time `bucket`s and sub-partitioning of mega-channels.
4. **Presence write storm.** Mass foreground/background events. Mitigate with TTL-expiry model and pull-based reads (6.2).

### Scaling levers
- **Sharding:** Cassandra partitions by `conversation_id` (+bucket); Kafka topics partitioned by conversation so per-conversation order is preserved; gateways scaled by adding nodes behind the LB.
- **Replication & DR:** Cassandra RF=3 across AZs, multi-DC replication across regions (e.g. `NetworkTopologyStrategy`). `QUORUM` writes survive one AZ failure. Kafka with RF=3 and `min.insync.replicas=2`. Active-active regions with conflict-free per-conversation ordering (seq assigned in the conversation's home region).
- **Caching:** Redis for routing table, presence, dedup cache, group membership, and recent-message read-through cache.
- **Circuit breakers & isolation:** if the Push service degrades, gateways/chat keep working (push is best-effort, async). If Cassandra latency spikes, shed read load (serve from cache) but never ack a write before it's durable. Bulkhead the media path entirely from messaging.
- **Load shedding & rate limiting:** per-user send rate limits stop spam/abuse from saturating fan-out. Kafka acts as a shock absorber — ingestion can outrun delivery temporarily; backlog drains during quieter periods.

### Failure scenarios
| Failure | Behavior |
|---|---|
| Gateway crashes | Clients detect dead socket, reconnect via LB to another gateway, resume from `last_seq`. Routing entries expire by TTL. |
| Recipient offline | Message persisted in Cassandra + `user_inbox`; Push service pings APNs/FCM; delivered on next connect. |
| AZ outage | `QUORUM` reads/writes continue from surviving replicas; gateways in other AZs absorb reconnects. |
| Kafka broker loss | RF=3 + ISR=2 → no data loss; consumers rebalance partitions. |
| Region outage | Traffic fails over to another region; Cassandra cross-DC replication has the data (eventual cross-region consistency, strong within region). |

---

## 8. Trade-offs & Alternatives

- **Cassandra vs. DynamoDB vs. HBase.** Cassandra: no single point of failure, multi-DC, tunable consistency, great for write-heavy append workloads — chosen. DynamoDB: less operational burden (managed) but vendor lock-in and cost at this scale. HBase: strong consistency but a master/RegionServer architecture with worse multi-DC story. For zero-ack-loss + multi-region, Cassandra's masterless model wins.
- **WebSocket vs. long-polling vs. SSE.** WebSocket is bidirectional and low-overhead — the right call for chat. Long-polling is a fallback for restrictive networks. SSE is server→client only (can't carry sends). MQTT (what Facebook Messenger historically used) is another option: lighter on mobile/battery, but WebSocket is more universal.
- **Kafka in the path vs. direct delivery.** Kafka adds a few ms of latency but buys durability, ordering, replay, and spike absorption. For p99 < 200ms we keep the sender ack on the synchronous Cassandra write and let Kafka handle async fan-out/delivery — so Kafka latency doesn't gate the sender ack.
- **Push vs. read receipts cost.** Read receipts double event volume. At 100x scale you'd make them aggregated/optional, or batch status updates per conversation rather than per message.
- **E2EE vs. server features.** WhatsApp chooses privacy (no server-side search/moderation). Slack chooses functionality (plaintext at rest, full-text search, compliance). State the requirement explicitly; the entire data model and feature set hinge on it.

### At 10x / 100x scale
- **10x (500B msg/day):** more Cassandra/gateway nodes; introduce regional cell-based architecture (users pinned to a home region/cell) to cap blast radius and cross-region chatter. Tiered storage — move cold messages to cheaper object storage.
- **100x:** the fan-out and presence systems dominate. Move to fan-out-on-read by default for anything but tiny groups, aggressive presence pull-only, and per-cell Kafka/Cassandra so no global component is a bottleneck. Consider QUIC/HTTP3 to cut mobile reconnection cost.

---

## Interview Q&A by Level

### 🟢 Basic
**Q: Why WebSockets instead of HTTP polling?**
A: Chat needs server-initiated, low-latency, bidirectional pushes. HTTP polling wastes requests and adds latency; WebSocket keeps one persistent full-duplex connection so the server can push a message the instant it arrives. Long-polling is only a fallback for networks that block WS.

**Q: How does a message get from sender to receiver at a high level?**
A: Client sends over its WS to a gateway → chat service assigns an id/seq and persists to Cassandra (then acks the sender) → publishes to Kafka → a delivery worker looks up the recipient's gateway in the routing registry and pushes over their live WS, or triggers a push notification if they're offline.

**Q: What are the three message states?**
A: *Sent* (server durably stored it, sender sees ✓), *Delivered* (recipient device received it, ✓✓), *Read* (recipient opened the chat, blue ✓✓). Each later state is reported back from the recipient device.

### 🟡 Intermediate
**Q: How do you guarantee ordering within a conversation?**
A: Assign a per-conversation monotonic `seq`, partition Kafka by conversation (so its events stay in one ordered partition), and use `seq` as the Cassandra clustering key. Clients detect gaps via `seq` discontinuities and backfill from history. Cross-conversation order is not guaranteed and doesn't need to be.

**Q: How do you avoid duplicate messages on retries?**
A: The client attaches a `client_msg_id` (UUID). The server caches `(sender_id, client_msg_id)` and uses it as a dedup key; a retried send maps to the same server `message_id`, so it's stored and delivered once — at-least-once delivery + idempotent dedup = exactly-once effect.

**Q: How does presence work without a write storm?**
A: Clients heartbeat every ~30s into a Redis key with a ~45s TTL; expiry = offline (handles crashes). Presence is *pulled* when a user views a chat list and pushed only for currently-open conversations, instead of broadcasting every change to all contacts.

**Q: Why is the connection tier separate from the business logic?**
A: Connections are stateful (long-lived sockets, memory/FD bound) and need careful drain/reconnect handling; business logic is stateless and freely scalable/restartable. Splitting them lets each scale on its own axis and keeps deploys safe.

### 🟠 Advanced
**Q: How do you handle group fan-out for both a 50-person WhatsApp group and a 100k-member Slack channel?**
A: Hybrid by member count. Small groups: fan-out-on-write — write to each member's `user_inbox` and push to online members. Large channels: fan-out-on-read — store the message once in the shared timeline and let currently-connected members read from it, pushing only to live connections. Writing to 100k inboxes per message would be catastrophic, so we never do that for mega-channels.

**Q: A user reconnects after 10 minutes offline on a different gateway. How do they catch up?**
A: The client persists `last_seq` per conversation. On reconnect (landing on any gateway via the LB) it sends those values; the server replays everything newer from Cassandra/`user_inbox`. The routing registry entry is overwritten to point at the new gateway. No full resync, no duplicate delivery.

**Q: How does end-to-end encryption change the architecture?**
A: With Signal-protocol E2EE the server only relays ciphertext and runs a prekey directory; it holds no private keys and cannot read content. Consequences: no server-side search, smart replies, or content moderation; group messaging uses sender keys to avoid O(N) per-message encryption; multi-device means per-device encryption. Slack opts out of E2EE precisely to keep server-side search and compliance.

**Q: Where do you put the durability boundary so you never lose an acknowledged message?**
A: The sender's ✓ is sent only *after* a Cassandra `QUORUM` write succeeds. Kafka and delivery happen asynchronously after that. So even if delivery, push, or a gateway fails, the message is durably stored and will be delivered on the recipient's next connect.

### 🔴 Expert
**Q: Design for active-active multi-region while keeping per-conversation ordering.**
A: Pin each conversation to a *home region* that owns its `seq` assignment, eliminating cross-region ordering conflicts. Cassandra replicates across regions (`NetworkTopologyStrategy`, `LOCAL_QUORUM` for low-latency local writes, async cross-region). Reads/writes for a conversation route to its home region; other regions serve replicas with eventual consistency. A cell/shard map (in a globally-replicated config store) tells gateways where each conversation lives. Failover reassigns a region's conversations to a backup region, accepting a brief seq-assignment pause to preserve order.

**Q: At 100x scale (5T messages/day), what's your biggest bottleneck and how do you re-architect?**
A: Fan-out and presence dominate, and any global component (a shared Kafka cluster, a global routing registry) becomes the limit. Re-architect into **cells**: self-contained units (gateways + chat svc + Kafka + Cassandra + Redis) each serving a slice of users, with users pinned to a home cell. Cross-cell messaging goes through a thin inter-cell router. This caps blast radius, removes global bottlenecks, and lets you add capacity by adding cells. Default to fan-out-on-read except for tiny groups, make read receipts aggregated/optional to halve event volume, tier cold messages to object storage, and adopt QUIC/HTTP3 to cut mobile reconnection cost.

**Q: How would you debug a spike in p99 delivery latency affecting only group chats?**
A: Isolate the stage: check fan-out worker lag and Kafka consumer lag per partition (a hot conversation may pin one partition), Cassandra `user_inbox` write latency and hot-partition metrics, and routing-registry lookup latency. Likely culprits: a hot Kafka partition for a viral group (mitigate by sub-partitioning the mega-channel and switching it to fan-out-on-read), or `user_inbox` hot partitions. Confirm with per-stage tracing (span timings sender-ack → Kafka publish → delivery push) and compare 1:1 vs. group paths to localize the regression.

**Q: How do you prevent a slow or malicious client from degrading a gateway?**
A: Bound per-connection send buffers and apply backpressure — if a client can't keep up, drop the live push and let it recover via history fetch rather than buffering unboundedly (which would OOM the gateway). Enforce per-user send and reconnection rate limits, use jittered backoff on reconnect to avoid thundering herds, and isolate gateways so one bad client/tenant can't starve others (bulkheading). Kafka absorbs ingestion spikes so a burst doesn't propagate synchronously downstream.
