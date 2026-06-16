# Design a Collaborative Document Editor (Google Docs)

> A worked, interview-grade design of a real-time collaborative document editor: many users edit the same document simultaneously, every keystroke converges to the same final state on every screen, and history is never lost — the core challenge being **concurrent conflict-free editing at low latency**.

[← Back to master index](../../README.md) · [← System Design index](../README.md)

---

## 1. Requirements

A collaborative editor sounds like "a textarea in the cloud," but the interviewer is probing the one genuinely hard problem in this space: **how do N people edit the same text at the same time and all converge to an identical document without a lock?** Lead by separating the editing-convergence core from the surrounding plumbing (auth, storage, presence) before drawing anything.

### Functional requirements
- **Real-time co-editing**: multiple users edit one document concurrently; each sees others' changes within a fraction of a second.
- **Convergence**: all clients editing the same document end in *byte-identical* state regardless of the order edits arrive (eventual consistency on content).
- **Rich text**: not just plain characters — bold/italic, headings, lists, tables, images, links (formatting is an edit too).
- **Presence & cursors**: show who is in the doc, their live cursor position, and selection ("Alice is typing here").
- **Comments & suggestions**: anchored comments and tracked "suggested edits" mode.
- **Version history**: browse and restore any past revision; show who changed what.
- **Offline editing**: edit while disconnected; reconcile on reconnect without losing work.
- **Sharing & permissions**: owner/editor/commenter/viewer roles; link sharing; per-doc ACLs.

### Non-functional requirements
- **Scale**: 100M DAU, ~50M documents edited/day, **~10M concurrent editing sessions**, peak collaborators per doc ~50 active (hard cap ~100). Average doc ~30 KB, p99 doc ~2 MB.
- **Latency**: local echo of your own keystroke **< 16 ms** (instant, optimistic); remote edit visible to collaborators **p99 < 200 ms** same-region; cursor/presence **< 100 ms**.
- **Availability**: **99.99%** — a doc that won't open or save mid-edit is a data-loss event in the user's eyes.
- **Durability**: **zero acknowledged-edit loss**. Once a client's edit is acked, it must survive a server crash. Target 11 nines of storage durability (object-store class).
- **Consistency**: **strong convergence** of document content (every replica converges to the same value) but the path is *eventually consistent / optimistic* — we never block a keystroke on a server round-trip. Permissions are strongly consistent (you must not edit a doc you were just removed from).
- **Security**: per-document ACL enforced server-side on every op; TLS in transit; encryption at rest; audit log of edits for enterprise/compliance.

### Clarifying questions a strong candidate asks
1. **What's the convergence model — OT or CRDT?** This is *the* architectural fork and dictates the server, the data model, and the offline story. (Google Docs uses Operational Transformation; Figma/Notion-style tools lean CRDT.)
2. **How many simultaneous editors per document?** 50 is very different from 5,000 — it changes whether you can fan out every op to everyone or need a different model.
3. **Plain text or rich text / arbitrary structure?** Rich text means ops over a tree/attributed model, not just a string — far harder transforms.
4. **Is robust offline editing required?** Offline + late reconnect strongly favors CRDTs (commutative merge) over server-coordinated OT.
5. **Do we need a full, restorable version history, or just "current state"?** Drives whether we persist the operation log forever or just snapshots.
6. **Real-time only, or also async (comments, suggestions)?** Suggestions/comments need stable anchors that survive concurrent edits.
7. **Single region or global collaboration?** A doc edited from SF and Singapore at once raises the latency floor and pushes you toward a per-doc home region.

> The first question is load-bearing. The entire rest of the design — server statefulness, the data model, the failure story — pivots on OT vs CRDT, so resolve it early and justify the choice.

---

## 2. Capacity Estimation

Real numbers, shown long-hand. Assume a 5-year horizon.

### Edit (write) QPS
A burst of typing is ~5–8 keystrokes/sec, but clients **batch/coalesce** ops (~one op flush every 50–100 ms) so the server sees far fewer ops than keystrokes.
```
Concurrent editing sessions          = 10,000,000
Fraction actively typing at an instant ≈ 10%  → 1,000,000 active typists
Op flushes per active typist          ≈ 5 ops/sec (after client coalescing)
Inbound op QPS  = 1,000,000 × 5       = 5,000,000 ops/sec  (~5M WPS avg)
Peak (~2.5×)                          ≈ 12,500,000 ops/sec
```
This is the dominant load and it is *write-heavy at the op layer* — unusual for consumer systems. The whole design optimizes ingesting + transforming + fanning out ops cheaply.

### Fan-out (the real amplifier)
Each accepted op must be broadcast to every *other* collaborator in that doc.
```
Avg active collaborators per editing doc ≈ 4
Fan-out factor                           = (4 - 1) = ~3 outbound per inbound op
Outbound op deliveries (peak) = 12.5M × 3 = ~37,500,000 deliveries/sec
```
A 50-person doc means 49× amplification for that doc — fan-out, not ingest, is what breaks first on hot documents.

