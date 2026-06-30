# Testing (JUnit, Mockito, Integration)

[← Back to master index](../README.md)

Testing is where engineering judgment becomes visible: knowing *what* to test, at *which* level, and *how* to keep the suite fast, deterministic, and trustworthy. This guide covers the modern JVM testing stack as of 2026 — JUnit 5 (Jupiter), Mockito, AssertJ, Testcontainers, Spring Boot test slices — plus the strategic concepts interviewers probe at senior levels: the test pyramid, TDD/BDD, contract testing, mutation testing, coverage limits, flakiness, and performance/load testing. Answers favor practical, runnable Java over theory for its own sake.

## 🟢 Basic (0–2 yrs)

### Q1. [Theory] What is a unit test, and what makes a good one?

A **unit test** verifies the behavior of a single unit of work — typically one class or method — in isolation from its collaborators (databases, network, file system, other beans). A good unit test is **F.I.R.S.T.**:

- **Fast** — milliseconds, so you can run thousands on every save.
- **Isolated/Independent** — no shared mutable state; tests can run in any order.
- **Repeatable** — same result every run, on any machine, regardless of time zone or network.
- **Self-validating** — a clear pass/fail with assertions, no manual log inspection.
- **Timely** — written close to (ideally before) the production code.

A unit test answers "does this logic do what I intend?" It should test *behavior and outcomes*, not internal implementation details, so it survives refactoring.

### Q2. [Theory] Explain the test pyramid. Why is it shaped that way?

The **test pyramid** (Mike Cohn) describes the ideal *proportion* of tests by level:

```
        /\
       /e2e\        few   — slow, brittle, high-value end-to-end flows
      /------\
     / integ. \     some  — service + real DB/queue via Testcontainers
    /----------\
   /   unit     \   many  — fast, isolated, cover branching logic
  /--------------\
```

The base is wide because unit tests are **cheap, fast, and pinpoint failures precisely**. Higher levels are progressively **slower, more fragile, and harder to debug** (a failing e2e test could be any of dozens of components), so you want fewer of them — just enough to verify that the units wire together correctly. An **inverted pyramid** ("ice cream cone") — mostly slow UI/e2e tests — leads to multi-hour CI runs and flaky pipelines that teams learn to ignore.

### Q3. [Theory] Unit vs integration vs end-to-end tests — what's the difference?

- **Unit** — one class/method, collaborators mocked or stubbed. No Spring context, no I/O. Microseconds–milliseconds.
- **Integration** — multiple components working together against *real* infrastructure or a real slice of the framework: a repository against a real Postgres (via Testcontainers), or a controller plus its serialization and validation via `@WebMvcTest`. Verifies wiring, SQL, serialization, transactions.
- **End-to-end (e2e)** — the whole system deployed, exercised through its public entry point (HTTP API or UI), often hitting real or production-like dependencies. Verifies a full user journey.

Rule of thumb: as you go up, **confidence per test rises but speed and stability fall**.

### Q4. [Practical] Show the basic structure of a JUnit 5 test and explain the Arrange-Act-Assert pattern.

**Arrange-Act-Assert (AAA)**, also called Given-When-Then, structures each test into three readable phases: set up inputs, invoke the behavior, verify the outcome.

```java
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PriceCalculatorTest {

    @Test
    void appliesTenPercentDiscountForLoyalCustomers() {
        // Arrange
        PriceCalculator calc = new PriceCalculator();
        Order order = new Order(100.00, CustomerTier.LOYAL);

        // Act
        double total = calc.total(order);

        // Assert
        assertThat(total).isEqualTo(90.00);
    }
}
```

The test name describes the *behavior* ("applies ten percent discount for loyal customers"), so a failure report reads like a spec. Note `@Test` comes from `org.junit.jupiter.api` (JUnit 5), not the JUnit 4 `org.junit` package.

### Q5. [Theory] Describe the JUnit 5 lifecycle annotations and when each runs.

```
@BeforeAll        (once, before everything — must be static by default)
  ├─ @BeforeEach  (before test 1)
  │     test1()
  │  @AfterEach   (after test 1)
  ├─ @BeforeEach  (before test 2)
  │     test2()
  │  @AfterEach   (after test 2)
@AfterAll         (once, after everything — static by default)
```

- `@BeforeAll` / `@AfterAll` — run once per class; expensive shared setup (start a container, build a costly fixture). Must be `static` unless the class is `@TestInstance(Lifecycle.PER_CLASS)`.
- `@BeforeEach` / `@AfterEach` — run around *every* test method; reset per-test state so tests stay isolated.

By default JUnit 5 creates a **new test instance per method** (`PER_METHOD`), which is why instance fields don't leak between tests.

### Q6. [Practical] How do you assert that a method throws an exception in JUnit 5?

Use `assertThrows`, which captures the thrown exception so you can assert on its message or type. This is far cleaner than the JUnit 4 `@Test(expected=...)` attribute because it scopes the assertion to the exact line expected to throw.

```java
import static org.junit.jupiter.api.Assertions.assertThrows;

@Test
void withdrawingMoreThanBalanceThrows() {
    Account account = new Account(50);

    IllegalArgumentException ex = assertThrows(
        IllegalArgumentException.class,
        () -> account.withdraw(100)
    );

    assertThat(ex).hasMessageContaining("insufficient funds");
}
```

With AssertJ you can also write `assertThatThrownBy(() -> account.withdraw(100)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("insufficient funds");`.

### Q7. [Theory] What is a mock? How does it differ from the real object?

A **mock** is a test double that stands in for a real collaborator. Instead of executing real logic, it returns programmed responses (**stubbing**) and records the calls made to it so the test can later **verify** interactions. Mocks let you:

- Isolate the unit under test from slow or non-deterministic dependencies (databases, HTTP clients).
- Force hard-to-reproduce conditions (a repository throwing `TimeoutException`).
- Assert that the unit *collaborated* correctly (e.g., it called `emailService.send(...)` exactly once).

The trade-off: mocks encode assumptions about the collaborator's contract. If those assumptions drift from reality, your unit test passes while production breaks — which is why integration and contract tests still matter.

### Q8. [Practical] Write a Mockito unit test that stubs a repository and verifies a save.

```java
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock UserRepository repository;
    @Mock EmailService emailService;
    @InjectMocks UserService service;   // mocks injected via constructor

    @Test
    void registeringUserPersistsAndSendsWelcomeEmail() {
        // Arrange — stub the save to echo back a user with an id
        when(repository.save(any(User.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        // Act
        User created = service.register("ada@example.com");

        // Assert outcome
        assertThat(created.getEmail()).isEqualTo("ada@example.com");

        // Assert interactions
        verify(repository).save(any(User.class));
        verify(emailService).sendWelcome("ada@example.com");
        verifyNoMoreInteractions(emailService);
    }
}
```

`@ExtendWith(MockitoExtension.class)` initializes the `@Mock` fields and injects them into `@InjectMocks` — no manual `MockitoAnnotations.openMocks(this)` needed.

### Q8b. [Theory] What is AssertJ and why prefer it over plain JUnit assertions?

**AssertJ** is a fluent assertion library. Instead of `assertEquals(expected, actual)` (whose argument order is easy to flip), you write `assertThat(actual).isEqualTo(expected)`, which reads naturally and chains rich, type-aware matchers:

```java
assertThat(users)
    .hasSize(3)
    .extracting(User::getEmail)
    .containsExactlyInAnyOrder("a@x.com", "b@x.com", "c@x.com");

assertThat(order.getStatus()).isEqualTo(Status.SHIPPED);
assertThat(response.getBody()).asString().contains("success");
```

Benefits: better failure messages (it prints both the collection and the mismatching element), discoverable API via IDE autocomplete, and **soft assertions** that report all failures at once rather than stopping at the first.

### Q9. [Practical] Demonstrate JUnit 5 parameterized tests.

`@ParameterizedTest` runs the same test body across many inputs, eliminating copy-paste. Source the arguments with `@ValueSource`, `@CsvSource`, `@MethodSource`, or `@EnumSource`.

```java
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ValidationTest {

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t", "\n"})
    void blankStringsAreInvalid(String input) {
        assertThat(Validator.isValid(input)).isFalse();
    }

    @ParameterizedTest(name = "{0} + {1} = {2}")
    @CsvSource({
        "1, 2, 3",
        "0, 0, 0",
        "-1, 1, 0",
        "2147483647, 0, 2147483647"
    })
    void addsCorrectly(int a, int b, int expected) {
        assertThat(Calculator.add(a, b)).isEqualTo(expected);
    }
}
```

For complex/object arguments use `@MethodSource("provider")` returning a `Stream<Arguments>`.

### Q10. [Theory] What is `@DisplayName` and why use it?

`@DisplayName` attaches a human-readable label to a test class or method, shown in IDE and CI reports instead of the method name. It lets test reports double as living documentation:

```java
@DisplayName("Shopping cart")
class CartTest {
    @Test
    @DisplayName("removing the last item empties the cart")
    void removingLastItemEmptiesCart() { ... }
}
```

`@DisplayNameGeneration(ReplaceUnderscores.class)` can auto-convert `removing_last_item_empties_cart` into a readable sentence if you prefer that convention.

### Q11. [Theory] What does `@Disabled` do, and when is it acceptable?

`@Disabled("reason")` skips a test (class or method) — JUnit reports it as skipped rather than passed. It's acceptable as a *short-lived* marker: a test for a feature behind a flag, or one you're quarantining while you fix a known flake (with a linked ticket). It is **not** acceptable as a permanent way to hide failures — a disabled test provides zero protection. Prefer a tracked ticket and a deadline. Conditional skipping (e.g., `@EnabledOnOs(OS.LINUX)`, `@DisabledIfEnvironmentVariable`) is the principled alternative when a test legitimately can't run everywhere.

### Q12. [Practical] What's the difference between a mock and a spy in Mockito?

- A **mock** is a complete stand-in: every method returns a default (null, 0, empty) until you stub it. Real code never runs.
- A **spy** wraps a *real* object: methods call through to the real implementation unless you stub them.

```java
// Mock — nothing real runs
List<String> mock = mock(List.class);
mock.add("x");
assertThat(mock.size()).isZero();        // add() did nothing; size() returns default 0

// Spy — real ArrayList behavior, selectively overridden
List<String> spy = spy(new ArrayList<>());
spy.add("x");
assertThat(spy.size()).isEqualTo(1);     // real add() ran

doReturn(100).when(spy).size();          // override one method
assertThat(spy.size()).isEqualTo(100);
```

**Caution with spies:** use `doReturn().when(spy).method()` rather than `when(spy.method()).thenReturn()`, because the latter actually *invokes* the real method during stubbing, which can have side effects. Heavy reliance on spies often signals a class that should be split.

### Q13. [Theory] What is test coverage, and what's a healthy target?

**Coverage** measures how much of your production code is executed by tests. Common metrics:

- **Line coverage** — fraction of lines run.
- **Branch coverage** — fraction of decision outcomes (each side of every `if`/`switch`) exercised. More meaningful than line coverage.

A pragmatic target is roughly **70–85% branch coverage**, with the understanding that the *last* 15% (logging, trivial getters, defensive `catch` blocks) costs far more than it's worth. Crucially, coverage measures **execution, not verification** — code can be 100% covered with zero assertions. Treat it as a *floor and a heat-map* (find untested branches), never as a quality score to be gamed.

### Q14. [Practical] How do you test code that depends on the current time?

Don't call `LocalDateTime.now()` or `Instant.now()` directly — inject a `java.time.Clock`. In tests, supply a fixed clock so time is deterministic.

```java
class SubscriptionService {
    private final Clock clock;
    SubscriptionService(Clock clock) { this.clock = clock; }

    boolean isExpired(Subscription s) {
        return s.getExpiresAt().isBefore(Instant.now(clock));
    }
}

@Test
void detectsExpiredSubscription() {
    Clock fixed = Clock.fixed(Instant.parse("2026-06-30T00:00:00Z"), ZoneOffset.UTC);
    SubscriptionService service = new SubscriptionService(fixed);

    Subscription s = new Subscription(Instant.parse("2026-06-29T00:00:00Z"));
    assertThat(service.isExpired(s)).isTrue();
}
```

The same principle (inject the source of nondeterminism) applies to randomness (`Random`/`UUID` suppliers) and the file system.

### Q15. [Theory] What is a flaky test, and why is it dangerous?

A **flaky test** passes or fails nondeterministically on the same code. It's dangerous because it **erodes trust in the entire suite**: once developers learn that red builds are sometimes "just flakes," they start re-running CI until green and ignore real regressions. Common causes:

- Timing/race conditions (`Thread.sleep` instead of awaiting a condition).
- Test interdependence / shared mutable state / ordering assumptions.
- Reliance on real time, time zones, locale, or system clock.
- Unstable external dependencies (real network, real third-party API).
- Nondeterministic iteration order (`HashMap`, parallel streams).

The fix is to make the test deterministic; if you can't immediately, **quarantine and ticket it** so it stops gating the pipeline while it's investigated.

### Q16. [Practical] Write a parameterized test using `@MethodSource` for object arguments.

```java
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;

class ShippingTest {

    static Stream<Arguments> shippingScenarios() {
        return Stream.of(
            Arguments.of(new Cart(0,   Country.US), 0.00),   // free over threshold? no, empty
            Arguments.of(new Cart(20,  Country.US), 5.00),
            Arguments.of(new Cart(100, Country.US), 0.00),   // free shipping over 50
            Arguments.of(new Cart(100, Country.CA), 12.00)   // intl flat rate
        );
    }

    @ParameterizedTest
    @MethodSource("shippingScenarios")
    void calculatesShipping(Cart cart, double expectedFee) {
        assertThat(ShippingCalculator.fee(cart)).isEqualTo(expectedFee);
    }
}
```

`@MethodSource` references a `static` method returning `Stream<Arguments>` (or a `Collection`/array). It's the go-to when inputs are objects rather than primitives/strings.

## 🟡 Intermediate (3–7 yrs)

### Q17. [Theory] Distinguish mocking, stubbing, and using fakes. When is each appropriate?

