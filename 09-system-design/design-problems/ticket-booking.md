# Design a Ticket Booking System (Ticketmaster / BookMyShow)

> A worked, interview-grade design of an event/movie ticket booking system: browse events, hold and reserve specific seats under brutal contention, take payment, and never double-sell a seat — even when 1M fans hit "buy" the instant a hot show goes on sale.

[← Back to master index](../../README.md) · [← System Design index](../README.md)

---

## 1. Requirements

A ticket booking system looks like a CRUD app ("events have seats, sell the seats"), but the interviewer is probing the one genuinely hard property: **strong consistency under extreme, bursty write contention**. The same seat must never be sold twice, yet hundreds of thousands of users fight over a few thousand seats in the same second. Lead by separating the read-heavy discovery surface from the write-critical booking surface.

### Functional requirements
- **Browse / search**: find events (concerts, movies, sports) by city, date, genre, venue; view event details and showtimes.
- **Seat map & availability**: for a given show, see the seat map with real-time available/held/sold status.
- **Hold a seat**: temporarily reserve selected seats for a few minutes while the user pays (the "shopping cart" of ticketing).
- **Book / purchase**: confirm the hold, take payment, issue tickets (QR/barcode) on success.
- **Cancel / refund**: cancel a booking within policy, release the seats, refund payment.
- **Waitlist / queue**: place fans in a virtual waiting room for ultra-high-demand on-sales.
- **General admission**: support non-reserved-seating events (just a quantity/inventory counter, no seat map).

### Non-functional requirements
- **Scale**: 100M registered users, ~10M bookings/day average, but **catastrophically bursty** — a single hot on-sale (e.g. Taylor Swift) can drive **1M+ users hitting one event in the first minute**, ~10K booking attempts/sec against a few thousand seats.
- **Latency**: search/browse p99 **< 200 ms**; seat-map load p99 **< 300 ms**; the book/confirm call p99 **< 1 s** (it spans a payment call).
- **Availability**: discovery/browse **99.99%**; the booking path targets **99.95%** but **correctness beats availability** — when in doubt we reject rather than risk a double-sell.
- **Durability**: a confirmed, paid booking must **never** be lost. A captured payment with no ticket is the worst possible outcome.
- **Consistency**: **strong** consistency on seat inventory — no two confirmed bookings may share a seat. Search/browse can be eventually consistent (stale availability counts are acceptable; the booking step is the source of truth).
- **Security**: PCI-DSS — never store raw card data (tokenize via the payment gateway); prevent scalping/bot abuse; idempotent payments so a retry never double-charges.

### Clarifying questions a strong candidate asks
1. **Reserved seating or general admission, or both?** Reserved seating (specific seat = unit of inventory) is the hard case; GA is just a counter. Most systems do both.
2. **How long is a seat held during checkout?** This hold TTL (typically 5–10 min) is the central tuning knob between conversion and inventory liquidity.
3. **What's the peak concurrency on a single hot event?** This — not average load — dictates the architecture (queue/waiting room, per-event sharding).
4. **Do we own payments or integrate a gateway (Stripe/Adyen)?** Drives the PCI scope and the saga/2-phase booking flow.
5. **Is overbooking ever acceptable?** Airlines deliberately overbook; concert seats absolutely cannot. This decides strong vs. relaxed consistency.
6. **Single region or global?** Events are inherently regional (a venue is in one city) — this lets us shard/pin by event and avoid global write coordination.
7. **Refund/cancellation policy and resale?** Affects state machine complexity and the secondary market.

> The make-or-break question is #5. Concert/movie seating demands **strong consistency** — the entire design hinges on never double-selling, which is why naive "cache the availability and let them book" designs fail.

---

## 2. Capacity Estimation

Real numbers, shown long-hand. Assume a 5-year horizon. The defining feature is that **average load is modest but peak load on a single event is extreme**.

### Booking write QPS (average)
```
10,000,000 bookings/day ÷ 86,400 s/day ≈ 116 bookings/sec  (trivial on average)
```
The average is laughably small. The system is not designed for the average — it's designed for the spike.

### Peak write QPS (a single hot on-sale)
```
1,000,000 users storming one event in minute 1
Each tries to hold/book repeatedly (retries, refreshes) ~5x → 5M attempts/min
5,000,000 ÷ 60 ≈ 83,000 hold/book attempts/sec against ONE event
Actual successful bookings are capped by inventory: ~3,000–20,000 seats total.
```
So we have **~80K contended write attempts/sec funnelling onto a few thousand rows**. This is the crux: the bottleneck is not aggregate throughput, it's **lock contention on a tiny hot inventory set**. This is precisely why a virtual waiting room exists (Deep Dive 6.4) — to convert 80K simultaneous attempts into a metered, orderly trickle the inventory store can serialize.

### Read QPS (browse / seat-map polling)
```
Browse:seat-map:book ratio ≈ 1000 : 100 : 1
Average browse ≈ 116 × 1000 ≈ 116,000 reads/sec
Peak (hot on-sale, 1M users polling seat map every ~3 s):
   1,000,000 ÷ 3 ≈ 333,000 seat-map reads/sec on ONE event
```
Reads dominate by 1000:1 and spike hardest on the seat map — served from cache/CDN, never from the inventory store directly.

