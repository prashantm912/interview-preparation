# Spring Framework Core

A deep, interview-focused guide to the Spring Framework core: the IoC container, dependency injection, bean lifecycle, AOP, transactions, events, profiles, and SpEL. Answers emphasize the *why* and the trade-offs that staff-level interviews probe, with version-specific notes through Spring Framework 6.2 / Spring Boot 3.x (2026).

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

### Q1. [Theory] What is Inversion of Control (IoC) and how does Dependency Injection (DI) relate to it?

**IoC** is a design principle where the control over object creation and wiring is inverted from your application code to a container/framework. Instead of an object instantiating its own collaborators (`new PaymentGateway()`), the container constructs and supplies them. **DI is one concrete implementation of IoC**: dependencies are *injected* from the outside rather than looked up. The benefit is decoupling — a class depends on abstractions (interfaces) and is unaware of concrete wiring, which makes it independently testable (you inject mocks) and reconfigurable without code changes. Other IoC techniques exist (service locator, dependency lookup via JNDI), but DI is preferred because dependencies are explicit in the constructor/signature rather than hidden inside method bodies. Spring's `ApplicationContext` is the IoC container that performs this wiring.

### Q2. [Theory] Compare constructor, setter, and field injection. Which should you use and why?

```
Constructor injection            Setter injection             Field injection
---------------------            ----------------             ---------------
@Component                       @Component                   @Component
class A {                        class A {                    class A {
  private final B b;               private B b;                 @Autowired
  A(B b){this.b=b;}                @Autowired                   private B b;
}                                  void setB(B b){this.b=b;}  }
                                 }
```

- **Constructor injection (recommended):** dependencies are `final`, guaranteeing immutability and a fully-initialized object. Mandatory dependencies are enforced at construction — you can never have a half-built bean. It fails fast on missing/circular deps and works without Spring in unit tests (`new A(mockB)`).
- **Setter injection:** good for *optional* or reconfigurable dependencies, and it breaks certain circular-dependency cycles (more below).
- **Field injection:** convenient but discouraged — fields can't be `final`, you can't construct the object in a test without reflection, it hides the number of dependencies (encouraging God classes), and it couples you to the container. IntelliJ and Spring both warn against it.

Since Spring 4.3, if a class has a single constructor, `@Autowired` on it is optional. The staff-level answer: **default to constructor injection**, use setters only for genuinely optional dependencies.

### Q3. [Theory] What is the difference between `ApplicationContext` and `BeanFactory`?

`BeanFactory` is the most basic container interface providing DI and lazy bean instantiation. `ApplicationContext` is a superset that adds enterprise features: internationalization (`MessageSource`), event publishing (`ApplicationEventPublisher`), automatic `BeanPostProcessor`/`BeanFactoryPostProcessor` registration, environment/profile support, and eager singleton pre-instantiation at startup. In practice you almost always use `ApplicationContext` (e.g., `AnnotationConfigApplicationContext`, or in Spring Boot the auto-configured context). `BeanFactory` matters mainly for memory-constrained or lazy scenarios and for understanding the layered design. The eager pre-instantiation of `ApplicationContext` is a feature: configuration errors surface at startup, not at first request.

### Q4. [Theory] What are the default bean scopes in Spring and when would you use each?

- **singleton** (default): one shared instance per container. Use for stateless services, repositories, configuration holders. Beware shared mutable state — it must be thread-safe.
- **prototype**: a new instance every time the bean is requested. Spring does *not* manage the full lifecycle of prototypes (no destruction callback). Use for stateful, short-lived objects.
- Web scopes: **request**, **session**, **application**, and **websocket** — one instance per HTTP request / session / `ServletContext` / WebSocket session.

A classic trap: injecting a `prototype` into a `singleton` gives you only one prototype instance for the singleton's lifetime. Use `ObjectProvider<T>`, `@Lookup`, or a scoped proxy to get a fresh instance per call.

### Q5. [Practical] How do you define beans? Compare `@Component` scanning vs `@Configuration`/`@Bean`.

```java
// Approach 1: component scanning (your own classes)
@Service
public class OrderService { /* ... */ }

@Configuration
@ComponentScan("com.acme.app")
public class AppConfig { }

// Approach 2: explicit @Bean (3rd-party classes you can't annotate)
@Configuration
public class InfraConfig {
    @Bean
    public DataSource dataSource() {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:postgresql://db/app");
        return new HikariDataSource(cfg);
    }
}
```

Use **component scanning** (`@Component`, `@Service`, `@Repository`, `@Controller`) for code you own — it's concise and convention-driven. Use **`@Bean` methods** when you need to register types you don't control (a `DataSource`, an SDK client, a library class), when construction needs logic, or when you want all wiring centralized and visible. In production codebases you typically mix both: scan your domain, use `@Bean` for infrastructure. Spring Boot's auto-configuration is entirely built on `@Bean` + `@Conditional`.

### Q6. [Coding] Wire a service with constructor injection and write a Spring-free unit test.

**Problem:** Build a `NotificationService` that depends on a `MessageSender`, register it, and test it without starting the container.

```java
public interface MessageSender {
    void send(String to, String body);
}

@Service
public class NotificationService {
    private final MessageSender sender;

    // Single constructor → @Autowired optional since Spring 4.3
    public NotificationService(MessageSender sender) {
        this.sender = sender;
    }

    public void notifyUser(String userId, String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text required");
        }
        sender.send(userId, "[ALERT] " + text);
    }
}

// Unit test — no Spring context needed, that's the payoff of constructor injection
class NotificationServiceTest {
    @Test
    void sendsPrefixedAlert() {
        MessageSender mock = mock(MessageSender.class);
        NotificationService svc = new NotificationService(mock);

        svc.notifyUser("u1", "disk full");

        verify(mock).send("u1", "[ALERT] disk full");
    }

    @Test
    void rejectsBlankText() {
        NotificationService svc = new NotificationService(mock(MessageSender.class));
        assertThrows(IllegalArgumentException.class, () -> svc.notifyUser("u1", "  "));
    }
}
```

**Time/Space:** wiring is O(beans) at startup; per-call logic is O(1). **Edge cases:** null/blank text, null sender (constructor injection makes a null collaborator impossible from the container). The key takeaway is that constructor injection lets you unit-test in microseconds without a container.

### Q7. [Theory] What does `@Autowired` do and how does Spring resolve ambiguity when multiple candidates exist?

`@Autowired` tells the container to inject a matching bean by **type**. When more than one candidate of the same type exists, Spring throws `NoUniqueBeanDefinitionException` unless you disambiguate using one of: **`@Primary`** (marks one bean as the default winner), **`@Qualifier("name")`** (names the exact bean), matching the **field/parameter name** to a bean name (a fallback), or **`@Profile`** to ensure only one is active. Since Spring 6 you can also use `@Qualifier` as a meta-annotation to build custom qualifiers (e.g., `@Fast`, `@Reliable`). If a dependency is optional, mark it `@Autowired(required=false)`, use `Optional<T>`, or `@Nullable`. Injecting `List<T>` or `Map<String,T>` collects *all* matching beans — useful for the strategy pattern.

---

## 🟡 Intermediate (3–7 yrs)

### Q8. [Theory] Walk through the complete Spring bean lifecycle from definition to destruction.

```
1. BeanDefinition loaded (scan / @Bean / XML)
2. BeanFactoryPostProcessor runs   → can mutate bean DEFINITIONS (not instances)
        e.g. PropertySourcesPlaceholderConfigurer resolves ${...}
3. Instantiation (constructor)     → InstantiationAwareBeanPostProcessor hooks
4. Populate properties (DI)        → field/setter injection, @Autowired resolved
5. Aware callbacks                 → BeanNameAware, BeanFactoryAware,
                                      ApplicationContextAware, EnvironmentAware
6. BeanPostProcessor.postProcessBeforeInitialization()
7. @PostConstruct  →  InitializingBean.afterPropertiesSet()  →  custom init-method
8. BeanPostProcessor.postProcessAfterInitialization()   ← AOP PROXIES created here
9. Bean is READY and in use
--- on context shutdown (singletons only) ---
10. @PreDestroy → DisposableBean.destroy() → custom destroy-method
```

Key insight for interviews: **AOP proxies are woven in step 8** by `AnnotationAwareAspectJAutoProxyCreator`, which is itself a `BeanPostProcessor`. That's why a proxied bean is a *different object* from the raw instance, and why self-invocation bypasses advice. `BeanFactoryPostProcessor` (step 2) edits metadata; `BeanPostProcessor` (steps 6/8) edits instances.

### Q9. [Theory] Explain the difference between `BeanPostProcessor` and `BeanFactoryPostProcessor` with concrete use cases.

`BeanFactoryPostProcessor` (BFPP) operates on **bean definitions** before any bean is instantiated. It can read and modify metadata — property values, scope, dependencies. Canonical example: `PropertySourcesPlaceholderConfigurer` resolving `${db.url}` placeholders, or `ConfigurationClassPostProcessor` which processes `@Configuration` classes. `BeanPostProcessor` (BPP) operates on **fully constructed bean instances**, with hooks before and after initialization callbacks. Canonical examples: `AutowiredAnnotationBeanPostProcessor` (injects `@Autowired`/`@Value`), `CommonAnnotationBeanPostProcessor` (`@PostConstruct`/`@Resource`), and `AnnotationAwareAspectJAutoProxyCreator` (creates AOP proxies). Order matters: all BFPPs run to completion before any BPP, because you must finalize definitions before instantiating. Both are auto-detected by `ApplicationContext`. A practical use: a BPP that wraps every `@Repository` to add metrics, or a BFPP that programmatically registers beans based on classpath scanning.

### Q10. [Theory] How does Spring resolve circular dependencies, and when does it fail?

Spring resolves circular references for **singleton beans using setter/field injection** via a three-level cache (the "three-level cache" of `DefaultSingletonBeanFactory`):

```
A needs B, B needs A  (setter injection)

1. Start creating A → expose an EARLY reference of A (a factory/ObjectFactory)
   into the "singletonFactories" cache before A is fully initialized.
2. While populating A, Spring needs B → starts creating B.
3. B needs A → finds A's early reference in the cache → injects it.
4. B finishes → A finishes populating with the now-complete B.
```

It **fails** (`BeanCurrentlyInCreationException`) when **both** beans use **constructor injection**, because neither can be even partially constructed without the other — there's no early reference to expose. Fixes: (a) refactor to remove the cycle (best — a cycle is usually a design smell), (b) switch one side to setter injection or `@Lazy` on a constructor parameter (injects a lazy proxy), or (c) use `ObjectProvider<T>`. **Spring Boot 2.6+ disables circular references by default** (`spring.main.allow-circular-references=false`), forcing you to fix the design rather than rely on the container papering over it.

### Q11. [Practical] A `@Transactional` method isn't rolling back / isn't applying at all. How do you debug it?

This is the most common Spring transaction bug. Checklist, in order of likelihood:

1. **Self-invocation:** calling a `@Transactional` method from another method *in the same class* bypasses the proxy (the call goes through `this`, not the proxy), so no transaction starts. Fix: move the method to another bean, inject self via `@Lazy`, or use `AopContext.currentProxy()`.
2. **Checked exceptions don't roll back by default.** Spring rolls back only on `RuntimeException` and `Error`. For checked exceptions add `@Transactional(rollbackFor = Exception.class)`.
3. **Swallowed exceptions:** a `try/catch` inside the method that eats the exception means Spring never sees it → commit happens.
4. **Non-public method:** with the default Spring AOP proxy, `@Transactional` is honored only on `public` methods (and class must be a Spring bean).
5. **Wrong proxy / missing `@EnableTransactionManagement`** (auto-enabled in Boot) or no `PlatformTransactionManager` bean.
6. **Non-transactional engine** (e.g., MyISAM in MySQL) — the DB itself ignores the transaction.

Production approach: confirm with `TransactionSynchronizationManager.isActualTransactionActive()` logging, enable `logging.level.org.springframework.transaction=TRACE`, and verify the bean is actually proxied (`AopUtils.isAopProxy(bean)`).

### Q12. [Theory] Explain `@Transactional` propagation levels with a real scenario.

Propagation governs how a transactional method behaves relative to an existing transaction:

| Propagation | Behavior |
|---|---|
| `REQUIRED` (default) | Join existing tx, or start a new one. |
| `REQUIRES_NEW` | Always suspend the outer tx and start an independent inner one. |
| `NESTED` | A savepoint inside the current tx — inner rollback doesn't kill the outer. |
| `SUPPORTS` | Join if one exists, else run non-transactionally. |
| `NOT_SUPPORTED` | Suspend any tx, run non-transactionally. |
| `MANDATORY` | Must run within an existing tx, else throw. |
| `NEVER` | Must run with no tx, else throw. |

**Real scenario — audit logging:** you process an order in `REQUIRED`, but you want the audit record persisted *even if the order rolls back*. Wrap the audit write in `REQUIRES_NEW` so it commits independently. Caveat: `REQUIRES_NEW` uses a second DB connection — under load this can exhaust the pool and even deadlock if the outer tx holds locks the inner one needs. `NESTED` (JDBC savepoints) is lighter but not supported by all transaction managers (JPA support is limited).

### Q13. [Theory] What are transaction isolation levels, and what anomalies does each prevent?

```
Anomaly →        Dirty Read   Non-repeatable Read   Phantom Read
READ_UNCOMMITTED   allowed         allowed             allowed
READ_COMMITTED     prevented       allowed             allowed
REPEATABLE_READ    prevented       prevented           allowed*
SERIALIZABLE       prevented       prevented           prevented
```

`@Transactional(isolation = Isolation.REPEATABLE_READ)` sets this on the connection. `DEFAULT` defers to the database's default (READ_COMMITTED for PostgreSQL/Oracle, REPEATABLE_READ for MySQL InnoDB). Higher isolation reduces anomalies but increases lock contention and deadlock risk, hurting throughput. *Note:* MySQL InnoDB's REPEATABLE_READ uses next-key locking and largely prevents phantoms too, which is why the table marks it with an asterisk — behavior is engine-specific. The staff-level point: choose the *lowest* isolation that satisfies correctness, and prefer optimistic locking (`@Version`) over high isolation for high-contention paths.

### Q14. [Theory] JDK dynamic proxies vs CGLIB — how does Spring AOP choose, and what are the implications?

```
Bean implements an interface?
        │
   ┌────┴─────────────────────────┐
  YES                            NO
   │                              │
 JDK dynamic proxy            CGLIB subclass proxy
 (proxies the interface)      (extends the class, overrides methods)
```

**JDK dynamic proxies** (built into the JDK) require the target to implement an interface; the proxy implements the same interface(s) and delegates. **CGLIB** creates a runtime subclass of the target by bytecode generation. Implications:
- CGLIB can't proxy `final` classes or `final`/`private` methods (it overrides, so they're invisible to advice).
- CGLIB doesn't call the real constructor in older versions; modern Spring (with Objenesis) instantiates without constructor side effects, which can surprise you if your constructor has logic.
- **Spring Boot defaults to CGLIB** (`proxyTargetClass=true`) even when interfaces exist, for consistency. Plain Spring uses JDK proxies when an interface is present unless you force CGLIB.
- Both are **runtime weaving**; for `final` classes, `@Transactional` on fields, or self-invocation support you'd need **compile/load-time weaving with AspectJ** (`@EnableLoadTimeWeaving`).

### Q15. [Coding] Implement a custom AOP aspect that logs execution time of `@Timed` methods.

```java
// 1. Marker annotation
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Timed {}

// 2. The aspect
@Aspect
@Component
public class TimingAspect {

    private static final Logger log = LoggerFactory.getLogger(TimingAspect.class);

    // Pointcut: any method annotated with @Timed, OR in a @Timed-annotated class
    @Around("@annotation(com.acme.Timed) || @within(com.acme.Timed)")
    public Object timeIt(ProceedingJoinPoint pjp) throws Throwable {
        long start = System.nanoTime();
        try {
            return pjp.proceed();          // invoke the target method
        } finally {
            long ms = (System.nanoTime() - start) / 1_000_000;
            log.info("{} took {} ms",
                     pjp.getSignature().toShortString(), ms);
        }
    }
}

@Configuration
@EnableAspectJAutoProxy   // auto-enabled by Spring Boot
class AopConfig {}

// Usage
@Service
class ReportService {
    @Timed
    public Report build() { /* heavy work */ return new Report(); }
}
```

**Advice types** you could use: `@Before`, `@After`, `@AfterReturning`, `@AfterThrowing`, and `@Around` (the most powerful — it controls whether/when `proceed()` is called and can alter the return value or args). **Time/Space:** the aspect adds O(1) overhead per call plus proxy indirection (one extra stack frame). **Edge cases:** self-invocation won't be intercepted (proxy bypass); the method must be `public` for proxy-based AOP; if `build()` throws, the `finally` still logs timing. This pattern is the basis of Micrometer's `@Timed`.

### Q16. [Practical] How do Spring profiles work and how would you structure config for dev/staging/prod?

Profiles let beans/config be conditionally active based on the environment. Activate with `spring.profiles.active=prod` (property, env var `SPRING_PROFILES_ACTIVE`, or JVM arg). Use `@Profile("prod")` on `@Configuration` classes or `@Bean` methods, and profile-specific property files `application-{profile}.yml` that override `application.yml`.

```java
@Configuration
@Profile("prod")
class ProdDataSourceConfig {
    @Bean DataSource ds() { return realPooledDataSource(); }
}

@Configuration
@Profile({"dev","test"})
class EmbeddedDataSourceConfig {
    @Bean DataSource ds() { return new EmbeddedDatabaseBuilder().setType(H2).build(); }
}
```

Production structure: a base `application.yml` with safe defaults, one override file per profile, secrets injected via environment variables / Vault (never committed), and a `@Profile("!prod")` guard on dangerous dev tooling (H2 console, verbose actuator endpoints). Spring Boot 3 supports `spring.config.activate.on-profile` and **profile groups** (`spring.profiles.group.prod=prod-db,prod-cache`) to compose profiles. **Security note:** never enable the H2 console, unauthenticated actuator, or stack-trace error responses in a `prod` profile — these are common breach vectors.

### Q17. [Coding] Use SpEL (Spring Expression Language) to inject computed and externalized values.

```java
@Component
public class PricingConfig {

    // Literal from properties with default
    @Value("${pricing.base:100}")
    private int base;

    // Arithmetic in SpEL
    @Value("#{${pricing.base:100} * 1.2}")
    private double withMargin;

    // Reference another bean's property
    @Value("#{systemProperties['user.region'] ?: 'US'}")
    private String region;

    // Collection from a comma-separated property
    @Value("#{'${pricing.tiers:bronze,silver,gold}'.split(',')}")
    private List<String> tiers;

    // Call a method on another bean
    @Value("#{taxService.rateFor('US')}")
    private double taxRate;
}
```

**Key syntax:** `${...}` is *property placeholder* resolution (from `Environment`), `#{...}` is *SpEL* evaluation. They compose: `#{${x} * 2}`. SpEL supports operators, ternary/Elvis (`?:`), safe navigation (`?.`), collection projection (`![...]`) and selection (`?[...]`), and bean references. **Security note:** **never evaluate user-supplied input as SpEL** — `SpelExpressionParser().parseExpression(userInput)` enables remote code execution (this is the root of several CVEs, e.g., Spring4Shell-adjacent expression-injection flaws). Always use a literal expression and pass user data as evaluation-context variables, not as the expression itself.

### Q18. [Theory] What is `@Lazy` and when is lazy initialization a good or bad idea?

`@Lazy` defers a singleton's creation until it is first requested rather than at context startup. On a `@Bean`/`@Component` it makes that bean lazy; on an injection point it injects a lazy *proxy* that resolves the real bean on first use (this is one way to break a constructor circular dependency). Global lazy init is available via `spring.main.lazy-initialization=true`. **Pros:** faster startup, lower memory if many beans are never used — useful in dev and serverless cold-start scenarios. **Cons:** configuration errors that would normally surface at startup are deferred to first request (a production landmine), and the first request pays the construction cost (latency spike). Recommendation: keep eager init in production for fail-fast behavior; consider lazy only for genuinely optional/rarely-used subsystems or to speed up local dev.

---

## 🟠 Advanced (8–12 yrs)

### Q19. [Theory] Explain the three-level singleton cache in detail and why three levels (not two) are needed.

`DefaultSingletonBeanRegistry` maintains:

```
singletonObjects        (L1)  → fully initialized, ready beans
earlySingletonObjects   (L2)  → early references (pre-init), already exposed
singletonFactories      (L3)  → ObjectFactory<?> that can PRODUCE an early ref
```

During creation of bean A: after instantiation but before population, Spring puts an `ObjectFactory` for A into L3. When B (mid-creation) asks for A, Spring calls that factory, which returns the early reference — **and crucially, if A needs an AOP proxy, the factory returns the proxy, not the raw bean** — then promotes it to L2. Why three levels? The L3 factory is the indirection that lets Spring decide *lazily* whether to wrap A in a proxy. If only two levels existed, Spring would have to eagerly create proxies for every bean just in case it's involved in a cycle, or it couldn't return a consistent proxy reference. L3 produces the (possibly proxied) early object exactly once; L2 caches that result so subsequent lookups during the same creation are consistent. This is why a proxied bean involved in a setter cycle still injects the proxy correctly.

### Q20. [Practical] You need a bean whose implementation is chosen at runtime based on a header/tenant. How do you design this with Spring?

Several patterns, depending on cardinality and lifecycle:

1. **Strategy map injection** — inject `Map<String, PaymentProvider>` (Spring keys it by bean name) and look up by tenant key. Simple, no proxies, fully testable. Best for a fixed set of strategies.
2. **`ObjectProvider<T>` / factory** — when you need lazy or conditional resolution with fallback (`getIfAvailable`).
3. **Scoped proxy + `ThreadLocal` context** — for request-scoped tenant data, use a custom scope or `request` scope with a scoped proxy so a singleton can hold a reference that resolves per-request.
4. **`AbstractRoutingDataSource`** — the canonical Spring solution for multi-tenant datasource routing: override `determineCurrentLookupKey()` to read a `ThreadLocal` tenant id.

```java
@Service
public class PaymentRouter {
    private final Map<String, PaymentProvider> providers; // keyed by bean name

    public PaymentRouter(Map<String, PaymentProvider> providers) {
        this.providers = providers;
    }
    public PaymentProvider forTenant(String tenant) {
        return providers.getOrDefault(tenant, providers.get("default"));
    }
}
```

Production trade-off: the strategy map is the cleanest and avoids per-request bean creation overhead. Reserve custom scopes for genuinely request-scoped *state*, and clear `ThreadLocal`s in a `finally`/filter to avoid leaks across pooled threads.

### Q21. [Theory] How does `@Configuration` differ from `@Component` for `@Bean` methods (the proxy / "lite mode" question)?

A class annotated `@Configuration` is CGLIB-enhanced ("full mode"): inter-bean method calls are intercepted so that calling another `@Bean` method returns the *singleton* from the container, not a fresh object.

```java
@Configuration
class Cfg {
    @Bean A a() { return new A(b()); }   // b() here returns the SAME singleton...
    @Bean B b() { return new B(); }      // ...as injected elsewhere, thanks to CGLIB
}
```

If you instead annotate the class with `@Component` (or set `@Configuration(proxyBeanMethods=false)` — "lite mode"), `@Bean` methods are *not* proxied: calling `b()` from `a()` creates a **new** `B`, breaking singleton guarantees. Lite mode is faster (no CGLIB subclass, no startup proxy cost) and is the right choice when your `@Bean` methods don't call each other. Spring Boot's auto-configuration increasingly uses `proxyBeanMethods=false` for startup performance. The interview point: know that *full mode* enforces singleton semantics across inter-bean references, and that this is implemented by the same CGLIB mechanism used for AOP.

### Q22. [Coding] Implement a custom bean scope (e.g., a "thread" scope).

```java
public class ThreadScope implements Scope {
    private final ThreadLocal<Map<String, Object>> store =
        ThreadLocal.withInitial(HashMap::new);

    @Override
    public Object get(String name, ObjectFactory<?> factory) {
        Map<String, Object> beans = store.get();
        return beans.computeIfAbsent(name, n -> factory.getObject());
    }

    @Override
    public Object remove(String name) {
        return store.get().remove(name);
    }

    @Override public void registerDestructionCallback(String n, Runnable cb) { /* track + run on thread end */ }
    @Override public Object resolveContextualObject(String key) { return null; }
    @Override public String getConversationId() { return Thread.currentThread().getName(); }
}

// Register it
@Configuration
class ScopeConfig implements BeanFactoryPostProcessor {
    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory bf) {
        bf.registerScope("thread", new ThreadScope());
    }
}

// Use it
@Component
@Scope(value = "thread", proxyMode = ScopedProxyMode.TARGET_CLASS)
class ThreadLocalContext { /* per-thread state */ }
```

**Why a `BeanFactoryPostProcessor`?** Scopes must be registered before beans of that scope are created — a BFPP runs at the right phase. **`proxyMode = TARGET_CLASS`** injects a scoped proxy so a singleton can hold a `ThreadLocalContext` that resolves to the correct per-thread instance on each call. **Edge cases:** you *must* clean up the `ThreadLocal` (memory leak in pooled threads), and run destruction callbacks when a thread is done. **Time/Space:** O(1) lookup; memory scales with live threads × scoped beans.

### Q23. [Theory] Explain the `ApplicationContext` event model and `@TransactionalEventListener`.

Spring's event system is an in-process publish/subscribe built on `ApplicationEventPublisher`. You publish any object (since Spring 4.2 it needn't extend `ApplicationEvent`) and consume it with `@EventListener`. Listeners are **synchronous by default** (same thread, same transaction) unless annotated `@Async` (requires `@EnableAsync`). This is powerful for decoupling: a service publishes `OrderPlacedEvent` and unrelated modules (email, analytics) react without compile-time coupling.

```java
@Service class OrderService {
    private final ApplicationEventPublisher publisher;
    OrderService(ApplicationEventPublisher p){ this.publisher = p; }
    @Transactional
    public void place(Order o) { /* save */ publisher.publishEvent(new OrderPlacedEvent(o)); }
}

@Component class EmailListener {
    @TransactionalEventListener(phase = AFTER_COMMIT)
    public void onPlaced(OrderPlacedEvent e) { sendConfirmation(e.order()); }
}
```

**`@TransactionalEventListener`** binds the listener to the transaction lifecycle — `AFTER_COMMIT` (default) fires only if the transaction commits, preventing the classic bug of sending a confirmation email for an order that later rolled back. Built-in lifecycle events (`ContextRefreshedEvent`, `ContextClosedEvent`, `ApplicationReadyEvent` in Boot) let you hook startup/shutdown. **Caveat:** synchronous listeners run in the publisher's thread and can slow it down or, if they throw, abort the publisher — use `@Async` or a real message broker (Kafka/RabbitMQ) when you need durability or isolation; the in-process event bus is not persistent.

### Q24. [Practical] A microservice has slow startup (45s) due to thousands of beans. How do you diagnose and fix it?

**Diagnose first:** enable Spring Boot's startup tracing (`ApplicationStartup` with `BufferingApplicationStartup`, exposed via the `/actuator/startup` endpoint) to get per-bean timing, and check `-Dspring.context.checkpoint` / debug logs for the slowest auto-configurations. Common culprits: eager creation of expensive beans (connection pools, caches warming up), classpath scanning over huge packages, and heavy `@PostConstruct` work.

**Fixes, in order of impact:**
1. **Narrow component scanning** to specific packages; avoid scanning the world.
2. **Lazy-init optional subsystems** selectively (`@Lazy`), keeping critical paths eager.
3. **Defer warmups** to `ApplicationReadyEvent` / async so they don't block the readiness probe.
4. **`@Configuration(proxyBeanMethods=false)`** to skip CGLIB on config classes.
5. **AOT + GraalVM native image** (Spring Boot 3 / Spring 6 AOT engine) — moves bean-definition processing to build time and can cut startup to tens of milliseconds; or **CRaC checkpoint/restore** to snapshot a warmed JVM. **Real-world case:** teams migrating Boot apps to GraalVM native images report startup dropping from tens of seconds to ~50–100 ms and RSS memory falling by ~50%, at the cost of build complexity and reflection configuration. For serverless (AWS Lambda) this is transformative for cold starts.

### Q25. [Theory] What ordering controls exist for `BeanPostProcessor`s, aspects, and listeners, and why does ordering matter?

Ordering is controlled by the `Ordered` interface, `@Order(n)` (lower = higher priority), and `PriorityOrdered` (a stronger tier that runs before plain `Ordered`). It matters because:
- **BPP order** determines, e.g., whether the autowiring BPP runs before a custom BPP that inspects injected fields.
- **Aspect order** determines advice nesting. With two `@Around` aspects, the lowest-order aspect wraps the outermost — critical when combining `@Transactional` (order `Ordered.LOWEST_PRECEDENCE` by default) with a custom retry or security aspect. A common bug: a retry aspect placed *inside* the transaction retries within a doomed transaction; you usually want retry *outside* the transaction boundary, so set the retry aspect's order lower (more outer) than transaction's.
- **`@TransactionalEventListener` + `@Order`** sequences listeners.

Staff-level nuance: `@Transactional`'s advisor order is configurable via `@EnableTransactionManagement(order = ...)`; align your custom aspects relative to it deliberately rather than relying on defaults.

### Q26. [Coding] Detect and fail fast on accidental shared mutable state in a singleton.

**Problem:** A singleton accumulates per-request state in a field, causing data bleed across concurrent requests. Show the bug and the fix.

```java
// BUG: singleton with mutable instance state — NOT thread-safe
@Service
class ReportBuilderBad {
    private List<String> rows = new ArrayList<>();   // shared across all threads!
    public String build(Data d) {
        rows.clear();
        d.records().forEach(r -> rows.add(r.toString()));
        return String.join("\n", rows);              // race: another thread mutates rows
    }
}

// FIX 1: keep state local to the method (stateless singleton)
@Service
class ReportBuilderGood {
    public String build(Data d) {
        List<String> rows = d.records().stream()       // local → thread-confined
                             .map(Object::toString)
                             .collect(Collectors.toList());
        return String.join("\n", rows);
    }
}

// FIX 2: if state must persist per-request, use request scope or pass a context object
```

**Why it breaks:** singletons are shared; two threads calling `build` interleave on the shared `rows` list, producing corrupted or mixed output, and `ArrayList` is not thread-safe (can even throw or corrupt internal state). **Detection in tests:** run the method from many threads and assert outputs are independent:

```java
@Test void noCrossThreadBleed() throws Exception {
    var svc = new ReportBuilderGood();
    var pool = Executors.newFixedThreadPool(16);
    List<Future<String>> futures = IntStream.range(0, 1000)
        .mapToObj(i -> pool.submit(() -> svc.build(dataOf(i))))
        .collect(Collectors.toList());
    for (int i = 0; i < futures.size(); i++)
        assertTrue(futures.get(i).get().contains("rec-" + i)); // each gets its own data
    pool.shutdown();
}
```

**Time/Space:** O(n) per build for n records; the fix adds no overhead (it removes false sharing). **Edge cases:** empty data, very large reports (stream/paginate), and ensuring no injected stateful collaborator hides the same bug.

### Q27. [Theory] How do `@Conditional` and Spring Boot auto-configuration interact under the hood?

