# J2EE / Jakarta EE

A practical, interview-focused tour of the Java enterprise platform — Servlets, JSP, EJB, CDI, JPA, JTA, JAX-RS/WS, JMS, JSF — plus the J2EE → Java EE → Jakarta EE evolution, the `javax` → `jakarta` namespace migration, application servers, and MicroProfile.

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

### Q1. [Theory] What is Jakarta EE and how did it evolve from J2EE?

Jakarta EE is a set of **specifications** for building enterprise Java applications — it is not a product but a contract that multiple vendors implement. The lineage is:

```
J2EE (1999–2003)        Java EE (2006–2017)        Jakarta EE (2018+)
  Sun Microsystems   →     Oracle (JCP)         →   Eclipse Foundation
  J2EE 1.2–1.4             Java EE 5–8              Jakarta EE 8, 9, 9.1, 10, 11
  Heavy XML, EJB 2.x       Annotations, CDI, EE 6   javax → jakarta namespace
```

Sun created J2EE; Oracle acquired Sun and stewarded Java EE through the JCP; in 2017 Oracle donated the platform to the **Eclipse Foundation**, where it was rebranded **Jakarta EE** (Oracle retained the "Java" trademark). The crucial technical consequence: Eclipse could not use the `javax.*` package namespace for *evolving* APIs, which triggered the namespace migration. The "why" matters in interviews — it explains why upgrading past Jakarta EE 8 is a breaking change, not a drop-in.

### Q2. [Theory] What is a Servlet and how does its lifecycle work?

A Servlet is a Java class that handles HTTP requests inside a web container (e.g., Tomcat, the web layer of WildFly). The container manages a strict lifecycle:

```
load class → instantiate (1 instance) → init() [once]
   → service() → doGet()/doPost()/... [per request, multi-threaded]
   → destroy() [once, on undeploy/shutdown]
```

The key insight is that the container creates **one Servlet instance** and dispatches concurrent requests to it on different threads. Therefore **instance fields are shared state** and must not hold per-request data — doing so is the classic Servlet thread-safety bug. Request-scoped data lives in local variables or the `HttpServletRequest`.

```java
@WebServlet("/hello")
public class HelloServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        resp.setContentType("text/plain");
        resp.getWriter().write("Hello from Servlet");
    }
}
```

### Q3. [Theory] What is a Servlet Filter and when would you use one?

A `Filter` intercepts requests/responses *before* and *after* they reach a Servlet, forming a chain. Filters are the right place for cross-cutting concerns: authentication, logging, compression (GZIP), CORS headers, request timing, and character-encoding enforcement. Because they are decoupled from business logic, you can add or reorder them without touching the Servlets themselves.

```java
@WebFilter("/*")
public class TimingFilter implements Filter {
    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
            throws IOException, ServletException {
        long start = System.nanoTime();
        chain.doFilter(req, resp);            // pass control down the chain
        long ms = (System.nanoTime() - start) / 1_000_000;
        ((HttpServletResponse) resp).setHeader("X-Elapsed-Ms", String.valueOf(ms));
    }
}
```

Order matters: filters execute in declared order on the way in and reverse order on the way out (an onion model). Forgetting to call `chain.doFilter(...)` silently drops the request.

### Q4. [Theory] What is JSP and how does it relate to Servlets?

JSP (JavaServer Pages) is a templating technology where you write HTML with embedded Java/EL. At first request the container **translates the JSP into a Servlet**, compiles it, and runs it — so JSP *is* a Servlet under the hood. Modern best practice uses JSTL tags and Expression Language (`${user.name}`) instead of scriptlets (`<% ... %>`), keeping logic out of the view (MVC). JSP is largely legacy today: new UI work uses JSF, server-side templating like Thymeleaf, or a decoupled SPA/REST architecture, but you will still meet JSP in maintenance-heavy enterprises.

### Q5. [Practical] How do you build a simple REST endpoint with JAX-RS?