### Read QPS (document opens / loads)
```
50M docs edited/day, but opens (incl. viewers, refreshes) ~5× edits = 250M opens/day
250,000,000 ÷ 86,400 ≈ 2,900 opens/sec  (~3K avg)
Peak ~3×                                ≈ 9,000 opens/sec
```
Opens are cheap relative to the op stream — each is a snapshot read + tail of the op log.

### Storage over 5 years
Two streams: **snapshots** (compacted current state) and the **op log** (history).
```
Documents in 5 yrs (50M created/day is high; assume 10M new docs/day):
  10,000,000/day × 365 × 5 = 18,250,000,000 ≈ 18.25B docs
Snapshot storage:
  18.25B × 30 KB avg        ≈ 5.5 × 10^14 B ≈ 550 TB  (current state)
Op-log / history storage (the big one):
  Ops/day = 5M ops/s × 86,400 ≈ 4.3 × 10^11 ops/day
  Avg op record ~ 80 B (type, position, payload ref, author, lamport ts)
  Daily op log   = 4.3e11 × 80 B ≈ 34 TB/day
  5-yr op log    = 34 TB × 365 × 5 ≈ 62 PB raw
With RF=3:        ≈ 186 PB
```
History dominates by two orders of magnitude. Mitigation: **periodic snapshot + log compaction** (keep recent fine-grained ops, roll old ops into squashed revisions and tier them to cold object storage).

### Bandwidth
```
Inbound:  12.5M ops/s × 80 B            ≈ 1.0 GB/s
Outbound: 37.5M deliveries/s × 80 B     ≈ 3.0 GB/s + framing/protocol overhead
```
Modest in bytes — the cost is *message rate and CPU for transforms*, not raw bandwidth. Media (embedded images) goes out-of-band to object storage + CDN and never rides the op stream.

### Memory — server-side document model (the expensive resource)
For OT, the server holds the authoritative state of each *active* doc in memory.
```
Active editing docs (distinct)        ≈ 10M sessions / 4 collaborators ≈ 2.5M active docs
In-memory model per active doc        ≈ 100 KB (text + structure + recent op window + cursors)
Total hot model RAM = 2.5M × 100 KB    ≈ 250 GB
Per editing server holding ~5,000 docs → 2.5M / 5,000 = 500 doc-servers (the stateful tier)
```
~500–800 stateful "doc server" nodes is the floor; the rest of the fleet (gateways, storage, presence) scales independently.

---

## 3. API Design

WebSocket for the real-time op channel (bidirectional, low-overhead). REST/gRPC for setup, snapshot load, history, sharing, and media.

```
# ---- Session bootstrap (REST) ----
POST /v1/documents/{doc_id}/sessions
  Authorization: Bearer <token>
  → { ws_url, session_id, doc_server_id,
      snapshot: { revision: 4471, content_ref: "s3://...", baseline_clock },
      collaborators: [ {user_id, color, cursor} ] }

# ---- WebSocket frames (after auth handshake on ws_url) ----
# Client → Server
{ "type": "OP",       "client_op_id": "uuid", "doc_id": "d1",
  "base_revision": 4471, "ops": [ {"insert":"Hello","at":120,"attrs":{"bold":true}} ] }
{ "type": "CURSOR",   "doc_id": "d1", "range": [120, 125] }
{ "type": "ACK_RECV", "up_to_revision": 4480 }          # client confirms it applied up to rev
{ "type": "PRESENCE", "doc_id": "d1", "status": "ACTIVE" }

# Server → Client
{ "type": "OP_ACK",   "client_op_id": "uuid", "revision": 4481 }   # your op committed at rev
{ "type": "OP",       "doc_id": "d1", "revision": 4481,
  "author": "u9", "ops": [ ... transformed against any concurrent ops ... ] }
{ "type": "CURSOR",   "doc_id": "d1", "user_id": "u9", "range": [200, 200] }
{ "type": "PRESENCE", "doc_id": "d1", "user_id": "u7", "status": "LEFT" }
{ "type": "RESYNC",   "from_revision": 4400 }            # server asks client to catch up

# ---- History, snapshot, sharing, media (REST/gRPC) ----
GET  /v1/documents/{doc_id}?revision=4471      # load a snapshot at a revision
GET  /v1/documents/{doc_id}/ops?from=4471&to=4481   # op range for catch-up
GET  /v1/documents/{doc_id}/history            # named revisions / restore points
POST /v1/documents/{doc_id}/restore { revision: 4400 }
PUT  /v1/documents/{doc_id}/acl    { user_id, role }   # owner|editor|commenter|viewer
POST /v1/media:initUpload          → { upload_url, media_ref }   # pre-signed PUT
```

