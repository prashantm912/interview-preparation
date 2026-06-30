# Data Engineering & Stream Processing

[← Back to master index](../README.md)

A practitioner's interview guide to modern data engineering: batch and stream processing engines (Spark, Flink, Kafka Streams), lake/warehouse/lakehouse storage, columnar formats, orchestration, change data capture, exactly-once semantics, windowing and late data, dimensional modeling, and data quality. Examples lean on SQL and Python (PySpark), which is the lingua franca of data platforms even in Java-heavy shops where Spark and Flink jobs are JVM-based.

## Table of Contents
- [🟢 Basic (0–2 yrs)](#-basic-02-yrs)
- [🟡 Intermediate (3–7 yrs)](#-intermediate-37-yrs)
- [🟠 Advanced (8–12 yrs)](#-advanced-812-yrs)
- [🔴 Expert (15+ yrs)](#-expert-15-yrs)

---

## 🟢 Basic (0–2 yrs)

### Q1. [Theory] What is the difference between batch processing and stream processing?
**Batch processing** operates on a bounded, finite dataset: you collect data over a window (an hour, a day), then run a job that reads all of it, transforms it, and writes the result. Latency is measured in minutes to hours, throughput is high, and the job has a clear start and end. **Stream processing** operates on an unbounded, continuous flow of events: records are processed one at a time (or in micro-batches) as they arrive, with latency in milliseconds to seconds.

```
BATCH                              STREAM
[====== day of data ======]        e e e e e e e e e ... (never ends)
        |  one job  |               |  | | |  | |  | continuous
        v           v               v  v v v  v v  v
     result (once/day)            results (always updating)
```

The mental model that unifies them: a batch is just a stream with a known end. Engines like Spark and Flink increasingly treat batch as a special case of streaming.

### Q2. [Theory] What is ETL, and how does ELT differ from it?
**ETL** = Extract, Transform, Load. You pull data from sources, transform it (clean, join, aggregate) in a dedicated processing engine, then load the finished result into the target warehouse. **ELT** = Extract, Load, Transform. You load the raw data into the warehouse *first*, then transform it in place using the warehouse's own compute (SQL).

ELT became dominant because cloud warehouses (Snowflake, BigQuery, Databricks SQL) made storage cheap and compute elastic, so it's now efficient to dump raw data and transform with SQL. Benefits: raw data is preserved for reprocessing, transformations are version-controlled SQL (e.g., dbt), and you avoid a separate transformation cluster. ETL still wins when you must mask/filter sensitive data *before* it lands (compliance), or when the target can't do the transform.

### Q3. [Theory] What is a data lake, a data warehouse, and a lakehouse?
- **Data warehouse**: a structured, schema-on-write store optimized for SQL analytics (Snowflake, Redshift, BigQuery). Strong governance, fast queries, but expensive for raw/unstructured data.
- **Data lake**: cheap object storage (S3, ADLS, GCS) holding raw files in any format, schema-on-read. Flexible and cheap, but easily becomes a "data swamp" with no ACID guarantees, no schema enforcement, and slow queries.
- **Lakehouse**: a table format (Delta Lake, Apache Iceberg, Apache Hudi) layered on the lake's cheap storage that adds ACID transactions, schema evolution, time travel, and warehouse-like performance. It tries to give you the lake's cost and flexibility with the warehouse's reliability and speed.

```
           cost      structure    ACID     schema
warehouse  high      high         yes      write
lake       low       low          no       read
lakehouse  low       medium-high  yes      read+evolve
```

### Q4. [Theory] What is a columnar storage format, and why is Parquet preferred for analytics?
Row formats (CSV, Avro, JSON) store all fields of a record together. **Columnar** formats (Parquet, ORC) store all values of one column together. For analytics — which typically scans a few columns across millions of rows — this is dramatically faster because:
1. **Column pruning**: read only the columns the query needs.
2. **Compression**: a column holds homogeneous values, so encodings (dictionary, run-length, delta) compress 5–10x better than mixed row data.
3. **Predicate pushdown**: per-row-group min/max statistics let the reader skip entire chunks without decoding them.

```
Row store:    [id,name,age][id,name,age]...   -> must read everything
Column store: [id,id,id...][name,name...][age,age...] -> read only 'age'
```

Use Avro/JSON for streaming/transport (row-by-row writes); use Parquet/ORC for the analytical query layer.

### Q5. [Practical] You have a 200 GB CSV file in S3 that analysts query daily. What's the first improvement you'd make?
Convert it to **partitioned Parquet**. CSV is row-based, uncompressed, untyped, and forces a full scan every query. Parquet gives column pruning, predicate pushdown, and typed columns. Partitioning by a common filter column (e.g., date) lets queries skip whole directories.

```sql
-- One-time conversion (Spark SQL / Athena CTAS)
CREATE TABLE events_parquet
WITH (format = 'PARQUET', partitioned_by = ARRAY['event_date'])
AS SELECT * FROM events_csv;
```

A query like `WHERE event_date = '2026-06-30' AND col_a > 5` now reads one partition and only the columns referenced — often 50–100x less data scanned, which directly cuts cost on pay-per-scan engines like Athena.

### Q6. [Theory] What is partitioning in a data lake/warehouse table?
Partitioning physically splits a table's data into separate directories/files based on the value of one or more columns (e.g., `year=2026/month=06/day=30/`). When a query filters on a partition column, the engine performs **partition pruning** — it reads only the matching directories instead of scanning the whole table.

```
events/
  event_date=2026-06-29/  part-0.parquet
  event_date=2026-06-30/  part-0.parquet   <- query for the 30th reads only this
  event_date=2026-07-01/  part-0.parquet
```

The art is choosing a partition column with the right cardinality: too coarse and you scan too much; too fine (e.g., per-second timestamp) and you create millions of tiny files, killing performance (the "small files problem").

### Q7. [Theory] What is Apache Kafka and what role does it play in a data pipeline?
Kafka is a distributed, durable, append-only **commit log** that acts as the central nervous system of a streaming platform. Producers write events to **topics**, which are split into **partitions** for parallelism; consumers read at their own pace, tracking their position with **offsets**. Key properties:
- **Durability**: events are persisted to disk and replicated across brokers.
- **Replayability**: because events are retained (by time or size), consumers can re-read from any offset — invaluable for backfills and reprocessing.
- **Decoupling**: producers and consumers don't know about each other; you can add new consumers without touching producers.

It's the backbone of both real-time pipelines and event-driven microservices.

### Q8. [Theory] In Kafka, what is a partition and how does it relate to ordering and parallelism?
A topic is divided into partitions, each an ordered, immutable sequence of records. **Ordering is guaranteed only within a partition**, not across the topic. The partition a record lands in is chosen by its **key** (hash of key mod partition count) — so all events for the same key (e.g., the same `user_id`) go to the same partition and stay ordered.

Parallelism is bounded by partition count: within a consumer group, each partition is consumed by exactly one consumer, so you can't have more active consumers than partitions. This is the central trade-off: more partitions = more parallelism but more overhead and weaker global ordering.

### Q9. [Practical] Write a SQL query to deduplicate records, keeping only the latest version of each key.
A staple of ingest pipelines (e.g., after a CDC load that may contain multiple updates per row). Use `ROW_NUMBER()` partitioned by the key, ordered by a recency column:

```sql
WITH ranked AS (
  SELECT
    *,
    ROW_NUMBER() OVER (
      PARTITION BY customer_id
      ORDER BY updated_at DESC
    ) AS rn
  FROM customer_updates
)
SELECT * EXCEPT (rn)   -- BigQuery; use explicit columns elsewhere
FROM ranked
WHERE rn = 1;
```

Each `customer_id` group is numbered by descending timestamp; `rn = 1` keeps only the newest. Use a tie-breaker (e.g., a sequence/offset) in `ORDER BY` if two updates share a timestamp.

### Q10. [Theory] What is idempotency and why does it matter in data pipelines?
An operation is **idempotent** if running it multiple times produces the same result as running it once. In data pipelines, jobs fail and get retried constantly, so non-idempotent writes cause duplicates or corruption. The classic fix is to make writes idempotent:
- **Overwrite a partition** rather than appending (`INSERT OVERWRITE PARTITION (dt='2026-06-30')`) — rerunning replaces, never duplicates.
- **MERGE/upsert** on a primary key instead of blind `INSERT`.
- **Deterministic output paths** so a rerun overwrites the same files.

A pipeline you can safely rerun is a pipeline you can safely operate. Idempotency is the foundation of reliable backfills and recovery.

### Q11. [Practical] Write a SQL MERGE (upsert) that inserts new rows and updates changed ones.
`MERGE` is the idempotent workhorse for incremental loads into a lakehouse table (Delta/Iceberg) or warehouse:

```sql
MERGE INTO customers AS target
USING staged_updates AS source
  ON target.customer_id = source.customer_id
WHEN MATCHED AND target.updated_at < source.updated_at THEN
  UPDATE SET name = source.name, email = source.email,
             updated_at = source.updated_at
WHEN NOT MATCHED THEN
  INSERT (customer_id, name, email, updated_at)
  VALUES (source.customer_id, source.name, source.email, source.updated_at);
```

The `AND target.updated_at < source.updated_at` guard prevents an out-of-order or replayed event from overwriting newer data with stale data — making the merge safe to rerun.

### Q12. [Theory] What is Apache Airflow and what is a DAG?
Airflow is a workflow **orchestrator**: it schedules, runs, and monitors pipelines defined as code. A **DAG** (Directed Acyclic Graph) is the pipeline definition — a set of **tasks** with dependencies, with no cycles (so there's always a clear execution order).

```
extract >> transform >> [load_warehouse, load_search_index] >> notify
```

Airflow handles scheduling (cron-like), retries, backfills, alerting, and a UI showing task state. Crucially, Airflow **orchestrates** work — it tells other systems (Spark, dbt, a warehouse) to do the heavy lifting; it is *not* itself a data processing engine. A common anti-pattern is pulling gigabytes of data *through* the Airflow worker instead of having it trigger a Spark job.

### Q13. [Coding] Write a minimal Airflow DAG with three dependent tasks.
```python
from airflow import DAG
from airflow.operators.python import PythonOperator
from datetime import datetime

def extract(): ...
def transform(): ...
def load(): ...

with DAG(
    dag_id="daily_sales_etl",
    schedule="0 2 * * *",          # 02:00 every day
    start_date=datetime(2026, 1, 1),
    catchup=False,                  # don't backfill historic runs on deploy
    max_active_runs=1,
    tags=["sales"],
) as dag:
    t1 = PythonOperator(task_id="extract",   python_callable=extract)
    t2 = PythonOperator(task_id="transform", python_callable=transform)
    t3 = PythonOperator(task_id="load",      python_callable=load)

    t1 >> t2 >> t3                  # dependency chain
```

`catchup=False` is important: with `catchup=True` and an old `start_date`, deploying the DAG triggers a run for every missed interval, which can flood your cluster.

### Q14. [Theory] What is a transformation vs an action in Spark?
Spark RDDs/DataFrames are **lazily evaluated**. **Transformations** (`map`, `filter`, `select`, `join`, `groupBy`) build up a logical plan but execute nothing — they return a new dataset. **Actions** (`count`, `collect`, `show`, `write`, `take`) trigger actual computation: Spark builds an execution plan from all the queued transformations and runs it.

```python
df2 = df.filter(df.amount > 100)   # transformation: nothing runs yet
df3 = df2.groupBy("region").sum()  # transformation: still nothing
df3.show()                         # ACTION: now Spark executes the whole chain
```

Lazy evaluation lets Spark's optimizer (Catalyst) fuse and reorder operations across the whole pipeline before running anything. The practical gotcha: calling an action twice recomputes the whole lineage unless you `cache()`/`persist()`.

### Q15. [Practical] A daily job creates 100,000 tiny files in S3. Why is this bad and how do you fix it?
The **small files problem**. Each file incurs metadata overhead: listing, opening, and reading thousands of tiny files dominates the actual data-read time, and object stores throttle high request rates. Query planning slows to a crawl.

Fixes:
- **Coalesce/repartition before writing**: `df.repartition(20).write...` or `df.coalesce(20)` to control output file count.
- **Compaction job**: periodically rewrite small files into larger ones (target 128 MB–1 GB per file). Lakehouse formats provide this: Delta's `OPTIMIZE`, Iceberg's `rewrite_data_files`.
- **Choose coarser partitioning** so each partition has enough data to fill a reasonably sized file.

```python
df.repartition("event_date").write.partitionBy("event_date").parquet(path)
```

### Q16. [Theory] What is a primary difference between OLTP and OLAP systems?
**OLTP** (Online Transaction Processing) systems (Postgres, MySQL) handle many small, concurrent read/write transactions — order placement, account updates. They're row-oriented, normalized, and optimized for low-latency single-record operations. **OLAP** (Online Analytical Processing) systems (Snowflake, BigQuery) handle a few large analytical queries scanning millions of rows — aggregations, reports. They're columnar, often denormalized, and optimized for throughput over latency. Data engineering largely consists of moving and reshaping data from OLTP sources into OLAP stores.

### Q17. [Practical] How would you incrementally load only new/changed rows from a source table?
Use a **high-water mark** (watermark column), typically a monotonically increasing `updated_at` timestamp or an auto-increment ID. Each run records the max value it processed; the next run pulls only rows beyond it:

```sql
-- Last successful run stored max_watermark = '2026-06-30 02:00:00'
SELECT * FROM source_orders
WHERE updated_at > '2026-06-30 02:00:00'
ORDER BY updated_at;
```

Caveats: the source must reliably update `updated_at` on every change (including deletes — which incremental loads often miss, requiring soft-deletes or CDC). Use `>` carefully around equal timestamps; overlapping with a small lookback window plus idempotent upserts avoids missing rows that committed late.

### Q18. [Theory] What is data quality, and name a few categories of checks you'd implement.
Data quality is the degree to which data is fit for its intended use. Common check categories:
- **Completeness**: no unexpected nulls; expected row counts present.
- **Uniqueness**: primary keys are unique; no duplicate records.
- **Validity**: values conform to type/format/range (e.g., `age BETWEEN 0 AND 120`, valid email regex).
- **Consistency**: cross-field/cross-table rules hold (e.g., `order_total = sum(line_items)`).
- **Freshness/timeliness**: data arrived within the expected SLA.
- **Accuracy**: values match a trusted source of truth.

Tools like Great Expectations, dbt tests, and Soda encode these as assertions that fail the pipeline (or quarantine bad rows) before bad data reaches consumers.

### Q19. [Coding] Write a PySpark snippet that reads JSON, filters, aggregates, and writes Parquet.
```python
from pyspark.sql import SparkSession, functions as F

spark = SparkSession.builder.appName("daily_revenue").getOrCreate()

orders = spark.read.json("s3://raw/orders/2026-06-30/")

daily = (
    orders
    .filter(F.col("status") == "COMPLETED")
    .groupBy("region")
    .agg(
        F.sum("amount").alias("revenue"),
        F.countDistinct("customer_id").alias("buyers"),
    )
)

(daily.write
      .mode("overwrite")                  # idempotent: rerun replaces output
      .partitionBy("region")
      .parquet("s3://curated/daily_revenue/dt=2026-06-30/"))
```

`mode("overwrite")` on a deterministic path makes the job safely rerunnable.

### Q20. [Theory] What does "schema-on-read" vs "schema-on-write" mean?
**Schema-on-write** (warehouses, RDBMS) enforces structure at load time — you must define columns and types before inserting, and bad data is rejected. This guarantees clean, queryable data but is rigid. **Schema-on-read** (data lakes) stores raw files as-is and applies a schema only when you query them. This is flexible — you can land any data immediately and decide structure later — but pushes the burden of validation and interpretation onto every reader, and silently tolerates malformed data until something breaks.

## 🟡 Intermediate (3–7 yrs)

### Q21. [Theory] Explain the Lambda architecture vs the Kappa architecture.
**Lambda** runs two parallel pipelines: a **batch layer** that reprocesses all historical data for accuracy, and a **speed (streaming) layer** that provides low-latency approximate results for recent data. A serving layer merges them. The downside is maintaining **two codebases** with the same business logic in two paradigms — a notorious source of drift and bugs.

```
            ┌── Batch layer  ──┐
source ──> ─┤                  ├──> Serving layer ──> query
            └── Speed layer ───┘
```

**Kappa** eliminates the batch layer: everything is a stream. You keep a long retention log (Kafka) and **reprocess by replaying the stream** through a new version of the streaming job. One codebase, one paradigm. Kappa is preferred today when your stream engine (Flink) is powerful enough to handle reprocessing; Lambda persists where batch tools are far more capable than streaming for certain heavy computations.

### Q22. [Theory] What is a shuffle in Spark, and why is it expensive?
A **shuffle** is the redistribution of data across partitions (and usually across the network between executors) so that all records sharing a key end up on the same partition. It's triggered by wide transformations: `groupBy`, `join`, `distinct`, `repartition`, `reduceByKey`.

It's the most expensive operation in Spark because it involves: writing intermediate data to disk, serializing it, transferring it over the network, and reading it back — plus it's a **stage boundary** that breaks pipelining.

```
Stage 1 (map)        SHUFFLE          Stage 2 (reduce)
[p0: a,b,a]   ──>  write+network  ──> [p0: a,a,a]
[p1: b,a,c]   ──>  by key hash    ──> [p1: b,b]
                                      [p2: c]
```

Minimize shuffles by: filtering early, broadcasting small tables in joins, pre-partitioning data on the join/group key, and avoiding unnecessary `repartition`.

### Q23. [Practical] You have a join between a 1 TB fact table and a 5 MB dimension table that's slow. How do you fix it?
Use a **broadcast (map-side) join**. By default Spark may shuffle both sides (sort-merge join), redistributing 1 TB over the network. Instead, broadcast the tiny dimension table to every executor so the join happens locally with no shuffle of the big table:

```python
from pyspark.sql.functions import broadcast
result = fact.join(broadcast(dim), "product_id")
```

Spark auto-broadcasts tables under `spark.sql.autoBroadcastJoinThreshold` (default 10 MB), but the hint forces it and documents intent. This turns an expensive shuffle join into a cheap hash join. The limit: the broadcast side must fit comfortably in executor memory.

### Q24. [Theory] What is data skew and how do you mitigate it in a distributed join/aggregation?
**Skew** is when data is unevenly distributed across partitions — one key (e.g., a `null` customer_id, or a whale customer) holds disproportionately many rows. After a shuffle, one task processes a giant partition while others finish instantly; that straggler task dominates wall-clock time and may OOM.

Mitigations:
- **Salting**: append a random suffix to the hot key to spread it across N partitions, then aggregate in two passes (per-salt, then combine).
- **Adaptive Query Execution (AQE)**: Spark 3+ detects skewed partitions at runtime and splits them automatically (`spark.sql.adaptive.skewJoin.enabled`).
- **Isolate hot keys**: handle the few skewed keys with a broadcast join and the rest normally.
- **Filter junk keys** (e.g., nulls) before the join.

```
Without salt:  key=X -> partition 3 (10M rows)  <- straggler
With salt:     key=X|0..3 -> partitions 3,7,1,5 (2.5M each)
```

### Q25. [Theory] Explain event time vs processing time, and why the distinction matters.
**Event time** is when an event actually occurred (embedded in the record, e.g., the sensor's timestamp). **Processing time** is when the streaming engine observes/processes it. They diverge because of network delays, retries, buffering, and out-of-order delivery.

```
event time:      e1(10:00)  e2(10:01)  e3(10:02)
                     \          \          /
processing time:   10:00:05   10:02:30  10:01:10   <- e3 arrives before e2!
```

Correct streaming aggregations (e.g., "revenue per minute") must use **event time**, otherwise results depend on system load and replay order — making them non-deterministic and impossible to reproduce. This is why watermarks and event-time windowing exist.

### Q26. [Theory] What is a watermark in stream processing?
A **watermark** is the engine's assertion that "I believe I have now seen all events with event time ≤ T." It's a heuristic for tracking progress in event time despite out-of-order arrival. Watermarks let the engine decide when a window is "complete" enough to emit a result and when buffered state can be dropped.

A common policy: `watermark = max_event_time_seen − allowed_lateness`. With 5 minutes of allowed lateness, when the latest event seen is 10:30, the watermark is 10:25 — windows ending at or before 10:25 can fire.

```
events seen: ...10:28, 10:30, 10:29...
max seen = 10:30
watermark (−5m) = 10:25  -> windows up to 10:25 may close
```

The trade-off: longer lateness allowance = more complete results but higher latency and more state retained.

### Q27. [Theory] Explain tumbling, sliding, and session windows.
Windows group unbounded streams into bounded chunks for aggregation:
- **Tumbling**: fixed-size, non-overlapping. Each event belongs to exactly one window. "Count events per 5-minute bucket."
- **Sliding**: fixed-size but overlapping, advancing by a smaller step. Each event can belong to multiple windows. "5-minute count, updated every 1 minute."
- **Session**: dynamic, gap-based. A window stays open while events keep arriving and closes after a period of inactivity (the gap). Sizes vary per key. "Group a user's activity into sessions separated by 30-min idle gaps."

```
Tumbling: [0-5)[5-10)[10-15)        non-overlapping
Sliding:  [0-5)
            [1-6)
              [2-7)                  overlapping
Session:  [activity...gap...] [activity...]   variable
```

### Q28. [Coding] Write a Spark Structured Streaming query with event-time windowing and a watermark.
```python
from pyspark.sql import functions as F

events = (spark.readStream.format("kafka")
          .option("subscribe", "clicks")
          .load()
          .select(F.from_json(F.col("value").cast("string"), schema).alias("d"))
          .select("d.*"))   # has event_time (timestamp), user_id

agg = (events
       .withWatermark("event_time", "10 minutes")     # tolerate 10m lateness
       .groupBy(
           F.window("event_time", "5 minutes"),        # tumbling
           "user_id")
       .agg(F.count("*").alias("clicks")))

(agg.writeStream
    .outputMode("update")
    .format("console")
    .trigger(processingTime="1 minute")
    .start())
```

The watermark bounds how long state is kept and how late an event can still update its window; events later than 10 minutes past the watermark are dropped.

### Q29. [Theory] How do you handle late-arriving data?
Strategies, often combined:
1. **Watermark with allowed lateness**: accept events up to a bound past the watermark; they still update their window. Beyond the bound, drop them.
2. **Side output / dead-letter** for dropped late events so they aren't silently lost — reprocess them in batch.
3. **Update/upsert semantics** in the sink so a late update can correct an already-emitted result (`outputMode("update")` + an idempotent sink keyed by window).
4. **Lambda-style reconciliation**: a nightly batch job recomputes from the full log to absorb stragglers the streaming layer missed.

The core tension: you can have completeness or low latency, not both — the watermark/lateness setting is where you choose your point on that curve.

### Q30. [Theory] What is Change Data Capture (CDC) and what approaches exist?
**CDC** captures row-level changes (inserts, updates, deletes) from a source database and streams them to downstream systems, keeping a replica/warehouse in sync without full reloads. Approaches:
- **Log-based** (preferred): read the database's transaction log (Postgres WAL, MySQL binlog) — captures every change including deletes, low impact on the source, ordered. Debezium is the standard tool, emitting changes to Kafka.
- **Query-based** (polling): periodically `SELECT ... WHERE updated_at > last_run`. Simple but misses hard deletes and intermediate updates between polls, and adds query load.
- **Trigger-based**: DB triggers write changes to an audit table. Captures everything but adds write overhead and is intrusive.

```
Postgres ──WAL──> Debezium ──> Kafka topic ──> Flink/Spark ──> Lakehouse
```

### Q31. [Practical] A Debezium CDC stream emits inserts, updates, and deletes. How do you apply them to a warehouse table correctly?
CDC events carry an operation type (`c`/`u`/`d` for create/update/delete) and before/after images. You must apply them in order per key and translate them into upserts/deletes — typically with a `MERGE`:

```sql
MERGE INTO dim_customer t
USING (
  -- keep only the latest change per key in this batch
  SELECT * FROM (
    SELECT *, ROW_NUMBER() OVER (PARTITION BY id ORDER BY lsn DESC) rn
    FROM cdc_batch
  ) WHERE rn = 1
) s
ON t.id = s.id
WHEN MATCHED AND s.op = 'd' THEN DELETE
WHEN MATCHED AND s.op IN ('u','c') THEN UPDATE SET name = s.name, email = s.email
WHEN NOT MATCHED AND s.op IN ('u','c') THEN INSERT (id, name, email)
     VALUES (s.id, s.name, s.email);
```

Ordering by the log sequence number (`lsn`/offset) — not wall-clock time — guarantees you apply the final state. Deduplicating to the latest change per key first avoids "row updated by source twice" errors.

### Q32. [Theory] What is exactly-once processing, and how is it actually achieved?
**Exactly-once** means each input event affects the output state exactly once, even across failures and retries — no duplicates, no losses. True end-to-end exactly-once is hard because failures can occur after processing but before acknowledging. It's achieved by combining:
- **Idempotent producers** + transactional writes (Kafka transactions tie consume-process-produce into one atomic unit).
- **Checkpointing** of processing state (Flink/Spark snapshot offsets *and* state together).
- **Transactional/idempotent sinks** so output and offset commit are atomic (two-phase commit).

Often what's really delivered is **effectively-once**: at-least-once delivery plus idempotent application (dedup keys, upserts), which is simpler and sufficient for most analytics. Pure exactly-once requires the sink to participate in the commit protocol.

### Q33. [Theory] Compare Apache Flink and Spark Structured Streaming.
- **Processing model**: Flink is a **true streaming** engine — it processes events one at a time with continuous operators. Spark Structured Streaming is fundamentally **micro-batch** (it added a low-latency continuous mode, but micro-batch is the mainstream path).
- **Latency**: Flink reaches single-digit milliseconds; Spark micro-batch is typically hundreds of ms to seconds.
- **State & event time**: both support rich state, event time, and watermarks; Flink's state backend and savepoints are more mature for large, long-lived state.
- **Ecosystem**: Spark dominates batch + ML and unifies batch/stream in one API; Flink is the specialist for low-latency, stateful event processing.

Rule of thumb: heavy batch + occasional streaming → Spark; demanding always-on low-latency streaming with large state → Flink.

### Q34. [Theory] What are Kafka Streams and how does it differ from Flink/Spark?
**Kafka Streams** is a **client library** (JVM) for building stream processing apps that read from and write to Kafka — not a separate cluster. Your application *is* the processor; you scale by running more instances of your app, and Kafka's consumer-group rebalancing distributes partitions among them. State is stored locally in **RocksDB** and backed up to Kafka changelog topics for fault tolerance.

```
Flink/Spark: submit job  -> dedicated cluster runs it
Kafka Streams: it's a library -> your microservice runs it, scales like any app
```

Use Kafka Streams when your processing is Kafka-to-Kafka, you want no extra cluster to operate, and the workload fits the library model (per-record transforms, joins, windowed aggregations). Use Flink for complex event processing, very large state, or non-Kafka sources/sinks at scale.

### Q35. [Practical] Your Spark job keeps failing with OutOfMemoryError on executors. Walk through how you'd diagnose and fix it.
A systematic approach:
1. **Identify where**: driver OOM (too much `collect()`/broadcast) vs executor OOM (skew, huge partitions, wide aggregations). Check the Spark UI's stage/task metrics for a skewed task.
2. **Reduce per-task data**: increase `spark.sql.shuffle.partitions` so each shuffle partition is smaller; repartition skewed data; salt hot keys.
3. **Avoid pulling data to one place**: never `collect()` a large DataFrame to the driver; reduce broadcast size or raise executor memory.
4. **Spill-friendly ops**: prefer `reduceByKey`/`agg` over `groupByKey` (which materializes all values).
5. **Tune memory**: adjust executor memory and `spark.memory.fraction`; cache only what's reused and `unpersist` when done.
6. **Enable AQE** so Spark right-sizes partitions and handles skew at runtime.

Fix the data distribution first (skew, partition sizing) — throwing memory at a skew problem just delays the failure.

### Q36. [Practical] Design a partitioning + bucketing strategy for a large clickstream table queried by date and user.
- **Partition by date** (`event_date`): queries almost always filter by a time range, so partition pruning eliminates most data. Daily granularity keeps partition count manageable; sub-daily only if volume is enormous.
- **Bucket by user_id** (e.g., 256 buckets): bucketing hashes `user_id` into a fixed number of files *within* each partition. Two benefits: (1) joins/aggregations on `user_id` between two co-bucketed tables avoid a shuffle (data is pre-arranged by key), and (2) point lookups for a user read only one bucket file.

```
clicks/
  event_date=2026-06-30/
    bucket_000.parquet   <- all users hashing to bucket 0
    bucket_001.parquet
    ...
```

Avoid partitioning by `user_id` directly — high cardinality would create millions of partitions (the small-files disaster). Partition on low-cardinality (date), bucket on high-cardinality (user).

### Q37. [Theory] What is star schema and snowflake schema in dimensional modeling?
A **star schema** has a central **fact table** (events/measurements: sales, clicks) surrounded by **dimension tables** (descriptive context: product, customer, date, store). Dimensions are **denormalized** — flat and wide. Queries join the fact to a few dimensions; the shape looks like a star.

A **snowflake schema** normalizes dimensions into sub-dimensions (e.g., `product → category → department` as separate tables), reducing redundancy.

```
STAR                          SNOWFLAKE
  dim_date                      dim_date
     |                             |
dim_prod—FACT—dim_cust       dim_prod—FACT—dim_cust
     |                          |
  (flat)                    dim_category—dim_dept
```

Star is preferred for analytics: fewer joins, simpler, faster, and friendlier to BI tools. Snowflake saves storage and helps consistency but adds join complexity. With cheap columnar storage, star usually wins.

### Q38. [Theory] What are fact tables and dimension tables, and what types of facts exist?
A **fact table** stores measurements of business processes at a defined grain (one row per order line, per click), holding numeric **measures** (amount, quantity) and **foreign keys** to dimensions. A **dimension table** stores the descriptive attributes you filter and group by (who, what, where, when).

Fact types by additivity:
- **Additive**: can be summed across all dimensions (e.g., `sales_amount`).
- **Semi-additive**: summable across some dimensions but not time (e.g., `account_balance` — sum across accounts, but not across days).
- **Non-additive**: ratios/percentages that can't be summed at all (e.g., `profit_margin`); store the components and compute the ratio at query time.

### Q39. [Theory] Explain Slowly Changing Dimensions (SCD) Type 1 vs Type 2.
SCDs define how you handle changes to dimension attributes over time:
- **Type 1 — overwrite**: update the attribute in place; history is lost. Use when old values don't matter (fixing a typo in a name).
- **Type 2 — add a new row**: insert a new version of the record with the changed attribute, marking the old row as expired and the new one as current. Preserves full history. Implemented with `effective_date`, `end_date`, and `is_current` (or a surrogate key per version).

```
Type 2 — customer moves city:
key  cust_id  city     effective    end          current
 1     C100   Boston   2020-01-01   2026-06-29    false   <- expired
 2     C100   Denver   2026-06-30   9999-12-31    true    <- new current row
```

Type 2 is essential when historical reports must reflect attribute values *as they were at the time of the fact* (e.g., which region a sale was credited to back then). The fact references the surrogate key, locking in the correct version.

### Q40. [Coding] Implement an SCD Type 2 upsert in SQL.
```sql
-- Step 1: expire current rows whose attributes changed
UPDATE dim_customer t
SET end_date = CURRENT_DATE - 1, is_current = false
FROM staged s
WHERE t.cust_id = s.cust_id AND t.is_current
  AND (t.city <> s.city OR t.email <> s.email);

-- Step 2: insert new current versions for changed or brand-new customers
INSERT INTO dim_customer (cust_id, city, email, effective_date, end_date, is_current)
SELECT s.cust_id, s.city, s.email, CURRENT_DATE, DATE '9999-12-31', true
FROM staged s
LEFT JOIN dim_customer t
  ON t.cust_id = s.cust_id AND t.is_current
WHERE t.cust_id IS NULL                                   -- new customer
   OR t.city <> s.city OR t.email <> s.email;             -- changed attribute
```

Run inside a transaction so the expire-and-insert is atomic. A surrogate key (auto-increment) on `dim_customer` gives each version a stable identity for facts to reference.

## 🟠 Advanced (8–12 yrs)

### Q41. [Practical] Design an idempotent, restartable backfill for 2 years of historical data into a partitioned table.
Goals: rerunnable, parallelizable, and not disruptive to live data. Strategy:
1. **Partition the work by time** (e.g., one task per day). Each task is independent and idempotent because it does `INSERT OVERWRITE PARTITION (dt=...)` — rerunning a day replaces exactly that partition, never duplicates.
2. **Drive it from the orchestrator**: an Airflow DAG with `catchup=True` (or a dynamic task mapping per day) so each interval is a tracked, retryable run with its own state. Failed days rerun in isolation.
3. **Throttle**: cap concurrency (`max_active_runs`, pool slots) so the backfill doesn't starve production jobs or overwhelm the source.
4. **Separate from live writes**: write to a staging path/table and swap, or run on a dedicated cluster, so a 2-year scan doesn't degrade real-time SLAs.
5. **Validate per partition**: row counts and quality checks before marking a day complete.

```
backfill DAG: [day_2024-01-01][day_2024-01-02]...  each: OVERWRITE its partition
              independent, idempotent, retryable, throttled
```

The key principle: per-partition idempotent overwrite makes the whole backfill safe to stop and resume at any point.

### Q42. [Theory] How does Flink achieve fault-tolerant exactly-once with checkpoints? Explain the Chandy-Lamport mechanism.
Flink periodically snapshots all operator state to durable storage so it can recover to a consistent point. It uses a variant of the **Chandy-Lamport distributed snapshot** algorithm with **barriers**:
1. The source injects a numbered **checkpoint barrier** into the stream alongside the data.
2. As a barrier flows through the dataflow, each operator, upon receiving it, snapshots its own state and forwards the barrier downstream.
3. For operators with multiple inputs, **barrier alignment** waits until the barrier arrives on all input channels before snapshotting — ensuring the snapshot reflects a consistent cut across the whole pipeline.
4. When all operators (and sinks) confirm, the checkpoint is complete; offsets are committed.

```
source ──barrier(n)──> opA ──barrier(n)──> opB ──barrier(n)──> sink
        snapshot at the barrier line = consistent global state
```

On failure, Flink restores all state from the last complete checkpoint and rewinds sources to the corresponding offsets. Combined with transactional sinks (two-phase commit), this yields end-to-end exactly-once. Unaligned checkpoints trade some consistency overhead for lower latency under backpressure.

### Q43. [Practical] A streaming job's state grows unbounded and eventually crashes. Diagnose and fix.
Unbounded state usually means the engine can never decide that some state is safe to drop. Common causes and fixes:
- **No watermark / watermark never advances**: windowed/aggregation state accumulates forever because windows never close. Ensure event-time watermarks are defined and actually progressing (a stalled source partition can hold the global watermark back — the "idle partition" problem; mark idle sources idle).
- **Unbounded keyspace**: keyed state per unique key with ever-growing distinct keys (e.g., keying by raw session_id forever). Add **state TTL** so idle keys expire.
- **Stream-stream joins without time bounds**: an interval/windowed join is required so the engine can purge old rows; an unbounded join buffers everything.
- **Large state backend misconfig**: move to RocksDB state backend (spills to disk) instead of in-memory for very large state.

```
Fix checklist: watermark advancing? state TTL set? joins time-bounded?
               idle partitions handled? RocksDB for big state?
```

### Q44. [Practical] Design an end-to-end exactly-once pipeline from Kafka through Flink to a JDBC sink.
Each hop must preserve the guarantee:
1. **Source**: Flink's Kafka source stores consumed offsets *in the checkpoint*, not in Kafka's auto-commit — so offsets advance only when state is durably snapshotted.
2. **Processing**: enable checkpointing with exactly-once mode; all operator state is part of the snapshot (barrier alignment ensures consistency).
3. **Sink**: use a **two-phase-commit** sink. On each checkpoint, the sink *pre-commits* (writes to a staging transaction); on checkpoint completion it *commits* the DB transaction. If the job fails between, recovery either replays uncommitted work or commits the prepared transaction — never both.

```
Kafka ──offsets in checkpoint──> Flink ──2PC──> JDBC
   checkpoint barrier ties source offset + state + sink txn into one atomic unit
```

If the sink can't do 2PC, fall back to **idempotent writes** (upsert on a primary/dedup key derived from event identity) — at-least-once delivery plus idempotency gives effectively-once, which is operationally simpler and usually sufficient.

### Q45. [Theory] Compare Delta Lake, Apache Iceberg, and Apache Hudi as lakehouse table formats.
All three add ACID transactions, schema evolution, and time travel to files on object storage, but differ in design and strengths:
- **Delta Lake**: a transaction log (`_delta_log` JSON + checkpoints) tracks file additions/removals. Tightly integrated with Spark/Databricks; excellent for batch + streaming upserts (`MERGE`), `OPTIMIZE`/Z-ordering for clustering. Now broadly open via Delta UniForm/Kernel.
- **Apache Iceberg**: a hierarchical metadata tree (snapshots → manifest lists → manifests) tracks files. Strong on **hidden partitioning** (partition transforms decoupled from query predicates, so no manual partition columns), engine-agnostic (Spark, Flink, Trino, Snowflake, BigQuery all read it), and clean snapshot isolation. The de facto open standard in 2026 with the REST catalog.
- **Apache Hudi**: optimized for **fast upserts and incremental pulls/CDC** with Copy-on-Write vs Merge-on-Read tables; record-level indexes make point updates efficient. Best when ingest is update-heavy and you need incremental consumers.

Selection: Iceberg for an open, multi-engine warehouse-style lake; Delta if you're Spark/Databricks-centric; Hudi for heavy streaming upserts/CDC with incremental reads.

### Q46. [Practical] How would you implement comprehensive data quality gates in a production pipeline?
Layer checks and decide what happens on failure:
1. **Schema/contract validation at ingest**: enforce a schema (Avro/Protobuf schema registry) so producers can't break consumers; reject or dead-letter non-conforming records.
2. **Declarative expectations** (Great Expectations / dbt tests / Soda): completeness, uniqueness, ranges, referential integrity, freshness — run as a DAG step *before* publishing.
3. **Quarantine, don't just fail**: route bad rows to a quarantine table for inspection and let good rows proceed (or block the publish for critical tables). Choose per table.
4. **Anomaly detection on metrics**: track row counts, null rates, and distribution drift over time; alert on statistical deviation, not just hard rules.
5. **Circuit breaker**: if quality fails, halt downstream publishes so bad data doesn't propagate — combined with idempotency, you can fix and rerun.
6. **Data contracts & SLAs**: agreements between producers and consumers, with ownership and freshness SLAs surfaced in a catalog.

```
extract -> validate_schema -> [quarantine bad | proceed good] -> expectations
        -> anomaly_check -> PUBLISH (gated) -> alert on breach
```

### Q47. [Theory] What is the role of a metadata catalog and table format in decoupling storage from compute?
A **catalog** (Hive Metastore, AWS Glue, Iceberg REST Catalog, Unity Catalog) maps logical table names to physical file locations, schemas, partitions, and snapshots. A **table format** (Iceberg/Delta/Hudi) defines how files and metadata are organized and how transactions/snapshots work. Together they let **any compute engine** (Spark, Flink, Trino, Snowflake) read and write the *same* tables consistently — you can swap or run multiple engines over one copy of the data without copying or locking yourself into one vendor. This separation of storage, table format, catalog, and compute is the architectural heart of the open lakehouse and a major driver of cost and flexibility in 2026.

### Q48. [Practical] A nightly batch pipeline regularly misses its SLA. How do you systematically find and fix the bottleneck?
1. **Instrument the DAG**: capture per-task duration over time to find which task degraded and whether it's trending up (data growth) or a sudden regression.
2. **Profile the slow task**: in Spark, the UI reveals skewed stages, excessive shuffle, spill to disk, or a straggler executor.
3. **Common fixes**:
   - **Skew/shuffle**: salt hot keys, broadcast small joins, enable AQE, right-size shuffle partitions.
   - **Reading too much**: ensure partition pruning and predicate pushdown actually fire; convert to columnar; compact small files.
   - **Recomputation**: cache reused DataFrames; avoid an action triggering full lineage twice.
   - **Resource contention**: isolate from other jobs (separate pool/cluster); scale executors.
   - **Incremental instead of full**: switch from full reload to watermark-based incremental + upsert.
4. **Re-architect if needed**: if the data simply grew past batch viability, move hot paths to streaming/incremental, or partition the work for parallelism. Always measure before and after.

### Q49. [Behavioral] Tell me about a time a data pipeline silently produced wrong data in production. How did you find it and what changed afterward?
Use STAR and emphasize *systemic* learning, not just the fix. A strong answer covers:
- **Situation/Task**: e.g., a dashboard's revenue looked plausible but was understated; finance flagged a discrepancy weeks later — the worst kind, because "wrong but plausible" data erodes trust silently.
- **Action**: reproduced by comparing against the source of truth, traced lineage to find a timezone bug in a join window that dropped late-arriving events; quantified the blast radius (which reports, which date range, who consumed it).
- **Result**: backfilled correct data with an idempotent rerun, notified affected stakeholders transparently.
- **Systemic change** (the real signal of seniority): added reconciliation checks comparing pipeline output to source totals, freshness/row-count anomaly alerts, and a data-contract test that would have caught the timezone assumption. The lesson: pipelines fail loudly when they crash but *silently* when logic is subtly wrong — so quality gates and reconciliation matter more than uptime. Show you drove a cultural shift toward treating data as a product with SLAs and ownership.

### Q50. [Theory] How do you handle schema evolution in a streaming pipeline without breaking downstream consumers?
Use a **schema registry** (Confluent Schema Registry with Avro/Protobuf/JSON Schema) that enforces **compatibility rules** on every schema change:
- **Backward compatible**: new schema can read old data (e.g., add an optional field with a default) — new consumers read old events.
- **Forward compatible**: old schema can read new data — old consumers tolerate new events.
- **Full**: both.

Practical rules: only add optional/defaulted fields; never remove or rename a required field or change its type in place (do additive change + dual-write + migrate + remove). For breaking changes, version the topic/subject or run old and new in parallel during migration. Columnar/lakehouse formats (Iceberg/Delta) support schema evolution (add/rename/reorder columns) on the storage side via metadata, so reads stay consistent. The discipline: producers and consumers deploy independently, so the registry's compatibility check is your contract enforcement.

### Q51. [Practical] When would you choose stream processing over batch, and what hidden costs come with "going real-time"?
Choose streaming when business value is genuinely time-sensitive: fraud detection, real-time personalization, monitoring/alerting, dynamic pricing — places where a result an hour late is worthless. Choose batch when consumers tolerate latency (daily reports, training datasets) because it's simpler and cheaper.

Hidden costs of real-time that interviewers want you to name:
- **Operational complexity**: always-on services, stateful recovery, checkpointing, backpressure, and 24/7 on-call vs a batch job you rerun.
- **Correctness is harder**: out-of-order data, watermarks, late data, and exactly-once semantics that batch sidesteps by reprocessing the whole bounded set.
- **Debugging/reprocessing**: replaying a stream to fix a bug is harder than rerunning a batch job; you need long retention and careful state handling.
- **Cost**: continuously running clusters vs bursty batch compute.

Senior framing: don't go real-time by default. Quantify the latency requirement; many "real-time" asks are satisfied by micro-batch every few minutes at a fraction of the complexity.

## 🔴 Expert (15+ yrs)

### Q52. [Theory] Critically evaluate the data mesh paradigm. When does it help and when does it create chaos?
**Data mesh** is a sociotechnical decentralization: domain teams own their data as **products** (with SLAs, documentation, quality, and discoverability), governed by **federated computational governance**, on a **self-serve platform**. It's a reaction to the bottleneck of a single central data team that owns everything and understands nothing deeply.

It *helps* in large organizations where (a) a central team is a chronic bottleneck, (b) domains have the maturity and headcount to own data products, and (c) leadership funds a real self-serve platform. It *creates chaos* when adopted as a label without the platform investment: you get fragmentation, inconsistent definitions of core entities (every team's "customer" differs), duplicated effort, and *worse* governance than centralization. Common failure: reorganizing ownership without building the platform and governance plane that make decentralization safe.

Senior judgment: data mesh is an organizational strategy, not a technology purchase. Most companies need only *parts* of it (clear ownership, data contracts, a catalog) and would be harmed by full decentralization. Match the operating model to org size and maturity.

### Q53. [Practical] Design a unified batch + streaming platform that avoids the Lambda dual-codebase problem at scale.
Target a **Kappa-leaning unified architecture** on an open lakehouse:
- **Single ingestion log**: Kafka with long retention as the source of truth for events; CDC (Debezium) brings in operational DBs.
- **One processing engine, one logic**: Flink (or Spark) jobs written once, run in streaming mode for low latency and re-run over the same code for reprocessing/backfill (Kappa). Where a heavy computation is impractical to stream, isolate it behind the same table interface rather than forking logic.
- **Lakehouse as the serving substrate**: Iceberg/Delta tables give ACID, so streaming upserts and batch corrections write to the *same* tables; time travel and snapshots enable reproducible reprocessing.
- **Open catalog + multiple query engines** (Trino/Spark/warehouse) over one copy of data.
- **Reprocessing strategy**: to fix a bug, spin up a new job version, replay from the retained log into a *new* table version, validate, then atomically swap — no parallel permanent pipeline.

```
Kafka(log) ─┬─ stream job ──> Iceberg tables ──> Trino/BI (fresh)
            └─ replay (same code, new version) ──> validate ──> swap
```

This collapses Lambda's two codebases into one, using replayability + ACID table swaps instead of a permanent batch layer.

### Q54. [Theory] How do you guarantee correctness across a multi-stage pipeline spanning Kafka, Flink, a lakehouse, and a warehouse — each with different consistency models?
You cannot rely on a single global transaction across heterogeneous systems, so you engineer **end-to-end effectively-once with reconciliation**:
1. **Per-hop guarantees**: Kafka transactions (consume-process-produce atomic), Flink checkpoints (state + offsets), lakehouse ACID commits, warehouse MERGE upserts — each hop is individually exactly/effectively once.
2. **Stable event identity**: a deterministic dedup/business key carried end to end so any hop can upsert idempotently regardless of retries.
3. **Monotonic ordering metadata** (LSN/offset/event-time) so the final state per key is deterministic even with out-of-order or replayed data.
4. **Watermarks + bounded lateness** so windows and joins are reproducible, with a side channel for stragglers.
5. **Reconciliation/audit layer**: independent batch jobs that recompute totals from the immutable log and compare against the serving tables, alerting on drift — catching the silent errors that per-hop guarantees miss.

The senior insight: "exactly-once" end-to-end across heterogeneous systems is achieved through **idempotency + deterministic ordering + reconciliation**, not a magic distributed transaction. Design for detect-and-correct, not just prevent.

### Q55. [Behavioral] You inherit a sprawling, fragile data platform with no tests, frequent silent failures, and low trust from the business. How do you turn it around?
Show strategic sequencing under constraint, not a rewrite fantasy:
- **Stabilize trust first**: instrument freshness, row-count, and reconciliation checks on the few *highest-value* tables so the business stops finding errors before you do. Visible reliability on what matters buys credibility and time.
- **Establish ownership and contracts**: map who consumes what, assign clear ownership, and introduce data contracts on critical interfaces so breaking changes stop happening silently.
- **Make pipelines idempotent and rerunnable** so incidents become "rerun and recover" instead of heroics — this alone slashes operational pain.
- **Strangler-fig migration**: don't big-bang rewrite; wrap and incrementally replace fragile components, validating each by running old and new in parallel and reconciling outputs.
- **Invest in the platform/self-serve layer** so teams stop building one-off fragile jobs.
- **Manage stakeholders**: be transparent about what's broken and the timeline; deliver early visible wins (the reliability dashboard) to rebuild trust.

The leadership signal: you prioritize **trust and business value over technical purity**, sequence remediation by impact, reduce operational toil systemically (idempotency, contracts, tests), and bring people along rather than disappearing into a multi-quarter rewrite.

### Q56. [Theory] How do streaming and data-engineering patterns change when the consumers are ML systems and LLM/RAG pipelines?
Several shifts that a 2026 staff-level engineer should articulate:
- **Feature freshness and consistency**: online ML needs a **feature store** giving the same feature values at training time (batch, point-in-time correct) and serving time (streaming, low latency) — preventing **training/serving skew**. Streaming pipelines compute and materialize features with strict point-in-time correctness (no future leakage).
- **Embeddings and vector pipelines**: RAG requires pipelines that chunk, embed, and upsert documents into a **vector store**, with CDC-driven incremental re-embedding when source docs change, plus dedup and freshness guarantees so retrieval reflects current truth.
- **Data quality becomes model quality**: bad/late/skewed data degrades models silently; reconciliation and drift detection (data drift *and* concept drift) become first-class, feeding back into retraining triggers.
- **Lineage and governance** matter more: provenance for every feature/embedding (which source, which version) for reproducibility, compliance, and debugging hallucinations.
- **Lakehouse as the shared substrate**: the same open tables serve BI, model training, and RAG ingestion, with time travel enabling reproducible training snapshots.

The throughline: classic data-engineering rigor — idempotency, point-in-time correctness, exactly-once, quality gates, lineage — doesn't disappear with AI consumers; it becomes *more* critical because errors propagate silently into model behavior.

## 🧩 Extended Questions — Set 1: Deeper theory & internals

### 🟢 — extended

#### Q57. [Theory] What is the physical anatomy of a Parquet file — row groups, column chunks, pages, and the footer?
A Parquet file is a self-describing, hierarchical container read back-to-front:
- **Row group**: a horizontal slice of the table (default target ~128 MB). It is the unit of parallelism — one task typically reads one row group.
- **Column chunk**: within a row group, all values for a single column stored contiguously. This contiguity is what enables column pruning (seek to the chunks you need, skip the rest).
- **Page**: a column chunk is split into pages (~1 MB), the smallest unit of encoding and compression. Each page has a header with value counts and (optionally) min/max stats.
- **Footer**: at the *end* of the file sits the `FileMetaData` — schema, row-group locations, and per-column-chunk statistics (min/max, null count). A reader opens the file, seeks to the last 8 bytes (`PAR1` magic + footer length), reads the footer, then seeks directly to the chunks it wants.

```
[PAR1][ RowGroup0: colA-chunk | colB-chunk ][ RowGroup1: ... ][ FileMetaData ][footer_len][PAR1]
```

Because metadata is in the footer, Parquet is written in a single forward pass but read with random access. The footer stats drive predicate pushdown: if a row group's `max(price) < 100`, a `WHERE price > 100` query skips the whole group without decoding a single page.

#### Q58. [Theory] Explain dictionary encoding and run-length encoding (RLE) in columnar formats. Why do they beat generic compression?
These are **lightweight encodings** applied *before* (and complementary to) generic compression (Snappy/Zstd):
- **Dictionary encoding**: replace each distinct value with a small integer index into a per-column-chunk dictionary. A column of country names (`"United States"`, `"Germany"`...) becomes a dictionary of ~200 strings plus a column of tiny ints. Huge win for low-cardinality string columns.
- **Run-length encoding (RLE)**: store runs of repeated values as `(value, count)`. Sorted or low-entropy columns compress dramatically — `1,1,1,1,1` becomes `(1, 5)`. Parquet pairs RLE with **bit-packing** (RLE/bit-pack hybrid) so the dictionary indices themselves use only as many bits as needed.

They beat throwing Gzip at raw rows because they exploit *columnar homogeneity and semantics* (a column is one type, often few distinct values), whereas generic compressors work on opaque byte streams. The encoded data is also smaller in memory and faster to filter — engines can evaluate predicates directly on dictionary indices (predicate on the dictionary, not on every row). When cardinality is too high, Parquet automatically falls back from dictionary to plain encoding per chunk.

#### Q59. [Theory] What exactly is a Kafka offset, and how do `__consumer_offsets`, log-end offset, and high-water mark differ?
An **offset** is a monotonically increasing integer identifying a record's position *within a partition* (not the topic). Several offset concepts coexist:
- **Log-end offset (LEO)**: the offset that will be assigned to the *next* record a broker appends — i.e., one past the last written record.
- **High-water mark (HW)**: the highest offset that has been replicated to all in-sync replicas (ISR). Consumers can only read up to the HW, never beyond — this prevents reading records that could be lost if the leader fails before replication. Records between HW and LEO exist on the leader but aren't yet "committed."
- **Committed consumer offset**: where a consumer group has recorded its progress, stored in the internal compacted topic `__consumer_offsets` (keyed by group+topic+partition). On rebalance or restart, a consumer resumes from here.

```
partition log:  [0][1][2][3][4][5][6]
                          ^HW=4        ^LEO=7   (5,6 not yet fully replicated)
group committed offset = 3  -> consumer resumes at 3
```

The distinction matters for delivery semantics: committing offsets *before* processing risks data loss; committing *after* risks duplicates — the core at-least-once vs at-most-once trade-off.

#### Q60. [Theory] What is the difference between `log.retention` and **log compaction** in Kafka, and when do you use each cleanup policy?
Kafka has two `cleanup.policy` modes that control how old data is removed:
- **`delete` (retention)**: drop entire log segments older than `retention.ms` or beyond `retention.bytes`. This is time/size-based eviction — used for event streams where old events stop mattering (clickstreams, metrics). Replay is bounded by the retention window.
- **`compact`**: retain at least the *latest* value for each key, garbage-collecting superseded older values. The log becomes a **changelog/snapshot** — you can replay it to reconstruct current state per key. Used for state topics: Kafka Streams' state-store changelogs, `__consumer_offsets`, CDC "current state" topics, and config/lookup tables.

```
delete:   keep last N hours, drop old segments wholesale
compact:  key=A v1, key=B v1, key=A v2  ->  compacted to  key=B v1, key=A v2
```

You can combine them (`compact,delete`) to keep the latest per key *and* eventually expire truly old keys (e.g., tombstone cleanup). A **tombstone** (a record with a key and `null` value) signals deletion; after `delete.retention.ms` the compactor physically removes the key. This is how a compacted topic propagates deletes.

#### Q61. [Practical] Your team debates `WHERE dt = '2026-06-30'` vs `WHERE event_timestamp >= '2026-06-30'` on a date-partitioned table. Why can the second one be catastrophically slow, and how do you check?
The table is partitioned by a column `dt` (a string/date derived at write time). Partition pruning only fires when the predicate is on the **partition column itself**, in a form the engine can match against partition metadata.

- `WHERE dt = '2026-06-30'` → the planner consults the catalog, finds exactly one partition directory, and reads only that. Fast.
- `WHERE event_timestamp >= '2026-06-30'` → `event_timestamp` is a *data* column, not the partition key. The engine has no partition metadata for it, so it cannot prune by directory; it must open every partition, read the `event_timestamp` column, and filter row by row (predicate pushdown may skip some row groups via min/max stats, but it still touches every partition's metadata and likely most files).

You verify with the query plan. In Spark:

```python
spark.sql("SELECT * FROM events WHERE dt = '2026-06-30'").explain(True)
# look for: PartitionFilters: [dt#.. = 2026-06-30], and a small "number of files read"
```

Look for `PartitionFilters` (good — pruning) vs the predicate appearing only under `PushedFilters`/`Filter` with all partitions scanned. Fixes: filter on the partition column; if users naturally filter by `event_timestamp`, either add a generated/derived partition column aligned to it, or use a format with hidden partitioning (Iceberg) so a timestamp predicate maps to the partition transform automatically.

#### Q62. [Theory] What is a "wide" vs "narrow" dependency in Spark, and how does it map to stages?
Spark's DAG scheduler classifies each transformation's parent→child partition relationship:
- **Narrow dependency**: each parent partition feeds *at most one* child partition (`map`, `filter`, `union`, `mapPartitions`). No data movement between nodes — these can be **pipelined** together within a single task on one executor.
- **Wide dependency** (shuffle dependency): a child partition depends on data from *many* parent partitions (`groupByKey`, `reduceByKey`, `join`, `distinct`, `repartition`). This requires a **shuffle**.

The scheduler cuts the DAG into **stages at every wide dependency**: a stage is a chain of narrow transformations that can run without a shuffle. A wide dependency forces the previous stage to fully complete (write shuffle files) before the next stage reads them.

```
read -> filter -> map  |SHUFFLE|  reduceByKey -> map -> write
\______ Stage 1 ______/          \_____ Stage 2 _____/
        (narrow, pipelined)               (after shuffle read)
```

This is why minimizing wide dependencies matters: each stage boundary is a materialization + network cost and a synchronization point where the slowest task gates the whole stage.

#### Q63. [Theory] How does Spark's Catalyst optimizer turn a query into an execution plan? Name the phases.
Catalyst is a rule-based + cost-based optimizer that progresses through tree transformations:
1. **Parsing → Unresolved Logical Plan**: the SQL/DataFrame API produces a tree with column/table references not yet validated.
2. **Analysis → Resolved Logical Plan**: resolve references against the catalog (table exists, column types), bind functions. Failures (unknown column) surface here.
3. **Logical Optimization**: apply rule-based rewrites — predicate pushdown, column pruning, constant folding, filter/projection reordering, `null` propagation, simplifying expressions. These are heuristics that are almost always wins.
4. **Physical Planning**: generate one or more physical plans (e.g., choose broadcast-hash-join vs sort-merge-join) and use **cost-based optimization** (table/column statistics) to pick one.
5. **Code generation (Tungsten / whole-stage codegen)**: compile the physical plan into JVM bytecode that fuses operators into tight loops, avoiding virtual function calls and boxing per row.

```
SQL/DF -> Unresolved LP -> (catalog) Resolved LP -> Optimized LP -> Physical Plans -> selected plan -> codegen
```

**Adaptive Query Execution (AQE)** adds a runtime feedback loop on top: after each stage, Spark re-optimizes using *actual* shuffle statistics — coalescing small partitions, switching join strategies, and splitting skewed partitions — because compile-time stats are often stale or missing.

#### Q64. [Practical] Write a SQL query using a window function to compute a 7-day moving average of daily revenue per region. Explain the frame.
```sql
SELECT
  region,
  event_date,
  daily_revenue,
  AVG(daily_revenue) OVER (
    PARTITION BY region
    ORDER BY event_date
    ROWS BETWEEN 6 PRECEDING AND CURRENT ROW    -- this row + 6 prior = 7 days
  ) AS revenue_7d_moving_avg
FROM daily_region_revenue
ORDER BY region, event_date;
```

The **window frame** `ROWS BETWEEN 6 PRECEDING AND CURRENT ROW` defines, for each row, the set of rows the aggregate sees. Key subtleties:
- `ROWS` counts *physical rows*, so it assumes exactly one row per day per region with no gaps. If days can be missing, `ROWS` would average over the wrong calendar span — use `RANGE BETWEEN INTERVAL '6' DAY PRECEDING AND CURRENT ROW` (on a date/timestamp `ORDER BY`) so the frame is defined by *value distance*, not row count.
- Without an explicit frame, the default with `ORDER BY` is `RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW` (a running total), which is a classic gotcha — people add `ORDER BY` for a moving average and silently get a cumulative one.

### 🟡 — extended

#### Q65. [Theory] Explain the consensus and replication model behind Kafka: ISR, `acks`, `min.insync.replicas`, and the leader epoch.
Kafka replicates each partition across brokers with one **leader** and several **followers**. Durability is governed by a set of interacting knobs:
- **ISR (in-sync replicas)**: the subset of replicas currently caught up to the leader (within `replica.lag.time.max.ms`). Only ISR members are eligible to become leader on failure.
- **`acks`** (producer): `acks=0` fire-and-forget, `acks=1` leader-only ack (can lose data if the leader dies before replication), `acks=all` waits until all ISR members have the record.
- **`min.insync.replicas`** (topic): the minimum ISR size for an `acks=all` write to succeed. With replication factor 3 and `min.insync.replicas=2`, you tolerate one broker down and still accept writes; if two are down, producers get an error rather than silently losing durability.
- **Leader epoch**: a monotonically increasing number bumped on each leader change. Followers use it to detect and truncate divergent log suffixes after a failover, replacing the old, buggy high-water-mark-based truncation and preventing data loss/divergence during leader changes.

The durable-config trifecta is `replication.factor=3`, `min.insync.replicas=2`, `acks=all`: a write is acknowledged only when it survives at least two brokers, so a single failure never loses acknowledged data.

#### Q66. [Theory] How do incremental/snapshot checkpoints work with RocksDB state backends, and what is the role of changelog state?
For large keyed state, in-memory snapshots are too slow, so engines (Flink) use the **RocksDB state backend**, which keeps state as on-disk LSM-tree SST files spilling beyond memory.
- **Incremental checkpoints**: instead of re-uploading all state each checkpoint, Flink uploads only the *new and changed* RocksDB SST files since the last checkpoint (RocksDB's immutable SST files make this natural). This makes checkpoint size proportional to the *delta*, not total state — essential for terabyte-scale state.
- **Trade-off**: recovery is more expensive (must reassemble from multiple incremental layers) and old checkpoints can't be deleted while newer ones reference their files; you manage retained checkpoints carefully.
- **Changelog state backend (generic log-based checkpointing)**: decouples checkpoint *duration* from the checkpoint *interval* by continuously writing state changes to a durable changelog and only periodically materializing RocksDB snapshots. This yields much shorter, more predictable checkpoint times under backpressure, at the cost of extra write amplification.

The throughline: checkpointing cost is the dominant tax on large stateful streaming, and these mechanisms exist to make that cost incremental and decoupled from latency.

#### Q67. [Practical] In Spark, why does `groupByKey().mapValues(...)` often OOM where `reduceByKey` does not? Explain the internals.
The difference is **map-side combine (pre-aggregation)**:
- `reduceByKey(f)` applies the reduce function *locally on each partition before the shuffle* (a combiner), so each mapper sends at most one partially-aggregated value per key over the network. The shuffle volume is bounded by the number of distinct keys, and the reduce side never holds all raw values for a key at once.
- `groupByKey()` does **no** map-side combine — it shuffles *every raw value* and then materializes the full `Iterable` of all values for a key on a single reducer partition. A hot key (or just a high-volume key) means one reducer must hold all its values in memory to build the iterable, causing OOM and massive shuffle traffic.

```
reduceByKey:  partition-local sum -> shuffle (1 value/key) -> final sum
groupByKey:   shuffle ALL values -> reducer holds full list per key -> OOM risk
```

Rule: prefer `reduceByKey`, `aggregateByKey`, or DataFrame `groupBy().agg()` (which Catalyst plans with partial aggregation) over `groupByKey`. Only use `groupByKey` when you genuinely need every value materialized and the per-key cardinality is bounded.

#### Q68. [Theory] What is the small-files problem *inside* a lakehouse table's metadata layer, and how does compaction (`OPTIMIZE` / `rewrite_data_files`) actually fix it?
Beyond the object-store read overhead, small files bloat the **table metadata**. In Iceberg, every data file is tracked by an entry in a **manifest**; thousands of tiny files mean huge manifest lists, so query *planning* (figuring out which files to read) becomes slow before any data is even read. In Delta, the `_delta_log` accumulates many `add`/`remove` actions. Streaming ingest is the usual culprit: each micro-batch commit writes new small files.

Compaction rewrites many small files into fewer large ones and rewrites the metadata to point at them:
- **Delta**: `OPTIMIZE table [WHERE partition_filter]` bin-packs files to a target size (e.g., 128 MB–1 GB); `ZORDER BY (cols)` additionally co-locates related values for better data skipping.
- **Iceberg**: `rewrite_data_files` (bin-pack or sort strategy) plus `rewrite_manifests` to compact the metadata tree; `expire_snapshots` removes old snapshots so dropped files can be physically deleted.

```
before: 5000 x 2MB files + bloated manifests -> slow planning + slow reads
after:  ~80 x 128MB files + compact manifests -> fast planning + fewer requests
```

The subtlety: compaction is itself a transaction that creates a new snapshot, so it must be scheduled to not conflict with concurrent writes, and old snapshots are retained for time-travel until explicitly expired — meaning storage temporarily holds both old and new files.

#### Q69. [Coding] Write a PySpark UDF and explain why a Pandas (vectorized) UDF outperforms a regular Python UDF.
```python
from pyspark.sql import functions as F
from pyspark.sql.types import DoubleType
import pandas as pd

# Regular Python UDF: row-at-a-time, serializes each row to Python and back
@F.udf(returnType=DoubleType())
def to_celsius_slow(f):
    return (f - 32) * 5.0 / 9.0

# Pandas (vectorized) UDF: operates on a whole pandas Series per batch via Arrow
@F.pandas_udf(DoubleType())
def to_celsius_fast(f: pd.Series) -> pd.Series:
    return (f - 32) * 5.0 / 9.0

df = df.withColumn("celsius", to_celsius_fast("fahrenheit"))
```

Why the vectorized version wins:
- A **regular Python UDF** breaks Spark's JVM-side pipeline: for *every row*, Spark serializes the value, ships it to a Python worker process, deserializes, runs the function, then serializes the result back. This per-row round trip dominates and also defeats whole-stage codegen and Catalyst optimizations (the UDF is an opaque black box).
- A **Pandas UDF** uses **Apache Arrow** to transfer data in columnar batches with zero-copy(-ish) serialization, then runs vectorized NumPy/pandas operations over the whole batch at C speed. One round trip per *batch* instead of per *row*.

Better still: avoid UDFs entirely when a built-in Spark SQL expression exists (`(F.col("fahrenheit") - 32) * 5 / 9`), because native expressions stay fully inside Catalyst/Tungsten codegen and need no Python at all.

#### Q70. [Theory] Explain the difference between Copy-on-Write (CoW) and Merge-on-Read (MoR) table layouts in a lakehouse, with their read/write trade-offs.
These describe how a table format applies updates/deletes to immutable columnar files:
- **Copy-on-Write**: on update/delete, rewrite the *entire* data file(s) containing affected rows with the changes applied. Reads are fast (read clean Parquet, no merge), but writes are expensive (write amplification — change one row, rewrite a whole file). Best for read-heavy, update-light tables.
- **Merge-on-Read**: on update/delete, write small **delta/delete files** (positional or equality deletes, or log files) recording the change *without* rewriting the base file. Writes are cheap and fast (great for high-frequency streaming upserts/CDC), but reads must **merge** base files with their delta files at query time, costing read latency. A background **compaction** periodically merges deltas into new base files to restore read performance.

```
CoW:  update -> rewrite full base file        (slow write, fast read)
MoR:  update -> append delete/delta file       (fast write, slow read) -> compact later
```

Hudi exposes this choice explicitly (CoW vs MoR tables); Iceberg supports MoR via positional/equality delete files; Delta uses deletion vectors (a MoR-style optimization marking deleted rows without rewriting). The decision is fundamentally about your read/write ratio and latency SLA.

#### Q71. [Practical] You need to join two streams (e.g., impressions and clicks) in real time. What are the internals and pitfalls of a streaming stream-stream join?
A stream-stream join must buffer state because the matching record may arrive later, so it requires a **time bound** to be feasible:
- **Interval/windowed join**: define that a click can match an impression within, say, the prior 30 minutes. The engine keeps each side's records in keyed state only for that window; the watermark lets it **purge** state older than the bound. Without a bound, state grows unbounded (you'd have to remember every impression forever).
- **Outer joins need extra care**: to emit an impression with *no* click (left outer), the engine must wait until the watermark guarantees no matching click can still arrive, *then* emit the null-padded row — so outer-join results are delayed by the join window plus watermark lag.
- **Pitfalls**: (1) late data beyond the watermark drops potential matches silently; (2) skew on the join key creates straggler state; (3) duplicate emits if the sink isn't idempotent; (4) clock/watermark misalignment between the two sources stalls progress (the slower source's watermark gates the join).

```
impressions ─┐
             ├─ keyed state, bounded by [t-30m, t] ─> emit matched (and timed-out unmatched)
clicks ──────┘   watermark purges expired state
```

The senior framing: a streaming join is really "a bounded, stateful, time-aware buffer." Choosing the window width trades match completeness against state size and latency.

#### Q72. [Theory] What is predicate pushdown vs projection pushdown vs partition pruning? Distinguish all three precisely.
All three reduce I/O but operate at different layers:
- **Partition pruning**: skip entire *partitions/directories* based on a predicate on the **partition column**, using catalog metadata — decided at *planning* time, before opening any data file. Coarsest and cheapest.
- **Predicate (filter) pushdown**: push a row-level filter (e.g., `price > 100`) down into the file reader so it uses per-row-group/page **min/max statistics** to skip chunks, and (for some sources like JDBC) into the source system so the filter executes there. Skips *parts of files*.
- **Projection (column) pushdown**: push the set of *needed columns* down so the reader fetches only those column chunks (columnar formats) — skips *columns* entirely.

```
SELECT name FROM t WHERE dt='2026-06-30' AND age > 40
        |              |                     |
   projection      partition             predicate
   pushdown        pruning               pushdown
 (only 'name')   (one dir)        (skip row groups where max(age)<=40)
```

A well-optimized scan stacks all three: prune to one partition, read only the needed columns, and within those skip row groups by statistics. When you `explain` a query, you should see `PartitionFilters`, `PushedFilters`, and a reduced output schema — if a filter is *not* in `PushedFilters`, it's being applied late (after reading), which is the thing to fix.

### 🟠 — extended

#### Q73. [Theory] Explain Iceberg's metadata architecture (metadata file → manifest list → manifests → data files) and how an atomic commit works.
Iceberg tracks a table as an immutable tree of metadata, enabling snapshot isolation without a metastore lock on data:
- **Metadata file** (`vN.metadata.json`): the table's root — current schema, partition spec, sort order, snapshot history, and a pointer to the **current snapshot**.
- **Manifest list**: per snapshot, a file listing all **manifest files** in that snapshot, with partition-range summaries for pruning.
- **Manifest file**: lists **data files** (and delete files), each entry carrying partition values, record counts, and column-level min/max/null stats used for skipping.
- **Data files**: the actual Parquet/ORC/Avro files.

```
catalog ptr -> v3.metadata.json -> snapshot S3 -> manifest-list -> [manifest-a, manifest-b] -> data files
```

**Atomic commit**: a writer produces new data files, writes new manifests and a new manifest list, builds a new metadata file referencing the new snapshot, then performs a **single atomic swap** of the catalog pointer from `vN` to `vN+1` (a compare-and-swap in the catalog — Hive/Glue/REST/Nessie). If two writers race, one's CAS fails and it retries against the new base (optimistic concurrency). Readers always resolve the pointer to one immutable metadata file, so they see a consistent snapshot and never a half-written table. This is also what powers time travel: an old `metadata.json` still references its snapshot tree intact.

#### Q74. [Theory] How does Snowflake's micro-partition + pruning architecture differ from Hive-style directory partitioning?
Hive-style partitioning is **explicit and user-managed**: you pick partition columns, data physically lives in `col=value/` directories, and pruning requires predicates on those exact columns (with the cardinality pitfalls already discussed).

Snowflake uses **automatic micro-partitions**: data is transparently divided into immutable ~50–500 MB compressed columnar units as it's loaded, with no user-declared partition columns. For each micro-partition, Snowflake stores rich **metadata** — per-column min/max, distinct counts, null counts. At query time it performs **pruning** by consulting this metadata to skip micro-partitions that can't match the predicate, for *any* column, not just a chosen partition key.

- **Clustering**: by default micro-partitions reflect load order. For large tables, a **clustering key** (and automatic reclustering) physically co-locates related values so pruning is effective on the columns you filter by — analogous to choosing a partition/sort dimension, but maintained automatically.
- **Trade-off**: you give up explicit control for far less operational burden and no small-files/partition-explosion problem, at the cost of vendor-managed opacity. The Iceberg analog is **hidden partitioning + sort orders**, bringing similar "don't hand-manage directories" benefits to the open lakehouse.

#### Q75. [Practical] Walk through diagnosing and fixing checkpoint failures / backpressure in a Flink job whose checkpoints keep timing out.
Checkpoint timeouts almost always mean a barrier can't traverse the dataflow in time, usually due to backpressure or large/slow state:
1. **Read the checkpoint UI**: per-operator checkpoint duration, **alignment time**, and async vs sync snapshot duration. High *alignment time* points to backpressure or skew (one input channel lags, stalling barrier alignment).
2. **Locate backpressure**: the backpressure tab shows which operator is the bottleneck (its inputs are blocked). The slow operator is downstream of where you see high buffering.
3. **Common causes & fixes**:
   - **Slow sink** (e.g., a DB that can't keep up): batch writes, increase sink parallelism, or async I/O.
   - **State too large / slow snapshot**: switch to RocksDB with **incremental checkpoints**; enable the **changelog state backend** to decouple checkpoint duration from interval.
   - **Barrier alignment cost under backpressure**: enable **unaligned checkpoints** so barriers overtake buffered data (trades larger checkpoint size for progress).
   - **Skew**: a hot key overloads one subtask — rekey/salt or rebalance.
   - **Insufficient resources / GC pauses**: tune task slots, managed memory, and GC.
4. **Tune intervals**: raise `checkpointTimeout` and set a `minPauseBetweenCheckpoints` so checkpoints don't pile up and starve processing.

The diagnostic principle: a checkpoint timeout is a *symptom*; the root cause is whatever is slowing the pipeline's throughput below its input rate — fix the bottleneck, not the timeout.

#### Q76. [Theory] What is the "dual write" problem, and why is the transactional outbox pattern (with CDC) the canonical fix?
The **dual write problem**: a service must update its database *and* publish an event (e.g., to Kafka) for the same logical action. If these are two separate operations, any failure between them leaves the system inconsistent — the DB committed but the event was lost (downstream never learns), or the event published but the DB write rolled back (phantom event). There's no atomic transaction spanning a DB and a message broker.

The **transactional outbox** fixes it by making the event part of the *same DB transaction*:
1. In one local ACID transaction, write the business change **and** insert the event row into an `outbox` table.
2. A separate relay reads the outbox and publishes to Kafka. The robust relay is **log-based CDC** (Debezium tailing the WAL/binlog of the outbox table) rather than a polling job — it captures every insert in commit order with low latency and at-least-once delivery.
3. Downstream consumers dedup on the event id (idempotent apply), turning at-least-once into effectively-once.

```
TXN { UPDATE orders; INSERT outbox(event) }  -- atomic
outbox --(Debezium CDC)--> Kafka --> consumers (dedup by event_id)
```

This guarantees the event is published **iff** the DB change committed, because they share one transaction. The inverse pattern (**listen-to-yourself**/event-carried state) and **CQRS** read models are built on the same CDC-from-the-log foundation.

#### Q77. [Coding] Write a SQL gaps-and-islands query to detect sessions/streaks in event data, and explain the technique.
A classic: group consecutive events into "islands" (sessions or streaks) separated by gaps. Here, segment a user's events into sessions where a gap > 30 minutes starts a new session.

```sql
WITH flagged AS (
  SELECT
    user_id,
    event_time,
    -- 1 when this event starts a new session (gap > 30 min from previous), else 0
    CASE
      WHEN event_time - LAG(event_time) OVER (
             PARTITION BY user_id ORDER BY event_time)
           > INTERVAL '30' MINUTE
        OR LAG(event_time) OVER (
             PARTITION BY user_id ORDER BY event_time) IS NULL
      THEN 1 ELSE 0
    END AS is_new_session
  FROM events
),
sessions AS (
  SELECT
    user_id,
    event_time,
    -- running sum of the flag = a monotonically increasing session id per user
    SUM(is_new_session) OVER (
      PARTITION BY user_id ORDER BY event_time
      ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
    ) AS session_id
  FROM flagged
)
SELECT
  user_id, session_id,
  MIN(event_time) AS session_start,
  MAX(event_time) AS session_end,
  COUNT(*)        AS events_in_session
FROM sessions
GROUP BY user_id, session_id;
```

The technique: `LAG` compares each row to the previous to detect a gap and emit a **boundary flag**; a **running `SUM` of that flag** assigns a stable, increasing id that is constant within an island and increments at each gap — collapsing "consecutive runs" into groupable ids. This is the standard SQL way to reproduce session-window logic that a streaming engine does natively, and it's the same gaps-and-islands pattern used for streaks, contiguous-date ranges, and state-change intervals.

#### Q78. [Theory] How do you make exactly-once work when the sink does not support transactions? Contrast idempotent-key, two-phase commit, and dedup-store approaches.
When a sink can't join a distributed transaction, you engineer **effectively-once** by making writes safe to repeat:
- **Idempotent key (upsert) approach**: derive a deterministic primary/dedup key from event identity (e.g., `source_partition + offset`, or a business key) and `MERGE`/`UPSERT`. Replays overwrite rather than duplicate. Simplest and most common; requires the sink to support keyed upserts and the key to be truly deterministic across retries.
- **Two-phase commit (2PC) sink**: the sink pre-commits to a staging transaction on each checkpoint and commits on checkpoint completion, tying output to the engine's checkpoint barrier. Gives true exactly-once but requires the sink to support prepared/staged transactions (JDBC XA, Kafka transactions, file-rename-on-commit) and adds latency and operational complexity (orphaned prepared txns after crashes).
- **External dedup store**: maintain a store (RocksDB state, Redis, a dedup table) of processed event ids; on each record, check-and-set before writing. Works for any sink but adds a stateful lookup per record, a TTL/cleanup concern, and its own consistency requirements.

```
no-txn sink choices:
  upsert by deterministic key   -> simplest, needs keyed sink
  2PC / staged-commit           -> true E1, needs prepared txns
  external dedup set            -> universal, extra stateful lookup
```

Senior judgment: prefer idempotent upserts (effectively-once) unless a hard requirement (financial ledgers, non-idempotent side effects like sending money/email) forces true 2PC. "Exactly-once" is usually *at-least-once delivery + idempotent application*.

### 🔴 — extended

#### Q79. [Theory] Compare the CAP/consistency positioning of the core data-platform components (Kafka, a lakehouse table, a warehouse) and how that shapes end-to-end guarantees.
No single consistency model spans the platform, so you must reason per component and compose them:
- **Kafka**: within a partition, a totally ordered, durable log; with `acks=all`+`min.insync.replicas` it is **CP-leaning** (rejects writes when it can't guarantee durability rather than risk loss). Cross-partition there is *no* global order — ordering is a per-key property you design for via partitioning.
- **Lakehouse table (Iceberg/Delta)**: **serializable snapshot isolation** via optimistic concurrency on an atomic metadata pointer swap. Readers see one consistent snapshot; concurrent writers may conflict and retry. Consistency is strong *per table* but there are no multi-table transactions — cross-table atomicity must be engineered.
- **Warehouse (Snowflake/BigQuery)**: ACID, often serializable per statement/transaction, but eventually consistent at the edges (e.g., cross-region replication, result caching, metadata propagation).

The composition insight: end-to-end correctness is **not** a single transaction — it is *per-hop strong guarantees stitched together by deterministic ordering metadata (offset/LSN/event-time), idempotent/upsert application, and an independent reconciliation layer*. You design for "each hop is exactly/effectively-once, and a reconciliation audit catches the residue," because no distributed transaction spans Kafka + lake + warehouse. Staff-level framing: name where each component sits, then explain that the *glue* — idempotency keys, monotonic ordering, and detect-and-correct reconciliation — is what provides the illusion of a globally consistent pipeline.

#### Q80. [Practical] Design the storage and serving layer for a feature store that must guarantee no training/serving skew and point-in-time correctness at scale.
Training/serving skew arises when a feature's value at *training* time differs from its value at *serving* time. The architecture must enforce a single definition and time-correct reads:
1. **One feature definition, two materializations**: define each feature's transformation once (as code/SQL) and compute it for both paths from the same logic, so batch and streaming can't drift. Many teams compute features in the **offline store** (lakehouse) and stream the same logic into the **online store**.
2. **Offline store (training)**: an immutable, append-only event log of feature values *with their valid-from timestamps* in a lakehouse table. Training queries do a **point-in-time (as-of) join**: for each label event at time `t`, fetch the feature value whose validity ≤ `t` — never a value computed after `t` (which would be **future leakage** / label leakage).
3. **Online store (serving)**: a low-latency KV store (Redis/DynamoDB/Cassandra) holding the *latest* feature value per entity, updated by the streaming pipeline. Serving reads the current value with single-digit-ms latency.
4. **Consistency mechanism**: materialize the online store *from the same computed offline values* (or the same streaming job), and log served feature vectors so you can later reconcile training vs serving distributions and detect skew.

```
event log (valid_from) ─┬─ batch -> offline store -> point-in-time join -> training set
   one transform        └─ stream -> online store (latest) -> low-latency serving
log served vectors -> reconcile training vs serving -> skew/drift alerts
```

```sql
-- Point-in-time (as-of) feature join: no value computed after the label time
SELECT l.entity_id, l.label_time, l.label,
       f.feature_value
FROM labels l
JOIN feature_history f
  ON f.entity_id = l.entity_id
 AND f.valid_from <= l.label_time
QUALIFY ROW_NUMBER() OVER (
  PARTITION BY l.entity_id, l.label_time
  ORDER BY f.valid_from DESC) = 1;   -- the most recent value as-of label_time
```

The non-negotiables at scale: point-in-time correctness (no future leakage), a single shared transformation definition, and continuous skew/drift monitoring — because a feature-store bug corrupts the model silently, exactly the failure mode classic data-engineering rigor exists to prevent.

#### Q81. [Theory] What changes in pipeline design when you must support GDPR "right to be forgotten" / hard deletes across an append-only, immutable lake and a replayable Kafka log?
Immutability and "delete this person everywhere" are in direct tension; you engineer deletion without breaking the append-only model:
- **Lakehouse deletes**: use the table format's row-level delete (`DELETE FROM ... WHERE user_id = ?`). In CoW this rewrites affected files; in MoR it writes delete files. Crucially, **time travel and old snapshots still contain the data**, so you must `expire_snapshots`/`VACUUM` past the retention boundary to physically purge it — and document that compliance deletion lags by the retention window unless you force expiry.
- **Crypto-shredding**: the scalable pattern. Encrypt each subject's PII with a per-subject key; to "delete," destroy the key, rendering all ciphertext (including in immutable files, backups, and Kafka) permanently unreadable. This avoids rewriting petabytes and reaches data you can't easily mutate.
- **Kafka**: a retained/compacted log is immutable mid-segment. Options: rely on retention expiry, use **compaction with tombstones** for keyed topics (publish a `null` value for the key, then the compactor removes it after `delete.retention.ms`), or — most robustly — keep PII out of Kafka and store only a reference/token (tokenization), with the PII (and its destroyable key) in a governed store.
- **Propagation & proof**: a deletion request must fan out to every derived copy (warehouse, search index, vector store, caches, backups) — so you need **lineage** to know where the data flowed, and an audit trail proving completion within the legal window.

```
delete request -> {lakehouse row-delete + snapshot expiry, Kafka tombstone/retention,
                   destroy per-subject crypto key, purge derived stores}
  driven by lineage graph + audited completion
```

The staff-level point: immutability is a *design choice for correctness*, not a law of physics — you preserve its benefits while satisfying deletion via crypto-shredding, tokenization, lineage-driven fan-out, and retention discipline, rather than abandoning immutability or hand-deleting rows.

#### Q82. [Practical] Design a zero-downtime migration of a critical pipeline from one table format/engine to another (e.g., Hive tables on Spark to Iceberg on Trino) without breaking consumers.
Treat it as a **strangler-fig dual-run with reconciliation**, never a big-bang cutover:
1. **Establish a contract and golden signals first**: snapshot current outputs and define equivalence checks (row counts, key-level hashes, aggregate totals per partition) so "correct" is measurable before you touch anything.
2. **Backfill into the new format in parallel**: stand up the Iceberg tables and backfill history idempotently (per-partition overwrite), running the *new* pipeline alongside the old without exposing it. Use Iceberg's ability to **migrate in place** (register existing Parquet via `add_files`/snapshot) where possible to avoid re-copying data.
3. **Dual-write / dual-run and reconcile**: run both pipelines for a soak period; an independent job compares old vs new outputs and alerts on any divergence. This catches subtle semantic differences (null handling, timezone, partition transforms) before users see them.
4. **Abstract the read path**: point consumers at a **view/alias or catalog name**, not physical paths, so cutover is a metadata change. Migrate consumers behind the alias.
5. **Canary cutover**: flip a subset of consumers (or one partition/region) to the new tables, monitor, then progressively roll forward. Keep the old pipeline warm as instant rollback.
6. **Decommission deliberately**: only after a clean soak with zero reconciliation drift, freeze and retire the old pipeline, then expire its storage.

```
old pipeline ──> old tables ─┐
                             ├─ reconcile (counts/hashes/totals) -> alias swap (canary -> full)
new pipeline ──> Iceberg ────┘                                     keep old as rollback
```

The leadership signal: migrations of critical infrastructure succeed through *measurable equivalence, parallel running, indirection at the read layer, and reversible canary cutovers* — not courage. You optimize for the ability to detect divergence and roll back instantly, treating data correctness and consumer trust as the hard constraints.

#### Q83. [Theory] What is the difference between a Kafka consumer group rebalance (eager vs cooperative/incremental) and why does the protocol choice matter operationally?
When consumers join/leave a group or partitions change, Kafka redistributes partitions via a **rebalance** coordinated by the group coordinator broker:
- **Eager (stop-the-world) rebalancing**: every consumer revokes *all* its partitions, then the leader reassigns and consumers rejoin. During the revoke→reassign gap, the *entire group stops consuming* — a latency spike proportional to group size. Worse for stateful apps (Kafka Streams) that must reload local state for newly assigned partitions.
- **Cooperative (incremental) rebalancing**: consumers revoke only the partitions that are actually moving, keeping the rest live. Reassignment happens in two phases so most partitions never pause. This dramatically reduces disruption in large groups and during rolling deploys/autoscaling.

```
eager:        all revoke -> group idle -> reassign -> resume   (big pause)
cooperative:  revoke only moving partitions -> others keep consuming  (minimal pause)
```

Operationally it matters because rebalances happen on *every* deploy, scale event, and transient timeout. With eager rebalancing, a rolling restart of N instances triggers N stop-the-world pauses; cooperative rebalancing makes deploys nearly seamless. Tuning `session.timeout.ms`/`heartbeat.interval.ms` and using **static group membership** (`group.instance.id`) further avoids spurious rebalances when a pod restarts quickly.

#### Q84. [Practical] Write a PySpark snippet using `mapInPandas` / Arrow to process partitions efficiently, and explain when this beats a Pandas UDF or built-in.
`mapInPandas` lets you process each partition as a stream of pandas DataFrames (via Arrow), giving full control over batched logic that doesn't fit a single-column UDF:

```python
import pandas as pd
from typing import Iterator

def enrich(batches: Iterator[pd.DataFrame]) -> Iterator[pd.DataFrame]:
    # Optional: set up an expensive resource ONCE per partition, not per row
    # e.g., load a model / open a connection here
    for pdf in batches:
        pdf = pdf[pdf["amount"] > 0]                 # vectorized filter
        pdf["amount_usd"] = pdf["amount"] * 1.08     # vectorized transform
        yield pdf

result = df.mapInPandas(enrich, schema="id long, amount double, amount_usd double")
```

When it wins:
- **Per-partition setup amortization**: you initialize an expensive object (an ML model, a tokenizer, a DB/HTTP client) once per partition and reuse it across all rows in that partition's batches — impossible with a row-at-a-time UDF and awkward with a scalar Pandas UDF.
- **Multi-column / row-shaping logic**: a scalar Pandas UDF returns one Series; `mapInPandas` can read many columns and emit a transformed DataFrame (add/drop columns, filter rows).
- **Still vectorized + Arrow-transferred**, so it avoids the per-row serialization tax of plain Python UDFs.

Prefer a **built-in Spark expression** when one exists (stays in Catalyst/Tungsten codegen, no Python). Reach for `mapInPandas` only when the logic genuinely needs Python libraries or per-partition state; it's the scalable bridge to pandas/ML code without dropping to one-row-at-a-time.

#### Q85. [Theory] How does Z-ordering (multidimensional clustering) improve data skipping, and how does it differ from partitioning and from Iceberg sort orders?
Data skipping works by reading per-file min/max statistics and skipping files that can't match a predicate. Its effectiveness depends on how **clustered** related values are within files:
- **Partitioning** clusters by exact value of low-cardinality columns into directories — great for equality/range on *that* column, useless for others, and prone to explosion on high cardinality.
- **Linear sort** (e.g., `ORDER BY a, b`) clusters tightly on `a`, weakly on `b` (b is only ordered within ties of a), so skipping is good for `a`, poor for `b`.
- **Z-ordering** interleaves the bits of multiple columns into a single space-filling (Z/Morton) curve, so files are clustered *simultaneously* across several dimensions. Min/max ranges per file stay narrow for *all* the Z-ordered columns at once, enabling effective skipping when queries filter on any of them (or combinations).

```
linear sort by (a,b):  tight on a, loose on b
Z-order   by (a,b):    both a and b stay locally clustered per file -> skip on either
```

Use Z-ordering (Delta `OPTIMIZE ... ZORDER BY (a,b,c)`) when a table is queried by several different high-cardinality columns and you can't partition by all of them. Iceberg achieves a similar effect with **sort orders** (including Z-order/space-filling strategies in compaction). The cost: Z-ordering is a full rewrite, so it's a periodic maintenance operation, and it helps only the columns you choose — pick the columns that actually appear in filters.

#### Q86. [Practical] Write a SQL query to detect data freshness/SLA breaches and missing partitions, suitable for a monitoring job.
A freshness and completeness check you can schedule and alert on:

```sql
-- 1) Freshness: is the newest data older than the SLA?
WITH latest AS (
  SELECT MAX(event_time) AS max_event_time,
         MAX(_ingested_at) AS max_ingested_at
  FROM curated.orders
)
SELECT
  max_event_time,
  max_ingested_at,
  TIMESTAMPDIFF(MINUTE, max_ingested_at, CURRENT_TIMESTAMP) AS minutes_since_load,
  CASE WHEN TIMESTAMPDIFF(MINUTE, max_ingested_at, CURRENT_TIMESTAMP) > 90
       THEN 'SLA_BREACH' ELSE 'OK' END AS freshness_status
FROM latest;

-- 2) Completeness: which expected daily partitions are missing in the last 14 days?
WITH expected AS (                         -- generate the calendar of expected days
  SELECT DATEADD(DAY, -seq, CURRENT_DATE) AS dt
  FROM (SELECT EXPLODE(SEQUENCE(0, 13)) AS seq)
),
present AS (
  SELECT DISTINCT event_date AS dt FROM curated.orders
  WHERE event_date >= DATEADD(DAY, -14, CURRENT_DATE)
)
SELECT e.dt AS missing_partition
FROM expected e
LEFT JOIN present p ON e.dt = p.dt
WHERE p.dt IS NULL                          -- expected but absent => gap
ORDER BY e.dt;
```

The pattern: track an **ingestion timestamp** separate from event time so freshness reflects when data actually landed (not when the event happened), and **generate the expected calendar** then anti-join against present partitions to surface gaps. Wrap both in an orchestration check that fails/pages when `freshness_status = 'SLA_BREACH'` or any `missing_partition` rows return — catching the silent "pipeline ran but produced nothing" failures that crash-only alerting misses.

#### Q87. [Theory] Explain consumer lag and how you'd build alerting that distinguishes a slow consumer from a stalled one from a traffic spike.
**Consumer lag** = log-end offset − committed consumer offset, per partition — how many records behind the head a consumer group is. Raw lag alone is ambiguous; you need its *derivative* and context:
- **Stalled consumer**: lag rising while the committed offset is *not advancing* (consumer made no progress). The consume rate ≈ 0 — almost always a crash, deadlock, poison message, or stuck rebalance. Page immediately.
- **Slow consumer**: lag rising while the offset *is* advancing, but slower than the produce rate (consume rate < produce rate sustained). Capacity problem — scale consumers (up to partition count), optimize processing, or check a slow downstream sink.
- **Traffic spike**: lag jumps but consume rate is at/near its max and produce rate spiked transiently; lag then drains once the burst passes. Usually self-healing — alert only if it doesn't drain within an SLA window.

```
metric to derive:  d(lag)/dt, consume_rate, produce_rate, offset_advancing?
stalled:  lag↑, offset flat, consume≈0        -> page now
slow:     lag↑, offset↑ slowly, consume<produce-> scale/optimize
spike:    lag↑ then drains, consume at max     -> watch, alert on time-to-drain
```

Best practice: alert on **time-to-drain / lag-in-seconds** (lag converted to estimated latency via throughput) rather than raw record count, because "10M records behind" means different things at 1k/s vs 1M/s. Tools like Burrow or Kafka exporter + Prometheus evaluate offset-advancement windows to classify these states automatically. The senior point: the actionable signal is the *trend and offset-advancement*, not the instantaneous lag number.

## 🧩 Extended Questions — Set 2: Practical scenarios, troubleshooting & coding

### 🟢 — extended

#### Q88. [Practical] A scheduled job loaded yesterday's data twice and your fact table now has duplicate rows. What do you do right now, and what do you change so it never recurs?
Two phases: remediate, then prevent.

Remediate now:
1. **Stop the bleeding**: pause the DAG so it can't re-run mid-fix.
2. **Quantify the blast radius**: confirm which partition(s) are duplicated and whether downstream consumers already read the bad data.
3. **Rebuild the affected partition idempotently** rather than trying to surgically delete dupes (error-prone). Reprocess the source for that day with `INSERT OVERWRITE PARTITION (dt='2026-06-29')`, which atomically replaces the partition with the correct single copy.

```sql
-- Verify the duplication first
SELECT dt, COUNT(*) AS rows, COUNT(*) - COUNT(DISTINCT order_id) AS dupes
FROM fact_orders
WHERE dt = '2026-06-29'
GROUP BY dt;
```

Prevent recurrence: make the write **idempotent** so a double-run is harmless — `INSERT OVERWRITE PARTITION` or a `MERGE` keyed on `order_id` instead of a blind `INSERT`. The lesson is that the fix for "ran twice" is not "make sure it never runs twice" (you can't guarantee that) — it's "make running twice produce the same result as running once."

#### Q89. [Practical] An analyst says "the dashboard is wrong." Walk through how you'd triage a data-correctness report.
A repeatable triage protocol, narrowing from symptom to root cause:
1. **Reproduce and define "wrong"**: get the exact number they see, the filters, and what they *expected*. "Wrong" is often a definition mismatch (different timezone, includes/excludes refunds), not a bug.
2. **Compare to a source of truth**: run a query straight against the raw/source data for the same slice. If raw matches the dashboard, the logic is right and the expectation is off; if they differ, the pipeline transformed something incorrectly.
3. **Walk the lineage backward**: dashboard → BI semantic layer → mart table → staging → source. Check row counts and a key aggregate at each hop to find where the number diverges.
4. **Check the usual silent killers**: timezone/`date` boundary bugs, a join that fans out (duplicates) or drops rows (inner vs left), a late-data window, a unit/currency mismatch, a stale partition.
5. **Fix at the right layer, backfill idempotently, and add a guard** (a reconciliation check) so the same class of error is caught automatically next time.

The senior move is treating the report as a signal to add a *test*, not just patch a number.

#### Q90. [Coding] Write a SQL query to find rows in a daily load that violate a referential-integrity rule (orphan foreign keys).
A standard pre-publish data-quality check: every `customer_id` in the fact must exist in the dimension.

```sql
-- Orphans: fact rows whose customer_id has no matching dimension row
SELECT f.order_id, f.customer_id
FROM staging_orders f
LEFT JOIN dim_customer d
  ON f.customer_id = d.customer_id
WHERE d.customer_id IS NULL          -- no match in the dimension
  AND f.customer_id IS NOT NULL;     -- distinguish true orphan from missing key
```

If this returns rows, you have orphans. In a pipeline you'd assert the count is zero (fail or quarantine otherwise):

```sql
SELECT COUNT(*) AS orphan_count
FROM staging_orders f
LEFT JOIN dim_customer d ON f.customer_id = d.customer_id
WHERE d.customer_id IS NULL AND f.customer_id IS NOT NULL;
-- pipeline gate: if orphan_count > 0 -> quarantine those rows and alert
```

The anti-join (`LEFT JOIN ... WHERE right IS NULL`) is the canonical "exists in A but not in B" pattern. Decide policy per table: block the publish for a critical dimension, or route orphans to a quarantine table and let valid rows through.

#### Q91. [Practical] Your Airflow DAG shows a task stuck in "running" for hours but nothing is happening. How do you debug it?
A stuck task is usually a resource/dependency problem outside Airflow itself:
1. **Read the task logs** in the UI first — a task truly hung shows no recent log lines; a slow task shows progress. No logs at all often means the worker never actually started it.
2. **Check executor/worker capacity**: with no free slots (pool exhausted, all Celery/K8s workers busy), the task is *queued*, not running — or a zombie. Look at the pool usage and worker health.
3. **Look at what the task triggers**: Airflow usually orchestrates external work (a Spark job, a warehouse query). The Airflow task is "running" because it's *waiting* on that external system — go check the Spark UI / warehouse query history; the hang is there (a lock, a skewed stage, a deadlocked query).
4. **Check for an external lock or sensor**: a `Sensor` in `poke` mode can sit for hours waiting for a file/partition that never arrives — use `reschedule` mode and a `timeout` so it frees the worker slot.
5. **Set `execution_timeout`** on tasks so they fail loudly instead of hanging forever, and use **zombie detection** (heartbeat) to reap dead tasks.

The structural fix: always set `execution_timeout` and use deferrable operators / sensors in reschedule mode so a hang becomes a timeout alert, not an indefinite stall.

#### Q92. [Practical] You need to load a CSV where some rows are malformed (wrong column count, bad types). How do you load the good rows without failing the whole job?
Use the reader's **permissive/bad-record handling** instead of letting one bad row abort the load. In Spark:

```python
df = (spark.read
      .option("header", True)
      .option("mode", "PERMISSIVE")              # don't fail on bad rows
      .option("columnNameOfCorruptRecord", "_corrupt")
      .schema(expected_schema)                    # explicit schema = type enforcement
      .csv("s3://raw/orders/2026-06-30/"))

good = df.filter(df["_corrupt"].isNull()).drop("_corrupt")
bad  = df.filter(df["_corrupt"].isNotNull())      # route to a dead-letter location

bad.write.mode("append").json("s3://quarantine/orders/")
good.write.mode("overwrite").parquet("s3://curated/orders/dt=2026-06-30/")
```

`PERMISSIVE` mode parses what it can and captures unparseable rows in the corrupt-record column instead of throwing. The key principle is **never silently drop bad data** — quarantine it so it's visible, countable, and reprocessable once you (or the source team) fix the cause. `DROPMALFORMED` discards bad rows silently and `FAILFAST` aborts the job; permissive + quarantine gives you both completeness for good rows and an audit trail for bad ones.

#### Q93. [Theory] A query that ran in 2 minutes last month now takes 40 minutes with no code change. What are the likely causes?
Same code getting slower almost always means the *data or environment* changed underneath it:
- **Data volume grew**: the table or a joined source is much larger; a full scan or shuffle now moves far more data. Check row counts/byte sizes over time.
- **Small-files accumulation**: a streaming or frequent-append source piled up thousands of tiny files, so planning and listing dominate. Needs compaction.
- **Statistics went stale**: the optimizer's cost-based choices (join order, broadcast vs shuffle) are based on outdated stats and now pick a bad plan. Re-`ANALYZE`/recompute table statistics.
- **Skew emerged**: a key that used to be balanced is now dominated by one value (a new whale customer, a flood of `null`s), creating a straggler.
- **Partition pruning broke**: a predicate or partition scheme changed so the query stopped pruning and now scans everything.
- **Resource contention**: another heavy job now shares the cluster/warehouse; you're queueing or starved of memory.

Diagnose with the query plan and history: compare the *current* plan and bytes-scanned against a baseline. The first three (volume, small files, stale stats) are the most common in practice.

#### Q94. [Coding] Write a Python script that reads from an API with pagination and writes results to newline-delimited JSON, handling rate limits.
A common ingestion pattern — paginate a REST API defensively and stream to NDJSON (one record per line, append-friendly and splittable).

```python
import json, time
import requests

def fetch_all(base_url, out_path, page_size=500):
    session = requests.Session()
    page, written = 1, 0
    with open(out_path, "w", encoding="utf-8") as f:
        while True:
            resp = session.get(
                base_url,
                params={"page": page, "per_page": page_size},
                timeout=30,
            )
            # Respect rate limiting: back off and retry on 429
            if resp.status_code == 429:
                wait = int(resp.headers.get("Retry-After", "5"))
                time.sleep(wait)
                continue
            resp.raise_for_status()
            records = resp.json().get("data", [])
            if not records:                 # empty page => done
                break
            for r in records:
                f.write(json.dumps(r) + "\n")   # NDJSON: one object per line
                written += 1
            page += 1
    return written
```

Key practices: use a `Session` for connection reuse, honor `Retry-After` on HTTP 429, set a `timeout` so a hung request can't stall ingestion forever, and write **newline-delimited JSON** so downstream Spark/warehouse loaders can split and parallelize the file. For production, add retry-with-exponential-backoff on 5xx and checkpoint the last successful page so a crash resumes instead of restarting.

#### Q95. [Practical] Your incremental load uses `WHERE updated_at > last_run` but rows are occasionally missing. Why, and how do you fix it?
The classic **watermark race / late commit** problem. `updated_at` is typically set at *row modification* time, but the row becomes *visible to your query* at *transaction commit* time. A transaction that sets `updated_at = 10:00` but commits at 10:05 — after your 10:02 extract ran with watermark `> 10:00` — will be skipped: its timestamp is below your next watermark, but it wasn't visible when you read.

Fixes:
- **Lookback overlap**: extract `WHERE updated_at > (last_run - safety_margin)` (e.g., 15 minutes) and rely on **idempotent upserts** so re-reading already-processed rows is harmless. This re-captures rows that committed late.
- **Use a commit-ordered marker** instead of wall-clock `updated_at` where available: a database-assigned monotonic LSN/SCN or an auto-increment that reflects commit order, not modification time.
- **CDC** (log-based) sidesteps it entirely — the WAL/binlog is in commit order, so you never miss a committed change and you also catch deletes (which `updated_at` polling misses).

The root principle: incremental watermarking on a *modification* timestamp is unsafe under concurrency; combine an overlapping window with idempotent merges, or move to log-based CDC.

#### Q113. [Coding] Write a SQL query to pivot daily event counts by type into one row per day with a column per event type.
A common reshaping for reporting — turn long (one row per event) into wide (one column per type) using conditional aggregation.

```sql
SELECT
  event_date,
  SUM(CASE WHEN event_name = 'view'     THEN 1 ELSE 0 END) AS views,
  SUM(CASE WHEN event_name = 'add_cart' THEN 1 ELSE 0 END) AS add_carts,
  SUM(CASE WHEN event_name = 'purchase' THEN 1 ELSE 0 END) AS purchases,
  COUNT(*)                                                 AS total_events
FROM events
WHERE event_date >= DATE '2026-06-01'
GROUP BY event_date
ORDER BY event_date;
```

The technique is **conditional aggregation** (`SUM(CASE WHEN ...)`) — portable across every SQL engine, unlike the `PIVOT` keyword which exists only in some (Spark SQL, SQL Server, Snowflake). Each `CASE` counts only the matching event type within the `GROUP BY event_date`. The trade-off: you must hard-code the event types as columns, so it's brittle if types are dynamic — for an unknown/large set of types, keep it long and let the BI tool pivot, or generate the SQL dynamically.

#### Q114. [Practical] You're handed a 50 GB gzipped CSV in S3 and your Spark job only uses one executor for the read. Why, and how do you fix it?
**Gzip is not splittable.** Spark parallelizes file reads by splitting a file into chunks, but gzip can only be decompressed from the start, so the entire 50 GB file must be read by a *single task* on one executor — the rest of the cluster sits idle. This is a classic throughput killer.

Fixes:
- **Repartition immediately after reading** so downstream stages parallelize even though the read itself can't: `df = spark.read.csv(path).repartition(200)`. The read is still single-threaded, but the transform/write isn't.
- **Better: re-encode the source into a splittable format.** Convert to Parquet (splittable, columnar, compressed) or to many smaller files, or use a splittable codec like **bzip2** (slow) or, ideally, store as Parquet with Snappy/Zstd. Then reads parallelize natively.
- **Split at the source**: if you control producers, write many gzip files (each read by its own task in parallel) instead of one giant gzip.

```python
# Read can't parallelize on one gzip, but force parallelism downstream:
df = spark.read.option("header", True).csv("s3://raw/huge.csv.gz").repartition(200)
# One-time fix: land it as splittable Parquet for all future jobs
df.write.mode("overwrite").parquet("s3://curated/huge/")
```

The principle: **compression codec splittability** determines read parallelism. gzip/Snappy-on-CSV are non-splittable; Parquet (and bzip2, LZO-with-index) are splittable. The durable fix is to convert raw gzip CSV into Parquet once at ingest so every subsequent job parallelizes the read.

### 🟡 — extended

#### Q96. [Practical] A Kafka consumer group is lagging badly during peak hours but you already have one consumer per partition. How do you scale further?
You've hit the structural ceiling: **parallelism is capped by partition count**, so adding consumers does nothing once you have one per partition. Options, roughly in order:
1. **Increase partitions** (and rebalance the load). This is the direct lever, but it's somewhat one-way (you can't easily reduce them) and it changes key→partition mapping, which can disrupt ordering and stateful processing — plan it.
2. **Make each consumer faster** so it processes more per second: batch the downstream writes, use async I/O to the sink, remove per-record blocking calls, and profile the hot path. Often the consumer is slow, not under-parallelized.
3. **Decouple consume from process**: have the consumer quickly hand records to a worker pool / async pipeline so a slow downstream doesn't gate polling (carefully, to preserve ordering and offset-commit correctness).
4. **Reduce work**: filter/aggregate earlier, compress, or move expensive enrichment off the hot path.
5. **Check for skew**: if one partition is hot (a bad key choice routes most traffic to it), repartition with a better key — adding consumers won't help a single hot partition.

The senior insight: lag at "one consumer per partition" is either a *throughput-per-consumer* problem (optimize the consumer) or a *partitioning* problem (more/better-distributed partitions) — diagnose which before reflexively adding partitions.

#### Q97. [Coding] Write a PySpark job that detects and quarantines rows failing multiple data-quality rules in one pass.
Evaluate all rules, tag each row with which checks it failed, then split clean vs dirty — efficient because it's a single scan.

```python
from pyspark.sql import functions as F

rules = {
    "null_customer":   F.col("customer_id").isNull(),
    "negative_amount": F.col("amount") < 0,
    "bad_email":       ~F.col("email").rlike(r"^[^@]+@[^@]+\.[^@]+$"),
    "future_date":     F.col("order_date") > F.current_date(),
}

# Build an array of the names of violated rules per row
# array_remove(array, None) does NOT strip nulls (it returns NULL for the whole array);
# use array_compact (Spark 3.4+) to drop the nulls left by the un-triggered when() branches.
violations = F.array_compact(
    F.array(*[F.when(cond, F.lit(name)) for name, cond in rules.items()])
)

tagged = df.withColumn("dq_violations", violations)

clean = tagged.filter(F.size("dq_violations") == 0).drop("dq_violations")
dirty = tagged.filter(F.size("dq_violations") > 0)

# Quarantine with the reasons attached for triage
dirty.write.mode("append").json("s3://quarantine/orders/dt=2026-06-30/")
clean.write.mode("overwrite").parquet("s3://curated/orders/dt=2026-06-30/")

# Emit metrics for monitoring/alerting
(dirty.select(F.explode("dq_violations").alias("rule"))
      .groupBy("rule").count().show())
```

One pass evaluates every rule, the `array` + `array_compact` trick collects only the violated rule names — `when()` yields `NULL` for rules that pass, and `array_compact` drops those nulls (note: `array_remove(arr, None)` would *not* work, since removing a `NULL` element returns `NULL` for the whole array). The quarantined rows carry *why* they failed — so triage doesn't require re-deriving the cause. The final `explode` + `groupBy` gives per-rule failure counts you can trend and alert on.

#### Q98. [Practical] A dbt model that builds a large table is slow and re-runs from scratch every time. How do you make it incremental?
Convert it to an **incremental materialization** so each run only processes new/changed rows instead of a full rebuild:

```sql
{{ config(materialized='incremental', unique_key='order_id',
          incremental_strategy='merge') }}

SELECT order_id, customer_id, amount, updated_at
FROM {{ source('raw', 'orders') }}
{% if is_incremental() %}
  -- only rows newer than what's already in this table
  WHERE updated_at > (SELECT MAX(updated_at) FROM {{ this }})
{% endif %}
```

Key elements:
- `materialized='incremental'` + `is_incremental()` guard so the `WHERE` filter applies only on incremental runs (the first run builds the whole table).
- `unique_key` + `incremental_strategy='merge'` so changed rows **upsert** rather than duplicate — handling updates, not just appends.
- A safety **lookback** (`updated_at > MAX(updated_at) - INTERVAL '1' DAY`) plus the merge to absorb late-committing rows (same watermark-race issue as raw SQL incrementals).

Operational notes: keep a `--full-refresh` path for backfills/schema changes, and ensure the source reliably updates `updated_at` (or use CDC). Incremental models trade simplicity for speed/cost — use them for large append-mostly tables, not small dimensions.

#### Q99. [Theory] A streaming job's output is correct but lags further behind real time every hour (growing latency). What causes "creeping lag" and how do you fix it?
Correct-but-creeping latency means the job's **throughput is slightly below its input rate**, so the deficit accumulates:
- **Sustained under-provisioning**: consume/process rate < produce rate by even a few percent → unbounded lag growth. Scale parallelism (up to partition count) or speed up the bottleneck operator.
- **Slow/blocking sink**: a downstream DB or API that can't keep up applies backpressure all the way to the source; the whole pipeline runs at the sink's speed. Batch writes, async I/O, or increase sink parallelism.
- **Growing state / checkpoint cost**: as keyed state grows, each checkpoint takes longer and steals throughput — switch to RocksDB + incremental checkpoints, add state TTL, or use the changelog backend.
- **GC pressure / memory**: rising heap pressure causes longer pauses over time; tune memory/GC.
- **Data growth or skew drift**: traffic grew, or a key became hot, pushing one subtask over capacity.

Diagnose via the engine's backpressure and throughput metrics: find the operator whose input buffers are saturated. The principle: creeping lag is a *steady-state capacity deficit*, not a one-off spike — you must raise sustained throughput above the sustained input rate, not just clear the current backlog.

#### Q100. [Coding] Write a SQL query to compute a sessionized funnel: of users who did step A, how many later did step B within 1 hour?
A funnel/conversion query combining a self-join with a time constraint.

```sql
WITH step_a AS (
  SELECT user_id, MIN(event_time) AS a_time
  FROM events WHERE event_name = 'view_product'
  GROUP BY user_id
),
step_b AS (
  SELECT user_id, event_time AS b_time
  FROM events WHERE event_name = 'add_to_cart'
)
SELECT
  COUNT(DISTINCT a.user_id)                         AS did_a,
  COUNT(DISTINCT CASE WHEN b.user_id IS NOT NULL
                      THEN a.user_id END)           AS did_a_then_b,
  ROUND(100.0 * COUNT(DISTINCT CASE WHEN b.user_id IS NOT NULL
                                    THEN a.user_id END)
              / NULLIF(COUNT(DISTINCT a.user_id), 0), 2) AS conversion_pct
FROM step_a a
LEFT JOIN step_b b
  ON b.user_id = a.user_id
 AND b.b_time > a.a_time                            -- B must come AFTER A
 AND b.b_time <= a.a_time + INTERVAL '1' HOUR;      -- within the window
```

The technique: anchor on each user's first step-A time, then `LEFT JOIN` step B with both an *ordering* constraint (`b_time > a_time`) and a *window* constraint (within 1 hour). The `LEFT JOIN` keeps non-converters so the denominator is "everyone who did A." `NULLIF(..., 0)` guards against divide-by-zero. For multi-step funnels, chain this pattern step by step, carrying the timestamp forward as each step's anchor.

#### Q101. [Practical] How do you backfill a single corrected day into a partitioned table that's being actively written to by a streaming job, without downtime or double-counting?
The challenge is correcting historical partition `dt=D` while a streaming job appends to *current* partitions — you must not corrupt live writes or create duplicates:
1. **Compute the correction in isolation**: build the fixed data for `dt=D` in a staging table/path, fully validated, before touching the live table.
2. **Use the table format's atomic operation**: with a lakehouse format (Iceberg/Delta), `REPLACE`/overwrite *only* partition `D` in a single atomic commit (`INSERT OVERWRITE ... WHERE dt = 'D'` or a partition-scoped replace). Snapshot isolation means readers see either the old or new partition, never a half-written mix, and the streaming writes to *other* partitions are untouched.
3. **Ensure the streaming job won't re-touch D**: confirm its watermark/window has long since closed `D` so it isn't still emitting late updates into that partition (if it might, coordinate a brief overlap or write late data to a reconciliation table).
4. **Idempotency makes it safe to retry**: the overwrite is deterministic, so a failed backfill simply reruns.

```sql
-- Atomic, partition-scoped correction; concurrent writes to other dt's are unaffected
INSERT OVERWRITE TABLE fact_orders
SELECT * FROM staging_fixed_day WHERE dt = '2026-06-15';
```

The enabling property is **partition-level atomic overwrite on an ACID table format** — it's what lets you surgically replace one day while a streaming job concurrently appends elsewhere. On a plain Hive directory table without ACID, you'd risk readers seeing a partial state, so you'd write-to-new-path-and-swap instead.

#### Q102. [Theory] Two engineers get different row counts running "the same" query against the lakehouse table. What are the plausible causes?
Non-deterministic or inconsistent counts usually trace to *what snapshot/state each query saw*, not the SQL:
- **Different snapshots (time)**: a concurrent write committed between the two runs, so one read an older snapshot. Lakehouse snapshot isolation means each query pins one snapshot; runs seconds apart can differ. Pin a specific snapshot/version (time travel) to compare apples to apples.
- **Caching**: one engine served a cached result (warehouse result cache, metadata cache) from before the latest commit while the other recomputed.
- **Uncompacted delete files (MoR)**: if one engine doesn't correctly apply positional/equality delete files (older reader/version), it counts logically-deleted rows the other excludes.
- **Different engine/version semantics**: one engine handles `NULL`s, duplicate keys, or partition filters slightly differently, or has a known reader bug.
- **Hidden filters / different session settings**: a default partition filter, row-level security/governance policy, or a session timezone changing a `date` boundary.

Diagnose by pinning both to the *same snapshot id* and the *same engine*, then comparing. If they still differ, it's a semantic/version bug; if they converge, it was concurrency or caching. This is exactly why immutable, versioned snapshots exist — reproducibility requires reading the *same* version.

#### Q115. [Coding] Write a PySpark snippet that safely handles a join that's silently fanning out (producing more rows than expected).
Row explosion from a join is a top silent-correctness bug: if the "lookup" side has duplicate keys, an inner join multiplies fact rows. Detect and prevent it.

```python
from pyspark.sql import functions as F

# 1) DETECT: is the dimension side actually unique on the join key?
dup_keys = (dim.groupBy("customer_id").count().filter(F.col("count") > 1))
if dup_keys.head(1):                       # non-empty => duplicates exist
    dup_keys.show()                        # surface the offending keys
    raise ValueError("Dimension is not unique on customer_id; join will fan out.")

# 2) PREVENT: dedup the dimension to one row per key BEFORE joining
dim_dedup = (dim
    .withColumn("rn", F.row_number().over(
        Window.partitionBy("customer_id").orderBy(F.col("updated_at").desc())))
    .filter(F.col("rn") == 1).drop("rn"))

result = fact.join(dim_dedup, "customer_id", "left")   # left join preserves fact grain

# 3) ASSERT the grain is preserved (output rows == fact rows for a left join)
assert result.count() == fact.count(), "Join changed row count — fan-out!"
```

The discipline: a join should never change the grain of the driving (fact) table unless you *intend* a one-to-many expansion. Always know the cardinality of each side; if the lookup side isn't unique on the key, dedup it first (keeping the correct version) or the join silently multiplies your metrics. The post-join `count` assertion is a cheap guard that catches fan-out before bad numbers reach a dashboard.

### 🟠 — extended

#### Q103. [Practical] Design an alerting strategy for a critical pipeline that catches "ran but produced wrong/no data" — the failures crash-only monitoring misses.
Crash alerts catch hard failures; the dangerous failures are *successful runs with bad output*. Layer detection:
1. **Freshness SLAs**: alert if the newest data is older than expected (the pipeline didn't run, or ran and wrote nothing). Track ingestion timestamp, not event time.
2. **Volume/row-count anomalies**: compare today's row count to a trailing baseline (e.g., week-over-week, same weekday); alert on a large drop *or* spike, not just zero. "Produced 10% of normal" is a silent failure.
3. **Reconciliation against source-of-truth**: independently recompute key totals (revenue, counts) from the immutable source and compare to the serving table; alert on drift beyond a tolerance. This catches subtle logic bugs that pass volume checks.
4. **Distribution/null-rate drift**: monitor null rates and key distributions; a column suddenly 80% null signals an upstream schema/join break.
5. **Business-invariant checks**: assertions that must always hold (`sum(line_items) = order_total`, no negative quantities, PK uniqueness).
6. **Tiered routing**: page on reconciliation/SLA breaches for tier-1 tables; ticket/Slack for lower tiers — avoid alert fatigue.

```
crash alert          -> "job failed"            (necessary, not sufficient)
freshness + volume   -> "ran but produced little/none"
reconciliation/drift -> "ran, looks fine, but is subtly wrong"
```

The senior framing: monitor **outputs and invariants**, not just process liveness. The worst data incidents are green dashboards with wrong numbers — only reconciliation and anomaly detection surface those before the business does.

#### Q104. [Practical] A nightly Spark job intermittently fails (~1 in 5 runs) with different errors each time. How do you approach a flaky distributed job?
Intermittent, varying failures point to **non-determinism, resource contention, or transient infrastructure** rather than a code logic bug:
1. **Collect and cluster the failures**: gather logs/stack traces across runs. Even "different errors" often share a root (e.g., OOM manifesting as shuffle-fetch failures, executor lost, and timeouts).
2. **Suspect skew/data-dependent paths**: if the input differs slightly each night, a borderline-skewed partition tips one subtask into OOM only some nights. Check task-level metrics on failed runs for a straggler. Fix with AQE, salting, better partition sizing.
3. **Resource contention**: the job fails when it overlaps another heavy job and loses the memory/slot race. Isolate it (dedicated pool/cluster) or schedule to avoid contention.
4. **Transient infra**: spot-instance preemption, a flaky S3/network call, a shuffle service hiccup. Add **retries with backoff** at the task level and idempotent writes so a retried/rerun job is safe.
5. **Timeouts too tight**: a run that's occasionally slow (data growth, GC) breaches a timeout. Right-size timeouts and the bottleneck.
6. **Reproduce by amplifying**: run against the largest/most-skewed recent input, or with reduced resources, to make the rare failure deterministic.

The principle: flakiness in distributed jobs is rarely random — it's a *threshold* effect (data near a skew/memory boundary, or contention) that crosses the line only sometimes. Find the threshold, then add margin and idempotent retries so transient crossings self-heal.

#### Q105. [Coding] Write a SQL query to reconcile two tables and report mismatched aggregates per key (a reconciliation/audit check).
A reconciliation job compares an independently-computed source total against the serving table and flags divergence per group.

```sql
WITH src AS (
  SELECT region, DATE(event_time) AS dt,
         SUM(amount) AS src_revenue, COUNT(*) AS src_rows
  FROM raw_events
  WHERE status = 'COMPLETED'
  GROUP BY region, DATE(event_time)
),
serving AS (
  SELECT region, dt, revenue AS svc_revenue, row_count AS svc_rows
  FROM curated_daily_revenue
)
SELECT
  COALESCE(s.region, t.region)            AS region,
  COALESCE(s.dt, t.dt)                    AS dt,
  s.src_revenue, t.svc_revenue,
  s.src_revenue - t.svc_revenue           AS revenue_diff,
  s.src_rows    - t.svc_rows              AS row_diff
FROM src s
FULL OUTER JOIN serving t
  ON s.region = t.region AND s.dt = t.dt
WHERE s.src_revenue IS DISTINCT FROM t.svc_revenue   -- value mismatch
   OR s.region IS NULL                                -- present only in serving
   OR t.region IS NULL                                -- present only in source
ORDER BY ABS(COALESCE(s.src_revenue,0) - COALESCE(t.svc_revenue,0)) DESC;
```

The pattern: a **`FULL OUTER JOIN`** on the grain so you catch keys present in one side but not the other (missing/extra groups), and `IS DISTINCT FROM` to compare values *including* `NULL`s safely. Ordering by the absolute difference surfaces the biggest discrepancies first. Scheduled as a check, any returned row is a reconciliation breach that should page for tier-1 data — this is the independent audit that catches the silent logic bugs per-hop guarantees miss.

#### Q106. [Practical] Your Iceberg/Delta table's query performance has degraded and storage costs ballooned. What maintenance has been neglected?
Lakehouse tables need ongoing maintenance; skipping it degrades both performance and cost:
- **No file compaction**: frequent/streaming writes left thousands of small files, so query planning and reads slowed. Run `OPTIMIZE` (Delta) / `rewrite_data_files` (Iceberg) to bin-pack to ~128 MB–1 GB files.
- **No snapshot/version expiry**: every write creates a snapshot and *retains old data files* for time travel. Without `expire_snapshots` (Iceberg) / `VACUUM` (Delta), obsolete files accumulate forever — that's the ballooning storage. Expire past your time-travel retention window.
- **No manifest/metadata compaction**: Iceberg manifests (or the Delta log) bloat, slowing planning. `rewrite_manifests` / Delta checkpointing compacts metadata.
- **No orphan-file cleanup**: failed writes leave files not referenced by any snapshot; `remove_orphan_files` reclaims them.
- **Missing clustering**: data-skipping degraded because related values aren't co-located; periodic `ZORDER`/sort-based compaction restores skipping.

```
neglected maintenance -> symptoms
  no compaction      -> small files -> slow planning/reads
  no snapshot expiry -> retained old files -> storage cost balloon
  no manifest rewrite-> bloated metadata -> slow planning
  no orphan cleanup  -> unreferenced files -> wasted storage
```

The operational lesson: a lakehouse table is not "write and forget." Schedule compaction, snapshot expiry, manifest rewrite, and orphan cleanup as routine maintenance — and tune retention so time-travel needs don't silently 5x your storage bill.

#### Q107. [Theory] After a deploy, a Kafka Streams app starts reprocessing from the beginning of topics, re-emitting old results. What likely happened?
Reprocessing-from-start means the app's **committed offsets / state were lost or reset**:
- **Consumer group / application.id changed**: Kafka Streams derives its consumer group from `application.id`. A changed (or accidentally new) `application.id` is a brand-new group with no committed offsets, so it starts at `auto.offset.reset` (often `earliest`) — reprocessing everything.
- **Offsets expired or were deleted**: `__consumer_offsets` retention elapsed while the app was down longer than `offsets.retention.minutes`, so committed offsets vanished and it reset to `earliest`.
- **Local state stores wiped**: the RocksDB state dir was lost (ephemeral pod storage, cleared volume), forcing a **changelog restore** — which replays the changelog topics. With a new app id this compounds into full reprocessing.
- **A reset was run**: someone ran the application reset tool (or set `auto.offset.reset=earliest` *and* offsets were absent).

```
new application.id        -> no committed offsets -> earliest -> full reprocess
offsets retention expired -> offsets gone         -> earliest -> full reprocess
state dir lost            -> changelog restore (re-reads changelog)
```

Prevention: keep `application.id` stable across deploys, persist the state directory (or accept changelog-restore time), set `offsets.retention.minutes` generously for apps that may pause, and make downstream sinks idempotent so an accidental reprocess re-emits without duplicating. The deploy itself should never change the identity that anchors offsets and state.

#### Q116. [Practical] You must reprocess 6 months of streaming data after fixing a bug, while the live stream keeps running. Design the reprocessing without double-counting or downtime.
This is the **Kappa reprocessing** pattern — replay history through corrected code without disrupting the live serving path:
1. **Replay from the retained log into a NEW table version**, not the live one. Spin up the fixed job as a *separate* deployment reading from the Kafka offset 6 months back (the log must have that retention — otherwise replay from an archived copy in the lakehouse). It writes to `events_v2` while the live job keeps writing `events_v1`.
2. **Decouple the reprocessing job's identity**: a distinct consumer group / `application.id` and distinct sink so it can't disturb live offsets or state.
3. **Make the write idempotent and deterministic**: key the output so replaying the same events produces the same rows (upsert by event id), so a restart of the backfill doesn't double-count.
4. **Reconcile then atomically swap**: validate `events_v2` against reconciliation totals and against `events_v1` for the overlap region, then flip consumers via a **catalog alias/view** to `events_v2` in one metadata change. Keep `v1` as instant rollback.
5. **Handle the seam**: define a clean cutover timestamp so the new table covers history up to "now" and the live job continues forward from the same boundary — no gap, no overlap double-count.

```
Kafka log (6mo retention) ─┬─ live job  -> events_v1 (serving via alias)
   replay from old offset  └─ fixed job -> events_v2 -> reconcile -> alias swap
                                   distinct group.id + idempotent upsert
```

The enabling properties are **log replayability** (long retention or an archived event log), **idempotent keyed writes** (replay is safe), and **read-path indirection** (alias swap = zero-downtime cutover). This is precisely why Kappa architectures keep a long-retention immutable log: reprocessing is "replay the same code over the same events into a new table, validate, swap" — not a parallel permanent pipeline.

#### Q108. [Coding] Write a Python function that validates a batch against a schema and expectations, returning a pass/fail report (Great Expectations-style, but hand-rolled).
A lightweight, dependency-free validator illustrating what a data-quality framework does under the hood.

```python
from dataclasses import dataclass, field

@dataclass
class CheckResult:
    name: str
    passed: bool
    detail: str = ""

def validate_batch(df, expectations) -> tuple[bool, list]:
    """df: pandas DataFrame; expectations: list of (name, fn) -> CheckResult."""
    results = [fn(df) for name, fn in expectations]
    all_passed = all(r.passed for r in results)
    return all_passed, results

# --- expectation builders ---
def expect_not_null(col):
    def check(df):
        n = int(df[col].isnull().sum())
        return CheckResult(f"not_null:{col}", n == 0, f"{n} nulls")
    return ("not_null:" + col, check)

def expect_unique(col):
    def check(df):
        dupes = int(df[col].duplicated().sum())
        return CheckResult(f"unique:{col}", dupes == 0, f"{dupes} duplicates")
    return ("unique:" + col, check)

def expect_between(col, lo, hi):
    def check(df):
        bad = int(((df[col] < lo) | (df[col] > hi)).sum())
        return CheckResult(f"between:{col}", bad == 0, f"{bad} out of [{lo},{hi}]")
    return ("between:" + col, check)

# --- usage in a pipeline gate ---
expectations = [
    expect_not_null("order_id"),
    expect_unique("order_id"),
    expect_between("amount", 0, 1_000_000),
]
ok, report = validate_batch(batch_df, expectations)
for r in report:
    print(f"[{'PASS' if r.passed else 'FAIL'}] {r.name} ({r.detail})")
if not ok:
    raise ValueError("Data quality gate failed; blocking publish.")
```

The design mirrors real DQ tools: **expectations are composable functions** returning structured results, the runner aggregates a pass/fail, and a failed gate raises to *block the publish* (or you'd branch to quarantine). The structured `CheckResult` (name + detail + pass) is what feeds dashboards and alerts. In production you'd use Great Expectations/Soda/dbt tests, but understanding this shape is what lets you debug and extend them.

### 🔴 — extended

#### Q109. [Practical] A subtly wrong number propagated through 40 downstream tables before anyone noticed. Design the response and the systemic fix.
This is the nightmare scenario — a silent error with wide blast radius. Response in order:
1. **Contain**: freeze the affected pipelines so the bad value stops propagating further, and snapshot current state for forensics.
2. **Map the blast radius via lineage**: use column-level lineage (dbt docs, OpenLineage/Marquez, a catalog) to enumerate exactly which of the 40 tables, dashboards, and consumers derived from the bad source and over what date range. You cannot fix what you can't enumerate.
3. **Fix at the source, then cascade idempotent backfills** in dependency order: correct the root table, then re-derive each downstream table with idempotent per-partition overwrites so the correction flows deterministically. Validate at each layer against reconciliation totals.
4. **Communicate transparently**: notify every affected consumer with scope, magnitude, and corrected-by time — silence destroys trust more than the bug did.

Systemic fixes (the real deliverable):
- **Reconciliation and anomaly detection** on tier-1 tables so the next silent error is caught in hours, not weeks.
- **Column-level lineage** as standing infrastructure so blast-radius mapping is instant, not archaeological.
- **Data contracts** on the source interface so the assumption that broke is now an enforced, tested invariant.
- **A blameless postmortem** that produces a test reproducing the bug, ensuring this *class* of error fails the pipeline forever after.

The staff-level signal: you treat a single bad number as a **systems failure** — the problem isn't the bug, it's that nothing detected it for weeks and nothing told you what it touched. You invest in detection (reconciliation), navigability (lineage), and prevention (contracts/tests), turning a crisis into durable resilience.

#### Q110. [Practical] Design a cost-optimization initiative for a data platform whose cloud bill is growing faster than data volume. Where do you look and what do you change?
Cost growing *faster* than data signals inefficiency, not just scale. Attack it systematically:
1. **Attribute cost first** (you can't optimize what you can't see): tag jobs/tables/teams, and break the bill into storage, compute (query/processing), and data transfer. Find the top 20% of spend.
2. **Storage**: neglected **snapshot/version retention** (un-expired lakehouse snapshots, old backups) is a classic silent balloon — set retention and expire. Tier cold data to cheaper storage; compact small files (which also waste request cost); drop unused/duplicate tables surfaced by lineage (zero-read tables).
3. **Compute**: find expensive recurring queries (warehouse query history) — full scans that should prune, missing partition filters, `SELECT *`, repeated recomputation that should be materialized incrementally. Convert full reloads to incremental. Right-size warehouses/clusters and enable auto-suspend.
4. **Scan reduction**: partitioning/clustering and columnar pruning directly cut pay-per-scan cost; one un-pruned dashboard query run thousands of times a day is often a top line item.
5. **Eliminate redundant pipelines**: data mesh/sprawl breeds duplicate copies of the same data; consolidate to shared tables (open lakehouse, one copy, many engines).
6. **Governance**: budgets/alerts per team, a chargeback model so owners feel their cost, and a review gate for new always-on streaming jobs (real-time has a standing cost).

The senior framing: a bill growing faster than volume is almost always *retention you never set*, *queries that don't prune*, *full reloads that should be incremental*, and *duplicated data* — visibility (cost attribution + lineage) comes first, because optimizing blindly wastes effort on the wrong 80%.

#### Q111. [Theory] Critically assess "data observability" platforms (Monte Carlo, etc.). What do they actually solve, and where do teams over-rely on them?
**Data observability** tools auto-monitor freshness, volume, schema, and distribution, and use lineage to map incidents — automating the anomaly detection and blast-radius mapping you'd otherwise hand-build.

What they genuinely solve:
- **Coverage at scale**: ML-driven anomaly detection across thousands of tables that no team could instrument by hand, catching freshness/volume/schema/distribution drift automatically.
- **Faster incident response**: lineage-based blast-radius mapping and alerting shrink time-to-detection and time-to-resolution for the *silent* failures crash-monitoring misses.
- **A shared trust signal**: surfaced SLAs and health put data reliability on a dashboard the business can see.

Where teams over-rely on them:
- **They detect, they don't fix**: an alert without idempotent pipelines, ownership, and contracts just tells you you're broken faster. The fix is still your engineering discipline.
- **Generic anomaly detection ≠ business-invariant checks**: statistical drift alerts miss domain-specific correctness (`sum(line_items)=order_total`, currency logic) and produce noise/false positives that cause alert fatigue. You still need hand-written invariant tests.
- **Garbage lineage in, garbage incident maps out**: their value depends on accurate lineage; gaps (opaque UDFs, external systems) leave blind spots teams wrongly trust.
- **No substitute for data contracts at the source**: detecting a producer's breaking change after the fact is worse than preventing it with an enforced contract.

Staff-level judgment: observability platforms are a *force multiplier on top of* good data engineering, not a replacement for it. Buy them to scale detection and lineage, but only after (or alongside) building idempotency, contracts, ownership, and invariant tests — otherwise you've bought a very expensive way to learn you're broken.

#### Q112. [Practical] Design an end-to-end lineage and impact-analysis capability across SQL transforms, Spark jobs, and BI tools. What's hard and how do you make it reliable?
Goal: given any column, know its upstream sources and every downstream consumer — for debugging, blast-radius, compliance, and cost. Architecture:
1. **Capture lineage at the right granularity**: aim for **column-level**, not just table-level, because impact analysis needs to know *which column* broke. Sources of lineage:
   - **SQL/dbt**: parse the SQL (dbt exposes the DAG and, increasingly, column lineage via parsing) to derive table- and column-level edges automatically.
   - **Spark**: emit lineage events via a runtime agent (e.g., the OpenLineage Spark listener) so jobs report inputs/outputs/columns as they run, rather than relying on fragile static parsing of arbitrary code.
   - **BI**: integrate the BI tool's metadata API to map dashboards/reports to their source tables/columns.
2. **Standardize on an open lineage model** (OpenLineage) feeding a metadata store/catalog (Marquez, DataHub, Unity/OpenMetadata) so heterogeneous tools emit to one graph.
3. **Expose impact analysis**: a query/UI answering "if I change column X, what breaks?" (downstream traversal) and "where did this bad value come from?" (upstream traversal).

What's hard (and the reliability strategy):
- **Opaque transforms**: UDFs, dynamic SQL, and code-based Spark logic resist static parsing — prefer **runtime-emitted** lineage (observe actual reads/writes) over parsing, and accept some manual annotation for black boxes.
- **Cross-system boundaries**: lineage often breaks at hops between systems (Kafka, external APIs, file drops); instrument each connector to emit lineage so the graph doesn't fragment.
- **Staleness/coverage**: lineage that's 80% complete is dangerously trusted as 100%. Track coverage explicitly, surface gaps, and treat lineage as a first-class, tested pipeline output — not a best-effort diagram.
- **Granularity vs cost**: full column-level lineage on everything is expensive to compute and store; prioritize tier-1 critical paths.

The staff-level point: reliable lineage comes from **runtime emission via an open standard (OpenLineage) into a central graph**, with explicit coverage tracking — because the failure mode is a *trusted-but-incomplete* lineage that silently omits the very edge you needed during an incident. Lineage is infrastructure to be engineered and validated, not a one-off diagram.

#### Q117. [Behavioral] You're asked to deliver a "real-time" pipeline in two weeks, but you believe a micro-batch solution is the right call. How do you handle the disagreement?
This tests engineering judgment *and* stakeholder management — the senior failure mode is being technically right but organizationally ineffective. A strong answer:
- **Lead with the requirement, not the technology**: ask *why* real-time — what decision or user experience depends on the latency? Often "real-time" means "fresher than the current daily batch," and a 2-minute micro-batch satisfies it at a fraction of the cost and risk. Quantify the actual latency SLA before arguing implementation.
- **Make the trade-offs explicit and honest**: present real-time's standing costs (always-on services, 24/7 on-call, exactly-once complexity, harder reprocessing) versus micro-batch's simplicity, and tie each to the two-week timeline and the team's operational maturity. Frame it as risk and total cost, not personal preference.
- **Offer a staged path**: ship reliable micro-batch now to hit the deadline and deliver value, with a clean migration path to true streaming *if and when* the latency requirement is proven to demand it. This de-risks the deadline and keeps the option open.
- **Disagree-and-commit**: if, after presenting the analysis, the decision-maker still wants true real-time with full understanding of the cost, commit and execute well — but ensure the trade-off was a documented, informed choice, not a default.

The leadership signal: you anchor on business value and latency *requirements*, communicate trade-offs in terms stakeholders care about (cost, risk, timeline), propose a pragmatic incremental path, and you can disagree firmly while still committing — influence through clarity, not stubbornness.

#### Q118. [Theory] Argue both sides: should a large org centralize data engineering in one platform team, or embed engineers in each domain? What's the synthesis?
A staff-level "it depends" that names the forces on each side and resolves them:

**Centralize (platform team owns pipelines/tooling):**
- Pros: consistent standards, no duplicated effort, deep platform expertise, easier governance, economies of scale on tooling.
- Cons: becomes a **bottleneck** — the central team lacks domain context and is overwhelmed by every team's requests; throughput and domain-fit suffer; the "central team that owns everything and understands nothing deeply" problem.

**Embed (engineers inside each domain):**
- Pros: deep domain knowledge, fast iteration, clear ownership of data as a product, no cross-team queue.
- Cons: **fragmentation** — inconsistent definitions of core entities (every team's "customer" differs), duplicated infrastructure, divergent quality, weak global governance, and skill silos.

**Synthesis (the 2026 consensus, and what data mesh gets right when done well):** a **hub-and-spoke / platform-plus-embedded** model. A central **platform team** owns the *self-serve infrastructure* — the lakehouse, orchestration, catalog, lineage, CI/CD, quality framework, and **federated governance standards** (data contracts, naming, security). **Domain teams** own their *data products* built on that platform, with clear SLAs and ownership. Governance is **federated computational** — global rules enforced automatically by the platform, local autonomy within them.

The key judgment: match the model to **org size and maturity**. A small org should centralize (not enough scale to embed). A large org with mature domains benefits from embed-on-a-platform — but *only* if leadership funds the real self-serve platform and governance plane; embedding without that platform produces chaos, not a mesh. The anti-pattern is choosing an org model as a slogan rather than building the platform/governance substrate that makes decentralization safe.

## ✅ Key Takeaways
- A batch is just a stream with a known end; modern engines (Spark, Flink) unify the two, and the real decision is the *latency requirement*, not the technology.
- Idempotency is the foundation of reliable pipelines: idempotent, rerunnable writes (overwrite-partition, upsert/MERGE) make backfills and failure recovery routine instead of heroic.
- Event time + watermarks + windowing are the core of correct streaming; the watermark/lateness setting is where you trade completeness against latency.
- Columnar formats, partitioning, and bucketing are the levers that turn full scans into pruned, cheap queries — choose partition columns by cardinality.
- The open lakehouse (Iceberg/Delta/Hudi + catalog) decouples storage, table format, and compute, giving lake economics with warehouse reliability across many engines.
- End-to-end exactly-once across heterogeneous systems is really idempotency + deterministic ordering + reconciliation, not one magic transaction.
- Dimensional modeling (star schema, SCD Type 2) still matters for analytics; preserve history where facts must reflect attribute values *as they were*.

## ⚠️ Common Pitfalls
- The small-files problem: over-partitioning (or per-record streaming writes) creates millions of tiny files that cripple query planning — compact and right-size.
- Treating Airflow as a processing engine and pulling large data through workers instead of orchestrating Spark/warehouse compute.
- Ignoring late and out-of-order data: using processing time instead of event time produces non-reproducible, load-dependent results.
- Non-idempotent appends that duplicate rows on retry; always design writes you can safely rerun.
- Shuffle and skew blindness: a single hot key creates a straggler that dominates runtime and OOMs — salt, broadcast, or enable AQE.
- "Going real-time" by default without quantifying the latency need, paying enormous operational and correctness costs for value micro-batch would deliver.
- Schema changes without a registry/compatibility rules, silently breaking downstream consumers.
- Silent data corruption: pipelines crash loudly but produce wrong-yet-plausible data quietly — without reconciliation and quality gates you find out from the business, not your alerts.

## 📚 Further Reading
- *Designing Data-Intensive Applications* — Martin Kleppmann (the foundational text on data systems, replication, and stream processing).
- *The Data Warehouse Toolkit* — Ralph Kimball & Margy Ross (dimensional modeling, star schemas, SCDs).
- *Fundamentals of Data Engineering* — Joe Reis & Matt Housley (the modern data engineering lifecycle).
- *Streaming Systems* — Tyler Akidau et al. (event time, watermarks, windowing — the definitive treatment).
- *Spark: The Definitive Guide* — Chambers & Zaharia; plus the Apache Flink and Kafka official docs.
- Apache Iceberg, Delta Lake, and Apache Hudi documentation; Debezium docs for CDC.
- dbt and Great Expectations docs for transformation-as-code and data quality testing.
