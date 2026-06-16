# EclipseLink

EclipseLink is the JPA reference implementation (the Eclipse-hosted successor to Oracle TopLink) and a broader persistence framework that also covers JAXB/JSON binding (MOXy), NoSQL, and the Database Web Services. This guide prepares you for interview questions ranging from "what is the reference implementation?" to deep cache-architecture and migration trade-offs.

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

### Q1. [Theory] What is EclipseLink and why is it called the JPA reference implementation?
EclipseLink is an open-source persistence framework hosted by the Eclipse Foundation. It descends from Oracle TopLink, which Oracle donated to Eclipse in 2007. It was selected as the **reference implementation (RI)** for JPA 1.0 (JSR 220), and continued as the RI through JPA 2.0/2.1/2.2 and now **Jakarta Persistence 3.x** (the `jakarta.persistence` namespace after the Java EE → Jakarta EE move). "Reference implementation" means it is the canonical, spec-compliant implementation against which the JPA TCK (Technology Compatibility Kit) is validated — when the spec is ambiguous, EclipseLink's behavior is effectively the tie-breaker. Beyond JPA, EclipseLink provides MOXy (object-to-XML/JSON binding implementing JAXB), an SDO implementation, and NoSQL/EIS support, so it is a "persistence services" project rather than just an ORM.

### Q2. [Theory] How does EclipseLink relate to JPA, and what does a minimal `persistence.xml` look like?
JPA is a *specification* (interfaces and annotations like `@Entity`, `EntityManager`, `EntityManagerFactory`); EclipseLink is one *implementation* of that spec, just as Hibernate ORM is another. You write against the JPA API and select EclipseLink as the provider in `persistence.xml`.

```xml
<persistence xmlns="https://jakarta.ee/xml/ns/persistence" version="3.0">
  <persistence-unit name="orders-pu" transaction-type="RESOURCE_LOCAL">
    <provider>org.eclipse.persistence.jpa.PersistenceProvider</provider>
    <class>com.shop.Order</class>
    <properties>
      <property name="jakarta.persistence.jdbc.url"  value="jdbc:postgresql://localhost/shop"/>
      <property name="jakarta.persistence.jdbc.user" value="app"/>
      <property name="eclipselink.logging.level" value="FINE"/>
      <property name="eclipselink.ddl-generation" value="create-or-extend-tables"/>
    </properties>
  </persistence-unit>
</persistence>
```

The `<provider>` line is the switch that picks EclipseLink. Vendor-specific knobs use the `eclipselink.*` prefix, mirroring how Hibernate uses `hibernate.*`.

