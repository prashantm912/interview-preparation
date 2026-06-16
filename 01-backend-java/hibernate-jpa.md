# Hibernate & JPA

A deep, interview-focused guide to the Java Persistence API (JPA) specification and its dominant implementation, Hibernate ORM. Covers mappings, fetching strategies, the persistence context lifecycle, caching, locking, querying, and the performance traps that separate juniors from staff engineers.

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

### Q1. [Theory] What is the difference between JPA and Hibernate?

JPA (Jakarta Persistence API, formerly Java Persistence API) is a **specification** — a set of interfaces and annotations (`EntityManager`, `@Entity`, `@OneToMany`, JPQL) that define how object-relational mapping should behave in Java. It ships no runtime logic itself. Hibernate is the most popular **implementation** (provider) of that spec; others include EclipseLink and OpenJPA.

The practical takeaway: if you code strictly against `jakarta.persistence.*` interfaces, you can theoretically swap providers. In reality almost everyone uses Hibernate-specific extensions (`@BatchSize`, `@Formula`, `@Filter`, the `Session` API, `@SQLRestriction`), which couples you to Hibernate. Hibernate predates JPA (2001 vs 2006) and heavily influenced the spec. The naming changed from `javax.persistence` to `jakarta.persistence` with Jakarta EE 9 — this is the single biggest breaking change between **Spring Boot 2 (Hibernate 5, `javax`)** and **Spring Boot 3 (Hibernate 6, `jakarta`)**.

```
┌─────────────────────────────────────────────┐
│  Your code: @Entity, EntityManager, JPQL     │  ← JPA spec (interfaces)
├─────────────────────────────────────────────┤
│  Hibernate ORM  (or EclipseLink, OpenJPA)    │  ← Provider (implementation)
├─────────────────────────────────────────────┤
│  JDBC  →  Connection Pool (HikariCP)         │
├─────────────────────────────────────────────┤
│  Database (PostgreSQL / MySQL / Oracle)      │
└─────────────────────────────────────────────┘
```

### Q2. [Theory] What is an entity, and what are the requirements for a class to be a valid JPA entity?

An entity is a lightweight, persistent domain object that maps to a database table. Each instance corresponds (typically) to a row. Requirements:

- Annotated with `@Entity` (or declared in `orm.xml`).
- Has a **no-argument constructor** (at least package-private/protected) — the provider uses reflection/bytecode to instantiate it.
- Must **not** be `final`, and persistent fields/methods must not be `final`, because Hibernate creates runtime proxies (CGLIB/ByteBuddy subclasses) for lazy loading.
- Has a primary key (`@Id`).
- Should be a top-level class (not an inner class), and should implement `Serializable` if it is to be detached/passed across tiers.

Good practice: override `equals`/`hashCode` carefully (often using a business/natural key, or a `UUID` assigned in the constructor — never the auto-generated DB id alone, which is null before persist and breaks `Set` semantics).

### Q3. [Theory] Explain the four states of a JPA entity lifecycle.

```
            persist()/save()                         commit/flush
  ┌─────────┐ ─────────────► ┌──────────┐ ─────────────────────► ┌──────────┐
  │ TRANSIENT│                │ MANAGED  │                         │ DATABASE │
  │  (new)   │ ◄───────────── │(persistent)│ ◄──── find()/query ──┘          │
  └─────────┘                 └──────────┘
                                  │  ▲
                          detach()│  │ merge()
                          /close()▼  │
                              ┌──────────┐        remove()         ┌──────────┐
                              │ DETACHED │        ──────────►      │ REMOVED  │
                              └──────────┘                         └──────────┘
```

- **Transient (new):** a freshly `new`-ed object the persistence context knows nothing about. No DB row, no id (unless app-assigned).
- **Managed (persistent):** attached to a `PersistenceContext`/`Session`. Changes are tracked via **dirty checking** and synchronized to the DB at flush time.
- **Detached:** was managed, but the persistence context closed or the object was evicted. Changes are no longer tracked; you must `merge()` to reattach.
- **Removed:** scheduled for deletion; a `DELETE` runs at flush.

### Q4. [Theory] What is the difference between `EAGER` and `LAZY` fetching?

`FetchType.LAZY` means the associated data is loaded **only when first accessed** — Hibernate inserts a proxy (for `@ManyToOne`/`@OneToOne`) or a lazy collection wrapper (for `@OneToMany`/`@ManyToMany`). `FetchType.EAGER` loads the association immediately as part of loading the owning entity (usually via a join or a secondary select).

Defaults matter: `@OneToMany` and `@ManyToMany` are **LAZY** by default; `@ManyToOne` and `@OneToOne` are **EAGER** by default. The pervasive best practice is to make **everything LAZY** and fetch what you need explicitly per query (join fetch / entity graph). Eager associations cause uncontrollable N+1 fetches and pull large object graphs into memory for every query, even when you only need one field.

### Q5. [Practical] You hit a `LazyInitializationException`. What happened and how do you fix it?

It means you accessed a lazy association (collection or proxy) **after the persistence context / Hibernate `Session` was already closed** — so there is no open connection to load the data.

```
Controller (no session) ──► entity.getOrders()  ──►  💥 LazyInitializationException
                                  ▲
            Session already closed when @Transactional service method returned
```

The **wrong** fixes (anti-patterns):

- `hibernate.enable_lazy_load_no_trans=true` — silently opens throwaway sessions, causes N+1 and inconsistency.
- Open Session In View (OSIV) — keeps the session open for the whole HTTP request. Spring Boot enables it by default (`spring.jpa.open-in-view=true`); it hides the real problem, holds DB connections during view rendering, and causes surprise queries in the controller. **Disable it.**

The **right** fixes — fetch the data while the session is open:

- `JOIN FETCH` in a JPQL query for that specific use case.
- A JPA **entity graph** (`@EntityGraph` / `EntityManager.createEntityGraph`).
- Map to a **DTO projection** so you never touch a lazy association in the web layer.
- Initialize within the transaction (`Hibernate.initialize(entity.getOrders())`).

### Q6. [Coding] Map a bidirectional `@OneToMany` between `Author` and `Book` correctly.

**Problem:** an author has many books; each book has one author. Map it so there is no redundant join table and the foreign key lives on `book`.

```java
@Entity
public class Author {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // "mappedBy" => Author is the INVERSE side; Book.author owns the FK.
    @OneToMany(mappedBy = "author",
               cascade = CascadeType.ALL,
               orphanRemoval = true,
               fetch = FetchType.LAZY)
    private List<Book> books = new ArrayList<>();

    // Helper methods keep BOTH sides of the relationship in sync.
    public void addBook(Book book) {
        books.add(book);
        book.setAuthor(this);
    }
    public void removeBook(Book book) {
        books.remove(book);
        book.setAuthor(null);
    }
    // getters/setters omitted
}

@Entity
public class Book {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String title;

    @ManyToOne(fetch = FetchType.LAZY)   // override EAGER default
    @JoinColumn(name = "author_id")      // the OWNING side: holds the FK column
    private Author author;
    // getters/setters omitted
}
```

**Key points / edge cases:**
- The **owning side** is the one *without* `mappedBy` (`Book`). Hibernate only writes the FK from the owning side, so always set `book.setAuthor(...)`. Forgetting this leaves the FK null even though the in-memory list contains the book.
- `orphanRemoval = true` deletes a book when removed from the collection; `CascadeType.ALL` cascades persist/merge/remove.
- Prefer `Set` over `List` for `@ManyToMany` and for `@OneToMany` with `orphanRemoval` when order doesn't matter, to avoid the "delete-all-then-reinsert" behavior Hibernate uses for bag (`List`) collections.

### Q7. [Theory] What does `@Transactional` do, and where should it live?

`@Transactional` (Spring) wraps a method in a database transaction: a proxy begins a transaction before the method, commits on normal return, and rolls back on a runtime exception (by default `RuntimeException`/`Error`, **not** checked exceptions unless you set `rollbackFor`). Within that transaction the persistence context stays open, so lazy loading works and dirty checking flushes changes at commit.

It belongs on the **service layer**, not repositories or controllers — the service defines the unit of work / business transaction boundary. Two gotchas: (1) self-invocation (calling a `@Transactional` method from within the same bean) bypasses the proxy and does nothing; (2) the default propagation is `REQUIRED`, which joins an existing transaction.

---

## 🟡 Intermediate (3–7 yrs)

### Q8. [Theory] Explain the N+1 select problem and the main ways to fix it.

N+1 happens when you fetch a list of N parent entities with one query, then a lazy association triggers **one additional query per parent** to load its children — total N+1 queries. It is the single most common Hibernate performance bug.

```
SELECT * FROM author;                       -- 1 query  → 100 authors
-- then for each author, on getBooks():
SELECT * FROM book WHERE author_id = 1;     -- query 2
SELECT * FROM book WHERE author_id = 2;     -- query 3
...                                         -- 100 more queries  ⇒ 101 total
```

Fixes:

1. **`JOIN FETCH`** in JPQL: `SELECT a FROM Author a JOIN FETCH a.books` — one query with a join. Best for a single targeted query.
2. **Entity graph** (`@EntityGraph(attributePaths = "books")`) — declarative, reusable, doesn't pollute the query string.
3. **`@BatchSize(size = 25)`** (Hibernate) — instead of N selects, loads children in batches using `WHERE author_id IN (?,?,...)`, turning N+1 into roughly N/25 + 1. Set globally via `hibernate.default_batch_fetch_size`. This is the best "set and forget" mitigation.
4. **DTO projection** — query only the columns you need with a constructor expression, sidestepping associations entirely.

Caveat: never `JOIN FETCH` **two** collection associations at once — that produces a Cartesian product. Use batch size or separate queries instead.

### Q9. [Coding] Demonstrate three ways to fix N+1 for loading authors with their books.

```java
// --- Approach 1: JPQL JOIN FETCH (DISTINCT avoids duplicate parents from the join) ---
@Query("SELECT DISTINCT a FROM Author a LEFT JOIN FETCH a.books")
List<Author> findAllWithBooks();   // 1 SQL query

// --- Approach 2: Spring Data JPA Entity Graph ---
@EntityGraph(attributePaths = {"books"})
@Query("SELECT a FROM Author a")
List<Author> findAllWithBooksGraph();

// --- Approach 3: Batch fetching (entity stays lazy, but loads in IN-batches) ---
@Entity
public class Author {
    @OneToMany(mappedBy = "author", fetch = FetchType.LAZY)
    @org.hibernate.annotations.BatchSize(size = 25)
    private List<Book> books = new ArrayList<>();
}
```

**Complexity:** Approach 1 & 2 = **O(1)** round-trips (single query) but the join fetch returns `rows = authors × books` so memory is `O(N·M)` and you lose DB-side pagination on collection fetches. Approach 3 = **O(N/batchSize)** round-trips with `O(N)` memory — far better when you have many parents.

**Edge cases:** `JOIN FETCH` + `setMaxResults` (pagination) on a collection forces Hibernate to paginate **in memory** (it logs `HHH000104: firstResult/maxResults specified with collection fetch; applying in memory`) — dangerous on large tables. For paginated collection loads, fetch IDs first (one query), then fetch the entities with `WHERE id IN (...)` plus join fetch — the "two-query" pattern.

### Q10. [Theory] First-level vs second-level cache — how do they differ?