### Storage over 5 years
Per-booking record:
```
booking_id     16 bytes
user_id         8 bytes
show_id         8 bytes
seat_ids       ~50 bytes (a few seats)
status          4 bytes
amount          8 bytes
payment_ref    32 bytes
timestamps     16 bytes
metadata      ~100 bytes
-------------------------------
~ 250 bytes/booking → round to ~300 B
```
```
Bookings: 10M/day × 365 × 5 = 18.25 × 10^9 records
18.25e9 × 300 B ≈ 5.5 × 10^12 bytes ≈ 5.5 TB
With 3x replication + indexes (~1.5x): 5.5 × 3 × 1.5 ≈ 25 TB
```
```
Seat inventory: say 100K events/yr × 1,000 shows-equiv × 2,000 seats avg
   ≈ 2 × 10^11 seat rows over 5 yrs at ~80 B = ~16 TB raw → ~70 TB replicated
```
Booking data is small (tens of TB) — this is a **contention problem, not a storage problem**. A sharded relational/transactional store handles it comfortably.

### Bandwidth
```
Peak seat-map reads: 333K RPS × ~5 KB seat-map JSON (cached/delta) ≈ 1.6 GB/s
   → served by CDN/cache, NOT origin. Origin sees a tiny fraction.
Booking writes: 80K attempts/s × 300 B ≈ 24 MB/s  (trivial)
```
Bandwidth on the write path is nothing; the seat-map read fan-out is large but is a caching/CDN problem.

### Cache / memory sizing (hot inventory)
```
A single hot show's seat map: 20,000 seats × ~30 B state ≈ 600 KB — fits trivially in Redis.
Concurrent hot events (multiple big on-sales): say 100 active × 600 KB ≈ 60 MB.
Plus waiting-room queue state: 1M users × ~50 B ≈ 50 MB per hot event.
```
The entire hot working set is **megabytes**, not gigabytes — it fits in RAM many times over. The challenge is never memory; it's **serializing writes to those few hundred KB without a stampede melting the database.**

---

## 3. API Design

REST/HTTPS for browse and booking; the seat-map live view can use WebSocket/SSE for push updates. Auth via OAuth bearer token for all write operations.

```http
# ---- Discovery (read-heavy, cacheable) ----
GET /api/v1/events?city=NYC&date=2026-07-04&category=concert
→ 200 { events: [{ event_id, name, venue, date, min_price, ... }] }

GET /api/v1/events/{event_id}/shows
→ 200 { shows: [{ show_id, start_time, venue, available_count }] }

GET /api/v1/shows/{show_id}/seatmap          # served from cache/CDN
→ 200 { sections:[{ id, rows:[{ seats:[{ seat_id, status, price_tier }]}]}], version }
   # status ∈ AVAILABLE | HELD | SOLD   (HELD/SOLD may be slightly stale; book is authoritative)

# ---- Hold (the contended write — STRONGLY consistent) ----
POST /api/v1/shows/{show_id}/holds
Authorization: Bearer <token>
Idempotency-Key: <uuid>                       # dedupe retries
{ "seat_ids": ["A12","A13"] }                 # or { "quantity": 2 } for GA
→ 201 Created
   { "hold_id":"h_8f3", "seat_ids":["A12","A13"], "expires_at":"2026-06-16T10:05:00Z",
     "amount": 240.00 }
→ 409 Conflict        # one or more seats already held/sold — return fresh seat map
→ 429 Too Many Requests   # rate-limited / sent to waiting room

# ---- Confirm / book (saga: capture payment, finalize) ----
POST /api/v1/holds/{hold_id}/book
Authorization: Bearer <token>
Idempotency-Key: <uuid>                       # CRITICAL: prevents double-charge
{ "payment_method_token": "pm_xxx" }
→ 200 { "booking_id":"b_1a2", "status":"CONFIRMED", "tickets":[{ seat, qr_url }] }
→ 402 Payment Required     # payment declined → hold released
→ 410 Gone                 # hold expired before confirm → seats released

# ---- Manage ----
GET    /api/v1/bookings/{booking_id}
DELETE /api/v1/bookings/{booking_id}          # cancel → refund (within policy)
→ 200 { status: "REFUNDED", refund_id }

# ---- Waiting room (high-demand on-sales) ----
POST /api/v1/shows/{show_id}/queue:join
→ 200 { queue_token, position, eta_seconds }  # poll until ENTERED, then holds allowed
```

**Design notes:** the `Idempotency-Key` on `holds` and `book` is non-negotiable — clients retry aggressively during on-sales, and the booking call wraps a payment capture, so a duplicate must be a no-op returning the original result, never a second charge. Hold and book are **two separate calls** (two-phase), so the expensive payment step happens only after seats are safely reserved.

---

## 4. Data Model

The booking path needs **ACID transactions** (atomic "check seats are free AND mark them held"), so the inventory/booking store is **relational** (PostgreSQL/MySQL, or a NewSQL store like CockroachDB/Spanner for horizontal scale). Discovery data is read-heavy and search-shaped, so it lives in **Elasticsearch + a cache**, fed from the relational source of truth.

