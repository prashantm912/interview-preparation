# Microservices Architecture Patterns

A deep, interview-focused guide to microservices: when to adopt them, how to decompose a system, the canonical design patterns (database-per-service, CQRS, strangler fig, sidecar, BFF, anti-corruption layer), and the operational realities (distributed data, observability, contract testing) that separate a working system from a distributed monolith. Knowledge current through 2026; primary examples in Java (Spring Boot 3 / Java 21).

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

### Q1. [Theory] What is a microservices architecture, and how does it differ from a monolith?

A monolith packages all functionality into a single deployable unit with one shared codebase and (usually) one shared database. A microservices architecture decomposes the application into a set of small, independently deployable services, each owning a single business capability and its own data store, communicating over the network (HTTP/gRPC or async messaging).

The key distinction is **independent deployability**, not size. A "microservice" is not defined by line count; it is defined by being able to build, test, deploy, and scale it without coordinating with other teams. The trade-off: you swap in-process method calls (fast, transactional, type-checked at compile time) for network calls (slow, partial-failure-prone, eventually consistent). Microservices buy you team autonomy and independent scaling at the cost of operational and data-consistency complexity.

```
MONOLITH                         MICROSERVICES
+-----------------------+        +--------+  +--------+  +---------+
|  UI / Controllers     |        | Orders |  | Payment|  | Catalog |
|  Order | Pay | Catalog|        |  svc   |  |  svc   |  |  svc    |
|  Shared business layer|        +---+----+  +---+----+  +----+----+
|  Single DB schema     |            |           |            |
+-----------------------+        +---v---+   +---v---+    +---v---+
   one deploy, one DB            | DB-O  |   | DB-P  |    | DB-C  |
                                 +-------+   +-------+    +-------+
                                  N deploys, N databases
```

### Q2. [Theory] What are the main trade-offs of adopting microservices?

**Benefits:** independent deployability and scaling, technology heterogeneity (the right tool per service), fault isolation (a failing recommendations service shouldn't take down checkout), and alignment with autonomous teams (Conway's Law working *for* you).

**Costs:** distributed-systems complexity (network latency, partial failures, retries), the loss of ACID transactions across services (you need sagas and eventual consistency), operational overhead (you now run dozens of deployables, CI/CD pipelines, dashboards), harder debugging (a single request fans out across many services), and data-management challenges (no JOINs across databases).

The honest summary: microservices trade *development-time* simplicity for *runtime* and *organizational* flexibility. If your organization can't yet run a monolith well — no CI/CD, no monitoring, no on-call discipline — microservices will amplify the dysfunction, not cure it.

### Q3. [Theory] What is a bounded context, and why does it matter for service boundaries?

A bounded context is a Domain-Driven Design concept: an explicit boundary within which a particular domain model and its ubiquitous language are consistent and well-defined. The word "Customer" in the *Sales* context (leads, opportunities) means something different from "Customer" in the *Support* context (tickets, SLAs) or *Billing* (invoices, payment methods).

It matters because **the best microservice boundaries usually align with bounded contexts**, not with technical layers or database tables. Drawing service boundaries along bounded contexts gives each service a cohesive model, minimizes the chatter between services (because tightly coupled concepts live together), and lets each team evolve its model independently. Splitting by technical layer ("a service for all the controllers, a service for all the DAOs") produces a distributed monolith with maximal coupling.

### Q4. [Practical] You have a Spring Boot service that needs the base URL of a downstream service. Where should that value live, and why?

It should live in **externalized configuration**, never hard-coded. In a 12-factor approach, config that varies by environment (dev/staging/prod) comes from the environment, not the build artifact — the same JAR runs everywhere.

In practice, with Spring Boot you bind it to a typed properties class and source the value from an environment variable or a config server:

```java
@ConfigurationProperties(prefix = "downstream.payment")
public record PaymentClientProps(String baseUrl, Duration timeout) {}
```

```yaml
# application.yml — value injected from env at runtime
downstream:
  payment:
    base-url: ${PAYMENT_BASE_URL:http://localhost:8081}
    timeout: 2s
```

In production you'd use a centralized config source: Spring Cloud Config, Consul, AWS AppConfig, or Kubernetes ConfigMaps/Secrets. The rule: **never bake environment-specific config or secrets into the image.** Secrets specifically belong in a secret manager (Vault, AWS Secrets Manager) and should be injected at runtime, not committed to the config repo.

### Q5. [Theory] What is service discovery and why do you need it?

In a dynamic environment, service instances come and go — they're autoscaled, rescheduled by Kubernetes onto new nodes, and get ephemeral IPs. Service discovery is the mechanism by which a client finds a healthy instance of a service without hard-coding addresses.

