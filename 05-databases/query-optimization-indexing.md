# Query Optimization & Indexing

A deep, interview-focused guide to how relational databases store, find, and join data — and how staff engineers diagnose and fix slow queries in production. Examples use Java (JDBC / JPA / Spring Data) and PostgreSQL/MySQL SQL where the engine matters.

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

### Q1. [Theory] What is a database index and what problem does it solve?

An index is an auxiliary, ordered data structure that lets the database locate rows matching a predicate without scanning the entire table. Without an index, finding `WHERE email = 'x@y.com'` forces a **full table scan** — O(N) rows read. With a B-tree index on `email`, the engine navigates the tree in O(log N) to find matching row pointers, then fetches just those rows. The trade-off is that every index must be kept in sync on `INSERT`/`UPDATE`/`DELETE`, costing extra write I/O and storage. So an index trades slower writes and more disk for dramatically faster reads on indexed predicates. Indexes are the single most impactful lever in query performance, which is why this topic dominates database interviews.

### Q2. [Theory] How does a B-tree index actually work internally?

Relational databases use a **B+tree** (a B-tree variant). Internal nodes hold only keys and child pointers for navigation; all actual keys and row pointers live in the **leaf level**, and the leaves are linked together in a doubly-linked list. This structure gives two superpowers: O(log N) point lookups (descend from root to leaf) and efficient **range scans** (find the start leaf, then walk the linked list sequentially). The tree is kept balanced and shallow — even a billion-row table is typically only 3–4 levels deep, so a lookup touches only a handful of pages.

```
                    [ 50 | 100 ]              <- root (internal)
                   /     |      \
          [10|30]    [60|80]     [120|150]    <- internal nodes
          /  |  \                   |   \
       ...leaves (sorted keys + row pointers, linked left<->right)...
       [1..9]<->[10..29]<->[30..49]<->[60..79] ...
```

Because leaves are sorted and linked, `ORDER BY indexed_col`, `BETWEEN`, `>`, `<`, and prefix `LIKE 'abc%'` can all be served by the index. A leading-wildcard `LIKE '%abc'` cannot, because the sort order is on the prefix.

### Q3. [Theory] What is the difference between a clustered and a non-clustered index?

A **clustered index** determines the physical order of rows on disk — the table *is* the index, with the full row data stored in the leaf nodes. A table can have only one clustered index. A **non-clustered (secondary) index** is a separate structure whose leaves store the indexed key plus a pointer back to the row.

The key engine difference:
- **SQL Server / MySQL InnoDB**: the table is stored as a clustered index keyed on the primary key (InnoDB uses the PK; secondary indexes store the PK as the row pointer).
- **PostgreSQL**: the table is a heap (unordered); *all* indexes are non-clustered and point to a physical tuple ID (`ctid`). `CLUSTER` only reorders once and is not maintained.

```
Clustered (InnoDB, PK = id):        Non-clustered secondary (on email):
  leaf -> [id=5 | full row data]      leaf -> [email | id=5]  -- then lookup PK
```

The practical consequence: in InnoDB, a secondary index lookup does *two* B-tree traversals (secondary index → PK → clustered index), unless the index covers the query.

### Q4. [Practical] You have `SELECT * FROM users WHERE email = ?`. How do you make it fast, and how do you verify?

Add an index on `email` and confirm with the execution plan. In Java with Spring Data JPA you would typically declare it on the entity, but the index lives in DDL:

```sql
CREATE INDEX idx_users_email ON users (email);
EXPLAIN ANALYZE SELECT * FROM users WHERE email = 'a@b.com';
```

Before the index the plan shows `Seq Scan on users` with a high cost and rows read; after it shows `Index Scan using idx_users_email`. In production I would also make it a `UNIQUE` index if email is unique — that doubles as a correctness constraint and lets the planner know at most one row matches. Then I would run `EXPLAIN ANALYZE` (not just `EXPLAIN`) to see actual vs. estimated rows, confirming the planner's statistics are accurate.

### Q5. [Coding] Write a JDBC method that fetches a user by email, and explain why the query shape matters for indexing.

**Problem:** Implement a parameterized lookup that is both injection-safe and index-friendly.

```java
import java.sql.*;
import java.util.Optional;

public class UserDao {
    private final DataSource dataSource;

    public UserDao(DataSource dataSource) { this.dataSource = dataSource; }

    public Optional<User> findByEmail(String email) {
        // Parameterized: the value is bound, never concatenated -> safe from SQL injection
        // AND lets the DB cache/reuse the prepared plan.
        final String sql = "SELECT id, email, created_at FROM users WHERE email = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new User(
                        rs.getLong("id"),
                        rs.getString("email"),
                        rs.getTimestamp("created_at").toInstant()));
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new DataAccessException("findByEmail failed", e);
        }
    }
}
```