JAX-RS (Jakarta RESTful Web Services) is annotation-driven. You define a resource class, map paths and HTTP verbs, and let the runtime (Jersey, RESTEasy) handle (de)serialization via JSON-B/Jackson.

```java
@Path("/orders")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OrderResource {

    @Inject OrderService service;            // CDI injection

    @GET @Path("/{id}")
    public Response get(@PathParam("id") long id) {
        return service.find(id)
                      .map(o -> Response.ok(o).build())
                      .orElse(Response.status(404).build());
    }

    @POST
    public Response create(@Valid Order order, @Context UriInfo uri) {
        Order saved = service.save(order);
        return Response.created(
            uri.getAbsolutePathBuilder().path(String.valueOf(saved.getId())).build()
        ).entity(saved).build();
    }
}
```

In production you would add Bean Validation (`@Valid`), an `ExceptionMapper` for consistent error bodies, and `@RolesAllowed` for security rather than returning bare entities.

---

## 🟡 Intermediate (3–7 yrs)

### Q6. [Theory] Explain CDI: beans, scopes, and injection. Why is it the backbone of modern Jakarta EE?

CDI (Contexts and Dependency Injection) is the type-safe DI and lifecycle framework that unifies the platform. Beans are managed objects discovered via `beans.xml`/annotations; the container injects them by **type plus qualifiers** rather than by string name, so wiring errors surface at deploy time, not runtime.

Scopes define lifecycle and visibility:

```
@ApplicationScoped  → one instance per app (singleton-ish, lazy)
@RequestScoped      → one per HTTP request
@SessionScoped      → one per HTTP session   (must be Serializable)
@Dependent          → default; lives/dies with its injection point
@ConversationScoped → spans multiple requests (JSF wizards)
```

CDI also provides **interceptors**, **decorators**, **producers** (`@Produces` factory methods), and **events** (`Event<T>` / `@Observes`) for loose coupling. It matters because EJB, JAX-RS, JSF, and MicroProfile all integrate through CDI — it is the connective tissue. A subtle gotcha: most CDI scopes use **client proxies**, so injecting a narrower scope (`@RequestScoped`) into a wider one (`@ApplicationScoped`) is safe — the proxy resolves the correct contextual instance per call.

### Q7. [Theory] EJB: contrast Stateless, Stateful, and Singleton session beans, plus MDBs.

EJBs are container-managed components that provide transactions, concurrency, and pooling "for free":

| Type | State | Concurrency | Typical use |
|------|-------|-------------|-------------|
| `@Stateless` | none between calls | pooled, thread-safe via pooling | service/facade layer |
| `@Stateful` | per-client conversation | one client at a time | shopping cart, wizard |
| `@Singleton` | shared, one instance | container-managed locking (`@Lock`) | cache, app config |
| MDB (`@MessageDriven`) | none | pooled | async JMS consumer |

A **Message-Driven Bean** is a stateless consumer that the container invokes when a JMS message arrives — decoupling producers from consumers. Historically EJBs were heavyweight (EJB 2.x home/remote interfaces, XML); EJB 3.x+ made them POJOs with annotations. Today many teams replace `@Stateless` facades with CDI beans plus `@Transactional`, reserving EJB for declarative transactions, the timer service, and async invocation.

### Q8. [Theory] Explain JPA: entities, the persistence context, and the entity lifecycle.

JPA (Jakarta Persistence) is the ORM **specification**; Hibernate and EclipseLink are implementations. The central abstraction is the **persistence context** — a first-level cache and unit-of-work managed by the `EntityManager` that tracks entity state and flushes changes at transaction commit.

```
       persist()            commit/flush
NEW ───────────► MANAGED ──────────────► (DB row)
                  │  ▲
          detach()│  │ merge()
                  ▼  │
              DETACHED
                  │ remove()
                  ▼
              REMOVED
```

Because managed entities are **dirty-checked** automatically, a setter inside a transaction generates an UPDATE without an explicit save call — surprising to JDBC veterans. Key levers: fetch type (`LAZY` vs `EAGER`), cascade rules, and the `@Version` field for optimistic locking. The biggest performance trap is the **N+1 select problem** (see Q9).

