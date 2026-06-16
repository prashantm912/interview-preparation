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

## 🧩 Extended Questions — Set 1: Deep Theory & Internals

### 🟢 Basic — extended

#### Q25. [Theory] What is a Jakarta EE "profile", and how do Full Platform, Web Profile, and the new Core Profile differ?

A **profile** is a named, certified subset of the specifications that an implementation must support to claim conformance. Splitting the platform into profiles lets vendors and developers avoid dragging the entire spec set into every deployment — a lightweight servlet runtime should not have to ship a JMS broker or EJB container to be "Jakarta EE".

The three profiles, from largest to smallest:

```
Full Platform   — everything: Servlet, JSP/Faces, CDI Full, EJB (incl. MDB),
                  JPA, JTA, JMS, JAX-WS, Batch, Connectors, Mail, etc.
Web Profile     — the web-app subset: Servlet, Faces, CDI, JPA, JTA,
                  Bean Validation, JAX-RS, WebSocket, Security.
                  NO JMS, NO JAX-WS, NO full EJB (only a lite EJB subset), NO Batch.
Core Profile    — introduced in Jakarta EE 10, aimed at cloud-native runtimes:
                  CDI Lite, JAX-RS, JSON-P/B, Annotations, Interceptors.
                  This is the MicroProfile-friendly minimal base.
```

The Core Profile is the most interview-relevant recent addition: it exists specifically so that runtimes like Quarkus and Helidon can be certified against a small, build-time-friendly core (CDI **Lite**, not Full) without implying a heavyweight server. The practical takeaway is that "is this app Jakarta EE compliant?" is meaningless without naming the profile — TomEE targets Web Profile, WildFly/Payara target Full Platform, and Quarkus aligns with Core Profile + MicroProfile.

#### Q26. [Theory] Why can't you just `new Thread()` inside a Jakarta EE component, and what should you use instead?

Spawning raw threads with `new Thread()` or your own `ExecutorService` inside a managed component breaks the container's contract. Container-managed threads carry **context**: the correct classloader, the security/identity context, the JTA transaction, the JNDI naming context, and CDI request scope. A thread you create yourself has none of that, so JNDI lookups, `@RequestScoped` injection, `SecurityContext.getCallerPrincipal()`, and transaction enlistment all silently fail or behave unpredictably. The container also can't account for your threads in its pool sizing, so under load you can exhaust CPU/memory in ways the server can't manage or shut down cleanly.

The correct mechanism is **Jakarta Concurrency** (formerly the Concurrency Utilities for Java EE), which gives you managed executors that propagate context:

```java
@ApplicationScoped
public class ReportRunner {
    @Resource ManagedExecutorService executor;          // container-managed pool

    public CompletableFuture<Report> buildAsync(long id) {
        return executor.supplyAsync(() -> {
            // runs on a managed thread: JTA, security, CDI context propagated
            return heavyComputation(id);
        });
    }
}
```

The four key types are `ManagedExecutorService`, `ManagedScheduledExecutorService`, `ManagedThreadFactory` (for libraries that insist on creating threads — hand them this factory so the threads are still managed), and `ContextService` (to wrap an arbitrary task/proxy with the current context). EE 10 added `@ManagedExecutorDefinition` and friends so you can declare pools annotation-style. The alternative for fire-and-forget work is EJB `@Asynchronous` or MicroProfile's asynchronous fault-tolerance, both of which also run on managed threads.

#### Q27. [Theory] What is JNDI and what role does resource injection (`@Resource`) play in Jakarta EE?

JNDI (Java Naming and Directory Interface) is the platform's **naming registry** — a tree of names mapped to objects (DataSources, JMS destinations, connection factories, EJBs, environment entries). The container binds configured resources into namespaces under roots like `java:comp/`, `java:module/`, `java:app/`, and `java:global/`, which scope visibility to a component, module, application, or the whole server respectively.

Historically you looked things up programmatically; today `@Resource` (and the CDI/JPA variants) does the lookup declaratively at injection time:

```java
@Stateless
public class PaymentService {
    @Resource(lookup = "java:/jdbc/PaymentDS")   DataSource ds;        // DataSource
    @Resource(lookup = "java:/jms/queue/payments") Queue paymentsQueue; // JMS dest
    @PersistenceContext                          EntityManager em;     // JPA
    @Inject                                      AuditLogger audit;    // CDI bean
}
```

The distinction interviewers probe: `@Resource` resolves **container resources** through JNDI (often configured in the server), `@PersistenceContext`/`@PersistenceUnit` handle JPA specifically, and `@Inject` handles **CDI-managed beans** by type+qualifier (no string names, compile-time-ish safety). The reason the platform keeps JNDI even in an annotation-driven world is **portability and externalized configuration**: the same WAR can be deployed against a `PaymentDS` defined differently in dev/test/prod without recompiling, because the binding lives in the server, not the code.

### 🟡 Intermediate — extended

#### Q28. [Theory] How do CDI client proxies actually work, and why are `@Dependent` and `@Singleton` "pseudo-scopes" excluded from proxying?

CDI distinguishes **normal scopes** (`@RequestScoped`, `@SessionScoped`, `@ApplicationScoped`, `@ConversationScoped`) from **pseudo-scopes** (`@Dependent`, and the CDI `@Singleton`). For every normal-scoped bean the container injects a **client proxy** — a generated subclass that holds no state itself. On each method call the proxy looks up the *current* contextual instance from the active context and forwards the call to it. This indirection is what makes it safe to inject a `@RequestScoped` bean into an `@ApplicationScoped` one: the singleton holds the proxy permanently, but every invocation resolves to the request-bound instance for the thread currently calling.

```
@ApplicationScoped Service  ──holds──►  [Proxy for @RequestScoped Ctx]
                                              │ per call:
   request A thread ───────────────────────► resolve → Ctx instance A
   request B thread ───────────────────────► resolve → Ctx instance B
```

Pseudo-scopes are **not proxied** because their lifecycle is degenerate: a `@Dependent` instance lives and dies with its single injection point (no context to look up — it's effectively "the same lifetime as my owner"), and CDI `@Singleton` is eagerly bound to one instance with no contextual switching. Proxying them would add cost with no benefit.

Two consequences that trip people up: (1) because proxies are subclasses, a normal-scoped bean **cannot be `final`**, cannot have `final` methods, and **needs a non-private no-arg constructor** — otherwise deployment fails with a "not proxyable" error. (2) Proxies are also why you should inject `@RequestScoped` beans rather than caching their instance in a field of a wider scope; the proxy already gives you the "fresh instance per request" behavior automatically.

#### Q29. [Theory] Compare interceptors and decorators in CDI. When do you reach for each?

Both are CDI mechanisms for wrapping bean behavior without editing the bean, but they operate at different levels of abstraction. An **interceptor** is *type-agnostic* cross-cutting logic bound by an interceptor binding annotation; it sees a generic `InvocationContext` (method, parameters, target) and knows nothing about the business contract. A **decorator** is *type-aware*: it implements the same business interface as the bean it decorates, injects the delegate via `@Delegate`, and can add domain logic that understands the actual methods.

```java
// INTERCEPTOR — generic, e.g. auditing any annotated method
@Interceptor @Audited @Priority(Interceptor.Priority.APPLICATION)
public class AuditInterceptor {
    @AroundInvoke
    Object audit(InvocationContext ctx) throws Exception {
        log.info("calling {}", ctx.getMethod().getName());
        return ctx.proceed();                  // continue the chain / real method
    }
}

// DECORATOR — type-aware, knows it's decorating PriceCalculator
@Decorator @Priority(Interceptor.Priority.APPLICATION)
public abstract class DiscountDecorator implements PriceCalculator {
    @Inject @Delegate @Any PriceCalculator delegate;
    @Override public BigDecimal price(Order o) {
        BigDecimal base = delegate.price(o);   // real calculation
        return o.isVip() ? base.multiply(new BigDecimal("0.9")) : base;  // domain logic
    }
}
```

Use an **interceptor** for orthogonal concerns that apply across many unrelated types — logging, security, transactions, metrics, retries. Use a **decorator** when you want to alter or extend the *business semantics* of a specific contract and benefit from the type system (the compiler checks you implement the interface). In the invocation order, interceptors run **before** decorators for a given call, and both are ordered by `@Priority`. The trade-off: interceptors are reusable but stringly-typed inside; decorators are type-safe but coupled to one contract.

#### Q30. [Theory] Explain JPA flush modes and the write-behind nature of the persistence context. Why might a query not see changes you just made?

The persistence context is a **write-behind, unit-of-work** cache: calling `persist`, `setX`, or `remove` on managed entities does not immediately hit the database. JPA batches the resulting INSERT/UPDATE/DELETE statements and **flushes** them — actually executing the SQL — either at transaction commit, when you call `em.flush()` explicitly, or automatically before a query that could be affected by the pending changes. This deferral lets Hibernate reorder and batch statements (better throughput) and lets you mutate entities freely without a save call per change (dirty checking).

`FlushModeType` controls the automatic part:

```
AUTO   (default) — flush before commit AND before any query whose results
                   could be affected by pending changes (Hibernate is conservative)
COMMIT           — flush only at commit; queries may NOT see your in-memory changes
```

The "why didn't my query see it?" surprise usually comes from `COMMIT` mode or from a **native SQL query**: native queries bypass JPA's dirtiness analysis, so if you `persist` an entity and then run a native `SELECT` without flushing, the row isn't in the DB yet and won't appear. The fix is an explicit `em.flush()` before the native query. A subtler trap: even in `AUTO` mode, a query against a *different* table than your pending change may not trigger a flush, depending on the provider's affected-table heuristics. Senior-level guidance: treat flush timing as something you reason about explicitly when mixing JPQL/native/criteria queries with in-flight mutations, rather than assuming "managed = instantly in the DB".

#### Q31. [Theory] What is the difference between `@Transactional`, EJB CMT, and bean-managed `UserTransaction`, and why does self-invocation break declarative transactions?

There are three demarcation styles. **EJB CMT** (`@TransactionAttribute` on `@Stateless`/`@Stateful`/`@Singleton`) is the original container-managed model, woven by the EJB container. **`@Transactional`** (Jakarta Transactions, EE 7+) brings the same declarative model to *any CDI bean* via a CDI interceptor, decoupling transactions from the EJB container — this is what most modern code uses. **`UserTransaction`** is fully programmatic (BMT): you call `begin()`/`commit()`/`rollback()` yourself, needed when transaction boundaries don't align with method boundaries.

The critical internals point is **how the declarative versions are implemented**: both CMT and `@Transactional` rely on the container placing a **proxy/interceptor in front of the bean**. The transactional behavior only triggers when a call crosses that proxy boundary from *outside* the bean. Therefore a call from one method of a bean to another method of the **same instance** (`this.other()`) bypasses the proxy entirely:

```java
@ApplicationScoped
public class OrderService {
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void auditLog(String msg) { ... }   // intended to commit independently

    public void place(Order o) {
        save(o);
        auditLog("placed " + o.getId());        // SELF-INVOCATION → interceptor NOT applied!
    }                                            // runs in place()'s tx, not a new one
}
```

Here `auditLog`'s `REQUIRES_NEW` is silently ignored because `place` called it directly on `this`. The fix is to move the transactional method to a separate injected bean (so the call goes through that bean's proxy), or to self-inject the bean and call through the injected reference. This is the single most common "my transaction annotation did nothing" bug, and it stems directly from the proxy-based interception model rather than any flaw in JTA itself.

#### Q32. [Theory] How does Bean Validation work under the hood — constraint composition, validation groups, and method validation?

Jakarta Bean Validation is a declarative constraint framework (reference implementation: Hibernate Validator). Constraints are annotations (`@NotNull`, `@Size`, `@Pattern`) each linked to one or more `ConstraintValidator` implementations via `@Constraint(validatedBy = ...)`. At validation time the engine walks the object graph (following `@Valid` on nested properties), collects all violations into a `Set<ConstraintViolation>`, and returns them — it does **not** stop at the first failure, which is why you get a complete error report rather than fail-fast behavior.

Two features distinguish it from naive checks. **Constraint composition** lets you build a reusable domain constraint from primitives:

```java
@NotNull
@Size(min = 8, max = 64)
@Pattern(regexp = ".*[A-Z].*", message = "needs an uppercase letter")
@Constraint(validatedBy = {})            // composed only, no extra validator
@Target({FIELD, PARAMETER}) @Retention(RUNTIME)
public @interface StrongPassword {
    String message() default "weak password";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
```

**Validation groups** let one model carry different rules for different operations (e.g., `Create.class` requires an `id` to be null while `Update.class` requires it present); you select the active group(s) per call, and integrations like JAX-RS `@ConvertGroup` apply them. **Method validation** extends constraints to method parameters and return values — JAX-RS, CDI, and EJB integrate it so that `@Valid`/`@NotNull` on a parameter is enforced automatically before the method body runs, throwing `ConstraintViolationException` (mapped to HTTP 400 by JAX-RS). The architectural value is that validation rules live with the model and are enforced consistently across the web layer, service layer, and persistence (`@PrePersist` validation) instead of being re-implemented at each tier.

#### Q33. [Theory] Contrast JSON-P, JSON-B, and JAXB. When does each apply, and how does JAX-RS pick a provider?

The platform has distinct standards for JSON and XML binding, and they solve different problems:

| Spec | Format | Style | Analogy | Use when |
|------|--------|-------|---------|----------|
| **JSON-P** (Jakarta JSON Processing) | JSON | low-level: streaming (`JsonParser`) + object model (`JsonObject`) | StAX/DOM for JSON | you need fine-grained control, no POJO mapping, or to handle arbitrary/dynamic JSON |
| **JSON-B** (Jakarta JSON Binding) | JSON | high-level POJO ↔ JSON binding | JAXB for JSON | you have POJOs and want automatic (de)serialization; the default for JAX-RS bodies |
| **JAXB** (Jakarta XML Binding) | XML | POJO ↔ XML binding via annotations | JSON-B for XML | XML payloads, SOAP/JAX-WS, schema-driven contracts |

JSON-P is the foundation JSON-B is typically built on; you drop to JSON-P when there's no fixed schema (parsing a webhook with unknown shape) or when you want to stream a huge document without materializing it. JSON-B (`@JsonbProperty`, `@JsonbTransient`, `@JsonbDateFormat`) is the convenient default. JAXB (`@XmlRootElement`, `@XmlElement`) handles XML and is what JAX-WS uses for SOAP.

In JAX-RS the binding is invisible because of **`MessageBodyReader`/`MessageBodyWriter` providers**. When a resource returns an object, the runtime performs content negotiation: it matches the request's `Accept` header (and the resource's `@Produces`) to a registered provider that can write that Java type to that media type. JSON-B ships a built-in provider for `application/json`, JAXB for `application/xml`, so the same POJO can serialize as either depending on what the client asks for — without the resource method knowing which serializer ran. You can register custom providers (e.g., to swap in Jackson) by annotating them `@Provider`.

#### Q34. [Theory] What is the JAX-RS provider/filter/interceptor pipeline, and how does it differ from a Servlet Filter?

JAX-RS has its own extensibility model layered above the Servlet container, and conflating it with Servlet Filters is a common mistake. The JAX-RS pipeline distinguishes **filters** (which act on the request/response *metadata* — headers, URI, status, security) from **interceptors** (which act on the *entity body* stream during (de)serialization):

```
Client ─► [ContainerRequestFilter(s)] ─► matched resource method
                │ PreMatching filters run BEFORE method matching
                │ (can rewrite URI/method to redirect routing)
        ─► [ReaderInterceptor] wraps MessageBodyReader (reads/decompresses body)
        ─► resource method executes
        ─► [WriterInterceptor] wraps MessageBodyWriter (writes/compresses body)
        ─► [ContainerResponseFilter(s)] ◄─ response on the way out
```

`ContainerRequestFilter` is where you implement authentication (`@PreMatching` to run before routing, or post-matching to use `@NameBinding` for per-resource application), `ContainerResponseFilter` adds CORS/cache headers, and `ReaderInterceptor`/`WriterInterceptor` handle body transforms like GZIP or encryption. Crucially these are **JAX-RS-aware**: filters get a `ContainerRequestContext` with access to the matched resource, the `SecurityContext`, and the ability to `abortWith(Response)` — none of which a raw Servlet Filter understands.

The difference from a Servlet Filter is one of layer and knowledge. A Servlet `Filter` sits at the container level, sees raw `HttpServletRequest`, and runs for *all* requests (JSP, static files, every servlet) with no concept of REST resources, media types, or `@RolesAllowed`. JAX-RS filters run only within the JAX-RS application, after the dispatcher has parsed the REST request, and can be bound to specific resources via `@NameBinding`. Rule of thumb: cross-cutting concerns that must apply to *everything served by the web app* (including non-REST) belong in a Servlet Filter; concerns specific to your REST API (content negotiation tweaks, REST auth, entity transforms) belong in JAX-RS providers.

#### Q35. [Theory] How do asynchronous Servlets (Servlet 3.0+ `AsyncContext`) work, and what problem do they solve?

The classic Servlet model is **one request = one container thread held for the whole request duration**. If a request blocks on something slow (a downstream HTTP call, a long DB query, waiting for an event), that worker thread sits idle but unavailable. Under load with many slow requests, you exhaust the (finite) worker pool even though the CPU is idle — a scalability ceiling driven by thread-per-request, not by actual work.

Async Servlets break the coupling. Calling `request.startAsync()` returns an `AsyncContext` and **releases the container worker thread back to the pool** while the response stays open. The slow work runs elsewhere (a managed executor, a callback, a reactive completion), and when the result is ready you write the response and call `asyncContext.complete()`:

```java
@WebServlet(urlPatterns = "/quote", asyncSupported = true)   // must opt in
public class QuoteServlet extends HttpServlet {
    @Resource ManagedExecutorService executor;

    @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
        AsyncContext ctx = req.startAsync();          // worker thread freed here
        ctx.setTimeout(5000);
        executor.execute(() -> {                       // runs on a managed thread
            try {
                String quote = slowDownstreamCall();
                ctx.getResponse().getWriter().write(quote);
            } catch (Exception e) {
                ((HttpServletResponse) ctx.getResponse()).setStatus(502);
            } finally {
                ctx.complete();                        // flush + close
            }
        });
    }
}
```

The win is **decoupling concurrency from the worker pool**: you can keep tens of thousands of slow connections open with a small worker pool, which matters for long-polling, SSE, and high-fan-out gateways. The trade-offs are real: error handling and timeouts become explicit (`AsyncListener`), you must not touch the request/response after `complete()`, and async only helps when the bottleneck is *waiting* (I/O), not CPU. It also doesn't make a blocking JDBC driver non-blocking — you've merely moved the blocking off the container thread. For genuinely reactive end-to-end flows, JAX-RS `@Suspended AsyncResponse` or returning `CompletionStage<T>` is the higher-level equivalent.

### 🟠 Advanced — extended

#### Q36. [Theory] Explain the classloading model of a Jakarta EE application server. Why do you get `ClassCastException` for "the same" class?

Application servers use a **hierarchy of classloaders with isolation**, not a single flat classpath. A typical layout:

```
Bootstrap / System CL  (JVM + server bootstrap)
        │
Server/Module CL       (the app server's own libs, EE API jars)
        │
   ┌────┴─────────────────────────┐
 EAR CL (shared lib/)        EAR CL (another app)   ← apps isolated from each other
   │                              │
 WAR CL  WAR CL              WAR CL                  ← each WAR gets its own child CL
 (WEB-INF/lib + classes)
```

The two principles that explain most surprises are **delegation** and **isolation**. Each application (EAR/WAR) gets its own classloader, so two deployed apps can use *different versions* of the same library without conflict — they're loaded by separate classloaders and never see each other's classes. Within an app, classloaders normally delegate to their parent first (parent-first), though servers often allow **parent-last / child-first** for a WAR so the app's bundled version of a library wins over the server's.