**Why query shape matters:** Using `WHERE email = ?` keeps the column "bare" on the left side. If you wrote `WHERE LOWER(email) = ?` the engine cannot use `idx_users_email` because the function destroys the index ordering — you would need a **functional index** `CREATE INDEX ON users (LOWER(email))`. Wrapping an indexed column in a function or arithmetic (`WHERE created_at + INTERVAL '1 day' > now()`) is the most common accidental cause of full scans.

- **Time:** O(log N) with the index; O(N) without.
- **Space:** O(1) extra in the method; the index itself is O(N) on disk.
- **Edge cases:** null email (the `= ?` won't match SQL `NULL` — use `IS NULL`), duplicate emails (use `UNIQUE`), connection leaks (try-with-resources closes them).

### Q6. [Theory] What does `SELECT *` cost you, and why do interviewers flag it?

`SELECT *` reads and transfers every column, which (1) inflates network and memory I/O, (2) breaks **covering-index** optimizations because the index rarely contains all columns so the engine must do extra heap/clustered lookups, and (3) makes code brittle when columns are added. In a Java app it also forces the ORM to hydrate fields you don't use. Selecting only needed columns (`SELECT id, email`) can let an index on `(email) INCLUDE (id)` satisfy the whole query from the index alone — an index-only scan.

---

## 🟡 Intermediate (3–7 yrs)

### Q7. [Theory] What is a composite index, and why does column order matter (the leftmost-prefix rule)?

A composite (multi-column) index sorts rows by the first column, then by the second within equal first values, and so on — like a phone book sorted by (last name, first name). The **leftmost-prefix rule** says an index on `(a, b, c)` can serve queries filtering on `a`, on `a, b`, or on `a, b, c`, but **not** a query filtering only on `b` or only on `c`, because the index isn't sorted by those without the prefix.

```
Index on (status, created_at):
  ('ACTIVE', 2026-01-01)
  ('ACTIVE', 2026-02-01)   <- WHERE status='ACTIVE' AND created_at>'...' : index range scan
  ('ACTIVE', 2026-03-01)
  ('CLOSED', 2026-01-15)   <- WHERE created_at>'...' alone : CANNOT use this index efficiently
```

Rule of thumb for ordering: put **equality predicates first**, then the column you **range/scan** on, then columns only needed for **output** (covering). Also weigh **selectivity** — a more selective leading column narrows the search faster, though equality-vs-range placement usually dominates.

### Q8. [Practical] Given `WHERE status = ? AND created_at > ? ORDER BY created_at`, what single index would you build?

`CREATE INDEX idx ON orders (status, created_at)`. The equality on `status` anchors the leftmost prefix, then `created_at` serves both the range filter *and* the `ORDER BY` — the index leaves are already sorted by `created_at` within each `status`, so the engine returns rows in order with **no sort step**. If you reversed it to `(created_at, status)`, the `status` equality could not be used as an index seek and the engine would scan a wide date range filtering `status` row by row. In production I'd confirm via `EXPLAIN ANALYZE` that the plan shows an `Index Scan` with no separate `Sort` node and that `Rows Removed by Filter` is near zero.

### Q9. [Theory] What is a covering index / index-only scan?

A covering index contains every column a query needs (in `SELECT`, `WHERE`, `ORDER BY`), so the engine answers entirely from the index without touching the table heap — a PostgreSQL **Index Only Scan** or SQL Server "covered query." This eliminates the second lookup (the "bookmark lookup" / heap fetch), often a 2–10x speedup.

```sql
-- Query: SELECT user_id, total FROM orders WHERE status = 'PAID';
CREATE INDEX idx_orders_paid ON orders (status) INCLUDE (user_id, total);
--  PostgreSQL/SQL Server: INCLUDE keeps payload columns in leaves
--  MySQL: list them in the key: (status, user_id, total)
```

Caveat for PostgreSQL: an index-only scan still needs the **visibility map** to confirm the tuple is visible to your transaction; if pages aren't all-visible (stale `VACUUM`), it falls back to heap fetches. So covering indexes pair with healthy autovacuum.

### Q10. [Theory] Define selectivity and cardinality. How do they drive the planner's choice?

**Cardinality** is the number of distinct values in a column; **selectivity** is the fraction of rows a predicate returns (low fraction = highly selective). A `gender` column has cardinality 2 → low selectivity → an index is nearly useless (matching half the table is cheaper via scan). A `uuid` column has near-unique cardinality → high selectivity → an index is very effective. The optimizer estimates selectivity from **statistics** (histograms, distinct counts) and chooses an index scan only when the estimated matching rows are a small enough fraction that random index+heap I/O beats sequential scan. This is why an index on a low-selectivity column is often *ignored* by the planner — and correctly so.

### Q11. [Practical] How do you read a PostgreSQL `EXPLAIN ANALYZE` plan? Walk through the key signals.

I read it bottom-up (leaf operations execute first) and look for specific red flags:

```
Limit  (cost=0.43..8.51 rows=10) (actual time=0.05..0.07 rows=10 loops=1)
  -> Index Scan using idx_orders_user on orders
       (cost=0.43..210.5 rows=250) (actual time=0.04..0.06 rows=10 loops=1)
       Index Cond: (user_id = 42)
       Rows Removed by Filter: 0
Planning Time: 0.2 ms
Execution Time: 0.1 ms
```

Signals I check:
- **Scan type:** `Seq Scan` on a large table with a selective predicate = missing/unused index.
- **Estimated vs actual `rows`:** a big mismatch (e.g. est 250, actual 50,000) means **stale statistics** → run `ANALYZE`.
- **`Rows Removed by Filter`:** high means the index isn't selective enough or the predicate isn't index-covered.
- **`loops` on inner nodes:** a Nested Loop with thousands of loops doing index lookups can be slower than a Hash Join.
- **`Sort` / `Sort Method: external merge Disk`:** spilling to disk → raise `work_mem` or add an index that provides order.
- **Buffers (with `EXPLAIN (ANALYZE, BUFFERS)`):** shared read vs hit shows cache effectiveness.

### Q12. [Coding] Implement keyset (seek) pagination instead of `OFFSET`, and explain why.

**Problem:** Paginate a large `orders` feed sorted by `created_at DESC, id DESC`. `OFFSET 1000000 LIMIT 20` forces the engine to generate and discard a million rows — O(offset). Keyset pagination is O(log N) per page regardless of depth.

```java
import java.sql.*;
import java.time.Instant;
import java.util.*;

public class OrderFeedDao {
    private final DataSource ds;
    public OrderFeedDao(DataSource ds) { this.ds = ds; }

    /** Pass the last row of the previous page as the cursor; null for page 1. */
    public List<Order> nextPage(Instant afterCreatedAt, Long afterId, int limit) {
        final String sql =
            "SELECT id, created_at, total FROM orders " +
            // (created_at, id) is a unique tie-break so no row is skipped/duplicated
            "WHERE (created_at, id) < (?, ?) " +
            "ORDER BY created_at DESC, id DESC " +
            "LIMIT ?";
        List<Order> out = new ArrayList<>(limit);
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            // For the first page, pass "infinity" sentinels so all rows qualify.
            ps.setTimestamp(1, Timestamp.from(
                afterCreatedAt != null ? afterCreatedAt : Instant.MAX.minusSeconds(1)));
            ps.setLong(2, afterId != null ? afterId : Long.MAX_VALUE);
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Order(rs.getLong("id"),
                                      rs.getTimestamp("created_at").toInstant(),
                                      rs.getBigDecimal("total")));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return out;
    }
}
```

Backed by `CREATE INDEX ON orders (created_at DESC, id DESC)`, each page is a single index seek to the cursor then a 20-row walk.

- **Time:** OFFSET pagination O(offset + limit) per page → catastrophic deep pages; keyset O(log N + limit) — constant per page.
- **Space:** O(limit).
- **Edge cases:** non-unique sort column (must add a unique tie-breaker like `id` or you skip/duplicate rows), first page sentinel, jumping to an arbitrary page number isn't possible (acceptable for infinite-scroll/feeds).

### Q13. [Practical] When do indexes *hurt*? Talk about write amplification.

Every secondary index is a separate B-tree that must be updated on `INSERT`, on `DELETE`, and on `UPDATE` of any indexed column. A table with 8 indexes turns one logical insert into ~9 B-tree modifications plus more WAL/redo logging and page splits — this is **write amplification**. I have seen ingestion tables where dropping unused indexes tripled insert throughput. Indexes also bloat storage, lengthen backups, and consume buffer-pool memory. In production I audit usage with `pg_stat_user_indexes` (`idx_scan = 0` → candidate for removal) or MySQL's `sys.schema_unused_indexes`, and for high-write tables I keep the index set minimal, sometimes dropping/recreating indexes around bulk loads.

### Q14. [Theory] What is parameter sniffing / plan caching and how can it go wrong?

To avoid re-planning identical queries, engines cache an execution plan keyed on the SQL text. On the *first* execution they "sniff" the bound parameter values and build a plan optimized for those values. The problem: if the first call passes a value matching 1 row, the engine caches an index-seek plan; a later call passing a value matching 5 million rows reuses that seek plan and runs disastrously slowly (or vice versa). Fixes by engine: SQL Server `OPTIMIZE FOR UNKNOWN` / `RECOMPILE` / Query Store plan forcing; PostgreSQL's prepared statements switch to a generic plan after 5 executions (tunable via `plan_cache_mode`); Oracle adaptive cursor sharing. In a Java/Hibernate app this surfaces as "the same query is fast for most tenants but pathologically slow for one large tenant" — a classic data-skew symptom.

### Q15. [Practical] A query intermittently goes slow after a big data load. What's your first hypothesis?

**Stale statistics.** After a bulk load, the planner's row-count and histogram estimates lag reality, so it picks bad plans (e.g. nested-loop join expecting 10 rows when there are now 10M). My first move is `ANALYZE the_table;` (or `ANALYZE` on the affected columns) and re-check `EXPLAIN ANALYZE` for estimate-vs-actual convergence. In PostgreSQL I verify `autovacuum`/`autoanalyze` thresholds aren't set too high for a fast-growing table, and consider raising `default_statistics_target` for skewed columns. If estimates are right but the plan is still bad, I look at parameter sniffing and missing/multi-column statistics next.

---

## 🟠 Advanced (8–12 yrs)

### Q16. [Practical] Give a full, real-world workflow for identifying and fixing a slow query in production.

This is the workflow I actually run:

```
1. DETECT   -> APM (Datadog/New Relic) flags p99 latency; pg_stat_statements
               ranks queries by total_exec_time, mean_time, calls.
2. CAPTURE  -> Grab the exact normalized SQL + a representative parameter set.
3. EXPLAIN  -> EXPLAIN (ANALYZE, BUFFERS) with realistic params on a replica.
4. DIAGNOSE -> Seq scan? stale stats? bad join order? sort spill? N+1 from ORM?
5. HYPOTHESIZE + FIX (one change at a time):
                 add/adjust index, rewrite predicate, fix ORM fetch,
                 update stats, partition, or cache.
6. VALIDATE -> Re-run EXPLAIN ANALYZE; compare actual time & buffers; load test.
7. SHIP SAFELY -> CREATE INDEX CONCURRENTLY (no table lock), behind a migration,
                  monitor before/after dashboards, keep a rollback (DROP INDEX).
```

The two highest-yield findings in practice are (a) a missing index causing a seq scan on a hot path, and (b) an ORM **N+1** problem where one parent query spawns N child queries. `pg_stat_statements` is the single best starting point because it surfaces the queries consuming the most *aggregate* time, not just the slowest single execution.

### Q17. [Coding] Detect and fix an N+1 query problem in Spring Data JPA.

**Problem:** Loading 100 orders and their line items issues 1 + 100 queries.

```java
// PROBLEM: lazy collection -> N+1. One query for orders, then one per order.
@Entity
class Order {
    @Id Long id;
    @OneToMany(mappedBy = "order", fetch = FetchType.LAZY)
    List<LineItem> items;
}
// service:
List<Order> orders = orderRepo.findByStatus("PAID"); // 1 query
for (Order o : orders) {
    total += o.getItems().size();                     // +1 query EACH -> 100 queries
}
```

**Fix A — JPQL fetch join (one query):**
```java
public interface OrderRepository extends JpaRepository<Order, Long> {
    @Query("SELECT DISTINCT o FROM Order o LEFT JOIN FETCH o.items WHERE o.status = :s")
    List<Order> findWithItems(@Param("s") String status);
}
```

**Fix B — entity graph (declarative, reusable):**
```java
@EntityGraph(attributePaths = "items")
List<Order> findByStatus(String status);
```

**Fix C — batch fetching** (when a fetch-join row explosion is undesirable):
```properties
# application.properties (Hibernate)
spring.jpa.properties.hibernate.default_batch_fetch_size=100
```
This turns N child queries into `N/batchSize` `IN (...)` queries.

- **Time:** N+1 problem is O(N) round trips; fetch join is O(1) round trip. Network round-trip latency, not CPU, is the killer here.
- **Edge cases:** fetch-joining **two** collections multiplies rows (cartesian product) — fetch one collection per query or use batch fetching; `DISTINCT` dedups the cartesian join in Fix A; pagination + fetch join warns "applied in memory" in Hibernate — use batch fetching with pagination instead.

### Q18. [Theory] When and how would you use denormalization and materialized views?

Normalization minimizes redundancy and keeps writes consistent, but read-heavy analytical or fan-out queries (joining 6 tables to render a dashboard) can be too slow. **Denormalization** duplicates data (e.g. storing `order_count` on `users`, or a JSON blob of a rendered view) to trade write complexity for read speed. **Materialized views** persist the result of a query physically and serve it like a table, refreshed on a schedule or trigger.

```sql
CREATE MATERIALIZED VIEW user_order_stats AS
  SELECT user_id, COUNT(*) AS orders, SUM(total) AS lifetime_value
  FROM orders GROUP BY user_id;
REFRESH MATERIALIZED VIEW CONCURRENTLY user_order_stats; -- no read lock; needs a unique index
```

Trade-offs: materialized views serve **stale** data between refreshes and `REFRESH` is expensive; denormalized columns risk drift if not updated transactionally (use triggers, CDC, or application-level consistency). I reach for these only after indexing and query rewrites are exhausted, and I quantify the freshness SLA the business can tolerate. A classic real-world case: Instagram and similar feeds precompute/denormalize "fan-out on write" timelines rather than joining at read time, because read volume dwarfs write volume.

### Q19. [Theory] Explain table partitioning. When does it help and when does it backfire?

Partitioning splits one logical table into physical chunks by a key — **range** (by date), **list** (by region), or **hash** (even distribution). The big win is **partition pruning**: a query with `WHERE event_date >= '2026-06-01'` touches only the relevant partition(s), and dropping old data becomes an instant `DROP PARTITION` instead of a massive `DELETE`. It also localizes index maintenance and vacuum to the hot partition.

```
orders (partitioned by RANGE on created_at)
  ├── orders_2026_q1   <- pruned away for a Q2 query
  ├── orders_2026_q2   <- only this scanned
  └── orders_2026_q3
```

It backfires when (a) queries don't filter on the partition key, so they scan *all* partitions (worse than one table), (b) you create too many partitions (planning overhead), or (c) you need cross-partition unique constraints (the unique key must include the partition key). Partitioning is for very large tables (hundreds of millions+ rows) or clear time-based lifecycle — it is not a substitute for indexing.

### Q20. [Coding] Write SQL + a Java check to find missing-index candidates and confirm with a plan.

**Problem:** Programmatically surface the highest-cost queries and verify an index helps.

```java
// Pull the worst offenders from pg_stat_statements (requires the extension).
public List<SlowQuery> topByTotalTime(int n) {
    String sql =
        "SELECT query, calls, total_exec_time, mean_exec_time, rows " +
        "FROM pg_stat_statements " +
        "ORDER BY total_exec_time DESC " +   // aggregate impact, not single slowest
        "LIMIT ?";
    List<SlowQuery> result = new ArrayList<>();
    try (Connection c = ds.getConnection();
         PreparedStatement ps = c.prepareStatement(sql)) {
        ps.setInt(1, n);
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(new SlowQuery(
                    rs.getString("query"),
                    rs.getLong("calls"),
                    rs.getDouble("total_exec_time"),
                    rs.getDouble("mean_exec_time")));
            }
        }
    } catch (SQLException e) { throw new RuntimeException(e); }
    return result;
}
```

Then for a suspect query, fetch the plan as JSON and assert it no longer seq-scans:

```java
public boolean usesIndexScan(String querySql) {
    try (Connection c = ds.getConnection();
         Statement st = c.createStatement();
         ResultSet rs = st.executeQuery("EXPLAIN (FORMAT JSON) " + querySql)) {
        rs.next();
        String plan = rs.getString(1);
        return plan.contains("Index Scan") || plan.contains("Index Only Scan");
    } catch (SQLException e) { throw new RuntimeException(e); }
}
```

- **Time:** the diagnostic queries are cheap; the value is in directing effort to the queries with the largest `total_exec_time` (calls × mean), which is where index work pays off most.
- **Edge cases:** never interpolate untrusted SQL into `EXPLAIN` (injection); `pg_stat_statements` normalizes parameters so you must supply realistic literals to reproduce skew; resetting stats (`pg_stat_statements_reset()`) between deploys gives clean before/after comparisons.

### Q21. [Practical] A `JOIN` between two large tables is slow. How do you reason about join strategy?

I check which **physical join algorithm** the planner chose and whether it fits the data:

```
Nested Loop : best when outer side is tiny AND inner side has an index on the join key.
              O(outer * lookup). Deadly if outer is large (millions of index probes).
Hash Join   : best for large, unsorted inputs with an equality join. Builds a hash
              table on the smaller side; O(n+m) but needs work_mem (else spills to disk).
Merge Join  : best when both inputs are already sorted on the join key (e.g. via index).
```

The fix path: ensure both join columns are indexed and the **same data type** (a `bigint = varchar` join silently casts and skips the index). If a Nested Loop is iterating millions of times, I either add the inner index, or nudge the planner toward a Hash Join by fixing the bad row estimate (statistics) that made it think the outer side was tiny. I also confirm join columns have matching collation and that no implicit function (e.g. `ON a.id = b.id::text`) defeats the index.

### Q22. [Theory] How do partial and functional indexes reduce cost?

A **partial index** indexes only rows matching a predicate, shrinking the index and its maintenance cost — ideal for skewed boolean/status columns where you only ever query a slice:

```sql
-- Only 0.5% of rows are unprocessed, but they're queried constantly:
CREATE INDEX idx_jobs_pending ON jobs (created_at) WHERE status = 'PENDING';
```
This index is tiny, fits in cache, and skips maintenance for the 99.5% of rows that flip to other statuses. A **functional (expression) index** indexes a computed value so a transformed predicate stays sargable:

```sql
CREATE INDEX idx_users_lower_email ON users (LOWER(email));
-- now WHERE LOWER(email) = ? uses the index
```

Both are surgical tools: partial indexes win on highly skewed filters; functional indexes win when the app unavoidably queries a transformation. The catch is the query predicate must match the index expression/condition *exactly* for the planner to use it.

### Q23. [Practical] How do you choose between adding a read replica, caching, and indexing for a read-heavy hot path?

Order of operations by cost and blast radius: **index/query-rewrite first** (cheapest, no new infra, fixes the root cause), **caching second** (Redis/in-process for expensive-to-compute or rarely-changing results, but adds invalidation complexity and staleness), **read replicas last** (scales raw read throughput but adds replication lag and routing logic, and doesn't fix an inefficient query — it just buys more hardware to run it). I'd never add a replica to mask a missing index; that's paying to run a bad query in parallel. The decision hinges on whether the bottleneck is *query efficiency* (index/rewrite), *recomputation cost* (cache), or *raw connection/throughput saturation* (replica/connection pooling). In a Spring app I'd also check the HikariCP pool isn't the actual bottleneck before touching the database.

---

## 🔴 Expert (15+ yrs)

### Q24. [Theory] How do MVCC and index design interact in PostgreSQL, and why can a "good" index still be slow?

PostgreSQL's MVCC keeps multiple **row versions**; an `UPDATE` writes a new tuple and marks the old one dead. Every index entry points to a physical tuple, so an update to an *indexed* column writes new index entries, and dead tuples accumulate as **bloat** until `VACUUM` reclaims them. Consequences experts watch for: (1) an index-only scan still consults the **visibility map**, so if autovacuum lags, the "index-only" scan does heap fetches anyway and is slow; (2) HOT (heap-only tuple) updates avoid index churn *only* when no indexed column changes and there's free space on the page — so over-indexing a frequently-updated column kills HOT and amplifies bloat; (3) index bloat itself enlarges the tree, adding levels. So a structurally perfect index can underperform because of vacuum health, fillfactor, and write patterns — the index is necessary but not sufficient. This is why I tune `fillfactor`, autovacuum aggressiveness, and minimize indexes on hot-update columns.

### Q25. [Practical] Walk through diagnosing a query that is fast in staging but slow in production with identical schema.

```
Hypotheses, ranked by likelihood, with the check for each:
1. DATA VOLUME / SKEW   -> prod has 1000x rows or a skewed tenant.
                           Check pg_stat_statements + EXPLAIN ANALYZE w/ prod params.
2. STALE / WRONG STATS  -> planner estimates diverge from actuals in prod only.
                           Check est-vs-actual rows; run ANALYZE.
3. PARAMETER SNIFFING   -> prod cached a plan for an atypical first param value.
                           Check generic vs custom plan; force RECOMPILE/reset.
4. CONFIG DRIFT         -> work_mem/effective_cache_size/random_page_cost differ;
                           SSD vs HDD assumptions. Compare SHOW ALL.
5. CONCURRENCY / LOCKS  -> prod has lock waits, vacuum, replication backpressure.
                           Check pg_stat_activity, pg_locks, lock_waits.
6. CACHE STATE          -> prod buffer pool cold or evicted; staging fully cached.
                           EXPLAIN (ANALYZE, BUFFERS) shared read vs hit.
```

The single most common real culprit is **data skew + parameter sniffing**: a query that's fast for the median tenant becomes pathological for the one whale customer whose data is 10,000x larger, and the cached plan was built for a small tenant. I'd reproduce with the whale's parameters, confirm the bad plan, and fix via better statistics, a partial/covering index for that access path, or query hints/plan forcing.

### Q26. [Theory] Discuss index strategy for distributed/NewSQL and columnar systems versus classic B-trees.

The B-tree model assumes a single node and random-access storage; modern systems diverge. **LSM-tree** stores (Cassandra, RocksDB, ScyllaDB) optimize writes by appending to memtables and flushing sorted SSTables, trading read amplification (must merge multiple SSTables, mitigated by Bloom filters) for huge write throughput — the opposite trade-off from B-trees. **Columnar/analytics** engines (Snowflake, ClickHouse, BigQuery, Parquet) skip row indexes entirely in favor of **min/max zone maps**, column pruning, and compression — you "index" by clustering/sort keys and partitioning, not by B-trees. **Distributed SQL** (CockroachDB, Spanner, TiDB, YugabyteDB) shards B-tree-like indexes across ranges/tablets, so index/shard key choice now also dictates data locality and hot-spotting (a monotonic key like `auto_increment` or `now()` creates a write hotspot on one shard — you hash or reverse it). The expert lesson: "indexing" generalizes to *physical data layout for the access pattern*, and the right answer depends on the storage engine's read/write asymmetry.

### Q27. [Practical] How do you ship a new index to a 500M-row production table with zero downtime?

```sql
-- PostgreSQL: build without an exclusive write lock (slower, but online).
CREATE INDEX CONCURRENTLY idx_orders_status_created ON orders (status, created_at);
-- If it fails midway it leaves an INVALID index -> DROP INDEX CONCURRENTLY then retry.
```

The full playbook: build with `CONCURRENTLY` (PostgreSQL) / `ALGORITHM=INPLACE, LOCK=NONE` (MySQL 8 / InnoDB) / `ONLINE = ON` (SQL Server Enterprise) so reads and writes continue. I run it during a low-traffic window because it still adds write overhead and a long-running transaction. I monitor replication lag (the index build replays on replicas and can stall them), disk space (the build needs temp space), and `pg_stat_progress_create_index`. I gate it behind a reversible migration with an explicit `DROP INDEX CONCURRENTLY` rollback, validate with `EXPLAIN ANALYZE` on a canary, and watch the before/after p99 dashboards. For truly massive tables I sometimes build on a replica, then promote, or use partitioning to index partition-by-partition.

### Q28. [Behavioral] Tell me about a time you disagreed with a team that wanted to "just add more indexes" to fix slowness.

I frame this with the situation, the data, and the outcome. On one service, the team's reflex was to add an index per slow query; the table already had 11 indexes and write latency was climbing because of write amplification. I pulled `pg_stat_user_indexes` and showed that 5 of the 11 indexes had `idx_scan = 0` over 30 days — pure write tax. Rather than argue from authority, I proposed an experiment: drop the unused indexes in staging under a replayed production write load and measure. Insert p99 dropped ~40%. For the *actual* slow read, the root cause was an ORM N+1, not a missing index — a fetch-join fixed it with zero new indexes. The lesson I emphasize in interviews: indexes are a cost, not free; the discipline is to measure usage and target the real bottleneck, and to disagree with evidence and a cheap reversible experiment rather than opinion. The team adopted an "index budget + usage audit" practice afterward.

### Q29. [Theory] What advanced statistics features close the gap when single-column stats mislead the planner?

Single-column statistics assume column independence, which breaks on **correlated columns** — e.g. `city` and `zip_code`. The planner multiplies selectivities (`P(city) * P(zip)`) and wildly underestimates rows, picking a nested loop that explodes. PostgreSQL's **extended statistics** fix this:

```sql
CREATE STATISTICS s_city_zip (dependencies, ndistinct, mcv)
  ON city, zip_code FROM addresses;
ANALYZE addresses;
```

`dependencies` captures functional dependency, `ndistinct` captures multi-column distinct counts, and `mcv` captures most-common-value combinations. Other levers: raising `default_statistics_target` for skewed columns (bigger histograms), Oracle's optimizer dynamic sampling and SQL Plan Baselines, SQL Server's multi-column statistics and Query Store. The expert insight is that *most "the optimizer is dumb" complaints are actually statistics problems* — give the planner accurate cardinality estimates and it usually picks the right plan; correlated predicates are the classic blind spot.

### Q30. [Practical] How do you build observability so slow queries are caught before customers complain?

I layer it: (1) **continuous query stats** — `pg_stat_statements` (or MySQL Performance Schema / `sys` views) scraped into Prometheus/Grafana, alerting on regressions in `mean_exec_time` and `total_exec_time` per normalized query; (2) **slow query log** with a sane threshold (e.g. `log_min_duration_statement = 200ms`) shipped to a log pipeline; (3) **APM tracing** (OpenTelemetry) that ties a slow span back to the exact endpoint and SQL, exposing N+1 patterns as fan-out traces; (4) **plan regression detection** — capture plans for top queries and diff them across deploys so a plan flip is caught immediately; (5) **CI guardrails** — fail the build if a migration adds a query that seq-scans a large table, or run `EXPLAIN` assertions in integration tests. The goal is to shift detection left: the database tells you about its hot, regressing queries continuously, so optimization is a steady backlog item rather than a 2 a.m. incident.

---

## ✅ Key Takeaways

- Indexes turn O(N) scans into O(log N) lookups via balanced **B+trees** with sorted, linked leaves — enabling both point lookups and range scans/ordering.
- **Column order** in composite indexes follows the leftmost-prefix rule: equality columns first, then the range/sort column, then covering payload columns.
- **Covering indexes / index-only scans** eliminate the heap fetch; **selectivity & cardinality** decide whether the planner even uses an index.
- Indexes are a **write tax** (write amplification, bloat, storage) — audit usage (`pg_stat_user_indexes`) and drop dead indexes.
- Read execution plans bottom-up; the biggest tells are **estimate-vs-actual row mismatches** (stale stats) and unexpected **seq scans** or disk **sorts**.
- Prefer **keyset pagination** over `OFFSET`, fix **ORM N+1** with fetch joins/entity graphs, and reach for **denormalization, materialized views, and partitioning** only after indexing and rewrites.
- Use `pg_stat_statements` to target the queries with the largest *aggregate* time, ship indexes with `CREATE INDEX CONCURRENTLY`, and verify with `EXPLAIN ANALYZE` before/after.

## ⚠️ Common Pitfalls

- Wrapping an indexed column in a function/arithmetic (`LOWER(email)`, `col + 1`) — kills index usage; use a functional index or rewrite to keep the column bare (sargable).
- Building `(a, b)` when queries filter only on `b` — the leftmost-prefix rule makes the index useless for that pattern.
- `SELECT *` defeating covering indexes and bloating I/O.
- Indexing low-cardinality columns (booleans, status with 2 values) and expecting a speedup — the planner correctly ignores them.
- Forgetting `ANALYZE` after bulk loads, then blaming the optimizer for bad plans.
- `OFFSET`-based deep pagination that degrades linearly with page depth.
- Joining columns of mismatched types (`bigint` vs `varchar`) — silent cast disables the index.
- Over-indexing high-write tables and ignoring write amplification, MVCC bloat, and lost HOT updates.
- Running a blocking `CREATE INDEX` (no `CONCURRENTLY`) on a hot table and locking out writes.
- Assuming correlated columns are independent — extended/multi-column statistics are needed to avoid catastrophic underestimates.

## 📚 Further Reading

- *SQL Performance Explained* — Markus Winand (and the companion site **use-the-index-luke.com**) — the definitive, vendor-neutral guide to B-tree indexing.
- *Designing Data-Intensive Applications* — Martin Kleppmann — Chapter 3 on storage engines (B-trees vs LSM-trees) and indexing trade-offs.
- *Database Internals* — Alex Petrov — deep dive on B-tree/LSM implementation, page layout, and buffer management.
- PostgreSQL official docs — "Performance Tips," `EXPLAIN`, `pg_stat_statements`, and "Extended Statistics."
- MySQL Reference Manual — "Optimization" and "Understanding the Query Execution Plan."
- *High Performance MySQL* (4th ed.) — Silvia Botros & Jeremy Tinley — practical indexing, schema, and EXPLAIN workflows.
