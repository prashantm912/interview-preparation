# MySQL — Interview Preparation Guide

A deep, staff-level reference for MySQL (with emphasis on InnoDB and MySQL 8.x), covering storage internals, indexing, locking and isolation, replication, query optimization, schema operations, and production tuning. Examples use Java (JDBC / HikariCP / JPA idioms) where code is relevant.

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

### Q1. [Theory] What is the difference between InnoDB and MyISAM, and why is InnoDB the default?

InnoDB is the default storage engine since MySQL 5.5 and is the only one you should choose for almost all OLTP workloads. The key differences:

- **Transactions**: InnoDB is ACID-compliant with full transaction support (`BEGIN`/`COMMIT`/`ROLLBACK`), MyISAM has none.
- **Locking**: InnoDB does row-level locking with MVCC; MyISAM only does table-level locking, which serializes writes and kills concurrency.
- **Crash recovery**: InnoDB uses a redo log (write-ahead log) for crash recovery; MyISAM tables can corrupt on crash and need `REPAIR TABLE`.
- **Foreign keys**: only InnoDB enforces referential integrity.
- **Clustered index**: InnoDB stores rows physically ordered by primary key; MyISAM stores data and indexes in separate files (`.MYD`/`.MYI`).

The trade-off historically was that MyISAM had faster full-table scans and `COUNT(*)` (it kept a row count), but in modern MySQL the durability, concurrency, and integrity guarantees of InnoDB dominate. MyISAM is effectively legacy.

### Q2. [Theory] What is a clustered index in InnoDB?

In InnoDB the table *is* the primary-key index — the leaf nodes of the B+tree contain the full row data, ordered by the primary key. This is the **clustered index**. There is exactly one per table.

Consequences:

- Primary-key lookups are very fast: one B+tree traversal lands directly on the row.
- **Secondary indexes** store the indexed columns plus the *primary key value* (not a physical row pointer). So a secondary-index lookup that needs non-indexed columns does two traversals: secondary index → PK value → clustered index. This is the **back-to-table** (or "bookmark lookup") cost.
- A large primary key bloats every secondary index, because the PK is duplicated in all of them. This is why a compact, monotonically increasing PK (like `BIGINT AUTO_INCREMENT`) is preferred over a random UUID.

```
Clustered index (PK = id)            Secondary index (on email)
        [root]                              [root]
       /      \                            /      \
   [10|...] [20|...]                  [a@..|10] [m@..|20]
   row data inline                    leaf stores PK (10), not row
```

If you don't declare a primary key, InnoDB picks the first non-null UNIQUE index, or generates a hidden 6-byte `GEN_CLUST_INDEX` rowid. Always declare an explicit PK.

### Q3. [Theory] Explain the four SQL isolation levels and MySQL's default.

The SQL standard defines four levels by which concurrency anomalies they prevent:

| Level | Dirty read | Non-repeatable read | Phantom read |
|---|---|---|---|
| READ UNCOMMITTED | possible | possible | possible |
| READ COMMITTED | prevented | possible | possible |
| REPEATABLE READ | prevented | prevented | possible (standard) |
| SERIALIZABLE | prevented | prevented | prevented |

InnoDB's default is **REPEATABLE READ**. Notably, InnoDB's REPEATABLE READ is stronger than the standard: because it uses **next-key locking** (gap + record locks), it also prevents most phantom reads for locking reads. Plain (non-locking) `SELECT` uses MVCC consistent snapshots, so they see a frozen view of the data as of the first read in the transaction.

The "why": REPEATABLE READ gives you a stable snapshot per transaction, which is convenient for application correctness, but the gap locks it takes can increase deadlock and lock-wait risk. Many high-throughput shops switch to READ COMMITTED (matching PostgreSQL/Oracle default) to reduce gap locking and shorten lock duration.

### Q4. [Practical] How do you create a basic index, and how do you check whether a query uses it?

```sql
CREATE INDEX idx_orders_customer ON orders (customer_id, created_at);

EXPLAIN SELECT * FROM orders
WHERE customer_id = 42 AND created_at > '2026-01-01';
```

In the `EXPLAIN` output you look at:

- `type`: `ref`/`range`/`eq_ref` are good; `ALL` (full table scan) is usually bad on a large table.
- `key`: which index was actually chosen (`NULL` means none).
- `rows`: estimated rows examined — lower is better.
- `Extra`: `Using index` (covering index, great), `Using where`, `Using filesort` or `Using temporary` (often a red flag for sorting/grouping cost).

In production you'd use `EXPLAIN ANALYZE` (MySQL 8.0.18+) to get *actual* execution times and row counts, not just estimates.

### Q5. [Coding] Write a JDBC query in Java that safely fetches a user by email without SQL injection.

**Problem**: A naive `String sql = "SELECT ... WHERE email = '" + email + "'"` is vulnerable to SQL injection. Use a `PreparedStatement` with bind parameters.

```java
import java.sql.*;
import java.util.Optional;

public Optional<User> findByEmail(DataSource ds, String email) {
    String sql = "SELECT id, email, name FROM users WHERE email = ?";
    try (Connection conn = ds.getConnection();
         PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setString(1, email);            // bound, never concatenated
        try (ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return Optional.of(new User(
                    rs.getLong("id"),
                    rs.getString("email"),
                    rs.getString("name")));
            }
            return Optional.empty();
        }
    } catch (SQLException e) {
        throw new RuntimeException("query failed", e);
    }
}
```

**Why this is safe**: the driver sends the SQL template and the parameter separately; the value `email` is never parsed as SQL. It also lets the server cache the execution plan and benefit from prepared-statement reuse.

- **Time/Space**: O(log n) index lookup if `email` is indexed (unique index recommended), O(1) extra space.
- **Edge cases**: null email, duplicate emails (use `UNIQUE` constraint), trailing whitespace / case sensitivity (depends on collation — `utf8mb4_0900_ai_ci` is case-insensitive).

**Security note**: never disable certificate validation or build SQL by string concatenation. Use least-privilege DB accounts (no `DROP`/`ALTER` for the app user).

### Q6. [Theory] What is the difference between `CHAR`, `VARCHAR`, and `TEXT`? And why `utf8mb4`?