Spring Boot auto-configuration is a curated set of `@Configuration` classes listed in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` (Boot 2.7+/3.x; previously `spring.factories`). Each is gated by `@Conditional` variants: `@ConditionalOnClass` (a class is on the classpath), `@ConditionalOnMissingBean` (the user hasn't defined their own), `@ConditionalOnProperty`, `@ConditionalOnWebApplication`, etc. The `@ConditionalOnMissingBean` guard is what makes auto-config *back off* the moment you define your own bean — that's the entire "convention over configuration with easy override" philosophy. These conditions are evaluated by `ConditionEvaluator` during `ConfigurationClassPostProcessor` processing, *before* the gated beans are registered. Crucially, `@AutoConfiguration` classes are ordered (`@AutoConfigureBefore/After/Order`) and evaluated after user configuration, so user beans win. The `--debug` flag or `/actuator/conditions` endpoint prints the **condition evaluation report** showing exactly which auto-configs matched and why — indispensable for debugging "why isn't my bean being created."

---

## 🔴 Expert (15+ yrs)

### Q28. [Theory] Compare Spring's proxy-based AOP with full AspectJ weaving. When would you mandate one over the other across an organization?

Spring AOP is **proxy-based runtime weaving**: it only intercepts Spring-managed beans, only at public method-call join points through the proxy, and suffers the self-invocation limitation. AspectJ is a complete AOP language supporting **compile-time, post-compile (binary), and load-time weaving (LTW)**, intercepting field access, constructors, private methods, and objects not managed by Spring — woven directly into bytecode, so there's no proxy and no self-invocation gap. Trade-offs: Spring AOP is zero-build-tooling, easy, and sufficient for ~95% of cases (transactions, security, caching, metrics). AspectJ is more powerful but adds build complexity (aspect compiler or `-javaagent` LTW), a steeper learning curve, and harder debugging. **Organizational stance:** mandate Spring AOP as the default for application-level concerns; reach for AspectJ LTW only for cross-cutting concerns that *must* apply to non-Spring objects or domain entities (e.g., domain-event publication on entities, fine-grained tracing of internal calls), and isolate that complexity in a dedicated module. Document the self-invocation pitfall prominently because it's the single most common Spring AOP bug.

### Q29. [Practical] Lead a migration from Spring Boot 2 (javax) to Spring Boot 3 (jakarta) across 40 services. What's your strategy and what breaks?

**The headline breaking change:** Spring Boot 3 / Spring 6 require **Java 17+** and migrate from the `javax.*` to the **`jakarta.*`** namespace (Servlet, Persistence, Validation, etc.) due to the Java EE → Jakarta EE transition. This ripples through every import, third-party library, and bytecode-manipulating tool.

**Strategy:**
1. **Prerequisite gates:** get every service onto the latest Boot 2.7 and Java 17 *first* — that isolates the JDK upgrade from the namespace migration.
2. **Dependency audit:** many libraries need major-version bumps to their jakarta-compatible releases (Hibernate 6, Tomcat 10, Jersey 3, etc.); some have no jakarta version and must be replaced.
3. **Automated rewrite:** use **OpenRewrite** (`UpgradeSpringBoot_3` recipe) to mechanically rewrite imports and config — invaluable at 40-service scale; follow with manual review.
4. **Pilot → wave rollout:** migrate one low-risk service end-to-end, capture a runbook, then roll out in waves with contract tests between services to catch wire-level regressions.
5. **Behavioral changes to watch:** Spring Security 6 config DSL (lambda-only, `WebSecurityConfigurerAdapter` removed), trailing-slash matching changed (`/foo/` no longer matches `/foo` by default), `@ConstructorBinding` semantics, and changes in property names. Observability shifts to **Micrometer Tracing** (Sleuth removed).

**What breaks most often:** custom servlet filters using `javax.servlet`, JPA entities importing `javax.persistence`, validation annotations, and any bytecode agent (older AspectJ/instrumentation) not aware of jakarta. The risk isn't compilation — OpenRewrite handles that — it's the runtime/behavioral deltas and transitive dependencies.

### Q30. [Theory] How do Spring 6 AOT processing and GraalVM native images change the bean model, and what are the constraints?

The Spring 6 / Boot 3 **AOT (Ahead-Of-Time) engine** moves work that traditionally happens at runtime — bean-definition processing, condition evaluation, proxy class generation — to **build time**. It generates Java source (`BeanFactoryInitializationAotContribution`s) that programmatically registers beans, plus GraalVM reachability metadata (reflection, resources, proxies, serialization hints). At runtime there's no classpath scanning or condition evaluation: the bean graph is "frozen." **Constraints this imposes:**
- The bean definitions are fixed at build time — **dynamic registration based on runtime conditions is forbidden**; profiles must be decided at build time for native images.
- Anything reflective (libraries, JSON binding, JPA) needs **reachability metadata**; missing hints cause runtime `ClassNotFoundException`/reflection failures rather than graceful degradation. You provide hints via `RuntimeHintsRegistrar` or `@RegisterReflectionForBinding`.
- CGLIB proxies are pre-generated at build time; `@Configuration(proxyBeanMethods=true)` works but adds build-time classes — lite mode is preferred.
- No lazy classloading benefits; closed-world assumption.

**Payoff:** sub-100ms startup and ~2–5× lower memory, ideal for serverless/edge. **Cost:** longer builds, harder debugging, and the discipline of a closed world. Alternative without GraalVM's constraints: **Project CRaC** checkpoints a warmed JVM and restores it in milliseconds while keeping the dynamic JVM model — a pragmatic middle ground.

### Q31. [Theory] Explain how `@Transactional` interacts with thread boundaries, async, and reactive code. Why can it silently do nothing?

Spring's `@Transactional` (the imperative variant) binds the transaction and connection to the **current thread** via `ThreadLocal` (`TransactionSynchronizationManager`). Consequences:
- **`@Async` methods** run on a *different* thread, so they get a *new, separate* transaction context — the caller's transaction does not propagate. Spawning async work inside a transaction and expecting it to share the tx is a silent correctness bug.
- **Manual threads / `CompletableFuture.supplyAsync`** likewise lose the transactional context.
- **Reactive (`Mono`/`Flux`)** code never touches `ThreadLocal` boundaries predictably; you must use the **reactive transaction manager** (`ReactiveTransactionManager` + `TransactionalOperator`) and `@Transactional` on reactive return types, which propagates context via the Reactor `Context`, not `ThreadLocal`. Mixing imperative `@Transactional` into a reactive chain does nothing useful.
- **`@Transactional` returning a `CompletableFuture`/`Future`** typically commits when the *method* returns, before the async work completes — another silent footgun.

The unifying principle: imperative transactions are thread-confined. Any work that escapes the thread escapes the transaction. The expert move is to keep the transactional unit-of-work synchronous and small, and design async/reactive flows around explicit transaction boundaries rather than assuming propagation.

### Q32. [Coding] Build a retry-with-backoff aspect that composes correctly *outside* the transaction boundary.

**Problem:** Transient failures (deadlocks, network blips) should retry, but retrying *inside* a doomed transaction is useless. Implement an ordered `@Around` aspect that wraps the transaction.

```java
@Target(ElementType.METHOD) @Retention(RetentionPolicy.RUNTIME)
public @interface Retry {
    int max() default 3;
    long backoffMs() default 100;
    Class<? extends Throwable>[] on() default { TransientDataAccessException.class };
}

@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10) // LOWER order = OUTER advice → wraps @Transactional
public class RetryAspect {

    @Around("@annotation(retry)")
    public Object around(ProceedingJoinPoint pjp, Retry retry) throws Throwable {
        int attempts = 0;
        Throwable last = null;
        while (attempts < retry.max()) {
            try {
                return pjp.proceed();              // a FRESH transaction starts inside each attempt
            } catch (Throwable t) {
                if (!retriable(t, retry.on())) throw t;
                last = t;
                attempts++;
                if (attempts < retry.max())
                    Thread.sleep(retry.backoffMs() * (1L << (attempts - 1))); // exponential backoff
            }
        }
        throw last;
    }

    private boolean retriable(Throwable t, Class<? extends Throwable>[] on) {
        for (Class<? extends Throwable> c : on)
            if (c.isInstance(t)) return true;
        return false;
    }
}

@Service
class TransferService {
    @Retry(max = 4, backoffMs = 50)
    @Transactional                          // inner advice → each retry = new tx
    public void transfer(Long from, Long to, BigDecimal amt) { /* ... */ }
}
```

**Why ordering is the crux:** `@Transactional` runs at `LOWEST_PRECEDENCE` by default. Setting the retry aspect's order *lower* makes it the **outer** advice, so each retry begins a brand-new transaction — exactly what you want, since a transaction that hit a deadlock is already rolled back and cannot be reused. If retry were *inside* the tx, you'd retry within a transaction marked rollback-only and every attempt would fail. **Time/Space:** worst case O(max) executions; backoff adds `Σ 2^i · base` sleep time. **Edge cases:** make operations idempotent (a retried `transfer` must not double-apply — guard with an idempotency key), cap total time to respect upstream timeouts, and never retry non-transient errors (validation failures). This pattern underpins Spring Retry's `@Retryable`.

### Q33. [Behavioral] Tell me about a time you made a controversial architectural decision around Spring (e.g., framework lock-in, abandoning AOP, or a DI pattern), and how you drove consensus.

Strong answers follow **Situation → Task → Action → Result** and show technical judgment plus stakeholder leadership. Example shape: *Situation* — a team was using heavy field injection and a tangle of circular dependencies "fixed" with `@Lazy`, causing flaky startup and untestable services. *Task* — I owned the reliability of the platform and needed to standardize without halting feature work. *Action* — I built a small ArchUnit ruleset to ban field injection and detect cycles in CI, wrote a migration guide, paired with two skeptical senior engineers to convert a hot service, and presented before/after startup-failure metrics at an architecture review. I explicitly addressed the dissent that "constructor injection is verbose" by showing Lombok/`@RequiredArgsConstructor` and the testability win. *Result* — circular-dependency startup failures went to zero, unit test setup time dropped, and the rule was adopted org-wide. The interviewer is assessing: do you ground architecture decisions in evidence, do you handle disagreement with respect and data, and do you make the *right* call adoptable (tooling, docs, incremental migration) rather than mandating from an ivory tower?

### Q34. [Theory] Where does Spring sit in the security threat model? Name concrete Spring-core-related vulnerability classes and mitigations.

Even "core" Spring features have security surface:
- **Expression injection (SpEL):** evaluating untrusted input as SpEL → RCE. Spring4Shell (CVE-2022-22965) exploited data-binding to reach `class.classLoader` properties. **Mitigation:** never evaluate user input as SpEL; restrict data binding with `@InitBinder` `setDisallowedFields` / `setAllowedFields`, keep Boot patched, and prefer DTOs over binding to domain objects.
- **Mass assignment / over-binding:** Spring MVC binding can set fields the user shouldn't control (e.g., `isAdmin`). **Mitigation:** explicit allow-lists, use immutable DTOs with `@ConstructorBinding`.
- **Insecure deserialization:** beans/endpoints accepting serialized Java objects. **Mitigation:** avoid Java serialization for untrusted data; use JSON with strict types.
- **Exposed actuator / H2 console in prod:** information disclosure and even RCE. **Mitigation:** profile-guard, authenticate, and restrict actuator exposure.
- **Profile/property misconfiguration:** debug error pages leaking stack traces, secrets in committed `application.yml`. **Mitigation:** externalize secrets (Vault/KMS), `@Profile` guards, fail builds on committed secrets.

The expert framing: Spring's flexibility (data binding, SpEL, dynamic proxies) is exactly what creates these surfaces, so treat *every place untrusted input meets Spring metadata/expression machinery* as a threat boundary and keep dependencies patched on a schedule, not reactively.

### Q35. [Practical] Design a multi-module Spring application's dependency-injection and configuration boundaries to keep it maintainable at scale.

At scale (dozens of modules, many teams), the goal is **clear ownership and minimal coupling**:

```
+------------------------------------------------------+
|  app-bootstrap (the only module with @SpringBootApp) |
|     imports configs, defines profiles, wires nothing |
+------------------------------------------------------+
        |            |                |
+-------------+ +-------------+ +-----------------+
| module-orders| | module-pay  | | module-shared   |
|  @Configuration| @Configuration|  (no Spring beans|
|  per module    |  per module  |   except utils)   |
+-------------+ +-------------+ +-----------------+
```

Principles I enforce:
1. **One owned `@Configuration` per module** exposing only the beans that module *publishes* as an API; internal beans stay package-private. Modules depend on *interfaces*, not concrete beans.
2. **No cross-module field injection**; constructor injection of published interfaces only, validated by ArchUnit so module boundaries can't erode.
3. **The bootstrap module is the only place** with `@SpringBootApplication` and active profiles; libraries ship `@AutoConfiguration` with `@ConditionalOnMissingBean` so consumers can override.
4. **Configuration properties** are typed (`@ConfigurationProperties` records) and namespaced per module to avoid collisions.
5. Consider **Spring Modulith** (Boot 3) to make module boundaries first-class, verify them in tests, and turn in-process events into a documented integration contract — a stepping stone toward extracting microservices later.

Trade-off: this discipline costs upfront design and tooling but pays off massively in build times, blast-radius containment, and the ability to extract a module into its own service with minimal rewiring.

---

## 🧩 Extended Questions — Set 1: Deep Theory & Internals

### 🟢 Basic — extended

#### Q36. [Theory] What is a `FactoryBean<T>` and how does it differ from a `@Bean` factory method?

A `FactoryBean<T>` is a special bean *whose job is to produce another object*. When you register a `FactoryBean` under the name `myThing`, the container does **not** hand callers the `FactoryBean` itself — it calls `getObject()` and returns the produced `T`. This indirection is how many framework integrations expose complex objects whose construction needs configuration logic that's awkward to express in a constructor: `SqlSessionFactoryBean` (MyBatis), `LocalContainerEntityManagerFactoryBean` (JPA), and `ProxyFactoryBean` (classic AOP) are all `FactoryBean`s.

```java
public class TenantClientFactoryBean implements FactoryBean<TenantClient> {
    private String region;
    public void setRegion(String r) { this.region = r; }

    @Override public TenantClient getObject() {            // build the real object here
        return TenantClient.builder().region(region).withRetries(3).build();
    }
    @Override public Class<?> getObjectType() { return TenantClient.class; }
    @Override public boolean isSingleton() { return true; } // container caches getObject()
}
```

The crucial quirk: to retrieve the **`FactoryBean` itself** rather than its product, you prefix the bean name with an ampersand — `context.getBean("&tenantClient")` returns the factory, `context.getBean("tenantClient")` returns the `TenantClient`. The `&` dereference operator is one of those facts that only shows up when you're debugging "why did I get a `FactoryBean` injected?"

So why use `FactoryBean` over a plain `@Bean` method? In modern code you usually *shouldn't* — a `@Bean` method is simpler, type-safe, and refactorable. `FactoryBean` predates Java-config and remains relevant mainly when (a) you're integrating a library that ships its own `FactoryBean`, (b) the object type isn't known until runtime (`getObjectType()` can return `null` early), or (c) you need the container to manage the factory's own lifecycle. The interview point is recognizing the pattern and the `&` dereference, not reaching for it in greenfield code.

#### Q37. [Theory] Compare `@Autowired`, `@Resource`, and `@Inject`. What are the semantic differences in how each resolves a bean?

All three perform dependency injection, but they come from different specifications and resolve candidates in a different *order*, which matters precisely when multiple beans of a type exist.

| Annotation | Origin | Primary match | Fallback |
|---|---|---|---|
| `@Autowired` | Spring | **by type** | then by `@Qualifier`/name |
| `@Resource` | Jakarta (`jakarta.annotation`) | **by name** | then by type |
| `@Inject` | Jakarta (`jakarta.inject`, JSR-330) | **by type** | then by `@Named`/qualifier |

`@Autowired` is type-first: it finds all candidates assignable to the field type, then narrows by `@Primary`, `@Qualifier`, or the field/parameter name. `@Resource` is **name-first**: it takes the field name (or its explicit `name` attribute) as the bean name and looks that up; only if no name matches does it fall back to type. `@Inject` behaves almost identically to `@Autowired` (type-first) but is the standard, framework-agnostic option — code annotated with `@Inject` could in theory run on any JSR-330 container.

```java
@Resource(name = "fastCache")   // by-name lookup, explicit
private Cache cache;

@Autowired @Qualifier("fastCache")  // by-type, narrowed by qualifier
private Cache cache;

@Inject @Named("fastCache")     // JSR-330 equivalent
private Cache cache;
```

The practical reason this is asked: a subtle bug where `@Resource private DataSource readReplica;` silently injects a *different* bean than expected because `@Resource` matched the field *name* `readReplica` to a bean named `readReplica`, whereas the developer assumed type matching. Also note the namespace shift — in Spring 6 / Boot 3 these annotations moved from `javax.annotation`/`javax.inject` to `jakarta.*`, and you need the dependency on the classpath (`jakarta.annotation-api`) for `@Resource`/`@PostConstruct` to be processed by `CommonAnnotationBeanPostProcessor`.

#### Q38. [Theory] What is the difference between `${...}` and `#{...}`, and how does Spring resolve each at runtime?

`${...}` is a **property placeholder** resolved by a `PropertySourcesPlaceholderConfigurer` (a `BeanFactoryPostProcessor`) against the `Environment`'s ordered list of `PropertySource`s — system properties, environment variables, `application.yml`, command-line args, etc. It's pure key lookup with an optional default (`${db.port:5432}`); it does not evaluate expressions. Because it's a BFPP, placeholder resolution happens at the *bean-definition* stage, before instantiation.

`#{...}` is a **SpEL expression** evaluated by the `SpelExpressionParser` against an evaluation context that includes other beans, system properties maps, and built-in functions. SpEL can do arithmetic, method calls, ternary/Elvis, collection operations, and bean references. The two are composable: in `#{${pricing.base} * 1.2}` the inner `${...}` is resolved first (text substitution) and the resulting literal is then evaluated as SpEL.

```java
@Value("${app.timeout:30}")                       // placeholder: text lookup
private int timeoutSeconds;

@Value("#{${app.timeout:30} * 1000}")             // SpEL over a resolved placeholder
private long timeoutMillis;

@Value("#{@featureFlags.isEnabled('beta')}")      // SpEL bean reference + method call
private boolean beta;
```

The interview-grade nuance is *ordering and phase*: placeholders resolve at definition-processing time and can't reference live beans, while SpEL `#{...}` evaluates at injection time and *can* reference other beans (`@beanName`). That's why you use `${...}` for static externalized config and `#{...}` only when you genuinely need computation or a live bean reference. And the security caveat carries over: never build a `#{...}` expression from untrusted input.

#### Q39. [Theory] What is the order of init callbacks — `@PostConstruct`, `InitializingBean.afterPropertiesSet()`, and a custom `init-method` — and why does Spring offer three?

For a single bean, Spring runs them in a fixed, well-defined order **after** dependency injection completes and **after** `BeanPostProcessor.postProcessBeforeInitialization()`:

```
1. @PostConstruct                       (processed by CommonAnnotationBeanPostProcessor)
2. InitializingBean.afterPropertiesSet()(the interface callback)
3. custom init-method                   (@Bean(initMethod="..."))
```

The symmetric destruction order on shutdown is `@PreDestroy` → `DisposableBean.destroy()` → custom `destroy-method`. Spring offers three mechanisms for historical and decoupling reasons. `@PostConstruct`/`@PreDestroy` are JSR-250 standard annotations — framework-agnostic and the recommended default. `InitializingBean`/`DisposableBean` are Spring interfaces; they avoid the annotation-processing dependency but **couple your class to Spring**, which is why they're discouraged for application code. The `initMethod`/`destroyMethod` attributes on `@Bean` are the clean option for **third-party classes** you can't annotate — you point at an existing method by name.

```java
@Component
class CacheWarmer implements InitializingBean {
    @PostConstruct void earliest() { /* runs first */ }
    @Override public void afterPropertiesSet() { /* runs second */ }
    // custom init via @Bean(initMethod="prime") if defined that way
}
```

A subtlety worth mentioning: because `@PostConstruct` runs *after* injection but the AOP proxy is created in `postProcessAfterInitialization()` (later), calling a `@Transactional` or `@Cacheable` method from inside your own `@PostConstruct` **bypasses the proxy** — the advice isn't there yet. That's a real-world gotcha when people try to do transactional warmup work in `@PostConstruct`; defer it to an `ApplicationReadyEvent` or `SmartInitializingSingleton.afterSingletonsInstantiated()` instead.

### 🟡 Intermediate — extended

#### Q40. [Theory] How does Spring's `Environment` abstraction work, and what is the precedence order of property sources in Spring Boot?

The `Environment` is Spring's unified abstraction over two things: **profiles** (which bean groups are active) and **properties** (key/value configuration). Properties are not stored in one place — they live in an ordered list of `PropertySource` objects held by a `MutablePropertySources`. When you resolve a key, Spring walks the list **in order and returns the first match**, so the *list order is the precedence order*. This is why the same key in two files can give different results depending on where each file sits in the chain.

Spring Boot defines a specific, documented precedence (highest wins). Abbreviated from the reference:

```
1. Devtools global settings (~/.config)         [dev only]
2. @TestPropertySource / test props
3. Command-line arguments (--server.port=8081)
4. SPRING_APPLICATION_JSON
5. ServletConfig / ServletContext params
6. JNDI (java:comp/env)
7. Java System properties (-Dkey=value)
8. OS environment variables (SERVER_PORT)
9. application-{profile}.yml (profile-specific)
10. application.yml (default)
11. @PropertySource on @Configuration
12. SpringApplication default properties
```

The two facts interviewers probe: command-line args and environment variables **override** packaged `application.yml`, which is what makes 12-factor / container deployments work (you ship one jar and override per environment via env vars). And **relaxed binding** means `SERVER_PORT`, `server.port`, `server-port`, and `serverPort` all map to the same property — so an env var in `UPPER_SNAKE_CASE` correctly overrides a YAML key in kebab/camel case. Knowing the chain lets you answer "why is my property value not what the file says" — almost always something higher in the list is overriding it, which you can confirm via the `/actuator/env` endpoint.

#### Q41. [Theory] When you inject `List<Foo>` or `Map<String, Foo>`, what does Spring put in the collection, and in what order?

Injecting a typed collection is Spring's built-in support for the **strategy / plugin pattern**: the container collects *every* bean assignable to the element type and injects them all. For `List<Foo>` you get every `Foo` bean; for `Map<String, Foo>` you get a map keyed by **bean name** → bean instance. This lets you add a new strategy simply by defining a new `@Component`, with zero changes to the consumer.

```java
public interface Validator { void validate(Order o); }

@Component @Order(1) class SchemaValidator implements Validator { /* ... */ }
@Component @Order(2) class FraudValidator  implements Validator { /* ... */ }

@Service
class OrderValidationChain {
    private final List<Validator> validators;     // BOTH injected, ordered
    OrderValidationChain(List<Validator> validators) { this.validators = validators; }
    void run(Order o) { validators.forEach(v -> v.validate(o)); }
}
```

The ordering question is the deep part. For a `List<Foo>`, Spring orders elements by the `@Order` annotation / `Ordered` interface / `@Priority` — **not** by declaration or scan order. If you rely on a specific sequence (a validation pipeline, a filter chain) you **must** make ordering explicit with `@Order`; assuming classpath scan order is a fragile bug that can change between builds or JVMs. For a `Map`, iteration order is insertion order of a `LinkedHashMap` Spring builds, again influenced by `@Order`.

One more nuance: if you want the *consumer itself* to also be eligible (or to exclude self), or you want lazy/optional semantics, use `ObjectProvider<Foo>` which exposes `.stream()`, `.orderedStream()`, and `getIfAvailable()`. And if **zero** matching beans exist, `List<Foo>` injects an empty list (not an error), whereas a single `Foo` injection would fail with `NoSuchBeanDefinitionException` — a behavioral difference that occasionally surprises people building optional plugin points.

#### Q42. [Theory] Compare `ObjectFactory`, `ObjectProvider`, and JSR-330 `Provider`. When does each matter?

All three break the *eager, one-shot* nature of normal injection by giving you a handle you call *later* to obtain the dependency — essential for injecting prototypes/request-scoped beans into singletons, deferring creation, or making a dependency optional.

- **`ObjectFactory<T>`** is the oldest and simplest: a single `getObject()` method. It's the type Spring uses internally (e.g., in the three-level singleton cache and in `Scope.get`). You rarely declare it in application code anymore.
- **`ObjectProvider<T>`** (Spring 4.3+) extends `ObjectFactory` and is the modern, richest option. It adds `getIfAvailable()`, `getIfUnique()`, `getIfAvailable(Supplier defaultSupplier)`, and stream methods `stream()` / `orderedStream()`. It turns "optional or zero-or-many dependency" into clean, exception-free code.
- **`Provider<T>`** (JSR-330, `jakarta.inject.Provider`) is the standard-spec equivalent with a single `get()` method — use it when you want framework-agnostic code.

```java
@Service
class ReportService {
    private final ObjectProvider<ReportContext> contextProvider; // prototype-scoped

    ReportService(ObjectProvider<ReportContext> p) { this.contextProvider = p; }

    String build() {
        ReportContext ctx = contextProvider.getObject(); // FRESH prototype each call
        // ...
        return ctx.render();
    }
}
```

The canonical use case is the **prototype-in-singleton** trap: if you inject a prototype `ReportContext` directly into a singleton, you get exactly one instance for the singleton's life. Injecting `ObjectProvider<ReportContext>` and calling `getObject()` per request gives you a genuinely fresh prototype each time, because the provider re-asks the container. `ObjectProvider` is preferred over `ObjectFactory` for new code (richer API), over a scoped proxy when you want explicit control rather than transparent proxying, and over `@Lookup` method injection because it's plain code rather than a CGLIB-overridden abstract method.

#### Q43. [Theory] Explain `@Async` internals: how is it implemented, what are the return-type rules, and what are the common gotchas?

`@Async` is implemented by the same proxy/AOP machinery as `@Transactional`. `@EnableAsync` registers an `AsyncAnnotationBeanPostProcessor` that wraps async beans in a proxy whose advice submits the method invocation to a `TaskExecutor` (by default a `SimpleAsyncTaskExecutor` unless you define a `ThreadPoolTaskExecutor` bean — and the default *creates a new thread per task*, which is dangerous in production, so you almost always provide your own pool). The proxy returns immediately to the caller while the real work runs on the executor thread.

Because it's proxy-based, every limitation of Spring AOP applies: **self-invocation does nothing** (an internal call doesn't go through the proxy, so it runs synchronously), and the method must be `public` on a Spring-managed bean. The **return type rules** are strict: an `@Async` method must return `void`, `Future<T>`, `CompletableFuture<T>` (preferred), or `ListenableFuture<T>`. Returning a plain value (e.g., `String`) is a bug — the proxy can only hand back a future placeholder or nothing, so a non-future return type will always be `null`/garbage.

```java
@Configuration @EnableAsync
class AsyncConfig {
    @Bean public Executor taskExecutor() {
        var ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(8); ex.setMaxPoolSize(16); ex.setQueueCapacity(100);
        ex.setThreadNamePrefix("async-");
        ex.initialize();
        return ex;
    }
}

@Service
class MailService {
    @Async                                  // runs on taskExecutor thread
    public CompletableFuture<Boolean> send(String to) {
        // ... blocking I/O off the request thread ...
        return CompletableFuture.completedFuture(true);
    }
}
```

The deepest gotchas: (1) **exceptions** thrown from a `void` `@Async` method vanish unless you register an `AsyncUncaughtExceptionHandler` (via `AsyncConfigurer`); for future-returning methods the exception surfaces when you call `.get()`. (2) **Transaction context does not propagate** — the async thread has its own `ThreadLocal`, so a `@Transactional` on the caller doesn't cover the async work. (3) **`ThreadLocal`-based context** (security context, MDC, request scope) is lost unless you decorate the executor with a `TaskDecorator` that copies it. These three account for most "my @Async behaves weirdly" tickets.

#### Q44. [Theory] How does `@Cacheable` work internally, and why can the same key sometimes miss the cache?

`@Cacheable` (and `@CachePut`/`@CacheEvict`) is interceptor-based AOP enabled by `@EnableCaching`, which registers a `CacheInterceptor` advisor. On a method call the interceptor (1) computes a **key** via the configured `KeyGenerator` (default `SimpleKeyGenerator` combines all parameters) or a SpEL `key` expression, (2) looks the key up in the named `Cache` obtained from the `CacheManager`, (3) returns the cached value on a hit, or proceeds to the method and stores the result on a miss. The actual storage is pluggable — `ConcurrentMapCacheManager` (default, in-memory), Caffeine, Redis, etc.

```java
@Service
class ProductService {
    @Cacheable(cacheNames = "products", key = "#id", unless = "#result == null")
    public Product byId(Long id) { /* expensive DB call */ }

    @CacheEvict(cacheNames = "products", key = "#p.id")
    public void update(Product p) { /* write-through eviction */ }
}
```

Now the "why does it miss" part, which is the real interview content. The most common cause is **self-invocation** — a method in the same class calling `byId()` bypasses the proxy, so no caching happens at all. The second is **key mismatch**: if the key is derived from a mutable object's `equals`/`hashCode`, or the SpEL key references a parameter that differs subtly (autoboxing `Long` vs `long`, or an object without proper `equals`), each call computes a different key and never hits. The third is the `condition`/`unless` interplay — `condition` is evaluated *before* invocation (skip caching entirely) while `unless` is evaluated *after* against `#result` (cache but veto storing this result); confusing the two leads to surprises like caching `null`.

There's also a concurrency subtlety: by default two threads can both miss and both execute the method (cache stampede). `@Cacheable(sync = true)` serializes computation per key for local caches, but not all `CacheManager`s support it. And `@Cacheable` caches *exceptions* by default? No — it does not cache exceptions; a thrown exception propagates and nothing is stored, so a failing expensive call will re-execute every time, which can hammer a struggling downstream. Understanding these mechanics is what separates "I added `@Cacheable`" from "I know why it isn't caching."

#### Q45. [Theory] What is `@DependsOn`, and how does it differ from declaring a dependency through injection?

`@DependsOn` forces the container to **instantiate and initialize** one or more named beans *before* the annotated bean, without creating a reference between them. It controls pure **initialization ordering**, not wiring. You reach for it when bean A doesn't *inject* bean B but nonetheless relies on a side effect B produces during its initialization — a classic example is a bean that registers a JDBC driver, seeds a database, installs a security provider, or starts an embedded server that another bean assumes is already running.

```java
@Component
@DependsOn("flywayMigrator")          // ensure migrations run before this repository inits
public class ReportingRepository {
    // does NOT inject FlywayMigrator, but its tables must exist first
}
```

The contrast with injection is the whole point. Normal DI (`A` injects `B`) *implies* ordering — Spring must create `B` to construct `A` — and it creates a real reference. `@DependsOn` is for the case where the ordering dependency is **implicit and side-effect-based**, with no object reference. Using injection where you only need ordering would force an unnecessary, possibly awkward reference (and might create a circular dependency); using `@DependsOn` keeps the classes decoupled while still guaranteeing sequence.

The caveats: `@DependsOn` is a code smell when overused because it encodes ordering that the type system can't see, making refactoring fragile (rename the depended-on bean and you get a runtime failure, not a compile error). It also only guarantees *initialization* order, and for destruction the order is reversed (depended-on beans are destroyed after dependents). Prefer making side effects explicit through events (`ApplicationReadyEvent`), `SmartLifecycle` phases, or actual injection where reasonable, and reserve `@DependsOn` for genuine init-side-effect coupling like schema migration before repositories.

#### Q46. [Theory] How does Spring perform generics-aware autowiring? Why can it inject the *right* `Repository<Order>` among several?

Since Spring 4.0, the container resolves dependencies using the **full parameterized type**, not just the raw class, via the `ResolvableType` abstraction that reads generic type information from fields, method parameters, and return types. This means a bean declared as `Repository<Order>` and another as `Repository<Customer>` are treated as *distinct candidates*, and injecting `Repository<Order>` picks the matching one with no `@Qualifier` needed — the generic type parameter acts as an implicit qualifier.

```java
public interface Repository<T> { void save(T entity); }

@Component class OrderRepository    implements Repository<Order>    { /* ... */ }
@Component class CustomerRepository implements Repository<Customer> { /* ... */ }

@Service
class OrderService {
    private final Repository<Order> repo;          // resolves to OrderRepository, unambiguously
    OrderService(Repository<Order> repo) { this.repo = repo; }
}
```

How does it work under the hood? Java erases generics at runtime for *instances*, but the generic type of a **declaration site** (a field, a method/constructor parameter, a `@Bean` method return) is preserved in the class file's signature metadata. Spring reads that via reflection into a `ResolvableType` and compares it against each candidate's resolvable type, including for `@Bean` methods whose declared return type is `Repository<Order>`. So the information Spring needs survives erasure precisely because it's attached to declarations, not objects.

The limits are worth knowing: it works when the generic type is concretely known at the injection point. If you inject a raw `Repository` or a wildcard `Repository<?>`, multiple candidates again become ambiguous and you'll need `@Qualifier` or `@Primary`. Also, collection injection composes with this — `List<Repository<Order>>` collects only the `Order` repositories. This generics-aware matching is one of the quiet quality-of-life features that makes large Spring codebases with many parameterized services and repositories wire themselves correctly.

#### Q47. [Theory] What does the `refresh()` method of `AbstractApplicationContext` do? Walk through the startup sequence.

`refresh()` is the **template method that builds and starts the entire container** — every `ApplicationContext` startup funnels through it. It's synchronized and defines a fixed sequence of phases; knowing them lets you reason precisely about *when* each extension point fires.

```
refresh() {
  1. prepareRefresh()                 // set startup time, validate required props
  2. obtainFreshBeanFactory()         // create/load BeanFactory + bean DEFINITIONS
  3. prepareBeanFactory()             // register standard post-processors, env beans
  4. postProcessBeanFactory()         // subclass hook (e.g. web context registers scopes)
  5. invokeBeanFactoryPostProcessors()// run ALL BFPPs (incl. ConfigurationClassPostProcessor,
                                       //   which processes @Configuration / @Bean / scanning)
  6. registerBeanPostProcessors()     // register (not run) all BPPs in order
  7. initMessageSource()              // i18n
  8. initApplicationEventMulticaster()// event infrastructure
  9. onRefresh()                      // subclass hook (web: create the embedded server)
  10. registerListeners()             // wire @EventListener / ApplicationListener beans
  11. finishBeanFactoryInitialization()// INSTANTIATE all non-lazy singletons (DI, BPPs, AOP)
  12. finishRefresh()                 // publish ContextRefreshedEvent, start SmartLifecycle
}
```

Two phases carry most of the conceptual weight. Step 5 is where **`@Configuration` classes are parsed and component scanning runs** (`ConfigurationClassPostProcessor` is itself a BFPP), so the full set of bean definitions exists only after step 5 — this is why a BFPP can register additional definitions but a BPP cannot. Step 11, `finishBeanFactoryInitialization()`, is where **all eager singletons are actually created**, dependency injection happens, `BeanPostProcessor`s run, and AOP proxies are woven — this is the phase that surfaces most configuration errors and dominates startup time.

