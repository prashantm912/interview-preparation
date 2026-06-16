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

## ✅ Key Takeaways

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

## 📚 Further Reading

- *Spring in Action, 6th Edition* — Craig Walls (covers Spring 5/Boot 2; concepts map to 6/3).
- *Spring Boot Up & Running* — Mark Heckler (production-oriented, Boot 2.x→3 mindset).
- [Spring Framework Reference Documentation — Core](https://docs.spring.io/spring-framework/reference/core.html) (IoC, AOP, SpEL, events).
- [Spring Framework Reference — Data Access & Transactions](https://docs.spring.io/spring-framework/reference/data-access/transaction.html).
- [Spring Boot Reference — Native Image & AOT](https://docs.spring.io/spring-boot/reference/packaging/native-image/index.html).
- [Spring Boot 3.0 Migration Guide (official wiki)](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-3.0-Migration-Guide) and the [OpenRewrite Spring recipes](https://docs.openrewrite.org/recipes/java/spring).
