# Spring Boot

Spring Boot is the de-facto standard for building production-grade JVM services: it layers opinionated auto-configuration, embedded servers, and production tooling on top of the Spring Framework so teams ship faster with less boilerplate. This guide covers the mechanics interviewers probe at every seniority level, current through Spring Boot 3.x (Spring Framework 6, Jakarta EE, GraalVM AOT) in 2026.

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

### Q1. [Theory] What problem does Spring Boot solve, and how is it different from the Spring Framework?

Spring Framework gives you the IoC container, dependency injection, AOP, transaction management, and a large module ecosystem, but historically you had to wire all of it together yourself: XML or Java config for every bean, manual servlet container setup, dependency-version juggling, and boilerplate to expose health/metrics. Spring Boot is an *opinionated layer on top of* Spring that removes that ceremony. Its three pillars are **auto-configuration** (sensible beans created based on what's on the classpath), **starter dependencies** (curated, version-aligned dependency bundles), and **embedded servers** (Tomcat/Jetty/Undertow/Netty bundled so the app is a self-contained JAR). It also ships Actuator for production observability. The key trade-off is convention over configuration: you get going in minutes, but you must understand the conventions to override them cleanly. Spring Boot does not replace Spring — it *is* Spring, configured for you.

### Q2. [Theory] What does `@SpringBootApplication` actually do?

It is a convenience meta-annotation that combines three annotations: `@SpringBootConfiguration` (a specialized `@Configuration` marking the primary config class), `@EnableAutoConfiguration` (triggers the auto-configuration machinery), and `@ComponentScan` (scans the package of the annotated class and its sub-packages for `@Component`, `@Service`, `@Repository`, `@Controller`, etc.). Because component scanning is rooted at the class's package, the common rule is to place your main application class in the *top-level package* so everything below it is discovered. You can fine-tune it with attributes like `scanBasePackages`, or `exclude`/`excludeName` to switch off specific auto-configurations.

### Q3. [Practical] How do you externalize configuration, and what is the property resolution order?

Configuration lives in `application.properties` or `application.yml`, environment variables, command-line args, and more. Spring Boot merges all sources into a single `Environment`, and order matters because later sources override earlier ones. Roughly highest-to-lowest precedence: devtools settings, command-line arguments, `SPRING_APPLICATION_JSON`, servlet params, JNDI, Java system properties, OS environment variables, profile-specific files, then the plain `application.yml`, then `@PropertySource`, then defaults. In production you bind config to typed POJOs with `@ConfigurationProperties` rather than scattering `@Value` everywhere:

```java
@ConfigurationProperties(prefix = "billing")
@Validated
public record BillingProperties(
    @NotBlank String currency,
    @Positive int retryAttempts,
    Duration timeout) {}
```

```yaml
billing:
  currency: USD
  retry-attempts: 3
  timeout: 5s   # relaxed binding: retry-attempts -> retryAttempts, Duration parsing
```

`@ConfigurationProperties` gives you relaxed binding (kebab-case, camelCase, underscores all map), type conversion (`Duration`, `DataSize`), and JSR-303 validation — far safer than stringly-typed `@Value`.

### Q4. [Theory] What are Spring profiles and when do you use them?

Profiles are named groups of beans and configuration activated for a particular environment (e.g. `dev`, `test`, `prod`). You activate them via `spring.profiles.active=prod` (property, env var `SPRING_PROFILES_ACTIVE`, or CLI). Profile-specific files like `application-prod.yml` are layered on top of the base file. You annotate beans with `@Profile("prod")` so an in-memory stub is used in dev and the real implementation in prod. In Spring Boot 2.4+ the recommended mechanism for grouping is **profile groups** and multi-document YAML with `spring.config.activate.on-profile`. A common mistake is overusing profiles for feature flags — profiles are for *environment shape*, not runtime toggles.

### Q5. [Practical] How do you create a simple REST endpoint and return a proper status code?

```java
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService service;
    UserController(UserService service) { this.service = service; }

    @GetMapping("/{id}")
    public ResponseEntity<UserDto> getUser(@PathVariable long id) {
        return service.find(id)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<UserDto> create(@Valid @RequestBody CreateUserRequest req) {
        UserDto created = service.create(req);
        return ResponseEntity
            .created(URI.create("/api/users/" + created.id()))
            .body(created);  // 201 with Location header
    }
}
```

`@RestController` = `@Controller` + `@ResponseBody`, so return values are serialized (via Jackson) straight to the body. Prefer constructor injection (shown) over field injection — it makes dependencies explicit, allows `final` fields, and is testable without reflection.

### Q6. [Theory] What is a Spring Boot starter and why use it?

A starter is a curated Maven/Gradle dependency that pulls in a coherent set of libraries for a capability — `spring-boot-starter-web` brings Spring MVC, Jackson, validation, and embedded Tomcat; `spring-boot-starter-data-jpa` brings Hibernate, Spring Data JPA, and a connection pool. Starters solve *dependency hell*: the `spring-boot-dependencies` BOM pins compatible versions so you don't specify versions yourself and don't get conflicting transitive dependencies. You can also write your own starter for a shared internal library (e.g. `acme-logging-spring-boot-starter`) that auto-configures company-wide concerns.

---

## 🟡 Intermediate (3–7 yrs)

### Q7. [Theory] Explain how auto-configuration works under the hood.

Auto-configuration is driven by `@EnableAutoConfiguration`, which loads a list of candidate configuration classes. In Spring Boot 2.7+ and 3.x these are listed in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` (the older `spring.factories` `EnableAutoConfiguration` key is deprecated). Each candidate is a `@AutoConfiguration` class guarded by `@Conditional` annotations so it only contributes beans when it makes sense. The flow:

```
Startup
  └─ @EnableAutoConfiguration
       └─ AutoConfigurationImportSelector
            ├─ read .../AutoConfiguration.imports
            ├─ apply exclusions / filters
            └─ for each candidate config:
                 evaluate @Conditional* guards
                   ├─ @ConditionalOnClass(DataSource.class)  ── present? ─┐
                   ├─ @ConditionalOnMissingBean(DataSource.class)         │
                   └─ @ConditionalOnProperty(...)                         ▼
                                                          register beans only if all pass
```

Common conditions: `@ConditionalOnClass` / `@ConditionalOnMissingClass` (classpath), `@ConditionalOnBean` / `@ConditionalOnMissingBean` (existing beans — this is what lets *your* bean win over the default), `@ConditionalOnProperty`, `@ConditionalOnWebApplication`, `@ConditionalOnResource`. Ordering is controlled by `@AutoConfigureBefore`/`@AutoConfigureAfter`. The single most useful debugging trick is running with `--debug` (or `debug=true`), which prints the **Condition Evaluation Report** showing which auto-configs matched, which didn't, and why.

### Q8. [Practical] You define your own `DataSource` bean but Spring still seems to create one. How does override actually work?

The default `DataSourceAutoConfiguration` is annotated with `@ConditionalOnMissingBean(DataSource.class)`, so *if you define your own `DataSource` bean, the default backs off* — that is the whole mechanism. If Spring still appears to create one, the usual causes are: (1) your bean is in a package not scanned, so the container never sees it; (2) you defined a differently-typed bean (e.g. `HikariDataSource`) but a condition checks a different type; (3) two auto-configs race and ordering matters. The right fix is rarely `exclude = DataSourceAutoConfiguration.class` — it's to make sure your bean is discovered and typed so the `@ConditionalOnMissingBean` correctly suppresses the default. Use the condition report (`--debug`) to confirm: it will literally say `DataSourceAutoConfiguration#dataSource did not match: @ConditionalOnMissingBean found bean 'myDataSource'`.

### Q9. [Theory] What changed between Spring Boot 2 and Spring Boot 3?

The headline changes:

| Area | Spring Boot 2 | Spring Boot 3 |
|------|---------------|---------------|
| Java baseline | Java 8+ | **Java 17+** (21 fully supported) |
| Namespace | `javax.*` | **`jakarta.*`** (Jakarta EE 9+) |
| Spring Framework | 5.x | 6.x |
| Native image | experimental (Spring Native project) | **first-class via GraalVM AOT** in core |
| Observability | Micrometer metrics + Sleuth tracing | **Micrometer Observation API**, Sleuth replaced by Micrometer Tracing |
| Config props | binding | same + improved |

The most disruptive practical change is the `javax` → `jakarta` package rename (servlet, persistence, validation, etc.), which forces dependency upgrades and code changes. AOT processing and GraalVM native images became built-in rather than a separate project. Observability was unified: instead of separate metrics and tracing stacks, you instrument once with the `Observation` API and it feeds both metrics and traces. Sleuth is gone; tracing is now Micrometer Tracing (bridging to OpenTelemetry or Brave).

### Q10. [Practical] Compare Spring MVC and WebFlux. When would you actually pick reactive?

```
Spring MVC (servlet, blocking)          Spring WebFlux (reactive, non-blocking)
─────────────────────────────          ───────────────────────────────────────
Thread-per-request                      Event loop, few threads (Netty)
Blocking I/O (one thread waits          Non-blocking I/O; thread released while
  on DB/HTTP per request)                 awaiting I/O
Tomcat/Jetty/Undertow                   Netty (default) / servlet 3.1+ async
Mono<T> / Flux<T> NOT required          Returns Mono<T> / Flux<T>
Easy debugging, mature                  Backpressure, harder stack traces
```

WebFlux shines when you have **high concurrency with lots of I/O wait** — a gateway/aggregator fanning out to many downstream services, streaming endpoints (SSE), or when you must serve tens of thousands of concurrent connections on limited threads. It does *not* magically make a CPU-bound service faster, and it does not help if your data access is still blocking (a blocking JDBC call on an event-loop thread is a disaster — you'd need R2DBC for true reactive persistence). The reactive programming model (operators, backpressure, no blocking calls anywhere in the chain) raises cognitive load and complicates debugging. In 2026, with **Java 21 virtual threads** (`spring.threads.virtual.enabled=true`), much of WebFlux's scalability benefit for blocking code can be achieved with the simpler MVC model — so the honest answer is: use MVC + virtual threads for most blocking-I/O workloads, and reserve WebFlux for genuinely streaming or fully-reactive stacks.

### Q11. [Coding] Implement a Spring MVC endpoint and the equivalent WebFlux endpoint that calls a downstream service.

**Problem:** Expose `GET /orders/{id}/enriched` that fetches an order then enriches it with a remote customer lookup.

```java
// --- Spring MVC (blocking, RestClient introduced in Boot 3.2) ---
@RestController
class MvcOrderController {
    private final OrderRepo repo;
    private final RestClient http;   // synchronous, modern replacement for RestTemplate

    MvcOrderController(OrderRepo repo, RestClient.Builder b) {
        this.repo = repo;
        this.http = b.baseUrl("https://customers").build();
    }

    @GetMapping("/orders/{id}/enriched")
    public EnrichedOrder enriched(@PathVariable long id) {
        Order o = repo.findById(id).orElseThrow(() -> new OrderNotFound(id));
        Customer c = http.get().uri("/customers/{cid}", o.customerId())
                         .retrieve().body(Customer.class);  // blocks this thread
        return new EnrichedOrder(o, c);
    }
}
```

```java
// --- WebFlux (non-blocking) ---
@RestController
class FluxOrderController {
    private final ReactiveOrderRepo repo;   // R2DBC, non-blocking
    private final WebClient http;

    FluxOrderController(ReactiveOrderRepo repo, WebClient.Builder b) {
        this.repo = repo;
        this.http = b.baseUrl("https://customers").build();
    }

    @GetMapping("/orders/{id}/enriched")
    public Mono<EnrichedOrder> enriched(@PathVariable long id) {
        return repo.findById(id)
            .switchIfEmpty(Mono.error(new OrderNotFound(id)))
            .flatMap(o -> http.get().uri("/customers/{cid}", o.customerId())
                              .retrieve().bodyToMono(Customer.class)
                              .map(c -> new EnrichedOrder(o, c)));
        // Nothing executes until subscribed; no thread blocks on I/O.
    }
}
```

**Complexity:** Both are O(1) work per request. The difference is *thread utilization*: MVC parks a thread for the duration of the remote call (Time per request unchanged, but throughput bound by pool size ≈ 200 threads); WebFlux releases the event-loop thread during I/O, so a handful of threads serve thousands of in-flight requests. **Edge cases:** propagate the 404 (`OrderNotFound`) via an `@ExceptionHandler`; in WebFlux never call `.block()` on the event loop; set per-call timeouts on both clients.

### Q12. [Coding] Write a global exception handler that returns RFC-7807 Problem Detail responses.

**Problem:** Centralize error handling so every controller returns a consistent, standards-compliant error body.

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    // Spring Boot 3 has native ProblemDetail support (RFC 7807)
    @ExceptionHandler(OrderNotFound.class)
    public ProblemDetail handleNotFound(OrderNotFound ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
            HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setTitle("Order not found");
        pd.setType(URI.create("https://errors.acme.com/order-not-found"));
        pd.setProperty("orderId", ex.getId());          // custom extension
        pd.setProperty("timestamp", Instant.now());
        return pd;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setTitle("Validation failed");
        var errors = ex.getBindingResult().getFieldErrors().stream()
            .collect(Collectors.toMap(FieldError::getField,
                                      FieldError::getDefaultMessage,
                                      (a, b) -> a));
        pd.setProperty("errors", errors);
        return pd;
    }
}
```

**Why this matters:** `@RestControllerAdvice` applies across all controllers. `ProblemDetail` (Boot 3) produces `application/problem+json` automatically. You can enable Spring's built-in problem responses for framework exceptions with `spring.mvc.problemdetails.enabled=true`. **Edge cases:** never leak stack traces or SQL in the `detail` (information disclosure / security risk); map duplicate-field-error keys; ensure the advice is component-scanned. **Complexity:** O(n) in number of validation errors.

### Q13. [Theory] How does Spring Data JPA save you from boilerplate, and where does it bite you?

Spring Data JPA generates repository implementations at runtime from interfaces. You declare `interface UserRepo extends JpaRepository<User, Long>` and get CRUD, paging, and sorting for free; **derived query methods** like `findByEmailAndActiveTrue(...)` are parsed from the method name into JPQL; `@Query` lets you write JPQL/native SQL; and `Specification`/QueryDSL handle dynamic queries. Where it bites: the **N+1 select problem** (lazy associations triggering a query per row — fix with `@EntityGraph` or `join fetch`), unbounded `findAll()` loading entire tables, `LazyInitializationException` when accessing a lazy field outside a transaction/session, and "open session in view" masking the problem in dev. Also, `save()` on a managed entity may be a no-op because the dirty-checking flush already persists changes. Senior engineers treat JPA as a leaky abstraction: you must understand the SQL Hibernate emits (turn on `spring.jpa.show-sql` / use datasource-proxy in tests).

### Q14. [Practical] How do you fix an N+1 query problem in Spring Data JPA? Show the approaches.

```java
// PROBLEM: each Order lazily loads its lineItems -> 1 + N queries
List<Order> orders = orderRepo.findAll();
orders.forEach(o -> o.getLineItems().size());   // N extra SELECTs

// FIX 1: @EntityGraph -- declarative fetch join, no JPQL
@EntityGraph(attributePaths = "lineItems")
List<Order> findByStatus(OrderStatus status);

// FIX 2: explicit JPQL fetch join
@Query("select distinct o from Order o join fetch o.lineItems where o.status = :s")
List<Order> findWithItems(@Param("s") OrderStatus status);

// FIX 3: batch fetching (config) -- turns N selects into N/batchSize
// application.yml: spring.jpa.properties.hibernate.default_batch_fetch_size: 50
```

**Trade-offs:** `join fetch` on multiple collections causes a Cartesian product (and you need `distinct`); for paging + collection fetch, fetch the IDs first (the "two-query" pattern) because Hibernate can't paginate a fetched collection in SQL. `default_batch_fetch_size` is the lowest-friction global mitigation. In production I add an integration test asserting the *number of SQL statements* (via datasource-proxy or `@DataJpaTest` + a query counter) so an N+1 regression fails CI rather than slipping to prod.

### Q15. [Practical] What is the Actuator and how do you expose a custom endpoint safely?

Actuator adds production endpoints — `/actuator/health`, `/info`, `/metrics`, `/env`, `/loggers`, `/threaddump`, `/heapdump`, `/prometheus`, etc. By default only `health` is exposed over HTTP in Boot 2.x+/3.x; you opt others in explicitly. Security is paramount: `/env`, `/heapdump`, and `/loggers` leak secrets and let attackers mutate state, so you protect them (Spring Security) and ideally expose Actuator on a *separate management port* not routed publicly.

```yaml
management:
  server.port: 9090                 # separate port, firewall it off
  endpoints.web.exposure.include: health,info,prometheus,metrics
  endpoint.health.show-details: when_authorized
  endpoint.health.probes.enabled: true   # /health/liveness, /health/readiness for k8s
```

```java
@Component
@Endpoint(id = "features")
public class FeatureFlagsEndpoint {
    private final Map<String, Boolean> flags = new ConcurrentHashMap<>();

    @ReadOperation public Map<String, Boolean> all() { return flags; }

    @WriteOperation public void set(@Selector String name, boolean enabled) {
        flags.put(name, enabled);     // exposed as POST /actuator/features/{name}
    }
}
```

Also implement `HealthIndicator` for custom liveness/readiness checks (e.g. a downstream dependency). The k8s probe groups (`liveness`/`readiness`) are critical for graceful rollouts.

### Q16. [Theory] How does Spring Boot package an application, and what are layered jars?

`spring-boot-maven-plugin`/`gradle` produces an **executable "fat" jar**: your code, all dependencies, and a small `JarLauncher` bootstrap, with a nested layout (`BOOT-INF/classes`, `BOOT-INF/lib`). `java -jar app.jar` invokes the launcher, which sets up a classloader that reads the nested jars directly (no unpacking needed). **Layered jars** (default since Boot 2.4) reorganize `BOOT-INF` into layers by change frequency — `dependencies`, `spring-boot-loader`, `snapshot-dependencies`, `application` — so Docker image builds cache the rarely-changing dependency layers separately from your fast-changing application layer. This dramatically shrinks image push/pull deltas: a code-only change re-layers ~megabytes instead of hundreds. You extract layers with `java -Djarmode=layertools -jar app.jar extract` in a multi-stage Dockerfile, or just use the plugin's `bootBuildImage` (Cloud Native Buildpacks) which produces an optimized OCI image without a Dockerfile.

---

## 🟠 Advanced (8–12 yrs)

### Q17. [Practical] Walk through implementing graceful shutdown correctly. What breaks if you don't?

Graceful shutdown means: stop accepting new requests, let in-flight requests finish within a grace period, then release resources (DB pools, message consumers). Without it, a rolling deploy or scale-down kills requests mid-flight — users get connection resets, half-committed work, and you may double-process messages.

```yaml
server.shutdown: graceful                 # Boot 2.3+; default is "immediate"
spring.lifecycle.timeout-per-shutdown-phase: 30s
```

```
SIGTERM (k8s sends this on pod termination)
   │
   ▼
Spring context close
   ├─ web server stops accepting NEW connections
   ├─ in-flight requests get up to 30s to complete
   ├─ SmartLifecycle beans stopped in phase order (high → low)
   │     e.g. stop Kafka listeners BEFORE closing DB pool
   └─ @PreDestroy / DisposableBean run, pools drained
   │
   ▼  (k8s grace period must be >= app grace period!)
SIGKILL (forced) if still alive after terminationGracePeriodSeconds
```

The gotchas: (1) Kubernetes `terminationGracePeriodSeconds` must exceed your app's grace period or k8s SIGKILLs you mid-drain. (2) Set a `preStop` sleep (or readiness-probe flip) so the service mesh/load balancer stops routing *before* the context starts closing — otherwise new requests still arrive during shutdown. (3) Order matters: implement `SmartLifecycle` with explicit phases so you stop *consuming* (Kafka, scheduled jobs) before tearing down what those consumers depend on. (4) Long-running async tasks need their own bounded executor with awaitTermination.

### Q18. [Theory] How does AOT processing and the GraalVM native image build work, and what are the constraints?

A GraalVM native image is an ahead-of-time-compiled, standalone executable with **no JIT and a closed-world assumption**: everything reachable must be known at build time. Spring's dynamic features (reflection, proxies, classpath scanning, conditional auto-config) conflict with closed-world, so Spring Boot 3 runs an **AOT processing phase** at build time that *executes the auto-configuration condition evaluation once*, generates explicit Java configuration + bean-registration code, and emits GraalVM reachability metadata (reflection, resources, proxies, serialization hints). GraalVM's `native-image` then compiles to a native binary.

```
Source + deps
   │  (1) Spring AOT: evaluate conditions, generate
   ▼      *__BeanDefinitions.java + reflect-config.json hints
Generated sources + reachability metadata
   │  (2) GraalVM native-image: closed-world static analysis + AOT compile
   ▼
Native executable  ──► ~50ms startup, ~½ the RSS, no warmup
```

Benefits: **startup in tens of milliseconds** and much lower memory — ideal for serverless/scale-to-zero and high-density deployments. Constraints/costs: long build times (minutes), peak throughput can be *lower* than warmed-up JIT for long-running CPU-bound services, dynamic reflection/proxies need explicit `@RegisterReflectionForBinding`/hints, profiles can't be fully dynamic (active profiles are baked unless you use the build-time profile support), and `@ConditionalOnProperty` decisions are frozen at build time if they affect bean structure. You add `@RuntimeHintsRegistrar` for libraries lacking metadata. The decision is workload-dependent: native shines for short-lived/burst workloads; classic JVM (with CDS/`-XX:ArchiveClassesAtExit` or Project Leyden in the future) often wins for steady high-throughput services.

### Q19. [Coding] Write an integration test using Testcontainers and `@SpringBootTest` that hits a real PostgreSQL.

**Problem:** Verify the repository layer against a real database, not H2 (which masks dialect bugs).

```java
@SpringBootTest
@Testcontainers
@AutoConfigureMockMvc
class OrderIntegrationTest {

    @Container @ServiceConnection   // Boot 3.1+ auto-wires datasource URL/creds
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired MockMvc mvc;
    @Autowired OrderRepo repo;

    @Test
    void createsAndFetchesOrder() throws Exception {
        var location = mvc.perform(post("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"customerId": 42, "amount": 19.99}"""))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getHeader("Location");

        mvc.perform(get(location))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.amount").value(19.99));

        assertThat(repo.count()).isEqualTo(1);
    }
}
```

**Key points:** `@ServiceConnection` (Boot 3.1+) removes the old `@DynamicPropertySource` boilerplate — Testcontainers' container is automatically translated into datasource properties. Reuse containers across the suite (`static` + Testcontainers reuse) to keep tests fast. **Edge cases:** clean state between tests (`@Transactional` rollback or explicit truncate); pin the image tag for reproducibility; in CI ensure a Docker daemon is available. **Complexity:** container startup ~1–3s amortized once per JVM; per-test SQL is O(1).

### Q20. [Coding] Demonstrate test slices and the difference between `@WebMvcTest`, `@DataJpaTest`, and `@SpringBootTest`.

**Problem:** Test the web layer in isolation without booting the whole context.

```java
// --- Web slice: loads ONLY MVC infra + the named controller, NOT services/repos ---
@WebMvcTest(UserController.class)
class UserControllerSliceTest {

    @Autowired MockMvc mvc;
    @MockitoBean UserService service;   // Boot 3.4+ (@MockBean deprecated)

    @Test
    void returns404WhenMissing() throws Exception {
        given(service.find(99L)).willReturn(Optional.empty());
        mvc.perform(get("/api/users/99"))
           .andExpect(status().isNotFound());
    }
}

// --- JPA slice: loads JPA repos + an embedded/Testcontainers DB, rolls back per test ---
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)   // use real DB, not H2
@Testcontainers
class UserRepoSliceTest {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @Autowired UserRepo repo;