The interview value is being able to place an extension point on this timeline: BFPPs run at step 5 (definitions still mutable), BPPs are *registered* at step 6 but *invoked* during step 11 per bean, `ContextRefreshedEvent` and `SmartLifecycle.start()` happen at step 12, and Spring Boot's `ApplicationReadyEvent` fires *after* `refresh()` returns entirely. If `refresh()` throws, it calls `destroyBeans()` and `cancelRefresh()` so a half-built context doesn't leak — which is why a failed startup cleanly shuts down already-created singletons.

### 🟠 Advanced — extended

#### Q48. [Theory] Explain `@Import`, `ImportSelector`, and `ImportBeanDefinitionRegistrar`. How does Spring Boot use them?

`@Import` is the programmatic glue that lets one `@Configuration` pull in others, and it accepts three kinds of arguments, in increasing power:

1. **`@Import(SomeConfig.class)`** — import another `@Configuration`/`@Component` class directly. Simple composition.
2. **`@Import(MySelector.class)`** where `MySelector implements ImportSelector` — the selector's `selectImports(AnnotationMetadata)` returns an **array of class names** to register, computed at processing time. This is *conditional configuration as code*: you decide which configs to import based on annotation attributes or the environment.
3. **`@Import(MyRegistrar.class)`** where `MyRegistrar implements ImportBeanDefinitionRegistrar` — gives you a `BeanDefinitionRegistry` to **register bean definitions imperatively**, the most flexible (and lowest-level) option.

```java
public class FeatureSelector implements ImportSelector {
    @Override public String[] selectImports(AnnotationMetadata meta) {
        boolean advanced = (boolean) meta.getAnnotationAttributes(EnableFeature.class.getName())
                                         .get("advanced");
        return advanced
            ? new String[]{ AdvancedFeatureConfig.class.getName() }
            : new String[]{ BasicFeatureConfig.class.getName() };
    }
}

@Retention(RUNTIME) @Import(FeatureSelector.class)
public @interface EnableFeature { boolean advanced() default false; }
```

There's also a `DeferredImportSelector`, whose imports are processed **after** all regular `@Configuration` classes — and this is exactly how **Spring Boot auto-configuration** works. `@EnableAutoConfiguration` imports `AutoConfigurationImportSelector` (a `DeferredImportSelector`), which reads the candidate auto-config classes from `META-INF/spring/...AutoConfiguration.imports`, filters them by `@Conditional` evaluation, and registers the survivors *after* your own configuration — guaranteeing your beans are seen first so `@ConditionalOnMissingBean` can correctly back off. This is the mechanism behind the entire `@EnableXxx` annotation family (`@EnableScheduling`, `@EnableCaching`, `@EnableTransactionManagement`): each is a meta-annotation that `@Import`s a selector or registrar.

#### Q49. [Theory] What is `SmartLifecycle`, how do its phases work, and how does it differ from `@PostConstruct`/`@PreDestroy`?

`Lifecycle`/`SmartLifecycle` is Spring's contract for components that need to **start and stop** in a controlled order *after the context is fully built* and *before it's torn down* — think message-broker consumers, schedulers, embedded servers, or background pollers. Unlike `@PostConstruct` (which runs per-bean during initialization, before the context is ready), `SmartLifecycle.start()` is invoked by the context in `finishRefresh()` once *all* singletons exist, and `stop()` runs at the very beginning of shutdown, before any bean is destroyed.

The key feature is **phased ordering**. Each `SmartLifecycle` bean returns a `getPhase()` value: on startup, **lower phases start first**; on shutdown, the order **reverses** (higher phases stop first). This lets you express "the database connection pool (low phase) must be up before the Kafka consumer (higher phase) starts, and the consumer must stop before the pool closes."

```java
@Component
class KafkaConsumerManager implements SmartLifecycle {
    private volatile boolean running = false;

    @Override public int getPhase() { return 100; }       // starts after lower-phase infra
    @Override public boolean isAutoStartup() { return true; }
    @Override public void start() { /* begin polling */ running = true; }
    @Override public void stop()  { /* drain + close */  running = false; }
    @Override public boolean isRunning() { return running; }
}
```

The differences from `@PostConstruct`/`@PreDestroy` are: (1) **timing** — lifecycle callbacks bracket the *running* context, not the per-bean init/destroy moments; (2) **ordering** — `SmartLifecycle` gives explicit cross-bean phase ordering, whereas init/destroy order is only constrained by dependency edges; (3) **graceful shutdown** — `SmartLifecycle.stop(Runnable callback)` (the overload) supports asynchronous, time-bounded shutdown, which Spring Boot ties to `server.shutdown=graceful` to drain in-flight requests before stopping. For anything that owns a long-running resource and must integrate with ordered startup/shutdown, `SmartLifecycle` is the right tool; `@PostConstruct` is for one-shot initialization of a single bean.

#### Q50. [Theory] How does `proxyMode = ScopedProxyMode.TARGET_CLASS` actually inject a short-lived bean into a singleton, and what is the runtime mechanism?

The fundamental tension: a singleton is created once and holds its injected references forever, but a request- or session-scoped bean must change per request. You cannot inject the *real* scoped bean into a singleton or it would freeze the first instance forever. The **scoped proxy** resolves this by injecting a *stand-in proxy* into the singleton; on every method call the proxy looks up (or creates) the *correct* scoped instance for the current context and delegates to it.

```java
@Component
@Scope(value = "request", proxyMode = ScopedProxyMode.TARGET_CLASS)
class RequestContext {
    private String correlationId;        // distinct per HTTP request
    // getters/setters
}

@Service
class AuditService {                     // singleton
    private final RequestContext ctx;    // actually a scoped PROXY, injected once
    AuditService(RequestContext ctx) { this.ctx = ctx; }
    void log(String msg) { record(ctx.getCorrelationId(), msg); } // resolves per-request
}
```

The runtime mechanism: `ScopedProxyMode.TARGET_CLASS` generates a **CGLIB subclass proxy** of the scoped type (use `INTERFACES` for a JDK proxy when the bean has interfaces). The proxy's overridden methods don't hold state; each call routes through the `Scope` implementation — for `request` scope, `RequestScope.get()` reads the current request's attributes (via `RequestContextHolder`'s `ThreadLocal`) and returns the instance bound to *this* request, lazily creating it on first access. So the singleton holds one stable proxy reference, while the actual delegate is resolved fresh per scope activation.

The deep implications: (1) the scoped bean is created **lazily** on first method call within its scope, not when the singleton is built; (2) you must be inside an active scope (an HTTP request thread) or you get `BeanCreationException: No thread-bound request found` — calling it from a `@Scheduled` task or async thread fails because there's no request `ThreadLocal`; (3) because it's a CGLIB proxy, the same `final`/`private`-method limitations and the need for a no-state subclass apply. This proxy-resolves-per-scope pattern is identical in spirit to how `@Configuration` and AOP proxies work — Spring reuses the same proxying foundation throughout.

#### Q51. [Theory] How does the classpath component scanner find candidates without loading every class, and what is the role of ASM?

Component scanning has a performance and correctness problem hiding in it: to decide whether `com.acme.SomeClass` is a `@Component`, the naive approach is to `Class.forName` every class under the base package and reflectively check its annotations. That would **load and initialize thousands of classes** at startup — triggering static initializers, pulling in dependencies you might not want loaded, and wasting time on classes that aren't components at all.

Spring avoids this with `ClassPathScanningCandidateComponentProvider`, which reads class files as **raw bytecode using ASM** (a bundled bytecode library) to extract annotation metadata *without loading the class into the JVM*. It scans the classpath resources matching the package pattern, parses each `.class` file's constant pool and annotation tables via `MetadataReader`/`SimpleMetadataReader`, and applies the include/exclude `TypeFilter`s (default include filter matches `@Component` and its meta-annotations like `@Service`). Only classes that *pass* the filters become candidate `BeanDefinition`s — and only those get loaded later when actually instantiated.

```
classpath:com/acme/**/*.class
        │
        ▼  (ASM reads bytecode, no classloading)
  MetadataReader → AnnotationMetadata { isAnnotated(@Component)? meta-annotations? }
        │
        ▼  TypeFilter include/exclude
  matched → register BeanDefinition (class still NOT loaded)
        │
        ▼  (later, at instantiation)
  Class actually loaded + constructed
```

This design is why **narrowing your scan packages matters for startup time** (fewer bytecode files to read) and why **meta-annotations work** — ASM can see that `@Service` is itself annotated with `@Component` by reading the annotation's own bytecode. It's also why a malformed or partially-present class on the classpath can cause scan-time errors that look mysterious: the metadata reader chokes on the bytecode before any of your code runs. The same `MetadataReader` infrastructure powers `@Conditional` evaluation and Spring Boot's auto-configuration filtering, all built to make decisions from metadata without premature classloading.

#### Q52. [Theory] How does Spring's transaction synchronization work, and what is `TransactionSynchronizationManager`?

`TransactionSynchronizationManager` (TSM) is the **thread-bound registry** that underpins all of Spring's imperative transaction management. It holds, in `ThreadLocal`s, the *resources* bound to the current transaction (the JDBC `Connection` or JPA `EntityManager` keyed by their factory), the active transaction's metadata (name, isolation, read-only flag, whether a real transaction is active), and a list of registered `TransactionSynchronization` callbacks. Every Spring data-access template (`JdbcTemplate`, JPA, Hibernate) consults TSM to find the *same* connection so that all operations on a thread join one transaction rather than each grabbing a fresh connection.

The synchronization callbacks are the powerful, less-known part. You can register a `TransactionSynchronization` to run logic at precise points in the transaction lifecycle:

```java
TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
    @Override public void beforeCommit(boolean readOnly) { /* validate */ }
    @Override public void afterCommit() { /* fire only if commit succeeded */ }
    @Override public void afterCompletion(int status) {
        if (status == STATUS_ROLLED_BACK) { /* compensating action */ }
    }
});
```

This is exactly how `@TransactionalEventListener(phase = AFTER_COMMIT)` is implemented — the event is buffered and a synchronization fires the listener in `afterCommit()`, guaranteeing the side effect (sending an email, publishing to Kafka) happens *only if the transaction committed*. It's also how Spring flushes the JPA persistence context before commit and releases the connection after completion.