### Why relational/transactional for inventory
Seat selling is the textbook case where you genuinely need a transaction: *read the seat's state, verify it's AVAILABLE, and flip it to HELD* must be atomic and isolated, or two buyers both see "available" and both succeed. NoSQL eventual consistency cannot guarantee this without bolting a coordination layer on top — so use a store that gives it natively.

```sql
-- The inventory unit. One row per physical seat per show. This is the hot, contended table.
CREATE TABLE seat_inventory (
  show_id        BIGINT NOT NULL,
  seat_id        VARCHAR(16) NOT NULL,      -- "A12"
  status         SMALLINT NOT NULL,          -- 0=AVAILABLE 1=HELD 2=SOLD
  price_tier     INT,
  held_by        BIGINT,                     -- user holding it (nullable)
  hold_expires_at TIMESTAMP,                 -- when a HELD seat auto-releases
  version        BIGINT NOT NULL DEFAULT 0,  -- optimistic-lock version
  PRIMARY KEY (show_id, seat_id)
);
-- Sharded by show_id → all seats for a show co-locate on one shard → single-shard
-- transactions for a booking (no distributed transaction needed in the common case).
CREATE INDEX ix_inv_expiry ON seat_inventory (hold_expires_at) WHERE status = 1;

-- Holds: the transient reservation. Short-lived.
CREATE TABLE holds (
  hold_id        UUID PRIMARY KEY,
  show_id        BIGINT NOT NULL,
  user_id        BIGINT NOT NULL,
  seat_ids       JSONB NOT NULL,
  amount         NUMERIC(10,2),
  status         SMALLINT,                   -- ACTIVE | CONVERTED | EXPIRED
  expires_at     TIMESTAMP NOT NULL,
  created_at     TIMESTAMP NOT NULL
);

-- Bookings: the durable, confirmed record. Never lost.
CREATE TABLE bookings (
  booking_id     UUID PRIMARY KEY,
  user_id        BIGINT NOT NULL,
  show_id        BIGINT NOT NULL,
  seat_ids       JSONB NOT NULL,
  amount         NUMERIC(10,2),
  status         SMALLINT,                   -- PENDING | CONFIRMED | CANCELLED | REFUNDED
  payment_ref    VARCHAR(64),                -- gateway charge id (idempotent)
  idempotency_key VARCHAR(64) UNIQUE,        -- dedupe the book call
  created_at     TIMESTAMP,
  updated_at     TIMESTAMP
);

-- General-admission inventory: just a counter, no seat rows.
CREATE TABLE ga_inventory (
  show_id        BIGINT PRIMARY KEY,
  total          INT,
  sold           INT,
  held           INT,
  version        BIGINT
);
```

### Storage-engine choice summary
| Data | Store | Why |
|---|---|---|
| Seat inventory, holds, bookings | **PostgreSQL / NewSQL (CockroachDB/Spanner)** | ACID transactions, row locking, strong consistency — the non-negotiable core |
| Event/venue/search catalog | **Elasticsearch** (+ source of truth in SQL) | Full-text + faceted search by city/date/genre |
| Seat-map cache, hold TTLs, rate-limit, queue | **Redis** | Sub-ms reads, atomic ops, TTL expiry, sorted sets for queue |
| Tickets/QR, large media | **Object store (S3) + CDN** | Cheap, cacheable, off the hot path |

**Single-shard transactions are the key design win:** sharding `seat_inventory` and `holds`/`bookings` by `show_id` means every booking for a given show touches exactly one shard, so we get full ACID with a **local** transaction — no slow two-phase commit across shards. Different events live on different shards, so the system scales by adding shards as events multiply, while each individual hot event is still served by a single, strongly-consistent partition.

---

## 5. High-Level Architecture

```
                          ┌────────────────────────────┐
                          │     Clients (web / mobile)   │
                          └───────────────┬──────────────┘
                                          │ HTTPS / WSS
                          ┌───────────────▼──────────────┐
                          │   CDN + API Gateway / L7 LB    │  ← caches seat maps, static
                          └───────┬───────────────┬────────┘
            READ path (browse)    │               │   WRITE path (hold/book)
              ┌───────────────────▼──┐    ┌────────▼────────────────────┐
              │   Search / Catalog    │    │   Waiting-Room / Queue Svc   │ ← throttles on-sale storm
              │   Service             │    │   (Redis sorted set)         │
              └─────────┬─────────────┘    └────────┬────────────────────┘
                        │                            │ admitted users only
              ┌─────────▼──────┐           ┌─────────▼────────────────────┐
              │ Elasticsearch  │           │     Booking Service           │
              │ (event search) │           │  (hold / confirm / cancel)    │
              └────────────────┘           └───┬───────────────┬──────────┘
                        ▲                       │ 1. atomic hold │ 2. confirm
              ┌─────────┴──────┐        ┌───────▼───────┐       │
              │  Seat-map cache │◄───────┤ Inventory DB  │       │
              │   (Redis)       │ deltas │ (SQL, sharded │       │
              └────────────────┘         │  by show_id,  │       ▼
                                         │  RF=3)        │  ┌──────────────────┐
                                         └───┬───────────┘  │ Payment Service   │
                                             │ hold expiry   │ → Gateway (Stripe)│
                                     ┌───────▼────────┐      │  idempotent       │
                                     │ Hold-Expiry     │     └─────────┬────────┘
                                     │ Sweeper (TTL)   │               │ success
                                     └────────────────┘                ▼
                                                            ┌────────────────────┐
                                                            │ Ticketing / Notify  │
                                                            │ (QR gen, email/push,│
                                                            │  S3 + CDN)          │
                                                            └─────────┬───────────┘
                                                                      │ events
                                                            ┌─────────▼───────────┐
                                                            │   Kafka → Analytics  │
                                                            └─────────────────────┘
```

