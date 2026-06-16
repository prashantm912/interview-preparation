# Design a Notification System (Push / Email / SMS / In-App Fan-out)

> A worked, interview-grade design of a multi-channel notification platform: accept a "notify user X" request from any internal service and reliably deliver it via push, email, SMS, or in-app — at billions of events per day, without duplicates, while respecting user preferences, rate limits, and third-party provider quirks.

[← Back to master index](../../README.md) · [← System Design index](../README.md)

---

## 1. Requirements

A notification system looks like "just call SendGrid/FCM," but the interviewer is probing how you reason about fan-out, idempotency, retries against flaky third parties, preference/quiet-hours enforcement, and rate limiting so you don't spam users or get your sender reputation blacklisted. Lead by clarifying scope and channels before drawing.

### Functional requirements
- **Multi-channel send**: deliver a notification via **push** (APNs/FCM), **email** (SES/SendGrid), **SMS** (Twilio/SNS), and **in-app** (a feed/inbox the client polls or subscribes to).
- **Triggered & templated**: internal services trigger notifications by `(user_id, template_id, payload)`; the system renders content from a versioned template per channel/locale.
- **User preferences**: per-user, per-category opt-in/opt-out, preferred channels, and **quiet hours** (no SMS/push at 2am local time).
- **Scheduling & batching**: send now, send-at (scheduled), or digest/batched (e.g. "5 new likes" rolled into one email).
- **De-duplication**: the same logical event must not notify a user twice even if the triggering service retries.
- **Delivery tracking**: track per-notification status — `queued → sent → delivered → opened/clicked → failed/bounced` — and expose it.
- **Rate limiting / throttling**: cap notifications per user per channel per time window (anti-spam + anti-fatigue).
- **Priority tiers**: transactional/critical (OTP, password reset) must jump ahead of marketing/promotional.

### Non-functional requirements
- **Scale**: **10B notifications/day** across all channels (split roughly 50% push, 25% in-app, 15% email, 10% SMS). 200M DAU. Triggering services peak at **~350K events/s**.
- **Latency**: transactional notifications (OTP) **p99 < 2 s** end-to-end (handed to provider). Marketing/digest is async and tolerant — minutes is fine.
- **Availability**: **99.95%** for the ingestion/transactional path (a dropped OTP locks a user out). Marketing can tolerate brief degradation.
- **Durability**: an **accepted** transactional notification must never be silently lost — persist before acking the caller. At-least-once delivery to the provider.
- **Consistency**: eventual is fine for delivery-status; preference/opt-out checks must be **strongly read** (never send to someone who just unsubscribed — legal/CAN-SPAM/GDPR risk).
- **Security & compliance**: PII (email/phone) encrypted at rest; honor unsubscribe/opt-out within legal windows; auditable consent; no plaintext OTP in logs.

### Clarifying questions a strong candidate asks
1. **Which channels, and is the channel chosen by the caller or by user preference?** (Drives whether routing logic lives in the platform or the caller.)
2. **What's the transactional vs. marketing split?** Latency and reliability targets differ by an order of magnitude — they probably want separate pipelines.
3. **Do we own templates/rendering, or does the caller send fully-rendered content?** Owning templates centralizes localization, branding, and A/B testing.
4. **Hard delivery guarantee or best-effort?** OTP must be near-guaranteed; "someone liked your post" is best-effort.
5. **Do we need dedup, and at what granularity?** Per `(user, event_id)` vs. per `(user, template, time-bucket)` for collapsing.
6. **Who owns provider relationships and quotas?** Provider rate limits (APNs, Twilio, SES sending tiers) heavily shape the design.
7. **In-app: push-style real-time, or pull/poll inbox?** Changes whether we need a WebSocket/long-poll tier or just a feed store.

> The most consequential question is the **transactional vs. marketing split**. They have opposite profiles — OTP is low-volume, latency-critical, must-not-drop; marketing is high-volume, latency-tolerant, droppable, and must respect aggressive throttling. Conflating them means either over-engineering marketing or under-serving OTP. Strong answer: **two priority lanes over shared infrastructure.**

---

## 2. Capacity Estimation

Real numbers, shown long-hand. Assume a 5-year horizon.

### Ingestion (write) QPS
```
10,000,000,000 notifications/day ÷ 86,400 s/day ≈ 115,740 /s   (~116K avg)
Peak factor ~3x  →  ~350,000 /s peak
```
Each "notification" may fan out to multiple channels, but ingestion counts logical events. Channel sends are downstream.

### Per-channel send rate (the real constraint — provider quotas)
```
Push   50% → 5.0B/day ÷ 86,400 ≈  57,900 /s avg  →  ~175K /s peak
In-app 25% → 2.5B/day            ≈  28,900 /s avg  →   ~87K /s peak
Email  15% → 1.5B/day            ≈  17,400 /s avg  →   ~52K /s peak
SMS    10% → 1.0B/day            ≈  11,600 /s avg  →   ~35K /s peak
```
SMS is the smallest volume but the **most expensive and most quota-constrained** (Twilio long-code limits ~1 msg/s/number; you need short codes / 10DLC / number pools). Provider quotas, not our compute, gate the SMS/email channels.

