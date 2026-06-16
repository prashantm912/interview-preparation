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

## 🧩 Extended Questions — Set 1: Deep Theory & Internals

### 🟢 Basic — extended

#### Q31. [Theory] What is the difference between the `ApplicationContext` and the `BeanFactory`, and which one does Spring Boot use?

`BeanFactory` is the *root* interface of Spring's IoC container — it provides the bare mechanics: lazy bean instantiation, dependency resolution, and lifecycle wiring. `ApplicationContext` is a sub-interface that *extends* `BeanFactory` and layers on the features you actually rely on in a real application: eager singleton instantiation at startup, automatic detection and invocation of `BeanPostProcessor`/`BeanFactoryPostProcessor` beans, `MessageSource` (i18n), the `ApplicationEvent` publishing system, environment/`PropertySource` abstraction, and `ResourceLoader` support. In other words, `BeanFactory` is the engine and `ApplicationContext` is the engine plus all the integration plumbing.

Spring Boot always uses an `ApplicationContext` — specifically a subtype chosen by the kind of application detected on the classpath: `AnnotationConfigServletWebServerApplicationContext` for a servlet web app (Tomcat/Jetty), `AnnotationConfigReactiveWebServerApplicationContext` for WebFlux (Netty), and a plain `AnnotationConfigApplicationContext` for a non-web app. `SpringApplication` decides this from the `WebApplicationType` it deduces by inspecting which web classes are present.

The interview point most people miss is *why eager instantiation matters*: because the `ApplicationContext` instantiates singletons at startup, wiring errors (missing beans, ambiguous injection, bad config) surface immediately at boot rather than lazily on the first request — this fail-fast behaviour is a deliberate design choice. With a raw `BeanFactory` you would not get automatic `BeanPostProcessor` registration, so AOP, `@Transactional`, and `@Async` proxying would not even work. You essentially never instantiate a `BeanFactory` directly in application code.

#### Q32. [Theory] What does `@Bean` give you that a `@Component`-scanned class does not, and when do you use each?

`@Component` (and its stereotypes `@Service`, `@Repository`, `@Controller`) marks a *class* you own so component scanning instantiates it via its constructor. `@Bean` is a method-level annotation inside a `@Configuration` class where *you* write the instantiation logic and return the object. The decisive distinction is **ownership**: use `@Component` for your own classes where you can add an annotation; use `@Bean` for third-party classes you cannot annotate (e.g. an `ObjectMapper`, a `RestClient`, a library client) or when construction needs custom logic, conditional wiring, or multiple instances of the same type.

```java
@Configuration
class HttpConfig {
    @Bean
    RestClient pricingClient(RestClient.Builder b,
                             @Value("${pricing.base-url}") String url) {
        return b.baseUrl(url)
                .requestInterceptor(new LoggingInterceptor())
                .build();           // construction logic you can't express with @Component
    }
}
```

There is also a subtle semantic difference around scope and method calls. In a `@Configuration` class (which Spring CGLIB-enhances by default), calling one `@Bean` method from another returns the *same singleton* rather than a fresh object, because the proxy intercepts the call and routes it through the container. This is the "full" `@Configuration` mode versus "lite" mode (a `@Bean` method on a non-`@Configuration` class, or `@Configuration(proxyBeanMethods = false)`), where inter-bean method calls create new instances. Knowing this prevents the classic bug of accidentally creating two copies of a singleton because you new-ed it up via a direct method call in lite mode.

#### Q33. [Theory] Explain the standard Spring bean scopes and what each really means for object identity and thread-safety.

A bean's scope governs how many instances the container creates and how long they live. The two core scopes are **singleton** (the default — exactly one shared instance per container, created eagerly at startup and cached) and **prototype** (a new instance every time the bean is requested, and crucially the container does *not* manage its full lifecycle — no `@PreDestroy` is called for prototypes). Web-aware contexts add **request**, **session**, and **application** scopes, plus **websocket**.

```
singleton  ── one instance for the whole container (default)         ── stateless services
prototype  ── new instance per lookup; container forgets it after    ── stateful helpers
request    ── one per HTTP request                                   ── per-request context
session    ── one per HTTP session                                   ── per-user state
application ── one per ServletContext                                ── app-wide web state
```

The most important practical consequence is **thread-safety**: a singleton is shared across all threads, so it must be stateless (or use only thread-safe state). The classic bug is injecting mutable per-request state into a singleton. The classic *trap* is injecting a `prototype`/`request`-scoped bean directly into a singleton: the singleton resolves its dependency once at startup and then holds the *same* instance forever, defeating the narrower scope. The fix is a scoped proxy — `@Scope(value = "request", proxyMode = ScopedProxyMode.TARGET_CLASS)` — which injects a CGLIB proxy that, on each method call, looks up the correct instance for the current request/thread. This proxy indirection is exactly how request-scoped beans work inside otherwise-singleton controllers.

#### Q34. [Theory] What is the difference between `@RestController` and `@Controller`, and how does Spring decide whether to render a view or serialize a body?

`@Controller` is the generic Spring MVC stereotype: its handler methods may return a `String` (interpreted as a *view name* resolved by a `ViewResolver`), a `ModelAndView`, or — only if individually annotated with `@ResponseBody` — a serialized object. `@RestController` is a convenience meta-annotation equal to `@Controller + @ResponseBody`, so *every* method's return value is written directly to the response body via `HttpMessageConverter`s, never resolved as a view. You use `@Controller` for server-rendered pages (Thymeleaf, JSP) and `@RestController` for JSON/XML APIs.

The decision mechanism lives in `RequestMappingHandlerAdapter`, which holds an ordered chain of `HandlerMethodReturnValueHandler`s. When `@ResponseBody` is present (or implied by `@RestController`), a `RequestResponseBodyMethodProcessor` takes over and runs content negotiation: it inspects the `Accept` header (and configured defaults), then picks an `HttpMessageConverter` whose supported media type matches — typically `MappingJackson2HttpMessageConverter` for `application/json`.

```
return value
   │
   ├─ @ResponseBody present? ── yes ─► content negotiation ─► HttpMessageConverter ─► body
   │
   └─ no ─► treat String as view name ─► ViewResolver ─► render template
```

The interview nuance: a `@Controller` method that returns a `String` "user/profile" renders a template, but the same method on a `@RestController` would serialize the literal string `"user/profile"` as the JSON body — a common source of confusion when someone copies a method between the two.

### 🟡 Intermediate — extended

#### Q35. [Theory] How does the Spring Boot 2.4+ Config Data API differ from the legacy property loading, and why was `bootstrap.yml` deprecated?

Before 2.4, profile-specific configuration was loaded with a "profile-then-property" model that produced surprising ordering: all `application.yml` documents were collected, then profile-specific files were layered, and the interaction with `spring.profiles.include` and multi-document files was order-insensitive and hard to reason about. Spring Boot 2.4 introduced the **Config Data API**, which processes configuration as an ordered, *document-by-document* import sequence with deterministic, top-to-bottom precedence within a file. This makes the new `spring.config.import` property the canonical way to pull in external config (files, Vault, Consul, Kubernetes ConfigMaps), and it replaces the older bootstrap mechanism.

```yaml
# Modern, ordered, document-based activation (2.4+)
spring:
  config:
    import: "optional:configserver:http://config:8888"   # explicit import, no bootstrap.yml
---
spring:
  config:
    activate:
      on-profile: prod          # replaces deprecated spring.profiles
  datasource:
    url: jdbc:postgresql://prod-db/app
```

`bootstrap.yml` (and the separate "bootstrap context" from Spring Cloud) was deprecated because the Config Data API can now import remote config *within the main application context*, eliminating the need for a pre-context phase. The bootstrap context existed only to fetch remote config before the main context started; with `spring.config.import` supporting `configserver:`, `vault:`, etc., that two-context complexity is unnecessary. You re-enable the legacy behaviour with `spring.cloud.bootstrap.enabled=true` only if a library still depends on it. The deeper lesson is that the new model trades a little learning curve for *deterministic, debuggable* precedence — you can reason about exactly which document wins by reading top to bottom.

#### Q36. [Theory] Explain how `@ConfigurationProperties` binding works under the hood, including relaxed binding and the difference from `@Value`.

`@ConfigurationProperties` triggers Spring Boot's `Binder`, a dedicated binding engine that walks a target object's properties and pulls matching values from all `PropertySource`s in the `Environment`. Its signature feature is **relaxed binding**: the canonical property name (kebab-case, e.g. `retry-attempts`) is matched against many forms — `retryAttempts`, `retry_attempts`, `RETRY_ATTEMPTS` — so the same property binds whether it comes from YAML, an env var (`BILLING_RETRY_ATTEMPTS`), or a system property. The `Binder` also runs the `ConversionService` to coerce strings into rich types (`Duration`, `DataSize`, `Period`, enums, nested objects, lists, maps) and then applies JSR-303 validation if the class is `@Validated`.

`@Value`, by contrast, uses SpEL/`${...}` placeholder resolution against a single resolved property. It does *not* do relaxed binding (the key must match exactly), does *not* validate, and binds one value at a time. So `@Value("${billing.retryAttempts}")` fails if the property is written `retry-attempts`, whereas `@ConfigurationProperties` handles both.

```java
@ConfigurationProperties(prefix = "billing")
@Validated
public record BillingProperties(
    @NotBlank String currency,
    @Positive int retryAttempts,     // binds from retry-attempts / RETRY_ATTEMPTS / retryAttempts
    Duration timeout,                // "5s" -> Duration via ConversionService
    Map<String, String> tags) {}
```

The trade-off and recommendation: prefer `@ConfigurationProperties` for any *group* of related settings (type safety, validation, IDE metadata, immutability with records) and reserve `@Value` for a single one-off literal where a whole class is overkill. A subtle gotcha — constructor binding (records/`@ConstructorBinding`) requires no setters and is immutable, while setter binding allows partial population; choosing the wrong style is a frequent source of "my property is null" confusion.

#### Q37. [Theory] Compare `RestTemplate`, `WebClient`, and `RestClient`. Why does Spring now recommend `RestClient` for synchronous calls?

`RestTemplate` is the original synchronous, blocking HTTP client built on a template-method design. It still works but has been in **maintenance mode** since Spring 5 — no new features, and its fluent ergonomics are dated. `WebClient` (Spring 5, WebFlux) is fully reactive and non-blocking, returning `Mono`/`Flux`; it can be used synchronously via `.block()`, but pulling the entire reactive stack (Reactor Netty) into a purely blocking app just to make HTTP calls is heavyweight. `RestClient` (Spring 6.1 / Boot 3.2) is the modern answer: a **synchronous** client with `WebClient`'s fluent, modern API but no reactive dependency — it reuses the same `HttpMessageConverter` infrastructure as `RestTemplate`.

| Client | Style | Stack | Status / use |
|--------|-------|-------|--------------|
| `RestTemplate` | blocking | servlet | maintenance mode; legacy code |
| `WebClient` | reactive (Mono/Flux) | WebFlux/Reactor | reactive apps, or streaming |
| `RestClient` | blocking, fluent | servlet | **recommended** for new sync code (Boot 3.2+) |

```java
// Modern synchronous call — fluent like WebClient, no reactive types
Customer c = restClient.get()
        .uri("/customers/{id}", id)
        .retrieve()
        .onStatus(HttpStatusCode::is4xxClientError,
                  (req, res) -> { throw new CustomerNotFound(id); })
        .body(Customer.class);
```

The recommendation logic: most services are blocking, and forcing them to learn Reactor just to call a REST API was friction. `RestClient` gives the *ergonomics* of `WebClient` without the *cognitive cost* of reactive programming, and it can even be backed by the same underlying request factory (JDK `HttpClient`, Apache, Jetty) and benefit from virtual threads. You still reach for `WebClient` when you genuinely need non-blocking concurrency or streaming. A practical bonus in Boot 3.x is the **HTTP Interface** (`@HttpExchange`) declarative client, which can be backed by either `RestClient` or `WebClient`.

#### Q38. [Theory] What is the difference between a Servlet `Filter`, a Spring `HandlerInterceptor`, and AOP advice? When do you use each?

These three intercept requests at different *layers* of the stack, and choosing the wrong one causes subtle bugs. A **Servlet `Filter`** lives in the servlet container *outside* Spring MVC — it wraps the entire request/response before `DispatcherServlet` even runs, sees the raw `ServletRequest`/`ServletResponse`, and is the right place for cross-cutting concerns that must run for *every* request regardless of whether a controller handles it: authentication (Spring Security is a filter chain), CORS, request logging, compression, correlation-id injection. A **`HandlerInterceptor`** runs *inside* `DispatcherServlet`, around handler execution, with `preHandle`/`postHandle`/`afterCompletion` hooks; it knows which handler (controller method) was selected, so it is ideal for MVC-aware concerns like per-handler authorization, modifying the model, or timing controller execution. **AOP advice** (`@Around`, etc.) operates at the *bean method* level, agnostic of HTTP — use it for transactional, caching, retry, or logging concerns on service-layer methods.

```
HTTP request
  │
  ▼
Servlet Filter chain   ◄── raw request, runs for everything (Security lives here)
  │
  ▼
DispatcherServlet
  ├─ HandlerInterceptor.preHandle   ◄── knows the chosen controller method
  ├─ AOP-advised service call (@Transactional / @Cacheable)  ◄── bean-method level
  ├─ controller returns
  └─ HandlerInterceptor.postHandle / afterCompletion
```

The decision rule: the further out you go (Filter → Interceptor → AOP), the less Spring-MVC context you have but the broader the coverage. Security and anything that must run even for unmapped/static requests belongs in a Filter. Anything needing the resolved handler belongs in an Interceptor. Anything that is really about *business method* behaviour (transactions, caching) belongs in AOP and should not be tangled into the web layer at all.

#### Q39. [Practical] How does Spring's `@Cacheable` abstraction actually work, and what are its proxy-related and key-related pitfalls?

`@Cacheable` is implemented exactly like `@Transactional`: a `BeanPostProcessor` wraps the bean in an AOP proxy, and a `CacheInterceptor` runs around the method. On invocation it computes a cache key (default `SimpleKeyGenerator` over the arguments, or a SpEL `key` expression), looks the key up in the configured `CacheManager`, returns the cached value on a hit, and on a miss calls the real method and stores the result. `@CachePut` always executes and updates the cache; `@CacheEvict` removes entries. Spring Boot auto-configures a `CacheManager` based on what is on the classpath (Caffeine, Redis, etc.) once you add `@EnableCaching`.

```java
@Cacheable(cacheNames = "prices", key = "#sku", unless = "#result == null")
public Price lookup(String sku) { ... }   // not cached when null

@CacheEvict(cacheNames = "prices", key = "#sku")
public void invalidate(String sku) { ... }
```

The pitfalls mirror the proxy model: (1) **self-invocation** — calling a `@Cacheable` method from another method in the same bean bypasses the proxy, so nothing is cached. (2) **Key design** — the default key uses *all* arguments; if you cache on the wrong argument set you get collisions or near-zero hit rate; for null-or-empty arguments the default `SimpleKey` can collide, so be explicit with a SpEL `key`. (3) **No built-in TTL** in the abstraction itself — `@Cacheable` has no expiry; TTL/eviction is configured on the *cache provider* (Caffeine `expireAfterWrite`, Redis `entryTtl`), and forgetting this yields an unbounded, stale cache. (4) **Caching `Optional`/null** needs `unless`/`@Cacheable(... )` care so you don't cache failures. (5) In a distributed system, a local Caffeine cache is per-instance, so eviction on one node does not propagate — use Redis or a cache-invalidation message for consistency.

#### Q40. [Theory] What is the difference between JDK dynamic proxies and CGLIB proxies in Spring, and how does Spring choose?

Spring AOP (which powers `@Transactional`, `@Async`, `@Cacheable`, `@Validated`, etc.) needs to create a proxy that wraps your bean. It has two strategies. **JDK dynamic proxies** are built into the JDK and proxy *interfaces*: they generate a runtime class implementing the bean's interfaces and delegating to an `InvocationHandler`. **CGLIB** proxies generate a *subclass* of the target class at runtime, overriding methods to insert the advice. The consequence: JDK proxies require the bean to implement an interface and only the interface methods are advised; CGLIB works on concrete classes but cannot proxy `final` classes or `final`/`private`/`static` methods (they can't be overridden).

```
Bean implements an interface?
   ├─ yes + proxyTargetClass=false  ──► JDK dynamic proxy (implements interface)
   └─ no, OR proxyTargetClass=true  ──► CGLIB subclass proxy
```

Historically Spring defaulted to JDK proxies when an interface was present and CGLIB otherwise, but **Spring Boot sets `proxyTargetClass=true` by default**, so Boot apps use CGLIB even for interface-bearing beans. This was a deliberate choice to avoid the surprise of "I injected the concrete type but got a proxy that only implements the interface" `ClassCastException`s, and to make proxying behave consistently. The practical implications you must remember: never make a `@Transactional`/`@Async` class or method `final` (the advice silently disappears with CGLIB, or fails to create the proxy); `private` methods are never advised; and self-invocation bypasses the proxy entirely regardless of which proxy type is used. Knowing which proxy is in play also explains odd injection issues — e.g. AspectJ load-time weaving avoids proxies altogether and so escapes these limitations, which is why some advanced setups switch to it.

#### Q41. [Theory] How does Spring resolve ambiguous dependency injection — explain `@Primary`, `@Qualifier`, and the resolution algorithm.

When multiple beans satisfy a single injection point, Spring follows a deterministic algorithm before giving up with `NoUniqueBeanDefinitionException`. The steps: (1) narrow candidates by *required type*; (2) if more than one remains, prefer a bean marked `@Primary`; (3) if no primary, try matching by `@Qualifier` value; (4) failing that, fall back to matching the *bean name* against the field/parameter name (this is why the parameter name accidentally mattering is a thing); (5) if still ambiguous, throw. There is also `@Priority` (Jakarta) as a tiebreaker after `@Primary`.

```java
@Bean @Primary DataSource primaryDs() { ... }    // default winner
@Bean @Qualifier("reporting") DataSource reportingDs() { ... }

@Service
class ReportService {
    ReportService(@Qualifier("reporting") DataSource ds) { ... } // explicit pick
}
class OrderService {
    OrderService(DataSource ds) { ... }            // gets the @Primary one
}
```

The design intent distinguishes the two annotations: **`@Primary` declares a sensible *default*** for the common case ("when in doubt, inject this one"), while **`@Qualifier` is the *explicit override*** for the specific case that wants the non-default bean. Using `@Primary` keeps most injection points clean and only forces `@Qualifier` where you genuinely need the alternative. The subtle trap interviewers probe is the *bean-name fallback*: code like `DataSource reportingDs` may work today by name-matching but silently break if someone renames the field or bean — so for anything but the primary you should be explicit with `@Qualifier` rather than relying on parameter-name coincidence. For collections, injecting `List<Handler>` or `Map<String, Handler>` gathers *all* matching beans (ordered by `@Order`), which is the idiomatic way to consume "all implementations of a strategy".

### 🟠 Advanced — extended

#### Q42. [Theory] How does Spring detect and handle circular dependencies, and why did Boot 2.6 make them fail by default?

A circular dependency is when bean A needs B and B needs A. Spring can resolve *some* cycles for **singleton, setter/field-injected** beans using a three-level cache and early bean references: it instantiates A (raw, not fully initialized), exposes an "early reference" to A while it is still being created, injects that early reference into B, finishes B, then completes A. This works only because setter/field injection happens *after* construction, leaving a window to inject a half-built reference. **Constructor injection cycles cannot be resolved** — A's constructor needs a fully-formed B and vice versa, which is impossible, so you get `BeanCurrentlyInCreationException`.

```
A (constructor needs B) ──► B (constructor needs A)  ── unresolvable, throws
A (field needs B)       ──► B (field needs A)        ── resolvable via early reference
                                                         (but discouraged)
```

Spring Boot **2.6 changed the default to prohibit circular references** (`spring.main.allow-circular-references=false`), so even the resolvable setter/field cycles now fail at startup unless you opt back in. The rationale is that a circular dependency is almost always a *design smell* — it indicates two beans with tangled responsibilities, and the silent early-reference resolution masked that, sometimes producing subtle initialization-order bugs (a bean observing a not-yet-fully-initialized collaborator). The intended fixes, in order of preference: refactor to remove the cycle (extract a third collaborator, or move the shared logic), use an event/`ApplicationEventPublisher` to decouple, use `@Lazy` on one injection point so a proxy breaks the construction-time cycle, or as a last resort set `allow-circular-references=true`. Interviewers want to hear that you treat the cycle as something to *eliminate*, not configure around.

#### Q43. [Theory] Explain how `@Async` works internally, why the return type matters, and the common gotchas.

`@Async` is, again, AOP-proxy-based: with `@EnableAsync`, a `BeanPostProcessor` wraps annotated beans, and an `AsyncExecutionInterceptor` submits the method body to a `TaskExecutor` instead of running it on the caller's thread. The caller returns immediately. The return type dictates how results flow back: `void` (fire-and-forget), `Future<T>`/`CompletableFuture<T>`/`ListenableFuture<T>` (the proxy returns a future the caller can compose or join). Returning a plain value other than a future is meaningless because the real value isn't computed yet — the proxy can only hand back a placeholder.

```java
@Async("appTaskExecutor")
public CompletableFuture<Report> generate(long id) {
    Report r = heavyComputation(id);          // runs on a pool thread
    return CompletableFuture.completedFuture(r);
}
```

The gotchas are the canonical proxy ones plus async-specific ones: (1) **self-invocation** — calling an `@Async` method from within the same bean runs it synchronously because the proxy is bypassed. (2) **The default executor** — if you don't define a `TaskExecutor`, Boot historically used a `SimpleAsyncTaskExecutor` that creates a *new thread per task* (no pooling) which can exhaust resources under load; you should define a bounded `ThreadPoolTaskExecutor` (or, in Boot 3.2+, virtual threads via `spring.threads.virtual.enabled=true`). (3) **Exception handling** — for `void` async methods, exceptions vanish unless you register an `AsyncUncaughtExceptionHandler`; for future-returning methods the exception surfaces when the future is resolved. (4) **Context propagation** — security context, MDC (trace ids), and request-scoped data do *not* automatically cross to the async thread; you need a `TaskDecorator` (or Micrometer's context-propagation) to copy them. (5) `@Async` and `@Transactional` on the same method interact badly because the transaction is bound to a thread — the async thread starts a *new* transactional context, so the caller's transaction does not extend into it.

#### Q44. [Theory] How does the Spring `ApplicationEvent` mechanism work, and when does `@EventListener` run synchronously vs asynchronously?

Spring's event system is an in-process publish/subscribe built on the `ApplicationEventMulticaster`. You publish via `ApplicationEventPublisher.publishEvent(...)` (the `ApplicationContext` implements it), and any bean method annotated `@EventListener` (or implementing `ApplicationListener`) whose parameter type matches the event is invoked. **By default, event publishing is *synchronous* and single-threaded** — `publishEvent` blocks until every listener has run, on the *publisher's thread*, in the *publisher's transaction*. This is a frequent surprise: people assume events are async and decoupled in time, but by default they are merely decoupled in *code*, not in execution.

```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void onOrderPlaced(OrderPlacedEvent e) {
    notificationService.send(e.orderId());   // runs only after the tx commits
}
```

Two refinements matter at senior level. First, `@TransactionalEventListener` binds listener execution to a transaction phase — `AFTER_COMMIT` (the default) ensures side effects (sending an email, emitting a message) happen *only if the originating transaction actually committed*, eliminating the classic "we emailed the customer then the DB rolled back" bug. Second, to make a listener truly asynchronous you either annotate it `@Async` (requires `@EnableAsync`) or configure an async `ApplicationEventMulticaster` with a `TaskExecutor`; otherwise a slow listener blocks the publisher. The design trade-off: in-process events are great for decoupling modules within one deployable (a "modular monolith"), but they are *not* durable — if the JVM dies between publish and handling, the event is lost. For cross-service or guaranteed delivery you graduate to a real message broker (Kafka/Rabbit), often via the transactional-outbox pattern.

#### Q45. [Theory] What are the variants of `@Import` and `ImportSelector`/`ImportBeanDefinitionRegistrar`, and how do `@Enable*` annotations use them?

`@Import` is the programmatic way to pull configuration into the context, and it accepts three kinds of arguments, each more powerful than the last. (1) A plain `@Configuration` class — straightforwardly registers its beans. (2) An **`ImportSelector`** — a class whose `selectImports` returns an array of class names to register, computed at processing time; this enables *conditional* sets of configuration based on the importing class's annotations (its `@Deferred` variant runs after regular configs, which is exactly what auto-configuration uses). (3) An **`ImportBeanDefinitionRegistrar`** — given the registry, it programmatically registers arbitrary `BeanDefinition`s, the most flexible (and lowest-level) option, used when bean definitions must be synthesized dynamically (e.g. one repository bean per discovered interface).

```java
public class FeatureSelector implements ImportSelector {
    @Override public String[] selectImports(AnnotationMetadata meta) {
        boolean advanced = (boolean) meta
            .getAnnotationAttributes(EnableFeature.class.getName()).get("advanced");
        return advanced
            ? new String[]{AdvancedConfig.class.getName()}
            : new String[]{BasicConfig.class.getName()};
    }
}
@Retention(RUNTIME) @Import(FeatureSelector.class)
public @interface EnableFeature { boolean advanced() default false; }
```

This is precisely the machinery behind the `@Enable*` family (`@EnableCaching`, `@EnableAsync`, `@EnableScheduling`, `@EnableAutoConfiguration`): each is a meta-annotation carrying an `@Import` that brings in the relevant configuration or a selector/registrar. `@EnableAutoConfiguration` imports `AutoConfigurationImportSelector` (a `DeferredImportSelector`), which reads the `AutoConfiguration.imports` file and applies condition filtering. Understanding this answers "how does adding one annotation wire up a whole subsystem" — and explains why ordering (`@AutoConfigureAfter`) and deferred selection exist: regular `@Configuration` must be processed before auto-config can decide what's missing, so auto-config defers.

#### Q46. [Practical] How does Spring Boot embed and start a web server, and how would you switch from Tomcat to Undertow or run on a different port at runtime?

In a servlet app, Spring Boot's `ServletWebServerApplicationContext` looks for a `ServletWebServerFactory` bean (auto-configured as `TomcatServletWebServerFactory` by default because `spring-boot-starter-web` brings Tomcat on the classpath). During `refresh()`, the context calls that factory to *create and start* the embedded server, registers the `DispatcherServlet` and any `Filter`/`Servlet` beans, and binds the port. There is no external container and no `web.xml`; the server is just another managed component whose lifecycle is tied to the context. The reactive stack is analogous but uses a `ReactiveWebServerFactory` and a `WebHandler` over Netty by default.

```xml
<!-- Swap Tomcat for Undertow: exclude Tomcat, add the Undertow starter -->
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-web</artifactId>
  <exclusions>
    <exclusion>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-tomcat</artifactId>
    </exclusion>
  </exclusions>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-undertow</artifactId>
</dependency>
```

Because the choice is *classpath-driven* via `@ConditionalOnClass`, swapping servers is a dependency change, not a code change — exclude `spring-boot-starter-tomcat` and add `-jetty` or `-undertow`. Configuration is uniform through `server.*` properties (`server.port`, `server.tomcat.*`, etc.), and you customize the server programmatically with a `WebServerFactoryCustomizer` bean. For runtime port behaviour: `server.port=0` binds an *ephemeral* port (useful in tests, retrieved via the `WebServerInitializedEvent` or `@LocalServerPort`), and the management server can run on a *separate* port via `management.server.port`. You cannot truly rebind the main port after startup without restarting the context — the bind happens once at `refresh()`.

#### Q47. [Theory] Explain how the executable fat jar's nested classloading works and why you cannot simply unzip it onto a normal classpath.

A standard JAR cannot itself contain *other* runnable JARs on the classpath — the JVM's classpath mechanism does not look inside nested jars. Spring Boot solves this with a custom layout and a custom classloader. The fat jar places your code under `BOOT-INF/classes/` and your dependency jars *unexploded* under `BOOT-INF/lib/`, with a tiny launcher (`org.springframework.boot.loader.launch.JarLauncher`) declared as the jar's `Main-Class` in the manifest (the manifest also records your real `Start-Class`). When you run `java -jar app.jar`, the JVM runs `JarLauncher`, which builds a `LaunchedClassLoader` (formerly `LaunchedURLClassLoader`) that knows how to read classes *directly out of the nested jars* without extracting them to disk, then it loads and invokes your `Start-Class`.

```
app.jar
 ├─ META-INF/MANIFEST.MF        Main-Class: ...JarLauncher
 │                              Start-Class: com.acme.MyApplication
 ├─ org/springframework/boot/loader/...   (the launcher)
 └─ BOOT-INF/
      ├─ classes/               your compiled code
      └─ lib/                   dependency-*.jar (nested, not exploded)
```

You cannot just `unzip` the fat jar and run it with `-cp BOOT-INF/classes` because the dependencies in `BOOT-INF/lib/*.jar` are nested jars the default system classloader won't traverse, and the nested jars deliberately use *stored* (uncompressed) entries so they can be memory-mapped — extraction defeats that. This design keeps the artifact a single, self-contained, signed unit while still honoring jar boundaries (no class-file flattening that would break jars relying on their own `MANIFEST` or sealed packages). For container builds you *do* explode it deliberately — via `--jarmode=layertools extract` or the newer `--jarmode=tools extract` — but onto a layout the launcher still understands, which is how layered Docker images (Q16) and faster startup are achieved.

#### Q48. [Theory] How does Spring Boot decide between the servlet and reactive web stacks, and what determines `WebApplicationType`?

When `SpringApplication.run` executes, it computes a `WebApplicationType` by inspecting the classpath, and that single decision drives which `ApplicationContext`, which auto-configurations, and which embedded server get used. The rule: if Spring WebFlux classes (`org.springframework.web.reactive.DispatcherHandler`) are present *and* Spring MVC's `DispatcherServlet`/servlet API are *not*, it picks `REACTIVE`; if the servlet/MVC classes are present it picks `SERVLET`; if neither web stack is present it picks `NONE` (a plain non-web app, e.g. a batch job or CLI). When *both* MVC and WebFlux are on the classpath, **MVC wins** (`SERVLET`) — a deliberate tie-breaker, because mixing both in one context is usually accidental.

