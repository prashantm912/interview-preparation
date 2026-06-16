# Spring Cloud — Interview Preparation Guide

Spring Cloud is a suite of tools built on top of Spring Boot that solves the recurring problems of distributed systems — centralized configuration, service discovery, intelligent routing, client-side load balancing, resilience, distributed tracing, and event-driven messaging. This guide takes you from the fundamentals to staff-level architectural trade-offs, current through 2026 (Spring Boot 3.x / Spring Cloud 2024.x–2025.x "Northfields/Oakwood" release trains, Java 17/21).

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

### Q1. [Theory] What is Spring Cloud and how does it relate to Spring Boot?

Spring Boot makes it trivial to build a single, self-contained, production-ready service (auto-configuration, embedded server, actuator). Spring Cloud is a layer **on top of** Spring Boot that addresses the problems you only encounter when you have *many* services talking to each other: where do they find configuration, how do they find each other, how do calls survive partial failures, and how do you trace a request across them.

The key conceptual point in an interview: Spring Cloud is **not a framework you adopt wholesale**. It is a curated set of independent libraries (Config, Gateway, OpenFeign, LoadBalancer, Resilience4j integration, Stream, Micrometer Tracing) governed by a **release train** — a BOM (Bill of Materials) that pins mutually compatible versions. You pick the pieces you need. Spring Cloud versions are aligned to Spring Boot: Spring Cloud 2023.x/2024.x require Spring Boot 3.x (Jakarta EE namespace, Java 17+).

### Q2. [Theory] What is the role of the Spring Cloud release train / BOM?

Distributed-systems libraries evolve at different speeds, but they must be mutually compatible (e.g., Gateway, LoadBalancer, and the tracing bridge must agree on Reactor and Micrometer versions). The **release train** is a named BOM (`spring-cloud-dependencies`, e.g., `2024.0.0`) that pins one tested combination. You import it with `dependencyManagement` and then declare individual starters **without versions**.

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.springframework.cloud</groupId>
      <artifactId>spring-cloud-dependencies</artifactId>
      <version>2024.0.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

The trade-off: you should generally not override individual Spring Cloud component versions, because you lose the compatibility guarantee the train provides.

### Q3. [Theory] What is service discovery and why can't you just hardcode hostnames?

In a microservice deployment, instances come and go: autoscaling adds replicas, deploys recycle pods, failures kill instances, and IPs change. Hardcoding `http://order-service-3:8080` is brittle and impossible to scale. **Service discovery** introduces a registry where each instance registers itself on startup and de-registers on shutdown; clients query the registry by *logical name* (`order-service`) and get back the current set of healthy instances.

```
   ┌──────────────┐     register/heartbeat    ┌──────────────┐
   │ order-service│ ────────────────────────► │   Registry   │
   │ (3 replicas) │                            │ (Eureka/etc.)│
   └──────────────┘                            └──────────────┘
                                                      ▲
   ┌──────────────┐     "where is order-service?"     │
   │payment-service│ ────────────────────────────────┘
   └──────────────┘     ◄── [10.0.1.5, 10.0.1.9, ...]
```

There are two styles: **client-side discovery** (client gets the list and picks an instance — Eureka + Spring Cloud LoadBalancer) and **server-side discovery** (an infra component like Kubernetes Service or a load balancer hides instances behind a VIP).

### Q4. [Practical] How do you externalize configuration following 12-factor principles in Spring Cloud?

