# Design a Real-Time Ad Click Aggregation Pipeline

> A worked, interview-grade design of an ad-click aggregation system: ingest billions of click events per day, aggregate them into per-ad/per-minute counters that power real-time advertiser dashboards and billing — all while surviving duplicates, late events, and the brutal tension between fast-and-approximate and slow-and-exact.

[← Back to master index](../../README.md) · [← System Design index](../README.md)

---

## 1. Requirements

This problem looks like "just count clicks", but the interviewer is probing how you reason about **stream processing, exactly-once semantics, late/out-of-order events, and the dashboard-vs-billing dual path**. The fact that the numbers eventually charge advertisers real money raises the correctness bar far above a typical analytics counter. Clarify scope before drawing.

### Functional requirements
- **Ingest click events**: every ad click (and impression) produces an event `{ad_id, campaign_id, user_id, ts, ip, geo, device}` that must be captured.
- **Aggregate**: roll events up into counters keyed by `(ad_id, minute)` — and by richer dimensions (campaign, country, device) on demand.
- **Real-time dashboard**: advertisers see click counts with **end-to-end lag of seconds**, not hours.
- **Time-range & top-N queries**: "clicks for campaign X between 14:00 and 15:00", "top 100 ads by clicks in the last hour".
- **Billing reconciliation**: a separate, **exact, auditable** daily total that advertisers are charged against.
- **Fraud / dedup filtering**: drop duplicate clicks (double-fire, retries, bots) before they inflate counts and bills.
- **Backfill / replay**: ability to recompute aggregates if a bug corrupts them or a late batch of events arrives.

### Non-functional requirements
- **Scale**: **10 billion click events/day** (≈115K events/s average, ~1M/s peak). Impressions are ~50× clicks but the design generalizes.
- **Latency**: dashboard freshness **p99 < 10 s** from click to visible count. Query latency p99 **< 500 ms**.
- **Availability**: **99.9%+** ingestion — a dropped click is lost revenue *and* an undercount the advertiser will dispute. The ingestion front door must never reject events it can buffer.
- **Durability**: **zero acknowledged-event loss**. Once we ACK an event at the edge it must survive broker/processor crashes.
- **Consistency**: the **fast path** (dashboard) may be approximate/eventually consistent; the **slow path** (billing) must be **exact and reproducible**. This dual-correctness requirement is the crux of the whole design.
- **Idempotency**: reprocessing the same event (replay, retry, consumer restart) must **not** double-count. Exactly-once *effect* on the aggregates.
- **Security**: authenticated event producers, signed events to deter spoofing, PII (IP, user_id) handling per privacy regs.

### Clarifying questions a strong candidate asks
1. **What's the freshness SLA for the dashboard vs. billing?** Seconds for the UI, but billing can tolerate hours of latency if it's exact. This justifies the **lambda/dual-path** architecture.
2. **How late can events arrive, and what's the watermark policy?** Mobile clients buffer offline and flush hours later; we must decide a cutoff (e.g. accept up to 1 hour late in the speed layer, unbounded in the batch layer).
3. **Is exactly-once required, or is at-most/at-least-once + dedup acceptable?** Billing needs effective exactly-once; the dashboard tolerates slight approximation.
4. **What dimensions and granularities?** Per-minute counts by `ad_id` is the base; do we also need country/device/campaign rollups and arbitrary group-bys? Drives the OLAP store choice.
5. **Do we count clicks or also charge for them (CPC)?** If we bill, fraud filtering and audit trails become first-class, not optional.
6. **How long must raw events be retained?** Replay/backfill and dispute resolution need raw events (e.g. 30–90 days) plus aggregates kept for years.
7. **Single region or global?** Global ad serving means regional ingestion with cross-region aggregation and clock-skew handling.

> The single most important clarification is **"dashboard fast-and-approximate vs. billing slow-and-exact"** — it's the seam along which the entire architecture splits.

---

## 2. Capacity Estimation

Real numbers, shown long-hand. Assume a 5-year retention horizon for aggregates, 90 days for raw events.

### Write (ingest) QPS
```
10,000,000,000 clicks/day ÷ 86,400 s/day ≈ 115,740 events/sec   (~115K EPS avg)
Peak factor ~8x (prime-time + ad campaigns) → ~925,000 EPS  (call it ~1M EPS peak)
```
Clicks alone are ~1M/s peak; if impressions (≈50×) flow through the same pipe, the front door must take **tens of millions of events/sec** — push impressions through the same Kafka design but with far more partitions.

### Read (query) QPS
```
Dashboards: ~500K advertisers, ~5% active at peak = 25,000 active sessions
Each polls/refreshes ~1 query / 5 s → 25,000 ÷ 5 ≈ 5,000 queries/sec
Plus internal/automated (alerting, top-N) ~2,000 q/s → ~7,000 q/s peak
```
Reads (7K q/s) are four orders of magnitude below writes — this is a **write/ingest-dominated** system. The engineering centers on the ingest + aggregation path, with queries served from pre-aggregated tables.

