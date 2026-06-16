# Design an E-Commerce Platform (Amazon)

> A full worked system-design problem: a multi-tenant, planet-scale storefront covering catalog, search, cart, checkout, payments, orders, and inventory — with strong consistency where money and stock are at stake and eventual consistency where it buys scale.

[← Back to master index](../../README.md) · [← System Design index](../README.md)

---

## 1. Requirements

### Functional
- **Browse & search catalog**: full-text search, faceted filtering (brand, price, rating), category navigation, product detail pages (PDP).
- **Cart**: add/remove/update items, persistent across sessions and devices, merge anonymous → logged-in cart.
- **Checkout**: address selection, shipping options, promo codes, tax calculation, place order.
- **Payments**: charge card / wallet / UPI, handle 3-D Secure, refunds, idempotent capture.
- **Inventory management**: track stock per SKU per warehouse; **never oversell**; reservations during checkout.
- **Orders**: order lifecycle (CREATED → PAID → FULFILLED → SHIPPED → DELIVERED / CANCELLED / REFUNDED), order history.
- **Recommendations**: "customers who bought…", trending, personalized homepage.
- **Flash sales / hot products**: handle 100x spikes on a single SKU without melting the database.

### Non-Functional
| Attribute | Target | Notes |
|---|---|---|
| **Availability** | 99.99% for browse/cart; 99.95% for checkout | Browse must survive checkout outages |
| **Latency** | PDP/search p99 < 200 ms; add-to-cart p99 < 100 ms; checkout p99 < 1 s | Payment hop dominates checkout |
| **Consistency** | **Strong** for inventory decrement & payment capture; **eventual** for catalog, reviews, recommendations, search index | CAP trade-off varies per service |
| **Durability** | Orders & payments: 0 data loss (RPO=0) | Multi-AZ synchronous replication |
| **Scale** | 200M DAU, peak 10x on sale days | Elastic, region-sharded |

### Clarifying questions a candidate should ask
1. Read-heavy or write-heavy? (E-commerce is ~**100:1 read:write** — browse dominates.) This justifies heavy caching and CQRS.
2. Global or single-region? Global means multi-region catalog replication + data-residency rules.
3. Marketplace (3rd-party sellers) or first-party only? Marketplace adds seller onboarding, multi-warehouse inventory, payout ledgers.
4. Is overselling ever acceptable? For most goods **no**; for some (digital, drop-ship) a tolerable small oversell may be allowed to favor availability.
5. What is the flash-sale magnitude? Determines whether we pre-allocate inventory into "buckets" and use a queue-based admission system.
6. Do we own payments or use a PSP (Stripe/Adyen)? Affects PCI scope.

---

## 2. Capacity Estimation

Assume **200M DAU**, each user does ~30 page views/day, of which ~25 are catalog/search.

**Read QPS (catalog/search):**
```
200M users × 25 views/day = 5B reads/day
5B / 86,400 s ≈ 58,000 QPS average
Peak (sale day, 10x) ≈ 580,000 QPS  → call it ~600K read QPS peak
```

**Write QPS (orders):** suppose 2% of DAU place an order/day → 4M orders/day.
```
4M / 86,400 ≈ 46 orders/sec average
Peak 10x ≈ 460 orders/sec; with retries/idempotency keys ~1K write QPS to OrderSvc
Cart writes ~10x order writes → ~5K cart write QPS peak
```

**Storage:**
- **Catalog**: 500M SKUs × ~5 KB (attributes, descriptions, refs) ≈ **2.5 TB** core; plus images on object store (avg 5 images × 200 KB × 500M ≈ **500 TB** on S3/CDN).
- **Orders**: 4M/day × 2 KB × 365 × 5 yr retention ≈ 4M×2KB×1825 ≈ **~15 TB** (line items in separate table push this to ~40 TB).
- **Cart**: 200M active carts × 2 KB ≈ **400 GB** — fits in a Redis/DynamoDB tier comfortably.
- **Inventory**: 500M SKUs × N warehouses × 200 B. At ~50 warehouses for stocked items but most SKUs in <5: ~ **a few hundred GB**, kept hot.

**Bandwidth:**
```
600K read QPS × 50 KB avg page payload ≈ 30 GB/s egress at peak
```
Almost all of this must be served from **CDN + edge cache**; only cache-miss tail hits origin (target <5% miss → ~1.5 GB/s to origin).