```
WebFlux present & MVC absent           ──► REACTIVE  (Netty, AnnotationConfigReactiveWebServerApplicationContext)
MVC/servlet present (with or w/o flux) ──► SERVLET   (Tomcat, AnnotationConfigServletWebServerApplicationContext)
neither                                ──► NONE      (no embedded server)
```

The implications interviewers chase: (1) You can *force* the type with `spring.main.web-application-type=reactive|servlet|none`, which is how you run a WebFlux-style `WebClient`-only app without accidentally starting Tomcat, or run a web library in a non-web context for tests. (2) The choice cascades — in a `REACTIVE` app, blocking auto-configs and servlet filters don't apply, and you must use `WebFilter` instead of servlet `Filter`. (3) A common real bug is depending on a library that transitively pulls `spring-webmvc` into a WebFlux service, flipping it to `SERVLET` and starting Tomcat unexpectedly; you diagnose it from the startup log ("Tomcat started") and the condition report, and fix it by excluding the offending transitive dependency or pinning the web-application-type.

### 🔴 Expert — extended

#### Q49. [Theory] Explain bean definition overriding, how it changed across Spring Boot versions, and why duplicate-bean-name behaviour matters.

A `BeanDefinition` is registered under a *name*. If two definitions claim the same name, one must win — historically the *last* registered definition silently overrode the earlier one. Spring Boot **2.1 disabled bean overriding by default** (`spring.main.allow-bean-definition-overriding=false`), so a duplicate name now throws `BeanDefinitionOverrideException` at startup. This was a safety change: silent overriding caused mystifying production bugs where an accidental same-named bean (often pulled in by a transitive auto-config or a copy-pasted `@Bean` method) replaced the intended one, and nobody noticed until behaviour was subtly wrong.

```
Two beans named "objectMapper"
   ├─ Boot < 2.1 default ──► last one silently wins (dangerous)
   └─ Boot >= 2.1 default ──► BeanDefinitionOverrideException at startup (fail fast)
```

The senior nuances: (1) This is *name* collision, not *type* collision — two differently-named beans of the same type are fine and resolved by `@Primary`/`@Qualifier` (Q41); overriding is specifically about identical names. (2) Auto-configuration relies on `@ConditionalOnMissingBean` (not overriding) to back off in favour of your beans — that is the *intended* override mechanism, and it is keyed on type/name conditions rather than blind replacement. (3) When you legitimately need to replace a bean (e.g. in tests, or to substitute an auto-configured bean), you either define it with the same name and set `allow-bean-definition-overriding=true`, or better, use the condition system / test support (`@MockitoBean`, `@TestConfiguration`) so the replacement is explicit. The principle to articulate: Spring Boot's modern defaults push *every* ambiguity (circular refs, bean overriding, missing properties) to fail loudly at startup rather than behave surprisingly at runtime — fail-fast is a core design philosophy.

#### Q50. [Theory] How does Spring's `ConversionService` and the type-conversion SPI work, and how do you register a custom converter that participates in property binding?

Spring centralizes all type conversion behind the `ConversionService`, a registry of `Converter<S,T>`, `ConverterFactory`, and `GenericConverter` implementations. Whenever the framework needs to turn one type into another — binding a `String` property to a `Duration`, converting a request param to an enum, coercing a SpEL result — it asks the `ConversionService` for a matching converter. This replaced the older, per-thread `PropertyEditor` model (still used in some web binding paths) with a stateless, thread-safe, composable design. Spring Boot pre-registers many converters (durations, data sizes, periods, `Charset`, `InetAddress`, delimited lists) so configuration "just works".

```java
@Component
@ConfigurationPropertiesBinding              // makes it available to @ConfigurationProperties binding
public class StringToMoneyConverter implements Converter<String, Money> {
    @Override public Money convert(String source) {
        return Money.parse(source);          // "USD 19.99" -> Money
    }
}
```

The subtlety that trips people up is *which* `ConversionService` is consulted in *which* context. There are effectively several: the application-wide `ConversionService` (used broadly), the MVC `WebConversionService`/`FormattingConversionService` (used for `@RequestParam`/`@PathVariable` binding and supports `Formatter`s with locale awareness), and the dedicated binding path for `@ConfigurationProperties`. To make a custom converter participate in *property binding* you must annotate it `@ConfigurationPropertiesBinding`; to make it participate in *web* binding you register it via `WebMvcConfigurer.addFormatters`. Forgetting this distinction is why "my converter works for request params but not for `@ConfigurationProperties`" (or vice versa) — they pull from different registries. Articulating this layered design, and that converters should be stateless and bidirectional pairs where needed, signals deep familiarity.

#### Q51. [Theory] At a deep level, what does Spring AOT processing generate, and what does GraalVM reachability metadata actually contain?

Spring AOT (Ahead-Of-Time) processing runs at *build time* and transforms the dynamic, reflection-and-condition-driven Spring bootstrap into *explicit, statically analyzable* code. Concretely it produces: (1) **generated bean-registration code** — instead of scanning the classpath and evaluating `@Conditional`s at runtime, AOT evaluates the conditions *once at build time* against the build-time classpath/environment and emits Java source (`*__BeanDefinitions`, an `ApplicationContextInitializer`) that registers exactly the beans that matched; (2) **proxy classes** generated statically rather than via runtime CGLIB; (3) **GraalVM reachability metadata** — JSON hint files describing every use of reflection, resources, dynamic proxies, and serialization that the closed-world `native-image` analysis cannot infer on its own.

```
reflect-config.json     ── classes/methods/fields accessed reflectively (e.g. JPA entities, Jackson DTOs)
resource-config.json    ── classpath resources loaded at runtime (messages.properties, templates)
proxy-config.json       ── interface sets needing JDK dynamic proxies
serialization-config.json ── types serialized/deserialized
```

The reason this exists is the **closed-world assumption**: `native-image` compiles only code it can prove reachable, and removes everything else; anything reached *only* via reflection or dynamic proxies is invisible to static analysis and would crash at runtime with `ClassNotFoundException`/`NoSuchMethodException`. The metadata tells the compiler "keep these elements and allow reflective access". Frameworks ship their own metadata, and Spring's AOT generates yours, but gaps remain for libraries lacking hints — which is why you supply `@RegisterReflectionForBinding`, `RuntimeHintsRegistrar`, or `@ImportRuntimeHints`. The deep trade-off to articulate: AOT *freezes* decisions that were dynamic on the JVM — active profiles, `@ConditionalOnProperty` that affect bean structure, and any "decide at runtime which implementation to wire" pattern must be reconsidered, because in native the wiring is fixed at build time. This is why a native build can behave differently from the JVM build and must be tested as its own artifact.

#### Q52. [Theory] How does the Spring Security filter chain integrate with Spring Boot auto-configuration, and what is the order/role of the key filters?

Spring Security is implemented as a single servlet `Filter` — the `DelegatingFilterProxy` named `springSecurityFilterChain` — registered with the servlet container, which delegates to a `FilterChainProxy`. That proxy holds one or more `SecurityFilterChain` beans, each matching a request pattern and containing an *ordered list of security filters*. Spring Boot's auto-configuration (`SecurityAutoConfiguration` + `SpringBootWebSecurityConfiguration`) wires a sensible default chain when you add `spring-boot-starter-security`: HTTP Basic + form login, CSRF on, all endpoints authenticated, and a generated password logged at startup. The moment you declare your *own* `SecurityFilterChain` bean, the defaults back off (the familiar `@ConditionalOnMissingBean` pattern), and you own the configuration.

```java
@Bean
SecurityFilterChain api(HttpSecurity http) throws Exception {
    return http
        .securityMatcher("/api/**")
        .authorizeHttpRequests(a -> a.anyRequest().hasRole("USER"))
        .csrf(csrf -> csrf.disable())               // stateless API
        .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()))
        .build();
}
```

The ordering within a chain is fixed and meaningful — roughly: `SecurityContextPersistenceFilter`/`SecurityContextHolderFilter` (loads any existing context) → `CsrfFilter` → authentication filters (`UsernamePasswordAuthenticationFilter`, `BearerTokenAuthenticationFilter`, etc.) → `ExceptionTranslationFilter` (turns `AccessDeniedException`/`AuthenticationException` into 401/403 or redirects) → `AuthorizationFilter` (formerly `FilterSecurityInterceptor`, the final access-decision gate). Two expert points: (1) authentication establishes *who you are* and runs before the `AuthorizationFilter` that decides *what you may do* — getting this order wrong (or short-circuiting in a custom filter) creates security holes. (2) Because security is a *servlet filter* it sits *outside* Spring MVC, so it can reject a request before any controller or `HandlerInterceptor` runs — which is exactly why authentication belongs there (Q38) and why a WebFlux app uses a parallel but different `WebFilter`-based `SecurityWebFilterChain` instead.

#### Q53. [Theory] Explain how Jackson serialization is configured in Spring Boot and the internals of how a return value becomes JSON.