### Q9. [Practical] You see hundreds of SQL queries loading one page. Diagnose and fix it.

This is the classic **N+1 problem**: you load N parent entities, then lazily access a relationship, triggering one extra query per parent.

**Approach:** confirm with SQL logging (`hibernate.show_sql`, or a query counter), then eliminate the extra round-trips.

```java
// PROBLEM: 1 query for orders + N queries for each order.items
List<Order> orders = em.createQuery("SELECT o FROM Order o", Order.class).getResultList();
orders.forEach(o -> o.getItems().size());   // lazy hit per order

// FIX 1: JOIN FETCH — single query
List<Order> orders = em.createQuery(
    "SELECT DISTINCT o FROM Order o JOIN FETCH o.items", Order.class).getResultList();

// FIX 2: EntityGraph (no JPQL change, reusable)
EntityGraph<Order> g = em.createEntityGraph(Order.class);
g.addAttributeNodes("items");
List<Order> orders = em.createQuery("SELECT o FROM Order o", Order.class)
    .setHint("jakarta.persistence.fetchgraph", g)
    .getResultList();

// FIX 3: @BatchSize(size = 50) on the collection — turns N queries into N/50
```

**Trade-offs:** `JOIN FETCH` on multiple collections causes a Cartesian product — fetch one collection per query or use `@BatchSize`. In production I default to `LAZY` everywhere and fetch explicitly per use case rather than relying on `EAGER`, which couples every query path to the largest one. **Complexity:** the bug turns an O(1) query plan into O(N) round-trips — latency dominated by network RTT, not data volume.

### Q10. [Theory] What is JTA and how do CMT, BMT, and transaction propagation work?

JTA (Jakarta Transactions) coordinates transactions across one or more resources (DB, JMS) and enables distributed two-phase commit (XA). You choose how transactions are demarcated:

- **CMT (Container-Managed Transactions):** declarative via `@TransactionAttribute` / `@Transactional`. The container begins/commits/rolls back around method boundaries.
- **BMT (Bean-Managed Transactions):** you control `UserTransaction.begin()/commit()` manually — needed for fine-grained or multi-step control.

Propagation attributes control how a method joins an existing transaction:

```
REQUIRED   → join existing, else start one      (default, most common)
REQUIRES_NEW → suspend caller's tx, start new tx (for audit logs that must survive rollback)
MANDATORY  → must already be in a tx, else error
SUPPORTS   → run in tx if present, else non-tx
NOT_SUPPORTED → suspend any tx
NEVER      → error if a tx exists
```

A frequent bug: by default JTA rolls back only on **unchecked** exceptions; checked exceptions commit unless you mark `@ApplicationException(rollback = true)` or call `setRollbackOnly()`.

### Q11. [Practical] When would you choose JAX-WS (SOAP) over JAX-RS (REST), and how do you secure each?

**JAX-WS** generates SOAP services with strict WSDL contracts, XML schemas, and the WS-\* stack (WS-Security, WS-AtomicTransaction, WS-ReliableMessaging). Choose it for **contract-first** integrations with legacy systems, banks, telco/government partners, and B2B middleware where formal contracts, message-level encryption/signing, and reliable messaging are mandated.

**JAX-RS** is lightweight, JSON-centric, and HTTP-native — the default for public APIs, microservices, and mobile/SPA backends.

```java
// JAX-WS SOAP endpoint
@WebService
public class QuoteService {
    @WebMethod
    public double getQuote(@WebParam(name = "symbol") String symbol) { ... }
}
```

**Security:** JAX-WS uses message-level WS-Security (signed/encrypted SOAP headers, SAML tokens) that survives intermediaries. JAX-RS relies on transport security (TLS) plus token-based auth — typically **OAuth2/OIDC JWT bearer tokens**, validated via a filter or `@RolesAllowed` with MicroProfile JWT. For REST, always enforce TLS, validate input (`@Valid`), and avoid leaking stack traces through `ExceptionMapper`.