    @Test
    void findsByEmail() {
        repo.save(new User("a@b.com"));
        assertThat(repo.findByEmail("a@b.com")).isPresent();
    }
}
```

**The hierarchy:** `@WebMvcTest`/`@DataJpaTest`/`@WebFluxTest`/`@JsonTest` are *slices* — they load a narrow, fast subset of the context and require you to mock collaborators. `@SpringBootTest` boots the **entire** application context (optionally a real server with `webEnvironment = RANDOM_PORT`) — slowest but highest fidelity, used for end-to-end paths. **Reactive parallel:** use `WebTestClient` instead of `MockMvc` for WebFlux. **Trade-off:** slices keep the test pyramid healthy (many fast slice/unit tests, few full-context integration tests). **Complexity:** slice context start ≪ full context; cache contexts by configuration to avoid repeated startup.

### Q21. [Theory] How does Spring's transaction management work, and what are the classic `@Transactional` traps?

`@Transactional` is implemented with AOP proxies: Spring wraps the bean in a proxy that begins a transaction before the method and commits/rolls back after. The traps stem from *how proxies work*: (1) **self-invocation** — calling a `@Transactional` method from another method *in the same class* bypasses the proxy, so no transaction starts; the fix is to call through the injected self-proxy or restructure. (2) **`private`/`final` methods** can't be proxied (CGLIB), so the annotation is silently ignored. (3) **Rollback rules** — by default Spring rolls back on `RuntimeException`/`Error` but *not* checked exceptions; you must set `rollbackFor`. (4) **Propagation** — `REQUIRES_NEW` suspends the outer transaction (separate physical tx), while the default `REQUIRED` joins it; misusing this causes partial commits or unexpected locking. (5) **Read-only** — `@Transactional(readOnly = true)` enables Hibernate flush-mode optimizations and can route to read replicas. (6) Holding a transaction open across a remote/HTTP call (long transaction) bloats connection pool usage and risks deadlocks.

### Q22. [Practical] A service intermittently exhausts its HikariCP connection pool under load. How do you diagnose and fix it?

**Diagnosis path:** First confirm via Actuator/Micrometer metrics (`hikaricp.connections.active`, `pending`, `acquire` timing) and HikariCP's own leak-detection log (`leakDetectionThreshold`). Typical root causes: (1) **connection leak** — a connection acquired but never returned (e.g. obtaining a `Connection` manually and not closing it, or a transaction that never commits/rolls back). (2) **long-held transactions** — `@Transactional` method making slow remote calls while holding a connection. (3) **pool too small for concurrency × latency** (Little's Law: needed connections ≈ throughput × hold-time). (4) **N+1 amplification** multiplying DB round-trips. 

**Fixes:** enable `spring.datasource.hikari.leak-detection-threshold=2000` to find leaks; move remote calls *outside* the transaction boundary; size the pool deliberately (often a *small* pool — e.g. cores × 2 — outperforms a huge one because the DB itself is the bottleneck); set `connection-timeout` so requests fail fast instead of piling up; add a circuit breaker on the slow downstream. In production I'd add an alert on `hikaricp.connections.pending > 0` sustained, because that's the leading indicator before user-facing timeouts. **Real-world case:** a payments service I worked on saw pool exhaustion every market open; root cause was a `@Transactional` method calling a fraud-scoring HTTP API — moving that call out of the transaction (commit, then enrich asynchronously) cut hold-time 20x and the exhaustion vanished.

### Q23. [Theory] How do you implement and unify observability (metrics, tracing, logging) in Spring Boot 3?

Boot 3 standardizes on the **Micrometer Observation API**: you create one `Observation` and it simultaneously emits metrics (via Micrometer) and distributed traces (via Micrometer Tracing, bridging to OpenTelemetry or Brave). Many integrations (web, JDBC, messaging) are auto-instrumented, so an incoming request automatically produces an HTTP server metric *and* a trace span with a propagated `traceId`. You add custom observations with `@Observed` (AOP) or the `ObservationRegistry` programmatically. For metrics you expose `/actuator/prometheus`; for tracing you configure an exporter (OTLP to a collector). Critically, you tie logs into traces by including `traceId`/`spanId` in the log MDC/pattern so a log line in Loki links to the span in Tempo/Jaeger. This "three pillars from one instrumentation point" model replaces Boot 2's separate Micrometer-metrics + Spring-Cloud-Sleuth stacks (Sleuth is discontinued). The trade-off is sampling: you trace-sample (e.g. 1–10%) to control cost, but keep metrics at 100% since they're aggregated.

### Q24. [Practical] How would you write a reusable internal auto-configuration / starter for company-wide concerns?

Create a library module exposing an `@AutoConfiguration` class guarded by conditions, register it in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`, and back it with `@ConfigurationProperties` so teams tune it via `application.yml`.