There are two models. **Client-side discovery**: the client queries a registry (Eureka, Consul) for instances and load-balances itself. **Server-side discovery**: the client calls a stable virtual address and an intermediary (a load balancer, or Kubernetes' `Service` + `kube-proxy`/DNS) routes to a healthy pod. In modern Kubernetes deployments, server-side discovery via cluster DNS (`http://payment-svc.default.svc.cluster.local`) is the default and you rarely run Eureka anymore. Either way, discovery must be paired with **health checks** so traffic only goes to ready instances.

---

## 🟡 Intermediate (3–7 yrs)

### Q6. [Theory] Explain the database-per-service pattern. What problem does it solve and what does it create?

Each service owns its data exclusively: it has its own schema/database, and **no other service may read or write that database directly** — access only through the owning service's API. This enforces loose coupling at the data layer, lets each service choose the best storage (Postgres for orders, Elasticsearch for search, Redis for sessions), and lets schemas evolve independently.

What it creates: you lose cross-service JOINs and cross-service ACID transactions. A query that used to be one SQL statement now requires API composition or a read model, and a write that spans services now requires a saga. The cardinal sin is the **shared database** anti-pattern, where multiple services read each other's tables — it looks like microservices but every schema change becomes a multi-team coordination event. If two services *must* share a table, that's strong evidence they belong in the same service.

```
RIGHT                                WRONG (shared DB anti-pattern)
[Order svc]--owns-->[Order DB]       [Order svc]---\
[Pay svc]  --owns-->[Pay DB]         [Pay svc]------>[ ONE shared DB ]
   cross access only via API         [Catalog svc]--/  any svc reads any table
```

### Q7. [Theory] What is the API Composition pattern and when does it break down?

API Composition implements a query that spans multiple services by having a composer (often an API gateway or a BFF) invoke each owning service and join the results in memory. For example, "show an order with customer details and product info" calls Order, Customer, and Catalog services and stitches the response.

It's simple and keeps each service authoritative over its own data. It breaks down when: (1) the in-memory join is large — paginating/filtering/sorting across services in application code is inefficient and can mean fetching huge datasets; (2) latency and availability degrade — the composite is only as fast and available as the slowest/least-available dependency (call 4 services at 99.9% each and you're at ~99.6%); (3) you need complex aggregations. When composition strains, the answer is usually **CQRS**: maintain a denormalized read model fed by events.

### Q8. [Theory] Explain CQRS in a microservices context. What are its costs?

CQRS (Command Query Responsibility Segregation) splits the write model from the read model. Commands mutate state through the authoritative services; those services publish events; a separate **read model** (a denormalized view, often in its own datastore) is built by consuming those events and is optimized purely for queries.

In microservices it's the natural complement to API composition: instead of fanning out to four services at query time, you maintain a precomputed view that answers the query in one hit. It enables independent scaling of reads vs writes and lets the read store use a query-optimized engine (Elasticsearch, a materialized view).

Costs: **eventual consistency** (the read model lags the write model — a user may not immediately see their own write), **operational complexity** (another datastore plus the event pipeline that keeps it fresh), and **complexity of correctness** (handling out-of-order/duplicate events, rebuilding the view after a bug). Don't reach for CQRS by default — adopt it for specific read-heavy or composition-heavy slices.

```
   Command side                         Query side
[Client]->[Order svc]--writes-->[Order DB]
                 |  emits OrderCreated event
                 v
            [Event bus] ---> [Projector] --builds--> [Read model DB]
                                                          ^
[Client read] -------------------------------------------/
```

### Q9. [Practical] You must extract a microservice from a large legacy monolith that you can't rewrite at once. What pattern do you use and how?

The **Strangler Fig** pattern (named after the vine that grows around a tree and gradually replaces it). You incrementally route slices of functionality to new services while the monolith keeps serving the rest, until the monolith is "strangled" and can be retired.

Approach in production:
1. Put a **facade/proxy** (API gateway or a routing layer) in front of the monolith so callers are decoupled from where logic actually lives.
2. Pick a low-risk, high-value, loosely-coupled capability first (e.g., *notifications* or *search*), build it as a new service, and route just that endpoint to the new service at the proxy.
3. Handle data: either the new service owns new writes and back-fills, or you use change-data-capture (e.g., Debezium) to sync the monolith DB into the new service during transition. An **anti-corruption layer** sits between them so the monolith's legacy model doesn't leak into the clean new model.
4. Migrate slices iteratively, measuring at each step; never do a big-bang cutover.

Trade-off: you run both systems in parallel for a while (more cost, dual maintenance), but you de-risk delivery and can stop/roll back at any slice boundary. This is what I'd actually do — big-bang rewrites of monoliths are the classic project graveyard.

### Q10. [Theory] What is the sidecar pattern, and how does it relate to a service mesh?

A sidecar is a helper container deployed alongside your service container (in the same Kubernetes pod), sharing its lifecycle and network namespace but running separately. It handles cross-cutting concerns — TLS termination, retries, circuit breaking, metrics, tracing — so your service code doesn't have to. The application talks to `localhost`; the sidecar handles the network.