The expert takeaways: because everything hangs off `ThreadLocal`, the transaction is strictly **thread-confined** (hence `@Async`/reactive don't inherit it). You can query TSM directly for debugging — `isActualTransactionActive()`, `isCurrentTransactionReadOnly()`, `getCurrentTransactionName()` — which is invaluable when diagnosing "is my code even in a transaction?" And registering an `afterCommit` synchronization is the correct, race-free pattern for "do X only after the data is durably committed," far safer than doing X inside the transaction and hoping it commits.

#### Q53. [Theory] Enumerate the AspectJ pointcut designators usable in Spring AOP. Which are *not* supported, and why?

Spring AOP uses AspectJ's pointcut *expression language* for matching but only supports a subset of designators, because Spring AOP intercepts only **method execution join points on Spring-managed proxied beans** — it is not full bytecode weaving. The commonly used designators:

| Designator | Matches | Example |
|---|---|---|
| `execution(...)` | method execution (the workhorse) | `execution(* com.acme.service.*.*(..))` |
| `within(...)` | join points within certain types | `within(com.acme.service..*)` |
| `@annotation(...)` | methods annotated with X | `@annotation(org.springframework.transaction.annotation.Transactional)` |
| `@within(...)` | methods of types annotated with X | `@within(org.springframework.stereotype.Service)` |
| `@target(...)` | runtime: target object's class annotated with X | `@target(MyMarker)` |
| `@args(...)` | runtime: argument types annotated with X | `@args(Validated)` |
| `args(...)` | argument types/values | `args(String, ..)` |
| `target(...)` | proxied target is instance of type | `target(com.acme.Repo)` |
| `this(...)` | proxy is instance of type | `this(com.acme.Repo)` |
| `bean(...)` | Spring-specific: by bean name (with wildcards) | `bean(*Service)` |

What's **not** supported and why: `call()` (Spring only sees the callee's *execution*, never the caller's call site, because there's no caller-side weaving), `get()`/`set()` (no field-access interception — proxies wrap methods, not fields), `initialization()`/`staticinitialization()`/`preinitialization()` (constructor/static-init join points aren't proxyable), and `handler()` (exception-handler join points). All of those require AspectJ's compile-time or load-time weaving because they touch join points that don't exist at the method-proxy level.

The interview-grade synthesis: the supported set reflects Spring AOP's fundamental model — *a proxy that intercepts public method calls on container beans*. Two of the listed designators (`target(...)`, `this(...)`, `@target`, `@args`) are *runtime* matches that incur a per-invocation check and can't be optimized to compile-time, so heavy use has a small cost. The `bean(...)` designator is Spring-only (not standard AspectJ) and is handy for coarse "all beans named `*Repository`" cross-cutting. When you need `get`/`set`/`call`/constructor interception, that's the signal you've outgrown proxy AOP and need AspectJ weaving.

#### Q54. [Theory] How is the `ApplicationContext` hierarchy structured in a Spring MVC application (root vs dispatcher context), and what are the visibility rules?

Classic Spring MVC defines a **parent-child context hierarchy**. The **root `WebApplicationContext`** (bootstrapped by `ContextLoaderListener`) holds infrastructure shared application-wide — services, repositories, data sources, transaction managers, security. Each `DispatcherServlet` creates its own **child context** holding web-layer beans — controllers, view resolvers, handler mappings, `@ControllerAdvice`. The child can have its own web-specific beans while inheriting from the root.

```
        Root WebApplicationContext         (services, repos, DataSource, TxManager)
                    ▲
                    │  parent
        ┌───────────┴────────────┐
   DispatcherServlet ctx     DispatcherServlet ctx   (controllers, view resolvers)
     (e.g. /api/*)              (e.g. /admin/*)
```

The **visibility rule is asymmetric and is the whole point**: a child context can see and inject beans from its parent (root), but the parent **cannot** see beans defined in a child. So a controller in the dispatcher context can inject a service from the root, but a root-context bean can never inject a controller. This enforces a clean layering — the web tier depends on the service tier, not vice versa — and lets multiple dispatcher servlets share one set of services while keeping their web beans isolated.

The classic bug this produces: if you accidentally component-scan your controllers in the *root* context *and* the dispatcher context, you get **two instances** of each, and `@RequestMapping`/transaction or aspect behavior can act on the wrong copy, or beans appear "missing" because they're defined in the sibling/parent you're not looking at. The fix is disciplined scan boundaries — root scans services, dispatcher scans `@Controller`s. **In Spring Boot**, this is largely simplified: the default is a **single application context** (no root/child split) because there's one embedded servlet container and one `DispatcherServlet`, so most Boot developers never see the hierarchy — but understanding it explains legacy apps and is essential when you intentionally run multiple dispatcher servlets or embed Spring in another framework.

#### Q55. [Theory] What is the difference between `@Order`/`Ordered` and `@Priority`, and where does each take effect?

Both express ordering, but they originate from different specs and Spring honors them in subtly different contexts. `@Order(int)` and the `Ordered` interface are Spring's own ordering mechanism; `@Priority(int)` comes from JSR-250 (`jakarta.annotation.Priority`). In both, a **lower number means higher priority** (runs first / sorts earlier).

The behavioral difference shows up in two places. First, **`@Priority` participates in autowiring candidate selection** — when multiple beans match a single injection point and none is `@Primary`, Spring picks the one with the *highest priority* (lowest `@Priority` value); `@Order` does **not** influence single-injection disambiguation at all. Second, when injecting a **`List<T>`** or using `OrderComparator`, both `@Order` and `@Priority` are consulted to sort the collection.

```java
@Component @Priority(1) class PrimaryProcessor implements Processor {}  // wins single inject
@Component @Priority(2) class BackupProcessor  implements Processor {}

@Service
class Consumer {
    Consumer(Processor p) { /* injects PrimaryProcessor due to @Priority */ }
    // but: List<Processor> would be [PrimaryProcessor, BackupProcessor] ordered by @Priority/@Order
}
```

The other distinction is *where you can put them*: `@Order` can annotate methods, types, and even `@Bean` methods, and is the idiomatic Spring choice for ordering aspects, filters, `CommandLineRunner`s, and `@TransactionalEventListener`s. `@Priority` is type-level only (it's a runtime annotation on classes). The precise rule interviewers want: for **collection ordering** they're roughly interchangeable, but for **resolving which single bean to autowire among several**, only `@Priority` (and `@Primary`, which takes precedence over `@Priority`) participates — `@Order` is ignored there. Knowing that asymmetry prevents the mistake of slapping `@Order` on competing beans and expecting it to decide which one gets injected.

### 🔴 Expert — extended

#### Q56. [Theory] Explain SpEL's evaluation modes and expression compilation. When and why would you enable the compiler?

SpEL is normally **interpreted**: each evaluation walks the parsed abstract syntax tree (the `Expression` object), performing reflection-based property access and method invocation. That's flexible and dynamic but carries reflection overhead, which is negligible for one-off `@Value` resolution at startup but can matter when an expression is evaluated millions of times on a hot path (e.g., per-row in a data pipeline, per-event in a rules engine, per-request in a routing layer).

For those cases SpEL offers a **compiler** that generates a real Java class implementing the expression as straight-line bytecode, eliminating the AST walk and most reflection. You enable it via `SpelParserConfiguration` with one of three `SpelCompilerMode`s:

```java
SpelParserConfiguration config = new SpelParserConfiguration(
        SpelCompilerMode.IMMEDIATE,        // compile on first evaluation
        this.getClass().getClassLoader());
ExpressionParser parser = new SpelExpressionParser(config);
Expression expr = parser.parseExpression("payload.amount * rate.factor");
// repeated evaluations now run compiled bytecode
```

- **`OFF`** (default): always interpreted.
- **`IMMEDIATE`**: compile on the first evaluation; if a later evaluation reveals a type assumption was wrong (e.g., a property that returned `Integer` now returns `Long`), it throws.
- **`MIXED`**: start interpreted, compile in the background after observing stable types, and *silently fall back* to interpreted if the compiled form hits a type mismatch — the safest mode for polymorphic data.

The critical constraint is that the compiler **infers types from the first evaluation(s)** and bakes them into the generated bytecode. If your expression operates on inputs whose runtime types vary, `IMMEDIATE` will fail and you must use `MIXED`. Also, not every expression is compilable (some constructs fall back to interpretation). The expert framing: SpEL compilation is a targeted optimization for expressions evaluated at very high frequency against type-stable inputs; for ordinary configuration-time expressions it buys nothing and adds complexity, so leave it `OFF` unless profiling proves SpEL evaluation is a hot spot.

#### Q57. [Theory] How does Spring read annotation metadata — standard reflection vs ASM — and why does the distinction matter for `@Conditional` and meta-annotations?

Spring has **two `AnnotationMetadata` implementations** and the choice between them is a deliberate, consequential design decision. `StandardAnnotationMetadata` uses **Java reflection** — it requires the `Class` to be *loaded* into the JVM. `SimpleAnnotationMetadata` (produced by the ASM-based `SimpleMetadataReader`) reads annotation information **directly from the `.class` bytecode** without loading the class. Both expose the same interface (`isAnnotated`, `getAnnotationAttributes`, `getMetaAnnotationTypes`, etc.), so the rest of Spring is agnostic to which one it's using.

Why have both? Because **component scanning and condition evaluation must inspect classes you may not want to load yet** — possibly classes whose dependencies aren't even on the classpath. Consider `@ConditionalOnClass(RedisTemplate.class)`: if Spring used reflection to check this, merely *loading the auto-configuration class* to read its annotations would trigger loading `RedisTemplate`, which throws `NoClassDefFoundError` if Redis isn't present — defeating the entire purpose of "only configure Redis if it's on the classpath." The ASM reader sidesteps this by reading the annotation's *string* references to types without resolving them, so the condition can evaluate against the *presence* of a class name rather than the loaded class.

```
@Configuration
@ConditionalOnClass(name = "org.springframework.data.redis.core.RedisTemplate")
class RedisAutoConfiguration { ... }
        │
   ASM reads bytecode → sees @ConditionalOnClass with type name (string)
        │
   condition checks: is that name loadable? → if NO, skip without ever loading it
```

This ASM-vs-reflection split is *the* enabling mechanism for Spring Boot's "configure X only if X is present" model and for **meta-annotation traversal** — Spring resolves that `@Service` is a `@Component`, or that a custom `@Transactional`-meta-annotated annotation implies transactions, by following annotation-on-annotation references in bytecode. The expert point: the metadata layer is what lets Spring make wiring decisions from *names and presence* rather than from *loaded types*, which is both a performance optimization and a correctness requirement for optional-dependency-driven configuration. The same machinery is why a missing transitive dependency surfaces as a clean "condition did not match" in the `/actuator/conditions` report instead of a classloading crash.

#### Q58. [Theory] Why doesn't Spring proxy-based AOP advise self-invocation, and what are all the ways to work around it? Analyze the trade-offs.

The root cause is structural, not a bug. With proxy-based AOP, the advice lives in a **wrapper object** (the JDK/CGLIB proxy) that holds a reference to the *raw* target. The container injects the *proxy* into collaborators, so external calls go `caller → proxy → (advice) → target`. But when a method inside the target calls another method on `this`, that call goes **directly to the raw target instance** — `this` is the unproxied object — so it never passes through the proxy and no advice (`@Transactional`, `@Cacheable`, `@Async`, custom aspects) runs.

```
External call:   client → [proxy: advice] → target.methodB()        ✅ advised
Self call:       target.methodA() → this.methodB()                  ❌ raw, no advice
                                    └── 'this' is the target, not the proxy
```

The workarounds, with trade-offs:

| Approach | How | Trade-off |
|---|---|---|
| **Refactor to another bean** | Move `methodB` to a separate `@Service` and inject it | Cleanest; respects the proxy boundary, but changes design |
| **Self-injection** | Inject the bean into itself (`@Lazy` to avoid cycle) and call `self.methodB()` | Works, but awkward and surprises readers |
| **`AopContext.currentProxy()`** | `((MyType) AopContext.currentProxy()).methodB()` | Requires `@EnableAspectJAutoProxy(exposeProxy = true)`; couples code to AOP and uses a `ThreadLocal` |
| **`TransactionTemplate` / programmatic** | Use the API directly instead of the annotation | No proxy at all; verbose but explicit and bypass-proof |
| **AspectJ load-time/compile-time weaving** | `@EnableLoadTimeWeaving` + agent | Advises self-calls because the advice is woven into bytecode, no proxy; build/agent complexity |

The expert synthesis: self-invocation isn't something to "fix" with a clever trick by default — its existence is a *signal* that two responsibilities (the orchestration method and the transactional/cached unit) belong in different beans. The **refactor** is almost always the right answer because it makes the transaction/cache boundary explicit and keeps the code understandable. `exposeProxy`/self-injection are pragmatic escape hatches when refactoring is genuinely impractical, and AspectJ weaving is the heavyweight option you adopt org-wide only when you have many such cases or need to advise non-method join points. Programmatic transaction/caching APIs are the bypass-proof fallback when annotation semantics are fighting you.

#### Q59. [Theory] How does `BeanDefinition` merging and the parent/child bean definition mechanism work? Why does it exist?

A `BeanDefinition` is the container's metadata record for a bean — its class, scope, constructor args, property values, autowire mode, init/destroy methods, and lazy/primary flags — created before any instance exists. Spring supports a **parent/child relationship between definitions**: a child definition names a `parent` and *inherits* its configuration, overriding or adding only what differs. At creation time Spring computes a **`RootBeanDefinition` (the "merged" definition)** by folding the child onto the parent, and that merged result is what actually drives instantiation.

```xml
<!-- the historical/explicit form -->
<bean id="baseService" abstract="true" class="com.acme.AbstractService">
    <property name="timeout" value="30"/>
    <property name="retries" value="3"/>
</bean>
<bean id="orderService" parent="baseService" class="com.acme.OrderService">
    <property name="timeout" value="10"/>   <!-- overrides parent; retries=3 inherited -->
</bean>
```

Why does this exist? Originally it was a **DRY mechanism for XML configuration** — share common property values, scope, and settings across many similar beans via an `abstract="true"` template definition (which is never instantiated itself, only inherited). It reduced repetition in the large XML files that dominated pre-annotation Spring.

In modern annotation/Java-config apps you rarely *author* parent definitions directly, but the **merging machinery is still central internally**, and that's the interview-worthy part. `getMergedBeanDefinition()` is called constantly: it's how `@Scope`, `@Lazy`, `@Primary`, and scanned metadata get resolved into the effective definition; how `BeanFactoryPostProcessor`s see a complete picture; and how framework code introspects beans (e.g., to find `@Bean` method metadata). When you write a custom `BeanFactoryPostProcessor` or a `BeanDefinitionRegistryPostProcessor` that programmatically tweaks definitions, you operate on these `BeanDefinition`/merged-definition objects. So even though parent/child XML inheritance is legacy, understanding that the container *always* works with a merged `RootBeanDefinition` explains how scattered metadata (annotations, scanning, post-processors) is reconciled into the single source of truth that instantiation reads.

#### Q60. [Theory] Compare imperative `@Transactional` with reactive transaction management at the internals level. Why can't they share a transaction manager or context propagation mechanism?

The two models are architecturally incompatible because they propagate context through **fundamentally different mechanisms**. Imperative transactions use `PlatformTransactionManager` and bind the connection/transaction state to the **thread** via `TransactionSynchronizationManager`'s `ThreadLocal`s. This works only because, in a blocking model, one logical operation stays on one thread from start to finish — so a `ThreadLocal` reliably represents "the current transaction."

Reactive code violates that assumption at its core: a single `Mono`/`Flux` pipeline **hops across threads** (event-loop workers, schedulers) between operators, so a `ThreadLocal` set at subscription time is meaningless by the time a downstream operator runs on a different thread. Reactive transactions therefore use `ReactiveTransactionManager` and propagate transaction state through the **Reactor `Context`** — an immutable key/value map that rides along the reactive subscription independent of which thread executes each step. The `TransactionalOperator` (or `@Transactional` on a method returning `Mono`/`Flux`) writes the transaction resource into that `Context`, and reactive data access (R2DBC, reactive Mongo) reads it from there.

```java
// Reactive: tx state travels in the Reactor Context, not a ThreadLocal
@Transactional
public Mono<Account> transfer(Long from, Long to, BigDecimal amt) {
    return accountRepo.debit(from, amt)
            .then(accountRepo.credit(to, amt))
            .thenReturn(...);   // commit when the returned publisher completes successfully
}
```

This is why mixing them silently fails: putting `@Transactional` (imperative) on a method that returns a `Mono` does nothing useful — the imperative interceptor binds a `ThreadLocal` and "commits" when the *method returns the publisher*, which is **before any reactive work runs**, so the transaction is already over by the time `debit`/`credit` execute on event-loop threads. You must use a `ReactiveTransactionManager` (e.g., `R2dbcTransactionManager`) and the transaction commits when the *returned publisher completes*, not when the method returns. The deeper expert point: the transaction boundary in reactive code is the lifecycle of the **publisher's subscription**, governed by Reactor `Context`, whereas in imperative code it's the lifecycle of the **method call on a thread**, governed by `ThreadLocal` — two different notions of "current," which is exactly why they need separate managers and cannot interoperate within one logical transaction. (Spring 6's `io.micrometer:context-propagation` library bridges `ThreadLocal`↔Reactor `Context` for *observability* data like tracing, but not for transaction resources.)

#### Q61. [Practical] You define two `@Bean` methods returning the same type and inject that type elsewhere with no qualifier. Walk through exactly how Spring decides, and what error you get if it can't.

This is the disambiguation algorithm interviewers love because it forces you to recite the resolution order precisely. When an injection point requires a single `T` and multiple candidate beans of type `T` exist, Spring resolves in this exact sequence:

1. **`@Primary`** — if exactly one candidate is marked `@Primary`, it wins immediately. (Two `@Primary` beans of the same type is itself an error: `NoUniqueBeanDefinitionException`.)
2. **`@Priority`** — if no `@Primary`, Spring picks the candidate with the highest priority (lowest `@Priority` value). Ties at the top priority are an error.
3. **Qualifier match** — a `@Qualifier("name")` at the injection point narrows to the matching bean (this normally happens *first* if present; without one we fall to the next step).
4. **Bean-name fallback** — Spring matches the **injection point's name** (field name, or parameter name if `-parameters` compilation is on) against the candidate bean names. So `private PaymentGateway stripeGateway` will match a bean named `stripeGateway`.
5. **Otherwise → fail** with `NoUniqueBeanDefinitionException`, listing all the candidate bean names.

```java
@Configuration
class Config {
    @Bean @Primary DataSource primaryDs() { ... }   // wins by @Primary
    @Bean DataSource replicaDs()           { ... }
}

@Service
class Repo {
    Repo(DataSource ds) { ... }   // injects primaryDs because it's @Primary
    // Without @Primary: would try param-name 'ds' → no bean named 'ds' → NoUniqueBeanDefinitionException
}
```

The practical wrinkle worth stating: the **bean name of a `@Bean` method defaults to the method name**, so `replicaDs()` registers a bean named `replicaDs`. That means you can disambiguate purely by **naming your injection target to match** — `DataSource replicaDs` parameter resolves to the `replicaDs()` bean via step 4, no annotation needed — though this is fragile (a rename silently changes wiring) and `@Qualifier` is clearer. The error itself, `NoUniqueBeanDefinitionException: expected single matching bean but found 2: primaryDs,replicaDs`, is diagnostic gold because it names the exact competing beans. The expert habit is to make multi-candidate situations *deliberate* with `@Primary` for the sensible default plus `@Qualifier` on the exceptions, rather than relying on the brittle name-matching fallback.

#### Q62. [Theory] What happens to a prototype-scoped bean's lifecycle, and why doesn't Spring call its destruction callback? How do you reclaim its resources?

Spring manages the **full lifecycle of singletons** but only a **partial lifecycle of prototypes**. For a prototype, the container instantiates it, populates dependencies, runs `BeanPostProcessor`s and initialization callbacks (`@PostConstruct`, `afterPropertiesSet`, custom init), and applies AOP — then **hands the instance to the caller and forgets about it**. Crucially, Spring does **not** track prototype instances and therefore **never invokes `@PreDestroy`/`DisposableBean.destroy()`/custom destroy-method** on them, even on context shutdown.

The reason is intrinsic to what "prototype" means: every request produces a new instance, the container has no way to know when (or whether) the caller is "done" with it, and holding references to every prototype it ever created would (a) defeat the purpose of a short-lived bean and (b) cause a memory leak — the container would pin objects that should be garbage-collected. So the contract is explicit: **the client is responsible for releasing prototype resources.** This is documented behavior, not an oversight.

```java
// If a prototype holds a resource, the CLIENT must close it.
@Bean @Scope("prototype")
FileExporter fileExporter() { return new FileExporter(); }  // opens a file handle

@Service
class ExportService {
    private final ObjectProvider<FileExporter> exporters;
    ExportService(ObjectProvider<FileExporter> p) { this.exporters = p; }

    void export(Data d) {
        FileExporter ex = exporters.getObject();   // fresh prototype
        try { ex.write(d); }
        finally { ex.close(); }                     // YOU release it — Spring won't
    }
}
```

If you genuinely need Spring to run a destruction callback for a short-lived bean, the right tools are: (1) a **custom `Scope`** that tracks instances and runs `registerDestructionCallback` at the appropriate boundary (e.g., request scope *does* destroy at request end), (2) wrapping the prototype acquisition in try-with-resources / `finally` as above, or (3) a `DestructionAwareBeanPostProcessor` if you must hook destruction logic. The expert nuance: people get burned when a prototype injects an expensive resource (a connection, a thread, a file handle) and assume Spring cleans it up like a singleton — it doesn't, and you get handle/connection leaks. Recognize that "prototype" means "Spring builds it, you own its death."

#### Q63. [Theory] How does Spring's `ConversionService` / type-conversion system work, and how does it relate to the legacy `PropertyEditor` mechanism?

When Spring injects a `@Value("${server.port}")` (a `String`) into an `int` field, or binds form/`@ConfigurationProperties` data into typed objects, something must convert `String → int`, `String → List<X>`, `String → Duration`, `String → enum`, etc. Spring has **two generations** of this machinery. The legacy mechanism is JavaBeans **`PropertyEditor`s** (`java.beans.PropertyEditor`) — single-direction `String ↔ Object` converters registered per `BeanWrapper`. Spring ships many (`CustomDateEditor`, `ClassEditor`) and lets you register your own via `PropertyEditorRegistrar` or `@InitBinder` in web controllers. Their limitations: they're not thread-safe (a new instance per binding), they're inherently `String`-centric, and they predate generics.

The modern mechanism (Spring 3+) is the **`ConversionService`** built on three SPIs: `Converter<S,T>` (simple one-way), `ConverterFactory<S, R>` (one source to a family of targets, e.g., `String → any Enum`), and `GenericConverter` (the most powerful — sees full `TypeDescriptor`s including generics and annotations, enabling `String → List<Integer>` or annotation-aware conversion). It's **thread-safe, type-pair-based, and generics-aware**, and it's what `@ConfigurationProperties` relaxed binding, SpEL, and `@Value` use under the hood. `DefaultConversionService` pre-registers a large set (numbers, collections, `Duration`/`Period`, `DataSize`, enums, `Charset`, etc.).

```java
@Component
class StringToMoneyConverter implements Converter<String, Money> {
    @Override public Money convert(String source) {
        return Money.parse(source);          // "USD 9.99" → Money
    }
}
// Registered automatically if a ConversionService bean picks it up; then:
@Value("${product.price}")   // "USD 9.99" String property
private Money price;          // converted via the Converter
```

The relationship and interview takeaway: the two systems **coexist** — Spring's `BeanWrapperImpl` can use both, and the `ConversionService` is consulted alongside registered `PropertyEditor`s. For new code you write `Converter`/`GenericConverter` and register them on a `ConversionService` (or expose them as beans for Boot to auto-register); you reach for `PropertyEditor`/`@InitBinder` mainly in legacy MVC binding scenarios or when integrating with code that expects the JavaBeans API. The expert point is recognizing that all those "magic" conversions — `String` properties becoming `Duration`, comma-separated values becoming `List`, names becoming enums — flow through this unified, extensible, thread-safe `ConversionService`, and that you extend it declaratively with a `Converter` bean rather than fighting it.

#### Q64. [Theory] What is the difference between `@Value` injection and `@ConfigurationProperties` binding, and why does Boot recommend the latter for grouped config?

`@Value` injects a *single* property (or SpEL expression) into a *single* field at a time. It resolves at the moment the bean is created, supports SpEL (`#{...}`), and is convenient for one-off values. `@ConfigurationProperties` instead binds an *entire tree* of properties sharing a prefix onto a strongly-typed object via Spring Boot's **relaxed binding** and the `ConversionService` — it's designed for grouped, structured configuration.

```java
@ConfigurationProperties(prefix = "mail")
public record MailProperties(
        String host,
        int port,
        @DefaultValue("5s") Duration timeout,
        List<String> bccAddresses,
        Tls tls) {
    public record Tls(boolean enabled, String trustStore) {}
}
// binds mail.host, mail.port, mail.timeout, mail.bcc-addresses[0..], mail.tls.enabled, ...
```

The differences that matter at staff level: (1) **Relaxed binding** — `@ConfigurationProperties` matches `mail.bcc-addresses`, `MAIL_BCCADDRESSES`, `mail.bccAddresses`, and `mail.bcc_addresses` all to the same field; `@Value("${mail.bcc-addresses}")` requires the *exact* key and won't honor kebab/env-var variants. (2) **Structured binding** — it binds lists, maps, nested objects, and `Duration`/`DataSize` types via the conversion system; `@Value` can only do that with awkward SpEL. (3) **Validation** — annotate the class `@Validated` and use JSR-380 (`@NotNull`, `@Min`) to fail fast at startup; `@Value` has no such hook. (4) **Metadata & IDE support** — `@ConfigurationProperties` generates `spring-configuration-metadata.json` (with `spring-boot-configuration-processor`), giving autocompletion and documentation in `application.yml`. (5) **No SpEL** — a deliberate restriction, because these are meant to be pure externalized config, not computed expressions.

The recommendation flows from cohesion and testability: grouped config in one typed, validated, immutable record is far more maintainable than a dozen `@Value` fields scattered across classes, and the binding is environment-friendly (env vars override cleanly via relaxed binding). Reserve `@Value` for genuinely standalone values or where you need a SpEL expression; use `@ConfigurationProperties` for any cohesive group of settings — which is also why every Spring Boot starter exposes its config as `*Properties` classes rather than loose `@Value`s.

#### Q65. [Theory] How does `@EventListener`'s `condition` attribute and conditional/asynchronous event handling work under the hood?

`@EventListener` is processed by `EventListenerMethodProcessor` (a `SmartInitializingSingleton`), which after all singletons are created scans every bean for `@EventListener` methods and registers an `ApplicationListenerMethodAdapter` for each with the `ApplicationEventMulticaster`. The adapter inspects the method's parameter type to determine which event class it handles, supporting generic event types via `ResolvableType` (so `@EventListener` on `void on(OrderEvent<Refund> e)` can match by payload generic).

The `condition` attribute is a **SpEL expression evaluated against the event** before the listener fires — the listener runs only if it returns `true`. The evaluation context exposes the event itself, its properties, the method arguments (`#root.args`), and `#event`/`#args` aliases, letting you filter declaratively without an `if` inside the method body:

```java
@EventListener(condition = "#event.priority == T(com.acme.Priority).HIGH")
public void onHighPriority(AlertEvent event) { page(event); }

@TransactionalEventListener(phase = AFTER_COMMIT)
@Async                                   // requires @EnableAsync
public void onCommitted(OrderPlacedEvent e) { analytics.record(e); }
```

The internals that catch people: (1) **Synchronous by default** — the multicaster invokes listeners on the *publisher's thread* in registration order, so a slow or throwing listener blocks/aborts the publisher; add `@Async` (which routes through the async executor) for isolation, but then you lose the publisher's transaction and exception propagation. (2) A listener can **return a value**, and a non-null return is *re-published* as a new event — a chaining feature that surprises people who return something incidentally. (3) `@TransactionalEventListener` combines with `condition`, and by default if there's *no* active transaction the listener silently doesn't fire (`fallbackExecution=false`) — a common "my AFTER_COMMIT listener never runs" bug when the publishing method wasn't actually transactional. (4) Exceptions from synchronous listeners propagate to the publisher and can roll back the transaction, whereas `@Async` listener exceptions go to the `AsyncUncaughtExceptionHandler` and are invisible to the publisher. Understanding that the in-process event bus is synchronous, transaction-aware, and SpEL-filtered — but *not* durable — is what guides when to use it versus a real broker.

#### Q66. [Theory] What is `@Primary` versus `@Fallback` (Spring 6.2), and how does the candidate-resolution model change with fallback beans?

`@Primary` designates a bean as the **preferred** candidate when multiple match an injection point — it actively *wins* disambiguation. Spring Framework **6.2** introduced the complementary `@Fallback` annotation, which marks a bean as a **lower-priority default that only applies when no non-fallback candidate exists**. The two solve opposite halves of the same problem: `@Primary` says "prefer me even among equals," `@Fallback` says "use me only if nobody better is around."

```java
@Bean @Fallback                       // default, used only if no other CacheManager bean exists
CacheManager noOpCacheManager() { return new NoOpCacheManager(); }

@Bean @ConditionalOnClass(Caffeine.class)
CacheManager caffeineCacheManager() { ... }   // a "real" (non-fallback) candidate
```

The resolution model change: when Spring resolves a single injection point, fallback-marked beans are **excluded from candidate consideration if at least one non-fallback candidate exists**. Only when *every* matching bean is a fallback (or there's exactly one fallback and no regular beans) does a fallback get injected. This inverts the `@Primary` mental model — `@Primary` competes and wins among real candidates, while `@Fallback` steps aside the moment a real candidate shows up.

Why does this matter and why was it added? It cleans up a long-standing auto-configuration awkwardness. Previously, library authors used `@ConditionalOnMissingBean` to provide defaults that back off when the user defines their own bean — but that's a *configuration-class* condition, evaluated at definition time and order-sensitive. `@Fallback` moves the "I'm only a default" semantics to the **injection-resolution** layer, so a fallback bean can coexist in the context yet automatically yield to any real candidate at the point of use, without `@ConditionalOnMissingBean` gymnastics or ordering concerns. The expert framing: `@Primary` and `@Fallback` are duals — one elevates a winner among peers, the other demotes a safety-net default — and Spring 6.2 giving fallback first-class status reflects how much of modern Spring is about graceful "provide a default unless overridden" composition.

#### Q67. [Practical] A bean needs the `ApplicationContext` (or its bean name, or the `Environment`). Compare the `*Aware` interfaces with injection, and explain when `Aware` is still justified.

The `*Aware` interfaces (`ApplicationContextAware`, `BeanNameAware`, `BeanFactoryAware`, `EnvironmentAware`, `ResourceLoaderAware`, `ApplicationEventPublisherAware`, etc.) are callback hooks: implement one and Spring invokes its setter during the **Aware-callbacks phase** of bean creation (step 5 in the lifecycle, before initialization callbacks), handing you the requested infrastructure object. They predate the era when these objects became injectable beans.

```java
// Old style — Aware interface
@Component
class LegacyService implements ApplicationContextAware, BeanNameAware {
    private ApplicationContext ctx;
    private String beanName;
    @Override public void setApplicationContext(ApplicationContext c) { this.ctx = c; }
    @Override public void setBeanName(String name) { this.beanName = name; }
}

// Modern style — just inject them, they're beans
@Component
class ModernService {
    ModernService(ApplicationContext ctx, Environment env,
                  ApplicationEventPublisher publisher) { /* ... */ }
}
```

For most cases, **prefer injection**: `ApplicationContext`, `Environment`, `ApplicationEventPublisher`, `ResourceLoader`, and `BeanFactory` are all injectable, which keeps the class free of framework callback interfaces, makes constructor injection (and thus testing) straightforward, and reads better. Implementing `ApplicationContextAware` couples you to Spring's API and forces a setter, working against immutability.

But `Aware` is still justified in specific cases: (1) **`BeanNameAware`** — the bean's *own* name isn't injectable, so this is the only clean way to learn it (useful in framework/infrastructure beans that self-register or log by name). (2) **Inside `BeanPostProcessor`s or other infrastructure beans** that run early in the lifecycle, where injection of certain context objects isn't yet wired but the Aware callback is guaranteed to fire at the right phase. (3) When you're authoring a **framework/library component** that must work across Spring versions and wants the explicit, ordered callback contract. The expert nuance: reaching for `ApplicationContextAware` in *application* code is usually a smell signaling a service-locator anti-pattern (pulling beans imperatively instead of declaring dependencies) — the legitimate uses are narrow and mostly infrastructural, with `BeanNameAware` being the one that has no injection equivalent.

#### Q68. [Theory] Explain `@Transactional(readOnly = true)`. Is it just a hint, and what concrete effects does it have across JDBC, JPA, and the database?

`readOnly = true` is **partly a hint and partly enforced**, and the layered answer is what interviewers want. At the Spring level it sets a flag in the transaction definition that propagates down through several layers, each of which may or may not act on it:

- **JPA / Hibernate (the most impactful):** Hibernate switches the session's `FlushMode` to `MANUAL`/`NEVER`, meaning it **skips dirty-checking and won't auto-flush** changes at commit. This is a real performance win on read paths — Hibernate doesn't snapshot entities for change detection — and it also means accidental modifications won't be persisted (a soft guardrail, though not a hard prohibition). This is the single biggest reason to mark read methods `readOnly`.
- **JDBC / Connection:** Spring calls `Connection.setReadOnly(true)`, which is a **hint to the JDBC driver/database**. Some databases/drivers optimize (route to a replica, take lighter locks, skip undo-log bookkeeping); others **ignore it entirely**. PostgreSQL with `default_transaction_read_only` can actually reject writes; many setups do nothing.
- **Routing:** with an `AbstractRoutingDataSource` or read/write splitting, the `readOnly` flag (queryable via `TransactionSynchronizationManager.isCurrentTransactionReadOnly()`) is commonly used to **route reads to a replica** datasource — a very common production pattern.

```java
@Transactional(readOnly = true)            // Hibernate skips dirty checking + flush
public List<OrderView> recentOrders() {
    return orderRepo.findTop100ByOrderByCreatedDesc().stream().map(OrderView::of).toList();
}
```

So is it "just a hint"? At the **JDBC connection level, largely yes** — enforcement depends on driver/DB. At the **ORM level, no** — Hibernate concretely changes flush behavior, which has measurable performance and correctness effects. The expert points: (1) marking query methods `readOnly = true` is a cheap, high-value optimization specifically for JPA-backed reads; (2) don't *rely* on it to prevent writes — it's not a security boundary, since the DB may ignore the connection flag and even Hibernate's manual flush can be forced; (3) it's the idiomatic signal for replica routing. And the usual proxy caveat applies — it only takes effect through the proxy, so a self-invoked read method gets no `readOnly` semantics at all.

#### Q69. [Theory] How does Spring AOT processing transform the application at build time, and what survives into the runtime versus what is eliminated? Contrast with JIT/reflection-based startup.

Spring 6 / Boot 3 **AOT (Ahead-Of-Time) processing** runs a build-time step that *executes part of the container's reasoning early* and emits generated Java source plus metadata, so the runtime does far less reflective work. Concretely, the `ApplicationContextAotGenerator` performs a "dry-run refresh": it processes `@Configuration` classes, evaluates `@Conditional`s, resolves the bean graph, and then **generates `BeanFactoryInitializationAotContribution`s** — Java code that registers each bean definition *imperatively and explicitly* (no scanning, no condition evaluation at runtime). It also pre-generates CGLIB proxy classes and emits GraalVM **reachability metadata** (`reflect-config.json`, `resource-config.json`, `proxy-config.json`) describing every reflective access, resource load, and proxy the app needs.

```
Build time (AOT):
  scan + parse @Configuration + evaluate @Conditional + plan bean graph
        │
        ▼  generates
  Xxx__BeanDefinitions.java  (explicit registerBeanDefinition calls)
  Xxx__BeanFactoryRegistrations.java
  reflect-config.json / resource-config.json / proxy-config.json  (GraalVM hints)
        │
Runtime (AOT mode or native image):
  load generated code → register beans directly → NO scanning, NO @Conditional eval
```

**What is eliminated at runtime:** classpath scanning, `@Configuration` parsing, `@Conditional` evaluation, and runtime CGLIB code generation — all of which are normally significant fractions of startup. **What survives:** the *resulting* bean instances, their wiring, and any genuinely runtime-dynamic behavior the generated code preserves; but the bean *graph topology is frozen at build time* — the set of beans and which conditions matched is decided then and cannot change based on the runtime environment.

Contrast with the conventional model: a JIT/reflection-based Spring Boot startup does all of that work *every* launch — scanning bytecode with ASM, evaluating dozens of conditions, generating proxy classes via CGLIB on the fly, and resolving the graph through reflection — paying the cost on each cold start. AOT shifts that one-time-deducible work to the build. On the **JVM**, AOT alone (without native image) already trims startup meaningfully and is a prerequisite for native; with **GraalVM native image** you additionally get closed-world AOT compilation to a standalone binary with sub-100ms startup and ~2-5× lower memory. The trade-offs are the closed-world constraints: no runtime bean registration, profiles fixed at build time, and every reflective path needing explicit hints (via `RuntimeHintsRegistrar`/`@RegisterReflectionForBinding`) or it fails at runtime instead of degrading. The expert synthesis: AOT doesn't make Spring "faster" so much as it *relocates* the deductive work from runtime to build time, trading dynamism and build complexity for startup latency and memory — which is exactly the right trade for serverless/edge and the wrong one for highly dynamic, profile-switching deployments.

## 🧩 Extended Questions — Set 2: Practical, Operational & Troubleshooting

### 🟢 Basic — extended

#### Q70. [Practical] Your app fails to start with `NoSuchBeanDefinitionException: No qualifying bean of type 'X'`. How do you systematically diagnose it?

This is the single most common Spring startup failure, and a methodical checklist beats guessing. The exception means Spring needed to inject a bean of type `X` but found *zero* candidates. Work through the causes in order of frequency:

1. **The class isn't being scanned.** `@ComponentScan` (or `@SpringBootApplication`) scans the package of the annotated class *and below*. If `X`'s package is a sibling or above the main class's package, it's invisible. Fix: move the class under the main package, or add the package to `scanBasePackages`. This is the #1 cause in multi-module projects where the bean lives in a library jar.
2. **The class isn't a bean at all** — you forgot `@Component`/`@Service`/`@Repository`, or it's only registered via a `@Bean` method in a `@Configuration` class that itself isn't scanned.
3. **A `@Conditional` excluded it** — for auto-configured beans, a `@ConditionalOnProperty`/`@ConditionalOnClass` may not match. Run with `--debug` and read the **condition evaluation report** ("Negative matches").
4. **Wrong profile active** — the bean is `@Profile("prod")` but you're running `dev`.
5. **You're injecting the concrete class but only the interface is a bean** (or vice versa), or a generics mismatch (`Repository<Order>` vs `Repository<Customer>`).

```bash
# Fastest first move: ask the running context what it actually has.
# Add a temporary CommandLineRunner or hit the actuator beans endpoint.
curl localhost:8080/actuator/beans | jq '.contexts.application.beans | keys'
```

The startup error message itself is improved in Boot 3: it prints an **"Action"** section ("Consider defining a bean of type 'X' in your configuration") and lists candidate beans when it's an ambiguity rather than absence. The practical habit is to *read the whole stack trace top to bottom* — the relevant line is usually the `Parameter N of constructor in com.acme.SomeService required a bean of type ... that could not be found`, which names both the consumer and the missing type, collapsing the search instantly.

#### Q71. [Practical] How do you externalize configuration so the *same* jar runs unchanged across dev, staging, and prod? Walk through a concrete setup.

The 12-factor principle is **one immutable artifact, environment-specific configuration injected from outside**. You never rebuild the jar per environment; you change what the environment hands it. Spring Boot makes this work through the property-source precedence chain — externally supplied values (env vars, command-line args) override anything packaged inside.

```yaml
# application.yml  (packaged, safe defaults only — NO secrets)
spring:
  application:
    name: orders
server:
  port: 8080
app:
  feature:
    new-checkout: false

---
# application-prod.yml  (still packaged, but prod-shaped non-secret config)
spring:
  config:
    activate:
      on-profile: prod
app:
  feature:
    new-checkout: true
```

```bash
# At deploy time the platform injects environment + secrets:
SPRING_PROFILES_ACTIVE=prod \
SPRING_DATASOURCE_URL=jdbc:postgresql://prod-db:5432/orders \
SPRING_DATASOURCE_PASSWORD=$(vault kv get -field=pw secret/orders/db) \
java -jar orders.jar
```

The key mechanics: profile selection via `SPRING_PROFILES_ACTIVE`, relaxed binding so `SPRING_DATASOURCE_URL` (env var) maps to `spring.datasource.url`, and **secrets that never live in any committed file** — they come from a secret manager (Vault, AWS Secrets Manager, Kubernetes Secrets) at runtime. For Kubernetes you mount config via `ConfigMap` (non-secret) and `Secret` (secret) as env vars or files, optionally using `spring.config.import=configtree:/etc/secrets/`. The payoff is auditability and safety: the artifact in your registry is identical across environments, so "it worked in staging" actually means something, and a leaked jar contains no credentials. The anti-pattern this replaces is per-environment builds or committed `application-prod.yml` files full of real passwords.

#### Q72. [Practical] A developer reports "my `@Value` is null / has the wrong value." What are the likely causes and how do you confirm?

`@Value` problems almost always come down to *timing*, *scope of the bean*, or *the property source*, and a few quick checks isolate which. The likely causes:

1. **`@Value` on a field read in the constructor.** Field injection (including `@Value`) happens *after* the constructor runs, so reading the field in the constructor sees `null`/`0`. Fix: inject the value as a **constructor parameter** (`MyBean(@Value("${x}") String x)`), which is also the recommended style.
2. **The property doesn't exist and there's no default.** `@Value("${app.x}")` with no `app.x` defined throws `IllegalArgumentException: Could not resolve placeholder` at startup (good — fail fast). But `@Value("${app.x:}")` with an empty default silently injects `""`, masking a typo. Always question whether a default is hiding a missing key.
3. **The bean isn't Spring-managed.** If you `new MyBean()` yourself, no injection happens and `@Value` is never processed — the field stays at its Java default.
4. **Wrong property source / precedence.** Something higher in the chain (env var, command-line) overrides the file you edited. Confirm via `/actuator/env`.
5. **`@Value` in a non-singleton or in a class instantiated before the placeholder configurer runs** (rare, but happens with custom `BeanFactoryPostProcessor`s).

```java
// BUG: field is null here
@Component
class Bad {
    @Value("${app.region}") private String region;
    Bad() { System.out.println(region); }   // null — injection hasn't happened yet
}

// FIX: constructor parameter — value is present before the body runs
@Component
class Good {
    private final String region;
    Good(@Value("${app.region}") String region) { this.region = region; }
}
```

To **confirm the actual resolved value**, hit `/actuator/env/app.region` (Boot prints the value *and* which property source won) or temporarily log it from a `@PostConstruct` (where injection is guaranteed complete) rather than the constructor. Ninety percent of "my `@Value` is wrong" tickets are either the constructor-timing bug or a higher-precedence override the developer didn't know about.

#### Q73. [Practical] How do you write an integration test that loads a Spring context, and how do you keep the test suite fast as it grows?

For an integration test you annotate the test class with `@SpringBootTest` (full context) or a **slice** annotation (`@WebMvcTest`, `@DataJpaTest`, `@JsonTest`) that loads only the relevant layer. The single most important performance lever is **context caching**: Spring's `TestContext` framework caches an `ApplicationContext` keyed by its *configuration* (the set of config classes, active profiles, properties, mock bean definitions, etc.). Tests that share an identical configuration **reuse the same context** instead of paying startup again.

```java
@SpringBootTest                          // full context, cached and shared across tests
class OrderServiceIT {
    @Autowired OrderService service;
    @Test void placesOrder() { /* ... */ }
}

@DataJpaTest                             // slice: only JPA + an embedded DB, much faster
class OrderRepositoryTest {
    @Autowired OrderRepository repo;
    @Test void findsByStatus() { /* ... */ }
}
```

The thing that *silently destroys* suite performance is **cache fragmentation**: every distinct configuration spawns a *new* cached context. The usual culprit is `@MockBean`/`@SpyBean` — each unique set of mocked beans produces a different cache key, so a context can't be reused across classes that mock different things, and Spring even *evicts and rebuilds* contexts when the cache fills (default capacity is 32). Other fragmenters: `@TestPropertySource` with per-class values, `@ActiveProfiles` differing across classes, and `@DirtiesContext` (which forcibly discards the context — use it sparingly, it's the nuclear option).

Practical guidance for a fast suite: (1) use **slices** instead of `@SpringBootTest` wherever possible; (2) **standardize** the small number of base configurations so contexts are shared (a couple of common `@SpringBootTest` setups, not dozens of bespoke ones); (3) prefer **Testcontainers with a static, shared container** (or a singleton pattern) over per-test databases; (4) avoid `@DirtiesContext` unless a test genuinely mutates global state; (5) replace `@MockBean` with constructor-injected hand-written test doubles in pure unit tests so they need no context at all. A suite that respects context caching can run hundreds of integration tests reusing a handful of contexts, versus one that rebuilds the world per class and takes 20× longer.

### 🟡 Intermediate — extended

#### Q74. [Practical] Production alert: `HikariPool-1 - Connection is not available, request timed out after 30000ms`. How do you diagnose and resolve connection-pool exhaustion?

This is one of the most common production Spring incidents, and it almost never means "the pool is too small" as the *root* cause. It means connections are being **borrowed faster than they're returned** — something is holding connections too long. Diagnose in this order:

1. **Confirm the symptom with metrics.** Hikari exposes `hikaricp.connections.active`, `.pending`, `.usage` via Micrometer/Actuator. If `active` is pinned at `maximumPoolSize` and `pending` is climbing, the pool is saturated. Enable `spring.datasource.hikari.leak-detection-threshold=20000` — Hikari then logs a stack trace of any connection held longer than 20s, which usually points *directly* at the offending code.
2. **Find the long holders.** Common causes: a `@Transactional` method that makes a **slow remote call (HTTP/RPC) while holding the DB connection** — the connection is pinned for the whole call; an unbounded query streaming a huge result set; a missing index turning a query into a table scan; or `REQUIRES_NEW` nesting that consumes *two* connections per request (outer + inner) and deadlocks the pool under load.
3. **Check for leaks** — code paths that open a connection (or `EntityManager`) outside Spring's management and never close it.

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 20          # NOT "bigger is better" — see below
      connection-timeout: 30000
      leak-detection-threshold: 20000
      max-lifetime: 1800000          # recycle before DB-side idle timeout
```

The resolution is usually **shortening the time connections are held**, not enlarging the pool: move remote calls *outside* the transaction, add the missing index, paginate, and avoid `REQUIRES_NEW` on hot paths. Pool *sizing* follows the formula popularized by Hikari — for many workloads `connections ≈ (core_count * 2) + effective_spindle_count`; a pool of 200 is almost always wrong because the database becomes the bottleneck and context-switching kills throughput. Also verify `maximumPoolSize` × instance-count doesn't exceed the database's `max_connections`. The expert framing: pool exhaustion is a *latency* problem masquerading as a *capacity* problem — fix what holds connections, then size the pool to the database's real concurrency limit.

#### Q75. [Practical] Under load you see intermittent data corruption / weird `ThreadLocal` values bleeding across requests. How do you find and fix the leak?

`ThreadLocal` bleed in a Spring app is a classic concurrency incident with a specific mechanism: **application servers reuse threads from a pool**, so if request A sets a `ThreadLocal` and never clears it, request B *on the same recycled thread* sees A's stale value. Symptoms include one user seeing another's data, wrong tenant/security context, or MDC log fields attributed to the wrong request — and it's intermittent because it only manifests when a thread happens to be reused before the value is cleared.

```java
// BUG: tenant set per request but never cleared → leaks to the next request on this thread
public class TenantFilter implements Filter {
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        TenantContext.set(resolveTenant(req));   // ThreadLocal.set
        chain.doFilter(req, res);                 // if this throws, finally never runs below
    }
}

// FIX: always clear in finally
public class TenantFilter implements Filter {
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        try {
            TenantContext.set(resolveTenant(req));
            chain.doFilter(req, res);
        } finally {
            TenantContext.clear();                // ALWAYS, even on exception
        }
    }
}
```

How to find it: reproduce under concurrency (a load test hitting endpoints with distinguishable per-user data and asserting isolation), and audit every `ThreadLocal.set` for a matching `clear()` in a `finally`. Spring's own request-scoped beans and `RequestContextHolder` are managed correctly by the framework, but **your** custom `ThreadLocal`s (tenant, correlation id, security additions, MDC) are your responsibility. The fixes: (1) clear in `finally` in a `Filter`/`HandlerInterceptor`; (2) for thread-pool executors (`@Async`, `CompletableFuture`), use a `TaskDecorator` that *copies on submit and clears on completion*, because the work runs on a different pooled thread that won't see the request-thread's `ThreadLocal` at all; (3) prefer request-scoped beans or passing context explicitly over raw `ThreadLocal` when feasible. The deeper lesson: `ThreadLocal` + thread pools is a leak waiting to happen, and the discipline of "set in try, clear in finally, copy across executor boundaries" is non-negotiable.

#### Q76. [Practical] An aspect (`@Transactional`/`@Cacheable`/custom `@Around`) "isn't firing." Give a step-by-step diagnostic procedure that distinguishes the possible causes.

"My annotation isn't doing anything" has a handful of root causes, and you can bisect them quickly. Procedure:

1. **Is the bean actually a proxy?** Inject it and check `AopUtils.isAopProxy(bean)` / `AopUtils.isCglibProxy(bean)`. If it returns `false`, the bean was never proxied — either it's not Spring-managed (`new`-ed somewhere) or no advisor matched it.
2. **Self-invocation?** Is the annotated method being called from *another method in the same class*? Internal `this.method()` calls bypass the proxy entirely. This is by far the most common cause for all three (`@Transactional`, `@Cacheable`, `@Async`).
3. **Is the method `public`?** Proxy-based AOP only advises `public` methods (transactions silently ignore non-public; CGLIB can't override `private`/`final`).
4. **Is the feature enabled?** `@EnableTransactionManagement`/`@EnableCaching`/`@EnableAspectJAutoProxy` (auto-enabled by Boot starters, but easy to miss in a non-Boot or sliced setup). Is there a `TransactionManager`/`CacheManager` bean?
5. **Pointcut mismatch** (custom aspects) — your `execution(...)`/`@annotation(...)` expression doesn't match the actual signature/package. Turn on `logging.level.org.springframework.aop=TRACE`.
6. **Wrong import / annotation** — e.g., importing `javax`/`jakarta.transaction.Transactional` vs `org.springframework.transaction.annotation.Transactional` (both work but have *different* rollback rules and attributes).

```java
@Service
class DiagnosticService {
    @Autowired ApplicationContext ctx;
    @PostConstruct void check() {
        Object bean = ctx.getBean(OrderService.class);
        System.out.println("isAopProxy=" + AopUtils.isAopProxy(bean)
                + " cglib=" + AopUtils.isCglibProxy(bean));
    }
}
```

The decisive split is step 1 vs step 2: if `isAopProxy` is `false`, the problem is *creation* (not managed / not matched / feature disabled); if it's `true` but the advice still doesn't run, the problem is almost certainly *invocation* (self-call or non-public). That single check collapses the search space immediately, and it's far faster than randomly adding annotations and redeploying.

#### Q77. [Practical] How do you implement graceful shutdown so in-flight requests complete and resources close cleanly when Kubernetes sends `SIGTERM`?

Graceful shutdown matters because an abrupt kill drops in-flight requests (5xx errors to users), can leave transactions half-applied, and skips resource cleanup. Spring Boot 2.3+ has **built-in graceful shutdown** that you enable in config; the container stops accepting new requests but lets active ones finish within a timeout.

```yaml
server:
  shutdown: graceful              # stop accepting new connections, drain in-flight
spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s   # max time to wait for the drain
```

The full picture is a **handshake between the orchestrator and the app**. On `SIGTERM`, the JVM runs Spring's shutdown hook → `ApplicationContext.close()` → `SmartLifecycle.stop()` callbacks (in reverse phase order) → the web server enters graceful mode and waits for active requests → then singleton `@PreDestroy`/`DisposableBean` run → connection pools, executors, and clients close. For this to work in Kubernetes you must also account for **load-balancer propagation lag**: when a pod is terminating, the Endpoints/iptables update isn't instantaneous, so for a brief window traffic still arrives at a pod that has stopped accepting. The standard fix is a `preStop` hook that sleeps a few seconds before the app starts shutting down:

```yaml
lifecycle:
  preStop:
    exec:
      command: ["sh", "-c", "sleep 5"]   # let the LB stop routing before we drain
terminationGracePeriodSeconds: 45        # MUST exceed sleep + Spring shutdown timeout
```

For custom long-running components, implement `SmartLifecycle` and do the draining/closing in `stop()` (use the `stop(Runnable callback)` overload for asynchronous shutdown, calling the callback when done so Spring waits correctly). The expert checklist: enable `server.shutdown=graceful`, set `timeout-per-shutdown-phase` below the pod's `terminationGracePeriodSeconds`, add a `preStop` sleep to cover LB propagation, ensure background pollers/consumers stop cleanly via `SmartLifecycle`, and verify with a rolling deploy under load that error rate stays at zero. The most common mistake is `terminationGracePeriodSeconds` being *shorter* than the app's drain timeout, so Kubernetes `SIGKILL`s mid-drain and you get the very errors graceful shutdown was supposed to prevent.

#### Q78. [Practical] Two property files seem to conflict and the "wrong" value wins. How do you debug property resolution in a running Boot app?

When a property resolves to an unexpected value, the cause is always **precedence** — some `PropertySource` higher in the ordered chain shadows the one you edited. Rather than reasoning about the documented order from memory, *ask the running app*, which knows the truth for this exact launch.

```bash
# Which value won, and from WHICH source?
curl localhost:8080/actuator/env/server.port
# → shows the resolved value plus every source that defines it, top source first

# Dump the whole environment with all property sources in precedence order
curl localhost:8080/actuator/env | jq '.propertySources[].name'
```

The `/actuator/env/{key}` endpoint is the decisive tool: it prints the property's value and lists *every* source that provides it, in precedence order, so you immediately see that, say, `OS environment variables` (`SERVER_PORT=9000`) is overriding your `application.yml` (`server.port=8080`). The common surprises it reveals: (1) an **environment variable** set in the deployment shadows the file — and relaxed binding means `SERVER_PORT` matches `server.port` even though they look different; (2) a **command-line arg** (`--server.port=...`) beats everything packaged; (3) a **profile-specific file** (`application-prod.yml`) overrides the base `application.yml` when that profile is active; (4) a `@TestPropertySource` overriding values in tests.

For build-time/startup debugging without actuator, run with `--debug` (prints the condition report, which interacts with `@ConditionalOnProperty`) or set `logging.level.org.springframework.boot.context.config=TRACE` to log config-file loading and which profiles activated which files. The mental model to internalize: properties live in an **ordered list and the first match wins**, so "conflict" is the wrong frame — there's a deterministic winner, and the question is always "what's sitting *above* my file in the chain?" The `/actuator/env` endpoint answers that in one call instead of guesswork.

#### Q79. [Practical] How do you tune the thread pool for `@Async`/Spring MVC, and what goes wrong with the defaults in production?

The defaults are dangerous and tuning is workload-specific, so this question separates people who've run Spring in production from those who haven't. The headline trap: **`@EnableAsync` with no `TaskExecutor` bean uses `SimpleAsyncTaskExecutor`, which creates a brand-new thread for every task and never pools them** — under load that spawns unbounded threads, exhausts memory, and can take the JVM down. You almost always must define your own `ThreadPoolTaskExecutor`.

```java
@Configuration @EnableAsync
class AsyncConfig {
    @Bean("ioExecutor")
    public ThreadPoolTaskExecutor ioExecutor() {
        var ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(16);                    // sized to workload, see below
        ex.setMaxPoolSize(32);
        ex.setQueueCapacity(200);                  // bounded — NEVER leave unbounded
        ex.setThreadNamePrefix("io-");
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        ex.setWaitForTasksToCompleteOnShutdown(true);   // drain on shutdown
        ex.setAwaitTerminationSeconds(30);
        ex.initialize();
        return ex;
    }
}
```

The mechanics that bite people: a `ThreadPoolExecutor` grows to `corePoolSize`, then **fills the queue before creating threads up to `maxPoolSize`** — so with a large `queueCapacity`, `maxPoolSize` is effectively never reached and tasks just back up in the queue (latency, not parallelism). An **unbounded queue** (the JDK default `LinkedBlockingQueue`) means tasks queue forever and you get OOM and unbounded latency instead of backpressure. The `RejectedExecutionHandler` matters: `AbortPolicy` (default) throws when saturated; `CallerRunsPolicy` applies natural backpressure by running the task on the submitting thread, which is usually the safer production choice. Sizing: **CPU-bound** work wants roughly `cores + 1` threads; **I/O-bound** work wants more (use Little's Law: threads ≈ target throughput × average latency), but bounded so a downstream slowdown can't spawn infinite threads.

For **Spring MVC's** own request-handling pool (Tomcat), tune `server.tomcat.threads.max` (default 200) and `accept-count`; an oversized pool just shifts the bottleneck to the DB connection pool or downstream service and increases context-switching. On Java 21+, **virtual threads** (`spring.threads.virtual.enabled=true`) change the calculus for I/O-bound work — you stop pooling and let each request own a cheap virtual thread, removing the pool-sizing headache for blocking I/O (but watch for `synchronized` pinning and unbounded concurrency to downstreams). Always set `setWaitForTasksToCompleteOnShutdown(true)` so in-flight async work drains on shutdown, and decorate the executor with a `TaskDecorator` if you need MDC/security context to cross the thread boundary.

#### Q80. [Practical] Logs from concurrent requests are interleaved and unattributable. How do you propagate a correlation/trace id through Spring, including across `@Async` and thread pools?

Without per-request context in logs, debugging a production incident across interleaved concurrent requests is nearly impossible. The standard solution is **MDC (Mapped Diagnostic Context)** — a `ThreadLocal` map (in SLF4J/Logback) whose keys you reference in the log pattern, so every log line automatically carries the correlation id.

```java
// 1. Populate MDC at the edge (filter), clear in finally
@Component
class CorrelationFilter extends OncePerRequestFilter {
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        String cid = Optional.ofNullable(req.getHeader("X-Correlation-Id"))
                             .orElse(UUID.randomUUID().toString());
        MDC.put("cid", cid);
        res.setHeader("X-Correlation-Id", cid);
        try { chain.doFilter(req, res); }
        finally { MDC.clear(); }          // critical: pooled threads reuse → must clear
    }
}
```

```
# logback pattern references the MDC key
%d{ISO8601} [%thread] %-5level [cid=%X{cid}] %logger - %msg%n
```

The hard part is **crossing thread boundaries**: `@Async` and `CompletableFuture` run on a *different* pooled thread that has its own empty MDC, so the correlation id vanishes in async logs. Fix it with a `TaskDecorator` that captures the submitting thread's MDC and restores it on the worker (and clears it after):

```java
public class MdcTaskDecorator implements TaskDecorator {
    public Runnable decorate(Runnable runnable) {
        Map<String, String> ctx = MDC.getCopyOfContextMap();   // capture on submit
        return () -> {
            if (ctx != null) MDC.setContextMap(ctx);            // restore on worker
            try { runnable.run(); }
            finally { MDC.clear(); }                            // clear after
        };
    }
}
// register: executor.setTaskDecorator(new MdcTaskDecorator());
```

In modern Spring (6 / Boot 3) the recommended path is **Micrometer Tracing** (the successor to Spring Cloud Sleuth), which auto-creates a trace id / span id, puts them in MDC for you, propagates them across HTTP/messaging via `Propagator`, and integrates the `io.micrometer:context-propagation` library to carry context into reactive (`Reactor Context`) and thread-pool boundaries. The interview-grade points: (1) MDC is `ThreadLocal`, so the same set-in-try/clear-in-finally and copy-across-executors discipline as any `ThreadLocal` applies; (2) async/reactive boundaries are where naive MDC silently breaks; (3) prefer the framework's tracing support over hand-rolled MDC for distributed systems because it also propagates *across services*, not just within one JVM.

### 🟠 Advanced — extended

#### Q81. [Practical] During a deploy, startup intermittently fails with `BeanCurrentlyInCreationException` (a circular dependency) that didn't appear in dev. How do you diagnose and permanently fix it?

`BeanCurrentlyInCreationException` means Spring detected a cycle it cannot break — typically a **constructor-injection cycle**, which has no early-reference escape hatch (unlike setter/field cycles resolved by the three-level cache). That it's *intermittent* across environments usually points to one of: a `@Profile`/`@Conditional` bean that only activates in some environments completing a cycle; an ordering nondeterminism; or Boot 2.6+ now *rejecting* cycles that older versions silently allowed (`spring.main.allow-circular-references=false` is the default).

```
Constructor cycle Spring CANNOT resolve:

  A(B b)  ──needs──▶  B(A a)
   ▲                    │
   └────────needs───────┘
  Neither can be even partially constructed → BeanCurrentlyInCreationException
