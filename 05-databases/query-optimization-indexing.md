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

## 🧩 Extended Questions — Set 1: Deeper Theory & Practical Operations

### 🟢 Basic — extended

#### Q31. [Theory] What is the difference between `EXPLAIN` and `EXPLAIN ANALYZE`, and when is each appropriate?

`EXPLAIN` shows the planner's **chosen plan and its cost estimates** without running the query — it is fast and side-effect-free. `EXPLAIN ANALYZE` actually **executes** the query and reports *actual* row counts, timing, and loop counts alongside the estimates. The whole art of plan reading is comparing the two numbers: a plan that *looks* fine on estimates can be catastrophic when the actuals diverge (the planner thought 10 rows, got 10 million).

Use plain `EXPLAIN` when you only want to see *which* index/join the planner picks, when the query is expensive and you don't want to pay to run it, or when the statement mutates data (an `EXPLAIN ANALYZE DELETE` really deletes — wrap it in a transaction you roll back). Use `EXPLAIN ANALYZE` when you are diagnosing *why* something is slow and need ground truth.

```sql
BEGIN;
EXPLAIN ANALYZE DELETE FROM staging WHERE batch_id = 42;  -- runs for real!
ROLLBACK;  -- undo the side effect
```

The interview tell is knowing that estimates come from statistics and can lie, while actuals come from execution and cannot — so `ANALYZE` (the run) is how you catch a lying optimizer, and `ANALYZE` (the command, confusingly the same word) is how you fix it by refreshing those statistics.

#### Q32. [Practical] You added an index but the query is still doing a sequential scan. List the reasons the planner ignores it.

This is one of the most common real-world frustrations, and there is a finite checklist. Walk it top to bottom:

```
1. Non-sargable predicate   -> WHERE LOWER(email)=? , col+1=?, col::text=? — function/cast
                               on the indexed column destroys index usability.
2. Low selectivity          -> predicate matches a large fraction of rows; a seq scan is
                               genuinely cheaper than index+heap random I/O. Planner is RIGHT.
3. Stale statistics         -> planner thinks the table is tiny; run ANALYZE.
4. Type mismatch            -> WHERE bigint_col = '123' (text literal) or join across types.
5. Leftmost-prefix miss     -> index (a,b) but query filters only on b.
6. Small table              -> whole table fits in a few pages; seq scan wins, by design.
7. Index just built / invalid -> CREATE INDEX CONCURRENTLY failed and left it INVALID.
```

The single biggest cause in my experience is the **non-sargable predicate** — an application accidentally wraps the column in a function (often via an ORM that emits `CAST` or `LOWER`). The second is the planner being *correct*: on a 1,000-row table or a predicate matching 40% of rows, the sequential scan is the optimal plan and forcing the index would be slower. Always confirm with `EXPLAIN ANALYZE` whether the seq scan is actually a problem before assuming it is — sometimes the "fix" is to do nothing.

#### Q33. [Theory] What does it mean for a predicate to be "sargable," and why does the term matter?

**SARGable** stands for "Search ARGument able" — a predicate the engine can satisfy by **seeking** into an index rather than evaluating against every row. A predicate is sargable when the indexed column appears bare on one side and is compared with `=`, `>`, `<`, `BETWEEN`, `IN`, or a prefix `LIKE 'abc%'`. It becomes non-sargable the moment you transform the column with a function, arithmetic, or a cast, because the index is ordered on the *raw* column value, not the transformed one.

```sql
-- NON-sargable: function on the column -> seq scan
WHERE EXTRACT(YEAR FROM created_at) = 2026
WHERE created_at::date = '2026-06-16'
WHERE amount * 1.1 > 100

-- SARGable rewrites: keep the column bare, move the math to the constant side
WHERE created_at >= '2026-01-01' AND created_at < '2027-01-01'
WHERE created_at >= '2026-06-16' AND created_at < '2026-06-17'
WHERE amount > 100 / 1.1
```

The term matters because "make it sargable" is the most frequent, highest-leverage query rewrite in practice. It turns an O(N) scan into an O(log N) seek with no schema change — just moving the transformation off the column. When you cannot rewrite (the app genuinely needs `LOWER(email)`), the answer is a **functional index** on the exact expression.

#### Q34. [Practical] How do you decide whether a column even deserves an index? Give the heuristics.

I weigh how the column is *used* against what an index costs. The green-light signals: the column appears in `WHERE`, `JOIN ON`, or `ORDER BY` of frequent queries; it is reasonably **selective** (a predicate returns a small fraction of rows); and the table is read-heavy or at least read-often-enough that the read win outweighs the write tax. Foreign-key columns almost always deserve an index — not just for joins but because some engines lock the parent on FK checks and unindexed FKs cause lock escalation.

The red-light signals: very low cardinality (booleans, a status with two values) where the planner will ignore the index anyway; a column on a write-hot table that is rarely filtered on (pure write amplification); or a column already covered by the leftmost prefix of an existing composite index (redundant). A rough decision sketch:

```
                 frequently filtered/joined/sorted?
                        │ no            │ yes
                   don't index    high selectivity?
                                   │ no        │ yes
                            low-card filter?   index it
                            │ partial index    (consider composite/covering)
                            │ if skewed slice
```

The discipline is that an index is a standing cost paid on every write forever, so the bar is "does a real, frequent query benefit enough to justify that" — not "could this column theoretically be searched." I validate the hypothesis with `pg_stat_statements` (is this query actually hot?) and, after shipping, with `pg_stat_user_indexes.idx_scan` (is the index actually used?).

#### Q35. [Theory] What is the difference between a unique index and a unique constraint, and which should you reach for?

Logically they enforce the same thing — no two rows share the indexed value — and in PostgreSQL/MySQL a unique *constraint* is **implemented by** creating a unique *index* under the hood. The difference is intent and capability. A unique constraint is declarative schema-level metadata (it can be the target of a foreign key, it shows up cleanly in `information_schema`, and ORMs and tooling recognize it as a business rule). A unique index is a storage object you can build with options a constraint cannot express: it can be **partial** (`WHERE deleted_at IS NULL`), it can be on an **expression** (`LOWER(email)`), and it can be built **concurrently** without a long lock.

```sql
-- Constraint: clean, declarative, FK-targetable
ALTER TABLE users ADD CONSTRAINT uq_users_email UNIQUE (email);

-- Unique index: can be partial / expression / built online
CREATE UNIQUE INDEX CONCURRENTLY uq_users_email_live
  ON users (LOWER(email)) WHERE deleted_at IS NULL;
```

Reach for a **constraint** when the rule is a plain whole-column uniqueness you want documented as a business invariant and possibly referenced by FKs. Reach for a **unique index** when you need partiality, an expression, soft-delete-aware uniqueness, or online creation on a large live table. A common production pattern is exactly the second one above: "email must be unique among non-deleted users," which a plain constraint cannot express.

### 🟡 Intermediate — extended

#### Q36. [Theory] Compare B-tree, Hash, GIN, GiST, and BRIN indexes. When do you pick each?

A B-tree is the default and right answer for the vast majority of cases (equality, ranges, ordering, prefix `LIKE`). The others are specialized for access patterns a B-tree serves poorly:

| Index type | Best for | Supports | Notes |
|---|---|---|---|
| **B-tree** | equality, range, `ORDER BY`, prefix LIKE | `= < > BETWEEN`, sorts | the default; balanced, O(log N) |
| **Hash** | pure equality only | `=` | no ranges/sorts; rarely worth it over B-tree in PG |
| **GIN** | multi-value columns: JSONB, arrays, full-text | containment `@>`, `?`, `@@` | slower writes; great for "does this doc contain X" |
| **GiST** | geometric, ranges, nearest-neighbour, fuzzy | `&&`, `<->` (KNN), overlap | extensible; used by PostGIS, range types |
| **BRIN** | huge, naturally-ordered tables (time-series) | range, when physical order ~ value order | tiny on disk; stores per-block min/max |

```sql
CREATE INDEX idx_doc_tags  ON documents USING GIN (tags);          -- array containment
CREATE INDEX idx_geo_loc   ON places    USING GIST (location);     -- spatial / KNN
CREATE INDEX idx_events_ts ON events    USING BRIN (created_at);   -- append-only by time
```

The decision hinges on data shape and query operator. **GIN** when a single row holds many searchable values (JSONB keys, array elements, text lexemes) and you ask "contains." **GiST** for geometry, overlapping ranges, and nearest-neighbour ("10 closest stores"). **BRIN** is the sleeper pick for append-only time-series: it is hundreds of times smaller than a B-tree because it only stores min/max per block range, and it works *only* when the physical row order correlates with the column value (insert-ordered timestamps). If you `UPDATE` and reshuffle that table, BRIN degrades to near-uselessness.

#### Q37. [Practical] A `LIKE '%term%'` search on a text column is slow. Walk through your options.

A leading-wildcard `LIKE '%term%'` is fundamentally **non-sargable for a B-tree** because the B-tree is ordered on the prefix, and there is no prefix here — the engine must scan every row. Throwing a normal index at it does nothing. The options, roughly in order of effort:

```sql
-- 1. Trigram index (pg_trgm) — makes %term% and ILIKE indexable
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX idx_products_name_trgm ON products USING GIN (name gin_trgm_ops);
SELECT * FROM products WHERE name ILIKE '%widget%';   -- now uses the GIN trigram index

-- 2. Full-text search — for word/language-aware search, not raw substring
CREATE INDEX idx_docs_fts ON docs USING GIN (to_tsvector('english', body));
SELECT * FROM docs WHERE to_tsvector('english', body) @@ to_tsquery('widget');
```

Pick the **trigram (pg_trgm) GIN index** when you genuinely need arbitrary substring/`ILIKE`/fuzzy matching — it breaks text into 3-character grams and indexes those, so `%widget%` becomes a containment lookup. Pick **full-text search** when you actually want word-level, stemmed, ranked search ("running" matches "run") rather than literal substrings. If the requirement is really *prefix* search (`term%`, no leading wildcard), a plain B-tree already works and you need nothing fancy. For serious search workloads (typo tolerance, relevance ranking, facets at scale), the honest answer in an interview is to offload to a dedicated engine like Elasticsearch/OpenSearch rather than bending the OLTP database.

#### Q38. [Theory] Explain index fragmentation/bloat and how rebuilding (`REINDEX`) helps — and its risks.

Over time, B-tree pages fill, **split**, and as rows are deleted/updated, leaves end up partly empty — the index occupies more pages than its live entries need. This **bloat** (PostgreSQL, driven by MVCC dead tuples) or **fragmentation** (SQL Server/MySQL, logical vs physical page order divergence) means a lookup touches more pages, more of the buffer pool is wasted on dead space, and range scans read extra I/O. A once-fast index quietly degrades even though its definition is unchanged.

```sql
-- PostgreSQL: rebuild online (PG12+), no long write lock
REINDEX INDEX CONCURRENTLY idx_orders_status;
-- check bloat first (pgstattuple or community bloat queries)

-- SQL Server: REBUILD (heavier) vs REORGANIZE (online, lighter)
ALTER INDEX idx_orders_status ON orders REBUILD WITH (ONLINE = ON);
```

`REINDEX`/`REBUILD` writes a fresh, densely-packed index and drops the bloated one, restoring lookup efficiency and reclaiming disk. The risks: a non-concurrent rebuild takes a heavy lock (use `CONCURRENTLY`/`ONLINE`), it needs roughly the index's size again in free disk for the new copy, and it generates significant WAL/log and I/O — so schedule it off-peak and monitor replication lag. The deeper fix for *recurring* bloat is healthy `autovacuum` and an appropriate `fillfactor` (leaving free space in pages so updates do in-place HOT updates instead of churning); `REINDEX` treats the symptom, vacuum tuning treats the cause.

#### Q39. [Coding] Write a query and supporting index to get the latest row per group efficiently, and explain the index choice.

**Problem:** For each `user_id`, return their most recent `order` (greatest `created_at`). The naive `GROUP BY` + correlated subquery or `MAX()` self-join is slow on large tables.

```sql
-- Index that makes the per-group lookup an index walk, not a full scan + sort:
CREATE INDEX idx_orders_user_created ON orders (user_id, created_at DESC);

-- PostgreSQL DISTINCT ON: one fast pass using that index order
SELECT DISTINCT ON (user_id) user_id, id, created_at, total
FROM orders
ORDER BY user_id, created_at DESC;
```

```java
// JDBC: portable window-function variant (works on MySQL 8, PG, SQL Server)
public List<Order> latestPerUser() {
    String sql =
        "SELECT user_id, id, created_at, total FROM (" +
        "  SELECT user_id, id, created_at, total, " +
        "         ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY created_at DESC) rn " +
        "  FROM orders) t WHERE rn = 1";
    // ... standard try-with-resources execute + map ...
    return run(sql);
}
```

The index `(user_id, created_at DESC)` is the whole trick: PostgreSQL's `DISTINCT ON` (or the `ROW_NUMBER` filter) can walk the index in `(user_id, created_at DESC)` order and take the **first** row of each `user_id` group without a sort or a heap scan of the rest. Without the descending second column the engine still finds the group boundaries but must sort within each group.