When a `@RestController` method returns an object, `RequestResponseBodyMethodProcessor` selects an `HttpMessageConverter`; for JSON that is `MappingJackson2HttpMessageConverter`, which delegates to a Jackson `ObjectMapper`. Spring Boot **auto-configures a single, shared `ObjectMapper`** via `JacksonAutoConfiguration`, applying sensible defaults (e.g. `WRITE_DATES_AS_TIMESTAMPS` disabled so `java.time` types serialize as ISO-8601, provided `jackson-datatype-jsr310` is present) and honouring `spring.jackson.*` properties. The serialization itself walks the object graph reflectively (or via Jackson's compiled accessors), respecting Jackson annotations (`@JsonProperty`, `@JsonIgnore`, `@JsonInclude`, `@JsonFormat`) and registered modules.

```yaml
spring:
  jackson:
    default-property-inclusion: non_null   # omit null fields globally
    serialization:
      write-dates-as-timestamps: false     # ISO-8601 dates
    deserialization:
      fail-on-unknown-properties: false    # tolerate extra fields from clients
```

There are three "right" levels at which to customize, in increasing intrusiveness: (1) `spring.jackson.*` properties for global behaviour; (2) a `Jackson2ObjectMapperBuilderCustomizer` bean to programmatically tweak the *auto-configured* builder *without* replacing it (the preferred approach — you keep Boot's defaults and add to them); (3) defining your own `ObjectMapper` `@Bean`, which *replaces* Boot's and means you inherit none of its defaults (a common cause of "dates suddenly serialize as timestamps in prod"). The internals nuance worth raising: the same shared `ObjectMapper` is used for both web (de)serialization and anywhere else you inject it, so changing it has app-wide reach; and because reflective field access on entities/DTOs is involved, this path needs reachability metadata in native images (Q51). Senior candidates also mention that for performance-critical or schema-strict APIs you might bypass reflection with compiled serializers or move to a different format (protobuf), trading flexibility for speed.

#### Q54. [Practical] How does `@Scheduled` work internally, and how would you make scheduled tasks safe in a multi-instance deployment?

`@EnableScheduling` registers a `ScheduledAnnotationBeanPostProcessor` that scans beans for `@Scheduled` methods and registers them with a `TaskScheduler` (by default a single-threaded `ThreadPoolTaskScheduler`). Each method becomes a task driven by `fixedDelay` (next run starts N ms after the previous *finishes*), `fixedRate` (runs every N ms regardless of duration — overlapping if a run exceeds the interval, *unless* the scheduler thread is busy), or `cron` (a six-field cron expression with optional timezone). The single-threaded default is a classic trap: one long-running task delays all others, so you almost always define a multi-threaded `ThreadPoolTaskScheduler`.

```java
@Scheduled(cron = "0 */5 * * * *", zone = "UTC")   // every 5 minutes, UTC
public void reconcile() { ... }

@Bean
TaskScheduler taskScheduler() {
    var s = new ThreadPoolTaskScheduler();
    s.setPoolSize(4);                                // avoid one task blocking others
    return s;
}
```

The hard problem is **multi-instance safety**: `@Scheduled` runs *independently on every instance*, so with three replicas a "send daily report" job fires three times. Spring itself has no clustering for this. The standard solutions: (1) a distributed lock — **ShedLock** is the idiomatic library, wrapping the method so only the instance that acquires a lock (in a shared DB/Redis) executes, others skip; (2) leader election (e.g. via the platform or Spring Integration's leader API) so only the leader schedules; (3) externalize scheduling entirely to a clustered scheduler (Quartz in clustered/JDBC mode, or a platform cron that hits an endpoint). The other production concerns: make tasks *idempotent* (a run might overlap a previous one or repeat after a restart), set timeouts so a hung downstream doesn't wedge the scheduler thread, instrument run duration/failures, and remember that `fixedRate` measures from start-to-start while `fixedDelay` measures end-to-start — choosing wrong causes either drift or unintended overlap.

#### Q55. [Theory] What exactly is `@Conditional`, how does the condition-evaluation engine work, and how do auto-config conditions get ordered and filtered for performance?

`@Conditional(SomeCondition.class)` attaches a `Condition` implementation to a bean/configuration; before registering the associated bean definitions, Spring calls `Condition.matches(ConditionContext, AnnotatedTypeMetadata)` and skips registration if it returns false. The `ConditionContext` exposes the `BeanFactory` (so far), the `Environment`, the `ResourceLoader`, and the `ClassLoader`, which is how conditions can inspect properties, existing beans, and classpath presence. All of Boot's `@ConditionalOnX` annotations are thin wrappers over `Condition` implementations (`OnClassCondition`, `OnBeanCondition`, `OnPropertyCondition`, etc.).

```java
public class OnAwsCondition implements Condition {
    @Override public boolean matches(ConditionContext ctx, AnnotatedTypeMetadata md) {
        return ctx.getEnvironment().containsProperty("AWS_EXECUTION_ENV");
    }
}
@Bean @Conditional(OnAwsCondition.class) AwsMetadataClient awsClient() { ... }
```

The performance and correctness subtleties are what separate a deep answer from a textbook one. First, **ordering**: `OnBeanCondition`/`OnMissingBean` depend on *which beans already exist*, so the evaluation order of auto-configurations is significant — this is why `@AutoConfigureBefore`/`@AutoConfigureAfter`/`@AutoConfigureOrder` exist and why auto-config is a *deferred* import processed after user configuration (so user beans exist before `@ConditionalOnMissingBean` checks run). Second, **filtering for speed**: with hundreds of candidate auto-configs, evaluating every condition is expensive, so Boot uses `AutoConfigurationImportFilter`s (notably `OnClassCondition` implemented as an `AutoConfigurationImportFilter`) to *bulk-eliminate* configurations whose required classes are absent — checked early and cheaply against the classpath index — before the more expensive per-bean conditions run. Third, conditions implementing `ConfigurationCondition` declare a *phase* (`PARSE_CONFIGURATION` vs `REGISTER_BEAN`) so the engine knows whether a condition can be evaluated during parsing or must wait until bean definitions are known. The single best diagnostic remains the **Condition Evaluation Report** (`--debug`), which prints, for every candidate, the exact condition that matched or did not and why — the authoritative source of truth when wiring behaves unexpectedly.

#### Q56. [Theory] Compare CDS/AppCDS, Project CRaC, and GraalVM native image as startup-acceleration strategies. What does each trade off?

All three attack JVM/Spring startup cost but at different points in the lifecycle, with very different trade-offs. **Class Data Sharing (CDS/AppCDS)** memory-maps a pre-parsed archive of loaded class metadata so the JVM skips re-parsing class files at every boot; Spring Boot 3.3 added first-class support via a "training run" that records the classes loaded and produces an archive. It is the *least invasive* — still a normal JVM with JIT and full dynamism — and gives a meaningful but modest startup reduction (often 20–40%) with essentially no behavioural change. **Project CRaC (Coordinated Restore at Checkpoint)** goes further: you start and *warm up* the app, take a checkpoint (a snapshot of the entire process memory image), and later *restore* it in milliseconds with the JIT already warmed; Spring supports CRaC lifecycle hooks so beans can release/reacquire resources (open file handles, sockets, DB connections) around the checkpoint. **GraalVM native image** AOT-compiles to a standalone binary with no JVM, no JIT, and closed-world analysis (Q18/Q51).

```
                      startup        memory     peak throughput   build/ops cost      dynamism
CDS / AppCDS          modest faster  ~same      same (still JIT)   low (training run)  full
Project CRaC          ~ms restore    snapshot   warmed JIT         medium (snapshot)   full, but resource hooks
GraalVM native        ~50ms          ~half RSS  often LOWER        high (long builds)  closed-world (hints needed)
```

The decision framework: **GraalVM native** wins for short-lived/serverless/scale-to-zero workloads where cold-start latency and memory density dominate and peak throughput matters less, but you pay long build times, lose JIT peak performance for long-running CPU-bound work, and must manage reachability metadata. **CRaC** is compelling when you need near-instant startup *and* warmed-JIT peak performance (it keeps the full JVM) — but checkpoint/restore has operational and security considerations (the snapshot may contain secrets in memory; resources must be cleanly handled by hooks) and platform support is still maturing. **CDS** is the pragmatic, zero-risk default: keep everything about the JVM, just shave startup, ideal when you want a quick win without changing your deployment model. The expert framing is that these are not mutually exclusive in spirit — they sit on a spectrum from "same JVM, faster parse" (CDS) to "snapshot a warmed JVM" (CRaC) to "no JVM at all" (native) — and the right choice is dictated by the workload's startup-frequency, throughput profile, and tolerance for build/ops complexity. Looking ahead, **Project Leyden** aims to bring some of these AOT benefits into the mainline JVM, blurring the lines further.

#### Q57. [Theory] What is a `FactoryBean`, how does it differ from a `@Bean` factory method, and why does the `&` prefix exist?

A `FactoryBean<T>` is a special bean that the container treats as a *factory*: instead of exposing the `FactoryBean` instance itself, the context calls its `getObject()` and exposes the *product* of type `T` under the bean name. It is the mechanism Spring uses internally for complex, configuration-heavy objects whose construction is non-trivial — `SqlSessionFactoryBean` (MyBatis), `LocalContainerEntityManagerFactoryBean` (JPA), and `ProxyFactoryBean` (AOP) are all `FactoryBean`s. The interface also declares `getObjectType()` (so type-based injection works before the object is created) and `isSingleton()`.

```java
public class TenantClientFactoryBean implements FactoryBean<TenantClient> {
    private String region;
    public void setRegion(String r) { this.region = r; }
    @Override public TenantClient getObject() {            // container exposes THIS product
        return TenantClient.builder().region(region).build();
    }
    @Override public Class<?> getObjectType() { return TenantClient.class; }
}
```

The distinction from a `@Bean` factory method is conceptual: a `@Bean` method is *your* Java code in a `@Configuration` class that the container invokes once; a `FactoryBean` is a *registered bean* that itself participates in the lifecycle (it can be configured, injected, and itself depend on other beans) and the container knows to "unwrap" it. The famous `&` prefix exists precisely because of this unwrapping: `getBean("tenantClient")` returns the *product* (`TenantClient`), while `getBean("&tenantClient")` returns the *`FactoryBean` itself*. In modern application code you rarely write a `FactoryBean` — a `@Bean` method is simpler and clearer — but you must recognize them when reading framework/integration code, and the `&` prefix is a frequent "do you actually understand the container" interview probe.

#### Q58. [Theory] Explain the semantics of `@Lazy` — on a bean, on an injection point, and `spring.main.lazy-initialization`. What are the hidden costs?

`@Lazy` defers instantiation. On a bean definition (`@Lazy @Component`), the bean is not created at startup but on first *access*. On an *injection point* (`@Lazy` on a constructor parameter or field), Spring injects a lazy-resolving **proxy** instead of the real bean; the real bean is created the first time a method is called through the proxy. The global `spring.main.lazy-initialization=true` makes *every* bean lazy by default. The three forms have different blast radii: bean-level lazy affects one bean and its dependents that also defer; injection-point lazy is how you break a construction-time circular dependency (Q42) because the proxy defers the actual dependency resolution; global lazy changes the whole context's startup behaviour.

```java
@Service
class ReportService {
    private final HeavyEngine engine;
    ReportService(@Lazy HeavyEngine engine) { this.engine = engine; } // proxy injected
    // HeavyEngine is built only when a method on `engine` is first called
}
```

The hidden costs are why "just turn on lazy init to speed up startup" is a trap. (1) **Failures move from startup to runtime** — a misconfiguration that would have failed fast at boot now surfaces on the first user request that triggers the bean, defeating Spring's fail-fast philosophy and turning a deploy-time error into a production incident. (2) **First-request latency** — the work isn't eliminated, just deferred; the first request that touches a lazy subtree pays the construction cost, causing a latency spike (bad for autoscaling/canary thresholds). (3) **Proxy surprises** — `@Lazy` injection introduces a proxy with the usual `final`/equality/`instanceof` caveats. (4) It can *mask* genuine startup-cost problems you should fix directly. The sound use of `@Lazy` is *targeted*: defer one genuinely expensive, rarely-used bean, or break a specific cycle — not as a global band-aid. Global lazy init is reasonable for *dev-time* fast restarts but is discouraged in production for exactly the fail-fast reason.

#### Q59. [Theory] How does Spring MVC content negotiation work, and how do you control whether a request is served as JSON vs XML?

Content negotiation decides the *response* media type via a `ContentNegotiationManager` that consults an ordered list of `ContentNegotiationStrategy`s. The default and recommended strategy is **header-based**: the client's `Accept` header (e.g. `Accept: application/json`) is matched against the media types that the available `HttpMessageConverter`s can produce, and the best match wins. Spring Boot also (configurably) supports **path-extension** and **request-parameter** strategies (`?format=xml`), though extension-based negotiation is disabled by default in modern Boot for security reasons (it has historically enabled content-type confusion and RFD attacks).

```yaml
spring:
  mvc:
    contentnegotiation:
      favor-parameter: true          # enable ?mediaType=... 
      parameter-name: mediaType
      media-types:
        json: application/json
        xml: application/xml
```

```java
// You can also constrain at the handler: only produce JSON
@GetMapping(value = "/users/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
public UserDto get(@PathVariable long id) { ... }
```

The internals worth articulating: the set of *possible* media types is the union of what the registered converters support — so XML output requires an XML converter on the classpath (Jackson XML or JAXB); without it, `Accept: application/xml` yields `406 Not Acceptable`. The `produces` attribute on `@RequestMapping` narrows what a handler will emit and participates in request *mapping* (a request whose `Accept` cannot be satisfied by any handler's `produces` gets 406; a `consumes` mismatch gets 415). The senior recommendation is to rely on the `Accept` header (the HTTP-correct mechanism), disable path-extension negotiation, and be explicit with `produces`/`consumes` — this avoids the ambiguity and security pitfalls of inferring type from URL suffixes while keeping the API spec-compliant.

#### Q60. [Theory] How does Bean Validation integrate with Spring, and explain validation groups, cascading, and where `@Valid` is triggered vs method-level validation.

Spring integrates Jakarta Bean Validation (Hibernate Validator is the reference implementation) at two distinct layers. **Web/controller layer**: annotating a `@RequestBody`/`@ModelAttribute` parameter with `@Valid` (or Spring's `@Validated`) makes the `DispatcherServlet` run validation *before* the handler executes; failures throw `MethodArgumentNotValidException` (body) or `BindException` (form), which you translate to a 400 (Q12). **Method/bean layer**: putting `@Validated` on a Spring bean *class* enables a `MethodValidationPostProcessor` proxy that validates `@Valid`/constraint-annotated *method parameters and return values* on any service method, throwing `ConstraintViolationException`. These are different code paths producing different exceptions — a frequent source of "why isn't my validation firing" when `@Valid` is placed on a service method without `@Validated` on the class.

```java
public interface OnCreate {}   // validation group marker

public record UserDto(
    @Null(groups = OnCreate.class) Long id,             // must be absent on create
    @NotBlank @Email String email,
    @Valid Address address) {}                          // @Valid cascades into Address

@PostMapping
public void create(@Validated(OnCreate.class) @RequestBody UserDto dto) { ... }
```

Two mechanisms deserve depth. **Cascading**: `@Valid` on a *field* (not just the parameter) tells the validator to recurse into the nested object — without it, `Address`'s constraints are ignored. **Validation groups**: constraints can declare a `groups` attribute, and `@Validated(SomeGroup.class)` activates only constraints in that group, which is how you apply different rules for create vs update (e.g. `id` must be null on create but present on update) without duplicating DTOs. The plain JSR `@Valid` cannot specify a group — that capability is why Spring's `@Validated` exists. The expert nuance: validation runs *before* your business logic, so it is a cheap first line of defence, but it is not a substitute for invariants enforced in the domain/DB layer; and constraint *ordering* across groups can be sequenced with `@GroupSequence` when you want fail-fast staged validation.

#### Q61. [Theory] In Reactor/WebFlux, explain the difference between `subscribeOn` and `publishOn`, and why blocking the event loop is catastrophic.

In Project Reactor, operators by default execute on whatever thread emitted the signal — there is no implicit threading. Two operators move work across threads, and confusing them is the most common reactive bug. **`subscribeOn(scheduler)`** affects the *subscription* and therefore the source: it determines which thread the *whole upstream chain* (including the data-producing `subscribe` side) runs on, regardless of where it appears in the pipeline (there is effectively one effective `subscribeOn`, the closest to the source). **`publishOn(scheduler)`** is positional: it switches the thread for all operators *downstream* of where it appears, until the next `publishOn`. So `subscribeOn` chooses where the work *starts*, `publishOn` chooses where subsequent steps *continue*.

```java
Flux.fromIterable(ids)
    .subscribeOn(Schedulers.boundedElastic())   // source emission on a worker pool
    .map(this::cheapTransform)                   // still on boundedElastic
    .publishOn(Schedulers.parallel())            // switch: downstream on parallel pool
    .map(this::cpuBoundTransform)                // runs on parallel
    .subscribe();
```

Blocking the event loop is catastrophic because WebFlux serves *all* requests on a tiny pool of non-blocking event-loop threads (Reactor Netty: roughly one per CPU core). If you make a blocking call (JDBC, `Thread.sleep`, `.block()`, a synchronous HTTP client) on one of those threads, that thread cannot service any other in-flight request for the duration — a handful of blocked threads stalls *thousands* of connections, collapsing throughput far worse than the equivalent thread-per-request server. The correct pattern when you *must* call blocking code from a reactive pipeline is to push it onto `Schedulers.boundedElastic()` (a pool designed for blocking work) via `subscribeOn`/`publishOn`, isolating it from the event loop. Reactor's `BlockHound` agent can detect accidental blocking calls on non-blocking threads during testing — a tool worth mentioning. The deeper point connecting to earlier answers (Q25): this fragility, plus unreadable stack traces, is exactly why virtual-thread-backed MVC is now the pragmatic default for blocking I/O, reserving reactive for genuinely non-blocking, streaming, backpressure-sensitive pipelines.

#### Q62. [Practical] How does the Spring test framework cache application contexts, and why can a single careless annotation make your whole test suite slow?

Booting a Spring `ApplicationContext` is expensive, so `SpringExtension`/`TestContext` framework **caches contexts and reuses them across test classes** keyed by their *configuration*. The cache key is a composite of everything that defines the context: the config classes/locations, active profiles, the `webEnvironment`, property sources (`@TestPropertySource`, `properties`), `ContextCustomizer`s, and notably the set of mocked beans. If two test classes resolve to the *same* key, they share one cached context (no second startup); if any element differs, a *new* context is created and cached separately. The default cache holds up to 32 contexts (LRU eviction beyond that).

```java
// These two SHARE a context (identical configuration)
@SpringBootTest class ATest { ... }
@SpringBootTest class BTest { ... }

// This one gets its OWN context — different key — and pays a fresh startup
@SpringBootTest(properties = "feature.x=true") class CTest { ... }

// @MockitoBean changes the bean set → distinct key → another context
@SpringBootTest class DTest { @MockitoBean PaymentGateway gw; }
```

The "one careless annotation slows everything" failure mode is real and frequently seen: adding `@MockitoBean`, `@TestPropertySource`, `@DirtiesContext`, or a unique inline `properties` to many test classes *fragments the cache* so each class spins up its own context, and a suite that should reuse 2–3 contexts ends up creating dozens — multiplying total runtime by the per-context startup cost. `@DirtiesContext` is the worst offender because it *evicts* the context after the class, forcing the next class to rebuild it. The discipline: standardize on a small number of shared test configurations so contexts are reused; avoid per-class property overrides (use a shared base test config or profile); reserve `@DirtiesContext` for the rare case where a test genuinely mutates shared singleton state; and prefer slices (Q20) which build smaller contexts. Diagnosing it is easy once you know to look — enable context-cache statistics logging (`logging.level.org.springframework.test.context.cache=DEBUG`) and you will see exactly how many contexts were created and the hit/miss counts.

#### Q63. [Theory] What are `HttpMessageConverter`s, how does the converter chain resolve a body, and how do declarative HTTP Interface clients (`@HttpExchange`) fit in?

`HttpMessageConverter` is the SPI that bridges Java objects and HTTP message bodies in *both* directions: `canRead`/`read` deserialize a request body into a parameter type, and `canWrite`/`write` serialize a return value into a response body, each declaring the media types it supports. Spring registers an ordered list (`MappingJackson2HttpMessageConverter` for JSON, `Jaxb2`/`MappingJackson2Xml` for XML, `StringHttpMessageConverter`, `ByteArrayHttpMessageConverter`, `ResourceHttpMessageConverter`, form converters, etc.). On the way *in*, the request's `Content-Type` selects the converter that `canRead` the target type; on the way *out*, content negotiation (Q59) plus the converters' `canWrite` capabilities select the writer. The same converter list is shared by `RestClient`/`RestTemplate`/`WebClient`, which is why custom serialization behaves consistently across server and client.

```java
@HttpExchange(url = "/customers", accept = "application/json")
interface CustomerApi {
    @GetExchange("/{id}")
    Customer byId(@PathVariable long id);
    @PostExchange
    Customer create(@RequestBody NewCustomer body);
}

@Bean
CustomerApi customerApi(RestClient.Builder builder) {
    RestClient client = builder.baseUrl("https://customers").build();
    return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(client))
            .build().createClient(CustomerApi.class);   // dynamic proxy implements the interface
}
```

The **HTTP Interface** (`@HttpExchange`, Spring 6) is a declarative client analogous to Spring Data repositories or OpenFeign: you define an interface with `@GetExchange`/`@PostExchange` methods, and `HttpServiceProxyFactory` generates a dynamic proxy that turns each call into an HTTP request, *reusing the same `HttpMessageConverter` infrastructure* to (de)serialize bodies. The elegance is that it is *backend-agnostic* — back it with a `RestClientAdapter` (synchronous, blocking, virtual-thread-friendly) or a `WebClientAdapter` (reactive) without changing the interface. This subsumes much of what people used OpenFeign for, native to Spring. The expert framing: message converters are the unifying abstraction beneath controllers, the three HTTP clients, and HTTP interfaces — understanding them explains why customizing one `ObjectMapper`/converter propagates everywhere, and why the choice of converters on the classpath dictates which media types your endpoints and clients can speak.

#### Q64. [Theory] How does Spring's `Environment` abstraction layer `PropertySource`s, and how do `@Profile`, property precedence, and origin tracking actually work together?

The `Environment` is Spring's unified facade over two concerns: *profiles* (which bean definitions are active) and *properties* (key-value configuration). Under the hood it holds an ordered `MutablePropertySources` list — a chain of `PropertySource` objects (command-line args, `SPRING_APPLICATION_JSON`, system properties, OS env vars, `application.yml`, profile-specific files, defaults). Resolution is **first-wins by order**: `getProperty("x")` walks the list top-to-bottom and returns the first source that contains the key, which is exactly *why* precedence works the way it does (Q3) — higher-priority sources are simply earlier in the list. Spring Boot constructs this ordering deterministically at startup and lets you observe/modify it via `EnvironmentPostProcessor` (Q27) before the context exists.

```
Environment.getProperty("server.port")
   │  walk PropertySources in order (first match wins)
   ▼
[ commandLineArgs ] → [ systemProperties ] → [ systemEnvironment ]
   → [ application-prod.yml ] → [ application.yml ] → [ defaults ]
                 ▲ first source containing the key returns its value
```

Profiles tie in at two points. The *active profiles* (`spring.profiles.active`, itself a property resolved through this chain) determine which `@Profile`-annotated beans are registered and which profile-specific property sources are loaded and *inserted into the ordering* (profile files take precedence over the plain file). So profiles do not bypass the property chain — they *add and prioritize* sources within it. The often-overlooked feature is **origin tracking** (`OriginTrackedValue`): Boot records *where* each property came from (file, line, env var), which powers the `/actuator/env` endpoint showing the resolved value *and* its source, and produces error messages like "property X bound from application-prod.yml line 12" — invaluable when debugging which of five overlapping sources actually supplied a value. The senior takeaways: never reason about a property's value without knowing the *source ordering*; use `/actuator/env` (secured — it leaks secrets, Q15) or `--debug` to see origins; and remember that relaxed binding (Q36) means the *same* logical key can arrive from differently-formatted entries across these sources, all merged through this one abstraction.

## 🧩 Extended Questions — Set 2: Practical, Operational & Troubleshooting

### 🟢 Basic — extended

#### Q65. [Practical] Your Spring Boot app starts locally but a teammate says "it picks up the wrong database in prod." How do you diagnose which property value won and where it came from?

This is the single most common config-confusion incident, and the fix is to stop guessing and read the *origin*. Spring Boot tracks where every property came from (file, line, env var, command-line arg) via `OriginTrackedValue`, and exposes it through Actuator. The first move is to hit `/actuator/env` (secured — it leaks secrets) and look at the `spring.datasource.url` entry: Actuator shows the *resolved* value plus the full list of sources that contributed it, in precedence order, so you can see that, say, an `SPRING_DATASOURCE_URL` environment variable injected by the deployment overrode the value in `application-prod.yml`.

```bash
# See the resolved value AND its origin
curl -s localhost:8080/actuator/env/spring.datasource.url | jq
# {
#   "property": { "source": "systemEnvironment", "value": "jdbc:postgresql://wrong-host/db" },
#   "propertySources": [ { "name": "systemEnvironment", "property": {...} },
#                        { "name": "applicationConfig: [classpath:/application-prod.yml]", "property": {...} } ]
# }
```

If you can't reach Actuator, run the app with `--debug` (or set `debug=true`) and the startup logs include enough to reason about active profiles and the loaded config files; you can also log the active profiles on `ApplicationReadyEvent`. The mental model to carry (and to recite in the interview) is the precedence order from Q3: command-line args > `SPRING_APPLICATION_JSON` > OS environment variables > Java system properties > profile-specific files > the base `application.yml`. The classic prod-only surprise is an environment variable — relaxed binding means `SPRING_DATASOURCE_URL` (env) binds to `spring.datasource.url` and *silently wins* over the YAML you were staring at. The durable lesson: in production, environment/secret-manager values almost always override file config, so the file is rarely the source of truth — always check `/actuator/env` or the origin metadata before changing YAML that "isn't taking effect."

#### Q66. [Practical] How do you change a log level in production without redeploying, and how would you ship structured (JSON) logs?

Spring Boot exposes the `loggers` Actuator endpoint, which lets you read and *mutate* log levels at runtime via HTTP — no restart, no redeploy. This is the correct first response when you need more detail to diagnose a live incident and the opposite move (turning a chatty logger down) when log volume is melting your aggregator.

```bash
# Turn on DEBUG for one package while an incident is live, then revert
curl -X POST localhost:8080/actuator/loggers/com.acme.payments \
     -H 'Content-Type: application/json' -d '{"configuredLevel":"DEBUG"}'
# revert: -d '{"configuredLevel":null}'  (falls back to the inherited/default level)
```

For structured logging, the modern path in Spring Boot 3.4+ is built-in: set `logging.structured.format.console=ecs` (or `logstash`/`gelf`) and Boot emits JSON without you adding a custom Logback encoder. On older versions you add `logstash-logback-encoder` and a `logback-spring.xml` with a `LoggingEventCompositeJsonEncoder`. Either way, the operationally critical detail is **trace correlation**: include `traceId`/`spanId` in every line (Boot's Micrometer Tracing puts them in the MDC automatically, and the default log pattern already prints them), so a log line in Loki/Elastic links directly to the span in Tempo/Jaeger.

The two production gotchas worth stating: (1) runtime log-level changes via `/actuator/loggers` are *not persisted* — a pod restart reverts them, which is usually what you want but bites you if you forget. (2) Logging is synchronous by default; under heavy DEBUG logging the appender can become a latency bottleneck, so for high-throughput services use an `AsyncAppender` (with a bounded queue and an explicit discard policy) so logging never blocks request threads, and always log to stdout in containers and let the platform handle aggregation rather than writing files inside the container.

#### Q67. [Practical] How do you configure HTTP client timeouts in Spring Boot, and why is a missing timeout the most dangerous default in a microservice?

A client with *no* timeout will wait forever for a hung downstream, and because the calling thread is held for the entire wait, a single slow dependency cascades into thread-pool exhaustion and then a full outage of *your* service — the classic distributed-systems failure mode. Every outbound call needs both a *connect* timeout (how long to establish the TCP/TLS connection) and a *read/response* timeout (how long to wait for the response once connected). The defaults in the underlying HTTP libraries are often "infinite," so you must set them explicitly.

```java
@Bean
RestClient pricingClient(RestClient.Builder builder) {
    var factory = new SimpleClientHttpRequestFactory();   // or JdkClientHttpRequestFactory / Apache
    factory.setConnectTimeout(Duration.ofSeconds(2));
    factory.setReadTimeout(Duration.ofSeconds(3));
    return builder.baseUrl("https://pricing").requestFactory(factory).build();
}
// Or, since Boot 3.4, the declarative ClientHttpRequestFactorySettings + properties:
//   spring.http.client.connect-timeout=2s
//   spring.http.client.read-timeout=3s
```

Beyond the per-call timeout, layer on a `TimeLimiter` (Resilience4j, Q29) for an end-to-end budget that also covers retries, and bound your *connection pool* (Apache/JDK clients) so you don't open unbounded sockets. The expert framing: timeouts must be *shorter than the caller's timeout* up the chain — if your API has a 5s SLA but you call a downstream with a 10s read timeout, you can never honour your own SLA and you'll pile up requests. In practice I set timeouts as a deliberate budget from the edge inward (e.g. edge 3s → service 2s → downstream 1s), add a circuit breaker so an already-failing dependency fails fast instead of consuming the timeout every time, and alert on the client's `http.client.requests` timer p99 so a creeping downstream latency is caught before it exhausts threads.

### 🟡 Intermediate — extended

#### Q68. [Practical] Your container keeps getting OOMKilled even though the JVM heap looks fine in metrics. How do you diagnose and fix container memory issues?

OOMKilled with a healthy *heap* almost always means the problem is **off-heap** or that the JVM is sizing itself against the wrong memory limit. The first check is whether the JVM even knows its container limit: on modern JDKs (11+) the JVM is container-aware and reads the cgroup limit, but only if you let it size the heap as a *percentage* rather than hardcoding `-Xmx`. If someone set `-Xmx3g` in a 2Gi container, the JVM happily grows heap toward 3g and the kernel kills it. Use `-XX:MaxRAMPercentage=75` so heap scales with the actual limit.

```bash
# Confirm what the JVM thinks its limits are
jcmd <pid> VM.flags | grep -i ram        # MaxRAMPercentage / MaxHeapSize
# Inspect total native footprint, not just heap
jcmd <pid> VM.native_memory summary       # requires -XX:NativeMemoryTracking=summary
# Kubernetes: was it really OOMKilled?
kubectl describe pod <pod> | grep -A3 'Last State'   # Reason: OOMKilled
```

The memory the kernel counts is RSS, which is heap **plus** metaspace, thread stacks (each thread ≈ 512KB–1MB — a runaway thread count is a frequent culprit), JIT code cache, GC structures, direct `ByteBuffer`s (Netty, NIO), and native libraries. So a leak in *non-heap* memory (e.g. unbounded thread creation from a misconfigured `SimpleAsyncTaskExecutor`, or direct buffers from a reactive client) never shows in heap dashboards yet drives RSS past the limit. Native Memory Tracking (NMT) plus comparing `jcmd VM.native_memory` snapshots over time pinpoints which category is growing. The fix depends on the category: cap thread pools (bounded `ThreadPoolTaskExecutor`), set `-XX:MaxDirectMemorySize`, bound metaspace if a classloader leak is in play, and *always* set `-XX:MaxRAMPercentage` so heap leaves headroom (~25%) for the rest. The lesson I repeat: in containers, "the heap is fine" is not "memory is fine" — you must budget total RSS against the cgroup limit, and the JVM's default ergonomics can betray you if the limit isn't communicated.

#### Q69. [Practical] A heap dump shows memory steadily climbing toward OutOfMemoryError. Walk through how you'd capture and analyze it in a Spring Boot service.

The workflow is: capture on or before the error, then analyze the dominator tree. To capture *automatically* at the moment of failure, set `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/dumps` so the JVM writes a `.hprof` exactly when it dies — critical because by the time a human reacts, the process is usually already restarted. To capture a *live* dump without crashing, use Actuator's `heapdump` endpoint (secured!) or `jcmd`/`jmap`.

```bash
curl -o heap.hprof localhost:8080/actuator/heapdump     # Actuator (protect this endpoint!)
jcmd <pid> GC.heap_dump /dumps/live.hprof               # live, no restart
# then open in Eclipse MAT and run "Leak Suspects" / look at the dominator tree
```

In Eclipse MAT (or VisualVM) the key views are the **dominator tree** (which objects retain the most memory) and the **leak suspects report**. Common Spring-specific findings: an unbounded in-memory cache (a `@Cacheable` with no TTL/size limit on a Caffeine map, Q39), a `static`/singleton-held collection that only ever grows (e.g. an event listener appending to a list), a classloader leak after repeated devtools restarts, `ThreadLocal`s never cleared in a pooled-thread environment, or huge result sets from an unbounded `findAll()` (Q13). You confirm a true leak versus normal churn by taking *two* dumps minutes apart and diffing object counts — a leak shows a class with monotonically rising instance count and retained size.

The operational discipline around this: pre-provision a writable `/dumps` volume sized larger than the heap (a dump of a 4Gi heap is ~4Gi), because in Kubernetes the ephemeral container filesystem may be too small and the dump itself can fail. Set a `preStop`/sidecar to ship the dump off the pod before it's reclaimed. And treat the *first* OOM as a learning artifact, not just an incident — the `.hprof` is the ground truth that tells you exactly which retained object graph blew the budget, which beats any amount of speculation from dashboards.

#### Q70. [Practical] How do you manage database schema migrations in Spring Boot, and what is the right way to run Flyway/Liquibase in production with multiple instances?

The non-negotiable starting point is to **turn off `hibernate.ddl-auto` in production** (`spring.jpa.hibernate.ddl-auto=validate` or `none`). Letting Hibernate auto-generate or update schema is fine for a throwaway dev DB but catastrophic in prod — it can drop columns, lock tables, or silently diverge across environments. Schema changes belong in versioned migration scripts managed by **Flyway** or **Liquibase**, both of which Spring Boot auto-configures: drop the dependency in, put scripts under `db/migration` (Flyway) or a changelog (Liquibase), and Boot runs pending migrations on startup before the rest of the context initializes.

```
src/main/resources/db/migration/
  V1__create_orders.sql
  V2__add_index_on_customer_id.sql
  V3__add_status_column.sql        # Flyway tracks applied versions in flyway_schema_history
```

The multi-instance problem: if you deploy 5 replicas simultaneously, all 5 try to migrate at once. Both tools handle this with a **database lock** — Flyway takes a lock on its history table; Liquibase uses a `DATABASECHANGELOGLOCK` table — so only one instance migrates and the others wait, then see the migrations already applied. This works, but the safer pattern at scale is to run migrations as a **separate step in the deploy pipeline** (a Kubernetes Job / init container, or `flyway migrate` in CI) *before* the new app version rolls out, so the application pods only ever `validate`. That decouples a slow or failing migration from the app's startup/readiness and avoids a half-migrated fleet.

The deepest practical concern is **backward compatibility during rolling deploys**: while old and new versions run simultaneously, the schema must satisfy both. So you never do a destructive change in one step — to rename a column you (1) add the new column, (2) deploy code writing to both, (3) backfill, (4) deploy code reading the new column, (5) drop the old column in a later release. This "expand/contract" (parallel-change) discipline is what makes zero-downtime deploys actually zero-downtime, and it's exactly the kind of operational maturity interviewers probe — auto-ddl can't give you any of it.

#### Q71. [Practical] Requests to your service are intermittently hanging. How do you take and read a thread dump to find the stuck threads?

A thread dump is the definitive tool for "stuck/hanging" symptoms because it shows *exactly what every thread is doing right now*. Capture it with `jstack <pid>`, `jcmd <pid> Thread.print`, or — conveniently for a Spring Boot app — the Actuator `/actuator/threaddump` endpoint. Take *two or three* dumps a few seconds apart: a thread stuck at the same stack frame across all dumps is genuinely blocked, whereas a thread at different frames is just busy.

```bash
curl -s localhost:8080/actuator/threaddump > dump1.txt   # Actuator (JSON)
jstack <pid> > dump2.txt                                  # or text form
# What to look for:
#   "http-nio-8080-exec-7"  BLOCKED  -> waiting on a monitor (lock contention)
#   many request threads parked in DataSource.getConnection -> pool exhaustion (Q22)
#   threads in socketRead0 with no timeout -> a hung downstream HTTP call (Q67)
#   a deadlock section "Found one Java-level deadlock" -> two locks acquired in opposite order
```

The reading method: scan the request worker threads (Tomcat names them `http-nio-<port>-exec-N`). If many are `WAITING`/`TIMED_WAITING` inside `HikariPool.getConnection`, your symptom is connection-pool starvation — chase the connection holders (Q22). If they're in `socketRead0`/`SocketInputStream.read`, you have a downstream call with no read timeout (Q67). If `jstack` prints a "Found one Java-level deadlock" block, it names the two threads and the two locks they hold/want — that's a code-level lock-ordering bug. Counting how many of your ~200 worker threads are stuck tells you how close you are to total saturation (when all workers are blocked, the service stops accepting requests and health checks fail).

The forward-looking note for modern stacks: with **virtual threads** (Q25), a stuck thread dump looks different — you may see thousands of virtual threads, and the concern shifts to *pinning* (a virtual thread stuck on a `synchronized` block or native call, unable to unmount from its carrier). `jcmd Thread.dump_to_file -format=json` gives a virtual-thread-aware dump, and `-Djdk.tracePinnedThreads=full` surfaces pinning. Either way, the discipline is the same: capture multiple dumps, classify the worker-thread states, and correlate the dominant blocked stack with the resource it's contending for.

#### Q72. [Practical] How do you profile and reduce a slow Spring Boot startup time?

First *measure*, don't guess. Spring Boot has a built-in startup tracker: register a `BufferingApplicationStartup` and read `/actuator/startup`, which gives you a timed tree of every startup step (bean instantiation, auto-config evaluation, `init` methods). That immediately tells you whether the time is in component scanning, a specific slow `@PostConstruct`, connection-pool warmup, Flyway migrations, or auto-configuration.

```java
public static void main(String[] args) {
    var app = new SpringApplication(MyApp.class);
    app.setApplicationStartup(new BufferingApplicationStartup(2048)); // record steps
    app.run(args);
}
// GET /actuator/startup  -> JSON tree with durations per step
// Also: run with --debug to get the Condition Evaluation Report (which auto-configs matched)
```

Common culprits and fixes, in roughly the order I check them: (1) **classpath/component-scan surface** — scanning a huge package tree or pulling in unused starters evaluates hundreds of auto-configs; exclude the ones you don't use (`@SpringBootApplication(exclude = …)`) and keep the main class high in the package tree but not scanning third-party packages. (2) **Eager connection-pool / client warmup** — a `DataSource` opening its minimum-idle connections or a client doing DNS/TLS at startup; tune `minimum-idle` or defer. (3) **Slow `@PostConstruct`/`ApplicationRunner`** doing remote calls synchronously at boot. (4) **Lots of beans** — `spring.main.lazy-initialization=true` defers creation (great for dev restart loops, but it moves failures and latency to first request, so I avoid it in prod — Q58).

For genuinely fast startup as a production goal (serverless, rapid autoscaling), step up to the platform-level levers from Q56: **CDS/AppCDS** (Boot 3.3 training run) for a low-risk 20–40% cut while staying on a normal JVM, **CRaC** to restore a warmed snapshot in milliseconds, or **GraalVM native image** for ~50ms cold starts. The decision framework is the same one I'd state in the interview: profile first with `/actuator/startup`, fix the obvious wiring/scan/warmup issues (often the biggest wins and free), and only reach for native/CRaC when the workload's startup *frequency* (scale-to-zero, burst autoscaling) actually justifies the build/ops complexity.

#### Q73. [Practical] After deploying to Kubernetes, pods cycle through CrashLoopBackOff or get killed during traffic. How do liveness/readiness probes interact with Spring Boot, and what's the common misconfiguration?

Spring Boot exposes Kubernetes-aware probe endpoints when you enable them: `/actuator/health/liveness` and `/actuator/health/readiness` (enabled via `management.endpoint.health.probes.enabled=true`, and auto-enabled when Boot detects it's running in Kubernetes). The semantic distinction is everything: **liveness** answers "is the app broken beyond recovery — should k8s *restart* it?", while **readiness** answers "can the app *take traffic right now*?". Conflating them is the number-one probe incident.

```yaml
# Spring side
management.endpoint.health.probes.enabled: true
management.endpoint.health.show-details: when_authorized
```
```yaml
# Kubernetes side — note the SEPARATE endpoints and generous startup allowance
livenessProbe:   { httpGet: { path: /actuator/health/liveness,  port: 8080 }, periodSeconds: 10 }
readinessProbe:  { httpGet: { path: /actuator/health/readiness, port: 8080 }, periodSeconds: 5  }
startupProbe:    { httpGet: { path: /actuator/health/liveness,  port: 8080 }, failureThreshold: 30, periodSeconds: 5 }
```

The classic misconfiguration is pointing **liveness at a deep health check** that includes the database or a downstream service. When that dependency has a transient blip, liveness fails, Kubernetes *restarts a perfectly healthy app*, which doesn't fix a downstream problem and often makes it worse (a restart storm during a DB hiccup). The rule: liveness must check only *internal* liveness (the app process responding), and downstream dependency health belongs in **readiness** (pull the pod out of the load balancer until the dependency recovers, without killing it). Spring's liveness group is deliberately shallow by default for exactly this reason.

The second classic incident is a slow-starting app being killed before it finishes booting — the fix is a **`startupProbe`** with a generous `failureThreshold × periodSeconds` budget, so liveness doesn't start counting failures until startup completes. And tying back to graceful shutdown (Q17): on termination, Spring flips readiness to `OUT_OF_SERVICE` *before* the context closes, so the load balancer stops routing while in-flight requests drain — but only if your k8s `terminationGracePeriodSeconds` exceeds the app's shutdown grace period, otherwise you're SIGKILLed mid-drain. The whole probe + shutdown story has to be designed together for zero-downtime rollouts.

#### Q74. [Practical] How do you correctly size a connection pool (and thread pool) for a Spring Boot service under load?

Pool sizing is a math problem, not a vibe, and the governing relationship is **Little's Law**: the number of concurrent in-use resources ≈ arrival rate × average hold time. If you serve 500 req/s and each request holds a DB connection for 20ms, you need roughly 500 × 0.02 = 10 concurrent connections — not 100. The counterintuitive result that surprises people is that a *small* pool often outperforms a large one: the database itself has limited cores and disk, so beyond a point more connections just create contention and context-switching at the DB, *increasing* latency. HikariCP's own guidance is famously a small pool (the old formula `connections ≈ (cores × 2) + effective_spindle_count` is a starting heuristic).

```yaml
spring.datasource.hikari:
  maximum-pool-size: 10        # derived from Little's Law, not "bigger is safer"
  minimum-idle: 10             # = max for a steady-load service (avoid churn)
  connection-timeout: 3000     # fail fast (3s) instead of piling up callers
  max-lifetime: 1800000        # < DB/infra idle-connection timeout to avoid stale conns
  leak-detection-threshold: 5000
```

The interaction with the *web* thread pool matters too. Tomcat defaults to ~200 worker threads. If 200 threads can each demand a DB connection but the pool only has 10, the other 190 block on `getConnection` (you see this in thread dumps, Q71) and `connection-timeout` decides whether they fail fast or pile up. So the right approach is end-to-end: bound the work that can be *in flight* (web threads), size the DB pool to the actual concurrency the DB can serve well, and set `connection-timeout` so excess load sheds quickly rather than queueing into latency. With **virtual threads** (Q25) the web side scales to huge concurrency, which makes a too-small *downstream* pool the new bottleneck — virtual threads don't create connections out of thin air, so you must still size and protect the pool (a `Semaphore`/bulkhead) or you'll just move the queue.

The production discipline: derive the numbers from measured throughput and hold-time, load-test to validate (watch `hikaricp.connections.pending` and the `acquire` timer), and alert on sustained `pending > 0` as the leading indicator of saturation (Q22). "We bumped the pool to 100 and it got slower" is a real and common story — the cure for pool exhaustion is usually *shorter hold times* (move remote calls out of transactions, fix N+1) and *right-sizing*, not a bigger pool.

#### Q75. [Practical] How do you keep dependencies patched and respond when a CVE like Spring4Shell / Log4Shell drops? Show the tooling.

The foundation is that Spring Boot's `spring-boot-dependencies` BOM pins a coherent, tested set of transitive versions, so the *first* response to most CVEs is simply to bump the Boot version (or the specific managed dependency's version property), and the BOM cascades a compatible set. That alone resolves a large fraction of advisories because the fixed library is already in a newer Boot patch release. For libraries the BOM lets you override, you set the version property (e.g. `<jackson.version>` / `jacksonVersion`) rather than declaring a raw dependency, so you don't fight the BOM.

```bash
# What do I actually depend on (incl. transitives)?
mvn dependency:tree -Dincludes=org.apache.logging.log4j   # or: ./gradlew dependencies
# Scan for known CVEs in CI
mvn org.owasp:dependency-check-maven:check                 # OWASP Dependency-Check
# Bump everything the BOM allows, then test
mvn versions:display-dependency-updates
```

Continuous hygiene means automation, not heroics: enable **Dependabot** / **Renovate** to open PRs as new versions land, run **OWASP Dependency-Check** (or Snyk/Trivy/`gh` advisories) in CI to *fail the build* on known-vulnerable transitives, and scan the *container image* too (Trivy/Grype) because base-image OS packages have CVEs your dependency tree doesn't show. The reason this matters viscerally: Log4Shell (CVE-2021-44228) and Spring4Shell (CVE-2022-22965) were both remotely exploitable in default configurations, so the window between disclosure and exploitation was hours — teams that already had dependency scanning and a one-command Boot bump patched in an afternoon; teams that didn't even know their transitive `log4j` version spent days just doing inventory.

The incident-response playbook I'd describe: (1) **inventory** — use `dependency:tree`/SBOM to find every place the vulnerable artifact appears, including transitives you didn't declare; (2) **assess** — is the vulnerable code path actually reachable/exploitable in your config (e.g. Spring4Shell required specific deployment shapes)? (3) **mitigate fast** — apply the documented stopgap if a patched version isn't yet available (for Log4Shell, removing the `JndiLookup` class or a flag) while you prepare the real fix; (4) **patch** — bump the Boot/library version, run the full test + integration suite (Testcontainers, Q19) as the gate, and roll out via canary; (5) **verify** — confirm the fixed version is actually deployed (not masked by a cached layer) and re-scan. The meta-point: CVE response is an *operational readiness* question — the teams that fare well invested in SBOMs, scanning, and a fast, well-tested upgrade path *before* the incident.

#### Q76. [Practical] CPU sits at 100% on one pod with no obvious traffic spike. How do you find the hot code path in production?

The goal is to map CPU burn to a *Java stack frame* with minimal overhead, and the lightweight, production-safe tool of choice is **async-profiler** (or JFR), which sample-profiles without the heavy instrumentation overhead of older profilers. The classic one-liner correlates a hot *OS* thread to a Java thread and then to the code.

```bash
# 1) Which OS thread is burning CPU?
top -H -p <pid>                       # note the high-CPU TID (decimal)
printf '%x\n' <TID>                   # convert to hex (the "nid" in jstack)
jstack <pid> | grep -A20 'nid=0x<hex>'  # the Java stack of that exact thread

# 2) Better: sample the whole JVM and produce a flame graph
asprof -d 30 -e cpu -f /tmp/cpu.html <pid>   # async-profiler, 30s CPU flame graph
# or capture a JFR recording:
jcmd <pid> JFR.start duration=60s filename=/tmp/rec.jfr settings=profile
```

A CPU flame graph makes the answer obvious: the widest frame is where the cycles go. In Spring services the usual suspects are a regex catastrophe (catastrophic backtracking on user input), an accidental tight loop or busy-wait, JSON (de)serialization of huge payloads, an N+1 turned into in-memory processing of an unbounded result set (Q13/Q14), or — frequently overlooked — **GC pressure** masquerading as CPU. So in parallel I check GC: `jstat -gcutil <pid> 1000` or the `jvm.gc.pause` Micrometer metrics; if the CPU is in GC threads with frequent full GCs, the real problem is allocation rate or a heap leak (Q69), not application code.

The production discipline: async-profiler's sampling overhead is low enough to run on a live pod for 30–60s, which is the key advantage over "reproduce it locally" (you often can't — it's data- or load-dependent). Capture the flame graph *while the symptom is happening*, because once the pod restarts the evidence is gone. And feed it back into prevention: if it was a regex, add input bounds and a timeout; if it was serialization, paginate or stream; if it was GC, fix the allocation hotspot or right-size the heap. The interview signal here is that you reach for sampling profilers and flame graphs on the live process rather than adding `System.currentTimeMillis()` logging and redeploying.

#### Q77. [Practical] How do you secure the Actuator endpoints in production, and what specifically goes wrong if you don't?

Actuator's power is also its danger: endpoints like `/env`, `/heapdump`, `/threaddump`, `/configprops`, `/loggers`, and `/shutdown` can leak credentials, dump the entire process memory (including secrets), reveal internal topology, or *mutate* runtime state. The hardening is layered. First, **expose only what you need** — by default only `health` is exposed over HTTP; opt the rest in explicitly and never blanket-expose with `*` in production.

```yaml
management:
  server.port: 9090                              # bind on a separate port, not publicly routed
  endpoints.web.exposure.include: health,info,prometheus,metrics
  endpoint.health.show-details: when_authorized  # don't leak component health to anonymous callers
  endpoint.env.show-values: when-authorized      # don't render raw property values
```
```java
@Bean
SecurityFilterChain actuator(HttpSecurity http) throws Exception {
    return http.securityMatcher(EndpointRequest.toAnyEndpoint())
        .authorizeHttpRequests(a -> a
            .requestMatchers(EndpointRequest.to("health","info")).permitAll()
            .anyRequest().hasRole("ACTUATOR"))      // auth for everything else
        .httpBasic(Customizer.withDefaults()).build();
}
```

The two most important controls beyond exposure are **a separate management port** (`management.server.port`) that your ingress/load balancer does *not* route from the public internet — so even a misconfigured exposure isn't reachable externally — and **authentication** via Spring Security's `EndpointRequest` matchers so any sensitive endpoint requires a role. Concrete failure modes if you skip this: a public `/actuator/env` hands an attacker your datasource password and API keys (Boot masks some keys, but custom secrets often slip through, which is why `show-values: never/when-authorized` matters); a public `/actuator/heapdump` lets anyone download a full memory image to mine for tokens in flight; a public `/actuator/loggers` lets an attacker flip logging to DEBUG and exfiltrate request payloads or DoS your log pipeline; and `/actuator/shutdown` (disabled by default — keep it that way) is an unauthenticated kill switch.

The principle to articulate: Actuator should be treated as a privileged admin interface, not part of the public API. Defense in depth — separate port + network policy + authentication + minimal exposure + value masking — so no single misconfiguration is fatal. I've seen real breaches start from a forgotten `endpoints.web.exposure.include=*` left over from a debugging session shipped to prod; the fix is to make the secure baseline the *default* in your shared platform starter (Q24) so app teams can't accidentally over-expose.

#### Q78. [Practical] How do you implement runtime feature flags in Spring Boot, and why is using Spring profiles for feature toggles an anti-pattern?

The clean separation is: **profiles describe the *shape of the environment*** (dev/test/prod — which beans, which infrastructure), while **feature flags describe *runtime behaviour you want to toggle independently of deployment*** (roll a feature to 10% of users, kill-switch a risky path during an incident). Using a profile as a feature flag is an anti-pattern because profiles are *baked at startup* — flipping one requires a redeploy/restart, you can't target a subset of users or do gradual rollout, and combinatorial profiles (`prod,feature-a,feature-b`) explode into an unmanageable matrix. Profiles also drive bean *existence*, so a "feature off" profile may not even instantiate the code path you want to A/B test.

```java
// Lightweight in-app flag (refreshable, evaluated per request)
@Component
class Features {
    private final Map<String,Boolean> flags = new ConcurrentHashMap<>();
    boolean on(String name) { return flags.getOrDefault(name, false); }
    void set(String name, boolean v) { flags.put(name, v); }   // expose via a secured Actuator endpoint
}

@Service
class CheckoutService {
    private final Features features;
    void checkout(Cart cart) {
        if (features.on("new-pricing-engine")) newPricing(cart);
        else legacyPricing(cart);                              // toggle at runtime, no redeploy
    }
}
```

For anything beyond a toy, use a real feature-flag system — **Togglz**, **FF4j**, or a managed service like LaunchDarkly/Unleash — which gives you per-user/percentage targeting, an audit trail, a UI, and centralized control across the fleet (an in-memory map per pod has no consistency across replicas, the same problem as a local cache in Q39). These integrate cleanly as a Spring bean you query at decision points. The operational payoff is real: flags decouple *deploy* from *release* (ship dark, enable later), enable instant kill-switches during incidents (turn off the misbehaving feature in seconds instead of a 20-minute rollback), and support canary/gradual rollout and A/B experiments — none of which profiles can do.

The nuance to state: there's a place for both. You still use a profile to choose, say, a stub payment gateway in `test` versus the real one in `prod` (environment shape). But "should feature X be live for these users right now" is a runtime decision that must live outside the deployment unit. Mixing the two — and especially reaching for `@Profile` when you mean "feature toggle" — is the smell interviewers are listening for.

### 🟠 Advanced — extended

#### Q79. [Practical] A production incident: p99 latency spiked 10x but throughput and error rate look normal. Walk me through how you'd diagnose it.

This signature — p99 blows up while p50, throughput, and error rate stay normal — points to *intermittent* contention or a *long-tail* resource, not a broad failure (which would move error rate and p50). My diagnosis follows the latency layer by layer using the observability stack (Q23). First I pull a **trace** for a slow request from Tempo/Jaeger: a distributed trace decomposes the request into spans, so I can immediately see *which segment* ate the time — a downstream call, a DB query, time spent *waiting to be picked up* by a worker thread, or GC pause. That single trace usually narrows it from "the service is slow" to "the customer-lookup span is 3s on slow requests."

```
Diagnostic ladder for a p99-only spike:
  1. Trace a slow request   -> which span? (downstream / DB / queue wait / serialization)
  2. GC?                     -> jvm.gc.pause p99, jstat -gcutil; full-GC pauses = tail latency
  3. Pool contention?        -> hikaricp.connections.pending/acquire, thread-pool queue depth
  4. Downstream tail?        -> http.client.requests p99 per dependency
  5. Lock contention?        -> 2-3 thread dumps, look for BLOCKED on a shared monitor
  6. Noisy neighbor / GC on host, or a specific slow query (pg_stat_statements)
```

The usual root causes for a *tail-only* spike, in order I check them: (1) **GC pauses** — periodic stop-the-world pauses hit only the unlucky requests in the pause window, producing exactly a p99 spike with normal median; `jvm.gc.pause` and `jstat` confirm it, and the fix is reducing allocation rate, right-sizing heap, or switching to a low-pause collector (ZGC/Shenandoah). (2) **Connection-pool or thread-pool queueing** — most requests get a resource instantly, but under bursts a few queue behind `getConnection` (Q22/Q74), adding wait only to the tail. (3) **A downstream's *own* tail** propagating to yours — `http.client.requests` p99 per dependency reveals it; the fix is a tighter timeout + circuit breaker (Q67/Q29). (4) **Lock contention** — a `synchronized` block or hot lock serializes a fraction of requests; thread dumps (Q71) show threads BLOCKED on the same monitor. (5) **A specific slow query plan** that only fires for some inputs (a missing index hit only by certain parameter values) — `pg_stat_statements` / slow-query log.

The method I'd emphasize in the interview: *trace first* to localize, then *drill with the metric that matches the suspected layer*. The trap is staring at aggregate dashboards (which look fine on average) instead of sampling the slow requests themselves. And I'd close with prevention: SLO-based alerting on p99 (not just averages), per-dependency client metrics, and load tests that specifically watch the tail — because a 10x p99 with healthy averages is invisible to anyone monitoring only means.

#### Q80. [Practical] How do you propagate context (trace IDs, security principal, MDC) across `@Async`, thread pools, and reactive boundaries — and what breaks if you don't?

The core problem is that a lot of Spring's per-request context lives in `ThreadLocal`s — the SLF4J `MDC` (which holds `traceId`/`spanId` for log correlation), the Spring Security `SecurityContextHolder`, and request-scoped beans. When work hops to *another thread* (an `@Async` method, a `ThreadPoolTaskExecutor`, a `CompletableFuture` on a different pool, or a reactive scheduler), those ThreadLocals are *not* on the new thread, so logs lose their trace ID (breaking correlation), `@PreAuthorize`/`SecurityContext` reads come back empty (authorization bugs, sometimes security holes), and request-scoped lookups fail.

```java
// Copy ThreadLocal context onto pool threads with a TaskDecorator
@Bean
ThreadPoolTaskExecutor appExecutor() {
    var ex = new ThreadPoolTaskExecutor();
    ex.setTaskDecorator(runnable -> {
        Map<String,String> mdc = MDC.getCopyOfContextMap();          // capture on caller thread
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return () -> {
            if (mdc != null) MDC.setContextMap(mdc);
            SecurityContextHolder.getContext().setAuthentication(auth);
            try { runnable.run(); } finally { MDC.clear(); SecurityContextHolder.clearContext(); }
        };
    });
    return ex;
}
```

Spring/Micrometer give you better-than-hand-rolled options. **Micrometer Context Propagation** (`io.micrometer:context-propagation`) defines `ThreadLocalAccessor`s for MDC, observation, and security, and integrates so that when you wrap an executor with `ContextSnapshotFactory`/`ContextExecutorService` the registered ThreadLocals are captured and restored automatically. For Security specifically there's `DelegatingSecurityContextAsyncTaskExecutor`/`...ExecutorService` that does the security-context copy for you. In **Reactor/WebFlux**, ThreadLocals are the wrong model entirely — context rides in the `Context`/`ContextView` of the reactive chain, and `Hooks.enableAutomaticContextPropagation()` (Reactor 3.5+) bridges the Micrometer ThreadLocalAccessors so MDC/trace/security flow across `publishOn`/`subscribeOn` hops.

The "what breaks" list is concrete and worth stating: logs from async work show *no* trace ID so you can't follow a request across the boundary (the single most common observability gap); `@Async` methods that do `@PreAuthorize` or read the current user get `null` and either throw or — worse — silently act as an anonymous/elevated principal; and the **cleanup** half is just as important — in a *pooled* thread you must `clear()` the MDC/SecurityContext in a `finally`, or the next task reusing that thread inherits a *stale* trace ID or, dangerously, a *previous user's* security context (a real cross-tenant leak). So context propagation is both a correctness/observability requirement and a security one, and the modern answer is to lean on Micrometer's context-propagation rather than copying ThreadLocals by hand everywhere.

#### Q81. [Practical] How would you implement rate limiting / throttling in a Spring Boot service, and where in the stack should it live?

Rate limiting protects a service from being overwhelmed (and enforces fairness/quotas), and the *layer* you put it in is a deliberate trade-off. The cheapest and most robust place is the **edge** — an API gateway, ingress, or service mesh — because it sheds excess load before it ever touches your JVM, threads, or DB. Inside the application you add limiting when the limit is *business-aware* (per-API-key tier, per-user quota, per-tenant) in a way the edge can't easily express. For a single instance, a token-bucket in-process is fine; for a *fleet*, the limiter must share state, which means a distributed store (Redis).

```java
// Resilience4j RateLimiter — declarative, per-instance limit
@RateLimiter(name = "search", fallbackMethod = "tooMany")
public List<Hit> search(String q) { ... }

private List<Hit> tooMany(String q, RequestNotPermitted ex) {
    throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded");
}
```
```yaml
resilience4j.ratelimiter.instances.search:
  limit-for-period: 100        # 100 calls
  limit-refresh-period: 1s     # per second
  timeout-duration: 0          # don't queue — reject immediately (429)
```

The critical distributed nuance: a per-instance limiter of 100/s across 10 replicas is effectively a *1000/s* global limit, and that drifts as you autoscale — so for a true global limit you need a **shared counter in Redis** (e.g. Bucket4j with a Redis/Hazelcast backend, or Spring Cloud Gateway's `RequestRateLimiter` with the Redis Lua token-bucket). The atomic Redis script avoids race conditions between instances. Always return **HTTP 429** with a `Retry-After` header (and ideally `RateLimit-*` headers) so well-behaved clients back off rather than hammering — pairing this with client-side retry-with-jitter prevents the rejected requests from becoming a thundering herd.

The design judgment to articulate: prefer the edge for crude volumetric protection (it's cheaper and protects the app from even *reaching* saturation), use in-app Resilience4j/Bucket4j for business-tier quotas, back fleet-wide limits with Redis for correctness, and combine rate limiting with the *bulkhead* and *circuit breaker* patterns (Q29) — rate limiting controls *inbound* pressure, bulkheads isolate *resource pools*, and circuit breakers protect *outbound* dependencies; together they're the load-management toolkit for a resilient service.

#### Q82. [Practical] A scheduled job runs fine for weeks then silently stops firing across the whole fleet. What are the likely causes and how do you make scheduling robust?

"Silently stops" for a `@Scheduled` job has a small set of usual culprits, and the first is the most common and most surprising: the **single-threaded default scheduler**. `@EnableScheduling` uses a one-thread `ThreadPoolTaskScheduler` by default, so if *any* scheduled method blocks indefinitely — a downstream call with no timeout (Q67), a hung DB connection — it monopolizes the only scheduler thread and *every other scheduled task stops firing*. From the outside the job "just stopped," but a thread dump (Q71) shows the lone scheduler thread parked in `socketRead0`. The fix is a multi-threaded `ThreadPoolTaskScheduler` *and* hard timeouts on everything the job calls.

```java
@Bean
ThreadPoolTaskScheduler taskScheduler() {
    var s = new ThreadPoolTaskScheduler();
    s.setPoolSize(4);                       // one blocked task can't starve the others
    s.setThreadNamePrefix("sched-");
    s.setWaitForTasksToCompleteOnShutdown(true);
    return s;
}
```

Other causes: (2) an **uncaught exception** in a `fixedRate`/`fixedDelay` task — Spring logs it but, depending on the path, a thrown exception can stop *that* task's future from rescheduling, so the job silently dies while the app stays up; always wrap the body in try/catch and emit a metric on failure. (3) With a **distributed lock** (ShedLock, Q54) misconfigured — e.g. a lock that's acquired but never released (a crash between acquire and release with a too-long `lockAtMostFor`) — no instance can re-acquire, so the job stops fleet-wide until the lease expires; set a sane `lockAtMostFor` as a safety valve. (4) Clock/timezone drift or a cron expression that's correct but only fires at a time you stopped observing.

Making it robust, which is the real point of the question: (1) bound the scheduler pool and timeout every external call so one stuck run can't wedge the rest; (2) wrap each job body to catch-and-log-and-meter so an exception can't silently kill the schedule; (3) make jobs **idempotent** (a run may overlap a slow previous one or repeat after a restart); (4) for multi-instance, use ShedLock/leader-election with a `lockAtMostFor` safety valve so a dead lock-holder can't freeze the fleet (Q54); and most importantly (5) **monitor the job's last-success timestamp** — emit a metric/heartbeat on each successful run and alert if it's stale (a "dead man's switch"), because the whole failure mode here is *silence*, and the only way to catch silence is to alert on the *absence* of a heartbeat rather than on errors.

#### Q83. [Practical] How would you implement a transactional outbox to reliably publish events (e.g. to Kafka) from a Spring Boot service, and why not just publish inside the transaction?

The problem is the **dual-write**: a typical handler updates the database *and* publishes an event to Kafka. These are two separate systems with no shared transaction, so any ordering fails. If you publish to Kafka *then* commit the DB and the commit fails, you've emitted an event for something that didn't happen (a phantom). If you commit the DB *then* publish and the publish fails (or the JVM dies in between), you've persisted state but never told anyone (a lost event). There is no atomic "DB + Kafka" without distributed transactions, which are heavyweight and poorly supported here. The **transactional outbox** sidesteps this: within the *same local DB transaction* that changes your state, you also insert the event into an `outbox` table. One atomic commit, no dual write.

```java
@Transactional
public void placeOrder(Order order) {
    orderRepo.save(order);
    outboxRepo.save(new OutboxEvent("OrderPlaced", toJson(order)));  // same tx, atomic with the save
}
// A separate relay reads unpublished rows and pushes to Kafka, marking them sent.
```

A separate **relay/poller** then reads unpublished outbox rows and publishes them to Kafka, marking each as sent only after the broker acks. Because the relay can crash and re-run, publishing is **at-least-once**, so consumers must be **idempotent** (dedupe on the event id). The relay can be a simple `@Scheduled` poller (with ShedLock so only one instance polls, Q54/Q82) or, better, **Change Data Capture** (Debezium tailing the DB write-ahead log) which turns the outbox table into a Kafka topic with no polling lag and no app-side relay. A lighter-weight Spring-only variant for *in-process* listeners is `@TransactionalEventListener(phase = AFTER_COMMIT)` (Q44), which guarantees the side effect runs only after commit — but that's still in-memory and *not durable* (a crash after commit before the listener runs loses it), which is exactly why the outbox table (durable) is needed for cross-service guarantees.

The trade-off framing to deliver: the outbox buys you reliable, eventually-consistent event publishing with at-least-once delivery and ordering-per-aggregate, at the cost of an extra table, a relay, and the requirement that consumers dedupe. You accept *eventual* consistency (a small delay between commit and publish) in exchange for never losing or fabricating an event. The anti-pattern it replaces — `kafkaTemplate.send(...)` inside an `@Transactional` method — looks fine in the happy path and silently corrupts data under the failure cases that *will* eventually happen at scale, which is precisely the kind of operational maturity the question is probing.

#### Q84. [Practical] You enabled virtual threads (`spring.threads.virtual.enabled=true`) and throughput got *worse* under load. What's the likely cause and how do you confirm it?

The prime suspect is **pinning**. A virtual thread normally *unmounts* from its carrier OS thread when it blocks on I/O, freeing the carrier to run another virtual thread — that's the whole scalability win. But a virtual thread *cannot* unmount while it's inside a `synchronized` block/method or a native (JNI) call; it stays *pinned* to its carrier for the duration. If your hot path blocks on I/O *while holding a `synchronized` lock* — common in older libraries, some connection pools, logging frameworks, or your own code — the carrier thread is stuck, the small carrier pool (defaults to the number of cores) is quickly exhausted, and you've recreated thread-starvation *worse* than the old platform-thread model because you have far fewer carriers than you used to have Tomcat workers.

```bash
# Confirm pinning: this logs a stack trace every time a VT is pinned during a blocking op
java -Djdk.tracePinnedThreads=full -jar app.jar
# (Newer JDKs: use a JFR event — jdk.VirtualThreadPinned — captured in a JFR recording)
jcmd <pid> JFR.start settings=profile name=vt
```

You confirm it by running with `-Djdk.tracePinnedThreads=full` (older JDKs) or capturing the `jdk.VirtualThreadPinned` JFR event, which prints the exact stack where pinning occurred — that points you straight at the `synchronized` block. The fixes, in order: (1) replace `synchronized` with `java.util.concurrent.locks.ReentrantLock` (which *does* allow the virtual thread to unmount) in your own code; (2) upgrade the offending library to a version that switched off `synchronized` for I/O (many did exactly this for the virtual-thread era — and notably **JDK 24 (JEP 491)** largely *eliminates* pinning from `synchronized`, so upgrading the JDK can make the problem disappear); (3) keep genuinely native/unavoidable-synchronized blocking work on a *dedicated platform-thread pool* (a bulkhead) so it can't starve carriers.

A secondary cause if it's not pinning: virtual threads make it trivially easy to fan out *enormous* concurrency, which can overwhelm a *downstream* bottleneck — a small DB connection pool (Q74), a downstream service, or a rate-limited API — turning "more concurrency" into "more queueing and timeouts." So the second thing I check is whether throughput dropped because a *bounded downstream resource* is now saturated by the higher in-flight count; the fix there is to bound concurrency to the downstream with a semaphore/bulkhead rather than letting unlimited virtual threads pile onto a 10-connection pool. The lesson: virtual threads remove the *thread* bottleneck but expose whatever the *next* bottleneck was, and pinning is the trap that can make them actively worse than platform threads if your stack still uses `synchronized` on blocking paths.

#### Q85. [Practical] After a deploy, every request returns 415 Unsupported Media Type / 406 Not Acceptable that worked before. How do you debug content-type/negotiation issues?

A sudden fleet-wide 415 or 406 after a deploy that "changed nothing functional" almost always traces to the **`HttpMessageConverter` set on the classpath changing** — which a dependency bump can do silently. A **415 Unsupported Media Type** means the server has no converter that `canRead` the request's `Content-Type` into the handler's parameter type (inbound). A **406 Not Acceptable** means no converter can `canWrite` a response matching the client's `Accept` header (outbound). The distinction tells you which direction to look (Q63/Q59).

```
415 (inbound)  : request Content-Type has no matching converter / handler 'consumes' mismatch
406 (outbound) : request Accept header has no matching converter / handler 'produces' mismatch
Debug steps:
  1. mvn dependency:tree -> did jackson-databind / jackson-datatype-jsr310 / a starter drop out?
  2. Did someone add `produces=`/`consumes=` to @RequestMapping that's now too narrow?
  3. Is the client sending Content-Type at all? (missing header -> 415 on @RequestBody)
  4. --debug / TRACE org.springframework.web to see converter selection
```

The most common root causes: (1) a dependency change removed Jackson or a needed module — e.g. losing `jackson-datatype-jsr310` doesn't 415 by itself but losing `jackson-databind` (or the whole `spring-boot-starter-web` being excluded in favour of a custom set) removes the JSON converter, so every JSON endpoint 415s/406s. (2) Someone added an explicit `consumes`/`produces` to a `@RequestMapping` that's narrower than what clients actually send (e.g. `consumes = "application/json"` while a client posts `application/json;charset=UTF-8` — usually fine, but `text/plain` or a missing header now fails). (3) A client that stopped sending a `Content-Type` header at all — `@RequestBody` with no `Content-Type` can't pick a reader and 415s. (4) For XML, the XML converter is only present if Jackson-XML/JAXB is on the classpath, so a removed dependency silently drops `application/xml` support.

The debugging method: first `mvn dependency:tree` (or compare the new vs old build's dependency report) to spot a converter library that disappeared — this catches the silent-classpath-change case immediately. Then turn on `TRACE` for `org.springframework.web` (via `/actuator/loggers`, Q66) to watch which converter Spring selects (or fails to select) for a failing request. Then inspect the actual request with the real `Content-Type`/`Accept` headers (a trace/access log or `tcpdump`/proxy), because the client may be sending something different than you assume. The durable takeaway: media-type errors are a *converter availability* problem at heart, so reason about "which converters are on the classpath and what does each handler `consumes`/`produces`," and treat a dependency bump as a first-class suspect when negotiation behaviour changes without a code change to the controller.

### 🔴 Expert — extended

#### Q86. [Practical] Design a zero-downtime deployment strategy for a fleet of Spring Boot services, covering schema changes, rollout, and rollback. What are the failure modes?

Zero-downtime means at no point is the service unable to serve a valid request, even though instances are being replaced and the schema may be changing. It's the composition of several mechanisms that each have to be correct *together*. **Rollout mechanics**: use a rolling (or blue-green/canary) deploy where new pods must pass a **readiness** probe before receiving traffic and old pods **gracefully drain** (Q17/Q73) — flip readiness to OUT_OF_SERVICE on SIGTERM, let in-flight requests finish within the grace period, and ensure k8s `terminationGracePeriodSeconds` exceeds the app grace period so you're never SIGKILLed mid-drain. A `preStop` hook (small sleep or readiness flip) gives the load balancer time to stop routing before the context starts closing.

```
Zero-downtime deploy = (independent, must all hold):
  schema    : expand/contract — every migration backward-compatible with the OLD code (Q70)
  rollout   : readiness gate in + graceful drain out, surge new pods before terminating old
  config    : new config compatible with old running version (no flag that old code can't honor)
  contracts : API/event schema backward-compatible (additive) — consumer-driven contract tests
  rollback  : new version safe to revert to old WITHOUT a schema rollback
```

**Schema** is the hard part and the most common cause of "zero-downtime" outages. During a rolling deploy, old and new code run *simultaneously* against *one* database, so every migration must be backward-compatible with the version still running — the **expand/contract** discipline (Q70): add columns/tables (never drop or rename in the same release as the code change), deploy code that tolerates both shapes, backfill, then remove the old shape in a *later* release. The corollary for **rollback**: because you can't easily roll a schema *back* (data may already depend on the new column), the new app version must be safe to *revert to the previous app version without reverting the schema* — which the expand phase guarantees, since the old code never knew about the new column.

The **failure modes** to enumerate (this is what separates a senior answer): (1) a destructive migration (drop/rename) that breaks the old pods still serving traffic — the single most common self-inflicted outage; (2) liveness pointing at a downstream so a deploy-time dependency blip restarts pods (Q73); (3) grace period mismatch causing dropped in-flight requests; (4) a config/feature-flag change that the *old* running version can't honor (flag must be backward-compatible too); (5) breaking an API or event-schema contract that downstream consumers depend on — guard with **consumer-driven contract tests** (Spring Cloud Contract) in CI; (6) connection-pool/cache warmup causing the first requests on new pods to be slow (a `startupProbe` and pre-warming mitigate). The unifying principle: zero-downtime is achieved by making *every* change backward-compatible for the duration of the rollout and by gating traffic on readiness while draining gracefully — and by testing the *rollback* path, not just the forward path, because the time you most need to roll back is the time you can least afford to discover it doesn't work.

#### Q87. [Practical] Your GraalVM native image builds successfully but throws `ClassNotFoundException` / `NoSuchMethodException` only at runtime. How do you diagnose and fix native-image reflection/metadata gaps?

This is the defining operational pain of native images and a direct consequence of the **closed-world assumption** (Q18/Q51): `native-image` includes only code it can *prove* reachable by static analysis, and anything reached *only* via reflection, dynamic proxies, JNI, or resource loading is invisible to that analysis, so it's stripped — then blows up at runtime when the reflective access happens. The build succeeds because the missing element isn't a compile error; it's a runtime reachability gap. Symptoms: a Jackson DTO that won't deserialize, a JPA entity that fails to instantiate, a `@ConfigurationProperties` binding that returns null, a missing `messages.properties`, or a JDK-dynamic-proxy interface that can't be created.

```java
// Tell GraalVM about reflective/serialization needs explicitly:
@RegisterReflectionForBinding(CustomerDto.class)        // reflection for Jackson (de)serialization
@ImportRuntimeHints(MyHints.class)
class AppConfig {}

class MyHints implements RuntimeHintsRegistrar {
    public void registerHints(RuntimeHints hints, ClassLoader cl) {
        hints.reflection().registerType(LegacyThing.class, MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
        hints.resources().registerPattern("db/migration/*.sql");   // keep a resource bundle
        hints.proxies().registerJdkProxy(SomeIntf.class);
    }
}
```

The diagnosis workflow: (1) **Reproduce against the running native binary**, not the JVM — by definition this only manifests in native, so your test gate must include native integration tests (the `process-aot`/native test support runs your tests against the AOT-processed app). (2) Read the exception — it names the exact class/method/resource that lacked metadata. (3) Use the **GraalVM tracing agent** during a JVM run to *auto-generate* the metadata: `java -agentlib:native-image-agent=config-output-dir=...` exercises the app under realistic traffic/tests and records every reflective/resource/proxy access into `reachability-metadata.json`, which you then ship under `META-INF/native-image`. (4) For framework-managed beans, Spring's AOT already generates most hints, so a gap usually means a *library* without metadata or *your* code doing reflection Spring can't see — fill it with `RuntimeHintsRegistrar`/`@RegisterReflectionForBinding`.

The expert framing: this is fundamentally a *test-and-metadata* discipline, not a debugging-after-prod one. Because the native build behaves differently from the JVM build, it is a **separate artifact that must be tested as such** — run the full integration suite against the native image (or at least the AOT-processed JVM) in CI, ideally with the tracing agent capturing real code paths, so reachability gaps fail the build instead of failing in production. The deeper trade-off to acknowledge: anything that decides wiring dynamically (active profiles, `@ConditionalOnProperty` affecting bean structure, runtime-chosen implementations) is frozen at build time in native (Q51), so some patterns must be redesigned rather than hinted. Teams that adopt native successfully treat the metadata + native test gate as part of the build contract from day one, not as a porting afterthought.

#### Q88. [Practical] During a deploy, your Kafka consumers enter an endless rebalance loop and stop processing. How do you diagnose and stabilize Spring Kafka consumers?

An endless rebalance loop almost always means consumers are being **kicked out of the group for missing heartbeats or exceeding the poll interval**, which triggers a rebalance, which slows things further, which causes more timeouts — a feedback loop. The root cause in Spring Kafka is usually that **`max.poll.interval.ms` is exceeded**: the consumer fetches a batch of records and your listener takes longer to process them than `max.poll.interval.ms` (default 5 min), so the broker assumes the consumer is dead and rebalances the partition to someone else — but the original consumer is still chewing on the batch, so when it finally calls `poll()` again it's been evicted, and the whole group reshuffles.

```yaml
spring.kafka.consumer:
  max-poll-records: 50            # smaller batches -> each poll cycle finishes within the interval
  properties:
    max.poll.interval.ms: 300000  # raise only if processing genuinely needs longer
    session.timeout.ms: 45000     # heartbeat-based liveness (separate from poll interval)
```

Diagnosis: enable DEBUG on `org.apache.kafka.clients.consumer` and look for `"Member ... sending LeaveGroup"` / `"Attempt to heartbeat failed"` / `"This member will leave the group because consumer poll timeout has expired"`. That last message is the smoking gun for the `max.poll.interval.ms` cause. Also watch consumer lag and the `kafka.consumer.fetch.manager` / Spring's `spring.kafka.listener` metrics, and check whether a single **poison-pill record** keeps throwing in the listener, causing infinite retry that blocks the partition (which looks like "stuck" and can interact with rebalancing).

Stabilization, in order: (1) **shrink `max-poll-records`** so each batch processes well within `max.poll.interval.ms` — this is the most common fix and far better than blindly raising the interval (a huge interval delays detection of genuinely dead consumers). (2) If processing is inherently slow, move it off the poll thread or raise `max.poll.interval.ms` deliberately. (3) Add a **`DefaultErrorHandler` with a `DeadLetterPublishingRecoverer`** so a poison-pill record is retried a bounded number of times then sent to a DLT instead of blocking the partition forever. (4) Tune `session.timeout.ms`/`heartbeat.interval.ms` (heartbeats run on a *background* thread in modern clients, so they're separate from poll-interval liveness — don't conflate them). (5) During deploys specifically, use **cooperative-sticky** partition assignment (`partition.assignment.strategy`) so a rolling restart causes incremental rebalances (only the moving partitions) instead of stop-the-world reassignment of every partition — this alone turns a disruptive deploy-time rebalance storm into a smooth handoff. (6) Make the listener **idempotent**, because at-least-once delivery plus rebalances means records *will* occasionally be reprocessed. The interview signal is distinguishing the two liveness mechanisms (background heartbeat vs poll-interval) and knowing that the cure is usually *smaller batches + DLT + cooperative rebalancing*, not just cranking timeouts.

#### Q89. [Practical] Define an SLO-based alerting strategy for a Spring Boot service using Actuator/Micrometer metrics. Which signals matter and how do you avoid alert fatigue?

The framework I'd anchor on is the **four golden signals** (latency, traffic, errors, saturation) expressed as **SLOs** (a target like "99.9% of requests succeed in <300ms over 30 days") with **error-budget burn-rate** alerting — because alerting on *symptoms the user feels*, not on every internal metric, is what avoids fatigue. Spring Boot gives you the raw signals out of the box via Micrometer: `http.server.requests` (a timer tagged by uri/status/method — your latency, traffic, and error rate in one), `jvm.gc.pause` and `jvm.memory.used` (saturation), `hikaricp.connections.*` (DB saturation, Q22/Q74), `resilience4j.circuitbreaker.state` (dependency health, Q29), and `executor.*` for thread pools.

```promql
# Symptom alerts (page on these): error rate and latency SLO
sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m]))
  / sum(rate(http_server_requests_seconds_count[5m])) > 0.001      # >0.1% 5xx

histogram_quantile(0.99,
  sum(rate(http_server_requests_seconds_bucket[5m])) by (le)) > 0.3  # p99 > 300ms

# Cause/saturation alerts (ticket, not page): leading indicators
hikaricp_connections_pending > 0                                   # pool saturation (Q22)
resilience4j_circuitbreaker_state{state="open"} == 1               # open breaker
```

The key discipline against alert fatigue is the **symptom-vs-cause split** and **burn-rate** alerting. You *page* on a small number of user-facing symptom SLOs (error rate, latency, availability) using multi-window burn-rate: a *fast* burn (e.g. consuming 2% of the 30-day budget in 1 hour) pages immediately because something is actively on fire; a *slow* burn (a gradual degradation over hours) opens a ticket. Cause/saturation metrics (pending connections, open breakers, GC pauses, queue depth) are *leading indicators* — they predict an SLO breach before users feel it — so they're great for *ticket*-level alerts and dashboards, but paging on every one of them at 3am produces the fatigue that makes people ignore the *real* page. The error budget itself is the governor: if you're burning budget fast, page; if budget is healthy, a blip is tolerable and doesn't need to wake anyone.

The implementation and pitfalls: expose `/actuator/prometheus`, scrape with Prometheus, and **be careful with metric cardinality** — `http.server.requests` tagged by raw URI explodes if you have path variables (`/users/123`), so rely on Spring's URI templating (`/users/{id}`) and never tag by high-cardinality values (user id, request id) or you'll OOM the metrics backend, a real production incident in itself. Calibrate thresholds from *measured* baselines and historical percentiles, not round numbers; alert on *rates and ratios* (error %), not raw counts; and ensure every page is *actionable* with a runbook. The senior framing to land: alert on what the *user* experiences (SLOs/golden signals), use error-budget burn rate to decide page-vs-ticket, treat saturation metrics as predictive ticket-level signals, and ruthlessly prune any alert that has fired without anyone needing to act — an alert nobody acts on is worse than no alert because it erodes trust in the ones that matter.

#### Q90. [Practical] A `@Transactional` method occasionally fails with deadlocks or `OptimisticLockException` under concurrency. How do you diagnose and resolve database locking issues in a Spring Boot app?

These are two different concurrency failures and the distinction drives the fix. A **deadlock** is two transactions each holding a lock the other needs, in opposite order — the database detects the cycle and kills one transaction (Postgres/MySQL throw a deadlock error, surfaced as a `CannotAcquireLockException`/`DeadlockLoserDataAccessException` in Spring's exception translation). An **`OptimisticLockException`** (Spring's `ObjectOptimisticLockingFailureException`) comes from JPA's `@Version` optimistic locking: two transactions read the same row, both try to write, and the second sees the version changed underneath it and aborts — *by design*, no DB-level lock was held. Diagnosing which you have is step one: read the actual exception and the DB logs.

```java
@Entity
class Account {
    @Id Long id;
    @Version long version;     // optimistic: detect concurrent writes, no held lock
    BigDecimal balance;
}

// Pessimistic lock when contention is high and you must serialize:
@Lock(LockModeType.PESSIMISTIC_WRITE)   // SELECT ... FOR UPDATE
@Query("select a from Account a where a.id = :id")
Account findForUpdate(@Param("id") Long id);
```

For **deadlocks**, the canonical root cause is *inconsistent lock ordering* — transaction A updates rows in order (1,2) while B updates (2,1). The fixes: (1) **acquire locks in a consistent order** everywhere (e.g. always update accounts by ascending id); (2) keep transactions **short** — the longer a tx holds locks, the wider the deadlock window, so move slow/remote work *outside* the transaction (Q22); (3) add **retry with backoff** for the transient deadlock loser (`@Retryable` on `CannotAcquireLockException`), since the DB killing one victim is a *recoverable* event and a retry usually succeeds; (4) reduce the lock footprint with appropriate indexes (a missing index can cause a query to lock far more rows than expected) and the right isolation level (overly strong isolation like SERIALIZABLE multiplies conflicts). Capture the DB's deadlock log (`SHOW ENGINE INNODB STATUS` / Postgres `deadlock` log lines) — it prints *exactly* the two statements and lock modes involved, which is the ground truth.

For **optimistic-lock failures**, the question is whether contention is *rare* (then optimistic locking is the right, cheap choice — just **retry** the failed transaction, re-reading the fresh version) or *frequent* (then optimistic locking thrashes with constant retries, and you should switch the hot path to **pessimistic locking** (`PESSIMISTIC_WRITE` / `SELECT ... FOR UPDATE`) to serialize access, accepting reduced concurrency for correctness — or redesign to avoid the hot row entirely, e.g. shard a counter, use an append-only ledger, or push the contention into a queue). The expert judgment to articulate: optimistic locking favors throughput under low contention (no held locks) and pessimistic favors correctness under high contention (serialized), deadlocks are an *ordering* problem solved by consistent acquisition + short transactions + retry, and the most powerful general lever for *all* of these is **shortening transactions and never doing remote/slow work while holding locks** — which is the same root-cause discipline behind connection-pool exhaustion (Q22). Always pair lock strategy with idempotent retry, because under concurrency *some* transaction *will* lose, and a clean retry turns a user-facing error into an invisible blip.

#### Q91. [Coding] Implement an idempotent POST endpoint using an idempotency key so client retries don't create duplicates.

**Problem:** A payment/order creation endpoint may be retried by clients (or by a gateway) after a timeout; without protection a single logical request creates two rows. The standard solution is an **idempotency key**: the client sends a unique key per logical operation, and the server guarantees that repeating the same key returns the *original* result instead of doing the work twice.

```java
@PostMapping("/payments")
public ResponseEntity<PaymentResult> pay(
        @RequestHeader("Idempotency-Key") String key,
        @Valid @RequestBody PaymentRequest req) {

    // 1) Try to claim the key atomically. The UNIQUE constraint on idempotency_key
    //    is the real concurrency guard — two parallel retries race here and exactly
    //    one wins the INSERT; the other catches the duplicate-key exception.
    try {
        store.begin(key, req.fingerprint());          // INSERT ... (status=IN_PROGRESS)
    } catch (DuplicateKeyException dup) {
        IdempotencyRecord rec = store.get(key);
        if (!rec.fingerprint().equals(req.fingerprint()))
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).build(); // same key, different body
        if (rec.status() == IN_PROGRESS)
            return ResponseEntity.status(HttpStatus.CONFLICT).build();             // still running, tell client to retry
        return ResponseEntity.status(rec.httpStatus()).body(rec.response());       // replay stored result
    }

    // 2) First time for this key — do the work, then persist the response under the key.
    PaymentResult result = paymentService.charge(req);
    store.complete(key, HttpStatus.CREATED.value(), result);
    return ResponseEntity.status(HttpStatus.CREATED).body(result);
}
```

**Why this design:** the correctness hinges on a **database UNIQUE constraint on the key**, not on a check-then-act in Java — under concurrent retries, "check if key exists, else insert" has a race window, whereas letting the INSERT fail with `DuplicateKeyException` is atomic. Storing a **request fingerprint** (a hash of the body) lets you reject the dangerous case where a client reuses a key with a *different* payload (422). Storing the **response** lets you replay the exact original result on a retry, which is what idempotency really promises.

**Edge cases and trade-offs:** (1) the work and the `store.complete` should be in the *same transaction* (or use the outbox pattern, Q83) so you never charge-but-fail-to-record; (2) put a **TTL** on idempotency records (e.g. 24–72h) so the table doesn't grow forever — keys are only useful for the retry window; (3) for a distributed fleet, the store must be shared (the DB or Redis), not in-memory per pod; (4) decide what an `IN_PROGRESS` collision returns — usually 409 so the client backs off rather than getting a half-formed answer. **Complexity:** O(1) per request (one indexed lookup/insert). This pattern is the server-side complement to client retry-with-jitter (Q67/Q89) — together they make at-least-once delivery safe.

#### Q92. [Practical] How do you diagnose and fix a "cache stampede" (thundering herd) when a hot cache entry expires under load?

A cache stampede happens when a popular cache entry expires (or the cache is cleared/restarted) and *every* concurrent request simultaneously misses, so they *all* hit the slow backend at once — a sudden synchronized load spike that can overwhelm the database and cause a cascading failure, often right after a deploy that flushed a Redis cache. The symptom is a periodic latency/error spike that correlates with cache TTL boundaries, or a DB CPU spike immediately after a cache restart while the cache is cold.

```java
// Caffeine: only ONE thread recomputes a key; others wait for that result (no stampede)
@Bean
CacheManager cacheManager() {
    var caffeine = Caffeine.newBuilder()
        .maximumSize(10_000)
        .expireAfterWrite(Duration.ofMinutes(10))
        .refreshAfterWrite(Duration.ofMinutes(8));   // async refresh BEFORE expiry -> entry never goes cold
    var mgr = new CaffeineCacheManager();
    mgr.setCaffeine(caffeine);
    return mgr;
}
```

The fixes, layered: (1) **single-flight / per-key locking** so only one request recomputes a given key while the others wait for that result — Caffeine's `LoadingCache` does this natively (a `get` with a loader is computed once per key under concurrent access), which is the cleanest mitigation and a strong reason to prefer a loading cache over manual `@Cacheable` get-or-compute. (2) **`refreshAfterWrite`** (Caffeine) recomputes an entry *asynchronously before* it expires, so the hot entry is never actually cold — readers keep getting the slightly-stale value while one background thread refreshes it. (3) **TTL jitter** — add randomness to each entry's expiry so a batch of entries written together don't all expire on the same tick (the synchronized-expiry version of the herd). (4) For a **distributed** cache (Redis), a single-pod loading cache doesn't coordinate across the fleet, so use a short distributed lock (Redisson lock / `SET NX`) around the recompute, or accept per-pod single-flight as a "good enough" reduction.

The operational nuance: the worst stampede is a **cold start after a cache flush/restart** — every key misses at once. Mitigate by warming critical keys on startup (an `ApplicationRunner` pre-loading the top-N hot entries), staggering cache restarts, and protecting the backend with a bulkhead/rate limiter (Q81) so even a stampede can't take the DB down. The deeper lesson connects to Q39: `@Cacheable` itself has no stampede protection or TTL — those are properties of the *cache provider* and the *access pattern*, so "we added caching" doesn't mean "we're protected from load spikes," and the cache *expiry* behavior under concurrency is exactly what you must design for.

#### Q93. [Theory] Explain JVM heap and GC tuning for a containerized Spring Boot service. Which collector and flags actually matter in 2026?

The first principle in a container is letting the JVM *see* its limits and sizing the heap as a fraction of them, not with absolute numbers. Modern JDKs are container-aware, so the right move is `-XX:MaxRAMPercentage=75` (leaving ~25% headroom for the non-heap RSS — metaspace, thread stacks, direct buffers, code cache, GC structures — which Q68 covers) rather than a hardcoded `-Xmx` that ignores the cgroup limit and gets you OOMKilled. For a steady-load server you typically set `-Xms` equal to `-Xmx` (or `InitialRAMPercentage = MaxRAMPercentage`) so the heap doesn't spend startup growing incrementally and so the OS commits the memory you've budgeted.

```bash
# Containerized, low-pause defaults that age well
-XX:MaxRAMPercentage=75
-XX:InitialRAMPercentage=75
-XX:+UseG1GC                      # default; great general-purpose, pause-target driven
-XX:MaxGCPauseMillis=200          # G1 pause goal (soft)
# Latency-critical, large heap: prefer a concurrent collector
-XX:+UseZGC -XX:+ZGenerational    # sub-millisecond pauses, scales to large heaps
-Xlog:gc*:file=/var/log/gc.log:time,uptime:filecount=5,filesize=20m   # always log GC
```

Collector choice is the next lever and it's workload-driven. **G1** (the default since JDK 9) is the right answer for the vast majority of services — it's a pause-target-driven, region-based collector that balances throughput and latency well and needs almost no tuning beyond `MaxGCPauseMillis`. **ZGC** (now generational, production-ready) is for *latency-critical* services and *large heaps* where even G1's tens-of-milliseconds pauses are too much — it delivers sub-millisecond pauses largely independent of heap size, at some throughput/footprint cost. **Parallel GC** maximizes raw throughput for batch/offline work where pause time doesn't matter. The 2026 framing: don't reach for exotic flags — pick the collector that matches your latency-vs-throughput priority, set the RAM percentage and pause goal, and *measure*.

The discipline that matters more than any single flag: **always enable GC logging** (it's nearly free and is the ground truth when you have the p99 spikes of Q79), and tune from *evidence* — `jstat -gcutil`, the `jvm.gc.pause` Micrometer metric, and GC logs tell you whether pauses, allocation rate, or premature promotion are the problem. The classic mistakes are hardcoding `-Xmx` larger than the container limit (OOMKilled), setting a tiny heap that GCs constantly (CPU burned in GC, looks like Q76), and cargo-culting flags from a blog without measuring. And remember GC tuning is often the *wrong* fix — high allocation rate from creating garbage in a hot loop (huge JSON payloads, N+1 materialization) is better solved in the code than by fiddling with the collector.

#### Q94. [Practical] Cross-origin browser requests to your API fail with CORS errors, but curl works fine. How does CORS work in Spring Boot and how do you configure it correctly?

The "curl works, browser fails" signature is the tell that this is **CORS**, a *browser-enforced* policy — curl and server-to-server calls ignore it entirely, so the failure is purely client-side enforcement of the same-origin policy. When a browser page on `https://app.example.com` calls your API on `https://api.example.com`, the browser first sends a **preflight** `OPTIONS` request (for non-simple requests) asking "may this origin use this method/headers?"; your server must answer with the right `Access-Control-Allow-*` headers or the browser blocks the *actual* request and surfaces a CORS error — even though the server may have been perfectly willing to respond.

```java
// Global, explicit CORS config (preferred over scattering @CrossOrigin)
@Bean
WebMvcConfigurer corsConfig() {
    return new WebMvcConfigurer() {
        public void addCorsMappings(CorsRegistry r) {
            r.addMapping("/api/**")
             .allowedOrigins("https://app.example.com")   // explicit origins, NOT "*"
             .allowedMethods("GET","POST","PUT","DELETE")
             .allowedHeaders("Authorization","Content-Type")
             .allowCredentials(true)                       // needed for cookies/Authorization
             .maxAge(3600);                                // cache preflight 1h
        }
    };
}
```

The configuration choices have real consequences. (1) **Never use `allowedOrigins("*")` together with `allowCredentials(true)`** — it's an invalid combination the browser rejects (you can't send credentials to a wildcard origin), and it's a security smell; list explicit origins, or use `allowedOriginPatterns` for controlled wildcarding. (2) The most common real bug is **Spring Security intercepting the preflight before MVC CORS runs** — the `OPTIONS` request has no auth header, Security 401s it, and the browser reports a CORS failure; the fix is `http.cors(Customizer.withDefaults())` so Security delegates to your `CorsConfigurationSource` and lets preflights through. (3) `maxAge` caches the preflight so the browser doesn't `OPTIONS` every call.

The diagnosis method: open the browser dev-tools Network tab and look at the `OPTIONS` preflight — its response headers tell you exactly which `Access-Control-Allow-*` is missing or mismatched (origin not allowed, method not allowed, header not allowed, or credentials mismatch). The architectural recommendation: for a real system, terminate CORS at the **edge/gateway** consistently rather than per-service, configure it *centrally* (a shared `CorsConfigurationSource`) rather than sprinkling `@CrossOrigin` annotations (which drift and are easy to forget), and treat the allowed-origins list as a security control — it's not authentication, but a permissive CORS policy combined with credentials can enable cross-site data exfiltration, so keep it explicit and minimal.

#### Q95. [Practical] A large file upload (or big JSON request) fails with "Maximum upload size exceeded" or a 400. How do request size limits work in Spring Boot and how do you handle large payloads?

There are *several independent* size limits in the stack, and the error you get depends on which one you hit — so the first diagnostic step is identifying *which* limit fired. For multipart uploads, Spring's `MultipartProperties` cap individual file size and total request size; the embedded server (Tomcat) has its *own* `max-swallow-size` and `max-http-form-post-size`; and a header/URL has `max-http-request-header-size`. Hitting the Spring multipart limit throws `MaxUploadSizeExceededException`; hitting Tomcat's limit can manifest as a connection reset or a different error before Spring even sees it.

```yaml
spring.servlet.multipart:
  max-file-size: 50MB          # per file
  max-request-size: 60MB       # whole multipart request
server.tomcat:
  max-swallow-size: 60MB       # Tomcat will reset the conn if body exceeds this while it drains
  max-http-form-post-size: 60MB
```
```java
@ExceptionHandler(MaxUploadSizeExceededException.class)
ProblemDetail tooBig(MaxUploadSizeExceededException ex) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE,
        "File exceeds the 50MB limit");                 // return a clean 413, not a 500
}
```

The deeper design point is that *raising the limit is usually the wrong answer* for genuinely large files. Buffering a 2GB upload into memory (or even a temp file) to then re-stream it is wasteful and a DoS vector — a few concurrent large uploads can exhaust heap or disk. The better patterns: (1) **stream** the upload rather than buffering — process the `InputStream` incrementally (e.g. pipe straight to object storage) so memory stays flat regardless of file size; (2) for cloud storage, use **pre-signed URLs** so the client uploads *directly to S3/GCS* and your service never proxies the bytes at all — it just issues the URL and records the metadata, which is the scalable answer; (3) keep the API endpoint limits *deliberately low* to reject abuse early, and route legitimately-large transfers through the storage path.

The operational gotchas: (1) remember the limit exists at **multiple layers** — Spring multipart, Tomcat, *and* any reverse proxy/ingress (nginx `client_max_body_size`, the AWS ALB/API Gateway payload caps) — so a request can be rejected upstream before it reaches your app, and you must align all of them. (2) Provide a clean **413 Payload Too Large** via an `@ExceptionHandler` (Q12) so clients get a meaningful error instead of a 500 or a reset connection. (3) For big *JSON* bodies specifically, also watch Jackson's parser limits and the cost of materializing a huge object graph (memory + GC, Q93) — streaming/`JsonParser` or pagination beats accepting a giant single request.

#### Q96. [Practical] How do you route reads to a replica and writes to the primary in Spring Boot, and what are the consistency pitfalls?

The mechanism is a **routing `DataSource`**: Spring provides `AbstractRoutingDataSource`, which picks the *actual* datasource per call based on a lookup key you control (typically stored in a `ThreadLocal`). You register two real datasources (primary/writer and replica/reader), and the routing datasource selects between them. The cleanest way to set the key is to key off the *transaction's read-only flag* — `@Transactional(readOnly = true)` becomes "use the replica," everything else uses the primary.

```java
public class RoutingDataSource extends AbstractRoutingDataSource {
    @Override protected Object determineCurrentLookupKey() {
        // Spring exposes the current transaction's read-only flag here
        return TransactionSynchronizationManager.isCurrentTransactionReadOnly() ? "replica" : "primary";
    }
}

@Service
class ReportService {
    @Transactional(readOnly = true)                 // -> routed to the replica
    List<Sales> dashboard() { ... }

    @Transactional                                  // -> routed to the primary (writer)
    void recordSale(Sale s) { ... }
}
```

The critical correctness pitfall is **replication lag**: a read replica is *eventually* consistent, so a read issued immediately after a write may not see that write (you save a record, redirect, and the detail page 404s because the replica hasn't caught up — the "read-your-own-writes" violation). The mitigations: (1) **route reads that must be read-your-own-writes to the primary** — not every read can go to the replica; user-facing flows right after a mutation often must hit the writer. (2) Be careful that the routing key is resolved *before* the connection is acquired — `determineCurrentLookupKey` runs at connection time, and a `@Transactional(readOnly=true)` that spans a write is a bug. (3) A read-only transaction is also a Hibernate optimization (flush mode `MANUAL`), so accidentally writing inside one fails or silently no-ops.

The architecture-level judgment: read-replica routing scales *read-heavy* workloads (reporting, dashboards, search) by offloading the primary, but it introduces eventual consistency that the application must be designed for, so it's not a transparent free win. Alternatives to weigh: a CQRS read model, a caching layer (Q92), or just a bigger primary if the read/write ratio doesn't justify the complexity. And in a fleet, prefer pushing this into a **shared platform library/auto-config** (Q24) so every service routes consistently rather than re-implementing the routing datasource (and its lag pitfalls) per team. The interview signal is naming replication lag and read-your-own-writes as the thing that bites teams who treat "just point reads at the replica" as transparent.

#### Q97. [Practical] How do you reproduce and fix a "works on my machine" bug that only appears in production? Walk through your methodology with Spring Boot specifics.

The disciplined approach is to enumerate **what differs between local and prod** and bisect, because "works locally, fails in prod" is by definition an *environment delta* problem. The usual axes of difference, which I check roughly in this order: (1) **configuration/profiles** — a different active profile, an env var overriding a property you didn't expect (Q65), a secret-manager value vs the local default; confirm with `/actuator/env` which value actually won. (2) **data** — prod has volume, skew, nulls, and edge-case rows local doesn't; an N+1 (Q14) or a missing index is invisible on 100 dev rows and lethal on 10M prod rows. (3) **concurrency/load** — race conditions, pool exhaustion (Q22), deadlocks (Q90), and cache stampedes (Q92) only manifest under real concurrency. (4) **infra** — real network latency and downstream timeouts (Q67), container memory limits triggering OOMKilled (Q68), a different DB engine than the H2 you tested with. (5) **time/locale/timezone** — the JVM default timezone differs between your laptop and the prod container, breaking date logic.

```bash
# Make prod observable, then bisect the environment delta:
curl -s localhost:8080/actuator/env | jq          # what config/profiles actually loaded? (Q65)
curl -s localhost:8080/actuator/configprops | jq  # resolved @ConfigurationProperties values
curl -X POST .../actuator/loggers/com.acme -d '{"configuredLevel":"DEBUG"}'  # raise logging live (Q66)
curl -s localhost:8080/actuator/threaddump        # stuck under concurrency? (Q71)
curl -o h.hprof localhost:8080/actuator/heapdump  # memory grows in prod only? (Q69)
```

The methodology, stated as a loop: **reproduce in an environment as prod-like as possible** before changing code — spin up the service with the prod profile against a Testcontainers (Q19) copy of the *real* database engine (never H2, which masks dialect/locking/index behavior), seed it with prod-shaped data or a sanitized snapshot, and apply load if concurrency is suspected. Use the observability you built (traces for a failing request, Q79; logs correlated by trace id, Q66/Q80; metrics) to pinpoint the failing layer from the *actual* prod incident rather than guessing. Form a single hypothesis about the delta, change *one* variable, and verify — resist the urge to change five things at once, which destroys your ability to attribute the fix.

The senior framing: the goal is to *narrow the environment delta until the bug appears in a controlled setting*, because a bug you can reproduce is a bug you can fix and write a regression test for, while a bug you "fixed" by guessing in prod will come back. The Spring-specific leverage points are Actuator (`/env`, `/configprops`, `/threaddump`, `/heapdump`, `/loggers`) for runtime introspection without a redeploy, Testcontainers for prod-fidelity reproduction, and profile/property awareness for the config-drift class of bugs — which, in my experience, is the single most common root cause of "works on my machine."

#### Q98. [Theory] When and how should you use Spring Boot DevTools, and why must it never reach production?

Spring Boot DevTools is a *developer-experience* dependency that speeds up the local edit-run loop. Its headline features: **automatic restart** (a dual-classloader trick — your project classes load in a restart classloader that's discarded and rebuilt on change, while the slow-changing dependency classes stay in the base classloader, so a restart is far faster than a cold boot), **LiveReload** (a browser refresh trigger), and **sensible dev-time property defaults** (it disables template/static-resource caching so you see changes immediately, and tweaks a few other settings for fast feedback). It's added with `spring-boot-devtools` and is, by design, only active during development.

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-devtools</artifactId>
  <optional>true</optional>          <!-- crucial: stops it propagating to dependents -->
</dependency>
```

The reason it must never reach production is partly automatic and partly your responsibility. Boot *automatically disables* DevTools when it detects the app was launched from a fully packaged jar (i.e. `java -jar`), and the Maven/Gradle plugins exclude it from the repackaged fat jar — so a normal production build already strips it. But you reinforce this by marking the dependency `optional`/`developmentOnly` so it never leaks onto a *downstream* module's classpath, and by never force-including it. The danger if it *did* run in prod is real: the restart classloader adds memory and complexity you don't want, the dev property overrides (disabled caching) hurt performance, and historically DevTools shipped a remote-debug/remote-restart feature that, if enabled and exposed, is a serious **remote code execution** risk — which is exactly why it's gated to development only.

The senior framing: DevTools is a productivity tool, not a runtime feature, and the right mental model is that it changes *defaults for the dev loop* (fast restart, no caching, LiveReload) which are precisely the opposite of what you want in production (stable classloading, aggressive caching, no remote hooks). The discipline is `optional`/`developmentOnly` scope plus trusting (and verifying) Boot's automatic disabling for packaged artifacts — and for the fastest *inner* loop, knowing that DevTools restart is faster than a cold start but slower than true hot-swap, so for trivial method-body edits, JVM HotSwap/JRebel-style reload or your IDE's "build & reload classes" can beat even DevTools. Either way, it is a local-only accelerant that the build pipeline must guarantee never ships.

## 🧩 Extended Questions — Set 3: Coding, Design & Expert Scenarios

### 🟢 Basic — extended

#### Q99. [Coding] Write a custom Bean Validation constraint (annotation + validator) and wire it into a Spring controller.

**Problem:** The built-in constraints (`@NotBlank`, `@Email`, `@Size`) don't cover a domain rule — say a `@StrongPassword` that must be at least 12 chars with an upper, a lower, a digit, and a symbol. The right Spring/Jakarta way is to define a *constraint annotation* and a `ConstraintValidator`, not to pollute the controller with `if` checks.

```java
@Target({ FIELD, PARAMETER })
@Retention(RUNTIME)
@Constraint(validatedBy = StrongPasswordValidator.class)
public @interface StrongPassword {
    String message() default "password does not meet complexity requirements";
    Class<?>[] groups() default {};                 // required by the Bean Validation spec
    Class<? extends Payload>[] payload() default {}; // required by the spec
    int minLength() default 12;
}

public class StrongPasswordValidator
        implements ConstraintValidator<StrongPassword, String> {

    private int minLength;
    private static final Pattern UPPER = Pattern.compile("[A-Z]");
    private static final Pattern LOWER = Pattern.compile("[a-z]");
    private static final Pattern DIGIT = Pattern.compile("\\d");
    private static final Pattern SYM   = Pattern.compile("[^A-Za-z0-9]");

    @Override public void initialize(StrongPassword ann) { this.minLength = ann.minLength(); }

    @Override public boolean isValid(String value, ConstraintValidatorContext ctx) {
        if (value == null) return true;             // let @NotNull own null-ness, not us
        return value.length() >= minLength
            && UPPER.matcher(value).find() && LOWER.matcher(value).find()
            && DIGIT.matcher(value).find() && SYM.matcher(value).find();
    }
}
```

```java
public record SignupRequest(@NotBlank @Email String email,
                            @StrongPassword String password) {}

@PostMapping("/signup")
public ResponseEntity<Void> signup(@Valid @RequestBody SignupRequest req) { ... }
```

**Why this design:** the validator is a Spring-managed bean *only if you need injection* — a `ConstraintValidator` can `@Autowired` other beans (e.g. a `BreachedPasswordChecker`) because Hibernate Validator uses Spring's `SpringConstraintValidatorFactory` to instantiate them. Returning `true` for `null` is the canonical convention so that nullability is a *separate, composable* concern owned by `@NotNull`. When validation fails on a `@RequestBody`, Spring throws `MethodArgumentNotValidException`, which your global handler (Q12) maps to a 400 ProblemDetail — so the new constraint slots into the existing error pipeline with zero controller changes. **Edge cases:** for *cross-field* rules (e.g. `password == confirmPassword`) you annotate the *class* and validate the whole object; avoid catastrophic-backtracking regexes on user input (Q76); and use a `ConstraintValidatorContext` to build a *dynamic* message if you want to tell the user *which* rule failed.

#### Q100. [Coding] Implement a `CommandLineRunner` that seeds reference data on startup, and explain how it differs from `ApplicationRunner` and `@PostConstruct`.

**Problem:** On first boot, populate a `country` lookup table if it's empty — a common "seed reference data" task. The idiomatic hook is a `CommandLineRunner` (or `ApplicationRunner`), which runs *after* the context is fully initialized and the web server is up but *before* `ApplicationReadyEvent` completes.

```java
@Component
@Profile("!test")                      // don't seed during slice/integration tests
class ReferenceDataSeeder implements ApplicationRunner {

    private final CountryRepository countries;
    ReferenceDataSeeder(CountryRepository countries) { this.countries = countries; }

    @Override
    @Transactional                     // seed in one transaction; rolls back on partial failure
    public void run(ApplicationArguments args) {
        if (countries.count() > 0) return;          // idempotent: skip if already seeded
        countries.saveAll(List.of(
            new Country("US", "United States"),
            new Country("IN", "India"),
            new Country("DE", "Germany")));
    }
}
```

**The three hooks, and why the runner wins here:** `@PostConstruct` runs during *bean initialization* — too early, because not every bean (or the `DataSource`, or transactional infrastructure) is guaranteed ready, and an exception there aborts context startup in a confusing way. `CommandLineRunner.run(String...)` and `ApplicationRunner.run(ApplicationArguments)` both run once the context is *fully refreshed*; the only difference is the argument type — `ApplicationRunner` gives you parsed `ApplicationArguments` (option flags vs non-option args), which is cleaner than the raw `String[]` of `CommandLineRunner`. Multiple runners execute in `@Order` sequence.

**Why idempotent + transactional + profile-gated:** seeding must be *idempotent* (the `count() > 0` guard) because the app restarts and you must not duplicate rows — the same discipline as Q91/Q83. Wrapping it in `@Transactional` means a partial failure rolls back rather than leaving half-seeded data. Gating with `@Profile("!test")` keeps the seeder out of test contexts where it would either slow tests or collide with test fixtures. **Edge case for multi-instance:** like `@Scheduled` (Q54), a runner fires on *every* replica, so for a real fleet either make the seed a one-shot deploy Job/Flyway migration (Q70) or guard it with a distributed lock — a naive runner racing across pods can deadlock or double-insert.

### 🟡 Intermediate — extended

#### Q101. [Coding] Build a declarative HTTP client with `@HttpExchange` and add resilience and a custom error decoder.

**Problem:** Call a remote `customers` API from a blocking service. Instead of hand-writing `RestClient` calls everywhere, define a typed interface and let Spring generate the proxy — then layer timeouts and error translation onto the backing client.

```java
@HttpExchange(url = "/customers", accept = "application/json")
public interface CustomerApi {
    @GetExchange("/{id}")
    Customer byId(@PathVariable long id);

    @PostExchange
    Customer create(@RequestBody NewCustomer body);
}
```

```java
@Configuration
class CustomerApiConfig {

    @Bean
    CustomerApi customerApi(RestClient.Builder builder) {
        var factory = ClientHttpRequestFactoryBuilder.detect()
                .build(ClientHttpRequestFactorySettings.defaults()
                        .withConnectTimeout(Duration.ofSeconds(2))
                        .withReadTimeout(Duration.ofSeconds(3)));   // never unbounded (Q67)

        RestClient client = builder
                .baseUrl("https://customers")
                .requestFactory(factory)
                .defaultStatusHandler(HttpStatusCode::is4xxClientError,
                        (req, res) -> { throw new CustomerClientException(res.getStatusCode()); })
                .build();

        return HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(client))
                .build()
                .createClient(CustomerApi.class);
    }
}
```

**Why this over OpenFeign or raw clients:** `@HttpExchange` is *native* to Spring 6 (no extra dependency), reuses the same `HttpMessageConverter` infrastructure as the rest of your app (Q63), and is *backend-agnostic* — swap `RestClientAdapter` for `WebClientAdapter` to go reactive without touching the interface. The `defaultStatusHandler` centralizes error translation so a 404 from the remote becomes a typed `CustomerClientException` your service can catch, rather than a raw `RestClientResponseException` leaking HTTP concerns into business code.

**Adding resilience:** wrap the *interface method calls* with Resilience4j (Q29) at the service layer, or — cleaner — annotate a thin wrapper bean with `@CircuitBreaker`/`@Retry`/`@TimeLimiter`. **Edge cases:** the timeouts must be *tighter than your own SLA* (Q67); only retry idempotent GETs, never the POST `create` without an idempotency key (Q91); and for native images, the dynamic proxy behind `@HttpExchange` needs reachability hints (Q87) — Spring's AOT registers them, but verify in the native build. **Complexity:** O(1) per call; the proxy adds negligible overhead over a hand-written client.

#### Q102. [Coding] Stream live data to the browser with Server-Sent Events (SSE) in Spring MVC and explain backpressure/lifecycle concerns.

**Problem:** Push real-time order-status updates to a dashboard without polling. SSE is the simplest fit (one-way, server→client, auto-reconnect, plain HTTP) — Spring MVC supports it via `SseEmitter`.

```java
@RestController
class OrderEventsController {

    // one emitter per connected client; thread-safe set for fan-out
    private final Set<SseEmitter> emitters = ConcurrentHashMap.newKeySet();

    @GetMapping(path = "/orders/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        SseEmitter emitter = new SseEmitter(Duration.ofMinutes(30).toMillis()); // explicit timeout
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(()    -> emitters.remove(emitter));
        emitter.onError(e       -> emitters.remove(emitter));
        return emitter;                       // request thread is released; connection stays open
    }

    @EventListener
    public void onOrderUpdated(OrderUpdatedEvent ev) {       // push from anywhere in the app
        emitters.forEach(em -> {
            try {
                em.send(SseEmitter.event()
                        .id(ev.id()).name("order-updated").data(ev.payload()));
            } catch (IOException broken) {
                em.complete();                // client gone — clean up
                emitters.remove(em);
            }
        });
    }
}
```

**Why SSE and not WebSocket here:** SSE is one-directional and rides ordinary HTTP/1.1+HTTP/2, so it traverses proxies cleanly, supports automatic browser reconnection with `Last-Event-ID`, and needs no extra protocol — perfect for server-push notifications. WebSocket is the right tool only when you need *bidirectional* low-latency messaging (chat, collaborative editing).

**Lifecycle and threading concerns (the senior part):** the `SseEmitter` *releases the servlet request thread* and uses servlet async, so a long-lived stream doesn't hold a worker thread — but on classic Tomcat each open SSE connection still consumes async resources, so thousands of concurrent streams favor virtual threads (Q25) or WebFlux's `Flux<ServerSentEvent>` (which has built-in backpressure). **Edge cases:** always set a timeout (an orphaned emitter leaks); handle `IOException` on `send` (the client disconnected) by completing and removing the emitter, or you slowly leak dead emitters — the classic SSE bug; and remember the `@EventListener` runs synchronously on the publisher's thread (Q44), so a slow `send` to one client can block the others — fan out on an executor or use the reactive variant if fan-out is large. **Complexity:** O(clients) per event for the broadcast.

#### Q103. [Coding] Add a custom Spring Data repository fragment (custom behavior alongside generated methods) and explain when you'd reach for it.

**Problem:** You need a complex, dynamically-built query (filters that vary at runtime) that derived method names and a static `@Query` can't express, but you still want the convenience of `JpaRepository` for everything else. The answer is the **custom repository fragment** pattern.

```java
// 1) fragment interface — your custom method(s)
interface OrderRepositoryCustom {
    List<Order> search(OrderSearchCriteria c);
}

// 2) implementation — name MUST be <FragmentInterface> + "Impl" by convention
class OrderRepositoryCustomImpl implements OrderRepositoryCustom {
    @PersistenceContext private EntityManager em;

    @Override public List<Order> search(OrderSearchCriteria c) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Order> q = cb.createQuery(Order.class);
        Root<Order> root = q.from(Order.class);
        List<Predicate> where = new ArrayList<>();
        if (c.status() != null)   where.add(cb.equal(root.get("status"), c.status()));
        if (c.minAmount() != null) where.add(cb.ge(root.get("amount"), c.minAmount()));
        if (c.customerId() != null) where.add(cb.equal(root.get("customerId"), c.customerId()));
        q.where(where.toArray(Predicate[]::new));
        return em.createQuery(q).setMaxResults(500).getResultList();   // bound the result!
    }
}

// 3) the repository extends BOTH the Spring Data base and your fragment
public interface OrderRepository
        extends JpaRepository<Order, Long>, OrderRepositoryCustom {
    List<Order> findByStatus(OrderStatus status);   // still get derived/generated methods
}
```

**Why fragments beat the alternatives:** Spring Data composes the final repository proxy from the generated implementation *plus* your `...Impl` fragment, so callers see one cohesive `OrderRepository` — they don't know which methods are generated and which are hand-written. This keeps dynamic-query logic out of the service layer (where people otherwise inject an `EntityManager` and lose the repository abstraction) while preserving CRUD/paging for free. The naming convention (`Impl` suffix, configurable via `repositoryImplementationPostfix`) is how Spring discovers the fragment.

**When to reach for it vs alternatives:** use a derived method (`findByStatus`) for simple fixed queries, `@Query` for fixed JPQL/native SQL, **`Specification`** for composable dynamic predicates that you want to *reuse and combine*, and a **custom fragment** when the query is complex/dynamic *and* you want full `CriteriaBuilder`/`EntityManager` control (multi-step logic, projections, `EntityGraph` tuning, native SQL with mapping). **Edge cases:** always bound the result set (`setMaxResults`) — an unbounded dynamic search is the N+1's ugly cousin (Q13); watch for the same N+1 trap inside the fragment (use a fetch join/`EntityGraph`, Q14); and the `Impl` fragment is not transactional by itself, so the calling service still owns the `@Transactional` boundary.

#### Q104. [Coding] Configure Spring Boot as an OAuth2 resource server validating JWTs, with method-level authorization.

**Problem:** Secure a stateless REST API so every request must carry a valid JWT (issued by an external IdP like Keycloak/Auth0/Cognito), validate the token's signature and claims, and authorize per-endpoint by scope/role. Spring Security's resource-server support does the heavy lifting.

```yaml
spring.security.oauth2.resourceserver.jwt:
  issuer-uri: https://idp.example.com/realms/acme   # Boot fetches JWKS + validates iss/exp/sig
```

```java
@Configuration
@EnableMethodSecurity                       // enables @PreAuthorize / @PostAuthorize
class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http) throws Exception {
        return http
            .securityMatcher("/api/**")
            .authorizeHttpRequests(a -> a
                .requestMatchers(HttpMethod.GET, "/api/public/**").permitAll()
                .anyRequest().authenticated())
            .csrf(csrf -> csrf.disable())                       // stateless API, no cookies
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .oauth2ResourceServer(o -> o.jwt(jwt ->
                jwt.jwtAuthenticationConverter(authoritiesConverter())))
            .build();
    }

    // map the JWT "scope"/"roles" claim to Spring authorities
    private JwtAuthenticationConverter authoritiesConverter() {
        var scopes = new JwtGrantedAuthoritiesConverter();
        scopes.setAuthorityPrefix("SCOPE_");
        var conv = new JwtAuthenticationConverter();
        conv.setJwtGrantedAuthoritiesConverter(scopes);
        return conv;
    }
}

@RestController
class AdminController {
    @PreAuthorize("hasAuthority('SCOPE_orders:admin')")     // method-level check
    @DeleteMapping("/api/orders/{id}")
    void delete(@PathVariable long id) { ... }
}
```

**Why this shape:** with only `issuer-uri`, Boot auto-configures a `JwtDecoder` that downloads the IdP's **JWKS** (public keys) from the discovery document and validates each token's signature, `iss`, and `exp` — you never handle keys or signatures yourself. Stateless + CSRF-disabled is correct *for a token API* (CSRF protects cookie-based sessions, which you don't have); the moment you use cookies you must re-enable it. `@EnableMethodSecurity` turns on `@PreAuthorize`, letting you express fine-grained rules in the domain layer instead of cramming everything into URL matchers.

**Edge cases and trade-offs:** never *log* the token (Q23/Q80) — it's a bearer credential; validate the **audience** (`aud`) claim too (a token valid for another service shouldn't work on yours) via a custom `OAuth2TokenValidator`; the JWKS fetch means a startup/runtime dependency on the IdP — cache keys and handle rotation (Spring does, with a refresh). For **opaque** tokens use introspection (`opaque-token` config) instead, trading a network call per request for instant revocation. The senior point: resource-server JWT validation is *local* (fast, no per-request IdP call) but tokens are valid until expiry (revocation is hard) — that latency-vs-revocation trade-off is the core design decision, and short token lifetimes + refresh tokens are the usual compromise.

#### Q105. [Coding] Write an AOP aspect that audits annotated service methods, capturing arguments, result, and timing.

**Problem:** You want a cross-cutting audit log (who called what, with which args, how long it took, success/failure) on selected service methods without scattering logging code. A Spring AOP `@Around` aspect targeting a custom marker annotation is the clean solution.

```java
@Target(METHOD) @Retention(RUNTIME)
public @interface Audited { String action(); }

@Aspect
@Component
public class AuditAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditAspect.class);

    @Around("@annotation(audited)")           // bind the annotation instance directly
    public Object audit(ProceedingJoinPoint pjp, Audited audited) throws Throwable {
        long start = System.nanoTime();
        String user = Optional.ofNullable(SecurityContextHolder.getContext().getAuthentication())
                              .map(Authentication::getName).orElse("anonymous");
        try {
            Object result = pjp.proceed();    // run the real method
            log.info("audit action={} user={} args={} outcome=OK ms={}",
                     audited.action(), user, Arrays.toString(pjp.getArgs()),
                     (System.nanoTime() - start) / 1_000_000);
            return result;
        } catch (Throwable ex) {
            log.warn("audit action={} user={} outcome=FAIL error={} ms={}",
                     audited.action(), user, ex.getClass().getSimpleName(),
                     (System.nanoTime() - start) / 1_000_000);
            throw ex;                         // never swallow — rethrow so behavior is unchanged
        }
    }
}
```

```java
@Service
class TransferService {
    @Audited(action = "MONEY_TRANSFER")
    void transfer(long from, long to, BigDecimal amount) { ... }
}
```

**Why AOP here:** auditing is a textbook *cross-cutting concern* — it applies uniformly across many methods and is orthogonal to business logic, so weaving it with an aspect keeps the service methods clean and the audit policy in one place. Binding `@annotation(audited)` gives the aspect the annotation *instance* so it can read `action()`. The aspect must **rethrow** on failure so it's transparent — an aspect that alters control flow or swallows exceptions is a maintenance nightmare.

**The proxy gotchas (same family as Q21/Q40/Q43):** AOP advice only fires through the Spring proxy, so (1) **self-invocation** bypasses it — calling an `@Audited` method from another method in the same bean produces no audit; (2) `private`/`final` methods aren't advised; (3) the target bean must be a Spring-managed bean. **Security/PII edge case:** logging `pjp.getArgs()` can leak secrets/PII (passwords, card numbers) — in real systems you mask or whitelist which args are recorded, exactly the concern from Q77. **Ordering:** if combined with `@Transactional`, use `@Order` to control whether the audit sees the committed or in-flight state — typically you want audit *inside* the transaction so a rollback also "undoes" the implied effect, but a durable audit trail often belongs in the outbox (Q83) so it survives rollback decisions deliberately.

#### Q106. [Coding] Implement and unit-test a global `@RestControllerAdvice` that also enriches responses via `ResponseBodyAdvice`.

**Problem:** Beyond mapping exceptions (Q12), you want every successful API response wrapped in a consistent envelope (e.g. adding a `traceId` and `serverTime`) without each controller doing it. `ResponseBodyAdvice` lets you intercept the body *after* the controller returns but *before* serialization.

```java
@RestControllerAdvice
public class ApiResponseAdvice implements ResponseBodyAdvice<Object> {

    @Override public boolean supports(MethodParameter returnType,
                                      Class<? extends HttpMessageConverter<?>> conv) {
        return MappingJackson2HttpMessageConverter.class.isAssignableFrom(conv); // JSON only
    }

    @Override public Object beforeBodyWrite(Object body, MethodParameter rt,
            MediaType mt, Class<? extends HttpMessageConverter<?>> conv,
            ServerHttpRequest req, ServerHttpResponse res) {
        if (body instanceof ProblemDetail) return body;   // don't wrap error bodies
        String traceId = MDC.get("traceId");
        return new ApiEnvelope<>(body, traceId, Instant.now());
    }

    record ApiEnvelope<T>(T data, String traceId, Instant serverTime) {}
}
```

```java
@WebMvcTest(controllers = GreetingController.class)
@Import(ApiResponseAdvice.class)               // advice isn't a controller, so import it explicitly
class ApiResponseAdviceTest {

    @Autowired MockMvc mvc;
    @MockitoBean GreetingService service;

    @Test
    void wrapsSuccessfulBodyInEnvelope() throws Exception {
        given(service.greet("Ada")).willReturn("Hello, Ada");
        mvc.perform(get("/greet").param("name", "Ada"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data").value("Hello, Ada"))   // wrapped under "data"
           .andExpect(jsonPath("$.serverTime").exists());
    }
}
```

**Why `ResponseBodyAdvice` and not a filter or interceptor:** a servlet `Filter` (Q38) sees only raw bytes — by the time it runs, the body is already serialized, so wrapping it would mean re-parsing JSON. `ResponseBodyAdvice` runs in the MVC pipeline with the *typed* return value, so you wrap the object and let Jackson serialize the envelope once. Excluding `ProblemDetail` keeps error responses RFC-7807-compliant (Q12) rather than double-wrapping them.

**Testing nuance (the point of the question):** `@WebMvcTest` loads only the web slice and the *named* controller — it does **not** auto-pick-up `@RestControllerAdvice` beans unless they're component-scanned within the slice, so you `@Import` the advice explicitly (a common "why doesn't my advice fire in the slice test" gotcha, related to Q20/Q62). Mock the service with `@MockitoBean` (Boot 3.4+, replacing `@MockBean`). **Edge cases:** envelope-wrapping changes your API contract — clients must expect `data`; some teams reject this pattern precisely because it breaks REST/HTTP idioms (status codes already convey success), so it's a design *trade-off*, not a default. **Complexity:** O(1) wrapping; verify it doesn't break streaming/`ResponseEntity<Resource>` responses (guard in `supports`).

### 🟠 Advanced — extended

#### Q107. [Coding] Write a complete custom auto-configuration with conditions, properties, IDE metadata, and a backing test.

**Problem:** Build a reusable internal starter that auto-configures a `RateLimiterClient` only when the right class is present and the feature is enabled, lets teams override it, and shows up with IDE autocompletion. This goes deeper than Q24 — it's the *full* production-grade package.

```java
// 1) typed, validated configuration properties
@ConfigurationProperties(prefix = "acme.ratelimit")
@Validated
public record RateLimitProperties(
        boolean enabled,
        @Positive int permitsPerSecond,
        @NotNull Duration timeout) {
    public RateLimitProperties {
        if (permitsPerSecond == 0) permitsPerSecond = 100;   // record default-via-compact-ctor
        if (timeout == null) timeout = Duration.ofMillis(250);
    }
}

// 2) the auto-configuration, guarded by conditions
@AutoConfiguration
@ConditionalOnClass(RateLimiterClient.class)
@ConditionalOnProperty(prefix = "acme.ratelimit", name = "enabled", havingValue = "true",
                       matchIfMissing = true)
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean                    // app can supply its own -> we back off (Q8)
    RateLimiterClient acmeRateLimiter(RateLimitProperties props) {
        return new RateLimiterClient(props.permitsPerSecond(), props.timeout());
    }
}
```

```
# 3) src/main/resources/META-INF/spring/
#    org.springframework.boot.autoconfigure.AutoConfiguration.imports
com.acme.ratelimit.RateLimitAutoConfiguration
```

```json
// 4) src/main/resources/META-INF/additional-spring-configuration-metadata.json
{ "properties": [
  { "name": "acme.ratelimit.permits-per-second",
    "type": "java.lang.Integer", "defaultValue": 100,
    "description": "Sustained request permits granted per second." } ] }
```

```java
// 5) test the conditions with ApplicationContextRunner (no full app boot needed)
class RateLimitAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(RateLimitAutoConfiguration.class));

    @Test void registersByDefault() {
        runner.run(ctx -> assertThat(ctx).hasSingleBean(RateLimiterClient.class));
    }
    @Test void backsOffWhenDisabled() {
        runner.withPropertyValues("acme.ratelimit.enabled=false")
              .run(ctx -> assertThat(ctx).doesNotHaveBean(RateLimiterClient.class));
    }
    @Test void userBeanWins() {
        runner.withUserConfiguration(CustomConfig.class)
              .run(ctx -> assertThat(ctx).getBean(RateLimiterClient.class)
                                         .isSameAs(ctx.getBean("custom")));
    }
}
```

**Why each piece matters:** the `AutoConfiguration.imports` file (not `spring.factories`, which is deprecated for this in Boot 3, Q7) is what makes the class a *candidate*; `@ConditionalOnClass` ensures it only activates when the optional dependency is present (so the starter is safe to depend on without forcing the class); `@ConditionalOnMissingBean` is the override contract (Q8); `additional-spring-configuration-metadata.json` powers IDE autocomplete and inline docs for your properties (the difference between a starter people *enjoy* using and one they fight). The compact record constructor supplies defaults idiomatically.

**Why `ApplicationContextRunner` is the right test tool:** it spins up a *minimal* context with *exactly* your auto-config and lets you assert conditional behavior (present/absent/overridden) per scenario in milliseconds — far faster and more precise than `@SpringBootTest`, and it's how Spring Boot tests its own auto-configs. **Edge cases:** order with `@AutoConfigureBefore/After` if your beans depend on another auto-config (Q55); for native images add a `RuntimeHintsRegistrar` (Q87); and document the off-switch (`enabled=false`) prominently because auto-config magic that can't be turned off frustrates teams (Q24).

#### Q108. [Coding] Implement read/write splitting with `AbstractRoutingDataSource` *and* make it work correctly across `@Transactional` boundaries.

**Problem:** Route `@Transactional(readOnly = true)` to a replica and writes to the primary (the design was discussed in Q96) — now *implement* it correctly, including the subtle ordering bug where the routing key must be resolved before the connection is bound to the transaction.

```java
enum DbRole { PRIMARY, REPLICA }

public class RoutingDataSource extends AbstractRoutingDataSource {
    @Override protected Object determineCurrentLookupKey() {
        return TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                ? DbRole.REPLICA : DbRole.PRIMARY;
    }
}

@Configuration
class DataSourceConfig {
    @Bean @ConfigurationProperties("app.datasource.primary")
    DataSource primary() { return DataSourceBuilder.create().build(); }

    @Bean @ConfigurationProperties("app.datasource.replica")
    DataSource replica() { return DataSourceBuilder.create().build(); }

    @Bean
    @Primary
    DataSource routingDataSource(DataSource primary, DataSource replica) {
        var routing = new RoutingDataSource();
        routing.setTargetDataSources(Map.of(DbRole.PRIMARY, primary, DbRole.REPLICA, replica));
        routing.setDefaultTargetDataSource(primary);
        return routing;
    }

    // CRITICAL: wrap in LazyConnectionDataSourceProxy so the connection (and thus the
    // routing decision) is deferred until first use — AFTER @Transactional set readOnly.
    @Bean
    DataSource lazyDataSource(@Qualifier("routingDataSource") DataSource routing) {
        return new LazyConnectionDataSourceProxy(routing);
    }
}
```

**The subtle, interview-defining bug:** Spring's transaction infrastructure normally acquires a connection *at transaction start*, **before** `setReadOnly` is propagated to `TransactionSynchronizationManager` — so `determineCurrentLookupKey` sees the default and routes everything to the primary, silently defeating the whole feature. The fix is `LazyConnectionDataSourceProxy`: it hands out a *proxy* connection and only acquires the *real* one on first statement execution — by which point the read-only flag is set, so the routing key is correct. This is the single detail that separates a working implementation from a broken one, and most naive implementations miss it.

**Correctness pitfalls (tying to Q96):** (1) **replication lag** means read-your-own-writes can fail — flows that read right after a write must use a non-readOnly transaction to hit the primary; (2) a `@Transactional(readOnly=true)` method that *writes* will hit the replica and fail (or, with `LazyConnectionDataSourceProxy`, route wrong) — keep readOnly honest; (3) connection-per-datasource pool sizing (Q74) now applies *twice*, so budget pools for primary and replica independently. **Edge case:** for explicit per-query routing (not tied to readOnly), set the key in a `ThreadLocal` via an aspect/interceptor and clear it in `finally` (same lifecycle discipline as Q80). The senior recommendation: push this into a shared starter (Q24/Q107) so every service routes — and gets the `LazyConnectionDataSourceProxy` fix — consistently.

#### Q109. [Coding] Build a Kafka consumer with retry, a dead-letter topic, and manual offset acknowledgment in Spring Kafka.

**Problem:** Consume order events reliably — retry transient failures with backoff, send poison-pill records to a dead-letter topic after exhausting retries (so one bad record can't block the partition, Q88), and commit offsets only after successful processing.

```java
@Configuration
@EnableKafka
class KafkaConsumerConfig {

    @Bean
    DefaultErrorHandler errorHandler(KafkaTemplate<Object, Object> template) {
        // route exhausted records to "<topic>.DLT"
        var recoverer = new DeadLetterPublishingRecoverer(template);
        // 3 retries, exponential backoff 1s -> 2s -> 4s (capped)
        var backoff = new ExponentialBackOffWithMaxRetries(3);
        backoff.setInitialInterval(1000L);
        backoff.setMultiplier(2.0);
        backoff.setMaxInterval(10_000L);
        var handler = new DefaultErrorHandler(recoverer, backoff);
        handler.addNotRetryableExceptions(IllegalArgumentException.class);  // don't retry bad data
        return handler;
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, OrderEvent> kafkaListenerContainerFactory(
            ConsumerFactory<String, OrderEvent> cf, DefaultErrorHandler errorHandler) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, OrderEvent>();
        factory.setConsumerFactory(cf);
        factory.setCommonErrorHandler(errorHandler);
        factory.getContainerProperties()
               .setAckMode(ContainerProperties.AckMode.MANUAL);   // we ack explicitly
        return factory;
    }
}

@Component
class OrderConsumer {
    @KafkaListener(topics = "orders", groupId = "fulfilment")
    public void consume(OrderEvent event, Acknowledgment ack) {
        process(event);          // idempotent! at-least-once means dupes happen (Q83)
        ack.acknowledge();       // commit offset only AFTER success
    }
}
```

**Why this design:** the `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` is the modern Spring Kafka pattern (replacing the old `SeekToCurrentErrorHandler`/`RetryTemplate`). On a failure it retries the record in-place with backoff; once retries are exhausted it publishes to `orders.DLT` and *moves on*, so a single poison-pill never blocks the partition forever (the exact failure mode in Q88). Distinguishing **retryable vs non-retryable** exceptions matters: a malformed record (`IllegalArgumentException`) should go straight to the DLT — retrying it just wastes time and risks tripping `max.poll.interval.ms`.

**Manual ack and idempotency:** `AckMode.MANUAL` + acking only after `process()` succeeds gives **at-least-once** semantics — the offset advances only when the work is done, so a crash mid-processing re-delivers the record. Because re-delivery (and rebalance-driven reprocessing, Q88) *will* happen, the listener **must be idempotent** (dedupe on event id, or use the inbox pattern). **Edge cases:** the backoff happens on the *consumer thread*, so long backoffs eat into `max.poll.interval.ms` — keep them short or use non-blocking retries (`@RetryableTopic`, which retries via separate delay topics instead of blocking the partition); monitor DLT depth and alert on it (a filling DLT means a systematic bug); and ensure the DLT has a consumer/runbook, or you've just moved the problem somewhere quiet. **Complexity:** O(retries) per failing record; successful records O(1).

#### Q110. [Coding] Implement a custom Actuator `HealthIndicator` and a readiness contributor that reflect a real downstream dependency.

**Problem:** Your service can't function without a downstream pricing API; you want `/actuator/health` to report `DOWN` (and Kubernetes readiness to pull the pod from the load balancer, Q73) when that dependency is unreachable — but *without* a flapping dependency causing pod *restarts*.

```java
@Component
class PricingHealthIndicator implements HealthIndicator {

    private final RestClient pricing;
    PricingHealthIndicator(RestClient.Builder b) {
        this.pricing = b.baseUrl("https://pricing").build();
    }

    @Override public Health health() {
        try {
            // cheap, fast probe with its own short timeout (don't hang the health check!)
            pricing.get().uri("/healthz").retrieve().toBodilessEntity();
            return Health.up().withDetail("pricing", "reachable").build();
        } catch (Exception ex) {
            return Health.down(ex).withDetail("pricing", "unreachable").build();
        }
    }
}
```

```yaml
management:
  endpoint.health:
    show-details: when_authorized
    probes.enabled: true
    group.readiness.include: readinessState, pricingHealthIndicator  # downstream in READINESS
    group.liveness.include:  livenessState                            # liveness stays shallow!
```

**Why this mapping is the whole point:** Spring aggregates all `HealthIndicator`s into the overall health and into *groups*. The critical design decision (straight from Q73) is **which group** the downstream indicator joins. Putting `pricing` in the **readiness** group means a downstream outage makes the pod report *not ready* — Kubernetes stops routing traffic to it but does **not** restart it, which is correct: restarting won't fix a downstream problem. Putting it in **liveness** would be the classic catastrophic mistake — a transient downstream blip would trigger a *restart storm* across healthy pods.

**Edge cases that make it production-safe:** (1) the health probe itself **must have a short timeout** (Q67) — a hanging downstream must not hang your `/health` endpoint, or the probe times out and you get false negatives; (2) consider a **circuit breaker / cached result** so you're not hammering the downstream's `/healthz` on every probe interval (probes fire every few seconds across every pod — that's real load); (3) decide whether the dependency is *truly* required — if your service can degrade gracefully (serve cached prices, Q92), the dependency being down should *not* make you unready, so don't over-couple readiness to every downstream. **The senior framing:** health indicators are a *contract about traffic-worthiness*, and the art is mapping each dependency to liveness (internal only), readiness (required-for-traffic), or neither (degradable) — getting that taxonomy right is what prevents both blackholing traffic and restart storms.

#### Q111. [Coding] Write a pagination + dynamic-filter endpoint using Spring Data `Specification` and `Pageable`, returning a stable, bounded result.

**Problem:** Expose `GET /orders?status=PAID&minAmount=50&page=0&size=20&sort=createdAt,desc` — dynamic filtering plus pagination — without N+1 and without unbounded queries. `Specification` (composable predicates) + `Pageable` is the idiomatic JPA answer.

```java
public interface OrderRepository
        extends JpaRepository<Order, Long>, JpaSpecificationExecutor<Order> {}

class OrderSpecs {
    static Specification<Order> hasStatus(OrderStatus s) {
        return (root, q, cb) -> s == null ? null : cb.equal(root.get("status"), s);
    }
    static Specification<Order> minAmount(BigDecimal min) {
        return (root, q, cb) -> min == null ? null : cb.ge(root.get("amount"), min);
    }
}

@GetMapping("/orders")
public Page<OrderDto> search(
        @RequestParam(required = false) OrderStatus status,
        @RequestParam(required = false) BigDecimal minAmount,
        @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
        @ParameterObject Pageable pageable) {

    Specification<Order> spec = Specification
            .where(OrderSpecs.hasStatus(status))
            .and(OrderSpecs.minAmount(minAmount));

    // cap page size to prevent a client requesting size=1_000_000 (DoS)
    Pageable safe = pageable.getPageSize() > 100
            ? PageRequest.of(pageable.getPageNumber(), 100, pageable.getSort())
            : pageable;

    return orderRepo.findAll(spec, safe).map(OrderDto::from);
}
```

**Why `Specification` + `Page`:** specifications are *composable and reusable* — each is a small, testable predicate, and `where(...).and(...)` builds the dynamic WHERE clause cleanly (returning `null` from a spec means "no constraint," so absent params drop out). `Page<T>` runs *two* queries — the content query (with `LIMIT/OFFSET`) and a `count` query for total elements — giving the client total pages. `@PageableDefault` + `@ParameterObject` (springdoc) bind the `page`/`size`/`sort` params automatically and document them in OpenAPI.

**The bounded/stable nuances interviewers probe:** (1) **always cap `size`** — an uncapped `size` lets a client pull the whole table in one request (memory + DB load, a real DoS); (2) **offset pagination degrades** on deep pages (`OFFSET 1000000` scans and discards a million rows) — for large datasets prefer **keyset/seek pagination** (`WHERE createdAt < :last ORDER BY createdAt DESC LIMIT 20`), which is O(page size) regardless of depth; (3) **stable sort** — paginating by a non-unique column (`createdAt`) can skip/duplicate rows across pages when ties exist, so add a tiebreaker (`sort = createdAt,id`); (4) the **count query** can be expensive on huge tables — use `Slice<T>` (no count, just "is there a next page") when you don't need the exact total. **N+1 watch:** if `OrderDto.from` touches a lazy association, you re-introduce N+1 (Q14) — use an `@EntityGraph` on a custom spec-executor method or a fetch-aware projection. **Complexity:** content query O(size) with proper indexes; count query O(matching rows) worst case.

#### Q112. [Coding] Implement the relay/poller side of a transactional outbox that publishes to Kafka with at-least-once delivery and a distributed lock.

**Problem:** Q83 established *why* the outbox pattern is needed and showed the write side; now *implement the relay* — the component that reads unpublished outbox rows and pushes them to Kafka, safely, in a multi-instance fleet (so only one pod publishes), marking rows sent only after the broker acks.

```java
@Component
class OutboxRelay {

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    OutboxRelay(OutboxRepository outbox, KafkaTemplate<String, String> kafka) {
        this.outbox = outbox; this.kafka = kafka;
    }

    @Scheduled(fixedDelay = 1000)                          // poll every second
    @SchedulerLock(name = "outboxRelay",                   // ShedLock: only ONE pod runs this
                   lockAtMostFor = "30s", lockAtLeastFor = "1s")
    @Transactional
    public void publishPending() {
        // claim a bounded batch; SKIP LOCKED lets parallel relays not contend (if you drop ShedLock)
        List<OutboxEvent> batch = outbox.findTop100ByStatusOrderByCreatedAt(Status.PENDING);
        for (OutboxEvent e : batch) {
            try {
                kafka.send(e.getTopic(), e.getAggregateId(), e.getPayload()).get(5, SECONDS);
                e.markSent();                              // only after broker ack
            } catch (Exception ex) {
                e.incrementAttempts();                     // leave PENDING; retried next cycle
                if (e.getAttempts() > 10) e.markFailed();  // park poison events for inspection
            }
        }
        outbox.saveAll(batch);                             // single tx commit of statuses
    }
}
```

**Why a lock and why mark-after-ack:** in a fleet, every replica runs `@Scheduled` (Q54/Q82), so without coordination N pods all publish the same rows → N× duplicate messages. **ShedLock** (`@SchedulerLock`) ensures only the lock-holder runs `publishPending`; `lockAtMostFor` is the *safety valve* so a crashed holder's lock auto-expires and the fleet recovers (Q82). The relay marks a row sent **only after** `kafka.send(...).get()` confirms the broker ack — if the publish fails or the pod crashes first, the row stays `PENDING` and is retried, which is exactly what makes delivery **at-least-once** (never lost). Consumers therefore must dedupe (Q83/Q109).

**Design trade-offs and the better alternative:** polling adds latency (up to the poll interval) and DB load; the lower-latency, lower-load alternative is **Change Data Capture (Debezium)** tailing the DB write-ahead log to stream the outbox table to Kafka with no app-side relay at all — preferred at scale. If you keep the poller, an alternative to ShedLock is `SELECT ... FOR UPDATE SKIP LOCKED` so *multiple* relays can each grab disjoint batches concurrently (higher throughput, no single-runner bottleneck) — a deliberate choice between "one publisher, simple ordering" (ShedLock) and "many publishers, more throughput" (SKIP LOCKED). **Edge cases:** bound the batch (don't load the whole table); preserve **per-aggregate ordering** by keying Kafka on `aggregateId` (Kafka orders within a partition); prune/archive sent rows so the table doesn't grow unbounded; and alert on rising `PENDING`/`FAILED` counts (a stuck relay is silent, Q82). **Complexity:** O(batch) per cycle.

#### Q113. [Coding] Implement a request/response logging filter with correlation IDs that is safe for high throughput and large bodies.

**Problem:** You want structured access logs with a correlation ID per request (for trace correlation, Q66/Q80) and *optionally* the request/response bodies for debugging — without breaking the stream (you can only read a request body once) and without blowing up memory on large payloads.

```java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)              // run early so the ID covers everything
class CorrelationAndLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger("access");

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        String cid = Optional.ofNullable(req.getHeader("X-Correlation-Id"))
                             .orElse(UUID.randomUUID().toString());
        MDC.put("correlationId", cid);                 // flows into every log line + downstream
        res.setHeader("X-Correlation-Id", cid);

        // wrap so the body can be read more than once (cached)
        var wrappedReq = new ContentCachingRequestWrapper(req, 8192);   // cap cached bytes
        var wrappedRes = new ContentCachingResponseWrapper(res);
        long start = System.nanoTime();
        try {
            chain.doFilter(wrappedReq, wrappedRes);
        } finally {
            long ms = (System.nanoTime() - start) / 1_000_000;
            log.info("method={} uri={} status={} ms={} bytesIn={} bytesOut={}",
                     req.getMethod(), req.getRequestURI(), wrappedRes.getStatus(), ms,
                     wrappedReq.getContentLength(), wrappedRes.getContentSize());
            wrappedRes.copyBodyToResponse();           // MUST: flush cached body to the real response
            MDC.remove("correlationId");               // clean up ThreadLocal (Q80)!
        }
    }
}
```

**The two traps this avoids:** (1) **you can only read a servlet request body once** — naively calling `getInputStream()` to log it consumes it, so the controller gets an empty body. `ContentCachingRequestWrapper`/`ResponseWrapper` cache the bytes so both the logger and the framework can read them; you *must* call `copyBodyToResponse()` in a `finally`, or the response body never reaches the client (a notorious "my responses are empty" bug). (2) **MDC is a ThreadLocal** — set it early so every log line and the downstream propagation (Q80) carry the correlation ID, and **clear it in `finally`** or a pooled thread leaks the previous request's ID into the next request (Q80's cross-request leak).

**High-throughput safety:** logging full bodies is expensive and a PII/secret risk (Q77) — so cap the cached size (`8192`), log bodies only at DEBUG or for sampled requests (not every request in prod), mask sensitive fields, and use an async appender (Q66) so logging never blocks request threads. **Why a `Filter` not an interceptor:** the correlation ID must cover *everything* including security and error responses, which happen outside the MVC handler (Q38), so it belongs in a `Filter` ordered first. **Edge cases:** `OncePerRequestFilter` prevents double-execution on async dispatches; for *large* uploads (Q95) never cache the body (stream it); and prefer letting Micrometer Tracing own the trace/span IDs while this filter adds a business correlation ID, rather than reinventing tracing.

### 🔴 Expert — extended

#### Q114. [Coding] Implement a custom `HandlerMethodArgumentResolver` to inject the authenticated tenant into controllers, and test it.

**Problem:** In a multi-tenant API, every controller needs the current tenant (derived from a JWT claim or header). Threading it through every method signature as a `@RequestHeader` is noise; a custom argument resolver lets you inject a typed `@CurrentTenant Tenant tenant` parameter cleanly.

```java
@Target(PARAMETER) @Retention(RUNTIME)
public @interface CurrentTenant {}

@Component
class CurrentTenantArgumentResolver implements HandlerMethodArgumentResolver {

    @Override public boolean supportsParameter(MethodParameter p) {
        return p.hasParameterAnnotation(CurrentTenant.class)
            && Tenant.class.equals(p.getParameterType());
    }

    @Override public Object resolveArgument(MethodParameter p, ModelAndViewContainer mav,
            NativeWebRequest req, WebDataBinderFactory binder) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt) {
            String tenantId = jwt.getToken().getClaimAsString("tenant_id");
            if (tenantId == null) throw new MissingTenantException();
            return new Tenant(tenantId);
        }
        throw new MissingTenantException();          // -> mapped to 403 by global advice (Q12)
    }
}