```

Diagnose: read the exception — Boot prints the **exact cycle** ("The dependencies of some of the beans in the application context form a cycle: a → b → a") with the bean names, so you don't have to guess. Confirm whether it's constructor (unbreakable) vs setter/field (breakable). The *quick unblock* options are `@Lazy` on one constructor parameter (injects a lazy proxy, deferring the real resolution and breaking the construction-time cycle) or switching one side to setter injection — but treat these as temporary.

```java
// Temporary unblock — @Lazy proxy breaks the construction cycle
@Service
class A {
    private final B b;
    A(@Lazy B b) { this.b = b; }   // b is a proxy; real B resolved on first use
}
```

The **permanent fix is to remove the cycle**, because it's a design smell signaling that two beans share a responsibility that belongs in a third. Refactor patterns: extract the shared logic into a new collaborator that both depend on; invert one direction using an event (`ApplicationEventPublisher`) so A publishes and B listens instead of calling directly; or merge the two if they're genuinely one concept. Re-enabling `allow-circular-references=true` to "fix" it is the wrong move — it just hides the smell and reintroduces the fragile, order-dependent startup that caused the intermittent failure in the first place. The expert stance: the exception is Spring doing you a favor by surfacing a latent design problem at startup rather than letting it cause subtle init-order bugs at runtime.

#### Q82. [Practical] A `@Transactional` method occasionally throws `TransactionTimedOutException` or holds locks too long, causing downstream deadlocks. How do you fix it?

A long-running transaction is a production hazard: it pins a DB connection, holds row/table locks for its entire duration, and bloats the database's undo/redo (e.g., PostgreSQL bloat, MySQL history list growth). The usual root causes are **doing non-database work inside the transaction** — a slow HTTP/RPC call, file I/O, sending an email, or a `Thread.sleep` — or processing an unbounded batch in a single transaction.

```java
// BUG: remote call inside the tx pins the connection + locks for the whole HTTP round-trip
@Transactional
public void checkout(Order o) {
    orderRepo.save(o);                          // acquires locks
    PaymentResult r = paymentClient.charge(o);  // SLOW remote call — tx + locks held the whole time
    o.setPaid(r.ok());
    orderRepo.save(o);
}

// FIX: keep the tx short; do remote work OUTSIDE it
public void checkout(Order o) {
    orderService.persistPending(o);             // short tx: save + commit
    PaymentResult r = paymentClient.charge(o);  // no tx, no locks held
    orderService.markPaid(o.getId(), r);        // separate short tx
}
```

Set an explicit **timeout** so a runaway transaction is killed instead of pinning resources indefinitely: `@Transactional(timeout = 5)` (seconds) — Spring rolls back and throws `TransactionTimedOutException` when exceeded, which is far better than an unbounded hang. For deadlocks specifically: ensure consistent **lock ordering** across transactions (always update tables/rows in the same order), keep transactions short, lower isolation where correctness allows, and use **optimistic locking** (`@Version`) instead of pessimistic locks on high-contention rows so conflicts fail fast and retry rather than block. Pair the timeout with a retry-outside-the-transaction aspect for transient deadlocks (databases throw a deadlock victim error you can safely retry).

The systemic fixes: (1) **transactions wrap only database work** — push remote calls, messaging, and slow I/O outside the boundary, accepting eventual consistency where needed (e.g., publish a domain event `AFTER_COMMIT` to trigger the email rather than sending it inside the tx); (2) **chunk large batches** into many small transactions so locks release between chunks; (3) set realistic per-method `timeout`s; (4) monitor with DB tools (`pg_stat_activity`, `SHOW ENGINE INNODB STATUS`) to find long-running transactions and the queries holding locks. The principle: a transaction's duration is its blast radius — minimize it.

#### Q83. [Practical] You're migrating a legacy XML-configured Spring app to annotation/Java config incrementally. What's your strategy, and how do you run both side by side?

A big-bang XML-to-Java rewrite is risky on a large codebase; the pragmatic approach is **incremental coexistence**, because Spring lets XML and Java config interoperate in the same context. The bridge in both directions is well supported:

```java
// Java config that pulls in the legacy XML so beans coexist
@Configuration
@ImportResource("classpath:legacy-context.xml")   // XML beans become available to Java config
@ComponentScan("com.acme.migrated")                // new annotated beans
public class AppConfig { }
```

```xml
<!-- ...and XML can see annotated beans / enable scanning -->
<context:annotation-config/>
<context:component-scan base-package="com.acme.migrated"/>
<bean class="com.acme.config.AppConfig"/>   <!-- register a @Configuration class from XML -->
```

Strategy: (1) **Stand up a Java `@Configuration` root** that `@ImportResource`s the existing XML — now you have one context fed by both, and nothing breaks. (2) **Migrate leaf-first**: convert beans with no or few dependents first (infrastructure, then services, then the wiring), moving each `<bean>` to an `@Bean` method or a `@Component` + scanning, and deleting it from XML in the same change. (3) **Keep bean names stable** — XML `id`s often double as injection qualifiers, so preserve them (`@Bean("legacyName")`) to avoid breaking `@Qualifier`/by-name references during the transition. (4) **Migrate by module/package** so each PR is small, reviewable, and independently testable, with the existing integration tests as the safety net verifying the context still wires identically. (5) Convert `<property>` placeholders and `PropertyPlaceholderConfigurer` to `@ConfigurationProperties`/`@Value` and Boot's relaxed binding.

Watch-outs: XML supports things annotations express differently (parent/child bean definitions → composition or `@ConfigurationProperties`; `lookup-method` → `ObjectProvider`/`@Lookup`; XML AOP `<aop:config>` → `@Aspect`), and XML's `default-lazy-init` / autowire-by-name semantics may differ subtly from annotation defaults, so verify scope and laziness after each move. If the end goal is Spring Boot, the final step is replacing the manual context bootstrap with `@SpringBootApplication` and letting auto-configuration subsume the infrastructure beans (datasource, transaction manager) you previously declared by hand — at which point much of the remaining XML simply deletes itself. The discipline that keeps this safe is **one small, test-covered migration per change**, never a sweeping rewrite.

#### Q84. [Practical] You suspect a memory leak where the `ApplicationContext` or its beans aren't being garbage-collected (common in repeated test runs or hot redeploys). How do you investigate?

Context/classloader leaks show up as steadily rising heap and eventually `OutOfMemoryError: Metaspace` (or heap) across repeated test runs, hot redeploys in an app server, or dynamic context creation. The mechanism is almost always a **reference from something long-lived to something that should be short-lived** — a `ThreadLocal` whose value holds a bean, a static registry, an un-stopped thread, or a JDBC driver/`InheritableThreadLocal` pinning the application classloader so the whole context (and all its classes) can't be collected.

Investigation procedure:

1. **Capture a heap dump** at the point of high memory (`jmap -dump:live,format=b,file=heap.hprof <pid>`, or `-XX:+HeapDumpOnOutOfMemoryError`).
2. **Open it in Eclipse MAT** and run the **Leak Suspects** report. The decisive tool is *"Path to GC Roots, excluding weak/soft references"* on a leaked `ApplicationContext` or bean — it shows the exact reference chain pinning it, which is the answer.
3. **Look for the usual culprits:** a `ThreadLocal` set on a pooled thread that holds a bean (the thread outlives the context); a static `Map`/listener/cache holding bean references; a background thread (custom executor, scheduler, Kafka consumer) that was never stopped, so it and everything it references stay alive; or a third-party library registering a shutdown hook / driver against the context classloader.
4. **In tests specifically:** verify you're not creating a *new* context per test class via unique configurations (context cache fragmentation) and not leaking via `@DirtiesContext` overuse plus static state; ensure `static` fields don't accumulate.

The fixes map to the cause: ensure every `ThreadLocal` is cleared (especially across thread pools), every `SmartLifecycle`/executor/consumer is **stopped on context close** (implement `stop()`/`@PreDestroy` and verify `ApplicationContext.close()` actually runs), remove static references to beans, and let connection pools/clients close in their destroy callbacks. For classloader leaks in app servers, the Tomcat `JreMemoryLeakPreventionListener` and proper driver deregistration (`DriverManager.deregisterDriver`) on shutdown are standard. The expert habit: the heap dump's *path to GC roots* turns "we have a leak somewhere" into "this `ThreadLocal` on the Hikari housekeeper thread is holding the old context" — never guess at leaks, dump and trace the reference chain.

#### Q85. [Practical] Design a feature-flag/kill-switch mechanism in Spring that can be toggled at runtime without redeploying. What are the options and trade-offs?

A kill switch you can flip *without a deploy* is operationally essential for incident response, but the naive approach — injecting a flag with `@Value` — captures the value **once at bean creation**, so it can never change at runtime. The options, in increasing capability:

1. **`@ConfigurationProperties` + Spring Cloud Config / Actuator refresh.** Bind flags to a typed properties bean and mark beans `@RefreshScope`; calling `POST /actuator/refresh` re-binds properties and recreates `@RefreshScope` beans with the new values. Good when flags live in a config server or git-backed source.
2. **A mutable, thread-safe flag holder bean.** A simple `@Component` holding an `AtomicBoolean`/`ConcurrentHashMap`, updated via an admin endpoint. Trivial, in-process, but per-instance (each pod must be toggled, or you broadcast).
3. **A dedicated feature-flag service** (Unleash, LaunchDarkly, Flagsmith, or a DB-backed table) queried per evaluation with a short-lived local cache. This is the production-grade answer for *targeted* flags (per-user, per-tenant, percentage rollouts) and centralized control across all instances.

```java
// Runtime-evaluated flag — read the value PER CALL, not captured at construction
@Component
class FeatureFlags {
    private final Map<String, Boolean> flags = new ConcurrentHashMap<>();
    public boolean enabled(String key) { return flags.getOrDefault(key, false); }
    public void set(String key, boolean on) { flags.put(key, on); }   // admin endpoint calls this
}

@Service
class CheckoutService {
    private final FeatureFlags flags;
    CheckoutService(FeatureFlags flags) { this.flags = flags; }
    void checkout(Order o) {
        if (flags.enabled("new-checkout")) newPath(o); else legacyPath(o);  // evaluated live
    }
}
```

The core trade-off is **propagation and consistency**: an in-process `AtomicBoolean` is instant but per-instance (you must hit every pod or use a broadcast mechanism like Spring Cloud Bus / a Redis pub-sub to fan out the toggle); a config-server refresh is centralized but requires triggering refresh on each instance; a flag service is centralized and supports rich targeting but adds an external dependency and per-evaluation latency (mitigated by local caching with a TTL, accepting a few seconds of eventual consistency). Other considerations: **read flags per evaluation, never cache the boolean in a field**; make the default *safe* (a missing flag should fail closed for risky features); add **observability** (log/emit a metric when a kill switch trips); and ensure the toggle path itself is highly available and authenticated, because in an incident the kill switch is exactly what you need to work. For most teams the pragmatic sweet spot is a managed flag service for targeted/gradual rollouts plus a simple actuator-exposed in-process kill switch for emergency global off — fast locally, centralized when you have time.

#### Q86. [Practical] How do you instrument a Spring app for production observability (metrics on beans, transactions, caches, executors) and what would you alert on?

Production observability in Spring is anchored by **Micrometer** (the metrics facade Boot auto-configures) plus **Actuator** endpoints and, in Boot 3, **Micrometer Tracing** and the **Observation API** that unifies metrics + traces. The wins come mostly for free: with `spring-boot-starter-actuator` and a registry (Prometheus, OTLP), Boot auto-instruments HTTP server requests, the Hikari pool, JVM/GC, and more, exposed at `/actuator/prometheus`.

```java
// Custom timing via the Observation API (creates a metric AND a trace span)
@Service
class ReportService {
    private final ObservationRegistry registry;
    ReportService(ObservationRegistry registry) { this.registry = registry; }
    Report build(Long id) {
        return Observation.createNotStarted("report.build", registry)
            .lowCardinalityKeyValue("type", "summary")     // becomes a metric tag
            .observe(() -> doBuild(id));
    }
}
```

```java
// Or declaratively
@Observed(name = "report.build")           // requires an ObservedAspect bean
public Report build(Long id) { ... }
```

What to instrument and surface: **HTTP** (`http.server.requests` — latency histograms and error rate per endpoint), **DB pool** (`hikaricp.connections.active`/`.pending`/`.timeout` — pending climbing means saturation), **transactions** (timing on `@Transactional` boundaries, rollback counts), **caches** (`cache.gets` with hit/miss tags via `@EnableCaching` + a cache metrics binder — a collapsing hit ratio signals a key bug or undersized cache), **thread pools** (`executor.active`, `executor.queued`, `executor.completed` — a saturated queue predicts latency), and JVM (heap, GC pause, thread count). Expose `/actuator/health` with liveness/readiness groups for Kubernetes probes.

What to alert on (symptom-based, not cause-based): p99 latency and error rate per endpoint breaching SLO; `hikaricp.connections.pending > 0` sustained or `connections.timeout` rate rising (pool exhaustion — the earlier incident); cache hit ratio dropping sharply; executor queue depth near capacity / rejected-task count > 0; GC pause time or heap-after-GC trending up (memory leak); and readiness probe flapping. The expert framing: instrument the **golden signals** (latency, traffic, errors, saturation) at the boundaries that map to Spring's resource pools, prefer **low-cardinality tags** (never put user ids or unbounded values in metric tags — it explodes the time-series database), and use the unified Observation API so one instrumentation produces both the metric and the correlated trace span, so when an alert fires you can pivot from "p99 spiked on /checkout" to the exact slow trace.

#### Q87. [Practical] A scheduled job (`@Scheduled`) is running multiple times across your horizontally-scaled instances, or overlapping with itself. How do you fix both problems?

`@Scheduled` is *per-JVM* with **no cluster awareness** — every instance runs the schedule independently, so with N replicas a "nightly job" runs N times. Separately, within a single instance, the default single-threaded scheduler means a long run can delay the next, and `fixedRate` can *queue* invocations; misconfiguration can also let a job overlap itself. These are two distinct problems with distinct fixes.

**Problem 1 — overlap/timing within one instance.** By default Spring uses a *single-threaded* `ThreadPoolTaskScheduler`, so `@Scheduled` tasks are serialized and a slow task blocks others. `fixedDelay` waits for completion before scheduling the next run (no self-overlap); `fixedRate` schedules by wall-clock and *can* fire again before the previous finished if you give it a multi-threaded scheduler. Control it deliberately:

```java
@Scheduled(fixedDelay = 60_000)          // next run starts 60s AFTER the previous completes
public void poll() { ... }               // cannot overlap itself

@Configuration
class SchedulingConfig implements SchedulingConfigurer {
    public void configureTasks(ScheduledTaskRegistrar r) {
        var s = new ThreadPoolTaskScheduler();
        s.setPoolSize(4);                // parallelism across DIFFERENT tasks
        s.setThreadNamePrefix("sched-");
        s.initialize();
        r.setTaskScheduler(s);
    }
}
```

**Problem 2 — duplicate runs across instances.** You need **distributed coordination** so only one instance executes each fire. The standard solution is **ShedLock** (a small library that wraps `@Scheduled` with a DB/Redis-backed lock):

```java
@Scheduled(cron = "0 0 2 * * *")
@SchedulerLock(name = "nightlyReconcile", lockAtMostFor = "10m", lockAtLeastFor = "1m")
public void reconcile() { ... }   // only ONE instance acquires the lock and runs
```

ShedLock acquires a named lock in a shared store before running; the others see it locked and skip. `lockAtMostFor` is a safety net that releases the lock if the holder dies mid-run (so the job isn't stuck forever); `lockAtLeastFor` prevents very fast jobs from running twice due to clock skew. Alternatives: **Quartz** in clustered mode (heavier, full scheduler with persistence and misfire handling), or an external scheduler (Kubernetes `CronJob`, an enterprise scheduler) that triggers exactly one pod via an endpoint — which sidesteps the in-app coordination entirely and is often the cleanest at scale. The expert framing: `@Scheduled` is fine for per-instance housekeeping (cache eviction, local metrics flush), but **any job with cluster-wide side effects needs a distributed lock or an external single-trigger**, and within an instance you choose `fixedDelay` vs `fixedRate` plus scheduler pool size deliberately to control overlap.

#### Q88. [Practical] After enabling `@Cacheable`, users intermittently see stale data, and after a deploy the cache "isn't shared." Diagnose and design the caching correctly.

Two distinct production caching failures, both rooted in not thinking about *where the cache lives and when entries are invalidated*. "Not shared after deploy" means you're using the **default in-memory `ConcurrentMapCacheManager`** (or local Caffeine) — each instance has its *own* cache, so a write on instance A doesn't invalidate B's copy, and a deploy/restart wipes the cache entirely (cold-start latency spike). "Stale data" means **writes aren't evicting/updating the cache**, so reads keep returning the old cached value.

```java
// BUG: caches reads but never evicts on write → stale forever
@Cacheable("products")
public Product byId(Long id) { return repo.findById(id).orElseThrow(); }
public void update(Product p) { repo.save(p); }   // cache still holds the OLD product

// FIX: keep cache consistent with writes
@CacheEvict(cacheNames = "products", key = "#p.id")   // or @CachePut to refresh in place
public void update(Product p) { repo.save(p); }
@CacheEvict(cacheNames = "products", key = "#id")
public void delete(Long id) { repo.deleteById(id); }
```

Design correctly: (1) **Choose the cache topology to match the requirement.** Local cache (Caffeine) is fastest and fine for read-mostly, tolerant-of-slight-staleness data with a **TTL** so entries self-expire; a **distributed cache (Redis/Hazelcast)** is needed when multiple instances must see a consistent view or when you can't tolerate per-instance divergence. With Redis, all instances share one cache, a write evicts globally, and a deploy doesn't cold-start the cache. (2) **Always pair reads with eviction on writes** (`@CacheEvict`/`@CachePut`) so the cache tracks the source of truth — caching without invalidation is the textbook stale-data bug. (3) **Set TTLs** even on distributed caches as a backstop against missed evictions. (4) Mind the proxy caveat — a self-invoked `@Cacheable`/`@CacheEvict` does nothing.

The deeper trade-offs: a **local cache with short TTL** accepts bounded staleness for speed and zero infra; a **distributed cache** gives consistency and survives restarts but adds a network hop, a serialization concern (cache the right DTO, watch for serialization version mismatches across deploys), and an external dependency to keep available. For high-write data, caching may be the wrong tool entirely. And beware **cache stampede** — when a hot key expires, many requests miss simultaneously and hammer the database; mitigate with `@Cacheable(sync = true)` (local) or a distributed lock / probabilistic early refresh. The expert summary: decide *local vs distributed* from your consistency and multi-instance requirements, *always* invalidate on writes, set TTLs as a safety net, and treat the cache as a consistency problem, not just a speed knob.

#### Q89. [Practical] How would you safely roll out a risky change to a heavily-used `@Service` bean (e.g., a rewritten pricing engine) in production, using Spring features?

The goal is to ship a risky rewrite with the ability to **compare, gradually shift traffic, and instantly roll back** — without a redeploy to revert. Spring gives you the wiring primitives; the rollout discipline does the rest.

```java
// Both implementations are beans; a router picks per call based on a runtime flag
@Service @Qualifier("legacy")  class LegacyPricing implements PricingEngine { ... }
@Service @Qualifier("v2")      class NewPricing    implements PricingEngine { ... }

@Service @Primary
class PricingRouter implements PricingEngine {
    private final PricingEngine legacy, v2;
    private final FeatureFlags flags;
    PricingRouter(@Qualifier("legacy") PricingEngine legacy,
                  @Qualifier("v2") PricingEngine v2, FeatureFlags flags) {
        this.legacy = legacy; this.v2 = v2; this.flags = flags;
    }
    public Price price(Cart c) {
        if (flags.percentageEnabled("pricing-v2", c.customerId())) return v2.price(c);
        return legacy.price(c);
    }
}
```

The rollout sequence: (1) **Shadow / dark launch** — run `v2` *alongside* `legacy` on a fraction of requests, return the legacy result to the user, but compute and **compare** v2's output, logging/metering divergences. This validates correctness on real traffic with zero user impact. (2) **Canary** — once divergence is acceptable, route a small percentage (e.g., 1% → 5% → 25%) of *real* traffic to v2 via the runtime feature flag, watching error rate, latency, and business metrics (did revenue/conversion move unexpectedly?). (3) **Progressive ramp** to 100% with the kill switch always one toggle away. (4) **Decommission** legacy once v2 is stable.

Why Spring features make this clean: both implementations are beans behind a common interface, a `@Primary` router selects at runtime (no redeploy to switch), and the decision uses a **runtime-evaluated feature flag** (not a `@Value` captured at startup) so percentages and the kill switch change live. Pair this with **observability** (per-implementation metrics tagged `engine=v2|legacy` so dashboards compare them side by side) and ensure the new path is **side-effect-safe under shadowing** (a shadow pricing call must not actually charge anyone — guard writes). The non-Spring-specific but essential parts: feature-flag-driven percentage rollout, automated comparison in shadow mode, canary with fast rollback, and decoupling deploy (shipping the code, dormant) from release (turning the flag on). This is how you make "rewrote the pricing engine" a non-event instead of a 2 a.m. incident.

### 🔴 Expert — extended

#### Q90. [Practical] Lead the introduction of GraalVM native images for a subset of Spring Boot 3 services. What's the rollout plan, what breaks, and how do you keep it maintainable?

Native images deliver sub-100ms startup and ~2-5× lower memory — transformative for serverless and scale-to-zero — but the **closed-world model** breaks anything that relies on runtime reflection/dynamic behavior unless you provide metadata. Leading this well is as much about *which services* and *guardrails* as about the build.

**Where to apply it (selectivity is the strategy):** target services that benefit most and resist least — **serverless/Lambda functions** (cold start dominates cost and latency), **CLI tools / batch jobs** (start, do work, exit), and **scale-to-zero edge services**. *Avoid* native for services that are highly dynamic (runtime bean registration, frequent profile switching), heavily reflective with poorly-supported libraries, or where build time and operational complexity outweigh the startup win (a long-lived monolith that starts once a week gains little).

**What breaks and how you handle it:**
- **Reflection / dynamic proxies / resources** must be known at build time. Spring's **AOT engine** generates most reachability metadata automatically, and the **GraalVM Reachability Metadata Repository** ships hints for popular libraries — but custom reflection (a hand-rolled JSON mapper, a library doing `Class.forName` on config strings) needs explicit `RuntimeHintsRegistrar` or `@RegisterReflectionForBinding`, or you get `ClassNotFoundException`/`MissingReflectionRegistration` *at runtime* instead of a graceful failure.
- **Bean graph is frozen at build time** — no runtime conditional bean registration; **profiles must be fixed at build time** (`-Dspring.profiles.active` passed to the AOT step), so "switch profiles via env var" no longer works for native binaries.
- **Some libraries lack native support** entirely and must be replaced or excluded.

**Rollout plan:** (1) **Prerequisite** — every target on Boot 3 / Java 17+, building cleanly with the AOT plugin on the JVM first (`mvn -Pnative spring-boot:process-aot` produces the generated sources you can inspect). (2) **Pilot one low-risk service** end to end; build the native image (`mvn -Pnative native:compile` / GraalVM `native-image`), run the *existing integration test suite against the native binary* — native-specific bugs (a missing reflection hint) only surface at runtime, so testing the actual binary is non-negotiable. (3) **CI integration** — native builds are slow (minutes) and memory-hungry, so run them on a dedicated pipeline stage, cache aggressively, and keep the JVM build as the fast feedback path. (4) **Wave rollout** with a runbook, keeping a JVM fallback image available for instant rollback if a native-only issue appears in prod. (5) Consider **Project CRaC** as an alternative for services that need fast startup but can't accept the closed-world constraints — it checkpoints a warmed JVM and restores in milliseconds while keeping full dynamism.

**Keeping it maintainable:** test the native binary in CI (not just the JVM build), centralize custom `RuntimeHints` in well-documented registrars, pin GraalVM/library versions, and treat the metadata as code that's reviewed and tested. The org-level guidance: native is a *targeted* tool for startup-sensitive workloads, not a default — adopt it where the cold-start economics justify the build complexity, and explicitly *don't* where dynamism matters more than milliseconds.

#### Q91. [Practical] A microservice's heap grows slowly and it OOMs every few days, restarting via the liveness probe. The leak is in Spring-managed state. Walk through the full RCA.

This is a classic slow-leak production incident, and the discipline is **measure → capture → trace → fix → verify**, never speculate. The pattern (steady heap growth, OOM every N days, restart) tells you it's a *retention* leak — objects accumulate and are never released — as opposed to a burst.

1. **Confirm it's a real leak, not load growth.** Plot `jvm.memory.used{area=heap}` and especially **heap-after-GC** (the post-GC live set) over days. If heap-after-GC trends *up* monotonically, live objects are accumulating — a true leak. Flat-after-GC with high peaks is just allocation pressure, a different problem.
2. **Capture a heap dump near the high-water mark** (`-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/dumps`, or `jcmd <pid> GC.heap_dump`), ideally two dumps hours apart to diff growth.
3. **Analyze in Eclipse MAT.** Use the **dominator tree** to find what retains the most heap, and **"Path to GC Roots (exclude soft/weak)"** on the suspect to get the exact reference chain pinning it. Diffing two dumps shows *which* collection is growing.
4. **Spring-specific culprits to expect:** an unbounded **in-memory cache** (a `ConcurrentMapCacheManager` with no TTL/size limit grows forever — the default `@Cacheable` cache is unbounded), a `@Component` singleton holding an ever-growing `List`/`Map` of per-request data (the mutable-singleton-state bug, but accumulating instead of overwriting), a **`ThreadLocal` on a pooled thread** retaining beans, an event-listener or registry that adds but never removes, or metrics with **high-cardinality tags** (a counter tagged with user id creates one time-series per user — Micrometer's meter map grows without bound and OOMs the app).
5. **Fix the specific retention** — bound the cache (size + TTL, or swap to Caffeine/Redis with eviction), stop accumulating in singleton fields (compute locally or use a bounded structure), clear `ThreadLocal`s, remove stale listeners, and **never use unbounded-cardinality metric tags**.
6. **Verify** — deploy the fix and watch heap-after-GC flatten over the same multi-day window; the proof is the trend line, not a hope.

```java
// Common offender: unbounded cache. Replace the default in-memory manager with a bounded one.
@Bean
CacheManager cacheManager() {
    var mgr = new CaffeineCacheManager("products", "prices");
    mgr.setCaffeine(Caffeine.newBuilder()
            .maximumSize(50_000)            // bound it — the default @Cacheable cache is UNBOUNDED
            .expireAfterWrite(Duration.ofMinutes(10)));
    return mgr;
}
```

The expert framing: a liveness probe that "fixes" OOMs by restarting is masking the bug and hurting users (dropped requests on each restart); the RCA must end with a **flattened heap-after-GC trend**, and the single most useful artifact is the heap dump's path-to-GC-roots, which converts "we leak somewhere" into "this unbounded cache / high-cardinality metric / uncleared ThreadLocal is the retainer." High-cardinality metric tags deserve special mention because they're a uniquely Spring/Micrometer-era leak that surprises teams who added a "helpful" per-user tag.

#### Q92. [Practical] You inherit a service where everything is field-injected, there are circular dependencies papered over with `@Lazy`, and startup is flaky. Lead the cleanup, enforcing it so it can't regress.

This is the legacy-DI-debt scenario, and the win is *enforced* hygiene, not a one-time cleanup that rots. The end state: constructor injection everywhere, no circular dependencies, fail-fast eager startup — with **CI gates** so the codebase can't slide back.

**Assess and stabilize first.** Inventory the cycles (the `BeanCurrentlyInCreationException` / Boot's cycle report names them; `@Lazy` is hiding others). Don't rip out `@Lazy` blindly — it's load-bearing right now. Map each cycle to its root cause: usually two beans sharing a responsibility that belongs in a third, or a layering violation (a repository calling back into a service).

**Migrate incrementally, cycle by cycle.** For each cycle, apply the right structural fix: extract the shared logic into a new collaborator both depend on; invert one edge using an `ApplicationEventPublisher` (A publishes, B listens, breaking the direct reference); or merge genuinely-one-concept beans. Convert field injection to **constructor injection** as you go — `@RequiredArgsConstructor` (Lombok) or records keep it terse, defusing the "constructor injection is verbose" objection. Each PR fixes one cycle and converts one slice, kept green by the existing tests; constructor injection itself *surfaces* remaining cycles at startup (constructor cycles can't be hidden), so progress is self-revealing.

**Enforce with tooling so it can't regress** — this is the part that makes it stick:

```java
// ArchUnit test fails the build if anyone reintroduces field injection
@AnalyzeClasses(packages = "com.acme")
class InjectionRules {
    @ArchTest static final ArchRule no_field_injection = noFields()
        .should().beAnnotatedWith(Autowired.class)
        .because("use constructor injection: testable, immutable, fail-fast on cycles");

