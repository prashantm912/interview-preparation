# Oracle Database — Interview Preparation Guide

A staff-level interview guide to Oracle Database covering PL/SQL, the cost-based optimizer, indexing, partitioning, materialized views, diagnostics (AWR/ASH), high availability (RAC/Data Guard), and Oracle-specific SQL. Knowledge current through 2026 (Oracle 19c/21c/23ai).

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

### Q1. [Theory] What is the difference between a PL/SQL procedure, function, and package?

A **procedure** performs an action and returns nothing directly (it may return via `OUT`/`IN OUT` parameters); a **function** must return a single value via `RETURN` and can be used inside SQL `SELECT` lists (subject to purity rules). A **package** is a named container with a *specification* (public API) and a *body* (private implementation), which gives you encapsulation, overloading, session-level state in package variables, and a single point of dependency. Packages also reduce recompilation churn: changing the body does not invalidate objects that depend only on the spec. In production you almost always wrap logic in packages rather than standalone procedures, because they version better and let you group related routines.

```sql
CREATE OR REPLACE PACKAGE order_pkg AS
  FUNCTION total_for(p_cust_id NUMBER) RETURN NUMBER;
  PROCEDURE place_order(p_cust_id NUMBER, p_amount NUMBER);
END order_pkg;
/
```

### Q2. [Theory] Explain Oracle's MVCC and read consistency model.

Oracle implements **multi-version read consistency** using **undo (rollback) segments**. When a query starts, Oracle records the current System Change Number (SCN); if it later encounters a block modified after that SCN, it reconstructs the older version from undo, so readers never block writers and writers never block readers. This means a long-running `SELECT` always sees a consistent snapshot as of statement start (statement-level consistency by default). The classic failure mode is **ORA-01555 "snapshot too old"**, which happens when the undo needed to reconstruct an old block has already been overwritten — typically a long query running against a heavily-updated table with undersized undo retention.

### Q3. [Practical] How do you generate surrogate keys in Oracle? Compare sequences and IDENTITY columns.

A **sequence** is an independent object producing monotonically increasing numbers via `seq.NEXTVAL`. Sequences are not gap-free (caching, rollbacks, and RAC node ordering create gaps), so never use them where contiguity is a business requirement. Since Oracle 12c you can use an **IDENTITY column** (`GENERATED ALWAYS AS IDENTITY`) which is backed by an internal sequence and avoids a trigger. In RAC, a non-`ORDER`/`NOCACHE` sequence is far cheaper because each instance caches its own range; forcing `ORDER` serializes across the cluster and becomes a scalability bottleneck.

```sql
CREATE SEQUENCE order_seq START WITH 1 INCREMENT BY 1 CACHE 1000 NOORDER;
-- 12c+ identity column
CREATE TABLE orders (
  id   NUMBER GENERATED ALWAYS AS IDENTITY,
  amt  NUMBER
);
```

### Q4. [Coding] Write SQL to find the second-highest salary per department.

**Problem:** Return the department and its 2nd-highest distinct salary. Handle departments with fewer than two distinct salaries (they should be excluded or null-handled).

```sql
-- Approach 1: analytic DENSE_RANK (handles ties correctly)
SELECT department_id, salary
FROM (
  SELECT department_id,
         salary,
         DENSE_RANK() OVER (PARTITION BY department_id
                            ORDER BY salary DESC) AS rnk
  FROM employees
)
WHERE rnk = 2;
```

`DENSE_RANK` treats tied salaries as one rank, so two people earning the top salary still leave the second *distinct* salary at rank 2. Use `ROW_NUMBER` instead if you literally want the 2nd row regardless of ties.

- **Time:** O(n log n) for the sort behind the window function.
- **Space:** O(n) for the partitioned sort.
- **Edge cases:** departments with one distinct salary return no row; nulls in salary sort last by default (add `NULLS LAST` to be explicit).

---

## 🟡 Intermediate (3–7 yrs)

### Q5. [Theory] How does the Cost-Based Optimizer (CBO) decide a plan, and what feeds it?

The CBO enumerates candidate execution plans (join orders, join methods, access paths) and picks the one with the lowest estimated **cost**, a unit combining I/O, CPU, and (for parallel/RAC) network. Its estimates depend on **object statistics** gathered by `DBMS_STATS`: row counts, distinct values (NDV), histograms for skewed columns, and clustering factor for indexes. Bad statistics are the single most common cause of bad plans — stale stats make the optimizer think a 10-million-row table still has 1,000 rows. Modern Oracle (12c+) adds **adaptive plans** (the optimizer can switch join methods at runtime) and **automatic reoptimization** (it learns from cardinality misestimates and corrects the next execution). Histograms matter because without them the CBO assumes uniform distribution, which catastrophically misestimates predicates on skewed columns like `status = 'PENDING'`.

```
SQL text ──► Parser ──► Optimizer (CBO)
                          │  uses: object stats, histograms,
                          │        system stats, hints, bind values
                          ▼
                    Plan candidates ──► cost each ──► cheapest plan
                          │
                          ▼
                    Row Source Generator ──► Execution
```

### Q6. [Theory] Compare B-tree, bitmap, and function-based indexes. When does each win?

A **B-tree index** is the default: balanced tree, great for high-cardinality columns and range scans, and the right choice for OLTP. A **bitmap index** stores one bitmap per distinct value and is superb for **low-cardinality** columns in read-mostly data warehouses, because bitmaps can be combined with fast bitwise AND/OR to satisfy multi-predicate queries; however, a single DML on a bitmap-indexed column locks a whole bitmap segment, so bitmaps are *toxic in OLTP* with concurrent writes. A **function-based index** indexes the result of an expression (e.g. `UPPER(last_name)` or `TRUNC(order_date)`), letting predicates that wrap a column in a function still use an index instead of doing a full scan. Choosing wrongly — e.g. a bitmap on a frequently-updated OLTP column — produces severe locking contention.

### Q7. [Practical] A report query suddenly went from 2 seconds to 5 minutes. Walk through your diagnosis.

First I capture the actual plan, not the estimated one: `DBMS_XPLAN.DISPLAY_CURSOR(format => 'ALLSTATS LAST')` shows estimated vs. actual rows (`E-Rows` vs `A-Rows`) — a large divergence means a cardinality misestimate driving a bad join. I check whether statistics went stale or a histogram disappeared after a stats gather, and whether **bind peeking / adaptive cursor sharing** picked a plan tuned for an outlier bind value. I compare against AWR history (`DBA_HIST_SQLSTAT`) to find the SQL plan hash that *used* to run and confirm a plan flip. The production fix is usually: re-gather stats with `DBMS_STATS`, and if the good plan must be locked in, use a **SQL Plan Baseline** (`DBMS_SPM`) rather than scattering hints through application code. Hints are a last resort because they bypass the optimizer and rot as data changes.

### Q8. [Coding] Write a PL/SQL procedure that bulk-loads rows with error logging, avoiding row-by-row slowness.

**Problem:** Insert many rows efficiently; if some rows violate constraints, log them and continue rather than failing the whole batch.

```sql
CREATE OR REPLACE PROCEDURE load_orders(p_limit PLS_INTEGER DEFAULT 10000) IS
  CURSOR c IS SELECT * FROM staging_orders;
  TYPE t_tab IS TABLE OF staging_orders%ROWTYPE;
  l_rows t_tab;
  dml_errors EXCEPTION;
  PRAGMA EXCEPTION_INIT(dml_errors, -24381);  -- array DML error
BEGIN
  OPEN c;
  LOOP
    FETCH c BULK COLLECT INTO l_rows LIMIT p_limit;   -- bounded fetch
    EXIT WHEN l_rows.COUNT = 0;

    BEGIN
      FORALL i IN 1 .. l_rows.COUNT SAVE EXCEPTIONS
        INSERT INTO orders VALUES l_rows(i);
    EXCEPTION
      WHEN dml_errors THEN
        FOR j IN 1 .. SQL%BULK_EXCEPTIONS.COUNT LOOP
          INSERT INTO load_errors(idx, err_code)
          VALUES (SQL%BULK_EXCEPTIONS(j).ERROR_INDEX,
                  SQL%BULK_EXCEPTIONS(j).ERROR_CODE);
        END LOOP;
    END;
  END LOOP;
  CLOSE c;
  COMMIT;
END;
/
```

`BULK COLLECT ... LIMIT` bounds memory (never `BULK COLLECT` an unbounded table into PGA), and `FORALL ... SAVE EXCEPTIONS` does set-based DML with per-row error capture.

- **Time:** O(n), but with a single context switch per batch instead of per row — typically 10–50x faster than a row-by-row loop.
- **Space:** O(p_limit) collection memory in PGA.
- **Edge cases:** empty staging table (loop exits immediately); always set a sane `LIMIT` to cap PGA usage on huge tables.

### Q9. [Theory] What problems do analytic (window) functions solve that GROUP BY cannot?

Analytic functions compute aggregate-style results **without collapsing rows**, so each detail row can carry running totals, ranks, moving averages, or comparisons to neighbors. `ROW_NUMBER`, `RANK`, `DENSE_RANK` rank within partitions; `LAG`/`LEAD` access prior/next rows for period-over-period deltas; `SUM(...) OVER (PARTITION BY ... ORDER BY ... ROWS BETWEEN ...)` produces running/windowed aggregates. Before window functions you needed correlated subqueries or self-joins, which were both slower and harder to read. A classic use is deduplication: `ROW_NUMBER() OVER (PARTITION BY natural_key ORDER BY load_ts DESC)` keeps the latest record per key in one pass.

### Q10. [Practical] How and why would you partition a large table?

Partitioning splits one logical table into physical segments to gain **partition pruning** (the optimizer scans only relevant partitions), cheaper maintenance, and parallelism. **Range partitioning** on a date is the workhorse for time-series/fact tables — old months can be dropped with an instant `DROP PARTITION` instead of a slow `DELETE`. **List partitioning** suits discrete categories (region, country); **hash partitioning** spreads data evenly to avoid hot spots; **composite** (e.g. range-hash) combines both. From 12.2 onward **interval partitioning** auto-creates partitions as new data arrives, removing manual maintenance. The trade-off: partitioning on the wrong key (one not used in predicates) gains nothing and adds local-index overhead; and global indexes must be maintained on partition operations unless you use `UPDATE INDEXES`.

```sql
CREATE TABLE sales (
  sale_id NUMBER, sale_date DATE, amount NUMBER
)
PARTITION BY RANGE (sale_date)
INTERVAL (NUMTOYMINTERVAL(1,'MONTH'))  -- auto new monthly partitions
( PARTITION p0 VALUES LESS THAN (DATE '2025-01-01') );
```

### Q11. [Theory] What is a materialized view and when do you choose it over a regular view?

A regular view is just stored SQL re-executed on every reference; a **materialized view (MV)** physically stores the precomputed result, so expensive aggregations/joins are paid once at refresh time, not per query. MVs support **fast (incremental) refresh** via materialized view logs that track only changed rows, **complete refresh**, and **on-commit** or **on-demand** scheduling. The powerful feature is **query rewrite**: with `ENABLE QUERY REWRITE` and `QUERY_REWRITE_ENABLED=TRUE`, the optimizer can transparently redirect a query against base tables to use the MV — users get speedups without changing SQL. The trade-off is staleness and refresh cost: an on-commit MV slows down every transaction touching the base tables, so high-write systems typically use scheduled on-demand refresh instead.

---

## 🟠 Advanced (8–12 yrs)

### Q12. [Theory] Explain how AWR, ASH, and Statspack relate, and how you'd use them in an incident.

**AWR (Automatic Workload Repository)** persists periodic snapshots (default hourly) of system and SQL statistics into `SYSAUX`; an **AWR report** between two snapshots shows top wait events, top SQL by elapsed/CPU, and load profile — your starting point for "the database was slow between 2 and 3 pm." **ASH (Active Session History)** samples active sessions every second, so it answers "what was happening *right now* / during a 5-minute spike" with session, SQL ID, and wait-event detail at fine granularity — ideal for transient stalls AWR's hourly granularity smooths over. **Statspack** is the older, free predecessor for editions/licenses without the Diagnostics Pack (AWR/ASH require the licensed Diagnostics Pack). In an incident I pull the AWR for the window to find the dominant wait class (e.g. `User I/O`, `Concurrency`, `Cluster`), then drill into ASH to attribute it to a specific SQL ID, session, or blocking lock.

```
       ┌──────────────── AWR (hourly snapshots, persisted) ──────────────┐
       │  load profile · top SQL · top waits · time model                │
       └─────────────────────────────────────────────────────────────────┘
                    ▲ summarizes                       ▲ same data, sub-second
                    │                                   │
   ASH (1-sec samples of ACTIVE sessions) ── v$active_session_history
                    │
   Statspack (manual snapshots, no Diagnostics Pack license needed)
```

### Q13. [Theory] Contrast Oracle RAC and Data Guard. What does each protect against?

**RAC (Real Application Clusters)** is *scale-out and availability within one site*: multiple instances on different nodes mount the *same* shared database (shared storage + Cache Fusion over the interconnect). It protects against **instance/node failure** — if a node dies, surviving nodes keep serving — and adds horizontal CPU/connection capacity. It does **not** protect your single shared copy of the data from corruption or site disaster. **Data Guard** is *disaster recovery and data protection*: it maintains one or more **standby** databases on separate storage (often a different data center) kept in sync by shipping and applying redo. It protects against **storage corruption, site loss, and human error** (via flashback on the standby), and standbys can offload read/reporting workloads (Active Data Guard). The canonical production design uses **both**: RAC for local HA, Data Guard for cross-site DR. Protection modes (Maximum Protection / Availability / Performance) trade commit latency against zero-data-loss guarantees.

### Q14. [Practical] You see heavy `gc buffer busy` and `gc cr block` waits on a RAC cluster. What's happening and how do you fix it?

These are **Cache Fusion** waits: an instance needs a block that another instance owns, so the block is shipped over the interconnect (consistent-read or current). Heavy `gc` waits usually mean **block contention across instances** — a classic cause is a right-growing index (monotonic sequence/IDENTITY key) where every node hammers the same rightmost leaf block, or a small hot table updated cluster-wide. Fixes, in order of preference: stop *all* nodes touching the same hot block by partitioning the workload (application affinity / services pinning related work to one node), use **reverse-key indexes** or hash-partitioned indexes to spread inserts, increase sequence `CACHE` and keep `NOORDER`, and check the private interconnect isn't saturated or misconfigured (it must be a dedicated low-latency network). I confirm the culprit via AWR's "Cluster" section and `gv$` views before changing anything.

### Q15. [Practical] Demonstrate Oracle Flashback features and a recovery scenario.

Flashback uses undo and the flashback logs/recycle bin to "go back in time" without restore-from-backup. **Flashback Query** reads data as of a past SCN/time (`AS OF TIMESTAMP`); **Flashback Version Query** shows every version of a row over a window; **Flashback Table** rewinds a whole table (requires row movement enabled); **Flashback Drop** restores a dropped table from the recycle bin; and **Flashback Database** rewinds the *entire* database to an SCN (requires flashback logging + FRA) — invaluable after a bad batch run or failed deployment, far faster than point-in-time restore.

```sql
-- "Someone deleted yesterday's orders" — recover without a restore
INSERT INTO orders
SELECT * FROM orders AS OF TIMESTAMP (SYSTIMESTAMP - INTERVAL '1' DAY) o
WHERE o.id NOT IN (SELECT id FROM orders);

-- See who changed a row and when
SELECT versions_xid, versions_operation, versions_starttime, amount
FROM   orders VERSIONS BETWEEN TIMESTAMP
       SYSTIMESTAMP - INTERVAL '1' HOUR AND SYSTIMESTAMP
WHERE  id = 42;
```

**Security note:** Flashback Query lets a user read historical data that may have been deliberately deleted (e.g. PII removed for GDPR). Lock down `FLASHBACK` privileges and be aware that aggressive undo retention can keep "deleted" sensitive data reconstructable longer than your data-retention policy allows.

### Q16. [Coding] Write a query using analytic functions to compute a 3-month moving average and month-over-month growth per product.

**Problem:** For each product and month, output the monthly revenue, the trailing 3-month moving average, and the percentage change versus the previous month.

```sql
WITH monthly AS (
  SELECT product_id,
         TRUNC(sale_date, 'MM')        AS mth,
         SUM(amount)                   AS revenue
  FROM   sales
  GROUP BY product_id, TRUNC(sale_date, 'MM')
)
SELECT product_id, mth, revenue,
       ROUND(AVG(revenue) OVER (
         PARTITION BY product_id ORDER BY mth
         ROWS BETWEEN 2 PRECEDING AND CURRENT ROW), 2)        AS mov_avg_3m,
       ROUND( (revenue - LAG(revenue) OVER (
                 PARTITION BY product_id ORDER BY mth))
              / NULLIF(LAG(revenue) OVER (
                 PARTITION BY product_id ORDER BY mth), 0) * 100, 1) AS mom_pct
FROM monthly
ORDER BY product_id, mth;
```

`ROWS BETWEEN 2 PRECEDING AND CURRENT ROW` defines the 3-row trailing window; `LAG` fetches the prior month; `NULLIF(..,0)` guards against divide-by-zero when the prior month was zero.

- **Time:** O(n log n) dominated by the sort within partitions.
- **Space:** O(n) for the windowed sort.
- **Edge cases:** first month per product has no `LAG` → `mom_pct` is null; months 1–2 average over fewer than 3 rows (window auto-shrinks); missing months are *not* interpolated — join to a calendar dimension if you need dense months.

### Q17. [Theory] What are SQL Plan Baselines and how do they differ from hints and profiles?

A **SQL Plan Baseline** (SQL Plan Management, `DBMS_SPM`) is a set of *accepted* execution plans for a statement; the optimizer will only use a plan that is both verified-better and accepted, giving **plan stability** across stats changes and upgrades while still allowing controlled evolution. A **SQL Profile** stores corrective information (scaling factors for cardinality estimates) that nudges the optimizer toward better estimates but does not freeze a specific plan. **Hints** are directives embedded in the SQL text that force optimizer choices and bypass its costing. The hierarchy of preference in production: fix stats/design first; use SQL Profiles when the optimizer mis-estimates; use Baselines to *lock* a known-good plan during upgrades; use hints only as a tactical, documented last resort because they don't adapt and they couple the plan to application code you may not own.

---

## 🔴 Expert (15+ yrs)

### Q18. [Theory] Discuss latches, mutexes, and the "library cache: mutex X" / cursor-sharing problems at scale.

Latches and mutexes are low-level serialization primitives protecting in-memory structures (the shared pool, library cache, buffer cache hash chains). At high concurrency the pathological case is **hard-parse storms**: applications that build SQL by string-concatenating literals (`WHERE id = 12345`) generate a unique statement per value, so each must be hard-parsed, each consumes shared-pool memory and a library-cache latch, and you see `library cache: mutex X` and `cursor: pin S` waits spike. The fix is **bind variables** (`WHERE id = :id`) so one parsed cursor is shared across executions — this also closes a **SQL injection** vector, making it a security fix as much as a performance one. `CURSOR_SHARING=FORCE` can retrofit literal-bind substitution as an emergency measure, but it can degrade plans for legitimately skewed predicates and is a band-aid, not a design. At extreme scale you also tune for **mutex contention** by avoiding excessively high child-cursor counts (caused by bind mismatch / adaptive cursor sharing explosion).

### Q19. [Practical] Design a zero-downtime migration of a 20 TB OLTP database to new hardware and a new major version. How?

I would use **Data Guard** plus **transient logical / rolling upgrade** patterns rather than a big-bang export. Step one: stand up the target on new hardware as a **physical standby**, let redo apply catch up over days with negligible production impact. For the version jump I'd evaluate **DBMS_ROLLING** (transient logical standby) so the standby is upgraded while still synced, then perform a **switchover** during a brief, well-rehearsed window — clients reconnect via a TNS/service alias or **Application Continuity** so in-flight transactions replay transparently. For the riskiest cutovers I keep the old primary as a standby for fast fallback (switchback) if KPIs regress. Alternatives: **GoldenGate** for true zero-downtime heterogeneous/bidirectional replication and long validation windows, or **Online Redefinition (`DBMS_REDEFINITION`)** for in-place table restructuring without locking. The non-negotiables are: rehearse the runbook against a full-size clone, capture a SQL Plan Baseline/Tuning Set *before* migration to catch post-upgrade plan regressions, and define objective rollback criteria up front.

```
[Prod Primary 19c] ──redo──► [Standby on new HW]
        │  (days of catch-up, zero prod impact)        │
        │                                    DBMS_ROLLING upgrade ▼
        │                                    [Standby now 23ai]
        └──────── brief switchover window ────────────► becomes PRIMARY
                  (Application Continuity replays in-flight txns)
        old primary kept as standby for fast fallback
```

### Q20. [Theory] When would you reach for In-Memory Column Store, and what's the cost model?

The **Database In-Memory** option keeps a *columnar* copy of selected tables/partitions in a dedicated In-Memory area (in addition to the row-format buffer cache), accelerating analytic scans/aggregations by orders of magnitude via SIMD vector processing and storage indexes that skip non-matching column ranges. It shines for **mixed OLTP+analytics** on the same database: OLTP keeps the row format while reports hit the column store, avoiding a separate ETL/warehouse. The cost model is honest: it consumes large amounts of RAM (you populate only the hot, analytically-queried objects), it's a separately licensed option, and every DML must maintain both the row buffer and the column store (mitigated by the transaction journal + periodic repopulation). In 23ai it pairs well with automatic in-memory population advisors. You would *not* use it to fix a poorly-indexed OLTP point-lookup — that's a B-tree problem, not a columnar one.

### Q21. [Practical] A real-world case study: tail-latency spikes on a high-throughput order system. Lead the investigation.

**Scenario:** A payments-adjacent order service on 4-node RAC shows p99 latency spiking from 20 ms to 800 ms several times an hour, no obvious CPU saturation. ASH (sampled during a spike) pointed at `enq: TX - index contention` and `gc buffer busy acquire` concentrated on the primary-key index of the `orders` table. **Root cause:** the PK used a single global sequence with `ORDER` (to look "tidy"), creating a right-hand hot leaf block that every RAC node fought over — Cache Fusion shipped that one block around the cluster on every insert. **Fix delivered:** switched the sequence to `NOORDER CACHE 100000`, converted the PK index to **hash-partitioned (16 ways)** to scatter inserts across leaf blocks, and pinned the heaviest service to a subset of nodes via RAC services to reduce cross-instance traffic. **Result:** `gc` waits dropped ~90%, p99 returned to ~25 ms and stayed flat under 2x load. **Lesson:** the "neat" monotonic ordered key was a textbook RAC anti-pattern; the data told the truth only because ASH captured the transient waits AWR's hourly average had hidden.

### Q22. [Behavioral] Tell me about a time you had to overrule a team's preferred database design. How did you handle it?

I structure the answer with **situation, the disagreement, how I drove to a decision, and the outcome**, emphasizing evidence over authority. A strong response: a team wanted bitmap indexes on an OLTP table because they "made the dashboard fast," but bitmap maintenance was serializing concurrent order updates and causing `enq: TX` waits. Rather than mandate by seniority, I reproduced the contention on a load-test clone, showed the wait-event profile with and without the bitmaps, and proposed a materialized view (refreshed on a schedule) to keep the dashboard fast *and* a B-tree on the OLTP path — meeting both goals. I document the decision and the measured trade-offs so it's a shared, reversible engineering call, not an edict. The signal interviewers want: you persuade with reproducible data, you respect the team's underlying goal, and you leave a written rationale others can revisit.

### Q23. [Theory] How do you secure an Oracle database beyond grants and roles?

Defense in depth: **least privilege** via roles (and revoke `PUBLIC` execute on dangerous packages like `UTL_FILE`, `DBMS_SCHEDULER`), **Virtual Private Database / Row-Level Security** to enforce row filters transparently regardless of the query, and **column-level redaction** (Data Redaction) so support staff see masked PII without changing apps. **Transparent Data Encryption (TDE)** encrypts data at rest (tablespace/column) so stolen datafiles or backups are useless without the keystore. **Unified Auditing** captures who did what for compliance, and **Database Vault** separates duties so even DBAs can't read application data — critical for regulated environments. At the SQL layer, the highest-impact control is still **bind variables** to prevent SQL injection, plus avoiding dynamic SQL built from untrusted input; when dynamic SQL is unavoidable, use `DBMS_ASSERT` to validate identifiers. Always pair these with network encryption (native or TLS) and a tested key-management/rotation process — TDE without key backups is a self-inflicted outage waiting to happen.

---

## 🧩 Extended Questions — Set 1: Deep Theory & Internals

### 🟢 Basic — extended

#### Q24. [Theory] What is the difference between `DELETE`, `TRUNCATE`, and `DROP`, and what happens internally with each?

These three commands differ in transactional semantics, what they touch on disk, and how recoverable they are. `DELETE` is **DML**: it removes rows one at a time, generates a full undo image of every deleted row, writes redo for both the change and the undo, fires `BEFORE/AFTER ... FOR EACH ROW` triggers, and respects `WHERE`. Because it is transactional you can `ROLLBACK` it, but on a large table it is slow and bloats undo/redo. Critically, `DELETE` does **not** lower the high-water mark (HWM), so a full scan after deleting 10M rows still reads all the empty blocks up to the old HWM — a classic "why is my count(*) still slow after I deleted everything" puzzle.

`TRUNCATE` is **DDL**: it deallocates the table's extents (or resets to `MINEXTENTS`) and resets the HWM to the beginning. It is effectively instantaneous regardless of row count, generates minimal undo (just the data-dictionary changes), issues an **implicit commit** before and after, does not fire DML triggers, and cannot be rolled back. `DROP` removes the object definition entirely; in modern Oracle it moves the table to the **recycle bin** (so `FLASHBACK TABLE ... TO BEFORE DROP` can recover it) unless you say `PURGE`.

```sql
DELETE FROM big_table WHERE status = 'OLD';   -- transactional, undo-heavy, HWM unchanged
TRUNCATE TABLE big_table;                       -- DDL, resets HWM, implicit commit, no rollback
TRUNCATE TABLE big_table DROP STORAGE;          -- also releases extents back
DROP TABLE big_table PURGE;                      -- skip the recycle bin entirely
```

| Aspect | DELETE | TRUNCATE | DROP |
|---|---|---|---|
| Class | DML | DDL | DDL |
| Rollback | Yes | No (implicit commit) | No (recyclebin recovers) |
| HWM reset | No | Yes | N/A |
| Fires DML triggers | Yes | No | No |
| `WHERE` filter | Yes | No | No |
| Speed on big table | Slow | Instant | Instant |

The interview signal is understanding that `TRUNCATE` is not "fast DELETE" — it is structurally different (no row-level undo, HWM reset), which is exactly why it cannot be filtered or rolled back.

#### Q25. [Theory] Explain the difference between `NULL` handling in Oracle and how it affects `=`, `NOT IN`, aggregates, and unique indexes.

`NULL` in Oracle means "unknown," and the key rule is that any comparison with `NULL` yields **UNKNOWN**, not TRUE or FALSE — so `WHERE col = NULL` and `WHERE col != NULL` both return zero rows; you must use `IS NULL` / `IS NOT NULL`. This three-valued logic is the source of the infamous `NOT IN` trap: `WHERE x NOT IN (SELECT y FROM t)` returns **no rows** if the subquery produces even a single `NULL`, because `x <> NULL` is UNKNOWN and `NOT IN` requires every comparison to be TRUE. `NOT EXISTS` does not have this problem and is the safer idiom for anti-joins.

Aggregates silently **ignore NULLs**: `AVG(col)` divides by the count of non-null values, and `COUNT(col)` counts only non-nulls while `COUNT(*)` counts rows. This means `AVG(salary)` and `SUM(salary)/COUNT(*)` can differ when some salaries are null. An Oracle-specific quirk is that an empty string `''` is treated as `NULL` (a legacy decision that diverges from the SQL standard and from PostgreSQL/SQL Server), so `WHERE name = ''` never matches.

```sql
-- Trap: returns ZERO rows if any manager_id is NULL
SELECT * FROM employees WHERE employee_id NOT IN (SELECT manager_id FROM employees);

-- Safe equivalent
SELECT e.* FROM employees e
WHERE NOT EXISTS (SELECT 1 FROM employees m WHERE m.manager_id = e.employee_id);
```

For indexing, a **B-tree index does not store entirely-NULL keys**, so `WHERE col IS NULL` cannot use a single-column B-tree index (it does a full scan) — a function-based index on `NVL(col, ...)` or a composite index whose other column is non-null works around it. Conversely, a unique index allows **multiple rows with NULL** in the indexed column precisely because all-null entries are not stored, which surprises people expecting Oracle to reject a second NULL.

#### Q26. [Theory] What is the difference between `VARCHAR2` and `CHAR`, and why does Oracle recommend `VARCHAR2` over `VARCHAR`?

`CHAR(n)` is **fixed-length**: Oracle blank-pads the value to the full declared width on storage, so `CHAR(10)` storing `'AB'` occupies 10 bytes as `'AB        '`. `VARCHAR2(n)` is **variable-length**: it stores only the actual characters plus a length byte, so `'AB'` takes ~3 bytes. The fixed-padding behavior of `CHAR` causes subtle comparison bugs because Oracle uses **blank-padded comparison semantics** when at least one operand is `CHAR` but **non-padded** semantics when both are `VARCHAR2` — so `'AB' = 'AB  '` can be TRUE or FALSE depending on the column types, which is a frequent source of "the join lost rows" mysteries. `CHAR` is justified only for genuinely fixed-width codes (a 2-char country code, a single status flag) and even then most shops standardize on `VARCHAR2`.

The `VARCHAR` keyword is currently a synonym for `VARCHAR2`, but Oracle **explicitly reserves the right to change `VARCHAR`'s semantics** in a future release (potentially to match the ANSI standard's NULL/empty-string handling). Because that future behavior is undefined, all Oracle documentation tells you to write `VARCHAR2` so your code's semantics are pinned and won't shift under an upgrade.

```sql
CREATE TABLE t (
  code  CHAR(3),          -- always padded to 3
  name  VARCHAR2(100)     -- stores actual length only; use this by default
);
-- Gotcha: blank-padded vs non-padded comparison
-- WHERE code = 'US'  may match 'US ' depending on operand types
```

A second decision is **length semantics**: `VARCHAR2(100 BYTE)` vs `VARCHAR2(100 CHAR)`. With multibyte character sets (AL32UTF8), `100 BYTE` may hold fewer than 100 characters and silently raise `ORA-12899` on a 100-character string. Use `CHAR` semantics (or set `NLS_LENGTH_SEMANTICS=CHAR`) for Unicode columns to avoid truncation surprises.

#### Q27. [Theory] What is the difference between `ROWNUM` and `ROW_NUMBER()`, and why does `WHERE ROWNUM > 1` return nothing?

`ROWNUM` is a **pseudocolumn assigned as rows are produced by the query**, before any `ORDER BY` is applied (unless that ordering comes from an index). Crucially, the value is assigned **after** the `WHERE` filter accepts a row and increments only when a row passes — so `WHERE ROWNUM > 1` can never be true: the first candidate row would have to be `ROWNUM = 1` to pass, but it fails the predicate, so `ROWNUM` is never incremented past 1 and **no row is ever returned**. The same logic makes `WHERE ROWNUM = 5` return nothing. Only `WHERE ROWNUM <= n` (or `= 1`) works, because it accepts a contiguous block starting at 1.

```sql
-- WRONG: returns zero rows, always
SELECT * FROM employees WHERE ROWNUM > 1;

-- Top-N done correctly: order in an inline view, THEN limit
SELECT * FROM (
  SELECT e.*, ROW_NUMBER() OVER (ORDER BY salary DESC) rn
  FROM employees e
) WHERE rn BETWEEN 11 AND 20;            -- a real "page 2"

-- 12c+ ANSI row limiting (cleaner, optimizer-aware)
SELECT * FROM employees ORDER BY salary DESC
OFFSET 10 ROWS FETCH NEXT 10 ROWS ONLY;
```

`ROW_NUMBER()` is an **analytic function** evaluated logically *after* the `WHERE`/`GROUP BY` and according to its own `ORDER BY` clause, so it gives deterministic, orderable row numbers you can filter on for true pagination. The classic mistake is `SELECT * FROM (SELECT ... ORDER BY x) WHERE ROWNUM <= 10` — that does work because the inline view materializes the order first — versus `SELECT ... WHERE ROWNUM <= 10 ORDER BY x`, which limits *before* ordering and gives you 10 arbitrary rows then sorts them. Since 12c, `FETCH FIRST n ROWS` is the readable, optimizer-friendly way and internally still uses a `ROW_NUMBER`-style window.