**Memory (cache):** hot working set is ~the top 20% of SKUs that drive 80% of traffic.
```
Hot SKUs ≈ 100M × 5 KB ≈ 500 GB of PDP data in Redis cluster (sharded across ~20 nodes × 64 GB).
```

**Takeaway:** the system is overwhelmingly a **read-scaling and caching** problem, with a small but *correctness-critical* write path (inventory + payment).

---

## 3. API Design

REST/JSON at the edge (GraphQL also reasonable for the storefront BFF); gRPC between internal services.

```http
# --- Catalog / Search ---
GET  /v1/products/{sku}                      -> ProductDetail (cacheable, ETag)
GET  /v1/search?q=shoes&brand=nike&page=2    -> {results[], facets, total}
GET  /v1/categories/{id}/products            -> paginated listing

# --- Cart (idempotent line ops) ---
GET  /v1/cart                                -> Cart
PUT  /v1/cart/items/{sku}   {qty}            -> Cart      # upsert, set absolute qty
DELETE /v1/cart/items/{sku}                  -> Cart

# --- Checkout (idempotent) ---
POST /v1/checkout/sessions  {cartId, addressId, shippingOption}
     Header: Idempotency-Key: <uuid>
     -> { checkoutId, amount, reservationToken, expiresAt }

POST /v1/checkout/{checkoutId}/pay  {paymentMethodId}
     Header: Idempotency-Key: <uuid>
     -> { orderId, status: "PAID" | "PAYMENT_PENDING" }

# --- Orders ---
GET  /v1/orders/{orderId}                    -> Order
GET  /v1/orders?status=SHIPPED               -> paginated
POST /v1/orders/{orderId}/cancel             -> Order

# --- Internal (gRPC) ---
InventoryService.Reserve(sku, qty, reservationToken, ttl) -> {ok|insufficient}
InventoryService.Commit(reservationToken)                 -> ok   # on payment success
InventoryService.Release(reservationToken)                -> ok   # on timeout/failure
PaymentService.Authorize(idemKey, amount, method)         -> authId
PaymentService.Capture(authId, idemKey)                   -> receipt
```

**Idempotency contract:** every state-changing checkout/payment call carries an `Idempotency-Key`. The service stores `(key → result)` in a dedup table with TTL; a retry with the same key returns the *stored* result instead of re-executing. This makes "place order" safe against client retries, double-clicks, and network timeouts.

---

## 4. Data Model

We deliberately use a **polyglot persistence** strategy — pick the store that matches each service's consistency and access pattern.

### Catalog — Document store (MongoDB / DynamoDB)
Products have heterogeneous, nested attributes (a TV vs a t-shirt have nothing in common). A flexible schema avoids hundreds of sparse SQL columns.
```json
// products (DynamoDB: PK=sku)
{ "sku":"B07X", "title":"...", "brand":"Nike", "categoryId":"shoes",
  "price":{"amount":12999,"currency":"INR"}, "attrs":{"size":[7,8,9],"color":"red"},
  "images":["s3://..."], "rating":4.6, "version":42 }
```

### Search — Inverted index (Elasticsearch / OpenSearch)
Denormalized, eventually consistent copy of the catalog fed by CDC. Optimized for full-text + faceting, **not** the source of truth.

### Cart — Key-value (DynamoDB or Redis)
```json
// PK = userId (or anonymous device token)
{ "userId":"u1", "items":[{"sku":"B07X","qty":2,"priceSnapshot":12999}], "updatedAt":... }
```
Low-durability-tolerant, single-key access, very high write rate → KV is ideal; **no joins needed**.

### Orders — Relational (PostgreSQL / Aurora), sharded by userId
Money requires ACID transactions, foreign keys, and auditability. Strong consistency is non-negotiable.
```sql
orders(order_id PK, user_id, status, total_amount, currency, idempotency_key UNIQUE,
       created_at, updated_at)
order_items(order_id FK, sku, qty, unit_price, line_total)
payments(payment_id PK, order_id FK, auth_id, status, captured_amount, idempotency_key UNIQUE)
outbox(id, aggregate_id, event_type, payload, published BOOL)   -- transactional outbox
```