### Q3. [Theory] What is the difference between the persistence context and the EclipseLink cache?
The **persistence context** (the `EntityManager`'s set of managed entities, also called the L1 cache) is per-transaction and per-`EntityManager` — it guarantees identity within one unit of work and is discarded when the EM closes. The **EclipseLink shared cache** (L2 cache) lives on the `EntityManagerFactory` and is shared across all EntityManagers/transactions in that JVM. A key distinction from Hibernate: in EclipseLink the **L2 shared cache is ON by default**, whereas in Hibernate the second-level cache is OFF until you configure a provider. This default surprises many newcomers when they update rows directly in the database and EclipseLink keeps serving stale objects from the shared cache.

### Q4. [Practical] How do you map a simple entity and read it back with EclipseLink?
The entity uses standard JPA annotations — there is nothing EclipseLink-specific required for a basic mapping, which is the whole point of coding to the spec.

```java
@Entity
@Table(name = "orders")
public class Order {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String customer;
    private BigDecimal total;
    // getters / setters
}

// Usage
EntityManagerFactory emf = Persistence.createEntityManagerFactory("orders-pu");
EntityManager em = emf.createEntityManager();
em.getTransaction().begin();
Order o = new Order();
o.setCustomer("Acme");
o.setTotal(new BigDecimal("42.00"));
em.persist(o);
em.getTransaction().commit();

Order found = em.find(Order.class, o.getId()); // likely served from L1/L2 cache
em.close();
```

### Q5. [Theory] What is "weaving" and why does EclipseLink need it?
Weaving is bytecode instrumentation that EclipseLink applies to entity classes to enable features such as **lazy loading of one-to-one and basic attributes**, **change tracking** (so dirty detection is O(changed-fields) instead of comparing every field against a snapshot), and **fetch groups**. Without weaving, `@OneToOne(fetch = LAZY)` and `@Basic(fetch = LAZY)` cannot be truly lazy because the field is loaded directly, not through a proxy method. JPA collections (`@OneToMany`) are lazy without weaving because the collection itself is a wrapper, but scalar lazy loading needs the woven accessor methods. Weaving therefore improves both correctness of lazy semantics and runtime performance.

---

## 🟡 Intermediate (3–7 yrs)

### Q6. [Theory] Static vs dynamic weaving — what is the difference and when do you use each?
```
DYNAMIC WEAVING (runtime)
  classloader loads Order.class --> javaagent intercepts --> woven bytes in memory
  needs: -javaagent:eclipselink.jar  (or container-provided agent)

STATIC WEAVING (build/compile time)
  Order.class --> [weave-maven/ant task] --> Order.class (already woven on disk)
  needs: nothing extra at runtime
```
**Dynamic weaving** happens at class-load time via a Java agent (`-javaagent:.../eclipselink.jar`) or, inside a Jakarta EE container/Spring with `LoadTimeWeaver`, via the container's instrumentation hook. It is convenient for development but requires the agent to be configured and can interfere with other agents.

**Static weaving** runs the `eclipselink-staticweave` Maven/Ant task during the build, producing already-woven `.class` files. It is preferred for production and for environments where you cannot or do not want a runtime agent (e.g., some app servers, GraalVM native images, OSGi, or when startup time matters). The trade-off: an extra build step and the need to re-weave when entities change.

If weaving is unavailable, set `eclipselink.weaving=false` and EclipseLink falls back to non-woven behavior (scalar lazy becomes eager, change tracking uses snapshot comparison).

### Q7. [Theory] Describe EclipseLink's L2 cache architecture and the cache types (`@Cache` isolation).
EclipseLink's shared cache can be configured per-entity with `@Cache`. The most important knob is `isolation`:

```
SHARED   : one cache region per EMF, all sessions/transactions share entities (default)
ISOLATED : cache lives in the persistence context only; nothing is shared in L2
PROTECTED: shared, but entities holding relationships to isolated data are handled safely
```

Other `@Cache` attributes: `type` (FULL, WEAK, SOFT, SOFT_WEAK, HARD_WEAK, CACHE, NONE) controls the eviction/reference strategy and bounded size; `size` caps entries; `expiry`/`expiryTimeOfDay` for TTL; `coordinationType` for cluster cache coordination; `alwaysRefresh`, `refreshOnlyIfNewer`, and `disableHits` for staleness control.

```java
@Entity
@Cache(
    type = CacheType.SOFT,        // GC-evictable
    size = 5000,
    expiry = 60000,               // 60s TTL
    isolation = CacheIsolationType.SHARED,
    coordinationType = CacheCoordinationType.INVALIDATE_CHANGED_OBJECTS)
public class Product { /* ... */ }
```

Use **ISOLATED** for security-sensitive or per-tenant data that must never leak across sessions, and for highly volatile data where caching causes more staleness bugs than it saves round trips.

### Q8. [Practical] EclipseLink keeps serving stale data after an external update. How do you fix it?
This is the classic consequence of the L2 cache being on by default. The root cause is that some process (a batch job, a DBA, another app, or a native SQL `UPDATE`) changed rows without going through this EMF, so the shared cache is unaware.

Options, from surgical to broad:
- **Refresh a single entity**: `em.refresh(entity)` or query hint `QueryHints.REFRESH = true`.
- **Invalidate a cache region** programmatically:
```java
emf.getCache().evict(Product.class);          // JPA standard
JpaHelper.getEntityManagerFactory(emf)
         .getServerSession()
         .getIdentityMapAccessor()
         .invalidateClass(Product.class);      // EclipseLink native
```
- **Per-entity policy**: `@Cache(expiry = ...)` for TTL, or `refreshOnlyIfNewer=true` with an `@Version` column.
- **Disable selectively**: `@Cache(isolation = CacheIsolationType.ISOLATED)` or `@Cacheable(false)` on entities that change out-of-band.
- **Cluster invalidation**: set `coordinationType` (JMS/RMI) so nodes invalidate each other.

In production I usually keep the shared cache for read-mostly reference data, set ISOLATED on volatile transactional tables, and add an `@Version` column so optimistic locking catches anything the cache misses.

### Q9. [Practical] How do you tune fetching to avoid N+1 and over-fetching in EclipseLink?
EclipseLink offers several mechanisms beyond plain `JOIN FETCH`:

- **Batch fetching** — issue one extra query that loads related objects for the whole result set instead of one query per row:
```java
query.setHint(QueryHints.BATCH, "o.lineItems");
query.setHint(QueryHints.BATCH_TYPE, BatchFetchType.IN); // JOIN | EXISTS | IN
```
`IN` batching (`WHERE parent_id IN (?, ?, ...)`) is often the most index-friendly. You can also annotate: `@BatchFetch(BatchFetchType.IN)`.
- **Join fetching** — single SQL with a join: `query.setHint(QueryHints.LEFT_FETCH, "o.lineItems")` or `@JoinFetch`. Best for to-one; can cause cartesian blow-up on multiple to-many.
- **Fetch groups** — load only a subset of an entity's columns (partial entities), great for wide tables:
```java
query.setHint(QueryHints.FETCH_GROUP_ATTRIBUTE, "id");
query.setHint(QueryHints.FETCH_GROUP_ATTRIBUTE, "total");
```
- **Read-only queries** — `QueryHints.READ_ONLY = true` returns shared-cache instances without registering them for change tracking, cutting memory and CPU.

Rule of thumb: `JOIN FETCH` for one-to-one and small one-to-many; `BATCH IN` for large collections across many parents; fetch groups for reporting on wide tables.

### Q10. [Theory] What is MOXy and where does it fit?
MOXy is EclipseLink's implementation of **JAXB** (object↔XML) plus a JSON binding layer. It lets one set of POJOs serialize to both XML and JSON, and crucially it can bind using the **same JPA mapping metadata** or an external `bindings.xml` so you do not have to pollute domain classes with annotations. Distinctive MOXy features: external binding files (decoupling mapping from code), XPath-based mapping (`@XmlPath("address/city/text()")`), bidirectional relationship handling with `@XmlInverseReference` (which plain JAXB cannot do, since JPA bidirectional graphs create cycles), and dynamic JAXB (binding without compiled classes). In a JAX-RS app you select MOXy as the message body provider; it was a common default in older Jersey/GlassFish stacks.

### Q11. [Coding] Write a generic JPA DAO that uses EclipseLink read-only and batch hints.
**Problem:** Build a reusable read method that returns a page of parents with their children efficiently and without polluting the shared cache.

```java
public class ReadOnlyDao {
    private final EntityManager em;
    public ReadOnlyDao(EntityManager em) { this.em = em; }

    public List<Order> findRecentWithItems(int limit) {
        TypedQuery<Order> q = em.createQuery(
            "SELECT o FROM Order o ORDER BY o.id DESC", Order.class);
        q.setMaxResults(limit);
        // Avoid N+1: one batched IN query loads all lineItems for the page.
        q.setHint("eclipselink.batch", "o.lineItems");
        q.setHint("eclipselink.batch.type", "IN");
        // Don't register results for change tracking; serve straight from cache.
        q.setHint("eclipselink.read-only", true);
        return q.getResultList();
    }
}
```
**Why it works:** `batch=o.lineItems` turns N child queries into 1; `read-only=true` skips the per-entity clone/snapshot that the unit of work would otherwise create.
**Time:** O(1) parent query + O(1) batched child query = 2 SQL statements regardless of page size, vs O(N+1) naively. **Space:** read-only avoids holding a registered copy per row.
**Edge cases:** read-only entities must NOT be mutated (changes won't persist and may corrupt shared-cache state); if `limit <= 0`, guard before calling `setMaxResults`; an empty result still issues only the parent query (no batch query fires).

### Q12. [Practical] How do you enable second-level caching in Hibernate vs EclipseLink, and why does the default matter?
In **Hibernate** you must add a provider (e.g., JCache/Infinispan/Ehcache), set `hibernate.cache.use_second_level_cache=true`, and annotate entities `@Cacheable`/`@Cache` (region usage strategy). Nothing is cached until you opt in. In **EclipseLink** the shared cache is enabled by default, so you must instead think about *opting out* (`shared-cache-mode=NONE`, `@Cacheable(false)`, or `@Cache(isolation=ISOLATED)`). This default flips the failure mode: Hibernate teams hit "cache isn't working" surprises, EclipseLink teams hit "stale data" surprises. The interview point is to show you understand that the default is a design philosophy difference, not just a config flag, and that you adjust your verification strategy accordingly (EclipseLink: test what happens under external updates; Hibernate: test that caching actually engages).

---

## 🟠 Advanced (8–12 yrs)

### Q13. [Theory] Compare EclipseLink and Hibernate across caching, weaving, dirty tracking, and query language.
```
                | EclipseLink                  | Hibernate ORM
----------------+------------------------------+------------------------------
L2 cache        | ON by default (built-in)     | OFF by default (pluggable)
Cache topology  | SHARED/ISOLATED/PROTECTED    | region strategies + JCache
Weaving         | static OR dynamic agent      | bytecode enhancement plugin
Lazy scalars    | needs weaving                | needs enhancement
Dirty detection | change tracking via weaving  | snapshot diff (or enhancement)
Vendor hints    | eclipselink.* (BATCH, etc.)  | org.hibernate.* / @QueryHints
Native query DSL| JPQL + EclipseLink Expr API  | JPQL + HQL + Criteria
XML/JSON binding| MOXy (JAXB) bundled          | none (separate libs)
NoSQL/EIS       | yes (built-in)               | Hibernate OGM (separate)
Connection pool | internal pool or external    | typically external (HikariCP)
```
The headline differences interviewers probe: **(1)** EclipseLink's cache is on by default and offers explicit isolation levels; **(2)** EclipseLink ships MOXy and NoSQL in-tree; **(3)** EclipseLink exposes a powerful native `Expression`/`ReadAllQuery` API and query hints (`BATCH`, `FETCH_GROUP`, `LEFT_FETCH`) that have no direct JPQL equivalent.

### Q14. [Practical] Walk through migrating a service from Hibernate to EclipseLink (or vice versa). What breaks?
**Scenario:** A Spring Boot 3 service on Hibernate must move to EclipseLink to standardize with a TopLink-heritage org.

**Approach:**
1. **Swap the provider** in `persistence.xml`/Spring config; replace `HibernateJpaVendorAdapter` with `EclipseLinkJpaVendorAdapter`. Add weaving (`LoadTimeWeaver` for dynamic, or static-weave Maven plugin).
2. **Audit vendor-specific code**: any `org.hibernate.*` import — `@Type`, `@Formula`, `@Where`, `@Filter`, `Session.createSQLQuery`, Hibernate `Criteria` — has no 1:1 EclipseLink equivalent and must be rewritten (often with `@Convert`, `DescriptorCustomizer`, or `Expression` API).
3. **ID generation**: default `GenerationType.AUTO` may pick a different strategy/sequence/table; verify and pin `SEQUENCE`/`IDENTITY` explicitly to avoid silently different SQL.
4. **Caching behavior flips**: Hibernate L2 was likely off; EclipseLink turns it on. Decide isolation per entity *before* go-live or you will ship stale-read bugs.
5. **DDL & dialect**: `eclipselink.ddl-generation` vs `hibernate.hbm2ddl.auto`; check column types, `@Lob`, boolean mapping, and reserved-word quoting differ.
6. **Behavioral differences**: flush ordering, cascade nuances, and how empty embeddables/`null` are treated can differ. Cover with integration tests against a real DB.

**Trade-offs:** the rewrite cost is concentrated in vendor-specific extensions and tests; the JPA-pure portion ports cleanly. **What I'd do:** wrap the provider behind the JPA interfaces only, run the full integration suite against a containerized DB (Testcontainers) on both providers, and migrate one bounded context at a time rather than big-bang.

### Q15. [Theory] How does EclipseLink cache coordination work in a cluster, and what are the failure modes?
In a multi-node deployment each JVM has its own L2 cache, so a write on node A leaves node B stale. EclipseLink **cache coordination** propagates change notifications between nodes over JMS or RMI. `@Cache(coordinationType=...)` chooses the protocol:
```
SEND_OBJECT_CHANGES        : push changed object state to peers (most chatty)
INVALIDATE_CHANGED_OBJECTS : tell peers to evict that key (peers re-read on demand)
SEND_NEW_OBJECTS_WITH_CHANGES
NONE                       : no coordination (default)
```
`INVALIDATE_CHANGED_OBJECTS` is usually safest: it avoids serializing/shipping object graphs and avoids ordering anomalies, at the cost of an extra read on the peer. **Failure modes:** message loss or broker downtime silently reintroduces staleness; large object graphs with `SEND_OBJECT_CHANGES` create network and serialization pressure; clock/ordering issues can cause an older state to overwrite newer. Many teams skip coordination entirely and instead use TTL expiry + `@Version` optimistic locking, accepting bounded staleness in exchange for operational simplicity — coordination adds a distributed-systems dependency (a broker) to what was a single-node concern.

### Q16. [Coding] Use the EclipseLink native Expression API to build a dynamic query, and contrast with Criteria.
**Problem:** Build a filter on `Order` where customer matches and total ≥ a threshold, when filters are optional at runtime.

```java
// EclipseLink native Expression API
ExpressionBuilder eb = new ExpressionBuilder(Order.class);
Expression where = eb.get("total").greaterThanEqual(100);
if (customer != null) {
    where = where.and(eb.get("customer").equalsIgnoreCase(customer));
}
ReadAllQuery raq = new ReadAllQuery(Order.class, where);
raq.addBatchReadAttribute(eb.get("lineItems"));          // batch fetch
List<Order> result = (List<Order>) JpaHelper.getEntityManager(em)
        .getActiveSession().executeQuery(raq);
```

Equivalent in **JPA Criteria** (portable across providers):
```java
CriteriaBuilder cb = em.getCriteriaBuilder();
CriteriaQuery<Order> cq = cb.createQuery(Order.class);
Root<Order> r = cq.from(Order.class);
List<Predicate> ps = new ArrayList<>();
ps.add(cb.ge(r.get("total"), 100));
if (customer != null) ps.add(cb.equal(cb.lower(r.get("customer")), customer.toLowerCase()));
cq.where(ps.toArray(new Predicate[0]));
List<Order> result2 = em.createQuery(cq).getResultList();
```
**Trade-off:** the Expression API exposes EclipseLink-only power (batch attributes on the query object, hierarchical/history queries, function expressions) but **locks you to EclipseLink**. Criteria is portable but cannot express batch-read attributes. **Time/Space:** both compile to one SQL statement; the Expression version additionally batches `lineItems` so the to-many fetch is O(1) extra query instead of O(N). **Edge cases:** null filters must be conditionally appended (never build `field = null` as equality — use `isNull()`); for very large `IN` lists EclipseLink can split into chunks (`maxBatchWritingSize`/parameter limits) — verify against your DB's bind-variable cap.

### Q17. [Practical] When do you actually pick EclipseLink over Hibernate in 2026?
Honest answer: Hibernate is the more common default in the Spring ecosystem (Spring Boot's default JPA provider), so EclipseLink is a *deliberate* choice for specific reasons:
- **Legacy/heritage**: the org came from Oracle TopLink; migration effort and existing `eclipselink.*` expertise favor staying.
- **Oracle stack alignment**: WebLogic ships EclipseLink as its JPA provider; staying on the RI reduces support friction.
- **MOXy needs**: you want unified XML+JSON binding with external bindings and `@XmlInverseReference` for bidirectional graphs, without dragging in extra libraries.
- **Built-in cache richness**: explicit `SHARED/ISOLATED/PROTECTED` isolation and built-in coordination without bolting on a cache provider.
- **NoSQL/EIS in one framework** for heterogeneous stores.
- **Spec purity / TCK alignment**: when you specifically want the reference behavior.

Conversely I pick Hibernate when I want the largest community, the richest ecosystem integrations, Spring Data JPA's smoothest path, and features like `@Filter`/`@SoftDelete`/envers. The decision is rarely about raw performance (both are fast and tunable) and mostly about ecosystem gravity and existing investment.

### Q18. [Theory] What is multitenancy support in EclipseLink and how does it interact with the cache?
EclipseLink has first-class `@Multitenant` support with three strategies: **SINGLE_TABLE** (a discriminator column like `tenant_id` filters rows), **TABLE_PER_TENANT** (each tenant gets its own table/schema), and **VPD** (Oracle Virtual Private Database). The critical caching consequence: with `SINGLE_TABLE` you must **not** keep tenant-scoped entities in the SHARED cache, or one tenant could see another's cached objects — a data-leak security bug. EclipseLink lets you set the entity cache to ISOLATED for multitenant entities (and `@Multitenant(includeCriteria=true)` ensures the tenant predicate is appended automatically). You bind the tenant per `EntityManager` via the `eclipselink.tenant-id` property. The security point worth stating in an interview: multitenancy + an on-by-default shared cache is exactly the combination where an unreviewed configuration silently cross-contaminates tenants.

---

## 🔴 Expert (15+ yrs)

### Q19. [Theory] How does EclipseLink's UnitOfWork compute and order writes, and how does that affect deadlocks?
EclipseLink's `UnitOfWork` registers a **clone** of each managed object and, at commit, compares clones against the shared-cache "backup" (or uses change-tracking from weaving) to compute a minimal **change set**. It then builds a **commit order** by topologically sorting based on foreign-key constraints and mapping dependencies, so inserts happen parents-before-children and deletes children-before-parents. This deterministic ordering reduces (but does not eliminate) database deadlocks compared to arbitrary flush order. Two expert-level levers: (1) **batch writing** (`eclipselink.jdbc.batch-writing=JDBC` with `batch-writing.size`) groups DML into JDBC batches, dramatically cutting round trips for bulk operations; (2) **ordering interactions** — if two transactions touch the same set of parents/children but acquire row locks in different orders due to application logic, you still deadlock, so consistent access order and short transactions still matter. Knowing that EclipseLink clones objects (cost) versus Hibernate's snapshot/proxy approach is the kind of detail that distinguishes deep familiarity.

### Q20. [Practical] A latency-sensitive service shows GC pressure and long pauses traced to EclipseLink. How do you diagnose and fix it?
**Diagnosis path:**
1. **Heap analysis** (heap dump + MAT): if the dominator tree is full of cached entities, the FULL/HARD `@Cache(type)` is retaining too much. Switch hot, large entities to `SOFT` or `WEAK` and bound `size`, or `ISOLATED`/`NONE` for volatile ones.
2. **UnitOfWork churn**: large read-then-discard queries clone every row. Apply `READ_ONLY=true` so results bypass UoW registration; use **fetch groups** to load only needed columns on wide tables.
3. **N+1 / over-fetch** (SQL logging `eclipselink.logging.level=FINE`, `logging.parameters=true`): replace per-row queries with `BATCH IN`; replace cartesian `JOIN FETCH` of multiple collections with batch fetching.
4. **Write batching**: enable JDBC batch writing for bulk inserts/updates to cut allocation and round trips.
5. **Connection pool**: EclipseLink's internal pool may be undersized — externalize to HikariCP and size to the DB.

**Trade-offs:** SOFT/WEAK caches reduce retention but increase cache misses and reloads; read-only entities cannot be mutated; fetch groups risk `LazyInitializationException`-style surprises if you later touch unloaded fields. **What I'd do in production:** baseline with FINE logging in staging, fix the dominant allocation source first (usually cache type + read-only), then re-measure before touching anything else — one change at a time so the cause of any regression is unambiguous.

### Q21. [Theory] How do you run EclipseLink with GraalVM native image or in a serverless/cold-start context?
Dynamic weaving relies on a runtime Java agent and runtime classloading, which conflicts with **GraalVM native image's** closed-world, ahead-of-time model. The strategy is **static weaving at build time** (so no agent is needed) plus reachability/reflection configuration so the native compiler retains the metadata EclipseLink reads via reflection (entity classes, descriptors, converters). Avoid features that require runtime class generation. For **serverless/cold start**, the `EntityManagerFactory` bootstrap (parsing metadata, building descriptors, weaving) is the expensive step, so you favor static weaving, a precomputed metadata/`SessionCustomizer`, deferred connection acquisition, and keeping the EMF warm across invocations rather than rebuilding it per request. The general principle: anything EclipseLink normally does lazily at runtime should be pushed to build time in AOT/cold-start environments.

### Q22. [Practical] Design a cache-and-consistency strategy for a read-heavy product catalog on EclipseLink across a cluster.
**Scenario:** 10:1 read/write, multi-node, occasional out-of-band price updates from a pricing service.

**Design:**
```
Reference data (Category, Brand)  -> @Cache(type=SOFT, size=N, expiry=600000)  SHARED
Volatile data (Price, Inventory)  -> @Cache(isolation=ISOLATED) or @Cacheable(false)
                                      + @Version optimistic lock
Cross-node freshness              -> coordinationType=INVALIDATE_CHANGED_OBJECTS (JMS)
Out-of-band updates               -> pricing service publishes evict event ->
                                      each node emf.getCache().evict(Price.class, id)
Read path                         -> READ_ONLY hint + BATCH IN for related lookups
```
**Trade-offs:** caching reference data gives the biggest hit-rate win because it changes rarely; isolating/uncaching volatile price data trades cache hits for correctness; TTL bounds worst-case staleness even if a JMS message is lost. **What I'd actually ship:** TTL + `@Version` as the safety net (works even when coordination fails), coordination as an optimization, and an explicit evict hook the pricing service can call. I would load-test with realistic update bursts and measure hit ratio and stale-read incidents, not just throughput.

### Q23. [Behavioral] Tell me about a time you championed an unpopular framework/migration decision. How did you handle the disagreement?
Use **STAR**. *Situation:* the team's default was Hibernate, but our flagship product ran on WebLogic and a TopLink/EclipseLink heritage, and a proposed "modernization" wanted a costly rewrite to Hibernate. *Task:* I had to evaluate objectively rather than follow ecosystem fashion. *Action:* I built a small proof-of-concept on both, benchmarked the actual workloads, and crucially **listed the vendor-specific code that would have to be rewritten** and the WebLogic support implications — turning a religious debate into a cost/risk table. I invited the loudest skeptic to co-author the comparison so it wasn't "my" doc. *Result:* we stayed on EclipseLink for the WebLogic services and adopted Hibernate for greenfield Spring Boot services, a "right tool per context" outcome. The lesson I emphasize: frame framework debates as **evidence + reversibility + total cost**, keep dissenters inside the analysis, and decide per bounded context rather than mandating one winner. Senior interviewers look for data-driven persuasion and the humility to let the data, not your preference, decide.

### Q24. [Theory] What are EclipseLink `DescriptorCustomizer`, `SessionCustomizer`, and `@Converter`, and when do you reach for them?
These are the extension points for behavior the annotations can't express. A **`SessionCustomizer`** runs once at EMF startup and lets you tweak the global session — register custom SQL functions, set connection pools, install query redirectors, or add named queries programmatically. A **`DescriptorCustomizer`** (wired via `@Customizer`) modifies a single entity's `ClassDescriptor` — change a mapping's fetch type, add a history policy, attach a `CacheInvalidationPolicy`, or set `additionalJoinExpression` for soft-delete-style filtering (EclipseLink's analog to Hibernate `@Where`). **`@Converter`/`@Convert`** (and `AttributeConverter` from JPA) handle column↔attribute transformation (e.g., encrypting a field, mapping an enum to a code). The expert judgment is *when not to*: customizers couple you tightly to EclipseLink internals and run early in bootstrap where errors are opaque, so reach for `AttributeConverter` (portable) first, then `@Customizer` only for genuinely provider-specific needs like history queries or query redirection.

---

## 🧩 Extended Questions — Set 1: Deep Theory & Internals

### 🟢 Basic — extended

#### Q25. [Theory] What is the relationship between Oracle TopLink and EclipseLink, and how do their version numbers line up with JPA/Jakarta Persistence releases?
EclipseLink is the open-source continuation of **Oracle TopLink**, one of the oldest commercial Java ORMs (it predates JPA, going back to the late 1990s as an object-to-relational tool for Smalltalk and then Java). In 2007 Oracle contributed the TopLink codebase to the Eclipse Foundation to seed the JPA 1.0 reference implementation; the donated core became **EclipseLink**, while Oracle continued to ship a commercial **TopLink** product that is essentially EclipseLink plus extra Oracle-specific features and support. So the lineage is: TopLink (proprietary) → donation → EclipseLink (open source) → TopLink (re-bundled EclipseLink + extras). Understanding this matters in interviews because a lot of "TopLink" documentation, `oracle.toplink.*` package references, and Stack Overflow answers are really about the same engine you get in EclipseLink.

The version timeline is worth memorizing at a high level because interviewers use it to test whether you actually shipped on it versus read a blog:

```
EclipseLink 1.x   -> JPA 1.0 (JSR 220) reference implementation
EclipseLink 2.0   -> JPA 2.0 (JSR 317)
EclipseLink 2.5   -> JPA 2.1 (JSR 338)  -- adds @Converter, stored procedures, entity graphs
EclipseLink 2.7   -> JPA 2.2 (Java EE 8) -- last javax.persistence release
EclipseLink 3.0   -> Jakarta Persistence 3.0 -- the big rename javax.* -> jakarta.*
EclipseLink 4.0   -> Jakarta Persistence 3.1 -- numeric/datetime functions, UUID generation
```

The single most disruptive boundary is **EclipseLink 2.7 → 3.0**: that is where the namespace flipped from `javax.persistence` to `jakarta.persistence` as part of the Java EE → Jakarta EE transfer (Oracle would not allow the `javax` namespace to evolve). It is not a behavioral upgrade — it is a package rename — but it is a hard, all-or-nothing migration: you cannot mix a `javax.persistence`-compiled entity with a `jakarta.persistence` provider in the same persistence unit. That is the kind of fact a senior interviewer expects you to call out when discussing upgrades.

#### Q26. [Theory] What is an "identity map" in EclipseLink, and why does it guarantee object identity within a unit of work?
The **identity map** is the data structure that backs both the persistence context (L1) and the shared cache (L2). It is essentially a `Map<CacheKey, Object>` keyed by the entity's class plus primary key, and its job is to enforce the JPA contract that **within a single persistence context, `em.find(X, id)` called twice returns the same Java object instance** (`==`, not just `.equals()`). When the `UnitOfWork` reads a row, it first checks the identity map; if an object for that key already exists it returns the existing instance rather than constructing a duplicate. This is what makes graph navigation consistent — if two queries both reach the same `Customer`, you get one `Customer` object, so a change made through one reference is visible through the other.

EclipseLink exposes the identity map type per entity via `@Cache(type=...)`, and the choice is really a choice of map implementation and reference strength:

```
FULL       : permanent strong refs, never evicts (good for small static reference data)
WEAK       : weak refs; entries collectible once unreferenced (GC-friendly, default-ish)
SOFT       : soft refs; survive until memory pressure (cache-y, more retention than WEAK)
SOFT_WEAK  : fixed-size sub-cache of SOFT refs + WEAK overflow (bounded hot set)
HARD_WEAK  : like SOFT_WEAK but hard refs for the hot set (won't yield under GC)
CACHE      : legacy fixed-size LRU
NONE       : no identity map / no L2 caching for this entity
```

The subtle internals point: the identity map is also what makes EclipseLink's change tracking and merge work, because the "backup" copy used to compute the change set is held alongside the cached instance. When people say "the L2 cache is on by default," what is literally true is that the **shared identity map per entity defaults to a real map (WEAK/SOFT family) rather than NONE**, so reads populate it. Setting `@Cacheable(false)` or `shared-cache-mode=NONE` is what swaps the shared map to a non-retaining mode.

#### Q27. [Theory] What does `shared-cache-mode` in persistence.xml control, and what are its legal values?
`shared-cache-mode` is the **JPA-standard** (not EclipseLink-specific) element that governs how the `@Cacheable` annotation interacts with the L2 shared cache. It is the portable way to flip caching policy without using vendor properties, and it takes one of five enum values from `jakarta.persistence.SharedCacheMode`:

```
ALL                   : cache every entity, ignore @Cacheable annotations
NONE                  : disable L2 for everything, ignore @Cacheable
ENABLE_SELECTIVE      : cache ONLY entities marked @Cacheable(true)  (opt-in)
DISABLE_SELECTIVE     : cache everything EXCEPT entities marked @Cacheable(false)  (opt-out)
UNSPECIFIED           : provider default (EclipseLink treats this like caching enabled)
```

```xml
<persistence-unit name="orders-pu">
  <shared-cache-mode>DISABLE_SELECTIVE</shared-cache-mode>
  ...
</persistence-unit>
```

The reason this matters for EclipseLink specifically is the default behavior. Because EclipseLink leans toward caching being on, `UNSPECIFIED` behaves like caching is broadly enabled, which is exactly the "stale read" trap from the existing questions. If you want EclipseLink to behave more like Hibernate's opt-in model, set `ENABLE_SELECTIVE` and annotate only the read-mostly reference entities with `@Cacheable(true)`. The interview-worthy nuance is that `shared-cache-mode` is the **spec-portable** lever, while `@Cache(isolation=...)` and `eclipselink.cache.shared.<Entity>=false` are the **EclipseLink-specific** finer-grained levers — reach for the portable one first when you can.

#### Q28. [Theory] What is the difference between `RESOURCE_LOCAL` and `JTA` transaction types, and how does that change how EclipseLink obtains connections?
The `transaction-type` attribute on `<persistence-unit>` declares who owns transaction boundaries. With **`RESOURCE_LOCAL`** the application drives transactions directly through `EntityManager.getTransaction().begin()/commit()`, and EclipseLink manages its own JDBC connection from a data source you configure (often its internal pool or a `jakarta.persistence.jdbc.url`). With **`JTA`** the transaction is owned by a container-managed **JTA transaction manager**; EclipseLink enlists its connection as an XA/managed resource and you must call `em.joinTransaction()` (or rely on the container to inject a transaction-scoped EM) rather than `getTransaction()`. Calling `getTransaction()` on a JTA-type EM throws `IllegalStateException`, which is a classic gotcha.

```xml
<!-- Standalone / Spring with a local DataSource -->
<persistence-unit name="local-pu" transaction-type="RESOURCE_LOCAL">
  <non-jta-data-source>java:comp/env/jdbc/localDS</non-jta-data-source>
</persistence-unit>

<!-- App server with container transactions -->
<persistence-unit name="jta-pu" transaction-type="JTA">
  <jta-data-source>java:/jdbc/ordersXADS</jta-data-source>
</persistence-unit>
```

The internals consequence is connection acquisition and pooling. Under `RESOURCE_LOCAL` EclipseLink controls when it checks a connection out of the pool — and by default it does **lazy/deferred connection acquisition**, meaning a read-only transaction may grab a connection only when the first SQL is issued, then return it, reducing pool pressure. Under `JTA`, the connection lifecycle is bound to the global transaction and the data source must be XA-capable if more than one resource participates, which costs the two-phase-commit overhead. The decision rule: use `RESOURCE_LOCAL` for standalone/Spring single-datasource apps, and `JTA` when you genuinely span multiple transactional resources (multiple databases, DB + JMS) and need atomicity across them.

### 🟡 Intermediate — extended

#### Q29. [Theory] How does EclipseLink's sequence preallocation work, and why does `allocationSize` cause "gaps" in IDs?
When you use `GenerationType.SEQUENCE` (or EclipseLink's default `TABLE`/sequence object behavior), EclipseLink does **preallocation**: instead of asking the database for one number per insert, it reserves a *block* of IDs in a single round trip and hands them out from memory until the block is exhausted. The block size is `allocationSize` (default **50** for `@SequenceGenerator`). So a `nextval` that returns 50 actually licenses EclipseLink to use 1–50 (the database increment must match the allocation size), and the next `nextval` returning 100 licenses 51–100. This is a major throughput optimization: bulk inserts of 1000 rows cost ~20 sequence round trips instead of 1000.

```java
@Id
@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "order_seq")
@SequenceGenerator(name = "order_seq", sequenceName = "ORDER_SEQ", allocationSize = 50)
private Long id;
```

The "gaps" everyone notices come from this design and are *by design, not a bug*. If a JVM allocates a block of 50, uses 7 IDs, and then restarts (crash, redeploy, scale-down), the remaining 43 are lost forever — the next JVM grabs a fresh block starting at the next `nextval`. Likewise rolled-back transactions consume IDs that are never reused. The internals takeaway for interviews: **sequence/identity values are not guaranteed contiguous and must never be used to count rows, infer insertion order across nodes, or fill a gapless ledger**. Two further nuances: `allocationSize` in the annotation must equal the database sequence's `INCREMENT BY`, or you will get duplicate-key collisions; and `IDENTITY` generation cannot preallocate at all (the DB assigns on insert), so it forces a row-by-row round trip and **disables JDBC batch writing for inserts**, which is a real performance reason to prefer `SEQUENCE` over `IDENTITY` on databases that support sequences.

#### Q30. [Theory] Walk through how EclipseLink translates a JPQL query into SQL. What stages does the query go through?
EclipseLink does not interpret JPQL row-by-row; it **compiles** it once into an internal query object and a parameterized SQL string, then caches that translation. The pipeline has roughly these stages:

```
JPQL string
   |  (1) ANTLR-based parser  -> abstract syntax tree
   v
AST
   |  (2) semantic analysis against the metadata model (descriptors/mappings)
   v
Expression tree (EclipseLink Expression API objects)
   |  (3) query object built: ReadAllQuery / ReportQuery / UpdateAllQuery ...
   v
DatabaseQuery
   |  (4) SQL generation via the platform/DatabasePlatform (dialect)
   v
Parameterized SQL  ->  prepared statement (cached by the statement cache)
```

The key insight is the **metadata model** in stage 2: EclipseLink resolves `o.customer.address.city` against the `ClassDescriptor`s and their `DatabaseMapping`s, deciding which tables to join, which join columns to use, and how inheritance/secondary tables expand. JPQL is therefore *object-level* and the descriptor model is what bridges it to *relational* SQL — the same query produces different SQL on Oracle vs PostgreSQL because stage 4 delegates to the `DatabasePlatform` (function names, pagination syntax, identifier quoting, `LIMIT` vs `ROWNUM` vs `FETCH FIRST`).

Two practical consequences interviewers like to hear. First, because the parse-and-build step is non-trivial, **named queries** (`@NamedQuery`) are compiled and validated at EMF bootstrap and reused, whereas a dynamically-built JPQL string is parsed on first use and then cached — so named queries fail fast and avoid first-call latency. Second, the translation goes through the Expression API internally, which is why the native `Expression`/`ReadAllQuery` API (covered earlier) gives you access to capabilities that simply have no JPQL surface syntax — you are dropping into the layer JPQL itself compiles down to.

#### Q31. [Theory] Compare optimistic and pessimistic locking in EclipseLink, including the JPA `LockModeType` values and what SQL each produces.
Optimistic locking assumes conflicts are rare and detects them at flush/commit time using a **`@Version`** column; pessimistic locking assumes conflicts are likely and takes database row locks up front via `SELECT ... FOR UPDATE`. EclipseLink implements the full set of JPA `LockModeType` values, and a strong answer maps each to its mechanism:

```
LockModeType            mechanism                              typical SQL effect
----------------------  -------------------------------------  -----------------------------
OPTIMISTIC (READ)       read-lock: check version at commit     UPDATE ... WHERE version = ?
OPTIMISTIC_FORCE_INCR.  bump version even on read-only access  UPDATE ... SET version=version+1
PESSIMISTIC_READ        shared row lock                        SELECT ... FOR SHARE / LOCK IN SHARE
PESSIMISTIC_WRITE       exclusive row lock                     SELECT ... FOR UPDATE
PESSIMISTIC_FORCE_INCR. exclusive lock + version increment     SELECT ... FOR UPDATE (+version bump)
NONE                    no locking                             plain SELECT/UPDATE
```

The **optimistic** path is the default recommendation: add `@Version private long version;` and on every update EclipseLink appends `WHERE id=? AND version=?` and checks the affected-row count. If zero rows updated, someone else changed the row first and EclipseLink throws `OptimisticLockException`, which you translate into a retry or a "someone else edited this" UI message. This costs almost nothing on the happy path and scales because it holds no locks across user think-time. The famous failure mode is when **another process updates the row without bumping the version column** (a raw SQL `UPDATE`), which defeats optimistic locking entirely — so a `@Version` column is a contract that *everyone* who writes must honor.

The **pessimistic** path is for short, hot, contended operations (e.g., decrementing inventory) where you cannot tolerate a retry storm. You request it via `em.find(Account.class, id, LockModeType.PESSIMISTIC_WRITE)` or `query.setLockMode(...)`, and EclipseLink emits `FOR UPDATE`, blocking other writers until your transaction ends. The trade-offs are classic: pessimistic locks serialize access (throughput hit, deadlock risk, must keep transactions short) but eliminate lost-update retries; EclipseLink lets you tune the wait via `eclipselink.pessimistic-lock.timeout`. The senior nuance is that `PESSIMISTIC_WRITE` interacts with the **shared cache**: EclipseLink will refresh the locked object from the database so you do not lock a row and then operate on stale cached state.

#### Q32. [Theory] What is the difference between EclipseLink's object cache and a query result cache, and why is caching query results harder?
EclipseLink's primary L2 cache is an **object cache** (the shared identity map): it stores fully-built entity instances keyed by primary key. A separate, opt-in mechanism is the **query result cache**, which caches the *result list of a specific query* (the ordered set of IDs/objects returned for a given JPQL + parameter combination), enabled with `QueryHints.QUERY_RESULTS_CACHE`:

```java
TypedQuery<Country> q = em.createQuery("SELECT c FROM Country c", Country.class);
q.setHint(QueryHints.QUERY_RESULTS_CACHE, true);
q.setHint(QueryHints.QUERY_RESULTS_CACHE_SIZE, 100);   // distinct param sets to keep
q.setHint(QueryHints.QUERY_RESULTS_CACHE_EXPIRY, 600000); // 10 min TTL
```

The reason the query cache is harder and more dangerous than the object cache comes down to **invalidation**. An object cache entry is keyed by primary key, so when row 42 changes, EclipseLink knows exactly which entry to invalidate. A *query result* cache entry is keyed by query text + bind parameters, and the set it stores depends on the *contents* of a whole table — `SELECT c FROM Customer c WHERE c.country = 'US'` can change because any customer was inserted, deleted, or had their country edited. EclipseLink cannot cheaply know which cached query results a given write invalidated, so it relies on TTL expiry and on invalidating result caches when the underlying class changes. This is exactly why the query result cache is **off by default and recommended only for queries over near-static reference data** (countries, currencies, category trees) — caching the results of a query over volatile transactional data trades correctness for a small latency win and almost always backfires.

#### Q33. [Theory] How do EclipseLink flush modes (`AUTO` vs `COMMIT`) work, and how does that affect query correctness?
`FlushModeType` controls when pending in-memory changes in the persistence context are written (flushed) to the database relative to query execution. With **`AUTO`** (the JPA default), EclipseLink flushes dirty changes to the database *before* executing a query whose result could be affected by those changes, so the query sees your own un-committed writes — this preserves "read-your-writes" consistency within the transaction. With **`COMMIT`**, EclipseLink only flushes at transaction commit, so a query run mid-transaction may *not* reflect entities you just modified in memory, which is faster (fewer flushes) but can return stale results relative to your own pending work.

```java
em.setFlushMode(FlushModeType.COMMIT);     // EM-wide
query.setFlushMode(FlushModeType.AUTO);    // override for one query
```

The correctness subtlety EclipseLink interviewers probe: even under `AUTO`, the flush-before-query is scoped — EclipseLink tries to flush changes *relevant* to the query rather than the entire context, but the safe mental model is "AUTO = my pending changes are visible to my queries; COMMIT = they are not until commit." A common real bug is setting `COMMIT` for performance, then writing a service method that updates an entity and immediately runs a JPQL aggregate expecting to see the change, and getting the pre-update value. The deeper point is that **JPQL/SQL queries bypass the persistence context** — they hit the database — so without the auto-flush they would never see in-flight changes; `AUTO` exists precisely to reconcile the in-memory unit of work with database-level queries. Use `COMMIT` only when you have measured flush overhead and you are certain no query in the transaction depends on un-flushed state.

#### Q34. [Theory] Explain the cascade types and `orphanRemoval` semantics, and how `orphanRemoval=true` differs from `CascadeType.REMOVE`.
Cascade types declare which `EntityManager` operations propagate from a parent entity across a relationship to its children: `PERSIST`, `MERGE`, `REMOVE`, `REFRESH`, `DETACH`, and the shorthand `ALL`. They are evaluated when the corresponding lifecycle operation runs on the parent — e.g., `CascadeType.PERSIST` on `Order.lineItems` means `em.persist(order)` also persists each line item, so you do not have to persist children individually. EclipseLink implements these per the JPA spec, and the practical guidance is to be deliberate: `CascadeType.ALL` on every relationship is a frequent source of accidental mass-deletes and over-eager merges.

`orphanRemoval=true` is a *different and stronger* concept that people constantly conflate with `CascadeType.REMOVE`:

```java
@OneToMany(mappedBy = "order",
           cascade = CascadeType.PERSIST,
           orphanRemoval = true)
private List<LineItem> lineItems = new ArrayList<>();

// CascadeType.REMOVE only:  em.remove(order)  -> deletes order AND its lineItems
// orphanRemoval=true:        order.getLineItems().remove(item)  -> deletes that item
//                            even though the order itself is NOT removed
```

The distinction is about *triggering condition*. `CascadeType.REMOVE` fires only when the **parent itself is removed** — it cascades the deletion downward. `orphanRemoval=true` fires when a **child is disassociated from the parent** (removed from the collection or its reference set to null), modeling a true *composition* / lifecycle-dependency relationship where a child cannot exist without its parent. So `orphanRemoval` implies remove-cascade behavior *plus* the extra rule that orphaned children are deleted. You want `orphanRemoval=true` for genuine ownership (an `Order` owns its `LineItem`s — pulling one out of the order means it should be deleted), and you specifically do **not** want it for shared/reference associations (removing a `Course` from a `Student`'s list must not delete the `Course`). Misapplying `orphanRemoval` to a many-to-many-ish reference relationship is a data-loss bug, which is why the semantic difference is a favorite interview probe.

#### Q35. [Practical] How do you call a stored procedure and map a complex native SQL result in EclipseLink, and what changed in JPA 2.1?
Before JPA 2.1, stored procedures were a vendor-specific affair; EclipseLink had `StoredProcedureCall` on its native query API. JPA 2.1 (EclipseLink 2.5) standardized this with **`@NamedStoredProcedureQuery`** and the `StoredProcedureQuery` API, so you can now invoke procedures portably, including IN/OUT/INOUT parameters and `REF_CURSOR` results:

```java
@NamedStoredProcedureQuery(
    name = "Order.summarize",
    procedureName = "summarize_orders",
    parameters = {
        @StoredProcedureParameter(mode = ParameterMode.IN,  name = "since",   type = LocalDate.class),
        @StoredProcedureParameter(mode = ParameterMode.OUT, name = "total",   type = BigDecimal.class),
        @StoredProcedureParameter(mode = ParameterMode.REF_CURSOR, name = "rows", type = void.class)
    },
    resultClasses = OrderSummary.class)
@Entity
public class Order { /* ... */ }

StoredProcedureQuery spq = em.createNamedStoredProcedureQuery("Order.summarize");
spq.setParameter("since", LocalDate.now().minusDays(30));
spq.execute();
BigDecimal total = (BigDecimal) spq.getOutputParameterValue("total");
List<OrderSummary> rows = spq.getResultList();
```

For arbitrary native SQL that returns columns not matching a single entity, you use **`@SqlResultSetMapping`** to describe how columns map to entities, scalar values, and constructor results:

```java
@SqlResultSetMapping(
    name = "OrderReport",
    classes = @ConstructorResult(
        targetClass = OrderReportDto.class,
        columns = {
            @ColumnResult(name = "customer", type = String.class),
            @ColumnResult(name = "order_count", type = Long.class)
        }))
// usage:
List<OrderReportDto> r = em.createNativeQuery(
    "SELECT customer, COUNT(*) AS order_count FROM orders GROUP BY customer",
    "OrderReport").getResultList();
```

The interview point is twofold. First, know that **JPA 2.1/EclipseLink 2.5 is the boundary** where stored procedures and `@ConstructorResult` became standard — older codebases use EclipseLink's native `StoredProcedureCall` and `ReportQuery`, which are still available and slightly more powerful (e.g., EclipseLink can map a `REF_CURSOR` more flexibly). Second, native queries and stored procedures **bypass the persistence context and can desynchronize the L2 cache** — a procedure that updates rows server-side leaves EclipseLink's shared cache stale, so you should mark such queries to invalidate affected classes or use them on uncached entities.

### 🟠 Advanced — extended

#### Q36. [Theory] How exactly does change tracking work with weaving, and what are the four tracking policies EclipseLink can use?
Dirty detection — figuring out which fields changed so the `UPDATE` only touches modified columns — is one of the costliest parts of any ORM, and EclipseLink supports several **`ObjectChangePolicy`** implementations chosen based on what weaving/configuration is available:

```
DeferredChangeDetectionPolicy   : snapshot at read time; at commit, field-by-field diff
                                  vs the cached backup. No weaving needed. O(fields) per object.
ObjectChangeTrackingPolicy      : woven setters mark the object dirty (a boolean flag) so
                                  EclipseLink only diffs objects known to have changed.
AttributeChangeTrackingPolicy   : woven setters record WHICH attributes changed in a
                                  change set; commit emits UPDATE only for those columns,
                                  with no diff scan at all. The fastest; needs weaving.
DeferredChangeDetectionPolicy (read-only / no UoW registration) : used for READ_ONLY results.
```

The mechanism behind `AttributeChangeTrackingPolicy` is the heart of *why EclipseLink weaves*. Weaving rewrites every setter so that `setTotal(x)` not only sets the field but also calls into an injected `PropertyChangeListener`/`_persistence_propertyChange(...)` that records `"total"` in a per-object change set. At commit, EclipseLink reads that change set directly — it never has to compare the object against a backup copy, and it never has to walk the entire object graph snapshotting unchanged objects. That turns commit cost from O(objects × fields) into O(changed objects × changed fields).

Without weaving, EclipseLink falls back to `DeferredChangeDetectionPolicy`: it keeps a **backup clone** of every registered object in the UnitOfWork and, at commit, walks all of them comparing field-by-field. This is correct but expensive in both CPU (the diff) and memory (a clone per managed object), and it is the concrete performance reason the existing material says "prefer weaving in production." The expert framing: weaving is not just about lazy loading — its biggest steady-state payoff is converting dirty detection from snapshot-diffing into push-based attribute change tracking.

#### Q37. [Theory] How does EclipseLink merge a detached entity back into a persistence context, and why can `merge()` silently lose updates?
`em.merge(detached)` does **not** make the detached instance managed; it copies the detached object's state onto a *managed* instance and returns that managed instance — the original detached object stays detached. Internally EclipseLink looks up the entity's primary key in the identity map: if a managed instance exists, it copies state onto it; if not, it loads the row from the database (or cache) into a fresh managed instance and copies state onto that; if the PK is null/new, it treats the merge as a persist. The crucial part is **which fields get copied**: merge copies the detached object's attribute values, including cascading along relationships marked `CascadeType.MERGE`.

```java
Order managed = em.merge(detachedOrder);
// detachedOrder is STILL detached; only `managed` is tracked.
managed.setStatus("SHIPPED");   // this is tracked
detachedOrder.setStatus("X");   // this is NOT
```

The "silently lose updates" trap has two classic shapes. **First**, if a detached entity was loaded as a *partial* object (a fetch group, or fields you never initialized) and you merge it, the un-loaded fields are still present as their default/null values on the detached object, and merge can **overwrite the database's good values with nulls** because merge does a whole-object state copy, not a diff against what you actually changed. **Second**, in optimistic-locking scenarios merge respects the `@Version`: if the detached object carries an old version and the row was updated meanwhile, the merge's flush throws `OptimisticLockException` — which is correct, but teams that swallow that exception effectively lose the user's edits. The senior guidance: avoid the detached-merge round-trip for partial entities, prefer loading the entity fresh inside the transaction and applying only changed fields, and always carry the `@Version` through to the client so merge can detect conflicts rather than blindly clobber.

#### Q38. [Theory] What are EclipseLink history (temporal) queries and how does `HistoryPolicy` work under the hood?
EclipseLink has built-in support for **temporal/historical data** through `HistoryPolicy`, which lets you query an entity *as it existed at a past point in time* without hand-rolling audit logic in application code. You configure it on the entity's descriptor (via a `DescriptorCustomizer`, since there is no portable JPA annotation for it), pointing at a **history table** that mirrors the base table with two extra columns — typically a row start and row end timestamp:

```java
public class OrderHistoryCustomizer implements DescriptorCustomizer {
    public void customize(ClassDescriptor descriptor) {
        HistoryPolicy policy = new HistoryPolicy();
        policy.addHistoryTableName("ORDERS", "ORDERS_HIST");
        policy.addStartFieldName("ROW_START");
        policy.addEndFieldName("ROW_END");
        descriptor.setHistoryPolicy(policy);
    }
}
```

Under the hood, when history is enabled EclipseLink **automatically writes the prior version of a row into the history table** on every update/delete (closing out the old row's `ROW_END`) inside the same transaction, so the history table accumulates an append-only timeline. You then query a past state with `AsOfClause`:

```java
ReadAllQuery q = new ReadAllQuery(Order.class);
q.setAsOfClause(new AsOfClause(timestamp));   // "the world as of <timestamp>"
List<Order> historical = (List<Order>) session.executeQuery(q);
```

The internals worth articulating: EclipseLink rewrites the SQL to read from the history table with a predicate like `ROW_START <= :asOf AND (ROW_END IS NULL OR ROW_END > :asOf)`, and it does this consistently across joins so an entire object graph can be reconstructed as-of a timestamp. The trade-off versus alternatives (Hibernate Envers, or database-native temporal tables like Oracle Flashback / SQL:2011 system-versioned tables) is that EclipseLink's `HistoryPolicy` keeps the temporal logic *inside the ORM* — portable across databases but doing the history-row writes itself (extra DML per change). If your database has native temporal tables, pushing history to the DB engine is often cheaper and more trustworthy; you would use `HistoryPolicy` when you need DB-portable, ORM-managed history without an extra library.

#### Q39. [Theory] How does EclipseLink implement inheritance mapping, and what are the trade-offs of SINGLE_TABLE vs JOINED vs TABLE_PER_CLASS in its query engine?
EclipseLink supports all three JPA `InheritanceType` strategies, and the descriptor model decides how a polymorphic query (`SELECT p FROM Payment p` where `Payment` has subclasses) expands into SQL:

```
SINGLE_TABLE     : one table, a DISCRIMINATOR column distinguishes subtypes.
                   Query = single SELECT + WHERE dtype IN (...). Fastest reads, no joins.
                   Cost: subclass-specific columns must be NULLable -> can't enforce NOT NULL.
JOINED           : root table + one table per subclass joined on shared PK.
                   Query = SELECT with JOINs (or UNION-style fan-out for polymorphic reads).
                   Normalized, allows NOT NULL; cost: every read joins, every insert hits 2+ tables.
TABLE_PER_CLASS  : one full table per concrete class, no shared table.
                   Polymorphic query = UNION ALL across all concrete tables.
                   Cost: polymorphic queries and shared-PK generation are awkward; least optimized.
```

The query-engine trade-off is the substantive part. For **SINGLE_TABLE**, a polymorphic `find` is a single indexed read and EclipseLink simply appends a discriminator predicate; this is why it is the default and the fastest for reads, but it sacrifices schema integrity (every subclass column is nullable) and produces sparse, wide tables. For **JOINED**, EclipseLink must join the root table to subclass tables; a query for the abstract root type that needs all subclass fields fans out across joins, and EclipseLink uses the discriminator (or outer joins) to know which subclass tables to bring in — clean schema, but read-heavy workloads pay the join tax on every access. For **TABLE_PER_CLASS**, EclipseLink expresses a polymorphic root query as a **`UNION ALL`** across each concrete table, which is the least optimizable (no shared index, sequence generation must be coordinated across tables) and is the one EclipseLink and the spec both flag as optional/least recommended.

The senior nuance EclipseLink adds beyond the spec is the descriptor-level control over discriminators and the ability to mix strategies in deep hierarchies, plus the interaction with the **L2 cache**: with SINGLE_TABLE all subtypes share one cache region keyed by PK, whereas JOINED hierarchies still cache by root PK but reads cost joins on a miss. In practice I default to SINGLE_TABLE for shallow hierarchies with few subclass-specific columns and switch to JOINED only when nullable-column sprawl or integrity constraints become unacceptable.

#### Q40. [Theory] How does MOXy compare to Jackson and the JDK/Glassfish JAXB RI for JSON/XML binding, and why would you choose MOXy?
MOXy (`org.eclipse.persistence.jaxb`) is unusual because it is a **single binding engine that targets both XML and JSON from the same metadata**, implementing the JAXB API (`jakarta.xml.bind`) and a JSON binding mode. That contrasts with the typical stack where **Jackson** handles JSON and a separate **JAXB implementation** (the Glassfish/Eclipse JAXB RI) handles XML — two libraries, two annotation sets, two mental models. The reasons to deliberately choose MOXy are specific:

```
Capability                         | MOXy            | Jackson        | JAXB RI (XML only)
-----------------------------------+-----------------+----------------+-------------------
XML + JSON from one mapping        | yes             | JSON only      | XML only
External mapping (no annotations)  | yes (bindings.xml)| limited (mixins)| no
XPath-based mapping (@XmlPath)     | yes             | no             | no
Bidirectional graphs w/o cycles    | @XmlInverseReference | @JsonIdentityInfo (different model) | no
Reuse JPA entity metadata          | yes             | no             | no
Dynamic binding (no compiled class)| yes             | partial (tree) | no
Ubiquity / community / perf        | smaller         | dominant, very fast | n/a
```

The standout MOXy features for an interview are **external binding files** (you map third-party or generated classes you cannot annotate, keeping domain classes clean) and **`@XmlInverseReference`**, which solves the bidirectional-relationship-cycle problem that plain JAXB cannot: a JPA `Order ↔ LineItem` graph has a cycle, and naive marshalling either infinite-loops or duplicates data, whereas `@XmlInverseReference` tells MOXy that one side is the "back-reference" to reconstruct on unmarshal without serializing the cycle. The honest trade-off: **Jackson is faster, far more widely adopted, and the de facto default in Spring Boot**, so you would only reach for MOXy when you genuinely need unified XML+JSON binding, external mappings, XPath mapping, or tight reuse of EclipseLink/JPA metadata — for example a legacy SOAP+REST service exposing the same domain model in both formats. Choosing MOXy purely for JSON in a greenfield Spring service would be swimming against the ecosystem.

#### Q41. [Practical] How does EclipseLink integrate with Spring, and what does `EclipseLinkJpaVendorAdapter` actually configure?
In a Spring (or Spring Boot) application you wire EclipseLink as the JPA provider behind Spring's `LocalContainerEntityManagerFactoryBean`, swapping the default `HibernateJpaVendorAdapter` for `EclipseLinkJpaVendorAdapter`. The vendor adapter is a convenience that translates Spring's abstract JPA settings (show-SQL, generate-DDL, the database platform) into the corresponding `eclipselink.*` properties so you do not hand-write them:

```java
@Bean
public LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds) {
    LocalContainerEntityManagerFactoryBean emf = new LocalContainerEntityManagerFactoryBean();
    emf.setDataSource(ds);
    emf.setPackagesToScan("com.shop.domain");

    EclipseLinkJpaVendorAdapter adapter = new EclipseLinkJpaVendorAdapter();
    adapter.setShowSql(true);            // -> eclipselink.logging.level=FINE (SQL)
    adapter.setGenerateDdl(false);
    adapter.setDatabasePlatform("org.eclipse.persistence.platform.database.PostgreSQLPlatform");
    emf.setJpaVendorAdapter(adapter);

    Map<String,Object> props = new HashMap<>();
    props.put("eclipselink.weaving", "true");
    emf.setJpaPropertyMap(props);
    return emf;
}
```

The non-obvious integration concern is **weaving**. Spring drives transactions through `JpaTransactionManager`, which is straightforward, but to get EclipseLink's dynamic weaving you must install Spring's `InstrumentationLoadTimeWeaver` (and run with the Spring instrumentation agent) so entity classes are woven at load time. If you cannot run an agent — and many Spring Boot deployments do not — you switch to **static weaving** via the `eclipselink-staticweave` build plugin, or accept non-woven behavior (`eclipselink.weaving=false`, losing scalar lazy loading and attribute change tracking). This is the most common stumbling block when teams move a service to EclipseLink under Spring: transactions and the EMF "just work," but lazy `@OneToOne` quietly becomes eager and dirty tracking falls back to snapshot diffing unless weaving is correctly set up. Spring Boot does not auto-configure EclipseLink the way it does Hibernate, so you also lose some of the `spring.jpa.*` auto-mapping and configure more explicitly.

### 🔴 Expert — extended

#### Q42. [Theory] How does the EclipseLink internal connection pool work, and when should you replace it with an external pool like HikariCP?
EclipseLink ships its own **internal connection pool** (`eclipselink.jdbc.connections.min/max/initial`) and historically distinguishes between a **read** pool and a **write** pool — the idea being that read-only queries can use a separate, possibly larger or differently-routed set of connections from transactional writes, and EclipseLink can lazily acquire a write connection only when the first DML happens. It also supports `eclipselink.jdbc.exclusive-connection.mode` to control whether a transaction holds a single connection exclusively or can release it between statements (relevant for `IsolatedClientSession` and connection affinity). This internal pool is fine for simple standalone apps and is what you get if you just specify a JDBC URL.

```xml
<property name="eclipselink.jdbc.connection_pool.default.min" value="5"/>
<property name="eclipselink.jdbc.connection_pool.default.max" value="20"/>
<property name="eclipselink.jdbc.exclusive-connection.mode"   value="Always"/>
```

In production, though, the prevailing practice is to **delegate pooling to a dedicated `DataSource` like HikariCP** and let EclipseLink simply borrow connections from it. The reasons are operational maturity rather than raw EclipseLink limitations: HikariCP has battle-tested leak detection, connection-validation, metrics (Micrometer/JMX), fast failure under DB outage, and integrates with container/Spring lifecycle and JTA. EclipseLink's internal pool lacks the observability and the aggressive correctness checks that an SRE team expects, and mixing EclipseLink's pool semantics with a JTA transaction manager is awkward. The expert rule: use the internal pool for tests, demos, and trivial standalone tools; configure an external `DataSource` (Hikari) for anything you have to operate, monitor, and reason about under failure — and then size *that* pool to the database's connection budget, with EclipseLink treating it as opaque.

#### Q43. [Theory] What are EclipseLink cursored streams / scrollable cursors, and why do they matter for very large result sets in a JPA world?
A normal `query.getResultList()` materializes the **entire** result set into a `List` in memory and registers every row in the persistence context — fine for a page, catastrophic for ten million rows (OOM, GC thrash, huge UnitOfWork). EclipseLink's answer is **cursored streams** and **scrollable cursors**, which stream rows from an open JDBC cursor on demand instead of buffering them, so memory stays bounded regardless of result size:

```java
Query q = em.createQuery("SELECT o FROM Order o");
q.setHint(QueryHints.CURSOR, true);
q.setHint(QueryHints.CURSOR_PAGE_SIZE, 500);    // rows fetched per round trip
CursoredStream stream = (CursoredStream) q.getSingleResult();
while (stream.hasNext()) {
    Order o = (Order) stream.next();
    process(o);
    // critical: periodically clear so the UoW/L1 doesn't accumulate every row
    if (stream.getPosition() % 1000 == 0) em.clear();
}
stream.close();   // releases the JDBC cursor/connection
```

The "why it matters" is that plain JPA gives you only `setMaxResults/setFirstResult` paging, which on most databases re-runs and re-scans the query for each page (O(N²) on offset-based pagination over a large table) and still buffers each page. A server-side cursor reads forward once, holding a stable cursor on the database side. The catches are exactly what makes this an expert topic: you must **`em.clear()` periodically** or the persistence context grows just as badly as a `List` would; the **cursor pins a connection** for its lifetime, so long-running streams hold a pooled connection and can starve the pool; and the cursor's validity is bound to the transaction, so combining it with very long processing risks transaction timeouts and lock retention. The principle to state: cursored streams trade JPA portability (this is an EclipseLink-specific hint) for the ability to process unbounded result sets with bounded memory — use them for ETL/export jobs, not for request-scoped reads, and always close and periodically clear.

#### Q44. [Theory] Why does EclipseLink offer `PROTECTED` cache isolation in addition to `SHARED` and `ISOLATED`, and what problem does it specifically solve?
`SHARED` (cache lives on the EMF, all sessions share instances) and `ISOLATED` (nothing shared; cache lives only in the persistence context) are the two ends of the spectrum. `PROTECTED` exists to solve a specific *graph consistency* problem that arises when a SHARED-cacheable entity holds **relationships to ISOLATED entities**. Consider a `Product` you want to cache (read-mostly, SHARED) that references a per-tenant or security-sensitive `PricingPolicy` that must be ISOLATED. If `Product` were plain SHARED, the cached `Product` instance would hold a reference to a particular session's `PricingPolicy` — and that reference would then be visible to *other* sessions reading the shared `Product`, leaking isolated data across sessions. That is the exact data-bleed bug isolation was supposed to prevent.

```
SHARED Product  --(refers to)-->  ISOLATED PricingPolicy
   if Product is SHARED naively, the cached Product's reference to PricingPolicy
   becomes visible to every session  ==> isolation violated.

PROTECTED Product:
   the entity's own state is cached in the shared cache (you still get the hit-rate win),
   BUT relationships to isolated entities are NOT held in the shared instance; they are
   resolved per-session from that session's isolated cache.
```

So `PROTECTED` means: cache the entity's *own* attributes in the shared cache (so you keep the caching benefit for the bulk of its data), but treat its references to isolated entities specially — resolve those per session rather than baking a single session's isolated object into the shared instance. EclipseLink will also automatically promote an entity to `PROTECTED` behavior when it detects relationships to isolated entities, which is why you sometimes see "protected" appear even when you only configured SHARED/ISOLATED. The expert framing: `PROTECTED` is the bridge that lets you mix cacheable reference data and non-cacheable isolated/tenant data in the *same object graph* without choosing between "cache nothing" and "leak isolated data" — it is essentially correctness glue for heterogeneous-isolation graphs.

#### Q45. [Theory] How does EclipseLink integrate Bean Validation and lifecycle callbacks, and at what points in the persistence cycle do they fire?
EclipseLink integrates **Jakarta Bean Validation** (`jakarta.validation`) at the JPA-defined automatic-validation points: by default it validates entities on the **pre-persist**, **pre-update**, and **pre-remove** lifecycle events, before the SQL is generated. You control which groups validate via `jakarta.persistence.validation.group.pre-persist/pre-update/pre-remove` properties, and you can disable it with `jakarta.persistence.validation.mode=NONE` (or `CALLBACK`/`AUTO`). When a constraint fails, EclipseLink throws `ConstraintViolationException` and the flush/commit is aborted — importantly *before* hitting the database, so a `@NotNull`/`@Size` violation never produces a bad row.

The ordering relative to **lifecycle callbacks** (`@PrePersist`, `@PostPersist`, `@PreUpdate`, `@PostUpdate`, `@PreRemove`, `@PostRemove`, `@PostLoad`) is the part interviewers probe, because the interaction is subtle:

```
em.persist / flush sequence for a new entity:
  1. @PrePersist callback runs            (your code can still mutate the entity)
  2. Bean Validation (pre-persist group)   (validates the now-final state)
  3. INSERT SQL generated and executed
  4. @PostPersist callback runs            (entity now has a generated ID, row exists)

update at flush:
  1. @PreUpdate  ->  2. Bean Validation (pre-update)  ->  3. UPDATE  ->  4. @PostUpdate
```

The mechanism detail worth stating: callbacks run *before* validation, which is deliberate — it lets a `@PrePersist` hook compute or normalize a field (e.g., set `createdAt`, lowercase an email) and have that computed value *then* validated, rather than validating a half-built entity. Two expert nuances: (1) `@PostLoad` fires when an entity is read/refreshed, which is where you reconstruct transient derived state, and (2) callbacks fire on the **persistence-context flush**, so an entity modified and then re-modified within one transaction fires `@PreUpdate` once at flush, not per setter — and if you query mid-transaction under `FlushModeType.AUTO`, the flush (and thus the callbacks + validation) can be triggered earlier than commit, which occasionally surprises people whose `@PreUpdate` has side effects.

#### Q46. [Theory] Explain how EclipseLink handles embeddables, element collections, and the difference between an embeddable and an entity at the cache/identity level.
An **`@Embeddable`** is a *value type*: it has no identity of its own, no primary key, and no independent lifecycle — it lives entirely inside its owning entity's row (its fields map to columns of the owner's table). An **`@Entity`** is a *reference type*: it has a primary key, its own identity-map entry, and an independent lifecycle. This distinction drives everything at the cache and identity level. An entity is cached by PK and shared via the identity map (two queries reaching the same entity return the same instance); an embeddable is **not** independently cached or identity-managed — it is just part of its owner's state, so each owner has its own copy and there is no `==` identity guarantee across owners even if the field values are equal.

```java
@Embeddable
public class Address {                 // value type: no @Id, no identity map entry
    private String street;
    private String city;
}

@Entity
public class Customer {
    @Id private Long id;
    @Embedded private Address address;            // columns live in CUSTOMER table

    @ElementCollection                            // collection of value types ...
    @CollectionTable(name = "CUSTOMER_PHONES",    // ... stored in a separate table
                     joinColumns = @JoinColumn(name = "customer_id"))
    @Column(name = "phone")
    private Set<String> phones = new HashSet<>();
}
```

**`@ElementCollection`** is a collection of value types (basics or embeddables) stored in a side table keyed by the owner's foreign key — but the elements are still value types with no identity. The critical behavioral consequence EclipseLink (and JPA generally) implements: because element-collection rows have no primary key of their own, **updating an element collection is typically implemented as delete-all-then-reinsert** for the owner's elements, which is why large element collections perform badly and why you should model anything that is large, frequently mutated, or needs its own identity as a proper `@OneToMany` of entities instead. The cache angle: the entire element collection is part of the owning entity's cached state (it travels with the owner in the L2 cache), whereas a `@OneToMany` of entities caches each child independently by its own PK. The senior judgment call is recognizing when a "value object" is really an entity in disguise — the moment it needs sharing, independent querying, identity, or efficient partial updates, the embeddable/element-collection model becomes a liability.

#### Q47. [Theory] Why does EclipseLink default lazy loading differently for `@ManyToOne`/`@OneToOne` versus `@OneToMany`/`@ManyToMany`, per the JPA spec?
The JPA specification mandates different *default* fetch types by cardinality: **`@ManyToOne` and `@OneToOne` default to `EAGER`**, while **`@OneToMany` and `@ManyToMany` default to `LAZY`**. EclipseLink follows the spec defaults. The reasoning is about cost asymmetry: a to-one association resolves to *at most one* related object, so eagerly fetching it (often via a join) is cheap and usually wanted; a to-many association could pull in an unbounded collection, so eager-loading it by default would risk loading huge graphs and cartesian explosions, hence lazy by default.

The EclipseLink-specific wrinkle — and a favorite interview trap — is that making a **to-one** association *actually* lazy requires **weaving**. A `@OneToMany` is lazy without weaving because the collection is represented by an `IndirectList`/`IndirectSet` wrapper (a proxy collection that triggers a query only when you first touch it), and that indirection works through ordinary Java collection semantics. But a `@OneToOne(fetch = LAZY)` field holds a *single object reference*; to defer loading it, EclipseLink must intercept the field access — and the only way to intercept a plain field read in Java is to rewrite the accessor via weaving. So:

```
@OneToMany(fetch = LAZY)   -> lazy WITHOUT weaving  (IndirectList proxy collection)
@ManyToOne                 -> EAGER by default
@OneToOne(fetch = LAZY)    -> lazy ONLY IF woven; otherwise silently EAGER
@Basic(fetch = LAZY)       -> lazy ONLY IF woven (e.g. lazy CLOB/BLOB)
```

The practical takeaway is twofold. First, you frequently *override* the to-one default to `LAZY` (`@ManyToOne(fetch = LAZY)`) to avoid pulling parents you do not need — but if weaving is off, that override is ignored and EclipseLink loads eagerly anyway, which is a silent performance bug rather than an error. Second, "lazy basic" (a lazily-fetched `@Lob` column) is the same weaving-dependent mechanism and is a great way to keep large columns out of normal reads — but again only when woven. This is precisely why EclipseLink documentation insists weaving be enabled in production: without it, half your carefully-chosen `LAZY` annotations on to-one and basic mappings are quietly no-ops.

#### Q48. [Practical] Two database calls return different results for the same entity inside one transaction. How would you reason about whether the cause is the L1 context, the L2 cache, or query bypass — and prove it?
This is a diagnosis question testing whether you understand the **three-layer read path** (L1 persistence context → L2 shared cache → database) and the fact that JPQL/SQL queries bypass L1. The reasoning framework: a `find()` by PK consults L1 first, then L2, then the DB; a JPQL/native *query* goes to the database (modulo the query result cache) and then *merges* results through the identity map. So "two calls disagree" almost always comes from one layer serving a value another layer doesn't have.

I would localize it methodically:

```
1. Reproduce with SQL logging on:
     eclipselink.logging.level=FINE
     eclipselink.logging.parameters=true
   -> If call A issues NO SQL and call B does, A was served from L1/L2 (cache hit).

2. Distinguish L1 vs L2:
     - Same EntityManager twice?  A second find() with no SQL is the L1 context
       returning the identical managed instance (== identity). That's correct per spec.
     - New EntityManager but still no SQL?  It's the L2 shared cache.
       Prove it: emf.getCache().contains(Entity.class, id) -> true.

3. Suspect query bypass / stale cache:
     - A find() returns OLD data but a fresh JPQL query returns NEW data (or vice versa)
       => the L2 object cache is stale relative to the DB, OR an external/native UPDATE
          changed the row without going through this EMF.
     - Prove it: emf.getCache().evict(Entity.class, id); re-find; if it now matches the
       DB, the shared cache was the culprit.

4. Confirm external mutation:
     - Compare the @Version column in the cached object vs SELECT version FROM table.
       A higher DB version proves someone wrote out-of-band.
```

The expert moves are: use `em.refresh(entity)` (forces a DB re-read and updates the cache) to test whether the DB and cache disagree; use `QueryHints.REFRESH=true` on a query to bypass the cache for that read; and remember that **modifying via `em.createQuery("UPDATE ...").executeUpdate()` (bulk update) bypasses the persistence context and does not update L1/L2**, so a bulk update followed by a `find()` in the same transaction is a textbook way to produce exactly this "two calls disagree" symptom. Once proven, the fix follows the cause: if it is benign L1 identity, nothing to fix; if it is stale L2, add TTL/`@Version`/ISOLATED or invalidate; if it is bulk-update bypass, call `em.refresh()`/`em.clear()` after the bulk DML or set `QueryHints.INVALIDATE_CACHE` on the bulk update. The point I would make to the interviewer is that you never guess — FINE logging plus `emf.getCache().contains(...)` plus the `@Version` comparison deterministically pinpoints which layer lied.

#### Q49. [Theory] How does EclipseLink's `@Mutable` / read-only descriptor behavior and the `eclipselink.read-only` query hint interact with the shared cache to avoid defensive copying?
EclipseLink's biggest steady-state cost on the read path is **cloning**: when a normal query returns entities, the UnitOfWork registers a working clone of each shared-cache instance (plus a backup clone for change detection under deferred policy) so your mutations don't corrupt the shared instance and so it can compute a change set at commit. The `eclipselink.read-only` hint (and read-only descriptors) is an optimization that says "these results will not be mutated, so skip registration entirely and hand back the shared-cache instance directly." That eliminates the clone-and-backup allocation per row, which on large result sets is a substantial CPU and GC win — it is the single most effective knob for read-heavy reporting paths.

```java
query.setHint(QueryHints.READ_ONLY, true);   // return shared-cache instances, no UoW clone
```

The dangerous interaction is exactly *why* cloning exists: a read-only entity **is the shared-cache instance**, shared across every concurrent transaction. If you mutate it, you are mutating cached state that other threads see, with no transactional isolation and no write to the database — a silent, hard-to-trace corruption. Relatedly, `@Mutable`/mutability metadata on attributes (and the platform's handling of value types like dates) tells EclipseLink whether an attribute's *contents* can change in place; treating an attribute as immutable lets EclipseLink skip deep-copying it during clone, but if it actually does get mutated in place EclipseLink can miss the change (no dirty detection) and fail to write it. So both mechanisms trade safety for speed by reducing copying, and both rely on a *contract you must honor*: read-only results are untouchable, and immutable-declared attributes must genuinely not be mutated in place.

The expert synthesis: cloning is EclipseLink's safety mechanism for the SHARED cache, and read-only/immutability are escape hatches for when that safety is unnecessary. The right pattern is to make read-only an explicit, reviewed decision on reporting/lookup queries (often combined with fetch groups and `BATCH IN` for maximum effect), never the default, and to enforce in code review that read-only result objects are never passed to a layer that might mutate them. If a caller might mutate, you either drop the hint or defensively copy into a DTO — which reintroduces the very copy you saved, so read-only pays off specifically when results flow straight to serialization/display without modification.

#### Q50. [Theory] What is the difference between `em.detach()`, `em.clear()`, EM close, and how does each affect the L1 context versus the L2 shared cache?
These four operations all "remove" entities from active management but at very different scopes, and conflating them is a common source of `LazyInitializationException` and surprise stale-data bugs. The crucial framing: **all of them affect only the L1 persistence context; none of them evict the L2 shared cache.** The shared cache is on the EMF and outlives any single EntityManager, so clearing/closing an EM never touches it — you need `emf.getCache().evict(...)` for that.

```
em.detach(entity)  : removes ONE entity (and cascaded ones) from the L1 context.
                     It becomes detached; further changes are NOT tracked.
                     Pending changes to it that were already flushed stay; un-flushed
                     changes are discarded for that entity. L2 untouched.

em.clear()         : detaches ALL managed entities at once (empties the L1 context).
                     Any un-flushed changes are LOST. The EM is still usable for new work.
                     Used in batch loops to bound memory. L2 untouched.

em.close()         : ends the EntityManager entirely. The L1 context is gone; the EM
                     cannot be reused. Open transactions/connections are released.
                     Detached entities you already hold remain usable (as detached). L2 untouched.

emf.getCache().evict(X) / em.refresh(x) : THIS is what touches the L2 shared cache.
```

The behavioral consequences worth articulating: after `detach`/`clear`/`close`, touching an un-initialized lazy association on a now-detached entity throws (the context that could load it is gone) — the analog of Hibernate's `LazyInitializationException`. In a **batch import loop** you call `em.flush(); em.clear();` every N rows to keep the L1 context from accumulating every entity (otherwise memory and dirty-check cost grow linearly) — but you must flush *before* clear or you discard the un-written changes. And the trap that bites teams: because none of these evict L2, a "clear and re-read" pattern intended to get fresh data will happily re-serve the **same stale objects from the shared cache** — so if your goal is freshness, `em.clear()` is not enough; you need `em.refresh()` or an explicit cache evict. The expert one-liner: detach/clear/close manage the *transaction-scoped* identity map; only refresh/evict manage the *JVM-scoped* shared identity map.

#### Q51. [Theory] How does EclipseLink decide the order of SQL statements within a flush, and how can statement reordering and batch writing interact to either help or hurt?
Within a flush, EclipseLink's `UnitOfWork` does not emit SQL in the order you called setters; it computes a **commit order** by analyzing the dependency graph implied by mappings and foreign keys, then orders DML so referential integrity holds: inserts proceed parents-before-children, deletes children-before-parents, and updates are slotted to respect constraints. On top of this it can **batch** statements (`eclipselink.jdbc.batch-writing=JDBC`) so that many homogeneous DML statements ship in one JDBC `addBatch/executeBatch` round trip. The interaction between *ordering* and *batching* is where the depth lies, because JDBC batching is most effective when consecutive statements are identical-shape (same SQL, different binds), and the commit order determines how "groupable" the stream is.

```
Naive order (interleaved):     Reordered + grouped for batching:
  INSERT order #1                INSERT order #1
  INSERT lineitem #1a            INSERT order #2          } batchable: same SQL
  INSERT order #2                INSERT lineitem #1a
  INSERT lineitem #2a            INSERT lineitem #2a      } batchable: same SQL
  -> 4 round trips, no batching  -> 2 batched round trips
```

EclipseLink supports **statement reordering** to maximize these homogeneous runs, and it also offers **parameterized batch writing** vs **dynamic (string) batch writing**: parameterized reuses a single `PreparedStatement` with batched binds (best on databases/drivers that support it well, e.g., setting `eclipselink.jdbc.batch-writing.size`), while dynamic concatenates SQL and is a fallback. The catches that make this expert-level: **`IDENTITY` ID generation defeats insert batching** because each insert must return its generated key immediately (you cannot batch when you need the key back per row before proceeding), which is a concrete reason to prefer `SEQUENCE` with preallocation when you want batched bulk inserts; batching also **delays constraint/error reporting** to `executeBatch`, so a single bad row's exception is harder to attribute to a specific statement; and aggressive reordering can occasionally surprise triggers or interleave with database-side logic that assumed a particular order. The synthesis to offer: EclipseLink already orders DML for correctness, and batching layers a throughput optimization on top — to actually benefit you must remove the things that fragment the batch (IDENTITY keys, mixed statement shapes, tiny transactions) and accept coarser error granularity in exchange for far fewer round trips.

#### Q52. [Theory] What are the trade-offs of EclipseLink's NoSQL/EIS support versus a purpose-built NoSQL driver or Spring Data, and when is "JPA over NoSQL" a mistake?
EclipseLink includes **NoSQL/EIS** support that lets you map entities to non-relational stores (historically MongoDB, Oracle NoSQL, and JCA/EIS resources) using JPA-style annotations (`@NoSql`), so the same `EntityManager` programming model spans relational and document stores. The appeal is uniformity: one ORM, one mapping vocabulary, one transaction-ish API across heterogeneous backends, which is attractive in polyglot-persistence shops that already standardized on EclipseLink. It can be genuinely useful for simple document-as-aggregate mappings where you want JPA ergonomics without learning a separate data-access stack.

The trade-offs, and why "JPA over NoSQL" is frequently a mistake, come from the **impedance mismatch between JPA's relational assumptions and NoSQL data models**. JPA is built around primary keys, foreign-key relationships, joins, JPQL, transactions, and a unit-of-work change set — concepts that map awkwardly or not at all onto document/key-value/wide-column stores. NoSQL stores favor *denormalized aggregates*, store-specific query languages (Mongo aggregation pipeline, CQL), eventual consistency, and access patterns designed around the queries you will run; forcing them behind a JPA facade tends to hide exactly the characteristics you adopted NoSQL for. You lose access to native features (Mongo's aggregation framework, secondary index nuances, TTL collections, sharding-aware queries), you inherit JPA semantics (cascades, the L2 cache, optimistic locking) that may be meaningless or wrong against the store, and you get a smaller, less-maintained code path than the vendor's first-party driver or Spring Data MongoDB.

The honest recommendation: reach for EclipseLink NoSQL only when you have a *light* document mapping, you are already deep in the EclipseLink ecosystem, and you value a single programming model over native power — and even then validate it carefully. For anything where the NoSQL store's native model, query language, or consistency behavior is central to why you chose it, use the **purpose-built driver or Spring Data module**, which exposes the store's real capabilities and is what the community actually maintains and documents. The interview-grade conclusion is recognizing that "use one ORM for everything" is an organizational convenience that can quietly cost you the performance and modeling advantages that justified the NoSQL choice in the first place — polyglot persistence usually wants polyglot data access, not a relational abstraction stretched over a non-relational store.

#### Q53. [Theory] How do JPA entity graphs work in EclipseLink, and how do they differ from EclipseLink's older fetch groups and load groups?
**Entity graphs** (`@NamedEntityGraph`, introduced in JPA 2.1 / EclipseLink 2.5) are the *portable, spec-standard* way to declaratively control which attributes and associations a query loads, overriding the static `FetchType` annotations at runtime. You attach a graph to a query as a hint with one of two semantics: a **fetch graph** (`jakarta.persistence.fetchgraph`) treats attributes in the graph as `EAGER` and everything else as `LAZY` (even normally-eager fields), while a **load graph** (`jakarta.persistence.loadgraph`) treats attributes in the graph as `EAGER` but leaves attributes outside the graph at their declared fetch type.

```java
@NamedEntityGraph(name = "Order.withItems",
    attributeNodes = @NamedAttributeNode("lineItems"))
@Entity
public class Order { /* ... */ }

EntityGraph<?> g = em.getEntityGraph("Order.withItems");
Order o = em.find(Order.class, id,
    Map.of("jakarta.persistence.fetchgraph", g));   // load ONLY id + lineItems graph
```

The relationship to EclipseLink's pre-2.1 mechanisms is the substantive part. EclipseLink had **fetch groups** (partial entity loading — load a subset of *columns/basic attributes*, deferring the rest until touched) and **load groups** (force a set of relationships to be loaded before the query returns, so you do not lazy-load them later outside the context) years before the spec standardized the idea. Entity graphs essentially **standardized fetch/load groups into portable JPA** — and EclipseLink implements entity graphs *on top of* its existing fetch-group/load-group machinery. So the practical distinctions are: fetch groups are EclipseLink-specific and finer-grained (they natively express partial column loading and integrate with weaving so accessing an unloaded attribute triggers a fetch), whereas entity graphs are portable but coarser and revolve around attribute nodes/subgraphs. The senior recommendation is to prefer **entity graphs** for portability when they suffice (controlling which associations load per query), and drop to **EclipseLink fetch groups** only when you specifically need partial-column loading on wide tables or the deferred-attribute-fetch-on-access behavior that weaving enables — the same depth/altitude reasoning that applies everywhere: use the portable abstraction first, the vendor extension only when it buys you something real.

#### Q54. [Theory] Why is the `equals()`/`hashCode()` contract for JPA entities subtle, and how does it interact with EclipseLink's identity map and detached entities?
The classic mistake is generating `equals()`/`hashCode()` from a database-generated `@Id`. The problem is lifecycle: a transient entity has a **null id** before persist, then gets assigned an id at flush — so its `hashCode()` *changes* mid-life. If you put that entity into a `HashSet` (e.g., a `@OneToMany` collection) while it is transient, then persist it, the object's hash bucket moves and the set can no longer find it (`contains()` returns false for an element that is actually in the set). This corrupts collection semantics in ways that are maddening to debug, and it is independent of which JPA provider you use.

```java
// FRAGILE: hashCode changes when id transitions from null -> generated value
@Override public int hashCode() { return Objects.hashCode(id); }

// ROBUST: use a stable business/natural key, or a UUID assigned in the constructor
@Override public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof Customer c)) return false;
    return businessKey != null && businessKey.equals(c.businessKey);
}
@Override public int hashCode() { return Objects.hashCode(businessKey); }
```

The EclipseLink-specific angle is the interaction with the **identity map**. *Within* one persistence context, EclipseLink guarantees that `em.find(X, id)` returns the same instance for the same key, so reference equality (`==`) already works correctly for managed entities — you do not strictly need a custom `equals()` to compare managed entities in the same context. But the moment entities become **detached** (different EM, serialized to a client, cached and re-read) you can hold *two different Java instances representing the same row*, and now `==` is false even though they are "the same" entity; correct `equals()` based on a stable key is what restores logical equality. The recommended approaches are: (1) use a **natural/business key** if one exists and is immutable; or (2) assign a **client-generated UUID** in the constructor so identity is stable from creation through persist and across detachment; and crucially, make `hashCode()` return a *constant or a stable-from-birth* value so it never changes after the object is created. The expert framing is that EclipseLink's identity map solves identity *inside* a unit of work, but `equals()/hashCode()` is what you need for correctness *across* units of work and detachment — and basing them on a generated id breaks precisely at the transient→persistent transition that the identity map cannot paper over.

#### Q55. [Theory] How does EclipseLink map enums, and what is the durability trade-off between `EnumType.ORDINAL` and `EnumType.STRING` (and using a custom converter)?
JPA (and thus EclipseLink) maps a Java enum to a column via `@Enumerated`, with two built-in strategies. **`EnumType.ORDINAL`** (the default if you write nothing) stores the enum's *position* — `0, 1, 2, ...` — as an integer. **`EnumType.STRING`** stores the enum constant's *name* as text. The trade-off is almost entirely about **schema durability under code change**, and getting it wrong is a silent data-corruption bug.

```java
public enum Status { NEW, PAID, SHIPPED }   // ORDINAL: NEW=0, PAID=1, SHIPPED=2

@Enumerated(EnumType.ORDINAL) private Status a;  // stores 0/1/2  -- FRAGILE
@Enumerated(EnumType.STRING)  private Status b;  // stores "NEW"/"PAID"  -- safer
```

The danger of `ORDINAL` is that the stored value is bound to *declaration order*. If a future developer inserts a new constant in the middle — `enum Status { NEW, PENDING, PAID, SHIPPED }` — every existing row's integer now points at the wrong constant (`1` was `PAID`, now means `PENDING`), and there is no error: rows are silently misinterpreted. `ORDINAL` is also unreadable in the database (you see `1`, not `PAID`) and brittle for anyone querying the table directly. `STRING` is resilient to reordering and self-documenting, at the cost of a wider column and breaking if someone *renames* a constant (the stored `"PAID"` no longer matches a renamed `SETTLED`). 

For production I default to **`EnumType.STRING`** (durability and readability beat the few bytes saved), and reach for a **JPA `AttributeConverter`** when I want full control — for example mapping each enum to an explicit, stable *code* that is decoupled from both ordinal and name, which survives both reordering *and* renaming:

```java
@Converter(autoApply = true)
public class StatusConverter implements AttributeConverter<Status, String> {
    public String convertToDatabaseColumn(Status s) { return s == null ? null : s.getCode(); }
    public Status convertToEntityAttribute(String c) { return Status.fromCode(c); }
}
```

The interview-grade point: never accept the `ORDINAL` default by omission. A custom converter (portable since JPA 2.1) is the most robust choice because the persisted representation becomes an explicit contract rather than an accident of enum declaration order, which matters enormously for long-lived schemas that outlive several rounds of code refactoring.

#### Q56. [Theory] What is the `@MappedSuperclass` versus `@Entity` inheritance distinction, and how does EclipseLink treat each in the descriptor and query model?
`@MappedSuperclass` and entity inheritance both let subclasses inherit mapping information, but they are fundamentally different in the descriptor/query model. A **`@MappedSuperclass`** is *not* an entity: it has no table, no descriptor of its own that you can query, and no identity — it exists purely to share mapped state (fields, `@Id` strategy, `@Version`, lifecycle callbacks) with the concrete entities that extend it. You **cannot** write `SELECT b FROM BaseEntity b` against a mapped superclass, you cannot have an association *to* a mapped superclass, and there is no polymorphic query across its subclasses — each subclass is an independent, unrelated entity that just happens to reuse the field definitions.

```java
@MappedSuperclass                       // NOT queryable, no table, no shared identity space
public abstract class Auditable {
    @Id @GeneratedValue private Long id;
    @Version private long version;
    private Instant createdAt;
}

@Entity public class Order   extends Auditable { /* own table ORDER  */ }
@Entity public class Invoice extends Auditable { /* own table INVOICE */ }
// SELECT a FROM Auditable a  -> ILLEGAL: Auditable is not an entity
```

Contrast with **`@Entity` + `@Inheritance`**: there the superclass *is* an entity with its own descriptor, so a query against the root type is **polymorphic** — `SELECT p FROM Payment p` returns `CreditCardPayment`, `BankTransfer`, etc., and EclipseLink expands the SQL according to the inheritance strategy (single-table discriminator, joins, or union). At the descriptor level EclipseLink builds a real `ClassDescriptor` for an entity superclass that participates in the inheritance hierarchy and the identity map, whereas for a `@MappedSuperclass` it merely *copies the mapping metadata* down into each concrete subclass's descriptor — there is no shared cache region, no shared id space, and no cross-subclass query path.

The design judgment: use `@MappedSuperclass` when you want **code reuse without a polymorphic relationship** — the canonical case is an `Auditable`/`BaseEntity` carrying `id`/`version`/timestamps that dozens of unrelated entities share, where you would *never* want to query "all auditables" or hold a reference to "some auditable." Use entity inheritance when the subclasses are genuinely a polymorphic family you query and associate as the common supertype. Choosing `@Inheritance(SINGLE_TABLE)` when you really just wanted shared columns forces all those unrelated entities into one table with a discriminator — a modeling error that `@MappedSuperclass` avoids cleanly.

#### Q57. [Theory] How does EclipseLink generate and validate the database schema (`eclipselink.ddl-generation`), and why is automatic DDL generation discouraged in production?
EclipseLink can derive DDL from your entity mappings via the `eclipselink.ddl-generation` property, with values that mirror common ORM behavior:

```
none                     : do nothing (the production default you should aim for)
create-tables            : CREATE TABLE for missing tables; leaves existing ones alone
drop-and-create-tables   : DROP then CREATE everything (DESTROYS data — dev/test only)
create-or-extend-tables  : create missing tables and ADD missing columns to existing ones
```
You also control the target with `eclipselink.ddl-generation.output-mode` (`database`, `sql-script`, or `both`) — the `sql-script` mode is the useful one, because it *emits the DDL to a file* rather than executing it, so you can review and version it:

```xml
<property name="eclipselink.ddl-generation" value="create-or-extend-tables"/>
<property name="eclipselink.ddl-generation.output-mode" value="sql-script"/>
<property name="eclipselink.create-ddl-jdbc-file-name" value="schema-create.sql"/>
```

Auto-DDL is discouraged in production for several converging reasons. First, **it is a one-way, mapping-driven derivation with no notion of migration history** — it cannot rename a column, backfill data, change a type with a transformation, drop an obsolete column safely, or apply changes in a controlled order; it only reconciles "what the entities imply" against "what exists" with coarse strategies. `drop-and-create` obviously destroys data, and even `create-or-extend` can only *add*, never reshape. Second, the generated DDL reflects the ORM's view, which often produces **suboptimal physical schema** — generic types, missing or naive indexes, no partitioning, default constraint names — that a DBA would never hand-write for a production system. Third, **letting the application mutate the schema at boot** is an operational and security anti-pattern: schema changes should be reviewed, tested against production-like data, ordered relative to code deploys, and runnable independently of the app, none of which auto-DDL supports.

The professional pattern is to use `ddl-generation` only as a *bootstrap or scaffolding aid* in development (or `sql-script` mode to **generate a starting migration** you then hand-edit), and manage production schema with a dedicated migration tool — **Flyway or Liquibase** — that has versioned, ordered, reversible, reviewed change sets. In production you set `ddl-generation=none` (or omit it) so the application never touches DDL, and optionally use EclipseLink/JPA schema *validation* to fail fast if the runtime schema does not match the mappings. The interview point is recognizing that DDL generation answers "make a schema that fits my objects right now," whereas production needs "evolve a living schema with history, data preservation, and review" — a different problem that migration tools, not the ORM, are built to solve.

#### Q58. [Theory] What guarantees does the JPA/EclipseLink `EntityManagerFactory` versus `EntityManager` give around thread safety, and what bugs arise from getting it wrong?
The threading contract is a frequent senior-level question because violating it produces intermittent, load-dependent corruption rather than clean failures. The rule is: an **`EntityManagerFactory` is thread-safe and expensive to build** — you create exactly one per persistence unit per application and share it across all threads — while an **`EntityManager` is NOT thread-safe and is cheap** — you create one per unit of work (per request/transaction) and never share it across threads. The EMF holds the immutable, shared state (parsed metadata, descriptors, the L2 shared cache, the connection pool); the EM holds *mutable, request-scoped* state (the L1 persistence context, the in-flight transaction, registered/dirty objects), which is exactly why it cannot be shared.

```
EntityManagerFactory  (one per app, thread-safe)
   |-- holds: metadata model, L2 shared cache, connection pool
   |
   +-- EntityManager (per request/thread, NOT thread-safe)
   |      holds: L1 context (identity map of THIS unit of work), tx, dirty set
   +-- EntityManager (another thread)
          ... independent L1 context ...
```

The bugs from getting it wrong are characteristic. **Sharing one `EntityManager` across threads** (e.g., storing it in a singleton or a servlet field) means two requests mutate the *same* persistence context concurrently: the identity map, the dirty-tracking change set, and the underlying connection are all touched by multiple threads with no synchronization, producing race conditions where one request flushes another's changes, entities appear/disappear unpredictably, the wrong transaction commits another thread's work, and you get `ConcurrentModificationException` or silent data interleaving under load — symptoms that vanish when you test single-threaded. Conversely, **creating a new EMF per request** is the opposite mistake: you pay the heavy bootstrap cost (metadata parsing, descriptor building, pool creation) repeatedly and you fragment the L2 cache into per-request caches that never share, destroying the caching benefit and leaking connection pools.

In practice frameworks handle this for you, which is why the contract is easy to forget: Spring's `@PersistenceContext`-injected EM is a **thread-bound proxy** that transparently routes to the correct per-transaction EM, and JTA containers give you transaction-scoped EMs. The senior answer is to state the contract precisely (EMF: one, shared, thread-safe; EM: per-unit-of-work, never shared), explain that the EM's non-thread-safety is a *direct consequence* of it holding the mutable L1 identity map and transaction, and note that the safe pattern in plain JPA is `EntityManager em = emf.createEntityManager()` at the start of a unit of work and `em.close()` in a finally block — one EM per thread per transaction, the EMF shared for the life of the application.

## 🧩 Extended Questions — Set 2: Practical, Operational & Troubleshooting

### 🟢 Basic — extended

#### Q59. [Practical] How do you turn on SQL logging in EclipseLink, and what do the logging levels actually show?
The single most useful operational skill with EclipseLink is reading what SQL it actually sends. Logging is controlled by `eclipselink.logging.level` and a family of sub-category toggles. The level is a `java.util.logging`-style scale, and the practical thing to remember is that **`FINE` is where SQL appears** — anything coarser (CONFIG, INFO, WARNING, SEVERE) hides the generated statements:

```
OFF / SEVERE / WARNING / INFO / CONFIG : no SQL shown
FINE     : the generated SQL statements
FINER    : SQL + transaction events, connection events
FINEST   : everything, including cache hits/misses and internal sequencing
ALL      : maximal verbosity
```

The most common real configuration is FINE plus parameter binding, because by default EclipseLink logs the SQL with `?` placeholders and you cannot see the actual bind values:

```xml
<property name="eclipselink.logging.level" value="FINE"/>
<property name="eclipselink.logging.parameters" value="true"/>   <!-- show bind values -->
<property name="eclipselink.logging.timestamp" value="true"/>
<property name="eclipselink.logging.thread" value="true"/>       <!-- which thread issued it -->
<property name="eclipselink.logging.session" value="true"/>
```

You can also scope verbosity per category so you do not drown in noise — `eclipselink.logging.level.sql=FINE` shows only SQL while keeping the root logger at WARNING, and categories like `cache`, `connection`, `query`, `transaction`, and `weaver` each have their own `eclipselink.logging.level.<category>`. The operational caveat I always state in an interview: **never run FINE/parameters in production steady-state** — it serializes every bind value to the log and can both crush throughput and leak PII (you are literally printing the values you sent to the database). The discipline is to enable it in staging or behind a flag, reproduce the issue, capture the SQL, then turn it back off. EclipseLink also lets you swap the logger to SLF4J (`eclipselink.logging.logger=org.eclipse.persistence.logging.slf4j.SLF4JLogger`) so the output flows through your normal logging pipeline rather than `System.out`.

#### Q60. [Practical] Where do you put EclipseLink configuration, and what is the precedence when the same property is set in multiple places?
EclipseLink reads configuration from several layers, and knowing the precedence prevents the "I set the property but nothing changed" class of bugs. The layers, from lowest to highest priority, are roughly: **provider defaults** → **`persistence.xml` `<properties>`** → **the map passed to `Persistence.createEntityManagerFactory(name, propertiesMap)`** → **system properties / programmatic overrides**. The map you pass at bootstrap *overrides* what is in `persistence.xml`, which is exactly how Spring and test harnesses inject environment-specific values (URL, user, password, pool size) without editing the XML:

```java
Map<String,Object> overrides = Map.of(
    "jakarta.persistence.jdbc.url", System.getenv("DB_URL"),
    "eclipselink.logging.level", "WARNING",
    "eclipselink.jdbc.batch-writing", "JDBC");
EntityManagerFactory emf =
    Persistence.createEntityManagerFactory("orders-pu", overrides);   // overrides win
```

A second axis of precedence is **annotation vs XML mapping**. EclipseLink supports an `orm.xml` (the JPA standard mapping descriptor) and its own `eclipselink-orm.xml`. XML mappings *override* annotations for the same entity, which is the standard escape hatch when you cannot or should not annotate a class (generated sources, third-party domain objects, or environment-specific mapping tweaks). The `eclipselink-orm.xml` additionally exposes EclipseLink-only mapping features that the standard `orm.xml` schema cannot express.

The interview-grade point is to treat this as a deliberate layering strategy rather than an accident: keep stable, structural configuration in `persistence.xml`/`orm.xml` (checked into version control), and inject the things that vary per environment (credentials, URLs, pool sizing, logging level) through the bootstrap property map or externalized config (Spring `@ConfigurationProperties`, environment variables). When a property "doesn't take," the first diagnostic is *which layer am I setting it in, and is a higher-priority layer silently overriding it* — for example a Spring `spring.jpa.properties.eclipselink.*` value will override the same key in `persistence.xml`.

#### Q61. [Practical] You see `LazyInitializationException`-style failures (or `NullPointerException` on a lazy field) outside the transaction. How do you fix it in EclipseLink, and what are the options?
The symptom is accessing a lazy association after the `EntityManager` that loaded the entity has closed — the persistence context that could issue the loading query is gone, so EclipseLink cannot resolve the indirection. With EclipseLink specifically, the failure shape depends on weaving: a lazy `@OneToMany` is an `IndirectList` that throws when triggered outside an active session, while a lazy woven `@OneToOne`/`@Basic` field typically resolves to `null` or throws depending on configuration. The root cause is always the same: you tried to read data that was never fetched while the loading context was still open.

The fix menu, from most to least preferred:

```
1. Fetch what you need WHILE the context is open:
     - JOIN FETCH in the query:  SELECT o FROM Order o JOIN FETCH o.lineItems WHERE ...
     - Entity graph hint:         em.find(Order.class, id, Map.of("jakarta.persistence.loadgraph", g))
     - EclipseLink BATCH/LEFT_FETCH hints for efficiency across many parents
2. Map to a DTO inside the transaction (projection) so nothing lazy escapes:
     - SELECT new com.shop.OrderDto(o.id, o.total) FROM Order o
3. Keep the context open longer (Open-Session-In-View) -- usually an ANTI-PATTERN
4. Make the association EAGER -- blunt; risks over-fetching everywhere
```

The senior framing is that this is a *design* decision, not a config flag. The correct default is **fetch exactly what the use case needs, inside the transaction, then return immutable data (DTOs) across the boundary** — that is what makes the dependency on the open context disappear. Reaching for EAGER fetch makes the symptom go away but pulls the association on *every* read path, reintroducing over-fetch. "Open Session In View" (holding the EM open through view rendering) is tempting in web apps and Spring even auto-enables it, but it couples your transaction lifetime to your rendering, hides N+1 problems, and holds connections longer — I disable it (`spring.jpa.open-in-view=false`) and force teams to declare their fetch needs explicitly. The EclipseLink-specific note is that **without weaving, your lazy to-one annotations are silently eager**, so paradoxically a missing-weaving misconfiguration can *mask* this exception in dev and then surface it in prod once weaving is correctly enabled — another reason to keep weaving consistent across environments.

### 🟡 Intermediate — extended

#### Q62. [Practical] Production shows hundreds of nearly identical SQL statements per request. Walk through diagnosing and fixing the N+1 in EclipseLink.
The N+1 problem — one query for the parents plus one query per parent for a lazy association — is the most common EclipseLink performance incident, and the diagnosis is methodical rather than guesswork. First, **prove it from the logs**: with `eclipselink.logging.level.sql=FINE` you will see one `SELECT ... FROM orders` followed by N copies of `SELECT ... FROM line_items WHERE order_id = ?` with incrementing parameters. The signature is "one parent query, then a burst of identical child queries differing only in the foreign-key bind."

```
SELECT id, customer FROM orders WHERE created > ?        <- 1 parent query
SELECT * FROM line_items WHERE order_id = 1001           <- begin N child queries
SELECT * FROM line_items WHERE order_id = 1002
SELECT * FROM line_items WHERE order_id = 1003
...                                                        (one per parent row)
```

The fix depends on the access pattern, and EclipseLink gives you more options than plain JPA. For a **single to-one or a small to-many**, `JOIN FETCH` (or `QueryHints.LEFT_FETCH`) collapses it into one statement. For a **large to-many across many parents**, `JOIN FETCH` causes a cartesian blow-up (rows = parents × children), so the right tool is **batch fetching**, which issues exactly one extra query for the whole page:

```java
// One parent query + ONE batched child query for all parents in the result set.
TypedQuery<Order> q = em.createQuery("SELECT o FROM Order o WHERE o.created > :d", Order.class);
q.setParameter("d", since);
q.setHint("eclipselink.batch", "o.lineItems");
q.setHint("eclipselink.batch.type", "IN");   // WHERE order_id IN (1001,1002,...) -- index friendly
```

The decision matrix I keep in my head: `LEFT_FETCH` for one-to-one and small collections you always need; `BATCH IN` for collections fetched across many parents (it stays O(1) extra queries and is index-friendly because it becomes an `IN (...)` predicate); fetch groups when the issue is actually wide-row over-fetch rather than relationship N+1. The two anti-fixes to call out are (a) making the association EAGER, which just hides the N+1 by making it happen on *every* query whether you need the data or not, and (b) cranking the L2 cache so the child queries become cache hits — that masks the symptom in steady state but the first cold request still pays N+1 and any cache miss reintroduces it. The real fix declares the fetch strategy at the query, sized to the page.

#### Q63. [Practical] How do you tune the JDBC statement cache and batch writing in EclipseLink, and what real numbers should you set?
Two independent EclipseLink knobs dominate write-path and prepared-statement performance, and interviewers like concrete values. The first is the **statement cache**, which caches `PreparedStatement` objects so EclipseLink reuses them instead of re-preparing identical SQL — eliminating parse/compile overhead on the database for hot queries:

```xml
<property name="eclipselink.jdbc.cache-statements" value="true"/>
<property name="eclipselink.jdbc.cache-statements.size" value="100"/>  <!-- per connection -->
```

The size should roughly cover your number of *distinct* hot statement shapes; 50–200 is typical. The catch I always raise: the cache is **per connection**, and prepared statements pin server-side cursors, so a large cache × a large pool can pressure the database's open-cursor limit (classic on Oracle: `ORA-01000: maximum open cursors exceeded`). You size it against `pool_size × cache_size < db_cursor_budget`.

The second is **batch writing**, which groups DML into JDBC batches to cut round trips — the biggest win for bulk insert/update workloads:

```xml
<property name="eclipselink.jdbc.batch-writing" value="JDBC"/>          <!-- use the driver's batch API -->
<property name="eclipselink.jdbc.batch-writing.size" value="500"/>      <!-- statements per batch -->
<property name="eclipselink.jdbc.batch-writing" value="Buffered"/>      <!-- alternative: EclipseLink buffers/concatenates -->
```

`JDBC` (true driver batching with `addBatch/executeBatch`) is the right default on PostgreSQL/MySQL/Oracle; on PostgreSQL you also want `reWriteBatchedInserts=true` in the JDBC URL so multi-row inserts collapse into one statement. The numbers that matter: a batch size of 100–1000 is the sweet spot — too small and you do not amortize the round trip, too large and you grow memory and lengthen the window where a single failure aborts the whole batch. The two gotchas to state: **`GenerationType.IDENTITY` disables insert batching** (the driver must return each generated key before the next insert), so use `SEQUENCE` with preallocation when you need batched bulk inserts; and batching **defers error reporting** to `executeBatch`, so you trade fine-grained "which row failed" diagnostics for throughput. I would benchmark with FINE logging off (logging itself dominates otherwise), comparing rows/sec at batch sizes of 100/500/1000 against the real driver and database before pinning a value.

#### Q64. [Practical] An external batch job updates rows directly in the database and your EclipseLink app keeps serving stale data. Lay out the production strategies and their trade-offs.
This is the canonical EclipseLink production incident because the L2 cache is on by default — any writer that bypasses this `EntityManagerFactory` (a nightly batch, a DBA, another service, a stored procedure, a native `UPDATE`) leaves the shared cache holding values the database no longer has. The strategies form a spectrum from "accept bounded staleness" to "guarantee freshness," and the right answer is usually a *combination* matched to each entity's volatility:

```
Strategy                         Freshness     Cost / trade-off
-------------------------------- ------------- --------------------------------------------
TTL expiry  @Cache(expiry=...)   bounded lag   simplest; reads stale up to TTL window
@Version optimistic lock         catches writes detects conflict at write, not read
em.refresh / QueryHints.REFRESH  per-read      forces a DB round trip; defeats caching there
emf.getCache().evict(Class,id)   targeted      needs an invalidation signal from the writer
ISOLATED / @Cacheable(false)     always fresh  no caching benefit for that entity
Cache coordination (JMS/RMI)     cross-node    only helps when the writer is an EclipseLink node
```

The architectural decision is to classify entities. **Read-mostly reference data** (countries, categories, product catalog) gets SHARED cache with a TTL — staleness of a few minutes is acceptable and the hit-rate win is large. **Volatile transactional data that is updated out-of-band** (inventory, prices changed by a pricing service) gets either ISOLATED/`@Cacheable(false)` or an explicit **invalidation hook**: the external writer publishes an event (Kafka/JMS) and each app node calls `emf.getCache().evict(Price.class, id)`. The subtle point is that **cache coordination does not help here** — coordination only propagates changes *between EclipseLink nodes that share the EMF configuration*; a non-EclipseLink batch job is invisible to it, so you need an application-level invalidation channel or TTL.

What I would actually ship in production is defense in depth: a `@Version` column on anything writable (so even if the cache lies, the next write fails fast rather than silently clobbering), a modest TTL on cached reference data as a safety net for invalidation messages that get lost, and an explicit evict endpoint/event the batch job triggers after it commits. I would also write an integration test that *simulates* the out-of-band update (write via a second connection, then read via EclipseLink) so the staleness behavior is verified, not assumed — because this bug is invisible in single-writer dev environments and only appears once a second writer exists in production.

#### Q65. [Coding] Write a robust retry wrapper for `OptimisticLockException` and explain why naive retries make things worse.
Optimistic locking (a `@Version` column) is the right concurrency default, but it surfaces conflicts as a thrown `OptimisticLockException` at flush/commit, and the caller must decide how to recover. The correct recovery for a *lost-update* conflict is usually **re-read the fresh state, re-apply the intended change, and retry** — but only for a bounded number of attempts and with jitter, because a tight retry loop on a hot row turns a conflict into a thundering herd.

```java
public <T> T withRetry(EntityManagerFactory emf, int maxAttempts, Function<EntityManager, T> work) {
    int attempt = 0;
    while (true) {
        EntityManager em = emf.createEntityManager();
        try {
            em.getTransaction().begin();
            T result = work.apply(em);          // work() must RE-READ inside this em, not reuse stale state
            em.getTransaction().commit();
            return result;
        } catch (OptimisticLockException | RollbackException ex) {
            safeRollback(em);
            if (++attempt >= maxAttempts) throw ex;          // give up -> surface to caller
            sleepWithJitter(attempt);                        // exponential backoff + randomness
        } finally {
            em.close();                                      // FRESH context each attempt -> no stale L1
        }
    }
}
private void sleepWithJitter(int attempt) {
    long base = Math.min(50L << attempt, 1000L);             // 50,100,200,... capped at 1s
    try { Thread.sleep(ThreadLocalRandom.current().nextLong(base)); }
    catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw new RuntimeException(ie); }
}
private void safeRollback(EntityManager em) {
    try { if (em.getTransaction().isActive()) em.getTransaction().rollback(); } catch (RuntimeException ignore) {}
}
```

**Why it works:** each attempt uses a *fresh* `EntityManager` (and therefore a fresh L1 context), so the retry re-reads the current `@Version` from the database instead of resubmitting the same stale version that just failed — reusing the old context would just throw again forever. The `work` function must perform its read inside the supplied `em` so the re-apply sees current state. **Time:** O(attempts) database round trips; expected attempts stay near 1 under low contention. **Edge cases:** (1) only retry *idempotent* or *re-appliable* operations — blindly retrying a non-idempotent side effect (sending an email, charging a card) duplicates it, so side effects belong outside the retried block; (2) cap attempts and back off with jitter or you create a retry storm that *increases* contention on the hot row; (3) `RollbackException` can wrap the optimistic failure, so catch both; (4) a wrong fix some teams reach for — switching to `PESSIMISTIC_WRITE` — trades the retry for a `SELECT ... FOR UPDATE` that serializes access and risks deadlocks, which is correct only for short, highly-contended operations, not as a blanket replacement for retry.

#### Q66. [Practical] How do you write integration tests for EclipseLink, and what makes testing the cache and weaving correctly tricky?
The non-negotiable principle is to test against a **real database** (via Testcontainers) rather than H2/in-memory, because the bugs that matter in EclipseLink are dialect-, cache-, and concurrency-specific and an in-memory DB papers over them — DDL types, sequence behavior, `FOR UPDATE` semantics, and `LIMIT`/pagination syntax all differ. A typical setup spins a Postgres container, points the persistence unit at it, and runs migrations (Flyway) so the schema matches production:

```java
@Testcontainers
class OrderRepositoryIT {
    @Container static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16");
    static EntityManagerFactory emf;

    @BeforeAll static void boot() {
        emf = Persistence.createEntityManagerFactory("orders-pu", Map.of(
            "jakarta.persistence.jdbc.url",  db.getJdbcUrl(),
            "jakarta.persistence.jdbc.user", db.getUsername(),
            "jakarta.persistence.jdbc.password", db.getPassword()));
    }
}
```

The genuinely tricky parts are caching and weaving. For **caching**, a test that writes and re-reads through the *same* EMF can pass even when the cache is broken, because it is served from the shared cache — so to test stale-read behavior you must mutate the row through a *second, independent connection* (or a second EMF) and then read through EclipseLink, asserting on whether you see the stale or fresh value. To get deterministic, cache-independent assertions you often disable the shared cache in tests (`shared-cache-mode=NONE` or `@Cacheable(false)`) so reads always hit the database — but then you are *not* testing the production cache configuration, so I keep a small set of tests with caching enabled specifically to verify TTL/invalidation/isolation behave as designed.

For **weaving**, the trap is that tests frequently run *without* the load-time-weaving agent, so lazy to-one associations are silently eager and your test "passes" while production (with weaving) behaves differently — or vice versa. The disciplined approach is to **statically weave the test classpath** (run the `eclipselink-staticweave` build step before tests) or configure the test JVM with the agent, so the woven behavior under test matches production. I also assert on *SQL counts* (using a query-logging or a statistics hook) rather than just on returned values, because the difference between "fetched correctly" and "fetched with N+1" is invisible in the result object but obvious in the statement count — capturing that the expected number of queries fired is what actually catches fetch-strategy regressions.

#### Q67. [Practical] What EclipseLink-specific metrics and signals do you monitor in production, and how do you expose them?
Operating EclipseLink well means watching three layers: the **cache**, the **connection pool**, and the **query/transaction** path. EclipseLink exposes runtime profiling through its `PerformanceMonitor`/`PerformanceProfiler` and you can register a query redirector or session profiler to capture per-query timing; you enable lightweight profiling with `eclipselink.profiler=PerformanceMonitor` (or `PerformanceProfiler` for heavier detail) and read the accumulated counters via the session:

```xml
<property name="eclipselink.profiler" value="PerformanceMonitor"/>
```
```java
// Pull cache + query counters from the underlying server session
ServerSession session = JpaHelper.getEntityManagerFactory(emf).getServerSession();
PerformanceMonitor monitor = (PerformanceMonitor) session.getProfiler();
Map<String,Object> snapshot = monitor.getOperationTimings();   // counters/timings by operation
```

The signals that actually predict incidents: **L2 cache hit ratio** (a falling ratio means more DB round trips — either the working set outgrew the cache `size`, or TTL/eviction is too aggressive); **connection pool saturation** (active vs max, and wait/borrow time — if you use HikariCP, its Micrometer metrics `hikaricp.connections.active/pending/usage` are the gold standard and far richer than EclipseLink's internal pool counters); **query latency and frequency** (to catch a query that quietly went from one statement to N+1 after a code change); and **statement-cache effectiveness** plus **batch sizes** on the write path.

In practice I route everything through the standard observability stack rather than EclipseLink's bespoke profiler: externalize the pool to HikariCP and scrape its Micrometer metrics, instrument the data source or use the JDBC layer to time and count statements, and emit cache hit/miss as application metrics. The reason is operational maturity — the EclipseLink `PerformanceMonitor` is useful for a focused investigation but is not a production telemetry system (no dashboards, alerting, or histograms out of the box). The interview-grade point is to name the *leading indicators* (cache hit ratio dropping, pool pending-threads rising, statements-per-request climbing) and tie each to the corrective action, rather than listing generic JVM metrics — those three EclipseLink-specific signals are what turn a 2am page into a five-minute diagnosis.

### 🟠 Advanced — extended

#### Q68. [Practical] A query that is fast in SQL*Plus is slow through EclipseLink (or vice versa). How do you find out why?
This is a classic "the ORM is slow" complaint that almost always decomposes into a small set of explainable causes. The disciplined approach is to **first capture the exact SQL EclipseLink sends** (FINE + parameters logging), copy it verbatim into the database with the *same bind values*, and `EXPLAIN`/`EXPLAIN ANALYZE` it — this immediately tells you whether the database is doing the same work in both places. If the SQL is identical and the plan is the same, the time is being spent *outside* SQL execution, in EclipseLink or the round trip.

The usual culprits and how to confirm each:

```
Symptom                                  Likely cause                         Confirm / fix
---------------------------------------- ------------------------------------ --------------------------------
Same SQL, fast in tool, slow in app      ORM materialization / cloning cost   READ_ONLY hint; compare with profiler
Many small queries instead of one        N+1 lazy loading                     SQL log shows the burst -> batch/fetch
One query but huge row set materialized  over-fetch (wide entity / cartesian) fetch groups; avoid multi-collection JOIN FETCH
Different plan tool vs app               bind peeking / parameter type mismatch check bind types; consider literals
First call slow, later fast              metadata/statement prep + cold cache  named queries; statement cache
Slow only under load                     pool exhaustion / lock contention     pool metrics; FOR UPDATE waits
```

The most subtle is the **bind-variable plan difference**: a tool run with literal values may get a different execution plan than EclipseLink's parameterized `PreparedStatement`, because the optimizer can use literal values for cardinality estimates ("bind peeking") but may pick a generic plan for a parameterized query. On Oracle this shows up as a query that is fast with a literal date range and slow with a bind. The fixes are database-specific (cursor sharing settings, hints, or in extreme cases forcing literals via `eclipselink.jdbc.bind-parameters=false` for that query) and you must weigh them against losing statement-cache reuse.

The other high-value finding is **materialization cost**: if the SQL and plan are identical but the app is still slower, EclipseLink is spending time building managed objects, cloning them into the UnitOfWork, and snapshotting for change detection. Confirm it by re-running with `QueryHints.READ_ONLY=true` and a fetch group — if it speeds up dramatically, the cost was object registration, not the database, and the fix is read-only/projection rather than touching SQL. The meta-skill the interviewer is checking is that you *isolate the layer* (database plan vs round trips vs object materialization) before changing anything, rather than randomly adding indexes or hints.

#### Q69. [Practical] Walk through a real production incident: intermittent `ORA-01000 maximum open cursors exceeded` (or connection leaks) traced to EclipseLink. How did you diagnose and resolve it?
`ORA-01000` (or its equivalents — too many prepared statements, cursor leaks) is a textbook EclipseLink-adjacent production incident, and the value of the answer is the systematic diagnosis. The error means the database ran out of open cursors for a session, and there are two distinct root causes that must be told apart: **(a) cursors are leaking** (something opens result sets/statements and never closes them), or **(b) cursors are configured to be retained** more aggressively than the database budget allows (statement cache × pool size exceeds the per-session cursor limit).

```
Diagnosis path:
1. Confirm which one:  query the DB for open cursors per session (Oracle: v$open_cursor)
   - growing without bound over time  -> LEAK
   - stable but high, ~ pool_size * cache_size  -> CONFIG / retention
2. Leak hunt:
   - CursoredStream / scrollable cursor never closed?  -> missing stream.close()
   - native Statement/ResultSet obtained via unwrap() and not closed?
   - EntityManager not closed in a finally block (RESOURCE_LOCAL)?
3. Retention math:
   eclipselink.jdbc.cache-statements.size (per connection) * pool max  > open_cursors limit?
```

In the incident shape I describe, the trigger was **cached statements pinning cursors**: `eclipselink.jdbc.cache-statements.size` was set high to speed up hot queries, and with a large connection pool the product (`cache_size × pool_max`) exceeded the Oracle `OPEN_CURSORS` parameter, so under peak concurrency sessions hit the cap. The fix was to right-size both — lower the per-connection statement cache to cover only the genuinely hot statement shapes, cap pool size to the database's connection budget, and raise `OPEN_CURSORS` modestly on the DB side — verified by watching `v$open_cursor` plateau below the limit under load.

The *other* common shape is a true **leak from cursored streams**: an ETL job using `QueryHints.CURSOR` that returned early on an exception without `stream.close()`, leaking the JDBC cursor and its connection each run until the pool and cursor table filled. The fix is the boring-but-correct one — `try/finally` around `stream.close()` and `em.close()`, and a pool leak-detection threshold (HikariCP `leakDetectionThreshold`) that logs a stack trace when a connection is held too long, which turns "intermittent at 2am" into "here is the exact code path that leaked." The lesson I emphasize is that you resolve it by *measuring the resource* (open cursors / active connections over time) to classify leak-vs-retention first, because the two causes have opposite fixes (close-your-resources vs lower-your-cache-and-pool-product), and guessing wrong wastes a maintenance window.

#### Q70. [Practical] How do you safely run bulk updates/deletes (`UPDATE`/`DELETE` JPQL) in EclipseLink, and what is the cache and lifecycle gotcha?
Bulk operations — `em.createQuery("UPDATE Order o SET o.status = :s WHERE o.created < :d").executeUpdate()` — are essential for performance because they push the work to the database in a single statement instead of loading thousands of entities into the persistence context and updating them one by one. But they have a sharp edge that bites teams: **bulk JPQL bypasses the persistence context and the lifecycle entirely**. The `UPDATE`/`DELETE` runs as direct SQL, so it does *not* load entities, does *not* fire `@PreUpdate`/`@PreRemove` callbacks or Bean Validation, does *not* respect cascade rules, and — most importantly in EclipseLink — does *not* update the L1 context or the L2 shared cache.

```java
em.getTransaction().begin();
int updated = em.createQuery(
    "UPDATE Order o SET o.status = :s WHERE o.created < :cutoff")
    .setParameter("s", Status.ARCHIVED)
    .setParameter("cutoff", cutoff)
    .executeUpdate();                          // direct SQL; cache now STALE for those rows
em.getTransaction().commit();
```

The gotchas, in order of how often they cause bugs: **(1) stale cache** — any of those `Order` rows still in the L2 shared cache (or L1 if loaded earlier in the same transaction) now hold the old status, so a subsequent `find()` returns the pre-update value. EclipseLink lets you invalidate as part of the bulk operation with the hint `eclipselink.query-results-cache.invalidate` / `eclipselink.cache-usage` controls, and the robust pattern is to explicitly invalidate the affected class after the bulk DML:

```java
query.setHint("eclipselink.batch.writing", ...);
// after commit, evict so the shared cache re-reads:
emf.getCache().evict(Order.class);
em.clear();   // also drop the now-stale L1 entities
```

**(2) skipped callbacks/validation** — if your `@PreUpdate` maintains an `updatedAt` or audit field, a bulk update silently skips it, so the audit trail is wrong; you must either set those columns explicitly in the `UPDATE` JPQL or accept that bulk operations are outside the lifecycle. **(3) ignored cascade and orphan rules** — a bulk `DELETE` does not cascade to children the way `em.remove()` does, so you can leave orphaned child rows or hit foreign-key violations; you must delete children explicitly (children-before-parents) or rely on database `ON DELETE CASCADE`. The senior framing: bulk operations are a deliberate trade — you give up the unit-of-work guarantees (lifecycle, cascade, automatic cache coherence) in exchange for set-based performance, so you must *manually* restore the parts you still need (invalidate the cache, clear the context, handle children, set audit columns in SQL), and you isolate bulk operations to their own transaction so they don't interleave with entity-level work that assumes cache coherence.

#### Q71. [Practical] Design the production deployment of EclipseLink weaving in a CI/CD pipeline. What can go wrong and how do you make it reliable?
Weaving is the part of EclipseLink that most often "works on my machine, fails in prod," so the production design is to **statically weave at build time** and remove the runtime agent dependency entirely. The CI/CD setup wires the `eclipselink-staticweave` task into the build so the compiled `.class` files are woven before packaging, which means the deployed artifact contains already-instrumented entities and the runtime needs no `-javaagent` and no `LoadTimeWeaver`:

```xml
<!-- Maven: weave the compiled classes in-place after compile -->
<plugin>
  <groupId>com.ethlo.persistence.tools</groupId>
  <artifactId>eclipselink-maven-plugin</artifactId>
  <executions>
    <execution>
      <phase>process-classes</phase>
      <goals><goal>weave</goal></goals>
    </execution>
  </executions>
</plugin>
```
```bash
# Verify weaving actually happened (woven classes carry injected _persistence_* members):
javap -p target/classes/com/shop/Order.class | grep _persistence_   # should print injected fields/methods
```

The failure modes to design against: **(1) forgetting to re-weave** when entities change — if the weave step is skipped or cached, the deployed entity is un-woven and lazy to-one becomes silently eager, a correctness/performance regression with no error; the mitigation is to make weaving part of the standard build lifecycle (not a manual step) and to add a smoke test that asserts `_persistence_` members exist (the `javap` check above, or a reflection assertion). **(2) double weaving** — applying both static weaving *and* a runtime agent can corrupt classes; pick one, and in containers ensure no stray `-javaagent:eclipselink.jar` lingers. **(3) classpath/version skew** — the weaver version must match the runtime EclipseLink version, or the injected hooks may not align; pin both to the same version via dependency management. **(4) GraalVM native image** — dynamic weaving is impossible under the closed-world model, so static weaving is *mandatory*, plus reachability metadata for the entities EclipseLink reads via reflection.

The reliability principle is to **shift the variability left**: weaving done at build time is deterministic and verifiable in CI, whereas runtime weaving depends on JVM flags, agent ordering, and container configuration that drift between environments. I add a CI gate that fails the build if any entity class lacks woven instrumentation, run the integration suite against the woven artifact (not the raw compiled classes), and keep dev consistent by either statically weaving locally too or documenting the agent so a developer never sees different lazy behavior than production. The interview point is recognizing that weaving is a *build artifact property*, and treating it as one (verified, versioned, gated) is what makes it reliable.

#### Q72. [Practical] Your service intermittently throws `TransactionRequiredException` or silently fails to persist. How do you debug transaction/EM configuration issues in EclipseLink?
`TransactionRequiredException` (thrown when you call `flush()`, `merge()`, or `persist()` outside an active transaction) and its sibling "the data just didn't save" are almost always **transaction-boundary or transaction-type mismatches**, not EclipseLink bugs. The diagnostic question to ask first is: *what owns the transaction, and is the operation actually inside it?* The two big classes are RESOURCE_LOCAL vs JTA confusion, and (in Spring) `@Transactional` not actually applying.

```
Cause                                            Symptom                          Check
------------------------------------------------ -------------------------------- ----------------------------
JTA persistence-unit, called getTransaction()    IllegalStateException            transaction-type in persistence.xml
RESOURCE_LOCAL, forgot begin()/commit()          changes never persisted          is there a begin/commit pair?
Spring @Transactional on a private/self-call     no transaction, silent no-op     proxy can't intercept -> public + external call
Read-only tx, then a write                       write silently dropped/rolled    @Transactional(readOnly=false)
Wrong EM (new one) inside @Transactional          changes in a different context   inject EM via @PersistenceContext, not new
```

The most common real bug in Spring apps is **self-invocation**: a method annotated `@Transactional` that is called from another method *in the same class* bypasses the Spring proxy, so the transaction advice never runs, `persist()` happens with no active transaction, and you get `TransactionRequiredException` or a silent no-commit. The fix is to move the transactional method to another bean (so the call goes through the proxy) or use the self-injection/`TransactionTemplate` pattern. Equally common is **creating a fresh `EntityManager`** inside a `@Transactional` method instead of using the injected `@PersistenceContext` EM — the new EM is not joined to the Spring-managed transaction, so its writes are isolated and lost.

To debug it concretely, I turn on transaction logging (`eclipselink.logging.level.transaction=FINER` shows begin/commit/rollback events, and Spring's `org.springframework.transaction` logger at DEBUG shows where transactions are created and why they are not) and trace whether a transaction actually wraps the operation. For plain JPA, the discipline that prevents the whole class of bugs is the canonical `try { begin(); ...; commit(); } catch { rollback(); } finally { em.close(); }` block, never sharing an EM across requests. The senior framing: this error is a *boundary* problem — the operation and the transaction are not in the same scope — so the fix is structural (correct transaction-type, correct proxy boundary, correct EM) rather than any EclipseLink property; reaching for `em.joinTransaction()` or sprinkling `flush()` calls treats the symptom, not the missing boundary.

#### Q73. [Practical] How do you migrate a `javax.persistence` (EclipseLink 2.7) application to `jakarta.persistence` (EclipseLink 3.x/4.x)? What is the realistic playbook?
The `javax.persistence` → `jakarta.persistence` move (EclipseLink 2.7 → 3.0) is a *namespace* migration, not a behavioral upgrade, but it is hard precisely because it is all-or-nothing: you cannot mix a `javax`-compiled entity with a `jakarta` provider in one persistence unit, and the transitive dependency graph (app server, JAX-RS, validation, transactions) all flips namespaces together. The realistic playbook is mechanical but must be done in the right order:

```
1. Inventory: every javax.* import that moved to jakarta.* --
   javax.persistence.*  -> jakarta.persistence.*
   javax.validation.*   -> jakarta.validation.*
   javax.transaction.*  -> jakarta.transaction.*
   javax.xml.bind.*      -> jakarta.xml.bind.*   (MOXy / JAXB)
2. Bump dependencies to Jakarta-era versions IN LOCKSTEP (provider + API + container + libs).
3. Update XML namespaces in persistence.xml / orm.xml:
      https://jakarta.ee/xml/ns/persistence  (version 3.0+)
4. Rebuild; re-run STATIC WEAVING with the new EclipseLink version.
5. Run the full integration suite against a real DB.
```

The single most useful tool is the **Eclipse Transformer (or the OpenRewrite `jakarta` recipes)**, which mechanically rewrites bytecode/source imports and XML namespaces, so you do not hand-edit thousands of imports:

```bash
# Eclipse Transformer: rewrite an artifact's javax.* references to jakarta.*
java -jar org.eclipse.transformer.cli.jar app-javax.jar app-jakarta.jar
```

The realistic gotchas beyond the rename: **(1) third-party libraries** that have not released Jakarta versions become hard blockers — you must upgrade or replace them, and a single un-migrated library can hold up the whole move because you cannot have both namespaces on the classpath for the same API. **(2) Weaving must be redone** with the matching EclipseLink version (a 2.7-woven class against a 3.x runtime is a mismatch). **(3) XML namespace bumps** in `persistence.xml`/`orm.xml` are easy to forget and cause cryptic bootstrap failures. **(4) Property names** mostly stayed `eclipselink.*` but the standard ones moved from `javax.persistence.*` to `jakarta.persistence.*` (e.g., `jakarta.persistence.jdbc.url`).

The migration strategy I advocate is to **do the namespace flip as an isolated, mechanical change** with no behavioral edits mixed in — run the transformer, bump dependencies in lockstep, re-weave, and prove equivalence with the integration suite — so that if something breaks you know it was the rename, not a logic change. Then, *separately*, take any version-feature upgrades (EclipseLink 4.0's UUID generation, new datetime/numeric JPQL functions). Bundling the rename with feature changes is what turns a one-day mechanical migration into a multi-week debugging slog, so keeping the two changes independent and individually verifiable is the senior move.

### 🔴 Expert — extended

#### Q74. [Practical] Design a zero-downtime schema migration strategy for an EclipseLink service running multiple instances behind a load balancer. What ordering and compatibility rules apply?
Zero-downtime schema change with a clustered EclipseLink app is fundamentally about **maintaining forward and backward compatibility between the running code and the schema during the rollout window**, because for a period old instances (old code) and new instances (new code) run against the *same* database. The governing technique is **expand/contract (parallel change)**: never make a single change that the old code cannot tolerate; instead split every breaking change into additive-then-cleanup phases across multiple deploys.

```
EXPAND (deploy 1):   add new nullable column / new table / new index (CONCURRENTLY)
                     -- old code ignores it, new code can start writing it
MIGRATE (background): backfill data into the new shape; dual-write from new code
CONTRACT (deploy 2):  once no running code reads the old shape, drop it / add NOT NULL
```

Concretely for common changes: **renaming a column** becomes "add new column → dual-write both → backfill → switch reads → drop old column" across two or three releases, never a single `RENAME` (which instantly breaks every old instance). **Adding a NOT NULL column** becomes "add nullable + default → backfill → enforce NOT NULL later." **Adding an index** uses `CREATE INDEX CONCURRENTLY` (Postgres) so it does not lock the table during the build. Migrations are driven by **Flyway/Liquibase run independently of the app** (never `eclipselink.ddl-generation`), and run *before* the new code that depends on the expanded schema is deployed.

The EclipseLink-specific concerns layered on top: **(1) the L2 cache across instances** — a schema/data change applied out-of-band leaves cached entities stale on every node, so the rollout must account for cache invalidation (TTL window, or an explicit evict, or rolling-restart the instances so their caches rebuild). **(2) sequence/preallocation** — if you change ID generation, remember EclipseLink preallocates blocks, so old and new instances must agree on `allocationSize` vs the database `INCREMENT BY` or you get duplicate-key collisions during the mixed window. **(3) entity mapping vs schema skew** — during expand, the new entity mapping may reference a column that only exists after the migration ran, so deploy order is *migrate-then-code*, and the new mapping must remain tolerant of the old shape if a rollback is needed. **(4) optimistic locking** — adding a `@Version` column to a hot table mid-flight needs care: old instances that don't know about the version column must not be writing those rows, or you reintroduce lost updates.

The strategy I would ship: every schema change passes a review gate asserting it is backward-compatible with the currently-deployed code; migrations run as a separate pipeline step before the rolling deploy; the app uses `ddl-generation=none` so it never mutates schema itself; and we keep the expand and contract in *separate releases* with a soak period between, so a problem in the new code can be rolled back without first having to reverse a destructive schema change. The interview-grade insight is that zero-downtime is a *sequencing and compatibility* discipline, not a feature of the ORM — EclipseLink's job is just to not surprise you (cache staleness, sequence collisions) during the window where two code versions coexist.

#### Q75. [Practical] Under high write concurrency you see frequent database deadlocks attributed to EclipseLink. How do you diagnose and reduce them?
Deadlocks under concurrency are rarely an EclipseLink bug per se — they are an *ordering* problem where two transactions acquire row/page locks in opposite orders — but EclipseLink's behavior (flush ordering, batching, cascade, and the cache) shapes how often they occur and how you fix them. The diagnosis starts at the database, not the application: capture the **deadlock graph** from the DB (Postgres `log_lock_waits` + the deadlock detail in the log; Oracle the trace file / `v$session` blocking chains; SQL Server the deadlock XML), because it names the exact two statements and the two resources locked in conflicting order. Without that graph you are guessing.

```
Diagnosis:
1. DB deadlock graph -> which two statements, which two rows/tables, what lock modes?
2. EclipseLink SQL log (FINE) at the same time -> map those statements back to entity ops
3. Identify the inconsistent acquisition order:
     Tx A: lock parent(1) then child(9)
     Tx B: lock child(9) then parent(1)   <- opposite order = deadlock
```

The reduction strategies, in priority order: **(1) consistent access ordering** — make all transactions touch rows in the same canonical order (e.g., always lock accounts by ascending id when transferring between two), which eliminates the cycle entirely; this often means sorting a batch by primary key before updating. EclipseLink's commit ordering helps within a single flush (parents-before-children) but cannot coordinate ordering *across* concurrent application transactions, so you must impose order in application logic. **(2) shorten transactions** — long transactions hold locks longer, widening the window for a cycle; move non-DB work (calls to other services, heavy computation) outside the transaction, and avoid user think-time inside a transaction. **(3) reduce lock footprint** — prefer optimistic locking (`@Version`, which takes no DB locks across think-time) over pessimistic where contention is low; when you must use `PESSIMISTIC_WRITE`, set a lock timeout (`eclipselink.pessimistic-lock.timeout`) so a blocked transaction fails fast and retries instead of deadlocking.

EclipseLink-specific contributors to investigate: **batch writing** can group statements such that the lock acquisition pattern differs from what you'd naively expect — disabling it temporarily can confirm whether reordering inside a batch is implicated; **cascade operations** (a cascaded delete touching child tables) extend the set of rows a transaction locks, so an aggressive `CascadeType.ALL` can turn a narrow operation into a wide locking footprint that collides with others; and the **shared cache + `PESSIMISTIC_WRITE`** interaction means EclipseLink refreshes the locked row from the DB, which is correct but means the lock-acquiring `SELECT ... FOR UPDATE` participates in the ordering you must keep consistent. The fix I would deploy: impose a deterministic resource-ordering convention in the service layer (sort-by-id before multi-row writes), shorten and de-scope transactions, prefer optimistic locking with a bounded jittered retry for the rare conflict, and reserve pessimistic locks (with timeouts) for the genuinely hot contended path — then verify by re-running the load test and watching the DB deadlock counter drop, because the only proof a deadlock fix worked is the deadlock rate measured under the same load.

#### Q76. [Practical] How do you profile and reduce EclipseLink's startup / EntityManagerFactory bootstrap time for a large entity model in a serverless or fast-restart context?
EMF bootstrap is the expensive, one-time cost in EclipseLink: parsing `persistence.xml` and `orm.xml`, scanning and processing every entity's annotations into `ClassDescriptor`s, resolving relationships and inheritance, validating named queries, weaving (if dynamic), and initializing the connection pool. For a large model (hundreds of entities) this can be seconds — irrelevant for a long-lived server but catastrophic for serverless cold starts or anything that restarts frequently. The first step is to **measure where the time goes**: enable `eclipselink.logging.level=FINE` during boot and time the phases, or profile the bootstrap with a sampling profiler — you almost always find descriptor processing, named-query validation, and weaving dominate, not connection setup.

The reduction levers:

```
Lever                                   Effect on bootstrap
--------------------------------------- ---------------------------------------------------
Static weaving (not dynamic agent)      removes load-time weaving cost from startup
Defer connection acquisition            don't open DB connections until first query
eclipselink.deploy-on-startup=false     lazy-deploy the persistence unit on first use
Trim the entity model / split PUs       fewer descriptors to build per unit
Canonical/static metamodel pregenerated avoid runtime metamodel generation cost
GraalVM native image (AOT)              push descriptor/metadata work to build time
```

For **serverless specifically**, the dominant strategy is to do as much as possible at *build time* and to *keep the EMF warm*. Static weaving is mandatory (no agent at runtime). You want the connection pool to acquire lazily so a function that doesn't hit the DB doesn't pay connection setup, and you want the EMF created *once per container instance* and reused across invocations (in the global/static scope that survives warm invocations) rather than per request — rebuilding the EMF per invocation is the single biggest cold-start mistake. If you control the platform, **GraalVM native image** with AOT moves descriptor/metadata processing into the binary so the runtime cost is near-zero, at the cost of build complexity and reflection configuration.

The architectural framing I would give: EclipseLink, like every JPA provider, front-loads a lot of work into EMF construction to make per-request work cheap — that bargain is great for servers and bad for ephemeral compute. So in serverless you (a) push the front-loaded work earlier still (build time, via static weaving and AOT), (b) amortize what remains by warming and reusing the EMF across the container's lifetime, and (c) defer anything not needed immediately (lazy connection acquisition, lazy deployment). I would also seriously question whether a heavy JPA provider is the right tool for a latency-critical, frequently-cold function at all — sometimes the right answer is a lightweight JDBC/SQL mapper for that specific path and keep EclipseLink for the long-lived services, because no amount of tuning makes a full ORM bootstrap free.

#### Q77. [Practical] How do you secure an EclipseLink application against SQL injection and data leakage, and where are the EclipseLink-specific risk points?
EclipseLink, used idiomatically, is *safe by construction* against SQL injection because JPQL and Criteria compile to **parameterized prepared statements** — bind values never become part of the SQL text. The injection risk appears precisely where developers leave the safe path: **string-concatenated native SQL or dynamically-built JPQL**. The rule is absolute — never concatenate user input into a query string; always bind:

```java
// VULNERABLE: user input concatenated into native SQL
em.createNativeQuery("SELECT * FROM users WHERE name = '" + input + "'");   // injection

// SAFE: parameter binding (JPQL or native)
em.createQuery("SELECT u FROM User u WHERE u.name = :n").setParameter("n", input);
em.createNativeQuery("SELECT * FROM users WHERE name = ?").setParameter(1, input);
```

The subtle EclipseLink-specific injection vectors: **(1) dynamic ORDER BY / column names** — you cannot bind an identifier (a column or table name) as a parameter, so code that builds `ORDER BY " + sortColumn` from user input is injectable even if the values are bound; the fix is to validate the column against an allow-list of known-safe names, never to pass it through. **(2) the native `Expression` API and report queries** that take function expressions — building expressions from raw user strings can reach the same hazard; keep user input in *values*, not in *structure*. **(3) `eclipselink.jdbc.bind-parameters=false`** — turning binding off (sometimes done to coax a better query plan) means literals are inlined into SQL, which both defeats statement caching *and* reopens the injection surface, so it must be used surgically and never with un-validated input.

The **data-leakage** risks are equally EclipseLink-flavored. The on-by-default **L2 shared cache + multitenancy** is the headline: caching tenant-scoped entities in the SHARED cache lets one tenant read another's cached objects — you must use `ISOLATED` cache for tenant data and `@Multitenant(includeCriteria=true)` so the tenant predicate is always appended. **SQL logging with parameters** (`eclipselink.logging.parameters=true`) prints every bind value, including PII and secrets, into logs — fine for a controlled debugging session, a leak if left on in production. And **detached entities serialized to clients** can over-expose fields if you ship the entity instead of a curated DTO, so the security-and-leakage discipline is to map to DTOs at the boundary and never serialize the entity graph wholesale. The interview-grade summary: stay on the parameterized path (JPQL/Criteria/bound native), allow-list anything that must be structural (sort columns), isolate tenant data out of the shared cache, keep parameter logging off in production, and project to DTOs — the framework gives you safety by default, and every breach is some deliberate step off that default.

#### Q78. [Practical] How would you implement and operate read/write splitting (routing reads to replicas) with EclipseLink, and what are the consistency hazards?
Read/write splitting — sending writes to the primary and routing read-only queries to one or more replicas — is a common scaling move, and EclipseLink supports it more natively than many ORMs through its **dual read/write connection pools** and exclusive-connection semantics. You can configure separate pools so that read-only operations borrow from a replica-pointed pool while transactional writes use the primary pool, and EclipseLink will use a read connection for queries that do not need the transactional (write) connection:

```xml
<!-- write pool -> primary; read pool -> replica -->
<property name="eclipselink.jdbc.connection_pool.default.url"      value="jdbc:postgresql://primary/shop"/>
<property name="eclipselink.jdbc.connection_pool.read.url"         value="jdbc:postgresql://replica/shop"/>
<property name="eclipselink.jdbc.connection_pool.read.min"         value="5"/>
<property name="eclipselink.jdbc.connection_pool.read.max"         value="30"/>
<property name="eclipselink.jdbc.exclusive-connection.mode"        value="Transactional"/>
```

The mechanism: EclipseLink can serve read-only queries (especially `READ_ONLY`-hinted ones outside a write transaction) from the read pool and only acquire the write/primary connection when DML or a transaction needs it (deferred/lazy write-connection acquisition). At the application layer, the cleaner and more common approach in Spring is a routing `DataSource` (`AbstractRoutingDataSource`) that picks primary vs replica based on the transaction's read-only flag (`@Transactional(readOnly=true)` → replica), with EclipseLink simply borrowing from whichever the router returns — this keeps the routing policy in one place and works regardless of provider.

The consistency hazards are the heart of the answer and are *replication-lag* problems, not EclipseLink problems. Replicas are asynchronously behind the primary, so the classic bug is **read-your-writes violation**: a user submits a change (write to primary), is redirected to a page that reads it (from a lagging replica), and sees the *old* value — looking like the save failed. Mitigations: route reads that must see just-written data to the primary (sticky-to-primary for a short window after a write, or keep the read inside the same write transaction so it uses the primary connection), or use a replica with bounded/monotonic lag guarantees. A second hazard is the **L2 cache layered on top**: if writes go to primary and the cache is populated from replica reads, the cache can be doubly stale (replica lag *plus* cache TTL), so for read-after-write-sensitive entities I disable caching or route their reads to primary. A third is **transaction correctness** — a transaction that does a read then a write must not split across replica-then-primary in a way that reads stale data and writes based on it; keeping any read-then-write logic on the primary connection (it is a write transaction, so it should be) avoids this.

The operating discipline: explicitly classify each read as "tolerates lag" (analytics, listings, search → replica) versus "needs read-your-writes" (the screen shown immediately after a save → primary), make `@Transactional(readOnly=true)` the routing signal so it is visible in code, monitor replica lag and have a fallback to primary when lag exceeds a threshold, and be deliberate about the cache so you don't stack two staleness sources. The senior point is that read/write splitting trades stronger consistency for read throughput, and the whole job is making that trade *consciously per query* rather than globally — EclipseLink gives you the routing machinery, but the consistency decisions are yours.

#### Q79. [Practical] A heap dump shows EclipseLink retaining far more memory than expected. Walk through identifying whether it is the L2 cache, the UnitOfWork, or a leak — and fixing it.
Memory growth attributed to EclipseLink has three distinct root causes with three different fixes, and a heap dump (analyzed in Eclipse MAT or similar) tells them apart by *what is dominating the retained-heap tree*. The investigation is: take the dump under the problematic condition, open the dominator tree, and ask which EclipseLink structure is at the top.

```
Dominator in heap dump            Root cause                         Fix
--------------------------------- ---------------------------------- ----------------------------------------
IdentityMap / CacheKey full of    L2 shared cache retaining too much @Cache(type=SOFT/WEAK), bound size,
  entity instances (held strongly)   (FULL/HARD type, unbounded size)   ISOLATED/NONE for volatile entities
UnitOfWorkImpl / cloned objects   per-transaction context too large  READ_ONLY hint; em.clear() in batch loops;
  growing during one transaction                                       fetch groups; smaller batches
Application object holding an EM  / leak: EM never closed, or a       close EM in finally; close cursors;
  or a CursoredStream             cursored stream not closed           don't cache managed entities long-term
```

**Case 1 — the L2 cache.** If the dominator is the identity map full of strongly-referenced entities, the `@Cache(type=...)` is too retentive. `FULL`/`HARD_WEAK` keep strong references that never yield to GC, and an unbounded `size` lets a large or unbounded working set accumulate. The fix is to switch hot, high-cardinality entities to `SOFT` (survives until memory pressure) or `WEAK` (collectible once unreferenced) and to bound `size`, or to set volatile/large entities to `ISOLATED`/`NONE` so they are not retained in the shared cache at all. The trade-off is more cache misses and reloads — which is the correct trade when the alternative is an OOM.

**Case 2 — the UnitOfWork.** If memory balloons *during a single long transaction* and is released at commit, the persistence context is accumulating clones: a query (or loop) that reads or persists thousands of rows registers a managed clone (and a backup clone for change detection) per row. The fixes are read-side (`QueryHints.READ_ONLY=true` so results bypass UoW registration, fetch groups so each clone is smaller) and write-side (`em.flush(); em.clear();` every N rows in a batch loop to drain the context). This is the most common "the import job OOMs at row 800,000" incident.

**Case 3 — a true leak.** If retained memory grows *across* requests and never releases, something holds a reference that should be transient: an `EntityManager` stored in a field or singleton (its L1 context never drains), a `CursoredStream` never closed (it pins rows, the cursor, and a connection), or application code caching managed entities in a static map (which then transitively pins the whole graph and the cache). The fix is lifecycle hygiene — close the EM in a `finally`, close cursors, and never retain managed entities beyond their transaction; if you need long-lived data, detach and copy it (or store a DTO).

The disciplined process I would describe: reproduce under load, capture the dump, read the dominator tree to classify (cache vs UoW vs leak), fix the *dominant* cause first, and re-measure before touching anything else — because the three causes have different and sometimes conflicting fixes (e.g., a bigger cache helps throughput but worsens Case 1), and changing several things at once destroys your ability to attribute the improvement. The interview-grade signal is naming the three causes precisely and tying each to a heap-dump signature, rather than reflexively "lowering the cache size," which only helps one of the three.

#### Q80. [Practical] How do you handle database connection failures, failovers, and stale connections gracefully in an EclipseLink production deployment?
Connection resilience is mostly an operational concern that EclipseLink delegates to the connection layer, so the senior answer is "externalize pooling to a battle-tested pool and configure it correctly, then handle the residual EclipseLink-level behaviors." With EclipseLink's internal pool you can set validation and retry, but in production you almost always front it with **HikariCP** (or the container's pool), because Hikari's connection validation, leak detection, and fast-failover behavior are far more mature than EclipseLink's internal pool:

```
Concern                  HikariCP setting / approach
------------------------ -----------------------------------------------------------
Detect stale/dead conn   connectionTestQuery / JDBC4 isValid(); validationTimeout
Recover from failover    maxLifetime < DB/LB idle timeout; aggressive eviction
Fail fast on DB down     connectionTimeout (don't block requests indefinitely)
Detect leaks             leakDetectionThreshold (logs stack trace of long-held conn)
Avoid thundering reconnect minimumIdle + steady pool; backoff at the app layer
```

EclipseLink does add a couple of relevant knobs: `eclipselink.jdbc.connections.wait-timeout` bounds how long a thread waits for a connection from the *internal* pool, and EclipseLink can be configured to **retry on certain communication exceptions** — historically via `eclipselink.jdbc.connection-health-validation` and the connection-validation/ping behavior, so a transaction that hits a dead connection can reconnect rather than fail. But the durable pattern is to let the pool handle validation/eviction (so dead connections are removed before EclipseLink ever borrows them) and to make the application **idempotent and retry-aware** for the failures that still leak through.

The failover specifics worth stating: when a primary database fails over (managed DB, RDS, a VIP/proxy), in-flight connections become invalid; you want `maxLifetime` set *below* any load-balancer/DB idle timeout so the pool proactively recycles connections rather than handing out a connection the DB has silently dropped, and you want validation on borrow so a stale connection is detected and replaced transparently. For the brief window during failover where new connections cannot be established, requests should **fail fast** (short `connectionTimeout`) and the application should retry at a higher level with backoff, rather than every thread blocking on connection acquisition and exhausting request threads (a cascading failure). For multi-AZ/replica setups, use a smart JDBC URL or proxy (e.g., the driver's failover support, or a proxy like PgBouncer/RDS Proxy) so reconnection targets the new primary automatically.

The principle I would articulate: connection resilience is a *layered* responsibility — the pool detects and replaces bad connections, the driver/proxy handles topology and reconnection, and the application owns idempotency and bounded retry for the failures that survive both. EclipseLink's role is mostly to *not get in the way* (don't pin connections via cursors during failover windows, don't let the statement cache hand back statements bound to dead connections), and to optionally retry transient communication errors. I would test this explicitly by killing the database / forcing a failover in a staging environment under load and verifying the app recovers within the pool's eviction window without manual restart — because connection-resilience config that has never been tested against an actual failover is config you don't actually have.

#### Q81. [Coding] Implement a fetch-group + read-only reporting query for a wide entity, and explain the production payoff and the trap.
Reporting and listing endpoints over wide tables are where EclipseLink's fetch groups and read-only hints pay off the most: a 60-column entity loaded for a list view that only displays 4 columns wastes I/O, network, and heap on 56 unused columns per row, and registers a full clone per row in the UnitOfWork. A **fetch group** loads only the named columns (partial entity), and **read-only** skips UnitOfWork registration entirely:

```java
public List<Order> findOrderSummaries(EntityManager em, int limit) {
    TypedQuery<Order> q = em.createQuery(
        "SELECT o FROM Order o ORDER BY o.created DESC", Order.class);
    q.setMaxResults(limit);
    // Load only the columns the summary view needs (partial entity).
    q.setHint(QueryHints.FETCH_GROUP_ATTRIBUTE, "id");
    q.setHint(QueryHints.FETCH_GROUP_ATTRIBUTE, "customer");
    q.setHint(QueryHints.FETCH_GROUP_ATTRIBUTE, "total");
    q.setHint(QueryHints.FETCH_GROUP_ATTRIBUTE, "created");
    // Don't register/clone in the UnitOfWork; serve straight from the shared cache.
    q.setHint(QueryHints.READ_ONLY, true);
    return q.getResultList();
}
```

**Why it works:** the fetch group makes EclipseLink emit `SELECT id, customer, total, created FROM orders ...` instead of `SELECT *`, so the database reads and ships only four columns; `READ_ONLY` returns shared-cache instances without the per-row clone-and-backup that change tracking needs. **Time:** one SQL statement; the win is in bytes transferred and objects allocated, not statement count. **Space:** O(rows × 4 columns) instead of O(rows × all columns), and no UnitOfWork clone per row — on a list endpoint serving thousands of rows this is the difference between a tidy heap and GC pressure.

**The trap (and edge cases):** a fetch-group entity is a *partial* object — touching an attribute *not* in the group triggers a lazy fetch of the rest of the row (a per-row query, i.e., a hidden N+1) if you are still in a session, or fails/returns default if detached. So a downstream layer that innocently calls `order.getShippingAddress()` on a summary object either silently issues extra queries or sees a null — both bugs. The discipline is to (1) treat fetch-group objects as *projection-shaped* and never let them flow to code expecting a full entity, (2) prefer a **constructor-expression DTO** (`SELECT new com.shop.OrderSummaryDto(o.id, o.customer, o.total, o.created) FROM Order o`) when the result is purely for read/display, because a DTO is *physically incapable* of exposing un-fetched fields and carries no partial-entity hazard, and (3) reserve fetch groups for cases where you genuinely need a (partial) managed entity. The additional `READ_ONLY` trap is the usual one — never mutate a read-only result, because it *is* the shared-cache instance and mutating it corrupts state other threads see. In production I default reporting/list endpoints to **DTO projections** (safest), and use fetch-group partial entities only when a managed-but-light entity is specifically required.

#### Q82. [Practical] How do you set query timeouts, lock timeouts, and isolation levels in EclipseLink, and why do these matter operationally?
Unbounded queries and locks are a top cause of production outages — one runaway report or one stuck pessimistic lock can exhaust the connection pool and cascade into a full outage. EclipseLink lets you bound each of these, and the operational skill is knowing which knob applies where. **Query timeout** caps how long a single statement may run before EclipseLink asks the JDBC driver to abort it:

```java
query.setHint(QueryHints.QUERY_TIMEOUT, 5000);              // milliseconds, per query
query.setHint(QueryHints.QUERY_TIMEOUT_UNIT, TimeUnit.MILLISECONDS.name());
// or globally:
// <property name="eclipselink.query.timeout" value="5000"/>
```

**Pessimistic lock timeout** bounds how long a `SELECT ... FOR UPDATE` will wait for a contended row before giving up rather than blocking indefinitely:

```java
em.find(Account.class, id, LockModeType.PESSIMISTIC_WRITE,
        Map.of("jakarta.persistence.lock.timeout", 3000));   // ms; 0 = NOWAIT on many DBs
```

**Isolation level** is set at the connection/transaction layer (EclipseLink can request a JDBC isolation via the data source or `eclipselink.jdbc.connection.isolation`), and the operational point is that most OLTP systems run `READ_COMMITTED` and rely on optimistic locking for consistency, escalating to `REPEATABLE_READ`/`SERIALIZABLE` only for specific invariants — because higher isolation increases locking and abort rates.

Why these matter together operationally: the failure mode they prevent is **resource starvation under pathology**. Without a query timeout, a single mis-planned query (a missing index after a data shift) holds its connection for minutes; with enough concurrent victims, every pooled connection is busy and healthy requests can't get one — the database is fine but the app is down. Without a lock timeout, a `FOR UPDATE` on a hot row behind a slow transaction blocks every other writer indefinitely, again pinning connections. The discipline is **defense in depth**: a per-query timeout sized to the SLA (reads should be fast; a 5s cap surfaces the slow query as an error you can alert on rather than a silent hang), a lock timeout so contention fails fast into a bounded retry, a pool acquisition timeout so threads don't block forever waiting for a connection, and a database-side `statement_timeout` as a backstop in case the app-level timeout is bypassed. The senior framing: timeouts convert *unbounded hangs* (which cascade) into *bounded errors* (which you can retry, alert on, and reason about) — you would rather fail one request fast than take the whole service down slowly.

#### Q83. [Practical] How do you implement soft-delete (logical delete) in EclipseLink, and what are the trade-offs versus a `@Where`-style filter?
Soft-delete — marking a row `deleted=true` instead of physically removing it — is common for audit, recoverability, and referential safety, but JPA has no standard annotation for it, so EclipseLink's approach is the `DescriptorCustomizer` with an **additional join expression**, which appends a `WHERE deleted = false` predicate to *every* read of that entity (EclipseLink's analog to Hibernate's `@Where`):

```java
public class SoftDeleteCustomizer implements DescriptorCustomizer {
    public void customize(ClassDescriptor descriptor) {
        ExpressionBuilder eb = descriptor.getQueryManager()
                .getAdditionalJoinExpression() != null
                ? new ExpressionBuilder() : new ExpressionBuilder();
        descriptor.getQueryManager().setAdditionalJoinExpression(
            eb.get("deleted").equal(false));   // every SELECT gets AND deleted = false
    }
}
```
```java
@Entity @Customizer(SoftDeleteCustomizer.class)
public class Document {
    @Id private Long id;
    private boolean deleted;   // the soft-delete flag
}
```

You then "delete" by setting the flag (`doc.setDeleted(true)`) rather than `em.remove()`, and the additional join expression transparently hides flagged rows from all subsequent reads. The portable alternative — without an EclipseLink customizer — is to **never query the entity directly** but always through a repository method that adds `AND e.deleted = false`, or to use a database **view** that filters deleted rows and map the entity to the view; both keep the logic explicit at the cost of discipline.

The trade-offs are real and worth naming. **Pros of the descriptor filter:** it is centralized and impossible to forget — every query, including lazy-loaded associations and `find()`, gets the predicate, so you cannot accidentally show deleted rows. **Cons / traps:** (1) **unique constraints** break, because a soft-deleted "duplicate" still occupies the unique value — you typically need partial unique indexes (`WHERE deleted = false`) or to mangle the unique column on delete. (2) **The filter applies everywhere, including where you don't want it** — admin "show deleted" screens, restore flows, and integrity checks need a way to *bypass* the predicate, which means the global filter forces an escape hatch (a separate native query or a different descriptor). (3) **Foreign keys and cascades** get subtle — a soft-deleted parent with live children, or counting/aggregating that must decide whether to include deleted rows. (4) **The L2 cache** must be consistent with the filter (a soft-deleted entity should be evicted or refreshed so it isn't served as live). (5) **Table bloat** — rows never leave, so high-churn tables grow unbounded and need periodic hard-purge of old soft-deleted rows. The senior recommendation: soft-delete is a *domain* decision (do we actually need recoverability/audit?), and if yes, implement it deliberately with partial unique indexes, an explicit bypass path for admin/restore, a purge job for old logical-deletes, and cache invalidation on the flag flip — rather than reflexively adding a global filter and discovering these issues in production.

#### Q84. [Practical] EclipseLink generates a huge `IN (?, ?, ?, ...)` clause that fails or performs terribly. What is happening and how do you fix it?
Large `IN` lists arise naturally from EclipseLink's `BatchFetchType.IN`, from `WHERE e.id IN :collection` queries, and from cascaded operations over big collections, and they hit two distinct walls. The first is a hard limit: many databases cap the number of bind parameters or `IN` elements — **Oracle's classic 1000-element `IN` limit** (`ORA-01795`), SQL Server's ~2100 parameter cap, PostgreSQL's practical limits — so a query with 5,000 ids throws an error outright. The second is *performance*: even where it's allowed, a giant parameterized `IN` defeats statement caching (every distinct list size is a different statement to prepare and cache) and can produce a poor plan.

```
Symptom                          Cause                          Fix
-------------------------------- ------------------------------ ----------------------------------------
ORA-01795 / too many parameters  IN list exceeds DB limit       chunk the IN list; or use a temp/join table
Statement cache thrash           every list size = new SQL      pad to fixed sizes, or use array/ANY()
Slow plan on huge IN             optimizer mishandles big IN    join against a values list / temp table
```

EclipseLink and the platform layer help: for batch fetching you can tune the **batch size** so the generated `IN` is chunked into multiple statements under the DB limit (`eclipselink.jdbc.max-rows`/batch sizing controls, and the platform's `getMaxBatchWritingSize`), and EclipseLink will split an over-limit `IN` into several queries on platforms where it knows the cap. For application-built queries, the fixes in order of robustness: **(1) chunk** the collection yourself into sub-lists of, say, 500 and union the results — simple and DB-agnostic; **(2)** on PostgreSQL use `= ANY(:array)` with a single array bind, which sidesteps the parameter-count problem entirely and keeps one stable statement; **(3)** for very large sets, **load the ids into a temporary table (or a `VALUES` list) and JOIN** against it, which both avoids the limit and usually plans far better than a thousand-element `IN`.

The operational lesson is to *bound the input*: an endpoint that takes a list of ids should cap the list size (and paginate beyond it) rather than letting an arbitrarily large `IN` reach the database. For EclipseLink batch fetching specifically, prefer `BatchFetchType.IN` for index-friendliness but verify the generated list stays under your database's cap on realistic page sizes — and if your pages can be large, either reduce the batch size so chunks stay under the limit or switch that path to `EXISTS`/`JOIN` batching. The senior framing: a runaway `IN` clause is almost always a missing bound on collection size somewhere upstream; fix it by chunking/array-binding/temp-table at the data layer *and* by capping list inputs at the API layer, so the database never sees an unbounded parameter list.

#### Q85. [Practical] How do you correctly handle dates, times, and time zones in EclipseLink, and what bugs come from getting it wrong?
Time-zone handling is a perennial source of subtle, hard-to-reproduce production bugs, and the modern correct approach with EclipseLink (Jakarta Persistence on a recent JDBC driver) is to **use `java.time` types and store instants in UTC**. JPA 2.2+/EclipseLink supports `LocalDate`, `LocalDateTime`, `LocalTime`, `OffsetDateTime`, and `Instant` directly (no `@Temporal` needed for `java.time` types — `@Temporal` is only for legacy `java.util.Date`/`Calendar`), mapping them to the appropriate SQL types:

```java
@Entity
public class Event {
    @Id private Long id;
    private Instant occurredAt;          // store the absolute instant (UTC) -- recommended for events
    private OffsetDateTime windowStart;  // instant + offset, when offset matters
    private LocalDate businessDate;      // a calendar date with NO time zone (e.g., invoice date)
    // legacy: @Temporal(TemporalType.TIMESTAMP) private java.util.Date created;
}
```

The bug-prevention rules: **(1) choose the type by meaning, not convenience.** An *absolute moment* (when something happened) is an `Instant`/`OffsetDateTime` stored in UTC. A *civil date* with no time component (an invoice date, a birthday) is a `LocalDate` — storing it as a timestamp invites off-by-one-day bugs when a UTC-stored midnight shifts across a zone. A *wall-clock time without a date* is `LocalTime`. The classic disaster is storing a business date as a `TIMESTAMP` in local server time: the same logical date renders as a different day depending on the reader's zone, and DST transitions create or skip hours.

**(2) Pin the time zone explicitly** rather than relying on the JVM default, server default, or driver default — those differ across environments (laptop vs CI vs prod container) and across deploys, so a value written by one and read by another shifts. Run the JVM in UTC (`-Duser.timezone=UTC`), store UTC, and convert to the user's zone only at the presentation edge. With some drivers you also want the connection's session time zone fixed; the PostgreSQL and Oracle JDBC drivers have settings for how `TIMESTAMP WITHOUT TIME ZONE` vs `WITH TIME ZONE` are interpreted, and mismatches there silently shift values.

**(3) Be explicit about the SQL column type.** `TIMESTAMP WITHOUT TIME ZONE` stores the wall-clock value with no offset (so it means nothing without an out-of-band convention), while `TIMESTAMP WITH TIME ZONE` normalizes to UTC. Mapping an `Instant` to a `WITHOUT TIME ZONE` column and reading it back through a JVM in a different zone is the textbook way to lose an offset. The bugs from getting this wrong are insidious because they only appear when *writer and reader disagree on zone* — they pass every test on a single machine in one zone and corrupt data the moment a container runs in UTC while a developer's box runs in local time, or when DST flips. The senior summary: standardize on `java.time`, store instants in UTC with an unambiguous column type, fix the JVM and connection zone to UTC, convert to local only for display, and reserve `LocalDate`/`LocalTime` for genuinely zoneless civil values — and write a test that round-trips a value across two different JVM time zones to prove the mapping is zone-stable.

#### Q86. [Practical] How do you deploy EclipseLink in a Jakarta EE application server (WildFly, WebLogic, Payara) versus standalone, and what changes?
Deploying inside a Jakarta EE container is materially different from standalone/Spring, and the differences cluster around **who provides the JPA provider, the data source, transactions, and weaving**. In a container the persistence unit is typically **JTA-typed**, the data source is a **container-managed JNDI resource** (XA-capable when spanning multiple resources), transactions are owned by the container's JTA transaction manager, and you obtain a transaction-scoped `EntityManager` via `@PersistenceContext` injection rather than constructing one yourself:

```xml
<!-- container deployment: JTA + JNDI data source -->
<persistence-unit name="orders-pu" transaction-type="JTA">
  <jta-data-source>java:/jdbc/ordersXADS</jta-data-source>
  <properties>
    <property name="eclipselink.target-server" value="WebLogic_10"/> <!-- integrate with the server's TM -->
  </properties>
</persistence-unit>
```

The provider question is the big one. **WebLogic ships EclipseLink as its built-in JPA provider**, so it "just works" and is the most frictionless target (this is a major reason Oracle-stack shops stay on EclipseLink). **WildFly/JBoss ships Hibernate as the default**, so to use EclipseLink you must deploy the EclipseLink modules/jars and explicitly select the provider — a more involved setup, and you must avoid classloader conflicts with the server's Hibernate. **Payara/GlassFish** has strong EclipseLink heritage (GlassFish was the JPA RI's home) and supports it well. In every container, you set `eclipselink.target-server` (e.g., `WebLogic_10`, `JBoss`, `Glassfish`) so EclipseLink integrates with that server's transaction manager and managed-connection lifecycle rather than managing its own.

The behavioral changes versus standalone: **(1) transactions** — you use `@PersistenceContext`/CMT, not `em.getTransaction()` (calling the latter on a JTA EM throws); the container begins/commits around your EJB/CDI method. **(2) weaving** — containers provide a `LoadTimeWeaver` instrumentation hook so dynamic weaving can work without a manual `-javaagent`, but the robust choice is still static weaving so you don't depend on each server's instrumentation quirks. **(3) connection pooling** — the container's data source owns the pool (sized and monitored via the server's admin console), and EclipseLink borrows from it; you do not configure EclipseLink's internal pool. **(4) XA/two-phase commit** — if the persistence unit participates in a global transaction with other resources (JMS, a second database), the data source must be XA and you accept the 2PC overhead. **(5) classloading** — entity classes, the provider, and the JPA API must be visible at the right classloader scope, a frequent source of `ClassNotFoundException`/`NoClassDefFoundError` on misconfigured WildFly deployments.

The senior framing: standalone/Spring puts *you* in charge of provider, pool, transactions, and weaving, so the config is explicit and portable; the app server takes over provider integration, pooling, and transaction management, so you configure *less* but must respect the container's contracts (JTA, JNDI, `target-server`, classloading). The decision to deploy in a container is usually driven by the broader platform (WebLogic + EclipseLink is a coherent Oracle-stack choice), and the migration trap when moving a service *out* of a container into Spring Boot is forgetting that JTA/CMT and the container data source go away — you must reintroduce explicit transaction management, an external pool (HikariCP), and a weaving strategy yourself.

#### Q87. [Practical] How do you choose and configure the EclipseLink database `Platform` (dialect), and what bugs come from the wrong platform?
EclipseLink's `DatabasePlatform` (the dialect) is the component in the SQL-generation stage that knows the database-specific syntax: pagination (`LIMIT`/`OFFSET` vs `ROWNUM` vs `FETCH FIRST`), identifier quoting, sequence/identity behavior, function names, data-type mapping, and `FOR UPDATE` syntax. EclipseLink **auto-detects** the platform from the JDBC connection metadata by default, but you can (and in some cases must) pin it explicitly:

```xml
<property name="eclipselink.target-database"
          value="org.eclipse.persistence.platform.database.PostgreSQLPlatform"/>
<!-- others: OraclePlatform / Oracle12Platform, MySQLPlatform, SQLServerPlatform, H2Platform -->
```

You pin it explicitly in three situations: **(1)** auto-detection picks a too-generic platform (the base `DatabasePlatform`) when running through a proxy or an unusual driver, producing lowest-common-denominator SQL that misses database-specific optimizations; **(2)** you want a *version-specific* platform — `Oracle12Platform` enables `OFFSET ... FETCH` pagination and identity columns that the older `OraclePlatform` doesn't, so pinning the right version unlocks better SQL; **(3)** you run tests against H2 but production against Postgres and want the test platform to mimic production behavior as closely as possible (though, as covered earlier, the real fix is Testcontainers with the actual database).

The bugs from a wrong/generic platform are exactly the dialect-sensitive features: **pagination** generated with the wrong idiom can be slow or incorrect (an emulated `ROWNUM` wrapper vs native `LIMIT`); **sequence/identity** handling can mismatch the actual database (preallocation against a sequence that doesn't increment as expected → duplicate keys); **reserved-word quoting** differs, so an entity field named `order` or `user` generates unquoted SQL that the database rejects; **data types** map differently (boolean, `UUID`, `JSON`, `CLOB`/`BLOB`, `TIMESTAMP WITH TIME ZONE`), so DDL generation and binding can produce wrong column types; and **functions** in JPQL (`CONCAT`, date arithmetic, `MOD`) translate to the wrong native function name. These often manifest as "works on database A, breaks on database B" or "fine in dev (H2), broken in prod (Oracle)."

The operational discipline: let auto-detection work in the common case, but **pin the explicit, version-specific platform in production** so you get a deterministic, optimal dialect rather than whatever metadata-based guess the connection yields — and verify the generated SQL (FINE logging) for pagination and sequence behavior on the *production* database, not a stand-in. If you genuinely target multiple databases, externalize the platform as config (per environment) and run the integration suite against each real database, because the platform is precisely the layer that makes "portable JPQL" emit different SQL — portability is a property you must *test*, not assume. The senior point is that the platform is the seam between portable mappings and physical SQL, so platform misconfiguration produces bugs that are invisible until you run against the specific database whose dialect you got wrong.

#### Q88. [Practical] How do you use EclipseLink partitioning to scale across multiple databases/shards, and when is it the right tool?
EclipseLink has built-in **`@Partitioning`** support that routes an entity's reads and writes to different connection pools (and therefore different physical databases) based on a policy — this is EclipseLink's mechanism for horizontal scaling, sharding, and data residency without an external sharding proxy. The policies cover the common topologies:

```
Policy                  Routing rule
----------------------- --------------------------------------------------------
@HashPartitioning       hash a field (e.g., customerId) -> pick a pool/shard
@RangePartitioning      route by value ranges (id 1-1M -> shard A, 1M-2M -> shard B)
@ValuePartitioning      map specific values to pools (region='EU' -> EU database)
@PinnedPartitioning     pin an entity to one specific connection pool
@ReplicationPartitioning write to all pools (replicate); read from one
@RoundRobinPartitioning spread reads across replicas
```
```java
@Entity
@Partitioned("byCustomer")
@HashPartitioning(name = "byCustomer", partitionColumn = @Column(name = "customer_id"),
    connectionPools = {"shardA", "shardB", "shardC"})
public class Order { /* ... */ }
```

The mechanism: you define multiple named connection pools (each pointing at a shard), annotate the entity with a partitioning policy, and EclipseLink computes the target pool per operation from the partition key, so a write for `customer_id=42` and the reads for that customer consistently hit the same shard. `@ReplicationPartitioning`/`@RoundRobinPartitioning` cover the read-replica case (write everywhere, read from one), while `@HashPartitioning`/`@RangePartitioning`/`@ValuePartitioning` cover true sharding by key.

When it is the right tool versus not: it fits when you have a **clean partition key** that almost all queries carry (customer/tenant id), so cross-shard queries are rare — sharding is only painless when your access pattern is shard-local. It is the right call for **data residency** (`@ValuePartitioning` by region keeps EU data in EU databases) and for scaling write throughput beyond one database. It is the *wrong* tool, or at least a costly one, when your queries frequently need to **span shards** (a report across all customers), because EclipseLink partitioning doesn't give you a free cross-shard join/aggregate — you'd have to scatter-gather in application code or maintain a separate aggregate store; cross-shard transactions also lose single-database ACID unless you accept XA/2PC across shards (expensive and operationally heavy). It also complicates **sequence/id generation** (ids must be globally unique across shards — use UUIDs or per-shard sequence offsets), **rebalancing** (moving a customer to a new shard is a data-migration project), and the **L2 cache** (cache regions are still per-EMF, so cache coherence interacts with the routing).

The senior recommendation: EclipseLink partitioning is a genuinely useful, ORM-native way to shard *when your domain has a natural, query-aligned partition key and you mostly do shard-local access* — it avoids bolting on a separate sharding proxy and keeps routing in the mapping. But sharding is a high-cost architectural commitment (cross-shard queries, global ids, rebalancing, distributed transactions), so I would reach for it only after exhausting vertical scaling and read replicas, choose the partition key from the dominant access pattern, keep transactions shard-local, and use globally-unique ids — and I'd weigh EclipseLink's built-in partitioning against a dedicated sharding layer (Vitess, Citus, a proxy) that may handle cross-shard concerns more maturely. The interview-grade judgment is recognizing that the framework feature solves the *routing* problem cheaply but does not solve the *fundamental* hard problems of sharding (cross-partition queries and transactions), which remain your architecture's responsibility.

#### Q89. [Practical] How do you integrate EclipseLink with Spring Data JPA, and what surprises teams that expect the Hibernate experience?
Spring Data JPA is provider-agnostic in principle — it generates repository implementations against the JPA API — so it *can* run on EclipseLink, but the integration is rougher than the Hibernate path because Spring Boot's auto-configuration is built and tested primarily around Hibernate. You wire it by providing an `EclipseLinkJpaVendorAdapter`-backed `EntityManagerFactory` and pointing `@EnableJpaRepositories` at it; Spring Data then uses that EMF for its repository proxies:

```java
@Configuration
@EnableJpaRepositories(basePackages = "com.shop.repo")
class JpaConfig {
    @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds) {
        var emf = new LocalContainerEntityManagerFactoryBean();
        emf.setDataSource(ds);
        emf.setPackagesToScan("com.shop.domain");
        emf.setJpaVendorAdapter(new EclipseLinkJpaVendorAdapter());
        emf.setJpaPropertyMap(Map.of("eclipselink.weaving", "static"));
        return emf;
    }
    @Bean JpaTransactionManager txManager(EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }
}
```

The surprises that bite teams expecting parity with the Hibernate experience: **(1) no auto-configuration** — Spring Boot auto-configures Hibernate from `spring.jpa.*` (dialect, ddl-auto, naming strategy, second-level cache) but does *not* auto-configure EclipseLink, so you wire the EMF, transaction manager, and weaving yourself, and many `spring.jpa.hibernate.*` knobs simply don't apply. **(2) Weaving** — Spring Data relies on the same EclipseLink weaving, so without static weaving (or a working `LoadTimeWeaver`) your lazy to-one associations are silently eager and change tracking falls back to snapshot diffing; this is the single most common "why is it behaving differently" issue, and Spring Boot won't set it up for you. **(3) Vendor query hints** — Spring Data's `@QueryHints` must use EclipseLink keys (`eclipselink.batch`, `eclipselink.read-only`, `eclipselink.refresh`), not the `org.hibernate.*` ones tutorials show:

```java
@QueryHints(@QueryHint(name = "eclipselink.batch", value = "o.lineItems"))
@Query("SELECT o FROM Order o WHERE o.created > :d")
List<Order> recent(@Param("d") Instant d);
```

**(4) Behavioral defaults differ** — the L2 cache being on by default (vs Hibernate's off) means a Spring Data `findById` can serve a cached entity where the team expects a database hit, reintroducing the stale-read class of bugs into code that "looks the same" as a Hibernate project. **(5) Auditing/Envers analogs** — Spring Data JPA auditing (`@CreatedDate`, `@LastModifiedDate`) works (it's JPA-callback based), but Hibernate-specific extensions Spring Data users lean on (Envers history, `@Filter`, `@SoftDelete`) have no Spring Data integration on EclipseLink and require EclipseLink-native mechanisms (`HistoryPolicy`, descriptor customizers). **(6) DDL** — `spring.jpa.hibernate.ddl-auto` doesn't apply; you use `eclipselink.ddl-generation` (and, in production, neither — Flyway/Liquibase).

The senior framing: Spring Data JPA on EclipseLink is *supported and works*, but it is a deliberately more hands-on setup where you give up the smooth Hibernate auto-config and must explicitly own weaving, vendor hints, and the cache-default behavior. I would set it up with static weaving from day one, audit every `@QueryHints` for EclipseLink-correct keys, decide the cache policy per entity up front (because the on-by-default cache will surprise a Hibernate-trained team), and keep the persistence layer behind repository interfaces so the provider-specific bits are contained. If a team specifically wants the frictionless Spring Boot + Spring Data experience and has no EclipseLink-driving reason (WebLogic, TopLink heritage, MOXy), that desire is itself an argument for Hibernate — the integration friction is real and worth weighing in the provider choice.

#### Q90. [Practical] How do you implement audit trails / change history on EclipseLink, given there is no Hibernate Envers? Compare the options.
EclipseLink has no direct equivalent to Hibernate Envers (the annotation-driven audit-table generator), so auditing is assembled from EclipseLink's own building blocks, and the right choice depends on whether you need *who-changed-what* audit logs versus *point-in-time historical queries*. There are four realistic approaches, each at a different layer:

```
Approach                         Captures              Cost / trade-off
-------------------------------- --------------------- -----------------------------------------
EclipseLink HistoryPolicy        full row history,     ORM-managed, queryable as-of timestamp;
  (descriptor customizer)        as-of queries          extra DML per change; no "who/why"
JPA lifecycle callbacks +        who/what/when audit    portable; you write the audit-event code;
  @PreUpdate/@PrePersist event   log (your shape)       must diff old vs new yourself
Database triggers / temporal     authoritative history  DB-native, catches out-of-band writes too;
  tables (SQL:2011, Flashback)   at the source          DB-specific, history logic outside the app
Event sourcing / change stream   domain events         richest semantics; biggest architectural cost
  (publish changes to a log)
```

**EclipseLink `HistoryPolicy`** (covered in Set 1) is the closest in spirit to Envers: configured via a `DescriptorCustomizer`, it writes the prior row version to a mirror history table on every update/delete and lets you run `AsOfClause` point-in-time queries — ideal when you need to reconstruct *what the data looked like at time T*. Its limitation is that it captures *state*, not *intent*: it doesn't natively record the acting user or a reason, and it only sees changes that go through EclipseLink.

**Lifecycle callbacks** (`@PreUpdate`/`@PrePersist`/`@PreRemove`, often combined with Spring Data's `@CreatedBy`/`@LastModifiedBy` auditing) are the portable choice for a *who/what/when* audit log: on each change you emit an audit record (entity, field, old value, new value, user, timestamp). You control the shape, but you must compute the diff yourself (the old value isn't automatically available in `@PreUpdate` unless you stash the loaded state), and like `HistoryPolicy` it only sees ORM-mediated writes.

**Database triggers or native temporal tables** (Oracle Flashback / Total Recall, SQL Server temporal tables, Postgres trigger-based audit) are the most *authoritative* because they capture changes at the source — including out-of-band writes by batch jobs or DBAs that the ORM never sees. The trade-off is that the history logic lives in the database (DB-specific, harder to keep in sync with the application model, and the "who/why" needs the app to set a session context the trigger reads). For systems where the audit trail must be trustworthy and complete (compliance, finance), pushing it to the database is often the correct, defensible choice precisely because it cannot be bypassed by skipping the ORM.

The senior recommendation: pick by *requirement*. If you need queryable point-in-time history and all writes go through EclipseLink, use `HistoryPolicy`. If you need a who/what/when audit log, use lifecycle callbacks + Spring auditing for the actor, and stash the pre-image to diff. If the audit must be authoritative against *all* writers (including non-ORM), use database temporal tables/triggers and have the app set a session user context. And if change history is itself a first-class domain concept, consider event sourcing. The point I'd make is that "EclipseLink has no Envers" is not a gap to lament but a prompt to choose the *right layer* for auditing — and the layer that can't be bypassed (the database) is frequently the right one for anything that has to stand up to an auditor.

#### Q91. [Practical] How do you correctly size the connection pool for an EclipseLink service, and what symptoms indicate it is mis-sized?
Connection-pool sizing is one of the highest-leverage and most misunderstood production tunings, and the counterintuitive truth is that **bigger is usually worse**. A connection pool fronts a finite database that can only do so much concurrent work; oversizing the pool lets too many queries hit the database at once, causing CPU/IO contention, lock contention, and *higher* latency for everyone — the database thrashes instead of queueing. The well-known starting formula (from the HikariCP guidance) is `connections ≈ (core_count × 2) + effective_spindle_count` for the database, which for a typical 8-core, SSD-backed database lands around 16–20 connections *total across all app instances sharing that DB* — far smaller than teams' instinct of "hundreds."

```
Symptom                                   Likely cause                  Direction
----------------------------------------- ----------------------------- --------------------
Threads blocked waiting for a connection  pool too SMALL (or leak)      increase / fix leak
  (connectionTimeout errors, high pending)
DB CPU/IO saturated, latency up under load pool too BIG                 decrease
Pool full but DB nearly idle              connections held too long     shorten transactions
  (long-held connections)                   (work-in-transaction)         / fix leaks
Intermittent exhaustion at peak only       pool sized for average,       size for peak concurrency
                                            not peak                       (or queue/backpressure)
```

The sizing method I use: **(1)** estimate the real *concurrent* database work (not total requests) — most requests spend most of their time not talking to the database, so concurrent DB operations are far fewer than concurrent requests; **(2)** start from the formula as a ceiling on what the *database* can usefully do, then divide that budget across app instances (10 instances sharing one DB means each instance's pool is small, because `instances × pool_size` is what hits the database); **(3)** size for *peak* concurrency with a little headroom, not average; **(4)** prefer a smaller pool with a short `connectionTimeout` and let excess load *queue at the app* (backpressure) rather than overwhelming the database — a queued request that completes in 50ms beats a database that's thrashing for everyone.

The EclipseLink-specific angles: its lazy/deferred write-connection acquisition means read-only transactions may hold connections for less time than you'd assume, which *reduces* the pool you need — but a cursored stream or a long transaction pins a connection for its entire duration, so those must be bounded or they distort sizing. Also remember the **statement-cache × pool-size = cursor budget** interaction from the `ORA-01000` discussion: a bigger pool multiplies cursor consumption. The symptoms that tell you it's wrong are concrete and measurable: HikariCP's `pending` (threads waiting for a connection) consistently above zero means too small (or a leak holding connections); database CPU/IO pegged with rising latency as you add load means too big; connections all in-use while the database sits idle means connections are held too long (work being done inside the transaction that shouldn't be, or a leak). 

The senior framing: pool sizing is about matching app concurrency to *database capacity*, with the pool acting as a deliberate throttle, not a buffer to make infinitely large. I size from the database's perspective (total connections across all instances), keep transactions short so connections turn over fast, monitor `active`/`pending`/`usage` continuously, and load-test to find the point where adding connections stops improving and starts hurting throughput — because the right pool size is an empirical property of *this* app against *this* database, not a number you can set once and forget.

#### Q92. [Practical] How do you detect mapping-versus-schema drift early (fail fast at boot rather than at the first query in production)?
A frequent and painful production incident is **mapping/schema drift**: the entity mappings expect a column, type, or table that the deployed database schema doesn't have (or has differently), and because EclipseLink generates SQL lazily on first use, the mismatch surfaces not at startup but at the *first request that touches that mapping* — often hours after deploy, on a code path that wasn't exercised in smoke tests. The goal is to convert that latent runtime failure into a loud boot-time failure, so a bad deploy never goes live.

The mechanisms, from cheapest to most thorough:

```
Technique                              When it catches drift
-------------------------------------- -----------------------------------------------
eclipselink.deploy-on-startup=true     forces descriptor deployment at boot (not lazy)
Validate named queries at startup       @NamedQuery are parsed/validated against metadata
A boot-time "ping" of each entity       SELECT ... FETCH FIRST 1 ROW per entity at startup
schema validation (DDL compare)         compare mappings vs information_schema before serving
Flyway/Liquibase migration on deploy    schema is migrated to the expected shape first
Integration tests vs real DB (CI)       drift caught before the artifact is ever built
```

The most reliable production guardrails are two complementary things. First, **named queries are validated at EMF bootstrap** — they are parsed and resolved against the descriptor model when the persistence unit deploys, so a JPQL query referencing a renamed field fails fast at startup rather than at first call; this is a concrete reason to prefer `@NamedQuery` for critical queries over ad-hoc dynamic JPQL (the dynamic string is only parsed on first use). You can force the persistence unit to fully deploy (build all descriptors) at startup rather than lazily, so descriptor-construction errors surface immediately. Second, and most decisively, the deploy pipeline runs **Flyway/Liquibase migrations before the new code starts**, so the schema is *guaranteed* to match what the new mappings expect — drift can't exist because the migration that adds the column runs ahead of the code that maps it (the expand/contract ordering from the zero-downtime question).

A cheap belt-and-suspenders technique for the residual risk is a **startup health check that touches every entity** — issue a trivial bounded query (`SELECT e FROM Entity e` with `setMaxResults(1)`) for each mapped type during application readiness, so any column/type/table mismatch throws *before* the instance reports healthy and the load balancer routes traffic to it. This trades a few milliseconds of startup for converting "first user hits the broken path at 2pm" into "the deploy fails its readiness probe and rolls back automatically."

The senior framing: the real fix is upstream of EclipseLink — **migrations run before code, and integration tests against a real database in CI catch drift before the artifact is even built**, so production never sees a mapping that doesn't match the schema. The runtime techniques (`deploy-on-startup`, named-query validation, the per-entity ping in the readiness probe) are the *backstop* that makes any drift that slips through fail loudly at boot and within the deployment's automatic-rollback window, rather than silently at the first unlucky request. The principle to articulate is "fail fast and fail at deploy time": you want the system that's about to be promoted to traffic to *prove* its mappings match the schema as part of becoming healthy, not to discover the mismatch from a user-facing 500 later.

## 🧩 Extended Questions — Set 3: Coding, Design & Expert Scenarios

### 🟢 Basic — extended

#### Q93. [Coding] Write a minimal bootstrap that opens an `EntityManagerFactory`, persists an entity inside a transaction, and closes everything correctly. What resource-management mistakes are common?
The point of this exercise is to show you understand the **resource lifecycle** — the `EntityManagerFactory` is heavyweight and long-lived (one per persistence unit per JVM), while the `EntityManager` is cheap, single-threaded, and per-unit-of-work. Mixing those lifetimes up is the single most common beginner mistake: creating a new EMF per request (it re-parses metadata and rebuilds descriptors every time — hundreds of milliseconds) or sharing one EM across threads (the persistence context is not thread-safe).

```java
public class Bootstrap {
    // EMF created ONCE, reused for the whole app. It is thread-safe.
    private static final EntityManagerFactory EMF =
        Persistence.createEntityManagerFactory("orders-pu");

    public static Long createOrder(String customer, BigDecimal total) {
        EntityManager em = EMF.createEntityManager();   // cheap, per-unit-of-work
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();
            Order o = new Order();
            o.setCustomer(customer);
            o.setTotal(total);
            em.persist(o);
            tx.commit();           // INSERT fires here; generated id is now populated
            return o.getId();
        } catch (RuntimeException ex) {
            if (tx.isActive()) tx.rollback();   // never leave a transaction open
            throw ex;
        } finally {
            em.close();            // ALWAYS close the EM -> returns the JDBC connection
        }
    }

    public static void shutdown() { EMF.close(); }  // at app shutdown only
}
```

**Why it works:** the `finally` block guarantees the `EntityManager` (and its pooled JDBC connection) is released even when the transaction throws, and the rollback in the `catch` prevents a half-applied transaction from leaking locks. **Time/Space:** O(1) per call; the expensive EMF construction is amortized to once per JVM. **Edge cases / common mistakes:** (1) forgetting `em.close()` leaks a connection per request and eventually exhausts the pool (`ORA-01000`/Hikari timeout); (2) calling `getTransaction()` when the PU is `transaction-type="JTA"` throws `IllegalStateException` — under JTA you call `em.joinTransaction()` instead; (3) building the EMF per request is the classic performance killer; (4) `o.getId()` is only reliably populated *after* `commit()` (or `flush()`) for `IDENTITY` generation, since the database assigns it on insert.

#### Q94. [Coding] Write a JUnit test that proves EclipseLink's L2 shared cache serves a stale value after an out-of-band update, then a second test proving `em.refresh()` fixes it. Why do you need two connections?
This is the canonical "prove the default cache behavior" exercise, and it forces you to confront the fact that **a single EMF cannot demonstrate staleness against itself** — every read and write through one EMF goes through the same shared cache, so it is internally consistent by construction. To observe staleness you must mutate the row *behind EclipseLink's back* (raw JDBC or a second independent connection), then read through EclipseLink and watch it return the cached value.

```java
@Test
void sharedCacheServesStaleValueAfterRawUpdate() throws Exception {
    EntityManager em = emf.createEntityManager();
    Product p = em.find(Product.class, 1L);          // loads into L1 + L2 shared cache
    assertEquals(new BigDecimal("10.00"), p.getPrice());
    em.clear();                                       // wipe L1 only; L2 still holds it

    // Mutate the row via a RAW connection EclipseLink knows nothing about:
    try (Connection c = dataSource.getConnection();
         PreparedStatement ps = c.prepareStatement(
             "UPDATE product SET price = ? WHERE id = ?")) {
        ps.setBigDecimal(1, new BigDecimal("99.00"));
        ps.setLong(2, 1L);
        ps.executeUpdate();
    }

    Product stale = em.find(Product.class, 1L);       // served from L2 shared cache
    assertEquals(new BigDecimal("10.00"), stale.getPrice());   // STALE, by design
    em.close();
}

@Test
void refreshBypassesCacheAndSeesTruth() {
    EntityManager em = emf.createEntityManager();
    Product p = em.find(Product.class, 1L);
    em.refresh(p);   // forces a DB re-read and UPDATES the shared cache
    assertEquals(new BigDecimal("99.00"), p.getPrice());
    em.close();
}
```

**Why two connections:** `em.clear()` discards the L1 persistence context but leaves the L2 shared cache intact, so the second `find()` is a pure L2 hit — issuing no SQL. The raw JDBC `UPDATE` simulates exactly the real-world cause of staleness (a batch job, DBA, or sibling app writing the row), which the shared cache cannot see. **Edge cases:** the test is order- and isolation-sensitive — run it in `READ_COMMITTED` and ensure the raw update commits before the second `find()`; if the shared cache happened to be configured `NONE` for `Product`, the first test would fail because EclipseLink would re-read and see `99.00`. That sensitivity is the lesson: your test asserts the *production cache configuration*, so disabling caching in tests hides exactly the bug you are trying to catch.

#### Q95. [Coding] Show how to write a JPA `AttributeConverter` that transparently encrypts a column, and explain why this is preferable to a `DescriptorCustomizer` for the same job.
`AttributeConverter` is the JPA-standard, portable extension point for transforming an attribute between its Java representation and its database column. It is the right tool for column-level concerns — encryption, mapping a value object to a code, storing an enum as a custom string — because it is declarative, testable in isolation, and provider-independent (the same converter works on Hibernate too).

```java
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {
    private final AesGcmCipher cipher = AesGcmCipher.fromEnv("APP_DATA_KEY");

    @Override
    public String convertToDatabaseColumn(String attribute) {       // entity -> column
        return attribute == null ? null : cipher.encrypt(attribute);
    }
    @Override
    public String convertToEntityAttribute(String dbData) {         // column -> entity
        return dbData == null ? null : cipher.decrypt(dbData);
    }
}

@Entity
public class Patient {
    @Id @GeneratedValue private Long id;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "ssn")
    private String ssn;     // stored encrypted-at-rest, decrypted transparently on read
}
```

**Why prefer it over a `DescriptorCustomizer`:** a customizer manipulates EclipseLink's `ClassDescriptor` directly, runs early in EMF bootstrap where failures are opaque, and couples you to EclipseLink internals — overkill for a per-column transform. The converter expresses the same intent declaratively and portably. **Edge cases and a sharp warning:** (1) you cannot do meaningful **equality predicates or range queries** on an encrypted column through JPQL, because the database only sees ciphertext — `WHERE ssn = :x` would have to compare ciphertexts, which fails for non-deterministic encryption like AES-GCM; if you need to look rows up by the encrypted value, you need deterministic encryption or a separate blind-index column. (2) The converter runs on every read/write, so its cost is on the hot path — keep it cheap and stateless-safe (the `AttributeConverter` instance may be shared across threads). (3) `@Convert` cannot be applied to `@Id` or `@Version` attributes. (4) Auto-apply (`@Converter(autoApply = true)`) converts *every* attribute of that type, which is usually too broad for encryption — apply it explicitly per field.

### 🟡 Intermediate — extended

#### Q96. [Coding] Implement a type-safe Criteria query with a dynamic, optional set of filters and pagination, and explain why building predicates conditionally avoids the `field = null` trap.
Criteria is the portable way to build queries whose shape depends on runtime input (a search form where any field may be blank). The professional pattern is to accumulate `Predicate`s into a list, appending one only when its filter is present, then `AND` them together — never embedding `field = null` into the SQL, which databases evaluate as `UNKNOWN` and silently drop the row.

```java
public record OrderFilter(String customer, BigDecimal minTotal, String status) {}

public List<Order> search(EntityManager em, OrderFilter f, int page, int size) {
    CriteriaBuilder cb = em.getCriteriaBuilder();
    CriteriaQuery<Order> cq = cb.createQuery(Order.class);
    Root<Order> root = cq.from(Order.class);

    List<Predicate> ps = new ArrayList<>();
    if (f.customer() != null && !f.customer().isBlank())
        ps.add(cb.equal(cb.lower(root.get("customer")), f.customer().toLowerCase()));
    if (f.minTotal() != null)
        ps.add(cb.greaterThanOrEqualTo(root.get("total"), f.minTotal()));
    if (f.status() != null)
        ps.add(cb.equal(root.get("status"), f.status()));

    cq.where(ps.toArray(Predicate[]::new))   // empty array -> no WHERE, returns all
      .orderBy(cb.desc(root.get("id")));

    return em.createQuery(cq)
             .setFirstResult(page * size)     // OFFSET
             .setMaxResults(size)             // LIMIT / FETCH FIRST
             .setHint("eclipselink.batch", "o.lineItems")   // still works on Criteria via hint
             .getResultList();
}
```

**Why conditional predicates matter:** if you instead wrote `cb.equal(root.get("status"), f.status())` unconditionally with a null status, EclipseLink generates `status = NULL`, which is never true in SQL three-valued logic, so the query returns *zero rows* instead of "all statuses" — a subtle, data-dependent bug. Building the list conditionally means an absent filter contributes no predicate at all. **Time/Space:** one SQL statement; offset pagination is O(offset + size) on most databases (the engine scans and discards `offset` rows), so deep pages degrade — for large datasets prefer keyset/seek pagination (`WHERE id < :lastSeenId`). **Edge cases:** an empty predicate array yields no `WHERE` clause (returns everything — guard with a mandatory filter if that is dangerous); `setFirstResult` with a huge offset is the classic slow-deep-pagination trap; and Criteria cannot express EclipseLink batch-read attributes directly, so you still attach `eclipselink.batch` as a query hint to avoid N+1 on `lineItems`.

#### Q97. [Coding] Write a `SessionCustomizer` that registers a custom database function and tunes the connection pool, and explain what belongs in a `SessionCustomizer` versus a `DescriptorCustomizer`.
A `SessionCustomizer` runs once when the `EntityManagerFactory`/session is being built and is the hook for **session-global** configuration that annotations cannot express — registering platform-level SQL functions, adjusting connection pools programmatically, installing query redirectors, or adding named queries at runtime. It is wired in via the `eclipselink.session.customizer` property.

```java
public class OrdersSessionCustomizer implements SessionCustomizer {
    @Override
    public void customize(Session session) throws Exception {
        // 1) Register a DB-specific function usable from the Expression API.
        DatabasePlatform platform =
            (DatabasePlatform) session.getDatasourceLogin().getDatasourcePlatform();
        ExpressionOperator soundex = new ExpressionOperator();
        soundex.setSelector(99001);
        soundex.setType(ExpressionOperator.FunctionOperator);
        soundex.printsAs("SOUNDEX(");
        soundex.bePrefix();
        soundex.printsJavaAs(")");
        platform.addOperator(soundex);

        // 2) Tune the internal read/write pools programmatically.
        if (session instanceof ServerSession server) {
            server.getConnectionPool("default").setMinNumberOfConnections(10);
            server.getConnectionPool("default").setMaxNumberOfConnections(40);
        }
    }
}
```
```xml
<property name="eclipselink.session.customizer" value="com.shop.OrdersSessionCustomizer"/>
```

**Session vs Descriptor split:** a `SessionCustomizer` is for things that are *global to the persistence unit* — pools, platform operators, login, named queries, profilers. A `DescriptorCustomizer` (wired via `@Customizer` on one entity) is for things scoped to *a single entity's mapping* — changing a mapping's fetch type, attaching a `HistoryPolicy` or `CacheInvalidationPolicy`, or adding an `additionalJoinExpression` for soft-delete filtering. **Edge cases / trade-offs:** customizers run very early, so an exception here aborts EMF creation with a sometimes-cryptic stack trace; they couple you tightly to EclipseLink internals, so reach for portable mechanisms (`AttributeConverter`, `shared-cache-mode`, query hints) first and use customizers only for genuinely provider-specific needs. The connection-pool tuning shown is fine for the internal pool, but in production you would usually externalize pooling to HikariCP and leave the `SessionCustomizer` to function registration and redirectors.

#### Q98. [Coding] Implement batch-insert of 100,000 rows efficiently in EclipseLink. Show the four levers (batch writing, periodic flush/clear, sequence allocation, no IDENTITY) and explain why each matters.
Naive bulk insertion — `persist()` in a loop and commit at the end — fails in three ways at scale: it issues one round trip per row, it accumulates 100k managed objects in one ever-growing UnitOfWork (OOM + slow change computation), and with `IDENTITY` IDs it cannot batch at all. The efficient version turns on JDBC batch writing, flushes-and-clears periodically to bound memory, and uses `SEQUENCE` with a large allocation so ID generation is not a per-row round trip.

```java
void bulkInsert(EntityManagerFactory emf, List<OrderRow> rows) {
    EntityManager em = emf.createEntityManager();
    em.getTransaction().begin();
    // Lever 1: batch writing (set as PU property; shown here per-EM via unwrap for clarity)
    //   eclipselink.jdbc.batch-writing=JDBC
    //   eclipselink.jdbc.batch-writing.size=1000
    int BATCH = 1000;
    for (int i = 0; i < rows.size(); i++) {
        Order o = toEntity(rows.get(i));
        em.persist(o);
        if (i % BATCH == 0 && i > 0) {
            em.flush();   // Lever 2: push this batch's DML to the DB now ...
            em.clear();   // ... and detach them so the UoW/L1 doesn't keep growing
        }
    }
    em.getTransaction().commit();  // final partial batch flushes here
    em.close();
}
```
```xml
<!-- Lever 1 + 3 in persistence.xml -->
<property name="eclipselink.jdbc.batch-writing" value="JDBC"/>
<property name="eclipselink.jdbc.batch-writing.size" value="1000"/>
```
```java
// Lever 4: SEQUENCE (preallocates) instead of IDENTITY (forces row-by-row).
@Id @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "order_seq")
@SequenceGenerator(name="order_seq", sequenceName="ORDER_SEQ", allocationSize = 1000)
private Long id;
```

**Why each lever matters:** **(1) batch writing** groups N inserts into one JDBC `addBatch`/`executeBatch`, collapsing N round trips into N/batchSize — typically a 10–50× throughput win and a large reduction in network and allocation. **(2) flush+clear** caps the UnitOfWork size so memory stays O(batch) instead of O(total), and keeps the change-set computation small. **(3) sequence allocation** of 1000 means EclipseLink fetches IDs ~100 times instead of 100,000 times. **(4) avoiding `IDENTITY`** is critical because `IDENTITY` requires the database to return the generated key on each insert, which **disables JDBC batching for inserts entirely** — the single most common reason "I turned on batch writing and nothing got faster." **Edge cases:** `allocationSize` must equal the DB sequence's `INCREMENT BY` or you get duplicate-key errors; clearing detaches everything, so don't hold references to persisted entities across a `clear()`; and one giant transaction holds locks and undo/redo for the whole run — for truly huge loads, commit in chunks (accepting partial-failure semantics) rather than one mega-transaction.

#### Q99. [Coding] Implement a JPA `EntityListener` plus lifecycle callbacks to stamp `createdAt`/`updatedAt` and a `lastModifiedBy`, and explain the ordering relative to Bean Validation.
Auditing timestamps and actor stamps are best handled with lifecycle callbacks so the logic lives in one place and fires automatically on every write, rather than being sprinkled across service methods (which inevitably miss a code path). A reusable `EntityListener` keeps the auditing concern out of the entities themselves.

```java
public class AuditListener {
    @PrePersist
    public void onCreate(Auditable e) {
        Instant now = Instant.now();
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        e.setLastModifiedBy(CurrentUser.id());   // from a ThreadLocal / security context
    }
    @PreUpdate
    public void onUpdate(Auditable e) {
        e.setUpdatedAt(Instant.now());
        e.setLastModifiedBy(CurrentUser.id());
    }
}

public interface Auditable {
    void setCreatedAt(Instant t); void setUpdatedAt(Instant t); void setLastModifiedBy(String id);
}

@Entity
@EntityListeners(AuditListener.class)
public class Order implements Auditable {
    @Id @GeneratedValue private Long id;
    private Instant createdAt;
    private Instant updatedAt;
    private String lastModifiedBy;
    /* ... + setters from Auditable ... */
}
```

**Ordering with Bean Validation:** the lifecycle callback fires **before** validation — the sequence for a new entity is `@PrePersist` → Bean Validation (pre-persist group) → `INSERT` → `@PostPersist`. This ordering is deliberate and exactly what you want here: the listener *computes* `createdAt`/`updatedAt`, and then validation runs against the now-complete state, so a `@NotNull private Instant createdAt;` constraint passes because the listener already populated it. If validation ran first, the timestamp would still be null and you'd get a spurious `ConstraintViolationException`. **Edge cases:** (1) `@PreUpdate` only fires when EclipseLink detects the entity is dirty at flush — touching a field and reverting it may not trigger it; (2) callbacks fire on *flush*, so under `FlushModeType.AUTO` a mid-transaction query can trigger the stamp earlier than commit; (3) avoid heavy work (no DB calls, no remote I/O) inside callbacks — they run inside the flush and can deadlock or slow every write; (4) reading the actor from a `ThreadLocal` couples persistence to the request thread, so for async/batch writes you must seed that context or pass the actor explicitly.

#### Q100. [Coding] Write a parameterized JPQL named query and show how to detect and avoid the EclipseLink `IN`-list / bind-variable explosion when the parameter is a large collection.
A `WHERE x IN :ids` query with a `Collection` parameter expands to `IN (?, ?, ?, ...)` with one bind variable per element. When the collection is large this hits real limits — Oracle caps an `IN` list at 1000 expressions, and every database has a maximum number of bind parameters per statement (~32767 on PostgreSQL). Beyond correctness, a 5,000-element `IN` list defeats the prepared-statement cache because each distinct size is a different SQL string, so you also lose statement reuse.

```java
@NamedQuery(name = "Order.byIds",
            query = "SELECT o FROM Order o WHERE o.id IN :ids")
@Entity public class Order { /* ... */ }

// Chunk the collection so no single IN list exceeds a safe bound.
public List<Order> findByIds(EntityManager em, Collection<Long> ids) {
    final int CHUNK = 900;                 // safely under Oracle's 1000 limit
    List<Order> all = new ArrayList<>(ids.size());
    List<Long> idList = new ArrayList<>(ids);
    for (int i = 0; i < idList.size(); i += CHUNK) {
        List<Long> slice = idList.subList(i, Math.min(i + CHUNK, idList.size()));
        all.addAll(em.createNamedQuery("Order.byIds", Order.class)
                     .setParameter("ids", slice)
                     .getResultList());
    }
    return all;
}
```

**Why chunking is the fix:** it caps the `IN` list at a database-safe size and limits the number of distinct statement shapes to a handful (one per chunk size — and only the last partial chunk differs), so the statement cache stays effective. **Time/Space:** O(ids/CHUNK) round trips instead of one giant statement that may fail outright. **Better alternatives worth raising in the interview:** (1) for very large sets, **join against a temporary table** or pass an **array parameter** (`unnest(:ids)` on PostgreSQL) so the database sees one bind, not thousands; (2) if the IDs come from another query, fold it into a **subquery/join** rather than materializing IDs into the app at all; (3) EclipseLink also exposes `eclipselink.jdbc.batch-writing` and parameter-binding controls, but those are about writes — the read-side fix is chunking or set-based SQL. **Edge case:** `subList` returns a view backed by the original list, which is fine here because we don't mutate it, but if you cached the slices you'd want defensive copies.

### 🟠 Advanced — extended

#### Q101. [Coding] Implement a tenant-aware persistence setup with EclipseLink `@Multitenant(SINGLE_TABLE)` and prove you avoided cross-tenant cache leakage. Show the EM-per-tenant wiring.
Single-table multitenancy stores all tenants' rows in one table distinguished by a `tenant_id` discriminator, and EclipseLink can append the tenant predicate automatically. The dangerous interaction is the shared L2 cache: if a tenant-scoped entity is `SHARED`-cached, a row loaded for tenant A sits in the EMF-wide cache and a request for tenant B could read it — a data-leak. The fix is to mark multitenant entities `ISOLATED` (or `PROTECTED`) so cached state never crosses sessions.

```java
@Entity
@Multitenant(MultitenantType.SINGLE_TABLE)
@TenantDiscriminatorColumn(name = "tenant_id", contextProperty = "eclipselink.tenant-id")
@Cache(isolation = CacheIsolationType.ISOLATED)   // <-- prevents cross-tenant cache bleed
public class Invoice {
    @Id @GeneratedValue private Long id;
    private BigDecimal amount;
}

// Bind the tenant per EntityManager. The discriminator is applied to every query AND insert.
public EntityManager emForTenant(EntityManagerFactory emf, String tenantId) {
    EntityManager em = emf.createEntityManager(
        Map.of("eclipselink.tenant-id", tenantId));
    return em;
}

// Proof test: tenant B must never see tenant A's row, even after A loaded it.
@Test void noCrossTenantLeak() {
    EntityManager a = emForTenant(emf, "tenant-A");
    a.getTransaction().begin();
    Invoice ia = new Invoice(); ia.setAmount(new BigDecimal("500"));
    a.persist(ia); a.getTransaction().commit();
    Long id = ia.getId();
    a.find(Invoice.class, id);   // loads it; with ISOLATED it does NOT enter the shared cache
    a.close();

    EntityManager b = emForTenant(emf, "tenant-B");
    assertNull(b.find(Invoice.class, id));   // B's tenant predicate filters it out
    b.close();
}
```

**Why ISOLATED is mandatory here:** with `SHARED`, the second `find` could return tenant A's cached `Invoice` *before* the tenant predicate is ever applied, because a primary-key cache hit short-circuits the database query (and thus the discriminator filter). `ISOLATED` keeps the entity only in the per-session context, so every cross-tenant read must go to the database and be filtered. **Trade-off:** you forfeit L2 caching for these entities — acceptable, because correctness and tenant isolation dominate a cache-hit-rate optimization for transactional tenant data. Cache reference data that is genuinely tenant-independent (currency lists, country codes) as `SHARED`. **Edge cases:** the `tenant-id` property must be set on *every* EM (a missing tenant context can return all tenants' rows or throw, depending on `includeCriteria`); native SQL bypasses the discriminator, so hand-written queries must add `tenant_id` themselves; and `TABLE_PER_TENANT` avoids the cache-leak problem structurally (separate tables) at the cost of schema multiplication.

#### Q102. [Coding] Implement a soft-delete (logical delete) for an entity using an EclipseLink `DescriptorCustomizer` with `additionalJoinExpression`, and contrast it with a manual `deleted = false` predicate everywhere.
Soft delete keeps a `deleted` flag instead of physically removing rows, but the hard part is ensuring *every* read silently excludes deleted rows without sprinkling `AND deleted = false` across hundreds of queries. EclipseLink has no `@Where` annotation like Hibernate, but its descriptor model exposes `additionalJoinExpression`, which appends a permanent predicate to every query for that entity.

```java
public class SoftDeleteCustomizer implements DescriptorCustomizer {
    @Override
    public void customize(ClassDescriptor descriptor) {
        ExpressionBuilder eb = descriptor.getQueryManager()
                                         .getAdditionalJoinExpression() != null
            ? new ExpressionBuilder() : new ExpressionBuilder();
        // append:  WHERE deleted = false  to every read of this entity
        Expression notDeleted = eb.get("deleted").equal(false);
        descriptor.getQueryManager().setAdditionalJoinExpression(notDeleted);
    }
}

@Entity
@Customizer(SoftDeleteCustomizer.class)
public class Document {
    @Id @GeneratedValue private Long id;
    private boolean deleted = false;
}

// "Deleting" becomes an update; the additionalJoinExpression hides it thereafter.
public void softDelete(EntityManager em, Long id) {
    Document d = em.find(Document.class, id);
    if (d != null) d.setDeleted(true);   // flush -> UPDATE deleted=true; future reads skip it
}
```

**Why the descriptor approach beats a manual predicate:** centralizing the filter in `additionalJoinExpression` means you cannot forget it on a new query — it is applied at the mapping layer to JPQL, Criteria, and relationship traversals alike. The manual `AND deleted = false` approach is a maintenance hazard: one forgotten clause leaks deleted rows, and it does not protect relationship navigation (loading `order.getLineItems()` would still pull deleted items). **Trade-offs and traps:** (1) the predicate is *always on*, so an admin "show deleted" screen or a purge job must drop to native SQL or a separate non-filtered mapping to see them; (2) unique constraints are subtle — a soft-deleted row still occupies its unique key, so re-creating "the same" logical record collides unless the unique index includes `deleted` or uses a partial index; (3) cascade/orphan-removal semantics no longer fire physical deletes, so foreign-key cleanup is your responsibility; (4) the L2 cache caches the *filtered* view, which is usually what you want but means an externally hard-deleted row can linger in cache. The senior judgment: soft delete is a data-model decision with schema, indexing, and compliance (data-retention/GDPR right-to-erasure) consequences — the EclipseLink mechanism is the easy part.

#### Q103. [Coding] Build a named entity graph and use it as a `fetchgraph`/`loadgraph` hint to control fetching at the query level. Contrast entity graphs with fetch groups.
Entity graphs (JPA 2.1+) are the **portable** way to override fetch plans per query without changing the static `fetch =` on the mapping — you declare which attributes/associations to fetch and pass the graph as a hint. This solves the "this annotation can't be both LAZY for endpoint A and EAGER for endpoint B" problem.

```java
@Entity
@NamedEntityGraph(
    name = "Order.withItemsAndCustomer",
    attributeNodes = {
        @NamedAttributeNode("customer"),
        @NamedAttributeNode(value = "lineItems", subgraph = "items")
    },
    subgraphs = @NamedSubgraph(name = "items",
        attributeNodes = @NamedAttributeNode("product")))
public class Order { /* ... lineItems, customer ... */ }

// fetchgraph: ONLY listed attributes are EAGER; everything else is LAZY (treat as fetch plan).
List<Order> orders = em.createQuery("SELECT o FROM Order o", Order.class)
    .setHint("jakarta.persistence.fetchgraph",
             em.getEntityGraph("Order.withItemsAndCustomer"))
    .getResultList();

// loadgraph: listed attributes are EAGER; unlisted keep their MAPPED fetch type.
List<Order> orders2 = em.createQuery("SELECT o FROM Order o", Order.class)
    .setHint("jakarta.persistence.loadgraph",
             em.getEntityGraph("Order.withItemsAndCustomer"))
    .getResultList();
```

**`fetchgraph` vs `loadgraph`:** with `fetchgraph` the graph is the *complete* fetch plan — attributes not in the graph become LAZY regardless of their mapping. With `loadgraph` the graph is *additive* — listed attributes become EAGER, but unlisted attributes retain whatever the mapping declared. Choosing wrong is a common bug: people expect `fetchgraph` to "also fetch the usual stuff" and are surprised that a normally-eager field came back lazy.

**Entity graphs vs EclipseLink fetch groups:**

```
                    | Entity graph (JPA standard)        | EclipseLink fetch group
--------------------+------------------------------------+----------------------------------
Scope               | which ASSOCIATIONS/attrs to fetch  | which COLUMNS of an entity to load
Primary use         | shape the object graph (joins)     | partial entities on wide tables
Portability         | portable across providers          | EclipseLink-specific
Lazy of unfetched   | proxy / triggers query on access   | woven accessor loads remaining cols
Granularity         | per-association + nested subgraphs  | per-basic-attribute
```

Use **entity graphs** to control how deep and wide the relationship fetch goes (avoiding N+1 by eager-fetching `lineItems` for this endpoint); use **fetch groups** to avoid pulling 40 columns of a wide table when you only need 3. They compose — a fetch group narrows columns, an entity graph shapes associations. **Edge cases:** touching an unfetched attribute later still triggers a lazy load (needs weaving for to-one/basic), so a graph reduces but doesn't eliminate the chance of post-transaction `LazyInitializationException`; and nested subgraphs are the only portable way to go more than one association deep.

#### Q104. [Coding] Implement read/write splitting in EclipseLink so read-only transactions hit a replica and writes hit the primary. What consistency hazard must the design account for?
Routing reads to replicas offloads the primary, but replicas are *asynchronously* behind, so the design must confine replica reads to operations that tolerate bounded staleness and route anything that must read-its-own-writes to the primary. EclipseLink supports this natively through the `ServerSession`'s ability to declare separate **read** and **write** connection pools, or you can drive it with `@ReadTransaction`-style routing in the application layer.

```xml
<!-- Two pools: writes go to primary, plain reads can use the read pool. -->
<property name="eclipselink.connection-pool.default.url"   value="jdbc:postgresql://primary/shop"/>
<property name="eclipselink.connection-pool.read.url"      value="jdbc:postgresql://replica/shop"/>
<property name="eclipselink.connection-pool.read.min"      value="5"/>
<property name="eclipselink.connection-pool.read.max"      value="30"/>
```
```java
// Force a query onto the read (replica) pool, and make it read-only so no UoW clone.
public List<Product> catalogFromReplica(EntityManager em) {
    return em.createQuery("SELECT p FROM Product p", Product.class)
             .setHint("eclipselink.read-only", true)
             .setHint("eclipselink.connection-pool", "read")   // route to replica pool
             .getResultList();
}

// Anything that just wrote and must see its write stays on the default (primary) pool.
public Order placeAndConfirm(EntityManager em, Order o) {
    em.getTransaction().begin();
    em.persist(o);
    em.getTransaction().commit();           // written to primary
    return em.find(Order.class, o.getId()); // read on primary -> guaranteed to see it
}
```

**The consistency hazard — replication lag / read-your-writes:** a user who submits an order and is immediately redirected to "your orders" can fail to see it if that read was routed to a replica that hasn't caught up. The design rule is: **route to the replica only stale-tolerant, read-only operations** (catalog browsing, reporting, search), and keep read-your-writes flows (post-submit confirmation, edit-then-view) on the primary, or pin a user to the primary for a short window after they write ("sticky primary"). **Additional hazards:** (1) the L2 shared cache muddies this — a replica read that populates the shared cache can serve that value to a later request expecting primary-fresh data, so cache volatile entities as `ISOLATED`/`NONE`; (2) a transaction must not mix a replica read and a primary write expecting consistency; (3) failover — if the replica dies, reads must fail over to the primary, which the connection pool/driver should handle (`targetServerType`/multi-host JDBC URLs). The honest framing in an interview: read/write splitting is a *consistency-for-throughput trade*, and the engineering work is classifying each query by its staleness tolerance, not the EclipseLink plumbing.

#### Q105. [Coding] Write a JPA `AttributeConverter` and a query approach for storing a JSON document column (e.g., PostgreSQL `jsonb`) in EclipseLink, and discuss the indexing/query limitations.
Storing a structured blob as JSON is common for flexible, sparsely-populated attributes. With EclipseLink you map the JSON column to a Java object via an `AttributeConverter` that (de)serializes with Jackson, while declaring the column type so the database stores it as `jsonb` rather than plain text.

```java
@Converter
public class JsonbConverter implements AttributeConverter<Map<String,Object>, String> {
    private static final ObjectMapper M = new ObjectMapper();
    @Override public String convertToDatabaseColumn(Map<String,Object> attr) {
        try { return attr == null ? null : M.writeValueAsString(attr); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }
    @Override public Map<String,Object> convertToEntityAttribute(String db) {
        try { return db == null ? null : M.readValue(db, new TypeReference<>() {}); }
        catch (IOException e) { throw new IllegalStateException(e); }
    }
}

@Entity
public class Event {
    @Id @GeneratedValue private Long id;
    @Convert(converter = JsonbConverter.class)
    @Column(name = "payload", columnDefinition = "jsonb")   // tells DDL gen to use jsonb
    private Map<String,Object> payload;
}

// Querying INTO the JSON requires native SQL + a DB JSON operator; JPQL can't path into it.
List<Event> hits = em.createNativeQuery(
        "SELECT * FROM event WHERE payload ->> 'type' = ?1", Event.class)
    .setParameter(1, "LOGIN")
    .getResultList();
```

**The fundamental limitation:** to EclipseLink's JPQL/Criteria layer the column is an opaque string/object — you cannot write `WHERE e.payload.type = 'LOGIN'` in JPQL because the converter hides the structure from the metamodel. Any query that *filters or indexes on a value inside the JSON* must drop to native SQL using the database's JSON operators (`->>`, `@>`, `jsonb_path_query`), which sacrifices portability and the metamodel's type safety. **Indexing:** a plain `jsonb` column is slow to filter; you must add a **GIN index** (`CREATE INDEX ON event USING gin (payload)`) or an expression index on a specific path (`CREATE INDEX ON event ((payload ->> 'type'))`) — and EclipseLink's DDL generation won't create those, so they live in your Flyway/Liquibase migrations. **Trade-offs:** JSON columns buy schema flexibility but cost queryability, constraint enforcement, and statistics quality (the optimizer estimates poorly over JSON). The senior guidance: use JSON for genuinely schemaless, mostly-read-whole, rarely-filtered data (audit payloads, raw webhooks); promote any field you frequently filter or join on into a real typed column. Also note `@Lob` is *not* the right tool here — it implies streaming large-object semantics, whereas `jsonb` wants the native type for operator support.

#### Q106. [Coding] Implement a `QueryRedirector` to add a global filter (e.g., a security/row-level predicate) to every read of an entity, and explain when this is better than `additionalJoinExpression`.
A `QueryRedirector` intercepts a query *before* execution and lets you rewrite or augment it — adding a runtime predicate, rerouting it, or injecting parameters. Unlike `additionalJoinExpression` (a *static* predicate fixed at descriptor build time), a redirector can apply a **dynamic, context-dependent** filter such as the current user's accessible region pulled from a `ThreadLocal` security context.

```java
public class RegionSecurityRedirector implements QueryRedirector {
    @Override
    public Object invokeQuery(DatabaseQuery query, Record args, Session session) {
        if (query instanceof ReadAllQuery raq && query.getReferenceClass() == Account.class) {
            String region = SecurityContext.currentRegion();   // runtime, per-request
            if (region != null) {
                ExpressionBuilder eb = raq.getExpressionBuilder();
                Expression restricted = raq.getSelectionCriteria() == null
                    ? eb.get("region").equal(region)
                    : raq.getSelectionCriteria().and(eb.get("region").equal(region));
                raq.setSelectionCriteria(restricted);
            }
        }
        return query.execute((AbstractSession) session, (AbstractRecord) args);
    }
}

public class AccountRedirectorCustomizer implements DescriptorCustomizer {
    @Override public void customize(ClassDescriptor d) {
        d.getQueryManager().setDefaultReadAllQueryRedirector(new RegionSecurityRedirector());
    }
}

@Entity @Customizer(AccountRedirectorCustomizer.class)
public class Account { @Id Long id; String region; /* ... */ }
```

**Why a redirector over `additionalJoinExpression`:** `additionalJoinExpression` is evaluated once and baked into the descriptor, so it can only encode a *constant* or a query-parameter-driven predicate — it cannot read "the current user's region" that changes per request. A `QueryRedirector` runs *per query execution*, so it can consult request-scoped context and tailor the predicate dynamically. This makes it the right tool for **row-level security**, dynamic multitenancy beyond the discriminator model, or feature-flag-driven filtering. **Trade-offs and dangers:** (1) the redirector runs on the hot path for every matching query, so keep it cheap; (2) it is deeply EclipseLink-specific and bypasses the portability of Criteria/JPQL — a code reviewer must know it exists or be baffled why rows are "missing"; (3) you must handle *all* query types you intend to secure (`ReadAllQuery`, `ReadObjectQuery`, report queries) or a gap leaks data — which is precisely the failure mode that makes row-level security in the application layer risky compared to database-native RLS (PostgreSQL `ROW LEVEL SECURITY`, Oracle VPD). The expert framing: a redirector is powerful but it is *security logic embedded in the ORM*, so for true security boundaries prefer database-enforced RLS and treat the redirector as defense-in-depth, not the only line.

### 🔴 Expert — extended

#### Q107. [Coding] Design and implement a custom `CacheKey`-aware second-level cache integration so EclipseLink can use an external distributed cache. What are the integration seams and consistency pitfalls?
Out of the box EclipseLink's L2 cache is in-process per JVM; to share it across a cluster without relying on JMS/RMI coordination you can integrate an external distributed cache. There is no single drop-in SPI as clean as Hibernate's `RegionFactory`, so the practical seams are: (1) a `CacheInterceptor` registered on the descriptor that delegates get/put to the external store, and (2) cache coordination disabled in favor of the external store's own replication.

```java
public class RedisCacheInterceptor extends CacheInterceptor {
    private final RedisTemplate<String,byte[]> redis;
    public RedisCacheInterceptor(IdentityMap targetMap, AbstractSession session,
                                 RedisTemplate<String,byte[]> redis) {
        super(targetMap, session);
        this.redis = redis;
    }
    @Override
    public CacheKey getCacheKey(Object primaryKey, boolean forMerge) {
        // Try the in-process map first (fast), then fall back to Redis on a miss.
        CacheKey local = super.getCacheKey(primaryKey, forMerge);
        if (local != null) return local;
        byte[] blob = redis.opsForValue().get(key(primaryKey));
        if (blob == null) return null;
        Object entity = deserialize(blob);
        return super.internalPutCacheKey(buildCacheKey(primaryKey, entity));
    }
    @Override
    public Object remove(Object primaryKey, Object object) {
        redis.delete(key(primaryKey));        // keep the distributed store coherent
        return super.remove(primaryKey, object);
    }
}
```
```java
public class ProductCacheCustomizer implements DescriptorCustomizer {
    @Override public void customize(ClassDescriptor d) {
        d.setCacheInterceptorClass(RedisCacheInterceptor.class);
    }
}
```

**Integration seams:** `CacheInterceptor` is the supported hook — EclipseLink routes identity-map operations through it, so you get a two-tier topology (fast local map backed by a shared remote store). You disable EclipseLink's own JMS/RMI coordination because the external cache now owns cross-node coherence.

**Consistency pitfalls (the hard part):** (1) **serialization cost and staleness** — every miss deserializes an object graph; if you store whole graphs you reintroduce the `SEND_OBJECT_CHANGES` ordering anomalies, so prefer storing by PK and invalidating rather than replicating state. (2) **write coherence** — a write on node A must invalidate the Redis entry *and* every node's local map; the local maps don't see Redis deletes automatically, so you still need a pub/sub invalidation channel (Redis keyspace notifications) or you've just moved staleness from "between JVMs" to "between each JVM and Redis." (3) **the dual-write problem** — committing to the database and updating the cache are not atomic; if the cache write succeeds and the DB rolls back (or vice versa) you cache a value that never existed. The robust pattern is **invalidate, don't update**: on commit, *delete* the cache entry and let the next read repopulate from the DB, which sidesteps dual-write inconsistency at the cost of a cache miss. The expert conclusion most teams reach is that bolting a distributed cache under EclipseLink is rarely worth it — TTL + `@Version` + ISOLATED for volatile data delivers most of the benefit with far less distributed-systems risk, and a distributed cache makes sense only for genuinely read-mostly, expensive-to-rebuild reference graphs.

#### Q108. [Coding] Implement an idempotent "upsert" (insert-or-update) in EclipseLink that is safe under concurrency, and explain why naive `find-then-persist-or-merge` has a race.
The naive upsert — `find(id)`, if null `persist(new)`, else `merge(changes)` — has a classic time-of-check-to-time-of-use race: two threads both `find` null, both `persist`, and the second hits a duplicate-key `PersistenceException` at flush (or worse, both succeed if there's no unique constraint, producing a duplicate). A correct upsert must rely on a **database uniqueness guarantee** and handle the constraint violation, or use the database's native upsert.

```java
public Account upsert(EntityManagerFactory emf, String naturalKey, BigDecimal delta) {
    for (int attempt = 0; attempt < 3; attempt++) {
        EntityManager em = emf.createEntityManager();
        try {
            em.getTransaction().begin();
            Account a = em.createQuery(
                    "SELECT a FROM Account a WHERE a.naturalKey = :k", Account.class)
                .setParameter("k", naturalKey)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)   // lock the row if it exists
                .getResultList().stream().findFirst().orElse(null);
            if (a == null) {
                a = new Account(naturalKey, delta);
                em.persist(a);            // may collide if another tx inserted concurrently
            } else {
                a.setBalance(a.getBalance().add(delta));   // update path, row is locked
            }
            em.getTransaction().commit();
            return a;
        } catch (RollbackException | EntityExistsException ex) {
            safeRollback(em);
            if (isUniqueViolation(ex)) continue;   // someone inserted first -> retry as update
            throw ex;
        } finally { em.close(); }
    }
    throw new IllegalStateException("upsert failed after retries for " + naturalKey);
}
```

**Why the race exists and how this addresses it:** the only reliable arbiter of "does this row already exist" under concurrency is the **database unique constraint** on `naturalKey`, not an application-level `find`. The retry converts the race into a deterministic outcome: if two transactions both try to insert, exactly one wins the unique constraint and the loser catches the violation and retries, this time finding the row and taking the update path (now under a pessimistic lock so the balance update is serialized). **Time/Space:** O(retries), near 1 under low contention. **Edge cases:** (1) this *requires* a `UNIQUE(natural_key)` constraint — without it there is no race-stopper and you get duplicates; (2) `PESSIMISTIC_WRITE` on the select-existing path prevents lost updates on the balance, but adds deadlock risk if multiple keys are touched in inconsistent order — keep the transaction tiny; (3) the cleanest solution where the database supports it is a **native `INSERT ... ON CONFLICT (natural_key) DO UPDATE`** (PostgreSQL) or `MERGE` (Oracle/SQL Server) executed as a single atomic statement, which eliminates the read entirely — but it bypasses the persistence context, so you must `em.refresh()`/evict afterward to keep the L2 cache coherent. The interview point: idempotent upsert is a *database-constraint* problem, and EclipseLink's job is to surface and retry the violation, not to prevent the race in application code.

#### Q109. [Coding] Stream and process 50 million rows for an export job with bounded memory in EclipseLink. Show the cursor + clear pattern and the connection/transaction hazards.
A request-style `getResultList()` over 50M rows OOMs instantly — it buffers every row and registers each in the UnitOfWork. EclipseLink's server-side cursor streams rows on demand, but the trap is that the persistence context still accumulates every entity you touch unless you periodically clear it. The correct pattern combines a cursored stream, periodic `em.clear()`, and read-only results to avoid clone overhead.

```java
void exportAll(EntityManagerFactory emf, Writer out) {
    EntityManager em = emf.createEntityManager();
    em.getTransaction().begin();   // cursor lives within a transaction
    Query q = em.createQuery("SELECT o FROM Order o ORDER BY o.id");
    q.setHint(QueryHints.CURSOR, true);
    q.setHint(QueryHints.CURSOR_PAGE_SIZE, 1000);   // JDBC fetch size per round trip
    q.setHint(QueryHints.READ_ONLY, true);          // no UoW clone per row
    CursoredStream cursor = (CursoredStream) q.getSingleResult();
    try {
        long n = 0;
        while (cursor.hasNext()) {
            Order o = (Order) cursor.next();
            writeCsvRow(out, o);
            if (++n % 5000 == 0) {
                cursor.clear();   // release already-streamed buffered objects
                em.clear();       // detach processed entities -> bounded heap
            }
        }
    } finally {
        cursor.close();           // release the JDBC cursor + its connection
        em.getTransaction().commit();
        em.close();
    }
}
```

**Why each piece is required:** the cursor (`CURSOR` + `CURSOR_PAGE_SIZE`) holds a forward-only server-side cursor so the database streams in pages instead of materializing the whole set; `READ_ONLY` skips the per-row clone/backup the UnitOfWork would otherwise allocate; `em.clear()` every few thousand rows is the linchpin — without it the persistence context grows exactly as badly as a `List` would and you OOM anyway. **Time/Space:** O(1) memory (bounded by page size + buffer), O(N) total work, one stable cursor instead of N+1 paging queries. **The hazards that make this expert-level:** (1) the cursor **pins a JDBC connection** for the entire run, so a multi-hour export holds a pooled connection and can starve the pool — size the pool accordingly or use a dedicated pool; (2) the cursor is bound to its **transaction**, so a very long run risks transaction timeouts, lock retention, and (on Oracle) `ORA-01555 snapshot too old` if undo can't preserve read consistency that long — for truly huge jobs, prefer **keyset pagination in separate short transactions** (`WHERE id > :lastId ORDER BY id LIMIT 1000`) which releases the connection between pages at the cost of more queries; (3) ordering matters for keyset paging and for cursor stability; (4) `cursor.close()` in a `finally` is non-negotiable — a leaked cursor leaks a connection. The senior framing: cursors give bounded memory but trade away connection availability and transaction-duration safety, so for the very largest jobs the connection- and transaction-friendly choice is often short-transaction keyset paging, not one long cursor.

#### Q110. [Coding] Configure EclipseLink for a GraalVM native image: static weaving + reflection config. Walk through the build wiring and what fails if you skip a step.
Native image compiles ahead-of-time under a closed-world assumption: no runtime bytecode generation, no agent-based weaving, and reflection must be registered in advance. EclipseLink's defaults (dynamic weaving via a Java agent, reflection-driven descriptor building) violate all three, so the migration is "push everything EclipseLink does lazily at runtime to build time."

```xml
<!-- 1) Static weaving plugin: weave entity bytecode at build time, no runtime agent. -->
<plugin>
  <groupId>com.ethlo.persistence.tools</groupId>
  <artifactId>eclipselink-maven-plugin</artifactId>
  <executions>
    <execution>
      <id>weave</id><phase>process-classes</phase>
      <goals><goal>weave</goal></goals>
    </execution>
  </executions>
</plugin>
```
```json
// 2) reflect-config.json — register entities/converters/customizers GraalVM must keep.
[
  { "name": "com.shop.Order",
    "allDeclaredFields": true, "allDeclaredMethods": true,
    "allDeclaredConstructors": true },
  { "name": "com.shop.EncryptedStringConverter", "allDeclaredConstructors": true },
  { "name": "org.eclipse.persistence.internal.jpa.EntityManagerFactoryProvider" }
]
```
```properties
# 3) persistence.xml / properties: weaving still "true" so woven code is used,
#    but it is STATIC (done above), and disable anything needing runtime classgen.
eclipselink.weaving=static
eclipselink.target-server=none
```

**What fails if you skip a step:** if you **skip static weaving**, the build produces no woven accessors and at runtime there is no agent to weave them, so `@OneToOne(fetch=LAZY)` and `@Basic(fetch=LAZY)` silently become eager and attribute change tracking falls back to snapshot diffing — a correctness/perf regression, not a crash, which makes it insidious. If you **skip reflection config**, the native image strips classes/fields EclipseLink reaches via reflection (entity fields, converter constructors, the provider class), and you get a runtime `ClassNotFoundException`/`NoSuchMethodException` the first time a descriptor is built — usually at EMF creation. If you **leave dynamic weaving on**, the agent isn't present in native image and EMF bootstrap fails outright. **Trade-offs:** native image buys fast cold start and low memory (great for serverless/CLI), but the build is slower, debugging is harder, and any EclipseLink feature relying on runtime class generation (some dynamic-entity/virtual-attribute scenarios) is off the table. The expert note: even outside native image, this same discipline (static weaving, precomputed metadata) is the right move for **serverless cold starts**, because the EMF bootstrap — parsing metadata, building descriptors, weaving — is the dominant cold-start cost, so moving it to build time and keeping the EMF warm across invocations is the win.

#### Q111. [Coding] Implement optimistic-locking conflict *merging* (not just retry): when two users edit the same record, reconcile non-conflicting field changes instead of failing. Show the version-aware diff/merge.
A blanket retry re-applies the *whole* operation, which is wrong when two users edited *different* fields of the same row — you want to keep both changes (a three-way merge), failing only on genuinely conflicting fields. This requires carrying the original (base) version the user started from, computing what each side changed, and merging field-by-field.

```java
// Client submits: the entity it loaded (base) + the edited values + the base version.
public Document mergeEdit(EntityManager em, DocumentEdit edit) {
    em.getTransaction().begin();
    Document current = em.find(Document.class, edit.id(),
                              LockModeType.PESSIMISTIC_WRITE);   // lock to serialize the merge
    if (current.getVersion() == edit.baseVersion()) {
        applyAll(current, edit);                  // fast path: nobody else changed it
    } else {
        // Three-way merge: base (what user started from) vs current (DB) vs edit (user's changes)
        for (Field f : Document.editableFields()) {
            Object base    = f.get(edit.base());      // value when user opened the form
            Object mine    = f.get(edit.edited());     // user's new value
            Object theirs  = f.get(current);           // current DB value
            boolean iChanged    = !Objects.equals(base, mine);
            boolean theyChanged = !Objects.equals(base, theirs);
            if (iChanged && theyChanged && !Objects.equals(mine, theirs)) {
                throw new FieldConflictException(f.getName(), mine, theirs);  // real conflict
            } else if (iChanged) {
                f.set(current, mine);              // only I changed it -> take mine
            } // else keep theirs (their change or unchanged)
        }
    }
    em.getTransaction().commit();   // @Version bumps; PESSIMISTIC_WRITE made the check-and-set atomic
    return current;
}
```

**Why this beats naive retry:** retry treats any concurrent change as fatal and re-runs the operation, which either clobbers the other user's edit (last-write-wins) or forces the user to redo their work. Field-level three-way merge keeps both users' non-overlapping changes and only surfaces a conflict when both edited the *same* field to *different* values — the same model version control uses. **Why the pessimistic lock here:** the merge is a read-modify-write that must be atomic; `PESSIMISTIC_WRITE` (`SELECT ... FOR UPDATE`) serializes concurrent merges of the same row so two merges can't both compute against the same `current` and lose one. **Edge cases / trade-offs:** (1) you must transmit the *base* state to the client and back (more payload, and the base must be tamper-checked); (2) field-level merge is only safe for genuinely independent fields — fields with cross-field invariants (e.g., `startDate`/`endDate`) can't be merged independently without violating the invariant, so those need to be conflict-grouped; (3) holding a pessimistic lock across the merge keeps it short but adds deadlock risk under high contention; (4) for very hot rows, this serializes writers — at extreme scale you'd move to CRDTs or an event-sourced model instead. The senior framing: optimistic locking *detects* conflicts; turning detection into *resolution* is a domain-modeling decision about which fields are independently mergeable, and EclipseLink's `@Version` is just the trigger.

#### Q112. [Coding] Write an integration test that asserts on the exact number of SQL statements a service method issues, to catch N+1 regressions. Why is asserting on results insufficient?
Fetch-strategy regressions are invisible in the returned objects — a method that loads 100 orders with their line items returns identical results whether it issued 2 queries (batched) or 101 queries (N+1). The only way to catch the regression in a test is to **count the SQL statements** and assert the count, turning "it's slow in production" into a failing build.

```java
class OrderServiceSqlCountIT {
    @RegisterExtension static SqlCountExtension sql = new SqlCountExtension();

    @Test
    void loadingOrdersWithItemsIssuesExactlyTwoQueries() {
        sql.reset();
        List<Order> orders = orderService.findRecentWithItems(100);  // page of 100
        assertEquals(100, orders.size());
        orders.forEach(o -> o.getLineItems().size());  // force the relationship to resolve

        // 1 query for the orders + 1 batched IN query for all line items = 2.
        assertEquals(2, sql.selectCount(),
            "N+1 regression: expected 2 SELECTs (orders + batched items), got " + sql.selectCount());
    }
}

// Counting hook: a session event listener that increments on each SQL call.
class SqlCountExtension implements BeforeAllCallback {
    private final AtomicInteger selects = new AtomicInteger();
    public void reset() { selects.set(0); }
    public int selectCount() { return selects.get(); }
    @Override public void beforeAll(ExtensionContext ctx) {
        Session session = JpaHelper.getServerSession(emf);
        session.getEventManager().addListener(new SessionEventAdapter() {
            @Override public void preExecuteQuery(SessionEvent e) {
                if (e.getQuery() instanceof ReadAllQuery || e.getQuery() instanceof ReadObjectQuery)
                    selects.incrementAndGet();
            }
        });
    }
}
```

**Why asserting on results is insufficient:** the result object graph is byte-for-byte identical regardless of how it was fetched, so a value assertion (`assertEquals(expected, orders)`) passes under both the efficient and the N+1 implementation. The *cost* — number of round trips — lives only in the SQL trace. By capturing the statement count via a `SessionEventListener` (or the EclipseLink `PerformanceMonitor` counters, or a JDBC proxy like datasource-proxy/p6spy), you make the fetch strategy an explicit, regression-tested contract. **Why this is hard to get right:** (1) the count is **cache-sensitive** — if the L2 cache already holds the line items, the batch query won't fire and the count is 1, so you must `emf.getCache().evict(...)`/`em.clear()` to a known cache state before the assertion, or run with caching disabled for the test; (2) it is **weaving-sensitive** — without weaving, lazy associations behave differently, so the test must run with the same weaving as production; (3) you must *trigger* the lazy relationship inside the test (the `forEach(... .size())` line) or the batch query never fires and you'd wrongly assert 1. The senior point: SQL-count assertions are the only durable defense against the most common ORM performance regression, and they belong on the critical read paths exactly because the result-based tests give false confidence.

#### Q113. [Coding] Implement a `CacheInvalidationPolicy` (TTL + version-aware) via a `DescriptorCustomizer`, and explain how it interacts with `@Version` to bound staleness without coordination.
When you can't (or won't) run cluster cache coordination, the robust way to bound staleness is **time-based invalidation** plus **optimistic versioning**: the cache entry expires after a TTL so a stale read can only persist for at most that window, and the `@Version` column guarantees that even if a stale object is used for a *write*, the write fails rather than silently clobbering a newer row.

```java
public class ProductCacheInvalidationCustomizer implements DescriptorCustomizer {
    @Override
    public void customize(ClassDescriptor descriptor) {
        // Entries older than 60s are treated as invalid and re-read on next access.
        TimeToLiveCacheInvalidationPolicy ttl =
            new TimeToLiveCacheInvalidationPolicy(60_000L);
        // Only refresh if the DB row is actually newer (cheap version check before full reload).
        ttl.setShouldUpdateReadTimeOnUpdate(true);
        descriptor.setCacheInvalidationPolicy(ttl);
    }
}

@Entity
@Customizer(ProductCacheInvalidationCustomizer.class)
public class Product {
    @Id @GeneratedValue private Long id;
    private BigDecimal price;
    @Version private long version;   // the safety net the TTL leans on
}
```

**How TTL and `@Version` combine:** the TTL bounds *read* staleness — after 60s any access re-reads from the database, so the worst-case window a node serves a stale price is the TTL, not "forever until something invalidates it." But TTL alone doesn't protect *writes*: a node could read a stale `Product` at second 59 and submit an update. That's where `@Version` is the backstop — EclipseLink appends `WHERE id=? AND version=?` to the update, so a write based on stale state updates zero rows and throws `OptimisticLockException`, which you retry against fresh state. Together they give **bounded read staleness + zero lost updates** with no broker, no JMS, no distributed coordination — just a clock and a version column.

```
without coordination:   read stale until something evicts it  (unbounded)  + lost-update risk
TTL only:               read stale <= TTL                     (bounded)    + lost-update risk
TTL + @Version:         read stale <= TTL                     (bounded)    + writes are SAFE
```

**Trade-offs:** a shorter TTL tightens staleness but increases database reads (more cache misses); `refreshOnlyIfNewer`/version-aware refresh reduces the cost of expiry by checking the version before a full reload. The honest comparison interviewers want: cache coordination (JMS/RMI) gives *near-immediate* cross-node freshness but adds a broker dependency that, when it fails, silently reintroduces unbounded staleness — whereas TTL + `@Version` degrades gracefully (worst case is bounded staleness, never lost data) and has no extra moving parts. For most systems the latter is the better operational trade; coordination is justified only when sub-TTL freshness is a hard requirement.

#### Q114. [Practical] Design the persistence layer for a high-throughput order system on EclipseLink: choose isolation, fetch strategy, locking, ID generation, and cache policy per entity, and justify each.
A good answer treats persistence design as a set of *per-entity* decisions driven by each entity's read/write profile, not a single global setting. I'd lay out the entities and decide each axis with its justification.

```
Entity         | Read:Write | ID gen     | Cache policy           | Locking        | Fetch
---------------+------------+------------+------------------------+----------------+------------------
Product/Catalog| 100:1      | SEQUENCE   | SHARED, SOFT, TTL 600s | OPTIMISTIC     | batch IN children
Customer       | 20:1       | SEQUENCE   | SHARED, SOFT, TTL 300s | OPTIMISTIC     | lazy, graph/endpoint
Order (header) | 3:1        | SEQUENCE   | ISOLATED / NONE        | OPTIMISTIC     | lazy lineItems
LineItem       | 3:1        | SEQUENCE   | ISOLATED               | (via Order)    | batch IN by order
Inventory      | 1:5        | SEQUENCE   | NONE                   | PESSIMISTIC_WR | n/a (point reads)
AuditEvent     | write-only | SEQUENCE   | NONE                   | none           | n/a (append)
```

**Justifications:** *Catalog/Product* is read-mostly and changes rarely, so a `SHARED` cache with `SOFT` references and a TTL gives the biggest hit-rate win; `SEQUENCE` (not `IDENTITY`) preserves JDBC batch writing for bulk catalog loads, and optimistic locking suffices because price edits are infrequent. *Order/LineItem* are transactional and volatile, so I make them `ISOLATED` (or uncached) to avoid stale-read bugs and cross-request bleed; `lineItems` are fetched with `BATCH IN` to kill N+1 when listing orders, and lazy by default with per-endpoint entity graphs deciding when to eager-fetch. *Inventory* is write-heavy and contended (decrement on each sale), so it gets `PESSIMISTIC_WRITE` to prevent oversell under concurrency and no caching (it must be authoritative); the transaction touching it must be tiny to limit lock hold time. *AuditEvent* is append-only, so no cache, no locking, and ideally batch-inserted.

**Cross-cutting design choices:** enable **static weaving** (production: scalar lazy + attribute change tracking), enable **JDBC batch writing** for the catalog/audit write paths, externalize pooling to **HikariCP** sized to the database's connection budget, and put a `@Version` column on every mutable entity as the universal lost-update guard. For staleness I'd rely on **TTL + `@Version`** rather than cluster coordination — bounded staleness with no broker dependency. **Trade-offs to articulate:** caching catalog data trades a small staleness window (bounded by TTL) for a large read-throughput win; isolating order data trades cache hits for correctness and tenant safety; pessimistic locking on inventory trades throughput on that hot row for correctness (no oversell). The senior framing: there is no global "right" setting — the design is a per-entity classification by read/write ratio, volatility, contention, and consistency requirement, and the artifact I'd produce is exactly the table above with the justification column, then validate it with SQL-count tests and a load test that includes realistic write bursts.

#### Q115. [Coding] Implement graceful handling of database failover/stale connections in EclipseLink so in-flight transactions fail fast and recover. Show the connection-validation and retry wiring.
When a database node fails over, pooled connections become stale — they look open but the next statement throws a connectivity error. Without protection, the first few requests after a failover get cryptic `SQLException`s and may hang on a dead socket. The defense is connection validation (don't hand out dead connections), aggressive timeouts (fail fast instead of hanging), and a retry on the recoverable connectivity errors.

```java
// 1) HikariCP validates connections and caps how long a borrow/socket can block.
HikariConfig hc = new HikariConfig();
hc.setJdbcUrl("jdbc:postgresql://primary:5432,replica:5432/shop?targetServerType=primary"
            + "&connectTimeout=3&socketTimeout=10");  // driver-level failover + timeouts
hc.setConnectionTestQuery(null);                       // use JDBC4 isValid() (preferred)
hc.setValidationTimeout(2_000);
hc.setMaxLifetime(1_800_000);                          // recycle before infra kills idle conns
hc.setConnectionTimeout(5_000);                        // fail fast if pool can't give a conn
DataSource ds = new HikariDataSource(hc);
```
```java
// 2) Retry only RECOVERABLE connectivity failures (not constraint/business errors).
public <T> T withConnectionRetry(EntityManagerFactory emf, Function<EntityManager,T> work) {
    for (int attempt = 1; ; attempt++) {
        EntityManager em = emf.createEntityManager();
        try {
            em.getTransaction().begin();
            T r = work.apply(em);
            em.getTransaction().commit();
            return r;
        } catch (PersistenceException ex) {
            safeRollback(em);
            if (attempt < 3 && isTransientConnectivity(ex)) {  // SQLState 08xxx, "connection closed"
                sleepWithJitter(attempt);
                continue;       // pool will hand out a FRESH, validated connection next time
            }
            throw ex;
        } finally { em.close(); }
    }
}
```

**Why each piece matters:** **connection validation** (`isValid()` on borrow) ensures EclipseLink never executes on a stale connection — the pool quietly discards dead ones and creates fresh ones pointing at the new primary. **`socketTimeout`/`connectTimeout`** convert a hung socket on a dead node into a prompt exception instead of a thread blocked for the OS TCP timeout (minutes). **`maxLifetime`** proactively recycles connections so they don't outlive an infra-side idle killer or a planned failover. **Retry on transient connectivity** (SQLState class `08`) recovers the first requests after failover, while *not* retrying constraint violations or business errors (which would just fail again). **Edge cases / hazards:** (1) you must distinguish *transient* (connection dropped, failover in progress) from *permanent* (bad SQL, constraint) errors — retrying the latter is wasted work; (2) a transaction that already did non-idempotent side effects must not be blindly retried (same rule as optimistic retry); (3) EclipseLink's own internal pool lacks Hikari's validation maturity, which is the concrete reason to externalize pooling for any system that must survive failover; (4) the JDBC URL's multi-host `targetServerType=primary` lets the driver itself re-resolve the primary after failover, so the pool's fresh connections land on the new leader. The senior framing: graceful failover is mostly a *pool + driver* concern (validate, time out, recycle), with EclipseLink contributing the transaction boundary and a narrow, error-class-aware retry on top.

#### Q116. [Behavioral] Tell me about a time you debugged a severe, intermittent production data-correctness issue in an ORM-backed system under pressure. How did you lead the investigation? (STAR)
Use **STAR** and emphasize disciplined diagnosis over heroics — at the staff level the interviewer is assessing how you reason under ambiguity and how you lead others, not whether you personally typed the fix. *Situation:* a payments service on EclipseLink intermittently showed customers a stale account balance after a top-up — roughly 1 in 500 requests, never reproducible on demand, and escalating because it eroded trust. *Task:* I owned the incident as tech lead; the pressure was a board-visible trust metric and a team already guessing at random fixes (people wanted to "just turn off caching everywhere").

*Action:* I refused to let the team change anything before we had evidence, because a blind config change on a heisenbug either hides it or makes it worse without teaching us the cause. I framed the three-layer read path explicitly for the team — L1 context, L2 shared cache, query bypass — and we instrumented to *prove* which layer lied: FINE SQL logging with parameters, plus capturing `emf.getCache().contains(...)` and comparing the cached `@Version` against `SELECT version` from the row on each occurrence. Within a day the data showed the pattern: the staleness only appeared on accounts a *batch reconciliation job* had updated with a bulk `UPDATE ... executeUpdate()` — which bypasses the persistence context and **does not invalidate the L2 cache**. The shared cache was serving the pre-batch balance. I assigned one engineer to write a failing integration test reproducing it (bulk update via a second connection, then read through EclipseLink), so we had a regression lock before touching production.

*Result:* the fix was surgical, not a sledgehammer — we set `QueryHints.INVALIDATE_CACHE` on the batch job's bulk update so it evicted the affected class, added a `@Version`-based TTL on the account entity as a backstop, and kept the SQL-count and stale-read tests in CI. The 1-in-500 errors went to zero, and crucially we *understood* why rather than having masked it. The lessons I draw out for the interviewer: under pressure the instinct to change things is the enemy — **evidence first, one change at a time, reproduce before you fix**; lead by giving the team a correct mental model (the three-layer read path) so the whole team debugs effectively instead of one hero; and remember that bulk DML and external writes bypassing the cache is the classic ORM correctness trap, so the durable fix includes a *test* that encodes the lesson, not just a config tweak.

#### Q117. [Coding] Implement a DTO projection with the JPQL constructor expression (`SELECT NEW ...`) and explain why it is better than fetching full entities for read-only screens.
For a read-only list/report screen you rarely need a managed entity graph — you need a handful of columns shaped into a transfer object. The JPQL **constructor expression** (`SELECT NEW`) projects directly into a DTO, so EclipseLink selects only the listed columns, builds plain (non-managed) objects, and never registers anything in the persistence context.

```java
public record OrderSummary(Long id, String customer, BigDecimal total, long itemCount) {}

public List<OrderSummary> summaries(EntityManager em) {
    return em.createQuery(
        "SELECT NEW com.shop.OrderSummary(o.id, o.customer, o.total, SIZE(o.lineItems)) " +
        "FROM Order o ORDER BY o.id DESC", OrderSummary.class)
        .setMaxResults(100)
        .getResultList();
}
```

**Why this beats fetching entities:** a full-entity query selects every mapped column (wide rows), registers each result in the UnitOfWork (clone + backup for change tracking), and risks N+1 when the view touches associations. The constructor projection selects exactly the four expressions, produces immutable DTOs with **zero persistence-context overhead**, and the `SIZE()` aggregate computes the count in SQL instead of loading the collection. It is the cleanest answer to "this screen is read-only and slow." **Time/Space:** one SQL statement projecting N columns; no per-row clone, so memory is just the DTOs. **Edge cases / constraints:** (1) the DTO needs a constructor matching the *exact* argument types and order — a `BigDecimal`/`Double` mismatch fails at runtime, not compile time, so this is a place integration tests earn their keep; (2) the constructor class must be referenced by fully-qualified name in JPQL (no import aliasing), though EclipseLink also supports `ConstructorResult` for native SQL; (3) you cannot navigate lazy associations on a DTO afterward (it isn't managed) — fetch everything you need in the projection; (4) `SIZE()` becomes a correlated subquery or join, so verify the generated SQL on large tables. The senior framing: DTO projection is the right default for queries whose results flow straight to serialization/display, complementing fetch groups (partial *entities*) — projections when you don't need an entity at all, fetch groups when you need a partial entity you might still navigate.

#### Q118. [Coding] Map an entity with a composite primary key two ways — `@IdClass` and `@EmbeddedId` — and explain the trade-offs and the `equals`/`hashCode` requirement.
Composite keys arise with natural keys and join tables. JPA offers two mappings: `@IdClass` (the key fields live on the entity, with a separate class mirroring them) and `@EmbeddedId` (a single embeddable holds the key). Both require the key class to be `Serializable` and to implement `equals`/`hashCode`, because EclipseLink uses the key object as the identity-map `CacheKey` — a broken `equals`/`hashCode` breaks cache lookups and `find()`.

```java
// ---- Option A: @IdClass ----
public class OrderLineId implements Serializable {
    private Long orderId; private int lineNo;          // names MUST match entity field names
    public OrderLineId() {}
    public OrderLineId(Long o, int l) { orderId = o; lineNo = l; }
    @Override public boolean equals(Object x) {
        if (!(x instanceof OrderLineId k)) return false;
        return Objects.equals(orderId, k.orderId) && lineNo == k.lineNo;
    }
    @Override public int hashCode() { return Objects.hash(orderId, lineNo); }
}
@Entity @IdClass(OrderLineId.class)
public class OrderLine {
    @Id private Long orderId;
    @Id private int lineNo;
    private String sku;
}

// ---- Option B: @EmbeddedId ----
@Embeddable
public class OrderLineKey implements Serializable {
    private Long orderId; private int lineNo;
    /* ctor, equals, hashCode as above */
}
@Entity
public class OrderLine2 {
    @EmbeddedId private OrderLineKey id;
    private String sku;
}
// find:  em.find(OrderLine2.class, new OrderLineKey(42L, 1));
```

**Trade-offs:**

```
                  | @IdClass                          | @EmbeddedId
------------------+-----------------------------------+-----------------------------------
Key fields        | duplicated on entity + id class   | encapsulated in one embeddable
JPQL access       | o.orderId  (flat)                 | o.id.orderId  (nested path)
find() argument   | new OrderLineId(...)              | new OrderLineKey(...)
Reuse / cleanliness| more boilerplate, fields repeated | single cohesive key type, reusable
Derived ids (@MapsId)| works                           | works, often cleaner
```

`@EmbeddedId` is usually the better default — the key is a first-class, reusable value object and JPQL paths make the composite nature explicit. `@IdClass` keeps flatter JPQL (`o.orderId` not `o.id.orderId`) which some teams prefer for terseness. **Critical edge case:** the key class's `equals`/`hashCode` must include *all* key fields and be stable — EclipseLink keys the identity map and L2 cache by this object, so a missing field or a mutable key makes two distinct rows collide in cache or makes `find()` miss; and the key must be immutable in practice (never mutate a key field on a managed entity). For relationships involving part of the composite key, prefer `@MapsId` to derive the identity from the association rather than mapping the FK column twice.

#### Q119. [Coding] Design a transactional outbox so domain events are published atomically with the database write in an EclipseLink service. Why not just publish to the broker inside the transaction?
The problem is the **dual-write**: a service that commits an order to the database *and* publishes an "OrderPlaced" event to Kafka has two independent commit points. If the DB commits and the broker publish fails (or vice versa), state and events diverge — a lost event or a phantom event. The **transactional outbox** makes the event part of the *same* database transaction: you insert the event into an `outbox` table in the same EclipseLink transaction as the business change, so they commit atomically, and a separate relay process reads the outbox and publishes to the broker with at-least-once delivery.

```java
@Entity
public class OutboxEvent {
    @Id @GeneratedValue private Long id;
    private String aggregateType; private String aggregateId;
    private String type;
    @Lob private String payload;          // serialized event JSON
    private Instant occurredAt;
    private boolean published = false;
}

// Business write + event insert in ONE transaction -> atomic.
public void placeOrder(EntityManager em, Order order, ObjectMapper mapper) {
    em.getTransaction().begin();
    em.persist(order);
    OutboxEvent ev = new OutboxEvent();
    ev.setAggregateType("Order"); ev.setAggregateId(order.getId() + "");
    ev.setType("OrderPlaced");
    ev.setPayload(toJson(mapper, new OrderPlaced(order.getId(), order.getTotal())));
    ev.setOccurredAt(Instant.now());
    em.persist(ev);                       // same tx as the order
    em.getTransaction().commit();         // both rows commit together, or neither does
}
```

```
   [ service tx ]                         [ relay (separate) ]
   INSERT order        ----commit---->    poll outbox WHERE NOT published
   INSERT outbox row          (atomic)    publish to Kafka  (at-least-once)
                                          mark published / delete row
```

**Why not publish inside the transaction:** publishing to the broker is a *non-transactional external call*; it isn't enrolled in the database transaction, so there is no atomicity between "row committed" and "message sent." Even XA/2PC across DB+broker is fragile, operationally heavy, and many brokers don't support it well. The outbox sidesteps distributed transactions entirely by reducing the problem to a single local DB commit. **Design details and trade-offs:** (1) delivery is **at-least-once**, so consumers must be **idempotent** (dedupe on event id) — the outbox guarantees the event is *eventually* published, not exactly once; (2) the relay can be a polling job (simple, slight latency) or change-data-capture tailing the DB log (Debezium — lower latency, no polling load); (3) order of events per aggregate matters — partition/relay by `aggregateId`; (4) the outbox table needs a cleanup/retention policy so it doesn't grow unbounded; (5) EclipseLink-specific note — batch the relay's reads with a cursor or keyset paging and mark-published in bulk, and keep the outbox entity uncached/`ISOLATED` since the relay mutates it out of band. The senior framing: the outbox trades exactly-once-illusion for an honest at-least-once + idempotent-consumer contract, which is the only robust model for atomic state-plus-event in a non-XA world.

#### Q120. [Coding] Use MOXy to bind one POJO to both XML and JSON, including a bidirectional relationship with `@XmlInverseReference`. Why does plain JAXB choke on the bidirectional graph?
A core MOXy value proposition is one mapping driving both formats, and its signature feature is handling **bidirectional object graphs** (an `Order` knows its `LineItem`s and each `LineItem` knows its `Order`) without infinite recursion. Plain JAXB has no concept of a back-reference, so marshalling a cycle either loops forever or, with manual `@XmlTransient`, loses the parent link on unmarshal. `@XmlInverseReference` tells MOXy "this side is the inverse — don't serialize it, reconstruct it on unmarshal."

```java
@XmlRootElement
@XmlAccessorType(XmlAccessType.FIELD)
public class Order {
    @XmlAttribute private Long id;
    private String customer;
    private List<LineItem> lineItems = new ArrayList<>();
}

@XmlAccessorType(XmlAccessType.FIELD)
public class LineItem {
    private String sku;
    private int qty;
    @XmlInverseReference(mappedBy = "lineItems")   // back-pointer, not serialized
    private Order order;
}

// One context, two output formats:
Map<String,Object> props = new HashMap<>();
JAXBContext ctx = JAXBContextFactory.createContext(new Class[]{Order.class}, props);

Marshaller xml = ctx.createMarshaller();          // XML output
xml.marshal(order, System.out);

Marshaller json = ctx.createMarshaller();          // JSON output, same context
json.setProperty(MarshallerProperties.MEDIA_TYPE, "application/json");
json.setProperty(MarshallerProperties.JSON_INCLUDE_ROOT, false);
json.marshal(order, System.out);
```

**Why plain JAXB chokes:** JAXB marshalling walks the object graph depth-first; with a true cycle (`order -> lineItem -> order -> ...`) it recurses until it stack-overflows. The usual JAXB workaround is `@XmlTransient` on the back-reference, but then on *unmarshal* each `LineItem.order` comes back null — you've broken the bidirectional link, forcing manual re-wiring in application code. `@XmlInverseReference` solves both: it omits the back-reference during marshal (no cycle) *and* repopulates it during unmarshal (each child's `order` is set to its parent automatically), which is exactly what you need for JPA-style bidirectional entities. **Trade-offs / when to use MOXy:** the honest comparison is that **Jackson is faster and the de facto JSON default in Spring Boot**, so you reach for MOXy specifically when you need *unified* XML+JSON from one mapping (e.g., a legacy SOAP + modern REST surface over the same domain), external `bindings.xml` to map classes you can't annotate, or `@XmlPath` XPath mapping. Choosing MOXy purely for JSON in a greenfield service swims against the ecosystem; choosing it for dual-format binding of a cyclic domain model is exactly its sweet spot. **Edge case:** `mappedBy` must name the owning collection field precisely, and the relationship must genuinely be bidirectional — pointing it at the wrong field silently produces a null back-reference.

#### Q121. [Coding] Implement keyset (seek) pagination in EclipseLink for an infinite-scroll feed and explain why it beats `setFirstResult`/`setMaxResults` offset paging at scale.
Offset pagination (`setFirstResult(page*size)`) degrades badly on deep pages because the database must **scan and discard** every row before the offset — page 10,000 of a 20-row feed scans 200,000 rows to return 20. Keyset (seek) pagination instead remembers the *last seen sort key* and asks for rows *after* it, so every page is an indexed range scan of constant cost regardless of depth.

```java
public record Page<T>(List<T> rows, Long nextCursor) {}

// First page: cursor == null. Subsequent pages pass the last id from the prior page.
public Page<Order> feed(EntityManager em, Long afterId, int size) {
    TypedQuery<Order> q;
    if (afterId == null) {
        q = em.createQuery(
            "SELECT o FROM Order o ORDER BY o.id DESC", Order.class);
    } else {
        q = em.createQuery(
            "SELECT o FROM Order o WHERE o.id < :after ORDER BY o.id DESC", Order.class)
            .setParameter("after", afterId);     // SEEK past the last row, no offset scan
    }
    List<Order> rows = q.setMaxResults(size)
                        .setHint("eclipselink.read-only", true)
                        .getResultList();
    Long next = rows.size() < size ? null : rows.get(rows.size() - 1).getId();
    return new Page<>(rows, next);
}
```

**Why keyset wins at scale:**

```
                 | Offset paging                  | Keyset (seek) paging
-----------------+--------------------------------+-------------------------------------
Deep-page cost   | O(offset + size)  scan+discard | O(log n + size)  indexed range scan
Stable under     | NO — inserts/deletes shift     | YES — anchored to a real key value,
 concurrent write|     offsets, rows skip/repeat   |     no row skipped or duplicated
Random page jump | yes (page 7 directly)          | no (must walk sequentially)
Index dependency | helps but offset still scans   | requires an index on the sort key
```

The two killer advantages are **constant cost** (a `WHERE id < :after ORDER BY id DESC LIMIT n` is an indexed range scan, so page 10,000 costs the same as page 1) and **correctness under concurrent writes** — offset paging shifts every offset when a row is inserted/deleted, so a user scrolling can see a row twice or skip one, whereas keyset is anchored to an actual key value and is immune. **Edge cases / requirements:** (1) the sort key must be **unique and indexed** — if you sort by a non-unique column (e.g., `createdAt`), append a tiebreaker (`ORDER BY createdAt DESC, id DESC` and seek on the pair) or pages can drop rows at value boundaries; (2) keyset can only move forward/backward sequentially, not jump to an arbitrary page number, so it fits infinite scroll and "load more," not a page-number UI; (3) `read-only` avoids the per-row UoW clone since feed rows flow straight to serialization. The senior framing: offset paging is fine for small, shallow result sets and arbitrary page jumps; for large feeds and infinite scroll, keyset is both faster and *more correct*, and the cost is giving up random page access plus needing a proper index and tiebreaker.

#### Q122. [Coding] Map a derived identity with `@MapsId` (a child whose PK is also its FK to the parent), and explain why this is cleaner than mapping the foreign-key column twice.
A one-to-one or "weak entity" relationship where the child shares the parent's primary key (e.g., `UserProfile` keyed by the same id as `User`) is best expressed with `@MapsId`, which tells JPA/EclipseLink to *derive* the child's identity from the association rather than mapping the join column as a separate `@Id` field. This avoids the classic duplication where you'd map `userId` both as the `@Id` and as the `@JoinColumn`, and the resulting double-write / mismatch bugs.

```java
@Entity
public class User {
    @Id @GeneratedValue private Long id;
    private String email;
}

@Entity
public class UserProfile {
    @Id private Long id;                  // PK == the owning User's id (derived)

    @MapsId                                // derive this entity's id from the `user` association
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id")               // FK column IS the PK column
    private User user;

    private String bio;
}

// Usage: you do NOT set the id yourself — it comes from the associated User.
UserProfile p = new UserProfile();
p.setUser(managedUser);                   // @MapsId copies managedUser.id into p.id at flush
p.setBio("...");
em.persist(p);                            // INSERT user_profile(id, bio) VALUES (user.id, ...)
```

**Why this is cleaner than mapping the FK twice:** without `@MapsId` you'd typically have a separate `@Id Long userId` *and* a `@OneToOne @JoinColumn(name="userId", insertable=false, updatable=false) User user`, which means the identity value lives in two places that can drift, you must remember to set the raw `userId` manually, and the `insertable=false/updatable=false` dance is error-prone. `@MapsId` makes the relationship the single source of truth for identity — you set the `User`, and EclipseLink derives the PK from it at flush, guaranteeing the FK and PK are always equal by construction. **Edge cases / trade-offs:** (1) you must assign the parent *before* persist so the id can be derived — persisting a `UserProfile` with a null `user` fails; (2) the parent must already have its id (persist the `User` first, or cascade), since a `@GeneratedValue` parent id isn't known until its own insert/flush; (3) `@MapsId` also handles the composite case — a child whose PK is `(parentId, someLocalKey)` uses `@MapsId("parentId")` inside an `@EmbeddedId` to map just that portion to the association; (4) the shared-PK one-to-one lets EclipseLink fetch the child by the same key as the parent, which is efficient and cache-friendly. The senior framing: `@MapsId` is the idiomatic way to model identifying relationships and weak entities, and reaching for it signals you understand the difference between an entity that *has* a foreign key and one whose very identity *is* that foreign key.

#### Q123. [Practical] Design a safe path to enable the EclipseLink L2 shared cache on a legacy service that currently runs with caching effectively disabled, without introducing stale-read incidents. What is your rollout plan?
The risk here is asymmetric: turning on the shared cache can dramatically cut database load, but a single mis-classified volatile entity introduces silent stale-read bugs that erode trust. So the design is a *staged, per-entity, evidence-driven* rollout rather than a global flip, and it leans on the spec-portable `shared-cache-mode=ENABLE_SELECTIVE` so caching is *opt-in* per entity rather than the EclipseLink-default broad-on behavior.

```xml
<!-- Start from opt-in, not the default broad caching. Only @Cacheable(true) entities cache. -->
<persistence-unit name="orders-pu">
  <shared-cache-mode>ENABLE_SELECTIVE</shared-cache-mode>
</persistence-unit>
```
```java
@Entity @Cacheable(true)
@Cache(type = CacheType.SOFT, size = 10_000, expiry = 300_000)   // bounded + TTL safety net
public class Currency { @Id String code; String symbol; }        // read-only reference data
```

**Rollout plan:**

```
Phase 0  Classify every entity by write profile + staleness tolerance:
           read-only reference (Currency, Country)  -> cache candidate
           read-mostly, edited rarely (Product)      -> cache candidate, TTL + @Version
           transactional / volatile (Order, Balance) -> DO NOT cache (or ISOLATED)
           tenant/security-scoped                    -> ISOLATED (never SHARED)
Phase 1  Add @Version to every cache candidate first (lost-update safety net).
Phase 2  ENABLE_SELECTIVE; mark ONLY the safest reference entities @Cacheable(true) with TTL.
Phase 3  Add SQL-count + stale-read integration tests; deploy to one canary instance.
Phase 4  Watch cache hit ratio + stale-read alarms; widen to read-mostly entities one at a time.
Phase 5  Add an explicit evict hook for any out-of-band writers (batch jobs, sibling apps).
```

**Why each guardrail:** starting with `ENABLE_SELECTIVE` inverts EclipseLink's default so nothing caches by accident — you whitelist deliberately. A **TTL** on every cached entity bounds the worst-case staleness window even if you mis-classify, converting "stale forever" into "stale for at most N seconds." A **`@Version` column** added *before* enabling caching guarantees that even a stale cached object cannot silently clobber a newer row on write. **Canarying one entity at a time** means any stale-read regression is attributable to a single change and reversible by un-marking `@Cacheable`. **Edge cases / failure modes to call out:** (1) out-of-band writers (DBA scripts, bulk `executeUpdate`, sibling services) bypass the cache, so any cached entity that *anything* writes outside this EMF needs an explicit eviction path or must stay uncached; (2) multitenant/security entities must be `ISOLATED` regardless of caching desire — cross-tenant bleed is a security incident, not a perf bug; (3) measure the *actual* hit ratio (`PerformanceMonitor` counters) — caching an entity nobody re-reads adds memory pressure for no benefit. The senior framing: enabling the cache is a *risk-managed migration*, not a config toggle — the artifact is the per-entity classification table, the order is "version column → opt-in → canary → widen," and TTL + `@Version` are the safety nets that make a mistake bounded and recoverable rather than a trust-destroying incident.

#### Q124. [Behavioral] Describe how you raised the bar on persistence/ORM practices across multiple teams using EclipseLink. How did you drive adoption without being the bottleneck? (STAR)
Use **STAR**, and frame it as *systemic* change — at the staff level the interviewer wants to see you scale your impact through standards, tooling, and enablement rather than by personally reviewing every query. *Situation:* across four teams sharing a WebLogic/EclipseLink platform, we had recurring production incidents from the same handful of root causes — N+1 fetches, stale L2 reads after batch jobs, missing `@Version` columns, and unbounded result sets causing OOMs. Each team rediscovered these the hard way, and I was becoming a single point of escalation because I'd seen them all before. *Task:* I wanted to eliminate the *class* of problem, not keep firefighting instances, and to do it without becoming the mandatory reviewer that bottlenecked every team.

*Action:* I converted tribal knowledge into **executable guardrails** rather than a wiki page nobody reads. Concretely: (1) a shared test utility that asserts SQL statement counts so N+1 regressions fail CI automatically; (2) a startup health check that pings every entity to catch mapping/schema drift at deploy time; (3) an architecture decision record codifying the per-entity cache-isolation policy (reference data `SHARED`+TTL, transactional `ISOLATED`, tenant data never `SHARED`) plus the mandatory `@Version` rule; and (4) a lightweight "persistence review" checklist that teams self-apply on PRs touching entities, so the knowledge lives with them. I deliberately *paired* with one engineer per team to land the first adoption rather than presenting top-down — they became the local advocate, so the practice spread through peers, not mandate. I also ran a short brown-bag on the three-layer read path (L1/L2/query-bypass) so everyone shared the same mental model when debugging.

*Result:* the recurring incident categories dropped sharply over two quarters, and crucially the teams stopped routing every persistence question to me — the CI guardrails caught regressions automatically and the checklist + ADR answered the common questions, so my involvement shifted from firefighting to occasional deep consults. The lessons I emphasize: **encode standards as automation, not documentation** (a failing build teaches better than a wiki); **scale through local champions** so adoption is peer-driven and you're not the bottleneck; and **give people the mental model**, not just the rule, so they can reason about novel cases. The anti-pattern I explicitly avoided was making myself the gatekeeper — durable bar-raising means the system enforces the bar after you step away, which is exactly what distinguishes leverage from heroics at the staff level.

## ✅ Key Takeaways
- EclipseLink is the **Jakarta Persistence reference implementation**, descended from Oracle TopLink, and also bundles **MOXy (JAXB/JSON)**, NoSQL/EIS, and SDO.
- Its **L2 shared cache is ON by default** (the inverse of Hibernate) with explicit `SHARED`/`ISOLATED`/`PROTECTED` isolation via `@Cache` — design isolation deliberately, especially under multitenancy.
- **Weaving** (static at build, or dynamic via Java agent) enables true scalar lazy loading, change tracking, and fetch groups; prefer **static weaving** for production, native image, and fast cold starts.
- EclipseLink extensions worth knowing: `@BatchFetch`/`QueryHints.BATCH`, `LEFT_FETCH`, fetch groups, `READ_ONLY`, the native `Expression`/`ReadAllQuery` API, and customizers (`SessionCustomizer`, `DescriptorCustomizer`).
- Teams pick EclipseLink for **WebLogic/Oracle alignment, TopLink heritage, MOXy, built-in cache coordination, and NoSQL** — Hibernate still wins on Spring-ecosystem gravity.
- Migration cost concentrates in **vendor-specific code** (`org.hibernate.*`), ID generation, DDL/dialect, and the **cache-default flip**; the JPA-pure layer ports cleanly.

## ⚠️ Common Pitfalls
- Assuming the L2 cache is off (Hibernate habit) and then debugging "stale" reads after external DB updates — invalidate, set TTL, or use ISOLATED.
- Caching **tenant-scoped or security-sensitive** entities in the SHARED cache → cross-tenant data leakage.
- Expecting `@OneToOne(fetch = LAZY)` to be lazy **without weaving** — it loads eagerly.
- Mutating entities returned by a `READ_ONLY` query — changes are silently lost and can corrupt shared-cache state.
- Over-using `JOIN FETCH` across multiple collections → cartesian product explosion; use `BATCH IN` instead.
- Relying on cache **coordination** as the only consistency mechanism — broker downtime reintroduces staleness; back it with TTL + `@Version`.
- Forgetting to switch from dynamic to **static weaving** for GraalVM native image / agent-restricted servers, causing bootstrap failures.

## 📚 Further Reading
- *EclipseLink Documentation & User Guide* — the official Eclipse Foundation docs (Jakarta Persistence extensions, caching, weaving, MOXy): https://eclipse.dev/eclipselink/documentation/
- *Pro JPA 2 in Java EE 8* — Mike Keith & Merrick Schincariol (Keith co-led JPA; the canonical deep JPA reference with EclipseLink coverage).
- *Jakarta Persistence Specification (3.x)* — https://jakarta.ee/specifications/persistence/ (the spec EclipseLink implements as RI).
- *EclipseLink Solutions Guide / Examples wiki* — caching, multitenancy, and MOXy how-tos: https://wiki.eclipse.org/EclipseLink/Examples
- *Java Persistence with Hibernate* — Christian Bauer & Gavin King (read the contrasting design philosophy to articulate EclipseLink-vs-Hibernate trade-offs).
- *EclipseLink/UserGuide/JPA/Advanced Topics* — query hints (`BATCH`, `FETCH_GROUP`, `LEFT_FETCH`) and the native Expression API reference on the Eclipse wiki.