@Configuration
class WebConfig implements WebMvcConfigurer {
    private final CurrentTenantArgumentResolver resolver;
    WebConfig(CurrentTenantArgumentResolver r) { this.resolver = r; }
    @Override public void addArgumentResolvers(List<HandlerMethodArgumentResolver> rs) {
        rs.add(resolver);                            // register it with MVC
    }
}
```

```java
@GetMapping("/dashboard")
public Dashboard dashboard(@CurrentTenant Tenant tenant) {     // injected, not boilerplate
    return service.dashboardFor(tenant);
}
```

```java
@WebMvcTest(DashboardController.class)
@Import(WebConfig.class)                              // resolver is wired via the config
class DashboardControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean DashboardService service;

    @Test
    void resolvesTenantFromJwt() throws Exception {
        given(service.dashboardFor(new Tenant("acme"))).willReturn(new Dashboard(...));
        mvc.perform(get("/dashboard").with(jwt().jwt(j -> j.claim("tenant_id", "acme"))))
           .andExpect(status().isOk());
    }
}
```

**Why an argument resolver:** it's the framework-blessed way to derive a controller parameter from request context — the same mechanism behind `@PathVariable`, `@RequestBody`, and Spring Security's `@AuthenticationPrincipal`. It centralizes the "extract tenant from JWT" logic so it can't be inconsistently re-implemented, and a *missing* tenant throws once, in one place, mapped to a 403 by the global advice (Q12). This beats a `ThreadLocal`-based "TenantContext" (which has the propagation/cleanup hazards of Q80) because the value is scoped to the method call, not a thread.

**Testing nuance (the expert bit):** `@WebMvcTest` lets you exercise the *real* resolver end-to-end; `spring-security-test`'s `jwt()` request post-processor injects a synthetic authenticated JWT with the claim, so you verify the resolver reads `tenant_id` correctly *through the actual MVC pipeline* — far stronger than unit-testing `resolveArgument` in isolation. **Edge cases:** the resolver runs *after* Security authenticated the request (filter order, Q38/Q52), so the `SecurityContext` is populated; for **tenant isolation** the tenant must also scope data access (a Hibernate filter / `@Where` / row-level security), because injecting the tenant doesn't *enforce* that queries are filtered — forgetting that is a cross-tenant data-leak bug. **Complexity:** O(1) per request.

#### Q115. [Coding] Write a custom `@Conditional` that activates beans only on a specific cloud platform, and explain its evaluation timing constraints.

**Problem:** A bean (say a cloud-metadata client) should only be registered when running on a particular platform — and you want it to compose with `@Configuration`/auto-config cleanly. This goes beyond Q55 by handling the *timing* constraint that trips people up.

```java
// the reusable condition
public class OnCloudPlatformCondition extends SpringBootCondition {
    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext ctx, AnnotatedTypeMetadata md) {
        var attrs = md.getAnnotationAttributes(ConditionalOnCloudPlatform.class.getName());
        CloudPlatform required = (CloudPlatform) attrs.get("value");
        Environment env = ctx.getEnvironment();
        CloudPlatform active = CloudPlatform.getActive(env);   // Boot's own detection
        return (active == required)
            ? ConditionOutcome.match(ConditionMessage.of("platform is " + required))
            : ConditionOutcome.noMatch(ConditionMessage.of("platform is " + active));
    }
}