### Inventory — Relational with row-level locking (PostgreSQL), or DynamoDB conditional writes
Correctness-critical, must support atomic decrement.
```sql
inventory(sku, warehouse_id, available INT, reserved INT, version INT,
          PRIMARY KEY(sku, warehouse_id))
reservations(token PK, sku, qty, status, expires_at)
```

**SQL vs NoSQL summary:** SQL for **orders, payments, inventory** (transactions, integrity, money). NoSQL/KV for **catalog, cart, sessions** (scale, flexible schema, simple access). Search and recommendations are derived, eventually-consistent read models.

---

## 5. High-Level Architecture

```
                              ┌─────────────┐
   Clients (web/mobile) ─────▶│   CDN/Edge   │  (static assets, cached PDPs, images)
                              └──────┬───────┘
                                     │ cache-miss / dynamic
                              ┌──────▼───────┐
                              │ API Gateway  │  authN, rate-limit, routing, TLS
                              │   + BFF      │
                              └──┬───┬───┬───┘
            ┌────────────────────┘   │   └────────────────────┐
            ▼                        ▼                        ▼
   ┌────────────────┐      ┌──────────────────┐      ┌────────────────┐
   │ Catalog Service│      │  Cart Service    │      │ Search Service │
   │ (Dynamo/Mongo) │      │  (Redis/Dynamo)  │      │ (Elasticsearch)│
   └───────┬────────┘      └──────────────────┘      └────────────────┘
           │ CDC                                             ▲ index updates
           │                ┌──────────────────────────────┘
           ▼                │
      ┌─────────┐     ┌─────┴──────────┐   reserve/commit   ┌────────────────┐
      │  Kafka  │◀───▶│ Checkout/Order │◀──────────────────▶│ Inventory Svc  │
      │ (events)│     │   Service      │                    │ (Postgres,lock)│
      └────┬────┘     │  (Postgres)    │                    └────────────────┘
           │          └───┬────────┬───┘
           │              │        │  authorize/capture
           ▼              │        ▼
   ┌───────────────┐      │   ┌──────────────┐    ┌──────────────────┐
   │ Recommendation│      │   │ Payment Svc  │───▶│ External PSP      │
   │  (read model) │      │   │ (idempotent) │    │ (Stripe/Adyen)    │
   └───────────────┘      │   └──────────────┘    └──────────────────┘
           ▲              ▼
           │      ┌───────────────┐
   CQRS read model │ Fulfillment / │ Shipping / Notification (Kafka consumers)
   built from Kafka│  Warehouse    │
                   └───────────────┘
```

**Walkthrough.** A request hits the **CDN** first; product pages and images are largely served from edge cache. Dynamic and cache-miss traffic flows to the **API Gateway/BFF**, which authenticates, rate-limits, and fans out to services. **Catalog**, **Cart**, and **Search** are independently scalable read paths. The **Checkout/Order Service** orchestrates the write path: it reserves inventory, captures payment, and persists the order in one Postgres transaction (plus an outbox row). **Kafka** is the backbone for asynchronous propagation — CDC streams catalog changes into the search index and recommendation read models, and order events drive fulfillment, shipping, and notifications. Each service owns its database (database-per-service); no cross-service joins.

---

## 6. Deep Dives

### 6.1 Inventory & the overselling problem (strong consistency)
The single hardest correctness problem. Two buyers must never both win the last unit.

**Reservation pattern (recommended).** Checkout is two-phase:
1. **Reserve** at checkout-session creation: atomically `available -= qty; reserved += qty` with a TTL (e.g. 10 min). Returns a `reservationToken`.
2. **Commit** on payment success: `reserved -= qty` (stock leaves the building). On timeout/abandonment, a sweeper **releases**: `available += qty; reserved -= qty`.

The atomic decrement must be safe under concurrency. Options:

| Mechanism | How | Trade-off |
|---|---|---|
| **DB conditional update** | `UPDATE inventory SET available=available-1 WHERE sku=? AND available>=1` and check rows-affected | Simple, correct, but a hot row serializes all writers → bottleneck on flash SKUs |
| **Optimistic concurrency (version)** | read `available,version`; `UPDATE ... WHERE version=?`; retry on conflict | Great for low contention; degenerates to retry storms on hot keys |
| **Distributed lock (Redis Redlock)** | acquire lock on `sku` before decrement | Adds latency + lock-expiry edge cases; avoid if a DB conditional update suffices |
| **Inventory buckets / sharded counters** | split 1000 units into 10 buckets of 100, decrement a random bucket | Removes the single hot row; rebalance when a bucket empties — best for flash sales |