`CHAR(n)` is fixed-length, right-padded with spaces — good for fixed-width data like country codes. `VARCHAR(n)` is variable-length with a 1–2 byte length prefix — the workhorse for strings. `TEXT`/`BLOB` are stored partly off-page (in overflow pages) and **cannot have a default value**; indexing them requires a prefix length.

Always use **`utf8mb4`** (not the legacy `utf8`, which is really `utf8mb3` and only stores up to 3-byte characters — it cannot store emoji or some CJK/supplementary characters). MySQL 8 defaults to `utf8mb4` with the `utf8mb4_0900_ai_ci` collation (Unicode 9.0, accent- and case-insensitive). Mismatched charset/collation between columns is a classic cause of silently failing index usage in joins.

---

## 🟡 Intermediate (3–7 yrs)

### Q7. [Theory] Describe the InnoDB buffer pool and why it is the single most important tuning knob.

The **buffer pool** is InnoDB's in-memory cache of data and index pages (default 16 KB pages). All reads and writes go through it: a page is read from disk into the pool, modified there (becoming a "dirty" page), and later flushed back. If your working set fits in the buffer pool, you get near-memory speeds; if it doesn't, you thrash on disk I/O.

```
        Buffer Pool (innodb_buffer_pool_size)
   +-------------------------------------------------+
   |  [ young (hot) sublist ]   <-- frequently used   |
   |  ---------------- midpoint (5/8) ---------------- |
   |  [ old (cold) sublist  ]   <-- newly read pages   |
   +-------------------------------------------------+
   Flush list (dirty pages) --> background flushing --> disk
```

Key points:

- It uses a modified **LRU with a midpoint insertion** to resist pollution from one-off full scans: new pages go into the *old* sublist, and only get promoted to the *young* sublist if accessed again after a short window (`innodb_old_blocks_time`).
- On a dedicated DB server, set `innodb_buffer_pool_size` to ~50–75% of RAM.
- `innodb_buffer_pool_instances` shards the pool to reduce mutex contention on large pools.
- Watch the buffer pool hit ratio and `Innodb_buffer_pool_reads` (disk reads) vs `Innodb_buffer_pool_read_requests` (logical reads). A high miss rate means undersized pool or oversized working set.

### Q8. [Theory] Explain redo logs, undo logs, the doublewrite buffer, and how a commit becomes durable.

InnoDB uses **WAL (write-ahead logging)**. The flow on commit:

```
1. Modify page in buffer pool (page now "dirty")
2. Write change record to the redo log buffer
3. On COMMIT: flush redo log buffer to the redo log files (fsync)
   -> controlled by innodb_flush_log_at_trx_commit
4. Return success to client (data page may still be dirty in memory)
5. Later: background flush of dirty pages to the tablespace
   (via the doublewrite buffer to avoid torn pages)
```

- **Redo log** (physical, "this page changed like so") makes commits durable without flushing the actual data pages — sequential log writes are cheap. On crash recovery, InnoDB replays the redo log to bring data pages forward.
- **Undo log** (logical) stores the *previous* version of rows. It serves two purposes: **rollback** and **MVCC** (other transactions read the old version via undo to get a consistent snapshot). Old undo is purged once no transaction needs it.
- **Doublewrite buffer**: pages are written first to a contiguous doublewrite area, then to their final location. This protects against **partial (torn) page writes** during a crash, because InnoDB pages (16 KB) are larger than the OS atomic write size (often 4 KB).
- `innodb_flush_log_at_trx_commit`: `1` (default, fully durable — fsync every commit), `2` (write to OS cache each commit, fsync once/sec — survives MySQL crash but not OS crash), `0` (flush once/sec — fastest, least safe). This is the classic durability-vs-throughput knob.

### Q9. [Practical] You have `SELECT * FROM events WHERE user_id = ? ORDER BY created_at DESC LIMIT 20` running slowly. How do you fix it?

**Scenario**: hot endpoint, `events` has 500M rows, query does `Using filesort` and scans many rows.

**Approach**:

1. Run `EXPLAIN ANALYZE`. Likely there's an index on `user_id` alone, so MySQL finds the user's rows but must sort them by `created_at` (filesort).
2. Create a **composite index that matches the access pattern**: `(user_id, created_at)`. Now MySQL can seek to `user_id = ?` and read rows already in `created_at` order, satisfying both the `WHERE` and the `ORDER BY` from the index — no filesort. Because the index column order is `created_at` ascending but you want DESC, MySQL 8 can still read it backwards efficiently (or you create a descending index `(user_id, created_at DESC)`, supported natively in MySQL 8).
3. Avoid `SELECT *` if you can use a **covering index**. If you only need a few columns, include them so the query is served entirely from the index (`Using index`).

**Trade-offs**: the composite index costs write amplification and storage. For deep pagination (`LIMIT 100000, 20`) prefer **keyset (seek) pagination** — `WHERE user_id = ? AND created_at < ? ORDER BY created_at DESC LIMIT 20` — instead of large `OFFSET`, which scans and discards rows.

**What I'd do in production**: add `(user_id, created_at DESC)`, switch to keyset pagination, and verify with `EXPLAIN ANALYZE` that `rows` examined dropped and filesort disappeared.

### Q10. [Coding] Implement keyset (cursor) pagination in Java to avoid slow OFFSET pagination.

**Problem**: `LIMIT 1000000, 20` forces MySQL to read and skip a million rows. Keyset pagination uses the last seen sort key as a cursor, giving O(log n + page) cost regardless of depth.

```java
public record Page<T>(List<T> items, Instant nextCursor) {}

public Page<Event> fetchEvents(DataSource ds, long userId, Instant cursor, int size) {
    // First page: cursor == null -> use a sentinel far in the future.
    String sql = """
        SELECT id, user_id, created_at, payload
        FROM events
        WHERE user_id = ?
          AND created_at < ?
        ORDER BY created_at DESC
        LIMIT ?
        """;
    try (Connection c = ds.getConnection();
         PreparedStatement ps = c.prepareStatement(sql)) {
        ps.setLong(1, userId);
        ps.setTimestamp(2, Timestamp.from(
            cursor != null ? cursor : Instant.now().plusSeconds(1)));
        ps.setInt(3, size);
        List<Event> out = new ArrayList<>(size);
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new Event(rs.getLong("id"), rs.getLong("user_id"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getString("payload")));
            }
        }
        Instant next = out.isEmpty() ? null : out.get(out.size() - 1).createdAt();
        return new Page<>(out, next);
    } catch (SQLException e) {
        throw new RuntimeException(e);
    }
}
```