### Raw event storage (90-day retention)
```
Per event ≈ 200 bytes (ad_id 8, campaign_id 8, user_id 8, ts 8, ip 16,
            geo 8, device 8, dedup_id 16, misc ~120)
Daily raw = 10e9 × 200 B = 2 × 10^12 B = 2 TB/day
90-day raw = 2 TB × 90 = 180 TB
With 3x replication = 540 TB  (object storage / Kafka tiered storage)
```

### Aggregate storage (5-year retention)
```
Base grain = (ad_id, minute). Suppose ~5M distinct ads active/day.
Per-minute buckets/day per ad: up to 1,440, but most ads aren't clicked every minute;
assume ~200 active minute-buckets/ad/day on average.
Rows/day ≈ 5,000,000 ads × 200 buckets = 1,000,000,000 rows/day
Per row ≈ 50 B (ad_id, minute, count, plus a few rollup columns)
Daily aggregate = 1e9 × 50 B = 50 GB/day
5-year = 50 GB × 365 × 5 ≈ 91 TB  (before rollup compaction)
```
Rolling minute → hour → day buckets after a retention window shrinks this dramatically (downsampling). Aggregates are ~3 orders of magnitude smaller than raw — that's the whole point of aggregating.

### Bandwidth
```
Ingest: 1,000,000 EPS × 200 B ≈ 200 MB/s sustained at peak  (Kafka ingress)
After RF=3 broker replication: ~600 MB/s internal
Query egress: 7,000 q/s × ~5 KB JSON ≈ 35 MB/s  (modest)
```
Ingest bandwidth and **partition/consumer throughput** are the constraints, not query egress.

### Stream-processor memory (windowed state)
```
Keep in-flight per-(ad_id, minute) counters for open windows + allowed lateness (say 10 min).
Active ad×minute keys in a 10-min window ≈ 5M ads × 10 min ≈ 50M keys (worst case;
realistically far fewer are active simultaneously, ~5–10M).
Per key ≈ 100 B (key + count + window metadata) → 5e6 × 100 B ≈ 500 MB – a few GB
```
Windowed aggregation state is **single-digit GB**, comfortably held in the stream processor's RocksDB-backed state store (Flink) spread across task slots, checkpointed to object storage.

---

## 3. API Design

Three surfaces: a high-throughput **ingest** endpoint (producers), a **query** API (dashboards), and an internal **admin/replay** API. Ingest is gRPC/HTTP with batching; queries are REST.

```
# ---- Ingest (producers → collector) : gRPC, batched, fire-and-forget-ish ----
POST /v1/events:batch
Authorization: Bearer <producer-token>
{
  "events": [
    { "dedup_id": "uuid-v4",          // client-generated idempotency key (REQUIRED)
      "ad_id": "a_123", "campaign_id": "c_9",
      "user_id": "u_77", "event_ts": 1718500000123,   // client event time (ms)
      "ip": "203.0.113.7", "geo": "US-CA", "device": "ios",
      "type": "CLICK" }
    /* ...up to 500 events per batch... */
  ]
}
→ 202 Accepted   { "received": 500, "rejected": 0 }     // ACK only after durable Kafka write
→ 400 Bad Request   // malformed / missing dedup_id
→ 429 Too Many Requests   (Retry-After)

# ---- Query (dashboard → query service) : REST ----
GET /v1/metrics?ad_id=a_123&from=2026-06-16T14:00Z&to=2026-06-16T15:00Z&granularity=minute
→ 200 { "series": [ {"t":"14:00","clicks":4821}, {"t":"14:01","clicks":5099}, ... ],
        "approximate": true, "as_of": "2026-06-16T15:00:03Z" }   // speed-layer answer

GET /v1/metrics/topn?dimension=ad_id&metric=clicks&window=1h&n=100
→ 200 { "results": [ {"ad_id":"a_998","clicks":1203945}, ... ] }

GET /v1/billing?campaign_id=c_9&date=2026-06-15
→ 200 { "clicks": 18203991, "billable_clicks": 17994412, "exact": true,
        "computed_by": "batch", "run_id": "b_20260615" }   // batch-layer, exact

# ---- Admin / replay (internal) ----
POST /v1/admin/replay   { "from_offset": ..., "to_offset": ..., "target": "batch" }
POST /v1/admin/backfill { "date": "2026-06-15", "reason": "agg bug #4412" }
```

**Design notes:** the `dedup_id` is **mandatory** and client-generated — it's the linchpin of idempotency. Ingest returns `202` **only after** the batch is durably written to Kafka (persist-before-ack), so a `202` is a durability promise. The query response carries `approximate` / `exact` and an `as_of` timestamp so the UI can honestly label fast-path numbers.

---

## 4. Data Model

Four stores, each chosen for one job: a **durable log** (Kafka) for ingest, **object storage** for raw events + checkpoints, an **OLAP store** for aggregates/queries, and **Redis** for the hottest live counters.