A **service mesh** (Istio, Linkerd) is sidecars at scale: every service gets a proxy sidecar (e.g., Envoy), and a control plane configures them centrally. This moves traffic management, mTLS, and observability out of application code and into infrastructure, giving you uniform, language-agnostic policy. The cost is operational complexity and per-hop latency. In 2024–2026 the trend is toward *sidecarless* meshes (Istio's ambient mode) to cut that overhead, using per-node proxies instead of per-pod sidecars.

### Q11. [Theory] Distinguish the Ambassador, Anti-Corruption Layer, and BFF patterns — they're easy to confuse.

- **Ambassador**: an out-of-process proxy that handles *client-side connectivity* concerns to a remote service (retries, monitoring, TLS, routing). It's a sidecar specialized for *outbound* calls — useful for legacy apps that can't easily add resilience libraries.
- **Anti-Corruption Layer (ACL)**: a *translation* layer that converts between your clean domain model and a foreign/legacy model, so the foreign model's concepts don't corrupt yours. It's about *semantic* isolation between bounded contexts, not connectivity.
- **Backend for Frontend (BFF)**: a dedicated backend per client type (web, iOS, Android), aggregating and shaping downstream data specifically for that UI. It's about *client-specific aggregation*, not translation or connectivity.

The mnemonic: Ambassador = *connection* concerns, ACL = *model* translation, BFF = *client* shaping.

### Q12. [Practical] Why introduce a BFF instead of letting mobile and web call microservices directly?

Different clients have different needs. A mobile app wants fewer, smaller, batched payloads (bandwidth/battery), a web SPA can tolerate chattier calls and wants richer data, and each evolves at its own pace. Without a BFF, you either bloat a single API gateway with client-specific logic or push aggregation into every client.

A BFF gives each frontend a tailored backend: it aggregates calls to downstream services, trims fields, handles client-specific auth/session concerns, and shields clients from backend refactors. The team owning the web app can own the web BFF and move fast.

```
[Web SPA]  -> [Web BFF]   --\
[iOS app]  -> [Mobile BFF]--->  [Order] [Catalog] [Pricing] [Inventory] services
[Partner]  -> [Public API GW]-/
```

Trade-off: more deployables and some duplicated aggregation logic across BFFs. Mitigate by sharing client libraries/contracts and keeping BFFs thin (orchestration only, no business rules). Security note: the BFF is a good place to terminate the user session and exchange it for short-lived downstream tokens (the BFF pattern for OAuth2 keeps tokens off the browser).

### Q13. [Coding] Implement an idempotent payment endpoint so a retried request never double-charges.

**Problem:** Networks retry. If a client sends `POST /payments` twice (timeout then retry), you must charge once. The standard solution is an **idempotency key** supplied by the client; the server records the key and returns the original result on replay.

```java
@RestController
@RequestMapping("/payments")
public class PaymentController {

    private final IdempotencyStore store;   // backed by Redis/Postgres with a unique key
    private final PaymentService payments;

    public PaymentController(IdempotencyStore store, PaymentService payments) {
        this.store = store;
        this.payments = payments;
    }

    @PostMapping
    public ResponseEntity<PaymentResult> charge(
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody @Valid ChargeRequest req) {

        // 1) Atomically claim the key. INSERT ... ON CONFLICT DO NOTHING semantics.
        var claimed = store.tryClaim(key, req.fingerprint());
        if (!claimed.isNew()) {
            if (!claimed.fingerprintMatches(req.fingerprint())) {
                // same key, different body => client bug; reject
                return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).build();
            }
            // replay: return the stored prior result (may be IN_PROGRESS -> 409)
            return claimed.result()
                    .map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.status(HttpStatus.CONFLICT).build());
        }

        // 2) First time: perform the charge exactly once, then persist the result.
        PaymentResult result = payments.charge(req);   // calls PSP, writes ledger row
        store.complete(key, result);
        return ResponseEntity.ok(result);
    }
}
```

The atomicity of `tryClaim` is the crux — it must be a single atomic DB operation (`INSERT ... ON CONFLICT DO NOTHING` returning whether the row was new) or a Redis `SET key val NX`. The fingerprint (hash of the body) guards against key reuse with a different payload.

**Time:** O(1) per request (one indexed lookup/insert). **Space:** O(N) for N stored keys; expire them with a TTL (e.g., 24–72h).

**Edge cases:** concurrent duplicate requests (the loser sees `IN_PROGRESS` → return 409 so the client retries to fetch the result); crash after charging but before `complete()` (mitigate by writing the PSP request with the idempotency key downstream too, so the PSP itself dedupes); missing header (reject with 400 — require the key for non-idempotent mutations).

### Q14. [Coding] Implement a resilient downstream call with timeout, retry, and circuit breaker in Spring Boot 3 (Resilience4j).

**Problem:** A synchronous call to a flaky downstream must not hang threads or cascade failures. Apply a timeout, bounded retries (only on transient errors), a circuit breaker, and a fallback.

```java
@Service
public class PricingClient {

    private final RestClient http;   // Spring Framework 6 RestClient

    public PricingClient(RestClient.Builder b,
                         @Value("${pricing.base-url}") String base) {
        this.http = b.baseUrl(base).build();
    }

    @CircuitBreaker(name = "pricing", fallbackMethod = "fallbackPrice")
    @Retry(name = "pricing")          // retry only transient failures (see config)
    @TimeLimiter(name = "pricing")    // requires a CompletableFuture return type
    public CompletableFuture<Price> getPrice(String sku) {
        return CompletableFuture.supplyAsync(() ->
            http.get().uri("/prices/{sku}", sku)
                .retrieve()
                .body(Price.class));
    }

    // Fallback: same signature + Throwable. Return a safe default or cached value.
    private CompletableFuture<Price> fallbackPrice(String sku, Throwable t) {
        return CompletableFuture.completedFuture(Price.unavailable(sku));
    }
}
```

```yaml
resilience4j:
  timelimiter.instances.pricing.timeout-duration: 2s
  retry.instances.pricing:
    max-attempts: 3
    wait-duration: 200ms
    enable-exponential-backoff: true
    retry-exceptions: [java.io.IOException, java.util.concurrent.TimeoutException]
  circuitbreaker.instances.pricing:
    sliding-window-size: 50
    failure-rate-threshold: 50          # open if >=50% of last 50 calls fail
    wait-duration-in-open-state: 10s
    permitted-number-of-calls-in-half-open-state: 5
```

**Key correctness points:** retry **only** transient/idempotent failures — never blindly retry a non-idempotent `POST` without an idempotency key, or you double-process. Use **exponential backoff + jitter** to avoid a retry storm (the thundering herd). The circuit breaker prevents hammering a dead dependency and gives it time to recover; the half-open state probes recovery.

**Complexity:** O(1) extra per call; worst case `max-attempts` round trips. **Edge cases:** timeout shorter than downstream's own retry budget; circuit stuck open due to a slow (not failing) dependency — use slow-call thresholds; bulkhead the thread pool so one slow dependency can't exhaust all threads.

### Q15. [Theory] How do you version a microservice API while keeping backward compatibility?

The goal is to evolve without breaking existing consumers, because in microservices you rarely deploy all clients atomically. Principles:

- **Prefer additive, non-breaking changes** (tolerant reader pattern): add optional fields, never remove or repurpose existing ones, never tighten validation on existing fields. Consumers should ignore unknown fields.
- **When a breaking change is unavoidable, version explicitly** — via URI (`/v2/orders`), media type (`Accept: application/vnd.acme.order.v2+json`), or header. URI versioning is the most operationally obvious; media-type versioning is purest REST. Pick one and be consistent.
- **Run versions in parallel** for a deprecation window, with monitoring on the old version's traffic so you know when it's safe to retire.
- For internal binary protocols use a schema with built-in evolution rules — **Protobuf** (gRPC) and **Avro** are designed for forward/backward compatibility if you follow field-numbering rules (never reuse field numbers, only add optional fields).

Pair this with **contract testing** (next question) so you *know* a change is backward compatible rather than hoping.

### Q16. [Practical] Two teams own producer and consumer services. How do you stop one team's deploy from breaking the other, without slow end-to-end tests?

Use **consumer-driven contract testing** (Pact, or Spring Cloud Contract). The consumer team writes expectations of the provider's API as a contract; the provider's CI verifies it still satisfies every consumer contract before deploying. This catches breaking changes at build time, in each team's own pipeline, without spinning up the whole system.

```
Consumer CI                 Pact Broker                 Provider CI
 write expectations  -->  publish contract  -->  provider verifies contract
 (mock provider in              ^   |                    on every build
  consumer unit tests)          |   v
                          can-i-deploy gate (checks compatibility matrix)
```

In production I'd wire a **Pact Broker** and a `can-i-deploy` gate: before either side deploys, it asks the broker whether that version is compatible with what's deployed in the target environment. This gives independent deployability with confidence. Trade-offs: contracts test the *interface*, not behavior or performance, so you still need a thin layer of end-to-end smoke tests and good observability; and contracts add discipline overhead. But the alternative — a giant shared integration-test suite — becomes the coordination bottleneck microservices were meant to remove.

### Q17. [Practical] Describe the testing pyramid for a microservice. What gets tested where?

```
        /\         End-to-end (few): critical user journeys across services
       /  \        Contract (Pact): provider/consumer interface compatibility
      /----\       Component/Integration: service + its DB + mocked collaborators
     /------\      Unit (many): domain logic, pure and fast
    /--------\
```

- **Unit** (most numerous): pure domain/business logic, no I/O, milliseconds.
- **Integration/component**: the service wired to its real database (Testcontainers) with external services stubbed (WireMock); verifies the service works as a unit including persistence and serialization.
- **Contract**: verifies the API agreements between this service and its collaborators (Pact). Replaces most cross-service integration tests.
- **End-to-end** (fewest): a handful of business-critical journeys through the deployed system. Expensive, flaky, slow — keep them minimal and rely on observability in production to catch the rest.

The anti-pattern is an inverted pyramid: lots of slow, flaky E2E tests and few unit tests, which makes pipelines slow and undermines the independent-deployment promise.

---

## 🟠 Advanced (8–12 yrs)

### Q18. [Theory] How do you manage a transaction that spans multiple services? Compare orchestration and choreography sagas.

Since there's no distributed ACID transaction across database-per-service boundaries, you use a **saga**: a sequence of local transactions, each publishing an event/command that triggers the next; if a step fails, you run **compensating transactions** to semantically undo prior steps (you can't roll back committed local transactions, so you issue an offsetting action — e.g., "refund" compensates "charge").