### Q12. [Coding] Implement a JMS producer/consumer for an order-processing queue, and explain delivery guarantees.

**Problem:** decouple order submission from fulfillment using a JMS queue so spikes don't overwheln the processor.

```java
// PRODUCER — inject the connection factory and queue
@ApplicationScoped
public class OrderPublisher {
    @Inject JMSContext context;
    @Resource(lookup = "java:/jms/queue/orders") Queue ordersQueue;

    public void publish(Order order) {
        context.createProducer()
               .setDeliveryMode(DeliveryMode.PERSISTENT)   // survives broker restart
               .send(ordersQueue, order);                  // serializable ObjectMessage
    }
}

// CONSUMER — MDB invoked by the container per message
@MessageDriven(activationConfig = {
    @ActivationConfigProperty(propertyName = "destinationLookup",
                              propertyValue = "java:/jms/queue/orders"),
    @ActivationConfigProperty(propertyName = "destinationType",
                              propertyValue = "jakarta.jms.Queue")
})
public class OrderConsumer implements MessageListener {
    @Inject OrderProcessor processor;

    @Override
    public void onMessage(Message message) {
        try {
            Order order = message.getBody(Order.class);
            processor.process(order);     // runs in a container transaction
        } catch (JMSException e) {
            throw new EJBException(e);     // forces rollback → message redelivered
        }
    }
}
```

**Delivery guarantees:** with `PERSISTENT` mode plus a transacted MDB, JMS gives **at-least-once** delivery — on failure the transaction rolls back and the broker redelivers (eventually to a Dead Letter Queue after the redelivery limit). Because redelivery can duplicate work, the consumer must be **idempotent** (e.g., dedupe on order ID). **Queue vs Topic:** a queue is point-to-point (one consumer per message, load-balanced); a topic is publish/subscribe (every subscriber gets a copy). **Complexity:** producer send is O(1); throughput scales horizontally by adding MDB instances.

### Q13. [Theory] What are JSF basics, and is JSF still relevant in 2026?

JSF (Jakarta Faces) is a **component-based, server-side** UI framework with a stateful lifecycle: Restore View → Apply Request Values → Process Validations → Update Model → Invoke Application → Render Response. You bind UI components (`<h:inputText value="#{bean.name}">`) to CDI backing beans via EL. Its strength is stateful, form-heavy internal apps (think back-office consoles) where component libraries like **PrimeFaces** deliver rich widgets with little JavaScript. Its weaknesses — server-side view state, scaling cost, awkwardness with SPAs — pushed most greenfield UI work to React/Angular over REST. In 2026 JSF remains relevant chiefly for maintenance and internal enterprise tooling, not new public-facing apps.

---

## 🟠 Advanced (8–12 yrs)

### Q14. [Practical] Walk through migrating a Java EE 8 app to Jakarta EE 10. What actually breaks?

The headline change is the **`javax.*` → `jakarta.*` namespace** rename, introduced in Jakarta EE 9. Eclipse couldn't evolve the `javax` packages (trademark), so every enterprise API moved:

```
javax.servlet.*     → jakarta.servlet.*
javax.persistence.* → jakarta.persistence.*
javax.ws.rs.*       → jakarta.ws.rs.*
javax.ejb.*         → jakarta.ejb.*
javax.enterprise.*  → jakarta.enterprise.*  (CDI)
javax.transaction.* → jakarta.transaction.*
-- but javax.* that belong to Java SE (javax.sql, javax.naming, javax.crypto) DO NOT change
```

**Approach:**

1. Pick the target. **Jakarta EE 9/9.1** is the "namespace-only, no feature changes" bridge — migrate there first to isolate the rename from feature changes. Then jump to **EE 10/11** for new features (CDI Lite, JDK 17/21 baseline, removal of legacy EJB 2.x, Faces/REST improvements).
2. Run the **Eclipse Transformer** (or OpenRewrite recipes) to bytecode/source-rewrite `javax` → `jakarta` across your code and dependencies.
3. Update `persistence.xml`, `web.xml`, `beans.xml` schema namespaces and versions; bump the BOM (`jakarta.jakartaee-api`).
4. Replace dependencies that haven't shipped a Jakarta build; verify the **app server version** supports your target (WildFly 27+, Payara 6, Open Liberty 22.0.0.x+ are EE 10).