- **Time/Space**: O(log n) seek + O(page size) read per page; constant regardless of how deep you scroll. OFFSET pagination is O(offset + page).
- **Edge cases**: ties on `created_at` — add a tiebreaker column (`(created_at, id)`) so the cursor is fully ordered and you never skip/duplicate rows. Backward pagination flips the comparison and re-sorts.

### Q11. [Theory] What are gap locks and next-key locks, and when do they bite you?

A **record lock** locks an index record. A **gap lock** locks the *interval between* index records (no rows), preventing inserts into that range. A **next-key lock** = record lock + the gap before it. InnoDB takes next-key locks under REPEATABLE READ for locking reads (`SELECT ... FOR UPDATE`, `UPDATE`, `DELETE`) to prevent phantoms.

```
Index values:  10        20        30
Gaps:        (..,10) (10,20) (20,30) (30,..)
WHERE id > 15 FOR UPDATE  locks gap (10,20), record 20,
                          gap (20,30), record 30, gap (30,..)
=> another txn cannot INSERT id=18 or id=25 until you commit
```

When they bite:

- **Deadlocks**: two transactions taking gap locks in opposite order. Classic with `INSERT ... ON DUPLICATE KEY UPDATE` and concurrent inserts into the same gap.
- **Reduced insert concurrency**: range scans with `FOR UPDATE` block inserts in the whole range.
- Locking on a **non-indexed column** escalates to locking *every* row scanned (effectively the whole table), because InnoDB can only lock index records.

Mitigations: index your `WHERE` predicates so locks are precise; switch to READ COMMITTED (which mostly disables gap locks, keeping only record locks on matched rows); keep transactions short.

### Q12. [Practical] How does MySQL replication work, and what are async vs semi-sync vs Group Replication?

MySQL replication ships the **binary log (binlog)** from the primary to replicas, which apply it.

```
Primary                         Replica
  |                               |
  | write binlog (ROW events)     |
  |------ dump thread --------->  | IO thread -> relay log
  |                               | SQL/applier threads apply
```

- **Asynchronous (default)**: the primary commits and returns immediately; it does not wait for replicas. Lowest latency, but on primary failure you can lose the last few transactions, and replicas may lag.
- **Semi-synchronous** (`rpl_semi_sync`): the primary waits until at least one replica acknowledges that it has *received* (written to relay log) the event before returning commit. This bounds data loss to in-flight transactions, at the cost of added commit latency. It is *not* full sync — the replica only acknowledges receipt, not apply.
- **Group Replication (MGR)**: a Paxos-based (group communication) protocol giving a fault-tolerant, self-healing group with automatic primary election (single-primary mode) or multi-primary. It provides virtually synchronous replication with conflict detection. This is the foundation of **InnoDB Cluster** (MGR + MySQL Router + MySQL Shell).

Operational essentials: use **GTIDs** (global transaction IDs) for easy failover and replica repositioning, parallel replication (`replica_parallel_workers`, `LOGICAL_CLOCK`) to reduce lag, and monitor `SHOW REPLICA STATUS` for `Seconds_Behind_Source`.

### Q13. [Theory] What is the binlog, what formats exist, and how is it used for CDC?

The **binary log** records all data-changing statements (DML/DDL) in commit order. It's used for replication, point-in-time recovery, and **CDC (change data capture)**.

Formats:

- **STATEMENT**: logs the SQL text. Compact but unsafe for non-deterministic functions (`NOW()`, `UUID()`, `RAND()`) and order-dependent updates.
- **ROW** (default since 5.7): logs the actual before/after row images. Deterministic and safe; the basis of CDC tools. Larger logs (tune with `binlog_row_image=MINIMAL` to log only changed columns + PK).
- **MIXED**: statement by default, switching to row for unsafe statements.

**CDC**: tools like **Debezium** (on Kafka Connect), Maxwell, or AWS DMS connect as a replica, read the ROW binlog, and stream change events to Kafka/downstream systems for cache invalidation, search indexing (Elasticsearch), data lakes, and event-driven microservices. This decouples downstream consumers from the OLTP database. Requires `binlog_format=ROW`, `binlog_row_image=FULL` (Debezium prefers FULL), GTIDs, and a stable replication user. **Outbox pattern**: write domain events into an outbox table in the same transaction and let CDC pick them up, giving exactly-once-ish delivery without dual-write inconsistency.

### Q14. [Coding] Write a query and Java helper to detect and avoid the N+1 problem when loading orders with line items.

**Problem (N+1)**: loading N orders, then issuing one query per order for its items = 1 + N queries. This destroys throughput under load.

```java
// BAD: N+1
List<Order> orders = orderRepo.findRecent(); // 1 query
for (Order o : orders) {
    o.setItems(itemRepo.findByOrderId(o.getId())); // N queries!
}
```

**Fix 1 — single JOIN / IN query** (batch fetch):

```java
public Map<Long, List<Item>> loadItems(DataSource ds, List<Long> orderIds) {
    String placeholders = orderIds.stream().map(x -> "?")
        .collect(java.util.stream.Collectors.joining(","));
    String sql = "SELECT order_id, sku, qty FROM order_items " +
                 "WHERE order_id IN (" + placeholders + ")";
    Map<Long, List<Item>> byOrder = new HashMap<>();
    try (Connection c = ds.getConnection();
         PreparedStatement ps = c.prepareStatement(sql)) {
        for (int i = 0; i < orderIds.size(); i++) ps.setLong(i + 1, orderIds.get(i));
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                byOrder.computeIfAbsent(rs.getLong("order_id"), k -> new ArrayList<>())
                       .add(new Item(rs.getString("sku"), rs.getInt("qty")));
            }
        }
    } catch (SQLException e) { throw new RuntimeException(e); }
    return byOrder; // 1 query total for all items
}
```

In JPA/Hibernate the equivalent fixes are `JOIN FETCH`, `@EntityGraph`, or `@BatchSize`. Note the placeholder list shouldn't be unbounded — chunk it (e.g., 1000 ids per `IN`) to avoid huge queries and to allow plan caching.

- **Time/Space**: 2 queries total (orders + items) vs 1+N. Network round-trips drop from N+1 to 2 — usually the dominant win.
- **Edge cases**: empty `orderIds` (skip the query), very large IN lists (chunk), duplicate rows from JOIN fetching collections (use `DISTINCT` or map-based dedup).