- **First-level (L1) cache** is the **persistence context** itself — one per `EntityManager`/`Session`, scoped to a single transaction. It is *always on* and not configurable. It guarantees within a transaction that `em.find(User, 1)` called twice returns the **same Java instance** (repeatable reads at the object level) and avoids re-querying. It also enables dirty checking. It is flushed/cleared when the session closes.

- **Second-level (L2) cache** is **shared across sessions/transactions**, scoped to the `EntityManagerFactory`/`SessionFactory` (i.e., the whole app, or a cluster with a distributed cache). It is **opt-in**, requires a provider (EhCache, Caffeine, Infinispan, Hazelcast) and `@Cacheable` + `@Cache(usage = ...)` per entity. It caches entity state (not objects), collections, and optionally query results (query cache, which needs the L2 cache to also store the underlying entities).

```
   SessionFactory
   ┌───────────────────────────────────────────────┐
   │  L2 cache (shared, opt-in)  +  Query cache      │
   └───────────────────────────────────────────────┘
        ▲                 ▲                  ▲
   ┌────┴────┐       ┌────┴────┐        ┌────┴────┐
   │Session 1│       │Session 2│        │Session 3│
   │ L1 cache│       │ L1 cache│        │ L1 cache│   ← per-transaction, always on
   └─────────┘       └─────────┘        └─────────┘
```

L2 is best for read-mostly reference data. With `READ_WRITE` or `TRANSACTIONAL` strategies it stays consistent; `NONSTRICT_READ_WRITE` allows brief staleness. The big risk: stale data when other apps (or raw SQL) modify the DB without going through Hibernate.

### Q11. [Theory] How does dirty checking work, and when does a flush actually happen?

When an entity is **managed**, Hibernate keeps a snapshot of its loaded state (the "hydrated state"). At flush time it compares each managed entity's current field values against that snapshot; any differences generate `UPDATE` statements automatically — you never call `update()`. This is **automatic dirty checking**.

A flush (synchronizing the persistence context to the DB, but *not* committing) happens:
1. Before the transaction commits.
2. Before a JPQL/HQL/Criteria query that might overlap pending changes (so the query sees your changes) — controlled by `FlushModeType` (`AUTO` default, `COMMIT` to defer).
3. When you explicitly call `em.flush()`.

Flushing does **not** commit; rollback still undoes everything. Performance tip: dirty checking cost is proportional to (number of managed entities × number of fields), so don't keep tens of thousands of entities managed — clear the context in batch jobs (`em.clear()`), or use `@Immutable`/`@Transactional(readOnly = true)` to skip snapshot/dirty checks for read-only flows.

### Q12. [Practical] A read-only API endpoint is slow and the GC churns. What do you change?

Likely cause: Hibernate loads full managed entities (taking a dehydrated snapshot for dirty checking) when you only render a few fields. Steps:

1. Mark the transaction `@Transactional(readOnly = true)`. With Hibernate 5.2+ this sets `FlushMode.MANUAL` and skips the dirty-checking snapshot, reducing memory and flush overhead.
2. Switch to a **DTO projection** (constructor expression or interface projection) so the SQL `SELECT`s only the needed columns. This avoids hydrating entities, association proxies, and the L1 snapshot entirely.
3. For large result sets, stream (`Stream<T>`) or paginate, and consider `hibernate.jdbc.fetch_size`.

In production I'd ship the DTO projection first — it usually cuts both query payload and heap dramatically, and it eliminates `LazyInitializationException` risk since you never expose a managed entity to the web layer.

### Q13. [Coding] Write a DTO projection three ways (constructor expression, interface projection, and Criteria/Tuple).

```java
// Plain DTO record (Java 16+)
public record BookView(Long id, String title, String authorName) {}

// --- 1. JPQL constructor expression (compile-time fragile to package moves) ---
@Query("""
       SELECT new com.example.dto.BookView(b.id, b.title, b.author.name)
       FROM Book b
       """)
List<BookView> findBookViews();

// --- 2. Spring Data interface projection (proxy generated from getters) ---
public interface BookSummary {
    Long getId();
    String getTitle();
    @Value("#{target.author.name}") String getAuthorName(); // SpEL for nested
}
List<BookSummary> findByTitleContaining(String kw);  // derived query, returns proxies

// --- 3. Criteria API with Tuple (dynamic, type-safe-ish) ---
public List<BookView> findViaCriteria(EntityManager em) {
    CriteriaBuilder cb = em.getCriteriaBuilder();
    CriteriaQuery<BookView> cq = cb.createQuery(BookView.class);
    Root<Book> b = cq.from(Book.class);
    cq.select(cb.construct(BookView.class,
            b.get("id"), b.get("title"), b.get("author").get("name")));
    return em.createQuery(cq).getResultList();
}
```

**Trade-offs:** constructor expressions are the most explicit and DB-efficient but break if you move/rename the DTO package. Interface projections are concise and Spring fetches only the referenced columns (for closed projections), but nested SpEL projections (`@Value`) become **open projections** that fetch the whole entity — losing the benefit. Criteria is best for dynamic queries built at runtime. **Edge case:** constructor expressions can't fetch collections; for parent-with-children DTOs, fetch flat rows and assemble in Java, or use a `@SqlResultSetMapping`.

### Q14. [Theory] Compare identifier generation strategies: IDENTITY, SEQUENCE, TABLE, AUTO, UUID.