These are types of **test doubles** (Gerard Meszaros' taxonomy):

- **Stub** — returns canned answers to calls; used for *state verification* ("given this input, the unit produces that output"). It provides indirect inputs.
- **Mock** — a stub that *also records calls* so you can verify interactions; used for *behavior verification* ("the unit called `send()` once"). It validates indirect outputs.
- **Fake** — a lightweight working implementation, e.g., an in-memory `Map`-backed repository or H2 standing in for Postgres. It has real behavior but is unsuited to production.

Guidance: prefer **stubs/fakes for state-based tests** (more refactor-resistant), reserve **mocks for verifying genuine side effects** (an email was sent, a message published). Over-mocking — asserting every interaction — couples tests to implementation and makes refactoring painful (the "mockist vs classicist" debate).

### Q18. [Practical] Use Mockito's `ArgumentCaptor` and explain when it beats argument matchers.

An `ArgumentCaptor` grabs the *actual* argument passed to a mock so you can run rich assertions on it after the fact — ideal when the object is built inside the unit under test and you can't easily predict it in advance.

```java
import org.mockito.ArgumentCaptor;

@Test
void publishesEventWithCorrectPayload() {
    orderService.placeOrder(new OrderRequest("SKU-1", 3));

    ArgumentCaptor<OrderPlacedEvent> captor =
        ArgumentCaptor.forClass(OrderPlacedEvent.class);
    verify(eventPublisher).publish(captor.capture());

    OrderPlacedEvent event = captor.getValue();
    assertThat(event.sku()).isEqualTo("SKU-1");
    assertThat(event.quantity()).isEqualTo(3);
    assertThat(event.occurredAt()).isNotNull();
}
```

Use a **matcher** (`eq`, `argThat`) when verifying *that* a call happened with simple expected values; use a **captor** when you need to inspect a *complex* constructed object or assert on multiple fields. Captors verify outputs *after* the call; `argThat` filters *during* verification.

### Q19. [Practical] How do you write a Spring Boot controller test with `@WebMvcTest`?

`@WebMvcTest` is a **test slice**: it loads only the web layer (controllers, filters, `@ControllerAdvice`, JSON converters) — *not* services or repositories — making it fast. You mock the service layer.

```java
@WebMvcTest(UserController.class)
class UserControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean UserService userService;   // Spring Boot 3.4+ (was @MockBean)

    @Test
    void getUserReturns200WithJson() throws Exception {
        when(userService.findById(1L))
            .thenReturn(new User(1L, "ada@example.com"));

        mockMvc.perform(get("/users/1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value("ada@example.com"));
    }

    @Test
    void getMissingUserReturns404() throws Exception {
        when(userService.findById(99L))
            .thenThrow(new UserNotFoundException(99L));

        mockMvc.perform(get("/users/99"))
            .andExpect(status().isNotFound());
    }
}
```

It exercises routing, validation, serialization, and your exception handling — the things a pure unit test of the controller class would miss. (`@MockBean` was deprecated in favor of `@MockitoBean` in Spring Boot 3.4.)

### Q20. [Practical] How do you test the persistence layer with `@DataJpaTest`?

`@DataJpaTest` loads only JPA components (entities, repositories, an `EntityManager`), wraps each test in a transaction that **rolls back at the end** (so tests don't pollute each other), and by default points at an embedded DB. For real fidelity, disable the embedded replacement and point it at a Testcontainers Postgres.

```java
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class UserRepositoryTest {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16");

    @Autowired UserRepository repository;

    @Test
    void findsByEmailIgnoringCase() {
        repository.save(new User("Ada@Example.com"));

        assertThat(repository.findByEmailIgnoreCase("ada@example.com"))
            .isPresent();
    }
}
```

`@ServiceConnection` (Spring Boot 3.1+) auto-wires the container's JDBC URL/credentials into the context, so you don't hand-write `@DynamicPropertySource`.

### Q21. [Theory] What is Testcontainers and what problem does it solve?

**Testcontainers** is a library that spins up **real dependencies in throwaway Docker containers** for the duration of a test — Postgres, Kafka, Redis, Elasticsearch, even arbitrary images — and tears them down automatically. It solves the **fidelity gap**: tests written against H2 or an in-memory fake can pass while the real database rejects the SQL (different dialect, missing features like JSONB, partial indexes, `ON CONFLICT`). With Testcontainers you test against the *exact* engine and version you run in production.

```
Test JVM ──starts──▶ [ Docker: postgres:16 ]  ◀── JDBC ──▶  real SQL
   │                        (ephemeral)
   └── on shutdown ──▶ Ryuk container removes it
```

Costs: requires a Docker daemon (or a compatible runtime) on dev machines and CI, and startup adds seconds — so reuse containers across a class with `@BeforeAll`/static `@Container`, or enable container reuse.

### Q22. [Theory] What is TDD, and what are its three phases?

**Test-Driven Development** inverts the usual order: write a failing test *first*, then the minimal code to pass it, then refactor. The **Red-Green-Refactor** cycle:

```
RED      → write a small failing test (the behavior doesn't exist yet)
GREEN    → write the simplest code that makes it pass (even if ugly)
REFACTOR → clean up the design with the test as a safety net
   ↑__________________________________________________|
```

Benefits: tests exist by construction, the design is shaped by how it's *used* (often yielding cleaner APIs), and you avoid writing untestable code or untested code. The discipline is in keeping steps *small* and not writing production code without a failing test demanding it.

### Q23. [Theory] What is BDD and how do tools like Cucumber relate to it?

**Behavior-Driven Development** extends TDD by framing tests as **examples of behavior described in business language**, using the **Given-When-Then** structure so non-developers (product, QA) can read and even author scenarios.

```gherkin
Feature: Account withdrawal
  Scenario: Withdrawing within balance
    Given an account with balance 100
    When I withdraw 30
    Then the balance should be 70
```

**Cucumber** (or JBehave) parses these `.feature` files and binds each step to Java "glue" code. The value is a **shared, living specification**; the risk is overhead — maintaining the Gherkin/glue layer is heavier than plain JUnit, so reserve BDD for genuinely cross-functional acceptance criteria, not as a default for unit tests.

### Q24. [Practical] How do you verify that a mock was called a specific number of times, in order, or never?

Mockito's `verify` takes a **verification mode**:

```java
verify(repository, times(2)).save(any());      // exactly twice
verify(repository, never()).delete(any());     // never called
verify(emailService, atLeastOnce()).send(any());
verify(audit, atMost(3)).log(any());

// Order across one or more mocks:
InOrder inOrder = inOrder(repository, eventPublisher);
inOrder.verify(repository).save(any());
inOrder.verify(eventPublisher).publish(any());  // must follow save

// Nothing else happened on this mock:
verifyNoMoreInteractions(repository);
```

`InOrder` is essential when sequence matters (persist *then* publish, to avoid emitting an event for a row that wasn't saved).

### Q25. [Theory] What is the test data builder pattern, and why use it over constructors?

A **test data builder** is a small fluent helper that constructs domain objects for tests with sensible defaults, overriding only the fields relevant to each test:

```java
public class UserBuilder {
    private String email = "default@example.com";
    private CustomerTier tier = CustomerTier.STANDARD;
    private boolean active = true;

    public static UserBuilder aUser() { return new UserBuilder(); }
    public UserBuilder withEmail(String e) { this.email = e; return this; }
    public UserBuilder loyal() { this.tier = CustomerTier.LOYAL; return this; }
    public UserBuilder inactive() { this.active = false; return this; }
    public User build() { return new User(email, tier, active); }
}

// In a test — only the relevant field is stated:
User user = aUser().loyal().build();
```

Benefits over raw constructors: tests state only what *matters* (reducing noise), they're resilient to added constructor params (defaults absorb the change), and intent is explicit (`aUser().inactive()` reads like a sentence). This is the hand-rolled cousin of object-mother libraries and tools like Instancio.

### Q26. [Practical] How do you stub a method to throw, and to return different values on successive calls?

```java
// Throw on call
when(repository.findById(99L))
    .thenThrow(new EntityNotFoundException());

// For void methods, use doThrow:
doThrow(new RuntimeException("boom"))
    .when(auditService).record(any());

// Different value each successive call (e.g., retry succeeding on 3rd try):
when(client.fetch())
    .thenThrow(new TimeoutException())   // 1st call
    .thenThrow(new TimeoutException())   // 2nd call
    .thenReturn("ok");                   // 3rd call onward

// Dynamic answer based on the argument:
when(repository.save(any(User.class)))
    .thenAnswer(inv -> {
        User u = inv.getArgument(0);
        return new User(42L, u.getEmail());   // simulate assigned id
    });
```

Chaining `.thenThrow().thenReturn()` is the canonical way to test retry/backoff logic deterministically.

### Q26b. [Practical] How do you write a parameterized test in JUnit 5 with `@EnumSource`, and how do nested tests organize a suite?

`@EnumSource` feeds every constant of an enum into a test; `@Nested` groups related tests into an inner-class hierarchy that mirrors the scenarios under test.

```java
@ParameterizedTest
@EnumSource(value = OrderStatus.class, names = {"SHIPPED", "DELIVERED"})
void finalStatusesCannotBeCancelled(OrderStatus status) {
    Order order = anOrder().withStatus(status).build();
    assertThatThrownBy(order::cancel)
        .isInstanceOf(IllegalStateException.class);
}

@Nested
@DisplayName("when the cart is empty")
class EmptyCart {
    Cart cart = new Cart();

    @Test void totalIsZero()        { assertThat(cart.total()).isZero(); }
    @Test void checkoutIsRejected() { assertThatThrownBy(cart::checkout)... }
}
```

`@Nested` classes share `@BeforeEach` setup from the outer class and let report output read as nested sentences ("Cart > when the cart is empty > total is zero").

### Q27. [Theory] What is a JUnit 5 extension? Give an example of when you'd write one.

The **Extension model** is JUnit 5's single, composable replacement for JUnit 4's runners and rules. An extension hooks into lifecycle callbacks (`BeforeEachCallback`, `AfterAllCallback`, `ParameterResolver`, `TestExecutionExceptionHandler`, etc.) to inject behavior across many tests. You register it with `@ExtendWith(MyExtension.class)` or — for built-in ones — annotations like `@ExtendWith(MockitoExtension.class)`.

You'd write one to, say, start a shared resource, seed/reset a database, capture and assert on logs, retry flaky integration tests, or resolve custom test parameters:

```java
public class TimingExtension implements BeforeEachCallback, AfterEachCallback {
    private long start;
    public void beforeEach(ExtensionContext ctx) { start = System.nanoTime(); }
    public void afterEach(ExtensionContext ctx) {
        long ms = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("%s took %d ms%n", ctx.getDisplayName(), ms);
    }
}
```

Unlike runners (you could only have one), you can stack many extensions on one test.

### Q28. [Practical] How do you run JUnit 5 tests in parallel, and what must you watch out for?

Enable it via `junit-platform.properties`:

```properties
junit.jupiter.execution.parallel.enabled=true
junit.jupiter.execution.parallel.mode.default=concurrent
junit.jupiter.execution.parallel.config.strategy=dynamic
```

You can also annotate with `@Execution(CONCURRENT)` / `@Execution(SAME_THREAD)` per class. **Watch out for:**

- **Shared mutable state** — static fields, singletons, files, a single DB row. Use `@ResourceLock("db")` to serialize tests that touch the same resource.
- **Order dependence** — parallelism surfaces hidden coupling between tests.
- **Thread-unsafe libraries** or static mocking (`Mockito.mockStatic`) which is thread-local but easy to misuse.

Parallelism is the fastest win for a slow suite *if* your tests are genuinely isolated — which is itself a good test of test quality.

### Q29. [Theory] What is contract testing, and what problem does it solve in microservices?

In a microservice system, service A (consumer) calls service B (provider). Integration-testing A against a *real* B is slow and couples deploys; mocking B in A's tests risks the mock drifting from B's actual behavior. **Contract testing** captures the **expectations the consumer has of the provider** as a machine-readable *contract*, then verifies **both sides against that same contract independently**:

```
Consumer A test  ──defines──▶  CONTRACT  ◀──verifies──  Provider B test
 (uses a stub generated          (shared)          (replays requests against
  from the contract)                                 the real provider)
```

If B changes a field name, B's contract verification fails *before* deploy — catching the break without an end-to-end environment. Tools: **Pact** (consumer-driven, broker-mediated) and **Spring Cloud Contract** (provider publishes contracts, generates consumer stubs).

### Q30. [Theory] Compare Pact and Spring Cloud Contract.

Both implement consumer-provider contract testing but differ in workflow:

- **Pact** is **consumer-driven**: the consumer writes the expectation in its tests, which produces a pact file; a **Pact Broker** shares it; the provider runs `pact-verifier` against it. Polyglot-friendly (JS, Java, Go, etc.), strong **can-i-deploy** versioning/tagging. Best when many heterogeneous consumers exist.
- **Spring Cloud Contract** is typically **provider-driven**: the provider authors contracts (Groovy/YAML), which generate (a) provider verification tests and (b) **WireMock stubs** published as a `stubs` jar that consumers use. Tightly integrated with the Spring/JVM ecosystem and Maven/Gradle.

Choose Pact for polyglot, consumer-led ecosystems; Spring Cloud Contract for JVM-heavy shops already in the Spring world.

### Q31. [Practical] How do you test asynchronous code without `Thread.sleep`?

`Thread.sleep` makes tests either slow (long sleep) or flaky (too-short sleep). Instead, **poll for the expected condition** with a timeout, using **Awaitility**:

```java
import static org.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.SECONDS;

@Test
void messageIsProcessedAsynchronously() {
    producer.send(new Order("SKU-1"));

    await()
        .atMost(5, SECONDS)
        .pollInterval(50, MILLISECONDS)
        .untilAsserted(() ->
            assertThat(repository.findBySku("SKU-1")).isPresent());
}
```

Awaitility re-evaluates the assertion until it passes or the timeout elapses, so the test finishes as soon as the work completes (fast on a fast machine) yet tolerates slow CI. For `CompletableFuture`, prefer `future.get(timeout)`; for reactive code use `StepVerifier`.

### Q32. [Theory] What is `@SpringBootTest`, and how does it differ from a test slice?

`@SpringBootTest` boots the **entire application context** — all beans, configuration, and auto-configuration — making it the heaviest, most realistic Spring test. With `webEnvironment = RANDOM_PORT` it starts a real embedded server you can hit via `TestRestTemplate`/`WebTestClient`.

```
@SpringBootTest         → full context, slowest, highest fidelity   (integration/e2e)
@WebMvcTest             → web layer only, services mocked            (slice)
@DataJpaTest            → JPA layer only, rolled back                (slice)
@JsonTest / @RestClientTest → serialization / client slices
```

Use **slices** for the bulk of integration coverage (fast, focused); reserve **`@SpringBootTest`** for a handful of full-wiring or true end-to-end happy-path tests. Overusing `@SpringBootTest` is a classic cause of multi-minute suites because each distinct context configuration is built (and cached) separately.

### Q33. [Practical] How do you write an end-to-end integration test of a REST endpoint with `@SpringBootTest`?

```java
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class OrderApiIntegrationTest {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16");

    @Autowired TestRestTemplate rest;

    @Test
    void createAndFetchOrderRoundTrips() {
        OrderRequest req = new OrderRequest("SKU-1", 2);

        ResponseEntity<OrderResponse> created =
            rest.postForEntity("/orders", req, OrderResponse.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        Long id = created.getBody().id();
        ResponseEntity<OrderResponse> fetched =
            rest.getForEntity("/orders/" + id, OrderResponse.class);

        assertThat(fetched.getBody().sku()).isEqualTo("SKU-1");
        assertThat(fetched.getBody().quantity()).isEqualTo(2);
    }
}
```

This exercises the full stack — routing, validation, service logic, JPA, real SQL against real Postgres — in one test, giving high confidence at the cost of speed.

## 🟠 Advanced (8–12 yrs)

### Q34. [Theory] Coverage is at 90% but production still has bugs. What's wrong, and what is mutation testing?

High coverage only proves lines *executed*, not that they were *meaningfully asserted*. A suite can call every line and assert nothing of value. **Mutation testing** measures the *fault-detection power* of your tests by deliberately introducing small bugs ("mutants") into the production code — flipping `>` to `>=`, replacing `+` with `-`, returning `null`, negating a condition — then running the suite:

- If a test **fails**, the mutant is **killed** (good — your tests caught the bug).
- If all tests still **pass**, the mutant **survived** (bad — that logic isn't truly tested).

The **mutation score** (killed / total) is a far stronger quality signal than coverage. **PIT (pitest)** is the standard JVM tool. Survived mutants pinpoint exactly which behaviors your assertions miss — often revealing tests that call a method but never check its result.

```
mutate:  if (a > b)  ──▶  if (a >= b)
run tests ──▶ all green?  → SURVIVED  (boundary case untested!)
          ──▶ a test red? → KILLED    (good)
```

### Q35. [Practical] How do you configure JaCoCo coverage gates, and why aren't they sufficient?

JaCoCo instruments bytecode to report line/branch coverage and can **fail the build** below a threshold:

```xml
<plugin>
  <groupId>org.jacoco</groupId>
  <artifactId>jacoco-maven-plugin</artifactId>
  <executions>
    <execution>
      <id>check</id>
      <goals><goal>check</goal></goals>
      <configuration>
        <rules>
          <rule>
            <element>BUNDLE</element>
            <limits>
              <limit>
                <counter>BRANCH</counter>
                <value>COVEREDRATIO</value>
                <minimum>0.80</minimum>
              </limit>
            </limits>
          </rule>
        </rules>
      </configuration>
    </execution>
  </executions>
</plugin>
```

They aren't sufficient because coverage measures execution, not verification (see Q34) and is trivially gamed (write tests with no assertions, or assert on trivia). A high JaCoCo number with a low PIT mutation score is a red flag. Use JaCoCo as a **floor against regressions** and to *find* untested code, complemented by mutation testing for *quality*. Also exclude generated code (DTOs, MapStruct mappers) from the ratio so it reflects real logic.

### Q36. [Theory] You inherit a suite where one flaky integration test fails ~5% of runs. Walk through how you diagnose and fix it.

1. **Quarantine first** — tag it (`@Tag("flaky")`) and exclude it from the gating job so it stops blocking deploys, with a ticket. Don't `@Disabled` and forget.
2. **Reproduce deterministically** — run it in a loop (`mvn test -Dtest=... -Dsurefire.rerunFailingTestsCount=0` in a shell loop, or an IDE "repeat until failure"). Run it both **in isolation** and **as part of the full suite** — if it only fails in the suite, the cause is *test pollution* (shared state/order), not the test itself.
3. **Hypothesize by category** — race condition (missing `await`), time/timezone, random data, DB state leaking between tests, external dependency, port conflicts, container readiness.
4. **Add observability** — capture timestamps, thread names, the seed (if randomized), and the actual-vs-expected on failure.
5. **Fix the root cause** — replace `sleep` with Awaitility; reset/recreate state per test; fix the source of nondeterminism (inject `Clock`/seed); use a fresh container or unique data per test.
6. **Prove it** — run hundreds of iterations green, then un-quarantine.

The cultural point: a flaky test is a **defect in the test**, not noise to be re-run away.

### Q37. [Practical] Write a custom JUnit 5 extension that retries a flaky test a few times.

```java
public class RetryExtension implements TestTemplateInvocationContextProvider {
    @Override
    public boolean supportsTestTemplate(ExtensionContext context) {
        return context.getTestMethod()
            .map(m -> m.isAnnotationPresent(Retry.class)).orElse(false);
    }
    @Override
    public Stream<TestTemplateInvocationContext> provideTestTemplateInvocationContexts(
            ExtensionContext context) {
        int max = context.getTestMethod().get().getAnnotation(Retry.class).value();
        return IntStream.rangeClosed(1, max).mapToObj(i -> new RetryContext(i, max));
    }
}
```

Used as `@TestTemplate @Retry(3)`. **Important caveat:** retrying is a *band-aid that hides flakiness*, not a cure. It's acceptable for genuinely external-dependency tests (a flaky third-party sandbox you don't control), but using it to paper over race conditions in your own code lets real bugs slip through. Prefer fixing the root cause (Q36); reach for retry only when you've proven the nondeterminism is outside your control.

### Q38. [Theory] How do you keep `@SpringBootTest` suites fast as a system grows?

The dominant cost is **building application contexts**. Strategies:

- **Reuse contexts via caching** — Spring caches contexts keyed by their *configuration*. Keep configurations identical across tests so they share one cached context. Each unique combination of `@MockitoBean`, `@TestPropertySource`, `@ActiveProfiles`, or `@DirtiesContext` forks a *new* context.
- **Avoid `@DirtiesContext`** — it evicts the cache and forces a rebuild. Reset state explicitly instead (truncate tables, reset mocks).
- **Prefer slices** — `@WebMvcTest`/`@DataJpaTest` build minimal contexts.
- **Shared base test classes** so configuration is uniform and the cache hits.
- **Reuse Testcontainers** across the suite (singleton container pattern or container reuse) rather than per-class start/stop.
- **Parallelize** at the fork level (Surefire `forkCount`) once tests are isolated.
- Push logic *down* into pure units so fewer tests need the full context at all.

### Q38b. [Behavioral] A teammate insists on 100% code coverage as a merge gate. How do you respond?

I'd agree with the underlying intent (high confidence) but push back on the metric. I'd explain concretely that **100% coverage doesn't mean 100% correctness** — show a unit test with no assertions that hits every line, or a surviving PIT mutant in fully-covered code. I'd point out the *costs*: chasing the last few percent forces tests for trivial getters, generated DTOs, and unreachable defensive branches, which adds maintenance burden and tempts people to write assertion-free tests just to clear the gate — actively *lowering* quality. My proposal: set a pragmatic branch-coverage floor (say 80%) on *changed* code to prevent regressions, exclude generated code, and invest the saved effort in **mutation testing** on the critical modules where bugs are expensive. I'd frame it as data-driven rather than a turf fight: pick one service, compare its coverage vs. mutation score, and let the gap make the argument. The goal is shared — catch bugs before users do — so I'd keep it collaborative.

### Q39. [Theory] When should you NOT mock, and what are the dangers of over-mocking?

Don't mock:

- **Value objects / data classes** — just construct them; mocking them is pure ceremony.
- **Types you don't own** (third-party clients, the JDK) — mock a thin *adapter* you control instead, because mocking external types bakes in *your assumptions* about their behavior, which can be wrong. Use a fake/Testcontainer for the real thing in integration tests.
- **The class under test** itself (partial mocks/spies of it) — usually a design smell.

Dangers of **over-mocking**: tests become a mirror of the implementation (every method call verified), so any refactor — even one that preserves behavior — breaks tests, making them a *change-prevention* tool rather than a *safety net*. Heavily-mocked tests can be **all green while the system is broken**, because nothing exercised the real interactions. The fix is to test at a slightly higher level (real collaborators where cheap, fakes for I/O) and verify *outcomes*, not call sequences.

### Q40. [Practical] How do you test logging, metrics, or other side effects?

For **logging**, attach an in-memory appender (Logback's `ListAppender`) and assert on captured events:

```java
ListAppender<ILoggingEvent> appender = new ListAppender<>();
appender.start();
((Logger) LoggerFactory.getLogger(PaymentService.class)).addAppender(appender);

paymentService.charge(declinedCard);

assertThat(appender.list)
    .anyMatch(e -> e.getLevel() == Level.WARN
                && e.getFormattedMessage().contains("declined"));
```

For **Micrometer metrics**, use a `SimpleMeterRegistry` and assert counters/timers moved:

```java
SimpleMeterRegistry registry = new SimpleMeterRegistry();
OrderService service = new OrderService(registry);
service.place(order);
assertThat(registry.counter("orders.placed").count()).isEqualTo(1.0);
```

Caveat: only test logs/metrics that are part of the **contract** (e.g., an audit-required log line, an SLO metric). Asserting on every debug line couples tests to incidental output.

### Q41. [Theory] What's the difference between performance, load, stress, and soak testing, and which tools fit?

- **Performance/baseline** — measure latency/throughput under expected load; establish SLO numbers (p50/p95/p99).
- **Load** — sustain *expected peak* concurrency to confirm SLOs hold.
- **Stress** — push *beyond* capacity to find the breaking point and observe failure mode (does it degrade gracefully or fall over?).
- **Soak/endurance** — moderate load for hours/days to surface leaks, connection-pool exhaustion, and slow degradation.
- **Spike** — sudden surge to test elasticity/autoscaling.

Tools (2026): **k6** (JS-scripted, CLI/CI-native, great for modern pipelines), **Gatling** (Scala/Java DSL, high concurrency via async, excellent reports), **JMeter** (GUI + XML, mature, broad protocol support, heavier). For JVM microservices, k6 or Gatling integrate cleanly into CI; JMeter remains common in enterprise/QA teams.

### Q42. [Practical] Sketch a load test in k6 and explain the key assertions.

```javascript
import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  stages: [
    { duration: '30s', target: 50 },   // ramp up to 50 VUs
    { duration: '2m',  target: 50 },   // sustain (load test)
    { duration: '30s', target: 0 },    // ramp down
  ],
  thresholds: {
    http_req_duration: ['p(95)<300'],  // 95% of requests under 300ms
    http_req_failed:   ['rate<0.01'],  // <1% errors
  },
};

export default function () {
  const res = http.get('https://api.example.com/orders/1');
  check(res, { 'status is 200': (r) => r.status === 200 });
  sleep(1);
}
```

The **thresholds** are the pass/fail gate: if p95 latency exceeds 300ms or the error rate exceeds 1%, k6 exits non-zero and **fails the CI job** — turning a performance budget into an automated regression guard, not a one-off manual exercise. `check` records functional correctness without aborting the run.

### Q43. [Theory] How do you decide what to mock vs. use a real Testcontainer in integration tests?

A decision framework:

```
Is it YOUR code / in-process logic?        → real object (don't mock)
Is it a value object?                       → real object
Is it slow/nondeterministic infra you own
  the protocol for (DB, Kafka, Redis)?      → real via Testcontainer (high fidelity)
Is it a 3rd-party HTTP API you don't control?
  → WireMock/mock server for the contract,
    + a small set of real calls in a separate,
      non-gating "live" suite if needed
Is it a hard-to-trigger failure mode
  (timeout, partial outage)?                → mock/fake to force it
```

Principle: use the **real thing for the boundaries you own** (your DB schema, your message format) because that's where integration bugs hide; **simulate** what you don't control or can't make deterministic. Mixing both — Testcontainers for the DB, WireMock for an external payment gateway — is the norm for a realistic service test.

## 🔴 Expert (15+ yrs)

### Q44. [Behavioral] You join a team with a 45-minute, frequently-red CI test suite that everyone ignores. How do you turn it around?

I treat it as both a technical and cultural problem.

**Stabilize first (regain trust):** A suite people ignore is worse than no suite. I'd measure flakiness empirically (track per-test failure rates over recent runs), **quarantine the top flaky tests** into a non-gating job so the gate goes reliably green, and make a green build *mean something* again. Each quarantined test gets a ticket and an owner.

**Then make it fast:** Profile where the 45 minutes go — usually a pile of `@SpringBootTest` contexts and per-class container starts. Apply Q38 tactics (context caching, fewer dirtied contexts, slices over full boots, singleton containers, parallelization). Often the pyramid is inverted; I'd push logic into fast unit tests and trim redundant high-level tests.

**Then prevent regression:** Add a *changed-code* coverage floor and a flakiness alert so new flakes are caught early; pick critical modules for mutation testing to ensure the green is *meaningful*, not just present.

**Culturally:** Make the cost visible (CI minutes, deploy delays), celebrate flake-fixes, and establish the norm that **breaking the build blocks the team** and is fixed-or-reverted fast. I'd do the first few high-profile fixes myself to model the standard, then spread ownership. The end state is a sub-10-minute suite that's green by default and trusted enough that red genuinely stops the line.

### Q45. [Theory] Design a testing strategy for a microservices platform. How do the layers fit together?

A layered strategy that maximizes confidence per minute of CI:

```
            ┌──────────────────────────────────────────┐
  per       │ Unit tests        (each service)          │ pure logic, mocks
  service   │ Slice tests       (@WebMvcTest, @DataJpaTest) controllers, SQL
            │ Integration tests (Testcontainers)        │ real DB/Kafka per service
            ├──────────────────────────────────────────┤
  cross     │ Contract tests    (Pact / SCC)            │ replace cross-service e2e
  service   │ Component tests   (service + its deps,     │ service boundary, externals
            │                     externals stubbed)     │   via WireMock
            ├──────────────────────────────────────────┤
  system    │ A FEW e2e smoke tests (critical journeys) │ in staging, post-deploy
            │ Synthetic monitoring / canaries in prod    │ continuous, real traffic
            └──────────────────────────────────────────┘
```

Key decisions:
- **Contract tests replace most cross-service integration tests** — they catch interface breaks without a full environment and without coupling deploy pipelines.
- **e2e is deliberately thin** — a handful of critical-path smoke tests, because full-system e2e is slow, flaky, and expensive to maintain at scale.
- **Shift-right** with canary releases, **feature flags**, and **synthetic/observability-based testing in production** — at scale you cannot pre-stage every real-world condition, so you test safely *in* production with fast rollback.
- **Consumer-driven contracts + can-i-deploy** gate independent deployability, which is the whole point of microservices.

### Q46. [Theory] What testing challenges are unique to event-driven / asynchronous systems, and how do you address them?

Async/event-driven systems break the request-response assumptions most test tooling makes:

- **No synchronous result to assert** — you must *poll for eventual state* (Awaitility) rather than read a return value, with sensible timeouts that tolerate CI jitter without being so long they mask hangs.
- **Ordering and idempotency** — at-least-once delivery means consumers must handle duplicates and out-of-order messages; tests must *deliberately* replay/duplicate/reorder events to verify idempotency, not just happy-path single delivery.
- **Eventual consistency windows** — assertions on a read model must wait for projection to catch up; tests that assert immediately are inherently flaky.
- **Schema evolution** — producers and consumers deploy independently, so test *backward/forward compatibility* of event schemas (Avro/Protobuf compatibility checks, schema-registry rules) and use **contract tests on the message format**, not just on HTTP.
- **Real broker fidelity** — H2/in-memory fakes won't reproduce partitioning, consumer-group rebalancing, or redelivery; use a **Testcontainers Kafka** for integration.
- **Poison messages / DLQs** — explicitly test that a malformed or repeatedly-failing message lands in the dead-letter queue and doesn't block the partition.
- **Time and retries** — inject the clock and control retry/backoff so tests are deterministic.

The overarching shift: from *"call returns X"* to *"the system eventually reaches state X, exactly once, even under duplication and reordering."*

### Q47. [Practical] How do you test idempotency and exactly-once-effect processing in a consumer?

You can't get exactly-once *delivery* from most brokers, so consumers must be **idempotent** (processing the same event twice yields the same effect once). Test it by deliberately delivering duplicates:

```java
@Test
void processingSameEventTwiceCreatesOneRecordAndOneSideEffect() {
    PaymentEvent event = aPaymentEvent().withEventId("evt-123").build();

    consumer.handle(event);
    consumer.handle(event);   // duplicate delivery

    // State effect happened exactly once
    assertThat(paymentRepository.findByEventId("evt-123")).hasSize(1);

    // Side effect (e.g., outbound notification) fired exactly once
    verify(notificationGateway, times(1)).notify(any());
}
```

The production mechanism under test is usually a **dedup/inbox table keyed by event id** (or an idempotency key) checked inside the same transaction as the state change. The test asserts the *second* delivery is a no-op for both the persisted state and any downstream side effect — the property that actually matters in production, where redelivery is routine.

### Q48. [Theory] How do you make integration tests deterministic and reproducible across CI agents and developer machines?

Determinism comes from eliminating every implicit dependency on the environment:

- **Pin dependency versions, including container images** — `postgres:16.4`, not `postgres:latest`, so the engine behaves identically everywhere.
- **Isolate data per test** — each test creates and cleans its own data (unique keys, or schema-per-test / truncate-between), never relying on leftover rows or test execution order.
- **Control all nondeterminism sources** — inject `Clock` (fixed instant), seed any RNG and log the seed, set a fixed time zone (`-Duser.timezone=UTC`) and locale, avoid relying on `HashMap`/`HashSet` iteration order.
- **Wait on conditions, not durations** — Awaitility/readiness probes instead of `sleep`; use container `waitingFor(Wait.forHealthcheck())` so tests start only when the dependency is truly ready.
- **No shared external state** — no hitting a shared staging DB or a real third-party API in gating tests; use ephemeral containers and stubbed externals.
- **Reproducible builds** — committed lockfiles, no network access during the build, hermetic toolchain.
- **Surface flakiness fast** — run tests in random order in CI to catch hidden inter-test coupling early, and track per-test failure rates so a newly-flaky test is caught before it spreads.

The litmus test: a fresh checkout on a clean agent with only Docker and the JDK produces identical results every run.

## 🧩 Extended Questions — Set 1: Deeper theory & internals

### 🟢 — extended

#### Q49. [Theory] Why does JUnit 5 create a new test instance per method by default, and what changes under `@TestInstance(Lifecycle.PER_CLASS)`?

By default (`Lifecycle.PER_METHOD`) JUnit 5 instantiates the test class **once per test method**. This guarantees **isolation**: instance fields can't leak state from one test into another, so tests stay independent and order-insensitive (the "I" in F.I.R.S.T.). The cost is that any expensive setup placed in fields runs for every test, and `@BeforeAll`/`@AfterAll` must be `static` (there's no single instance to hang them on).

`@TestInstance(Lifecycle.PER_CLASS)` flips this: **one instance for the whole class**. Consequences:

- `@BeforeAll`/`@AfterAll` may be **non-static** instance methods (handy when shared setup needs injected fields).
- Instance state now **persists across methods** — convenient for an expensive immutable fixture, but a trap if any test mutates it, since it reintroduces inter-test coupling.
- Required for some patterns like a non-static `@MethodSource` factory.

Rule of thumb: keep `PER_METHOD` for purity; reach for `PER_CLASS` only when the shared resource is genuinely immutable or you reset it explicitly in `@BeforeEach`.

#### Q50. [Theory] How does JUnit 5's architecture (Platform, Jupiter, Vintage) actually fit together?

JUnit 5 is not a monolith — it's three sub-projects, which is *why* it's extensible and why old tests still run:

- **JUnit Platform** — the *foundation*. It defines the `TestEngine` SPI and is what build tools and IDEs launch (`junit-platform-launcher`, `junit-platform-console`). It knows nothing about a specific test style; it just discovers and runs whatever engines are registered.
- **JUnit Jupiter** — the new *programming + extension model* (`@Test`, `@ParameterizedTest`, the `Extension` API) **plus** its own `TestEngine` (`junit-jupiter-engine`) that runs Jupiter tests on the Platform.
- **JUnit Vintage** — a `TestEngine` that runs legacy **JUnit 3 and 4** tests on the same Platform, easing migration.

```
            ┌──────────────── JUnit Platform (launcher + TestEngine SPI) ───────────────┐
            │   jupiter-engine            vintage-engine        (third-party engines)     │
            │   runs Jupiter tests        runs JUnit 4 tests     e.g. Spock, Cucumber      │
            └────────────────────────────────────────────────────────────────────────────┘
```

The payoff: any framework (Spock, Cucumber, your own) can implement `TestEngine` and run side-by-side under one launcher, and a codebase mid-migration can run JUnit 4 and 5 tests in the same build via Vintage.

#### Q51. [Theory] What is "test smell," and name several with their fix.

A **test smell** is a recurring pattern in test code that signals a maintainability or reliability problem — the test equivalent of a code smell (catalogued largely by Meszaros in *xUnit Test Patterns*). Common ones:

- **Eager test / Assertion roulette** — many unrelated assertions in one method with no messages, so a failure doesn't say *which* one broke. Fix: split into focused tests or use AssertJ's descriptive/soft assertions.
- **Mystery guest** — the test depends on external data (a file, a shared DB row) not visible in the test. Fix: inline the fixture or use a builder.
- **Fragile test / Overspecification** — verifying every interaction or exact call order, so behavior-preserving refactors break it. Fix: assert outcomes, not implementation.
- **Slow poke** — a unit test that sleeps or hits I/O. Fix: inject the dependency, await conditions.
- **Conditional test logic** — `if`/`for` inside the test, so it may silently skip its own assertions. Fix: parameterize instead.
- **Test code duplication** — copy-pasted setup. Fix: builders, factory methods, `@BeforeEach`.

The meta-point: test code is production code and rots the same way; smells predict future flakiness and maintenance pain.

#### Q51b. [Practical] What's the difference between `assertAll` (soft assertions) and a sequence of plain assertions, and when does it matter?

A plain sequence of assertions is **fail-fast**: the first failure throws and the rest never run, so you fix one problem, rerun, discover the next — slow when several fields are wrong. **Soft assertions** evaluate *all* of them and report every failure at once.

```java
// JUnit 5 built-in: assertAll
assertAll("user",
    () -> assertEquals("ada@example.com", user.getEmail()),
    () -> assertEquals(CustomerTier.LOYAL, user.getTier()),
    () -> assertTrue(user.isActive())
);

// AssertJ SoftAssertions
SoftAssertions softly = new SoftAssertions();
softly.assertThat(user.getEmail()).isEqualTo("ada@example.com");
softly.assertThat(user.getTier()).isEqualTo(CustomerTier.LOYAL);
softly.assertThat(user.isActive()).isTrue();
softly.assertAll();   // throws once, listing every failure
```

Use soft assertions when verifying **multiple independent properties of one result** (a DTO's fields), so one run surfaces all mismatches. Keep fail-fast when a later assertion is **meaningless if an earlier one failed** (asserting on `list.get(0)` after asserting the list is non-empty) — there, soft assertions would just add a confusing NPE on top of the real failure.

#### Q52. [Theory] What does "test behavior, not implementation" mean concretely, and why does it survive refactoring?

It means assertions should target the **observable outcome** of a unit — its return value, the state it leaves behind, or the genuine side effects that are part of its contract — not *how* it computes that outcome (which private methods it calls, the exact sequence of internal collaborator calls, intermediate variables).

Why it matters: **refactoring** is by definition changing the *how* while preserving the *what*. A test bound to implementation (e.g., `verify(helper).step1(); verify(helper).step2();` for an internal algorithm) fails the moment you reorganize the code even though behavior is identical — so the suite becomes a *change-prevention* tool that punishes improvement. A behavior-focused test (`assertThat(service.total(order)).isEqualTo(90.00)`) stays green through any refactor that keeps the answer right, and goes red only when behavior actually breaks — which is exactly when you want a failure. The practical heuristic: prefer **state verification** (assert on results) over **interaction verification** (verify calls), reserving the latter for side effects that *are* the contract (an email sent, an event published).

#### Q53. [Practical] What's the difference between `@Mock`, `@MockitoBean`, and a manually-constructed mock — and when does each apply?

All three produce a Mockito mock, but they differ in *who injects it where*:

- **`@Mock` (+ `@ExtendWith(MockitoExtension.class)`)** — a plain Mockito mock field, injected into the unit under test via `@InjectMocks`. **No Spring context.** This is the fast, pure-unit-test path.
- **`@MockitoBean`** (Spring Boot 3.4+, replaced `@MockBean`) — used inside a Spring test (`@SpringBootTest`/`@WebMvcTest`). It **replaces the real bean in the application context** with a mock, so any other bean wired to that type gets the mock. Slower, because it requires a context — and each distinct set of `@MockitoBean`s forks a *new* cached context.
- **Manual `mock(Foo.class)`** — no annotations/extension at all; you new-up the mock and pass it to a constructor yourself. Maximum control, useful in tiny tests or non-JUnit code.

```java
// Pure unit — no Spring
@ExtendWith(MockitoExtension.class)
class ServiceTest {
    @Mock Repo repo;
    @InjectMocks Service service;
}

// Spring slice — bean swapped in the context
@WebMvcTest(UserController.class)
class ControllerTest {
    @MockitoBean UserService userService;   // replaces the real bean
}
```

Default to `@Mock` for unit tests (no context cost); use `@MockitoBean` only when you genuinely need a bean swapped inside a running Spring context.

#### Q54. [Theory] Why are unit tests on private methods discouraged, and what should you do instead?

A private method is an **implementation detail**, not part of the class's contract. Testing it directly (via reflection, or by loosening visibility to package-private just for tests) couples the suite to *how* the class works, so refactoring the internals — the very thing privacy is meant to enable freely — breaks tests even when public behavior is unchanged. It also creates a false sense of safety: the private method can be perfectly tested while the public path that uses it is broken.

What to do instead:

- **Test through the public API** — exercise the private logic via the public methods that call it. If a branch in a private method is genuinely unreachable from any public entry point, that's a hint it's **dead code**.
- If a private method is so complex it *demands* its own tests, that's a **design signal**: extract it into its own collaborator class with a public method (and its own focused tests). This is "listen to the tests" — difficulty testing reveals a missing abstraction.

The exception is pragmatic: occasionally a pure, complex algorithm is package-private to allow direct testing — acceptable, but treat the friction as feedback that it might want to be its own unit.

### 🟡 — extended

#### Q55. [Theory] How does Mockito create mocks under the hood, and what are the implications (final classes, static methods, the inline mock maker)?

Historically Mockito used a **subclass/proxy** approach: it generated a dynamic subclass of the mocked type (via Byte Buddy) overriding every method to route through its stubbing engine. The implications of that model:

- It could not mock **`final` classes/methods** or **`static` methods** — there's nothing to override.
- It works by inheritance, so the mock *is-a* subtype, fine for interfaces and non-final classes.

Modern Mockito (2.x+, default since 5.x) ships the **inline mock maker** (`mockito-core` includes it; older setups added `mockito-inline`), which uses **bytecode instrumentation via a Java agent** to redefine classes in place. This enables:

- Mocking **`final` classes and methods**.
- **`mockStatic(...)`** for static methods and **`mockConstruction(...)`** for `new` calls — each scoped to a try-with-resources block and **thread-local**, so they must be closed and don't leak across threads.

```java
try (MockedStatic<Files> files = mockStatic(Files.class)) {
    files.when(() -> Files.exists(any())).thenReturn(true);
    assertThat(MyUtil.check(path)).isTrue();
}   // static stub removed here
```

Implications to remember: static/construction mocking is **thread-local** (don't share across parallel tests), it's a sign you might be testing around an awkward static dependency (prefer injecting a seam), and Mockito **still cannot mock truly native or some JDK-internal methods**.

#### Q56. [Practical] Explain Mockito's `RETURNS_DEEP_STUBS`, `lenient()` stubbing, and strict stubs — what problems do they solve or cause?

These three concern Mockito's **strictness and convenience** behavior:

- **Strict stubs** (default under `MockitoExtension`, `Strictness.STRICT_STUBS`) — Mockito **fails the test** if you declare a `when(...)` that's never used (`UnnecessaryStubbingException`) or call a stubbed method with unexpected args (`PotentialStubbingProblem`). This catches dead/wrong stubs and copy-paste rot. It's the recommended default.
- **`lenient()`** — opts a single stubbing *out* of strict checking: `lenient().when(repo.find(any())).thenReturn(...)`. Use it for a stub shared across many tests in a base setup where some tests legitimately don't hit it — but overuse defeats the purpose of strict stubs and hides real problems.
- **`RETURNS_DEEP_STUBS`** — `mock(Foo.class, RETURNS_DEEP_STUBS)` auto-stubs chained calls so `foo.getBar().getBaz().getName()` returns a mock-of-a-mock instead of NPE-ing. Convenient but a **smell**: it usually means you're mocking a chain that violates the Law of Demeter, and the deep stub hides that coupling. Prefer building a real object or mocking only the immediate collaborator.

```java
@ExtendWith(MockitoExtension.class)   // STRICT_STUBS by default
class Demo {
    @Mock(answer = Answers.RETURNS_DEEP_STUBS) Config config;  // use sparingly

    @Test void t() {
        lenient().when(config.get("unused")).thenReturn("x");  // won't fail if unused
        when(config.getDb().getUrl()).thenReturn("jdbc:...");  // deep stub
    }
}
```

Net guidance: keep strict stubs on (they find bugs in *tests*), use `lenient()` surgically, and treat deep stubs as a refactor prompt.

#### Q57. [Theory] When you stub the same method twice or stub plus verify, what are the precedence rules in Mockito?

Mockito resolves overlapping stubs and verification with a few deterministic rules worth knowing:

- **Last matching stub wins for the *same* matcher.** If you write two `when(mock.foo()).thenReturn(1)` then `...thenReturn(2)`, calls return `2`. Re-stubbing replaces.
- **Consecutive chaining returns in sequence.** `when(mock.foo()).thenReturn(1, 2, 3)` (or chained `.thenReturn`) returns 1, then 2, then 3, then **sticks on the last value** for all further calls.
- **More-specific argument stubs and ordering.** With different matchers, the stub whose matcher matches the actual argument applies. When multiple could match, the **last declared** matching stub wins — so declare the general `any()` case *first* and specific cases *after*, or the general one shadows them.
- **`verify` checks invocations, independent of stubbing.** Verification counts actual calls; an unstubbed method still returns a type default (null/0/empty) and can be verified. Default answers don't count as "stubbed."
- **`doReturn/doThrow/doAnswer` for special cases** — required for `void` methods, for spies (to avoid calling the real method during stubbing), and when the real call would throw. They take precedence in the sense that they bypass the real method entirely.

```java
when(mock.lookup(anyString())).thenReturn("default");  // general FIRST
when(mock.lookup("vip")).thenReturn("special");         // specific LAST wins for "vip"
```

#### Q58. [Practical] How does `@InjectMocks` decide which constructor/field to inject, and what are its silent failure modes?

`@InjectMocks` tries, in order: **constructor injection** (it picks the constructor with the most arguments it can satisfy), then **setter injection**, then **field injection**. It matches mocks to targets primarily **by type, then by name** when types are ambiguous.

The dangerous part is its **silent failure modes**:

- If injection *partially* fails — e.g., a dependency has no matching `@Mock` — Mockito does **not** throw; it leaves that field `null`. You then get a confusing `NullPointerException` deep in the test rather than a clear "missing dependency" error.
- **Same-type ambiguity**: two collaborators of the same type are disambiguated by field *name* matching the constructor parameter name; if names don't line up (or parameter names were stripped at compile time), the wrong mock — or null — can be injected.
- It can mask a **constructor that does real work**; `@InjectMocks` instantiates the real object, so logic in the constructor runs against partially-injected mocks.

Because of this, many teams **prefer explicit constructor injection in the test** over `@InjectMocks`:

```java
@Mock Repo repo;
@Mock Clock clock;
Service service;

@BeforeEach
void setUp() {
    service = new Service(repo, clock);   // explicit: compile-time safe, no silent nulls
}
```

This makes missing/added dependencies a **compile error**, not a runtime NPE, and documents exactly how the unit is wired.

#### Q59. [Theory] Why is asserting on floating-point equality dangerous in tests, and how do you do it correctly?

Binary floating point (`double`/`float`) can't represent most decimal fractions exactly, so arithmetic accumulates tiny representation errors: `0.1 + 0.2 == 0.30000000000000004`, and `assertEquals(0.3, 0.1 + 0.2)` fails. Naive equality on computed doubles is therefore inherently flaky across values, platforms, and JIT optimizations.

Correct approaches:

- **Assert within a tolerance (epsilon).**

```java
assertEquals(0.3, 0.1 + 0.2, 1e-9);                  // JUnit 5 delta overload
assertThat(0.1 + 0.2).isCloseTo(0.3, within(1e-9));  // AssertJ
```

- **Use `BigDecimal` for money and exact decimals** — and compare with `compareTo` (value), *not* `equals` (which also compares scale, so `2.0` ≠ `2.00`):

```java
assertThat(price).usingComparator(BigDecimal::compareTo).isEqualTo(new BigDecimal("19.99"));
// or
assertThat(price.compareTo(new BigDecimal("19.99"))).isZero();
```

The deeper lesson: choose the epsilon based on the **domain's required precision**, and never use `double` for currency in the first place — that removes the whole class of problem.

#### Q60. [Practical] How do `@TempDir`, `@CsvFileSource`, and `@ArgumentsSource` extend JUnit 5's parameterization and resource handling?

These are lesser-known but powerful built-ins:

- **`@TempDir`** injects a fresh temporary directory per test (or shared per class as a `static` field), and JUnit **deletes it automatically** afterward — clean, isolated file-system tests without manual cleanup.
- **`@CsvFileSource`** sources parameters from a CSV file on the classpath, good for large data sets or data maintained outside code.
- **`@ArgumentsSource`** plugs in a **custom `ArgumentsProvider`**, the most flexible source — when `@MethodSource`/`@CsvSource` aren't enough (e.g., generating randomized-but-seeded cases or pulling from a fixture factory).

```java
@Test
void writesAndReadsFile(@TempDir Path dir) throws IOException {
    Path file = dir.resolve("data.txt");
    Files.writeString(file, "hello");
    assertThat(Files.readString(file)).isEqualTo("hello");   // dir auto-deleted after
}

@ParameterizedTest
@CsvFileSource(resources = "/shipping-cases.csv", numLinesToSkip = 1)
void shipping(int weight, String country, double fee) { ... }

@ParameterizedTest
@ArgumentsSource(RandomOrderProvider.class)   // custom provider, seeded for determinism
void processesOrder(Order order) { ... }
```

`@TempDir` in particular removes a classic flakiness source (leftover temp files between runs) and respects per-method isolation.

#### Q61. [Theory] How does Spring's test ApplicationContext caching actually key contexts, and how do you avoid cache misses?

Spring's `TestContext` framework **caches the `ApplicationContext`** so multiple test classes can share one expensive boot. The cache key is the **full set of context-configuration attributes**, including:

- the `@ContextConfiguration` / `@SpringBootTest` classes & locations,
- **active profiles** (`@ActiveProfiles`),
- **property sources** (`@TestPropertySource`, inlined properties),
- the set of **`@MockitoBean`/`@MockitoSpyBean`** definitions,
- context initializers, `web` environment type, and a few more.

If *any* of these differ, you get a **different key → a brand-new context built and cached separately**. So a suite with many slightly-different `@TestPropertySource` values or ad-hoc `@MockitoBean` combinations silently builds dozens of contexts, dominating CI time.

To maximize cache hits:

- **Standardize configuration** behind shared abstract base test classes so most tests share one key.
- Group the **same `@MockitoBean` set** rather than sprinkling per-test variations.
- **Avoid `@DirtiesContext`** — it *evicts* the entry and forces a rebuild; reset state explicitly instead.
- Use a small number of well-known profile/property combinations.

The mental model: each *distinct configuration* costs one full boot; design tests so there are only a handful of distinct configurations across the whole suite.

#### Q62. [Practical] What is `@DynamicPropertySource`, and how does it differ from `@ServiceConnection` for wiring Testcontainers into Spring?

Both feed a container's runtime coordinates (URL, port, credentials) into the Spring environment, but at different levels:

- **`@DynamicPropertySource`** is the **manual, general** mechanism: a `static` method receiving a `DynamicPropertyRegistry` where you register properties *lazily* (evaluated after the container starts). Works for **any** property and any container, including ones with no Spring Boot integration.

```java
@Container static GenericContainer<?> redis =
    new GenericContainer<>("redis:7").withExposedPorts(6379);

@DynamicPropertySource
static void props(DynamicPropertyRegistry r) {
    r.add("spring.data.redis.host", redis::getHost);
    r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
}
```

- **`@ServiceConnection`** (Spring Boot 3.1+) is the **declarative, zero-config** path for **supported** container types (Postgres, MySQL, Kafka, Redis, MongoDB, etc.). You annotate the `@Container` field and Boot auto-derives all the connection properties via a `ConnectionDetails` factory — no property names to hand-write.

```java
@Container @ServiceConnection
static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16");
```

Use `@ServiceConnection` whenever the container is supported (less boilerplate, fewer typos); fall back to `@DynamicPropertySource` for unsupported images or bespoke properties (a feature flag, a custom client setting).

### 🟠 — extended

#### Q63. [Theory] What are equivalence partitioning and boundary value analysis, and why do boundaries catch the most bugs?

These are systematic **black-box test-design** techniques for choosing inputs without testing every possible value:

- **Equivalence partitioning** — divide the input domain into classes where the program is expected to behave *the same*, then test **one representative per class** (plus invalid classes). E.g., for "age 18–65 eligible," the partitions are `<18` (invalid), `18–65` (valid), `>65` (invalid); one value from each largely covers the class.
- **Boundary value analysis (BVA)** — test the **edges** of each partition and just inside/outside them: for `18–65`, test `17, 18, 19` and `64, 65, 66`. Often you test the boundary and boundary±1.

Boundaries catch the most bugs because the **most common defects are off-by-one and wrong relational operators** — `>` vs `>=`, `<` vs `<=`, `<` vs `!=`. These errors are invisible mid-partition (where any representative passes) and only manifest *exactly at the edge*. This is precisely the class of fault that **mutation testing** targets by flipping `>` to `>=`; a suite that tests only mid-partition representatives lets those mutants survive. So combine the two: partitioning for breadth (cover every behavior class cheaply), BVA for the edges where logic actually breaks.

#### Q64. [Theory] How do property-based testing (jqwik) and example-based testing differ, and when is each superior?

**Example-based** testing (ordinary JUnit) asserts on **specific, hand-picked inputs** you thought of. **Property-based** testing asserts on **invariants that must hold for *all* inputs**, and the framework **generates hundreds of randomized cases** to try to falsify them — then **shrinks** any failing case to the minimal reproducer.

```java
import net.jqwik.api.*;

class SortProperties {
    @Property
    boolean sortingIsIdempotent(@ForAll List<@IntRange(min=-1000, max=1000) Integer> xs) {
        List<Integer> once = sort(xs);
        return once.equals(sort(once));            // sorting a sorted list changes nothing
    }

    @Property
    boolean sortPreservesSizeAndElements(@ForAll List<Integer> xs) {
        List<Integer> sorted = sort(xs);
        return sorted.size() == xs.size()
            && new HashSet<>(sorted).equals(new HashSet<>(xs));
    }
}
```

Property-based shines for:

- **Algorithmic / pure functions** with clear invariants — round-trips (`decode(encode(x)) == x`), idempotence, commutativity, ordering, conservation.
- Finding **edge cases you'd never enumerate** (empty, huge, negative-zero, Unicode, boundary integers) — the generator explores the space, and shrinking hands you the smallest failing input.

Example-based is superior when behavior is **specific and not a general law** (this customer with this discount yields this price), for documentation-by-example, and where defining a meaningful invariant is harder than just stating the expected output. In practice they're complementary: properties for the laws, examples for the specifics and as regression pins for found bugs.

#### Q65. [Practical] Explain how PIT mutation testing works internally (mutators, test selection, performance) and how to interpret a surviving mutant.

**PIT (pitest)** measures fault-detection power by:

1. **Generating mutants** — it applies **mutation operators** to *bytecode* (not source), each a small semantic change: `CONDITIONALS_BOUNDARY` (`<`→`<=`), `NEGATE_CONDITIONALS`, `MATH` (`+`→`-`), `INCREMENTS`, `RETURN_VALS` (return `null`/`0`/`false`), `VOID_METHOD_CALLS` (remove a call), `EMPTY_RETURNS`, etc. The default set is a curated, low-false-positive subset.
2. **Selecting tests per mutant** — naively running the whole suite per mutant is `O(mutants × tests)` and infeasible. PIT first runs the suite with **line coverage instrumentation**, then for each mutant runs **only the tests that cover the mutated line**, and **stops at the first killing test**. This makes it tractable on real codebases.
3. **Classifying** each mutant: **KILLED** (a covering test failed — good), **SURVIVED** (all covering tests passed — a gap), **NO_COVERAGE** (no test even reached it), or **TIMED_OUT** (the mutant caused an infinite loop — counted as killed, since the test detected the change via timeout).

Interpreting a **surviving mutant**: it pinpoints a *specific behavior your assertions don't pin down*. A survived `CONDITIONALS_BOUNDARY` on `if (x > limit)` means **no test exercises the `x == limit` boundary**; a survived `RETURN_VALS` means a test calls the method but **never asserts its return value**. The fix is targeted: add the boundary case or the missing assertion. The **mutation score** (killed / (total − no-coverage), or sometimes / total) is the headline number; chase it on critical modules, not trivially everywhere (mutation runs are expensive — use `--targetTests`/incremental analysis on changed code).

#### Q66. [Theory] What is consumer-driven contract *internals*: how does Pact generate, share, and verify a contract, and what does "can-i-deploy" actually check?

Mechanically, Pact works in three phases:

1. **Consumer side (generation):** the consumer's unit test runs against a **Pact mock provider** (an in-process HTTP server). Each interaction you declare (`given` provider state, request, expected response) is recorded; if the consumer code calls the mock as specified, Pact emits a **pact file** — JSON listing every request/response pair (with **matchers**, e.g., "any string here," not just literals, so it tests *structure* not exact values).
2. **Sharing:** the pact file is published to a **Pact Broker** (or PactFlow), tagged with the consumer's **version and branch/environment**. The broker is the system of record for "which consumer version expects which provider behavior."
3. **Provider side (verification):** the provider's build pulls the relevant pacts and **replays each recorded request against the real running provider**, setting up each interaction's `given` **provider state** via hooks (seed the DB so "user 1 exists"). If the real response satisfies the consumer's matchers, the interaction passes; results are published back to the broker.

**`can-i-deploy`** then answers a *deployment-safety* question without an integration environment: given "I want to deploy consumer X version 1.4 to **production**," it queries the broker for **every provider X talks to** and checks that, **for the versions currently in production**, there exists a **verified, compatible contract**. If a provider in prod hasn't verified X@1.4's expectations (or verified them as failing), `can-i-deploy` returns non-zero and **blocks the deploy**. This is what makes **independent deployability** safe: each service deploys on its own cadence, and the broker's matrix of "who-verified-what-where" prevents shipping a consumer whose expectations the live providers don't meet.

#### Q67. [Practical] How do you test database transaction and isolation behavior (e.g., that a method is `@Transactional`, rollback-on-exception, or optimistic locking)?

Transactional behavior is integration-level (it needs a real transactional resource), and there are distinct properties to verify:

- **Rollback on exception** — assert that a failure mid-method leaves **no partial writes**. Run against a real DB (Testcontainers), trigger the failure, then assert the side effects were rolled back. Don't use `@DataJpaTest`'s auto-rollback here (it would hide the behavior); use a real transaction boundary.

```java
@SpringBootTest @Testcontainers
class TransferServiceTxTest {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16");
    @Autowired TransferService service;
    @Autowired AccountRepository accounts;

    @Test
    void transferRollsBackEntirelyWhenSecondDebitFails() {
        assertThatThrownBy(() -> service.transfer("A", "BAD", 100))
            .isInstanceOf(AccountNotFoundException.class);
        // first debit must have been rolled back — balance unchanged
        assertThat(accounts.findById("A").orElseThrow().getBalance())
            .isEqualByComparingTo("1000.00");
    }
}
```

- **Optimistic locking** (`@Version`) — load the same entity in two "sessions," save one, then save the other and assert `OptimisticLockingFailureException`:

```java
@Test
void concurrentUpdateThrowsOptimisticLockException() {
    Product p1 = repo.findById(1L).orElseThrow();
    Product p2 = repo.findById(1L).orElseThrow();   // same version
    p1.setPrice(BigDecimal.TEN);  repo.saveAndFlush(p1);   // bumps version
    p2.setPrice(BigDecimal.ONE);
    assertThatThrownBy(() -> repo.saveAndFlush(p2))
        .isInstanceOf(OptimisticLockingFailureException.class);
}
```

- **Isolation-level / race behavior** (lost updates, phantom reads) requires **genuine concurrency** — spin up two threads with a `CyclicBarrier`/`CountDownLatch` to force overlap, against a real DB, since H2 won't reproduce Postgres's MVCC semantics. A key caveat: `@Transactional` on the *test method* can mask production behavior (it wraps everything in one transaction that never commits and uses one connection), so for true transaction tests, **don't** make the test transactional — let the service's own boundaries run.

#### Q68. [Theory] How do you test multithreaded / concurrent code deterministically, and what tools help (CountDownLatch, jcstress, awaitility)?

Concurrency bugs (races, visibility, deadlocks) are **nondeterministic by nature**, so naive tests either miss them (the race rarely triggers) or are flaky. Techniques, from simple to specialized:

- **Force interleavings with synchronization aids** — `CountDownLatch`/`CyclicBarrier` to make N threads start *simultaneously* (maximizing contention), `Phaser` for multi-stage coordination, then join and assert the final state. This raises the probability of triggering a race versus letting threads start staggered.

```java
@Test
void counterIsThreadSafeUnderContention() throws Exception {
    Counter counter = new Counter();
    int threads = 16, perThread = 10_000;
    var start = new CountDownLatch(1);
    var done  = new CountDownLatch(threads);
    var pool  = Executors.newFixedThreadPool(threads);
    for (int i = 0; i < threads; i++) {
        pool.submit(() -> {
            start.await();                       // all wait, then go together
            for (int j = 0; j < perThread; j++) counter.increment();
            done.countDown(); return null;
        });
    }
    start.countDown();                            // release simultaneously
    done.await(5, TimeUnit.SECONDS);
    assertThat(counter.get()).isEqualTo(threads * perThread);   // no lost updates
    pool.shutdownNow();
}
```

- **Repeat to raise trigger probability** — `@RepeatedTest(100)` or loop, since a single run may not interleave unluckily. (This raises *confidence*, never *proof*.)
- **`Awaitility`** — for async completion, poll the condition instead of sleeping.
- **jcstress (Java Concurrency Stress)** — the *right* tool for **low-level memory-model / lock-free** code. It runs billions of interleavings, enumerates *all observed result states*, and flags **forbidden/surprising** outcomes the JMM allows — catching visibility/reordering bugs ordinary tests can't.
- **Static/dynamic analyzers** — Thread Sanitizer-style tools, `-Xint`/stress JVM flags, and code review for happens-before reasoning.

The honest caveat: passing concurrency tests **increase confidence but don't prove correctness** — absence of an observed race isn't absence of the race. For critical lock-free structures, reason about the JMM *and* stress with jcstress.

### 🔴 — extended

#### Q69. [Theory] Compare the "London school" (mockist) and "Detroit/Chicago school" (classicist) of TDD. What are the deeper trade-offs?

The two schools differ in **how they isolate the unit under test** during outside-in TDD:

- **Detroit / Chicago (classicist, state-based)** — the "unit" is a *behavior*, possibly spanning a small cluster of real collaborating objects. You use **real objects** where cheap and **mock only true external boundaries** (I/O). Tests assert on **resulting state/outputs**. Design emerges bottom-up.
- **London (mockist, interaction-based)** — the "unit" is a *single class*; **every collaborator is mocked**. Tests assert on **interactions** (which methods were called on the mocks). Design is driven top-down: you discover collaborators' interfaces by mocking the calls you wish existed ("need-driven design," the *GOOS* book).

Deeper trade-offs:

| Aspect | Classicist (state) | Mockist (interaction) |
|---|---|---|
| Refactor resistance | High — tests survive internal restructuring | Lower — mocks couple to call structure; refactors break tests |
| Failure localization | Coarser — a bug can surface in several tests | Sharp — exactly one unit's test fails |
| Design feedback | Emergent, bottom-up | Strong upfront interface discovery |
| Risk | Slightly larger units; some integration overlap | Over-mocking; "all green, system broken"; tests mirror code |
| Best for | Pure domain logic, algorithms | Coordinating/orchestration classes with genuine collaborators |

Most experienced teams are **pragmatically classicist by default** (state assertions are more durable), reaching for **mockist interaction verification at true boundaries** (a gateway, a publisher) where the side effect *is* the contract. The debate ultimately maps to the broader "test behavior, not implementation" principle — mockist tests are more prone to violating it.

#### Q70. [Theory] What is the relationship between testability and good design? How is "hard to test" a design signal?

Testability and good (low-coupling, high-cohesion) design are **two views of the same property**. A unit is easy to test when it has *clear, narrow seams* — explicit dependencies you can substitute, deterministic behavior, and a focused responsibility. Those are exactly the qualities that make code easy to *change* and *reason about*. So testability is a **continuous design metric** you get for free, and friction in a test is feedback:

- **Hard to instantiate** (huge constructor, many collaborators) → the class has **too many responsibilities**; split it (SRP).
- **Must mock 8 things** to test one method → **high coupling** / a missing abstraction; the class is an orchestration hub that should delegate to cohesive pieces.
- **Can't make it deterministic** (calls `now()`, `new Random()`, static singletons, `new` of a dependency inside a method) → **hidden dependencies**; inject them (dependency inversion), introducing a seam.
- **Need deep stubs / Law-of-Demeter chains** (`a.getB().getC().doX()`) → **inappropriate intimacy**; tell-don't-ask, or pass the needed object directly.
- **Want to test a private method directly** → that logic wants to be its **own collaborator** with a public contract.

This is the core thesis of *Growing Object-Oriented Software, Guided by Tests*: writing the test first **exerts pressure** toward decoupled, well-factored designs because painful tests are unwelcome, so you fix the *design* to make the test pleasant. The senior framing: don't write awkward tests to fit awkward code — treat the awkwardness as the code telling you where the design is wrong, and refactor the production code first.

#### Q71. [Practical] How would you design a fault-injection / chaos test for resilience patterns (timeouts, retries, circuit breakers, bulkheads)?

Resilience code only earns its keep under failure, so you must **inject the failure deterministically** and assert the *pattern's* behavior, at two levels:

**Unit/component level** — stub the dependency to produce the exact failure mode, and verify the resilience policy reacts correctly. With **Resilience4j**:

```java
@Test
void circuitOpensAfterFailureThresholdThenShortCircuits() {
    // Arrange a breaker: open after 50% failures in a window of 4
    CircuitBreaker cb = CircuitBreaker.of("svc", CircuitBreakerConfig.custom()
        .slidingWindowSize(4).failureRateThreshold(50).build());
    when(client.call()).thenThrow(new TimeoutException());

    // Drive it to the threshold
    for (int i = 0; i < 4; i++) {
        assertThatThrownBy(() -> cb.executeCallable(client::call));
    }
    // Breaker now OPEN → next call short-circuits WITHOUT invoking the client
    assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    assertThatThrownBy(() -> cb.executeCallable(client::call))
        .isInstanceOf(CallNotPermittedException.class);
    verify(client, times(4)).call();   // the open breaker did NOT call a 5th time
}
```

Assert the *properties* of each pattern: **retry** stops after N attempts and respects backoff (drive a `.thenThrow().thenThrow().thenReturn()` sequence, verify call count and that a success on attempt 3 returns normally); **timeout** aborts a slow call within budget (a controllable latency stub); **bulkhead** rejects once max concurrent permits are exhausted (saturate with latched threads, assert the overflow call is rejected — protecting the rest of the system); **fallback** returns the degraded response when the primary fails.

**Integration / chaos level** — inject failures into *real* infrastructure: a **Toxiproxy** container (via Testcontainers) sits between app and DB/dependency and injects **latency, bandwidth limits, or connection cuts**, so you verify end-to-end that the app degrades gracefully, sheds load, and recovers when the dependency returns. In larger environments this graduates to **chaos engineering** (Chaos Monkey/Litmus) running controlled experiments in staging/prod with a hypothesis ("killing one replica keeps p99 within SLO"), a blast-radius limit, and automated rollback. The throughline: a resilience feature with no fault-injection test is **untested by definition** — the happy path never exercises it.

#### Q72. [Theory] How do you test for security properties and prevent regressions (authz checks, injection, dependency CVEs) within a CI test strategy?

Security testing layers onto the normal pyramid; the goal is to make security **executable and regression-guarded**, not a one-off pentest:

- **Authorization tests as first-class functional tests** — for every protected endpoint, assert the *negative* cases: anonymous → 401, wrong-role/other-tenant → 403, and **horizontal access** (user A cannot read user B's resource). These are ordinary `@WebMvcTest`/integration tests with `@WithMockUser`/`SecurityMockMvcRequestPostProcessors`. Authz gaps are the most common and most damaging real-world bug class (broken object-level authorization), and they're invisible unless you test the *denial* paths explicitly.

```java
@Test @WithMockUser(roles = "USER")
void userCannotAccessAnotherUsersOrder() throws Exception {
    mockMvc.perform(get("/orders/{id}", someoneElsesOrderId))
           .andExpect(status().isForbidden());
}
```

- **Injection / input-validation tests** — parameterized tests feeding malicious inputs (SQL metacharacters, path traversal `../`, oversized payloads, XSS strings) asserting they're rejected/escaped. Use **parameterized queries** (and test that the repository is parameterized), not string concatenation.
- **SAST / dependency scanning in CI** — static analyzers (SpotBugs + **find-sec-bugs**, Semgrep, CodeQL) and **Software Composition Analysis** (OWASP Dependency-Check, **Snyk**, GitHub Dependabot, OSV-Scanner) that **fail the build** on known-CVE dependencies. Pin and update via lockfiles; gate on severity thresholds.
- **DAST / fuzzing for deeper coverage** — OWASP ZAP against a deployed instance for runtime issues; coverage-guided **fuzzing (jazzer/JQF)** on parsers and input handlers to surface crashes and injection paths.
- **Secrets scanning** — gitleaks/trufflehog as a pre-commit/CI gate so credentials never land in history.
- **Regression pins** — when a vuln is found and fixed, add a **test reproducing the exploit** so it can never silently return (treat security bugs like any defect: failing test first, then fix).

The senior framing: bake security checks into the **same gating pipeline** as functional tests (shift-left) so a regression *blocks the merge*, and complement with periodic manual pentests for the creative attacks automation misses — automation for regressions and known classes, humans for novel threats.

#### Q73. [Practical] How do you approach testing and verifying non-deterministic AI/LLM components (2026), where outputs aren't byte-stable?

LLM-backed features break the deterministic assert-equals model: the same prompt yields different wordings, and quality is a spectrum, not pass/fail. A 2026 strategy layers several techniques:

- **Separate the deterministic seams from the model.** Unit-test everything *around* the LLM with the model **mocked/stubbed**: prompt construction, output **schema parsing/validation**, tool-call dispatch, retries, guardrails, token budgeting. These are normal deterministic tests and should be the bulk of coverage. Treat the model as an injected dependency behind an interface so you can substitute a fake.
- **Constrain and validate structure.** Force **structured output** (JSON schema / tool-calling) and assert the response **parses and validates** against the schema, required fields present, enums in range, numbers in bounds — deterministic even when prose varies. A response that doesn't conform is a hard failure regardless of wording.
- **Assertions on invariants, not exact text.** Check properties that must hold: contains required entities, **no PII leaked**, stays within allowed actions, cites a source from the provided context, length/format bounds, refuses out-of-scope requests. Use **semantic similarity** (embedding cosine ≥ threshold against a reference) where you need "means roughly this."
- **Eval suites / LLM-as-judge (offline, not in unit CI).** Maintain a curated **golden dataset** of inputs with graded rubrics; score with metrics (exact-match where applicable, **faithfulness/groundedness, relevance, toxicity, refusal-correctness**) often using an **LLM-as-judge** with a fixed rubric and a stronger judge model. Track an **aggregate score with a regression threshold** ("don't merge if eval score drops >X%"), because individual cases are noisy — you gate on the *distribution*, run multiple samples per case, and accept it's statistical.
- **Pin determinism where you can** for *some* tests: `temperature=0`/seed reduces (but doesn't guarantee) variance; record/replay (VCR-style cassettes of real API responses) makes CI fast, offline, and stable, refreshed periodically against the live model.
- **Adversarial / red-team tests** — prompt-injection, jailbreak, and data-exfiltration attempts as a standing suite, asserting guardrails hold; treat a successful jailbreak like a security regression with a reproducing case.
- **Guardrails and online evaluation** — runtime validators (schema, safety classifiers, groundedness checks) plus **production monitoring** (sampled human/automated review, drift detection), because the model and the world change underneath you; shift-right with canaries and human-in-the-loop for high-stakes outputs.

The mindset shift mirrors async/event-driven testing: move from *"output equals X"* to *"output satisfies these invariants, scores above threshold on an eval set, and resists known attacks"* — deterministic gates around a probabilistic core, plus statistical evaluation of the core itself.

#### Q74. [Theory] What are approval (snapshot/golden-master) tests, and where do they earn their place versus assertion-based tests?

An **approval test** (a.k.a. snapshot or golden-master test) asserts that the output of some operation **matches a previously-approved reference artifact** stored alongside the test. Instead of hand-writing assertions on each field, you capture the entire output (a serialized object, a rendered document, an HTTP response body, a generated SQL string), compare it to the saved "approved" copy, and **fail on any diff**. On the first run — or an intentional change — you review the produced output and, if correct, *approve* it (promoting "received" to "approved").

Where they earn their place:

- **Large, structurally-rich outputs** where field-by-field assertions are tedious and brittle — generated reports, serialized DTO trees, formatted exports, rendered HTML/emails.
- **Characterization / pinning legacy code** — the canonical *working-effectively-with-legacy-code* move: wrap untested code in a golden-master test that records its *current* behavior, giving you a safety net to refactor under, even before you understand the code.
- **Detecting unintended ripple effects** — a one-line change that subtly alters a serialized payload shows up immediately as a diff.

Tools: **ApprovalTests (Java)**, AssertJ's `toMatchSnapshot`-style helpers, or Spring's JSON comparison. The risks to manage: approvals can be **rubber-stamped** (a developer approves a wrong output without scrutiny — the failure mode unique to this style), they require **deterministic output** (scrub timestamps, ids, ordering before comparing, or they're perpetually flaky), and a large diff can hide the one meaningful change. Use them for *what the output is*; keep targeted assertions for *why a specific value must be what it is*.

#### Q75. [Practical] How do you write a characterization test to safely refactor untested legacy code, and what's the workflow?

A **characterization test** documents what the code **currently does** (bugs and all), not what it *should* do — its purpose is to **detect change**, creating a safety net before you touch a tangle of untested logic.

Workflow (Michael Feathers' approach):

1. **Find a seam and pin the output.** Write a test that calls the legacy method with representative inputs and asserts on *whatever it returns now* — even if you don't know the right answer yet.
2. **Let the test tell you the actual behavior.** Assert something deliberately wrong, run it, and read the failure message — it reveals the real output. Then set the assertion to that observed value. (Approval tests automate exactly this step.)

```java
@Test
void characterizeLegacyPricing() {
    LegacyPricingEngine engine = new LegacyPricingEngine();
    // I don't yet know the "correct" price — pin what it ACTUALLY produces today
    BigDecimal result = engine.price(new Cart(3, "GOLD", "US"));
    assertThat(result).isEqualByComparingTo("254.97");   // observed, not designed
}
```

3. **Expand coverage at the boundaries** — add cases for empty carts, max quantities, each customer tier, each region, null/odd inputs — building a net dense enough that any behavior change trips a test.
4. **Refactor under the net.** Now restructure, extract methods, introduce seams for dependency injection — and the characterization tests confirm you preserved behavior. If a test *legitimately* should change (you're fixing a known bug), update it deliberately and note why.
5. **Graduate to real tests.** As you understand the code, convert characterization assertions into intention-revealing behavior tests with meaningful names.

The discipline: **never refactor untested legacy code without first pinning its behavior** — otherwise you can't distinguish "I improved the structure" from "I silently changed what it does."

#### Q76. [Behavioral] Your organization wants to adopt a strict "no PR merges without tests" policy. As a senior engineer, how do you roll it out without tanking velocity or breeding gaming?

I'd treat it as a **change-management** problem, not a switch to flip, because a blunt mandate breeds exactly the pathologies we want to avoid — assertion-free tests written to clear the gate, and resentment that erodes the testing culture.

**Frame the why, with data.** I'd open with the cost of *not* testing — recent production incidents that a test would have caught, time lost to manual regression, the fear-of-change tax on the codebase — so the policy is a shared goal (ship faster *safely*), not a compliance burden.

**Gate on the right metric.** "Has tests" is gameable; I'd gate on **coverage of *changed* lines** (so legacy debt doesn't block work) with a pragmatic floor, plus **mutation testing on critical modules** so the green actually means something. Exclude generated code. The point is meaningful verification, not a number.

**Phase it in.** Start as a **warning/advisory** (the bot comments, doesn't block), publish a baseline, give teams a few sprints and good examples/templates, *then* make it blocking — first on the highest-risk services, expanding as the muscle builds. A flag day on the whole monorepo guarantees backlash.

**Invest in making testing easy** — fast test infra (the suite must be quick or people route around it), shared fixtures/builders/Testcontainers setup, and pairing/brown-bags so juniors learn *good* tests, not just *any* tests. A slow or painful test harness is the #1 reason policies get gamed.

**Watch for and name the gaming** — in code review, reject tests with no real assertions; I'd model the standard by reviewing for *test quality*, not just presence, and celebrate good tests publicly. **Allow principled exceptions** (a documented `// no-test: trivial config` escape hatch with reviewer sign-off) so the policy bends instead of breaking and people don't learn to defeat it.

**Measure the outcome, not just compliance** — track escaped-defect rate and change-failure rate, and be willing to adjust the policy if the data says it's adding ceremony without catching bugs. The success criterion is *fewer production incidents and more confident refactoring*, not *100% of PRs have a file named `*Test.java`*.

#### Q77. [Theory] How should a test strategy evolve across a system's lifecycle — prototype, growth, and mature/legacy phases — and why is "the right amount of testing" context-dependent?

There is no universal correct test ratio; the optimal investment is a function of **the cost of a bug** and **the rate of change**, both of which shift over a system's life:

- **Prototype / discovery phase** — the product hypothesis is unproven and may be thrown away. Heavy test coverage here is often **waste**: you'd be hardening code that's about to be deleted, and tests *slow* the pivoting you need. Strategy: a thin safety net on the **core domain logic and anything money/data-corrupting**, lean on manual exploration and fast feedback, accept low coverage elsewhere. The risk is *not shipping the right thing*, not regressions.
- **Growth / scaling phase** — the product is validated, the team and codebase are expanding, and regressions now cost real users and real revenue. This is where you **build the pyramid in earnest**: invest in fast unit coverage, slice/integration tests, contract tests for the services now multiplying, CI gates, and flakiness discipline. The dominant risk shifts to *breaking what works as you add features and people*, so tests become the enabler of *parallel, confident change* rather than overhead.
- **Mature / legacy phase** — change is slower but each change is **higher-stakes** (large blast radius, fewer people who understand the code). Strategy emphasizes **characterization tests** to pin existing behavior, strong **regression suites**, **contract tests** to safely evolve boundaries, and **production observability/synthetic monitoring** because the system is too big to fully pre-stage. The risk is *unintended ripple effects in code no one fully remembers*, so the net must be dense and the feedback (including in prod) strong.

The senior judgment is recognizing **which phase you're in and matching investment to risk** — over-testing a throwaway prototype and under-testing a payment system that handles millions are *the same mistake*: a mismatch between testing cost and bug cost. The constants across phases are: always protect **irreversible/financial/security-critical** paths heavily, keep the suite **fast and trusted** (a slow or flaky suite is net-negative at any phase), and treat the test strategy as something you **revisit deliberately** as the cost-of-failure and rate-of-change curves move — not a fixed policy set once and forgotten.

## 🧩 Extended Questions — Set 2: Practical scenarios, troubleshooting & coding

### 🟢 — extended

#### Q78. [Practical] A teammate's test passes when run alone but fails in the full suite. How do you diagnose it?

This is the classic signature of **test pollution** — a shared, mutable state that one test leaves behind and another depends on (or is corrupted by). Diagnosis workflow:

1. **Confirm the symptom** — run the failing test in isolation (green) and in the suite (red); the asymmetry confirms inter-test coupling rather than a bug in the test itself.
2. **Bisect by ordering** — run with `junit.jupiter.testmethod.order.default` set to a random/deterministic order, or use Surefire's `runOrder=random` with a printed seed, then narrow down *which* earlier test poisons it.
3. **Hunt the shared state** — the usual culprits are `static` fields, singletons (a `@Bean` holding mutable state, a static cache), the database (a row created by test A that test B's count assertion didn't expect), `System` properties, the default locale/timezone, or a `MockedStatic` left unclosed.
4. **Fix the leak, not the order** — never "solve" it by forcing test order (that hides the defect). Reset state in `@AfterEach` (clear caches, truncate tables, close static mocks), make the test create and assert only on *its own* data, and avoid `static` mutable fixtures.

```java
@AfterEach
void resetSharedState() {
    Locale.setDefault(Locale.US);           // if a test changed it
    cache.clear();                          // singleton cache
    // DB cleanup is usually done via @Sql or a truncate, or @Transactional rollback
}
```

The deeper signal: a suite that depends on execution order is **not isolated** (the "I" in F.I.R.S.T.), and running tests in random order in CI surfaces these bugs before they bite.

#### Q79. [Coding] Write a JUnit 5 test that fails right now (red), as the first step of TDD for a `StringCalculator.add("")` that should return 0.

In TDD you write the failing test *before* the production code exists. The compile error or assertion failure *is* the "red" — it proves the test actually exercises behavior that isn't there yet.

```java
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class StringCalculatorTest {

    private final StringCalculator calc = new StringCalculator();

    @Test
    void emptyStringReturnsZero() {
        assertThat(calc.add("")).isEqualTo(0);   // RED: StringCalculator/add() don't exist yet
    }
}
```

The minimal **green** implementation is then:

```java
public class StringCalculator {
    public int add(String numbers) {
        if (numbers.isEmpty()) return 0;
        // ... grows as the next failing test demands (single number, comma-separated, etc.)
        return Integer.parseInt(numbers);
    }
}
```

The discipline is to add the *next* failing test (`add("1")` → 1, then `add("1,2")` → 3) and only write code each new red demands — never code without a test pulling it into existence.

#### Q80. [Practical] Your `assertEquals` keeps failing on two objects that "look equal." What's happening and how do you fix it?

Almost always the class **doesn't override `equals()`/`hashCode()`**, so `assertEquals` falls back to **reference identity** (`Object.equals`), and two distinct instances with identical fields are "not equal." Fixes:

- **Make it a `record`** (Java 16+) — records auto-generate value-based `equals`/`hashCode`/`toString`. This is the modern default for data carriers.
- **Override `equals`/`hashCode`** consistently (or use Lombok `@EqualsAndHashCode`, IDE generation) if it must be a class.
- **Or assert field-by-field without touching production code** using AssertJ's recursive comparison — useful when you can't or shouldn't add `equals` (e.g., a JPA entity where `equals` semantics are contentious):

```java
assertThat(actual)
    .usingRecursiveComparison()
    .ignoringFields("createdAt", "id")     // ignore generated/volatile fields
    .isEqualTo(expected);
```

`usingRecursiveComparison()` compares the object graph field-by-field regardless of `equals`, and lets you ignore volatile fields — the pragmatic choice for rich DTOs and entities.

#### Q81. [Coding] Write a parameterized test covering the boundaries of a `isAdult(int age)` rule (adult at 18).

Boundary value analysis says the bugs live at the edge, so test 17/18/19 explicitly rather than one mid-partition value.

```java
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.assertThat;

class AgePolicyTest {

    @ParameterizedTest(name = "age {0} -> adult={1}")
    @CsvSource({
        "0,  false",
        "17, false",   // just below boundary
        "18, true",    // exactly at boundary (off-by-one trap)
        "19, true",    // just above
        "150, true"
    })
    void adultnessByAge(int age, boolean expected) {
        assertThat(AgePolicy.isAdult(age)).isEqualTo(expected);
    }
}
```

The `17/18/19` triple kills the common `>` vs `>=` mutant — the exact fault a mid-partition test (`age 40`) would miss.

#### Q82. [Practical] You see `UnnecessaryStubbingException` from a Mockito test. What does it mean and how should you respond?

Under strict stubs (the default with `MockitoExtension`), Mockito **fails the test if a `when(...)` stub is declared but never actually invoked** during the test. It's catching a real problem, not nagging:

- The stub is **dead** (left over from a refactor, or a copy-paste from another test), so delete it.
- Or the code path you *expected* to call it **didn't run** — which is itself a bug your test just revealed (e.g., a guard short-circuited before reaching the stubbed call).

```java
// FAILS: this stub is never reached because the user is inactive and the method returns early
when(repository.save(any())).thenReturn(saved);
service.process(inactiveUser);   // returns before saving
```

The right response is to **understand why the stub is unused** before silencing it. Only if the stub is *legitimately* shared across several tests in common setup (and some don't hit it) should you mark that single one `lenient()`. Reaching for `@MockitoSettings(strictness = Strictness.LENIENT)` on the whole class to make the error go away throws away a useful safety net.

#### Q83. [Coding] Write a test proving a method throws the *right* exception with the *right* message and cause.

`assertThrows`/`assertThatThrownBy` should pin not just the type but the message and (where it matters) the wrapped cause — otherwise a test passes on the wrong exception.

```java
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Test
void parsingInvalidConfigWrapsTheUnderlyingCause() {
    assertThatThrownBy(() -> ConfigLoader.load("not-json"))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("failed to parse config")
        .hasCauseInstanceOf(com.fasterxml.jackson.core.JsonParseException.class);
}
```

Asserting on `hasCauseInstanceOf` matters when your code wraps a low-level exception in a domain one — it verifies you didn't swallow the original, which is what makes production stack traces debuggable.

#### Q84. [Practical] A test calls a method that returns `Optional<User>` and you keep getting a `NoSuchElementException` in the test. What's the idiomatic fix?

The test is calling `.get()` on an empty `Optional` — usually because the stub returns `Optional.empty()` (the default for an unstubbed method that returns `Optional` is actually `Optional.empty()`, not `null`, but only if the mock is set up to return it; an unstubbed plain mock returns `null` for `Optional` unless `RETURNS_DEFAULTS` is configured). Two things to fix:

1. **Stub it to return a present Optional** when the test expects a hit:

```java
when(repository.findById(1L)).thenReturn(Optional.of(new User(1L, "ada@example.com")));
```

2. **Assert on the `Optional` directly** instead of unwrapping with `.get()` — AssertJ has first-class Optional assertions:

```java
assertThat(service.find(1L))
    .isPresent()
    .get()
    .extracting(User::getEmail)
    .isEqualTo("ada@example.com");

// empty case:
assertThat(service.find(99L)).isEmpty();
```

This avoids the `.get()` landmine and produces a clear failure ("expected present but was empty") instead of an opaque `NoSuchElementException`.

### 🟡 — extended

#### Q85. [Coding] Write a test for retry-with-backoff logic: the client fails twice then succeeds; assert it returns the value and was called exactly three times.

Stub a consecutive sequence with `thenThrow().thenThrow().thenReturn()`, then verify the call count — this makes retry behavior deterministic without real delays.

```java
@ExtendWith(MockitoExtension.class)
class ResilientFetcherTest {

    @Mock RemoteClient client;
    @InjectMocks ResilientFetcher fetcher;   // retries up to 3 times

    @Test
    void retriesTransientFailuresThenSucceeds() {
        when(client.fetch())
            .thenThrow(new TimeoutException("attempt 1"))
            .thenThrow(new TimeoutException("attempt 2"))
            .thenReturn("payload");

        String result = fetcher.fetchWithRetry();

        assertThat(result).isEqualTo("payload");
        verify(client, times(3)).fetch();          // 2 failures + 1 success
    }

    @Test
    void givesUpAfterMaxAttempts() {
        when(client.fetch()).thenThrow(new TimeoutException("always down"));

        assertThatThrownBy(fetcher::fetchWithRetry)
            .isInstanceOf(RetryExhaustedException.class);
        verify(client, times(3)).fetch();          // exactly the max, no more
    }
}
```

Crucially, the retry *delay* itself should be injected (a `Sleeper`/`Clock` seam) so the test doesn't actually wait — otherwise a 3-attempt test with real backoff sleeps for seconds.

#### Q86. [Practical] Your `@WebMvcTest` fails to start with "No qualifying bean" for a service the controller doesn't even use directly. Why, and how do you fix it?

`@WebMvcTest` loads the web slice but **must still satisfy the dependency graph of the controllers it loads** (and any `@ControllerAdvice`, filters, or `WebMvcConfigurer` it picks up). If a loaded component injects a bean that isn't a controller, the slice has no instance of it and fails. Fixes:

- **Provide the missing collaborator as a mock**: `@MockitoBean MissingService missingService;` — the slice gets a stand-in.
- **Scope the slice to one controller**: `@WebMvcTest(UserController.class)` rather than the un-targeted `@WebMvcTest`, which scans *all* controllers and drags in everyone's dependencies.
- If a `@Component` (e.g., a security filter or a Jackson customizer) is being picked up and pulling in heavy beans, exclude it or provide a test config.

The mental model: a slice is "web layer + nothing else," so **every non-web bean reachable from the loaded controllers must be mocked or explicitly supplied**.

#### Q87. [Coding] Write a `@DataJpaTest` proving a custom `@Query` derives the right result against a real Postgres via Testcontainers.

Testing a repository against H2 risks dialect gaps; pin it to the real engine.

```java
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class OrderRepositoryTest {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired OrderRepository orders;
    @Autowired TestEntityManager em;

    @Test
    void findsOnlyOrdersAboveThresholdSortedByTotalDesc() {
        em.persist(new Order("A", new BigDecimal("50.00")));
        em.persist(new Order("B", new BigDecimal("150.00")));
        em.persist(new Order("C", new BigDecimal("200.00")));
        em.flush();

        List<Order> result = orders.findHighValueOrders(new BigDecimal("100.00"));

        assertThat(result)
            .extracting(Order::getRef)
            .containsExactly("C", "B");      // > 100, sorted desc; "A" excluded
    }
}
```

Using `TestEntityManager.persist` + `flush` (rather than the repository's own `save`) seeds data through a separate path, so the test of the read query isn't tautological with the write path.

#### Q88. [Practical] How do you assert that a mock was NOT called with a specific argument, while it *was* called with others?

Combine `verify(..., never())` with an argument matcher, or use `verify` with a negated `argThat`. The common need is "we never sent an email to a blocked address, but did send to allowed ones":

```java
service.notifyAll(List.of(allowed, blocked));

verify(emailService).send(eq(allowed.getEmail()), any());      // allowed got one
verify(emailService, never()).send(eq(blocked.getEmail()), any());  // blocked never did

// Or assert nothing matched a predicate:
verify(emailService, never()).send(argThat(addr -> isBlocked(addr)), any());
```

A subtlety: `verify(mock, never()).method(any())` asserts the method was *never* called with *any* argument — too strong if it was legitimately called for other inputs. Scope the matcher (`eq(specific)` or `argThat(predicate)`) so you assert the **negative for the specific case** while allowing the positive cases.

#### Q89. [Coding] Write a test that uses `ArgumentCaptor` to verify an event published inside the service has the correct, computed fields.

When the unit constructs an object internally, you can't predict it with `eq(...)`; capture it and assert on its fields.

```java
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock EventPublisher publisher;
    @Mock Clock clock;
    @InjectMocks OrderService service;

    @Captor ArgumentCaptor<OrderPlacedEvent> eventCaptor;

    @Test
    void placingOrderPublishesEventWithComputedTotalAndTimestamp() {
        when(clock.instant()).thenReturn(Instant.parse("2026-06-30T12:00:00Z"));

        service.place(new OrderRequest("SKU-9", 4, new BigDecimal("25.00")));

        verify(publisher).publish(eventCaptor.capture());
        OrderPlacedEvent event = eventCaptor.getValue();

        assertThat(event.sku()).isEqualTo("SKU-9");
        assertThat(event.total()).isEqualByComparingTo("100.00");          // 4 * 25.00
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-06-30T12:00:00Z"));
    }
}
```

The `@Captor` annotation (with `MockitoExtension`) is cleaner than `ArgumentCaptor.forClass(...)` and avoids generics warnings.

#### Q90. [Practical] A flaky test fails ~1 in 50 runs with a timing-related assertion on an async result. Walk through fixing it.

The fix is to **stop racing the asynchronous work and instead wait for its observable outcome**:

1. **Identify the race** — the test asserts immediately (or after a fixed `Thread.sleep`) on state that an async task updates; sometimes the task finishes in time, sometimes not.
2. **Replace sleep/immediate-assert with a polled condition** using Awaitility, which re-checks until the assertion passes or a timeout elapses:

```java
producer.send(new Order("SKU-1"));

await().atMost(5, SECONDS)
       .pollInterval(50, MILLISECONDS)
       .untilAsserted(() -> assertThat(repository.findBySku("SKU-1")).isPresent());
```

3. **Pick a timeout generous enough for slow CI** but not so long it masks a genuine hang (5s is typical; a 30s timeout that "fixes" flakiness is hiding a real performance problem).
4. **Prove the fix** — run it a few hundred times (`@RepeatedTest(300)` or a CI loop) to confirm it's green consistently, since a 1-in-50 flake needs many iterations to trust.

The principle from the async-testing rule: assert on *eventual state*, never on *elapsed time*.

#### Q91. [Coding] Write a test that injects a fixed `Clock` so a "token expires after 15 minutes" rule is deterministic.

Never call `Instant.now()` in production code you want to test deterministically — inject a `Clock`.

```java
class TokenService {
    private final Clock clock;
    TokenService(Clock clock) { this.clock = clock; }

    Token issue() { return new Token(Instant.now(clock).plus(15, ChronoUnit.MINUTES)); }
    boolean isExpired(Token t) { return !Instant.now(clock).isBefore(t.expiresAt()); }
}

@Test
void tokenIsValidBeforeExpiryAndExpiredAfter() {
    Instant t0 = Instant.parse("2026-06-30T10:00:00Z");
    // mutable clock so we can advance time without sleeping
    var clock = new java.time.Clock() {
        Instant now = t0;
        public Instant instant() { return now; }
        public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        public java.time.Clock withZone(java.time.ZoneId z) { return this; }
        void advance(Duration d) { now = now.plus(d); }
    };
    TokenService service = new TokenService(clock);
    Token token = service.issue();

    assertThat(service.isExpired(token)).isFalse();   // at issue time
    clock.advance(Duration.ofMinutes(16));            // jump past expiry — no real sleep
    assertThat(service.isExpired(token)).isTrue();
}
```

Advancing a controllable clock tests time-dependent logic in microseconds; a `Thread.sleep(16 * 60_000)` would be absurd.

#### Q92. [Practical] You need to test code that reads an environment variable / system property. How do you do it without polluting other tests?

Don't mutate the real environment globally — that leaks into other tests. Options, best first:

- **Refactor for injection** — read the value once at construction and pass it in (`new Service(config.getApiUrl())`), so the test just supplies a value. This removes the dependency on the environment entirely.
- **JUnit Pioneer's `@SetSystemProperty` / `@SetEnvironmentVariable`** — scoped to the test and **auto-restored** afterward:

```java
@Test
@SetSystemProperty(key = "feature.x.enabled", value = "true")
void featureXIsOnWhenPropertySet() {
    assertThat(FeatureFlags.isEnabled("x")).isTrue();
}   // property restored after the test
```

- **For Spring**, use `@TestPropertySource(properties = "feature.x.enabled=true")` or `@DynamicPropertySource` so the value lives in the context, not the JVM globals.

The anti-pattern is `System.setProperty(...)` in the test body without cleanup — it silently changes behavior for every subsequent test in the JVM, manufacturing order-dependent flakiness.

### 🟠 — extended

#### Q93. [Coding] Write a test that forces a slow dependency to exceed a timeout and asserts the resilience layer aborts within budget.

Use a controllable-latency stub and a `@Timeout` (or assert the policy's own exception), so the test verifies the *timeout fired*, not that you waited.

```java
@Test
void callExceedingTimeoutFailsFastWithTimeoutException() {
    TimeLimiter limiter = TimeLimiter.of(TimeLimiterConfig.custom()
        .timeoutDuration(Duration.ofMillis(100)).build());

    // a supplier that would take 2s — should be aborted at ~100ms
    Supplier<CompletableFuture<String>> slow = () ->
        CompletableFuture.supplyAsync(() -> {
            try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return "late";
        });

    assertThatThrownBy(() -> limiter.executeFutureSupplier(slow))
        .isInstanceOf(TimeoutException.class);
}
```

The assertion is on the **TimeoutException being thrown**, which proves the limiter cut the call off — far better than measuring wall-clock time, which is itself flaky. The slow path runs on a separate thread so it doesn't actually block the test for 2s past the timeout.

#### Q94. [Practical] PIT reports a surviving `NEGATE_CONDITIONALS` mutant on `if (user.isActive())`. What does that tell you and how do you kill it?

A survived `NEGATE_CONDITIONALS` means PIT flipped the condition to `if (!user.isActive())` and **every covering test still passed** — proof that **no test distinguishes the active branch from the inactive branch**. Your tests exercise the line but never assert the *difference* the condition controls. To kill it, add a test that takes the **other branch** and asserts a different outcome:

```java
@Test
void activeUserReceivesNotification() {
    service.notify(aUser().active().build());
    verify(notifier).send(any());          // active path observable effect
}

@Test
void inactiveUserIsSkipped() {
    service.notify(aUser().inactive().build());
    verify(notifier, never()).send(any()); // inactive path — the missing assertion
}
```

Now flipping the condition makes one of the two tests fail, so the mutant is killed. The general rule: a surviving conditional mutant points to a **branch whose two outcomes aren't both asserted** — add the missing side.

#### Q95. [Coding] Write an idempotency test for a Kafka consumer using a Testcontainers Kafka broker.

Deliver the same record twice and assert the effect happened once — the property that actually matters under at-least-once delivery.

```java
@SpringBootTest
@Testcontainers
class PaymentConsumerIdempotencyTest {

    @Container @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer(
        DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    @Autowired KafkaTemplate<String, PaymentEvent> producer;
    @Autowired PaymentRepository payments;

    @Test
    void duplicateDeliveryProducesExactlyOneRecord() {
        PaymentEvent event = new PaymentEvent("evt-123", "acct-1", new BigDecimal("50.00"));

        producer.send("payments", event.eventId(), event);
        producer.send("payments", event.eventId(), event);   // duplicate

        await().atMost(10, SECONDS).untilAsserted(() ->
            assertThat(payments.findByEventId("evt-123")).hasSize(1));   // exactly one
    }
}
```

The production mechanism under test is a **dedup/inbox table keyed by `eventId`** inside the same transaction as the write. Awaitility handles the async, eventually-consistent nature; the assertion (`hasSize(1)`) is the exactly-once-*effect* guarantee.

#### Q96. [Practical] A `@SpringBootTest` suite that used to take 4 minutes now takes 12. How do you find what regressed?

The dominant cost in Spring tests is **context builds**, and the usual regression is that someone introduced configuration variety that **forked new cached contexts**. Investigation:

1. **Turn on context-cache logging** — set `logging.level.org.springframework.test.context.cache=DEBUG`. It logs cache hits/misses and the cache size; a sudden jump in distinct contexts is the smoking gun.
2. **Look for new cache-key differentiators** added recently — a new `@MockitoBean` combination, a per-test `@TestPropertySource`, an `@ActiveProfiles`, or a stray `@DirtiesContext` (which *evicts* and forces rebuilds).
3. **Check for a new heavy auto-configuration** pulled in (a starter dependency that boots more beans, or a new Testcontainer started per-class instead of as a singleton).
4. **Profile fork/parallelism** — did `forkCount` or parallel config change?

The fix is to **collapse the variety**: route tests through a shared base class with one configuration, group identical `@MockitoBean` sets, replace `@DirtiesContext` with explicit state reset, and use the **singleton container** pattern. Each *distinct* configuration costs one full boot — the suite slowed because the number of distinct configurations grew.

#### Q97. [Coding] Write a test using `MockedStatic` to stub a static utility, and explain why it must be scoped.

Static mocking is thread-local and must be closed, so scope it with try-with-resources.

```java
@Test
void usesStubbedSystemTimeForFileNaming() {
    try (MockedStatic<Instant> instantMock = mockStatic(Instant.class)) {
        instantMock.when(Instant::now)
                   .thenReturn(Instant.parse("2026-06-30T00:00:00Z"));

        String name = ReportNamer.dailyFileName();    // calls Instant.now() internally

        assertThat(name).isEqualTo("report-2026-06-30.csv");
    }   // static stub removed here — leaks nowhere
}
```

It **must** be scoped because the inline mock maker installs the stub on the **current thread only** and keeps it active **until closed**. An un-closed `MockedStatic` poisons every subsequent test on that thread (they'd see the stubbed static), and it can't be shared across parallel test threads. The cleaner long-term fix is usually to **inject a `Clock`/`Supplier` seam** instead of mocking `Instant.now()` statically — reaching for `mockStatic` is a hint the dependency should have been injected.

#### Q98. [Practical] How do you test that a method is correctly annotated `@Transactional` and actually rolls back, given proxy subtleties?

Two distinct things to verify, and a common trap:

- **The proxy is actually applied.** `@Transactional` works via a Spring AOP proxy; calling a `@Transactional` method **from within the same class** (self-invocation) bypasses the proxy, so the annotation does nothing. A unit test won't catch this — you need an **integration test that goes through the Spring-managed bean**.
- **Rollback genuinely happens.** Drive a failure mid-method against a **real DB** (Testcontainers) and assert no partial writes survive:

```java
@SpringBootTest @Testcontainers
class TransferRollbackTest {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16");
    @Autowired TransferService service;       // the real proxied bean
    @Autowired AccountRepository accounts;

    @Test
    void debitRollsBackWhenCreditFails() {
        assertThatThrownBy(() -> service.transfer("A", "MISSING", new BigDecimal("100")))
            .isInstanceOf(AccountNotFoundException.class);
        assertThat(accounts.findById("A").orElseThrow().getBalance())
            .isEqualByComparingTo("1000.00");   // debit was rolled back
    }
}
```

Critical caveat: **do not annotate the test method `@Transactional`** — that wraps everything in one test-managed transaction that never commits and reuses one connection, which *masks* the production rollback behavior you're trying to verify.

#### Q99. [Coding] Write a property-based test (jqwik) asserting a round-trip property: `deserialize(serialize(x)).equals(x)` for any valid object.

Round-trip (encode/decode) is the canonical property-test invariant — far stronger than a handful of examples because the generator explores inputs you'd never enumerate and shrinks failures to a minimal case.

```java
import net.jqwik.api.*;
import static org.assertj.core.api.Assertions.assertThat;

class JsonRoundTripProperties {

    @Property
    void serializeThenDeserializeYieldsEqualObject(@ForAll("orders") Order order) {
        String json = mapper.writeValueAsString(order);
        Order restored = mapper.readValue(json, Order.class);
        assertThat(restored).isEqualTo(order);          // round-trip invariant
    }

    @Provide
    Arbitrary<Order> orders() {
        Arbitrary<String> skus = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(10);
        Arbitrary<Integer> qty  = Arbitraries.integers().between(1, 1000);
        Arbitrary<BigDecimal> price = Arbitraries.bigDecimals()
            .between(BigDecimal.ZERO, new BigDecimal("9999.99")).ofScale(2);
        return Combinators.combine(skus, qty, price).as(Order::new);
    }
}
```

If serialization drops a field, mishandles a `null`, or loses `BigDecimal` scale, jqwik finds a failing case and **shrinks it** to the smallest reproducer (e.g., `qty=1, price=0.00`), pointing straight at the bug.

#### Q100. [Practical] Coverage shows a `catch` block as uncovered. Is that a problem, and how do you cover it meaningfully?

Not automatically a problem — but an *unexercised* error path is where production surprises hide, so it's worth a judgment call:

- **If the catch handles a realistic failure** (a timeout, a parse error, a DB constraint violation), **cover it by forcing that failure** and asserting the *handling behavior* — the fallback returned, the error logged/metered, the exception translated:

```java
@Test
void translatesRepositoryFailureIntoDomainException() {
    when(repository.findById(1L)).thenThrow(new DataAccessResourceFailureException("db down"));

    assertThatThrownBy(() -> service.get(1L))
        .isInstanceOf(ServiceUnavailableException.class)   // the catch's translation
        .hasCauseInstanceOf(DataAccessResourceFailureException.class);
}
```

- **If the catch is genuinely defensive/unreachable** (catching a checked exception the API declares but can never throw in context), forcing it may be impossible or contrived. Then either **simplify the code** (don't catch what can't happen) or **exclude it from the coverage ratio** rather than writing a meaningless test.

The point isn't the green number — it's whether the **error behavior is specified**. Covering a catch with no assertion on *what it does* is exactly the assertion-free coverage that mutation testing exposes.

### 🔴 — extended

#### Q101. [Practical] An integration test passes locally but fails only in CI with "connection refused" to the Testcontainer. How do you debug it?

This is an environment-fidelity gap between the dev machine and the CI runner. Systematic debugging:

1. **Container readiness** — the app connected before the container was actually accepting connections. Local machines (warm Docker, fast disk) hide this; CI (cold pull, slower I/O) exposes it. Fix with a proper **wait strategy** (`waitingFor(Wait.forListeningPort())` or `Wait.forLogMessage(...)`), and use `@ServiceConnection` which wires coordinates *after* startup.
2. **Docker availability/permissions in CI** — is the Docker daemon present and is the runner allowed to use it? Docker-in-Docker, rootless Docker, or a Testcontainers Cloud token may be needed. Check the agent's Docker socket access.
3. **Networking differences** — on CI the test may run *inside* a container, so `localhost` isn't the host; Testcontainers handles this via the host gateway, but custom `localhost` hardcoding breaks. Use `container.getHost()`/`getMappedPort()`, never a hardcoded port.
4. **Resource limits / image pull** — CI may throttle CPU/memory (slow startup → timeout) or rate-limit the registry pull (use a mirror/cache, pin tags). Increase the startup timeout for slow agents.
5. **Reproduce CI locally** — run the test inside the same CI image/container to recreate the environment rather than guessing.

The throughline: "works on my machine" for integration tests almost always means an **implicit dependency on local environment state** (warm cache, host networking, ready timing) that the test should make explicit.

#### Q102. [Coding] Write a concurrency test that forces a race on a non-thread-safe counter and proves the thread-safe version is correct.

Force simultaneous starts with a latch to maximize contention, then assert no lost updates.

```java
@Test
void atomicCounterHasNoLostUpdatesUnderContention() throws Exception {
    AtomicCounter counter = new AtomicCounter();     // uses AtomicLong internally
    int threads = 16, perThread = 50_000;
    var start = new CountDownLatch(1);
    var done  = new CountDownLatch(threads);
    var pool  = Executors.newFixedThreadPool(threads);

    for (int i = 0; i < threads; i++) {
        pool.submit(() -> {
            try {
                start.await();                       // all block here...
                for (int j = 0; j < perThread; j++) counter.increment();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        });
    }
    start.countDown();                               // ...then released together
    assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(counter.get()).isEqualTo((long) threads * perThread);   // exact, no races lost
    pool.shutdownNow();
}
```

Run the *same* test against the naive `long count++` version and it will (usually) fail with a count below the expected total — demonstrating the lost-update race. The honest caveat: a passing run **increases confidence but doesn't prove** thread-safety; for lock-free structures, complement with **jcstress**, which enumerates JMM-permitted interleavings.

#### Q103. [Practical] How do you write a contract test (consumer side, Pact) and what does it actually verify versus an integration test?

The consumer test runs against a **Pact mock provider** and records the expectations into a pact file — it verifies the **consumer correctly forms requests and parses responses** for a given contract, *without* the real provider being up.

```java
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "inventory-service")
class InventoryClientPactTest {

    @Pact(consumer = "order-service")
    V4Pact stockAvailable(PactDslWithProvider builder) {
        return builder
            .given("SKU-1 has 5 in stock")                 // provider state
            .uponReceiving("a stock check for SKU-1")
                .path("/stock/SKU-1").method("GET")
            .willRespondWith()
                .status(200)
                .body(new PactDslJsonBody()
                    .stringValue("sku", "SKU-1")
                    .integerType("available", 5))          // matcher: any integer, not literal 5
            .toPact(V4Pact.class);
    }

    @Test
    @PactTestFor(pactMethod = "stockAvailable")
    void clientParsesStockResponse(MockServer mockServer) {
        InventoryClient client = new InventoryClient(mockServer.getUrl());
        Stock stock = client.getStock("SKU-1");
        assertThat(stock.available()).isEqualTo(5);
    }
}
```

What it verifies vs. an integration test: the contract test confirms the **consumer's expectations of the interface** (URL, method, response shape) and produces a contract the **provider later verifies independently** — catching interface drift *without* a shared environment or coupled deploys. An integration test, by contrast, exercises the consumer against a *real* (or Testcontainer) provider, verifying *actual* end-to-end behavior but at the cost of environment coupling. Contract tests replace the *cross-service* integration tests; you keep integration tests for *your own* infrastructure (DB, broker).

#### Q104. [Coding] Write a fault-injection integration test using Toxiproxy to inject latency between the app and its database, asserting graceful degradation.

Toxiproxy (via Testcontainers) sits between the app and a real dependency and injects controllable network faults, so you can verify timeouts/fallbacks end-to-end.

```java
@SpringBootTest @Testcontainers
class DbLatencyResilienceTest {

    static Network net = Network.newNetwork();

    @Container static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16").withNetwork(net);

    @Container static ToxiproxyContainer toxiproxy =
        new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.9.0").withNetwork(net);

    static ToxiproxyContainer.ContainerProxy proxy;

    @DynamicPropertySource
    static void dbProps(DynamicPropertyRegistry r) {
        proxy = toxiproxy.getProxy(postgres, 5432);
        r.add("spring.datasource.url", () ->
            "jdbc:postgresql://" + proxy.getContainerIpAddress() + ":" + proxy.getProxyPort() + "/test");
    }

    @Autowired CatalogService service;

    @Test
    void slowDatabaseTriggersTimeoutAndFallback() throws Exception {
        proxy.toxics().latency("slow", ToxicDirection.DOWNSTREAM, 5_000);  // 5s added latency

        // service has a 1s query timeout + cached fallback
        CatalogResponse response = service.getCatalog();

        assertThat(response.degraded()).isTrue();          // served from fallback, not blocked
        assertThat(response.items()).isNotEmpty();
    }
}
```

This proves the *behavior under failure* — the query times out and the service serves a degraded-but-available response rather than hanging — which the happy path never exercises. A resilience feature without such a fault-injection test is untested by definition.

#### Q105. [Practical] You're asked to test an LLM-backed summarization endpoint where output text varies run to run. How do you make it testable in CI?

Apply the deterministic-gates-around-a-probabilistic-core strategy:

- **Mock the model for the bulk of tests.** Put the LLM behind an interface and inject a fake; unit-test everything *around* it deterministically — prompt assembly, **output schema parsing/validation**, retry/guardrail logic, token budgeting, PII redaction. These are normal assert-equals tests and should dominate coverage.
- **Assert invariants, not exact prose**, on real-ish output: the summary is **non-empty and within a length bound**, contains required entities from the source, **leaks no PII**, stays grounded (every claim traceable to the input), and **parses against the JSON schema** if structured output is used. Use **semantic similarity** (embedding cosine ≥ threshold vs. a reference) where "means roughly this" is needed.
- **Keep eval/LLM-as-judge suites offline, not in unit CI.** Maintain a curated **golden dataset** scored on faithfulness/relevance/toxicity with a fixed rubric, gate on an **aggregate score with a regression threshold** (sampling multiple times per case because individual outputs are noisy), and run it nightly or on model changes — not on every commit.
- **Stabilize what you can**: `temperature=0`/seed reduces variance, and **record/replay cassettes** of real responses make the CI path fast, offline, and stable, refreshed periodically against the live model.
- **Standing adversarial suite** — prompt-injection and jailbreak attempts asserting guardrails hold; treat a successful jailbreak as a security regression with a reproducing case.

The mindset, as with async systems: shift from *"output equals X"* to *"output satisfies these invariants and scores above threshold on an eval set."*

#### Q106. [Coding] Write an authorization regression test proving user A cannot access user B's resource (horizontal access / IDOR).

Broken object-level authorization (IDOR) is among the most damaging real-world bug classes, and it's invisible unless you test the **denial** path explicitly.

```java
@WebMvcTest(OrderController.class)
class OrderAuthorizationTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean OrderService orderService;

    @Test
    @WithMockUser(username = "userA", roles = "USER")
    void userCannotReadAnotherUsersOrder() throws Exception {
        // order 77 belongs to userB; service enforces ownership and throws/forbids
        when(orderService.getForCurrentUser(77L))
            .thenThrow(new AccessDeniedException("not your order"));

        mockMvc.perform(get("/orders/{id}", 77L))
            .andExpect(status().isForbidden());      // 403, NOT 200 with someone else's data
    }

    @Test
    void anonymousUserIsUnauthorized() throws Exception {
        mockMvc.perform(get("/orders/{id}", 77L))
            .andExpect(status().isUnauthorized());   // 401
    }
}
```

The key is asserting the **negative outcomes** (401 for anonymous, 403 for the wrong owner) — a suite that only tests "the owner can read their order" passes while the IDOR vulnerability ships. When a real IDOR is found and fixed, add a test reproducing it so it can never silently regress.

#### Q107. [Practical] A behavior-verifying test (`verify(...)`) breaks on every refactor even though behavior is unchanged. How do you fix the test design?

The test is **overspecified** — it asserts on *how* the code works (which collaborators it calls, in what order) rather than *what* it produces. Refactoring changes the "how" while preserving the "what," so the test fails on improvements it should tolerate. Remediation:

- **Switch from interaction verification to state verification** where possible: instead of `verify(repo).save(...); verify(mapper).map(...);`, assert on the **observable result** — the returned object, the persisted state (read it back), or the genuine externally-visible side effect.
- **Reserve `verify` for true boundaries** — a side effect that *is* the contract (an event published, an email sent, a payment captured). Verify *that* it happened with the right payload, not the internal call sequence that produced it.
- **Drop incidental assertions** — `verifyNoMoreInteractions` and `InOrder` on internal collaborators are usually overspecification; keep ordering assertions only where order is part of the contract (persist *before* publish).
- **Test at a slightly higher level** with real collaborators where cheap (and fakes for I/O), so behavior-preserving refactors of the internals don't touch the test surface at all.

The principle: a good test fails **only when behavior changes**. If it fails on a pure refactor, it's coupled to implementation — fix the *test* to assert outcomes, which also makes the suite a safety net for refactoring rather than an obstacle to it.

#### Q108. [Coding] Write a characterization/approval test to pin the current output of untested legacy code before refactoring it.

A characterization test records what the code *currently does* (bugs and all) so any change trips a failure — your safety net for refactoring under uncertainty.

```java
@Test
void characterizeLegacyInvoiceRendering() {
    LegacyInvoiceRenderer renderer = new LegacyInvoiceRenderer();
    Invoice invoice = new Invoice("ACME", List.of(
        new LineItem("Widget", 3, new BigDecimal("9.99")),
        new LineItem("Gadget", 1, new BigDecimal("19.95"))
    ));

    String rendered = renderer.render(invoice);

    // I don't yet know the "correct" format — pin what it ACTUALLY produces today.
    // (First run: assert something wrong, read the failure, paste the real output here.)
    assertThat(rendered).isEqualTo(
        "INVOICE: ACME\n" +
        "Widget x3 @ 9.99 = 29.97\n" +
        "Gadget x1 @ 19.95 = 19.95\n" +
        "TOTAL: 49.92\n");
}
```

For large outputs, use an **approval test** (ApprovalTests for Java) so the reference lives in an `.approved.txt` file and you review diffs rather than hand-editing strings. Once the net is dense (add boundary cases — empty invoice, zero quantity, each region), refactor freely; if a test *should* change because you're fixing a known bug, update it deliberately and note why. Then graduate the pins into intention-revealing behavior tests as you understand the code.

#### Q109. [Behavioral] After a production incident, the postmortem action item is "add a test." How do you make that meaningful rather than box-ticking?

I treat "add a test" as the *minimum*, and aim for the test to (a) **reproduce the exact failure** and (b) make the whole *class* of bug harder to recur — not just paper over one symptom.

**Write the failing test first.** Before any fix, I reproduce the incident as a red test at the *lowest level that captures it* — ideally a unit test, escalating to integration only if the bug lives in the wiring (a real SQL dialect issue, a transaction boundary, a race). Seeing it go red proves the test actually exercises the bug; seeing it go green after the fix proves the fix works. This is just systematic debugging applied to incidents.

**Pin the root cause, not the surface.** If the incident was a null that slipped through, I don't just assert "this one input no longer NPEs" — I ask *why* it was possible (missing validation? an Optional misused? a contract assumption?) and test the underlying invariant, often with a **parameterized or property-based test** covering the boundary class the incident exposed.

**Guard the regression permanently.** The test gets a clear name referencing the incident, lives in the gating suite, and (for the bug class) I consider a broader guard — a contract test if it was cross-service drift, a mutation-testing pass on the module to find sibling gaps, an authorization denial test if it was an authz hole.

**Fix the systemic gap too.** A single test is a point fix; the postmortem should also ask *why our existing tests didn't catch this* — was the path untested, the assertion too weak, the environment unfaithful (H2 vs Postgres)? Sometimes the real action item is "we test against the wrong database" or "our async tests assert too eagerly," and fixing *that* prevents a family of future incidents.

The anti-pattern I avoid is the assertion-light test added just to close the ticket — it gives false confidence. The bar is: **the test fails on the old code, passes on the new, and would catch the bug's cousins.**

## ✅ Key Takeaways

- Follow the **test pyramid**: many fast unit tests, fewer integration tests, a thin layer of e2e — and keep tests F.I.R.S.T.
- **JUnit 5** (lifecycle, parameterized, extensions) + **Mockito** (mock/spy/captor/verify) + **AssertJ** (fluent assertions) is the core JVM unit-test stack in 2026.
- Use **Spring test slices** (`@WebMvcTest`, `@DataJpaTest`) for focused, fast integration coverage and **Testcontainers** for high-fidelity tests against real databases and brokers.
- **Coverage measures execution, not verification** — gate with JaCoCo as a floor, but use **mutation testing (PIT)** to prove your assertions actually catch bugs.
- **Contract testing** (Pact / Spring Cloud Contract) replaces most cross-service integration tests and enables independent deployability.
- **Flaky tests are defects** — fix the root cause (inject `Clock`, await conditions, isolate state), don't re-run them away.
- Make performance budgets executable with **k6 / Gatling / JMeter** thresholds wired into CI.

## ⚠️ Common Pitfalls

- Chasing 100% coverage and writing assertion-free tests to hit the number.
- Over-mocking — verifying every interaction so tests break on any refactor and pass while production is broken.
- Using `Thread.sleep` for async coordination instead of Awaitility; relying on real time, locale, or `HashMap` ordering.
- Overusing `@SpringBootTest` (and `@DirtiesContext`) so the suite balloons to many minutes via repeated context builds.
- Testing against H2/in-memory fakes when production uses Postgres/Kafka — passing tests, failing prod due to dialect/feature gaps.
- Disabling or re-running flaky tests indefinitely instead of quarantining-with-a-ticket and fixing the cause.
- Tests coupled to implementation details (private methods, exact call sequences) rather than observable behavior.
- An inverted pyramid: mostly slow e2e/UI tests, yielding hours-long, flaky pipelines teams stop trusting.

## 📚 Further Reading

- *JUnit 5 User Guide* — junit.org/junit5 (lifecycle, parameterized tests, extension model).
- *Mockito* documentation and `org.mockito.Mockito` Javadoc (argument captors, verification modes, spies).
- *AssertJ* fluent assertions guide — assertj.github.io.
- *Testcontainers for Java* docs and Spring Boot `@ServiceConnection` reference.
- *Spring Boot Testing* reference — test slices, `@SpringBootTest`, `@MockitoBean`.
- *Growing Object-Oriented Software, Guided by Tests* — Freeman & Pryce (TDD, test doubles, mockist style).
- *Test-Driven Development: By Example* — Kent Beck.
- *xUnit Test Patterns* — Gerard Meszaros (test double taxonomy, fixtures, smells).
- **PIT** mutation testing — pitest.org; **Pact** — docs.pact.io; **Spring Cloud Contract** reference.
- **k6** (grafana.com/docs/k6), **Gatling**, and **Apache JMeter** documentation for load/performance testing.