@Target({ TYPE, METHOD }) @Retention(RUNTIME)
@Conditional(OnCloudPlatformCondition.class)
public @interface ConditionalOnCloudPlatform { CloudPlatform value(); }
```

```java
@Bean
@ConditionalOnCloudPlatform(CloudPlatform.KUBERNETES)
KubernetesMetadataClient k8sMetadata() { return new KubernetesMetadataClient(); }
```

**Why extend `SpringBootCondition` instead of raw `Condition`:** `SpringBootCondition` produces a `ConditionOutcome` *with a human-readable message*, which is what makes your condition show up legibly in the **Condition Evaluation Report** (`--debug`, Q7/Q55) — "did not match: platform is NONE" — turning an opaque "my bean isn't there" mystery into a one-line diagnosis. Reusing Boot's `CloudPlatform.getActive(env)` leverages the framework's own platform detection rather than re-sniffing env vars.

**The timing constraint that separates a deep answer:** conditions are evaluated at two possible phases (`ConfigurationPhase.PARSE_CONFIGURATION` vs `REGISTER_BEAN`). A condition that inspects only the `Environment`/classpath (like this one) can evaluate early; but a condition that depends on *which beans already exist* (`@ConditionalOnBean`, like `OnBeanCondition`) **must** wait until bean definitions are registered, which is exactly why such conditions implement `ConfigurationCondition` and declare `REGISTER_BEAN`, and why auto-configuration is a *deferred* import processed *after* user config (Q55) — so user beans exist before `@ConditionalOnMissingBean` runs. If you write a bean-dependent condition and let it evaluate during parsing, it sees an incomplete bean factory and gives wrong answers. **Edge cases:** for **native images** (Q51/Q87), conditions are evaluated *once at build time* during AOT — so a condition that depends on a *runtime-only* signal (an env var set only in prod) will be frozen to its build-time value, a subtle native-vs-JVM divergence. The discipline: keep conditions side-effect-free, fast, and aware of *when* they run.

#### Q116. [Coding] Implement a non-blocking parallel fan-out aggregator in WebFlux that calls three services concurrently with per-call timeouts and partial-failure tolerance.

**Problem:** Build `GET /profile/{id}` that aggregates a user, their orders, and their recommendations from three independent downstream services *concurrently*, each with its own timeout, where a failure of the *optional* recommendations service degrades gracefully rather than failing the whole response. This is WebFlux's sweet spot (Q10/Q25).

```java
@GetMapping("/profile/{id}")
public Mono<Profile> profile(@PathVariable String id) {

    Mono<User> user = userClient.get().uri("/users/{id}", id)
            .retrieve().bodyToMono(User.class)
            .timeout(Duration.ofMillis(800));                 // required: propagate failure

    Mono<List<Order>> orders = orderClient.get().uri("/orders?user={id}", id)
            .retrieve().bodyToFlux(Order.class).collectList()
            .timeout(Duration.ofMillis(800));                 // required

    Mono<List<Reco>> recos = recoClient.get().uri("/recos/{id}", id)
            .retrieve().bodyToFlux(Reco.class).collectList()
            .timeout(Duration.ofMillis(400))
            .onErrorResume(ex -> Mono.just(List.of()));       // OPTIONAL: degrade to empty

    // Mono.zip subscribes to all three at once -> they run concurrently, not sequentially
    return Mono.zip(user, orders, recos)
            .map(t -> new Profile(t.getT1(), t.getT2(), t.getT3()));
}
```

**Why this is genuinely reactive value:** `Mono.zip` subscribes to all three sources eagerly, so the three HTTP calls execute **concurrently** on the event loop and the total latency is ~max(call latencies), not the sum — and *no thread blocks* during the I/O wait (Q10), so a handful of event-loop threads serve enormous concurrency. Doing this with blocking `RestClient` would either run them sequentially or require an explicit executor; the reactive model expresses concurrent fan-out declaratively.

**Partial-failure design (the senior judgment):** the per-call `.timeout()` ensures one slow downstream can't make the whole request hang (Q67); the difference between *required* and *optional* dependencies is encoded in error handling — `user`/`orders` propagate their failure (the response is meaningless without them, so the whole `Mono` errors → mapped to 5xx/504), while `recos` uses `onErrorResume` to **degrade to an empty list**, so a recommendations outage yields a still-useful profile. This "required vs degradable" distinction is the same taxonomy as health indicators (Q110). **Edge cases:** never put a blocking call in this chain — it pins the event loop and collapses throughput (Q61); add a circuit breaker (Resilience4j reactive operators) so a persistently-down dependency fails fast instead of timing out every call; and watch that `Mono.zip` fails fast on the *first* error of a required source (cancelling the others) — if you need *all* results regardless, use `Mono.zipDelayError`. **Complexity:** latency O(max of the three calls); the trade-off for this elegance is the reactive debugging cost (Q25).

#### Q117. [Coding] Design and implement a typed, validated, refreshable configuration with `@ConfigurationProperties` and Spring Cloud `@RefreshScope`, and explain the refresh hazards.

**Problem:** Centralize feature limits (e.g. `app.limits.max-batch-size`, `app.limits.upload-quota`) as typed, validated config that can be *refreshed at runtime* (from a config server) without a restart — and do it without the classic "refresh corrupted my singleton" bug.

```java
@ConfigurationProperties(prefix = "app.limits")
@Validated
public class LimitProperties {
    @Min(1) @Max(10_000) private int maxBatchSize = 100;
    @NotNull private DataSize uploadQuota = DataSize.ofMegabytes(50);
    // getters/setters (setter binding so values can be re-bound on refresh)
}
```

```java
@Component
@RefreshScope                                  // Spring Cloud: bean is recreated on /actuator/refresh
class BatchProcessor {
    private final LimitProperties limits;
    BatchProcessor(LimitProperties limits) { this.limits = limits; }