The III factor of the [12-factor app](https://12factor.net) says: **store config in the environment**, strictly separated from code, so the same build artifact runs in dev, staging, and prod. In Spring Boot/Cloud this means:

- Never bake environment-specific values into the jar. Use `application.yml` for defaults only.
- Override via environment variables, command-line args, or a config server. Spring Boot's `Environment` abstraction merges all property sources with a well-defined precedence (command-line > env vars > config server > profile files > `application.yml`).
- Use **profiles** (`spring.profiles.active=prod`) for per-environment property files (`application-prod.yml`).
- Keep secrets out of plain config — use Vault, Kubernetes Secrets, or AWS Secrets Manager mounted as env vars.

In production I'd combine a **Spring Cloud Config Server** (or Kubernetes ConfigMaps) for non-secret config and a dedicated secrets backend for credentials, so the artifact is immutable and config is injected at runtime.

### Q5. [Coding] Write a minimal OpenFeign client to call another service by its logical name.

**Problem:** `order-service` needs to fetch customer data from `customer-service`. Write a declarative HTTP client.

```java
// 1. Enable Feign in your Spring Boot app
@SpringBootApplication
@EnableFeignClients
public class OrderServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}

// 2. Declare the client interface — "customer-service" is the logical
//    discovery name; Spring Cloud LoadBalancer resolves it to a real instance.
@FeignClient(name = "customer-service")
public interface CustomerClient {

    @GetMapping("/customers/{id}")
    CustomerDto getCustomer(@PathVariable("id") String id);
}

// 3. Inject and use it like any bean
@Service
public class OrderService {
    private final CustomerClient customerClient;

    public OrderService(CustomerClient customerClient) {
        this.customerClient = customerClient;
    }

    public Order enrich(Order order) {
        CustomerDto c = customerClient.getCustomer(order.getCustomerId());
        order.setCustomerName(c.name());
        return order;
    }
}
```

**Edge cases:** A 404 from the remote service throws `FeignException.NotFound` — decide whether to translate it to a domain exception or `Optional`. If `customer-service` has no healthy instances, you get a load-balancer exception; wrap the call in a circuit breaker (see Q14). **Time complexity** is dominated by network I/O, not CPU — design for latency and failure, not Big-O here.

### Q6. [Theory] What is the difference between client-side and server-side load balancing?

**Server-side** load balancing puts a dedicated component (hardware LB, NGINX, AWS ALB, Kubernetes `Service`/kube-proxy) between client and servers; the client knows one VIP and the LB chooses the backend. **Client-side** load balancing puts the algorithm *inside the calling service*: the client fetches the full instance list from discovery and picks one itself (Spring Cloud LoadBalancer does this; the older Netflix Ribbon is removed).

```
SERVER-SIDE:   client ─► [ LB / VIP ] ─► instance{1..n}   (one network hop to LB)
CLIENT-SIDE:   client(has list) ─────► chosen instance     (no extra hop)
```

Client-side avoids an extra network hop and a single chokepoint, and lets you do smart per-call routing (zone affinity, weighting). Server-side is simpler operationally and is the natural model in Kubernetes. Many teams run client-side LB *within* a cluster and server-side at the edge.

---

## 🟡 Intermediate (3–7 yrs)

### Q7. [Theory] How does Spring Cloud Config Server work, and how does `@RefreshScope` enable runtime config changes?

Config Server is a standalone Spring Boot app that serves configuration from a backend (Git is the default and most common; also Vault, JDBC, filesystem). Clients fetch their config at startup by name/profile/label: `GET /{application}/{profile}/{label}` (e.g., `/order-service/prod/main`). The server resolves property precedence and returns a merged property source the client injects into its `Environment`.

```
              ┌────────────┐   git pull    ┌──────────┐
              │Config Server│ ◄──────────── │ Git repo │
              └─────┬───────┘               └──────────┘
        GET /order-service/prod  │
   ┌────────────┐                ▼
   │order-service│ ◄── merged properties (application.yml + order-service-prod.yml)
   └────────────┘
```

By default config is read once at boot. **`@RefreshScope`** makes a bean lazily recreated the next time it's accessed after a `/actuator/refresh` POST (or a broadcast via Spring Cloud Bus). The bean's `@Value`/`@ConfigurationProperties` are re-bound to the latest config. The trade-off: only `@RefreshScope` beans (and `@ConfigurationProperties`) refresh; things bound at construction elsewhere (e.g., a `DataSource` connection pool) usually need a restart unless specifically handled.

```java
@RefreshScope
@Component
public class PricingService {
    @Value("${pricing.surge-multiplier:1.0}")
    private double surgeMultiplier;   // re-read after /actuator/refresh
}
```

### Q8. [Practical] You changed a value in Git but 40 service instances didn't pick it up. How do you propagate config changes to a fleet?

Hitting `/actuator/refresh` on one instance only refreshes that one. For a fleet you have three production approaches:

1. **Spring Cloud Bus** — connects all instances over a message broker (Kafka/RabbitMQ). A single `POST /actuator/busrefresh` (or a Git webhook hitting the Config Monitor endpoint) broadcasts a `RefreshRemoteApplicationEvent` to every instance. This is the classic Spring Cloud answer.
2. **Kubernetes-native** — mount config as a ConfigMap; use a sidecar/reloader (e.g., `stakater/Reloader`) to roll pods, or use Spring Cloud Kubernetes' ConfigMap watch to trigger refresh automatically.
3. **Just redeploy** — for immutable-infrastructure shops, the cleanest answer is often "config change = new deploy," avoiding the complexity of live refresh entirely.

In production I'd default to option 3 for anything that can tolerate a rolling restart (it's auditable and avoids partial-state bugs), and reserve Bus/live-refresh for feature flags and tuning knobs (timeouts, thresholds) where a restart is too disruptive. **Security note:** the refresh/bus actuator endpoints must be secured — an unauthenticated `busrefresh` is a denial-of-service and config-poisoning vector.

### Q9. [Theory] Explain Spring Cloud Gateway's architecture: routes, predicates, and filters.

Spring Cloud Gateway (the reactive, Netty/WebFlux-based successor to Zuul 1) routes requests using three building blocks:

- **Route** — the atomic unit: an ID, a destination URI, a set of predicates, and a set of filters.
- **Predicate** — a condition that must be true for the route to match (path, method, header, host, query param, time before/after).
- **Filter** — logic that runs on the request and/or response (rewrite path, add headers, strip prefix, rate limit, circuit break, retry).