### Component walkthrough
- **CDN + API Gateway**: terminates TLS, serves the (cacheable) seat map and static assets at the edge, authenticates, and routes read vs. write traffic to separate fleets so a booking storm can't starve browsing.
- **Search/Catalog service** (stateless): serves browse/search from Elasticsearch and event metadata from cache; never touches the inventory store. This is where the 1000:1 read load is absorbed.
- **Seat-map cache (Redis)**: holds the per-show seat-status map for fast reads; the booking service publishes deltas on every hold/release/sale so the map stays near-real-time. Reads are best-effort/eventually-consistent — the inventory DB remains the source of truth at book time.
- **Waiting-room / Queue service**: for high-demand on-sales, admits users into the booking path at a metered rate (e.g. N per second) using a Redis sorted set, converting an 80K-RPS storm into a trickle the inventory DB can serialize (Deep Dive 6.4).
- **Booking service** (stateless logic, but transactional against the DB): executes the **two-phase** flow — (1) atomically hold seats, (2) on confirm, capture payment via a saga and finalize the booking. The brains of the contended path.
- **Inventory DB (sharded SQL)**: the strongly-consistent source of truth. Sharded by `show_id` so each booking is a single-shard ACID transaction.
- **Hold-Expiry sweeper**: releases seats whose hold TTL lapsed (Deep Dive 6.3), returning inventory to the pool.
- **Payment service + gateway**: tokenized, PCI-compliant, **idempotent** capture so retries never double-charge (Deep Dive 6.2).
- **Ticketing/Notify**: generates QR/barcode tickets, stores them in S3/CDN, emails/pushes confirmation. Runs after the booking is durably confirmed.
- **Kafka → Analytics**: async event stream for dashboards, fraud detection, and demand analytics — never in the booking latency path.

---

## 6. Deep Dives

### 6.1 Preventing double-booking — pessimistic vs. optimistic locking
The single most important correctness property. Three concurrency-control approaches to "mark these seats HELD only if they're all AVAILABLE":

**(a) Pessimistic locking (`SELECT ... FOR UPDATE`).**
```sql
BEGIN;
SELECT seat_id, status FROM seat_inventory
  WHERE show_id = :s AND seat_id IN ('A12','A13')
  FOR UPDATE;                          -- row locks held for the txn
-- app checks all are AVAILABLE; if not, ROLLBACK and return 409
UPDATE seat_inventory SET status = 1, held_by = :u,
       hold_expires_at = now() + interval '6 min', version = version + 1
  WHERE show_id = :s AND seat_id IN ('A12','A13');
COMMIT;
```
- **Pros**: dead simple, airtight — the row lock serializes concurrent buyers; the second waits, sees SOLD/HELD, fails cleanly.
- **Cons**: under 80K RPS on a few rows, lock waits queue up and connections pile, risking lock contention/thread exhaustion. Mitigate by keeping the transaction *tiny* (lock → check → flip → commit in microseconds) and fronting it with the waiting room so the arrival rate is bounded.

**(b) Optimistic locking (version/CAS).**
```sql
UPDATE seat_inventory SET status = 1, held_by = :u, version = version + 1
  WHERE show_id = :s AND seat_id = 'A12' AND status = 0 AND version = :v;
-- rows_affected = 0  → someone beat us → retry with fresh map or 409
```
- **Pros**: no held locks, higher throughput when contention is *moderate* and most attempts target *different* seats.
- **Cons**: degenerates under heavy contention on the *same* seats (the front-row scramble) — high retry/abort rate, wasted work. Good for spread-out demand, poor for the dogpile on a single premium seat.

**Decision**: pessimistic `FOR UPDATE` with **minimal transaction scope**, fronted by the waiting room to cap arrival rate. For GA inventory it's even simpler — a single atomic `UPDATE ga_inventory SET held = held + :q WHERE total - sold - held >= :q` (conditional, single row), which is naturally serialized. A Redis-based atomic seat-claim (Lua script flipping seat bits) is a viable fast-path *cache* in front of the DB, but the **DB transaction remains the authority** — never trust a cache as the source of truth for inventory.