**Design notes:** every client op carries a `client_op_id` (UUID) for **idempotent dedup** and a `base_revision` — the server revision the op was authored against — which is exactly what the transform engine needs to rebase the op onto the current state. The server replies with `OP_ACK` carrying the committed `revision`, establishing a single global order per document. `RESYNC` is the escape hatch: if a client falls too far behind or detects a gap, it reloads a snapshot + op tail rather than trying to patch forward.

---

## 4. Data Model

Three distinct stores, each matched to its access pattern.

### 1. Operation log — the source of truth (append-only)
The document's history *is* the ordered sequence of committed ops; current state is a fold over that log. This is event-sourcing. The access pattern is **append + ordered range scan by `(doc_id, revision)`**, write-heavy, no joins — a textbook **wide-column store (Cassandra/Bigtable)** or an append-only log (Kafka) fronting it.

```sql
-- Op log: partition by document, cluster by monotonic revision
CREATE TABLE document_ops (
  doc_id        text,
  bucket        int,          -- revision bucket (e.g. revision/10000) to cap partition size
  revision      bigint,       -- per-document monotonic, the global order for this doc
  client_op_id  uuid,         -- idempotency / dedup key
  author_id     bigint,
  base_revision bigint,       -- revision the op was authored against (for audit/replay)
  op_payload    blob,         -- the transformed op(s): inserts/deletes/format ranges
  lamport_ts    bigint,       -- logical clock (tie-break / CRDT ordering)
  created_at    timestamp,
  PRIMARY KEY ((doc_id, bucket), revision)
) WITH CLUSTERING ORDER BY (revision ASC);
```

### 2. Snapshots — compacted current state (for fast open)
Replaying 4 million ops to open a doc is absurd. Periodically (every N ops or T seconds) persist a **materialized snapshot** of the document model to object storage; loading a doc = newest snapshot + replay only the small op tail since it.
```sql
CREATE TABLE document_snapshots (
  doc_id        text,
  revision      bigint,        -- the op revision this snapshot reflects
  content_ref   text,          -- s3://.../doc_id/rev.bin  (the serialized model)
  size_bytes    bigint,
  created_at    timestamp,
  PRIMARY KEY (doc_id, revision)
) WITH CLUSTERING ORDER BY (revision DESC);
```
- Snapshot blobs live in **object storage (S3/GCS)** — cheap, durable (11 nines), CDN-frontable for read-only viewers.
- Keep the last few snapshots so a `restore` to a recent revision is a blob fetch, not a replay.

### 3. Metadata & permissions — relational
Sharing, ACLs, ownership, folders, and titles are read-heavy, low-write, relational, and need transactions + secondary indexes. A **sharded relational store (Postgres/Spanner) or DynamoDB**, cached in Redis.
```sql
documents(doc_id PK, owner_id, title, current_revision, created_at, updated_at)
document_acl(doc_id, principal_id, role, granted_by, PRIMARY KEY(doc_id, principal_id))
comments(comment_id PK, doc_id, anchor_ref, author_id, body, resolved, created_at)
```

### 4. Ephemeral state — Redis
- **Doc routing**: `route:{doc_id} → {doc_server_id}` — which stateful server currently owns this doc.
- **Presence/cursors**: `presence:{doc_id}` → set of `{user_id, color, cursor, last_seen}`, TTL-expired; cursors are pub/sub only, never persisted.
- **Op dedup cache**: `(doc_id, client_op_id)` short-TTL guard against retries.

**Why this split:** the op log wants cheap ordered appends at millions/sec (Cassandra/Kafka); snapshots want cheap durable bulk blobs (object store); permissions want transactional correctness (relational); live cursors want microsecond ephemeral state (Redis). No single engine is good at all four.

---

## 5. High-Level Architecture

```
        Browsers / Mobile (each runs a local document model + transform engine)
                                  │  persistent WebSocket
                                  ▼
                    ┌──────────────────────────────┐
                    │   Load Balancer (L7, WS)       │  routes by doc_id (consistent hash)
                    └───────────────┬───────────────┘
                                    │
                    ┌───────────────▼────────────────┐        ┌───────────────────┐
                    │   Connection / Gateway tier      │◄──────►│  Auth + ACL svc    │  (per-op
                    │   (stateless, holds WS sockets)  │        │  (perm check)      │   permission)
                    └───────────────┬────────────────┘        └───────────────────┘
                                    │ forward op to the doc's owner
                    ┌───────────────▼────────────────────────────────┐
                    │     Document Server tier  (STATEFUL)             │
                    │  - owns authoritative in-mem model per doc       │
                    │  - Operational Transform engine (serialize +     │
                    │    transform every op against concurrent ops)    │
                    │  - assigns the per-doc monotonic `revision`      │
                    └───┬───────────────┬───────────────────┬─────────┘
            commit op   │               │ broadcast         │ snapshot/persist
        ┌───────────────▼──┐    ┌───────▼────────┐   ┌──────▼───────────────┐
        │  Op Log (Kafka →  │    │  Fan-out to     │   │  Snapshot Service    │
        │  Cassandra),      │    │  other collab   │   │  (materialize model  │
        │  append-only      │    │  WS connections │   │  → S3, periodic)     │
        └───────────────────┘    └────────────────┘   └──────┬───────────────┘
                                                              ▼
   ┌──────────────┐   ┌──────────────┐                ┌──────────────┐
   │  Redis        │   │ Metadata DB   │                │ Object Store │
   │ route/presence│   │ (ACL,titles,  │                │  (S3) + CDN  │
   │  /cursors     │   │  comments)    │                │  snapshots+  │
   └──────────────┘   └──────────────┘                │  media       │
                                                       └──────────────┘
```