- **`IDENTITY`** uses an auto-increment column. Simple, but it **disables JDBC batch inserts** for that entity because Hibernate must execute each `INSERT` immediately to read back the generated key. Bad for high-volume inserts.
- **`SEQUENCE`** uses a database sequence. Preferred on PostgreSQL/Oracle; Hibernate can pre-fetch ids and **batch inserts**. Combine with the **pooled / pooled-lo optimizer** and `allocationSize` to amortize sequence round-trips (e.g., `allocationSize=50` grabs 50 ids per network call).
- **`TABLE`** emulates a sequence using a separate table with row locking. Portable but slow and contention-prone — avoid unless the DB has no sequences.
- **`AUTO`** lets the provider pick. In Hibernate 5 on MySQL this meant TABLE (surprising/slow); behavior changed across versions, so prefer being explicit.
- **`UUID`** (`@GeneratedValue(strategy = UUID)` in JPA 3.1 / Hibernate 6, or `@UuidGenerator`) is app-assigned, great for distributed systems and pre-persist equality, but random UUIDv4 hurts B-tree index locality (page splits). Use **UUIDv7** / time-ordered UUIDs, or store as `BINARY(16)`/native `uuid` type rather than `CHAR(36)`.

**Rule of thumb:** SEQUENCE with a pooled optimizer for relational DBs that support it; time-ordered UUID for distributed/sharded systems.

### Q15. [Theory] JPQL vs Criteria API vs native SQL — when do you use each?

- **JPQL/HQL** is an object-oriented query language over entities (not tables). Use it for the bulk of static queries; it's portable and readable, and Hibernate translates it to dialect-specific SQL.
- **Criteria API** builds queries programmatically as Java objects. Use it when the query shape is **dynamic** (optional filters, sorting chosen at runtime) — you avoid string concatenation and SQL injection. With the JPA **metamodel** (`Book_.title`) it's type-safe and refactor-friendly, at the cost of verbosity.
- **Native SQL** (`@Query(nativeQuery = true)` or `em.createNativeQuery`) is for DB-specific features (window functions, CTEs, `JSONB`, hints, recursive queries) or hand-tuned performance. You lose portability and some ORM features; map results with `@SqlResultSetMapping` or to DTOs.

In practice: JPQL by default, Criteria for dynamic search screens, native for the 5% that needs raw SQL power.

### Q16. [Coding] Build a type-safe dynamic search with Criteria API and optional filters.

```java
public List<Book> search(EntityManager em, String titleLike,
                         Long authorId, Integer minYear) {
    CriteriaBuilder cb = em.getCriteriaBuilder();
    CriteriaQuery<Book> cq = cb.createQuery(Book.class);
    Root<Book> book = cq.from(Book.class);

    List<Predicate> predicates = new ArrayList<>();
    if (titleLike != null && !titleLike.isBlank()) {
        predicates.add(cb.like(cb.lower(book.get("title")),
                               "%" + titleLike.toLowerCase() + "%"));
    }
    if (authorId != null) {
        predicates.add(cb.equal(book.get("author").get("id"), authorId));
    }
    if (minYear != null) {
        predicates.add(cb.greaterThanOrEqualTo(book.get("year"), minYear));
    }
    cq.where(predicates.toArray(Predicate[]::new));  // AND of all present filters
    cq.orderBy(cb.asc(book.get("title")));
    return em.createQuery(cq).getResultList();
}
```

**Why this beats string concatenation:** parameters are bound (no SQL injection), and absent filters simply contribute no predicate. **Edge cases:** an empty predicate list yields an unfiltered `SELECT` (which you may want to forbid); `LIKE` on a non-indexed column is a full scan — consider a trigram/GIN index. **Complexity:** query construction is O(number of filters); execution depends on indexing. **Spring alternative:** the JPA `Specification<T>` API wraps exactly this pattern.

### Q17. [Practical] When and why would you use `@Version` for optimistic locking?

Use optimistic locking when conflicting concurrent updates are **rare** and you don't want to hold DB locks (high throughput, web request scope). Add a `@Version` column (int/long/timestamp). On update, Hibernate adds `WHERE id = ? AND version = ?` and increments the version; if zero rows match, someone else updated the row first and Hibernate throws `OptimisticLockException` (Spring: `ObjectOptimisticLockingFailureException`).

```sql
UPDATE book SET title=?, version = 3 WHERE id = ? AND version = 2;  -- 0 rows ⇒ conflict
```

Production handling: catch the exception, reload, re-apply or merge changes, and either retry (idempotent operations) or surface a 409 to the user. Real-world example: an e-commerce inventory or wallet-balance update — two requests decrementing stock simultaneously. Optimistic locking lets both proceed without blocking, and the loser retries; this scales far better than pessimistic row locks under contention that's actually rare. If contention is **high** (a hot row everyone updates), switch to pessimistic locking instead.

---

## 🟠 Advanced (8–12 yrs)

### Q18. [Theory] Contrast optimistic and pessimistic locking, including the pessimistic lock modes.

**Optimistic** (`@Version`) detects conflicts at write time — no DB locks held, best for low contention and long "think time" (a user editing a form). **Pessimistic** locking acquires DB-level locks *up front* so others block, best for short, high-contention critical sections.

JPA `LockModeType`:
- `PESSIMISTIC_READ` → shared lock (`SELECT ... FOR SHARE`): others can read, not write.
- `PESSIMISTIC_WRITE` → exclusive lock (`SELECT ... FOR UPDATE`): others block on read-for-update and write.
- `PESSIMISTIC_FORCE_INCREMENT` → write lock **and** bumps `@Version` (locks parent when modifying children).
- `OPTIMISTIC` / `OPTIMISTIC_FORCE_INCREMENT` → version check at flush, optionally forcing an increment.

```java
Book b = em.find(Book.class, id, LockModeType.PESSIMISTIC_WRITE);
// other tx doing SELECT ... FOR UPDATE on same row will block here
```

Pessimistic risks: **deadlocks** (always lock in a consistent order), lock-wait timeouts, and reduced concurrency. Use `jakarta.persistence.lock.timeout` to fail fast (`SELECT ... FOR UPDATE NOWAIT` / `SKIP LOCKED`). `SKIP LOCKED` is the classic pattern for a DB-backed job queue where each worker grabs the next unlocked row.