    @ArchTest static final ArchRule layering = layeredArchitecture().consideringAllDependencies()
        .layer("Web").definedBy("..web..")
        .layer("Service").definedBy("..service..")
        .layer("Repo").definedBy("..repository..")
        .whereLayer("Repo").mayOnlyBeAccessedByLayers("Service")   // repo can't call service → kills a cycle class
        .whereLayer("Service").mayOnlyBeAccessedByLayers("Web");
}
```

```yaml
# And keep Boot's circular-reference guard ON so any new cycle fails the build
spring:
  main:
    allow-circular-references: false   # Boot 2.6+ default — do NOT re-enable to "fix" a cycle
```

**Drive consensus** (it's a people problem too): show before/after evidence — startup-failure rate, flaky-test count, unit-test setup time — at an architecture review; pair with skeptical seniors on the first hot service so the pattern is demonstrated, not decreed; write a one-page migration guide. The enforcement combo — ArchUnit banning field injection and enforcing layering, plus `allow-circular-references=false` failing the build on any new cycle — means the cleanup is permanent: the next engineer *cannot* reintroduce the debt without a red build. The expert principle: a refactor without a guardrail regresses; the deliverable isn't "I fixed the cycles," it's "the build now prevents them."

#### Q93. [Practical] How do you debug a `@Conditional`/auto-configuration mystery — "my bean isn't created" or "an unexpected bean is created" — in a Spring Boot app?

Auto-configuration mysteries are common because so much wiring is *implicit* and gated by conditions you didn't write. The definitive tool is the **condition evaluation report**, which tells you exactly which auto-configurations matched, which didn't, and *why* — turning a mystery into a fact.

```bash
# Run with --debug (or set debug=true) to print the condition report at startup
java -jar app.jar --debug

# Or query it live on a running app
curl localhost:8080/actuator/conditions | jq
```

The report has three sections that map directly to the two failure modes:
- **Positive matches** — auto-configs that *were* applied and the conditions they satisfied. If an "unexpected bean is created," find it here to see which auto-config produced it and which condition let it through (commonly a starter you pulled in transitively brought a `@ConditionalOnClass` that matched).
- **Negative matches** — auto-configs that were *skipped* and the precise condition that failed (e.g., `@ConditionalOnProperty (spring.kafka.bootstrap-servers) did not find property`, or `@ConditionalOnClass did not find required class 'io.lettuce...'`). If "my bean isn't created," this section names the unmet condition — usually a missing dependency, an unset property, or the wrong profile.
- **Exclusions / unconditional classes** — what was explicitly excluded.

```java
// "Unexpected bean" — exclude an auto-config you don't want
@SpringBootApplication(exclude = { DataSourceAutoConfiguration.class })
public class App { }
```

The diagnostic flow: for **"my bean isn't created,"** check negative matches for the relevant auto-config and read the failing condition — then satisfy it (add the dependency, set the property, activate the profile) or define the bean yourself. For **"an unexpected bean is created,"** check positive matches to find the responsible auto-config, then either exclude it (`@SpringBootApplication(exclude=...)` or `spring.autoconfigure.exclude`) or define your own bean so `@ConditionalOnMissingBean` makes the auto-config back off. Remember the ordering guarantee that makes the latter work: **auto-configs are evaluated *after* user config** (via the `DeferredImportSelector`), so a user-defined bean is seen first and `@ConditionalOnMissingBean` correctly steps aside. The expert habit: never guess at auto-configuration behavior — run `--debug` once and read the report; it's the authoritative, per-launch explanation of every conditional decision, and it also explains environment-specific mysteries (a property present in prod but not dev flips a `@ConditionalOnProperty`).

#### Q94. [Practical] Describe a real production incident you traced to a Spring misconfiguration, how you diagnosed it under pressure, and what you changed to prevent recurrence.

A strong answer follows **Situation → Detection → Diagnosis → Mitigation → Root cause → Prevention** and demonstrates calm, evidence-driven debugging plus a systemic fix — not just "I restarted it." A representative shape:

*Situation/Detection* — During a traffic spike, the checkout service began timing out; alerts fired on rising p99 latency and a climbing `hikaricp.connections.pending`. Users saw 500s. *Diagnosis under pressure* — I pulled the Hikari metrics from the dashboard and saw `connections.active` pinned at the pool max (20) with `pending` growing, so I knew it was **connection-pool exhaustion**, not CPU or GC (heap/GC metrics were flat). I'd previously enabled `leak-detection-threshold`, so the logs already had stack traces of long-held connections — they all pointed at a `@Transactional` method that made a **synchronous payment-gateway HTTP call while holding the DB connection**. Under normal load the gateway responded in 50ms and the pool never saturated; under the spike the gateway slowed to 2s, so each request pinned a connection 40× longer, and 20 connections couldn't keep up.

*Mitigation* — Immediate: I bumped the pool modestly and added a tight `connectTimeout`/`readTimeout` on the gateway client so slow calls failed fast instead of pinning connections indefinitely, which stabilized the error rate within minutes. *Root cause* — the real bug was architectural: a remote call inside the transaction boundary, so the connection's lifetime was coupled to an external service's latency. *Permanent fix* — I refactored so the DB transaction only wrapped the database writes (a short "pending" commit), the payment call happened *outside* any transaction, and the result was persisted in a separate short transaction; for the post-payment side effects (email, analytics) I moved to `@TransactionalEventListener(AFTER_COMMIT)`. *Prevention* — I added an alert on `hikaricp.connections.pending > 0` sustained (leading indicator, not lagging error rate), an ArchUnit/review guideline flagging remote-client calls inside `@Transactional` methods, set sane default client timeouts in a shared config, and wrote a runbook entry mapping "pending connections climbing" → "find the long holder via leak detection."

What the interviewer is assessing: did you diagnose from **metrics/evidence** rather than guessing (active vs pending, ruling out GC/CPU), did you separate **fast mitigation** from **root-cause fix**, did you land on the *real* cause (remote call in a transaction holding a connection — a recurring Spring anti-pattern) rather than the symptom (small pool), and did you close the loop with **prevention** (leading-indicator alert, timeouts as defaults, a review guardrail, a runbook). The honest, specific, metrics-grounded narrative with a systemic fix is what distinguishes senior operational judgment from "we scaled up and hoped."

#### Q95. [Practical] Your team debates whether to keep heavy use of Spring (transactions, AOP, auto-config) or move toward a leaner DI/explicit-wiring approach for a latency-critical service. How do you frame and decide this?

This is a judgment question testing whether you can weigh framework leverage against control and cost *with evidence*, rather than dogmatically. Frame it around **what the service actually needs** and **measured cost**, not preference.

The case *for* leaning on Spring: enormous leverage — transactions, security, caching, observability, connection pooling, and a vast tested ecosystem are essentially free and battle-hardened; team familiarity lowers onboarding and bug rates; and most "Spring is slow" claims are about *startup*, not steady-state request latency (AOP proxy indirection is nanoseconds, negligible against a single DB round-trip). For the overwhelming majority of services, Spring's overhead is invisible next to I/O.

The case *for* leaner/explicit wiring: in genuinely latency-critical or resource-constrained contexts, the costs become real — **startup time and memory** (mitigated by AOT/native/CRaC rather than abandoning Spring), the **closed-world friction** if you go native, proxy indirection on truly hot in-process paths (millions of calls/sec with no I/O), and the *cognitive* cost of implicit auto-configuration making behavior hard to reason about under pressure. A lean approach (manual wiring, or a lightweight DI like Dagger/Guice, or Quarkus/Micronaut which do build-time DI) trades ecosystem and convenience for explicitness, faster startup, and lower memory.

How I'd decide: (1) **Quantify the actual requirement** — what are the latency/startup/memory SLOs, and does current Spring usage *measurably* violate them? Profile before assuming. (2) **Prefer the cheaper fix first** — most startup/memory concerns are solved by **Spring's own AOT/native-image/lite-mode/lazy-init and narrowed scanning** without leaving the ecosystem, preserving the team's productivity. (3) **Reserve a framework switch for evidence of a fundamental mismatch** — e.g., a serverless function where cold start dominates economics and the team is willing to own native-image complexity, where **Micronaut/Quarkus** (compile-time DI, no runtime reflection) may be a better *fit* than retrofitting Spring. (4) **Don't conflate "lean DI" with "no framework"** — hand-rolled wiring reintroduces the bugs DI solves and rarely pays off. (5) **Consider blast radius and team** — a switch costs months of velocity and a new skill set; that must be justified by SLO violations, not aesthetics.

The expert synthesis: this is a *fit-for-purpose* decision driven by measured SLOs, and the default is to **optimize within Spring** (AOT/native/lazy/lite-mode, move work off the hot path) before considering a framework change, reserving alternatives like Micronaut/Quarkus for the narrow set of workloads where build-time DI and minimal footprint are first-order requirements. The mature stance is neither "Spring everywhere" nor "Spring is bloat" but "measure, exhaust the in-ecosystem optimizations, and switch only when the data shows a genuine mismatch the framework can't close."

#### Q96. [Practical] How do you safely use `@RefreshScope` and dynamic configuration refresh in production, and what are the failure modes?

`@RefreshScope` (from Spring Cloud Context) lets a bean pick up new property values *without a restart*: marked beans are wrapped in a proxy, and on a refresh trigger (`POST /actuator/refresh`, or a `RefreshScopeRefreshedEvent` from Spring Cloud Bus) their cached instance is **discarded and lazily re-created** on the next access with the freshly re-bound configuration. It's powerful for tuning timeouts, flags, and thresholds live — but it has sharp edges that cause production surprises.

```java
@RefreshScope                       // re-created on /actuator/refresh with new property values
@Component
class RateLimiterConfig {
    @Value("${ratelimit.permits-per-second}") private double permits;  // re-bound on refresh
    public double permits() { return permits; }
}
```

The mechanism and its **failure modes**:
1. **Re-creation, not mutation.** A refresh *destroys and rebuilds* the bean — its `@PostConstruct` runs again and any in-memory state is lost. A `@RefreshScope` bean holding a counter, a warmed cache, or an open connection will reset/leak on refresh. Keep refresh-scoped beans **stateless configuration holders**, not stateful resources.
2. **Lazy re-creation latency.** The new instance is built on first access *after* refresh, so the first request post-refresh pays construction cost — and any error in re-binding (a now-invalid property) surfaces *then*, at request time, not at refresh time, mimicking the lazy-init production landmine.
3. **Propagation across instances.** `/actuator/refresh` is *per-instance* — you must call it on every pod or use **Spring Cloud Bus** (Kafka/RabbitMQ) to broadcast a refresh to the whole cluster. Forgetting this leads to *inconsistent configuration across instances*, which is worse than a uniform restart.
4. **Beans that captured config elsewhere don't update.** A `@RefreshScope` proxy only re-binds its *own* fields; if another (non-refresh-scoped) bean read the same property at startup, it keeps the stale value — partial refresh is a real consistency bug.
5. **Connection pools / clients** wrapped in refresh scope can leak the old resource if the destroy callback doesn't close it, or briefly run with two pools during transition.

Safe usage: confine `@RefreshScope` to **stateless config beans**; validate re-bound config (a refresh that injects a bad value should fail loudly and ideally be caught by `@Validated` `@ConfigurationProperties`); use **Spring Cloud Bus** for cluster-wide, consistent propagation; and for resources like datasources prefer purpose-built dynamic-config support (or accept that some changes warrant a rolling restart). The expert framing: dynamic refresh trades the simplicity and *fail-fast-at-startup* guarantee of immutable config for live tunability — it's the right tool for operational knobs (timeouts, limits, flags) but the wrong one for stateful beans or anything where a misconfiguration silently surfacing at the next request is unacceptable. Treat a refresh as a *mini-redeploy of those beans*, with the same care about state, validation, and cluster-wide consistency.

#### Q97. [Practical] How do you design Spring Boot health checks (liveness vs readiness) so Kubernetes restarts and routing behave correctly?

Conflating liveness and readiness is a common, damaging mistake: a misconfigured liveness probe that depends on a downstream (e.g., the database) will **restart the pod when the database blips**, turning a transient dependency outage into a restart storm that makes everything worse. The two probes answer fundamentally different questions, and Spring Boot models them explicitly via Actuator **health groups**.

- **Liveness** = "is this JVM irrecoverably broken; should Kubernetes *kill and restart* it?" It must depend on **nothing external** — only on the app itself being deadlock-free and able to make progress. A failing liveness probe triggers a restart, so it must *never* fail for a reason a restart won't fix (a down database does not get fixed by restarting your app).
- **Readiness** = "can this instance *serve traffic right now*; should the load balancer route to it?" This *should* reflect critical dependencies (DB, required downstreams) and in-progress startup/shutdown — a failing readiness probe removes the pod from the LB *without* killing it, so it can recover and rejoin.

```yaml
# Spring Boot exposes liveness/readiness probe groups automatically on Kubernetes
management:
  endpoint:
    health:
      probes:
        enabled: true
      group:
        readiness:
          include: readinessState,db,redis    # critical deps gate routing
        liveness:
          include: livenessState               # NO external deps
```

```
GET /actuator/health/liveness   → { "status": "UP" }   (app is alive)
GET /actuator/health/readiness  → { "status": "UP" }   (ready to serve)
```

Spring Boot's `ApplicationAvailability` ties these to lifecycle automatically: `readiness` flips to `OUT_OF_SERVICE` during startup (before the context is ready) and during **graceful shutdown** (so the LB stops routing while in-flight requests drain — the readiness/graceful-shutdown pairing is what makes zero-downtime deploys work), while `liveness` stays `UP` unless the app is genuinely broken. You can drive availability programmatically by publishing `AvailabilityChangeEvent` (e.g., flip readiness to `REFUSING_TRAFFIC` when a circuit breaker to a hard dependency opens). The expert checklist: liveness depends on *nothing external* (or you get restart storms), readiness gates on *critical dependencies and lifecycle state*, both wired through health groups, and the readiness/graceful-shutdown integration verified under a rolling deploy with load — the failure mode you're preventing is "a 30-second DB hiccup restarted every pod in the cluster."

#### Q98. [Practical] After a dependency upgrade, you hit `NoSuchMethodError`/`ClassNotFoundException` at runtime though it compiled fine. How do you diagnose and prevent these Spring dependency conflicts?

A `NoSuchMethodError`/`NoClassDefFoundError` that compiles but fails at runtime is the signature of a **dependency version conflict (JAR hell)**: at compile time one version of a library is on the classpath, but at runtime a *different*, incompatible version wins because some transitive dependency dragged it in. Spring ecosystems are especially prone to this because Boot, Spring Cloud, and dozens of starters each pull large transitive trees (Jackson, Netty, SLF4J, Guava, etc.).

Diagnose:

```bash
# See the resolved dependency tree and WHERE the offending version comes from
mvn dependency:tree -Dincludes=com.fasterxml.jackson.core:jackson-databind
./gradlew :app:dependencies --configuration runtimeClasspath
# Find duplicate classes on the classpath (two jars providing the same class)
mvn dependency:analyze
```

The tree shows the conflicting versions and their paths, and Maven prints "omitted for conflict" where its *nearest-wins* resolution picked a version — which is frequently the *wrong* one for runtime compatibility. The class that throws `NoSuchMethodError` tells you which library; the tree tells you which transitive path forced the bad version.

Prevent (this is the real answer): **use Spring Boot's dependency management / BOM and don't fight it.** `spring-boot-starter-parent` (or importing `spring-boot-dependencies` as a BOM) pins a *tested, mutually-compatible* set of versions for the entire ecosystem. The rules: (1) **omit versions** for managed dependencies — let the BOM decide, so everything aligns to the versions Boot tested together; (2) when you *must* override (a security patch), override via the BOM's version properties (`<jackson-bom.version>`) rather than pinning one artifact, so the whole family moves together and stays consistent; (3) add `spring-cloud-dependencies` as a BOM (aligned to your Boot version per the compatibility matrix) rather than picking Cloud library versions individually; (4) treat an unmanaged third-party library's transitive deps with suspicion — exclude and re-declare under BOM management if it drags an incompatible Jackson/Netty. The expert framing: the BOM exists precisely to eliminate this class of bug, so the discipline is "version what the BOM doesn't manage, let the BOM manage the rest, override only via BOM properties" — ad-hoc per-artifact version pinning is how teams reintroduce JAR hell. And run `dependency:tree`/`dependencies` *before* shipping a dependency bump, not after the production `NoSuchMethodError`.

#### Q99. [Practical] A bulk operation through Spring Data JPA / `JpaTemplate` is extremely slow (thousands of inserts take minutes). How do you diagnose and fix it?

Slow bulk JPA operations are a classic, and the causes are specific and fixable. The usual suspects: **no JDBC batching** (each insert is a separate round-trip), the **persistence context growing unbounded** (every entity stays managed, so flush/dirty-check cost grows quadratically), the **N+1 problem** on reads feeding the writes, and **`GenerationType.IDENTITY`** silently disabling Hibernate batching.

Diagnose first — *see the SQL*: enable statement logging and counting so you know whether it's one batched statement or thousands of individual ones.

```yaml
spring:
  jpa:
    properties:
      hibernate:
        jdbc.batch_size: 50            # enable JDBC batching
        order_inserts: true            # group same-table inserts so they batch
        order_updates: true
        generate_statistics: true      # logs batch counts to verify it's working
logging:
  level:
    org.hibernate.SQL: DEBUG           # confirm: are statements batched?
```

The fixes:
1. **Enable JDBC batching** (`jdbc.batch_size`) and `order_inserts`/`order_updates` so Hibernate groups statements into batches instead of one round-trip each — often a 10× win on its own.
2. **The IDENTITY trap:** `@GeneratedValue(strategy = IDENTITY)` forces Hibernate to execute each insert immediately to get the generated key, which **completely disables insert batching**. Use a `SEQUENCE` with a pooled optimizer (`@SequenceGenerator(allocationSize = 50)`) so keys are pre-allocated and inserts can batch.
3. **Flush and clear in chunks** to keep the persistence context bounded — otherwise the first-level cache holds every entity and dirty-checking degrades:

```java
@Transactional
public void importAll(List<Row> rows) {
    for (int i = 0; i < rows.size(); i++) {
        em.persist(toEntity(rows.get(i)));
        if (i % 50 == 0) { em.flush(); em.clear(); }   // bound the persistence context
    }
}
```

4. **For truly large volumes, bypass JPA.** JPA is the wrong tool for million-row loads; drop to `JdbcTemplate.batchUpdate(...)` or a database-native bulk loader (`COPY`/`LOAD DATA`). The ORM's per-entity overhead (event listeners, dirty checking, cascade) is pure cost you don't need for a straight insert.

The expert framing: the first move is always to *look at the emitted SQL* (`generate_statistics` / SQL logging) to confirm whether batching is actually happening — teams set `batch_size` and assume it works while `IDENTITY` silently defeats it. Then bound the persistence context with periodic `flush`/`clear`, and recognize the threshold where JPA stops being appropriate and `JdbcTemplate`/native bulk load is the right answer. Slow bulk JPA is rarely "the database is slow" — it's almost always many small round-trips that should have been batched.

#### Q100. [Practical] How do you enable and correctly use Spring's method-level validation (`@Validated` + `@NotNull`/`@Valid` on parameters), and what are the gotchas?

Bean Validation (JSR-380 / Jakarta Validation) is most familiar on `@RequestBody` DTOs in controllers, but Spring also supports validating **arbitrary bean method parameters and return values** — useful for enforcing contracts on service methods regardless of the caller. It's enabled by annotating the class `@Validated` (Spring's annotation, *not* `@Valid`), which registers a `MethodValidationPostProcessor` that proxies the bean and validates constraints on each call.

```java
@Service
@Validated                                    // class-level: turns on method validation
public class AccountService {

    public Account open(@NotBlank String owner,
                        @Min(0) BigDecimal initialDeposit,
                        @Valid AccountOptions options) {   // @Valid cascades into the object
        // if any constraint fails, ConstraintViolationException is thrown BEFORE the body runs
        return repo.save(new Account(owner, initialDeposit));
    }

    @NotNull                                  // validate the RETURN value too
    public Account find(@NotNull Long id) { ... }
}
```

The mechanics and **gotchas**:
1. **`@Validated` on the class, constraints on the parameters.** Forgetting the class-level `@Validated` is the #1 reason "my `@NotNull` parameter isn't enforced" — without it there's no validation proxy, so the annotations are inert.
2. **It throws `ConstraintViolationException`, not `MethodArgumentNotValidException`.** The latter is the controller-`@RequestBody` exception; method validation throws the former, so your `@ExceptionHandler`/`@ControllerAdvice` must handle `ConstraintViolationException` to produce a clean 400 instead of a 500.
3. **Proxy-based, so the same caveats apply** — self-invocation bypasses validation (the call doesn't go through the proxy), and the method must be on a Spring-managed bean. In Spring Boot 3.x there's an additional nuance: framework method validation (controllers) and Bean Validation method validation can both be present, so know which path you're on.
4. **`@Valid` vs the constraint annotations:** plain `@NotNull`/`@Min` validate the parameter itself; `@Valid` on a parameter **cascades** validation into that object's own constrained fields. People forget `@Valid` and wonder why nested object constraints aren't checked.
5. **Groups and ordering** work (`@Validated(OnCreate.class)`) to apply different rules in different contexts.

The value proposition: method validation pushes input contracts down to the *service* layer so they're enforced no matter who calls (controller, scheduler, another service, a test), fail-fast and declaratively, instead of scattering manual `if (x == null) throw` guards. The expert reminders: it's `@Validated` (class) that switches it on, it throws `ConstraintViolationException` (handle it), it's proxy-based (no self-invocation, public methods), and `@Valid` is what cascades into nested objects — getting any of those wrong produces the "validation silently does nothing" or "I get a 500 instead of a 400" tickets.

#### Q101. [Practical] You get `BeanDefinitionOverrideException` (or beans silently overriding each other) at startup. What causes it and how do you resolve it correctly?

`BeanDefinitionOverrideException` means **two bean definitions registered under the same name**, and Spring Boot 2.1+ *rejects* this by default (`spring.main.allow-bean-definition-overriding=false`) instead of silently letting one win — which is a deliberate safety improvement, because silent overriding used to cause baffling "wrong bean is wired" bugs. The exception names both the offending bean name and the two sources.

Common causes:
1. **Two `@Bean` methods producing the same bean name** — bean name defaults to the method name, so two `@Bean public DataSource dataSource()` in different `@Configuration` classes collide.
2. **A `@Bean` method colliding with a component-scanned `@Component`** of the same default name.
3. **Importing the same configuration twice**, or a library's auto-config defining a bean your code also defines under the same name (when it *isn't* guarded by `@ConditionalOnMissingBean`).
4. **Scanning the same package from two places** (the root + dispatcher context bug, or overlapping `@ComponentScan` base packages).

```java
// Collision: both register a bean named "objectMapper"
@Configuration class A { @Bean ObjectMapper objectMapper() { ... } }
@Configuration class B { @Bean ObjectMapper objectMapper() { ... } }   // BeanDefinitionOverrideException

// Resolve by giving distinct names + a clear @Primary, NOT by re-enabling overriding
@Configuration class A { @Bean @Primary ObjectMapper appObjectMapper() { ... } }
@Configuration class B { @Bean ObjectMapper auditObjectMapper() { ... } }
```

The **correct** resolution is to fix the duplication, not to re-enable overriding: rename the beans so they're distinct (and mark the intended default `@Primary`), remove the redundant definition, or — if a library auto-config is the other source — let your bean win cleanly (the library's should be `@ConditionalOnMissingBean`, and yours being present makes it back off). Re-enabling `spring.main.allow-bean-definition-overriding=true` is a code smell: it silences the error but reintroduces *order-dependent* "which definition wins" nondeterminism, where a change in scan order or import order silently swaps which implementation is live — exactly the class of bug the default-off setting prevents. The legitimate use of `allow-bean-definition-overriding=true` is narrow (some test setups that intentionally replace a bean, though `@MockBean`/`@TestConfiguration` are cleaner). The expert stance: treat `BeanDefinitionOverrideException` as Spring catching an *ambiguity you didn't intend*, read the two sources it names, and make the wiring deterministic by removing the duplicate or naming + `@Primary`-ing deliberately — never paper over it by allowing overrides globally.

#### Q102. [Practical] How do Spring Boot DevTools and hot-reload work, what are the gotchas, and why must they never reach production?

DevTools accelerates the local edit-compile-see loop, and understanding *how* it does so explains both its gotchas and why it's a production hazard. Its core trick is a **two-classloader scheme**: a *base* classloader holds unchanging third-party jars, and a *restart* classloader holds your application classes. On a code change, DevTools throws away and recreates only the restart classloader — an **"automatic restart"** that's much faster than a full JVM restart because the framework/library classes never reload. It also provides **LiveReload** (browser refresh on static-resource change) and sets development-friendly property defaults (disables template/resource caching, tweaks logging).

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-devtools</artifactId>
  <optional>true</optional>          <!-- optional/runtime: not transitively inherited, excluded from the fat jar -->
  <scope>runtime</scope>
</dependency>
```

**Gotchas:**
1. **It's a restart, not true hot-swap.** DevTools restarts the application context, so it's fast but not instantaneous, and **in-memory state is lost** on each change (which is usually fine in dev). For genuine class-redefinition without restart you need JRebel or the JVM's limited HotSwap (method-body changes only).
2. **The two-classloader split causes `ClassCastException`/identity surprises** — an object created before a restart can be a *different* `Class` instance (different classloader) than after, and caching/serializing across restarts or mixing DevTools-loaded and base-loaded classes can produce confusing `instanceof`/cast failures.
3. **`@Entity`/scanning changes** and certain config changes still need a real restart; not every change is picked up by the fast path.
4. **It changes behavior via property defaults**, so something that "works in dev" (caching off) may differ in prod (caching on) — a source of dev/prod drift.

**Why it must never reach production** — this is the load-bearing point: (1) the restart classloader and file-watching add overhead and an entirely different classloading model than prod runs on, so you'd be testing/running a *different* setup; (2) DevTools historically exposed a **remote debug/restart tunnel** (`spring-boot-devtools` remote support) that, if reachable, is a serious **remote code execution** risk; (3) it disables caches and changes defaults, degrading performance and masking real behavior. Spring Boot guards against this on purpose: DevTools **disables itself when running from a packaged jar** (it detects it's not a "local" launch), and the recommended `optional`/`runtime` dependency scope means it isn't packaged into the fat jar or inherited by downstream modules. The expert checklist: include DevTools only as `optional`/`runtime`, rely on Boot's auto-disable in packaged apps, never expose remote DevTools, and remember that the fast feedback comes from a *restart with a split classloader* — which is exactly why it's a dev-only tool and why its classloader model causes the occasional baffling `ClassCastException` you don't see in production.

#### Q103. [Practical] How do you replace or mock a specific bean in tests without rebuilding the whole context for every variation, and what are the trade-offs of `@MockBean` vs `@TestConfiguration` vs constructor doubles?

There are three idiomatic ways to substitute a bean in a Spring test, and choosing well is mostly about **context-cache impact** (suite speed) versus convenience. The options:

1. **`@MockBean`/`@SpyBean`** — Spring Boot adds (or replaces) a Mockito mock in the context. Most convenient: `@MockBean PaymentClient client;` and then `when(client.charge(any())).thenReturn(ok())`. The catch is **context-cache fragmentation**: each unique set of `@MockBean`s produces a *distinct cache key*, so a context can't be reused across test classes that mock different beans — heavy `@MockBean` use silently multiplies the number of contexts built and tanks suite speed. (Note Boot 3.4+ introduces `@MockitoBean`/`@MockitoSpyBean` as the successors with the same caching consideration.)
2. **`@TestConfiguration` + `@Primary`** — define a nested or imported test configuration that provides a replacement bean marked `@Primary` (or `@Bean` overriding by name). More verbose but explicit, and a *shared* `@TestConfiguration` reused across many test classes keeps the context cache key identical, so contexts are reused. Good for substituting infrastructure (a fake clock, an in-memory implementation) across a whole test module.
3. **Plain constructor doubles (no Spring at all)** — for genuine unit tests, `new MyService(mockCollaborator)` needs *no context*, runs in microseconds, and has zero cache impact. This is the fastest path and the payoff of constructor injection.

```java
// Option 1: convenient, but each distinct mock set = a new cached context
@SpringBootTest
class CheckoutMockBeanTest {
    @MockBean PaymentClient payment;
    @Autowired CheckoutService checkout;
}

// Option 2: shared test config → context reused across all classes that import it
@TestConfiguration
class FakeClockConfig {
    @Bean @Primary Clock fixedClock() { return Clock.fixed(INSTANT, ZoneOffset.UTC); }
}

// Option 3: no Spring — fastest, zero cache impact
class CheckoutServiceUnitTest {
    @Test void chargesOnce() {
        var payment = mock(PaymentClient.class);
        var svc = new CheckoutService(payment);     // constructor injection pays off here
        svc.checkout(cart);
        verify(payment).charge(any());
    }
}
```

The decision framework: for **pure logic tests, use constructor doubles** (option 3) — no context, no cache concern, fastest feedback, and it validates that your DI is clean. For **integration tests that need most of the context but must stub one collaborator** (a remote client, a flaky dependency), `@MockBean` is the pragmatic choice — but be aware of and *minimize the variety* of mock combinations so contexts stay shareable. For **substituting infrastructure consistently across many tests** (clock, ID generator, an in-memory adapter), a **shared `@TestConfiguration`** is best because it keeps the cache key stable. The trade-off triangle is convenience vs explicitness vs suite speed: `@MockBean` is most convenient but most cache-fragmenting; `@TestConfiguration` is explicit and cache-friendly when shared; constructor doubles are fastest but only for true unit scope. The expert habit is to **default to constructor doubles**, reach for shared `@TestConfiguration` for cross-cutting fakes, and use `@MockBean` deliberately and sparingly — because the silent cost of scattered `@MockBean`s is a test suite that quietly rebuilds dozens of contexts and takes minutes instead of seconds.



- **Constructor injection is the default**: immutable, fail-fast, testable without the container; field injection is an anti-pattern.
- **The lifecycle is the map**: `BeanFactoryPostProcessor` edits *definitions*, `BeanPostProcessor` edits *instances*, and **AOP proxies are created in `postProcessAfterInitialization`** — which explains self-invocation.
- **`@Transactional` is proxy-based and thread-bound**: self-invocation, checked exceptions, swallowed exceptions, non-public methods, and async/reactive boundaries are the usual reasons it "does nothing."
- **Know the proxy mechanics**: JDK (interface) vs CGLIB (subclass), Boot defaults to CGLIB; `@Configuration` full vs lite mode is the same CGLIB story applied to inter-bean calls.
- **Circular deps** are resolvable only for setter/field singletons via the three-level cache; constructor cycles fail, and Boot 2.6+ rejects cycles by default — fix the design.
- **Spring 6 / Boot 3** mean Java 17+, jakarta namespace, AOT/native-image options, and lambda-based Security DSL.
- **Security lives in core too**: SpEL injection, mass assignment, and exposed actuator/H2 are real, profile-guard and patch accordingly.

## ⚠️ Common Pitfalls

- Expecting `@Transactional`/`@Cacheable`/`@Async` to work on **self-invoked** or **private** methods — proxy bypass.
- Injecting a **prototype into a singleton** and getting one instance forever (use `ObjectProvider`/`@Lookup`/scoped proxy).
- Relying on **checked-exception rollback** — Spring rolls back only on unchecked by default; set `rollbackFor`.
- Putting **mutable per-request state in a singleton field** → cross-thread data corruption.
- Using **field injection**, then being unable to unit-test or detect circular dependencies until runtime.
- Forgetting that `@Configuration` **lite mode** (`proxyBeanMethods=false`) makes inter-`@Bean` calls create new instances.
- Evaluating **user input as SpEL** (RCE) or leaving **H2 console/actuator** open in prod.
- Wrapping **retry inside** the transaction instead of outside, so retries occur within a rollback-only transaction.
- Assuming `@Async`/reactive code **inherits the caller's transaction** — it doesn't (thread-confined `ThreadLocal`).
- Enabling global **lazy initialization** in production and converting startup failures into first-request outages.

## 🧩 Extended Questions — Set 3: Coding, Design & Expert Scenarios

### 🟢 Basic — extended