- **Time:** with the index, roughly O(distinct users × log N); without it, O(N log N) for the sort over all rows.
- **Space:** O(distinct users) for the result.
- **Edge cases:** ties on `created_at` (add `id` to the `ORDER BY` for determinism), users with zero orders (a `LEFT JOIN LATERAL` is needed if you must include them), and `DISTINCT ON` being PostgreSQL-specific — use the `ROW_NUMBER` form for portability.

#### Q40. [Practical] How do connection pooling and prepared-statement caching interact with query performance?

People obsess over the query plan and forget that *acquiring a connection* and *parsing/planning the statement* are themselves costs. Opening a fresh database connection involves a TCP handshake, authentication, and backend process/thread startup — tens of milliseconds, often dwarfing a sub-millisecond indexed lookup. A **connection pool** (HikariCP in Java, PgBouncer at the infra layer) amortizes that by reusing warm connections. If the pool is undersized, requests queue waiting for a connection and you see "slow queries" that are actually *connection-wait* time, not execution time — a misdiagnosis I have watched cost teams days.

**Prepared-statement caching** layers on top: a `PreparedStatement` is parsed and planned once, then re-executed with new bound parameters, skipping the parse/plan step. This is a real win for hot queries, but it interacts with two things. First, **plan caching can backfire on skewed data** (the parameter-sniffing problem from Q14) — a cached generic plan may not suit every parameter. Second, the cache is **per-connection**, so it only helps if the pool reuses connections rather than churning them.

```properties
# HikariCP + PostgreSQL JDBC: enable server-side prepared statements
spring.datasource.hikari.maximum-pool-size=20
spring.datasource.hikari.data-source-properties.prepareThreshold=3
spring.datasource.hikari.data-source-properties.preparedStatementCacheQueries=256
```

A critical gotcha: if you put **PgBouncer in `transaction` pooling mode** in front of PostgreSQL, server-side prepared statements break (a statement prepared on one backend may execute on another), so you must disable them or use a PgBouncer version with prepared-statement support. The interview-level point is that "slow query" is sometimes a connection-management problem, and the fix is pool sizing and statement reuse, not a new index.

#### Q41. [Theory] What is the cost-based optimizer actually optimizing, and what inputs does it use?