### Storage — notification records over 5 years
Per-record estimate (the durable notification log):
```
notification_id   16 bytes (UUID/Snowflake)
user_id            8 bytes
template_id        8 bytes
channel            1 byte
status             1 byte
payload (rendered refs/small) ~200 bytes
timestamps        ~24 bytes
provider_msg_id   ~32 bytes
-------------------------------
~ 290 bytes/record  → round to ~300 B/record
```
```
10B/day × 300 B = 3 TB/day
3 TB × 365 × 5 = 5,475 TB ≈ 5.5 PB raw (5 yr)
With RF=3:  ≈ 16.4 PB
```
We do **not** keep full status history forever — hot status lives ~30–90 days, then rolls to cold object storage / is aggregated. Hot window:
```
3 TB/day × 90 days = 270 TB hot  → ~810 TB with RF=3
```

### Bandwidth
```
Ingestion: 350K/s × ~500 B request ≈ 175 MB/s   (modest)
Email egress: emails carry rendered HTML, but we send a template ref + payload to
  the provider, not the full body each time → small.
Push egress: 175K/s × ~1 KB APNs/FCM payload ≈ 175 MB/s to providers.
```
Bandwidth is not the bottleneck — **provider throughput limits and per-user throttling** are.

### Cache / memory sizing
The hot lookups are **user preferences**, **device tokens**, and the **dedup set**:
```
Preferences: 200M DAU × ~500 B (channels, categories, quiet hours, locale) ≈ 100 GB
Device tokens: avg 2 devices/user × 200M × ~200 B ≈ 80 GB
Dedup keys (last 24h of event_ids): 10B/day × ~50 B key, but cache only a
   working set / use a probabilistic filter — see Deep Dive 6.2.
```
A **sharded Redis cluster (~250–300 GB usable)** holds the hot preference + token working set and the recent dedup keys/rate-limit buckets. Cold preferences fall back to the source-of-truth store on miss.

---

## 3. API Design

Internal callers use **gRPC** (low-overhead, typed, internal); external/admin uses REST. In-app real-time uses WebSocket/SSE (optional). Auth via service-to-service mTLS + per-service API key.

```
# ---- Trigger a notification (gRPC / REST) — the hot ingestion path ----
POST /v1/notifications
Idempotency-Key: <event_id>            # caller-supplied, enables dedup
Authorization: Bearer <service-token>
{
  "user_id":      "u_123",
  "template_id":  "order_shipped_v3",
  "priority":     "TRANSACTIONAL",     # TRANSACTIONAL | MARKETING | DIGEST
  "channels":     ["AUTO"],            # AUTO = resolve by user preference, or explicit list
  "payload":      { "order_id": "A98", "eta": "2026-06-18" },
  "send_at":      null,                # null = now; ISO-8601 = scheduled
  "locale":       "en-US"
}
→ 202 Accepted
{ "notification_id": "ntf_01H...", "status": "QUEUED", "deduped": false }
→ 200 OK  { "notification_id": "...", "deduped": true }   # idempotent replay
→ 400 Bad Request        # unknown template / bad payload
→ 429 Too Many Requests  # caller exceeded its quota

# ---- Query status ----
GET /v1/notifications/{notification_id}
→ 200 { id, user_id, channel, status: "DELIVERED", attempts, provider_msg_id, updated_at }

# ---- User preferences (admin/self-service) ----
GET  /v1/users/{user_id}/preferences
PUT  /v1/users/{user_id}/preferences
{ "categories": { "marketing": false, "security": true },
  "channels":   { "sms": true, "push": true, "email": true },
  "quiet_hours": { "start": "22:00", "end": "08:00", "tz": "America/New_York" } }

# ---- Unsubscribe (one-click, CAN-SPAM compliant, no auth) ----
GET /unsubscribe?token=<signed-token>   → 200 (opt-out recorded immediately)

# ---- In-app feed ----
GET /v1/users/{user_id}/feed?cursor=<seq>&limit=50
POST /v1/users/{user_id}/feed/{id}:markRead

# ---- Provider webhooks (delivery receipts) ----
POST /v1/webhooks/{provider}    # Twilio/SES/SendGrid POST delivery/bounce/open events
```