#### Q104. [Coding] Write a configuration that selects a `Clock` bean by profile, and a test that proves the right one is wired.

A common real need is making time deterministic in tests while using the real wall clock in production. Profiles plus `@Primary` give you a clean, container-driven switch with zero `if` statements in your business code.

```java
@Configuration
public class TimeConfig {

    @Bean
    @Profile("!test")                 // every profile EXCEPT test
    public Clock systemClock() {
        return Clock.systemUTC();
    }

    @Bean
    @Profile("test")
    public Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    }
}

@Service
public class InvoiceService {
    private final Clock clock;
    public InvoiceService(Clock clock) { this.clock = clock; }   // depends on the abstraction
    public Instant now() { return clock.instant(); }
}
```

```java
@SpringJUnitConfig({TimeConfig.class, InvoiceService.class})
@ActiveProfiles("test")              // activates the fixed clock
class InvoiceServiceTest {
    @Autowired InvoiceService svc;

    @Test
    void usesFixedClockUnderTestProfile() {
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), svc.now());
    }
}
```

The design lesson is that **time is a dependency** — injecting `Clock` instead of calling `Instant.now()` statically is what makes the test possible without mocking static methods. The profile mechanism does the selection at context-build time, so only one `Clock` ever exists in a given context, and there's no ambiguity to resolve. **Edge case:** if both profiles were accidentally active you'd get a `NoUniqueBeanDefinitionException`; marking one `@Primary` or using mutually exclusive `@Profile` expressions (`test` vs `!test`) prevents that. This is the smallest possible illustration of "depend on abstractions, let the container choose the concretion."

#### Q105. [Coding] Implement a `FactoryBean` that builds an object whose concrete type is decided at runtime.

`FactoryBean` earns its keep when the produced object needs construction *logic* (branching, lookups, builder calls) that a simple `@Bean` method could also do — but the canonical case is integrating a library, or returning different implementations based on configuration.

```java
public class CacheFactoryBean implements FactoryBean<Cache> {

    private String backend = "in-memory";     // set via property / setter injection
    public void setBackend(String b) { this.backend = b; }

    @Override
    public Cache getObject() {
        return switch (backend) {
            case "redis"     -> new RedisCache();
            case "in-memory" -> new InMemoryCache();
            default          -> throw new IllegalStateException("unknown backend: " + backend);
        };
    }

    @Override public Class<?> getObjectType() { return Cache.class; }  // interface, not impl
    @Override public boolean isSingleton() { return true; }
}

@Configuration
class CacheConfig {
    @Bean
    CacheFactoryBean cache(@Value("${cache.backend:in-memory}") String backend) {
        CacheFactoryBean fb = new CacheFactoryBean();
        fb.setBackend(backend);
        return fb;
    }
}
```

When something injects `Cache`, Spring calls `getObject()` and hands over the produced `Cache`, not the `CacheFactoryBean`. Returning the **interface** from `getObjectType()` (rather than a concrete class) is deliberate — it lets the factory swap implementations without callers caring, and lets autowiring-by-type still match. If you ever need the factory itself (rare), `context.getBean("&cache")` with the `&` dereference returns it. **The honest trade-off:** in greenfield code a plain `@Bean` method that returns the right `Cache` is simpler and type-safe; reach for `FactoryBean` mainly when a library forces it or when the produced type isn't statically known.

#### Q106. [Practical] How do you read a configuration value once at startup and fail fast if it's missing or invalid?

The robust pattern is to bind config into a validated object at context refresh, so a bad value aborts startup with a clear message rather than surfacing as a `NullPointerException` on the first request. `@ConfigurationProperties` + JSR-380 validation does this declaratively.

```java
@ConfigurationProperties(prefix = "billing")
@Validated
public record BillingProps(
        @NotBlank String currency,
        @Min(1) @Max(100) int retryAttempts,
        @NotNull Duration timeout) {}

@Configuration
@EnableConfigurationProperties(BillingProps.class)
class BillingConfig {}
```

```yaml
billing:
  currency: USD
  retry-attempts: 3
  timeout: 5s          # Spring's Duration binding parses "5s", "500ms", "2m"
```

If `currency` is blank or `retry-attempts` is 0, the application **fails to start** with a `BindValidationException` naming the offending property — exactly the fail-fast behavior you want. Contrast this with `@Value("${billing.retry-attempts}")` scattered across classes: a missing property either injects `null`/throws lazily, and there's no central validation. **The why:** binding at startup converts a class of production incidents (misconfiguration discovered at 3am under load) into a deploy-time failure caught by your readiness probe before traffic arrives. Use `Duration`/`DataSize` types so the framework parses human-friendly units, and prefer immutable `record`-based binding (constructor binding is the default in Boot 3) so the config object can't be mutated after validation.

### 🟡 Intermediate — extended

#### Q107. [Coding] Implement the Strategy pattern with Spring so adding a new strategy requires zero changes to the dispatcher.

The open/closed payoff of DI is that you inject *all* implementations of an interface and route at runtime; a new strategy is just a new `@Component`. Spring collects them into a `Map<beanName, T>` or a `List<T>` automatically.

```java
public interface DiscountStrategy {
    boolean supports(CustomerTier tier);
    BigDecimal apply(BigDecimal price);
}

@Component class GoldDiscount implements DiscountStrategy {
    public boolean supports(CustomerTier t) { return t == CustomerTier.GOLD; }
    public BigDecimal apply(BigDecimal p) { return p.multiply(new BigDecimal("0.80")); }
}
@Component class SilverDiscount implements DiscountStrategy {
    public boolean supports(CustomerTier t) { return t == CustomerTier.SILVER; }
    public BigDecimal apply(BigDecimal p) { return p.multiply(new BigDecimal("0.90")); }
}

@Service
public class PricingService {
    private final List<DiscountStrategy> strategies;  // ALL impls injected

    public PricingService(List<DiscountStrategy> strategies) {
        this.strategies = strategies;
    }

    public BigDecimal priceFor(CustomerTier tier, BigDecimal base) {
        return strategies.stream()
                .filter(s -> s.supports(tier))
                .findFirst()
                .map(s -> s.apply(base))
                .orElse(base);                         // no strategy → no discount
    }
}
```

Injecting `List<DiscountStrategy>` makes Spring gather every matching bean (ordered by `@Order`/`Ordered` if you care about precedence). Adding a `PlatinumDiscount` component is a one-file change — `PricingService` never moves. **Two refinements an interviewer probes:** (1) for direct key lookup, inject `Map<String, DiscountStrategy>` where the key is the bean name, avoiding the `supports`/filter scan — O(1) instead of O(n); (2) if two strategies claim the same tier you get nondeterministic precedence, so either make `supports` mutually exclusive or impose `@Order`. The deeper point is that **collection injection is the idiomatic Spring expression of the strategy/visitor/chain patterns**, and it's what makes plugin-style extensibility trivial.

#### Q108. [Coding] Inject a fresh prototype instance per call into a singleton, three correct ways.

The classic trap: a `prototype` injected into a `singleton` is resolved *once*, so you get the same instance forever. To get a new one per use you must ask the container each time. Here are the three idiomatic mechanisms with their trade-offs.

```java
@Component @Scope("prototype")
class Worker { /* stateful, short-lived */ }

// ---- Way 1: ObjectProvider (recommended — type-safe, no proxy, fluent API) ----
@Service
class ServiceA {
    private final ObjectProvider<Worker> workers;
    ServiceA(ObjectProvider<Worker> workers) { this.workers = workers; }
    void run() { Worker w = workers.getObject(); /* fresh every call */ }
}

// ---- Way 2: @Lookup method injection (Spring overrides this method via CGLIB) ----
@Service
abstract class ServiceB {
    void run() { Worker w = createWorker(); /* fresh every call */ }
    @Lookup protected abstract Worker createWorker();   // container implements it
}

// ---- Way 3: scoped proxy on the prototype (transparent, but watch the gotcha) ----
@Component @Scope(value = "prototype", proxyMode = ScopedProxyMode.TARGET_CLASS)
class ProxiedWorker { }
@Service
class ServiceC {
    private final ProxiedWorker worker;   // a proxy; each METHOD CALL hits a fresh target
    ServiceC(ProxiedWorker worker) { this.worker = worker; }
}
```

`ObjectProvider` is the cleanest in modern code: it's explicit at the call site, needs no class-level magic, and offers `getIfAvailable`/`getIfUnique` for optional resolution. `@Lookup` is elegant but forces the bean to be non-final/abstract so CGLIB can override the method — it surprises people who don't expect their service to be subclassed. The **scoped-proxy** approach is transparent but has a subtle gotcha: a *new prototype is created on each method invocation through the proxy*, which is rarely what you mean and can be wasteful. The interview-grade summary: prefer `ObjectProvider`; use `@Lookup` when you can't change the constructor; use a scoped proxy only when you genuinely want per-call resolution hidden behind a stable reference.

#### Q109. [Coding] Create a custom composed qualifier annotation to disambiguate beans semantically.

String qualifiers (`@Qualifier("fastClient")`) are typo-prone and untyped. A custom annotation meta-annotated with `@Qualifier` gives you compile-checked, refactorable, intention-revealing wiring.

```java
@Qualifier                                   // makes THIS a qualifier
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Resilient {}

@Qualifier
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface LowLatency {}

public interface HttpClient { String get(String url); }

@Component @Resilient   class RetryingClient   implements HttpClient { /* retries, slower */ }
@Component @LowLatency  class DirectClient     implements HttpClient { /* no retries, fast  */ }

@Service
class PaymentGateway {
    private final HttpClient client;
    // No string literal — the annotation type IS the qualifier
    PaymentGateway(@Resilient HttpClient client) { this.client = client; }
}
```

Because `@Resilient` is itself meta-annotated with `@Qualifier`, Spring treats a bean annotated `@Resilient` as carrying that qualifier and matches the injection point requiring `@Resilient`. The win over a raw string is real: rename refactoring works, the IDE autocompletes the marker, and a typo is a compile error rather than a runtime `NoSuchBeanDefinitionException`. You can also attach attributes to the annotation and Spring will match on those values (a `@DataStore(Type.SQL)` style qualifier). The trade-off is a little annotation boilerplate, justified anywhere you have two-plus interchangeable implementations whose distinction is *semantic* (resilient vs fast, primary vs replica) rather than incidental.

#### Q110. [Coding] Register a bean only when a property is set, and back off when the user defines their own.

This is the auto-configuration idiom you'd use when shipping a shared library or a feature behind a flag: gate creation with `@ConditionalOnProperty` and step aside with `@ConditionalOnMissingBean`.

```java
@AutoConfiguration
public class RateLimiterAutoConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "ratelimit", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean                              // user override wins
    public RateLimiter rateLimiter(@Value("${ratelimit.permits:100}") int permits) {
        return new TokenBucketRateLimiter(permits);
    }
}
```

```
ratelimit.enabled=true   → our TokenBucketRateLimiter is created
ratelimit.enabled=false  → no RateLimiter bean at all
user declares @Bean RateLimiter → @ConditionalOnMissingBean backs off, user's wins
```

The ordering is what makes this safe: `@ConditionalOnMissingBean` must be evaluated *after* user configuration, which is exactly why auto-configuration classes are processed last (registered via `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`). A subtle gotcha is that `@ConditionalOnMissingBean` matches by type by default and is order-sensitive within a single `@Configuration` — two auto-config beans of the same type in the wrong order can both register or both back off unexpectedly, so keep conditional beans in separate, ordered configurations. The design principle is "**opinionated defaults, trivial override**": the library does the right thing out of the box, a flag disables it, and a user `@Bean` replaces it without editing the library.

#### Q111. [Coding] Wire an `@Async` event listener that fires only after commit, and order multiple listeners.

Decoupling side effects from the core transaction is one of Spring's best features, but doing it *correctly* means firing after the commit and not blocking the publisher. Combine `@TransactionalEventListener(AFTER_COMMIT)`, `@Async`, and `@Order`.

```java
public record OrderPlaced(String orderId, BigDecimal total) {}

@Service
class OrderService {
    private final ApplicationEventPublisher events;
    OrderService(ApplicationEventPublisher e) { this.events = e; }

    @Transactional
    public void place(Order o) {
        repository.save(o);
        events.publishEvent(new OrderPlaced(o.id(), o.total()));  // queued, not yet delivered
    }
}

@Component
@EnableAsync
class OrderListeners {

    @Order(1)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void index(OrderPlaced e) { searchIndex.add(e.orderId()); }   // runs first

    @Order(2)
    @Async                                                         // off the publisher's thread
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void email(OrderPlaced e) { mailer.confirm(e.orderId()); }    // runs async, after index
}
```

`AFTER_COMMIT` guarantees the listener never fires for an order that rolled back — eliminating the "we emailed a confirmation for an order that doesn't exist" bug. `@Order` sequences the synchronous registration of listeners; `@Async` moves the slow email send off the request thread so a slow SMTP server can't stall the transaction's caller. **The trap to call out:** an `@Async` listener runs on a *different thread with no transaction*, so if it needs DB access it starts a fresh transaction — and crucially, an exception in an `@Async` `AFTER_COMMIT` listener is *swallowed* (the commit already happened), so you need your own error handling/dead-letter logic. For durability across restarts, this in-process bus isn't enough — you'd promote it to a transactional outbox (see the advanced outbox question).

#### Q112. [Coding] Manage a transaction programmatically with `TransactionTemplate`, and explain when to prefer it over `@Transactional`.

Declarative `@Transactional` is the default, but programmatic control via `TransactionTemplate` (or `TransactionalOperator` for reactive) wins when the transactional boundary is *dynamic*, *fine-grained*, or *not aligned with a method*.

```java
@Service
public class BatchImporter {
    private final TransactionTemplate tx;
    private final JdbcTemplate jdbc;

    public BatchImporter(PlatformTransactionManager txManager, JdbcTemplate jdbc) {
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.jdbc = jdbc;
    }

    public void importInChunks(List<Row> rows) {
        // commit every 500 rows so a failure near the end doesn't roll back everything
        for (List<Row> chunk : Lists.partition(rows, 500)) {
            tx.executeWithoutResult(status -> {
                try {
                    chunk.forEach(r -> jdbc.update("INSERT ...", r.args()));
                } catch (DataAccessException ex) {
                    status.setRollbackOnly();      // explicit rollback decision
                    throw ex;
                }
            });
        }
    }
}
```

The reasons to go programmatic: (1) you need **multiple commits inside one method** (chunked batch processing) which a single `@Transactional` can't express; (2) the boundary depends on **runtime conditions** (only wrap in a transaction if `n > threshold`); (3) you want to **avoid the proxy entirely** so self-invocation isn't an issue — `TransactionTemplate` works on a plain method call because it's not AOP-based. The cost is more verbose code and the responsibility of calling `setRollbackOnly()` yourself. The decision rule: **default to `@Transactional` for its clarity; reach for `TransactionTemplate` when the unit of work doesn't map cleanly onto a single method boundary** — batch jobs and conditional transactions are the textbook cases.

#### Q113. [Coding] Safely evaluate a SpEL expression that contains user-supplied data without opening an RCE.

SpEL is Turing-complete and can invoke arbitrary Java (`T(java.lang.Runtime).getRuntime().exec(...)`), so concatenating user input into an expression is a remote-code-execution vulnerability. The fix is to keep the *expression* a fixed literal and pass user data as *variables* in a locked-down evaluation context.

```java
// ❌ DANGEROUS — user controls the expression text → RCE
public Object evil(String userInput) {
    return new SpelExpressionParser().parseExpression(userInput).getValue();
}

// ✅ SAFE — fixed expression, user data is a bound variable, restricted context
public boolean isEligible(int userAge, String userTier) {
    ExpressionParser parser = new SpelExpressionParser();
    Expression expr = parser.parseExpression("#age >= 18 and #tier == 'GOLD'"); // literal

    // SimpleEvaluationContext disallows type references, constructors, bean refs
    EvaluationContext ctx = SimpleEvaluationContext.forReadOnlyDataBinding().build();
    ctx.setVariable("age", userAge);
    ctx.setVariable("tier", userTier);

    return Boolean.TRUE.equals(expr.getValue(ctx, Boolean.class));
}
```

Two defenses combine here. First, the **expression is a constant in your source code** — users never control the grammar, only the values of `#age`/`#tier`. Second, `SimpleEvaluationContext` (introduced precisely for this) is a hardened context that blocks the dangerous parts of SpEL: no `T(...)` type references, no constructor invocation, no bean resolution — only property access and the operators you'd want in a rule. By contrast, the default `StandardEvaluationContext` enables the full language and must never touch untrusted data. This is the exact mistake behind several Spring CVEs (expression injection in routing/validation paths). The rule for interviews: **expressions are code; treat them like SQL — parameterize, don't concatenate** — and pick `SimpleEvaluationContext` for any data-binding scenario.

#### Q114. [Coding] Implement a distributed-lock guard for `@Scheduled` so a job runs once across a cluster.

By default `@Scheduled` fires on *every* instance, so a horizontally-scaled service runs the job N times. The standard solution is a shared lock in the database/Redis; you can wire it with a small AOP-style guard or, in practice, the ShedLock library. Here's the design and a minimal hand-rolled version to show the mechanism.

```java
@Component
public class ReportJob {

    private final LockProvider lock;     // backed by a DB row or Redis SETNX
    ReportJob(LockProvider lock) { this.lock = lock; }

    @Scheduled(cron = "0 0 * * * *")     // top of every hour, on every instance
    public void runHourly() {
        // acquire a lock keyed by job name with a TTL slightly longer than max runtime
        Optional<Lock> held = lock.tryAcquire("hourly-report", Duration.ofMinutes(50));
        if (held.isEmpty()) {
            return;                       // another instance owns it this hour
        }
        try (Lock l = held.get()) {
            generateReport();             // exactly one instance does the work
        }
    }
}
```