### Q15. [Theory] How does MySQL's cost-based optimizer choose a plan, and what overrides do you have?

The optimizer is **cost-based**: it enumerates access paths (which index, join order, join algorithm) and estimates a cost from table/index **statistics** (cardinality, row counts) stored per-index (`information_schema.STATISTICS`) and from data-distribution **histograms** (MySQL 8 `ANALYZE TABLE ... UPDATE HISTOGRAM`). It picks the lowest estimated cost.

Things that mislead it:

- **Stale statistics** → wrong row estimates → bad index choice. Run `ANALYZE TABLE`. InnoDB samples pages (`innodb_stats_persistent_sample_pages`).
- **Skewed data** without a histogram → it assumes uniform distribution.
- **Functions on indexed columns** (`WHERE DATE(created_at) = ...`) → index can't be used unless you have a functional index (MySQL 8).

Overrides / tools:

- **Index hints**: `USE INDEX`, `FORCE INDEX`, `IGNORE INDEX`.
- **Optimizer hints** (8.0): `/*+ JOIN_ORDER(...) */`, `/*+ INDEX_MERGE(...) */`, `/*+ NO_ICP(...) */`, `/*+ SET_VAR(...) */`.
- **Join algorithms**: MySQL 8.0.18+ added **hash join** for equi-joins without usable indexes; otherwise nested-loop join (with Batched Key Access / MRR). MySQL 8 also has **Index Condition Pushdown (ICP)** and the historic optimizer switches in `optimizer_switch`.

Prefer fixing statistics and indexes over hard-coding `FORCE INDEX`, which can rot as data changes.

### Q16. [Coding] Write a SQL query using a window function to rank top 3 products by sales per category (MySQL 8).

**Problem**: pre-8.0 this needed correlated subqueries or session-variable tricks. MySQL 8 window functions make it clean.

```sql
WITH sales AS (
    SELECT category_id, product_id, SUM(amount) AS total
    FROM order_lines
    GROUP BY category_id, product_id
),
ranked AS (
    SELECT
        category_id,
        product_id,
        total,
        ROW_NUMBER() OVER (
            PARTITION BY category_id
            ORDER BY total DESC
        ) AS rn
    FROM sales
)
SELECT category_id, product_id, total
FROM ranked
WHERE rn <= 3
ORDER BY category_id, total DESC;
```

- **`ROW_NUMBER`** gives a strict 1..N with no ties; use **`RANK`** if you want ties to share a rank and skip (1,1,3), or **`DENSE_RANK`** for no gaps (1,1,2).
- The CTE (`WITH`) is a MySQL 8 feature; recursive CTEs (`WITH RECURSIVE`) handle hierarchies (org charts, category trees).
- **Time/Space**: aggregation is O(n), the window sort is O(n log n) per partition. Index `(category_id, product_id, amount)` helps the grouping.
- **Edge cases**: categories with fewer than 3 products (returns all of them), null amounts (filter or `COALESCE`).

### Q17. [Practical] A `DELETE FROM logs WHERE created_at < ?` on a huge table locks things up. How do you do it safely?

**Scenario**: deleting 200M old rows in one statement holds a huge transaction, bloats undo, blocks purge, replicates as a giant event, and can stall replicas.

**Approach — batched/chunked delete**:

```sql
-- Loop in application code until 0 rows affected:
DELETE FROM logs
WHERE created_at < '2025-01-01'
ORDER BY created_at
LIMIT 5000;
-- sleep briefly between batches to let purge/replication catch up
```

Better still for *time-based* purging: use **partitioning by range on `created_at`** and `ALTER TABLE ... DROP PARTITION`, which is a near-instant metadata operation with no row-by-row delete and no replication storm.

**Trade-offs**: batched deletes still fragment the table and don't reclaim disk to the OS (use `OPTIMIZE TABLE` or `null` rebuild during a window if needed). Partition-drop is dramatically cheaper but requires designing partitioning up front.

**Production move**: convert the table to RANGE partitions by month, drop old partitions on a schedule, and keep batched delete as the fallback for ad-hoc cleanup. Always run such jobs off-peak and monitor replica lag.

---

## 🟠 Advanced (8–12 yrs)

### Q18. [Theory] Explain MVCC in InnoDB in detail: read views, undo, and the purge thread.

InnoDB implements **MVCC** so readers don't block writers and vice versa. Each row in the clustered index carries two hidden system columns: **`DB_TRX_ID`** (the transaction that last modified it) and **`DB_ROLL_PTR`** (a pointer into the undo log to the previous version).

```
Row (current):  [data v3 | DB_TRX_ID=120 | roll_ptr] --+
                                                        v
Undo: [data v2 | trx=110 | roll_ptr] --> [data v1 | trx=90 | NULL]
```

When a transaction does a consistent (non-locking) read, InnoDB builds a **read view**: a snapshot of which transaction IDs were active/committed at that instant. To read a row, it walks the version chain via `roll_ptr` until it finds a version whose `DB_TRX_ID` is visible to that read view (committed before the snapshot and not in the active set).

- Under **REPEATABLE READ**, the read view is created once (at first read) and reused for the whole transaction → stable snapshot.
- Under **READ COMMITTED**, a fresh read view is created for *each* statement → you see others' committed changes.

Old row versions become garbage once no read view can see them. The background **purge thread(s)** reclaim that undo space. If a long-running transaction (or an idle uncommitted transaction) holds an old read view, purge stalls and **undo/history list length** grows unbounded — a notorious cause of bloat and slow reads. Monitor `Innodb_history_list_length` and hunt long transactions.

### Q19. [Practical] How would you perform an online schema change on a 1-billion-row table with zero downtime?

**Scenario**: add a column / change a type on a hot table. A blocking `ALTER` could hold a metadata lock for hours.

**Options**:

1. **Native online DDL (`ALGORITHM=INPLACE, LOCK=NONE`)** — MySQL 8 can do many ALTERs (add nullable column, add index, add column with `INSTANT` algorithm) online. **`ALGORITHM=INSTANT`** (8.0.12+) adds a column in *metadata only* — milliseconds, no rebuild. Always check whether the change supports INSTANT/INPLACE first; it's the cheapest path. But some changes (e.g., changing a column type, dropping a column pre-8.0.29) still require COPY.