```java
@AutoConfiguration
@ConditionalOnClass(WebClient.class)
@EnableConfigurationProperties(AcmeTracingProperties.class)
public class AcmeTracingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean                       // let apps override
    @ConditionalOnProperty(prefix = "acme.tracing", name = "enabled",
                           matchIfMissing = true)
    public WebClientCustomizer acmeHeaderPropagation(AcmeTracingProperties props) {
        return builder -> builder.defaultHeader("X-Acme-Trace", props.serviceName());
    }
}
```

**Best practices:** name it `acme-<concern>-spring-boot-starter`; always use `@ConditionalOnMissingBean` so applications can override; expose `@ConfigurationProperties` with validation; ship `additional-spring-configuration-metadata.json` so IDEs autocomplete your properties; for native support add `@RuntimeHintsRegistrar`. This is how platform teams enforce logging, tracing, security defaults, and resilience across dozens of services without copy-paste. **Trade-off:** auto-config magic can surprise teams — document conditions and provide an off-switch property.

---

## 🔴 Expert (15+ yrs)

### Q25. [Theory] Compare the scalability models: thread-per-request + virtual threads vs WebFlux. How do you decide at an org level?

```
Platform threads (classic MVC)   Virtual threads (Java 21, MVC)   WebFlux (Reactor)
──────────────────────────────   ──────────────────────────────   ───────────────────
~1 OS thread per request         millions of cheap virtual         few event-loop threads
blocks the OS thread on I/O      threads; carrier thread freed     never blocks; async
pool exhaustion under I/O wait     on blocking I/O                  backpressure built-in
simple, mature debugging         simple code, huge concurrency     steep learning curve
                                 still blocking semantics           non-blocking end-to-end
```

