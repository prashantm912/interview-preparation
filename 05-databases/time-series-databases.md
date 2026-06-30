# Time-Series Databases

[← Back to master index](../README.md)

A time-series database (TSDB) is a system specialized for data points indexed primarily by time — metrics, sensor readings, events, financial ticks — where writes are overwhelmingly append-only, the dominant query is "range over time," and data ages out via retention. This guide walks from the fundamentals (what makes time-series data special, TSDB vs RDBMS) through the major engines (InfluxDB, TimescaleDB, Prometheus, VictoriaMetrics) and into expert internals like Gorilla/XOR compression, the cardinality problem, continuous aggregates, and hypertable chunking. Content is current to 2026 (InfluxDB 3.x on the Arrow/Parquet "IOx" engine, TimescaleDB 2.x, Prometheus 3.x, and VictoriaMetrics).

## 🟢 Basic (0–2 yrs)

### Q1. [Theory] What is a time-series database and how does time-series data differ from ordinary relational data?

A **time-series database (TSDB)** is a database optimized for data where each record is tied to a timestamp and the primary access pattern is "give me values over a time range." Examples: server CPU every 10 s, IoT temperature every second, stock prices per tick, application latency per request.

Time-series data has distinctive characteristics that shape the entire engine:

- **Append-mostly, time-ordered writes** — new data almost always has a timestamp at (or near) *now*; updates and deletes of old points are rare.
- **Immutable once written** — you don't UPDATE a CPU reading from 3 hours ago; you only add new readings.
- **High ingest volume** — millions of points per second is common.
- **Queries are range scans by time** — "last 5 minutes," "this week vs last week," almost never "find this one random row."
- **Data ages out** — old raw data is downsampled or deleted via retention policies.
- **Recent data is hot, old data is cold** — most queries hit the last few hours/days.

Ordinary relational (OLTP) data, by contrast, is read/updated/deleted at random keys, must support strong transactional consistency, and has no inherent time ordering. A TSDB trades general-purpose flexibility for crushing efficiency on the time-series access pattern.

### Q2. [Theory] Why not just store time-series data in a regular relational database?

You *can* — and for small volumes you should. But at scale a general-purpose RDBMS struggles in several ways that a TSDB is purpose-built to solve:

- **Write amplification from B-tree indexes.** Inserting time-ordered rows into a single huge B-tree-indexed table causes index bloat and slows as the table grows. TSDBs partition by time so writes hit a small, recent partition.
- **Poor compression.** Row-store RDBMSs store heterogeneous columns together and compress poorly. TSDBs use columnar layouts and time-series-specific encodings (delta-of-delta, XOR) achieving 10–20× compression.
- **No native retention/downsampling.** In an RDBMS you hand-roll cron jobs to `DELETE` old rows (expensive, causes bloat/vacuum) and to roll up aggregates. TSDBs make retention and continuous rollups first-class.
- **Range-scan performance.** Time-partitioned + columnar storage means a "last hour" query touches only the relevant chunk, not the whole table.

```
RDBMS single table:          TSDB (time-partitioned):
[ one giant B-tree ]         [chunk: Mon][chunk: Tue][chunk: Wed]...
 every insert rebalances      insert → newest chunk only
 query scans/seeks all        query → only chunks in range
```

The interesting middle ground is **TimescaleDB**, which is a Postgres extension — you get full SQL and the relational ecosystem, but it adds time-partitioning, compression, and continuous aggregates under the hood.

### Q3. [Theory] What are the core components of a time-series data point? Explain tags vs fields.