**Design notes:** the `Idempotency-Key` (the caller's `event_id`) is the linchpin of dedup — replays return the original `notification_id` with `deduped: true` and **never** re-send. `channels: ["AUTO"]` delegates routing to the preference engine; an explicit list lets transactional callers force a channel (OTP must go to SMS even if the user muted marketing SMS). Ingestion returns **202 Accepted** after a durable enqueue — actual delivery is async.

---

## 4. Data Model

Mixed storage: a **wide-column store (Cassandra)** for the high-write notification log, **Redis** for ephemeral hot state, and a **relational store (Postgres/DynamoDB)** for preferences, templates, and device tokens (low write, needs indexes + transactions for consent/audit).

### Why a wide-column store for the notification log
- Write-heavy (350K/s peak), append-mostly, queried by `notification_id` (point lookup) or by `user_id` (recent feed) — no joins.
- Linear horizontal scale, multi-DC replication, no single primary → survives AZ loss.
- Tunable consistency lets transactional writes use `QUORUM` and marketing use `ONE`.

```sql
-- Durable notification log: point lookup by id; partition spreads evenly
CREATE TABLE notifications (
  notification_id  uuid,
  user_id          bigint,
  template_id      text,
  channel          text,          -- PUSH | EMAIL | SMS | IN_APP
  priority         text,          -- TRANSACTIONAL | MARKETING | DIGEST
  status           text,          -- QUEUED|SENT|DELIVERED|OPENED|FAILED|BOUNCED|SUPPRESSED
  attempts         int,
  provider_msg_id  text,
  payload_ref      text,          -- pointer to rendered content (object store) or small inline
  created_at       timestamp,
  updated_at       timestamp,
  PRIMARY KEY (notification_id)
);

-- Per-user in-app feed: partition by user, cluster by seq desc (recent first)
CREATE TABLE user_feed (
  user_id    bigint,
  bucket     int,                  -- yyyymm bucket caps partition size
  seq        bigint,               -- per-user monotonic
  notification_id uuid,
  title      text,
  body       text,
  read       boolean,
  created_at timestamp,
  PRIMARY KEY ((user_id, bucket), seq)
) WITH CLUSTERING ORDER BY (seq DESC);
```

### Preferences, tokens, templates — relational / DynamoDB
Read-heavy, low-write, need indexes, consent audit, and transactional updates:
```sql
user_preferences(user_id PK, categories jsonb, channels jsonb,
                 quiet_hours jsonb, locale, updated_at)
consent_log(user_id, category, action, source, ts)   -- append-only audit (legal)
device_tokens(user_id, device_id PK, platform, token, last_seen, valid bool)
templates(template_id, version, channel, locale, subject, body_template,
          PRIMARY KEY(template_id, version, channel, locale))
suppression_list(channel, address_hash PK, reason, ts)  -- hard bounces, opt-outs
```
- **`suppression_list`** is critical: a hard-bounced email or opted-out number must be checked **before every send** to protect sender reputation and stay legal. It's effectively a global do-not-send set, cached in Redis.
- **Templates are versioned** so an in-flight A/B test or rollback never changes already-queued notifications.

### Ephemeral state — Redis
- **Dedup keys**: `dedup:{event_id}` → `notification_id`, TTL ~24–48h.
- **Rate-limit buckets**: `rl:{user_id}:{channel}` token buckets.
- **Preference / token cache**: read-through, with the relational store as source of truth.
- **Scheduled set**: a sorted set `sched` scored by `send_at` epoch for the scheduler to pop due items.

---

## 5. High-Level Architecture

```
   Internal services (orders, social, auth, marketing…)
            │  gRPC: notify(user, template, payload)
            ▼
   ┌──────────────────────┐
   │  Notification API     │  validate · auth · dedup-check · rate-limit (coarse)
   │  (Ingestion, stateless)│  → persist QUEUED → 202 Accepted
   └───────────┬───────────┘
               │ enqueue by priority
        ┌──────▼───────────────────────────────────────┐
        │                  Kafka                         │
        │  topic: txn (high prio)  ·  topic: marketing   │  ← priority lanes
        └──────┬───────────────────────────────┬────────┘
               │                                │
   ┌───────────▼────────────┐                   │ (scheduled / batched)
   │  Preference & Routing   │            ┌──────▼─────────┐
   │  Service                │            │  Scheduler /    │
   │  - load prefs (cache)   │            │  Digest Worker  │ pops due/batched
   │  - opt-out / suppression│            │  (Redis ZSET)   │ → back to Kafka
   │  - quiet-hours / channel│            └─────────────────┘
   │  - de-dup confirm       │
   └───────────┬─────────────┘
               │ per-channel work items
        ┌──────▼───────────────────────────────────────┐
        │              Kafka (per-channel topics)        │
        │   push · email · sms · in-app                  │
        └───┬───────┬────────┬───────────┬───────────────┘
            │       │        │           │
      ┌─────▼──┐ ┌──▼────┐ ┌─▼─────┐ ┌───▼──────────┐
      │ Push   │ │ Email │ │  SMS  │ │  In-App      │
      │ Worker │ │ Worker│ │ Worker│ │  Worker      │  channel workers:
      │+render │ │+render│ │+render│ │ write feed + │  render · throttle ·
      └───┬────┘ └──┬────┘ └──┬────┘ │ WS push      │  retry · circuit-break
          │         │         │      └───┬──────────┘
      ┌───▼──┐  ┌───▼───┐ ┌───▼───┐      │
      │APNs/ │  │ SES/  │ │Twilio/│      ▼
      │ FCM  │  │SendGrid│ │ SNS  │  in-app feed store (Cassandra) + WS gateway
      └───┬──┘  └───┬───┘ └───┬───┘
          └─────────┴─────────┴────────────► Provider webhooks ──► Status Updater
                                                                    │
                                            ┌───────────────────────▼──────────┐
                                            │  notifications log (Cassandra) +  │
                                            │  analytics (ClickHouse via Kafka) │
                                            └───────────────────────────────────┘
```

### Component walkthrough
- **Notification API (ingestion)** — stateless. Authenticates the caller, validates the template/payload, does a **fast dedup check** (Redis), applies coarse per-caller rate limits, persists a `QUEUED` row durably, then publishes to the **priority-appropriate Kafka topic** and returns `202`. Critically, it does *minimal* work — routing/preferences happen downstream so ingestion stays fast for OTP.
- **Kafka (priority lanes)** — separate `txn` and `marketing` topics (and per-channel topics downstream). The transactional lane gets more consumers/partitions and is never starved by a marketing blast. Provides durability, replay, and spike absorption.
- **Scheduler / Digest worker** — for `send_at` and batched/digest notifications. A Redis sorted set keyed by due-time; a poller moves due items back into Kafka. Digests collapse many events into one (Deep Dive 6.4).
- **Preference & Routing service** — the brain. Loads user preferences (cached), checks the **suppression list** and opt-outs, applies **quiet hours** (defer or drop per priority), resolves `AUTO` into concrete channels, confirms dedup, and emits one work item per resolved channel into the per-channel Kafka topics.
- **Channel workers (push/email/sms/in-app)** — channel-specific. Render the template for the channel/locale, enforce **provider-aware throttling**, call the third-party provider, handle retries with backoff, and update status. Each isolated so a Twilio outage can't stall push.
- **Providers** — APNs/FCM, SES/SendGrid, Twilio/SNS. They are flaky, quota-limited, and async (delivery is confirmed later via webhook).
- **Status Updater** — consumes provider **webhooks** (delivered/bounced/opened/clicked) and updates the notification log + feeds analytics. Bounces/opt-outs write to the suppression list.
- **In-app worker + feed store + WS gateway** — writes to the per-user feed (Cassandra) and, if the user is connected, pushes over WebSocket; otherwise the client pulls on next open.

---

## 6. Deep Dives

### 6.1 Reliable delivery against flaky third parties (retries, backoff, DLQ)
Providers fail constantly — transient 5xx, rate-limit 429s, timeouts. We engineer **at-least-once delivery** to the provider plus idempotency so retries don't double-send.

```
Channel worker per work item:
1. Render content (template + payload + locale).
2. Check suppression list (Redis) — if suppressed, mark SUPPRESSED, stop.
3. Acquire per-user + per-provider rate-limit token (else requeue with delay).
4. Call provider with a timeout + idempotency key (provider-side dedup where supported).
5. On success → status SENT, store provider_msg_id (delivery confirmed later via webhook).
6. On transient error (5xx/429/timeout) → exponential backoff + jitter, requeue.
      retry schedule e.g. 1s, 4s, 16s, 64s, ... capped, max N attempts.
7. On permanent error (invalid token/number, hard bounce) → status FAILED,
      add to suppression list, do NOT retry, optionally fall back to another channel.
8. After max attempts → Dead Letter Queue for inspection/manual replay.
```
- **Exponential backoff + jitter** prevents retry storms from synchronizing and hammering a recovering provider (the thundering-herd-on-recovery problem).
- **Idempotency key to the provider** (APNs `apns-collapse-id`, SES message dedup, Twilio idempotency) means a worker retry after an ambiguous timeout doesn't send twice.
- **DLQ** captures the genuinely-undeliverable for observability; we never silently drop a transactional notification — it either succeeds, falls back to another channel, or lands in the DLQ with an alert.
- **Circuit breaker per provider**: if Twilio error-rate spikes, open the breaker, stop hammering it, and (for transactional) **fail over to a secondary SMS provider (SNS)**.

### 6.2 De-duplication & exactly-once *effect*
True exactly-once is impossible across networks + third parties; we get **at-least-once + idempotent dedup = exactly-once effect**. Two dedup layers:

**Layer 1 — ingestion dedup (caller retries):** the caller supplies an `Idempotency-Key` (`event_id`). On ingest:
```
SETNX dedup:{event_id} = notification_id   (TTL 48h)
  - success → first time, proceed
  - already exists → return the stored notification_id, deduped:true, DO NOT enqueue
```
This is a Redis conditional write; the first writer wins atomically.

**Layer 2 — send dedup (internal retries / reprocessing):** the work item carries the `notification_id`; the channel worker uses it as the provider idempotency key and as a guard on the status write (`UPDATE ... IF status = 'QUEUED'`), so reprocessing the same Kafka message twice can't double-send.

**Scale concern:** storing every `event_id` for 48h is 10B × ~50 B ≈ 500 GB/day of keys. Mitigations: TTL-expire aggressively, shard the dedup Redis, and for *collapse-style* dedup (e.g. "don't notify about the same post twice in an hour") use a **probabilistic filter** or `(user, template, time-bucket)` composite key rather than per-event. Exact dedup only for transactional; collapse dedup for high-volume social notifications.

### 6.3 Preference, quiet-hours & suppression enforcement (the compliance hot path)
Every send must pass three gates, evaluated in the Preference & Routing service:
```
1. Suppression check  — hard bounce / global opt-out? → SUPPRESS (never send).
2. Category opt-in    — did the user opt into this category? Marketing requires
                        explicit opt-in; TRANSACTIONAL (OTP, security) bypasses
                        category prefs (legitimate interest / required).
3. Channel + quiet hours — preferred channel enabled? Within quiet hours
                        (computed in the user's local tz)?  Per priority:
                          - TRANSACTIONAL: send anyway (OTP can't wait til 8am).
                          - MARKETING/DIGEST: defer to end of quiet window, or drop.
```
- **Quiet hours are local-time** → store the user's tz and compute against it; a global "22:00" is meaningless across regions.
- **Strong read for opt-out**: preferences are cached, but an *unsubscribe* must take effect immediately. We write opt-out to the source of truth, then invalidate/overwrite the cache synchronously, and the suppression list is checked at send time — so a user who unsubscribes 1s before a blast is still excluded. This is the one place we choose **consistency over latency** (legal exposure beats a few ms).
- **Priority override matrix** keeps OTP flowing while muting noise: transactional ignores quiet hours and marketing opt-out *for the security category only*, never for promotions.

### 6.4 Batching, digests & notification fatigue
Spamming users is a product failure (and tanks open rates / sender reputation). Two collapsing strategies:
- **Rate limiting (token bucket per `user:channel`)**: e.g. ≤ 1 push/min, ≤ 5 emails/day for marketing. Excess is dropped or rolled into a digest. Implemented as Redis token buckets, refilled over time.
- **Digesting**: instead of 50 "X liked your photo" pushes, accumulate events in a per-user window and emit **one** "50 people liked your photos" notification. The Scheduler/Digest worker holds a per-user accumulator (`digest:{user}:{category}`) and flushes on a timer or count threshold.
```
On social event for user U:
   INCR digest:{U}:likes ; ZADD flush_schedule (now+window) U:likes
Digest worker (on flush):
   count = GETDEL digest:{U}:likes
   if count == 1 → send specific notification
   if count > 1  → render "N people liked your posts", send once
```
- **Trade-off**: digesting cuts volume and fatigue dramatically but adds latency (a like isn't notified instantly). Transactional notifications are **never** digested.

### 6.5 In-app notifications: push real-time vs. pull inbox
In-app is a feed/inbox, not a third-party provider. Two delivery modes:
| | Push (WebSocket/SSE) | Pull (poll feed on open) |
|---|---|---|
| Freshness | Instant (badge updates live) | Stale until next poll/open |
| Infra cost | Stateful gateway tier holding connections | Stateless, simple |
| Offline | Stored in feed, shown on next connect | Same |

**Recommendation:** **store-and-pull as the durable baseline, push as an enhancement.** Always write to the durable `user_feed` (so it survives and syncs across devices); *additionally* push over WebSocket if the user is currently connected (badge/toast updates live). The feed is the source of truth; the WS push is a latency optimization, so a dropped socket never loses a notification — the client reconciles via `GET /feed?cursor=<last_seq>` on reconnect. This mirrors the chat-system routing-registry pattern but is read-pull-first because in-app notifications tolerate staleness.

---

## 7. Scaling, Bottlenecks & Failure Handling

**What breaks first?** In order: (1) **third-party provider quotas/outages** (the real ceiling — you can't out-scale Twilio's per-number limit by adding servers), (2) **the marketing blast overwhelming shared infra and starving transactional**, (3) **dedup/preference Redis hot keys**, (4) **Cassandra write hot partitions** for super-active users, (5) **scheduler/digest backlog**.

- **Priority isolation is the headline mitigation.** Separate Kafka lanes (`txn` vs `marketing`), separate consumer groups, and **reserved capacity** for transactional so a 2B-message marketing campaign can't delay an OTP. If marketing backs up, it backs up alone.
- **Provider rate-limit management**: per-provider token buckets sized to the contracted quota; SMS uses **number pools / short codes / 10DLC** to multiply throughput; email warms up IP/domain reputation and respects SES sending tiers. When a provider's quota is hit, queue (for marketing) or fail over to a secondary provider (for transactional).
- **Stateless services scale horizontally**: ingestion API, preference/routing, and channel workers are all stateless behind autoscaling groups + Kafka consumer scaling. Add partitions + consumers to add throughput.
- **Kafka as shock absorber**: ingestion can spike to 350K/s while delivery drains at provider-limited rates; the backlog buffers and drains during quieter periods. RF=3, `min.insync.replicas=2`.
- **Cassandra**: RF=3 across AZs, multi-DC. Notification log partitions by `notification_id` (uniform, no hot partition). The `user_feed` uses `(user_id, bucket)` to cap partition size for power users.
- **Redis hot keys**: shard dedup/rate-limit keyspace by hash; replicate hot preference entries; use local L1 caches in the routing service for the hottest users.
- **Circuit breakers & graceful degradation**: provider down → open breaker, fail over (transactional) or queue (marketing). Analytics/webhook pipeline down → keep delivering, buffer status updates. Preference cache miss → fall back to source of truth, never skip the suppression check.
- **Failure scenarios**:

| Failure | Behavior |
|---|---|
| Provider (Twilio) outage | Circuit breaker opens; transactional SMS fails over to SNS; marketing SMS queues until recovery. |
| Marketing blast (2B msgs) | Lands only on the marketing lane/topic; transactional lane unaffected; campaign drains at throttled rate. |
| Ingestion API node dies | Stateless — LB routes elsewhere; in-flight requests retried by callers (idempotent, so safe). |
| Kafka broker loss | RF=3 + ISR=2 → no loss; consumers rebalance. |
| Duplicate provider webhook | Status update is idempotent (keyed by provider_msg_id) → no double-counting. |
| Region outage | Cross-DC Cassandra replicas serve; ingestion fails over via GeoDNS; scheduled items replicated. |

**Thundering herd on provider recovery**: when a circuit breaker closes, ramp traffic back gradually (half-open state, slow-start) with jittered retries so the recovering provider isn't instantly re-saturated.

---

## 8. Trade-offs & Alternatives

- **One pipeline vs. priority lanes.** A single queue is simpler but lets a marketing blast delay OTPs. **Chosen: separate transactional and marketing lanes** with reserved transactional capacity — the latency profiles are too different to share blindly.
- **Push real-time vs. pull inbox for in-app.** Pull is cheap and durable; push is fresh but needs a stateful connection tier. **Chosen: durable pull as baseline + best-effort WS push** so freshness is an optimization, never a correctness dependency.
- **Exact dedup vs. probabilistic/collapse dedup.** Exact (per `event_id`) is correct but storage-heavy at 10B/day. **Chosen: exact dedup for transactional, collapse/probabilistic for high-volume social** to bound the dedup store.
- **Synchronous vs. asynchronous delivery.** Synchronous would let callers know the real outcome but couples them to flaky providers and kills throughput. **Chosen: async (202 Accepted + status API/webhooks)** — durability boundary is the durable enqueue, not provider acceptance.
- **Build templating/routing in-platform vs. caller-rendered.** Owning templates centralizes localization, branding, A/B, and compliance footers (unsubscribe link). **Chosen: in-platform templates**, with an escape hatch for fully-rendered transactional content.
- **CAP**: on the send path we choose **AP** for status/delivery (eventual, best-effort) but **CP** for the opt-out/suppression check (a unsubscribed user must never be messaged — reject/suppress over availability). State this explicitly: compliance gates are strongly consistent; everything else is eventual.

**At 10x scale (~3.5M events/s):** more Kafka partitions + consumers, shard Redis further, add channel-worker fleets per provider region, negotiate higher provider quotas and add provider redundancy (multiple SMS/email vendors with weighted routing). Move digest accumulation to a dedicated streaming aggregator (Flink) instead of Redis counters.

**At 100x scale:** becomes a **cell-based** architecture — users pinned to a home cell (ingestion + Kafka + routing + workers + Redis per cell) so no global component is a bottleneck, with a thin global layer only for templates and the suppression list (globally replicated, read-mostly). Provider connections pooled per region/cell. Analytics moves to sampled/probabilistic aggregation.

---

## Interview Q&A by Level

### 🟢 Basic
**Q. [Theory] Why return 202 Accepted instead of waiting for the provider to confirm delivery?**
A: Providers (APNs, Twilio, SES) are flaky and asynchronous — confirmation can take seconds and may arrive later via webhook. Blocking the caller on provider acceptance would couple every triggering service to third-party latency/outages and crush throughput. We durably enqueue, return `202`, and report final delivery via a status API and provider webhooks. The durability boundary is the persisted enqueue, not provider acceptance.

**Q. [Theory] Why split transactional and marketing into separate pipelines?**
A: They have opposite profiles. Transactional (OTP, password reset) is low-volume, latency-critical (p99 < 2s), and must-not-drop. Marketing is high-volume, latency-tolerant, droppable, and heavily throttled. Sharing one queue lets a 2-billion-message marketing blast sit ahead of an OTP and lock a user out. Separate lanes with reserved transactional capacity keep critical messages fast.

**Q. [Practical] How does the system know which channel(s) to use?**
A: The caller sends `channels: ["AUTO"]` to delegate routing to the Preference & Routing service, which resolves it against the user's enabled channels and category preferences — or sends an explicit list for cases like OTP that must go to SMS regardless of marketing-SMS preference. Routing logic lives in the platform so callers don't each re-implement preference rules.

### 🟡 Intermediate
**Q. [Practical] How do you prevent sending a user the same notification twice when the calling service retries?**
A: The caller supplies an `Idempotency-Key` (its `event_id`). On ingest we do a Redis `SETNX dedup:{event_id} = notification_id` with a ~48h TTL. The first writer wins and proceeds; a retry finds the key, returns the original `notification_id` with `deduped: true`, and never enqueues again. A second internal layer uses the `notification_id` as the provider idempotency key so reprocessing a Kafka message can't double-send either.

**Q. [Practical] How do you enforce quiet hours and unsubscribe so you never message someone you shouldn't?**
A: Three gates in the routing service: suppression-list check (hard bounce / global opt-out), category opt-in (marketing needs explicit opt-in; transactional bypasses it), and channel + quiet-hours (computed in the user's local timezone). Opt-out is the one strongly-consistent path: an unsubscribe writes to the source of truth and synchronously invalidates the cache, and the suppression list is checked at send time — so an unsubscribe 1 second before a blast still excludes the user. Quiet hours defer/drop marketing but never block transactional.

**Q. [Theory] How do you handle the huge volume difference between SMS and push?**
A: SMS is the smallest volume (~10%) but the tightest constraint — Twilio long codes do ~1 msg/s/number, so we use number pools, short codes, or 10DLC to multiply throughput, and per-provider token buckets sized to the contracted quota. Push is high volume but APNs/FCM scale well, so it's gated by our worker fleet, not provider quotas. Each channel has its own worker fleet and throttle so they scale independently.

**Q. [Coding] Sketch the dedup-on-ingest logic in pseudocode.**
A:
```python
def ingest(req):
    event_id = req.headers["Idempotency-Key"]
    # Atomic first-writer-wins; value is the new id we'd assign
    new_id = new_notification_id()
    won = redis.set(f"dedup:{event_id}", new_id, nx=True, ex=48*3600)
    if not won:
        existing = redis.get(f"dedup:{event_id}")
        return Response(200, {"notification_id": existing, "deduped": True})

    if not rate_limiter.allow(req.caller):       # coarse per-caller cap
        return Response(429)

    persist_notification(new_id, status="QUEUED", req=req)   # durable
    topic = "txn" if req.priority == "TRANSACTIONAL" else "marketing"
    kafka.publish(topic, work_item(new_id, req))
    return Response(202, {"notification_id": new_id, "deduped": False})
```
The `set(..., nx=True)` is the atomic guard; persisting before publishing ensures we never ack a notification we didn't durably record.

### 🟠 Advanced
**Q. [Practical] A provider (Twilio) starts timing out. What happens to in-flight and new SMS?**
A: A per-provider circuit breaker tracks error rate; once it trips it opens, so workers stop hammering the failing provider. Transactional SMS **fails over to a secondary provider** (e.g. SNS) via weighted routing; marketing SMS **queues** in Kafka until recovery. In-flight items that timed out are retried with exponential backoff + jitter (and a provider idempotency key so an ambiguous timeout doesn't double-send). On recovery the breaker goes half-open and ramps traffic gradually to avoid re-saturating the provider. Anything that exhausts retries lands in a DLQ with an alert — never silently dropped.

**Q. [Practical] How do you stop notification fatigue without dropping important messages?**
A: Two mechanisms, applied only to non-transactional traffic. (1) Token-bucket rate limits per `user:channel` (e.g. ≤1 push/min, ≤5 marketing emails/day); excess rolls into a digest or is dropped. (2) Digesting — accumulate "X liked your post" events in a per-user window and emit one "N people liked your posts" notification instead of N. Transactional notifications bypass both and always send immediately. The trade-off is latency (a like isn't instant), which is acceptable for social but never for OTP.

**Q. [Theory] What's your consistency model, in CAP terms?**
A: Mixed. Delivery status and the in-app feed are **AP** — eventually consistent, best-effort, optimized for availability and throughput. The opt-out/suppression check is **CP** — we choose consistency over availability because messaging an unsubscribed user is a legal/reputational failure; better to reject or suppress than risk it. Dedup on ingest is also effectively CP (atomic first-writer-wins). So the compliance gates are strongly consistent; everything else is eventual.

**Q. [Behavioral] Marketing wants to send a one-time 2-billion-message campaign tonight. Engineering worries it'll delay OTPs and trip provider limits. How do you handle it?**
A: I'd frame it as a capacity-and-isolation conversation, not a no. First, confirm the campaign lands on the marketing lane with its own consumers, so transactional capacity is reserved and unaffected — that removes the OTP risk technically, and I'd show the dashboards proving lane isolation. Second, I'd coordinate with the email/SMS provider ahead of time to raise quotas and warm IPs, and spread the 2B over a throttled window (e.g. several hours) sized to the negotiated rate rather than firing instantly. Third, I'd ensure suppression/opt-out and rate caps are enforced so we don't torch sender reputation. I'd give marketing a clear delivery-window estimate and a dashboard, so they get their campaign and engineering keeps OTPs safe. The goal is a documented, repeatable "campaign mode" rather than a one-off heroic effort.

### 🔴 Expert
**Q. [Practical] Redesign for 100x scale and true global low latency. Where does the architecture change?**
A: It becomes cell-based. Pin users to a home cell containing its own ingestion API, Kafka, routing service, channel workers, and Redis, so no global component bottlenecks — capacity scales by adding cells. The only global, read-mostly layer is templates and the suppression list, both globally replicated (a user opted out anywhere is suppressed everywhere). Provider connections are pooled per region with multiple vendors per channel and weighted/failover routing. Digest accumulation moves from Redis counters to a streaming aggregator (Flink) for correctness at volume, and delivery analytics shifts to sampled/probabilistic aggregation. Cross-cell is only needed for the rare case of moving a user between cells.

**Q. [Coding] Implement a per-user-per-channel token-bucket rate limiter on Redis.**
A: A lazy refill computed at check time avoids a background refiller:
```lua
-- KEYS[1] = bucket key   ARGV: capacity, refill_per_sec, now_ms, cost
local b = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(b[1]) or tonumber(ARGV[1])   -- start full
local ts     = tonumber(b[2]) or tonumber(ARGV[3])
local elapsed = (tonumber(ARGV[3]) - ts) / 1000.0
tokens = math.min(tonumber(ARGV[1]), tokens + elapsed * tonumber(ARGV[2]))
local cost = tonumber(ARGV[4])
if tokens < cost then
  redis.call('HMSET', KEYS[1], 'tokens', tokens, 'ts', ARGV[3])
  redis.call('PEXPIRE', KEYS[1], 3600000)
  return 0          -- throttled
end
tokens = tokens - cost
redis.call('HMSET', KEYS[1], 'tokens', tokens, 'ts', ARGV[3])
redis.call('PEXPIRE', KEYS[1], 3600000)
return 1            -- allowed
```
Running it as a Lua script makes the read-modify-write atomic (no race between concurrent workers for the same user). Capacity/refill differ per channel (e.g. push capacity=1 refill≈0.017/s for 1/min; marketing email capacity=5 refill≈5/86400). Transactional bypasses this entirely.

**Q. [Practical] Provider webhooks for delivery receipts can arrive duplicated, out of order, or never. How do you keep status accurate?**
A: Treat webhooks as an at-least-once, possibly-out-of-order stream. Make the status update **idempotent and monotonic**: key updates by `provider_msg_id`, and only advance status along the allowed lifecycle (`SENT → DELIVERED → OPENED`), never regress — so a late `SENT` webhook arriving after `DELIVERED` is ignored. Duplicates are absorbed because re-applying the same state is a no-op. For "never arrives," run a reconciliation sweep: anything stuck in `SENT` past a threshold is re-queried via the provider's status API or marked `UNKNOWN`. Hard-bounce/opt-out webhooks additionally write to the suppression list so we stop future sends.

**Q. [Behavioral] Post-incident: a bug caused 4 million users to get a duplicate push at 3am. Walk through how you respond.**
A: Immediate: stop the bleeding — confirm the duplicate source (a Kafka reprocessing loop bypassing the send-dedup guard?), pause the affected worker/topic, and verify no further duplicates are going out. Communicate: notify support and leadership with scope (4M users, one duplicate, 3am — so quiet-hours enforcement also failed for these). Mitigate user impact: prepare a brief apology if warranted and ensure no cascading opt-outs spike. Root cause: blameless postmortem — likely the consumer re-processed without the `notification_id` idempotency guard, *and* the quiet-hours gate was skipped because the duplicate path didn't re-enter the routing service. Fix: enforce the send-dedup guard at the worker (idempotent status write keyed on `notification_id`), ensure every send path passes the routing/quiet-hours gates (no bypass), add a regression test, and add an alert on duplicate-send rate and on any send during quiet hours. Follow-up: track action items to done and share learnings so the same class of bug can't recur. The honest framing matters — own it, fix the systemic gap (a path that skipped both dedup and compliance gates), not just the symptom.

---

*Key takeaway: a notification system is a masterclass in reliability against unreliable dependencies — the interesting engineering is priority isolation (so a marketing blast never delays an OTP), idempotent at-least-once delivery with backoff/circuit-breakers/DLQ against flaky providers, strongly-consistent opt-out/suppression for compliance, and fatigue control via rate limits and digests.*