Since Java 21 + Boot 3.2, enabling `spring.threads.virtual.enabled=true` lets the *familiar blocking programming model* scale to enormous concurrency because virtual threads unmount from their carrier OS thread during blocking I/O. For the vast majority of I/O-bound microservices this captures ~80% of WebFlux's benefit at ~20% of the complexity, and it works with blocking JDBC/JPA. WebFlux remains justified for: true streaming (SSE/WebSocket fan-out), backpressure-sensitive pipelines, and fully reactive data stacks (R2DBC) where you want non-blocking end-to-end. **The org-level decision** is mostly about *cost of complexity*: reactive code is harder to write, debug (no readable stack traces), and staff for; virtual-thread-based blocking code is mainstream. My default recommendation in 2026 is MVC + virtual threads as the standard, with WebFlux as a deliberate, justified exception — and beware *pinning* (synchronized blocks / native calls pinning a virtual thread to its carrier, which can re-create starvation).

### Q26. [Behavioral] Tell me about a time you led a Spring Boot 2 → 3 migration across many services. How did you de-risk it?

I framed it as a program, not a code change. **Approach:** (1) Inventory — scripted a scan across all repos for `javax.*` usage, EOL dependencies, and Sleuth/legacy Actuator config, producing a per-service effort estimate and a dependency-readiness matrix (some libs had no Jakarta release yet, which gated those services). (2) Pilot — migrated one low-risk, high-coverage service first to build a runbook and shake out shared-library issues. (3) Tooling — used OpenRewrite recipes to mechanically rename `javax`→`jakarta` and update Boot/Spring versions, which removed most manual toil and human error. (4) Platform libraries first — upgraded our internal starters (logging, tracing migrated Sleuth→Micrometer Tracing, security) and released them before app teams touched their code. (5) Guardrails — every service kept its contract tests and Testcontainers integration tests green as the gate; we shadow-traffic-tested the highest-risk ones. (6) Rollout — staggered by blast radius, with feature-flagged canaries and a documented rollback. **Outcome/lesson:** the long pole was never our code — it was transitive dependencies lacking Jakarta support and undocumented reflection that broke only at runtime. The lesson I carry: invest disproportionately in the pilot and the shared starters; once those are solid, the per-service migrations become near-mechanical.