2. **gh-ost** (GitHub) — triggerless: it creates a **shadow table** with the new schema, copies rows in chunks, and tails the **binlog** to apply ongoing changes, then does an atomic cut-over rename. Because it reads the binlog instead of using triggers, it adds no synchronous write overhead on the primary and can throttle based on replica lag. Preferred for very hot tables.

3. **pt-online-schema-change** (Percona) — uses **triggers** on the original table to keep the shadow table in sync while copying. Simpler/older; triggers add write overhead and can be problematic if the table already has triggers.

```
gh-ost cut-over:
  original_table -> _table_old   (rename)
  _table_new     -> table        (atomic swap)
```

**What I'd do**: try native `INSTANT`/`INPLACE` first; if not supported, use gh-ost with conservative throttling on replica lag, run during lower traffic, and have a tested rollback (drop the shadow table). Verify row counts and checksums (`pt-table-checksum`) before cut-over.

### Q20. [Theory] Walk through diagnosing and resolving an InnoDB deadlock.

A deadlock is a cycle of transactions each waiting on a lock the other holds. InnoDB detects cycles and **rolls back the transaction with the least work done** (the "victim"), returning error 1213.

Diagnosis:

```sql
SHOW ENGINE INNODB STATUS\G   -- "LATEST DETECTED DEADLOCK" section
-- or persistent history:
SELECT * FROM performance_schema.data_lock_waits;
SELECT * FROM performance_schema.data_locks;
```

The status output shows the two transactions, the SQL each was running, and which locks they held vs waited for. Common causes:

- **Inconsistent lock ordering**: txn A updates rows in order (1,2), txn B in order (2,1).
- **Gap locks** from range `UPDATE`/`DELETE` under REPEATABLE READ.
- **`INSERT ... ON DUPLICATE KEY UPDATE`** and concurrent inserts on a unique key (gap + insert-intention lock interplay).
- Missing indexes causing full-row locking.

Fixes: acquire locks in a **consistent order** across the codebase, keep transactions short, add precise indexes, lower isolation to READ COMMITTED to drop gap locks, and make the application **retry** the victim transaction (deadlocks are expected and retryable, not bugs). Application-level retry with idempotency is the standard production pattern.

### Q21. [Coding] Implement a deadlock-aware retry wrapper in Java for transactional operations.

**Problem**: deadlocks (SQLState 40001 / error 1213) and lock-wait timeouts (1205) are transient. The robust pattern is exponential-backoff retry around an *idempotent* transaction.

```java
import java.sql.*;
import java.util.concurrent.ThreadLocalRandom;

public <T> T runWithRetry(DataSource ds, int maxAttempts,
                          SqlFunction<Connection, T> work) throws SQLException {
    SQLException last = null;
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                T result = work.apply(c);
                c.commit();
                return result;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            last = e;
            boolean retryable =
                "40001".equals(e.getSQLState())   // deadlock
                || e.getErrorCode() == 1213        // ER_LOCK_DEADLOCK
                || e.getErrorCode() == 1205;       // ER_LOCK_WAIT_TIMEOUT
            if (!retryable || attempt == maxAttempts) throw e;
            long backoff = (long) (Math.pow(2, attempt) * 25)
                + ThreadLocalRandom.current().nextLong(50); // jitter
            try { Thread.sleep(backoff); }
            catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new SQLException("interrupted during retry backoff", ie);
            }
        }
    }
    throw last;
}

@FunctionalInterface
interface SqlFunction<A, R> { R apply(A a) throws SQLException; }
```

- **Time/Space**: O(attempts), bounded by `maxAttempts`; exponential backoff with jitter avoids thundering-herd re-collisions.
- **Edge cases**: the `work` callback **must be idempotent** (it may run multiple times), don't retry non-transient errors (constraint violations), and respect thread interruption.

### Q22. [Practical] Design a partitioning strategy for a multi-tenant time-series events table at scale.

**Scenario**: 10s of TB of events, queried by recent time ranges, with periodic purge of old data.

**Strategy — RANGE partitioning on a time column** (commonly `RANGE COLUMNS(created_at)` or on a `TO_DAYS()`/`YEAR()` expression):

```sql
CREATE TABLE events (
  id BIGINT NOT NULL AUTO_INCREMENT,
  created_at DATETIME NOT NULL,
  tenant_id INT NOT NULL,
  payload JSON,
  PRIMARY KEY (id, created_at)        -- partition key MUST be in every unique key
) PARTITION BY RANGE COLUMNS(created_at) (
  PARTITION p2026_01 VALUES LESS THAN ('2026-02-01'),
  PARTITION p2026_02 VALUES LESS THAN ('2026-03-01'),
  PARTITION pmax     VALUES LESS THAN (MAXVALUE)
);
```

Benefits:

- **Partition pruning**: time-range queries only scan relevant partitions.
- **Instant purge**: `ALTER TABLE events DROP PARTITION p2026_01` removes a month in milliseconds — no row-by-row delete, no replication storm.
- Smaller per-partition indexes → better buffer-pool locality.

**Critical constraints / trade-offs**:

- Every **unique key (incl. PK) must contain the partitioning column** — this often forces a composite PK like `(id, created_at)`, which changes uniqueness semantics and bloats secondary indexes.
- **Foreign keys are not supported** on partitioned tables.
- Queries that *don't* filter on the partition key scan **all** partitions (worse than a non-partitioned table). So partitioning helps only if the access pattern aligns with the partition key.
- For tenant-isolation at extreme scale, consider **application-level sharding** (by tenant) on top of, or instead of, partitioning. Partitioning is not sharding — it's still one server.

**Production reality**: I'd add a scheduled job to pre-create next month's partition and drop the oldest, and keep `tenant_id` as a leading index column for tenant-scoped queries.

### Q23. [Theory] What is Index Condition Pushdown (ICP), Multi-Range Read (MRR), and a covering index, and how do they reduce I/O?

These are optimizer techniques that reduce the expensive **random back-to-table lookups** in InnoDB:

- **Covering index** (`Using index`): the index contains *every* column the query needs, so InnoDB answers entirely from the secondary index B+tree — zero clustered-index lookups. The single most impactful trick; design indexes to "cover" hot queries (include selected/filtered columns, possibly via `INCLUDE`-style trailing columns in the composite).