- **Choreography**: services react to each other's events with no central coordinator. Decentralized and loosely coupled, but the overall flow is implicit and emergent — hard to understand, debug, and detect cycles in. Good for short sagas.
- **Orchestration**: a central saga orchestrator explicitly tells each service what to do and tracks state (often as a state machine). Easier to reason about, monitor, and modify; the cost is the orchestrator becomes a logical coupling point and must be highly available.

```
Choreography (event-driven)         Orchestration (commands from coordinator)
Order--OrderCreated-->Payment        +-------------+ reserve  +-----------+
Payment--Paid-------->Inventory      | Saga        |--------->| Inventory |
Inventory--Reserved-->Shipping       | Orchestrator|--charge->| Payment   |
(compensate by reverse events)       |  (FSM)      |--ship--->| Shipping  |
                                     +-------------+ (compensate on failure)
```

Sagas give **atomicity** but **not isolation** — concurrent sagas can see intermediate states. Mitigate with semantic locks, commutative updates, or versioned records. For correctness, pair sagas with the outbox/idempotency patterns below.

### Q19. [Theory] What is the transactional outbox pattern and what failure does it prevent?

It prevents the **dual-write problem**: a service that updates its database *and* publishes an event to a broker in two separate operations can crash between them, leaving the DB updated but the event lost (or vice versa) — there's no shared transaction across DB and broker.

The outbox pattern makes the two writes atomic: in the **same local DB transaction** that mutates business state, you insert the event into an `outbox` table. A separate **message relay** then reads the outbox and publishes to the broker, marking rows sent. Because the business change and the outbox insert commit together, you never lose an event.

```
[Tx]: UPDATE orders SET status='PAID';
      INSERT INTO outbox(event) VALUES('OrderPaid');   <-- one atomic commit
   --------------------------------------------------
[Relay]: poll outbox  OR  CDC (Debezium tails the WAL)  --> publish to Kafka --> mark sent
```