### Q27. [Theory] Spring Boot has many `BeanFactoryPostProcessor`/`BeanPostProcessor` and lifecycle hooks. Explain the startup sequence and where you'd intervene.

```
SpringApplication.run()
 1. create environment (load properties, resolve profiles)        ← EnvironmentPostProcessor
 2. print banner, create ApplicationContext
 3. apply ApplicationContextInitializers
 4. load bean definitions (@Configuration, component scan, AOT)
 5. invoke BeanFactoryPostProcessors                              ← mutate bean DEFINITIONS
       (e.g. ConfigurationClassPostProcessor, property placeholders)
 6. register BeanPostProcessors
 7. instantiate singletons:
       constructor → @Autowired → BeanPostProcessor.before
       → @PostConstruct/InitializingBean → BPP.after (AOP proxy wrap here)
 8. SmartLifecycle.start() by phase
 9. ApplicationRunner / CommandLineRunner
10. ready  ── ApplicationReadyEvent
   ...
   context close → SmartLifecycle.stop() (reverse phase) → @PreDestroy
```

**Where you intervene:** `EnvironmentPostProcessor` (via `spring.factories`) to inject config *before* the context exists — e.g. pull secrets from Vault/SSM and add a `PropertySource` early. `BeanFactoryPostProcessor` to alter bean *definitions* (rare; e.g. conditional bean registration). `BeanPostProcessor` to wrap/decorate beans (this is how AOP proxies and `@Transactional`/`@Async` are applied). `SmartLifecycle` for ordered start/stop (graceful shutdown ordering). `ApplicationRunner`/`CommandLineRunner` for startup tasks after the context is ready. Knowing this sequence is how you debug "my bean isn't proxied" (BPP timing) or "my config isn't visible during another bean's init" (ordering) problems.

