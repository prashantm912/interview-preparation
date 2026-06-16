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