### Q19. [Theory] How does transaction isolation interact with Hibernate's caches and locking?

Hibernate delegates isolation to the DB (set via the JDBC connection / `spring.datasource`). The L1 cache provides **application-level repeatable reads within one transaction** regardless of the DB isolation — calling `find` twice returns the same instance from L1, so you might not see another transaction's committed change even under `READ_COMMITTED`. This can surprise developers expecting fresh data; use `em.refresh()` to force a re-read.

Phantom and non-repeatable-read anomalies still depend on DB isolation. Optimistic locking (`@Version`) effectively gives you **first-committer-wins** semantics on top of `READ_COMMITTED`, which is why most apps run `READ_COMMITTED` + `@Version` rather than paying for `SERIALIZABLE`. Beware that the L2 cache with `NONSTRICT_READ_WRITE` can return slightly stale data after a commit — use `READ_WRITE`/`TRANSACTIONAL` if you need consistency.

### Q20. [Practical] A nightly batch inserts 1M rows and either OOMs or runs for hours. How do you tune it?

```
Symptoms: heap grows linearly, flush gets slower each iteration, one INSERT per row in logs.
```

Fixes, in order:
1. **Enable JDBC batching:** `hibernate.jdbc.batch_size=50` (or 100), `hibernate.order_inserts=true`, `hibernate.order_updates=true`. Ordering groups same-table statements so the JDBC driver can batch them.
2. **Avoid `IDENTITY` ids** — they kill batching. Use `SEQUENCE` with a pooled optimizer and a sane `allocationSize`.
3. **Flush + clear periodically:** every batch_size rows call `em.flush(); em.clear();` to release managed entities and bound the L1 cache / dirty-check cost.
4. **Disable cascade & L2 churn** for the batch; consider `StatelessSession` (no L1 cache, no dirty checking, no cascades) for pure bulk work.
5. For massive volumes, prefer a **bulk JPQL `UPDATE`/`DELETE`** or the DB's native COPY/bulk loader. Bulk JPQL bypasses the persistence context — so it doesn't trigger cascades or update the L1 cache (clear it afterward).

```java
for (int i = 0; i < rows.size(); i++) {
    em.persist(toEntity(rows.get(i)));
    if (i % 50 == 0) { em.flush(); em.clear(); }   // bound memory + batch
}
```

In production I'd benchmark batch sizes (50–200 is typical), watch the actual SQL with `datasource-proxy`/p6spy, and confirm batching with the Hibernate statistics. MySQL also needs `rewriteBatchedStatements=true` on the JDBC URL for batching to actually rewrite into multi-row inserts.

### Q21. [Coding] Implement safe optimistic-lock retry logic.

```java
@Service
@RequiredArgsConstructor
public class WalletService {
    private final WalletRepository repo;

    @Retryable(retryFor = ObjectOptimisticLockingFailureException.class,
               maxAttempts = 3, backoff = @Backoff(delay = 50, multiplier = 2))
    @Transactional
    public void debit(Long walletId, BigDecimal amount) {
        Wallet w = repo.findById(walletId)
                       .orElseThrow(() -> new NoSuchElementException("wallet"));
        if (w.getBalance().compareTo(amount) < 0) {
            throw new InsufficientFundsException();  // business error: do NOT retry
        }
        w.setBalance(w.getBalance().subtract(amount));
        // dirty checking + @Version => UPDATE ... WHERE id=? AND version=?
        // commit fails with OptimisticLock if another tx changed the row first
    }
}
```

**Why this is correct:** each retry runs a **fresh transaction** that reloads the current version, so it re-checks the balance against the latest state — never blindly re-applying stale arithmetic. **Edge cases:** the operation must be idempotent or recompute from reloaded state (it does); cap retries to avoid livelock under heavy contention; classify `InsufficientFundsException` as non-retryable. **Time complexity:** O(retries) DB round-trips; expected ~1 under low contention. For a permanently hot row, switch to `PESSIMISTIC_WRITE` (serializing access) rather than retrying forever.

### Q22. [Theory] Explain `@Embeddable`, inheritance mapping strategies, and their trade-offs.

`@Embeddable`/`@Embedded` is **composition** — value objects (e.g., `Money`, `Address`) whose columns live inline in the owner's table. No identity, no lifecycle of their own. Great for DDD value objects and for grouping columns.

Inheritance strategies:
- **`SINGLE_TABLE`** (default): all subclasses in one table with a discriminator column. Fastest (no joins), but subclass-specific columns must be nullable, weakening DB constraints.
- **`JOINED`**: a table per class; subclass rows join to the parent table on PK. Normalized, allows `NOT NULL`, but every read/write does a join — slower.
- **`TABLE_PER_CLASS`**: one table per concrete class with all columns repeated. Polymorphic queries use `UNION` and are slow; identity generation is awkward. Rarely the right choice.

```
SINGLE_TABLE        JOINED                TABLE_PER_CLASS
┌──────────────┐    ┌──────────┐          ┌──────────────┐ ┌──────────────┐
│ payment      │    │ payment  │          │ card_payment │ │ paypal_pay   │
│ dtype |card  │    │  id, amt │          │ (all cols)   │ │ (all cols)   │
│ |paypal cols │    └────┬─────┘          └──────────────┘ └──────────────┘
└──────────────┘    ┌────┴────┬────────┐
  1 table           │card_pay │paypal  │   UNION for polymorphic queries
  + discriminator   └─────────┴────────┘
```

Default to `SINGLE_TABLE` for performance unless you need column-level integrity, then `JOINED`. Prefer composition (`@Embeddable`/interfaces) over entity inheritance when you can — ORM inheritance is rigid.

### Q23. [Practical] Production logs show duplicate/Cartesian rows and a `MultipleBagFetchException`. Diagnose and fix.