Two relay implementations: **polling** the outbox table (simple, some latency) or **Change Data Capture** with Debezium tailing the transaction log (low latency, no polling load). The relay guarantees *at-least-once* delivery, so **consumers must be idempotent** (dedupe on event id). Combined with sagas, the outbox is what makes event-driven microservices actually reliable in production.

### Q20. [Coding] Implement the transactional outbox write and an idempotent consumer.

**Problem:** Atomically persist business state and an event, then consume that event exactly-once-effectively despite at-least-once delivery.

```java
// --- Producer side: single transaction writes state + outbox row ---
@Service
public class OrderService {
    private final OrderRepository orders;
    private final OutboxRepository outbox;
    private final ObjectMapper mapper;

    public OrderService(OrderRepository o, OutboxRepository ob, ObjectMapper m) {
        this.orders = o; this.outbox = ob; this.mapper = m;
    }

    @Transactional   // both saves commit atomically or not at all
    public void markPaid(UUID orderId) {
        Order order = orders.findById(orderId).orElseThrow();
        order.markPaid();
        orders.save(order);

        var event = new OutboxEvent(
            UUID.randomUUID(),                 // unique event id (for consumer dedupe)
            "Order", orderId.toString(),
            "OrderPaid", toJson(new OrderPaid(orderId)),
            Instant.now(), false);
        outbox.save(event);                    // SAME transaction
    }

    private String toJson(Object o) {
        try { return mapper.writeValueAsString(o); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
```

```java
// --- Consumer side: idempotent via processed-events table ---
@Component
public class OrderPaidConsumer {
    private final ProcessedEventRepository processed;
    private final ShippingService shipping;

    public OrderPaidConsumer(ProcessedEventRepository p, ShippingService s) {
        this.processed = p; this.shipping = s;
    }

    @KafkaListener(topics = "order-events")
    @Transactional
    public void handle(OutboxEnvelope msg) {
        // Dedupe: if we've seen this event id, skip. Unique constraint enforces it.
        if (processed.existsById(msg.eventId())) {
            return;                            // duplicate delivery -> no-op
        }
        shipping.schedule(msg.aggregateId());  // business side effect
        processed.save(new ProcessedEvent(msg.eventId(), Instant.now()));
    }
}
```

**Time:** O(1) per produced event and per consumed event (indexed lookups). **Space:** O(N) outbox + processed-events rows; prune both with a retention window. **Edge cases:** relay publishes a row twice (consumer dedupe handles it); consumer side effect succeeds but `processed.save` fails — keeping them in one transaction (or making `shipping.schedule` itself idempotent) prevents reprocessing; very high throughput — partition the outbox/processed tables or use CDC to avoid polling contention.

### Q21. [Coding] Implement client-side service discovery with health-aware, weighted load balancing.

**Problem:** In client-side discovery, given a list of service instances (each with a weight and a health flag), pick one instance per call so that traffic is distributed proportionally to weight, only healthy instances are chosen, and selection is O(1)/O(log n) — not O(n) per request on a hot path.

**Approach 1 — naive (filter + random):** filter healthy, pick uniformly at random. Simple but ignores weights and re-filters every call (O(n) per request).

**Approach 2 — weighted reservoir via cumulative weights + binary search** (used below): precompute a prefix-sum of weights over healthy instances, then binary-search a random point. O(log n) per pick, O(n) to (re)build when the instance set changes — which is rare relative to request volume.

```java
public final class WeightedLoadBalancer {

    public record Instance(String host, int port, int weight, boolean healthy) {}

    private volatile int[] cumulative;     // prefix sums of healthy weights
    private volatile Instance[] healthy;   // parallel array of healthy instances
    private volatile int total;            // sum of healthy weights

    /** Rebuild on instance-set changes (discovery refresh / health update). O(n). */
    public void refresh(List<Instance> instances) {
        var live = instances.stream()
                .filter(i -> i.healthy() && i.weight() > 0)
                .toArray(Instance[]::new);
        int[] cum = new int[live.length];
        int running = 0;
        for (int i = 0; i < live.length; i++) {
            running += live[i].weight();
            cum[i] = running;
        }
        // publish atomically (volatile writes) so choose() always sees a consistent set
        this.healthy = live;
        this.cumulative = cum;
        this.total = running;
    }

    /** Pick a healthy instance weighted by weight. O(log n). Thread-safe for reads. */
    public Optional<Instance> choose() {
        Instance[] live = healthy;          // snapshot the volatile refs
        int[] cum = cumulative;
        int sum = total;
        if (live == null || live.length == 0 || sum == 0) {
            return Optional.empty();        // no healthy capacity
        }
        int target = ThreadLocalRandom.current().nextInt(sum);  // [0, sum)
        int idx = lowerBound(cum, target);  // first cumulative > target
        return Optional.of(live[idx]);
    }

    // binary search: smallest index i where cum[i] > target
    private static int lowerBound(int[] cum, int target) {
        int lo = 0, hi = cum.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (cum[mid] <= target) lo = mid + 1; else hi = mid;
        }
        return lo;
    }
}
```

**Time:** `choose()` is O(log n); `refresh()` is O(n) but runs only on discovery/health changes. **Space:** O(n) for the two arrays.

**Edge cases:** all instances unhealthy → `choose()` returns empty so the caller can fail fast / trip a circuit breaker; weight 0 instances excluded (drain mode); concurrent refresh while choosing — the `volatile` snapshot ensures `choose()` reads one consistent (arrays, total) triple, never a half-updated set; integer overflow if total weight exceeds `Integer.MAX_VALUE` (use `long` for large fleets). In production you'd pair this with smooth weighted round-robin (Nginx's algorithm) to avoid bursts, plus outlier detection to auto-eject instances that start erroring — exactly what Envoy/the mesh does for you, which is why server-side discovery is now the default.