### Component walkthrough (request flow of one keystroke)
1. **Client** applies the edit to its *local* model immediately (optimistic, < 16 ms — no network on the keystroke path), and sends an `OP {client_op_id, base_revision, ops}` over its WebSocket.
2. **Load balancer** routes WS connections; once connected, ops for a given `doc_id` must reach the single server that owns that doc. The LB (or gateway) consistent-hashes by `doc_id`.
3. **Gateway tier (stateless)** terminates the socket, authenticates the frame, performs an **ACL check** (is this user still an editor?), dedups by `client_op_id`, and forwards the op to the **document server** that owns `doc_id` (looked up via Redis `route:{doc_id}`).
4. **Document server (stateful)** is the heart: it holds the authoritative in-memory model for the doc and runs the **OT engine**. It serializes the op into the doc's single ordered timeline, **transforms** it against any concurrent ops committed since the op's `base_revision`, assigns the next `revision`, and applies it to its model.
5. It then (a) **appends** the committed op to the op log (Kafka → Cassandra) for durability/history, (b) **acks** the author (`OP_ACK {revision}`), and (c) **fans out** the transformed op to every other collaborator's gateway/socket.
6. **Snapshot service** periodically materializes the model to S3 and records a `document_snapshots` row so future opens are fast.
7. **Presence/cursors** flow over the same sockets but through Redis pub/sub — never persisted, never blocking the op path.

The key architectural decision visible here: the **document server is a stateful, single-owner-per-doc serialization point**. That single ownership is what makes ordering and transformation tractable (see Deep Dive 6.1).

---

## 6. Deep Dives

### 6.1 Operational Transformation (OT) vs CRDT — the central decision
Two users start from `"cat"`. User A inserts `"s"` at end → `"cats"`. Concurrently User B inserts `"!"` at end. Both ops were authored against revision 0. If the server naively applies both at "position 3," one clobbers the other and the clients diverge. The job of the convergence layer is to make these *commute* to the same result everywhere.

**Operational Transformation (Google Docs):**
- A **central server serializes** all ops for a doc into one order and **transforms** each incoming op against the ops that committed since its `base_revision`. B's "insert ! at 3" is transformed to "insert ! at 4" because A's "insert s at 3" shifted positions.
```
transform(op_b, op_a):
   if op_a.type == INSERT and op_a.pos <= op_b.pos:
       op_b.pos += op_a.length        # shift right past the earlier insert
   # deletes, overlaps, and format ranges have their own transform rules
   return op_b
```
- Client side runs the symmetric algorithm: it keeps a buffer of *sent-but-unacked* and *pending* ops, and when a remote op arrives it transforms it against those local ops before applying — so its optimistic local state stays consistent with what the server will decide.
- **Pros:** compact ops (just positions), the model the user sees is a plain document (no per-character metadata bloat), battle-tested in Google Docs/Wave.
- **Cons:** transform functions are *notoriously* hard to get right (rich text + tables explodes the case matrix), and correctness depends on a **single serialization point per doc** → the doc server is stateful and a per-doc bottleneck/SPOF you must engineer around.