### 6.2 The booking + payment saga — never charge without a ticket, never ticket without a charge
The confirm step spans an external payment call (seconds, can fail/timeout) and must leave the system consistent. We use a **two-phase reserve-then-confirm saga** with compensations:
```
PHASE 1 — HOLD (fast, local txn):
  atomically flip seats AVAILABLE→HELD, create hold with TTL (6 min). Reply to user.

PHASE 2 — CONFIRM (saga, triggered by user submitting payment):
  1. Create booking row status=PENDING (idempotent on Idempotency-Key).
  2. Call payment gateway CAPTURE with the SAME idempotency key.
       • success → goto 3
       • declined → compensate: release hold (HELD→AVAILABLE), booking=FAILED, 402
       • timeout/unknown → DO NOT release yet; reconcile (gateway is source of truth)
  3. In one local txn: flip seats HELD→SOLD, booking=CONFIRMED, store payment_ref.
  4. Generate tickets, send confirmation (async, after commit).
```
- **Idempotency everywhere**: both the booking row (`UNIQUE idempotency_key`) and the gateway capture (idempotency key passed through) ensure a retried confirm returns the *original* result — no double-charge, no duplicate booking.
- **The dangerous state is "payment captured, seats not yet SOLD"** (crash between step 2 and 3). A **reconciliation job** scans PENDING bookings with a successful `payment_ref`, completes them (flip to SOLD/CONFIRMED, re-issue tickets); if the hold expired and seats were resold, it auto-**refunds** — money never silently disappears.
- **Why hold-then-pay (two calls) not pay-immediately**: we reserve the scarce resource (the seat) *before* the slow payment step, so a user isn't charged for a seat someone else grabbed mid-payment, and the inventory isn't locked for the entire duration of a flaky card flow beyond the hold TTL.

### 6.3 Hold expiration — releasing abandoned carts without leaking inventory
A held seat that's never paid for must return to the pool, or hot shows "sell out" with phantom holds. Two mechanisms working together:
- **TTL on the hold** (`hold_expires_at`), surfaced via Redis key TTL for fast reads. The seat map shows HELD seats; once expired they flip back to AVAILABLE.
- **Lazy + sweeper hybrid**:
  - *Lazy*: any booking attempt that encounters a HELD seat whose `hold_expires_at < now()` may treat it as AVAILABLE within the same transaction (claim it) — self-healing on the hot path, no waiting for a sweeper.
  - *Sweeper*: a background job runs every ~30 s using the partial index on `(hold_expires_at) WHERE status=HELD` to bulk-release expired holds and publish seat-map deltas. Keeps the cache and counts honest even for unvisited seats.
- **Race safety**: release is itself a conditional update (`... WHERE status=HELD AND hold_expires_at < now()`), so a user who confirms in the last millisecond and a sweeper releasing the same seat can't both win — the DB serializes them. Choose the **hold TTL** carefully: too short hurts conversion (users lose seats mid-checkout); too long starves inventory during a hot on-sale. ~5–10 min is typical; shorten dynamically for ultra-high-demand events.

### 6.4 The thundering herd — virtual waiting room / queue
The defining failure mode: 1M users hit "buy" in second one. Letting them all reach the inventory DB melts it (connection storm, lock convoys). The fix is a **virtual waiting room** that admits users at a controlled rate.
```
1. On-sale opens. Clients call queue:join → get a queue_token + position.
2. Positions stored in a Redis sorted set (score = join timestamp) — O(log n) insert.
3. An admission controller releases the front of the queue into the booking path at a
   metered rate, e.g. min(available_seats_remaining, target_throughput) per second.
4. Admitted users get a short-lived access token allowing hold/book calls; everyone
   else polls "position / eta" against a CACHED endpoint (no DB hit).
5. As inventory depletes, admission slows/stops; remaining queue is told "sold out".
```
- **Why it works**: it transforms an 80K-RPS instantaneous storm into a steady, bounded stream (say 1–2K admissions/sec) that the inventory DB *can* serialize with pessimistic locks and tiny transactions. It also gives a **fair, first-come ordering** instead of a random lottery, and a far better UX than 500 errors.
- **Bot defense**: the queue is the natural choke point for rate-limiting, CAPTCHA, device fingerprinting, and per-account purchase caps — stopping scalper bots from consuming the queue.
- **Sizing**: admission rate ≈ inventory ÷ desired sell-out time, capped by DB capacity. For 20K seats sold over ~10 min, ~35 admits/sec suffices — orders of magnitude below the storm.

### 6.5 Search & seat-map reads at scale (decoupled from inventory)
Reads outnumber writes 1000:1 and must never touch or slow the inventory DB.
- **Search** is served from **Elasticsearch**, populated from the SQL catalog via CDC/Kafka. Faceted queries (city, date, genre, price) are ES's sweet spot; results are cached at the edge with short TTLs.
- **Seat-map** is served from **Redis** (and CDN for the static layout). On every hold/release/sale, the booking service publishes a **delta** (`{seat_id, new_status}`) to the cache and to a WebSocket/SSE fan-out so open seat maps update live. The version field lets clients detect staleness.
- **Eventual consistency is fine here**: the seat map may show a seat AVAILABLE that was just grabbed — the user finds out at *hold* time (409 + fresh map). The authoritative check is always the transactional hold, so a slightly stale read never causes a double-sell, only an occasional "seat just taken" retry.
- **Hot-event read fan-out** (333K RPS polling one map): collapse it with CDN micro-caching (1–2 s TTL) and push-based WebSocket deltas instead of polling, so the origin serves the map a handful of times per second regardless of viewer count.

---

## 7. Scaling, Bottlenecks & Failure Handling