```
request ─► [Predicate match?] ─► [pre-filters] ─► proxy to URI ─► [post-filters] ─► response
              path=/api/orders/**          add header, rate-limit         add CORS, trace id
```

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: order-route
          uri: lb://order-service          # lb:// uses discovery + LoadBalancer
          predicates:
            - Path=/api/orders/**
          filters:
            - StripPrefix=1                 # /api/orders -> /orders upstream
            - name: CircuitBreaker
              args:
                name: orderCB
                fallbackUri: forward:/fallback/orders
```

Because it's reactive (non-blocking), Gateway handles high concurrency with few threads — but it means your custom filters must also be non-blocking; a blocking call inside a filter starves the Netty event loop. Note: as of recent release trains the artifact is `spring-cloud-starter-gateway-server-webflux` (and an MVC/servlet variant exists for blocking stacks).

### Q10. [Coding] Implement a custom Spring Cloud Gateway global filter that adds a correlation ID to every request.

**Problem:** Every inbound request should get an `X-Correlation-Id` header (generated if missing) that propagates downstream and into logs.

```java
@Component
public class CorrelationIdGlobalFilter implements GlobalFilter, Ordered {

    public static final String HEADER = "X-Correlation-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String correlationId = exchange.getRequest()
                .getHeaders()
                .getFirst(HEADER);

        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        final String id = correlationId;
        // Mutate the request so the header is forwarded upstream
        ServerHttpRequest mutated = exchange.getRequest().mutate()
                .header(HEADER, id)
                .build();

        // Also echo it back on the response for clients
        exchange.getResponse().getHeaders().add(HEADER, id);

        return chain.filter(exchange.mutate().request(mutated).build());
    }

    @Override
    public int getOrder() {
        // Run early so downstream filters/logging see the id
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
```

**Edge cases:** Respect an existing correlation ID from an upstream caller (don't overwrite it) so you preserve end-to-end traceability. The filter is non-blocking and allocation-light. **Complexity:** O(1) per request. Pair this with Micrometer Tracing (Q18) so the same ID lands in your spans.

### Q11. [Practical] How do you implement rate limiting at the gateway, and what algorithm does Spring Cloud Gateway use?

Spring Cloud Gateway ships a `RequestRateLimiter` filter backed by **Redis** implementing a **token-bucket** algorithm. You configure `replenishRate` (tokens added per second = steady-state allowed rate), `burstCapacity` (bucket size = max burst), and `requestedTokens` (cost per request). A `KeyResolver` decides what you limit *per* — per user, per API key, per IP.

```yaml
filters:
  - name: RequestRateLimiter
    args:
      redis-rate-limiter.replenishRate: 100      # 100 req/s sustained
      redis-rate-limiter.burstCapacity: 200      # allow short bursts to 200
      redis-rate-limiter.requestedTokens: 1
      key-resolver: "#{@apiKeyResolver}"
```

```java
@Bean
KeyResolver apiKeyResolver() {
    return exchange -> Mono.just(
        exchange.getRequest().getHeaders().getFirst("X-API-Key"));
}
```

**Trade-offs:** Redis makes the limit *global* across all gateway replicas (correct for distributed enforcement) but adds a dependency and a per-request Redis round trip. For coarse local limits you can use Resilience4j's in-memory `RateLimiter`, but it's per-instance, so N gateways = N× the limit. In production I'd use Redis-backed limiting for tenant/quota enforcement and return `429 Too Many Requests` with a `Retry-After` header. **Security:** rate limiting is a primary defense against credential-stuffing and scraping; key on something the attacker can't trivially rotate (authenticated user > API key > IP).

### Q12. [Theory] Compare Eureka and Consul as discovery backends. When would you pick each (or neither)?

**Eureka** (Netflix) is an AP-leaning, eventually-consistent registry purpose-built for discovery. It favors availability: under network partition it keeps serving the last-known registry (self-preservation mode) rather than aggressively evicting instances — good for "stale data beats no data" discovery. It's simple, JVM-centric, and integrates seamlessly with Spring Cloud.

**Consul** (HashiCorp) is a CP-leaning (Raft-based) system that also does KV config, health checking, and service mesh (Consul Connect). It's multi-platform (not JVM-bound) and stronger on consistency and ACLs.

| | Eureka | Consul |
|---|---|---|
| CAP lean | AP (availability) | CP (consistency) |
| Extra features | discovery only | KV store, health, mesh, multi-DC |
| Ecosystem | JVM/Spring | polyglot |
| Ops | self-preservation quirks | Raft quorum to operate |

**When neither:** if you run on **Kubernetes**, the platform already provides discovery (DNS + `Service`) and config (ConfigMap/Secret). Running Eureka inside k8s is usually redundant. Pick Eureka for simple Spring-only fleets on VMs; Consul when you need polyglot discovery + config + mesh; neither when k8s already solves it.

### Q13. [Coding] Write an OpenFeign client with a custom error decoder and a fallback.

**Problem:** Translate HTTP errors to domain exceptions and provide a graceful degradation path.

```java
@FeignClient(
    name = "inventory-service",
    configuration = InventoryFeignConfig.class,
    fallbackFactory = InventoryFallbackFactory.class)   // requires CircuitBreaker integration
public interface InventoryClient {
    @GetMapping("/stock/{sku}")
    StockDto getStock(@PathVariable String sku);
}

// Custom error decoder: map status codes to domain exceptions
public class InventoryFeignConfig {
    @Bean
    public ErrorDecoder errorDecoder() {
        return (methodKey, response) -> switch (response.status()) {
            case 404 -> new SkuNotFoundException(methodKey);
            case 429 -> new RetryableException(
                    response.status(), "rate limited", response.request().httpMethod(),
                    (Long) null, response.request());
            default  -> new InventoryUnavailableException(response.status());
        };
    }
}

// Fallback runs when the circuit is open or the call fails
@Component
public class InventoryFallbackFactory implements FallbackFactory<InventoryClient> {
    private static final Logger log = LoggerFactory.getLogger(InventoryFallbackFactory.class);

    @Override
    public InventoryClient create(Throwable cause) {
        return sku -> {
            log.warn("inventory fallback for sku={} cause={}", sku, cause.toString());
            return StockDto.unknown(sku);   // degrade: treat as "stock unknown"
        };
    }
}
```

Enable Feign + circuit breaker integration:

```yaml
spring:
  cloud:
    openfeign:
      circuitbreaker:
        enabled: true
```

**Edge cases:** Throwing `RetryableException` from the decoder lets Feign's `Retryer` retry idempotent calls — never auto-retry non-idempotent POSTs. The fallback should return *safe* defaults; returning fake "in stock" data could let you oversell. **Complexity:** negligible CPU; the design value is bounded failure behavior.

### Q14. [Theory] Explain the Resilience4j circuit breaker states and key tuning parameters.

A circuit breaker prevents a failing dependency from cascading by "tripping" after too many failures, failing fast instead of piling up blocked threads. Resilience4j models three states (plus half-open):

```
            failureRate > threshold
   CLOSED ───────────────────────────► OPEN
     ▲                                   │  wait waitDurationInOpenState
     │  enough successes in HALF_OPEN    ▼
     └──────────── HALF_OPEN ◄───────────┘
                (limited trial calls)
```

- **CLOSED** — calls pass through; failures recorded in a sliding window.
- **OPEN** — calls fail immediately (fallback) without touching the dependency, giving it time to recover.
- **HALF_OPEN** — after a wait, a limited number of trial calls are allowed; if they mostly succeed → CLOSED, else → OPEN.

Key knobs: `slidingWindowType` (COUNT_BASED vs TIME_BASED), `slidingWindowSize`, `failureRateThreshold` (%), `slowCallRateThreshold` + `slowCallDurationThreshold` (slow calls count as failures), `waitDurationInOpenState`, and `permittedNumberOfCallsInHalfOpenState`. Resilience4j is the standard choice now that Hystrix is end-of-life; it's lightweight, functional, and integrates with Micrometer for metrics.

### Q15. [Coding] Apply Resilience4j circuit breaker, retry, and a fallback to a service method.

**Problem:** Make a remote call resilient: retry transient failures, trip a circuit breaker on sustained failure, and fall back gracefully.

```java
@Service
public class QuoteService {

    private final RestClient restClient;

    public QuoteService(RestClient restClient) {
        this.restClient = restClient;
    }

    @CircuitBreaker(name = "pricing", fallbackMethod = "fallbackQuote")
    @Retry(name = "pricing")          // retry BEFORE the breaker counts a failure
    public Quote getQuote(String sku) {
        return restClient.get()
                .uri("lb://pricing-service/quotes/{sku}", sku)
                .retrieve()
                .body(Quote.class);
    }

    // Fallback signature: original args + the Throwable
    private Quote fallbackQuote(String sku, Throwable t) {
        return Quote.cached(sku);     // last-known or default
    }
}
```

```yaml
resilience4j:
  retry:
    instances:
      pricing:
        max-attempts: 3
        wait-duration: 200ms
        enable-exponential-backoff: true
        exponential-backoff-multiplier: 2
        retry-exceptions:
          - java.io.IOException
  circuitbreaker:
    instances:
      pricing:
        sliding-window-type: COUNT_BASED
        sliding-window-size: 20
        failure-rate-threshold: 50
        wait-duration-in-open-state: 10s
        permitted-number-of-calls-in-half-open-state: 3
```

**Edge case / ordering gotcha:** annotation order matters. By default Resilience4j applies them so that **Retry wraps CircuitBreaker** is *not* the default — the default aspect order is `Retry > CircuitBreaker > RateLimiter > TimeLimiter > Bulkhead`, meaning Retry is outermost. You usually want retries to happen and only count as a single breaker failure once exhausted; verify with metrics. Never retry non-idempotent operations. **Complexity:** bounded by `max-attempts × per-call latency` — cap it so total latency stays within your SLA.

### Q16. [Practical] What's the difference between Resilience4j's bulkhead, rate limiter, and time limiter, and when do you use each?

These solve *different* failure modes; senior candidates should not conflate them:

- **Bulkhead** — limits **concurrent** calls to a dependency so one slow dependency can't exhaust your whole thread pool. Two flavors: `SemaphoreBulkhead` (limits concurrent permits) and `ThreadPoolBulkhead` (isolates calls on a separate bounded pool + queue). Named after ship compartments: a leak in one doesn't sink the ship.
- **RateLimiter** — limits **total throughput** over time (e.g., 50 calls/sec), regardless of concurrency — used to respect a downstream's quota or protect it.
- **TimeLimiter** — caps **per-call duration**, cancelling calls that exceed a timeout (works with `CompletableFuture`/reactive types).

```
Bulkhead   ─► "no more than 10 in flight at once"
RateLimiter─► "no more than 50 per second"
TimeLimiter─► "give up on any single call after 2s"
```

In production for a critical downstream I combine all four: TimeLimiter (bound latency) → Bulkhead (cap concurrency) → CircuitBreaker (fail fast on sustained failure) → Retry (recover transient blips) → fallback. Misuse example: using a RateLimiter when you actually have a *concurrency* (thread-exhaustion) problem won't help.

### Q17. [Theory] What are Spring Cloud Stream binders and the destination binding model?

Spring Cloud Stream is an abstraction over messaging that lets you write broker-agnostic event handlers using `java.util.function` beans (`Supplier`, `Function`, `Consumer`). A **binder** is the broker-specific adapter (Kafka, RabbitMQ, Kafka Streams, Pulsar). Your code deals with logical **bindings**; the binder maps them to physical destinations (Kafka topics, Rabbit exchanges/queues).

```java
// A Consumer<T> bean automatically becomes an inbound binding "processOrder-in-0"
@Bean
public Consumer<OrderEvent> processOrder() {
    return event -> orderService.handle(event);
}

// A Function<T,R> consumes from -in-0 and produces to -out-0
@Bean
public Function<OrderEvent, ShipmentEvent> toShipment() {
    return order -> new ShipmentEvent(order.id());
}
```

```yaml
spring:
  cloud:
    function:
      definition: processOrder;toShipment
    stream:
      bindings:
        processOrder-in-0:
          destination: orders          # Kafka topic / Rabbit exchange
          group: order-processor       # consumer group for load sharing
        toShipment-out-0:
          destination: shipments
```

The big win is **portability and consumer groups**: switching Kafka↔Rabbit is largely a dependency + config change, and `group` gives you competing-consumer load sharing with at-least-once delivery. Trade-off: the abstraction hides broker-specific features, so for advanced Kafka semantics you sometimes drop to native APIs.

---

## 🟠 Advanced (8–12 yrs)

### Q18. [Theory] How does distributed tracing work with Micrometer Tracing + OpenTelemetry in Spring Boot 3, and what changed from Spring Cloud Sleuth?

In Spring Boot 2 / Spring Cloud, **Sleuth** instrumented your app and propagated trace context. In Spring Boot 3, Sleuth is **gone**, replaced by **Micrometer Observation API + Micrometer Tracing**, which bridges to either Brave (Zipkin) or **OpenTelemetry** (OTel). The Observation API is a single instrumentation point that emits *both* metrics and traces, avoiding double instrumentation.

```
   Service A                         Service B
 ┌──────────┐  trace+span headers   ┌──────────┐
 │ Observation│ ───(W3C traceparent)──► Observation│
 └────┬─────┘                       └────┬─────┘
      │ spans                            │ spans
      ▼                                  ▼
   ┌─────────────── OTel Collector ───────────────┐
   └───────► Tempo / Jaeger / Zipkin (backend) ────┘
```

Key points for an interview:
- Context propagates via **W3C Trace Context** (`traceparent` header) by default in the OTel bridge; legacy B3 headers (Brave/Zipkin) are also supported for interop.
- A **trace** spans the whole request; each service/operation is a **span** with a parent-child relationship; `traceId` and `spanId` auto-appear in logs via the logging pattern (MDC).
- Use the OTel **Collector** as a vendor-neutral pipeline so you can swap backends (Jaeger, Tempo, Honeycomb, Datadog) without changing app code.
- **Sampling** is critical: head-based sampling (e.g., 10%) controls cost; tail-based sampling (in the Collector) lets you keep all error/slow traces.

```xml
<dependency><groupId>io.micrometer</groupId><artifactId>micrometer-tracing-bridge-otel</artifactId></dependency>
<dependency><groupId>io.opentelemetry</groupId><artifactId>opentelemetry-exporter-otlp</artifactId></dependency>
```

### Q19. [Practical] A request crosses Gateway → service A → (async Kafka) → service B and the trace breaks at the Kafka boundary. How do you debug and fix it?

This is a classic **context-propagation-across-async-boundary** problem. The trace context lives in a thread-local (the current `Observation`/`Span`); when you publish to Kafka and a *different* consumer thread in another service picks it up, the context is lost unless it's serialized into the message and restored.

Diagnosis and fix:
1. **Confirm propagation headers**: with Micrometer Tracing + Spring Cloud Stream/Spring Kafka, the `traceparent` (or B3) should be written as a **Kafka record header** on send and read on receive. If missing, instrumentation isn't wired up. Verify `spring-kafka` and the tracing bridge are on the classpath and that you're using the *instrumented* `KafkaTemplate`/listener container.
2. **Check `@Async`/manual thread pools**: if you hop threads with a raw `ExecutorService`, wrap it so the Observation context propagates (`ContextSnapshot`/`ContextExecutorService`), or use `ContextPropagatingTaskDecorator`.
3. **Check the consumer side**: the listener must *open a child span* with the incoming context as parent. Spring Cloud Stream's Kafka binder does this automatically when tracing is present; a hand-rolled consumer won't.
4. **Reactive caveat**: in WebFlux you must propagate via the Reactor `Context`, not thread-locals — enable `Hooks.enableAutomaticContextPropagation()` (Micrometer Context Propagation).

In production I'd add an integration test that asserts the same `traceId` appears in service B's logs for a message produced by service A, so the boundary stays instrumented as code changes.

### Q20. [Theory] What is the "thundering herd" / synchronized-restart problem with config refresh and discovery, and how do you mitigate it?

When a fleet of N instances all refresh, reconnect, or restart at the same instant — e.g., a `busrefresh` broadcast, a discovery server restart, or a config push that triggers pool re-creation — they can simultaneously hammer downstream dependencies (DB, cache, the discovery server itself), causing a self-inflicted outage. This is the thundering herd.

Mitigations:
- **Jitter / staggering**: add randomized delay to refresh handling and reconnect/heartbeat intervals so instances don't act in lockstep.
- **Rolling refresh** rather than simultaneous broadcast; or rolling deploy instead of live refresh.
- **Backoff with jitter** on discovery re-registration and Feign/LoadBalancer retries (Resilience4j supports randomized exponential backoff).
- **Eureka self-preservation** specifically guards against mass-eviction during a network blip: if too many instances miss heartbeats at once, Eureka *stops* evicting them, assuming a network problem rather than mass death. Understand it can also mask real outages.
- **Cache the registry / config** locally so a discovery-server outage doesn't immediately break client routing.

### Q21. [Coding] Implement a custom Spring Cloud LoadBalancer that prefers same-zone instances (zone affinity) to cut cross-AZ latency and egress cost.

**Problem:** In a multi-AZ deployment, prefer calling instances in the *same* availability zone, falling back to others only if none are available.

```java
public class ZonePreferenceLoadBalancer implements ReactorServiceInstanceLoadBalancer {

    private final String myZone;                                   // e.g. "us-east-1a"
    private final ObjectProvider<ServiceInstanceListSupplier> supplierProvider;
    private final AtomicInteger position = new AtomicInteger(0);

    public ZonePreferenceLoadBalancer(String myZone,
            ObjectProvider<ServiceInstanceListSupplier> supplierProvider) {
        this.myZone = myZone;
        this.supplierProvider = supplierProvider;
    }

    @Override
    public Mono<Response<ServiceInstance>> choose(Request request) {
        ServiceInstanceListSupplier supplier = supplierProvider.getIfAvailable();
        return supplier.get(request).next().map(instances -> pick(instances));
    }

    private Response<ServiceInstance> pick(List<ServiceInstance> all) {
        if (all.isEmpty()) return new EmptyResponse();

        // Prefer same-zone; fall back to all if none in-zone are healthy
        List<ServiceInstance> sameZone = all.stream()
                .filter(i -> myZone.equals(i.getMetadata().get("zone")))
                .toList();
        List<ServiceInstance> candidates = sameZone.isEmpty() ? all : sameZone;

        // Round-robin within the chosen candidate set
        int idx = Math.abs(position.getAndIncrement() % candidates.size());
        return new DefaultResponse(candidates.get(idx));
    }
}
```

```java
// Register it for a specific service via a LoadBalancerClient config class
@Configuration
public class CustomLbConfig {
    @Bean
    public ReactorServiceInstanceLoadBalancer zoneLoadBalancer(
            Environment env, LoadBalancerClientFactory factory) {
        String svc = env.getProperty(LoadBalancerClientFactory.PROPERTY_NAME);
        String zone = env.getProperty("eureka.instance.metadata-map.zone", "unknown");
        return new ZonePreferenceLoadBalancer(zone,
                factory.getLazyProvider(svc, ServiceInstanceListSupplier.class));
    }
}
```

**Edge cases:** if *all* same-zone instances are unhealthy, fall back to cross-zone (handled). Watch for **zone imbalance** — if 90% of traffic originates in zone A but capacity is even, zone A's instances overload; combine zone affinity with health/weight awareness. **Complexity:** O(n) filter per choose call, n = instance count (small). **Real-world payoff:** cross-AZ traffic incurs latency *and* cloud egress charges; Netflix and many AWS shops use zone affinity precisely to cut both.

### Q22. [Practical] How do you secure service-to-service calls in a Spring Cloud system, and where does the Gateway fit?

Defense in depth across the request path:

1. **Edge (Gateway)**: terminate TLS, authenticate the *end user* (validate the JWT / OAuth2 access token against the authorization server via `spring-cloud-gateway` + `spring-security-oauth2-resource-server`), enforce coarse authorization and rate limits, and strip dangerous inbound headers. The Gateway is the trust boundary — it should *not* blindly forward client-supplied identity headers.
2. **Service-to-service**: never trust the network. Use **mTLS** (often via a service mesh like Istio/Linkerd, or Consul Connect) so each call is mutually authenticated, *and/or* propagate a signed token. For OAuth2, propagate the user's token or use the **client-credentials** grant for system-to-system calls; Spring Security's `OAuth2AuthorizedClientManager` integrates with Feign/RestClient to attach tokens automatically.
3. **Config & secrets**: secure Config Server with auth + TLS; keep secrets in Vault/KMS, not Git. Encrypt sensitive values (Config Server supports `{cipher}` encryption).
4. **Actuator**: lock down `/actuator/refresh`, `/busrefresh`, `/env`, `/heapdump` — these leak config and enable DoS/RCE-adjacent attacks if open.

```
[client]─JWT─►[Gateway: authN + rate-limit + TLS]─mTLS+token─►[svc A]─mTLS+token─►[svc B]
                         │
                         ▼ validates token against
                    [Authorization Server / IdP]
```

The key staff-level point: the Gateway authenticates *users*; the mesh/token layer authenticates *services*. Don't conflate them, and don't let internal services implicitly trust traffic just because it came from inside the cluster (zero-trust).

### Q23. [Theory] How do you achieve exactly-once / idempotent processing with Spring Cloud Stream over Kafka, given that the default is at-least-once?

Out of the box, Spring Cloud Stream + Kafka gives **at-least-once** delivery: a consumer can reprocess a message after a crash or rebalance because offsets are committed *after* processing. True end-to-end exactly-once is hard; you reach it through a combination:

- **Idempotent consumers** (the pragmatic answer): make handlers idempotent so reprocessing is harmless — dedupe on a business key / message ID stored in a DB with a unique constraint, or use an "inbox" table. This is the most robust, broker-independent approach.
- **Kafka transactions / EOS**: enable the producer's transactional id and `read_process_write` semantics so the offset commit and output production are atomic (`spring.cloud.stream.kafka.binder.transaction.transaction-id-prefix`). This gives exactly-once *within Kafka*, but not across an external DB.
- **Transactional outbox + CDC**: write the event to an `outbox` table in the *same* DB transaction as your business change, then relay it to Kafka (Debezium/Connect). This guarantees the DB state and the published event never diverge — the gold standard for reliable event publishing.

```
DB tx { update order; insert into outbox(event) }  ──► CDC (Debezium) ──► Kafka topic
   atomic: order change and event are committed together or not at all
```

In production I default to **idempotent consumers + transactional outbox**; I reach for Kafka EOS only when the whole pipeline is Kafka-to-Kafka. Also handle **poison messages** with a dead-letter topic (`spring.cloud.stream.bindings.<in>.consumer.max-attempts` + DLQ) so one bad message doesn't block the partition.

### Q24. [Coding] Implement an idempotent Spring Cloud Stream consumer with dead-letter handling.

**Problem:** Process payment events at-least-once safely: dedupe duplicates and route permanent failures to a DLQ.

```java
@Configuration
public class PaymentConsumerConfig {

    @Bean
    public Consumer<Message<PaymentEvent>> processPayment(
            ProcessedEventRepository processed, PaymentService payments) {
        return message -> {
            PaymentEvent event = message.getPayload();
            String eventId = event.eventId();

            // Idempotency guard: unique constraint on eventId makes this atomic
            if (!processed.tryMarkProcessed(eventId)) {
                return;  // duplicate — already handled, ack and move on
            }
            payments.apply(event);   // if this throws, retry/DLQ kicks in
        };
    }
}

@Repository
public class ProcessedEventRepository {
    private final JdbcTemplate jdbc;
    public ProcessedEventRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Returns true if newly inserted (first time), false if it already existed. */
    public boolean tryMarkProcessed(String eventId) {
        try {
            jdbc.update("INSERT INTO processed_events(event_id, processed_at) VALUES (?, ?)",
                    eventId, Timestamp.from(Instant.now()));
            return true;
        } catch (DuplicateKeyException e) {
            return false;   // seen before
        }
    }
}
```

```yaml
spring:
  cloud:
    function:
      definition: processPayment
    stream:
      bindings:
        processPayment-in-0:
          destination: payments
          group: payment-processor
          consumer:
            max-attempts: 3                 # local retries before DLQ
      kafka:
        bindings:
          processPayment-in-0:
            consumer:
              enable-dlq: true              # route exhausted failures to DLQ
              dlq-name: payments.DLQ