The infamous `ClassCastException: com.x.Foo cannot be cast to com.x.Foo` (identical names!) happens because **class identity in the JVM is `(fully-qualified name, defining classloader)`**, not just the name. If `Foo` is loaded once by the WAR classloader and once by a parent/shared classloader, they are *two distinct types* to the JVM and casting one to the other fails. This typically arises from packaging a library both in `WEB-INF/lib` and in a server module, or passing objects between two WARs. The fixes: keep a given API/class in exactly one classloader (e.g., put shared types in the EAR `lib/` or mark server-provided APIs `provided` in Maven so they aren't bundled), and understand your server's delegation policy. This is also why a memory leak on redeploy (the old WAR classloader can't be GC'd because something in a parent CL holds a reference) is a recurring production issue.

#### Q37. [Theory] What is CDI Lite vs CDI Full, and why was the split introduced in Jakarta EE 10?

CDI was historically a **runtime, reflection-heavy** model: at deploy time the container scans the classpath, discovers beans, resolves injection points, fires portable-extension events, and builds the bean graph dynamically. That flexibility (runtime `BeanManager`, `@Observes` for deployment lifecycle, dynamic `Instance<T>` lookups, decorators discovered at runtime) is powerful but expensive at startup and hostile to **ahead-of-time / GraalVM native compilation**, which wants to know the whole world at build time and can't tolerate arbitrary runtime reflection or classpath scanning.

Jakarta EE 10 split the spec into **CDI Lite** and **CDI Full**:

```
CDI Lite  — a build-time-friendly subset. New "Build Compatible Extensions"
            (jakarta.enterprise.inject.build.compatible.spi) run at BUILD time.
            Foundation for Quarkus/Helidon/Core Profile + native images.
CDI Full  — Lite + everything classic: Portable Extensions (runtime SPI),
            full BeanManager, specialization, some decorator/interceptor edge cases,
            session/conversation scopes tied to the full web stack.
```

The motivation is squarely **cloud-native**: native-image runtimes need the bean wiring resolved and code generated during compilation so the binary has no scanning/reflection cost and starts in milliseconds. CDI Lite's Build Compatible Extensions replace the runtime Portable Extension model with hooks the framework processes at build time. The practical consequence: when you target the Core Profile or build a Quarkus native image, you're working against CDI **Lite** semantics, and some Full-CDI features (runtime portable extensions, certain dynamic lookups) either aren't available or behave differently. A traditional WildFly/Payara full-platform deployment gives you CDI Full. Knowing which one you're on explains why a library that uses runtime Portable Extensions may not work under a Lite runtime.

#### Q38. [Theory] Explain XA two-phase commit internals: the prepare/commit protocol, in-doubt transactions, and the last-resource commit optimization.

XA implements distributed atomicity via a transaction **coordinator** (the JTA Transaction Manager) talking to multiple **resource managers** (XA-capable DataSources, JMS providers) that implement the `XAResource` interface. The protocol has two phases:

```
Phase 1 — PREPARE:   TM asks each resource "can you commit?"
                     resource does all the work, writes it durably, locks rows,
                     replies VOTE-COMMIT (ready) or VOTE-ROLLBACK.
Phase 2 — COMMIT:    if ALL voted commit  → TM tells each to COMMIT.
                     if ANY voted rollback → TM tells each to ROLLBACK.
```

The guarantee comes from the rule that a resource voting "yes" in prepare **must** be able to commit later no matter what — it persists the prepared state. The danger window is between phase 1 and phase 2: if the **coordinator crashes after some resources committed and others didn't know yet**, those resources are left **in-doubt** — they've voted to commit, hold locks, and are waiting for a verdict. Recovery requires a durable **transaction log** (the TM's recovery log); on restart the TM reads the log, reconciles with each resource's recovered prepared transactions, and drives them to the correct outcome. Until recovery completes, in-doubt transactions hold locks and can block other work — which is exactly why XA hurts throughput and why operators dread a lost transaction log.

Two important optimizations and their limits: **1PC (one-phase commit)** — if only a *single* resource is enlisted, the TM skips prepare and just commits directly (no protocol overhead). The **Last Resource Commit Optimization (LRCO / last-resource gambit)** lets you mix **one non-XA (single-phase) resource** with multiple XA ones: the TM prepares all the XA resources first, then commits the single non-XA resource as the deciding vote — if that last commit succeeds, the XA ones are committed; if it fails, they roll back. This lets a non-XA DB participate "almost" atomically without a true XA driver, but it's not bulletproof: a crash after the last resource commits but before the XA commits leaves a heuristic mismatch. The senior takeaway: XA is correct but operationally heavy (recovery log, locks, in-doubt handling), so reserve it for genuine single-deployment multi-resource atomicity and prefer outbox/saga across service boundaries.

#### Q39. [Theory] Compare the JPA second-level cache to the persistence context (L1), and explain the consistency hazards of each.

JPA has two caching layers with very different scopes and lifetimes. The **first-level cache (L1)** *is* the persistence context — it's scoped to a single `EntityManager`/transaction, guarantees **identity** (two `find` calls for the same id return the *same* Java object), enables dirty checking, and is discarded when the context closes. It's not optional and not shared. The **second-level cache (L2)** is a `SessionFactory`/`EntityManagerFactory`-scoped, **cross-transaction, shared** cache of entity state (not objects), configured via `@Cacheable` + `shared-cache-mode` and a provider like Ehcache/Infinispan/Hazelcast.

```
            ┌────────────── EntityManagerFactory ──────────────┐
            │   L2 cache (shared, cross-tx, entity STATE)       │
            └───────────────────┬───────────────────────────────┘
   EM #1 (tx A)                 │              EM #2 (tx B)
   ┌─ L1: managed objects ─┐    │     ┌─ L1: managed objects ─┐
   │ identity, dirty-check │    │     │ identity, dirty-check │
   └───────────────────────┘    │     └───────────────────────┘
                          reads miss L1 → consult L2 → consult DB
```

The hazards differ. L1's main pitfall is **staleness within a long-lived context**: an extended persistence context that lives across many requests accumulates entities and won't see other transactions' committed changes for already-loaded entities unless you `refresh`. L2's hazards are sharper: it can serve **stale data** if rows are modified outside the JPA provider (direct JDBC, native bulk `UPDATE`/`DELETE`, another app, or `Query` bulk operations that bypass the cache), because the provider doesn't know those rows changed. It also weakens isolation — concurrent transactions may read the same cached state. Therefore L2 is appropriate mainly for **read-mostly reference data** (country codes, product catalog) with a defined invalidation/TTL strategy, and you must configure cache concurrency strategy (`READ_ONLY`, `NONSTRICT_READ_WRITE`, `READ_WRITE`, `TRANSACTIONAL`) deliberately. The interview-grade conclusion: L1 is about correctness/identity within a unit of work and is always on; L2 is a performance optimization that trades freshness for speed and must be reasoned about as a distributed-cache consistency problem.

#### Q40. [Theory] How does the JAX-RS `@Context` injection and runtime work in a multithreaded resource, given resources are per-request by default?

JAX-RS resource classes are by default instantiated **per-request** — a fresh instance for each incoming request — which is why you can safely keep request data in fields. That's the opposite of a Servlet (single instance, shared). This default per-request model is what makes `@Context`-injected objects like `UriInfo`, `HttpHeaders`, `Request`, and `SecurityContext` safe to use as instance fields: each instance belongs to one request, so the injected context refers to that request.

The subtlety arises when you make a resource a **singleton** — either explicitly (returning it as a singleton from `Application.getSingletons()`) or implicitly via `@ApplicationScoped`/`@Singleton`. Now one instance serves concurrent requests, and you obviously cannot store a single `UriInfo` field that's correct for everyone. JAX-RS solves this by **injecting a thread-aware proxy** for `@Context` types: the field holds a proxy that, on each method call, resolves the context for the request bound to the *current* thread. This is the same proxy trick CDI uses for normal scopes.

```java
@Path("/profile")
@ApplicationScoped                      // SINGLETON resource, shared across requests
public class ProfileResource {
    @Context UriInfo uriInfo;           // proxy → resolves per-thread/per-request
    @Context SecurityContext security;  // proxy → current caller per request

    @GET public String me() {
        return security.getUserPrincipal().getName()   // correct per request
             + " @ " + uriInfo.getRequestUri();
    }
}
```

The practical guidance: prefer the default per-request resource lifecycle unless instance creation is genuinely expensive, because it's the simplest mental model. If you go singleton (common when integrating with CDI `@ApplicationScoped`), rely on `@Context` proxy injection for request-scoped data and treat any other instance field as shared mutable state requiring the usual concurrency care. Mixing CDI and JAX-RS scopes is where teams get bitten — e.g., field-injecting a `@RequestScoped` CDI bean into a singleton resource works *because* CDI proxies it, but storing the result of `bean.getValue()` in a field does not.

#### Q41. [Theory] What is the Jakarta Batch (JSR-352) chunk-processing model, and why use it over a plain loop in a scheduled job?

Jakarta Batch standardizes large-scale, restartable batch processing — the nightly "process 10 million records" job. Its core abstraction is the **chunk-oriented step**: a `Reader` produces items one at a time, a `Processor` transforms each, and a `Writer` persists them in **chunks** of a configured size, with each chunk committed in its own transaction.

```
Job ──► Step (chunk) ──► loop:
            read() ──► process() ──► [accumulate]
            ... repeat `item-count` times ...
            write(chunk)  ──► COMMIT this chunk's transaction
            (checkpoint persisted: where to resume)
```

Why this beats a hand-rolled loop in a `@Schedule` EJB method:

1. **Restartability / checkpointing.** The runtime persists a checkpoint after each chunk in a job repository. If the JVM dies at record 7,000,000, restarting the job resumes from the last committed checkpoint instead of redoing everything (or worse, double-processing). A naive loop in one giant transaction either holds locks for hours or has no resume point.
2. **Bounded transactions and memory.** Committing per chunk keeps transactions short (no multi-hour lock holding, no giant undo log) and keeps only `item-count` items in memory rather than the whole dataset.
3. **Skip/retry policies and metrics.** You declaratively configure how many bad records to skip, which exceptions to retry, and the runtime tracks read/write/skip counts.
4. **Partitioning / parallelism.** A step can be partitioned across threads to process ranges in parallel — declarative, with the runtime managing the partitions.

The job is defined in a Job Specification Language XML (`META-INF/batch-jobs/*.xml`) and started via `JobOperator`. The trade-off is ceremony: for a trivial "delete rows older than 30 days" task, a scheduled query is simpler. Reach for Batch when the job is **long-running, large, and must be restartable/observable** — that's the bar where its checkpointing and chunk transactions pay for themselves. Note that Batch is part of the **Full Platform**, not the Web Profile, so a TomEE/Web-Profile deployment won't have it built in.

### 🔴 Expert — extended

#### Q42. [Theory] Walk through the complete Jakarta Faces request lifecycle and explain the role of the `immediate` attribute and postback handling.

Faces (JSF) processes every request through a strict six-phase lifecycle, and understanding *why* it's six phases is the key to debugging "my value didn't update" or "validation ran when it shouldn't have":

```
1. Restore View          — rebuild the server-side component tree (UIViewRoot).
                           Initial GET: build new tree. Postback: restore saved state.
2. Apply Request Values   — pull raw submitted strings into each component's
                           submittedValue (no conversion/validation yet).
3. Process Validations    — convert (Converter) + validate (Validator) each input.
                           A failure here SKIPS to Render Response with messages.
4. Update Model Values    — push validated values into the backing-bean properties.
5. Invoke Application      — run the action method (e.g. the button's #{bean.save}).
6. Render Response         — render the (possibly new) view; save view state.
```

A **postback** is a form submission back to the same view (Faces tracks this via a hidden state field and `facesContext.isPostback()`). On a postback all six phases run; on an initial GET only phases 1 and 6 run (build tree, render). This is why state must be restored before request values can be applied — the model binding (`value="#{bean.x}"`) needs the component tree from the prior render.

The **`immediate="true"`** attribute is the classic senior gotcha. It moves a component's processing **earlier** in the lifecycle: an `immediate` input is converted/validated during *Apply Request Values* (phase 2) instead of *Process Validations* (phase 3), and an `immediate` command (button/link) has its action invoked during phase 2 as well, **before** the rest of the form is validated or the model is updated. The canonical use is a "Cancel" button: you want it to work even when the form has validation errors, so you mark it `immediate="true"` so it fires before validation can block it. The trade-off and trap: because `immediate` actions run before *Update Model Values*, the backing bean's properties have **not** been updated when the action executes — relying on them gives stale values. It also means an `immediate` field's validation can short-circuit the whole form unexpectedly. The mental model interviewers want: `immediate` is a deliberate lever to reorder when conversion/validation/action happen relative to the rest of the form, used for cancel/navigation and partial-processing scenarios.

#### Q43. [Theory] Jakarta EE 10 vs 11: what actually changed, and what determines the Java SE baseline of an EE release?

Each Jakarta EE release pins a **minimum Java SE version** because the spec APIs and TCK are compiled and tested against specific JDKs, and the platform wants to leverage current language/runtime features (records, sealed types, virtual threads) while guaranteeing implementations interoperate. The baseline is a deliberate platform decision, not just "whatever's newest."

```
Jakarta EE 8   — javax namespace, Java SE 8 baseline (the last javax release).
Jakarta EE 9   — javax → jakarta rename ONLY, no new features. Java SE 8/11.
Jakarta EE 9.1 — EE 9 certified additionally on Java SE 11.
Jakarta EE 10  — first feature release post-rename. Java SE 11 baseline (runs on 17).
                 Introduced the Core Profile and CDI Lite; CDI 4.0; removed long-
                 deprecated cruft; many specs modernized (Faces 4, REST 3.1, etc.).
Jakarta EE 11  — Java SE 17 baseline (also certified on 21). Drops support for
                 running on Java 8/11 as the minimum moves up. Continued pruning
                 (e.g. further deprecation/removal of legacy ManagedBean, some EE
                 Connector/legacy items) and alignment with modern records/etc.
```

The headline themes are: EE 9 was a *purely mechanical* namespace bridge so teams could isolate the rename from feature risk; EE 10 was the first release to add real features on the new namespace and crucially introduced the **Core Profile + CDI Lite** to make the platform native-image and cloud-native friendly; EE 11 pushes the **Java SE baseline to 17** (with 21 certification), which lets implementations assume modern JDK features and, importantly, opens the door to **virtual threads (Project Loom)** in EE runtimes — letting the thread-per-request model scale without async plumbing.

Why the baseline matters in practice: it dictates which JDK your app server requires, gates language features you can use, and affects native-image and virtual-thread strategies. An interviewer asking this wants to see that you understand the platform's cadence — mechanical bridge (9) → feature catch-up + cloud-native core (10) → modern-JDK baseline + Loom era (11) — rather than memorizing a feature checklist. When advising an upgrade, the key questions become "what JDK does this EE level mandate, and does my chosen server certify it?"

#### Q44. [Theory] Explain how Jakarta WebSocket differs architecturally from HTTP request/response and async servlets, and what concurrency guarantees the endpoint has.

Jakarta WebSocket implements full-duplex, long-lived bidirectional connections over a single TCP socket (after an HTTP **Upgrade** handshake), which is architecturally different from both classic and async HTTP. HTTP — even async — is fundamentally request/response: the client asks, the server eventually answers, the exchange ends. WebSocket establishes a **persistent session** where either side can push messages at any time until close. This is the right tool for chat, live dashboards, collaborative editing, and server-initiated notifications, where HTTP would force inefficient polling or one-directional SSE.

The programming model is annotation- (or interface-) driven endpoints:

```java
@ServerEndpoint("/chat/{room}")
public class ChatEndpoint {
    @OnOpen    public void open(Session s, @PathParam("room") String room) { ... }
    @OnMessage public String onMessage(String msg, Session s) {            // echo/broadcast
        return "you said: " + msg;     // returned value is sent back to this peer
    }
    @OnClose   public void close(Session s, CloseReason r) { ... }
    @OnError   public void error(Session s, Throwable t)   { ... }
}
```

The crucial concurrency guarantee is that the container creates **one endpoint instance per connection (per `Session`)** by default, and — per the spec — **calls into a given endpoint instance are not made concurrently for the same session**; message-handling callbacks for one peer are serialized. That means you do *not* need to synchronize access to per-session state inside the endpoint. However, **shared state across sessions** (e.g., a registry of all sessions for broadcast, or a shared room model) *is* concurrently accessed and must be thread-safe (a `ConcurrentHashMap`/`CopyOnWriteArraySet` of sessions). A second subtlety: a `Session`'s **basic remote** (`getBasicRemote()`) is synchronous/blocking and you must not have two threads send on the same session simultaneously; use `getAsyncRemote()` for non-blocking sends, but still don't interleave partial messages. The architectural trade-off versus async servlets: async servlets keep an HTTP response open and scale waiting requests, but the model is still one-shot request/response; WebSocket is genuinely bidirectional and stateful per connection, which buys real-time push at the cost of managing connection lifecycle, backpressure, and horizontal scaling (sticky sessions or an external pub/sub to fan out across nodes).

#### Q45. [Theory] How does MicroProfile Config resolve a property, and what are ConfigSources, ordinals, and Converters under the hood?

MicroProfile Config externalizes configuration so the same artifact runs across environments without rebuilding. A value is assembled at lookup time from an ordered set of **ConfigSources**, each of which is just a named map of key→string plus an **ordinal** (priority number). When you request a property, the runtime queries sources in **descending ordinal order** and returns the first match — so higher-ordinal sources *override* lower ones:

```
Default built-in ConfigSources (higher ordinal wins):
  System properties           ordinal 400   (-Dkey=value)
  Environment variables       ordinal 300   (KEY or KEY mapped from key)
  microprofile-config.properties (in META-INF)  ordinal 100
  + any custom ConfigSource you register (e.g. Consul/Vault/DB) at chosen ordinal
```

Resolution example: `app.timeout` defined in `microprofile-config.properties` (100) is overridden by an env var `APP_TIMEOUT` (300), which is itself overridden by `-Dapp.timeout=` (400). Environment-variable mapping follows defined rules (dots/dashes to underscores, uppercase) so `app.timeout` matches `APP_TIMEOUT`.

The value arrives as a String, then a **Converter** turns it into the requested Java type. Built-in converters handle primitives, `Duration`, `URL`, arrays/lists (comma-separated), and `Optional<T>`; you register custom `Converter<T>` implementations (also ordinal-ranked) for domain types. Injection is declarative:

```java
@Inject @ConfigProperty(name = "app.timeout", defaultValue = "5s")
Duration timeout;                              // String "5s" → Duration via converter

@Inject @ConfigProperty(name = "app.retries")
Optional<Integer> retries;                     // absent → empty Optional, no exception
```

The design's power is layering + type safety: ops can override anything via env/system properties (twelve-factor friendly) without touching the artifact, defaults live with the code, and converters keep types out of stringly-typed config. The interview nuance: `@ConfigProperty` resolution is **dynamic** for some implementations only if you inject a `Provider<T>`/`Supplier<T>` (re-reads on each `get()`); a plain injected value is resolved at injection time, so a runtime config change won't be seen unless you ask for it lazily. Knowing the ordinal precedence and the String→Converter pipeline is what separates "I use `@ConfigProperty`" from understanding *why* an env var beats the properties file.

#### Q46. [Theory] CDI `Event`/`@Observes` internals: synchronous vs asynchronous events, transactional observers, and ordering. What are the failure-mode trade-offs?

CDI events implement an in-process **observer pattern** for loose coupling: a producer fires `event.fire(payload)` without knowing who listens, and any bean with an `@Observes` method for that type (matching qualifiers) is invoked. The internals that matter for senior questions are *when* and *on which thread* observers run, and what happens on failure.

**Synchronous events** (`Event.fire`) invoke observers **on the firing thread, synchronously, within the same transaction**, and observer exceptions **propagate back to the firer** — so a failing observer can break the producer and roll back its transaction. **Asynchronous events** (`Event.fireAsync`, CDI 2.0+) run observers (`@ObservesAsync`) on **other threads**, return a `CompletionStage`, do **not** propagate exceptions back synchronously (they're collected into the returned stage), and are **decoupled from the firer's transaction**. Choosing the wrong one is a real bug source: fire async and you've lost transactional atomicity with the producer; fire sync and a slow/failing observer stalls or rolls back the caller.

```java
// Producer
@Inject Event<OrderPlaced> placed;
void place(Order o) { save(o); placed.fire(new OrderPlaced(o.getId())); }  // sync, in-tx

// Transactional observer: runs AFTER successful commit — perfect for "send email
// only if the order actually persisted"
void onPlaced(@Observes(during = TransactionPhase.AFTER_SUCCESS) OrderPlaced e) {
    emailer.confirm(e.orderId());   // won't run if the tx rolled back
}
```

**Transactional observers** are the killer feature: `@Observes(during = ...)` with `AFTER_SUCCESS`, `AFTER_FAILURE`, `AFTER_COMPLETION`, or `BEFORE_COMPLETION` defers the observer to a transaction-lifecycle phase. `AFTER_SUCCESS` solves the classic "don't send the confirmation email / publish the event if the DB transaction rolled back" problem — the observer only fires if the surrounding JTA transaction committed. **Ordering** among observers can be controlled with `@Priority` (lower runs first). The architectural trade-offs: CDI events are great for *in-process* decoupling within one deployment, but they are **not** a message bus — they don't cross JVM boundaries, async events don't survive a crash (no durability), and there's no retry/dead-letter. For cross-service or durable delivery you still want JMS/Kafka/outbox; for "react to a domain event in the same app, only if it committed," transactional CDI observers are the precise, lightweight tool. The most common production mistake is using a *synchronous, in-transaction* observer to do slow external I/O (HTTP, email), which couples external latency/failure into the business transaction — `AFTER_SUCCESS` or async fixes that.

#### Q47. [Practical] Diagnose a redeploy-induced `OutOfMemoryError: Metaspace` after several hot redeploys. What's the root cause and how do you fix it?

This is the classic **classloader leak**, and it's a deep test of understanding the app-server classloading model. Each hot redeploy of a WAR creates a **new application classloader** and is supposed to discard the old one so its loaded classes can be unloaded (in modern JVMs, class metadata lives in **Metaspace**). If anything outside the application's classloader retains a strong reference to *any* object whose class was loaded by the old WAR classloader, the **entire old classloader graph stays reachable** — every class it loaded remains in Metaspace, and after a handful of redeploys Metaspace fills and the JVM throws `OutOfMemoryError: Metaspace`.

**Diagnosis approach:**

```bash
# 1. Capture a heap dump after several redeploys
jcmd <pid> GC.heap_dump /tmp/leak.hprof
# 2. In Eclipse MAT / VisualVM: look for multiple instances of your WebappClassLoader
#    (or the server's equivalent). One per leaked redeploy = smoking gun.
# 3. Run "Path to GC Roots" on an OLD classloader instance — the chain shows
#    exactly which external reference is pinning it.
```

The usual culprits are references held by something with a **longer lifetime than the WAR**:

1. **ThreadLocals on container/pool threads.** A `ThreadLocal` set on a worker thread (from the server's shared pool) whose value is an app-loaded class — the thread outlives the redeploy, so the value (and its classloader) never GCs. Always `remove()` ThreadLocals in a finally block or `contextDestroyed`.
2. **Unstopped threads / timers.** A `new Thread()` (see why you shouldn't) or scheduler the app started but didn't stop on undeploy keeps running and references app classes.
3. **JDBC drivers registered in `java.sql.DriverManager`** (a JVM-level singleton) but bundled in the WAR — deregister in `contextDestroyed`.
4. **Caches/listeners registered in a parent classloader** (logging frameworks, MBeans, shutdown hooks) holding app objects.
5. **`static` fields in a class loaded by a parent CL** caching an app-loaded instance.

**Fix:** clean up in `ServletContextListener.contextDestroyed` (or `@PreDestroy`): stop your threads/executors, cancel timers, deregister JDBC drivers and JMX MBeans, clear ThreadLocals, and remove any listeners you registered with shared frameworks. Use managed resources (`ManagedExecutorService`, container DataSources) instead of app-created ones precisely because the container handles their lifecycle across redeploy. The pragmatic operational mitigation is to **disable hot redeploy in production and do a clean restart** — hot redeploy is a dev convenience, and many teams forbid it in prod specifically to avoid this class of leak. Raising `-XX:MaxMetaspaceSize` only delays the inevitable; it's not a fix.

#### Q48. [Theory] Why is `@Stateless` EJB pooling still meaningful, and how do its concurrency and transaction guarantees differ from an `@ApplicationScoped` CDI bean with `@Transactional`?

A `@Stateless` EJB and an `@ApplicationScoped` CDI bean with `@Transactional` look interchangeable for a service facade, but their **concurrency models** differ in a way that matters under load. The container maintains a **pool** of stateless bean instances. When a client invokes one, the container leases an instance from the pool, the method runs, and the instance returns to the pool — crucially, **the container guarantees a given instance is never executing two methods concurrently**. So you may write a `@Stateless` bean as if single-threaded (instance fields used only within a single method call are safe), and the pool *throttles* concurrency: pool size caps how many invocations run at once, and excess callers queue.

```
@Stateless: caller ─► [borrow instance from pool of N] ─► run ─► return to pool
            concurrency bounded by pool size N; instance never reentered concurrently
            → built-in bulkheading / backpressure

@ApplicationScoped CDI bean: ONE shared instance, container does NOT serialize calls
            all threads hit the same instance concurrently
            → no pooling, no throttle; YOU must make fields thread-safe
```

The `@ApplicationScoped` bean is a **single shared instance** invoked by all threads concurrently with no container-imposed serialization or pool limit. There's only one object, so any mutable instance field is shared mutable state you must guard yourself, and there's no built-in concurrency cap — a flood of requests all execute on that one instance simultaneously. For a *stateless* service that's usually fine (no instance state to protect) and avoids pooling overhead, which is why modern code often prefers it. But you lose the EJB pool's implicit **bulkhead**: with `@Stateless` the pool naturally limits in-flight work to a downstream resource; with the CDI bean you'd add a `@Bulkhead` or semaphore yourself.

On **transactions** they converge: EJB CMT (`@TransactionAttribute`, default `REQUIRED`, rollback on unchecked exceptions) and `@Transactional` (same default, same rollback semantics, configurable `rollbackOn`/`dontRollbackOn`) are effectively equivalent and both interceptor/proxy-based (so both suffer the self-invocation problem). EJB additionally gives you the **timer service**, `@Asynchronous`, and read/write **container-managed concurrency on `@Singleton`** (`@Lock(READ/WRITE)`), which CDI lacks out of the box. The senior conclusion: choose `@Stateless` when you want the container's pooling/throttling, timer, or async features; choose `@ApplicationScoped` + `@Transactional` for a leaner, EJB-container-free stateless service — but then remember you've given up the pool's implicit backpressure and must enforce concurrency limits explicitly.

#### Q49. [Theory] Explain JPA cascade types, orphan removal, and why `CascadeType.REMOVE` plus a bidirectional relationship is a frequent data-loss footgun.

Cascading controls which entity lifecycle operations **propagate from a parent entity to its associated entities** along a relationship. Each `@OneToMany`/`@ManyToOne`/etc. can declare `cascade = {...}`:

```
PERSIST  — persisting parent persists children (saving an Order saves its new Items)
MERGE    — merging parent merges children
REMOVE   — removing parent removes children
REFRESH  — refresh propagates
DETACH   — detach propagates
ALL      — all of the above
```

**Orphan removal** (`orphanRemoval = true`) is *different from* `CascadeType.REMOVE` and frequently confused with it. Cascade-remove fires when you delete the **parent**. Orphan removal fires when a child is **disassociated** from the parent — e.g., you remove an `Item` from `order.getItems()`; JPA then deletes that now-orphaned row even though the parent still exists. Orphan removal models true *composition* ("an Item cannot exist without its Order"), while cascade-remove only handles the parent-deletion case.

The footgun: declaring `cascade = CascadeType.ALL` (which includes `REMOVE`) on relationships that represent **shared or referenced** data rather than owned composition. Consider a bidirectional `@ManyToMany` between `Student` and `Course`, or a `@ManyToOne` from `Order` to a shared `Customer`, where someone naively puts `CascadeType.ALL` on the association. Now `em.remove(order)` cascades into deleting the `Customer` — wiping a record other orders reference. Or with `orphanRemoval` on a collection that's actually a many-to-many, reassigning a child deletes a shared entity. Because the deletes happen silently at flush time via the dirty-checking machinery, the data loss isn't obvious in code review — it surfaces as "why did deleting one order also delete the customer and three other orders' data?"

The discipline: apply `REMOVE`/`orphanRemoval` **only to true parent-owns-child composition** where the child has no independent existence (Order→OrderLine, Invoice→LineItem). For *references* to shared entities (Order→Customer, Student↔Course) use `cascade = {PERSIST, MERGE}` at most, never `REMOVE`/`ALL`, and never `orphanRemoval`. Also be aware that `CascadeType.REMOVE` on a large collection issues a delete per child (N statements) rather than a bulk delete, so it's both a correctness *and* a performance consideration; for big owned collections a bulk JPQL `DELETE` is faster but bypasses cascade and the persistence context.

#### Q50. [Theory] How does JAX-RS resolve which method handles a request (matching algorithm), and what are sub-resource locators?

JAX-RS request dispatch is more involved than "match the path" — there's a defined **matching algorithm** that ranks candidates so the most specific method wins deterministically. Given a request, the runtime: (1) finds the **root resource class** whose `@Path` matches the leading URI segment(s); (2) among that class's methods, filters to those whose `@Path` (plus the class path) matches the *remaining* URI; (3) further filters by **HTTP method** (`@GET`/`@POST`/...); (4) filters by media type — the request's `Content-Type` must be compatible with the method's `@Consumes` and the `Accept` header compatible with its `@Produces`; (5) if multiple still match, it **sorts** by specificity: more literal characters in the path template beat fewer, a regex-constrained `{id:\\d+}` beats a bare `{id}`, and a more specific media type (`application/json`) beats a wildcard (`*/*`). If after all that nothing matches it returns 404 (no path), 405 (path but wrong method), 415 (unsupported `Content-Type`), or 406 (can't produce acceptable type) — and knowing *which* status corresponds to which failure stage is a frequent interview detail.

```
GET /orders/42/items   Accept: application/json
   ① root resource: @Path("/orders")  → OrderResource
   ② remaining "/42/items" matches a method @Path("/{id}/items")
   ③ @GET ✓   ④ @Produces(JSON) compatible with Accept ✓
   ⑤ if both @Path("/{id}/items") and @Path("/{id}/{x}") matched →
       "/{id}/items" wins (more literal chars)
```

**Sub-resource locators** are the second part of the question and a genuinely advanced feature. A method annotated with `@Path` but **no HTTP-method annotation** is a *locator*: instead of handling the request itself, it **returns an object** (or class) that JAX-RS then treats as a fresh resource and continues matching against for the rest of the URI. This enables dynamic, runtime-decided routing and composition:

```java
@Path("/orders")
public class OrderResource {
    @Path("/{id}")                 // NO @GET/@POST → this is a sub-resource LOCATOR
    public OrderItemsResource items(@PathParam("id") long id) {
        return new OrderItemsResource(orderService.find(id));   // returned obj routes the rest
    }
}
public class OrderItemsResource {          // not a root resource; reached only via locator
    private final Order order;
    public OrderItemsResource(Order o) { this.order = o; }
    @GET @Produces(MediaType.APPLICATION_JSON)
    public List<Item> list() { return order.getItems(); }
}
```

The power of locators: the sub-resource is chosen **at runtime** (you could return different handler types based on the order's state or the user's role), the returned object can be a CDI bean with its own injection, and you can build hierarchical, polymorphic APIs without enumerating every path in one giant resource class. The cost: locators are harder to reason about statically (tools that scan annotations can't always see the routing), and because the locator method runs per request to produce the handler, you pay an extra instantiation. Most APIs don't need them, but they're the right answer for genuinely dynamic routing and for decomposing a large resource into focused sub-resources that share a parent context.

#### Q51. [Theory] Compare transaction-scoped vs extended persistence contexts. Why does `LazyInitializationException` happen, and what are the architectural fixes?

JPA defines two persistence-context lifetimes. A **transaction-scoped** context (the default for an injected `@PersistenceContext EntityManager`) is created when a JTA transaction begins and **destroyed/detached when the transaction commits** — entities become detached the moment the method's transaction ends. An **extended** context (`@PersistenceContext(type = EXTENDED)`, valid only in a `@Stateful` EJB) survives across multiple transactions/method calls, keeping entities managed for the conversation's lifetime.

The `LazyInitializationException` is the direct consequence of transaction-scoped behavior interacting with lazy loading. A lazy association (`@OneToMany(fetch = LAZY)`) is only loaded when first accessed, **but only while the entity is still managed** (the context is open). The classic flow that fails:

```java
@Stateless class OrderService {
    @PersistenceContext EntityManager em;
    public Order get(long id) { return em.find(Order.class, id); }  // tx ends on return → DETACHED
}
// In the web layer, AFTER the service method (and its tx) returned:
order.getItems().size();   // entity detached, context closed → LazyInitializationException
```

The entity left the service detached; accessing its uninitialized lazy collection has no open context to issue the SELECT. The **wrong** fixes you should be able to critique: switching everything to `EAGER` (couples every query path to the heaviest one, reintroduces N+1/Cartesian issues) and the **Open-Session-In-View** anti-pattern (holding the persistence context open for the entire HTTP request via a filter) — it "works" but leaks the transaction boundary into the view, makes lazy SQL fire unpredictably during rendering, and holds DB resources longer. The **architectural fixes**: fetch exactly what the use case needs *inside* the transaction (`JOIN FETCH`, entity graphs), or map to **DTOs/projections** in the service layer so the web tier never touches managed entities. The senior framing is that `LazyInitializationException` is a *design smell signalling a leaked boundary* — entities are escaping their unit of work — and the cure is defining clear DTO boundaries rather than stretching the persistence context to paper over it.

#### Q52. [Theory] How does CDI bean discovery work — `bean-discovery-mode` (annotated vs all), `beans.xml`, and what makes a class eligible as a managed bean?

When an application deploys, CDI **scans bean archives** to build the set of managed beans, and the discovery mode controls how aggressive that scan is. A *bean archive* is a jar/classpath entry that CDI inspects; its behavior is governed by `META-INF/beans.xml` (or `WEB-INF/beans.xml` for a WAR) and its `bean-discovery-mode` attribute:

```
annotated (DEFAULT since CDI 1.1)  — only classes with a "bean defining annotation"
                                     (a normal scope like @ApplicationScoped/@RequestScoped,
                                      @Stereotype, etc.) become managed beans.
all                                — EVERY eligible class in the archive is a bean,
                                     even with no annotation (legacy CDI 1.0 behavior).
none                               — archive is NOT a bean archive; nothing is scanned.
```

The crucial modern default is **`annotated`**: if `beans.xml` is absent or empty, an *implicit* bean archive in `annotated` mode is assumed, meaning only annotated classes are beans. This is the source of a very common confusion — a developer writes a plain POJO with no scope annotation, expects `@Inject` to find it, and gets `Unsatisfied dependency`. In `annotated` mode that POJO is invisible to CDI; it needs a scope/bean-defining annotation (or you switch the archive to `all`).

To be a managed bean a class must also satisfy structural rules: it must be a concrete class (or an abstract class annotated `@Decorator`), be a top-level class or static nested class, and have either a **no-arg constructor or a constructor annotated `@Inject`** (because the container must instantiate it). Beyond that, **producer methods/fields** (`@Produces`) register beans for types CDI can't otherwise manage (third-party classes, values from config), and `@Vetoed`/extensions can remove beans. The interview-grade points: (1) `annotated` is the default and explains most "bean not found" issues, (2) the bean-defining-annotation requirement is what makes scanning fast and predictable (no accidental beans), and (3) producers are the escape hatch for turning non-annotated or external types into injectable beans. Knowing this also explains why CDI Lite/native builds care so much about discovery — they want the bean set known at build time, which the `annotated` mode plus producers makes tractable.

#### Q53. [Theory] What are CDI producers, disposers, and the `InjectionPoint` metadata, and what problem do they solve that plain bean classes can't?

A **producer** (`@Produces` on a method or field) is a factory that CDI calls to obtain a bean instance, registering the *return type* as an injectable bean. It exists to bring under CDI management things you **can't annotate as beans yourself**: third-party/JDK classes (you can't add `@ApplicationScoped` to `java.sql.DataSource`), objects requiring custom construction logic, or values computed from configuration. The producer's own bean (often `@ApplicationScoped`) can inject dependencies and decide how to build the product.

```java
@ApplicationScoped
public class JdbcProducer {
    @Produces @ApplicationScoped @Named("reporting")
    DataSource reportingDs(@ConfigProperty(name="report.url") String url) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        return ds;                                  // CDI now injects this where @Named("reporting") DataSource is needed
    }

    void closeDs(@Disposes @Named("reporting") DataSource ds) {   // DISPOSER
        ((HikariDataSource) ds).close();            // cleanup when the producer's scope ends
    }
}
```

A **disposer** (`@Disposes`, matching the producer by type+qualifiers) is the symmetric teardown hook: because the producer — not the container — created the object, the container can't know how to destroy it (close a pool, release a handle), so the disposer runs when the producer-bean's context ends. Without a disposer, producer-created resources leak.

The third piece, **`InjectionPoint`**, lets a producer inspect *where* it's being injected — the target field/parameter, its annotations, the declaring class, the qualifiers. This enables context-aware production, the canonical example being a logger producer that reads the injection point's class to name the logger:

```java
@Produces
Logger logger(InjectionPoint ip) {
    return Logger.getLogger(ip.getMember().getDeclaringClass().getName());
}
// @Inject Logger log;  → produces a logger named after the class that injected it
```

This is the kind of thing a plain bean class fundamentally can't do, because a normal bean has no knowledge of its consumers. Together, producers + disposers + `InjectionPoint` make CDI a complete factory/lifecycle SPI rather than just a "instantiate-my-classes" container: you get managed construction of arbitrary types, deterministic cleanup, and injection-site-aware customization, all type-safe and integrated with scopes and qualifiers. The trade-off to mention is that producer beans add indirection — overusing them for things that could be simple `@Inject`-able beans makes the wiring harder to trace.

#### Q54. [Theory] How does declarative security (`@RolesAllowed`) actually get enforced, and how do Jakarta Security's `HttpAuthenticationMechanism` and `IdentityStore` fit together?

Declarative authorization like `@RolesAllowed("ADMIN")` is enforced by an **interceptor/proxy** the container weaves around the secured component (EJB or CDI bean). Before the method body runs, the interceptor consults the **security context** — specifically the caller's `Principal` and the set of roles the caller holds — and throws an access exception (mapped to HTTP 403 in the web tier) if none of the allowed roles match. The key realization is that `@RolesAllowed` only does *role membership* checks; it answers "is this caller in role X?", not "may this caller touch *this specific* object" — the latter (instance-level / object authorization, the IDOR defense) is the application's job and must be coded explicitly.

But where do the principal and roles *come from*? Jakarta Security (EE 8+) standardized this with two pluggable, CDI-friendly SPIs that decouple **how you authenticate** from **where you look up identities**:

```
Request ─► HttpAuthenticationMechanism   ── "extract & validate credentials from the request"
              (BASIC, FORM, CUSTOM e.g. JWT bearer; you implement validateRequest)
                     │ hands credentials to ↓
           IdentityStore                  ── "validate credentials, return caller + roles/groups"
              (DatabaseIdentityStore, LdapIdentityStore, or custom)
                     │ result ↓
           SecurityContext / caller Principal + groups  ──► @RolesAllowed checks use this
```

The `HttpAuthenticationMechanism` owns the *protocol* of authentication — parsing the `Authorization` header, redirecting to a login form, handling a JWT — and produces a credential. The `IdentityStore` owns *identity verification and attribute lookup* — given a username/password (or token), confirm it and return the caller's name and group memberships. The container wires them so that after a successful authentication the `SecurityContext` is populated, and that's what `@RolesAllowed` and `SecurityContext.isCallerInRole(...)` read. There's also a **group-to-role mapping** layer (groups from the identity store map to logical roles the app checks).

Why this matters architecturally: before Jakarta Security, authentication was configured per-vendor (proprietary JAAS login modules, server-specific realms), making apps non-portable. The SPI split means you can ship a portable `@ApplicationScoped` `IdentityStore` and a custom `HttpAuthenticationMechanism` (e.g., validating OIDC JWTs via MicroProfile JWT) that work across WildFly, Payara, and Liberty unchanged. The senior caveats: `@RolesAllowed` is coarse-grained (RBAC only — no object-level checks, do those yourself), declarative checks at the method boundary won't catch self-invocation (same proxy caveat as transactions), and you must still enforce transport security (TLS) and input validation independently — authentication/authorization is one layer, not the whole security story.

#### Q55. [Theory] How do MicroProfile Health liveness vs readiness probes work, and why does conflating them cause cascading restarts in Kubernetes?

MicroProfile Health exposes HTTP endpoints reporting whether a service is functioning, designed to integrate with orchestrators like Kubernetes. The spec distinguishes three probe kinds, and the **liveness vs readiness** distinction is the part interviewers probe because getting it wrong causes outages:

```
/health/live      (@Liveness)   — "is the process healthy, or is it broken beyond recovery?"
                                  K8s action on FAIL: RESTART the pod.
/health/ready      (@Readiness)  — "is the process ready to SERVE traffic right now?"
                                  K8s action on FAIL: remove pod from the Service load balancer
                                  (stop routing) but do NOT restart.
/health/started    (@Startup)    — "has slow startup finished?" gates the others during boot.
```

You implement checks as CDI beans producing a `HealthCheckResponse`:

```java
@Readiness @ApplicationScoped
public class DbReadiness implements HealthCheck {
    @Inject DataSource ds;
    @Override public HealthCheckResponse call() {
        try (var c = ds.getConnection()) {
            return HealthCheckResponse.up("database");
        } catch (Exception e) {
            return HealthCheckResponse.down("database");   // → pulled from LB, not restarted
        }
    }
}
```

The **cascading-restart failure mode** comes from putting a *dependency* check (database, downstream service, message broker reachable) into the **liveness** probe. Liveness failing tells Kubernetes "this process is irreparably broken — kill it." But a temporarily unreachable database does **not** mean your process is broken; it means you can't serve requests *right now*. If that's in liveness, then when the database has a hiccup, **every replica** fails liveness simultaneously and Kubernetes restarts them all — turning a brief dependency blip into a full fleet restart storm, and the freshly restarted pods still can't reach the DB, so they get killed again: a crash loop that amplifies the original incident.

The correct separation: **liveness** should check only intrinsic, self-recoverable-only-by-restart conditions (deadlock detected, fatal internal state, out of a critical resource the process can't recover from) — keep it dependency-free and cheap. **Readiness** is where dependency checks belong: if the DB is down, fail readiness so traffic stops routing to the pod (and the LB sends it elsewhere or clients back off), but the pod keeps running and automatically rejoins when the DB recovers — no restart, no crash loop. The `@Startup` probe handles slow initialization (JIT warmup, cache priming) so the orchestrator doesn't kill a pod that's merely still booting. The principle: liveness = "should you kill me?", readiness = "should you send me traffic?", and conflating them converts transient dependency failures into self-inflicted outages.

#### Q56. [Theory] In a JAX-RS API, how do you return data asynchronously, and contrast `@Suspended AsyncResponse`, `CompletionStage`, and Server-Sent Events.

JAX-RS offers several ways to decouple producing a response from the request thread, and choosing among them comes down to *one delayed value* vs *a stream of values* and the programming style you want. The motivation is the same as async servlets (Q35): freeing the container request thread while slow work happens, so a small thread pool can serve many in-flight requests.

**`@Suspended AsyncResponse`** is the imperative async API. You inject it, return `void`, the request thread is released, and you resume the response later (often from a managed executor) by calling `asyncResponse.resume(entity)`:

```java
@GET @Path("/{id}")
public void getAsync(@PathParam("id") long id, @Suspended AsyncResponse ar) {
    ar.setTimeoutHandler(a -> a.resume(Response.status(503).build()));
    ar.setTimeout(5, TimeUnit.SECONDS);
    executor.execute(() -> ar.resume(service.slowLookup(id)));  // request thread already freed
}
```

**Returning `CompletionStage<T>`** (JAX-RS 2.1+) is the declarative, reactive style — cleaner, composable, and the recommended default for a single delayed result. The runtime sees the `CompletionStage`, releases the thread, and writes the response when the stage completes:

```java
@GET @Path("/{id}")
public CompletionStage<Order> get(@PathParam("id") long id) {
    return service.lookupAsync(id);          // composes with .thenApply / .exceptionally
}
```

**Server-Sent Events (SSE)** solves a *different* problem: a long-lived, **one-way server→client stream** of multiple events over a single HTTP connection (`text/event-stream`). Use it for live feeds, progress updates, and notifications where you push many messages over time rather than one response:

```java
@GET @Path("/stream") @Produces(MediaType.SERVER_SENT_EVENTS)
public void stream(@Context SseEventSink sink, @Context Sse sse) {
    // keep sink, push events as they arise; connection stays open
    feed.subscribe(item -> sink.send(sse.newEvent(item.toJson())));
}
```

The contrast: `@Suspended AsyncResponse` and `CompletionStage` both produce **exactly one** response asynchronously — pick `CompletionStage` for its composability unless you need the imperative timeout/callback hooks of `AsyncResponse`. **SSE** produces **many** events over a persistent connection and is unidirectional (server→client); it's lighter than WebSocket (it's plain HTTP, auto-reconnects, no upgrade handshake) but can't receive from the client — choose WebSocket (Q44) when you need true bidirectional traffic. All three only help when the bottleneck is *waiting*; none magically makes blocking JDBC non-blocking — you still need the slow work to actually run off the request thread (and ideally on non-blocking I/O) to realize the scalability benefit.

#### Q57. [Theory] How do annotations and the deployment descriptor (`web.xml`, `ejb-jar.xml`) interact, and what is the override/merge precedence?

Jakarta EE supports two parallel ways to configure components — **annotations** in code and **XML deployment descriptors** — and a precise set of rules governs how they combine, which matters for both legacy maintenance and environment-specific overrides. The historical arc: J2EE was XML-heavy (every servlet/EJB declared in descriptors); EE 5+ made annotations the primary mechanism; but descriptors were retained because they let you **override configuration without recompiling**, which is invaluable for ops (e.g., changing a URL mapping or a security constraint per deployment).

The governing rules:

```
1. Descriptor OVERRIDES annotations for the same element.
   (XML is "closer to deployment" so it wins — ops can change behavior without code.)
2. metadata-complete="true" in web.xml / ejb-jar.xml  →  annotations are IGNORED entirely;
   only the descriptor is authoritative (faster scan, full XML control).
3. metadata-complete absent/false  →  annotations + descriptor are MERGED, with XML winning
   on conflicts and adding what annotations didn't specify.
```

```xml
<!-- web.xml overrides @WebServlet("/hello") on the class without touching code -->
<servlet>
    <servlet-name>hello</servlet-name>
    <servlet-class>com.example.HelloServlet</servlet-class>   <!-- same annotated class -->
</servlet>
<servlet-mapping>
    <servlet-name>hello</servlet-name>
    <url-pattern>/greeting</url-pattern>                      <!-- now served at /greeting -->
</servlet-mapping>
```

The practical implications: (1) when an annotated `@WebServlet("/a")` mysteriously serves at `/b`, look for a `web.xml` mapping overriding it. (2) Setting `metadata-complete="true"` is a deliberate choice for fully descriptor-driven apps and also a minor startup optimization because the container can skip annotation scanning of that archive. (3) Web fragments (`web-fragment.xml` inside library jars) add a third source with their own ordering rules (`<absolute-ordering>` / `<ordering>`), letting frameworks contribute servlets/filters — relevant when filter ordering across libraries behaves unexpectedly. The senior framing: annotations optimize for developer ergonomics and co-location with code; descriptors optimize for late-binding configuration and ops control; the override-toward-XML precedence exists precisely so that the people deploying the app can adjust it without owning the source — but it also means the "source of truth" for a given setting may not be in the code you're reading, which is a real maintenance gotcha.

#### Q58. [Theory] What is context propagation across asynchronous boundaries (MicroProfile Context Propagation / Jakarta Concurrency 3.0), and why is it necessary for tracing and security?

Many Jakarta EE / MicroProfile facilities depend on **thread-bound context**: the CDI request scope, the JTA transaction, the security principal, and tracing spans (OpenTelemetry) are all associated with the *current thread* via thread-locals. This works fine in the synchronous request-per-thread model. The problem appears the instant work hops to a *different* thread — a `CompletableFuture.supplyAsync` on a generic pool, a reactive callback, a `@Asynchronous` method — because the destination thread does **not** inherit the originating thread's context. Without intervention, code on the new thread sees no request scope, no transaction, an empty security context, and — critically for observability — **a broken trace** (the span on the worker thread isn't linked to the request's span, so distributed traces show orphaned or missing segments).

Context propagation explicitly **captures** the relevant context on the source thread and **restores** it on the target thread for the duration of the task. Jakarta Concurrency's `ManagedExecutorService` already propagates standard context to tasks it runs; MicroProfile Context Propagation (now largely folded into Jakarta Concurrency 3.0) generalizes this so you can wrap arbitrary `CompletableFuture`/`CompletionStage` pipelines and choose *which* contexts to propagate, clear, or leave unchanged:

```java
@Inject ThreadContext threadContext;     // MP Context Propagation / Jakarta Concurrency
@Inject ManagedExecutor managedExecutor; // propagation-aware executor

CompletableFuture<Order> handle(long id) {
    // managedExecutor propagates CDI/security/tx/tracing context to the async stage:
    return managedExecutor.supplyAsync(() -> service.lookup(id))   // context present here
                          .thenApply(this::enrich);                // and here
    // vs CompletableFuture.supplyAsync(...) on a raw pool → context LOST on the worker thread
}
```

Why each context matters across the boundary: **tracing/OpenTelemetry** needs the active span propagated so the async work appears as a child span of the request — otherwise your distributed traces are full of gaps and you can't follow a request end-to-end. **Security** needs the caller principal propagated or the async task runs unauthenticated (downstream `@RolesAllowed`/JWT-based calls fail or, worse, run with the wrong identity). **CDI request scope** must be propagated or `@RequestScoped` injection on the worker thread fails. **Transactions** are the nuanced one — you usually do *not* want a transaction propagated to a parallel async task (a JTA transaction isn't designed for concurrent threads), so context propagation lets you explicitly **clear** the transaction context for the async task while propagating the others. The senior insight: context propagation is what makes the EE/MicroProfile programming model survive the move from thread-per-request to async/reactive code — it's the glue that keeps observability, security, and scoping coherent when work fans out across threads, and the reason you should use `ManagedExecutor`/`ThreadContext` rather than raw `CompletableFuture` on a plain executor in any container-managed code path.

## 🧩 Extended Questions — Set 2: Practical, Operational & Troubleshooting

### 🟢 Basic — extended

#### Q59. [Practical] How do you configure a JDBC DataSource in a Jakarta EE app, and what's the difference between a server-managed pool and `@DataSourceDefinition`?

In a Jakarta EE deployment you almost never `new` a connection or use raw `DriverManager` — you want a **pooled, container-managed DataSource** registered in JNDI so the container can manage connection lifecycle, transaction enlistment (JTA/XA), and recovery. There are two common ways to define one, and the choice has real operational consequences.

The **server-managed approach** defines the pool in the application server's configuration (WildFly `standalone.xml`, Payara `domain.xml`, Liberty `server.xml`) and the app just looks it up. This is the production-grade choice because **ops can change the URL, credentials, and pool size per environment without rebuilding the WAR**, secrets can be vaultized/encrypted by the server, and you get the server's mature pool (IronJacamar, Liberty's pool) with built-in leak detection and validation.

```xml
<!-- WildFly standalone.xml — server owns the pool; app looks up java:/jdbc/OrdersDS -->
<datasource jndi-name="java:/jdbc/OrdersDS" pool-name="OrdersDS">
    <connection-url>jdbc:postgresql://db:5432/orders</connection-url>
    <driver>postgresql</driver>
    <pool>
        <min-pool-size>5</min-pool-size>
        <max-pool-size>30</max-pool-size>
    </pool>
    <validation>
        <validate-on-match>true</validate-on-match>
        <background-validation>true</background-validation>
    </validation>
</datasource>
```

The **`@DataSourceDefinition`** annotation (or a `<data-source>` in `web.xml`) defines the pool *inside the application*, which is convenient for self-contained demos and microservices (the artifact is portable, no server config step):

```java
@DataSourceDefinition(
    name = "java:app/jdbc/OrdersDS",
    className = "org.postgresql.ds.PGSimpleDataSource",
    url = "jdbc:postgresql://db:5432/orders",
    minPoolSize = 5, maxPoolSize = 30)
@ApplicationScoped
public class DataSourceConfig {}
```

The trade-off: app-defined datasources bake configuration (often including credentials) into the deployment, which is an anti-pattern for secrets and forces a redeploy to change pool sizing. My rule of thumb is server-managed datasources for traditional app-server deployments (the ops team owns tuning), and externalized config (MicroProfile Config / Kubernetes secrets feeding an Agroal/Hikari pool) for Quarkus/microservice deployments. Either way the application code stays the same — `@Resource(lookup=...)` or `@PersistenceContext` against a JNDI name — which is the whole point of the JNDI indirection.

#### Q60. [Practical] How do you turn on SQL logging in Hibernate to debug a query problem, and why is `show_sql` not enough in production?

The quick developer toggle is `hibernate.show_sql=true` (prints SQL to stdout) plus `hibernate.format_sql=true` (pretty-prints) and `hibernate.use_sql_comments=true` (adds the originating HQL as a comment). But `show_sql` writes raw to `System.out` with **no parameter values** (you see `?` placeholders), no timing, and no log routing — useless for real diagnosis and a performance drag if left on.

The production-appropriate approach is to configure **SLF4J/Logback categories** so you get bind parameters, timing, and proper log management:

```properties
# persistence.xml properties — keep show_sql=false in prod
hibernate.format_sql=true

# logback.xml / log4j2 — the real diagnostic levers
# the actual SQL statements:
<logger name="org.hibernate.SQL" level="DEBUG"/>
# the bound parameter VALUES (the bit show_sql can't give you):
<logger name="org.hibernate.orm.jdbc.bind" level="TRACE"/>   <!-- Hibernate 6 -->
# (Hibernate 5: org.hibernate.type.descriptor.sql.BasicBinder)
```

For diagnosing the *number* and *latency* of queries — the N+1 problem, slow statements, missing batching — neither of these is ideal because you have to eyeball the log. The better production tools are: (1) **Hibernate statistics** (`hibernate.generate_statistics=true`, exposed via `SessionFactory.getStatistics()`) which counts query executions, cache hits, and flushes; (2) a **JDBC-proxy library** like **datasource-proxy** or **P6Spy** that wraps the DataSource and logs every statement with inlined parameters, execution time, and batch size; or (3) APM tooling (the database span in OpenTelemetry traces) which shows query counts per request without any logging. The senior point: `show_sql` is a dev convenience; in production you want **structured, sampled, parameter-aware** observability so that "this endpoint makes 340 queries" is a metric you can alert on, not something you discover by reading stdout.

#### Q61. [Practical] A user reports that special characters (accents, emoji) are garbled in your web app. Walk through diagnosing the encoding problem end to end.

This is the classic "mojibake" bug, and it almost always comes from a **mismatch in the character-encoding chain** — every hop (request bytes → servlet parsing → JVM string → JDBC → DB column → response bytes) must agree on UTF-8, and the weakest link corrupts everything downstream. The diagnosis is to walk the chain and pin every layer to UTF-8.

```
Browser ──UTF-8 bytes──► Servlet container ──► your code ──► JDBC ──► DB column
   form accept-charset      request.setCharacterEncoding    connection charset   column charset/collation
                            response.setContentType("...;charset=UTF-8")
```

The common failure points, in the order I'd check them:

1. **Request decoding.** For POST bodies, the container decodes form parameters using a charset that defaults (historically) to ISO-8859-1 if you don't set it. Fix with a `@WebFilter` calling `request.setCharacterEncoding("UTF-8")` *before* any `getParameter()` call, or set it container-wide. (Servlet 4+/Jakarta lets you set a default request encoding in `web.xml` via `<request-character-encoding>UTF-8</request-character-encoding>`.)
2. **Response encoding.** `response.setContentType("text/html; charset=UTF-8")` (or `setCharacterEncoding`) so the browser decodes correctly.
3. **JDBC/connection.** The DB connection must transfer UTF-8 (e.g., MySQL needs `characterEncoding=UTF-8` / `useUnicode=true` on the URL, or a `utf8mb4` server charset; emoji specifically need **`utf8mb4`** on MySQL, not the legacy 3-byte `utf8`).
4. **Database storage.** The column/table charset and collation must be UTF-8 (`utf8mb4`); a `latin1` column truncates or replaces characters at write time — and once stored wrong, the data is corrupted at rest.

The reason emoji are a special tell: MySQL's legacy `utf8` is a 3-byte encoding that **cannot store 4-byte characters** (emoji, some CJK), so accents may survive while emoji turn into `?` — a strong hint the DB layer is the culprit. The systematic fix is to standardize on UTF-8 (utf8mb4) at *every* layer and add the request-encoding filter; the lesson for interviews is that encoding bugs are never one setting — they're a pipeline, and you debug by isolating which hop first sees the corruption (log the bytes/codepoints at each layer).

### 🟡 Intermediate — extended

#### Q62. [Practical] How do you size a JDBC connection pool in production, and what symptoms indicate it's wrong in each direction?

Connection-pool sizing is one of the highest-leverage production tuning decisions and one of the most commonly botched. The key mental model is that connections are a **shared, finite, expensive resource bounded by the database**, not by your app — each connection consumes DB memory and a backend process/thread, so the *database's* capacity, not your request volume, sets the ceiling. A frequently cited starting heuristic (from the HikariCP authors, derived from PostgreSQL benchmarks) is `connections ≈ ((core_count * 2) + effective_spindle_count)` for the *database server*, then divide that budget across all app instances that share the DB.

```
DB can handle ~50 connections total.
4 app pods × max_pool_size 30 = 120 → oversubscribed → DB context-switch thrashing.
4 app pods × max_pool_size 10 = 40  → within budget, leaves headroom.
```

**Symptoms of too-small a pool:** threads pile up `WAITING` in `getConnection()` (visible in a thread dump), request latency spikes under load while DB CPU stays low (you're queueing for connections, not doing DB work), and you see pool-exhaustion timeouts. **Symptoms of too-large a pool:** the *database* is the one suffering — high DB CPU from context switching, lock contention, memory pressure, "too many connections" errors, and counter-intuitively *worse* throughput because a smaller pool would let the DB execute queries more efficiently (queueing at the app is cheaper than thrashing the DB).

The other levers that matter as much as max size: **connection timeout** (how long a thread waits before failing fast — keep it short so you shed load instead of hanging), **max lifetime / idle timeout** (recycle connections to dodge DB-side or firewall idle-kill, and to survive failovers), **leak detection** (HikariCP `leakDetectionThreshold`, server-side equivalents) to catch code that borrows and never returns, and **validation** (test-on-borrow or background validation so a half-dead connection after a DB restart doesn't get handed to a request). The senior framing I'd give: size the pool to the **database's** capacity divided across instances, keep it small and let queueing happen at the app where it's observable and cheap, fail fast on acquisition, and instrument pool metrics (active/idle/pending) so you can see saturation before it becomes an outage.

#### Q63. [Coding] You're inserting 100,000 rows via JPA and it's extremely slow. Diagnose and fix it.

The naive loop is slow for several compounding reasons, and a strong answer names each and fixes it. First, with the default flush behavior every `persist` accumulates in the persistence context, which **grows unbounded** (memory pressure, slower dirty-checking as it scans more entities), and the statements aren't batched, so you pay one network round-trip per INSERT. Second, if the entity uses `GenerationType.IDENTITY`, Hibernate **cannot JDBC-batch** the inserts at all because it must execute each insert to learn the generated key.

```java
// SLOW: one round-trip per row, context never cleared, possibly no batching
for (int i = 0; i < 100_000; i++) {
    em.persist(new Order(...));   // accumulates forever
}
```

```java
// FAST: enable JDBC batching + periodically flush & clear the context
// persistence.xml:
//   hibernate.jdbc.batch_size = 50
//   hibernate.order_inserts   = true   (group same-type inserts for batching)
//   hibernate.order_updates   = true
int batch = 50;
for (int i = 0; i < 100_000; i++) {
    em.persist(new Order(...));
    if (i % batch == 0) {
        em.flush();   // send this batch to the DB
        em.clear();   // detach flushed entities → free memory, keep context small
    }
}
```

The two critical fixes are **JDBC batching** (`hibernate.jdbc.batch_size`) so N inserts become one multi-row round-trip, and **periodic `flush()` + `clear()`** so the persistence context doesn't accumulate all 100k managed entities. The ID-generation gotcha is decisive: switch from `IDENTITY` to a **`SEQUENCE` with a pooled/`hi-lo` optimizer** so Hibernate can pre-allocate ID ranges and batch the inserts (with `IDENTITY`, batching is silently disabled no matter what you set). For truly bulk work where you don't need entity lifecycle, the fastest path is a **bulk JPQL `INSERT`/native SQL** or the DB's native bulk loader (`COPY`/`LOAD DATA`), accepting that you bypass the persistence context, cascades, and L2 cache.

**Complexity/trade-off:** the naive version is O(N) round-trips (network-RTT-bound); batching cuts that to O(N/batch_size) round-trips and bounds memory to `batch_size` entities. The trade-off of bypassing JPA with native bulk ops is losing dirty-checking, cascade, validation, and cache coherence — fine for ETL, dangerous if other code assumes the entities went through normal lifecycle.

#### Q64. [Practical] How do you implement structured request logging with a correlation/trace ID across a Jakarta EE request, and why is MDC the wrong tool with async/reactive code?

Production debugging requires correlating all log lines for one request (and ideally across services). The standard technique is a **correlation ID** (or the W3C `traceparent`) attached at the edge and propagated through every log line. In a synchronous servlet world the canonical tool is **SLF4J MDC (Mapped Diagnostic Context)** — a thread-local map whose keys you add to the log pattern:

```java
@Provider @PreMatching
public class CorrelationFilter implements ContainerRequestFilter, ContainerResponseFilter {
    @Override public void filter(ContainerRequestContext req) {
        String id = Optional.ofNullable(req.getHeaderString("X-Correlation-Id"))
                            .orElse(UUID.randomUUID().toString());
        MDC.put("correlationId", id);          // thread-local
        req.setProperty("correlationId", id);
    }
    @Override public void filter(ContainerRequestContext rq, ContainerResponseContext rs) {
        rs.getHeaders().add("X-Correlation-Id", MDC.get("correlationId"));
        MDC.clear();                            // CRITICAL: clear on pooled threads
    }
}
// logback pattern: %d %-5level [%X{correlationId}] %logger - %msg%n
```

Two hard-won operational rules apply. First, **always `MDC.clear()` (or remove keys) at the end of the request** — container worker threads are pooled and reused, so a leftover MDC value will leak into the *next* request that lands on that thread, producing wrong correlation IDs (and, as in the Metaspace question, MDC is a classic ThreadLocal leak vector across redeploys). Second, **MDC breaks across async/reactive boundaries**: because it's thread-local, the moment work hops to a `CompletableFuture` worker, a `@Suspended AsyncResponse` resume thread, or a reactive scheduler, the MDC is empty and your correlation ID vanishes from the logs at exactly the point you most need it.

The fix for async code is **context propagation** (Jakarta Concurrency 3.0 / MicroProfile Context Propagation, see the related Set 1 question): use a `ManagedExecutor`/`ThreadContext` that captures and restores the MDC (and trace span) on the target thread, rather than a raw executor. In modern stacks the cleaner answer is to lean on **OpenTelemetry**, which propagates the trace/span context across threads and services for you and injects `trace_id`/`span_id` into logs via its logback/log4j appender — making the correlation ID a first-class part of distributed tracing rather than a hand-rolled MDC value. The senior framing: MDC is correct and cheap for synchronous request-per-thread code provided you clear it religiously, but it is fundamentally thread-bound, so the instant you go async you must switch to explicit context propagation or a tracing library that handles the hop.

#### Q65. [Practical] Contrast packaging as a WAR on an app server vs an executable uber-jar (Payara Micro / Quarkus). What are the operational trade-offs?

This is a real deployment-architecture decision, and the two models optimize for different worlds. The **WAR-on-app-server** model deploys a thin WAR into a shared, long-running application server (WildFly, Liberty, WebLogic) that provides the runtime, datasources, JMS, security realms, and clustering. The **executable-jar** model (Payara Micro `--deploy app.war`, Quarkus/Helidon `runner.jar`, Spring Boot–style) bundles the runtime *into* the artifact so you `java -jar app.jar` with no external server.

```
WAR + app server                         Executable uber-jar
─────────────────────────────            ─────────────────────────────
server config owns DS/JMS/security       app owns/embeds everything
one server hosts many apps               one process = one app
heavier, slower start (seconds+)         lighter; Quarkus native ~tens of ms
ops patches the server centrally         each artifact carries its runtime
classic on-prem / VM clustering          cloud-native / container / 12-factor
```

The operational trade-offs: the **app-server model** centralizes runtime patching (fix a CVE in the server once, all apps benefit), shares resources across co-deployed apps (cheaper if you host many small apps on one server), and matches established on-prem ops with mature clustering/session-replication — but it couples deployment to a stateful shared server, makes "one app per container" awkward, and starts slowly (bad for autoscaling). The **uber-jar model** is the natural fit for Docker/Kubernetes: the artifact is the deployment unit, configuration comes from env vars (12-factor / MicroProfile Config), scaling is "run more identical containers," and startup is fast enough for autoscaling and scale-to-zero — but each artifact now carries (and must patch) its own runtime, and you give up the shared-server economies.

The senior conclusion: choose the app server when you have an existing investment, need its full-profile services and clustering, or run many apps on shared infrastructure; choose the executable jar (especially Quarkus native) for containerized microservices where fast startup, low memory, immutable artifacts, and per-service independence matter more than shared runtime management. Note this isn't strictly either/or — Payara offers both Server and Micro, Open Liberty produces runnable jars, so you can often pick the packaging per service without changing the programming model.

#### Q66. [Practical] How do you handle large file uploads in a Jakarta EE app without running out of memory?

The memory-killer with uploads is **buffering the whole file in memory** — reading a `byte[]` of a 2 GB upload, or letting a framework materialize the full multipart body, will OOM the heap under even modest concurrency (10 concurrent 500 MB uploads = 5 GB of heap). The defenses operate at several layers, and a strong answer covers all of them.

First, **stream rather than buffer**. The Servlet multipart API (`@MultipartConfig`) lets you set a `fileSizeThreshold` above which parts spill to disk instead of staying in memory, plus `maxFileSize` and `maxRequestSize` caps that the container enforces *before* your code runs:

```java
@WebServlet("/upload")
@MultipartConfig(
    fileSizeThreshold = 1024 * 1024,        // ≤1MB in memory, larger spills to temp disk
    maxFileSize       = 100L * 1024 * 1024, // reject any single file > 100MB
    maxRequestSize    = 120L * 1024 * 1024, // reject the whole request > 120MB
    location          = "/var/upload-tmp")
public class UploadServlet extends HttpServlet {
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {
        Part part = req.getPart("file");
        try (InputStream in = part.getInputStream()) {
            storage.streamTo(in);            // stream straight to disk/object store, never a byte[]
        }
    }
}
```

Second, **enforce limits early and reject fast** — `maxFileSize`/`maxRequestSize` make the container reject oversized uploads before they're fully read, so an attacker can't exhaust disk or memory by sending a huge body. Third, **don't store the bytes in the entity/DB by default**; stream to object storage (S3/blob) or disk and persist only a reference, because pushing large blobs through JPA buffers them and bloats the DB. Fourth, for very large or unreliable uploads, prefer **chunked/resumable** protocols (tus, multipart-by-range) so a dropped connection doesn't restart from zero.

The operational extras that bite in production: configure the **reverse proxy / load balancer** body-size limits too (nginx `client_max_body_size`, etc.) or the container limits never get a chance to apply; clean up the **temp directory** (failed uploads leave spill files); validate content type and scan for malware out-of-band; and for async-heavy systems use async I/O so a slow uploader doesn't pin a worker thread for minutes (Servlet async / non-blocking read). The throughline: never let untrusted input size dictate your heap — stream it, cap it, and push the bytes to storage built for them.

#### Q67. [Practical] How do you test a Jakarta EE application — unit, integration, and in-container — and where does each fit?

Testing EE code has a reputation for being hard because so much behavior comes from the container (injection, transactions, JPA, JAX-RS). The pragmatic answer is a **test pyramid** that uses the cheapest technique that actually exercises the behavior you care about, escalating to in-container tests only where container semantics matter.

```
   ▲ few   In-container / E2E (Arquillian, deployed app + Testcontainers DB)
   │       — real server, real CDI/JTA/JPA; slow, high fidelity
   │       Integration (Testcontainers + embedded runtime, REST-assured)
   │       — real DB & HTTP, lighter than full server
   ▼ many  Unit (JUnit 5 + Mockito; plain POJO logic, mocked deps)
           — fast, no container, the bulk of tests
```

**Unit tests** treat CDI beans as plain objects: because CDI uses constructor/field injection, you instantiate the class and pass mocks (`@Mock OrderRepository`) — no container needed, milliseconds per test. This covers business logic. The limitation is that anything depending on container behavior (transaction rollback, JPA dirty checking, JAX-RS marshalling, `@RolesAllowed`) isn't exercised, so don't pretend a mocked unit test validates your transaction boundaries.

**Integration tests** bring up the *real* infrastructure cheaply: **Testcontainers** spins up a real PostgreSQL/MySQL/Kafka in Docker so JPA mappings, SQL dialect quirks, constraints, and migrations are tested against the actual engine (not H2, which famously diverges); **REST-assured** drives the real HTTP API. For the runtime, Quarkus's `@QuarkusTest` and Open Liberty's dev mode start the actual application stack fast enough for the inner loop. **In-container tests** with **Arquillian** package a micro-deployment, deploy it to a real (or embedded) server, and run the test *inside* the container so you get genuine CDI, JTA, JPA, and JAX-RS — the highest fidelity for verifying container contracts, at the cost of speed and setup complexity.

The senior judgment is about **fidelity vs cost**: push as much as possible down to fast unit tests; use Testcontainers for anything touching the database or external systems (the single biggest source of "works on H2, breaks in prod" bugs); reserve Arquillian/full-container tests for the genuinely container-dependent behaviors that can't be validated otherwise. Avoid the common trap of testing JPA against an in-memory H2 with relaxed SQL compatibility and declaring victory — Testcontainers against the real engine catches dialect, locking, and constraint behavior that H2 silently tolerates.

### 🟠 Advanced — extended

#### Q68. [Practical] Production incident: a JAX-RS endpoint intermittently returns 500s and you find "connection is not associated with a managed connection" / leaked connections after a DB blip. Diagnose and harden.

This symptom points to **broken/leaked connections** surviving a transient database disruption (a failover, restart, network blip, or firewall idle-kill). The pool handed out connections that were established *before* the blip; the DB has since dropped them server-side, but the pool still believes they're valid, so the next request that borrows one gets a dead socket and the driver throws — manifesting as intermittent 500s that mysteriously "heal" over minutes as the bad connections age out. The "not associated with a managed connection" variant additionally signals a connection used *outside* the transaction/enlistment the container expects, often because code held a connection across a boundary or a leak detector reclaimed it.

```
DB failover at T0 ──► all pooled connections silently dead
   request borrows stale conn ──► driver I/O error ──► 500
   ... repeats until maxLifetime/validation evicts the dead ones
```

The hardening, in order of impact:

1. **Connection validation.** Enable test-on-borrow or background validation with a fast validation query / JDBC `isValid()` so the pool detects and discards dead connections *before* handing them out. WildFly: `<validate-on-match>`/`<background-validation>`; HikariCP/Agroal: `connectionTestQuery`/`validationQuery` + `keepalive`. This alone fixes the "intermittent 500s after failover" class of bug.
2. **`maxLifetime` shorter than the DB/firewall idle timeout.** Proactively recycle connections so none lives long enough to be silently killed by the DB or a stateful firewall (a notorious cause of "connection reset" after exactly N minutes idle).
3. **Leak detection.** Set a `leakDetectionThreshold` so the pool logs a stack trace when a connection is borrowed and not returned within X seconds — that stack trace points straight at the code path that leaks (usually a missing try-with-resources / a path that throws before `close()`).
4. **Fail fast and shed.** A short `connectionTimeout` so threads don't hang forever waiting for a connection during the recovery window, plus a circuit breaker / readiness-probe failure so Kubernetes stops routing while the DB is unreachable.

The root-cause discipline: connections are stateful sessions to an external system that can die independently of your app, so a pool must **validate, recycle, and detect leaks** — treating connections as immortal is the bug. In the postmortem I'd also check whether application code ever bypasses the container (raw `DataSource.getConnection()` without try-with-resources, or holding a connection across an external HTTP call), because that turns a transient blip into a permanent leak. The lesson is that pool *correctness* config (validation, lifetime) is as important as pool *sizing*, and it's the part teams forget until their first DB failover.

#### Q69. [Practical] How do you do a zero-downtime deployment of a Jakarta EE application, and what application-level constraints does rolling/blue-green deployment impose?

Zero-downtime deployment is mostly an *orchestration* concern, but it imposes hard *application* requirements that EE developers must design for, and missing them is what turns a "rolling update" into an outage. The two dominant strategies:

```
Blue-Green:  run full new version (green) alongside old (blue), flip the LB
             when green is healthy. Instant cutover, instant rollback, 2× capacity.
Rolling:     replace instances a few at a time; OLD and NEW run SIMULTANEOUSLY
             during the rollout window. Cheaper, but mixed-version traffic.
```

The application-level constraints that actually determine whether this works:

1. **Graceful shutdown.** A draining instance must stop accepting new requests, finish in-flight ones, then exit. This means honoring SIGTERM, failing the **readiness probe first** (so the LB stops routing) while staying live long enough to drain, and not killing active transactions mid-flight. EE apps need a `@PreDestroy`/`ServletContextListener` that quiesces executors, JMS consumers, and timers cleanly. Kubernetes `terminationGracePeriodSeconds` + a `preStop` hook that fails readiness and sleeps is the standard pattern.
2. **Backward/forward-compatible database schema.** During a rolling update, old and new code hit the *same* database simultaneously, so a destructive migration (drop/rename a column the old version still reads) breaks the old instances. The discipline is **expand-then-contract**: deploy an additive migration (add nullable column / new table) compatible with both versions, deploy the new code, then in a *later* release remove the old column once no running version needs it. Never couple a breaking schema change to the same release that needs it.
3. **No sticky in-JVM state, or replicated sessions.** If a user's session lives only in one instance's heap, killing that instance loses it. Either go stateless (JWT/externalized session in Redis) or use the server's session replication (and accept its cost). Rolling updates assume any instance can serve any request.
4. **Idempotent, version-tolerant messaging/APIs.** A message produced by v2 might be consumed by a v1 instance and vice versa during the window — message schemas and REST contracts must be additively evolved (tolerant reader), and consumers idempotent.

The senior framing: orchestrators give you the *mechanism* for zero downtime, but the *application contract* is what makes it safe — graceful drain, expand/contract schema migrations, statelessness or session externalization, and backward-compatible interfaces. Blue-green is operationally simpler to reason about (one version serves at a time, trivial rollback) but needs double capacity; rolling is resource-efficient but forces you to tolerate mixed-version traffic, which is precisely where un-thought-through schema or contract changes cause incidents.

#### Q70. [Practical] Your monolithic EE app suffers long GC pauses under load. How do you diagnose and tune the JVM, and what app-level causes do you check first?

Long GC pauses present as periodic latency spikes (requests freeze for hundreds of ms to seconds), often correlated with traffic. The disciplined approach is **measure before tuning** — never change GC flags blind. Turn on GC logging and read it:

```bash
# Java 11+: unified logging
-Xlog:gc*,gc+heap=debug:file=/var/log/gc.log:time,uptime,level,tags
# then analyze with GCViewer / GCeasy: look at pause times, frequency,
# old-gen growth (leak?), allocation rate, and whether pauses are young or full GC
```

The diagnostic questions, in order: (1) Are pauses **full GCs** (whole heap, long) or frequent young-gen collections? Recurring full GCs with rising post-GC old-gen usage means a **memory leak** — capture a heap dump (`jcmd <pid> GC.heap_dump`) and find the retained dominator in Eclipse MAT. (2) Is the **allocation rate** sky-high? An endpoint churning huge transient objects (loading a million-row result set into memory, building giant strings, deserializing fat payloads) drives constant young-gen pressure. (3) Is the **heap simply too small** for the live set + working memory, forcing constant collection?

Tuning levers (after diagnosis):

```
-Xms = -Xmx           set equal to avoid heap-resize pauses; size to live set + headroom
-XX:+UseG1GC          G1 is the default (Java 9+); good general low-pause collector
-XX:MaxGCPauseMillis=200   G1 pause-time goal (a target, not a guarantee)
ZGC / Shenandoah      sub-millisecond pauses for large heaps (Java 17+), at some throughput cost
-XX:MaxMetaspaceSize  cap metaspace (and recall the redeploy-leak class of bug)
```

But the senior point is that **GC tuning is usually treating a symptom** — the real fix is almost always at the application layer. The biggest wins come from reducing allocation and retention: stream large query results instead of materializing them (`Stream`/cursor/pagination, not `getResultList()` of a million rows), bound caches (an unbounded `@ApplicationScoped` cache *is* a leak), avoid stuffing huge objects in the HTTP session, fix the N+1 that loads 50k entities per request, and keep the persistence context small (flush/clear in batch jobs — see the bulk-insert question). I'd switch collectors (G1 → ZGC/Shenandoah) only for genuinely large heaps where pause-time is the constraint after the app is already lean. The order of operations: log → analyze (leak vs pressure vs undersized) → fix the allocation/retention at the app layer → size the heap correctly → and only then reach for exotic collector flags.

#### Q71. [Practical] How do you manage secrets (DB passwords, API keys, JWT signing keys) in a Jakarta EE deployment across environments, and what are the anti-patterns?

Secrets management is where many EE apps quietly fail security review. The headline anti-patterns to call out immediately: **hardcoding** credentials in source, committing them in `persistence.xml`/`application.properties`, baking them into the Docker image, logging them, or putting them in a `@DataSourceDefinition` annotation (which embeds them in the deployable artifact). Anything that lands a plaintext secret in version control, an image layer, or a log line is a breach waiting to happen because those propagate to places you don't control.

The layered approach, from traditional app server to cloud-native:

```
App server:   server-managed DataSource with a PASSWORD VAULT
              (WildFly elytron credential-store, WebLogic encrypted config,
               Liberty <variable> + encoded password) — secret lives in
               server config, encrypted, ops-owned, NOT in the WAR.
Container:    inject via env vars / mounted files from a secret manager
              (Kubernetes Secrets, AWS Secrets Manager, HashiCorp Vault),
              consumed through MicroProfile Config (env-var ConfigSource).
Best:         short-lived, rotated, dynamically-issued credentials
              (Vault dynamic DB creds, cloud IAM DB auth) — no long-lived static secret.
```

The principles that matter: (1) **externalize** — the secret never lives in the artifact; the same WAR/image runs in dev/test/prod with the secret supplied by the environment (MicroProfile Config's ordinal precedence makes env/system properties override defaults cleanly). (2) **least exposure** — mount secrets as files or env vars only to the process that needs them, never broadcast. (3) **rotation** — prefer dynamically issued, short-lived credentials (Vault dynamic secrets, cloud IAM tokens) so a leaked secret has a short blast radius and rotation doesn't require redeploy. (4) **encryption at rest and in transit** for the secret store, and audit logging of secret access. (5) For **JWT signing**, never ship the private key in the app — load it from the secret store or a KMS/HSM, and use asymmetric keys (services verify with the public key, only the issuer holds the private key).

Kubernetes-specific caveat worth raising: a plain `Secret` is only **base64-encoded, not encrypted**, and is readable by anyone with namespace access, so enable encryption-at-rest for etcd and use a real secret manager (Vault/Sealed Secrets/cloud SM) for sensitive material rather than relying on the bare `Secret` object. The senior throughline: treat secrets as externalized, environment-supplied, short-lived, and audited — the application code should reference a *name* (JNDI/config key) and never *contain* a value, which is exactly the indirection the EE platform's JNDI/Config layers were built to provide.

#### Q72. [Coding] Implement correct, efficient pagination over a large JPA result set, and explain why naive `setFirstResult/setMaxResults` degrades and how to fix it.

The naive offset pagination works but degrades on deep pages, and the fix (keyset/seek pagination) is a frequent senior-level discussion. First, the JPA offset approach:

```java
public List<Order> page(int page, int size) {
    return em.createQuery("SELECT o FROM Order o ORDER BY o.createdAt DESC, o.id DESC", Order.class)
             .setFirstResult(page * size)   // OFFSET
             .setMaxResults(size)           // LIMIT
             .getResultList();
}
```

The problem: `OFFSET n` requires the database to **scan and discard the first n rows** every time, so page 1 is fast but page 10,000 makes the DB read and throw away 200,000 rows before returning 20 — latency grows linearly with page depth (O(offset)). It's also **unstable**: if rows are inserted/deleted between page requests, the offset shifts and users see duplicates or skipped rows. There are two further traps specific to JPA: paginating a query with a `JOIN FETCH` of a *collection* makes Hibernate apply the limit **in memory** after fetching everything (a `HHH000104` warning) — catastrophic — so paginate the root entities first and fetch collections separately; and counting total rows for "page X of Y" adds a second expensive `COUNT(*)` query.

The efficient fix is **keyset (seek) pagination**: instead of an offset, remember the sort key of the last row seen and ask for rows *after* it. With an index on the sort columns, the DB seeks directly to the start of the next page — constant time regardless of depth:

```java
// Keyset pagination: pass the last page's (createdAt, id) cursor instead of a page number
public List<Order> seek(Instant lastCreatedAt, long lastId, int size) {
    return em.createQuery("""
        SELECT o FROM Order o
        WHERE (o.createdAt < :ts) OR (o.createdAt = :ts AND o.id < :id)
        ORDER BY o.createdAt DESC, o.id DESC""", Order.class)
        .setParameter("ts", lastCreatedAt)
        .setParameter("id", lastId)          // tie-breaker keeps ordering total/stable
        .setMaxResults(size)
        .getResultList();
}
```

**Why keyset wins:** with a composite index on `(createdAt, id)` the query is O(log n + size) — the DB jumps straight to the cursor position — and it's **stable** under concurrent inserts because it anchors to a real row's key rather than a positional offset. The trade-offs: keyset only supports next/previous (no random "jump to page 500"), and you must include a unique tie-breaker (`id`) in the sort or rows with equal `createdAt` can be skipped/duplicated. The senior guidance: use offset pagination for small/shallow datasets and admin tables where "page N" UX matters; switch to keyset for large feeds, infinite-scroll, and APIs where deep pagination or stability under churn is required — and always paginate root entities, fetching collections in a second query (`@BatchSize`/entity graph) to avoid the in-memory-limit trap.

#### Q73. [Practical] How do you expose application metrics and wire them into monitoring/alerting for a Jakarta EE service, and what should you actually alert on?

Observability is table stakes for production EE services, and the platform answer is **MicroProfile Metrics** (or, increasingly, **Micrometer/OpenTelemetry metrics**) exposing a Prometheus-scrapable endpoint that a Prometheus/Grafana or APM stack ingests. You get JVM and vendor metrics for free (heap, GC, thread pools, datasource pool usage) and add business/operational metrics declaratively:

```java
@ApplicationScoped
public class OrderService {
    @Counted(name = "orders_created_total", description = "orders created")
    @Timed(name = "order_create_latency", unit = MetricUnits.MILLISECONDS)
    public Order create(Order o) { ... }   // exposes count + latency histogram at /metrics
}
// scraped by Prometheus from /metrics (or /q/metrics in Quarkus)
```

But emitting metrics is the easy half; the senior skill is **knowing what to alert on**. The best framework is to alert on **symptoms users feel (SLOs), not causes**, and reserve cause-based metrics for diagnosis. Concretely I'd structure it around the **RED method** for the service (Rate, Errors, Duration) and the **USE method** for resources (Utilization, Saturation, Errors):

```
ALERT on symptoms (page someone):
  - error rate (5xx / total) over SLO threshold        ← users see failures
  - p99 latency over SLO                                ← users see slowness
  - readiness failing across replicas                  ← service down
SATURATION metrics (alert when near limit / use for diagnosis):
  - DB connection pool: pending/active near max         ← imminent starvation
  - thread pool queue depth / rejected tasks
  - heap after-GC trending up (leak), GC pause time
  - circuit breaker open, JMS queue depth / DLQ growth, message age
```

The anti-patterns to avoid: alerting on *causes* (high CPU, a single GC pause) pages people for things that may not affect users and causes alert fatigue; alerting only on averages hides the tail (always track p95/p99, not mean latency); and not having **saturation** signals means you find out about pool/thread exhaustion only when it's already an outage instead of when it's trending toward one. I'd also emit the *four golden signals* with **labels** (endpoint, status) so you can slice which endpoint is degrading, and ensure metrics, logs (with correlation ID), and traces are **linked** so an alert leads quickly to the offending request. The throughline: instrument richly for diagnosis, but alert narrowly on user-facing SLO breaches plus leading saturation indicators — that combination catches incidents early without drowning on-call in noise.

#### Q74. [Practical] A scheduled `@Schedule` EJB timer runs twice (or not at all) in your clustered deployment. Explain why and how to fix it.

This is a classic clustering gotcha. The EJB `@Schedule`/timer service by default fires a timer **on every node that has the timer registered**, so in a cluster of N nodes a "send the nightly report" timer runs N times — sending N emails, processing the batch N times, or racing on the same rows. The "not at all" variant happens when timers are *persistent* and tied to a specific node's timer store that's down, or when a misconfiguration means no node owns the timer. The root cause is that a timer is a per-JVM construct, and naive clustering replicates the *trigger*, not the *coordination*.

The fixes, depending on platform and requirements:

1. **Use clustered/HA timers.** Modern servers support cluster-aware timer execution where the timer fires on exactly one node. WildFly/JBoss EAP support **clustered persistent timers** (backed by a shared database timer store with a distributed singleton/lock so one node executes); configure the timer-service to use a shared datastore and mark the timer persistent. This is the cleanest EE-native answer.
2. **Singleton election.** Run the scheduled job inside a cluster-wide **singleton service** — only the elected primary node has the active timer. JBoss EAP's `@Clustered @Singleton`/HA singleton service, or a distributed lock (Hazelcast/Infinispan, ZooKeeper, a DB `SELECT ... FOR UPDATE` leader lock) so only the lock holder executes.
3. **External scheduler / `ShedLock`-style guard.** In cloud-native deployments the common pattern is a database-backed lock (acquire a row/advisory lock before running; skip if already held) so that even though every replica's timer fires, **only the first to grab the lock does the work**. This is robust and platform-agnostic.
4. **Externalize scheduling entirely.** Move the trigger out of the app to a Kubernetes `CronJob` that invokes a single endpoint, or a message-driven design where one scheduler enqueues a job and an idempotent consumer processes it once.

```java
// Lock-guarded scheduled task (pattern): only the lock holder runs the body
@Singleton
public class ReportJob {
    @Schedule(hour = "2", persistent = false)   // fires on every node...
    public void run() {
        if (!clusterLock.tryAcquire("nightly-report", Duration.ofMinutes(30))) return; // ...but only one wins
        try { generateAndSendReport(); } finally { clusterLock.release("nightly-report"); }
    }
}
```

The senior framing: scheduling in a cluster is fundamentally a **distributed coordination / leader-election problem**, and the bug comes from assuming "set up a timer" means "runs once globally" when it actually means "runs once per node." Whichever mechanism you choose (HA timer, singleton election, distributed lock, external cron), the job itself should also be **idempotent** as a belt-and-suspenders measure, because in any leader-election system there are edge cases (failover during execution, lock expiry) where the job *could* run twice — idempotency turns that from a data-corruption incident into a harmless no-op.

### 🔴 Expert — extended

#### Q75. [Practical] You're moving a thread-per-request Jakarta EE service onto virtual threads (Project Loom, Java 21 / EE 11). What breaks, what helps, and how do you roll it out safely?

Virtual threads (Java 21, leveraged by Jakarta EE 11's Java 17/21 baseline) promise to make the *blocking* thread-per-request model scale like async without the async programming complexity: you keep writing straightforward blocking code, but the runtime parks a virtual thread cheaply on blocking I/O instead of pinning an OS thread, so a small platform-thread pool can carry hundreds of thousands of in-flight blocking requests. For EE this is enormous because the platform's entire ergonomic model (servlets, JAX-RS, JDBC, `@Transactional`) is blocking-style — Loom lets that model scale without rewriting everything reactive.

But several things break or need care:

1. **`synchronized` causes pinning.** If a virtual thread blocks *inside a `synchronized` block/method* (or a native frame), it **pins** the carrier OS thread, defeating the benefit — under load you can exhaust carriers and stall. Legacy EE libraries, connection pools, and logging frameworks historically use `synchronized` around I/O. The fix is to replace hot-path `synchronized` with `ReentrantLock` (which is Loom-friendly), and to audit dependencies; later JDKs reduce some pinning, but you must verify with `-Djdk.tracePinnedThreads=full`.
2. **Thread-pool-based throttling disappears.** The whole point of small worker pools was implicit backpressure (Q on `@Stateless` pooling). With virtual threads you can spawn effectively unlimited concurrency, so a slow downstream no longer self-limits — you can now overwhelm the *database* or a downstream service because nothing caps fan-out. You must reintroduce **explicit limits**: a bounded connection pool (still finite!), semaphores/bulkheads, and rate limits. Virtual threads remove the accidental safety the bounded pool gave you.
3. **ThreadLocal cost and pooling assumptions.** Code that caches expensive objects in `ThreadLocal` assuming a small fixed thread count now allocates per-virtual-thread (potentially millions), blowing memory. And you must **never pool virtual threads** — they're cheap and disposable; pooling them is an anti-pattern. EE context propagation (CDI scope, security, MDC, tracing) needs to follow virtual threads correctly, so use the platform's scoped-value/context-propagation mechanisms.

The safe rollout: (1) move to the Java 21 baseline and a Loom-aware runtime (Quarkus, Helidon Níma, recent WildFly/Liberty offer virtual-thread executors for request handling); (2) enable pinning diagnostics in staging and load-test, fixing every `synchronized`-on-I/O hotspot you find in your code and dependencies; (3) **re-add explicit concurrency limits** (connection pool sizing becomes *more* important, not less, plus bulkheads on downstream calls) because the implicit pool cap is gone; (4) verify context propagation (transactions, security, tracing) survives the virtual-thread hop; (5) roll out behind a flag with the old executor as fallback, comparing latency/throughput/error metrics. The senior insight: Loom doesn't make blocking code free — it makes *waiting* cheap, so the bottleneck shifts from "thread pool exhaustion" to "downstream resource exhaustion," and the engineering work moves from writing async plumbing to **re-establishing the backpressure** that the bounded thread pool used to provide for free.

#### Q76. [Practical] A Quarkus/Helidon native image build fails or the app misbehaves only when compiled to native (works fine on the JVM). How do you debug GraalVM native-image issues in a Jakarta EE/MicroProfile app?

Native compilation (GraalVM `native-image`) does **closed-world, ahead-of-time** analysis: it must determine every reachable class, method, and resource *at build time* and produces a binary with no JIT, no dynamic class loading, and only the reflection/resources/proxies it was told about. This is why the Core Profile + CDI Lite exist (build-time bean wiring), and why code that relies on **runtime reflection, dynamic proxies, classpath scanning, or runtime resource loading** — all common in classic EE/reflection-heavy libraries — breaks specifically in native mode while working on the JVM, where those happen dynamically.

The characteristic failure modes and their fixes:

```
Symptom (native only)                     Cause                         Fix
─────────────────────────────────────     ──────────────────────       ─────────────────────────────
ClassNotFound / NoSuchMethod at runtime    reflection not registered     reflect-config.json / @RegisterForReflection
"resource not found" (templates, certs)    resource not bundled          resource-config.json / quarkus.native.resources.includes
ProxyClass error                           dynamic proxy not declared     proxy-config.json
Build fails: "unsupported feature"          class initialized at build    move to runtime init (--initialize-at-run-time)
Serialization fails                        serialization not registered   serialization-config.json
SSL/timezone/locale missing                not included by default        enable in native config
```

The debugging methodology: (1) **reproduce the analysis decision** — run with the **native-image agent** (`-agentlib:native-image-agent=config-output-dir=...`) on the JVM while exercising the app's full code paths; the agent records the reflection, proxies, resources, and JNI the app actually uses and emits the config files the native build needs. This is the single most effective tool. (2) Read the build output's **reachability/analysis report** to see what's included and why (`--diagnostics-mode`, reachability metadata). (3) For "works on JVM, wrong behavior on native," suspect **build-time vs run-time initialization**: a class whose static initializer reads an env var or current time gets *frozen at build time* unless you mark it `--initialize-at-run-time`, so it captures the build machine's value instead of the runtime's. (4) Lean on the framework: **Quarkus and Helidon do most of this for you** at build time (they generate reflection/proxy config, do CDI wiring at build, and ship metadata for supported extensions) — which is exactly why a Quarkus extension "just works" in native while a raw library you added does not, because no one supplied its native metadata.

The senior framing: native image trades dynamism for startup speed and low memory by resolving the world at build time, so the entire class of EE bugs here stems from **runtime dynamism that the closed-world analysis can't see**. The fix is always to *make the dynamism visible to the build* — via the agent-generated config or framework extensions — or to *eliminate* it (prefer build-time CDI, avoid reflection-heavy libraries without native metadata). Practically: develop and test on the JVM, but run native builds and native integration tests (`@QuarkusIntegrationTest`) in CI from day one, because native-only failures discovered late are expensive — and reserve native for services where its sub-100ms startup and tiny footprint genuinely pay off (serverless, dense packing, scale-to-zero), since the build is slower and the debugging surface is harder than plain JVM.

#### Q77. [Practical] Production is throwing `OutOfMemoryError: Java heap space` intermittently. Walk through your full incident-response and root-cause process for an EE app.

A heap OOM is one of the highest-stakes EE incidents because it usually means an instance dies, and the disciplined response separates *stabilize* from *diagnose* from *fix*. My process:

**1. Stabilize (stop the bleeding).** Ensure the process produces a **heap dump on OOM** automatically — this must be configured *before* the incident: `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/var/dumps`. Confirm the orchestrator restarts the pod (readiness/liveness) so users see recovery, and if it's a fast-leak, temporarily scale out / raise `-Xmx` to lengthen the time between crashes so you can capture a *good* dump. Crucially, **a bigger heap is not a fix** — for a true leak it only delays the OOM; for a load spike it might be the right answer, and distinguishing the two is the whole diagnosis.

**2. Diagnose — leak vs load vs undersized.** Read the GC log: if **old-gen occupancy after each full GC keeps climbing** and never recovers, it's a leak (memory retained, not released); if heap usage spikes with traffic and recovers after GC, it's a *load/allocation* problem or undersized heap, not a leak.

```bash
jcmd <pid> GC.heap_dump /var/dumps/heap.hprof   # capture (or use the auto-dump)
# Open in Eclipse MAT → "Leak Suspects" report → "Dominator Tree"
# → find the object retaining the most heap → "Path to GC Roots (exclude weak/soft)"
#   shows EXACTLY what's holding the memory and why it can't be collected.
```

**3. Identify the EE-specific usual suspects** the dominator tree points to:

- **Unbounded caches.** An `@ApplicationScoped` `HashMap` used as a cache with no eviction/TTL grows forever — the most common "slow leak." (Use a bounded cache: Caffeine/Infinispan with size+TTL.)
- **HTTP session bloat.** Large objects stuffed into `HttpSession` × many concurrent sessions, or sessions never expiring — heap scales with user count.
- **Materializing huge result sets.** `getResultList()` of a million rows, or building a giant in-memory collection/CSV/JSON — an *allocation* spike that OOMs under one bad request (paginate/stream instead).
- **Persistence-context accumulation** in a long loop without `flush()/clear()` (the bulk-insert problem).
- **ThreadLocal / classloader leaks** across redeploys (the Metaspace cousin, but heap-retaining objects too).
- **Connection/stream resources** not closed, retaining buffers.

**4. Fix at the right layer and add guardrails.** Fix the actual retention (bound the cache, stream the query, externalize sessions), then add **prevention**: heap-usage alerting *before* OOM (post-GC old-gen trend), memory limits aligned between `-Xmx` and the container limit (a container OOM-kill with `-Xmx` set too close to the cgroup limit is a separate trap — leave headroom for metaspace, threads, and off-heap/direct buffers), and a load test that reproduces the leak so the fix is verified.

The senior throughline: heap OOM response is *capture the evidence (dump), classify the shape (leak vs spike vs undersized), find the dominator and GC-root path, fix the retention at the app layer, and add leading-indicator alerts*. The trap that catches teams is reflexively raising `-Xmx` — it converts a fast, debuggable crash into a slow, harder-to-catch one and never fixes a real leak. The companion trap is the **container cgroup limit**: even with a fine `-Xmx`, total RSS (heap + metaspace + thread stacks + direct ByteBuffers + native) can exceed the pod's memory limit and get OOM-killed by the kernel with *no* Java heap dump — so size the heap as a fraction of the container limit and watch RSS, not just heap.

#### Q78. [Practical] Your single-page app gets CORS errors calling your JAX-RS API. Explain what's actually happening and implement it correctly.

CORS errors confuse people because the *symptom* (a blocked fetch in the browser) and the *cause* (a missing response header) live on different sides. CORS is a **browser-enforced** security mechanism: when JavaScript on `https://app.example.com` calls an API on `https://api.example.com` (a different origin), the browser refuses to expose the response to the script *unless the server explicitly opts in* with `Access-Control-Allow-Origin`. The server isn't "rejecting" anything — it returns a normal response, but the browser blocks the JS from reading it because the opt-in header is absent. (Non-browser clients like curl never see CORS at all, which is why "it works in Postman but not the browser" is the tell.)

For any "non-simple" request (custom headers like `Authorization`, methods like `PUT`/`DELETE`, JSON content type), the browser first sends a **preflight `OPTIONS`** request asking "am I allowed to do this?" and only proceeds if the server answers with the right `Allow-*` headers. So you must handle both the preflight and the actual response. A `ContainerResponseFilter` is the clean place:

```java
@Provider
public class CorsFilter implements ContainerResponseFilter {
    @Override
    public void filter(ContainerRequestContext req, ContainerResponseContext resp) {
        var h = resp.getHeaders();
        h.add("Access-Control-Allow-Origin", "https://app.example.com");  // NOT "*" with credentials
        h.add("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        h.add("Access-Control-Allow-Headers", "Authorization, Content-Type");
        h.add("Access-Control-Allow-Credentials", "true");               // for cookies/auth
        h.add("Access-Control-Max-Age", "3600");                         // cache preflight
    }
}
// Plus a resource/filter that answers OPTIONS preflight with 200 and the same headers.
```

The security points an interviewer wants: **never combine `Access-Control-Allow-Origin: *` with `Allow-Credentials: true`** — the spec forbids it and browsers reject it, because a wildcard origin plus credentials would let any site make authenticated calls; instead echo back a *validated* specific origin from an allowlist. Don't reflexively reflect *any* `Origin` header back (that defeats the protection). And remember CORS is **not authorization** — it only controls which browser origins can *read* responses; it does nothing for non-browser clients and is no substitute for actual authentication/authorization on the endpoint. Most app servers and Quarkus offer a built-in CORS configuration that's preferable to hand-rolling the filter, but the mental model (browser-enforced, preflight, server opt-in, allowlist not wildcard-with-credentials) is what you must be able to explain.

#### Q79. [Practical] How do you tune JMS redelivery and dead-letter handling so a poison message doesn't take down your consumer?

A **poison message** — one that always fails processing (malformed payload, references deleted data, triggers a bug) — is a production hazard because with at-least-once delivery and transacted consumers, a failure rolls back and the broker **redelivers the same message**, which fails again, redelivers again... an infinite hot loop that burns CPU, fills logs, and blocks the queue behind it. The fix is a deliberate **redelivery + dead-letter strategy** rather than relying on defaults.

The levers (broker-specific names vary; concepts are universal): a **redelivery limit / max-delivery-count** caps how many times a message is retried before the broker gives up and routes it to a **Dead Letter Queue (DLQ)** instead of the original queue. A **redelivery delay / backoff** spaces out retries (often with exponential backoff) so a *transient* failure (downstream briefly down) gets a chance to succeed without hammering, while a *permanent* failure exhausts retries and lands in the DLQ.

```xml
<!-- ActiveMQ Artemis (WildFly default) address-setting -->
<address-setting match="jms.queue.orders">
    <max-delivery-attempts>5</max-delivery-attempts>      <!-- then → DLQ -->
    <redelivery-delay>2000</redelivery-delay>             <!-- 2s base delay -->
    <redelivery-delay-multiplier>2.0</redelivery-delay-multiplier>  <!-- exponential backoff -->
    <max-redelivery-delay>60000</max-redelivery-delay>
    <dead-letter-address>jms.queue.ordersDLQ</dead-letter-address>
</address-setting>
```

```java
// Consumer: distinguish transient (retry) from permanent (don't retry) failures
@Override public void onMessage(Message m) {
    try {
        processor.process(m.getBody(Order.class));
    } catch (TransientException e) {
        throw new EJBException(e);     // rollback → broker redelivers (with backoff)
    } catch (PermanentException e) {
        // DON'T rethrow → commit so it isn't redelivered; route to DLQ ourselves / log+alert
        deadLetter.send(m, e.getMessage());
    }
}
```

The senior nuances: (1) **classify failures** — only retry *transient* errors; for a genuinely poison message, retrying is pointless, so either let the delivery-count exhaust to the DLQ or explicitly divert it, but don't loop forever. (2) **Monitor the DLQ** — a DLQ that silently fills is data quietly being lost; alert on DLQ depth and have a tested **replay** path to reprocess after fixing the bug. (3) **Keep consumers idempotent** because redelivery means duplicate processing on the *successful* retry of a partially-applied message. (4) **Beware head-of-line blocking** — if your queue is strictly ordered and one poison message blocks everything behind it, you need the DLQ to move it aside quickly (low max-delivery-attempts) or a design that doesn't require strict ordering. The throughline: at-least-once delivery guarantees the message *will* be retried, so production-grade messaging is mostly about bounding retries, backing off, dead-lettering poison messages, and making consumers idempotent — defaults left untouched are how a single bad message becomes an outage.

#### Q80. [Practical] How do you configure transaction and statement timeouts, and why are these among the most overlooked production settings?

Timeouts are the difference between a slow query causing a *brief* blip versus *cascading* into total resource exhaustion, yet they're frequently left at "infinite" defaults. There are several distinct timeouts, and conflating them is a common mistake — each guards a different resource:

```
Statement/query timeout   — max time the DB lets ONE SQL statement run before it cancels it.
                            (JDBC Statement.setQueryTimeout, hibernate.jakarta.persistence.query.timeout,
                             or DB-side statement_timeout). Guards: a runaway query holding a connection+locks.
JTA transaction timeout   — max wall-clock for the WHOLE transaction before the TM marks it rollback-only.
                            (server tx-timeout, @TransactionTimeout on EJB). Guards: long tx holding a connection,
                             accumulating locks, blocking others.
Connection acquisition timeout — max time a thread waits to BORROW a pooled connection (fail fast vs hang).
HTTP/socket read timeout  — max time waiting on a downstream call (REST client, JMS). Guards: a hung dependency
                            pinning a request thread (and the DB connection it may hold) indefinitely.
```

```java
// EJB transaction timeout
@Stateless
public class ReportService {
    @TransactionTimeout(value = 30, unit = TimeUnit.SECONDS)
    public Report build() { ... }     // tx rolled back if it exceeds 30s
}
// JPA query timeout (per query)
em.createQuery("...").setHint("jakarta.persistence.query.timeout", 5000).getResultList();
// MicroProfile Rest Client / fault tolerance
@Timeout(value = 2, unit = ChronoUnit.SECONDS)
int callDownstream() { ... }
```

Why they're overlooked and why it matters: without timeouts, the failure mode is **unbounded resource holding** — a single slow query holds its connection and locks indefinitely; the request thread waiting on it is also stuck; under load every worker ends up waiting on the same slow path, and you get the thread-pool/connection-pool exhaustion stall (the production-incident question). Timeouts convert "everything hangs forever" into "this one request fails fast and frees its resources," which is the foundation of graceful degradation. The senior discipline is to set them in a **consistent hierarchy**: the downstream-call timeout < the transaction timeout < the request timeout, so an inner timeout fires and releases resources *before* an outer one, and a statement timeout at the DB as a backstop so even a connection used outside your timeouts can't run away. Pair them with circuit breakers and `connectionTimeout` so the system *sheds* load under stress instead of accumulating it. The interview-grade point: timeouts are how you bound the blast radius of slowness; "no timeout" is a latent outage waiting for the day a dependency or query gets slow.

#### Q81. [Coding] A `@OneToMany` relationship causes a subtle bug where saving a child doesn't persist, or the wrong rows update. Explain ownership and the bidirectional-sync pitfall.

This is one of the most common JPA correctness bugs, and it stems from misunderstanding the **owning side** of a relationship. In JPA, for a bidirectional association, exactly **one side owns the foreign key** and JPA *only looks at the owning side* when deciding what SQL to generate. For a one-to-many/many-to-one, the **`@ManyToOne` side is the owner** (it holds the FK column); the `@OneToMany` side that declares `mappedBy` is the **inverse** side and is essentially read-only for persistence purposes.

```java
@Entity public class Order {
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Item> items = new ArrayList<>();   // INVERSE side — mappedBy = NOT the owner
}
@Entity public class Item {
    @ManyToOne @JoinColumn(name = "order_id")
    private Order order;                             // OWNING side — holds the FK
}
```

The bug: developers add a child only to the collection and expect it to persist:

```java
// BROKEN — sets only the inverse side
Item item = new Item(...);
order.getItems().add(item);     // inverse side updated...
em.persist(order);              // ...but Item.order (the OWNER) is null → FK not set!
// Result: depending on mapping, the item isn't persisted, or its order_id is NULL,
// or a phantom UPDATE fires. JPA ignored the collection because it's the inverse side.
```

The fix is to **always set both sides**, ideally through a helper method that keeps the object graph consistent:

```java
// Order.java — synchronize both sides so the in-memory graph matches what JPA persists
public void addItem(Item item) {
    items.add(item);
    item.setOrder(this);     // set the OWNING side → JPA writes the FK
}
public void removeItem(Item item) {
    items.remove(item);
    item.setOrder(null);     // with orphanRemoval=true → row deleted
}
```

The deeper reasons this matters: (1) JPA generates SQL from the **owning** side, so an unset owner means an unset/wrong FK regardless of what the collection contains. (2) An out-of-sync object graph also lies to your *in-memory* code within the same transaction — `order.getItems()` shows the child but `item.getOrder()` is null, leading to NPEs and wrong business logic before any SQL even runs. (3) `equals`/`hashCode` on entities interact badly here — if you use the DB-generated `id` in `hashCode`, a not-yet-persisted entity (id null) added to a `HashSet`-backed collection can vanish after the id is assigned. The senior framing: bidirectional relationships are a *convenience for navigation* that comes with the obligation to keep both sides synchronized, and JPA's "only the owner drives SQL" rule is the single most important thing to internalize — almost every "my child didn't save" or "the FK is null" bug traces back to updating only the inverse side. Many teams sidestep the whole hazard by using only the owning (`@ManyToOne`) side and avoiding bidirectional collections unless navigation genuinely needs them.

#### Q82. [Practical] How do you manage database schema migrations for a Jakarta EE app, and why is `hibernate.hbm2ddl.auto=update` dangerous in production?

Schema evolution is a first-class operational concern, and the platform's default convenience — Hibernate's `hbm2ddl.auto`— is exactly the wrong tool for production. The values are `validate` (check the schema matches entities, change nothing), `update` (alter the schema to match entities), `create`/`create-drop` (rebuild, destroying data), and `none`. `update` is seductive ("Hibernate keeps the schema in sync automatically") and catastrophic in production for several reasons:

1. **It never drops or modifies in destructive ways** — it only *adds*, so renamed columns, changed types, removed columns, and altered constraints either don't happen or happen unpredictably, leaving schema drift. 2. **It's not deterministic or reviewable** — you can't see, review, or version the DDL it will run; it decides at startup based on whatever entities are on the classpath. 3. **It's not coordinated across instances** — in a cluster, multiple nodes starting simultaneously may race to alter the schema. 4. **No rollback, no history, no data migrations** — it can't backfill data, reorder steps, or be undone.

The production-grade answer is a **versioned migration tool** — **Flyway** or **Liquibase** — that treats the schema as code: ordered, immutable, version-controlled migration scripts applied in sequence, tracked in a metadata table, runnable in CI, and reviewable in a PR.

```sql
-- Flyway: V1__create_orders.sql, V2__add_status_index.sql ... applied in order, recorded
ALTER TABLE orders ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'NEW';
CREATE INDEX idx_orders_status ON orders(status);
```

```properties
# In production: Hibernate only VALIDATES; migrations own the schema
hibernate.hbm2ddl.auto = validate     # fail fast if entities and schema diverge
# Flyway/Liquibase runs the actual DDL (in CI/CD or at controlled startup with a lock)
```

The recommended setup: migrations (Flyway/Liquibase) are the single source of truth for schema changes, applied as a **discrete, gated step** in the deploy pipeline (or at startup behind a distributed lock so only one node migrates); Hibernate runs with `validate` so a mismatch between code and schema fails the deployment loudly instead of silently corrupting data. This also dovetails with **zero-downtime deploys** (the expand-then-contract discipline): versioned migrations let you make additive changes compatible with old and new code, deploy, then contract in a later migration — something `hbm2ddl.auto` fundamentally can't orchestrate. The senior throughline: schema changes are irreversible operations on production data, so they demand the same rigor as code changes — versioned, reviewed, ordered, tested against a real engine (Testcontainers), and applied deterministically — and `hbm2ddl.auto=update` is a development convenience that trades away every one of those properties.

#### Q83. [Practical] How do you implement rate limiting and idempotency for a public JAX-RS API, and why do you need both?

These two protections solve different but complementary problems for a public API. **Rate limiting** protects the *service* from being overwhelmed (abuse, runaway clients, DoS) by capping how many requests a client may make per window. **Idempotency** protects *data correctness* when a client safely retries — without it, a network blip that causes a retry of "charge the card" double-charges.

**Rate limiting** is best applied at the edge (API gateway, ingress, a `ContainerRequestFilter`) using a token-bucket or sliding-window algorithm keyed by client identity (API key, user, IP), returning `429 Too Many Requests` with a `Retry-After` header when exceeded:

```java
@Provider @Priority(Priorities.AUTHORIZATION + 10)
public class RateLimitFilter implements ContainerRequestFilter {
    private final RateLimiter limiter; // e.g. Bucket4j token bucket, keyed per client
    @Override public void filter(ContainerRequestContext req) {
        String client = clientKey(req);                 // API key / authenticated subject
        if (!limiter.tryConsume(client)) {
            req.abortWith(Response.status(429)
                .header("Retry-After", "5")
                .header("X-RateLimit-Remaining", "0")
                .build());
        }
    }
}
```

**Idempotency** uses a client-supplied **`Idempotency-Key`** header (a UUID the client generates per logical operation and reuses across retries). The server records the key with the result of the first successful processing; a retry with the same key returns the stored result instead of re-executing:

```java
@POST @Path("/payments")
public Response pay(@HeaderParam("Idempotency-Key") String key, Payment p) {
    var existing = idempotencyStore.find(key);          // keyed store (DB/Redis) with TTL
    if (existing != null) return existing.toResponse(); // replay prior result — no double charge
    Payment result = processor.charge(p);               // do the work once
    idempotencyStore.save(key, result);                 // record (atomically with the charge)
    return Response.status(201).entity(result).build();
}
```

The senior nuances: rate limiting belongs as **early in the pipeline as possible** (ideally the gateway, before the request consumes a thread/connection) and should be **distributed** in a multi-instance deployment — a per-JVM counter is wrong because each replica would allow the full quota; use a shared store (Redis) or gateway-level limiting so the limit is global. For idempotency, the **record-and-result must be atomic with the operation** (store the key in the same transaction as the charge, or you can process twice if the crash lands between charging and recording), the key needs a **TTL** (you can't store keys forever), and you should distinguish "same key, identical request" (replay the result) from "same key, *different* body" (reject as a conflict, since the client is misusing the key). Why you need both: rate limiting stops a client from *overwhelming* you but doesn't make retries *safe*; idempotency makes retries *safe* but doesn't stop *volume*. A robust public API combines them — plus authentication, input validation, and timeouts — so that clients can retry freely (idempotency), can't abuse you (rate limit), and the failure modes are bounded (timeouts). Note both are usually better handled by infrastructure (API gateway for rate limiting, a shared idempotency store) than reinvented per service, but you must understand the semantics to configure them correctly.

#### Q84. [Practical] You need to debug a database deadlock that surfaces as intermittent `LockAcquisitionException`/deadlock errors under load. Walk through it.

A deadlock occurs when two transactions each hold a lock the other needs, forming a cycle, so neither can proceed; the database detects the cycle and **kills one transaction as the "victim,"** which surfaces in JPA as a `PessimisticLockException`/`LockAcquisitionException` (or a vendor deadlock SQLState like Postgres `40P01`, MySQL `1213`). The "intermittent under load" character is the tell: deadlocks require concurrency and specific interleavings, so they're rare at low traffic and frequent at peak — and they're often *not* a bug in any single transaction but an emergent property of *ordering*.

**Diagnosis:**

```sql
-- Capture the deadlock graph from the DB (it knows exactly which locks/tx cycled):
-- PostgreSQL: it's logged automatically with the two statements involved (log_lock_waits=on)
-- MySQL/InnoDB: SHOW ENGINE INNODB STATUS \G  → "LATEST DETECTED DEADLOCK" section
--   shows both transactions, the rows/indexes locked, and which was rolled back.
```

The deadlock report tells you the two statements and the rows/indexes involved. The classic root cause is **inconsistent lock-acquisition order**: transaction A updates row 1 then row 2; transaction B updates row 2 then row 1 — interleave them and each holds what the other wants. Other common EE-specific causes: (1) a batch job and an online transaction touching the same rows in different orders; (2) **`CascadeType.REMOVE` on a collection** issuing per-child deletes that lock parent and child in an order that conflicts with another path; (3) **gap/next-key locks** in MySQL `REPEATABLE READ` locking more than the exact row, so two transactions inserting "adjacent" rows deadlock; (4) foreign-key checks taking shared locks on the parent while another tx updates it.

**Fixes, in order of preference:**

1. **Establish a consistent ordering.** Make all transactions acquire locks in the *same* order (e.g., always update accounts in ascending id order). A consistent global order makes deadlock cycles impossible — this is the most robust fix.
2. **Reduce lock scope and duration.** Shorter transactions hold locks for less time (don't do external I/O inside a tx; commit sooner; the transaction-timeout discipline). Smaller, more selective updates lock fewer rows.
3. **Use optimistic locking** (`@Version`) instead of pessimistic where contention is low — it avoids holding write locks at all, trading deadlocks for occasional `OptimisticLockException` + retry (the optimistic-locking question).
4. **Lower isolation where safe** or adjust index strategy to avoid gap locks (MySQL), and add indexes so the DB locks precise rows rather than ranges/scans.
5. **Retry the victim.** Deadlocks are *expected* under concurrency; the loser should retry with backoff (the transaction is atomic, so a retry is safe) — so wrap the operation in bounded retry-on-deadlock as a pragmatic backstop.

The senior framing: a deadlock is the database correctly protecting integrity by breaking a cycle, so the question is rarely "which transaction is buggy" but "why do my transactions acquire locks in conflicting orders, and can I make the order consistent or the locks narrower/shorter." You diagnose from the DB's own deadlock graph (don't guess), fix the *ordering/scope* root cause, and keep a deadlock-retry as a safety net because under enough concurrency some interleaving will always eventually occur.

#### Q85. [Practical] How do you set up distributed tracing across Jakarta EE / MicroProfile services, and what does it let you diagnose that logs and metrics cannot?

Distributed tracing reconstructs the **end-to-end path of a single request** as it fans out across services, recording each operation as a **span** (with start/end time, attributes, and parent linkage) all sharing one **trace ID**. The platform answer is **MicroProfile Telemetry** (which adopted **OpenTelemetry**, superseding the older MicroProfile OpenTracing), with the runtime auto-instrumenting JAX-RS inbound requests, the MicroProfile Rest Client outbound calls, and JDBC, while you add spans for meaningful business operations.

```
trace_id = abc123  (one request, many spans across services)
[ api-gateway 0–250ms ]
  └─[ order-svc /orders 5–230ms ]
       ├─[ DB SELECT order 10–25ms ]
       ├─[ HTTP → inventory-svc 30–180ms ]   ← 150ms here = the latency culprit
       │     └─[ inventory-svc DB query 40–170ms ]   ← actually a slow DB query
       └─[ HTTP → payment-svc 185–225ms ]
```

Setup: enable the runtime's OpenTelemetry support (Quarkus/Helidon/Liberty config or the `mp.telemetry` properties), point the OTLP exporter at a collector (Jaeger/Tempo/vendor), and ensure **context propagation** carries the W3C `traceparent` header across service calls *and* across async/thread boundaries (the context-propagation question — a raw `CompletableFuture` breaks the trace). You also inject the `trace_id`/`span_id` into log lines so logs and traces cross-link.

```java
@Inject Tracer tracer;   // OpenTelemetry
public Order place(Order o) {
    Span span = tracer.spanBuilder("order.place").startSpan();
    try (var scope = span.makeCurrent()) {
        span.setAttribute("order.items", o.getItems().size());
        return process(o);
    } catch (Exception e) {
        span.recordException(e); span.setStatus(StatusCode.ERROR); throw e;
    } finally { span.end(); }
}
```

What tracing reveals that logs and metrics cannot: **logs** tell you *what happened* in one service but not how a request flowed across many, and correlating them manually across services is painful; **metrics** tell you *aggregate* health (p99 latency is up) but not *which* request or *which hop* caused a specific slow request. Tracing answers the question metrics and logs can't: *"for this one slow/failed request, exactly which service and which operation consumed the time or threw the error?"* In the example above, metrics might show order-svc p99 climbing, logs in order-svc show nothing wrong, but the trace immediately pinpoints that the time is spent in inventory-svc's DB query — turning a multi-team finger-pointing exercise into a precise diagnosis. The three pillars are complementary: **metrics** for alerting on aggregate SLOs (and detecting *that* something's wrong), **traces** for locating *where* in a distributed call graph the problem is, and **logs** (correlated by trace ID) for the detailed *why* once you've localized it. The senior point: in a microservices EE/MicroProfile deployment, tracing is not optional polish — it's the only tool that makes cross-service latency and error attribution tractable, and the critical implementation detail is correct context propagation (sync *and* async) so traces don't fragment exactly where the request gets interesting.

#### Q86. [Practical] How does HTTP session clustering/replication work in a Jakarta EE cluster, what does it cost, and when should you avoid it entirely?

When you scale an app server horizontally and use `HttpSession` (or `@SessionScoped` beans), a user's session lives in one node's heap — so if the load balancer routes the next request to a different node, or the original node dies, the session is gone (logged out, lost cart). The classic EE answer is **session replication**: the cluster copies session state across nodes so any node can serve the user, and a failed node's sessions survive on another.

```
Approaches:
  Sticky sessions only  — LB pins a user to one node (jvmRoute/affinity cookie).
                          No replication; fast; but node death = lost sessions.
  Full replication      — every session copied to ALL nodes. Survives any failure,
                          but O(N) network/memory cost; scales badly past a few nodes.
  Buddy/pair replication — each node replicates to one or a few "buddies" (Infinispan/
                          JBoss). Survives single-node failure at far lower cost than full.
  Externalized sessions  — store sessions OUTSIDE the JVM (Infinispan/Hazelcast grid, Redis,
                          or DB). Nodes are stateless; any node serves any request.
```

The costs are real and often underappreciated: replication adds **network traffic and serialization overhead on every session mutation** (so everything you put in the session must be `Serializable`, and large session objects are expensive to replicate), it consumes **memory on multiple nodes**, it introduces **consistency questions** (a request that mutates the session on node A racing a failover to node B), and it **limits horizontal scalability** because the replication cost grows with cluster size (full replication especially). It also complicates rolling deploys — replicated session state must survive version changes (serialization compatibility).

The senior recommendation is increasingly to **avoid server-side session state altogether** for new services: go **stateless** with **token-based auth (JWT)** where the client carries the identity/claims and the server stores nothing per-session, or externalize the minimal necessary state to a shared store (Redis/Infinispan) so app instances are interchangeable and disposable — which is precisely what cloud-native scaling, autoscaling, and zero-downtime rolling deploys want. When you *do* need server sessions (stateful legacy apps, JSF back-office tools), prefer **buddy/pair replication or an external grid over full replication**, keep sessions **small and serializable**, and combine with **sticky sessions** so most requests hit the node that already has the session (replication then only matters for failover, not every request). The throughline: session replication is a valid tool for stateful clustered apps but it's a scalability and operational tax; the modern default is to design statelessness in (JWT + externalized state) so you never pay that tax, reserving replication for genuinely stateful applications that can't be redesigned.

#### Q87. [Practical] How do you secure and operate the MicroProfile Health and Metrics endpoints, and what mistakes expose you in production?

Health (`/health`, `/health/live`, `/health/ready`) and Metrics (`/metrics`) endpoints are operationally essential but introduce real exposure if deployed naively. The mistakes that bite teams:

1. **Leaking internal details to the public.** A health endpoint that returns rich diagnostic data (DB hostnames, dependency URLs, version/build info, stack traces, internal queue depths) hands an attacker a reconnaissance map. The `/metrics` endpoint similarly exposes internal topology, traffic patterns, and sometimes labels containing sensitive identifiers. Neither should be reachable by the public internet with full detail.
2. **Authentication that breaks the probes.** If you slap normal app authentication on `/health`, the orchestrator's probe (which doesn't authenticate) fails, and Kubernetes restarts your healthy pods — turning a security measure into an outage. The probe and the human/scraper consumer have different access needs.
3. **Expensive checks abused.** A readiness check that hits the DB on every call, exposed publicly, is a cheap DoS amplifier (and even legitimate frequent probing can load a heavy check).

The correct operational setup:

```
Network isolation: expose health/metrics on a SEPARATE port/management interface,
   reachable only from inside the cluster (kubelet, Prometheus) — NOT via the public ingress.
   (Quarkus: quarkus.management.enabled=true moves them to a management port;
    app servers expose a management interface separate from the app HTTP port.)
Probes: keep /health/live and /health/ready UNAUTHENTICATED but bound to the internal
   network only, and keep them CHEAP (liveness dependency-free; readiness light).
Metrics: scrape over the internal network; if exposed wider, require auth (mTLS / token)
   for the scraper, and avoid high-cardinality/sensitive labels.
Detail level: return minimal data publicly; rich diagnostics only on the internal interface.
```

The principle is **separation of planes**: the *data plane* (your app's public API, authenticated) and the *management plane* (health, metrics, admin) should be on different network interfaces with different access policies. Probes need to be reachable by the orchestrator without app auth, so you protect them with **network boundaries** (internal-only ports, NetworkPolicies, service mesh) rather than application authentication — that's how you get "the kubelet can probe, the internet cannot" without breaking the probe. Metrics scraping is similarly an internal concern between Prometheus and the pod. The senior framing: these endpoints are part of your **attack surface and your reliability surface simultaneously** — over-secure them (app auth on liveness) and you cause restart storms; under-secure them (public, verbose) and you leak internals and create DoS vectors. The right answer is network-level isolation of the management plane, minimal public detail, cheap dependency-free liveness, and authenticated/internal-only metrics — operating them as infrastructure endpoints, not application endpoints.

#### Q88. [Practical] How do you enable HTTP response compression and proper caching headers in a Jakarta EE app, and what are the security and correctness pitfalls?

These two HTTP optimizations cut bandwidth and latency substantially, but each has gotchas that turn a performance win into a bug or vulnerability. **Compression** (gzip/deflate/brotli) shrinks text responses (JSON, HTML, JS, CSS) often 70-90%, hugely reducing transfer time. **Caching headers** let browsers and CDNs avoid re-fetching unchanged resources entirely.

Compression is usually best done at the **reverse proxy / load balancer / CDN** (nginx `gzip on`, or the ingress) because it's centralized and offloads CPU from the app, but you can also do it in the container or a `WriterInterceptor`:

```java
@Provider
public class GzipInterceptor implements WriterInterceptor {
    @Override public void aroundWriteTo(WriterInterceptorContext ctx) throws IOException {
        // only if the client accepts gzip (check Accept-Encoding) and content is compressible
        ctx.getHeaders().putSingle("Content-Encoding", "gzip");
        ctx.setOutputStream(new GZIPOutputStream(ctx.getOutputStream()));
        ctx.proceed();
    }
}
```

Caching is controlled by `Cache-Control`, `ETag`, and `Last-Modified`; JAX-RS supports conditional requests so unchanged resources return `304 Not Modified` (no body):

```java
@GET @Path("/{id}")
public Response get(@PathParam("id") long id, @Context Request request) {
    Product p = service.find(id);
    EntityTag etag = new EntityTag(Integer.toHexString(p.hashCode()));
    Response.ResponseBuilder notModified = request.evaluatePreconditions(etag);
    if (notModified != null) return notModified.build();      // → 304, no body sent
    return Response.ok(p).tag(etag)
        .cacheControl(CacheControl.valueOf("max-age=60, private")).build();
}
```

The pitfalls a senior engineer must flag:

1. **BREACH/CRIME — compression + secrets + reflected input is a vulnerability.** Compressing a response that contains *both* a secret (CSRF token, session data) *and* attacker-influenced reflected content can leak the secret via response-size analysis. The mitigation is to not compress responses mixing secrets with reflected input, use CSRF tokens that change per request, and rely on TLS — but the existence of this class is why you should be deliberate about compressing sensitive dynamic responses.
2. **Caching authenticated/personalized responses publicly.** Marking a per-user response `Cache-Control: public` lets a shared cache/CDN serve *user A's* data to *user B*. Personalized responses must be `private` (or `no-store`), and you must `Vary` on `Authorization`/`Cookie` so caches key correctly. Getting this wrong is a serious data-leak bug.
3. **Stale data / cache invalidation.** Aggressive `max-age` on data that changes makes users see stale content; use short TTLs + `ETag` revalidation for mutable data, long `max-age` + content-hashed URLs for immutable static assets.
4. **`Vary` correctness.** If you compress conditionally on `Accept-Encoding`, you must send `Vary: Accept-Encoding` or a cache may serve a gzipped body to a client that can't decode it.

The senior framing: compression and caching are high-value but they interact with **security (BREACH, cache poisoning, leaking personalized data) and correctness (staleness, `Vary`)**, so the rules are: do them at the edge/CDN when you can, mark personalized responses `private`/`no-store` and set `Vary` correctly, use `ETag`/`304` for mutable data and immutable content-hashed URLs for static assets, and be cautious compressing responses that mix secrets with reflected input. They're not "just turn it on" toggles — each has a failure mode that's a production incident.

#### Q89. [Coding] Show a connection/resource leak that survives code review, and the patterns that prevent it.

Resource leaks are insidious because the leaking code often *looks* correct and works fine in testing — the leak only manifests under load or on the error path, surfacing later as pool exhaustion or `OutOfMemoryError`. The classic leak is closing a resource in the happy path but not when an exception is thrown before `close()`:

```java
// LEAKS on any exception between getConnection() and close()
public List<Order> findAll() throws SQLException {
    Connection c = dataSource.getConnection();      // borrowed from pool
    PreparedStatement ps = c.prepareStatement("SELECT * FROM orders");
    ResultSet rs = ps.executeQuery();                // if THIS throws, close() never runs
    List<Order> orders = map(rs);
    rs.close(); ps.close(); c.close();               // only reached on success → LEAK on error
    return orders;
}
```

Under load with intermittent failures, each error path leaks a connection that's never returned to the pool; after `maxPoolSize` leaks the pool is exhausted and the whole app stalls — and because it only leaks on errors, it passes happy-path tests and code review. The fix is **try-with-resources** (Java 7+), which guarantees `close()` runs even on exception, in reverse order of acquisition:

```java
// CORRECT — try-with-resources closes everything, even on exception, in reverse order
public List<Order> findAll() throws SQLException {
    try (Connection c = dataSource.getConnection();
         PreparedStatement ps = c.prepareStatement("SELECT * FROM orders");
         ResultSet rs = ps.executeQuery()) {
        return map(rs);
    }   // c, ps, rs all closed automatically here — leak-proof
}
```

The general patterns that prevent the whole class of leak: (1) **try-with-resources for every `AutoCloseable`** (connections, statements, result sets, input/output streams, `JMSContext`, files) — never rely on a manual `close()` in the happy path. (2) **Prefer the container-managed abstraction** so you don't handle raw resources at all: an injected `@PersistenceContext EntityManager` (the container manages the connection and closes it at transaction end) or a JAX-RS/CDI-managed flow means there's no `Connection` for *you* to leak. (3) **Enable pool leak detection** (`leakDetectionThreshold`) so a leak that does slip through logs a stack trace pointing at the offending borrow site rather than silently exhausting the pool. (4) For library code that *returns* a resource the caller must close, document it and return an `AutoCloseable` so the caller can use try-with-resources.

The subtle leaks that still survive even careful review: holding a `Stream` from a JPA query without closing it (Hibernate's `Stream` holds a result set / connection until closed — must be in try-with-resources), and registering a resource with a listener/cache but never deregistering (the redeploy/Metaspace leak family). The senior throughline: a resource leak is a correctness bug that hides on the error path and only becomes visible as exhaustion under load — so the defense is *structural* (try-with-resources makes the close non-optional and the code can't compile-time forget it) plus *detective* (pool leak detection catches what slips through), and the best move of all is to use container-managed resources so the lifecycle isn't your code's responsibility in the first place.

#### Q90. [Practical] How do you configure and tune a MicroProfile Rest Client for production (timeouts, connection reuse, propagation), and what defaults bite you?

The MicroProfile Rest Client gives you a type-safe declarative HTTP client (define an interface with JAX-RS annotations, the runtime generates the implementation), which is clean — but the *defaults* are tuned for convenience, not production, and several will hurt you under load.

```java
@RegisterRestClient(configKey = "inventory")
@Path("/inventory")
public interface InventoryClient {
    @GET @Path("/{sku}") Stock getStock(@PathParam("sku") String sku);
}
```

```properties
# Externalized config (MicroProfile Config) — set these explicitly, don't trust defaults:
inventory/mp-rest/url=https://inventory.internal
inventory/mp-rest/connectTimeout=2000      # ms — fail fast if can't connect
inventory/mp-rest/readTimeout=3000         # ms — THE critical one (see below)
inventory/mp-rest/scope=jakarta.enterprise.context.ApplicationScoped
```

The defaults that bite, in order of severity:

1. **No read timeout by default.** Many Rest Client implementations default to *infinite* read timeout — so if the downstream hangs, your request thread (and any DB connection/transaction it holds) hangs *forever*, and under load every worker ends up stuck on the slow dependency: the cascading thread-exhaustion stall. **Always set `connectTimeout` and `readTimeout` explicitly.** This is the single most important production setting.
2. **Connection pooling / reuse.** Make sure the underlying HTTP client **reuses connections (keep-alive) and pools them** rather than opening a new TCP+TLS connection per call — TLS handshakes per request are a massive latency and CPU cost. Verify the client's connection-pool size and that it's not creating a new client per request (use `@ApplicationScoped` or `@RegisterRestClient` so the client is reused, not rebuilt per call).
3. **No resilience by default.** The Rest Client doesn't retry, circuit-break, or bulkhead on its own — combine it with **MicroProfile Fault Tolerance** (`@Timeout`, `@Retry`, `@CircuitBreaker`, `@Bulkhead`, `@Fallback`) on the calling method so a slow/failing downstream degrades gracefully instead of cascading.
4. **Header/context propagation.** By default it won't forward your auth token or trace context. Register a **`ClientHeadersFactory`** (or `@RegisterClientHeaders`) to propagate the `Authorization` (JWT) and `traceparent` headers, or downstream calls run unauthenticated and your distributed traces fragment. MicroProfile JWT + the Rest Client integrate to propagate the caller's token.

```java
@Timeout(2000) @Retry(maxRetries = 2) @CircuitBreaker(failureRatio = 0.5, requestVolumeThreshold = 10)
@Fallback(fallbackMethod = "cached")
public Stock stock(String sku) { return client.getStock(sku); }   // resilient wrapper
```

The senior framing: the Rest Client's ergonomics make it easy to forget it's a *network call to an independently-failing system*, and the dangerous defaults (infinite read timeout, no resilience, no propagation) reflect that. Production-grade usage means **explicit timeouts (always)**, **connection reuse/pooling** (don't pay TLS per call, don't build a client per request), **fault-tolerance wrappers** for graceful degradation, and **context/auth propagation** so security and tracing survive the hop. Treat every Rest Client interface as a potential failure and latency injection point and configure it accordingly — the same discipline you'd apply to a JDBC pool, because a synchronous downstream call holding a thread is exactly as dangerous as a slow query holding a connection.

#### Q91. [Practical] How do you correctly map enums, dates/times, and money in JPA entities, and what are the silent data-corruption traps in each?

These three everyday types have JPA mapping defaults that are wrong often enough to cause real production data corruption, and a senior engineer should map them deliberately rather than accept defaults.

**Enums — never use `EnumType.ORDINAL`.** The default for `@Enumerated` is `ORDINAL`, which stores the enum's *position* (0, 1, 2...) as an integer. The trap: if anyone **reorders the enum constants or inserts one in the middle**, every previously stored value now means a *different* enum constant — silent, catastrophic data corruption with no error. Always use `STRING`:

```java
@Enumerated(EnumType.STRING)        // stores "SHIPPED", not a fragile ordinal
private OrderStatus status;          // safe to reorder/add constants; readable in the DB
```
`STRING` costs a few bytes and breaks if you *rename* a constant (rename = a data migration), but it's vastly safer than `ORDINAL` where a harmless-looking source reorder corrupts data. (A `@Converter` to an explicit stable code is the most robust option for long-lived schemas.)

**Dates/times — use `java.time`, store instants in UTC, and beware `TIMESTAMP WITHOUT TIME ZONE`.** Modern JPA maps `java.time` types natively, so use `Instant`/`LocalDate`/`OffsetDateTime` rather than legacy `java.util.Date`/`Calendar`. The silent corruption here is **timezone**: storing a `LocalDateTime` (no zone) into a `TIMESTAMP WITHOUT TIME ZONE` column means the value's meaning depends on the JVM/DB session timezone, so the same instant read on a server in a different zone (or after a DST change) comes back shifted by hours. The discipline: store **`Instant` (UTC)** in a timestamp column for points in time, convert to the user's zone only at the presentation layer, and pin the JVM/connection timezone (e.g., `-Duser.timezone=UTC`, `hibernate.jdbc.time_zone=UTC`) so reads and writes agree.

```java
@Column private Instant createdAt;   // UTC instant — unambiguous across servers/zones
@Column private LocalDate birthDate; // a calendar date with NO time/zone — correct for "a date"
```

**Money — `BigDecimal` with explicit precision/scale, never `double`/`float`.** Floating-point types cannot represent decimal fractions exactly (`0.1 + 0.2 != 0.3`), so storing money as `double` accumulates rounding errors that become accounting discrepancies — a guaranteed audit failure. Use `BigDecimal` mapped to a fixed-precision `DECIMAL/NUMERIC` column, and specify `precision`/`scale` so the DDL and rounding are explicit:

```java
@Column(precision = 19, scale = 4)   // DECIMAL(19,4) — exact, no binary-float rounding
private BigDecimal amount;            // and define rounding mode in your arithmetic
```

The throughline across all three: JPA's *defaults* optimize for "it compiles and stores something," not for correctness over a schema's lifetime — and each default has a failure mode that is **silent** (no exception, just wrong data): ordinal enums corrupt on reorder, naive date mapping shifts across timezones/DST, and float money drifts by rounding. The senior habit is to map these deliberately — `EnumType.STRING` (or a converter), `Instant`/UTC for instants with pinned DB timezone, and `BigDecimal` with explicit precision for money — because the cost of getting them wrong isn't a crash you'll catch in testing; it's corrupted data you discover in an audit months later.

#### Q92. [Practical] After a deployment, the app fails to start with `Unsatisfied dependencies`/`Ambiguous dependencies` CDI errors. How do you diagnose and resolve them quickly?

CDI deployment failures are deploy-time (good — they fail fast rather than at runtime) but the error messages can be cryptic, and there's a systematic way to read them. The two dominant errors are **`UnsatisfiedResolutionException`** ("no bean matches the injection point") and **`AmbiguousResolutionException`** ("more than one bean matches"), and each has a small, finite set of causes.

**`Unsatisfied dependency` — CDI found zero beans for an injection point.** Walk these causes in order:

1. **Missing bean-defining annotation (most common).** The target class is a plain POJO with no scope, and the archive is in the default `annotated` discovery mode, so CDI doesn't manage it (the bean-discovery question). Fix: add a scope (`@ApplicationScoped`) or a `@Produces` method.
2. **Missing or `none`-mode `beans.xml`.** A library jar without a `beans.xml` (and no annotated beans) isn't a bean archive; add an empty `beans.xml` or annotate its beans.
3. **Injecting an interface with no implementation bean**, or the implementation isn't on the classpath / wasn't scanned.
4. **Qualifier mismatch** — you injected `@Inject @Reporting DataSource` but no bean carries the `@Reporting` qualifier.
5. **Scope of the producer vs the consumer**, or `@Vetoed`/an extension removed the bean.

**`Ambiguous dependency` — CDI found two or more beans and can't pick.** Causes:

1. **Two implementations of the same interface** both eligible, with no qualifier to distinguish them. Fix: add a **qualifier** to each and inject with the qualifier, or mark one `@Default` and the others not, or make the alternatives `@Alternative` and enable exactly one.
2. **A duplicate bean** from the same class scanned twice (e.g., packaged in two jars on the classpath — a build/dependency problem).
3. **A producer and a managed bean** both providing the same type.

```java
// Resolve ambiguity with qualifiers:
@Qualifier @Retention(RUNTIME) @Target({FIELD, PARAMETER, METHOD})
public @interface Reporting {}

@ApplicationScoped @Reporting public class ReportingDataSourceProducer { ... }
@Inject @Reporting DataSource ds;       // now unambiguous

// Or select among alternatives in beans.xml:
//   @Alternative on the alt impls, then <alternatives><class>...</class></alternatives>
```

The fast diagnostic method: **read the exception — it names the exact injection point (class + field) and lists the candidate beans it found (for ambiguous) or says it found none (for unsatisfied).** That immediately tells you whether the problem is "too few" (discovery/annotation/qualifier missing) or "too many" (need a qualifier/alternative). For tricky cases, enable the CDI implementation's debug logging (Weld logs the full bean set and resolution decisions) or use `@Inject Instance<MyType>` + `isUnsatisfied()`/`isAmbiguous()` programmatically to probe. A frequent real-world cause of *ambiguous* errors specifically is **the same bean class appearing in two jars** (a dependency packaged both in `WEB-INF/lib` and a shared module — also the classloading `ClassCastException` cousin), so check your dependency tree (`mvn dependency:tree`) when a bean you only wrote once is reported as ambiguous.

The senior framing: CDI resolution is **type + qualifiers** by design, so every failure is either "the type/qualifier combo matches nothing" (fix discovery or add the bean/qualifier) or "matches more than one" (disambiguate with qualifiers or alternatives) — the error always tells you which, and the resolution is mechanical once you internalize that the container resolves by type-plus-qualifier, not by name. Because these fail at deploy time, they're far cheaper than the runtime nulls you'd get with manual wiring — the discipline is to read the candidate list in the message rather than guessing.

## 🧩 Extended Questions — Set 3: Coding, Design & Expert Scenarios

### 🟢 Basic — extended

#### Q93. [Coding] Write a JAX-RS `ExceptionMapper` that turns domain exceptions into a consistent JSON error body. Why is this better than letting exceptions bubble up?

Without an `ExceptionMapper`, an uncaught exception in a JAX-RS resource produces whatever the runtime defaults to — usually an HTML error page or a bare 500 with a leaked stack trace, and a different shape per error. That breaks clients (which must parse one consistent contract) and leaks internals. The fix is a CDI-discovered `@Provider` that maps each exception type to a status and a structured body.

```java
public record ApiError(String code, String message, String traceId) {}

@Provider
public class AppExceptionMapper implements ExceptionMapper<RuntimeException> {

    @Context UriInfo uriInfo;

    @Override
    public Response toResponse(RuntimeException ex) {
        if (ex instanceof EntityNotFoundException) {
            return build(Response.Status.NOT_FOUND, "not_found", ex.getMessage());
        }
        if (ex instanceof IllegalArgumentException) {
            return build(Response.Status.BAD_REQUEST, "invalid_request", ex.getMessage());
        }
        // Unknown: log the real detail server-side, return a sanitized body
        String traceId = UUID.randomUUID().toString();
        Logger.getLogger("api").log(Level.SEVERE, "unhandled @ " + uriInfo.getPath()
                + " traceId=" + traceId, ex);
        return build(Response.Status.INTERNAL_SERVER_ERROR, "internal_error",
                     "An unexpected error occurred");
    }

    private Response build(Response.Status status, String code, String msg) {
        return Response.status(status)
                       .type(MediaType.APPLICATION_JSON)
                       .entity(new ApiError(code, msg, MDC.get("traceId")))
                       .build();
    }
}
```

The mapper centralizes the error contract: one place defines status codes, machine-readable `code` fields, and what is safe to expose. A subtlety interviewers like: JAX-RS picks the **most specific** mapper by exception type, so you can have a generic `Throwable` mapper plus narrower ones, and the runtime resolves the closest supertype. Note also that mappers do **not** fire for exceptions thrown after the response has started streaming, and a `WebApplicationException` you throw with an explicit `Response` bypasses mapping entirely — so reserve `WebApplicationException` for cases where you already know the exact response.

#### Q94. [Coding] Implement a CDI `@Produces` factory method to expose a configured third-party client (e.g., an HTTP or object-mapper instance) as an injectable bean. Why not just `new` it everywhere?

A `@Produces` method is the CDI-idiomatic way to make a non-CDI object injectable. You can't annotate a library class you don't own with `@ApplicationScoped`, and `new`-ing it at every call site duplicates configuration, defeats pooling/reuse, and makes the object impossible to mock in tests. A producer turns construction into a single, scoped, injectable definition.

```java
@ApplicationScoped
public class JsonConfig {

    @Produces
    @ApplicationScoped                 // one shared, thread-safe instance
    public ObjectMapper objectMapper() {
        return JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
    }

    @Produces
    @ApplicationScoped
    public HttpClient httpClient() {        // expensive: holds a connection pool
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    // If the produced type needs cleanup, pair with a disposer:
    public void closeClient(@Disposes HttpClient client) {
        // HttpClient (JDK) has no close pre-21; for AutoCloseable resources:
        // ((AutoCloseable) client).close();
    }
}

// Anywhere:
@Inject ObjectMapper mapper;
@Inject HttpClient http;
```

The producer's scope controls the lifecycle of the produced object: `@ApplicationScoped` gives you a singleton-style shared instance (correct for a thread-safe `ObjectMapper` or a pooled `HttpClient`), whereas `@RequestScoped` or `@Dependent` would give per-request/per-injection instances. The key design win is **inversion of configuration**: the client is configured once, in one place, and every consumer gets the same correctly-tuned instance — and in tests you replace it with `@Alternative` or a producer in a test bean archive. The `@Disposes` parameter gives deterministic cleanup when the producing context ends, which plain `new` can never provide.

#### Q95. [Coding] Implement a request-scoped CDI bean that captures the authenticated user and trace ID, and inject it into both a JAX-RS resource and a service. Why is request scope the right tool here?

Per-request contextual data — the caller principal, a generated trace ID, tenant ID — should not be threaded through every method signature, nor stored in a `ThreadLocal` you manage by hand (which leaks on pooled threads if you forget to clear it). A `@RequestScoped` CDI bean is exactly this: the container creates one instance per HTTP request, makes it injectable everywhere, and destroys it (and any `@PreDestroy` cleanup) when the request ends.

```java
@RequestScoped
public class RequestContext {
    private String traceId;
    private String userId;

    @PostConstruct
    void init() { this.traceId = UUID.randomUUID().toString(); }

    public String traceId() { return traceId; }
    public String userId()  { return userId;  }
    public void setUserId(String id) { this.userId = id; }
}

// Populate it once, early, in a JAX-RS request filter:
@Provider @Priority(Priorities.AUTHENTICATION)
public class ContextFilter implements ContainerRequestFilter {
    @Inject RequestContext ctx;
    @Override public void filter(ContainerRequestContext rc) {
        ctx.setUserId(rc.getSecurityContext().getUserPrincipal() == null
                ? "anonymous"
                : rc.getSecurityContext().getUserPrincipal().getName());
        rc.getHeaders().add("X-Trace-Id", ctx.traceId());
    }
}

// Consume it deep in the stack without passing it around:
@ApplicationScoped
public class AuditService {
    @Inject RequestContext ctx;            // proxy: resolves the current request's instance
    public void record(String action) {
        log.info("user={} trace={} action={}", ctx.userId(), ctx.traceId(), action);
    }
}
```

The reason this works even though `AuditService` is `@ApplicationScoped` (a singleton) is the **client proxy**: the injected `RequestContext` is a proxy that, on every call, resolves the instance bound to the *current* request thread. So one singleton service safely sees the right per-request data for whichever request is calling. Compared to a hand-rolled `ThreadLocal`, the container guarantees creation and teardown around the request boundary, which is what prevents the classic "stale user leaks into the next request on a reused pool thread" bug.

### 🟡 Intermediate — extended

#### Q96. [Coding] Implement a CDI interceptor that caches method results, including the binding annotation, the interceptor, and `@Priority` wiring. What are the correctness traps?

A caching interceptor is a canonical example of cross-cutting behavior. You need three pieces: an `@InterceptorBinding` annotation, the `@Interceptor` class itself, and either a `beans.xml` `<interceptors>` entry or a `@Priority` to enable it.

```java
// 1. The binding
@InterceptorBinding
@Retention(RUNTIME) @Target({METHOD, TYPE})
public @interface Cached {
    @Nonbinding long ttlSeconds() default 60;   // @Nonbinding: not part of binding resolution
}

// 2. The interceptor
@Cached @Interceptor
@Priority(Interceptor.Priority.APPLICATION + 10)   // enables it; lower runs earlier
public class CachingInterceptor {
    private final Map<Object, Entry> cache = new ConcurrentHashMap<>();
    private record Entry(Object value, long expiresAt) {}

    @AroundInvoke
    public Object cache(InvocationContext ctx) throws Exception {
        Object key = List.of(ctx.getMethod().getName(), Arrays.asList(ctx.getParameters()));
        long ttl = ctx.getMethod().getAnnotation(Cached.class).ttlSeconds();
        Entry e = cache.get(key);
        long now = System.currentTimeMillis();
        if (e != null && e.expiresAt() > now) return e.value();
        Object result = ctx.proceed();                       // real method
        cache.put(key, new Entry(result, now + ttl * 1000));
        return result;
    }
}

// 3. Use it
@ApplicationScoped
public class RateService {
    @Cached(ttlSeconds = 30)
    public BigDecimal fxRate(String from, String to) { /* expensive lookup */ }
}
```

The traps are what separate a junior from a senior answer. **Self-invocation**: if `RateService` calls its own `fxRate` from another method via `this.fxRate(...)`, the interceptor is bypassed — interception only happens through the CDI proxy, so the call must come from an injected reference. **Key construction**: using `Object[]` parameters directly as a map key won't work (array identity, not value equality) — wrap in a `List`. **`@Nonbinding`**: without it, `@Cached(ttlSeconds=30)` and `@Cached(ttlSeconds=60)` would be treated as *different* bindings, and the interceptor might not match; `@Nonbinding` excludes the attribute from binding resolution while still being readable at runtime. **Thread safety and unbounded growth**: a real cache needs a size bound/eviction (Caffeine) and must consider whether caching `null`/exceptions is correct. Interception cost is also non-trivial — every intercepted call goes through reflection, so don't annotate hot trivial methods.

#### Q97. [Coding] Design and implement a multi-tenant data-access layer in JPA using Hibernate filters or a tenant discriminator. Walk through the trade-offs of the isolation strategies.

Multi-tenancy has three classic database strategies, and the choice drives the code. **Separate database per tenant** (strongest isolation, highest operational cost), **separate schema per tenant** (good isolation, schema sprawl), and **shared schema with a discriminator column** (cheapest, weakest isolation, requires bulletproof filtering). For most SaaS, shared-schema with a discriminator is the default — and the implementation must guarantee no query can ever omit the tenant predicate.

```java
@Entity
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = String.class))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class Invoice {
    @Id Long id;
    @Column(name = "tenant_id", nullable = false, updatable = false) String tenantId;
    BigDecimal amount;
}

@RequestScoped
public class TenantAwareEm {
    @PersistenceContext EntityManager em;
    @Inject RequestContext ctx;             // holds current tenantId (from JWT/header)

    @PostConstruct void enableFilter() {
        em.unwrap(Session.class)
          .enableFilter("tenantFilter")
          .setParameter("tenantId", ctx.tenantId());
    }

    public List<Invoice> invoices() {
        // The Hibernate filter auto-appends "AND tenant_id = ?" to the SQL
        return em.createQuery("SELECT i FROM Invoice i", Invoice.class).getResultList();
    }
}
```

Hibernate also offers **native multi-tenancy** via a `CurrentTenantIdentifierResolver` + `MultiTenantConnectionProvider`, which is the cleaner choice for database/schema-per-tenant because it swaps the connection rather than filtering rows. The discriminator-filter approach above is for shared-schema. The critical correctness points: the filter does **not** apply to `em.find()` by primary key (you can still load another tenant's row by ID — so enforce a tenant check there too, or use the resolver approach), `@Version`/native queries bypass it, and writes must set `tenant_id` from the context, never from client input (or a tenant can write into another's partition). The senior framing is that shared-schema multi-tenancy trades cost for a permanent, high-stakes invariant ("the tenant predicate is never missing") that you should enforce in *one* place and back with row-level security in the database as defense in depth.

#### Q98. [Coding] Implement an idempotent JAX-RS endpoint using an `Idempotency-Key` header so retried POSTs don't double-create. Design the storage and concurrency.

POST is not idempotent by HTTP semantics, but networks force clients to retry, and a retried "create order" must not create two orders. The industry pattern is a client-supplied `Idempotency-Key`: the server stores the key with the result of the first successful execution and replays that result for any later request with the same key.

```java
@Path("/orders")
public class OrderResource {
    @Inject OrderService orders;
    @PersistenceContext EntityManager em;

    @POST
    @Transactional
    public Response create(@HeaderParam("Idempotency-Key") String key,
                           @Valid CreateOrder cmd) {
        if (key == null || key.isBlank())
            return Response.status(400).entity("Idempotency-Key required").build();

        // Try to claim the key atomically via a UNIQUE constraint
        IdempotencyRecord rec = new IdempotencyRecord(key, "IN_PROGRESS");
        try {
            em.persist(rec);
            em.flush();                       // forces the INSERT -> may throw on dup key
        } catch (PersistenceException dup) {  // key already exists
            IdempotencyRecord existing = em.find(IdempotencyRecord.class, key);
            if ("IN_PROGRESS".equals(existing.status))
                return Response.status(409).entity("retry in progress").build();
            return Response.status(existing.httpStatus)   // replay stored result
                           .entity(existing.responseBody).build();
        }

        Order created = orders.create(cmd);  // real work, same transaction
        rec.status = "DONE";
        rec.httpStatus = 201;
        rec.responseBody = toJson(created);
        return Response.created(URI.create("/orders/" + created.getId()))
                       .entity(created).build();
    }
}
```

The design hinges on a **UNIQUE constraint on the key column** doing the concurrency control: two simultaneous retries race to `INSERT`, exactly one wins, the loser sees the constraint violation and either waits/409s or replays. Storing the response body lets you return the *same* representation on replay (important if the client needs the generated ID). Trade-offs: keys need a TTL/cleanup job or the table grows forever; the stored response can get large; and you must decide scope — keys are usually scoped per-endpoint and per-user to prevent collisions. An alternative is doing the claim in a separate `REQUIRES_NEW` transaction so an `IN_PROGRESS` marker survives even if the main work rolls back, but that adds the complexity of cleaning up stale markers. The principle: idempotency keys turn an at-least-once delivery channel into effectively-once *processing*, which is what clients actually need.

#### Q99. [Coding] Write a parameterized JPA Criteria query that builds a dynamic search filter safely. Why use the Criteria API over string-concatenated JPQL?

When a search endpoint has optional filters (name, status, date range), naive code concatenates JPQL strings — which is verbose, error-prone with `WHERE`/`AND` glue, and a SQL/JPQL-injection risk if any fragment includes user input. The Criteria API builds the query as a typed object graph: predicates are composed programmatically, parameters are always bound, and the metamodel gives compile-time checking of attribute names.

```java
public List<Customer> search(String nameLike, Status status,
                             LocalDate from, LocalDate to) {
    CriteriaBuilder cb = em.getCriteriaBuilder();
    CriteriaQuery<Customer> q = cb.createQuery(Customer.class);
    Root<Customer> c = q.from(Customer.class);

    List<Predicate> predicates = new ArrayList<>();
    if (nameLike != null && !nameLike.isBlank())
        predicates.add(cb.like(cb.lower(c.get("name")),
                               "%" + nameLike.toLowerCase() + "%"));   // bound param
    if (status != null)
        predicates.add(cb.equal(c.get("status"), status));
    if (from != null)
        predicates.add(cb.greaterThanOrEqualTo(c.get("createdAt"), from));
    if (to != null)
        predicates.add(cb.lessThanOrEqualTo(c.get("createdAt"), to));

    q.select(c)
     .where(cb.and(predicates.toArray(Predicate[]::new)))   // empty -> matches all
     .orderBy(cb.desc(c.get("createdAt")));

    return em.createQuery(q).setMaxResults(100).getResultList();
}
```

The decisive advantage is **safety and composability**: every value passed to `cb.like`/`cb.equal` becomes a bound parameter automatically, so there is no injection surface, and you assemble the `WHERE` clause as a list of predicates with no fragile string glue. Using the **static metamodel** (`Customer_.name` instead of `"name"`) upgrades attribute references to compile-time checks, so renaming a field breaks the build instead of failing at runtime. The trade-offs versus JPQL: Criteria is more verbose for static queries (use JPQL/`@NamedQuery` there), and complex Criteria can be hard to read. The right rule is JPQL for fixed queries, Criteria for **dynamic** query construction where the shape depends on runtime inputs — never string concatenation for the latter.

#### Q100. [Coding] Implement a Bean Validation cross-field constraint (e.g., "end date must be after start date") with a custom annotation and validator.

Single-field constraints (`@NotNull`, `@Size`) can't express relationships between fields. For "endDate after startDate" you write a **class-level** constraint: a custom annotation targeting the type, plus a `ConstraintValidator` that receives the whole object and reports the violation against the relevant property.

```java
@Target(TYPE) @Retention(RUNTIME)
@Constraint(validatedBy = ValidDateRangeValidator.class)
public @interface ValidDateRange {
    String message() default "endDate must be after startDate";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}

public class ValidDateRangeValidator
        implements ConstraintValidator<ValidDateRange, Booking> {
    @Override
    public boolean isValid(Booking b, ConstraintValidatorContext ctx) {
        if (b == null || b.getStart() == null || b.getEnd() == null)
            return true;                         // let @NotNull handle nulls
        boolean valid = b.getEnd().isAfter(b.getStart());
        if (!valid) {
            ctx.disableDefaultConstraintViolation();
            ctx.buildConstraintViolationWithTemplate(ctx.getDefaultConstraintMessageTemplate())
               .addPropertyNode("end")           // attach error to the 'end' field
               .addConstraintViolation();
        }
        return valid;
    }
}

@ValidDateRange
public class Booking {
    @NotNull private LocalDate start;
    @NotNull private LocalDate end;
    // getters...
}
```

Two design points interviewers look for. First, **separation of concerns**: the validator returns `true` for null fields and defers to `@NotNull`, so each constraint does one job and error messages don't double up. Second, **violation targeting**: by default the error attaches to the class; calling `addPropertyNode("end")` makes the message point at the field the UI should highlight. This constraint participates in the full Bean Validation machinery — it fires automatically on JAX-RS `@Valid` parameters, on JPA `@PrePersist`/`@PreUpdate`, and you can include it in validation **groups** (e.g., only on `Create`, not `Update`). The alternative — validating in the resource method by hand — scatters the rule, can't be reused across entry points, and won't integrate with the automatic validation lifecycle. Pushing it into a declarative constraint makes it composable and consistently enforced wherever the object is validated.

#### Q101. [Coding] Build a type-safe MicroProfile Rest Client interface to call a downstream service, including a custom header propagation and an exception-mapping `ResponseExceptionMapper`.

MicroProfile Rest Client lets you declare an HTTP client as an annotated Java interface — the runtime generates the implementation, handles (de)serialization, and integrates with CDI and Fault Tolerance. This replaces hand-written `HttpClient` plumbing with a contract that mirrors the server's JAX-RS resource.

```java
@Path("/inventory")
@RegisterRestClient(configKey = "inventory-api")   // base URL from config
@RegisterProvider(InventoryExceptionMapper.class)
public interface InventoryClient {

    @GET @Path("/{sku}")
    @Produces(MediaType.APPLICATION_JSON)
    @ClientHeaderParam(name = "X-Trace-Id", value = "{traceId}")  // computed per call
    Stock getStock(@PathParam("sku") String sku);

    default String traceId() { return MDC.get("traceId"); }
}

// Translate downstream HTTP errors into typed domain exceptions
public class InventoryExceptionMapper implements ResponseExceptionMapper<RuntimeException> {
    @Override public boolean handles(int status, MultivaluedMap<String,Object> h) {
        return status >= 400;
    }
    @Override public RuntimeException toThrowable(Response r) {
        return switch (r.getStatus()) {
            case 404 -> new SkuNotFoundException();
            case 429 -> new RateLimitedException();
            default  -> new UpstreamException("inventory " + r.getStatus());
        };
    }
}
```

```properties
# microprofile-config.properties
inventory-api/mp-rest/url=https://inventory.internal:8443
inventory-api/mp-rest/connectTimeout=2000
inventory-api/mp-rest/readTimeout=3000
```

```java
@ApplicationScoped
class InventoryGateway {
    @Inject @RestClient InventoryClient client;   // CDI injects the generated impl
    @Retry(maxRetries = 2) @CircuitBreaker
    Stock stock(String sku) { return client.getStock(sku); }
}
```

The win over a hand-rolled client is **the contract is the code**: the interface is the single source of truth for paths, verbs, and types, the base URL and timeouts are externalized to config (so dev/prod differ without recompiling), and the `ResponseExceptionMapper` converts opaque HTTP statuses into domain exceptions your callers can pattern-match on. Because the injected client is a CDI bean, you can layer Fault Tolerance annotations and OpenTelemetry on top, and you can register it as `@RegisterRestClient` *and* inject `@RestClient` for compile-checked wiring. The defaults that bite: timeouts default to "infinite" on some runtimes (always set `connectTimeout`/`readTimeout`), and the client does not automatically propagate the caller's auth token or trace headers unless you configure header propagation (`@ClientHeaderParam` or `org.eclipse.microprofile.rest.client.propagateHeaders`).

#### Q102. [Coding] Implement a JAX-RS `ContainerRequestFilter` that does token-based authentication and populates the `SecurityContext`, so `@RolesAllowed` works downstream.

When you authenticate with a bearer JWT rather than container BASIC/FORM auth, you bridge the token into the JAX-RS security model by replacing the `SecurityContext` in a pre-matching filter. After that, declarative `@RolesAllowed` and `securityContext.isUserInRole(...)` work as if the container authenticated the user.

```java
@Provider
@Priority(Priorities.AUTHENTICATION)
public class JwtAuthFilter implements ContainerRequestFilter {

    @Inject TokenVerifier verifier;   // validates signature, exp, issuer

    @Override
    public void filter(ContainerRequestContext rc) {
        String auth = rc.getHeaderString(HttpHeaders.AUTHORIZATION);
        if (auth == null || !auth.startsWith("Bearer ")) {
            rc.abortWith(Response.status(401).build());
            return;
        }
        try {
            Claims claims = verifier.verify(auth.substring(7));
            Principal principal = claims::getSubject;
            Set<String> roles = claims.getRoles();
            boolean secure = rc.getUriInfo().getBaseUri().getScheme().equals("https");

            rc.setSecurityContext(new SecurityContext() {
                public Principal getUserPrincipal() { return principal; }
                public boolean isUserInRole(String r) { return roles.contains(r); }
                public boolean isSecure() { return secure; }
                public String getAuthenticationScheme() { return "Bearer"; }
            });
        } catch (TokenException e) {
            rc.abortWith(Response.status(401).build());
        }
    }
}

@Path("/admin")
public class AdminResource {
    @GET @RolesAllowed("admin")          // enforced by the container against our SecurityContext
    public List<Audit> audits() { ... }
}
```

The architecture point: by replacing the `SecurityContext` in an **AUTHENTICATION-priority** filter (which runs before authorization), you reuse the standard authorization machinery instead of scattering `if (role == ...)` checks across resources. `@RolesAllowed` is enforced by a JAX-RS interceptor that consults exactly the `SecurityContext` you installed. `abortWith` short-circuits the chain so an invalid token never reaches the resource. The senior caveats: validate signature **and** standard claims (`exp`, `iss`, `aud`), not just decode the token; this filter handles authentication and *role-based* authorization, but **object-level** authorization (can this user see *this* order?) still belongs in the service layer; and for a fully standard approach, Jakarta Security's `HttpAuthenticationMechanism` plus MicroProfile JWT does the same thing with less custom code — worth mentioning as the spec-blessed alternative.

#### Q103. [Coding] Implement Server-Sent Events (SSE) with JAX-RS to stream live updates to a browser, and explain when SSE beats WebSocket.

SSE is a one-way, server-to-client stream over a long-lived HTTP response with `text/event-stream`. JAX-RS supports it natively via `SseEventSink` and `SseBroadcaster`, which is ideal for pushing notifications, progress, or live metrics to many clients without the bidirectional complexity of WebSocket.

```java
@Path("/events")
@ApplicationScoped
public class EventResource {

    private SseBroadcaster broadcaster;
    @Context Sse sse;

    @PostConstruct
    void init() { /* broadcaster created lazily on first subscribe */ }

    @GET @Path("/subscribe")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public void subscribe(@Context SseEventSink sink) {
        if (broadcaster == null) broadcaster = sse.newBroadcaster();
        broadcaster.register(sink);            // sink auto-removed on client disconnect
    }

    // Called from anywhere (e.g., a CDI event observer) to fan out an update
    public void publish(String type, String data) {
        if (broadcaster == null) return;
        broadcaster.broadcast(
            sse.newEventBuilder().name(type).data(String.class, data).build());
    }
}
```

```javascript
// Browser — native EventSource, no library needed
const es = new EventSource('/api/events/subscribe');
es.addEventListener('order-shipped', e => console.log(JSON.parse(e.data)));
```

SSE wins when the data flow is **server-to-client only**: dashboards, notification feeds, build/job progress, live scores. It rides on plain HTTP so it traverses proxies and corporate firewalls cleanly, auto-reconnects in the browser (`EventSource` retries and supports `Last-Event-ID` for resuming), and reuses your existing auth/headers. **WebSocket** is the right call when you need true bidirectional, low-latency messaging (chat, collaborative editing, gaming) where the client also pushes frequently. The operational caveats for SSE: each subscriber holds a connection for its lifetime, so on a thread-per-request server this can pin worker threads (use async I/O / virtual threads, and cap concurrent subscribers); HTTP/1.1 limits a browser to ~6 connections per origin (HTTP/2 multiplexes and removes this); and you must broadcast on managed threads and handle the broadcaster being shared application-scoped state.

### 🟠 Advanced — extended

#### Q104. [Coding] Implement the transactional Outbox pattern end-to-end in Jakarta EE so a DB write and an event publish are atomic without XA.

The Outbox pattern solves the dual-write problem: you must update your DB *and* publish an event, but doing both in separate transactions risks one succeeding and the other failing. Instead of XA across the DB and broker, you write the event into an `outbox` table **in the same local DB transaction** as the business change, then a separate relay reads unpublished rows and sends them, marking them done. The DB write is atomic; publishing becomes at-least-once with idempotent consumers.

```java
@Entity @Table(name = "outbox")
public class OutboxEvent {
    @Id @GeneratedValue Long id;
    String aggregateId;
    String type;
    @Column(columnDefinition = "text") String payload;
    @Enumerated(EnumType.STRING) Status status = Status.PENDING;
    Instant createdAt = Instant.now();
    enum Status { PENDING, SENT }
}

@Stateless
public class OrderService {
    @PersistenceContext EntityManager em;

    @Transactional   // ONE local transaction covers both writes
    public Order placeOrder(CreateOrder cmd) {
        Order o = new Order(cmd);
        em.persist(o);
        em.persist(new OutboxEvent("Order", o.getId().toString(),
                                   "OrderPlaced", toJson(o)));  // same tx
        return o;
    }
}

@Singleton                       // EJB timer-driven relay
public class OutboxRelay {
    @PersistenceContext EntityManager em;
    @Inject JMSContext jms;
    @Resource(lookup = "java:/jms/topic/orders") Topic topic;

    @Schedule(second = "*/2", minute = "*", hour = "*", persistent = false)
    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public void flush() {
        List<OutboxEvent> batch = em.createQuery(
                "SELECT e FROM OutboxEvent e WHERE e.status = :s ORDER BY e.id",
                OutboxEvent.class)
            .setParameter("s", OutboxEvent.Status.PENDING)
            .setLockMode(LockModeType.PESSIMISTIC_WRITE)   // skip-locked in real DBs
            .setMaxResults(100).getResultList();
        for (OutboxEvent e : batch) {
            jms.createProducer().send(topic, e.getPayload());
            e.setStatus(OutboxEvent.Status.SENT);          // dirty-checked update
        }
    }
}
```

The correctness argument: because the business row and the outbox row are written in the **same** transaction, they commit or roll back together — there is no window where the order exists but the event was lost. The relay then guarantees *at-least-once* publication (a crash after send but before marking SENT means a duplicate), so consumers **must be idempotent** (dedupe on the event id). Real-world hardening: in a cluster, multiple relay instances would double-publish, so use `SELECT ... FOR UPDATE SKIP LOCKED` (or a leader election) so each row is claimed by one node; the `@Schedule` timer should be non-persistent and you may prefer change-data-capture (Debezium reading the outbox table from the WAL) to avoid polling latency. The trade-off versus XA is explicit: you give up exactly-once and synchronous atomicity across resources in exchange for no distributed-transaction coordinator, no in-doubt transactions, and far better availability and throughput — which is why outbox is the default for microservices.

#### Q105. [Coding] Implement a streaming JAX-RS endpoint that exports a million-row report as CSV without loading it all into memory. What are the JPA and HTTP pitfalls?

Building the whole CSV in a `String` or `List` will OOM at scale. The fix is to stream: use a JAX-RS `StreamingOutput`, fetch rows with a scrollable/streamed JPA result, and flush to the client as you go so memory stays bounded regardless of result size.

```java
@GET @Path("/report.csv")
@Produces("text/csv")
public Response export() {
    StreamingOutput body = out -> {
        try (var writer = new BufferedWriter(new OutputStreamWriter(out, UTF_8))) {
            writer.write("id,sku,amount\n");
            // Hibernate: stream() returns a Stream backed by a forward-only cursor
            try (Stream<Object[]> rows = em.createQuery(
                    "SELECT i.id, i.sku, i.amount FROM Invoice i", Object[].class)
                    .setHint(QueryHints.HINT_FETCH_SIZE, 1000)   // JDBC fetch size
                    .getResultStream()) {
                int n = 0;
                Iterator<Object[]> it = rows.iterator();
                while (it.hasNext()) {
                    Object[] r = it.next();
                    writer.write(r[0] + "," + r[1] + "," + r[2] + "\n");
                    if (++n % 1000 == 0) { writer.flush(); em.clear(); }  // detach to free L1
                }
            }
        }
    };
    return Response.ok(body)
                   .header("Content-Disposition", "attachment; filename=report.csv")
                   .build();
}
```

The JPA pitfalls are the heart of this question. `getResultList()` materializes everything; you need `getResultStream()` (Hibernate uses a server-side cursor) plus a **JDBC fetch size** hint, or the driver may still buffer the whole ResultSet (notoriously, the MySQL driver buffers unless `fetchSize = Integer.MIN_VALUE`/streaming is enabled). Critically, every row you touch becomes **managed in the L1 persistence context**, so without periodic `em.clear()` the persistence context itself grows to a million entities and OOMs even though you stream the output — selecting a projection (`Object[]`/DTO) instead of full entities avoids that entirely. The HTTP pitfalls: the work must run **inside the transaction/EntityManager scope** for the cursor to stay open while streaming (so don't close the EM before `StreamingOutput` runs — keep it transaction-scoped around the whole stream, or use a stateless approach that holds the connection), set `Content-Disposition` for download, and accept that you can't easily set `Content-Length` (chunked transfer) so error handling mid-stream means the client may receive a partial file — emit a trailer or status row if integrity matters. Streaming converts an O(N)-memory operation into O(1) memory at the cost of holding a DB connection for the export's duration, so cap concurrency.

#### Q106. [Coding] Write a CDI extension (portable extension / Build Compatible Extension) that auto-registers beans matching a convention. When is this justified?

CDI extensions hook into the container bootstrap to observe and modify the bean set programmatically — adding beans, vetoing classes, adding annotations, or validating wiring. They are how frameworks (Quarkus, Deltaspike, MicroProfile impls) integrate. The classic example: scan for classes implementing an SPI and register each as a bean with a qualifier, without per-class boilerplate.

```java
// CDI Full portable extension (javax/jakarta.enterprise.inject.spi.Extension)
public class MetricsExtension implements Extension {

    private final List<AnnotatedType<?>> collectors = new ArrayList<>();

    // Observe each type during scan; collect those implementing MetricCollector
    <T> void scan(@Observes ProcessAnnotatedType<T> pat) {
        if (MetricCollector.class.isAssignableFrom(pat.getAnnotatedType().getJavaClass())) {
            collectors.add(pat.getAnnotatedType());
            // could also add @ApplicationScoped via pat.configureAnnotatedType()
        }
    }

    // After validation, assert at least one collector exists (fail fast at deploy)
    void afterValidation(@Observes AfterDeploymentValidation adv) {
        if (collectors.isEmpty())
            adv.addDeploymentProblem(new IllegalStateException("no MetricCollector beans"));
    }
}
```

```
# META-INF/services/jakarta.enterprise.inject.spi.Extension
com.acme.MetricsExtension
```

The lifecycle events you hook are the key knowledge: `BeforeBeanDiscovery` (add synthetic types/qualifiers), `ProcessAnnotatedType` (modify/veto each scanned class — this is where you'd add annotations or `veto()` to suppress a bean), `ProcessInjectionPoint`/`ProcessBean` (inspect wiring), `AfterBeanDiscovery` (register fully synthetic beans via `addBean`), and `AfterDeploymentValidation` (fail the deploy with `addDeploymentProblem` if invariants are violated). Jakarta EE 10's CDI Lite introduced **Build Compatible Extensions** (a separate, build-time-friendly API using `@Discovery`/`@Enhancement`/`@Registration`/`@Synthesis` phases) precisely so extensions can run at build time for native image — Quarkus relies on this. When is an extension justified? Rarely in application code — most needs are met by producers, qualifiers, and `@Alternative`. Reach for an extension when you're building a **framework or library** that must integrate convention-based discovery, add cross-cutting behavior across many beans, or validate architectural rules at deploy time. The trade-off is real complexity and tight coupling to the CDI SPI, so the bar should be "I'm extending the platform," not "I want a bit less boilerplate."

#### Q107. [Coding] Design and implement a saga (orchestration style) for a multi-service booking workflow with compensating transactions. Contrast with choreography.

A saga maintains consistency across services without distributed transactions by breaking a workflow into a sequence of local transactions, each with a **compensating action** that semantically undoes it. In **orchestration**, a central coordinator drives the steps and triggers compensation on failure; in **choreography**, services react to each other's events with no central brain.

```java
// Orchestrator: book flight -> book hotel -> charge card; compensate in reverse on failure
@ApplicationScoped
public class TripBookingSaga {
    @Inject @RestClient FlightClient flights;
    @Inject @RestClient HotelClient  hotels;
    @Inject @RestClient PaymentClient payments;

    public TripResult book(TripRequest req) {
        Deque<Runnable> compensations = new ArrayDeque<>();
        try {
            String flightId = flights.reserve(req.flight());
            compensations.push(() -> flights.cancel(flightId));

            String hotelId = hotels.reserve(req.hotel());
            compensations.push(() -> hotels.cancel(hotelId));

            String payId = payments.charge(req.payment());
            compensations.push(() -> payments.refund(payId));

            return new TripResult(flightId, hotelId, payId);   // all committed
        } catch (RuntimeException step) {
            compensations.forEach(Runnable::run);   // LIFO: undo completed steps
            throw new SagaFailedException("trip booking rolled back", step);
        }
    }
}
```

Each step is its own committed local transaction in its own service, so there is no shared lock or 2PC. The defining requirement is that **compensations are semantic, not physical** — you can't "roll back" a charge that already settled, you issue a refund; you can't un-send an email, you send a correction. That means sagas only give **eventual consistency** and you must design for intermediate visible states (a flight briefly reserved then cancelled). Production hardening: every step and every compensation must be **idempotent** (compensation may be retried), the orchestrator's state must be **persisted** (so a crash mid-saga can resume — this is what Eclipse MicroProfile LRA / `@LRA` standardizes, or a state machine in the DB), and compensations that themselves fail need retry-with-alerting. **Orchestration vs choreography:** orchestration centralizes the flow (easy to reason about, monitor, and change; the coordinator is a coupling point and potential bottleneck) while choreography is fully decoupled via events (scales independently but the end-to-end flow is implicit and hard to debug — "no one owns the workflow"). The senior judgment is to prefer orchestration for complex, evolving workflows where observability matters, and choreography for simple, stable event reactions.

#### Q108. [Coding] Implement a graceful-shutdown sequence for a Jakarta EE / MicroProfile service so in-flight requests complete and resources close cleanly during a Kubernetes rolling update.

During a rolling update Kubernetes sends `SIGTERM` and removes the pod from the Service endpoints, but in-flight requests and async work can be killed mid-flight, causing 5xxs and partial writes. Graceful shutdown means: stop accepting new work, drain in-flight work within a deadline, then close resources in reverse order of acquisition.

```java
@ApplicationScoped
public class LifecycleManager {

    @Inject ManagedExecutorService executor;
    private final AtomicBoolean draining = new AtomicBoolean(false);

    // Readiness flips to false the moment we start draining
    @Produces @Readiness
    HealthCheck readiness() {
        return () -> draining.get()
            ? HealthCheckResponse.down("shutting-down")
            : HealthCheckResponse.up("ready");
    }

    // @Observes BeforeDestroyed(ApplicationScoped) fires on container shutdown
    void onShutdown(@Observes @BeforeDestroyed(ApplicationScoped.class) Object init) {
        draining.set(true);                 // 1. readiness=down -> k8s stops routing
        sleepQuietly(Duration.ofSeconds(5)); // 2. let LB/endpoints converge
        executor.shutdown();                 // 3. stop accepting new async tasks
        try {                                // 4. drain in-flight async work
            if (!executor.awaitTermination(20, TimeUnit.SECONDS))
                executor.shutdownNow();
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
```

The ordering is the insight. The race is between Kubernetes removing the pod from load-balancer rotation and the pod stopping its server — and these are **not** synchronized: the kubelet sends `SIGTERM` and updates endpoints concurrently, so if you stop the server immediately, the LB may still send you traffic for a second or two. The standard fix is to fail **readiness first** (so the endpoint controller deregisters you) and **sleep briefly** to let that propagate before tearing down, often combined with a `preStop` hook (`sleep 5`) in the pod spec. You also must set `terminationGracePeriodSeconds` larger than your drain deadline, or the kubelet sends `SIGKILL` and you lose in-flight work anyway. On the app server side, full-profile servers (WildFly suspend mode, Open Liberty `server stop --timeout`) implement request draining; you complement that with closing your own managed executors, JMS consumers (stop consuming, finish current message), and any held connections. The trade-off is shutdown latency (a few extra seconds per pod) for zero dropped requests during deploys — almost always worth it for user-facing services.

#### Q109. [Coding] Implement a database advisory/leader-election lock so a clustered Jakarta EE `@Schedule` job runs on exactly one node. Compare approaches.

A `@Schedule` timer fires on every node in a cluster, so a "send daily invoices" job runs N times unless you coordinate. The robust, dependency-light approach is a database-backed lease: each fire attempts to atomically claim a lock row whose lease has expired; only the winner runs.

```java
@Singleton
public class InvoiceJob {
    @PersistenceContext EntityManager em;

    @Schedule(hour = "2", minute = "0", persistent = false)
    public void run() {
        if (acquire("daily-invoices", Duration.ofMinutes(30))) {
            try { sendInvoices(); }
            finally { release("daily-invoices"); }
        }   // else: another node holds the lease, do nothing
    }

    @Transactional(TxType.REQUIRES_NEW)
    boolean acquire(String name, Duration lease) {
        // Atomic conditional UPDATE: claim only if free or expired
        int won = em.createNativeQuery("""
            UPDATE job_lock
               SET owner = :node, expires_at = :exp
             WHERE name = :name AND (owner IS NULL OR expires_at < :now)
            """)
            .setParameter("node", nodeId())
            .setParameter("exp", Instant.now().plus(lease))
            .setParameter("now", Instant.now())
            .setParameter("name", name)
            .executeUpdate();
        return won == 1;     // exactly one node's UPDATE matches
    }

    @Transactional(TxType.REQUIRES_NEW)
    void release(String name) {
        em.createNativeQuery("UPDATE job_lock SET owner=NULL WHERE name=:n AND owner=:o")
          .setParameter("n", name).setParameter("o", nodeId()).executeUpdate();
    }
}
```

The correctness lever is that a single conditional `UPDATE` is **atomic at the database** — exactly one concurrent transaction's `WHERE` clause matches a free/expired lock, so exactly one node gets `rowsAffected == 1`. The **lease (expiry)** is essential: if the winning node crashes before `release`, the lock would be held forever, so other nodes can reclaim it once `expires_at` passes (set the lease longer than the job's worst-case runtime, and ideally renew it for long jobs). Alternatives, ranked: (1) **Postgres advisory locks** / `SELECT ... FOR UPDATE SKIP LOCKED` — connection-scoped, auto-released on disconnect, no expiry bookkeeping, but tied to one DB; (2) the runtime's **clustered singleton/HA timer** (WildFly clustered EJB timer, Payara/Hazelcast distributed lock) — least code but couples you to the server; (3) an external coordinator (**ZooKeeper/etcd/Kubernetes Lease**) — best for many jobs and proper leader election but adds an operational dependency. The DB-lease approach is the pragmatic default because it needs no new infrastructure and survives node failure via expiry; the trade-off is you must reason carefully about lease duration vs. clock skew vs. job runtime.

#### Q110. [Coding] You must add audit columns (createdBy, createdAt, modifiedBy, modifiedAt) to every entity automatically. Implement it with JPA lifecycle callbacks / an `EntityListener`, and handle the "who" cleanly.

Manually setting audit fields in every service method is error-prone and forgettable. JPA's `@EntityListeners` with `@PrePersist`/`@PreUpdate` callbacks centralize this so the fields are stamped automatically whenever an entity is inserted or updated, regardless of which code path triggered it.

```java
@MappedSuperclass
@EntityListeners(AuditListener.class)
public abstract class Auditable {
    @Column(updatable = false) protected Instant createdAt;
    @Column(updatable = false) protected String  createdBy;
    protected Instant modifiedAt;
    protected String  modifiedBy;
    // getters/setters...
}

public class AuditListener {
    @PrePersist
    void onCreate(Auditable e) {
        Instant now = Instant.now();
        String who = currentUser();
        e.createdAt = now; e.createdBy = who;
        e.modifiedAt = now; e.modifiedBy = who;
    }
    @PreUpdate
    void onUpdate(Auditable e) {
        e.modifiedAt = Instant.now();
        e.modifiedBy = currentUser();
    }
    private String currentUser() {
        return CDI.current().select(RequestContext.class).get().userId();  // see Q95
    }
}

@Entity
public class Invoice extends Auditable { @Id Long id; BigDecimal amount; }
```

The design wins: the rule lives in one listener attached via a `@MappedSuperclass`, so every entity that extends `Auditable` is audited consistently and `@Column(updatable=false)` protects the creation fields from being overwritten on update. The genuinely tricky part is **"who"** — the listener has no method parameter for the current user, and an `EntityListener` instance is not a CDI bean by default, so you bridge to the request context via `CDI.current()` (or use Hibernate Envers, or Spring Data's `AuditorAware` equivalent). The caveats interviewers probe: lifecycle callbacks fire at **flush** time, not at the moment you call the setter, so the timestamp reflects flush ordering; callbacks must **not** call back into the `EntityManager` (no queries/`persist` inside `@PrePersist` — undefined behavior and risk of recursion); bulk JPQL `UPDATE`/`DELETE` and native SQL **bypass** these callbacks entirely (so a `UPDATE Invoice SET ...` won't stamp `modifiedAt` — a common silent gap); and if you need full change history (old/new values, not just last-modified), use **Hibernate Envers** (`@Audited`) which maintains versioned audit tables rather than overwriting columns.

#### Q111. [Behavioral] Tell me about a time a production incident traced back to a Jakarta EE design decision you made. How did you handle it and what changed afterward?

This question tests ownership, technical depth, and whether you turn incidents into systemic improvement — use STAR. **Situation:** "I designed an order-processing service where the JAX-RS resource called a downstream payment provider *inside* the JTA transaction, so the DB connection was held for the whole external HTTP call. It passed load tests, but in production a payment-provider slowdown caused our connection pool to starve — every worker was blocked in `getConnection`, and the whole service stalled, not just payments." **Task:** "I was on call and owned both the incident and the design that caused it. The immediate goal was to restore service; the real goal was to make this class of failure impossible."

**Action:** "First, mitigation under pressure — I confirmed the root cause from a thread dump (a wall of threads `WAITING` on the pool), temporarily raised the pool size to bleed off the backlog, and added a readiness-probe failure so Kubernetes stopped routing to saturated pods. Then the actual fix: I moved the external call **outside** the transaction (commit the order as PENDING locally, publish to an outbox, and confirm payment asynchronously), and added a `@Timeout` and `@CircuitBreaker` around the provider call so a slow dependency can't consume all workers. I also added a metric for pool wait time and an alert before saturation, not after." **Result:** "We recovered in about 25 minutes; the redesign eliminated the coupling so a provider outage now degrades only payment confirmation, not order intake. I wrote a blameless postmortem and turned the lesson — 'never hold a DB connection across a network call' — into a code-review checklist item and an architecture guideline the whole team adopted."

The qualities the interviewer is listening for: that you owned a mistake without deflecting, separated immediate mitigation from durable fix, used data (thread dump, metrics) rather than guessing, and converted a one-off incident into a reusable guardrail. A weak answer blames the dependency or stops at "we restarted it." A strong answer shows the senior instinct that **the design, not the dependency, was the bug** — and that resilience patterns plus organizational learning, not bigger pools, are the real remedy.

### 🔴 Expert — extended

#### Q112. [Coding] Implement a custom CDI `Context` and scope (e.g., a `@TenantScoped` or batch-job scope). What does the SPI require and where do teams get it wrong?

Beyond the built-in scopes, CDI lets you define your own by implementing the `Context` SPI and registering it via a portable extension. This is genuinely expert territory — frameworks do it (`@TransactionScoped`, MicroProfile's request-context bridging) but applications rarely should. A custom scope is justified when you have a well-defined unit of work that isn't request/session/application — for instance, beans whose lifecycle should follow a long-running batch job or a tenant activation.

```java
// 1. The scope annotation
@NormalScope(passivating = false) @Inherited
@Target({TYPE, METHOD, FIELD}) @Retention(RUNTIME)
public @interface JobScoped {}

// 2. The Context implementation: stores instances keyed by the active job
public class JobContext implements Context {
    private static final ThreadLocal<Map<Contextual<?>, Object>> ACTIVE =
            new ThreadLocal<>();

    public static void enter() { ACTIVE.set(new HashMap<>()); }
    public static void exit()  { ACTIVE.remove(); }   // must destroy beans + run @PreDestroy

    @Override public Class<? extends Annotation> getScope() { return JobScoped.class; }
    @Override public boolean isActive() { return ACTIVE.get() != null; }

    @Override @SuppressWarnings("unchecked")
    public <T> T get(Contextual<T> c, CreationalContext<T> cc) {
        Map<Contextual<?>, Object> store = require();
        return (T) store.computeIfAbsent(c, k -> c.create(cc));  // create lazily, cache
    }
    @Override public <T> T get(Contextual<T> c) {
        Map<Contextual<?>, Object> store = require();
        return (T) store.get(c);
    }
    private Map<Contextual<?>, Object> require() {
        Map<Contextual<?>, Object> s = ACTIVE.get();
        if (s == null) throw new ContextNotActiveException();
        return s;
    }
}

// 3. Register it from an extension
public class JobScopeExtension implements Extension {
    void register(@Observes AfterBeanDiscovery abd) { abd.addContext(new JobContext()); }
}
```

The SPI contract has sharp edges. A **normal scope** (`@NormalScope`) means beans are client-proxied, so `get(Contextual, CreationalContext)` must *create-if-absent and cache*, while `get(Contextual)` returns the existing instance or null — getting these two semantics wrong yields fresh instances per call or NPEs. `isActive()` must accurately reflect whether the scope is entered, or injection fails with `ContextNotActiveException` at the worst time. The mistake teams make most is **forgetting destruction**: when the scope ends you must iterate the stored beans and call `contextual.destroy(instance, creationalContext)` so `@PreDestroy` runs and the `CreationalContext` releases dependent beans — skipping this leaks memory and dependent-scoped sub-beans. The `ThreadLocal` approach also doesn't survive thread hops (async/executor boundaries), so for anything crossing threads you need explicit context propagation. The senior takeaway: a custom scope is powerful but you're now responsible for the entire lifecycle the container normally handles — most "I need a custom scope" cases are better served by `@Dependent` beans plus explicit lifecycle management, or by `@TransactionScoped`/request scope, and you should only build one when the unit-of-work boundary is truly first-class in your domain.

#### Q113. [Coding] Implement a JPA `AttributeConverter` to transparently encrypt a column at rest, and discuss the searchability and key-rotation trade-offs.

A JPA `AttributeConverter` intercepts the mapping between an entity attribute and its database column, making it the clean insertion point for column-level encryption: the entity holds plaintext, the database stores ciphertext, and no service code changes. This is the standard approach for field-level encryption of PII (SSNs, tokens) when full-disk or TDE encryption isn't sufficient (e.g., DBA access must not see plaintext).

```java
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    @Override
    public String convertToDatabaseColumn(String plaintext) {
        if (plaintext == null) return null;
        return Crypto.encrypt(plaintext);    // AES-GCM, returns base64(iv || ciphertext || tag)
    }

    @Override
    public String convertToEntityAttribute(String stored) {
        if (stored == null) return null;
        return Crypto.decrypt(stored);
    }
}

@Entity
public class Customer {
    @Id Long id;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "ssn", length = 512)     // ciphertext is longer than plaintext
    private String ssn;
    private String name;                    // not encrypted -> searchable
}
```

The converter runs on every read and write, so encryption is fully transparent to the rest of the app — that is its strength and its trap. The first trade-off is **searchability**: an encrypted column cannot be used in `WHERE ssn = ?`, range queries, `LIKE`, or indexes, because identical plaintexts produce *different* ciphertexts under a proper AEAD scheme (random IV per encryption — which you want, since deterministic encryption leaks equality). If you must look up by an encrypted value, store a separate keyed **blind index** (HMAC of the normalized plaintext) that is deterministic and indexable, accepting the small equality-leak that implies. The second trade-off is **key rotation**: because the converter has no per-row metadata by default, you should prefix the ciphertext with a key/version identifier so old rows decrypt with the old key while new writes use the new key, and a background job re-encrypts lazily — otherwise rotating keys means a synchronous re-encrypt of the whole table. Further sharp edges: a `@Converter` is not CDI-managed, so injecting a KMS client needs a bridge (`CDI.current()` or a static holder); converters don't fire for native/bulk SQL (those write/read raw ciphertext); and you must size the column for base64-expanded ciphertext plus the IV and auth tag. The discipline this enforces is that "encrypt the column" is never free — you trade query capability and key-management complexity for confidentiality, and the design has to decide consciously which columns are worth that cost.

#### Q114. [Coding] Demonstrate and fix a transaction-propagation correctness bug where an audit log is lost on rollback. Show the `REQUIRES_NEW` boundary and explain the persistence-context implications.

A frequent subtle bug: you want an audit/log entry to persist **even if the business transaction rolls back** (you need a record that the attempt happened), but because the audit write joins the same transaction (`REQUIRED`), it rolls back too. The fix is `REQUIRES_NEW`, which suspends the caller's transaction and runs the audit in an independent one that commits on its own.

```java
// BUG: audit shares the caller's transaction -> rolled back together
@Stateless
public class PaymentService {
    @Inject AuditService audit;
    @PersistenceContext EntityManager em;

    @Transactional
    public void pay(Payment p) {
        audit.log("attempt", p.getId());     // REQUIRED -> same tx
        em.persist(p);
        riskCheck(p);                         // throws -> WHOLE tx rolls back, audit gone
    }
}

// FIX: audit runs in its own committed transaction
@Stateless
public class AuditService {
    @PersistenceContext EntityManager em;

    @Transactional(Transactional.TxType.REQUIRES_NEW)   // suspend caller, new tx, commit now
    public void log(String action, Object id) {
        em.persist(new AuditEntry(action, id, Instant.now()));
    }   // commits here, independently of the caller
}
```

The mechanics: `REQUIRES_NEW` tells the transaction manager to **suspend** the active transaction, begin a fresh one, run the method, commit it, and then resume the original. So when `riskCheck` later throws and the payment transaction rolls back, the audit row is already durably committed in its own transaction and survives. The non-obvious correctness implication is the **persistence context**: with a transaction-scoped EntityManager, the suspended outer transaction and the new inner transaction have *separate* persistence contexts, so an entity you `persist`ed (but not yet flushed) in the outer transaction is **not visible** to the inner one, and the inner transaction reads from the database as it was committed — which can cause surprising "I just saved it but the nested method can't see it" behavior, and even self-deadlocks if the inner transaction tries to lock a row the outer transaction already locked but hasn't committed. The trade-offs: `REQUIRES_NEW` consumes a second connection for the duration (so the outer transaction holds one connection while the inner needs another — a pool-sizing consideration, and a deadlock risk if the pool is tiny), and it breaks atomicity by design, which is exactly the point for audit/notification but *wrong* for business invariants. And the EJB self-invocation caveat applies: the audit method must be on a **separately injected bean** (`AuditService`), because calling a `REQUIRES_NEW` method on `this` bypasses the interceptor and the new transaction never starts.

#### Q115. [Coding] Implement a back-pressured asynchronous processing pipeline in Jakarta EE using `ManagedExecutorService` and a bounded queue. Why is an unbounded `CompletableFuture` chain dangerous in a container?

Fire-and-forget async work that accepts tasks faster than it can complete will, with an unbounded queue, grow memory without limit until the JVM OOMs — and in a container this also evades the server's own resource accounting. The fix is a **bounded** work queue with an explicit rejection/back-pressure policy, running on a container-managed executor so context (security, transaction, tracing) propagates.

```java
@ApplicationScoped
public class ThumbnailPipeline {

    @Resource ManagedThreadFactory threadFactory;   // container-managed threads
    private ThreadPoolExecutor pool;

    @PostConstruct
    void start() {
        pool = new ThreadPoolExecutor(
            4, 8, 60, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(500),           // BOUNDED queue = back-pressure
            threadFactory,
            new ThreadPoolExecutor.CallerRunsPolicy() // when full: caller does the work (throttles)
        );
    }

    public boolean submit(ImageJob job) {
        try { pool.execute(() -> process(job)); return true; }
        catch (RejectedExecutionException e) { return false; }  // or block/shed load
    }

    @PreDestroy
    void stop() {
        pool.shutdown();
        try { if (!pool.awaitTermination(30, TimeUnit.SECONDS)) pool.shutdownNow(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
```

The danger with naive `CompletableFuture.supplyAsync(...)` chains is twofold. First, the default `ForkJoinPool.commonPool()` is **shared JVM-wide and unmanaged** — it carries none of the container context, so JTA/security/CDI-request scope silently break, and its size is fixed to CPU count regardless of your workload. Second, and worse for stability, there is **no back-pressure**: each `supplyAsync` enqueues unconditionally, so a producer faster than the consumers builds an ever-growing queue of pending tasks and captured objects until the heap is exhausted — and because the work isn't accounted in any bounded structure, the failure mode is a sudden OOM, not graceful degradation. The bounded `ArrayBlockingQueue` plus a rejection policy converts overload into an explicit, observable signal: `CallerRunsPolicy` makes the submitting thread execute the task (naturally slowing the producer), or you return `false`/429 to shed load, or you block the producer — each is a deliberate back-pressure strategy rather than unbounded accumulation. Using `ManagedThreadFactory` (or `ManagedExecutorService` directly) keeps the threads under the container's control and context-propagating. The senior framing: in a container you must always answer "what happens when work arrives faster than I can process it?" — and "memory grows until we crash" is never an acceptable answer; bounded queues, explicit rejection, and managed pools are how you make overload a controlled, measurable condition instead of an outage.

#### Q116. [Coding] Implement deterministic JSON-B serialization customization (adapters, naming strategy, polymorphic types) for a JAX-RS API, and explain the version-compatibility risks.

JSON-B (Jakarta JSON Binding) is the standard binding layer behind JAX-RS JSON. For a stable public API you need explicit control over field naming, date formats, null handling, and polymorphism — relying on defaults invites accidental breaking changes when you refactor Java field names.

```java
// Application-wide config via a ContextResolver so JAX-RS uses it for (de)serialization
@Provider
public class JsonbConfigResolver implements ContextResolver<Jsonb> {
    private final Jsonb jsonb = JsonbBuilder.create(new JsonbConfig()
        .withPropertyNamingStrategy(PropertyNamingStrategy.LOWER_CASE_WITH_UNDERSCORES)
        .withDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT)
        .withNullValues(false)                 // omit nulls -> smaller, stable payloads
        .withAdapters(new MoneyAdapter()));

    @Override public Jsonb getContext(Class<?> type) { return jsonb; }
}

// Custom adapter: serialize Money as a string, not a nested object
public class MoneyAdapter implements JsonbAdapter<Money, String> {
    public String adaptToJson(Money m)   { return m.amount() + " " + m.currency(); }
    public Money  adaptFromJson(String s){ var p = s.split(" "); 
                                           return new Money(new BigDecimal(p[0]), p[1]); }
}

// Per-field overrides on the DTO
public class OrderDto {
    @JsonbProperty("order_id") Long id;        // explicit name decouples JSON from Java
    @JsonbTransient String internalNote;       // never serialized
    @JsonbDateFormat("yyyy-MM-dd") LocalDate placedOn;
    Money total;
}
```

The customization layers from broadest to narrowest: a `JsonbConfig` sets API-wide policy (naming strategy, date format, null handling, registered adapters), `@JsonbProperty`/`@JsonbDateFormat`/`@JsonbTransient` override per field, and `JsonbAdapter`/`JsonbSerializer` handle types JSON-B can't map idiomatically (value objects like `Money`, or legacy types). The version-compatibility risks are the real expert content. **Naming**: if you rely on the default (field name → JSON key), renaming a Java field silently renames the JSON key and breaks every client — so a public API should pin names with `@JsonbProperty`, treating the wire format as a contract independent of code. **Polymorphism**: JSON-B 3.0 added `@JsonbTypeInfo`/`@JsonbSubtype` for type discriminators, but the discriminator property name and subtype aliases become part of your contract — changing them breaks deserialization, and adding an unknown subtype must be tolerated by clients. **Implementation drift**: JAX-RS runtimes may default to **Jackson** or **JSON-B/Yasson** depending on the server, and their defaults differ (null handling, date representation, unknown-property behavior), so an app that "just worked" on one server can serialize differently on another — pinning a `ContextResolver<Jsonb>` (or explicitly choosing the provider) removes that ambiguity. The discipline: for any externally consumed API, make the serialization explicit and deterministic, version the schema, and never let a Java refactor silently mutate the wire contract.

#### Q117. [Coding] Implement a Jakarta Batch (JSR-352) chunk-oriented job with reader/processor/writer, checkpointing, and a skip/retry policy. When does batch beat a streaming pipeline?

Jakarta Batch standardizes large-volume, restartable batch processing with a chunk model: a `ItemReader` produces items, an `ItemProcessor` transforms/filters them, and an `ItemWriter` persists them in chunks, with the container committing a transaction and a **checkpoint** every N items so a failure can restart from the last checkpoint rather than the beginning.

```xml
<!-- META-INF/batch-jobs/invoice-job.xml -->
<job id="invoiceJob" xmlns="https://jakarta.ee/xml/ns/jakartaee" version="2.0">
  <step id="generate">
    <chunk item-count="500" skip-limit="50" retry-limit="3">
      <reader ref="invoiceReader"/>
      <processor ref="invoiceProcessor"/>
      <writer ref="invoiceWriter"/>
      <skippable-exception-classes>
        <include class="com.acme.MalformedRowException"/>
      </skippable-exception-classes>
      <retryable-exception-classes>
        <include class="jakarta.persistence.OptimisticLockException"/>
      </retryable-exception-classes>
    </chunk>
  </step>
</job>
```

```java
@Named @Dependent
public class InvoiceReader extends AbstractItemReader {
    @Inject @BatchProperty Long lastId;
    private Iterator<Account> cursor;

    @Override public void open(Serializable checkpoint) {
        long from = checkpoint == null ? 0L : (Long) checkpoint;  // resume point
        cursor = accountDao.streamBilledSince(from).iterator();
    }
    @Override public Object readItem() {
        return cursor.hasNext() ? cursor.next() : null;   // null => end of data
    }
    @Override public Serializable checkpointInfo() { return lastSeenId; }  // persisted per chunk
}
// Processor returns null to filter an item out of the chunk; Writer gets a List<Invoice>.

// Launch
long execId = BatchRuntime.getJobOperator().start("invoiceJob", new Properties());
```

The container guarantees that make this more than a `for` loop: each chunk of `item-count` items runs in one transaction and writes a **checkpoint**, so a crash at item 700,000 restarts from the last committed checkpoint (e.g., 500,000), not from zero — this restartability is the headline feature. **Skip** lets bad individual records be logged and bypassed without failing the whole job (up to `skip-limit`), and **retry** transparently re-attempts transient failures (lock contention, deadlocks) up to `retry-limit`, optionally with a `<no-rollback-exception-classes>` refinement. The job/step/chunk metadata (status, checkpoints, exit codes) is persisted in the **job repository**, giving you queryable history and the ability to restart a failed execution by id. Batch beats a hand-rolled streaming pipeline when you need **restartability, partial-failure tolerance, progress visibility, and operational control** over a finite bulk workload — month-end billing, ETL, report generation — especially with **partitioning** (`<partition>`) to parallelize across threads/ranges. A streaming pipeline (Q105) or reactive flow is better for **unbounded, low-latency** data where there's no notion of "the job finished" and you don't need per-chunk checkpoint/restart semantics. The trade-off is that Batch carries ceremony (XML JSL, the job repository, the reader/processor/writer contracts) that's overkill for a simple one-shot loop but pays for itself precisely when a long bulk job *will* eventually fail partway and you can't afford to redo committed work.

#### Q118. [Coding] Implement WebSocket fan-out with a shared `@ApplicationScoped` registry and per-session state, and address the concurrency and clustering pitfalls.

A WebSocket endpoint instance is created per connection by default, so to broadcast (chat, live notifications) you maintain a shared registry of open sessions in an application-scoped bean and push to all of them. The endpoint must coordinate access to that shared state and respect the WebSocket concurrency rules.

```java
@ApplicationScoped
public class SessionRegistry {
    private final Set<Session> sessions = ConcurrentHashMap.newKeySet();  // thread-safe
    void add(Session s)    { sessions.add(s); }
    void remove(Session s) { sessions.remove(s); }

    public void broadcast(String json) {
        for (Session s : sessions) {
            if (s.isOpen())
                s.getAsyncRemote().sendText(json);   // async: avoids head-of-line blocking
        }
    }
}

@ServerEndpoint("/chat/{room}")
public class ChatEndpoint {
    @Inject SessionRegistry registry;

    @OnOpen    public void open(Session s)  { registry.add(s); }
    @OnClose   public void close(Session s) { registry.remove(s); }
    @OnError   public void error(Session s, Throwable t) { registry.remove(s); }

    @OnMessage
    public void message(String msg, Session sender, @PathParam("room") String room) {
        registry.broadcast("{\"room\":\"" + room + "\",\"msg\":\"" + escape(msg) + "\"}");
    }
}
```

The concurrency pitfalls are specific to WebSocket. First, the registry must use a **concurrent collection** (`ConcurrentHashMap.newKeySet()`), because `@OnOpen`/`@OnClose` fire on different threads as connections come and go while `broadcast` iterates — a plain `HashSet` corrupts or throws `ConcurrentModificationException`. Second, the spec forbids **concurrent calls to `sendText` on the same `Session`** from multiple threads; the *basic* (synchronous) remote will throw if a send is already in progress, so for fan-out you either use `getAsyncRemote()` (which queues sends per session) or serialize sends per session yourself — and even with async you must handle the send-buffer filling up for a slow client (back-pressure: a stuck client shouldn't block the broadcast loop, hence iterating with async sends and removing dead sessions). Third, each endpoint instance is per-connection but the **CDI injection of an application-scoped registry is shared**, which is exactly what you want — but don't store per-connection state in the registry without keying it by `Session`. The biggest architectural pitfall is **clustering**: this registry is per-JVM, so in a multi-node deployment a message received on node A is only broadcast to clients connected to node A. To fan out across the cluster you need a shared pub/sub backplane — publish each message to a JMS topic / Redis / Hazelcast, and have every node's registry subscribe and rebroadcast to its local sessions. Sticky sessions alone don't solve this (they keep a client on one node but don't share messages between nodes). The senior framing: in-memory WebSocket fan-out is trivial on one node and a distributed-systems problem the moment you scale horizontally, so design the backplane in from the start if you'll ever run more than one instance.

#### Q119. [Coding] Diagnose and fix a memory leak caused by a `@ConversationScoped` (or long-lived) bean accumulating state, and explain how CDI scope choice causes or prevents leaks.

Long-lived CDI scopes are a classic leak source: a bean whose lifetime is tied to a user conversation, session, or the application accumulates references that are never released because the scope itself never ends. The canonical case is `@ConversationScoped` conversations that are *begun* but never *ended*, so each leaves a bean (and everything it references) pinned in the conversation context until the session expires.

```java
// LEAK: long-running conversation begun but never ended; accumulates per user
@ConversationScoped
public class WizardState implements Serializable {
    @Inject Conversation conversation;
    private final List<byte[]> uploadedChunks = new ArrayList<>();  // grows unbounded

    public void start() {
        if (conversation.isTransient()) conversation.begin();   // long-running now
    }
    public void addChunk(byte[] c) { uploadedChunks.add(c); }
    // BUG: no conversation.end() on completion or cancel -> context lives until session dies
}

// FIX: bound the lifecycle explicitly
@ConversationScoped
public class WizardState implements Serializable {
    @Inject Conversation conversation;
    private final List<byte[]> uploadedChunks = new ArrayList<>();

    public void start() {
        if (conversation.isTransient()) {
            conversation.begin();
            conversation.setTimeout(TimeUnit.MINUTES.toMillis(10));  // safety net
        }
    }
    public void finish() {
        persist(uploadedChunks);
        uploadedChunks.clear();
        if (!conversation.isTransient()) conversation.end();   // releases the context NOW
    }
}
```

The diagnosis flow: a steadily climbing heap that survives GC, a heap dump (jmap/MAT) showing many instances of the bean retained via the CDI/Weld conversation or session context maps, with retained sizes dominated by the accumulated state. The root cause is almost always **scope outliving usefulness**: a `@SessionScoped` bean caching large objects per user (multiplied by every active session), an `@ApplicationScoped` bean using an unbounded `Map` as a cache (no eviction — effectively a permanent leak), or `@ConversationScoped` conversations begun without a matching `end()`. The fix depends on which: for conversations, always pair `begin()` with `end()` on both the success and cancel paths and set a `setTimeout` so abandoned conversations are reclaimed; for session-scoped state, store only small identifiers and rehydrate from the DB, and shorten session timeout; for application-scoped caches, use a bounded cache with eviction (Caffeine) rather than a raw `Map`. The deeper principle the interviewer is after: **CDI scope is a memory-lifecycle decision, not just a visibility one.** Every object reachable from a scoped bean lives as long as that scope, so choosing too wide a scope (or never ending a manual one) turns ordinary caching into a leak that scales with users or uptime. The discipline is to pick the narrowest scope that satisfies the use case, treat any unbounded collection in a non-request scope as a red flag, and make manual scope boundaries (`Conversation.begin/end`) as symmetric and exception-safe as resource `close()`.

#### Q120. [Coding] Implement context propagation for a trace ID and security identity across an async boundary using Jakarta Concurrency `ContextService` / MicroProfile Context Propagation. Why does naive `CompletableFuture` lose context?

When work hops to another thread — `ManagedExecutorService`, a `CompletableFuture` stage, a reactive operator — the destination thread does not inherit the originating thread's context: the CDI request scope, the JTA transaction, the security identity, and any `ThreadLocal` (MDC trace id) are all thread-bound and simply absent on the new thread. That's why a trace id set in a request filter vanishes in async log lines and `SecurityContext.getCallerPrincipal()` returns null inside an async task. Jakarta Concurrency's `ContextService` (and MicroProfile Context Propagation) solve this by **capturing** the current context and **wrapping** the task so the context is applied on the executing thread and cleared afterward.

```java
@ApplicationScoped
public class AsyncOrderService {

    @Inject ManagedExecutorService mes;   // EE 10: already propagates context by default
    @Resource ContextService contextService;

    // Approach A: ManagedExecutorService propagates context automatically
    public CompletableFuture<Receipt> processAsync(Order o) {
        return mes.supplyAsync(() -> {
            // trace id (MDC), security identity, CDI request scope all present here
            log.info("processing {}", o.getId());        // log line carries the trace id
            return charge(o);
        });
    }

    // Approach B: wrap a task to run with the *current* context on ANY executor
    public Runnable contextual(Runnable task) {
        return contextService.contextualRunnable(task);   // captures now, applies on run
    }

    // MicroProfile alternative: build an executor that propagates exactly what you choose
    // ManagedExecutor mp = ManagedExecutor.builder()
    //        .propagated(ThreadContext.CDI, ThreadContext.SECURITY, ThreadContext.APPLICATION)
    //        .cleared(ThreadContext.TRANSACTION)   // don't leak a tx into async work
    //        .build();
}
```

The reason naive `CompletableFuture.supplyAsync(task)` loses context is twofold: it runs on the unmanaged `ForkJoinPool.commonPool()`, and even on a custom executor the JDK `CompletableFuture` does nothing to capture/restore thread-bound state — it just runs your lambda on whatever thread the pool gives it. So the lambda executes with an empty MDC, no active CDI request context, and the anonymous pool thread's (lack of) security identity. The managed approach captures a **snapshot** of the configured context types when the task is *submitted*, installs them on the worker thread before running, and tears them down after — which is essential not just for tracing convenience but for correctness: distributed tracing breaks without trace-id propagation, authorization breaks without identity propagation, and tenant isolation breaks without tenant-context propagation. The expert nuances: you must decide per context type whether to **propagate, clear, or leave unchanged** — propagating a *transaction* into async work is usually wrong (the async task shouldn't enlist in the caller's transaction, which may have already committed/rolled back), so `TRANSACTION` is typically *cleared*; propagating request scope into long-lived async work can extend that scope's lifetime unexpectedly. In EE 10, `ManagedExecutorService`/`ManagedScheduledExecutorService` propagate context by default, which is why preferring them over raw `ForkJoinPool` or `Executors.newFixedThreadPool` is the single most important habit for async correctness in a Jakarta EE app.

#### Q121. [Coding] Design a feature-flagged strangler facade in JAX-RS that routes traffic between a legacy implementation and a new one, with metrics and instant rollback. Walk the design.

The strangler pattern incrementally replaces a legacy system by intercepting calls at a facade and routing each to either the old or new implementation, growing the new path until the old one is dead. In Jakarta EE you implement the facade as a JAX-RS resource (or a CDI service) that selects the implementation per request via a feature flag, emits comparison metrics, and can flip back instantly if the new path misbehaves.

```java
@ApplicationScoped
public class PricingFacade {
    @Inject @Named("legacy") PricingService legacy;
    @Inject @Named("v2")     PricingService v2;
    @Inject Config config;                       // MicroProfile Config (hot-reloadable source)
    @Inject MeterRegistry metrics;

    public Price price(Order o) {
        boolean useV2 = rolloutEnabled(o);       // % rollout / allowlist / kill switch
        String impl = useV2 ? "v2" : "legacy";
        var sample = Timer.start(metrics);
        try {
            Price p = (useV2 ? v2 : legacy).price(o);
            metrics.counter("pricing.calls", "impl", impl, "outcome", "ok").increment();
            return p;
        } catch (RuntimeException e) {
            metrics.counter("pricing.calls", "impl", impl, "outcome", "error").increment();
            if (useV2 && config.getOptionalValue("pricing.v2.fallbackOnError", Boolean.class)
                               .orElse(true)) {
                return legacy.price(o);          // automatic fallback to legacy on v2 failure
            }
            throw e;
        } finally {
            sample.stop(metrics.timer("pricing.latency", "impl", impl));
        }
    }

    private boolean rolloutEnabled(Order o) {
        if (config.getValue("pricing.v2.killSwitch", Boolean.class)) return false; // instant off
        int pct = config.getValue("pricing.v2.rolloutPct", Integer.class);
        return Math.floorMod(o.getCustomerId().hashCode(), 100) < pct;   // sticky per customer
    }
}
```

The design choices that make this safe in production. **Routing is config-driven and hot**: the rollout percentage and kill switch come from MicroProfile Config backed by a source you can change without redeploy (a config map, a DB-backed `ConfigSource`), so you can dial v2 from 1% to 100% — or back to 0% — in seconds; that instant-rollback capability is the whole point of a strangler facade. **Routing is sticky** (hash by customer id, not random) so a given customer sees a consistent implementation, which avoids flapping and makes user-visible behavior reproducible. **Both paths are metered with the same labels** (`impl=v2|legacy`, outcome, latency) so you can compare error rate and latency between implementations on a dashboard and gate rollout on real signal rather than hope — and optionally run **shadow/parallel** mode (call both, serve legacy, diff the results, alert on divergence) before serving v2 at all. **A fallback path** turns a v2 failure into a legacy success rather than a user-facing error during the risky early rollout. The trade-offs: dual implementations and the facade add temporary complexity and a coupling point, the facade must not itself become permanent, and shadow mode doubles load on read paths and is unsafe for non-idempotent writes. The senior framing is that a strangler migration's success depends less on the new code than on the **operability of the cutover** — gradual, observable, sticky, and instantly reversible — and the facade is where you engineer all four.

#### Q122. [Coding] Implement correct optimistic locking with a retry across a JAX-RS request boundary (detached entities, version sent to the client), and explain why server-side `@Version` alone is insufficient for "lost update" across HTTP.

Within a single transaction, `@Version` (Q19) catches concurrent writes. But a REST edit spans **two** requests — a GET that returns the entity and a later PUT that submits changes — with no server-side transaction held in between (the entity is detached, the user may sit on the form for minutes). To detect that someone else edited the row during that gap, the client must **round-trip the version** it read, and the server must compare it on update. This is optimistic locking across the HTTP boundary, and it's distinct from the in-transaction case.

```java
// GET returns the entity including its current version (or as an ETag)
@GET @Path("/{id}")
public Response get(@PathParam("id") long id) {
    Account a = em.find(Account.class, id);
    return Response.ok(toDto(a))
                   .header("ETag", "\"" + a.getVersion() + "\"")   // version as ETag
                   .build();
}

// PUT carries the version the client last saw, via If-Match (or a body field)
@PUT @Path("/{id}")
@Transactional
public Response update(@PathParam("id") long id,
                       @HeaderParam("If-Match") String ifMatch,
                       AccountDto dto) {
    Account a = em.find(Account.class, id);
    long clientVersion = parseEtag(ifMatch);
    if (a.getVersion() != clientVersion)
        return Response.status(Response.Status.PRECONDITION_FAILED)   // 412: someone else edited
                       .entity(new Conflict(toDto(a))).build();        // return current state
    a.setBalance(dto.balance());
    try {
        em.flush();   // UPDATE ... WHERE id=? AND version=clientVersion; bumps version
    } catch (OptimisticLockException e) {     // lost the race in the tiny commit window
        return Response.status(Response.Status.PRECONDITION_FAILED).build();
    }
    return Response.ok(toDto(a)).header("ETag", "\"" + a.getVersion() + "\"").build();
}
```

Why server-side `@Version` alone is insufficient: when you load the entity fresh inside the PUT transaction, JPA reads the *current* version from the database, so a plain dirty-checked update would compare the row against itself and happily overwrite whatever changed since the user's GET — the "lost update" the user never sees. The version the user actually based their edit on lived on the client between the two requests, so it **must travel to the client and back**; the server enforces optimism by checking the client-supplied version against the current one (an explicit `If-Match`/version comparison) and only then applying the change. Using the **`ETag` + `If-Match`** mechanism is the HTTP-native expression of exactly this (the spec calls it conditional requests), and returning **412 Precondition Failed** with the current state lets the client show a "this changed, here's the latest, merge and retry" UX rather than silently clobbering. The retry policy lives on the client (re-GET, re-apply, re-PUT) because only a human or domain logic can resolve a genuine conflict. Two sharp edges: there's still a tiny in-transaction race between your manual version check and the flush, so keep the `OptimisticLockException` catch as the authoritative backstop (the database `WHERE version=?` is the real guarantee, your pre-check is just a friendlier early exit); and if you compare versions manually you must read the version as part of the same transaction that does the update, or you reintroduce the gap. The principle: stateless HTTP has no ambient transaction across requests, so cross-request optimistic locking requires **carrying the version through the client** — the database `@Version` is necessary but the protocol-level conditional request is what closes the lost-update window users actually hit.

#### Q123. [Coding] Implement a custom Jakarta Security `HttpAuthenticationMechanism` plus an `IdentityStore`, and explain how it supersedes legacy JAAS/container-specific login modules.

Jakarta Security (introduced in Java EE 8) standardized authentication into two CDI-based SPIs: an `HttpAuthenticationMechanism` (how credentials are extracted from the request and how challenges are issued) and an `IdentityStore` (how credentials are validated and roles resolved). Implementing them yourself gives a fully portable, container-independent auth flow that integrates directly with `@RolesAllowed`, replacing the per-server JAAS login-module configuration that used to make security non-portable.

```java
@ApplicationScoped
public class TokenAuthMechanism implements HttpAuthenticationMechanism {
    @Inject IdentityStoreHandler stores;     // delegates to all registered IdentityStores

    @Override
    public AuthenticationStatus validateRequest(HttpServletRequest req,
            HttpServletResponse res, HttpMessageContext ctx) {
        String token = req.getHeader("X-API-Token");
        if (token == null) {
            return ctx.isProtected()
                ? ctx.responseUnauthorized()      // 401 on protected resource, no creds
                : ctx.doNothing();                 // public resource -> continue anonymous
        }
        CredentialValidationResult r = stores.validate(new TokenCredential(token));
        if (r.getStatus() == CredentialValidationResult.Status.VALID) {
            return ctx.notifyContainerAboutLogin(r);   // sets caller principal + groups
        }
        return ctx.responseUnauthorized();
    }
}

@ApplicationScoped
public class TokenIdentityStore implements IdentityStore {
    @Inject ApiTokenDao dao;
    @Override
    public CredentialValidationResult validate(Credential credential) {
        if (!(credential instanceof TokenCredential tc)) return NOT_VALIDATED_RESULT;
        return dao.find(tc.token())
            .map(t -> new CredentialValidationResult(t.userId(), t.roles()))  // valid + roles
            .orElse(INVALID_RESULT);
    }
}

@Path("/reports") public class ReportResource {
    @GET @RolesAllowed("analyst")    // enforced against the principal/groups we established
    public List<Report> list() { ... }
}
```

The flow: the mechanism's `validateRequest` runs for every request into the security domain, extracts the credential, and delegates validation to the `IdentityStoreHandler`, which fans out to every registered `IdentityStore` and aggregates the result (one store can validate, another can supply roles). On success, `notifyContainerAboutLogin` installs the caller principal and groups into the container's `SecurityContext`, after which standard declarative authorization (`@RolesAllowed`, `web.xml` constraints, `SecurityContext.isCallerInRole`) just works. The reason this **supersedes JAAS**: legacy Java EE security relied on JAAS `LoginModule`s and realm/identity configuration done in **vendor-specific** ways (WildFly `security-domain`, WebLogic providers, GlassFish realms), so the same WAR needed different server configuration to authenticate, and the login modules weren't CDI-aware (no injection, no portable lifecycle). Jakarta Security moves all of this into **portable, CDI-managed components packaged with the application** — the mechanism and store are `@ApplicationScoped` beans that can `@Inject` DAOs/clients, they deploy unchanged across WildFly/Payara/Open Liberty, and built-in implementations (`@BasicAuthenticationMechanismDefinition`, `@FormAuthenticationMechanismDefinition`, `@DatabaseIdentityStoreDefinition`, `@LdapIdentityStoreDefinition`) cover common cases declaratively. The trade-offs and caveats: you still layer transport security (TLS) and **object-level** authorization yourself (the mechanism only establishes *who* the caller is and their coarse roles); `validateRequest` runs on the request thread so keep store lookups fast/cached; and for stateless token APIs you typically `ctx.doNothing()` on public paths and avoid creating HTTP sessions (`HttpMessageContext` lets you control session creation). For microservices, **MicroProfile JWT** is essentially a specialized, standardized token mechanism built on these same ideas — worth naming as the cloud-native counterpart to a hand-written token mechanism.

#### Q124. [Behavioral] As a staff engineer, you must decide whether to standardize the org on Jakarta EE/MicroProfile (Quarkus) or Spring Boot for the next five years. Walk me through how you'd drive that decision.

This probes architectural leadership, stakeholder management, and the judgment to make a high-stakes, hard-to-reverse decision without bias — STAR with emphasis on process. **Situation:** "We had a fragmented landscape — legacy WildFly/Jakarta EE monoliths, some Spring Boot services, and a mandate to standardize the platform for the next five years to cut cognitive load, hiring friction, and operational sprawl. As staff engineer I owned the recommendation but not the unilateral decision." **Task:** "Produce a defensible, evidence-based recommendation that the principal engineers, platform team, and engineering directors would commit to — and that wouldn't be relitigated every quarter."

**Action:** "I refused to make it a tribal debate. First I defined **weighted decision criteria** with the stakeholders up front — team skills and hiring market, startup time and memory footprint (we run dense Kubernetes and some serverless, so cold-start and per-pod cost matter), native-image maturity, ecosystem and library availability, vendor support and LTS cadence, observability/MicroProfile integration, and migration cost from our existing estate. Then I ran a **time-boxed bake-off**: the same representative service implemented in Quarkus and Spring Boot 3, measured for startup time, RSS, p99 latency, native-image build success, and developer ergonomics, with the actual numbers published. I deliberately surfaced **disconfirming evidence** for my initial lean — including where Spring's ecosystem breadth and our existing in-house expertise reduced risk. I also separated the decision into 'default for greenfield' vs 'forced migration of existing services' so we didn't conflate a standard with a costly rewrite." **Result:** "We chose Quarkus as the greenfield default for latency- and density-sensitive services (the native-image startup and memory wins were decisive for our autoscaling cost), kept Spring Boot as a supported second option for teams with deep investment, and explicitly chose **not** to rewrite stable monoliths absent another reason. I documented the rationale and the criteria in an ADR so future revisits start from the recorded reasoning, not from scratch. A year later attrition of the decision was near zero because people had bought into the *criteria*, not just the outcome."

What the interviewer is evaluating: whether you make consequential decisions through explicit, weighted criteria and real measurement rather than preference; whether you actively seek evidence against your own bias; whether you manage stakeholders so the decision *sticks* (buy-in on the process, an ADR for durability); and whether you have the maturity to say "both are standards-aligned, choose on operational profile and team reality" rather than evangelizing a framework. The strongest signal is decoupling "what's our default" from "what must we migrate," and tying every criterion to a business outcome (cost, reliability, hiring, delivery speed) — a staff engineer optimizes the org's long-run total cost, not the elegance of any single stack.

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