### Event log — Kafka
The ingest substrate is an **append-only, partitioned, replayable log**. Kafka is the canonical choice: it decouples bursty producers from processors, provides ordered partitions, durable RF=3 storage, and — crucially — **replay** for backfill.
```
Topic: click-events
  partitions: 1,024            -- sized for ~1M EPS / ~1K EPS per partition
  key: ad_id                   -- co-locates an ad's events in one partition (ordering + locality)
  RF: 3, min.insync.replicas: 2
  retention: 7 days (hot) + tiered storage to S3 for 90 days
  value: serialized Event { dedup_id, ad_id, campaign_id, user_id, event_ts,
                            ingest_ts, ip, geo, device, type }
```
Keying by `ad_id` keeps a single ad's clicks ordered within one partition and lets a stream operator own all of an ad's state locally. (Watch for **hot ads** — see Deep Dive 7.)

### Raw event archive — object storage (S3/GCS)
Every event is also landed as raw Parquet/Avro files partitioned by `date/hour`. This is the **source of truth for the batch layer** and for disputes — aggregates can always be recomputed from here.
```
s3://clicks-raw/dt=2026-06-16/hr=14/part-00037.parquet   (columnar, compressed)
```

### Aggregate store — OLAP columnar DB (ClickHouse / Druid)
Pre-aggregated counters queried by time range, top-N, and group-by. A **columnar OLAP store** (ClickHouse, Druid, or Pinot) is the sweet spot: blazing range scans and aggregations over time-partitioned, append-mostly data.
```sql
-- Per-minute base aggregate (speed layer writes here continuously)
CREATE TABLE clicks_by_minute (
  ad_id        String,
  campaign_id  String,
  minute       DateTime,       -- truncated to minute (event time)
  country      LowCardinality(String),
  device       LowCardinality(String),
  clicks       UInt64,         -- count for this bucket
  layer        Enum('speed','batch'),  -- which layer produced this row
  version      UInt32          -- monotonic; batch overwrites speed on reconciliation
) ENGINE = ReplacingMergeTree(version)     -- newest version wins on merge
ORDER BY (ad_id, minute, campaign_id, country, device)
PARTITION BY toYYYYMMDD(minute);
```
- **Partition by day, order by `(ad_id, minute, …)`** → time-range queries for an ad scan a tiny contiguous slice.
- **`ReplacingMergeTree(version)`**: the speed layer writes `layer='speed'` rows fast; the nightly batch layer writes authoritative `layer='batch'` rows with a higher `version`, and ClickHouse's merge **replaces** the approximate row with the exact one. This is how the fast and slow paths reconcile in one table.
- `LowCardinality` columns compress the repetitive country/device dimensions hard.

### Hot live counters — Redis
For the "right now" big number on a dashboard and for top-N leaderboards, a Redis layer holds the current-minute counters and sorted sets, updated by the stream processor — single-digit-ms reads without touching OLAP.
```
HINCRBY  clicks:{ad_id}:{minute}  count  1        -- live minute counter
ZINCRBY  topn:clicks:1h  1  {ad_id}               -- sorted set for top-N
PFADD    uniq:{ad_id}:{hour}  {user_id}           -- HyperLogLog for unique users (approx)
```

### Billing ledger — relational (Postgres)
The authoritative billed totals (output of the batch layer, after fraud filtering) live in a transactional store with an audit trail — money needs ACID and immutability.
```sql
billing_daily(campaign_id, date, raw_clicks, billable_clicks, run_id, computed_at, PRIMARY KEY(campaign_id, date, run_id))
```

---

## 5. High-Level Architecture

This is a **lambda architecture**: a **speed layer** (stream) for fresh-but-approximate dashboard numbers, and a **batch layer** for exact-but-delayed billing numbers, reconciling into the same serving store.

```
                       ┌──────────────────────────────────────────┐
   Ad servers /        │             Producers (SDKs)             │
   apps / browsers ───►│  attach dedup_id, event_ts; batch & send │
                       └───────────────────┬──────────────────────┘
                                           │ gRPC/HTTP (batched)
                              ┌────────────▼─────────────┐
                              │   Collector / Ingest API  │  ← auth, validate, persist-before-ACK
                              │   (stateless, autoscaled) │
                              └────────────┬─────────────┘
                                           │ produce (key=ad_id)
                              ┌────────────▼─────────────┐
                              │     Kafka  click-events   │  RF=3, 1,024 partitions, tiered→S3
                              └───┬───────────────────┬───┘
            ┌─────────────────────┘                   └──────────────────────┐
            │ (continuous)                                       (continuous) │
   ┌────────▼─────────────┐                               ┌──────────────────▼────────┐
   │  SPEED LAYER          │                               │  Raw landing sink          │
   │  Stream processor     │                               │  (Kafka→S3, Parquet)       │
   │  (Flink): dedup →      │                               │  = batch source of truth   │
   │  window by (ad,min) →  │                               └──────────────┬─────────────┘
   │  count → emit          │                                              │ (nightly / hourly)
   └───────┬───────────────┘                               ┌──────────────▼─────────────┐
           │ upsert layer='speed'                          │  BATCH LAYER                │
           │                                               │  Spark job: full dedup +     │
   ┌───────▼─────────────┐    ┌──────────────┐             │  fraud filter + exact agg    │
   │  Redis live counters │    │  OLAP store  │◄────────────┤  → upsert layer='batch',     │
   │  + top-N sorted sets │    │  (ClickHouse)│  reconcile  │     higher version           │
   └───────┬─────────────┘    └──────┬───────┘             │  → Billing ledger (Postgres) │
           │                         │                     └──────────────────────────────┘
           │     ┌───────────────────▼────────┐
           └────►│   Query Service (REST)       │──► Dashboards / Alerting / Top-N
                 │  speed→Redis/OLAP, exact→OLAP│
                 └──────────────────────────────┘
```