**Pitfalls:** mixed `javax`/`jakarta` artifacts on the classpath cause `NoClassDefFoundError`; serialized session objects and persisted bytecode across the boundary break; third-party libraries lag. **What I'd actually do:** strangler approach — transform module by module, keep the EE 8 → EE 9 step purely mechanical and test-covered, then layer feature upgrades.

### Q15. [Theory] How does MicroProfile relate to Jakarta EE, and which specs matter for microservices?

MicroProfile is a **complementary** set of specifications (governed at Eclipse) that adds the "cloud-native" pieces Jakarta EE historically lacked, built *on top of* a Jakarta EE subset (CDI, JAX-RS, JSON-B/P). It optimizes the platform for microservices:

```
Config           → externalized, layered configuration (env > file > default)
Health           → /health, liveness & readiness probes (Kubernetes)
Metrics          → Prometheus-format metrics
OpenAPI          → auto-generated API docs
Rest Client      → type-safe declarative HTTP client (interface + annotations)
JWT Propagation  → OIDC/JWT-based security across services
Fault Tolerance  → @Retry, @Timeout, @CircuitBreaker, @Bulkhead, @Fallback
OpenTelemetry    → distributed tracing
```

Implementations include **Open Liberty, Payara Micro, Quarkus, Helidon, and WildFly**. The interview-grade point: Jakarta EE standardizes the *programming model*; MicroProfile standardizes the *operational/resilience model*, and Quarkus/Helidon fuse both into fast-startup, low-memory, native-image-capable runtimes that compete directly with Spring Boot.

### Q16. [Practical] Add resilience to a flaky downstream call without coupling to a vendor library.

Use **MicroProfile Fault Tolerance** annotations — declarative, container-woven (via CDI interceptors), and portable across runtimes.

```java
@ApplicationScoped
public class InventoryGateway {

    @Inject @RestClient InventoryClient client;

    @Retry(maxRetries = 3, delay = 200, jitter = 100)
    @Timeout(value = 2, unit = ChronoUnit.SECONDS)
    @CircuitBreaker(requestVolumeThreshold = 10, failureRatio = 0.5,
                    delay = 5, delayUnit = ChronoUnit.SECONDS)
    @Bulkhead(value = 20)                       // cap concurrent calls
    @Fallback(fallbackMethod = "cachedStock")
    public int stockLevel(String sku) {
        return client.getStock(sku);
    }

    int cachedStock(String sku) { return cache.getOrDefault(sku, 0); }
}
```

**Trade-offs:** the circuit breaker prevents cascading failure and thread exhaustion, but a too-aggressive `failureRatio` flaps; `@Retry` without a `@Timeout` can amplify load on an already-struggling dependency (retry storm). **What I'd do in production:** pair these with backpressure, idempotent operations, and a meaningful fallback (cached/last-known value), and emit metrics so the circuit state is observable.

### Q17. [Theory] Compare the major application servers. How do you choose one?

```
WildFly      (Red Hat, open source)    — full EE 10 + MicroProfile, fast, popular for self-hosted
JBoss EAP    (Red Hat, commercial)     — WildFly hardened + support SLAs
Payara       (fork of GlassFish)       — Server + Micro (uber-jar), strong cloud/MP story
GlassFish    (Eclipse RI)              — the reference implementation; light production use
Open Liberty (IBM, open source)        — composable features, excellent MicroProfile, low footprint
WebLogic     (Oracle, commercial)      — heavyweight, deep clustering, big-enterprise/legacy
WebSphere    (IBM, commercial)         — similar enterprise/legacy positioning
TomEE        (Apache)                  — Tomcat + EE Web Profile; lightest, Servlet-centric
Quarkus/Helidon (runtimes, not servers)— EE/MP subset, GraalVM native, microservices-first
```