**CRDT (Conflict-free Replicated Data Type — Figma, Yjs, Automerge):**
- Each character/element gets a globally-unique, densely-ordered identifier (e.g. fractional index or a tree position). Ops **commute by construction**: inserting "s" and "!" produces the same set regardless of order, because each has its own unique position id; ties broken by a site id / Lamport clock.
- **Pros:** no central transform server needed → peers can merge directly, **excellent offline + late-merge** story, naturally peer-to-peer/edge-friendly.
- **Cons:** **metadata overhead** — every character carries a position id and tombstone (deleted chars often can't be physically removed, only marked), bloating memory and requiring periodic garbage collection; reconciling intent for rich-text/structured edits is still subtle.

| | OT | CRDT |
|---|---|---|
| Coordinator | Central server per doc (serialization point) | None required (commutative merge) |
| Op size / overhead | Small (positions) | Larger (per-element ids + tombstones) |
| Offline / late merge | Harder (server must rebase) | Natural strength |
| Implementation risk | High (transform matrix) | High in a different way (GC, intent) |
| Used by | Google Docs, Wave | Figma, Yjs, Automerge, Notion-ish |

**Decision:** For a Google-Docs-style server-centric rich-text product with strong history and moderate offline needs, **choose OT** with a single authoritative doc server per document. If robust offline-first and peer/edge collaboration were primary, I'd choose a CRDT and accept the memory/GC cost. State the trade explicitly — interviewers want the *reasoning*, not a memorized answer.

### 6.2 The single-owner doc server: ordering, ownership, and failover
OT requires one serialization point per doc. We make each active document **owned by exactly one doc server** at a time (recorded in Redis `route:{doc_id}`).
- **Why single owner:** assigning the monotonic `revision` and transforming against concurrent ops is trivially correct when one process does it serially; doing it across replicas would require consensus on every keystroke — far too slow.
- **Ownership acquisition:** first edit acquires a short-lived **lease** (Redis lock / etcd) on `doc_id`; the gateway routes all ops for that doc to the lease holder.
- **Failover is the hard part.** If a doc server dies, its in-memory model is lost — but **never acked-op loss**, because every committed op was appended to the durable op log *before* the ack. Recovery: a new doc server acquires the lease, loads the latest snapshot, and **replays the op tail** from the log to rebuild the exact model, then clients `RESYNC` from their last-applied revision.
- **Durability boundary:** the `OP_ACK` to the author is sent only *after* the op is durably in the log (or at least in Kafka with RF=3, `acks=all`). So a crash between apply and ack at worst makes the client retry (idempotent via `client_op_id`); it never loses an acked edit.

### 6.3 Optimistic local editing & the client state machine
The < 16 ms local-echo requirement means we *cannot* wait for the server on a keystroke. Each client runs a small state machine:
```
local model = snapshot @ base_revision
buffer = []          # ops sent to server, not yet acked
pending = []         # ops typed locally, not yet sent

on local edit e:
    apply e to local model immediately        # instant echo
    if buffer empty: send e; buffer=[e]
    else: pending.append(e)                   # one in-flight op at a time per doc

on OP_ACK(rev):
    buffer = []
    if pending: send next; buffer=[pending.pop(0)]

on remote OP(op, rev):
    op' = transform(op, against buffer + pending)   # rebase remote op over my unacked work
    apply op' to local model
    also transform my buffer/pending against op (so future sends are correct)
```
This is the OT client algorithm (Google's "Jupiter" model): exactly **one op in flight per doc**, the rest queued, with bidirectional transform keeping optimistic local state and authoritative server state reconcilable. The user never feels latency; the math guarantees convergence once the dust settles.

### 6.4 Fan-out on hot documents & presence
- **Normal docs (≤ ~50 collaborators):** the owning doc server broadcasts each committed op directly to the other collaborators' gateways. With ~3 average collaborators this is cheap.
- **Hot docs (a 100-person all-hands doc, or a viral public doc with thousands of viewers):** broadcasting every op to thousands of sockets from one server saturates it.
  - **Viewers vs editors:** viewers don't need every keystroke — push them **coalesced updates** (batched every ~250 ms) or serve them snapshot+poll. Only active editors get the live op stream.
  - **Fan-out tier:** offload broadcast to a dedicated fan-out/delivery fleet subscribing to the doc's op stream (via Kafka topic per shard), so the stateful doc server only does transform+commit, not N-way socket writes.
  - **Op coalescing:** merge a rapid burst of single-char inserts into one combined op before broadcast to cut message rate.
- **Presence & cursors:** ride Redis pub/sub, heartbeat with TTL (no heartbeat in ~30 s → that collaborator's cursor disappears). Cursors are best-effort and never touch the op log or durability path — a dropped cursor update is invisible; a dropped op is not allowed.

### 6.5 Version history, snapshots & restore without storing everything forever
History = the op log, but 62 PB of raw ops is untenable, and users don't want per-keystroke granularity in the timeline.
- **Snapshot + compaction:** every N ops / T minutes, materialize the model to S3 and record a snapshot. Old fine-grained ops behind a settled snapshot get **squashed** into coarser "named revisions" (e.g. "Edits by Alice, 2:00–2:15 PM") and the raw ops are tiered to cold storage or dropped past a retention window.
- **Open path:** load newest snapshot + replay the (small) op tail → O(tail), not O(history).
- **Restore:** restoring to revision R doesn't rewrite history — it **appends** the inverse/replacement ops that transform current → state-at-R, preserving an auditable forward-only log (so "restore" is itself an undoable edit).
- **Comments/suggestion anchors** must survive concurrent edits: anchor a comment to a *stable position id / relative anchor* (between element A and B), not an absolute character offset, so an insert above it doesn't orphan the comment. This is one place CRDT-style stable ids help even in an OT system, so anchoring often uses a parallel stable-id scheme.

---

## 7. Scaling, Bottlenecks & Failure Handling

**What breaks first?** In order: (1) **fan-out on hot docs**, (2) **the per-doc stateful doc server** (single owner = single bottleneck for one doc), (3) **op-log write throughput / history storage**, (4) connection (gateway) memory, (5) snapshot/compaction lag.

- **Doc server is the scaling unit.** A single document's edit rate is capped by one process (one serialization point). Mitigate: this is rarely a problem because *human* edit rate per doc is bounded (50 people typing ≈ a few hundred ops/sec — trivial for one core). The risk is *many docs per server*, solved by spreading docs across the fleet via consistent hashing on `doc_id` and migrating hot docs to dedicated nodes.
- **Stateless gateways scale horizontally** behind the LB; add nodes for more connections. Doc servers scale by adding nodes and rebalancing doc ownership (lease handoff with a quick `RESYNC`).
- **Op log:** Kafka (partitioned by `doc_id`, RF=3, `min.insync.replicas=2`, `acks=all`) absorbs write spikes and is the durability boundary; Cassandra (RF=3, multi-DC) is the long-term ordered store. Both scale by adding partitions/nodes.
- **History storage** is the cost driver — control it with snapshotting, log compaction, retention windows, and tiering cold ops to object storage.
- **Replication & DR:** op log RF=3 across AZs; snapshots in S3 (cross-region replication). For global collaboration, pin each doc to a **home region** that owns its serialization; remote-region editors take the cross-region latency hit but still converge. Failover reassigns a region's docs to a backup region by re-leasing + replaying.
- **Circuit breakers / degradation:** if the snapshot service lags, keep editing (snapshots are an optimization, not the source of truth). If presence/Redis is down, drop cursors but keep editing. If the op log is unavailable, **stop acking** — better to make clients buffer locally (they can, optimistically) than to ack an op we can't durably store.

### Failure scenarios
| Failure | Behavior |
|---|---|
| Doc server crashes mid-edit | New server acquires the lease, loads latest snapshot, replays op-log tail to rebuild exact model; clients `RESYNC` from last-applied revision. No acked-op loss (op was logged before ack). |
| Client disconnects (offline) | Client keeps editing its local model; buffers ops. On reconnect it sends buffered ops with their `base_revision`; server transforms them against everything committed meanwhile and commits/acks. |
| Late reconnect, big gap | Server replies `RESYNC`; client reloads a fresh snapshot + op tail and rebases its unsent local ops onto it. |
| Two ops collide concurrently | OT transform rebases the later one (by revision order) → both survive, all clients converge. |
| Kafka broker loss | RF=3 + ISR=2 → no data loss; partitions rebalance. |
| Region outage | Doc's home region fails over to a backup; op log cross-region replicated; brief serialization pause during lease handoff. |

**Thundering herd on doc open** (e.g. a shared link goes viral): serve read-only viewers from a **CDN-cached snapshot** and a coalesced poll stream rather than admitting thousands as live editors; only promote to a live op-stream when they actually start editing.

---

## 8. Trade-offs & Alternatives

- **OT vs CRDT.** OT: compact ops, clean document model, but needs a central per-doc serialization point and brutal transform functions. CRDT: no coordinator and superb offline/merge, but per-element id + tombstone overhead and GC. **Chosen: OT** for a server-centric, history-rich, rich-text product (the Google Docs model); **CRDT** if offline-first or peer/edge collaboration were the priority.
- **Stateful doc servers vs fully stateless.** A stateful single-owner server makes ordering/transform trivially correct but introduces ownership, leasing, and failover complexity. The alternative — coordinating ordering across stateless replicas — would need consensus per keystroke (too slow). **Chosen: stateful single owner** with durable op log + snapshot replay for recovery.
- **Event-sourced op log vs storing only current state.** The op log gives free version history, audit, and crash recovery, at the cost of huge storage and a compaction pipeline. Storing only current state is cheaper but loses history and complicates recovery. **Chosen: op log + periodic snapshots** (event sourcing), with compaction/tiering to bound cost.
- **Optimistic local editing vs server-authoritative echo.** Optimistic editing is mandatory for the < 16 ms feel but forces the client transform machinery and a reconciliation protocol. Server-authoritative (wait for the round trip) would be trivially consistent but unusably laggy. **Chosen: optimistic** with one-op-in-flight reconciliation.
- **Consistency model (CAP).** Document *content* is **AP-leaning**: never block a keystroke; converge afterward (strong convergence, eventual path). *Permissions* are **CP**: a removed editor must be rejected immediately, even at the cost of a round trip — you cannot optimistically allow an edit the user is no longer permitted to make.

**At 10x scale (~125M ops/sec):** more doc-server and gateway nodes; aggressive op coalescing; demote all non-editing participants to coalesced/CDN snapshot streams; partition the op log more finely. The doc server itself rarely needs sharding because per-doc human edit rate is naturally bounded.

**At 100x scale / true global:** move to a **cell architecture** — self-contained units (gateways + doc servers + Kafka + Cassandra + Redis) each owning a slice of documents, with docs pinned to a home cell/region; a thin cross-cell router handles the rare cross-cell access. Push read-only collaboration to the **edge** (snapshots in edge KV + CDN), reserving origin doc servers for actual editors. Consider a **CRDT-at-the-edge** layer for offline/regional resilience while keeping OT at the authoritative core.

---

## Interview Q&A by Level

### 🟢 Basic
**Q. [Theory] Why can't two people just edit the same document with a lock, one at a time?**
A: A lock serializes editing — only one person types while everyone else waits, which defeats the entire point of real-time collaboration and feels terrible. The whole problem is letting everyone edit *simultaneously* and still converge to one consistent document. That requires conflict resolution (OT or CRDT), not mutual exclusion.

**Q. [Theory] Why use WebSockets instead of HTTP polling here?**
A: Collaboration is bidirectional and latency-sensitive — the server must *push* other people's edits and cursor moves the instant they happen, and the client streams ops continuously. A persistent full-duplex WebSocket does this with minimal per-message overhead; polling would add latency and waste requests. Long-polling is only a fallback for networks that block WS.

**Q. [Practical] How does typing feel instant if every edit goes to a server?**
A: It doesn't go to the server first. The client applies your keystroke to its **local model immediately** (optimistic, < 16 ms, no network), echoes it on screen, and *asynchronously* sends the op to the server. The server later acks and broadcasts it; the client's transform logic reconciles its optimistic state with the server's authoritative order. You never wait on the network to see your own typing.

### 🟡 Intermediate
**Q. [Theory] Explain Operational Transformation with a concrete example.**
A: Two users edit `"cat"` (revision 0). A inserts `"s"` at position 3 → server commits it as revision 1. B concurrently inserts `"!"` at position 3, authored against revision 0. The server transforms B's op against A's: since A inserted one character at/ before position 3, B's insert position shifts to 4, giving `"cats!"`. Every client transforms remote ops against its own unacked ops the same way, so all converge to identical text regardless of arrival order.

**Q. [Theory] OT vs CRDT — when would you pick each?**
A: OT uses a central server to serialize and transform ops; ops are compact and the document model is clean, but transform functions are hard and you need a single coordinator per doc. CRDTs give every element a unique position id so ops commute without a coordinator — great for offline/peer/edge merging — but carry per-element id + tombstone overhead and need garbage collection. Pick OT for a server-centric, history-rich product like Google Docs; pick CRDT for offline-first or peer-to-peer tools like Figma/Yjs.

**Q. [Practical] How do you load a document fast when its history is millions of ops?**
A: Don't replay history. Periodically materialize a **snapshot** of the current model to object storage and record its revision. Opening a doc = fetch the newest snapshot + replay only the small op tail committed since it. That's O(tail) instead of O(history), and read-only viewers can be served the snapshot straight from a CDN.

**Q. [Practical] Where is the durability boundary so no acknowledged edit is ever lost?**
A: The server sends `OP_ACK` to the author **only after** the op is durably committed to the op log (Kafka RF=3, `acks=all`, then Cassandra). Apply-to-memory and broadcast happen around that, but the ack — the thing that tells the client "it's safe" — comes after durability. A crash before the ack just makes the client retry idempotently via `client_op_id`; nothing acked is lost.

### 🟠 Advanced
**Q. [Theory] Why does an OT document need a single owning server, and what happens when it dies?**
A: OT correctness depends on one serialization point assigning the per-doc order and transforming each op against concurrent ones — doing that across replicas would require consensus per keystroke. So each active doc is owned by exactly one doc server (via a lease). If it dies, its in-memory model is lost but no acked op is, because every committed op was written to the durable log first. A new server acquires the lease, loads the latest snapshot, replays the op-log tail to rebuild the exact model, and clients `RESYNC` from their last-applied revision.

**Q. [Practical] A document is opened by 5,000 people. How do you handle fan-out?**
A: Distinguish editors from viewers. The handful of active editors get the live op stream; the thousands of viewers get **coalesced** updates (batched every ~250 ms) or a CDN-cached snapshot with a light poll — they don't need every keystroke. Offload the actual N-way socket writes to a dedicated fan-out fleet subscribing to the doc's Kafka stream, so the stateful doc server only transforms + commits. Coalesce bursty single-char ops into combined ops to cut message rate. Promote a viewer to the live stream only when they start editing.

**Q. [Coding] Sketch the client-side OT reconciliation loop (optimistic editing with one op in flight).**
A:
```python
class DocClient:
    def __init__(self, snapshot, base_rev):
        self.model = snapshot          # local, optimistic
        self.rev = base_rev
        self.buffer = None             # the one op sent but not yet acked
        self.pending = []              # ops typed locally, not yet sent

    def on_local_edit(self, op):
        self.model.apply(op)           # instant local echo
        if self.buffer is None:
            self.buffer = op
            self.send(op, base_revision=self.rev)
        else:
            self.pending.append(op)    # only one op in flight per doc

    def on_ack(self, rev):
        self.rev = rev
        self.buffer = None
        if self.pending:
            self.buffer = self.pending.pop(0)
            self.send(self.buffer, base_revision=self.rev)

    def on_remote(self, remote_op, rev):
        # rebase the remote op over my un-acked work, and vice versa
        for local in ([self.buffer] if self.buffer else []) + self.pending:
            remote_op = transform(remote_op, local)   # shift remote past my local op
            local_new = transform(local, remote_op)   # keep my local ops valid too
            local.positions = local_new.positions
        self.model.apply(remote_op)
        self.rev = rev
```
The invariant: exactly one op in flight per doc, local ops applied immediately, and bidirectional `transform` keeps optimistic local state convergent with the server's authoritative order.

**Q. [Practical] How do comments stay anchored to the right text when people edit around them?**
A: Don't anchor to an absolute character offset — an insert above it would silently shift the anchor. Anchor to a **stable relative position / element id** (e.g. "between element A and element B"), so concurrent inserts and deletes elsewhere don't orphan or misplace the comment. This is effectively a small CRDT-style stable-id scheme layered alongside the OT text, which is why even OT systems keep a parallel stable identifier for anchors.

### 🔴 Expert
**Q. [Theory] Design for global collaboration (editors in SF and Singapore on one doc) while keeping convergence.**
A: Pin each document to a **home region** that owns its serialization point (the single OT owner), so there's still one authoritative order. Editors in remote regions connect to the nearest gateway, which forwards ops to the home-region doc server — they pay the cross-region RTT for *commit confirmation* but not for local echo (still optimistic and instant). The op log replicates cross-region (e.g. Cassandra `NetworkTopologyStrategy`, async). For failover, reassign the doc's home region to a backup and replay the log. If sub-200 ms commit globally were mandatory, you'd move to a CRDT core so regional replicas could merge without a central owner, accepting the metadata/GC cost.

**Q. [Practical] At 100x scale, what's your biggest bottleneck and how do you re-architect?**
A: Fan-out and the sheer number of stateful doc servers dominate, and any global component (a shared routing registry, one Kafka cluster) becomes the limit. Re-architect into **cells**: self-contained units (gateways + doc servers + Kafka + Cassandra + Redis) each owning a slice of documents, docs pinned to a home cell, with a thin cross-cell router for the rare cross-cell access. Push read-only collaboration to the **edge** (snapshots in edge KV + CDN) so origin doc servers handle only real editors. Default non-editing participants to coalesced streams, compact/tier history aggressively, and consider an edge CRDT layer for offline/regional resilience over the OT core.

**Q. [Behavioral] Tell me about a time you shipped a hard real-time/consistency feature under pressure and a trade-off you made.**
A: Use STAR and be concrete about the trade-off, not the heroics. Example: *Situation* — we shipped live multi-user editing for a docs feature with a fixed launch date. *Task* — guarantee convergence without a months-long OT/CRDT build. *Action* — I scoped v1 to a single-owner server with optimistic local editing and a durable op log, explicitly deferring robust offline editing (we showed a "reconnecting" state and replayed buffered ops on reconnect instead of full offline merge), and wrote a fuzz harness that replayed randomized concurrent op streams to prove convergence. *Result* — we launched on time with zero acked-edit-loss incidents; we logged the offline gap as known debt and added a CRDT-based offline mode the next quarter. The lesson: name the deferred trade-off loudly (offline) so it's a decision, not a surprise, and back the consistency claim with a convergence fuzz test rather than hope.

**Q. [Theory] How do you bound history storage when the op log grows by tens of PB?**
A: Treat the op log as event-sourced but *not* infinitely fine-grained. Snapshot periodically, then **compact**: squash settled fine-grained ops behind a snapshot into coarser named revisions (per-author, per-time-window) and tier the raw ops to cold object storage or drop them past a retention window — users rarely need keystroke-level history older than a few weeks. Restores append inverse ops (forward-only log) rather than rewriting history, so you keep an auditable, compactable timeline while bounding hot-storage cost to recent activity plus snapshots.

---

*Key takeaway: a collaborative editor is a masterclass in concurrency and consistency — the real engineering is the convergence layer (OT vs CRDT), a stateful single-owner serialization point made crash-safe by a durable op log + snapshot replay, optimistic local editing for instant feel, and disciplined fan-out so one hot document doesn't melt a server.*