```

**Edge cases:** the dedupe insert and the business write should ideally share a transaction (or use an inbox pattern) so you don't mark-processed-then-crash. Prune `processed_events` with a TTL so it doesn't grow unbounded. `max-attempts: 3` with DLQ prevents a poison message from stalling the partition forever. **Complexity:** O(1) per message (one indexed insert + the business op).

### Q25. [Practical] Walk through a real-world migration: monolith → Spring Cloud microservices. What order, and what breaks?

A pragmatic strangler-fig migration I'd run:

1. **Stand up the edge first** — put Spring Cloud Gateway in front of the monolith so all traffic routes through one place. Nothing changes behaviorally, but you now control routing, can add tracing/auth, and can split off routes later.
2. **Externalize config** — Config Server / ConfigMaps and 12-factor the monolith before splitting, so extracted services inherit good config hygiene.
3. **Carve by bounded context** — extract the *least-coupled, highest-value* capability first (often something read-heavy like catalog or notifications), behind a Gateway route. Use OpenFeign + LoadBalancer for the synchronous calls back into the monolith.
4. **Introduce async** — move integration to events (Spring Cloud Stream) to decouple; apply the outbox pattern for reliable publishing.
5. **Add resilience + observability** — Resilience4j and Micrometer Tracing from day one of the first split, because partial failures appear the moment you have two services.

What breaks / surprises: distributed transactions disappear (you need sagas/outbox); latency and failure modes multiply (every in-process call is now a network call); shared databases become a coupling trap (give each service its own data); local debugging gets harder (hence tracing). The classic mistake is splitting too fine, too early — a "distributed monolith" with chatty synchronous calls is *worse* than the monolith. I'd keep services coarse and split only along proven seams.

---

## 🔴 Expert (15+ yrs)

### Q26. [Theory] Is Spring Cloud still relevant given Kubernetes, service meshes, and the move toward platform-provided primitives? Make the architectural call.

This is the defining staff-level question circa 2026. Kubernetes natively provides **service discovery** (DNS/Service), **config** (ConfigMap/Secret), and **load balancing** (kube-proxy), and a **service mesh** (Istio/Linkerd) provides **mTLS, traffic shaping, retries, circuit breaking, and tracing** at the *sidecar/infra* layer — overlapping much of what Spring Cloud once owned (Eureka, Config Server, Ribbon, Hystrix).

My position: the overlap means you should *not* run redundant layers. On Kubernetes I'd typically **drop Eureka and Config Server** in favor of platform primitives, and push cross-cutting reliability (mTLS, retries, traffic policy) into the **mesh** where it's language-agnostic and centrally governed. But Spring Cloud remains valuable for what lives *inside the application*:
- **Spring Cloud Gateway** as an application-aware API gateway (auth, request transformation, GraphQL/BFF logic) above the mesh's L4/L7 plumbing.
- **OpenFeign / declarative clients** and **Resilience4j** for *application-semantic* resilience (fallbacks that return meaningful business defaults — a mesh can't know your "last-known price").
- **Spring Cloud Stream** for event-driven app logic.
- **Micrometer Observation** for app-level spans/metrics the mesh can't see.

The mature answer: **mesh owns infrastructure resilience; the app owns business-semantic resilience.** Don't double-circuit-break. Choose Spring Cloud components where they encode *domain* behavior, and let the platform own the network plumbing. Running both Eureka *and* k8s discovery is a smell.

### Q27. [Theory] Design the resilience and timeout budget for a request that fans out across 5 services. What goes wrong if you don't?

The core principle is a **latency budget that decreases down the call tree** and timeouts that are *consistent with retries*. If the Gateway's client times out at 2s but a downstream Feign call retries 3× at 1s each, the client gives up while the server is still working — wasted load and "retry storms."

Rules I enforce:
- **Decreasing timeouts down the stack**: each layer's timeout must be *less* than its caller's remaining budget, accounting for its own work. `gateway(2000ms) > A(1500ms) > B(900ms)`.
- **Retries multiply load**: a 3× retry at every hop in a 3-deep tree is up to 27× amplification. Cap retries, only on idempotent ops, with jittered backoff, and ideally use **retry budgets** (retry only if total retries stay under X% of traffic).
- **Hedging vs retry**: for tail latency, a hedged request (fire a second request after p95) can beat retry-on-timeout, but doubles load — use sparingly.
- **Circuit breakers per dependency**, not global, so one bad dependency doesn't trip calls to healthy ones.
- **Bulkheads** so a slow dependency can't consume all threads serving the *other* four fan-out calls.
- **Fail-open vs fail-closed** decisions per dependency: a recommendations call should fail-open (degrade), a fraud check should fail-closed.

```
budget 2000ms
  Gateway ─┬─► svc A (1500ms, idempotent, retry≤1)
           ├─► svc B (1500ms)
           ├─► svc C (1500ms)  ── all in parallel, bounded by slowest + bulkheaded
           ├─► svc D (1500ms)
           └─► svc E (1500ms, fail-open)