**How to choose:** for a regulated enterprise needing commercial support and clustering → JBoss EAP or WebLogic. For modern self-hosted full-profile apps → WildFly or Payara. For lightweight Servlet/JAX-RS apps → TomEE. For cloud-native microservices with fast startup and low memory → Quarkus or Open Liberty. Decisive factors are: required profile (Full vs Web), support model, footprint/startup (matters for autoscaling and per-pod cost), MicroProfile support, and native-image capability.

### Q18. [Practical] Production incident: under load, requests hang and the thread pool is exhausted. How do you investigate?

**Real-world scenario** (a payments gateway I'll describe generically): latency spikes during peak, then total stall. **Approach:**

1. **Thread dump** (`jstack`, or the server's admin console) — count threads `BLOCKED`/`WAITING`. A wall of threads stuck in JDBC `getConnection` means **connection-pool starvation**: the DB pool is smaller than the request worker pool, so workers queue waiting for connections.
2. Check whether a slow downstream (no `@Timeout`) holds connections open, multiplying the leak.
3. Look for transactions spanning external HTTP calls — holding a DB connection across a network call is the anti-pattern.

**Fix / trade-offs:** size the connection pool relative to DB capacity (not request volume), add statement timeouts and JTA transaction timeouts, move long external calls outside the transaction, add a `@Bulkhead`/circuit breaker so a slow dependency can't consume all workers, and add **readiness probe** failure so Kubernetes stops routing traffic to a saturated pod. The lesson: pool sizes across layers (HTTP workers → DB connections → downstream clients) must be balanced, or the smallest pool becomes the bottleneck that takes everything down.

### Q19. [Coding] Implement an optimistic-locking update that handles concurrent edits gracefully.

**Problem:** two users edit the same account balance concurrently; the last write must not silently overwrite the first.

```java
@Entity
public class Account {
    @Id Long id;
    BigDecimal balance;

    @Version Long version;        // JPA increments this on every UPDATE
}

@Stateless
public class AccountService {
    @PersistenceContext EntityManager em;

    public Account withdraw(Long id, BigDecimal amount) {
        Account a = em.find(Account.class, id);     // reads current version, e.g. v=7
        if (a.getBalance().compareTo(amount) < 0)
            throw new InsufficientFundsException();
        a.setBalance(a.getBalance().subtract(amount));
        // On commit Hibernate issues:
        // UPDATE account SET balance=?, version=8 WHERE id=? AND version=7
        // If another tx already bumped version to 8, 0 rows update → OptimisticLockException
        return a;
    }
}

// Caller retries on conflict
public Account withdrawWithRetry(Long id, BigDecimal amt) {
    for (int attempt = 0; attempt < 3; attempt++) {
        try { return service.withdraw(id, amt); }
        catch (OptimisticLockException e) { /* reload & retry */ }
    }
    throw new ConcurrentModificationException("too many retries");
}
```

**Why optimistic over pessimistic:** optimistic locking (`@Version`) assumes conflicts are rare and avoids holding DB locks — far better throughput for read-heavy workloads. **Pessimistic** (`em.lock(a, LockModeType.PESSIMISTIC_WRITE)` / `SELECT ... FOR UPDATE`) is right only for high-contention hotspots where retries would thrash. **Complexity:** the happy path is one SELECT + one UPDATE (O(1)); under contention, expected retries grow with concurrency. **Edge cases:** retry storms (cap attempts + backoff), and `@Version` must be on a persisted column or it silently does nothing.

---

## 🔴 Expert (15+ yrs)

### Q20. [Theory] Critically assess: is "Jakarta EE vs Spring" still the right framing in 2026?

Decreasingly. Historically Spring grew as a lighter alternative to EJB-heavy J2EE; the platforms then converged — EE adopted annotation-driven DI (CDI), Spring adopted JPA/Bean Validation/JAX-RS-like ideas. By 2026 the real axis is **runtime model and startup/footprint**, not framework allegiance. Spring Boot 3 (which itself moved to the `jakarta.*` namespace, requiring Java 17+) competes head-to-head with **Quarkus and Helidon**, which implement Jakarta EE + MicroProfile and add GraalVM native compilation for sub-100ms startup and tens-of-MB memory — decisive for serverless and dense Kubernetes packing. The mature take: choose based on team skills, the operational profile (cold-start sensitivity, memory budget), vendor support, and ecosystem fit, rather than treating it as a tribal "EE vs Spring" choice. Both are standards-aligned today.

### Q21. [Theory] How do you implement distributed transactions across a DB and a message broker, and when should you avoid them?

The classic EE answer is **JTA two-phase commit (XA)**: an XA-capable DataSource and an XA JMS ConnectionFactory enlist in one global transaction, and the transaction manager runs prepare → commit across both resources.

```
        ┌──────────────── JTA Transaction Manager ────────────────┐
Begin → │  prepare(DB) ──► prepare(JMS) ──► commit(DB) commit(JMS) │ → done
        │     if any "no" in prepare phase → rollback both         │
        └──────────────────────────────────────────────────────────┘
```

XA guarantees atomicity but has real costs: in-doubt transactions if the coordinator crashes between prepare and commit (requires a durable transaction log and recovery), reduced throughput, and resource locking across the window. **When to avoid:** in microservices XA across services is an anti-pattern (tight coupling, distributed locks). Prefer the **Outbox pattern** (write the event to a DB table in the same local transaction, then a relay publishes it — at-least-once + idempotency) or **Sagas** (a sequence of local transactions with compensating actions) for eventual consistency. The senior judgment: use XA only within a single deployment where strong atomicity across two local resources is genuinely required; otherwise embrace eventual consistency.

### Q22. [Theory] What are the security responsibilities split between the container and the application in Jakarta EE?

The **container** provides authentication mechanisms (BASIC, FORM, mutual TLS, and pluggable `HttpAuthenticationMechanism` via Jakarta Security), identity stores (`IdentityStore` for LDAP/DB), declarative authorization (`@RolesAllowed`, `web.xml` security constraints), and the security context (`SecurityContext.getCallerPrincipal()`). The **application** owns input validation, output encoding (XSS defense), business-level authorization (instance-level checks the container can't express), secrets handling, and dependency hygiene. Critical pitfalls to call out in interviews: relying solely on role checks while missing **object-level authorization** (IDOR — user A reading user B's order by ID); trusting client input in JPQL string concatenation (**JPQL/SQL injection** — always use bound parameters); leaking stack traces; and shipping serialized objects over JMS/RMI, which opens **deserialization attacks**. Jakarta Security (EE 8+) unified what used to be vendor-specific JAAS configuration into a portable, CDI-friendly model, and MicroProfile JWT layers token-based auth for microservices.

### Q23. [Behavioral] Describe leading a major platform migration. How did you manage risk and stakeholders?

Strong answers follow situation → action → result with explicit risk management. Example framing: *"We migrated a 600-KLOC Java EE 7 monolith on WebLogic to Jakarta EE 10 on Open Liberty to cut licensing cost and enable container deployment. I de-risked it by (1) sequencing the work — a purely mechanical `javax`→`jakarta` transform step first, fully test-gated, before any feature changes; (2) running both stacks in parallel behind a feature-flagged router (strangler) so we could roll back per-module; (3) building a regression suite and shadow-traffic comparison before cutover; (4) setting weekly stakeholder checkpoints with a risk burndown, and negotiating a freeze window with the business for the final cutover. Result: zero unplanned downtime, ~40% infra cost reduction, and a 90% faster CI build."* The interviewer is probing for incremental delivery, reversibility, measurable outcomes, and how you communicated trade-offs to non-technical stakeholders — not deep API trivia.

### Q24. [Practical] A team wants to "go cloud-native" but is on a 12-year-old WebLogic monolith. What's your pragmatic roadmap?

**Approach — don't big-bang rewrite.** (1) Stabilize and measure: add observability (MicroProfile Metrics/OpenTelemetry, even retrofitted) so you know what to extract. (2) Containerize the monolith first (lift-and-shift to a container, fix statefulness — externalize HTTP sessions, move file/local state to object storage/DB) to get CI/CD and orchestration wins early. (3) Apply the **strangler fig**: carve out the highest-value, lowest-coupling bounded contexts as Jakarta EE + MicroProfile services (Quarkus/Liberty), routing via an API gateway, leaving the core monolith intact. (4) Replace XA-spanning workflows with outbox/saga as you extract. (5) Tackle the namespace/EE-version upgrade as part of each extraction rather than all at once. **Trade-offs:** microservices add operational complexity (networking, distributed tracing, data consistency) — extract only where independent scaling/deploy cadence justifies it; a well-modularized "modulith" is often the right destination, not a swarm of services. **What I'd actually push back on:** "cloud-native" as a goal in itself — tie every step to a business outcome (cost, deploy frequency, reliability) or the migration stalls.

---

## ✅ Key Takeaways

- Jakarta EE is a **set of specifications** with multiple vendor implementations; the J2EE → Java EE → Jakarta EE move (Sun → Oracle → Eclipse) drove the breaking **`javax` → `jakarta`** namespace migration (Jakarta EE 9+).
- **CDI is the backbone** — EJB, JPA, JAX-RS, JSF, and MicroProfile integrate through it; understand scopes, proxies, producers, and events.
- **Servlets are multi-threaded singletons** — never store request state in instance fields; filters handle cross-cutting concerns in an onion model.
- **JPA**: master the persistence context, dirty checking, the N+1 problem, and optimistic vs pessimistic locking.
- **JTA** transactions roll back on unchecked exceptions by default; reserve XA/2PC for single-deployment multi-resource needs and prefer outbox/saga in microservices.
- **MicroProfile** adds the cloud-native operational layer (Config, Health, Metrics, Fault Tolerance, JWT, OpenTelemetry) on top of a Jakarta EE subset; Quarkus/Helidon fuse both with native-image speed.
- Choose an app server by **profile, support model, footprint, and MicroProfile/native support** — WildFly/Payara (open), JBoss EAP/WebLogic (commercial), TomEE (light), Open Liberty (composable).

## ⚠️ Common Pitfalls

- Storing per-request data in Servlet/Singleton instance fields → race conditions under load.
- Forgetting `chain.doFilter(...)` in a Filter → requests silently dropped.
- Relying on `EAGER` fetching globally → fat queries and N+1; default to `LAZY` and fetch explicitly.
- Assuming checked exceptions roll back JTA transactions — they commit unless configured otherwise.
- Mixing `javax.*` and `jakarta.*` artifacts on one classpath → `NoClassDefFoundError`; transform consistently.
- Holding DB connections/transactions open across external HTTP calls → pool starvation and stalls.
- Non-idempotent JMS consumers with at-least-once delivery → duplicate processing on redelivery.
- Missing object-level authorization (IDOR) and using string-concatenated JPQL (injection) — always bind parameters.
- Treating "microservices" as the goal rather than independent scaling/deploy needs — a modulith is often better.

## 📚 Further Reading

- **Jakarta EE Specifications** — official specs and APIs: <https://jakarta.ee/specifications/>
- **Eclipse MicroProfile** — specs and guides: <https://microprofile.io/>
- *Jakarta EE Cookbook* (Elder Moraes, Packt) — recipe-driven coverage of the platform.
- *Practical Cloud-Native Java Development with MicroProfile* (Emily Jiang et al., Packt).
- *Pro JPA 2/3 in Jakarta EE* (Mike Keith, Merrick Schincariol, Massimo Nardone, Apress) — definitive JPA reference.
- **Eclipse Transformer & OpenRewrite** — tooling for the `javax`→`jakarta` migration: <https://github.com/eclipse/transformer> and <https://docs.openrewrite.org/>.