### Q22. [Practical] Your microservices are hard to debug — a slow request touches eight services. How do you build observability?

You need the three pillars working together, correlated by IDs:

1. **Distributed tracing** — propagate a trace context (W3C `traceparent` header) through every hop. With OpenTelemetry + Micrometer Tracing in Spring Boot 3, each service auto-creates spans and exports to a backend (Tempo/Jaeger). A trace shows the full call tree and which span is slow.
2. **Metrics** — RED (Rate, Errors, Duration) per service and per endpoint, plus the **golden signals** (latency, traffic, errors, saturation). Micrometer → Prometheus → Grafana. Set SLOs and alert on burn rate, not on raw CPU.
3. **Structured logs** — JSON logs with the `traceId`/`spanId` injected, shipped to a central store (Loki/ELK) so you can pivot from a slow trace straight to the logs of that exact request.

```
Request --traceparent--> [A]->[B]->[C]...   each span exported with shared traceId
   Grafana: click slow trace -> see span tree -> jump to logs filtered by traceId
```

In production I'd standardize OTel instrumentation via a shared library or the mesh, enforce trace propagation in contract tests, and add **exemplars** linking metrics to traces. The cultural piece matters too: define SLOs/SLIs per service and review error budgets — observability without SLOs is just dashboards nobody watches.

### Q23. [Theory] How do you handle distributed data queries and reporting across many service databases without a shared DB?

Several complementary techniques, chosen by access pattern:

- **API Composition** for small, ad-hoc joins at request time (discussed above).
- **CQRS read models / materialized views** built from events for frequent, complex queries — denormalize once, query cheaply.
- **A data lake / warehouse** for analytics and reporting: stream each service's data out (via CDC or events) into a central analytical store (Snowflake/BigQuery/lakehouse). Operational stores stay isolated; analysts query the warehouse. This keeps reporting from coupling your services.
- **Event sourcing** (where appropriate): the event log itself is the source of truth and can be replayed to build any projection.

The key principle: **don't let a reporting requirement justify a shared operational database.** Move data *out* asynchronously into a purpose-built store rather than letting analytics reach *into* operational schemas. Trade-off: every projection is eventually consistent and adds a pipeline to operate; only build the projections you actually query.

### Q24. [Practical] How do you do schema migrations and zero-downtime deploys when services and their databases change independently?

The rule is **backward-and-forward-compatible, multi-phase migrations** — never a breaking schema change in one shot, because old and new code run simultaneously during a rolling deploy.

Use the **expand–migrate–contract** (parallel-change) pattern for, say, renaming a column:
1. **Expand**: add the new column; code writes both old and new, reads old. Deploy. (DB is now ahead of code, but old code still works.)
2. **Migrate**: backfill the new column from the old; switch code to read new, still write both. Deploy.
3. **Contract**: stop writing the old column; later, drop it. Deploy, then run the destructive migration last.

```
v1 code  ──┐
           ├─ both run during rollout ─> schema must satisfy BOTH
v2 code  ──┘
Expand(add col) -> deploy -> Backfill -> deploy(read-new) -> Contract(drop old)
```

Tooling: Flyway/Liquibase with migrations gated so destructive steps are separate, deliberate releases. Combine with **blue-green** or **canary** deploys and feature flags to decouple "deploy the code" from "turn on the behavior," enabling instant rollback. Never run a `DROP COLUMN`/`NOT NULL` add in the same release that ships the code depending on it.

### Q25. [Theory] What are the security implications unique to microservices, and how do you address them?

The attack surface multiplies: every service is a network endpoint, and east-west (service-to-service) traffic is now exposed. Key concerns and mitigations:

- **Service-to-service authentication/encryption**: assume the network is hostile (zero-trust). Use **mTLS** for mutual auth and in-transit encryption — a service mesh can enforce this transparently.
- **Token propagation / authorization**: don't re-authenticate the user at every hop; propagate a signed token. Use OAuth2/OIDC with short-lived JWTs, and the **token exchange** pattern so a service calls downstream with a scoped token rather than the user's full credentials. Validate `aud`/`scope` at each service.
- **Secret management**: secrets in a vault (HashiCorp Vault, cloud secret managers), injected at runtime, rotated automatically — never in images or config repos.
- **Edge vs internal**: terminate user auth at the gateway/BFF, but **don't trust the edge alone** — internal services must still authorize (defense in depth).
- **Increased blast radius of supply chain**: many services means many dependencies; scan images (SCA), sign artifacts (Sigstore/cosign), and enforce least-privilege network policies.

The summary: microservices push you toward **zero-trust** — authenticate and authorize every call, encrypt every hop, and assume any internal service could be compromised.

### Q26. [Practical] A team proposes splitting a 3-month-old startup product into 12 microservices. What's your guidance? (When NOT to use microservices.)

I'd push back and recommend a **modular monolith** first. Microservices solve *organizational* and *scaling* problems you don't have yet, while imposing *operational* costs you can't yet absorb. At a 3-month-old product:

- **Boundaries are unknown.** The domain is still churning; premature service boundaries become expensive to move (a refactor that's a method-move in a monolith becomes a cross-service contract change + data migration). Get the bounded contexts right *in a monolith* with well-enforced module boundaries first.
- **No operational maturity.** Microservices presuppose mature CI/CD, observability, on-call, and infra-as-code. Without them you get a distributed monolith — all the cost, none of the benefit.
- **Team size.** With one or two teams, you don't need independent deployability; you need to ship features. Conway's Law says architecture mirrors org structure — a small org should have a small-N architecture.

When microservices *are* warranted: distinct scaling profiles (one component needs 50x the others), genuine team autonomy at scale (many teams blocked by a shared deploy), strict fault-isolation needs, or technology heterogeneity that can't coexist. My guidance: build a **modular monolith** with clear module boundaries and a clean event/interface layer, instrument it well, and extract services (strangler fig) *only* when a specific, measured pain — scaling, deploy contention, or team friction — justifies a specific split. Amazon, Netflix, and Uber all extracted services from monoliths under concrete pressure; they didn't start with hundreds.

---

## 🔴 Expert (15+ yrs)

### Q27. [Theory] How does Conway's Law shape microservice architecture, and what is the Inverse Conway Maneuver?

Conway's Law observes that organizations design systems that mirror their communication structures. If you have four teams, you'll get (roughly) four-part systems, and the integration points will fall on the team boundaries. In microservices this is decisive: service boundaries that cut across team boundaries create constant cross-team coordination, killing the independent-deployability benefit.

The **Inverse Conway Maneuver** is deliberately structuring teams to produce the architecture you want — if you want loosely coupled services around bounded contexts, organize *stream-aligned teams* each owning a context end-to-end (the *Team Topologies* model: stream-aligned, platform, enabling, complicated-subsystem teams). The corollary for staff/architect-level decisions: you can't fix a bad architecture without addressing the org structure that produced it. I've seen "microservice" initiatives fail because three teams still shared ownership of one service's deploy — the org didn't change, so the coupling didn't either.

### Q28. [Practical] You inherit a "distributed monolith." How do you diagnose it and what's your remediation strategy?

A distributed monolith has the costs of distribution (network, ops) without the benefits (independent deploy/scale). **Diagnosis signals:** services must be deployed together in lock-step; a shared database or shared library that forces synchronized releases; long synchronous call chains where one service down breaks many; changes routinely span multiple repos/teams; no service can be reasoned about in isolation.

**Remediation strategy (what I'd actually do):**
1. **Map the coupling** — build a dependency graph from traces and CI data; find the synchronous chains and shared-data hotspots. Measure deploy coupling (which services always ship together).
2. **Fix data coupling first** — break shared databases via database-per-service, using outbox/CDC to decouple writes from reads; introduce read models so services stop reaching into each other's stores.
3. **Convert synchronous chains to async** where the business allows — replace request/response cascades with event-driven flows and sagas to restore fault isolation.
4. **Re-align boundaries to bounded contexts** — sometimes the right move is to *merge* over-split services back together (a modular monolith for a context) rather than split further. "Right-sizing" beats "more services."
5. **Address the org** — apply the inverse Conway maneuver so team ownership matches the boundaries.

Sequence matters: I'd stop the bleeding (data + sync coupling) before any cosmetic re-slicing, and I'd resist the temptation to rewrite — incremental, measured de-coupling beats a second big-bang.

### Q29. [Theory] Discuss consistency models and the CAP/PACELC implications of your microservice data design.

CAP says under a network **P**artition you must choose **C**onsistency or **A**vailability. Within a single service's datastore you may pick CP (e.g., a quorum DB) or AP (e.g., Dynamo-style); *across* services you're effectively running an AP system glued by events, so you live with **eventual consistency** by design.

**PACELC** is the more useful lens for architects: it extends CAP by noting that *even when there's no partition (E, else)* you trade **L**atency vs **C**onsistency. Synchronous strong-consistency calls across services add latency and reduce availability (the multiplicative-availability problem); async eventual consistency gives you latency and availability but demands you handle staleness, ordering, and duplicates.

At staff level the decision is *per data flow*: where money or safety is involved, pay for stronger consistency (synchronous confirmation, or a saga with semantic locks and reconciliation); where a slightly stale read is fine (a product view count), embrace eventual consistency and CQRS. The architectural skill is knowing **which invariants are truly cross-service** — those few are where you spend your consistency budget (sagas, idempotency, reconciliation jobs), and everything else gets cheap eventual consistency.

### Q30. [Practical] Real-world case: walk through how a large e-commerce company evolved its checkout from monolith to microservices, and the lessons.

A representative composite (mirroring publicly documented journeys at Amazon, Netflix, and Uber): the company started with a Rails/Java monolith. Checkout, inventory, pricing, and payments all lived together; a single deploy gated every team, and Black Friday load forced them to scale the *entire* monolith to handle the *checkout* spike — wasteful and risky.

Evolution: they put an **API gateway** in front (strangler fig), then extracted the highest-pain capability first — **pricing/inventory** (distinct scaling profile) — giving it its own datastore fed by **CDC** from the monolith during transition, behind an **anti-corruption layer**. Checkout became a **saga orchestrator** coordinating cart, payment, and inventory with compensations; the **outbox pattern** guaranteed reliable events; **idempotency keys** prevented double-charges on retries. They invested heavily in **OpenTelemetry tracing** *before* splitting, because they (correctly) feared debugging a fan-out.

**Lessons (the genuinely transferable ones):**
- Extract under *measured* pressure, not for fashion — they split what hurt (scaling, deploy contention), not everything.
- Observability and CI/CD maturity *precede* decomposition, not follow it.
- The hard part is **data**, not code — CDC, outbox, sagas, and reconciliation jobs were the bulk of the work.
- Some splits were wrong and got merged back; "right-sizing" was iterative.
- Org structure changed in lockstep (inverse Conway) — autonomous teams owning contexts end-to-end is what made independent deploys real.

### Q31. [Behavioral] As an architect, two senior engineers are in a heated disagreement: one insists on choreography sagas for loose coupling, the other on orchestration for observability. How do you resolve it?

I'd reframe from "who's right" to "what does *this* workflow need," because both are defensible and the answer is contextual. First I'd make the disagreement concrete: which specific sagas, how many steps, who needs to understand the flow, what's the failure/compensation complexity, and what are the debuggability requirements from on-call.

Then I'd propose a decision framework rather than a blanket rule: short, simple, genuinely-independent flows lean choreography (low coupling, no orchestrator to operate); complex, multi-step, business-critical flows with compensation logic and audit needs lean orchestration (explicit state, easier to monitor and change). I'd suggest we can use **both** in the same system for different workflows — this isn't a one-winner decision.

To de-escalate, I'd acknowledge the valid concern under each position (coupling risk vs operational opacity), get them to agree on the *criteria*, and pilot the contested flow with a spike if needed. I'd document the decision in an ADR with the trade-offs so it's defensible and revisitable. The meta-point I'd want both to internalize: at our scale, senior engineers should converge on *reversible, well-reasoned* decisions over winning arguments — and as the architect, my job is to protect the decision *process*, not to impose my own preference.

### Q32. [Theory] When should you re-merge microservices back into a monolith or modular monolith, and how do you know your decomposition went too far?

You've over-decomposed when the *cost* of distribution exceeds its *benefit* for a given set of services. Concrete signals: nanoservices that do almost nothing and exist only to make a network call; services that are *always* deployed and changed together (false independence); chatty synchronous call patterns where two services round-trip constantly (they share a bounded context and should be one); shared data forced through awkward APIs; and an ops burden disproportionate to the service's value.

The remedy isn't ideological — it's **right-sizing**. Re-merge services whose coupling reveals they belong to the same bounded context, ideally into a **modular monolith** (one deployable, strong internal module boundaries) for that context, which keeps logical separation while removing network/data/ops overhead. This is increasingly mainstream in 2024–2026 ("monolith-first," Shopify's modular monolith, Amazon Prime Video's well-known re-consolidation of a video-monitoring pipeline for cost/perf). The expert stance: the unit of independence is the **bounded context and the team that owns it**, not the smallest possible service — and architecture should be continuously re-evaluated, with merges as legitimate as splits.

---

## ✅ Key Takeaways

- **Independent deployability, not size,** defines a microservice. If services must deploy together, you have a distributed monolith — the worst of both worlds.
- **Align boundaries with DDD bounded contexts and teams** (Conway's Law). Get boundaries right in a modular monolith before extracting services.
- **Database-per-service is foundational**; the shared database is the cardinal anti-pattern. You trade JOINs and ACID for sagas, outbox, idempotency, and eventual consistency.
- **Reliability patterns are mandatory, not optional**: timeouts + retries-with-backoff + circuit breakers, idempotency keys, the transactional outbox, and idempotent consumers.
- **Observability (tracing + metrics + structured logs, correlated by trace id) precedes decomposition.** You cannot operate a fan-out you can't see.
- **Contract testing (Pact) enables independent deployment with confidence**; keep E2E tests minimal at the top of the pyramid.
- **Security is zero-trust**: mTLS east-west, short-lived propagated tokens with scope checks, secrets in a vault, defense in depth beyond the edge.
- **Right-size continuously**: merging over-split services back into a modular monolith is a legitimate, mature move, not a failure.

## ⚠️ Common Pitfalls

- **Adopting microservices for resume/fashion reasons** before having CI/CD, observability, and team autonomy — guaranteeing a distributed monolith.
- **Sharing a database** across services, coupling their schemas and forcing lock-step deploys.
- **Synchronous call chains** that destroy fault isolation and multiply latency/availability (4 × 99.9% ≈ 99.6%).
- **The dual-write problem**: updating the DB and publishing an event in two steps without an outbox, silently losing events on crash.
- **Non-idempotent consumers** under at-least-once delivery, causing duplicate side effects (double charges, double shipments).
- **Breaking API changes without versioning or contract tests**, or a destructive schema migration shipped with the code that depends on it (skip expand–migrate–contract at your peril).
- **Distributed transactions via 2PC** instead of sagas — fragile, blocking, and poorly supported across heterogeneous stores.
- **Inverted test pyramid**: leaning on slow, flaky end-to-end tests, recreating the coordination bottleneck microservices were meant to remove.
- **Nanoservices / premature decomposition** before the domain stabilizes, making boundary changes ruinously expensive.

## 📚 Further Reading

- *Building Microservices, 2nd ed.* — Sam Newman (O'Reilly). The definitive practitioner's guide to decomposition, integration, and operations.
- *Microservices Patterns* — Chris Richardson (Manning). Canonical catalog of saga, CQRS, outbox, API composition; see also microservices.io.
- *Domain-Driven Design* — Eric Evans, and *Implementing DDD* — Vaughn Vernon. Bounded contexts, anti-corruption layers, ubiquitous language.
- *Team Topologies* — Skelton & Pais. Conway's Law and the inverse maneuver applied to team and service design.
- *Release It!, 2nd ed.* — Michael Nygard. Circuit breakers, bulkheads, and stability patterns for production distributed systems.
- [microservices.io](https://microservices.io) (Richardson's pattern language) and the [Spring Cloud](https://spring.io/projects/spring-cloud) / [Resilience4j](https://resilience4j.readme.io) docs for current Java/Spring Boot 3 implementations.