`MultipleBagFetchException` is thrown at startup when you try to `JOIN FETCH` **two `List` (bag) collections** on the same entity — Hibernate can't reconcile the cross product into bags. Even when it doesn't throw (e.g., with `Set`), fetching two collections produces a **Cartesian product**: if a `Post` has 10 comments and 5 tags, a double join returns 50 rows, blowing up memory and bandwidth.

Fixes:
1. **Change one or both collections to `Set`** to avoid the bag exception — but the Cartesian product remains, so this alone isn't enough.
2. **Fetch one collection per query** (two queries hitting the same managed entities), or use **`@BatchSize`** / `default_batch_fetch_size` so the second collection loads via `IN` batches.
3. Use **`FetchMode.SUBSELECT`** so the collection loads with a single subselect query keyed off the original query's IDs.

The robust production pattern: load the root + first collection with `JOIN FETCH` (paginated by IDs), and let secondary collections load via batch fetching. Avoid ever join-fetching two collections together.

### Q24. [Theory] What is the difference between `save`/`persist`, `merge`, `saveOrUpdate`, and `update`?

- **`persist`** (JPA) makes a transient entity managed; it does **not** guarantee an immediate `INSERT` (it may defer to flush) and returns void. Throws if the entity is detached.
- **`save`** (Hibernate-native) returns the generated id and forces the id to be assigned (may insert eagerly for `IDENTITY`).
- **`merge`** (JPA) copies the state of a **detached** (or transient) entity onto a **managed** instance and returns that managed instance — *the argument you pass in stays detached*. Forgetting to use the return value is a classic bug. Merge may issue a `SELECT` to load the current row first.
- **`update`** (Hibernate-native) reattaches a detached entity to the session, assuming it represents an existing row; throws if a different instance with the same id is already managed.
- **`saveOrUpdate`** (Hibernate-native) chooses insert vs update based on whether the id/version looks new.

Modern guidance: prefer the JPA methods (`persist` for new, `merge` for detached). Spring Data's `save()` delegates to `persist` for new entities (no id / version) and `merge` otherwise; with `@GeneratedValue` it detects "new" via a null id.

### Q25. [Practical] How do you decide whether to enable the second-level cache, and what are the risks at scale?

Enable L2 only for entities that are **read frequently and written rarely** with high cache-hit ratios — reference/lookup data (countries, product categories, config). Measure first via Hibernate statistics (hit/miss/put counts).

Risks at scale: (1) **staleness** if any process writes the DB outside Hibernate (reports, other services, manual SQL) — the cache won't be invalidated; (2) **distributed invalidation cost** — in a cluster you need a distributed/replicated cache (Infinispan, Hazelcast) and a strategy (`READ_WRITE` with soft locks vs `TRANSACTIONAL`); (3) **memory pressure** and tuning eviction; (4) the **query cache** is notoriously tricky — it stores only IDs, then loads entities from the L2 cache, and is invalidated on any write to the involved tables, so it often hurts write-heavy workloads.

In a microservices world I usually prefer an **application-level cache** (Caffeine/Redis) at the service boundary over Hibernate's L2, because it's explicit, observable, and decoupled from the persistence layer — but L2 is excellent for monolith reference data.

### Q26. [Theory] What are derived (composite) identifiers, `@IdClass` vs `@EmbeddedId`, and `@MapsId`?

A composite key uses multiple columns. JPA offers two mappings:
- **`@EmbeddedId`** — a single `@Embeddable` key field; access fields via the embedded object (`order.getId().getLineNo()`).
- **`@IdClass`** — separate `@Id` fields on the entity plus a matching key class; cleaner field access but more boilerplate keeping the two classes in sync.

`@MapsId` is used in **derived identifiers**: when an entity's PK is (or includes) the FK to its parent (shared primary key, common in `@OneToOne` and weak-entity `@OneToMany`). It tells Hibernate to derive the id from the association, avoiding a redundant column and an extra `SELECT`:

```java
@Entity
class UserProfile {
    @Id Long id;                       // same value as user.id
    @OneToOne @MapsId @JoinColumn(name = "id")
    User user;
}
```

This is the efficient way to model 1:1 (e.g., `User` ↔ `UserProfile`) because it shares the PK rather than carrying a nullable FK and avoids the extra select Hibernate otherwise does to decide null-vs-proxy on a lazy one-to-one.

---

## 🔴 Expert (15+ yrs)

### Q27. [Theory] Why is a lazy `@OneToOne` on the *inverse* (non-owning) side problematic, and how do you fix it?

For a `@ManyToOne`/owning `@OneToOne`, Hibernate has the FK value and can create a proxy without hitting the DB — lazy works. But for the **inverse side** of a `@OneToOne` (the side with `mappedBy`), Hibernate doesn't know whether the association is `null` or present without querying, because the FK lives on the other table. Therefore it must execute a `SELECT` even to decide proxy-vs-null — defeating laziness and reintroducing N+1.

Fixes: (1) make the relationship **bidirectional with a shared PK using `@MapsId`** so the child's existence is implied by its own PK; (2) use **bytecode enhancement** (`hibernate-enhance-maven-plugin`) with `@LazyToOne(LazyToOneOption.NO_PROXY)` (Hibernate 5) / lazy `@OneToOne` (Hibernate 6 enhanced) so Hibernate intercepts field access and lazily loads even the inverse side; (3) remodel as `@ManyToOne` where the FK can live on the queried side. This is a frequent staff-level "why is this query firing?" investigation.

### Q28. [Practical] A team's microservice has unpredictable latency spikes traced to the ORM. Walk through your investigation.

```
Plan: prove it's the ORM → find the bad pattern → fix at the right layer → add guardrails.
```