**What breaks first?** The **inventory database under concentrated write contention** — long before aggregate throughput is an issue, lock contention on a single hot show's seats and the connection storm from a herd will tip it over. Order of stress: (1) inventory-DB lock contention / connections on the hot shard, (2) waiting-room/Redis under the join storm, (3) seat-map read fan-out, (4) payment-gateway throughput/latency, (5) ticket generation.

- **Shard by `show_id`**: each event is independent inventory, so different events live on different shards and scale horizontally as the catalog grows. A single mega-event is still one shard, but its working set is tiny (megabytes) — the limit is *contention*, not size, which the waiting room caps. For events expected to be huge, dedicate a shard / read replicas.
- **Bound arrival rate, don't just add capacity**: the waiting room is the primary scaling lever for the hot path — you cannot out-provision 1M simultaneous writers on 20K rows; you must serialize them. Admission control is the architecture.
- **Stateless read & booking-logic tiers** autoscale behind the LB; the stateful DB scales by sharding + read replicas (replicas serve seat-map/availability reads, never the booking transaction).
- **Replication & DR**: inventory DB RF=3 across AZs with synchronous replication to a standby (a confirmed booking must survive AZ loss); since events are regional, pin an event's inventory to its home region and avoid cross-region write coordination. Async cross-region replicas for DR with a clear RPO (near-zero for confirmed bookings).
- **Payment failures**: gateway down/slow → the booking call times out gracefully, the hold remains until its TTL, and the user can retry idempotently; captured-but-uncommitted payments are healed by the reconciliation job (6.2). Payment is the one external dependency we treat with a saga + reconciliation, never fire-and-forget.
- **Circuit breakers & graceful degradation**: if the seat-map cache or search is down, browsing degrades (stale/limited results) but **booking still works** (it reads the DB directly). If analytics/Kafka is down, shed it — bookings proceed. The booking transaction is the one thing we protect at all costs.
- **Overselling guard**: even under cache failure or split-brain, the *only* place inventory is decremented is the single-shard ACID transaction — so the worst a failure causes is failed bookings (retryable), never a double-sell.
- **Idempotency + retries**: every write carries an idempotency key; clients use jittered exponential backoff so a herd's retries don't synchronize into a secondary storm.

**Thundering-herd on cache miss** (seat map evicted during a spike): single-flight/request-coalescing so only one rebuild hits the DB, plus serving slightly-stale-but-acceptable cached maps.

---

## 8. Trade-offs & Alternatives

- **Strong vs. eventual consistency**: inventory is **strongly consistent** (ACID, single-shard) because a double-sell is unacceptable; search and seat-map reads are **eventually consistent** because a stale availability count is harmless (the hold step is authoritative). **CAP**: on the booking write we choose **CP** — reject/fail rather than risk inconsistency during a partition; on the read/browse path we choose **AP** — keep serving (possibly stale) results.
- **Pessimistic vs. optimistic locking**: pessimistic `FOR UPDATE` with tiny transactions + a waiting room wins for *concentrated* contention (everyone wants the same front-row seat); optimistic CAS wins for *spread-out* demand. **Chosen: pessimistic + admission control**, since hot on-sales are the design driver.
- **Two-phase hold-then-pay vs. single-step buy**: holding first reserves the scarce seat before the slow payment, giving a better UX and clean inventory semantics, at the cost of a hold-expiry mechanism and the chance a user loses a held seat. **Chosen: two-phase** — it's how every real ticketing system works.
- **SQL/NewSQL vs. NoSQL**: NoSQL would force us to build distributed locking/transactions by hand to avoid double-sells — reinventing what a transactional DB gives free. **Chosen: relational/NewSQL** for the inventory core; NoSQL/ES only for the read-shaped catalog. (If we needed global horizontal scale *with* ACID, **Spanner/CockroachDB** is the natural upgrade from sharded Postgres.)
- **Own payments vs. gateway**: integrating Stripe/Adyen slashes PCI scope (we store only tokens) at the cost of a network hop and saga complexity. **Chosen: gateway** — payments are not where we want to take on liability.

**At 10x scale (10M concurrent across many simultaneous on-sales):** more shards, dedicate shards/replicas to mega-events, scale the waiting-room Redis cluster, and push seat-map updates entirely to WebSocket deltas + CDN micro-caching to flatten read fan-out. The waiting room's admission math is what keeps each shard within its serialization budget.

**At 100x / global scale:** move to a **cell-based, region-pinned** architecture — each region owns its local events end-to-end (inventory, booking, queue), so there is no global write bottleneck; a thin routing layer maps `event → home region`. Adopt **Spanner/CockroachDB** for events that genuinely span regions, and make the waiting room a globally-distributed admission mesh that meters per-region capacity. Tickets, search, and analytics are already horizontally scalable and edge-served.

---

## Interview Q&A by Level

### 🟢 Basic
**Q. [Theory] Why can't you just cache seat availability and let users book against the cache?**
A: Because a cache is eventually consistent and offers no atomic compare-and-set across the read-check-write of selling a seat. Two users could both read "AVAILABLE" from the cache and both succeed — a double-sell. The cache is fine for *displaying* availability, but the authoritative "claim this seat only if it's free" must be a strongly-consistent transaction in the inventory DB. The cache is a read accelerator, never the source of truth for inventory.

