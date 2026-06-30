# Cassandra (Wide-Column)

[← Back to master index](../README.md)

Apache Cassandra is a distributed, masterless wide-column NoSQL database designed for linear scalability, high write throughput, and operational availability across multiple datacenters with no single point of failure. It blends Amazon Dynamo's partitioning/replication/availability model with a Bigtable-style column-family data model, and it is optimized for known, query-first access patterns rather than ad-hoc relational querying. This guide covers Cassandra from fundamentals through expert-level distributed-systems internals, using CQL examples, and is current through 2026 (Cassandra 4.x/5.0 with SAI, vector search, and Accord-based transactions on the horizon).

## 🟢 Basic (0–2 yrs)

### Q1. [Theory] What is Apache Cassandra and what problems is it designed to solve?

Cassandra is a **distributed wide-column store** built for workloads that need huge write throughput, horizontal scalability, and continuous availability — even across multiple geographic datacenters. Its lineage is the union of two papers: **Amazon Dynamo** (peer-to-peer partitioning, consistent hashing, tunable consistency, hinted handoff) and **Google Bigtable** (the column-family/wide-row data model and the LSM-tree storage engine).

The core problems it solves:
- **Linear horizontal scale** — add nodes and throughput/capacity grows roughly linearly; there is no master to become a bottleneck.
- **No single point of failure** — every node is identical (masterless/peer-to-peer); losing a node does not take the cluster down.
- **High write throughput** — the write path is append-only (commit log + memtable), so writes are extremely cheap.
- **Multi-DC availability** — built-in cross-datacenter replication with per-query consistency control.

It is *not* a general-purpose relational database: no joins, no arbitrary ad-hoc queries, limited transactions. You trade relational flexibility for scale and availability, and you must design tables around the queries you will run.

### Q2. [Theory] What is the wide-column data model?

A wide-column model organizes data into **tables** of **partitions**, where each partition is an ordered collection of **rows**, and each row is a set of **columns**. The "wide" part means a single partition can hold from one to millions of rows, and different partitions need not have an identical set of columns — the model is sparse.

Conceptually it sits between key-value and relational:

```
KEY (partition)        ->   wide row of clustering-keyed sub-rows
"sensor#42"            ->   [ ts=10:00 | temp=21 ]  [ ts=10:01 | temp=22 ]  ...
"sensor#43"            ->   [ ts=10:00 | temp=19 ]  ...
```

You look up by partition key (which node owns the data), then scan/seek within the partition by clustering columns (which are stored sorted on disk). This is why range scans *within a partition* are fast and cheap, while scans *across partitions* are expensive. Modern CQL presents this as familiar table syntax, but the underlying storage is still partitioned wide rows — understanding that mapping is essential to modeling correctly.

### Q3. [Theory] What is a primary key in Cassandra, and how does it differ from an RDBMS primary key?

In Cassandra a **primary key** has two parts: a **partition key** and optional **clustering columns**.

```cql
CREATE TABLE sensor_readings (
    sensor_id  text,        -- partition key
    reading_ts timestamp,   -- clustering column
    temp       double,
    PRIMARY KEY (sensor_id, reading_ts)
);
```

- The **partition key** (`sensor_id`) determines *which node(s)* store the row, via a hash (token) of the key.
- The **clustering columns** (`reading_ts`) determine the *sort order of rows within that partition* on disk.

The differences from an RDBMS primary key:
- It is not just a uniqueness constraint — it dictates **physical data placement and on-disk sort order**.
- You generally **cannot query efficiently by anything other than the primary key** (no arbitrary `WHERE` on non-key columns without secondary indexes or `ALLOW FILTERING`).
- Choosing it is a **data-distribution and query-access decision**, not just an identity decision. A bad primary key cannot be "fixed with an index" the way it often can in an RDBMS.

### Q4. [Theory] Explain the difference between partition key and clustering columns.

The **partition key** answers "*where does this data live?*" — it is hashed to a token that maps to a position on the ring, selecting the owning node(s). All rows sharing a partition key live together on the same nodes and are read/written as a unit.

The **clustering column(s)** answer "*how is data ordered inside the partition?*" — rows within a partition are physically stored sorted by the clustering columns, enabling efficient ordered range scans and `ORDER BY`.

```cql
PRIMARY KEY ((sensor_id, day), reading_ts)
--           ^^^^^^^^^^^^^^^^^  ^^^^^^^^^^^
--           composite          clustering column
--           partition key      (sorted within partition)
```

A composite partition key uses double parentheses: `((sensor_id, day))` means both columns together form the partition key (both must be supplied to locate the partition). Clustering columns are everything after the partition-key group. Rule of thumb: partition key = **even distribution + the equality predicate you always filter by**; clustering columns = **the sort order and range predicates** you need.

### Q5. [Theory] What is CQL and how does it relate to SQL?

CQL (Cassandra Query Language) is Cassandra's query language. It **deliberately looks like SQL** — `CREATE TABLE`, `INSERT`, `SELECT … WHERE`, `UPDATE`, `DELETE` — to lower the learning curve, but it is **not SQL** and intentionally omits things Cassandra cannot do efficiently:

- **No JOINs** — you denormalize instead.
- **No arbitrary `WHERE`** — predicates must align with the primary key (partition key for routing, clustering columns for range/sort).
- **No subqueries, no `GROUP BY` across partitions** (limited aggregation only).
- **No referential integrity / foreign keys.**

CQL is a *guardrail*: its restrictions push you toward queries that map to single-partition, sequential disk access. When CQL refuses a query (e.g., filtering on a non-key column), it's usually telling you the table is modeled for a different access pattern. `ALLOW FILTERING` exists to override this but is an anti-pattern for production reads at scale.

### Q6. [Coding] Write CQL to create a keyspace and a basic table.

A **keyspace** is the top-level container (analogous to a database/schema) and is where you declare the **replication strategy and factor**.

```cql
-- Keyspace with NetworkTopologyStrategy, RF=3 in datacenter "dc1"
CREATE KEYSPACE store
  WITH replication = {
    'class': 'NetworkTopologyStrategy',
    'dc1': 3
  };

USE store;

-- A query-first table: "get all orders for a customer, newest first"
CREATE TABLE orders_by_customer (
    customer_id  uuid,
    order_ts     timestamp,
    order_id     uuid,
    total        decimal,
    status       text,
    PRIMARY KEY (customer_id, order_ts)
) WITH CLUSTERING ORDER BY (order_ts DESC);
```