    void process(List<Item> items) {
        if (items.size() > limits.getMaxBatchSize())          // reads the CURRENT limit
            throw new BatchTooLargeException(limits.getMaxBatchSize());
        // ...
    }
}
```

```bash
# After updating config in the config server, trigger a refresh:
curl -X POST localhost:8080/actuator/refresh    # re-binds @ConfigurationProperties, recreates @RefreshScope beans
```

**How refresh actually works:** `@ConfigurationProperties` beans are *automatically re-bound* on a refresh event (their fields are repopulated from the updated `Environment`), and `@RefreshScope` beans are *destroyed and lazily recreated* on next access so they pick up new dependencies. The validation (`@Validated`) runs on every (re)bind, so a bad refreshed value (`maxBatchSize = -5`) fails the *refresh* rather than silently poisoning the app — fail-fast extended to runtime config.

**The hazards that make this an expert topic:** (1) **stateful `@RefreshScope` beans lose state** on refresh — the bean is recreated, so any in-memory state (a cache, an open connection, a counter) is discarded; never put `@RefreshScope` on a bean holding important runtime state. (2) **mid-flight inconsistency** — a refresh recreates beans *while requests are in flight*, so request A may see the old limit and request B the new one; if multiple properties must change *atomically* together, a partial refresh can yield an inconsistent combination — bind them into *one* `@ConfigurationProperties` object so they re-bind together. (3) **proxy semantics** — `@RefreshScope` injects a proxy (Q58), with the usual `final`/`equals`/`instanceof` caveats. (4) **what *won't* refresh** — things read only at startup (the `DataSource` pool size, the embedded server port, `@Value` captured into a `final` field) don't change on refresh; only beans that *re-read* the properties see new values. The senior recommendation (echoing Q78): use refresh for *operational knobs* (limits, timeouts, feature toggles), not for changes that require re-wiring infrastructure — those need a rolling restart — and prefer a real feature-flag system for user-facing toggles. Always validate refreshed config and keep refreshable properties grouped for atomicity.

#### Q118. [Behavioral] Tell me about a time you made a significant architectural decision on a Spring Boot platform that you later had to defend, revisit, or reverse. (STAR)

**Situation:** At a previous company we ran ~40 Spring Boot microservices, and an early platform decision — made before my time — had standardized *every* service on Spring WebFlux "for scalability," including services doing straightforward blocking JDBC work. By the time I led the platform team, we were seeing the cost: onboarding took weeks because reactive was hard to learn, production incidents took longer to debug because of unreadable reactive stack traces, and several teams had quietly introduced `.block()` calls on the event loop (Q61) that caused intermittent throughput collapses nobody could explain.

**Task:** I was asked to improve developer velocity and incident MTTR without a risky big-bang rewrite, and I had to decide whether to double down on reactive (invest in training/tooling) or change the standard — a politically charged call, because the original decision had executive sponsorship and "we don't use blocking code here" had become an identity.

**Action:** I started with evidence rather than opinion. I pulled six months of incident data and showed that a disproportionate share of throughput incidents traced to event-loop blocking, and I measured that ~30 of the 40 services were I/O-bound CRUD services with no streaming or backpressure needs — i.e. they got *none* of WebFlux's actual benefits while paying all its complexity cost. When Java 21 virtual threads landed (Boot 3.2), I built a proof of concept: migrated one representative service from WebFlux to MVC + virtual threads (Q25), load-tested it to show equivalent concurrency for blocking I/O, and demonstrated the stack traces were readable and the code was ~40% smaller. I wrote a decision record proposing **MVC + virtual threads as the new default, with WebFlux as a justified exception** for genuinely streaming/reactive services, and — crucially — I did *not* propose rewriting the existing reactive services (sunk cost; rewrite risk), only changing the default for *new* services and offering an opt-in migration for teams that wanted it. I socialized it with the original decision-makers privately first, framing it as "the landscape changed (virtual threads didn't exist when we chose), not "the original call was wrong."

**Result:** The new default was adopted; over the next year new services shipped faster (onboarding dropped from weeks to days for backend hires), and the event-loop-blocking class of incident effectively disappeared for new services. Three existing teams chose to migrate; the rest stayed reactive without issue because they were the genuinely-reactive minority. **The lesson I carry:** architectural decisions have a shelf life — the platform (virtual threads) moved under a choice that was *reasonable when made* — so I now build "revisit triggers" into significant decisions (a date or a condition that prompts re-evaluation), I lead with data over advocacy when reversing a sponsored decision, and I distinguish "change the default going forward" from "rewrite everything," because the second is rarely worth the risk. Defending a decision and being willing to reverse it are the same skill: holding the *goal* (velocity, reliability) fixed while holding the *mechanism* loosely.

#### Q119. [Coding] Implement graceful shutdown ordering with `SmartLifecycle` so consumers stop before the resources they depend on.

**Problem:** Q17 established *why* shutdown ordering matters; now *implement* it. On SIGTERM you must stop a Kafka-style message consumer (stop pulling new work) *before* you close the thread pool and DB connections it uses — otherwise in-flight pulls fail mid-processing. `SmartLifecycle` with explicit phases gives you this ordering.

```java
@Component
class MessageConsumerLifecycle implements SmartLifecycle {