**Q. [Theory] Why split the flow into a separate "hold" and "book" step instead of one "buy" call?**
A: Payment is slow (seconds) and can fail. If we took payment first, a user could be charged for a seat someone else grabbed during the payment, or the seat would be locked for the entire flaky card flow. Holding the seat first (with a short TTL) reserves the scarce resource instantly, then we run the slow payment against a guaranteed reservation. It mirrors a shopping cart: reserve, then check out.

**Q. [Practical] What happens to a seat if a user holds it but never completes payment?**
A: The hold has a TTL (e.g. 6 minutes) stored as `hold_expires_at`. Two mechanisms release it: lazily, any new booking attempt treats an expired-but-still-HELD seat as available and can claim it within its transaction; and a background sweeper runs every ~30s to bulk-release expired holds and refresh the seat-map cache. Both use conditional updates so a last-millisecond confirm can't race with the release.

### 🟡 Intermediate
**Q. [Theory] How do you guarantee a seat is never sold twice under heavy concurrency?**
A: The flip from AVAILABLE→HELD (and HELD→SOLD) happens inside a single-shard ACID transaction. I shard inventory by `show_id` so all of a show's seats live on one shard, then use pessimistic `SELECT ... FOR UPDATE` to lock the target rows, verify they're all AVAILABLE, and flip them — keeping the transaction microscopically short. The row lock serializes concurrent buyers, so the second one sees HELD/SOLD and gets a 409. Optimistic version-based CAS is an alternative for spread-out demand, but pessimistic locking is more predictable under the dogpile.

**Q. [Practical] How do you keep a double-charge from happening when a client retries the booking call during a spike?**
A: Idempotency keys end-to-end. The client sends an `Idempotency-Key` header on the book call; the booking row has a `UNIQUE` constraint on it, so a retry hits the existing row and returns the original result instead of creating a new booking. Critically, we pass the *same* key to the payment gateway's capture, so the gateway also dedupes — a retried capture returns the original charge, never a second one. A retry is always a safe no-op returning the first outcome.

**Q. [Theory] Why is this system fine with eventual consistency for browsing but not for booking?**
A: A stale availability count while browsing is harmless — the worst case is a user clicks a seat that was just taken and gets a "seat unavailable, here's a fresh map" 409 at hold time. The authoritative check is always the transactional hold, so stale reads never cause a double-sell, only an occasional retry. Booking, by contrast, mutates scarce shared inventory where a wrong answer means selling the same seat twice — that demands strong consistency. So I deliberately run two consistency models: AP on reads, CP on the booking write.

**Q. [Practical] Search is slow when users filter by city + date + genre. What do you do?**
A: Don't serve search from the transactional DB. Index events into Elasticsearch (kept in sync from the SQL source of truth via CDC/Kafka) where faceted, full-text, multi-field filtering is native and fast, and cache popular query results at the edge with short TTLs. The inventory DB is reserved entirely for the consistent booking path; the read-heavy 1000:1 browse load is absorbed by ES + cache + CDN.

### 🟠 Advanced
**Q. [Practical] 1M people hit "buy" for one concert the instant it goes on sale. How does the system survive?**
A: You can't out-provision 1M concurrent writers against ~20K rows — you have to serialize them, so the core mechanism is a **virtual waiting room**. Users join a Redis sorted set and get a position; an admission controller releases the front of the queue into the booking path at a metered rate tied to inventory and DB capacity (e.g. ~1–2K/sec). Everyone else polls a cached position endpoint, never the DB. This converts the 80K-RPS instantaneous storm into a steady, fair, first-come stream the inventory DB can handle with short pessimistic-lock transactions. The queue doubles as the choke point for bot defense and per-account purchase caps.

**Q. [Coding] Write the core atomic seat-hold logic and explain the failure modes.**
A: A minimal pessimistic transaction:
```python
def hold_seats(conn, show_id, seat_ids, user_id, ttl_min=6):
    with conn.transaction():                       # BEGIN
        rows = conn.execute(
            """SELECT seat_id, status, hold_expires_at
                 FROM seat_inventory
                WHERE show_id = %s AND seat_id = ANY(%s)
                FOR UPDATE""",                       # row locks; serializes buyers
            (show_id, seat_ids)).fetchall()

        if len(rows) != len(seat_ids):
            raise NotFound("unknown seat")

        now = utcnow()
        for r in rows:
            available = (r.status == AVAILABLE) or \
                        (r.status == HELD and r.hold_expires_at < now)  # lazy expiry
            if not available:
                raise Conflict(f"{r.seat_id} taken")  # ROLLBACK → 409 + fresh map

        conn.execute(
            """UPDATE seat_inventory
                  SET status = %s, held_by = %s,
                      hold_expires_at = %s, version = version + 1
                WHERE show_id = %s AND seat_id = ANY(%s)""",
            (HELD, user_id, now + timedelta(minutes=ttl_min), show_id, seat_ids))

        hold_id = create_hold(conn, show_id, user_id, seat_ids, now + ttl)
    publish_seatmap_delta(show_id, seat_ids, HELD)   # after COMMIT, best-effort
    return hold_id
```
Failure modes: (1) **contention** — concurrent holders block on `FOR UPDATE`; the loser sees HELD and gets a clean 409, so keep the transaction tiny to minimize lock-hold time. (2) **partial availability** — if any seat is taken we roll back the whole hold (all-or-nothing). (3) **expired holds** — handled lazily inside the same lock, so we don't need to wait for the sweeper. (4) **cache delta after commit** — if the publish fails the DB is still correct; the sweeper/next read reconciles the cache.