```

Without this you get **retry storms**, **cascading timeouts** (clients abandon work servers still do), and **resource exhaustion** where one slow dependency starves threads for all others — the textbook microservice meltdown.

### Q28. [Practical] Your distributed tracing bill exploded and traces are still missing the slow requests you care about. Redesign the sampling strategy.

This is the cost-vs-coverage tension of tracing at scale. Head-based sampling (decide at ingress, e.g., keep 1%) is cheap and simple but **random** — it misses most of the rare slow/error requests you actually need, while still costing a lot at high volume.

Redesign:
- **Tail-based sampling in the OTel Collector**: buffer complete traces, then decide *after* seeing the outcome — keep 100% of errors and traces over a latency threshold, sample the boring fast successes at 1%. This directly targets the requests you care about. Cost: the Collector must buffer spans (memory + a stateful collector tier).
- **Per-route / per-tenant rates**: high-value or low-volume routes at higher sampling; chatty health-check traffic at near-zero.
- **Parent-based consistency**: ensure a sampling decision propagates so you don't get partial traces (some spans kept, some dropped). Use `parentbased_traceidratio`.
- **Span limits & attribute pruning**: cap span/attribute counts and drop high-cardinality noise to shrink per-trace cost.
- **Separate hot vs cold storage**: keep recent traces searchable (Tempo/Jaeger), archive raw to cheap object storage.

In production I'd run head sampling as a coarse floor (protect the app from instrumentation overhead) and tail sampling in the Collector for *selection*, so the bill scales with *interesting* traffic, not total traffic. Validate by asserting that p99-latency and error traces are present in dashboards after the change.

### Q29. [Behavioral] Tell me about a time you had to convince a team to change a core distributed-systems decision (e.g., drop Eureka, adopt a mesh, or change the retry strategy). How did you handle the disagreement?

A strong answer uses **STAR** and shows technical leadership without steamrolling:

- **Situation**: "We ran Eureka + Config Server inside Kubernetes, duplicating what the platform already provided. It added two stateful components to operate, an extra failure mode, and onboarding friction — but the team had built tooling around it and was understandably reluctant to change."
- **Task**: "I needed to align us on retiring Eureka/Config Server in favor of k8s-native discovery and ConfigMaps, without it feeling like I was dismissing their prior work."
- **Action**: "Rather than mandate it, I ran a small spike on one non-critical service, measured the operational delta (fewer moving parts, faster cold starts), and wrote it up with the concrete failure scenarios Eureka had caused us in the last two incidents. I invited the original authors to co-review and explicitly credited the value Eureka *had* provided before k8s matured. We agreed on a phased migration with a rollback plan and kept Spring Cloud Gateway + Resilience4j because those encode app-level behavior k8s doesn't."
- **Result**: "We removed two stateful services, cut a recurring incident class, and the original author led the migration — which mattered more than being right, because they owned the outcome."

The signals interviewers want: data over opinion, respecting prior decisions, scoping a low-risk proof, distinguishing *infrastructure* concerns (move to platform) from *application* concerns (keep in Spring Cloud), and building consensus so the change sticks.

### Q30. [Theory] What are the failure and consistency implications of running Spring Cloud Config Server as a hard dependency at startup, and how do you make a fleet resilient to it being down?

If services do a **blocking fetch** of config from Config Server at startup (`spring.config.import=configserver:...` with fail-fast), then **Config Server becomes a single point of failure for *deployments and restarts***: if it's down during a deploy or a mass restart (e.g., AZ recovery), instances can't boot — exactly when you least want a coupled dependency. Worse, a synchronized restart creates a thundering herd against it (Q20).

Resilience strategies:
- **Retry + backoff** on config import (`spring.cloud.config.fail-fast=true` *with* `spring.cloud.config.retry.*`) so a transient blip doesn't fail the boot.
- **Local fallback / config caching**: bake last-known-good config into the image or a sidecar so a Config Server outage degrades gracefully rather than blocking startup.
- **Run Config Server highly available** (multiple replicas behind a LB, Git as the durable source of truth so the server is stateless and trivially replaceable).
- **Prefer pull-from-Git or platform config** for the *critical bootstrap* path, reserving Config Server for dynamic/refreshable values; on k8s, ConfigMaps remove the runtime dependency entirely since config is materialized into the pod.
- **Decouple secrets** so a Config Server outage never blocks credential access (separate Vault path).

The expert framing: a config server trades *centralization and dynamism* for an *availability coupling at the worst possible time*. Decide deliberately which config is bootstrap-critical (make it locally available/immutable) versus runtime-tunable (acceptable to fetch), and never let your blast radius for "deploy works" depend on a single config service.

### Q31. [Practical] How would you load-test and capacity-plan a Spring Cloud Gateway tier, and what are its specific bottlenecks?

Gateway is reactive (Netty event loop), so its scaling characteristics differ from a servlet app. Capacity planning approach:

- **Establish the workload shape**: connection count, request size, upstream latency distribution, and whether routes do heavy transformation. Gateway throughput is gated by **upstream latency** and **connection pool limits** far more than CPU.
- **Tune the upstream connection pool** (`spring.cloud.gateway.httpclient.pool.*` — `max-connections`, `acquire-timeout`). A too-small pool serializes requests; too large can overwhelm upstreams.
- **Watch the event loop**: any *blocking* code in a filter (a synchronous DB/HTTP call, blocking crypto) stalls the loop and collapses throughput. Profile for blocking calls; offload them to a bounded scheduler if unavoidable.
- **Memory / buffers**: large request/response bodies and response aggregation pressure direct memory (Netty `PooledByteBufAllocator`); stream rather than buffer where possible.
- **Redis rate-limiter latency**: if rate limiting adds a Redis round trip per request, Redis becomes a dependency in the hot path — co-locate and monitor it.
- **Load test with realistic upstreams** (Gatling/k6 with simulated upstream latency), not an instant-echo backend, or you'll wildly over-estimate capacity. Test the **degradation mode**: what happens when upstreams slow down (does the pool saturate, do circuits open)?

Bottom line: size by *concurrent in-flight requests × upstream latency*, eliminate blocking in filters, and load-test the *failure* path, not just the happy path.

---

## ✅ Key Takeaways

- **Spring Cloud is a toolkit, not a monolith**: adopt individual components (Gateway, Config, OpenFeign, Resilience4j, Stream, Micrometer Tracing) governed by a release-train BOM that guarantees version compatibility.
- **On Kubernetes, prefer platform primitives** for discovery/config/load-balancing and a service mesh for infra-level resilience; keep Spring Cloud for *application-semantic* concerns (business-aware fallbacks, app gateway logic, event handling).
- **Resilience is layered**: TimeLimiter (latency) → Bulkhead (concurrency) → CircuitBreaker (fail fast) → Retry (transient recovery) → fallback (degrade). They solve different failure modes — don't conflate them.
- **Timeout budgets must decrease down the call tree** and be consistent with retries to avoid retry storms and cascading timeouts.
- **Tracing in Spring Boot 3 is Micrometer Observation + Tracing bridging to OpenTelemetry/Brave** (Sleuth is gone); use the OTel Collector and tail-based sampling to control cost while keeping error/slow traces.
- **At-least-once is the default in Spring Cloud Stream**; achieve effective exactly-once with idempotent consumers + the transactional outbox pattern, plus DLQs for poison messages.
- **Secure deliberately**: Gateway authenticates users, mesh/tokens authenticate services, secrets live in Vault/KMS, and actuator refresh/bus endpoints must be locked down.

## ⚠️ Common Pitfalls

- **Blocking calls inside reactive Gateway filters** — stalls the Netty event loop and tanks throughput.
- **Retrying non-idempotent operations** (POSTs) — duplicates orders/payments; and stacking retries at every hop causes exponential load amplification.
- **Running redundant layers** — Eureka *and* Kubernetes discovery, or a mesh circuit breaker *and* a Resilience4j one doing the same job (double-breaking, confusing failure behavior).
- **Treating `@RefreshScope` as universal** — connection pools, listeners, and beans bound at construction often need a restart; `/actuator/refresh` only refreshes one instance unless you use the Bus.
- **Config Server as a hard, fail-fast startup dependency** — turns it into a SPOF for deploys and mass restarts; add retry/backoff and local fallback.
- **Unbounded idempotency/dedupe tables** — they grow forever without a TTL/pruning job.
- **Head-only trace sampling** — saves money but randomly drops the rare slow/error traces you actually need; add tail-based sampling in the Collector.
- **Leaving actuator endpoints open** — `/env`, `/refresh`, `/busrefresh`, `/heapdump` leak config and enable DoS; always secure them.
- **Splitting the monolith too fine, too early** — a chatty distributed monolith is worse than the original; split along proven bounded-context seams.

## 📚 Further Reading

- **Spring Cloud Reference Documentation** — the canonical, per-component docs (Config, Gateway, OpenFeign, LoadBalancer, Stream): https://spring.io/projects/spring-cloud
- **Resilience4j User Guide** — circuit breaker, retry, bulkhead, rate limiter, time limiter internals and configuration: https://resilience4j.readme.io
- **OpenTelemetry & Micrometer Tracing docs** — Observation API, context propagation, the OTel Collector and sampling: https://opentelemetry.io and https://micrometer.io/docs/tracing
- **"Release It!" by Michael Nygard** (2nd ed.) — the definitive book on stability patterns (circuit breaker, bulkhead, timeouts) that Resilience4j implements.
- **"Building Microservices" by Sam Newman** (2nd ed.) — bounded contexts, decomposition, and the trade-offs behind the migration and design questions above.
- **"Microservices Patterns" by Chris Richardson** — saga, transactional outbox, API composition, and the data-consistency patterns referenced for Spring Cloud Stream.
- **The Twelve-Factor App** — the config/build/release principles underpinning 12-factor Spring Cloud apps: https://12factor.net
