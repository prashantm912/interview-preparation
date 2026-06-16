# PostgreSQL — Interview Preparation Guide

PostgreSQL is an open-source, ACID-compliant object-relational database known for its MVCC concurrency model, extensibility, rich indexing, and strong SQL standards compliance. This guide covers the internals and operational depth that senior interviews probe — from tuple visibility and VACUUM to logical replication, SSI, and production performance tuning (current through PostgreSQL 17/18, 2026).

[← Back to master index](../README.md)

## Table of Contents

- [🟢 Basic (0–2 yrs)](#-basic-02-yrs)
- [🟡 Intermediate (3–7 yrs)](#-intermediate-37-yrs)
- [🟠 Advanced (8–12 yrs)](#-advanced-812-yrs)
- [🔴 Expert (15+ yrs)](#-expert-15-yrs)
- [✅ Key Takeaways](#-key-takeaways)
- [⚠️ Common Pitfalls](#️-common-pitfalls)
- [📚 Further Reading](#-further-reading)

---

## 🟢 Basic (0–2 yrs)

### Q1. [Theory] What does it mean that PostgreSQL is ACID-compliant, and how does it deliver each guarantee?

ACID stands for Atomicity, Consistency, Isolation, and Durability. **Atomicity** means a transaction either fully commits or fully rolls back — PostgreSQL achieves this by writing changes that only become visible on `COMMIT`, and a crash mid-transaction leaves no partial state. **Consistency** means every committed transaction moves the database from one valid state to another, enforced via constraints (PK, FK, CHECK, NOT NULL). **Isolation** means concurrent transactions don't interfere visibly; PostgreSQL uses MVCC plus selectable isolation levels. **Durability** means once committed, data survives crashes — guaranteed by the Write-Ahead Log (WAL) being `fsync`-ed to disk before the commit is acknowledged. The "why" matters in interviews: durability and atomicity both flow from the WAL, which is the single most important reliability mechanism in the engine.

### Q2. [Theory] What is the difference between `VARCHAR(n)`, `TEXT`, and `CHAR(n)`?

In PostgreSQL all three are stored using the same variable-length internal representation (`varlena`), so there is **no performance penalty** for `TEXT` versus `VARCHAR`. `CHAR(n)` is blank-padded to a fixed length and is almost never the right choice — it wastes space and surprises people with trailing spaces. `VARCHAR(n)` enforces a maximum length check; `TEXT` is unbounded. The common production recommendation is to use `TEXT` (or `VARCHAR` without a length) and enforce length with a `CHECK` constraint only when the business rule truly requires it, since changing a `VARCHAR(n)` limit historically required a table rewrite (improved in modern versions but still a habit worth avoiding).

### Q3. [Theory] What is a primary key versus a unique constraint, and how are they implemented?

A primary key uniquely identifies a row and implies `NOT NULL` plus `UNIQUE`; a table can have at most one. A unique constraint enforces uniqueness but permits one or multiple NULLs (since `NULL` is never equal to `NULL` under standard SQL semantics). Both are physically backed by a **B-tree unique index** that PostgreSQL creates automatically. This is why adding a primary key on a large table is expensive — it builds an index. Choosing a natural key versus a surrogate key (like `BIGINT GENERATED ALWAYS AS IDENTITY`) is a design decision: surrogate keys keep foreign keys narrow and stable, which is usually preferred at scale.

### Q4. [Practical] You need an auto-incrementing ID column. What should you use in modern PostgreSQL and why not `SERIAL`?

Prefer `GENERATED ALWAYS AS IDENTITY` (SQL-standard, available since PostgreSQL 10) over the legacy `SERIAL`. `SERIAL` is syntactic sugar that creates a separate sequence and sets a column default; the sequence ownership is loose, the column can be overwritten by accident, and dump/restore edge cases are messier. `IDENTITY` columns are owned by the table, block manual inserts (with `ALWAYS`) unless you use `OVERRIDING SYSTEM VALUE`, and behave more predictably.

```sql
-- Modern, recommended
CREATE TABLE orders (
    id    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    total NUMERIC(12,2) NOT NULL
);
```

A note for distributed systems: monotonic integer IDs leak row counts and create insert hotspots; consider UUIDv7 (time-ordered) when you need globally unique, index-friendly keys.

### Q5. [Coding] Write a query (and the JDBC code) to upsert a user row, inserting if absent or updating the email if present.

**Problem:** A "create or update" by natural key without a race between `SELECT` then `INSERT`.

```sql
INSERT INTO users (username, email, updated_at)
VALUES (?, ?, now())
ON CONFLICT (username)
DO UPDATE SET email = EXCLUDED.email,
              updated_at = now()
WHERE users.email IS DISTINCT FROM EXCLUDED.email;  -- skip no-op updates
```

```java
String sql = """
    INSERT INTO users (username, email, updated_at)
    VALUES (?, ?, now())
    ON CONFLICT (username)
    DO UPDATE SET email = EXCLUDED.email, updated_at = now()
    WHERE users.email IS DISTINCT FROM EXCLUDED.email
    """;
try (PreparedStatement ps = conn.prepareStatement(sql)) {
    ps.setString(1, username);
    ps.setString(2, email);
    int affected = ps.executeUpdate();   // 0 if no-op, 1 if inserted/updated
}
```

**Why `ON CONFLICT` over a transaction with retry:** it is atomic at the statement level and leans on the unique index, eliminating the TOCTOU race entirely. **Edge cases:** the conflict target must match an existing unique constraint/index; `IS DISTINCT FROM` avoids dead tuples (and unnecessary WAL/bloat) from identical updates. **Time complexity:** one B-tree lookup, O(log n).

### Q6. [Theory] What is a NULL in PostgreSQL and what are the common gotchas?

`NULL` represents the absence of a value, not zero or empty string. The gotchas: `NULL = NULL` is `NULL` (not `TRUE`), so you must use `IS NULL`; aggregate functions like `COUNT(col)` skip NULLs while `COUNT(*)` counts all rows; `NULL` values are not counted by unique constraints (multiple NULLs allowed); and concatenating or arithmetic with NULL yields NULL. Use `COALESCE(col, default)` to substitute, and `IS DISTINCT FROM` for NULL-safe equality comparisons.

---

## 🟡 Intermediate (3–7 yrs)

### Q7. [Theory] Explain MVCC and how PostgreSQL determines tuple visibility.

MVCC (Multi-Version Concurrency Control) lets readers and writers avoid blocking each other by keeping **multiple physical versions of a row (tuples)**. Every row stores hidden system columns: `xmin` (the transaction ID that created it) and `xmax` (the transaction ID that deleted/updated it, or 0 if live). When a transaction starts a statement (or snapshot), it captures a **snapshot** describing which transaction IDs (XIDs) are committed, in progress, or in the future. A tuple is visible to a transaction if its `xmin` is committed and visible in the snapshot **and** its `xmax` is either zero or belongs to a transaction not visible (still running, aborted, or after the snapshot).

```
Row update flow (MVCC):
  Original tuple:  [xmin=100, xmax=0   | data="A"]   <- live
  UPDATE in txn 150:
    old tuple:     [xmin=100, xmax=150 | data="A"]   <- dead once 150 commits
    new tuple:     [xmin=150, xmax=0   | data="B"]   <- new version

  Snapshot of a concurrent reader (started before 150 committed)
  still sees data="A" because xmax=150 is not yet committed in its snapshot.
```

The crucial consequences: an `UPDATE` is physically an insert + mark-old-as-dead (not in-place), `DELETE` only sets `xmax`, and these dead tuples accumulate as **bloat** until VACUUM reclaims them. Visibility checks also consult the **commit log (clog/pg_xact)** and use the **visibility map** to short-circuit. This design is why PostgreSQL has near-zero read locking but pays for it with vacuuming.

### Q8. [Theory] What is VACUUM, why is autovacuum critical, and what is bloat?

Because MVCC leaves dead tuples behind on every `UPDATE`/`DELETE`, tables and indexes accumulate **bloat** — space occupied by invisible row versions. `VACUUM` reclaims that space for reuse within the table (it does not usually shrink the file; `VACUUM FULL` rewrites and shrinks but takes an `ACCESS EXCLUSIVE` lock). VACUUM also updates the visibility map (enabling index-only scans), refreshes the free space map, and — critically — performs **transaction ID freezing** to prevent XID wraparound. **Autovacuum** is the background daemon that triggers VACUUM/ANALYZE when dead-tuple counts cross configurable thresholds (`autovacuum_vacuum_scale_factor`, default 0.2 = 20% of the table). On high-churn tables you almost always lower per-table scale factors so vacuum runs more frequently in smaller bites. Bloat symptoms include growing table size with flat row counts, degraded scan performance, and inflated index sizes.

### Q9. [Theory] What is transaction ID wraparound and why can it shut down a database?

XIDs are 32-bit, giving ~4 billion values that wrap around. PostgreSQL uses a "frozen" marker so very old rows are always considered visible regardless of XID comparison. If autovacuum cannot freeze old tuples fast enough (e.g., disabled autovacuum, long-running transactions holding back the freeze horizon, or massive write volume), the database approaches wraparound. To protect data integrity, PostgreSQL first throws warnings, then refuses new XIDs and goes **read-only**, requiring a manual VACUUM to recover. This is a famous production outage class (Sentry's well-known 2015 incident). Modern versions added a 64-bit-friendly multixact handling and better monitoring via `pg_stat_progress_vacuum`, but the rule stands: never disable autovacuum, and watch `age(datfrozenxid)`.

### Q10. [Theory] Compare PostgreSQL index types: B-tree, Hash, GiST, GIN, BRIN, and SP-GiST.

```
Index type | Best for                              | Notes
-----------+---------------------------------------+--------------------------------
B-tree     | Equality + range on ordered scalars   | Default; supports ORDER BY, unique
Hash       | Equality only (=)                     | WAL-logged since v10; rarely beats B-tree
GiST       | Geometric, ranges, nearest-neighbor   | Lossy, extensible; PostGIS, KNN (<->)
SP-GiST    | Non-balanced/partitioned trees        | Quadtrees, tries, IP ranges
GIN        | Multi-value: arrays, JSONB, full text | Inverted index; fast lookups, slow writes
BRIN       | Huge, naturally-ordered tables        | Block-range min/max; tiny, great for time-series
```

The "why" interviewers want: **B-tree** is the default because it serves equality, range, sorting, and uniqueness. **GIN** indexes the *elements inside* a composite value (each array element, each JSONB key/value, each lexeme), making `@>` containment and full-text queries fast at the cost of expensive inserts (mitigated by the `fastupdate` pending list). **BRIN** stores only per-block-range summaries, so it is orders of magnitude smaller than B-tree and ideal for append-only time-series where physical order tracks the indexed column. **GiST** is the extensible framework that powers spatial (PostGIS), range types, and nearest-neighbor search.

### Q11. [Practical] How do JSONB and GIN indexing work, and when would you use it versus normalized columns?

`JSONB` stores JSON in a decomposed binary form (deduplicated keys, no whitespace), enabling indexing and operators like `@>` (contains), `?` (key exists), and path access (`->`, `->>`, `#>>`). Without an index, JSONB containment queries scan the table. A **GIN index** makes them fast:

```sql
-- Default jsonb_ops: supports @>, ?, ?&, ?|
CREATE INDEX idx_doc_gin ON events USING gin (payload);

-- jsonb_path_ops: smaller & faster for @> containment only
CREATE INDEX idx_doc_pathops ON events USING gin (payload jsonb_path_ops);

SELECT * FROM events WHERE payload @> '{"type":"login","ok":true}';
```

**Production guidance:** use JSONB for genuinely schema-flexible or sparse attributes (audit payloads, third-party webhooks, user-defined fields). For attributes you filter, join, or aggregate on constantly, prefer real columns — they get statistics, smaller indexes, and constraints. A hybrid pattern is to promote hot keys into **generated columns** with their own B-tree index while keeping the full document in JSONB. Trade-off: GIN indexes are larger and slower to update than B-tree, and JSONB lacks per-key statistics so the planner estimates poorly on selective keys.

### Q12. [Practical] How do you read an `EXPLAIN (ANALYZE, BUFFERS)` plan? Walk through what you look for.

`EXPLAIN` shows the planner's chosen plan with estimated cost/rows; adding `ANALYZE` actually executes it and reports real timing and row counts; `BUFFERS` shows shared/temp block reads (cache vs disk). You read it **inside-out, bottom-up** — leaf nodes execute first.

```
Seq Scan on orders  (cost=0.00..18334.00 rows=1000000 width=8)
                    (actual time=0.01..95.3 rows=1000000 loops=1)
  Buffers: shared hit=5000 read=13334
```

What to scan for: (1) **Estimated vs actual rows** — a big mismatch means stale statistics; run `ANALYZE`. (2) **Seq Scan on a large table** with a selective filter — likely a missing index. (3) **Nested Loop with high `loops`** — often a bad join order from row misestimation. (4) **`read=` high in BUFFERS** — cold cache or insufficient `shared_buffers`. (5) **`Rows Removed by Filter`** — work wasted reading rows that didn't match. (6) **External merge / temp disk in sorts** — raise `work_mem`. A practical habit: compare `rows` estimated to actual at every node; the planner's mistakes cascade upward.

### Q13. [Coding] Write a Java method that runs `EXPLAIN (ANALYZE, FORMAT JSON)` and extracts the top-level execution time.

```java
public double explainAnalyzeMillis(Connection conn, String query) throws SQLException {
    String wrapped = "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + query;
    try (Statement st = conn.createStatement();
         ResultSet rs = st.executeQuery(wrapped)) {
        if (rs.next()) {
            String json = rs.getString(1);              // single JSON text column
            JsonNode root = new ObjectMapper().readTree(json);
            JsonNode plan = root.get(0).get("Plan");
            return plan.get("Actual Total Time").asDouble();   // ms of the root node
        }
        throw new SQLException("No plan returned");
    } catch (JsonProcessingException e) {
        throw new SQLException("Bad plan JSON", e);
    }
}
```

**Caveat (security/correctness):** running `EXPLAIN ANALYZE` on `INSERT/UPDATE/DELETE` actually executes the statement and mutates data — wrap such calls in a transaction you roll back. **Complexity:** parsing is O(size of plan JSON). **Edge case:** `FORMAT JSON` returns one row, one column; older drivers may need `getObject`. Never build the inner `query` from untrusted input — it bypasses parameterization and is an injection vector.

### Q14. [Practical] What are partial and covering indexes, and when do they win?

A **partial index** indexes only rows matching a predicate, shrinking the index and speeding maintenance:

```sql
-- Only index unprocessed jobs (a tiny fraction of a huge queue table)
CREATE INDEX idx_jobs_pending ON jobs (created_at)
WHERE status = 'pending';
```

A **covering index** (via `INCLUDE`) stores extra non-key columns in the index leaf so the query is answered entirely from the index — an **index-only scan** — without heap fetches:

```sql
CREATE INDEX idx_orders_cover ON orders (customer_id) INCLUDE (total, status);
-- SELECT total, status FROM orders WHERE customer_id = ? -> index-only scan
```

Partial indexes win on skewed data (queue tables, soft-delete `WHERE deleted_at IS NULL`, boolean flags) where the hot subset is small. Covering indexes win on read-heavy lookups where heap visits dominate. The catch with index-only scans: the **visibility map** must mark the page all-visible, which requires recent VACUUM — otherwise PostgreSQL still visits the heap to check visibility.

### Q15. [Theory] Explain PostgreSQL's transaction isolation levels and what anomalies each prevents.

```
Level             | Dirty read | Non-repeatable | Phantom | Serialization anomaly
------------------+------------+----------------+---------+----------------------
Read Uncommitted  | (PG: same as Read Committed — no dirty reads ever)
Read Committed    | No         | Possible       | Possible| Possible
Repeatable Read   | No         | No             | No*     | Possible
Serializable      | No         | No             | No      | No
```

PostgreSQL never allows dirty reads — `READ UNCOMMITTED` is treated as `READ COMMITTED`. **Read Committed** (default) takes a fresh snapshot per statement, so each statement sees the latest committed data. **Repeatable Read** takes one snapshot for the whole transaction (snapshot isolation), preventing non-repeatable and phantom reads, but can still produce write-skew anomalies. **Serializable (SSI)** adds Serializable Snapshot Isolation, which detects dangerous read-write dependency cycles and aborts one transaction with a `40001` serialization error, giving true serializable behavior. The trade-off: SSI requires application-level retry logic on serialization failures but avoids the cost of explicit locking.

### Q16. [Coding] Implement a serializable-transaction retry loop in Java for a banking transfer.

**Problem:** Under `SERIALIZABLE`, conflicting transactions abort with SQLState `40001`; you must retry.

```java
public void transfer(DataSource ds, long from, long to, BigDecimal amt) throws SQLException {
    int maxRetries = 5;
    for (int attempt = 1; ; attempt++) {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            try (PreparedStatement debit = c.prepareStatement(
                    "UPDATE accounts SET balance = balance - ? WHERE id = ? AND balance >= ?")) {
                debit.setBigDecimal(1, amt); debit.setLong(2, from); debit.setBigDecimal(3, amt);
                if (debit.executeUpdate() == 0) {
                    c.rollback();
                    throw new IllegalStateException("Insufficient funds");
                }
            }
            try (PreparedStatement credit = c.prepareStatement(
                    "UPDATE accounts SET balance = balance + ? WHERE id = ?")) {
                credit.setBigDecimal(1, amt); credit.setLong(2, to);
                credit.executeUpdate();
            }
            c.commit();
            return;                                  // success
        } catch (SQLException e) {
            if ("40001".equals(e.getSQLState()) && attempt < maxRetries) {
                backoff(attempt);                    // exponential jitter
                continue;                            // retry whole transaction
            }
            throw e;
        }
    }
}
```

**Why this shape:** SSI may abort *either* transaction at commit, so the entire unit of work must be replayable — never retry a single statement. **Edge cases:** cap retries to avoid livelock; use exponential backoff with jitter; ensure the work is idempotent or fully re-derived from inputs. **Complexity:** each attempt is O(log n) index lookups; expected retries are low under moderate contention.

### Q17. [Practical] Why is connection handling a problem in PostgreSQL, and how does PgBouncer help?

Each PostgreSQL connection is a **separate OS process** with its own memory (work_mem allocations, catalog caches), so connections are expensive — a few thousand idle connections can exhaust RAM and degrade the scheduler. Applications (especially serverless/Lambda or many app pods) often open far more connections than the DB can efficiently serve. **PgBouncer** is a lightweight connection pooler that multiplexes many client connections onto a small set of server connections.

```
[1000 app clients] -> [PgBouncer pool: 25 server conns] -> [PostgreSQL]
```

Its three modes: **session** (server conn held for the client's whole session — safest, supports all features), **transaction** (server conn returned after each transaction — best throughput, but breaks session-level features like `SET`, prepared statements pre-PG-14, `LISTEN/NOTIFY`, advisory-lock-across-statements), and **statement** (per-statement, most restrictive). Production default is usually **transaction pooling** with the application avoiding session-state assumptions. Note PostgreSQL 14+ added support for protocol-level prepared statements with transaction pooling, and modern PgBouncer/pgcat handle this better. Always set the DB `max_connections` realistically (e.g., 100–300) and let the pooler absorb the fan-out.

### Q18. [Practical] A query that was fast is now slow after a data load. Walk through your diagnosis.

Real scenario: a nightly bulk load triples a table's size and a previously index-using query now does a sequential scan. **Approach:** (1) Run `EXPLAIN (ANALYZE, BUFFERS)` and compare estimated vs actual rows. (2) Check whether `ANALYZE` ran after the load — bulk loads don't auto-update statistics immediately, so the planner has stale row estimates and may pick a seq scan. (3) Inspect `pg_stat_user_tables` for `last_analyze`/`last_autovacuum` and dead-tuple counts. (4) Check if the index is bloated or invalid. **What I'd do in production:** run `ANALYZE the_table;` (or `VACUUM ANALYZE`) immediately after large loads as part of the ETL; if estimates are still off on correlated columns, add **extended statistics** (`CREATE STATISTICS`) so the planner understands cross-column dependencies; consider `default_statistics_target` increases for skewed columns. **Trade-off:** higher statistics targets mean slower `ANALYZE` and planning but better plans — worth it on large, queried tables.

### Q19. [Theory] What is the WAL, and how does it underpin durability, replication, and PITR?

The **Write-Ahead Log** is an append-only sequence of change records written *before* the corresponding data pages are flushed to disk. The rule (write-ahead): a change is durable once its WAL record is `fsync`-ed, even if the dirty data page is still only in shared buffers. On crash, recovery replays WAL from the last checkpoint to restore committed changes and discard uncommitted ones. The WAL is the foundation of three capabilities: **durability** (commit = WAL flushed), **streaming replication** (standbys replay the primary's WAL stream), and **Point-In-Time Recovery** (a base backup plus archived WAL lets you restore to any moment). Key tuning knobs include `wal_compression`, `max_wal_size`/`checkpoint_timeout` (checkpoint frequency vs recovery time), and `synchronous_commit` (turning it off trades a tiny durability window for big write throughput).

---

## 🟠 Advanced (8–12 yrs)

### Q20. [Theory] Explain declarative partitioning, its benefits, and partition pruning.

Declarative partitioning (PG 10+, matured through 11–13) splits one logical table into physical child tables by **RANGE**, **LIST**, or **HASH** of a partition key.

```sql
CREATE TABLE events (id bigint, ts timestamptz, payload jsonb)
    PARTITION BY RANGE (ts);
CREATE TABLE events_2026_06 PARTITION OF events
    FOR VALUES FROM ('2026-06-01') TO ('2026-07-01');
```

Benefits: (1) **Partition pruning** — the planner (and at runtime, the executor) skips partitions that can't match the `WHERE` clause, cutting I/O dramatically for time-bounded queries. (2) **Cheap data lifecycle** — dropping last month's data is a `DROP TABLE`/`DETACH` (instant) instead of a massive `DELETE` that bloats. (3) Smaller per-partition indexes, faster vacuum. **Caveats:** the partition key should appear in most queries and ideally in the primary key; cross-partition unique constraints require the key be part of the constraint; too many partitions (thousands) inflate planning time. Modern versions support partition-wise joins/aggregates and runtime pruning for parameterized plans. Real-world: time-series/event tables and multi-tenant LIST partitioning by tenant are the canonical use cases.

### Q21. [Theory] Compare streaming (physical) replication with logical replication. When do you use each?

```
                 Physical (streaming)         Logical (pub/sub)
Unit replicated  Raw WAL / data blocks         Decoded row changes (INSERT/UPDATE/DELETE)
Granularity      Whole cluster                 Per-table / per-publication
Version match    Same major version required   Cross-version capable
Standby usage    Read replicas, HA failover    Subscriber is fully writable
DDL              Replicated implicitly         NOT replicated (manual schema sync)
Typical use      HA, read scaling, PITR        Zero-downtime upgrades, CDC, selective sync
```

**Physical/streaming replication** ships the byte-for-byte WAL to standbys that replay it; the standby is a read-only mirror used for HA failover (often via Patroni) and read scaling. **Logical replication** uses logical decoding to turn WAL into a stream of row-level changes published to subscribers; it's selective, cross-version, and the target is writable — ideal for **major-version upgrades with near-zero downtime**, change-data-capture into Kafka/Debezium, and replicating a subset of tables between heterogeneous systems. Trade-offs: logical replication doesn't replicate DDL or sequences automatically, has more overhead, and historically didn't handle large transactions well (improved with streaming of in-progress transactions in PG 14+ and bidirectional/active-active improvements in later versions).

### Q22. [Practical] Design a high-availability failover setup and explain split-brain risk.

```
            writes
              |
        +-----v------+    streaming WAL    +-------------+
client->|  PRIMARY   |-------------------->|  STANDBY 1  | (sync)
        +-----+------+\                    +-------------+
              |        \  WAL              +-------------+
         WAL archive    +----------------->|  STANDBY 2  | (async)
              |                            +-------------+
        [object store for PITR]
        Patroni + etcd/Consul: leader election, automatic failover, fencing
```

**Approach:** use **Patroni** (or pg_auto_failover) with a distributed consensus store (etcd/Consul/ZooKeeper) for leader election. One synchronous standby gives zero data loss (`synchronous_commit = on`, `synchronous_standby_names`); async standbys add read capacity. On primary failure, Patroni promotes a standby and reconfigures others to follow the new leader. **Split-brain** is the danger: if a network partition makes the old primary think it's still leader while a new one is promoted, both accept writes and diverge. Mitigation: the consensus store enforces a single leader, and **fencing** (STONITH or `pg_rewind` + demotion) ensures the old primary is demoted before it can accept conflicting writes. **What I'd do:** one sync standby for RPO=0 within the region, async cross-region for DR, and WAL archiving for PITR as the ultimate backstop. Trade-off: synchronous replication adds commit latency proportional to the network round-trip.

### Q23. [Practical] How do you detect and remove index/table bloat in production safely?

**Detection:** estimate bloat with the `pgstattuple` extension (`SELECT * FROM pgstattuple('orders');` gives `dead_tuple_percent`) or community bloat-estimation queries against `pg_class`/`pg_statistic`. Watch `pg_stat_user_tables.n_dead_tup` and autovacuum frequency. **Removal options and trade-offs:** (1) Routine `VACUUM` reclaims space for reuse but doesn't return it to the OS — usually sufficient. (2) `VACUUM FULL` rewrites and shrinks the table but holds an `ACCESS EXCLUSIVE` lock — never on a live OLTP table. (3) For indexes, **`REINDEX CONCURRENTLY`** (PG 12+) rebuilds without long locks. (4) For tables, use **`pg_repack`** — it rebuilds the table/indexes online with only brief locks. **Production playbook:** first fix the *cause* (tune autovacuum scale factors lower, kill long-running transactions that pin the xmin horizon, batch large deletes), then run `pg_repack` during a low-traffic window for severe table bloat and `REINDEX CONCURRENTLY` for index bloat. Always verify free disk headroom — repack needs space for a full copy.

### Q24. [Coding] Write a query and Java code to find the slowest queries using `pg_stat_statements`.

**Problem:** Identify the top resource-consuming statements across the cluster.

```sql
-- Requires: CREATE EXTENSION pg_stat_statements;  (and shared_preload_libraries)
SELECT queryid,
       calls,
       round(total_exec_time::numeric, 1)         AS total_ms,
       round(mean_exec_time::numeric, 2)          AS mean_ms,
       round(100.0 * total_exec_time
             / sum(total_exec_time) OVER (), 1)    AS pct_of_total,
       rows
FROM pg_stat_statements
ORDER BY total_exec_time DESC
LIMIT 20;
```

```java
public List<SlowQuery> topQueries(Connection conn, int limit) throws SQLException {
    String sql = """
        SELECT queryid, calls, total_exec_time, mean_exec_time, query
        FROM pg_stat_statements
        ORDER BY total_exec_time DESC
        LIMIT ?
        """;
    List<SlowQuery> out = new ArrayList<>();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setInt(1, limit);
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new SlowQuery(
                    rs.getLong("queryid"),
                    rs.getLong("calls"),
                    rs.getDouble("total_exec_time"),
                    rs.getDouble("mean_exec_time"),
                    rs.getString("query")));
            }
        }
    }
    return out;
}
record SlowQuery(long id, long calls, double totalMs, double meanMs, String sql) {}
```

**Key insight:** sort by `total_exec_time` (cumulative impact), not `mean_exec_time` — a query taking 5ms but running a million times often hurts more than a rare 2-second report. `pg_stat_statements` normalizes literals so similar queries aggregate. **Security:** the `query` text is normalized (constants replaced by `$n`), avoiding leaking specific values; restrict the view via `pg_read_all_stats`. **Complexity:** a top-N sort over the in-memory statement hash table.

### Q25. [Practical] Walk through tuning PostgreSQL memory and planner settings for an OLTP workload.

Real scenario: a 64 GB RAM database server running mixed OLTP. **Key knobs and reasoning:** (1) `shared_buffers` ≈ 25% of RAM (16 GB) — PostgreSQL's page cache; beyond ~40% you get diminishing returns because it double-buffers with the OS cache. (2) `effective_cache_size` ≈ 50–75% of RAM (a *hint* to the planner about total cache, OS + shared_buffers), which biases it toward index scans. (3) `work_mem` is **per sort/hash node per connection** — set conservatively (e.g., 16–64 MB) because a query with several sorts under many connections multiplies it; raise it per-session for big analytical queries instead. (4) `maintenance_work_mem` higher (1–2 GB) speeds VACUUM/index builds. (5) `random_page_cost` lower (1.1) on SSDs so the planner stops over-penalizing index scans. (6) `max_connections` modest + PgBouncer. **What I'd do:** start from these ratios, then validate with `pg_stat_statements`, `EXPLAIN ANALYZE`, and watch `temp_files`/`temp_bytes` (indicates `work_mem` too low). Trade-off: aggressive `work_mem` risks OOM under load; conservative wastes potential. Always change one variable at a time and measure.

### Q26. [Theory] What are the differences between an index scan, index-only scan, and bitmap heap scan?

An **Index Scan** walks the index and, for each match, fetches the heap row — efficient for highly selective predicates returning few rows. An **Index-Only Scan** answers the query from the index alone (the needed columns are all in the index and the page is marked all-visible in the visibility map), skipping heap access entirely — fastest, but depends on recent VACUUM. A **Bitmap Heap Scan** is chosen when the predicate matches many scattered rows: PostgreSQL first builds a bitmap of matching heap pages from one or more indexes (a Bitmap Index Scan), sorts them in physical order, then reads the heap pages sequentially. This converts random I/O into sequential I/O and can combine multiple indexes (`BitmapAnd`/`BitmapOr`). The planner's choice hinges on estimated selectivity: few rows → index scan; medium → bitmap; most rows → sequential scan.

### Q27. [Theory] How does logical decoding power Change Data Capture (CDC), and what are replication slots?

Logical decoding reads the WAL and, via an output plugin (e.g., `pgoutput`, `wal2json`), emits a stream of committed row-level changes. CDC tools like **Debezium** consume this to publish change events into Kafka, keeping downstream systems (search indexes, caches, data lakes) in sync without dual writes. A **replication slot** is server-side state that tracks how far a consumer has read, guaranteeing the primary retains the necessary WAL until the consumer confirms consumption. The danger interviewers probe: an **inactive or slow slot pins WAL retention**, so WAL accumulates and can fill the disk, taking the primary down. Mitigations include monitoring `pg_replication_slots.restart_lsn` lag, setting `max_slot_wal_keep_size` (PG 13+) to cap retention, and dropping orphaned slots. Slots also exist for physical replication to prevent the primary from recycling WAL a standby still needs.

### Q28. [Practical] Describe using PostGIS for a real geospatial feature and how indexing supports it.

Scenario: "find all stores within 5 km of a user." With **PostGIS** you store a `geography(Point,4326)` column and use a **GiST index** to accelerate spatial predicates:

```sql
CREATE EXTENSION postgis;
ALTER TABLE stores ADD COLUMN geo geography(Point, 4326);
CREATE INDEX idx_stores_geo ON stores USING gist (geo);

SELECT id, name, ST_Distance(geo, :userPoint) AS meters
FROM stores
WHERE ST_DWithin(geo, :userPoint, 5000)      -- index-accelerated bounding filter
ORDER BY geo <-> :userPoint                  -- KNN nearest-neighbor via GiST
LIMIT 10;
```

`ST_DWithin` uses the GiST index to prune candidates by bounding box before exact distance math, and the `<->` operator drives a **KNN index scan** that returns nearest neighbors in index order without scanning everything. Real-world: ride-hailing, delivery ETA, and store locators all rely on this pattern. Trade-off: `geography` does true spheroidal math (accurate, slower) versus `geometry` (planar, faster, needs a projected SRID); choose based on accuracy needs and dataset extent.

---

## 🔴 Expert (15+ yrs)

### Q29. [Theory] Explain Serializable Snapshot Isolation (SSI) internals — how does PostgreSQL detect serialization anomalies without locking reads?

SSI builds on snapshot isolation but adds detection of **dangerous structures**: a pattern of read-write dependencies that can produce a non-serializable schedule. PostgreSQL tracks **predicate locks** (SIREAD locks) that record what each transaction *read* — at the tuple, page, or relation granularity depending on volume. It then monitors for **rw-antidependencies** (transaction T1 reads data that T2 later writes). A serialization failure becomes possible when there is a cycle, specifically a transaction with both an incoming and outgoing rw-conflict edge forming a "dangerous structure" (two consecutive rw edges with a pivot). When detected, PostgreSQL aborts one transaction with SQLSTATE `40001`. Crucially, SIREAD locks **don't block** — they only flag conflicts — so readers never wait, preserving MVCC's non-blocking reads. The cost is memory for predicate locks (granularity promotion under pressure can cause false positives and extra aborts) and the application burden of retry logic. This is among the most sophisticated isolation implementations in any production database and is a frequent deep-dive in staff-level interviews.

### Q30. [Theory] How does HOT (Heap-Only Tuple) update optimization reduce write amplification, and when does it fail?

A normal `UPDATE` writes a new tuple *and* must add new index entries pointing to it, even for unchanged-key indexes — expensive write amplification plus index bloat. **HOT** avoids this when **no indexed column is changed** and the new tuple fits on the **same heap page** as the old one. PostgreSQL then chains the new version to the old via a `t_ctid` pointer within the page; index entries still point to the original tuple, and an index lookup follows the HOT chain to the live version. This means no new index entries and the dead tuples can be cleaned by lightweight **HOT pruning** during normal page access (not even requiring a full VACUUM). HOT fails when (a) any indexed column changes, or (b) the page lacks free space (so set a lower `fillfactor`, e.g., 80–90, on heavily updated tables to reserve room). The practical lever: avoid indexing frequently-updated columns and tune `fillfactor` so high-churn tables keep getting HOT updates — this is one of the highest-leverage, least-known tuning moves for write-heavy OLTP.

### Q31. [Practical] You're architecting a 50 TB multi-tenant SaaS database. How do you scale PostgreSQL beyond a single node?

**Approach, layered:** (1) **Vertical + read replicas first** — biggest box, then streaming replicas for read scaling; route reads via a proxy. This handles surprisingly large workloads. (2) **Partitioning** — partition huge tables by tenant (LIST/HASH) or time (RANGE) to keep indexes and vacuum manageable and enable pruning. (3) **Tenant sharding** — if writes outgrow one node, shard by `tenant_id`: either application-level routing or **Citus** (now part of the PostgreSQL ecosystem) which distributes tables across worker nodes with a coordinator, supporting distributed joins and parallel queries. (4) **Offload** — move analytics to a column store / data lake via logical replication/CDC, and cold data to cheaper storage. **Trade-offs:** sharding sacrifices cross-shard transactions and joins (or makes them expensive via 2PC/coordinator); the shard key choice is near-irreversible, so pick one aligned with the dominant access pattern (tenant). **What I'd actually do:** exhaust vertical + partitioning + read replicas (they cover most SaaS scale), introduce Citus only when single-node writes are the proven bottleneck, and design the schema with `tenant_id` everywhere from day one to keep the future sharding path open. Real-world: companies like Heap, Cloudflare, and Microsoft (Citus) run multi-TB PostgreSQL fleets this way.

### Q32. [Theory] Explain checkpoints, full-page writes, and how they interact with WAL volume and recovery time.

A **checkpoint** flushes all dirty shared buffers to data files and records a checkpoint WAL record, establishing a point from which crash recovery can begin — recovery only replays WAL *after* the last checkpoint. **Full-page writes** (FPW) protect against **torn pages**: if the OS/storage writes only part of an 8 KB page during a crash, replaying a normal delta WAL record onto a corrupt page is unsafe. So the *first* modification to a page after each checkpoint writes the **entire page image** into the WAL. The interactions are a classic tuning tension: frequent checkpoints mean shorter recovery and steadier I/O but **more full-page writes** (because each checkpoint resets the "first touch" tracking), inflating WAL volume; infrequent checkpoints reduce FPW/WAL but lengthen recovery and cause larger I/O spikes at checkpoint time. Levers: `checkpoint_timeout`, `max_wal_size`, `checkpoint_completion_target` (spread the flush to smooth I/O), and `wal_compression` (compresses those full-page images). On modern systems with large `max_wal_size`, checkpoints are spread out and FPW dominate WAL right after each one — a key thing to recognize when WAL volume spikes periodically.

### Q33. [Coding] Implement a safe, batched delete of millions of old rows without long locks or bloat explosion.

**Problem:** Purge events older than 90 days from a 500M-row table without a single giant transaction (which would lock, bloat, and balloon WAL).

```java
public long purgeOld(DataSource ds, int batchSize) throws SQLException {
    String sql = """
        WITH doomed AS (
            SELECT id FROM events
            WHERE ts < now() - interval '90 days'
            ORDER BY id
            LIMIT ?
            FOR UPDATE SKIP LOCKED       -- don't block concurrent writers
        )
        DELETE FROM events e USING doomed d
        WHERE e.id = d.id
        """;
    long totalDeleted = 0;
    while (true) {
        int deleted;
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(true);                    // each batch commits independently
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setInt(1, batchSize);
                deleted = ps.executeUpdate();
            }
        }
        totalDeleted += deleted;
        if (deleted < batchSize) break;               // drained
        sleepBriefly();                               // let autovacuum keep up
    }
    return totalDeleted;
}
```

**Why batched:** small transactions keep locks short, let autovacuum reclaim dead tuples between batches (preventing runaway bloat), and cap WAL generation per commit. `FOR UPDATE SKIP LOCKED` avoids blocking on rows other workers hold. **Better alternative when applicable:** if the table were **range-partitioned by time**, the entire purge is a `DROP TABLE`/`DETACH PARTITION` — O(1), no dead tuples, no vacuum. That's the architecturally superior answer and worth stating. **Complexity:** O(n) total work across batches; each batch O(batchSize · log n). **Edge cases:** ensure an index on `ts` (or `id` order matching) so each batch's `SELECT` is cheap; throttle to protect replication lag.

### Q34. [Coding] Build a reliable job queue in PostgreSQL using `SKIP LOCKED`.

**Problem:** Multiple workers must each grab a distinct pending job without double-processing or blocking each other.

```java
public Optional<Job> claimJob(Connection conn, String workerId) throws SQLException {
    conn.setAutoCommit(false);
    String sql = """
        UPDATE jobs
        SET status = 'running', locked_by = ?, locked_at = now()
        WHERE id = (
            SELECT id FROM jobs
            WHERE status = 'pending'
            ORDER BY priority DESC, created_at
            FOR UPDATE SKIP LOCKED          -- skip rows other workers locked
            LIMIT 1
        )
        RETURNING id, payload
        """;
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setString(1, workerId);
        try (ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                Job j = new Job(rs.getLong("id"), rs.getString("payload"));
                conn.commit();
                return Optional.of(j);
            }
        }
    }
    conn.commit();
    return Optional.empty();
}
```

**Why this works:** `FOR UPDATE SKIP LOCKED` lets each worker atomically claim a different row; locked rows are invisible to other claimers, so there's no contention and no double-claim. A **partial index** `WHERE status='pending'` keeps the scan tiny as the queue drains. **Edge cases:** add a reaper that resets jobs stuck in `running` past a timeout (worker crash); for at-least-once semantics keep the job until processing succeeds, then mark `done`. **Trade-off:** PostgreSQL-as-queue is operationally simple and transactional with your data (no dual-write problem), but at extreme throughput a dedicated broker (Kafka/SQS) scales further. For most apps this pattern is the pragmatic winner. **Complexity:** O(log n) per claim with the partial index.

### Q35. [Theory] How would you achieve zero-downtime major version upgrades and large schema migrations?

**Major version upgrade options:** (1) `pg_upgrade` with hard links is fast but requires brief downtime and is in-place. (2) **Logical replication** enables near-zero downtime: stand up the new-version cluster as a subscriber, let it catch up via logical replication, verify, then flip the application and promote — downtime is just the cutover. This is how large shops upgrade with seconds of disruption. **Schema migrations on huge tables** require avoiding long `ACCESS EXCLUSIVE` locks: add columns as `NULL`/with non-volatile defaults (PG 11+ makes adding a column with a constant default instant — metadata only), create indexes with `CREATE INDEX CONCURRENTLY`, add foreign keys/check constraints as `NOT VALID` then `VALIDATE CONSTRAINT` (which takes a weaker lock), and backfill data in batches. Tools like `pg-osc`/`gh-ost`-style table copies or `pg_repack` handle the hardest rewrites online. **What I'd emphasize:** always set a short `lock_timeout` for migrations so a blocked DDL fails fast instead of queueing behind a long query and stalling all traffic — a single un-timed `ALTER TABLE` waiting on a lock can cause a full outage as new queries pile up behind it.

### Q36. [Behavioral] Tell me about a time you diagnosed a severe PostgreSQL production incident. How did you lead the response?

**Situation:** Use STAR. Example framing: a payments service began timing out; the primary's CPU was pinned and replication lag was climbing. **Task:** restore service fast while preserving data integrity, and find root cause. **Action:** I declared an incident and split roles — one engineer mitigating, one investigating. We checked `pg_stat_activity` and found dozens of sessions blocked behind a long-running `ALTER TABLE` that had queued behind an even longer reporting query, creating a lock pileup. We cancelled the reporting query (`pg_cancel_backend`), which let the DDL and the queue drain, restoring service in minutes. Root cause: a migration shipped without `lock_timeout`. **Result:** I led a blameless postmortem; we added mandatory `lock_timeout`/`statement_timeout` to all migrations, alerting on lock-wait depth and replication lag, and a CI check that flags rewriting DDL. **What this signals to interviewers:** calm incident command, correct use of diagnostics (`pg_stat_activity`, `pg_locks`, `pg_blocking_pids`), bias toward mitigation before forensics, and turning the incident into durable systemic fixes rather than blame.

### Q37. [Theory] What are the security best practices for a production PostgreSQL deployment?

Layered defense: (1) **Authentication** — use `scram-sha-256` (not `md5`), restrict via `pg_hba.conf` by host/database/user, and prefer certificate or IAM-based auth for cloud. (2) **Encryption** — TLS for connections (`sslmode=verify-full` on clients to prevent MITM) and at-rest encryption at the storage layer. (3) **Least privilege** — applications connect as roles with only needed grants; never as superuser; use `REVOKE` on `PUBLIC` schema (PG 15 changed the default so `PUBLIC` no longer has CREATE on the `public` schema, a meaningful hardening). (4) **Row-Level Security (RLS)** for multi-tenant isolation so a tenant physically cannot read another's rows even with a query bug. (5) **Injection prevention** — always parameterize; dynamic SQL must use `quote_ident`/`format(%I, %L)`. (6) **Auditing** — `pgaudit` extension and log analysis. (7) **Secrets** — rotate credentials, avoid passwords in connection strings (use a secrets manager). (8) Keep current with security patches. RLS plus least-privilege roles is the combination that contains blast radius when application bugs inevitably occur.

### Q38. [Practical] Your write throughput is capped and you suspect WAL/checkpoint pressure. How do you confirm and fix it?

**Confirm:** check `pg_stat_bgwriter` for `checkpoints_timed` vs `checkpoints_req` — many *requested* (not timed) checkpoints mean `max_wal_size` is too small and you're force-checkpointing under write pressure. Inspect `pg_stat_wal` (PG 14+) for WAL bytes, watch `wal_buffers` waits, and correlate latency spikes with checkpoint timing. High `buffers_backend` means backends are flushing their own dirty pages because the background writer can't keep up. **Fix, in order:** (1) raise `max_wal_size` (e.g., to tens of GB) so checkpoints are spread by time, not forced by volume. (2) Set `checkpoint_completion_target = 0.9` to smear the flush across the interval and avoid I/O spikes. (3) Enable `wal_compression` to shrink full-page-write volume. (4) Move WAL (`pg_wal`) to a separate fast disk to decouple WAL fsync from data I/O. (5) For non-critical workloads consider `synchronous_commit = off` (commits return before WAL fsync — small data-loss window on crash, large throughput gain) or batch commits. **Trade-off:** bigger `max_wal_size` lengthens crash recovery; async commit trades a durability window for speed — acceptable for some workloads, never for payments.

### Q39. [Theory] How do extensions like `pg_stat_statements`, `pgaudit`, and `pg_partman` fit a mature operational stack, and what does extensibility cost?

PostgreSQL's extensibility (custom types, operators, index access methods, background workers, hooks) is its defining strength. **`pg_stat_statements`** is the cornerstone of query observability — normalized, aggregated execution stats, essentially mandatory in production (loaded via `shared_preload_libraries`). **`pgaudit`** provides session/object-level audit logging for compliance (SOC2, PCI, HIPAA). **`pg_partman`** automates partition creation/retention so you don't hand-manage time partitions. **PostGIS** turns PostgreSQL into a leading spatial database. **Costs and risks:** extensions in `shared_preload_libraries` require a restart to add and run with backend privileges, so a buggy or unmaintained extension can crash or compromise the server; managed cloud providers (RDS/Aurora/Cloud SQL) restrict which extensions are allowed for exactly this reason. Version-compatibility across major upgrades must be verified (some C extensions need rebuilds). The discipline: treat extensions as dependencies — pin versions, vet maintenance status, and test upgrades. The payoff is enormous: many capabilities other databases bolt on externally live natively and transactionally inside PostgreSQL.

---

## 🧩 Extended Questions — Set 1: Deep Theory & Internals

### 🟢 Basic — extended

#### Q40. [Theory] What is the difference between `NUMERIC`/`DECIMAL`, `REAL`/`DOUBLE PRECISION`, and why does it matter for money?

`NUMERIC(p,s)` (the SQL standard `DECIMAL`) stores numbers as an **exact, arbitrary-precision decimal** — internally a base-10000 array of digits with a separate weight and sign. Because it is exact, `0.1 + 0.2` equals exactly `0.3`, and no rounding error creeps in across millions of additions. `REAL` (4 bytes) and `DOUBLE PRECISION` (8 bytes) are IEEE-754 binary floating point: fast (hardware FPU), compact, but **inexact** for most decimal fractions — `0.1` has no exact binary representation, so sums drift.

The interview point is *why this matters for money*. Floating point will silently produce `10.00000000000001` or lose pennies over large batches, which is unacceptable for ledgers, invoices, and balances. Use `NUMERIC` for any value where exactness is contractual. The trade-off is performance and storage: `NUMERIC` arithmetic is done in software, so it is significantly slower than `double precision`, and it occupies more space.

```sql
SELECT 0.1::double precision + 0.2::double precision;  -- 0.30000000000000004
SELECT 0.1::numeric + 0.2::numeric;                    -- 0.3  (exact)
```

For scientific/analytic workloads where speed dominates and tiny error is tolerable, prefer `double precision`. A subtle gotcha: `NUMERIC` *without* a precision spec is "unconstrained" and can store any magnitude exactly — useful but means you lose the implicit rounding `NUMERIC(12,2)` would give you. Note also that `NUMERIC` supports special values `'NaN'` and (since PG 14) `'Infinity'`, and that `NaN` compares as **equal to NaN and greater than all numbers** in PostgreSQL — a deliberate deviation from IEEE semantics so that B-tree indexes and sorts remain well-defined.

#### Q41. [Theory] What is the difference between `timestamp`, `timestamptz`, and how does PostgreSQL actually store time zones?

The surprising fact interviewers love: **`timestamptz` does not store a time zone**. Both `timestamp` (without time zone) and `timestamptz` (with time zone) occupy 8 bytes storing a microsecond count from the epoch `2000-01-01`. The difference is purely *behavioral at the boundary*. When you insert into a `timestamptz`, PostgreSQL interprets the literal using the session `TimeZone` setting, converts it to **UTC**, and stores that UTC instant. On `SELECT`, it converts the stored UTC value back into the session's `TimeZone` for display. `timestamp` does none of this — it stores the wall-clock digits you gave it verbatim, with no zone awareness, which makes it almost always the wrong choice for real events.

```sql
SET TimeZone = 'America/New_York';
SELECT '2026-06-16 12:00:00'::timestamptz;        -- stored as 16:00 UTC, shown as 12:00 -04
SET TimeZone = 'Asia/Kolkata';
SELECT '2026-06-16 12:00:00-04'::timestamptz;     -- same instant, shown as 21:30 +05:30
```

The practical rule is: store instants as `timestamptz` (an unambiguous point on the timeline) and let the session/connection set its zone for display, or convert explicitly with `AT TIME ZONE`. Reserve `timestamp` for cases where the zone genuinely does not apply (e.g., "store opens at 09:00 local in every branch's own zone" — a wall-clock concept). `AT TIME ZONE` is overloaded and confuses people: applied to a `timestamptz` it returns a zone-less `timestamp` (the wall clock in that zone), and applied to a `timestamp` it returns a `timestamptz` (interpreting the wall clock as being in that zone).

#### Q42. [Theory] What is the difference between a schema, a database, and a catalog in PostgreSQL?

A **cluster** is a single running PostgreSQL instance managed by one postmaster over one data directory; it contains multiple **databases**. A connection targets exactly one database and *cannot* query across databases in the same session (no cross-database joins) — this is a hard isolation boundary, unlike MySQL where "database" and "schema" are synonyms. Within a database, a **schema** is a namespace that groups tables, views, functions, types, etc.; the same object name can exist in different schemas (`sales.orders` vs `archive.orders`). Every database ships with a `public` schema by default.

"Catalog" has two meanings. The **system catalogs** (`pg_class`, `pg_attribute`, `pg_index`, ...) are the metadata tables that *are* the database's self-description — they live in the `pg_catalog` schema, which is implicitly first on every `search_path`. The **information_schema** is a SQL-standard, portable read-only view layer over those catalogs.

```
cluster (one postmaster, one data dir)
 ├── database: app
 │    ├── schema: public      (tables, views, functions)
 │    ├── schema: sales
 │    ├── schema: pg_catalog  (system catalogs — pg_class, pg_attribute, ...)
 │    └── schema: information_schema
 └── database: analytics
```

The `search_path` (default `"$user", public`) controls unqualified name resolution and is the source of subtle bugs: if a user creates a table in their own same-named schema, queries silently hit a different table than expected. For multi-tenant designs, schema-per-tenant gives strong namespace isolation but explodes catalog size and complicates pooling, while a single schema with `tenant_id` columns scales better operationally.

### 🟡 Intermediate — extended

#### Q43. [Theory] Describe the layout of a heap page and a tuple on disk. Why is the row width and column order sometimes relevant?

A heap (table) is an array of fixed-size **8 KB pages** (`BLCKSZ`). Each page has a structured layout: a 24-byte **page header**, an array of 4-byte **line pointers (ItemIds)** growing downward from the top, the actual **tuples** growing upward from the bottom, and **free space** in the middle. The split design lets a tuple move within the page (during HOT pruning) by updating its line pointer without changing the index entries that reference `(page, line-pointer-index)` via the CTID.

```
 +-------------------------------------------------+ 0
 | PageHeaderData (24 bytes)                       |
 +-------------------------------------------------+
 | ItemId | ItemId | ItemId | ...   ->             |  line pointers grow down
 +-------------------------------------------------+
 |                free space                       |
 +-------------------------------------------------+
 |             <- ... | Tuple | Tuple | Tuple      |  tuples grow up
 +-------------------------------------------------+ 8192
```

Each tuple has a 23-byte header (`HeapTupleHeaderData`: `xmin`, `xmax`, `ctid`, info bits, plus an optional null bitmap) followed by the user data, with each field aligned to its type's boundary (`int8`/`timestamp` align to 8, `int4` to 4, etc.). This alignment is why **column order can affect on-disk size**: placing an `int2` between two `int8` columns wastes padding bytes, whereas grouping columns largest-to-smallest minimizes padding. On wide tables with billions of rows, reordering columns to reduce padding can shrink the heap by several percent. The null bitmap is only present when a row actually contains a NULL, so NULLs are cheap. This page model also explains the 8 KB-derived limits: a tuple must fit in a page (after TOAST), and the maximum 1600 columns comes from the per-tuple header constraints.

#### Q44. [Theory] What is TOAST, and how does PostgreSQL store values larger than a page?

A tuple must fit within an 8 KB page, but you can clearly store a 50 MB `text` or `jsonb` value. **TOAST** (The Oversized-Attribute Storage Technique) is how. When a row's total size exceeds a threshold (`TOAST_TUPLE_THRESHOLD`, ~2 KB), PostgreSQL compresses and/or moves large `varlena` (variable-length) attributes out of the main heap into a hidden, per-table **TOAST table**, leaving an 18-byte pointer in the original tuple. The large value is split into ~2 KB chunks stored as ordinary rows in the TOAST relation, indexed for retrieval.

Each toastable column has a **storage strategy** controlling this: `PLAIN` (never toasted, fixed-length types), `EXTENDED` (compress then move out — default for most varlena types), `EXTERNAL` (move out but don't compress — better for substring access since compressed values can't be partially read), and `MAIN` (compress but keep inline if possible).

```sql
ALTER TABLE docs ALTER COLUMN body SET STORAGE EXTERNAL;     -- favor fast substring()
SELECT relname, reltoastrelid FROM pg_class WHERE relname='docs';
```

The interview-grade insight is the performance behavior: TOASTed columns are fetched **lazily** only when the value is actually referenced, so `SELECT id FROM docs` never touches the TOAST table — which is a strong argument against `SELECT *` on tables with big columns. Default compression is `pglz`; PG 14 added **LZ4** (`default_toast_compression = lz4`), which is much faster to compress/decompress at a slightly lower ratio. Heavy updates of large TOASTed values also create bloat in the TOAST table, which has its own autovacuum.

#### Q45. [Theory] Walk through the lifecycle of a query inside the backend: parse, rewrite, plan, execute.

Every statement flows through four stages in the backend process. (1) **Parse** — the raw SQL string is lexed and parsed into a parse tree; the parser also performs *analysis*, resolving table/column names against the catalogs and type-checking, producing a `Query` node. Syntax errors and "column does not exist" errors surface here. (2) **Rewrite** — the rule system transforms the query: views are expanded into their underlying queries, and any user `CREATE RULE` rewrites apply. Row-Level Security policies are also injected as additional qualifiers here. (3) **Plan/optimize** — the planner enumerates candidate execution plans (join orders, scan methods, join algorithms), estimates each one's cost using table statistics from `pg_statistic`, and picks the cheapest, producing a `PlannedStmt` tree of plan nodes. (4) **Execute** — the executor runs the plan tree using a demand-pull (Volcano/iterator) model: each node's `next()` pulls rows from its children on demand.

```
SQL text → [Parser] → parse tree → [Analyzer] → Query
         → [Rewriter] → Query(+views/RLS) → [Planner] → PlannedStmt
         → [Executor: pull rows top-down] → result
```

This separation explains several behaviors. The **plan cache** lives between planning and execution: prepared statements cache the `PlannedStmt` so repeated executions skip parse and plan. The cost-based planner's quality depends entirely on statistics, which is why stale `ANALYZE` causes bad plans even with correct SQL. And because the executor is a pull-based tree, `LIMIT` can stop early without the lower nodes producing all rows — a key reason `LIMIT` queries with a matching index ordering are cheap.

#### Q46. [Theory] Compare the three join algorithms — nested loop, hash join, and merge join. When does the planner pick each?

```
Algorithm    | Best when                          | Cost shape      | Needs
-------------+------------------------------------+-----------------+------------------
Nested Loop  | Outer side small; inner indexed     | O(outer·lookup) | optional inner index
Hash Join    | One side fits in work_mem; equi-join| O(N+M) build+probe| equality predicate
Merge Join   | Both inputs sorted on join key      | O(N+M) merge    | sorted/indexed inputs
```

A **nested loop** iterates the outer relation and, for each row, probes the inner — cheap when the outer is tiny and the inner has a usable index (turning the inner probe into an O(log n) index scan), and disastrous when both sides are large (it becomes O(N·M)). A **hash join** builds an in-memory hash table on the smaller ("build") side, then streams the larger ("probe") side against it; it only works for equality joins and is the workhorse for large unsorted equi-joins, but if the build side exceeds `work_mem` it spills to disk in **batches** (graceful but slower). A **merge join** requires both inputs sorted on the join key; it then walks them in lockstep like a zipper — excellent when inputs are already sorted (e.g., both arrive via index scans on the join columns) or for range/inequality merges, but the cost of sorting an unsorted input can make it lose to a hash join.

The planner chooses based on **estimated cardinalities and available orderings**: small outer with indexed inner favors nested loop; large unordered equi-joins favor hash; pre-sorted inputs favor merge. The classic production failure is a row-count underestimate causing the planner to pick a nested loop expecting "a few" outer rows, then actually getting millions — the plan node shows a huge `loops=` count and the query melts down. Spotting that mismatch in `EXPLAIN ANALYZE` and fixing the underlying statistics (or extended statistics for correlated columns) is the cure.

#### Q47. [Theory] How does PostgreSQL's cost-based optimizer estimate costs, and what is the genetic query optimizer (GEQO)?

The planner assigns each candidate plan a **cost** in abstract units derived from a handful of tunable parameters: `seq_page_cost` (1.0, the baseline — cost of a sequential page read), `random_page_cost` (default 4.0 — a random page read; lowered to ~1.1 on SSDs), `cpu_tuple_cost`, `cpu_index_tuple_cost`, and `cpu_operator_cost`. Total cost is roughly `(pages read × page cost) + (rows processed × cpu cost)`. To estimate *how many* rows and pages a node touches, it uses **statistics** collected by `ANALYZE` into `pg_statistic`: per-column null fraction, number of distinct values (`n_distinct`), most-common-values (MCV) list with frequencies, and a histogram of the value distribution. Selectivity of `WHERE x = 5` comes from the MCV/histogram; join selectivity multiplies these together (assuming independence — the source of correlated-column errors that **extended statistics** fix).

For queries with many joins, exhaustively searching all join orders is factorial and explodes. When the number of relations in a `FROM` exceeds `geqo_threshold` (default 12), PostgreSQL switches from the exhaustive **dynamic-programming** join search to **GEQO** — a genetic algorithm that treats join orders as "chromosomes," evolves a population through crossover/mutation, and keeps the cheapest survivors. GEQO finds a good-enough plan in bounded time at the risk of non-determinism and occasionally a suboptimal order.

The practical implications: the planner is only as good as its statistics, so `default_statistics_target` (sample size, default 100) and per-column `ALTER TABLE ... ALTER COLUMN ... SET STATISTICS` matter on skewed columns; `random_page_cost` must reflect your storage (SSD vs spinning disk) or the planner will systematically mis-weigh index vs sequential scans. These are estimates, not measurements — which is why `EXPLAIN` (estimates) can diverge wildly from `EXPLAIN ANALYZE` (reality).

#### Q48. [Theory] What is the buffer manager and the clock-sweep algorithm? How does it relate to `shared_buffers`?

`shared_buffers` is a fixed-size shared-memory array of 8 KB **buffer frames** that all backends share — PostgreSQL's own page cache sitting in front of (and partly duplicating) the OS page cache. When a backend needs a page, it looks it up in a hash table of buffer descriptors; a **hit** returns the in-memory page, a **miss** triggers a read from disk into a free or evicted frame. Because the cache is finite, PostgreSQL needs an eviction policy, and it uses a variant of **clock-sweep** (an approximation of LRU that is cheaper under concurrency than true LRU).

Each buffer carries a small **usage counter** (0–5). On access, the counter is incremented (capped). The clock hand sweeps circularly over the buffers; at each buffer it decrements the counter, and a buffer becomes evictable only when its counter hits zero and its pin count is zero. Frequently used pages keep getting their counter bumped and survive; cold pages decay to zero and get reused. If the chosen victim is **dirty**, it must be written out (to WAL-protected data files) before reuse.

```
clock hand →  [buf:usage=2]→[buf:usage=0  EVICT]→[buf:usage=4]→[buf:usage=1]→ (wraps)
              each pass decrements usage; zero + unpinned = victim
```

Two interview nuances: (1) PostgreSQL deliberately uses a **ring buffer** strategy for large sequential scans, bulk writes, and VACUUM so a single big `SELECT *` over a huge table doesn't evict the entire hot working set — a feature competitors often lack. (2) Sizing `shared_buffers` at ~25% of RAM (not more) is conventional precisely because data also lives in the OS cache; oversizing causes double-buffering and can hurt. The `pg_buffercache` extension lets you inspect exactly what is resident.

#### Q49. [Theory] What is the difference between the visibility map and the free space map, and what role does each play?

Each table has two small auxiliary fork files alongside its main heap. The **Free Space Map (FSM)** tracks, per heap page, roughly how much free space remains, so an `INSERT`/`UPDATE` looking for somewhere to place a new tuple can quickly find a page with room instead of scanning the whole table or always appending. VACUUM updates the FSM as it reclaims dead-tuple space, which is how that reclaimed space becomes reusable for new rows (even though the file doesn't shrink).

The **Visibility Map (VM)** has two bits per heap page: **all-visible** (every tuple on the page is visible to all current transactions — no dead tuples needing cleanup) and **all-frozen** (every tuple is frozen, so the page can be skipped during anti-wraparound freezing). The all-visible bit is what makes **index-only scans** possible: if the page is marked all-visible, an index-only scan can return the indexed columns *without* visiting the heap to check tuple visibility, because there's nothing on that page that could be invisible.

```
heap page state → set by VACUUM:
  all-visible bit  → enables index-only scan (skip heap visibility check)
  all-frozen  bit  → lets VACUUM skip the page during wraparound freezing
```

The interaction interviewers probe: after heavy writes, the VM bits get cleared (the page now has un-vacuumed changes), so index-only scans silently start hitting the heap again and slow down until autovacuum re-marks the pages. This is why "index-only scan" plans can degrade between vacuums, and why on read-heavy tables you sometimes vacuum more aggressively just to keep the VM warm. Both maps are tiny (one byte per page for FSM, two bits for VM) and are themselves crash-safe but rebuildable.

#### Q50. [Theory] How do sequences work internally, why do they create gaps, and what is sequence caching?

A sequence is a special single-row relation (`pg_class.relkind = 'S'`) holding `last_value`, the increment, and cache parameters. `nextval()` atomically advances it and returns the next value. The critical design property: **sequences are non-transactional** for the value they hand out — `nextval()` does **not** roll back. If a transaction calls `nextval()` and then aborts, the consumed value is gone forever. This is deliberate: making sequences transactional would serialize all inserters behind a single lock, destroying concurrency. The consequence is that identity/serial columns inevitably have **gaps** after rollbacks, crashes, or cache discards — gaps are normal and must not be treated as data loss or used to detect missing records.

Sequence **caching** (`CACHE n`) trades gaps for throughput: each backend grabs a block of `n` values under one lock and hands them out locally without further locking. With `CACHE 50`, a backend reserves 50 values; if it disconnects after using 3, the other 47 are discarded, creating a 47-value gap. Per-session caches also mean values are **not strictly monotonic across sessions** — session A may cache 1–50 while session B caches 51–100, so B's rows can commit with higher sequence values before A's.

```sql
CREATE SEQUENCE s CACHE 1;          -- default: minimal gaps, lock per nextval
ALTER SEQUENCE s CACHE 100;         -- fewer locks, larger gaps, weaker ordering
SELECT nextval('s'), currval('s');  -- currval is session-local
```

For crash safety, sequence advances are WAL-logged, but to avoid logging every single increment PostgreSQL logs a batch (32 values) ahead — so a crash can lose up to ~32 values of progress, another gap source. The takeaway for interviews: never assume gapless, monotonic, or transactional sequence values; if you need a gapless invoice number, you must build it with a separate counter row under explicit locking (and accept the serialization cost).

#### Q51. [Theory] What is the difference between heavyweight locks, lightweight locks (LWLocks), and spinlocks?

PostgreSQL has three tiers of locking, used for different purposes. **Heavyweight locks** (a.k.a. lock-manager locks) are the SQL-visible locks on database objects — table locks (`ACCESS SHARE` through `ACCESS EXCLUSIVE`), row locks, advisory locks. They support multiple modes with a full conflict matrix, are tracked in the shared lock table, participate in **deadlock detection**, and are held until transaction end (or explicit release for advisory). These are what you see in `pg_locks` and what `LOCK TABLE`, `SELECT ... FOR UPDATE`, and DDL acquire.

**Lightweight locks (LWLocks)** protect shared-memory data structures inside the engine — buffer pool entries, WAL insertion, the clog, the lock table itself. They offer only two modes (shared/exclusive), are very fast, are **not** subject to deadlock detection (the code is written to always acquire them in a fixed order), and are held only for the brief duration of a critical section. When you see `LWLock` wait events in `pg_stat_activity.wait_event` (e.g., `WALWrite`, `BufferContent`, `LockManager`), you're seeing internal contention, not application-level locking.

**Spinlocks** are the lowest tier: a few machine instructions implementing a busy-wait mutex around tiny critical sections (often used to protect an LWLock's own state). They are held for nanoseconds, never across a system call, and busy-loop rather than sleep because the wait is expected to be trivially short.

```
Heavyweight  → object/row/advisory locks; many modes; deadlock-detected; pg_locks
LWLock       → shared-memory structures; shared/excl; ordered to avoid deadlock
Spinlock     → tiny critical sections; busy-wait; sub-microsecond
```

The interview value: diagnosing contention requires knowing *which* tier. Application lock waits (heavyweight) show up as `Lock` wait events and blocking PIDs you can resolve via `pg_blocking_pids()`. Internal scalability ceilings (heavy `WALInsert`/`LockManager` LWLock waits) point to architectural limits — e.g., LockManager LWLock contention from queries touching thousands of partitions, fixed by reducing partition count or using fast-path locking.

### 🟠 Advanced — extended

#### Q52. [Theory] How does PostgreSQL build a snapshot, and what exactly do xmin, xmax, and the xip list mean?

When a transaction (or statement, under Read Committed) needs to determine visibility, it acquires a **snapshot** — a frozen view of which transactions had committed at that instant. A snapshot is conceptually `{xmin, xmax, xip[]}`: **xmin** is the lowest transaction ID still active (every XID below it is known committed or aborted and resolved), **xmax** is the first not-yet-assigned XID (everything ≥ xmax is in the future and invisible), and the **xip list** is the set of XIDs that were *in progress* at snapshot time (between xmin and xmax) and therefore invisible even though they fall in the visible range.

To decide if a tuple is visible, PostgreSQL checks the tuple's creating XID (`t_xmin`) and deleting XID (`t_xmax`) against the snapshot, consulting the **commit log (pg_xact/clog)** for commit status, and short-circuits using cached **hint bits** stamped on the tuple after the first visibility check:

```
visible(tuple) iff:
   t_xmin is committed AND visible-in-snapshot   (not in xip, < snapshot.xmax)
   AND (t_xmax == 0  OR  t_xmax is NOT committed/visible)   -- not yet deleted to me
```

This is the mechanism behind "the same query sees different rows under different isolation levels": Read Committed takes a *fresh* snapshot per statement (so xip/xmax advance), while Repeatable Read/Serializable take *one* snapshot at first query and reuse it for the whole transaction. It's also why a single long-running transaction is so damaging — its XID stays in everyone's xip-relevant horizon (the global xmin), preventing VACUUM from removing dead tuples newer than that transaction's snapshot, which causes bloat cluster-wide. Recent versions optimized snapshot acquisition (e.g., snapshot-scalability work in PG 14 reduced the `ProcArrayLock` contention of building snapshots under high connection counts).

#### Q53. [Theory] What are MultiXacts, and how can they cause their own wraparound problem?

A single tuple's `xmax` field normally holds one transaction ID — the transaction that deleted or locked it. But multiple transactions can hold a **shared lock** on the same row simultaneously (e.g., several concurrent `SELECT ... FOR SHARE`, or foreign-key checks that lock the parent row). Since `xmax` is one slot, PostgreSQL replaces it with a **MultiXact ID** — a pointer into a separate `pg_multixact` structure listing all the transactions (and their lock modes) sharing that tuple, with an info bit on the tuple marking xmax as a MultiXact rather than a plain XID.

The crucial and lesser-known fact is that MultiXact IDs are themselves a **32-bit counter that can wrap around**, exactly like regular XIDs — and they have their own freeze horizon (`autovacuum_multixact_freeze_max_age`, default 400 million). A workload that heavily uses row share locks or foreign keys on hot parent rows (think many child inserts all FK-locking the same parent) can burn through MultiXact IDs *faster* than ordinary XIDs and trigger anti-wraparound autovacuums driven by MultiXact age, even when the table's regular XID age looks healthy.

```sql
SELECT datname, age(datfrozenxid)        AS xid_age,
       mxid_age(datminmxid)              AS mxid_age
FROM pg_database ORDER BY mxid_age DESC;
```

This is a classic "the database keeps anti-wraparound-vacuuming a table I rarely write to" mystery — the answer is FK/share-lock-driven MultiXact consumption. PostgreSQL also stores MultiXact *members* in a second SLRU that has its own size pressure (`multixact_member` exhaustion), which historically caused hard-to-diagnose stalls. Monitoring `mxid_age()` alongside `age(datfrozenxid)` is the operational lesson.

#### Q54. [Theory] What is the difference between a generic plan and a custom plan for a prepared statement, and how does PostgreSQL decide?

When you `PREPARE` a statement (or a driver does so under the hood), PostgreSQL caches the parse/analyze result. On `EXECUTE`, it can either build a **custom plan** — re-planned with the *actual* parameter values substituted, so the planner can use those constants for selectivity estimation — or a **generic plan**, planned once with parameters treated as opaque placeholders and reused across executions to amortize planning cost. Custom plans are more accurate (they know `WHERE status = 'rare_value'` is selective) but pay full planning cost every execution; generic plans are nearly free to reuse but can be badly wrong when parameter values have skewed selectivity.

PostgreSQL uses an adaptive heuristic: for the first five executions it always builds custom plans and records their costs. From the sixth onward it compares the average custom-plan cost to the estimated generic-plan cost (which includes an assumed planning-cost saving); if the generic plan isn't meaningfully more expensive, it switches to the generic plan permanently. This auto-tuning aims to capture planning savings on repetitive queries while protecting skewed ones — but it can backfire: a statement whose first five parameter values happen to be selective may lock into a generic plan that's terrible for a later common value.

```sql
SET plan_cache_mode = force_custom_plan;   -- always re-plan with literals
SET plan_cache_mode = force_generic_plan;  -- never re-plan (PG 12+)
SET plan_cache_mode = auto;                -- default adaptive behavior
```

The practical lever (PG 12+) is `plan_cache_mode`: force custom plans for queries over highly skewed columns (e.g., a `status` column that's 99% `'done'`), and force generic plans for simple high-frequency point lookups where planning overhead dominates. This also intersects with PgBouncer transaction pooling and protocol-level prepared statements — knowing that "my parameterized query is suddenly slow after the sixth call" is the generic-plan switchover is a strong senior signal.

#### Q55. [Theory] How does `CREATE INDEX CONCURRENTLY` avoid locking writes, and why can it fail and leave an invalid index?

A plain `CREATE INDEX` takes a `SHARE` lock that blocks all writes (but not reads) to the table for the entire build — unacceptable on a busy table. `CREATE INDEX CONCURRENTLY` (CIC) trades a single long lock for a longer, multi-phase build that allows concurrent reads *and writes* throughout. It works in passes: (1) register the index in the catalog as "not ready, not valid" so new writes start maintaining it; (2) take a snapshot and do a first heap scan to build the index over existing rows; (3) take a second snapshot and a second scan to catch rows changed during the first pass; (4) wait for all transactions that could still be using older snapshots to finish, then mark the index valid. The two scans plus the wait are why CIC is slower and does roughly twice the work of a normal build.

The failure mode interviewers want: if CIC fails partway (a deadlock, a uniqueness violation discovered during the build, a cancellation, or a crash), it leaves behind an **`INVALID` index** that is still being maintained by writes (so it adds write overhead) but is **not used by the planner**. You must detect and clean it up manually.

```sql
-- Find invalid indexes left by a failed CONCURRENTLY build
SELECT i.indrelid::regclass AS table, c.relname AS index
FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
WHERE NOT i.indisvalid;

DROP INDEX CONCURRENTLY my_failed_index;      -- then retry the build
```

Additional gotchas: CIC **cannot run inside a transaction block** (it manages its own transactions for the snapshot waits), it waits on long-running transactions which can make it appear to hang, and a `UNIQUE` CIC that hits a real duplicate fails and leaves the invalid index. The same `CONCURRENTLY` machinery applies to `DROP INDEX CONCURRENTLY` and `REINDEX CONCURRENTLY`. The operational rule: always verify `indisvalid` after a concurrent build, and set `lock_timeout` cautiously since CIC's brief catalog locks can still queue behind a long transaction.

#### Q56. [Theory] How do CTEs interact with the optimizer — when are they an optimization fence, and what changed in PostgreSQL 12?

Before PostgreSQL 12, a `WITH` clause (CTE) was always an **optimization fence**: each CTE was materialized into a temporary result set, fully computed, before the outer query ran. The planner could not push predicates down into the CTE or inline it. This had two faces — it was a useful *trick* to force a particular evaluation (materialize a complex subquery once, prevent the planner from re-evaluating a volatile function), but it was also a frequent *performance trap*: people wrote readable CTE-chained queries expecting SQL-standard transparency and got surprise full materializations that defeated index usage.

PostgreSQL 12 changed the default to **inline** CTEs that are referenced exactly once and are side-effect-free (no `INSERT`/`UPDATE`/`DELETE`, no volatile functions), letting the planner optimize across the boundary as if it were a subquery — so predicate pushdown and index selection work. You can still control it explicitly:

```sql
WITH recent AS MATERIALIZED (        -- force the old fence behavior
    SELECT * FROM events WHERE ts > now() - interval '1 day'
)
SELECT * FROM recent WHERE user_id = 42;   -- without MATERIALIZED, predicate can push in

WITH cte AS NOT MATERIALIZED (...) ...      -- force inlining even when referenced many times
```

The reasoning to articulate: `MATERIALIZED` is correct when you want to *guarantee* a subexpression is computed once (it's referenced many times, or it calls an expensive/volatile function, or you're deliberately fencing a planner misestimate). `NOT MATERIALIZED` (or relying on the default) is correct when you want the planner to see through the CTE and push filters down. **Recursive** CTEs (`WITH RECURSIVE`) and CTEs containing data-modifying statements are always materialized regardless. Knowing the version boundary (pre-12 fence vs 12+ inlining) is exactly the kind of detail senior interviews use to date a candidate's PostgreSQL experience.

#### Q57. [Theory] How does parallel query execution work, and what governs whether and how much parallelism a query gets?

A backend can recruit **parallel worker processes** (from the `max_worker_processes` pool, bounded by `max_parallel_workers` and per-statement `max_parallel_workers_per_gather`) to split scan/join/aggregate work across CPUs. The plan contains a **Gather** (or **Gather Merge**) node: below it, multiple workers plus the leader run the *partial* plan in parallel, each processing a slice of the input; above it, the Gather node collects their outputs. Parallel-aware nodes include Parallel Seq Scan (workers grab page ranges via a shared block counter), Parallel Index Scan, Parallel Hash Join (workers cooperatively build one shared hash table since PG 11), and partial aggregation (workers pre-aggregate, the leader finalizes).

```
Finalize Aggregate
  └ Gather  (workers=4)
      └ Partial Aggregate
          └ Parallel Seq Scan on big_table   (each worker scans a disjoint slice)
```

The planner only *considers* parallelism when the table is large enough (`min_parallel_table_scan_size`, default 8 MB) and the estimated cost justifies the **parallel overhead** (`parallel_setup_cost`, `parallel_tuple_cost`) of spawning workers and shipping tuples through shared memory. The number of workers scales roughly logarithmically with table size unless overridden by the table's `parallel_workers` reloption. Several things make a query (or a node) **parallel-restricted or parallel-unsafe** and force serial execution: writing data (DML is parallel-unsafe except the parallelized portions of `CREATE TABLE AS`/`CREATE INDEX`), calling `PARALLEL UNSAFE` functions (the default for user functions unless explicitly marked `PARALLEL SAFE`), cursors, `SERIALIZABLE` isolation historically, and constructs like `FOR UPDATE`.

The interview-grade nuances: (1) workers count against your overall process and memory budget — each worker can use up to `work_mem`, so a 4-worker hash join can use 5× `work_mem`. (2) Functions in queries must be correctly labeled `PARALLEL SAFE`/`RESTRICTED`/`UNSAFE`; mislabeling either disables parallelism or risks correctness. (3) "Why is my big query single-threaded?" usually traces to a parallel-unsafe function, a small-table estimate, or `max_parallel_workers_per_gather = 0`.

#### Q58. [Theory] What is the difference between `DROP COLUMN`, adding a column with a default, and a table rewrite — and which DDL operations are "instant"?

Whether an `ALTER TABLE` is instant (metadata-only) or a full **table rewrite** (every row copied, doubling disk use and holding `ACCESS EXCLUSIVE` for the duration) determines whether it's safe on a billion-row table. `DROP COLUMN` is **instant**: PostgreSQL doesn't physically remove the data; it marks the column dropped in `pg_attribute` (renaming it to `........pg.dropped.N........` and setting `attisdropped`), and the bytes are only reclaimed lazily as rows are later updated or the table is rewritten/repacked. This is why dropping a column doesn't immediately free space.

Adding a column has evolved. Adding a **nullable** column with no default was always instant. Adding a column **with a constant (non-volatile) default** was a full rewrite before PostgreSQL 11; since 11 it is **instant** — PostgreSQL stores the default as a "missing value" attribute (`atthasmissing`/`attmissingval`) and synthesizes it for old rows at read time, rewriting lazily. But a **volatile** default (e.g., `DEFAULT random()` or `DEFAULT clock_timestamp()`) still forces a rewrite because each row needs a distinct computed value.

```
Operation                                  | Lock              | Rewrite?
-------------------------------------------+-------------------+----------
ADD COLUMN ... (nullable, no default)      | ACCESS EXCLUSIVE  | No (instant)
ADD COLUMN ... DEFAULT <constant>          | ACCESS EXCLUSIVE  | No (PG 11+; was yes)
ADD COLUMN ... DEFAULT <volatile>          | ACCESS EXCLUSIVE  | Yes
ALTER COLUMN TYPE (binary-coercible)       | ACCESS EXCLUSIVE  | No (e.g., varchar→text)
ALTER COLUMN TYPE (general)                | ACCESS EXCLUSIVE  | Yes
DROP COLUMN                                | ACCESS EXCLUSIVE  | No (lazy)
SET NOT NULL                               | ACCESS EXCLUSIVE  | Scan (verify), no rewrite
```

Two more senior details: even an "instant" `ALTER TABLE` still needs the brief `ACCESS EXCLUSIVE` lock, so it must wait for and block concurrent queries — hence always use `lock_timeout` so a blocked DDL fails fast instead of stalling all traffic behind it. And some type changes are *binary coercible* (`varchar(50)` → `text`, or widening `varchar(50)` → `varchar(100)` since PG 9.2) and skip the rewrite, while narrowing or changing representation forces one. Knowing this table is what separates a safe migration from an accidental multi-hour outage.

#### Q59. [Practical] Explain how `pg_rewind` works and why it is needed after a failover.

After an automated failover, a standby is promoted to primary and starts its own WAL timeline. The **old primary**, once it comes back, has diverged: it may contain transactions it committed locally that never reached the new primary (because they were lost in the crash/partition), so its data directory is *ahead* of the new timeline on some pages and can't simply rejoin as a standby — replaying the new primary's WAL onto it would be incoherent. The naive fix is a full fresh base backup (`pg_basebackup`) of the entire data directory, which on a multi-terabyte cluster can take hours and saturate the network.

**`pg_rewind`** is the fast alternative. It synchronizes the old primary's data directory with the new primary by copying only the **blocks that changed** since the two diverged. It works by reading the old primary's WAL backward from its current position to the **last common checkpoint** (the divergence point), collecting the set of data blocks that were modified on the old timeline, then fetching the *current* version of exactly those blocks from the new primary (plus the new primary's WAL forward). The result is a data directory that can replay the new timeline's WAL and follow the new primary — a small fraction of the work of a full rebuild.

```bash
pg_rewind --target-pgdata=/data/old_primary \
          --source-server="host=new-primary port=5432 user=repl" \
          --progress
```

Prerequisites and gotchas: the cluster must have either `wal_log_hints = on` or data checksums enabled (so `pg_rewind` can identify changed blocks — without hints, full-page-write info is insufficient); the old primary must be **cleanly shut down** first (a crash-inconsistent directory must first be recovered); and you need a `restore_command` or access to enough WAL to reach the divergence point. This is the mechanism Patroni invokes automatically to reattach a failed primary, avoiding a full re-clone — a key reason large HA fleets can recover quickly. The trade-off: `pg_rewind` discards the old primary's diverged (lost) transactions, which is the correct behavior but means any locally-committed-then-lost writes are gone, reinforcing why synchronous replication is needed for RPO=0.

#### Q60. [Theory] What are hint bits, and why can a pure `SELECT` cause disk writes and dirty pages?

The first time any transaction examines a tuple's visibility, it must consult the **commit log (pg_xact/clog)** to learn whether the tuple's `xmin`/`xmax` transactions committed or aborted — a relatively expensive shared-memory/SLRU lookup. To avoid repeating that lookup forever, PostgreSQL caches the answer directly on the tuple as **hint bits** in the tuple header (`HEAP_XMIN_COMMITTED`, `HEAP_XMIN_INVALID`, `HEAP_XMAX_COMMITTED`, `HEAP_XMAX_INVALID`). Once set, future visibility checks read the bit and skip the clog entirely.

The counterintuitive consequence interviewers probe: a **read-only `SELECT` can dirty pages and cause writes**. When the first reader after a bulk insert/load scans those rows, it sets the hint bits, which modifies the page — marking it dirty — so it eventually gets written back to disk by the background writer or checkpointer. This is why the *first* query over freshly loaded data is often mysteriously slow and generates write I/O, and why a `SELECT`-only workload can still produce dirty-buffer writes. Hint-bit setting is *not* WAL-logged (it's a derivable optimization, recoverable from the clog), which is generally fine, but it can interact badly with checksums/full-page writes after a checkpoint.

```
First scan of new rows:
  tuple visibility unknown → read pg_xact (clog)  → expensive
  set HEAP_XMIN_COMMITTED on tuple → page now DIRTY → write-back later
Subsequent scans:
  read hint bit → skip clog → cheap
```

The practical implications: (1) benchmark "first run" separately — hint-bit setting warms the data the same way the buffer cache does. (2) Bulk-load patterns sometimes deliberately run a `SELECT count(*)` or `VACUUM` after loading to set hint bits (and freeze) up front rather than penalizing the first user query. (3) This is part of why VACUUM is valuable beyond bloat reclamation — `VACUUM (FREEZE)` and the visibility-map updates set/settle these states so later reads are cheap and index-only scans become possible.

### 🔴 Expert — extended

#### Q61. [Theory] Walk through logical decoding internals: how does the WAL — which is physical — get turned into row-level changes, and what are the constraints?

The deep tension is that WAL records are **physical** (e.g., "on relation 16384, block 42, at this offset, write these bytes"), yet logical replication needs **logical** changes ("INSERT into table `orders` the row `{id:7, total:9.99}`"). Logical decoding bridges this. A **logical replication slot** drives a decoding session that reads the WAL stream sequentially and reassembles transactions: it buffers each transaction's change records by XID (because WAL interleaves concurrent transactions), and only on seeing the **commit** record does it emit that transaction's changes in commit order to an **output plugin** (`pgoutput` for native logical replication, or `wal2json`, `decoderbufs`, etc.). The plugin formats the changes for the consumer.

For decoding to produce meaningful logical rows it needs two things the physical WAL doesn't always carry. First, `wal_level = logical` must be set so the WAL includes enough information (e.g., the old row's identifying columns on `UPDATE`/`DELETE`). Second, each replicated table needs a **REPLICA IDENTITY** so `UPDATE`/`DELETE` can be expressed as "the row *with these key values* changed" — by default this is the primary key; a table with no PK requires `REPLICA IDENTITY FULL` (log the entire old row, expensive) or it can't replicate updates/deletes.

```sql
ALTER TABLE t REPLICA IDENTITY FULL;          -- no PK: log whole old row for UPDATE/DELETE
SELECT * FROM pg_logical_slot_peek_changes('myslot', NULL, NULL);  -- inspect decoded stream
```

The hard constraints and failure modes: (1) decoding happens in **commit order**, so a giant or long-open transaction delays *all* downstream changes until it commits (PG 14 added streaming of in-progress transactions to mitigate). (2) **DDL is not decoded** — schema changes must be propagated out-of-band, and a column added on the publisher but not the subscriber breaks replication. (3) The slot **pins WAL** at its `restart_lsn` until the consumer confirms, so a stalled consumer accumulates WAL and can fill the disk (cap with `max_slot_wal_keep_size`). (4) Catalog timetravel: decoding old changes requires the catalog state *as it was* at that time, which is why a slot also pins the catalog xmin and an aggressive `VACUUM` on `pg_catalog` can't remove rows a slot still needs. Articulating the physical-to-logical reassembly plus the REPLICA IDENTITY requirement is the core of a strong answer.

#### Q62. [Theory] Compare PostgreSQL's MVCC implementation (in-heap versioning) with Oracle's/MySQL InnoDB's undo-log approach. What are the trade-offs?

Both PostgreSQL and Oracle/InnoDB implement MVCC, but the *physical* mechanism differs fundamentally and this drives their operational characteristics. PostgreSQL keeps **all row versions in the heap itself**: an `UPDATE` writes a brand-new tuple in the table and marks the old one dead (`xmax`), and readers find the version visible to their snapshot by walking what's in the heap. Oracle and InnoDB instead keep **one current version in place** and write the *before-image* needed to reconstruct older versions into a separate **undo/rollback segment**; a reader needing an old version reconstructs it by applying undo records backward.

```
PostgreSQL (in-heap)           InnoDB / Oracle (undo)
--------------------           ----------------------
UPDATE = new tuple in heap     UPDATE = modify in place
old version stays in heap      old version → undo log
readers scan heap versions     readers reconstruct from undo
cleanup = VACUUM (dead tuples) cleanup = purge undo (background)
```

The trade-offs cut both ways. PostgreSQL's design makes **rollback essentially free** (just don't make the new tuples visible; the dead ones are cleaned later) and keeps the commit path simple, but it pays with **bloat and VACUUM**: dead versions accumulate in the table and indexes, indexes must point at every version, and a long transaction holds back cleanup globally. The undo approach keeps the main table **compact** (no dead tuples, indexes point at the single live row) and avoids vacuum-style bloat, but pays with **expensive rollback** (must apply undo), the risk of "**snapshot too old / ORA-01555**" errors when undo is recycled before a long reader finishes, and contention on the undo segments.

A second-order difference: because PostgreSQL indexes reference physical tuples and every update can create a new index entry (unless HOT applies), **write amplification on indexed-column updates** is a known PostgreSQL weakness — addressed partially by HOT and by ongoing work (e.g., index deduplication in PG 13, and experimental pluggable storage like zheap that explored an undo-style heap). InnoDB's clustered-index design means secondary indexes store the primary key rather than a physical pointer, so a row move doesn't invalidate secondary indexes, but secondary-index lookups cost an extra primary-key traversal. The senior takeaway: PostgreSQL trades vacuum overhead for cheap rollback and simple concurrency, which is an excellent default but demands disciplined autovacuum tuning at scale.

#### Q63. [Theory] How do GIN indexes work internally — the posting tree/list structure, the pending list, and the cost model?

A GIN (Generalized Inverted iNdex) is an **inverted index**: instead of mapping a row to its value, it maps each *extracted key* (an array element, a JSONB key/value, a full-text lexeme) to the set of rows containing it. Internally it's a B-tree of **keys** (the "entry tree"); each key's leaf points to its **posting list** — the TIDs of matching heap tuples. When a single key matches many rows, the posting list outgrows a page and is promoted to a **posting tree** (a B-tree of TIDs) for efficient lookup and merging. A containment query like `tags @> ARRAY['a','b']` is answered by finding each key's posting list and intersecting them — extremely fast for "which rows contain all/any of these elements."

The performance asymmetry interviewers focus on: GIN is **slow to update** because inserting one row may touch many keys, each requiring a posting-list/tree modification scattered across the index. To soften this, GIN has a **pending list** (`fastupdate = on`, the default): new entries are appended to an unsorted pending list cheaply, and are merged into the main structure in bulk later — during `VACUUM`, when the list exceeds `gin_pending_list_limit`, or on demand via `gin_clean_pending_list()`.

```sql
CREATE INDEX ON docs USING gin (body) WITH (fastupdate = on, gin_pending_list_limit = '4MB');
SELECT gin_clean_pending_list('docs_body_idx');   -- force merge
```

The catch with the pending list is that **reads must also scan it** (it isn't yet in sorted form), so a large unmerged pending list slows queries and adds latency variance — a query can be fast or slow depending on pending-list state. For write-heavy + read-latency-sensitive workloads you sometimes set `fastupdate = off` to pay the write cost up front for predictable reads. Cost-wise, `jsonb_path_ops` (containment-only) builds smaller posting structures by hashing whole paths instead of indexing each key separately, so it's faster and smaller for `@>` queries but can't serve key-existence (`?`) queries. Understanding the entry tree → posting list/tree → pending list pipeline, and the read/write trade-off it creates, is the depth expected here.

#### Q64. [Theory] How does autovacuum's cost-based delay throttle itself, and how do you reason about tuning it on a high-write system?

Autovacuum must run continuously without saturating disk I/O and harming foreground queries, so VACUUM uses a **cost-based delay** accounting scheme. As it works, it accumulates an abstract "cost" for the pages it touches: `vacuum_cost_page_hit` (1 — page found in shared buffers), `vacuum_cost_page_miss` (2 — page read from disk; tunable, historically larger), and `vacuum_cost_page_dirty` (20 — a clean page it had to dirty). When the accumulated cost reaches `autovacuum_vacuum_cost_limit` (default 200, or inherited from `vacuum_cost_limit`), the worker **sleeps** for `autovacuum_vacuum_cost_delay` (default 2 ms in modern versions) before resuming. This converts "how much I/O work per unit time" into a controllable throttle — effectively a rate limiter on VACUUM's page throughput.

```
work until accumulated_cost >= cost_limit  → sleep cost_delay ms → repeat
effective I/O budget ≈ cost_limit / cost_delay  (pages-worth of work per ms)
```

The key tuning reasoning: on a **high-write system** the default throttle is often far too gentle, so autovacuum falls behind — dead tuples and bloat accumulate faster than vacuum can reclaim them, and you eventually see anti-wraparound emergency vacuums that *ignore* the cost delay and hammer I/O at the worst time. The fix is to **raise the I/O budget** (increase `autovacuum_vacuum_cost_limit` and/or lower `autovacuum_vacuum_cost_delay`) so vacuum keeps pace, and to **increase parallelism/frequency** by raising `autovacuum_max_workers` and lowering per-table `autovacuum_vacuum_scale_factor` on the hottest tables so they vacuum in small frequent passes rather than huge rare ones.

The trade-offs to articulate: too aggressive and VACUUM competes with foreground I/O, raising query latency; too gentle and you accrue bloat and risk wraparound. Modern hardware (NVMe) generally justifies a much higher cost limit than the conservative defaults. Critical caveats: per-table `autovacuum_*` storage parameters override the global ones for hot tables; anti-wraparound (`to prevent wraparound`) vacuums are non-skippable and run regardless; and `maintenance_work_mem` (or `autovacuum_work_mem`) governs how many dead TIDs vacuum can collect per index pass — too small forces multiple expensive index scans per table. The senior signal is connecting the cost accounting to a concrete "autovacuum can't keep up" symptom and the specific knobs that fix it.

#### Q65. [Theory] What is fillfactor, and how does tuning it interact with HOT updates, page splits, and index bloat?

`fillfactor` is a per-relation storage parameter (10–100, default **100** for tables, **90** for B-tree indexes) telling PostgreSQL what percentage of each page to fill during inserts/builds, deliberately leaving free space behind. For a **heap table**, that reserved space is what enables **HOT updates**: a HOT update requires the new tuple version to fit on the *same page* as the old one (so index entries needn't change). At `fillfactor = 100` a hot-updated page quickly fills, HOT stops applying, updates start landing on other pages, and you get index write amplification and bloat. Lowering a heavily-updated table to `fillfactor = 85` reserves room so successive updates stay HOT, dramatically reducing index churn and WAL.

```sql
ALTER TABLE accounts SET (fillfactor = 85);   -- reserve 15% per page for HOT updates
VACUUM FULL accounts;                          -- or pg_repack, to apply to existing pages
```

For **B-tree indexes**, fillfactor governs how full leaf pages are packed at build time. The interaction is with **page splits**: when you insert a key into a full leaf page, B-tree must split it into two half-full pages — an expensive operation that fragments the index and causes bloat. Leaving free space (the default 90, or lower) on randomly-inserted indexes reduces split frequency. But for **monotonically increasing keys** (a serial/timestamp PK where new keys always go to the rightmost page), PostgreSQL has special "rightmost leaf" handling and packs those pages densely (fillfactor 90 is effectively ignored for the always-rightmost inserts), so you generally leave it alone there.

The reasoning to convey: fillfactor is a **space-for-update-efficiency trade**. You pay storage (and slightly more pages to scan) up front to avoid the much larger cost of page splits, off-page updates, and index bloat under churn. It only affects *newly written* pages, so changing it requires a rewrite (`VACUUM FULL`/`pg_repack`/REINDEX) to take effect on existing data. The canonical wins: lower table fillfactor on hot OLTP tables to preserve HOT; leave index fillfactor at default for random keys; don't bother lowering it for append-only/monotonic data where there's no in-place update or random-insert split pressure.

#### Q66. [Theory] Explain the architecture of PostgreSQL's background processes — postmaster, backends, and the auxiliary processes — and what each is responsible for.

PostgreSQL is a **process-per-connection** architecture (not threads). The **postmaster** is the supervisory parent process: it listens on the socket/port, performs authentication handshakes, **forks a backend process for each accepted connection**, owns the shared-memory and semaphore setup, and — crucially for reliability — monitors all children and orchestrates crash recovery if any backend dies unexpectedly (it resets shared memory and restarts the cluster to a consistent state rather than risk corruption). Each client **backend** then handles exactly one connection's queries for its lifetime; backends share data only through shared memory (the buffer pool, lock tables, WAL buffers), which is why each connection carries real OS-process overhead and why poolers matter.

Beyond backends, a set of **auxiliary processes** handle background duties:

```
postmaster (supervisor: listen, fork, crash recovery)
 ├── backend (one per client connection)  ... ×N
 ├── background writer   — trickles dirty buffers to disk to smooth I/O
 ├── checkpointer        — performs checkpoints (flush all dirty buffers + WAL record)
 ├── WAL writer          — flushes WAL buffers to disk periodically
 ├── autovacuum launcher — schedules autovacuum workers per database
 │    └── autovacuum worker(s) — run VACUUM/ANALYZE  (≤ autovacuum_max_workers)
 ├── archiver            — runs archive_command to ship completed WAL segments
 ├── stats/logical-rep   — cumulative statistics; logical/physical replication senders
 └── WAL receiver/senders— streaming replication endpoints
```

The interview value is matching a symptom to the responsible process. High `buffers_backend` in `pg_stat_bgwriter` means backends are flushing their own dirty pages because the **background writer/checkpointer** can't keep up. WAL fsync stalls implicate the **WAL writer** and checkpoint timing. Replication lag points at **WAL sender/receiver**. The **archiver** failing (a broken `archive_command`) silently piles up unarchived WAL and can fill `pg_wal`. And the **postmaster's** crash-recovery design — kill one backend and the whole cluster briefly restarts to protect shared memory — explains why a single backend segfault (e.g., from a buggy C extension) takes down all connections momentarily. PostgreSQL 15 also moved the statistics collector from a UDP-socket process to shared memory, eliminating a long-standing scalability and stats-loss pain point — a good version detail to mention.

#### Q67. [Theory] What are SLRU caches (clog, subtrans, multixact), and how can they become a hidden performance bottleneck?

Several pieces of PostgreSQL's internal bookkeeping are stored in fixed-size, on-disk ring structures cached through **SLRU** (Simple LRU) buffers — small dedicated caches separate from the main buffer pool. The main ones: **pg_xact (clog)** records each transaction's commit/abort status (2 bits per XID); **pg_subtrans** maps subtransactions to their parents; **pg_multixact** holds the shared-lock member lists (see MultiXacts); **pg_commit_ts** optionally records commit timestamps; and others. These are consulted constantly during visibility checks (e.g., the clog lookup behind hint bits), so they sit on the hot path.

Historically each SLRU had a **small, hardcoded number of buffer pages** (e.g., clog ~128 buffers). On large, high-concurrency systems this becomes a hidden ceiling: when working-set XIDs/multixacts span more pages than the cache holds, every visibility check that misses must do an I/O and contends on the SLRU's control lock — surfacing as `SLRU` / `MultiXactOffsetSLRU` / `SubtransSLRU` wait events in `pg_stat_activity` and unexplained latency spikes that don't correspond to any user query cost.

```sql
-- Symptom: high SLRU-related waits under heavy concurrency / long-running transactions
SELECT wait_event_type, wait_event, count(*)
FROM pg_stat_activity WHERE wait_event_type = 'LWLock' GROUP BY 1,2;
-- PG 17 exposes per-SLRU stats:
SELECT * FROM pg_stat_slru;
```

The classic triggers are **subtransaction-heavy** workloads (lots of `SAVEPOINT`s, or PL/pgSQL `BEGIN ... EXCEPTION` blocks each of which creates a subtransaction) overflowing `pg_subtrans`, and **MultiXact-heavy** FK/share-lock workloads overflowing the multixact SLRUs — both producing the infamous "**subtrans/multixact SLRU contention**" stalls. The fix evolved: for years you needed a recompile to enlarge these caches, but **PostgreSQL 17 made SLRU buffer sizes configurable** (`subtransaction_buffers`, `multixact_offset_buffers`, `transaction_buffers`, etc.) and added `pg_stat_slru` observability. Mentioning that these caches exist, why they're on the hot path, the subtransaction/multixact triggers, and the PG 17 tunability is a strong staff-level depth signal.

#### Q68. [Practical] How would you diagnose and resolve a deadlock, and how does PostgreSQL's deadlock detector actually work?

A **deadlock** occurs when two (or more) transactions each hold a lock the other needs, forming a cycle in the wait-for graph — e.g., T1 locked row A and wants B, T2 locked row B and wants A. PostgreSQL does **not** prevent deadlocks proactively (that would require global lock ordering it can't enforce on arbitrary SQL); instead it **detects** them. When a transaction blocks waiting for a heavyweight lock, it doesn't run the detector immediately — it first sleeps for `deadlock_timeout` (default **1 second**), on the assumption most lock waits resolve quickly and running the detector on every wait would be wasteful. If still blocked after the timeout, it runs the **deadlock detector**, which builds the wait-for graph among waiting processes and searches for a cycle. On finding one, it picks a **victim** (the transaction that triggered the check) and aborts it with `ERROR: deadlock detected` (SQLSTATE `40P01`); the others proceed.

```sql
-- Reproduce / observe:
-- session 1: BEGIN; UPDATE accounts SET ... WHERE id=1;   -- locks row 1
-- session 2: BEGIN; UPDATE accounts SET ... WHERE id=2;   -- locks row 2
-- session 1:        UPDATE accounts SET ... WHERE id=2;   -- waits on session 2
-- session 2:        UPDATE accounts SET ... WHERE id=1;   -- cycle → one aborted 40P01
SELECT pid, wait_event_type, wait_event, query FROM pg_stat_activity WHERE wait_event_type='Lock';
SELECT pg_blocking_pids(pid) FROM pg_stat_activity WHERE state='active';
```

Diagnosis in production: the server log records the full deadlock with the involved PIDs, the queries, and the locks each held/wanted (enable `log_lock_waits` to also capture long lock waits before they deadlock). The root cause is almost always **inconsistent lock acquisition order** across code paths. The fixes, in order of preference: (1) make all transactions acquire locks in a **consistent, deterministic order** (e.g., always update accounts by ascending `id`) — this eliminates cycles entirely; (2) keep transactions short to shrink the window; (3) handle `40P01` with the same **retry loop** you'd use for `40001`, since a retried transaction usually succeeds once the conflict clears; (4) reduce lock scope (e.g., `SELECT ... FOR UPDATE SKIP LOCKED` for queue patterns, or batching). Tuning `deadlock_timeout` is a trade-off: lower detects faster but burns CPU running the detector on transient waits; raise it if deadlocks are rare and you want to avoid detector overhead. The senior point is that deadlocks are an application *design* problem (lock ordering) that PostgreSQL merely *detects*, not something you tune your way out of.

#### Q69. [Theory] How do collations affect sorting and indexes, and why can a glibc/ICU upgrade silently corrupt an index?

A **collation** defines the locale-specific rules for comparing and ordering text — whether `'Z' < 'a'`, how accented characters sort, case sensitivity, and locale conventions. PostgreSQL gets these rules from a **provider**: the operating system's **libc/glibc** (the historical default, e.g., `en_US.UTF-8`), or **ICU** (International Components for Unicode), which PostgreSQL increasingly favors and which became the default provider for new databases in PG 15+. The special `C` (a.k.a. `POSIX`) collation sorts by raw byte value — fast, deterministic, version-independent, but not linguistically "correct" for human-language ordering.

The reason collation is a deep-internals topic: **B-tree indexes on text columns are physically ordered according to the collation's comparison rules at build time.** The index's on-disk key ordering *is* the collation's sort order. If the underlying collation library changes its comparison rules — which happens across **glibc upgrades** (notably the glibc 2.28 change that reordered many locales) or ICU version bumps — then the rules PostgreSQL uses at query time no longer match the order the index was built with. The index is now *silently corrupt*: lookups can miss rows that are actually present, and `UNIQUE` constraints can be violated because a "duplicate" sorts to a different position than the existing key.

```sql
-- Force byte-order collation: stable across OS upgrades, fast (enables abbreviated keys)
CREATE TABLE t (name text COLLATE "C");
-- Use ICU with explicit, versioned rules
CREATE COLLATION mycoll (provider = icu, locale = 'en-US');
-- After a glibc/ICU upgrade, rebuild affected indexes:
REINDEX INDEX CONCURRENTLY idx_name;
SELECT collname, collversion FROM pg_collation;   -- track recorded vs current version
```

PostgreSQL records a `collversion` and warns when the library's reported version differs from what an index was built with — that warning is a signal to `REINDEX`. The operational best practices: prefer **ICU collations** (versioned and consistent across OSes, unlike glibc which differs per distro), or use `COLLATE "C"` for columns that only need equality/byte-order (it's also faster and enables the **abbreviated keys** sort optimization), pin/track collation versions, and always `REINDEX` text indexes after an OS major upgrade or container base-image change. This is a famous source of subtle data-corruption incidents precisely because nothing errors loudly — the index just quietly returns wrong results.

#### Q70. [Theory] What are advisory locks, how do they differ from row/table locks, and when are they the right tool?

**Advisory locks** are application-defined heavyweight locks that PostgreSQL tracks but attaches **no meaning to** — they don't lock any actual table or row. You lock an arbitrary 64-bit integer (or a pair of 32-bit ints) of your own choosing, and the *convention* for what that number represents is entirely up to your application. They share the same lock manager and deadlock detector as regular locks but are decoupled from data, which makes them ideal for coordinating application-level work that doesn't map cleanly to locking a specific row.

The crucial distinction is **scope/lifetime**. Regular row/table locks are tied to the transaction and released automatically at commit/rollback. Advisory locks come in two flavors: **session-level** (`pg_advisory_lock`) which persist until explicitly unlocked or the session ends — *outliving transactions* — and **transaction-level** (`pg_xact_advisory_lock`) which auto-release at transaction end like normal locks. There are also non-blocking `try_` variants that return immediately rather than waiting.

```sql
-- Mutual exclusion for a singleton background job across many app instances:
SELECT pg_try_advisory_lock(hashtext('nightly-report-generator'));
-- returns true to exactly one caller; others get false and skip the job
-- ... do the work ...
SELECT pg_advisory_unlock(hashtext('nightly-report-generator'));
```

When they're the right tool: a **distributed mutex** (only one app instance runs a cron job), serializing access to an external resource, a lightweight leader-election primitive, or guarding a multi-step operation that spans several transactions (where a row lock would be released too early). The trade-offs and gotchas: (1) session-level locks **leak** if you forget to unlock or the connection is returned to a pool still holding one — with **PgBouncer transaction pooling they're especially dangerous** because the server connection (and its session locks) is shared across clients. (2) They're not visible as "business" locks, so they need disciplined naming conventions (often via `hashtext` of a meaningful string). (3) They still participate in deadlock detection, so inconsistent advisory-lock ordering can deadlock. For cross-transaction coordination they beat the alternatives (a "lock table" row you `SELECT FOR UPDATE` holds a row lock only for that transaction and bloats); for simple within-transaction needs, regular locks are clearer.

#### Q71. [Theory] How does `postgres_fdw` push work down to a remote server, and what defeats pushdown?

A **Foreign Data Wrapper (FDW)** lets PostgreSQL query external data sources as if they were local tables, implementing the SQL/MED standard. `postgres_fdw` connects to another PostgreSQL server; the local planner treats the remote tables via a **foreign scan** node. The performance everything-hinges-on concept is **pushdown**: rather than dragging every remote row across the network and filtering locally, the FDW pushes as much of the query *to the remote server* as it safely can, so the remote does the heavy lifting and returns only the needed result. `postgres_fdw` can push down `WHERE` filters, joins between two foreign tables *on the same remote server*, aggregates, sorts, and `LIMIT` — generating native SQL to run remotely.

```sql
CREATE EXTENSION postgres_fdw;
CREATE SERVER remote FOREIGN DATA WRAPPER postgres_fdw OPTIONS (host '10.0.0.5', dbname 'sales');
CREATE USER MAPPING FOR app SERVER remote OPTIONS (user 'app', password '...');
IMPORT FOREIGN SCHEMA public FROM SERVER remote INTO sales_remote;
EXPLAIN VERBOSE SELECT count(*) FROM sales_remote.orders WHERE region = 'EU';
--   look for "Remote SQL: SELECT count(*) ... WHERE region = 'EU'"  → aggregate pushed down
```

What **defeats pushdown** (the part interviewers probe): (1) a `WHERE` clause that calls a **non-`IMMUTABLE`/non-shippable function** or a **local-only operator/collation** — the planner can't guarantee the remote evaluates it identically, so it fetches all rows and filters locally (the classic "why is my FDW query reading the whole remote table?" surprise). (2) **Joining a foreign table to a local table** — there's no remote counterpart for the local rows, so the join happens locally after fetching. (3) **Joins across two *different* foreign servers** can't be pushed as one remote query. (4) Stale remote statistics: `postgres_fdw` relies on `use_remote_estimate` (off by default) or local stats from `ANALYZE` on the foreign table; without good estimates the planner mis-costs the foreign scan. Tuning levers include `use_remote_estimate = true` (ask the remote to plan/cost, more accurate but adds round-trips), `fetch_size` (rows per network round-trip), and ensuring functions used in predicates are shippable. The senior point: FDW performance is *all about* what gets pushed down, and you confirm it with `EXPLAIN VERBOSE` reading the generated "Remote SQL."

#### Q72. [Theory] What are subtransactions and savepoints, and what are their hidden performance costs?

A **savepoint** marks a point within a transaction you can roll back to without aborting the whole transaction; under the hood each savepoint (and each PL/pgSQL `BEGIN ... EXCEPTION WHEN ... END` block, and each statement in some drivers' autosave modes) creates a **subtransaction** with its own subtransaction ID (subxid). Subtransactions let part of a transaction fail and be undone while the parent continues. The parent transaction only commits/aborts as a unit at the top level; subtransaction commit just makes its changes visible to the parent, and subtransaction abort discards them.

```sql
BEGIN;
  INSERT INTO ledger ...;
  SAVEPOINT sp1;
  INSERT INTO audit ...;      -- if this fails:
  ROLLBACK TO SAVEPOINT sp1;  -- undo only the audit insert, keep the ledger insert
COMMIT;
```

The hidden cost — a frequent staff-level "gotcha" — is the **`pg_subtrans` SLRU and the 64-subxid cache limit**. Each backend caches up to **64** subxids per transaction directly in shared memory (`PGPROC`). Below that, visibility checks for subtransactions are cheap. Once a single transaction exceeds 64 subtransactions, it **overflows**, and subsequent visibility determinations for *any* transaction must consult the `pg_subtrans` SLRU on disk to map subxids to their parent — and as discussed in the SLRU question, that cache is small and serially locked. The result is the notorious **"subtransaction SLRU overflow"** performance cliff: a workload that wraps every statement in a savepoint (common with ORMs that enable per-statement savepoints, or PL/pgSQL with many exception blocks in a loop) can suddenly degrade cluster-wide under concurrency, with `SubtransSLRU` wait events, even though no individual query looks expensive.

The reasoning to convey: subtransactions are semantically valuable but not free — they consume XIDs (each subxid is a real transaction ID, contributing to wraparound pressure), they pin visibility state, and crossing the 64-per-transaction threshold flips you from in-memory to SLRU lookups. Mitigations: avoid gratuitous savepoints (turn off ORM "autosave" / per-statement savepoint modes), restructure tight loops so the `EXCEPTION` block isn't entered per-iteration when avoidable, keep transactions short, and on PG 17 enlarge the subtransaction SLRU buffers if the workload genuinely needs many subtransactions. Recognizing that an innocuous-looking ORM setting can cause a cluster-wide cliff is the depth expected.

#### Q73. [Theory] What do the different `synchronous_commit` levels mean, and how do they trade durability against latency?

`synchronous_commit` controls **how much guarantee a `COMMIT` waits for before returning success to the client** — it is the single most important knob trading durability for write latency, and the levels are more nuanced than "on/off." The fundamental tension: a fully durable commit must flush WAL to stable storage locally (and, with replicas, confirm remote receipt), each of which costs a round-trip or fsync; relaxing those waits cuts latency but opens a window where an acknowledged commit can be lost on crash.

```
Level         | Local WAL flushed? | Standby confirms? | Loss window on crash
--------------+--------------------+-------------------+----------------------------
off           | not waited on      | no                | last ~3× wal_writer_delay of commits
local         | yes (fsync local)  | no                | none locally; standby may lag
on (default)  | yes                | yes, FLUSHED      | none (with sync standby) — strongest
remote_write  | yes                | yes, WRITTEN (OS) | standby OS crash can lose it
remote_apply  | yes                | yes, REPLAYED     | none + read-your-writes on standby
```

The crucial subtlety in `synchronous_commit = off`: it does **not** risk database corruption or partial transactions — atomicity is preserved because WAL ordering is intact; it only means a *committed* transaction might **vanish entirely** if the server crashes before the WAL writer flushes it (a window of roughly `3 × wal_writer_delay`). So it trades a small **durability** window for a large throughput gain, and is perfectly acceptable for data you can regenerate (analytics ingest, caches, click logs) but never for payments or ledgers. With replication, the levels above `local` define *what the synchronous standby must confirm*: `remote_write` (standby OS has the bytes), `on`/`remote_flush` (standby fsynced — survives standby crash, RPO=0), and `remote_apply` (standby has *replayed* it, so a read on the standby is guaranteed to see the commit — the only level giving cross-node read-your-writes, at the highest latency).

The powerful operational point is that `synchronous_commit` is **per-transaction settable**, not just a global. You can run the cluster at the strong default `on` but mark specific low-value, high-volume transactions `SET LOCAL synchronous_commit = off` to get their throughput, while your financial transactions keep full durability — best of both worlds. Likewise `remote_apply` can be set only on the rare transaction that immediately reads from a replica. The trade-off framing to deliver: every step toward stronger durability adds a flush or a network round-trip to commit latency; the right answer is workload-specific and can be tuned at transaction granularity rather than forced cluster-wide.

## 🧩 Extended Questions — Set 2: Practical, Operational & Troubleshooting

### 🟢 Basic — extended

#### Q74. [Practical] What is the difference between `pg_dump`, `pg_dumpall`, and `pg_basebackup`, and when do you use each?

These three tools sit at different layers of the backup stack and are not interchangeable. **`pg_dump`** produces a *logical* backup of a **single database** — a script (or custom/directory archive) of SQL statements that recreate the schema and data. It is consistent (it runs in a single repeatable-read snapshot), portable across major versions and architectures, and selective (you can dump one table, schema-only, or data-only). **`pg_dumpall`** wraps `pg_dump` to cover the *whole cluster* plus the **global objects** that `pg_dump` skips — roles, tablespaces, and grants — which is why a `pg_dump`-only backup that forgets `pg_dumpall --globals-only` restores tables but leaves you with no users. **`pg_basebackup`** is a *physical* backup: a byte-for-byte copy of the entire data directory, used to seed streaming replicas and as the base image for Point-In-Time Recovery.

```bash
pg_dump -Fc -d app -f app.dump            # custom-format, compressed, parallel-restorable
pg_dumpall --globals-only -f globals.sql  # roles + tablespaces (logical backup companion)
pg_basebackup -D /backup/base -X stream -c fast -P  # physical base for replica/PITR
```

The decision rule: use **`pg_dump`** for migrations, version upgrades, selective restores, and moving data between environments; use **`pg_basebackup` + WAL archiving** for production disaster recovery where you need fast restore of a large cluster and the ability to recover to an arbitrary point in time. The crucial trade-off is restore speed and granularity versus size: a logical dump of a 2 TB database might take many hours to restore (it rebuilds indexes from scratch), whereas a physical base backup restores at disk-copy speed but can only restore the *whole* cluster at the same major version on a compatible architecture. Mature shops layer both — physical for RPO/RTO, periodic logical dumps for portability and per-object recovery.

#### Q75. [Practical] How do you check the current activity on a database, and how do you cancel or kill a problematic query?

The single most-used operational view is **`pg_stat_activity`** — one row per backend (connection), showing `pid`, `state` (`active`, `idle`, `idle in transaction`), `query`, `query_start`, `wait_event_type`/`wait_event`, and the client address. The first thing you reach for in an incident is "what is running right now and for how long":

```sql
SELECT pid, usename, state,
       now() - query_start AS run_time,
       wait_event_type, wait_event, left(query, 80) AS query
FROM pg_stat_activity
WHERE state <> 'idle'
ORDER BY run_time DESC;
```

To stop a query there are two functions with very different semantics. **`pg_cancel_backend(pid)`** sends a *cancel* signal — it aborts the *current query* but leaves the connection and transaction intact (the client gets an error and can continue). **`pg_terminate_backend(pid)`** is the heavier hammer — it kills the *entire backend connection*, rolling back its transaction and disconnecting the client. The operational rule is **always try cancel first**; terminate only when cancel doesn't work (e.g., a backend stuck in an uninterruptible state) because terminating drops the connection, which can disrupt connection-pool accounting.

```sql
SELECT pg_cancel_backend(12345);     -- polite: cancel current query
SELECT pg_terminate_backend(12345);  -- forceful: kill the whole connection
```

A frequent senior nuance: a query showing `state = 'active'` with a `Lock` wait event isn't slow on its own — it's *blocked*. Use `pg_blocking_pids(pid)` to find the culprit holding the lock and cancel *that* one instead, since killing the victim just lets the next queued query block on the same lock.

#### Q76. [Practical] What is "idle in transaction" and why is it dangerous in production?

`idle in transaction` is a backend state where a client has issued `BEGIN` (or run a statement that implicitly started a transaction) but is now sitting idle — not running a query — without having committed or rolled back. It usually comes from application bugs: a connection borrowed from a pool that ran a query, then went off to call an external API or wait on application logic while holding the transaction open, or an ORM that forgot to commit. It looks harmless because no query is running, but it is one of the most damaging things you can do to a PostgreSQL cluster.

The danger is twofold. First, an open transaction **holds its snapshot**, which **pins the global xmin horizon** — autovacuum cannot remove any dead tuple newer than that transaction's snapshot, *anywhere in the database*. A single forgotten `idle in transaction` connection left open for hours can cause cluster-wide bloat and, in the extreme, block the freezing that prevents XID wraparound. Second, if the transaction took any **locks** (even an implicit `ACCESS SHARE` from a `SELECT`, or a row lock from `UPDATE`), those locks are held until the transaction ends, blocking DDL and conflicting writers indefinitely.

```sql
-- Find offenders
SELECT pid, now() - state_change AS idle_for, query
FROM pg_stat_activity
WHERE state = 'idle in transaction'
ORDER BY idle_for DESC;
```

The fix is layered: set **`idle_in_transaction_session_timeout`** (e.g., `'5min'`) so PostgreSQL automatically terminates connections idling in a transaction too long; fix the application to commit/rollback promptly and never do network I/O inside a transaction; and monitor/alert on `idle in transaction` duration. This setting is one of the highest-value safety knobs in a production config.

#### Q77. [Practical] How do you configure `pg_hba.conf`, and how would you debug a "connection refused" or "no pg_hba.conf entry" error?

`pg_hba.conf` (Host-Based Authentication) is the gatekeeper file that decides *who* can connect, *from where*, to *which database*, as *which user*, and with *what authentication method*. It is evaluated **top to bottom, first match wins** — order matters, and a broad early rule can shadow a specific later one. Each line is `TYPE DATABASE USER ADDRESS METHOD`:

```
# TYPE  DATABASE   USER     ADDRESS          METHOD
local   all        all                       scram-sha-256
host    app        appuser  10.0.0.0/24      scram-sha-256
hostssl all        all      0.0.0.0/0        scram-sha-256   # require TLS from anywhere
host    all        all      0.0.0.0/0        reject          # explicit deny catch-all
```

Two distinct failures get confused. **"Connection refused"** is a *network/listener* problem — PostgreSQL isn't accepting connections on that address/port at all. Check `listen_addresses` in `postgresql.conf` (default `localhost` only — must be `'*'` or a specific IP to accept remote connections), the port, and firewall/security-group rules; `pg_hba.conf` isn't even reached yet. **"no pg_hba.conf entry for host ..., user ..., database ..."** means the TCP connection *succeeded* but no rule matched — this is purely a `pg_hba.conf` problem.

The debugging workflow: (1) confirm `listen_addresses` and that the server is reachable (`telnet`/`nc` to the port). (2) Read the exact error — it tells you the host, user, database, and whether SSL was used, which is precisely what you match against the rules. (3) Add or fix the matching `host`/`hostssl` line, remembering first-match-wins ordering. (4) **Reload, don't restart** — `pg_hba.conf` changes apply with `SELECT pg_reload_conf();` or `pg_ctl reload` (no downtime); only some `postgresql.conf` parameters need a restart. A common gotcha is editing the file but forgetting to reload, then concluding the syntax is wrong. Use `pg_hba_file_rules` view (a system view) to see the parsed rules and spot syntax errors without trial and error.

### 🟡 Intermediate — extended

#### Q78. [Practical] How do you set up Point-In-Time Recovery (PITR), and how do you restore to a specific moment after an accidental `DELETE`?

PITR lets you recover the cluster to *any instant* between a base backup and the present, which is the answer to "someone ran `DELETE FROM orders` without a `WHERE` at 14:32 — get us back to 14:31." It rests on two ingredients: a **physical base backup** and a **continuous archive of WAL** segments. You enable archiving so every completed WAL segment is copied to durable storage, then take a base backup as the starting point:

```ini
# postgresql.conf
wal_level = replica
archive_mode = on
archive_command = 'test ! -f /archive/%f && cp %p /archive/%f'   # or push to S3/gcs
```

```bash
pg_basebackup -D /backup/base -X none -c fast   # take the base; WAL comes from the archive
```

To **restore to 14:31**, you stop the cluster, restore the base backup into the data directory, then tell PostgreSQL where to fetch archived WAL and how far to replay. Recovery target syntax moved into `postgresql.conf` plus a signal file in PG 12+ (the old `recovery.conf` is gone):

```ini
# postgresql.conf (or postgresql.auto.conf), then create the signal file
restore_command   = 'cp /archive/%f %p'
recovery_target_time = '2026-06-16 14:31:00+00'
recovery_target_action = 'promote'
```

```bash
touch /data/recovery.signal      # PG 12+: presence of this file triggers recovery mode
pg_ctl start                     # replays WAL up to the target, then promotes
```

PostgreSQL replays WAL from the base backup forward, stopping at the first transaction *after* the target time, and promotes to a new timeline. The crucial operational details: targets can be a **time**, an **LSN**, a **named restore point** (`pg_create_restore_point('before-migration')` — invaluable to set before risky operations), a **transaction ID**, or `recovery_target = 'immediate'` (stop as soon as consistency is reached). Always restore to a **separate instance first** to extract the lost rows rather than overwriting production, because recovery is one-way and forks a new timeline. In practice most teams use a backup manager — **pgBackRest** or **WAL-G** — which handle compression, parallelism, retention, and S3 archiving far more robustly than a hand-rolled `archive_command`.

#### Q79. [Practical] Your `SELECT count(*)` on a large table is slow. Why, and what are the alternatives?

This surprises people coming from MySQL/MyISAM: in PostgreSQL `SELECT count(*) FROM big_table` with no `WHERE` is **O(n)** — it must scan every row (or at least every entry of a suitable index for an index-only scan) because MVCC means **there is no single authoritative row count**. Different transactions see different numbers of visible rows depending on their snapshot, so PostgreSQL cannot keep one cached counter; it has to count the tuples visible to *your* snapshot at query time. On a billion-row table that is a multi-second-to-minute full scan.

The alternatives depend on whether you need an *exact* or *approximate* count. For an **approximate** count (good enough for "showing ~12,400,000 records" or pagination UIs), read the planner's estimate, which is essentially free:

```sql
-- Fast approximate count from planner statistics (updated by ANALYZE/autovacuum)
SELECT reltuples::bigint AS approx_rows
FROM pg_class WHERE relname = 'big_table';

-- Or from a filtered query's plan estimate:
EXPLAIN (FORMAT JSON) SELECT * FROM big_table WHERE status = 'active';
```

For an **exact, frequently-read** count, the right pattern is to **maintain a counter** — a summary row updated by triggers on insert/delete, or a periodically-refreshed materialized aggregate. Be careful: a single global counter row updated on every insert becomes a write hotspot and serialization point, so high-throughput designs shard the counter into N rows and sum them, or use an eventually-consistent rollup. If you need exact counts only occasionally, an **index-only scan** (`count(*)` over a small covering index on an all-visible table) is faster than a heap scan but still O(n). The interview point is recognizing *why* it's slow (MVCC, no cached count) and choosing approximate-from-stats versus maintained-counter based on the accuracy requirement.

#### Q80. [Coding] Implement efficient pagination over a large result set. Why is `OFFSET` an anti-pattern?

The naive approach, `ORDER BY created_at LIMIT 20 OFFSET 100000`, is an anti-pattern because PostgreSQL must **generate and discard** all 100,000 preceding rows before returning your 20 — `OFFSET` doesn't skip work, it does the work and throws it away. So page 1 is fast and page 5,000 is glacial, and worse, if rows are inserted/deleted between page loads the offset shifts and users see duplicated or skipped rows. The cost grows linearly with the page depth, which is exactly backwards for "infinite scroll" UIs where deep pages are common.

The fix is **keyset pagination** (a.k.a. seek method or cursor pagination): instead of "skip N rows," remember the sort key of the **last row seen** and ask for rows *after* it. Backed by an index on the sort key, every page costs the same O(log n) — a single index seek to the boundary, then a sequential read of the page.

```sql
-- First page
SELECT id, created_at, title FROM posts
ORDER BY created_at DESC, id DESC
LIMIT 20;

-- Next page: pass the last row's (created_at, id) as the cursor
SELECT id, created_at, title FROM posts
WHERE (created_at, id) < (:last_created_at, :last_id)   -- row-value comparison
ORDER BY created_at DESC, id DESC
LIMIT 20;
```

```java
// Cursor is the last (created_at, id) tuple, base64-encoded for the client
String sql = """
    SELECT id, created_at, title FROM posts
    WHERE (created_at, id) < (?, ?)
    ORDER BY created_at DESC, id DESC
    LIMIT ?
    """;
try (PreparedStatement ps = conn.prepareStatement(sql)) {
    ps.setTimestamp(1, lastCreatedAt);
    ps.setLong(2, lastId);
    ps.setInt(3, pageSize);
    // ... read rows; the last row's (created_at, id) becomes the next cursor
}
```

Two design notes that show depth: (1) the sort key **must be unique** (or made unique with a tiebreaker like `id`), otherwise rows sharing the same `created_at` straddle a page boundary and get duplicated or lost — hence the composite `(created_at, id)` and the **row-value comparison** `(a, b) < (?, ?)`, which PostgreSQL can satisfy with a single composite-index range scan. (2) Keyset pagination can't jump to "page 500" directly (it's inherently forward/backward from a cursor), which is fine for feeds/APIs but not for "go to page N" UIs — there, cap the offset or use approximate page boundaries. The trade-off is jump-ability for constant-time scaling.

#### Q81. [Practical] You see "FATAL: sorry, too many clients already." Diagnose and fix it.

This error means active connections have hit **`max_connections`** and PostgreSQL is refusing new ones (it actually reserves a few slots for superusers via `superuser_reserved_connections`, so regular users hit the wall slightly earlier). It is almost never solved by simply raising `max_connections` — that treats the symptom and often makes things worse, because each connection is a full backend process consuming memory and the server can thrash under thousands of them.

The diagnosis is to find *where the connections are going* and *what state they're in*:

```sql
SELECT state, count(*) FROM pg_stat_activity GROUP BY state ORDER BY 2 DESC;
SELECT usename, application_name, count(*) FROM pg_stat_activity GROUP BY 1,2 ORDER BY 3 DESC;
SHOW max_connections;
```

The usual root causes and fixes: (1) **No connection pooler** — application instances each open their own pool, fanning out to thousands of direct connections. Fix: put **PgBouncer** (transaction mode) in front and set the app pools small; PostgreSQL serves a few hundred real connections behind it. (2) **Connection leaks** — code that opens connections without returning them (missing `close()`, exceptions bypassing pool return). The `pg_stat_activity` query shows them piling up as `idle`. (3) **`idle in transaction`** connections held open by application bugs, not freed back to the pool — fix the code and set `idle_in_transaction_session_timeout`. (4) **Pool sizing math** wrong — total app pool size across all instances exceeds DB capacity.

The principle to articulate: PostgreSQL's process-per-connection model means the *right* `max_connections` is modest (often 100–300 even on big servers), and the fan-out is absorbed by a pooler, not by the database. Raising `max_connections` to mask a leak or a missing pooler buys a little time and then collapses harder (memory pressure, context-switch overhead, slower snapshot building). The senior move is fixing the connection topology, not the number.

#### Q82. [Practical] How do you diagnose replication lag on a streaming standby, and what causes it?

Replication lag is the delay between a change committing on the primary and that change being received, written, flushed, and applied on a standby. You measure it from both ends. On the **primary**, `pg_stat_replication` shows per-standby LSNs and lag timers; on the **standby**, you compare the last received vs replayed LSN, or compute time lag against the primary's clock:

```sql
-- On the primary: byte lag and time lag per connected standby
SELECT application_name, state,
       pg_wal_lsn_diff(pg_current_wal_lsn(), replay_lsn) AS replay_lag_bytes,
       write_lag, flush_lag, replay_lag
FROM pg_stat_replication;

-- On the standby: time the replayed data is behind the primary
SELECT now() - pg_last_xact_replay_timestamp() AS replay_delay;
```

Lag has three sub-stages — **write** (WAL received and written to the standby's OS), **flush** (fsynced), and **replay** (actually applied to data files) — and `pg_stat_replication` exposes each as a separate timer, which is diagnostic: high *write/flush* lag points at **network or standby disk I/O** limits, while high *replay* lag with low write lag points at the **standby being CPU/IO-bound on replay** or, very commonly, **replay being blocked by a conflicting query** on the standby (a long read query holds a snapshot, and `max_standby_streaming_delay` pauses replay rather than cancel the query).

The common causes and levers: (1) a **burst of writes** on the primary (bulk load, mass update) outruns the standby's single-threaded replay — mitigate by batching writes and avoiding giant transactions. (2) **Long-running queries on a hot-standby** conflict with replay; tune `max_standby_streaming_delay` (let replay wait vs cancel queries) or enable `hot_standby_feedback = on` (standby tells primary to hold back vacuum, preventing query cancellations at the cost of some primary bloat). (3) **Network saturation** between sites. (4) A **slow disk** on the standby. The operational must-do is *alerting on lag* (both bytes and seconds) because a silently lagging standby is a hidden RPO/read-consistency hole — and if you read from replicas, lag means stale reads.

#### Q83. [Practical] Walk through a methodology for finding and fixing a missing index in production.

The disciplined approach starts from *evidence*, not guessing. **Step 1 — find the expensive statements** with `pg_stat_statements`, sorted by `total_exec_time` (cumulative pain), not mean time. **Step 2 — get the plan** for the worst offenders with `EXPLAIN (ANALYZE, BUFFERS)` using representative parameter values, and look for the tells: a `Seq Scan` on a large table with a selective `WHERE`, a high `Rows Removed by Filter`, or a nested loop with a huge `loops` count from a non-indexed join column. **Step 3 — confirm selectivity**: an index only helps if the predicate is selective (returns a small fraction of rows); a `Seq Scan` returning 60% of the table is *correctly* chosen, and an index there would be ignored or counterproductive.

```sql
-- Tables doing lots of sequential scanning relative to index scans (candidates)
SELECT relname, seq_scan, idx_scan, seq_tup_read,
       seq_tup_read / NULLIF(seq_scan, 0) AS avg_rows_per_seq_scan
FROM pg_stat_user_tables
WHERE seq_scan > 0
ORDER BY seq_tup_read DESC LIMIT 20;
```

**Step 4 — create the index `CONCURRENTLY`** so you don't lock writes, choosing the column order to match the query (equality columns first, then the range/sort column), and prefer **partial** indexes for skewed predicates or **covering** (`INCLUDE`) indexes to enable index-only scans. **Step 5 — verify** by re-running `EXPLAIN ANALYZE` and confirming the plan switched and timing dropped; **Step 6 — check for redundancy** so you don't add an index already covered by a leading-column prefix of an existing composite index.

```sql
CREATE INDEX CONCURRENTLY idx_orders_cust_status
  ON orders (customer_id, status) WHERE status <> 'archived';

-- Later: find unused indexes wasting write overhead and space
SELECT relname, indexrelname, idx_scan
FROM pg_stat_user_indexes WHERE idx_scan = 0 ORDER BY pg_relation_size(indexrelid) DESC;
```

The trade-off framing that signals seniority: every index **speeds reads but taxes writes** (each `INSERT`/`UPDATE` of an indexed column maintains it) and consumes space and cache. So the goal isn't "add indexes," it's "add the *minimum* set that covers the hot query patterns," then *remove* unused indexes (zero `idx_scan` over a representative window) which are pure write overhead. Avoid building indexes blindly from a tool's suggestions — validate each against a real plan, because a poorly-chosen multicolumn order or an index on a low-selectivity column gives the write cost with none of the read benefit.

#### Q84. [Coding] Write a bulk-load routine using `COPY` and explain why it crushes row-by-row `INSERT`.

`COPY` is PostgreSQL's bulk-loading firehose: it streams rows in a single command over the protocol, bypassing the per-statement parse/plan/execute overhead, WAL-logging more efficiently, and avoiding a network round-trip per row. Loading a million rows with individual `INSERT` statements can be **10–50× slower** than `COPY` because each `INSERT` pays statement overhead, a network round-trip, and (without batching) its own transaction commit/fsync. The JDBC driver exposes `COPY` via the `CopyManager` API:

```java
import org.postgresql.copy.CopyManager;
import org.postgresql.core.BaseConnection;

public long bulkLoad(Connection conn, Reader csvData) throws SQLException, IOException {
    CopyManager cm = new CopyManager(conn.unwrap(BaseConnection.class));
    return cm.copyIn(
        "COPY events (ts, user_id, payload) FROM STDIN WITH (FORMAT csv)",
        csvData);                           // returns rows loaded
}
```

```sql
-- psql / server-side equivalent
COPY events (ts, user_id, payload) FROM '/data/events.csv' WITH (FORMAT csv, HEADER);
\copy events FROM 'events.csv' WITH (FORMAT csv, HEADER)   -- client-side variant
```

Why it wins and how to make it win more: (1) **One command, one parse, streamed data** — no per-row overhead. (2) For the *fastest* loads, drop or defer secondary indexes and foreign keys, load with `COPY`, then rebuild indexes once at the end (building an index in bulk is far cheaper than maintaining it per row). (3) Load into a table with `wal_level` appropriately and, when the table was created/truncated in the *same transaction*, PostgreSQL can skip WAL for the load (`COPY` into a fresh table is minimally logged), a big speedup for ETL. (4) Raise `maintenance_work_mem` so the post-load index builds are fast. **Edge cases:** `COPY` is all-or-nothing per statement (an error aborts the whole copy unless you stage into an unlogged/temp table first and validate); it doesn't run row triggers the same way as `INSERT` by default; and for *upsert* semantics you `COPY` into a staging table then `INSERT ... ON CONFLICT ... SELECT FROM staging`. **Complexity:** O(n) with a tiny constant versus O(n) with a large per-row constant for `INSERT`.

#### Q85. [Practical] When and how would you use a materialized view, and how do you refresh it without blocking readers?

A regular **view** is just a stored query — every time you select from it, the underlying query runs. A **materialized view** physically *stores the computed result* on disk, so reads are as fast as reading a table, at the cost of the data being a **snapshot** that goes stale until you refresh it. The right use case is an expensive aggregation or join that is **read far more often than the underlying data changes** and where slight staleness is acceptable: dashboards, leaderboards, daily rollups, denormalized search/reporting tables, precomputed analytics.

```sql
CREATE MATERIALIZED VIEW daily_sales AS
SELECT date_trunc('day', created_at) AS day, region, sum(total) AS revenue
FROM orders GROUP BY 1, 2
WITH DATA;
```

The refresh gotcha is the heart of the question. A plain `REFRESH MATERIALIZED VIEW daily_sales` takes an **`ACCESS EXCLUSIVE` lock** for the entire recompute — readers are *blocked* the whole time, which is unacceptable for a view people query constantly. The fix is `REFRESH MATERIALIZED VIEW **CONCURRENTLY**`, which recomputes into a new copy and then diffs it in, letting readers keep querying the old data throughout (it takes only a brief lock at swap time):

```sql
-- Concurrent refresh REQUIRES a unique index on the materialized view
CREATE UNIQUE INDEX ON daily_sales (day, region);
REFRESH MATERIALIZED VIEW CONCURRENTLY daily_sales;   -- readers not blocked
```

The trade-offs to articulate: `CONCURRENTLY` **requires a unique index** on the view (so it can match old and new rows for the diff), is **slower** than a plain refresh (it computes the diff), and still does the full recompute each time — there is **no incremental refresh** built into core PostgreSQL (you'd reach for the `pg_ivm` extension or hand-rolled trigger-maintained summary tables for true incremental updates). Operationally you schedule refreshes (cron/pg_cron) at a cadence matching acceptable staleness, and for very large views consider partitioning the underlying data and maintaining per-partition rollups instead of one monolithic refresh. The senior point: materialized views trade freshness for read speed, `CONCURRENTLY` trades refresh cost for non-blocking reads, and neither is incremental in core PG.

### 🟠 Advanced — extended

#### Q86. [Practical] You must change a column's type on a 500M-row table with zero downtime. Walk through the safe procedure.

A naive `ALTER TABLE ... ALTER COLUMN amount TYPE numeric(18,2)` triggers a **full table rewrite** under an `ACCESS EXCLUSIVE` lock — every row copied, every index rebuilt, the table unavailable for the duration (potentially hours), and all queries queue behind it. On a 500M-row production table that is an outage. The zero-downtime technique is the **expand-and-contract (dual-write) migration**: add a new column, backfill it in batches, keep it in sync, then swap, never holding a long lock.

```sql
-- 1. Add the new column: instant (nullable, no default) since it's metadata-only
ALTER TABLE payments ADD COLUMN amount_new numeric(18,2);

-- 2. Keep it in sync going forward with a trigger (so new/updated rows populate it)
CREATE FUNCTION sync_amount() RETURNS trigger AS $$
BEGIN NEW.amount_new := NEW.amount::numeric(18,2); RETURN NEW; END $$ LANGUAGE plpgsql;
CREATE TRIGGER trg_sync_amount BEFORE INSERT OR UPDATE ON payments
  FOR EACH ROW EXECUTE FUNCTION sync_amount();
```

```sql
-- 3. Backfill existing rows in small batches (short transactions, lets autovacuum keep up)
UPDATE payments SET amount_new = amount::numeric(18,2)
WHERE amount_new IS NULL AND id BETWEEN :lo AND :hi;   -- loop over id ranges
```

Once the backfill is complete and verified, you do the **cutover**: in a single short transaction (guarded by a tight `lock_timeout`), drop the trigger, drop the old column, and rename the new one — all metadata operations that are instant. Build any needed indexes on the new column with `CREATE INDEX CONCURRENTLY` *before* the cutover so the swap doesn't leave you index-less.

```sql
SET lock_timeout = '2s';   -- fail fast rather than queue behind a long query
BEGIN;
  DROP TRIGGER trg_sync_amount ON payments;
  ALTER TABLE payments DROP COLUMN amount;
  ALTER TABLE payments RENAME COLUMN amount_new TO amount;
COMMIT;
```

The reasoning to convey: the only locks ever held are *brief metadata locks*, never a multi-hour rewrite; the application keeps reading/writing throughout; and the tight `lock_timeout` ensures the cutover either grabs its momentary lock or fails cleanly (you retry) rather than stalling all traffic. If the *application* must also switch from reading `amount` to `amount_new`, you sequence deploys so the app reads both/either during the transition (read-new-fall-back-to-old) before the final rename. This expand/contract pattern — add, dual-write, backfill, verify, swap, clean up — is the canonical zero-downtime schema-change playbook, and tools like `pg-osc`, `pgroll`, or migration frameworks (Rails `strong_migrations`, Flyway with care) encode it.

#### Q87. [Practical] An OLTP query intermittently gets a terrible plan — fast 95% of the time, then suddenly a nested loop over millions of rows. Diagnose.

This "sometimes fast, sometimes catastrophic" pattern for the *same query text* almost always traces to one of three plan-stability problems, and the diagnosis is to capture the *bad* plan when it happens (log it via `auto_explain` with `log_min_duration` so the slow executions dump their actual plan). **Cause 1 — generic vs custom plan flip (parameterized queries):** after the sixth execution PostgreSQL may switch a prepared statement to a **generic plan** that assumes average selectivity; if the column is skewed (e.g., `status` is 99% `'done'`), a generic plan that's fine for the common value is disastrous for a rare value, or vice-versa. The tell is that the slow runs share a parameter value that's far from average. Fix: `SET plan_cache_mode = force_custom_plan` for that query, or restructure so selective values get re-planned.

**Cause 2 — stale or insufficient statistics on correlated columns:** the planner assumes column independence, so `WHERE country = 'US' AND state = 'CA'` is estimated as the product of two selectivities, badly underestimating the real (correlated) row count and picking a nested loop expecting "a few" rows. Fix: `CREATE STATISTICS` (extended statistics) to teach the planner the dependency, and raise `default_statistics_target` on skewed columns. **Cause 3 — a moving boundary on time/sequence columns:** a predicate like `WHERE created_at > now() - interval '1 hour'` sits at the edge of the histogram where stats are sparse and stale between `ANALYZE` runs, so the estimate swings wildly as data grows; more frequent `ANALYZE` (or autovacuum analyze tuning) on the hot table stabilizes it.

```sql
-- Capture the actual bad plan in the logs without manual reproduction
-- postgresql.conf: shared_preload_libraries='auto_explain'
SET auto_explain.log_min_duration = '500ms';
SET auto_explain.log_analyze = on;        -- logs real rows vs estimates for slow runs

-- Teach the planner about correlated columns
CREATE STATISTICS s_country_state (dependencies) ON country, state FROM customers;
ANALYZE customers;
```

The methodology to articulate: don't theorize from the *fast* plan — instrument to capture the *slow* one, then compare estimated vs actual rows at each node (a 1000× underestimate feeding a nested loop is the signature). The fix is almost never a query rewrite alone; it's giving the planner better information (extended statistics, higher stats target, fresher `ANALYZE`) or removing plan instability (`force_custom_plan`). Knowing the *three* distinct mechanisms — generic-plan switchover, cross-column correlation, and edge-of-histogram time predicates — and how to tell them apart is exactly the depth a strong interviewer is probing.

#### Q88. [Practical] How would you migrate a 5 TB database to a new major version with minimal downtime?

There are three families of approach, and the right one depends on your downtime budget and risk tolerance. **(1) `pg_upgrade` with hard links** is the simplest: it relinks the data files in place to the new version's catalog format, so it doesn't copy the 5 TB — it's fast (minutes to low hours) but requires the database to be **down** for the whole operation and runs in place, so a failed upgrade is scary without a tested rollback. You mitigate by snapshotting the volume first and running `pg_upgrade --check` ahead of time. This is the default for maintenance windows where an hour of downtime is acceptable.

**(2) Logical replication** is the near-zero-downtime path. You stand up a fresh cluster on the *new* major version, create a publication on the old primary and a subscription on the new one; logical replication copies the initial snapshot then streams ongoing changes, so the new cluster catches up while the old one keeps serving. When lag is near zero you do a brief **cutover**: stop writes, let the last changes drain, repoint the application, and promote the new cluster. Downtime is just the cutover (seconds to a minute). The catches are the well-known logical-replication limitations: **DDL isn't replicated** (freeze schema changes during migration), **sequences aren't synced** (you must advance them on the target at cutover — `pg_sequence_last_value`/manual `setval`), large objects and some types need attention, and every table needs a primary key or `REPLICA IDENTITY`.

```sql
-- On old (publisher):
CREATE PUBLICATION mig FOR ALL TABLES;
-- On new cluster (subscriber, new major version):
CREATE SUBSCRIPTION mig CONNECTION 'host=old dbname=app' PUBLICATION mig;
-- At cutover: verify lag ~0, stop app writes, then fix sequences on the target:
SELECT setval('orders_id_seq', (SELECT last_value FROM old.orders_id_seq));
```

**(3) Blue/green with replication + reverse safety net** is the production-grade version of (2): after cutover you optionally set up logical replication *back* from new→old so you can fail back if the new version misbehaves. **The plan I'd actually run:** test `pg_upgrade --check` and the full logical-replication path in staging against a copy; if the maintenance window allows ~1 hour, `pg_upgrade` is simplest; if downtime must be seconds, use logical replication with a rehearsed cutover runbook (freeze DDL, drain lag, swap connection string via the pooler, fix sequences, smoke-test, keep the old cluster warm for fast rollback). Whichever path, run `ANALYZE` on the new cluster immediately after cutover — fresh clusters have no statistics and will pick terrible plans until analyzed, a classic "we migrated and everything got slow" post-cutover incident.

#### Q89. [Practical] A foreign key is making `DELETE`s on the parent table extremely slow. What's happening and how do you fix it?

The classic trap: PostgreSQL **automatically creates an index on the primary-key/unique side** of a foreign key (the *referenced* column), but it does **not** create an index on the **referencing** (child) column. So when you `DELETE` or `UPDATE` a parent row, PostgreSQL must verify no child rows still reference it (or cascade the action), and with no index on the child's FK column that check is a **full sequential scan of the entire child table — for every parent row deleted**. Deleting 1,000 parents from a table with a 50M-row child does 1,000 sequential scans of 50M rows. The same penalty hits `ON DELETE CASCADE` and `ON UPDATE CASCADE`.

The fix is simply to **index the referencing column(s)** on the child table — built `CONCURRENTLY` to avoid locking:

```sql
-- Child table referencing parent; the FK column needs its own index
CREATE INDEX CONCURRENTLY idx_orders_customer_id ON orders (customer_id);
-- Now the referential-integrity check on parent DELETE is an index lookup, not a seq scan
```

You can *find* all the unindexed foreign keys proactively rather than discovering them during an incident — a query joining `pg_constraint` (contype `'f'`) against `pg_index` to find FK columns with no matching index is a standard health check, and most monitoring tools ship it. The trade-offs and nuances to mention: (1) indexing every FK column has a write/space cost, so on tables you *never* delete-from or cascade-update you might skip it — but the safe default is to index FK columns. (2) The index column order must have the FK column(s) as a **leftmost prefix** to be usable for the RI check. (3) Beyond the missing index, large cascading deletes also generate huge amounts of WAL and bloat both tables, so for mass purges prefer batched deletes or partition `DROP` (as covered for the time-series case). The headline insight an interviewer wants: PostgreSQL indexes the *referenced* side automatically but **never the referencing side**, and that omission is one of the most common causes of mysteriously slow deletes.

#### Q90. [Practical] How do you diagnose a query that spills to disk (temp files), and what do you tune?

When a sort, hash, or `GROUP BY` needs more memory than its `work_mem` allowance, PostgreSQL doesn't fail — it **spills to temporary files on disk** and finishes the operation more slowly (an external merge sort or a multi-batch hash). It's silent: the query just gets slower under load. You detect it three ways. In a plan, `EXPLAIN (ANALYZE, BUFFERS)` shows the giveaway phrases — `Sort Method: external merge  Disk: 84032kB` or a Hash node with multiple `Batches` and `Disk Usage`. Cluster-wide, `pg_stat_database.temp_files` / `temp_bytes` counts spill activity per database, and enabling `log_temp_files = 0` logs every temp file created with its size and the statement.

```sql
SELECT datname, temp_files, pg_size_pretty(temp_bytes) AS temp_written
FROM pg_stat_database WHERE temp_bytes > 0 ORDER BY temp_bytes DESC;
```

```
-- In an EXPLAIN ANALYZE plan, the tells:
Sort  (actual rows=2000000 ...)
  Sort Method: external merge  Disk: 153672kB        <- spilled
HashAggregate (...) Batches: 8  Memory Usage: ...  Disk Usage: 96000kB   <- spilled
```

The tuning lever is **`work_mem`**, but the critical nuance — the thing that separates a junior from a senior answer — is that `work_mem` is allocated **per sort/hash node, per connection**, not per query and not globally. A single complex query with three sorts and two hash joins can use `5 × work_mem`; multiply by hundreds of concurrent connections and a generous global `work_mem` can OOM the server. So the right approach is: keep the **global `work_mem` conservative** (e.g., 16–64 MB), and **raise it per-session** for the specific heavy analytical queries that need it: `SET LOCAL work_mem = '512MB';` inside the transaction running the big report. PostgreSQL 13+ also added `hash_mem_multiplier` to give hash operations more memory than sorts, since hash spills are costlier.

The full diagnosis-to-fix chain: (1) confirm spilling via plan/`pg_stat_database`/`log_temp_files`. (2) Decide whether the spill is *worth fixing* — a rare report spilling is fine; a hot OLTP query spilling on every call is not. (3) For the hot case, either raise `work_mem` for that workload (per-session, not globally), add an index so the sort is avoided entirely (an index providing the sort order means no sort node at all), or reduce the data sorted (filter earlier, paginate). (4) Ensure temp files live on fast storage and watch they don't fill the disk. The trade-off to state: more `work_mem` avoids spills but risks memory exhaustion under concurrency — tune it to the workload, per-session for the heavy hitters, never blindly global.

#### Q91. [Practical] How do you set up and tune full-text search natively in PostgreSQL, and when do you outgrow it?

PostgreSQL has built-in full-text search via the `tsvector` (a document parsed into normalized lexemes with positions) and `tsquery` (a search expression) types, matched with the `@@` operator. The text-analysis pipeline applies a **configuration** (language) that tokenizes, lowercases, removes stop words, and stems (`running` → `run`), so `to_tsvector('english', body)` and `to_tsquery('english', 'run')` match across inflections. For performance you store the parsed vector — ideally as a **generated column** — and index it with **GIN**:

```sql
ALTER TABLE articles
  ADD COLUMN search tsvector
  GENERATED ALWAYS AS (to_tsvector('english', coalesce(title,'') || ' ' || coalesce(body,''))) STORED;
CREATE INDEX idx_articles_fts ON articles USING gin (search);

SELECT id, ts_rank(search, q) AS rank
FROM articles, to_tsquery('english', 'postgres & replication') q
WHERE search @@ q
ORDER BY rank DESC LIMIT 20;
```

Tuning and capability notes: use a **generated `tsvector` column** (PG 12+) rather than a trigger so it stays correct automatically; weight fields with `setweight` (title > body) to influence `ts_rank`; combine with the `pg_trgm` extension for **typo-tolerant / fuzzy / autocomplete** matching (trigram similarity and `ILIKE '%...%'` acceleration), which native FTS alone doesn't do well; and use `ts_headline` for snippet highlighting. GIN is the right index (fast lookups, the inverted structure suits "find docs containing these lexemes"); RUM (an extension) adds rank-aware ordering inside the index.

When you **outgrow** it: native FTS is excellent up to mid-scale and has the huge advantage of being **transactionally consistent with your data** (no separate index to sync, no dual-write) and one fewer system to operate. But it lacks the relevance sophistication, faceting, typo correction at scale, distributed sharding, and analyzer ecosystem of dedicated engines. You move to **Elasticsearch/OpenSearch** when you need advanced relevance tuning (BM25 tuning, learning-to-rank), large-scale faceted/aggregated search, multi-language analyzers, or search volume that would dominate your OLTP database's resources — typically syncing via logical-replication/CDC (Debezium). The senior framing: start with native FTS for the operational simplicity and consistency; graduate to a search engine when relevance/scale/feature requirements clearly exceed what `tsvector`+GIN+`pg_trgm` deliver, and treat the cutover as a CDC-driven denormalization, not a wholesale move off PostgreSQL.

#### Q92. [Practical] What essential metrics and alerts would you put on a production PostgreSQL fleet?

Effective PostgreSQL monitoring layers **golden signals** (latency/throughput/errors/saturation) with PostgreSQL-specific health indicators that catch its characteristic failure modes *before* they cause an outage. The non-negotiable alerts, grouped by failure class:

```
Category        | Metric / source                              | Why it matters / alert on
----------------+----------------------------------------------+---------------------------------
Wraparound      | age(datfrozenxid), mxid_age()                | > ~1.5B → emergency before read-only
Bloat / vacuum  | n_dead_tup, last_autovacuum, autovac running | rising dead tuples = bloat/slow scans
Replication     | replay_lag (bytes & seconds), slot status    | stale replicas, RPO hole
Repl. slots     | pg_replication_slots restart_lsn lag         | inactive slot pins WAL → disk fills
Connections     | count vs max_connections, idle-in-txn age    | exhaustion, leaks, xmin pinning
Disk            | pg_wal size, data volume %, temp_bytes       | WAL/temp filling disk → crash
Long queries    | longest active query / xact duration         | runaway query, lock pileup
Locks           | blocked PIDs, lock-wait depth                | lock storms, deadlock precursors
Cache / IO      | cache hit ratio, checkpoint timing           | sizing/IO problems
Query perf      | pg_stat_statements top by total_exec_time    | regressions, missing indexes
```

The PostgreSQL-specific ones that generic database monitoring *misses* are the high-value alerts: **XID wraparound age** (`age(datfrozenxid)` approaching the 2-billion danger zone forces the database read-only — alert well before, around 1–1.5B), **replication slot lag** (an inactive slot silently pins WAL and fills the disk — a top cause of self-inflicted primary outages), **`idle in transaction` duration** (pins the xmin horizon and holds locks), and **dead tuple growth / autovacuum keeping up**. These map directly to the failure modes covered throughout this guide, which is why they're the ones a PostgreSQL-savvy SRE alerts on specifically.

For implementation, the standard stack is the **Prometheus `postgres_exporter`** (or your cloud provider's enhanced monitoring on RDS/Aurora/Cloud SQL) feeding Grafana dashboards, with `pg_stat_statements` enabled for query-level observability and `auto_explain` to capture slow plans. The principle to articulate: alert on **leading indicators** (rising dead tuples, growing replication lag, climbing wraparound age, lengthening idle-in-transaction) so you intervene during the slow build-up, not on the **trailing catastrophe** (database read-only, disk full, connections exhausted) when you're already in an outage. Pair every alert with a runbook, and tier severity so a slowly-rising bloat metric pages differently than an imminent wraparound.

### 🔴 Expert — extended

#### Q93. [Practical] Your database hit XID wraparound and went read-only (or is about to). Walk through the emergency recovery.

This is the most feared PostgreSQL outage: when `age(datfrozenxid)` approaches ~2 billion, PostgreSQL refuses to assign new XIDs to protect data integrity, and the database stops accepting writes — at first with strident warnings, then by entering a state where only single-user-mode VACUUM can recover it. The cause is always that **freezing fell behind**: autovacuum was disabled or throttled too aggressively, a **long-running transaction** or **stale replication slot** or **abandoned prepared (2PC) transaction** held back the freeze horizon for weeks, or write volume outran vacuum on a huge table. The recovery has an order, and panicking (e.g., dropping data) makes it worse.

**Step 1 — identify the offending relations and what's pinning the horizon:**

```sql
-- Which databases/tables are oldest (closest to wraparound)?
SELECT datname, age(datfrozenxid) FROM pg_database ORDER BY 2 DESC;
SELECT relname, age(relfrozenxid) FROM pg_class
WHERE relkind IN ('r','m','t') ORDER BY 2 DESC LIMIT 20;

-- What is HOLDING BACK freezing? (any one of these blocks ALL freezing)
SELECT pid, age(backend_xid), age(backend_xmin), state, query
FROM pg_stat_activity WHERE backend_xmin IS NOT NULL ORDER BY age(backend_xmin) DESC;
SELECT slot_name, age(xmin) FROM pg_replication_slots;     -- stale slots pin xmin
SELECT gid, prepared FROM pg_prepared_xacts;               -- abandoned 2PC pins xmin
```

**Step 2 — remove the blocker first**, because VACUUM *cannot* freeze tuples newer than the oldest snapshot still in use: terminate the long-running transaction (`pg_terminate_backend`), drop the orphaned replication slot (`pg_drop_replication_slot`), or `ROLLBACK PREPARED` the abandoned 2PC transaction. **Step 3 — run an aggressive freezing VACUUM** on the oldest tables. If the database is still accepting connections (it warns but isn't fully read-only yet), you can do this online with maximized vacuum resources; if it has already shut down to prevent wraparound, you start in **single-user mode** and vacuum there:

```bash
# If fully shut down for wraparound, recover via single-user mode:
postgres --single -D /data mydb
backend> VACUUM FREEZE;        # or VACUUM (FREEZE, VERBOSE) the oldest tables first
```

**Step 4 — raise the I/O budget** so the emergency vacuum runs fast (`maintenance_work_mem` high, `vacuum_cost_delay` low/zero for the manual vacuum) and prioritize the tables with the highest `age(relfrozenxid)` to pull the cluster minimum down fastest. The post-incident hardening: **never disable autovacuum**, monitor `age(datfrozenxid)` with an alert around 1–1.5B (covered in the metrics question), watch for and reap long-running transactions / inactive slots / abandoned 2PC, lower `autovacuum_freeze_max_age` headroom thoughtfully, and tune autovacuum to keep pace on high-write tables. The senior signal is knowing that **VACUUM alone won't help until you remove whatever is pinning the xmin horizon**, and that the three usual pinners are long transactions, replication slots, and prepared transactions.

#### Q94. [Practical] After a routine OS package upgrade, a UNIQUE index started rejecting valid inserts and some queries miss rows. What happened?

This is the **collation-version corruption** incident, and recognizing it from these exact symptoms — *valid* inserts suddenly rejected as duplicates, and equality/range queries on text columns *missing rows that are present* — is a strong staff-level signal. The root cause: a B-tree index on a `text` column is **physically ordered according to the collation's comparison rules as they existed at build time**. An OS upgrade that bumped **glibc** (the infamous glibc 2.28 change reordered many `*.UTF-8` locales) or **ICU** changed those comparison rules underneath the index. Now PostgreSQL's runtime comparisons no longer match the on-disk key order, so the B-tree's search descends to the wrong leaf: a lookup for an existing value can fail to find it (query misses rows), and a `UNIQUE` insert can be placed where it doesn't see the existing duplicate (or wrongly collides with a non-duplicate).

```sql
-- PostgreSQL records the collation version an index/db was built with and warns on mismatch
SELECT collname, collversion FROM pg_collation WHERE collprovider = 'c';
-- Look for "WARNING: collation ... has version mismatch" in the logs after the upgrade
-- Find text indexes that need rebuilding:
SELECT indexrelid::regclass FROM pg_index i
JOIN pg_opclass oc ON oc.oid = ANY(i.indclass::oid[])  -- (illustrative; tools script this)
WHERE /* index involves a collatable text column */ true;
```

The fix is to **`REINDEX` every index on collatable text columns** so they're rebuilt under the *new* collation rules, using `REINDEX INDEX CONCURRENTLY` (PG 12+) to avoid downtime; on bad cases you also `REINDEX` databases and refresh the recorded collation version with `ALTER COLLATION ... REFRESH VERSION` / `ALTER DATABASE ... REFRESH COLLATION VERSION` once you've rebuilt. Critically, until reindexed the data may already be **logically inconsistent** (duplicate "unique" values that slipped in), so you may need to find and reconcile those before the unique index can even be rebuilt.

The durable hardening, which is the real lesson: (1) prefer **ICU collations** over glibc — ICU is explicitly versioned and consistent across distros, so PostgreSQL can detect mismatches reliably and you're not at the mercy of the host OS's libc. (2) For columns that only need equality and byte-order (codes, IDs, hashes), use `COLLATE "C"` — it's version-independent *and* faster (enables abbreviated keys). (3) Pin the OS/container base image and treat any glibc/ICU change as requiring a planned `REINDEX`. (4) Build replicas carefully: a replica running a different glibc than the primary can have *silently inconsistent* indexes from day one. This bug is feared precisely because nothing crashes — the database quietly returns wrong answers, so the postmortem fix is as much process (image pinning, collation strategy) as it is `REINDEX`.

#### Q95. [Practical] How do PostgreSQL's behavior and tuning differ on managed cloud services (RDS, Aurora, Cloud SQL) versus self-managed, and what gotchas matter?

Managed PostgreSQL trades operational control for convenience, and a senior engineer must know *which knobs and capabilities disappear* so they don't design something the platform forbids. The shared constraints across RDS/Cloud SQL/Azure: **no superuser** (you get a powerful but non-`SUPERUSER` admin role like `rds_superuser`), **no shell/filesystem access** (so no custom `archive_command` scripts, no arbitrary file `COPY`, no installing arbitrary C extensions — only an **allow-listed set** of extensions, which is why `shared_preload_libraries` changes go through the parameter group and a reboot), **managed backups/PITR and replication** (you can't hand-tune WAL archiving; you use the platform's snapshots and read replicas), and **parameter groups** instead of editing `postgresql.conf` directly (many parameters are locked or have platform-imposed bounds).

**Amazon Aurora PostgreSQL** is the bigger architectural departure: it **replaces the storage engine** with a distributed, log-structured, multi-AZ storage layer. Replicas don't replay WAL — they read from the *shared* storage volume — so replica lag is typically milliseconds and adding read replicas doesn't add write-replay load. But that also means Aurora's durability, checkpoint, and WAL behavior differ from community PostgreSQL (no `full_page_writes` in the same sense, different `max_wal_size` semantics), some parameters are inert, and certain extensions or low-level features behave differently or are unavailable. You tune Aurora more by instance class and the storage layer's behavior than by classic checkpoint/WAL knobs.

The practical gotchas to call out: (1) **extension availability** — verify your dependencies (PostGIS, `pg_partman`, `pg_cron`, specific FDWs) are on the provider's allow-list *before* committing to managed. (2) **You can't `pg_terminate_backend` everything** or do single-user-mode wraparound recovery the same way — you lean on the provider's tooling, and some emergency procedures require a support ticket. (3) **Logical replication for migration** still works (it's protocol-level), which is the main path for migrating *into* or *out of* a managed service. (4) **Cost shape** differs — Aurora charges for I/O and storage growth, so a bloat or full-table-scan problem that's merely slow on self-managed becomes *expensive* on Aurora. (5) **Version upgrades** are provider-orchestrated (often blue/green), removing some control but adding safety. The framing to deliver: managed services handle the undifferentiated heavy lifting (backups, failover, patching) and give Aurora's superior storage scaling, but you give up filesystem/superuser-level control, are limited to allow-listed extensions, and must design within the platform's guardrails — so validate extension support and emergency-recovery procedures up front rather than discovering the limits during an incident.

#### Q96. [Practical] Walk through diagnosing high CPU on the primary with no obvious single slow query.

"CPU is pinned but `pg_stat_activity` shows lots of short queries, none individually slow" is a distinct diagnostic problem from "one runaway query," and the methodology is to aggregate rather than hunt for a single culprit. **Step 1 — aggregate by query shape** with `pg_stat_statements` to find where total CPU is going; the answer is frequently **high-frequency cheap queries** (a 2 ms query running 50,000 times/second from an N+1 ORM pattern or a tight polling loop) whose *cumulative* `total_exec_time` dwarfs any individual slow query. Sort by `total_exec_time` and by `calls`, and compute mean — a tiny mean with enormous calls is the signature.

```sql
SELECT left(query, 60) AS query, calls,
       round(total_exec_time::numeric, 0) AS total_ms,
       round(mean_exec_time::numeric, 3)  AS mean_ms,
       round(100 * total_exec_time / sum(total_exec_time) OVER (), 1) AS pct
FROM pg_stat_statements ORDER BY total_exec_time DESC LIMIT 15;
```

**Step 2 — distinguish the CPU class.** Look at **wait events** in `pg_stat_activity`: if backends are mostly *not* waiting (no `Lock`/`IO`/`LWLock` wait events, just running on-CPU), it's genuine compute — bad plans doing seq scans/sorts in memory, missing indexes turning lookups into scans, or expensive functions/regex/JSON parsing per row. If you see heavy **`LWLock`** waits (e.g., `LockManager`, `WALInsert`, buffer-mapping, or `SLRU`-related), the CPU is being burned on **internal contention**, not query work — pointing at architectural limits like too many partitions inflating planning/lock-manager work, subtransaction/multixact SLRU contention, or spinlock contention under extreme connection counts.

**Step 3 — common concrete causes and fixes**, in order of frequency: (1) **missing index** causing repeated seq scans on a hot query — find via the plan and `seq_scan` counters, fix with a targeted index. (2) **N+1 / chatty application** — the same query shape with millions of calls; fix in the app (batch, join, cache) not the DB. (3) **Bad plans from stale statistics** after a data shift — `ANALYZE`, extended statistics. (4) **Too many connections** each doing a little work — context-switch and snapshot-building overhead; add/right-size PgBouncer. (5) **Expensive per-row work** — regex, `jsonb` extraction, `NUMERIC` math, or non-`IMMUTABLE` functions defeating optimization; consider generated columns or precomputation. (6) **Internal contention** from extreme partition counts or subtransaction overflow — reduce partitions, fix savepoint abuse, or (PG 17) tune SLRU buffers.

The senior framing: high CPU with no single slow query is almost always **death by a thousand cuts** (volume × cheap query, or N+1) or **internal contention**, not one bad statement — so you *aggregate* with `pg_stat_statements`, *classify* with wait events (on-CPU vs LWLock contention), and fix at the right layer (an index, an application change, a connection-topology change, or a contention knob), rather than chasing the longest-running query that may not even be the problem.

#### Q97. [Practical] Design and operate a database migration framework discipline for a team — what rules keep migrations from causing outages?

Most PostgreSQL outages aren't bad queries — they're **migrations that took an unexpected lock or rewrite**, so the discipline is a set of enforceable rules, not ad-hoc care. The foundational rule: **every migration runs with a short `lock_timeout`** (and often `statement_timeout`), so a DDL that can't immediately acquire its lock *fails fast and retries* instead of queueing behind a long-running query — because a single un-timed `ALTER TABLE` waiting on a lock will **block every subsequent query** on that table (they queue behind the DDL's pending `ACCESS EXCLUSIVE` request), turning one slow query into a full outage in seconds.

```sql
-- Every migration session
SET lock_timeout = '3s';
SET statement_timeout = '0';     -- or a sane cap; DDL like CREATE INDEX may need long
-- Then the actual change, written to avoid rewrites/long locks:
ALTER TABLE orders ADD COLUMN note text;                 -- instant (metadata only)
CREATE INDEX CONCURRENTLY idx_orders_note ON orders (note);  -- non-blocking build
ALTER TABLE orders ADD CONSTRAINT chk_total CHECK (total >= 0) NOT VALID;  -- fast, no scan
ALTER TABLE orders VALIDATE CONSTRAINT chk_total;        -- weaker lock, separate step
```

The codified rules every safe-migration framework (Rails `strong_migrations`, `pgroll`, gh-ost-style tools, or a team's own CI lint) enforces: (1) **never add a column with a volatile default** (forces a rewrite); constant defaults are fine since PG 11. (2) **Always `CREATE INDEX CONCURRENTLY`** on live tables (and verify `indisvalid` afterward, since a failed concurrent build leaves an invalid index). (3) **Add constraints `NOT VALID` then `VALIDATE` separately** — the initial add is a quick metadata change taking a strong lock only briefly, and `VALIDATE` scans under a *weaker* lock that doesn't block writes. (4) **Add foreign keys in two steps** (`NOT VALID` then validate) and **always index the referencing column**. (5) **Backfill in batches**, never one giant `UPDATE` (lock duration, WAL, bloat, replication lag). (6) **Avoid `ALTER COLUMN TYPE`** rewrites — use the expand/contract pattern. (7) **Separate transactional concerns**: `CREATE INDEX CONCURRENTLY` can't run in a transaction block, so it must be its own migration step.

The operational wrapper around the rules: a **CI check** that statically flags dangerous DDL (rewriting `ALTER`, non-concurrent index, volatile default, big backfill) before it merges; **migrations decoupled from deploys** so a slow index build doesn't block the app rollout; **expand/contract sequencing** so schema and application changes are backward-compatible across the deploy window (add new, deploy code that writes both, backfill, switch reads, drop old); and a **tested rollback** for every forward migration. The senior point: migration safety is a *systematic, enforced* discipline — `lock_timeout` as a seatbelt, `CONCURRENTLY`/`NOT VALID`/batched-backfill as the techniques, and CI linting plus expand/contract as the process — because the failure mode (a lock pileup behind an innocuous-looking `ALTER`) is silent until it takes everything down.

#### Q98. [Theory] What is the difference between `TRUNCATE`, `DELETE`, and `DROP`, and what are the transactional and operational implications of each?

These three remove data at very different levels with very different costs and locks. **`DELETE`** removes rows one at a time (matching a `WHERE`, or all rows without one): it's MVCC-aware (marks each row's `xmax`, leaving dead tuples that VACUUM must later reclaim — so a `DELETE` of millions of rows generates massive WAL and bloat and doesn't shrink the table), it fires **row triggers** and enforces foreign-key actions, takes a `ROW EXCLUSIVE` lock (concurrent reads continue), and is fully transactional and rollback-able. **`TRUNCATE`** removes *all* rows by essentially discarding the table's data files and creating fresh empty ones: it's near-instant regardless of table size (it doesn't touch rows individually, generates minimal WAL, and immediately reclaims space), but it takes an **`ACCESS EXCLUSIVE` lock** (blocks everything, even reads), doesn't fire per-row triggers (only statement-level `TRUNCATE` triggers), and bypasses row-level foreign-key checks (it refuses if other tables reference it unless you `CASCADE`).

```sql
DELETE FROM logs WHERE created_at < now() - interval '30 days';  -- MVCC, triggers, bloat
TRUNCATE logs;                          -- instant, ACCESS EXCLUSIVE, no row triggers
TRUNCATE logs RESTART IDENTITY CASCADE; -- also reset sequences + truncate FK-referencing tables
DROP TABLE logs;                        -- removes the table object entirely
```

The transactional nuance that surprises people: in PostgreSQL **`TRUNCATE` is transactional and MVCC-safe** — you can `TRUNCATE` inside a transaction and `ROLLBACK` it, and concurrent transactions either see the full old contents or the empty table depending on commit (unlike some databases where `TRUNCATE` is an implicit-commit DDL). It does this by creating a *new* relfilenode for the table; rollback just discards it. **`DROP TABLE`** goes further — it removes the table definition, indexes, triggers, and data entirely from the catalog (also transactional and rollback-able in PostgreSQL), and like `TRUNCATE` takes `ACCESS EXCLUSIVE`.

The decision and operational guidance: use **`DELETE`** when you need a `WHERE` filter, triggers/FK-cascade behavior, or to remove a subset; use **`TRUNCATE`** to empty an entire table fast and reclaim space (ETL staging tables, full resets) — but never on a hot table during peak because of the `ACCESS EXCLUSIVE` lock. For removing *old time-ranged data* at scale, the architecturally best answer is neither: **range-partition by time and `DROP`/`DETACH` the old partition** (instant, no bloat, no dead tuples), which is why partitioning is the standard pattern for retention. And the senior catch: a giant unfiltered `DELETE` is the wrong tool for "empty this table" precisely because it's O(n) with full WAL and leaves bloat that VACUUM then has to chase — reach for `TRUNCATE` or partition-`DROP` instead.

#### Q99. [Coding] Write a robust idempotent backfill/data-migration script for a huge table, with progress tracking and resumability.

A one-shot `UPDATE big_table SET ...` on hundreds of millions of rows is an outage in waiting: a single enormous transaction holds locks and an old snapshot (pinning the xmin horizon and starving autovacuum), generates gigantic WAL, blows up replication lag, and if it fails at row 400M you start over from zero. The correct shape is **batched, idempotent, resumable, and self-throttling** — each batch is its own short transaction that commits independently, the work is keyed so re-running is safe, and progress is recorded so a crash resumes where it left off.

```java
public void backfillEmailDomain(DataSource ds, int batchSize) throws SQLException {
    // Resume from the last processed id (persisted in a control table)
    long lastId = readCheckpoint(ds, "backfill_email_domain");
    String sql = """
        WITH batch AS (
            SELECT id FROM users
            WHERE id > ? AND email_domain IS NULL      -- idempotent: only un-backfilled rows
            ORDER BY id
            LIMIT ?
        )
        UPDATE users u
        SET email_domain = lower(split_part(u.email, '@', 2))
        FROM batch b
        WHERE u.id = b.id
        RETURNING u.id
        """;
    while (true) {
        long maxIdInBatch = lastId;
        int updated = 0;
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, lastId);
                ps.setInt(2, batchSize);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) { maxIdInBatch = rs.getLong(1); updated++; }
                }
            }
            writeCheckpoint(c, "backfill_email_domain", maxIdInBatch);  // same txn as the batch
            c.commit();                                  // batch + checkpoint commit atomically
        }
        if (updated == 0) break;                         // drained
        lastId = maxIdInBatch;
        throttle(ds);                                    // pause if replication lag is high
    }
}
```

The design decisions that make it production-safe: (1) **Batched short transactions** keep locks brief, cap WAL per commit, and let autovacuum reclaim the dead tuples each `UPDATE` creates *between* batches — preventing the bloat explosion a single giant transaction causes. (2) **Idempotency** via the `email_domain IS NULL` predicate (and/or the `id >` cursor) means re-running the whole script, or resuming after a crash, never double-processes or corrupts — critical because backfills *do* get interrupted. (3) **Checkpoint in the same transaction** as the batch so progress and work commit atomically — you can never be in a state where rows were updated but the checkpoint wasn't recorded (or vice versa). (4) **Keyset cursor (`id >`)** not `OFFSET`, so each batch is an O(log n) index seek, not an increasingly expensive scan. (5) **Adaptive throttling** — check `pg_stat_replication` replay lag and pause when standbys fall behind, so the backfill doesn't blow your RPO or cause replica-served reads to go stale.

The trade-offs and edge cases to mention: smaller batches mean more transactions/overhead but shorter locks and finer resumability — tune to balance throughput against lock duration and replication lag (a few thousand rows per batch is a common sweet spot). Ensure an index supports both the cursor (`id`) and ideally the filter, or each batch's `SELECT` degrades. For backfills that *change every row* (no "IS NULL" sentinel), track progress purely by the id cursor in the control table and make the transformation deterministic so re-applying it is harmless. **Complexity:** O(n) total across batches, each batch O(batchSize · log n); the whole job is interruptible and exactly-once-effective by construction. This batched-idempotent-resumable-throttled shape is the canonical answer to "migrate data in a huge table safely," and it directly mirrors the safe batched-`DELETE` and zero-downtime column-change patterns elsewhere in this guide.

#### Q100. [Practical] Your primary-key `id` column is an `integer` and inserts just started failing with "integer out of range." What happened and how do you fix it without downtime?

The dreaded **`int4` sequence exhaustion**: a `SERIAL`/`int` primary key tops out at **2,147,483,647** (2^31−1), and once the backing sequence reaches it, every `INSERT` fails with `ERROR: integer out of range`. It's a hard production stop that often arrives suddenly — the table may have far fewer than 2.1B *rows* because sequences leave **gaps** (rollbacks, caching, deletes), so the sequence counter outran the row count. People are caught off guard because "we only have 800M rows" feels safe while the sequence is already near the ceiling. The immediate triage check:

```sql
SELECT last_value, 2147483647 - last_value AS headroom
FROM orders_id_seq;
```

The fix is to widen the column to **`bigint`** (`int8`, max ~9.2 quintillion), but a naive `ALTER TABLE orders ALTER COLUMN id TYPE bigint` is a **full table rewrite under `ACCESS EXCLUSIVE`** — every row and index rebuilt, the table locked for the duration: an outage on a billion-row table, and you're *already* in an incident. The zero-downtime approach is the **expand/contract** pattern: add a new `bigint` column, backfill it in batches while a trigger keeps it in sync with new inserts, build a unique index on it `CONCURRENTLY`, then in a brief locked transaction swap it into the primary key and re-point foreign keys.

```sql
-- Emergency stopgap if you can take a momentary lock: a bigint id rewrite is unavoidable
-- the safe path is expand/contract (add bigint col, backfill batched, swap), as in Q86.
ALTER TABLE orders ADD COLUMN id_big bigint;             -- instant
-- trigger to populate id_big on insert/update + batched backfill of existing rows ...
CREATE UNIQUE INDEX CONCURRENTLY orders_id_big_uk ON orders (id_big);
-- brief locked cutover: drop old PK, promote id_big to id/PK, fix referencing FKs
```

The lessons: (1) **default new identity columns to `bigint`** (`BIGINT GENERATED ALWAYS AS IDENTITY`) from day one — the 8-byte cost is trivial insurance against this exact outage, and it's why modern advice never uses `int4` PKs on tables that grow. (2) **Foreign keys referencing the column must be widened too**, which is why this migration is multi-table and benefits from the dual-write playbook. (3) **Monitor sequence headroom** (`last_value` vs the type max) as a metric so you migrate calmly months ahead instead of during an outage. (4) Remember sequences are non-transactional and gap-prone, so sequence value ≠ row count — always watch the *sequence*, not the row total.

#### Q101. [Practical] When would you use an `UNLOGGED` table, and what exactly do you give up?

An `UNLOGGED` table skips writing its changes to the **WAL**, which removes the single biggest source of write overhead: no WAL means no fsync of log records on commit and no WAL volume to ship. The result is **dramatically faster writes** (often 2–5×) for the table. The price, stated precisely: because the WAL is also what makes data **crash-safe and replicated**, an unlogged table is **truncated automatically on crash recovery** (PostgreSQL can't guarantee its consistency, so it empties it) and is **not replicated to standbys** (it doesn't exist as data on a physical replica, and logical replication won't carry it). It *survives a clean shutdown/restart*, just not a crash.

```sql
CREATE UNLOGGED TABLE session_cache (key text PRIMARY KEY, value jsonb, expires timestamptz);
-- Fast writes; vanishes on crash and is absent on replicas.
ALTER TABLE session_cache SET LOGGED;     -- promote to durable (rewrites + WALs the data)
```

The right use cases are exactly where **the data is transient, reproducible, or cache-like** and losing it on a crash is acceptable: ETL **staging tables** (loaded fresh each run), **materialized intermediate results**, session/cache tables you can rebuild, scratch tables for heavy analytical steps, or high-throughput ingest buffers that get flushed elsewhere. A common pattern is `CREATE UNLOGGED TABLE` for a bulk-load/transform staging area, do the work at full speed, then `INSERT ... SELECT` the validated results into the durable table.

The trade-offs and gotchas to articulate: (1) **never** use unlogged for data you can't lose or must read on a replica — that's the whole contract. (2) `ALTER TABLE ... SET LOGGED` to make it durable later **rewrites the table and WAL-logs all of it** (a heavy operation), and `SET UNLOGGED` likewise rewrites — so toggling isn't free. (3) On a server that crashes often, "fast but empty after crash" can be a nasty surprise if the app assumes persistence. (4) Indexes on unlogged tables are themselves unlogged. The senior framing: `UNLOGGED` trades **durability and replication** for **write speed**, so it's a precise tool for transient/reproducible data — and a footgun if used for anything authoritative.

#### Q102. [Practical] How do you implement secure multi-tenant isolation with Row-Level Security, and what are the operational pitfalls?

Row-Level Security (RLS) makes the *database itself* enforce that a tenant can only see and modify its own rows, so a missing `WHERE tenant_id = ?` in application code — the kind of bug that causes catastrophic cross-tenant data leaks — physically cannot return another tenant's data. You enable RLS on the table and attach **policies** that the planner injects as mandatory predicates on every query, keyed off a per-session variable the app sets after authenticating the tenant:

```sql
ALTER TABLE documents ENABLE ROW LEVEL SECURITY;
ALTER TABLE documents FORCE ROW LEVEL SECURITY;   -- apply even to the table owner

CREATE POLICY tenant_isolation ON documents
  USING      (tenant_id = current_setting('app.tenant_id')::bigint)   -- read/visibility
  WITH CHECK (tenant_id = current_setting('app.tenant_id')::bigint);  -- writes can't escape

-- The app sets the tenant context per request/connection-checkout:
SET app.tenant_id = '42';        -- or set_config('app.tenant_id', '42', true) for txn-scope
```

The `USING` clause filters which rows are *visible* (SELECT/UPDATE/DELETE see only matching rows); the `WITH CHECK` clause prevents *writing* rows that would violate the predicate (so a tenant can't `INSERT` or `UPDATE` a row into another tenant's id). The defense-in-depth value is that even with a SQL-injection bug or a forgotten filter, the policy still constrains the result — RLS plus least-privilege roles is the combination that contains blast radius.

The operational pitfalls that make or break this in production: (1) **Table owners and superusers bypass RLS by default** — you must add `FORCE ROW LEVEL SECURITY` so even the owning role is constrained, and the app must connect as a **non-owner, non-superuser role**. (2) **Connection pooling leaks context** — with PgBouncer transaction pooling, a `SET` persists on the server connection and can bleed into the next client's transaction; use **transaction-scoped** `set_config('app.tenant_id', ..., true)` (the `true` makes it `SET LOCAL`) set at the start of every transaction, never a session `SET`. (3) **Performance** — the policy predicate is added to every query, so `tenant_id` must be **indexed** (and ideally the leading column of composite indexes) or RLS turns point lookups into scans; many teams *partition by tenant* so RLS predicates also prune partitions. (4) **Policy gaps** — RLS is per-command (`FOR SELECT/INSERT/UPDATE/DELETE`/`ALL`); forgetting a command or the `WITH CHECK` half leaves a hole. (5) **Hard-to-debug "missing rows"** — a query returning nothing because `app.tenant_id` wasn't set looks like a bug, so fail loudly if the setting is absent. The senior framing: RLS is excellent **defense in depth** that moves tenant isolation from fallible application code into the engine, but it demands disciplined session-variable management (transaction-scoped, pooler-safe), a non-owner role with `FORCE`, and `tenant_id` indexing/partitioning to keep it fast.

#### Q103. [Practical] What is the practical difference between `VACUUM` and `ANALYZE`, and when do you run each manually despite autovacuum?

They are two different maintenance jobs that autovacuum happens to launch together, and conflating them causes real confusion in incidents. **`VACUUM`** reclaims space from dead tuples (MVCC garbage), updates the visibility/free-space maps, and freezes old XIDs to prevent wraparound — it's about **physical health** (bloat, space reuse, index-only-scan eligibility, wraparound safety). **`ANALYZE`** samples the table and updates the **planner statistics** in `pg_statistic` (null fractions, n_distinct, most-common-values, histograms) — it's about **query planning quality**, and it touches nothing physical. A table can be perfectly vacuumed but have stale statistics (bad plans), or have great statistics but be badly bloated (slow scans) — different problems, different cures.

```sql
VACUUM (VERBOSE) orders;            -- reclaim bloat, update maps/freeze (no stats change)
ANALYZE orders;                     -- refresh planner statistics only
VACUUM ANALYZE orders;              -- do both
VACUUM (FREEZE, ANALYZE, VERBOSE) orders;
```

Autovacuum runs *both* on its own thresholds (`autovacuum_vacuum_scale_factor` for VACUUM, `autovacuum_analyze_scale_factor` for ANALYZE), and for steady-state tables that's enough — you should **not** routinely run them by hand. The cases where you *do* intervene manually: (1) **immediately after a bulk load or large `UPDATE`** run `ANALYZE` (or `VACUUM ANALYZE`) — autovacuum's analyze hasn't fired yet, so the planner has stale row estimates and may pick seq scans or nested loops (this is the canonical "fast query went slow after a load" fix). (2) After a **major version upgrade or restore**, the new cluster has *no* statistics — run a database-wide `ANALYZE` before opening to traffic or every query plans blind. (3) During a **wraparound emergency**, run `VACUUM (FREEZE)` manually on the oldest tables with maximized resources. (4) For a **one-off bloat cleanup** on a table autovacuum isn't keeping up with, a manual `VACUUM` (and, for severe bloat, `pg_repack`).

The trade-offs and senior nuances: `ANALYZE` is cheap (a sample governed by `default_statistics_target`, default 100) — run it freely after data shifts. `VACUUM` does real I/O and competes with foreground work, but plain `VACUUM` is online (only `ROW EXCLUSIVE`-ish, never blocks reads); it's `VACUUM **FULL**` that takes `ACCESS EXCLUSIVE` and must never hit a live OLTP table. The right long-term answer to "should I cron manual VACUUMs" is usually **no — tune autovacuum** (lower per-table scale factors, raise the cost limit) so it keeps pace, reserving manual runs for the after-bulk-load `ANALYZE` and genuine emergencies. Knowing they solve *orthogonal* problems (physical bloat/wraparound vs planner statistics) is the distinction interviewers are checking.

#### Q104. [Practical] How do you safely use `LISTEN`/`NOTIFY`, and where does it break down at scale?

`LISTEN`/`NOTIFY` is PostgreSQL's built-in **publish/subscribe** mechanism: a session runs `LISTEN channel` to subscribe, and any session can `NOTIFY channel, 'payload'` to broadcast a message (up to ~8 KB payload) to all current listeners. Its killer feature is **transactional integration** — notifications sent inside a transaction are delivered only if (and when) that transaction **commits**, and never on rollback. That makes it perfect for "tell the app a row changed *after the change is durable*" without the dual-write problem of writing to the DB and a separate message broker and risking them diverging. The common pattern is a trigger that `NOTIFY`s on insert/update so application workers react in near-real-time:

```sql
CREATE FUNCTION notify_job() RETURNS trigger AS $$
BEGIN
  PERFORM pg_notify('jobs', NEW.id::text);   -- pg_notify() allows a dynamic payload
  RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER trg_notify_job AFTER INSERT ON jobs
  FOR EACH ROW EXECUTE FUNCTION notify_job();
-- A listener (e.g., JDBC) issues LISTEN jobs; and reacts to notifications.
```

The strengths in moderation: it's transactional, low-latency, requires no extra infrastructure, and is great for cache invalidation signals, waking job-queue workers (combine with `SKIP LOCKED` claiming), and pushing config-reload events to app instances. Where it **breaks down at scale and the pitfalls**: (1) **delivery is best-effort to *currently connected* listeners only** — there's no persistence, no replay, no offset; a listener that's disconnected (or reconnecting) **misses notifications entirely**, so you must treat NOTIFY as a *hint to go check the table*, not as the source of truth (idempotent "something changed, re-scan" rather than "here is the event you must process"). (2) **It does not cross to replicas** and isn't part of physical replication, so a read replica can't listen for primary changes. (3) **A global lock on commit** — the notification queue is serialized, so extremely high `NOTIFY` rates create contention (`NOTIFY` queue / `pg_notify` lock waits) and can bottleneck commit throughput. (4) **Connection-pooler hostility** — `LISTEN` is session state, so with PgBouncer **transaction pooling it doesn't work** (the listening session isn't pinned); you need session pooling or a dedicated direct connection for listeners. (5) **Payload size limit** (~8 KB) and queue size limits.

The senior framing: `LISTEN`/`NOTIFY` is an excellent *lightweight, transactional* signaling primitive for moderate volumes — use it as a **commit-safe "go look" trigger** paired with a durable table (the actual state), keep listeners on dedicated/session-pooled connections, and make handlers idempotent and self-healing (re-scan on connect to catch missed signals). When you need **guaranteed delivery, persistence, replay, fan-out across regions, or very high throughput**, you've outgrown it — move to a real broker (Kafka/SQS/Rabbit), typically fed by CDC (logical decoding/Debezium) so you keep the transactional-consistency benefit without NOTIFY's at-most-once, listeners-only-while-connected limits.

#### Q105. [Practical] You suspect a heavily-updated table stopped getting HOT updates and is bloating. How do you confirm and fix it?

A write-heavy OLTP table whose size and index sizes keep growing while the live row count stays flat, with autovacuum running constantly yet never catching up, is the signature of **lost HOT updates causing index bloat**. Recall HOT (heap-only tuple) updates avoid creating new index entries — but only when **no indexed column changes** *and* the new tuple version **fits on the same page**. When either condition fails, every update writes new index entries (write amplification) and dead index tuples accumulate, so indexes bloat even though VACUUM is working. You confirm with the HOT-update ratio from `pg_stat_user_tables`:

```sql
SELECT relname,
       n_tup_upd, n_tup_hot_upd,
       round(100.0 * n_tup_hot_upd / NULLIF(n_tup_upd, 0), 1) AS hot_pct,
       n_dead_tup, last_autovacuum
FROM pg_stat_user_tables
ORDER BY n_tup_upd DESC;
-- A low hot_pct (e.g., <50%) on a hot table = HOT is failing → index bloat
```

Two distinct root causes, each with its own fix. **Cause A — an indexed column is being updated:** if you index a frequently-changing column (a `status`, a `last_seen_at`, a counter), *every* update of that column is disqualified from HOT and must touch the index. The fix is to **stop indexing the churny column** if you can (or split hot-mutable fields into a separate narrow table), since indexing a high-update column trades read speed for relentless write amplification and bloat. **Cause B — no free space on the page:** even if no indexed column changes, HOT needs room on the same page for the new version; at the default `fillfactor = 100` a page fills and subsequent updates land elsewhere, breaking HOT. The fix is to **lower `fillfactor`** to reserve in-page room for successive updates:

```sql
ALTER TABLE accounts SET (fillfactor = 85);   -- reserve 15% per page for HOT updates
VACUUM FULL accounts;   -- or pg_repack — fillfactor only affects NEWLY written pages
```

The full remediation sequence: (1) confirm low `hot_pct` and correlate with which columns are updated and which are indexed. (2) Remove indexes on hot-mutable columns where feasible. (3) Lower `fillfactor` on the table so updates stay in-page, then **rewrite** (`pg_repack` online, or `VACUUM FULL` in a window) because fillfactor only governs newly written pages — existing dense pages won't benefit until rewritten. (4) Reindex the bloated indexes (`REINDEX INDEX CONCURRENTLY`) and tune autovacuum to keep pace afterward. The trade-off to state: lower fillfactor costs some storage and a few more pages to scan, but for a hot OLTP table that's a great deal versus the much larger cost of index write amplification, page splits, and chronic bloat. Restoring HOT (right fillfactor, not indexing churny columns) is one of the highest-leverage, least-known fixes for write-heavy bloat — directly the lever from the fillfactor internals question, applied as a production diagnosis.

#### Q106. [Practical] What are prepared (two-phase commit) transactions, and why are orphaned ones a serious operational hazard?

A **prepared transaction** is PostgreSQL's support for the **two-phase commit (2PC)** protocol, used by distributed transaction coordinators (XA, JTA, distributed sagas) to atomically commit across multiple resources. The flow splits commit into two phases: `PREPARE TRANSACTION 'gid'` does all the work and durably persists it as ready-to-commit but **not yet visible** (locks held, changes flushed to WAL), and later a `COMMIT PREPARED 'gid'` or `ROLLBACK PREPARED 'gid'` finalizes it. Between those steps the transaction is **in-doubt** — the coordinator has promised it can commit, so PostgreSQL must hold its locks and keep its snapshot alive across the gap, even across a server restart (prepared transactions survive a crash, that's the point).

```sql
BEGIN; UPDATE accounts SET balance = balance - 100 WHERE id = 1;
PREPARE TRANSACTION 'txn-7f3a';        -- durable, in-doubt, locks held, NOT visible
-- ... coordinator coordinates the other resource ...
COMMIT PREPARED 'txn-7f3a';            -- or ROLLBACK PREPARED 'txn-7f3a';
SELECT gid, prepared, owner FROM pg_prepared_xacts;   -- inspect in-doubt transactions
```

The serious hazard is an **orphaned prepared transaction**: if the coordinator crashes, loses track of the `gid`, or a bug never sends the final commit/rollback, the prepared transaction sits **forever** in `pg_prepared_xacts`. Because it's a live (in-doubt) transaction, it **pins the xmin horizon** — exactly like a long-running transaction — so **autovacuum cannot freeze or clean up anywhere in the database**, driving cluster-wide bloat and, left long enough, **XID wraparound** (a prepared transaction is one of the three classic xmin pinners, alongside long-running transactions and stale replication slots, that you hunt for in a wraparound emergency). It also holds its **locks** indefinitely, blocking conflicting writers and DDL.

The operational discipline: (1) **`max_prepared_transactions` defaults to 0** (2PC disabled) — only enable it if you genuinely run a distributed transaction coordinator, because the feature is a liability without one managing the lifecycle. (2) **Monitor `pg_prepared_xacts`** and alert on any prepared transaction older than a short threshold (minutes) — a healthy coordinator finalizes them almost immediately. (3) **Resolve orphans manually** with `ROLLBACK PREPARED 'gid'` (or `COMMIT PREPARED` if you know it should have committed) once you've confirmed the coordinator abandoned it — this is part of wraparound/bloat triage. (4) Prefer application-level patterns (sagas with compensating actions, the outbox/CDC pattern, or `SKIP LOCKED` queues) over distributed 2PC when you can, precisely to avoid the in-doubt-transaction fragility. The senior point: prepared transactions are necessary for genuine XA/distributed atomicity but are **dangerous when orphaned** because an in-doubt transaction pins xmin and locks just like a forgotten open transaction — so enable 2PC only with a real coordinator and monitor `pg_prepared_xacts` as a first-class health metric.

#### Q107. [Practical] How do you configure logging to catch slow queries, lock waits, and autovacuum problems without drowning in log noise?

PostgreSQL's logging is the cheapest, always-available observability tool, but the defaults log almost nothing useful and naive "log everything" settings flood disk and *themselves* become a performance problem (synchronous logging under load can stall backends). The art is enabling the **targeted** settings that catch the failure modes this guide covers while keeping volume sane. The high-value parameters, by purpose:

```ini
# Slow query capture — the workhorse. Log statements slower than a threshold, not all.
log_min_duration_statement = '500ms'     # or 1s; NOT 0 (which logs everything) in prod
auto_explain.log_min_duration = '1s'     # log the actual PLAN of slow queries (shared_preload_libraries)
auto_explain.log_analyze = on            # include real rows vs estimates (some overhead)

# Lock & deadlock visibility — catch lock pileups before/when they happen
log_lock_waits = on                      # log a query that waited > deadlock_timeout for a lock
deadlock_timeout = '1s'                   # also the threshold for log_lock_waits

# Autovacuum visibility — see when vacuum runs, how long, and what it reclaimed
log_autovacuum_min_duration = '0'        # log every autovacuum (or a threshold like '1s')

# Connection / disk-spill / temp-file forensics
log_temp_files = 0                       # log every temp file (work_mem spill) with its size
log_connections = on                     # auth/connection auditing (can be noisy)
log_checkpoints = on                     # checkpoint frequency & I/O (cheap, very useful)

# Make every line parseable: who, when, which db/txn
log_line_prefix = '%m [%p] %q%u@%d %a %x '   # timestamp, pid, user@db, app, xid
```

The reasoning behind the key choices: **`log_min_duration_statement`** is *the* slow-query log — set it to a threshold (500 ms–1 s) so you capture the genuinely slow statements without logging millions of fast ones; `0` (log all) is for short debugging sessions, never steady-state production. **`auto_explain`** goes further by logging the *plan* of slow queries automatically, so you catch the intermittent bad plan (the generic-plan flip, the nested-loop-from-misestimate) *as it happens* without manually reproducing it — `log_analyze = on` adds real-vs-estimated rows but has measurable overhead, so enable it judiciously. **`log_lock_waits`** turns the lock-pileup outage (a migration queued behind a long query blocking everything) into a logged, diagnosable event. **`log_autovacuum_min_duration = 0`** reveals whether autovacuum is keeping up. **`log_temp_files`** surfaces silent `work_mem` spills. **`log_checkpoints`** is cheap and exposes checkpoint/WAL pressure.

The operational guardrails: (1) **log volume is itself a risk** — `log_min_duration_statement = 0` or `log_statement = 'all'` on a busy server can saturate the disk and slow every backend (logging can be on the commit path); always use thresholds. (2) **Use a log collector / structured logging** (`logging_collector = on`, or CSV/JSON log format in PG 15+ which adds `jsonlog`) and ship to a central system so logs are searchable and rotated, not filling the data volume. (3) **`log_line_prefix`** must include timestamp, PID, user, database, and transaction id or the logs are hard to correlate — many incidents are solved by joining a slow-query line to the lock-wait line by PID/xid. (4) Pair logging with `pg_stat_statements` (aggregate view) — logs give you *individual* slow events and their parameters/plans, `pg_stat_statements` gives you the *cumulative* picture; you need both. The senior framing: configure logging to capture the *specific* failure signatures (slow statements with `log_min_duration_statement`, intermittent bad plans with `auto_explain`, lock pileups with `log_lock_waits`, vacuum lag with `log_autovacuum_min_duration`, spills with `log_temp_files`) at *thresholds* that keep volume manageable, ship it centrally, and make every line correlatable — so when an incident hits, the evidence is already captured rather than something you scramble to enable mid-outage.

## 🧩 Extended Questions — Set 3: Coding, Design & Expert Scenarios

### 🟢 Basic — extended

#### Q108. [Coding] Write a query to return the running total of daily revenue and a 7-day moving average using window functions.

This is a near-universal SQL screening task because it tests whether you reach for **window functions** instead of correlated subqueries or self-joins. A window function computes a value over a *frame* of rows related to the current row, without collapsing them into groups the way `GROUP BY` does. The running total uses an unbounded preceding frame; the moving average uses a sliding 7-row frame.

```sql
SELECT
    day,
    revenue,
    SUM(revenue) OVER (ORDER BY day
                       ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS running_total,
    AVG(revenue) OVER (ORDER BY day
                       ROWS BETWEEN 6 PRECEDING AND CURRENT ROW)        AS moving_avg_7d
FROM daily_revenue
ORDER BY day;
```

The subtle correctness point that separates a strong answer is the difference between `ROWS` and `RANGE` framing. `ROWS BETWEEN 6 PRECEDING` counts **physical rows**, so if a day is missing (a gap in the data) the "7-day" average actually spans more than 7 calendar days. If true calendar-window semantics matter, you use `RANGE BETWEEN INTERVAL '6 days' PRECEDING AND CURRENT ROW` (PostgreSQL supports `RANGE` with values since PG 11), which is gap-aware but requires the `ORDER BY` column to be a single sortable type the offset can subtract from. Another gotcha: the **default frame** when you specify `ORDER BY` but omit a frame clause is `RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW`, which treats *peer* rows (ties on the ordering key) as one unit — so a naive `SUM(...) OVER (ORDER BY day)` over duplicate days gives the same running total to all rows of that day, which may or may not be what you want.

The performance note worth stating: a single ordered scan feeds all the window functions sharing the same `OVER` ordering — PostgreSQL computes them in one **WindowAgg** pass over a sorted input. An index on `day` lets the planner skip the sort entirely. Replacing this with a correlated subquery per row would be O(n²) and is the anti-pattern interviewers are checking you avoid.

#### Q109. [Coding] Given an `employees` table with `manager_id`, write a recursive query to return each employee with their depth in the org tree and full management chain.

Hierarchical traversal is the canonical use of `WITH RECURSIVE`. A recursive CTE has two parts joined by `UNION [ALL]`: a **base (anchor) term** that seeds the result, and a **recursive term** that references the CTE itself and runs repeatedly until it produces no new rows. PostgreSQL evaluates it with a working table: each iteration feeds the previous iteration's new rows back into the recursive term.

```sql
WITH RECURSIVE org AS (
    -- anchor: top-level managers
    SELECT id, name, manager_id,
           1                         AS depth,
           ARRAY[name]               AS chain
    FROM employees
    WHERE manager_id IS NULL
  UNION ALL
    -- recursive: employees reporting to someone already in `org`
    SELECT e.id, e.name, e.manager_id,
           o.depth + 1,
           o.chain || e.name          -- append to the path array
    FROM employees e
    JOIN org o ON e.manager_id = o.id
)
SELECT id, name, depth,
       array_to_string(chain, ' > ') AS management_chain
FROM org
ORDER BY chain;
```

The two senior-level safety considerations: first, **cycle protection**. If the data is dirty (A reports to B, B reports to A), a `UNION ALL` recursion loops forever. PostgreSQL 14+ offers a native `CYCLE` clause (`... CYCLE id SET is_cycle USING path`) that detects and stops cycles; before that you carry a path array and add `WHERE NOT e.id = ANY(o.chain_ids)` to the recursive term. Using `UNION` instead of `UNION ALL` deduplicates but does *not* prevent infinite recursion on cyclic graphs because the newly generated rows still differ by depth.

Second, **performance and ordering**. Recursive CTEs are always materialized and do a breadth-first walk; on deep or wide trees an index on `manager_id` is essential so each iteration's join is an index lookup rather than a seq scan. The `ARRAY` path column doubles as a stable sort key for pretty-printing the tree (`ORDER BY chain`) and as the cycle-guard, which is why carrying it is idiomatic. For genuinely huge hierarchies that are read far more than written, the `ltree` extension or a closed-form **closure table** can outperform recursive traversal.

#### Q110. [Coding] Write SQL to pivot a long key/value `metrics` table into columns (one row per entity, one column per metric) two different ways.

Pivoting (long-to-wide) is a frequent coding question because there are two idiomatic approaches with different trade-offs, and a strong candidate names both. The portable, planner-friendly way is **conditional aggregation** with `FILTER` (PG 9.4+), which is just `GROUP BY` plus per-column filtered aggregates:

```sql
SELECT entity_id,
       MAX(value) FILTER (WHERE key = 'cpu')    AS cpu,
       MAX(value) FILTER (WHERE key = 'mem')    AS mem,
       MAX(value) FILTER (WHERE key = 'disk')   AS disk
FROM metrics
GROUP BY entity_id;
```

The second way uses the `tablefunc` extension's `crosstab()` function, which is more compact for many columns but fragile — it requires a precise `ORDER BY` (1,2) in the source query, the output column list must be declared explicitly, and missing values map to NULLs positionally, which can silently misalign columns if a category is absent for some rows:

```sql
CREATE EXTENSION IF NOT EXISTS tablefunc;
SELECT * FROM crosstab(
    'SELECT entity_id, key, value FROM metrics ORDER BY 1,2',
    'SELECT DISTINCT key FROM metrics ORDER BY 1'
) AS ct(entity_id int, cpu numeric, mem numeric, disk numeric);
```

The reasoning to convey: prefer `FILTER` aggregation in application code because it is standard SQL, the planner optimizes it well, and the column mapping is explicit and safe. Reach for `crosstab` only for wide, ad-hoc reporting where typing many `FILTER` clauses is tedious. The hard limitation both share — and the thing interviewers want you to recognize — is that **SQL output columns must be fixed at parse time**, so a *truly dynamic* pivot (unknown set of keys) cannot be done in one pure-SQL statement; you must either generate the SQL dynamically in the app, return JSON (`jsonb_object_agg(key, value)`), or use a two-step PL/pgSQL function that builds the column list. Returning `jsonb_object_agg(key, value) GROUP BY entity_id` is often the cleanest answer when the key set is open-ended.

### 🟡 Intermediate — extended

#### Q111. [Coding] Write a query that detects and lists gaps in a sequence of order numbers (the "gaps and islands" problem).

"Gaps and islands" is a classic SQL puzzle that tests window-function fluency and the ability to reason about row-to-row relationships. The goal: given a column of integers (or dates) with some values missing, report the ranges of missing values (gaps) or the contiguous runs (islands). The elegant technique uses `LEAD`/`LAG` to compare each row to its neighbor.

```sql
-- GAPS: ranges of missing order numbers
WITH ordered AS (
    SELECT order_no,
           LEAD(order_no) OVER (ORDER BY order_no) AS next_no
    FROM orders
)
SELECT order_no + 1               AS gap_start,
       next_no - 1                AS gap_end,
       next_no - order_no - 1     AS missing_count
FROM ordered
WHERE next_no - order_no > 1      -- a discontinuity
ORDER BY gap_start;
```

For the dual "islands" problem (contiguous runs), the standard trick is the **difference-of-row-numbers** method: subtract `ROW_NUMBER()` from the value, and rows in the same consecutive run share a constant difference, which you then group on:

```sql
WITH tagged AS (
    SELECT order_no,
           order_no - ROW_NUMBER() OVER (ORDER BY order_no) AS grp
    FROM orders
)
SELECT MIN(order_no) AS island_start, MAX(order_no) AS island_end, COUNT(*) AS len
FROM tagged
GROUP BY grp
ORDER BY island_start;
```

The reasoning that signals depth: the row-number trick works because in a contiguous run, the value increases by exactly 1 each step and so does `ROW_NUMBER()`, so their difference is invariant within a run and changes only at a break — turning "find consecutive runs" into a simple `GROUP BY`. The same pattern generalizes to dates (cast to an integer day count first) and to detecting consecutive login streaks or uptime windows. Both forms are a single sorted pass plus window evaluation — O(n log n) for the sort — and an index on the ordering column removes the sort. The naive alternative (self-join each row to the next) is O(n²) and is exactly what this technique is meant to replace.

#### Q112. [Coding] Implement a PL/pgSQL trigger that maintains an audit-history row on every UPDATE, capturing the changed columns as JSONB.

Triggers are a core PL/pgSQL coding task, and an audit trigger exercises `OLD`/`NEW`, JSONB manipulation, and the discipline of writing trigger functions that are safe under concurrency. The pattern: a generic trigger function that diffs `OLD` against `NEW` and writes only the changed keys into an audit table, so it is reusable across tables.

```sql
CREATE TABLE audit_log (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    table_name  text        NOT NULL,
    row_pk      text        NOT NULL,
    changed_at  timestamptz NOT NULL DEFAULT now(),
    changed_by  text        NOT NULL DEFAULT current_user,
    old_values  jsonb,
    new_values  jsonb
);

CREATE OR REPLACE FUNCTION audit_changes() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    delta_old jsonb;
    delta_new jsonb;
BEGIN
    -- keep only keys whose value actually changed
    SELECT jsonb_object_agg(key, value), jsonb_object_agg(key, n.value)
      INTO delta_old, delta_new
    FROM jsonb_each(to_jsonb(OLD)) o
    JOIN jsonb_each(to_jsonb(NEW)) n USING (key)
    WHERE o.value IS DISTINCT FROM n.value;

    IF delta_new IS NOT NULL THEN          -- something changed
        INSERT INTO audit_log(table_name, row_pk, old_values, new_values)
        VALUES (TG_TABLE_NAME, OLD.id::text, delta_old, delta_new);
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_audit_orders
AFTER UPDATE ON orders
FOR EACH ROW EXECUTE FUNCTION audit_changes();
```

Several decisions carry real weight. It is an **`AFTER UPDATE`** trigger, not `BEFORE`: audit logging should record what actually committed, and `AFTER` runs once the row change is settled; a `BEFORE` trigger could see a value another `BEFORE` trigger later mutates. Using `IS DISTINCT FROM` makes the diff NULL-safe (so a column going from a value to NULL is captured, and a no-op update writes nothing). Capturing only the **delta** rather than the full row keeps the audit table compact and makes "what changed" queryable directly.

The trade-offs an interviewer probes: row-level triggers add write latency and run inside the same transaction, so a slow audit insert slows every update and a failure rolls back the business change — keep the function lean and the audit table append-only with no foreign keys. At high write volume, trigger-based auditing competes with the main workload; logical-decoding CDC (Debezium) moves the cost off the hot path and is the architecturally cleaner choice for high-throughput systems. Also note `TG_OP`, `TG_TABLE_NAME`, and `TG_WHEN` are the magic variables that let one function serve INSERT/UPDATE/DELETE across many tables; a production version branches on `TG_OP` to handle inserts (no `OLD`) and deletes (no `NEW`).

#### Q113. [Coding] Write a query to delete duplicate rows, keeping the earliest one per natural key, on a table that lacks a unique constraint.

Deduplication is a common data-cleanup coding task, and the clean solution uses `ctid` (the physical row locator) or a window function to pick survivors. The window-function approach is the most readable and portable: rank duplicates by the tiebreaker and delete everything that isn't rank 1.

```sql
WITH ranked AS (
    SELECT ctid,
           ROW_NUMBER() OVER (PARTITION BY email
                              ORDER BY created_at, ctid) AS rn
    FROM users
)
DELETE FROM users
WHERE ctid IN (SELECT ctid FROM ranked WHERE rn > 1);
```

The key insight is using **`ctid`** as the deletion handle. `ctid` is the tuple's `(block, offset)` physical address, present on every row even without a primary key, so it uniquely identifies each duplicate even when all visible column values are identical. The `ORDER BY created_at, ctid` makes the survivor deterministic — earliest `created_at`, with `ctid` as a stable tiebreaker so two rows with the same timestamp don't both get kept or both get deleted.

The production caveats are important to state. `ctid` is **not stable across `VACUUM FULL`, `CLUSTER`, or updates** (HOT updates move tuples), so you must run the whole CTE-DELETE in a single statement (as above) and never cache `ctid` values to reuse later. On a very large table this single statement can lock and bloat heavily — better to batch it (delete `WHERE rn > 1 LIMIT n` in a loop) like the batched-delete pattern. Finally, the correct end state is to **add the unique constraint** (`ALTER TABLE users ADD CONSTRAINT uq_email UNIQUE (email)`, ideally built with `CREATE UNIQUE INDEX CONCURRENTLY` first then attached) so the duplicates can never return — deduplication without then enforcing uniqueness just defers the problem.

#### Q114. [Coding] Design and write the SQL for a tag/label system: storing tags on entities and querying "entities matching ALL of these tags" efficiently.

This is a schema-design-plus-query coding question that surfaces the array-vs-junction-table trade-off and the use of GIN indexing for set containment. There are two viable designs. The **normalized junction table** is the textbook relational choice:

```sql
CREATE TABLE article_tags (
    article_id bigint REFERENCES articles(id),
    tag        text,
    PRIMARY KEY (article_id, tag)
);
-- "articles having ALL of {sql, performance}":
SELECT article_id
FROM article_tags
WHERE tag IN ('sql', 'performance')
GROUP BY article_id
HAVING COUNT(*) = 2;            -- matched both required tags
```

The **denormalized array** design stores tags inline and uses a GIN index with the `@>` "contains" operator for fast ALL-match (`AND`) and `&&` "overlaps" for ANY-match (`OR`):

```sql
ALTER TABLE articles ADD COLUMN tags text[];
CREATE INDEX idx_articles_tags ON articles USING gin (tags);

SELECT id FROM articles WHERE tags @> ARRAY['sql','performance'];  -- ALL (AND)
SELECT id FROM articles WHERE tags && ARRAY['sql','performance'];  -- ANY (OR)
```

The design reasoning: the junction table normalizes cleanly, lets you attach attributes to the tag relationship (who tagged, when), enforces referential integrity, and gives per-tag statistics, but multi-tag ALL queries need a `GROUP BY ... HAVING COUNT` that can be heavier and needs careful indexing on `(tag, article_id)`. The array design makes ALL/ANY/overlap queries a single index-accelerated operator with a tiny, fast GIN index (`jsonb_path_ops`-style compactness), is ideal when tags are read-mostly and you rarely need per-tag metadata, but it denormalizes (no FK to a tag dictionary, harder to rename a tag globally, no per-tag row statistics).

The senior framing: choose the junction table when tags are first-class entities with their own lifecycle and metadata, or when you need strong integrity; choose the array+GIN when the access pattern is dominated by "find rows containing this set of tags" and tags are simple labels. A hybrid — array column for fast filtering plus a tag dictionary table for canonical names/metadata — is common in production. The `@>` containment query is the highlight answer because it expresses "matches ALL" declaratively and runs as one index scan rather than an aggregate-and-filter.

#### Q115. [Coding] Write a query using `GROUPING SETS`/`ROLLUP` to produce subtotals and a grand total in a single pass, and explain how to distinguish a real NULL from a subtotal NULL.

Multi-level aggregation (subtotals by dimension plus a grand total) is a reporting task that naive solutions handle with `UNION ALL` of multiple `GROUP BY` queries — scanning the table once per level. `GROUPING SETS`, `ROLLUP`, and `CUBE` compute all the levels in **one scan**, which is both faster and the idiomatic answer.

```sql
SELECT region, product,
       SUM(amount) AS total,
       GROUPING(region, product) AS grp   -- bitmask of which dims are aggregated-away
FROM sales
GROUP BY ROLLUP (region, product)          -- (region,product), (region), ()
ORDER BY region NULLS LAST, product NULLS LAST;
```

`ROLLUP (region, product)` produces three grouping sets: the detail `(region, product)`, the per-region subtotal `(region)` with `product` rolled up, and the grand total `()` with both rolled up. `CUBE` instead produces *all* combinations of the dimensions; `GROUPING SETS ((region),(product),())` lets you hand-pick exactly which levels you want.

The crucial correctness detail interviewers test is **NULL ambiguity**: in a subtotal row, the rolled-up dimension shows `NULL`, which is indistinguishable from a genuine `NULL` data value in that column. The `GROUPING()` function resolves this — it returns `1` for a column that is aggregated away in this row (a subtotal/total) and `0` for a column carrying a real value. So you label rows with `CASE WHEN GROUPING(region) = 1 THEN 'All regions' ELSE region END`. Without `GROUPING()`, a region literally named NULL would masquerade as the grand total and corrupt the report. This is the kind of edge case that separates someone who has actually built reporting queries from someone who has only read about `ROLLUP`.

### 🟠 Advanced — extended

#### Q116. [Coding] Implement an idempotent "transactional outbox" pattern in PostgreSQL to reliably publish events without dual-write inconsistency.

The dual-write problem — writing to the database and then publishing to a message broker as two separate operations — is unsolvable atomically because a crash between them either loses the event or publishes one for a transaction that rolled back. The **transactional outbox** fixes this by writing the event into an `outbox` table *in the same transaction* as the business change, so they commit or roll back together; a separate relay then reads the outbox and publishes.

```sql
CREATE TABLE outbox (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    aggregate_id  text        NOT NULL,
    event_type    text        NOT NULL,
    payload       jsonb       NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    published_at  timestamptz
);
CREATE INDEX idx_outbox_unpublished ON outbox (id) WHERE published_at IS NULL;
```

```java
// Business write + event enqueue in ONE transaction (atomic, no dual write)
void placeOrder(Connection c, Order o) throws SQLException {
    c.setAutoCommit(false);
    try {
        insertOrder(c, o);                                   // business change
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO outbox(aggregate_id, event_type, payload) VALUES (?,?,?::jsonb)")) {
            ps.setString(1, o.id()); ps.setString(2, "OrderPlaced");
            ps.setString(3, o.toJson());
            ps.executeUpdate();
        }
        c.commit();                                          // both or neither
    } catch (SQLException e) { c.rollback(); throw e; }
}

// Relay: claim a batch with SKIP LOCKED, publish, mark published — at-least-once
List<Long> relayBatch(Connection c, Publisher broker) throws SQLException {
    c.setAutoCommit(false);
    String claim = """
        SELECT id, event_type, payload FROM outbox
        WHERE published_at IS NULL
        ORDER BY id
        FOR UPDATE SKIP LOCKED
        LIMIT 100
        """;
    List<Long> ids = new ArrayList<>();
    try (PreparedStatement ps = c.prepareStatement(claim);
         ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
            broker.publish(rs.getString("event_type"), rs.getString("payload")); // may dup on retry
            ids.add(rs.getLong("id"));
        }
    }
    if (!ids.isEmpty()) {
        try (PreparedStatement up = c.prepareStatement(
                "UPDATE outbox SET published_at = now() WHERE id = ANY(?)")) {
            up.setArray(1, c.createArrayOf("bigint", ids.toArray()));
            up.executeUpdate();
        }
    }
    c.commit();
    return ids;
}
```

The semantics to articulate: this guarantees **at-least-once** delivery, not exactly-once. If the relay publishes then crashes before `UPDATE ... published_at`, the event republishes on restart — so consumers must be **idempotent** (dedupe on an event id). The **partial index** `WHERE published_at IS NULL` keeps the relay's scan O(unpublished) even as the outbox grows to millions of historical rows; a retention job prunes old published rows. `FOR UPDATE SKIP LOCKED` lets multiple relay instances run concurrently without double-claiming. The architecturally superior variant uses **logical decoding / CDC (Debezium)** to tail the outbox table's WAL instead of polling, eliminating the relay's query load and latency entirely — the polling relay shown here is the simple, dependency-free version that works everywhere. The whole point, and the line to deliver, is that the event and the state change share one ACID transaction, so the system can never be in a state where the order exists but its event was lost (or vice versa).

#### Q117. [Coding] Implement an inventory-decrement that must never oversell under high concurrency. Show the locking approach and why a naive read-modify-write fails.

This is a concurrency-correctness coding question that exposes whether a candidate understands lost updates and write skew. The naive approach — `SELECT qty`, check in the app, then `UPDATE` — is a textbook **lost update / write skew** bug: two concurrent transactions both read `qty = 1`, both decide it's fine, and both decrement, overselling to -1. Under the default Read Committed isolation, the two `SELECT`s don't conflict, so nothing prevents it.

There are three correct fixes, and naming the trade-offs is the point:

```sql
-- (1) Atomic conditional UPDATE — simplest, best for a single row. No app-side check.
UPDATE inventory
SET qty = qty - :n
WHERE sku = :sku AND qty >= :n;        -- row affected = success; 0 rows = insufficient
-- The WHERE qty >= :n + row lock makes the check-and-decrement atomic.

-- (2) SELECT ... FOR UPDATE — pessimistic lock when you must read-then-decide in the app
BEGIN;
  SELECT qty FROM inventory WHERE sku = :sku FOR UPDATE;  -- locks the row
  -- ... business logic ...
  UPDATE inventory SET qty = qty - :n WHERE sku = :sku;
COMMIT;

-- (3) SERIALIZABLE isolation — detects the conflict, aborts one txn with 40001 (retry)
```

The atomic conditional `UPDATE` (option 1) is the best answer for the common case: it does the read, the comparison, and the write as one statement, and PostgreSQL takes a row lock for the duration of the `UPDATE`, so a concurrent decrement blocks until the first commits and then re-evaluates `qty >= :n` against the updated value. You detect "out of stock" by checking `executeUpdate() == 0` (zero rows affected). This is lock-efficient (only the one row), needs no retry loop, and can't oversell.

`SELECT ... FOR UPDATE` (option 2) is right when the decision genuinely requires application logic between the read and the write — it pessimistically locks the row so the read value stays valid through the update, at the cost of holding the lock for the whole transaction (longer contention). `SERIALIZABLE` (option 3) is the most general (it catches write skew across *different* rows, e.g., a constraint summing several rows) but requires a `40001` retry loop and aborts under contention rather than blocking. The senior judgment: for a single-row counter, prefer the atomic conditional `UPDATE` (no locks held across round-trips, no retries); escalate to `FOR UPDATE` only when multi-statement logic forces it, and to `SERIALIZABLE` only when the invariant spans multiple rows that no single-row lock can protect. Never trust an app-side `if (qty >= n)` between a `SELECT` and an `UPDATE` — that's the bug.

#### Q118. [Coding] Write a function that does a "fuzzy" search ranked by similarity using `pg_trgm`, and explain how the index makes `LIKE '%term%'` fast.

Substring and typo-tolerant search is a frequent advanced task because plain `LIKE '%term%'` (leading wildcard) cannot use a normal B-tree index and forces a sequential scan. The `pg_trgm` extension solves both fuzzy matching *and* leading-wildcard `LIKE` by indexing **trigrams** (three-character sequences) with a GIN or GiST index.

```sql
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- GIN trigram index: accelerates similarity (%, <->) AND LIKE/ILIKE '%term%'
CREATE INDEX idx_products_name_trgm ON products USING gin (name gin_trgm_ops);

-- Ranked fuzzy search: closest matches first
SELECT id, name,
       similarity(name, :q) AS sim
FROM products
WHERE name % :q                 -- '%' = similarity above pg_trgm.similarity_threshold
ORDER BY name <-> :q            -- '<->' = distance; smallest = most similar (KNN-orderable)
LIMIT 20;
```

The mechanism to explain: `pg_trgm` breaks each string into its set of trigrams (e.g., `"cat"` → `{"  c"," ca","cat","at "}`), and the GIN index is an inverted index from each trigram to the rows containing it. A query for `name LIKE '%cat%'` is decomposed into the trigrams of `'cat'`, the index finds the candidate rows containing those trigrams, and only those candidates are rechecked against the actual `LIKE` pattern — turning a full table scan into an index lookup over a small candidate set. The same trigram overlap drives `similarity()` (count of shared trigrams over total), the `%` operator (above a tunable threshold), and the `<->` distance operator used for KNN-ordered "most similar first" results.

The trade-offs and pitfalls: GIN trigram indexes are large and slower to update, so they suit read-heavy search columns; GiST trigram indexes are smaller and support the `<->` KNN order operator efficiently for `LIMIT`-style "top N closest." Trigram search degrades on very short search terms (fewer than 3 characters produce few/no trigrams) and is language-agnostic substring matching, **not** linguistic full-text search — for stemming, stop-words, and ranking by term frequency you want `tsvector`/`tsquery` instead. The right framing: use `pg_trgm` for typo tolerance, autocomplete, "did you mean," and fast `LIKE '%x%'`; use native FTS (`to_tsvector`) for document/word search. They are complementary, and strong systems sometimes index both.

#### Q119. [Coding] Implement a gapless sequence generator (e.g., for invoice numbers) and explain why the built-in sequence cannot do this.

This question tests whether a candidate understands *why* regular sequences have gaps (covered in Q50: `nextval()` is non-transactional and never rolls back) and can correctly trade concurrency for the gapless guarantee that legal/accounting requirements sometimes demand. Because a sequence intentionally hands out a value even if the transaction aborts, it can never be gapless. The only way to get gapless numbering is to **serialize the allocation through a lockable counter row** so the number is consumed inside the transaction and rolls back with it.

```sql
CREATE TABLE invoice_counter (
    series  text PRIMARY KEY,
    last_no bigint NOT NULL DEFAULT 0
);

CREATE OR REPLACE FUNCTION next_invoice_no(p_series text) RETURNS bigint
LANGUAGE sql AS $$
    UPDATE invoice_counter
    SET last_no = last_no + 1
    WHERE series = p_series
    RETURNING last_no;
$$;
```

```sql
BEGIN;
  -- allocates AND row-locks the counter; concurrent callers block here until COMMIT
  SELECT next_invoice_no('2026');           -- e.g. 1043
  INSERT INTO invoices(number, ...) VALUES (1043, ...);
COMMIT;   -- if this rolls back, the counter increment rolls back too -> no gap
```

Why this is gapless and the built-in sequence is not: the `UPDATE ... RETURNING` takes a **row lock** on the single counter row for the rest of the transaction, so any concurrent invoice creation blocks until the holder commits or rolls back. If the transaction rolls back, the `last_no` increment is undone along with everything else — the number is returned to the pool. With a real sequence, `nextval()` would have permanently burned the number. So gaplessness requires that number allocation be *transactional*, which inherently means *serialized*.

The cost is exactly that serialization — this is the explicit trade-off to state. Every invoice creation for a series is now single-threaded through one row lock, so under high concurrency callers queue and throughput drops; you also risk lock contention and longer transactions if other slow work sits between the allocation and the commit (keep the transaction tight — allocate the number last, just before commit). Advisory locks (`pg_advisory_xact_lock`) are an alternative locking mechanism but have the same serialization cost. The senior conclusion: never force gapless numbering unless a real requirement (tax/audit law) demands it; if it's merely cosmetic, use a normal `IDENTITY`/sequence and accept gaps. When it is required, scope the counter as narrowly as possible (per-series, per-year) so contention is partitioned, and keep the allocating transaction microscopic.

#### Q120. [Coding] Write SQL that uses an `EXCLUSION` constraint to prevent overlapping bookings for the same resource. Why is this better than application-level checks?

Preventing overlapping reservations (the same room booked for overlapping time ranges) is a classic problem where application checks race and fail under concurrency, and PostgreSQL has a purpose-built feature: the **exclusion constraint** with range types and the `btree_gist` extension. It declares, at the schema level, that no two rows may have *overlapping* ranges for the *same* resource — enforced atomically by the engine, immune to races.

```sql
CREATE EXTENSION IF NOT EXISTS btree_gist;   -- lets GiST index the equality part (room_id)

CREATE TABLE bookings (
    id       bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    room_id  bigint NOT NULL,
    during   tstzrange NOT NULL,
    EXCLUDE USING gist (
        room_id WITH =,          -- same room
        during  WITH &&          -- overlapping time range  -> rejected
    )
);

-- This succeeds; a concurrent overlapping insert raises a conflict error (23P01)
INSERT INTO bookings(room_id, during)
VALUES (7, tstzrange('2026-06-16 09:00+00', '2026-06-16 10:00+00', '[)'));
```

The reasoning for why this beats application logic: an app-level "SELECT for overlaps, then INSERT if none" is a **check-then-act race** — two concurrent requests both find no overlap and both insert, double-booking the room. You'd have to wrap it in `SERIALIZABLE` with retries or take a coarse table lock. The exclusion constraint instead enforces the invariant **inside the engine via a GiST index**: when a row is inserted, PostgreSQL checks the index for any existing row where `room_id =` and `during &&`, and if one exists it raises a `23P01` exclusion violation. This is atomic and concurrency-safe with no application coordination, the same way a `UNIQUE` constraint prevents duplicate keys — in fact an exclusion constraint with all `WITH =` operators *is* a unique constraint; `&&` generalizes "equal" to "overlaps."

The details that demonstrate mastery: `btree_gist` is required because the `room_id WITH =` (scalar equality) part needs GiST support that the extension provides; the range type (`tstzrange`) with explicit bounds `'[)'` (inclusive start, exclusive end) is what makes back-to-back bookings — one ending exactly when the next starts — *not* overlap, which is almost always the desired semantics. You handle the violation in the app by catching SQLSTATE `23P01` and returning "slot taken." Trade-offs: GiST exclusion checks are a bit heavier than a plain unique B-tree check, and the constraint can't be `DEFERRABLE` in a way that helps bulk loads much, but for correctness-critical scheduling (rooms, equipment, doctor appointments, IP-range allocation with `inet`) it is the single most robust tool PostgreSQL offers and is strongly preferred over any application-side guard.

#### Q121. [Coding] Write a query to find the median and 95th percentile of response times, and explain the difference between `percentile_cont` and `percentile_disc`.

Percentiles are a routine analytics task where many candidates incorrectly reach for `AVG` (which hides tail latency) or hand-roll ranking logic. PostgreSQL provides **ordered-set aggregate functions** built for exactly this: `percentile_cont` and `percentile_disc`, invoked with the special `WITHIN GROUP (ORDER BY ...)` syntax.

```sql
SELECT
    percentile_cont(0.5)  WITHIN GROUP (ORDER BY response_ms) AS p50_interpolated,
    percentile_disc(0.5)  WITHIN GROUP (ORDER BY response_ms) AS p50_actual_value,
    percentile_cont(0.95) WITHIN GROUP (ORDER BY response_ms) AS p95,
    percentile_cont(ARRAY[0.5,0.9,0.99]) WITHIN GROUP (ORDER BY response_ms) AS p_multi
FROM requests
WHERE created_at >= now() - interval '1 hour';
```

The distinction interviewers want: **`percentile_cont`** (continuous) treats the data as a continuous distribution and **interpolates** between the two nearest values, so the median of `{10, 20}` is `15` — a value that may not exist in the data. **`percentile_disc`** (discrete) returns an **actual data point** — the smallest value whose cumulative distribution is ≥ the requested fraction — so the median of `{10, 20}` is `10`. For latency SLOs and most metrics, `percentile_cont` is conventional (it matches how monitoring tools report p95/p99); `percentile_disc` is correct when you must return a value that genuinely occurred (e.g., "the actual order amount at the 90th percentile").

Two practical points elevate the answer. First, you can pass an **array of fractions** (`percentile_cont(ARRAY[...])`) to compute many percentiles in one pass — far cheaper than separate aggregates. Second, these are **exact** percentiles requiring a full sort of the group, which is O(n log n) and memory-heavy on huge datasets; for streaming/approximate percentiles over billions of rows at low cost, the `t-digest` or `hll`/`tdigest` extensions (or pre-aggregated histograms) trade a small accuracy bound for massive speed — naming that scaling escape hatch shows you've used these in production where computing exact p99 over a day of traffic on every dashboard refresh would be prohibitive.

### 🔴 Expert — extended

#### Q122. [Coding] Write a custom aggregate function in PostgreSQL (state transition + final function). When is a custom aggregate the right tool?

PostgreSQL lets you define your own aggregates via `CREATE AGGREGATE`, supplying a **state transition function** (`sfunc`) called once per input row to fold the value into an accumulating state, and an optional **final function** (`ffunc`) to transform the final state into the result. This is the same machinery `sum`/`avg` use, and implementing one shows deep understanding of how aggregation actually works. Here is a numerically-stable geometric mean, which the built-ins don't provide:

```sql
-- state: (running sum of ln(x), count). Summing logs avoids float overflow on large products.
CREATE FUNCTION geomean_accum(state float8[], x float8) RETURNS float8[]
LANGUAGE sql IMMUTABLE AS $$
    SELECT ARRAY[ state[1] + ln(x), state[2] + 1 ]
$$;

CREATE FUNCTION geomean_final(state float8[]) RETURNS float8
LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE WHEN state[2] = 0 THEN NULL ELSE exp(state[1] / state[2]) END
$$;

CREATE AGGREGATE geomean(float8) (
    SFUNC     = geomean_accum,
    STYPE     = float8[],
    INITCOND  = '{0,0}',
    FINALFUNC = geomean_final,
    PARALLEL  = SAFE,                 -- allows partial aggregation across workers...
    COMBINEFUNC = geomean_combine     -- ...if you also supply a combine func (below)
);

CREATE FUNCTION geomean_combine(a float8[], b float8[]) RETURNS float8[]
LANGUAGE sql IMMUTABLE AS $$ SELECT ARRAY[ a[1]+b[1], a[2]+b[2] ] $$;
```

The design reasoning: accumulating `ln(x)` and exponentiating the mean at the end avoids the overflow you'd get from multiplying thousands of values directly — a real numerical-stability concern that justifies a custom aggregate over `exp(avg(ln(x)))` inline only when you want it as a reusable, composable aggregate usable with `GROUP BY`, `FILTER`, and window frames. Declaring `IMMUTABLE` lets the planner cache and optimize; declaring `PARALLEL SAFE` plus a `COMBINEFUNC` is what allows **partial aggregation in parallel workers** (each worker aggregates its slice into a partial state, the leader merges them via `combinefunc`) — without it the aggregate forces serial execution, which is the most common reason a custom aggregate silently kills parallelism on big tables.

When it's the right tool: custom aggregates shine for domain-specific reductions that compose with SQL's aggregation framework — statistical measures the core lacks, custom "last value by timestamp" (`first`/`last` aggregates), merging probabilistic sketches (HLL union), or building a custom JSON/array rollup. The alternatives to weigh: a plain SQL expression (`exp(avg(ln(x)))`) is simpler if you don't need reuse or window support; a window function handles row-relative computation; and for one-off analytics, doing it in the application may be clearer. Reach for `CREATE AGGREGATE` when the reduction is reused across queries, must work inside `GROUP BY`/window/`FILTER`, and benefits from running close to the data — and always provide `COMBINEFUNC` + `PARALLEL SAFE` so it doesn't become a parallelism barrier.

#### Q123. [Coding] Implement a "get-or-create" hot-path that avoids both the unique-violation churn and the lock contention of naive approaches under heavy concurrency.

"Get-or-create" (a.k.a. find-or-insert) looks trivial but is a notorious concurrency trap. The naive `SELECT; if missing INSERT` races: two sessions both miss, both insert, one gets a unique violation. The "just `INSERT ... ON CONFLICT DO NOTHING` then `SELECT`" approach is correct but has a subtle cost worth dissecting, and the truly hot path needs careful ordering.

```sql
-- Correct, race-free get-or-create returning the row in one round trip where possible.
-- Step 1: try to read (the overwhelmingly common case on a hot key).
SELECT id FROM tags WHERE name = :name;

-- Step 2 (only if missing): insert-or-ignore, then read again.
WITH ins AS (
    INSERT INTO tags(name) VALUES (:name)
    ON CONFLICT (name) DO NOTHING
    RETURNING id
)
SELECT id FROM ins
UNION ALL
SELECT id FROM tags WHERE name = :name      -- fallback when ON CONFLICT skipped the insert
LIMIT 1;
```

The non-obvious expert points. First, **`ON CONFLICT DO NOTHING` does not return the conflicting row** — `RETURNING` is empty when the insert is skipped, which is why the `UNION ALL` with a follow-up `SELECT` is required to get the id in all cases. Using `DO UPDATE SET name = EXCLUDED.name` instead would make `RETURNING` always fire, but at the cost of a **dead tuple and WAL on every conflict** (a real update of an unchanged row) — on a hot key hit millions of times, that manufactures bloat and write amplification for nothing, so `DO NOTHING` + read-back is preferred for pure get-or-create.

Second, **read-first ordering matters for the hot path**: the steady state is "the row already exists," so attempting the `SELECT` first avoids generating a doomed `INSERT` (and its WAL/xid consumption and index churn) on every call. Some argue insert-first to avoid a race window, but with `ON CONFLICT` there is no correctness window either way — read-first is purely an optimization to skip work on the common path. Third, **every failed `INSERT` still burns a transaction id and may briefly take an insertion lock**, so a high-churn get-or-create on a contended key can become a subtransaction/XID hotspot; if the value space is small and hot (a handful of tags hit constantly), caching resolved ids in the application removes the database round-trip entirely, which is the real fix at extreme scale. The senior framing: `ON CONFLICT DO NOTHING` makes it correct and race-free; read-first ordering and avoiding `DO UPDATE` make it cheap; and an application-side cache makes it disappear from the hot path.

#### Q124. [Coding] Write a recursive query to traverse a graph (e.g., friend-of-friend up to N hops) with cycle protection, and discuss its scaling limits.

Graph traversal in SQL — shortest reachable set within N hops — extends the recursive-CTE pattern with depth bounding and mandatory cycle detection, and the interesting part is articulating where pure SQL stops being the right tool. Given an `edges(src, dst)` table:

```sql
WITH RECURSIVE reachable AS (
    SELECT dst AS node, 1 AS hops, ARRAY[:start, dst] AS path
    FROM edges
    WHERE src = :start
  UNION ALL
    SELECT e.dst, r.hops + 1, r.path || e.dst
    FROM edges e
    JOIN reachable r ON e.src = r.node
    WHERE r.hops < :max_hops              -- depth bound
      AND NOT e.dst = ANY(r.path)         -- cycle guard: don't revisit nodes on this path
)
SELECT node, MIN(hops) AS shortest_hops
FROM reachable
GROUP BY node
ORDER BY shortest_hops;
```

The correctness mechanics: the `path` array records the nodes visited along each branch, and `NOT e.dst = ANY(r.path)` prevents revisiting a node *on the same path*, which both stops infinite loops on cyclic graphs and avoids redundant expansion. `r.hops < :max_hops` bounds the depth. The final `GROUP BY node MIN(hops)` collapses the many paths that may reach the same node into its shortest distance. PostgreSQL 14+'s native `CYCLE node SET is_cycle USING path` clause does the path-tracking and cycle marking for you, but the explicit array form makes the mechanism visible and works on all versions.

The scaling discussion is what marks an expert answer. This is effectively a breadth-first search where each level **fans out multiplicatively** — on a dense social graph, "friends of friends of friends" can touch millions of nodes, and the working table (and the per-row `path` arrays) explode in memory; recursive CTEs are always materialized, can't prune to "top-K nearest" early, and have no graph-aware indexing, so beyond a few hops on dense graphs they become slow or OOM. The `path`-as-array cycle guard is itself O(path length) per row, adding cost. The senior conclusion: PostgreSQL recursive CTEs are perfect for **shallow, sparse hierarchies** (org charts, category trees, bill-of-materials a few levels deep) and bounded reachability on modest graphs, but for deep traversals, shortest-path, centrality, or pattern matching on large dense graphs, a purpose-built **graph database** (Neo4j) or the **Apache AGE** extension (openCypher on PostgreSQL) is the right tool — and recognizing that boundary, rather than forcing a 6-hop CTE on a billion-edge graph, is the point.

#### Q125. [Coding] Implement a robust distributed lock using PostgreSQL advisory locks, and explain the failure modes versus a dedicated lock service.

Advisory locks are application-defined locks that PostgreSQL tracks but attaches no meaning to — perfect for coordinating "only one worker runs this job" across processes without a separate Redis/ZooKeeper. The expert distinction is **session-level** versus **transaction-level** advisory locks and their very different lifecycle/failure semantics.

```sql
-- Transaction-scoped: auto-released at COMMIT/ROLLBACK — safest, no leak risk.
SELECT pg_advisory_xact_lock(hashtext('nightly-report'));   -- blocks until acquired
-- ... do the exclusive work inside this transaction ...
COMMIT;                                                       -- lock auto-released

-- Try-lock variant (non-blocking): returns true/false, run job only if you won.
SELECT pg_try_advisory_xact_lock(hashtext('nightly-report')) AS got_lock;

-- Session-scoped: held until pg_advisory_unlock or disconnect — must release explicitly.
SELECT pg_advisory_lock(42);    -- ...   SELECT pg_advisory_unlock(42);
```

```java
boolean runIfLeader(Connection c, String jobKey, Runnable job) throws SQLException {
    c.setAutoCommit(false);
    try (PreparedStatement ps = c.prepareStatement("SELECT pg_try_advisory_xact_lock(hashtext(?))")) {
        ps.setString(1, jobKey);
        try (ResultSet rs = ps.executeQuery()) {
            rs.next();
            if (!rs.getBoolean(1)) { c.rollback(); return false; }  // someone else holds it
        }
    }
    job.run();          // exclusive section
    c.commit();         // releases the xact lock atomically with the work
    return true;
}
```

Why `pg_advisory_xact_lock` is the robust default: it is released **automatically** when the transaction ends — including on crash, network drop, or `pg_terminate_backend` — so it cannot leak the way a session-level lock can if a worker dies without calling `pg_advisory_unlock`. The `try` variant gives non-blocking leader election (run only if you grabbed the lock). Hashing a string key with `hashtext()` into the required `bigint` is idiomatic, but note the **collision risk**: two different keys can hash to the same 64-bit value and falsely contend; for critical use, use the two-`int` form `pg_advisory_lock(classid, objid)` with a namespace, or accept the negligible collision probability.

The failure modes versus a dedicated service are the senior content. (1) Advisory locks live on **one PostgreSQL node** — if you have multiple primaries or shards, they don't coordinate across them, so they're a *single-database* lock, not a truly distributed one. (2) On **failover**, the standby has no knowledge of the primary's advisory locks (they aren't replicated), so after promotion the lock effectively vanishes — two workers could briefly both think they're leader, exactly the split-brain concern. (3) They add load and connection pressure to your database. A dedicated lock service (ZooKeeper, etcd, Consul, Redis Redlock) is built for consensus, fencing tokens, and lease expiry across nodes. The pragmatic conclusion: advisory locks are excellent for *intra-cluster* coordination — singleton cron jobs, serializing a migration, leader election among workers all talking to the *same* database — and you should reach for them there because they're free and transactional; but for cross-region or cross-database coordination with strict correctness under failover, use a real consensus-backed lock manager. Always prefer the transaction-scoped variant to avoid leaks, and never hold an advisory lock across user think-time or external I/O.

#### Q126. [Coding] Write a query to deduplicate a stream while keeping the latest version per key using `DISTINCT ON`, and contrast it with the window-function approach.

`DISTINCT ON` is a PostgreSQL-specific extension that elegantly solves "one row per group, picking a specific one" — the latest event per user, the most recent price per product — in a single, highly optimizable construct. Many candidates only know the window-function form, so showing `DISTINCT ON` and knowing when each wins is a differentiator.

```sql
-- DISTINCT ON: keep the most recent row per user_id.
SELECT DISTINCT ON (user_id)
       user_id, status, event_time
FROM events
ORDER BY user_id, event_time DESC;     -- ORDER BY MUST lead with the DISTINCT ON key(s)

-- Equivalent window-function form:
SELECT user_id, status, event_time
FROM (
    SELECT *, ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY event_time DESC) AS rn
    FROM events
) s
WHERE rn = 1;
```

The mechanics and the critical gotcha: `DISTINCT ON (user_id)` keeps the **first row of each `user_id` group as ordered by the `ORDER BY`** — so the `ORDER BY` *must begin with* the `DISTINCT ON` expressions, then add the tiebreaker (`event_time DESC`) that decides which row survives. Forgetting to lead the `ORDER BY` with the distinct keys is a parse error or, worse, a logically wrong result. To get the *oldest* per user you flip to `event_time ASC`.

The trade-off to articulate: `DISTINCT ON` is terser and the planner often executes it more cheaply — it can satisfy it with a single index scan when there's a matching index on `(user_id, event_time DESC)`, reading just the first row of each group via a **skip/loose-index-scan-like** plan and stopping early, which is very fast. The `ROW_NUMBER()` version is SQL-standard (portable to other databases) and more flexible — it can keep the top *N* per group (`WHERE rn <= 3`), apply complex tiebreak logic, or return the rank itself, none of which `DISTINCT ON` can do (it's strictly top-1). The senior judgment: use `DISTINCT ON` for the common "latest one per key" because it's concise and index-friendly on PostgreSQL; switch to the window function when you need top-N, need portability, or need the rank value. Both are a single scan; the window form materializes ranks for every row before filtering, so on huge groups `DISTINCT ON` with the right index can short-circuit and win.

#### Q127. [Coding] Design the SQL and ingestion pattern for a time-series metrics table optimized for write throughput and time-range reads. What are your indexing and partitioning choices?

This is a design-plus-coding question combining partitioning, BRIN indexing, and write-path tuning — a realistic system-design slice. The workload is append-heavy (constant inserts of `(metric, ts, value)`), reads are almost always **time-bounded** ("last 24h for metric X"), and old data is dropped wholesale. That shape dictates the design.

```sql
CREATE TABLE metrics (
    metric_id int         NOT NULL,
    ts        timestamptz NOT NULL,
    value     double precision NOT NULL
) PARTITION BY RANGE (ts);

-- one partition per day (automated by pg_partman in production)
CREATE TABLE metrics_2026_06_16 PARTITION OF metrics
    FOR VALUES FROM ('2026-06-16') TO ('2026-06-17');

-- BRIN on ts: tiny index, ideal because rows are naturally clustered by insert time
CREATE INDEX idx_metrics_ts_brin ON metrics USING brin (ts) WITH (pages_per_range = 32);
-- B-tree only on the dimension we filter by alongside time
CREATE INDEX idx_metrics_metric_ts ON metrics_2026_06_16 (metric_id, ts);
```

The reasoning behind each choice. **Range partitioning by time** is the backbone: it enables partition pruning so a "last 24h" query touches one or two partitions instead of the whole dataset, keeps each partition's indexes small and autovacuum fast, and — critically — turns data retention into an instant `DROP TABLE`/`DETACH PARTITION` instead of a massive `DELETE` that would bloat and generate enormous WAL. **BRIN on `ts`** is the standout: because rows are inserted in roughly time order, each block-range's min/max `ts` is tight and non-overlapping, so a time-range scan reads only the relevant block ranges; a BRIN index is *orders of magnitude smaller* than a B-tree (kilobytes vs gigabytes) and adds almost nothing to insert cost — exactly right for a write-heavy table where a full B-tree on `ts` would be a large maintenance burden. The B-tree on `(metric_id, ts)` serves the common "this metric over this time window" filter where you need point lookup on the dimension.

The write-path and operational tuning that completes the answer: ingest with **`COPY`** or multi-row `INSERT` batches (not row-by-row) to minimize per-statement overhead and WAL; consider an **`UNLOGGED`** staging table or `synchronous_commit = off` on the ingest transactions if a small recovery window is acceptable for the raw feed (huge throughput gain); set a higher `fillfactor` is unnecessary since there are no updates (append-only means no HOT concern, default 100 is fine). Watch out for the partitioning pitfalls: too many partitions (per-minute over years → tens of thousands) inflate planning time and LockManager contention, so pick a partition granularity (per-day or per-week) that keeps the count in the low hundreds, and automate creation/retention with **`pg_partman`**. For very high cardinality or compression needs, the **TimescaleDB** extension layers automatic chunking, columnar compression, and continuous aggregates on exactly this model — naming it shows you know where native PostgreSQL hands off to a specialized tool.

#### Q128. [Coding] Write the SQL to implement optimistic concurrency control with a version column, and show how the application detects and handles a conflicting concurrent update.

Optimistic concurrency control (OCC) is the standard pattern for "edit a record someone else might be editing" without holding locks across user think-time — essential for web apps where a user loads a form, ponders, then saves minutes later. Holding a row lock that whole time (pessimistic) would be disastrous; OCC instead detects conflicts at write time using a **version column**.

```sql
-- Schema: a version (or updated_at) column bumped on every write.
ALTER TABLE documents ADD COLUMN version int NOT NULL DEFAULT 1;

-- The update succeeds only if the row is still at the version we read.
UPDATE documents
SET title   = :new_title,
    body    = :new_body,
    version = version + 1
WHERE id = :id
  AND version = :version_i_read;     -- the optimistic check
-- rows affected = 1  -> success;   0 -> someone else changed it first (conflict)
```

```java
boolean save(Connection c, long id, int seenVersion, String title) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("""
            UPDATE documents SET title=?, version=version+1
            WHERE id=? AND version=?""")) {
        ps.setString(1, title); ps.setLong(2, id); ps.setInt(3, seenVersion);
        int n = ps.executeUpdate();
        if (n == 0) throw new OptimisticLockException(id);  // reload + merge + retry / ask user
        return true;
    }
}
```

The mechanism and its correctness: the application reads the row (capturing `version`), the user edits, and on save the `UPDATE`'s `WHERE version = :seen` either matches (no one else wrote → 1 row updated, version bumped) or matches nothing (someone committed a change, bumping the version → 0 rows updated). Detecting the conflict is simply checking the affected-row count is zero. There is **no lock held during think-time** — the only contention is the instantaneous `UPDATE` itself. This is why OCC scales for read-heavy, conflict-rare workloads: the common case (no concurrent edit) pays nothing, and only genuine conflicts incur a retry.

The trade-offs and edge cases worth raising. OCC is ideal when **conflicts are rare**; if many users hammer the same row, optimistic retries thrash and pessimistic locking (`SELECT ... FOR UPDATE`) or a serialized counter becomes better. On conflict the application must decide policy: blindly retry (last-write-wins), reload-and-merge (three-way merge of fields), or surface the conflict to the user ("this record changed; review and resave") — the right choice is domain-specific and is the interesting product decision. Using `version int` is cleaner than `updated_at` because timestamps can collide at sub-millisecond resolution and clock skew across nodes makes them unreliable as a conflict token — a monotonic integer is unambiguous. This pattern is exactly what ORMs like Hibernate/JPA implement with `@Version`; knowing it's "just" a conditional `UPDATE` on a version column, and that the conflict signal is the zero-rows-affected result, is the substance.

#### Q129. [Behavioral] Tell me about a time you made a database design or architecture decision that you later had to revisit. How did you handle the trade-offs and the eventual change?

**Situation (STAR):** A framing that resonates at staff level: early in a product's life I chose a single shared PostgreSQL schema with a `tenant_id` column for a multi-tenant SaaS, over schema-per-tenant or separate databases. At the time it was the right call — a handful of tenants, a small team, and we needed to ship; the shared-schema model kept migrations trivial (one `ALTER TABLE`), connection pooling simple, and operations light. **Task:** Two years later we'd grown to thousands of tenants with a few very large "whale" accounts, and the largest tenant's data was degrading query performance and vacuum behavior for everyone on shared tables; a noisy-neighbor incident made it urgent to revisit the original decision.

**Action:** I deliberately avoided the temptation to declare the original design "wrong" — it had been correct for its context, and the team had built enormous velocity on it. Instead I quantified the actual pain (per-tenant query latency distributions from `pg_stat_statements` tagged by tenant, bloat and autovacuum lag on the hot tables) and scoped the *minimal* change that addressed it. Rather than a risky big-bang migration to schema-per-tenant, we **partitioned the hottest tables by `tenant_id` (LIST/HASH)** so whale tenants landed on isolated partitions with their own indexes and vacuum cadence, and we moved the very largest tenants' analytics off the primary via logical replication. Because we'd had `tenant_id` on every table from day one (a forward-looking part of the original design), the partitioning path was open without an application rewrite. I socialized the trade-offs explicitly with the team and stakeholders: we accepted more complex DDL and more partitions in exchange for isolation, and we kept the door open to true sharding only if write volume later demanded it.

**Result:** The noisy-neighbor latency spikes disappeared, autovacuum kept up per-partition, and we did it incrementally with no downtime and no application code change. **What this signals:** the senior lessons I'd emphasize are (1) the original decision was *right for its time* — judging past choices by present scale is a junior reflex; (2) designing with a cheap escape hatch (`tenant_id` everywhere) made the future pivot low-cost, which is the real craft — *reversibility* over premature optimization; (3) I changed the minimum necessary based on measured evidence rather than rewriting on instinct; and (4) I led the trade-off conversation transparently so the team understood *why* the model was evolving, turning a potential "we built it wrong" morale hit into a "we built it to evolve" win. The meta-point interviewers look for is comfort with the fact that good architecture is a sequence of context-appropriate decisions with deliberate optionality, not a single permanent choice.

#### Q130. [Coding] Write a query to compute period-over-period growth (this month vs last month) per category using `LAG`, handling missing periods correctly.

Period-over-period comparison is a staple analytics task that trips people up on **missing periods** — if a category had no sales last month, a naive self-join silently drops it. The robust approach generates a complete period spine, left-joins the data, then uses `LAG` partitioned by category to reach the prior period.

```sql
WITH monthly AS (
    SELECT category,
           date_trunc('month', sold_at) AS month,
           SUM(amount)                  AS revenue
    FROM sales
    GROUP BY category, date_trunc('month', sold_at)
),
spine AS (   -- every (category, month) combination so gaps become explicit zero/NULL rows
    SELECT c.category, m.month
    FROM (SELECT DISTINCT category FROM monthly) c
    CROSS JOIN generate_series(date '2026-01-01', date '2026-12-01', interval '1 month') m(month)
)
SELECT s.category, s.month,
       COALESCE(mo.revenue, 0)                               AS revenue,
       LAG(COALESCE(mo.revenue, 0)) OVER w                   AS prev_revenue,
       ROUND( (COALESCE(mo.revenue,0) - LAG(COALESCE(mo.revenue,0)) OVER w)
              / NULLIF(LAG(COALESCE(mo.revenue,0)) OVER w, 0) * 100, 1) AS pct_change
FROM spine s
LEFT JOIN monthly mo ON mo.category = s.category AND mo.month = s.month
WINDOW w AS (PARTITION BY s.category ORDER BY s.month)
ORDER BY s.category, s.month;
```

The reasoning that demonstrates rigor: the `CROSS JOIN` of distinct categories with a `generate_series` of months builds a **dense spine** so that a month with no sales appears as a real row (revenue 0 via `COALESCE`) rather than vanishing — without it, `LAG` would jump from, say, January straight to March and report a wrong "month-over-month" change spanning two months. `LAG(...) OVER (PARTITION BY category ORDER BY month)` correctly resets at each category boundary so categories don't bleed into each other. `NULLIF(prev, 0)` guards the division so a category whose prior month was zero yields `NULL` (undefined growth) rather than a divide-by-zero error.

The `WINDOW w AS (...)` named-window clause is a nice touch — it defines the frame once and reuses it across multiple window expressions, which is cleaner and ensures they share the same partition/order so the planner computes them in one pass. The whole query is a single grouped scan plus an ordered window pass; an index on `(category, sold_at)` supports the aggregation. The senior point: the *easy* part is `LAG`; the part that separates a correct production query from a subtly broken one is recognizing that gaps in the time series will corrupt period-over-period math unless you densify the periods first.

#### Q131. [Coding] Implement safe schema migration code that adds a NOT NULL column with a default and a foreign key to a huge table without a long lock. Show the exact step sequence.

Adding constraints to a billion-row table is where migrations cause outages, and the expert answer is a precise sequence of weak-lock operations rather than one blocking `ALTER`. The naive `ALTER TABLE big ADD COLUMN status text NOT NULL DEFAULT 'active' REFERENCES statuses(name)` can rewrite the table and validate the FK under `ACCESS EXCLUSIVE`, freezing all access. Here is the safe decomposition:

```sql
-- Always cap how long DDL will wait for its lock, so it fails fast instead of
-- queueing behind a long query and stalling ALL new traffic behind it.
SET lock_timeout = '3s';

-- 1. Add the column with a CONSTANT default — instant since PG 11 (metadata-only,
--    stored as a "missing value", old rows synthesized at read time). No rewrite.
ALTER TABLE big ADD COLUMN status text NOT NULL DEFAULT 'active';

-- 2. Add the FK as NOT VALID — takes only a brief lock, does NOT scan existing rows.
ALTER TABLE big ADD CONSTRAINT fk_status
    FOREIGN KEY (status) REFERENCES statuses(name) NOT VALID;

-- 3. Validate separately — takes a weaker SHARE UPDATE EXCLUSIVE lock that allows
--    reads and writes to continue while it scans to verify existing rows.
ALTER TABLE big VALIDATE CONSTRAINT fk_status;
```

The reasoning for each step: step 1 relies on the PG 11+ optimization where a **constant** default is stored as metadata (`atthasmissing`/`attmissingval`) and applied to old rows lazily at read time, so adding the column is instant — but this only holds for a *non-volatile* default; `DEFAULT random()` or `DEFAULT now()` (well, `now()` is stable within a transaction but a volatile default like `clock_timestamp()`) would force a full rewrite. Step 2's `NOT VALID` is the key trick: it registers the foreign key so *new and updated* rows are checked immediately, but skips the expensive scan of existing rows that would otherwise hold a long lock. Step 3's `VALIDATE CONSTRAINT` then verifies the pre-existing rows under a `SHARE UPDATE EXCLUSIVE` lock — concurrent reads and writes proceed throughout — and you can run it during a quieter window.

The operational guardrails that complete a senior answer: set `lock_timeout` (and a separate, larger `statement_timeout` for the validate scan) so that if the brief catalog lock in step 1/2 can't be obtained because a long-running transaction holds the table, the migration **fails fast and retries** rather than queuing and blocking the flood of queries piling up behind it — an un-timed `ALTER` waiting on a lock is the single most common self-inflicted PostgreSQL outage. The same NOT VALID → VALIDATE pattern applies to `CHECK` constraints. If the column genuinely needs a computed/volatile backfill, you add it nullable, backfill in batches (small committed transactions so autovacuum keeps up and WAL stays bounded), then `SET NOT NULL` — which in PG 12+ can use an existing validated `CHECK (col IS NOT NULL)` to avoid even the full-table scan for the `NOT NULL` set.

#### Q132. [Coding] Write a query that uses `LATERAL` to fetch the top 3 most recent orders per customer in one statement, and explain why `LATERAL` beats the alternatives.

"Top-N per group" is a frequent advanced task, and `LATERAL` joins are the most efficient and readable solution when there's a supporting index — they let a subquery on the right side of a join **reference columns from the left side**, effectively running a correlated subquery that the planner can drive by index.

```sql
SELECT c.id AS customer_id, c.name, o.id AS order_id, o.created_at, o.total
FROM customers c
CROSS JOIN LATERAL (
    SELECT o.id, o.created_at, o.total
    FROM orders o
    WHERE o.customer_id = c.id          -- references the left side: this is what LATERAL enables
    ORDER BY o.created_at DESC
    LIMIT 3
) o
ORDER BY c.id, o.created_at DESC;
```

Why `LATERAL` wins: with an index on `orders(customer_id, created_at DESC)`, the planner executes this as a nested loop where, for each customer, the inner scan does an **index scan that stops after 3 rows** — it never materializes or ranks all of a customer's orders. Contrast with the `ROW_NUMBER() OVER (PARTITION BY customer_id ORDER BY created_at DESC)` window approach, which must compute a rank for *every* order row and then filter `WHERE rn <= 3` — fine for small data, but it processes the entire orders table even though you only want 3 per customer. On a table where customers have thousands of orders each, `LATERAL` reads 3 index entries per customer while the window function reads and sorts everything.

The trade-offs to articulate: `LATERAL` shines precisely when (a) the per-group limit is small, (b) there's an index matching the inner `WHERE` + `ORDER BY`, and (c) the number of groups (left rows) is modest — it's a nested loop, so if there are millions of customers the per-iteration overhead adds up and a window function or a single sorted scan may win. `CROSS JOIN LATERAL` drops customers with no orders; use `LEFT JOIN LATERAL (...) ON true` to keep them (with NULL order columns). `LATERAL` is also the idiomatic way to "join to a set-returning function per row" (e.g., `JOIN LATERAL jsonb_array_elements(c.data) ...`). The senior framing: reach for `LATERAL` for index-driven top-N-per-group and per-row function expansion; reach for window functions when you need ranks across the whole set, portability, or when no supporting index exists so both approaches scan everything anyway.

#### Q133. [Coding] Implement a rate limiter (sliding window) in PostgreSQL. What are the concurrency and cleanup concerns?

Building a rate limiter in the database is a design-coding question that surfaces atomicity, the fixed-vs-sliding-window distinction, and the cost of using PostgreSQL for high-frequency counters. A correct, race-free fixed-window limiter uses an upsert that increments and reads the count in one atomic statement:

```sql
CREATE TABLE rate_limit (
    key        text NOT NULL,
    window_start timestamptz NOT NULL,
    count      int NOT NULL,
    PRIMARY KEY (key, window_start)
);

-- One atomic statement: bump the current minute's counter and return the new value.
INSERT INTO rate_limit(key, window_start, count)
VALUES (:key, date_trunc('minute', now()), 1)
ON CONFLICT (key, window_start)
DO UPDATE SET count = rate_limit.count + 1
RETURNING count;        -- caller rejects the request if count > limit
```

For a true **sliding window** (smoother than fixed buckets, which allow a 2x burst at bucket boundaries), keep per-sub-window counts (e.g., per-second buckets) and sum the trailing window:

```sql
-- allow N requests per 60s, computed over per-second buckets
WITH bump AS (
    INSERT INTO rate_limit(key, window_start, count)
    VALUES (:key, date_trunc('second', now()), 1)
    ON CONFLICT (key, window_start) DO UPDATE SET count = rate_limit.count + 1
    RETURNING key
)
SELECT COALESCE(SUM(count), 0) AS used_in_window
FROM rate_limit
WHERE key = :key AND window_start > now() - interval '60 seconds';
```

The concurrency correctness: the `INSERT ... ON CONFLICT DO UPDATE ... RETURNING` is the linchpin — it performs the increment under the row lock and returns the post-increment value atomically, so two concurrent requests for the same key can't both read a stale count and both decide they're under the limit (the lost-update bug a `SELECT`-then-`UPDATE` would have). Bucketing by `date_trunc('minute'/'second', now())` keys the counter to a time window so old windows naturally fall out of the `SUM`.

The honest trade-offs an interviewer wants: PostgreSQL is **not the ideal rate limiter** for high request volumes — each check is a write transaction (WAL, dead tuples on every `DO UPDATE`, row-lock contention on hot keys), and a popular key becomes a single-row write hotspot that also generates bloat that autovacuum must chase. The cleanup concern is real: expired buckets accumulate and need a periodic `DELETE WHERE window_start < now() - interval '...'` (ideally on a time-partitioned table so it's a `DROP PARTITION`). At meaningful scale, **Redis** (atomic `INCR`+`EXPIRE`, in-memory, no durability tax, built-in TTL) is the right tool and is what production systems use. The database limiter is correct and convenient when limits are coarse, traffic is modest, or you specifically want the limit decision to be transactional with other database work — knowing *when not to use PostgreSQL here* is as important as writing the atomic upsert.

#### Q134. [Coding] Write a query to find rows where a JSONB document fails a structural expectation (e.g., missing a required key or wrong type), for a data-quality audit.

Validating semi-structured data is an advanced JSONB task that exercises the operators, existence/type functions, and the `jsonb_path` query language. The goal: scan an `events` table and flag documents that violate an expected shape, for a backfill or quality report.

```sql
SELECT id, payload
FROM events
WHERE NOT (payload ? 'user_id')                              -- missing required key
   OR jsonb_typeof(payload -> 'amount') <> 'number'          -- wrong type for amount
   OR NOT (payload -> 'tags') @> '[]'::jsonb                  -- tags not an array (or absent)
   OR (payload ->> 'status') NOT IN ('new','paid','void');   -- value out of allowed set
```

A more powerful and declarative approach uses **SQL/JSON path** (`@?` / `@@`, PG 12+), which expresses the predicate as a path expression and even supports filters and type predicates inside the document:

```sql
-- Flag docs that do NOT satisfy: amount is a positive number and user_id exists
SELECT id
FROM events
WHERE NOT (payload @@ '$.amount.type() == "number" && $.amount > 0 && exists($.user_id)');
```

The reasoning across operators: `?` tests key existence, `jsonb_typeof()` returns the JSON type of an element so you can assert `number`/`string`/`array`/`object`, `->` extracts as JSONB (for type checks and containment) while `->>` extracts as text (for value comparisons), and `@>` containment cheaply asserts "is an array/object of the expected shape." The `@@` jsonpath-match operator lets you fold several conditions into one expression with in-document filters and the `.type()` accessor, which is far more concise for complex rules and is the modern idiom.

The performance and pitfall notes that show depth: a plain GIN index (`jsonb_ops`) accelerates `?`, `@>`, and (with a `jsonb_path_ops` variant) containment, but **type/value predicates like `jsonb_typeof(...) <> 'number'` are not index-accelerated** and force a sequential scan — acceptable for a one-off audit, but if you need to enforce shape continuously, the right tool is a **`CHECK` constraint** (`CHECK (jsonb_typeof(payload->'amount') = 'number')`) or, better, promoting the validated fields to real typed columns (possibly generated columns) so the database enforces correctness on write rather than detecting violations after the fact. For `@@` jsonpath predicates you can build a GIN index with `jsonb_path_ops` and use `@?`/`@@` to get index support for the existence portions. The senior framing: ad-hoc auditing uses these read operators freely; *preventing* bad data going forward means moving the invariant into a constraint or the schema, because a JSONB column with no shape enforcement will inevitably accumulate the very violations this query is hunting for.

#### Q135. [Coding] Implement an efficient "keyset" (seek) pagination API over a composite sort key, including the exact WHERE clause and the index that makes it O(log n) per page.

Keyset pagination is the production-correct alternative to `OFFSET` (which is O(offset) — it scans and discards every skipped row, so deep pages get linearly slower). The interesting expert detail is paginating over a **composite, non-unique sort key** (e.g., sort by `created_at DESC, id DESC`), where the `WHERE` clause must use **row-value (tuple) comparison** to be both correct and index-friendly.

```sql
-- Page 1
SELECT id, created_at, title
FROM posts
ORDER BY created_at DESC, id DESC
LIMIT 20;

-- Next page: pass the last row's (created_at, id) as the cursor.
-- Row-value comparison expresses "strictly before (created_at, id)" in one shot.
SELECT id, created_at, title
FROM posts
WHERE (created_at, id) < (:last_created_at, :last_id)   -- tuple comparison
ORDER BY created_at DESC, id DESC
LIMIT 20;

-- The index that makes each page an O(log n) seek + sequential read of 20 rows:
CREATE INDEX idx_posts_seek ON posts (created_at DESC, id DESC);
```

Why the tuple comparison is essential: when the primary sort column (`created_at`) has ties, you cannot paginate on it alone — rows sharing a timestamp would be skipped or duplicated across page boundaries. Adding `id` as a unique tiebreaker makes the sort total. But the naive expansion `WHERE created_at < :c OR (created_at = :c AND id < :id)` is both error-prone and often planned poorly. The row-value form `(created_at, id) < (:c, :id)` is semantically exactly "the tuple ordered strictly before the cursor under lexicographic comparison," and PostgreSQL can translate it into an **index range scan** that seeks directly to the cursor position in the composite index and reads forward — so every page, including the millionth, costs one O(log n) descent plus reading 20 leaf entries. There is no scanning-and-discarding; performance is constant regardless of page depth.

The subtleties that mark expertise: the index column order and *direction* must match the `ORDER BY` exactly (`created_at DESC, id DESC`) for the seek to work without an extra sort — mixing ascending and descending across columns requires a matching mixed-direction index. The cursor must encode the full composite key (commonly base64-encoded `(created_at, id)`) rather than a page number, which is why keyset pagination doesn't support "jump to page 500" — it only does next/previous, the deliberate trade-off for its constant performance and its **stability under concurrent inserts** (new rows don't shift the window the way `OFFSET` causes rows to be skipped or repeated). The senior conclusion: use `OFFSET` only for small, shallow result sets or admin tools; use keyset/seek with a tuple-comparison `WHERE` and a direction-matched composite index for any user-facing infinite-scroll or deep pagination, and accept that you give up random page access in exchange for O(log n)-per-page and correctness under concurrent writes.

#### Q136. [Coding] Write a PL/pgSQL function that performs a batched, resumable backfill of a derived column with progress logging and exception handling. What makes it production-safe?

Backfilling a derived column across a huge table inside the database (rather than from the app) is an expert task that combines batching, transaction control inside PL/pgSQL, `RAISE` logging, and exception handling. The function processes a bounded slice per call so it never holds a giant transaction, and it's resumable because it advances by primary key.

```sql
CREATE OR REPLACE FUNCTION backfill_full_name(p_batch int DEFAULT 5000)
RETURNS bigint
LANGUAGE plpgsql AS $$
DECLARE
    v_last_id bigint := 0;
    v_rows    int;
    v_total   bigint := 0;
BEGIN
    LOOP
        UPDATE users u
        SET full_name = trim(coalesce(first_name,'') || ' ' || coalesce(last_name,''))
        WHERE u.id IN (
            SELECT id FROM users
            WHERE id > v_last_id
              AND full_name IS NULL          -- resumable: only un-backfilled rows
            ORDER BY id
            LIMIT p_batch
        )
        RETURNING u.id INTO v_last_id;        -- NOTE: see correctness fix below

        GET DIAGNOSTICS v_rows = ROW_COUNT;
        EXIT WHEN v_rows = 0;                 -- drained

        SELECT max(id) INTO v_last_id
        FROM users WHERE full_name IS NOT NULL AND id > v_last_id - p_batch;

        v_total := v_total + v_rows;
        RAISE NOTICE 'backfilled % rows, total %, last_id %', v_rows, v_total, v_last_id;
        COMMIT;                               -- commit each batch (PL/pgSQL procedure / DO block)
        PERFORM pg_sleep(0.05);               -- throttle: let autovacuum & replicas keep up
    END LOOP;
    RETURN v_total;
END;
$$;
```

The production-safety properties to articulate: (1) **Batching with per-batch `COMMIT`** keeps each transaction small, so locks are short-lived, dead tuples from the updates are reclaimable by autovacuum *between* batches (preventing the bloat explosion a single 500M-row `UPDATE` would cause), and WAL generation per commit is bounded — note `COMMIT` inside a loop requires a **procedure or `DO` block** (PL/pgSQL functions can't commit; procedures introduced in PG 11 can). (2) **Resumability via `WHERE full_name IS NULL` and key ordering** means if the job dies halfway, re-running it picks up exactly where it stopped without redoing work or needing external bookkeeping — the not-yet-backfilled predicate *is* the cursor. (3) **`GET DIAGNOSTICS ROW_COUNT`** drives the drain condition, and (4) **`RAISE NOTICE` progress logging** makes a multi-hour backfill observable. (5) **`pg_sleep` throttling** prevents the backfill from saturating I/O and blowing out replication lag — a backfill that runs as fast as possible is a common cause of replica lag alarms and even failover.

The edge cases and corrections worth flagging (and an interviewer loves a candidate who self-audits): driving the cursor purely off `full_name IS NULL` is the safest resumable predicate, while tracking `v_last_id` is an optimization to avoid re-scanning already-processed key ranges — combine both (advance the id cursor *and* filter on the null predicate) and ensure an index supports the slice `SELECT` (`CREATE INDEX CONCURRENTLY ... ON users (id) WHERE full_name IS NULL` — a partial index that shrinks as the backfill progresses, keeping each batch's scan cheap). Also wrap risky per-batch work in a `BEGIN ... EXCEPTION WHEN OTHERS THEN RAISE WARNING ...; CONTINUE; END` block only if individual rows can legitimately fail and you want to skip-and-log rather than abort the whole run. The senior framing: a safe backfill is small batches + commit-between + resumable predicate + throttle + a supporting (ideally partial) index + observable progress — the opposite of the naive single `UPDATE` that locks the table, balloons WAL, bloats catastrophically, and has to restart from scratch if interrupted.

#### Q137. [Coding] Write a query using `tsvector`/`tsquery` for ranked full-text search with highlighting, and show how a generated column plus GIN index make it fast.

Native full-text search is an advanced feature interviewers probe to see if you know the `tsvector`/`tsquery` model, ranking, and the right way to index it. Unlike `LIKE` or trigram search, FTS understands language: it tokenizes text into normalized **lexemes** (stemming "running"→"run"), drops stop-words, and ranks by term frequency and proximity. The production pattern stores a precomputed `tsvector` in a **generated column** indexed with GIN:

```sql
ALTER TABLE articles
    ADD COLUMN search tsvector
    GENERATED ALWAYS AS (
        setweight(to_tsvector('english', coalesce(title,'')),  'A') ||   -- title weighted highest
        setweight(to_tsvector('english', coalesce(body, '')),  'B')
    ) STORED;

CREATE INDEX idx_articles_search ON articles USING gin (search);

-- Ranked search with snippet highlighting
SELECT id, title,
       ts_rank(search, q)                          AS rank,
       ts_headline('english', body, q,
                   'StartSel=<b>, StopSel=</b>')    AS snippet
FROM articles, websearch_to_tsquery('english', :user_query) q
WHERE search @@ q                                   -- index-accelerated match
ORDER BY rank DESC
LIMIT 20;
```

The design reasoning: the **`GENERATED ALWAYS AS (...) STORED`** column (PG 12+) computes the `tsvector` automatically on every insert/update, so the search vector can never drift out of sync with the source text — superior to the old trigger-maintained column or computing `to_tsvector` at query time (which would prevent index use and re-tokenize on every search). `setweight(..., 'A'/'B')` lets `ts_rank` score title matches above body matches. The **GIN index** on the `tsvector` is what makes `search @@ q` fast: GIN is an inverted index mapping each lexeme to the rows containing it, so a query lexeme is a direct lookup rather than a scan. **`websearch_to_tsquery`** (PG 11+) parses Google-style input ("foo -bar \"exact phrase\"") safely into a `tsquery`, which is far better for user-facing search than `to_tsquery` (which throws syntax errors on raw user input) or `plainto_tsquery` (which ANDs everything with no operator support).

The trade-offs and scaling boundary: `ts_headline` is **not index-accelerated** and re-reads the document body to build the highlighted snippet, so it's evaluated only on the final `LIMIT 20` rows after ranking — never let it run over the whole match set. `ts_rank` likewise scores only matched rows. The stored vector and GIN index add write cost and storage (the index can be large, mitigated by `fastupdate` pending lists). PostgreSQL FTS is excellent for moderate corpora, exact-language search, and keeping search transactional with your data (no separate sync pipeline). You outgrow it when you need typo tolerance (combine with `pg_trgm`), multi-language analyzers, faceting, relevance tuning (BM25), or billion-document scale — at which point a dedicated engine (Elasticsearch/OpenSearch) is warranted, fed via CDC. Knowing that the generated-column + GIN + `websearch_to_tsquery` + deferred-`ts_headline` combination is the *correct* native pattern, and where it hands off to a search engine, is the expert signal.

#### Q138. [Coding] Write the SQL to set up partition-wise automatic time partitioning with a default partition, and explain the trap the default partition creates.

Maintaining range partitions over time is an operational-coding task, and the expert nuance is the **default partition** — a catch-all for rows that match no defined range — which is both a safety net and a performance trap. Here is a hand-rolled monthly setup (production would use `pg_partman` to automate creation/retention):

```sql
CREATE TABLE events (id bigint, ts timestamptz NOT NULL, payload jsonb)
    PARTITION BY RANGE (ts);

CREATE TABLE events_2026_06 PARTITION OF events
    FOR VALUES FROM ('2026-06-01') TO ('2026-07-01');
CREATE TABLE events_2026_07 PARTITION OF events
    FOR VALUES FROM ('2026-07-01') TO ('2026-08-01');

-- Catch-all for any row outside defined ranges (e.g., a future ts before its partition exists)
CREATE TABLE events_default PARTITION OF events DEFAULT;
```

The default partition's value is that an `INSERT` with a `ts` for which no partition yet exists doesn't fail with "no partition of relation found for row" — it lands in `events_default` instead of erroring out the transaction. That sounds purely good, which is the trap.

The traps to explain. (1) **Attaching a new partition that overlaps rows already sitting in the default partition fails**: `ATTACH PARTITION events_2026_08 FOR VALUES FROM ('2026-08-01') TO ('2026-09-01')` must scan the default partition to prove no row already there belongs in the new range, and if the default *does* hold August rows (because they were inserted before the August partition existed), the attach errors until you move those rows out. So a default partition that ever accumulates rows makes adding the corresponding real partition painful — you must first relocate the misfiled rows. (2) **The default partition defeats some partition pruning**: because a query's range *could* match rows in the catch-all, the planner often must include the default partition in scans it would otherwise prune, eroding the pruning benefit. (3) It silently hides a bug — rows landing in the default usually mean your partition-creation automation fell behind, and without monitoring `events_default`'s row count you won't notice until the attach failures or scan slowdowns appear. The senior practice: either run with **no default partition** and ensure automation (pg_partman, or a scheduled job) always creates partitions *ahead* of time so inserts never miss — failing loudly is better than silently misfiling — or keep a default partition strictly as an alerting tripwire (monitor its row count, alert if nonzero) and never let data persist in it. Knowing that the convenient-looking default partition trades clean failures and full pruning for a silent accumulation that later blocks `ATTACH` is exactly the kind of operational depth expert interviews look for.

#### Q139. [Coding] Implement a query that detects lock-blocking chains in real time and produces the kill recommendation, suitable for an on-call runbook.

When a lock pileup is causing an outage, the on-call engineer needs to instantly see *who blocks whom* and which backend to cancel — and a single self-join over `pg_locks` joined to `pg_stat_activity` produces exactly that. This is a coding question because the join logic (matching a waiting lock to the granted lock that conflicts with it) is non-trivial, and `pg_blocking_pids()` plus the `pg_blocking_pids` array do the heavy lifting in modern versions.

```sql
SELECT
    blocked.pid                              AS blocked_pid,
    blocked.usename                          AS blocked_user,
    now() - blocked.query_start              AS blocked_for,
    left(blocked.query, 60)                  AS blocked_query,
    blocker.pid                              AS blocking_pid,
    blocker.state                            AS blocking_state,    -- watch for 'idle in transaction'
    now() - blocker.query_start              AS blocker_running_for,
    left(blocker.query, 60)                  AS blocking_query
FROM pg_stat_activity blocked
CROSS JOIN LATERAL unnest(pg_blocking_pids(blocked.pid)) AS b(blocking_pid)
JOIN pg_stat_activity blocker ON blocker.pid = b.blocking_pid
WHERE blocked.wait_event_type = 'Lock'        -- only sessions actually waiting on a lock
ORDER BY blocked_for DESC;
```

The mechanics and why this is the right query: `pg_blocking_pids(pid)` (PG 9.6+) returns the array of backend PIDs whose locks are blocking the given backend — it correctly accounts for lock-mode conflict matrices and even multi-way waits, which is hugely better than hand-joining `pg_locks` (the old, error-prone way that often missed cases). `unnest`-ing it via `LATERAL` expands each blocked session to one row per blocker, and joining back to `pg_stat_activity` enriches both sides with user, query text, and — the most actionable field — the blocker's **`state`**. The pattern you're hunting for in an incident is a blocker whose `state` is **`idle in transaction`**: that means the root cause isn't a slow query but a session holding locks while doing nothing (an app that forgot to commit), and the fix is to terminate *that* session, not the visible victims.

The runbook judgment to deliver: the correct response is to identify the **root blocker** (a PID that appears in others' `blocking_pid` but is itself not waiting — the head of the chain) and act on it, because killing a blocked *victim* just frees it for the next queued session to block on the same lock — whack-a-mole. Use **`pg_cancel_backend(pid)` first** (cancels the current statement, preserves the connection) and escalate to **`pg_terminate_backend(pid)`** only if cancel doesn't release the lock (e.g., the blocker is `idle in transaction` with nothing to cancel — there you *must* terminate to roll back its transaction and free the locks). The preventive follow-ups belong in the postmortem: set `idle_in_transaction_session_timeout` so forgotten transactions self-terminate, set `lock_timeout` on migrations so DDL doesn't queue and amplify a pileup, and alert on `pg_stat_activity` lock-wait depth. Shipping this query in the runbook with the cancel-the-root-blocker-first rule turns a frantic outage into a two-minute diagnosis — which is precisely the operational maturity the question is testing.

## ✅ Key Takeaways

- **MVCC** gives non-blocking reads but creates dead tuples; **VACUUM/autovacuum** is not optional — it reclaims bloat and prevents XID wraparound.
- Pick the **right index** for the access pattern: B-tree (default/range), GIN (JSONB/arrays/full-text), BRIN (huge ordered/time-series), GiST (spatial/KNN); add **partial** and **covering (`INCLUDE`)** indexes for skewed data and index-only scans.
- Read every plan with `EXPLAIN (ANALYZE, BUFFERS)` and chase **estimate-vs-actual row mismatches** — stale statistics cause most bad plans; run `ANALYZE` after bulk loads.
- The **WAL** underpins durability, streaming replication, logical replication/CDC, and PITR; tune checkpoints and `max_wal_size` to balance WAL volume against recovery time.
- Use **Serializable (SSI)** for correctness-critical logic and always implement **`40001` retry loops**; otherwise default Read Committed with careful locking.
- Solve the connection-fan-out problem with **PgBouncer** (transaction pooling) and a modest DB `max_connections`.
- Scale with **partitioning** and **read replicas** first; reach for **Citus/sharding** only when single-node writes are the proven bottleneck — and design `tenant_id` in from day one.
- **`SKIP LOCKED`** turns PostgreSQL into a solid job queue; **batched deletes** or **partition DROP** avoid bloat and lock storms.

## ⚠️ Common Pitfalls

- Disabling autovacuum or ignoring long-running transactions that pin the xmin horizon — leads to bloat and, eventually, wraparound read-only mode.
- Running `VACUUM FULL` on a live OLTP table (it takes `ACCESS EXCLUSIVE`); use `pg_repack`/`REINDEX CONCURRENTLY` instead.
- Shipping migrations without `lock_timeout`/`statement_timeout` — a blocked DDL queues all subsequent queries behind it and causes outages.
- Assuming `OFFSET` pagination scales; deep offsets scan and discard rows — use keyset (seek) pagination on an indexed column.
- Over-using JSONB for data you filter/join on, then wondering why plans are bad (no per-key statistics); promote hot keys to columns.
- Setting `work_mem` high globally — it's per-node-per-connection and can OOM the box under concurrency; raise it per-session for big queries.
- Forgetting that `CREATE INDEX` (without `CONCURRENTLY`) locks writes; and that index-only scans still hit the heap if the visibility map is stale.
- Leaving inactive logical replication slots around — they pin WAL and can fill the disk and crash the primary.
- Counting on transaction-mode PgBouncer while using session features (`SET`, `LISTEN/NOTIFY`, session advisory locks) — they silently break.

## 📚 Further Reading

- *PostgreSQL Documentation* — Chapters on MVCC, Indexes, WAL, Replication, and Routine Vacuuming (the authoritative source): https://www.postgresql.org/docs/current/
- *PostgreSQL 14 Internals* by Egor Rogov (free PDF) — the definitive deep dive on MVCC, buffer manager, WAL, and the planner.
- *The Art of PostgreSQL* by Dimitri Fontaine — practical SQL and PostgreSQL-idiomatic application design.
- *PostgreSQL High Performance* / *PostgreSQL 14 Administration Cookbook* — operational tuning, vacuum, and HA.
- Use The Index, Luke (use-the-index-luke.com) by Markus Winand — index design and EXPLAIN reading across SQL databases.
- PgBouncer, Patroni, and Citus official docs — connection pooling, HA/failover, and distributed PostgreSQL.