### 🟡 Intermediate — extended

#### Q28. [Theory] Walk through the lifecycle of a SQL statement: parse, bind, execute, fetch. Where does soft vs hard parse fit?

Every statement passes through distinct phases in the cursor lifecycle. During **parse**, Oracle syntactically and semantically checks the SQL, then computes a **hash of the statement text** and probes the library cache. If an identical sharable cursor exists, it is a **soft parse** — the existing plan and cursor are reused, skipping optimization entirely. If not, it is a **hard parse**: the optimizer runs (statistics lookup, plan enumeration, costing), allocates shared-pool memory, and builds the cursor. Hard parsing is expensive (CPU plus a library-cache mutex), which is why bind variables matter — literals make every value a distinct text and force a hard parse each time.

```
 ┌─ PARSE ──────────────────────────────────────────────┐
 │  syntax/semantic check → hash text → probe lib cache  │
 │     hit  → SOFT parse  (reuse cursor + plan)          │
 │     miss → HARD parse  (optimize, build cursor)       │
 └───────────────────────────────────────────────────────┘
        │
   BIND  (substitute :var values; bind peeking on hard parse)
        │
   EXECUTE  (run plan; for DML do the work + redo/undo)
        │
   FETCH  (return rows to client, array-sized by arraysize)
```

**Bind** substitutes the actual values for placeholders. On a hard parse, Oracle does **bind peeking** — it looks at the first bind value to choose a plan, which since 11g is refined by **Adaptive Cursor Sharing** so a skewed column can have multiple child cursors (one plan for selective binds, another for unselective). **Execute** runs the plan; for queries this positions the result, for DML it performs the change and writes redo/undo. **Fetch** ships rows to the client in array batches governed by the client `arraysize` — a too-small arraysize causes excessive round trips (`SQL*Net message from client` waits). The cheapest possible execution is **no parse at all**: a held/cached cursor that only re-binds and re-executes, which is what connection pools and `session_cached_cursors` aim for.

#### Q29. [Theory] What is the clustering factor of an index, and why does it determine whether the optimizer uses the index?

The **clustering factor (CF)** measures how well the physical row order in the table matches the logical order of the index keys. The optimizer computes it by walking the index in order and counting how many times consecutive index entries point to a *different* table block than the previous entry. If the table is physically sorted the same way as the index, the CF is close to the **number of blocks** (best case); if rows are scattered randomly, the CF approaches the **number of rows** (worst case). It directly drives the optimizer's estimate of how many block I/Os a range scan plus table access will cost.

```
Index keys in order:  A1 A2 A3 B1 B2 C1 C2 ...
Good CF (≈ #blocks):  [A1 A2 A3][B1 B2][C1 C2]   ← few block switches, cheap range scan
Bad  CF (≈ #rows):    A→blk7, A→blk2, A→blk9 ...  ← every row a new block, ~one I/O per row
```

This is why two indexes on the *same* table with the *same* selectivity can lead to wildly different decisions: an index on a column the table is naturally ordered by (e.g. an append-only `order_date` on a heap that grows by time) has a great CF and the optimizer happily range-scans it, whereas an index on a randomly-distributed column has a terrible CF, so a range scan returning even 2–3% of rows looks as expensive as a full table scan and the optimizer correctly prefers the full scan.

The practical consequences: rebuilding an index does **not** improve its CF (the *table* order is what matters, not the index), so the real fix is reorganizing the table (e.g., an IOT, a sorted CTAS, or a hash/range cluster) or accepting that a low-CF index is only worth it for very selective single-key lookups. It also explains why composite-index column order and table loading order interact — loading data in index-key order is a cheap way to manufacture a good clustering factor for the queries that matter.

#### Q30. [Theory] Explain undo vs redo: what each protects, and how they cooperate during commit, rollback, and instance recovery.

**Redo** is the forward-change log: every change to a data block (including changes to *undo* blocks) is recorded as a redo record in the log buffer and flushed to the online redo logs. Its job is **durability and recovery** — replaying redo can reconstruct committed changes that hadn't yet been written to datafiles when the instance crashed. **Undo** is the *before-image* of changed data, stored in undo segments (themselves protected by redo). Its jobs are **transaction rollback, read consistency (MVCC), and flashback** — it lets a session reverse its own uncommitted changes and lets readers reconstruct an older block version as of a past SCN.

```
 UPDATE row:
   1. write UNDO (old value) to undo segment ── also logged in REDO
   2. modify the data block in the buffer cache ── logged in REDO
   3. (later) DBWR writes dirty block to datafile (async)
   4. LGWR flushes REDO to logfile (on commit, must complete first)

 COMMIT  = LGWR flushes redo up to the commit record (write-ahead logging)
 ROLLBACK = apply UNDO to reverse uncommitted changes
 CRASH RECOVERY = roll FORWARD with redo (redo all), then roll BACK
                  with undo (reverse the uncommitted)
```

The cooperation is the **write-ahead logging (WAL) protocol**: a commit is durable the instant LGWR has flushed the redo up to and including the commit marker — the dirty data blocks themselves may still be in the buffer cache and get written later by DBWR. This is why `COMMIT` latency is bound by *log* I/O (`log file sync` waits), not datafile I/O. On crash recovery Oracle does two passes: **roll forward** by replaying all redo (which re-creates both committed *and* uncommitted changes, including the undo segments), then **roll back** by using that recovered undo to reverse transactions that never committed. Because undo is itself protected by redo, this two-phase scheme correctly handles a crash that happened mid-transaction, and it also underpins `ORA-01555` — if undo needed for a long read has been overwritten, the consistent image can no longer be built.

#### Q31. [Theory] Compare the four transaction isolation levels Oracle actually supports and explain why Oracle has no dirty reads or true SERIALIZABLE-via-locking.

Oracle implements isolation with **multiversioning**, not read locks, so its behavior differs from the lock-based databases the ANSI levels were written for. Oracle supports **READ COMMITTED** (the default) and **SERIALIZABLE** as statement-spanning levels, plus **READ ONLY** (a session-level snapshot that disallows writes) and the special **SERIALIZABLE** semantics; it does *not* support READ UNCOMMITTED because **dirty reads are impossible** — a reader always builds a consistent image from undo and never sees another transaction's uncommitted data. There is therefore no concept of seeing in-flight changes.

| Level | Dirty read | Non-repeatable read | Phantom | How Oracle does it |
|---|---|---|---|---|
| READ UNCOMMITTED | — | — | — | **Not supported** (MVCC makes dirty reads impossible) |
| READ COMMITTED (default) | No | Possible | Possible | New snapshot per *statement* |
| SERIALIZABLE | No | No | No | Snapshot fixed at *transaction* start; `ORA-08177` on conflict |
| READ ONLY | No | No | No | Transaction-level snapshot, no DML allowed |

Under **READ COMMITTED** each *statement* sees a snapshot as of when that statement began, so two executions of the same query in one transaction can return different results (non-repeatable reads and phantoms are allowed). Under **SERIALIZABLE** the snapshot is fixed at *transaction* start; if the transaction tries to update a row that was changed and committed by another transaction after that point, Oracle raises **`ORA-08177: can't serialize access`** rather than blocking — this is **optimistic** serialization (first-committer-wins), not the pessimistic range-locking SQL Server uses. The practical implication is that SERIALIZABLE applications must be coded to **catch ORA-08177 and retry**. Note Oracle's SERIALIZABLE is technically *snapshot isolation*, which historically permitted write-skew anomalies, distinct from true serializability — a subtlety worth flagging in a senior interview.

#### Q32. [Practical] What is the difference between `DECODE` and `CASE`, `NVL` and `COALESCE`, and `NVL2`? When do their evaluation semantics bite you?

`DECODE` is an Oracle-proprietary function doing equality mapping: `DECODE(x, a, r1, b, r2, default)`. Its quirk is that it treats **NULL as equal to NULL** (unlike `=`), so `DECODE(col, NULL, 'was null', 'had value')` actually works. `CASE` is the ANSI-standard, more readable and more powerful form (it supports ranges and arbitrary boolean conditions, not just equality), and it can be used in PL/SQL as a statement. Both `CASE` and `DECODE` **short-circuit** — they stop evaluating branches once a match is found.

```sql
-- DECODE: equality only, NULL = NULL
SELECT DECODE(status, 'A','Active', 'I','Inactive', 'Unknown') FROM t;

-- CASE: ranges & conditions, ANSI, readable
SELECT CASE WHEN salary > 100000 THEN 'High'
            WHEN salary > 50000  THEN 'Mid'
            ELSE 'Low' END FROM employees;
```

The subtle trap is **eager vs lazy evaluation in `NVL` vs `COALESCE`**. `NVL(a, b)` always evaluates **both** arguments before returning, so `NVL(x, expensive_function())` or `NVL(x, 1/0)` runs the second argument even when `x` is non-null — potentially raising errors or doing needless work. `COALESCE(a, b, c, ...)` is **short-circuiting**: it stops at the first non-null and never evaluates later arguments, so `COALESCE(x, 1/0)` is safe when `x` is non-null. `COALESCE` also takes N arguments and is ANSI-standard, while `NVL` takes exactly two. `NVL2(x, val_if_not_null, val_if_null)` is a three-way variant.

```sql
-- NVL evaluates BOTH args → this can raise ZERO_DIVIDE even when x is not null
SELECT NVL(x, 1/0) FROM t;          -- dangerous
-- COALESCE short-circuits → safe when x is not null
SELECT COALESCE(x, 1/0) FROM t;     -- only divides if x IS null
```

The rule of thumb: prefer `CASE`/`COALESCE` for portability and safe evaluation; reach for `DECODE`/`NVL` only in legacy code or when you specifically want `DECODE`'s NULL-equality behavior. A type subtlety: `NVL` implicitly converts the second argument to the first's datatype, whereas `COALESCE` requires compatible types and may error instead of silently converting — which is arguably safer.

#### Q33. [Theory] What is the difference between PGA and SGA, and how does automatic memory management (AMM vs ASMM) allocate them?

The **SGA (System Global Area)** is the *shared* memory segment all server processes attach to: it holds the **buffer cache** (cached data blocks), the **shared pool** (library cache of parsed SQL/PLSQL plus the data dictionary cache), the **redo log buffer**, the **large pool**, and (if enabled) the In-Memory column store. It is allocated at instance startup and shared cluster-wide-per-instance. The **PGA (Program Global Area)** is *private* per server process: it holds session state, cursor state, and crucially the **work areas** for sorts, hash joins, and bitmap operations. A big sort or hash join that exceeds its work-area allotment spills to **temp tablespace** (a one-pass or multi-pass operation), which is why `PGA_AGGREGATE_TARGET` sizing directly affects analytic-query performance.

```
 ┌──────────────── SGA (shared) ───────────────┐     PGA (private, per process)
 │ Buffer Cache | Shared Pool | Redo Buffer     │     ┌────────────────────────┐
 │ Large Pool   | Java Pool   | In-Memory Store │     │ session memory          │
 └──────────────────────────────────────────────┘     │ SQL work areas          │
        controlled by SGA_TARGET                       │  (sort_area / hash_area)│
                                                        └────────────────────────┘
                                                        controlled by PGA_AGGREGATE_TARGET
```

For management there are two models. **ASMM (Automatic Shared Memory Management)**, set via `SGA_TARGET`, lets Oracle auto-tune the *internal* SGA components (buffer cache vs shared pool, etc.) against each other, while PGA is tuned separately by `PGA_AGGREGATE_TARGET`. **AMM (Automatic Memory Management)**, set via `MEMORY_TARGET`, goes further and lets Oracle move memory **between** SGA and PGA automatically as the workload shifts. AMM sounds attractive but is generally **discouraged on Linux** because it requires `/dev/shm` (tmpfs) and is incompatible with HugePages — and HugePages is essentially mandatory for large SGAs to avoid page-table bloat and TLB thrashing. So the production-standard configuration is ASMM (`SGA_TARGET` + HugePages) plus a separately-set `PGA_AGGREGATE_TARGET`, with the newer `PGA_AGGREGATE_LIMIT` as a hard ceiling to prevent PGA from exhausting host memory.

#### Q34. [Practical] What is the difference between a correlated and a non-correlated subquery, and how does the optimizer transform them (unnesting)?

A **non-correlated subquery** can be evaluated independently of the outer query — it references no outer columns, so logically it runs once and feeds its result up (e.g. `WHERE dept_id IN (SELECT id FROM departments WHERE region='EU')`). A **correlated subquery** references columns from the outer query, so *logically* it must be re-evaluated for each outer row (e.g. `WHERE EXISTS (SELECT 1 FROM orders o WHERE o.cust_id = c.id)`). Written naively, a correlated subquery suggests an O(outer × inner) nested execution, which is why people fear them.

In practice the optimizer almost always performs **subquery unnesting**: it rewrites the subquery into a **join** (often a semi-join for `EXISTS`/`IN`, or an anti-join for `NOT EXISTS`/`NOT IN`) so it can choose hash/merge/nested-loop join methods and proper join order based on cost, rather than literally looping. This is why `EXISTS` and `IN` frequently produce *identical* plans on modern Oracle — both unnest to a semi-join — and the historical "always prefer EXISTS over IN" advice is largely obsolete. The meaningful difference remains in **NULL handling** (`NOT IN` with nullable columns is the trap from Q25, while `NOT EXISTS` is null-safe).

```sql
-- Correlated EXISTS → optimizer unnests to a SEMI JOIN
SELECT c.* FROM customers c
WHERE EXISTS (SELECT 1 FROM orders o WHERE o.cust_id = c.id);

-- NOT EXISTS → ANTI JOIN (null-safe); prefer over NOT IN on nullable cols
SELECT c.* FROM customers c
WHERE NOT EXISTS (SELECT 1 FROM orders o WHERE o.cust_id = c.id);
```

Unnesting can be blocked by certain constructs (`ROWNUM` in the subquery, some aggregates, user-defined functions with side effects), in which case the optimizer falls back to **filter** evaluation (the literal per-row loop) — visible as a `FILTER` operation in the plan with the subquery as a child. When you see a `FILTER` driving millions of executions of a subquery in `DBMS_XPLAN`, that is the optimizer failing to unnest, and the fix is to rewrite as an explicit join or remove the unnest-blocking construct. Scalar subqueries in the `SELECT` list have their own optimization, **scalar subquery caching**, where Oracle caches results per distinct input value within a statement.

### 🟠 Advanced — extended

#### Q35. [Theory] Explain how Oracle implements row-level locking without a lock manager, and what the "ITL" and lock bytes in the block are.

Unlike databases that keep locks in a central in-memory lock table (which consumes memory proportional to the number of locked rows and can escalate to page/table locks under pressure), Oracle stores **row locks inside the data block itself**. Each block header contains **Interested Transaction List (ITL)** slots; when a transaction modifies a row, it acquires an ITL slot (pointing to its undo) and sets a **lock byte in the row's directory entry** referencing that ITL slot. The lock therefore costs *no separate memory* and there is **no lock escalation** — Oracle can lock a billion rows without running out of lock memory, which is a fundamental concurrency advantage.

```
 Data block:
 ┌───────────────────────────────────────────────┐
 │ block header                                    │
 │  ITL slots:  [slot0: Txn A → undo ptr, SCN]     │  ← transaction "owns" the row via slot
 │              [slot1: free]                       │
 │  ...                                             │
 │ row directory: row5 → lock byte = ITL slot 0     │  ← the actual "lock"
 │ row data ...                                     │
 └───────────────────────────────────────────────┘
```

The consequence is that a reader checking whether a row is locked just inspects the lock byte and the ITL; a writer waiting on a locked row enqueues on a **TX enqueue** (the `enq: TX - row lock contention` wait event) keyed to the blocking transaction, and is awoken when that transaction commits or rolls back. Because the lock lives with the data, "who is blocking whom" is resolved through `V$LOCK`/`V$TRANSACTION` rather than a lock table.

One real-world failure mode falls straight out of this design: **ITL contention** (`enq: TX - allocate ITL entry`). If a block has too few ITL slots (controlled by `INITRANS`) and many transactions try to modify *different* rows in the *same* block concurrently, they serialize waiting for a free ITL slot — common on small, hot tables or indexes with the default `INITRANS 1/2`. The fix is to raise `INITRANS` or reduce rows-per-block (lower `PCTFREE`/higher `PCTUSED` tuning) so concurrent transactions each get their own slot.

#### Q36. [Theory] How does deadlock detection work in Oracle, and how does it differ from a simple lock timeout?

Oracle runs **automatic deadlock detection**: it maintains a *waits-for graph* among enqueues, and a background mechanism periodically checks for a **cycle** (transaction A waits on a resource held by B, while B waits on a resource held by A). When a cycle is detected, Oracle does not wait for any timeout — it immediately picks one transaction as the victim, rolls back **just the statement** that closed the cycle (not the whole transaction), and raises **`ORA-00060: deadlock detected`** to that session, writing a trace file with the deadlock graph. The other transaction proceeds. This is fundamentally different from a *lock timeout*: a timeout (like `innodb_lock_wait_timeout` in MySQL or `SET LOCK_TIMEOUT` in SQL Server) simply gives up after N seconds whether or not a true deadlock exists, which both lets real deadlocks linger and falsely kills legitimate long waits.

```
 Waits-for graph (cycle = deadlock):
   Txn A  ──waits for row R2 (held by B)──►  Txn B
     ▲                                          │
     └──────── waits for row R1 (held by A) ◄───┘
   → Oracle detects the cycle, kills the statement that completed it (ORA-00060)
```

The crucial design point for application developers: ORA-00060 rolls back **only the offending statement**, leaving the rest of the victim's transaction intact and *still holding its other locks* — the session is **not** automatically rolled back. So robust code must trap ORA-00060 and explicitly `ROLLBACK` (or roll back to a savepoint) and retry, because simply re-issuing the failed statement while still holding locks can immediately re-deadlock. The classic prevention is enforcing a **consistent lock-acquisition order** across the application (e.g., always lock accounts in ascending account-id order) so cycles cannot form in the first place. A frequent hidden cause is **unindexed foreign keys**: a child-table DML or parent update takes a full-table share lock on the child, dramatically widening the contention surface and turning ordinary concurrent updates into deadlocks.

#### Q37. [Theory] Explain the shared pool's library cache and the difference between cursor invalidation and cursor obsolescence. What causes ORA-04068?

The **library cache** (within the shared pool) holds parsed representations of SQL cursors and compiled PL/SQL, plus dependency metadata linking each cursor/object to the objects it depends on. When you change a dependent object — gather stats, add an index, alter a table, recompile a package — Oracle must ensure no stale plan or stale compiled code is reused, so it performs **cursor invalidation**: dependent cursors are marked invalid and will be (hard) re-parsed on next use. Historically, DDL caused an immediate hard invalidation storm (a flood of hard parses right after a deployment); modern Oracle (12.2+) uses **rolling/deferred invalidation** so cursors are invalidated gradually over a randomized window (`_optimizer_invalidation_period`) to avoid a hard-parse spike.

**Obsolescence** is different from invalidation: a parent cursor can accumulate many **child cursors** (variants differing by bind types, NLS settings, optimizer environment, adaptive cursor sharing, etc.). When the number of children grows past a threshold, Oracle marks the parent cursor **obsolete** and builds a fresh one, retiring the bloated family — this prevents a single SQL with pathological non-sharing from consuming unbounded shared-pool memory and causing `library cache: mutex X` contention while searching a huge child list.

`ORA-04068: existing state of packages has been discarded` is a related but distinct PL/SQL phenomenon. **Package state** (package-level variables, cursors) lives in a session's UGA and is tied to the package's compilation. If a package (or something it depends on) is recompiled while a session has *instantiated* that package's state, on the session's next reference Oracle detects the state is now invalid and discards it, raising `ORA-04068` (preceded by `ORA-04065`/`ORA-06508`). This is why deploying changes to packages **with package-level variables** during active sessions is disruptive — the workaround is either deploying during quiet windows, using **edition-based redefinition (EBR)** for true online code changes, or designing packages to be stateless so there is no state to discard.

#### Q38. [Theory] What is the difference between an index range scan, index unique scan, index skip scan, index full scan, and index fast full scan?

These five access paths all use a B-tree index but read it very differently. An **INDEX UNIQUE SCAN** is used when a predicate on a unique/primary-key index guarantees at most one row — it descends the tree to exactly one leaf entry, the cheapest possible index access. An **INDEX RANGE SCAN** descends to the first qualifying leaf and walks the leaf chain reading consecutive entries while the predicate holds (equality on a non-unique column, or a `>`/`<`/`BETWEEN`/`LIKE 'abc%'` range) — entries come back in index order, which the optimizer can exploit to avoid a sort.

```
 INDEX UNIQUE SCAN   : root→branch→one leaf entry            (pk = :id)
 INDEX RANGE SCAN    : root→branch→leaf, walk leaf chain →   (col BETWEEN a AND b)
 INDEX SKIP SCAN     : "skip" leading col values, sub-scan   (idx(a,b), predicate on b only)
 INDEX FULL SCAN     : walk ALL leaves in ORDER (single-block reads)
 INDEX FAST FULL SCAN: read ALL blocks multiblock, UNORDERED (like a full table scan of the index)
```

An **INDEX SKIP SCAN** (9i+) lets a composite index `(a, b)` satisfy a predicate on `b` alone by internally probing the index once per *distinct value of the leading column* `a` — worthwhile only when `a` has **low cardinality** (few distinct values to skip through); with high-cardinality `a` it degenerates and a full scan wins. An **INDEX FULL SCAN** reads every leaf block **in key order** using single-block I/O, chosen when the optimizer needs the data *sorted* (e.g., to satisfy an `ORDER BY` or as the probe side of a sort-merge) or when the index covers all needed columns and avoids the table. An **INDEX FAST FULL SCAN** reads the entire index segment with **multiblock I/O** like a full table scan, returning rows in **physical (unordered)** order — by far the fastest way to scan an index when order doesn't matter and the index is a "skinny" covering structure (e.g., `COUNT(*)` off a NOT NULL indexed column). The key trade-off: full scan = ordered + slow single-block reads; fast full scan = unordered + fast multiblock reads (and it can run in parallel).

#### Q39. [Theory] Compare a heap-organized table, an index-organized table (IOT), and a hash cluster. What workload does each suit?

A **heap-organized table** is the default: rows are stored in no particular order in whatever free block is available, and a primary key is a *separate* B-tree index that stores the key plus a `ROWID` pointer back to the heap. A PK lookup therefore costs an index traversal **plus** a table block read (two structures). This is ideal for general OLTP and for tables accessed by many different columns/indexes.

An **index-organized table (IOT)** stores the *entire row inside the primary-key B-tree leaf*, ordered by PK — there is no separate heap. A PK lookup is a single structure traversal (the row is right there at the leaf), and rows physically clustered by PK make range scans on the key extremely efficient. IOTs shine for tables almost always accessed by PK or PK-prefix range (lookup tables, association/intersection tables, time-series keyed by `(id, ts)`). The trade-offs: secondary indexes on an IOT store a **logical rowid (UROWID)** guess rather than a physical ROWID, so secondary-index access can require an extra probe, and very wide non-key columns force an **overflow segment**, partially recreating the two-structure cost.

```
 Heap table:        PK index ──ROWID──► heap block (2 reads on PK lookup)
 IOT:               PK B-tree leaf CONTAINS the whole row (1 read, rows sorted by PK)
 Hash cluster:      hash(key) → block directly, NO index at all (1 read, equality only)
```

A **hash cluster** stores rows by applying a **hash function to the cluster key** to compute the block directly — so an equality lookup needs **no index traversal at all**, just one hash computation and one block read. It is the fastest possible equality access for a well-known, fixed-size, equality-accessed table (the canonical example is a reference/dimension table fetched by exact key). Its weaknesses are severe for the wrong workload: you must pre-size the number of hash buckets (`HASHKEYS`/`SIZE`) up front, a poor estimate causes either wasted space or **overflow block chaining** that destroys the single-read benefit, and because data is scattered by hash it is **terrible for range scans** (`BETWEEN`/`<`) on the cluster key. So: heap for general-purpose, IOT for PK-centric and range-on-key access, hash cluster for high-volume exact-match lookups on a stable, well-understood table.

#### Q40. [Practical] Explain how `DBMS_STATS` gathering options (estimate_percent, method_opt, AUTO_SAMPLE_SIZE, incremental) change statistics quality and gather cost.

Statistics quality is a trade-off between accuracy and the time/IO to gather. **`ESTIMATE_PERCENT`** controls how much data is sampled: a low percentage is fast but can produce inaccurate **NDV (number of distinct values)** estimates — and NDV drives cardinality, the most important input to the optimizer. The modern recommendation is **`AUTO_SAMPLE_SIZE`** (the default since 11g), which uses a special **one-pass, hash-based distinct-value algorithm** that scans 100% of the data but computes NDV in a single pass at roughly the cost of a sample — giving near-exact NDV without the inaccuracy of small samples. Hard-coding `ESTIMATE_PERCENT => 10` is now an anti-pattern that *degrades* accuracy versus the default.

```sql
-- Recommended modern defaults
BEGIN
  DBMS_STATS.GATHER_TABLE_STATS(
    ownname          => 'SALES_OWNER',
    tabname          => 'SALES',
    estimate_percent => DBMS_STATS.AUTO_SAMPLE_SIZE,     -- one-pass exact NDV
    method_opt       => 'FOR ALL COLUMNS SIZE AUTO',     -- histograms where skew + usage warrant
    granularity      => 'AUTO',                          -- global + partition stats
    cascade          => TRUE);                           -- include indexes
END;
/
```

**`METHOD_OPT`** governs **histograms**: `FOR ALL COLUMNS SIZE AUTO` tells Oracle to create histograms only on columns that are both *skewed* and *actually used in predicates* (it consults column-usage tracking). Forcing `SIZE 1` (no histograms) blinds the optimizer to skew (the `status='PENDING'` problem from the base questions), while `SIZE 254` everywhere wastes space and gather time and can cause bind-peeking instability. Histogram type matters by version: 12c added **top-frequency** and **hybrid** histograms (replacing the old height-balanced ones) which handle high-distinct-count skewed columns far better.

**Incremental statistics** (`INCREMENTAL => TRUE`) is the big win for large partitioned tables: instead of re-scanning the entire table to recompute *global* stats after one partition changes, Oracle maintains per-partition **synopses** and aggregates them to derive global-level NDV cheaply — turning an overnight full gather into minutes. The cost is the synopsis storage in `SYSAUX` and the requirement that you gather at the partition level. Other levers worth knowing: `DEGREE` for parallel gathering, `NO_INVALIDATE` to control the cursor-invalidation timing after a gather (tying back to Q37), and **pending stats** (`PUBLISH => FALSE`) to validate new statistics against a test workload before making them visible to production.

### 🔴 Expert — extended

#### Q41. [Theory] Explain how Cache Fusion maintains coherency in RAC: the roles of GCS, GES, the master, and the difference between current and consistent-read block transfers.

In RAC every instance has its own buffer cache, yet they all mount one physical database, so Oracle must guarantee global cache coherency without writing every shared block to disk. This is **Cache Fusion**, coordinated by two services in the Global Resource Directory: **GCS (Global Cache Service)** governs *data block* access across instances, and **GES (Global Enqueue Service)** governs non-block resources (enqueues/locks, library-cache, dictionary). Each block (resource) has a **master** instance that tracks which instances currently hold copies and in what mode; mastery is distributed across nodes and can **remaster** dynamically to the instance accessing a resource most (affinity), reducing interconnect chatter.

```
 Instance 1 wants block B (mastered by Inst 3, currently dirty in Inst 2):
   1. Inst1 → GCS master (Inst3): "I need B"
   2. master tells holder (Inst2) to ship B over the INTERCONNECT
   3. Inst2 builds the image, ships block directly to Inst1 (not via disk!)
   4. master records new holder/mode in the Global Resource Directory
```

The pivotal innovation is that a block needed by another instance is **shipped directly over the high-speed interconnect**, not flushed to disk and re-read (the old "ping to disk" model of OPS, Oracle's pre-9i clustering). Two transfer flavors exist: a **CR (consistent-read) transfer** ships a *read-consistent version* of the block (reconstructed via undo to a past SCN) for a query — the sender keeps its copy; a **current-block transfer** ships the *current* version for a session that intends to modify it, transferring ownership of the right to change it. These map to the wait events `gc cr block ...` and `gc current block ...`. A subtlety is the **write-write coherency** problem: before a block can be shipped current to a modifier, the holder may need to write redo (and the past-image handling ensures recovery correctness) — Oracle uses **past images (PI)** retained in the sender's cache so that if the new owner's instance crashes before writing, recovery can rebuild from the PI plus redo. This whole scheme is why a **right-growing hot block** (monotonic key, from the base-question RAC anti-pattern) is so toxic: the same block ping-pongs current across nodes, saturating the interconnect with current-block transfers.

#### Q42. [Theory] Walk through Oracle's commit internals: what LGWR does, group commit, the "commit cleanout", and why `COMMIT NOWAIT` / batch commit exist.

A `COMMIT` does far less than people imagine — it does **not** write the changed data blocks to disk. The durable act is: Oracle writes a **commit redo record** carrying the transaction's SCN, then **LGWR flushes the redo log buffer up to and including that commit record** to the online redo log. Once that log write completes, the transaction is durable (write-ahead logging) and LGWR posts the waiting foreground process. The session's wait during this flush is the **`log file sync`** event, and its latency is bounded by **log file I/O latency**, not datafile I/O — which is why fast/low-latency redo storage (or properly configured redo on flash) is the single biggest lever on commit-heavy OLTP throughput.

```
 COMMIT path:
   foreground: generate commit record → post LGWR → WAIT (log file sync)
   LGWR:       gather redo from MANY committing sessions (GROUP COMMIT)
               → one big sequential write to redo log (log file parallel write)
               → post all waiters at once
   later:      block cleanout marks rows committed lazily (delayed block cleanout)
```

**Group commit** is the throughput multiplier: while LGWR is busy writing, other sessions pile their commit records into the log buffer; LGWR's next write flushes *all* of them in **one** I/O and posts them all together. So under load, thousands of commits per second cost far fewer than thousands of log writes — the per-commit cost amortizes. This is also why an artificially "chatty" commit-per-row pattern is wasteful: it maximizes `log file sync` round trips even though group commit softens the blow; batching DML between commits is the right pattern (balanced against undo retention and lock duration).

Two more internals matter. **Commit cleanout / delayed block cleanout**: at commit, Oracle only marks the *transaction table* entry committed and does a fast "commit cleanout" of blocks still in cache; blocks already aged out are **not** revisited — instead the *next session to read* such a block notices the transaction committed (via the ITL/SCN), cleans the block, and (surprisingly) generates **redo and undo while merely SELECTing** — the classic "why did my read-only query generate redo / cause `ORA-01555`" puzzle. Finally, **`COMMIT WRITE NOWAIT/BATCH`** (11g+) lets you relax durability for throughput: `NOWAIT` returns without waiting for LGWR (risking loss of the last commits on a crash), and `BATCH` lets LGWR defer the write — appropriate only for workloads that can tolerate losing a few recent transactions (e.g., reloadable staging), never for financial systems.

#### Q43. [Theory] What are bind variable peeking and adaptive cursor sharing, and how can they cause "plan flips" that bind variables were supposed to prevent?

Bind variables solve the hard-parse/shared-pool problem by letting one cursor serve many values, but they create a tension: the optimizer wants the *value* to estimate cardinality (especially on skewed columns), yet the whole point of a shared cursor is that the value isn't baked in. **Bind peeking** (9i+) resolves this by having the optimizer *peek at the bind value present during the hard parse* and optimize for **that** value — then reuse the resulting plan for all later executions regardless of their bind values. This is great when the column is uniformly distributed, but **catastrophic on skewed data**: if the first execution happens to bind a rare value (`status='ARCHIVED'`, 0.1% of rows), the optimizer picks an index plan and *every* subsequent execution — including one binding `status='ACTIVE'` (95% of rows) — inherits that index plan and does millions of single-block reads instead of a full scan. The plan you get becomes a lottery decided by *which value was bound first after the cursor was (re)parsed*.

```
 Hard parse binds 'ARCHIVED' (rare) → optimizer peeks → picks INDEX plan
   later execution binds 'ACTIVE' (95%) → REUSES index plan → disaster
   (or vice-versa) → "why is the same query sometimes fast, sometimes slow?"
```

**Adaptive Cursor Sharing (ACS, 11g+)** was introduced to fix exactly this. ACS marks a cursor **bind-sensitive** when a peeked predicate involves something where the bind value could change cardinality (e.g., a histogram exists). On execution it monitors actual rows processed; if executions with different binds show very different row counts, it marks the cursor **bind-aware** and begins generating **multiple child cursors**, each tied to a *range* of selectivity, so a selective bind gets the index plan and an unselective bind gets the full-scan plan — both cached side by side. The cost is more child cursors (more shared-pool memory, slightly more parsing) and the fact that ACS **learns reactively**: the *first* bad execution still happens before ACS notices and splits the cursor, so you can observe a transient slow run after a flush/restart.

The practical implications for an expert: plan flips under bind peeking are a top cause of "it was fine yesterday" incidents, often triggered by a **stats gather or shared-pool flush** that re-hard-parsed the cursor with a different first bind. The durable fixes are to **lock the good plan with a SQL Plan Baseline** (Q17), or — where the column is so skewed that one plan can never serve all values — to *deliberately use literals* for that predicate (so each value gets its own correctly-costed cursor) accepting the extra hard parses, or to split the query. ACS and SQL Plan Management interact, and on busy systems many shops disable ACS for specific pathological SQL via baselines rather than letting it thrash.

#### Q44. [Theory] Explain the System Change Number (SCN): what it is, how it advances, its role across redo/flashback/Data Guard/RAC, and what "SCN headroom" exhaustion means.

The **SCN (System Change Number)** is Oracle's logical, monotonically increasing clock — a 48-bit counter stamped on every committed change and on read-consistent snapshots. It is the backbone of nearly every consistency mechanism: a query records the current SCN at start and uses it to decide, block by block, whether to read the block as-is or reconstruct an older version from undo (**read consistency**); each commit advances the SCN and stamps the commit record in redo (**recovery ordering**); **flashback** maps a wall-clock time to an SCN via `SMON_SCN_TIME` to read the past; **media/crash recovery** replays redo in SCN order; and across **Data Guard** the standby applies redo to a known SCN, while **RAC** keeps a single global SCN coordinated across instances (via the Lamport or broadcast-on-commit scheme) so all nodes agree on ordering.

```
 SCN advances on: every COMMIT, and periodically by background activity
   query @ SCN=5000 → for each block: block.SCN <= 5000 ? read it
                                       block.SCN >  5000 ? rebuild via UNDO
 RAC: global SCN agreed across instances (broadcast-on-commit / Lamport)
 Data Guard: standby applies redo up to a recovery SCN; failover at known SCN
```

A subtle operational risk is **SCN headroom**. Oracle enforces a *soft* upper bound on how high the SCN may be relative to the database's age — computed as roughly `(seconds since 1988) × a rate` — to leave room for growth. If a database advances SCN abnormally fast (historically via excessive commits, or by **database links to a system with a much higher SCN**, since linked databases synchronize their SCNs to the highest participant), it can approach the headroom limit and, in the worst case, hit `ORA-600` errors or refuse new transactions. This is why a single misbehaving high-commit-rate database connected by DB links can "infect" an entire mesh of linked databases by dragging all their SCNs upward. Oracle shipped patches that raised the SCN advance-rate limit and added compatibility levels (the "auto-rollover" / higher headroom changes), but the architectural lesson stands: SCN is a finite, shared, monotonic resource, and DB-link topologies should be designed knowing that **the whole mesh inherits the SCN of its fastest-advancing member**.

#### Q45. [Theory] Compare Oracle's redo-based physical replication (Data Guard) with logical replication (GoldenGate / logical standby). What are the trade-offs and CDC mechanics?

**Physical standby (Data Guard)** ships the primary's **redo stream** and applies it block-for-block (Redo Apply / managed recovery), producing a byte-identical copy. Because it replays low-level block changes, it is the most efficient and lowest-overhead replication, guarantees an *exact* replica, and supports zero-data-loss modes (SYNC). Its constraints follow directly: the standby must be the **same Oracle version and platform-compatible**, it replicates the **entire database** (you can't pick tables), and until you open it Active Data Guard read-only it isn't queryable; you cannot have a different schema, extra indexes, or transformations on the standby.