    private final MessagePoller poller;
    private volatile boolean running = false;
    MessageConsumerLifecycle(MessagePoller poller) { this.poller = poller; }

    @Override public int getPhase() { return Integer.MAX_VALUE; }   // start LAST, stop FIRST
    @Override public boolean isRunning() { return running; }

    @Override public void start() { poller.startPolling(); running = true; }

    @Override public void stop() {
        poller.stopPolling();          // stop ACCEPTING new messages first
        poller.drainInFlight(Duration.ofSeconds(20));   // let current ones finish
        running = false;
    }
}

@Component
class WorkerPoolLifecycle implements SmartLifecycle {
    private final ThreadPoolTaskExecutor pool;
    private volatile boolean running = false;
    WorkerPoolLifecycle(ThreadPoolTaskExecutor pool) { this.pool = pool; }

    @Override public int getPhase() { return 0; }       // start earlier, stop LATER than consumer
    @Override public boolean isRunning() { return running; }
    @Override public void start() { running = true; }
    @Override public void stop() {
        pool.setWaitForTasksToCompleteOnShutdown(true);
        pool.setAwaitTerminationSeconds(20);
        pool.shutdown();
        running = false;
    }
}
```

**The phase semantics (the crux):** `SmartLifecycle` beans **start in ascending phase order and stop in descending order** — so a *higher* phase starts last and stops *first*. By giving the consumer `Integer.MAX_VALUE` and the worker pool `0`, the container guarantees: on startup the pool is ready before the consumer begins pulling; on shutdown the **consumer stops first** (no new work enters the system), *then* the worker pool drains and closes. This is precisely the ordering Q17's ASCII diagram demands ("stop Kafka listeners BEFORE closing DB pool"), and getting the phases backwards causes the exact bug — new messages arrive after the pool is gone.

**Integration with Boot graceful shutdown:** this composes with `server.shutdown=graceful` and `spring.lifecycle.timeout-per-shutdown-phase` (Q17) — the web server drains HTTP requests, and your `SmartLifecycle` phases drain the *async/messaging* side in the right order. **Edge cases:** the `stop()` work must complete within `timeout-per-shutdown-phase` or the context close proceeds anyway (and k8s `terminationGracePeriodSeconds` must exceed the total, or SIGKILL truncates the drain, Q17/Q73); `getPhase()` collisions are stopped in undefined relative order, so use distinct phases for things with a real dependency; and make processing **idempotent** because a truncated drain may leave messages half-processed (Q83/Q109). The senior point: shutdown is a *dependency graph stopped in reverse*, and `SmartLifecycle` phases are how you encode that graph explicitly rather than hoping bean-destruction order happens to be right.

#### Q120. [Coding] Write a slice + integration test strategy for the global exception handler, verifying both the status and the RFC-7807 body.

**Problem:** The `@RestControllerAdvice` from Q12/Q106 is critical infrastructure — a regression that turns a 404 into a 500 (or leaks a stack trace) is a real incident. Yet exception handlers are frequently *untested* because people forget the advice doesn't fire in a bare unit test. Write tests that actually exercise it.

```java
@WebMvcTest(OrderController.class)
@Import(GlobalExceptionHandler.class)            // advice must be imported into the slice (Q106)
class GlobalExceptionHandlerTest {

    @Autowired MockMvc mvc;
    @MockitoBean OrderService service;

    @Test
    void notFoundProducesProblemDetail() throws Exception {
        given(service.find(99L)).willThrow(new OrderNotFound(99L));

        mvc.perform(get("/api/orders/99").accept(MediaType.APPLICATION_JSON))
           .andExpect(status().isNotFound())
           .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
           .andExpect(jsonPath("$.title").value("Order not found"))
           .andExpect(jsonPath("$.status").value(404))
           .andExpect(jsonPath("$.orderId").value(99))         // custom extension property
           .andExpect(jsonPath("$.detail").value(not(containsStringIgnoringCase("select"))));
    }