1. **Observe the SQL, not the code.** Turn on `datasource-proxy` / p6spy (with bind params and per-query timing) or Hibernate statistics. `show_sql=true` alone lies because it doesn't show batching/timing. Add the `n_plus_one` detector or assertions in tests (e.g., `DataSourceAssertions` counting queries).
2. **Look for the classic culprits:** OSIV firing queries during JSON serialization; eager associations dragging graphs; N+1; missing `@BatchSize`; collection fetch with pagination paginating in memory; L1 cache bloat in long transactions; connection-pool starvation (HikariCP) from long-running transactions holding connections during external calls.
3. **Correlate with pool metrics.** Latency spikes often mean **connection-pool exhaustion**: a `@Transactional` method making a slow REST call while holding a DB connection. Fix by moving I/O outside the transaction and shrinking transaction scope.
4. **Fix at the right layer:** DTO projections for read paths, `JOIN FETCH`/entity graph for specific aggregates, batch fetching globally, disable OSIV.
5. **Add guardrails:** integration tests asserting query counts, `spring.jpa.open-in-view=false`, statement timeouts, and an SLO on DB time per request.

**Real-world case:** a payments service had p99 spikes every few minutes; the root cause was OSIV plus an eager `@ManyToMany`, so list endpoints fired 200+ queries and exhausted the 10-connection pool during traffic bursts. Disabling OSIV, making associations lazy, and adding DTO projections cut p99 by ~80% and removed the pool-starvation alerts.

### Q29. [Theory] Discuss the trade-offs of the Repository pattern + ORM versus alternatives (jOOQ, MyBatis, raw JDBC) at scale.

ORMs (Hibernate/JPA) maximize productivity for **write-heavy, aggregate-oriented domains** with rich object graphs, cascading, dirty checking, and optimistic locking. They struggle with **complex read models**: reporting, analytics, dynamic projections, set-based operations, and DB-specific SQL — where the object-relational impedance mismatch and the persistence-context overhead get in the way.

A mature pattern is **CQRS-style split**: use JPA/Hibernate for the command/write side (entities, invariants, transactions) and a lightweight SQL mapper — **jOOQ** (type-safe SQL DSL, excellent for complex reads) or **MyBatis** (SQL in XML/annotations) — for the query/read side. jOOQ gives compile-time-checked SQL and shines for analytics; MyBatis suits teams that want full SQL control with mapping convenience; raw JDBC/`JdbcTemplate` is fine for a handful of hot queries. The cost is two mental models and tooling. At staff level the call is: don't force everything through one tool — Hibernate for the domain, jOOQ/native for the hard SQL.

### Q30. [Coding] Implement a robust DB-backed job queue using `SKIP LOCKED` (pessimistic, non-blocking).

```java
public interface JobRepository extends JpaRepository<Job, Long> {

    // PESSIMISTIC_WRITE + SKIP LOCKED: each worker grabs the next free row
    // without blocking on rows another worker already locked.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")) // -2 = SKIP LOCKED
    @Query("""
           SELECT j FROM Job j
           WHERE j.status = 'PENDING'
           ORDER BY j.createdAt
           """)
    List<Job> pickBatch(Pageable pageable);
}

@Service
@RequiredArgsConstructor
class JobWorker {
    private final JobRepository repo;

    @Transactional   // lock held for the duration of the transaction
    public void processNext() {
        List<Job> jobs = repo.pickBatch(PageRequest.of(0, 10));
        for (Job j : jobs) {
            j.setStatus("RUNNING");      // dirty checking persists at commit
            doWork(j);
            j.setStatus("DONE");
        }
        // commit releases the row locks
    }
}
```