```
 Physical (Data Guard):   primary redo ──► standby applies REDO blocks  (exact copy, whole DB)
 Logical (GoldenGate):    primary redo ──► EXTRACT mines redo → trail → REPLICAT applies SQL/DML
                          (table-level, cross-version, cross-platform, bidirectional, transform)
```

**Logical replication (GoldenGate, or a logical standby)** instead **mines the redo** (and supplemental log data) to reconstruct *logical change records* — the equivalent SQL DML — and applies those on the target. This decouples the target: it can be a **different version, different platform, even a non-Oracle database**, can replicate **selected tables/columns**, can **transform** data in flight, supports **bidirectional/active-active** topologies with conflict detection/resolution, and the target is **fully open read/write** the whole time. That flexibility is exactly why GoldenGate is the tool for **near-zero-downtime migrations and upgrades across major versions** (the base-question migration scenario). The costs: it requires **supplemental logging** on the source (extra redo to capture full before/after row images and key columns), it has higher overhead and operational complexity, it does **not** replicate everything by default (DDL replication and certain datatypes need explicit handling), and active-active introduces conflict-resolution design burden.

The decision framework: choose **physical Data Guard** when you want a faithful DR copy with minimal overhead, same version/platform, and possibly read offload (ADG). Choose **logical/GoldenGate** when you need cross-version/cross-platform replication, selective or transformed data, active-active or multi-master, or true zero-downtime upgrades with a long bidirectional validation window. Many large shops run **both**: Data Guard for local/synchronous DR and GoldenGate for the migration and for feeding downstream heterogeneous systems. The shared mechanic worth naming is that both are ultimately **CDC off the redo log** — the difference is whether you *apply the redo* (physical) or *interpret the redo into logical changes* (logical).

#### Q46. [Practical] You must change a column's datatype on a 500M-row table that is queried 24/7. Compare online redefinition, add-column-and-backfill, and an editioning-view approach.

A blocking `ALTER TABLE ... MODIFY` that physically rewrites 500M rows would hold locks and stall the workload for the duration, so the real engineering question is which **online** technique to use. **`DBMS_REDEFINITION`** is Oracle's purpose-built online reorg: it creates an interim table with the new structure, populates it, uses materialized-view-log-style change tracking to keep it in sync with ongoing DML, builds the dependent objects, then does a near-instant **final lock-and-swap** of the names. It handles the hard parts (concurrent DML during the copy, dependency rebuild) and works at the table or partition level, but it consumes roughly double the space during the operation and the column-mapping must be expressible in the redefinition's `COL_MAPPING`.

```sql
-- DBMS_REDEFINITION skeleton (column type change via interim table + col mapping)
BEGIN
  DBMS_REDEFINITION.CAN_REDEF_TABLE('APP','BIG_T');
  DBMS_REDEFINITION.START_REDEF_TABLE(
    'APP','BIG_T','BIG_T_INT',
    col_mapping => 'id id, CAST(amount AS NUMBER(18,2)) amount, status status');
  DBMS_REDEFINITION.COPY_TABLE_DEPENDENTS('APP','BIG_T','BIG_T_INT', ...);
  DBMS_REDEFINITION.SYNC_INTERIM_TABLE('APP','BIG_T','BIG_T_INT');
  DBMS_REDEFINITION.FINISH_REDEF_TABLE('APP','BIG_T','BIG_T_INT');  -- brief lock & swap
END;
/
```

The **add-column-and-backfill** pattern avoids any reorg: add a new nullable column of the target type (a fast metadata-only operation, especially with 11g+ where adding a `NOT NULL` column *with a default* is also metadata-only), backfill it in **bounded batches** (commit every N thousand rows to cap undo and avoid long row locks), keep the old and new columns in sync via a trigger during the transition, then cut the application over to the new column and drop the old one. It is fully controllable and low-risk, but it requires application coordination (two columns visible during the window) and careful batching to avoid `ORA-01555`/undo pressure. This is often the pragmatic choice when you can change the app.

The most elegant zero-downtime answer is **Edition-Based Redefinition (EBR)** with **editioning views**: the table's columns are hidden behind an editioning view, so you can add the new physical column, expose it through a *new edition's* editioning view (with cross-edition triggers transforming data between old and new columns), let old sessions keep using the old edition while new sessions use the new one, and retire the old edition once all sessions migrate. This delivers true *hot* rollout and rollback with no app-visible disruption, at the cost of significant up-front design (your schema must already be edition-enabled with editioning views and the team must understand cross-edition triggers). The decision: `DBMS_REDEFINITION` for a one-off structural change with minimal app changes; add-column-backfill when you control the app and want maximum control with minimal Oracle-feature dependency; EBR when continuous online deployability is a standing requirement worth the architectural investment.

#### Q47. [Theory] Explain how Oracle decides between nested loops, hash join, and sort-merge join, and how join order and "swap inputs" affect cost.

The three physical join methods have different cost shapes, and the optimizer chooses per join based on estimated cardinalities and available access paths. **Nested loops (NL)** iterate the outer (driving) row source and, for each row, probe the inner — ideally via an index. NL wins when the **outer is small and the inner is indexed**, because cost ≈ outer_rows × index_probe_cost; it returns first rows quickly (good for OLTP/pagination) but degrades to O(N×M)-ish when the outer is large or the inner lacks a usable index. **Hash join** builds an in-memory **hash table on the smaller (build) input**, then streams the larger (probe) input through it — cost is roughly one scan of each side, so it dominates for **large, unindexed equijoins** in DSS/analytics; its weakness is it requires an **equality** predicate, needs a PGA work area (spills to temp if the build side doesn't fit), and doesn't return a row until the build completes.

```
 NESTED LOOPS : for each outer row → indexed probe of inner   (small driver + indexed inner)
 HASH JOIN    : build hash on smaller side → probe with larger (big equijoins, needs PGA)
 SORT-MERGE   : sort both inputs on join key → merge          (presorted/range joins, no equality req.)
```

**Sort-merge join (SMJ)** sorts both inputs on the join key and merges them in a single coordinated pass. It is competitive with hash join for large sets when the inputs are **already sorted** (e.g., from an index full scan or a prior sort that the order can be reused), and it is the method of choice for **non-equi (range) joins** like `a.ts BETWEEN b.start AND b.end` where hash join cannot apply (no equality). If neither side is presorted, hash join usually beats SMJ because it avoids sorting both sides.

Two costing levers tie it together. **Join order** — which table drives — is enumerated by the optimizer (bounded heuristics for many tables) because the cheapest order depends on which intermediate results are smallest; getting the *driving* table wrong (often from a cardinality misestimate) is the classic cause of a plan that does a giant nested-loop. **Swap inputs** specifically applies to hash joins: the optimizer chooses which input is the *build* side, and it should pick the one estimated smaller — a misestimate that makes it build the hash table on the *larger* input wastes memory and may spill to temp. This is why the single highest-leverage fix for bad join plans is almost always **correcting the cardinality estimate** (stats/histograms/extended stats on correlated columns) so the optimizer sizes the inputs right; forcing `USE_NL`/`USE_HASH`/`LEADING` hints treats the symptom while baselines (Q17) lock a known-good join shape.

#### Q48. [Theory] What is the difference between conventional-path and direct-path operations (inserts, SQL*Loader, export/import), and what are the durability and space trade-offs?

A **conventional-path** insert goes through the normal buffer-cache machinery: it reuses existing free space in blocks below the high-water mark (honoring `PCTFREE`), it goes through the buffer cache, and it generates full **undo and redo**. This is correct for OLTP where many sessions concurrently insert small numbers of rows and need transactional rollback and minimal wasted space. A **direct-path** operation (`INSERT /*+ APPEND */`, `INSERT /*+ APPEND_VALUES */`, SQL*Loader direct path, `CREATE TABLE AS SELECT`, datapump) bypasses the buffer cache and **formats and writes new blocks directly above the high-water mark**, server-process to datafile. Because it doesn't search for free space or pass through the cache, it is dramatically faster for **bulk** loads.

```
 Conventional: rows → buffer cache → reuse free space (below HWM), full UNDO + REDO
 Direct-path : rows → formatted blocks → written ABOVE HWM, bypass cache
               (minimal UNDO; REDO can be skipped if NOLOGGING + appropriate mode)
```

The trade-offs are precise and frequently tested. Direct-path generates **minimal undo** for the data itself (the new extents are simply discarded on rollback), and if the object/tablespace is in **`NOLOGGING`** mode and the operation qualifies, it can also **skip most redo** for the data — turning a load that would otherwise be redo-bound into a near-IO-bound bulk write. But "no redo" means those blocks are **not recoverable from the redo stream**: after a NOLOGGING direct load you must take a **fresh backup** of the affected datafiles, or media recovery will mark the blocks corrupt (and on a Data Guard primary, NOLOGGING can leave the standby with `NOLOGGING` block corruption unless **`FORCE LOGGING`** is enabled — which is why DR-protected databases almost always set `FORCE LOGGING`, overriding NOLOGGING entirely).

There are further costs that make direct-path wrong for OLTP: a direct-path insert takes an **exclusive table lock** (only one direct-path writer at a time, though parallel DML coordinates slaves), and because it only writes **above the HWM** it does **not reuse** the free space left by prior deletes — repeated `APPEND` loads into a delete-heavy table can bloat the segment. So the rule is: direct-path + NOLOGGING for one-shot bulk loads/ETL into staging where you'll re-backup and don't need concurrency or rollback granularity; conventional path for concurrent transactional inserts where space reuse, redo protection, and row-level concurrency matter.

#### Q49. [Practical] Design the diagnosis of a `log file sync` wait spike. What sub-causes exist, and how do you distinguish them from `log file parallel write`?

`log file sync` is the wait a *foreground* session experiences while it waits for **LGWR to confirm its commit redo has been flushed**; `log file parallel write` is the wait **LGWR itself** experiences while doing the physical write to the redo log members. The first diagnostic split is comparing the two: if `log file sync` is high **and** `log file parallel write` is comparably high, the bottleneck is genuinely the **redo I/O** (slow storage, log members on contended/spinning disks, or undersized log buffer forcing frequent writes). If `log file sync` is high but `log file parallel write` is **low**, LGWR's writes are fast — the time is being lost elsewhere: **CPU starvation** (LGWR or the posted foreground can't get on a runnable CPU), **excessive commit frequency** (commit-per-row chatter so sessions queue behind each other), or **LGWR post/wait scheduling** overhead.

```
 commit → foreground posts LGWR → [foreground WAITS: log file sync] ──┐
                                                                       │
 LGWR: gather redo → [LGWR WAITS: log file parallel write] → write ────┘ → post foreground
        ▲ if this is low but sync is high → look at CPU / commit rate / scheduling, NOT disk
```