### Component walkthrough
1. **Producer SDKs** — running in ad servers/apps; they stamp each event with a **client `dedup_id`** and **event-time `ts`**, batch events (up to ~500), and ship them. Batching amortizes network cost at 1M EPS.
2. **Collector / Ingest API** — *stateless, autoscaled*. Authenticates the producer, validates schema, and **writes to Kafka before returning `202`** (persist-before-ACK = durability promise). Applies coarse rate limiting and does *no* aggregation — it must be dumb and fast.
3. **Kafka `click-events`** — the durable, replayable backbone. Keyed by `ad_id`, RF=3, tiered to S3 for 90-day replay. Absorbs the gap between burst ingest and steady processing.
4. **Speed layer (Flink stream job)** — consumes Kafka, **deduplicates** on `dedup_id` (state store of recently-seen ids), assigns events to **event-time windows** keyed `(ad_id, minute)`, counts, and **emits to Redis + OLAP** tagged `layer='speed'`. Uses watermarks to handle out-of-order events and allowed-lateness for stragglers (Deep Dive 6.2).
5. **Raw landing sink** — a Kafka→S3 connector lands every event as columnar Parquet. This is the **immutable source of truth** the batch layer recomputes from.
6. **Batch layer (Spark, hourly/nightly)** — reads the full raw archive for a window, performs **global dedup**, full **fraud filtering**, and **exact aggregation**, then upserts authoritative `layer='batch'` rows (higher `version`) into OLAP and writes the **billing ledger**. Slow but correct, and re-runnable.
7. **OLAP store (ClickHouse)** — serves time-range/top-N/group-by queries. `ReplacingMergeTree` lets exact batch rows supersede approximate speed rows transparently.
8. **Redis** — current-minute counters and top-N sorted sets for instant "live" reads.
9. **Query Service** — routes: live/recent → Redis/OLAP speed rows (labeled `approximate`); historical/billing → OLAP batch rows / Postgres (`exact`).

---

## 6. Deep Dives

### 6.1 Exactly-once aggregation — the dedup + idempotency story
Counting money means a click must contribute **exactly once**, despite at-least-once delivery, producer retries, consumer restarts, and replays. We engineer **at-least-once transport + idempotent processing = exactly-once effect**, on two levels.

