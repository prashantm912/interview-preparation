# Design a Payment Gateway

A worked system-design problem: build a PCI-compliant payment gateway that authorizes, captures, and settles card payments through external PSPs/card networks, with a double-entry ledger, exactly-once semantics, and strong consistency on money movement.

[← Back to master index](../../README.md) · [← System Design index](../README.md)

---

## 1. Requirements

A payment gateway sits between a merchant's checkout and the card networks / payment service providers (PSPs). It accepts a payment request, runs fraud and authorization, captures funds, and eventually settles money to the merchant — all while never losing or duplicating money.

### Functional requirements
- **Authorize** a card payment (place a hold on the cardholder's funds).
- **Capture** an authorized amount (move the held funds), supporting partial and multiple captures.
- **Settle** captured funds to the merchant's account (batch, T+1/T+2).
- **Refund** (full/partial) and **void** (cancel an un-captured auth).
- **Idempotency**: a retried request with the same idempotency key must never charge twice.
- **Tokenize** card data so merchants never touch raw PAN (Primary Account Number).
- **Double-entry ledger**: every money movement is recorded as balanced debit/credit entries.
- **Reconciliation**: match internal ledger against PSP/network settlement files daily.
- **Fraud hooks**: synchronous risk scoring before authorization; async rules afterward.
- **Webhooks**: notify merchants of state transitions (authorized, captured, failed, refunded).
- **Audit trail**: immutable, append-only record of every state change for compliance.

### Non-functional requirements
- **Consistency**: strong consistency for money — the ledger must be the single source of truth. No double-spend, no phantom funds. We trade availability for correctness on the write path (CP in CAP terms).
- **Durability**: a committed transaction is never lost. RPO ≈ 0 for the ledger.
- **Availability**: 99.99% for the authorization API (about 52 min/year downtime). The gateway is in the critical path of merchant revenue.
- **Latency**: p99 < 500 ms for authorize end-to-end (most of the budget is the external PSP round-trip, typically 150–300 ms).
- **Security/compliance**: PCI-DSS Level 1, tokenization, encryption at rest and in transit, key rotation, SOC 2.
- **Exactly-once effects**: at-least-once delivery + idempotency = exactly-once observable effect.

### Clarifying questions a candidate should ask
1. Card-present (terminals) or card-not-present (online) or both? (Assume CNP / online.)
2. Are we the PSP/acquirer ourselves, or do we integrate external PSPs (Stripe, Adyen, Braintree)? (Assume we orchestrate multiple PSPs and connect to networks via an acquirer.)
3. Single currency or multi-currency / FX? (Assume multi-currency, no FX conversion in v1.)
4. Do we store card data ourselves (full PCI scope) or use a vault/network tokens? (Assume we run a tokenization vault — Level 1 scope.)
5. Expected scale and growth? (Drives the capacity math below.)
6. Regulatory footprint — PSD2/SCA in EU, 3-D Secure mandates? (Assume yes; SCA challenge flow required.)

---

## 2. Capacity Estimation

Assume a mid-to-large gateway.

```
Peak throughput target:        10,000 payments/sec (peak, e.g. Black Friday)
Average throughput:            2,000 payments/sec
Daily payment volume:          2,000 * 86,400 ≈ 173M payments/day  (~170M)
```

**QPS / write amplification.** A single payment is not one write. It fans out:
```
1 authorize  -> 1 fraud score + 1 PSP call + ~4 ledger entries + 1 audit row
1 capture    -> 1 PSP call + ~4 ledger entries + 1 audit row
1 settle     -> batched
Plus webhooks, state machine transitions, reconciliation reads.
```
Rough write QPS to the datastore at peak:
```
10,000 payments/s * ~10 row writes (txn + ledger entries + audit + state) = ~100,000 writes/s
```
That is the number that decides our storage engine — 100k durable writes/sec is well beyond a single Postgres primary, so the ledger must be **partitioned/sharded** (see §7).

**Storage.** Per payment we persist: transaction record (~1 KB), 4–8 ledger entries (~200 B each ≈ 1.5 KB), audit events (~1 KB), token mapping (one-time per card). Call it ~4 KB/payment of durable, retained data.
```
170M payments/day * 4 KB ≈ 680 GB/day
Yearly (raw):                680 GB * 365 ≈ 248 TB/year
With replication factor 3:   ~744 TB/year
```
Compliance often mandates 7-year retention for financial records:
```
248 TB/year * 7 ≈ 1.7 PB raw  (hot in cluster for ~90 days, rest tiered to cold object storage / WORM)
```

**Bandwidth.** Authorize request/response payloads are small (~2 KB each way).
```
10,000 req/s * 2 KB * 2 (req+resp) ≈ 40 MB/s ≈ 320 Mbps at peak  — trivial network-wise.
```
The bottleneck is not bandwidth; it's durable write IOPS and PSP latency.

**Memory / cache.** Idempotency keys must be checked on every request. Keep an in-memory index (Redis) of recent keys:
```
Keep idempotency keys for 24h (replay window).
170M/day * 1 key * ~100 B (key + status + result pointer) ≈ 17 GB/day
=> ~20–25 GB Redis (sharded), TTL 24–72h. Comfortable.
```
Hot merchant config + token-vault lookups also cached, a few GB more.

**Takeaways from the math:** the design is dominated by (1) ~100k durable writes/sec needing horizontal partitioning, (2) external PSP latency dominating the latency budget, and (3) multi-petabyte long-term retention forcing tiered/WORM cold storage.

---

## 3. API Design

REST over HTTPS (TLS 1.3), mutual TLS for server-to-PSP. All mutating endpoints require an `Idempotency-Key` header.

```http
POST /v1/payments
Authorization: Bearer <merchant_api_key>
Idempotency-Key: 6f1c... (client-generated UUID, unique per logical attempt)
Content-Type: application/json

{
  "amount":        4999,                // minor units (cents). NEVER floats.
  "currency":      "USD",
  "capture":       false,               // false = auth-only (auth+capture later)
  "payment_method": { "token": "tok_visa_4242" },  // network/vault token, never raw PAN
  "merchant_id":   "mrc_123",
  "order_id":      "ord_998",
  "customer":      { "id": "cus_77", "ip": "203.0.113.5" },
  "three_ds":      { "required": "automatic" }
}

200 OK
{
  "id": "pay_01HXX...",
  "status": "authorized",               // requires_action | authorized | captured | failed
  "amount": 4999, "amount_captured": 0,
  "psp": "adyen", "network_txn_id": "...",
  "risk": { "score": 12, "decision": "allow" },
  "created_at": "2026-06-16T10:00:00Z"
}
```

```http
POST /v1/payments/{id}/capture     { "amount": 4999 }   # partial allowed
POST /v1/payments/{id}/refund      { "amount": 1000, "reason": "customer_request" }
POST /v1/payments/{id}/void        # cancels an un-captured authorization
GET  /v1/payments/{id}             # current state + state history
GET  /v1/ledger/entries?txn_id={id}
```

**Idempotency contract.** The `Idempotency-Key` is scoped to `(merchant_id, key)`. First request with a key creates a record `(key -> in_progress)`. Concurrent duplicates get `409 conflict_in_progress`; replays after completion return the **stored original response** byte-for-byte (with `Idempotent-Replayed: true`). Keys expire after 24–72h.

**Webhooks** (gateway → merchant), signed with HMAC-SHA256, at-least-once delivery, exponential backoff, merchant must dedupe on `event.id`:
```json
{ "id": "evt_...", "type": "payment.captured", "data": { "id": "pay_...", "amount": 4999 } }
```

---

## 4. Data Model

We choose **relational (PostgreSQL/Spanner-class)** for the ledger and transactions, and selectively NoSQL for high-volume append-only logs.

**Why SQL for money:** ACID transactions, multi-row atomic commits (debit and credit in one transaction), strong consistency, mature constraint enforcement, and the double-entry invariant `SUM(debits) = SUM(credits)` is naturally expressed and checkable. Eventual consistency on a ledger is a non-starter — a read that sees a debit but not its matching credit shows fake money. At our write volume we use a **horizontally-partitioned SQL** approach (Vitess/Citus) or a NewSQL store (**CockroachDB / Google Spanner / TiDB**) that gives distributed ACID with serializable isolation.

```sql
-- Transactions: the payment lifecycle (one row per payment intent)
CREATE TABLE transactions (
  id              UUID PRIMARY KEY,           -- ULID/Snowflake, time-sortable
  merchant_id     UUID NOT NULL,              -- SHARD KEY
  idempotency_key TEXT NOT NULL,
  status          TEXT NOT NULL,              -- enum (state machine)
  amount          BIGINT NOT NULL,            -- minor units, integer (no floats)
  amount_captured BIGINT NOT NULL DEFAULT 0,
  currency        CHAR(3) NOT NULL,
  psp             TEXT, network_txn_id TEXT,
  created_at      TIMESTAMPTZ, updated_at TIMESTAMPTZ,
  version         INT NOT NULL DEFAULT 0,     -- optimistic concurrency control
  UNIQUE (merchant_id, idempotency_key)       -- DB-level exactly-once guard
);

-- Double-entry ledger: append-only, immutable. Every movement = balanced rows.
CREATE TABLE ledger_entries (
  id          UUID PRIMARY KEY,
  txn_id      UUID NOT NULL REFERENCES transactions(id),
  account_id  UUID NOT NULL,                  -- e.g. merchant_receivable, psp_clearing, fees
  direction   CHAR(1) NOT NULL,               -- 'D' debit | 'C' credit
  amount      BIGINT NOT NULL CHECK (amount > 0),
  currency    CHAR(3) NOT NULL,
  posted_at   TIMESTAMPTZ NOT NULL,
  entry_group UUID NOT NULL                   -- all entries of one movement; SUM(D)=SUM(C)
);
-- Invariant enforced in the SAME transaction that inserts the group.

CREATE TABLE accounts (
  id UUID PRIMARY KEY, owner_type TEXT, owner_id UUID,
  kind TEXT,                                   -- asset|liability|revenue|clearing
  currency CHAR(3),
  balance BIGINT NOT NULL DEFAULT 0            -- materialized; reconciled vs SUM(entries)
);

-- Idempotency store (also fronted by Redis)
CREATE TABLE idempotency_keys (
  merchant_id UUID, key TEXT, status TEXT,     -- in_progress|completed
  response_body JSONB, request_hash TEXT,      -- detect key reuse w/ different body
  created_at TIMESTAMPTZ, expires_at TIMESTAMPTZ,
  PRIMARY KEY (merchant_id, key)
);

-- Tokens live in a SEPARATE, isolated PCI vault DB (encrypted, restricted access)
CREATE TABLE token_vault (
  token TEXT PRIMARY KEY, pan_encrypted BYTEA, last4 CHAR(4),
  brand TEXT, exp_month INT, exp_year INT, network_token TEXT
);

-- Audit log: append-only, partitioned by day, WORM-tiered after 90d
CREATE TABLE audit_events (
  id UUID, txn_id UUID, actor TEXT, event TEXT, payload JSONB, at TIMESTAMPTZ
);
```

**Where NoSQL fits.** The **audit log** and **webhook delivery log** are append-only, write-heavy, never updated — a great fit for Cassandra/DynamoDB (linear write scaling, TTL, cheap). Risk/fraud feature data and event streams flow through **Kafka**. The money-moving ledger stays in distributed SQL.

**Sharding key:** `merchant_id`. A merchant's transactions, ledger entries, and balances co-locate, so a payment's multi-row commit stays within one shard (single-shard ACID, no cross-shard 2PC on the hot path).

---

## 5. High-Level Architecture

```
                         ┌──────────────────────────────────────────────┐
   Merchant / Checkout    │            EDGE / SECURITY LAYER             │
        │  HTTPS+mTLS      │  CDN · WAF · TLS termination · rate limit    │
        ▼                  └───────────────────────┬──────────────────────┘
   ┌─────────┐                                     ▼
   │API Gateway│  auth (API key/JWT), idempotency-key extraction
   └────┬─────┘
        ▼
 ┌──────────────────┐   sync risk score    ┌──────────────┐
 │ Payment Orchestr.│◄────────────────────►│ Fraud / Risk │  (ML model, rules, velocity)
 │  (Saga manager)  │                       └──────────────┘
 └───┬───────┬──────┘
     │       │ resolve token            ┌──────────────┐
     │       └─────────────────────────►│ Token Vault  │ (PCI scope, isolated, HSM keys)
     │                                   └──────────────┘
     │ atomic write (txn + ledger entries, single shard, serializable)
     ▼
 ┌────────────────────────────────────────────────────────────┐
 │      LEDGER + TRANSACTION STORE (distributed SQL, CP)        │
 │   shard by merchant_id · synchronous replication (Raft)     │
 └───────────────┬─────────────────────────────────────────────┘
                 │ outbox (CDC)                ┌──────────────┐
                 ▼                              │  Redis       │ idempotency keys,
 ┌──────────────────────────┐                  │  (cache)     │ hot config, balances
 │  Kafka  (event backbone) │◄─────────────────┘──────────────┘
 └───┬───────────┬──────────┘
     │           │
     ▼           ▼
 ┌─────────┐  ┌──────────────┐   ┌──────────────────────────────────────┐
 │ Webhook │  │ PSP Adapter  │──►│  PSP / Acquirer / Card Networks      │
 │ delivery│  │ (per-PSP)    │   │  (Adyen, Stripe, Visa/MC, ACH)       │
 └─────────┘  └──────────────┘   └──────────────────────────────────────┘
                 │ settlement files (T+1)
                 ▼
 ┌──────────────────────────┐   ┌──────────────────────────┐
 │ Reconciliation engine    │──►│ Cold/WORM store (S3 Glacier)│ 7-yr retention
 └──────────────────────────┘   └──────────────────────────┘
```

**Component walkthrough.**
- **Edge layer** — CDN/WAF, TLS 1.3, DDoS protection, IP/merchant rate limiting. The card-input page can be a tokenization iframe served by the vault so raw PAN never hits merchant or gateway app servers.
- **API Gateway** — authenticates the merchant, extracts the idempotency key, enforces quotas, routes to the orchestrator. Stateless, autoscaled.
- **Payment Orchestrator (Saga manager)** — the brain. Runs the auth/capture/settle state machine as a saga with compensating actions. Writes the ledger atomically, calls fraud + PSP, emits events.
- **Fraud/Risk** — synchronous low-latency score (allow/challenge/deny) in the auth path; async rules and ML retraining off the Kafka stream.
- **Token Vault** — isolated PCI-scoped service holding encrypted PANs / network tokens behind an HSM. Everything else handles only opaque tokens, shrinking PCI scope.
- **Ledger + Transaction store** — distributed SQL, the source of truth, CP-biased, synchronous (Raft) replication.
- **Kafka** — durable event backbone fed by the transactional **outbox** (CDC), driving webhooks, fraud, analytics, and reconciliation with exactly-once-ish processing.
- **PSP Adapters** — one normalized interface, per-PSP implementations; route by cost/health/geography, with failover.
- **Reconciliation engine** — ingests PSP/network settlement files, matches them against the ledger, flags breaks.

---

## 6. Deep Dives

### 6.1 Idempotency & exactly-once

Networks retry; clients retry; nobody may be charged twice. We layer three guards:

1. **Idempotency key** `(merchant_id, key)` with a `UNIQUE` constraint in the DB. The first request inserts `status=in_progress` inside the same transaction that begins the payment. A duplicate that arrives while the first is running hits the unique constraint → returns `409 in_progress`. A duplicate after completion returns the **stored response verbatim**. We also store a `request_hash`; if the same key arrives with a *different* body, that's a client bug → `422`.
2. **Idempotency at the PSP boundary.** We forward our own idempotency key (or PSP-provided one) so a retried PSP call doesn't create a second authorization. We persist the `network_txn_id` so we can query the PSP ("did this auth go through?") instead of blindly retrying.
3. **Outbox + consumer dedupe.** Events are written to an `outbox` table in the same DB transaction as the ledger write (transactional outbox), so an event is published iff the money moved. Consumers dedupe by `event.id`. Net effect: **at-least-once delivery + idempotent handlers = exactly-once observable effect.** True end-to-end exactly-once is impossible (two-generals); we achieve effectively-once via dedupe.

### 6.2 Double-entry ledger & double-spend / consistency

Money is never "updated" — it is **moved** via balanced entries. An authorization capture posts, e.g.:
```
entry_group g1:  DEBIT  psp_clearing      4999    CREDIT merchant_receivable 4999
                 DEBIT  merchant_receivable 150   CREDIT gateway_fee_revenue  150
SUM(debits) == SUM(credits)  -> enforced in the SAME serializable transaction.
```
Properties this buys us:
- **Auditability**: balances are derivable by replaying entries; the materialized `balance` is just a cache validated against `SUM(entries)`.
- **No double-spend**: a refund cannot exceed `amount_captured`; a capture cannot exceed `authorized - already_captured`. These are checked under a row lock / `version` check on the `transactions` row.
- **Concurrency control**: optimistic locking (`version` column, compare-and-swap) on the transaction row prevents two captures from racing. Under contention we fall back to `SELECT ... FOR UPDATE`. The orchestrator never lets two state transitions for the same payment commit concurrently.
- **Isolation level**: serializable (or snapshot + explicit locks) for money-moving transactions to prevent write skew (e.g., two refunds each individually valid but jointly over-refunding).

**CAP stance:** the ledger is **CP**. During a partition we refuse writes that can't reach quorum rather than risk divergent balances. We localize the blast radius by sharding on `merchant_id`, so a partition affecting one shard doesn't stall the whole platform.

### 6.3 Saga-based failure handling (auth → capture → settle)

A payment is a distributed transaction across our DB, the fraud service, and an external PSP — we can't hold a 2PC lock across an external network. We use an **orchestration saga** with explicit compensations:

```
State machine:
  CREATED → RISK_CHECKED → AUTHORIZED → CAPTURED → SETTLED
                  │             │           │
                  ▼ deny        ▼ fail      ▼ fail
               DECLINED      AUTH_FAILED  CAPTURE_FAILED → (compensate: VOID auth)
```
- Each step is durably recorded *before* the external call (write-ahead intent), then reconciled with the result. If the orchestrator crashes mid-step, a recovery worker reads `in_progress` transactions, **queries the PSP** for the real outcome (using the stored `network_txn_id`), and resumes or compensates — it never blindly retries a money move.
- **Compensating actions**: a failed capture after a successful auth triggers a **void/reversal**, not a delete (you cannot "undo" a ledger; you post a reversing entry).
- **Timeouts & retries**: PSP calls have tight timeouts; retries use exponential backoff + jitter and are *idempotent* (same key). Ambiguous timeouts ("did the auth succeed?") are resolved by a status query, never a duplicate submit.

### 6.4 PCI-DSS, tokenization & PSP integration

- **Scope minimization**: raw PAN enters only the **token vault** via a hosted iframe / direct-to-vault POST. App servers, logs, and the ledger see only `tok_*` or **network tokens** (issued by Visa/Mastercard, which also reduce decline rates and survive card re-issuance).
- **Encryption**: PAN encrypted with envelope encryption; data-encryption keys wrapped by an HSM-backed KMS; periodic key rotation; TLS 1.3 + mTLS in transit.
- **Segmentation**: vault runs in an isolated network segment with its own audit, access controls, and minimal surface — this is what keeps PCI scope (and audit cost) bounded.
- **PSP abstraction**: a normalized `PSPAdapter` interface (`authorize/capture/refund/void`) with per-provider implementations. A **routing layer** picks a PSP by cost, success-rate, currency, and health; on failure or high decline it **retries on an alternate PSP** (cascading/failover routing) — only safe because of idempotency keys.
- **3-D Secure / SCA**: when risk or regulation (PSD2) demands, we return `requires_action` and drive the cardholder through a 3DS challenge, then resume the saga.

### 6.5 Reconciliation & unique ID generation

- **Reconciliation**: each day the PSPs/networks deliver settlement files. The recon engine matches every `network_txn_id` to a ledger entry. Categories: matched, missing-in-ledger (we never recorded it — investigate), missing-in-file (we think it captured but the network didn't), amount mismatch (fees/FX). Breaks raise alerts and post adjusting entries; nothing is silently overwritten. This is the safety net that catches any exactly-once gap that slipped through.
- **IDs**: transaction IDs are **ULID/Snowflake-style** — time-sortable, k-ordered (good for index locality and range scans), globally unique without a central allocator (worker-id + timestamp + sequence). We avoid auto-increment (hotspots, leaks volume, doesn't shard).

---

## 7. Scaling, Bottlenecks & Failure Handling

**What breaks first.** At ~100k durable writes/sec the **ledger primary** is the first bottleneck — a single SQL primary tops out long before that. Fixes:
- **Shard by `merchant_id`** (Vitess/Citus) or run NewSQL (CockroachDB/Spanner/TiDB) that auto-shards ranges. Single-shard ACID keeps the hot-path commit local; cross-merchant ops (rare) use 2PC.
- **Hot merchant skew**: a single mega-merchant can hot-spot one shard. Mitigate with sub-sharding (hash `merchant_id + bucket`) for whales, and per-merchant rate limits.
- **Read scaling**: dashboards/analytics read from **replicas** and from the Kafka-fed warehouse, never the write primary.

**External PSP latency / availability** is the next bottleneck — it dominates the latency budget and is outside our control. Defenses: per-PSP **circuit breakers** (open on elevated error/latency, shed load fast), **timeouts + bounded retries**, **bulkheads** (one PSP's outage can't exhaust all threads), and **failover routing** to a healthy PSP. Authorizations can degrade gracefully (route elsewhere) rather than fail outright.

**Replication & DR.**
- Ledger: synchronous quorum (Raft) replication within a region (RPO ≈ 0); async cross-region for DR. Multi-region active-active for money is hard (you'd need cross-region consensus → latency); we run **active-passive per shard** with fast failover, or pin a merchant to a home region.
- Kafka: replication factor 3, min-ISR 2.
- Backups: continuous WAL archiving + point-in-time recovery; periodic restore drills.
- **DR targets**: RPO ≈ 0 (no committed money lost), RTO ~ minutes via automated failover.

**Backpressure & overload.** Token-bucket rate limiting at the edge per merchant; queue auth requests with bounded depth; shed lowest-priority traffic first. Webhooks and analytics are async (Kafka) so a consumer slowdown never blocks the auth path.

**Failure handling summary.** Crash mid-saga → recovery worker reconciles via PSP status query. Duplicate request → idempotency guard. PSP timeout → status query, not blind retry. Partition on a shard → that shard goes read-only/rejects writes (CP), others unaffected. Settlement mismatch → recon flags + adjusting entry.

---

## 8. Trade-offs & Alternatives

| Decision | Chosen | Alternative | Why |
|---|---|---|---|
| Ledger store | Distributed SQL (Spanner/Cockroach/Vitess) | Cassandra/Dynamo | Money needs ACID + multi-row atomicity; eventual consistency risks fake balances. |
| Consistency | CP (refuse on partition) | AP (accept, reconcile later) | Correctness of money > availability of a single write. |
| Distributed txn | Saga + compensation | 2PC across PSP | Can't hold a lock across an external network; 2PC blocks on coordinator failure. |
| Exactly-once | At-least-once + idempotency + dedupe | "true" exactly-once | Genuinely impossible across networks (two generals); effectively-once is achievable and simpler. |
| Event delivery | Transactional outbox + Kafka | Dual-write app→Kafka | Dual writes can publish an event with no DB commit (or vice-versa); outbox makes them atomic. |
| Card data | Tokenization vault / network tokens | Store PAN in main DB | Containment of PCI scope; lower breach blast radius and audit cost. |
| Concurrency | Optimistic (version CAS), pessimistic under contention | Always lock | Optimistic is cheaper at low contention; locks only where needed. |

**At 10×** (100k payments/sec avg): more shards, regional sharding by merchant home region, dedicated infra and possibly bypassing PSPs to connect **directly to card networks as an acquirer/processor** (cuts cost and a network hop, but adds heavy scheme-compliance burden). Tiered storage becomes mandatory; recon runs continuously, not nightly.

**At 100×**: multi-region active-active with merchant home-region affinity and conflict-free per-merchant ledgers; in-house fraud ML platform; cell-based architecture (each cell a self-contained gateway slice) to bound blast radius and enable independent regional deploys/DR.

**What I'd reconsider:** if strict per-payment latency mattered more than cross-merchant joins, an **event-sourced ledger** (Kafka log of immutable events + projected balances) is attractive for auditability and replay — at the cost of harder ad-hoc querying and read-your-writes. For a regulated core ledger I keep distributed SQL as the system of record and use the event log as the *derived* stream.

---

## Interview Q&A by Level

### 🟢 Basic
**Q: Why store amounts as integer minor units instead of floats/decimals?**
Floating point can't represent values like 0.10 exactly, causing rounding drift that's unacceptable for money. We store integer cents (e.g., `4999` = $49.99), eliminating rounding error and making equality/sum checks exact.

**Q: What's the difference between authorization, capture, and settlement?**
Authorization places a hold on the cardholder's funds (verifies the card and reserves the amount) but moves no money. Capture instructs the network to actually move the held funds (can be partial/delayed, e.g. ship-then-charge). Settlement is the batch process (T+1/T+2) where the acquirer/network actually transfers money into the merchant's account.

**Q: What is an idempotency key and why is it required?**
A client-generated unique key per logical operation. The server records it; a retried request with the same key returns the original result instead of charging again. It turns unreliable, retried network calls into safe, exactly-once-effect operations.

### 🟡 Intermediate
**Q: Explain double-entry bookkeeping and why a gateway uses it.**
Every money movement creates balanced entries: total debits equal total credits within an entry group. Balances are derived by summing entries, so the system is fully auditable, self-checking (`SUM(D)=SUM(C)`), and incapable of "losing" money — a missing leg fails the invariant immediately. It also makes reconciliation and corrections (reversing entries) clean.

**Q: How do you prevent double-spend / two concurrent captures on the same payment?**
Optimistic concurrency: each transaction row has a `version`; a state change does a compare-and-swap on `(id, version)`. The loser retries against fresh state. Under high contention we use `SELECT ... FOR UPDATE`. The capture amount is checked against `authorized - already_captured` inside a serializable transaction so two captures can't jointly over-capture.

**Q: Why a saga instead of a distributed transaction (2PC) for auth→capture?**
The PSP/network is an external system we can't enroll in a 2PC, and holding locks across a 150–300 ms external call (which may time out) doesn't scale and risks coordinator-blocking. A saga executes steps with durable intent and defines compensating actions (e.g., void a successful auth when a later step fails), giving us forward progress and recoverability without cross-system locks.

### 🟠 Advanced
**Q: A PSP call times out and you don't know whether the authorization succeeded. What do you do?**
Never blindly retry — that risks a double-auth. We persisted the `network_txn_id` / our idempotency key before the call. On timeout we issue an idempotent retry with the same key (PSP dedupes) or, better, a **status query** to ask the PSP the actual outcome. The saga only advances or compensates based on the confirmed result. The recovery worker handles cases where our process crashed: it scans `in_progress` rows and reconciles each against the PSP.

**Q: How do you guarantee an event (e.g., webhook) is published if and only if the money moved?**
Transactional outbox: in the same DB transaction that writes the ledger entries, we insert a row into an `outbox` table. A CDC process (e.g., Debezium) tails the outbox and publishes to Kafka. Because the outbox write is part of the atomic commit, the event exists iff the money moved — eliminating the dual-write inconsistency. Consumers dedupe by `event.id` for effectively-once handling.

**Q: How does tokenization reduce PCI-DSS scope, and what's the failure mode if the vault is down?**
Raw PAN is captured directly into an isolated vault (via hosted iframe / direct POST), so application servers, logs, and the ledger only ever see opaque tokens — they fall out of the strictest PCI scope, shrinking audit surface and breach blast radius. If the vault is down, new card entries can't be tokenized (degrade: queue or fail those), but payments using already-issued tokens / network tokens can still proceed, since the vault resolves the token only at the PSP boundary in an isolated call.

### 🔴 Expert
**Q: Defend your CAP choice. When would you ever relax strong consistency?**
The core ledger is CP: during a partition we reject writes that can't reach quorum rather than risk divergent balances or double-spend — incorrect money is worse than a brief unavailability, and we cap the blast radius by sharding on `merchant_id`. We relax consistency only on **derived, non-authoritative** data: read replicas for dashboards (eventually consistent), the Kafka event stream, fraud features, and analytics. Authorizations themselves can degrade by **rerouting** to a healthy PSP rather than failing, which improves availability without compromising ledger correctness.

**Q: You can't truly have exactly-once delivery. How do you still guarantee a customer is charged exactly once?**
Correct — exactly-once *delivery* is impossible across an unreliable network (two-generals). We achieve exactly-once *effect*: (1) idempotency keys with a unique DB constraint dedupe duplicate API requests; (2) the same key flows to the PSP so retries don't create a second auth; (3) the transactional outbox ensures events fire iff money moved; (4) consumers are idempotent and dedupe by event id; (5) nightly/continuous reconciliation against settlement files catches anything that still slipped, posting adjusting entries. Defense in depth, not a single mechanism.

**Q: Design the ledger for 100k writes/sec across regions while keeping it auditable. What's the architecture and where do consistency cracks appear?**
Shard the ledger by `merchant_id` (sub-shard whales) so each payment's multi-row commit is single-shard ACID; use NewSQL (Spanner/Cockroach) with Raft quorum per shard for RPO≈0. Pin each merchant to a home region to avoid cross-region consensus latency on the hot path; replicate async cross-region for DR (active-passive per shard) — true multi-region active-active for a single account would need cross-region consensus and pay the WAN latency tax. The cracks appear at boundaries: cross-merchant/cross-currency movements need 2PC or a saga (kept rare and off the hot path); read replicas lag, so anything needing read-your-writes must hit the primary; and the derived event stream is at-least-once, so every downstream consumer must be idempotent. Auditability is preserved because entries are append-only and immutable — balances are always reconstructable by replay, and the recon engine continuously validates internal state against the networks' authoritative settlement files.