### Q28. [Practical] Design the resilience and configuration strategy for a fleet of Spring Boot microservices. What are the security and operational implications?

**Configuration:** Externalize everything; use Spring Cloud Config / Kubernetes ConfigMaps for non-secret config and a secrets manager (Vault, AWS Secrets Manager, GCP Secret Manager) for secrets — *never* secrets in `application.yml` or images. Bind to `@ConfigurationProperties` with `@Validated` so a bad config fails fast at startup, not at first request. Use config refresh (Spring Cloud `@RefreshScope` or restart-on-change) deliberately — live refresh is powerful but a foot-gun for stateful beans.

**Resilience:** Resilience4j for circuit breakers, bulkheads, rate limiters, retries with jitter, and time limiters around every remote dependency; pair with sensible HTTP/DB timeouts (a missing client timeout is the most common cascade-failure cause). Graceful shutdown + readiness probes for zero-downtime deploys. Idempotency keys on mutating endpoints so retries are safe.

**Security implications:** lock down Actuator (separate management port, authenticated, never expose `/env` `/heapdump` publicly — they leak credentials); validate and bind all input (`@Valid`), rely on Spring Security with deny-by-default; keep dependencies patched (Boot's BOM + Dependabot/`dependency-check`, given Log4Shell/Spring4Shell history); enforce TLS and propagate auth context (JWT) through traces without logging tokens. **Operational:** unified observability (Q23), structured JSON logging with trace correlation, SLO-based alerting on the golden signals, and chaos/load testing of the shutdown and circuit-breaker paths before they're needed in an incident.

### Q29. [Coding] Implement a resilient downstream call with Resilience4j: circuit breaker + retry + timeout + fallback.

**Problem:** Protect a service from a flaky downstream and degrade gracefully.

```java
@Service
public class PricingClient {

    private final RestClient http;
    PricingClient(RestClient.Builder b) { this.http = b.baseUrl("https://pricing").build(); }

    @CircuitBreaker(name = "pricing", fallbackMethod = "fallbackPrice")
    @Retry(name = "pricing")                 // retries on transient errors
    @TimeLimiter(name = "pricing")           // requires reactive/CompletableFuture return
    public CompletableFuture<Price> price(String sku) {
        return CompletableFuture.supplyAsync(() ->
            http.get().uri("/price/{sku}", sku).retrieve().body(Price.class));
    }

    // signature must match + trailing Throwable
    private CompletableFuture<Price> fallbackPrice(String sku, Throwable t) {
        return CompletableFuture.completedFuture(Price.cachedOrDefault(sku));
    }
}
```

```yaml
resilience4j.circuitbreaker.instances.pricing:
  sliding-window-size: 50
  failure-rate-threshold: 50          # open at 50% failures
  wait-duration-in-open-state: 10s
  permitted-number-of-calls-in-half-open-state: 5
resilience4j.retry.instances.pricing:
  max-attempts: 3
  wait-duration: 200ms
  enable-exponential-backoff: true
resilience4j.timelimiter.instances.pricing:
  timeout-duration: 1s
```

**Order of aspects matters:** Resilience4j applies `Retry(CircuitBreaker(TimeLimiter(...)))` by default — retries wrap the breaker, so a retry storm won't hammer an open circuit. **Edge cases:** only retry *idempotent*/transient failures (not 4xx); add jitter to avoid thundering-herd; the fallback must be fast and not call the same failing dependency; emit metrics (`resilience4j.circuitbreaker.state`) so you alert on open breakers. **Complexity:** O(maxAttempts) calls worst case; fallback O(1).

### Q30. [Theory] What are the deepest performance levers for Spring Boot startup and runtime in 2026?

For **startup** (matters for serverless, autoscaling, and dev loop): (1) **AOT + GraalVM native** for ~50ms cold start (Q18). (2) **CDS / AppCDS** and Boot 3.3's `Training Run` + **Class Data Sharing** to memory-map pre-parsed classes, cutting JVM startup meaningfully without going native; Project **CRaC** (Coordinated Restore at Checkpoint) snapshots a warmed JVM and restores in milliseconds. (3) Trim auto-configuration (lazy initialization `spring.main.lazy-initialization=true` for dev, or exclude unused auto-configs) and minimize component-scan surface. For **runtime throughput**: right-size connection/thread pools using Little's Law, enable virtual threads for I/O-bound blocking work, use HTTP/2 and connection keep-alive on clients, cache with `@Cacheable` + a real cache (Caffeine/Redis) with explicit TTL/eviction, and avoid the N+1/eager-loading JPA traps (Q14). For **memory/density**: native images or tuned heap + G1/ZGC, and layered jars for image efficiency (Q16). The expert move is to *measure first*: profile with async-profiler / JFR, read the Actuator startup metrics (`/actuator/startup` with `BufferingApplicationStartup`), and treat each lever as a trade-off (native trades peak throughput and build time for startup/memory; lazy init trades startup speed for first-request latency and hides wiring errors).

---

## ✅ Key Takeaways

- Spring Boot = Spring + **auto-configuration + starters + embedded server + Actuator**; `@SpringBootApplication` bundles config, auto-config, and component scan.
- Auto-configuration is just `@Conditional`-guarded `@AutoConfiguration` classes; `@ConditionalOnMissingBean` is *why your beans override defaults*. Use `--debug` for the condition report.
- Prefer typed `@ConfigurationProperties` (with `@Validated`) over scattered `@Value`; profiles describe *environment shape*, not feature flags.
- Boot 3 = Java 17+, `jakarta.*`, Spring 6, first-class GraalVM AOT/native, unified Micrometer Observation (Sleuth gone).
- In 2026, **MVC + virtual threads** is the pragmatic default for I/O-bound services; reserve WebFlux for streaming/fully-reactive stacks.
- Test with the **slice → full-context** pyramid (`@WebMvcTest`/`@DataJpaTest` → `@SpringBootTest`), and use **Testcontainers + `@ServiceConnection`** for real-DB fidelity.
- Enable `server.shutdown=graceful`, align it with k8s `terminationGracePeriodSeconds`, and order `SmartLifecycle` phases.
- Lock down Actuator; never expose `/env`/`/heapdump`/`/loggers` publicly.

## ⚠️ Common Pitfalls

- **`@Transactional` self-invocation** (calling it within the same class) silently does nothing — proxies are bypassed.
- **N+1 queries** and `LazyInitializationException` from naive JPA mappings; assert SQL counts in tests to catch regressions.
- **Holding a transaction across a remote call** — bloats the connection pool and causes intermittent HikariCP exhaustion.
- **Field injection** (`@Autowired` on fields) hides dependencies and breaks testability — use constructor injection.
- **Missing client timeouts** on `RestClient`/`WebClient`/JDBC → cascading failures; always set timeouts + a circuit breaker.
- **Excluding auto-config to "fix" overrides** when the real issue is component-scan placement or bean typing.
- **Reactive code with a hidden blocking call** (e.g. JDBC on the event loop) — silently destroys WebFlux throughput.
- **GraalVM native surprises**: reflection/proxy/`@ConditionalOnProperty` decisions frozen at build time; add hints and test the native binary, not just the JVM build.
- **Leaking secrets/stack traces** in error responses or unsecured Actuator endpoints.

## 📚 Further Reading

- [Spring Boot Reference Documentation](https://docs.spring.io/spring-boot/index.html) — the authoritative, version-specific source (read the 3.x docs).
- *Spring Boot Up & Running* — Mark Heckler (O'Reilly) — practical, modern Boot.
- *Spring in Action, 6th Edition* — Craig Walls (Manning) — broad Spring/Boot coverage.
- [Spring Boot 3.0 Migration Guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-3.0-Migration-Guide) — official `javax`→`jakarta` and native migration notes.
- [Micrometer / Observability Documentation](https://docs.micrometer.io/) — metrics + tracing with the Observation API.
- [Spring Boot reference: GraalVM Native Images](https://docs.spring.io/spring-boot/reference/packaging/native-image/index.html) — AOT processing and native build constraints.
