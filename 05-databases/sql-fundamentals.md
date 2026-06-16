# SQL Fundamentals

A deep, interview-grade reference on relational SQL: querying, joins, window functions, CTEs, transactions, ACID, isolation levels, normalization, and a set of classic coding problems. Examples are illustrated with **Java** (JDBC/Spring) where host-language code matters, and standard ANSI SQL with engine-specific notes (PostgreSQL, MySQL 8+, SQL Server, Oracle) throughout. Knowledge current through 2026.

[← Back to master index](../README.md)

## Table of Contents

- [🟢 Basic (0–2 yrs)](#-basic-02-yrs)
- [🟡 Intermediate (3–7 yrs)](#-intermediate-37-yrs)
- [🟠 Advanced (8–12 yrs)](#-advanced-812-yrs)
- [🔴 Expert (15+ yrs)](#-expert-15-yrs)
- [✅ Key Takeaways](#-key-takeaways)
- [⚠️ Common Pitfalls](#-common-pitfalls)
- [📚 Further Reading](#-further-reading)

---

## 🟢 Basic (0–2 yrs)

### Q1. [Theory] What is the logical order of evaluation of a SELECT statement, and why does it matter?

SQL is written in one order but **logically evaluated in another**. The written order is `SELECT ... FROM ... JOIN ... WHERE ... GROUP BY ... HAVING ... ORDER BY ... LIMIT`, but the engine evaluates it roughly as:

```
1. FROM / JOIN      -> build the working row set (cartesian + join predicates)
2. WHERE            -> filter individual rows (no aggregates allowed yet)
3. GROUP BY         -> collapse rows into groups
4. HAVING           -> filter groups (aggregates allowed)
5. SELECT           -> compute/expand projected columns, evaluate window funcs
6. DISTINCT
7. ORDER BY         -> sort the final result
8. LIMIT / OFFSET   -> slice
```

This explains common beginner errors: you **cannot reference a `SELECT` alias in `WHERE`** (WHERE runs before SELECT), but you often *can* in `ORDER BY` (which runs after). It also explains why aggregate filters must go in `HAVING`, not `WHERE`. Understanding this order is the single highest-leverage piece of SQL knowledge because nearly every query bug traces back to it.

### Q2. [Theory] Explain the four join types: INNER, LEFT/RIGHT OUTER, FULL OUTER, and CROSS.

A join combines rows from two tables based on a predicate.

- **INNER JOIN** keeps only rows that match the predicate in *both* tables.
- **LEFT OUTER JOIN** keeps all rows from the left table; unmatched right-side columns become `NULL`.
- **RIGHT OUTER JOIN** is the mirror image (rarely used—people flip the tables and use LEFT).
- **FULL OUTER JOIN** keeps unmatched rows from *both* sides.
- **CROSS JOIN** is the Cartesian product (every left row × every right row) with no predicate.

```
employees            departments
+----+--------+      +------+----------+
| id | dept_id|      | d_id | name     |
+----+--------+      +------+----------+
| 1  | 10     |      | 10   | Eng      |
| 2  | 20     |      | 30   | Sales    |
| 3  | NULL   |      +------+----------+
+----+--------+

INNER  -> emp 1 (Eng), emp 2 has dept 20 (no match) dropped
LEFT   -> emp 1 (Eng), emp 2 (NULL), emp 3 (NULL)   all employees kept
FULL   -> emp 1 (Eng), emp 2 (NULL), emp 3 (NULL), + Sales (no employee)
```

A key gotcha: a `LEFT JOIN` with a filter on the right table placed in `WHERE` silently behaves like an `INNER JOIN`, because `NULL = anything` is unknown. Put right-table conditions in the `ON` clause instead.

### Q3. [Practical] When do you use GROUP BY vs HAVING vs WHERE?

`WHERE` filters **rows before grouping**; `HAVING` filters **groups after aggregation**. Always push filters into `WHERE` when they apply to individual rows—it reduces the number of rows the aggregation must process and lets indexes help.

```sql
-- Departments where the average salary of *current* employees exceeds 100k
SELECT dept_id, AVG(salary) AS avg_sal
FROM   employees
WHERE  status = 'ACTIVE'        -- per-row filter, runs first, index-friendly
GROUP  BY dept_id
HAVING AVG(salary) > 100000;    -- per-group filter, must be HAVING
```

In production I treat `HAVING` as a code smell *if* the condition could have lived in `WHERE`. A condition like `HAVING dept_id = 10` should be `WHERE dept_id = 10`—putting it in HAVING forces the engine to aggregate every group then throw most away.

### Q4. [Theory] What is NULL and how does it behave in comparisons, aggregates, and joins?

`NULL` means "unknown," not "zero" or "empty string." It uses **three-valued logic**: any comparison with NULL yields `UNKNOWN`, not true or false. So `NULL = NULL` is `UNKNOWN`, and a `WHERE` clause only keeps rows where the predicate is `TRUE`. You must use `IS NULL` / `IS NOT NULL`. Aggregates like `SUM`, `AVG`, and `COUNT(col)` **skip NULLs**, but `COUNT(*)` counts all rows. This causes a classic bug: `AVG(col)` over a column with NULLs divides by the count of non-NULL values, not the row count. In `NOT IN (subquery)`, a single NULL in the subquery makes the whole predicate return no rows—prefer `NOT EXISTS` to avoid this trap.

### Q5. [Coding] Write a query to find the second highest salary, handling ties and the "no second salary" case.

**Problem:** Return the second-highest distinct salary. If it doesn't exist (e.g., all salaries equal, or fewer than two distinct values), return `NULL`.

**Approach 1 — subquery (portable, simple):**

```sql
SELECT MAX(salary) AS SecondHighestSalary
FROM   employees
WHERE  salary < (SELECT MAX(salary) FROM employees);
```

Because we wrap it in `MAX`, an empty inner result yields `NULL` automatically—exactly the required behavior.

**Approach 2 — window function (generalizes to Nth):**

```sql
SELECT MAX(salary) AS SecondHighestSalary
FROM (
  SELECT salary,
         DENSE_RANK() OVER (ORDER BY salary DESC) AS rnk
  FROM   employees
) t
WHERE rnk = 2;
```

Use `DENSE_RANK` so tied top salaries don't push the "second" value off. `RANK` would skip rank 2 if two people tied for first.

- **Time:** O(n) for the subquery form (two scans); O(n log n) for the windowed sort.
- **Space:** O(1) for approach 1; O(n) for the windowed intermediate.
- **Edge cases:** fewer than 2 distinct salaries → returns NULL; duplicate top salaries handled by DISTINCT/DENSE_RANK.

### Q6. [Theory] Difference between primary key, unique constraint, and foreign key.

A **primary key** uniquely identifies a row, is `NOT NULL`, and there is exactly one per table; it typically backs the clustered index. A **unique constraint** also enforces uniqueness but allows (in most engines) a single NULL or, per the SQL standard, multiple NULLs since NULLs aren't "equal." A **foreign key** is a referential-integrity constraint that requires each value to exist in the referenced table's key, preventing orphan rows and enabling cascading actions (`ON DELETE CASCADE`, `ON DELETE SET NULL`). FKs cost a little on writes (the engine must verify the parent exists) but are essential for data correctness and let the optimizer reason about relationships.

### Q7. [Practical] How would you call a parameterized SQL query safely from Java?

Always use `PreparedStatement` with bind parameters—never string concatenation. This prevents SQL injection and lets the driver/engine cache the plan.

```java
String sql = "SELECT id, name FROM employees WHERE dept_id = ? AND status = ?";
try (Connection conn = dataSource.getConnection();
     PreparedStatement ps = conn.prepareStatement(sql)) {
    ps.setInt(1, deptId);
    ps.setString(2, "ACTIVE");
    try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
            System.out.println(rs.getInt("id") + " " + rs.getString("name"));
        }
    }
}
```

In Spring Boot 3 you'd typically use `JdbcTemplate`/`JdbcClient` or JPA, which bind parameters for you. The security point is non-negotiable: any query built by concatenating user input (`"... dept_id = " + input`) is an injection vulnerability.

---

## 🟡 Intermediate (3–7 yrs)

### Q8. [Theory] Subqueries vs JOINs: when does each win, and what about correlated subqueries?

A **JOIN** combines columns from multiple tables into one row set; a **subquery** computes an intermediate value or set used by the outer query. They often produce identical plans because modern optimizers can rewrite one into the other.

- Use a **JOIN** when you need columns from both tables in the output.
- Use a **non-correlated subquery** for a single scalar (`WHERE salary > (SELECT AVG(salary) ...)`) or a set membership test.
- A **correlated subquery** references the outer row and re-evaluates per outer row—conceptually O(n·m). Optimizers frequently de-correlate it into a join or semi-join, but not always, so for large data prefer `EXISTS`/`IN`/joins.

```sql
-- EXISTS (semi-join) is usually optimal for "does a related row exist?"
SELECT e.* FROM employees e
WHERE EXISTS (SELECT 1 FROM orders o WHERE o.emp_id = e.id);
```

`EXISTS` short-circuits on the first match and is NULL-safe, unlike `IN` with a NULL-containing list. Rule of thumb: `EXISTS`/`NOT EXISTS` for existence checks, `JOIN` when you need the data, scalar subqueries for single values.

### Q9. [Coding] Write the canonical "Nth highest salary" solution.

**Problem:** Given a parameter `N`, return the Nth highest distinct salary, or NULL if it doesn't exist.

**MySQL 8 / PostgreSQL — window function (cleanest, generalizes):**

```sql
SELECT DISTINCT salary AS NthHighestSalary
FROM (
  SELECT salary, DENSE_RANK() OVER (ORDER BY salary DESC) AS rnk
  FROM   employees
) t
WHERE rnk = :N;
```

**Portable — using LIMIT/OFFSET (returns NULL handling needs a wrapper):**

```sql
-- PostgreSQL / MySQL: distinct salaries, skip N-1, take 1
SELECT (
  SELECT DISTINCT salary
  FROM   employees
  ORDER  BY salary DESC
  LIMIT  1 OFFSET (:N - 1)
) AS NthHighestSalary;
```

Wrapping in an outer scalar subquery guarantees a single NULL row when the offset exceeds the number of distinct salaries.

- **Time:** O(n log n) (sort dominates). **Space:** O(n) for the ranked set.
- **Edge cases:** N ≤ 0 (validate in app code), N larger than distinct count → NULL, tied salaries collapsed by DISTINCT/DENSE_RANK.

### Q10. [Theory] Explain window functions: ROW_NUMBER, RANK, DENSE_RANK, LAG, LEAD, and running totals.

Window functions compute a value **across a set of rows related to the current row** without collapsing rows the way `GROUP BY` does. The window is defined by `OVER (PARTITION BY ... ORDER BY ... frame)`.

- **ROW_NUMBER()** — unique sequential number; ties broken arbitrarily (by the ORDER BY then physical order).
- **RANK()** — ties share a rank, then the next rank *skips* (1,1,3).
- **DENSE_RANK()** — ties share a rank, no gaps (1,1,2).
- **LAG(col, n)/LEAD(col, n)** — value from n rows behind/ahead in the partition; great for period-over-period deltas.
- **Running total** — `SUM(amount) OVER (ORDER BY dt ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)`.

```sql
SELECT dt, amount,
       SUM(amount) OVER (ORDER BY dt
                         ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS running_total,
       amount - LAG(amount) OVER (ORDER BY dt) AS day_over_day_delta,
       ROW_NUMBER() OVER (PARTITION BY category ORDER BY amount DESC) AS rn_in_cat
FROM sales;
```

A critical subtlety: the default frame when you specify `ORDER BY` but omit `ROWS/RANGE` is `RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW`, which treats **peer rows (equal ORDER BY values) together**—this can produce surprising running totals on tied dates. Use explicit `ROWS` for true row-by-row accumulation.

### Q11. [Coding] "Top N per group": return the top-2 highest-paid employees per department.

**Problem:** For each department, return its two highest-paid employees, including ties at the cutoff.

```sql
SELECT dept_id, emp_id, salary
FROM (
  SELECT dept_id, emp_id, salary,
         DENSE_RANK() OVER (PARTITION BY dept_id ORDER BY salary DESC) AS rnk
  FROM   employees
) t
WHERE rnk <= 2
ORDER BY dept_id, salary DESC;
```

Choice of ranking function changes semantics:
- `ROW_NUMBER` → exactly 2 rows per dept (arbitrary tie-break).
- `RANK`/`DENSE_RANK` → may return >2 if salaries tie at the cutoff; `DENSE_RANK` includes the "3rd distinct level" only if it's ≤2.

- **Time:** O(n log n) for the partitioned sort. **Space:** O(n).
- **Edge cases:** departments with fewer than 2 employees (returns all), ties at rank 2 (decide ROW_NUMBER vs DENSE_RANK based on business rule).

### Q12. [Coding] Remove duplicate rows, keeping one copy of each.

**Problem:** A table has fully or partially duplicated rows. Delete duplicates, keeping the row with the smallest `id` per duplicate group (group defined by `email`).

**Approach 1 — `DELETE` with a self-join (MySQL-friendly):**

```sql
DELETE e1
FROM   employees e1
JOIN   employees e2
  ON   e1.email = e2.email
 AND   e1.id   > e2.id;     -- delete the higher-id duplicates
```

**Approach 2 — window function + CTE (PostgreSQL / SQL Server, very readable):**

```sql
WITH ranked AS (
  SELECT id,
         ROW_NUMBER() OVER (PARTITION BY email ORDER BY id) AS rn
  FROM   employees
)
DELETE FROM employees
WHERE id IN (SELECT id FROM ranked WHERE rn > 1);
```

**Approach 3 — for full-row dedup in a SELECT (no delete):** `SELECT DISTINCT *` or `GROUP BY` all columns.

- **Time:** Approach 1 is O(n²) worst case without an index on `email`; with an index it's roughly O(n log n). Approach 2 is O(n log n) (the partitioned sort).
- **Space:** O(n) for the ranked CTE.
- **Edge cases:** NULL emails (each NULL is distinct, so they won't be grouped—decide whether NULLs are "duplicates"), and always run inside a transaction so you can roll back if the row count looks wrong.

### Q13. [Practical] What are CTEs, and how do recursive CTEs work? Give a real use case.

A **Common Table Expression** (`WITH name AS (...)`) is a named, scoped subquery that improves readability and can be referenced multiple times. Most engines inline non-recursive CTEs, though **PostgreSQL ≤11 materialized them by default** (an optimization fence); PG12+ inlines them unless you write `WITH ... AS MATERIALIZED`. So a CTE is *not* automatically a performance win—it's primarily for clarity.

A **recursive CTE** has an *anchor* member and a *recursive* member unioned together, ideal for hierarchies (org charts, bill-of-materials, graph traversal):

```sql
WITH RECURSIVE org AS (
  SELECT id, name, manager_id, 1 AS depth          -- anchor: the CEO
  FROM   employees WHERE manager_id IS NULL
  UNION ALL
  SELECT e.id, e.name, e.manager_id, o.depth + 1   -- recursive step
  FROM   employees e
  JOIN   org o ON e.manager_id = o.id
)
SELECT * FROM org ORDER BY depth, id;
```

Real use case: at a company I'd use this to compute the full reporting chain or to flatten a category tree for an e-commerce facet sidebar. Guard against cycles—add a depth limit or track visited nodes (SQL Server's `MAXRECURSION`, PostgreSQL's `CYCLE` clause) so a malformed graph doesn't loop forever.

### Q14. [Theory] Set operations: UNION vs UNION ALL, INTERSECT, EXCEPT/MINUS.

Set operations combine the results of two queries that must have **compatible column counts and types**.

- **UNION** concatenates and **removes duplicates** (implies a sort/hash dedup—costly).
- **UNION ALL** concatenates and keeps duplicates (cheap—use it whenever you know rows are already distinct or duplicates are acceptable).
- **INTERSECT** returns rows present in both.
- **EXCEPT** (Oracle: **MINUS**) returns rows in the first query not in the second.

```
A = {1,2,2,3}   B = {2,3,4}
A UNION B      = {1,2,3,4}
A UNION ALL B  = {1,2,2,3,2,3,4}
A INTERSECT B  = {2,3}
A EXCEPT B     = {1}
```

A frequent performance bug is reaching for `UNION` out of habit when `UNION ALL` would do—the implicit deduplication on large result sets is expensive and usually unintended.

### Q15. [Theory] Explain ACID with a concrete example.

ACID describes the guarantees a transaction provides:

- **Atomicity** — all statements commit or none do. A bank transfer debiting account A and crediting account B must be all-or-nothing; a crash between the two writes must roll back the debit.
- **Consistency** — the transaction moves the database from one valid state to another, honoring constraints, triggers, and invariants (total money conserved).
- **Isolation** — concurrent transactions don't see each other's uncommitted intermediate state; the result is *as if* they ran in some serial order (subject to the chosen isolation level).
- **Durability** — once committed, the change survives crashes, typically via a write-ahead log (WAL) flushed to stable storage before the commit returns.

```java
Connection conn = dataSource.getConnection();
try {
    conn.setAutoCommit(false);                         // begin transaction
    debit(conn, fromAccount, amount);
    credit(conn, toAccount, amount);
    conn.commit();                                     // atomic + durable
} catch (SQLException ex) {
    conn.rollback();                                   // atomicity preserved
    throw ex;
} finally {
    conn.setAutoCommit(true);
    conn.close();
}
```

### Q16. [Theory] Explain isolation levels and the anomalies each prevents.

Isolation levels trade correctness for concurrency. The SQL standard defines four, characterized by which read anomalies they permit:

```
Anomaly \ Level   READ_UNCOMMITTED  READ_COMMITTED  REPEATABLE_READ  SERIALIZABLE
Dirty read              YES               no              no              no
Non-repeatable read     YES              YES              no              no
Phantom read            YES              YES             YES*             no
```

- **Dirty read:** reading another transaction's *uncommitted* data that may be rolled back.
- **Non-repeatable read:** re-reading the same row returns a different value because another transaction committed an update in between.
- **Phantom read:** re-running the same range query returns *new rows* inserted by another committed transaction.

`*` Implementation matters: the standard says REPEATABLE READ allows phantoms, but **MySQL InnoDB's REPEATABLE READ uses next-key locking and MVCC, preventing most phantoms** for snapshot reads. **PostgreSQL REPEATABLE READ is true snapshot isolation** (no phantoms on reads, but can fail on write conflicts). PostgreSQL/SQL Server **SERIALIZABLE** uses SSI (serializable snapshot isolation) or strict 2PL respectively. The default in PostgreSQL, Oracle, and SQL Server is READ COMMITTED; MySQL InnoDB defaults to REPEATABLE READ. Always know your engine's actual behavior, not just the standard.

### Q17. [Practical] How do you set an isolation level and handle a lost update in Java?

A **lost update** occurs when two transactions read a value, each modifies it, and the second overwrites the first. READ COMMITTED does *not* prevent it. Two production strategies:

**Optimistic locking (preferred for low contention):** add a `version` column and check it on update.

```sql
UPDATE accounts
SET    balance = :newBalance, version = version + 1
WHERE  id = :id AND version = :expectedVersion;
-- 0 rows affected => someone else updated; retry or fail
```

JPA does this automatically with `@Version`. In raw JDBC you set the level explicitly:

```java
conn.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
```

**Pessimistic locking (high contention):** `SELECT ... FOR UPDATE` takes a row lock for the duration of the transaction, serializing access. The trade-off is reduced concurrency and deadlock risk. In production I default to optimistic locking with a bounded retry loop, falling back to pessimistic locks only on hot rows (e.g., inventory counters on a flash sale).

### Q18. [Theory] What is the difference between a view and a materialized view?

A **view** is a stored query—a virtual table. It holds no data; the engine expands and re-executes the underlying query every time you reference it. Views are great for encapsulating logic, enforcing column-level security (expose a subset of columns), and simplifying complex joins, with **no storage cost and always-fresh data**.

A **materialized view** physically stores the query result on disk. Reads are fast (it's precomputed), but the data is **stale until refreshed** (`REFRESH MATERIALIZED VIEW` in PostgreSQL—optionally `CONCURRENTLY` to avoid blocking reads). They're ideal for expensive aggregations queried far more often than the base data changes—dashboards, reporting rollups. The trade-off is storage plus a refresh strategy (scheduled, on-demand, or incremental). SQL Server's indexed views and Oracle's materialized views with query rewrite can even be substituted automatically by the optimizer.

### Q19. [Practical] Walk through a real indexing decision for a slow query.

**Scenario:** A `SELECT * FROM orders WHERE customer_id = ? AND status = 'OPEN' ORDER BY created_at DESC LIMIT 20` query times out under load on a 200M-row table.

**Approach:** Run `EXPLAIN (ANALYZE, BUFFERS)`. It shows a full sequential scan. The fix is a **composite index** matching the query's access pattern:

```sql
CREATE INDEX idx_orders_cust_status_created
ON orders (customer_id, status, created_at DESC);
```

Column order follows the **equality-then-range/sort** rule: equality predicates (`customer_id`, `status`) first, then the column used for ordering (`created_at`) so the index also satisfies the `ORDER BY`, avoiding a sort. This can turn the plan into an **index range scan + limit**, reading only ~20 rows.

**Trade-offs:** the index speeds reads but slows writes and consumes storage; a too-wide or redundant index hurts. I'd also consider a **covering index** (`INCLUDE` the selected columns in PostgreSQL/SQL Server) to enable index-only scans, and avoid `SELECT *` so the covering index is feasible. In production I verify with the real plan, not intuition—optimizers are statistics-driven and the "obvious" index sometimes isn't chosen until `ANALYZE` updates stats.

### Q20. [Coding] Find gaps in a sequence (missing IDs).

**Problem:** Table `seq(id INT)` should contain a contiguous range. Find the missing ID ranges.

**Approach using LEAD:**

```sql
WITH ordered AS (
  SELECT id,
         LEAD(id) OVER (ORDER BY id) AS next_id
  FROM   seq
)
SELECT id + 1            AS gap_start,
       next_id - 1      AS gap_end
FROM   ordered
WHERE  next_id - id > 1;   -- a jump bigger than 1 => gap between them
```

- **Time:** O(n log n) for the ordered window. **Space:** O(n).
- **Edge cases:** the last row has `next_id = NULL` (no gap after it), single-row tables (no gaps), and gaps before the minimum/after the maximum need explicit boundary handling if the expected range is known.

---

## 🟠 Advanced (8–12 yrs)

### Q21. [Coding] Solve the classic "Gaps and Islands" problem.

**Problem:** Given login dates per user, collapse *consecutive-day* logins into contiguous "islands" (streaks), returning each user's streak start and end.

**The trick:** for consecutive sequences, `value - ROW_NUMBER()` is constant within an island. Subtracting an incrementing row number from a (densely incrementing) value yields the same group key for runs.

```sql
WITH numbered AS (
  SELECT user_id, login_date,
         ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY login_date) AS rn
  FROM   logins
),
grouped AS (
  SELECT user_id, login_date,
         -- date minus rn (in days) is constant within a consecutive run
         login_date - (rn * INTERVAL '1 day') AS grp
  FROM   numbered
)
SELECT user_id,
       MIN(login_date) AS streak_start,
       MAX(login_date) AS streak_end,
       COUNT(*)        AS streak_length
FROM   grouped
GROUP  BY user_id, grp
ORDER  BY user_id, streak_start;
```

For integer sequences it's simply `value - ROW_NUMBER()`. For dates, subtract `rn` days (syntax varies: PostgreSQL `date - rn`, SQL Server `DATEADD(day, -rn, date)`, MySQL `DATE_SUB(date, INTERVAL rn DAY)`).

- **Time:** O(n log n) (the partitioned sort). **Space:** O(n).
- **Edge cases:** duplicate dates per user (dedup first, or use `DENSE_RANK`), gaps of exactly one day vs weekends (define "consecutive" per business rules), single-login users (a length-1 island).

This pattern generalizes to sessionization (gap > 30 min starts a new session), detecting contiguous "on" periods in IoT telemetry, and billing-period coalescing.

### Q22. [Theory] Explain MVCC and how it differs from lock-based concurrency.

**Multi-Version Concurrency Control** lets readers see a consistent *snapshot* without blocking writers and vice versa. Instead of locking rows for reads, the engine keeps multiple versions of each row, each tagged with transaction timestamps/IDs. A reader sees the latest version committed *before its snapshot*, so `SELECT` never waits on a concurrent `UPDATE`.

```
Lock-based:                          MVCC:
T1 reads row -> shared lock          T1 reads snapshot @ t0 (no lock)
T2 update -> waits for T1            T2 updates -> writes new version @ t1
                                      T1 still sees old version @ t0
```

PostgreSQL stores old versions inline and relies on **VACUUM** to reclaim dead tuples (the source of bloat if autovacuum lags). Oracle and MySQL InnoDB use **undo/rollback segments** to reconstruct prior versions. The cost of MVCC is version storage, garbage collection, and **write conflicts under snapshot isolation** (a transaction may abort with a serialization failure rather than block), which the application must retry. MVCC is why "readers don't block writers" is true in PostgreSQL/Oracle/MySQL but historically *not* in older SQL Server READ COMMITTED (until RCSI / snapshot isolation was enabled).

### Q23. [Theory] Walk through normalization 1NF → BCNF, and when you'd denormalize.

Normalization removes redundancy and update anomalies by decomposing tables based on functional dependencies.

- **1NF:** atomic values, no repeating groups/arrays in a column; each cell holds one value.
- **2NF:** 1NF + no *partial dependency*—every non-key attribute depends on the *whole* composite key, not part of it.
- **3NF:** 2NF + no *transitive dependency*—non-key attributes depend only on the key, "the whole key, and nothing but the key."
- **BCNF:** a stricter 3NF—*every* determinant (left side of a functional dependency) must be a candidate key. BCNF handles edge cases 3NF misses when there are overlapping candidate keys.

```
Unnormalized order line:
(order_id, product_id, product_name, qty, customer_id, customer_city)

Problem: product_name depends only on product_id (transitive),
         customer_city depends only on customer_id.

3NF decomposition:
  order_line(order_id, product_id, qty)
  product(product_id, product_name)
  customer(customer_id, customer_city)
```

**Denormalization** deliberately reintroduces redundancy for read performance—precomputed aggregates, duplicated lookup columns, wide reporting tables—trading write complexity and consistency risk for fewer joins. In OLTP I normalize to 3NF/BCNF as the default and denormalize *selectively* with evidence (a hot read path, measured join cost), keeping the redundant copy in sync via triggers, application logic, or materialized views. OLAP/warehouse star schemas are intentionally denormalized (fact + dimension tables).

### Q24. [Practical] A report query joining 6 tables is slow. How do you diagnose and fix it?

**Diagnosis order:**

1. `EXPLAIN ANALYZE` — find the actual vs estimated row counts. A large mismatch signals **stale statistics**; run `ANALYZE`/`UPDATE STATISTICS`.
2. Look for the expensive node: a hash join spilling to disk, a nested loop over millions of rows, or a sequential scan where an index should help.
3. Check **join order and cardinality**: the optimizer may pick a bad order if estimates are off; selective filters should be applied early.

**Fixes, in order of preference:**

```
Bad estimates       -> ANALYZE; add extended/multi-column statistics
Missing index       -> composite/covering index on join + filter keys
Disk spill          -> raise work_mem (PG) / sort space; or reduce row width
Repeated heavy join -> materialized view refreshed on a schedule
Optimizer mis-plan  -> rewrite (push predicates down, avoid SELECT *),
                       last resort: optimizer hints / pg_hint_plan
```

**Real-world example:** a finance reporting query joined transactions to five dimension tables and ran in 90 s. `EXPLAIN ANALYZE` showed the optimizer estimated 1,000 rows from the transactions filter but actually produced 4M, causing a nested loop. Adding a multi-column statistic on the correlated filter columns and a covering index on `transactions(account_id, posted_date) INCLUDE (amount)` let the planner switch to a hash join with index-only scan, dropping it to 1.2 s. The lesson: most "slow join" problems are really **cardinality estimation** problems, not missing indexes.

### Q25. [Coding] Pivot rows into columns (monthly sales per product).

**Problem:** From `sales(product, month, amount)` produce one row per product with a column per month (Jan/Feb/Mar).

**Approach 1 — conditional aggregation (portable, works everywhere):**

```sql
SELECT product,
       SUM(CASE WHEN month = 1 THEN amount ELSE 0 END) AS jan,
       SUM(CASE WHEN month = 2 THEN amount ELSE 0 END) AS feb,
       SUM(CASE WHEN month = 3 THEN amount ELSE 0 END) AS mar
FROM   sales
GROUP  BY product;
```

**Approach 2 — engine-specific:** SQL Server `PIVOT`, PostgreSQL `crosstab` (tablefunc extension), Oracle `PIVOT`. These are concise but less portable and require a fixed/known column set.

- **Time:** O(n) single pass + group. **Space:** O(distinct products).
- **Edge cases:** months with no sales (the `CASE` yields 0 via `ELSE 0`; use `NULL` if you must distinguish "no data" from "zero"), unknown/dynamic column sets (requires dynamic SQL—watch for injection), and very high cardinality pivot keys (don't pivot 1000 columns—reconsider the model).

### Q26. [Theory] What is a deadlock, how do databases detect it, and how do you prevent it?

A **deadlock** is a cycle of transactions each holding a lock the other needs:

```
T1: locks row A, then wants row B
T2: locks row B, then wants row A
        -> neither can proceed; cycle in the wait-for graph
```

Engines run a **deadlock detector** that periodically builds the wait-for graph; on finding a cycle it picks a **victim** (usually the transaction with the least work/log to undo), aborts it, and returns an error (PostgreSQL `40P01`, SQL Server 1205). The application must catch and **retry** the victim.

**Prevention strategies:**
- **Consistent lock ordering**—always acquire locks (or update rows) in the same order, e.g., ascending primary key. This single discipline eliminates most application deadlocks.
- **Keep transactions short**; don't hold locks across user think-time or network calls.
- **Lower isolation** where correctness permits, reducing lock footprint.
- **Use `SELECT ... FOR UPDATE` with `SKIP LOCKED`** for queue-style workloads so workers grab different rows.

### Q27. [Theory] Compare clustered vs non-clustered indexes and the B-tree vs hash vs LSM trade-offs.

A **clustered index** determines the physical row order; the leaf nodes *are* the table rows (InnoDB primary key, SQL Server clustered index). There's at most one per table, and it makes range scans on the clustering key very fast. A **non-clustered (secondary) index** stores the key plus a pointer (or the clustering key) to the row; a lookup may need a second hop to the heap/clustered index unless the index is **covering**.

Index structures:
- **B-tree / B+-tree** — the default; balanced, great for equality *and* range queries and ordered scans. O(log n) lookups.
- **Hash index** — O(1) equality lookups but **no range queries** and no ordering; niche (PostgreSQL hash indexes, memory tables).
- **LSM-tree** (RocksDB, Cassandra, MyRocks) — optimized for **write-heavy** workloads: buffers writes in memory and flushes sorted runs, compacted in the background. Excellent write throughput and compression, at the cost of read amplification and compaction overhead.

The choice maps to workload: B-tree for general OLTP, LSM for ingest-heavy/time-series, hash only for pure point lookups.

---

## 🔴 Expert (15+ yrs)

### Q28. [Theory] Explain serializable snapshot isolation (SSI) and how it differs from two-phase locking.

**Strict two-phase locking (S2PL)** achieves serializability by acquiring shared/exclusive locks and holding them until commit (the "growing then shrinking" phases never overlap with a release before the end). It's correct but blocks heavily and is prone to deadlocks; readers block writers.

**Serializable Snapshot Isolation (SSI)**, pioneered in PostgreSQL 9.1, builds on MVCC: transactions run on snapshots (non-blocking, like REPEATABLE READ) while the engine tracks **read/write dependencies** between concurrent transactions. It detects *dangerous structures*—specifically a pair of rw-antidependencies forming a cycle—that could break serializability, and aborts one transaction with a serialization failure (`40001`). The win is that readers never block and there are no read locks; the cost is that some transactions abort and must be retried, and the engine maintains predicate/SIREAD locks to track conflicts. In practice SSI gives true serializability with far better concurrency than S2PL for read-mostly workloads, provided the application implements a retry loop. Key operational caveat: long-running transactions increase the chance of false-positive aborts and SIREAD lock memory pressure.

### Q29. [Practical] Design a sharding and partitioning strategy for a 50TB multi-tenant OLTP system.

```
                +------------------ Routing layer ------------------+
                |  shard key = tenant_id (hash)  ->  shard 0..N-1    |
                +----------------------------------------------------+
                         |              |               |
                    Shard 0         Shard 1   ...    Shard N-1
                  (PG primary)    (PG primary)      (PG primary)
                   + replicas      + replicas        + replicas
                       |
              Each shard: declarative range partition by created_at (monthly)
```

**Decisions and trade-offs:**

- **Partitioning (within a node)** splits a big table into sub-tables by a key (range by date, list by region, hash). Benefits: partition pruning, cheap `DROP PARTITION` for retention, smaller indexes per partition. It does **not** spread load across machines.
- **Sharding (across nodes)** distributes data over independent databases for horizontal scale. Choose a **shard key** that (a) co-locates data accessed together and (b) spreads load evenly. For multi-tenant, `tenant_id` hash is the natural key—keeps each tenant's data on one shard so most queries avoid cross-shard fan-out.

**Hard problems I'd plan for:** cross-shard joins and transactions (avoid; denormalize or use app-level orchestration / two-phase commit only when unavoidable), **rebalancing** when a shard gets hot (use many logical shards mapped to fewer physical nodes so you move logical shards, à la Citus/Vitess), global uniqueness and sequences (use UUIDv7/snowflake IDs, not per-shard sequences), and **distributed transaction** consistency. I'd lean on a mature solution (Citus, Vitess, CockroachDB, or AWS Aurora Limitless) rather than hand-rolling, because the operational burden of resharding and failover is where teams get hurt. Retention via partition drop on each shard keeps the 50TB bounded.

### Q30. [Theory] How does a cost-based optimizer work, and when do you override it?

A **cost-based optimizer (CBO)** enumerates candidate execution plans (join orders, join algorithms—nested loop/hash/merge, access methods—seq scan/index scan/index-only) and estimates each plan's cost from **statistics**: table cardinalities, column histograms, distinct-value counts (n-distinct), correlation, and configured cost constants (random vs sequential page cost). It picks the cheapest estimated plan. Crucially, its quality is bounded by statistics accuracy; the classic failure is **correlated predicates**—e.g., `WHERE city = 'NYC' AND state = 'NY'`—where the CBO multiplies independent selectivities and badly underestimates rows, choosing a nested loop that explodes.

**When and how to intervene (escalating):**
1. Fix statistics first—`ANALYZE`, raise the histogram target, add **extended/multi-column statistics** (PG `CREATE STATISTICS`) for correlated columns.
2. Rewrite the query—predicate pushdown, avoid functions on indexed columns (`WHERE date_trunc('day', ts) = ...` defeats the index; use a range instead), break monster queries with temp tables.
3. Tune cost constants and `work_mem`.
4. **Hints as a last resort**—Oracle/SQL Server have first-class hints; PostgreSQL needs `pg_hint_plan` or plan management. Hints freeze a plan that may become wrong as data grows, so they incur maintenance debt. I treat a hint as a temporary brace while I fix the real root cause (stats/model).

### Q31. [Behavioral] Tell me about a time a database decision caused a production incident and what you changed.

**Situation:** A new feature shipped a query inside a Spring `@Transactional` service method that also made an external HTTP call to a payment provider *while holding row locks* (`SELECT ... FOR UPDATE` on the account). **Task:** Under Black-Friday load the provider latency spiked, transactions held locks for seconds, lock waits cascaded, and the connection pool exhausted—checkout went down. **Action:** I led the incident: first mitigation was raising the lock-wait timeout to fail fast and shedding load; the real fix was restructuring so the external call happened *outside* the transaction—reserve inventory optimistically with a `version` column, call the provider, then commit or compensate. I added a saga-style compensation for failures and a circuit breaker on the provider. **Result:** checkout recovered and tail latency dropped ~80%. **What I changed systemically:** a lint/review rule banning network I/O inside transactions, plus a load test that injects downstream latency. The lesson I now teach: a transaction's job is to be *short*; anything that can block (user input, network, large batch work) must live outside the lock scope.

### Q32. [Coding] Compute a 7-day moving average and detect anomalies with window frames.

**Problem:** From daily `metrics(dt, value)`, compute a trailing 7-day moving average and flag days where the value deviates more than 2 standard deviations from that trailing window.

```sql
WITH windowed AS (
  SELECT dt, value,
         AVG(value)    OVER w AS ma7,
         STDDEV_SAMP(value) OVER w AS sd7,
         COUNT(*)      OVER w AS n
  FROM   metrics
  WINDOW w AS (ORDER BY dt
               ROWS BETWEEN 6 PRECEDING AND CURRENT ROW)
)
SELECT dt, value, ma7, sd7,
       CASE WHEN n = 7
             AND ABS(value - ma7) > 2 * sd7 THEN 'ANOMALY'
            ELSE 'OK' END AS flag
FROM   windowed
ORDER  BY dt;
```

Note the **explicit `ROWS` frame** (6 preceding + current = 7 rows) rather than the default `RANGE`, so tied/missing dates don't distort the window. The `WINDOW` clause names the frame once and reuses it—cleaner and ensures all three aggregates share an identical window.

- **Time:** O(n) with a streaming window (engines compute frame aggregates incrementally for `ROWS`). **Space:** O(window size) for the running frame, O(n) output.
- **Edge cases:** the first 6 rows have fewer than 7 samples (`n = 7` guard suppresses false anomalies), zero standard deviation (constant values—`ABS(...) > 0` always false, correct), and gaps in dates (if calendar continuity matters, left-join a date spine first so the 7-row window equals 7 calendar days).

### Q33. [Theory] Discuss the CAP/PACELC implications for choosing a SQL database in a distributed setting.

CAP says under a network **P**artition you must choose **C**onsistency or **A**vailability. **PACELC** extends it: *Else* (no partition), you trade **L**atency vs **C**onsistency. This directly shapes distributed-SQL choices. A single-node Postgres/MySQL is strongly consistent but not partition-tolerant by definition (one node). Distributed SQL engines pick a stance: **Spanner/CockroachDB** are CP/PC—they favor consistency (using TrueTime or hybrid logical clocks + Raft consensus, paying latency for cross-region quorum writes) and may reject writes during partitions. **Multi-leader / eventually-consistent** stores favor availability and lower latency but expose conflict resolution. For an OLTP system of record (money, inventory) I choose **strong consistency** (CP/PC) and accept the write-latency cost, often co-locating leaders with the dominant write region and using **follower reads** for low-latency, slightly-stale reads where the use case tolerates it. The architectural skill is mapping each *workload* to its consistency need rather than picking one global setting—reference data can be eventually consistent and cached at the edge while ledgers stay linearizable.

### Q34. [Practical] How do you safely run an online schema migration on a 1-billion-row table with zero downtime?

**Problem:** Add a `NOT NULL` column with a default and a new index to a hot 1B-row table without locking out writes.

```
Phase 1: Add nullable column (metadata-only in PG11+/MySQL8 instant DDL) -> no rewrite
Phase 2: Backfill default in batches (e.g. 10k rows/commit, throttled, off-peak)
Phase 3: Add NOT NULL constraint as NOT VALID, then VALIDATE (PG) -> no long lock
Phase 4: CREATE INDEX CONCURRENTLY (PG) / pt-online-schema-change (MySQL)
Phase 5: Deploy code that reads/writes the column (expand/contract pattern)
```

**Key techniques and trade-offs:**
- Use **instant/metadata-only DDL** where the engine supports it (PostgreSQL adds a nullable column instantly; PG11+ even adds a column *with a constant default* without a full rewrite). Avoid operations that rewrite the whole table under an `ACCESS EXCLUSIVE` lock.
- **Batch the backfill** with small transactions to avoid long-held locks, replication lag, and bloat; throttle to protect the I/O budget.
- `CREATE INDEX CONCURRENTLY` (PostgreSQL) builds without blocking writes (two scans, slower, can't run in a transaction). MySQL: use `pt-online-schema-change` or `gh-ost`, which build a shadow table and swap.
- **Expand/contract (parallel change):** make the schema backward compatible, deploy code that tolerates both shapes, migrate data, then remove the old shape—so app and DB are never incompatible mid-deploy.
- Always have a **rollback plan** and test on a production-sized replica first; watch replication lag, lock_waits, and autovacuum during the run.

### Q35. [Theory] What security risks live at the SQL layer beyond injection, and how do you mitigate them?

SQL injection is the headline risk—mitigated by **parameterized queries/prepared statements** everywhere (never string concatenation), plus least-privilege accounts and allow-listing for any unavoidable dynamic identifiers. But several others matter at staff level:

- **Excessive privilege:** the application account often has more rights than it needs (DDL, access to other schemas). Grant only the minimum; separate read-only and read-write roles; never let the app connect as a superuser/`dba`.
- **Sensitive-data exposure:** use **column/row-level security** (PostgreSQL RLS policies, Oracle VPD) to enforce tenant isolation in the database, not just the app, and column **encryption / masking** for PII; consider TDE for at-rest and TLS for in-transit.
- **Second-order injection:** stored user input later concatenated into dynamic SQL (e.g., in a stored procedure). Parameterize there too.
- **Information leakage via errors:** verbose DB errors returned to clients reveal schema; map them to generic messages.
- **Side-channel / blind injection and timing:** rate-limit and monitor; use a WAF as defense-in-depth, not a substitute for parameterization.
- **Audit and detection:** enable audit logging on sensitive tables and anomalous-query alerting.

Defense in depth means assuming the app tier can be compromised and ensuring the database still enforces tenant isolation and least privilege on its own.

---

## 🧩 Extended Questions — Set 1: Deep Theory & Internals

### 🟢 Basic — extended

#### Q36. [Theory] What is the difference between DELETE, TRUNCATE, and DROP, and why do they behave so differently?

All three remove data, but they operate at completely different layers of the engine, which explains their wildly different performance and transactional semantics.

- **`DELETE`** is a **DML** operation. It removes rows one at a time, firing row-level triggers, evaluating the `WHERE` clause, and writing each removal to the transaction log/WAL so it is fully rollback-able and replication-safe. On most engines it does *not* reclaim space immediately—the rows are marked dead (MVCC) and reclaimed later by VACUUM (PostgreSQL) or purge (InnoDB). It also does not reset identity/sequence counters.
- **`TRUNCATE`** is a **DDL** operation that deallocates the table's data pages wholesale rather than touching rows. It is dramatically faster on large tables because it logs page deallocations, not individual rows. It typically resets the identity column (`TRUNCATE ... RESTART IDENTITY` in PG), cannot have a `WHERE` clause, does not fire row triggers, and in some engines requires no row locks but takes a brief schema/exclusive lock.
- **`DROP`** removes the table object itself—data, indexes, constraints, triggers, and the catalog entry.

```
DELETE   -> per-row, logged, MVCC dead tuples, triggers fire, rollback-able, slow on bulk
TRUNCATE -> page-level dealloc, minimal log, resets identity, DDL lock, very fast
DROP     -> removes the object entirely (schema gone)
```

A common interview trap: "Is `TRUNCATE` transactional?" The honest answer is *it depends on the engine*. In PostgreSQL and SQL Server, `TRUNCATE` is transactional and can be rolled back inside an explicit transaction. In MySQL (InnoDB) and Oracle, `TRUNCATE` performs an **implicit commit** and cannot be rolled back. Knowing this distinction is exactly the kind of engine-awareness senior interviewers probe for.

#### Q37. [Theory] What is the difference between CHAR, VARCHAR, and TEXT, and why does fixed-length CHAR still exist?

`CHAR(n)` is **fixed-length**: the engine always stores (and on most engines, blank-pads to) exactly `n` characters, trimming or padding as needed. `VARCHAR(n)` is **variable-length** with a declared maximum, storing only the actual bytes plus a small length prefix (1–2 bytes). `TEXT` (PostgreSQL) or `VARCHAR(MAX)`/`CLOB` is effectively unbounded variable-length, often stored out-of-line (TOAST in PostgreSQL, LOB pages elsewhere) once it exceeds the page's inline threshold.

The interesting "why" is that in PostgreSQL specifically, `CHAR`, `VARCHAR`, and `TEXT` are stored **identically** under the hood—there is no performance benefit to `VARCHAR(n)` over `TEXT`, and `CHAR(n)` is actually *slower* because of the padding. PostgreSQL docs explicitly recommend `TEXT` or unbounded `VARCHAR`. The length limit is a constraint enforced at write time, not a storage optimization. In other engines (SQL Server, Oracle, MySQL), the calculus differs: fixed-length `CHAR` can be marginally faster for truly fixed data (e.g., a 2-char country code, an MD5 hash) because rows have predictable offsets and no length bookkeeping.

```sql
country CHAR(2)        -- justified: always exactly 2 chars, predictable
status  VARCHAR(20)    -- justified: bounded, but length varies
notes   TEXT           -- justified: unbounded free text
```

The padding behavior of `CHAR` causes subtle bugs: `'US' = 'US   '` may compare equal under standard `CHAR` semantics (trailing-blank-insensitive comparison) but the value you read back may carry trailing spaces in some drivers. For new schemas the practical default is `VARCHAR`/`TEXT`; reserve `CHAR` for genuinely fixed-width codes.

#### Q38. [Theory] Why should you avoid FLOAT/DOUBLE for money, and what does DECIMAL/NUMERIC do differently?

`FLOAT`/`DOUBLE` (and `REAL`) are **binary floating-point** types following IEEE 754. They store a mantissa and exponent in base 2, which means many ordinary decimal fractions—`0.1`, `0.2`, `0.3`—have no exact binary representation and are stored as the nearest approximation. The classic symptom is `0.1 + 0.2 = 0.30000000000000004`. For monetary or any value requiring exact decimal arithmetic, this rounding error accumulates and produces reconciliation failures—pennies that don't add up.

`DECIMAL`/`NUMERIC(p, s)` is **fixed-point / exact**: it stores the value as an integer scaled by the number of decimal places (`s`), with `p` total significant digits. `NUMERIC(10,2)` represents up to 99,999,999.99 with *no* rounding error, because the engine does base-10 arithmetic on the underlying integer. The trade-off is that exact decimal math is slower (often software-emulated rather than using the CPU's FPU) and uses more storage.

```sql
-- WRONG: silent rounding errors accumulate
price DOUBLE PRECISION

-- RIGHT: exact, predictable cents
price NUMERIC(12, 2)
```

The deeper point an interviewer wants: floating point is the correct choice when you need *range and speed over exactness*—scientific measurements, ML features, statistics—where a relative error of 1e-15 is irrelevant. Money, counters, and anything compared for exact equality should use `DECIMAL`/`NUMERIC` or store integer minor units (cents). Choosing the wrong type here is a genuine production bug, not a style preference.

#### Q39. [Theory] What does autocommit mode mean, and how does it interact with explicit transactions?

**Autocommit** is the default session mode in nearly every SQL engine where each individual statement is implicitly wrapped in its own transaction and committed the moment it succeeds (or rolled back if it fails). So a lone `UPDATE` with autocommit on is immediately durable—there is no open transaction to roll back afterward. This is convenient for ad-hoc work but dangerous for multi-statement units of work: if your "transfer" is two separate `UPDATE` statements under autocommit, a crash between them leaves the database half-updated, violating atomicity.

To group statements you either issue an explicit `BEGIN`/`START TRANSACTION ... COMMIT`, or you disable autocommit so a transaction stays open until you explicitly commit. The semantics differ by engine: MySQL and PostgreSQL default to autocommit *on*; issuing `BEGIN` suspends it until `COMMIT`/`ROLLBACK`. Oracle is unusual—it has *no* autocommit by default in SQL*Plus; a transaction implicitly begins with the first DML and stays open until you commit. JDBC defaults `Connection.getAutoCommit()` to `true`, which is why the canonical Java transaction pattern calls `conn.setAutoCommit(false)` first.

```java
conn.setAutoCommit(false);   // open one explicit transaction
debit(conn);                 // not yet durable
credit(conn);                // still not durable
conn.commit();               // both become durable atomically
```

A subtle gotcha: DDL statements (`CREATE`, `ALTER`, `DROP`, and `TRUNCATE` in MySQL/Oracle) cause an **implicit commit** in some engines, silently ending any open transaction. So running a `CREATE TABLE` in the middle of what you thought was one transaction can commit your prior DML unexpectedly. PostgreSQL is the outlier that makes DDL fully transactional.

#### Q40. [Theory] What is a sequence/identity column internally, and why can it leave gaps?

A **sequence** (PostgreSQL `SEQUENCE`, Oracle `SEQUENCE`) or **identity/auto-increment** column (SQL Server `IDENTITY`, MySQL `AUTO_INCREMENT`) is a server-side counter that hands out monotonically increasing numbers for surrogate keys. The crucial internal property is that **sequence allocation is non-transactional**: when a transaction requests the next value, the counter advances immediately and is *not* rolled back if the transaction aborts. This is by design—rolling back the counter would force every nextval to take a lock and serialize all inserts, destroying concurrency.

The consequence is **gaps**. If transaction A grabs id 100 then rolls back, id 100 is gone forever; the next insert gets 101. Caching makes gaps larger: engines pre-allocate a block of values per session (`CACHE 50`, MySQL's `innodb_autoinc_lock_mode`, SQL Server's identity cache of 1000 for `BIGINT`) for performance, so a crash or restart discards the unused cached block. This is why you must **never assume sequence values are gapless or contiguous**—using them to count rows or detect "missing" records is a bug.

```sql
-- PostgreSQL
CREATE SEQUENCE order_seq START 1 CACHE 50;
SELECT nextval('order_seq');   -- advances even if the tx later rolls back
```

Interviewers use this to test whether you understand the tension between correctness guarantees and concurrency. The takeaway: a sequence guarantees **uniqueness and monotonicity within a session**, not global ordering, not gaplessness, and—because of caching across nodes—not even strictly increasing order across concurrent sessions. If you need gapless numbering (invoice numbers for tax compliance), you must implement it separately with its own locked counter table, accepting the serialization cost.

### 🟡 Intermediate — extended

#### Q41. [Theory] Explain how a B+-tree index physically organizes data, including page splits and fill factor.

A **B+-tree** index is a balanced tree where all keys live in the **leaf level** and internal nodes hold only separator keys plus child pointers, forming a sparse routing structure. Leaf pages are linked in a doubly-linked list, which is what makes **range scans** efficient: find the start key via the tree (O(log n)), then walk the leaf chain sequentially. Every leaf is at the same depth, so every lookup costs the same number of page reads—typically 3–4 for tables up to billions of rows, because the fan-out per page is high (hundreds of keys).

The dynamic behavior matters for write performance. When you insert a key into a full leaf page, the engine performs a **page split**: it allocates a new page, moves roughly half the entries over, and propagates a new separator up to the parent (which may itself split, cascading toward the root). Splits cost I/O and, critically, can leave the index **physically fragmented**—logically adjacent leaves end up scattered across the file, so a range scan that should be sequential becomes random I/O.

```
Before insert into full page:        After split:
[ 10 | 20 | 30 | 40 ]   (full)       [ 10 | 20 ]  ->  [ 30 | 40 ]
                                       new separator (30) pushed to parent
```

**Fill factor** (PostgreSQL `fillfactor`, SQL Server `FILLFACTOR`, Oracle `PCTFREE`) controls how full pages are packed at build time. A 100% fill maximizes read density and minimizes the index size but guarantees a split on the very next insert into any page. Leaving free space (e.g., 70–90%) reduces split frequency for tables with random-key inserts at the cost of a larger index. The classic tuning insight: **monotonically increasing keys** (timestamps, identities) always insert at the rightmost leaf, so they never split mid-page and a high fill factor is ideal; **random keys** (UUIDv4) scatter inserts everywhere, causing constant splits and fragmentation—one of the strongest arguments for time-ordered UUIDv7 over random UUIDv4 as a primary key.

#### Q42. [Theory] What is a covering index and an index-only scan, and what determines whether you get one?

A **covering index** is one that contains *every column a query needs*, so the engine can answer the query entirely from the index without ever touching the table's heap/clustered storage—an **index-only scan**. This eliminates the second I/O hop (the "heap fetch" or "bookmark lookup") that a normal secondary index lookup requires, which on large tables is often the dominant cost because those heap pages are scattered randomly.

You make an index covering either by adding columns to the key, or—better for columns you only need to *return* but not *search/sort on*—by using the `INCLUDE` clause (PostgreSQL 11+, SQL Server), which stores the extra columns only in the leaf pages without bloating the internal nodes or affecting key ordering.

```sql
-- Query: SELECT status, amount FROM orders WHERE customer_id = ?
CREATE INDEX idx_cov
  ON orders (customer_id)        -- search key
  INCLUDE   (status, amount);    -- payload columns, leaf-only
```

The crucial PostgreSQL-specific caveat: an index-only scan still requires the engine to confirm each tuple's **visibility** under MVCC, because the index doesn't store transaction visibility info. PostgreSQL uses the **visibility map**—a bitmap marking pages where all tuples are visible to everyone—to skip the heap check. If recent writes have left the visibility map stale (VACUUM hasn't run), PostgreSQL falls back to heap fetches even on a perfectly covering index, and `EXPLAIN` shows `Heap Fetches: N`. So "covering index → index-only scan" is necessary but not sufficient in PostgreSQL; you also need a well-vacuumed table. In SQL Server and Oracle the visibility data lives differently (versioning in tempdb/undo), so the heap-fetch concern manifests differently but the covering-index principle is the same.

#### Q43. [Theory] Explain the difference between a partial/filtered index and a full index, with a real use case.

A **partial index** (PostgreSQL) / **filtered index** (SQL Server) indexes only the subset of rows matching a `WHERE` predicate, rather than every row in the table. The index is physically smaller, cheaper to maintain on writes (rows outside the predicate never touch it), and often dramatically faster for queries whose predicate matches the index's filter, because the index contains *only* the interesting rows.

The canonical use case is a **highly skewed boolean or status column**. Suppose 99% of a 100M-row `orders` table is `status = 'COMPLETED'` and you constantly query the 1% that are `'PENDING'`. A full index on `status` indexes all 100M rows; a partial index indexes ~1M:

```sql
-- PostgreSQL
CREATE INDEX idx_pending ON orders (created_at)
WHERE status = 'PENDING';

-- The planner uses it only when the query predicate implies the filter:
SELECT * FROM orders WHERE status = 'PENDING' ORDER BY created_at;
```

Another powerful use is **enforcing conditional uniqueness**—e.g., "only one active subscription per user" via `CREATE UNIQUE INDEX ... WHERE active`, which standard unique constraints can't express. The trade-off is that the optimizer can only use the index when it can *prove* the query predicate is implied by the index predicate; a query without `status = 'PENDING'` (or a broader predicate) won't use it, so partial indexes are a precision tool, not a general substitute for full indexes. MySQL notably does **not** support partial/filtered indexes (its "prefix indexes" are an unrelated feature indexing the first N characters of a string), which is a real engine-difference worth knowing.

#### Q44. [Theory] How does the optimizer choose between a nested loop, hash join, and merge join?

These are the three physical join algorithms, and the cost-based optimizer picks among them based on input sizes, available indexes, sort order, and memory.

- **Nested loop join**: for each row of the outer (smaller) input, probe the inner input for matches. Cost is roughly O(outer × inner) unless the inner side has an index on the join key, in which case each probe is an O(log n) index lookup and the total is O(outer × log inner). It wins when one side is **small** and the other has an index on the join column—the bread-and-butter join for OLTP point lookups.
- **Hash join**: build an in-memory hash table on the smaller ("build") input keyed by the join column, then stream the larger ("probe") input through it. Cost is roughly O(outer + inner)—a single pass over each. It wins for **large, unindexed equi-joins**, but requires enough memory (`work_mem`); if the build side exceeds memory it **spills to disk** in partitioned batches, degrading performance. Hash joins only work for **equality** predicates.
- **Merge join**: sort both inputs on the join key (or read them already-sorted from indexes), then merge them in a single linear pass like a zipper. Cost is O(n log n) if sorting is needed, O(n) if both inputs arrive pre-sorted. It wins for **large joins where inputs are already ordered** (e.g., both sides have a B-tree index on the join key) and supports range/inequality merges in some engines.

```
          | best when                              | needs equality? | memory
Nested    | small outer + indexed inner            | no              | tiny
Hash      | large unindexed equi-join              | yes             | build side
Merge     | large inputs already sorted on key     | no (PG: yes-ish)| sort buffers
```

The interview insight: the optimizer's choice is driven by **estimated cardinalities**. If statistics make it think the outer side is 10 rows when it's really 10 million, it picks a nested loop and the query explodes—the single most common cause of a query that "suddenly got slow" after data growth or stale stats. This connects directly to why fixing statistics is the first lever, before indexes or hints.

#### Q45. [Theory] Compare hash aggregation vs sort aggregation for GROUP BY. When does each get chosen?

A `GROUP BY` (and `DISTINCT`) can be executed two fundamentally different ways, and seeing which one the optimizer chose in `EXPLAIN` tells you a lot.

**Sort aggregation** sorts all input rows by the grouping key, then makes a single linear pass collapsing consecutive equal-key rows into groups. Cost is dominated by the O(n log n) sort. It shines when the input is **already sorted** (e.g., it arrives ordered from a B-tree index on the group key), reducing the aggregation itself to O(n) with no sort at all, and it produces output already ordered by the group key—handy if an `ORDER BY` on the same columns follows.

**Hash aggregation** builds a hash table keyed by the grouping columns, accumulating each group's running aggregate as it streams rows through. Cost is O(n)—a single pass, no sort. It wins when the input is **unsorted** and the number of *distinct groups* fits in memory (`work_mem`). The risk is memory: if there are far more distinct groups than expected, the hash table can't fit and the engine spills to disk (modern PostgreSQL 13+ handles this gracefully; older versions could blow past `work_mem`).

```
GROUP BY country  (few distinct values, unsorted input)  -> HashAggregate (fast, O(n))
GROUP BY user_id  (millions of groups, input sorted by user_id via index) -> GroupAggregate (sort/stream)
```

The decisive factors are **cardinality of the grouping key** and **input ordering**. Few groups + unsorted input strongly favors hash aggregation; many groups already arriving sorted favors sort/stream aggregation. This is why an index on the group-by columns can transform a query: it removes the sort, lets the engine stream-aggregate, and avoids the memory pressure of a giant hash table—a concrete reason `GROUP BY` performance is so sensitive to indexing and statistics.

#### Q46. [Theory] What is the difference between EXISTS, IN, and a JOIN at the execution-plan level?

Although these often produce identical results, they express different intentions and can map to different physical operators—understanding the mapping is what separates rote knowledge from real understanding.

- **`IN (subquery)`** and **`EXISTS (subquery)`** both express a **semi-join**: "return outer rows that have *at least one* match," with no duplication of the outer row regardless of how many matches exist. Modern optimizers de-correlate both into the same semi-join operator (hash semi-join or nested-loop semi-join), so performance is usually equivalent. The real difference is **NULL semantics**: `NOT IN` with a NULL anywhere in the subquery returns *no rows* (three-valued logic—`x NOT IN (1, NULL)` is `UNKNOWN`), whereas `NOT EXISTS` is NULL-safe and behaves as you intend. This is why the durable rule is "prefer `NOT EXISTS` over `NOT IN` whenever NULLs are possible."
- **A `JOIN`** is an **inner join** that *can multiply* the outer row: if the right side has three matches, you get three output rows. So `JOIN` is only equivalent to a semi-join when you add `DISTINCT` or the join key is unique on the right side. Using a plain `JOIN` for an existence check and then `DISTINCT`-ing away the duplicates is a common anti-pattern that does more work than `EXISTS`.

```sql
-- Semi-join intent: customers who placed at least one order
SELECT c.* FROM customers c
WHERE EXISTS (SELECT 1 FROM orders o WHERE o.customer_id = c.id);   -- no dup, NULL-safe

-- JOIN equivalent needs DISTINCT and can blow up rows first:
SELECT DISTINCT c.* FROM customers c
JOIN orders o ON o.customer_id = c.id;                              -- multiplies then dedups
```

The execution-plan takeaway: `EXISTS`/`IN` let the engine **short-circuit** on the first match (semi-join), while a `JOIN` materializes every match before any deduplication. For "does a related row exist," `EXISTS` is the most precise expression of intent and gives the optimizer the cleanest semi-join plan.

#### Q47. [Theory] What is a LATERAL join (CROSS APPLY) and what problem does it solve that a normal join can't?

A **LATERAL join** (SQL standard `LATERAL`, SQL Server `CROSS APPLY`/`OUTER APPLY`) lets a subquery or table expression on the right side of a join **reference columns from the tables on its left**. A normal join's right-hand subquery is evaluated independently and cannot see the left row; LATERAL removes that restriction, evaluating the right expression *once per left row* with that row's values in scope. It is essentially a correlated subquery promoted to a first-class join input that can return multiple columns and multiple rows.

The problem it uniquely solves is **"top-N per group" returning full rows, parameterized by the outer row**, and **calling table-returning functions per row**. Without LATERAL you'd resort to window functions plus filtering or awkward correlated scalar subqueries (one per output column). With LATERAL it reads naturally:

```sql
-- The 3 most recent orders for each customer, full order rows
SELECT c.id, c.name, o.order_id, o.amount, o.created_at
FROM   customers c
CROSS JOIN LATERAL (
  SELECT order_id, amount, created_at
  FROM   orders
  WHERE  customer_id = c.id          -- references the LEFT table — only legal with LATERAL
  ORDER  BY created_at DESC
  LIMIT  3
) o;
```

Use `CROSS JOIN LATERAL` to drop customers with no orders, or `LEFT JOIN LATERAL ... ON true` to keep them with NULLs. The performance characteristic is that of a nested loop—efficient when the inner query is index-supported (here an index on `orders(customer_id, created_at)` makes each lateral evaluation a cheap index range scan). LATERAL is also the idiomatic way to unnest JSON/arrays per row and to expand set-returning functions alongside their source row. MySQL added `LATERAL` in 8.0.14; SQL Server uses `APPLY`; PostgreSQL and Oracle support `LATERAL` directly.

#### Q48. [Theory] Explain GROUPING SETS, ROLLUP, and CUBE and how they differ from multiple UNION'd GROUP BY queries.

These are extensions to `GROUP BY` that produce **multiple levels of aggregation in a single pass** over the data, instead of writing several separate `GROUP BY` queries glued together with `UNION ALL`.

- **`GROUPING SETS`** is the general form: you explicitly list each set of grouping columns you want, and the result is the union of grouping at each of those granularities. `GROUP BY GROUPING SETS ((region, product), (region), ())` yields per-region-per-product totals, per-region subtotals, and one grand total.
- **`ROLLUP(a, b, c)`** is sugar for a *hierarchical* sequence of grouping sets: `(a,b,c), (a,b), (a), ()`—ideal for drill-down hierarchies like year → quarter → month with subtotals at each level and a grand total.
- **`CUBE(a, b)`** generates *all 2ⁿ combinations* of the grouping columns: `(a,b), (a), (b), ()`—every possible subtotal, used for cross-tabular OLAP reports.

```sql
SELECT region, product, SUM(amount) AS total,
       GROUPING(region)  AS is_region_total,
       GROUPING(product) AS is_product_total
FROM   sales
GROUP  BY ROLLUP (region, product);
```

The key advantage over `UNION ALL` of separate queries is **one scan, not N scans**: the engine reads the base data once and computes all the subtotals together, which on a large fact table is a major efficiency win and also lets the optimizer share sorts/hashes. A subtlety is distinguishing a real `NULL` in the data from a NULL that marks a subtotal row (the column that was "rolled up"). The `GROUPING(col)` function returns 1 when the column is aggregated away for that row and 0 otherwise, letting you label subtotal rows correctly—essential because both look like NULL in the output.

#### Q49. [Theory] What is collation, and how can it silently break comparisons, sorting, and index usage?

**Collation** is the set of rules that determines how text is *compared and sorted*—whether comparisons are case-sensitive, accent-sensitive, and what order characters fall in for a given language. **Character set/encoding** (UTF-8, Latin-1) is separate: it determines how characters are *stored as bytes*. Collation sits on top, defining semantics like "is `'a' = 'A'`?" and "does `'ä'` sort before or after `'z'`?"

Collation silently breaks things in several ways. First, **comparisons depend on it**: under a case-insensitive collation (common default in SQL Server, e.g., `SQL_Latin1_General_CP1_CI_AS`, and MySQL's `utf8mb4_general_ci`), `WHERE name = 'smith'` matches `'Smith'`; under a case-sensitive collation it does not. The same query returns different rows on two servers configured differently—a nasty environment-specific bug. Second, **sorting** changes: German phonebook vs dictionary ordering, Turkish dotless-i, locale-specific rules all alter `ORDER BY`.

```sql
-- Force a specific collation for one comparison
SELECT * FROM users WHERE name = 'smith' COLLATE "C";          -- PostgreSQL: byte order, CS
WHERE name = 'smith' COLLATE Latin1_General_CS_AS;             -- SQL Server: case-sensitive
```

The performance trap is the worst: **an index is built in a specific collation**, and if a query compares using a *different* collation (e.g., you apply `COLLATE` in the predicate, or join two columns with mismatched collations), the engine **cannot use the index** and falls back to a scan—often with a "collation mismatch" error or a silent performance cliff. This is why joining columns across databases with different collations, or filtering with an explicit `COLLATE` that differs from the index's, can turn a millisecond lookup into a full scan. The defensive practice is to choose collation deliberately at the database/column level, keep it consistent across columns you join, and avoid ad-hoc `COLLATE` in hot-path predicates.

#### Q50. [Theory] How do TIMESTAMP, TIMESTAMP WITH TIME ZONE, and DATE differ, and why is "timestamptz doesn't store a timezone" the key insight?

`DATE` stores a calendar date with no time and no zone. `TIMESTAMP` (a.k.a. `TIMESTAMP WITHOUT TIME ZONE`) stores a date and time with **no zone information**—it's a "wall clock" reading with no inherent meaning about which instant in the world it refers to. `TIMESTAMP WITH TIME ZONE` (`timestamptz` in PostgreSQL) is the one people misunderstand most.

The key insight that interviewers love: **`timestamptz` does NOT store a timezone.** Internally it stores a single absolute instant (UTC, as microseconds since an epoch). On *input*, the engine converts the supplied value from the session's timezone (or an explicit offset) to UTC and stores that. On *output*, it converts the stored UTC instant *back* into the session's current timezone for display. So the same row shows `2026-06-16 09:00-04` to a New York session and `2026-06-16 15:00+02` to a Berlin session—it's the *same instant* rendered in two zones. `TIMESTAMP WITHOUT TIME ZONE`, by contrast, does no conversion at all: the bytes you put in are the bytes you get out, with no notion of which instant they denote.

```sql
SET timezone = 'America/New_York';
SELECT '2026-06-16 09:00'::timestamptz;   -- stored as 13:00 UTC
SET timezone = 'Europe/Berlin';
SELECT ts FROM events;                     -- same instant, now displayed as 15:00+02
```

The practical guidance: for "a moment in time that happened/will happen" (logins, transactions, audit events), use `timestamptz`/`TIMESTAMP WITH TIME ZONE` so the instant is unambiguous globally. Use `TIMESTAMP WITHOUT TIME ZONE` only for genuinely zone-less wall-clock values (a recurring 09:00 daily alarm in *local* time, a business's posted opening hour). The single most common bug is storing user-event timestamps in `TIMESTAMP WITHOUT TIME ZONE`, then being unable to reconcile events across regions because you've thrown away the zone the value was recorded in. Note MySQL's quirk: its `TIMESTAMP` *does* convert to/from UTC (and is limited to 1970–2038), while its `DATETIME` does not—different naming, similar concepts.

### 🟠 Advanced — extended

#### Q51. [Theory] What is the Write-Ahead Log (WAL/redo log), and how does it deliver both Atomicity and Durability?

The **Write-Ahead Log** (PostgreSQL WAL, MySQL/InnoDB redo log, Oracle redo log, SQL Server transaction log) is the central mechanism behind ACID's A and D. The cardinal rule is **write-ahead**: before any change to a data page is allowed to reach the data files on disk, a record describing that change must first be written and flushed to the sequential log. Because the log is append-only and sequential, flushing it is fast (sequential I/O), whereas the actual data pages can be written lazily and out of order later.

**Durability** falls out directly: at `COMMIT`, the engine forces (`fsync`) the log up to and including that transaction's commit record to stable storage *before* returning success to the client. Even if the server crashes a microsecond later with the dirty data pages still only in the buffer pool (RAM), recovery **replays** the log forward (redo) and reconstructs every committed change—the committed data is safe because the *log* is safe, not because the data files were updated.

**Atomicity** comes from the recovery protocol's other half. Using ARIES-style recovery (redo then undo), on restart the engine redoes all logged changes to bring pages to their crash-time state, then **undoes** the effects of any transaction that did not have a commit record—rolling back partial work so it's all-or-nothing.

```
COMMIT path:                          Crash recovery:
1. write WAL records (sequential)     1. Analysis: find last checkpoint
2. fsync WAL up to commit record      2. Redo: replay log -> data pages
3. ack client  <-- durable here       3. Undo: roll back uncommitted txns
4. (data pages flushed lazily later)
```

This design also explains performance behaviors: group commit batches many transactions' fsyncs into one to amortize the disk flush; `synchronous_commit = off` (PostgreSQL) trades a small window of durability for throughput by acking before the fsync; and checkpoint storms occur when too many dirty pages must be flushed at once. The WAL is also the foundation of physical replication—shipping the log stream to replicas—so understanding it underpins durability, recovery, and high availability all at once.

#### Q52. [Theory] What is the buffer pool / shared buffers, and how do dirty pages, checkpoints, and the data files relate?

The **buffer pool** (InnoDB `innodb_buffer_pool`, PostgreSQL `shared_buffers`, SQL Server buffer cache) is the in-memory cache of fixed-size **pages** (typically 8KB in PostgreSQL, 16KB in InnoDB) that the engine reads from and writes to. Disk I/O is orders of magnitude slower than RAM, so virtually all reads and writes go through this cache: a query reads a page into the pool if it isn't already there (a cache miss/physical read) and operates on the in-memory copy.

When a transaction modifies a page, that page becomes **dirty**—its in-memory contents now differ from the on-disk version. Crucially, the dirty page is *not* immediately written to the data file; only the WAL record is forced at commit (per the write-ahead rule). This decoupling is what makes commits fast: you pay one sequential log flush, not a random data-file write for every changed page. The actual data-file writes are deferred and batched.

```
            +-----------------------------+
  query --> |   Buffer pool (RAM)         |
            |   [clean][dirty][dirty]...  |
            +------------+----------------+
                         | background writer / checkpoint flushes dirty pages
                         v
            +-----------------------------+
            |   Data files on disk        |
            +-----------------------------+
```

A **checkpoint** is the periodic process that flushes all dirty pages up to a certain log position to the data files and records that position, which (a) bounds crash-recovery time—recovery only needs to replay WAL *after* the last checkpoint—and (b) lets the engine recycle old WAL segments. The tension is that flushing many dirty pages at once causes an I/O spike (a "checkpoint storm"); tuning spreads this out (`checkpoint_completion_target`, InnoDB's adaptive flushing). The eviction policy (clock-sweep/LRU variants) decides which pages to drop when the pool is full, and a clean page can be dropped instantly while a dirty one must be flushed first. Understanding this layer explains why "the second run of a query is fast" (warm cache), why working-set-vs-RAM ratio dominates OLTP performance, and why monitoring the buffer cache hit ratio is a first-line diagnostic.

#### Q53. [Theory] In PostgreSQL, what causes table bloat, what is a HOT update, and how does VACUUM fit in?

PostgreSQL's MVCC implementation never updates a row in place—an `UPDATE` writes an **entirely new tuple version** and marks the old one dead, and a `DELETE` just marks the tuple dead. The old (dead) tuples remain physically in the table's pages until reclaimed. **Bloat** is the accumulation of these dead tuples (and similarly dead index entries): the table occupies far more disk than its live row count justifies, scans read mostly-dead pages, and the buffer cache fills with garbage—silently degrading performance over time. High-churn tables (queues, counters, session tables) bloat fastest.

**VACUUM** is the garbage collector that reclaims dead tuples, marking their space reusable for future inserts/updates within the same table (regular `VACUUM`) without returning it to the OS, while `VACUUM FULL` rewrites the whole table to physically shrink it but takes an exclusive lock. **Autovacuum** runs this automatically based on the fraction of changed rows; the most common production performance regression is autovacuum falling behind on a hot table, letting bloat snowball. VACUUM also updates the **visibility map** (enabling index-only scans) and prevents **transaction ID wraparound**, a catastrophic failure mode if the 32-bit XID counter is allowed to wrap.

```
UPDATE row:   [v1 (dead)] [v2 (live)]   <- old version lingers until VACUUM
HOT UPDATE:   v2 stored in the SAME page, indexes still point at v1's slot
              via a redirect chain -> no index entries rewritten
```

A **HOT (Heap-Only Tuple) update** is a key optimization: if an `UPDATE` does *not* change any indexed column *and* the new version fits on the same page, PostgreSQL chains the new version off the old one within that page and **avoids touching any index**—no new index entries, less WAL, and the dead tuple can be cleaned by a lightweight in-page mechanism without a full VACUUM. This is why "don't index columns you update constantly" and "leave fill-factor headroom on high-update tables" are real tuning levers: both increase the HOT-update rate, dramatically cutting bloat and write amplification. Engines using undo-based MVCC (Oracle, InnoDB) don't bloat the table the same way—they push old versions into undo/rollback segments—so this is a distinctly PostgreSQL concern.

#### Q54. [Theory] What is write skew, and why can SERIALIZABLE catch it when REPEATABLE READ / snapshot isolation cannot?

**Write skew** is a concurrency anomaly that snapshot isolation (and thus PostgreSQL's REPEATABLE READ) permits but serializability forbids. Two transactions each read an overlapping set of rows, each makes a decision based on what it read, and each writes to *different* rows—so there's no direct write-write conflict for snapshot isolation to detect, yet the combined result violates an invariant that each transaction individually preserved.

The classic example is an on-call constraint: "at least one doctor must remain on call." Two doctors are on call. Both transactions run concurrently, each reads "count of on-call doctors = 2 ≥ 1, so it's safe for *me* to go off call," and each updates *its own* row to off-call. Each transaction alone preserved the invariant given its snapshot, but together they leave zero doctors on call.

```
Initial: Alice=on, Bob=on  (invariant: >=1 on call)

T1 (Alice): reads count=2 -> OK to leave -> SET alice=off
T2 (Bob):   reads count=2 -> OK to leave -> SET bob=off
                            (different rows, no write-write conflict)
Result: 0 on call  -- invariant violated, yet each tx saw a consistent snapshot
```

Snapshot isolation can't catch this because it only aborts on **write-write** conflicts to the *same* item (first-committer-wins), and here the two writes touch different rows. **SERIALIZABLE** in PostgreSQL uses Serializable Snapshot Isolation, which additionally tracks **read-write dependencies (rw-antidependencies)**: it notices that T1 read data that T2 wrote and vice versa, forming a dangerous cycle that can't correspond to any serial order, and aborts one transaction with a serialization failure (`40001`). The practical lesson: snapshot isolation is *not* serializable, and the standard mitigations are to (a) use true `SERIALIZABLE` and implement a retry loop, or (b) materialize the conflict by forcing the transactions to touch a common row—e.g., `SELECT ... FOR UPDATE` on a shared lock row or updating a summary/count row—so a write-write conflict is created that even snapshot isolation will detect.

#### Q55. [Theory] What is two-phase commit (2PC), and what are its failure modes in distributed transactions?

**Two-phase commit (2PC)** is the protocol for making a single transaction atomic across multiple independent resources (databases, message brokers) coordinated by a **transaction coordinator**. It runs in two phases:

1. **Prepare (voting) phase:** the coordinator asks every participant to prepare. Each participant does all the work, writes it durably to its log, acquires the necessary locks, and replies *YES (prepared)*—promising it can commit if asked—or *NO (abort)*. Once a participant votes YES, it is in an **in-doubt** state: it must hold its locks and wait, unable to unilaterally commit or abort.
2. **Commit/abort phase:** if all voted YES, the coordinator writes a commit decision to its own log and tells everyone to commit; if any voted NO (or timed out), it tells everyone to abort. Participants act and release locks.

```
Coordinator                Participants
   |---- PREPARE ---------->|   each: do work, flush log, lock, vote YES/NO
   |<--- VOTE (YES/YES) ----|
   | (log COMMIT decision)  |
   |---- COMMIT ----------->|   each: commit, release locks, ack
```

The fundamental weakness is the **blocking problem**: if the coordinator crashes *after* a participant has voted YES but *before* delivering the decision, the participant is stuck in-doubt—it can't commit (maybe someone voted NO) and can't abort (maybe everyone voted YES and the decision was COMMIT). It must **hold its locks indefinitely** until the coordinator recovers, blocking other transactions. This is why 2PC is famously slow and operationally fragile: a single coordinator failure can freeze the whole system, and network partitions during phase 2 leave participants in-doubt.

This is exactly why distributed systems often *avoid* 2PC in favor of **sagas** (a sequence of local transactions with compensating actions), **outbox patterns**, or consensus-based commit (Raft/Paxos, as in Spanner/CockroachDB, which replaces the fragile single coordinator with a fault-tolerant replicated log). The interview-level point: 2PC gives you atomicity across resources but sacrifices availability under coordinator/partition failure (a direct CAP consequence), so at scale you prefer eventually-consistent compensation patterns unless strict cross-resource atomicity is non-negotiable.

#### Q56. [Practical] What are savepoints and nested transactions, and how does an exception inside a transaction behave across engines?

A **savepoint** is a named marker within an open transaction that you can roll back to *partially*, undoing the statements after the savepoint while keeping the work before it and leaving the outer transaction open. True **nested transactions** (independently committable inner transactions) are not supported by mainstream SQL engines; what they provide instead is savepoint-based "nested transaction" emulation—which is precisely what ORMs like JPA/Hibernate use for `@Transactional(propagation = NESTED)`.

```sql
BEGIN;
  INSERT INTO orders ...;          -- keep this
  SAVEPOINT before_lines;
  INSERT INTO order_lines ...;     -- risky
  -- something went wrong with the lines:
  ROLLBACK TO SAVEPOINT before_lines;   -- undo lines, KEEP the order, tx still open
  INSERT INTO order_lines ...;     -- retry differently
COMMIT;
```

The critical engine difference is **what happens to a transaction after a statement raises an error**. In PostgreSQL, *any* SQL error **aborts the entire transaction**: every subsequent statement fails with "current transaction is aborted, commands ignored until end of transaction block," and you can only `ROLLBACK` (or `ROLLBACK TO SAVEPOINT` to a point before the error). This is why PostgreSQL clients/ORMs automatically wrap each statement in an implicit savepoint when you want to continue after a caught error. In MySQL and SQL Server, by contrast, a statement error generally does *not* abort the whole transaction by default—you can catch it and continue—though SQL Server's behavior depends on `SET XACT_ABORT` and the severity of the error.

The practical consequence for application code: never assume "catch the exception and keep going" works uniformly. On PostgreSQL you must roll back to a savepoint taken before the risky statement to continue; on others you may proceed but should still check error severity. Savepoints are the portable primitive for "try a sub-operation, and if it fails, undo just that part"—exactly how retry-on-conflict and partial-failure handling are built inside larger transactions.

#### Q57. [Theory] What is MERGE / UPSERT, and what are the concurrency hazards of "insert if not exists, else update"?

`MERGE` (SQL standard, supported by SQL Server, Oracle, and PostgreSQL 15+) and engine-specific upserts (`INSERT ... ON CONFLICT DO UPDATE` in PostgreSQL, `INSERT ... ON DUPLICATE KEY UPDATE` in MySQL) all solve the same problem: atomically *insert a row if it doesn't exist, otherwise update the existing one*. They collapse the read-then-write race that a naive application-level "SELECT, then INSERT or UPDATE" suffers from.

The concurrency hazard is the heart of the question. The naive pattern—`SELECT` to check existence, then `INSERT` or `UPDATE` based on the result—has a **time-of-check-to-time-of-use** race: two concurrent sessions both check, both see "not present," and both `INSERT`, causing a unique-violation (or duplicate rows if no unique constraint). Wrapping it in a transaction at READ COMMITTED does *not* fix this, because each transaction's check ran before the other's insert committed.

```sql
-- PostgreSQL: atomic, relies on the unique index to arbitrate the race
INSERT INTO inventory (sku, qty) VALUES ('A1', 5)
ON CONFLICT (sku) DO UPDATE SET qty = inventory.qty + EXCLUDED.qty;
```

The robust solution requires a **unique constraint/index** on the conflict key—that's what lets the engine detect the collision atomically and route to the UPDATE branch. Even so, two subtleties bite: (1) `ON CONFLICT` only fires on the *specific* constraint you name, so multiple unique constraints need care; (2) SQL Server's `MERGE` is notorious for **not being atomic against concurrent inserts by default**—without `HOLDLOCK`/serializable hints it can still deadlock or produce primary-key violations under concurrency, a well-documented footgun. The takeaway: prefer the engine's purpose-built upsert (`ON CONFLICT`, `ON DUPLICATE KEY`) backed by a unique index over hand-rolled SELECT-then-write, and on SQL Server treat `MERGE` with explicit locking hints or fall back to `INSERT`-catch-`UPDATE` with retry.

#### Q58. [Theory] How does the optimizer use statistics and histograms, and why do correlated columns wreck estimates?

The cost-based optimizer never looks at your actual data when planning; it estimates row counts (**cardinalities**) from precomputed **statistics**: per-column histograms (the distribution of values into buckets), the number of distinct values (**n-distinct**), the most common values (MCVs) and their frequencies, null fraction, and physical correlation. From these it computes **selectivity**—the fraction of rows a predicate keeps—and propagates estimates up the plan tree to cost each candidate plan. A histogram, for instance, lets it estimate `WHERE price BETWEEN 50 AND 100` by summing the buckets in that range.

The estimates are accurate for *single* predicates but break on **correlated columns**, because the optimizer assumes **independence** by default: it computes the combined selectivity of `WHERE city = 'New York' AND state = 'NY'` as `sel(city) × sel(state)`. But city and state are perfectly correlated—every 'New York' row is already 'NY'—so the true selectivity is just `sel(city)`, while the independence assumption multiplies them and produces an estimate orders of magnitude too low. The optimizer then thinks it'll get 5 rows, picks a nested loop, and the query explodes when 5 million rows actually flow through it.

```sql
-- Tell PostgreSQL these columns are correlated:
CREATE STATISTICS city_state_stats (dependencies, ndistinct)
  ON city, state FROM addresses;
ANALYZE addresses;
```

The fixes follow the diagnosis: keep statistics fresh (`ANALYZE`/`UPDATE STATISTICS`), raise the histogram resolution (`SET STATISTICS`/`default_statistics_target`) on skewed columns, and—decisively—create **extended/multi-column statistics** (PostgreSQL `CREATE STATISTICS`, SQL Server multi-column stats) so the optimizer learns the functional dependency and stops multiplying selectivities. This is the concrete mechanism behind the maxim "most slow-query problems are cardinality-estimation problems": the plan is only as good as the row estimates, and correlated predicates are the most common way those estimates go wrong.

#### Q59. [Theory] What is the difference between prepared-statement plan caching, parameter sniffing, and a generic plan?

When you prepare a parameterized statement, the engine can **cache the execution plan** and reuse it across executions with different parameter values, avoiding re-parsing and re-optimizing every call—a major throughput win for OLTP. But *which* plan it caches creates a classic performance pathology.

**Parameter sniffing** (SQL Server's term; PostgreSQL has an analogous custom-vs-generic mechanism) is when the engine optimizes the plan using the *specific parameter values supplied on the first execution*. If those first values are unrepresentative, the cached plan is great for them and terrible for everyone else. The textbook example: a query `WHERE status = ?` first runs with `status = 'PENDING'` (0.1% of rows → the optimizer picks an index seek), the plan is cached, and then it's reused for `status = 'COMPLETED'` (99% of rows → an index seek with millions of lookups is catastrophic; a full scan would have been right).

```sql
-- PostgreSQL: control the custom-vs-generic decision
SET plan_cache_mode = force_custom_plan;    -- re-optimize every execution (no sniffing risk)
SET plan_cache_mode = force_generic_plan;   -- one shape for all params
-- default 'auto': custom plans for first 5 execs, then a generic plan if it's not costlier
```

A **generic plan** ignores the actual parameter values and is built from average selectivities (`n-distinct`-based), so it's stable and immune to sniffing but can be suboptimal for skewed data. PostgreSQL's default `auto` mode is a compromise: it builds custom plans for the first five executions, and if the generic plan's estimated cost isn't meaningfully worse, it switches to the generic plan to save planning time. The interview-level fixes mirror the engines' tools: `OPTION (RECOMPILE)` or `OPTIMIZE FOR` hints in SQL Server, `force_custom_plan` in PostgreSQL for skewed predicates, or restructuring the query so the skewed predicate is handled separately. The core insight is that **plan caching trades planning cost for the risk of a stale plan chosen under the wrong parameters**, and skewed data distributions are where that trade-off bites.

#### Q60. [Theory] How does declarative table partitioning work internally, and what is partition pruning vs partition-wise join?

**Declarative partitioning** (PostgreSQL `PARTITION BY RANGE/LIST/HASH`, MySQL partitioning, Oracle, SQL Server partitioned tables) splits one logical table into multiple physical **child tables (partitions)**, each holding a disjoint subset of rows defined by a partition key. The table looks like a single table to queries, but the engine routes rows and reads to the relevant partitions. Each partition has its own (smaller) indexes and can be managed independently—dropped, detached, vacuumed, or placed on different storage.

**Partition pruning** is the key read-time optimization: when a query's `WHERE` clause constrains the partition key, the planner *excludes* partitions that cannot contain matching rows, so it scans only the relevant ones. A query for `WHERE created_at >= '2026-06-01'` against a table range-partitioned by month touches only June's partition and its small index, not the whole year's data. Pruning can happen at plan time (constant predicates) or at execution time (parameters/joins resolved at runtime).

```
orders  PARTITION BY RANGE (created_at)
  ├── orders_2026_05   (May rows + its own index)
  ├── orders_2026_06   (June rows + its own index)   <- only this is scanned
  └── orders_2026_07   (July rows + its own index)
        ^ WHERE created_at >= '2026-06-01' AND < '2026-07-01' prunes the rest
```

**Partition-wise join** is the second optimization: when two tables are partitioned compatibly on their join key, the optimizer can join matching partition pairs independently (partition 1 ⋈ partition 1, etc.) instead of joining the full tables, which shrinks each hash/sort and parallelizes naturally. The big operational wins are **retention via cheap `DROP`/`DETACH PARTITION`** (instant, no giant `DELETE`), smaller per-partition indexes, and pruning. The essential caveat for the interview: partitioning splits data **within a single node**—it does *not* spread load across machines (that's sharding), and choosing a partition key that queries don't filter on gives you all the management overhead with none of the pruning benefit.

#### Q61. [Theory] What is the difference between physical (streaming) replication and logical replication, and how does each relate to the WAL?

Both replication forms ship changes from a primary to replicas, but they operate at different abstraction levels with different capabilities.

**Physical (streaming) replication** ships the raw **WAL byte stream** and replays it block-for-block on the replica, producing an exact physical copy of the primary—same page layout, same everything. It's efficient and low-overhead (the WAL already exists for durability, so you're just shipping it), and it's the basis of standby/failover high-availability. Its constraints follow from being physical: the replica must run the *same major version* and architecture, the *entire* cluster is replicated (you can't pick individual tables), and the replica is **read-only** (physical replay can't tolerate independent writes). It enables synchronous or asynchronous modes (trading commit latency for zero data loss) and **follower/hot-standby reads** for offloading read traffic.

**Logical replication** decodes the WAL into a stream of **logical change events** (row inserted/updated/deleted with values) and applies them via normal SQL-equivalent operations on the subscriber. Because it's logical, it can: replicate a **subset of tables**, replicate **between different major versions** (the backbone of near-zero-downtime major-version upgrades), feed data into **different systems** (CDC into Kafka, data warehouses), and the subscriber is a fully writable database that merely receives those changes. The cost is more overhead (decoding, no DDL replication by default, the need for replica identity/primary keys to apply updates) and weaker ordering/consistency guarantees than byte-exact physical replay.

```
Physical:  primary WAL bytes ---ship---> replica replays WAL ---> identical copy (read-only, same version)
Logical:   primary WAL ---decode---> {INSERT/UPDATE/DELETE events} ---> subscriber applies via SQL
                                                                        (subset, cross-version, CDC-capable)
```

The decision maps to purpose: choose **physical** for HA/failover and read scaling of the whole cluster; choose **logical** for selective replication, cross-version upgrades, and change-data-capture into heterogeneous targets. The unifying insight an interviewer is checking: **both are derived from the same WAL**—the WAL isn't just for crash recovery, it's the single source of truth for the database's change history, which is why durability, point-in-time recovery, and replication all rest on it.

### 🔴 Expert — extended

#### Q62. [Theory] Trace the lifecycle of a SQL query from text to result set through the engine's internal stages.

A query passes through a well-defined pipeline, and naming the stages precisely demonstrates real engine understanding.

1. **Parsing**: the raw text is tokenized (lexer) and checked against the SQL grammar (parser), producing an abstract syntax tree. Syntax errors are caught here.
2. **Analysis / semantic validation (binding)**: identifiers are resolved against the system catalog—do these tables and columns exist, are types compatible, does the user have privileges? This binds names to actual catalog objects and produces an annotated query tree.
3. **Rewrite**: rule-based transformations expand views into their definitions, apply row-level-security predicates, expand `*`, and normalize the tree. Subquery flattening/de-correlation often happens here or in the planner.
4. **Planning / optimization**: the cost-based optimizer enumerates equivalent plans—join orders, join algorithms, access paths—estimates each from statistics, and selects the cheapest. For prepared statements this is where the cached/generic-plan decision happens.
5. **Execution**: the chosen plan tree is run, typically via the **Volcano/iterator model** where each operator exposes `next()` and pulls rows from its children on demand (or a vectorized/batched model in modern analytics engines). Buffer-pool pages are read, joins/aggregations performed, and rows streamed out.
6. **Result return**: rows are sent to the client over the wire protocol, often streamed via a cursor rather than fully materialized.

```
SQL text -> [Parse] -> AST -> [Analyze/Bind] -> [Rewrite] -> query tree
         -> [Optimize w/ stats] -> physical plan -> [Execute (iterator/vectorized)] -> rows
```

Two expert-level nuances: first, the **plan cache** sits between optimize and execute—a prepared statement skips parse/analyze/optimize on reuse, which is the throughput foundation of OLTP and the source of parameter-sniffing pathologies. Second, the **iterator (Volcano) model** is why `LIMIT` can be cheap: execution is demand-driven, so the engine can stop pulling rows once `LIMIT` is satisfied rather than computing the full result—provided no blocking operator (a sort without an ordered index, a hash aggregate) sits below the limit and forces full materialization. Understanding which operators are *pipelined* (streamed) vs *blocking* (must consume all input before emitting) explains a huge amount of observed query behavior.

#### Q63. [Theory] How do SQL:2011 system-versioned (temporal) tables work, and what query problems do they solve?

**System-versioned temporal tables** (SQL:2011 standard; implemented in SQL Server as `SYSTEM_VERSIONING`, MariaDB, Oracle Flashback Archive, DB2) let the database automatically track the **full history of every row's changes** with system-managed validity periods, so you can query the table *as of any past point in time*. The engine maintains two timestamp columns—a row-start and row-end (period columns)—and a paired **history table**: every `UPDATE`/`DELETE` automatically copies the prior version into the history table with its valid time range, while the current table holds only live rows.

```sql
-- SQL Server
CREATE TABLE employee (
  id INT PRIMARY KEY,
  salary MONEY,
  valid_from DATETIME2 GENERATED ALWAYS AS ROW START,
  valid_to   DATETIME2 GENERATED ALWAYS AS ROW END,
  PERIOD FOR SYSTEM_TIME (valid_from, valid_to)
) WITH (SYSTEM_VERSIONING = ON (HISTORY_TABLE = employee_history));

-- "What was everyone's salary on 2026-01-01?"
SELECT * FROM employee FOR SYSTEM_TIME AS OF '2026-01-01';
```

The problems it solves cleanly are **point-in-time auditing** ("what did this record look like before the change that caused the incident?"), **regulatory/compliance history** (immutable change trails), **accidental-change recovery** (reconstruct a row's prior state without restoring a backup), and **trend analysis over how data evolved**. The standard distinguishes **system time** (transaction/audit time, machine-managed) from **application/valid time** (the real-world period a fact is true, application-managed)—and **bitemporal** tables track both axes simultaneously, the gold standard for financial and insurance systems where you must answer "what did we *believe* was true on date X, as we knew it on date Y?"

The trade-offs: storage grows with churn (every update keeps a historical copy—pair it with a history-table retention/partitioning strategy), and writes do extra work to maintain history. Before SQL:2011, teams hand-rolled this with audit triggers and `effective_from`/`effective_to` columns—error-prone and easy to get wrong under concurrency. The standardized feature pushes the correctness (especially the atomic period maintenance on update) into the engine, which is why it's preferred over DIY audit tables when the engine supports it.

#### Q64. [Theory] What is the SQL standard, how do its editions (SQL-92 → SQL:2023) differ, and why does "ANSI SQL" portability remain a myth?

**SQL is an ISO/IEC and ANSI standard** that has evolved through major editions: **SQL-86/89** (foundational), **SQL-92** (the big one—the modern join syntax, `CASE`, subqueries, the still-quoted four isolation levels), **SQL:1999** (recursive CTEs, triggers, user-defined types, `WITH RECURSIVE`, boolean/array types), **SQL:2003** (window functions, `MERGE`, `SEQUENCE`, identity columns, SQL/XML), **SQL:2008** (`INSTEAD OF` triggers, `TRUNCATE`, `FETCH FIRST`), **SQL:2011** (temporal/system-versioned tables, windowed `NTILE`/frame extensions), **SQL:2016** (JSON functions, row pattern matching `MATCH_RECOGNIZE`, polymorphic table functions), **SQL:2019** (multidimensional arrays), and **SQL:2023** (the `JSON` data type, property graph queries `SQL/PGQ`, `ANY_VALUE`, `GREATEST`/`LEAST` standardized).

The standard is organized into **conformance levels and optional feature packages** rather than a monolith—and critically, *no vendor implements all of it, and every vendor adds proprietary extensions*. This is the root of why "just write ANSI SQL for portability" is largely a myth.

```
SQL-92    : modern JOIN syntax, isolation levels, CASE, subqueries
SQL:1999  : WITH RECURSIVE, triggers, UDTs
SQL:2003  : WINDOW functions, MERGE, sequences, identity
SQL:2008  : TRUNCATE, FETCH FIRST n ROWS
SQL:2011  : temporal (system-versioned) tables
SQL:2016  : JSON functions, MATCH_RECOGNIZE
SQL:2023  : JSON type, SQL/PGQ property graphs
```

Concrete portability gaps an expert cites: pagination (`LIMIT` in PostgreSQL/MySQL vs `FETCH FIRST n ROWS ONLY` in standard/Oracle/DB2 vs `TOP`/`OFFSET-FETCH` in SQL Server); string concatenation (`||` standard/PG/Oracle vs `+` in SQL Server vs `CONCAT()`); `EXCEPT` vs Oracle's `MINUS`; upsert syntax differing on every engine; data type names (`SERIAL`, `AUTO_INCREMENT`, `IDENTITY`, `SEQUENCE`); date arithmetic; and divergent isolation-level *implementations* despite identical *names* (PostgreSQL REPEATABLE READ ≈ snapshot vs the standard's weaker definition). The practical stance: the standard is a lingua franca that makes core querying transferable knowledge, but production code is engine-specific, and writing truly portable SQL means restricting yourself to a lowest-common-denominator subset that sacrifices most of each engine's strengths—which is why real systems target one engine and use its dialect deliberately.

#### Q65. [Theory] How do foreign-key constraints interact with locking, and why can they cause surprising deadlocks and contention?

A foreign key enforces that every child row references an existing parent, and *checking and preserving* that invariant requires the engine to take **locks on the parent row** during child writes—which is a frequent, non-obvious source of contention and deadlocks. When you insert (or update the FK column of) a child row, the engine must verify the parent exists *and* prevent the parent from being deleted/key-changed concurrently in a way that would orphan the child. To do that it takes a **shared/key-share lock on the referenced parent row** for the duration of the child transaction.

The contention problem arises when **many child rows reference the same parent**: every concurrent transaction inserting children of parent P takes a shared lock on P's row. Shared locks are mutually compatible, so that alone is fine—but if any transaction tries to *update* parent P (needing an exclusive/key-update lock) while children hold shared locks, it blocks, and the classic deadlock emerges when transactions acquire the parent and child locks in opposite orders. PostgreSQL specifically introduced `FOR KEY SHARE`/`FOR NO KEY UPDATE` lock modes (8.x → 9.3 improvements) precisely so that inserting children no longer blocks parent updates that don't touch the key, dramatically reducing this contention—an engine-history detail that shows deep familiarity.

```
T1: INSERT child (parent_id = P)  -> KEY SHARE lock on parent P
T2: INSERT child (parent_id = P)  -> KEY SHARE lock on parent P (compatible, OK)
T3: UPDATE parent SET name=.. WHERE id = P  -> needs lock conflicting with KEY SHARE -> waits
   (+ if T1 later updates parent P, and T3 inserts a child -> opposite order -> deadlock)
```

Two further FK-locking hazards: **unindexed foreign keys** are a notorious performance and locking trap—when you delete or update a parent, the engine must scan the *child* table to check for referencing rows, and without an index on the child's FK column that's a full table scan holding locks (Oracle is especially infamous for table-level locks on unindexed FKs during parent DML). And **cascading actions** (`ON DELETE CASCADE`) amplify lock footprint, since deleting one parent can lock and delete thousands of descendants in one transaction. The practical rules: always index foreign-key columns on the child side, keep transactions that touch hot parent rows short, acquire locks in a consistent order, and be aware that FKs trade a measurable write-time locking cost for the integrity guarantee—occasionally a reason high-throughput systems enforce referential integrity in the application layer instead.

#### Q66. [Theory] How is JSON stored and indexed in a relational engine (JSONB vs JSON), and when should you reach for it over normalized columns?

Modern relational engines support semi-structured JSON as a first-class type, but *how* it's stored matters enormously. PostgreSQL distinguishes **`json`** (stores the exact text verbatim—preserves whitespace, key order, and duplicate keys; re-parsed on every access) from **`jsonb`** (a decomposed *binary* representation—parsed once on input, keys deduplicated and sorted, no whitespace). `jsonb` is slightly slower to write and loses textual fidelity but is far faster to query and—critically—**indexable**. MySQL's `JSON` type and SQL Server's `JSON` (text-backed, with a native binary `json` type added in SQL Server 2025) occupy similar points on this spectrum.

The decisive capability is indexing. You cannot meaningfully B-tree-index arbitrary keys inside a text `json` blob, but `jsonb` supports a **GIN (Generalized Inverted Index)** that indexes every key and value, accelerating containment (`@>`) and existence (`?`) queries:

```sql
CREATE TABLE events (id BIGSERIAL, payload JSONB);
CREATE INDEX idx_payload ON events USING GIN (payload);

-- Uses the GIN index: "events whose payload contains type=login"
SELECT * FROM events WHERE payload @> '{"type": "login"}';

-- Or a targeted B-tree on one extracted key for range/equality:
CREATE INDEX idx_user ON events ((payload->>'user_id'));
```

The architectural guidance an interviewer wants: JSON is right for **genuinely schema-less or sparse, evolving data**—third-party webhook payloads, per-tenant custom fields, event envelopes—where the shape varies row to row and you'd otherwise have a forest of nullable columns or an EAV table. It is *wrong* as a lazy substitute for a real schema: data you filter, join, aggregate, or constrain heavily belongs in typed columns where the planner has statistics, constraints enforce integrity, and B-tree indexes are cheap. The common anti-pattern is dumping core business entities into a JSON column and then fighting the engine to query them—losing foreign keys, type checking, column statistics, and cheap indexing. A pragmatic hybrid is to keep the canonical fields as real columns (often via **generated columns** extracted from the JSON) and reserve JSON for the genuinely variable tail.

#### Q67. [Theory] What is the difference between a server-side cursor and a client-side result set, and why does fetching a large query "OOM the app" or "hold a transaction open"?

When a query returns many rows, *where* those rows live and *when* they're produced is a central engine/driver concern. A **client-side (materialized) result set** ships the entire result to the client/driver buffer up front; the application then iterates an in-memory list. It's simple and releases server resources quickly, but a query returning millions of rows can exhaust the application's heap—the classic "the report job OOMs" incident—because the whole result is buffered at once (MySQL's default JDBC behavior loads the full result into the client unless you opt into streaming).

A **server-side cursor** keeps the result (or the ability to produce it) on the *server* and streams rows to the client in batches as it iterates (`fetch size` / `FETCH n`). The application's memory stays bounded regardless of result size, which is the right tool for large exports and ETL. The cost is that the server holds resources—and, crucially, often **holds a transaction/snapshot open** for the cursor's lifetime. Under MVCC that snapshot pins old row versions, **blocking VACUUM/undo cleanup** and causing bloat if the cursor is iterated slowly (e.g., the app does per-row network calls between fetches). A long-open cursor is therefore a stealthy cause of replication lag and table bloat.

```java
// JDBC server-side streaming (PostgreSQL): autocommit OFF + fetchSize > 0
conn.setAutoCommit(false);                 // required, else PG buffers everything
try (PreparedStatement ps = conn.prepareStatement(
        "SELECT * FROM huge_table", ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
    ps.setFetchSize(1000);                  // stream 1000 rows at a time
    try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) { /* bounded memory */ }
    }
}
conn.commit();
```

The expert framing balances the two failure modes: client-side risks **client memory**; server-side risks **server-held snapshots and locks**. The discipline is to stream large results with a bounded fetch size (avoiding client OOM) *while keeping the cursor's transaction short and the iteration fast* (avoiding long-held snapshots). Drivers also differ sharply—PostgreSQL JDBC requires `autoCommit=false` plus a non-zero fetch size to stream at all; MySQL requires `Statement.FETCH_SIZE = Integer.MIN_VALUE` or `useCursorFetch`—so "set the fetch size" is engine-and-driver-specific, not universal.

#### Q68. [Theory] How does NULL behave in UNIQUE constraints, CHECK constraints, GROUP BY, DISTINCT, and ORDER BY — and where do engines disagree?

Three-valued logic produces a set of NULL behaviors that are *inconsistent across contexts within the very same SQL standard*, which is exactly why it trips people up. The unifying rule is that comparisons with NULL yield `UNKNOWN`, but different language features treat "NULL equals NULL" differently for grouping than for filtering.

- **UNIQUE constraints**: because `NULL = NULL` is UNKNOWN (not true), the standard says NULLs don't violate uniqueness—so a unique column can hold *multiple* NULLs. But engines diverge: PostgreSQL and Oracle allow many NULLs in a unique column; SQL Server historically allows only **one** NULL (treating NULLs as equal for this purpose). SQL:2023 standardized `UNIQUE NULLS [NOT] DISTINCT`, and PostgreSQL 15+ exposes it—`UNIQUE NULLS NOT DISTINCT` makes NULLs collide so only one is allowed.
- **CHECK constraints**: a check passes unless it evaluates to *false*. A NULL input makes most checks evaluate to UNKNOWN, which is **treated as satisfied**—so `CHECK (age >= 0)` does *not* reject a NULL age. This surprises people who expect checks to reject NULLs; you must add `AND age IS NOT NULL` or a separate `NOT NULL` constraint.
- **GROUP BY and DISTINCT**: here NULLs are treated as **equal to each other**—all NULL values collapse into a *single* group / a single distinct value. This is the opposite of the `WHERE`/comparison behavior, and it's deliberate so grouping is useful.
- **ORDER BY**: NULLs sort together but their *position* is engine-specific. PostgreSQL and Oracle default NULLs to **last** in `ASC`; MySQL and SQL Server default them **first**. The standard provides `ORDER BY col NULLS FIRST | NULLS LAST` to make it explicit (PostgreSQL/Oracle support it; MySQL 8 and SQL Server require workarounds like `ORDER BY col IS NULL, col`).

```sql
-- NULLs collide here (one group), but two NULLs satisfy a default UNIQUE constraint:
SELECT region, COUNT(*) FROM sales GROUP BY region;   -- all NULL regions -> ONE group
ORDER BY region NULLS LAST;                            -- explicit, portable intent
```

The senior-level point is that **NULL semantics are context-dependent by design**: equality-for-filtering says "two NULLs are not equal," equality-for-grouping says "two NULLs are equal," and constraints lean toward "permit on unknown." Memorizing the table isn't enough—you must also know the engine defaults (multiple-NULLs-in-unique, NULL ordering) because the *same query returns different results on different engines*, which is a real cross-database migration hazard.

#### Q69. [Theory] What is a connection pool, why is establishing a SQL connection expensive, and what goes wrong when the pool is sized incorrectly?

Establishing a new database connection is **expensive** because it's far more than a TCP handshake: the client and server negotiate the wire protocol, perform authentication (often involving password hashing rounds or TLS negotiation), and—critically in process-per-connection engines like PostgreSQL—the server **forks a dedicated backend process** with its own memory (work_mem allocations, catalog caches). This can take tens of milliseconds, dwarfing the actual query time for an OLTP request. A **connection pool** (HikariCP, PgBouncer, the driver's built-in pool) amortizes this by keeping a set of established connections open and handing them to requests on demand, returning them to the pool afterward rather than tearing them down.

The non-intuitive part is **sizing**. Developers reflexively make the pool huge ("more connections = more throughput"), but the opposite is usually true. Each connection consumes server memory and, more importantly, contends for the real bottleneck resources—CPU cores and disk. Past the point where active connections exceed the server's ability to *do work in parallel*, additional connections just add context-switching, lock contention, and memory pressure, *reducing* throughput. The well-known HikariCP guidance derived from PostgreSQL benchmarks is roughly `connections ≈ ((core_count × 2) + effective_spindle_count)`—often a startlingly small number (e.g., 10–20) that outperforms a pool of hundreds.

```
Undersized pool:   requests queue waiting for a connection -> latency spikes, timeouts
Oversized pool:    too many active queries thrash CPU/disk/locks -> throughput collapses
                   + can exceed the server's max_connections -> hard connection errors
```

The failure modes are symmetric and both common in incidents. An **undersized** pool (or one drained by connections leaked because code forgot to close/return them, or held open across slow external calls) causes request queueing—threads block waiting to borrow a connection, latency climbs, and the app appears "down" though the database is idle. An **oversized** pool (or many app instances each with a large pool) can blow past the server's `max_connections` limit, causing outright connection-refused errors, and even below that limit it degrades the database under load. This connects directly to the transaction-discipline rule: **a connection held during a slow external call or a long transaction is a connection unavailable to others**, so the most effective pool tuning is often *shortening how long each request holds its connection*, not enlarging the pool. At extreme scale, a transaction-level pooler like PgBouncer multiplexes thousands of client connections onto a few server connections, decoupling client concurrency from server backend count entirely.

## 🧩 Extended Questions — Set 2: Practical, Operational & Troubleshooting

### 🟢 Basic — extended

#### Q70. [Practical] How do you read an EXPLAIN / EXPLAIN ANALYZE plan, and what is the difference between the two?

`EXPLAIN` shows the optimizer's **chosen plan and its estimates** without running the query; `EXPLAIN ANALYZE` (PostgreSQL) / `EXPLAIN ANALYZE`/`EXPLAIN (ANALYZE, BUFFERS)` actually **executes** the query and reports the *real* row counts, timings, and buffer activity alongside the estimates. The single most valuable thing you do with the output is compare **estimated rows vs actual rows**: a large divergence is the fingerprint of stale or missing statistics, and almost every "this query suddenly got slow" incident starts there.

Read a plan **inside-out and bottom-up**: the leaf nodes (scans) execute first and feed their parents (joins, aggregates, sorts), with the topmost node producing the final rows. Each node reports an estimated cost range (`cost=startup..total`), estimated rows, and—under `ANALYZE`—`actual time=startup..total rows=N loops=M`. The `loops` field matters: a node showing `rows=5 loops=200000` means it ran 200k times (a nested loop inner side), so the *total* work is 1M rows, not 5.

```
EXPLAIN ANALYZE SELECT * FROM orders WHERE customer_id = 42;

Seq Scan on orders  (cost=0.00..18334.00 rows=3 width=64)
                    (actual time=0.5..210.3 rows=3 loops=1)
  Filter: (customer_id = 42)
  Rows Removed by Filter: 999997     <- read 1M rows to return 3 = missing index
Planning Time: 0.1 ms
Execution Time: 210.4 ms
```

The diagnostic signals I scan for first: a **Seq Scan** with a huge "Rows Removed by Filter" (a missing index), an **estimate vs actual mismatch** (bad statistics), a **Sort** or **Hash** spilling to disk (`Sort Method: external merge Disk: 240000kB` → raise `work_mem`), and **`Heap Fetches: N`** on an index-only scan (VACUUM is behind). Always prefer `EXPLAIN ANALYZE` for real diagnosis—plain `EXPLAIN` only tells you what the optimizer *believes*, and the whole point of debugging a slow query is to find where belief and reality diverge. Caveat: `EXPLAIN ANALYZE` runs the query, so for an `UPDATE`/`DELETE` wrap it in a transaction you roll back.

#### Q71. [Practical] A query is slow only in production but fast in your dev/staging environment. How do you debug that?

This is one of the most common real-world tickets, and the answer is almost never "the SQL is different"—it's the **environment around the SQL**. The first hypothesis is **data volume and distribution**: dev has 10k rows where prod has 500M, so a plan that's fine on a tiny table (seq scan, nested loop) is catastrophic at scale, and skew (one customer with 90% of the orders) defeats plans that assumed even distribution. I reproduce by running `EXPLAIN ANALYZE` *in production* (read-only, safe) and comparing the plan to staging—they're frequently different plans entirely.

The second hypothesis is **statistics**: prod may have stale stats (autovacuum/auto-analyze fell behind after a bulk load), so the optimizer estimates badly and picks a bad join order or a nested loop that explodes. The third is **concurrency and contention** that simply doesn't exist in single-user dev: lock waits, buffer-cache pressure (prod's working set doesn't fit in RAM so it's hitting disk while dev is fully cached), connection-pool saturation, or replication lag if the query runs on a read replica.

```
Checklist for "slow in prod only":
1. EXPLAIN ANALYZE in BOTH envs -> are the plans even the same?
2. Row counts / data skew      -> prod has 1000x data or a hot key
3. Statistics fresh?           -> ANALYZE the table; check last_analyze
4. Cache state                 -> cold cache / working set > RAM (buffer hit ratio)
5. Contention                  -> lock waits, pool exhaustion, replica lag
6. Config differences          -> work_mem, effective_cache_size, plan_cache_mode
7. Parameter sniffing          -> cached plan built for an unrepresentative value
```

The disciplined approach is to **make staging look like production**: restore a prod-sized dataset (anonymized) and match the key config (`work_mem`, `shared_buffers`, `effective_cache_size`, `default_statistics_target`). The trap is "fixing" the SQL based on the dev plan, deploying, and finding nothing changed because the prod plan was never the same plan. I treat the production `EXPLAIN ANALYZE` as the source of truth and reproduce from there.

#### Q72. [Practical] How do you implement pagination, and why is OFFSET-based pagination an anti-pattern at scale?

The naive approach is **OFFSET/LIMIT**: `ORDER BY created_at LIMIT 20 OFFSET 100000`. It's correct and trivial, but it has a hidden cost that makes it an anti-pattern for deep pages: the engine must **generate and discard every row up to the offset**. Page 5,000 means producing 100,000 rows, sorting them, throwing away 99,980, and returning 20. Cost grows linearly with the offset, so the last pages of a large set are pathologically slow, and they get slower as the table grows.

The scalable alternative is **keyset (a.k.a. "seek" or cursor) pagination**: instead of skipping N rows, you remember the sort-key value of the last row on the previous page and ask for rows *after* it. Backed by an index on the sort key, each page is an O(log n) index seek plus a 20-row range scan—constant time regardless of how deep you page.

```sql
-- OFFSET pagination (degrades with depth):
SELECT * FROM orders ORDER BY created_at DESC, id DESC
LIMIT 20 OFFSET 100000;        -- must produce + discard 100k rows

-- Keyset pagination (constant time, index-friendly):
SELECT * FROM orders
WHERE (created_at, id) < (:last_created_at, :last_id)   -- row-value comparison
ORDER BY created_at DESC, id DESC
LIMIT 20;                       -- index seek to the cursor, read 20
```

The critical detail is a **unique, deterministic sort key**. If you paginate by `created_at` alone and timestamps tie, rows can be skipped or duplicated across page boundaries; appending a unique tiebreaker (`id`) and using a row-value comparison `(a, b) < (?, ?)` fixes it. The trade-offs: keyset pagination can't jump to "page 5,000" directly (no arbitrary page numbers) and is awkward for arbitrary user-clickable page links—but for infinite-scroll, "load more," APIs, and any deep traversal it's the correct pattern. A common middle ground is OFFSET for the first few shallow pages (where it's cheap) and keyset for deep traversal or APIs.

#### Q73. [Practical] What is the N+1 query problem, how do you detect it, and how do you fix it?

The **N+1 problem** is the most common ORM-induced performance bug: you run **1 query** to fetch a list of N parent rows, then the code (often a lazy-loaded association in a loop) fires **N additional queries**, one per parent, to fetch each one's children. Fetching 100 orders and then lazily touching `order.getCustomer()` in a loop issues 1 + 100 = 101 round trips, each with its own network latency and parse/plan overhead. The database work per query is trivial, but the **round-trip count** destroys latency—this is death by a thousand cuts, invisible in unit tests on small data and catastrophic in production.

Detection is straightforward once you look: enable SQL logging (Hibernate `show_sql` / `org.hibernate.SQL` at DEBUG, `datasource-proxy`, or a tool like p6spy) and watch for the same parameterized query repeated dozens of times with different bind values, or use APM (application performance monitoring) that flags "N similar queries in one request." In raw SQL the symptom is a loop in application code that runs a query per iteration.

```java
// N+1: 1 query for orders, then 1 per order for its customer
List<Order> orders = repo.findAll();              // 1 query
for (Order o : orders) {
    System.out.println(o.getCustomer().getName()); // N lazy-load queries
}

// Fix: fetch the association up front in ONE query
@Query("SELECT o FROM Order o JOIN FETCH o.customer")   // JPQL join fetch
List<Order> findAllWithCustomer();
```

The fixes: in JPA use `JOIN FETCH`, an `@EntityGraph`, or `@BatchSize` (which turns N single-row queries into a few `IN (...)` batch queries); in raw SQL, **join** or fetch the children in a single `WHERE child.parent_id IN (...)` query and stitch them in memory. The deeper lesson is that the N+1 problem is a **round-trip** problem, not a query-cost problem—so the fix is always "fetch related data in fewer queries," and the durable defense is to make lazy-loading explicit and to load-test against production-sized collections where the N in N+1 is large.

### 🟡 Intermediate — extended

#### Q74. [Practical] You need to delete 100 million rows from a 1-billion-row table without taking the system down. How do you do it?

A single `DELETE FROM events WHERE created_at < '2024-01-01'` against 100M rows is a classic production-killer: it runs in **one giant transaction**, holding locks for the entire duration, generating enormous WAL/redo (which can fill disk and stall replication), bloating the table with dead tuples, and—if it fails or is cancelled near the end—rolling back *everything*, having accomplished nothing while consuming hours. The right approach is **chunked deletion in many small, committed batches**.

```sql
-- PostgreSQL: delete in bounded batches, committing each, until none remain
LOOP (driven by app/script):
DELETE FROM events
WHERE id IN (
  SELECT id FROM events
  WHERE created_at < '2024-01-01'
  ORDER BY id
  LIMIT 10000            -- small batch
);
-- commit; sleep a few ms to let replicas catch up; repeat while rowcount > 0
```

Each batch is its own transaction, so locks are held briefly, WAL is generated incrementally (and can be archived/recycled), and you can stop or throttle at any point with the work so far preserved. The `LIMIT`+`IN` form (or MySQL's `DELETE ... LIMIT 10000`) bounds each transaction; a short sleep between batches throttles I/O and lets read replicas catch up to avoid replication lag. Running `VACUUM` periodically during the campaign reclaims space so the table doesn't keep growing on disk.

The expert move, when you're deleting *most* of a table or a whole time range, is to avoid `DELETE` entirely: if the table is **partitioned by date**, `DROP PARTITION`/`DETACH PARTITION` removes 100M rows instantly with a metadata operation and no dead tuples. Alternatively, **`CREATE TABLE keep AS SELECT ... WHERE created_at >= ...`** the rows you want, then swap names—often faster than deleting the majority. The decision tree: small fraction → batched delete; large fraction or whole range → partition drop or copy-and-swap. The anti-patterns to call out are the single monster `DELETE`, and forgetting that even batched deletes need a `VACUUM`/space-reclaim plan and replica-lag monitoring.

#### Q75. [Practical] Walk me through diagnosing a deadlock incident in production. What do you look at and how do you fix it?

When the app logs deadlock errors (PostgreSQL `40P01 deadlock detected`, MySQL `1213`, SQL Server `1205`), the first thing I do is **read the deadlock report the engine already produced**—I don't guess. PostgreSQL logs both statements and the lock cycle; MySQL exposes the last deadlock via `SHOW ENGINE INNODB STATUS`; SQL Server captures a **deadlock graph** via Extended Events / the system_health session. These tell me exactly which two (or more) transactions deadlocked, which rows/keys each held and waited on, and the SQL involved—that's 90% of the diagnosis.

From the report I identify the **lock-acquisition order**, because a deadlock is by definition a cycle: T1 holds A wants B, T2 holds B wants A. The root cause is almost always **inconsistent ordering**—two code paths that update the same rows in different orders. The canonical fix is to impose a **consistent lock/update order** everywhere (e.g., always update accounts in ascending `id` order, always lock parent before child), which makes the cycle impossible.

```
InnoDB deadlock report shows:
  T1: UPDATE accounts WHERE id=1  (holds), then WHERE id=2 (waits)
  T2: UPDATE accounts WHERE id=2  (holds), then WHERE id=1 (waits)  -> cycle
Fix: every transaction updates ids in ascending order -> no cycle possible
```

Beyond ordering, the other levers: **keep transactions short** (less time holding locks = smaller window for a cycle), **reduce the lock footprint** (more selective `WHERE`, lower isolation where correct, a covering index so the engine locks fewer rows / avoids gap locks under InnoDB REPEATABLE READ), and watch for **missing indexes on foreign keys**, which force the engine to lock far more than the rows you targeted. Critically, deadlocks are *normal* under concurrency and cannot be 100% eliminated, so the application **must** wrap the transaction in a **retry loop with backoff**—the engine kills one victim and that transaction should simply retry, often succeeding immediately. The mature posture is: prevent the common cycles via lock ordering and short transactions, and make the rest survivable via idempotent retries.

#### Q76. [Practical] How do you bulk-load millions of rows efficiently, and why is row-by-row INSERT so slow?

Row-by-row `INSERT` (especially via an ORM `save()` in a loop) is slow for the same round-trip reason as N+1: each statement is a separate network round trip, parse/plan, index maintenance, and—if autocommit is on—a separate transaction with its own WAL fsync. Inserting a million rows this way can take hours and hammers the WAL with a million tiny commits. The fixes attack each of those costs.

The single biggest win is the engine's **bulk-load path**: PostgreSQL `COPY` (and JDBC `CopyManager`), MySQL `LOAD DATA INFILE`, SQL Server `BULK INSERT`/`bcp`. These bypass the per-row statement machinery and stream data in a compact format, often **10–100× faster** than individual inserts. When you must use `INSERT`, **multi-row inserts** (`INSERT INTO t VALUES (...),(...),(...)`) and **JDBC batching** (`addBatch()`/`executeBatch()` with `rewriteBatchedStatements=true` for MySQL) collapse many rows into few round trips inside one transaction.

```java
// JDBC batched insert: one round trip per batch, one transaction
conn.setAutoCommit(false);
try (PreparedStatement ps = conn.prepareStatement(
        "INSERT INTO events(ts, payload) VALUES (?, ?)")) {
    for (int i = 0; i < rows.size(); i++) {
        ps.setTimestamp(1, rows.get(i).ts);
        ps.setString(2, rows.get(i).payload);
        ps.addBatch();
        if (i % 10000 == 0) ps.executeBatch();   // flush every 10k
    }
    ps.executeBatch();
}
conn.commit();
```

The second category of wins is **deferring index and constraint work**: dropping non-essential secondary indexes and FKs before the load and rebuilding them afterward is far cheaper than maintaining them per row (the index B-trees suffer constant page splits during random-key inserts). For an initial load into an empty table, batching in one transaction also lets the WAL be written more efficiently (PostgreSQL can skip WAL for `COPY` into a table created in the same transaction with `wal_level=minimal`). The trade-offs to state: dropping indexes/FKs during a load means the table is temporarily unconstrained and unqueryable for those access paths, so it's an offline/initial-load technique, not something to do on a live table serving traffic. For ongoing high-volume ingest, partition by time and load into the newest partition.

#### Q77. [Practical] What is replication lag, what causes it, and how does it cause subtle application bugs?

**Replication lag** is the delay between a write committing on the primary and that change being visible on a read replica. It exists because replication is typically **asynchronous** (the primary acks the commit without waiting for replicas, for latency) and because the replica must **apply** the change stream, which can fall behind under load. The lag is usually milliseconds but spikes to seconds or minutes during heavy write bursts, long-running transactions on the primary, big `DELETE`/`UPDATE` batches, or when the replica is CPU/IO-bound or single-threaded in apply.

The subtle application bug is **read-your-own-writes inconsistency**: an app writes to the primary, then immediately reads from a replica that hasn't applied the write yet, so the user "saves a profile" and the next page shows the old data—classic "I clicked save but nothing happened" tickets. This is especially nasty because it's intermittent (only when lag exceeds the time between write and read) and invisible in tests where there's one database.

```
write -> PRIMARY (committed at t0)
              | async stream
              v
read  -> REPLICA (applied at t0 + lag)   <- read at t0+5ms sees STALE data
```

The mitigations map to the consistency need of each read. For reads that **must** see the user's own write, **route them to the primary** (sticky "read from primary for N seconds after a write," or per-session "read-your-writes" routing). For reads that tolerate slight staleness (dashboards, search, analytics) replicas are fine. PostgreSQL/MySQL offer **synchronous replication** for zero-lag-loss on critical writes (the primary waits for a replica ack), trading commit latency; some systems use **causal/bounded-staleness reads** (wait until the replica has caught up to the LSN of your write). The operational essentials: **monitor and alert on lag** (PostgreSQL `pg_stat_replication` lag, MySQL `Seconds_Behind_Master`), cap it, and never route monetary or correctness-critical reads to an async replica. The design lesson is that "just add read replicas to scale reads" silently introduces an eventual-consistency model the application must be written to tolerate.

#### Q78. [Practical] A column you filter on heavily has an index, but the query still does a full scan. What are the likely causes?

This is a top-tier debugging scenario, and the causes cluster into a few well-known buckets. The most common is a **non-sargable predicate**—you've wrapped the indexed column in a function or expression, so the engine can't use the index on the raw column. `WHERE UPPER(email) = 'X'`, `WHERE created_at::date = '2026-06-16'`, `WHERE amount + 0 = 100`, or `WHERE substr(code,1,3) = 'ABC'` all defeat a plain index on the column because the index stores the *raw* value, not the transformed one. The fix is to rewrite to a **sargable** form (a range instead of a function) or build a matching **expression/functional index**.

```sql
-- Non-sargable: function on the column -> index unused, full scan
WHERE date_trunc('day', created_at) = '2026-06-16';
-- Sargable rewrite: a range the index can seek
WHERE created_at >= '2026-06-16' AND created_at < '2026-06-17';

-- Or an expression index to match the original predicate:
CREATE INDEX idx_email_lower ON users (LOWER(email));
```

The second bucket is **low selectivity / cost**: if the predicate matches a large fraction of the table (say >5–20%, engine-dependent), the optimizer *correctly* chooses a sequential scan because random index lookups for that many rows are slower than one sequential pass. That's not a bug—the index just isn't selective enough for that value, often due to **data skew** (the value you're filtering is the common one) and is a case for a partial index or accepting the scan. The third bucket is **type/collation mismatch**: comparing an `integer` column to a string literal, or a column to a value with a different collation, forces an implicit cast on the *column* (non-sargable again) or disables the index.

Remaining causes to rule out: **stale statistics** making the optimizer mis-estimate selectivity (run `ANALYZE`); the index being **disabled/invalid** (a failed `CREATE INDEX CONCURRENTLY` leaves an `INVALID` index in PostgreSQL); a **leading-column mismatch** on a composite index (an index on `(a, b)` can't seek on `b` alone); and the engine simply having a **bad plan from parameter sniffing**. My debugging order is: run `EXPLAIN ANALYZE`, check whether the predicate is sargable, check selectivity (how many rows match), check the column/literal types, then check stats and index validity. The lesson interviewers want: "has an index" is necessary but not sufficient—the **query has to be written to let the index be used**, and the optimizer may rationally decline it.

#### Q79. [Practical] How do you find the slowest / most expensive queries running against a database in production?

You don't guess—you let the database tell you. Every major engine has a **query-statistics view or slow-query log** that aggregates execution data, and that's where I start. In PostgreSQL the gold standard is the **`pg_stat_statements`** extension, which records per-normalized-query total time, mean time, call count, rows, and I/O, so you can rank by *total* time (the queries actually consuming the server) vs *mean* time (individually slow queries):

```sql
-- PostgreSQL: top queries by cumulative time spent
SELECT query, calls, total_exec_time, mean_exec_time, rows
FROM   pg_stat_statements
ORDER  BY total_exec_time DESC
LIMIT  20;
```

MySQL has the **slow query log** (`slow_query_log`, `long_query_time`) plus the **Performance Schema** (`events_statements_summary_by_digest`), and the `pt-query-digest` tool to summarize it; SQL Server has **Query Store** (a built-in flight recorder of query plans and runtime stats over time, ideal for "what regressed after the last deploy?") plus Dynamic Management Views like `sys.dm_exec_query_stats`. The key analytical insight is to rank by **total time = mean × calls**, not just mean: a query taking 5ms but called a million times an hour is often a bigger problem than a 2-second report run once a day, and only the cumulative view surfaces it.

For *live* investigation of what's running *right now*—a query that's hung or holding locks—I look at the active-session views: PostgreSQL `pg_stat_activity` (with `wait_event` and `state`, joined to `pg_locks` to find blockers), MySQL `SHOW PROCESSLIST` / `performance_schema`, SQL Server `sp_who2` / `sys.dm_exec_requests`. The full operational workflow is: aggregate views (`pg_stat_statements`/Query Store) to find the chronic offenders, `EXPLAIN ANALYZE` to understand each one, and the activity/lock views to catch acute incidents in real time. The maturity signal is treating query performance as something you **monitor continuously**, not something you only investigate after a complaint.

#### Q80. [Practical] How do you write a database migration that is safe to run on a live system and can be rolled back?

A migration on a live system must satisfy three properties: it must not **lock out** traffic, it must be **backward-compatible** with the currently-running application code (because the deploy isn't atomic—old and new code run simultaneously during a rollout), and it must be **reversible** or forward-fixable. The discipline that delivers all three is the **expand/contract (a.k.a. parallel-change) pattern**, which splits a breaking change into a sequence of individually-safe, backward-compatible steps.

Consider renaming a column `name` → `full_name`. Doing it in one `ALTER` breaks the old app instances mid-deploy. The expand/contract sequence:

```
EXPAND   1. Add new column full_name (nullable, no rewrite)
         2. Backfill full_name from name in batches
         3. Add a trigger (or app dual-write) keeping both in sync
         4. Deploy app that READS/WRITES full_name (old col still maintained)
CONTRACT 5. After the new app is fully rolled out & verified, drop the trigger
         6. Drop the old column name
```

Each step is backward-compatible and independently reversible, so at no point are the database and *any* running app version incompatible. The same pattern handles `NOT NULL` additions (add nullable → backfill → validate constraint with `NOT VALID` then `VALIDATE` to avoid a long lock), type changes (add new column, dual-write, migrate, swap), and table splits.

Operational rules I enforce: migrations live in **version-controlled, ordered, idempotent** files (Flyway/Liquibase/Alembic) so they apply exactly once and the schema version is tracked; **DDL that rewrites a large table or takes `ACCESS EXCLUSIVE` locks is banned during business hours**—use `CREATE INDEX CONCURRENTLY`, `ADD COLUMN` without volatile defaults, and online tools (`gh-ost`/`pt-online-schema-change` on MySQL). For rollback, *additive* migrations (new column/table/index) are trivially reversible by dropping; *destructive* steps (drop column) are deferred to the very end and only after the new code is proven, because you can't un-drop data—so the real "rollback" for a bad deploy is **roll back the app, not the schema**, which only works if the schema stayed backward-compatible. Always test the migration on a **production-sized clone** and have the down-migration written and tested, not improvised during an incident.

#### Q81. [Practical] What is a soft delete, what problems does it introduce, and when would you prefer a hard delete or archival?

A **soft delete** marks a row as deleted (`deleted_at TIMESTAMP NULL` or `is_deleted BOOLEAN`) instead of physically removing it, so the data is recoverable, audit history is preserved, and foreign-key references don't break. It's popular for user-facing "trash/restore" features, regulatory retention, and avoiding cascade-delete complexity. But it's far from free, and a senior engineer names the costs.

The biggest problem is that **every query must now remember to filter `WHERE deleted_at IS NULL`**, and forgetting it anywhere leaks "deleted" data—a correctness and privacy bug that's easy to introduce and hard to catch. ORMs mitigate this with global filters (Hibernate `@Where`/`@SQLDelete`, EF Core query filters), but those have their own surprises (they apply to joins, can be accidentally disabled). Second, **unique constraints break**: if `email` is unique and a user soft-deletes then re-registers, the old "deleted" row still occupies the unique value—you need a partial unique index (`UNIQUE ... WHERE deleted_at IS NULL`) or to include `deleted_at` in the key. Third, **the table grows unbounded** with dead-but-present rows, bloating indexes and slowing scans, and the soft-deleted rows still consume space and appear in `COUNT(*)`-style aggregates if you forget the filter.

```sql
-- Conditional uniqueness so a soft-deleted email can be reused:
CREATE UNIQUE INDEX uq_users_email_active
  ON users (email) WHERE deleted_at IS NULL;

-- Every read must filter; this is the recurring footgun:
SELECT * FROM users WHERE deleted_at IS NULL AND ...;
```

The alternatives and when to prefer them: a **hard delete** is right when there's no recovery/audit requirement and the relationship doesn't need historical references—it keeps the table lean and queries simple. **Archival** (move deleted rows to a separate `*_archive` table or cold storage, then hard-delete from the live table) gives you recoverability *and* a lean operational table, at the cost of more moving parts—my usual recommendation when retention is required but the live table is hot. The decision hinges on requirements: need restore/audit/legal-hold → soft delete or archive; need a fast, simple, lean table → hard delete. I avoid reflexively soft-deleting everything "just in case," because the per-query filter burden and the constraint/bloat problems are a tax paid on every future query forever.

#### Q82. [Practical] Why is SELECT * considered an anti-pattern in production code, beyond "it's wasteful"?

The obvious reason is wasted I/O and network—you pull columns you don't use, including potentially large `TEXT`/`BLOB`/JSON columns that may be stored out-of-line (TOAST/LOB), so a `SELECT *` can trigger expensive de-TOASTing for data you discard. But the deeper, production-grade reasons are about **correctness, stability, and the optimizer**.

First, `SELECT *` **defeats covering indexes**. If you have an index that includes exactly the three columns a query needs, `SELECT col1, col2, col3` can be satisfied by an index-only scan; `SELECT *` forces a heap/clustered-index lookup for the other columns, turning a fast index-only plan into a slower one. So `SELECT *` silently prevents one of the most powerful index optimizations.

```sql
-- index-only scan possible:
SELECT customer_id, status FROM orders WHERE customer_id = ?;   -- covered by idx
-- forces heap fetch for every row:
SELECT * FROM orders WHERE customer_id = ?;                     -- pulls all columns
```

Second, `SELECT *` makes code **fragile to schema changes**. Adding a column changes the result shape: ordinal-based result handling breaks, `INSERT INTO t SELECT * FROM s` breaks when columns are added/reordered, views built on `SELECT *` can behave unexpectedly, and ORMs/serializers may suddenly start shipping a new sensitive column (a PII leak) that nobody intended to expose. Explicit column lists make the contract stable and reviewable. Third, it obscures intent in code review—a reader can't tell what data the code actually depends on.

The honest nuance: `SELECT *` is perfectly fine for **ad-hoc exploration** in a SQL console and is sometimes acceptable in `EXISTS` subqueries (where the column list is irrelevant—the engine ignores it). The rule is about **application code paths**, where explicit columns give you stable contracts, enable covering indexes, prevent accidental data exposure, and minimize I/O. It's one of those "it works fine until it doesn't" patterns—the costs are invisible until a schema change or a TOAST column or a missed index-only scan turns it into an incident.

#### Q83. [Practical] How do query timeouts and statement cancellation work, and how should an application use them?

Without a timeout, a single pathological query can hold locks, pin a connection, and consume CPU/IO indefinitely—one bad query can cascade into pool exhaustion and an outage. **Statement/query timeouts** are the safety valve: they bound how long a statement may run before the engine cancels it and returns an error. Every layer can impose one, and understanding the layering is the practical skill.

At the **database** level: PostgreSQL `statement_timeout` (per session or per role/database), plus `lock_timeout` (cap waiting *for a lock*, distinct from total runtime) and `idle_in_transaction_session_timeout` (kill connections holding a transaction open while idle—a major bloat/lock source). MySQL has `max_execution_time` (a SELECT-only hint, in ms) and `innodb_lock_wait_timeout`. SQL Server uses `LOCK_TIMEOUT` and client-driven `CommandTimeout`. At the **driver/application** level: JDBC `Statement.setQueryTimeout(seconds)` and HikariCP's `connectionTimeout`/`validationTimeout`; the driver sends a cancel request when the timeout fires.

```java
// Application-level timeout: cancel a query that runs too long
PreparedStatement ps = conn.prepareStatement(sql);
ps.setQueryTimeout(5);     // seconds; driver issues a cancel if exceeded
```
```sql
-- PostgreSQL: bound runtime, lock waits, and idle transactions
SET statement_timeout = '5s';
SET lock_timeout = '2s';
SET idle_in_transaction_session_timeout = '30s';
```

The application discipline: set timeouts **at multiple layers** (defense in depth—the DB-side timeout fires even if the app crashes or the network drops, which a client-side timeout can't guarantee), and set them per **workload class**—a 2-second timeout for synchronous user-facing requests but a much longer one for batch/report jobs, ideally via separate roles or connection pools so a slow report can't starve interactive traffic. Two subtleties: cancellation is **cooperative and not instantaneous**—the engine cancels at safe checkpoints, and a statement deep in a tight C loop may take a moment to notice; and a cancelled statement **rolls back** its work (no partial effect for a single statement), but in a multi-statement transaction the surrounding transaction state must be handled (on PostgreSQL the transaction is now aborted and needs rollback). Distinguishing `statement_timeout` (total runtime), `lock_timeout` (waiting for a lock), and `idle_in_transaction_session_timeout` (idle within a transaction) is exactly the granularity a strong interviewer probes.

### 🟠 Advanced — extended

#### Q84. [Practical] Design a backup and point-in-time-recovery (PITR) strategy for a critical OLTP database, and how do you verify it?

A credible strategy layers two things: **periodic full (base) backups** plus **continuous archiving of the WAL/transaction log**, which together enable **point-in-time recovery**—restoring to *any* instant, not just the moment of the last backup. The base backup is your starting snapshot; replaying archived WAL forward from it lets you stop at, say, "11:59:58, one second before the bad `DELETE` ran." Without WAL archiving you can only restore to the last full/incremental backup and lose everything since.

```
PITR mechanics:
[Base backup @ Sunday 00:00] + [archived WAL segments: Sun 00:00 -> now]
   restore base, then replay WAL up to a target:
   recovery_target_time = '2026-06-16 11:59:58'   <- stop just before the mistake
```

The concrete tooling: PostgreSQL uses `pg_basebackup` + `archive_command`/`pg_receivewal` (or managed tools like pgBackRest, Barman, WAL-G); MySQL uses Percona XtraBackup + binlog archiving; SQL Server uses full + differential + transaction-log backups with `RESTORE ... STOPAT`; cloud-managed databases (RDS/Aurora/Cloud SQL) provide automated snapshots + log shipping with a console "restore to point in time." I define the strategy by two business numbers: **RPO (Recovery Point Objective)**—how much data loss is tolerable, which sets backup/WAL-shipping frequency—and **RTO (Recovery Time Objective)**—how fast you must be back, which drives whether you need a hot standby vs restoring from cold storage.

The part most teams get wrong, and the part I emphasize, is **verification**: a backup you have never restored is not a backup, it's a hope. I schedule **automated restore drills**—regularly restore the latest backup to an isolated host, replay WAL, run integrity checks (`pg_amcheck`, `CHECKDB`, row-count/checksum comparisons), and measure the actual restore time against the RTO. I also store backups **off-host and off-region** (a backup on the same disk dies with the server; the same account is vulnerable to a compromised credential or accidental drop), encrypt them, and test the *whole* runbook including credentials and DNS cutover. The complementary defenses: replicas are for *availability/HA*, not backups (a `DROP TABLE` or logical corruption replicates instantly to every replica), and PITR is specifically what saves you from the human-error and bad-deploy class of disasters that HA can't.

#### Q85. [Practical] How do you tune autovacuum in PostgreSQL for a high-write table, and what are the symptoms when it falls behind?

Because PostgreSQL's MVCC leaves dead tuples behind on every `UPDATE`/`DELETE`, **autovacuum** is the background process that reclaims that space, updates the visibility map (enabling index-only scans), refreshes statistics, and—most importantly—prevents transaction-ID wraparound. On a high-write table, the default autovacuum settings (tuned for a generic workload) are frequently too lazy, and the result is **bloat that compounds**: the table and its indexes grow far beyond the live data size, scans get slower because they read mostly-dead pages, and the buffer cache fills with garbage.

The settings that matter are the **thresholds that decide when autovacuum triggers**. By default it fires when dead tuples exceed `autovacuum_vacuum_scale_factor` (0.2 = 20%) of the table plus `autovacuum_vacuum_threshold`. On a 500M-row table, 20% means letting **100 million** dead tuples accumulate before vacuuming—far too much. For hot tables you override per-table to a small scale factor or a fixed threshold, and raise the vacuum **cost limit** so it works faster:

```sql
ALTER TABLE events SET (
  autovacuum_vacuum_scale_factor = 0.02,   -- vacuum at 2% dead, not 20%
  autovacuum_vacuum_cost_limit   = 2000,   -- let it do more work per round
  autovacuum_vacuum_cost_delay   = 2       -- ms; throttle, lower = more aggressive
);
```

The symptoms of autovacuum falling behind, in escalating severity: queries slowing as the table bloats; `pg_stat_user_tables.n_dead_tup` climbing and `last_autovacuum` going stale; index-only scans degrading to heap fetches (visibility map not maintained); replication/long transactions **holding back the xmin horizon** so vacuum *can't* remove tuples even when it runs (a long-open transaction or stale replication slot is the classic "autovacuum is running but bloat keeps growing" cause); and—the dangerous endgame—**transaction ID wraparound** warnings, where if `age(datfrozenxid)` approaches 2 billion, PostgreSQL will eventually refuse writes and shut down to protect data, forcing an emergency single-user vacuum.

The diagnostic and tuning loop: monitor `n_dead_tup`, `n_live_tup`, `last_autovacuum`, and table/index bloat; for high-churn tables lower the scale factor and raise the cost limit so vacuum keeps pace; raise `autovacuum_max_workers` if many tables need attention; and hunt down **long-running transactions and abandoned replication slots** that pin the xmin horizon, because no amount of autovacuum tuning helps if vacuum is forbidden from removing the dead tuples. HOT-update-friendly schema design (don't index volatile columns, leave fill-factor headroom) reduces the dead-tuple rate at the source.

#### Q86. [Practical] A scheduled batch job intermittently fails with serialization or deadlock errors under SERIALIZABLE / high isolation. How do you make it robust?

Under SERIALIZABLE (especially PostgreSQL's SSI) and even REPEATABLE READ, the engine **expects** the application to handle transient aborts: a transaction may fail with a **serialization failure** (`40001`) or **deadlock** (`40P01`) not because of a bug but because the isolation level chose to abort it rather than allow an anomaly. These are **retryable** errors—the canonical, correct response is to roll back and **retry the whole transaction**, not to lower isolation reflexively. A batch job that doesn't retry will fail intermittently under concurrency forever.

The robust pattern is a **bounded retry loop with exponential backoff and jitter**, retrying only on the specific retryable SQLStates:

```java
int attempts = 0;
while (true) {
    try {
        conn.setAutoCommit(false);
        conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        doBatchWork(conn);          // the whole unit, side-effect-free until commit
        conn.commit();
        break;
    } catch (SQLException e) {
        conn.rollback();
        String s = e.getSQLState();
        if (("40001".equals(s) || "40P01".equals(s)) && ++attempts < MAX_RETRIES) {
            Thread.sleep(backoffWithJitter(attempts));   // avoid thundering herd
            continue;               // retry the entire transaction
        }
        throw e;                    // non-retryable or out of attempts
    }
}
```

Two correctness requirements make the retry safe: the transaction body must be **idempotent / side-effect-free until commit**—any external effect (sending an email, calling an API, writing a file) must happen *after* commit or be itself idempotent, otherwise a retried transaction double-fires the side effect. And the retry must redo the **read-and-compute** inside the transaction, not reuse values read in the failed attempt, because the whole point is that the data may have changed.

To *reduce* the abort rate (not just survive it): shrink the transaction's footprint and duration (smaller batches, touch fewer rows, commit more often), order row access consistently to avoid deadlock cycles, add indexes so the engine locks/tracks fewer rows, and—where business rules permit—drop to READ COMMITTED with explicit `SELECT ... FOR UPDATE` on the specific contended rows, which often gives the needed correctness with far less contention than full SERIALIZABLE. The mindset shift the interviewer is checking: under high isolation, **aborts are a normal control signal, and a correct application is one that retries**, with backoff and idempotency, rather than treating every `40001` as a defect.

#### Q87. [Practical] Walk through diagnosing and fixing a "too many connections" / connection-storm incident.

The page fires: the app is throwing "FATAL: sorry, too many clients already" (PostgreSQL) / "Too many connections" (MySQL), requests are timing out, and the database may be pinned. The first diagnostic question is **where did the connections go**—are they *active* (running queries), *idle* (open but doing nothing), or *idle in transaction* (open, holding a transaction and its locks/snapshot)? That distinction points at completely different root causes.

```sql
-- PostgreSQL: triage connection states right now
SELECT state, count(*) FROM pg_stat_activity GROUP BY state;
-- look especially for 'idle in transaction' (held locks + blocked vacuum)
SELECT pid, state, query, now() - xact_start AS xact_age
FROM   pg_stat_activity
WHERE  state = 'idle in transaction'
ORDER  BY xact_age DESC;
```

The common root causes: (1) **leaked connections**—code that borrows from the pool and doesn't return it (missing `try-with-resources`/`close()`), so the pool slowly drains and then every request blocks; (2) **idle-in-transaction** sessions—a `BEGIN` with no matching commit, often because an external call or exception left the transaction dangling, which also holds locks and blocks VACUUM; (3) **pool sized too large × too many app instances**—20 instances × a pool of 50 = 1000 connections demanded against a server `max_connections` of 200, so it's a math problem, not a leak; (4) a **traffic spike or downstream slowdown** where queries suddenly take longer, so connections that used to be held for 5ms are held for 5s and the pool can't keep up; (5) a **thundering herd** after a brief blip where every client reconnects at once.

The immediate mitigation is to **reclaim connections** (kill idle-in-transaction sessions with `pg_terminate_backend`, restart the leaking app instance) and protect the database. The durable fixes follow the cause: enforce **connection return** (try-with-resources, leak-detection in HikariCP via `leakDetectionThreshold`), set **`idle_in_transaction_session_timeout`** so dangling transactions self-heal, and—the architectural fix for the "many instances" math—put a **transaction-pooler like PgBouncer** in front, which multiplexes thousands of client connections onto a small fixed set of real server connections, decoupling app concurrency from `max_connections`. Counter-intuitively, the right move is usually to **shrink** per-app pools (per the `cores × 2` sizing) and add a pooler, *not* to raise `max_connections`, because more backends mean more memory and context-switch contention that degrades the whole server. The lesson: "too many connections" is rarely solved by allowing more connections; it's solved by finding what's holding them and bounding how long anything may hold one.

#### Q88. [Practical] How would you roll out table partitioning on an existing large, live table that wasn't partitioned originally?

The hard part isn't partitioning a *new* table—it's converting an existing 1B-row hot table to a partitioned one **without downtime and without a giant blocking rewrite**. You can't just `ALTER TABLE ... PARTITION BY` an existing populated table in most engines (PostgreSQL can't convert in place; you must create a new partitioned table). So the strategy is a **parallel-table migration with a controlled cutover**, again the expand/contract philosophy applied to physical layout.

```
1. CREATE TABLE orders_part (LIKE orders INCLUDING ALL) PARTITION BY RANGE (created_at);
2. CREATE the partitions (orders_2024_01 ... current + a couple future months).
3. Dual-write: app (or triggers) writes to BOTH orders and orders_part.
4. Backfill historical rows into orders_part in batches (throttled, off-peak).
5. Verify row counts / checksums match between old and new.
6. Cutover: in one transaction, swap names (orders -> orders_old, orders_part -> orders).
7. Point reads/writes at the now-partitioned orders; keep orders_old as a safety net.
8. After a soak period, drop orders_old.
```

The key decisions: choose a **partition key the queries actually filter on** (usually time for an events/orders table) so you get pruning—partitioning on a key nobody filters by gives you all the maintenance overhead and none of the read benefit. Decide the **granularity** (monthly vs daily) by retention and partition count: too many tiny partitions hurt planning time, too few large ones lose the benefits; a few hundred partitions is a reasonable ceiling. Pre-create **future partitions** (and automate it with `pg_partman` or a scheduled job) so an insert never hits a missing partition and errors.

The trade-offs and gotchas to name: the **cutover** is the only delicate moment—a `RENAME` swap inside a transaction is near-instant and reversible, far safer than a long migration window; **primary keys and unique constraints must include the partition key** in PostgreSQL (a global unique index across partitions isn't supported the same way), which sometimes forces a key redesign; **foreign keys to/from a partitioned table** have version-dependent support; and you must keep the **dual-write window** correct so no rows are lost in flight. The payoff that justifies all this: instant retention via `DROP PARTITION` instead of giant `DELETE`s (which is often the *reason* for the migration), smaller per-partition indexes, partition pruning, and the ability to vacuum/maintain partitions independently. I'd validate the whole sequence on a production-sized clone, including the backfill timing and the cutover, before touching production.

#### Q89. [Practical] How do you safely test a query/index change for performance regressions before shipping it?

Shipping an index or query rewrite based on a dev-box `EXPLAIN` is how you cause the "fixed it locally, broke it in prod" incident from Q71. Safe performance work requires reproducing **production-like data, statistics, and configuration**, and measuring the *actual* plan and timing—not eyeballing the SQL. My workflow starts by capturing the real plan: `EXPLAIN (ANALYZE, BUFFERS)` against a production-sized dataset (a restored anonymized clone, or a read replica for read-only `SELECT`s) so the optimizer sees representative cardinalities and the cache behaves realistically.

For an **index** change specifically, PostgreSQL offers a powerful trick: **hypothetical indexes** via the `hypopg` extension, which let the optimizer *consider* an index that doesn't physically exist, so you can see whether the planner would even use it and how the plan changes—**before** paying the cost of building it on a billion rows:

```sql
-- hypopg: ask "would the planner use this index?" without building it
SELECT * FROM hypopg_create_index('CREATE INDEX ON orders (customer_id, created_at)');
EXPLAIN SELECT * FROM orders WHERE customer_id = 42 ORDER BY created_at DESC LIMIT 20;
-- if the plan switches to the hypothetical index, it's worth building for real
```

When I do build it on the clone, I create it **`CONCURRENTLY`** (so the test mirrors how prod will build it without locking) and then re-measure with the cache both cold and warm, because a plan that's fast on a warm cache may be I/O-bound on a cold one. I compare **before/after** on the *same* hardware/data: plan shape, total time, buffers read, and—critically—whether the change helped the target query *without regressing others* (a new index slows writes and may change plans for unrelated queries that suddenly find it attractive).

The broader safety net is to **stage the rollout**: ship the change behind the ability to quickly drop the index (an index is cheap to remove if it backfires), monitor `pg_stat_statements`/Query Store and the slow log for regressions in the hours after deploy, and watch write-latency and index-bloat metrics since every index taxes every write. For risky query rewrites I A/B them where possible (shadow traffic or feature flag). The interview-level point: performance changes are **empirical**, validated against production-shaped data and measured by real plans/timings, with a fast rollback and post-deploy monitoring—never "it looked faster on my laptop."

#### Q90. [Practical] What metrics and signals do you monitor to keep a SQL database healthy, and which ones are leading indicators of trouble?

A healthy-database monitoring practice tracks layers from the OS up through the query workload, and the skill is knowing which signals are **leading** (warn you before users feel pain) vs **lagging** (confirm an outage you're already in). At the **resource** layer I watch CPU, memory, disk space and **disk I/O latency/utilization**, and network—disk-space-free is a leading indicator that's catastrophic if ignored (WAL/log can fill a disk and halt writes), and rising I/O wait often precedes a slowdown as the working set outgrows RAM.

At the **database-internals** layer the high-value signals are: **buffer cache hit ratio** (a dropping ratio means the working set no longer fits in RAM → more disk reads, a leading indicator of degradation); **connection count and states** (climbing `idle in transaction` is a leading indicator of leaks/dangling transactions before they exhaust the pool); **replication lag** (leading indicator of stale reads and failover data loss); **lock waits / blocked sessions** and deadlock rate; **transaction-ID age** (`age(datfrozenxid)` approaching wraparound is a slow-moving but lethal leading indicator); and **table/index bloat and `n_dead_tup`** (leading indicator that autovacuum is losing the race).

```
Leading indicators (act before users notice):
  buffer hit ratio falling | replication lag rising | idle-in-tx growing
  dead-tuple count climbing | disk free shrinking | xid age rising | lock waits up
Lagging indicators (you're already in an incident):
  query latency p99 spiking | error rate (timeouts/too-many-connections) | throughput drop
```

At the **workload** layer I track p50/p95/p99 **query latency** and throughput per query class (via `pg_stat_statements`/Query Store), and watch for plan regressions after deploys (Query Store is purpose-built for "what changed?"). The operational discipline is to **alert on leading indicators with headroom**—e.g., page when disk is 80% full, replication lag exceeds the SLA, idle-in-transaction count or the oldest-transaction age crosses a threshold, or xid age passes a safe mark—so you intervene before the lagging indicators (latency, errors) hit users. The maturity signal is connecting each metric to a concrete failure mode and a runbook action, not just collecting dashboards: a falling cache hit ratio → check working-set/RAM and add memory or tune queries; rising lag → throttle the heavy writer or scale apply; growing idle-in-transaction → find and kill the dangling session and fix the code path that leaked it.

#### Q91. [Coding] Write an idempotent "upsert plus running balance" operation safely under concurrency, and explain the failure modes you're guarding against.

**Problem:** A payments service receives ledger events (possibly **redelivered**, e.g., from an at-least-once message queue) and must (a) record each event exactly once and (b) keep an account's running balance correct, even under concurrent events for the same account. Design the SQL so retries/duplicates don't double-count and concurrent updates don't lose increments.

The two failure modes are **double-processing** (the same event applied twice because the queue redelivered it or the app retried after a timeout) and the **lost update** (two concurrent events read the same balance, each adds its amount, and one overwrites the other). I guard double-processing with an **idempotency key**—a unique constraint on the event id so a duplicate insert is rejected/ignored—and I guard the lost update by doing the balance change as a single **atomic read-modify-write in SQL** (`balance = balance + ?`), never read-into-app-then-write-back.

```sql
-- 1) Record the event exactly once; duplicate delivery is a no-op.
INSERT INTO ledger_events (event_id, account_id, amount)
VALUES (:event_id, :account_id, :amount)
ON CONFLICT (event_id) DO NOTHING;     -- unique(event_id) makes this idempotent

-- 2) Apply to the balance ONLY if the event was newly inserted this call.
--    Atomic increment in the engine = no lost update, no app-side read.
WITH applied AS (
  SELECT :account_id AS acct, :amount AS amt
  WHERE EXISTS (                       -- only when the insert above actually inserted
    SELECT 1 FROM ledger_events
    WHERE event_id = :event_id AND inserted_at = now()::date  -- (illustrative guard)
  )
)
UPDATE accounts a
SET    balance = a.balance + :amount
WHERE  a.id = :account_id;
```

The cleaner production shape does both steps in **one transaction**, keying idempotency off the insert's effect: insert with `ON CONFLICT DO NOTHING`, check the affected-row count, and only run the `UPDATE accounts SET balance = balance + :amount WHERE id = :account_id` when the insert actually added a row. The atomic `balance = balance + :amount` is the crux—it makes the database serialize the increment internally (the row lock is held only for that statement), so two concurrent events for the same account both apply without either reading a stale value. This beats `SELECT balance; balance += amt; UPDATE balance = :newval`, which is the textbook lost update.

The trade-offs and edge cases: the unique idempotency key is what makes the operation **safe to retry**, which is essential with at-least-once delivery—without it, every network timeout risks a double-charge. Under very high contention on one hot account, the per-statement row lock serializes those events (correct but a throughput ceiling); if that's a problem you shard the balance into sub-accounts and sum, or batch events. If you need the **post-update balance** returned, use `UPDATE ... RETURNING balance` (PostgreSQL). And the whole thing must run at READ COMMITTED or higher with the increment expressed in SQL—doing the arithmetic in application code reopens the lost-update hole no matter the isolation level.

#### Q92. [Practical] A query intermittently returns wrong/incomplete results in a paginated API under concurrent writes. What's happening and how do you fix it?

This is a subtle correctness bug, not a performance one, and it has two classic causes. The first is **unstable ordering across pages**: if the API paginates with `ORDER BY created_at` but `created_at` has ties (or isn't unique), the engine is free to return tied rows in *different physical orders* on different page requests, so a row can appear on page 2 *and* page 3, or be skipped entirely between them. Concurrent inserts make it worse by shifting OFFSET boundaries under the user's feet. The fix is a **fully deterministic sort key**—append a unique tiebreaker so ordering is total:

```sql
-- Ambiguous: ties in created_at -> rows can repeat or vanish across pages
ORDER BY created_at DESC LIMIT 20 OFFSET 40;

-- Deterministic: unique tiebreaker -> stable total order
ORDER BY created_at DESC, id DESC LIMIT 20 OFFSET 40;
```

The second cause is **OFFSET pagination over a changing dataset**: between fetching page 1 and page 2, a new row is inserted at the top, so OFFSET 20 now points one row later than the user expects—they see a row twice (it shifted down past the boundary) or miss one. This is inherent to OFFSET pagination: it slices by *position*, and positions move when the underlying set changes. The robust fix is **keyset/seek pagination** (Q72): paginate by "rows after the last-seen key value" rather than by absolute position, so concurrent inserts elsewhere don't shift the user's window. For a fully consistent snapshot across all pages (e.g., a paged export), the heavyweight option is to hold the pagination inside a single **REPEATABLE READ / snapshot** transaction or take a one-time materialized snapshot, so every page sees the data as of one instant.

The diagnostic tell that distinguishes this from a "missing rows" data bug is that it's **intermittent and concurrency-dependent**—it only manifests when writes interleave with paging, and it disappears in single-user testing. The senior framing: pagination correctness depends on a **stable total order** and an awareness that OFFSET semantics are position-based over a possibly-moving set. The durable fixes are deterministic ordering (always) plus keyset pagination (for deep/consistent traversal), reserving snapshot-transaction pagination for the cases that truly need a frozen view across all pages. This connects to Q72: the same OFFSET weaknesses that make it *slow* also make it *incorrect* under concurrency.

### 🔴 Expert — extended

#### Q93. [Practical] Describe an end-to-end zero-downtime database engine/major-version migration (e.g., PostgreSQL 13 → 16, or cross-cloud), including cutover and rollback.

The hard requirement is migrating a live, write-heavy database to a new major version (or new host/cloud) with **near-zero downtime and a safe rollback**, which rules out the simple `pg_dump`/restore (hours of downtime) and even `pg_upgrade` in place (a maintenance window plus no easy rollback once you're on the new binaries). The technique that delivers continuous availability is **logical replication**: stand up the new-version database as a *subscriber*, let it catch up while the old one keeps serving traffic, then cut over when lag is near zero.

```
1. Provision target (new major version / new cloud), matching config & extensions.
2. Initial copy: logical replication does the base copy of existing data.
3. Stream: target continuously applies changes from source via logical replication.
   (source keeps serving 100% of traffic; target tails it, lag -> ~0)
4. Validate: row counts, checksums, sample queries, EXPLAIN plans on target.
5. Cutover window (seconds): stop writes briefly, let target drain remaining lag,
   flip the app's connection string / DNS / proxy to the target.
6. Reverse-replicate target -> source (so rollback is possible without data loss).
7. Soak; if healthy, decommission source. If broken, flip back to source.
```

Logical replication is the right tool *because* it works **across major versions** (unlike physical/streaming replication, which requires identical versions) and even across engines/clouds via the same decode-and-apply model or a CDC tool (Debezium, AWS DMS). The cutover itself is the only downtime, and it's seconds: quiesce writes (or set the source read-only), wait for the subscriber to apply the final changes (lag → 0), then redirect traffic at the proxy/DNS layer. Routing through a proxy (PgBouncer, a service-discovery endpoint) rather than hardcoded hosts makes the flip atomic and instantly reversible.

The expert-level details that make it *safe*: set up **reverse replication** (new → old) before cutover so that if the new version misbehaves you can fail *back* without losing the writes that landed on the new primary—rollback is otherwise the scariest gap. Watch for logical-replication's known gaps: **sequences are not replicated** (you must advance them on the target before cutover or new inserts collide), **DDL isn't replicated** (freeze schema changes during the migration), large objects and some types need care, and every replicated table needs a **replica identity** (primary key) for updates/deletes to apply. I validate on the target with real `EXPLAIN` plans because a new major version can change the planner's choices, and I rehearse the entire runbook—including the rollback path and the sequence-advance step—on a clone before touching production. The summary the interviewer wants: physical replication can't cross versions, so **logical replication + a proxy-level cutover + reverse replication for rollback** is the standard zero-downtime major-upgrade pattern, with sequences, DDL, and replica identity as the gotchas that bite teams who skip the rehearsal.

#### Q94. [Practical] You inherit a system with a UUIDv4 primary key on a huge, write-heavy table and write performance is degrading. Diagnose and fix it.

The symptom—write throughput degrading as the table grows, with high I/O and index bloat—points straight at the **random-insert pathology of UUIDv4 primary keys**. A UUIDv4 is fully random, so each insert lands at an unpredictable position in the primary key's B-tree. Unlike a monotonically increasing key (which always appends to the rightmost leaf, keeping recently-touched pages hot in cache), random keys scatter inserts across the *entire* index, so every insert dirties a different, likely-cold page that must be read from disk, modified, and written back. As the index grows past RAM, the buffer cache hit ratio for these random writes collapses and you get **write amplification, constant page splits, and severe index fragmentation/bloat**.

```
Sequential key (id):     inserts -> always the rightmost leaf -> hot page, no splits
UUIDv4 (random):         inserts -> scattered across ALL leaves -> cold pages, splits
                         -> random write I/O, low cache hit, fragmentation, bloat
```

The first-line diagnosis confirms this with `EXPLAIN`/stats: high `shared_buffers` misses on insert, index bloat (compare index size to a freshly-rebuilt copy), and—on InnoDB specifically—UUIDv4 as the *clustered* primary key is doubly bad because the **entire row** is stored in primary-key order, so random PKs scatter the whole table, and every secondary index stores the bulky 16-byte random PK as its row pointer, inflating every index.

The fix is to move to a **time-ordered identifier**: **UUIDv7** (timestamp-prefixed, so values increase roughly monotonically while remaining globally unique and non-coordinated), or a **Snowflake/ULID** style ID, or a plain `BIGINT` sequence if you don't need client-side/distributed generation. Time-ordered keys restore the append-at-the-end insert pattern—hot rightmost pages, no mid-page splits, minimal fragmentation—recovering most of the lost write throughput while keeping the "generate IDs without a round trip / globally unique across shards" benefits that motivated UUIDs in the first place. If you can't change the PK immediately, interim mitigations include rebuilding/reindexing to reclaim bloat (`REINDEX CONCURRENTLY`), lowering fill factor to absorb splits, and on SQL Server using a *sequential* GUID (`NEWSEQUENTIALID()`) or a separate `BIGINT` clustering key with the UUID as a non-clustered unique key.

The migration itself follows expand/contract: add the new time-ordered key, dual-write/backfill, switch foreign-key references and the clustering/PK over a controlled cutover. The interview-grade insight ties back to Q41's page-split mechanics: **primary-key choice is a physical-storage decision**, not just a uniqueness one, and random UUIDs as a clustering key fight the B-tree's append-friendly nature—one of the most common avoidable causes of write-performance decay in growing systems.

#### Q95. [Practical] How do you safely change a column's data type (e.g., INT → BIGINT on a 2-billion-row PK) without downtime, and why is the obvious ALTER dangerous?

The naive `ALTER TABLE ... ALTER COLUMN id TYPE BIGINT` is dangerous because on most engines it **rewrites the entire table** under an `ACCESS EXCLUSIVE` lock (PostgreSQL) or a full table copy (older MySQL): every row is rewritten to the new width, every index rebuilt, all while blocking reads and writes for the *hours* that takes on 2 billion rows, generating massive WAL, and risking disk exhaustion. For a primary key it's worse—the change cascades to every foreign key and secondary index. This is the classic "we ran an ALTER and the site went down for three hours" incident, and the `INT`→`BIGINT` PK case is especially urgent because it usually means you're **approaching INT's 2.1-billion limit**, where running out of IDs causes a hard outage (inserts fail entirely).

The zero-downtime approach is, once again, **expand/contract with a new column and a controlled cutover**, never an in-place rewrite of the live column:

```
1. ADD COLUMN id_new BIGINT  (nullable -> metadata-only, instant, no rewrite).
2. Dual-write: app/trigger writes id_new = id on every INSERT/UPDATE going forward.
3. Backfill id_new = id for existing rows in throttled batches (10k/commit).
4. Build a UNIQUE index on id_new CONCURRENTLY (no write lock).
5. Add/repoint foreign keys to id_new (each child gets a bigint FK col, same dance).
6. Cutover in a short transaction: swap primary key from id to id_new, rename cols.
7. Drop the old INT column after a soak period.
```

The reason each step is safe is that **adding a nullable column is metadata-only** (no rewrite) on PostgreSQL 11+ and MySQL 8 instant DDL, the backfill is batched so locks are brief and WAL is incremental, and `CREATE INDEX CONCURRENTLY` builds without blocking writes. The cutover—swapping which column is the PK and renaming—is a brief metadata operation, not a data rewrite. Foreign keys are the multiplier: each referencing table needs its own widened column and backfill, so a PK type change is really a *coordinated multi-table* migration, which is why teams should widen to `BIGINT` proactively long before hitting the limit rather than under emergency.

The engine nuances worth citing: PostgreSQL can't change a column type without a rewrite for incompatible types, so the new-column dance is mandatory; MySQL 8's `ALGORITHM=INPLACE`/`INSTANT` helps for *some* changes but a PK widening generally still copies, so `gh-ost`/`pt-online-schema-change` (shadow-table-and-swap) is the standard online tool. The recurring expert principle across Q80/Q88/Q93/Q94 and this question: **never do an operation that rewrites or exclusively locks a large hot table in place**—decompose it into additive, backward-compatible steps with batched backfills and a metadata-only cutover, and rehearse on a production-sized clone with the rollback path written down.

#### Q96. [Practical] How do you decide between fixing a slow query with an index, a query rewrite, a materialized view, denormalization, or caching? Give the decision framework.

These are five tools at escalating cost/complexity, and a senior engineer chooses by **diagnosing the bottleneck first** (via `EXPLAIN ANALYZE`) and then picking the **lowest-cost option that addresses the actual cause**—not reaching for the heaviest hammer. The framework runs from cheapest/most-reversible to most-invasive:

```
Diagnose with EXPLAIN ANALYZE, then escalate only as needed:
1. Statistics       -> stale estimates? ANALYZE / extended stats   (free, instant)
2. Index            -> missing access path? composite/covering/partial index
3. Query rewrite    -> non-sargable / bad shape? sargable predicate, EXISTS, fewer joins
4. Materialized view-> expensive aggregate read >> base-data change rate? precompute
5. Denormalization  -> chronic multi-join hot path? duplicate columns + sync strategy
6. Caching          -> read-heavy, staleness-tolerant, key-able? app/Redis cache
```

The reasoning per tier: **statistics and indexes** are the default first moves because they're cheap, fast to apply, and reversible—an index is a localized change you can drop if it backfires, and most "slow query" problems are really missing-index or stale-stats problems (per Q24/Q44/Q58). A **query rewrite** is next when the SQL is the problem—non-sargable predicates (Q78), an accidental `JOIN`-then-`DISTINCT` where `EXISTS` belongs (Q46), or a monster query the optimizer mis-plans—because it costs nothing at runtime and often beats adding indexes.

The heavier tools trade freshness or write-complexity for read speed and should be justified by the **read/write ratio and staleness tolerance**. A **materialized view** is right when an expensive aggregation is queried far more often than the base data changes and consumers tolerate refresh-interval staleness (dashboards, rollups)—you pay storage and a refresh strategy. **Denormalization** (duplicating a column to avoid a chronic join) is the next step when even a materialized view is too coarse, but it adds a permanent **consistency burden** (the copy must be kept in sync via triggers/app logic/CDC), so I require measured evidence of a hot path, not a hunch. **Caching** (application-level or Redis) sits outside the database and is ideal for read-heavy, key-addressable, staleness-tolerant data, but it introduces invalidation complexity ("there are only two hard problems...") and a consistency model the app must own.

The meta-principle is **escalate by cost and reversibility, guided by measurement**: try stats → index → rewrite (cheap, local, reversible) before materialized view → denormalization → cache (storage, staleness, sync/invalidation complexity). I also weigh *who else* a change affects: an index taxes all writes; denormalization spreads consistency logic across the codebase; caching moves correctness into the app. The wrong move is jumping straight to a cache or denormalization for a problem a one-line index or a sargable rewrite would have solved—you've added permanent complexity to dodge a cheap fix. Conversely, hammering indexes at a problem that's fundamentally "this 12-table aggregate is recomputed on every dashboard load" is the case where a materialized view is genuinely the right, if heavier, answer.

#### Q97. [Practical] A production query is hung right now, holding locks and blocking other sessions. How do you find and safely resolve it live?

This is an acute incident—something is *currently* blocking, and you need to find the head of the blocking chain and decide whether to kill it without making things worse. The first move is to query the **live activity/lock views** and build the blocking tree, because the offender is usually one root transaction that everything else is queued behind. PostgreSQL exposes this directly with `pg_blocking_pids()`:

```sql
-- PostgreSQL: find blocked sessions and exactly who is blocking them
SELECT  blocked.pid          AS blocked_pid,
        blocked.query        AS blocked_query,
        blocking.pid         AS blocking_pid,
        blocking.state       AS blocking_state,
        now() - blocking.xact_start AS blocker_xact_age,
        blocking.query       AS blocking_query
FROM    pg_stat_activity blocked
JOIN    LATERAL unnest(pg_blocking_pids(blocked.pid)) AS bpid ON true
JOIN    pg_stat_activity blocking ON blocking.pid = bpid
WHERE   cardinality(pg_blocking_pids(blocked.pid)) > 0;
```

Before killing anything I characterize the blocker: is it **active** (a long-running query making progress—maybe let it finish or it'll just restart), **idle in transaction** (a dangling `BEGIN` with no commit—almost always safe and correct to terminate, since it's doing nothing but holding locks), or a **long write transaction** whose rollback will itself be expensive? The two tools differ in violence: PostgreSQL `pg_cancel_backend(pid)` cancels the *current statement* gracefully (preferred—try it first), while `pg_terminate_backend(pid)` kills the whole connection (use when cancel doesn't work or the session is idle-in-transaction). MySQL uses `KILL QUERY id` vs `KILL CONNECTION id`; SQL Server uses `KILL session_id`.

```sql
SELECT pg_cancel_backend(12345);     -- gentle: cancel the running statement
SELECT pg_terminate_backend(12345);  -- forceful: drop the whole session
```

The safety judgment is the expert part: terminating a transaction triggers a **rollback**, and rolling back a transaction that has done a lot of work can take as long as the work itself and generate more I/O—so killing a near-complete giant `UPDATE` can prolong the incident, whereas killing an idle-in-transaction session is instant relief. I prefer `cancel` over `terminate`, kill the **root** blocker (not the victims queued behind it, which will resolve once the root releases), and capture the offending SQL before killing so I can fix the root cause—a missing `commit`, a lock held across an external call, or a missing index forcing a broad lock. The follow-up is preventive: `idle_in_transaction_session_timeout`, `statement_timeout`, and `lock_timeout` (Q83) so the system self-heals next time instead of needing a human at 3 a.m.

#### Q98. [Practical] You run a web app on Spring/JPA and see mysterious "connection is closed" / "transaction silently rolled back" / stale-data errors. What transaction-boundary mistakes cause these?

ORM transaction-boundary bugs are a whole class of production incidents, and they trace to misunderstanding where the transaction *actually* begins and ends versus where the code thinks it does. The first classic is **`@Transactional` self-invocation**: in Spring, `@Transactional` is implemented by a proxy, so when a method in a bean calls *another* `@Transactional` method on `this`, the call bypasses the proxy and **no transaction is started**—the annotation silently does nothing. The symptom is writes that aren't atomic or `LazyInitializationException` because there's no session, and it's baffling because the annotation is right there. The fix is to call through the proxy (inject the bean into itself or split into another bean).

The second is the **`LazyInitializationException`**: the transaction (and Hibernate session) closes when the `@Transactional` service method returns, but the controller/view then touches a lazily-loaded association—no session, exception. This is the ORM face of the N+1/lazy-loading issue (Q73); the fix is to fetch what you need inside the transaction (`JOIN FETCH`/entity graph), not to keep the session open into the view layer (the "open session in view" anti-pattern, which masks the problem and worsens connection hold time).

```java
// BUG: self-invocation -> inner @Transactional is ignored (no proxy)
@Service class OrderService {
  public void place(Order o) { save(o); }            // NOT proxied
  @Transactional public void save(Order o) { ... }   // annotation does nothing here
}
// BUG: exception inside a tx marks it rollback-only; caller's commit then fails
//      with "Transaction silently rolled back because it has been marked as rollback-only"
```

The third is the **"silently rolled back" / "rollback-only"** error: a runtime exception thrown *inside* an inner transactional boundary marks the transaction **rollback-only**; if an outer layer catches that exception and tries to commit, the commit fails with "Transaction silently rolled back because it has been marked as rollback-only." This stems from Spring's default rollback rule (rolls back on unchecked exceptions, **not** on checked exceptions unless you set `rollbackFor`), so a caught-and-swallowed exception leaves the transaction poisoned. The fourth—the "connection is closed" / pool-exhaustion class—is **holding the transaction (and thus the connection) across slow work**: an external HTTP call or a long computation inside `@Transactional` keeps the DB connection borrowed the whole time (echoing Q31), draining the pool under load.

The durable disciplines: keep `@Transactional` methods **short and free of network I/O**; understand the **propagation** semantics (`REQUIRED` joins an existing transaction—so an exception anywhere can poison the whole thing; `REQUIRES_NEW` suspends and starts a fresh one; `NESTED` uses a savepoint per Q56); set `rollbackFor`/`noRollbackFor` deliberately rather than relying on the unchecked-only default; fetch associations explicitly to avoid lazy-loading after the session closes; and never rely on self-invoked `@Transactional`. These bugs are invisible in single-user dev and surface as intermittent production errors—exactly why interviewers probe them.

#### Q99. [Practical] For a specific real workload, how do you choose an isolation level rather than just defaulting?

The mistake is treating isolation as a global "always READ COMMITTED" (or "always SERIALIZABLE to be safe") setting. The senior approach is to choose **per workload**, matching the level to the *specific anomalies that workload can't tolerate* and weighing that against the concurrency/abort cost—because higher isolation buys correctness at the price of more aborts (under MVCC) or more blocking (under locking). I reason from the anomaly table (Q16) to the business invariant.

The default for most OLTP is **READ COMMITTED**: it prevents dirty reads, allows non-repeatable/phantom reads, and has the least overhead. It's correct for the common pattern of "read some data, compute, write specific rows by key with atomic updates" *as long as* you express writes safely—e.g., `UPDATE balance = balance + ?` (atomic, no lost update under RC) rather than read-then-write, and use optimistic-locking `@Version` (Q17) to catch the lost update where you do read-modify-write in the app. Most web requests live here.

I escalate to **REPEATABLE READ / snapshot** when a transaction reads the same data multiple times and must see a **consistent snapshot**—a financial report summing many rows that must reflect one instant, or a multi-statement read that would be wrong if another transaction's commit changed a value mid-way. PostgreSQL's REPEATABLE READ gives a true snapshot, which is exactly right for "give me a consistent view of the world as of when I started." I go to **SERIALIZABLE** only when there's a multi-row **invariant** that simpler levels can't protect—the write-skew class (Q54), like "at least one doctor on call" or "the sum of these rows must not exceed a limit"—where two transactions reading overlapping data and writing different rows would each be individually valid but jointly violate the rule. SERIALIZABLE catches it, at the cost of serialization-failure aborts that demand a retry loop (Q86).

```
READ COMMITTED   -> default OLTP; atomic UPDATEs + @Version handle lost updates
REPEATABLE READ  -> need a consistent multi-read snapshot (reports, consistency)
SERIALIZABLE     -> multi-row invariant / write-skew risk; pair with retry loop
```

The decision framework I state: start at the engine default, identify the precise anomaly each transaction is vulnerable to, and **raise isolation only for the specific transactions that need it** (you can set it per-transaction, not just per-connection), rather than globally. Often the cheaper alternative to raising isolation is to **materialize the conflict explicitly**—`SELECT ... FOR UPDATE` on the contended row(s) or a summary/lock row—so even READ COMMITTED detects the write-write conflict (Q54's mitigation), which gives the needed correctness with more predictable performance than SERIALIZABLE's opportunistic aborts. The anti-patterns: blanket SERIALIZABLE (needless aborts and a mandatory retry loop everywhere) and blind READ COMMITTED on a transaction that actually has a snapshot or invariant requirement (silent correctness bugs under concurrency).

#### Q100. [Practical] A datetime bug shows up only around midnight, month-end, or daylight-saving transitions. How do you diagnose and prevent timezone/DST bugs at the SQL layer?

Time bugs that cluster around **midnight, month boundaries, or the twice-yearly DST switch** are almost always a **timezone mismatch** between where a timestamp was recorded, how it's stored, and how it's queried. The root cause is usually storing event timestamps in a **zone-less type** (`TIMESTAMP WITHOUT TIME ZONE` / MySQL `DATETIME`) while different parts of the system assume different zones, so a value written as "local 23:30" is later interpreted as UTC (or vice versa), shifting events across a day/month boundary and producing "the report for June is missing the last few hours" or "this row counts in the wrong day."

The diagnosis: figure out, for each timestamp column, **what instant it actually represents** and **what zone each writer/reader assumes**. The fix at the schema level is to store **absolute instants** for events—`TIMESTAMP WITH TIME ZONE`/`timestamptz` (which, per Q50, stores UTC internally and converts on I/O)—and treat zone-less wall-clock types only for genuinely local concepts (a recurring 9 a.m. local alarm, a posted opening hour). Equally important: store and compute in **UTC** server-side and convert to the user's zone only at the presentation edge, so all arithmetic and comparisons happen in an unambiguous frame.

```sql
-- BUG: zone-less storage + a date-bucketing query that assumes server local time
SELECT date_trunc('day', created_at), count(*)   -- created_at is timestamp (no zone)
FROM events GROUP BY 1;                            -- "day" depends on session TZ -> drifts

-- ROBUST: store timestamptz (UTC), bucket explicitly in the user's zone
SELECT date_trunc('day', created_at AT TIME ZONE 'America/New_York') AS local_day,
       count(*)
FROM events GROUP BY 1;                            -- unambiguous, DST-correct
```

DST is the sharpest edge because it makes local time **non-monotonic and non-unique**: the spring-forward hour *doesn't exist* (02:30 local is invalid) and the fall-back hour *occurs twice* (01:30 happens twice), so date math done in local time can skip or double-count, and "add 24 hours" is not the same as "tomorrow at the same local time." The defenses: do all interval/bucket math on the UTC instant (where a day is always 24h), use the engine's IANA timezone database (`AT TIME ZONE 'Region/City'`, which knows DST rules) rather than fixed offsets like `-05:00` (which are *wrong* half the year), and never store local time without also knowing its zone. The prevention checklist I apply: `timestamptz` for events, UTC end-to-end with conversion only at display, explicit `AT TIME ZONE` in any local-day/local-month aggregation, named IANA zones (not fixed offsets), and tests that exercise midnight, month boundaries, and both DST transitions—because these bugs are invisible the other 363 days of the year.

#### Q101. [Practical] You add read replicas to scale reads. What can break, and how do you route queries correctly?

"Just add read replicas and send reads there" sounds free, but it silently changes your consistency model from a single source of truth to an **eventually consistent** one, and several things break if the application isn't written for it. The headline is **read-your-own-writes** (Q77): a user writes to the primary, an immediate read hits a lagging replica, and they see stale data—"I saved it but it's gone." Related: **monotonic-read violations** where successive reads bounce between replicas at different lag and the user sees data go *backwards*, and **causal anomalies** where a read sees an effect but not its cause because the two writes replicated at different speeds.

So routing can't be "all reads → replica." It must be **per-read consistency-aware**:

```
Route to PRIMARY (strong, fresh):
  - read-your-own-writes window (e.g., for N seconds after a user's write)
  - anything correctness-critical: balances, inventory checks, auth, before-write reads
Route to REPLICA (staleness-tolerant):
  - analytics, dashboards, search, recommendations, list views, exports
Guard rails:
  - NEVER read for a write decision (read-modify-write) from an async replica
  - cap and alert on replication lag; circuit-break replicas that exceed SLA
```

The mechanics: a sticky **"read from primary for N seconds after a write"** policy (session-pinned) covers the common read-your-own-writes case cheaply; for stronger guarantees some systems offer **bounded-staleness / causal reads** where the read waits until the replica has applied at least the LSN/GTID of the user's last write. Critically, **never make a write decision from a replica read**—a "check stock then decrement" that reads stock from a lagging replica can oversell; that read-before-write must hit the primary (and ideally be a single atomic `UPDATE ... WHERE qty >= ?` anyway). Reporting and analytics are the *ideal* replica workload precisely because they tolerate seconds of staleness and you *want* them off the primary.

The operational pieces that make it safe: **monitor and bound lag** (route away from or circuit-break a replica that falls behind the SLA, since a lagging replica silently serves very stale data), watch for replicas getting **query-cancelled** under streaming replication conflicts (PostgreSQL `max_standby_streaming_delay` vs long replica queries—a real "my replica query randomly fails" gotcha), and ensure failover promotes a sufficiently-caught-up replica to avoid data loss. The framing interviewers want: read replicas scale *read throughput*, not *consistency*—adding them introduces an eventual-consistency model, and the engineering work is routing each read to the right place based on its freshness requirement, not flipping a "use replicas" switch.

#### Q102. [Practical] How do you set up and tune a slow-query workflow to continuously catch regressions, and what do you do when a deploy makes queries slower?

Catching regressions requires treating query performance as a **continuously monitored, deploy-aware** signal rather than something you investigate after a complaint. The foundation is always-on aggregation: `pg_stat_statements` (PostgreSQL), the slow query log + `events_statements_summary_by_digest` (MySQL), or **Query Store** (SQL Server)—the last is purpose-built for this because it persists *per-query plans and runtime stats over time*, so you can literally ask "show me queries whose plan or duration changed after Tuesday's deploy." I rank by **total time (mean × calls)** to surface the queries actually consuming the server, set a `long_query_time`/slow-log threshold appropriate to the SLA, and wire the data into dashboards and alerts keyed to p95/p99 latency per query class.

The deploy-aware part is the key practice: tag/baseline the workload's plans and timings **before** each deploy so a regression is attributable. When a deploy makes queries slower, the diagnostic question is **what changed**, and the answer is usually one of: a **plan flip** (the optimizer picked a different, worse plan—often because a new index made an alternative look attractive, or stats shifted, or parameter sniffing cached a bad plan), a **new/removed index** altering access paths, a **query rewrite** in the new code, or a **data/volume change** that crossed a cost threshold. Query Store makes plan flips obvious by showing the old vs new plan for the same query.

```sql
-- SQL Server Query Store: find queries that regressed and force the good plan
-- (identify a query whose plan changed and got slower, then:)
EXEC sp_query_store_force_plan @query_id = 42, @plan_id = 17;   -- pin the known-good plan
```
```sql
-- PostgreSQL: spot the regression in the aggregate view post-deploy
SELECT query, calls, mean_exec_time, total_exec_time
FROM   pg_stat_statements ORDER BY total_exec_time DESC LIMIT 20;
-- then EXPLAIN ANALYZE the suspect and compare its plan to the pre-deploy baseline
```

The remediation, in order: for a **plan flip**, the fast mitigation is to force/pin the known-good plan (SQL Server `sp_query_store_force_plan`, or `force_custom_plan`/hints elsewhere) to stop the bleeding, *then* fix the real cause—refresh stats, adjust the offending index, or rewrite the query—and unpin once stable (a pinned plan is technical debt that can itself go stale). For a **new-index-induced** regression on an unrelated query, you may drop or reshape the index. For **parameter sniffing**, apply the Q59 tools (`RECOMPILE`/`force_custom_plan`). The disciplined workflow is: baseline before deploy → monitor the aggregate views and p99 after deploy → when a regression appears, attribute it via Query Store/plan comparison → mitigate fast (pin plan) → root-cause and fix → remove the temporary brace. The maturity signal is having a **rollback-fast, then fix-properly** posture and treating "the deploy regressed query X" as a detectable, attributable event rather than a mystery.

#### Q103. [Practical] When and how would you enforce data integrity in the application layer instead of with database constraints — and what are the real risks?

The default and strongly-preferred position is to enforce integrity **in the database**: `NOT NULL`, `UNIQUE`, `CHECK`, and foreign-key constraints are the last line of defense, enforced no matter which app, script, migration, or human touches the data, and they let the optimizer reason about the data (e.g., a unique constraint enables certain plan choices). The database is the **single chokepoint** every write must pass through; the application is not, so "we validate in the app" is only true for writes that go through *that* app's validated path—a one-off script, a second service, or a manual `UPDATE` bypasses it entirely. That asymmetry is why moving integrity to the app is, by default, a mistake.

There are nonetheless real, considered cases where teams relax DB-level constraints. The most common is **foreign keys at extreme write scale or in sharded/distributed systems**: FK checks take locks on parent rows (Q65) and add per-write cost, and across shards a referenced row may live on a different node where the engine *can't* enforce the constraint at all—so high-throughput and distributed-SQL systems sometimes drop FKs and enforce referential integrity in application logic (or accept eventual consistency). Other cases: **CHECK constraints too complex or cross-row** for the DB to express cleanly (multi-table invariants the engine can't declare), **bulk-load windows** where constraints are temporarily dropped for speed then re-validated (Q76), and **polyglot/microservice** boundaries where one service owns a table and others must go through its API rather than reach across with an FK.

```
Prefer DB constraints (default):           Consider app-layer enforcement when:
  - single source of truth                  - sharded: parent on another node (FK impossible)
  - enforced for ALL writers                - extreme write throughput (FK lock cost matters)
  - optimizer can use them                  - invariant too complex for CHECK/cross-table
  - cheap, declarative, hard to bypass       - service boundary owns the table
```

The risks of app-layer enforcement, which I name explicitly, are exactly why it's a deliberate trade-off, not a convenience: **any writer that bypasses the app corrupts the data** (orphaned rows, duplicate "unique" values, invalid states), and you *will* get such writers—migrations, admin scripts, data fixes, a second consumer; **race conditions** that a unique index would have arbitrated atomically (the check-then-insert TOCTOU race from Q57) reappear and must be handled with explicit locking or idempotency keys; and **bugs are silent**—instead of a clean constraint violation at write time, you discover the corruption later in a report or an incident, far from the cause. So the honest framing: enforce integrity in the database by default because it's the only universal chokepoint, and move it to the application only with a concrete forcing reason (sharding, scale, expressiveness, service ownership), eyes open to the fact that you're trading a strong, universal guarantee for a weaker, bypassable one—and compensating with disciplined access paths, idempotency, and monitoring for the violations the database is no longer catching for you.

## 🧩 Extended Questions — Set 3: Coding, Design & Expert Scenarios

### 🟢 Basic — extended

#### Q104. [Coding] Write a query that returns employees who earn more than their manager.

**Problem:** Given `employees(id, name, salary, manager_id)` (self-referencing), return every employee whose salary exceeds their direct manager's salary.

This is the canonical **self-join** problem, and the key realization is that "the manager" is just another row in the *same* table, so you join the table to itself with one alias for the employee and one for the manager:

```sql
SELECT e.name AS employee, e.salary AS emp_salary,
       m.name AS manager,  m.salary AS mgr_salary
FROM   employees e
JOIN   employees m ON e.manager_id = m.id      -- m is the same table, aliased
WHERE  e.salary > m.salary;
```

The join predicate `e.manager_id = m.id` wires each employee row to its manager row. Because it's an **INNER** join, employees with `manager_id IS NULL` (the CEO) are naturally excluded — they have no manager to compare against, which is the correct behavior here. If the business wanted the CEO included with a NULL comparison you'd switch to a `LEFT JOIN`, but then the `WHERE e.salary > m.salary` filter would drop the NULL-manager rows anyway (since `salary > NULL` is `UNKNOWN`), so the INNER join is both simpler and equivalent.

- **Time:** O(n) with an index on `employees(id)` (each employee does one index lookup of its manager); O(n²) without. **Space:** O(1) beyond the output.
- **Edge cases:** the CEO (`manager_id IS NULL`) is excluded; a self-managing row (`manager_id = id`) compares a salary to itself and is filtered out (`s > s` is false); ties (equal salaries) are excluded by the strict `>` — use `>=` if "at least as much" is intended.

#### Q105. [Coding] Write a query to compute each row's value as a percentage of the group total.

**Problem:** From `sales(region, product, amount)`, return each row with its amount as a percentage of its region's total.

The instinct of a beginner is to compute region totals in a separate `GROUP BY` query and join back, but a **window aggregate** does it in one pass without collapsing rows — the whole point of `SUM(...) OVER (PARTITION BY ...)` is that it computes the group total *alongside* each detail row:

```sql
SELECT region, product, amount,
       SUM(amount) OVER (PARTITION BY region) AS region_total,
       ROUND(100.0 * amount
             / SUM(amount) OVER (PARTITION BY region), 2) AS pct_of_region
FROM   sales;
```

The window `SUM(amount) OVER (PARTITION BY region)` with **no `ORDER BY`** sums the entire partition (not a running total), so every row in a region sees the same `region_total`. Contrast this with a plain `GROUP BY region` aggregate, which would return one row per region and lose the per-product detail — window functions exist precisely to attach group-level aggregates to individual rows.

Two correctness details matter. First, write `100.0 * amount` (or cast) so the division is floating/decimal, not integer — `100 * amount / total` with integer types truncates to 0 for every row where `amount < total`, a classic silent bug. Second, guard against a **zero region total**: if a region's amounts sum to 0, you divide by zero; wrap it as `amount / NULLIF(SUM(amount) OVER (...), 0)` so the result is `NULL` rather than an error.

- **Time:** O(n log n) (partitioned aggregation). **Space:** O(n).
- **Edge cases:** zero-sum partition (use `NULLIF`), NULL amounts (excluded from `SUM`, so percentages still sum to 100% over the non-NULL rows), single-row regions (100%).

#### Q106. [Coding] Write a query to find departments that have no employees.

**Problem:** Given `departments(id, name)` and `employees(id, dept_id)`, return departments with zero employees.

This is the "anti-join" — rows on the left with *no* match on the right — and there are three idiomatic ways to write it, each with a different NULL-safety and performance profile:

```sql
-- 1) NOT EXISTS (preferred: NULL-safe, usually an anti-join plan)
SELECT d.* FROM departments d
WHERE NOT EXISTS (SELECT 1 FROM employees e WHERE e.dept_id = d.id);

-- 2) LEFT JOIN ... IS NULL (the "find the unmatched left rows" idiom)
SELECT d.* FROM departments d
LEFT JOIN employees e ON e.dept_id = d.id
WHERE e.id IS NULL;                       -- no matching employee row

-- 3) NOT IN  (DANGEROUS if dept_id can be NULL)
SELECT d.* FROM departments d
WHERE d.id NOT IN (SELECT dept_id FROM employees);
```

The interviewer is usually probing whether you know why **`NOT IN` is a trap**: if any `employees.dept_id` is `NULL`, the subquery list contains a NULL, and `d.id NOT IN (1, 2, NULL)` evaluates to `UNKNOWN` (never `TRUE`) for *every* row — so the query returns **zero rows**, silently wrong. `NOT EXISTS` is NULL-safe because it tests row existence, not value membership, and most optimizers compile it (and the `LEFT JOIN ... IS NULL` form) into the same efficient **anti-join** operator.

- **Time:** O(n) with an index on `employees(dept_id)` for forms 1 and 2; **Space:** O(1) beyond output.
- **Edge cases:** NULL `dept_id` values (break form 3, harmless for 1/2); a department referenced by a soft-deleted employee (add `WHERE e.deleted_at IS NULL` to the correlation). Prefer `NOT EXISTS` as the default.

#### Q107. [Coding] Write a query that labels each order as 'High', 'Medium', or 'Low' value and counts each bucket.

**Problem:** From `orders(id, amount)`, bucket each order (`>= 1000` High, `>= 100` Medium, else Low) and then return a count per bucket.

This tests two things: the **`CASE` expression** for conditional logic, and the discipline to reuse a derived value without repeating the `CASE`. The naive approach repeats the `CASE` in both `SELECT` and `GROUP BY`; a cleaner approach computes it once in a subquery/CTE:

```sql
WITH labeled AS (
  SELECT id, amount,
         CASE WHEN amount >= 1000 THEN 'High'
              WHEN amount >= 100  THEN 'Medium'
              ELSE 'Low'
         END AS value_band
  FROM   orders
)
SELECT value_band, COUNT(*) AS order_count, SUM(amount) AS total_amount
FROM   labeled
GROUP  BY value_band
ORDER  BY MIN(amount);                  -- sort bands by their value, not alphabetically
```

The crucial property of `CASE` is that it is evaluated **top-to-bottom and short-circuits** on the first matching `WHEN`. So an order of 1500 matches `>= 1000` first and never reaches `>= 100` — which is why the conditions are ordered from most to least restrictive. Reverse them (`>= 100` first) and *everything* ≥ 100 would be labeled 'Medium', a common ordering bug. The `ELSE` catches everything that fell through, including any negative or zero amounts.

A subtle touch: `ORDER BY value_band` would sort alphabetically (High, Low, Medium — wrong), so I sort by `MIN(amount)` per band to get the natural Low → Medium → High order. Alternatively, label with a sortable prefix or `ORDER BY CASE value_band WHEN 'High' THEN 1 ...`.

- **Time:** O(n) single pass plus the group. **Space:** O(distinct bands).
- **Edge cases:** boundary values (1000 is High, 100 is Medium — inclusive bounds chosen deliberately), NULL `amount` (falls into `ELSE` 'Low' unless you add an explicit `WHEN amount IS NULL`), negative amounts.

### 🟡 Intermediate — extended

#### Q108. [Coding] Find all customers who logged in for 3 or more consecutive days. (LeetCode "Human Traffic"–style)

**Problem:** Given `logins(user_id, login_date)` (one row per user per day they logged in), return users who have at least one run of **3 consecutive calendar days**.

This is a gaps-and-islands variant (related to Q21) where the cleanest tool is the **`value - ROW_NUMBER()` constant-difference trick**: within a run of consecutive dates, subtracting an incrementing row number from the date yields a constant group key, because both increase in lockstep.

```sql
WITH numbered AS (
  SELECT user_id, login_date,
         ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY login_date) AS rn
  FROM   (SELECT DISTINCT user_id, login_date FROM logins) d   -- dedup same-day rows
),
runs AS (
  SELECT user_id,
         login_date - (rn * INTERVAL '1 day') AS grp,          -- constant within a run
         COUNT(*) OVER (PARTITION BY user_id,
                        login_date - (rn * INTERVAL '1 day')) AS run_len
  FROM   numbered
)
SELECT DISTINCT user_id
FROM   runs
WHERE  run_len >= 3;
```

The mechanism: for consecutive dates `Jun 1, Jun 2, Jun 3` with row numbers `1, 2, 3`, the differences are `Jun 1−1 = May 31`, `Jun 2−2 = May 31`, `Jun 3−3 = May 31` — identical, so they group together; a gap (skip Jun 4, log Jun 5 as `rn=4`) breaks the pattern (`Jun 5−4 = Jun 1`, a new group). Counting rows per group gives the run length, and we keep users with any run ≥ 3.

The **dedup step is essential**: if a user can have two rows for the same date, `ROW_NUMBER` would advance without the date advancing, corrupting the difference. `SELECT DISTINCT user_id, login_date` (or `DENSE_RANK` over dates) collapses duplicates first. Date arithmetic is dialect-specific: PostgreSQL `login_date - rn`, SQL Server `DATEADD(day, -rn, login_date)`, MySQL `DATE_SUB(login_date, INTERVAL rn DAY)`.

- **Time:** O(n log n) (the per-user ordered window). **Space:** O(n).
- **Edge cases:** duplicate same-day logins (dedup first), users with fewer than 3 total logins (no run can reach 3), the definition of "consecutive" across weekends/holidays (this treats every calendar day as eligible — adjust if business days only).

#### Q109. [Coding] Compute the median salary per department using only standard SQL.

**Problem:** Return the **median** salary for each department. Medians are awkward in SQL because there's no plain `MEDIAN()` aggregate in the standard, and the median straddles one or two middle rows depending on parity.

The cleanest modern answer is the **ordered-set aggregate** `PERCENTILE_CONT`, which is in the SQL standard and handles even/odd counts and interpolation automatically:

```sql
-- Standard / PostgreSQL / Oracle: ordered-set aggregate
SELECT dept_id,
       PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY salary) AS median_salary
FROM   employees
GROUP  BY dept_id;
```

`PERCENTILE_CONT(0.5)` returns the **continuous** (interpolated) median — for an even count it averages the two middle values, matching the textbook definition; `PERCENTILE_DISC(0.5)` returns the **discrete** median (an actual data value, the lower of the two middles). The `WITHIN GROUP (ORDER BY ...)` syntax is the distinguishing feature of ordered-set aggregates: the aggregate needs the rows *sorted* to find the positional value, and that clause supplies the sort.

For engines without ordered-set aggregates (MySQL, older SQL Server), the portable approach is a **window-function trick**: number rows per group ascending and descending, and the median row(s) are where the two ranks are within one of each other:

```sql
-- Portable (MySQL 8 / SQL Server): the middle row(s) where asc and desc ranks meet
WITH ranked AS (
  SELECT dept_id, salary,
         ROW_NUMBER() OVER (PARTITION BY dept_id ORDER BY salary)      AS rn_asc,
         COUNT(*)     OVER (PARTITION BY dept_id)                      AS cnt
  FROM   employees
)
SELECT dept_id, AVG(salary) AS median_salary
FROM   ranked
WHERE  rn_asc IN (FLOOR((cnt + 1) / 2.0), CEIL((cnt + 1) / 2.0))   -- 1 or 2 middle rows
GROUP  BY dept_id;
```

The `IN (FLOOR(...), CEIL(...))` selects exactly the single middle row (odd count) or the two middle rows (even count), and `AVG` then averages them — collapsing to the single value when odd. This is the durable pattern when `PERCENTILE_CONT` isn't available.

- **Time:** O(n log n) (sort per partition). **Space:** O(n).
- **Edge cases:** even vs odd group size (handled by interpolation/averaging), a one-row department (median = that value), NULL salaries (excluded by the aggregate; decide if a NULL should count). Prefer `PERCENTILE_CONT` where supported — it's clearer and correct by construction.

#### Q110. [Coding] Pivot is in the file already — now write the reverse: unpivot wide columns back into rows.

**Problem:** A wide table `sales_wide(product, jan, feb, mar)` has one column per month. Normalize it into long form `(product, month, amount)` — the inverse of the pivot in Q25.

The portable, engine-agnostic technique is a **`UNION ALL` of one projection per column**, each selecting the literal month label alongside that column's value:

```sql
SELECT product, 'jan' AS month, jan AS amount FROM sales_wide
UNION ALL
SELECT product, 'feb',          feb           FROM sales_wide
UNION ALL
SELECT product, 'mar',          mar           FROM sales_wide;
```

This works everywhere and is explicit, but it scans the table once per column (three passes here). For many columns, the cleaner approach in PostgreSQL is a single scan using a **`LATERAL` join over a `VALUES` list** (or `unnest` of arrays), which pairs each row with its set of (label, value) tuples in one pass:

```sql
-- PostgreSQL: single scan, expands each row into N (month, amount) rows
SELECT w.product, v.month, v.amount
FROM   sales_wide w
CROSS  JOIN LATERAL (VALUES
         ('jan', w.jan), ('feb', w.feb), ('mar', w.mar)
       ) AS v(month, amount);
```

Engine-specific unpivot operators exist too — SQL Server's `UNPIVOT`, Oracle's `UNPIVOT` — and are concise but non-portable. The `LATERAL`/`VALUES` form is my default in PostgreSQL because it reads naturally, scans once, and trivially extends to more columns by adding tuples.

Why unpivot at all? Wide, "spreadsheet-shaped" tables are an anti-pattern for analytics: adding a new month means an `ALTER TABLE` and rewriting every query, whereas long form (`product, month, amount`) is fully normalized, indexable on `(product, month)`, and lets you `GROUP BY month` or filter ranges without touching the schema. Unpivoting is the standard first step when ingesting external spreadsheet/CSV data into a queryable model.

- **Time:** O(n) for the LATERAL form (single scan); O(c·n) for the UNION ALL form (one scan per column `c`). **Space:** O(c·n) output.
- **Edge cases:** NULLs in a month column (become a NULL `amount` row — filter with `WHERE amount IS NOT NULL` if "no data" should be omitted), differing column types (the unioned columns must be type-compatible), and a dynamic/unknown set of month columns (requires dynamic SQL — watch for injection).

#### Q111. [Coding] Use FILTER (or CASE) to compute multiple conditional aggregates in one pass.

**Problem:** From `orders(id, status, amount, created_at)`, in a single query return, per day: total orders, count of completed orders, count of cancelled orders, and the sum of completed revenue.

The naive approach runs four separate aggregate queries or four correlated subqueries. The right approach computes all of them in **one scan** using conditional aggregation. The SQL-standard, most readable form is the **`FILTER` clause** (PostgreSQL, SQLite, and standard SQL):

```sql
SELECT date_trunc('day', created_at) AS day,
       COUNT(*)                                   AS total_orders,
       COUNT(*) FILTER (WHERE status = 'COMPLETED') AS completed,
       COUNT(*) FILTER (WHERE status = 'CANCELLED') AS cancelled,
       SUM(amount) FILTER (WHERE status = 'COMPLETED') AS completed_revenue
FROM   orders
GROUP  BY date_trunc('day', created_at)
ORDER  BY day;
```

`FILTER (WHERE ...)` restricts which rows feed *that specific aggregate*, independently per column, while all aggregates share the single `GROUP BY` scan. For engines without `FILTER` (MySQL, SQL Server), the equivalent is the **`CASE`-inside-aggregate** idiom, which exploits the fact that aggregates skip NULLs:

```sql
SELECT date_trunc('day', created_at) AS day,
       COUNT(*) AS total_orders,
       COUNT(CASE WHEN status = 'COMPLETED' THEN 1 END) AS completed,
       SUM(CASE WHEN status = 'COMPLETED' THEN amount END) AS completed_revenue
FROM   orders GROUP BY 1;
```

The trick in the `CASE` form is the **omitted `ELSE`**, which defaults to `NULL`: `COUNT` ignores NULLs (so only completed rows are counted) and `SUM` ignores NULLs (so only completed amounts are summed). A common bug is writing `ELSE 0` with `COUNT` — `COUNT(0)` counts *every* row because 0 is not NULL, inflating the count to the total. `FILTER` avoids that footgun entirely by being explicit about which rows participate.

This pattern is the workhorse of reporting: it replaces N table scans (or N joins of per-status subqueries) with one, which on a large fact table is the difference between a slow dashboard and a fast one.

- **Time:** O(n) single scan + group. **Space:** O(distinct days).
- **Edge cases:** the `COUNT(CASE ... ELSE 0)` count-inflation bug, NULL `status` (excluded from the filtered aggregates), and days with zero completed orders (the filtered `SUM` returns NULL, not 0 — wrap in `COALESCE(..., 0)` if a numeric zero is wanted).

#### Q112. [Coding] Write a recursive CTE that returns each node's full path and total subtree size in a hierarchy.

**Problem:** Given `categories(id, name, parent_id)` (a tree), for every node return its breadcrumb path (e.g., `Electronics > Phones > Android`) and its depth. This extends the basic recursive CTE (Q13) by building an accumulated string and depth.

```sql
WITH RECURSIVE tree AS (
  -- anchor: roots have no parent
  SELECT id, name, parent_id,
         name::text          AS path,
         1                   AS depth
  FROM   categories
  WHERE  parent_id IS NULL
  UNION ALL
  -- recursive step: extend the parent's path with this node's name
  SELECT c.id, c.name, c.parent_id,
         t.path || ' > ' || c.name,
         t.depth + 1
  FROM   categories c
  JOIN   tree t ON c.parent_id = t.id
)
SELECT id, path, depth
FROM   tree
ORDER  BY path;
```

The recursion carries **accumulator columns** down the tree: each recursive iteration appends `' > ' || c.name` to the parent's already-built `path` and increments `depth`. The anchor seeds the roots with just their own name at depth 1; the recursive member then joins children to their parent rows already in `tree`, so the path grows one level per iteration until no more children match. `ORDER BY path` yields a natural depth-first, hierarchy-sorted listing.

The expert concern is **cycle protection**. A malformed graph (A's parent is B, B's parent is A) would recurse forever. PostgreSQL 14+ offers a declarative `CYCLE` clause; otherwise you carry a visited-path array and stop if a node repeats:

```sql
-- PostgreSQL CYCLE clause: stop and flag if a node is revisited
WITH RECURSIVE tree AS ( ... )
  CYCLE id SET is_cycle USING cycle_path
SELECT * FROM tree WHERE NOT is_cycle;
-- SQL Server alternative: OPTION (MAXRECURSION 100) as a hard ceiling
```

This pattern generalizes to bill-of-materials (sum component costs up the tree), org charts (reporting chains), and threaded comments. To compute a **subtree aggregate** (e.g., total products under each category), you'd run the recursion the *other* direction (parent ← children) or aggregate the descendant set per node — the same machinery, different join direction.

- **Time:** O(n) over the tree (each node visited once) plus string-concatenation cost proportional to depth. **Space:** O(n) for the working set, O(sum of path lengths) for the materialized paths.
- **Edge cases:** cycles (use `CYCLE`/`MAXRECURSION`/visited-set), very deep trees (string paths grow; consider storing `ltree`/materialized-path columns for hot reads), orphan nodes whose `parent_id` references a missing row (won't appear under any root — a data-integrity check worth adding).

#### Q113. [Coding] Write a query to find pairs of users who are *mutual* friends from a directed follow table.

**Problem:** Given `follows(follower_id, followee_id)` (directed: A follows B), return distinct unordered pairs where the relationship is **mutual** (A follows B *and* B follows A), without listing each pair twice.

The mutuality test is a **self-join on the reversed edge**: row `(A, B)` is mutual if there also exists a row `(B, A)`. Self-joining `follows` to itself with the join keys crossed finds those:

```sql
SELECT f1.follower_id AS user_a, f1.followee_id AS user_b
FROM   follows f1
JOIN   follows f2
  ON   f1.follower_id = f2.followee_id        -- A == B's followee
 AND   f1.followee_id = f2.follower_id        -- B == A's followee
WHERE  f1.follower_id < f1.followee_id;        -- canonical order => each pair once
```

The join condition encodes "there is an edge back the other way." Without the `WHERE` filter, every mutual pair appears **twice** — once as `(A, B)` and once as `(B, A)` — because both directed rows independently satisfy the symmetric join. The `f1.follower_id < f1.followee_id` predicate keeps only the canonical orientation (smaller id first), deduplicating to one row per unordered pair. This `<` trick (rather than `<>`) is the standard way to enumerate unordered pairs exactly once and is worth recognizing instantly.

An alternative that some find clearer uses `EXISTS` for the reverse edge, which can be a better plan when `follows` is large and indexed on `(follower_id, followee_id)`:

```sql
SELECT follower_id AS user_a, followee_id AS user_b
FROM   follows f
WHERE  follower_id < followee_id
  AND  EXISTS (SELECT 1 FROM follows r
               WHERE r.follower_id = f.followee_id
                 AND r.followee_id = f.follower_id);
```

- **Time:** O(n) with a composite index on `(follower_id, followee_id)` (each row probes for its reverse); O(n²) without. **Space:** O(1) beyond output.
- **Edge cases:** self-follows (`A follows A` — excluded by the strict `<`), duplicate edges (dedup with `DISTINCT` or a unique constraint on the pair), and very high-degree nodes (a celebrity followed by millions) which can make the self-join expensive — the indexed `EXISTS` form scales better there.

#### Q114. [Coding] Use DISTINCT ON (or a window) to get the latest row per group efficiently.

**Problem:** From `events(user_id, event_type, created_at, payload)`, return the single **most recent** event per user — full row, not just the max timestamp.

This is the "greatest-N-per-group with N=1, full row" problem. A beginner writes `WHERE created_at = (SELECT MAX(created_at) ...)` correlated per user, which is correct but often slow and breaks on ties. PostgreSQL has a beautifully concise tool — **`DISTINCT ON`** — that keeps the first row per group according to the `ORDER BY`:

```sql
-- PostgreSQL: one row per user, the one with the latest created_at
SELECT DISTINCT ON (user_id) user_id, event_type, created_at, payload
FROM   events
ORDER  BY user_id, created_at DESC;       -- per user, newest first => keep that one
```

`DISTINCT ON (user_id)` collapses to one row per `user_id`, and which row survives is determined by the `ORDER BY`: the leading column(s) **must match** the `DISTINCT ON` key, and the trailing column (`created_at DESC`) picks the winner — here the newest. This is more efficient than a window+filter when an index on `(user_id, created_at DESC)` exists, because the engine can walk the index and emit the first row of each group.

The portable equivalent (every engine) is **`ROW_NUMBER()` filtered to 1**:

```sql
SELECT user_id, event_type, created_at, payload
FROM (
  SELECT *, ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY created_at DESC) AS rn
  FROM   events
) t
WHERE rn = 1;
```

Both return exactly one row per group, breaking ties arbitrarily by physical order; if ties must be broken deterministically, add a tiebreaker to the `ORDER BY` (e.g., `created_at DESC, id DESC`). Use `RANK() = 1` instead of `ROW_NUMBER()` if you want *all* rows tied for the latest timestamp.

- **Time:** O(n) if an index supports the `(user_id, created_at DESC)` ordering (no sort); O(n log n) otherwise. **Space:** O(n) for the windowed form, O(groups) for `DISTINCT ON` streaming over an index.
- **Edge cases:** ties on `created_at` (add a deterministic tiebreaker), users with one event (returned as-is), and the desire for top-3 instead of top-1 (switch to `ROW_NUMBER() <= 3` — `DISTINCT ON` only does N=1).

### 🟠 Advanced — extended

#### Q115. [Coding] Design and write a query to detect overlapping time ranges (double bookings).

**Problem:** Given `bookings(room_id, starts_at, ends_at)`, find all pairs of bookings for the **same room** whose time ranges **overlap** — the classic double-booking detector.

The crux is the **overlap predicate**. Two half-open intervals `[s1, e1)` and `[s2, e2)` overlap if and only if `s1 < e2 AND s2 < e1` — each starts before the other ends. This is the canonical, off-by-one-safe condition (using `<` with half-open intervals so back-to-back bookings that merely *touch* at a boundary don't count as overlapping):

```sql
SELECT a.room_id,
       a.id AS booking_a, b.id AS booking_b,
       a.starts_at, a.ends_at, b.starts_at, b.ends_at
FROM   bookings a
JOIN   bookings b
  ON   a.room_id = b.room_id
 AND   a.id < b.id                  -- each overlapping pair once, exclude self
 AND   a.starts_at < b.ends_at      -- a starts before b ends
 AND   b.starts_at < a.ends_at;     -- b starts before a ends  => they overlap
```

The `a.id < b.id` condition does double duty: it excludes a row matching itself, and it lists each overlapping pair only once (the `<` pair-enumeration trick from Q113). The two range conditions are the overlap test; getting them right is the whole question, and the symmetric `s1 < e2 AND s2 < e1` form is the one to memorize — it's far less error-prone than enumerating "a contains b OR b contains a OR a starts inside b OR ...".

The senior-level extension is **prevention, not just detection**. PostgreSQL can forbid overlaps declaratively with an **`EXCLUDE` constraint** backed by a GiST index over a range type, so the database itself rejects a double booking at insert time — a far stronger guarantee than an application check that races under concurrency (the TOCTOU problem from Q57):

```sql
-- PostgreSQL: the DB refuses to insert an overlapping booking for the same room
ALTER TABLE bookings ADD CONSTRAINT no_overlap
  EXCLUDE USING gist (
    room_id WITH =,                             -- same room
    tstzrange(starts_at, ends_at) WITH &&       -- ranges must not overlap (&&)
  );
```

The `&&` operator is range-overlap; `EXCLUDE` is like a generalized `UNIQUE` that uses any operator, and it's the right tool for "no two rows may overlap on this dimension." This pushes the invariant into the engine where concurrency can't break it.

- **Time:** detection self-join is O(n²) naively; an index on `(room_id, starts_at)` and range-aware indexing (GiST) bring it down substantially. **Space:** O(1) beyond output.
- **Edge cases:** touching-but-not-overlapping bookings (`ends_at == starts_at` — excluded by strict `<` with half-open intervals, which is usually correct), zero-length bookings (`starts_at == ends_at`), open-ended bookings (`ends_at IS NULL` meaning "indefinite" — needs `COALESCE(ends_at, 'infinity')`), and multi-row overlaps (the pairwise query reports each pair; collapsing into "this slot is contended" needs a further grouping).

#### Q116. [Coding] Compute a sessionization: group events into sessions where a gap > 30 minutes starts a new session.

**Problem:** From `events(user_id, ts)`, assign a session id per user such that consecutive events within 30 minutes belong to the same session, and a gap exceeding 30 minutes starts a new one.

This is the **time-based gaps-and-islands** pattern and the standard analytics technique behind "sessions" in product metrics. The approach is a two-step window computation: use `LAG` to find the gap from the previous event, mark where a new session begins, then take a **running sum of those markers** to produce a stable session number.

```sql
WITH gaps AS (
  SELECT user_id, ts,
         -- 1 when the gap from the previous event exceeds 30 min (or it's the first event)
         CASE WHEN ts - LAG(ts) OVER (PARTITION BY user_id ORDER BY ts)
                   > INTERVAL '30 minutes'
              OR LAG(ts) OVER (PARTITION BY user_id ORDER BY ts) IS NULL
              THEN 1 ELSE 0 END AS is_new_session
  FROM   events
)
SELECT user_id, ts,
       -- running sum of the new-session flags = monotonically increasing session id
       SUM(is_new_session) OVER (PARTITION BY user_id ORDER BY ts
                                 ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)
         AS session_id
FROM   gaps
ORDER  BY user_id, ts;
```

The two-window pattern is the heart of it. First, `LAG(ts)` gives each event the timestamp of the user's previous event; comparing the gap to the threshold produces a `0/1` flag where `1` marks the start of a new session (including the user's very first event, where `LAG` is NULL). Second, a **running `SUM`** of that flag increments at exactly each session boundary and stays constant within a session — so it *is* the session number. This "flag the boundaries, then cumulative-sum them into group ids" idiom is one of the most reusable advanced SQL techniques.

Note the **explicit `ROWS` frame** on the running sum: with an `ORDER BY` and no frame, the default is `RANGE`, which would lump together events with identical timestamps as peers — usually not what you want for a strict event sequence. `ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW` makes it a true row-by-row cumulative sum.

- **Time:** O(n log n) (per-user ordered windows). **Space:** O(n).
- **Edge cases:** identical timestamps (the `ROWS` frame handles them; decide if simultaneous events should be one or distinct), the first event per user (forced to start a session via the `LAG IS NULL` branch), and tuning the 30-minute threshold per product definition. To get a **global** session id across users rather than per-user numbering, concatenate `user_id` with the per-user session number.

#### Q117. [Practical] Design a normalized schema for a many-to-many tagging system, and explain the indexing.

**Problem:** Design tables so that articles can have many tags and tags can apply to many articles, supporting "all articles with tag X" and "all tags on article Y" efficiently, plus "articles having ALL of tags {X, Y, Z}".

A many-to-many relationship is modeled with a **junction (join) table** holding foreign keys to both sides; you never put a comma-separated `tags` column on `articles` (that violates 1NF and is unqueryable/unindexable). The three tables:

```sql
CREATE TABLE articles (
  id    BIGSERIAL PRIMARY KEY,
  title TEXT NOT NULL
);
CREATE TABLE tags (
  id   BIGSERIAL PRIMARY KEY,
  name TEXT NOT NULL UNIQUE              -- tag names are unique
);
CREATE TABLE article_tags (             -- the junction table
  article_id BIGINT NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
  tag_id     BIGINT NOT NULL REFERENCES tags(id)     ON DELETE CASCADE,
  PRIMARY KEY (article_id, tag_id)       -- composite PK: prevents duplicate links
);
-- the reverse-direction index for "all articles with tag X"
CREATE INDEX idx_article_tags_tag ON article_tags (tag_id, article_id);
```

The indexing is the substance of the answer. The **composite primary key `(article_id, tag_id)`** gives you (a) uniqueness — an article can't be linked to the same tag twice — and (b) a fast index for "tags on article Y" (leading column `article_id`). But that PK index *cannot* efficiently serve "articles with tag X" because `tag_id` isn't the leading column (the leading-column rule from Q44). So you add a **second index leading with `tag_id`**, covering the reverse traversal. Including both columns in each index (`(article_id, tag_id)` and `(tag_id, article_id)`) makes both queries **index-only** — they never touch the junction table heap. `ON DELETE CASCADE` keeps the junction clean when an article or tag is deleted.

The interesting query is "articles having **all** of tags {X, Y, Z}" — a relational-division problem. The standard solution is group-and-count:

```sql
SELECT article_id
FROM   article_tags
WHERE  tag_id IN (:x, :y, :z)
GROUP  BY article_id
HAVING COUNT(DISTINCT tag_id) = 3;       -- matched all three required tags
```

This is far better than chaining `INTERSECT`s or three self-joins: filter to the candidate tags, group per article, and keep articles whose distinct-matched-tag count equals the number required. For tag-heavy "match any" filtering at scale, an alternative denormalized design keeps a `tag_ids BIGINT[]` array (or `jsonb`) on `articles` with a **GIN index** (Q66), trading normalized integrity for fast containment queries — a deliberate denormalization for read-heavy faceted search.

The trade-offs to state: the normalized junction is the correct default (integrity, no duplication, both-direction indexing), while the array/GIN denormalization is a read-optimization for faceted-search hot paths that you adopt with evidence, keeping the array in sync via triggers or application logic.

#### Q118. [Practical] Design an append-only audit/event-log schema and explain why immutability and partitioning matter.

**Problem:** Design a schema to record an immutable audit trail of "who changed what, when" across many entity types, queryable by entity and by time, retained for years.

The defining principle is **append-only immutability**: audit rows are *inserted* and never updated or deleted (except by retention), so the log is a trustworthy record that can't be tampered with through the normal path. A flexible single-table design captures heterogeneous changes:

```sql
CREATE TABLE audit_log (
  id          BIGSERIAL,
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  actor_id    BIGINT,                       -- who (NULL for system actions)
  entity_type TEXT    NOT NULL,             -- 'order', 'user', ...
  entity_id   BIGINT  NOT NULL,             -- which row
  action      TEXT    NOT NULL,             -- 'INSERT' | 'UPDATE' | 'DELETE'
  changes     JSONB,                        -- {"field": {"old": .., "new": ..}}
  PRIMARY KEY (id, occurred_at)             -- includes partition key (see below)
) PARTITION BY RANGE (occurred_at);

CREATE INDEX idx_audit_entity ON audit_log (entity_type, entity_id, occurred_at);
```

Three design decisions carry the weight. First, the **`JSONB changes` column** handles the heterogeneity: every entity type has different fields, and a fixed-column schema can't capture "what changed" across all of them, so the diff goes into semi-structured JSON (the legitimate "schema-less tail" use of JSON from Q66) while the queryable dimensions — actor, entity, time, action — stay as typed, indexed columns. Second, **range partitioning by `occurred_at`** (Q60) is essential at audit scale because the table grows forever: monthly partitions give cheap retention (`DROP PARTITION` to expire old months instantly instead of a giant `DELETE`), partition pruning for time-bounded queries, and smaller per-partition indexes. Third, **immutability is enforced**, not just intended — revoke `UPDATE`/`DELETE` from the application role, or add a trigger that raises on any non-`INSERT`, so the audit integrity holds even against buggy code.

```sql
-- Enforce append-only at the DB: the app role may only INSERT and SELECT
REVOKE UPDATE, DELETE ON audit_log FROM app_role;
```

The querying patterns the indexes serve: "full history of order #123" hits `idx_audit_entity` (entity_type, entity_id, occurred_at) for a tight range scan; "everything that happened last Tuesday" prunes to one partition and scans by time. Writing audit rows is typically done via **triggers** on the source tables (consistent, can't be forgotten) or via the application/CDC (more flexible, decoupled) — triggers guarantee capture but add write overhead and run inside the writing transaction; CDC (decoding the WAL, Q61) captures changes asynchronously without touching the hot write path, which is the preferred approach at high throughput.

The trade-offs: a single wide audit table is simple and flexible but mixes all entity types (JSON queries are slower than typed columns); an alternative is per-entity audit tables (or system-versioned temporal tables, Q63) which are more typed but proliferate. For most systems the partitioned, JSON-payload, append-only single table is the pragmatic choice, with partitioning and enforced immutability as the non-negotiable parts.

#### Q119. [Practical] Design a schema and the query for a "no double-booking" seat-reservation system that is correct under concurrency.

**Problem:** Design the tables and the reservation transaction for booking seats (e.g., a flight or theater) such that two concurrent requests can never both grab the same seat.

The whole difficulty is **concurrency correctness**: a naive "check if seat is free, then insert a reservation" has the TOCTOU race (Q57) where two requests both see "free" and both book. The robust design pushes the uniqueness invariant into the database with a **unique constraint**, so the engine — not the application — arbitrates the race atomically:

```sql
CREATE TABLE seats (
  id          BIGINT PRIMARY KEY,
  event_id    BIGINT NOT NULL,
  seat_label  TEXT   NOT NULL,
  UNIQUE (event_id, seat_label)
);
CREATE TABLE reservations (
  id        BIGSERIAL PRIMARY KEY,
  seat_id   BIGINT NOT NULL REFERENCES seats(id),
  user_id   BIGINT NOT NULL,
  booked_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (seat_id)                         -- THE invariant: one reservation per seat
);
```

The `UNIQUE (seat_id)` on `reservations` is the entire correctness mechanism: no matter how many concurrent transactions try, the database guarantees **at most one** reservation row per seat, and the losers get a unique-violation error to handle. Booking is then a single atomic insert whose success/failure *is* the answer — no separate "check" step that could race:

```sql
-- Atomic booking: succeeds for exactly one of two concurrent callers
INSERT INTO reservations (seat_id, user_id) VALUES (:seat_id, :user_id)
ON CONFLICT (seat_id) DO NOTHING
RETURNING id;          -- returns a row if YOU got the seat; no rows if it was taken
```

If the `INSERT ... ON CONFLICT DO NOTHING` returns a row, you won the seat; if it returns nothing, someone else has it — the application reads the result, not a prior `SELECT`. This is bulletproof under any isolation level because the unique index physically can't hold two rows for the same seat. The alternative — **pessimistic locking** with `SELECT ... FOR UPDATE` on the seat row before inserting — also works and is appropriate when booking involves multi-seat atomicity or a hold/timeout workflow:

```sql
-- Pessimistic variant: lock the seat row for the transaction, then book
SELECT id FROM seats WHERE id = :seat_id FOR UPDATE;   -- serializes contenders
-- ... verify not already reserved, then INSERT the reservation ...
```

For real ticketing you usually need a **hold/expiry** workflow: a `status` (`HELD`/`CONFIRMED`) with a `held_until` timestamp so a seat is temporarily reserved during checkout and released if payment doesn't complete — implemented with the same unique constraint plus a background job (or `held_until < now()` predicate) reclaiming expired holds, ideally using `FOR UPDATE SKIP LOCKED` (Q120) so concurrent reclaimers don't fight.

The trade-offs to articulate: the **unique-constraint + insert-and-handle-conflict** approach is the simplest correct design and scales well (no explicit locks held across the request); **pessimistic `FOR UPDATE`** is needed when a booking spans multiple seats atomically or requires a read-then-decide step, at the cost of holding locks and reduced concurrency. The anti-pattern — and the thing the interviewer is checking you *don't* do — is enforcing "one reservation per seat" only in application code with a check-then-insert, which races under load and oversells seats.

#### Q120. [Coding] Implement a concurrent-safe job queue in SQL using FOR UPDATE SKIP LOCKED.

**Problem:** Multiple worker processes pull jobs from a `jobs` table. Each job must be processed by exactly one worker, workers shouldn't block each other competing for the same job, and a crashed worker's job should become reclaimable.

The naive "`SELECT` the next pending job, then `UPDATE` it to in-progress" has two failures under concurrency: two workers `SELECT` the same job (double processing), or they serialize behind a row lock and throughput collapses. The purpose-built solution is **`FOR UPDATE SKIP LOCKED`** (PostgreSQL 9.5+, MySQL 8+, Oracle), which locks the rows a worker claims and tells *other* workers to **skip already-locked rows** rather than wait for them:

```sql
-- Each worker atomically claims a batch of jobs no one else is touching
WITH claimed AS (
  SELECT id
  FROM   jobs
  WHERE  status = 'PENDING'
  ORDER  BY priority DESC, created_at        -- pick highest-priority, oldest first
  FOR UPDATE SKIP LOCKED                      -- lock these rows, skip ones others hold
  LIMIT  10
)
UPDATE jobs j
SET    status = 'RUNNING', claimed_at = now(), worker_id = :worker_id
FROM   claimed c
WHERE  j.id = c.id
RETURNING j.id, j.payload;                    -- the jobs THIS worker now owns
```

`SKIP LOCKED` is the magic: without it, worker B's identical query would block on the rows worker A locked; with it, B simply steps over A's locked rows and grabs the *next* unlocked ones. So N workers each pull a **disjoint** set of jobs with no contention and no double processing — the rows are claimed and flipped to `RUNNING` in one atomic statement (the `UPDATE ... RETURNING` over the locked CTE). This is the standard, scalable database-backed queue pattern, used by job systems that don't want a separate broker.

Two operational pieces complete it. **Crash recovery**: a worker that dies leaves jobs stuck in `RUNNING`; a reaper periodically resets jobs whose `claimed_at` is older than a timeout back to `PENDING` so another worker retries them — which requires the work to be **idempotent** (the job may run twice if the original worker was merely slow, not dead). **Completion**: on success the worker sets `status = 'DONE'` (or deletes the row); on failure it increments an attempt counter and either retries or moves to a dead-letter status after a max.

```sql
-- Reaper: reclaim jobs abandoned by crashed/stalled workers
UPDATE jobs SET status = 'PENDING', worker_id = NULL
WHERE  status = 'RUNNING' AND claimed_at < now() - INTERVAL '5 minutes';
```

- **Time:** O(log n) to find claimable rows with an index on `(status, priority, created_at)` — a **partial index `WHERE status = 'PENDING'`** (Q43) is ideal since only pending rows are ever scanned. **Space:** O(batch size).
- **Edge cases:** at-least-once execution (jobs must be idempotent because the reaper can re-queue a slow-but-alive worker's job), priority starvation (a flood of high-priority jobs starving low ones — add aging), and the queue table bloating with `DONE` rows (archive/partition or delete completed jobs, since high-churn queue tables bloat fast under MVCC, Q53). `SKIP LOCKED` is the feature to name — it's exactly what makes a SQL queue concurrency-safe without a lock convoy.

#### Q121. [Coding] Write a query using a recursive CTE to generate a continuous date series and left-join to fill gaps.

**Problem:** A `daily_sales(sale_date, amount)` table is missing rows for days with no sales. Produce a row for **every** day in a range, showing 0 for missing days — a "date spine" / calendar fill, essential for correct time-series charts and moving averages (Q32's caveat).

The problem is that aggregating `daily_sales` directly produces gaps — days with no sales simply don't appear, so a chart or a 7-day window silently treats "no data" as "the next data point," distorting trends. The fix is to generate a complete calendar and **left-join** the data onto it. PostgreSQL has `generate_series`, but the portable, interview-friendly tool is a **recursive CTE** that walks day by day:

```sql
WITH RECURSIVE calendar AS (
  SELECT DATE '2026-06-01' AS d            -- anchor: range start
  UNION ALL
  SELECT d + INTERVAL '1 day'              -- recursive: next day
  FROM   calendar
  WHERE  d < DATE '2026-06-30'             -- stop at range end
)
SELECT c.d AS sale_date,
       COALESCE(s.amount, 0) AS amount     -- 0 for days with no sales
FROM   calendar c
LEFT  JOIN daily_sales s ON s.sale_date = c.d
ORDER  BY c.d;
```

The recursive CTE seeds the start date and adds one day per iteration until the end date, materializing every calendar day whether or not sales exist. The **`LEFT JOIN` from the calendar to the data** is what fills gaps: every calendar day is preserved, and days with no matching `daily_sales` row get NULL, which `COALESCE(..., 0)` turns into a zero. Reverse the join direction (data left-joined to calendar) and you'd lose the gap-filling, so the calendar must be the left/driving table.

In PostgreSQL the idiomatic one-liner replaces the recursive CTE with a set-returning function, which the planner handles more efficiently:

```sql
SELECT g.d::date AS sale_date, COALESCE(s.amount, 0) AS amount
FROM   generate_series(DATE '2026-06-01', DATE '2026-06-30', INTERVAL '1 day') AS g(d)
LEFT  JOIN daily_sales s ON s.sale_date = g.d::date
ORDER  BY g.d;
```

For repeated use, the best practice is a **permanent `dim_date` calendar dimension table** (pre-populated with every date plus attributes like is_weekend, fiscal_quarter, holiday flags) — generating the spine on the fly is fine for ad-hoc queries, but a warehouse keeps a real date dimension so every report joins to the same authoritative calendar. This is why star schemas (Q23) always include a date dimension.

- **Time:** O(days) to generate the spine plus O(n) for the join with an index on `daily_sales(sale_date)`. **Space:** O(days in range).
- **Edge cases:** very large ranges (recursive CTEs can hit recursion limits — `generate_series` or a `dim_date` table scales better), timezone/DST when generating *timestamps* rather than dates (a "day" isn't always 24h, Q100 — generate dates, not timestamps, for calendar buckets), and ensuring the join key types match exactly (date vs timestamp casts).

#### Q122. [Coding] Write the "exchange seats" / swap-adjacent-rows query (LeetCode-style) without mutating data.

**Problem:** A `seat(id, student)` table has sequential ids `1..n`. Swap every pair of adjacent students (1↔2, 3↔4, ...). If `n` is odd, the last seat stays. Return the result as a query, not an UPDATE.

This tests whether you can do positional arithmetic in `SELECT` rather than reaching for a procedural loop. The trick is to compute the **target id** for each row with simple parity arithmetic: odd ids move +1, even ids move −1, and the last odd id (when `n` is odd) stays put.

```sql
SELECT
  CASE
    WHEN id % 2 = 1 AND id = (SELECT MAX(id) FROM seat) THEN id      -- last odd: stays
    WHEN id % 2 = 1 THEN id + 1                                      -- odd: take next seat's id
    ELSE id - 1                                                      -- even: take prev seat's id
  END AS id,
  student
FROM   seat
ORDER  BY id;                                                       -- order by the NEW id
```

The logic: an odd seat `1` should display at position `2`, so its new id is `id + 1`; an even seat `2` should display at position `1`, so its new id is `id - 1`. The only special case is when the table has an odd number of seats — the final odd id has no partner to swap with, so it keeps its own id (the first `WHEN`). Ordering by the *computed* new id arranges the output as the swapped seating. Crucially, we relabel ids rather than moving the `student` values, which is simpler and avoids a self-join.

An alternative, often considered the more elegant version, computes the swapped id with a single arithmetic expression using the count — `id + 1 - 2 * ((id + 1) % 2)` adjusted for the odd tail — but the `CASE` form is far more readable and the readability is worth more than cleverness in a code review. A window-function variant uses `LEAD`/`LAG` to pull the neighbor's student directly:

```sql
-- Window variant: pull the adjacent student via LEAD/LAG
SELECT id,
       CASE WHEN id % 2 = 1 THEN COALESCE(LEAD(student) OVER (ORDER BY id), student)
            ELSE LAG(student) OVER (ORDER BY id)
       END AS student
FROM   seat ORDER BY id;
```

Here odd rows take the *next* row's student (`LEAD`), even rows take the *previous* (`LAG`), and `COALESCE` keeps the last odd student in place when `LEAD` is NULL — arguably the cleanest because it never touches ids at all.

- **Time:** O(n) (single scan; the window variant is O(n) with the ordered window). **Space:** O(1) / O(n) for the window.
- **Edge cases:** odd `n` (last seat unmoved — both variants handle it), gaps in the id sequence (the arithmetic assumes contiguous ids; with gaps, use `ROW_NUMBER()` to derive positions first), and a single-row table (returned unchanged).

### 🔴 Expert — extended

#### Q123. [Coding] Compute a true running median (or arbitrary percentile) over a stream — and explain why it's hard in SQL.

**Problem:** For `metrics(dt, value)`, compute the running **median** of all values seen up to and including each day. Unlike a running `SUM` or `AVG`, this is genuinely hard, and explaining *why* is half the question.

The difficulty is that **`PERCENTILE_CONT` is an ordered-set aggregate, and ordered-set aggregates do not support a window frame** — you cannot write `PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY value) OVER (ORDER BY dt ROWS ...)`. Running `SUM`/`AVG` work as windows because they're *incrementally computable* (add one value, update the total), but a median requires knowing the *full sorted distribution* of the frame at each step, which the window machinery for ordered-set aggregates doesn't expose. So the naive windowed median is a syntax error in PostgreSQL.

The workaround is a **correlated lateral subquery** that recomputes the percentile over the growing prefix at each row — correct but O(n²), acceptable only for modest data:

```sql
-- Correct but O(n^2): recompute the median over all rows up to each dt
SELECT m.dt, m.value,
       (SELECT PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY m2.value)
        FROM   metrics m2
        WHERE  m2.dt <= m.dt) AS running_median
FROM   metrics m
ORDER  BY m.dt;
```

For large data the honest expert answer is that **SQL is the wrong layer for a true streaming median** — you'd either (a) accept an approximate quantile via a sketch algorithm (`t-digest`/`approx_percentile`, available as extensions in PostgreSQL, BigQuery's `APPROX_QUANTILES`, etc.), which maintains a small mergeable summary in roughly O(n) and is exactly how analytics engines do percentiles at scale, or (b) compute it application-side with a two-heap (min-heap/max-heap) structure that gives O(log n) per element. The point an interviewer wants is the *recognition* that an exact running percentile is not incrementally maintainable like a sum, so it's either O(n²) in pure SQL or you switch to an approximate, mergeable sketch.

```sql
-- Scalable in practice: approximate running percentile via a t-digest sketch (PG extension)
SELECT dt, value,
       approx_percentile(0.5, percentile_agg(value)
         OVER (ORDER BY dt ROWS UNBOUNDED PRECEDING)) AS approx_running_median
FROM   metrics;     -- mergeable sketch IS incrementally windowable, unlike PERCENTILE_CONT
```

- **Time:** O(n²) for the exact correlated form; ~O(n) for the sketch/approximate form; O(n log n) total for an app-side two-heap. **Space:** O(1) per step (sketch) vs O(n) (exact prefix).
- **Edge cases:** exact-vs-approximate accuracy requirements (financial reporting may forbid approximation), even-count interpolation (`PERCENTILE_CONT` vs `_DISC`), and the realization that *because* the median isn't a distributive/algebraic aggregate, no amount of windowing makes the exact version cheap — the trade-off (exact O(n²) vs approximate O(n)) is the real answer.

#### Q124. [Theory] Explain BRIN indexes and when they dramatically outperform B-trees — with the underlying assumption they rely on.

**BRIN (Block Range INdex)**, introduced in PostgreSQL 9.5, is a radically different index structure from a B-tree, and it shines in a specific but very common situation: **huge tables whose physical row order correlates with the indexed column** — overwhelmingly, append-only time-series data ordered by an ever-increasing timestamp or id. Instead of indexing every row (like a B-tree), BRIN stores, **per block range** (a group of consecutive physical pages, e.g., 128 pages by default), just the **min and max** of the indexed column in that range. The index is therefore *tiny* — kilobytes for a table where a B-tree would be many gigabytes.

The query mechanism is range exclusion: to answer `WHERE created_at BETWEEN x AND y`, BRIN checks each block range's stored min/max and **skips any range that can't contain matching rows**, scanning only the surviving ranges. When data is physically clustered by the column (new rows appended at the end, so each block range covers a tight, non-overlapping time window), this prunes almost the entire table and reads only the relevant blocks. The dramatic wins versus B-tree: **orders of magnitude smaller** (so it fits in cache and barely taxes writes), and **near-zero write overhead** because appending a row usually just widens an existing range's max rather than inserting into a tree (no page splits, the bloat/fragmentation problem from Q41/Q94 simply doesn't apply).

```sql
-- Tiny, write-cheap index for an append-only, time-ordered table
CREATE INDEX idx_events_brin ON events USING brin (created_at);
-- Query prunes to the relevant block ranges:
SELECT * FROM events WHERE created_at >= '2026-06-01' AND created_at < '2026-06-02';
```

The **critical assumption** — and the thing that makes or breaks BRIN — is **physical-logical correlation**: the rows must be stored on disk in roughly the same order as the indexed column. If correlation is high (append-only inserts, or a table kept ordered via `CLUSTER`/partitioning), BRIN is spectacular. If the column is **randomly distributed** across the table (e.g., a random UUID, or a column updated heavily so values scatter), every block range's min/max spans the whole domain, no ranges can be excluded, and BRIN degenerates to a **full scan** — useless. This is why BRIN is the go-to for the data-warehouse fact table and the IoT/log table but never for a high-cardinality random lookup key, where a B-tree's per-row precision is required. The senior framing: BRIN trades per-row precision for almost-free storage and writes, betting on physical ordering — a perfect fit for the largest, most write-heavy, time-ordered tables where a B-tree's size and write cost are the actual problem.

#### Q125. [Theory] What are deferrable constraints and constraint timing, and what problem do they uniquely solve?

By default, constraints are checked **immediately** — at the moment each row is inserted/updated. **Deferrable constraints** (SQL standard; PostgreSQL, Oracle) let you postpone the check to **transaction commit time** (`DEFERRABLE INITIALLY DEFERRED`, or per-transaction with `SET CONSTRAINTS ... DEFERRED`). The constraint is still enforced — the transaction will roll back at commit if it's violated — but *temporarily inconsistent intermediate states are allowed within the transaction*. This solves a class of problems that immediate checking makes impossible.

The canonical case is a **circular or mutual foreign-key dependency**. Suppose `employees.manager_id` references `employees.id`, and you must insert a two-person mutual-management cycle, or a chicken-and-egg pair of tables that reference each other. With immediate FK checking, the *first* insert fails because the row it references doesn't exist yet — there's no ordering that works. A `DEFERRABLE INITIALLY DEFERRED` foreign key lets both rows be inserted in any order; the references only need to be valid *at commit*, by which point both rows exist:

```sql
ALTER TABLE employees
  ADD CONSTRAINT fk_mgr FOREIGN KEY (manager_id) REFERENCES employees(id)
  DEFERRABLE INITIALLY DEFERRED;
-- now both inserts of a mutually-referencing pair succeed; checked only at COMMIT
```

The second classic case is **bulk renumbering under a unique constraint**: `UPDATE teams SET rank = rank + 1` processes rows one at a time, and partway through, two rows transiently share a `rank` value, violating an immediate `UNIQUE` constraint even though the *final* state is perfectly unique. A deferrable unique constraint allows the transient collision and validates only the committed end state. Without deferral you're forced into ugly workarounds (renumber via a temporary offset, or order the updates to avoid collisions).

The trade-offs and subtleties: deferred checking means the violation surfaces at `COMMIT` rather than at the offending statement, so error handling must cope with a commit-time failure (and you lose the precise statement that caused it). It also means the engine must **remember all the rows to re-check** until commit, costing memory for huge transactions. And not all engines support it — MySQL/InnoDB historically does **not** offer deferrable constraints (FK checks are immediate, though `SET foreign_key_checks = 0` is a blunt, session-wide, *unenforced* escape hatch used during bulk loads, which is dangerous because it doesn't validate at all). The expert point: deferrable constraints are the correct tool when a *valid* final state requires passing through a transiently-invalid intermediate state — circular references and bulk renumbering being the textbook cases — and reaching for session-wide constraint disabling instead is the unsafe shortcut that can leave permanently corrupt data.

#### Q126. [Theory] Explain advisory locks and when they are the right tool versus row locks.

**Advisory locks** (PostgreSQL `pg_advisory_lock`, MySQL `GET_LOCK`) are application-defined locks keyed by an **arbitrary integer (or string)** that have **no association with any table or row** — the database simply provides a mutual-exclusion primitive whose meaning is entirely up to the application. Unlike row locks, which the engine takes automatically to protect *data*, an advisory lock protects a *concept* you name: "only one worker may run the nightly rollup," "serialize all operations for tenant 42," "this distributed cron job runs on one node only." The database becomes a coordination point that every app instance already connects to, so you get cluster-wide mutual exclusion without a separate lock service (Redis, ZooKeeper).

```sql
-- Session-level advisory lock: ensure only one instance runs a job
SELECT pg_try_advisory_lock(hashtext('nightly-rollup'));  -- true => you got the lock
-- ... do the exclusive work ...
SELECT pg_advisory_unlock(hashtext('nightly-rollup'));
```

The decision versus row locks hinges on **what you're actually serializing**. Use a **row lock** (`SELECT ... FOR UPDATE`) when you're protecting a specific row's data during a read-modify-write — that's its purpose and it's automatically released at transaction end. Reach for an **advisory lock** when the thing you need to serialize **isn't a single row**: a process/job, a multi-row or cross-table operation that has no single row to lock, or a logical resource that may not even correspond to existing data (locking on a not-yet-inserted key to prevent a race before the row exists). A frequent real use is **avoiding lock contention on a hot parent**: instead of `SELECT ... FOR UPDATE` on a heavily-referenced row (which blocks unrelated readers/writers of that row), take an advisory lock on the *id* to serialize just the operation you care about, leaving normal data access unblocked.

The critical operational subtleties: PostgreSQL advisory locks come in **session-level** (held until explicitly unlocked or the session ends — must be paired with unlock, or a leaked lock blocks everyone) and **transaction-level** (`pg_advisory_xact_lock`, auto-released at transaction end — safer, no leak risk) variants; prefer the transaction-scoped form unless you specifically need the lock to outlive the transaction. They are **not** enforced by the database against anyone who ignores them — they only work if *all* code paths agree to take the lock (it's cooperative, like a mutex convention). And the keyspace is a flat integer space, so two unrelated features hashing to the same key would unintentionally serialize against each other — namespace your keys carefully. The senior framing: advisory locks are a general-purpose, app-defined mutex hosted by the database, ideal for serializing *processes and logical operations* (singleton jobs, per-tenant serialization, pre-insert races) where there's no row to lock — and the wrong tool when a plain row lock or a unique constraint already models the invariant.

#### Q127. [Theory] How do generated/computed columns work, and how do STORED vs VIRTUAL differ across engines?

A **generated column** (SQL standard `GENERATED ALWAYS AS (expr)`) is a column whose value is *computed from other columns in the same row* by an expression, rather than supplied on insert. You can't write to it directly; the engine derives it. This moves a derived value out of application code and into the schema, where it's guaranteed consistent for every writer and — critically — **can be indexed**. The two flavors differ in whether the computed value is physically materialized:

- **STORED** (`GENERATED ALWAYS AS (expr) STORED`): the value is computed at write time and **physically stored** on disk like a normal column. Reads are free (it's just a column), and you can build an ordinary B-tree index on it. The cost is storage and a small write-time computation. PostgreSQL (12+) supports *only* STORED generated columns.
- **VIRTUAL** (MySQL's default, SQL Server computed columns by default): the value is **not stored** but computed on the fly *when read*. It costs no storage and no write overhead, but every read re-evaluates the expression. MySQL can index a virtual column (it materializes the value in the index); SQL Server requires the computed column to be `PERSISTED` (its word for STORED) to index it in some cases.

```sql
-- PostgreSQL (STORED only): a searchable lowercased email, kept in sync automatically
ALTER TABLE users
  ADD COLUMN email_lower TEXT GENERATED ALWAYS AS (lower(email)) STORED;
CREATE INDEX idx_email_lower ON users (email_lower);   -- now case-insensitive lookups seek

-- MySQL: VIRTUAL (default, no storage) vs STORED
ALTER TABLE orders
  ADD COLUMN total DECIMAL(12,2) AS (qty * unit_price) VIRTUAL;   -- computed on read
```

The decision between them is a **storage-vs-read-cost trade-off**: choose STORED when the column is read or indexed frequently (pay once at write, free thereafter — the usual choice for an indexed derived column), and VIRTUAL when it's cheap to compute, rarely read, or you want to avoid the write cost and storage (and the engine can still index it if needed). A common, powerful pattern is **extracting a JSON field into a generated column** (Q66): `GENERATED ALWAYS AS ((payload->>'user_id')::bigint) STORED`, then indexing it — giving you a typed, indexable view of a JSON field while keeping the flexible JSON payload, the pragmatic hybrid between schema-on-read and schema-on-write.

The constraints worth knowing: the generation expression must be **deterministic and immutable** (no `now()`, no random, no subqueries referencing other rows — it can only see its own row's columns), because the engine relies on it being recomputable/consistent. You can't have a generated column depend on another generated column in some engines, and altering the expression may require a table rewrite. The senior framing: generated columns push derived-value correctness into the database (no writer can forget to compute it, no skew between app instances) and unlock indexing of computed/JSON-extracted values; STORED trades storage for free indexed reads while VIRTUAL trades read-time recomputation for zero storage, and the choice follows the read/write/index profile of that specific column.

#### Q128. [Coding] Write a gap-free sequence generator (e.g., invoice numbers) and explain why ordinary sequences can't do it.

**Problem:** Generate strictly **gapless, contiguous** invoice numbers per year (`2026-0001, 2026-0002, ...`) for legal/tax compliance, correct under concurrent inserts. Ordinary `SEQUENCE`/`AUTO_INCREMENT` can't satisfy this — explain why and implement a correct alternative.

As established in Q40, **sequences are deliberately non-transactional**: they advance and don't roll back, and they pre-allocate cached blocks, precisely so that concurrent inserts don't serialize on the counter. That design *guarantees* gaps — a rolled-back transaction, a crash discarding a cached block, or simply concurrent allocation leaves holes. For most surrogate keys that's fine (and desirable). But invoice numbers in many jurisdictions must be **gapless** for audit, so you cannot use a sequence; you must trade concurrency for contiguity with an explicit, **locked counter**:

```sql
-- A counter row per series; the row lock serializes allocation -> no gaps
CREATE TABLE invoice_counter (
  series TEXT PRIMARY KEY,        -- e.g. the year '2026'
  last_no INT NOT NULL
);

-- Allocation, inside the SAME transaction as the invoice insert:
WITH next AS (
  UPDATE invoice_counter
  SET    last_no = last_no + 1
  WHERE  series = '2026'
  RETURNING last_no                -- atomic increment, row lock held to commit
)
INSERT INTO invoices (series, number, customer_id, amount)
SELECT '2026', last_no, :customer_id, :amount FROM next;
```

The correctness mechanism is that the `UPDATE ... RETURNING` takes an **exclusive row lock** on the single counter row and holds it until the transaction commits. Any concurrent transaction trying to allocate the next number **blocks** on that lock until the first commits or rolls back — so if the first transaction rolls back, its increment is undone and the number is *reused* by the next, leaving no gap. This is exactly the serialization that sequences avoid, and it's exactly what gaplessness requires: numbers are handed out one at a time, in commit order, with no possibility of a discarded value. The allocation **must** be in the same transaction as the invoice insert; allocate-then-insert-in-a-separate-transaction reintroduces the gap if the insert fails.

The trade-off is the whole point: this **serializes all invoice creation for a series** on one row lock, capping throughput at "one commit at a time" — the antithesis of a sequence's concurrency. That's an acceptable and intended cost for invoices (you don't issue millions per second, and correctness/compliance dominates), but it would be a disastrous bottleneck for general surrogate keys. If even invoice throughput matters, you partition the series (per-region counters) or batch-allocate within a single transaction.

- **Time:** O(1) per allocation, but **serialized** — effective throughput is bounded by transaction commit latency on the hot counter row. **Space:** O(series count).
- **Edge cases:** the counter row must exist (upsert it for a new year/series with `ON CONFLICT`); never cache the number across transactions; on rollback the number is correctly reused (the feature, not a bug); and contention is by design — monitor lock waits and consider per-shard series only if a single locked row becomes the bottleneck. The interview point: gaplessness and high concurrency are fundamentally at odds, sequences chose concurrency, and a gapless requirement forces you to give that up with an explicit locked counter.

#### Q129. [Theory] What is relational division, and how do you implement "find rows matching ALL of a set" idiomatically?

**Relational division** is the relational-algebra operation that answers "find the X's that are related to **every** Y in a given set" — the dual of selection. Real questions of this shape are common: "customers who bought *all* products in a bundle," "students who passed *every* required course," "users with *all* of these permissions," "suppliers who can provide *all* the parts we need." It's called division because it's the inverse of a Cartesian product: if `orders × required_products` would pair every order with every required product, division finds the orders that actually have *all* those pairings. SQL has no `DIVIDE` keyword, so you express it one of three ways, and recognizing the pattern is the skill.

The most common and efficient idiom is **group-and-count**: filter to the required set, group by the candidate, and keep candidates whose distinct-match count equals the size of the required set (the same technique as Q117's "all tags"):

```sql
-- Customers who bought ALL of products {10, 20, 30}
SELECT customer_id
FROM   purchases
WHERE  product_id IN (10, 20, 30)
GROUP  BY customer_id
HAVING COUNT(DISTINCT product_id) = 3;     -- matched all three required
```

The `COUNT(DISTINCT ...)` is important — without `DISTINCT`, a customer who bought product 10 three times would count as 3 and falsely "match all," so distinctness guards against duplicate rows inflating the count. This form is usually the best plan: one filtered scan, one grouping.

The second idiom is the **double-`NOT EXISTS`** (the textbook "there is no required product that this customer did *not* buy"), which is the literal logical translation of "for all" via "there does not exist a counterexample":

```sql
SELECT c.customer_id
FROM   customers c
WHERE  NOT EXISTS (                              -- no required product...
  SELECT 1 FROM required_products r
  WHERE  NOT EXISTS (                            -- ...that this customer did NOT buy
    SELECT 1 FROM purchases p
    WHERE  p.customer_id = c.customer_id AND p.product_id = r.product_id));
```

This form generalizes when the required set is itself a *table* (not a hardcoded list) and handles edge cases precisely — including the **vacuous-truth** subtlety that division returns *every* candidate when the required set is empty ("everyone has bought all zero of these products"), which the group-and-count form gets wrong (it returns nobody). The double-NOT-EXISTS is the rigorous answer; group-and-count is the pragmatic fast path for a known, non-empty set.

The senior framing: relational division is "match ALL" (universal quantification), distinct from the far more common "match ANY" (existential, a simple `IN`/`EXISTS`/join). The interviewer is checking that you (a) recognize the shape, (b) reach for group-and-count-`HAVING` as the efficient default, and (c) know the double-`NOT EXISTS` form for the general/empty-set cases and the `COUNT(DISTINCT)` guard against duplicate-row inflation.

#### Q130. [Coding] Detect and break infinite recursion in a hierarchy, and report which rows form the cycle.

**Problem:** A `parts(part_id, contains_part_id)` bill-of-materials should be a DAG, but bad data introduced a cycle (part A contains B, B contains A), and a recursive CTE over it loops forever. Write a recursive query that **detects** cycles and reports the offending paths instead of hanging.

A plain recursive CTE has no memory of where it's been, so a cycle makes it re-traverse the same nodes endlessly until it errors or exhausts memory. The portable fix is to **carry the visited path as an array** and stop when a node would repeat, flagging that row as a cycle:

```sql
WITH RECURSIVE traverse AS (
  SELECT part_id, contains_part_id,
         ARRAY[part_id] AS path,           -- visited nodes so far
         false          AS is_cycle
  FROM   parts
  UNION ALL
  SELECT p.part_id, p.contains_part_id,
         t.path || p.part_id,              -- extend the path
         p.part_id = ANY(t.path)           -- TRUE if we're revisiting a node => cycle
  FROM   parts p
  JOIN   traverse t ON p.part_id = t.contains_part_id
  WHERE  NOT t.is_cycle                    -- stop expanding once a cycle is found
)
SELECT path, is_cycle
FROM   traverse
WHERE  is_cycle;                           -- report only the rows that closed a loop
```

The mechanism: each iteration appends the current node to a `path` array and checks `p.part_id = ANY(t.path)` — if the node is already in the path, we've come back to where we started, so `is_cycle` becomes true and the `WHERE NOT t.is_cycle` guard prevents expanding that branch any further (which is what stops the infinite loop). The `path` array then *shows* the cycle, so you can report exactly which parts form the loop — far more useful for fixing the data than just "recursion limit exceeded."

PostgreSQL 14+ provides this declaratively with the **`CYCLE` clause**, which manages the visited-set and the flag for you and is the idiomatic modern form:

```sql
WITH RECURSIVE traverse AS (
  SELECT part_id, contains_part_id FROM parts
  UNION ALL
  SELECT p.part_id, p.contains_part_id
  FROM   parts p JOIN traverse t ON p.part_id = t.contains_part_id
)
CYCLE part_id SET is_cycle USING cycle_path     -- engine tracks the path & flags cycles
SELECT * FROM traverse WHERE is_cycle;
```

Other engines impose a hard ceiling instead of detecting cycles semantically: SQL Server's `OPTION (MAXRECURSION 100)` aborts after N levels (a blunt safety net, not true detection), and Oracle's `CONNECT BY` has `NOCYCLE` plus `CONNECT_BY_ISCYCLE`. The expert point is that recursive traversal over *potentially* cyclic data **must** carry cycle protection — either the visited-path array (portable), the `CYCLE` clause (PostgreSQL 14+), or `NOCYCLE`/`MAXRECURSION` — and the array form has the bonus of reporting the actual offending path so the underlying data bug can be fixed.

- **Time:** O(V + E) over the reachable graph in the acyclic case; the cycle guard bounds work that would otherwise be infinite. **Space:** O(path length × paths) for the materialized arrays.
- **Edge cases:** self-loops (`part_id = contains_part_id`, caught immediately), multiple independent cycles (each reported via its own path), very deep legitimate hierarchies (the array grows — fine, but watch memory), and the difference between detecting a cycle vs merely capping depth (a hard `MAXRECURSION` may falsely abort a deep-but-acyclic tree).

#### Q131. [Theory] Explain MATCH_RECOGNIZE (row pattern matching) and the class of problems it solves elegantly.

`MATCH_RECOGNIZE` (SQL:2016; implemented in Oracle, Snowflake, Trino/Presto, Flink, and partially elsewhere) brings **regular-expression-style pattern matching over ordered rows** into SQL. Where window functions let you look at neighboring rows one offset at a time, `MATCH_RECOGNIZE` lets you declare a *pattern of consecutive rows* — like a regex over a row sequence — and extract matches. It's the elegant, declarative answer to a class of problems that are painful with raw window functions and `LAG`/`LEAD` gymnastics: detecting **V-shapes (a price dip-then-recovery), trends, double-bottoms, three-strikes patterns, sessionization, and state-machine transitions** in ordered event/time-series data.

The query partitions and orders rows (like a window), then defines named **row pattern variables** with `DEFINE` predicates and composes them into a **`PATTERN`** using regex operators (`*`, `+`, `?`, alternation), with `MEASURES` projecting values out of the match:

```sql
-- Find a "V": a strictly falling run, then a strictly rising run (price dip & recovery)
SELECT * FROM stock_ticks
MATCH_RECOGNIZE (
  PARTITION BY symbol
  ORDER BY     ts
  MEASURES  FIRST(DOWN.ts) AS start_ts,
            LAST(UP.ts)    AS end_ts,
            MATCH_NUMBER() AS mno
  ONE ROW PER MATCH
  PATTERN  (DOWN+ UP+)                       -- one-or-more falling, then one-or-more rising
  DEFINE   DOWN AS price < PREV(price),      -- "DOWN" rows are where price fell
           UP   AS price > PREV(price)       -- "UP" rows are where price rose
);
```

The power is in the declarative pattern: `PATTERN (DOWN+ UP+)` says "a maximal run of falling prices immediately followed by a run of rising prices," and `DEFINE` gives the meaning of each variable using `PREV`/`NEXT`/`FIRST`/`LAST` navigation. Expressing the same V-detection with window functions requires multiple `LAG`/`LEAD` layers, boundary-flag-and-cumulative-sum tricks (Q116), and self-joins — verbose and error-prone — whereas `MATCH_RECOGNIZE` reads almost like the specification. `ONE ROW PER MATCH` summarizes each match to a single row; `ALL ROWS PER MATCH` keeps the constituent rows with their assigned pattern variable, useful for labeling.

The honest caveats: it is **not in PostgreSQL or MySQL** (a major portability gap — it lives in Oracle, Snowflake, Trino, Flink, and other analytics-oriented engines), the syntax has a learning curve, and on huge datasets a complex pattern can be expensive (it's essentially running an NFA over the partition). The senior framing: when you find yourself stacking `LAG`/`LEAD` and boundary-flag-cumulative-sum tricks to recognize a *shape across a sequence of rows*, that's the signal `MATCH_RECOGNIZE` was designed for — and knowing it exists (and that it's an analytics-engine feature, not a vanilla OLTP one) demonstrates awareness of where SQL has evolved beyond window functions for complex event/pattern detection.

#### Q132. [Behavioral] Tell me about a time you had to push back on a data-model or schema decision that you believed was wrong. (STAR)

**Situation:** A team I joined as a senior/staff engineer was about to ship a new analytics-heavy feature whose core entity — customer "activity records" with dozens of varying attributes — was being modeled as a single `attributes JSONB` blob "for flexibility," with the plan to filter, aggregate, and join on fields *inside* that JSON. The lead favored it because it avoided schema migrations as requirements evolved. **Task:** I was convinced this would become a performance and correctness disaster within months (the JSON-as-a-substitute-for-schema anti-pattern from Q66), and my job was to change the decision *without* simply pulling rank or stalling a team that was eager to ship.

**Action:** Rather than argue in the abstract, I made it **empirical**. I loaded a production-scale sample (tens of millions of rows) into two schemas — the all-JSON design and a hybrid where the high-traffic filter/join/aggregate fields were promoted to typed, indexed columns with the genuinely variable tail left in `jsonb` — and ran the feature's actual query workload against both with `EXPLAIN ANALYZE`. The JSON-only design produced sequential scans and bad cardinality estimates (no column statistics on JSON fields, Q58), running the dashboard queries 30–50× slower, and it offered no way to enforce a `NOT NULL`/`CHECK`/foreign key on fields the business treated as mandatory. I presented this as a *trade-off table*, not a verdict — JSON's flexibility win versus the measured query cost, lost statistics, and lost integrity constraints — and explicitly endorsed keeping JSON for the sparse, truly-variable attributes, so the lead's flexibility concern was *addressed*, not dismissed. I also showed that generated columns (Q127) could extract-and-index JSON fields later if a tail field became hot, de-risking the "but requirements will change" objection.

**Result:** We shipped the hybrid model. The dashboards met their latency SLA, and over the next two quarters two "mandatory" fields that would have silently accepted nulls in the JSON design were caught by the `NOT NULL` constraints we'd added. **What I changed systemically:** the disagreement became a template — we adopted a lightweight "schema decision record" that requires any "put it in JSON" proposal to state which fields will be filtered/joined/aggregated (those go in columns) versus genuinely schema-less (those stay JSON), backed by an `EXPLAIN ANALYZE` on representative data. The lesson I carry: pushing back effectively as a senior engineer means **converting an opinion war into a measurement**, steel-manning the other side's real concern (flexibility), and offering a design that satisfies it rather than just blocking — disagreement lands far better as a benchmark and a trade-off table than as authority.

#### Q133. [Behavioral] Describe how you raised the SQL/data-access quality bar across a team or organization. (STAR)

**Situation:** As a staff engineer on a platform team, I inherited a service landscape where database incidents were recurring and self-inflicted: N+1 query storms from ORMs (Q73), full-table-rewrite migrations run during business hours (Q80/Q95), `SELECT *` everywhere defeating covering indexes (Q82), missing indexes on foreign keys causing lock escalation (Q65), and transactions holding connections across external HTTP calls (Q31). No single team was negligent; the knowledge was uneven and there were no guardrails, so the same mistakes recurred across teams. **Task:** I was asked to reduce database-related incidents durably — which meant changing *systems and defaults*, not just fixing the current fires, because teaching one team doesn't stop the next from repeating the mistake.

**Action:** I attacked it on three layers so good practice became the path of least resistance rather than a thing people had to remember. **Tooling/automation first** (the highest-leverage, because it doesn't rely on vigilance): I added CI checks that fail a build on a migration containing a blocking DDL pattern (a bare `ALTER` that rewrites, `CREATE INDEX` without `CONCURRENTLY`) and that flag obvious N+1 patterns and `SELECT *` in new code; I wired up `pg_stat_statements`/slow-query dashboards per service with alerts on regressions after deploys (Q102), so a query that got slower was *attributable to a deploy* instead of discovered weeks later. **Guardrails in the defaults**: app-side connection-pool sizing per the `cores×2` guidance (Q69) and a lint rule banning network I/O inside `@Transactional` (the exact rule born from the Q31 incident), plus `statement_timeout`/`idle_in_transaction_session_timeout` set in the standard service template so a single bad query self-heals (Q83). **Then teaching, anchored to the tooling**: a concise "database review checklist," a recurring brown-bag where we walked through *real* `EXPLAIN ANALYZE` outputs from our own incidents, and a rotation where I paired with each team on their slowest query so the learning was concrete and owned, not abstract.

**Result:** Over two quarters, database-related incidents dropped substantially, and — the metric I cared about more — the *recurrence* of the same class of incident largely stopped because the CI checks caught them pre-merge. New engineers inherited the guardrails by default, so onboarding no longer depended on someone happening to know the lore. **The systemic lesson:** raising a quality bar across an org is mostly about **moving correctness into defaults and automation** (CI checks, templates, alerts, timeouts) so the right thing happens without anyone remembering to do it, and reserving human teaching for the judgment that can't be automated — reviewing plans, choosing isolation levels, weighing denormalization. Education alone regresses the moment attention moves on; guardrails persist. I treat "could a CI check or a default have prevented this?" as the first question after any database incident.

#### Q134. [Theory] What is the difference between logical and physical query optimization, and where does each happen?

Query optimization happens in two conceptually distinct phases, and naming the boundary precisely demonstrates real depth. **Logical optimization** (a.k.a. rewrite/algebraic optimization) transforms the query into an *equivalent* query that's expected to be cheaper, operating purely on **relational algebra equivalences without yet considering physical access methods, indexes, or cost**. **Physical optimization** (a.k.a. plan/cost-based optimization) then chooses *how* to execute that logical form — which indexes, which join algorithms, which join order — using the cost model and statistics (Q44/Q58). The first phase reshapes *what* to compute; the second decides *how* to compute it.

Logical optimizations are **rule-based, cost-independent rewrites** that are (almost) always wins: **predicate pushdown** (move `WHERE` filters as close to the scans as possible so less data flows upward), **projection pushdown** (read only needed columns early), **subquery flattening / de-correlation** (turn a correlated subquery into a join or semi-join, Q8/Q46), **view expansion** (inline a view's definition), **constant folding** (`WHERE 1 = 1`), **predicate simplification**, **join elimination** (drop a join to a table whose columns aren't used and whose FK guarantees a match), and **outer-to-inner join conversion** (a `LEFT JOIN` with a `WHERE` filter on the right table becomes an inner join, Q2). These don't need statistics — they're sound transformations the optimizer applies during the rewrite stage of the query lifecycle (Q62).

```
Logical (rewrite, equivalence-based, no cost):
  push WHERE filters down to scans | prune unused columns | flatten subqueries
  expand views | fold constants    | eliminate redundant joins | LEFT->INNER

Physical (cost-based, statistics-driven):
  index scan vs seq scan | nested-loop vs hash vs merge join | join ORDER
  hash vs sort aggregation | parallel degree | pick the cheapest estimated plan
```

The critical distinction is **certainty**: logical rewrites are equivalence-preserving and essentially always beneficial (pushing a filter down can't make things worse), so they're applied unconditionally; physical choices are **estimates** that depend on cardinality statistics and can be *wrong* (the nested-loop-on-a-bad-estimate explosion, Q44). This is why the two failure modes differ — a logical-rewrite limitation means the optimizer *didn't restructure* something (e.g., couldn't de-correlate a subquery, so you rewrite it by hand), whereas a physical-optimization failure means it picked a bad *plan* from correct structure (stale stats, correlated predicates) and the fix is statistics/indexes/hints. The senior framing: when you "optimize a query by rewriting it" (sargable predicates, `EXISTS` instead of `JOIN`-`DISTINCT`, pushing filters in), you're helping the **logical** phase; when you add indexes, update statistics, or hint a join method, you're influencing the **physical** phase — and diagnosing which phase is failing tells you whether the fix is a rewrite or a stats/index change.

#### Q135. [Coding] Write a query to find the longest streak of a condition (e.g., a stock's longest consecutive up-days) and report its boundaries.

**Problem:** From `prices(symbol, dt, close)`, for each symbol find the **longest run of consecutive up-days** (each day's close greater than the previous day's), returning the streak length and its start/end dates. This combines `LAG` for the up/down signal, the boundary-flag-cumulative-sum islands trick (Q116), and a top-1-per-group selection.

The build-up has three stages: (1) mark each day up or down relative to the prior day, (2) group consecutive up-days into islands using the cumulative-sum-of-boundaries technique, (3) measure each island and pick the longest per symbol.

```sql
WITH marked AS (                                    -- 1) is each day an "up" day?
  SELECT symbol, dt, close,
         CASE WHEN close > LAG(close) OVER (PARTITION BY symbol ORDER BY dt)
              THEN 1 ELSE 0 END AS is_up
  FROM   prices
),
grouped AS (                                        -- 2) assign an island id to up-runs
  SELECT symbol, dt, is_up,
         -- a new up-run starts where is_up flips 0->1; cumulative sum of those starts = island id
         SUM(CASE WHEN is_up = 1
                   AND LAG(is_up) OVER (PARTITION BY symbol ORDER BY dt) = 0
                  THEN 1 ELSE 0 END)
             OVER (PARTITION BY symbol ORDER BY dt
                   ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS run_id
  FROM   marked
),
runs AS (                                           -- 3) measure each up-run
  SELECT symbol, run_id,
         MIN(dt) AS streak_start, MAX(dt) AS streak_end, COUNT(*) AS streak_len
  FROM   grouped
  WHERE  is_up = 1                                  -- only up-days form up-streaks
  GROUP  BY symbol, run_id
)
SELECT symbol, streak_start, streak_end, streak_len
FROM (
  SELECT *, ROW_NUMBER() OVER (PARTITION BY symbol ORDER BY streak_len DESC, streak_start) AS rn
  FROM   runs
) t
WHERE rn = 1;                                       -- the longest streak per symbol
```

The chain of techniques is the lesson. `LAG(close)` turns each day into an up/down flag (`is_up`). Then, rather than the `value - ROW_NUMBER()` trick (which suits *contiguous values*), this uses the more general **"flag the run boundaries, cumulative-sum them into a group id"** idiom from Q116: a run starts where `is_up` transitions `0 → 1`, and the running sum of those start-flags assigns a stable `run_id` to each up-run. Grouping by `(symbol, run_id)` and filtering `is_up = 1` measures each up-streak's length and span, and a final `ROW_NUMBER()` filtered to `rn = 1` per symbol selects the longest, with `streak_start` as a deterministic tiebreaker when two streaks tie on length.

This is a genuinely composite problem — it's why it's an expert coding question — and recognizing that the **islands trick generalizes from "consecutive values" to "consecutive rows satisfying a predicate"** is the senior-level insight. The same shape solves longest login streak, longest uptime window in telemetry, longest run of passing tests, etc.

- **Time:** O(n log n) per symbol (the ordered windows dominate). **Space:** O(n).
- **Edge cases:** the first day per symbol (`LAG` is NULL → `is_up = 0`, correctly not starting an up-run), ties in streak length (broken deterministically by `streak_start`), flat days where `close` equals the prior close (treated as not-up by the strict `>` — decide if equal counts), gaps in trading days (consecutive *rows*, not calendar days — if calendar continuity matters, join to a date spine first, Q121), and symbols with no up-days at all (produce no run rows — `LEFT JOIN` back to the symbol list if every symbol must appear).

## ✅ Key Takeaways

- Master the **logical order of evaluation**—most query bugs (alias scope, WHERE vs HAVING, accidental inner joins) trace back to it.
- Prefer `EXISTS`/`NOT EXISTS` over `IN`/`NOT IN` when NULLs are possible; prefer `UNION ALL` over `UNION` unless you truly need dedup.
- **Window functions** (ROW_NUMBER/RANK/DENSE_RANK/LAG/LEAD + explicit `ROWS` frames) solve top-N-per-group, running totals, dedup, and gaps-and-islands cleanly—learn them deeply.
- Know your engine's **actual** isolation behavior, not just the SQL standard: PostgreSQL REPEATABLE READ ≈ snapshot, MySQL InnoDB defaults to REPEATABLE READ with next-key locks, most others default to READ COMMITTED.
- **MVCC** is why readers don't block writers in PG/Oracle/MySQL; understand VACUUM/undo and write-conflict aborts under snapshot/serializable isolation.
- Normalize to 3NF/BCNF by default in OLTP; **denormalize with measured evidence** and a sync strategy. Star schemas are intentionally denormalized.
- Most "slow query" problems are **cardinality-estimation** problems—fix statistics and use composite/covering indexes ordered equality→range/sort before reaching for hints.
- Keep transactions **short**; never do network I/O inside a lock scope. Use optimistic locking by default, pessimistic only on hot rows.
- Always use **parameterized queries** and least-privilege accounts; push tenant isolation into the DB (RLS) for defense in depth.

## ⚠️ Common Pitfalls

- Filtering a `LEFT JOIN`'s right table in `WHERE` instead of `ON`, silently turning it into an INNER JOIN.
- Using `column = NULL` instead of `IS NULL`; forgetting that `AVG`/`SUM` skip NULLs while `COUNT(*)` does not.
- Reaching for `UNION` (with its hidden sort/dedup) when `UNION ALL` suffices.
- Relying on the default window frame (`RANGE`) for running totals and getting peer-row surprises on tied ORDER BY values—use `ROWS`.
- Putting row-level predicates in `HAVING` (forces full aggregation) instead of `WHERE`.
- `NOT IN (subquery)` returning zero rows because the subquery contains a NULL.
- Assuming a CTE is a performance optimization—often it's just readability, and pre-PG12 it was an optimization fence.
- Wrapping an indexed column in a function (`WHERE UPPER(name) = ...`) and defeating the index; use a functional/expression index or a sargable range instead.
- Running a full-table-rewrite DDL (`ALTER` that rewrites, blocking `CREATE INDEX`) on a hot large table during business hours.
- Holding locks across external calls or user think-time, causing cascading lock waits and pool exhaustion.
- Trusting the optimizer's plan blindly when statistics are stale or predicates are correlated.

## 📚 Further Reading

- *SQL Performance Explained* — Markus Winand (and the companion site **use-the-index-luka.com**); the best concise treatment of indexing and sargability.
- *Designing Data-Intensive Applications* — Martin Kleppmann; chapters on transactions, isolation, MVCC, and distributed consistency are essential at the advanced/expert tiers.
- *Database Internals* — Alex Petrov; B-trees, LSM-trees, storage engines, and distributed transaction protocols.
- *Fundamentals of Database Systems* — Elmasri & Navathe; the canonical text for normalization (1NF–BCNF) and functional dependencies.
- [PostgreSQL Documentation](https://www.postgresql.org/docs/current/) — authoritative on MVCC, isolation levels, `EXPLAIN`, window functions, and concurrent DDL.
- [MySQL 8 Reference Manual — InnoDB Locking and Transaction Model](https://dev.mysql.com/doc/refman/8.0/en/innodb-locking-transaction-model.html) — definitive on InnoDB isolation and next-key locking.