- **Index Condition Pushdown (ICP)** (`Using index condition`): when the index has columns the `WHERE` clause references but they're not part of the leading equality, MySQL evaluates those conditions **at the index level** before fetching the full row, filtering out non-matches and avoiding wasted clustered-index reads. Without ICP, the server fetches each row then filters.

- **Multi-Range Read (MRR)**: instead of doing random reads to the clustered index in secondary-index order (random I/O), MRR collects the PK values, sorts them, then reads the clustered index in PK order — converting random I/O into more sequential I/O (`Using MRR`). Especially helpful for range scans on spinning disks / cold caches.

```
Without covering index (back-to-table):
  secondary idx leaf -> get PK -> random seek into clustered idx -> row
With covering index:
  secondary idx leaf -> done (all needed columns are here)
```

### Q24. [Practical] Your replicas are lagging badly during peak. How do you diagnose and fix replication lag?

**Diagnose**:

- `SHOW REPLICA STATUS` → `Seconds_Behind_Source`, and check whether the **IO thread** (receiving) or the **SQL/applier threads** (applying) are the bottleneck. Look at `performance_schema.replication_applier_status_by_worker`.
- Is it a **single hot transaction** (one big `UPDATE`/`DELETE`/`ALTER`) serializing the applier? ROW format huge transactions are a classic cause.

**Fixes**:

- **Parallel replication**: set `replica_parallel_workers > 0` with `replica_parallel_type=LOGICAL_CLOCK` and `binlog_transaction_dependency_tracking=WRITESET` so independent transactions apply concurrently on the replica. This is the biggest single lever in MySQL 8.
- **Break up large transactions** on the primary (batched deletes/updates) so they don't serialize.
- Ensure the replica isn't I/O-starved: enough buffer pool, fast storage, and consider `replica_preserve_commit_order` implications.
- For read scaling, route latency-sensitive reads to the **primary** (or to replicas only when `Seconds_Behind_Source` is acceptable), and use semi-sync or read-your-writes routing where consistency matters.
- Reduce write amplification (fewer/leaner indexes, MINIMAL row image) to shrink binlog volume.

**Real-world**: the WRITESET-based parallel applier plus eliminating multi-million-row single statements typically takes lag from minutes to sub-second on write-heavy systems.

### Q25. [Theory] How do auto-increment, `innodb_autoinc_lock_mode`, and UUIDs interact with the clustered index, and what's the right PK strategy at scale?

Because InnoDB clusters by PK, **insert order matters**. A monotonically increasing PK (`BIGINT AUTO_INCREMENT`) appends to the right edge of the B+tree → minimal page splits, good cache locality. A **random PK (UUIDv4)** scatters inserts across the tree → frequent page splits, fragmentation, poor buffer-pool locality, and a 16-byte PK duplicated in every secondary index.

- **`innodb_autoinc_lock_mode`**: `0` (traditional, table-level AUTO-INC lock), `1` (consecutive — default in 5.7), `2` (interleaved — default in 8.0, highest concurrency but gaps and non-deterministic ordering with statement-based binlog; safe with ROW binlog). Mode 2 maximizes insert throughput.
- **Gaps are normal** with AUTO_INCREMENT (rollbacks, mode 2, bulk inserts). Don't assume contiguity.

At scale the recommended strategy is one of:

- **`BIGINT AUTO_INCREMENT`** when single-writer; simplest and fastest.
- **UUIDv7 / ULID** (time-ordered) instead of UUIDv4 if you need globally unique IDs without a central sequence — they're monotonic-ish so they preserve clustered-index locality. Store as `BINARY(16)`, not `CHAR(36)`.
- **Snowflake-style** distributed IDs for sharded systems (time + node + sequence), monotonic and decentralized.

Avoid random UUIDv4 as a clustered PK on large, high-insert tables.

---

## 🔴 Expert (15+ yrs)

### Q26. [Theory] Compare MySQL Group Replication / InnoDB Cluster with external HA (Galera, Vitess, Aurora) and explain the consistency guarantees.

There is a spectrum of MySQL HA/scale-out architectures:

- **InnoDB Cluster** = Group Replication (Paxos-based group communication) + MySQL Router (connection routing) + MySQL Shell (admin). Single-primary by default with automatic failover. Provides **virtually synchronous** replication: a transaction is certified across the group before commit, with conflict detection. You can tune read consistency via `group_replication_consistency` (e.g., `BEFORE`, `AFTER`, `BEFORE_AND_AFTER`) to get read-your-writes / fully linearizable reads at a latency cost.
- **Galera** (Percona XtraDB Cluster / MariaDB) — similar certification-based synchronous replication, multi-primary; mature, but write throughput is bounded by the slowest node and large transactions / hotspots cause certification conflicts (deadlock-on-commit).
- **Vitess** — sharding/orchestration layer (originated at YouTube) that shards MySQL horizontally, handles resharding, connection pooling, and query routing while presenting a logical database. The path to true horizontal write scaling; CNCF-graduated and used by Slack, GitHub, etc.
- **Amazon Aurora MySQL** — re-architected storage: a distributed, log-structured storage layer replicated 6-ways across 3 AZs; the database writes only redo log records, not pages. Gives fast failover and up to 15 low-lag readers, decoupling compute from storage.

**Consistency reality**: classic async replication is eventually consistent (read replicas can be stale). Group Replication / Galera give synchronous *write* guarantees but reads from secondaries can still be stale unless you request stronger consistency. None of these change the single-shard ACID semantics of InnoDB — they change the *availability and durability across nodes*. Choose based on whether you need HA (MGR/Galera/Aurora) vs horizontal write scale (Vitess/app sharding).

### Q27. [Practical] Walk through tuning a write-heavy MySQL 8 instance from first principles. (Industry case study)

**Case study**: a payments service doing ~40k writes/sec was hitting commit latency spikes and periodic stalls.

**Methodology — measure, don't guess** (USE method + `performance_schema` / `sys` schema):

1. **Confirm the bottleneck**: `sys.statements_with_runtimes_in_95th_percentile`, `SHOW ENGINE INNODB STATUS` (look at log-flushing, checkpoint age, free buffers), and OS-level `iostat`. Found: redo log too small → frequent **adaptive flushing** / sharp checkpoints stalling writes.