**Q. [Practical] The payment gateway times out after you've sent a capture — you don't know if money moved. What now?**
A: Treat the gateway as the source of truth and never guess. Leave the booking PENDING and the seats HELD (don't release on an unknown outcome). A reconciliation job queries the gateway by idempotency key: if the charge succeeded, complete the booking (flip seats to SOLD, issue tickets); if it failed, release the hold and mark the booking FAILED. If the hold already expired and the seats were resold but the charge went through, auto-refund. The invariant is: never a captured payment without a ticket, never a ticket without a payment — and we converge to it via reconciliation, not by acting on an ambiguous timeout.

**Q. [Theory] Optimistic vs. pessimistic locking for seat inventory — when would you pick each?**
A: Pessimistic (`FOR UPDATE`) when contention is *concentrated* — everyone fighting for the same premium seats during a hot on-sale — because it serializes cleanly with a bounded, predictable cost and avoids wasted retry work. Optimistic (version/CAS) when demand is *spread out* across many seats and conflicts are rare, since it avoids holding locks and gives higher throughput. For ticketing, the design driver is the dogpile, so I default to pessimistic with tiny transactions plus a waiting room to cap arrival rate; I'd use optimistic CAS for low-demand events or as a Redis fast-path in front of the authoritative DB.

### 🔴 Expert
**Q. [Practical] Redesign for global scale with simultaneous mega-on-sales in multiple regions.**
A: Go cell-based and region-pinned. Each event has a *home region* that owns its inventory, booking service, and waiting room end-to-end, so there's no global write coordination and no single bottleneck — capacity scales by adding cells/regions. A thin globally-replicated routing layer maps `event_id → home region`; user traffic for an event is routed there. For the rare event that genuinely must sell across regions, use **Spanner/CockroachDB** (TrueTime/Raft-based strong consistency) instead of sharded Postgres, accepting higher write latency for global ACID. The waiting room becomes a distributed admission mesh metering each region to its local DB budget. Reads (search, seat maps, tickets) are already edge-served and globally cacheable, so they need no special handling.

**Q. [Theory] How would you prevent scalpers and bots from sweeping the best seats?**
A: Layer defenses at the waiting room (the natural choke point): device fingerprinting and behavioral scoring to detect automation, CAPTCHA/proof-of-work gates before admission, strict per-account and per-payment-method purchase caps enforced transactionally, velocity limits per IP/account, and queue-fairness so bots can't cut. Tie purchases to verified identity for high-demand events (named tickets, ID-at-entry), use delayed/randomized ticket delivery to blunt instant resale, and run async fraud scoring on the Kafka event stream to claw back flagged bookings. It's defense-in-depth — no single check suffices, but the metered queue makes mass automated buying economically painful.

**Q. [Coding] How do you sell general-admission inventory (a quantity counter, not seats) without overselling under concurrency?**
A: A single conditional atomic update on one counter row — no seat rows, no row-scan:
```sql
UPDATE ga_inventory
   SET held = held + :qty, version = version + 1
 WHERE show_id = :show
   AND (total - sold - held) >= :qty;     -- only succeeds if enough remain
-- rows_affected = 1 → reserved; 0 → sold out, return 409
```
Because it's a single-row conditional update, the DB serializes concurrent buyers on that row's lock; the check and decrement are atomic, so we can never drive `sold + held` above `total`. Confirming a payment moves the count `held → sold` in the same atomic style; an expired hold moves it back `held → 0`. For extreme throughput I'd front it with a Redis `DECRBY` fast-path (atomic, reject at zero) and reconcile to the DB, but the DB row remains the authority that makes overselling impossible.

**Q. [Behavioral] You shipped a booking flow and within a week support reports a handful of customers were charged but received no tickets. How do you handle it?**
A: First, stop the bleeding and make customers whole — immediately run a reconciliation pass over PENDING bookings with a successful `payment_ref` to either issue the owed tickets or refund where seats were lost, and proactively contact affected customers rather than waiting for complaints. Then root-cause without blame: pull traces to find where the saga breaks — almost certainly a crash or timeout between payment capture and the seats-to-SOLD commit, the exact dangerous window. Fix forward by making the reconciliation job continuous (not manual), adding alerting on PENDING-with-charge bookings older than a threshold, and adding an integration test that injects a crash between capture and commit. Finally, write a blameless postmortem capturing the invariant we violated (never a charge without a ticket), what monitoring would have caught it sooner, and share it so the team learns. The priority order is: customers first, durable fix second, prevention and learning third.

---

*Key takeaway: a ticket booking system is a masterclass in strong consistency under bursty contention — the real engineering is serializing a thundering herd via a waiting room, guaranteeing single-seat atomicity with short single-shard transactions, and wrapping payment in an idempotent saga so you never charge without a ticket or sell a seat twice.*