**Why this scales:** `FOR UPDATE SKIP LOCKED` lets N workers consume disjoint job sets concurrently with zero lock contention — no Redis/Kafka needed for moderate volume. **Edge cases:** keep the transaction short (don't do slow external I/O while holding row locks — move heavy work out and only lock to claim); handle worker crash (a separate reaper resets stale `RUNNING` rows past a timeout); `SKIP LOCKED` hint value differs by provider — on Postgres Hibernate emits `FOR UPDATE SKIP LOCKED`. **Complexity:** O(batch size) rows locked per poll; throughput scales with worker count until DB write saturation.

### Q31. [Theory] How do you keep ORM mapping changes safe across deployments (schema migration discipline)?

Never let Hibernate manage production DDL: set `hibernate.hbm2ddl.auto=validate` (or `none`) in prod, and own schema evolution with **Flyway** or **Liquibase** versioned migrations. Hibernate's `update` mode is non-deterministic, can't drop/rename, and never runs in prod. The entity model and the migration scripts must be reviewed together so they don't drift.

For zero-downtime deploys, follow the **expand/contract (parallel change)** pattern: (1) expand — add nullable columns/tables in a backward-compatible migration; (2) deploy code that writes both old and new; (3) backfill; (4) switch reads; (5) contract — drop the old column in a later release. This avoids the classic failure where a new `NOT NULL` column breaks the still-running old instances during a rolling deploy. Keep migrations forward-only, idempotent where possible, and test them against a prod-like snapshot in CI.

### Q32. [Behavioral] A senior engineer insists on `EAGER` everywhere "to avoid LazyInitializationException." How do you handle it?

I'd separate the legitimate pain (lazy exceptions are real and annoying) from the proposed cure (which is worse). First I'd acknowledge their goal — predictable data loading — then show data: I'd enable SQL logging on a representative endpoint and demonstrate the query explosion and latency that `EAGER` + OSIV cause, ideally on their own slow endpoint. Concrete evidence beats architectural arguments.

Then I'd propose the real fix as a pattern, not a one-off: lazy by default, fetch explicitly per use case via DTO projections and entity graphs, disable OSIV, and add an integration test that asserts query counts so regressions are caught automatically. I'd frame it as reducing on-call pain, not "you're wrong." If they still resist, I'd pilot it on one endpoint, measure, and let the numbers (and the removed pager alerts) make the case. The behavioral key: lead with empathy for the underlying problem, decide with data, and institutionalize the fix so it doesn't rely on tribal knowledge.

### Q33. [Practical] Security: how can ORM usage introduce vulnerabilities, and how do you prevent them?

1. **JPQL/HQL injection** — concatenating user input into a query string (even JPQL) is injectable. Always use **named/positional parameters** (`:name`), never string concatenation. Native queries are equally vulnerable; parameterize them too.
2. **`ORDER BY` / dynamic column injection** — bind parameters can't parameterize column names or sort direction, so allowlist them; don't pass raw user input into `Sort`/`order by`.
3. **Mass assignment / over-posting** — binding request JSON directly onto an `@Entity` lets attackers set fields they shouldn't (e.g., `role`, `isAdmin`, `balance`). Use **DTOs** for input and map explicitly; never bind the web request straight to a managed entity.
4. **Information disclosure** — serializing entities can leak lazy associations, internal fields, or trigger queries; use response DTOs and `@JsonIgnore`.
5. **Second-order issues** — caching sensitive data in L2 across tenants, or returning other tenants' rows because a `tenant_id` filter was forgotten (use Hibernate `@Filter`/multi-tenancy or always scope queries by tenant). **DoS:** unbounded queries — always paginate and set statement timeouts.

The unifying principle: treat the persistence layer as a trust boundary — parameterize everything, use DTOs in and out, allowlist dynamic SQL fragments, and scope every query by tenant/owner.

### Q34. [Theory] What changed in Hibernate 6 / JPA 3.1 that matters for a senior engineer migrating from Hibernate 5?

The headline change is the **`javax.persistence` → `jakarta.persistence` namespace** move (Jakarta EE 9+), which forces a coordinated upgrade with **Spring Boot 3** and **Java 17+** baseline. Hibernate 6 also rewrote the query engine: a new **semantic query model (SQM)** and a fully reworked SQL AST, yielding better SQL, smarter type handling (the new `JdbcType`/`JavaType` system replacing the old `Type`), and improved literal/parameter handling.

Other notable items: native support for `@GeneratedValue(strategy = UUID)` and time-based UUID generators; better read-by-`id` `MultiLoad`; improved `byte[]`/`LOB` handling; `@TimeZoneStorage`; stricter handling that surfaces previously silent mapping issues (some Hibernate 5 code emits new warnings/errors). For migration: audit removed/renamed APIs (the old `Criteria` API was already gone; `IdentifierGenerator` SPI changed), re-test generated SQL for any hand-tuned queries, and verify L2 cache provider compatibility. Treat it as a query-engine upgrade, not just a package rename.

---

## ✅ Key Takeaways

- **JPA is the spec; Hibernate is the implementation** — know which features are portable vs Hibernate-only.
- **Lazy by default, fetch explicitly.** Make all associations `LAZY` and pull what you need with `JOIN FETCH`, entity graphs, or DTO projections per use case.
- **N+1 is the #1 ORM bug.** Detect it with SQL logging and query-count tests; fix with join fetch / entity graph for specific queries and global `@BatchSize` as a safety net.
- **Disable Open Session In View** (`spring.jpa.open-in-view=false`) — it hides lazy problems and starves the connection pool.
- **Dirty checking auto-updates managed entities;** keep the managed set small in batch jobs (`flush()` + `clear()`), and use `readOnly` transactions for read paths.
- **Prefer `SEQUENCE` (pooled) ids for batching;** `IDENTITY` disables JDBC insert batching; use time-ordered UUIDs for distributed systems.
- **Optimistic locking (`@Version`) for low contention; pessimistic (`SELECT ... FOR UPDATE`, `SKIP LOCKED`) for hot rows and job queues.**
- **Use DTOs at every boundary** — for input (prevent mass assignment) and output (avoid lazy serialization and data leaks).
- **Never let Hibernate manage prod DDL** — use Flyway/Liquibase with expand/contract migrations.

## ⚠️ Common Pitfalls

- Relying on the EAGER defaults of `@ManyToOne`/`@OneToOne` and triggering uncontrolled fetches.
- `JOIN FETCH`-ing two collections → `MultipleBagFetchException` or a Cartesian product.
- Pagination (`setMaxResults`) combined with a collection `JOIN FETCH` → silent in-memory pagination (`HHH000104`).
- Using the auto-generated DB id in `equals`/`hashCode` → broken `Set` semantics before persist; use a business key or assigned UUID.
- Ignoring the return value of `merge()` (the argument stays detached).
- `@Transactional` self-invocation doing nothing because it bypasses the Spring proxy.
- Forgetting to set the **owning side** of a bidirectional association → FK stays null.
- Holding a DB connection during slow external I/O inside `@Transactional` → connection-pool starvation and latency spikes.
- Leaving `hibernate.ddl-auto=update` in production.
- Binding HTTP request bodies directly to entities (mass-assignment vulnerability).
- Lazy `@OneToOne` on the inverse side still firing a SELECT (needs `@MapsId` or bytecode enhancement).

## 📚 Further Reading

- **Vlad Mihalcea — _High-Performance Java Persistence_** (the definitive deep-dive on Hibernate performance) and his blog at vladmihalcea.com.
- **_Java Persistence with Hibernate_, 2nd ed.** — Bauer, King, Gregory (Manning).
- **Jakarta Persistence 3.1 Specification** — jakarta.ee/specifications/persistence/.
- **Hibernate ORM 6 User Guide** — docs.jboss.org/hibernate/orm/6.x/userguide/.
- **Spring Data JPA Reference Documentation** — docs.spring.io/spring-data/jpa/reference/.
- **Thorben Janssen — _Hibernate Tips_** and thorben-janssen.com (practical N+1, projections, fetching recipes).