    @Test
    void validationErrorProduces400WithFieldErrors() throws Exception {
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"customerId": null, "amount": -5}"""))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.errors.customerId").exists())
           .andExpect(jsonPath("$.errors.amount").exists());
    }
}
```

```java
// And one FULL-context test to prove the wired-together pipeline (advice + security + converters)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class OrderErrorContractIT {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
    @Autowired TestRestTemplate rest;

    @Test void missingOrderReturnsProblemJsonOverTheWire() {
        var res = rest.getForEntity("/api/orders/123456", ProblemDetail.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getBody().getTitle()).isEqualTo("Order not found");
    }
}
```

**Why both levels:** the `@WebMvcTest` slice (Q20) is fast and tests the advice in the *real MVC pipeline* — but it only fires if you `@Import` the advice (the recurring gotcha, Q106). It lets you assert the exact status, the `application/problem+json` content type, custom extension fields, and — crucially — that the `detail` **does not leak SQL or stack traces** (a security regression test, tying to Q12/Q77). The full `@SpringBootTest` integration test then proves the *whole* wired pipeline (advice + security filters + message converters + real DB) produces the right contract over an actual HTTP socket, catching integration issues a slice can't (e.g. a security filter swallowing the response, or a converter misconfiguration).

**The testing-philosophy point:** error responses are part of your API *contract*, so they deserve the same test rigor as success paths — yet they're disproportionately under-tested because the advice indirection makes them feel "framework-y." Asserting the *negative* (no SQL in the body) turns a security property into an executable, regression-proof guarantee. **Edge cases:** test the framework exceptions too (`405 Method Not Allowed`, `415`, `406`, Q85) if you customize them via `spring.mvc.problemdetails.enabled=true`; and keep the contract test in CI as the gate, so "a refactor changed our 404 to a 500" fails the build, not production.

#### Q121. [Coding] Implement a non-trivial `@Cacheable` setup with conditional caching, a custom key generator, and explicit eviction, then explain the consistency model.

**Problem:** Cache product lookups, but only cache *active* products (skip caching `null`/inactive results), key on a composite of `sku + locale`, give entries a real TTL, and evict precisely on update — across a fleet where a local cache won't propagate evictions (Q39).

```java
@Configuration
@EnableCaching
class CacheConfig {
    @Bean
    public CacheManager cacheManager() {
        var caffeine = Caffeine.newBuilder()
                .maximumSize(50_000)
                .expireAfterWrite(Duration.ofMinutes(15))     // TTL lives on the PROVIDER (Q39)
                .recordStats();
        var mgr = new CaffeineCacheManager("products");
        mgr.setCaffeine(caffeine);
        return mgr;
    }

    @Bean("skuLocaleKeyGen")
    public KeyGenerator skuLocaleKeyGen() {
        return (target, method, params) -> params[0] + "::" + params[1];  // sku::locale
    }
}

@Service
class ProductService {

    @Cacheable(cacheNames = "products", keyGenerator = "skuLocaleKeyGen",
               unless = "#result == null || !#result.active()")     // don't cache misses/inactive
    public Product lookup(String sku, Locale locale) { return repo.fetch(sku, locale); }

    @CachePut(cacheNames = "products", keyGenerator = "skuLocaleKeyGen")
    public Product update(String sku, Locale locale, ProductUpdate u) {
        return repo.update(sku, locale, u);     // refresh the cache with the new value
    }

    @CacheEvict(cacheNames = "products", key = "#sku + '::' + #locale")
    public void deactivate(String sku, Locale locale) { repo.deactivate(sku, locale); }
}
```

**Why each annotation choice:** `unless` is evaluated *after* the method returns (so it can inspect `#result`), which is the correct hook to *not cache* a null or inactive product — caching a miss pollutes the cache and serves stale "not found"s. `@CachePut` on `update` writes the fresh value into the cache *and* runs the method (unlike `@Cacheable`, which short-circuits), keeping the cache coherent with the write. `@CacheEvict` removes the entry on deactivation. The custom `KeyGenerator` makes the composite key explicit — relying on the default all-args `SimpleKey` (Q39) is fragile and collision-prone for multi-arg methods.

**The consistency model (the senior depth):** this is a **local, per-instance** Caffeine cache, so in a fleet `@CacheEvict` on pod A does **not** evict pod B's copy — pod B serves the stale value until its TTL expires. That's an *eventual consistency* window bounded by `expireAfterWrite` (15 min here). Whether that's acceptable is a *business* decision: for product descriptions, 15-min staleness is fine; for prices or inventory it may not be. The fixes when you need fleet-wide coherence: (1) a **shared distributed cache** (Redis) so eviction is global; (2) a **cache-invalidation message** (publish "product X changed" to Kafka, every pod evicts locally) — eventual but fast; (3) shorter TTLs trading hit-rate for freshness. **Other edge cases:** `@Cacheable` self-invocation doesn't cache (proxy, Q39); guard against the **stampede** on TTL expiry (Q92) with `refreshAfterWrite` or a loading cache; and `recordStats()` + the `cache.gets`/`cache.evictions` Micrometer metrics let you *measure* hit rate (a cache with a 5% hit rate is just overhead). The principle: `@Cacheable` gives you the *mechanism*, but TTL, stampede protection, and cross-node consistency are *your* design decisions on top of it.

#### Q122. [Coding] Configure virtual-thread executors correctly for `@Async` and scheduled work in Boot 3.2+, and guard against the pitfalls.

**Problem:** You enabled `spring.threads.virtual.enabled=true` (Q25) for request handling, but you also have `@Async` background work and scheduled jobs — wire those to virtual threads too, while avoiding the pinning and unbounded-fan-out traps (Q84) that make virtual threads backfire.

```yaml
spring.threads.virtual.enabled: true     # Tomcat request threads + default executors -> virtual
```

```java
@Configuration
@EnableAsync
class AsyncConfig implements AsyncConfigurer {

    // @Async work on virtual threads: each task gets its own cheap VT
    @Override public Executor getAsyncExecutor() {
        return new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor());
    }

    // exceptions in void @Async methods otherwise vanish (Q43)
    @Override public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) ->
            LoggerFactory.getLogger(method.getDeclaringClass())
                         .error("async failure in {} args={}", method, Arrays.toString(params), ex);
    }

    // For genuinely blocking work that PINS (synchronized/native, Q84), keep a bounded
    // PLATFORM-thread pool so it can't starve virtual-thread carriers.
    @Bean("pinnedSafeExecutor")
    ThreadPoolTaskExecutor pinnedSafeExecutor() {
        var ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(8);
        ex.setMaxPoolSize(16);
        ex.setQueueCapacity(100);
        ex.setThreadNamePrefix("pinned-");
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        return ex;
    }
}
```

**Why a per-task virtual executor for `@Async`:** virtual threads are cheap to create (millions are fine), so `newVirtualThreadPerTaskExecutor()` — *not* a bounded pool — is the idiomatic backing for `@Async`: every task gets its own virtual thread that unmounts during I/O, giving huge concurrency for I/O-bound background work without a pool to size or exhaust. The `AsyncUncaughtExceptionHandler` is mandatory hygiene (Q43) — without it, exceptions from `void` async methods disappear silently.

**The guardrails (the point of the question):** (1) **Pinning** — if a task blocks while holding a `synchronized` lock or in a native call (Q84), it pins its carrier; for *known* pinning-prone work, route it to the bounded **platform-thread** `pinnedSafeExecutor` (via `@Async("pinnedSafeExecutor")`) so it can't starve the small carrier pool. (2) **Unbounded fan-out hitting a bounded downstream** — virtual threads make it trivial to launch thousands of concurrent calls, which can saturate a 10-connection DB pool (Q74/Q84); bound concurrency to the downstream with a `Semaphore`/bulkhead, *not* by making the executor smaller. (3) **Context propagation** — virtual threads don't automatically carry MDC/SecurityContext across the `@Async` boundary any more than platform threads do (Q80), so a `TaskDecorator` / Micrometer context-propagation is still required. (4) For **scheduling**, the single-threaded default scheduler (Q54/Q82) is still a trap — virtual threads don't fix a *one-thread* scheduler; set `spring.task.scheduling.simple.concurrency-limit` or a virtual-thread-backed `TaskScheduler`. The senior framing: virtual threads change the *default unit* (cheap, per-task) but the *bottleneck* moves to whatever is bounded next (carriers under pinning, downstream pools) — so "turn it on" must be paired with "find and protect the next bottleneck."

#### Q123. [Coding] Implement consumer-driven contract testing for a Spring Boot producer with Spring Cloud Contract, and explain why it prevents integration breakage.

**Problem:** Your `orders` service publishes an API consumed by three other teams. A field rename or status-code change can silently break them, and you only find out in a shared integration environment (or production). Consumer-driven contracts catch this *at the producer's build time*.

```groovy
// producer side: src/test/resources/contracts/shouldReturnOrder.groovy
Contract.make {
    description "should return an order by id"
    request {
        method GET()
        url "/api/orders/42"
    }
    response {
        status OK()
        headers { contentType(applicationJson()) }
        body([
            id: 42,
            status: "PAID",
            amount: 19.99
        ])
        bodyMatchers {
            jsonPath('$.id', byEquality())
            jsonPath('$.status', byRegex('PAID|PENDING|CANCELLED'))
            jsonPath('$.amount', byType())
        }
    }
}
```

```java
// Spring Cloud Contract GENERATES a test from each contract; you supply the base class:
abstract class ContractBaseTest {
    @BeforeEach void setup() {
        OrderController controller = new OrderController(mockServiceReturning(
            new Order(42, OrderStatus.PAID, new BigDecimal("19.99"))));
        RestAssuredMockMvc.standaloneSetup(controller);
    }
}
```

**How it works and why it's powerful:** the producer writes (or accepts from consumers) a **contract** describing request/response pairs. Spring Cloud Contract's plugin *generates a test* from each contract that runs against the *real* controller during the producer's build — so if a developer renames `status` to `state` or changes the JSON shape, the generated test **fails the producer's build**, immediately and locally. Simultaneously, the plugin produces a **stub jar** (a WireMock stub) that *consumers* download and test against, so consumers verify their code against the exact same contract *without* needing the live producer. The contract is the single source of truth both sides bind to.

**Why this beats the alternatives:** a shared end-to-end integration environment finds breakage *late* (after deploy, often flakily) and couples release schedules; pure producer-side tests don't capture what consumers actually *depend on* (you might "safely" remove a field three consumers rely on). Consumer-driven contracts shift the failure **left to build time** and encode the dependency explicitly — they're the guard the zero-downtime story (Q86) calls for against breaking API/event contracts during rolling deploys. **Edge cases and trade-offs:** contracts add process overhead and must be *maintained* alongside the API (a stale contract gives false confidence); they verify *structure/shape*, not full semantics; and for **messaging** (Kafka), the same tool verifies message contracts (the producer emits a message matching the contract; consumers test against the stubbed message). The discipline: treat the contract as a *negotiated interface*, version it with the API, and make the generated tests a required CI gate — that's what turns "we think we're backward-compatible" into "the build proves it."

#### Q124. [Coding] Write a Testcontainers-based test that asserts the absence of N+1 queries by counting SQL statements.

**Problem:** Q14 recommended "an integration test asserting the *number of SQL statements* so an N+1 regression fails CI." Now *implement* it — a test that fails the build if a repository method that should fetch in one query secretly fans out into N+1.

```java
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Testcontainers
@Import(QueryCountConfig.class)               // wire datasource-proxy as a query counter
class OrderFetchNPlusOneTest {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired OrderRepository repo;

    @Test
    void findWithItems_runsExactlyOneQuery_noNPlusOne() {
        seedOrdersEachWithLineItems(5);          // 5 orders, each with several line items
        SQLStatementCountValidator.reset();

        List<Order> orders = repo.findAllWithLineItems();   // uses @EntityGraph / join fetch
        orders.forEach(o -> o.getLineItems().size());       // touch the lazy collection

        // The whole point: ONE select, not 1 + 5. A regression to lazy loading -> 6 -> test fails.
        SQLStatementCountValidator.assertSelectCount(1);
    }
}
```

```java
@TestConfiguration
class QueryCountConfig {
    // Wrap the real DataSource with datasource-proxy so every SQL statement is counted.
    @Bean
    static BeanPostProcessor dataSourceCountingProxy() {
        return new BeanPostProcessor() {
            @Override public Object postProcessAfterInitialization(Object bean, String name) {
                if (bean instanceof DataSource ds && !(bean instanceof ProxyDataSource)) {
                    return ProxyDataSourceBuilder.create(ds)
                            .name("counting").countQuery().build();
                }
                return bean;
            }
        };
    }
}
```

**Why count SQL statements:** N+1 is *invisible* in functional tests — the code returns the right data, just with 1+N queries instead of 1, so correctness assertions pass while performance silently rots. The only way to catch a regression mechanically is to assert the *statement count*. `datasource-proxy`'s `SQLStatementCountValidator` (or Hibernate's `Statistics`, or `QueryCountHolder`) intercepts the JDBC layer and counts selects/inserts/updates, so `assertSelectCount(1)` turns "no N+1" into an executable contract. The test must run against a **real database** (Testcontainers, Q19), because H2 can mask dialect/fetch behavior; `@DataJpaTest` + `replace = Replace.NONE` keeps the slice fast while using the real Postgres.

**Why this is high-value:** it converts a performance property into a build gate — if someone later removes the `@EntityGraph` from `findAllWithLineItems`, the lazy collection access fans out to 6 queries and the test *fails in CI* (Q14), not in a production latency incident (Q79). It also documents *intent*: the assertion declares "this method is meant to fetch in one query." **Edge cases:** reset the counter *after* seeding (seeding does its own inserts); be aware that `count` queries (`Page`, Q111), second-level cache hits, and batch fetching change the expected number — so the assertion encodes a *specific* fetch strategy and must be updated deliberately if the strategy changes; and for collection fetch joins remember the Cartesian-product/`distinct` caveat (Q14). The senior framing: this is the testing complement to understanding JPA-as-a-leaky-abstraction (Q13) — you don't just *know* about N+1, you *prevent its return* with an automated guard.

#### Q125. [Coding] Implement a custom `ObjectMapper` customization (module + serializer) without replacing Boot's auto-configured mapper, and explain why that distinction matters.

**Problem:** You need custom JSON behavior — say serialize all `Money` values as a string `"USD 19.99"` and register a module for a legacy date format — but you must *not* lose Spring Boot's carefully-chosen defaults (ISO-8601 dates, JSR-310 support, property inclusion). Q53 warned that defining your own `ObjectMapper` bean discards Boot's defaults; do it the *additive* way.

```java
@JsonComponent                                   // Boot auto-registers @JsonComponent serializers
public class MoneyJsonComponent {

    public static class Serializer extends JsonSerializer<Money> {
        @Override public void serialize(Money m, JsonGenerator gen, SerializerProvider sp)
                throws IOException {
            gen.writeString(m.currency() + " " + m.amount());   // "USD 19.99"
        }
    }
    public static class Deserializer extends JsonDeserializer<Money> {
        @Override public Money deserialize(JsonParser p, DeserializationContext ctx)
                throws IOException {
            String[] parts = p.getValueAsString().split(" ");
            return new Money(parts[0], new BigDecimal(parts[1]));
        }
    }
}

@Configuration
class JacksonConfig {
    // ADD to the auto-configured builder; do NOT define your own ObjectMapper bean.
    @Bean
    Jackson2ObjectMapperBuilderCustomizer jsonCustomizer() {
        return builder -> builder
                .modulesToInstall(new LegacyDateModule())
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .serializationInclusion(JsonInclude.Include.NON_NULL);
    }
}
```

**Why additive customization, not replacement (the core point):** Spring Boot auto-configures one shared `ObjectMapper` (Q53) with non-obvious-but-important defaults — `WRITE_DATES_AS_TIMESTAMPS` disabled (so `java.time` serializes as ISO-8601), JSR-310 module registered, and `spring.jackson.*` properties applied. If you declare `@Bean ObjectMapper`, you **replace** it and inherit *none* of that, which is exactly how "dates suddenly serialize as epoch millis in prod" bugs happen. The two safe extension points are `@JsonComponent` (Boot scans for it and registers your serializers/deserializers into the existing mapper) and `Jackson2ObjectMapperBuilderCustomizer` (you receive Boot's *pre-configured* builder and *add* to it), both of which preserve the defaults and layer your changes on top.

**Why it matters beyond the web layer:** that single `ObjectMapper` is shared by controllers, the HTTP clients (`RestClient`/`WebClient`, Q63), and anywhere you inject it — so customizing it the additive way changes serialization *consistently* app-wide, whereas a rogue replacement mapper desynchronizes (e.g. your web layer and your Kafka serializer now disagree on date format). **Edge cases:** order matters if two customizers touch the same setting (use `@Order`); a custom `Money` serializer must round-trip (serializer + matching deserializer), or you can write JSON you can't read back; for **native images** (Q51/Q87) reflective Jackson access on your DTOs needs reachability metadata — `@RegisterReflectionForBinding` or Spring AOT covers framework-managed types but verify custom ones in the native build. The senior framing: prefer the *least intrusive* customization that achieves the goal (properties → customizer/`@JsonComponent` → full replacement), because each step up the intrusiveness ladder discards more framework behavior you'd then have to re-implement and keep in sync.

#### Q126. [Behavioral] Describe a time you diagnosed a hard, intermittent production issue in a Spring Boot service under pressure. How did you approach it and what did you change afterward? (STAR)

**Situation:** A payments service I owned started returning intermittent 504s during the morning market-open spike — maybe 0.5% of requests, only for ~20 minutes, only on the busiest day of the week. p50 latency was normal, error rate was *almost* normal, so dashboards looked "mostly fine," and it had survived two incident reviews without a root cause because by the time anyone looked, the spike was over and everything was green again (the classic p99-only signature, Q79).

**Task:** As the service owner I was asked to find and fix the actual root cause — not just "scale up and hope" — because the 504s were hitting a revenue-critical checkout path and the business had lost trust after two inconclusive postmortems. The hard constraint was that I *couldn't reproduce it on demand*; it only appeared under real market-open load.

**Action:** I refused to keep guessing from aggregate dashboards and instead made the *next* occurrence diagnosable. I added per-dependency client metrics (`http.client.requests`, Q67/Q89) and made sure distributed tracing sampled enough during the spike window to capture slow requests (Q23/Q79), and I pre-positioned capture: a `preStop`-safe way to grab a thread dump and the HikariCP metrics the moment alerts fired. On the next market open, I pulled a trace for a 504 and it localized instantly — the slow span was `getConnection`, not any downstream call. The thread dumps (Q71) confirmed it: dozens of `http-nio` workers parked in `HikariPool.getConnection`, i.e. **connection-pool exhaustion** (Q22), but only under the spike. Digging into *why* hold-time spiked, I found a `@Transactional` checkout method that made a synchronous fraud-scoring HTTP call *while holding a DB connection* (Q22's exact anti-pattern) — fine at low volume, but at market-open concurrency the fraud API slowed slightly, hold-times ballooned, the pool drained, and the *queued* requests timed out as 504s while p50 (requests that got a connection instantly) stayed normal.

**Action (the fix):** I moved the fraud call *out* of the transaction — commit the order, then enrich/score asynchronously via the outbox pattern (Q83) so the connection is released before the slow remote call — and added a `connection-timeout` so excess load *sheds fast* (Q74) instead of piling into 504s, plus a circuit breaker on the fraud API (Q29). I also right-sized the pool from measured hold-time via Little's Law (Q74) rather than just enlarging it.

**Result:** Hold-time dropped ~20x, the next several market opens were clean, and the 504s vanished. Afterward I changed three things institutionally: (1) added an **alert on `hikaricp.connections.pending > 0` sustained** as a leading indicator (Q22), so we'd catch saturation *before* user-facing timeouts; (2) added a **lint/review rule** flagging remote calls inside `@Transactional` methods, because this anti-pattern had bitten us more than once; (3) added **SLO/p99-based alerting** (Q89) instead of average-based, since the whole incident was invisible to mean-latency monitoring. **The lesson:** intermittent prod issues are usually *load-* or *data-dependent* emergent behavior, and the highest-leverage move under pressure is not to theorize but to *make the next occurrence observable* — trace first to localize, then drill with the metric for that layer (Q79) — and then to convert the specific root cause into a *systemic* guard (alert + lint + better SLOs) so the class of bug can't silently return.

#### Q127. [Coding] Implement distributed rate limiting across a fleet using a Redis-backed token bucket, returning correct 429 semantics.

**Problem:** Q81 explained that per-instance limiters drift across replicas and a true *global* limit needs shared state. Implement a fleet-wide rate limiter using Redis (atomic via a Lua script so concurrent pods don't race), enforcing e.g. 1000 requests/minute *per API key* across the whole cluster, with proper `429` + `Retry-After` semantics.

```java
@Component
class RedisRateLimiter {

    private final StringRedisTemplate redis;
    private final RedisScript<List> script;     // returns [allowed, remaining, retryAfterSeconds]

    RedisRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
        // token-bucket Lua: atomic check-and-decrement under concurrency
        this.script = RedisScript.of(new ClassPathResource("rate_limit.lua"), List.class);
    }

    record Decision(boolean allowed, long remaining, long retryAfterSeconds) {}

    Decision check(String apiKey, int limit, int windowSeconds) {
        @SuppressWarnings("unchecked")
        List<Long> r = (List<Long>) redis.execute(script,
                List.of("ratelimit:" + apiKey),
                String.valueOf(limit), String.valueOf(windowSeconds),
                String.valueOf(Instant.now().getEpochSecond()));
        return new Decision(r.get(0) == 1L, r.get(1), r.get(2));
    }
}

@Component
class RateLimitInterceptor implements HandlerInterceptor {
    private final RedisRateLimiter limiter;
    RateLimitInterceptor(RedisRateLimiter limiter) { this.limiter = limiter; }

    @Override public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object h) {
        String key = req.getHeader("X-Api-Key");
        var d = limiter.check(key, 1000, 60);
        res.setHeader("RateLimit-Remaining", String.valueOf(d.remaining()));
        if (!d.allowed()) {
            res.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());   // 429
            res.setHeader("Retry-After", String.valueOf(d.retryAfterSeconds()));
            return false;                                          // short-circuit; don't call handler
        }
        return true;
    }
}
```

**Why Redis + Lua and an interceptor:** the *atomicity* is the crux — "read count, check limit, increment" across N pods has a race window where two pods both see "999 used" and both allow, exceeding the limit. A **Lua script runs atomically on the Redis server**, so the check-and-decrement is a single indivisible operation regardless of how many pods call concurrently — that's what makes the limit *globally* correct, not per-instance (Q81). A `HandlerInterceptor` (Q38) is the right layer because it runs *before* the controller and can short-circuit cheaply, and it has access to the resolved handler if you want per-endpoint limits; for *purely volumetric* protection the edge/gateway is still cheaper (Q81), so this is for *business-tier* per-key quotas the gateway can't express.

**Correct 429 semantics (frequently botched):** return **HTTP 429 Too Many Requests** (not 403/503), include a **`Retry-After`** header so well-behaved clients back off the right amount, and ideally `RateLimit-*` headers so clients can self-throttle *before* hitting the wall — this prevents rejected requests from becoming a thundering herd (pair with client retry-with-jitter, Q67/Q89). **Edge cases and trade-offs:** Redis is now a dependency on the request path, so a Redis outage must **fail open** (allow) or **fail closed** (reject) — a deliberate choice (usually fail-open with a local fallback limiter, so a Redis blip doesn't take down the whole API); the per-key cardinality can bloat Redis keys (set TTLs on the buckets, which the Lua script should do); and the sliding-vs-fixed-window choice matters (a fixed window allows a 2x burst at the boundary — a sliding-window-log or token-bucket smooths it). **Complexity:** O(1) Redis round-trip per request (one network hop added to every call — measure its latency impact). The senior framing: distributed rate limiting trades a network hop and a shared dependency for *correctness across the fleet*, and the design decisions (fail-open vs closed, window algorithm, where in the stack) matter more than the code.

#### Q128. [Practical] Design a multi-module Spring Boot system for a mid-size domain. How do you structure modules, manage shared code, and decide service boundaries?

**Problem (a design exercise):** You're starting a new platform for, say, an order-management domain (orders, inventory, payments, notifications). The interviewer wants to see how you reason about *structure* — modules vs services, shared libraries, and where to draw boundaries — not a single-app tutorial.

**Start with the monolith-vs-microservices judgment, contextually.** For a mid-size domain with a small-to-medium team, I'd *default to a well-structured modular monolith* (a "modulith") rather than premature microservices, because microservices add distributed-systems tax (network failure, eventual consistency, distributed tracing, deployment complexity, the outbox pattern of Q83) that you should pay *only* when you have a concrete reason — independent scaling, independent deployment cadence, team autonomy at scale, or genuinely independent failure domains. Spring Boot supports this directly: **Spring Modulith** lets you enforce module boundaries *within one deployable* (modules communicate via published events or explicit APIs, and a test fails the build if a module reaches into another's internals), giving you many of the boundary benefits *without* the network. You can extract a module into its own service later precisely because the boundary was already enforced.

```
order-platform/                         (Gradle/Maven multi-module)
├── platform-bom/            ← internal BOM pinning shared versions (Q6)
├── platform-starters/       ← acme-*-spring-boot-starter (logging, tracing, security) (Q24/Q107)
│     └── auto-config + @ConfigurationProperties, shared across every module/service
├── shared-kernel/           ← truly shared, stable types ONLY (money, ids, error contracts)
├── modules/  (modulith) OR  services/  (microservices)
│     ├── orders/            ← owns its data; exposes an API + publishes events
│     ├── inventory/
│     ├── payments/
│     └── notifications/
└── contracts/               ← consumer-driven contracts between modules/services (Q123)
```

**Shared-code discipline (where teams get it wrong).** I distinguish three kinds of "shared": (1) **cross-cutting infrastructure** (logging, tracing, security defaults, resilience config) → internal **starters/auto-config** (Q24/Q107) so it's *opt-in via dependency* and centrally upgradeable — this is the right kind of sharing. (2) **a stable shared kernel** of value types (Money, identifiers, error/ProblemDetail contracts) → a small, *rarely-changing* `shared-kernel` library. (3) **domain logic** → **NOT shared** — sharing entities/business logic across module boundaries is the anti-pattern that recreates a distributed monolith (a change in one forces lockstep redeploys of all). An internal **BOM** (Q6) pins versions so every module/service agrees on dependency versions without conflict.

**Drawing boundaries (the core skill).** I draw service/module boundaries around **business capabilities and data ownership**, not technical layers — each module *owns its data* (no shared database tables across boundaries; cross-module reads go through an API or a replicated read model), so the boundary is a real encapsulation, not a shared schema with extra steps. The litmus tests: does this capability change for *different reasons* / on a *different cadence* than its neighbors (independent deployability)? does it have *different scaling characteristics* (payments is low-volume/high-consistency; notifications is high-volume/eventually-consistent)? could a failure here be *isolated* from there? **Communication** across boundaries is async events (the outbox pattern, Q83) for decoupling and resilience, with synchronous calls (`@HttpExchange`, Q101) only where a real-time response is required — and every cross-boundary contract guarded by consumer-driven contract tests (Q123) so refactors can't silently break consumers. **The trade-off framing I'd close with:** start modular-but-together, enforce boundaries from day one (Modulith), and extract to separate services *driven by evidence* (scaling, team, deploy-cadence pressure) — because the boundary is the expensive decision to get wrong, while the deployment topology (monolith vs services) is comparatively cheap to change *if* the boundaries were clean.

#### Q129. [Coding] Implement an inbound idempotency/deduplication mechanism for an at-least-once message consumer (the consumer-side complement to the outbox).

**Problem:** Q83/Q109/Q112 established that outbox/Kafka delivery is *at-least-once*, so consumers *will* occasionally receive duplicates (after a rebalance, a relay retry, or a crash-before-ack). The consumer must process each logical event *exactly once in effect*. Implement the **inbox** (dedup) pattern.

```java
@Component
class OrderEventConsumer {

    private final ProcessedEventRepository processed;   // table with UNIQUE(event_id)
    private final OrderService orders;

    OrderEventConsumer(ProcessedEventRepository processed, OrderService orders) {
        this.processed = processed; this.orders = orders;
    }

    @KafkaListener(topics = "orders", groupId = "fulfilment")
    @Transactional                                       // dedup-insert + business work in ONE tx
    public void handle(OrderEvent event, Acknowledgment ack) {
        try {
            // Atomic claim: the UNIQUE constraint is the real dedup guard (Q91), not a check-then-act.
            processed.save(new ProcessedEvent(event.id(), Instant.now()));
        } catch (DataIntegrityViolationException duplicate) {
            ack.acknowledge();                           // already processed -> ack and skip
            return;
        }
        orders.apply(event);   // the actual side effect, in the SAME transaction as the dedup row
        ack.acknowledge();     // commit (dedup row + business change) then advance offset
    }
}
```

**Why this works and why the UNIQUE constraint is load-bearing:** the correctness hinges on inserting the `event_id` into a `processed_events` table with a **UNIQUE constraint** *in the same transaction* as the business side effect. If a duplicate arrives, the insert fails atomically with `DataIntegrityViolationException` and we skip — there's no check-then-act race (the same atomicity argument as the idempotency-key endpoint, Q91). Because the dedup row and the business write commit together, you can never end up "processed the order but didn't record that we did" (or vice versa) — the two are one atomic unit. The offset is acked only after commit, preserving at-least-once *receipt* while achieving exactly-once *effect*.

**Why not just make every operation naturally idempotent?** Sometimes you can (`UPDATE ... SET status='PAID' WHERE id=?` is naturally idempotent), and that's even better — *prefer* intrinsic idempotency when the operation allows it. But many side effects aren't naturally idempotent (incrementing a counter, sending an email, appending a ledger entry, calling a non-idempotent downstream), and for those the inbox table is the general mechanism. **Edge cases and trade-offs:** (1) the `processed_events` table grows unbounded — prune by TTL/partition (you only need the dedup window, e.g. days, like Q91); (2) if the side effect is in a *different* system than the dedup DB (e.g. send email + record in DB), you reintroduce a dual-write and need the *same* outbox reasoning on the outbound side (Q83) — exactly-once across two systems is fundamentally about pushing the dedup boundary to where a single atomic commit covers both the effect-record and the effect; (3) for very high throughput a Redis `SET NX` with TTL can front the DB dedup as a fast-path (with the DB as the durable backstop). The senior framing: "exactly-once delivery" is a myth in distributed systems — you get *at-least-once delivery + idempotent processing = exactly-once effect*, and the inbox table (or intrinsic idempotency) is how the *consumer* upholds its half of that contract, complementing the producer's outbox (Q83/Q112).

#### Q130. [Practical] Design the testing strategy for a Spring Boot microservice — the full pyramid — and justify what you test at each layer and what you deliberately don't.

**Problem (a design exercise):** An interviewer asks you to lay out the *complete* testing strategy for a production Spring Boot service, justifying the layers, the tools, and — importantly — the *trade-offs* (speed vs fidelity, what to mock, what to not bother testing). This synthesizes the slice/Testcontainers/contract material (Q19/Q20/Q62/Q123/Q124) into a coherent strategy.

**The pyramid, bottom to top, with Spring-specific tools:**

```
        /\        E2E / smoke (very few)      ── real deploy, real downstreams; @SpringBootTest(RANDOM_PORT) or post-deploy probes
       /  \       Contract tests (per boundary)── Spring Cloud Contract (Q123): producer build + consumer stubs
      /    \      Integration (some)           ── @SpringBootTest + Testcontainers (Q19): real DB/broker, full wiring
     /------\     Slice tests (many)           ── @WebMvcTest/@DataJpaTest/@JsonTest (Q20): one layer, mocked collaborators
    /--------\    Unit tests (most)            ── plain JUnit, NO Spring context: domain logic, pure functions
```

**Unit tests (the wide base):** the bulk of tests should be *plain JUnit* with **no Spring context at all** — domain logic, calculations, state machines, validators (the `isValid` logic of Q99) tested by direct instantiation and constructor injection of mocks. They run in milliseconds, so they're where you cover the *combinatorial* edge cases (boundary values, error branches). The justification: most bugs are in *logic*, and logic doesn't need a container — booting Spring to test a pure function is waste (and the context-cache cost of Q62 multiplies it).

**Slice tests (many):** `@WebMvcTest` for controllers (request mapping, validation, serialization, the exception handler of Q120 — with the advice `@Import`ed), `@DataJpaTest` for repositories (derived queries, specs, and the **N+1 statement-count guard** of Q124) against a real DB via Testcontainers, `@JsonTest` for serialization contracts. Slices load a *narrow, fast* context and force you to mock collaborators (`@MockitoBean`), which keeps them quick and focused on one layer. Justification: they catch the framework-integration bugs unit tests can't (a wrong `@RequestMapping`, a broken converter) without the cost of a full context.

**Integration tests (some):** `@SpringBootTest` + **Testcontainers** (Q19) booting the *whole* context against a *real* Postgres/Kafka — the high-fidelity layer that proves the wired-together system works (transactions actually commit, migrations apply, security filters let the right requests through). Use a real DB, **never H2** (it masks dialect/locking/index behavior, Q97). Justification: this is where you test *behavior that only emerges from integration* — transaction boundaries, locking (Q90), the full error pipeline over a real socket (Q120). Keep these *fewer* because they're slow, and **share context configuration** so the context cache (Q62) is reused rather than fragmented.

**Contract tests (per boundary):** **Spring Cloud Contract** (Q123) for every API/event boundary with another team — the producer's build verifies it honors the contract, consumers test against generated stubs. Justification: this is what makes the zero-downtime/rolling-deploy story (Q86) safe, catching breaking changes at *build* time instead of in a shared environment.

**E2E / smoke (very few):** a handful of post-deploy smoke tests against a real environment (does the service start, pass health checks, serve a canary request). Justification: full end-to-end tests are *slow, flaky, and expensive to maintain* (they couple many services' release schedules), so I keep them minimal and rely on contracts + integration tests for confidence, using E2E only to verify *deployment* and *wiring in the real environment*.

**What I deliberately DON'T test (the senior judgment interviewers want):** I don't test the *framework itself* (that Spring injects a bean, that `@GetMapping` maps — Spring tests that); I don't write integration tests for trivial pass-through controllers that have no logic (a slice test suffices); I don't chase 100% line coverage (it incentivizes testing getters and inflates the suite without catching bugs — I target *meaningful* coverage of logic and contracts); and I don't duplicate the same assertion across pyramid layers (if a unit test covers a branch, the integration test shouldn't re-cover it — it should test *integration*). **The unifying trade-off:** every test buys *confidence* at a cost of *speed and maintenance*, and the pyramid shape is the optimization — push coverage *down* to the cheapest layer that can catch a given class of bug, reserve the slow/expensive layers for what *only* they can catch, and ruthlessly avoid redundant or framework-testing tests. A suite that's slow and flaky gets ignored or disabled, which is worse than a smaller suite people trust — the same "trust" argument as alert fatigue (Q89).

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