**Level 1 — dedup in the speed layer (best-effort, bounded window).**
```
On each event in the stream operator (keyed by ad_id):
  if dedup_id ∈ seen_ids_state (RocksDB, TTL = allowed lateness, e.g. 1h):
      drop  (duplicate)
  else:
      add dedup_id → seen_ids_state
      increment window count for (ad_id, minute_of(event_ts))
```
- State is **keyed and checkpointed**: Flink checkpoints the dedup set + window counters to object storage atomically, so a restart resumes without double-counting and without losing in-flight counts.
- The dedup set is **bounded by a TTL** (we can't keep every id forever); within the lateness window this catches retries/double-fires. Anything beyond the window is caught by Level 2.

**Level 2 — global dedup in the batch layer (authoritative).** The nightly Spark job sees the *entire* day's raw events and dedups globally on `dedup_id` (e.g. a distinct/group-by), independent of any window. Its output **overwrites** the speed-layer estimate via the OLAP `version` column. So even if the speed layer miscounted (dropped a late event, double-counted across a checkpoint boundary), the **billed number is corrected** within hours.

**Why two levels?** The speed layer optimizes for latency and can only afford bounded state, so its exactness is approximate. The batch layer optimizes for correctness with unbounded view of the data. The `version`-based reconciliation in OLAP is what marries them — the dashboard shows the fast number, then it silently snaps to the exact number once batch runs.

### 6.2 Late & out-of-order events — watermarks and allowed lateness
Events arrive late constantly: mobile clients buffer offline, networks reorder, regions have clock skew. Counting by **event time** (when the click happened) — not **processing time** (when we saw it) — is mandatory, or a phone that flushes an hour of clicks would dump them all into the wrong minute.

```
Event time vs processing time:
  click happens 14:03:30  (event_ts)
  arrives at processor 14:09:00  (5.5 min late, e.g. offline mobile)
  → must land in the 14:03 minute bucket, not 14:09
```
- **Watermarks**: the stream tracks a watermark = "event time up to which we believe we've seen all events" = `max(event_ts) − allowed_lateness`. When the watermark passes minute `M`, window `M` is *triggered* and emitted.
- **Allowed lateness** (e.g. 10 min for dashboard, up to 1h tolerated): events later than the watermark but within allowed lateness **update** the already-emitted window (an incremental correction upsert to OLAP). Events later than that are **dropped to a side output** and swept up by the batch layer.
- **Trade-off**: longer lateness = more accurate speed-layer numbers but more in-flight window state and later finalization. We pick a small lateness for the dashboard (snappy, mostly-right) and rely on the batch layer for the long tail of stragglers — exactly the lambda split.

```
   watermark advances ───────────────►
   |--win 14:03--|--win 14:04--|--win 14:05--|
        ▲ fire when wm > 14:04          ▲ late event for 14:03 within
          (allowed_lateness)              lateness → emit correction
```

### 6.3 The dual-path (lambda) reconciliation — fast vs. exact
This is the architectural heart and the thing interviewers push on hardest.

| | Speed layer (stream) | Batch layer |
|---|---|---|
| Tech | Flink on Kafka | Spark on S3 Parquet |
| Latency | seconds | hours |
| Correctness | approximate (bounded dedup, dropped stragglers) | exact (global dedup, full fraud filter) |
| Drives | dashboard, top-N, alerts | billing, disputes, audit |
| Reprocessable? | only within lateness window | fully (replay raw) |

- **Reconciliation mechanism**: both write `clicks_by_minute`, but batch rows carry a higher `version` and `layer='batch'`; the `ReplacingMergeTree` merge collapses duplicates keeping the highest version → the exact value wins. The Query Service surfaces `approximate:true` until the batch row lands.
- **Why not just the stream (kappa architecture)?** A pure-streaming (kappa) design is simpler operationally — one codebase, reprocess by replaying Kafka through the same job. It can work *if* the stream processor gives true exactly-once (Flink's checkpointed state + transactional sinks do) and you can afford to keep enough state / replay enough log. The reason many ad systems still keep a batch layer: **billing demands a fully auditable, independently-recomputable exact total** from immutable raw data, and unbounded global dedup is cheaper in batch than holding all dedup state forever in the stream. I'd **start kappa-leaning** (Flink exactly-once + Kafka replay) and add a **batch reconciliation job purely for billing/audit** — pragmatic lambda.

### 6.4 Top-N and unique-user counts — approximation is a feature
- **Top-N (e.g. top 100 ads by clicks/hour)**: maintaining an exact global sort over millions of ads per query is wasteful. Use a **Redis sorted set** (`ZINCRBY`) updated by the stream for the live leaderboard, and **Count-Min Sketch** in the processor to find heavy hitters with bounded memory. For exact top-N (billing-adjacent), the OLAP store answers `ORDER BY clicks DESC LIMIT 100` over the day partition — slower but exact, and acceptable since top-N queries are rare.
- **Unique users (reach)**: exact distinct counts over billions of events are expensive. **HyperLogLog** (`PFADD`/`PFCOUNT` in Redis, or HLL in ClickHouse) gives ~1–2% error in **a few KB per key** instead of storing every user_id. Mergeable across windows/regions. Advertisers accept the small error on "unique reach"; exact uniques (if ever billed) come from the batch layer.

### 6.5 Fraud / invalid click filtering
Inflated clicks mean inflated bills and disputes, so filtering is part of correctness, not a bolt-on.
- **Cheap real-time filters (speed layer)**: drop obvious dupes (`dedup_id`), rate-limit per `(user_id, ad_id)` (>N clicks/min = bot), drop known-bad IP ranges / data-center ASNs, and basic bot heuristics. These keep the dashboard roughly honest.
- **Expensive batch filters (batch layer)**: ML-based click-fraud scoring, cross-event correlation (same fingerprint clicking many ads), retroactive blocklist application. The **billable** count subtracts these — which is exactly why billing waits for batch.
- **Two counts surfaced**: `raw_clicks` (everything) and `billable_clicks` (post-fraud). Advertisers see both; we bill on the latter.

---

## 7. Scaling, Bottlenecks & Failure Handling

**What breaks first?** In order: (1) **Kafka partition / consumer throughput** at 1M EPS, (2) **hot keys** (a viral ad on one partition), (3) **stream-processor state** size & checkpoint duration, (4) **OLAP write/merge** pressure, (5) batch-job runtime as data grows.

- **Stateless ingest scales horizontally**: collectors behind an autoscaling group; add nodes for more EPS. They hold no state, so scaling is trivial and crashes are harmless (Kafka is the buffer).
- **Kafka scaling**: add partitions (we sized 1,024 for clicks; impressions get far more) and brokers; consumer groups scale processing by adding Flink task slots up to the partition count. **Tiered storage** offloads old segments to S3 so brokers aren't disk-bound while still allowing 90-day replay.
- **Hot-key mitigation** (a single viral ad doing 100K EPS to one partition — see below): **two-level (local pre-)aggregation**. Each processing node first aggregates locally per `(ad_id, minute)` (combiner), emitting partial counts every few seconds, and a second stage sums partials. This converts 100K events/s into a handful of partial-count messages/s and removes the single-partition hotspot. Optionally salt the key (`ad_id#shard`) for the hottest ads and re-sum downstream.
```
   raw events ─► [local pre-agg per node] ─► partial counts ─► [global sum] ─► OLAP
   (100K/s)         (combiner, every 5s)       (a few/s)
```
- **Stream state & checkpoints**: keep window + dedup state in RocksDB (off-heap, spills to disk), checkpoint **incrementally** to S3. If checkpoints get slow, shrink dedup TTL / lateness or shard state further. Recover from the last checkpoint on failure — no lost counts, no double counts.
- **OLAP scaling**: ClickHouse shards by `ad_id` hash and replicates; `ReplacingMergeTree` background merges do the reconciliation. **Downsample** old minute-data to hourly/daily to cap storage and merge load.
- **Replication & DR**: Kafka RF=3 + `min.insync.replicas=2` (survives a broker/AZ loss with no loss). OLAP replicas across AZs. Raw S3 archive is the ultimate backstop — any aggregate can be rebuilt by replaying it. Cross-region: ingest locally per region, replicate raw + Kafka to a primary for global aggregation.

### Failure scenarios
| Failure | Behavior |
|---|---|
| Collector crashes | Stateless; LB routes to healthy nodes. In-flight unacked batches are retried by the producer (dedup_id makes that safe). |
| Kafka broker down | RF=3/ISR=2 → no loss; consumers rebalance partitions, processing continues. |
| Flink job crashes | Resume from last checkpoint (state + offsets). At-least-once replay from Kafka; dedup state prevents double-count. |
| OLAP node down | Replica serves reads; writes buffer; merges catch up. |
| Speed layer wrong (bug) | Batch layer recomputes exact values from raw S3 and overwrites via `version` — self-healing within a day. |
| Late event flood (mobile flush) | Within lateness → window corrections; beyond → side-output, swept by batch. Dashboard briefly under-reports, billing stays exact. |
| Whole region outage | Failover to secondary region; raw events replicated; reprocess any gap from Kafka/S3. RPO≈0 for acked events. |

**Thundering-herd / backpressure**: if processing lags ingest, Kafka simply buffers (that's its job) and the dashboard freshness degrades gracefully — but **no events are lost and no ACK is given falsely**. Flink applies backpressure upstream rather than OOM-ing.

---

## 8. Trade-offs & Alternatives

- **Lambda vs. Kappa.** Lambda (separate batch + speed) gives an independent, auditable exact recompute — ideal for billing — at the cost of **two codebases** that can drift. Kappa (stream-only, reprocess by replay) is simpler and, with Flink exactly-once + Kafka replay, can be correct. **Chosen: pragmatic lambda** — Flink for everything, plus a thin batch job *only* for billing/audit. If billing weren't involved, I'd go pure kappa.
- **Exact vs. approximate.** Dashboards get **approximate** (bounded dedup, dropped stragglers, HLL uniques, Count-Min top-N) for low latency; billing gets **exact** from batch. Surfacing both honestly (`approximate` flag) avoids advertiser confusion. Forcing exactness on the hot path would blow the <10s SLA.
- **Event time vs. processing time.** Event time (watermarks) is mandatory for correctness with late/offline clients; it costs window-state complexity and finalization delay. Processing time would be simpler but would mis-bucket every late mobile flush.
- **Kafka vs. Kinesis/Pulsar.** Kafka: mature, replayable, tiered storage, huge ecosystem — chosen. Kinesis: managed, less ops, but shard limits and cost at 1M EPS. Pulsar: better multi-tenancy / tiered storage natively. The *log-as-backbone* idea is the invariant; vendor is swappable.
- **OLAP choice (ClickHouse vs. Druid vs. Pinot).** ClickHouse: fastest raw scans, simple ops, `ReplacingMergeTree` perfect for layer reconciliation — chosen. Druid/Pinot: better real-time ingestion + auto-rollup and richer multi-tenant query isolation; either is defensible.
- **CAP stance.** Ingest path is **AP** — never reject an event we can buffer; accept eventual visibility. Billing path is effectively **CP/correctness-first** — we'd rather take hours and be exact than be fast and wrong about money.

**At 10x scale (100B events/day, ~10M EPS):** more Kafka partitions + brokers, regional ingest with local pre-aggregation so cross-region traffic is partial counts (not raw events), aggressive two-level aggregation everywhere, and downsampling minute→hour earlier. The batch job moves from nightly to **incremental/continuous** (e.g. micro-batches) to keep runtime bounded.

**At 100x scale:** push aggregation to the **edge** — pre-aggregate at regional collectors so the central pipeline ingests partial counts, not individual clicks (turning 10M EPS of events into far fewer count-deltas). Adopt a **cell-based** layout (each region a self-contained pipeline) with a thin global merge layer, and make billing reconciliation a sharded, parallel batch over per-cell raw archives.

---

## Interview Q&A by Level

### 🟢 Basic
**Q. [Theory] Why aggregate at all instead of querying raw click events directly?**
A: Raw events are ~2 TB/day; answering "clicks for ad X in this hour" by scanning raw events at query time would be slow and expensive at 7K q/s. Pre-aggregating into `(ad_id, minute)` counters shrinks the data ~1000× and turns every dashboard query into a tiny range scan over a time-partitioned, columnar table — milliseconds instead of full scans.

**Q. [Theory] Why is this a write-heavy (ingest-dominated) system?**
A: ~1M events/s of ingest at peak versus ~7K queries/s — four orders of magnitude apart. So the design effort goes into the ingest + aggregation pipeline (Kafka, stream processing, dedup, windowing), while queries are cheap because they hit pre-aggregated tables.

**Q. [Practical] What is the role of Kafka here, and why not write events straight to the database?**
A: Kafka is a durable, replayable, partitioned buffer that decouples bursty 1M-EPS producers from steady-rate processors. Writing 1M EPS directly to a DB would overwhelm it and couple producer availability to DB health. Kafka absorbs spikes (back-pressure becomes "buffer", not "drop"), guarantees ordering per partition, and — uniquely — lets us **replay** events to rebuild aggregates after a bug.

### 🟡 Intermediate
**Q. [Theory] Explain event time vs. processing time and why it matters here.**
A: Event time is when the click actually happened (stamped by the client); processing time is when our system saw it. A phone offline for an hour flushes a backlog of clicks that arrive "now" but belong to past minutes. We must bucket by **event time** or those clicks land in the wrong minute, corrupting the time series and the bill. Watermarks let the stream reason about event-time progress despite out-of-order arrival.

**Q. [Practical] How do you guarantee a click is counted exactly once?**
A: At-least-once transport plus idempotent processing. The client attaches a `dedup_id`; the stream processor keeps a checkpointed set of recently-seen ids and drops repeats within the lateness window. Flink's checkpointing makes window counts + dedup state atomic with Kafka offsets, so restarts don't double-count. For billing, the batch layer does a **global** dedup over the full day's raw events and overwrites the stream's estimate — catching anything the bounded stream dedup missed.

**Q. [Practical] How do late events get incorporated without re-running everything?**
A: Windows stay "open" for an allowed-lateness period after their watermark fires. A late event within that window triggers an **incremental correction** — re-emit the updated count for that minute (an upsert into OLAP). Events later than the allowed lateness go to a side output and are reconciled by the nightly batch layer rather than forcing a full recompute online.

**Q. [Behavioral] An advertiser complains the dashboard showed 1.02M clicks at 3pm but their invoice says 0.95M. How do you handle it?**
A: First, this is **expected and by design**, so I'd explain it without defensiveness: the dashboard is the **speed layer** — fast but approximate, and it counts raw clicks. The invoice is the **batch layer** — exact and **fraud-filtered**, so invalid/duplicate clicks were removed (raw 1.02M → billable 0.95M). I'd point them to the API fields (`approximate:true`, `raw_clicks` vs `billable_clicks`) and the `as_of`/`run_id` provenance. Then I'd check internally that the batch run completed cleanly and the fraud filter wasn't over-aggressive. Long-term I'd make the UI label this clearly so the gap doesn't surprise advertisers — the fix here is partly engineering, partly product communication.

### 🟠 Advanced
**Q. [Theory] Walk through the lambda architecture and the reconciliation mechanism.**
A: Two paths consume the same events. The **speed layer** (Flink) produces fresh, approximate `(ad_id, minute)` counts to OLAP/Redis tagged `layer='speed'`. The **batch layer** (Spark over the immutable raw S3 archive) recomputes exact, fraud-filtered counts and writes `layer='batch'` rows with a higher `version`. The OLAP `ReplacingMergeTree` merges on the key and keeps the highest `version`, so the exact batch row transparently supersedes the approximate speed row. Dashboards label data `approximate` until the batch row lands. This gives seconds-fresh UX and money-grade exactness from one serving table.

**Q. [Coding] Sketch the core windowed aggregation with dedup and watermarks (pseudocode).**
A:
```python
# Flink-style streaming job (pseudocode), keyed by ad_id
stream = kafka_source("click-events")
    .assign_timestamps(lambda e: e.event_ts)
    .with_watermark(bounded_out_of_orderness=minutes(10))   # allow 10m lateness
    .key_by(lambda e: e.ad_id)

class CountWithDedup(KeyedProcessFunction):
    seen = MapState("seen_ids")          # dedup_id -> True, TTL = 1h, checkpointed
    counts = MapState("minute_counts")   # minute -> count, in window state

    def process(self, e, ctx):
        if self.seen.contains(e.dedup_id):       # idempotency
            return                                # duplicate -> drop
        self.seen.put(e.dedup_id, True)

        m = truncate_to_minute(e.event_ts)
        if event_time(e) < ctx.watermark() - allowed_lateness:
            ctx.side_output(LATE, e)             # too late -> batch layer handles it
            return
        self.counts.put(m, self.counts.get(m, 0) + 1)
        ctx.timer_service().register_event_time_timer(m + minute(1))  # fire window

    def on_timer(self, minute, ctx):             # watermark passed this minute
        upsert_olap(ad_id=ctx.key, minute=minute,
                    clicks=self.counts.get(minute), layer="speed", version=now())
        redis.HINCRBY(f"clicks:{ctx.key}:{minute}", "count", delta)

# Checkpointing makes (seen, counts, kafka offsets) atomic -> exactly-once effect.
```
The key points an interviewer wants: dedup before counting, event-time windowing, watermark-driven firing, late events routed to a side output, and checkpointed state so restarts neither lose nor double-count.

**Q. [Practical] A single ad goes viral — 100K events/s onto one Kafka partition (its `ad_id` key). What breaks and how do you fix it?**
A: That partition and the single consumer/task owning that key become a hotspot — lag spikes, state grows, the dashboard for that ad falls behind. Fix with **two-level aggregation**: each processing node pre-aggregates that ad's clicks locally (a combiner) and emits a partial count every few seconds; a downstream stage sums partials. 100K events/s collapses to a handful of partial-count messages/s, removing the per-key bottleneck. For extreme cases, **salt the key** (`ad_id#0..N`) to spread it across partitions and re-sum the shards downstream — at the cost of N× the per-key state and a merge step.

**Q. [Theory] How do you compute unique users and top-N at this scale affordably?**
A: Exact distinct over billions of events is too costly online, so unique reach uses **HyperLogLog** (few KB/key, ~1–2% error, mergeable across windows/regions). Top-N uses a **Redis sorted set** updated by the stream for the live leaderboard, with **Count-Min Sketch** to bound memory on heavy-hitter tracking; exact top-N (when needed for billing-adjacent reporting) comes from an OLAP `ORDER BY ... LIMIT` over the day partition. The principle: trade a little accuracy for huge memory savings on the hot path, and fall back to exact-but-slow only when correctness is contractually required.

### 🔴 Expert
**Q. [Theory] When would you drop the batch layer and go pure kappa, and what makes that safe?**
A: Go kappa when there's **no money-grade audit requirement** that demands an independent recompute from immutable raw data — e.g. pure analytics/dashboards. It's safe when the stream processor provides **true exactly-once** (Flink's checkpointed state + transactional/idempotent sinks), you retain enough Kafka history (tiered storage) to **replay** for reprocessing, and your dedup/window state is affordable to keep. You reprocess by spinning up a parallel job from an earlier offset and atomically swapping the output. The reason I keep a thin batch job even then for ad billing is auditability: finance wants a number reproducible from raw events by a separate code path, plus unbounded global dedup and heavy fraud ML are cheaper offline than holding all that state in the stream forever.

**Q. [Practical] Design this for active-active multi-region ad serving with global aggregates.**
A: Ingest **locally per region** (regional collectors + Kafka) so clicks never cross oceans on the hot path. Each region runs its own speed layer producing **partial** `(ad_id, minute, region)` counts and lands raw events to a regional archive. A **global merge** layer sums regional partials into worldwide counts (counts are additive, so cross-region merge is conflict-free — no ordering needed, just summation). Handle **clock skew** by trusting client `event_ts` and using generous watermarks at the global merge. Billing batch runs per-region over local raw archives, then a global reducer sums them — fully reproducible. Failover: a region's traffic shifts to a neighbor; its raw archive is replicated so any gap is reprocessed. Because counts commute, this scales cleanly without a global coordinator.

**Q. [Coding] How would you make the OLAP upsert idempotent so retries of the speed layer's emit don't inflate counts?**
A: Never use a blind `INCR` into the durable aggregate; make each emit a **set/replace of the full window value**, keyed and versioned:
```sql
-- The speed layer emits the CURRENT TOTAL for (ad_id, minute), not a delta.
INSERT INTO clicks_by_minute (ad_id, minute, clicks, layer, version)
VALUES ('a_123', '2026-06-16 14:03:00', 5099, 'speed', 1718500999);
-- ReplacingMergeTree(version) keeps only the highest-version row per key.
-- A retried emit with the same or lower version is harmless (idempotent);
-- a newer emit (more events arrived) simply supersedes the old total.
```
The window state in Flink holds the running total, so each emit is the absolute count for that minute, not an increment. Retries/replays therefore overwrite rather than add. The same idea makes batch's exact rows (`layer='batch'`, even higher version) win over speed automatically. (Redis live counters *can* use `INCR` because they're throwaway approximate state, rebuilt from the durable OLAP totals if lost.)

**Q. [Behavioral] Mid-incident: the speed layer is 20 minutes behind and advertisers are paging support that "their numbers are frozen." Walk me through your response.**
A: First, **assess blast radius and protect correctness**: confirm via Kafka consumer-lag and Flink backpressure metrics that we're *buffering, not dropping* — acked events are safe, this is a freshness incident, not a data-loss one. That distinction drives my communication: I'd post a status update that dashboards are delayed but **no clicks are lost and billing is unaffected**, which de-escalates most advertiser anxiety. Then **mitigate**: scale out Flink task slots / add Kafka consumers up to the partition count, check for a hot-key or a poison message stalling a partition, and if needed temporarily widen checkpoint intervals or shed the most expensive enrichment to let counts catch up. **Verify** lag drains and freshness returns to SLA. **Afterward**, a blameless post-mortem: was it a traffic spike (autoscaling gap), a hot ad (need salting/two-level agg), or a bad deploy? The follow-ups are usually autoscaling on consumer lag and a hot-key guard. Throughout, the discipline is: never trade away the durability/exactness guarantee to chase freshness — degrade latency gracefully, never lose money-relevant data.

---

*Key takeaway: an ad-click aggregation pipeline is a masterclass in stream processing under a dual-correctness mandate — the interesting engineering is exactly-once aggregation via dedup + checkpointing, event-time windowing with watermarks for late mobile events, and a lambda split that serves a seconds-fresh approximate dashboard while a re-runnable batch layer produces the exact, fraud-filtered number you actually bill on.*