The methodical path: from the AWR/ASH for the window, read **`Avg wait (ms)` for both events** and the **commits-per-second** from the load profile. High avg `log file parallel write` (say >5–10 ms) points squarely at redo storage — remediate by moving redo to low-latency storage (flash/NVMe), separating redo from datafile I/O, increasing redo log member size to reduce log switches, and checking for `log file switch (checkpoint incomplete)` which signals undersized/too-few redo logs. A high *count* of `log file sync` with a *small* per-wait time and very high commits/sec is the **application** pattern: someone is committing inside a row-by-row loop — the fix is batching commits (tying back to Q42's group-commit economics) rather than touching storage at all.

Several second-order causes are worth naming for a senior answer. **RAC** adds `gc` overhead and broadcast-on-commit costs that inflate sync time. **Data Guard SYNC (Maximum Availability/Protection)** makes the commit wait for the *standby* to acknowledge redo receipt, so a network/standby slowdown shows up as `log file sync` on the **primary** even though local redo I/O is fine — distinguishable via the `Redo Transport` / `SYNC Remote Write` events. Finally, since 12c **LGWR can use multiple worker processes (scalable LGWR)**, so on high-core systems verify whether `LG0n` slaves are the relevant writers. The discipline is: never assume `log file sync` means "slow disk" — always corroborate with `log file parallel write`, the commit rate, and the DG/RAC topology before prescribing a fix.

#### Q50. [Theory] What is edition-based redefinition (EBR), and how do editioning views and cross-edition triggers enable patching an application with zero downtime?

**Edition-Based Redefinition (EBR)** lets you maintain **multiple versions of editionable objects** (PL/SQL packages, procedures, functions, triggers, views, synonyms) inside a single database at the same time, each version belonging to a named **edition**. Sessions choose an edition (via `ALTER SESSION SET EDITION` or a service default), and an object resolves to *its edition's version* — so you can install a complete new version of the application's code in a **child edition** while live sessions keep executing the **parent edition** unaffected, then flip new connections to the child and retire the parent. The core problem EBR solves is that tables are **not editionable** (data is shared by all editions), so changing the *schema* under running code is the hard part — and that's where the other two features come in.

```
 Parent edition (in use)        Child edition (new code installed)
    pkg v1, view v1                 pkg v2, view v2
            \                       /
             \   shared TABLE      /
              \   (one copy)      /
        EDITIONING VIEW projects table cols per edition;
        CROSS-EDITION TRIGGER keeps old & new columns in sync during rollout
```

An **editioning view** is a special view that selects directly from a single base table with no joins or expressions — it is essentially an *editionable alias* for the table's columns. Applications are written against the editioning view, **never the table directly**. Because the view is editionable, each edition can expose a *different projection* of the same physical table: the old edition's view shows the old column, the new edition's view shows a renamed/retyped/added column, all backed by one shared table. This is what makes a column change (Q46) hot-deployable — old and new code each see the shape they expect.

A **cross-edition trigger** bridges the data during the transition window when *both* editions are live. A **forward** cross-edition trigger fires on DML done by the old edition and transforms the data into the new column's format (so new-edition readers see correct values), and you can backfill existing rows the same way; a **reverse** crossedition trigger keeps the old column populated from new-edition writes so the still-running old code remains correct. Once every session has migrated to the new edition, you drop the cross-edition triggers and the old edition, completing a truly zero-downtime application patch. The trade-offs: EBR demands that the schema be **edition-enabled with editioning views from the start** and that developers understand the forward/reverse trigger discipline — it is heavyweight to adopt, but for systems where any downtime is unacceptable it is the only fully online application-upgrade mechanism Oracle offers.

#### Q51. [Theory] Compare Oracle's multitenant architecture (CDB/PDB) with the legacy non-CDB model. What is shared, what is isolated, and what does this change for upgrades and resource management?

Before 12c, every Oracle "database" was a **non-CDB**: a full set of background processes, an SGA, a data dictionary, and a `SYSTEM`/`SYSAUX`/undo set per database — so consolidating 50 applications meant 50 complete instances (heavy memory and process overhead) or cramming them into shared schemas (naming and isolation pain). **Multitenant** restructures this into one **container database (CDB)** that hosts many **pluggable databases (PDBs)**. The CDB has a single set of **background processes, one SGA, and the redo/undo/controlfiles shared across all PDBs**, plus a **root container (CDB$ROOT)** holding the Oracle-supplied dictionary and metadata, and a **seed (PDB$SEED)** template for fast PDB creation. Each PDB carries only its **own application data and its own private data dictionary**, behaving to an application exactly like a standalone database.

```
 CDB instance: ONE SGA + ONE set of background procs + shared redo/undo/control
   ├── CDB$ROOT   (Oracle metadata, common users/roles)
   ├── PDB$SEED   (template)
   ├── PDB: sales   (own dictionary + own data)   ◄── app connects via service
   ├── PDB: hr      (own dictionary + own data)
   └── PDB: billing (own dictionary + own data)
```

The shared-vs-isolated split is the crux. **Shared at the CDB level**: the instance memory and processes, the redo stream, the control files, the undo (by default; 12.2+ supports local undo per PDB), and **common users/roles** (prefixed `C##`) that exist across all containers. **Isolated per PDB**: the application schemas, the local data dictionary, local users, tablespaces, and the PDB's open/read-write state. This yields the headline operational wins: you **`UNPLUG`** a PDB from one CDB and **`PLUG`** it into another (or a higher-version CDB) to migrate or upgrade by simply moving it; **cloning** a PDB (including thin/snapshot clones) gives instant test copies; and **"upgrade by unplug/plug"** lets you patch the binaries once at the CDB and move PDBs onto the new version with minimal per-database work.

The consequences for the topics elsewhere in this guide: **resource management** gains a new dimension — the CDB Resource Manager arbitrates CPU/IO/memory *between PDBs* (shares and utilization limits) so one noisy PDB can't starve neighbors, on top of the intra-database consumer groups. **Backup/recovery and Data Guard** operate at the CDB level (the standby protects the whole CDB), though you can do PDB-level point-in-time recovery. And from 21c onward the **non-CDB architecture is desupported**, so multitenant isn't optional anymore — at minimum you run a CDB with a single PDB. The trade-off to acknowledge: shared undo/redo and a shared dictionary mean PDBs are **not fully isolated for every failure mode** (a CDB instance crash takes all PDBs down), which is why critical isolation still uses separate CDBs or Data Guard, while multitenant primarily optimizes **density, provisioning speed, and fleet patching**.

#### Q52. [Theory] Explain the lifecycle of a data block: PCTFREE, PCTUSED, row migration, row chaining, and how they hurt performance differently.

A block's free-space behavior is governed by two storage parameters (in manual segment-space management) or by the **ASSM (Automatic Segment Space Management)** bitmap (the modern default). **`PCTFREE`** reserves a percentage of each block as headroom for *future updates that grow existing rows* — so a `PCTFREE 20` block stops accepting new *inserts* once it is 80% full, keeping 20% in reserve. **`PCTUSED`** (only relevant under manual/freelist management) is the threshold below which a block becomes eligible to accept inserts again after deletes free space. The tuning intuition: high `PCTFREE` for tables whose rows grow a lot post-insert (lots of `UPDATE`s adding data), low `PCTFREE` for insert-mostly, never-updated tables to maximize packing.

```
 PCTFREE 20:  insert until block 80% full → reserve 20% for row growth
   row UPDATE grows the row, fits in reserved space → fine
   row UPDATE grows the row, NO room in block → ROW MIGRATION:
      ┌ original block: stub/forwarding ROWID ─┐
      └──────────────────────────────────────► row moved to a new block
```

**Row migration** happens when an `UPDATE` enlarges a row beyond the free space in its current block: Oracle moves the *entire row* to a new block and leaves a **forwarding pointer (stub)** in the original block, because the row's ROWID must not change (indexes point to the original ROWID). The performance cost is that an **index** access still lands on the original block, reads the stub, then does an *extra* I/O to follow the pointer to the real row — so heavily-migrated tables show inflated `table fetch continued row` and double the logical reads on index lookups. The fix is raising `PCTFREE` and reorganizing (move/redef) to eliminate the migrations.

**Row chaining** is different: the row is simply **too large to fit in a single block** (e.g., a row wider than the block size, or a row with 255+ columns which Oracle splits into row pieces) so it spans multiple blocks by design and *cannot* be fixed by `PCTFREE` — the only remedies are a larger block size (a separate tablespace with bigger `db_block_size`), restructuring the table, or moving large columns to LOBs. The interview distinction: migration is an *avoidable* side effect of updates against insufficient `PCTFREE` (fixable by reorg), while chaining is an *intrinsic* size problem (fixable only by changing block size or row design). Both manifest as extra block reads, but you diagnose them via `ANALYZE TABLE ... LIST CHAINED ROWS` and the `chain_cnt`/`table fetch continued row` statistic.

#### Q53. [Theory] What is the difference between a database trigger's `:NEW`/`:OLD`, statement vs row triggers, `BEFORE` vs `AFTER`, and the dreaded mutating-table error?

Triggers fire on DML and come in two granularities. A **statement-level trigger** fires **once per triggering statement** regardless of how many rows it affects (even zero) — useful for auditing "an update happened" or enforcing a table-wide rule. A **row-level trigger** (`FOR EACH ROW`) fires **once per affected row** and gives you the **`:OLD`** and **`:NEW`** pseudorecords: `:OLD` holds the pre-change values (null for inserts), `:NEW` holds the post-change values (null for deletes), and in a `BEFORE ... FOR EACH ROW` trigger you may **assign to `:NEW`** to modify the values about to be written (the standard place to default/normalize/derive columns). In an `AFTER` row trigger the row is already written, so `:NEW` is read-only.

```
 Firing order for one row of an UPDATE:
   BEFORE statement → BEFORE row (:OLD/:NEW, can modify :NEW) →
     [row written] → AFTER row (:OLD/:NEW read-only) → AFTER statement
```

The timing choice (`BEFORE` vs `AFTER`) is semantic: use **`BEFORE` row** to validate or transform values before they hit the table; use **`AFTER` row** when you need the row to already exist (e.g., to reference a generated identity value, or to enqueue downstream work knowing the change is in place). The compound-trigger form (11g+) bundles all four timing points into one trigger body with a shared state section, which is both cleaner and the key tool for the next problem.

The **mutating-table error (`ORA-04091`)** occurs when a **row-level trigger tries to read or modify the very table that is currently being changed** by the triggering statement — Oracle forbids it because the table is in an inconsistent, mid-statement state and the result would depend on processing order, breaking read consistency. The classic trap is a row trigger that runs `SELECT COUNT(*) FROM the_same_table` to enforce a cardinality rule. The correct solutions are: move the logic to a **statement-level** trigger (which fires after the table is consistent), use a **compound trigger** to collect affected keys in the row section and act on them in the after-statement section, or — better still — enforce the rule with a **declarative constraint** where possible, since constraints are set-based, correct under concurrency, and far cheaper than trigger logic. Reaching for an autonomous transaction to dodge `ORA-04091` is an anti-pattern: it sidesteps the error but reads a *committed* snapshot that ignores the in-flight statement, producing wrong results.

#### Q54. [Theory] Explain how Oracle stores and indexes LOBs (BasicFiles vs SecureFiles), and why `LONG`, `VARCHAR2(32k)`, and CLOB are not interchangeable.

Oracle's large-object types (`CLOB`, `BLOB`, `NCLOB`) store the locator **inline in the row** (a pointer plus, optionally, small values stored in-row up to ~4000 bytes) while the bulk of the data lives in a **separate LOB segment** with its own **LOB index** that maps logical offsets to physical chunks. This separation is why scanning a table doesn't pay for LOB data you don't select, and why LOB chunk size, caching (`CACHE`/`NOCACHE`), and logging are tunable independently of the table. There are two storage implementations: **BasicFiles** (the legacy format) and **SecureFiles** (11g+, now the default and recommended), which adds **deduplication** (identical LOBs stored once), **compression**, **transparent encryption** (integrating with TDE), and far better concurrency and space management via an in-segment space bitmap rather than the old freelist/LOB-index contention. On any modern system you should be using SecureFiles; BasicFiles is effectively deprecated.

```
 Row: [ ... col, col, LOB locator ──────────► LOB SEGMENT (chunks) ]
                                              indexed by LOB INDEX (offset → chunk)
   SecureFiles adds: dedup, compress, encrypt, better space mgmt + concurrency
   in-row option: small LOB (<~4000 B) can live inside the row block
```

The legacy **`LONG`/`LONG RAW`** types predate LOBs and are riddled with restrictions: at most **one `LONG` column per table**, you cannot use it in `WHERE`, `GROUP BY`, most functions, or many SQL contexts, and they are painful to fetch programmatically. Oracle has urged migration to `CLOB`/`BLOB` for two decades; `LONG` survives mainly in legacy data-dictionary columns. So `LONG` and `CLOB` are *not* interchangeable — `CLOB` removes essentially all those limits and supports up to terabytes, piecewise read/write via `DBMS_LOB`, and the SecureFiles features above.

The `VARCHAR2(32767)` ("extended data types," 12c+, enabled by `MAX_STRING_SIZE=EXTENDED`) blurs the line but is **not** a CLOB: although it lets a `VARCHAR2` exceed the old 4000-byte limit up to 32 KB, values larger than ~4000 bytes are **transparently stored as an out-of-line LOB under the hood** — so you get LOB-like storage with `VARCHAR2` syntax, but it caps at 32 KB and enabling `EXTENDED` is a one-way, instance-wide change that requires running `utl32k.sql` and cannot be reverted. The decision: use plain `VARCHAR2` up to 4000, extended `VARCHAR2(32k)` for "a bit bigger than 4000 but bounded" text, and a true `CLOB`/`BLOB` (SecureFiles) for genuinely large or unbounded content. Choosing wrong means either hitting a hard size ceiling (extended VARCHAR2) or paying LOB overhead for small values (needless CLOB).

#### Q55. [Theory] What are extended statistics (column groups and expression statistics), and what optimizer error do they fix that plain column stats cannot?

Plain per-column statistics carry an implicit, dangerous assumption: that predicates on different columns are **independent**, so the optimizer estimates the combined selectivity of `WHERE col_a = :a AND col_b = :b` by **multiplying** the individual selectivities. When the columns are actually **correlated**, this multiplication wildly *under*estimates the row count. The textbook example is `WHERE country = 'USA' AND state = 'CA'` — `state` is functionally dependent on `country`, so multiplying their selectivities (as if a CA-but-not-USA row were possible) yields an absurdly low cardinality, which drives the optimizer to a nested-loop/index plan when a hash join/full scan was right. No amount of single-column histogram accuracy fixes this, because the error is in the *combination*, not either column alone.

```sql
-- Column GROUP extended stats: teach the optimizer that (country,state) correlate
SELECT DBMS_STATS.CREATE_EXTENDED_STATS('SALES_OWNER','CUSTOMERS','(country,state)')
FROM dual;
-- then re-gather; or let it happen automatically:
BEGIN
  DBMS_STATS.GATHER_TABLE_STATS('SALES_OWNER','CUSTOMERS',
     method_opt => 'FOR ALL COLUMNS SIZE AUTO FOR COLUMNS (country,state)');
END;
/
```

**Column-group statistics** (extended stats on a set of columns) fix this by storing the **real number of distinct value-combinations** for the group, so the optimizer uses the measured combined NDV instead of multiplying. Oracle can even discover useful column groups for you via `DBMS_STATS.SEED_COL_USAGE` + `REPORT_COL_USAGE`, which watches the workload and recommends groups that actually appear together in predicates. The second flavor, **expression statistics**, addresses the mirror problem: a predicate like `WHERE UPPER(last_name) = :n` or `WHERE TRUNC(order_date) = :d` wraps a column in a function, and the optimizer has **no statistics on the function's result**, so it falls back to a fixed guess (e.g., 1% selectivity). Creating extended stats on the expression (`('(UPPER(last_name))')`) — or a function-based index, which implicitly creates them — gives the optimizer real NDV/histogram data for the expression's output.

The trade-offs to flag: extended stats add gather cost and dictionary objects, they only help predicates that *match* the group/expression exactly, and column groups don't help `OR`/range predicates the way they help conjunctive equalities. But for the very common "two correlated columns" and "function on a column" cardinality misestimates — which are a leading hidden cause of the bad-plan incidents in Q7/Q43 — extended statistics are the *correct, optimizer-native* fix, vastly preferable to hinting because they let the CBO keep adapting as data changes.

#### Q56. [Practical] How does parallel execution work in Oracle — the QC/PX-server model, granules, the producer/consumer table queue, and when does parallelism backfire?

Parallel execution decomposes one SQL statement across many processes. The session that issued the statement becomes the **Query Coordinator (QC)**; it recruits **PX server processes** (slaves) from a shared pool (`parallel_max_servers`), hands out work, and assembles the final result. Work is divided into **granules** — usually **block-range granules** (contiguous block ranges of the object) so even an unpartitioned table parallelizes evenly, or **partition granules** when a partition-wise operation maps one slave set per partition. Each granule is processed independently, which is what delivers near-linear speedup on large scans/joins/sorts *when* the system has spare CPU and I/O bandwidth.

```
        Query Coordinator (your session)
              ┌──────────┴──────────┐
        PRODUCER set (scan/filter)   ── distributes rows via ──►  TABLE QUEUE
              (PX servers, DOP=N)         (hash/range/broadcast)        │
                                                                         ▼
                                                        CONSUMER set (join/sort/aggregate)
              └──────────────────────► results back to QC ◄────────────┘
```

Many parallel plans use **two slave sets** in a **producer/consumer** pattern connected by a **table queue (TQ)**: one set scans and filters rows (producers) and *redistributes* them to the other set (consumers) that performs the join or aggregation. The redistribution method — **HASH** (for hash joins/group-by, spreading rows by a hash of the key), **BROADCAST** (send the small side to every consumer, ideal when one input is tiny), or **RANGE** (for parallel sort) — is chosen by the optimizer and shown as `PX SEND HASH`/`PX SEND BROADCAST` in the plan. The **Degree of Parallelism (DOP)** sets how many slaves per set; note that a two-set plan actually consumes **2×DOP** processes, a common surprise that exhausts `parallel_max_servers`.

Parallelism backfires in several well-known ways an interviewer wants you to name. **OLTP point queries**: the overhead of recruiting slaves, distributing granules, and reassembling results dwarfs the work for a sub-second indexed lookup — parallel query is for *throughput on big data*, not *latency on small data*. **Concurrency**: many users each requesting high DOP rapidly exhausts the PX pool; statements then either **downgrade** to lower DOP or serial (silent performance variance) or queue — which is why `parallel_degree_policy=AUTO` with **statement queuing** exists to throttle rather than thrash. **Skew**: if the redistribution key is skewed (one hash bucket gets most rows), one consumer slave does almost all the work while the rest idle — the parallel equivalent of a hotspot, visible as one PX server burning CPU while others sit in `PX Deq` waits. And **direct-path interactions**: parallel DML takes table-level locks (Q48) and disables some concurrency. The discipline is to use parallelism deliberately (DSS/ETL/maintenance, large scans), size DOP to available CPU/IO, and rely on Auto DOP + statement queuing on shared systems rather than hard-coding aggressive `PARALLEL` hints everywhere.

#### Q57. [Theory] Compare the optimizer's handling of `UNION` vs `UNION ALL`, `EXISTS`/`IN`/`INTERSECT` for semi-joins, and `MINUS`/`NOT EXISTS` for anti-joins — including the NULL and duplicate semantics.

These set operations look similar but differ in both **duplicate handling** and the **work the optimizer must do**. `UNION ALL` simply concatenates the two result sets — no deduplication, so it is **cheap** (no sort/hash to remove duplicates) and preserves duplicates. `UNION` additionally **removes duplicate rows across the combined result**, which forces a **sort-unique or hash-unique** operation over everything — materially more expensive on large inputs. The single most common gratuitous-cost mistake in SQL is writing `UNION` when the branches are already disjoint (e.g., partitioned by a mutually-exclusive `WHERE`): you pay for a global dedup that can never remove anything. **Rule: default to `UNION ALL` and only use `UNION` when you genuinely need cross-branch deduplication.**

```sql
-- Cheap: branches are disjoint, no dedup needed
SELECT id FROM a WHERE region='EU'
UNION ALL
SELECT id FROM a WHERE region='US';     -- UNION here would pay for a pointless sort-unique

-- Semi-join (return left rows that HAVE a match, no duplication of left)
SELECT c.* FROM customers c WHERE EXISTS (SELECT 1 FROM orders o WHERE o.cid=c.id);
```

For **semi-join** semantics (return rows from the left that *have at least one* match on the right, without multiplying the left rows), `EXISTS` and `IN` both unnest to a **semi-join** on modern Oracle and typically produce identical plans (see Q34). `INTERSECT` is related but stricter: it returns **distinct rows present in both** result sets and, like `UNION`, performs a dedup; it compares *whole rows* rather than expressing a correlated predicate, so it's not a drop-in for `EXISTS`. The crucial NULL nuance: `IN`/`EXISTS` semi-joins are generally safe, but `NOT IN` is the null-trap from Q25 because a single NULL on the right makes the whole anti-predicate UNKNOWN.

For **anti-join** semantics (return left rows with *no* match on the right), the safe, optimizer-friendly forms are `NOT EXISTS` (which unnests to a true **anti-join** and is **null-safe**) and `MINUS` (returns **distinct** rows in the first set not in the second, again with a dedup and whole-row comparison). `MINUS` differs from `NOT EXISTS` in three ways worth stating: it **deduplicates** its output, it compares the **entire projected row** (so `NULL` values are treated as *matching* `NULL` for set comparison, unlike `=`), and it requires the two branches to be **union-compatible** (same column count/types). So `NOT EXISTS` is the right tool for "rows in A lacking a related row in B by key," while `MINUS` is right for "distinct rows of A that are not, value-for-value, in B." Picking the wrong one yields either accidental deduplication (`MINUS` collapsing legitimate duplicates) or the `NOT IN` null bug — both classic correctness defects that an interviewer probes by asking how each treats duplicates and NULLs.

## 🧩 Extended Questions — Set 2: Practical, Operational & Troubleshooting

### 🟢 Basic — extended

#### Q58. [Practical] How do you check what a session is currently waiting on and kill a runaway session safely?

The first move during a "the database is hung" call is to look at active sessions, not to restart anything. `V$SESSION` joined with the session that is blocking gives you the live picture: who is active, what event they are waiting on, what SQL they are running, and crucially the `BLOCKING_SESSION` column that points at the holder of the lock. Resist the urge to log into the OS and `kill -9` an Oracle process — killing the OS process directly can leave the session in a `KILLED`/`MARKED FOR KILL` limbo and force PMON to do cleanup; the supported path is `ALTER SYSTEM KILL SESSION` (which rolls back the transaction and releases locks cleanly) or `DISCONNECT SESSION ... POST_TRANSACTION` if you want to let the current transaction finish first.

```sql
-- Who is active and what are they waiting on / blocked by
SELECT sid, serial#, username, status, event, blocking_session,
       sql_id, last_call_et
FROM   v$session
WHERE  status = 'ACTIVE' AND username IS NOT NULL
ORDER BY blocking_session NULLS FIRST, last_call_et DESC;

-- Kill cleanly (rolls back, releases locks). IMMEDIATE returns control at once.
ALTER SYSTEM KILL SESSION '152,40327' IMMEDIATE;
```

The subtle part is what happens *after* the kill. A session running a huge uncommitted DML must **roll back**, and that rollback can take as long as (or longer than) the work it is undoing — `V$TRANSACTION.USED_UREC` shrinking tells you it is progressing. So killing a 40-minute batch update does not instantly free things; you may watch a long `transaction recovery` afterwards. The interview signal is that you reach for `V$SESSION`/`V$LOCK` before any disruptive action, you prefer the database's own kill mechanism over OS kills, and you understand that "killed" means "rolling back," not "gone."

#### Q59. [Practical] A user reports "ORA-01017: invalid username/password" intermittently while the app works most of the time. How do you triage?

Intermittent authentication failures almost never mean the password is "sometimes wrong" — they point at something stateful or distributed. The first hypotheses: the app uses a **connection pool** and one bad credential snuck into the pool (some connections fail, others succeed), the account is **expiring** under a profile (`PASSWORD_LIFE_TIME`) so it works until the grace period lapses, or the account is getting **locked** by `FAILED_LOGIN_ATTEMPTS` after a few bad tries from a misconfigured node and then auto-unlocks. In a multi-node RAC or multi-instance setup, one node may have a stale credential or a different `sqlnet.ora`/wallet, so failures correlate with which instance the listener routed you to.

```sql
-- Check the account's profile limits and current state
SELECT username, account_status, lock_date, expiry_date, profile
FROM   dba_users WHERE username = 'APPUSER';

-- What limits does that profile impose?
SELECT resource_name, limit
FROM   dba_profiles
WHERE  profile = (SELECT profile FROM dba_users WHERE username='APPUSER')
  AND  resource_name IN ('FAILED_LOGIN_ATTEMPTS','PASSWORD_LIFE_TIME','PASSWORD_LOCK_TIME');
```

I also check the **audit trail / listener log** to see *which* client and *which* instance the failures originate from, and whether they cluster in time (a batch job firing with a stale secret) or by source IP (one app server out of ten). A frequent real cause is **case-sensitive passwords** (`SEC_CASE_SENSITIVE_LOGON`, default TRUE since 11g) combined with an old client or a config file storing the password in the wrong case. The discipline: ORA-01017 that is *intermittent* is an operational/state problem (pool, expiry, lock, one bad node), not a "retype the password" problem — gather the *who/when/where* before changing the credential.

#### Q60. [Practical] What is the difference between `COMMIT` and `ROLLBACK` frequency, and why is "commit in a loop" or "never commit" both wrong?

Commit frequency is a tuning dial with a bad answer at each extreme. **Committing every row** (commit inside a row-by-row loop) maximizes `log file sync` round trips: each commit forces LGWR to flush redo and the foreground to wait, so a million-row load becomes a million tiny synchronous waits — even with group commit softening it, you have thrown away throughput for nothing. It also defeats restartability illusions people expect, because each row is independently durable. **Never committing** (one giant transaction over millions of rows) is the opposite failure: you hold locks for the whole duration (blocking others), you accumulate enormous **undo** that must be retained for the life of the transaction (risking `ORA-30036` "unable to extend undo" and inflating `ORA-01555` risk for concurrent readers), and a failure near the end forces a long rollback.

```sql
-- Anti-pattern: commit per row (chatty log file sync)
FOR r IN (SELECT * FROM staging) LOOP
  INSERT INTO target VALUES (...);
  COMMIT;                       -- DON'T
END LOOP;

-- Better: batch with a bounded fetch and periodic commit
DECLARE
  CURSOR c IS SELECT * FROM staging;
  TYPE t IS TABLE OF staging%ROWTYPE; rows t;
BEGIN
  OPEN c;
  LOOP
    FETCH c BULK COLLECT INTO rows LIMIT 10000;
    EXIT WHEN rows.COUNT = 0;
    FORALL i IN 1..rows.COUNT INSERT INTO target VALUES rows(i);
    COMMIT;                     -- commit per batch, not per row
  END LOOP;
  CLOSE c;
END;
/
```

The right pattern is **batched commits** — commit every few thousand rows in a `BULK COLLECT ... LIMIT`/`FORALL` loop. This amortizes `log file sync` over many rows while keeping undo bounded and lock duration short. The interview point is that there is no "always commit more" or "always commit less" rule; you commit at a batch granularity chosen to balance redo/log-sync overhead (favors fewer commits) against undo retention, lock duration, and concurrent-reader `ORA-01555` exposure (favors more frequent commits).

#### Q61. [Practical] How do you read an execution plan in production, and what is the difference between EXPLAIN PLAN and the actual runtime plan?

`EXPLAIN PLAN` (or autotrace's explain) shows the plan the optimizer *would* pick, computed **without binding the real values and without running the statement** — so it can differ from reality because of bind peeking, adaptive plans, and the simple fact that it never executes, meaning you only see *estimated* rows. The far more useful tool in an incident is `DBMS_XPLAN.DISPLAY_CURSOR` against the cursor that *actually ran*, with `format => 'ALLSTATS LAST'`, which shows **E-Rows (estimated)** beside **A-Rows (actual)** per operation. A large divergence between E-Rows and A-Rows is the single most diagnostic signal: it pinpoints exactly where the optimizer misestimated cardinality and therefore why it chose a bad join method or order.

```sql
-- Run the statement (with STATISTICS_LEVEL=ALL or the GATHER_PLAN_STATISTICS hint),
SELECT /*+ GATHER_PLAN_STATISTICS */ ... ;

-- then read the REAL plan with estimated-vs-actual rows
SELECT * FROM TABLE(
  DBMS_XPLAN.DISPLAY_CURSOR(format => 'ALLSTATS LAST'));
```

Reading the plan itself: it is a tree, and you read **innermost/most-indented first**, working outward — the deepest child runs first and feeds its parent. Watch for `TABLE ACCESS FULL` on a large table where you expected an index, a `NESTED LOOPS` driving millions of iterations (a cardinality misestimate making the optimizer think the driver was tiny), a `FILTER` operation with a high `Starts` count (failed subquery unnesting, Q34), and `Buffers`/`A-Time` columns to see where the actual time and logical reads concentrate.

The operational rule: never tune from `EXPLAIN PLAN` alone in production — it lies about which plan really ran and gives you no actuals. Pull the live cursor with `DISPLAY_CURSOR(..., 'ALLSTATS LAST')`, or for an already-finished SQL pull it from AWR with `DBMS_XPLAN.DISPLAY_AWR(sql_id)`. The E-Rows/A-Rows comparison tells you whether the problem is a *statistics/cardinality* issue (fix stats, add histograms/extended stats) or a genuinely good estimate that simply needs a different access structure.

### 🟡 Intermediate — extended

#### Q62. [Practical] Walk through diagnosing and fixing an "ORA-01555: snapshot too old" that hits a long-running report every night.

ORA-01555 means a query needed a *read-consistent* version of a block (as of the query's start SCN) but the **undo required to reconstruct it had already been overwritten** by other transactions. The classic shape is exactly the one described: a long-running report (an hour-long aggregation) reading a table that other sessions are actively modifying and committing. As those writers commit and undo gets recycled, the report eventually hits a block whose old image is gone, and Oracle cannot honor read consistency, so it fails. It is fundamentally a *retention vs duration* mismatch, not a corruption.

```sql
-- Is undo retention shorter than the longest query? Check tuned retention & errors.
SELECT to_char(begin_time,'HH24:MI') t, tuned_undoretention,
       maxquerylen, ssolderrcnt          -- snapshot-too-old error count
FROM   v$undostat ORDER BY begin_time;

-- Guarantee retention so undo is NOT overwritten before this window elapses
ALTER SYSTEM SET undo_retention = 7200;        -- seconds (= 2h)
-- and to make it a hard guarantee, set the undo tablespace RETENTION GUARANTEE:
ALTER TABLESPACE undotbs1 RETENTION GUARANTEE;
```

The fixes, in order: (1) ensure `UNDO_RETENTION` comfortably exceeds the report's runtime *and* the undo tablespace is large enough to actually keep that much undo — `UNDO_RETENTION` is only a target unless the tablespace is sized for it or you set `RETENTION GUARANTEE` (which makes it hard, at the risk of DML failing with `ORA-30036` when undo can't be reused). (2) Reduce the *need* for so much consistent-read reconstruction by **not running the long report against the hot OLTP table during peak writes** — schedule it off-peak, or read from a **read-only standby (Active Data Guard)** or a **materialized view** so the report and the OLTP writers don't contend on undo. (3) For PL/SQL doing "fetch across commit" (the legacy anti-pattern of committing inside an open cursor loop), the cursor's own commits recycle the undo it later needs — the fix is to restructure so you don't commit inside the driving cursor.

The senior nuance: blindly cranking `UNDO_RETENTION` to a huge value is not free — it forces undo to be kept, growing the undo tablespace and potentially causing `ORA-30036` for writers. The architecturally correct answer for a heavy reporting workload is to **separate reporting from OLTP** (standby/MV/replica) rather than to keep widening the undo window until the OLTP side starts failing.

#### Q63. [Practical] You get "ORA-01652: unable to extend temp segment." What is actually happening and how do you resolve it without just adding space?

`ORA-01652` means an operation needed more space in the **temporary tablespace** (or in a permanent tablespace for a sort/hash/temp segment) than was available. In the common case it is the **TEMP tablespace** filling because a query's sort or hash join **spilled to disk** — its work area exceeded `PGA_AGGREGATE_TARGET`'s allotment so it went one-pass/multi-pass onto temp, and either the query is huge or several large queries hit temp at once and collectively exhausted it. Adding a datafile to TEMP makes the symptom disappear, but the right first question is *why is so much going to temp* — frequently it is a bad plan (a Cartesian join from a missing predicate, or a cardinality misestimate causing a giant hash build) generating absurd intermediate volumes.

```sql
-- Who is consuming temp right now, and via which SQL?
SELECT s.sid, s.username, s.sql_id, u.tablespace,
       u.blocks * (SELECT block_size FROM dba_tablespaces WHERE tablespace_name=u.tablespace)
         / 1024/1024 AS mb_used
FROM   v$session s
JOIN   v$tempseg_usage u ON u.session_addr = s.saddr
ORDER BY mb_used DESC;
```

So the resolution path is: (1) identify the SQL via `V$TEMPSEG_USAGE`/`V$SQL_WORKAREA`, (2) check whether the plan is *reasonable* — a single query eating hundreds of GB of temp is usually a plan defect (missing join predicate → Cartesian, or `E-Rows` vastly under `A-Rows` driving a monster hash) that you fix with stats/extended stats/a corrected query, not more disk, (3) if the workload is legitimately large (a real DSS aggregation), then increase `PGA_AGGREGATE_TARGET` so more of it stays in memory and only the genuine overflow uses temp, and (4) size TEMP for the real concurrent peak. The interview signal is refusing to reflexively `ADD TEMPFILE` and instead asking whether the temp consumption is *justified by the data* or is a symptom of a defective plan — and knowing that temp pressure ties directly back to PGA work-area sizing.

#### Q64. [Practical] Explain how RMAN backup, the fast recovery area (FRA), and "ORA-00257: archiver error, connect internal only" relate in a production incident.

`ORA-00257` ("archiver error") in production almost always means the database is in **ARCHIVELOG mode**, the archiver (ARCn) needs to copy a filled online redo log to the archive destination, and **it cannot** — usually because the **Fast Recovery Area is full**. When the archiver can't archive, Oracle will not overwrite the online redo log it needs to keep, so once all online logs are full and unarchived, the database **stops accepting new transactions** (it freezes to avoid losing redo). That is why a backup misconfiguration manifests as a total outage: it is the durability mechanism protecting itself.

```sql
-- Is the FRA full? (the usual root cause)
SELECT name, space_limit/1024/1024/1024 gb_limit,
       space_used/1024/1024/1024 gb_used,
       (space_used/space_limit)*100 pct_used
FROM   v$recovery_file_dest;

-- What is filling it? (archived logs that were never backed up & purged)
SELECT file_type, percent_space_used, number_of_files
FROM   v$flash_recovery_area_usage ORDER BY percent_space_used DESC;
```

The emergency mitigation is to make room so the archiver can proceed: either **increase `DB_RECOVERY_FILE_DEST_SIZE`** (immediate, if the filesystem has room) or **archive/back up and delete obsolete archived logs** with RMAN (`BACKUP ARCHIVELOG ALL DELETE INPUT`, then `DELETE OBSOLETE`/`DELETE EXPIRED` per your retention policy). What you must *not* do is delete archived redo logs at the OS level — RMAN's catalog/controlfile then thinks they still exist, breaking recoverability and leaving `V$ARCHIVED_LOG` inconsistent.

The root-cause fix is the actual lesson: the FRA filled because **archived logs accumulated faster than the backup job purged them** — the backup job failed silently, the retention/`DELETE INPUT` step was missing, or redo generation spiked (a big batch, a NOLOGGING-disabled bulk load) and outran the schedule. So after restoring service you (1) confirm RMAN backups are actually running and succeeding, (2) ensure the retention policy plus `DELETE` steps reclaim archived logs, and (3) size the FRA for the real redo-generation rate plus the backup window. Treating ORA-00257 as "delete some files" without fixing the backup pipeline guarantees a repeat outage.

#### Q65. [Practical] How do you migrate a schema between databases? Compare Data Pump (expdp/impdp) with the legacy exp/imp and with transportable tablespaces.

For most schema-level migrations the tool is **Data Pump (`expdp`/`impdp`)**, the server-side successor to the old client-side `exp`/`imp`. Data Pump runs as server processes writing to a **directory object** on the database server (not the client), supports **parallelism** (`PARALLEL=n`), can run over a **network link** (`NETWORK_LINK`, no intermediate dump file at all), supports fine-grained filtering (`INCLUDE`/`EXCLUDE`, `QUERY`, `REMAP_SCHEMA`, `REMAP_TABLESPACE`), and can **transform** objects on import (e.g., remap a schema name or strip storage clauses). The legacy `exp`/`imp` is client-side, single-threaded, far slower, and is desupported for most uses — you reach for it only against very old databases that Data Pump can't read.

```bash
# Export one schema with parallelism to a server-side directory object
expdp app/pwd@PROD schemas=APP directory=DP_DIR \
      dumpfile=app_%U.dmp parallel=4 logfile=exp_app.log

# Import into another DB, remapping schema and tablespace
impdp app/pwd@TEST directory=DP_DIR dumpfile=app_%U.dmp parallel=4 \
      remap_schema=APP:APP_TEST remap_tablespace=USERS:APP_DATA \
      logfile=imp_app.log

# Or move directly over a DB link with no dump file at all:
impdp app/pwd@TEST network_link=PROD_DBLINK schemas=APP \
      remap_schema=APP:APP_TEST
```

For *very large* datasets where copying every row is too slow, **transportable tablespaces (TTS)** is the heavy-duty option: you make the tablespaces read-only, copy the **datafiles** physically (so no row-by-row insert), and import only the **metadata** with Data Pump — effectively moving terabytes at file-copy speed. The constraints are that the tablespace set must be **self-contained** (no objects referencing segments outside the set), endianness must match or be converted with RMAN `CONVERT`, and (in older releases) block size/character-set compatibility rules apply; **full transportable export/import** (12c+) extends this to move an entire database's user tablespaces in one operation, a favorite for non-CDB-to-PDB consolidation.

The decision framework: **Data Pump** for routine schema/table moves, refactoring (schema/tablespace remap), and cross-version migrations where you want logical flexibility; **Data Pump over `NETWORK_LINK`** when you want to avoid staging a dump file; **transportable / full transportable tablespaces** when the data volume makes logical row-by-row import too slow and you can tolerate the read-only/self-contained constraints. None of these are zero-downtime — for that you reach for GoldenGate or Data Guard (Q19/Q45); Data Pump implies an outage window for the objects being moved.

#### Q66. [Practical] Your `DBMS_STATS` auto-job runs nightly, yet a key table still gets bad plans. What are the likely causes and fixes?

The auto stats job (`auto optimizer stats collection`, part of the autotask framework) only gathers stats on objects it considers **stale** — meaning roughly **>10% of rows changed** since the last gather, as tracked in `DBA_TAB_MODIFICATIONS`. So the first likely cause is that the table is large and the daily change is *under* the staleness threshold, leaving stats representative of last month's data even though the *distribution* shifted (e.g., a new hot `status` value appeared). A second common cause is that **stats are locked** on the table (someone ran `DBMS_STATS.LOCK_TABLE_STATS` to "freeze a good plan") so the auto job silently skips it. A third is **timing**: the auto job runs in a maintenance window that may end before reaching your table, or it runs *before* the nightly ETL that loads the data, so it gathers stats on an empty/partial table.

```sql
-- Are stats stale, locked, and how fresh?
SELECT table_name, num_rows, last_analyzed, stale_stats, stattype_locked
FROM   dba_tab_statistics
WHERE  owner='APP' AND table_name='BIG_FACT';

-- Has the table changed more than the staleness threshold since last gather?
SELECT table_name, inserts, updates, deletes, truncated, timestamp
FROM   dba_tab_modifications WHERE table_owner='APP' AND table_name='BIG_FACT';
```

The fixes map to the cause. If staleness threshold is the issue, **gather explicitly** for that table on its own schedule right after the ETL, or lower the threshold for that table with `DBMS_STATS.SET_TABLE_PREFS(..., 'STALE_PERCENT', '5')`. If a *transient* skew is the problem (intra-day the data looks nothing like the nightly snapshot — e.g., a queue table that fills and drains), the right answer can be to **lock representative stats** or **set them manually** with `DBMS_STATS.SET_TABLE_STATS`/`SET_COLUMN_STATS` so the optimizer always sees a sensible picture rather than chasing a moving target. If a missing histogram on a newly-skewed column is the culprit, fix `METHOD_OPT` preferences (Q40). And if the table is loaded by a bulk job, **gather stats as the last step of the ETL** (or use `DBMS_STATS` "online" stats gathered automatically during a direct-path CTAS/insert in 12c+) rather than waiting for the autotask.

The deeper operational point is that "the auto job runs" does not mean "every table has good stats." You verify *per object* with `DBA_TAB_STATISTICS` (`last_analyzed`, `stale_stats`, `stattype_locked`), you align stats gathering with the **data lifecycle** (gather after loads, not on a fixed clock that races the ETL), and for volatile tables you may deliberately fix stats rather than re-deriving them nightly. Pending stats (`PUBLISH=FALSE`, Q40) let you validate a new gather against a test workload before exposing it, which is the safe way to change stats on a table that keeps regressing.

#### Q67. [Coding] Write a query to find the current blocking-session tree (who is blocking whom) and the SQL involved.

**Problem:** During a lock-contention incident you need to see the full chain — root blockers at the top, the sessions waiting beneath them, and the SQL each is running — so you can decide which one session to kill to free the most waiters.

```sql
-- Blocking tree using the built-in V$SESSION.BLOCKING_SESSION
SELECT LPAD(' ', LEVEL*2) || s.sid               AS session_tree,
       s.serial#,
       s.username,
       s.status,
       s.event,
       s.sql_id,
       s.seconds_in_wait                          AS secs_waiting
FROM   v$session s
WHERE  s.username IS NOT NULL
START WITH s.blocking_session IS NULL
           AND EXISTS (SELECT 1 FROM v$session b
                       WHERE b.blocking_session = s.sid)   -- only roots that actually block
CONNECT BY PRIOR s.sid = s.blocking_session
ORDER SIBLINGS BY s.seconds_in_wait DESC;
```

The query uses Oracle's **hierarchical `CONNECT BY`** to walk `BLOCKING_SESSION → SID`: the `START WITH` clause anchors on sessions that are not themselves blocked (`blocking_session IS NULL`) but *do* block someone (the `EXISTS`), giving you the **root blockers**; `CONNECT BY PRIOR s.sid = s.blocking_session` then descends to everyone waiting on them, transitively. `LPAD(' ', LEVEL*2)` indents the tree so the visual structure is obvious, and `ORDER SIBLINGS BY seconds_in_wait` surfaces the longest-suffering waiters first.

```
SID 152  (root blocker, holding TX lock, idle in app)   ← kill THIS one
  └─ SID 318  waiting enq: TX - row lock contention  (90s)
       └─ SID 442  waiting enq: TX - row lock contention  (45s)
```

- **Why this beats a flat list:** the tree shows that killing the single root (SID 152) frees the whole cascade, whereas killing a mid-chain waiter accomplishes nothing.
- **Edge cases:** RAC spans instances — use `GV$SESSION` and include `INST_ID` because the blocker may be on another node; a session blocked by `enq: TM` (table lock from an **unindexed foreign key**) won't always populate `blocking_session` cleanly, so cross-check `V$LOCK` (`TYPE`, `LMODE`, `REQUEST`) for those; and a self-deadlock or distributed-transaction blocker may need `DBA_BLOCKERS`/`DBA_WAITERS` or `V$LOCKED_OBJECT` to fully resolve.
- **Next step:** once you have the root `SID,SERIAL#`, kill it cleanly with `ALTER SYSTEM KILL SESSION` (Q58), not an OS kill.

#### Q68. [Practical] How do you tune the shared pool to avoid "ORA-04031: unable to allocate N bytes of shared memory"?

`ORA-04031` is the shared-pool (or another SGA pool) saying it could not find a contiguous chunk of memory to satisfy an allocation — most often in the **shared pool** because of **fragmentation and pressure from un-shareable SQL**. The dominant root cause is the same villain as the hard-parse storms (Q18): applications using **literals instead of bind variables** flood the library cache with thousands of one-off cursors, each consuming a small chunk, fragmenting the pool until no large contiguous piece remains. So the *real* fix is usually not "make the pool bigger" — that just delays the failure — but **fixing the SQL to use bind variables** (or, as an emergency stopgap, `CURSOR_SHARING=FORCE` to retrofit literal replacement, accepting its plan-stability downsides).

```sql
-- Is the shared pool full of distinct, near-identical literal SQL? (smoking gun)
SELECT sql_text, COUNT(*) variants
FROM   v$sql
GROUP  BY substr(sql_text,1,40)
HAVING COUNT(*) > 50
ORDER  BY variants DESC FETCH FIRST 20 ROWS ONLY;

-- Pool sizing & free memory
SELECT pool, name, bytes/1024/1024 mb
FROM   v$sgastat WHERE name='free memory';
```

Other contributors and their fixes: an **undersized shared pool** relative to a legitimately large/diverse SQL workload (raise `SHARED_POOL_SIZE`, or under ASMM let `SGA_TARGET` grow it — and use **HugePages** so a large SGA doesn't thrash the TLB, Q33); **large PL/SQL packages or huge anonymous blocks** that need big contiguous chunks (mitigate by keeping packages pinned with `DBMS_SHARED_POOL.KEEP` so they aren't aged out and re-loaded, fragmenting the pool); and **excessive child cursors** from bind mismatch / adaptive cursor sharing explosion (Q43) bloating the library cache. The `RESERVED` area of the shared pool exists specifically to reserve space for large allocations so a flood of small ones can't starve them.

The methodical answer in an interview: ORA-04031 is a **fragmentation/sharing** problem far more often than a raw **size** problem. You confirm with `V$SGASTAT` (free memory), `V$SQL`/`V$SQLAREA` (count of near-duplicate literal SQL), and the shared-pool advisor (`V$SHARED_POOL_ADVICE`), then attack the cause: bind variables first, pin large packages, size HugePages and the pool sensibly, and only use `CURSOR_SHARING=FORCE` or a `FLUSH SHARED_POOL` as temporary relief. Repeatedly flushing the shared pool to "fix" ORA-04031 is a band-aid that just incurs a re-parse storm afterward.

### 🟠 Advanced — extended

#### Q69. [Practical] A production CPU spike pins all cores at 100%. Lead the triage from "the box is hot" to a specific SQL.

I treat a CPU storm as a *who-is-burning-CPU* hunt, top-down, and I keep it inside the database's own instrumentation rather than starting at OS `top`. The fastest path is **ASH**: sample the active sessions during the spike and group by `SQL_ID` and `EVENT`. If the dominant samples are **`ON CPU`** (not waiting on a wait event), the database is genuinely compute-bound and a small number of SQL_IDs almost always account for the bulk of it — that is your target. If instead the samples cluster on a *wait* event (e.g., `latch: ...`, `cursor: pin S`, `gc ...`), the CPU is a symptom of contention, not raw work, and you pivot to that.

```sql
-- Top CPU consumers in the last 5 minutes, by SQL and what they were doing
SELECT sql_id,
       session_state,                              -- ON CPU vs WAITING
       COUNT(*)                                    AS samples,
       ROUND(COUNT(*) * 100 / SUM(COUNT(*)) OVER (),1) AS pct
FROM   v$active_session_history
WHERE  sample_time > SYSTIMESTAMP - INTERVAL '5' MINUTE
GROUP  BY sql_id, session_state
ORDER  BY samples DESC FETCH FIRST 15 ROWS ONLY;
```

Once a `SQL_ID` dominates, I pull its **actual plan** with `DBMS_XPLAN.DISPLAY_CURSOR(sql_id, format=>'ALLSTATS LAST')` and look for the classic CPU-burners: a plan that flipped to a **nested loop** doing millions of buffer gets (a cardinality misestimate, Q47), a **full table scan** repeated per row of a `FILTER` (failed unnesting, Q34), or a missing index forcing a scan-and-sort. The two questions are "did this plan change recently?" (compare to `DBA_HIST_SQLSTAT` for the historical plan hash — a **plan flip**, often from bind peeking, Q43) and "is it being executed far more often than usual?" (an application loop or a retry storm), because a CPU spike is either *each execution got more expensive* or *the same cheap statement is being run vastly more times*.

The remediation depends on which it is. A plan flip → re-gather stats or **lock the good plan with a SQL Plan Baseline** (Q17). An execution-count explosion → fix the application (a runaway loop, a missing cache, a retry-on-error tight loop). A genuinely missing access path → add the index/extended stats. And to *stop the bleeding* while diagnosing, the **Resource Manager** can cap the offending consumer group's CPU (Q73) so one runaway report can't starve OLTP. The discipline that signals seniority: I distinguish *ON CPU* from *waiting*, I attribute CPU to a concrete `SQL_ID` via ASH before touching anything, and I determine whether the cost-per-execution or the execution-count changed — because the fix is completely different.

#### Q70. [Practical] Explain SQL Tuning Advisor, SQL Tuning Sets, and how you'd use them to proactively harden a release.

**SQL Tuning Advisor** (part of the Tuning Pack) takes a single SQL statement and runs the optimizer in a special **tuning mode** that is allowed to spend far more time analyzing it: it checks for stale/missing statistics, tries alternative plans, can validate a better plan by partial execution, and produces **recommendations** — gather stats, create an index, accept a **SQL Profile** (corrective cardinality scaling), or restructure the SQL. A **SQL Tuning Set (STS)** is a *named collection* of statements plus their execution context and statistics (plan hashes, bind data, elapsed time), captured from the cursor cache or from AWR — it is the unit you feed to the advisor in bulk and the unit you transport between systems.

```sql
-- Capture the top SQL from AWR into a tuning set, then tune the whole set
BEGIN
  DBMS_SQLTUNE.CREATE_SQLSET(sqlset_name => 'REL_2026_06');
  -- (load it from AWR top-N or the cursor cache via LOAD_SQLSET / SELECT_WORKLOAD_REPOSITORY)
  DBMS_SQLTUNE.CREATE_TUNING_TASK(
     sqlset_name => 'REL_2026_06', task_name => 'tune_rel_2026_06');
  DBMS_SQLTUNE.EXECUTE_TUNING_TASK('tune_rel_2026_06');
END;
/
SELECT DBMS_SQLTUNE.REPORT_TUNING_TASK('tune_rel_2026_06') FROM dual;
```

The proactive release-hardening workflow ties these together with **SQL Plan Management**. Before a release or upgrade, I capture the production workload's top SQL into an **STS** (from AWR), and I capture the *current good plans* as **SQL Plan Baselines** (`DBMS_SPM.LOAD_PLANS_FROM_SQLSET`). After the change (new code, new optimizer version, new stats), the baselines guarantee Oracle keeps using a *verified* plan and will only switch to a new plan after it is proven not-worse — so an optimizer upgrade can't silently regress my critical SQL. The STS is also what I replay with **SQL Performance Analyzer (SPA)** to *predict* which statements an upgrade would regress, before it touches production.

The honest caveats: SQL Tuning Advisor and STS/SPA require the **Tuning Pack** license, and the advisor's instinct to recommend **SQL Profiles** can mask a stats problem — a profile that scales a cardinality estimate is a patch, and if you accept dozens of them you end up with hidden, hard-to-audit corrections. So I use the advisor to *find* the cause (often "stats are stale" or "this column needs extended stats"), prefer fixing stats/indexes/extended stats over accepting profiles, and reserve **baselines** for *locking* plans across the release boundary. That combination — STS to capture, SPA to predict regressions, baselines to lock plans — is how you make an Oracle upgrade boring instead of an incident.

#### Q71. [Practical] How do you manage index health: rebuild vs coalesce vs shrink, invisible indexes, monitoring usage, and the cost of over-indexing?

The first principle is that **B-tree indexes rarely need rebuilding** — the old "rebuild all indexes weekly" ritual is largely a myth that wastes time and generates redo. A B-tree self-balances; space freed by deletes is generally reused by subsequent inserts of nearby keys. Genuine cases for action are narrow: an index that became badly **deleted-sparse** (a table where you bulk-delete old data but the key range isn't re-inserted, leaving half-empty leaf blocks) benefits from `COALESCE` (merges adjacent sparse leaf blocks in place, online, no extra space) or `SHRINK SPACE`; a true **rebuild** is warranted mainly to move an index to another tablespace, change storage attributes, rebuild after a NOLOGGING/unusable state, or convert it. Rebuild does *not* fix a bad **clustering factor** (that's table order, Q29).

```sql
-- Is an index actually used? (turn on monitoring, then check after a real workload window)
SELECT name, used, start_monitoring, end_monitoring
FROM   v$object_usage;        -- per current schema; 12.2+: DBA_INDEX_USAGE has richer stats

-- Test-drop safely with INVISIBLE: optimizer ignores it but it's still maintained
ALTER INDEX idx_orders_status INVISIBLE;
-- ...observe the workload; if nothing regresses, drop it. To restore instantly:
ALTER INDEX idx_orders_status VISIBLE;
```

**Invisible indexes** (11g+) are the safe way to manage the *over-indexing* risk. Over-indexing is a real cost: every index must be maintained on **every INSERT/UPDATE/DELETE** to the indexed columns, so a table with 12 indexes pays 12× the index-maintenance redo/undo and CPU per DML — a frequent hidden cause of slow OLTP writes and `enq: TX - index contention`. Before dropping a suspect index you make it **INVISIBLE**: the optimizer stops considering it (so you can verify nothing regresses) while Oracle keeps maintaining it, meaning if a plan *does* regress you flip it back `VISIBLE` instantly with no rebuild. Only after a full business-cycle observation do you drop it.

To decide *which* indexes are dead weight, use **index usage tracking**: `ALTER INDEX ... MONITORING USAGE` (older) or the automatic **`DBA_INDEX_USAGE`** view (12.2+, which records access counts and patterns). An index that shows zero uses across month-end, quarter-end, and reporting cycles is a drop candidate — but I always check it isn't there to **enforce a unique/PK constraint** or to **avoid an unindexed-FK table lock** (Q36), because those serve a purpose beyond query access paths. The discipline: stop reflexive rebuilds, use COALESCE/SHRINK for genuine sparsity, track real usage before dropping, and lean on INVISIBLE to make index changes reversible without downtime.

#### Q72. [Practical] Describe partition maintenance operations you'd run on a time-series fact table, and how to do them without breaking global indexes or queries.

A range/interval-partitioned fact table by date is maintained through a small set of **partition DDL** operations that are vastly cheaper than equivalent DML. **Dropping old data** is `ALTER TABLE ... DROP PARTITION` (or `TRUNCATE PARTITION`) — instant metadata/extent operation versus an hours-long `DELETE` that bloats undo/redo; this is the whole point of partitioning a time-series table. **Rolling in new data** uses `EXCHANGE PARTITION`: you load and index a standalone staging table off to the side, then *swap* it into the partitioned table with a near-instant data-dictionary pointer flip — no data movement, no long lock, no load impact on live queries. **Compressing/aging** old partitions (`ALTER TABLE ... MOVE PARTITION ... COMPRESS`, or moving them to cheaper storage) lets you tier hot vs cold data within one table.

```sql
-- Roll OUT old data instantly (vs a slow DELETE)
ALTER TABLE sales DROP PARTITION sales_2024_01 UPDATE GLOBAL INDEXES;

-- Roll IN: load staging table, then swap it in atomically
ALTER TABLE sales
  EXCHANGE PARTITION sales_2026_06 WITH TABLE sales_stage_2026_06
  INCLUDING INDEXES WITHOUT VALIDATION UPDATE GLOBAL INDEXES;
```

The trap that breaks production is **global indexes**. With **local** indexes (one index partition per table partition), a `DROP`/`EXCHANGE`/`TRUNCATE PARTITION` only affects that index partition and stays valid. But a **global** index (one B-tree spanning all partitions — often the PK or a cross-partition unique index) is **marked UNUSABLE** by a partition operation unless you tell Oracle to maintain it. The clause `UPDATE GLOBAL INDEXES` (11g+) keeps global indexes valid *as part of the operation* (asynchronous global index maintenance in 12c+ defers the cleanup to a background job for `DROP`/`TRUNCATE`, making them near-instant), whereas omitting it leaves the global index unusable and **every query using it then fails or full-scans** until you rebuild it — a classic self-inflicted outage right after a "routine" partition drop.

The operational discipline: design the table so the partition key matches your aging/loading boundaries (so DROP/EXCHANGE align to whole partitions), prefer **local indexes** where the access pattern allows so maintenance is naturally partition-scoped, and *always* add `UPDATE GLOBAL INDEXES` to partition DDL that has any global index — or schedule the operation in a window and rebuild the global indexes immediately after. With interval partitioning the roll-in is even simpler (new partitions auto-create on insert), but the global-index rule is unchanged. The interview signal is knowing that the *speed* of partition maintenance is real but the *global-index gotcha* is the thing that turns a 1-second `DROP PARTITION` into an incident.

#### Q73. [Practical] How does Database Resource Manager prevent one workload from starving others, and how would you configure it for a mixed OLTP+reporting database?

The Resource Manager (DBRM) exists to solve the "one runaway report eats all the CPU and the OLTP users time out" problem at the *database* layer, where the OS scheduler can't help because it doesn't understand which sessions are critical. You define **consumer groups** (e.g., `OLTP`, `REPORTS`, `BATCH`, `MAINTENANCE`), **mapping rules** that assign sessions to a group (by service name, username, program, or module — set explicitly or via `DBMS_SESSION.SWITCH_CURRENT_CONSUMER_GROUP`), and a **resource plan** that allocates CPU **shares** and limits among the groups. Crucially, DBRM only *throttles when there is contention* — if the box is idle, the reporting group can use 100% of the CPU; the limits bite only when groups compete.

```sql
-- A plan: OLTP gets priority at level 1; reports get what's left at level 2
BEGIN
  DBMS_RESOURCE_MANAGER.CREATE_PENDING_AREA();
  DBMS_RESOURCE_MANAGER.CREATE_PLAN('MIXED_PLAN', 'OLTP priority over reporting');
  DBMS_RESOURCE_MANAGER.CREATE_PLAN_DIRECTIVE('MIXED_PLAN','OLTP_GRP',
     mgmt_p1 => 80);                                    -- 80% at level 1
  DBMS_RESOURCE_MANAGER.CREATE_PLAN_DIRECTIVE('MIXED_PLAN','REPORT_GRP',
     mgmt_p2 => 100,                                    -- the rest, at level 2
     switch_group => 'CANCEL_SQL', switch_elapsed_time => 1800);  -- kill reports >30min
  DBMS_RESOURCE_MANAGER.VALIDATE_PENDING_AREA();
  DBMS_RESOURCE_MANAGER.SUBMIT_PENDING_AREA();
END;
/
ALTER SYSTEM SET RESOURCE_MANAGER_PLAN = 'MIXED_PLAN';
```

Beyond CPU shares, DBRM has levers that are exactly right for a mixed workload: **`SWITCH_GROUP`/`SWITCH_ELAPSED_TIME`** automatically demotes (or `CANCEL_SQL`/kills) a long-running statement after N seconds — so an analyst's accidental Cartesian join is downgraded to a low-priority group or cancelled instead of crushing OLTP; **`PARALLEL_DEGREE_LIMIT_P1`** caps the DOP a group can request (preventing the reporting group from grabbing all PX slaves, Q56); **active session limits** and **undo/temp quotas** per group; and **statement queuing** that holds parallel statements rather than letting them thrash. In **multitenant** (Q51) there's a second tier — a CDB-level plan arbitrates between *PDBs* so one tenant can't starve others, on top of each PDB's internal plan.

The configuration philosophy for OLTP+reporting on one database: put OLTP in a high-share group at the top level (so it always gets responsiveness under contention), put reporting/ad-hoc in a lower-priority group with **runaway-query auto-cancel** and a **parallel cap**, and put nightly batch/maintenance in its own group active mainly during the maintenance window. The win is that you get *isolation without separate hardware* — the reporting workload can burst when the system is idle but is automatically reined in the instant OLTP needs the CPU. The caveat to state: DBRM manages CPU/parallel/active-sessions well but is **not** a substitute for fixing genuinely broken SQL — it contains the blast radius, it doesn't make a bad query fast.

#### Q74. [Practical] Explain how to capture and replay a production workload to de-risk a change (Database Replay / SQL Performance Analyzer).

The hardest part of any infrastructure change — an upgrade, a hardware move, a parameter change — is proving it won't regress the *real* workload, and synthetic load tests rarely match production's concurrency and data mix. **Database Replay** (Real Application Testing) solves this by **capturing the actual production workload** (all external client requests — the SQL, binds, transactions, timing, concurrency) into capture files, then **replaying** that exact workload against a test system that's a clone of production, optionally faster or slower than real time. You then compare performance and any **divergences** (errors or different row counts) between capture and replay, so you're testing against true production behavior, not a guess.

```bash
# 1) On PROD: capture a representative window
EXEC DBMS_WORKLOAD_CAPTURE.START_CAPTURE(name=>'pre_upgrade', dir=>'CAP_DIR');
#    ...run for a representative period (e.g., a peak hour, month-end)...
EXEC DBMS_WORKLOAD_CAPTURE.FINISH_CAPTURE();

# 2) Restore a clone to the SAME point-in-time, apply the change (upgrade/param/HW),
#    then on the TEST system: process and replay
EXEC DBMS_WORKLOAD_REPLAY.PROCESS_CAPTURE('CAP_DIR');
EXEC DBMS_WORKLOAD_REPLAY.INITIALIZE_REPLAY('pre_upgrade','CAP_DIR');
EXEC DBMS_WORKLOAD_REPLAY.PREPARE_REPLAY();
# start replay clients (wrc) against the test DB, then:
EXEC DBMS_WORKLOAD_REPLAY.START_REPLAY();
```

For changes that affect only **SQL plans** (most commonly an optimizer/version upgrade or a stats/parameter change), the lighter, lower-risk tool is **SQL Performance Analyzer (SPA)**. SPA takes a **SQL Tuning Set** (Q70) of the production SQL, executes (or explains) each statement **before** and **after** the change on a test system, and produces a report classifying each SQL as **improved, unchanged, or regressed**, with the plan diff. This is exactly how you de-risk an upgrade's optimizer changes: SPA tells you *precisely which statements* would regress, so you can pre-emptively lock their plans with **SQL Plan Baselines** before going live — turning "we'll find out in production" into "we have a list and a mitigation for each."

The decision between them: use **SPA** when the change's risk is *plan changes* (it's faster, single-statement, no concurrency needed) — upgrades, `optimizer_features_enable`, stats changes, index changes. Use **Database Replay** when the risk involves **concurrency, contention, or throughput** (hardware migration, RAC node count change, big SGA change, OS/storage change) because only a concurrent replay surfaces locking, latch, and `gc` effects that single-statement SPA can't. Both require the **Real Application Testing** license, and both depend on testing against a **point-in-time-consistent clone** of production data — the realism of the data is what makes the result trustworthy. The senior framing: never upgrade or re-platform a critical Oracle database without an SPA run (for plans) and, for anything touching concurrency, a Database Replay (for contention).

### 🔴 Expert — extended

#### Q75. [Practical] A NOLOGGING bulk load left blocks marked corrupt on the standby after a Data Guard failover. Explain how this happened and how to prevent and recover it.

This is a textbook NOLOGGING-vs-Data-Guard collision. A **direct-path NOLOGGING** load (Q48) — `INSERT /*+ APPEND */`, CTAS, or SQL*Loader direct path against a NOLOGGING object — deliberately **skips most redo** for the loaded data to go fast. On the **primary** the blocks are fine, but because the change wasn't written to redo, **none of it shipped to the standby**. The standby's datafiles never received those blocks, so when you query (or fail over to) the standby, Oracle finds blocks the redo stream "knows" should exist but whose content was never logged — and reports them as **`ORA-01578` / NOLOGGING block corruption** (`ORA-26040: data block was loaded using the NOLOGGING option`). It's not physical corruption; it's *logically missing* data on the replica.

```sql
-- Prevention: force ALL changes to be logged regardless of NOLOGGING hints
ALTER DATABASE FORCE LOGGING;          -- standard for any DR-protected DB

-- Detect NOLOGGING-affected blocks (after the fact)
SELECT file#, block#, blocks, object#
FROM   v$database_block_corruption;
SELECT * FROM v$nonlogged_block;       -- 12c+ : explicitly tracks nonlogged ranges
```

The prevention is unambiguous and is *the* standard for any database with a standby: enable **`FORCE LOGGING`** at the database (or tablespace) level. `FORCE LOGGING` overrides every NOLOGGING hint and object attribute, guaranteeing all changes generate redo and therefore reach the standby — you trade some bulk-load speed for a recoverable, replicable database, which for a DR-protected system is non-negotiable. If you genuinely need fast loads, the modern alternative is to keep `FORCE LOGGING` on and rely on direct-path's other speedups, or use a NOLOGGING window only on a system you can fully re-backup and that has no standby.

Recovery when it has already happened: on the **primary**, the affected blocks are intact, so the fix is to **re-ship the data** — restore/recover the affected datafiles on the standby from a *fresh* primary backup taken *after* the NOLOGGING load (RMAN `RECOVER ... NONLOGGED BLOCK` in 12c+ can repair just the nonlogged ranges by pulling current blocks from the primary), or in the worst case re-create the standby datafiles. If the corruption is discovered *after a failover* (the standby became primary and the data is simply gone there), you must reload that data from source because the only good copy was on the old primary. The lesson an interviewer wants: **NOLOGGING and Data Guard are fundamentally incompatible unless you accept manual reconciliation — so DR-protected databases run `FORCE LOGGING`, full stop**, and "make the load faster with NOLOGGING" is the wrong instinct on a replicated system.

#### Q76. [Practical] Walk through diagnosing a distributed-transaction / database-link incident: in-doubt transactions, `ORA-02049`, and the two-phase commit failure modes.

Distributed transactions span databases via **database links**, and Oracle coordinates their atomicity with **two-phase commit (2PC)**: a *prepare* phase where every participating database promises it can commit (writing the changes to redo but not committing), followed by a *commit* phase where the coordinator tells all participants to finalize. The failure mode that defines this topic is a **crash or network partition *between* prepare and commit** — a participant has promised (and is holding locks) but never received the final commit/rollback, leaving an **in-doubt transaction**. That participant cannot unilaterally decide, so it holds its locks and waits, and other sessions trying to touch those rows eventually hit **`ORA-02049: timeout: distributed transaction waiting for lock`**.

```sql
-- Find in-doubt distributed transactions and what they were doing
SELECT local_tran_id, global_tran_id, state, mixed, advice, fail_time
FROM   dba_2pc_pending;

SELECT local_tran_id, in_out, database, dbuser_owner, interface
FROM   dba_2pc_neighbors;            -- who the other participants were
```

The normal resolution is to *let RECO do its job*: the background **RECO (Recoverer) process** automatically reconnects to the other participants once the network/instance is back and resolves in-doubt transactions by completing the 2PC protocol — so the first action is usually to **restore connectivity and wait**, not to intervene manually. You only **manually force** a resolution (`COMMIT FORCE '<local_tran_id>'` or `ROLLBACK FORCE`) when RECO genuinely can't reach a participant and the locks are causing an outage — and you must force the **same outcome** (commit or rollback) that the coordinator ultimately chose, or you create a **mixed/heuristic outcome** (`MIXED='yes'` in `DBA_2PC_PENDING`), meaning the distributed transaction committed on one node and rolled back on another, leaving the databases **logically inconsistent** — a data-integrity incident, not just a hang.

The senior nuances: 2PC also explains why distributed transactions are operationally fragile and why architects minimize cross-database transactional writes (preferring messaging/queues or GoldenGate for cross-DB data movement) — a single transaction's durability now depends on *every* participant and the network between them. Watch for **`distrib. transaction commit`/`PMON deadlock`** symptoms, ensure `DISTRIBUTED_LOCK_TIMEOUT` is tuned for your network, and remember that **SCN synchronization over DB links** (Q44) means a high-SCN remote database can drag your SCN upward through the link. The discipline: identify in-doubt transactions via `DBA_2PC_PENDING`/`DBA_2PC_NEIGHBORS`, let **RECO** resolve them automatically whenever possible, force manually only as a last resort and only matching the coordinator's decision, and treat any `MIXED='yes'` as a correctness incident requiring reconciliation.

#### Q77. [Practical] Design connection management for a high-concurrency app: shared servers vs dedicated, connection pooling, DRCP, and what "too many connections" actually breaks.

The failure this prevents is real: each **dedicated server** connection spawns a server process with its own PGA, so 10,000 idle-but-connected app threads can mean 10,000 processes and tens of GB of PGA consumed by sessions doing nothing — exhausting `PROCESSES`/`SESSIONS`, thrashing the OS scheduler, and risking `ORA-00020: maximum number of processes exceeded`. The first-line fix is **not** an Oracle feature at all but an **application connection pool** (HikariCP, UCP, WebLogic/Tomcat pools): a small, bounded set of physical connections (often dozens, not thousands) multiplexed across many logical app requests. A right-sized pool — counterintuitively *small*, often near the number of CPU cores times a small factor — usually *outperforms* a huge one because it avoids context-switch and latch contention; oversizing the pool just moves the bottleneck into the database.

```
 Dedicated:   each client ──► its own server process + PGA   (heavy at high count)
 Shared srv:  clients ──► dispatchers ──► shared server pool  (fewer processes, UGA in SGA)
 DRCP:        pooled servers shared ACROSS app tiers/hosts    (best for many short-lived conns)
 App pool:    bounded physical conns reused by many requests  (the FIRST thing to get right)
```

When the architecture forces many connections that Oracle itself must hold (e.g., many app servers, or PHP/short-lived processes that can't keep a persistent pool), Oracle offers two server-side multiplexing models. **Shared server** (the old MTS) routes clients through **dispatchers** to a shared pool of server processes, so N clients share M<N servers and per-session UGA moves into the SGA (large pool) — it reduces process count but adds dispatcher queuing latency and is poorly suited to long-running or parallel work. The modern, generally-better option is **Database Resident Connection Pooling (DRCP)**: a pool of *dedicated-style* servers managed by a connection broker that can be **shared across many client processes and even across hosts/middle-tiers**, ideal for large fleets of short-lived connections (web/microservices) — far more memory-efficient than dedicated and without shared server's dispatcher drawbacks.

The decision framework and the "what breaks" point: **always** get the application pool right first (bounded, modest size, with proper validation/timeouts) — most "Oracle can't handle our connections" problems are an unbounded or oversized app pool, not Oracle. Use **DRCP** when you have many app processes/hosts that each need only brief, intermittent database time (the per-connection memory savings are large and DRCP pools across tiers). Use **shared server** sparingly, mainly for legacy many-idle-connection workloads, never for batch/parallel/long transactions. And size `PROCESSES`/`SESSIONS`/`PGA_AGGREGATE_LIMIT` for the *real* concurrent peak, because the thing that actually breaks at "too many connections" is **PGA/process exhaustion and scheduler thrash**, which degrades *everyone*, not just the over-connecting app — so bounding connections is a stability control, not just a tuning nicety.

#### Q78. [Practical] How do you triage a "the whole database is slow" call when AWR shows the top wait is `read by other session` or `buffer busy waits`?

`buffer busy waits` and its cousin `read by other session` are **block-level contention** events: multiple sessions want the *same buffer* at the same time, and they serialize. `read by other session` specifically means a session wants a block that **another session is already reading from disk** — so it waits for that physical read to finish rather than issuing a duplicate read. `buffer busy waits` means sessions are contending for a block already in memory (or being read) for incompatible purposes. The key insight that separates a senior diagnosis from a guess is that these are **symptoms of concentration** — many sessions funneling onto a few hot blocks — not a generic "I/O is slow" signal.

```sql
-- Which blocks/objects are hot? Find the segment behind the waits.
SELECT o.owner, o.object_name, o.object_type, ash.event, COUNT(*) samples
FROM   v$active_session_history ash
JOIN   dba_objects o ON o.data_object_id = ash.current_obj#
WHERE  ash.event IN ('buffer busy waits','read by other session')
  AND  ash.sample_time > SYSTIMESTAMP - INTERVAL '15' MINUTE
GROUP  BY o.owner, o.object_name, o.object_type, ash.event
ORDER  BY samples DESC FETCH FIRST 15 ROWS ONLY;
```

I attribute the waits to a **specific segment and block class** via ASH (`CURRENT_OBJ#`, `P1`=file, `P2`=block, `P3`=block class). The common root causes and their fixes: (1) a **right-hand-growing index** on a monotonic key where every inserting session hits the same rightmost leaf block — the RAC-and-single-instance hotspot (Q14/Q21), fixed by a **reverse-key** or **hash-partitioned index** or larger sequence cache; (2) **freelist / segment-header contention** on a hot table under heavy concurrent insert — largely solved by **ASSM** (Automatic Segment Space Management, the modern default) which replaces freelists with bitmap space management; (3) a **small hot table** (a counter/sequence-emulation table, a "current status" row) updated by everyone — fixed by spreading the rows, caching in the app, or using a real sequence instead of a counter row; (4) genuinely **slow I/O** turning ordinary reads into long ones so that `read by other session` piles up behind them — confirmed by checking `db file sequential read` latency alongside.

The triage discipline: a "whole database is slow" call with `buffer busy`/`read by other session` on top is *almost never* "add more memory" — it's a **hotspot**. So I (1) confirm the dominant wait class and its **avg wait time** in AWR, (2) use ASH to pin the **exact segment and block class** generating the contention, (3) classify it (right-growing index? header/freelist? hot row? slow disk under concurrent demand?), and (4) apply the structural fix — reverse-key/hash-partition the index, confirm ASSM, distribute the hot row, or address the underlying storage latency. The mistake to avoid is treating block contention as an aggregate I/O or memory shortage; the cure is to **de-concentrate** the access, and AWR+ASH tell you *where* the concentration is so you fix the right object.

#### Q79. [Practical] You must roll out a critical PL/SQL package change to a 24/7 system without ORA-04068 errors hitting live sessions. Compare your options.

The hazard is **`ORA-04068`** (Q37): recompiling a package that has **instantiated package state** (package-level variables/cursors) in active sessions invalidates that state, and those sessions get `ORA-04068` ("existing state of packages has been discarded") on their next call — a user-visible error in the middle of their work. So the rollout strategy depends on (a) whether the package holds state and (b) how much downtime/disruption is tolerable. The naive approach — `CREATE OR REPLACE PACKAGE BODY` against the live system — works only if you can guarantee no session has the package's state instantiated, which on a 24/7 system you generally cannot.

```
 Option           Online?   Handles pkg state?   Cost / requirement
 ───────────────  ────────  ──────────────────   ─────────────────────────────
 CREATE OR REPLACE  no*      no → ORA-04068        only safe in a quiet window
 Quiet-window deploy yes-ish no (but no sessions)  brief drain of connections
 Stateless redesign  yes     n/a (no state)        design discipline up front
 Edition-Based Redef yes     YES (full isolation)  schema must be edition-enabled
```

The cleanest *architectural* answer is to **design packages to be stateless** — keep no package-level variables that survive across calls (or make them re-derivable), so there is no instantiated state to discard and `CREATE OR REPLACE BODY` becomes effectively non-disruptive (existing sessions just pick up the new body on next call; only truly in-flight executions of that package would be affected, which a brief retry handles). For systems where state is unavoidable, a **controlled quiet window** (drain the connection pool, deploy, let it reconnect) sidesteps ORA-04068 by ensuring no session holds state during the recompile — acceptable when you have *any* maintenance window, even a 30-second drain.

The gold-standard for *true* zero-downtime is **Edition-Based Redefinition** (Q50): install the new package version in a **child edition** while live sessions keep running the **parent edition's** version untouched — no recompile happens against their edition, so **no ORA-04068**. New connections (or sessions that explicitly switch) use the new edition; once all sessions have migrated, you retire the old edition. EBR is the only mechanism that lets you change package code *and* keep both old and new running simultaneously with zero session disruption — at the cost of the schema being edition-enabled and the team understanding editions. The interview signal: I name **ORA-04068 as the specific risk**, I distinguish **stateful vs stateless** packages (because that determines the blast radius), and I rank the options — stateless design and EBR for true online change, a connection drain as the pragmatic middle ground, and a raw `CREATE OR REPLACE` only in a confirmed quiet window.

#### Q80. [Practical] Explain how you'd find and fix the most expensive SQL in a database you've never seen before, using only built-in tooling.

Walking into an unfamiliar database, I start at the **load profile and top SQL**, because that's where the time actually goes — not at whatever someone *thinks* is slow. The fastest orientation is an **AWR report** for a representative busy window (`@?/rdbms/admin/awrrpt`): its "Top SQL by Elapsed Time" / "by CPU" / "by Buffer Gets" / "by Physical Reads" sections rank the statements consuming the most resource, and the "Top Timed Events" tells me whether the database is CPU-bound, I/O-bound, or contention-bound overall. That single report frames everything: if `DB CPU` dominates, I hunt CPU-heavy SQL; if `User I/O` dominates, I hunt high-physical-read SQL; if a concurrency wait dominates, the expensive SQL may be a victim of contention rather than the cause.

```sql
-- Top SQL by total DB time from the cursor cache (no AWR license needed)
SELECT sql_id,
       ROUND(elapsed_time/1e6,1)            AS elapsed_s,
       executions,
       ROUND(elapsed_time/1e6/GREATEST(executions,1),3) AS s_per_exec,
       buffer_gets, disk_reads,
       ROUND(buffer_gets/GREATEST(executions,1))        AS gets_per_exec
FROM   v$sql
ORDER  BY elapsed_time DESC FETCH FIRST 20 ROWS ONLY;
```

The crucial analytical step is separating **expensive per execution** from **expensive in aggregate**. A SQL with huge `s_per_exec`/`gets_per_exec` is individually inefficient (bad plan, missing index, full scan) — fix the *statement*. A SQL that's cheap per run but has millions of `executions` is an **application pattern** problem (a loop, a missing cache, an N+1 query) — fix the *caller*, because no amount of SQL tuning helps a statement that's merely run too often. `V$SQL` (live, no license) or `DBA_HIST_SQLSTAT` (history, AWR-licensed) give me both dimensions; I always look at `executions` and the per-execution ratios, not just total time, to avoid "optimizing" a statement whose real problem is its frequency.

Having found a target `SQL_ID`, I pull its **actual plan** (`DBMS_XPLAN.DISPLAY_CURSOR(sql_id, format=>'ALLSTATS LAST')` or `DISPLAY_AWR`) and read the **E-Rows vs A-Rows** divergence (Q61) to decide whether the fix is stats/histograms/extended stats (cardinality misestimate), an access path (missing/wrong index), or a query rewrite (failed unnesting, accidental Cartesian). For a quick automated second opinion I can run **SQL Tuning Advisor** on that one `SQL_ID` (Q70) — but I treat its SQL Profile recommendation skeptically, preferring to fix the underlying stats/index. The whole method uses **only built-in tooling** — AWR/ASH (licensed Diagnostics/Tuning Pack) or, license-free, `V$SQL`/`V$SQL_PLAN`/`V$ACTIVE_SESSION_HISTORY`-equivalents and `EXPLAIN PLAN`/`DBMS_XPLAN` — and the discipline is the same regardless of the database: find where time goes (AWR top-SQL), split per-exec cost from execution count, read the real plan's estimate-vs-actual, and fix the actual cause rather than reflexively hinting.

#### Q81. [Practical] Describe a real character-set migration (e.g., WE8MSWIN1252 → AL32UTF8) and the data-loss pitfalls that make it dangerous.

Migrating a database character set to **AL32UTF8** (Unicode) is one of the most error-prone operations in Oracle because it can **silently corrupt or truncate data** if done naively. The danger has two sources. First, in a single-byte charset like WE8MSWIN1252 every character is one byte, but in AL32UTF8 accented and non-ASCII characters take **2–4 bytes** — so a value that fit in `VARCHAR2(10 BYTE)` may suddenly need more than 10 bytes and **won't fit**, causing **data loss / truncation** unless columns use **CHAR length semantics**. Second, and more insidious, **invalid or mis-tagged data**: if the source database was tagged WE8MSWIN1252 but actually contained UTF-8 bytes (or vice versa) — a depressingly common result of past misconfigured app inserts — a blind conversion will mangle those characters irreversibly.

```bash
# The mandatory first step: scan for data that won't convert cleanly
$ csscan FULL=Y TOCHAR=AL32UTF8 ...        # legacy scanner (pre-12c)
# 12c+ : the Database Migration Assistant for Unicode (DMU) GUI/CLI
#   classifies every column's data as:
#     - Changeless     (pure ASCII, safe)
#     - Convertible    (needs re-encoding, will fit)
#     - Truncation     (won't fit current column width)  ← MUST widen/CHAR semantics
#     - Lossy/Invalid  (bytes don't decode as source charset) ← MUST clean first
```

So the safe procedure is **scan-before-convert, always**. The **DMU (Database Migration Assistant for Unicode)** — or the legacy **CSSCAN/CSALTER** on older versions — analyzes every character column and classifies its data into *changeless* (pure ASCII, no work), *convertible* (will re-encode and still fit), *truncation* (the wider UTF-8 form exceeds the column's byte width — you must widen columns or switch to CHAR length semantics first), and *lossy/invalid* (bytes that don't even decode as the declared source charset — these you must **fix at the source** before migrating, because there is no automatic correct interpretation). You do **not** proceed until truncation and lossy categories are zero; DMU then performs the conversion safely (it can widen columns and convert in place). `CSALTER` only works when there's no convertible/lossy data (essentially when the change is just a metadata re-tag), which is why it alone is dangerous as a shortcut.

The pitfalls to call out as an expert: (1) **byte vs char semantics** — set `NLS_LENGTH_SEMANTICS=CHAR` (or define columns as `VARCHAR2(n CHAR)`) so column sizes are expressed in *characters*, immune to the multi-byte expansion (Q26); (2) **`AL32UTF8` vs `UTF8`** — pick `AL32UTF8` (true 4-byte Unicode incl. supplementary characters), not the older `UTF8` (CESU-8, which mishandles supplementary planes); (3) **the migration is one-way and high-risk**, so it's done with a full backup and ideally on a clone first, and on large databases the convertible volume can make it a long maintenance operation. The lesson: a charset migration is fundamentally a **data-quality and column-sizing** exercise gated by a mandatory scan (DMU/CSSCAN) — the conversion command is the easy part, and skipping the scan is exactly how teams silently lose accented characters and discover it months later.

#### Q82. [Practical] How do you watch a long-running statement *live* and decide whether to let it finish or kill it?

When someone says "my report has been running for 40 minutes, is it stuck or just slow?", the tool is **Real-Time SQL Monitoring** (`V$SQL_MONITOR` / the `DBMS_SQLTUNE.REPORT_SQL_MONITOR` report, auto-triggered for any SQL that runs >5 seconds or in parallel). Unlike a plan, it shows **live, per-operation progress**: which plan line is currently executing, how many rows each step has produced so far, its actual vs estimated rows, memory/temp used, and a per-operation **percentage-complete** for steps like full scans and sorts. That lets you answer the real question — is it *progressing* (rows climbing, % advancing) or *wedged* (parked on one operation, no progress, blocked or spinning)?

```sql
-- Live monitoring report for an active SQL (HTML version is richer)
SELECT DBMS_SQLTUNE.REPORT_SQL_MONITOR(
         sql_id => 'a1b2c3d4e5f6g', type => 'TEXT') FROM dual;

-- Or list what's being monitored right now
SELECT sid, sql_id, status, elapsed_time/1e6 elapsed_s,
       (SELECT ROUND(SUM(NVL(output_rows,0))) FROM v$sql_plan_monitor m
        WHERE m.sid = s.sid AND m.sql_id = s.sql_id)  rows_so_far
FROM   v$sql_monitor s WHERE status = 'EXECUTING';
```

The decision logic: if the monitor shows the statement **steadily producing rows** and a sane plan, it's just slow — let it finish (or accept it'll be slow this run and tune it afterward). If it's parked on one operation with the actual rows *already far exceeding* the estimate (E-Rows ≪ A-Rows on the live line), it picked a bad plan — a nested loop ballooning, a hash join spilling to temp — and it may never finish in acceptable time, so killing and fixing the plan is right. If it's not on CPU and not progressing, check `V$SESSION.BLOCKING_SESSION`/`EVENT` — it's blocked, and the fix is upstream (Q67), not killing this victim.

The senior point is that SQL Monitoring turns "is it stuck?" from a guess into an observation: you see the *current* operation, its progress, and its estimate-vs-actual error in real time, so you make an evidence-based call to wait or kill — and the same report, saved after the fact, is one of the best artifacts for tuning the statement later. (It requires the Tuning Pack; without it you reconstruct a coarser picture from `V$SESSION_LONGOPS` and repeated `V$SQL_PLAN_MONITOR`-equivalent sampling.)

#### Q83. [Practical] Explain ADDM and the autotask framework, and how they fit (or don't) into a real performance workflow.

**ADDM (Automatic Database Diagnostic Monitor)** runs automatically after each AWR snapshot and analyzes the period using a top-down, *DB-time-based* method: it identifies the activities consuming the most database time, attributes them to root causes (a specific SQL, an undersized memory pool, I/O contention, excessive parsing, a config setting), quantifies the **impact in DB-time**, and emits **ranked, actionable recommendations** with the estimated benefit of each. Its value is triage speed — instead of reading raw AWR sections yourself, ADDM says "SQL_ID xyz consumed 38% of DB time; consider this index" — which is a genuinely good *starting* point for an incident review.

```sql
-- Read the latest ADDM findings for the most recent snapshots
SELECT DBMS_ADDM.GET_REPORT('ADDM_TASK_NAME') FROM dual;     -- or via EM/AWR scripts

-- The autotask framework: what's scheduled and is it succeeding?
SELECT client_name, status, attributes
FROM   dba_autotask_client;                                  -- stats, ADDM, segment advisor, SQL tuning
```

The **autotask framework** is the scheduler that runs Oracle's self-management jobs in the **maintenance windows** (default nightly/weekend): the **auto optimizer statistics gather** (Q66), **Automatic SQL Tuning Advisor**, **segment advisor** (space reclamation candidates), and ADDM analysis. Knowing this matters operationally because these jobs can themselves cause off-hours load, can be **disabled** (then your stats silently go stale), or can **race the ETL** (gathering stats before data lands). You inspect and control them via `DBA_AUTOTASK_CLIENT` and `DBMS_AUTO_TASK_ADMIN`, and you adjust the **maintenance window** timing so the autotasks run *after* nightly loads, not during peak or before the data exists.

Where ADDM does *not* fit: it reasons from AWR's averaged window, so it **smooths over transient spikes** the way AWR does — a 90-second p99 stall several times an hour may not register as a top DB-time consumer over an hour, and ADDM will miss it where **ASH** (Q12) would catch it. ADDM also tends to recommend **SQL Profiles and indexes** that, accepted uncritically, accumulate as hidden corrections (same caution as the SQL Tuning Advisor, Q70). So the mature workflow is: use ADDM for fast first-pass triage of *sustained* problems and to confirm the dominant DB-time consumer, but reach for **ASH/SQL Monitoring** for transient or latency-tail issues, and treat ADDM's specific recommendations as hypotheses to validate (re-gather stats, check the real plan) rather than changes to apply blindly.

#### Q84. [Practical] When is an autonomous transaction the right tool, when is it an anti-pattern, and what production bug does it commonly cause?

An **autonomous transaction** (`PRAGMA AUTONOMOUS_TRANSACTION`) lets a PL/SQL block run a *fully independent child transaction* that commits or rolls back **without affecting the parent transaction**. The legitimate use case is narrow and specific: **logging/auditing that must persist even if the main transaction rolls back**. If your error handler writes to an `error_log` table and the business transaction then rolls back, a normal insert would be rolled back too — you'd lose the very error record you need. An autonomous transaction commits the log row independently, so the audit survives the rollback. The same applies to incrementing a sequence-like counter or recording an attempt regardless of outcome.

```sql
CREATE OR REPLACE PROCEDURE log_error(p_msg VARCHAR2) IS
  PRAGMA AUTONOMOUS_TRANSACTION;          -- independent child transaction
BEGIN
  INSERT INTO error_log(ts, msg) VALUES (SYSTIMESTAMP, p_msg);
  COMMIT;                                  -- MUST commit (or rollback) before returning
END;
/
-- caller's transaction can ROLLBACK and the log row still persists
```

The anti-pattern — and the production bug — is using an autonomous transaction to **dodge the mutating-table error (`ORA-04091`, Q53)** or to "read around" the current transaction. Because the autonomous child runs in its *own* transaction, it sees only **committed** data and is **blind to the parent's uncommitted changes** — so a row trigger that fires an autonomous query against its own table to "count rows" gets a snapshot that ignores the in-flight statement, producing **logically wrong results** that pass in light testing and corrupt data under real concurrency. It can also **self-deadlock**: if the parent holds a row lock and the autonomous child tries to update the same row, the child waits on a lock the parent will never release until the child returns — a guaranteed hang (`ORA-00060`/`ORA-04020`-style stall).

The rules an interviewer wants: autonomous transactions are correct for **independent side effects that must outlive the parent's fate** (logging, auditing, error capture) and must **always commit or rollback explicitly** (an unterminated autonomous transaction raises `ORA-06519`). They are *wrong* whenever the logic needs to see the parent's data, wrong as a way to evade `ORA-04091` (use a compound/statement trigger or a constraint instead, Q53), and dangerous when they touch the same rows the parent has locked. The mark of misuse is "I added `PRAGMA AUTONOMOUS_TRANSACTION` to make the error go away" — that almost always trades a clear error for a silent correctness or deadlock bug.

#### Q85. [Practical] Walk through recovering a single dropped/corrupted datafile in ARCHIVELOG mode with RMAN, and contrast it with restoring the whole database.

The strength of **ARCHIVELOG mode** is *granular* recovery: you can restore and recover a **single datafile** (or even individual blocks) while the rest of the database — every other tablespace — stays **online and serving users**. If one datafile is lost or corrupt, you do **not** restore the whole database. You take just that datafile (or its tablespace) offline, restore *only* it from the last backup, and **recover** it by applying the archived + online redo logs generated since that backup, which rolls it forward to the current SCN so it's consistent with everything else. Because redo since the backup is preserved (that's what ARCHIVELOG mode guarantees), there is **zero data loss**.