2. **Redo log sizing**: increase `innodb_redo_log_capacity` (8.0.30+ unified knob; previously `innodb_log_file_size` × `innodb_log_files_in_group`). A larger redo log spreads out checkpoint flushing and smooths write spikes. Sized to absorb ~1 hour of redo at peak.

3. **Flushing & I/O capacity**: set `innodb_io_capacity` / `innodb_io_capacity_max` to match the SSD/NVMe actual IOPS so the background flusher keeps up without over-flushing. Use `innodb_flush_neighbors=0` on SSD.

4. **Durability vs throughput**: kept `innodb_flush_log_at_trx_commit=1` (payments need durability) and `sync_binlog=1`, but enabled **binlog group commit** tuning (`binlog_group_commit_sync_delay`) to batch fsyncs and amortize latency.

5. **Buffer pool**: 70% of RAM, multiple instances; verified the working set fit.

6. **Connection management**: app uses **HikariCP** with a *small* pool (cores × ~2–4, not hundreds) — oversized pools cause context-switch and lock contention, not more throughput.

7. **Hot row / contention**: identified a counter row updated by every transaction → sharded the counter into N rows to spread lock contention (`UPDATE counter_shard WHERE shard = rand_mod_N`).

**Outcome**: P99 commit latency dropped ~5x, stalls eliminated. The lesson: tune the **redo log / flushing pipeline** and **reduce contention**, not random `my.cnf` cargo-cult settings.

### Q28. [Theory] Explain how the binlog and InnoDB redo log are kept consistent on commit (two-phase commit / group commit), and why it matters for crash recovery and replication.

A committed transaction must be atomically reflected in **both** the InnoDB redo log (for the data) and the **binlog** (for replication/PITR). If they could diverge on crash, a replica could have a transaction the primary lost, or vice versa. MySQL coordinates them with an **internal XA two-phase commit**:

```
COMMIT:
  Phase 1 (prepare): InnoDB writes redo log, marks trx PREPARED (fsync)
  -------- write event to binlog, fsync binlog (sync_binlog=1) --------
  Phase 2 (commit):  InnoDB marks trx COMMITTED in redo
Crash recovery: for each PREPARED trx, COMMIT it iff it's in the binlog,
                else ROLLBACK. => binlog is the source of truth.
```

The binlog acts as the **coordinator/source of truth**: on recovery, a transaction that was prepared in InnoDB is committed only if it made it to the binlog. This guarantees the binlog and the data agree, so replicas built from the binlog stay consistent with the primary.

**Group commit** batches the fsyncs across this pipeline (flush/sync/commit stages) so many transactions share fsyncs, dramatically improving throughput when `sync_binlog=1` and `innodb_flush_log_at_trx_commit=1`. `binlog_group_commit_sync_delay` deliberately waits a few microseconds to gather a larger batch. The WRITESET dependency info computed here also feeds the replica's parallel applier. Getting this right is what lets you keep full durability *and* high throughput simultaneously.

### Q29. [Practical] How do you safely migrate a large production MySQL fleet to a new major version (e.g., 5.7 → 8.0) with minimal risk?

**Scenario**: hundreds of databases, 24/7 traffic, can't take downtime.

**Approach**:

1. **Pre-checks**: run **MySQL Shell `util.checkForServerUpgrade()`** to flag removed features, reserved keywords (8.0 added many — `RANK`, `GROUPS`, `CUME_DIST`, etc.), deprecated SQL modes, `utf8` → `utf8mb4` collation changes, and the removal of query cache.
2. **Behavioral diffs to validate**: 8.0 changed the **default collation** (`utf8mb4_0900_ai_ci`) which can alter sort order and unique-key collisions; **GROUP BY no longer implicitly sorts**; `innodb_autoinc_lock_mode` default changed to 2; the **query cache is gone**; histograms and new optimizer behaviors can change plans (regression-test critical queries with `EXPLAIN`).
3. **Roll out via replication**: stand up an 8.0 **replica** under the 5.7 primary (8.0 can replicate from 5.7), let it catch up, run read traffic against it in shadow/canary to validate correctness and plans, then **promote** it (controlled failover with GTIDs) once confident. Repeat fleet-wide in waves.
4. **Rollback plan**: you generally **cannot replicate 8.0 → 5.7** (forward-incompatible), so the rollback is "fail back to the still-running old primary before promotion." Keep the old primary alive until the new one is proven.
5. **Per-database canary**: migrate the least-critical shards first, bake, then expand.

**Key risk**: silent **plan regressions** and **collation-induced uniqueness changes**. Mitigate with query baselining (capture `EXPLAIN` + timings before/after) and a backout window per wave.

### Q30. [Behavioral] Tell me about a time you made a database decision under uncertainty that turned out to be wrong, and how you handled it.

A strong answer uses **STAR** and shows ownership and learning rather than blame:

- **Situation**: "We chose UUIDv4 as the clustered primary key for a new high-write events table because it simplified our distributed ID generation and avoided a central sequence."
- **Task**: "Within a quarter, as the table grew past ~300M rows, insert latency degraded and the buffer pool hit ratio dropped sharply. I owned the storage layer."
- **Action**: "I reproduced it on a staging clone, used `SHOW ENGINE INNODB STATUS` and `information_schema` to confirm massive page splitting and index fragmentation from the random PK scattering inserts across the clustered index. I proposed migrating to **UUIDv7 (time-ordered)** stored as `BINARY(16)`, validated the locality improvement on the clone, then rolled it out with **gh-ost** to avoid downtime and a dual-write/backfill for in-flight rows."
- **Result**: "Insert P99 dropped ~4x and fragmentation stabilized. I wrote a short internal guideline: 'never use random UUIDs as InnoDB clustered keys; use time-ordered IDs or a BIGINT.' I also added a pre-merge schema-review checklist so the same mistake couldn't recur silently."

The interviewer is checking: do you reason from data, own the mistake, fix it without heroics-only, and **institutionalize the lesson** so the team gets better?

### Q31. [Theory] Discuss strategies for horizontal scaling of MySQL writes: sharding, the trade-offs, and how to avoid common failure modes.

A single MySQL primary is ultimately bound by one machine's write capacity. To scale writes you **shard** (partition data across independent MySQL instances). Approaches:

- **Hash sharding** (shard = `hash(tenant_id) % N`): even distribution, but resharding when you add nodes is painful (most keys move). **Consistent hashing** or **range/directory-based** mapping (a lookup service mapping key → shard) eases re-balancing.
- **Vitess** abstracts this: it manages shards, resharding (vreplication), and presents a single logical DB, so the app mostly doesn't see shards. This is how YouTube/Slack scaled MySQL.

Failure modes and mitigations:

- **Cross-shard transactions**: lose single-node ACID; need application-level orchestration or 2PC (slow) — design the shard key so transactions stay within one shard (e.g., shard by `tenant_id` so a tenant's data is co-located).
- **Cross-shard joins / aggregations**: become scatter-gather; push aggregation up or denormalize.
- **Hot shards**: a single huge tenant overwhelms one shard → need the ability to split a shard or isolate large tenants.
- **Distributed unique IDs**: can't use per-table AUTO_INCREMENT globally → use Snowflake/UUIDv7.
- **Schema changes across shards**: must be applied consistently to all shards (gh-ost per shard, orchestrated).

The golden rule: choose a **shard key aligned with your dominant access pattern** so the common queries and transactions touch exactly one shard. Premature sharding is costly — first exhaust vertical scaling, read replicas, caching, and partitioning.

### Q32. [Practical] How would you architect a read-your-own-writes consistent system on top of asynchronous read replicas?

**Scenario**: writes go to the primary, reads are scaled across async replicas (which lag), but a user who just updated their profile must see their own change immediately.

**Approaches**:

1. **Route critical reads to the primary** for a short window after a write (e.g., "sticky to primary for N seconds after any write in this session"). Simple, but loads the primary.
2. **GTID-based wait**: capture the **GTID** of the write, then before a replica read, call `WAIT_FOR_EXECUTED_GTID_SET(gtid, timeout)` on the replica so it blocks until it has applied at least that transaction. Gives true read-your-writes from replicas without overloading the primary. ProxySQL and some drivers support GTID-consistent routing.
3. **Session consistency tokens**: store the last-write LSN/GTID per user session (e.g., in a cookie or cache) and route reads only to replicas that are caught up past that token.
4. **Semi-sync / Group Replication with `AFTER` consistency**: stronger guarantee at higher write latency.

**Trade-offs**: option 1 is easy but doesn't scale; option 2 is the elegant production answer (used with ProxySQL) but adds read latency when the replica is behind and requires GTID plumbing. I'd implement GTID-wait with a fallback to primary if the wait times out, plus monitoring on replica lag so the fallback rate is an SLO signal.

---

## ✅ Key Takeaways

- **InnoDB everywhere**: clustered index = the table is the PK B+tree; keep PKs compact and monotonic (BIGINT or UUIDv7), because the PK is embedded in every secondary index.
- **The buffer pool and redo-log/flushing pipeline are your top tuning levers** — size the buffer pool to the working set and the redo log to smooth checkpoints; measure with `performance_schema`/`sys`, don't cargo-cult `my.cnf`.
- **Design indexes to match access patterns**; aim for covering indexes (`Using index`) and put `WHERE` + `ORDER BY` columns in the right composite order; verify with `EXPLAIN ANALYZE`.
- **Isolation = REPEATABLE READ by default** with MVCC + next-key/gap locks; READ COMMITTED reduces gap locking and deadlocks. Always make transactions short and retry deadlocks.
- **ROW binlog underpins replication, PITR, and CDC** (Debezium/outbox); use GTIDs and WRITESET parallel replication to fight lag.
- **Online schema change**: prefer native `INSTANT`/`INPLACE`, then gh-ost (triggerless) for hot tables; partition-drop beats giant DELETEs for time-series purging.
- **MySQL 8 features**: CTEs (incl. recursive), window functions, descending/functional indexes, histograms, hash join, INSTANT DDL, and the unified redo-log capacity knob.
- **Scaling**: vertical → read replicas + caching → partitioning → sharding (Vitess/Aurora) as the last resort; pick a shard key aligned to your dominant access pattern.

## ⚠️ Common Pitfalls

- **Implicit type conversion** kills index usage: `WHERE phone = 123456` on a `VARCHAR` column, or charset/collation mismatch across joined columns, forces a full scan and silent slowness.
- **Functions on indexed columns** (`WHERE DATE(created_at)=...`, `WHERE UPPER(email)=...`) prevent index use — use range predicates or functional indexes (8.0).
- **N+1 queries** from ORMs (lazy collections); fix with JOIN FETCH / `@EntityGraph` / batch IN-queries.
- **Random UUIDv4 as clustered PK** causes page splits, fragmentation, and bloated secondary indexes on large/hot tables.
- **Deep OFFSET pagination** (`LIMIT 1000000,20`) scans and discards rows; use keyset/seek pagination with a unique tiebreaker.
- **Giant single DELETE/UPDATE** bloats undo, stalls purge, and floods replicas — batch it or use partition drop.
- **Long-running / idle-in-transaction** sessions stall the purge thread and balloon the undo history list, degrading the whole instance.
- **Oversized connection pools** create lock and CPU contention, not more throughput — size HikariCP small.
- **`SELECT *`** defeats covering indexes and bloats network/buffer usage; select only needed columns.
- **Assuming AUTO_INCREMENT is gap-free** — rollbacks and `innodb_autoinc_lock_mode=2` produce gaps by design.
- **Storing money in FLOAT/DOUBLE** — use `DECIMAL`; floating point rounding corrupts financial totals.
- **Forgetting that partitioned tables can't have foreign keys** and that every unique key must include the partition column.

## 📚 Further Reading

- *High Performance MySQL, 4th Edition* — Silvia Botros & Jeremy Tinley (O'Reilly, 2021). The definitive operations/performance text for modern MySQL 8.
- *MySQL 8.0 Reference Manual* — the official docs, especially the InnoDB, Optimization, and Replication chapters: https://dev.mysql.com/doc/refman/8.0/en/
- *Designing Data-Intensive Applications* — Martin Kleppmann (O'Reilly). Storage engines, replication, partitioning, and consistency from first principles.
- *Understanding MySQL Internals* — and the Percona blog (https://www.percona.com/blog/) for deep-dive InnoDB internals, gh-ost/pt-osc, and tuning case studies.
- **gh-ost** documentation (https://github.com/github/gh-ost) and **Debezium** docs (https://debezium.io/documentation/) for online schema change and CDC.
- **Vitess** documentation (https://vitess.io/docs/) for horizontal sharding of MySQL.