A time-series point typically has four parts (using InfluxDB's line protocol vocabulary, which generalizes well):

```
measurement,tag_set field_set timestamp
cpu,host=web1,region=us-east  usage=87.3,idle=12.7  1719792000000000000
```

- **Measurement / metric name** — what is being measured (`cpu`, `temperature`, `http_requests_total`).
- **Tags** — indexed, low-to-medium cardinality *metadata* that you filter and group by: `host`, `region`, `sensor_id`, `status_code`. Tags are always strings and form the *series identity*.
- **Fields** — the actual measured *values*: `usage=87.3`, `temperature=21.4`. Fields are typically **not indexed** and can be floats, ints, booleans, strings.
- **Timestamp** — when the measurement occurred.

The key rule: **tags are for things you filter/group by; fields are for the values you aggregate.** You filter `WHERE host='web1'` (tag) and compute `mean(usage)` (field). Putting a high-cardinality value (like a request ID or raw measurement) in a tag is the classic cardinality mistake covered later.

### Q4. [Theory] What is a "series" in a TSDB and why does it matter?

A **series** (or time series) is the unique combination of a measurement name plus its full set of tag key/value pairs. Each distinct combination is a separate logical stream of (timestamp, value) points.

```
cpu,host=web1,region=us-east   → series A
cpu,host=web2,region=us-east   → series B
cpu,host=web1,region=us-west   → series C
```

This matters enormously because **the number of unique series — the cardinality — is the single biggest driver of a TSDB's memory and index footprint.** Every series needs an index entry, and many engines hold series metadata in memory. Two tags with 1,000 values each multiply to 1,000,000 potential series. Understanding series is the foundation for understanding the cardinality problem (Q31).

### Q5. [Theory] Name the major time-series databases and their one-line distinguishing traits.

- **InfluxDB** — purpose-built TSDB with its own query languages; v3 ("IOx") is built on Apache Arrow + DataFusion + Parquet and speaks SQL. Popular for IoT/metrics.
- **TimescaleDB** — a PostgreSQL *extension*; full SQL + relational joins, with time-partitioned "hypertables," compression, and continuous aggregates. Best when you want SQL and the Postgres ecosystem.
- **Prometheus** — pull-based monitoring system with its own embedded TSDB and the PromQL query language; the de-facto standard for cloud-native/Kubernetes metrics. Designed for operational monitoring, not long-term storage.
- **VictoriaMetrics** — a Prometheus-compatible TSDB optimized for high cardinality, better compression, and long-term storage; often used as a drop-in remote-write backend or Prometheus replacement.
- Others worth knowing: **Graphite** (older, hierarchical), **OpenTSDB** (on HBase), **QuestDB** (fast SQL, columnar), **Amazon Timestream**, **ClickHouse** (a column store frequently used for time-series/observability).

### Q6. [Practical] Write a query to get the average CPU usage per host over the last hour, bucketed into 5-minute intervals (TimescaleDB/SQL).

This is the canonical "time bucketing + group by tag" query. In TimescaleDB you use `time_bucket()`:

```sql
SELECT
    time_bucket('5 minutes', ts)        AS bucket,
    host,
    avg(usage)                          AS avg_usage
FROM cpu_metrics
WHERE ts >= now() - interval '1 hour'
GROUP BY bucket, host
ORDER BY bucket, host;
```

`time_bucket('5 minutes', ts)` floors each timestamp to its 5-minute window so that 10:00–10:05 collapses to one row per host. The `WHERE ts >= now() - interval '1 hour'` lets the planner prune to only the recent chunk(s). This is the bread-and-butter TSDB query: **filter by time range → bucket → group by tag → aggregate field.**

### Q7. [Theory] What is a retention policy and why do TSDBs need one?

A **retention policy** automatically deletes (or downsamples) data older than a configured age. Time-series data grows without bound — sensors and servers emit forever — so you cannot keep raw, full-resolution data indefinitely. Retention bounds storage cost and keeps the hot dataset small.

Typical pattern:
- Keep **raw** 1-second data for **7 days**.
- Keep **1-minute rollups** for **90 days**.
- Keep **1-hour rollups** for **2 years**.

In a plain RDBMS, expiring old data means expensive `DELETE` + vacuum/reindex. In a TSDB, retention drops whole time-partitions/chunks, which is a cheap metadata operation (often just removing files). Prometheus has `--storage.tsdb.retention.time`; InfluxDB has retention policies/buckets; TimescaleDB uses `add_retention_policy()`.

### Q8. [Practical] How would you set up a 30-day retention policy in TimescaleDB?

TimescaleDB ships a background job framework; you attach a retention policy to a hypertable:

```sql
-- Drop chunks whose data is entirely older than 30 days
SELECT add_retention_policy('cpu_metrics', INTERVAL '30 days');

-- Inspect / verify
SELECT * FROM timescaledb_information.jobs
WHERE proc_name = 'policy_retention';

-- Manually drop old chunks immediately (one-off)
SELECT drop_chunks('cpu_metrics', older_than => INTERVAL '30 days');
```

The retention policy runs periodically and calls `drop_chunks` under the hood. Because chunks are separate tables/files, dropping them is a fast metadata operation — no per-row `DELETE`, no bloat, no vacuum storm. That is exactly why time partitioning matters for retention.

### Q9. [Theory] What is downsampling and why is it important?

**Downsampling** is reducing the resolution of older data by replacing many high-frequency raw points with fewer aggregated points (e.g., turn 60 one-second readings into one one-minute average/min/max). It is the partner of retention.

Why it matters:
- **Storage** — keeping 1-second data for years is wasteful; a 1-hour rollup is 3,600× smaller.
- **Query speed** — a "last 2 years" chart doesn't need second-level detail; querying pre-aggregated hourly data is far faster than scanning billions of raw points.
- **Cost** — less data = less disk, less I/O, less memory.

The standard tiered strategy keeps recent data at full resolution and progressively coarsens older data:

```
age:    0────7d────────90d──────────2y────────►
res:    1s raw │ 1m rollup │ 1h rollup │ dropped
```

You typically keep the aggregates you actually query (avg, min, max, count, sum) so you can still build accurate charts from coarse data.

### Q10. [Practical] Show a query to find the maximum temperature recorded per sensor today (InfluxDB SQL / generic SQL).

```sql
SELECT
    sensor_id,
    max(temperature) AS max_temp
FROM readings
WHERE time >= date_trunc('day', now())
GROUP BY sensor_id
ORDER BY max_temp DESC;
```

`date_trunc('day', now())` gives midnight today; everything from then on is "today." We group by the `sensor_id` tag and take `max(temperature)` of the field. In InfluxDB 3.x this runs as standard SQL; in older InfluxDB 1.x you'd write the equivalent InfluxQL (`SELECT max(temperature) ... GROUP BY sensor_id`).

### Q11. [Theory] What is the difference between push-based and pull-based metric collection?

- **Pull-based (scraping)** — the monitoring system periodically fetches metrics from targets over HTTP. **Prometheus** is the canonical example: it scrapes `/metrics` endpoints every N seconds. The server controls timing and can detect when a target is *down* (scrape fails). Service discovery tells Prometheus what to scrape.
- **Push-based** — clients send metrics *to* the database. **InfluxDB** (via Telegraf or direct writes), **Graphite**, and StatsD work this way. Good for short-lived/batch jobs that may not be alive when a scrape would occur, and for events behind firewalls.

```
PULL (Prometheus):   server ──GET /metrics──► target  (server-driven)
PUSH (InfluxDB):     client ──write points──► server  (client-driven)
```

Trade-offs: pull gives the server visibility into target health and centralizes timing, but needs network reachability and service discovery. Push works for ephemeral/batch workloads but needs a gateway (e.g., Prometheus Pushgateway) for short-lived jobs and can overwhelm the server if clients misbehave.

### Q12. [Theory] What is Prometheus and what is it good (and not good) at?

**Prometheus** is an open-source, pull-based monitoring and alerting system with an embedded TSDB and the **PromQL** query language. It scrapes targets, stores samples locally, evaluates alerting/recording rules, and integrates with Alertmanager for notifications and Grafana for dashboards. It's the de-facto standard for Kubernetes/cloud-native monitoring.

Good at:
- **Operational monitoring** of infrastructure and services.
- **Dimensional metrics** with labels and a powerful query language (PromQL).
- **Alerting** on those metrics.

Not good at:
- **Long-term storage** — local storage is meant for days-to-weeks; for years you pair it with remote-write backends (VictoriaMetrics, Thanos, Cortex/Mimir).
- **Event/log/trace data** — it's for numeric metrics, not high-cardinality events or raw logs.
- **100% accuracy/billing** — sampling and staleness mean it's for monitoring, not exact financial accounting.

### Q13. [Practical] Write a PromQL query for the per-second HTTP request rate over the last 5 minutes, by status code.

```promql
sum by (status_code) (
  rate(http_requests_total[5m])
)
```

`http_requests_total` is a **counter** (monotonically increasing). `rate(...[5m])` computes the per-second average rate of increase over a 5-minute window, automatically handling counter resets (restarts). `sum by (status_code)` aggregates across all instances, keeping one series per status code. This pattern — `rate()` on a counter, then `sum by (label)` — is the single most common PromQL idiom.

### Q14. [Theory] What are the basic metric types in Prometheus?

- **Counter** — a value that only goes up (resets to 0 on restart): total requests, total errors, bytes sent. Query with `rate()`/`increase()`, never use the raw value.
- **Gauge** — a value that can go up or down: current memory usage, temperature, queue depth, in-flight requests.
- **Histogram** — samples observations into configurable buckets plus a sum and count, enabling quantile estimation (e.g., request latency distribution). Exposed as `_bucket`, `_sum`, `_count`.
- **Summary** — like a histogram but computes client-side quantiles directly; can't be aggregated across instances the way histograms can.

Rule of thumb: use **histograms** for latencies you want to aggregate (p99 across a fleet via `histogram_quantile`), counters for totals, gauges for point-in-time levels.

### Q15. [Theory] What does a typical TSDB write path look like, and why is it usually append-only?

Most TSDBs use an **LSM-tree-style, append-only** write path because time-series writes arrive in time order and are immutable:

```
incoming points
   │
   ▼
[ Write-Ahead Log ]  ← durability (replay on crash)
   │
   ▼
[ in-memory buffer / head block ]  ← recent, fast to write & query
   │  (periodically flushed)
   ▼
[ immutable, compressed, columnar blocks/chunks on disk ]
   │  (background compaction merges & re-compresses)
   ▼
[ larger compacted blocks ]
```

Writes hit a WAL (for crash recovery) and an in-memory head block. Periodically the head is flushed to immutable, compressed, columnar on-disk blocks. Background compaction merges small blocks into larger ones and applies better compression. This append-only design makes writes cheap and sequential (great for disks), and immutability is what unlocks aggressive compression and easy retention (just drop whole blocks).

### Q16. [Practical] How do you write data to InfluxDB? Show the line protocol.

InfluxDB ingests via **line protocol**, a compact text format: `measurement,tag_set field_set timestamp`.

```
weather,location=us-midwest,sensor=A temperature=82.0,humidity=54 1719792000000000000
weather,location=us-midwest,sensor=B temperature=79.5,humidity=58 1719792000000000000
cpu,host=web1 usage=87.3,idle=12.7
```

Rules:
- Comma-separated **tags** follow the measurement (no spaces); space separates tag set from field set.
- Comma-separated **fields** carry the values; another space precedes the optional timestamp.
- If the timestamp is omitted, the server stamps it on arrival (default nanosecond precision).
- Strings in fields are double-quoted; tags are always strings (unquoted).

You'd POST a batch of these lines to the write endpoint (e.g., via Telegraf, the client libraries, or `curl`). Batching many lines per request is essential for throughput.

### Q17. [Theory] What is a "chunk" (or partition/block/shard) and why partition by time?

A **chunk** is a self-contained sub-table holding the data for one time interval (e.g., one day or one week). TimescaleDB calls them chunks, InfluxDB calls them shards, Prometheus calls them blocks. Partitioning by time gives:

- **Fast inserts** — recent writes hit only the newest, small chunk; indexes stay small.
- **Cheap retention** — expiring data drops whole chunks instead of deleting rows.
- **Query pruning (chunk exclusion)** — a "last hour" query only opens chunks overlapping that range; the rest are skipped entirely.
- **Per-chunk compression** — older chunks can be compressed independently while the live chunk stays writable.

```
 hypertable "cpu"
 ┌──────────┬──────────┬──────────┬──────────┐
 │ Jun-28   │ Jun-29   │ Jun-30   │ Jul-01   │  ← chunks (one per day)
 │ compressed│compressed│compressed│  live    │
 └──────────┴──────────┴──────────┴──────────┘
 query "last 2h" → opens only Jul-01 chunk
```

### Q18. [Theory] Why is time-series data so compressible, and roughly how much can you expect?

Time-series data is highly compressible because consecutive values are extremely *predictable and similar*:

- **Timestamps** are near-evenly spaced (every 1 s, 10 s…), so storing the *delta between deltas* (delta-of-delta) is often a single bit.
- **Values** change slowly — CPU at 87.1, 87.2, 87.0… — so XOR-ing consecutive floats yields mostly zero bits that compress away.
- **Repetition** — many series report the same constant for long stretches.

Real-world TSDBs commonly achieve **10×–20×** (sometimes far more) compression versus raw, and dramatically better than a generic row-store RDBMS. Facebook's Gorilla paper reported compressing a 16-byte (timestamp, value) pair down to ~1.37 bytes on average — over 90% reduction. This is why a TSDB can hold years of metrics in a fraction of the space an RDBMS would need.

### Q19. [Practical] Write a query to detect gaps — sensors that haven't reported in the last 10 minutes.

A common "is this thing alive?" check. Find the latest reading per sensor and flag stale ones:

```sql
SELECT
    sensor_id,
    max(ts)                       AS last_seen,
    now() - max(ts)               AS staleness
FROM readings
GROUP BY sensor_id
HAVING max(ts) < now() - interval '10 minutes'
ORDER BY last_seen;
```

We aggregate the most recent timestamp per sensor and keep only sensors whose newest point is older than 10 minutes. In Prometheus the equivalent uses the `absent()` or `time() - max(timestamp(up)) > 600` idiom; the concept (detect missing recent data) is the same.

### Q20. [Theory] When should you NOT use a time-series database?

A TSDB is the wrong tool when:

- **Your data isn't time-centric** — you query by arbitrary keys, not time ranges (use an RDBMS or key-value store).
- **You need heavy transactional updates/deletes** — TSDBs assume append-only immutable data; frequent in-place updates fight the engine.
- **You need complex multi-table relational joins and constraints** — general relational workloads belong in Postgres/MySQL (though TimescaleDB bridges this).
- **Cardinality is unbounded** — if every point has a unique ID (per-request tracing), a TSDB's per-series index explodes; use a column store or trace/log system.
- **Low volume** — for a few thousand rows, a regular RDBMS is simpler; don't add operational complexity you don't need.

The honest answer in interviews: "use a TSDB when your dominant pattern is *append timestamped data and query ranges over time with retention/rollups*; otherwise reach for a general-purpose store."

## 🟡 Intermediate (3–7 yrs)

### Q21. [Theory] Explain delta-of-delta encoding for timestamps with a concrete example.

**Delta-of-delta** stores the *change in the gap* between consecutive timestamps, exploiting that points arrive at near-regular intervals.

Take timestamps (seconds): `1000, 1010, 1020, 1030, 1041`.

1. **Deltas** (gaps): `10, 10, 10, 11`.
2. **Delta-of-deltas** (change in gap): `0, 0, +1`.

Since the interval is almost always constant, the delta-of-delta is almost always **0**, which a variable-length encoding stores in a single bit. Only when the cadence changes (the `+1`) do you spend more bits. So a perfectly regular stream of 1-second timestamps costs ~1 bit each instead of 8 bytes.

```
raw ts:    1000   1010   1020   1030   1041
delta:        10     10     10     11
d-of-d:        0      0     +1          ← mostly zeros → 1 bit each
```

This is the timestamp half of the Gorilla/Prometheus compression scheme.

### Q22. [Theory] Explain Gorilla / XOR compression for floating-point values.

The value half of Gorilla compression XORs each float with the previous one and stores only the *meaningful* changed bits.

Mechanism:
1. Take two consecutive IEEE-754 doubles and **XOR** them. Because values drift slowly, most high and low bits are identical, so the XOR has many leading and trailing zeros.
2. If the XOR is **zero** (value unchanged), store a single `0` bit.
3. Otherwise store a control bit, then the count of leading zeros and the block of meaningful (non-zero) bits, often reusing the previous block's window.

```
v1 = 87.10  →  0100000001010101110...
v2 = 87.12  →  0100000001010101111...
XOR           →  0000000000000000001...  ← lots of leading/trailing zeros
store only the few meaningful middle bits
```

Combined with delta-of-delta timestamps, Facebook's Gorilla achieved ~1.37 bytes per 16-byte point. Prometheus and many TSDBs use this family of encodings.

### Q23. [Theory] What is a continuous aggregate / materialized rollup, and how does it differ from a regular materialized view?

A **continuous aggregate** is a pre-computed, *incrementally maintained* rollup of time-series data (e.g., hourly averages). It's TimescaleDB's term; InfluxDB has "continuous queries"/tasks; Prometheus has "recording rules."

Difference from a plain materialized view:
- A regular materialized view must be **fully recomputed** on refresh — expensive on huge tables.
- A continuous aggregate is **incremental**: it only recomputes the time buckets whose underlying data changed (typically just recent chunks), tracking a watermark of what's already materialized. Old, settled buckets are never recomputed.

```sql
CREATE MATERIALIZED VIEW cpu_hourly
WITH (timescaledb.continuous) AS
SELECT
    time_bucket('1 hour', ts) AS bucket,
    host,
    avg(usage)  AS avg_usage,
    max(usage)  AS max_usage,
    count(*)    AS n
FROM cpu_metrics
GROUP BY bucket, host;

-- keep it fresh automatically
SELECT add_continuous_aggregate_policy('cpu_hourly',
    start_offset => INTERVAL '3 hours',
    end_offset   => INTERVAL '1 hour',
    schedule_interval => INTERVAL '30 minutes');
```

This gives you fast dashboards (query the small rollup, not raw data) without manual cron jobs.

### Q24. [Practical] Write a query that downsamples raw 1-second data into 1-minute averages and inserts into a rollup table.

The manual downsampling pattern (what a continuous aggregate automates):

```sql
INSERT INTO metrics_1min (bucket, host, avg_usage, min_usage, max_usage, n)
SELECT
    time_bucket('1 minute', ts) AS bucket,
    host,
    avg(usage),
    min(usage),
    max(usage),
    count(*)
FROM metrics_raw
WHERE ts >= '2026-06-30 00:00:00'
  AND ts <  '2026-07-01 00:00:00'
GROUP BY bucket, host
ON CONFLICT (bucket, host) DO UPDATE
SET avg_usage = EXCLUDED.avg_usage,
    min_usage = EXCLUDED.min_usage,
    max_usage = EXCLUDED.max_usage,
    n         = EXCLUDED.n;
```

Note we keep `min`, `max`, `count`, `sum` (not just `avg`) — these are the building blocks that let you correctly re-aggregate coarser windows later. The `ON CONFLICT` makes the job idempotent and safe to re-run for late data.

### Q25. [Theory] Why can't you simply average the averages when downsampling further? How do you roll up correctly?

Averaging averages is wrong whenever the buckets have **different counts**, because a simple mean of means weights each bucket equally regardless of how many points it represents.

Example: bucket A has 100 points averaging 10; bucket B has 1 point of 100.
- Naïve avg-of-avgs = (10 + 100) / 2 = **55**.
- Correct weighted avg = (100·10 + 1·100) / 101 = 1100/101 ≈ **10.9**.

To roll up correctly you must keep **sum and count** (and min/max) rather than just avg:

```sql
SELECT
    time_bucket('1 hour', bucket) AS hour,
    sum(sum_usage) / sum(n)       AS avg_usage,   -- weighted, correct
    min(min_usage)                AS min_usage,
    max(max_usage)                AS max_usage,
    sum(n)                        AS n
FROM metrics_1min
GROUP BY hour;
```

`min`/`max`/`sum`/`count` are "decomposable" and re-aggregate cleanly; `avg` is not — derive it from sum/count. (Percentiles are even trickier — you need t-digest/HDR sketches, not raw percentiles, to combine across buckets.)

### Q26. [Theory] What is the cardinality problem in time-series databases?

**Cardinality** is the total number of unique series (measurement + every distinct tag-value combination). The **cardinality problem** is that memory, index size, and query cost grow with cardinality, and high cardinality can crush a TSDB.

It explodes multiplicatively:

```
cpu  ×  host(10,000)  ×  region(20)  ×  service(50)
     = up to 10,000 × 20 × 50 = 10,000,000 series
```

Each series needs an index entry and often in-memory metadata; ingest must look up/create the series on every write. Put something **unbounded** in a tag — user ID, request ID, full URL, email, container ID that churns — and cardinality grows without limit, causing OOMs, slow queries, and ingest stalls.

The fix: keep high-cardinality, unique-ish values in **fields** (unindexed) not **tags** (indexed); bound tag values; and pick a TSDB built for high cardinality (VictoriaMetrics) if you truly need it.

### Q27. [Practical] You see your Prometheus/InfluxDB instance OOMing. How do you diagnose and fix a cardinality blowup?

Diagnose first:
- **Prometheus:** check `prometheus_tsdb_head_series` (total active series) and use `topk(10, count by (__name__)({__name__=~".+"}))` to find the metrics with the most series. The `/api/v1/status/tsdb` endpoint lists top label-value cardinalities.
- **InfluxDB:** `SHOW SERIES CARDINALITY` and `SHOW TAG KEY CARDINALITY` pinpoint offenders.

Common root causes: a tag/label holding user IDs, request IDs, URLs with query strings, pod names that churn on every deploy, or unbounded error messages.

Fix:
- **Remove the offending label** or move it to a field/log.
- **Bucket/normalize** high-cardinality values (e.g., template URLs to `/users/:id` instead of `/users/12345`).
- **Drop labels at scrape/ingest time** via `metric_relabel_configs` (Prometheus) or by changing the exporter.
- **Add cardinality limits** (`--storage.tsdb.head-series-limit` style guards, per-tenant limits in Mimir/VM).
- If genuinely high cardinality is required, move to **VictoriaMetrics** or a column store like ClickHouse.

### Q28. [Practical] Write a PromQL query for the 99th percentile request latency using histograms.

With a Prometheus **histogram** metric `http_request_duration_seconds_bucket`, you estimate quantiles from the buckets:

```promql
histogram_quantile(
  0.99,
  sum by (le) (
    rate(http_request_duration_seconds_bucket[5m])
  )
)
```

`rate(..._bucket[5m])` gives the per-second rate in each bucket; `sum by (le)` aggregates across instances while preserving the bucket boundary label `le` ("less than or equal"); `histogram_quantile(0.99, ...)` interpolates the p99 from those cumulative buckets. **You must keep the `le` label** in the aggregation — that's the most common mistake. (Native/exponential histograms in Prometheus 3.x improve accuracy and avoid pre-choosing buckets.)

### Q29. [Theory] What is a hypertable in TimescaleDB and how does it work under the hood?

A **hypertable** is TimescaleDB's abstraction that looks like one ordinary Postgres table but is automatically partitioned into many **chunks** behind the scenes — primarily by time, optionally also by a "space" dimension (e.g., `device_id` hashed into partitions).

```sql
CREATE TABLE cpu_metrics (ts timestamptz, host text, usage double precision);
SELECT create_hypertable('cpu_metrics', by_range('ts', INTERVAL '1 day'));
```

Under the hood:
- Each chunk is a real child table covering a time interval (e.g., one day).
- Inserts are routed to the chunk for that timestamp; the live chunk stays small so indexes are fast.
- Queries use **chunk exclusion** (constraint exclusion on the time column) to skip non-matching chunks entirely.
- Chunks can be individually compressed, reordered, moved to cheaper tablespaces, or dropped (retention).

You interact with it as a single table — `SELECT ... FROM cpu_metrics` — and TimescaleDB transparently fans out across chunks. Full SQL, joins, and the Postgres ecosystem all still work.

### Q30. [Practical] How do you enable compression on a TimescaleDB hypertable, and what's the trade-off?

```sql
-- columnar compression, grouped by host, ordered by time desc
ALTER TABLE cpu_metrics SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'host',
    timescaledb.compress_orderby   = 'ts DESC'
);

-- automatically compress chunks older than 7 days
SELECT add_compression_policy('cpu_metrics', INTERVAL '7 days');
```

How it works: TimescaleDB converts row chunks into a **columnar** format and applies type-specific encodings (delta-of-delta for timestamps, Gorilla/XOR for floats, dictionary/RLE for repeated tags). `segmentby` groups rows that share a value (e.g., per host) so they compress together; `orderby` improves locality.

Trade-off: compressed chunks give **10–20× storage savings** and faster large scans, but they are effectively **immutable** — direct row-level `UPDATE`/`DELETE` on compressed data is restricted/expensive (newer versions allow it by decompressing affected segments). So compress *old, settled* chunks while keeping the recent, frequently-mutated chunk uncompressed.

### Q31. [Theory] How does the tags-vs-fields distinction affect indexing, querying, and cardinality?

The distinction is fundamentally about what's **indexed**:

| Aspect            | Tags                              | Fields                          |
|-------------------|-----------------------------------|---------------------------------|
| Indexed           | Yes (part of series identity)     | No (in InfluxDB v1/v2)          |
| Filterable fast   | Yes — `WHERE host='web1'`         | Slow / full scan                |
| Aggregatable      | Group by                          | Yes — `mean()`, `sum()`         |
| Data types        | Strings only                      | float/int/bool/string           |
| Cardinality impact| **High** — each value = new series| **None** — just stored values   |

Practical rules:
- Filter/group columns → **tags** (host, region, status, sensor_id).
- Measured numeric values → **fields** (usage, temperature, latency).
- **Never** put unbounded/unique values (request_id, user_id, raw URL) in a tag — that's the cardinality bomb.

If you need to *filter* by a high-cardinality value occasionally, accept a slower field scan rather than indexing it as a tag. (InfluxDB 3.x changes this calculus somewhat — its Parquet/columnar engine handles higher cardinality and lets you query fields more efficiently.)

### Q32. [Theory] How does a TSDB handle late-arriving (out-of-order) data?

**Late-arriving data** is a point whose timestamp is older than data already written — common with buffering clients, network delays, mobile/IoT devices that sync intermittently, or batch backfills.

How engines cope:
- **Out-of-order ingestion support** — modern Prometheus (OOO samples), InfluxDB, and TimescaleDB accept points that land in already-flushed chunks/blocks, routing them to the correct time chunk and (if needed) decompressing/rewriting it.
- **Re-materialize affected rollups** — continuous aggregates must recompute the *specific time buckets* the late data falls into. TimescaleDB tracks an "invalidation log" so only touched buckets are refreshed.
- **Watermarks / grace windows** — streaming systems define how long to wait for late data before finalizing a window; data later than the watermark may be dropped or sent to a side path.

Costs: late data into a *compressed* chunk forces decompress→rewrite→recompress (expensive). Late data into a finalized rollup window requires recomputation. The design tension is "finalize early for speed" vs "wait longer for completeness."

```
timeline:     [====== window 10:00–10:05 ======]
finalized at: 10:06 (watermark)
late point:   ts=10:03 arrives at 10:08  → must reopen & recompute that window
```

### Q33. [Practical] How would you backfill historical data efficiently into a TSDB?

Backfilling (loading old data) needs care because it writes into non-recent chunks and can thrash compression/rollups:

1. **Bulk insert, not row-by-row** — use `COPY` (TimescaleDB), line-protocol batches (InfluxDB), or the bulk import tools; batch thousands of points per request.
2. **Insert into uncompressed chunks** — backfill *before* compressing, or temporarily decompress target chunks; writing into compressed chunks is slow.
3. **Disable/defer continuous aggregate refresh** during the load, then refresh the affected ranges once at the end (`refresh_continuous_aggregate(..., start, end)`), rather than triggering per-insert invalidations.
4. **Order by time** within batches to maximize compression locality.
5. **Tune chunk size** so backfill doesn't create thousands of tiny chunks.

```sql
-- after a big historical load, refresh rollups for the loaded window once
CALL refresh_continuous_aggregate('cpu_hourly', '2025-01-01', '2025-02-01');
```

### Q34. [Theory] Compare InfluxDB, TimescaleDB, Prometheus, and VictoriaMetrics on their core design choices.

| Dimension        | InfluxDB (3.x)            | TimescaleDB              | Prometheus               | VictoriaMetrics          |
|------------------|---------------------------|--------------------------|--------------------------|--------------------------|
| Foundation       | Arrow/DataFusion/Parquet  | PostgreSQL extension     | Custom Go TSDB           | Custom Go TSDB           |
| Query language   | SQL / InfluxQL            | Full SQL                 | PromQL                   | PromQL/MetricsQL         |
| Ingestion        | Push (line protocol)      | Push (SQL/COPY)          | Pull (scrape)            | Push (remote-write/pull) |
| Strength         | IoT/metrics, flexible     | SQL + relational joins   | Cloud-native monitoring  | High cardinality, LTS    |
| Cardinality      | Improved in v3            | Good                     | Limited (local)          | Excellent                |
| Long-term store  | Yes                       | Yes                      | No (needs remote)        | Yes                      |
| Best when…       | You want a dedicated TSDB | You want SQL + ecosystem | You're on Kubernetes     | You scale Prometheus     |

Quick heuristics: **Kubernetes monitoring → Prometheus**; **scale/retain Prometheus data → VictoriaMetrics (or Thanos/Mimir)**; **want SQL + joins + relational features → TimescaleDB**; **flexible IoT/metrics platform → InfluxDB**.

### Q35. [Practical] Write a query for week-over-week comparison: this week's hourly traffic vs the same hours last week.

A classic dashboard query using a time offset:

```sql
SELECT
    time_bucket('1 hour', ts)                       AS hour_of_week,
    sum(requests) FILTER (
        WHERE ts >= date_trunc('week', now())
    )                                               AS this_week,
    sum(requests) FILTER (
        WHERE ts >= date_trunc('week', now()) - interval '7 days'
          AND ts <  date_trunc('week', now())
    )                                               AS last_week
FROM traffic
WHERE ts >= date_trunc('week', now()) - interval '7 days'
GROUP BY hour_of_week
ORDER BY hour_of_week;
```

In PromQL the equivalent uses the `offset` modifier: `sum(rate(requests[1h])) and sum(rate(requests[1h] offset 7d))`. The pattern — align a current window against a shifted-back window — underlies most "vs last period" comparisons.

### Q36. [Theory] What is a downsampling/retention tiered storage strategy, and how do you design one?

A **tiered strategy** keeps multiple resolutions of the same data, each with its own retention, so recent data is detailed and old data is coarse but long-lived:

```
Tier          Resolution   Retention    Source
raw           1 s          7 days       direct ingest
metrics_1m    1 min        90 days      rollup of raw
metrics_1h    1 hour       2 years      rollup of 1m
metrics_1d    1 day        7 years      rollup of 1h
```

Design steps:
1. **Identify query horizons** — what's the longest dashboard range and what resolution does it need?
2. **Pick rollup intervals** matching those horizons (don't keep 1 s data for a 2-year chart).
3. **Choose decomposable aggregates** — sum, count, min, max (and sketches for percentiles) so each tier can derive the next.
4. **Set retention per tier** so storage cost stays bounded.
5. **Automate** with continuous aggregates + retention policies, not cron.

The result: dashboards query the *coarsest tier that still answers the question*, which is dramatically faster and cheaper than always scanning raw data.

### Q37. [Theory] How do continuous aggregates / recording rules improve query performance, and what's the catch?

They **pre-compute** expensive aggregations so dashboards read small, ready-made results instead of scanning raw data at query time. A p99-latency-by-service panel over 30 days might scan billions of raw points; against an hourly rollup it scans thousands of rows — orders of magnitude faster, and cheaper on CPU/IO.

Prometheus **recording rules** do the same for PromQL: evaluate `job:http_requests:rate5m` once per interval and store it, so dashboards/alerts query the small recorded series.

The catches:
- **Staleness** — results lag by the refresh interval; very recent data may be missing or partial (often solved by "real-time aggregates" that union the materialized part with a live query of the newest data).
- **Storage** — you now store both raw and rollups.
- **Maintenance/correctness** — late data must trigger re-materialization of affected windows; choosing decomposable aggregates is essential (percentiles need sketches).

### Q38. [Practical] How do you implement a moving average / smoothing in a TSDB query?

Moving averages smooth noisy series. Two common approaches:

**SQL window function:**
```sql
SELECT
    bucket,
    avg_usage,
    avg(avg_usage) OVER (
        ORDER BY bucket
        ROWS BETWEEN 4 PRECEDING AND CURRENT ROW
    ) AS moving_avg_5
FROM cpu_5min
ORDER BY bucket;
```
This averages each point with the previous four (a 5-point trailing window).

**PromQL** has `avg_over_time` for a time-based window:
```promql
avg_over_time(node_cpu_usage[15m])
```

Use trailing windows for alerting (causal, no future data) and centered windows only for offline analysis. Note that moving averages introduce lag proportional to the window width.

## 🟠 Advanced (8–12 yrs)

### Q39. [Theory] Walk through the Prometheus TSDB on-disk format (head block, WAL, blocks, compaction).

Prometheus storage has several layers:

```
ingest → [ Head block (in-memory) ] ──WAL──► disk (crash recovery)
              │ every ~2h
              ▼
        [ persistent block: 2h ]   immutable directory:
              │                      chunks/  (compressed samples)
   compaction merges blocks         index    (postings: label→series)
              ▼                      meta.json, tombstones
        [ larger blocks: 1d, ... ]
```

- **Head block** — the most recent (~2 h) data lives in memory for fast read/write; every append is also written to the **WAL** so a crash can be replayed.
- **Block** — periodically the head is flushed to an immutable on-disk block (a directory) containing: **chunks** (the actual delta-of-delta/XOR-compressed samples), an **index** (an inverted index / *postings list* mapping label-value pairs → series IDs), `meta.json`, and `tombstones` (for deletes).
- **Compaction** — background process merges adjacent small blocks into bigger ones, deduplicates, applies tombstones, and re-compresses — reducing the number of blocks a query must open.
- **Retention** — old blocks past the retention window are simply deleted (whole directories).

The **inverted index (postings)** is what makes label-based queries fast: `host="web1"` looks up a posting list of matching series, then intersects with other label posting lists.

### Q40. [Theory] How do label/tag inverted indexes work, and why does high cardinality hurt them specifically?

TSDBs that query by labels (Prometheus, VictoriaMetrics, InfluxDB's TSI) use an **inverted index**: for each label key=value pair, store a sorted **postings list** of the series IDs that have it.

```
host="web1"   → [series 3, 7, 12, 88, ...]
region="us"   → [series 3, 7, 50, 88, ...]
query host="web1" AND region="us"  →  intersect lists → [3, 7, 88]
```

Query = intersect/union postings lists; this is fast even over millions of series. But:

- **Every unique label value adds a postings entry**, and **every unique series adds an index entry and in-memory metadata**. High cardinality means a gigantic index that may not fit in memory.
- **Index build/lookup cost** grows; ingest must resolve every incoming point to a series ID (a hashmap lookup that grows with cardinality).
- **Churn** (series that appear and disappear, e.g., per-deploy pod names) bloats the index with dead series until compaction reclaims them.

That's why cardinality is *the* scaling axis: it's not the number of *samples* (cheap, compressible) but the number of *series* that stresses the index and memory.

### Q41. [Theory] When should you choose a column store (ClickHouse) over a purpose-built TSDB, and vice versa?

Both are columnar and great at scans, but they optimize for different things:

**Choose a purpose-built TSDB (Influx/Timescale/Prometheus/VM) when:**
- The workload is classic metrics: regular cadence, time-range queries, downsampling/retention/rollups out of the box.
- You want PromQL/time-series functions, alerting, and the monitoring ecosystem.
- Operational simplicity for metrics matters more than ad-hoc analytics.

**Choose a column store like ClickHouse when:**
- **Very high cardinality** and **wide, ad-hoc analytical queries** (group by many dimensions, arbitrary filters) — observability events, logs, traces, clickstreams.
- You need **fast aggregations over enormous datasets** with flexible SQL, joins, and arrays/maps.
- You're building an analytics/observability platform (many vendors back logs+metrics+traces with ClickHouse).

```
              regular metrics, rollups, alerting  →  TSDB
high-cardinality events, ad-hoc analytics, logs  →  column store (ClickHouse)
```

The line is blurring: TimescaleDB and InfluxDB 3.x are columnar; ClickHouse has TTL/materialized views for time-series. Decide on cardinality, query shape (fixed metric queries vs ad-hoc analytics), and ecosystem fit.

### Q42. [Practical] Design the storage and rollup strategy for an IoT platform ingesting 1M points/sec from 500k devices. Walk through your decisions.

**Requirements:** 1M points/s, 500k devices, mixed dashboards (live + historical), bounded cost.

1. **Cardinality budget.** Series ≈ devices × metrics. 500k devices × (say) 10 metrics = 5M series — manageable, *if* we keep device_id as a tag but never add unbounded tags (no per-message IDs). Validate the tag set up front.
2. **Engine choice.** High ingest + high-ish cardinality + long retention → **VictoriaMetrics** or **TimescaleDB** (with space partitioning by device hash), or InfluxDB 3.x. If we want SQL/joins with device metadata, TimescaleDB; if pure metrics at huge cardinality, VictoriaMetrics/ClickHouse.
3. **Ingest path.** Buffer through Kafka → batched writers (thousands of points/request). Decouples spikes and enables replay/backfill. Partition Kafka by device hash for ordering and parallelism.
4. **Partitioning.** Time chunks of ~1 day, plus space partitioning by `hash(device_id)` so writes and queries spread across nodes/disks.
5. **Tiered rollups:** raw 1 s for 7 days → 1 min for 90 days → 1 h for 2 years, via continuous aggregates keeping sum/count/min/max (+ t-digest for percentiles).
6. **Compression** on chunks older than 1–2 days (10–20× savings); keep the live chunk uncompressed.
7. **Retention policies** auto-drop expired chunks per tier.
8. **Query routing:** dashboards hit the coarsest rollup that answers the range; only "live device detail" hits raw.

```
devices → Kafka(partition by device) → batch writers → TSDB cluster
                                              │
                          raw(7d) → 1m(90d) → 1h(2y)  [continuous aggregates]
                                              │
                                   compression + retention per tier
```

### Q43. [Behavioral] Tell me about a time you had to choose a database technology and convinced your team. How did you handle the trade-offs?

(Structure the answer with Situation–Task–Action–Result and show *engineering judgment*, not just preference.)

A strong answer: "Our monitoring was on a single Postgres table that was 4 TB, queries timed out, and `DELETE`-based retention caused vacuum storms and weekly incidents. **Task:** pick a sustainable store. **Action:** I framed it as a decision matrix — ingest rate, cardinality, retention horizon, query shapes, team SQL familiarity, and operational cost. I prototyped TimescaleDB (kept SQL + our Grafana/Postgres tooling) and VictoriaMetrics (better at raw scale) against a week of production traffic, measuring compression, p99 query latency, and ingest headroom. TimescaleDB gave 14× compression, sub-second rollup queries via continuous aggregates, and let the team keep writing SQL — the migration risk was lowest. I documented the trade-offs, including where VM would win if cardinality 10×'d, so the decision was reversible. **Result:** storage dropped from 4 TB to ~300 GB, query timeouts disappeared, and retention became a cheap chunk-drop." 

The interviewer is listening for: did you quantify trade-offs, prototype with real data, consider team/operational factors, and keep the decision reversible — not just chase the trendiest tool.

### Q44. [Theory] How do you correctly compute and store percentiles across rollups? Why is naive percentile rollup wrong?

Percentiles are **not decomposable**: you cannot take the p99 of several buckets and combine them into the p99 of the union — you'd need the raw distribution. Averaging or maxing per-bucket p99s gives a wrong (usually under- or over-stated) answer.

The correct approach is to store a **mergeable sketch** of the distribution per bucket, then merge sketches to compute percentiles at any granularity:

- **t-digest** — compact, accurate at the tails, mergeable (used in TimescaleDB Toolkit `percentile_agg`, many systems).
- **HDR histogram / DDSketch** — fixed relative-error histograms, also mergeable.
- **Prometheus histograms** — store cumulative bucket counts; `histogram_quantile` interpolates, and bucket counts **are** additive across instances/time (so they merge correctly).

```sql
-- store a t-digest per hour
CREATE MATERIALIZED VIEW latency_hourly WITH (timescaledb.continuous) AS
SELECT time_bucket('1 hour', ts) AS bucket, service,
       percentile_agg(latency_ms) AS pct          -- mergeable sketch
FROM requests GROUP BY bucket, service;

-- later, merge hourly sketches into a daily p99 correctly
SELECT service,
       approx_percentile(0.99, rollup(pct)) AS p99
FROM latency_hourly
WHERE bucket >= now() - interval '1 day'
GROUP BY service;
```

The interview point: store the *sketch*, not the *percentile*, so you can re-aggregate accurately.

### Q45. [Practical] Diagnose: a "last 30 days" dashboard query suddenly takes 40 seconds. Walk through your investigation.

Systematic diagnosis:

1. **Is it scanning raw data?** Most likely the panel queries raw points instead of a rollup. `EXPLAIN (ANALYZE)` (TimescaleDB) or check the query — point it at the hourly continuous aggregate.
2. **Chunk exclusion working?** Confirm the time predicate is on the partitioning column and is sargable (no function wrapping the timestamp, correct type). If chunks aren't excluded, every chunk is opened.
3. **Did compression/retention change?** A recently compressed chunk scans differently; a retention misconfig may have left far more data than expected.
4. **Cardinality growth?** A new label (deploy IDs, pod names) may have multiplied series, bloating the index and slowing intersection. Check active series trend.
5. **Continuous aggregate stale or disabled?** If the rollup refresh job failed, dashboards may have fallen back to raw, or "real-time aggregate" is recomputing a huge live tail.
6. **Resource pressure?** Index no longer fits in memory → disk thrash; check cache hit ratio, I/O wait.

```
slow query → EXPLAIN ANALYZE
   ├─ scanning raw? → repoint to rollup
   ├─ chunks not excluded? → fix time predicate (no func on ts)
   ├─ series count spiked? → find/kill high-card label
   └─ rollup job failed? → fix continuous aggregate refresh
```

The discipline: measure with EXPLAIN, isolate whether it's *data volume*, *cardinality*, or *missing rollup*, then fix the specific cause.

### Q46. [Theory] How do TSDBs achieve horizontal scale and high availability? Discuss clustering models.

TSDBs scale along two axes — **ingest/storage** and **query** — using a few patterns:

- **Sharding by series/time.** Distribute series across nodes by hashing the series key, and partition each node's data by time. Writes for a series go to its shard; queries fan out and merge. (VictoriaMetrics cluster: `vmstorage` shards, `vminsert` routes by series hash, `vmselect` fans out.)
- **Replication.** Each shard is replicated to N nodes for HA; on node loss, a replica serves. Some systems use quorum writes; Prometheus instead favors **running two identical replicas** scraping the same targets (dedup at query via Thanos/VM).
- **Federation / remote-write.** Prometheus stays single-node but **remote-writes** to a scalable backend (Thanos, Cortex/Mimir, VictoriaMetrics) for long-term, global, HA storage.
- **Read path aggregation.** A query layer (Thanos Querier, vmselect) deduplicates across replicas and merges results from shards.

```
targets → Prom replica A ┐
targets → Prom replica B ┘ remote_write → [ VM/Mimir cluster: shard+replicate ]
                                              ▲ query (dedup + fan-in) ▲
                                                     Grafana
```

Trade-offs: consistency is usually **eventual/best-effort** (monitoring tolerates small gaps), and the hard part is deduplicating overlapping replica data and handling clock skew at query time.

### Q47. [Theory] What consistency and durability guarantees do TSDBs typically provide, and why is that acceptable?

Most TSDBs deliberately **relax** strong ACID guarantees in favor of ingest throughput and availability:

- **Durability** — a WAL gives crash recovery, but many TSDBs **batch and buffer** writes in memory and ack before the data is fully persisted/compacted; some allow tunable fsync. A crash can lose the last few seconds of un-flushed data.
- **Consistency** — clustered TSDBs are typically **eventually consistent**; replicas may briefly diverge, and a query right after a write might miss the newest point. No cross-series transactions.
- **No isolation/transactions** — you can't atomically update many series; writes are independent appends.

Why acceptable for the metrics use case: monitoring/IoT data is **statistical and high-volume** — losing one of millions of samples, or reading data a second stale, doesn't change a CPU-trend chart or an alert that fires on sustained breaches. The workload values *throughput, availability, and cost* over *exactness*. (The corollary: don't use a TSDB for data where every record must be exact and transactional — e.g., financial ledgers or billing of record.)

## 🔴 Expert (15+ yrs)

### Q48. [Theory] Compare the storage engine evolution of InfluxDB (TSM → IOx) and what it reveals about TSDB design tensions.

InfluxDB's engine history is a case study in TSDB trade-offs:

- **TSM (Time-Structured Merge Tree, Influx 1.x/2.x)** — a custom LSM-style engine: a WAL, an in-memory cache, and immutable on-disk **TSM files** (columnar, delta/XOR-compressed) merged by compaction, plus a **TSI** (Time Series Index) inverted index on disk to bound memory. It was fast for moderate cardinality but **struggled with high cardinality** — the per-series index and in-memory structures ballooned, the infamous "cardinality wall."
- **IOx (InfluxDB 3.x)** — a ground-up rewrite on the **Apache Arrow** in-memory format, **DataFusion** query engine, **Parquet** as the durable columnar file format, and object storage as the backing store. This decouples compute from storage, leans on a mature columnar ecosystem (better high-cardinality and analytical queries), and exposes **SQL**.

What it reveals:
- The central tension is **cardinality vs. memory/index cost** — every major TSDB redesign is partly an answer to "how do we stop the series index from killing us."
- The industry is **converging on columnar formats (Arrow/Parquet) and separation of compute and storage**, blurring the line between TSDB and analytical column store.
- Custom engines give control but enormous maintenance burden; building on Arrow/DataFusion/Parquet trades some specialization for ecosystem leverage and analytical flexibility.

### Q49. [Theory] Design a multi-tenant TSDB platform. What are the hard problems and how do you solve them?

A multi-tenant metrics platform (think Grafana Cloud / hosted Prometheus) faces several hard problems:

1. **Tenant isolation** — one noisy tenant (cardinality bomb, query storm) must not degrade others. Solutions: per-tenant **cardinality limits**, ingest rate limits, query concurrency/`max samples` limits, and resource quotas (Mimir/Cortex enforce these per tenant).
2. **Data partitioning** — tenant ID becomes a top-level shard dimension (often the first label / hash key) so a tenant's data is co-located and droppable. Avoid cross-tenant index mixing.
3. **Cost attribution / fair use** — meter samples ingested, series count, query CPU per tenant for billing and throttling.
4. **Cardinality governance** — enforce per-tenant active-series caps and reject/relabel offending labels at ingest; expose cardinality dashboards so tenants self-correct.
5. **Query fairness** — schedule queries with per-tenant queues; split big queries (Mimir query-frontend splits by time and caches results).
6. **Retention/rollup per tenant** — different tiers/retention per plan, implemented via per-tenant policies.
7. **Security** — strict tenant scoping on every read/write path (a missing tenant filter is a data-leak bug).

```
ingest → auth/tenant-id → per-tenant limits (rate, cardinality)
       → shard by (tenant, series hash) → replicated storage
query  → tenant scoping → query-frontend (split + cache + per-tenant queue)
```

The recurring theme: **the multiplexing dimension is the tenant**, and almost every limit, shard key, and quota is keyed on it — because in a shared TSDB, *cardinality and query cost are the blast radius* you must contain.

### Q50. [Behavioral] Describe leading a migration off a legacy time-series system. How did you manage risk and stakeholders?

(Use STAR; emphasize risk management, dual-running, and stakeholder communication.)

Strong narrative: "**Situation:** We ran a decade-old Graphite/Whisper cluster that couldn't keep up with ingest, had no high availability, and on-call hated it. **Task:** migrate to a modern stack (Prometheus + VictoriaMetrics for long-term) without losing historical data or breaking thousands of dashboards/alerts owned by many teams. 

**Action:** I treated it as a phased, reversible migration. (1) Stood up the new stack **in parallel** and dual-wrote/remote-wrote so both systems had live data — no big-bang cutover. (2) Wrote a **dashboard/alert translation** layer and migrated panels team-by-team, validating each against the old system. (3) Backfilled history via batch import into VM. (4) Defined explicit **success metrics and rollback criteria** (query parity, alert parity, ingest headroom) and a **soak period** where both ran. (5) Communicated a clear timeline, ran office hours, and gave each owning team a checklist — I made the affected teams partners, not surprised bystanders. 

**Result:** We cut storage cost ~70%, gained HA, dropped p99 query latency from seconds to sub-second, and decommissioned Graphite with zero alerting gaps. The dual-run cost more short-term but eliminated the risk of a silent monitoring outage — which for a *monitoring* system is the worst possible failure." 

Interviewers want: incremental/reversible strategy, real risk controls (dual-run, parity checks, rollback), and treating downstream owners as stakeholders you actively communicated with.

### Q51. [Theory] How would you build accurate exemplars/traces correlation and high-cardinality drill-down without blowing up your TSDB?

The tension: metrics must stay **low-cardinality** (cheap, aggregatable), but on-call needs **high-cardinality drill-down** ("which *request* caused this latency spike?"). Putting request/trace IDs in metric labels is the cardinality bomb. Solutions:

- **Exemplars** — attach a small number of sampled trace IDs to metric series (Prometheus/OpenMetrics exemplars) without making them labels. A latency histogram bucket carries example trace IDs; you click from a spiking metric straight to a representative trace. Cardinality stays bounded because exemplars are *sampled annotations*, not new series.
- **Two stores, correlated by labels** — keep aggregatable metrics in the TSDB and high-cardinality events/traces/logs in a **column store/trace backend** (ClickHouse, Tempo, Loki). Correlate via shared low-cardinality labels (service, endpoint) + time range. The metric tells you *something is wrong and when*; you pivot to the event store for *which/why*.
- **Sampling + aggregation at the edge** — pre-aggregate per-endpoint in the collector (OpenTelemetry) so the TSDB sees bounded dimensions, while raw spans go to the trace store.

```
metric spike (low-card)  ──exemplar trace_id──►  trace backend (high-card)
        │  shared labels (service, endpoint, time window)        │
        └────────────────── correlate ───────────────────────────┘
```

The principle: **don't make the TSDB hold high-cardinality identity**; keep it for aggregatable signals and *link* to a high-cardinality store for drill-down.

### Q52. [Theory] At extreme scale, how do you handle clock skew, write ordering, and exactly-once semantics in time-series ingestion?

At millions of points/sec across many producers, time itself becomes a hard problem:

- **Clock skew.** Producers' clocks disagree, so a "later" event may carry an earlier timestamp. Mitigations: rely on **NTP/chrony** sync, but design downstream logic to tolerate skew — use **grace windows/watermarks** before finalizing aggregates, and prefer **event-time** semantics with bounded lateness rather than assuming monotonic arrival. For strict ordering needs, attach a logical sequence/ingest-time alongside event-time.
- **Out-of-order / late writes.** Accept OOO samples (modern engines do) and re-materialize affected rollup windows via invalidation logs; define a max lateness past which data is dropped or side-channeled.
- **Exactly-once-ish.** True exactly-once is expensive; TSDBs aim for **idempotent ingest**: a point is keyed by (series, timestamp), so re-delivering the same point is a no-op/overwrite rather than a duplicate. Combined with an at-least-once transport (Kafka) + idempotent writes, you get effectively-once. Use the (series, ts) primary key and `ON CONFLICT`/last-write-wins semantics.
- **Backpressure & ordering.** Partition the ingest pipeline (e.g., Kafka by series hash) to preserve per-series order and bound reordering, and apply backpressure so spikes buffer instead of dropping.

```
producers (skewed clocks) → Kafka (partition by series, at-least-once)
   → idempotent writers keyed by (series, ts)  → engine accepts OOO
   → watermark/grace window → finalize + re-materialize late windows
```

The expert framing: you **don't fight physics** (perfect global time/order) — you make ingestion *idempotent on (series, ts)*, tolerate bounded lateness with watermarks, and accept eventual, statistically-correct results, because that's the right consistency model for telemetry.

## 🧩 Extended Questions — Set 1: Deeper theory & internals

### 🟢 — extended

#### Q53. [Theory] What does "append-only" actually buy a storage engine, beyond "writes are fast"?

Append-only is not just a write optimization — it changes the entire engine's contract. When data is never mutated in place, several properties fall out for free:

- **Sequential I/O.** Appends go to the end of a file/segment, so the disk head (or SSD controller) writes contiguous blocks. Sequential writes are an order of magnitude faster than random writes even on NVMe, and they minimize write amplification on flash.
- **Immutability enables aggressive compression.** You can only apply delta-of-delta or XOR encoding to a block if you know it will never change after it's sealed. A sealed, compressed block is read-only by construction.
- **Lock-free reads.** Readers never see a half-updated record, so you rarely need row-level locking — a reader either sees a block or it doesn't. Concurrency is cheap.
- **Trivial crash recovery.** Recovery means "replay the WAL from the last checkpoint and discard any partial tail" — no in-place corruption to untangle.
- **Cheap retention.** Dropping old data = deleting whole sealed files, a metadata operation, instead of rewriting pages.

The cost you pay is that *edits* (true updates/deletes of old points) become awkward — they're handled by tombstones, decompress-rewrite-recompress, or merge-on-read, all of which are deliberately made rare because the workload is append-mostly.

#### Q54. [Theory] What is the difference between event time and ingestion time, and why does a TSDB care?

- **Event time** is when the measurement actually happened (the timestamp the sensor/app stamped on the point).
- **Ingestion time** (also called processing or arrival time) is when the database received and wrote it.

For perfectly behaved producers these are nearly equal, but they diverge whenever there's buffering, network delay, retries, or an offline device that syncs later. A TSDB cares because almost everything is keyed on event time: bucketing, retention, downsampling, and "last 5 minutes" queries all use event time. If you accidentally bucket or retain by ingestion time, a device that uploads a day-old batch lands in *today's* bucket and corrupts your charts.

The practical consequences:
- **Late data** is defined relative to event time (an old event-time point arriving now).
- **Watermarks/grace windows** decide how long to wait, in event time, before finalizing a window.
- **Out-of-order** means event time going backwards relative to what's already stored.

A useful diagnostic is to keep *both* timestamps when debugging ingestion problems — a large gap between them points at a buffering producer or a clock-skew issue.

#### Q55. [Practical] Write a SQL query that, for each sensor, returns the most recent reading (the "last point" query) efficiently.

The "last point per series" query is extremely common (current dashboards, status pages). The naive `GROUP BY` + subquery is fine, but `DISTINCT ON` (Postgres/TimescaleDB) is the idiomatic, index-friendly form:

```sql
SELECT DISTINCT ON (sensor_id)
    sensor_id,
    ts,
    temperature,
    humidity
FROM readings
WHERE ts >= now() - interval '1 day'   -- bound the scan with chunk exclusion
ORDER BY sensor_id, ts DESC;
```

`DISTINCT ON (sensor_id)` keeps the first row per `sensor_id` after the `ORDER BY`, and because we order by `ts DESC` that first row is the newest. The `WHERE ts >= now() - interval '1 day'` is important: without a time bound the planner may scan all history. TimescaleDB also ships a purpose-built `last(value, time)` aggregate and "skip scan" optimization for exactly this pattern, and InfluxDB has a `last()` selector — all solving the same "give me the current value of every series" need.

#### Q56. [Theory] What is the WAL (write-ahead log) and what role does it play in a TSDB specifically?

A **write-ahead log** is an append-only file to which every incoming write is recorded *before* it's acknowledged, so that durability does not depend on the (slower) flush of in-memory structures to their final on-disk form.

In a TSDB the flow is: a point arrives → it's appended to the WAL and inserted into the in-memory head block → the client is ack'd. The head block is later flushed to an immutable, compressed on-disk block, at which point the corresponding WAL segment can be discarded. If the process crashes between ack and flush, recovery replays the WAL to rebuild the head block, so no acknowledged data is lost.

TSDB-specific nuances:
- The head block holds *recent, hot* data that is also the most-queried, so the WAL protects exactly the window users care most about.
- Because the head data isn't yet compressed, the WAL is also where you'd see out-of-order/late samples first land before being sorted into blocks.
- Many TSDBs let you tune WAL fsync behavior (per-write vs periodic) to trade a few seconds of potential data loss for much higher ingest throughput — acceptable for statistical telemetry, not for a ledger.

#### Q57. [Theory] Why are TSDBs almost always columnar rather than row-oriented on disk?

Time-series queries overwhelmingly read **one or a few columns across many rows** — "average `usage` over the last hour" touches the `usage` column and the `ts` column, not the other fields. A columnar layout stores each column contiguously, which gives:

- **Less I/O.** You read only the columns the query needs, skipping unrelated fields entirely. A row store would drag every column of every row off disk.
- **Far better compression.** A column holds values of one type with similar magnitude (all the `usage` floats, all the `ts` timestamps), so type-specific encodings — delta-of-delta for timestamps, XOR/Gorilla for floats, dictionary/RLE for repeated tag strings — work dramatically better than compressing a mixed-type row.
- **Vectorized execution.** The engine can process a column as a tight array (SIMD-friendly), summing or filtering thousands of values per CPU instruction batch.

```
Row store:   [ts,host,usage,idle][ts,host,usage,idle]...  (read everything)
Column store: [ts ts ts ...][host host ...][usage usage ...]  (read only what you need)
```

The trade-off — columnar is bad at "fetch one whole row by key" — barely matters here, because TSDBs almost never do single-row point lookups; they do column-wise range scans.

#### Q58. [Practical] Show how to compute the rate of change of a counter in SQL (the equivalent of PromQL `rate()`).

Counters only increase (and reset to 0 on restart), so you can't use the raw value — you need the per-second increase between consecutive samples, handling resets. The SQL idiom uses the `lag()` window function:

```sql
SELECT
    ts,
    host,
    CASE
        WHEN value >= lag(value) OVER w
            THEN (value - lag(value) OVER w)
                 / EXTRACT(EPOCH FROM (ts - lag(ts) OVER w))
        ELSE  -- counter reset: value dropped, treat increase as the new value
            value / EXTRACT(EPOCH FROM (ts - lag(ts) OVER w))
    END AS per_second_rate
FROM requests_total
WINDOW w AS (PARTITION BY host ORDER BY ts)
ORDER BY host, ts;
```

`lag(value) OVER w` gives the previous sample for that host; dividing the value delta by the time delta (in seconds via `EXTRACT(EPOCH ...)`) yields the per-second rate. The `CASE` detects a **counter reset** (current value below the previous) and treats the increase as the current value, mirroring how PromQL's `rate()` extrapolates over resets. This is exactly the logic a TSDB hides behind `rate()` / `irate()`.

#### Q59. [Theory] What is "chunk exclusion" (constraint/partition pruning) and why is it the single most important query optimization in a TSDB?

**Chunk exclusion** is the planner's ability to skip entire time partitions whose time range cannot possibly contain rows matching the query's time predicate. If a query says `WHERE ts >= now() - interval '2 hours'`, and chunks are one-day partitions, the planner opens only the chunk(s) overlapping the last two hours and never touches the thousands of older chunks.

It's the most important optimization because TSDB tables are enormous (billions of rows over years), and almost every query is bounded in time. Without chunk exclusion, a "last 2 hours" query would scan the whole table; with it, the work is proportional to the *queried range*, not the *stored range*. This is the core reason time partitioning exists at all.

The critical gotcha: chunk exclusion requires the time predicate to be **sargable** — a plain comparison on the raw partitioning column. If you wrap the timestamp in a function (`WHERE date_trunc('day', ts) = '...'`) or compare against a non-immutable expression in a way the planner can't fold, pruning is defeated and you fall back to a full scan. "Keep the timestamp bare on the left side of the comparison" is the rule that preserves it.

#### Q60. [Theory] Tags vs fields: from a pure storage-engine perspective, what physically differs between them?

The tags-vs-fields split is usually taught as "filter vs aggregate," but physically the difference is about **what gets put in the inverted index and what defines series identity**:

- **Tags** become part of the **series key**. The engine computes an identifier from `(measurement + sorted tag set)` and adds an entry to the **inverted index** (postings list) for each `tag_key=tag_value` pair. Every new tag-value combination is therefore a new series — a new index entry and new in-memory metadata. Tags are stored once per series, not per point.
- **Fields** are the actual sample values stored in the **columnar data blocks**, compressed with delta/XOR encodings. They are *not* indexed (in classic Influx v1/v2), carry no identity, and adding a new field value does not create a series — it just appends a number to a column.

So the cardinality cost lives entirely on the tag side: tags drive index size and memory, fields drive only raw data volume (which compresses superbly). That asymmetry is precisely why "put unbounded/unique values in fields, never tags" is the cardinal rule — a field value is a cheap compressed number, a tag value is an index entry that multiplies series count.

#### Q81. [Theory] Why is the `(series, timestamp)` pair the natural primary key for a TSDB, and what does choosing it enable?

A TSDB's logical model is "for each series, a sorted stream of (timestamp → value) points," so the unique identity of any data point is exactly its **series** (measurement + tags) plus its **timestamp**. Treating `(series, ts)` as the primary key is not an arbitrary choice — it's the shape of the data itself, and it enables several core behaviors:

- **Idempotent ingest.** Re-delivering the same `(series, ts)` is a no-op or last-write-wins overwrite, never a duplicate. Combined with an at-least-once transport (Kafka), this yields effectively-once semantics without expensive distributed transactions.
- **Natural sort and compression.** Storing rows sorted by `(series, ts)` clusters each series' points contiguously and in time order, which is precisely what delta-of-delta and XOR encoders need to compress well.
- **Efficient range scans.** "Series X over `[t0, t1)`" is a contiguous slice of the sorted data — a single sequential read.
- **Clean deduplication on conflict.** `ON CONFLICT (series, ts) DO UPDATE` (or last-write-wins) gives a well-defined merge rule for overlapping/late data.

So the key choice cascades into idempotency, compression locality, scan efficiency, and conflict resolution — four things a TSDB depends on — which is why nearly every engine is internally organized around `(series, ts)`.

### 🟡 — extended

#### Q61. [Theory] How does an LSM-tree relate to a TSDB's storage engine, and where does the analogy break down?

An **LSM-tree (log-structured merge-tree)** buffers writes in an in-memory structure (memtable), flushes it to immutable sorted on-disk files (SSTables), and runs background **compaction** to merge those files into fewer, larger ones. TSDBs borrow this shape directly: the head/memory block is the memtable, sealed compressed blocks/chunks/TSM-files are the SSTables, and compaction merges and re-compresses them.

The analogy holds for: append-only writes, immutable on-disk segments, a WAL for durability, and tiered compaction reducing the number of files a read must open.

Where it breaks down:
- **Sort key.** A general LSM-tree (RocksDB, Cassandra) sorts by an arbitrary primary key and must handle random key distributions and read-modify-write. A TSDB's effective sort is `(series, time)`, and time is near-monotonic, so flushes are mostly already in order — far less merge work.
- **No read-modify-write.** LSM key-value stores expect updates and tombstones across the whole keyspace; TSDBs assume immutability, so deletes/updates are rare and handled specially.
- **Compaction goals differ.** TSDB compaction is as much about *better compression and downsampling alignment* (merging 2h blocks into 1d blocks) as about read amplification, whereas a KV LSM compacts primarily to bound read amplification and reclaim space from overwritten keys.

So a TSDB is "LSM-flavored," but specialized: the monotonic time dimension lets it avoid much of the general LSM machinery for random updates.

#### Q62. [Practical] Write a query to fill gaps in a sparse series so every bucket has a value (gap filling / interpolation).

Dashboards often need a value in *every* time bucket even when no data arrived, so lines don't break. TimescaleDB provides `time_bucket_gapfill` plus `locf` (last-observation-carried-forward) and `interpolate`:

```sql
SELECT
    time_bucket_gapfill('1 minute', ts) AS bucket,
    sensor_id,
    avg(temperature)                       AS avg_temp,
    locf(avg(temperature))                 AS temp_filled,        -- carry last value forward
    interpolate(avg(temperature))          AS temp_interpolated   -- linear interpolation
FROM readings
WHERE ts >= now() - interval '1 hour'
  AND ts <  now()
GROUP BY bucket, sensor_id
ORDER BY sensor_id, bucket;
```

`time_bucket_gapfill` emits a row for every 1-minute bucket in the range even when the underlying group is empty; `locf()` fills those empty buckets with the previous known value, while `interpolate()` draws a straight line between the surrounding known points. The explicit `ts < now()` upper bound is required so gapfill knows the range to generate. In PromQL the analogous behavior comes from the query engine's lookback-delta staleness handling rather than an explicit function.

#### Q63. [Theory] Explain how a Prometheus histogram lets you compute aggregatable quantiles, and why a summary cannot.

A **histogram** exposes a set of **cumulative bucket counters** (`_bucket{le="0.1"}`, `le="0.5"`, `le="1"`, ... plus `_sum` and `_count`). Each bucket counts how many observations were ≤ that boundary. Because these are plain counters, they are **additive**: you can `sum by (le)` the bucket counts across many instances and the result is still a valid histogram of the combined population. `histogram_quantile()` then interpolates the requested quantile from those merged cumulative buckets.

A **summary** instead computes quantiles **client-side**, per instance, and exposes them as pre-baked numbers (`quantile="0.99"`). You cannot average or sum p99s from different instances to get the fleet p99 — quantiles aren't additive. So a summary's quantiles are only valid for that single instance and can't be aggregated.

```
histogram: bucket counts add up → sum by (le) → histogram_quantile → fleet p99 ✓
summary:   pre-computed q99 per instance → averaging q99s is mathematically wrong ✗
```

The trade-offs: histograms need you to pick bucket boundaries in advance (mitigated by Prometheus 3.x **native/exponential histograms**, which choose buckets dynamically), while summaries give exact per-instance quantiles but no cross-instance aggregation. For anything you want to aggregate, use a histogram.

#### Q64. [Theory] What is series churn, and why can it be worse than raw high cardinality?

**Series churn** is the rate at which series are *created and abandoned* over time, as opposed to the static count of currently-active series. A canonical cause is putting a value that changes on every deploy into a label — a Kubernetes pod name, a container ID, a build hash. Each deploy retires the old series and creates new ones.

Why churn can hurt more than a high but *stable* cardinality:
- **The index accumulates dead series.** Even after a series stops receiving samples, its entries linger in the inverted index and in-memory structures until compaction/head truncation reclaims them. High churn means the index holds far more series than are currently active.
- **Memory is sized for the *sum over the retention/head window*, not the instantaneous count.** If you churn 50k series every deploy and deploy hourly, the head block may track millions of series across its window even though only 50k are live at any instant.
- **Compaction pressure.** Constantly creating and dropping series increases the merge/cleanup work the background compactor must do.

The fix mirrors the cardinality fix but emphasizes *stability*: don't label with values that rotate (pod names, ephemeral IDs); prefer stable identifiers (deployment, service, node) and let the volatile identity live in logs/traces or as exemplars.

#### Q65. [Practical] Write a query to detect anomalies as values that deviate more than 3 standard deviations from a rolling mean.

A simple, explainable anomaly detector flags points far from their recent rolling statistics. Using window functions over bucketed data:

```sql
WITH stats AS (
    SELECT
        bucket,
        host,
        avg_usage,
        avg(avg_usage) OVER w  AS roll_mean,
        stddev_samp(avg_usage) OVER w AS roll_std
    FROM cpu_5min
    WINDOW w AS (
        PARTITION BY host
        ORDER BY bucket
        ROWS BETWEEN 19 PRECEDING AND 1 PRECEDING   -- trailing window, excludes current point
    )
)
SELECT
    bucket, host, avg_usage, roll_mean, roll_std,
    (avg_usage - roll_mean) / NULLIF(roll_std, 0) AS z_score
FROM stats
WHERE roll_std IS NOT NULL
  AND abs(avg_usage - roll_mean) > 3 * roll_std
ORDER BY bucket;
```

The window covers the 19 buckets *before* the current one (`1 PRECEDING`, not `CURRENT ROW`) so the current point doesn't contaminate the baseline it's being compared against. The **z-score** `(value − mean) / std` measures how many standard deviations away the point is; we flag `|deviation| > 3·std`. `NULLIF(roll_std, 0)` guards against division by zero on flat windows. This is a causal (trailing-window) detector suitable for alerting; for seasonal data you'd compare against the same hour last week instead.

#### Q66. [Theory] How does retention interact with compression and continuous aggregates? What's the ordering that avoids waste?

These three background features must be ordered carefully or you do redundant work:

1. **Continuous aggregates (rollups) should be materialized *before* the raw data is dropped.** If retention deletes raw chunks before the rollup has consumed them, those buckets are lost forever. So the raw retention window must be *longer* than the rollup's refresh lag.
2. **Compression should happen *after* a chunk is settled but can happen before retention.** Compressing a chunk you're about to drop is wasted CPU, so compression policies typically target an age band that's old enough to be immutable but young enough to still be queried (e.g., compress at 2 days, retain raw to 7 days).
3. **Each rollup tier has its own retention.** Raw is dropped soonest; coarser tiers live longest. The rollup-then-drop ordering applies at every tier boundary.

A sane policy set:

```
raw:    compress @ 2d, drop @ 7d     (rollup to 1m must run before 7d)
1m agg: compress @ 14d, drop @ 90d   (rollup to 1h must run before 90d)
1h agg: drop @ 2y
```

The invariant: **never drop a tier's data until the next coarser tier has fully consumed it, and never compress what you're about to delete.** Getting the offsets wrong either loses data (retention too aggressive) or wastes CPU (compressing doomed chunks).

#### Q67. [Theory] What is the role of `segmentby` and `orderby` in columnar compression, and how do you choose them?

When TimescaleDB compresses a chunk, it transposes rows into per-column arrays grouped into compressed batches. Two settings control how:

- **`segmentby`** chooses the column(s) whose values define a compression *segment*. Rows sharing a `segmentby` value (e.g., all rows for `host='web1'`) are grouped together, and that value is stored *once* per segment instead of per row. It also lets queries that filter on the `segmentby` column skip irrelevant segments without decompressing. Choose `segmentby` to match the column you most often **filter/group by** and that has *moderate* cardinality.
- **`orderby`** sets the sort order of rows *within* a segment, typically `ts DESC`. Good ordering clusters similar/adjacent values so delta-of-delta and XOR encodings see smooth runs, maximizing the compression ratio and improving locality for time-range scans.

Choosing them:
- `segmentby` should be a low-to-moderate cardinality label you filter on (`device_id`, `host`, `service`). Too high a cardinality creates tiny one-row segments that compress poorly; too low (everything in one segment) loses the skip-segment benefit.
- `orderby` is almost always the time column descending so recent data and smooth value runs sit together.

Get these wrong (e.g., `segmentby` on a high-cardinality field) and you can *lose* the compression benefit entirely — it's a real tuning knob, not a default to ignore.

#### Q68. [Practical] Write a PromQL query that computes the ratio of errors to total requests (an SLI), guarding against divide-by-zero.

A service-level indicator like "error rate" is a ratio of two counters' rates. The pattern is `sum(rate(errors)) / sum(rate(total))`, but you must avoid producing `NaN`/spurious values when there's no traffic:

```promql
sum(rate(http_requests_total{status_code=~"5.."}[5m]))
  /
clamp_min(sum(rate(http_requests_total[5m])), 1)
```

`rate(...[5m])` turns each counter into a per-second rate; `sum(...)` aggregates across instances. The numerator filters to 5xx responses via the regex label matcher `status_code=~"5.."`. The denominator wraps the total in `clamp_min(..., 1)` so that when there are zero requests the divisor is 1 instead of 0, yielding 0 rather than a divide-by-zero `NaN` that would break alerts and graphs. (An alternative is the `> 0` guard with `or vector(0)`, but `clamp_min` is the compact idiom.) For a percentage you'd multiply by 100; for SLO burn-rate alerting you'd compare this ratio across multiple windows.

#### Q82. [Practical] Write a query to compute the time spent in each state (e.g., how long a machine stayed "running" vs "idle") from state-change events.

When a series records *state transitions* (one row each time the state changes), the duration in each state is the gap until the *next* event for that machine — a classic `lead()` window pattern:

```sql
WITH durations AS (
    SELECT
        machine_id,
        state,
        ts AS started_at,
        lead(ts) OVER (PARTITION BY machine_id ORDER BY ts) AS ended_at
    FROM machine_state_events
    WHERE ts >= now() - interval '1 day'
)
SELECT
    machine_id,
    state,
    sum(
        EXTRACT(EPOCH FROM (COALESCE(ended_at, now()) - started_at))
    ) AS seconds_in_state
FROM durations
GROUP BY machine_id, state
ORDER BY machine_id, seconds_in_state DESC;
```

`lead(ts)` fetches the timestamp of the *next* state change for that machine, so `ended_at - started_at` is how long the current state lasted. `COALESCE(ended_at, now())` handles the still-open final interval (the latest state has no following event, so it runs until "now"). Summing per `(machine_id, state)` gives total time in each state — the basis for utilization, uptime, and SLA reporting from event-style time-series data.

### 🟠 — extended

#### Q69. [Theory] Explain "merge-on-read" vs "merge-on-write" for handling overlapping/late data, with their trade-offs.

When late or overlapping data lands in a region that already has stored data, the engine must reconcile duplicates and ordering. Two strategies:

- **Merge-on-write (compaction-time merge).** When new data arrives, the engine rewrites the affected on-disk segment so the stored block is always clean, sorted, and deduplicated. Reads are then simple and fast — they scan a single, ordered block. The cost is paid on the *write/compaction* path: late data into a compressed chunk triggers decompress → merge → recompress, which is expensive and amplifies writes.
- **Merge-on-read (query-time merge).** New/late data is written to a separate overlay (a new small block, an out-of-order head), and the *reader* merges the base block with the overlays at query time, resolving duplicates on the fly. Writes stay cheap (just append the overlay), but reads get slower and more complex because every query must merge potentially many overlapping fragments, until background compaction folds them in.

```
merge-on-write:  write pays (rewrite block) → read is simple/fast
merge-on-read:   write is cheap (append overlay) → read pays (merge fragments)
```

The trade-off is the classic write-vs-read amplification choice. TSDBs often blend them: accept out-of-order samples cheaply into a head/overlay (merge-on-read in the short term), then compaction periodically rewrites to a clean sorted block (merge-on-write eventually), bounding read-side fragment count.

#### Q70. [Theory] How do native/exponential histograms in Prometheus improve on classic fixed-bucket histograms internally?

Classic Prometheus histograms require you to **pre-declare bucket boundaries** (`le` values). This forces a guess: too few/wrong buckets and your quantile estimates are coarse or wildly wrong where the real distribution sits between boundaries; too many buckets and every histogram becomes many series, inflating cardinality.

**Native (exponential) histograms** change the representation:
- Buckets are defined by an **exponential schema** — bucket boundaries are `(1 + 2^-schema)^i`, so they grow geometrically and cover the whole range with **relative** error rather than absolute. You don't choose boundaries; a single `schema` parameter sets resolution, and the histogram auto-covers wherever the data actually lands.
- The whole histogram is stored as **one compact, structured sample** (a sparse set of populated buckets with counts) rather than one separate `_bucket` time series per boundary. This collapses what used to be N series into effectively one, dramatically cutting cardinality.
- Resolution can **adapt** — if a series produces too many populated buckets, the schema can be reduced (coarsened) automatically to bound the count.

The net wins: bounded, predictable relative error across the entire range without guessing boundaries, far lower series cardinality, and cheaper aggregation. The cost is a more complex sample type and storage/encoding path, and ecosystem tooling (exporters, query features) had to catch up — which is largely why it shipped as a major Prometheus 3.x capability.

#### Q71. [Practical] Design a query/storage approach to compute the p99 latency over 90 days from data stored at multiple resolutions. Show the rollup-merge query.

You cannot store a single "p99" per bucket and average them (percentiles aren't decomposable), so you store a **mergeable sketch** (t-digest) per bucket at each tier and merge sketches at query time.

Storage: a continuous aggregate per tier keeps a t-digest, not a number:

```sql
-- hourly tier stores a mergeable sketch, refreshed incrementally
CREATE MATERIALIZED VIEW latency_1h WITH (timescaledb.continuous) AS
SELECT time_bucket('1 hour', ts) AS bucket,
       service,
       percentile_agg(latency_ms) AS sketch     -- t-digest, mergeable
FROM requests
GROUP BY bucket, service;
```

Query for the 90-day p99: merge all the hourly sketches in range, then read the quantile off the merged sketch — no raw data scanned:

```sql
SELECT
    service,
    approx_percentile(0.99, rollup(sketch)) AS p99_90d,
    approx_percentile(0.50, rollup(sketch)) AS p50_90d
FROM latency_1h
WHERE bucket >= now() - interval '90 days'
GROUP BY service;
```

`rollup(sketch)` merges the per-hour t-digests into one combined distribution (the operation that makes this correct), and `approx_percentile` reads any quantile off the merged sketch. Because each hour is already summarized, a 90-day query touches ~2,160 sketch rows per service instead of billions of raw points — fast *and* accurate. If you'd stored raw p99 per hour and averaged, the answer would be silently wrong.

#### Q72. [Theory] What is "downsampling alignment" and how do misaligned buckets across tiers cause incorrect rollups?

**Downsampling alignment** means that each coarser tier's buckets must be exact unions of the finer tier's buckets, anchored to the same epoch origin. A 1-hour bucket must contain exactly the twelve 5-minute buckets that fall within it, with the same start instant — `[10:00, 11:00)` = the 5-minute buckets `[10:00,10:05) … [10:55,11:00)`.

Misalignment breaks rollups in subtle ways:
- **Boundary phase offset.** If the 5-minute tier is bucketed from `:00` but the hourly tier is anchored from `:30`, an hourly bucket straddles two different sets of 5-minute buckets, double-counting some and missing others when you try to re-aggregate from the finer tier.
- **Timezone/DST drift.** Bucketing by local time means a DST transition makes a "day" 23 or 25 hours, so daily buckets no longer tile cleanly over hourly ones. Always bucket internal tiers in UTC and convert only for display.
- **Origin mismatch.** `time_bucket` lets you set an origin; if two tiers use different origins, their grids don't nest, and `sum(n)`/`sum(sum_usage)` across the finer tier won't reconstruct the coarser bucket.

The rule: pick a single epoch origin (UTC, e.g., the Unix epoch), make every tier's interval a clean multiple of the finer tier's, and never bucket internal storage in a local/DST-affected timezone. Aligned grids let `sum`, `min`, `max`, and merged sketches re-aggregate exactly from one tier to the next.

#### Q73. [Theory] Walk through what physically happens when a late point arrives into an already-compressed chunk.

Compressed chunks are immutable columnar batches, so a late write that belongs in one cannot simply be appended. The sequence (TimescaleDB-style, but representative):

1. **Locate the target chunk** by the point's event-time — chunk exclusion in reverse, finding which sealed chunk owns that interval.
2. **Decompress the affected segment(s).** The engine expands the relevant compressed batch back into row form (only the `segmentby` group(s) the new point touches, not necessarily the whole chunk).
3. **Insert the row** into the now-uncompressed segment, re-establishing sort order by the `orderby` key.
4. **Recompress** the segment, regenerating delta-of-delta/XOR/dictionary encodings over the new contents.
5. **Invalidate dependent rollups.** Any continuous aggregate bucket covering that timestamp is marked in an **invalidation log** so the next refresh recomputes exactly those buckets — not the whole rollup.

This is why late data into compressed history is costly: a single out-of-order point can force decompress → reinsert → recompress of a segment plus rollup re-materialization. The operational mitigations: keep a recent window *uncompressed* sized to your expected lateness, batch backfills, and defer/refresh rollups once over the loaded range rather than per point. Newer engine versions optimize this (e.g., staging late data and merging in bulk), but the fundamental cost — you must rewrite an immutable block — remains.

#### Q74. [Practical] Write a query that buckets data into uneven, business-meaningful windows (e.g., per calendar month) rather than fixed intervals.

Fixed intervals (`time_bucket('30 days', ...)`) don't align to calendar months, which have 28–31 days. For calendar-aware bucketing TimescaleDB offers `time_bucket` with month/year intervals (and a timezone), since months aren't a fixed number of seconds:

```sql
SELECT
    time_bucket('1 month', ts, timezone => 'UTC') AS month,
    service,
    sum(requests)                                  AS total_requests,
    avg(latency_ms)                                AS avg_latency
FROM traffic
WHERE ts >= date_trunc('year', now())
GROUP BY month, service
ORDER BY month, service;
```

Passing a `month`/`year` interval makes `time_bucket` snap to true calendar boundaries (the 1st of each month) instead of counting a flat 2,592,000 seconds, and the `timezone` argument anchors those boundaries to the right local midnight (critical for "monthly" billing reports that must match a calendar). On vanilla Postgres you'd use `date_trunc('month', ts)` for the same effect. The takeaway: calendar periods are *not* fixed-width, so use calendar-aware bucketing for billing/reporting windows and reserve fixed intervals for uniform technical metrics.

#### Q75. [Theory] How do "real-time aggregates" reconcile a materialized rollup with the freshest, not-yet-materialized data?

Continuous aggregates lag reality by their refresh interval, so a naive query of the rollup misses the newest data (everything since the last materialization). **Real-time aggregates** fix this by **unioning two parts at query time**:

1. The **materialized portion** — pre-computed buckets read cheaply from the continuous aggregate's storage, covering up to the materialization watermark.
2. A **live tail** — the buckets *after* the watermark are computed on the fly from the raw hypertable, then appended.

```
│◄──── materialized (read from rollup) ────►│◄ live tail (compute from raw) ►│
                                       watermark                          now
```

The query planner rewrites a `SELECT` against the continuous aggregate into `materialized_part UNION ALL raw_aggregation_after_watermark`, so you get both *fast historical* and *fresh recent* results from one query, without the dashboard showing a gap at the right edge.

Trade-offs and gotchas:
- The live tail scans raw data, so if the watermark falls far behind (refresh job stalled), that tail grows and the query slows toward a raw scan — a stalled refresh job is a common cause of a suddenly-slow "should be cheap" rollup query.
- It can be disabled (`materialized_only = true`) when you specifically want only settled, immutable results (e.g., for billing) and don't want the partial newest bucket.

#### Q76. [Theory] Compare quorum-replication TSDBs with the "run two identical replicas and dedup at read" model Prometheus uses. When is each appropriate?

Two HA philosophies:

- **Quorum replication (clustered TSDB).** Each shard is replicated to N nodes; a write is acknowledged once a quorum persists it, and reads can require a quorum to guarantee they see the latest acked write. This gives stronger, tunable consistency and automatic failover within the cluster (VictoriaMetrics cluster, Mimir/Cortex with replication factor). Appropriate when you want a single managed storage cluster with defined durability/consistency guarantees and central control of replication.

- **Two identical replicas + read-time dedup (Prometheus pattern).** You simply run two independent Prometheus servers scraping the *same* targets. Each is a complete, standalone copy. A query layer (Thanos Querier, vmselect, Grafana with two datasources) **deduplicates** overlapping samples at read time. There is no write coordination at all — the redundancy is end-to-end, including the collection path.

When each fits:
- The **two-replica** model shines for *collection-path* resilience: because each replica scrapes independently, a network partition or a crashed scraper only affects one copy, and there's nothing to coordinate. It tolerates the messy reality that scrapes from two servers won't be perfectly time-aligned — dedup handles that. It's operationally dead-simple and matches Prometheus's "each server is self-contained" design.
- **Quorum replication** fits when you've centralized storage (remote-write into a cluster) and want defined durability/consistency and capacity scaling beyond one node, accepting the complexity of a distributed storage layer.

The deep point: monitoring tolerates *eventual, best-effort* consistency, so Prometheus pushes redundancy all the way to independent collectors and resolves conflicts at read time — cheaper and more robust against collection-path failures than trying to make distributed writes strongly consistent. Large deployments often combine both: redundant Prometheis remote-writing into a replicated cluster.

### 🔴 — extended

#### Q77. [Theory] Derive why delta-of-delta plus XOR encoding approaches the information-theoretic limit for regular telemetry, and where it fails.

Compression effectiveness is bounded by the **entropy** (true information content) of the stream. Regular telemetry has very low entropy, and the Gorilla encodings are designed to spend bits proportional to that entropy:

- **Timestamps.** A stream sampled every `T` seconds has almost no information per timestamp — given the previous two, the next is *predictable*. Delta-of-delta encodes the *deviation from the predicted regular spacing*, which for a perfectly periodic stream is 0 and costs ~1 bit (a "no change" flag). You're spending bits only on the *surprise* (cadence changes), which is exactly what entropy says is the irreducible content. Hence ~1 bit/timestamp vs 64 bits raw.
- **Values.** Slowly-varying floats differ from the previous value in only a few bits. XOR isolates exactly the changed bit-region (leading zeros + meaningful block + trailing zeros), so you encode roughly the number of *bits that actually changed* plus small framing — again proportional to the per-sample surprise. A constant value XORs to zero → 1 bit.

So for low-entropy regular telemetry the scheme spends ≈ (entropy) bits/sample, which is why Gorilla reached ~1.37 bytes per 16-byte point — close to optimal *for that data*.

Where it fails (high-entropy inputs):
- **Irregular timestamps** (event-driven, jittery arrival) make delta-of-delta nonzero constantly, so timestamps stop compressing.
- **Noisy/rapidly-varying values** (true randomness, high-frequency signals, encrypted/already-compressed payloads) have high per-sample entropy; XOR has many meaningful bits and little gain — you can even *expand* near-random data.
- **Frequent counter resets or step changes** break the "previous predicts next" assumption, spiking the encoded size.

The principle: these encodings are *predictive delta coders* tuned for the low-entropy regular case; their ratio degrades gracefully toward 1:1 as the input's entropy rises, and no encoder can beat entropy.

#### Q78. [Theory] Design the index structure for a TSDB that must support both low-latency label lookups and bounded memory at 100M+ active series.

At 100M+ series the inverted index can't naively live fully in RAM, yet label queries must stay fast. A layered design:

1. **Two-level inverted index, mostly on disk.** Persist postings lists (label=value → sorted series IDs) on disk in immutable, compressed segments (like Prometheus's per-block index or Influx's TSI). Keep only **hot** structures in memory: a small in-memory head index for the recent (writable) block, plus caches.
2. **Compressed postings.** Store series-ID postings as delta-encoded, bit-packed integers (e.g., delta + varint, or roaring bitmaps) so intersection/union is fast and lists are small. Roaring bitmaps give near-RAM-speed boolean ops while staying compact on disk.
3. **Symbol/dictionary table.** Intern label strings into integer IDs once; the index references integers, not repeated strings, slashing memory for repetitive label values.
4. **Sharding by series hash.** Partition the whole series space across nodes by `hash(series key)` so each node's index covers only its shard — 100M series across 20 nodes is 5M each, a tractable per-node index.
5. **Per-segment min/max time + bloom/range pruning.** Tag each index segment with its time span so time-bounded queries skip segments (the index analog of chunk exclusion), and use Bloom filters for "does this segment contain label X" to avoid loading cold postings.
6. **Memory-map + page cache, not heap.** Mmap the on-disk index so the OS page cache holds hot postings and cold ones are evicted automatically, bounding RSS without manual cache management.
7. **Churn control via head truncation/compaction.** Periodically drop dead series from the index so memory tracks *active*, not *ever-seen*, series.

```
query labels → shard(s) by hash → per-shard: prune segments by time
   → load (mmap/cache) compressed postings (roaring) → intersect → series IDs
   → fetch column chunks for those series in the time range
```

The governing trade-offs: keep the index **integer-interned, compressed, time-pruned, mmap-backed, and sharded** so memory tracks active series per node rather than global ever-seen series — that's how systems like VictoriaMetrics sustain very high cardinality with bounded RAM.

#### Q79. [Theory] How would you architect a TSDB on object storage (S3) with separated compute and storage, and what new problems does that create?

Decoupling compute from storage (the InfluxDB 3.x / Mimir / modern-lakehouse direction) means durable data lives in **object storage (S3)** as immutable columnar files (Parquet), and stateless compute nodes read/write them. Architecture:

- **Ingesters** buffer recent writes in memory + local WAL, then periodically flush sorted, compressed **Parquet files** to S3 and register them in a **catalog** (metadata DB) that maps `(table, series/partition, time range) → file + statistics`.
- **Queriers** are stateless: given a query, consult the catalog to find the relevant Parquet files by time/partition pruning, fetch only the needed columns/row-groups from S3 (using Parquet footers + predicate pushdown), and execute (DataFusion/Arrow).
- **Compactor** runs in the background, merging many small ingester files into larger, better-sorted, better-compressed Parquet files and updating the catalog — the cloud analog of LSM compaction.
- **Caching tier** (local SSD / memory on queriers) holds hot files/row-groups so common queries don't re-fetch from S3.

New problems this creates:
- **Object-store latency & cost.** S3 GETs are tens of milliseconds and *billed per request*; naive small reads are slow and expensive. You must use large row-groups, column/row-group pruning, request coalescing, and aggressive caching to hide latency.
- **No in-place update.** Objects are immutable; late data and compaction mean writing new files and reconciling overlaps (merge-on-read across file versions until the compactor folds them), plus catalog consistency.
- **Catalog becomes critical (and a bottleneck).** The metadata DB must scale with file count and stay consistent; file-listing and pruning happen here, so it needs careful indexing and can become the limiting resource.
- **Recent-data freshness.** Data not yet flushed lives only in ingesters; queriers must query *both* ingesters (hot, un-flushed) and S3 (cold) and merge — a hot/cold split with its own consistency edge.
- **Compaction lag = read amplification.** If the compactor falls behind, queriers face thousands of tiny files to merge-on-read, degrading latency — so compactor health is now a first-class SLO.

The payoff is elastic, cheap, durable storage and independently scalable compute; the price is engineering around object-store latency/cost, an immutable-file update model, and a metadata catalog that becomes central to performance.

#### Q80. [Behavioral] You're the architect; the team wants to put a high-cardinality dimension (per-customer ID, 2M values) into metric labels for a new dashboard. How do you handle it?

(Use judgment, stakeholder empathy, and a concrete alternative — not a flat "no.")

A strong answer frames it as understanding the *need*, then redirecting to a design that meets it without detonating the TSDB:

"First I'd dig into the actual requirement: do they need *per-customer time series* (a graph per customer), or do they need to *occasionally drill into one customer*? Those have very different solutions, and the ask is usually the latter dressed as the former.

I'd explain the cost concretely, in their terms: 2M customer IDs × the other label combinations could mean tens of millions of new series, which translates to specific RAM, ingest-slowdown, and on-call-risk numbers on our current cluster — I'd show the cardinality math and ideally a quick load test, so it's data, not dogma.

Then I'd offer alternatives that actually serve the goal:
- **Keep metrics aggregatable** (per region/plan/tier) and put the high-cardinality per-customer detail in a **column store / events backend** (ClickHouse) or logs, correlated by time + low-card labels, with a drill-down link from the dashboard.
- Use **exemplars** to jump from a spiking aggregate metric to a representative per-customer trace.
- If a *bounded* set of top-N customers genuinely needs first-class metrics, label only those (e.g., top 50 by revenue) and bucket the rest as `other`.
- If true per-customer metrics at this scale are a hard requirement, that's a different system (high-cardinality store / per-tenant sharding) and a budget conversation, which I'd surface to leadership with the cost trade-off rather than quietly absorbing the risk.

I'd make the team a partner: agree on the real user story, show the cost, and pick the option that meets it. The outcome I want is the dashboard they need *and* a monitoring system that's still standing — and a documented decision so the next person understands why per-customer isn't a label."

What an interviewer listens for: you didn't just say no, you quantified the risk, distinguished the real requirement from the proposed implementation, offered concrete alternatives (exemplars, separate store, top-N), and escalated the genuine trade-off to stakeholders instead of silently accepting unbounded cardinality.

## 🧩 Extended Questions — Set 2: Practical scenarios, troubleshooting & coding

### 🟢 — extended

#### Q83. [Practical] You run `SELECT avg(usage) FROM cpu_metrics WHERE host = 'web1'` with no time predicate and it's painfully slow. What's wrong and how do you fix it?

Omitting a time predicate defeats the single most important TSDB optimization: **chunk exclusion**. Without a `WHERE ts >= ...` bound the planner cannot prune chunks, so it scans every chunk/block across the entire retention window — potentially years of data — and decompresses all of it.

The fix is almost always to **add a bounded time range** so only the relevant chunks are opened:

```sql
SELECT avg(usage)
FROM cpu_metrics
WHERE host = 'web1'
  AND ts >= now() - interval '1 hour';
```

Rule for dashboards and ad-hoc queries alike: **always bound by time first.** If you genuinely need an all-time aggregate, compute it from a long-retention rollup (e.g., a daily continuous aggregate) instead of scanning raw data. In Prometheus the analog is querying a counter over an unbounded `[...]` range or a huge range vector — keep ranges as narrow as the question allows.

#### Q84. [Practical] Your line-protocol writes to InfluxDB are returning HTTP 400. List the most common causes and how to debug them.

A 400 from the write endpoint means the *payload* is malformed (a 401/403 is auth, a 503 is overload — different problems). The usual line-protocol culprits:

- **Unescaped spaces or commas** in a measurement/tag/field key — spaces and commas are structural separators and must be backslash-escaped inside identifiers.
- **A field that changes type** between writes — writing `temperature=82` (int) then `temperature=82.0` (float) to the same field causes a type conflict.
- **Quoting mistakes** — string field values must be double-quoted (`status="ok"`); tag values must *not* be quoted; numeric fields must not be quoted.
- **Bad timestamp precision** — sending millisecond timestamps while the endpoint expects nanoseconds (or vice versa) yields wildly wrong times or rejects.
- **Trailing whitespace / empty lines** in the batch.

Debug by writing a *single* line with `curl`, reading the error body (InfluxDB returns the offending line and reason), then bisecting a failing batch. Validate types are consistent across your producers — type conflicts are the sneakiest because individual lines look fine.

#### Q85. [Coding] Write a SQL query to count how many distinct sensors reported data in each hour of the last day.

This is an "active entities per bucket" query — useful for spotting fleets dropping offline.

```sql
SELECT
    time_bucket('1 hour', ts)        AS hour,
    count(DISTINCT sensor_id)        AS active_sensors
FROM readings
WHERE ts >= now() - interval '1 day'
GROUP BY hour
ORDER BY hour;
```

`count(DISTINCT sensor_id)` gives the number of unique sensors that emitted at least one point in each hourly bucket. A sudden drop in `active_sensors` flags a gateway outage or network partition. For very large fleets where exact distinct-count is expensive, swap in an approximate sketch (`approx_count_distinct` / HyperLogLog) to trade a little accuracy for big speed gains.

#### Q86. [Practical] A teammate stored timestamps as a `text` (string) column. What breaks, and how do you migrate?

Storing time as text breaks essentially everything a TSDB is built to do:

- **No chunk exclusion** — the planner can't reason about ranges on a string, so every query full-scans.
- **No time functions** — `time_bucket`, `date_trunc`, interval math all require a real `timestamptz`.
- **Lexical, not chronological, ordering** — `'2026-1-5'` sorts before `'2026-12-1'` only by luck of formatting; `'9'` > `'10'` as strings.
- **Bloat** — text timestamps are far larger and don't get delta-of-delta compression.

Migrate by adding a real `timestamptz` column, backfilling with a parse, then swapping:

```sql
ALTER TABLE readings ADD COLUMN ts_proper timestamptz;
UPDATE readings SET ts_proper = ts_text::timestamptz;
-- validate, then drop the old column and (re)create the hypertable on ts_proper
```

Always store time as `timestamptz` (or the engine's native time type), in UTC, with timezone handled at display.

#### Q87. [Coding] Write a query to return the single latest reading per sensor (the "last point" query) using DISTINCT ON.

```sql
SELECT DISTINCT ON (sensor_id)
    sensor_id,
    ts,
    temperature
FROM readings
ORDER BY sensor_id, ts DESC;
```

`DISTINCT ON (sensor_id)` keeps the first row per `sensor_id` after ordering, and `ORDER BY sensor_id, ts DESC` makes that first row the newest one. This is the idiomatic Postgres/TimescaleDB last-point query. To bound the scan, add a `WHERE ts >= now() - interval '1 day'` so you only touch recent chunks; TimescaleDB also ships `last(temperature, ts)` as an aggregate for the same intent inside a `GROUP BY sensor_id`.

#### Q88. [Practical] Your Grafana dashboard shows a flat line at the very end of every time-series panel. What's happening?

That trailing flat/dropping segment is almost always the **last, incomplete bucket**. The current time-bucket (say the in-progress minute) has only received part of its data, so a `count` or `sum` shows artificially low and a `rate` may dip — it looks like a cliff but it's just "the minute isn't over yet."

Fixes:
- **Exclude the trailing incomplete bucket** from the query/panel, or
- Use the engine's **real-time aggregate** so the freshest partial window is computed live and labeled, and
- In Grafana, be aware of how it aligns the time range to "now" — set the panel to not render the final partial point, or shift the window to end one interval back.

The same effect at the *start* of a `rate()`/`increase()` window comes from extrapolation; the trailing artifact is the more common dashboard confusion. Recognizing "the last point is partial" prevents false alerts.

#### Q89. [Coding] Write a query that converts an irregular stream into regular 1-minute buckets, emitting NULL for minutes with no data.

You need a generated time spine left-joined to the data so empty minutes still appear:

```sql
SELECT
    g.bucket,
    avg(r.usage) AS avg_usage
FROM generate_series(
        now() - interval '1 hour',
        now(),
        interval '1 minute'
     ) AS g(bucket)
LEFT JOIN cpu_metrics r
       ON r.ts >= g.bucket
      AND r.ts <  g.bucket + interval '1 minute'
GROUP BY g.bucket
ORDER BY g.bucket;
```

`generate_series` produces every minute boundary; the `LEFT JOIN` keeps buckets with no matching rows (their `avg` is `NULL`). TimescaleDB's `time_bucket_gapfill()` does this more efficiently and adds `locf()` (last-observation-carried-forward) and `interpolate()` for filling the gaps — prefer it in production; the `generate_series` form is the portable, engine-agnostic version to know.

#### Q90. [Practical] Ingest throughput is fine but query latency is terrible right after a big write burst. What's the likely cause?

A write burst floods the **in-memory head/buffer and creates many small, uncompacted blocks/chunks**, plus possibly a backlog of WAL the engine is still flushing. Queries then have to open and merge a large number of tiny, unordered, uncompressed segments, which is slow.

Likely contributors and remedies:
- **Compaction is behind** — the background merge hasn't yet consolidated small blocks into bigger, ordered ones. Give it headroom (CPU/IO) or tune compaction settings; latency recovers as it catches up.
- **Recent chunk is uncompressed and huge** — expected for the live chunk; ensure your compression policy compresses older chunks so only the small live one is scanned raw.
- **Cache eviction** — the burst pushed hot index/series metadata out of memory; queries now hit disk. More RAM or smaller working set helps.

The pattern "writes OK, reads spike after bursts" almost always points at **compaction lag / too many small segments**, not the query itself.

#### Q91. [Coding] Write a query to compute total bytes transferred per day from a per-interval `bytes` field (a sum rollup).

```sql
SELECT
    time_bucket('1 day', ts)  AS day,
    sum(bytes)                AS total_bytes
FROM network_traffic
WHERE ts >= now() - interval '30 days'
GROUP BY day
ORDER BY day;
```

`sum` is a **decomposable** aggregate, so this same shape works at every rollup tier — `sum(sum_bytes)` of a daily rollup gives the weekly total correctly. Contrast with a *counter* (cumulative bytes-ever), where you'd need `max(bytes) - min(bytes)` per bucket (or `rate`/`increase` in PromQL) instead of `sum`, because summing a monotonically increasing counter is meaningless.

#### Q92. [Practical] You need to delete data for one specific device that should never have been ingested (a privacy/GDPR request). Why is this awkward in a TSDB, and how do you do it?

TSDBs assume **append-only, immutable** data, so targeted deletes cut against the grain:

- Data for that device is **interleaved across many compressed chunks/blocks**; deleting specific rows means decompressing, rewriting, and recompressing each affected chunk — expensive.
- Some engines implement deletes as **tombstones** that only take effect at the next compaction, so space isn't reclaimed immediately.

Practical approaches:
- **TimescaleDB:** `DELETE FROM metrics WHERE device_id = 'X'` works but may require decompressing affected chunks first; for large ranges, dropping whole chunks is cheaper if the device's data is time-isolated.
- **Prometheus:** the admin delete-series API (`/api/v1/admin/tsdb/delete_series`) plus `clean_tombstones` to actually reclaim.
- **InfluxDB:** `DELETE` predicate by tag, then compaction reclaims.

For recurring privacy requirements, design so deletable entities map to **droppable partitions** (e.g., per-tenant) and keep a record so you can prove the deletion. Mention to interviewers that "easy to append, hard to surgically delete" is a fundamental TSDB trade-off.

### 🟡 — extended

#### Q93. [Practical] A `rate()` panel shows a giant spike to an impossible value right when a service restarted. Explain and fix.

A counter resets to 0 on process restart. If a TSDB naively computed `(later - earlier)` across the reset it would see a huge *negative* jump; Prometheus' `rate()`/`increase()` instead detect the reset and treat it as a counter continuing from 0, which is correct. The "impossible spike" usually comes from one of:

- **Extrapolation at window edges** — `rate()` extrapolates to the boundaries of the range; a counter that starts mid-window (just after restart) can be extrapolated into a brief overshoot.
- **A too-short range vector** relative to the scrape interval, so a single sample reset dominates.
- **Mixing series** — `rate()` applied across instances that reset at different times, then summed incorrectly.

Fixes: widen the range vector to comfortably exceed the scrape interval (`[5m]` for a 15–30 s scrape), apply `rate()` *before* aggregating (`sum(rate(x[5m]))`, never `rate(sum(x)[5m])`), and consider clamping/`clamp_max` only for display. The deeper lesson: **`rate()` per series first, aggregate second** — the most common PromQL ordering bug.

#### Q94. [Coding] Write a query that detects when a sensor's value crosses a threshold (transitions from below to above) using window functions.

A threshold *crossing* needs the previous value, which `lag()` provides:

```sql
SELECT ts, sensor_id, temperature
FROM (
    SELECT
        ts,
        sensor_id,
        temperature,
        lag(temperature) OVER (
            PARTITION BY sensor_id ORDER BY ts
        ) AS prev_temp
    FROM readings
    WHERE ts >= now() - interval '1 hour'
) s
WHERE prev_temp <= 80      -- was below/at threshold
  AND temperature > 80     -- now above
ORDER BY sensor_id, ts;
```

`lag(...) OVER (PARTITION BY sensor_id ORDER BY ts)` pulls each row's previous reading for the same sensor; the outer filter keeps only the rows where the value was `<= 80` before and `> 80` now — the rising-edge crossings. Swap the comparisons for falling-edge detection. This is the SQL building block for "alert on transition, not on level," which avoids re-alerting every sample while the value stays high.

#### Q95. [Practical] Two of your monitoring replicas disagree on an alert — one fires, one doesn't. Why, and is that a bug?

It's usually **not a bug** — it's the nature of independently-scraping replicas. Prometheus HA typically runs two identical servers scraping the same targets. They scrape at slightly different instants, so:

- A value hovering right at the threshold can be just-above for one replica and just-below for the other at evaluation time.
- A brief target blip might be caught by one scrape and missed by the other.
- Rule evaluation happens on independent clocks, so a transient condition resolves differently.

This is why you **deduplicate alerts in Alertmanager** (it groups identical alerts from both replicas) rather than expecting bit-identical state. If the disagreement is *persistent* (not transient), then investigate: divergent configs, one replica failing scrapes, or clock skew. The takeaway: for monitoring you accept eventual/approximate agreement and dedupe downstream, instead of demanding strong consistency between replicas.

#### Q96. [Coding] Write a query to compute a session/segment boundary: group consecutive rows where the gap to the previous row exceeds 5 minutes into sessions.

This is the classic "gaps-and-islands" pattern — assign a session id that increments whenever the gap is too large:

```sql
SELECT
    user_id,
    ts,
    sum(new_session) OVER (
        PARTITION BY user_id ORDER BY ts
    ) AS session_id
FROM (
    SELECT
        user_id,
        ts,
        CASE
            WHEN ts - lag(ts) OVER (PARTITION BY user_id ORDER BY ts)
                 > interval '5 minutes'
              OR lag(ts) OVER (PARTITION BY user_id ORDER BY ts) IS NULL
            THEN 1 ELSE 0
        END AS new_session
    FROM events
    WHERE ts >= now() - interval '7 days'
) marked
ORDER BY user_id, ts;
```

The inner query sets `new_session = 1` whenever the gap from the previous event exceeds 5 minutes (or it's the user's first event); the outer running `sum()` turns those flags into monotonically increasing session ids per user. From `session_id` you can then aggregate session duration, event counts, etc. This pattern generalizes to any "break a stream into segments on a gap/condition" problem.

#### Q97. [Practical] After enabling compression in TimescaleDB, some `UPDATE`s on recent data started failing or got slow. Why?

Compression turns a row chunk into an **immutable columnar** representation. Historically, direct `UPDATE`/`DELETE` on a compressed chunk was disallowed; newer TimescaleDB versions support it but must **decompress the affected segment, apply the change, and recompress** — which is slow and can lock.

If your "recent" data is still being mutated (corrections, late upserts), you compressed it **too early**. The fix:

- **Push the compression boundary out** so only settled data compresses: `add_compression_policy('metrics', INTERVAL '7 days')` instead of, say, 1 hour, if you routinely amend data up to a few days old.
- Route corrections so they land in the still-uncompressed live chunk.
- If you must mutate compressed data, batch it and accept the decompress/recompress cost.

General principle: **compress old, settled chunks; keep the actively-mutated window uncompressed.** Compression and frequent in-place mutation are fundamentally at odds.

#### Q98. [Coding] Write a query for the busiest hour of the day on average (which hour sees the most traffic), across the last 30 days.

Extract hour-of-day and average across days:

```sql
SELECT
    extract(hour FROM ts)  AS hour_of_day,
    avg(hourly_requests)   AS avg_requests
FROM (
    SELECT
        time_bucket('1 hour', ts) AS ts,
        count(*)                  AS hourly_requests
    FROM requests
    WHERE ts >= now() - interval '30 days'
    GROUP BY 1
) hourly
GROUP BY hour_of_day
ORDER BY avg_requests DESC;
```

The inner query collapses raw events into per-hour counts; the outer query groups those by *hour-of-day* (0–23) and averages, revealing the daily traffic shape. Ordering by `avg_requests DESC` puts the peak hour first — useful for capacity planning and scheduling maintenance windows in the trough. Mind timezones: `extract(hour ...)` uses the session timezone, so set it explicitly if "peak local hour" matters.

#### Q99. [Practical] Your continuous aggregate shows wrong numbers for a window where you backfilled old data. What went wrong and how do you fix it?

Backfilling writes into time ranges the continuous aggregate has **already materialized**. Whether the rollup self-corrects depends on the **invalidation/refresh** mechanism:

- TimescaleDB records an **invalidation log** when underlying data in a materialized range changes, and the next refresh recomputes those buckets — but only if the refresh policy's window actually covers that old range. A policy that only refreshes "the last 3 hours" will never revisit data you backfilled into last month.

Fix: **explicitly refresh the affected historical range** after a backfill:

```sql
CALL refresh_continuous_aggregate('cpu_hourly', '2025-01-01', '2025-02-01');
```

For Prometheus recording rules there's no retroactive recompute at all — recorded series only reflect data present at evaluation time, so backfilled raw data won't appear in already-recorded series. The general rule: **after backfilling, manually re-materialize the rollup ranges you touched**; don't assume the periodic policy will reach back that far.

#### Q100. [Coding] Write a PromQL query that alerts when the 5-minute error ratio exceeds 5%, but only when there's meaningful traffic (at least 1 req/s).

```promql
(
  sum(rate(http_requests_total{status=~"5.."}[5m]))
  /
  sum(rate(http_requests_total[5m]))
) > 0.05
and
sum(rate(http_requests_total[5m])) > 1
```

The first parenthesized term is the error ratio (5xx rate over total rate). The `and` clause is the **traffic guard**: it keeps the alert only when total request rate exceeds 1/s, so a single error during a quiet period (1 error / 2 requests = 50%) doesn't page anyone. `and` in PromQL is a set intersection on matching label sets, so both conditions must hold for the same series. Guarding ratios against low-denominator noise is essential — without it, error-rate alerts are notoriously flappy at low traffic.

#### Q101. [Practical] A query joining your metrics hypertable to a `devices` metadata table is slow. How do you speed it up?

Joining a huge time-partitioned hypertable to a small dimension table is common (enrich readings with device location/type). Slowness usually comes from scanning too much of the hypertable or a poor join plan.

Tactics:
- **Filter the hypertable by time first** so chunk exclusion runs *before* the join — push `WHERE ts >= ...` down, don't let the join force a full scan.
- **Aggregate then join** — reduce the hypertable to per-device rollups, then join the small result to `devices`, rather than joining billions of raw rows.
- Ensure `devices` is small/cached and the join key (`device_id`) is indexed on both sides; a hash join on a small dimension table is ideal.
- Consider a **continuous aggregate** that already groups by `device_id`, so the join hits a compact rollup.

```sql
SELECT d.location, m.avg_usage
FROM (
    SELECT device_id, avg(usage) AS avg_usage
    FROM cpu_metrics
    WHERE ts >= now() - interval '1 day'
    GROUP BY device_id
) m
JOIN devices d ON d.device_id = m.device_id;
```

The principle: **shrink the time-series side (prune by time, pre-aggregate) before joining the dimension table.**

#### Q102. [Coding] Write a query to find the top 5 noisiest series by sample count in the last hour — a cardinality/volume triage query.

When ingest is too high, find who's responsible:

```sql
SELECT
    sensor_id,
    count(*) AS sample_count
FROM readings
WHERE ts >= now() - interval '1 hour'
GROUP BY sensor_id
ORDER BY sample_count DESC
LIMIT 5;
```

This surfaces the chattiest sensors so you can investigate a misconfigured high-frequency reporter. The Prometheus analog for *cardinality* (not volume) triage is `topk(5, count by (__name__)({__name__=~".+"}))` to find the metrics with the most series, plus the `/api/v1/status/tsdb` endpoint for top label-value cardinalities. Knowing both the volume query (samples) and the cardinality query (series) is what lets you tell apart "one sensor writing too often" from "a label exploding into millions of series."

### 🟠 — extended

#### Q103. [Practical] Design an end-to-end runbook for diagnosing "ingestion is dropping points" in a high-throughput TSDB. Where do you look, in order?

Drops can happen anywhere from client to disk; work the path systematically:

1. **Client side** — are producers getting write errors (429/503/timeouts) and *silently discarding* on failure? Check client retry/buffer metrics first; many drops are client-side give-ups.
2. **Network / load balancer** — connection limits, request-size caps cutting batches, LB timeouts shorter than write latency.
3. **Ingest gateway / queue** — if Kafka fronts the TSDB, check consumer lag and whether the topic is dropping due to retention while consumers fall behind.
4. **TSDB write path** — WAL/disk full, head block back-pressure, rate limits / per-tenant ingest caps being hit, out-of-order rejections (points older than the allowed window).
5. **Cardinality limits** — new series being rejected because a head-series limit is reached (looks like "some points drop").
6. **Resource saturation** — CPU pinned by compaction, IO saturated, OOM killer restarting the process (gaps around restarts).

Confirm with the engine's own metrics (dropped/rejected counters, WAL fsync latency, append failures) and correlate the *time* of drops with deploys, traffic spikes, and compaction cycles. The discipline: **follow the data path end to end and use the system's self-metrics rather than guessing.**

#### Q104. [Practical] You must reduce TSDB storage cost by 60% without losing the ability to answer year-long dashboards. What levers do you pull and in what order?

Order levers by impact-per-effort:

1. **Verify compression is on and effective** — uncompressed old chunks are the biggest easy win (10–20×). Check the compression ratio per chunk; fix `segmentby`/`orderby` if it's poor.
2. **Right-size retention per tier** — are you keeping raw 1 s data for a year when nobody queries below 1 h past 30 days? Drop raw retention hard; that's often the bulk of the bytes.
3. **Add/strengthen downsampling** — materialize 1 m / 1 h / 1 d rollups with decomposable aggregates so year-long dashboards read tiny rollups, then expire the underlying raw.
4. **Attack cardinality** — dead/churned series and accidental high-cardinality labels inflate the index and sample count; drop or relabel them.
5. **Tier to cheaper storage** — move old, compressed chunks to object storage / cheaper tablespaces.
6. **Drop unused metrics** — audit what's actually queried; stop ingesting metrics no dashboard or alert uses.

The structural answer is **tiered downsampling + aggressive raw retention + compression**: keep decomposable rollups for the long horizons and let raw data age out fast. You preserve every dashboard while collapsing the byte count, because year-long views never needed second-resolution data.

#### Q105. [Coding] Write the rollup-merge query to compute a correct weighted average and min/max for a day from pre-aggregated hourly rollups, then explain why each aggregate combines the way it does.

```sql
SELECT
    time_bucket('1 day', bucket) AS day,
    host,
    sum(sum_usage) / sum(n)      AS avg_usage,   -- weighted mean
    min(min_usage)               AS min_usage,
    max(max_usage)               AS max_usage,
    sum(n)                       AS n
FROM cpu_hourly
GROUP BY day, host
ORDER BY day, host;
```

Why each combines as it does:
- **avg** is *not* decomposable — you cannot average the hourly averages because buckets have different counts. You must carry `sum` and `count` and compute `sum(sum)/sum(n)`, which reconstructs the true weighted mean.
- **min/max** are decomposable — the min of mins is the global min; the max of maxes is the global max. They combine trivially.
- **count/sum** are decomposable — summing the partial sums/counts gives the totals.
- **percentiles** are the trap: `max(p99_hour)` is *not* the daily p99. You need a mergeable sketch (t-digest/HDR) stored per hour and merged, not the raw percentile values.

The general rule interviewers want: **store decomposable aggregates (sum, count, min, max) plus sketches for percentiles; never re-aggregate avg-of-avgs or percentile-of-percentiles.**

#### Q106. [Practical] A "p99 latency" alert is paging constantly but users report no problem. Walk through how you'd debug the alert quality itself.

Treat the *alert* as the suspect, not just the system:

- **Bucket resolution** — fixed histogram buckets that are too coarse around your real latencies make `histogram_quantile` interpolate badly, inflating p99. Check bucket boundaries vs actual latency distribution; consider native/exponential histograms.
- **Low traffic / small denominator** — at low request counts a single slow request swings p99 wildly. Add a traffic guard so the alert only evaluates with meaningful volume.
- **Aggregation bug** — dropping the `le` label or computing `histogram_quantile` after summing incorrectly produces nonsense quantiles. Verify `sum by (le) (rate(..._bucket[5m]))` ordering.
- **Window too short** — a 1 m window makes p99 jumpy; widen to 5 m for a stabler signal.
- **Wrong SLO target** — maybe p99 is just genuinely noisy and you should alert on a *burn rate* over a longer window or on p99 *sustained* for N minutes (`for:` clause), not instantaneous.

The fix is usually **multi-window burn-rate alerting** (fast-burn + slow-burn) with proper buckets and a traffic guard, rather than a raw instantaneous-p99 threshold. Good alerting questions test whether you distinguish "the system is unhealthy" from "the metric/alert is badly designed."

#### Q107. [Coding] Write a query to compute uptime/availability percentage over the last 30 days from `up`-style samples (1 = up, 0 = down).

```sql
SELECT
    target,
    100.0 * sum(CASE WHEN up = 1 THEN 1 ELSE 0 END) / count(*)
        AS availability_pct
FROM health_checks
WHERE ts >= now() - interval '30 days'
GROUP BY target
ORDER BY availability_pct ASC;
```

This treats each sample as an equal-weight observation: the fraction of samples where `up = 1` is the availability. Ordering ascending surfaces the worst targets first. Caveat: equal-weighting is only valid if samples are **evenly spaced**; with irregular sampling you should weight by the *duration* each state held (integrate over time) instead of counting samples — otherwise a burst of close-together "up" samples biases the number. The PromQL analog is `avg_over_time(up[30d])` (which gives the fraction up directly), and for SLOs you'd often use `1 - (sum(increase(slo_errors[30d])) / sum(increase(slo_total[30d])))`.

#### Q108. [Practical] Your TSDB cluster has one node far hotter than the others (CPU, ingest, disk). Diagnose the imbalance and fix it.

A hot node means data or load isn't spread evenly. Likely causes:

- **Skewed partitioning key** — if you space-partition by `hash(device_id)` but a handful of devices emit the overwhelming majority of points, those hash partitions are hot. Or a low-cardinality space key concentrates series on few nodes.
- **Time-based hotspotting** — if routing sends all *recent* writes to one node (e.g., the node owning the current time shard), the live write workload piles on it. Good designs spread the live window across nodes.
- **A single huge tenant/series** in a multi-tenant setup landing entirely on one shard.
- **Replication/leader role** — that node is leader for hot shards and absorbing all writes.

Fix by **choosing a higher-cardinality, more uniform shard key**, hashing to spread the current-time writes, splitting/rebalancing the hot shard, and isolating heavy tenants. Confirm with per-node ingest-rate and per-shard size metrics before moving data. The principle: **even load needs a uniformly-distributed sharding key and a write-routing scheme that doesn't funnel "now" onto one node.**

#### Q109. [Coding] Write a query that flags flapping series — values that change state (above/below a threshold) more than N times in a window.

Count state transitions per series using `lag()`:

```sql
SELECT
    sensor_id,
    sum(transition) AS transitions
FROM (
    SELECT
        sensor_id,
        CASE
            WHEN (temperature > 80)
              <> (lag(temperature) OVER (PARTITION BY sensor_id ORDER BY ts) > 80)
            THEN 1 ELSE 0
        END AS transition
    FROM readings
    WHERE ts >= now() - interval '1 hour'
) t
GROUP BY sensor_id
HAVING sum(transition) > 10
ORDER BY transitions DESC;
```

For each row we compute the boolean "is above 80" for the current and previous reading; when they differ (`<>`), that's a state transition. Summing the transitions per sensor and filtering `> 10` surfaces sensors flapping across the threshold — exactly the series that would generate alert storms. This is the storage-side analog of hysteresis: detect flapping so you can apply a deadband or `for:` duration before alerting.

#### Q110. [Practical] You're seeing "out-of-order sample" rejections in Prometheus. Explain the cause and the modern options to handle it.

Historically Prometheus required samples per series to arrive in **strictly increasing timestamp order**; a sample older than the last appended one for that series was rejected as "out of order." Common causes:

- **Clock skew** between targets, so a re-scraped or remote-written sample carries an earlier timestamp.
- **Remote-write from multiple sources** for the same series arriving interleaved.
- **Batching/replay** that resends older data.
- **Duplicate scrapes** from misconfigured HA.

Modern handling: Prometheus added **out-of-order ingestion** support — set `out_of_order_time_window` so samples up to that age are accepted into a dedicated out-of-order head and merged at query/compaction time. Beyond the window they're still dropped. Complementary fixes: **synchronize clocks (NTP/PTP)**, ensure a single writer per series, and for genuinely late/batch data use a backend (VictoriaMetrics, Mimir) with more permissive ordering. The conceptual point: append-only engines historically assumed monotonic time per series; OOO support relaxes that within a bounded window at some storage/merge cost.

#### Q111. [Coding] Write a query to compute the correlation between two metrics (e.g., CPU and latency) over aligned time buckets.

Align both metrics to the same buckets, then use a statistical aggregate:

```sql
WITH cpu AS (
    SELECT time_bucket('1 minute', ts) AS b, avg(usage) AS cpu
    FROM cpu_metrics
    WHERE ts >= now() - interval '6 hours'
    GROUP BY b
),
lat AS (
    SELECT time_bucket('1 minute', ts) AS b, avg(latency_ms) AS lat
    FROM latency_metrics
    WHERE ts >= now() - interval '6 hours'
    GROUP BY b
)
SELECT corr(cpu.cpu, lat.lat) AS pearson_r
FROM cpu
JOIN lat USING (b);
```

Each CTE buckets one metric to 1-minute averages; the join aligns them on the shared bucket `b`; `corr()` computes the Pearson correlation coefficient across the aligned pairs. A value near +1 suggests CPU and latency move together (a capacity-saturation signal). Always align to identical buckets before correlating — comparing differently-bucketed or unaligned series produces garbage. For lagged relationships, shift one series with `lag()` before correlating.

### 🔴 — extended

#### Q112. [Practical] You're tasked with cutting query latency on a 10-billion-row hypertable where dashboards routinely scan 90-day ranges. Give a prioritized, end-to-end optimization plan.

Work from "touch less data" outward:

1. **Make dashboards read rollups, not raw.** A 90-day panel should hit a 1 h (or 1 d) continuous aggregate, turning a multi-billion-row scan into thousands of rows. This is the single biggest win; everything else is secondary.
2. **Confirm chunk exclusion is firing.** Verify every dashboard query has a sargable time predicate on the partition column and that `EXPLAIN` shows only the relevant chunks opened. Kill any query missing a time bound.
3. **Compression with good segmentby/orderby.** Ensure old chunks are columnar-compressed and `segmentby` matches the common filter (e.g., `host`/`device_id`) so the engine skips whole segments and scans fewer bytes.
4. **Right chunk size.** Too-large chunks reduce exclusion granularity; too-small chunks explode planning overhead. Tune so a typical query opens a handful of chunks.
5. **Real-time aggregates** so the rollup still includes the freshest minutes without scanning raw for "now."
6. **Index/ordering for the hot filter** within chunks; reorder compressed chunks by the common query order.
7. **Cache and concurrency** — dashboard result caching, and ensure compaction isn't starving query CPU.

The architecture answer: **pre-aggregate for the long horizons, prune by time, compress with filter-aligned segmentby, and only ever scan raw for short, recent ranges.** Validate each step with `EXPLAIN (ANALYZE, BUFFERS)` to prove fewer chunks/bytes are touched.

#### Q113. [Practical] Postmortem scenario: a deploy doubled active series and the TSDB OOM-killed, losing the in-memory head. Walk through immediate recovery, root cause, and prevention.

**Immediate recovery (stop the bleeding):**
- Bring the process back; it should **replay the WAL** to reconstruct the head block — the WAL is exactly what protects against losing in-memory data on a crash, so verify replay completed and how much (if anything beyond the unflushed head) was lost.
- If it OOMs again on replay, give it **more memory temporarily** or limit ingestion (drop/relabel the offending series at the scrape/remote-write layer) so it can stabilize.

**Root cause:** the deploy introduced a high-cardinality label (likely a per-pod/per-deploy ID, a request/trace id, or an unbounded value) that doubled active series, blowing past the memory the head/index needs. Confirm with `prometheus_tsdb_head_series` over the deploy and `topk(... count by (__name__) ...)` to find the exploding metric.

**Prevention:**
- **Cardinality limits / per-tenant guards** so new series are rejected gracefully instead of OOMing the process.
- **`metric_relabel_configs` / drop rules** to strip dangerous labels at ingest.
- **CI/lint on metric definitions** and a cardinality budget reviewed before deploys.
- **Series-churn alerting** so a cardinality jump pages *before* it OOMs.
- Consider an engine built for high cardinality (VictoriaMetrics) or moving the high-cardinality dimension to exemplars/traces.

The senior framing: the WAL gave you durability for recovery, but the *real* fix is **bounding cardinality at ingest and catching the jump in CI/alerts**, not buying more RAM after the fact.

#### Q114. [Coding] Implement, in SQL, a correct time-weighted average over irregularly-spaced samples (where each value holds until the next sample), and explain why a plain `avg()` is wrong here.

A plain `avg(value)` weights every sample equally, which is wrong when samples are irregularly spaced and each reading *persists* until the next one — a value held for an hour should count far more than one held for a second. The correct approach weights each value by the **duration until the next sample** (a step-function integral):

```sql
WITH stepped AS (
    SELECT
        ts,
        value,
        lead(ts) OVER (ORDER BY ts) AS next_ts
    FROM gauge_readings
    WHERE ts >= now() - interval '1 day'
)
SELECT
    sum(
        value * extract(epoch FROM (next_ts - ts))
    )
    /
    sum(
        extract(epoch FROM (next_ts - ts))
    ) AS time_weighted_avg
FROM stepped
WHERE next_ts IS NOT NULL;
```

`lead(ts)` gives the start of the next sample; `extract(epoch FROM (next_ts - ts))` is the number of seconds the current value held. We weight each value by its duration, sum, and divide by total duration — the area under the step-function curve divided by the time span, i.e., the true time-weighted mean. The final sample is excluded (no known end time) or you'd clamp it to "now." TimescaleDB ships `time_weighted_average()` (with `LOCF`/linear interpolation options) for exactly this; knowing the manual integral shows you understand *why* equal-weight `avg()` lies on irregular telemetry.

## ✅ Key Takeaways

- Time-series data is **append-mostly, immutable, time-ordered, high-volume, and ages out** — TSDBs specialize the whole stack (time partitioning, columnar compression, retention, rollups) for that pattern.
- **Tags/labels are indexed identity (filter & group); fields are unindexed values (aggregate).** Putting high-cardinality values in tags is the #1 mistake — **cardinality (unique series count), not sample count, is the scaling axis.**
- **Time partitioning (chunks/blocks/shards)** makes inserts fast, retention a cheap chunk-drop, and queries skip irrelevant data via chunk exclusion.
- **Compression is huge** (10–20×) thanks to **delta-of-delta timestamps** and **Gorilla/XOR float encoding** — time-series data is intrinsically predictable.
- **Downsampling + retention + continuous aggregates/recording rules** keep recent data detailed and old data coarse-but-long-lived; store **decomposable aggregates (sum/count/min/max) and percentile sketches (t-digest)**, never re-derive avg-of-avgs or percentile-of-percentiles.
- Engine cheat sheet: **Prometheus** (pull, Kubernetes monitoring), **VictoriaMetrics** (scale/long-term Prometheus, high cardinality), **TimescaleDB** (SQL + relational, hypertables), **InfluxDB 3.x** (Arrow/Parquet, flexible IoT/metrics).
- TSDBs trade **strong ACID for throughput/availability** — eventual consistency and best-effort durability are acceptable for statistical telemetry, not for exact ledgers.

## ⚠️ Common Pitfalls

- **High-cardinality tags** (request IDs, user IDs, raw URLs, churning pod names) — the classic OOM/index-bloat bomb; template/normalize values and keep unique IDs in fields or a separate store.
- **Averaging averages / taking max of per-bucket p99s** when rolling up — mathematically wrong; keep sum+count and mergeable sketches.
- **Wrapping the timestamp column in a function** in `WHERE` (e.g., `date_trunc(ts) = ...`) — breaks chunk exclusion and forces a full scan.
- **Using raw `DELETE` for retention** instead of dropping chunks — causes bloat/vacuum storms; use native retention policies.
- **Querying raw data for long-range dashboards** instead of a rollup — scans billions of points; point panels at the coarsest aggregate that answers the question.
- **Writing into compressed chunks or backfilling without deferring rollup refresh** — slow and thrashing; decompress/insert/recompress and refresh affected windows once.
- **Forgetting the `le` label** when computing `histogram_quantile`, or using the raw value of a counter instead of `rate()`.
- **Treating Prometheus local storage as long-term** — it's not; remote-write to VictoriaMetrics/Mimir/Thanos for retention and HA.

## 📚 Further Reading

- [Gorilla: A Fast, Scalable, In-Memory Time Series Database (Facebook, VLDB 2015)](https://www.vldb.org/pvldb/vol8/p1816-teller.pdf) — the foundational paper for delta-of-delta + XOR compression.
- [Prometheus documentation & "Storage" internals](https://prometheus.io/docs/prometheus/latest/storage/) — head block, WAL, blocks, compaction, and PromQL.
- [TimescaleDB documentation](https://docs.timescale.com/) — hypertables, chunks, compression, continuous aggregates, and retention policies.
- [InfluxDB 3.x ("IOx") documentation](https://docs.influxdata.com/) — Arrow/DataFusion/Parquet engine, line protocol, and SQL.
- [VictoriaMetrics documentation](https://docs.victoriametrics.com/) — high-cardinality handling, cluster architecture, and MetricsQL.
- *Designing Data-Intensive Applications* — Martin Kleppmann (O'Reilly) — LSM-trees, columnar storage, partitioning, and consistency models that underpin every TSDB.
- [Prometheus: Up & Running, 2nd Edition](https://www.oreilly.com/library/view/prometheus-up/9781098131135/) — practical PromQL, histograms, recording rules, and operating Prometheus at scale.
- [t-digest paper (Ted Dunning)](https://arxiv.org/abs/1902.04023) — mergeable percentile sketches for correct rollup of quantiles.