**Why prefer reservation + conditional update over distributed locks:** a `WHERE available>=qty` conditional update is *already* atomic and serializable inside the DB — it does the locking for you, with no risk of a lock-holder crashing mid-flight. Reach for Redlock only when the decrement spans multiple resources the DB can't transact over together.

### 6.2 Order Saga (orchestrated, distributed transaction)
Placing an order touches Inventory, Payment, and Order DBs — no single ACID transaction spans them. We use a **Saga** with compensating actions, driven by an orchestrator in the Checkout Service:

```
1. ReserveInventory(token)        ──compensate──▶ ReleaseInventory(token)
2. AuthorizePayment(idemKey)      ──compensate──▶ VoidAuthorization(authId)
3. CommitInventory(token)         ──compensate──▶ (rare; flag for manual)
4. CapturePayment(authId)         ──compensate──▶ RefundPayment
5. PersistOrder + emit OrderPaid
```
If any step fails, the orchestrator runs the compensations for completed steps in reverse. Saga gives **atomicity-of-effect without distributed 2PC** (which would couple availability of all participants). Choreography (pure event-driven) is an alternative but makes the global flow hard to reason about; **orchestration wins for checkout** because the happy/failure paths are well-defined and need clear ownership.

**Transactional outbox** guarantees the `OrderPaid` event is published iff the order row commits: the event is written to an `outbox` table in the *same* transaction, then a relay (Debezium/CDC) ships it to Kafka. This eliminates the dual-write problem (DB committed but Kafka publish lost).

### 6.3 Idempotent checkout & payment
Network timeouts make the client unsure whether "pay" succeeded — it retries. Without idempotency that double-charges.
- Client generates an `Idempotency-Key` (UUID) per logical attempt.
- Payment service stores `UNIQUE(idempotency_key)`; first call inserts + executes, returns result; duplicate insert violates the constraint → service returns the *previously stored* result.
- Combined with PSP-side idempotency keys, this makes capture **exactly-once-effect**.
- The order's own `idempotency_key UNIQUE` prevents two orders from one checkout.

### 6.4 Read scaling via CQRS + caching (eventual consistency)
Catalog/search/recommendations are read-dominated (~100:1) and tolerate staleness of seconds.
- **CQRS**: writes go to the catalog source-of-truth (Dynamo); a CDC stream (DynamoDB Streams → Kafka) projects into **read models**: Elasticsearch for search, denormalized PDP documents in Redis, recommendation matrices. Readers never touch the write store.
- **Multi-layer cache**: CDN (edge) → API-gateway cache → Redis (PDP/price) → DB. PDPs carry an `ETag`/`version`; price/stock badges fetched separately so the heavy PDP body stays cached while volatile fields refresh.
- **Consistency window**: a price change propagates via CDC in <1s; the cart re-validates `priceSnapshot` at checkout so a user is never charged a stale price.

### 6.5 Hot-key & flash-sale handling
A single SKU (new iPhone) can take 100x normal traffic — both reads and the inventory write row become hot.
- **Reads**: pin the PDP in every cache tier; use **request coalescing** (single-flight) so a cache miss triggers one origin fetch, not a thundering herd. Serve a slightly stale price with short TTL.
- **Writes (the last-unit war)**:
  - **Pre-allocate inventory into buckets** (§6.1) to shard the hot row.
  - **Admission control / virtual waiting room**: front the SKU with a Kafka-backed queue. Users get a token; the system admits N/sec into checkout matching the reservation rate. This converts a stampede into a smooth stream and gives a fair FIFO experience.
  - **Sell-out fast-path**: once `available==0`, a Redis flag short-circuits all further reserve attempts at the edge — no DB hit at all.