Here `customer_id` is the partition key (all of one customer's orders co-located), `order_ts` is the clustering column sorted descending so the most recent orders come first without a sort at read time. Always prefer `NetworkTopologyStrategy` over the legacy `SimpleStrategy` — even for a single DC — so the cluster can grow to multiple datacenters later.

### Q7. [Coding] Write CQL for basic CRUD operations.

```cql
-- INSERT (also acts as upsert)
INSERT INTO orders_by_customer (customer_id, order_ts, order_id, total, status)
VALUES (
  11111111-1111-1111-1111-111111111111,
  '2026-06-30 10:15:00',
  22222222-2222-2222-2222-222222222222,
  149.99, 'PLACED'
);

-- SELECT a single partition, optionally range-restricted by clustering col
SELECT order_id, total, status
FROM orders_by_customer
WHERE customer_id = 11111111-1111-1111-1111-111111111111
  AND order_ts >= '2026-06-01';

-- UPDATE (also an upsert: creates the row if absent)
UPDATE orders_by_customer
SET status = 'SHIPPED'
WHERE customer_id = 11111111-1111-1111-1111-111111111111
  AND order_ts = '2026-06-30 10:15:00';

-- DELETE (writes a tombstone — see tombstone questions)
DELETE FROM orders_by_customer
WHERE customer_id = 11111111-1111-1111-1111-111111111111
  AND order_ts = '2026-06-30 10:15:00';
```

Key point: **`INSERT` and `UPDATE` are functionally identical upserts** in Cassandra — there is no "row already exists" error and no read-before-write by default. Every mutation must fully specify the partition key (and the clustering key for a single-row operation).

### Q8. [Theory] What does "query-first" or "query-driven" data modeling mean?

In an RDBMS you model the *entities* (normalize), then write whatever queries you need. In Cassandra you **start from the queries** and build a table for each query pattern, even if that means storing the same data multiple times.

The workflow:
1. List the application's read queries (e.g., "orders by customer", "order by id", "orders by status today").
2. For *each* query, design a table whose primary key makes that query a single-partition lookup.
3. Accept **denormalization** — the same order may live in `orders_by_customer`, `orders_by_id`, and `orders_by_status` tables.
4. Keep the copies consistent on write (application-side, or via batches/materialized views).

```
RDBMS:   model entities  -> write any query  (read flexibility)
Cassandra: list queries  -> one table each   (read performance at scale)
```

The mantra: **"data duplication and disk space are cheap; random disk seeks and cross-node scatter-gather are expensive."** You optimize for sequential, single-partition reads.

### Q9. [Theory] Why are joins not supported, and what do you do instead?

Joins require correlating rows that may live on *different nodes*, which means a scatter-gather across the cluster plus network shuffles — exactly the kind of unbounded, unpredictable operation Cassandra avoids to keep latency flat at scale. So Cassandra simply doesn't offer joins.

Instead you **denormalize**: pre-compute the joined result at write time and store it in a single partition so the read is one lookup. If you need "orders with customer name", you store the customer name *inside* the order rows rather than joining to a customer table. The cost moves from read time (where it would be unpredictable and slow) to write time (cheap and bounded). For the rare analytical join you push that work to a separate system — Spark over Cassandra, or an analytics warehouse fed by CDC.

### Q10. [Theory] What are the main use cases where Cassandra shines, and where should you avoid it?

**Shines when:**
- **Write-heavy** workloads (time series, IoT sensor data, event/audit logs, metrics).
- **Massive scale** with predictable, key-based access patterns (messaging, feeds, user activity, product catalogs).
- **High availability / multi-region** requirements where you can't tolerate downtime.
- Known queries you can model tables around in advance.

**Avoid when:**
- You need **ad-hoc / analytical querying**, complex joins, or frequently changing query patterns.
- You need **strong ACID transactions across many rows/tables** as the norm (though LWT and the upcoming Accord transactions help).
- Your data volume is small enough that a single PostgreSQL/MySQL instance suffices — Cassandra's operational complexity isn't worth it.
- Workloads dominated by **updates/deletes on the same data** (tombstone churn) rather than appends.

The summary heuristic: Cassandra rewards *high-volume, write-heavy, availability-critical workloads with well-known access patterns*.

### Q11. [Theory] What is a node, a cluster, a datacenter, and a rack in Cassandra topology?

- **Node** — a single Cassandra instance (one JVM/server) that owns a range of tokens and stores the corresponding data.
- **Rack** — a logical grouping of nodes that share a failure domain (e.g., a physical rack, or an availability zone in the cloud). Cassandra tries to place replicas on *different racks* so one rack/AZ failure doesn't take out all copies.
- **Datacenter (DC)** — a logical grouping of racks, typically corresponding to a physical region. Replication factor is set **per datacenter**.
- **Cluster** — the full set of nodes across all datacenters that share data and gossip with each other.

```
Cluster
 ├─ DC: us-east           ├─ DC: eu-west
 │   ├─ rack a: node1     │   ├─ rack a: node5
 │   ├─ rack b: node2     │   └─ rack b: node6
 │   └─ rack c: node3     │
```

This hierarchy is what `NetworkTopologyStrategy` uses to spread replicas across racks and DCs for fault tolerance.

### Q12. [Theory] What is replication factor (RF) and replication strategy?

**Replication factor (RF)** is the number of copies of each piece of data the cluster keeps. RF=3 means every partition is stored on 3 different nodes. RF is set **per keyspace, per datacenter**.

**Replication strategy** is the algorithm that decides *which* nodes hold the replicas:
- **`SimpleStrategy`** — places replicas on the next RF-1 nodes clockwise on the ring, ignoring rack/DC topology. Use only for single-DC dev/test; **never in production**.
- **`NetworkTopologyStrategy` (NTS)** — topology-aware: places replicas in each named datacenter and spreads them across racks to maximize fault tolerance. Always use this in production.

```cql
ALTER KEYSPACE store WITH replication = {
  'class': 'NetworkTopologyStrategy',
  'us_east': 3,
  'eu_west': 3
};
```

RF=3 is the de-facto production standard: it tolerates one node loss while still allowing `QUORUM` reads/writes (2 of 3).

### Q13. [Theory] What are consistency levels ONE, QUORUM, and ALL?

A **consistency level (CL)** specifies how many replicas must acknowledge a read or write before the coordinator returns success. It is set **per query**, giving *tunable consistency*.

- **ONE** — only one replica must respond. Fastest, lowest latency, but you might read stale data or lose a write if that replica fails before replicating. Highest availability.
- **QUORUM** — a majority of replicas (`floor(RF/2) + 1`) must respond. With RF=3, QUORUM = 2. The balanced default.
- **ALL** — every replica must respond. Strongest consistency but **zero tolerance** for any replica being down — one dead node makes the operation fail. Rarely used.

```
RF=3:   ONE -> 1 ack    QUORUM -> 2 acks    ALL -> 3 acks
```

Other useful levels: `LOCAL_QUORUM` (quorum within the local DC only — the multi-DC workhorse), `LOCAL_ONE`, `EACH_QUORUM`, `TWO`, `THREE`, `ANY` (write-only; satisfied by a hint).

### Q14. [Theory] What does it mean that Cassandra has "tunable consistency"?

Tunable consistency means **you choose the consistency/latency trade-off per operation** rather than the database imposing a fixed level. By picking read CL (R) and write CL (W) relative to RF (N), you decide where you sit on the CAP/PACELC spectrum.

The crucial relationship for **strong consistency** is:

```
R + W > N   =>   read and write quorums overlap on at least one replica
                 => the read is guaranteed to see the latest acknowledged write
```

Example (RF=3): `W=QUORUM(2) + R=QUORUM(2) = 4 > 3` → strongly consistent. If instead you write at ONE and read at ONE (`1+1=2 ≤ 3`), you get eventual consistency with the best latency/availability. This per-query knob is one of Cassandra's defining features: a critical write can use QUORUM while a tolerant analytics read uses ONE, in the same application.

### Q15. [Theory] Why is Cassandra called "eventually consistent" and what does that mean?

Cassandra is an **AP** system (CAP): under a network partition it favors **availability** over strong consistency. Replicas can temporarily diverge — a write at CL ONE might reach one replica before the others — so two reads at low CL may briefly see different values.

"**Eventually consistent**" means that, in the absence of new writes, all replicas will *converge* to the same latest value through Cassandra's anti-entropy mechanisms: **read repair**, **hinted handoff**, and **repair (anti-entropy repair / `nodetool repair`)**. The word "eventually" is doing real work — convergence is not instantaneous. If your application needs read-your-writes guarantees, you opt into strong consistency via `R + W > N` (e.g., QUORUM/QUORUM) at the cost of some latency and availability. Cassandra's design lets *you* decide per query rather than forcing one global trade-off.

### Q16. [Practical] How do you set the consistency level for a query?

In `cqlsh` it is a session-level setting:

```cql
CONSISTENCY QUORUM;
SELECT * FROM orders_by_customer WHERE customer_id = ...;
```

In the Java driver (DataStax Java Driver 4.x), set it per statement or as a profile default:

```java
SimpleStatement stmt = SimpleStatement.builder(
        "SELECT * FROM store.orders_by_customer WHERE customer_id = ?")
    .addPositionalValue(customerId)
    .setConsistencyLevel(DefaultConsistencyLevel.LOCAL_QUORUM)
    .build();

ResultSet rs = session.execute(stmt);
```

Or define an execution profile in `application.conf` so all reads default to `LOCAL_QUORUM`. The rule: in multi-DC deployments default to **`LOCAL_QUORUM`** (strong within the local DC, no cross-DC latency on the request path), and reserve `EACH_QUORUM`/`QUORUM` for the rare cases that truly need cross-DC agreement.

### Q17. [Theory] What is a partition and what is a partition (row) key hash / token?

A **partition** is the set of all rows sharing the same partition key; it is the **atomic unit of storage and replication** — an entire partition lives together on the same replica nodes.

A **token** is the output of hashing the partition key with the cluster's partitioner (default **Murmur3Partitioner**, producing a 64-bit signed token in the range −2^63 … 2^63−1). The token determines the partition's position on the **ring**, which determines which node owns it.

```
partition key "sensor#42"  --Murmur3-->  token = -4011...   --> falls in node3's range
```

Because the hash spreads keys uniformly, well-chosen partition keys distribute data and load evenly across the cluster. The token is also why you can't do range scans across partition keys — adjacent keys hash to scattered tokens on different nodes.

### Q18. [Theory] What is the difference between Cassandra and a traditional RDBMS?

| Aspect | RDBMS (e.g., PostgreSQL) | Cassandra |
|---|---|---|
| Topology | Single primary (often) / leader-based | Masterless peer-to-peer |
| Scaling | Vertical, read replicas, sharding (manual) | Horizontal, linear, built-in |
| Data model | Normalized, relational, joins | Denormalized wide-column, no joins |
| Query | Ad-hoc SQL, flexible | Query-first CQL, predefined access |
| Consistency | Strong ACID by default | Tunable (eventual → strong) |
| Transactions | Full multi-row ACID | Limited (LWT; Accord upcoming) |
| Availability | Failover required | No SPOF, always-on |
| Best for | Complex queries, integrity, moderate scale | Write-heavy, huge scale, HA |

The one-line version: an RDBMS optimizes for **query flexibility and strong consistency at moderate scale**; Cassandra optimizes for **write throughput, linear scale, and availability with predefined access patterns**.

### Q19. [Coding] Model a table for "get the latest N readings for a sensor."

Time-series is Cassandra's signature use case. Partition by the entity and a time bucket; cluster by timestamp descending so "latest N" is just `LIMIT N`.

```cql
CREATE TABLE readings_by_sensor (
    sensor_id  text,
    bucket     text,         -- e.g. '2026-06-30' to bound partition size
    reading_ts timestamp,
    temp       double,
    PRIMARY KEY ((sensor_id, bucket), reading_ts)
) WITH CLUSTERING ORDER BY (reading_ts DESC);

-- Latest 10 readings for a sensor on a given day:
SELECT reading_ts, temp
FROM readings_by_sensor
WHERE sensor_id = 'sensor#42' AND bucket = '2026-06-30'
LIMIT 10;
```

The `bucket` is the **time-bucketing** trick: without it, a busy sensor's partition would grow unbounded over years and become a "wide partition" hotspot. Choose the bucket granularity (hour/day/month) so each partition stays under ~100 MB / ~100k rows.

### Q20. [Theory] What is the commit log and what is its purpose?

The **commit log** is an append-only, on-disk file that records *every* mutation **before** it is applied to the in-memory memtable. It exists for **durability**: if a node crashes after acknowledging a write but before the memtable is flushed to an SSTable, the commit log is replayed on restart to recover those mutations.

```
Write path:  client -> coordinator -> replica:
   1) append to commit log (durable)   ┐ both happen
   2) write to memtable (in-memory)    ┘ before ack
```

Because the commit log write is a sequential append (fast) and the memtable write is just memory, Cassandra writes are very cheap — no random disk I/O, no read-before-write. Once a memtable is flushed to an SSTable, the corresponding commit-log segments can be recycled. Commit-log durability is configurable (`periodic` fsync by default for throughput, or `batch`/`group` for stricter durability).

## 🟡 Intermediate (3–7 yrs)

### Q21. [Theory] Explain the LSM-tree storage engine and why Cassandra uses it.

Cassandra stores data using a **Log-Structured Merge-tree (LSM-tree)**, optimized for write throughput. Instead of updating data in place (like a B-tree), it accumulates writes in memory and flushes them as **immutable, sorted files (SSTables)**, merging them in the background.

The flow:
1. Writes go to the **commit log** (durability) and a **memtable** (sorted in-memory structure).
2. When a memtable fills, it is flushed to an immutable **SSTable** on disk (sequential write).
3. Over time many SSTables accumulate; **compaction** merges them, discarding obsolete/overwritten data and tombstones.

```
memtable (RAM, sorted) --flush--> SSTable_1
                                  SSTable_2  --compaction--> SSTable_merged
                                  SSTable_3
```

Why LSM: **writes are sequential appends** (no in-place mutation, no random seeks), giving the high write throughput Cassandra is known for. The trade-off is **read amplification** (a read may need to check multiple SSTables) and **write amplification from compaction** — both managed by bloom filters, partition indexes, and compaction strategy.

### Q22. [Theory] Walk through the write path in detail.

```
1. Client sends write to any node -> that node becomes the COORDINATOR.
2. Coordinator hashes the partition key -> token -> identifies the RF replica nodes.
3. Coordinator forwards the write to all live replicas (and stores hints for down ones).
4. On each replica:
     a. Append mutation to the COMMIT LOG (durability).
     b. Apply mutation to the MEMTABLE (in-memory, sorted).
     c. Update the row cache / counter cache if applicable.
5. Replica acks the coordinator.
6. Coordinator waits for CL acks (e.g., QUORUM=2), then acks the client.
7. Asynchronously: when memtable is full -> flush to immutable SSTable;
   commit-log segments freed once flushed.
```

Notice what is **absent**: no read-before-write, no in-place update, no locking. That's why writes are O(1) and uniformly fast regardless of whether the row exists. Updates and deletes are just new timestamped writes (the latter being tombstones); conflicting versions are resolved at read time by **last-write-wins** based on the write timestamp.

### Q23. [Theory] Walk through the read path in detail.

A read is more complex than a write because data for one partition may be spread across the memtable and several SSTables.

```
1. Coordinator routes to replicas owning the partition (per CL).
2. On a replica, to assemble the row:
     a. Check the MEMTABLE (newest unflushed data).
     b. Check the ROW CACHE (if enabled) — possible fast hit.
     c. For each candidate SSTable:
          - BLOOM FILTER: skip the SSTable if it definitely lacks the key.
          - PARTITION KEY CACHE / partition index / summary -> find offset.
          - Read the partition; seek by clustering columns.
     d. MERGE results across memtable + SSTables, resolving by
        write-timestamp (last-write-wins); apply tombstones.
3. Coordinator gathers CL responses; if replicas disagree -> READ REPAIR.
4. Return the reconciled, latest version to the client.
```

The key performance structures are **bloom filters** (avoid touching SSTables that can't contain the key — false positives possible, false negatives impossible) and the **partition index/summary** (locate the partition's byte offset). Read latency grows with the number of SSTables a partition is spread across, which is why compaction strategy matters so much.

### Q24. [Theory] What are memtables and SSTables?

- **Memtable** — an in-memory, sorted (by clustering key) write-back cache, one per table. New writes land here after the commit log. It is mutable while resident. When it reaches a threshold (size/time), it is **flushed**.
- **SSTable (Sorted String Table)** — the immutable, on-disk file produced by flushing a memtable. Sorted by partition token then clustering key. Once written, an SSTable is **never modified** — updates/deletes are new writes in newer SSTables, reconciled at read/compaction time.

An SSTable on disk is actually a set of component files: `Data.db` (the rows), `Index.db` (partition index), `Summary.db` (sampled index in memory), `Filter.db` (bloom filter), `CompressionInfo.db`, `Statistics.db`, etc. Immutability is the whole trick: it makes writes append-only and lets reads use cheap, cacheable, prebuilt indexes — at the cost of needing compaction to reclaim space and bound read amplification.

### Q25. [Theory] What is a tombstone and why does it exist?

A **tombstone** is a special marker written when data is **deleted** (or when a column/row is set with a TTL that expires). Because SSTables are immutable, Cassandra **cannot remove data in place** — instead it writes a tombstone (a delete record with a timestamp) that *shadows* the older data.

```
SSTable_1:  name = "Ana"        (ts=100)
SSTable_2:  name = <tombstone>  (ts=200)   -> read returns "deleted"
```

At read time the tombstone (newer timestamp) wins, so the data appears gone. The actual bytes are only reclaimed later during **compaction**, and only after **`gc_grace_seconds`** (default 10 days) has elapsed. The grace period exists so the deletion can propagate to all replicas; removing a tombstone too early could let a replica that missed the delete **resurrect** the data (the "zombie data" problem).

### Q26. [Theory] Why are tombstones dangerous, and how do they cause problems?

Tombstones are deceptively expensive. Three core dangers:

1. **Read amplification / latency.** To answer a query, Cassandra must read *all* tombstones in the queried range to know what's deleted — even though they return no data. A query scanning a range full of tombstones can read thousands of dead markers, blowing up latency. Cassandra warns at `tombstone_warn_threshold` (1,000) and **aborts** the query at `tombstone_failure_threshold` (10,000).

2. **Zombie data (resurrection).** If you set `gc_grace_seconds` too low (or skip repairs) and a replica missed a delete, compaction can purge the tombstone before the delete reaches that replica — the old value then "comes back to life."

3. **Disk and compaction pressure.** Tombstones occupy space and must be carried through compactions until they're eligible for purge.

```
range query over a queue partition:
[ tombstone ][ tombstone ][ tombstone ]...[ live row ]
 \__________ thousands of dead markers _________/   -> slow / fails
```

The classic offender is the **queue/anti-pattern** (insert then delete rows in the same partition) and **range deletes**. Mitigations: model to avoid mass deletes, use TTLs thoughtfully, prefer separate partitions you can drop, and tune `gc_grace_seconds` with awareness of your repair cadence.

### Q27. [Theory] What is compaction and what are the main compaction strategies?

**Compaction** is the background process that merges multiple SSTables into fewer, larger ones — discarding overwritten data, purging expired tombstones, and reducing the number of SSTables a read must touch (lowering read amplification). It is the LSM-tree's housekeeping.

Main strategies:

- **STCS — SizeTieredCompactionStrategy** (default). Merges SSTables of similar size into bigger ones. Great for **write-heavy** workloads; low write amplification. Downsides: can need ~50% free disk headroom transiently, and reads may touch many SSTables. Good for time-series / append-mostly.

- **LCS — LeveledCompactionStrategy.** Organizes SSTables into size-tiered *levels* where a partition lives in (mostly) one SSTable per level, guaranteeing reads touch few SSTables (typically ≤ levels). Best for **read-heavy / update-heavy** workloads needing predictable read latency; costs more write amplification and I/O.

- **TWCS — TimeWindowCompactionStrategy.** Buckets SSTables by time window and compacts within a window. Ideal for **time-series with TTL**, where entire old windows can be dropped wholesale — extremely tombstone- and TTL-friendly. The go-to for IoT/metrics/logs.

- **UCS — UnifiedCompactionStrategy** (Cassandra 5.0). A configurable strategy that can behave like STCS or LCS via parameters, intended to unify and simplify tuning.

Choosing wrong (e.g., STCS for an update-heavy read-latency-sensitive table) is a common cause of latency problems.

### Q28. [Practical] When would you choose TWCS over STCS or LCS?

Choose **TWCS** when the data is **time-series with a TTL** and writes are append-only within time order — IoT/sensor data, metrics, event logs, audit trails.

```cql
CREATE TABLE metrics_by_host (
    host       text,
    bucket     text,
    ts         timestamp,
    value      double,
    PRIMARY KEY ((host, bucket), ts)
) WITH CLUSTERING ORDER BY (ts DESC)
  AND default_time_to_live = 2592000          -- 30 days TTL
  AND compaction = {
    'class': 'TimeWindowCompactionStrategy',
    'compaction_window_unit': 'DAYS',
    'compaction_window_size': 1
  };
```

Why TWCS wins here: each daily window becomes its own set of SSTables, and once *every* row in a window has expired (TTL), Cassandra can **drop the entire SSTable** without per-row tombstone compaction — cheap, predictable, and tombstone-light. STCS would mix old and new data into the same SSTables (so old data can't be dropped wholesale), and LCS would waste write I/O leveling data that's about to expire. Rule: **TWCS for time-windowed, TTL'd, append-only data**; never update or delete individual rows in a TWCS table (it breaks the window invariant).

### Q29. [Theory] What is the gossip protocol and what does it do?

**Gossip** is the peer-to-peer protocol Cassandra nodes use to share cluster **membership and state** — which nodes are up/down, their tokens, load, schema version, and DC/rack. There is no central registry; each node periodically (once per second) gossips with a few random peers, and information spreads epidemically across the cluster.

```
node1 <-> node3   node2 <-> node5   ... each second, random peers exchange state
  -> within a few rounds, all nodes converge on the same view of the cluster
```

Each piece of state carries a **version/generation number**, so nodes always adopt the newest information and discard stale views. Gossip is how a masterless cluster maintains a shared, self-healing picture of itself: new nodes are discovered, failed nodes are detected (via the **failure detector**, which uses gossip heartbeats and the Phi-accrual algorithm), and the topology stays current — all without a coordinator. Seed nodes are just well-known bootstrap contacts so a joining node knows where to start gossiping.

### Q30. [Theory] Explain the ring and consistent hashing in Cassandra.

Cassandra arranges the token space (−2^63 … 2^63−1 for Murmur3) into a **logical ring**. Each node owns a contiguous range of tokens; a partition key is hashed to a token, and the node responsible is the first node **clockwise** from that token. Replicas are the next nodes clockwise (placed per the replication strategy).

```
          token 0
        ┌──────────┐
   node4│          │node1
        │   RING   │
   node3│          │node2
        └──────────┘
key -> token -> walk clockwise -> owning node + next RF-1 replicas
```

This is **consistent hashing**: when a node is added or removed, only the keys in its immediate range need to move, not the whole dataset — minimizing data reshuffling and enabling near-linear scaling. Modern Cassandra uses **virtual nodes (vnodes)**: each physical node owns many small, scattered token ranges instead of one big range, which spreads data and streaming load more evenly and makes adding/removing nodes smoother.

### Q31. [Theory] What are virtual nodes (vnodes) and why do they help?

**Vnodes** split each physical node's ownership into many small token ranges (default historically 256, now commonly **16** with `num_tokens` in modern versions to balance even distribution against repair efficiency) scattered around the ring, rather than one large contiguous range per node.

Benefits:
- **Even load distribution** — many small ranges average out hot spots better than one big range.
- **Faster, parallel bootstrap/decommission** — a joining/leaving node streams from/to *many* peers simultaneously instead of just its two ring neighbors.
- **Better rebuild after failure** — recovery work is spread across the whole cluster.
- **No manual token assignment** — you don't hand-balance tokens when adding nodes.

The trade-off: too many vnodes increases repair overhead and the chance that a quorum's replicas span more physical nodes, slightly raising the probability that multiple simultaneous failures affect some token range. That's why the default was lowered from 256 toward 16. Vnodes are essentially "automatic, fine-grained sharding within the consistent-hashing ring."

### Q32. [Theory] What is hinted handoff?

**Hinted handoff** is a mechanism that preserves writes destined for a **temporarily down** replica. When the coordinator sends a write and one replica is unreachable, the coordinator **stores a hint** (the mutation plus the target node) locally. When the down node comes back (detected via gossip), the coordinator **replays the hint** to it, healing the missed write.

```
write -> replicas [A, B, C];  C is DOWN
coordinator: write to A, B; store HINT for C
... C recovers ...
coordinator replays hint -> C now has the data
```

Hints have a time bound (`max_hint_window_in_ms`, default 3 hours): if a node is down longer, hints are dropped and you must rely on **read repair** and **`nodetool repair`** to reconcile. Hinted handoff improves availability (a brief outage doesn't lose writes) and reduces the inconsistency window, but it is **not a substitute for repair** — it only covers nodes that were briefly down while the coordinator was watching. The write CL `ANY` is uniquely satisfied by a hint alone (write succeeds even if *no* replica is currently up), which trades durability for availability.

### Q33. [Theory] What is read repair?

**Read repair** is an anti-entropy mechanism that fixes inconsistencies **during reads**. When a read at CL > ONE contacts multiple replicas and their responses disagree (different write timestamps for the same data), the coordinator:

1. Determines the **most recent** value (highest write timestamp — last-write-wins).
2. Returns it to the client.
3. **Writes the up-to-date value back** to the stale replica(s) in the background.

```
read at QUORUM -> replica A: name="Ana"(ts=200), replica B: name="An"(ts=150)
 -> A wins; client gets "Ana"; B is repaired to "Ana"
```

Read repair piggybacks consistency healing on normal read traffic, so frequently-read data stays consistent without explicit repairs. Cassandra historically also had "background read repair" on a probability (`read_repair_chance`), but modern versions (4.0+) replaced that with **blocking/non-blocking read repair** driven by the read consistency level. Read repair only heals data that gets read — cold data still needs scheduled `nodetool repair`.

### Q34. [Theory] What is anti-entropy repair (`nodetool repair`) and why is it necessary?

`nodetool repair` is the **scheduled, comprehensive** anti-entropy process that compares data across replicas and reconciles *all* differences — not just the rows that happen to be read. It builds **Merkle trees** (hash trees) of each replica's data for a token range, exchanges the tree hashes, and **streams only the differing ranges** between replicas.

Why it's necessary:
- Hinted handoff and read repair only cover *briefly-down* nodes and *read* data. **Cold data on a long-down node** can stay inconsistent forever otherwise.
- It is required to **safely purge tombstones**: you must repair within `gc_grace_seconds` so all replicas learn of deletes before tombstones are collected — otherwise deleted data can resurrect (zombies).

```
replica A Merkle tree  vs  replica B Merkle tree
   differing leaf hashes -> stream only those ranges -> converge
```

Operationally you run repair regularly (e.g., via **Cassandra Reaper** or `nodetool repair -pr` per node) so the whole cluster is repaired at least once every `gc_grace_seconds`. Incremental repair (4.x) marks already-repaired SSTables to avoid re-doing work. Skipping repair is one of the most common production mistakes.

### Q35. [Coding] How do you model a many-to-many relationship without joins?

You create **two denormalized tables**, one for each query direction. Classic example: students enroll in courses.

```cql
-- "Which courses is this student in?"
CREATE TABLE courses_by_student (
    student_id  uuid,
    course_id   uuid,
    course_name text,
    enrolled_at timestamp,
    PRIMARY KEY (student_id, course_id)
);

-- "Which students are in this course?"
CREATE TABLE students_by_course (
    course_id    uuid,
    student_id   uuid,
    student_name text,
    enrolled_at  timestamp,
    PRIMARY KEY (course_id, student_id)
);
```

On enrollment you write to **both** tables (ideally in a `BEGIN BATCH` if they share the partition routing concerns, or with application-level dual writes). Each query becomes a single-partition lookup. The cost is duplicated data and the responsibility to keep both copies in sync on writes and deletes — which is the deliberate Cassandra trade-off: pay at write time so reads are fast and predictable.

### Q36. [Practical] What is a `BATCH` in Cassandra and when should (and shouldn't) you use it?

A CQL `BATCH` groups multiple writes. There are two kinds with very different semantics:

```cql
-- LOGGED batch: atomic across statements (all-or-nothing), via batchlog
BEGIN BATCH
  INSERT INTO courses_by_student (student_id, course_id, course_name) VALUES (...);
  INSERT INTO students_by_course (course_id, student_id, student_name) VALUES (...);
APPLY BATCH;

-- UNLOGGED batch: no atomicity guarantee, just grouped
BEGIN UNLOGGED BATCH ... APPLY BATCH;
```

**Use a (LOGGED) batch** to keep **denormalized copies of the same data atomically consistent** across multiple tables (the example above). The batchlog ensures all statements eventually apply even if the coordinator dies mid-batch.

**Do NOT use batches** as a performance optimization to "bulk insert" many unrelated partitions — that is a classic anti-pattern. A multi-partition batch forces one coordinator to fan out to many nodes, creating a hotspot and *worse* throughput than independent async writes. Rule: **batch for atomicity across few partitions, not for bulk throughput.** For bulk loading, use many concurrent prepared async statements (or `COPY`/bulk loaders).

### Q37. [Practical] What is a Lightweight Transaction (LWT) and how does it work?

An **LWT** provides **compare-and-set** (linearizable) semantics for the cases where last-write-wins isn't enough — e.g., "create this user only if the username is free." It uses the **Paxos** consensus protocol under the hood and is triggered by `IF` clauses.

```cql
-- Insert only if the row doesn't already exist
INSERT INTO users (username, email)
VALUES ('ana', 'ana@x.com')
IF NOT EXISTS;

-- Conditional update
UPDATE accounts SET balance = 90
WHERE id = 42
IF balance = 100;
```

How it works: a four-round-trip Paxos exchange (prepare/promise, propose/accept) among the replicas establishes agreement before applying the write, guaranteeing no two concurrent LWTs both "win." The cost is **significant latency** (multiple round-trips vs. one for a normal write) and contention if many LWTs target the same partition. Use LWTs **sparingly** for genuinely contended uniqueness/conditional logic; never as the default write path. (Cassandra 5.x's **Accord** protocol aims to provide faster, general multi-partition transactions.)

### Q38. [Theory] What is a counter column and what are its caveats?

A **counter** is a special column type for distributed incrementing/decrementing values (e.g., page views, likes) without read-before-write at the application level.

```cql
CREATE TABLE page_views (
    page_id text PRIMARY KEY,
    views   counter
);

UPDATE page_views SET views = views + 1 WHERE page_id = 'home';
```

Caveats:
- A table with a counter can contain **only** counter columns (plus the primary key) — you can't mix counters and regular columns.
- Counter updates are **not idempotent**; a retried `+1` after an ambiguous timeout could double-count. They use a special read-modify-write internally, so they're **more expensive** than normal writes and can't be made fully exactly-once under failure.
- You can't set a counter to an arbitrary value or use a counter as part of the primary key.

Use counters for approximate, high-volume tallies where occasional small drift is acceptable; for exact accounting, model differently (e.g., append events and aggregate, or use LWT).

### Q39. [Theory] What are TTL and how do they interact with tombstones?

**TTL (time-to-live)** lets you expire data automatically after N seconds, per column, per write, or as a table default.

```cql
INSERT INTO sessions (id, token) VALUES (1, 'abc') USING TTL 3600;   -- 1 hour
-- or table-wide:
ALTER TABLE sessions WITH default_time_to_live = 86400;              -- 1 day
```

When data's TTL expires, it becomes an **expired-cell tombstone** — it's logically gone but still occupies space and must be purged by compaction (after `gc_grace_seconds`, like any tombstone). So TTL doesn't *instantly* free space, and **mass-expiring data in the same partition can create tombstone storms** that slow reads, exactly like explicit deletes. The clean pattern is **TTL + TWCS**: time-bucket the data so an entire SSTable's worth of cells expires together and the whole file is dropped — avoiding per-cell tombstone overhead entirely. TTL with the *wrong* compaction strategy is a common source of tombstone trouble.

### Q40. [Practical] How do secondary indexes work and when should you avoid them?

A regular **secondary index (2i)** lets you query by a non-primary-key column. But Cassandra's classic 2i is a **local index** — each node indexes only its own data — so a query by an indexed column that isn't the partition key becomes a **scatter-gather across all nodes**:

```cql
CREATE INDEX ON users (email);
SELECT * FROM users WHERE email = 'ana@x.com';   -- hits EVERY node
```

Avoid classic 2i when:
- The indexed column is **high cardinality** (e.g., email) — the scatter-gather is expensive and doesn't scale.
- The indexed column is **very low cardinality** (e.g., boolean) — index partitions become huge and unbalanced.
- The column is frequently updated/deleted (index tombstone churn).

Better options: **build a dedicated query table** (denormalize) for the access pattern, or use **SASI** (legacy) or, in Cassandra 5.0, **SAI (Storage-Attached Indexing)** — a far more efficient, integrated index supporting range, prefix, and (with vector search) similarity queries with much better performance characteristics. Rule of thumb: prefer a purpose-built table; use SAI for genuinely ad-hoc-ish secondary access; treat classic 2i as a last resort for low-traffic, moderate-cardinality lookups within a known partition.

### Q41. [Theory] What is a materialized view and what are its trade-offs?

A **materialized view (MV)** is a server-maintained denormalized table derived from a base table with a different primary key, so you don't have to maintain the second query table manually.

```cql
CREATE MATERIALIZED VIEW orders_by_status AS
  SELECT * FROM orders_by_customer
  WHERE status IS NOT NULL AND customer_id IS NOT NULL AND order_ts IS NOT NULL
  PRIMARY KEY (status, order_ts, customer_id);
```

When you write to the base table, Cassandra automatically updates the MV. The appeal is convenience: no application-side dual writes. The trade-offs and cautions:
- MVs add **write-path overhead** (each base write triggers a read + view update) and can introduce **inconsistencies** between base and view under certain failure/repair scenarios.
- They have had a history of **bugs/edge cases**, and the feature is officially flagged **experimental** in several versions.

Practical guidance: many seasoned teams **avoid MVs in production** and prefer explicit denormalized tables maintained by the application (full control, well-understood semantics). Know what MVs are and their pitfalls — that nuance is what interviewers probe.

### Q42. [Practical] How do you avoid hotspots and large partitions?

A **hotspot** is a partition (or node) receiving disproportionate traffic; a **large partition** is one that grows too big (rule of thumb: keep partitions under ~100 MB and ~100k rows). Both kill performance.

Techniques:
- **Bucket/shard the partition key** to bound size and spread load:

```cql
-- BAD: one partition per sensor grows forever
PRIMARY KEY (sensor_id, ts)

-- GOOD: add a time bucket so partitions are bounded
PRIMARY KEY ((sensor_id, day), ts)
```

- **Composite partition keys** to add cardinality and distribute (e.g., `((country, region))`).
- **Avoid low-cardinality partition keys** (e.g., a `status` with 3 values would concentrate everything onto 3 partitions/nodes).
- **Add a synthetic shard bucket** (`hash(id) % N`) for naturally skewed keys (one celebrity user with millions of followers).
- Monitor with `nodetool tablehistograms` and `nodetool cfstats` (look at max partition size).

The mental model: the partition key must give you **both** even distribution **and** a bounded partition size, while still being the thing you filter by. Getting this wrong is the single most common cause of Cassandra performance problems.

### Q43. [Theory] What is `LOCAL_QUORUM` and why is it the multi-DC default?

`LOCAL_QUORUM` requires a quorum of replicas **within the local datacenter only** (the DC the coordinator is in), ignoring remote DCs for satisfying the consistency level.

Why it's the multi-DC workhorse:
- **Strong consistency within a DC** (`R + W > RF_local` if you write and read `LOCAL_QUORUM`) without paying **cross-DC network latency** on the request path. Replication to other DCs still happens asynchronously in the background.
- **DC isolation** — a remote DC being slow or partitioned doesn't block local operations, preserving availability.

```
write LOCAL_QUORUM in us-east (RF=3): 2 local acks -> client ok
   (data still streams to eu-west asynchronously)
```

Contrast with `EACH_QUORUM` (a quorum required in *every* DC — strong global consistency but slow and fragile to any DC outage) and plain `QUORUM` (a quorum of *all* replicas across all DCs — incurs cross-DC latency). For most geo-distributed apps, `LOCAL_QUORUM` reads + writes give the best balance of consistency, latency, and availability.

### Q44. [Practical] How do you choose a good partition key? Give the criteria.

Three criteria, all of which must hold:

1. **High cardinality / even distribution** — the key must hash to spread data uniformly across the ring. `user_id` good; `country` (a handful of values) bad on its own.
2. **Bounded partition size** — one partition's worth of data must stay small (< ~100 MB / 100k rows). Time-series keys need a **time bucket** to bound growth.
3. **Matches the query** — every read must supply the *full* partition key for an equality match, so the key must contain exactly the columns you always filter by.

```cql
-- Query: "messages in a chat room, by time, bounded by day"
PRIMARY KEY ((room_id, day), msg_ts)
--           ^^^^^^^^^^^^^^   even-ish + bounded + always known at query time
```

A useful test: for each application query, ask "can I supply the entire partition key as an equality filter, and will that partition stay reasonably sized?" If no, redesign. The partition key is a *single* decision that simultaneously controls distribution, partition size, and query-ability — which is why it's the heart of Cassandra modeling.

### Q45. [Theory] How does Cassandra resolve conflicting writes (last-write-wins)?

Cassandra resolves conflicts using **last-write-wins (LWW)** based on each cell's **write timestamp** (microseconds since epoch, assigned by the coordinator/client). When reads or compaction encounter multiple versions of the same cell, the one with the **highest timestamp wins**; ties are broken by value comparison.

```
cell "status":  SSTable_1 = "PLACED"(ts=1000)   SSTable_2 = "SHIPPED"(ts=2000)
                -> "SHIPPED" wins (newer ts)
```

Implications and risks:
- There is **no merge** — the whole losing cell is discarded (no vector clocks, no app-level conflict resolution like Dynamo offers).
- **Clock skew is dangerous**: if client/coordinator clocks drift, a logically-newer write with a smaller timestamp can be silently lost. Keep clocks tightly synced (NTP/chrony) and prefer server-assigned timestamps.
- You can override the timestamp with `USING TIMESTAMP`, but doing so manually is error-prone.

LWW is simple and fast but means **concurrent updates to the same cell are not merged — one is dropped**. Model to avoid concurrent in-place updates to the same cell where possible (append-style or per-source columns).

## 🟠 Advanced (8–12 yrs)

### Q46. [Theory] Compare Cassandra, DynamoDB, and an RDBMS across the key dimensions.

| Dimension | Cassandra | DynamoDB | RDBMS |
|---|---|---|---|
| Lineage | Dynamo + Bigtable | Dynamo (managed) | Relational |
| Operations | Self-managed (or Astra/managed) | Fully managed serverless | Self/managed |
| Data model | Wide-column, clustering | Key-value / document (items) | Relational tables |
| Partitioning | Consistent hashing ring, you pick key | Partition key (managed splits) | Manual sharding |
| Consistency | Tunable per query (ONE→ALL) | Eventual or strong (per read) | Strong ACID |
| Transactions | LWT (Paxos); Accord upcoming | TransactWriteItems (limited) | Full ACID |
| Multi-region | Native, symmetric, you control | Global Tables (managed) | Hard |
| Scaling cost | Linear, capacity you provision | Pay-per-request / provisioned | Vertical-heavy |
| Secondary access | 2i / SAI / query tables | GSIs / LSIs | Any index |
| Vendor | Open-source, no lock-in | AWS lock-in | Varies |

The strategic summary: **DynamoDB** = "Cassandra's data model as a zero-ops AWS service" — pick it when you're all-in on AWS and want no operational burden, accepting lock-in and per-request pricing. **Cassandra** = pick it for **multi-cloud / on-prem / no-lock-in**, fine-grained control, predictable cost at very large sustained scale, and richer per-query consistency tuning. **RDBMS** = pick it for relational integrity, ad-hoc queries, and transactions at small-to-moderate scale. They overlap most on the Dynamo-style key-value workloads; the deciding factors are usually *operational model, lock-in, and query flexibility*, not raw capability.

### Q47. [Theory] Explain the consistency math: how do you guarantee strong consistency, and what does `R + W > N` actually buy you?

With N=RF, write CL=W, read CL=R, the inequality **`R + W > N`** guarantees the read and write quorums **overlap on at least one replica**, so any read is guaranteed to include at least one replica that saw the latest acknowledged write (which wins by LWW timestamp). That gives **read-your-writes / monotonic** strong consistency.

```
N=3:  W=2 (QUORUM), R=2 (QUORUM) -> 2+2=4 > 3  -> overlap guaranteed  -> STRONG
      W=1 (ONE),    R=1 (ONE)    -> 1+1=2 ≤ 3  -> may not overlap     -> EVENTUAL
      W=3 (ALL),    R=1 (ONE)    -> 3+1=4 > 3  -> strong, fast reads / fragile writes
      W=1 (ONE),    R=3 (ALL)    -> 1+3=4 > 3  -> strong, fast writes / fragile reads
```

What it does **not** buy you: it is *not* linearizability for read-modify-write or concurrent compare-and-set — two concurrent QUORUM writes can both succeed and LWW silently drops one. For true linearizable single-key CAS you need **LWT (Paxos)**, not quorum tuning. So `R + W > N` gives **strong read consistency for last-write-wins reads**, while LWT gives **linearizable conditional writes** — different guarantees for different needs. The art is choosing W and R to balance read latency, write latency, and how much availability you sacrifice (higher quorums = less tolerance for down nodes).

### Q48. [Theory] How does multi-datacenter replication work and what are the failure modes?

With `NetworkTopologyStrategy` you set RF per DC (e.g., `{'us_east': 3, 'eu_west': 3}`). A write to the local DC's coordinator is replicated to local replicas **and** forwarded once to a coordinator in each remote DC, which fans it out to that DC's replicas. Reads are normally served by `LOCAL_QUORUM` from the local DC.

```
write (LOCAL_QUORUM, us_east):
   us_east: replica1,2,3  <- 2 acks satisfy CL, client returns
   eu_west: replica1,2,3  <- updated asynchronously (cross-DC)
```

Failure modes and handling:
- **A whole DC goes down** — `LOCAL_QUORUM` in surviving DCs is unaffected; the down DC catches up via hinted handoff and repair when it returns. `EACH_QUORUM` writes would *fail* during the outage (that's the cost of stronger guarantees).
- **Cross-DC link partition** — DCs diverge temporarily (eventual consistency); they reconcile via repair when the link heals. Choosing `LOCAL_*` CLs keeps each DC available and independent.
- **Clock skew across DCs** — LWW conflict resolution can mis-order writes; strict NTP is essential.

The design lets you trade global strong consistency (`EACH_QUORUM`, fragile) for regional availability (`LOCAL_QUORUM`, resilient). Most production geo deployments choose the latter and accept brief cross-DC inconsistency healed by repair.

### Q49. [Theory] Walk through what happens when a node is added or removed from the cluster.

**Adding a node (bootstrap):**
```
1. New node starts, contacts SEED nodes, joins gossip.
2. It is assigned token ranges (vnodes) -> it now owns parts of the ring.
3. STREAMING: existing replicas that previously owned those ranges stream the
   data to the new node (it builds up its SSTables).
4. While streaming, the node is in JOINING state (not yet serving reads at full).
5. Once streaming completes -> node goes UP/NORMAL and serves traffic.
6. Run `nodetool cleanup` on old owners to remove data they no longer own.
```

**Removing a node:**
- **Graceful (`nodetool decommission`)** — the leaving node streams its data to the new owners *before* leaving, so RF is preserved with no manual step.
- **Dead node (`nodetool removenode` / `assassinate`)** — if the node is already gone, other replicas stream the missing copies to restore RF.

Because of **consistent hashing + vnodes**, only the affected token ranges move (a fraction of total data), and streaming is parallelized across many nodes — enabling near-linear, low-disruption scaling. Pitfalls: bootstrapping multiple nodes simultaneously can violate consistency (do them one at a time, or use the proper procedure), and you must run `cleanup` afterward to reclaim space.

### Q50. [Practical] How would you diagnose and fix high read latency in a Cassandra cluster?

Work top-down from symptoms to root cause:

```
1. Scope it: nodetool tablestats / tablehistograms -> which table, which percentile?
   nodetool proxyhistograms -> coordinator-level read latency distribution.
2. SSTables per read: high "SSTables per read" => read amplification.
     -> wrong compaction strategy (STCS for update-heavy) -> switch to LCS.
     -> compaction falling behind -> nodetool compactionstats; add throughput.
3. Tombstones: tablestats "tombstones per read"; logs show tombstone warnings.
     -> queue anti-pattern / range deletes / mass TTL -> remodel; use TWCS.
4. Large/hot partitions: tablehistograms max partition size; nodetool toppartitions.
     -> repartition with bucketing / synthetic shards.
5. Wide reads / no LIMIT / ALLOW FILTERING -> bound queries; remodel.
6. JVM/GC: long GC pauses (gc logs) -> tune heap/G1, off-heap, check page cache.
7. Disk I/O: iostat -> slow disk; ensure data on fast NVMe; check read-ahead.
8. Caches: key/row cache hit rate; bloom-filter false-positive ratio.
```

The most common root causes, in rough order: **bad data model (wide/hot partitions, tombstones)**, **wrong compaction strategy for the access pattern**, and **`ALLOW FILTERING`/non-key queries doing scatter-gather**. The senior instinct is to suspect the *model* first — most "Cassandra is slow" incidents are modeling problems surfacing as read amplification, not hardware. Fixes range from query bounding (quick) to remodeling tables and backfilling (slow but durable).

### Q51. [Theory] What is the role of bloom filters, partition index, and key cache in the read path?

These three structures exist to **avoid unnecessary disk I/O** when locating a partition across many SSTables:

- **Bloom filter** (per SSTable, in memory) — a probabilistic set membership test on partition keys. It answers "could this SSTable contain key K?" with **no false negatives** (if it says no, the SSTable definitely lacks K, so skip it) and tunable false positives. This lets a read skip most SSTables instantly. Tunable via `bloom_filter_fp_chance` (lower FP = more memory).
- **Partition index** (`Index.db`) + **partition summary** (sampled, in memory) — once a bloom filter says "maybe", the summary narrows to a region of the index, and the index gives the **byte offset** of the partition in the `Data.db` file.
- **Key cache** — caches recently accessed partition-key → offset mappings, so a hot key skips the index lookup entirely.

```
read K -> for each SSTable:
   bloom filter says "no"  -> skip (cheap, in memory)
   bloom filter says "maybe" -> key cache? -> else summary -> index -> offset -> seek
```

Together they reduce a read from "scan every SSTable" to "seek directly into the one or two SSTables that actually hold the key." Their effectiveness degrades when a partition is spread across *many* SSTables (compaction behind) — which is why these structures and compaction health are intertwined.

### Q52. [Theory] Explain how Paxos powers LWT and why LWTs are expensive.

LWTs implement **linearizable compare-and-set** using a **Paxos** consensus round among the replicas of the partition. A single LWT requires up to **four round-trips**:

```
1. PREPARE / PROMISE  — proposer asks replicas to promise on a ballot number.
2. READ               — read current value to evaluate the IF condition.
3. PROPOSE / ACCEPT   — propose the new value; replicas accept if ballot is still highest.
4. COMMIT             — commit the accepted value; replicas apply and ack.
```

Why expensive:
- **4× the round-trips** of a normal write (which is one), and each round needs a quorum — so latency and inter-node traffic balloon.
- **Contention**: concurrent LWTs on the same partition contend on ballots, causing retries (livelock under hotspots).
- A separate **Paxos state** (`system.paxos`) must be read/written and later cleaned up.

So LWTs guarantee something quorum tuning cannot (true linearizable conditionals, no LWW loss), but at a steep cost. Use them only for genuinely contended invariants (unique username, "claim this slot once"). Cassandra 5.x's **Accord** (a leaderless, one-round-trip-in-the-common-case consensus) is designed to make transactions far cheaper and support multi-partition transactions — a major evolution beyond Paxos LWT.

### Q53. [Coding] Design the data model for a chat/messaging application.

Identify the queries first: (1) messages in a conversation, newest first, paginated; (2) list of a user's conversations; (3) unread counts.

```cql
-- Q1: messages in a conversation, time-ordered, bounded by bucket
CREATE TABLE messages_by_conversation (
    conversation_id uuid,
    bucket          text,        -- e.g. yyyy-mm to bound partition growth
    msg_ts          timeuuid,    -- timeuuid = time-ordered + unique
    sender_id       uuid,
    body            text,
    PRIMARY KEY ((conversation_id, bucket), msg_ts)
) WITH CLUSTERING ORDER BY (msg_ts DESC);

-- Q2: a user's conversation list, most-recently-active first
CREATE TABLE conversations_by_user (
    user_id        uuid,
    last_msg_ts    timeuuid,
    conversation_id uuid,
    peer_name      text,
    PRIMARY KEY (user_id, last_msg_ts, conversation_id)
) WITH CLUSTERING ORDER BY (last_msg_ts DESC);

-- Q3: unread counters per (user, conversation)
CREATE TABLE unread_by_user (
    user_id        uuid,
    conversation_id uuid,
    unread         counter,
    PRIMARY KEY (user_id, conversation_id)
);
```

Key decisions: **`timeuuid`** for messages (time-ordered *and* globally unique, avoiding ts collisions); **monthly bucketing** so a busy conversation's partition stays bounded; **DESC clustering** so "latest messages" needs no sort; a **separate counter table** for unread counts (accepting counter caveats). On each new message you write the message row, update `conversations_by_user` (delete old + insert new last_msg_ts, or use an upsert pattern), and increment the unread counter — a deliberate denormalized fan-out, typical of Cassandra modeling.

### Q54. [Behavioral] Tell me about a time you had to convince a team to adopt (or not adopt) Cassandra. How did you frame the trade-offs?

This question probes **technical judgment, stakeholder communication, and intellectual honesty about a tool's limits** — interviewers want to see that you choose databases based on workload fit, not hype, and that you can articulate trade-offs to non-experts. Use **STAR** and make the *decision criteria* the centerpiece.

A strong answer: "**(Situation)** A team wanted to move our primary transactional order-management system from PostgreSQL to Cassandra because they'd heard it 'scales infinitely.' **(Task)** As the senior engineer I needed to make sure the choice fit the *workload*, not the buzz, and either champion or push back with evidence. **(Action)** I mapped our actual access patterns against Cassandra's sweet spot. The order system needed **multi-row ACID transactions** (inventory + payment + order in one atomic unit), **ad-hoc operational queries** that changed monthly, and **relational integrity** — all areas where Cassandra is weak (LWT is costly and single-partition; no joins; query-first modeling fights changing queries). The data volume was also well within a single Postgres instance's comfort zone. I built a small decision matrix (consistency needs, query flexibility, scale, ops cost) and walked the team through *where Cassandra would actively hurt us*. But I also identified the part that *was* a great Cassandra fit — the high-volume **order-event audit log and activity feed** (append-only, write-heavy, time-series, never updated) — and proposed using Cassandra *there* instead. **(Result)** We kept the transactional core on PostgreSQL and adopted Cassandra for the event/audit pipeline, where it later handled 10x write growth without issue. The team learned to choose by access pattern, and I wrote a one-page 'when to use which datastore' guide that prevented several later mis-fits."

The signals this demonstrates: **workload-driven decision-making** (matching access patterns to engine strengths, not following hype), **honest articulation of weaknesses** (willing to say "Cassandra is the wrong tool here" even when it's the exciting choice), **finding the right scope** (polyglot persistence — Cassandra for the part that fits), and **durable influence** (the decision guide). A weaker answer is "I told them Cassandra was wrong"; the strong version shows you *quantified the fit per access pattern* and landed a nuanced polyglot outcome.

### Q55. [Practical] How do you safely run repairs in production without overwhelming the cluster?

Repair is necessary but I/O- and CPU-intensive, and naive repairs can degrade live traffic. The discipline:

```
1. Run repair regularly: at least once every gc_grace_seconds (default 10d)
   on every node, or tombstones may resurrect deleted data.
2. Use INCREMENTAL repair (4.x) to skip already-repaired SSTables (less work),
   OR full repair with -pr (primary range) so each range is repaired once
   cluster-wide instead of RF times.
3. Throttle: stream_throughput / compaction throughput limits so repair
   streaming/compaction don't starve client I/O.
4. Stagger: never repair all nodes at once; one (or a few) at a time, ideally
   in low-traffic windows.
5. Automate with Cassandra Reaper — it orchestrates subrange, staggered,
   resumable repairs across the cluster with backpressure.
6. Monitor: nodetool compactionstats / netstats during repair; watch p99 latency.
```

The senior framing: repair is a **scheduled, throttled, staggered, automated** operation — treating it as an occasional manual `nodetool repair` on the whole cluster is how teams either cause latency incidents or, worse, *skip it* and get zombie data. **Cassandra Reaper** is the de-facto standard precisely because correct repair scheduling (subrange, incremental, backpressure-aware, resumable) is hard to do by hand. The two failure modes to avoid: repair-induced overload (mitigated by throttling/staggering) and *no repair at all* (mitigated by automation + alerting on last-repair age).

### Q56. [Theory] What happens during compaction in detail, and how does it purge tombstones?

Compaction reads multiple input SSTables and writes new, merged SSTable(s), then deletes the inputs:

```
inputs: SSTable_A, SSTable_B, SSTable_C  (sorted by token/clustering)
 -> merge-sort rows by partition+clustering key
 -> for each cell, keep the highest-timestamp version (LWW), drop shadowed ones
 -> evaluate tombstones for purge eligibility
 -> write merged SSTable_D; delete A, B, C
```

**Tombstone purge** is the subtle part. A tombstone (or expired-TTL cell) can be dropped only when **all** of these hold:
1. `gc_grace_seconds` has elapsed since the tombstone was written.
2. **No SSTable outside this compaction** could contain older, shadowed data for that key — otherwise dropping the tombstone would un-delete that data. (This "overlapping SSTable" check is why a tombstone in one SSTable can't be purged if shadowed data sits in another SSTable not part of the same compaction.)
3. The cluster has been repaired so all replicas know the delete (operationally enforced by repairing within `gc_grace_seconds`).

This is why tombstones can **linger far longer than `gc_grace_seconds`** if the shadowed data is in SSTables that never get compacted together — a real production gotcha. Strategies like LCS and TWCS, and tools like `nodetool garbagecollect` or single-SSTable tombstone compaction, exist to force purge in stubborn cases.

### Q57. [Theory] How does Cassandra handle the CAP and PACELC theorems?

Under **CAP**, Cassandra is an **AP** system: during a network **partition** it chooses **availability** — nodes keep serving reads/writes with whatever replicas they can reach, accepting temporary inconsistency that's healed later. It does *not* sacrifice availability to guarantee consistency (it won't refuse writes just because some replicas are unreachable, given a satisfiable CL).

But CAP only describes behavior *during a partition*. **PACELC** is more precise: **PA/EL**. *Partition → Availability*; *Else (normal operation) → Latency*. In the common no-partition case, Cassandra trades **consistency for latency** — low consistency levels (ONE) give the lowest latency, higher levels (QUORUM/ALL) give more consistency at higher latency.

```
PACELC: if Partition then (A vs C) -> A
        else            (L vs C) -> tunable, default leans L
```

The nuance that impresses interviewers: Cassandra's **tunable consistency** means it isn't dogmatically AP/EL — *you* slide the dial per query. A QUORUM/QUORUM workload behaves much more like a CP/EC system for that operation. So the precise statement is: "Cassandra is *fundamentally* PA/EL, but tunable consistency lets you move toward CP/EC per query at the cost of latency and availability."

### Q58. [Practical] How do you handle schema changes and migrations safely in Cassandra?

Cassandra schema changes propagate via gossip and are generally **online** (no table lock for adding columns), but there are sharp edges:

```cql
-- SAFE & cheap (metadata-only, no data rewrite):
ALTER TABLE orders ADD coupon_code text;
ALTER TABLE orders DROP coupon_code;   -- careful: see below

-- RISKY / disallowed:
-- cannot change a column's type in incompatible ways
-- cannot change PRIMARY KEY columns -> must create a NEW table + migrate
```

Safe practices:
- **Adding a column** is metadata-only and cheap. **Dropping** a column leaves data on disk until compaction and can clash if you re-add with a different type — prefer additive evolution.
- **You cannot alter the primary key.** A new access pattern → **new table + backfill** (dual-write new + old, backfill historical data, cut reads over, retire old table).
- **Avoid concurrent DDL from many clients** — schema disagreements ("schema mismatch") across nodes can occur; apply DDL from one place and wait for `nodetool describecluster` to show a single schema version.
- For large backfills, use **Spark** or batched async writes with throttling, not a single client loop.

The senior point: because you can't reshape the primary key, Cassandra migrations are mostly **"new table + dual-write + backfill + cutover"** rather than in-place `ALTER`. Plan access patterns up front; treat each new query as potentially a new table.

### Q59. [Theory] What is `ALLOW FILTERING` and why is it dangerous?

`ALLOW FILTERING` tells Cassandra to **scan and filter rows that the query's predicate cannot satisfy via the primary key/index** — i.e., to read more data than the key structure allows and filter in memory.

```cql
-- Refused without ALLOW FILTERING because status isn't a key/indexed column:
SELECT * FROM orders WHERE status = 'PENDING' ALLOW FILTERING;
```

Why it's dangerous:
- It can trigger a **full-table / multi-partition scan** (scatter-gather across the whole cluster), with latency that grows with data size — **unpredictable and unbounded**.
- It works fine on tiny tables in dev, then **falls off a cliff** in production as data grows — a latent landmine.
- It can overload coordinators and cause timeouts under load.

The one *acceptable* use is filtering on a **non-key column *within a single, already-restricted partition*** (so the scan is bounded to one partition's rows). Even then, prefer a model that doesn't need it. The rule: **`ALLOW FILTERING` in a production query is a red flag** — it almost always means the table is modeled for a different access pattern, and the fix is a new query table or an appropriate index (SAI), not the filtering flag.

### Q60. [Practical] How do you tune compaction throughput and concurrent compactors, and what's the trade-off?

Compaction must keep up with write volume (or read amplification climbs as SSTables pile up), but it competes with client traffic for disk I/O and CPU.

```
# nodetool / cassandra.yaml levers:
nodetool setcompactionthroughput 64        # MB/s cap (0 = unlimited)
concurrent_compactors: <num>               # parallel compaction threads
                                           # (default ~ min(disks, cores))
compaction_throughput_mb_per_sec: 64       # yaml default
```

The trade-off:
- **Too low throughput / too few compactors** → compaction falls behind → SSTable count grows → **read amplification** and pending compactions pile up (`nodetool compactionstats` shows a growing backlog) → latency degrades.
- **Too high** → compaction starves client reads/writes of I/O → **client p99 latency spikes**, page cache churn.

Tuning approach: watch `nodetool compactionstats` for **pending compactions trending up** (under-provisioned) vs. client latency suffering during compaction bursts (over-aggressive). On fast NVMe you can afford higher throughput; on slower disks throttle harder and lean on TWCS/LCS to reduce total compaction work. Also size `concurrent_compactors` to your CPU/disk and avoid setting it so high it starves the JVM. The principle: compaction is a **continuous background tax** you must size to your write rate and hardware — not a knob to crank blindly in either direction.

### Q61. [Theory] How does Cassandra detect node failures (the failure detector)?

Cassandra uses a **Phi (Φ) Accrual Failure Detector** rather than a simple binary up/down heartbeat. Through gossip, each node tracks the **inter-arrival times of heartbeats** from its peers and builds a statistical model of expected arrival. From that it computes a continuously-varying suspicion level **Φ** — roughly, the log-probability that the node has failed given how overdue its last heartbeat is.

```
recent heartbeat intervals -> distribution -> if a heartbeat is very overdue,
Φ rises; when Φ exceeds phi_convict_threshold (default 8), the node is marked DOWN.
```

Why accrual (vs. a fixed timeout): a single static timeout is brittle — too short causes false positives on a slow network, too long delays real-failure detection. The accrual detector **adapts to observed network conditions**: on a flaky/slow network the expected interval widens, so it won't prematurely convict; on a fast, stable network it convicts quickly. `phi_convict_threshold` tunes sensitivity (higher = more tolerant of latency, slower to convict). Marking a node down triggers hinted handoff for its writes and removal from the read/write replica set until gossip sees it return. This adaptiveness is what keeps a large, geo-distributed, masterless cluster from constantly flapping nodes up and down.

## 🔴 Expert (15+ yrs)

### Q62. [Theory] Cassandra is "always available for writes" — explain the deep mechanics and the precise guarantees and non-guarantees.

The write-availability story rests on several layered mechanisms, and the precision matters:

1. **No read-before-write + masterless routing** — any node can coordinate, and a write just appends (commit log + memtable). There's no leader to fail over, no lock, no existence check, so the *write itself* is always cheap and any live coordinator can accept it.
2. **Tunable write CL** — at `ANY`, a write succeeds even if **no replica is up**, because a **hint** on the coordinator counts as success. At `ONE`/`LOCAL_ONE`, one replica suffices. Only `QUORUM`/`ALL`/`EACH_QUORUM` can *fail* a write due to insufficient live replicas.
3. **Hinted handoff** bridges briefly-down replicas; **read repair** and **`nodetool repair`** reconcile afterward.

The **non-guarantees** an expert must state plainly:
- "Always available" is **only true at low CL**. At `QUORUM` with too many replicas down, writes *do* fail — availability is **tunable, not absolute**.
- **Durability at `ANY` is weak**: if the coordinator dies before replaying its hint and no replica got the write, it's lost. `ANY` trades durability for availability.
- **No conflict merging** — concurrent writes resolve by LWW, so "available" doesn't mean "no lost updates"; one concurrent update is silently dropped.
- **Clock skew** can cause a newer write to lose to an older one.

So the precise expert statement: *"Cassandra offers tunably-high write availability via masterless append-only writes, hinted handoff, and low consistency levels — but availability, durability, and the absence of lost updates are all dials you trade against each other, not guarantees you get for free."*

### Q63. [Theory] Discuss read repair's subtleties: blocking vs. non-blocking, digest reads, and why modern Cassandra removed `read_repair_chance`.

Modern read repair (4.0+) is driven by the **read path and consistency level**, not a background probability. The mechanics:

- **Digest reads.** For a read at CL > ONE, the coordinator sends a **full data request to one replica** and **digest (hash) requests to the others**. If all digests match the data, no repair is needed — cheap. If a digest **mismatches**, the coordinator issues full reads to reconcile.
- **Blocking read repair.** When a mismatch is found, the coordinator computes the latest value and **writes it back to the stale replicas, *blocking* the read response until enough replicas are repaired to satisfy the consistency level.** This is what makes `QUORUM` reads monotonic — the read doesn't return until the consistency guarantee actually holds, closing a subtle consistency hole that older "background" repair left open.
- **Read repair scope** is now configurable per table (`read_repair = 'BLOCKING'` default, or `'NONE'`).

Why `read_repair_chance` / `dclocal_read_repair_chance` were **removed**: they performed *probabilistic background* repair on reads, which (a) gave no consistency guarantee (it was best-effort), (b) added cross-DC traffic and latency unpredictably, and (c) was redundant with the now-correct **blocking** repair tied to CL. The redesign made read repair **deterministic and consistency-correct** rather than a statistical band-aid. The expert insight: blocking read repair is part of *why* `R + W > N` actually yields monotonic reads in modern Cassandra — the read won't complete until the overlap is reconciled.

### Q64. [Theory] What is Cassandra's Accord / transaction roadmap, and how does it change the consistency story?

**Accord** (CEP-15, landing through Cassandra 5.x) is a **new consensus protocol** designed to give Cassandra **strict-serializable, general-purpose, multi-partition transactions** — something LWT/Paxos cannot do (LWT is single-partition and expensive).

What makes Accord notable:
- **Leaderless** — unlike Raft/multi-Paxos which funnel through a leader (a bottleneck and a failover liability), Accord is leaderless, fitting Cassandra's masterless ethos.
- **One round-trip in the common (uncontended) case** — using **reorder buffering** and **globally synchronized timestamps** (hybrid logical clocks, ideally with tight clock sync like spanner-style TrueTime-lite), Accord can reach agreement in a single round-trip when there's no conflict, versus Paxos LWT's ~four.
- **Multi-partition, multi-key transactions** with strict serializability — true ACID transactions across partitions, not just single-key CAS.

How it changes the story: historically Cassandra forced you to choose denormalization + LWW + occasional costly single-key LWT. Accord adds a **genuine transactional tier** that's cheap enough to use more broadly, narrowing the gap with NewSQL systems while keeping Cassandra's masterless, multi-DC, linearly-scalable core. The nuance for interviews: Accord doesn't replace tunable consistency or denormalized modeling — it **adds** a strong-transaction option for the cases that need it, so the architecture becomes "fast eventual/LWW by default, cheap strong transactions when you ask." It's the most significant consistency evolution in Cassandra's history and a sign of the NoSQL/NewSQL convergence.

### Q65. [Practical] Design a globally-distributed, multi-region system on Cassandra with strong-ish consistency and disaster recovery. Walk through the key decisions.

Goal: a user-facing service across `us-east`, `eu-west`, `ap-south` with low local latency, regional fault tolerance, and DR.

```
Topology:
  3 DCs, NetworkTopologyStrategy, RF=3 per DC (9 copies total).
  Each DC spread across 3 racks/AZs -> survives an AZ loss with quorum intact.

Consistency:
  Default reads/writes: LOCAL_QUORUM  (strong within a region, no cross-DC latency).
  Cross-region async replication keeps DCs converged; repair heals the rest.
  Reserve EACH_QUORUM only for the rare globally-critical invariant.

Routing:
  Clients pinned to nearest DC (DC-aware load-balancing policy, LOCAL_* CLs).
  Geo-DNS / app routing sends users to their home region.

Conflict / correctness:
  LWW + strict NTP/chrony for clock sync (clock skew = lost writes).
  Use LWT/Accord only for true cross-key invariants (rare).

DR:
  A whole region down -> traffic fails over to another region (still LOCAL_QUORUM
  there). The down region rejoins via hinted handoff + repair.
  Backups: nodetool snapshot + incremental backups shipped to object storage;
  test restores. Consider an extra "backup-only" DC with RF for point-in-time.
```

The decision rationale to articulate: **`LOCAL_QUORUM` everywhere** is the crux — it gives regional strong consistency and isolation (one region's outage/slowness doesn't block others) while accepting brief cross-region eventual consistency healed by repair. RF=3 per DC across 3 AZs gives AZ-failure tolerance *and* local quorum survivability. You explicitly **avoid global-strong CLs** (`EACH_QUORUM`) as the default because they make every write hostage to your slowest/most-fragile region. DR rests on **failover + snapshots + tested restores**, not just replication (replication is not backup — a bad delete replicates everywhere). The expert framing: design for **regional autonomy with eventual global convergence**, and reserve global-strong semantics for the narrow slice that truly needs it.

### Q66. [Theory] Deep dive: how do clock skew and timestamp resolution threaten correctness, and how do you mitigate them?

Because Cassandra resolves all conflicts by **last-write-wins on a microsecond timestamp**, the *correctness of your data depends on clock accuracy* — an unusual and underappreciated property.

Failure modes:
- **Lost updates from skew.** Coordinator A's clock is 50 ms ahead of B's. A write routed through B (logically *later* in wall time) gets a *smaller* timestamp than an earlier write through A — so the genuinely newer write **loses and is silently discarded**. No error, just missing data.
- **Resolution collisions.** Two writes in the same microsecond to the same cell tie on timestamp; the tiebreak is a value comparison, which can pick the "wrong" one relative to causal order.
- **`USING TIMESTAMP` misuse.** Manually setting a far-future timestamp can make a write **immortal** (nothing can overwrite it until wall-clock catches up) — a foot-gun, sometimes weaponized to "pin" data but disastrous by accident.
- **Tombstone vs. data ordering.** A delete (tombstone) with a skewed-low timestamp may fail to shadow a write it should have deleted, or vice versa — causing resurrected or wrongly-deleted data.

Mitigations:
- **Tight time sync** — NTP with multiple sources, or `chrony`; in serious deployments, consider higher-precision sync. Monitor and alert on clock drift across nodes.
- **Prefer server-side timestamps** consistently (don't mix client- and server-assigned), and avoid manual `USING TIMESTAMP` except deliberately.
- **Model to avoid concurrent in-place updates to the same cell** — append-style data, per-source columns, or counters/CRDT-like patterns sidestep LWW collisions entirely.
- For invariants that truly can't tolerate LWW races, use **LWT/Accord** (linearizable), which don't rely on wall-clock ordering for correctness.

The expert point: in Cassandra, **the cluster's clocks are part of the correctness model**. "It's eventually consistent" hides the sharper truth that *clock skew can cause permanent, silent data loss under LWW* — so time discipline and modeling-away-of-concurrent-cell-updates are first-class operational concerns, not afterthoughts.

### Q67. [Behavioral] Describe leading the resolution of a severe Cassandra production incident and the systemic changes you made afterward.

This question tests **incident command under pressure, evidence-driven diagnosis in a distributed system, and — most importantly — durable prevention** that separates staff-level engineers from firefighters. Use **STAR** and weight the *prevention* as heavily as the heroics.

A strong answer: "**(Situation)** During a campaign, read p99 on our largest cluster jumped from 12 ms to multi-second and queries started timing out — a Sev-1 with checkout impact. **(Task)** As on-call lead I had to restore service fast *without* a blind change that could worsen it, then find the true root cause. **(Action)** I declared the incident and assigned roles (comms owner, scribe) so I could focus on diagnosis instead of fielding questions, then worked the evidence systematically rather than guessing. `nodetool tablehistograms` showed **SSTables-per-read had exploded** on one table and **tombstones-per-read** was in the thousands; the logs were full of **tombstone-threshold warnings**. Correlating with a recent feature deploy, I found we'd shipped a 'mark notifications as read by deleting them' path — a **queue/delete anti-pattern** generating tombstone storms in hot partitions, while `nodetool compactionstats` showed compaction badly behind so the tombstones weren't being purged. I made the smallest reversible move first — **feature-flagged off the delete path** to stop new tombstones (latency began recovering) — rather than reflexively restarting nodes, then ran a **targeted single-SSTable tombstone compaction / `garbagecollect`** on the affected table to drain the existing tombstones, and temporarily raised compaction throughput within I/O headroom. **(Result)** Service recovered in ~20 minutes, fully resolved within the hour, zero data loss. In the **blameless post-incident review** I drove systemic changes: (1) replaced the delete-to-mark-read design with an **append + TTL + TWCS** model so 'reads' no longer create tombstones and old data drops by whole SSTable; (2) added **alerting on tombstones-per-read and SSTables-per-read and pending-compaction backlog** — the leading indicators I'd had to find manually; (3) a **schema/PR review checklist** that flags delete-heavy and queue-like access patterns before they ship; (4) wrote a 'Cassandra tombstone incident' runbook codifying the `tablehistograms`/`compactionstats`/deploy-correlation sequence."

The signals: **incident command** (roles, comms, blameless review), **distributed-systems-specific evidence** (tombstones/SSTables-per-read, compaction backlog — not generic 'it's slow'), **reversible-smallest-change-first** judgment (flag off before forcing compaction or restarting nodes), and — the staff-level differentiator — **turning one incident into permanent prevention at multiple layers**: eliminate the bug class (append+TTL+TWCS remodel), detect the failure mode sooner (leading-indicator alerts), and catch it before it ships (review checklist + runbook). A weak answer stops at "we ran a compaction and it fixed itself"; the strong one shows the remodel and the prevention, which is what's really being tested.

### Q68. [Theory] How would you evaluate whether to use Cassandra 5.0's SAI and vector search versus alternatives for a semantic-search / RAG workload?

Cassandra 5.0 adds **Storage-Attached Indexing (SAI)** — a unified, efficient secondary index integrated with the storage engine — and **vector search** (an `ANN`/approximate-nearest-neighbor index over `vector` columns, e.g., for embeddings). This positions Cassandra as a candidate **vector database** for RAG/semantic-search, especially when you already run Cassandra.

Evaluation framework:
- **When Cassandra+SAI/vector fits.** You already operate Cassandra at scale and want to **co-locate embeddings with operational data** (avoid a separate vector DB and the sync/consistency burden between them); you need **massive write throughput and horizontal scale** for vectors; you want **multi-region** vector serving with Cassandra's replication; and your recall/latency needs are met by its ANN implementation. SAI also fixes classic 2i's pain — efficient, integrated, supports range/prefix/numeric/text and AND-ing predicates.
- **When a dedicated vector DB wins.** If you need **state-of-the-art ANN algorithms/tuning, advanced filtering+ANN hybrid ranking, or specialized index types** (HNSW variants, quantization, advanced reranking) that purpose-built engines (e.g., dedicated vector stores) optimize harder; or if your scale is modest and a managed vector service is operationally simpler; or you need features Cassandra's vector index doesn't yet match.
- **Trade-offs to weigh.** Consistency model (Cassandra's tunable/eventual vs. a vector DB's guarantees), recall vs. latency tuning knobs, operational footprint (one system vs. two), and lock-in.

The expert framing: the decision is usually **"one system vs. two."** Cassandra 5.0's vector + SAI shines for teams who already have Cassandra and value **operational consolidation and scale/availability** over having the most cutting-edge ANN tuning — keeping embeddings next to the operational data with one replication/HA story. A team whose product *is* search quality, or who needs the most advanced ANN features, may still prefer a specialized vector engine. Benchmark **recall@k and p99 latency on your real embeddings and filters**, not synthetic data, before committing — and weigh the consistency model against your RAG freshness requirements.

## 🧩 Extended Questions — Set 1: Deeper theory & internals

These questions go beneath the surface mechanics already covered, into the byte-level layout, the gossip/Merkle/streaming internals, hybrid logical clocks, memory architecture, and the exact rules governing tombstone purge, range tombstones, and SSTable formats. They assume you know the basics above and want the "how is this actually implemented" level that staff interviews probe.

### 🟢 — extended

#### Q69. [Theory] What component files make up a single SSTable on disk, and what is each one for?

An SSTable is not one file but a **set of component files sharing a generation/identifier**, each holding one facet of the data so reads can use small, cacheable structures instead of scanning the big data file.

- **`Data.db`** — the actual rows: partitions sorted by token, rows within a partition sorted by clustering key, with cell values, timestamps, and TTLs. This is the only large file.
- **`Index.db`** — the **partition index**: for each partition key, its byte offset into `Data.db` (and, for large partitions, an embedded index of clustering positions within the partition).
- **`Summary.db`** — a **sampled** subset of `Index.db` kept in memory; it narrows a key lookup to a small region of `Index.db` so you don't binary-search the whole index file.
- **`Filter.db`** — the serialized **bloom filter** (also loaded into memory) answering "could this SSTable contain key K?"
- **`CompressionInfo.db`** — offsets of compressed chunks, so the reader can decompress only the needed chunk of `Data.db`.
- **`Statistics.db`** — metadata: min/max timestamps, clustering ranges, estimated row/tombstone counts, partitioner, compression ratio — used by compaction and query planning.
- **`Digest.crc32` / `CRC.db`** — checksums for corruption detection.
- **`TOC.txt`** — a table of contents listing the components.

The split is the LSM trick made physical: the big file is immutable and append-written; the small companion files (summary, filter, stats) are cheap to hold in memory and let a read skip straight to the right offset.

#### Q70. [Theory] What is the difference between a row-level delete, a cell-level delete, and a range tombstone?

Cassandra has **several kinds of tombstone**, and they differ in how much they shadow and how expensive they are:

- **Cell tombstone** — deletes a single column of a single row (`DELETE col FROM t WHERE pk=… AND ck=…`, or overwriting a cell with `null`). Smallest scope.
- **Row tombstone** — deletes an entire row (`DELETE FROM t WHERE pk=… AND ck=…`). Shadows all of that row's cells at once.
- **Range tombstone** — deletes a *contiguous range* of clustering keys in one marker (`DELETE FROM t WHERE pk=… AND ck >= ? AND ck < ?`). A single marker shadows many rows, so it is space-efficient to write — but it is **read-expensive**, because during a read every range tombstone in the queried slice must be carried in memory and checked against each candidate row to decide what's deleted.
- **Partition tombstone** — deletes the whole partition (`DELETE FROM t WHERE pk=…`). One marker shadows everything in the partition.
- **TTL / expired-cell tombstone** — a cell whose TTL elapsed; it becomes a tombstone automatically at expiry.

The practical lesson: a range tombstone is cheap to write but can dominate read cost if a partition accumulates many of them (the classic queue anti-pattern produces range/row tombstone storms). Partition-level deletes are the cheapest way to remove a lot of data because they are one marker that the read path can short-circuit on.

#### Q71. [Theory] What does `WRITETIME()` return, and why is it useful for reasoning about conflicts?

`WRITETIME(col)` returns the **microsecond timestamp** attached to a cell — the value Cassandra uses for last-write-wins conflict resolution. It exposes the otherwise-hidden metadata that decides which version of a cell survives.

```cql
SELECT status, WRITETIME(status) AS ts FROM orders_by_customer
WHERE customer_id = 11111111-1111-1111-1111-111111111111
  AND order_ts = '2026-06-30 10:15:00';
```

Why it matters:
- **Debugging "lost" updates** — if a write seemed to vanish, comparing `WRITETIME` across replicas or against your expectation reveals clock-skew or stale-timestamp problems (a logically newer write that carries an older timestamp loses).
- **Understanding LWW** — it makes the conflict rule concrete: two cells with different `WRITETIME` resolve to the higher one; equal timestamps tie-break on value.
- **Manual timestamps** — it lets you verify what `USING TIMESTAMP` actually wrote, which is essential when you deliberately override timestamps (e.g., backfills).

Note `WRITETIME` works on regular columns, not on primary-key columns (which have no separate cell) or counters (which track timestamps differently).

#### Q72. [Coding] Write CQL to fetch a page of results using paging/`PER PARTITION LIMIT`, and explain how server-side paging works.

Cassandra paginates with a **paging state** (an opaque cursor the driver tracks), not `OFFSET`. There is no `OFFSET` because skipping N rows would still require reading them. For "top-K per partition" Cassandra offers `PER PARTITION LIMIT`.

```cql
-- Cap rows returned per partition (e.g., latest 3 readings for EACH sensor in a multi-row IN)
SELECT sensor_id, reading_ts, temp
FROM readings_by_sensor
WHERE sensor_id IN ('sensor#42', 'sensor#43') AND bucket = '2026-06-30'
PER PARTITION LIMIT 3;

-- Ordinary LIMIT bounds the TOTAL rows; combine with paging for large result sets
SELECT reading_ts, temp
FROM readings_by_sensor
WHERE sensor_id = 'sensor#42' AND bucket = '2026-06-30'
LIMIT 1000;
```

How server-side paging works: the driver requests a **fetch size** (e.g., 5,000 rows). The coordinator returns one page plus a **paging state** (an encoded position: token + clustering position). The next request replays that state so the scan **resumes where it left off** without re-reading. This keeps memory bounded on both client and coordinator. `PER PARTITION LIMIT` is evaluated on each replica before clustering-LIMIT, making it efficient for "newest N rows per partition" queries that would otherwise over-read.

#### Q73. [Theory] What is a partitioner, and why does Cassandra default to Murmur3Partitioner instead of a hash like MD5 or the order-preserving partitioner?

A **partitioner** is the function mapping a partition key to a **token**, which determines ring position and therefore data placement. Cassandra ships several:

- **Murmur3Partitioner (default)** — uses MurmurHash3 to produce a 64-bit signed token (−2⁶³ … 2⁶³−1). It is **fast** (non-cryptographic, cheap to compute) and **uniformly distributed**, which is exactly what you want for even data spread.
- **RandomPartitioner (legacy)** — MD5-based, producing a 127-bit token. Also uniform, but MD5 is **slower** (cryptographic hash, unnecessary work here) — Murmur3 replaced it for performance.
- **ByteOrderedPartitioner / OrderPreservingPartitioner (deprecated, avoid)** — tokens preserve key order, theoretically enabling range scans across partition keys. In practice they cause **severe hotspots and uneven load** (sequential keys cluster on adjacent ranges/nodes) and make balancing a nightmare. The whole point of hashing is to *destroy* locality so load spreads.

The default is Murmur3 because the design goal is **uniform, cheap distribution**, not range-scannability across partition keys — you get range scans *within* a partition via clustering columns instead. The partitioner is a cluster-wide setting fixed at creation; you cannot change it on a live cluster without rebuilding.

#### Q74. [Theory] How is data physically laid out inside a single partition in the Data.db file?

Within one partition, the on-disk layout (modern "big"/`bti` SSTable formats) is:

```
[ partition key ] [ partition-level deletion info (partition tombstone, if any) ]
[ static row (static columns, if the table has them) ]
[ row 1: clustering key | row-level deletion | cells (col:value:timestamp:ttl) ]
[ row 2: clustering key | ... ]
[ range tombstone markers interleaved at their clustering boundaries ]
...
```

Rows are stored **sorted by clustering key** (in the table's clustering order), which is *why* range scans and `ORDER BY` within a partition are free — the data is already in order, so a read is a sequential scan from a starting offset. Each **cell** carries its own timestamp and optional TTL, which is how cell-level LWW and per-cell expiry work. **Range tombstone markers** are stored at their open/close clustering boundaries and must be merged with rows at read time. For **large partitions**, the `Index.db` stores an internal index of clustering positions so a read can seek partway into the partition rather than scanning from its start. This layout is the concrete reason the data model rules exist: the partition key picks the file/offset; the clustering key picks the position within the sorted run.

#### Q75. [Theory] What is a "static column" and how does it relate to the partition?

A **static column** is a column whose value is **shared by all rows in a partition** — it belongs to the partition as a whole, not to any individual clustering row.

```cql
CREATE TABLE invoices_by_customer (
    customer_id  uuid,
    line_no      int,
    customer_tier text STATIC,     -- one value per customer (partition)
    sku          text,
    qty          int,
    PRIMARY KEY (customer_id, line_no)
);
```

Here every line item (clustering row) under a `customer_id` sees the same `customer_tier`; updating it updates it for the whole partition. Physically the static row is stored **once per partition** (right after the partition header, before the clustered rows), not duplicated per row. Use cases: partition-level metadata you'd otherwise duplicate into every row (a header/summary value, a denormalized parent attribute). Caveats: a static column can only exist in a table that **has clustering columns** (a partition with multiple rows); and you can read/write it without specifying a clustering key. It's a small but useful tool for keeping per-partition data DRY without a second table.

### 🟡 — extended

#### Q76. [Theory] Explain hybrid logical clocks (HLC) and where Cassandra's timestamps fit relative to them.

A **Hybrid Logical Clock** combines a **physical (wall-clock) component** with a **logical counter** so that timestamps (a) stay close to real time, yet (b) **respect causality** even under small clock skew, by bumping the logical part when wall-clocks would otherwise go backwards or collide.

```
HLC tick:  physical = max(local_wall_clock, last_seen_physical)
           if physical == last_seen_physical: logical += 1   else logical = 0
```

Where Cassandra sits: classic Cassandra writes use a **plain microsecond wall-clock timestamp** (client- or coordinator-assigned) for LWW — *not* a true HLC. That is precisely why clock skew can cause silent lost updates: there's no logical component to break ties causally; a skewed-low wall clock just loses. The **Accord** transaction protocol (CEP-15) moves Cassandra toward **HLC-style globally-meaningful timestamps** to order transactions across the cluster with one round-trip in the common case. So the precise statement: legacy LWW = wall-clock only (skew-sensitive); Accord = hybrid-logical-style timestamps (causally robust). Knowing this distinction explains both why old advice obsesses over NTP and why Accord is a genuine correctness upgrade, not just a speed one.

#### Q77. [Theory] Walk through exactly how `nodetool repair` builds and compares Merkle trees, and what gets streamed.

Anti-entropy repair reconciles replicas by exchanging **Merkle trees** (binary hash trees over token ranges) rather than the data itself, so only differences cross the wire.

```
1. The repair coordinator splits the token range into subranges and asks each
   replica to build a Merkle tree (a "validation compaction" — it scans the
   SSTables for that range and hashes row data into tree leaves).
2. Each leaf hash covers a sub-subrange; parent nodes hash their children, up to a root.
3. Replicas send their trees to the coordinator, which compares them top-down:
     - equal subtree hash  -> that whole range is identical, skip it (the win).
     - differing hash      -> descend to find the specific differing leaf ranges.
4. For each differing leaf range, replicas STREAM the actual rows to each other
   so every replica converges to the union of latest (LWW) data.
```

Key internals/costs: building the tree requires reading the data (**validation compaction** — CPU and disk I/O), which is why repair is heavy; the **tree depth/granularity** bounds precision (coarser trees flag larger ranges as "different," streaming more than strictly necessary); and **vnodes** multiply the number of small ranges to validate. Incremental repair (4.x) marks SSTables as "repaired" so already-reconciled data is skipped, shrinking each subsequent repair. The elegance: comparing kilobytes of hashes lets you avoid shipping gigabytes of identical data — you only stream the genuine deltas.

#### Q78. [Theory] How does Cassandra streaming work (during bootstrap, repair, and rebuild), and what is zero-copy streaming?

**Streaming** is the mechanism that moves SSTable data between nodes — used on bootstrap (new node), decommission, repair (differing ranges), and rebuild/replace.

Classic streaming: the sending node reads the relevant rows out of its SSTables, **serializes them through the normal write path representation**, sends them over the network, and the receiver **deserializes and writes new SSTables**. This is correct but CPU-heavy on both ends (serialize/deserialize, re-index, re-bloom-filter).

**Zero-copy streaming (a.k.a. "fast streaming," Cassandra 4.0+):** when an *entire SSTable* needs to move (common in repair/bootstrap with vnodes), Cassandra can stream the **raw SSTable file bytes directly** — block by block — without deserializing rows into objects and re-serializing them. It uses the OS's efficient file transfer paths and skips the per-row CPU work.

```
classic:  SSTable -> rows -> objects -> network -> objects -> rows -> new SSTable (CPU-heavy)
zero-copy: SSTable file bytes -> network -> SSTable file bytes (near-disk-speed)
```

Impact: zero-copy streaming made bootstrapping and repair **dramatically faster** (often several times), which in turn makes scaling out and replacing failed nodes far less disruptive — a meaningful operational improvement in 4.0. It applies when whole SSTables can be moved; partial-range streaming still uses the row-based path.

#### Q79. [Theory] What is the batchlog, where does it live, and how does it make a LOGGED batch atomic?

A **LOGGED batch** is made atomic (all-or-nothing across its statements) by the **batchlog**: before applying the batch, the coordinator writes the entire batch to a **`system.batches`** table **replicated to two other nodes** (in the same or different racks/DCs for resilience). Only after the batchlog is durably recorded does the coordinator apply the individual mutations to their target replicas.

```
1. Coordinator serializes the batch -> writes it to the batchlog (2 replica nodes).
2. Coordinator applies each statement to its target replicas.
3. On success -> coordinator deletes the batchlog entry.
4. If the coordinator DIES mid-batch -> the batchlog replicas notice the
   un-deleted entry after a timeout and REPLAY the batch to completion.
```

So "atomic" here means **eventually all statements apply even if the coordinator crashes** — not isolation. There is **no isolation**: other readers can observe a partially-applied batch in flight, and the batch is not a transaction (no rollback, no read-consistency snapshot). This is exactly why LOGGED batches are for **keeping denormalized copies of the same data eventually-consistent across a few tables**, not for transactional integrity or bulk loading. The batchlog write is also extra work (write amplification + 2 extra nodes), which is part of why multi-partition LOGGED batches are costly.

#### Q80. [Theory] What is speculative retry, and how does it reduce tail latency?

**Speculative retry** is a coordinator tactic to cut **p99/p999 tail latency** caused by one slow replica. Normally a read at, say, `LOCAL_QUORUM` waits for the required replicas; if one of them is a straggler (GC pause, hot disk), the whole read waits on it. Speculative retry lets the coordinator **send an extra read request to one more replica** if the original ones are taking too long.

```cql
ALTER TABLE orders_by_customer
  WITH speculative_retry = '99PERCENTILE';   -- fire a spare read past the p99 latency mark
-- alternatives: '50ms' (fixed threshold), 'ALWAYS', 'NONE'
```

How it helps: the coordinator tracks the table's recent read-latency distribution; if the in-flight read exceeds the configured threshold (e.g., the 99th percentile), it proactively asks an additional replica, then returns whichever response satisfies the consistency level first. This **hedges against a single slow node** without waiting for a timeout. The trade-off: it generates **extra read load** (more requests when nodes are slow, exactly when they're already stressed), so overly aggressive settings (`ALWAYS`) can amplify load. `99PERCENTILE` is a good default — it only kicks in for genuinely abnormal reads. This is the read-path analog of hedged requests in other distributed systems.

#### Q81. [Coding] Write CQL using `IN` on a clustering key vs. on a partition key, and explain why one is far more dangerous.

```cql
-- SAFE-ish: IN on a CLUSTERING key, within a SINGLE partition
SELECT * FROM readings_by_sensor
WHERE sensor_id = 'sensor#42' AND bucket = '2026-06-30'
  AND reading_ts IN ('2026-06-30 10:00', '2026-06-30 10:05');
-- one partition, a few sorted seeks -> cheap

-- DANGEROUS: IN on the PARTITION key spans MANY partitions/nodes
SELECT * FROM readings_by_sensor
WHERE sensor_id IN ('sensor#1','sensor#2', ... ,'sensor#500')
  AND bucket = '2026-06-30';
-- the coordinator must scatter-gather to up to 500 partitions across the cluster
```

Why the partition-key `IN` is dangerous: each value in the `IN` is potentially a **different token on a different node**, so a large `IN` turns one query into a **scatter-gather** the coordinator must fan out, collect, and merge — concentrating load and latency on that coordinator and ballooning tail latency. It also defeats client-side token-aware routing (the request can't go to a single owning replica). A clustering-key `IN`, by contrast, stays **within one partition** (already located by the partition key) and just seeks to a few sorted positions — bounded and cheap. Best practice: keep partition-key `IN` lists tiny (or better, issue **parallel async single-partition queries** from the client and merge results), and reserve `IN` for clustering keys within a known partition.

#### Q82. [Theory] How do row cache, key cache, counter cache, and chunk (page) cache differ, and when is each safe to enable?

Cassandra has several caches at different layers of the read path:

- **Key cache** (on by default) — maps **partition key → byte offset** in an SSTable, skipping the partition-index lookup. Cheap, small, almost always beneficial; safe to leave on.
- **Row cache** (off by default) — caches **entire partitions (the head of a partition, up to N rows)** in memory. Powerful for **small, hot, read-heavy, rarely-updated** partitions, but **dangerous otherwise**: a write to a cached partition **invalidates the whole cached entry**, and caching large partitions wastes memory and can thrash. Enable only on tables with small partitions and a high read:write ratio.
- **Counter cache** — caches counter values to reduce the read-before-write that counter updates require, speeding counter-heavy workloads.
- **Chunk cache / page cache** — Cassandra relies heavily on the **OS page cache** (and an off-heap chunk cache for compressed data) to keep hot `Data.db` chunks in RAM. This is why Cassandra wants lots of free RAM *outside* the JVM heap and why over-sizing the heap (starving page cache) hurts reads.

The guidance: trust **key cache + OS page cache** as the workhorses; reach for **row cache only on small hot read-mostly partitions**; size the JVM heap modestly so the OS page cache stays large. Misusing row cache on large or write-heavy partitions is a classic self-inflicted performance wound.

#### Q83. [Theory] What memtable flush triggers exist, and what happens to the commit log when a memtable flushes?

A memtable is flushed to an immutable SSTable when **any** of several triggers fires:

- **Memory pressure** — total memtable heap/off-heap usage crosses `memtable_cleanup_threshold` (a fraction of the configured memtable space); the largest memtable is flushed to reclaim space.
- **Commit log pressure** — the commit log reaches its size cap (`commitlog_total_space`); Cassandra flushes the memtables holding the **oldest unflushed mutations** so those commit-log segments can be recycled.
- **Time** — `memtable_flush_period_in_ms` (if set) forces periodic flushes.
- **Manual / operational** — `nodetool flush`, snapshot, drain, or schema changes.

The commit-log relationship is the crucial part: each commit-log segment can only be **recycled once every memtable whose mutations it contains has been flushed**. So when a memtable flushes, Cassandra records that those mutations are now safely in an SSTable, and any commit-log segment with no remaining unflushed mutations is freed/recycled. This is why a stuck or slow flush (e.g., disk pressure) causes the **commit log to grow** — and why commit-log-pressure-triggered flushes exist as a safety valve. On restart, only commit-log segments newer than the last flush need replaying, which bounds recovery time.

### 🟠 — extended

#### Q84. [Theory] Explain the exact preconditions for a tombstone to be safely dropped during compaction, including the "overlapping SSTable" rule and `only_purge_repaired_tombstones`.

A tombstone (or expired-TTL cell) can be **purged** only when dropping it cannot resurrect shadowed data. All of these must hold:

1. **`gc_grace_seconds` elapsed** since the tombstone's local deletion time — the propagation window so all replicas can learn of the delete.
2. **No overlapping SSTable holds older shadowed data outside this compaction.** Compaction only sees its **input** SSTables. If a *different* SSTable (not in this compaction) contains an older live value for the same key whose timestamp is below the tombstone's, then purging the tombstone here would un-shadow that older value on a future read. Cassandra checks the min/max timestamps and key ranges of overlapping SSTables; if overlap with potentially-shadowed data exists, the tombstone is **kept**. This is why tombstones can linger far past `gc_grace_seconds` until the right SSTables compact together.
3. **(If enabled) `only_purge_repaired_tombstones = true`** — with incremental repair, this option refuses to purge a tombstone unless its SSTable is marked **repaired**, guaranteeing the delete has been reconciled cluster-wide before purge. It prevents zombie data even if `gc_grace_seconds` passes without a repair, at the cost of tombstones lingering until repair runs.

```
purge tombstone IFF: age > gc_grace_seconds
                 AND no overlapping non-input SSTable could hold shadowed older data
                 AND (not only_purge_repaired_tombstones OR sstable is repaired)
```

Operational levers when tombstones won't drop: `nodetool garbagecollect` (a focused GC compaction), **single-SSTable / tombstone compaction**, lowering `unchecked_tombstone_compaction` thresholds, or switching to TWCS so whole expired windows drop as files. The expert point: tombstone purge is a **safety-gated** operation — Cassandra would rather keep a useless tombstone than risk resurrecting deleted data.

#### Q85. [Theory] How does LeveledCompactionStrategy (LCS) bound the number of SSTables a read touches, and what is its "L0 backlog" failure mode?

LCS organizes SSTables into **levels** (L0, L1, L2, …) where each level is ~10× the size of the one below, and within **L1 and above, SSTables are non-overlapping** in key range (each level partitions the keyspace). Because a level has at most one SSTable covering any given key, a point read touches **at most one SSTable per level** — so reads are bounded to roughly the number of levels (typically ≤ ~7 even for large datasets). That predictable, low read amplification is why LCS suits **read-heavy / update-heavy** workloads.

```
L0: freshly flushed, may overlap (catch-all)
L1: ~10× L0 size, non-overlapping, one SSTable per key range
L2: ~10× L1, non-overlapping
...   a read checks L0 (all of it) + at most one SSTable in each higher level
```

The **L0 backlog failure mode**: newly flushed SSTables land in **L0**, where they *can* overlap. LCS continuously compacts L0 → L1 to maintain the invariant. If the **write rate exceeds compaction throughput**, L0 accumulates many overlapping SSTables faster than LCS can drain them. Now a read must check **all** of L0 (overlapping) plus one per higher level — read amplification spikes, and the cluster falls behind, sometimes spiraling. Symptoms: growing pending compactions, rising "SSTables per read," latency degradation. This is precisely why LCS is **wrong for write-heavy** workloads (its leveling cost can't keep up) and why you watch `nodetool compactionstats` for an L0 pile-up. STCS or TWCS tolerate write bursts far better.

#### Q86. [Theory] What is the difference between full repair, incremental repair, and subrange repair — and what consistency hazard did early incremental repair have?

These are three orthogonal dimensions of how `nodetool repair` runs:

- **Full repair** — validates and reconciles **all** data for the targeted ranges every time; correct but expensive (re-checks already-consistent data repeatedly).
- **Incremental repair** — after reconciling, marks SSTables as **`repaired`** and **separates the repaired set from the unrepaired set**; subsequent repairs only validate the **unrepaired** data, dramatically reducing work over time. This is the modern default for routine cadence.
- **Subrange repair** — repairs a **specific token sub-range** at a time rather than a node's whole range, enabling fine-grained, resumable, backpressure-friendly scheduling (what Cassandra Reaper orchestrates).
- **`-pr` (primary range)** — each node repairs only the ranges it's the primary owner of, so running `-pr` on every node repairs the cluster **once** instead of RF-times.

The **early-incremental-repair hazard**: pre-4.0 incremental repair had a flaw where the **anticompaction** that splits repaired/unrepaired SSTables, combined with how validation interacted with concurrent compaction, could mark data repaired inconsistently or even **lead to resurrection / over-streaming** in edge cases — so many operators stuck with full subrange repair. Cassandra **4.0 substantially redesigned incremental repair** (transactional anticompaction, better tracking) to make it safe and the recommended default. The interview-grade nuance: "incremental repair is the right default *now* (4.0+), but historically it was risky, which is why a lot of older guidance and Reaper configs defaulted to full subrange repair."

#### Q87. [Theory] How does Cassandra's gossip state actually propagate — explain generations, versions, heartbeats, and how a node distinguishes restart from a stale view.

Gossip carries each node's state as an **`EndpointState`** containing a **`HeartBeatState`** (generation + version) plus **`ApplicationState`** entries (status, tokens, load, schema version, DC/rack, etc.), each tagged with a version number.

- **Generation** — a number (typically a startup timestamp) that **increments each time a node restarts**. A higher generation means "this is a newer incarnation of the node," so peers discard all older-generation state for it. This is exactly how a node distinguishes a **restart** (new generation, adopt fresh state) from a **stale gossip view** (same generation, just compare versions).
- **Version** — within a generation, a monotonically increasing counter bumped every time any application state changes. Peers keep the **highest (generation, version)** they've seen and ignore anything older.
- **Heartbeat** — every second a node bumps its own version (a heartbeat tick) and gossips with up to three peers (a live node, possibly an unreachable one, and possibly a seed), exchanging digests of "what versions I have for whom"; the peer replies with what it's missing and requests what *it* lacks. Through this three-way `SYN`/`ACK`/`ACK2` exchange, the newest state spreads **epidemically** and the cluster converges within a few rounds.

So the precise model: **(generation, version) is a logical clock per node**; generation handles restarts, version handles incremental updates, and the failure detector watches heartbeat **inter-arrival times** (not just absence) to compute Φ. This is what lets a masterless cluster maintain one self-healing, restart-aware view of membership with no coordinator.

#### Q88. [Coding] Demonstrate a CRDT-style / append-only model that sidesteps last-write-wins data loss, and explain why it's safer than an in-place update.

The hazard: two concurrent in-place updates to the same cell resolve by LWW — **one is silently dropped**. The fix is to model so that concurrent writes go to **different cells/rows** and the "current value" is *derived* at read time, never overwritten in place.

```cql
-- INSTEAD OF a single mutable "balance" cell (LWW-lossy):
--   UPDATE accounts SET balance = ? WHERE id = ?   -- concurrent writers clobber each other

-- Append immutable, uniquely-keyed events; the balance is the fold of events.
CREATE TABLE account_ledger (
    account_id  uuid,
    event_id    timeuuid,        -- unique per event -> no two writes share a cell
    delta       decimal,         -- +deposit / -withdrawal
    reason      text,
    PRIMARY KEY (account_id, event_id)
) WITH CLUSTERING ORDER BY (event_id DESC);

-- Two concurrent deposits become two distinct rows (different event_id) -> NEITHER is lost.
INSERT INTO account_ledger (account_id, event_id, delta, reason)
VALUES (?, now(), 50.00, 'deposit');

-- "Current balance" is computed by summing deltas (app-side or via periodic snapshot row):
SELECT delta FROM account_ledger WHERE account_id = ?;   -- fold client-side
```

Why it's safer: because each write has a **unique clustering key (`timeuuid`)**, concurrent writes never target the same cell, so **LWW never has to discard anything** — every event survives. This is the Cassandra-idiomatic analog of a **G-Counter / PN-Counter CRDT** or event sourcing: convergence by *union of immutable facts* rather than *overwrite of a shared cell*. The cost is read-time aggregation (mitigated with periodic snapshot/rollup rows). Use this for balances, inventory, vote tallies, or anything where a silently-lost concurrent update would be a correctness bug — reserving LWT/Accord only for cases needing a synchronous invariant check.

#### Q89. [Theory] What are the failure modes of materialized views at the storage/repair level, and why are they flagged experimental?

Materialized views (MVs) are server-maintained denormalized tables, but their maintenance has subtle storage-level hazards:

- **Read-before-write on the base update.** To update the view correctly when a base row changes, Cassandra must **read the prior base value** (to know which old view row to tombstone) before writing the new view row. That adds a read to every base write and means MV updates aren't pure appends.
- **Base/view divergence under failure.** The base and view rows live on **different nodes** (different partition keys → different tokens). If a node fails between the base mutation and the corresponding view mutation, or hints/repair reconcile them at different times, the base and view can **drift out of sync** — and there's no simple cross-checking repair that guarantees they reconverge.
- **Repair doesn't fully cover the base↔view consistency.** Repairing the base table and the view table independently doesn't guarantee the *derivation relationship* holds; subtle bugs have allowed orphaned or missing view rows.
- **Inability to safely repair/rebuild in all cases**, and historically several correctness bugs (lost updates, incorrect tombstoning) — which is why MVs carry an **experimental** flag (gated behind `enable_materialized_views`) in many versions.

The expert takeaway: MVs trade a hard distributed-systems problem (keeping a derived dataset consistent with its source across failures) for convenience, and that problem isn't fully solved at the storage layer. Most seasoned teams prefer **application-maintained denormalized tables** (explicit dual writes with known semantics) over MVs, accepting the boilerplate in exchange for predictable, debuggable consistency.

#### Q90. [Theory] How does compression work in Cassandra (chunk length, compressor choice), and what's the read-time cost/benefit trade-off?

Cassandra compresses **`Data.db` in fixed-size chunks** (default `chunk_length_in_kb = 16`, configurable), with offsets stored in `CompressionInfo.db` so a read can decompress **only the chunk(s)** covering the needed bytes rather than the whole file.

```cql
ALTER TABLE orders_by_customer WITH compression = {
  'class': 'LZ4Compressor',     -- fast default; also SnappyCompressor, DeflateCompressor, ZstdCompressor
  'chunk_length_in_kb': 16
};
```

The trade-offs:
- **Compressor choice** — **LZ4** (default) is very fast with modest ratios (best for latency-sensitive workloads); **Zstd** offers better ratios at tunable CPU cost (good when disk/space-bound); **Deflate** maximizes ratio but is CPU-heavy (rarely worth it). Choose by whether you're CPU-bound or disk/IO/space-bound.
- **Chunk length** — **smaller chunks** (e.g., 4 KB) mean a point read decompresses less wasted data (lower read amplification) but worse compression ratio and more chunk metadata; **larger chunks** (64 KB) compress better but force decompressing more bytes per small read. Tune small for random point reads, larger for scan-heavy/analytical tables.

Benefits: less disk used, **more data fits in the OS page cache** (often a *net read speedup* despite decompression CPU, because you avoid disk seeks), and less I/O. Cost: CPU to decompress on every read of a cold chunk, and a small read still pays to decompress a whole chunk. The principle: compression usually helps reads on real workloads (cache density wins), but **match compressor and chunk size to your access pattern** — random point lookups favor smaller chunks + LZ4; archival/scan tables favor larger chunks + Zstd.

### 🔴 — extended

#### Q91. [Theory] Reason precisely about whether `QUORUM`/`QUORUM` gives linearizability. Where exactly does it fall short, and what does Accord add?

`R + W > N` with `QUORUM`/`QUORUM` guarantees **read overlap**: any read quorum intersects any write quorum on at least one replica, so a read sees the latest *acknowledged* write (by LWW timestamp). This delivers **monotonic, read-your-writes strong consistency for single-key reads** — but it is **not full linearizability**, for two reasons:

1. **Concurrent writes both "succeed" and one is silently dropped.** Two clients issue `QUORUM` writes to the same cell concurrently; both meet their write quorum and both return success, but LWW keeps only the higher timestamp — so there's no single agreed total order with a unique winner that both clients could have predicted. A linearizable register would order them and let each observe the effect; LWW just discards one with no signal.
2. **Read-modify-write races.** "Read balance, then write balance−10" at QUORUM/QUORUM is **not** atomic: two clients can both read 100 and both write 90, losing a decrement. The overlap guarantee says nothing about the *gap* between the read and the write.
3. **Failed/partial writes leave an undefined state.** A `QUORUM` write that reaches one replica then the coordinator dies may or may not be "seen" later depending on repair/read-repair timing — an in-doubt outcome a linearizable system wouldn't have.

What **Accord** adds: a **leaderless, strict-serializable consensus** that orders transactions with **globally meaningful (HLC-style) timestamps**, giving true linearizable/serializable semantics for **multi-key, multi-partition** operations in one round-trip in the common case — closing exactly the read-modify-write and concurrent-write-ordering gaps that QUORUM tuning and even single-partition Paxos LWT cannot. The precise expert statement: *"QUORUM/QUORUM = strong (monotonic) read consistency for LWW registers, not linearizability; LWT = linearizable single-partition CAS; Accord = strict-serializable multi-partition transactions."*

#### Q92. [Theory] Walk through the internal phases of Paxos LWT including the `system.paxos` table and how contention causes livelock.

A Cassandra LWT runs **Paxos per partition key** and persists its state in the **`system.paxos`** table (the accepted ballots/values), so progress survives coordinator failure. The phases:

```
1. PREPARE/PROMISE  — proposer picks a ballot (a TimeUUID, time-ordered). It asks a
   quorum of replicas to PROMISE not to accept any lower ballot. Replicas record the
   ballot in system.paxos and reply with any value they've already accepted.
2. READ (condition)  — the proposer reads the current data to evaluate the IF clause
   (e.g., IF NOT EXISTS / IF col = ?). This is why an LWT also does a read internally.
3. PROPOSE/ACCEPT    — if the IF holds, propose the new value at that ballot; a quorum
   of replicas ACCEPT it (recording it in system.paxos) if no higher ballot intervened.
4. COMMIT            — the accepted value is committed to the base table and replicas
   ack; the paxos state is later cleared.
```

That's up to **four round-trips, each needing a quorum** — hence LWTs are ~4× a normal write and need `SERIAL`/`LOCAL_SERIAL` consistency for the Paxos rounds plus the regular CL for the commit.

**Livelock under contention:** if many proposers target the **same partition** concurrently, each `PREPARE` with a higher ballot **invalidates** others' promises, so proposers keep getting "a higher ballot was promised" and **restart**, repeatedly preempting each other without anyone committing — classic Paxos **dueling proposers / livelock**. Cassandra mitigates with randomized backoff and by completing in-progress Paxos rounds it discovers, but **hot-partition LWT contention** still degrades to high latency and retries. The operational rule: LWTs are for **low-contention invariants** (claim-once, unique key); piling concurrent LWTs onto one partition is a self-inflicted livelock. The `system.paxos` reads/writes and later cleanup are also extra I/O the workload pays for.

#### Q93. [Theory] Design a deletion/data-expiry strategy for a high-churn table that avoids tombstone hell entirely. Compare TTL+TWCS, partition-drop, and time-bucketed tables.

For high-churn data (events, sessions, queues, feeds), the goal is to **remove data without ever scanning tombstones on the read path.** Three strategies, in rough order of preference:

1. **Time-bucketed partitions you can drop wholesale (best).** Put a time bucket in the partition key (`((entity, day), ts)`). Old data is removed by **deleting/expiring whole partitions or dropping whole SSTables** — the read path for *current* data never traverses old tombstones because old data lives in different partitions/SSTables entirely. With per-day buckets, "delete everything older than 30 days" touches partitions reads no longer query.

2. **TTL + TimeWindowCompactionStrategy (excellent for time-series).** Set `default_time_to_live` and TWCS so each time window is its own SSTable set. When **every cell in a window expires**, Cassandra **drops the entire SSTable file** without per-cell tombstone compaction — near-zero tombstone read cost. The rule: **never update or delete individual rows** in a TWCS table (that breaks the "all data in a window expires together" invariant and reintroduces tombstones).

3. **Separate table-per-period + `DROP`/`TRUNCATE` (sledgehammer).** Write to `events_2026_06`, `events_2026_07`, …; to expire a whole month, **`DROP TABLE`** it — metadata-only, instant, zero tombstones, zero compaction. Reads target the relevant period table(s). Operationally heavier (table lifecycle management, routing) but the cheapest possible expiry.

```cql
-- Strategy 2: TTL + TWCS, no per-row deletes ever
CREATE TABLE events_by_user (
  user_id uuid, bucket text, ts timeuuid, payload text,
  PRIMARY KEY ((user_id, bucket), ts)
) WITH CLUSTERING ORDER BY (ts DESC)
  AND default_time_to_live = 2592000
  AND compaction = {'class':'TimeWindowCompactionStrategy',
                    'compaction_window_unit':'DAYS','compaction_window_size':1};
```

**What to avoid:** the **queue anti-pattern** (insert then `DELETE` rows in the same long-lived partition) and **range deletes over hot partitions** — both create tombstone storms that the read path must scan. The unifying principle: **make deletion a file-level or partition-level operation (drop), never a per-row marker the read path has to wade through.** If you find yourself issuing many `DELETE`s, the model is wrong — restructure so expiry happens by dropping whole windows/partitions/tables.

#### Q94. [Theory] How do hinted handoff, the `system.hints` storage, `max_hint_window_in_ms`, and hint replay interact — and where can hints silently fail to protect a write?

Hinted handoff stores undeliverable mutations so they can be replayed when a down replica returns. The internals and their limits:

- **Storage** — hints are written to **local files (the hints directory; historically a `system.hints` table)** on the coordinator, keyed by the target node. Each hint is the full mutation plus target endpoint.
- **`max_hint_window_in_ms`** (default ~3 hours) — the coordinator **only stores hints for a node down less than this window.** If a node is down **longer**, the coordinator **stops storing new hints** for it (and existing hints may expire) — beyond this window, reconciliation depends entirely on **repair**. This is the single biggest "hints silently don't help" case: a node down for hours accumulates a gap that only `nodetool repair` will close.
- **Replay** — when gossip reports the node UP, the coordinator(s) **stream their stored hints** to it, throttled (`hinted_handoff_throttle`) so replay doesn't overwhelm a just-recovered node. After successful delivery the hints are deleted.

Where hints **silently fail to protect a write**:
1. **Beyond the hint window** — long outages exceed `max_hint_window_in_ms`; those writes are not hinted and need repair.
2. **Coordinator crash before replay** — if the coordinator holding the hints dies (and the data wasn't durably on enough replicas), the hint is lost; at write CL `ANY`, where a hint *was* the only copy, this is **data loss**.
3. **Hint expiry / disk limits** — hints can be dropped under storage pressure or TTL, again deferring to repair.
4. **The write never met CL in the first place** — hints don't retroactively satisfy a failed `QUORUM` write; the client already saw failure.

The precise model: **hints are a best-effort, bounded-window bridge for brief outages, not a durability or consistency guarantee.** They reduce the inconsistency window for short blips but are explicitly **not a substitute for `nodetool repair`** — which is the only mechanism guaranteed to reconcile cold data on a long-down node and to make tombstone purge safe. Treating hints as if they guarantee convergence is a classic operational misconception that leads to skipped repairs and eventual zombie/stale data.

#### Q95. [Theory] Explain how Cassandra 5.0's Storage-Attached Indexing (SAI) is implemented differently from classic 2i and SASI, and why it scales better.

Classic secondary indexes (2i) and SAI differ fundamentally in **how and where the index lives relative to the storage engine**:

- **Classic 2i** maintains a **separate hidden index table** (a local index partitioned by the indexed value on each node). Each indexed write does an extra write to that hidden table; the index has its own SSTables, its own compaction, its own tombstones, and its own memory — **multiplying overhead per indexed column** and making high-cardinality or frequently-updated columns very expensive. A non-partition-key query still scatter-gathers across nodes.
- **SASI** (legacy, experimental) built richer on-disk term indexes (supporting prefix/range/`LIKE`) but had its own index files per column, heavy memory use, and maturity issues.
- **SAI (Cassandra 5.0)** is **storage-attached**: the index structures are **built as part of the SSTable lifecycle itself**, sharing the SSTables' write/flush/compaction path rather than maintaining a separate index table. Multiple SAI indexes on the same table **share infrastructure and on-disk format**, so adding more indexed columns adds **far less per-column overhead** (much smaller disk and memory footprint than N separate 2i indexes). SAI supports **numeric ranges, text/prefix matching, and is the foundation for vector/ANN search**, and it can efficiently **AND multiple SAI predicates** server-side.

```cql
CREATE INDEX ON users (email) USING 'sai';
CREATE INDEX ON users (signup_date) USING 'sai';   -- second index shares SAI infrastructure
SELECT * FROM users WHERE email = ? AND signup_date > ?;   -- multiple SAI predicates AND-ed
```

Why it scales better: because the index is **co-located with and compacted alongside the data**, there's **no separate index table to keep consistent**, **dramatically less write amplification and memory per indexed column**, and a unified format that supports range/prefix/vector queries. SAI still doesn't make a non-partition-key query avoid touching multiple nodes (it's still a distributed scatter for global queries), so the **denormalized query-table pattern remains preferred for the highest-traffic access paths** — but SAI makes **moderate-traffic, ad-hoc-ish secondary access** genuinely viable in production, which classic 2i never was. The expert framing: SAI turns secondary indexing from "last-resort anti-pattern" into a "reasonable tool for secondary access patterns," while query tables stay the choice for the hottest paths.

#### Q96. [Theory] Discuss the durability spectrum of the commit log: `periodic` vs `batch` vs `group` fsync, and the exact window of data loss for each.

The commit log provides durability, but **when it fsyncs to disk** determines the precise window of acknowledged-but-unflushed data you can lose on a hard crash/power failure:

- **`periodic` (default)** — the commit log is fsynced to disk **every `commitlog_sync_period_in_ms` (default 10 s)**, but **writes are acknowledged to the client immediately** (after hitting the OS buffer, before fsync). **Loss window: up to one sync period (~10 s) of acknowledged writes** if the machine loses power before the next fsync. Highest throughput; weakest single-node durability. (Note: at RF>1 with QUORUM, *other replicas* still have the write, so cluster-level loss is far less likely than single-node loss — this window is the **per-node** exposure.)
- **`batch`** — the write is **not acknowledged until the commit log fsync completes.** Cassandra batches concurrent writes and fsyncs them together (within `commitlog_sync_batch_window_in_ms`), then acks. **Loss window: effectively zero** acknowledged writes (an ack means it's on disk) — at the cost of every write waiting on an fsync, which hurts latency/throughput, especially on slow disks.
- **`group`** — a middle ground (4.x): like batch, the ack waits for fsync, but fsyncs are **grouped on a time interval (`commitlog_sync_group_window_in_ms`)** to amortize fsync cost across many writes. **Loss window: zero acknowledged writes**, with **better throughput than `batch`** by trading a small, bounded extra latency (wait for the next group fsync) for far fewer fsync calls.

```
periodic:  ack -> ... (≤10s) ... -> fsync     | loss window ≈ sync period, fast
batch:     write -> fsync -> ack              | loss window ≈ 0, slowest
group:     write -> wait for group fsync -> ack | loss window ≈ 0, throughput-friendly
```

The decision: **`periodic`** for max throughput when RF replication + repair are your durability backstop and a ~10 s single-node window is acceptable; **`batch`/`group`** when you need per-node "acked means durable" semantics (financial/regulatory), with **`group`** generally preferred over `batch` because it gives the same zero-loss guarantee at much better throughput. The subtlety experts state: in a replicated cluster, **replication factor + consistency level are the *primary* durability mechanism**; commit-log sync mode governs the **single-node** loss window, and the two interact (e.g., `periodic` + QUORUM across independent-failure racks is durable in practice unless multiple replicas lose power simultaneously).

#### Q97. [Practical] You see `nodetool tablestats` reporting a high "tombstones scanned per read" and rising p99, but `gc_grace_seconds` has passed and you've run repair. Why might tombstones still not be purged, and how do you force them out?

This is the **"overlapping SSTable"** trap from Q84 made operational. Even with `gc_grace_seconds` elapsed and repair done, a tombstone is **kept** if a *different* SSTable not currently being compacted with it could hold older shadowed data for the same key — purging it would risk resurrection. So the tombstone lingers until the right SSTables happen to compact **together**, which may not occur for a long time under STCS (old and new data scattered across many size-tiers).

Diagnosis and remedies:

```
# 1. Confirm: which SSTables, how many droppable tombstones?
nodetool tablestats <ks>.<table>          # tombstones per read, droppable ratio
sstablemetadata <Data.db>                 # estimated droppable tombstone ratio per SSTable

# 2. Force a focused garbage-collecting compaction (purges droppable tombstones,
#    accounting for overlaps across SSTables):
nodetool garbagecollect <ks> <table>

# 3. Or force a single-SSTable / major compaction to bring overlapping data together:
nodetool compact <ks> <table>             # major compaction (use cautiously: big SSTable)

# 4. Tune so it happens automatically:
ALTER TABLE t WITH compaction = { ... 'unchecked_tombstone_compaction':'true',
                                  'tombstone_threshold':'0.1',
                                  'tombstone_compaction_interval':'86400' };
```

Other levers: lower `tombstone_threshold` (ratio that triggers single-SSTable tombstone compaction) and `tombstone_compaction_interval`; if using incremental repair, ensure `only_purge_repaired_tombstones` isn't blocking purge on unrepaired SSTables (run repair so the SSTables are marked repaired). The durable fix, not the firefight: **remodel away from per-row deletes** — move to **TTL + TWCS** (whole expired SSTables drop, no overlap problem) or **time-bucketed partitions you can drop**, so the read path never scans tombstones in the first place. The senior point: `garbagecollect`/major compaction is the *immediate* remedy; the *root cause* is almost always a delete-heavy model on a non-TWCS table, and the permanent fix is the model, not repeated forced compactions.

#### Q98. [Theory] How does token allocation work with vnodes in modern Cassandra (the allocation algorithm), and why did the recommended `num_tokens` drop from 256 to 16?

With vnodes, each node owns `num_tokens` token ranges scattered around the ring. **How those tokens are chosen** matters for balance:

- **Old approach (random allocation, `num_tokens=256`)** — each node picked its tokens **randomly**. With many tokens (256) per node, the law of large numbers made the *aggregate* ownership roughly even, hiding the randomness's imbalance. Fewer tokens with random allocation would produce **lopsided ownership** (some nodes owning much more of the ring than others).
- **New approach (allocation algorithm, `allocate_tokens_for_local_replication_factor` / `_keyspace`)** — modern Cassandra uses a **token allocation algorithm** that, when adding a node, **chooses tokens to deliberately minimize ownership variance** for a given RF (it picks ranges that even out load rather than rolling dice). Because allocation is now *smart*, you no longer need 256 tokens to average away randomness.

Why **256 → 16** (and sometimes lower):
- **Repair and availability cost of many vnodes** — more vnodes means more **token ranges to validate during repair** (more Merkle subtrees, more overhead) and, critically, a **higher probability that any given quorum's replicas span more distinct physical nodes**. With many tiny ranges, the set of nodes involved in replicating data grows, which **increases the chance that some token range loses a quorum when multiple nodes fail simultaneously** (worse availability under correlated failures).
- **Streaming/operational overhead** — more ranges to track during bootstrap/decommission.
- The **allocation algorithm** makes a low token count (16) achieve good balance *without* the downsides of many vnodes.

```
yaml (modern):
  num_tokens: 16
  allocate_tokens_for_local_replication_factor: 3   # algorithm evens ownership for RF=3
```

The expert framing: the 256 default existed to **statistically mask random token placement**; once Cassandra gained an **allocation algorithm** that places tokens to minimize imbalance, the optimal shifted to **few tokens (16)** — better repair efficiency, better failure-correlation properties, and smaller operational overhead, with balance preserved by the algorithm rather than by brute-force vnode count. Picking `num_tokens` is thus a **balance-vs-availability/repair trade-off**, and modern guidance (16, with the allocation algorithm) reflects the better answer.

## 🧩 Extended Questions — Set 2: Practical scenarios, troubleshooting & coding

### 🟢 — extended

#### Q99. [Practical] `nodetool status` shows a node as `DN` (Down/Normal). Walk through how you triage it.

`DN` means the node is recognized by the cluster (it has a token range and data) but is currently seen as **down** via gossip. Triage in order:

1. **Confirm it's really down vs. a flapping/gossip view.** Run `nodetool status` from *another* node — a single coordinator's view can be stale. If only one node sees it as down, suspect a network/gossip partition rather than a dead process.
2. **Check the process and host.** On the affected node: is the Cassandra process running (`ps`/`systemctl status cassandra`)? Is the host reachable (ping, SSH)? Out-of-memory kills, a full disk, or a crashed JVM are the usual culprits.
3. **Read the logs.** `system.log` and `debug.log` — look for `OutOfMemoryError`, `Killing JVM`, commit-log/disk-full errors, or long GC pauses (`GCInspector` warnings). A node that GC-pauses for tens of seconds gets marked down by peers even though the process is alive.
4. **Check disk.** A full data or commit-log volume will take a node down or read-only; `df -h` first.
5. **Decide recovery.** If it was down **briefly** (< `max_hint_window_in_ms`, default 3 h), just restart it and let hinted handoff + read repair heal it, then run a repair. If it was down **longer than the hint window**, hints were dropped — restart and run a **full repair** of its ranges so it catches missed writes.

The key mental model: `DN` is a *gossip-level* status, so always cross-check from multiple nodes, and the down-duration relative to the hint window decides whether a plain restart suffices or a repair is mandatory.

#### Q100. [Practical] A `cqlsh` query times out with `ReadTimeout` but the same query worked yesterday. What's your first-pass checklist?

A read that *used to* work and now times out almost always means the partition got more expensive to read, or a replica is unhealthy. First-pass checklist:

1. **Is it one partition or all queries?** Try a different partition key. If only one key is slow, you have a **growing/hot partition** or a **tombstone-laden** partition; if all reads are slow, suspect a node, GC, or compaction backlog.
2. **Tombstones.** Run the query with `TRACING ON;` — tracing shows "tombstone cells read." If you see thousands, that partition has accumulated deletes/expired TTL cells. This is the single most common cause of "it worked yesterday."
3. **Partition size.** `nodetool tablehistograms <ks> <table>` shows partition-size and cell-count percentiles. A partition that crossed ~100 MB / 100k cells degrades sharply.
4. **Node health.** `nodetool status` for any `DN`/joining nodes; `nodetool compactionstats` for a compaction backlog; `nodetool tpstats` for dropped reads / blocked queues.
5. **Consistency level.** A CL of `QUORUM`/`ALL` will time out if a replica is down; `LOCAL_ONE` would still succeed. Confirm the CL hasn't changed.

The fastest discriminator is `TRACING ON` on the slow query — it directly shows tombstones scanned, SSTables touched, and which replica was slow, turning guesswork into evidence.

#### Q101. [Coding] Write CQL to enable tracing and interpret what to look for.

```cql
-- Turn on request tracing in cqlsh
TRACING ON;

SELECT * FROM store.orders_by_customer
WHERE customer_id = 11111111-1111-1111-1111-111111111111
  AND order_ts >= '2026-06-01';

-- (run the query; cqlsh prints a trace table)
TRACING OFF;

-- Inspect a past trace by session id from the tracing keyspace:
SELECT activity, source, source_elapsed
FROM system_traces.events
WHERE session_id = <session_uuid>;
```

What to look for in the trace output:
- **`Read N live rows and M tombstone cells`** — a high M is your smoking gun for tombstone-driven latency.
- **`Merged data from memtable and N sstables`** — many SSTables for one partition means read amplification; check compaction strategy/backlog.
- **`source` columns** — which replicas were contacted and how long each took (`source_elapsed`). One slow replica points to a node problem, not a query problem.
- **Coordinator vs. replica time** — large coordinator overhead can indicate cross-DC hops or speculative retries firing.

Tracing writes to the `system_traces` keyspace and adds overhead, so enable it for diagnosis on a few queries, not as an always-on setting.

#### Q102. [Practical] You ran `DELETE` but the row still shows up in `SELECT`. List the possible causes.

A "delete that didn't take" has a handful of usual explanations:

1. **Consistency mismatch.** You deleted at CL `ONE` and a replica missed it, then read at `ONE` from a replica that still has the live value. Read at the same or higher CL such that `R + W > N` to see the delete reliably.
2. **Timestamp / clock issue.** The delete's write timestamp is **older** than the value's timestamp (e.g., a client supplied `USING TIMESTAMP`, or clock skew). Last-write-wins means an older tombstone loses to a newer insert. Check `WRITETIME()` of the column vs. the delete.
3. **Zombie resurrection.** The tombstone was purged after `gc_grace_seconds` before a long-down replica learned of the delete; that replica's old value then resurfaced via read repair/repair. Cause: skipped repairs.
4. **Partial primary key.** Your `DELETE` didn't specify the full clustering key, so it deleted different rows than the one you're reading, or created a range tombstone that doesn't cover the live row.
5. **Reinsert after delete.** Some other writer reinserted the row with a newer timestamp.

The disciplined check is `SELECT WRITETIME(col), col ...` to compare timestamps — it instantly reveals whether the delete is older (LWW loss), missing (CL/repair), or genuinely shadowed.

#### Q103. [Coding] Write CQL to inspect a column's write timestamp and TTL to debug stale data.

```cql
-- See when a column was last written and its remaining TTL (seconds)
SELECT customer_id,
       status,
       WRITETIME(status) AS status_ts,
       TTL(status)       AS status_ttl
FROM store.orders_by_customer
WHERE customer_id = 11111111-1111-1111-1111-111111111111
  AND order_ts = '2026-06-30 10:15:00';
```

`WRITETIME()` returns the microsecond-resolution timestamp Cassandra uses for last-write-wins conflict resolution; comparing it across replicas or against a competing write tells you which mutation "wins." `TTL()` returns the remaining seconds before the cell auto-expires (`null` if no TTL). If a value you expected to be gone has a positive TTL, it simply hasn't expired yet; if a value you expected to persist shows an unexpectedly low TTL, a `default_time_to_live` on the table is silently expiring it. Note: you cannot call `WRITETIME()`/`TTL()` on primary-key columns or collections directly.

#### Q104. [Practical] How do you check the size and row count of a specific partition that you suspect is "wide"?

There's no single CQL command for one partition's bytes, so combine tools:

1. **`nodetool tablehistograms <keyspace> <table>`** — gives partition-size and cell-count distributions (50th/95th/99th/max percentiles) for the whole table. A max partition far above the rest flags a wide-partition outlier.
2. **`nodetool tablestats <keyspace>.<table>`** — shows "Compacted partition maximum bytes" and "Average live cells per slice," good for spotting tables with a fat tail.
3. **For a *specific* key**, count rows directly (small/medium partitions only):
   ```cql
   SELECT COUNT(*) FROM readings_by_sensor
   WHERE sensor_id = 'sensor#42' AND bucket = '2026-06-30';
   ```
   (Bounded to one partition so it won't scatter-gather, but it still reads the whole partition — use cautiously.)
4. **`nodetool toppartitions`** (where available) or the `sstablepartitions` / `sstablemetadata` offline tools to find the largest partitions in an SSTable.

The guardrail is to keep partitions under roughly **100 MB and ~100k rows**; `tablehistograms` is the fastest way to confirm whether a table is approaching that and which percentile is the problem.

#### Q105. [Practical] After adding a node, `nodetool status` shows it as `UJ` (Up/Joining) for a long time. What is happening and what do you check?

`UJ` = **Up, Joining**: the node is alive and **streaming its share of data** from existing replicas before it starts serving reads. A long join is normal for large datasets but worth verifying:

1. **Streaming progress.** `nodetool netstats` shows active streams and percent complete per peer. If bytes are still moving, it's just a big transfer — be patient.
2. **Stuck streams.** If `netstats` shows no progress, a stream may have stalled (network blip, a source node restarted). Check `system.log` on both ends for stream errors; you may need to stop the join and re-bootstrap.
3. **Throughput throttle.** `stream_throughput_outbound_megabits_per_sec` caps streaming; if it's set conservatively, joins are slow by design. You can raise it temporarily via `nodetool setstreamthroughput`.
4. **Disk/compaction on the new node.** As it receives SSTables it also compacts; a slow disk extends the join.

A node in `UJ` is **not yet counted as a replica for reads** but *is* receiving writes, so the cluster stays consistent. Don't bootstrap two nodes simultaneously in the same rack, and after it flips to `UN` (Up/Normal), run `nodetool cleanup` on the *other* nodes to drop the data they no longer own.

#### Q106. [Practical] A developer says "I'll just add a secondary index to query by email." How do you respond?

I'd explain why classic 2i is usually the wrong reflex and offer better options:

- **Classic 2i is a local index** — each node indexes only its own data — so a query by `email` (not the partition key) becomes a **scatter-gather across every node**, with latency that grows with cluster size. It also struggles on **high-cardinality** columns like email (each value matches very few rows, so you pay the fan-out for almost nothing) and on **very low-cardinality** columns (huge index partitions).
- **The Cassandra-idiomatic answer is a query table:** make a table keyed by `email` that mirrors the data, written at the same time as the base table. One lookup, no fan-out.
   ```cql
   CREATE TABLE users_by_email (
       email   text PRIMARY KEY,
       user_id uuid,
       name    text
   );
   ```
- **If you genuinely need ad-hoc secondary access at scale**, use **Cassandra 5.0 SAI (Storage-Attached Indexing)**, which is far more efficient than classic 2i/SASI and supports numeric ranges and (with vectors) similarity — but it's still not free, so reserve it for cases a query table can't cover.

The summary: uniqueness/equality lookups → a denormalized query table; richer secondary predicates → SAI; classic 2i → only for low-traffic, partition-restricted queries.

#### Q107. [Coding] Write CQL to create a query table for a uniqueness lookup and enforce uniqueness on insert.

```cql
-- Lookup table keyed by the thing you want unique
CREATE TABLE users_by_email (
    email      text PRIMARY KEY,
    user_id    uuid,
    created_at timestamp
);

-- Enforce "email must be unique" with an LWT (compare-and-set)
INSERT INTO users_by_email (email, user_id, created_at)
VALUES ('ana@x.com', 11111111-1111-1111-1111-111111111111, toTimestamp(now()))
IF NOT EXISTS;
```

The `IF NOT EXISTS` makes the insert a **Lightweight Transaction**: Cassandra runs Paxos so that only one of two concurrent inserts for the same email succeeds (the other returns `[applied]=False` with the existing row). Without the `IF`, the second insert would silently upsert and overwrite — no uniqueness. Caveat: LWTs are ~4× the cost of a normal write and contend on the same partition, so use them only at the genuine uniqueness boundary (account creation), and pair this lookup table with your main `users` table (write both, ideally guarding the lookup with the LWT first).

#### Q108. [Practical] What does `nodetool tpstats` tell you, and which counters indicate trouble?

`nodetool tpstats` reports the state of Cassandra's internal **thread pools (stages)** — how many tasks are active, pending, completed, blocked, and dropped. The danger signals:

- **Pending tasks climbing** on `MutationStage` (writes) or `ReadStage` (reads) — the node can't keep up; requests are queuing. Sustained nonzero pending = overload.
- **Dropped messages** (bottom section): `READ`, `MUTATION`, `READ_REPAIR`, `HINT` drops mean the node shed work because queues exceeded timeouts. Dropped `MUTATION` is especially bad — those writes were lost on that replica and now rely on hints/repair to heal.
- **`CompactionExecutor` pending** — compaction backlog (read amplification rising).
- **Blocked / "All time blocked"** on `MemtableFlushWriter` or `MemtablePostFlush` — flush can't keep up with ingest, often a slow disk.

The triage logic: pending on `ReadStage`/`MutationStage` points to capacity/GC; drops point to which path is saturated; blocked flush/compaction points to disk I/O. Pair it with `nodetool compactionstats` and GC logs to localize whether the bottleneck is CPU, disk, or memory.

#### Q109. [Practical] Your application throws `NoHostAvailableException` intermittently. What are the likely causes?

`NoHostAvailableException` (DataStax driver) means the driver tried all candidate coordinators and none could serve the request. Intermittent (not constant) points to transient or partial problems:

1. **Replicas down for the requested CL.** At `QUORUM`/`LOCAL_QUORUM`, if enough replicas for a given partition are down, those requests fail while others succeed — hence *intermittent*. Check `nodetool status` for `DN` nodes.
2. **Overloaded nodes shedding requests.** Coordinators returning timeouts/overloaded errors get marked down by the driver temporarily; under load this flaps. Check `tpstats` drops and GC pauses.
3. **Driver-side misconfiguration.** Too-aggressive timeouts, a too-small connection pool, or a load-balancing policy pinned to a DC that's unhealthy.
4. **Network / partial partition.** The app host can reach some nodes but not others.
5. **Token-aware routing to a struggling replica** for specific keys, so only certain partitions fail.

Diagnose by inspecting the **inner exceptions** the driver wraps (it lists the per-host errors — timeout vs. connection refused vs. unavailable), which immediately distinguishes a CL/availability problem from a pure connectivity problem.

### 🟡 — extended

#### Q110. [Practical] Walk through a concrete runbook for replacing a permanently dead node.

Replacing a dead node (vs. decommissioning a live one) uses the **replace_address** mechanism so the new node assumes the dead node's token ranges:

1. **Confirm the node is truly dead** and won't come back — `nodetool status` shows it `DN`. Do **not** `removenode` first if you intend to replace, because replacement should inherit its tokens.
2. **Provision a replacement** host with the same Cassandra version and config.
3. **Start it with the replace flag**, pointing at the dead node's address:
   ```
   -Dcassandra.replace_address_first_boot=<dead_node_ip>
   ```
   (in `cassandra-env.sh`/`jvm.options`). The new node bootstraps, streams the dead node's data from the surviving replicas, and takes over its tokens.
4. **Monitor** `nodetool netstats` for streaming progress; it stays `UJ` until done, then flips to `UN`.
5. **Run repair** on the new node afterward to catch any writes that arrived during the gap, since streaming sources may themselves have been slightly inconsistent.
6. **Remove the dead entry** if it lingers (`nodetool removenode <host_id>` only if replacement didn't clear it).

Critical caveat: if the dead node was down **longer than `gc_grace_seconds`**, streaming its (possibly stale, tombstone-expired) data risks resurrecting deletes — so replace promptly and always repair. Also ensure the replacement keeps the same rack assignment to preserve replica placement.

#### Q111. [Practical] How do you safely decommission a live node, and what's the difference from `removenode`?

- **`nodetool decommission`** is run **on the node being removed, while it is up**. It streams that node's data to the new owners *before* leaving, so no copies are lost and the ring stays balanced. This is the clean, preferred path for shrinking a cluster or retiring hardware.
- **`nodetool removenode <host_id>`** is run **from another node** when the target is already **dead** and can't decommission itself. The cluster re-replicates the dead node's ranges from surviving replicas. Because the dead node can't stream its own (possibly more-recent) data, you should **run repair afterward**.
- **`nodetool assassinate`** is the last resort — it forcibly removes a node from gossip without any data movement; use only when `removenode` is stuck, and follow with repair.

Runbook for a live decommission: verify the cluster can hold the data with one fewer node (capacity + RF), run `nodetool decommission` on the target, watch `netstats` until streaming completes and the node disappears from `nodetool status`, then run `nodetool cleanup` on remaining nodes. The mental model: **decommission = graceful, streams first; removenode/assassinate = the node is already gone, the cluster heals from replicas.**

#### Q112. [Coding] Write CQL that demonstrates a correct denormalized dual-write kept atomic, and explain the failure mode if you skip the batch.

```cql
-- Two tables holding the same order, for two query directions
BEGIN BATCH
  INSERT INTO orders_by_customer (customer_id, order_ts, order_id, total, status)
    VALUES (:cust, :ts, :oid, :total, 'PLACED');

  INSERT INTO orders_by_id (order_id, customer_id, order_ts, total, status)
    VALUES (:oid, :cust, :ts, :total, 'PLACED');
APPLY BATCH;
```

This is a **LOGGED** batch (the default), so the two inserts are **atomic**: the coordinator writes a record to the **batchlog** first, and if it dies mid-batch another node replays the batchlog so *both* statements eventually apply. If you instead did two separate writes and the process crashed between them, you'd get **divergent denormalized tables** — `orders_by_id` has the order but `orders_by_customer` doesn't, so "list this customer's orders" silently omits it. Important constraints: keep the batch to the **few tables that hold the same logical row** (both target the *same* partition data conceptually), never use a multi-partition batch for bulk throughput, and remember the batch is *atomic* but **not isolated** — readers can briefly see one statement applied before the other.

#### Q113. [Practical] You need to bulk-load 500M rows. Why is a `BATCH` the wrong tool, and what do you do instead?

A multi-partition `BATCH` is the wrong tool because its atomicity guarantee forces a **single coordinator** to fan the writes out to many nodes and persist a **batchlog** entry, creating a coordinator hotspot and *more* total work than independent writes. Batches are for **atomicity across a few denormalized tables**, never for throughput.

Correct bulk-loading approaches:
1. **Concurrent prepared async statements.** Prepare the insert once, then fire many single-partition writes asynchronously with a bounded in-flight window (e.g., 1–2k concurrent), using **token-aware** routing so each write goes straight to a replica. This saturates the cluster evenly.
2. **`cqlsh COPY FROM`** for moderate CSV loads (it parallelizes internally) — fine for millions, not ideal for hundreds of millions.
3. **SSTable bulk loading** for the largest loads: generate SSTables offline with `CQLSSTableWriter`, then stream them in with **`sstableloader`**. This bypasses the write path entirely and is the fastest for huge initial loads.
4. **Spark + the Cassandra connector** for distributed, parallel ingestion from a data lake.

Also throttle to avoid overwhelming compaction, spread the load across coordinators, and pre-split/pre-bucket partition keys so the load lands evenly rather than hammering a few partitions.

#### Q114. [Practical] How do you diagnose and fix a write hotspot where one node has far higher load than the others?

A write hotspot means traffic is concentrated on the replicas owning a small set of tokens. Diagnose, then fix the model:

**Diagnose:**
1. `nodetool status` — look at the **Load** and **Owns** columns; one node carrying disproportionate load with similar ownership indicates a *traffic* hotspot, not an ownership imbalance.
2. `nodetool toppartitions <ks> <table>` — samples the hottest partitions in real time, naming the offending keys.
3. Application metrics — is one partition key (e.g., a "global counter" row, a celebrity user, a `status='ACTIVE'` bucket) receiving most writes?

**Fix (almost always a modeling change):**
- **Add a bucketing/sharding component to the partition key** so a hot logical entity spreads across N partitions:
  ```cql
  -- before: PRIMARY KEY (feed_id, ts)   <- one hot partition per popular feed
  -- after : PRIMARY KEY ((feed_id, shard), ts)   shard = hash(writer) % 16
  ```
- **Avoid low-cardinality partition keys** (`status`, `country`, a single counter row) — they funnel writes to a handful of tokens.
- For **time-series**, ensure you bucket by time so "now" isn't one ever-growing hot partition.

The root cause is nearly always a partition key that doesn't distribute the *write* workload; vnodes and rebalancing won't help because the problem is logical, not token allocation.

#### Q115. [Coding] Write CQL to bucket a high-write partition to remove a hotspot.

```cql
-- Problem: a viral post's likes all hit ONE partition (post_id) -> hot replica
-- Solution: split each post into N sub-partitions via a shard bucket

CREATE TABLE likes_by_post (
    post_id  uuid,
    shard    int,          -- 0..(N-1), chosen at write time
    user_id  uuid,
    liked_at timestamp,
    PRIMARY KEY ((post_id, shard), user_id)
);

-- Write: pick a shard, e.g. hash(user_id) % 16 in the app
INSERT INTO likes_by_post (post_id, shard, user_id, liked_at)
VALUES (:post, :shard, :user, toTimestamp(now()));

-- Read all likers: scatter the N shards (N small, fixed) and merge in the app
SELECT user_id FROM likes_by_post WHERE post_id = :post AND shard = 0;
SELECT user_id FROM likes_by_post WHERE post_id = :post AND shard = 1;
-- ... up to shard = N-1
```

The trade-off is explicit: bucketing **spreads writes across N partitions/replica sets** (killing the hotspot) at the cost of **N reads to reconstruct the full set**. Choose N to match your write skew (16 is common) — large enough to spread load, small enough that the fan-out read stays cheap. For counts you'd keep a per-shard counter and sum N values. This "shard bucket in the partition key" is the canonical Cassandra fix for any single-logical-entity hotspot.

#### Q116. [Practical] `nodetool compactionstats` shows hundreds of pending compactions. Is that a problem, and what do you do?

A growing pending-compaction count means **SSTables are accumulating faster than compaction can merge them**, which raises read amplification (each read touches more SSTables) and, eventually, disk usage. Assess severity first:

1. **Is it draining or growing?** A transient spike after a bulk load or repair is normal and will drain. A steadily *climbing* backlog under steady-state load is the real problem.
2. **Check the cause:** a large repair/bootstrap just streamed many SSTables; a wrong compaction strategy (e.g., STCS on an update-heavy table); or compaction is throttled too low.

**Remedies:**
- **Raise throughput** if I/O headroom exists: `nodetool setcompactionthroughput <MB/s>` (0 = unthrottled, risky) and increase `concurrent_compactors` if you have spare CPU/disk.
- **Confirm disk isn't full** — compaction needs free space (STCS can need ~50% headroom); a full disk stalls it.
- **Reconsider strategy** — an update/read-heavy table thrashing under STCS may belong on LCS; time-series on TWCS.
- **Don't** blindly run `nodetool compact` (major compaction) on STCS — it creates one huge SSTable that then never compacts again. Prefer letting the strategy work, or use TWCS/LCS appropriately.

The judgment call: a backlog that drains is fine; a persistent one means I/O is saturated or the strategy is mismatched — fix throughput/headroom first, strategy second.

#### Q117. [Practical] How do you investigate and resolve high GC pauses that are causing nodes to flap?

Long JVM GC pauses freeze a node so peers stop receiving its gossip heartbeats and mark it `DN` — it "flaps" up and down, dropping requests each time. Investigate:

1. **Confirm GC is the cause.** `system.log` shows `GCInspector` warnings ("GC for ... ms"); pauses > a few seconds correlate with the flapping timestamps. Enable GC logging if not already on.
2. **Find what fills the heap.** Common drivers: **wide-partition reads** (assembling a huge partition in memory), **large `IN` queries or big batches**, **tombstone scans** (reading thousands of tombstones), too-large memtable/cache settings, or just an undersized heap for the load.
3. **Tune the collector.** Modern Cassandra favors **G1GC** with a heap typically 16–31 GB (stay under the 32 GB compressed-oops boundary); pathological STW pauses on CMS are a reason to move to G1, and newer JDKs offer ZGC for very low pause targets.

**Resolve the root cause, not just the collector:** cap query shapes (forbid huge `IN`, bound batch size, page reads), fix wide partitions and tombstone storms (the usual real culprits), and right-size heap/caches. Bumping the heap alone often just delays the problem — the durable fix is eliminating the allocation spikes from bad partitions/queries.

#### Q118. [Coding] Write CQL to page through a very large partition without timing out.

```cql
-- Use clustering-key range + LIMIT as an explicit cursor over a big partition.
-- Page 1: newest 1000 events for a device
SELECT event_ts, payload
FROM events_by_device
WHERE device_id = 'dev#9'
ORDER BY event_ts DESC
LIMIT 1000;

-- Page 2: continue strictly BEFORE the last event_ts you saw (e.g. '2026-06-30 09:00:00')
SELECT event_ts, payload
FROM events_by_device
WHERE device_id = 'dev#9'
  AND event_ts < '2026-06-30 09:00:00'
ORDER BY event_ts DESC
LIMIT 1000;
```

Two layers of paging exist and you should use both. **Server-side automatic paging** (driver `fetchSize`, default 5000) splits any result into pages transparently using an opaque paging state — that alone keeps a single query from materializing everything. But for a *huge* partition you should *also* do **explicit clustering-key range paging** (the `event_ts < lastSeen` cursor above), because it lets you checkpoint progress, resume after failures, and bound each request's work deterministically. The anti-pattern is `LIMIT 1000000` with no cursor, or relying on `OFFSET`-style skipping (Cassandra has none) — always page forward through the **clustering key**, never re-scan from the partition start.

#### Q119. [Practical] A `SELECT ... ALLOW FILTERING` works in your test environment but you're told never to ship it. Explain the concrete production risk with numbers.

`ALLOW FILTERING` tells Cassandra "I accept that you'll read rows that don't match and discard them" — i.e., it permits a query whose predicate isn't backed by the primary key, so the engine must **scan and filter**. The risk is one of **scale, not correctness**:

- In test with **1,000 rows on 1 node**, a filtering scan reads 1,000 rows and returns your 5 matches in milliseconds — looks fine.
- In production with **1,000,000,000 rows across 50 nodes**, the same query becomes a **cluster-wide scatter-gather** that reads vast numbers of rows on every node to find the same handful — seconds-to-minutes latency, massive heap pressure, GC pauses, and it can take nodes down. The cost grows with **data volume and cluster size**, which is exactly what test environments lack.

So the query that "works" in test is a latent landmine that detonates only once real data arrives. The fixes: build a **query table** keyed by the predicate, use **SAI** if you need flexible secondary access, or restrict filtering to **within a single partition** (`WHERE partition_key = ? AND non_key_col = ? ALLOW FILTERING`), where the scan is bounded to one partition and is acceptable.

### 🟠 — extended

#### Q120. [Practical] Design an end-to-end repair strategy for a 60-node, 3-DC cluster and justify each choice.

The goal is to repair **every range at least once within `gc_grace_seconds`** without saturating the cluster. My design:

1. **Use Cassandra Reaper (or an equivalent scheduler), not naive `nodetool repair`.** Reaper splits repairs into **token subranges**, runs them with bounded parallelism, paces them over time, and resumes after failures — avoiding the "repair the whole cluster at once" latency incident.
2. **Incremental repair as the steady state**, run frequently (e.g., daily), so already-repaired SSTables are marked and skipped — only new data is validated. Periodically run a **full repair** to guard against incremental-repair edge cases (the historical over-streaming / anticompaction hazards).
3. **Per-DC, per-token-range scheduling.** Repair within each DC and stagger across DCs so cross-DC streaming doesn't congest the WAN links all at once.
4. **Set `gc_grace_seconds` deliberately** (default 10 days) so the **full repair cycle completes well within it** — if repair takes 5 days, 10 days grace is safe; if you can only repair every 12 days, raise grace or speed up repair.
5. **Throttle streaming and compaction** during repair (`setstreamthroughput`, `setcompactionthroughput`) so repair doesn't crowd out live traffic, and schedule heavier passes in off-peak windows.
6. **Monitor** repair duration, streamed bytes, and pending compactions; alert if a cycle risks exceeding `gc_grace_seconds`.

The justification thread: tombstone purge correctness *requires* a complete repair within grace; subrange + paced + incremental keeps that achievable on 60 nodes without the cluster-wide latency spikes that scare teams away from repairing at all.

#### Q121. [Practical] A multi-DC cluster shows correct data in DC1 but stale data in DC2. Walk through root-causing it.

DC2 lagging DC1 means cross-DC replication or repair isn't keeping DC2 converged. Root-cause systematically:

1. **Is cross-DC replication flowing at all?** Check `nodetool status` from a DC2 node — are DC1 nodes seen as `UN`? A WAN partition or firewall change can sever the inter-DC gossip/streaming path, so DC2 simply never receives writes.
2. **Write consistency level.** If the app writes at **`LOCAL_QUORUM`** (the common default), the coordinator acks after the **local DC** confirms and replicates to DC2 **asynchronously**. A backlog, dropped mutations, or a WAN slowdown then leaves DC2 transiently behind. That's expected eventual consistency — confirm whether the staleness is *transient* (catches up) or *persistent*.
3. **Dropped mutations / hints to DC2.** `nodetool tpstats` on DC2 nodes for dropped `MUTATION`; if DC2 nodes were overloaded or briefly down, cross-DC writes were shed and now depend on hints/repair.
4. **Repair coverage.** If DC2 missed writes beyond the hint window and repair doesn't include cross-DC ranges, DC2 stays stale forever. Verify repairs span both DCs.
5. **Read CL.** If the *reader* in DC2 uses `LOCAL_QUORUM`, it only sees DC2 replicas — so any DC2 lag is visible. A read at `EACH_QUORUM`/`QUORUM` would pull the fresh value (at cross-DC latency).

The decision tree: transient lag under async cross-DC replication is normal (tune backpressure/throughput); persistent staleness means a **severed link, sustained dropped mutations, or repair that doesn't cover DC2** — fix connectivity first, then run a cross-DC repair to converge.

#### Q122. [Theory] Explain exactly why writing and reading both at `LOCAL_QUORUM` does *not* guarantee you read your own write across datacenters.

`LOCAL_QUORUM` requires a quorum **within the coordinator's local DC only**. Consider RF=3 per DC across DC1 and DC2:

- A **write at `LOCAL_QUORUM` in DC1** succeeds once **2 of DC1's 3 replicas** ack. DC2's replicas are updated **asynchronously** and are *not* required for the write to return success.
- If a client then **reads at `LOCAL_QUORUM` in DC2**, it needs 2 of DC2's 3 replicas — but those may **not yet have received** the asynchronously-replicated write. The read returns the **old** value.

The math `R + W > N` only holds **within one DC** here (`2 + 2 > 3` for DC1, and separately for DC2), but the write quorum and the read quorum are in **different replica sets**, so they don't overlap — the overlap guarantee that powers read-your-writes is broken across DCs.

To get cross-DC read-your-writes you must make the quorums overlap globally: write at **`EACH_QUORUM`** (a quorum in *every* DC) or read at **`QUORUM`/`EACH_QUORUM`** so the read set spans DCs and intersects the write set — at the cost of cross-DC latency. The common pragmatic answer is to **pin a session to one DC** (sticky routing) so reads and writes share the same local replica set, or use LWT/Accord where true linearizability is required.

#### Q123. [Coding] Write CQL and explain the model for an idempotent, retry-safe event ingestion pipeline.

```cql
-- Append-only, idempotent: the event's own id is part of the primary key,
-- so a retried insert overwrites itself instead of duplicating.
CREATE TABLE events_by_device (
    device_id  text,
    bucket     text,        -- e.g. '2026-06-30-14' (hour) to bound partition size
    event_id   timeuuid,    -- globally unique per event, also orders within bucket
    payload    text,
    PRIMARY KEY ((device_id, bucket), event_id)
) WITH CLUSTERING ORDER BY (event_id DESC)
  AND compaction = { 'class': 'TimeWindowCompactionStrategy',
                     'compaction_window_unit': 'HOURS',
                     'compaction_window_size': 1 }
  AND default_time_to_live = 7776000;     -- 90 days

-- Insert is naturally idempotent: same (device_id, bucket, event_id) upserts.
INSERT INTO events_by_device (device_id, bucket, event_id, payload)
VALUES ('dev#9', '2026-06-30-14', :event_id, :payload);
```

The design principles that make this retry-safe at scale:
- **Idempotency via the key.** Because `event_id` is in the primary key and the producer generates it once per logical event, a network-timeout retry re-inserts the *same* row (an upsert), so at-least-once delivery doesn't create duplicates — no read-before-write, no LWT needed.
- **Append-only, no updates/deletes** → no tombstones, so reads stay fast.
- **Time bucketing + TWCS + TTL** → each hour's data lands in its own SSTables and the whole file is dropped when it expires, avoiding per-row tombstones entirely.
- **`timeuuid`** gives uniqueness *and* time ordering for cheap "latest events" reads.

Avoid counters or in-place updates here; if you need per-key aggregates, derive them by scanning/streaming the append-only log rather than mutating shared rows, preserving idempotency and tombstone-freedom.

#### Q124. [Practical] You must change a table's partition key (the original choice causes wide partitions). What's the migration plan with zero downtime?

You **cannot `ALTER` a primary key** in Cassandra, so changing the partition key means creating a new table and migrating. Zero-downtime plan:

1. **Design the new table** with the corrected key (e.g., add a time/shard bucket to the partition key to bound size). Validate the new model against all current queries.
2. **Dual-write.** Deploy code that writes to **both** the old and new tables for every mutation. From this moment, all *new* data is in the new table; the cluster keeps serving reads from the old one.
3. **Backfill historical data.** Use a controlled, throttled job (Spark + the Cassandra connector, or a paged reader/writer) to copy existing rows from old → new, transforming the key (computing buckets/shards). Make the backfill **idempotent** (upserts) and resumable, and throttle it so it doesn't starve live traffic or flood compaction.
4. **Verify.** Reconcile counts/checksums between old and new for sampled key ranges; let dual-write + backfill run until parity.
5. **Switch reads** to the new table behind a flag, monitor latency/error budgets, and keep dual-write on as a rollback safety net.
6. **Decommission the old table** once confident: stop dual-writing, then `DROP TABLE` the old one (and `nodetool cleanup`).

The whole thing hinges on **dual-write + idempotent throttled backfill + flagged read cutover**, which lets you migrate live with rollback at every step and no maintenance window.

#### Q125. [Theory] A query intermittently returns slightly different results at CL ONE depending on which replica answers. Is this a bug? Explain and give the fix.

It is **not a bug** — it is expected behavior of an AP, eventually-consistent system read at a low consistency level. At **CL ONE**, the coordinator returns whatever the **single fastest replica** has, and replicas can transiently diverge (a recent write reached some replicas but not yet others; a replica was briefly down; hinted handoff/read repair hasn't healed it yet). So two reads can hit different replicas and see different (both "valid") versions until convergence.

Why it happens specifically:
- A write at low CL acks before all replicas have it.
- **Read repair only triggers at CL > ONE** (or probabilistically in old versions), so CL-ONE reads don't even heal the divergence they observe.

The fix is to **choose consistency to match the requirement**, not to "fix the database":
- Need **read-your-writes / monotonic reads** → ensure `R + W > N` (e.g., write `QUORUM`, read `QUORUM`, or `LOCAL_QUORUM` within a DC), which forces the read and write sets to overlap.
- Need **linearizability** for compare-and-set → use **LWT** (`IF` / Paxos) or, going forward, **Accord** transactions.
- If low-latency `ONE` is acceptable and minor staleness isn't → keep `ONE`, and rely on **regular repair** to bound divergence.

The teaching point: in Cassandra, consistency is a **per-query choice**; "different results at ONE" is the consistency/latency trade-off working as designed, and the lever is the CL, not a code fix.

#### Q126. [Coding] Write CQL using `PER PARTITION LIMIT` to fetch the top item from each of many partitions efficiently, and explain its value.

```cql
-- One leaderboard partition per region; clustering by score DESC.
CREATE TABLE scores_by_region (
    region   text,
    score    int,
    player   text,
    PRIMARY KEY (region, score, player)
) WITH CLUSTERING ORDER BY (score DESC, player ASC);

-- Top 3 players in EACH region in a single query (when you can enumerate regions):
SELECT region, score, player
FROM scores_by_region
WHERE region IN ('us', 'eu', 'apac')
PER PARTITION LIMIT 3;
```

`PER PARTITION LIMIT N` caps the rows returned **per partition** rather than for the whole result set, so the query returns the top N from *each* partition it touches instead of N rows total. Without it, a plain `LIMIT 3` over a multi-partition `IN` could return all 3 rows from a single region. The value: it lets you do "top-N per group" in one server-side pass, reading only N rows per partition (cheap, since clustering order means they're the first N on disk) — a clean fit for leaderboards, "latest message per conversation," or "most recent reading per sensor." Caveat: combine it with a bounded `IN`/known partition list (large `IN` on partition keys is itself an anti-pattern), or run per-partition queries concurrently when the partition set is large.

#### Q127. [Practical] After a repair, disk usage spiked and didn't come back down. What happened and what do you do?

A repair-induced disk spike is usually one of two things:

1. **Over-streaming / anticompaction overhead.** Repair streams the differing ranges between replicas; if replicas had drifted, each receives extra SSTables. With (older) **incremental repair**, **anticompaction** splits SSTables into repaired/unrepaired sets, transiently increasing SSTable count and disk use. The new data then needs **compaction** to merge and reclaim space — until compaction catches up, disk stays high.
2. **Snapshots.** Repairs (and many `nodetool` ops) can leave **snapshots** — hard-linked copies of SSTables that pin disk space even after the underlying files would be deleted. These don't clear automatically.

What to do:
- **Check the compaction backlog** (`nodetool compactionstats`). If it's draining, the spike is transient — let compaction merge the streamed SSTables and space returns. Raise compaction throughput if I/O allows.
- **Look for lingering snapshots:** `nodetool listsnapshots`, then `nodetool clearsnapshot` for ones you no longer need (verify they aren't your backup).
- **Confirm tombstones can purge** — if repair just made all replicas consistent, the next compaction can finally drop tombstones past `gc_grace_seconds`, which *reduces* space.
- **Ensure free headroom** so compaction can actually run; a near-full disk stalls reclamation.

The mental model: repair temporarily *adds* SSTables (streamed/anticompacted) and may leave snapshots; space comes back when **compaction merges them** and **snapshots are cleared** — so triage compaction progress and snapshots before assuming a leak.

### 🔴 — extended

#### Q128. [Behavioral] Describe a time you diagnosed a subtle data-correctness bug in production (e.g., resurrected deletes) and the systemic fixes you put in place.

Strong answers use a STAR structure and show systemic thinking, not just a one-off patch:

- **Situation.** "Support escalated that previously-deleted user records were reappearing days later. Intermittent, no error logs — a silent correctness bug, the worst kind."
- **Task.** "Find why deletes weren't sticking and guarantee it couldn't recur, without a maintenance window."
- **Action.** "I compared `WRITETIME()` of the resurfaced columns against the delete timestamps and found the values were *older* than the deletes — so it wasn't LWW loss. That pointed at **tombstone resurrection**. Auditing ops, I found repairs had silently been failing on a subset of nodes for weeks, so deletes weren't propagating before `gc_grace_seconds` purged the tombstones — classic zombie data. Immediate mitigation: temporarily raised `gc_grace_seconds` to stop further purges, then ran full repairs across all replicas to propagate the missing deletes and re-delete the resurrected rows."
- **Result + systemic fixes.** "Zero recurrences. Systemically I (1) deployed **Cassandra Reaper** with alerting so an incomplete repair cycle pages us *before* `gc_grace_seconds`, (2) added a **repair-freshness dashboard** per keyspace, (3) wrote a **runbook** linking `gc_grace_seconds` to the measured repair-cycle time, and (4) added a synthetic **delete-and-verify canary** that would catch resurrection automatically."

The signal interviewers want: timestamp-driven diagnosis (evidence over guessing), understanding the tombstone/repair/`gc_grace_seconds` interaction deeply, and converting an incident into **automation + monitoring + runbook** so the failure class is closed, not just the instance.

#### Q129. [Practical] Walk through end-to-end how you'd troubleshoot a p99 latency regression that appeared after a seemingly unrelated deploy.

I'd treat it as a controlled investigation, correlating the deploy with a concrete mechanism rather than assuming causation:

1. **Localize the regression.** Is p99 up on **reads, writes, or both**? On **all tables or one**? On **all nodes or a subset**? Per-table/per-node dashboards (and `nodetool tablehistograms`) narrow the surface immediately. A single-table read regression is a very different bug from a cluster-wide one.
2. **Correlate with the deploy's actual change.** "Unrelated" deploys often change query shape subtly: a new feature added an **`ALLOW FILTERING`** query, a larger **`IN`** clause, a bigger **batch**, a **dropped `LIMIT`**, a changed **consistency level**, or a new write pattern that **widened a partition** or **created tombstones** (insert-then-delete). Diff the query log / driver requests before vs. after.
3. **Inspect the data-path symptoms.** `TRACING ON` on the now-slow query → tombstones scanned, SSTables touched, replica timings. `nodetool tpstats` for dropped/pending; `compactionstats` for backlog; GC logs for new pause patterns. A deploy that, say, started TTL-deleting rows in a hot partition shows up as rising tombstone counts.
4. **Check resource and topology shifts.** Did the deploy change client routing (load-balancing policy, DC affinity), connection-pool size, or timeouts? Did it coincide with a node `DN`/joining that's unrelated to code?
5. **Form and test a hypothesis.** E.g., "the new endpoint issues an unbounded range read that hits a wide partition" → reproduce in staging with prod-shaped data, confirm the trace, fix the query/model, and verify p99 recovers. **Roll back** if the fix isn't immediate and the regression breaches SLO.

The discipline: **localize → correlate the deploy's real behavioral delta → confirm via tracing/metrics → reproduce → fix → verify**, never "it must be the database" without the trace evidence tying the slow query to a concrete cost (tombstones, SSTable count, wide partition, or a replica problem).

#### Q130. [Theory] An architect proposes using Cassandra LWT for a high-throughput banking ledger because "it's linearizable." Critique this and propose a sound design.

The proposal is **technically possible but operationally wrong** for a high-throughput ledger:

**Why LWT-everywhere fails here:**
- LWT runs **Paxos**: ~**4 round-trips** among replicas per conditional write, often 4× the latency and far less throughput than a normal write. Under high contention on the **same account partition**, concurrent LWTs **livelock** (proposals keep preempting each other), collapsing throughput exactly where money is hottest.
- LWT is **per-partition linearizable only** — a transfer touching **two accounts (two partitions)** is *not* atomic with classic LWT, so you can't safely move money between accounts as one operation. Mixing LWT with non-LWT writes to the same data also breaks the linearizability guarantee.

**Sounder designs:**
1. **Event-sourced, append-only ledger (preferred).** Model immutable, idempotent **transaction events** keyed so each posting is a unique append (`PRIMARY KEY ((account_id, bucket), txn_id)`); never mutate a balance in place. Derive balances by folding events (or maintain a materialized balance updated from the event stream). Idempotency keys make retries safe; no read-modify-write contention, no tombstones. This is how most ledgers at scale are actually built.
2. **Use Cassandra 5.x Accord** where you genuinely need **multi-partition, strict-serializable transactions** (transfer = debit A + credit B atomically). Accord provides general, leaderless multi-key transactions without Paxos's per-partition livelock profile — the right primitive if you need transactional transfers in Cassandra itself.
3. **Or pick the right tool.** If the ledger is moderate-scale and demands rich ACID across many rows, a relational/NewSQL store (PostgreSQL, CockroachDB/Spanner-style) may simply be the correct choice; use Cassandra for the high-volume immutable history and a transactional store for the balance-mutation core.

The critique to articulate: "linearizable" is **necessary but not sufficient** — it's *per-partition*, *expensive*, and *contention-fragile*. For a ledger, prefer **append-only event sourcing for idempotency and throughput**, reach for **Accord** for true multi-account atomicity, and reserve LWT for low-rate uniqueness checks, not the hot transfer path.

## ✅ Key Takeaways

- Cassandra is a **masterless, wide-column, AP/EL** store (Dynamo + Bigtable lineage) optimized for **write-heavy, high-scale, highly-available** workloads with **predefined access patterns**.
- **Model query-first**: design a denormalized table per query; the **partition key** controls distribution + which node owns data, **clustering columns** control on-disk sort order within a partition.
- A good partition key gives **even distribution + bounded size + matches the query**; getting it wrong (hot/wide partitions) is the #1 source of performance problems.
- **Tunable consistency** per query: `R + W > N` (e.g., QUORUM/QUORUM) yields strong reads; `LOCAL_QUORUM` is the multi-DC default; LWT/Accord for linearizable transactions.
- The **LSM engine** (commit log → memtable → immutable SSTable → compaction) makes writes append-only and fast; **bloom filters + indexes** keep reads efficient.
- **Tombstones** (from deletes/TTL) are a top hazard — they cause read amplification, can resurrect data, and must be purged via compaction after `gc_grace_seconds` with regular **repair**.
- Anti-entropy is layered: **hinted handoff** (briefly-down nodes) + **read repair** (read-time healing) + **`nodetool repair`** (comprehensive, mandatory). Skipping repair causes zombie data.
- Choose **compaction strategy** by workload: STCS (write-heavy), LCS (read/update-heavy), **TWCS (time-series + TTL)**.

## ⚠️ Common Pitfalls

- **Wide / hot partitions** — unbounded time-series partitions or low-cardinality partition keys; always bucket and ensure even distribution (< ~100 MB / 100k rows).
- **The queue / delete anti-pattern** — inserting then deleting rows in the same partition creates tombstone storms that wreck read latency; model append + TTL + TWCS instead.
- **`ALLOW FILTERING` in production** — hides a scatter-gather full scan that works in dev and collapses at scale; build a query table or use SAI instead.
- **Multi-partition `BATCH` for bulk throughput** — creates coordinator hotspots and is slower than concurrent async writes; use LOGGED batches only for atomicity across a few denormalized tables.
- **Skipping or mis-running repair** — not repairing within `gc_grace_seconds` causes deleted data to resurrect (zombies); naive cluster-wide repair causes latency incidents. Automate with Reaper, staggered + throttled.
- **Overusing LWT / secondary indexes** — LWTs are ~4× the cost (Paxos) and contend; classic 2i becomes a cluster-wide scatter-gather. Prefer denormalized tables; use SAI for secondary access.
- **`SimpleStrategy` in production** — ignores rack/DC topology; always use `NetworkTopologyStrategy`, even for a single DC.
- **Ignoring clock skew** — LWW conflict resolution means clock drift can cause silent, permanent lost updates; enforce tight NTP/chrony sync.

## 📚 Further Reading

- *Cassandra: The Definitive Guide, 3rd Edition* — Jeff Carpenter & Eben Hewitt (O'Reilly) — the canonical reference, current to recent versions.
- *Designing Data-Intensive Applications* — Martin Kleppmann (O'Reilly) — replication, partitioning, consistency, and LSM-trees from first principles; essential context for the advanced/expert sections.
- [Apache Cassandra Official Documentation](https://cassandra.apache.org/doc/latest/) — authoritative on data modeling, operations, compaction, and repair; current through 5.0.
- [DataStax Cassandra Data Modeling guides & DS201/Academy](https://www.datastax.com/learn) — query-first modeling methodology, anti-patterns, and hands-on courses.
- [The Amazon Dynamo paper](https://www.allthingsdistributed.com/files/amazon-dynamo-sosp2007.pdf) and the [Google Bigtable paper](https://research.google/pubs/pub27898/) — Cassandra's two foundational ancestors.
- [CEP-15: Accord (general-purpose transactions)](https://cwiki.apache.org/confluence/display/CASSANDRA/CEP-15%3A+General+Purpose+Transactions) — the leaderless consensus protocol behind Cassandra's transaction roadmap.
- [Cassandra 5.0 SAI & Vector Search documentation](https://cassandra.apache.org/doc/latest/cassandra/developing/cql/indexing/sai/sai-overview.html) — Storage-Attached Indexing and vector/ANN search for RAG workloads.