The two distinct problems an interviewer wants separated: **(1) cross-instance duplication** — solved by a *shared* lock (DB unique row, Redis `SET key val NX PX ttl`, or ShedLock's `@SchedulerLock`), not by anything local. **(2) self-overlap** — a slow run still executing when the next trigger fires; solved by Spring's single-threaded scheduler default *or* by making the lock TTL/`fixedDelay` enforce no overlap. The critical detail is the **TTL must exceed the worst-case runtime** but be short enough that a crashed holder releases the lock — too short and two instances run; too long and a dead instance blocks the job. In production, prefer **ShedLock** (`@SchedulerLock(name="hourly-report", lockAtMostFor="50m")`) over hand-rolled logic because it handles clock skew and the release-on-crash semantics correctly; the hand-rolled version above is for illustrating *why* a shared, TTL'd lock is the right primitive.

#### Q115. [Coding] Validate method arguments and return values with `@Validated` at the service layer.

Bean Validation isn't just for web controllers — `@Validated` on a Spring bean enables method-level validation via an AOP interceptor, enforcing constraints on parameters and return values of any service method. This pushes invariants to the boundary instead of scattering manual `if` checks.

```java
@Service
@Validated                               // turns on the MethodValidationPostProcessor for this bean
public class AccountService {

    public Account open(@NotBlank String owner,
                        @Min(0) BigDecimal initialDeposit) {
        // if owner is blank or deposit < 0, a ConstraintViolationException is thrown
        return repository.save(new Account(owner, initialDeposit));
    }

    @NotNull                             // validates the RETURN value too
    public Account find(@NotNull String id) {
        return repository.findById(id).orElseThrow();
    }
}
```

`@Validated` (Spring's annotation, not `jakarta.validation.Valid`) on the *class* is what activates the `MethodValidationPostProcessor`, which proxies the bean and validates each call. Violations throw `ConstraintViolationException` (note: *not* `MethodArgumentNotValidException`, which is the MVC binding case) — so your exception handler must map both. **The gotchas worth naming:** because it's proxy-based, method validation has the same self-invocation and `public`-method constraints as all Spring AOP; and to validate a nested object's fields you need `@Valid` (cascade) on the parameter, while `@NotNull`/`@Min` validate the parameter itself. The value is centralizing input contracts: the service guarantees its own preconditions, so callers (controllers, message listeners, other services) all get the same enforcement without duplicating checks.

### 🟠 Advanced — extended

#### Q116. [Coding] Programmatically register beans at startup from a scan of the classpath using `BeanDefinitionRegistryPostProcessor`.

When you need to register beans whose number/identity isn't known until you inspect the environment or classpath — a plugin system, one repository proxy per discovered entity, one client per configured tenant — you do it by editing the registry before instantiation. `BeanDefinitionRegistryPostProcessor` is the precise hook.

```java
@Component
public class TenantClientRegistrar implements BeanDefinitionRegistryPostProcessor {

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
        // imagine tenants come from env/config discovered at startup
        for (String tenant : List.of("acme", "globex", "initech")) {
            BeanDefinition def = BeanDefinitionBuilder
                    .genericBeanDefinition(TenantClient.class)
                    .addConstructorArgValue(tenant)
                    .setScope(BeanDefinition.SCOPE_SINGLETON)
                    .getBeanDefinition();
            registry.registerBeanDefinition("tenantClient_" + tenant, def);
        }
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory bf) { /* no-op */ }
}
```

This runs in the `BeanFactoryPostProcessor` phase — *after* definitions are loaded but *before* any bean is instantiated — which is the only safe time to add definitions (add them later and they may be missed by autowiring or condition evaluation). The result is three real, fully-managed `TenantClient` beans that participate in autowiring, lifecycle, and AOP exactly as if you'd hand-written three `@Bean` methods. `BeanDefinitionRegistryPostProcessor` extends `BeanFactoryPostProcessor` and adds the registry-mutation callback; Spring Data uses this exact mechanism to materialize a repository implementation for every `@Repository` interface it finds. **The caution:** registered beans are invisible to compile-time tooling and to AOT processing unless you also contribute AOT hints, so use this for genuinely dynamic cardinality, not as a substitute for static config.

#### Q117. [Coding] Implement multi-tenant datasource routing with `AbstractRoutingDataSource`.

The canonical Spring answer to "route to a different database per tenant/request" is `AbstractRoutingDataSource`: a `DataSource` that delegates to one of several real datasources based on a key you compute per call (usually from a `ThreadLocal` set by a filter).

```java
public final class TenantContext {
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();
    public static void set(String tenant) { CURRENT.set(tenant); }
    public static String get() { return CURRENT.get(); }
    public static void clear() { CURRENT.remove(); }            // MUST clear (pooled threads)
}

public class TenantRoutingDataSource extends AbstractRoutingDataSource {
    @Override
    protected Object determineCurrentLookupKey() {
        return TenantContext.get();                              // picks the target DS
    }
}

@Configuration
class DataSourceConfig {
    @Bean
    DataSource dataSource(DataSource acmeDs, DataSource globexDs) {
        TenantRoutingDataSource routing = new TenantRoutingDataSource();
        routing.setTargetDataSources(Map.of("acme", acmeDs, "globex", globexDs));
        routing.setDefaultTargetDataSource(acmeDs);
        return routing;
    }
}

@Component
class TenantFilter extends OncePerRequestFilter {
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            TenantContext.set(req.getHeader("X-Tenant-Id"));
            chain.doFilter(req, res);
        } finally {
            TenantContext.clear();                              // prevent leak across requests
        }
    }
}
```

`determineCurrentLookupKey()` is called on *every* `getConnection()`, so the routing decision is per-operation and transparent to JPA/`JdbcTemplate` above it. The two production-critical details: **(1) you must `clear()` the `ThreadLocal` in a `finally`** or a pooled thread serving the next request inherits the previous tenant's database — a catastrophic data-isolation bug. **(2) Transactions bind the connection at transaction start**, so the tenant must be set *before* the `@Transactional` boundary; switching tenants mid-transaction does nothing. For schema-per-tenant or wildly different pools this scales to dozens of tenants; beyond that (hundreds/thousands) the connection-pool multiplication becomes the bottleneck and you move to a single pool with `SET search_path`/catalog switching or a dedicated tenant-aware pool.

#### Q118. [Coding] Design a pluggable extension SPI for your platform using Spring's `@Import` machinery.

The goal: third parties (or feature teams) drop a jar on the classpath and their handlers light up, without the core editing a registry. Spring's `ImportSelector` / `ImportBeanDefinitionRegistrar` + a custom `@Enable...` annotation is the idiomatic platform-extension pattern (it's how `@EnableScheduling`, `@EnableCaching`, and Boot starters work).

```java
// 1. Core defines the contract and an enabling annotation
public interface Plugin { String name(); void execute(Context ctx); }

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Import(PluginRegistrar.class)            // importing the annotation triggers registration
public @interface EnablePlugins {
    String[] basePackages() default {};
}

// 2. The registrar discovers and registers plugin beans from metadata
public class PluginRegistrar implements ImportBeanDefinitionRegistrar {
    @Override
    public void registerBeanDefinitions(AnnotationMetadata meta, BeanDefinitionRegistry reg) {
        var attrs = meta.getAnnotationAttributes(EnablePlugins.class.getName());
        // scan basePackages for @Component Plugin impls, or read a service-loader file,
        // then register each discovered Plugin as a bean definition...
        // (scanner.findCandidateComponents(pkg).forEach(reg::registerBeanDefinition))
    }
}

// 3. The core consumes ALL plugins via collection injection
@Service
class PluginEngine {
    private final List<Plugin> plugins;
    PluginEngine(List<Plugin> plugins) { this.plugins = plugins; }
    void runAll(Context c) { plugins.forEach(p -> p.execute(c)); }
}
```

The design layers cleanly: the **contract** (`Plugin`) and the **enabling annotation** live in a core API jar; the **registrar** translates `@EnablePlugins` metadata into bean definitions; and the **engine** consumes whatever was registered via `List<Plugin>` injection so it's oblivious to how many plugins exist. Why `ImportBeanDefinitionRegistrar` over plain component scanning? Because it lets the *core* control discovery policy (which packages, which service-loader files, conditional gating) rather than relying on every plugin author to be inside the app's scan path — essential when plugins ship in separate jars. The honest trade-offs to raise: registrars run before the context is fully formed so they can't inject other beans (only read metadata/environment); and dynamically-registered beans need explicit AOT hints to survive native-image compilation. For a simpler in-house plugin system where all code is in the scan path, plain `List<Plugin>` injection (Q107) is enough — reserve the `@Import` machinery for cross-jar, library-grade extensibility.

#### Q119. [Coding] Wrap every repository bean with a metrics-collecting proxy using a `BeanPostProcessor`.

When you want a cross-cutting concern applied to a *category* of beans without annotating each one (or without an aspect's pointcut), a `BeanPostProcessor` that returns a proxy from `postProcessAfterInitialization` is the surgical tool — and it runs at the exact lifecycle phase where Spring's own AOP proxies are created.

```java
@Component
public class MetricsProxyBeanPostProcessor implements BeanPostProcessor {

    private final MeterRegistry meters;
    MetricsProxyBeanPostProcessor(MeterRegistry meters) { this.meters = meters; }

    @Override
    public Object postProcessAfterInitialization(Object bean, String name) {
        if (!isRepository(bean)) return bean;                 // only wrap repositories

        return Proxy.newProxyInstance(
            bean.getClass().getClassLoader(),
            bean.getClass().getInterfaces(),                  // JDK proxy → bean must impl iface
            (proxy, method, args) -> {
                Timer.Sample sample = Timer.start(meters);
                try {
                    return method.invoke(bean, args);
                } finally {
                    sample.stop(meters.timer("repo.calls",
                        "repo", bean.getClass().getSimpleName(),
                        "method", method.getName()));
                }
            });
    }

    private boolean isRepository(Object bean) {
        return AnnotationUtils.findAnnotation(bean.getClass(), Repository.class) != null;
    }
}
```

Returning a *different object* from `postProcessAfterInitialization` is legal and is exactly how AOP works — Spring uses whatever you return as the bean. Here a JDK dynamic proxy times every interface method. Two correctness points dominate: **(1) ordering** — if another BPP (like the AOP auto-proxy creator) also wants to wrap this bean, you must reason about order (`Ordered`), or you end up proxying a proxy or losing one layer; **(2) proxy type** — JDK proxies require the bean to implement an interface (repositories usually do); for concrete classes you'd use CGLIB via `ProxyFactory` instead of `java.lang.reflect.Proxy`. In practice you'd usually prefer a Micrometer `@Timed` aspect or `MeterBinder` for this, but the BPP approach wins when the rule is "every bean matching condition X" rather than "every method matching pointcut Y," and it's the canonical demonstration that *AOP proxies are just BPP output*.

#### Q120. [Coding] Implement a transactional outbox so events publish reliably with the database commit.

The in-process event bus loses messages if the process dies after commit but before delivery. The **transactional outbox** writes the event to a DB table *in the same transaction* as the business change, then a separate poller publishes and marks it sent — guaranteeing at-least-once delivery without distributed transactions.

```java
@Entity @Table(name = "outbox")
class OutboxEvent {
    @Id @GeneratedValue Long id;
    String type;
    @Column(columnDefinition = "jsonb") String payload;
    Instant createdAt;
    boolean published;
}

@Service
class OrderService {
    private final OrderRepository orders;
    private final OutboxRepository outbox;

    @Transactional                                   // ONE transaction: order + outbox row
    public void place(Order o) {
        orders.save(o);
        outbox.save(new OutboxEvent("OrderPlaced", toJson(o), Instant.now(), false));
        // both commit atomically, or neither does — no lost event, no phantom event
    }
}

@Component
class OutboxPublisher {
    private final OutboxRepository outbox;
    private final MessageBroker broker;

    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void drain() {
        for (OutboxEvent e : outbox.findTop100ByPublishedFalseOrderByCreatedAt()) {
            broker.publish(e.type, e.payload);       // at-least-once: may re-send on crash
            e.published = true;                      // dirty-checked, committed by @Transactional
        }
    }
}
```

The key insight is that the outbox write and the business write share a single local transaction, so they're atomic *without* a two-phase commit across DB and broker — you've reduced an unsolvable distributed-commit problem to a solvable single-database one. The poller provides **at-least-once** semantics (a crash after `broker.publish` but before commit re-sends on restart), which is why downstream consumers must be **idempotent** (dedupe by event id). Compared to `@TransactionalEventListener(AFTER_COMMIT)` — which is in-memory and lost on crash between commit and listener — the outbox is durable across restarts at the cost of a polling table and a slight latency. Production refinements: index `(published, created_at)`, add a `SELECT ... FOR UPDATE SKIP LOCKED` to let multiple publishers drain in parallel, and prune or partition the table. This is the staff-level answer to "how do you publish events reliably when you also have a database transaction."

#### Q121. [Coding] Solve the self-invocation problem three ways and justify the choice.

Self-invocation — an internal call from one method of a bean to another `@Transactional`/`@Cacheable`/`@Async` method of the *same* bean — bypasses the proxy because the call goes through `this`, not the proxy reference. Here are three correct fixes with their trade-offs.

```java
// THE BUG: outer() calls inner() via `this` → @Transactional on inner() does nothing
@Service
class BuggyService {
    public void outer() { inner(); }
    @Transactional public void inner() { /* never runs in a tx */ }
}

// FIX 1 (best): extract inner() to a separate bean — the call now crosses a proxy
@Service
class OuterService {
    private final InnerService inner;
    OuterService(InnerService inner) { this.inner = inner; }
    public void outer() { inner.doWork(); }      // proxied call → advice applies
}
@Service
class InnerService {
    @Transactional public void doWork() { /* runs in a tx */ }
}

// FIX 2: self-inject a proxy reference (@Lazy avoids the circular-dependency failure)
@Service
class SelfRefService {
    @Autowired @Lazy private SelfRefService self;
    public void outer() { self.inner(); }        // goes through the proxy
    @Transactional public void inner() { /* runs in a tx */ }
}

// FIX 3: expose the current proxy via AopContext (requires exposeProxy = true)
@EnableAspectJAutoProxy(exposeProxy = true)
@Service
class AopContextService {
    public void outer() { ((AopContextService) AopContext.currentProxy()).inner(); }
    @Transactional public void inner() { /* runs in a tx */ }
}
```

**Fix 1 (extract to another bean) is almost always right** because the need for self-invocation through a proxy is usually a sign the two responsibilities belong in different objects — it fixes the design, not just the symptom, and keeps the code container-independent. **Fix 2 (self-injection with `@Lazy`)** works and is sometimes pragmatic for a single hot path, but it's a code smell that signals the class is doing too much, and the self-reference is confusing to readers. **Fix 3 (`AopContext.currentProxy()`)** is the most explicit but couples your code to the AOP machinery (`exposeProxy=true`, a cast to your own type) and is the least readable — reserve it for cases where you truly can't refactor. The interview-grade conclusion: the existence of self-invocation pain is feedback that a class has two collaborating responsibilities; **prefer extraction**, and treat the other two as escape hatches.

#### Q122. [Coding] Compose two `@Around` aspects with deterministic, correct ordering.

When multiple aspects advise the same join point, their relative `@Order` determines nesting — lower order is *more outer* (wraps the others). Getting this right is essential when, say, retry must surround the transaction, and metrics should measure the whole thing including retries.

```java
@Aspect @Component
@Order(10)                                   // lowest order → OUTERMOST
class MetricsAspect {
    @Around("@annotation(Monitored)")
    public Object measure(ProceedingJoinPoint pjp) throws Throwable {
        long t = System.nanoTime();
        try { return pjp.proceed(); }
        finally { record(pjp.getSignature(), System.nanoTime() - t); }
    }
}

@Aspect @Component
@Order(20)                                   // middle → wraps the transaction, inside metrics
class RetryAspect {
    @Around("@annotation(Monitored)")
    public Object retry(ProceedingJoinPoint pjp) throws Throwable {
        for (int i = 1; ; i++) {
            try { return pjp.proceed(); }
            catch (TransientDataAccessException e) {
                if (i >= 3) throw e;         // each proceed() enters a FRESH transaction
            }
        }
    }
}
// @Transactional advisor defaults to Ordered.LOWEST_PRECEDENCE → INNERMOST here
```

The resulting nesting is `Metrics( Retry( Transaction( method ) ) )`. This order is deliberate and correct: **retry must be outside the transaction** so each attempt gets a *new* transaction — retrying inside a transaction that's already marked rollback-only is useless (the second attempt commits into a doomed transaction). **Metrics outside retry** measures total latency including retries, which is what you want to alarm on. The mechanism is that with two `@Around` advices, the lower-order advice's `proceed()` invokes the next advice in the chain, so order ascends inward. The classic bug an interviewer is fishing for: forgetting that `@Transactional` defaults to `LOWEST_PRECEDENCE` (innermost), so a retry aspect with a *higher* number than that would land *inside* the transaction and silently fail to retry effectively. Always set explicit `@Order` on custom aspects relative to the transaction advisor (configurable via `@EnableTransactionManagement(order=...)`) rather than relying on luck.

#### Q123. [Coding] Implement `SmartLifecycle` to start and stop a background component in the right order during context start/stop.

For components that hold resources or background threads — a Kafka consumer loop, a scheduler, a connection to an external system — `@PostConstruct`/`@PreDestroy` are too coarse: they don't give you start/stop *ordering* relative to other components, and `@PostConstruct` runs before the whole context is ready. `SmartLifecycle` solves both.

```java
@Component
public class MessageConsumer implements SmartLifecycle {

    private volatile boolean running = false;
    private ExecutorService loop;

    @Override public int getPhase() { return Integer.MAX_VALUE; }  // start LAST, stop FIRST
    @Override public boolean isAutoStartup() { return true; }

    @Override
    public void start() {
        loop = Executors.newSingleThreadExecutor();
        running = true;
        loop.submit(this::poll);          // begins consuming only after context is fully up
    }

    @Override
    public void stop() {
        running = false;
        loop.shutdown();                  // stop accepting work
        try { loop.awaitTermination(30, TimeUnit.SECONDS); }   // drain in-flight
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    @Override public boolean isRunning() { return running; }

    private void poll() { while (running) { /* consume and process */ } }
}
```

`SmartLifecycle` beats `@PostConstruct` for three reasons. **(1) Phases:** `getPhase()` orders start/stop across components — lower phases start first and stop last; here `MAX_VALUE` means the consumer starts after everything else is ready (so it never processes a message before its dependencies are up) and stops first (so it stops accepting work before downstream beans close). **(2) Lifecycle alignment:** `start()` runs after the context is *fully refreshed*, not mid-construction like `@PostConstruct`, so all collaborators are guaranteed initialized. **(3) Graceful stop:** `stop()` is invoked on context close, giving you a drain window — critical for clean shutdown on Kubernetes `SIGTERM`. The `isRunning()` flag lets Spring know whether to call `stop()`. The trade-off versus `@PreDestroy` is that `SmartLifecycle` participates in the *phased* shutdown protocol, which is exactly what you need when stop order matters; `@PreDestroy` fires in undefined order relative to other beans' destruction.

#### Q124. [Coding] Design the configuration and DI boundaries for a hexagonal (ports-and-adapters) application so the domain stays Spring-free.

The architectural goal is that the **domain/application core has no Spring dependency** — it's plain Java testable without a container — while Spring lives only in the adapter and configuration layers. This keeps the most valuable code framework-agnostic and the framework where it belongs: wiring.

```
                ┌─────────────────── adapters (Spring-aware) ───────────────────┐
   HTTP ───▶ WebAdapter ─┐                                  ┌─ JpaOrderRepository ──▶ DB
   Kafka ──▶ MsgAdapter ─┤                                  ├─ HttpPaymentClient  ──▶ API
                         ▼                                  ▼
              ┌──────── application core (NO Spring) ────────┐
              │  PlaceOrderUseCase (depends on PORTS only)   │
              │    in-port:  PlaceOrder (interface)          │
              │    out-port: OrderRepository, PaymentPort    │
              └──────────────────────────────────────────────┘
```

```java
// application core — pure Java, no @Service, no Spring imports
public class PlaceOrderService implements PlaceOrder {        // in-port impl
    private final OrderRepository repo;                       // out-port (interface)
    private final PaymentPort payment;                        // out-port (interface)
    public PlaceOrderService(OrderRepository repo, PaymentPort payment) {
        this.repo = repo; this.payment = payment;
    }
    public OrderId place(NewOrder o) { /* pure domain logic */ }
}

// configuration layer — the ONLY place that knows about Spring + wires core to adapters
@Configuration
class CoreWiringConfig {
    @Bean
    PlaceOrder placeOrder(OrderRepository repo, PaymentPort payment) {  // adapters injected
        return new PlaceOrderService(repo, payment);          // plain `new`, container-managed
    }
}
```

The discipline that makes this work: the core declares **ports as interfaces** and never references concrete adapters or Spring annotations; the **adapters implement the out-ports** (`JpaOrderRepository implements OrderRepository`) and *are* `@Component`/`@Repository`; and a thin **configuration module** uses explicit `@Bean` methods to `new` up the core services, injecting the adapter beans. The payoff is enormous for testing — the entire use case runs in a unit test with hand-built fakes and *no application context*, giving millisecond feedback — and for portability, since swapping JPA for jOOQ or HTTP for gRPC touches only adapters. The trade-offs to acknowledge: more interfaces and a dedicated wiring module (some teams find the indirection heavy for a CRUD app), and you forgo conveniences like `@Transactional` *inside* the core (you put transactions on the adapter or a thin application-service shell). The staff-level framing: **Spring is a delivery mechanism, not your architecture** — confine it to the edges so the domain can outlive any framework choice.

#### Q125. [Coding] Use an `ApplicationContextInitializer` to programmatically register a property source before the context refreshes.

Sometimes you must inject configuration *before* any bean or even `@Configuration` class is processed — e.g., resolve a secret from a vault, compute a derived property, or set defaults that other property sources can override. `ApplicationContextInitializer` runs at the earliest possible point, before `refresh()`.

```java
public class VaultPropertyInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext ctx) {
        ConfigurableEnvironment env = ctx.getEnvironment();
        Map<String, Object> secrets = Map.of(
            "db.password", fetchFromVault("db.password"),
            "api.key",     fetchFromVault("api.key"));
        // addFirst → highest precedence, overrides application.yml; addLast → only a default
        env.getPropertySources().addFirst(
            new MapPropertySource("vault", secrets));
    }
    private String fetchFromVault(String key) { /* call vault client */ return "..."; }
}
```

```properties
# register it so Boot runs it during startup
# src/main/resources/META-INF/spring.factories
org.springframework.context.ApplicationContextInitializer=com.acme.VaultPropertyInitializer
```

The `initialize` callback fires after the `Environment` is prepared but *before* `refresh()` — meaning before `@Value`/`@ConfigurationProperties` resolution, before condition evaluation, before any bean is created. That timing is the whole point: properties you add here are visible to everything downstream, including `@ConditionalOnProperty` gating. **Precedence matters:** `addFirst` makes vault secrets win over `application.yml` (right for secrets that must override committed defaults), while `addLast` registers fallbacks. Compared to a `BeanFactoryPostProcessor`, the initializer runs even *earlier* and has no access to bean definitions — it's purely environment-level. You register it via `spring.factories`, a `context.initializer.classes` property, or `SpringApplicationBuilder.initializers(...)`. The use cases that genuinely need this: fetching secrets at boot, adapting config to the runtime platform (cloud metadata), and seeding properties that conditional auto-configuration depends on.

#### Q126. [Coding] Design a custom cache abstraction key generator and explain why default keys can collide or miss.

`@Cacheable`'s default key is built from the method arguments (`SimpleKeyGenerator`), which is fine for single-arg methods but silently dangerous when two methods share a cache name and argument shape, or when arguments don't implement `equals`/`hashCode` well. A custom `KeyGenerator` makes keys explicit and collision-proof.

```java
@Component("methodAwareKeyGen")
public class MethodAwareKeyGenerator implements KeyGenerator {
    @Override
    public Object generate(Object target, Method method, Object... params) {
        // include the method name so two methods can safely share one cache region
        return target.getClass().getSimpleName() + ":" + method.getName() + ":" +
               Arrays.stream(params).map(String::valueOf).collect(Collectors.joining(","));
    }
}

@Service
@CacheConfig(cacheNames = "users")
class UserService {

    @Cacheable(keyGenerator = "methodAwareKeyGen")
    public User findById(Long id) { /* ... */ }

    // Without method-aware keys, findById(1L) and findByLegacyId(1L) would COLLIDE
    @Cacheable(keyGenerator = "methodAwareKeyGen")
    public User findByLegacyId(Long id) { /* ... */ }

    // Or pin the key explicitly with SpEL for full control
    @Cacheable(cacheNames = "users", key = "#tenant + ':' + #id")
    public User findScoped(String tenant, Long id) { /* ... */ }
}
```

The default `SimpleKeyGenerator` uses *only the parameters*, not the method identity, so two methods in the same cache region with the same argument types produce identical keys — `findById(1L)` and `findByLegacyId(1L)` would return each other's cached value, a subtle and dangerous bug. The flip side is **cache misses**: if a parameter is a value object with a poor `equals`/`hashCode` (or a mutable object that changed), equal-by-business-meaning calls generate different keys and never hit. The fixes shown — a method-aware `KeyGenerator` or an explicit SpEL `key` — make the key deterministic and intention-revealing. **Design guidance for the interview:** prefer explicit `key = "#..."` SpEL for clarity on each method, use a custom `KeyGenerator` when you want a *uniform* keying policy across a service, always include a tenant/scope dimension in multi-tenant caches (a shared key across tenants is a data-leak vector), and ensure cache-key arguments are immutable with correct equality. Also remember `@Cacheable` is proxy-based, so self-invocation and `null`-handling (`unless`/`@Cacheable(unless="#result == null")`) are part of getting caching correct.

#### Q127. [Coding] Provide AOT/native-image reflection hints for a class Spring can't see statically.

In a GraalVM native image there's no runtime classpath scanning or open reflection — the "closed world" means any class accessed reflectively (JSON binding, a library, a dynamically-loaded plugin) must be declared at build time or it fails at runtime. Spring 6's `RuntimeHintsRegistrar` is how you contribute these hints in Java.

```java
public class AppRuntimeHints implements RuntimeHintsRegistrar {
    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        // allow reflective construction + field/method access (e.g., for Jackson binding)
        hints.reflection().registerType(ExternalDto.class,
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                MemberCategory.INVOKE_DECLARED_METHODS,
                MemberCategory.DECLARED_FIELDS);

        // bundle a resource that's loaded by name at runtime
        hints.resources().registerPattern("templates/email.html");

        // register a JDK proxy that's created reflectively
        hints.proxies().registerJdkProxy(MyDynamicInterface.class);
    }
}

// activate the registrar
@Configuration
@ImportRuntimeHints(AppRuntimeHints.class)
class NativeConfig {}
```

The mental model is the **closed-world assumption**: at build time the AOT engine freezes the bean graph and computes reachability; anything reached only by reflection, resource lookup, or dynamic proxy is *invisible* to that analysis unless you tell it. Missing a hint produces a runtime failure (`ClassNotFoundException`, a reflective `InaccessibleObjectException`, or a silently-empty deserialization) rather than the graceful degradation you'd get on the JVM — which is why native images need this discipline. For common cases Spring provides shortcuts: `@RegisterReflectionForBinding(ExternalDto.class)` covers the serialization scenario, and most Spring/Boot infrastructure ships its own hints. The interview-grade point: **AOT trades runtime dynamism for startup speed and low memory**, and `RuntimeHintsRegistrar`/`@ImportRuntimeHints` is the seam where you re-declare the dynamism the analyzer can't infer — primarily reflection, resources, serialization, and JDK proxies. Test it by running `mvn -Pnative native:compile` (or the GraalVM `native-image` agent during JVM runs to *generate* hints automatically).

### 🔴 Expert — extended

#### Q128. [Coding] Implement reactive transaction management correctly, and explain why imperative `@Transactional` silently does nothing in a reactive chain.

In WebFlux/R2DBC, the transaction context must propagate through the Reactor `Context`, not a `ThreadLocal` — because a reactive pipeline hops threads freely. Using imperative `@Transactional` (backed by a `ThreadLocal`-bound `PlatformTransactionManager`) on a method returning `Mono`/`Flux` binds and commits the transaction synchronously when the method *returns the publisher*, long before the data actually flows — so it does nothing useful. The fix is a `ReactiveTransactionManager`.

```java
@Configuration
class R2dbcTxConfig {
    @Bean
    ReactiveTransactionManager txManager(ConnectionFactory cf) {
        return new R2dbcTransactionManager(cf);     // reactive, NOT DataSourceTransactionManager
    }
}

@Service
class TransferService {
    private final AccountRepo accounts;
    private final TransactionalOperator tx;         // programmatic reactive tx

    TransferService(AccountRepo accounts, ReactiveTransactionManager tm) {
        this.accounts = accounts;
        this.tx = TransactionalOperator.create(tm);
    }

    // Declarative also works on a reactive return type IF a ReactiveTransactionManager exists:
    @Transactional
    public Mono<Void> transferDeclarative(String from, String to, BigDecimal amt) {
        return accounts.debit(from, amt).then(accounts.credit(to, amt)).then();
    }

    // Programmatic — explicit boundary, composes with the reactive chain
    public Mono<Void> transferProgrammatic(String from, String to, BigDecimal amt) {
        return accounts.debit(from, amt)
                .then(accounts.credit(to, amt))
                .then()
                .as(tx::transactional);             // the WHOLE chain runs in one tx
    }
}
```

The crux is **context propagation mechanism**: imperative transactions live in `TransactionSynchronizationManager`'s `ThreadLocal`, which is meaningless across the thread hops of an asynchronous pipeline; reactive transactions live in the Reactor `Context`, which *does* propagate down the chain regardless of which thread executes each operator. So a `ReactiveTransactionManager` (`R2dbcTransactionManager`, or the reactive Mongo manager) is mandatory — you cannot reuse `DataSourceTransactionManager`. With that bean present, `@Transactional` on a `Mono`/`Flux`-returning method works because Spring's reactive transaction interceptor wires the transaction into the returned publisher's `Context`; `TransactionalOperator` is the programmatic equivalent for dynamic boundaries. The expert warning: **never mix blocking JDBC inside a reactive transaction** (it blocks the event loop and the transactions don't share context), and never expect an imperative `@Transactional` to govern a reactive flow — the two transaction models are fundamentally incompatible because their context-propagation substrates differ.

#### Q129. [Behavioral] Tell me about a time you had to overrule a team's preference for a Spring feature (e.g., heavy AOP or auto-config) because of production risk. How did you drive the decision?

**(STAR)** **Situation:** I joined a payments team where a previous lead had introduced a sprawling web of custom `@Around` aspects — one for auditing, one for retry, one for "soft multi-tenancy" via `ThreadLocal`, plus `@Transactional` — all advising the same service methods. Production was seeing sporadic, unreproducible bugs: occasional double-charges and audit records with the wrong tenant. The team's instinct was to add *another* aspect to "fix" the ordering.

**Task:** As the staff engineer, I had to decide whether to keep investing in the aspect stack or to unwind it, and I had to bring along a team that was emotionally invested in the "clean, declarative" design — without sounding like I was just dictating.

**Action:** First I made the problem *visible* rather than arguing from opinion: I wrote a focused integration test that logged the actual advice nesting (`AopUtils`/proceed tracing) and demonstrated that the retry aspect was nested *inside* the transaction, so retries were happening against a rollback-only transaction — that was the double-charge. I also showed the `ThreadLocal` tenant wasn't cleared on one error path, which was the audit mix-up. Then I proposed concrete, smaller-blast-radius alternatives: move retry *outside* the transaction with explicit `@Order`, replace the home-grown tenant aspect with a `OncePerRequestFilter` + `finally`-clear, and delete the audit aspect in favor of a transactional-outbox-backed `@TransactionalEventListener(AFTER_COMMIT)`. I framed it as "reduce the number of invisible interceptors on the hot path," not "AOP is bad." I ran a design review where the failing test did most of the persuading, and I committed to pairing on the migration so it wasn't a mandate from on high.

**Result:** We removed two of the four aspects, made ordering explicit on the remaining ones, and the double-charge and tenant-bleed incidents went to zero over the next quarter. Just as important, the team adopted a norm: any new aspect on a transactional path needs an explicit `@Order` and an integration test asserting the nesting. **Reflection:** the lesson I carry is that with senior teams you don't win architectural arguments by asserting principles — you win by making the failure mode reproducible and then offering a *less* clever alternative that's obviously safer. The technical insight (retry must wrap the transaction; `ThreadLocal`s must be cleared in `finally`) was necessary but not sufficient; the durable fix was the team norm.

#### Q130. [Behavioral] Describe leading a large, risky Spring framework upgrade (or DI re-architecture) where you had to balance velocity against stability across many teams.

**(STAR)** **Situation:** We had ~30 Spring Boot 2.7 services on Java 11, and a mandate to reach Boot 3 / Java 17 within two quarters to stay on supported versions (security patches). Several services were business-critical (checkout, auth), and the org had been burned before by a "big bang" framework upgrade that caused a multi-day outage.

**Task:** I was the technical lead for the migration program. My job was to get every service upgraded without a repeat outage, while not freezing feature work for two quarters — leadership explicitly would not accept a long code freeze.

**Action:** I split the risk into independent axes so we never changed two hard things at once. **Phase 1** was a pure JDK move: get everything to Java 17 *on Boot 2.7* first, behind the existing test suites — no namespace change, low risk. **Phase 2** was the `javax`→`jakarta` and Boot 3 migration, which I de-risked with automation: I built an OpenRewrite recipe pipeline (`UpgradeSpringBoot_3`) that mechanically rewrote imports and config, so the diff was reviewable and consistent rather than hand-edited per service. I picked a *low-traffic internal* service as the pilot, took it all the way to production, and turned the experience into a written runbook listing the real breakages we hit (Spring Security 6 lambda DSL, trailing-slash matching change, Sleuth → Micrometer Tracing). Then we rolled out in **waves of 3–4 services**, owning-team-driven but with my group pairing and reviewing, and we gated each wave on contract tests between services to catch wire-level regressions. I kept a public dashboard of which services were on which version so leadership could see steady progress instead of a scary all-or-nothing date.

**Result:** All 30 services migrated in the two-quarter window with zero customer-facing incidents; the worst issue was a staging-only failure from a transitive library with no jakarta release, which we caught in a wave and swapped before it shipped. Feature teams kept shipping because each wave was a few days of focused work, not a freeze. **Reflection:** the decision that mattered most was *separating the JDK upgrade from the namespace upgrade* — bundling them would have made every failure ambiguous. The second was investing early in the OpenRewrite pipeline and the pilot runbook; the up-front cost paid for itself by the third wave because reviews became mechanical. At staff level, the leadership skill was converting an intimidating monolithic deadline into a visible, parallelizable sequence that people could trust.

#### Q131. [Coding] Demonstrate how Spring propagates context (transaction, security, MDC) across `@Async` and explain the virtual-threads angle.

`@Async` moves work to a pool thread, which by default inherits *none* of the caller's `ThreadLocal`-based context — transaction, `SecurityContext`, and SLF4J `MDC` all vanish. Spring's `ContextSnapshot`/task-decorator mechanism (Micrometer Context Propagation, integrated in Spring 6) is how you carry it across.

```java
@Configuration
@EnableAsync
class AsyncConfig implements AsyncConfigurer {

    @Override
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(8);
        // decorate each task to snapshot-and-restore context across the thread hop
        exec.setTaskDecorator(runnable -> {
            ContextSnapshot snapshot = ContextSnapshotFactory.builder().build()
                    .captureAll();                 // captures MDC, security, tracing, etc.
            return () -> {
                try (ContextSnapshot.Scope scope = snapshot.setThreadLocals()) {
                    runnable.run();                // context restored on the worker thread
                }
            };
        });
        exec.initialize();
        return exec;
    }
}

@Service
class Notifier {
    @Async
    public CompletableFuture<Void> send(String userId) {
        log.info("sending");                       // MDC correlationId is now present
        return CompletableFuture.completedFuture(null);
    }
}
```

The principle is that **all of these contexts are `ThreadLocal`-backed and thread-confined**, so crossing a thread boundary loses them unless something explicitly snapshots the value on the submitting thread and restores it on the executing thread — which is exactly what a `TaskDecorator` wired to Micrometer's `ContextSnapshot` does. This is why a `@Transactional` *never* propagates into `@Async` (the new thread has no bound connection — a correctness footgun, not just a logging one), why `SecurityContextHolder` returns null in async code without `DelegatingSecurityContextExecutor`, and why trace/correlation ids drop out of async logs without this decorator. **The virtual-threads (Java 21+, Boot 3.2+) angle the interviewer is probing:** with `spring.threads.virtual.enabled=true`, each task may run on its own virtual thread, which *reduces* the need for pooling but does **not** change the `ThreadLocal` semantics — context still must be propagated across the submit/run boundary because it's still a different thread. Virtual threads also make `ThreadLocal` *more* expensive at massive scale (millions of carriers), which is part of why the platform is shifting toward `ScopedValue` (JEP 446+) and structured concurrency; the future-proof answer is to rely on the Micrometer context-propagation abstraction rather than hand-managing `ThreadLocal`s.

#### Q132. [Coding] Design a reusable Spring Boot starter (auto-configuration library) that other teams consume, including conditionals, properties, and AOT-readiness.

**(STAR-free design.)** A well-built starter is "drop the dependency, get sane defaults, override anything" — the same contract as Boot's own starters. The structure has three deliberate layers.

```
acme-ratelimit-starter        (thin: just dependencies + transitive autoconfigure)
acme-ratelimit-autoconfigure  (the @AutoConfiguration classes + conditionals)
acme-ratelimit-core           (the actual RateLimiter implementation, framework-light)
```

```java
@AutoConfiguration
@ConditionalOnClass(RateLimiter.class)                       // only if core is on classpath
@EnableConfigurationProperties(RateLimitProps.class)
public class RateLimitAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean                                // consumer override wins
    @ConditionalOnProperty(prefix = "acme.ratelimit", name = "enabled",
                           havingValue = "true", matchIfMissing = true)
    RateLimiter rateLimiter(RateLimitProps props) {
        return new TokenBucketRateLimiter(props.permitsPerSecond());
    }
}

@ConfigurationProperties("acme.ratelimit")
@Validated
record RateLimitProps(@Min(1) int permitsPerSecond) {
    RateLimitProps { if (permitsPerSecond == 0) permitsPerSecond = 100; }  // default
}
```

```
# src/main/resources/META-INF/spring/
#   org.springframework.boot.autoconfigure.AutoConfiguration.imports
com.acme.ratelimit.RateLimitAutoConfiguration
```

The design decisions that separate a good starter from a fragile one: **(1) split `starter` from `autoconfigure`** so the autoconfigure jar can be depended on without forcing the opinionated default dependency set — Boot's own convention. **(2) Gate everything with conditions** — `@ConditionalOnClass` so the config silently no-ops when the feature's classes are absent, and `@ConditionalOnMissingBean` so a consumer's own `RateLimiter` bean transparently replaces yours (the override contract). **(3) Register via the `AutoConfiguration.imports` file** (Boot 2.7+/3.x), *not* the legacy `spring.factories`. **(4) Type-safe, validated `@ConfigurationProperties`** with documented prefixes and an `additional-spring-configuration-metadata.json` so IDEs autocomplete the keys. **(5) AOT-readiness:** ship `RuntimeHints` for anything reflective and prefer `proxyBeanMethods=false` so the starter works in native images — consumers increasingly expect this. The trade-offs to call out: auto-configuration ordering (`@AutoConfigureBefore/After`) becomes load-bearing once multiple starters interact, and `@ConditionalOnMissingBean` is type-and-order sensitive, so you keep conditional beans isolated and test the *back-off* behavior explicitly (`ApplicationContextRunner` is the tool — it lets you assert "given this classpath and these properties, exactly these beans exist"). The staff-level marker is treating the starter as a *product with a compatibility contract*: documented properties, predictable back-off, and condition-evaluation transparency via `/actuator/conditions`.

#### Q133. [Coding] Write an `ApplicationContextRunner` test that proves auto-configuration behaves correctly under different classpaths and properties.

The right way to test conditional configuration (a starter, any `@Conditional` bean) is `ApplicationContextRunner` — it spins up a throwaway context per scenario in-memory, far cheaper than `@SpringBootTest`, and lets you assert exactly which beans exist given a classpath/property combination.

```java
class RateLimitAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RateLimitAutoConfiguration.class));

    @Test
    void createsRateLimiterByDefault() {
        runner.run(ctx -> assertThat(ctx).hasSingleBean(RateLimiter.class));
    }

    @Test
    void backsOffWhenUserDefinesOwn() {
        runner.withUserConfiguration(CustomRateLimiterConfig.class)
              .run(ctx -> assertThat(ctx).getBean(RateLimiter.class)
                                         .isInstanceOf(CustomRateLimiter.class));  // user's wins
    }

    @Test
    void disabledByProperty() {
        runner.withPropertyValues("acme.ratelimit.enabled=false")
              .run(ctx -> assertThat(ctx).doesNotHaveBean(RateLimiter.class));
    }

    @Test
    void backsOffWhenClassAbsent() {
        runner.withClassLoader(new FilteredClassLoader(RateLimiter.class))   // simulate missing dep
              .run(ctx -> assertThat(ctx).doesNotHaveBean(RateLimiter.class));
    }

    @Configuration
    static class CustomRateLimiterConfig {
        @Bean RateLimiter custom() { return new CustomRateLimiter(); }
    }
}
```

Each `run(...)` builds a minimal context, executes the assertion lambda, and tears it down — milliseconds per scenario versus seconds for a full `@SpringBootTest`, which matters when you're testing the *matrix* of conditions (class present/absent × property on/off × user override/none). The four tests above pin the entire contract of a starter: the default-on behavior, `@ConditionalOnMissingBean` back-off, `@ConditionalOnProperty` gating, and `@ConditionalOnClass` back-off (simulated with `FilteredClassLoader`, which hides a class from the context's classloader to mimic a missing dependency). This is the tool that turns "I think the conditions are right" into a regression-proof assertion — and it's exactly how Spring Boot tests its own auto-configurations. The expert habit: every conditional bean you ship has a corresponding `ApplicationContextRunner` test proving both that it appears when it should *and* that it correctly disappears/backs off when it shouldn't, because the back-off path is the one humans forget and break.

#### Q134. [Coding] Detect and prevent a context-cache explosion that's making the test suite slow, programmatically.

Spring caches the `ApplicationContext` across test classes keyed by configuration (the set of config classes, active profiles, `@MockBean`s, properties, etc.). Each *distinct* key builds a *new* context; a sprawling suite can silently build dozens, turning a 30-second run into 10 minutes. The fix is to make contexts *shareable* by stabilizing the cache key, and to detect the problem with the context-cache statistics.

```java
// Detect: log how many contexts were built and the cache hit ratio
@SpringBootTest
class ContextCacheDiagnosticsTest {
    @Test
    void reportCacheStats(@Autowired ApplicationContext ctx) {
        // ContextCache stats are exposed via the TestContext framework's statistics;
        // enable with: -Dspring.test.context.cache.maxSize=... and the logging category below
        // logging.level.org.springframework.test.context.cache=DEBUG
        // → logs "Spring test ApplicationContext cache statistics: [size=N, hitCount=..,
        //    missCount=.., ...]"  — a high missCount == a cache explosion.
    }
}
```

```java
// PREVENT — make the cache key identical across classes:

// 1. Share ONE base test config instead of bespoke @TestConfiguration per class
@SpringBootTest
abstract class BaseIntegrationTest {            // every IT extends this → same context key
    // shared @MockBeans, shared properties live here, NOT scattered per test class
}

// 2. Avoid sprinkling @MockBean with different combinations — each unique set = new context
//    Prefer a shared @TestConfiguration with fakes, reused everywhere.

// 3. Don't use @DirtiesContext unless truly necessary — it EVICTS the context, forcing a rebuild
//    @DirtiesContext  // ← every use of this is a deliberate, costly cache miss
```

The mechanism: the `ContextCache` key is a hash of everything that defines the context — config classes/locations, active profiles, property sources, `@MockBean`/`@MockitoBean` definitions, even `@TestPropertySource` values. Any variation forks a new context. So the *causes* of explosion are: scattered `@MockBean` combinations (each unique multiset is a new key — the single most common culprit), per-class `@TestPropertySource` with different values, gratuitous `@DirtiesContext` (which evicts rather than reuses), and many bespoke `@TestConfiguration` classes. The *prevention* is consolidation: a small number of shared base classes / `@TestConfiguration`s that thousands of tests reuse, so the cache hit rate approaches 100%. **Detection** is via `logging.level.org.springframework.test.context.cache=DEBUG`, which prints `hitCount`/`missCount`/`size` — a missCount climbing with your test-class count is the smoking gun. The staff-level framing: test-suite speed is an engineering deliverable, and the dominant lever in a Spring codebase is context reuse — measure the cache stats in CI and treat a rising miss count as a regression, the same way you'd treat a perf regression in production.

#### Q135. [Theory] At the deepest level, why does `@Transactional` self-invocation, `@Configuration` full-mode singleton enforcement, and AOP advice ALL stem from the *same* proxy mechanism — and what single mental model unifies them?

These three seemingly separate behaviors are surface manifestations of one fact: **Spring intercepts behavior by handing callers a *proxy* that wraps the real bean, and interception only happens when a call goes *through* that proxy.** Once you internalize "the bean you hold a reference to is usually not the raw object — it's a proxy that delegates," every one of these phenomena becomes a corollary.

```
   caller ──▶ [ PROXY ] ──(interception: tx/cache/async/advice)──▶ [ real bean ]
                  ▲
                  │ external calls go THROUGH the proxy  → interception fires
                  │ self-calls (this.method()) skip it   → interception is BYPASSED
```

Trace each behavior to the same root. **(1) `@Transactional` self-invocation does nothing** because the transactional advice lives in the proxy; an internal `this.inner()` call never touches the proxy, so the advice chain isn't entered — the call reaches the real method directly. **(2) `@Configuration` full-mode singleton enforcement** works because `@Configuration` classes are *themselves* CGLIB-proxied: when one `@Bean` method calls another (`a() { return new A(b()); }`), the call to `b()` goes through the configuration *proxy*, which intercepts it and returns the cached singleton instead of executing the method body — the identical "call goes through a proxy that intercepts" mechanism, applied to inter-bean references rather than transactions. Set `proxyBeanMethods=false` (lite mode) and the proxy disappears, so `b()` is a plain method call returning a fresh object — same cause, proxy removed. **(3) AOP advice (logging, retry, security)** is literally the general case: an `@Around` aspect is woven into the proxy, and it too only fires for calls routed through the proxy, which is why aspects also suffer self-invocation.

The unifying mental model is therefore: **Spring's declarative magic is interception, interception requires a proxy, and a proxy can only intercept calls that pass through it.** From this single idea you can *derive* — without memorizing — that self-invocation bypasses transactions/caching/async/advice, that proxies must be obtained via injection (not `new` and not `this`), that `final` classes/methods break CGLIB interception (nothing to override), that the proxy is a *different object* than the raw bean (so `==` identity and field access differ), and that `@Configuration` lite mode is just "the same proxy, turned off." The reason this matters at the expert level is that it converts a dozen disconnected "gotchas" into one principle: when something declarative "isn't working," your first question is always *"did this call actually go through the proxy?"* — and the answer explains transactions, caching, async, security, validation, and inter-`@Bean` semantics in one stroke. The only escape from the proxy's limits is to stop using proxies: compile/load-time AspectJ weaving rewrites the bytecode of the real class itself, so there is no proxy and no through-the-proxy requirement — which is precisely why AspectJ is the answer when you need to advise self-calls, `final` classes, or non-Spring objects.

## 📚 Further Reading

- *Spring in Action, 6th Edition* — Craig Walls (covers Spring 5/Boot 2; concepts map to 6/3).
- *Spring Boot Up & Running* — Mark Heckler (production-oriented, Boot 2.x→3 mindset).
- [Spring Framework Reference Documentation — Core](https://docs.spring.io/spring-framework/reference/core.html) (IoC, AOP, SpEL, events).
- [Spring Framework Reference — Data Access & Transactions](https://docs.spring.io/spring-framework/reference/data-access/transaction.html).
- [Spring Boot Reference — Native Image & AOT](https://docs.spring.io/spring-boot/reference/packaging/native-image/index.html).
- [Spring Boot 3.0 Migration Guide (official wiki)](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-3.0-Migration-Guide) and the [OpenRewrite Spring recipes](https://docs.openrewrite.org/recipes/java/spring).