### 6.6 Sharding & unique IDs
- **Orders** sharded by `user_id` (a user's order history co-locates; checkout for a user hits one shard). `order_id` = **Snowflake** (timestamp + shard + sequence) → globally unique, roughly time-sortable, no central allocator.
- **Inventory** partitioned by `sku` (or `sku, warehouse`) so a SKU's stock lives on one node — its conditional updates serialize locally without cross-shard coordination.

---

## 7. Scaling, Bottlenecks & Failure Handling

**What breaks first?** The **inventory hot row** under flash sales and the **order DB write throughput** on sale days. Mitigations: bucketed counters + admission queue for the former; shard Orders by user and use read replicas for order-history reads for the latter.

**Read path scaling:** statelessly horizontal — add gateway/BFF and catalog replicas behind a load balancer; lean on CDN to absorb 95%+ of read bytes. Elasticsearch scales by adding shards/replicas.

**Replication & partitioning:**
- Orders/Payments/Inventory: **synchronous multi-AZ** replication (RPO=0). Async cross-region replica for DR.
- Catalog/Cart: async replication, read-anywhere; tolerate brief staleness.
- Kafka: replication factor 3, min-ISR 2.

**Failure isolation / resilience:**
- **Bulkheads**: separate connection pools and clusters per service so a Payment slowdown can't exhaust threads serving browse.
- **Circuit breakers** around the PSP and Inventory gRPC: if the PSP is degraded, fail fast and queue payment for async retry rather than holding checkout threads.
- **Graceful degradation**: if Recommendations is down, render generic best-sellers; if Search is down, fall back to category browse. **Browsing must never depend on checkout.**
- **Idempotency + retries with exponential backoff + jitter** everywhere on the write path.
- **Reservation TTL sweeper** reclaims stock from abandoned checkouts so inventory isn't leaked.

**Disaster recovery:** active-passive (or active-active for catalog) across regions; orders fail over to a warm standby with continuous WAL shipping. Target RTO < 15 min, RPO = 0 for orders.

**CAP positioning:** Inventory and Payment choose **CP** — on a partition we refuse to sell rather than risk overselling/double-charge (consistency over availability). Catalog, Search, Cart, Recommendations choose **AP** — keep serving (possibly stale) data through partitions. This per-service CAP choice is the heart of the design.

---

## 8. Trade-offs & Alternatives

| Decision | Chosen | Alternative | Why |
|---|---|---|---|
| Transaction model | **Saga (orchestrated)** | 2PC across services | 2PC blocks and couples availability; saga keeps services autonomous |
| Inventory concurrency | **DB conditional update + reservation** | Redis distributed lock everywhere | DB conditional update is atomic without a fragile external lock |
| Inventory consistency | **Strong (CP)** | Eventual w/ reconciliation | Overselling physical goods is a real-money, brand-damaging error |
| Catalog/search | **Eventual (AP) via CDC** | Strongly consistent reads | 100:1 read ratio; seconds of staleness is fine and unlocks massive cache hit rates |
| Order store | **Sharded SQL** | Single NoSQL store | Money needs ACID, integrity, and auditable transactions |
| Event backbone | **Kafka + transactional outbox** | Direct synchronous calls / dual writes | Decouples services, guarantees no lost events |

**At 10x scale:** add inventory bucketing by default, more aggressive multi-region read replicas, and a dedicated waiting-room service for every launch SKU. **At 100x scale:** regionally shard the *entire* stack (cell-based architecture / "cells" per geography), move catalog to a globally distributed store (Spanner/CockroachDB or DynamoDB Global Tables), and treat each region as an independent failure domain with its own inventory ledger reconciled centrally. Consider moving recommendation serving to a separate online feature store + ANN index.

---

## Interview Q&A by Level

### 🟢 Basic
**Q: Why not store everything in one relational database?**
A: Access patterns and consistency needs differ wildly. Catalog is read-heavy with flexible schema (NoSQL fits); orders/payments need ACID (SQL fits); carts are simple high-write KV. One DB would be a scaling and schema bottleneck and couple all teams together. Polyglot persistence + database-per-service lets each scale independently.

**Q: How does add-to-cart work and why is it fast?**
A: A `PUT /cart/items/{sku}` upserts a single key in Redis/DynamoDB keyed by user. No joins, no transactions across services, so it's a single-digit-millisecond write. Price is snapshotted but re-validated at checkout.

**Q: Why use a CDN?**
A: ~30 GB/s of read egress at peak is dominated by images and largely-static product pages. The CDN serves these from the edge, cutting origin load by 95%+ and slashing latency for global users.

### 🟡 Intermediate
**Q: How do you prevent overselling the last unit?**
A: Two-phase reservation. At checkout we atomically decrement with a conditional update `WHERE available >= qty` (rows-affected tells us success) and mark stock `reserved` with a TTL. On payment success we commit; on timeout a sweeper releases. The DB conditional update is itself atomic, so two concurrent buyers can't both succeed.

**Q: What's CQRS and why use it here?**
A: Command Query Responsibility Segregation: writes hit the source-of-truth catalog store; a CDC stream projects denormalized read models (Elasticsearch, Redis PDPs, recommendations). Readers scale independently of writers, each read model is shaped for its query, and we accept seconds of eventual consistency in exchange for huge read throughput.

**Q: How is checkout made idempotent?**
A: The client sends an `Idempotency-Key`. Payment and Order tables have a unique constraint on it; the first request executes and stores the result, retries hit the constraint and return the stored result. Combined with PSP idempotency keys this yields exactly-once payment effects.

### 🟠 Advanced
**Q: Walk through the order saga and its compensations.**
A: ReserveInventory → AuthorizePayment → CommitInventory → CapturePayment → PersistOrder+emit event. On failure, compensate in reverse: VoidAuthorization, ReleaseInventory. An orchestrator owns the state machine; a transactional outbox guarantees the `OrderPaid` event publishes iff the order commits, avoiding the dual-write problem. We choose saga over 2PC to keep services autonomous and avoid blocking locks across services.

**Q: How do you handle a flash sale on one SKU?**
A: Reads: pin the PDP in all cache tiers, use single-flight request coalescing, set a sell-out Redis flag to short-circuit at the edge once stock hits zero. Writes: split inventory into bucketed counters to remove the hot row, and front the SKU with a Kafka-backed virtual waiting room that admits buyers at the rate inventory can be reserved — converting a stampede into a fair, smooth stream.

**Q: Where do you place each service on the CAP spectrum?**
A: Inventory and Payment are CP — on a partition we'd rather reject than oversell or double-charge. Catalog, Search, Cart, and Recommendations are AP — they keep serving possibly-stale data through partitions. The art is making this choice per service rather than globally.

### 🔴 Expert
**Q: A reservation is committed but the payment capture later fails at the PSP. Now stock is gone but unpaid. How do you reconcile?**
A: This is the saga's hardest compensation. Options: (1) keep payment **authorized** (hold) before committing inventory and only *capture* after commit, so a capture failure leaves money un-taken and we re-release stock; (2) if commit-then-capture ordering is forced, treat capture failure as a retriable step with backoff, and if it permanently fails, run a compensating refund-equivalent — re-stock via an `InventoryRestock` event and flag the order CANCELLED. A reconciliation job continuously diffs `payments` vs `inventory commits` to catch stragglers. The lesson: order saga steps so the *reversible* action (auth) happens before the *hard-to-reverse* one (physical stock commit).

**Q: How do you guarantee no events are lost between the Order DB and Kafka?**
A: Transactional outbox: the domain change and an `outbox` row are written in the same DB transaction. A CDC relay (Debezium reading the WAL) publishes outbox rows to Kafka and marks them published. Because the write is atomic, an event exists in the outbox iff the order committed; the relay provides at-least-once delivery, and consumers dedupe by event id for effectively-once processing.

**Q: At 100x scale, how does the architecture change?**
A: Move to a cell-based architecture — partition the entire stack by geography into independent cells, each with its own inventory ledger, order shards, and caches, so a cell is a blast-radius boundary. Catalog moves to a globally distributed store (DynamoDB Global Tables / Spanner) with regional read locality. Inventory becomes a per-region ledger reconciled to a global view asynchronously, accepting that cross-region stock visibility is eventually consistent while within-region sales stay strongly consistent. Recommendations move to an online feature store plus an approximate-nearest-neighbor index served separately from the transactional path.

**Q: How do you keep the search index consistent with the catalog?**
A: It's intentionally eventually consistent. Catalog writes emit CDC events to Kafka; an indexer consumes them and updates Elasticsearch, using the document `version` to ignore out-of-order updates (last-writer-wins by version). Typical lag is sub-second. If the indexer falls behind or a reindex is needed, we can replay the Kafka topic from the catalog snapshot — the index is a fully rebuildable derived view, never the source of truth.

---

[← Back to master index](../../README.md) · [← System Design index](../README.md)