```bash
# Single datafile recovery — rest of the DB stays OPEN
RMAN> SQL 'ALTER DATABASE DATAFILE 7 OFFLINE';     -- take only the bad file offline
RMAN> RESTORE DATAFILE 7;                            -- from last backup
RMAN> RECOVER DATAFILE 7;                            -- apply redo to roll it forward
RMAN> SQL 'ALTER DATABASE DATAFILE 7 ONLINE';        -- back in service, no data loss

# Even finer: repair just corrupt blocks, file stays online
RMAN> RECOVER DATAFILE 7 BLOCK 12345;
# or, driven from the corruption view:
RMAN> RECOVER CORRUPTION LIST;
```

Contrast this with **NOARCHIVELOG mode**, where the only backups are *consistent* (cold) backups and no redo is archived — so the best you can do after *any* datafile loss is restore the **entire** database to the point of the last full backup and **lose every change since** (and you can't do online file-level recovery at all). This is precisely why every production database that values its data runs in ARCHIVELOG mode: it converts "restore everything, lose a day" into "restore one file, lose nothing, while the system stays up." Even finer-grained, **Block Media Recovery** (`RECOVER ... BLOCK` / `RECOVER CORRUPTION LIST`) repairs *just the corrupt blocks* — pulled from a backup or, if you have a standby, from the standby — while the datafile **remains online**, so a handful of bad blocks don't require taking even one file offline.

The points that signal experience: (1) the whole reason for ARCHIVELOG mode is **file-level, no-data-loss, mostly-online recovery** — restoring the full database is the *fallback*, not the plan; (2) you must have the **archived redo** continuously from the backup to now (which loops back to the FRA/archiver discipline of Q64 — if the archiver was stuck and logs were lost, the recovery chain is broken); (3) for the system tablespace or controlfile/spfile loss the scope widens, but for ordinary data tablespaces you stay surgical; and (4) RMAN's `VALIDATE`/`RESTORE ... PREVIEW` and a regularly *tested* restore are what make this real — an untested backup is a hope, not a recovery strategy.

#### Q86. [Practical] Why and how would you gather *system* statistics and fixed-object statistics, and what breaks if you never do?

Beyond *object* statistics (table/index/column stats, Q40), the optimizer relies on two other classes that teams routinely forget. **System statistics** describe the *hardware*: CPU speed, single-block I/O latency, multi-block I/O latency and the effective multi-block read count. The CBO uses these to convert its abstract I/O+CPU cost into a realistic comparison — specifically to weigh a **full table scan** (large multi-block reads) against an **index access** (many small single-block reads). Without representative system stats the optimizer falls back to defaults (noworkload values) that may not match your actual storage (e.g., flash arrays where multi-block reads are far cheaper than the defaults assume), systematically **biasing plans** — often toward index access when full scans would be cheaper on your hardware, or vice versa.

```sql
-- Capture workload-based system stats during a representative busy period
BEGIN
  DBMS_STATS.GATHER_SYSTEM_STATS('START');        -- begin sampling
  -- ...let a representative workload run (e.g., peak hour)...
  DBMS_STATS.GATHER_SYSTEM_STATS('STOP');
END;
/

-- Fixed-object stats: stats on the X$/V$ internal structures
EXEC DBMS_STATS.GATHER_FIXED_OBJECTS_STATS;
```

**Fixed-object statistics** are stats on the **X$ tables** underlying the `V$`/`GV$` dynamic performance views and other in-memory structures. Queries against the data dictionary and dynamic views — which Oracle itself runs constantly, and which your monitoring/EM/AWR queries hammer — are optimized using these. If they're never gathered, the optimizer guesses at the cardinality of dictionary/`V$` queries, and you get the ironic failure mode where **monitoring and dictionary queries themselves become slow** (a `SELECT` against `V$SESSION` joined to `DBA_OBJECTS` doing something absurd), or recursive SQL and certain DDL slow down — symptoms that are baffling because nobody thinks to stat the internal structures.

The operational guidance: gather **system stats once** in *workload* mode during a representative peak so the CBO knows your real I/O latencies (and re-gather only when the hardware changes — a SAN-to-flash migration is the canonical trigger), and gather **fixed-object stats** after a major upgrade, after significant SGA/workload changes, and otherwise periodically (when the database is at a *typical* load, since they capture the size of in-memory structures). The "what breaks if you never do" answer: object stats alone leave the optimizer with a distorted view of *hardware cost* (bad FTS-vs-index decisions) and a blind spot on *dictionary/V$ queries* (slow monitoring and recursive SQL) — both are real, commonly-overlooked sources of "the plans are just slightly wrong everywhere" that no amount of table-stats gathering fixes.

#### Q87. [Practical] Your scheduled DBMS_SCHEDULER job silently stopped running. How do you diagnose and harden Oracle job scheduling?

A job that "just stopped" is diagnosed by reading the scheduler's own history, never by assuming. `DBMS_SCHEDULER` records every run in **`DBA_SCHEDULER_JOB_RUN_DETAILS`** (status, error, actual start, duration) and the job's current state in **`DBA_SCHEDULER_JOBS`** (`STATE`, `ENABLED`, `FAILURE_COUNT`, `NEXT_RUN_DATE`). The usual culprits surface immediately: the job hit its **`MAX_FAILURES`** and auto-**disabled** itself after repeated errors; its **schedule/window** changed or the named window is closed; the job is `BROKEN`/`DISABLED`; the **`JOB_QUEUE_PROCESSES`** parameter is 0 (which disables the job coordinator entirely); or the job *is* running but **overruns its interval** so consecutive runs collide and it appears stalled.

```sql
-- Did it run, and why did it stop?
SELECT job_name, status, error#, actual_start_date, run_duration
FROM   dba_scheduler_job_run_details
WHERE  job_name = 'NIGHTLY_ETL'
ORDER  BY actual_start_date DESC FETCH FIRST 10 ROWS ONLY;

-- Current state: enabled? broken? how many failures? when is next run?
SELECT job_name, enabled, state, failure_count, max_failures,
       last_start_date, next_run_date
FROM   dba_scheduler_jobs WHERE job_name = 'NIGHTLY_ETL';
```

Diagnosis path: check `STATE` and `ENABLED` first (a `DISABLED` job after `FAILURE_COUNT` reaching `MAX_FAILURES` is the most common "it silently stopped"); read the last `ERROR#`/status in `..._RUN_DETAILS` to see *why* it failed (a dependency missing, a privilege revoked, the target table renamed); confirm `JOB_QUEUE_PROCESSES > 0` (a zero here disables the whole subsystem and is a classic post-maintenance mistake); and verify the **resource plan/window** didn't move the job out of its run window or throttle its consumer group to nothing (Q73). For chains, inspect `DBA_SCHEDULER_RUNNING_JOBS` and the chain step states.

Hardening so it doesn't fail silently again: attach a **job event / notification** (`DBMS_SCHEDULER.ADD_JOB_EMAIL_NOTIFICATION` or `SET_ATTRIBUTE` on `RAISE_EVENTS`) so a failure *alerts someone* instead of just incrementing a counter; set a sane **`MAX_RUN_DURATION`** so an overrunning job is stopped rather than colliding with the next run; consider **`RESTARTABLE`** and a modest `MAX_FAILURES` with `RETRY` rather than auto-disable-on-first-failure; and use **lightweight jobs / job classes** mapped to a resource group so a heavy job is both contained and observable. The discipline: scheduler problems are almost always answered by `DBA_SCHEDULER_JOB_RUN_DETAILS` + `DBA_SCHEDULER_JOBS` (the data is *right there*), and the fix isn't just "re-enable it" — it's adding **failure notification** and **duration limits** so the next failure is loud and self-contained instead of silent.

#### Q88. [Practical] How do you store and tune large documents/images efficiently with SecureFiles LOBs, and what are the operational knobs?

For genuinely large or unbounded binary/text content (images, PDFs, JSON documents, XML), the right type is a **SecureFiles `BLOB`/`CLOB`** (Q54) — and the operational difference between a sloppy LOB column and a tuned one is large. The most consequential knob is **caching**: `NOCACHE` (the default for LOBs) means LOB reads/writes **bypass the buffer cache** and go straight to disk via direct path — correct for big, write-once-read-rarely media (you don't want a 50 MB image evicting thousands of hot data blocks from the cache), whereas `CACHE` is right for small, frequently-read LOBs. The second is **`LOGGING` vs `NOLOGGING`/`FILESYSTEM_LIKE_LOGGING`**: full redo logging is mandatory on a DR-protected database (Q75's `FORCE LOGGING` overrides it anyway), but for reproducible bulk media loads on a non-replicated system, reduced logging speeds ingestion.

```sql
CREATE TABLE documents (
  id     NUMBER PRIMARY KEY,
  body   BLOB
)
LOB (body) STORE AS SECUREFILE (
  ENABLE STORAGE IN ROW           -- small LOBs (<~4000B) stay in the row block
  CACHE READS                     -- cache on read, not on write-heavy load
  NOLOGGING                       -- (only where acceptable; FORCE LOGGING overrides)
  DEDUPLICATE                     -- store identical LOBs once
  COMPRESS MEDIUM                 -- compress LOB content
);
```

SecureFiles-specific features turn the column into a tunable store: **`DEDUPLICATE`** detects byte-identical LOBs and stores them once (huge for systems where the same attachment is uploaded many times), **`COMPRESS LOW|MEDIUM|HIGH`** compresses content (trading CPU for space — `MEDIUM` is the usual balance), and **encryption** integrates with TDE for at-rest protection of sensitive documents — all transparent to the application. **`ENABLE STORAGE IN ROW`** keeps small LOBs inline in the row block (one read, no separate LOB segment access) while spilling large ones out-of-line, which is the right default for mixed-size content; `DISABLE STORAGE IN ROW` forces everything out-of-line, better when LOBs are uniformly large and you don't want them bloating row migration.

The operational pitfalls to flag: **read/write LOBs in chunks** with `DBMS_LOB` (or the OCI/JDBC streaming APIs) sized to the LOB **chunk size** rather than materializing a multi-hundred-MB LOB in memory; place the LOB segment in its **own tablespace** so its I/O and space management don't contend with the hot table data; ensure you're on **SecureFiles, not legacy BasicFiles** (BasicFiles lacks dedup/compress/encrypt and suffers the old LOB-index/freelist contention); and remember **`NOCACHE` + direct I/O** is usually right for big media precisely to *protect the buffer cache*. The interview signal is treating a LOB column as a *tunable subsystem* — caching, logging, in-row threshold, dedup/compress, dedicated tablespace, chunked access — rather than "it's just a BLOB, throw the file in."

#### Q89. [Practical] Describe applying a quarterly Release Update (RU) with minimal downtime, and what RAC/Data Guard buy you here.

Oracle ships security and bug fixes as quarterly **Release Updates (RUs)** (and the lighter **Release Update Revisions, RURs**), applied with **OPatch**. The fix has two parts that people conflate: **patching the binaries** (the `$ORACLE_HOME` software) and **patching the database** (running the **datapatch** SQL that updates the dictionary/PL/SQL to match). On a single-instance database both require the database down for the binary relink and a brief restart for datapatch — real, if short, downtime. The point of the operational design is to shrink or eliminate the *database-unavailable* portion.

```bash
# 1) Patch the binaries in a (possibly out-of-place) ORACLE_HOME
$ opatch apply                                   # or opatchauto for the GI/RDBMS stack
# 2) Apply the SQL changes to the running/upgraded DB
$ ./datapatch -verbose                           # updates dictionary & PL/SQL to the RU
# verify:
SQL> SELECT patch_id, status, action FROM dba_registry_sqlpatch ORDER BY action_time;
```

On **RAC** you do a **rolling patch**: patch and bounce **one node at a time** while the surviving nodes keep serving connections (clients reconnect/fail over via services and FAN). Because the cluster has multiple instances over shared storage, the *database stays available throughout* — only individual instances blip. This is one of RAC's headline operational payoffs (alongside HA, Q13): you get **near-zero-downtime patching** for the OS/Grid Infrastructure/RDBMS, as long as the patch is certified **rolling-installable** (most RUs are; some require all nodes at the same patch level simultaneously, which breaks the rolling property).

For patches that *aren't* rolling, or for a **major upgrade**, **Data Guard** provides the low-downtime path (Q19): you patch/upgrade the **standby** first while the primary serves normally, then do a brief **switchover** so the patched standby becomes primary — the only outage is the switchover window (seconds to a minute, masked further by Application Continuity). Combined with **out-of-place patching** (apply the RU to a *new* `ORACLE_HOME` and switch to it, so rollback is just switching back) and a prior **SQL Performance Analyzer** run (Q74) to catch plan regressions, this is how shops apply quarterly RUs without a meaningful maintenance window. The discipline an interviewer probes: separate **binary patch** from **datapatch**, know that **RAC gives rolling node-by-node patching** and **Data Guard gives switchover-based patching** for non-rolling cases, use **out-of-place homes** for instant rollback, and always validate plan stability (baselines/SPA) because even a "bug fix" RU can shift optimizer behavior.

#### Q90. [Practical] How would you implement and operate database auditing for a compliance requirement without crippling performance?

For a compliance mandate ("record all access to the PII tables and all privileged actions"), the modern mechanism is **Unified Auditing** (12c+), which consolidates the old `AUD$`, `FGA_LOG$`, and various separate trails into a **single, secure, read-protected audit trail** written through a high-performance internal queue. The operational design centers on **audit policies**: you define a named policy specifying *what* to audit (actions, privileges, roles, or specific objects/columns) and optionally *conditions*, then `AUDIT POLICY ... ` to enable it — globally or scoped to specific users. The key to not crippling performance is **auditing selectively**: audit the *sensitive* objects and *privileged* operations, not every `SELECT` from every user, because a too-broad policy generates enormous trail volume and write overhead.

```sql
-- Policy: audit reads of a PII table and all DDL by privileged users
CREATE AUDIT POLICY pii_access_pol
  ACTIONS SELECT, UPDATE, DELETE ON hr.employees_pii;
AUDIT POLICY pii_access_pol;                       -- enable (optionally BY/EXCEPT users)

-- Fine-Grained Auditing: only when a SENSITIVE column is actually touched
BEGIN
  DBMS_FGA.ADD_POLICY(
    object_schema => 'HR', object_name => 'EMPLOYEES_PII',
    policy_name   => 'salary_fga',
    audit_column  => 'SALARY', audit_condition => NULL,
    statement_types => 'SELECT,UPDATE');
END;
/
```

**Fine-Grained Auditing (FGA, `DBMS_FGA`)** is the precision tool that keeps overhead low: instead of auditing every access to a table, FGA fires **only when a specified column is referenced and an optional condition is met** — e.g., audit a `SELECT` on `employees` *only* when the `SALARY` column is in the query and the row belongs to an executive. This dramatically cuts trail volume versus blanket statement auditing while still capturing exactly the sensitive accesses compliance cares about, and it can optionally run an event handler. The trade-off is that FGA evaluates its predicate per qualifying statement, so the **condition must be cheap** and the policy narrow.

The operational realities to manage: (1) **the audit trail grows** and must be maintained — use `DBMS_AUDIT_MGMT` to relocate the unified trail to its own tablespace, set up **automatic purging** by age, and archive per the retention policy, or the trail itself becomes a space/performance incident; (2) **protect the trail** — auditors and DBAs should be separated (this is where **Database Vault** earns its keep, so even DBAs can't alter or delete audit records, satisfying separation-of-duties); (3) **size for write volume** — Unified Auditing's queued writes are efficient, but a firehose policy still costs, so measure the trail growth rate after enabling and tighten policies that produce noise. The senior framing: compliance auditing is a *targeting and lifecycle* problem — use Unified Auditing policies and FGA to capture **precisely** the sensitive/privileged events (not everything), put the trail in its own tablespace with automated purge/archive, and protect it with separation-of-duties — which gives you a defensible audit posture without turning auditing into the performance bottleneck.

#### Q91. [Practical] A sequence-driven primary key is causing insert contention even on single-instance Oracle. Diagnose the hotspot and fix it.

Even without RAC, a monotonically increasing key (a sequence or IDENTITY column) on a high-insert table creates a **right-hand index leaf hotspot**: every concurrent insert generates the *next* value, which lands in the *same* rightmost leaf block of the primary-key B-tree, so all inserting sessions contend for that one block and its ITL slots. The symptom in AWR/ASH is **`buffer busy waits`** and **`enq: TX - index contention`** concentrated on the PK index (Q78), plus possibly **`enq: TX - allocate ITL entry`** if the leaf block runs out of ITL slots (Q35). It's the single-instance cousin of the RAC Cache-Fusion hotspot (Q21) — same root cause (right-growing index), just contending within one buffer cache instead of across the interconnect.

```sql
-- Confirm the hotspot: waits concentrated on ONE index, hot rightmost block
SELECT event, COUNT(*) samples
FROM   v$active_session_history
WHERE  current_obj# = (SELECT object_id FROM dba_objects
                       WHERE object_name='ORDERS_PK' AND owner='APP')
  AND  sample_time > SYSTIMESTAMP - INTERVAL '10' MINUTE
GROUP  BY event ORDER BY samples DESC;

-- Fix A: increase sequence cache so sessions don't serialize on seq.NEXTVAL itself
ALTER SEQUENCE app.orders_seq CACHE 100000 NOORDER;

-- Fix B: spread inserts across many leaf blocks with a hash-partitioned (global) index
CREATE INDEX app.orders_pk ON app.orders(id) GLOBAL PARTITION BY HASH(id) PARTITIONS 16;
```

There are two distinct contention points to separate. The **sequence object itself**: if `CACHE` is small (the default is 20) or, worse, `ORDER`/`NOCACHE` is set, sessions serialize generating values — so first ensure a **large `CACHE` with `NOORDER`** (the cached range per instance means sessions rarely touch the dictionary; gaps are acceptable because PKs needn't be contiguous, Q3). The **index leaf hotspot** is the harder one: spread the inserts across many leaf blocks. The cleanest modern fix is a **hash-partitioned index** (partition the PK index `BY HASH` into N partitions), so consecutive sequence values hash to *different* index partitions/leaf blocks and the contention is divided N ways. A **reverse-key index** also spreads inserts (it reverses the bytes so adjacent values land far apart) but **breaks range scans** on the key, so it's a worse choice when you ever do `WHERE id BETWEEN ...`.

The decision and caveats: raise the sequence `CACHE` and confirm `NOORDER` first (cheap, always correct); then choose **hash-partitioned index** to scatter the leaf hotspot while *preserving* range-scan ability, reserving **reverse-key** for cases where you never range-scan the key. The deeper architectural option is to **not use a globally-monotonic key at all** — a partially-randomized or composite key (e.g., prefixing with a small bucket id) eliminates the single growth point entirely. The lesson worth stating: the "tidy sequential PK" is a textbook insert-contention anti-pattern *even on single instance* — the optimization is to deliberately **de-cluster** the inserts (cache the sequence, hash the index, or design a non-monotonic key) so concurrent inserts don't all collide on one rightmost block, accepting that gaps and non-sequential keys are a feature, not a defect.

## 🧩 Extended Questions — Set 3: Coding, Design & Expert Scenarios

### 🟢 Basic — extended

#### Q92. [Coding] Write SQL to pivot rows into columns (e.g. monthly sales per product across 12 columns), then describe how to unpivot it back.

The native tool since 11g is the `PIVOT` operator, which turns distinct values of a column into headings and applies an aggregate to the measure. The key thing to internalize is that `PIVOT` **always aggregates** — you must list the pivoted values explicitly (a static pivot) unless you use `PIVOT XML` for a dynamic set, and any column *not* named in the `SELECT`-feeding inline view becomes an implicit grouping key, which is the single most common reason a pivot "explodes" into more rows than expected.

```sql
-- Pivot: one row per product, a column per quarter
SELECT * FROM (
  SELECT product_id,
         'Q' || TO_CHAR(sale_date,'Q') AS qtr,
         amount
  FROM   sales
)
PIVOT ( SUM(amount) FOR qtr IN ('Q1' AS q1, 'Q2' AS q2,
                                'Q3' AS q3, 'Q4' AS q4) );

-- Unpivot: collapse the 4 columns back into rows
SELECT product_id, qtr, amount
FROM   sales_pivoted
UNPIVOT ( amount FOR qtr IN (q1 AS 'Q1', q2 AS 'Q2',
                             q3 AS 'Q3', q4 AS 'Q4') );
```

The trade-off versus the old-school `SUM(CASE WHEN qtr='Q1' THEN amount END)` manual pivot is readability and a slightly cleaner plan, but the manual `CASE` form is more flexible (you can mix aggregates and apply different filters per column) and works on every Oracle version. `UNPIVOT` by default **excludes NULLs** (`EXCLUDE NULLS` is implicit) — add `INCLUDE NULLS` if a missing measure should still produce a row, a subtlety that bites when you unpivot a sparse matrix and silently lose the empty cells.

#### Q93. [Coding] Write a recursive query to walk an employee-manager hierarchy and show each employee's depth and root-to-node path. Show both `CONNECT BY` and recursive CTE.

Oracle has two ways to do hierarchical traversal. The legacy `CONNECT BY` is compact and gives you pseudo-columns for free — `LEVEL` (depth), `SYS_CONNECT_BY_PATH` (the path string), `CONNECT_BY_ROOT` (the root's value), and `CONNECT_BY_ISLEAF`. The ANSI **recursive CTE** (`WITH ... UNION ALL`) is portable and more flexible for non-tree recursion, but you build the depth and path manually.

```sql
-- CONNECT BY (Oracle-native)
SELECT LPAD(' ', 2*(LEVEL-1)) || last_name AS org,
       LEVEL                                AS depth,
       SYS_CONNECT_BY_PATH(last_name,'/')   AS path,
       CONNECT_BY_ISLEAF                     AS is_leaf
FROM   employees
START WITH manager_id IS NULL
CONNECT BY PRIOR employee_id = manager_id
ORDER SIBLINGS BY last_name;

-- Recursive CTE (ANSI, portable)
WITH org (employee_id, last_name, manager_id, depth, path) AS (
  SELECT employee_id, last_name, manager_id, 1, '/'||last_name
  FROM   employees WHERE manager_id IS NULL
  UNION ALL
  SELECT e.employee_id, e.last_name, e.manager_id,
         o.depth + 1, o.path||'/'||e.last_name
  FROM   employees e JOIN org o ON e.manager_id = o.employee_id
)
SEARCH DEPTH FIRST BY last_name SET ord
SELECT depth, path FROM org ORDER BY ord;
```

The most important production concern with either approach is **cycle protection**: a corrupt row where a manager reports (transitively) to a subordinate sends `CONNECT BY` into `ORA-01436 (loop in user data)`. `CONNECT BY` guards with the `NOCYCLE` keyword (and `CONNECT_BY_ISCYCLE` flags the offending row); the recursive CTE uses the `CYCLE ... SET ... TO ... DEFAULT` clause. Performance-wise, an index on the join column (`manager_id`) is what keeps both from degrading to repeated full scans, and `CONNECT BY` typically wins on raw speed for pure trees while the CTE is preferable when you also need set operations or you care about portability.

#### Q94. [Coding] Write an idempotent `MERGE` (upsert) that inserts new rows, updates changed ones, and deletes rows no longer present — and explain the gotchas.

`MERGE` is the canonical upsert, but a correct one has to handle three subtleties: matching on a key that the source might duplicate, updating only when something actually changed (to avoid touching unchanged rows and bloating redo), and the fact that the optional `DELETE` clause only fires on rows that *matched* the `ON` condition.

```sql
MERGE INTO dim_customer t
USING (
  SELECT cust_id, name, status, region FROM stg_customer
) s
ON (t.cust_id = s.cust_id)
WHEN MATCHED THEN
  UPDATE SET t.name   = s.name,
             t.status = s.status,
             t.region = s.region
  WHERE  DECODE(t.name,  s.name,  0, 1) = 1     -- only if a value differs
      OR DECODE(t.status,s.status,0, 1) = 1
      OR DECODE(t.region,s.region,0, 1) = 1
  DELETE WHERE s.status = 'PURGE'               -- deletes a MATCHED row
WHEN NOT MATCHED THEN
  INSERT (cust_id, name, status, region)
  VALUES (s.cust_id, s.name, s.status, s.region);
```

The biggest landmine is `ORA-30926: unable to get a stable set of rows in the source tables`, which means the source produced **more than one row per target key** — `MERGE` refuses to guess which one wins, so you must dedupe the source (e.g. with `ROW_NUMBER()` keeping the latest) before merging. The `DELETE` clause is *not* a "remove rows absent from the source" operation — it can only delete rows that matched `ON` and then satisfied the `DELETE WHERE`, so a true "delete rows no longer in source" requires a separate anti-join `DELETE` (or a full outer comparison). The `DECODE(...)=1` guard on the `UPDATE` is the cheap idempotency trick: it makes re-running the same source a no-op instead of rewriting every row's redo and bumping `ORA_ROWSCN`.

### 🟡 Intermediate — extended

#### Q95. [Coding] Solve the "gaps and islands" problem: collapse consecutive date ranges (or consecutive integer sequences) into contiguous islands.

Gaps-and-islands is a rite-of-passage SQL problem: given rows that should form contiguous runs (consecutive days a user was active, consecutive ticket numbers), group each run into a single island with its start and end. The elegant trick is the **"difference of two row numbers"**: assign a dense sequence within the partition and subtract it from the value being checked for contiguity — rows in the same run share a constant difference, which becomes the grouping key.

```sql
-- Collapse consecutive active days per user into [start, end] islands
WITH ranked AS (
  SELECT user_id, activity_date,
         activity_date
           - NUMTODSINTERVAL(
               ROW_NUMBER() OVER (PARTITION BY user_id
                                  ORDER BY activity_date), 'DAY') AS grp
  FROM   activity
)
SELECT user_id,
       MIN(activity_date) AS island_start,
       MAX(activity_date) AS island_end,
       COUNT(*)           AS days_in_run
FROM   ranked
GROUP  BY user_id, grp
ORDER  BY user_id, island_start;
```

The mechanics: for a run of consecutive dates, `activity_date` increases by one day each row and `ROW_NUMBER()` also increases by one, so `date - rownum_days` stays constant across the whole run and changes the moment there's a gap — that constant is a synthetic group id. For pure integers it's even simpler: `value - ROW_NUMBER()`. This is far cheaper than a self-join or recursive approach because it's a single sort-based pass, `O(n log n)`. The edge case to call out: **duplicate values** within the partition break the row-number arithmetic (two rows with the same date), so dedupe first or switch to `DENSE_RANK()` if duplicates should be treated as one. From 12c you can also express islands with `MATCH_RECOGNIZE`, which reads more declaratively but compiles to similar work.

#### Q96. [Coding] Use `MATCH_RECOGNIZE` (row pattern matching, 12c+) to detect a pattern — e.g. three consecutive price drops (a "V" or downtrend) in a time series.

`MATCH_RECOGNIZE` brings regex-style pattern matching to rows, which is dramatically more readable than the analytic-function gymnastics it replaces for sequence detection (fraud bursts, sensor anomalies, stock trends, sessionization). You define classifier conditions, then a pattern over them like a regex, and Oracle scans the ordered partition matching greedily.

```sql
-- Find runs of 3+ consecutive falling prices per symbol
SELECT symbol, start_t, bottom_t, drops
FROM   ticks
MATCH_RECOGNIZE (
  PARTITION BY symbol
  ORDER BY     tick_time
  MEASURES  FIRST(DOWN.tick_time) AS start_t,
            LAST(DOWN.tick_time)  AS bottom_t,
            COUNT(DOWN.*)         AS drops
  ONE ROW PER MATCH
  PATTERN ( DOWN{3,} )                       -- 3 or more "down" rows in a row
  DEFINE  DOWN AS price < PREV(price)        -- a row whose price fell
);
```

The power here is the `DEFINE` clause referencing `PREV()`/`NEXT()` and prior matched rows, and the `PATTERN` quantifiers (`+ * ? {n,m}`, alternation `|`) that express "what shape of run am I looking for." `ONE ROW PER MATCH` returns a summary row per match (good for "alert me when a downtrend occurs"); `ALL ROWS PER MATCH` returns every constituent row tagged with its `CLASSIFIER()`, which you want when you need the detail. The interview signal is knowing this exists at all — many engineers reach for procedural PL/SQL cursors to detect sequences, and `MATCH_RECOGNIZE` does it set-based in one pass with the optimizer's help. The caveat is that it's CPU-heavy on huge partitions and the pattern semantics (greedy vs reluctant, `AFTER MATCH SKIP`) need care to avoid overlapping or missed matches.

#### Q97. [Coding] Write a pipelined table function and explain when it beats a regular collection-returning function or a plain view.

A **pipelined table function** streams rows to its caller one at a time via `PIPE ROW`, so the consumer can start processing before the function finishes and the whole result set never materializes in PGA. You query it with the `TABLE()` operator as if it were a table. This is the right tool when you must apply **procedural logic per row that SQL can't express** (calling an external API simulation, complex parsing, fan-out transformations) but still want to consume the output set-based and pipeline it into an `INSERT ... SELECT`.

```sql
CREATE TYPE t_out_row AS OBJECT (id NUMBER, tag VARCHAR2(30));
/
CREATE TYPE t_out_tab AS TABLE OF t_out_row;
/
CREATE OR REPLACE FUNCTION classify(p_cur SYS_REFCURSOR)
  RETURN t_out_tab PIPELINED
IS
  l_id NUMBER; l_amt NUMBER;
BEGIN
  LOOP
    FETCH p_cur INTO l_id, l_amt;
    EXIT WHEN p_cur%NOTFOUND;
    PIPE ROW (t_out_row(l_id, CASE WHEN l_amt > 1000 THEN 'BIG' ELSE 'SMALL' END));
  END LOOP;
  RETURN;                       -- pipelined funcs RETURN with no value
END;
/
-- Stream rows straight into a target table, low PGA footprint
INSERT INTO classified
SELECT * FROM TABLE(classify(CURSOR(SELECT id, amount FROM orders)));
```

The advantage over a function that builds and returns a full nested table is **memory and latency**: pipelining caps PGA at roughly one row of buffering and lets parallel query distribute the work (with `PARALLEL_ENABLE (PARTITION p_cur BY ...)`). Versus a plain view, you choose a pipelined function only when the transformation genuinely needs PL/SQL — a view is always preferable when pure SQL suffices, because the optimizer can merge, push predicates into, and rewrite a view, whereas a table function is largely a black box (it can't push your `WHERE` predicate inside, so you may scan more than necessary). The honest trade-off: pipelined functions are powerful glue for ETL but they defeat optimizer transformations, so don't reach for them when set-based SQL or a view will do.

#### Q98. [Coding] Generate and query JSON in Oracle (19c/21c/23ai): build a JSON document from relational rows and query a JSON column back into columns.

Modern Oracle is a competent document store. On the generation side, `JSON_OBJECT`, `JSON_ARRAYAGG`, and `JSON_OBJECTAGG` build JSON directly in SQL, so you can serve an API payload straight from a join without app-side assembly. On the shredding side, `JSON_TABLE` projects a JSON document into relational columns, and `JSON_VALUE`/`JSON_QUERY` extract scalars/fragments via SQL/JSON path expressions.

```sql
-- Build a nested JSON document per customer with an array of orders
SELECT JSON_OBJECT(
         'customerId' VALUE c.cust_id,
         'name'       VALUE c.name,
         'orders'     VALUE (
            SELECT JSON_ARRAYAGG(
                     JSON_OBJECT('id' VALUE o.id, 'amt' VALUE o.amount)
                     ORDER BY o.id)
            FROM   orders o WHERE o.cust_id = c.cust_id)
       RETURNING CLOB) AS doc
FROM   customers c;

-- Shred a JSON column back into rows/columns
SELECT jt.id, jt.amt
FROM   raw_events e,
       JSON_TABLE(e.payload, '$.orders[*]'
         COLUMNS ( id  NUMBER PATH '$.id',
                   amt NUMBER PATH '$.amt' )) jt;
```

The design considerations: storage of JSON should use a `JSON` type (21c+ native binary OSON, which is far faster to query than parsing text) or at minimum a `CLOB`/`BLOB` with an `IS JSON` check constraint on older versions. For query performance you index JSON paths with a **function-based index on `JSON_VALUE`** for a hot scalar, or a **multi-value index** (21c+) for array predicates, or a **search index** for ad-hoc full-document queries. The architectural trade-off worth voicing: JSON columns buy schema flexibility but you lose the optimizer's column statistics and referential integrity, so the mature pattern is *hybrid* — keep the relational core normalized and use JSON for genuinely variable, sparse, or externally-shaped attributes, not as a lazy substitute for modeling. 23ai's **JSON Relational Duality Views** formalize exactly this: one document API over a normalized store.

#### Q99. [Practical] Design a soft-delete / temporal-history strategy for a core table. Compare a status flag, a separate history table, and Flashback Data Archive.

"Soft delete" and "keep history" are different requirements that get conflated, and the right design depends on which you actually need. The simplest is a **`deleted_at` / `is_active` flag**: rows are never physically removed, queries filter on the flag. It's trivial to implement but it pollutes every query (forget the predicate and you leak deleted data — a real security bug), it bloats the hot table and its indexes with dead rows, and a partial/function-based index or a VPD policy is needed to keep the live-row queries fast and safe.

A **separate history table** (current row in the main table, prior versions copied to `_hist` by a row trigger or by the application) keeps the operational table lean and lets you tune history storage independently (compress it, partition it by time, move it to cheaper storage). The cost is write amplification and the discipline of keeping the trigger correct; it's the workhorse for SCD-style auditing.

| Approach | Query impact | Storage | Point-in-time "as of" | Best for |
|---|---|---|---|---|
| Status flag | Every query needs filter | Bloats hot table | No | Cheap recoverable delete |
| History table | Clean main table | Extra, tunable | Manual (join on validity) | Audit trail, SCD2 |
| Flashback Data Archive | Transparent | Auto, compressed | `AS OF` built-in | Compliance/regulatory retention |

The heavyweight, often-overlooked option is **Flashback Data Archive (FDA / "Total Recall")**: you mark a table as tracked and Oracle transparently retains its history in a managed archive, queryable with the ordinary `AS OF TIMESTAMP` / `VERSIONS BETWEEN` syntax with **no application changes and no triggers**. It's purpose-built for regulatory retention (it can even enforce that history can't be tampered with). The trade-off is operational complexity and that it's an Oracle-specific feature, but for "I must prove the state of this record on any past date for seven years," FDA beats hand-rolled history tables on both correctness and developer effort.

#### Q100. [Practical] Design a multi-tenant schema in Oracle. Compare separate databases (PDBs), separate schemas, and a shared schema with a tenant-id column.

Multi-tenancy is a spectrum of isolation-vs-density trade-offs, and Oracle gives you a strong option at each end. **PDB-per-tenant** (multitenant container database) gives the hardest isolation — each tenant is a fully separate pluggable database with its own users, objects, and even point-in-time recovery, clonable and pluggable in seconds. It's ideal for enterprise customers who demand data separation, independent backups, and per-tenant upgrades, and it scales to thousands of PDBs, but per-tenant overhead (SGA structures, background work) makes it heavy for a long tail of tiny tenants.

**Schema-per-tenant** packs many tenants in one database, each owning a schema with identical object names. Isolation is via Oracle's privilege model, density is much higher than PDBs, and you can still back up or export one tenant's schema. The pain is operational: deploying a DDL change means applying it to N schemas, and the data dictionary grows with object count.

```sql
-- Shared-schema isolation enforced transparently with VPD (RLS)
CREATE OR REPLACE FUNCTION tenant_pred(p_schema VARCHAR2, p_obj VARCHAR2)
  RETURN VARCHAR2 IS
BEGIN
  RETURN 'tenant_id = SYS_CONTEXT(''APP_CTX'',''TENANT_ID'')';
END;
/
BEGIN
  DBMS_RLS.ADD_POLICY(
    object_schema => 'APP', object_name => 'ORDERS',
    policy_name   => 'tenant_iso',
    function_schema => 'APP', policy_function => 'TENANT_PRED',
    statement_types => 'SELECT,INSERT,UPDATE,DELETE');
END;
/
```

**Shared schema with a `tenant_id` column** is the highest-density, lowest-isolation end — every tenant's rows live in the same tables, partitioned by tenant. The decisive design rule is to **never rely on the application to add `WHERE tenant_id = :x`** (one forgotten predicate is a cross-tenant data breach); instead enforce it in the database with **VPD/Row-Level Security** driven by a session context, so the filter is appended to *every* statement automatically and centrally. Pair it with **partitioning or list-partitioning by tenant_id** so a large tenant's data prunes cleanly and you can move/archive a tenant. The general guidance: PDBs for a small number of high-value, high-isolation tenants; shared-schema-plus-VPD for SaaS scale with many small tenants; schema-per-tenant as a middle ground when you need per-tenant export but not full PDB isolation.

### 🟠 Advanced — extended

#### Q101. [Coding] Implement a robust deduplication of a 200M-row table keeping the newest record per natural key, doing it online without a long lock.

The naive `DELETE` of duplicates by self-join or `ROWID` works on small tables but on 200M rows it generates enormous undo/redo, holds locks, and can run for hours. The robust pattern is **build-the-clean-copy-then-swap**: create the deduplicated result with a fast direct-path `CREATE TABLE AS SELECT`, then atomically replace the original. Within the CTAS you rank by the natural key and keep `ROW_NUMBER() = 1`.

```sql
-- 1. Build a clean, deduplicated copy (direct-path, NOLOGGING optional)
CREATE TABLE orders_dedup
  NOLOGGING PARALLEL 8
AS
SELECT id, cust_id, amount, load_ts
FROM (
  SELECT o.*,
         ROW_NUMBER() OVER (PARTITION BY cust_id, amount      -- natural key
                            ORDER BY load_ts DESC, rowid)      -- newest wins
           AS rn
  FROM   orders o
)
WHERE rn = 1;

-- 2. Rebuild indexes/constraints/grants on the new table, then swap
ALTER TABLE orders      RENAME TO orders_old;
ALTER TABLE orders_dedup RENAME TO orders;
-- 3. Validate, then DROP orders_old PURGE;
```

Why this beats an in-place `DELETE`: a direct-path CTAS writes above the high-water mark with minimal undo and can run in parallel and `NOLOGGING` (forcing a fresh backup afterward, since `NOLOGGING` data is unrecoverable from redo — Q75), so it's often an order of magnitude faster and the original stays fully available for reads the whole time. If even the brief rename window is unacceptable, do the swap with **`DBMS_REDEFINITION`** (online redefinition), which keeps the table queryable and writable throughout and applies interim DML via a materialized-view-log mechanism. The `ORDER BY load_ts DESC, rowid` tiebreaker is the detail that makes "keep newest" deterministic even when two duplicates share the same timestamp — without a stable tiebreaker the kept row is arbitrary across runs.

#### Q102. [Coding] Write a safe dynamic SQL routine that builds a query from user-supplied filter and sort inputs without opening a SQL-injection hole.

Dynamic SQL is unavoidable for generic search/filter screens, but it's also the number-one SQL-injection vector. The rule is: **values go in as bind variables, never concatenated; identifiers (table/column names, sort direction) must be validated, never trusted.** You cannot bind an identifier, so for the column name and direction you whitelist or pass them through `DBMS_ASSERT`.

```sql
CREATE OR REPLACE FUNCTION search_orders(
  p_status  VARCHAR2,
  p_min_amt NUMBER,
  p_sort_col VARCHAR2,                 -- identifier, cannot be bound
  p_dir      VARCHAR2 DEFAULT 'ASC'
) RETURN SYS_REFCURSOR IS
  l_cur  SYS_REFCURSOR;
  l_col  VARCHAR2(30);
  l_dir  VARCHAR2(4);
  l_sql  VARCHAR2(4000);
BEGIN
  -- validate identifier against the data dictionary (whitelist via ASSERT)
  l_col := DBMS_ASSERT.SIMPLE_SQL_NAME(p_sort_col);
  l_dir := CASE UPPER(p_dir) WHEN 'DESC' THEN 'DESC' ELSE 'ASC' END;

  l_sql := 'SELECT * FROM orders WHERE status = :s '
        || 'AND amount >= :a ORDER BY '|| l_col ||' '|| l_dir;

  OPEN l_cur FOR l_sql USING p_status, p_min_amt;   -- values are BOUND
  RETURN l_cur;
END;
/
```

The anatomy of safety here: `status` and `amount` are *values*, so they go through the `USING` clause as binds — this both prevents injection and lets the cursor be shared (one parsed statement reused across millions of searches, avoiding the hard-parse storm of Q18). The sort column is an *identifier* that the user controls, so concatenating it raw would let an attacker append `1; DROP TABLE ...`; `DBMS_ASSERT.SIMPLE_SQL_NAME` (or a `CASE`/lookup whitelist against `USER_TAB_COLUMNS`) guarantees it's a legal, expected identifier before it reaches the string. The sort direction is constrained to a closed set with a `CASE`. The deeper principle for an interview: the moment you concatenate *anything* into SQL, prove it's an identifier from a finite trusted set — anything that's data must be a bind, full stop.

#### Q103. [Coding] Compute a running balance and detect when an account goes negative, using analytic functions with a frame — and explain why `RANGE` vs `ROWS` matters here.

A running balance is `SUM(delta) OVER (ORDER BY ...)`, but the frame clause (`ROWS` vs `RANGE`) is where people quietly get wrong answers. `ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW` sums a fixed number of physical rows; `RANGE` sums all rows whose **ordering value ties** with the current row. If two transactions share the same timestamp, `RANGE` lumps them into the same frame and reports the *same* post-batch balance for both, whereas `ROWS` gives a distinct per-transaction balance.

```sql
SELECT account_id, txn_time, delta,
       SUM(delta) OVER (PARTITION BY account_id
                        ORDER BY txn_time, txn_id          -- tiebreaker!
                        ROWS BETWEEN UNBOUNDED PRECEDING
                                 AND CURRENT ROW)  AS balance,
       CASE WHEN SUM(delta) OVER (PARTITION BY account_id
                        ORDER BY txn_time, txn_id
                        ROWS BETWEEN UNBOUNDED PRECEDING
                                 AND CURRENT ROW) < 0
            THEN 'OVERDRAWN' END                   AS flag
FROM   ledger
ORDER  BY account_id, txn_time, txn_id;
```

The critical detail is the **deterministic ordering**: `ORDER BY txn_time` alone is ambiguous when timestamps tie, and a non-deterministic order makes the running balance non-reproducible across runs — adding a unique tiebreaker (`txn_id`) and using `ROWS` makes each row's balance the true balance *after that specific transaction*. This matters intensely for financial correctness: a `RANGE` frame on a tied timestamp would make every transaction in the same instant report the batch-end balance, hiding a transient overdraft that actually occurred. The performance note: the window sort is `O(n log n)` per partition, and an index on `(account_id, txn_time, txn_id)` lets the optimizer satisfy the `ORDER BY` without a separate sort, turning the analytic into a cheap streaming operation.

#### Q104. [Coding] Use the model clause or a recursive CTE to generate a dense date series and left-join sparse data onto it (filling missing days with zero).

Reports almost always need a *dense* axis — every day present, even days with no sales — but transactional data is sparse. The pattern is to generate the calendar spine and `LEFT JOIN` the facts onto it, defaulting nulls to zero. Generating the spine is itself a small interview classic; the connect-by-level trick and the recursive CTE both work.

```sql
-- Dense daily spine via CONNECT BY, left-joined to sparse sales
WITH cal AS (
  SELECT DATE '2026-01-01' + LEVEL - 1 AS d
  FROM   dual
  CONNECT BY LEVEL <= DATE '2026-01-31' - DATE '2026-01-01' + 1
)
SELECT cal.d,
       NVL(SUM(s.amount), 0) AS revenue
FROM   cal
LEFT   JOIN sales s
       ON s.sale_date >= cal.d AND s.sale_date < cal.d + 1
GROUP  BY cal.d
ORDER  BY cal.d;
```

The reason you build the spine instead of `GROUP BY sale_date` is that a plain group-by can only produce rows for dates that *have* data — a zero-sales day simply vanishes, which breaks moving averages, charts, and "did we sell nothing or did the ETL fail?" alerting. The half-open join predicate (`>= d AND < d+1`) is the correct, index-friendly way to bucket timestamps into a day without wrapping the column in `TRUNC()` (which would disable a range index on `sale_date`). For multi-dimensional gap filling (every product × every day) you cross-join the calendar with the product dimension first, then left-join facts — and for genuinely complex inter-row calculations (forecasting, what-if cells) the `MODEL` clause lets you address cells like a spreadsheet, though it's notoriously hard to read and most teams stop at the spine-plus-left-join pattern.

#### Q105. [Practical] Design an exactly-once outbox/CDC pattern in Oracle to publish domain events to a downstream system reliably.

The hard problem in event-driven systems is the **dual-write**: you commit a business change *and* publish an event, and a crash between the two corrupts state. The robust Oracle solution is the **transactional outbox**: write the event into an `outbox` table **in the same transaction** as the business change, so they commit atomically — there is no window where one succeeds without the other. A separate relay process then reads unpublished outbox rows, sends them downstream, and marks them sent.

```sql
-- Business change and event commit together (one transaction = atomic)
UPDATE orders SET status = 'SHIPPED' WHERE id = :id;
INSERT INTO outbox (event_id, aggregate_id, type, payload, created_at, sent)
VALUES (SYS_GUID(), :id, 'OrderShipped',
        JSON_OBJECT('orderId' VALUE :id, 'status' VALUE 'SHIPPED'),
        SYSTIMESTAMP, 'N');
COMMIT;

-- Relay: claim a batch without double-sending (SKIP LOCKED = concurrent relays)
SELECT event_id, payload FROM outbox
WHERE  sent = 'N'
ORDER  BY created_at
FOR UPDATE SKIP LOCKED FETCH FIRST 500 ROWS ONLY;
-- ...publish each... then: UPDATE outbox SET sent='Y' WHERE event_id IN (...); COMMIT;
```

The key Oracle mechanics: `FOR UPDATE SKIP LOCKED` lets you run **multiple relay workers in parallel** without them fighting over the same rows or blocking each other — each worker grabs a disjoint batch, which is how you scale the publisher. Downstream you get *at-least-once* delivery (a crash after send-before-mark replays the event), so consumers must be **idempotent**, keyed on the stable `event_id` — true exactly-once is achieved at the consumer by dedup, not by the broker. The native alternative is **Transactional Event Queues (TxEventQ / AQ)**, Oracle's built-in queuing that's transactional with the database and speaks a Kafka-compatible API in 21c+, removing the hand-rolled relay entirely; the outbox-table pattern is what you reach for when the downstream is an external broker (Kafka) and you can't make the enqueue part of the DB transaction.

#### Q106. [Practical] Design a partitioning + ILM strategy for a 10 TB fact table that must keep 7 years online but cheaply. Walk the full design.

A 10 TB, 7-year fact table is a classic Information Lifecycle Management problem: hot recent data must be fast, ancient data must be cheap, and the whole thing must stay queryable and maintainable. The foundation is **range partitioning by time** (interval-partitioned by month or day) so the optimizer prunes to the relevant partitions and you can manage each partition independently — drop old data with an instant `DROP PARTITION` instead of a multi-hour `DELETE`.

```sql
CREATE TABLE fact_sales (
  sale_id NUMBER, sale_date DATE, region VARCHAR2(10), amount NUMBER
)
PARTITION BY RANGE (sale_date)
INTERVAL (NUMTOYMINTERVAL(1,'MONTH'))
( PARTITION p_seed VALUES LESS THAN (DATE '2019-01-01') )
ROW STORE COMPRESS ADVANCED;        -- OLTP compression on current data

-- Heat-map driven ILM: auto-tier cold partitions to higher compression
ALTER TABLE fact_sales ILM ADD POLICY
  COLUMN STORE COMPRESS FOR QUERY HIGH
  SEGMENT AFTER 6 MONTHS OF NO MODIFICATION;
```

The tiering strategy layers compression and storage by age. Current partitions use **Advanced Row compression** (write-friendly, modest ratio) so OLTP-style updates stay cheap. Partitions past a no-modification threshold are recompressed with **Hybrid Columnar Compression (HCC) "Query High" or "Archive"** via Automatic Data Optimization policies driven by the **Heat Map** (Oracle tracks last-access/modification per segment and applies the policy automatically) — HCC can hit 10–50x on cold data, turning 10 TB into something far smaller. Oldest partitions can be moved to cheaper storage tiers (or read-only tablespaces, which shrink backups since RMAN skips unchanged read-only files). The maintenance design: use **local indexes** so partition operations don't invalidate global indexes (or `UPDATE INDEXES` when you must keep globals), keep statistics fresh with **incremental statistics** (Q40) so adding a partition doesn't force a full-table re-gather, and rehearse the monthly "add new partition / compress N-month-old / drop 7-year-old" cycle as an automated job. The result: recent queries are fast and uncompressed-cheap to update, historical queries still work transparently, and 7 years fits in a fraction of the raw footprint.

#### Q107. [Practical] Design a queue table that thousands of workers poll concurrently without lock convoys or skipped/duplicated work.

A naive "poll the table for the next pending job" design collapses under concurrency: every worker locks the same top row, they serialize into a **lock convoy**, and clumsy retry logic causes either missed jobs or the same job processed twice. The Oracle-native fix is `SELECT ... FOR UPDATE SKIP LOCKED`, which atomically claims rows *and steps over rows another worker already locked*, so N workers fan out across N distinct jobs with zero blocking.

```sql
-- Each worker claims its own jobs; no two workers get the same row, no waiting
SELECT job_id, payload
FROM   job_queue
WHERE  state = 'READY'
ORDER  BY priority, enqueued_at
FOR UPDATE SKIP LOCKED
FETCH  FIRST 10 ROWS ONLY;
-- process, then within the same txn:
UPDATE job_queue SET state='DONE', done_at=SYSTIMESTAMP WHERE job_id IN (...);
COMMIT;
```

The reasons this is robust: the row lock *is* the claim, held under the transaction, so a worker that crashes mid-job has its lock released by Oracle and the job becomes claimable again — no orphaned "in-progress forever" rows, no separate lease table to reconcile. `SKIP LOCKED` is what eliminates the convoy: instead of waiting on a held row, a worker immediately moves to the next free one, so throughput scales with worker count rather than collapsing. The design caveats worth raising: index the `(state, priority, enqueued_at)` predicate so claiming doesn't full-scan a growing table, **archive or partition completed jobs** (a `DONE` row left in place forever turns the queue into a slow-to-scan graveyard — partition by state or sweep to a history table), and add a **visibility-timeout / max-attempts** column so a job that repeatedly fails is dead-lettered rather than retried forever. If this is a first-class need, Oracle's **Transactional Event Queues (TxEventQ)** give you all of this — ordering, dequeue-by-correlation, retry/exception queues, multi-consumer — as a managed feature rather than a hand-built table, and it's transactional with your business data.

#### Q108. [Coding] Write a `PIVOT`-free conditional aggregation report and add `ROLLUP`/`CUBE`/`GROUPING SETS` subtotals — explain `GROUPING_ID`.

For a report that needs both a cross-tab *and* subtotals, conditional aggregation (`SUM(CASE ...)`) plus a super-aggregate grouping extension is more flexible than `PIVOT`, because `PIVOT` can't emit subtotal rows. `ROLLUP(a,b)` produces subtotals along a hierarchy (per b within a, per a, and grand total); `CUBE(a,b)` produces every combination of subtotals; `GROUPING SETS` lets you name exactly the aggregation levels you want, avoiding `CUBE`'s combinatorial blow-up.

```sql
SELECT region, product,
       SUM(CASE WHEN channel='WEB'   THEN amount END) AS web,
       SUM(CASE WHEN channel='STORE' THEN amount END) AS store,
       SUM(amount)                                    AS total,
       GROUPING_ID(region, product)                   AS gid   -- which level?
FROM   sales
GROUP  BY ROLLUP (region, product)
ORDER  BY GROUPING(region), GROUPING(product), region, product;
```

The subtle part interviewers probe is telling a **real NULL apart from a subtotal NULL**. A `ROLLUP` subtotal row carries `NULL` in the rolled-up column, which is indistinguishable from a row that genuinely had `NULL` in that column — `GROUPING(col)` returns 1 when the NULL is a subtotal marker and 0 when it's real data, and `GROUPING_ID(a,b)` packs those flags into a single integer (0 = detail, 1 = subtotal on b, 2 = subtotal on a, 3 = grand total) that's perfect for sorting subtotals to the right place and for a `CASE` that labels them ("Region Total", "Grand Total"). The performance advantage over running multiple `UNION ALL` aggregations at different levels is that `ROLLUP`/`GROUPING SETS` computes all the levels in **one pass** over the data with a single sort/hash, instead of re-scanning the fact table once per level — on a large fact table that's the difference between one expensive aggregation and four.

### 🔴 Expert — extended

#### Q109. [Coding] Implement a server-side result cache and a deterministic function, and explain when each silently returns stale or wrong answers.

Oracle has two caching layers people confuse. The **result cache** (`/*+ RESULT_CACHE */` on a query, or `RESULT_CACHE` on a PL/SQL function) stores the *final result* in the SGA and serves it to subsequent identical calls without re-executing — and crucially it's **automatically invalidated when any dependent table changes**, so it's safe for read-mostly lookups. A `DETERMINISTIC` function, by contrast, only *promises* the optimizer that the same inputs always yield the same output, allowing it to cache repeated calls *within a statement* — but Oracle does **not verify** the promise.

```sql
-- Result cache: safe, auto-invalidated on base-table DML
CREATE OR REPLACE FUNCTION tax_rate(p_region VARCHAR2) RETURN NUMBER
  RESULT_CACHE IS
  l NUMBER;
BEGIN
  SELECT rate INTO l FROM tax WHERE region = p_region;   -- dependency tracked
  RETURN l;
END;
/

-- DETERMINISTIC: a PROMISE Oracle trusts but never checks
CREATE OR REPLACE FUNCTION fx(p NUMBER) RETURN NUMBER DETERMINISTIC IS
BEGIN
  RETURN p * (SELECT spot FROM rates WHERE id=1);  -- WRONG: depends on a table!
END;
/
```

The trap with `DETERMINISTIC` is exactly the second example: marking a function deterministic when it actually reads a table (or `SYSDATE`, or a package variable) is a lie the optimizer believes — it may call the function once and reuse the result, so if the underlying `rates` row changes mid-query you get a **stale value with no error**, and worse, a `DETERMINISTIC` function is also what a function-based index caches at *index-build* time, so an index over a non-deterministic "deterministic" function silently returns wrong rows forever. The discipline: use `RESULT_CACHE` for table-backed lookups (it's correctness-safe because dependencies are tracked), and reserve `DETERMINISTIC` strictly for pure functions of their arguments (math, string formatting). The result cache's own caveat is contention — a very high-frequency cache with frequent base-table DML thrashes the `Result Cache: RC Latch`, so it suits *read-mostly reference data*, not a hot, constantly-changing table.

#### Q110. [Coding] Demonstrate scalar subquery caching and how it can both speed up and silently break a query.

Oracle caches the results of a **scalar subquery** within a single statement execution: for repeated input values it reuses the previously computed result instead of re-running the subquery, which can turn an `O(n)` per-row lookup into far fewer executions. This is a free optimization that often makes a correlated scalar subquery competitive with a join — but it relies on the subquery being *functionally deterministic for a given input*, and it caches only a finite hash of recent inputs, so behavior is workload-dependent.

```sql
-- Scalar subquery cache: dim lookup runs once per DISTINCT region, not per row
SELECT o.order_id,
       (SELECT d.region_name FROM dim_region d
        WHERE d.region_id = o.region_id) AS region   -- cached by region_id
FROM   orders o;
```

Where it speeds things up: if `orders` has 10M rows but only 50 distinct `region_id` values, the subquery may execute ~50 times instead of 10M, because the cache short-circuits repeats — this is why an "obviously slow" correlated subquery sometimes outruns the equivalent join. Where it silently breaks: if the subquery is **non-deterministic** — it calls `DBMS_RANDOM`, `SYSTIMESTAMP`, a sequence's `NEXTVAL`, or a function with side effects — the cache means it runs *fewer* times than there are rows, so you get a `NEXTVAL` reused across many rows or a "random" value repeated, which is almost never what the author intended. The expert nuance is that scalar subquery caching is **not guaranteed** (it's a hash cache of bounded size, so with many distinct inputs you can still get many executions, and cache hit rate is not contractual), so you must never *rely* on it for either performance (use a join if you need a guarantee) or for limiting how often a side-effecting subquery runs (it's undefined). It's an optimization to exploit when you understand it and a footgun when you assume it behaves like a real once-per-value guarantee.

#### Q111. [Practical] Explain fine-grained dependency tracking and how a careless `SELECT *` or column add can invalidate cursors and cause a latch storm on a busy system.

Oracle tracks dependencies between objects so that changing a referenced object invalidates the things that depend on it. Since 11g this is **fine-grained**: adding a column to a table no longer invalidates a view or PL/SQL unit that doesn't reference the new column, because dependency is tracked at the **column/element level**, not the whole-object level — this dramatically reduces recompilation churn from routine schema additions. The subtlety is what *defeats* it: a unit that does `SELECT *` or `INSERT` without a column list depends on the **entire row shape**, so any column change to that table invalidates it even though "nothing it uses changed."

```
 Coarse (pre-11g):  ALTER TABLE add col  ──► invalidates ALL dependents
 Fine-grained 11g+: ALTER TABLE add col  ──► invalidates ONLY units that
                                              reference the changed columns
        BUT  SELECT *  / INSERT w/o column list
             depend on the whole row  ──► still invalidated by any add
```

On a quiet system invalidation is invisible — the next execution hard-parses and revalidates. On a **busy** system it's dangerous: a DDL during peak load invalidates a swath of cursors at once, and every concurrent session that next executes one of them must **hard-parse simultaneously**, all contending for the **library cache mutex / `cursor: pin S wait on X`** — a self-inflicted parse storm that can stall the database far longer than the DDL itself took. This is why online DDL is scheduled in quiet windows and why editioning (EBR, Q50) exists: it lets you stage the new definition and switch atomically without invalidating live sessions. The actionable lessons: avoid `SELECT *` and column-less `INSERT` in stored code precisely because they widen the dependency footprint and turn benign column adds into mass invalidations; and treat any DDL on a hot object as a potential parse-storm trigger, not a free metadata change.

#### Q112. [Coding] Write a polymorphic table function (21c) or a generic ETL transform, and explain why it's safer than building SQL by string concatenation.

Polymorphic Table Functions (PTFs, 18c/19c+) let you write a table function whose **output shape is determined at parse time from the input table**, so you can build genuinely generic transforms (drop columns, add computed columns, transpose) that the optimizer still understands as a row source — without the string-concatenation dynamic SQL that those generic transforms used to require. You implement a `DESCRIBE` method that declares the output columns and a `FETCH_ROWS` method that produces data.

```sql
-- A PTF that passes through a table but adds a row-hash column
CREATE PACKAGE add_hash_pkg AS
  FUNCTION describe(tab IN OUT DBMS_TF.TABLE_T)
    RETURN DBMS_TF.DESCRIBE_T;
  PROCEDURE fetch_rows;
END;
/
CREATE PACKAGE BODY add_hash_pkg AS
  FUNCTION describe(tab IN OUT DBMS_TF.TABLE_T)
    RETURN DBMS_TF.DESCRIBE_T IS
  BEGIN
    RETURN DBMS_TF.DESCRIBE_T(
      new_columns => DBMS_TF.COLUMNS_NEW_T(
        1 => DBMS_TF.COLUMN_METADATA_T(name=>'ROW_HASH',
                                       type=>DBMS_TF.TYPE_VARCHAR2)));
  END;
  PROCEDURE fetch_rows IS /* read DBMS_TF.GET_*, compute, PUT_COL */ BEGIN NULL; END;
END;
/
CREATE FUNCTION add_hash(t TABLE) RETURN TABLE PIPELINED
  ROW POLYMORPHIC USING add_hash_pkg;
/
-- Used like a table; output columns known to the optimizer
SELECT * FROM add_hash(orders);
```

The reason this is safer than the old approach (a procedure that string-builds `SELECT col1, col2, ..., my_hash AS row_hash FROM ...` and `EXECUTE IMMEDIATE`s it) is twofold. First, **security**: concatenating column names and shapes into SQL is the same injection/identifier-trust hazard as Q102, whereas a PTF receives the table metadata through a typed, structured API (`DBMS_TF.TABLE_T`) — there is no string to inject into. Second, **optimizability and correctness**: dynamically-built SQL is opaque to the optimizer and brittle when schemas change, while a PTF is a first-class row source the optimizer can plan around, with its output columns validated at parse time so a downstream `SELECT` referencing a non-existent column fails to compile rather than at runtime. The trade-off is that PTFs have a steeper API and aren't worth it for one-off transforms — but for a *reusable, schema-generic* operator (a masking transform applied to many tables, a pivot that adapts to its input), they replace fragile dynamic-SQL generators with a maintainable, secure, optimizer-friendly construct.

#### Q113. [Practical] A correctly-bound query still has two wildly different good plans for two bind values. Design a solution that gives each its right plan without hints in app code.

This is the **bind-sensitive / skewed-predicate** problem: a query like `WHERE status = :s` is genuinely fast as an index range scan when `:s = 'PENDING'` (0.1% of rows) and genuinely fast as a full scan when `:s = 'CLOSED'` (95% of rows) — there is no single best plan, so freezing one penalizes the other. Bind variables solved the parse-storm problem but reintroduced this through **bind peeking**: the first execution's value picks the plan, and subsequent executions with the opposite skew inherit the wrong one.

The designed answer is to let Oracle maintain **multiple child cursors** via **Adaptive Cursor Sharing (ACS)**, which is on by default: after observing that a predicate is bind-sensitive (a histogram on `status` tells it the column is skewed), Oracle marks the cursor *bind-aware* and builds a separate child cursor per selectivity range, peeking and pairing each bind value to the appropriate plan. The prerequisite you must ensure is that a **histogram exists on the skewed column** — without it the optimizer assumes uniform distribution, never realizes the binds matter, and ACS never kicks in.

```sql
-- 1. Make sure the skewed column has a histogram so ACS can detect sensitivity
EXEC DBMS_STATS.GATHER_TABLE_STATS('APP','ORDERS', -
       method_opt => 'FOR COLUMNS SIZE AUTO status');

-- 2. Confirm the cursor went bind-aware and spawned plan-specific children
SELECT sql_id, child_number, is_bind_sensitive, is_bind_aware,
       plan_hash_value, executions
FROM   v$sql WHERE sql_id = '&sqlid';
```

If ACS still picks wrong for an outlier value, the next escalation — still without touching application code — is **SQL Plan Management**: capture both good plans into a **SQL Plan Baseline** so the optimizer is allowed to choose between the verified-good alternatives but never a regression. The deeper architectural option, when one literal value is *always* an extreme outlier, is to deliberately **not bind that predicate** (use a literal for the skewed column while binding everything else, or `CURSOR_SHARING` exceptions) so each value gets its own peeked plan — a rare case where mixing a literal into otherwise-bound SQL is correct. The point for the interview: the modern, code-free toolchain is *histogram → ACS → baseline*, and reaching for an inline hint means you've skipped the mechanisms designed precisely for this.

#### Q114. [Behavioral] Tell me about a time a production database incident was caused by your team's change, and how you led the response and the follow-up.

I answer this with **STAR**, and the signal I aim to show is calm ownership, blameless rigor, and durable systemic fixes — not heroics. **Situation:** a routine index "cleanup" release dropped an index that an overnight batch secretly depended on; the batch's plan flipped to a full scan, it ran past the business window, and downstream settlement files were late — a customer-visible, money-adjacent impact. **Task:** as the senior engineer on call I owned both the immediate restoration and making sure it couldn't recur.

**Action:** I first stabilized rather than investigated — I confirmed the regression with `DBMS_XPLAN.DISPLAY_CURSOR` showing the plan flip and the `A-Rows` blowup (Q7), recreated the dropped index `ONLINE` to restore the original plan, and verified the batch caught up, communicating status to stakeholders on a steady cadence so the business wasn't guessing. Only once it was stable did I move to root cause. I didn't stop at "we dropped an index": I asked *why our process let an index with live dependents look unused* — the answer was that we'd checked `DBA_INDEXES` but not index usage monitoring over a full business cycle, so a month-end-only index looked dead.

**Result and follow-up:** the durable fixes were systemic, not personal — we made **index drops require evidence from `V$INDEX_USAGE` / index usage tracking across a full monthly cycle**, we started marking candidate indexes **`INVISIBLE` first** (Q71) so a "drop" is instantly reversible without a rebuild, and we added the critical batch's plan to a **SQL Plan Baseline** so a missing index degrades gracefully instead of flipping. I wrote the **blameless postmortem** myself — naming the process gap, not the engineer who ran the script — because the lesson interviewers look for is that a senior person converts an incident into guardrails the whole team inherits, and models that being honest about your own change's blast radius is safe.

#### Q115. [Coding] Write SQL/PLSQL to detect and resolve duplicate-but-not-identical rows (fuzzy/business-key duplicates), and design the survivorship rules.

Hard duplicates (identical rows) are easy; the real data-quality problem is **business-key duplicates** that disagree on attributes — the same customer entered twice with a different phone number or a typo in the name. Detection requires defining the *match key* (often a normalized composite: lowercased email, or `SOUNDEX`/`UTL_MATCH` similarity on name + same DOB), and resolution requires explicit **survivorship rules** deciding which value of each conflicting attribute survives into the golden record.

```sql
-- Detect business-key dup groups and rank survivors by a quality policy
WITH norm AS (
  SELECT cust_id, LOWER(TRIM(email)) AS k_email, name, phone, updated_at,
         CASE WHEN phone IS NOT NULL THEN 1 ELSE 0 END
            + CASE WHEN name  IS NOT NULL THEN 1 ELSE 0 END AS completeness
  FROM   customers
),
grp AS (
  SELECT n.*,
         COUNT(*)     OVER (PARTITION BY k_email) AS dup_cnt,
         ROW_NUMBER() OVER (PARTITION BY k_email
                            ORDER BY completeness DESC,   -- most complete wins
                                     updated_at  DESC,    -- then most recent
                                     cust_id      ASC)    -- stable tiebreaker
            AS survivor_rank
  FROM   norm n
)
SELECT * FROM grp WHERE dup_cnt > 1 ORDER BY k_email, survivor_rank;
-- survivor_rank = 1 is the golden record; merge others' non-null fields into it,
-- repoint foreign keys, then soft-delete the losers.
```

The design discussion is where seniority shows. Survivorship is a **policy decision, not a SQL trick**: "most recently updated wins" is wrong when a recent edit blanked a field, so a better rule is **field-level survivorship** — for each attribute keep the most-complete/most-trusted source value, which can mean the golden record is a *composite* of several duplicates rather than any single surviving row. The fuzzy-match step (`UTL_MATCH.JARO_WINKLER_SIMILARITY` for names, normalization of phones/emails before comparison) determines recall vs precision — too loose and you merge two real people, which is far worse than missing a dup. Operationally the merge must be **transactional and reversible**: repoint child foreign keys to the survivor, record the merge in an audit table (so a wrong merge can be undone), and soft-delete rather than hard-delete the losers. The lesson worth stating: dedup is 20% SQL and 80% agreeing on the business rules for which data is authoritative — the query is easy once survivorship is defined.

#### Q116. [Practical] Design a defensible test/QA strategy for a large PL/SQL codebase: unit tests, mocking, and CI for database code.

Database code is notoriously under-tested because people treat it as configuration rather than software, then pay for it with production regressions. A defensible strategy starts with a **unit-test framework** — **utPLSQL** is the de facto standard — that runs assertions inside the database, so you test packages the way you test application code: arrange a known fixture, call the procedure, assert on results and on raised exceptions, then roll back so the database is left pristine.

```sql
-- utPLSQL-style test: each test sets up data, asserts, and rolls back
CREATE OR REPLACE PACKAGE test_order_pkg IS
  --%suite(Order pricing)
  --%test(applies bulk discount above threshold)
  PROCEDURE bulk_discount_applied;
END;
/
CREATE OR REPLACE PACKAGE BODY test_order_pkg IS
  PROCEDURE bulk_discount_applied IS
    l_price NUMBER;
  BEGIN
    -- arrange: deterministic fixture
    INSERT INTO products(id, unit_price) VALUES (1, 100);
    -- act
    l_price := order_pkg.price_for(p_prod=>1, p_qty=>500);
    -- assert
    ut.expect(l_price).to_equal(45000);     -- 10% bulk discount expected
    ROLLBACK;                                -- leave DB clean
  END;
END;
/
```

The hard parts are **test isolation and dependency seams**. Isolation: tests must not see each other's data, so each test runs in its own transaction and rolls back (or uses utPLSQL's auto-rollback), and tests never depend on existing production-shaped data — they create their own minimal fixtures. Mocking external dependencies (a remote table over a DB link, `SYSDATE`, a sequence, a call to an external service) is done by **dependency injection at the PL/SQL level**: wrap the non-deterministic/external thing behind a package function so tests can substitute a stub, e.g. route "current time" through `clock_pkg.now` rather than calling `SYSDATE` directly, so a test can freeze time. The CI design: spin up a disposable database (a container or a cloned **PDB**, which clones in seconds and is perfect for ephemeral test instances), apply migrations from version control (Liquibase/Flyway), run the utPLSQL suite, and **fail the build on any test failure or any invalid object** (`SELECT ... FROM user_objects WHERE status='INVALID'`). The senior point: schema and PL/SQL belong in version control and a migration tool, plans are guarded by baselines, and "it compiled" is not "it works" — invalid-object checks plus behavioral tests catch the two distinct failure modes.

#### Q117. [Coding] Write a query that safely handles time zones across regions (storing `TIMESTAMP WITH TIME ZONE` vs `TIMESTAMP WITH LOCAL TIME ZONE`) and converts for reporting.

Time-zone bugs are subtle and expensive, and the root cause is almost always storing a naive `TIMESTAMP` (or worse a `DATE`) for an event that happened in some other zone, then guessing the zone later. The correct model for a global event is `TIMESTAMP WITH TIME ZONE`, which stores the instant *and* its UTC offset, so the moment is unambiguous; `AT TIME ZONE` converts it for display in any region.

```sql
-- Store the instant unambiguously, then present it per-region
SELECT order_id,
       event_ts                              AS stored_tz,        -- TIMESTAMP WITH TIME ZONE
       event_ts AT TIME ZONE 'UTC'           AS in_utc,
       event_ts AT TIME ZONE 'America/New_York' AS in_nyc,
       event_ts AT TIME ZONE 'Asia/Kolkata'  AS in_ist,
       CAST(event_ts AT TIME ZONE 'UTC' AS DATE) AS utc_date_only
FROM   orders;

-- TIMESTAMP WITH LOCAL TIME ZONE: normalized to DB tz on store,
-- auto-converted to the SESSION tz on retrieval
ALTER SESSION SET TIME_ZONE = 'Europe/London';
SELECT created_ltz FROM events;   -- shown in London time automatically
```

The distinction that trips people: `TIMESTAMP WITH TIME ZONE` *preserves the original offset* you stored, whereas `TIMESTAMP WITH LOCAL TIME ZONE` (LTZ) **normalizes to the database time zone on insert and renders in the session's time zone on query**, so two users in different zones see "their" local time from the same stored value automatically — convenient for user-facing timestamps, but it means the original offset is *lost*, which matters if you ever need to know the wall-clock time at the event's location. Use named region zones (`'America/New_York'`), **not fixed offsets** (`'-05:00'`), so daylight-saving transitions are handled correctly — a fixed offset is wrong half the year. Two more landmines: Oracle's plain `DATE` and `TIMESTAMP` carry **no** zone information, so storing UTC in them only works if *every* reader agrees they're UTC (a fragile convention); and the time-zone rules come from the database's **time-zone file version**, which must be patched in sync across primary/standby or `AT TIME ZONE` can resolve a historical date differently on each node. The reporting rule of thumb: store the unambiguous instant (TZ or normalized UTC), convert at the edges for display, and never do arithmetic on mixed-zone naive timestamps.

#### Q118. [Practical] You inherit a database where `CURSOR_SHARING=FORCE` was set to stop a parse storm, but now some reports are slow. Explain the trade-off and the correct exit strategy.

`CURSOR_SHARING=FORCE` is the emergency tourniquet for a literal-SQL parse storm (Q18): Oracle rewrites literals in incoming SQL into system-generated bind variables (`WHERE id = 12345` becomes `WHERE id = :SYS_B_0`), so thousands of near-identical literal statements collapse into one shareable cursor and the hard-parse/library-cache-mutex pressure evaporates. It works, which is why it survives in so many databases as a "temporary" setting that became permanent. The cost is that it **reintroduces the bind-peeking problem to statements that were previously self-tuning**: a literal query for a skewed predicate used to get a perfect plan *because* the literal told the optimizer the exact selectivity; once forced into a bind, it's at the mercy of whichever value was peeked first, so a report that was fast on `status='PENDING'` literals can now inherit the plan peeked for `status='CLOSED'` and degrade.

```sql
-- Diagnose: are the slow reports now sharing a cursor that has the wrong plan?
SELECT sql_id, sql_text, executions, plan_hash_value, is_bind_sensitive
FROM   v$sql
WHERE  sql_text LIKE '%:SYS_B_%'           -- forced-bind signature
ORDER  BY elapsed_time/GREATEST(executions,1) DESC;
```

The trade-off in one line: `FORCE` trades **parse-time cost for plan quality** — it's the right call when the bottleneck is parsing (OLTP literal storm) and the wrong default when plan quality on skewed data matters (DSS/reporting). The correct exit strategy is not to flip it back to `EXACT` and reopen the parse storm, but to **fix the root cause**: make the offending application use **proper bind variables** so the well-written SQL gets `EXACT` semantics, while the legacy literal-heavy code still benefits. In the interim you protect the regressed reports specifically — rely on **Adaptive Cursor Sharing** plus **histograms** so the forced-bind cursors become bind-aware (Q113), and **lock the good plans for the critical reports into SQL Plan Baselines** so they can't flip regardless of peeking. The senior framing: `CURSOR_SHARING=FORCE` is a database-wide blunt instrument; the durable answer is per-statement (bind the app, baseline the reports), and the global setting is reserved for code you genuinely cannot change.

#### Q119. [Practical] Design a chargeback/showback and resource-isolation scheme on a consolidated database serving many apps. What Oracle features combine?

Consolidating many applications onto one database (or one CDB with many PDBs) saves licenses and ops effort but creates a **noisy-neighbor and accountability problem**: one app's runaway report can starve the others, and finance wants to attribute cost to each consumer. The design combines isolation features for *protection* with metering features for *chargeback*. For isolation, **Database Resource Manager** (Q73) is the core control — you map sessions to consumer groups (by service, user, or program) and cap their CPU shares, parallel-server usage, and even runaway-query thresholds (auto-kill or downgrade a query exceeding an estimated/actual time). In a multitenant CDB you layer **PDB-level resource plans** (shares and CPU/utilization limits per PDB) so an entire app's PDB can't monopolize the container.

```sql
-- Cap a reporting group's CPU and auto-cancel runaway queries
BEGIN
  DBMS_RESOURCE_MANAGER.CREATE_PLAN_DIRECTIVE(
    plan             => 'CONSOLIDATED_PLAN',
    group_or_subplan => 'REPORTING_GRP',
    mgmt_p1          => 20,                 -- 20% CPU share under contention
    parallel_degree_limit_p1 => 8,
    switch_time      => 300,                -- 5 min...
    switch_group     => 'CANCEL_SQL');      -- ...then cancel the statement
END;
/
```

For chargeback/showback, the metering data already exists: **AWR / `DBA_HIST_*`** and the time model give per-service and per-module DB-time, CPU, and I/O, and **`V$SERVICE_STATS` / `V$SERVICEMETRIC`** attribute work to named services — so the design mandate is that *every app connects through its own service name* (and sets `MODULE`/`ACTION` via `DBMS_APPLICATION_INFO`), because service/module is the dimension you aggregate cost by. You roll those metrics up periodically into a chargeback table: DB-time and CPU-seconds per service become the billable unit. In a CDB, per-PDB resource usage is exposed directly, making PDB-per-app the cleanest chargeback boundary. The features compose as: **services + MODULE tagging** for attribution, **Resource Manager (+ PDB plans)** for enforcement/protection, **AWR/time-model + service metrics** for the cost data, and **Resource Manager runaway directives** as the safety valve so showback isn't your *only* defense against a single query consuming the box. The principle worth stating: you can't charge back or isolate what you can't attribute, so the foundational design decision is per-app service names from day one.

#### Q120. [Coding] Write a query and explain a plan that proves an index is *not* helping (e.g. implicit conversion, leading-wildcard, or function on the column disables it).

A frequent production mystery is "there's an index on that column, why is it full-scanning?" The answer is almost always that the predicate is **non-sargable** — something prevents the optimizer from doing an index range scan on the raw column. The three classic killers are an implicit datatype conversion, a function wrapping the column, and a leading-wildcard `LIKE`.

```sql
-- 1. Implicit conversion: acct_no is VARCHAR2, bound/compared as a NUMBER
--    Oracle applies TO_NUMBER(acct_no) to EVERY row -> index unusable
SELECT * FROM accounts WHERE acct_no = 12345;          -- BAD (acct_no is char)
SELECT * FROM accounts WHERE acct_no = '12345';        -- GOOD: index range scan

-- 2. Function on the column disables the plain index
SELECT * FROM emp WHERE UPPER(last_name) = 'SMITH';    -- needs FBI or it scans
CREATE INDEX emp_uname_fbi ON emp (UPPER(last_name));  -- function-based index fixes it

-- 3. Leading wildcard cannot range-scan a B-tree
SELECT * FROM emp WHERE last_name LIKE '%smith%';      -- full scan; consider Text index
```

You *prove* it from the plan rather than asserting it. Run the statement and pull the actual plan with predicate detail:

```sql
SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY_CURSOR(format=>'ALLSTATS LAST +PREDICATE'));
-- Tell-tale signs in the output:
--   "TABLE ACCESS FULL"  where you expected "INDEX RANGE SCAN"
--   filter("ACCOUNTS"."ACCT_NO"=TO_NUMBER(:B1))   <- implicit TO_NUMBER = the smoking gun
--   A-Rows >> the rows you actually wanted, large buffer gets
```

The `Predicate Information` section is the proof: when you see the optimizer wrapping your column in `TO_NUMBER(...)`, `INTERNAL_FUNCTION(...)`, or any function in the `access`/`filter` line, the index on the bare column is dead because the index stores raw values, not the function's output — so it must compute the function per row, which is a full scan by definition. The fixes follow from the cause: align the literal/bind datatype to the column (or fix the column type) for implicit conversion; build a **function-based index** matching the exact expression for the `UPPER()` case; and for leading-wildcard searches use an **Oracle Text** (`CONTEXT`/`CTXCAT`) index rather than a B-tree, since `%term%` is fundamentally not a prefix range scan. The interview-grade insight: an index helps only when the predicate is *sargable* (the column appears bare on one side compared to a value of the same type), and the predicate section of `DISPLAY_CURSOR` is how you demonstrate, not guess, why it isn't.

#### Q121. [Coding] Implement optimistic and pessimistic locking patterns correctly in Oracle, and show how the "lost update" bug manifests.

The **lost update** is the canonical concurrency bug: two sessions read the same row, both compute a new value from what they read, both write — and the second write silently overwrites the first, losing it. Oracle's row locks prevent two *simultaneous* writers, but they do **not** stop lost updates across a read-then-later-write pattern (the typical web request: read in one statement, write in a much later one), so you must choose a locking strategy explicitly.

```sql
-- PESSIMISTIC: lock at read time; other writers block until you commit
SELECT balance INTO :bal FROM accounts WHERE id = :id FOR UPDATE;   -- row locked now
-- ...compute new balance...
UPDATE accounts SET balance = :new WHERE id = :id;
COMMIT;                                                              -- lock released

-- OPTIMISTIC: don't lock; detect conflict at write time via a version column
SELECT balance, version INTO :bal, :ver FROM accounts WHERE id = :id;
-- ...user thinks for 30 seconds...
UPDATE accounts SET balance = :new, version = version + 1
WHERE  id = :id AND version = :ver;                  -- 0 rows updated = someone else won
-- if SQL%ROWCOUNT = 0 -> conflict: re-read, re-apply, or tell the user
```

**Pessimistic** (`SELECT ... FOR UPDATE`) is correct and simple but holds the row lock for the whole think-time, which kills concurrency on hot rows and risks a user walking away with a row locked — so add `FOR UPDATE WAIT 5` or `NOWAIT` (or `SKIP LOCKED` for queue semantics, Q107) so a contending session fails fast instead of hanging. It suits short, high-contention critical sections where a conflict is likely and retry is costly. **Optimistic** (a `version`/`ORA_ROWSCN` column checked in the `UPDATE`'s `WHERE`) holds no lock during think-time and scales beautifully when conflicts are *rare* — the cost is that on the occasional conflict you get `SQL%ROWCOUNT = 0` and must handle it (re-read and retry, or surface "someone else changed this" to the user). The Oracle-specific nicety is `ORA_ROWSCN`, a pseudo-column that changes whenever a row is modified, letting you do optimistic checks without an explicit version column (with `ROWDEPENDENCIES` on the table for row-level granularity). The decision rule: optimistic for read-heavy, low-conflict, long-think-time flows (web apps); pessimistic for short, high-conflict server-side critical sections — and the bug to *name* in the interview is that doing neither (plain read, then plain update) is a lost-update waiting to happen the moment two users edit the same record.

#### Q122. [Practical] Design an end-to-end strategy to safely upgrade across two major versions (e.g. 12c → 23ai) for a system with strict SLAs, including plan-regression protection.

A two-major-version jump under strict SLAs is as much a *plan-stability* and *rollback* problem as a binary upgrade, and the failure mode that burns teams isn't the upgrade itself — it's the optimizer behaving differently afterward. The strategy has three pillars: minimize downtime, protect against plan regressions, and guarantee fallback.

For **downtime**, you don't do an in-place big-bang on the primary. You stand up the target version as a **standby** (physical, then a **rolling upgrade via `DBMS_ROLLING`/transient logical standby**, Q19) so the upgrade is applied off to the side while production runs, then cut over with a brief, rehearsed **switchover**; clients reconnect through a service alias and **Application Continuity** replays in-flight transactions so users see a blip, not errors. Crucially you **keep the old version as a standby afterward** for a fast switchback if KPIs regress — that's your rollback that doesn't involve a restore.

```bash
# Capture the pre-upgrade plan/workload baseline BEFORE touching anything
sqlplus / as sysdba <<'SQL'
-- 1. Seed SQL Plan Baselines from the current shared pool / a tuning set
EXEC DBMS_SPM.LOAD_PLANS_FROM_SQLSET(sqlset_name=>'PRE_UPGRADE_STS');
-- 2. Snapshot stats so they can be restored if the new optimizer misbehaves
EXEC DBMS_STATS.EXPORT_DATABASE_STATS(statown=>'SYSTEM', stattab=>'PRE_UPG_STATS');
SQL
# 3. On a full-size clone of the target version, run SQL Performance Analyzer
#    over the captured SQL Tuning Set to find statements that regress.
```

For **plan-regression protection** — the heart of a senior answer — you do the comparison *before* the cutover, not after the outage. Capture a **SQL Tuning Set** of the real workload on the old version, then on a full-size clone running 23ai use **SQL Performance Analyzer (SPA)** to execute that same workload and produce a regression report: it tells you exactly which statements got slower and by how much, so you remediate them (baselines, stats, or fixes) *before* go-live instead of firefighting in production. Lock the known-good plans into **SQL Plan Baselines** captured from the old version so the new optimizer is constrained to verified plans and can only evolve to a new plan after proving it's faster. Pair this with the new version's **optimizer-statistics preferences** (consider keeping `OPTIMIZER_FEATURES_ENABLE` pinned initially, then lifting it deliberately) so you decouple "run on new binaries" from "adopt new optimizer behavior." The non-negotiables for the SLA: rehearse the entire runbook against a full-size clone, define **objective rollback criteria** (p99 latency, batch completion time) and the switchback procedure up front, and run **Database Replay** (Q74) of captured production load on the target so you've seen the real concurrency behave before a single user does. The framing that signals staff-level: the upgrade is the easy part; the engineering is making the optimizer's behavior change *observable and reversible* before it touches customers.

#### Q123. [Behavioral] Describe a situation where you had to make a database design or operational decision under significant ambiguity and time pressure, and how you reasoned about the risk.

I use **STAR** and aim to show structured risk reasoning under uncertainty rather than a lucky guess. **Situation:** during a major sale event, write latency on the orders database began climbing and the on-call dashboard showed growing `enq: TX` and `buffer busy` waits, but we had incomplete information — AWR's last snapshot was 40 minutes stale, the change log showed three recent deploys, and the business was escalating because checkout was visibly slowing. **Task:** decide, within minutes and without a clean root cause, what action to take that would relieve pressure *without* risking a worse outage (a wrong `ALTER`, an ill-advised restart, or killing the wrong sessions could turn a slowdown into a full outage during peak revenue).

**Action:** I reasoned about it as **reversible vs irreversible** actions under uncertainty — the senior instinct is to prefer cheap, reversible diagnostics and mitigations over irreversible ones, and to buy time before betting. I pulled **live ASH** (`V$ACTIVE_SESSION_HISTORY`, which doesn't need a fresh AWR snapshot — Q12) rather than waiting for AWR, and it pointed at index contention concentrated on the orders PK — consistent with a right-growing-key hotspot under the sale's insert surge (Q91), not any of the three deploys. Rather than the irreversible step of altering the index live, I first applied the cheapest reversible mitigation: raised the sequence `CACHE` (an online, instantly-reversible change) and used **Resource Manager** to throttle a heavy background reconciliation job that was amplifying contention, which bought headroom immediately. I communicated a clear hypothesis and the fact that it was a hypothesis, with a stated checkpoint: "if waits don't drop in 10 minutes, we escalate to the index change in a controlled way."

**Result:** waits dropped enough to keep checkout within SLA through the peak, and we did the durable fix (hash-partitioned PK index, Q21) in a planned window afterward with full testing — not under fire. **Reflection:** the decision framework I'd articulate is that under ambiguity you (1) gather the fastest *non-destructive* evidence available rather than the most complete, (2) order candidate actions by reversibility and blast radius and take the cheap reversible ones first, (3) state your hypothesis and a checkpoint so you don't anchor on a wrong guess, and (4) separate "stop the bleeding now" from "fix it right later." The signal interviewers want isn't that I was right — it's that I had a disciplined way to be wrong safely.

## ✅ Key Takeaways

- The optimizer is only as good as its **statistics and histograms**; most "Oracle is slow" tickets are stale stats or a missing histogram on a skewed column.
- Choose indexes by workload: **B-tree for OLTP and ranges, bitmap only for low-cardinality read-mostly data, function-based for expression predicates.** Bitmaps in write-heavy OLTP cause locking pain.
- Use **set-based PL/SQL** (`BULK COLLECT` + `FORALL`) and bind variables; row-by-row loops and literal SQL are the two biggest scalability killers (and literals are a SQL-injection risk).
- **RAC = local availability/scale-out; Data Guard = disaster recovery.** Serious systems run both. Monotonic ordered keys are a RAC contention anti-pattern.
- **AWR** for the trend over an hour, **ASH** for the sub-second spike, **Statspack** when you lack the Diagnostics Pack license.
- Prefer **SQL Plan Baselines** to lock good plans during upgrades over scattering hints; fix stats and design before forcing the optimizer.
- **Flashback** turns many "restore from backup" incidents into seconds-long recoveries — but governs PII retention carefully.

## ⚠️ Common Pitfalls

- Treating sequence values as **gap-free** — they aren't (caching, rollback, RAC).
- `BULK COLLECT` without a `LIMIT`, blowing up PGA on large tables.
- Forcing `CURSOR_SHARING=FORCE` cluster-wide as a "fix" instead of using bind variables, then suffering plan instability on skewed data.
- Partitioning on a column that **never appears in predicates**, so partition pruning never kicks in — pure overhead.
- On-commit materialized views on **high-write base tables**, silently taxing every transaction.
- Over-indexing OLTP tables, slowing every DML; and creating bitmap indexes on concurrently-updated columns.
- Ignoring `ORA-01555` until production: undersized undo retention against long-running reports.
- Embedding hints in application SQL and forgetting them; they rot as data volumes change.

## 📚 Further Reading

- *Oracle Database 19c/23ai SQL Tuning Guide* (Oracle official documentation) — the authoritative CBO, statistics, and SPM reference.
- *Troubleshooting Oracle Performance*, Christian Antognini — deep, methodical performance diagnosis.
- *Expert Oracle Database Architecture*, Thomas Kyte — how the engine actually works (concurrency, undo, indexing).
- *Oracle PL/SQL Programming*, Steven Feuerstein — the definitive PL/SQL book (bulk processing, packages, error handling).
- *Oracle Concepts Guide* (official docs) — RAC, Data Guard, flashback, partitioning, In-Memory fundamentals.
- Oracle Optimizer Blog (blogs.oracle.com/optimizer) — current optimizer behavior, adaptive features, and version differences.