A cost-based optimizer (CBO) does not know what is "fast" — it builds an abstract **cost number** for each candidate plan and picks the cheapest. That cost is a weighted estimate of resource usage: number of pages read (sequential vs random — random is charged more, e.g. PostgreSQL's `random_page_cost` of 4.0 vs `seq_page_cost` of 1.0), CPU per row/operator, and for some engines memory and parallelism. It enumerates plan alternatives — different indexes, join orders, join algorithms (nested loop / hash / merge), scan types — and costs each, then chooses the minimum.

The two inputs that decide everything are **statistics** and **configuration**. Statistics (row counts, distinct values, histograms, most-common-values, null fraction) let it estimate how many rows each operator emits — and since cost compounds up the plan tree, a bad cardinality estimate at a leaf produces a wildly wrong total cost and a wrong plan. Configuration constants (`random_page_cost`, `effective_cache_size`, `work_mem`, `cpu_tuple_cost`) tell it the *hardware reality*: on SSDs random access is far cheaper than the default 4.0 assumes, so leaving `random_page_cost` at the spinning-disk default makes the planner irrationally avoid indexes.

```sql
-- On SSD/cloud storage, tell the planner random I/O is cheap so it uses indexes more:
SET random_page_cost = 1.1;
-- Tell it how much OS+DB cache exists so it trusts that index pages are cached:
SET effective_cache_size = '24GB';
```

The expert framing: "the optimizer made a dumb choice" is almost always "the optimizer was fed wrong cardinality estimates (stats) or wrong hardware costs (config)." Fix the inputs and the CBO usually finds the plan you wanted. This is why tuning `random_page_cost` for SSDs and keeping statistics fresh are higher-leverage than fighting individual plans.

#### Q42. [Practical] Your `ORDER BY ... LIMIT 10` query is slow even though the sort column is indexed. What's going on?

The classic trap: an index on the sort column exists, but the **filter** uses a *different* column, so the engine cannot use one index to both filter and order. It either filters via one index then sorts the survivors (a `Sort` node, slow if many survivors), or walks the sort-ordered index applying the filter row by row, scanning far past the `LIMIT` looking for 10 matches.

```sql
-- Query: WHERE status = 'ACTIVE' ORDER BY created_at DESC LIMIT 10;

-- BAD: separate indexes — planner picks one, then sorts or over-scans
CREATE INDEX idx_status  ON orders (status);
CREATE INDEX idx_created ON orders (created_at DESC);

-- GOOD: composite index serves filter AND order; LIMIT becomes a 10-row walk
CREATE INDEX idx_status_created ON orders (status, created_at DESC);
```

With `(status, created_at DESC)`, the engine seeks to the `status='ACTIVE'` slice — already sorted by `created_at DESC` within it — and reads exactly 10 leaf entries. The `EXPLAIN` plan shows an `Index Scan` with **no `Sort` node**, which is the signal you want. The failure mode to watch for is when only `idx_created` exists and `status` is selective: the planner may walk the whole date-ordered index newest-first, discarding non-`ACTIVE` rows, and if active rows are sparse it scans thousands of rows to find 10 — a `LIMIT` that should be instant becomes a near-full scan. The fix is the composite index ordering equality-column-first, range/sort-column-second, exactly per the leftmost-prefix rule (Q7).

### 🟠 Advanced — extended

#### Q43. [Theory] Explain the read/write amplification trade-off between B-trees and LSM-trees in depth.

B-trees and LSM-trees sit at opposite ends of the read/write-amplification spectrum, and the choice encodes the workload's read/write ratio. A **B-tree** updates data **in place**: to change a row it locates the leaf page and rewrites it. Reads are cheap (one O(log N) descent, low *read* amplification) but writes incur **write amplification** — a small logical change rewrites a whole page, may trigger page splits, and is journaled (WAL/redo). Random writes scatter across the tree, causing random I/O.

An **LSM-tree** (Cassandra, RocksDB, ScyllaDB, modern MySQL MyRocks) instead **buffers writes in memory** (a memtable) and flushes them as immutable, sorted files (SSTables) via sequential I/O — so writes are fast and write-amplification-friendly at flush time. The cost moves to reads: a key may live in the memtable or any of several SSTables, so a read may probe multiple files (**read amplification**), mitigated by **Bloom filters** (skip SSTables that definitely lack the key) and a sparse index per SSTable. Background **compaction** merges SSTables to bound read amplification — which itself reintroduces write amplification, just deferred and sequential.

```
B-tree write:  update -> find page -> rewrite page (+ WAL) -> maybe split   [random, in-place]
LSM write:     append to memtable -> flush sorted SSTable -> compact later   [sequential, deferred]

                read amp        write amp        best for
B-tree          low             higher           read-heavy, point+range, OLTP
LSM-tree        higher          lower (deferred)  write-heavy ingest, time-series, logs
```

The staff-level insight: there is no free lunch — you choose *where* to pay. Pick B-trees (PostgreSQL/InnoDB) when reads dominate and you need cheap range scans and strong point-read latency; pick LSM when ingest volume dominates and you can tolerate read amplification and background compaction overhead. RUM conjecture frames it formally: you can optimize at most two of Read, Update, and Memory amplification, never all three.

#### Q44. [Practical] How do you safely roll out a risky index change with the ability to measure and revert?

I treat an index change like any production change: reversible, measured, and gated. The build itself must be online — `CREATE INDEX CONCURRENTLY` (PostgreSQL), `ALGORITHM=INPLACE, LOCK=NONE` (InnoDB), `ONLINE=ON` (SQL Server Enterprise) — so it never blocks the hot path. But "it built" is not "it helped," so the rollout has phases:

```bash
# 1. Baseline BEFORE: snapshot the target query's plan + timing on a replica
psql -c "SELECT pg_stat_statements_reset();"   # clean window
# capture mean_exec_time / total_exec_time for the query id

# 2. Build online, watching progress + replication lag
psql -c "CREATE INDEX CONCURRENTLY idx_orders_status_created ON orders(status, created_at);"
psql -c "SELECT * FROM pg_stat_progress_create_index;"

# 3. Verify it's VALID (CONCURRENTLY can fail half-built)
psql -c "SELECT indexrelid::regclass, indisvalid FROM pg_index WHERE NOT indisvalid;"

# 4. Measure AFTER: same query, compare plan (EXPLAIN) + pg_stat_statements deltas

# 5. Rollback path, always ready:
psql -c "DROP INDEX CONCURRENTLY idx_orders_status_created;"
```

The discipline points: build on a **replica or canary first** if the table is enormous; **monitor replication lag** because the index build replays downstream and can stall replicas; ensure **free disk** for the build's temporary space; and keep the migration **reversible** with an explicit `DROP INDEX CONCURRENTLY` rollback step, never a destructive irreversible one. I also leave the index in place for a full traffic cycle (including the weekly/monthly batch jobs) before declaring victory, because an index that helps the online path can change the plan of a nightly report for the worse. The non-negotiable is that at every moment I can answer "what is the before/after number" and "how do I revert in one command."

#### Q45. [Theory] What is a "tipping point" in the optimizer, and why does the same query flip between index scan and seq scan?

The tipping point is the **selectivity threshold** at which the cost of an index scan crosses the cost of a sequential scan, and the planner switches plans. An index scan pays **random I/O** per matching row (jump to the index leaf, then jump to the heap page) plus the index traversal. A sequential scan pays cheap **sequential I/O** for the whole table but reads every row. When few rows match, random access to just those rows wins; as the matching fraction grows, the random I/O of fetching each one eventually exceeds just streaming the whole table sequentially — so beyond some fraction (often surprisingly low, like 5–20% depending on row size and `random_page_cost`), the seq scan becomes cheaper and the planner flips.

```
cost
 │            seq scan (flat: reads all rows regardless of match %)
 │   ─────────────────────────────────────
 │  /  index scan (rises with matching rows: random I/O per row)
 │ /
 │/  tipping point ── below: index wins; above: seq scan wins
 └────────────────────────────────────────► fraction of rows matched
```

This explains a confusing production symptom: a query is fast (index scan) for a parameter that matches 200 rows and slow (seq scan) for one that matches 2,000,000 — and *both plans are correct* for their inputs. It also explains why a parameter-sniffed cached plan is dangerous: the plan chosen at the tipping point's wrong side is reused for the other side. The levers that move the tipping point: `random_page_cost` (lower on SSDs pushes the crossover higher, favoring indexes), correct statistics (so the estimated fraction matches reality), covering indexes (eliminate the heap-fetch random I/O, flattening the index-scan cost line and pushing the tipping point far right), and clustering/correlation (a physically-clustered index makes the "random" fetches sequential).

#### Q46. [Coding] Implement an idempotent, online index migration in a real migration tool (Flyway/Liquibase) and explain the safeguards.

**Problem:** Add a covering index to a large live PostgreSQL table from a migration that must not lock the table, must be safely re-runnable, and must not silently leave a half-built index.

```sql
-- V42__add_orders_status_created_idx.sql  (Flyway)
-- CONCURRENTLY cannot run inside a transaction block, so disable Flyway's wrapper:
-- (Flyway: name the file so it's detected; set the script config below)

-- flyway.executeInTransaction=false   <- in V42...conf or config

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_orders_status_created
    ON orders (status, created_at) INCLUDE (total, user_id);
```

```yaml
# Liquibase equivalent with the matching safeguards
databaseChangeLog:
  - changeSet:
      id: add-orders-status-created-idx
      author: prashant
      runInTransaction: false          # CONCURRENTLY needs autocommit
      changes:
        - sql:
            sql: >-
              CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_orders_status_created
              ON orders (status, created_at) INCLUDE (total, user_id)
      rollback:
        - sql:
            sql: DROP INDEX CONCURRENTLY IF EXISTS idx_orders_status_created
```

The safeguards, each of which I have seen omitted and cause an incident:

- **`runInTransaction=false` / `executeInTransaction=false`** — `CREATE INDEX CONCURRENTLY` throws if wrapped in a transaction, which is exactly what migration tools do by default. Forgetting this fails the deploy.
- **`IF NOT EXISTS`** — makes the migration **idempotent**: a retried deploy (after a network blip) does not error on the existing index.
- **An explicit `rollback` with `DROP INDEX CONCURRENTLY IF EXISTS`** — gives a non-locking, re-runnable revert.
- **Post-deploy validity check** (out of band): `CONCURRENTLY` can fail mid-build and leave an **INVALID** index that still costs writes but isn't used; a health check queries `pg_index WHERE NOT indisvalid` and pages if found, so the operator can `DROP ... CONCURRENTLY` and retry.

- **Time/Space:** the build is O(N log N) and needs temporary disk roughly the size of the finished index; it runs longer than a blocking build but holds no write lock.
- **Edge cases:** a failed `CONCURRENTLY` build (invalid index left behind), a migration retried after partial success (handled by `IF NOT EXISTS`), and replication lag during the build on downstream replicas (monitor, don't fire-and-forget).

#### Q47. [Practical] A nightly batch job and a daytime OLTP query share a table and keep "fighting" over the plan/cache. How do you diagnose and resolve it?

This is a workload-interference problem, and the symptoms are telling: the OLTP query is fine all day, then after the batch job runs (or while it runs) the OLTP query's plan flips or its latency spikes. There are a few distinct mechanisms and I check which one is in play:

```
1. STATS CHURN     -> batch loads/deletes millions of rows; autoanalyze fires and the
                      planner's stats now reflect the batch's data shape, not OLTP's.
2. PLAN/PARAM CACHE -> a shared cached plan got (re)built during the batch with batch-
                      shaped parameters; OLTP reuses that ill-fitting plan.
3. CACHE EVICTION  -> the batch's big scan evicts the OLTP hot pages from the buffer
                      pool; OLTP then pays cold-cache read I/O.
4. LOCK / VACUUM   -> batch holds locks or triggers aggressive vacuum that competes for I/O.
```

The diagnosis path is to correlate the latency spike's *timing* with the batch schedule (APM + `pg_stat_statements` snapshots before/after the batch), then check estimate-vs-actual rows (stats churn), generic-vs-custom plan (cache), and `EXPLAIN (ANALYZE, BUFFERS)` shared-read vs hit (cache eviction). The resolutions differ by cause: for **stats churn**, lock down statistics or run a targeted `ANALYZE` after the batch to restore OLTP-favorable estimates; for **plan cache**, force a recompile after the batch or use `OPTIMIZE FOR`/`plan_cache_mode` so OLTP gets its own plan; for **cache eviction**, isolate the batch to a **read replica** or run it off-peak so it doesn't trample the OLTP working set; for **lock/vacuum contention**, batch in smaller chunks with commits and tune `autovacuum_vacuum_cost_delay`. The strategic answer is **workload isolation** — route analytical/batch traffic to a replica so the two workloads stop sharing a buffer pool, plan cache, and statistics surface. This is the operational version of "OLTP and OLAP want opposite physical layouts."

#### Q48. [Theory] How does index design change for write-heavy versus read-heavy tables? Contrast the strategies.

The two workloads pull in opposite directions, and a staff engineer designs the index set for whichever one dominates rather than reflexively indexing everything. On a **read-heavy** table (a product catalog read millions of times, written rarely) indexes are nearly free in relative terms: I add composite and covering indexes liberally to make every hot query an index-only scan, accepting the modest write cost because writes are rare. The optimization target is read latency, and more (well-chosen) indexes is usually right.

On a **write-heavy** table (an event/ingest table, an audit log, a metrics sink) every index is a tax paid on every insert — write amplification (Q13), more WAL, page splits, and lost HOT updates (Q24). Here I keep the index set **minimal and surgical**: only the indexes that serve genuinely hot reads, prefer **partial indexes** to index just the queried slice, consider **BRIN** instead of B-tree for naturally time-ordered data (tiny, cheap to maintain), and sometimes **drop indexes during bulk loads** and rebuild after. I also avoid indexing **monotonically increasing keys in a way that creates a hotspot** in distributed systems, and watch for the right-hand-edge contention on the rightmost leaf of a B-tree under append-heavy inserts.

```
Read-heavy strategy                 Write-heavy strategy
─────────────────────               ─────────────────────
+ many composite/covering indexes   - minimal index set (write tax matters)
+ index-only scans everywhere       + partial indexes (just the hot slice)
+ materialized views OK             + BRIN for time-ordered append data
  (read latency is the goal)        + drop/rebuild around bulk loads
                                     + tune fillfactor, autovacuum, HOT updates
```

The unifying principle: an index converts write effort into read speed, so the index budget should be set by the read/write ratio. The mistake interviews probe for is treating a high-ingest table like a read table and drowning it in indexes — or the reverse, under-indexing a read-mostly table out of misplaced write-cost anxiety.

#### Q49. [Practical] You see `Rows Removed by Filter` is huge in an `EXPLAIN ANALYZE`. What does it mean and how do you fix it?

`Rows Removed by Filter` is the count of rows the engine **read but then discarded** because they failed a predicate that the access method couldn't satisfy by itself. It is a direct measure of wasted work: the engine paid I/O and CPU to fetch rows it immediately threw away. A small number is fine; a number that dwarfs the rows actually returned means the chosen index (or seq scan) is fetching a wide swath and filtering in a second step instead of *seeking* precisely.

```
-- Symptom: index narrows on status, but month filter is applied as a post-filter
Index Scan using idx_orders_status on orders
   Index Cond: (status = 'PAID')
   Filter: (created_at >= '2026-06-01' AND created_at < '2026-07-01')
   Rows Removed by Filter: 1,840,222     <-- read 1.85M, returned ~9K
```

The cause is almost always that **only part of the predicate is an index condition** (`Index Cond`) while the rest is a **post-scan `Filter`**. The fix is to extend the index so the second predicate also becomes a seek, not a filter — here, a composite `(status, created_at)` turns the date range into an `Index Cond` and the filter count collapses toward zero:

```sql
CREATE INDEX idx_orders_status_created ON orders (status, created_at);
-- now: Index Cond: (status='PAID' AND created_at >= '...' AND created_at < '...')
--      Rows Removed by Filter: 0
```

Other causes and fixes: a **non-sargable predicate** forcing a filter (rewrite to sargable form, Q33); **stale statistics** making the planner pick a poor index (run `ANALYZE`); or a **partial index** whose condition would eliminate the filter entirely. The general principle is to move predicates from the `Filter` line up into the `Index Cond` line, because anything in `Filter` is rows you paid to read and discarded.

### 🔴 Expert — extended

#### Q50. [Theory] Explain how the join order search space and join-order optimization (e.g. PostgreSQL's GEQO) affect plan quality on many-table joins.

For an N-table join the number of possible join orders explodes super-exponentially (the number of binary trees over N relations, ~N! for left-deep orderings and far more counting bushy trees). The optimizer cannot enumerate all of them past a handful of tables, so it uses **dynamic programming** (System R style) to build optimal sub-plans bottom-up while pruning dominated ones — but DP itself becomes too expensive beyond ~12 tables. At that point PostgreSQL switches to **GEQO** (Genetic Query Optimizer), a heuristic that *samples* the join-order space with a genetic algorithm rather than exhaustively searching it. The threshold is `geqo_threshold` (default 12).

The practical consequence: on a query joining 15+ tables, the planner may pick a **non-optimal join order** simply because it stopped searching — and the chosen order can vary run to run (GEQO is randomized). Symptoms are an otherwise-inexplicable bad plan on a big join, or plan instability. Levers an expert reaches for:

```sql
-- Let the exhaustive search go further (costs planning time, but better plans):
SET geqo = off;                    -- disable genetic heuristic for this session
SET join_collapse_limit = 16;      -- let the planner reorder more explicit JOINs
SET from_collapse_limit = 16;      -- flatten more subqueries into the join search
-- Or pin order by writing JOINs in the intended order with join_collapse_limit = 1.
```

The deeper points: **join order dominates cost** because an early join that produces a huge intermediate result poisons everything above it, so cardinality estimation at the leaves matters most on big joins (a wrong estimate picks a bad order). Reducing the *number* of joined tables (denormalization, pre-aggregation, splitting the query) is often more effective than fighting the optimizer. And `join_collapse_limit = 1` is the blunt-but-effective escape hatch: it forces the planner to honor the literal `JOIN` order you wrote, trading the optimizer's freedom for determinism when you know better.

#### Q51. [Practical] Design an indexing and partitioning strategy for a 10-billion-row time-series events table with mixed recent-OLTP and historical-analytics access.

I start from the access patterns because they dictate physical layout. The workload is bimodal: hot OLTP-ish queries hit **recent** data filtered by entity and time (`WHERE device_id = ? AND ts >= now() - interval '1 hour'`), while analytics scans **wide time ranges** aggregating across entities. No single layout serves both, so I combine **range partitioning by time** with **per-partition indexing**, and isolate analytics to a replica.

```sql
-- Range-partition by time so pruning + cheap data lifecycle (DROP old partitions)
CREATE TABLE events (
    ts          timestamptz NOT NULL,
    device_id   bigint NOT NULL,
    payload     jsonb
) PARTITION BY RANGE (ts);

-- Daily (or hourly) partitions; old ones become read-only / detachable
CREATE TABLE events_2026_06_16 PARTITION OF events
    FOR VALUES FROM ('2026-06-16') TO ('2026-06-17');

-- Hot partitions: B-tree for the OLTP access path (entity + time)
CREATE INDEX ON events_2026_06_16 (device_id, ts DESC);

-- Cold/historical partitions: BRIN — tiny, perfect for append-ordered scans
CREATE INDEX ON events_2026_05_01 USING BRIN (ts);
```

The reasoning, point by point. **Range partition by `ts`** gives partition pruning (a 1-hour query touches one partition, not 10B rows) and turns data retention into an instant `DROP TABLE`/`DETACH PARTITION` instead of a catastrophic `DELETE`. **Different indexes per partition lifecycle**: recent partitions get a B-tree `(device_id, ts DESC)` to serve the point-in-time OLTP lookups with index seeks and no sort; old partitions get **BRIN on `ts`**, which is hundreds of times smaller than a B-tree and ideal because the data is physically time-ordered and only ever scanned in ranges. **Partition key in the unique key**: any uniqueness must include `ts` since cross-partition unique constraints aren't supported. **Workload isolation**: analytics runs on a read replica (or a columnar mirror via CDC into ClickHouse/BigQuery) so giant aggregation scans don't evict the OLTP working set or hold the primary's I/O.

```
events (RANGE by ts)
  ├── ...older...    BRIN(ts), maybe compressed/columnar, read-only   ── analytics (replica)
  ├── events_2026_06_15  B-tree(device_id, ts DESC)
  └── events_2026_06_16  B-tree(device_id, ts DESC)   ◀ hot OLTP writes+reads (primary)
```

The trade-offs I'd call out: too-fine partitions (hourly over years = tens of thousands) bloat planning time, so I'd tier (hourly recent, rolled up to daily/monthly historically) or use a tool like `pg_partman`/TimescaleDB hypertables to automate it; and BRIN collapses if historical partitions are ever updated out of order, so they must stay append-only. The meta-point is that at 10B rows "indexing" becomes "data layout + partition lifecycle + workload routing," not a single `CREATE INDEX`.

#### Q52. [Theory] What are the consistency and visibility subtleties of an index-only scan under concurrent writes, across MVCC engines?

An index-only scan promises to answer a query from the index alone, skipping the heap — but **indexes in MVCC engines don't store row visibility**, so the engine cannot tell from the index whether a given entry corresponds to a tuple version that is visible to the current transaction. PostgreSQL solves this with the **visibility map**: a per-page bitmap marking pages where *all* tuples are visible to all transactions. During an index-only scan, for each index entry the engine checks the visibility map for that heap page — if the page is all-visible, it trusts the index entry and skips the heap; if not, it must fetch the heap tuple to check `xmin`/`xmax` visibility ("heap fetches" in the plan).

```
EXPLAIN ANALYZE ... ->
  Index Only Scan using idx ... 
     Heap Fetches: 482133     <-- visibility map stale: NOT truly "index-only"
```

The concurrency subtlety: a freshly written or recently updated region of the table has pages **not** marked all-visible (because there are still-running or recently-committed transactions whose effects must be checked), so an index-only scan over hot, frequently-written data degrades into many heap fetches — exactly when you least want it. **VACUUM** is what sets visibility-map bits, so an index-only scan's performance is coupled to autovacuum health: lag behind and your "index-only" scan quietly becomes an index+heap scan. Contrast with engines that store row data **in** the clustered index leaf (InnoDB): a secondary index there always needs the PK lookup into the clustered index for non-covered columns, but covered queries read from the secondary index leaf directly, and visibility is resolved via the clustered index and undo log rather than a separate visibility map.

The expert takeaways: (1) an index-only scan is a *performance* optimization, never a *correctness* shortcut — visibility is always honored, just at the cost of heap fetches when it can't be proven from the map; (2) `Heap Fetches` in the plan is the metric that tells you whether you're actually getting the index-only benefit; (3) covering indexes on write-hot tables underdeliver until vacuum catches up, so pairing them with aggressive autovacuum (or a lower `autovacuum_vacuum_scale_factor` on that table) is part of the design, not an afterthought.

#### Q53. [Practical] A query's plan suddenly regressed after a routine deploy with no query change. How do you root-cause a plan flip?

A plan flip with unchanged SQL is one of the more unnerving incidents because the "obvious" suspect (a code change) is ruled out. I treat it as a differential: *something* in the optimizer's inputs changed, and the inputs are statistics, configuration, data, and the cached plan. I work the differential systematically:

```
What changed between "good plan" and "bad plan"? Check each input:
  1. STATISTICS  -> did an ANALYZE/autoanalyze run near the deploy and shift estimates?
                    Compare pg_stats / histogram; re-check est-vs-actual rows.
  2. DATA SHAPE  -> a migration/backfill in the deploy changed cardinality or skew
                    (e.g. a new column defaulted, a bulk update, a new tenant onboarded).
  3. CONFIG      -> did the deploy change work_mem, random_page_cost, parallelism,
                    or a feature flag toggling a planner setting? diff SHOW ALL.
  4. PLAN CACHE  -> a connection-pool recycle re-planned with a different sniffed param.
  5. INDEX STATE -> did a migration add/drop an index, change a constraint, or leave
                    an INVALID index that the planner now avoids/prefers?
  6. VERSION     -> did the deploy bump the DB engine/extension version (planner changes)?
```

The fastest way to *see* the regression is to capture both plans side by side: pull the prior good plan from a plan-history store (Query Store on SQL Server, `pg_stat_statements` + captured `EXPLAIN` snapshots on PostgreSQL, AWR/SQL baselines on Oracle) and `EXPLAIN ANALYZE` the current bad one with the same parameters. The diff usually points straight at the cause — a join order change implies a cardinality estimate shift (stats or data), a switch from index scan to seq scan implies a selectivity/stats change or a dropped index, a sort spilling to disk implies a `work_mem` config change.

The **immediate mitigation** versus **root fix** distinction matters in an incident: to stop the bleeding I can force the prior plan (SQL Server `Query Store` force-plan, Oracle SQL Plan Baselines, PostgreSQL `pg_hint_plan` or temporarily disabling a plan type like `SET enable_seqscan = off` as a scoped hotfix). Then I fix the *root* — refresh/lock statistics, add the missing index, revert the config, or add extended statistics for the correlated columns the new data exposed. The prevention I push for afterward is **plan-regression detection in CI/observability** (Q30): capture and diff plans for top queries across deploys so a flip is caught at deploy time, not at 2 a.m.

#### Q54. [Theory] Compare how PostgreSQL, MySQL/InnoDB, and SQL Server physically organize tables and secondary indexes, and the performance implications.

These three engines make a foundational choice differently — whether the table *is* an index — and almost every other performance behavior follows from it.

| Aspect | PostgreSQL | MySQL / InnoDB | SQL Server |
|---|---|---|---|
| Table storage | **Heap** (unordered) | **Clustered on PK** (IOT) | **Heap** *or* **clustered index** (optional) |
| Row pointer in 2ndary index | physical `ctid` (tuple id) | the **primary key** value | RID (heap) or clustering key |
| Secondary lookup cost | index → heap by `ctid` (1 hop) | index → PK → clustered index (**2 B-tree descents**) | heap: index→RID (1 hop); clustered: index→key→clustered (2) |
| `CLUSTER` / physical order | one-time `CLUSTER`, **not** maintained | **always** maintained (PK order) | maintained by clustered index |
| MVCC dead rows | in-heap, reclaimed by `VACUUM` | undo log / rollback segments | version store (tempdb, RCSI) |

The implications. In **InnoDB**, the primary key is doubly important: it's the physical row order *and* the value every secondary index stores as its pointer, so a wide or random PK (a UUID v4) bloats every secondary index and scatters inserts across the clustered tree (page splits, poor cache locality) — which is why a monotonic surrogate `bigint` PK (or UUID v7) is the standard advice. A secondary-index lookup costs two B-tree traversals unless the index covers the query, making **covering indexes** especially valuable in InnoDB.

In **PostgreSQL**, the heap means all indexes are equal "second-class" pointers to `ctid`, so there's no privileged clustered index and no PK-in-every-index bloat — but it pays for MVCC with **heap bloat** and visibility-map dependence for index-only scans (Q52), and `CLUSTER` correlation decays after writes. In **SQL Server**, you *choose* heap vs clustered per table; a heap with a non-clustered index uses RIDs (one hop, but forwarded records under updates hurt), while a clustered table behaves like InnoDB. The expert framing: "why is my secondary index slow in MySQL but not Postgres?" often reduces to the **two-hop clustered-index lookup and PK width** in InnoDB, and "why does my Postgres index-only scan do heap fetches?" reduces to the **heap + visibility map**. Knowing which engine you're on changes the right PK choice, the value of covering indexes, and the vacuum/maintenance story.

#### Q55. [Practical] How would you tune the key PostgreSQL planner/memory configuration parameters for an OLTP workload on modern SSD/cloud hardware, and what's the risk of each?

The defaults ship conservative (tuned for 2005-era spinning disks and small RAM), so on modern hardware several are actively harmful and worth changing — but each has a failure mode if overshot. I tune from the most impactful:

```ini
# --- I/O cost model: tell the planner random access is cheap on SSD ---
random_page_cost = 1.1            # default 4.0 assumes HDD; on SSD random ≈ sequential
effective_cache_size = 24GB       # ~50-75% of RAM; informs how much is likely cached
                                  # (an estimate, NOT an allocation)

# --- memory per operation ---
work_mem = 32MB                   # per sort/hash NODE, per connection — multiplies!
shared_buffers = 8GB              # ~25% of RAM; PG's own page cache

# --- maintenance & autovacuum (keeps stats fresh + bloat down) ---
maintenance_work_mem = 1GB        # speeds CREATE INDEX / VACUUM
autovacuum_vacuum_scale_factor = 0.05   # vacuum sooner on large tables (default 0.2 too lazy)
default_statistics_target = 100   # raise to 500-1000 for skewed columns
```

The reasoning and risks, parameter by parameter:

- **`random_page_cost`** — lowering from 4.0 toward 1.1 is the single biggest planner win on SSD/cloud storage because it stops the optimizer from irrationally avoiding indexes (it thought random I/O was 4x sequential; on SSD it's near 1x). *Risk:* set too low and the planner over-favors index scans even where a seq scan would win — generally safe on real SSDs.
- **`effective_cache_size`** — it's an *estimate* of OS+DB cache, not an allocation; setting it realistically (50-75% of RAM) tells the planner index pages are probably cached, again favoring index scans. *Risk:* essentially none (no memory is reserved); too low just makes it pessimistic.
- **`work_mem`** — the dangerous one. It's allocated **per sort/hash operation, per connection**, so a query with 3 sorts on 200 connections can consume `3 × 200 × work_mem`. Too high invites **OOM**; too low spills sorts to disk (`external merge Disk` in plans). Tune it against `max_connections` and consider per-role/per-query overrides rather than a high global.
- **`shared_buffers`** — ~25% of RAM is the rule of thumb (more isn't always better because PG also leans on the OS cache and large values can hurt). *Risk:* too large starves the OS cache and other processes.
- **autovacuum aggressiveness** — the defaults let large tables accumulate dead tuples and stale stats (Q15, Q24), which causes the *other* performance problems in this whole document. Lowering `autovacuum_vacuum_scale_factor` and raising `autovacuum_max_workers`/cost limits keeps stats fresh and bloat bounded. *Risk:* too aggressive competes with foreground I/O.

The meta-point I'd make in an interview: don't tune blind — measure with `pg_stat_statements`, plan analysis, and `EXPLAIN (ANALYZE, BUFFERS)` before and after, change **one parameter at a time**, and remember that the highest-leverage "tuning" is usually fixing statistics and indexes, with config tuning a close partner rather than a substitute. On managed cloud Postgres (RDS/Aurora/Cloud SQL) many of these come pre-tuned to the instance size, so the focus shifts to `work_mem`, autovacuum per-table overrides, and `default_statistics_target` for skewed columns.

#### Q56. [Theory] What is write skew / right-hand-edge index contention on monotonically increasing keys, and how do you mitigate it at scale?

When inserts carry a **monotonically increasing key** (auto-increment `bigint`, `created_at = now()`, a sequence), every new row sorts to the **rightmost leaf** of the B-tree. Under high insert concurrency, all sessions pile onto that one page and its parent: they contend for the same buffer latch/lock, the same page splits repeatedly, and in distributed systems the same shard/range owns all current writes. This is **right-hand-edge contention** (and in sharded systems, a **write hotspot**) — throughput is capped by one page or one node even though the cluster is mostly idle. It's a single-table-design problem that masquerades as a general scaling wall.

```
B-tree under monotonic inserts:           all writers hammer ──┐
   [..][..][..][..][..][..][ HOT LEAF ]                        ▼
                              ^^^^^^^^  every insert lands here, latch contention,
                                        repeated page splits, cache line ping-pong
```

Mitigations differ by system, but the theme is **spreading writes across the key space**:

- **Single-node OLTP (PG/InnoDB):** the contention is usually tolerable, but if it bites, options include hash-partitioning the table so inserts spread across N partitions/leaves, or using a less-monotonic key. InnoDB's clustered-on-PK design makes this sharper (a random UUID PK *fixes* the hotspot but *creates* page-split-everywhere and cache-locality problems — hence **UUID v7 / ULID**, which are time-ordered-but-bucketed: ordered enough for locality, spread enough to ease the single-leaf hotspot only marginally — the real lever is partitioning).
- **Distributed SQL (CockroachDB, Spanner, Bigtable, DynamoDB):** this is *the* canonical anti-pattern. A monotonic key sends all writes to the last range/tablet/partition, hotspotting one node. Fixes: **hash-shard the index** (CockroachDB `USING HASH`), **salt/prefix the key** with a hash bucket so writes scatter, or use a random/UUID-style key — explicitly trading scan locality for write distribution.

```sql
-- CockroachDB: hash-sharded index spreads monotonic inserts across ranges
CREATE INDEX idx_events_ts ON events (ts) USING HASH WITH (bucket_count = 8);
```

The expert trade-off to articulate: monotonic keys give **great range-scan locality and cache behavior** but **terrible write distribution**; random/hashed keys give **great write distribution** but **poor scan locality and (in InnoDB) page-split/bloat costs**. The right answer is workload-dependent — single-node read-heavy tables often *want* the monotonic key, while high-ingest distributed tables must scatter writes. UUID v7/ULID and hash-sharding are the modern attempts to get locality and distribution at once, but they are compromises, not free lunches.

#### Q57. [Practical] You're handed a slow query you've never seen, on a schema you don't know, in a production incident. Narrate your exact 10-minute triage.

Under incident pressure I run a tight, repeatable loop that gets from "unknown slow query" to "actionable hypothesis" fast, prioritizing reversible mitigation over the perfect fix:

```
MIN 0-2  CONTAIN: is this query the actual cause? Check APM/pg_stat_activity for the
         worst query by current/total time. Is it blocked (lock wait) or burning CPU/IO?
           SELECT pid, state, wait_event_type, wait_event, now()-query_start AS dur, query
           FROM pg_stat_activity WHERE state='active' ORDER BY dur DESC;
MIN 2-5  EXPLAIN: EXPLAIN (ANALYZE, BUFFERS) with realistic params (on a replica if the
         query is huge). Read bottom-up: Seq Scan on big table? Sort spill to Disk?
         Nested Loop with huge loops? est-vs-actual rows way off?
MIN 5-7  CLASSIFY the smell:
           est<<actual rows  -> stale stats        -> ANALYZE <table>
           Seq Scan + selective predicate -> missing/unused index, or non-sargable
           Rows Removed by Filter huge -> predicate not in Index Cond
           lock wait_event -> blocking session     -> find & (maybe) cancel the blocker
           Heap Fetches huge -> vacuum lag on index-only scan
MIN 7-10 MITIGATE (reversible, scoped) then FIX:
           - kill a runaway/blocking session (pg_cancel_backend) if it's the blocker
           - run ANALYZE if stats are stale (cheap, safe)
           - if a plan flip: force prior plan / SET enable_seqscan=off in session as hotfix
           - file the durable fix (index via CONCURRENTLY, query rewrite) for after the incident
```

The judgment calls that matter in the moment: **don't ship a `CREATE INDEX` mid-incident** unless it's `CONCURRENTLY` and you've sized the impact — the safest in-incident levers are `ANALYZE` (cheap, often fixes a stale-stats plan flip), cancelling a single runaway/blocking session, and a *session-scoped* planner nudge. I explicitly separate **mitigation** (stop the bleeding now, reversibly) from the **durable fix** (the right index or rewrite, shipped through normal change control afterward), because conflating them is how a 2 a.m. fix becomes a second incident. And I keep asking "is this query even the cause?" — production slowness is frequently a lock wait, a connection-pool exhaustion (Q40), or a cache-cold replica, not the query's own plan, and `pg_stat_activity` wait events tell me which within the first two minutes.

#### Q58. [Theory] How do covering indexes, included columns, and the index's role as a "narrow table" change capacity planning and cache behavior?

A covering index is, physically, a **narrow, sorted copy of a subset of the table's columns** — and thinking of it that way reframes both performance and capacity. Because an index-only scan reads just the index, the relevant working set is the *index's* size, not the table's. A 500-byte-per-row table with a covering index on three 20-byte columns means hot queries touch a structure ~8x smaller, so far more of the answer fits in `shared_buffers`/the OS cache, cache hit ratio rises, and random heap I/O disappears. You are deliberately trading **disk and write cost** for a small, cache-resident read structure.

```sql
-- The index is a 3-column "narrow table" sorted by status, answering the query alone:
CREATE INDEX idx_orders_cover ON orders (status, created_at) INCLUDE (total, user_id);
-- Query reads ~ (4 cols) per row from a compact B-tree instead of the wide heap row.
```

The `INCLUDE` clause (PostgreSQL/SQL Server) matters precisely here: included columns are stored **only in the leaf level**, not the internal nodes, so they make the index *cover* the query without bloating the navigational part of the tree or affecting its sort key. Putting payload columns in `INCLUDE` rather than in the key keeps point lookups and the key's uniqueness semantics clean while still avoiding the heap fetch. (MySQL lacks `INCLUDE`, so you append payload columns to the key, which does enlarge the navigational structure — a real difference between engines.)

The capacity-planning implications an expert weighs: (1) **storage** — covering indexes duplicate column data, so a heavily-covered schema can have indexes totaling *more* than the table; budget disk and backup time accordingly. (2) **write cost** — every `INCLUDE`d column must be maintained on update, so covering a frequently-updated column reintroduces write amplification and (in PG) defeats HOT updates. (3) **cache economics** — the win is real only if the covered query is hot enough that keeping its narrow structure cache-resident pays back the storage/write cost. So covering indexes are a *targeted* tool for hot, read-dominant access paths, not a default; the framing "an index is a narrow, sorted, cache-friendly materialization of an access pattern" is what guides where they're worth it.

#### Q59. [Practical] Describe how you'd migrate a heavily-indexed OLTP table to a new schema (e.g. changing the primary key or partitioning it) with minimal downtime.

This is one of the highest-risk operations in database operations, because changing the PK or partition scheme touches the table's physical organization and every secondary index, on a table that's serving live traffic. The principle is **never block writes for long, never do it irreversibly, always have a verification + rollback gate** — which rules out a naive `ALTER TABLE` that rewrites the whole table under a lock. The standard pattern is an **online dual-write / backfill / cutover** migration:

```
1. CREATE the new table (new PK / partitioned) alongside the old, with its indexes.
2. DUAL-WRITE: app (or triggers/logical replication) writes every change to BOTH tables.
3. BACKFILL old rows into the new table in throttled batches (commit per chunk; watch
   replication lag, locks, and I/O — never one giant transaction).
4. VERIFY: row counts and checksums match; shadow-read the new table and compare results.
5. CUTOVER: flip reads to the new table (feature flag / view swap / rename in a txn).
6. SOAK then DROP the old table once confidence is high; keep it as instant rollback until then.
```

```sql
-- A common tactic: a VIEW or renamed table so the cutover is an atomic swap
BEGIN;
ALTER TABLE orders RENAME TO orders_old;
ALTER TABLE orders_new RENAME TO orders;   -- app sees "orders" continuously
COMMIT;   -- single fast metadata operation; rollback = swap back
```

The tooling and safeguards I'd lean on rather than hand-rolling: **pt-online-schema-change / gh-ost** (MySQL) and **pg_repack** or logical-replication-based tools build the new structure and copy data via triggers/replication while the table stays writable, then do a fast atomic rename. For partitioning an existing PostgreSQL table, the modern path is creating the partitioned parent and `ATTACH`ing the existing table as a partition (or backfilling into new partitions), avoiding a full rewrite. Throughout, I **throttle the backfill** to protect the OLTP workload (cache eviction, lock waits, replication lag — the same interference concerns as Q47), build the new indexes with `CONCURRENTLY`, and keep the old table intact as a one-command rollback until the new one has soaked through a full business cycle (including month-end batch jobs).

The two failure modes I'd explicitly guard against: a **giant single-transaction backfill** that bloats WAL/undo, holds locks, and can't be interrupted (always chunk + commit); and an **irreversible cutover** with no verified rollback (always keep the old table and make the swap atomic and reversible). The interview-grade summary: treat it as a controlled, reversible, dual-write migration with continuous verification — the schema change is the easy part, the *safe online transition* is the engineering.

#### Q60. [Behavioral] Tell me about a time you had to push back on a "rewrite it in NoSQL / it's a database scaling problem" conclusion when the real issue was query optimization.

I anchor this in a concrete incident and let the data carry the argument. A team was convinced their PostgreSQL instance had "hit its scaling ceiling" — p99 latency on the main dashboard endpoint had crept past two seconds, and the proposed fix was a multi-quarter migration to a NoSQL store, framed as "Postgres can't handle our scale." That conclusion was expensive and, I suspected, premature, so before debating architecture I asked for one thing: the actual evidence from `pg_stat_statements` and an `EXPLAIN ANALYZE` of the offending endpoint.

The data told a different story. The endpoint was issuing an ORM **N+1** (one query for the dashboard's parent rows, then a query per row for each widget's stats — Q17), and the single "parent" query was doing a **sequential scan** because a predicate had been wrapped in a function and was non-sargable (Q33). There was no scaling ceiling; there was a missing functional index and a fetch-join that wasn't being used. The database CPU was largely idle — the latency was almost entirely round-trip and full-scan time, not throughput saturation. I reproduced the fix in staging under replayed production load: a functional index plus an entity-graph fetch took the endpoint from ~2s to ~40ms, on the *same* hardware.

The way I handled the pushback mattered as much as being right. I didn't frame it as "you're wrong about NoSQL"; I framed it as "let's confirm the bottleneck before we commit a quarter of roadmap to it," and proposed a cheap, time-boxed experiment with a clear measurable outcome. That made it a shared investigation rather than a turf fight, and the evidence (idle CPU, the N+1 trace, the before/after numbers) made the conclusion uncontroversial. The lessons I draw out in an interview: (1) "we need a different database" is a *conclusion* that demands evidence — measure where the time actually goes before re-platforming; (2) most "scaling" pain on a healthy OLTP database is query/index/ORM inefficiency, which is orders of magnitude cheaper to fix than a datastore migration; and (3) the most effective way to disagree with a senior decision is a reversible, low-cost experiment that lets the data settle it, not an argument from authority. We kept Postgres, instituted the `pg_stat_statements` + plan-regression observability from Q30, and the "scaling crisis" never recurred.

## 🧩 Extended Questions — Supplemental Set: Mixed Depth

### 🟢 Basic — extended

#### Q61. [Theory] How are `NULL` values handled in indexes, and why can `WHERE col IS NULL` or `col <> ?` behave unexpectedly?

`NULL` means "unknown," and that semantics ripples into indexing. By default a PostgreSQL B-tree **does** store `NULL` entries (sorted to one end), so `WHERE col IS NULL` *can* use a normal index. But a `UNIQUE` index treats two `NULL`s as distinct (because unknown ≠ unknown), so a unique column can hold many `NULL` rows — surprising people who expect uniqueness to block them. MySQL/InnoDB also indexes `NULL`s and shares this unique-allows-many-NULLs behavior. SQL Server is the odd one out: a `UNIQUE` constraint allows only **one** `NULL`.

The trap is that `IS NULL` is sargable but inequality and `NOT IN` often are not. A predicate like `WHERE status <> 'DONE'` matches a large, scattered fraction of rows, so the planner usually picks a sequential scan — an index rarely helps a "not equal" because it is low-selectivity by nature. Worse, `NOT IN (subquery)` becomes a correctness/perf landmine if the subquery can return a `NULL`: the whole predicate evaluates to `UNKNOWN` and returns zero rows, and the planner cannot use an anti-join optimization.

```sql
-- Many NULLs allowed under a UNIQUE index (PG/MySQL): NULL != NULL
CREATE UNIQUE INDEX uq_ext_ref ON accounts (external_ref);  -- 1000s of NULL external_ref OK

-- Index just the rows you actually query, excluding the noisy NULLs:
CREATE INDEX idx_accounts_ref_notnull ON accounts (external_ref) WHERE external_ref IS NOT NULL;

-- Avoid NOT IN with nullable subqueries; use NOT EXISTS (NULL-safe, anti-join friendly):
SELECT * FROM a WHERE NOT EXISTS (SELECT 1 FROM b WHERE b.a_id = a.id);
```

The interview-level point: know that `NULL` indexing is engine-specific, that "many NULLs under UNIQUE" is intended, that a **partial index `WHERE col IS NOT NULL`** is the clean way to skip a column dominated by nulls, and that `NOT EXISTS` is the safe, optimizer-friendly replacement for `NOT IN` on nullable data.

#### Q62. [Practical] What is the difference between a primary key, a unique index, and a foreign key from a performance standpoint?

These three constraints are often conflated, but each has a distinct performance footprint. A **primary key** is a unique, non-null identifier; in clustered-storage engines (InnoDB, SQL Server clustered tables) it also dictates the *physical row order* and is embedded in every secondary index as the row pointer — so a wide or random PK (e.g. a UUIDv4) silently bloats every other index and scatters inserts. A **unique index** enforces no-duplicates and, like any index, must be checked on every insert/update of the key — that check is itself an index lookup, so a unique constraint is never "free" on writes.

A **foreign key** is the one people forget to index. The FK *constraint* enforces referential integrity, but it does **not** automatically create an index on the child column in PostgreSQL or MySQL. This bites in two ways: (1) joins from child to parent on an unindexed FK do full scans, and (2) deleting or updating a parent row forces the engine to scan the child table to check for orphans — and in some engines takes a lock on the child — so an unindexed FK can cause shocking lock contention and slow deletes.

```sql
-- The FK constraint does NOT create this index for you — add it explicitly:
ALTER TABLE order_items ADD CONSTRAINT fk_oi_order
    FOREIGN KEY (order_id) REFERENCES orders(id);
CREATE INDEX idx_order_items_order_id ON order_items (order_id);  -- needed for joins + parent deletes
```

The rule of thumb: a PK gives you a unique index automatically and (in clustered engines) sets physical order, a unique index is an enforced standing write cost, and **a foreign key almost always needs a hand-created index on the referencing column** — its absence is one of the most common performance/locking surprises in real schemas.

#### Q63. [Theory] What is a bitmap index scan / bitmap heap scan, and when does PostgreSQL choose it over a plain index scan?

A plain index scan walks the index and, for each match, immediately jumps to the heap to fetch the row — fine when few rows match and they're scattered, but if it must fetch *many* rows it does lots of **random** heap I/O, sometimes re-reading the same page repeatedly. A **bitmap scan** is a two-phase compromise: the **Bitmap Index Scan** walks the index and builds an in-memory bitmap of which *heap pages* (or rows) contain matches, then the **Bitmap Heap Scan** reads those pages in **physical (sequential-ish) order**, visiting each page once. This converts scattered random I/O into mostly sequential I/O.

```
Bitmap Heap Scan on orders   (recheck condition)
  ->  BitmapOr
        ->  Bitmap Index Scan on idx_status   (status = 'PAID')
        ->  Bitmap Index Scan on idx_region   (region = 'EU')
```

The killer feature is that bitmaps from **multiple indexes can be combined** with `BitmapAnd`/`BitmapOr` before touching the heap — so a query like `WHERE status = 'PAID' OR region = 'EU'` can use *two separate single-column indexes*, OR their bitmaps, and fetch each qualifying page once. This is how PostgreSQL serves an `OR` across columns without a single composite index covering both. The planner picks a bitmap scan in the middle ground: more rows than a point lookup (so plain index scan's random fetches get expensive) but fewer than would justify a full seq scan. The `Recheck Cond` line appears because a "lossy" bitmap may track pages rather than exact rows when it grows large, so it rechecks the predicate on the fetched rows. The takeaway for interviews: bitmap scans are why you sometimes *don't* need a composite index for `OR`/multi-predicate queries — Postgres can AND/OR several indexes together at the page level.

### 🟡 Intermediate — extended

#### Q64. [Theory] How do `IN (...)`, `OR`, and `EXISTS` differ in how the optimizer can index and execute them?

These three look interchangeable but optimize very differently. An `IN (list)` of literals is the friendliest: the engine treats `col IN (1,2,3)` as `col=1 OR col=2 OR col=3` over an indexed column and typically does an efficient index range/seek per value (or a single skip through the index). `OR` is the troublemaker: `WHERE a = ? OR b = ?` spanning **different columns** often can't use a single index as a clean seek — the engine either does a seq scan, or (in PostgreSQL) a **bitmap OR** of two indexes (Q63), or you rewrite it as a `UNION` of two index-friendly queries.

```sql
-- OR across columns: may seq-scan. Rewrite as UNION so each branch uses its own index:
SELECT * FROM users WHERE email = ? 
UNION
SELECT * FROM users WHERE phone = ?;     -- each arm hits a dedicated index
```

`EXISTS` and `IN (subquery)` are about **semi-joins**. `WHERE x IN (SELECT ...)` and `WHERE EXISTS (SELECT 1 ... WHERE correlated)` are usually optimized to the same semi-join plan by modern planners, *except* for `NULL` semantics: `IN` with a `NULL` in the subquery can drop rows unexpectedly, while `EXISTS` is `NULL`-safe. `EXISTS` also short-circuits — it stops at the first matching row — which is ideal for "does at least one exist" checks. Conversely `NOT IN` with a nullable subquery is a correctness trap (Q61) and blocks anti-join optimization, so `NOT EXISTS` is preferred there.

The practical guidance: prefer `IN (literals)` for fixed lists, rewrite cross-column `OR` as `UNION` (or rely on bitmap OR), use `EXISTS`/`NOT EXISTS` for correlated existence checks because they're `NULL`-safe and short-circuit, and remember that on most engines `IN (subquery)` and `EXISTS` produce equivalent semi-join plans so readability/NULL-safety should drive the choice.

#### Q65. [Coding] Write a batch "upsert" that stays index-efficient at scale, and explain the indexing and locking implications.

**Problem:** Insert-or-update thousands of rows per call (e.g. a metrics sink) without row-by-row round trips and without index/lock pathologies.

```java
import java.sql.*;
import java.util.List;

public class MetricUpserter {
    private final DataSource ds;
    public MetricUpserter(DataSource ds) { this.ds = ds; }

    /** Single multi-row INSERT ... ON CONFLICT, batched. Requires a UNIQUE index on (device_id, bucket). */
    public void upsertBatch(List<Metric> metrics, int batchSize) {
        final String sql =
            "INSERT INTO metrics (device_id, bucket, count) VALUES (?, ?, ?) " +
            "ON CONFLICT (device_id, bucket) DO UPDATE SET count = metrics.count + EXCLUDED.count";
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            c.setAutoCommit(false);
            int n = 0;
            for (Metric m : metrics) {
                ps.setLong(1, m.deviceId());
                ps.setObject(2, m.bucket());        // e.g. an Instant truncated to the hour
                ps.setLong(3, m.count());
                ps.addBatch();                       // accumulate, don't round-trip per row
                if (++n % batchSize == 0) { ps.executeBatch(); c.commit(); }
            }
            ps.executeBatch();
            c.commit();
        } catch (SQLException e) {
            throw new RuntimeException("upsert batch failed", e);
        }
    }
}
```

The `ON CONFLICT (device_id, bucket)` clause **requires a unique index** on exactly those columns — that index is both the conflict-detection mechanism and the access path, so without it the statement errors. Batching with `addBatch`/`executeBatch` collapses N network round trips into one (the same round-trip economics as the N+1 discussion), and committing per chunk avoids one giant transaction that bloats WAL/undo and holds locks too long.

- **Time:** O(N) inserts but with O(N/round-trips) network cost; each conflict check is an O(log N) index probe.
- **Space:** O(batchSize) buffered.
- **Edge cases:** deadlocks if concurrent batches touch overlapping keys in different orders (sort the batch by key to impose a consistent lock order), the unique index being mandatory for `ON CONFLICT`, and lock contention on hot keys (a "hot bucket" everyone increments) — which may push you toward sharded counters or a write-optimized store. Note MySQL's equivalent is `INSERT ... ON DUPLICATE KEY UPDATE` and also relies on a unique key.

#### Q66. [Practical] A `GROUP BY` aggregation query is slow. What are your options to speed it up?

I diagnose *how* the engine is grouping, because the two algorithms have very different costs. A **HashAggregate** builds an in-memory hash table keyed by the group columns — fast, but if the distinct-group count exceeds `work_mem` it spills to disk (or, pre-PG13, blew past the limit). A **GroupAggregate** requires the input already **sorted** by the group key and streams it, which is cheap *if* an index already provides that order but expensive if it has to sort first. So the first question is: is there a `Sort` node feeding the aggregate, and is it spilling to disk?

```sql
-- 1. Index that provides group order -> GroupAggregate with no sort step:
CREATE INDEX idx_orders_user ON orders (user_id);   -- GROUP BY user_id streams in order

-- 2. Index-only aggregation (covering): COUNT/SUM served from the index alone
CREATE INDEX idx_orders_user_total ON orders (user_id) INCLUDE (total);

-- 3. Pre-aggregate with a materialized view for expensive, repeated rollups (Q18):
CREATE MATERIALIZED VIEW mv_user_totals AS
  SELECT user_id, COUNT(*) c, SUM(total) s FROM orders GROUP BY user_id;
```

The escalating options: (1) give the aggregate a useful order via an index so it streams instead of sorts; (2) make it a **covering** index so the aggregate reads only the index (index-only aggregation); (3) raise `work_mem` if a HashAggregate is spilling to disk (watch the per-connection multiplication, Q55); (4) reduce input rows first — push `WHERE` filters before the `GROUP BY` and ensure they're sargable so fewer rows reach the aggregate; (5) for expensive, frequently-repeated rollups, **pre-aggregate** into a materialized view or a denormalized summary table refreshed on a schedule. For very high cardinality grouping, **partial/parallel aggregation** (PostgreSQL parallel query) splits the work across workers. The principle is the same as everywhere else: either feed the operator fewer rows, or give it an index that lets it avoid the expensive sort/hash, or precompute the answer.

#### Q67. [Theory] What is the leftmost-prefix rule's interaction with "skip scan," and does column order still matter on engines that have it?

The classic rule (Q7) says an index on `(a, b)` can't serve a query filtering only on `b`. **Index skip scan** (a.k.a. loose index scan) relaxes this *partially*: when the leading column `a` has **low cardinality**, the engine can "skip" through each distinct value of `a` and do a sub-search on `b` within each — effectively `for each distinct a: seek (a, b=?)`. Oracle has had skip scan for years; MySQL 8.0 added it for some cases; PostgreSQL historically lacked a general skip scan (you emulated it with a recursive "loose index scan" CTE), though newer versions and extensions narrow the gap.

```sql
-- Index (region, customer_id). Query filters only customer_id.
-- Skip scan viable IF region has few distinct values (e.g. 5 regions):
--   for each of 5 regions:  seek (region=R, customer_id=?)
-- = 5 small index seeks, far cheaper than a full scan — but only because region cardinality is tiny.
```

So column order **still matters**, and skip scan is not a license to stop thinking about it. Skip scan only pays off when the skipped leading column(s) have *low* cardinality — if `a` has a million distinct values, "skip through each" is a million seeks, worse than a scan, and the planner won't choose it. It's a fallback that makes a sub-optimal index *usable*, not *optimal*. The right design is still to lead the index with the columns your queries actually filter on (equality first), and treat skip scan as a safety net for the occasional query that filters on a trailing column — not as a substitute for a correctly-ordered index or a second index. In an interview, the nuanced answer is: "skip scan reduces the penalty for a missing leading-column predicate when that column is low-cardinality, but optimal column order by the leftmost-prefix and equality-first rules is still the design goal."

#### Q68. [Practical] When does wrapping a query in a CTE (`WITH`) help or hurt performance, and what is an "optimization fence"?

A CTE (`WITH cte AS (...)`) reads as a tidy named subquery, but historically in PostgreSQL it was an **optimization fence**: the CTE was *always materialized* into a temporary result first, and the outer query could not push predicates down into it or merge it. That meant `WITH big AS (SELECT * FROM huge) SELECT * FROM big WHERE id = 5` materialized the *entire* `huge` table, then filtered — catastrophically slower than an inline subquery where the `id = 5` predicate pushes down into an index seek.

```sql
-- PG <12: this CTE is a fence — 'huge' is fully materialized, then filtered (slow)
WITH big AS (SELECT * FROM huge)
SELECT * FROM big WHERE id = 5;

-- PG 12+: CTEs are inlined by default; force the old behavior explicitly when you WANT a fence:
WITH big AS MATERIALIZED   (SELECT ...)  -- force materialization (reuse, break a bad plan)
WITH big AS NOT MATERIALIZED(SELECT ...)  -- force inlining (push predicates in)
```

PostgreSQL 12 changed the default so CTEs are **inlined** (predicates push down) unless the CTE is recursive, used more than once, or marked `MATERIALIZED`. So the modern answer is version-dependent: on PG 12+ a CTE is usually as fast as a subquery, but on older versions (and other engines with fence semantics) a CTE can force a full materialization. Materialization isn't always bad — it *helps* when an expensive subquery is referenced multiple times (compute once, reuse) or when you deliberately want to break a pathological plan by forcing an intermediate result. The interview point: know that `WITH` historically meant "materialize and fence" in PostgreSQL, that PG 12+ inlines by default, and that `MATERIALIZED`/`NOT MATERIALIZED` give you explicit control — so a CTE's performance depends entirely on whether it's materialized and whether predicates can push through it.

### 🟠 Advanced — extended

#### Q69. [Theory] What actually causes a deadlock between two transactions, and how do indexing and access order relate to it?

A deadlock is a cycle in the wait-for graph: transaction T1 holds lock A and waits for lock B, while T2 holds lock B and waits for lock A — neither can proceed, so the engine's deadlock detector picks a **victim** and aborts it (PostgreSQL returns `deadlock detected`, MySQL `ER_LOCK_DEADLOCK`). The root cause is almost always **inconsistent lock ordering**: two code paths acquire the *same* set of row/key locks in *different* orders. Crucially, locks aren't only taken on the rows you explicitly `UPDATE` — they're taken on **index entries** the engine touches, and in some isolation levels on **gaps** between index entries (InnoDB next-key locks) to prevent phantoms. So index design directly shapes the locking surface.

```
T1: UPDATE accounts SET bal=bal-10 WHERE id=1;  -- locks row/key 1
T2: UPDATE accounts SET bal=bal-10 WHERE id=2;  -- locks row/key 2
T1: UPDATE accounts SET bal=bal+10 WHERE id=2;  -- waits for T2's lock on 2
T2: UPDATE accounts SET bal=bal+10 WHERE id=1;  -- waits for T1's lock on 1  => DEADLOCK
```

The standard prevention is to **acquire locks in a consistent global order** — e.g. always update accounts in ascending `id` order, or sort a batch by key before applying it (Q65). Beyond ordering: keep transactions short (less time holding locks), touch fewer rows (a precise index seek locks fewer index entries than a range scan that gap-locks a swath), and in InnoDB be aware that a query without a good index escalates to locking *many* rows (it locks every row it scans, not just those it matches) — so **a missing index can directly cause deadlocks and lock contention**, not just slowness. The expert connection between this topic and indexing: a well-chosen index narrows the set of index entries and gaps a statement locks, shrinking the contention surface; a full scan under a write transaction can lock the whole table's worth of index entries and turn a benign workload into a deadlock factory.

#### Q70. [Coding] Implement and benchmark a "find rows missing in the other table" query three ways, and reason about which the planner prefers.

**Problem:** Find `customers` with no `orders`. The three canonical formulations (`NOT IN`, `NOT EXISTS`, `LEFT JOIN ... IS NULL`) can produce different plans and different *results* on nullable data.

```sql
-- 1. NOT IN — DANGER: if orders.customer_id can be NULL, returns ZERO rows (NULL semantics, Q61)
SELECT c.* FROM customers c
WHERE c.id NOT IN (SELECT o.customer_id FROM orders o);

-- 2. NOT EXISTS — NULL-safe, optimized to an anti-join; the recommended form
SELECT c.* FROM customers c
WHERE NOT EXISTS (SELECT 1 FROM orders o WHERE o.customer_id = c.id);

-- 3. LEFT JOIN / IS NULL — also an anti-join; equivalent plan on modern planners
SELECT c.* FROM customers c
LEFT JOIN orders o ON o.customer_id = c.id
WHERE o.customer_id IS NULL;
```

```java
// Benchmark harness: time each variant and capture the plan to compare
public void compareAntiJoins(Connection c) throws SQLException {
    String[] queries = { NOT_IN_SQL, NOT_EXISTS_SQL, LEFT_JOIN_SQL };
    for (String q : queries) {
        long t0 = System.nanoTime();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(q)) {
            int rows = 0; while (rs.next()) rows++;
            System.out.printf("%dms, %d rows%n", (System.nanoTime()-t0)/1_000_000, rows);
        }
        // Capture plan: prepend "EXPLAIN (ANALYZE, BUFFERS) " and read text
    }
}
```

On a modern PostgreSQL planner, **`NOT EXISTS` and `LEFT JOIN ... IS NULL` both compile to an Anti Join** (a Hash Anti Join or Merge Anti Join), and they should perform identically — the optimizer recognizes the equivalence. The supporting index is `orders(customer_id)`: it lets the anti-join probe efficiently and lets a Merge Anti Join avoid a sort. **`NOT IN` is the outlier** — it cannot be turned into a clean anti-join when the subquery column is nullable (because of the `UNKNOWN` semantics), so the planner uses a slower materialized/hashed-subplan form *and* may return wrong results. 

- **Time:** anti-join is O(n+m) with a hash, or O(n log n) with sorted/indexed inputs; `NOT IN` with nullable columns degrades and can't anti-join.
- **Edge cases:** nullable `customer_id` makes `NOT IN` return zero rows (the headline bug), the `LEFT JOIN` form must test a **non-nullable** joined column (test the join key or PK, not an arbitrary nullable column, or you reintroduce wrong results), and an index on the FK column is what keeps all three from degrading to full scans.

#### Q71. [Practical] How do you tune `fillfactor` and design for HOT updates, and what is the measurable payoff?

`fillfactor` tells the engine how full to pack each page on insert/build — e.g. `fillfactor = 90` leaves 10% of each page free. The reason this matters is PostgreSQL's **HOT (Heap-Only Tuple) update** optimization: when you `UPDATE` a row *without changing any indexed column* and there's **free space on the same page**, the new tuple version lives on that page and the existing index entries keep pointing at it via a chain — meaning **no index entries are created or modified**. That avoids index write amplification (Q13), avoids index bloat, and lets cheap micro-vacuuming reclaim dead versions. If the page is 100% full, the update must place the new version on a *different* page, breaking HOT and forcing every index to be updated.

```sql
-- Lower fillfactor on an update-heavy table so updates stay HOT (in-page):
ALTER TABLE sessions SET (fillfactor = 80);
-- new pages keep 20% free for in-place new tuple versions
VACUUM FULL sessions;   -- or pg_repack; needed to apply fillfactor to existing pages

-- Verify HOT is happening: n_tup_hot_upd should track n_tup_upd
SELECT relname, n_tup_upd, n_tup_hot_upd,
       round(100.0 * n_tup_hot_upd / NULLIF(n_tup_upd,0), 1) AS hot_pct
FROM pg_stat_user_tables WHERE relname = 'sessions';
```

The measurable payoff is large for update-heavy tables (session stores, counters, status flags): a high `hot_pct` (close to 100%) means updates aren't touching indexes at all, so write throughput rises and index bloat falls. The trade-off is space — lower fillfactor means rows occupy more pages, slightly increasing storage and scan I/O, so it's only worth it where updates are frequent. The other half of the design is **not indexing the columns that change most**: if you index a frequently-updated column, every update of it breaks HOT regardless of fillfactor, so the levers are (1) leave free space via fillfactor, and (2) avoid indexing hot-update columns. This is the concrete, tunable mechanism behind the MVCC/HOT discussion in Q24.

#### Q72. [Theory] How does query parallelism work in PostgreSQL, and when does it help versus hurt an indexed query?

PostgreSQL can split a query across multiple **parallel workers**, each scanning a disjoint slice of a table and feeding partial results to a `Gather` node that combines them. Parallel-aware operators include Parallel Seq Scan, Parallel Index Scan, Parallel Bitmap Heap Scan, and partial aggregation (each worker pre-aggregates, the leader finalizes). The planner enables it when the estimated work exceeds thresholds (`min_parallel_table_scan_size`, `parallel_setup_cost`, `parallel_tuple_cost`) and the table is large enough that splitting the scan amortizes the worker startup cost.

```sql
-- See/control parallelism
SET max_parallel_workers_per_gather = 4;
EXPLAIN ANALYZE SELECT region, SUM(total) FROM orders GROUP BY region;
-- Plan shows:  Gather -> Partial HashAggregate -> Parallel Seq Scan on orders
```

Parallelism is a big win for **large analytical scans and aggregations** — a 100M-row `SUM`/`GROUP BY` over a table that can't be served by a tiny index benefits from N cores reading in parallel. But it can *hurt* or simply not apply for the bread-and-butter OLTP case: a **selective indexed point lookup** returns few rows fast and gains nothing from parallel setup overhead, so the planner (correctly) runs it serially. Parallelism also competes for cores and `work_mem` (each worker gets its own `work_mem` allocation, multiplying memory pressure), so on a busy OLTP box with many concurrent connections, aggressive parallelism can cause CPU and memory contention that *slows* the overall system even as individual analytic queries speed up. Certain constructs disable it (writes via the parallel leader, some functions marked `PARALLEL UNSAFE`, cursors). The expert framing: parallelism is an OLAP-scan accelerator, not an OLTP fix — for a query that *should* be an index seek, the right move is the index, not more workers; and on mixed workloads you tune `max_parallel_workers_per_gather` down (or isolate analytics to a replica, Q47) so parallel scans don't trample the OLTP working set.

#### Q73. [Practical] How do you read and use the `cost=` numbers in an `EXPLAIN` plan? What do the two numbers and the units mean?

Every node shows `(cost=startup..total rows=R width=W)`, and misreading these is common. The cost is in **abstract units** anchored to `seq_page_cost = 1.0` — it is *not* milliseconds; it's an estimate of relative work (page reads weighted by `seq_page_cost`/`random_page_cost` plus CPU costs like `cpu_tuple_cost`). The two numbers are the **startup cost** (work before the first row can be emitted) and the **total cost** (work to return all rows). The gap between them is meaningful: a `Sort` or `Hash` has a high startup cost because it must consume its entire input before producing a single output row, whereas an `Index Scan` has near-zero startup because it streams rows immediately — which is exactly why `LIMIT` queries favor low-startup plans.

```
Sort  (cost=12000.50..12010.50 rows=4000 width=64)   <- high startup: must sort everything first
  ->  Seq Scan on orders (cost=0.00..8000.00 rows=4000 width=64)
Index Scan using idx (cost=0.43..120.5 rows=10 width=64) <- near-zero startup: streams immediately
```

How I *use* the numbers: (1) the **total cost** is what the planner compares across candidate plans — the chosen plan is the lowest-total-cost one, so comparing the seq-scan branch's cost to the index-scan branch's reveals *why* the planner chose what it did. (2) The **`rows` estimate** is the load-bearing input — if `EXPLAIN ANALYZE` shows actual rows wildly different from the estimated `rows`, the cost is built on a lie and the plan is suspect (stale stats / correlated columns, Q29). (3) **startup vs total** explains `LIMIT` behavior: under a small `LIMIT`, a low-startup index scan beats a low-total-but-high-startup sort, even if the sort's total looks cheaper for the full result. The crucial caveat for interviews: cost units are arbitrary and only comparable *within the same planner run on the same config* — you cannot compare a cost of 5000 on one server to 5000 on another, and you never read cost as a time. To know real time, run `EXPLAIN ANALYZE` and read the `actual time` numbers.

### 🔴 Expert — extended

#### Q74. [Theory] How does the isolation level (and MVCC snapshot model) affect query plans, locking, and the indexes you need?

Isolation level changes *what the query must guarantee about concurrent data*, which changes locking and sometimes plan shape. Under **MVCC snapshot isolation** (PostgreSQL's default Read Committed and Repeatable Atble, InnoDB's Repeatable Read), readers see a consistent snapshot and **don't block writers** — reads use snapshot visibility rather than shared locks, so a `SELECT` doesn't take row locks at all in the common case. This is why "readers don't block writers and writers don't block readers" holds, and it means index design is about access efficiency, not lock avoidance, for plain reads. But the moment you add `SELECT ... FOR UPDATE` / `FOR SHARE`, the reader *does* lock the matching index entries and rows — and the **index determines which entries get locked**.

```sql
-- Repeatable Read / Serializable can fail on write-skew or serialization conflicts:
-- T1 and T2 both read a snapshot, both decide to insert, both pass the check -> anomaly.
BEGIN ISOLATION LEVEL SERIALIZABLE;
SELECT count(*) FROM seats WHERE flight=42 AND booked=false;  -- both see 1 free seat
-- ... both INSERT a booking ...  one transaction is aborted with a serialization failure
COMMIT;  -- ERROR: could not serialize access due to read/write dependencies
```

The subtleties experts call out: (1) **InnoDB's Repeatable Read takes next-key (gap) locks** on the index range a statement scans to prevent phantoms, so a range `UPDATE`/`SELECT FOR UPDATE` over a *poorly-indexed* predicate gap-locks a wide swath of index entries — turning a missing index into a concurrency killer, not just a speed problem. A precise index shrinks the locked gap. (2) **PostgreSQL Serializable (SSI)** doesn't add traditional locks but tracks read/write dependencies and *aborts* transactions on dangerous cycles — so the application must retry on serialization failures, and reducing the read footprint (via selective indexes) reduces false-positive conflicts. (3) **Read Committed** re-reads a fresh snapshot per statement, so a long-running query in RC can see a moving target. The connection to this topic: isolation level decides whether and how much your indexes participate in *locking* (not just lookups), and a well-chosen index reduces both the rows scanned and the lock/conflict surface — so "add the right index" is sometimes the fix for a *concurrency* incident, not a latency one.

#### Q75. [Practical] How does `ANALYZE` actually build statistics (sampling, histograms, MCV lists), and how do you fix a column where the planner consistently misestimates?

`ANALYZE` does **not** read the whole table — it takes a **random sample** (default ~300 × `default_statistics_target` rows, so ~30,000 rows at the default target of 100) and from that sample estimates, per column: the fraction of nulls, the number of distinct values (`n_distinct`), the **most-common-values (MCV) list** with their frequencies, and a **histogram** of the remaining values' distribution into equi-depth buckets. The planner uses these to estimate selectivity: for a value in the MCV list it uses the stored frequency; for others it interpolates from the histogram. This sampling is why estimates can be wrong on skewed or large-cardinality columns — the sample may miss rare-but-clustered values or mis-estimate `n_distinct`.

```sql
-- 1. Increase resolution for a specific skewed column (more MCV slots + histogram buckets):
ALTER TABLE events ALTER COLUMN event_type SET STATISTICS 1000;  -- per-column target
ANALYZE events;

-- 2. Inspect what the planner actually believes:
SELECT attname, n_distinct, most_common_vals, most_common_freqs
FROM pg_stats WHERE tablename = 'events' AND attname = 'event_type';

-- 3. Correlated columns the planner assumes independent -> extended statistics (Q29):
CREATE STATISTICS s_evt (dependencies, ndistinct, mcv) ON event_type, region FROM events;
ANALYZE events;
```

The fix path when a column is consistently misestimated: first confirm via `EXPLAIN ANALYZE` that estimated rows diverge from actual; then (1) **raise the per-column statistics target** so the histogram and MCV list capture more detail of a skewed distribution; (2) if `n_distinct` is wrong (common on large tables because it's hard to estimate from a sample), **set it manually** with `ALTER TABLE ... ALTER COLUMN ... SET (n_distinct = -0.5)` (a negative value means "this fraction of rows are distinct," which scales with table growth); (3) if the misestimate comes from **correlated predicates**, add **extended statistics**; (4) ensure `autoanalyze` actually runs often enough on a fast-growing table (lower `autovacuum_analyze_scale_factor`). The expert insight reinforces Q29/Q41: the planner is a function of its statistics, sampling has inherent error on skew and cardinality, and the levers — per-column target, manual `n_distinct`, extended stats, analyze frequency — are how you feed it the truth so it picks the right plan.

#### Q76. [Theory] What is the difference between a generic plan and a custom plan for prepared statements, and how does PostgreSQL decide between them?

When you `PREPARE` a statement (directly, or via JDBC server-side prepared statements after `prepareThreshold` executions), PostgreSQL can build either a **custom plan** — re-planned each execution using the *actual* bound parameter values, so selectivity estimates are accurate for those values — or a **generic plan** — planned once with *placeholder* selectivity estimates (using average/total statistics), then reused for all future executions, skipping planning cost entirely. Custom plans are accurate but pay the planning cost every time; generic plans are cheap to execute but may be wrong for atypical parameters. This is the precise mechanism behind the parameter-sniffing discussion in Q14.

```sql
-- PG's heuristic: first 5 executions use custom plans; then if the generic plan's
-- estimated cost isn't much worse than the average custom cost, it switches to generic.
SET plan_cache_mode = 'auto';          -- the default heuristic
SET plan_cache_mode = 'force_custom_plan';   -- always re-plan with real params (skew-safe)
SET plan_cache_mode = 'force_generic_plan';  -- always reuse (saves planning, risks skew)
```

The decision algorithm: PostgreSQL runs the first **5 executions** as custom plans, recording their costs, then computes a generic plan and compares its estimated cost to the *average* of the custom plans. If the generic plan is not significantly more expensive, it locks in the generic plan to save planning overhead; otherwise it keeps re-planning custom. The failure mode is **data skew**: if most parameter values are cheap but some "whale" value matches millions of rows, the average custom cost looks cheap, PostgreSQL adopts a generic plan tuned for the average, and the whale query then runs on a plan that's disastrous for it (or the inverse — a generic plan built pessimistically penalizes the common cheap case). The expert fixes: set `plan_cache_mode = force_custom_plan` for queries known to have skewed parameters (you pay planning cost to guarantee a fit), use literals instead of parameters for the rare skewed values so they get their own plan, or restructure so the hot and skewed paths are different statements. The interview-grade summary ties to Q14 and Q45: generic-vs-custom is *the* PostgreSQL realization of parameter sniffing, the "first 5 then compare" heuristic is the decision rule, and `plan_cache_mode` is the lever.

#### Q77. [Practical] An OLTP table's autovacuum can't keep up and bloat/transaction-ID wraparound risk is rising. How do you diagnose and remediate?

This is an operational emergency that masquerades as gradual slowness, then becomes an outage. The mechanism: every `UPDATE`/`DELETE` leaves a **dead tuple** that `VACUUM` must reclaim, and every transaction consumes from a 32-bit **transaction ID (XID)** space that `VACUUM` must "freeze" old rows to recycle. If autovacuum falls behind a high-write table, dead tuples accumulate (bloat → slower scans, worse index-only scans per Q52), and unfrozen XIDs march toward **wraparound** — at which point PostgreSQL forces an emergency shutdown to prevent data corruption. So this is both a performance and an availability problem.

```sql
-- 1. Find the worst-bloated / least-vacuumed tables and wraparound risk:
SELECT relname, n_dead_tup, n_live_tup,
       round(100.0*n_dead_tup/NULLIF(n_live_tup+n_dead_tup,0),1) AS dead_pct,
       last_autovacuum
FROM pg_stat_user_tables ORDER BY n_dead_tup DESC LIMIT 20;

-- 2. Transaction-ID age — how close to wraparound (2.1B is the wall):
SELECT relname, age(relfrozenxid) AS xid_age
FROM pg_class JOIN pg_namespace n ON n.oid=relnamespace
WHERE relkind='r' ORDER BY xid_age DESC LIMIT 20;

-- 3. Make autovacuum more aggressive on the hot table specifically:
ALTER TABLE hot_events SET (
  autovacuum_vacuum_scale_factor = 0.02,     -- vacuum at 2% dead, not the 20% default
  autovacuum_vacuum_cost_limit = 2000,       -- let it do more work per round
  autovacuum_vacuum_cost_delay = 2           -- throttle less
);
```

The remediation, in order: (1) **confirm what's behind** — is it dead-tuple bloat (`n_dead_tup`/`dead_pct`) or XID age (`age(relfrozenxid)` approaching the warning at ~200M and the wall at ~2.1B)? (2) For acute wraparound risk, **run a manual `VACUUM (FREEZE)`** on the oldest tables immediately — don't wait for autovacuum. (3) **Tune autovacuum per-table** for the hot table (lower scale factor so it triggers sooner, raise the cost limit / lower the cost delay so each run does more work, raise `autovacuum_max_workers`) rather than globally. (4) Address the *cause*: a long-running transaction or abandoned replication slot or prepared transaction **holds back the XID horizon** so vacuum *can't* reclaim anything cluster-wide — find it via `pg_stat_activity` (oldest `xact_start`) and `pg_replication_slots`, and kill/fix it, or vacuum is fighting with one hand tied. (5) Reduce the dead-tuple generation rate via HOT updates (Q71) and fewer indexes on the hot table. The expert framing: autovacuum keeping up is a *prerequisite* for every other optimization in this document (fresh stats, index-only scans, bounded bloat), and "the database is mysteriously degrading and at wraparound risk" almost always traces to a long-lived transaction or stale replication slot pinning the XID horizon — find that first.

#### Q78. [Theory] How do columnar storage and vectorized execution change "indexing" for analytical queries, and why don't you just add B-trees?

In a row store, a B-tree index helps because the engine fetches whole rows and an index lets it fetch *fewer* of them. In a **columnar store** (ClickHouse, parquet-backed engines, DuckDB, Snowflake, PostgreSQL with `cstore`/Hydra/Citus columnar), data is stored **column by column**, which fundamentally changes the optimization model: an analytical query like `SELECT region, SUM(revenue) FROM sales WHERE year=2026 GROUP BY region` reads *only* the `year`, `region`, and `revenue` columns and skips the dozens of others entirely (**column/projection pruning**), so the dominant win comes from reading less *data*, not finding fewer *rows*. Columns also compress dramatically (run-length, dictionary, delta encoding) because adjacent values are homogeneous, and **vectorized execution** processes batches of column values in tight CPU-cache-friendly loops (often SIMD), instead of the row-at-a-time tuple iteration of an OLTP engine.

```
Row store + B-tree:           find the few matching rows, fetch full rows (random I/O)
Columnar + zone maps:         read only needed columns, skip blocks via min/max, scan
                              compressed batches with vectorized/SIMD ops (sequential I/O)
```

So instead of B-trees you "index" with **sort/clustering keys, partitioning, and zone maps (min/max per block)**: ClickHouse's `ORDER BY` clustering key and sparse primary index, Snowflake's micro-partitions with per-column min/max metadata, Parquet's row-group statistics. A query that filters on the clustering/sort key lets the engine **skip entire blocks** whose min/max can't match — block-level skipping, not row-level seeking. You don't add B-trees because (1) a B-tree's strength is locating *individual scattered rows*, but analytics scans *ranges and aggregates*, where reading a compressed column sequentially beats random index lookups; (2) maintaining row-level B-trees on a write-mostly-bulk-load columnar table would be enormous and pointless; (3) the cardinality of analytic predicates is usually low-selectivity (a quarter of the year, a region) where a scan of compressed columns wins anyway (the tipping point of Q45, pushed all the way toward scans). The expert generalization (echoing Q26): "indexing" means *organizing physical layout for the access pattern* — for OLTP point/range lookups that's a B-tree; for OLAP wide-scan aggregations it's columnar layout + sort keys + zone maps + vectorized execution, and forcing B-trees onto an analytical workload is solving the wrong problem.

#### Q79. [Coding] Implement a recursive CTE for a hierarchy traversal and explain how to keep it from exploding, plus the supporting index.

**Problem:** Traverse an adjacency-list tree (`employees(id, manager_id)`) to get an entire reporting subtree. A naive recursive walk can loop forever on a cycle and scan inefficiently without the right index.

```sql
CREATE INDEX idx_employees_manager ON employees (manager_id);   -- the recursion's access path

WITH RECURSIVE subtree AS (
    -- anchor: the root we start from
    SELECT id, manager_id, name, 1 AS depth, ARRAY[id] AS path
    FROM employees WHERE id = :root_id
  UNION ALL
    -- recursive term: join the working set to children via the indexed FK
    SELECT e.id, e.manager_id, e.name, s.depth + 1, s.path || e.id
    FROM employees e
    JOIN subtree s ON e.manager_id = s.id
    WHERE e.id <> ALL(s.path)          -- cycle guard: never revisit a node already on the path
      AND s.depth < 50                 -- depth cap: hard stop on runaway recursion
)
SELECT id, manager_id, name, depth FROM subtree;
```

```java
// JDBC: bind the root, stream results; the recursion runs entirely server-side (one round trip)
public List<Employee> subtree(Connection c, long rootId) throws SQLException {
    String sql = "WITH RECURSIVE subtree AS ( ... )";   // the SQL above, :root_id -> ?
    try (PreparedStatement ps = c.prepareStatement(sql)) {
        ps.setLong(1, rootId);
        try (ResultSet rs = ps.executeQuery()) {
            List<Employee> out = new ArrayList<>();
            while (rs.next()) out.add(map(rs));
            return out;
        }
    }
}
```

The recursion repeatedly does `JOIN employees e ON e.manager_id = s.id`, so an **index on `manager_id`** turns each recursion step into an index lookup of a node's children instead of a full table scan per level — without it the query is O(levels × tablesize). The two explosion guards are essential: the **cycle guard** (`e.id <> ALL(s.path)`, tracking the visited path in an array) prevents infinite loops if the data has a cycle (which adjacency lists can, through bad data), and the **depth cap** bounds pathological depth. 

- **Time:** with the index, O(nodes-in-subtree × log N); without it, O(levels × N) — a full scan per level.
- **Space:** O(nodes-in-subtree); the `path` array adds per-row overhead proportional to depth.
- **Edge cases:** cycles in the data (the path guard), unbounded depth (the cap), the anchor matching nothing (empty result), and very wide/deep trees where the materialized working set is large — for read-heavy hierarchy queries a **closure table** or `ltree`/materialized-path design often beats repeated recursive CTEs.

#### Q80. [Practical] You must decide between scaling reads with replicas, scaling writes with sharding, and fixing the query. Walk through the decision and the failure modes of each.

The instinct under load is to add hardware, but the discipline is to identify which of three distinct bottlenecks you actually have, because the wrong remedy is expensive and sometimes makes things worse. The three are: **query/index inefficiency** (fixed by indexing/rewriting, near-free, fixes the root cause), **read throughput saturation** (the primary is read-bound and queries are already efficient — fixed by **read replicas**), and **write throughput saturation** (a single primary can't absorb the write/ingest volume or the dataset exceeds one node — fixed by **sharding/partitioning across nodes**). I diagnose with metrics: is CPU/IO saturated, or idle with high latency (pointing at locks, connection waits, or inefficiency)? Is the load reads or writes? Is one query dominating `pg_stat_statements`?

```
Symptom                                  Likely bottleneck        Remedy (in order)
───────────────────────────────────     ──────────────────       ───────────────────────────
high latency, DB CPU mostly idle         query/lock/connection    fix index/rewrite, pool, ANALYZE
read CPU/IO saturated, queries efficient read throughput          read replicas + read routing
write IOPS/WAL saturated, single primary write throughput         shard / partition / write-optimized store
dataset > one node's disk/RAM            capacity                 shard or archive cold data
```

The failure modes are the crux. **Read replicas** add **replication lag** — a read-after-write on a replica can return stale data, so you need read-your-writes routing (send a user's reads to the primary briefly after their write) and you must accept eventual consistency; and critically, *a replica doesn't fix an inefficient query, it just buys hardware to run the bad query in parallel* (Q23). **Sharding** is the heaviest hammer: it imposes a shard key on every query (cross-shard queries and joins become scatter-gather, slow and complex), breaks global uniqueness and cross-shard transactions/foreign keys, creates **hotspots** if the shard key is monotonic or skewed (Q56), and makes resharding painful — so it's a one-way door you take only when a single node genuinely can't hold the writes or data. **Fixing the query** has essentially no downside and routinely recovers 10-100x, which is why it's always first. The staff-level answer: prove the bottleneck with metrics, exhaust query/index/connection fixes (cheap, root-cause), add replicas for read scaling (cheap-ish, mind lag and that it doesn't fix bad queries), and reach for sharding last (expensive, irreversible-ish, imposes a shard key on the whole system) — and never add infrastructure to mask an inefficiency you haven't measured.

#### Q81. [Theory] What are "invisible indexes" and hypothetical indexes, and how do they de-risk index changes in production?

Two features let you test index decisions without paying the full cost or risk. An **invisible index** (MySQL 8.0 `ALTER INDEX ... INVISIBLE`; Oracle `INVISIBLE`) is a real, fully-maintained index that the **optimizer ignores** — it exists and is kept up to date on writes, but no query plan will use it. The point is *reversible disabling*: before dropping an index you suspect is unused, mark it invisible and watch production for regressions; if nothing breaks over a full business cycle, drop it for real; if something slows down, flip it back visible **instantly** (a metadata change, no rebuild). This neutralizes the scariest part of dropping an index — discovering too late that some rare monthly report depended on it.

```sql
-- MySQL: "soft delete" an index to test impact before truly dropping it
ALTER TABLE orders ALTER INDEX idx_orders_legacy INVISIBLE;
-- monitor... if all good:  DROP INDEX idx_orders_legacy ON orders;
-- if a query regressed:    ALTER TABLE orders ALTER INDEX idx_orders_legacy VISIBLE;  -- instant
```

A **hypothetical index** goes the other way — testing an index you *don't have yet* without building it. PostgreSQL's `HypoPG` extension (and SQL Server's "missing index" DTA / `WITH RECOMPILE` advisors) lets you create a phantom index definition that the planner *believes exists* and will cost/use in `EXPLAIN`, without ever writing the index to disk:

```sql
CREATE EXTENSION hypopg;
SELECT hypopg_create_index('CREATE INDEX ON orders (status, created_at)');
EXPLAIN SELECT * FROM orders WHERE status='PAID' ORDER BY created_at;  -- planner uses the phantom
-- If the plan improves, build it for real with CREATE INDEX CONCURRENTLY.
```

The combined workflow de-risks the whole index lifecycle: use **HypoPG/hypothetical indexes** to confirm a *new* index would actually be chosen and would improve the plan *before* spending hours building it on a huge table (and before adding a standing write cost that turns out to be useless); and use **invisible indexes** to safely retire a *suspected-dead* index with an instant rollback instead of a risky `DROP`. The expert framing ties to the index-budget discipline (Q28): both features turn index changes from irreversible gambles into measured, reversible experiments — you validate the benefit before paying the build cost, and you validate the absence of harm before paying the drop risk.

#### Q82. [Practical] Describe a complete production playbook for diagnosing and fixing a sudden write-throughput collapse on a heavily-indexed table.

A write-throughput collapse (inserts/updates that were fast suddenly queue up) on a heavily-indexed table has a specific differential, and I work it in order of likelihood and check-cost. The candidates: **index write amplification crossing a threshold** (too many indexes, each insert now does many B-tree modifications), **lock/latch contention** (a hotspot leaf under monotonic keys, Q56, or lock waits from concurrent writers), **autovacuum falling behind** (bloat slowing every index update, and dead-tuple buildup, Q77), **a checkpoint/WAL storm** (writes stall waiting on fsync/checkpoint I/O), or **lost HOT updates** (an index was added on a hot-update column, so updates that were HOT now touch every index, Q71).

```sql
-- 1. Are writes blocked on locks? (wait events tell you contention vs raw work)
SELECT wait_event_type, wait_event, count(*)
FROM pg_stat_activity WHERE state='active' GROUP BY 1,2 ORDER BY 3 DESC;

-- 2. How many indexes is each write maintaining, and are any unused (pure tax)?
SELECT indexrelname, idx_scan, pg_size_pretty(pg_relation_size(indexrelid)) AS size
FROM pg_stat_user_indexes WHERE relname='hot_table' ORDER BY idx_scan ASC;

-- 3. Did HOT-update ratio collapse (someone indexed a churning column)?
SELECT n_tup_upd, n_tup_hot_upd FROM pg_stat_user_tables WHERE relname='hot_table';

-- 4. Is autovacuum behind / WAL/checkpoint pressure high?
SELECT n_dead_tup, last_autovacuum FROM pg_stat_user_tables WHERE relname='hot_table';
SHOW checkpoint_timeout; SHOW max_wal_size;
```

The playbook: (1) **Check wait events first** — if writers are blocked on `Lock`/`LWLock`, it's contention (find the blocker, check for a hotspot key or a long transaction holding locks); if they're burning I/O, it's raw work (amplification/vacuum/WAL). (2) **Audit the index set** — `idx_scan = 0` indexes are pure write tax; drop them (using invisible-index testing first, Q81). The single highest-yield fix on an over-indexed write table is removing dead indexes, which I've seen triple insert throughput (Q13, Q28). (3) **Check the HOT ratio** — a recent index on a frequently-updated column collapses `n_tup_hot_upd` and amplifies every update; remove that index or stop updating that column. (4) **Check autovacuum/WAL** — if vacuum is behind, bloat is slowing every index operation (tune per-table, Q77); if checkpoints are storming, raise `max_wal_size` and lengthen `checkpoint_timeout` to spread the I/O. (5) **For a monotonic-key hotspot**, consider hash partitioning to spread inserts (Q56). The strategic close: write throughput on an indexed table is governed by the index budget, vacuum health, and lock/hotspot behavior — so the durable fix is an **index-usage audit and budget** (drop unused indexes, avoid indexing hot-update columns, keep vacuum healthy), not adding hardware, and the diagnostic that separates the causes is the wait-event breakdown.

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
