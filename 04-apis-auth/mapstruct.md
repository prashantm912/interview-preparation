# MapStruct

MapStruct is a Java annotation processor that generates type-safe, **compile-time** bean mapping code (plain method calls, no reflection) from `@Mapper` interfaces. It eliminates hand-written DTO↔entity boilerplate while staying fast, debuggable, and verifiable at build time.

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

### Q1. [Theory] What is MapStruct and how does it fundamentally differ from reflection-based mappers?

MapStruct is a code generator that runs as a **JSR-269 annotation processor** during `javac` compilation. You declare an interface annotated with `@Mapper`; MapStruct generates an implementation class (e.g. `UserMapperImpl`) that contains plain getter/setter calls. Because the mapping is just ordinary Java code, there is **no runtime reflection, no proxies, and no introspection cost** — performance is essentially the same as hand-written code.

The "why" matters in interviews: reflection-based mappers like ModelMapper and the older Dozer resolve property matches at runtime, which is slower, harder to debug, and fails *at runtime* if a field is renamed. MapStruct shifts those failures to **compile time** — a misspelled or unmapped property produces a build error or warning, not a `NullPointerException` in production three weeks later. This compile-time safety is MapStruct's core selling point.

```
   @Mapper interface  ──javac + annotation processor──▶  generated *Impl class
   (you write)                                            (plain get/set code, compiled into your jar)
```

### Q2. [Practical] Write a minimal MapStruct mapper for a DTO↔entity pair and explain the generated output.

```java
// Domain entity
public class User {
    private Long id;
    private String firstName;
    private String lastName;
    // getters/setters
}

// DTO
public class UserDto {
    private Long id;
    private String firstName;
    private String lastName;
    // getters/setters
}

@Mapper                      // org.mapstruct.Mapper
public interface UserMapper {
    UserMapper INSTANCE = Mappers.getMapper(UserMapper.class); // default (no-DI) lookup

    UserDto toDto(User user);
    User toEntity(UserDto dto);
}
```

MapStruct generates `UserMapperImpl` that does, conceptually:

```java
public UserDto toDto(User user) {
    if (user == null) return null;
    UserDto dto = new UserDto();
    dto.setId(user.getId());
    dto.setFirstName(user.getFirstName());
    dto.setLastName(user.getLastName());
    return dto;
}
```

Fields with identical names and compatible types are mapped automatically by convention. You only write `@Mapping` for the exceptions.

### Q3. [Theory] What does `@Mapping` do, and what are its most common attributes?

`@Mapping` overrides the default name-based matching for a single target property. The essentials:

- `source` / `target` — map `source="user.address.city"` (nested path) to `target="city"`.
- `expression` — inline Java, e.g. `expression = "java(user.getFirst() + \" \" + user.getLast())"`.
- `constant` — a fixed literal value.
- `dateFormat` / `numberFormat` — formatting for `String`↔`Date`/number conversions.
- `ignore = true` — explicitly skip a target property (silences "unmapped target" warnings).
- `defaultValue` / `defaultExpression` — value used when the source is `null`.

```java
@Mapping(target = "fullName", expression = "java(u.getFirstName() + \" \" + u.getLastName())")
@Mapping(target = "createdAt", source = "registeredOn", dateFormat = "yyyy-MM-dd")
@Mapping(target = "tenantId", constant = "RETAIL")
@Mapping(target = "password", ignore = true)  // never copy secrets into a DTO
UserDto toDto(User u);
```

### Q4. [Practical] How do you make MapStruct work as a Spring bean instead of using `Mappers.getMapper`?

Set the **component model** so the generated `*Impl` is annotated with `@Component` and discoverable by Spring's component scan:

```java
@Mapper(componentModel = "spring")
public interface UserMapper {
    UserDto toDto(User user);
}
```

```java
@Service
public class UserService {
    private final UserMapper mapper;            // injected — no INSTANCE field needed
    public UserService(UserMapper mapper) { this.mapper = mapper; }
}
```

Supported component models include `default` (factory), `spring`, `cdi`, `jsr330`, and `jakarta`. For Spring Boot 3 (Jakarta EE 9+), use `spring` or `jakarta`. Prefer constructor injection so mappers are testable and `final`.

---

## 🟡 Intermediate (3–7 yrs)

### Q5. [Theory] How does MapStruct map collections and nested objects automatically?

For **collections**, if you declare `List<UserDto> toDtos(List<User> users)` and a `UserDto toDto(User)` method already exists, MapStruct generates a loop that calls the element mapper per item. The same applies to `Set`, `Map`, and arrays. You rarely write the loop yourself — you just declare both signatures.

For **nested objects**, if a property is itself a bean (e.g. `User.address` of type `Address`), MapStruct looks for a method that maps `Address → AddressDto`. If one exists (in the same mapper or a mapper listed via `uses`), it is invoked; if not, MapStruct attempts to generate an inline nested mapping. The recommended practice is to **declare the element/nested mapper explicitly** so generation is predictable and reusable.

```
List<User> ──toDtos──▶ for each: toDto(User) ──▶ for Address field: toAddressDto(Address)
```

```java
@Mapper(componentModel = "spring")
public interface UserMapper {
    UserDto toDto(User u);
    List<UserDto> toDtos(List<User> users);        // generated loop reuses toDto
    AddressDto toAddressDto(Address a);             // reused for the nested field
}
```

### Q6. [Practical] You need to combine fields from two source objects and reference a shared helper mapper. How?

Use **multiple source parameters** plus the `uses` attribute to delegate sub-mappings to another mapper component:

```java
@Mapper(componentModel = "spring", uses = { AddressMapper.class, MoneyMapper.class })
public interface OrderMapper {

    @Mapping(target = "customerName", source = "customer.fullName")
    @Mapping(target = "shippingAddress", source = "order.shipTo")   // handled by AddressMapper
    @Mapping(target = "total", source = "order.amount")             // handled by MoneyMapper
    OrderDto toDto(Order order, Customer customer);
}
```

`uses` keeps mappers small and composable: `AddressMapper` and `MoneyMapper` are themselves Spring beans, injected into `OrderMapperImpl`. In production this is how you avoid one giant 600-line mapper and instead build a graph of focused, independently testable mappers. Trade-off: too much delegation can make it hard to trace which mapper handles which field, so keep the `uses` list meaningful.

### Q7. [Coding] Implement an update-in-place mapping that merges a DTO into an existing entity without overwriting fields that are null in the DTO.

**Problem:** A PATCH endpoint sends a partial `UserDto`. You must update only the non-null fields of the managed JPA entity, leaving the rest untouched.

```java
@Mapper(componentModel = "spring",
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface UserPatchMapper {

    // @MappingTarget tells MapStruct to mutate the passed entity instead of creating a new one
    void updateEntityFromDto(UserDto dto, @MappingTarget User entity);
}
```

Generated logic conceptually:

```java
public void updateEntityFromDto(UserDto dto, User entity) {
    if (dto == null) return;
    if (dto.getFirstName() != null) entity.setFirstName(dto.getFirstName());
    if (dto.getLastName()  != null) entity.setLastName(dto.getLastName());
    // null DTO fields are skipped -> existing entity values preserved
}
```

- **Time complexity:** O(N) over the number of mapped properties (constant per request).
- **Space complexity:** O(1) extra — mutates in place, no new object.
- **Edge cases:** a `null` DTO returns early; with `IGNORE`, you **cannot** clear a field to `null` via PATCH — if "explicitly set to null" must be distinguishable from "absent", switch to `Optional`/`JsonNullable` wrappers (e.g. `org.openapitools.jackson.nullable.JsonNullable`) instead of `IGNORE`. Collections obey `NullValueCheckStrategy` separately.

### Q8. [Coding] Map an enum to a String with one renamed value and a safe fallback for unknown inputs.

**Problem:** Convert `OrderStatus` to its API string. `OrderStatus.IN_TRANSIT` must serialize as `"SHIPPED"`; any future/unknown enum constant must map to `"UNKNOWN"` rather than failing the build.

```java
@Mapper(componentModel = "spring")
public interface StatusMapper {

    @ValueMapping(source = "IN_TRANSIT", target = "SHIPPED")
    @ValueMapping(source = MappingConstants.ANY_REMAINING, target = "UNKNOWN")
    String toApiStatus(OrderStatus status);

    // Reverse: any unrecognized String -> a null/sentinel enum
    @ValueMapping(source = MappingConstants.ANY_UNMAPPED, target = MappingConstants.NULL)
    OrderStatus fromApiStatus(String apiStatus);
}
```

- `ANY_REMAINING` covers every constant not explicitly listed (compile error if you forget a known one — good for exhaustiveness).
- `ANY_UNMAPPED` covers everything including the explicitly listed (used for reverse parsing).
- **Time/Space:** O(1) — generates a `switch`; no allocation.
- **Edge cases:** `null` input maps to `null` by default; `ANY_REMAINING` does **not** match `null`. Use this pattern to keep enum evolution from silently breaking serialization — a real win over manual `switch` statements that throw on new constants.

### Q9. [Theory] Explain MapStruct's null-handling strategies and when each is appropriate.

MapStruct exposes three orthogonal knobs, configurable per-mapping, per-mapper (`@Mapper`), or globally (`@MapperConfig`):

- **`nullValueCheckStrategy`** — `ON_IMPLICIT_CONVERSION` (default; null-checks only when a type conversion happens) vs `ALWAYS` (null-check every source before access). Use `ALWAYS` when sources may have null nested objects you don't want to NPE on.
- **`nullValuePropertyMappingStrategy`** — what to do when a source *property* is null during update: `SET_TO_NULL` (default), `IGNORE` (keep target's value — essential for PATCH), or `SET_TO_DEFAULT`.
- **`nullValueMappingStrategy`** — what to do when the source *argument itself* is null: `RETURN_NULL` (default) or `RETURN_DEFAULT` (return empty collection/object).

The interview "why": defaults are NPE-safe for create (whole-object) mappings, but for PATCH/merge you almost always need `IGNORE`. For collection-returning methods, `RETURN_DEFAULT` avoids forcing every caller to null-check the result.

### Q10. [Practical] How does MapStruct compare to ModelMapper, Dozer, and manual mapping in production? Give numbers and a recommendation.

```
                  | Resolution   | Reflection | Typical relative speed | Debuggable
------------------+--------------+------------+------------------------+-----------
Manual mapping    | compile      | none       | 1.0x  (baseline)       | yes
MapStruct         | compile      | none       | ~1.0–1.1x              | yes (real code)
ModelMapper       | runtime      | heavy      | ~10–30x slower         | hard
Dozer (legacy)    | runtime+XML  | heavy      | ~50–100x slower         | hard
```

Independent JMH benchmarks (e.g. the widely cited `mapstruct/mapstruct-examples` and community benchmark repos) consistently show MapStruct mapping millions of objects per second, within a hair of hand-written code, while ModelMapper and Dozer are an order of magnitude or more slower because they walk type metadata per call.

**Production recommendation:** Default to MapStruct for any high-throughput service (request mapping on a hot path, batch ETL, streaming). It costs a bit of build configuration and a learning curve on `@Mapping`, but you get compile-time safety and near-zero runtime cost. Reserve ModelMapper only for prototypes or genuinely dynamic mappings where the target shape isn't known at compile time. Dozer is effectively **deprecated/unmaintained** — do not start new projects on it.

### Q11. [Practical] How do you add MapStruct to a Spring Boot 3 + Lombok project, and what's the classic gotcha?

The gotcha: **annotation processor ordering**. Lombok must generate getters/setters *before* MapStruct reads them, or MapStruct sees no accessors. Add the `lombok-mapstruct-binding` processor and order them correctly.

```xml
<!-- Maven -->
<dependency>
  <groupId>org.mapstruct</groupId>
  <artifactId>mapstruct</artifactId>
  <version>1.6.3</version>   <!-- 1.6.x supports JDK 21 / Spring Boot 3 -->
</dependency>

<build><plugins><plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-compiler-plugin</artifactId>
  <configuration>
    <annotationProcessorPaths>
      <path><groupId>org.mapstruct</groupId>
            <artifactId>mapstruct-processor</artifactId>
            <version>1.6.3</version></path>
      <path><groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId><version>1.18.34</version></path>
      <path><groupId>org.projectlombok</groupId>
            <artifactId>lombok-mapstruct-binding</artifactId>
            <version>0.2.0</version></path>   <!-- the critical glue -->
    </annotationProcessorPaths>
  </configuration>
</plugin></plugins></build>
```

In Gradle, the equivalent is declaring all three under `annotationProcessor`. Symptom of getting it wrong: "Unmapped target property" warnings for *every* field, or an `*Impl` that copies nothing — because MapStruct compiled before Lombok injected accessors.

---

## 🟠 Advanced (8–12 yrs)

### Q12. [Theory] When would you use a `default` method, an `abstract` class mapper, vs a `@DecoratedWith` decorator? Compare the three extension mechanisms.

All three let you inject custom logic, but they differ in scope and intent:

```
default method        → small inline custom mapping inside the interface; MapStruct calls it
abstract class mapper → same, plus shared state/injected deps via fields; MapStruct subclasses it
@DecoratedWith        → wrap the WHOLE generated mapper; pre/post-process every call, add cross-cutting logic
```

- **Default methods** (`@Mapper interface` with `default ... { }`): best for a one-off conversion (e.g. `default String map(Money m) { return m.format(); }`). MapStruct automatically uses it when signatures match. Zero ceremony.
- **Abstract class mappers** (`@Mapper public abstract class`): use when you need injected collaborators (`@Autowired`/constructor-injected services) or shared helper methods, while still letting MapStruct generate the bulk. MapStruct generates a subclass implementing the abstract methods.
- **`@DecoratedWith`**: a true decorator — you extend an abstract decorator base, call `delegate.toDto(...)`, then add behavior (enrich, audit, post-process collections). Use for cross-cutting concerns spanning many mappings. Trade-off: the most indirection; reserve it for genuine wrap-the-whole-mapper needs, not single fields.

```java
@Mapper(componentModel = "spring")
@DecoratedWith(UserMapperDecorator.class)
public interface UserMapper {
    UserDto toDto(User u);
}

public abstract class UserMapperDecorator implements UserMapper {
    @Autowired @Qualifier("delegate") private UserMapper delegate;
    @Autowired private DisplayNameService names;

    @Override public UserDto toDto(User u) {
        UserDto dto = delegate.toDto(u);                 // generated mapping
        dto.setDisplayName(names.resolve(u));            // enrichment
        return dto;
    }
}
```

### Q13. [Practical] You have 40 mappers and want consistent global settings plus shared converters. How do you centralize configuration?

Use a shared `@MapperConfig` interface and reference it via `@Mapper(config = ...)`. This is the DRY mechanism for fleet-wide policy:

```java
@MapperConfig(
    componentModel = "spring",
    unmappedTargetPolicy = ReportingPolicy.ERROR,                 // fail the build on gaps
    nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS,
    injectionStrategy = InjectionStrategy.CONSTRUCTOR,
    uses = { CommonConverters.class }                              // shared Money/Date converters
)
public interface CentralMapperConfig {}

@Mapper(config = CentralMapperConfig.class)
public interface ProductMapper { ProductDto toDto(Product p); }
```

**Why `unmappedTargetPolicy = ERROR` in production:** it turns "I forgot to map the new `taxId` field I just added to the DTO" from a silent null in prod into a failing CI build. This is one of MapStruct's biggest organizational wins on large teams — the build *enforces* mapping completeness. Trade-off: it can be noisy during rapid prototyping; teams often run `WARN` locally and `ERROR` in CI, or use `@BeanMapping(ignoreUnmappedSourceProperties = ...)` for known asymmetries.

### Q14. [Theory] Explain `@Context`, `@BeforeMapping`/`@AfterMapping`, and `@ObjectFactory`. When are they the right tool?

These are MapStruct's lifecycle and dependency hooks:

- **`@Context`** — passes an object *through* the mapping graph without it being a mapped source (e.g. the current `Locale`, a `CycleAvoidingMappingContext`, or a persistence `EntityManager`). MapStruct threads it into every nested call automatically.
- **`@BeforeMapping` / `@AfterMapping`** — methods invoked before/after the generated body. Use `@AfterMapping` with `@MappingTarget` to post-process (set audit timestamps, derive computed fields, validate). They can be in the mapper, in a `uses` mapper, or take `@Context`.
- **`@ObjectFactory`** — controls *how* the target is instantiated instead of `new`. Essential when the target has no no-arg constructor, must come from a JPA `EntityManager.find` (to update a managed entity), or is built via a factory/builder.

```java
@AfterMapping
default void stampAudit(@MappingTarget AuditableDto dto, @Context Clock clock) {
    dto.setMappedAt(Instant.now(clock));
}

@ObjectFactory
public User resolve(UserDto dto, @Context EntityManager em) {
    return dto.getId() != null ? em.find(User.class, dto.getId()) : new User();
}
```

The `@ObjectFactory` + `EntityManager` pattern is the canonical way to merge into a managed entity so JPA dirty-checking persists changes — a frequent advanced interview scenario.

### Q15. [Coding] Map a recursive/bidirectional object graph (e.g. `Category` with parent and children) without infinite recursion or stack overflow.

**Problem:** `Category` has a `parent` and a `List<children>`, each child pointing back to the parent. A naive mapping recurses forever.

```java
@Mapper(componentModel = "spring")
public interface CategoryMapper {

    CategoryDto toDto(Category c, @Context CycleAvoidingMappingContext ctx);
    List<CategoryDto> toDtos(List<Category> c, @Context CycleAvoidingMappingContext ctx);
}

// Reusable context that caches already-mapped instances (from MapStruct examples repo)
public class CycleAvoidingMappingContext {
    private final Map<Object, Object> known = new IdentityHashMap<>();

    @BeforeMapping
    public <T> T getMapped(Object source, @TargetType Class<T> targetType) {
        return source == null ? null : targetType.cast(known.get(source)); // return cached if seen
    }

    @BeforeMapping
    public void store(Object source, @MappingTarget Object target) {
        known.put(source, target);   // remember before recursing into children
    }
}
```

- **How it breaks the cycle:** before mapping any node, `getMapped` checks the identity map; if the source was already mapped, it returns the existing target instead of recursing. `store` records the mapping *before* descending into children, so the back-reference resolves to the cached object.
- **Time complexity:** O(V + E) — each node and edge visited once.
- **Space complexity:** O(V) for the identity cache.
- **Edge cases:** must use `IdentityHashMap` (not `equals`-based) so two distinct nodes that are `.equals()` aren't conflated; `null` sources short-circuit; the context must be created **fresh per top-level call** (it's stateful) — never share one instance across requests.

### Q16. [Practical] A new microservice extracted from a monolith has subtly different field names and units (cents vs dollars). How do you build a robust anti-corruption mapping layer with MapStruct?

This is a classic Domain-Driven Design **anti-corruption layer (ACL)**. Treat MapStruct as the translation boundary so the external/legacy model never leaks into your domain:

```java
@Mapper(componentModel = "spring", uses = MoneyConverter.class)
public interface LegacyOrderAcl {

    @Mapping(target = "amount",   source = "totalCents")   // unit conversion via MoneyConverter
    @Mapping(target = "buyerId",  source = "custNo")        // renamed field
    @Mapping(target = "placedAt", source = "ts", dateFormat = "yyyyMMddHHmmss")
    Order fromLegacy(LegacyOrderRecord r);
}

@Component
public class MoneyConverter {
    public BigDecimal toDollars(long cents) {               // picked up by `uses`
        return BigDecimal.valueOf(cents).movePointLeft(2);
    }
}
```

**Approach → trade-offs → production reality:** Keep the legacy DTO classes in an `infrastructure`/`acl` package, never in `domain`. The mapper is the single chokepoint where naming, units, time zones, and null semantics are reconciled, with `unmappedTargetPolicy = ERROR` so any new legacy field forces a deliberate decision. I'd add **golden-file/property-based tests** around the ACL because unit bugs (cents vs dollars, ms vs seconds) are exactly the kind of silent data corruption that costs real money — a well-known class of incident in payments and billing systems. The trade-off is extra mapper code, but it buys an explicit, testable contract between bounded contexts.

### Q17. [Theory] How would you unit-test MapStruct mappers, and what should the tests actually assert?

Because the `*Impl` is real generated code, you test it like any class — no mocking framework needed for the mapping itself:

- For `componentModel = "default"`, instantiate via `Mappers.getMapper(X.class)`; for `spring`, use `@SpringBootTest`/a slice or just `new XMapperImpl()` (the impl has a public constructor when deps allow).
- **Assert the non-trivial transformations**, not the trivial 1:1 copies: renamed fields, formatting, unit conversions, enum value mappings, null/`@MappingTarget` IGNORE behavior, and collection element mapping.
- **Round-trip tests** (`toDto(toEntity(x))` ≈ `x`) catch asymmetric mappings.
- Crucially, treat `unmappedTargetPolicy = ERROR` and CI compilation as your *first* test — most mapping mistakes are caught before any unit test runs. Add architecture tests (ArchUnit) to enforce that domain code only talks to the world through mappers.

---

## 🔴 Expert (15+ yrs)

### Q18. [Theory] Walk through what happens inside `javac` when MapStruct processes your mappers, and the architectural implications of that model.

MapStruct's `MappingProcessor` implements `javax.annotation.processing.Processor`. During compilation, `javac` discovers it on the processor path and invokes it across one or more **rounds**. For each `@Mapper` type, MapStruct uses the `javax.lang.model` mirror API (`Elements`, `Types`) to inspect source/target types, builds a model of properties, resolves a mapping for each target property (direct, via built-in conversion, via a `uses`/method, via `expression`), and then emits a `*Impl` `.java` source via a FreeMarker template, which `javac` compiles in a later round.

```
javac round 1: read sources ──▶ MapStruct processor ──▶ generate UserMapperImpl.java
javac round 2: compile generated sources ──▶ (no new processors firing) ──▶ done
```

**Architectural implications:** (1) Errors are reported as compiler diagnostics tied to source locations — first-class IDE integration. (2) There is **zero runtime dependency footprint** beyond the tiny annotations jar; the generated code calls only your own getters/setters. (3) It composes with other processors (Lombok, immutables) but is sensitive to *ordering* and to processors that generate accessors. (4) Because everything is resolved against the *static* type model, MapStruct cannot map shapes only known at runtime — a deliberate design boundary, not a bug.

### Q19. [Practical] On a 1,200-class codebase, mappers have drifted into inconsistency and build times crept up. As the staff engineer, how do you remediate?

I'd run a staged remediation:

1. **Standardize policy via a single `@MapperConfig`** (component model, `unmappedTargetPolicy = ERROR`, injection strategy, null strategies) and migrate mappers to `config = ...`. This kills bespoke per-mapper settings that cause drift.
2. **Enforce with ArchUnit/Checkstyle**: every mapper must reference the shared config; DTOs may not appear in domain packages.
3. **Decompose god-mappers** using `uses` into focused, reusable converters; extract shared `MoneyConverter`/`DateConverter`.
4. **Build-time profiling**: MapStruct itself is fast, but enabling incremental annotation processing (Gradle), avoiding unnecessary `expression =` (which can't be optimized and hurts readability), and trimming overlapping processors usually recovers most time. Verify with `-Xlint` and the compiler's processing timing.
5. **Add round-trip and golden tests** to the worst offenders before refactoring, so behavior is pinned.

The leadership angle: I'd land this incrementally behind CI gates, not a big-bang rewrite, and pair it with a short internal "mapping conventions" doc so the standard sticks after I move on.

### Q20. [Theory] What are the security and correctness implications of careless mapping, and how does MapStruct help or hurt?

Mappers sit on the trust boundary between external DTOs and internal entities, so they are a real security surface:

- **Over-posting / mass assignment** — if you blindly map an inbound DTO onto an entity, an attacker may set fields they shouldn't (`role`, `isAdmin`, `accountBalance`). MapStruct *helps* because mappings are explicit and visible; with `unmappedTargetPolicy = ERROR` you must consciously decide each field, and you can `@Mapping(ignore = true)` sensitive ones. But it doesn't protect you automatically — review which fields a request-mapper writes.
- **Sensitive-data leakage outbound** — entity→DTO mappings can accidentally expose `passwordHash`, PII, or internal IDs. Explicitly `ignore` them and add tests asserting they're absent.
- **Type/format coercion** — `dateFormat`/`numberFormat` and `expression` can silently mis-parse hostile input; validate at the edge (Bean Validation) *before* mapping, not inside it.
- **Logging** — never `toString()` a freshly mapped DTO that may carry secrets into logs.

Net: MapStruct's explicitness and compile-time enforcement make secure mapping *easier to get right and audit* than reflection mappers that copy "everything that matches," which are inherently prone to over-posting.

### Q21. [Behavioral] Tell me about a time you chose a mapping strategy and had to defend it against a team that wanted reflection-based mapping for "speed of development."

**Situation:** Joining a payments platform, the team had standardized on ModelMapper because "you don't have to write mappers." Latency-sensitive endpoints were mapping large object graphs per request.

**Task:** I owned a p99 latency regression on the order-creation path.

**Action:** I profiled it and showed mapping was a measurable chunk of CPU due to reflection, and — more damaging — we'd had two production incidents from runtime mapping mismatches after refactors. I built a side-by-side: migrated one hot mapper to MapStruct, added a JMH benchmark (order-of-magnitude faster) and a deliberately-broken field rename that *failed the build* under MapStruct but *passed and broke prod* under ModelMapper. I framed it as risk reduction, not just speed.

**Result:** We adopted MapStruct via a shared `@MapperConfig`, kept ModelMapper only for an admin tool with truly dynamic shapes, and the latency regression closed. The lesson I emphasize: "speed of development" must include the cost of *runtime* failures and debugging, not just lines of mapper code — and compile-time safety usually wins that math on systems that handle money.

### Q22. [Practical] How do you handle MapStruct with Java records, immutables, and builder-based targets (modern Java 17/21 codebases)?

MapStruct 1.5+ supports immutable targets natively:

- **Java records** — fully supported as both source and target; MapStruct maps via the canonical constructor, matching component names. No setters required.
- **Builders** — if the target exposes a `builder()` (Lombok `@Builder`, Immutables, AutoValue, protobuf), MapStruct detects and uses it automatically via its `BuilderProvider` SPI; configurable with `@Mapper(builder = @Builder(...))` or globally.
- **Constructor injection of properties** — for any type with a single suitable constructor, MapStruct passes mapped values as constructor args.

```java
public record OrderDto(Long id, BigDecimal total, String status) {}

@Mapper(componentModel = "spring")
public interface OrderMapper {
    @Mapping(target = "status", source = "state")   // works against the record constructor
    OrderDto toDto(Order order);
}
```

**Version note:** ensure MapStruct ≥ 1.5.x (1.6.x for best JDK 21 support) — older versions predate record/builder handling. With records you lose `@MappingTarget` update-in-place (records are immutable), so PATCH semantics must rebuild the record or use a mutable intermediate. This is a common gotcha when teams migrate DTOs to records expecting in-place merge to keep working.

---

## 🧩 Extended Questions — Set 1: Deep Theory & Internals

### 🟢 Basic — extended

#### Q23. [Theory] What are MapStruct's "built-in conversions," and why do they matter for the mental model of how mapping is resolved?

Built-in conversions are the set of type transformations MapStruct knows how to perform **without any method or `@Mapping` from you**. They cover the conversions that are unambiguous and lossless-by-convention: all the numeric wrapper/primitive widenings and the standard `String`↔number, `String`↔`Date`/`LocalDate`/`LocalDateTime`, `String`↔enum, `String`↔`UUID`/`URL`, `BigDecimal`/`BigInteger`↔primitives, `Calendar`↔`Date`, JAXB `XMLGregorianCalendar`, and `String`↔`Character`. When source and target property differ in type but a built-in conversion applies, MapStruct silently inserts it.

The reason this matters in an interview is that built-in conversions sit at a specific rung in MapStruct's **mapping-method selection algorithm**. For each target property MapStruct tries, in order: (1) a direct assignment if types are assignable, (2) a built-in conversion, (3) a mapping method (yours, or one from a `uses` mapper), (4) a complex/nested generated mapping, and finally an `expression`/`constant`/`defaultValue` if you specified one. Understanding this precedence explains why, for example, you don't need to write a `String toString(LocalDate)` method — MapStruct already has one — but you *do* need `dateFormat` to control how it renders.

```java
public class Src { Long count; String createdOn; }   // String holding a date
public class Dst { String count; LocalDate createdOn; }

@Mapper interface M {
    @Mapping(target = "createdOn", source = "createdOn", dateFormat = "yyyy-MM-dd")
    Dst map(Src s);   // count: Long->String built-in; createdOn: String->LocalDate built-in
}
```

The trade-off to flag: built-in conversions are convenient but can hide intent (e.g. an implicit `Long`→`String` may be a modeling smell). Some teams enable strict `MappingControl` to *forbid* implicit conversions so every cross-type mapping is explicit and reviewed.

#### Q24. [Theory] What exactly does `Mappers.getMapper(X.class)` do under the hood, and why is it discouraged with the Spring component model?

`Mappers.getMapper(Class)` is the factory for the **`default` component model**. Internally it resolves the implementation class by appending the configured suffix (default `Impl`) to the mapper's fully qualified name, loads it via the same `ClassLoader` as the mapper interface using `ServiceLoader`/reflection lookup, and returns a (cached) singleton instance constructed via its no-arg constructor. It is essentially a tiny service-locator: no dependency injection container is involved, so any collaborators the mapper needs (other mappers via `uses`, injected services) must themselves be instantiable without a container.

That last point is exactly why mixing `Mappers.getMapper` with `componentModel = "spring"` is a mistake. When you set `componentModel = "spring"`, the generated `*Impl` declares its dependencies as Spring-injected fields/constructor args (`@Autowired` or constructor injection). If you then call `Mappers.getMapper(UserMapper.class)`, you bypass Spring entirely — the impl is created outside the container, its injected `uses` mappers and services are `null`, and you get NPEs the moment a delegated sub-mapping runs.

```java
// default model — Mappers.getMapper is correct
@Mapper public interface PlainMapper { Dto toDto(Entity e); }
PlainMapper m = Mappers.getMapper(PlainMapper.class);   // singleton, no DI

// spring model — DO NOT use Mappers.getMapper; inject it instead
@Mapper(componentModel = "spring") public interface SpringMapper { ... }
```

The deeper architectural lesson: the component model is a code-generation switch that changes *who owns instantiation*. Pick one ownership model per mapper and stay consistent — keep the `INSTANCE` field only for `default`-model mappers (typically library code with no Spring context).

#### Q25. [Theory] Why is MapStruct's output a separate generated `.java` source file rather than bytecode woven into your interface, and what does that buy you?

MapStruct is a standard **JSR-269 annotation processor**, and the JSR-269 contract only allows processors to *create new* source/class files via the `Filer` API — it explicitly forbids modifying existing source files. So MapStruct cannot weave code into your `@Mapper` interface; it must emit a new top-level type (`UserMapperImpl.java`) into `generated-sources`. This is a deliberate constraint of the processing model, not a MapStruct design quirk, and it stands in contrast to bytecode-manipulation tools (AspectJ weaving, Lombok's controversial AST hacking, ByteBuddy agents) that mutate compiled output.

The practical payoff of generating readable source is substantial. The generated file is plain Java you can open, set breakpoints in, step through in a debugger, and read in a stack trace — there is no "magic" frame. It is checked by `javac` like any other source, so type errors in a custom `expression` surface as ordinary compile errors with line numbers. It integrates with coverage tools, and the diff is reviewable if you choose to commit generated sources (most teams don't). Because nothing happens at runtime, there is no startup cost, no agent, and no reflective warm-up.

```
src/main/java/UserMapper.java   (you, the @Mapper interface)
        │  annotation processing (javac, Filer.createSourceFile)
        ▼
target/generated-sources/annotations/UserMapperImpl.java   (MapStruct emits)
        │  same javac invocation, later round
        ▼
target/classes/UserMapperImpl.class   (ordinary bytecode)
```

The trade-off is that generated sources can clutter the build tree and confuse IDEs that aren't configured to mark `generated-sources` as a source root — a frequent "my IDE shows red but Maven builds fine" symptom. The fix is configuring the IDE/build to recognize the generated folder, which the MapStruct/Spring archetypes do automatically.

#### Q26. [Theory] What is the difference between `unmappedTargetPolicy` and `unmappedSourcePolicy`, and why do they default differently?

`unmappedTargetPolicy` controls what happens when a **target** property has no mapping source — i.e. a field on the object you're producing that nothing fills. Its default is `WARN`. `unmappedSourcePolicy` (added in MapStruct 1.3) controls the opposite: a **source** property that is never read into the target. Its default is `IGNORE` — completely silent.

The asymmetry is intentional and worth being able to justify. An unmapped *target* is usually a latent bug: you added a `taxId` field to your DTO and forgot to map it, so it silently stays `null` in production. That deserves at least a warning (and `ERROR` in CI). An unmapped *source*, by contrast, is frequently *intentional and benign* — entities routinely carry more fields than a given DTO needs (audit columns, version, lazy associations, internal flags). Defaulting `unmappedSourcePolicy` to anything louder than `IGNORE` would flood every normal projection mapping with noise.

```java
@Mapper(unmappedTargetPolicy = ReportingPolicy.ERROR,    // fail build on forgotten targets
        unmappedSourcePolicy = ReportingPolicy.WARN)     // optional: notice dropped sources
public interface AccountMapper { AccountDto toDto(Account a); }
```

When *would* you raise `unmappedSourcePolicy`? In an anti-corruption layer or a "lossless audit" mapping where dropping a source field silently could lose data — there, surfacing unread sources catches the case where the upstream model gained a field you should be persisting. Otherwise leave it `IGNORE`. The interviewer is probing whether you understand that "completeness" means different things for the producing side versus the consuming side of a mapping.

### 🟡 Intermediate — extended

#### Q27. [Theory] Explain MapStruct's mapping-method *selection* algorithm when several candidate methods could apply, and how ties are broken.

When MapStruct needs to map a value of type `S` to type `T`, it searches all available mapping methods (in the mapper, inherited, and from every `uses` mapper) for candidates whose parameter type is assignable from `S` and whose return type is assignable to `T`. If exactly one candidate matches it is used. The interesting cases are zero matches and multiple matches.

With **multiple matches**, MapStruct applies a most-specific-type heuristic: it prefers the candidate whose parameter type is the most specific (closest in the type hierarchy) that still accepts `S`, and similarly the most specific return type. If after that more than one candidate remains equally specific, MapStruct does **not** guess — it raises an `"Ambiguous mapping methods found"` compile error and forces you to disambiguate. The disambiguation mechanism is `@Named` on the method plus `qualifiedByName` on the `@Mapping`, or the type-based `@Qualifier`-style custom annotation with `qualifiedBy`.

```java
@Mapper interface PriceMapper {
    @Named("net")   BigDecimal net(Money m)   { return m.net(); }
    @Named("gross") BigDecimal gross(Money m)  { return m.gross(); }

    // Without qualifiedByName this is an AMBIGUOUS error: two Money->BigDecimal methods
    @Mapping(target = "displayPrice", source = "price", qualifiedByName = "gross")
    Receipt toReceipt(Order order);
}
```

The "why" behind the compile-time failure is core to MapStruct's philosophy: silently picking one of two equally valid mappings would reintroduce exactly the runtime-surprise problem MapStruct exists to eliminate. Forcing an explicit qualifier keeps the resolution deterministic and auditable. A subtle corollary: adding an innocent new `@Named`-less helper method to a `uses` mapper can suddenly make a previously-unambiguous mapping ambiguous across the whole project — a reason to qualify *all* convertible-type helpers defensively in large codebases.

#### Q28. [Theory] What is a "presence check" method, and how does it change the generated null/absence logic compared with a plain getter?

A presence check is MapStruct's support for source types that distinguish "property is absent" from "property is present but null." For a source property `foo`, in addition to the getter `getFoo()`, MapStruct looks for a companion method following a naming convention — `hasFoo()` (boolean presence check) — and, since 1.3, will also honor wrapper-style "presence" via `Optional`-ish accessors and protobuf/`JsonNullable` patterns. When a presence check exists, the generated code guards the assignment with `if (source.hasFoo())` instead of (or in addition to) a null check.

This matters most for **PATCH/merge semantics**, where the three-valued logic (absent / present-null / present-value) cannot be expressed by null alone. With a plain getter, MapStruct can only ask "is it null?" — so it cannot tell a client who *omitted* `email` from one who explicitly sent `"email": null` to clear it. A presence check (commonly via `JsonNullable<T>` with its `isPresent()`) restores that distinction.

```java
public class PatchDto {
    private JsonNullable<String> email = JsonNullable.undefined();
    public JsonNullable<String> getEmail() { return email; }
    // MapStruct recognizes JsonNullable presence -> generates isPresent() guard
}

@Mapper(componentModel = "spring", nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface UserPatchMapper {
    void patch(PatchDto dto, @MappingTarget User entity);
}
```

The generated guard then becomes roughly `if (dto.getEmail().isPresent()) entity.setEmail(dto.getEmail().orElse(null));`, which correctly applies an explicit null while ignoring an omitted field. The trade-off is API/model complexity — you have to adopt a presence-aware wrapper type throughout the DTO — but for true RFC-7386-style JSON Merge Patch it is the only way to get the semantics right, and `nullValuePropertyMappingStrategy = IGNORE` alone is insufficient because it conflates absent with null.

#### Q29. [Theory] Compare `@InheritConfiguration` and `@InheritInverseConfiguration`. What problem does each solve and how does MapStruct match the configuration to inherit?

Both annotations exist to eliminate duplicated `@Mapping` declarations between closely related methods. `@InheritConfiguration` is for **forward** reuse: a second method with the *same* mapping direction (e.g. an update method) reuses the `@Mapping` config of a create method. `@InheritInverseConfiguration` is for the **reverse** direction: a `toEntity` method automatically inverts the `source`/`target` pairs you declared on the corresponding `toDto` method, so a renamed field is configured once.

MapStruct resolves *which* method's config to inherit by matching method signatures. For `@InheritConfiguration`, it looks for a method in the same mapper whose source/target types are assignable and applies its configuration; if more than one candidate exists you must disambiguate with the `name` attribute. For `@InheritInverseConfiguration`, it finds the method whose source and target types are swapped relative to the annotated one. Only *invertible* mappings are inherited — `expression`, `constant`, and one-directional transforms can't be mechanically reversed and are skipped (you may need to override them explicitly).

```java
@Mapper(componentModel = "spring")
public interface CarMapper {
    @Mapping(target = "seatCount", source = "numberOfSeats")
    @Mapping(target = "make",      source = "manufacturer")
    CarDto toDto(Car car);

    @InheritInverseConfiguration            // seatCount<-numberOfSeats etc. auto-reversed
    Car toEntity(CarDto dto);

    @InheritConfiguration(name = "toEntity") // reuse the (now-inverse) config for the updater
    void update(CarDto dto, @MappingTarget Car car);
}
```

The value is DRYness and a single source of truth for field renames; the failure mode is over-reliance on inversion for mappings that *aren't* symmetric (lossy conversions, computed fields), where the inherited config silently does the wrong thing. The seasoned take is: use inheritance for genuinely symmetric DTO↔entity pairs, and write explicit `@Mapping`s the moment the two directions diverge.

#### Q30. [Practical] How does MapStruct handle Java `Stream`, arrays, and `Map`, and what are the semantic subtleties versus `List`/`Set`?

MapStruct treats iterables, `Stream`, arrays, and `Map` as four related-but-distinct mapping families. For `Iterable`/`Collection`/`List`/`Set` it generates a loop that creates a new target collection and maps each element using an element mapping method (yours or built-in). For **`Stream`**, it generates a `.map(elementMapper).collect(...)` pipeline; you can map `Stream`→`Stream`, or `Stream`↔`List`/`Set`/array, which is handy at functional boundaries. For **arrays**, it allocates a target array of the right component type and maps element by element, supporting array↔collection in both directions. For **`Map`**, it maps keys and values independently — declaring (or relying on built-in) conversions for the key type and value type separately.

The subtleties that trip people up are about *which concrete collection type* you get and how nulls/empties behave. MapStruct picks a sensible default implementation for an interface target (`ArrayList` for `List`, `LinkedHashSet` for `Set`, `LinkedHashMap` for `Map` — preserving insertion order), but if you need a specific type you should declare it as the return type. Null handling is governed by `nullValueMappingStrategy`: by default a null source collection yields a null target, but `RETURN_DEFAULT` yields an empty collection, which is usually what callers want so they can iterate without a null check.

```java
@Mapper(componentModel = "spring",
        nullValueMappingStrategy = NullValueMappingStrategy.RETURN_DEFAULT)
public interface CatalogMapper {
    List<ProductDto>      toList(List<Product> p);
    Set<ProductDto>       toSet(Stream<Product> p);          // Stream -> Set
    ProductDto[]          toArray(List<Product> p);          // List -> array
    Map<String, PriceDto> toPriceMap(Map<String, Price> m);  // keys/values mapped separately
}
```

A `Map` gotcha worth naming: with `@MapMapping` you control key/value formatting, but if the key type changes you must ensure key uniqueness is preserved — collapsing two distinct source keys to one target key silently loses entries, and MapStruct won't warn you. For `Stream` targets remember the consumer owns terminal operation cost; returning a `Stream` from a mapper can surprise callers who expect a materialized collection.

#### Q31. [Theory] What is the `uses` resolution order, and how does MapStruct decide between a built-in conversion and a method from a `uses` mapper when both could apply?

`uses` registers other mappers/converters as candidate sources of mapping methods, but it does not change MapStruct's fundamental *precedence* of strategies. For a given source→target type pair, MapStruct still evaluates strategies in this order: (1) direct assignment, (2) **built-in conversion**, (3) **mapping method** (whether defined in the current mapper or pulled in via `uses`), (4) generated complex mapping. A method from `uses` lives at rung 3 — so a built-in conversion *wins* over a `uses` method for the same type pair, which surprises people who add a custom `String`→`LocalDate` converter and find MapStruct ignoring it in favor of its built-in.

To force your custom converter to be used over a built-in, you have to make MapStruct *choose* it explicitly: give it a `@Named` qualifier and reference it via `qualifiedByName`, or use `@Mapping(dateFormat=...)`-style configuration only when you actually want the built-in. Among multiple `uses` mappers, ordering of the array is **not** a tie-break — selection is by type specificity (Q27), and genuine ties are a compile error, not a "first one wins."

```java
@Mapper(uses = StrictDateConverter.class)
public interface ReportMapper {
    // Built-in String->LocalDate would normally win; force the custom converter:
    @Mapping(target = "asOf", source = "asOfRaw", qualifiedByName = "strictIso")
    ReportDto toDto(Report r);
}
@Component class StrictDateConverter {
    @Named("strictIso") LocalDate parse(String s) { return LocalDate.parse(s); } // throws on bad input
}
```

The design intent is predictability: built-ins are deterministic and well-defined, so MapStruct prefers them unless you opt out, rather than letting an incidental method on a `uses` mapper silently shadow them. The expert habit is to treat any custom converter that overlaps a built-in type pair as something you must qualify explicitly, and to document why — otherwise a future refactor that drops the qualifier silently reverts to the built-in behavior.

### 🟠 Advanced — extended

#### Q32. [Theory] Explain MapStruct's `BuilderProvider` / accessor-naming SPI. How does MapStruct stay decoupled from Lombok, Immutables, AutoValue, and protobuf?

MapStruct exposes a small set of **Service Provider Interfaces (SPIs)** so it can adapt to non-standard bean conventions without hard-coding knowledge of any specific library. The two most important are the `BuilderProvider` SPI (`org.mapstruct.ap.spi.BuilderProvider`) and the `AccessorNamingStrategy` SPI (`org.mapstruct.ap.spi.AccessorNamingStrategy`, often subclassed as `DefaultAccessorNamingStrategy`). The `BuilderProvider` tells MapStruct, for a given target type, whether it has a builder and what the builder-creation method (`builder()`), the build method (`build()`), and the builder type are. The `AccessorNamingStrategy` tells MapStruct how to recognize getters, setters, adders, and presence-check methods when they don't follow the JavaBeans `get/set` convention.

This SPI layering is *why* MapStruct works out-of-the-box with Lombok `@Builder`, Immutables, AutoValue, and even fluent/`with`-style accessors: MapStruct ships a default `BuilderProvider` that recognizes the common `builder()`/`build()` shape, and you can register a custom one on the annotation processor path via `META-INF/services` to teach it any other convention (protobuf's `newBuilder()`/`build()` and `addX()` repeated-field adders, for instance, are handled by the protobuf-specific support or a custom strategy). MapStruct never depends on Lombok or protobuf at compile or runtime — it only depends on the *shape* exposed through these SPIs.

```
META-INF/services/org.mapstruct.ap.spi.AccessorNamingStrategy
   └─ com.acme.FluentAccessorNamingStrategy   (recognizes withX()/x() instead of setX()/getX())
```

```java
public class FluentAccessorNamingStrategy extends DefaultAccessorNamingStrategy {
    @Override public boolean isSetterMethod(ExecutableElement m) {
        return m.getSimpleName().toString().startsWith("with");   // builder-style setter
    }
}
```

The architectural lesson for a staff-level answer: MapStruct's extensibility is *static and processor-time*, delivered through `ServiceLoader`-discovered SPIs rather than runtime plugins. This keeps the generated code clean and dependency-free while still adapting to almost any bean style, and it's the mechanism you'd reach for when integrating a homegrown immutable-object framework rather than abandoning MapStruct.

#### Q33. [Theory] How does MapStruct behave across a multi-module Maven/Gradle build, and what are the constraints on mappers and `uses` spanning module boundaries?

A MapStruct mapper is processed when *its own* module is compiled, and the generated `*Impl` is compiled into that module's artifact. This has concrete consequences for multi-module projects. A mapper in module B can `uses` a mapper from module A as long as A is a compile dependency of B and A's mapper *interface* is on B's compile classpath — MapStruct reads the type from the classpath (via the `javax.lang.model` element API backed by classfiles), not from A's source. The generated `AImpl` from module A is bundled in A's jar, and B's generated `BImpl` will reference it.

The important constraint is that MapStruct can only inspect what's visible on the **annotation-processing classpath** at the time B is compiled. If A's mapper relies on something only present in A's *source* (not its compiled artifact), or if A's `*Impl` wasn't generated because A's build didn't run the processor, B's mapping will fail or fall back. There's also a packaging subtlety: the tiny `org.mapstruct:mapstruct` annotations jar must be a normal (compile-scope) dependency in every module that *defines* mappers, while the `mapstruct-processor` belongs on the `annotationProcessorPaths` / `annotationProcessor` configuration only — putting the processor on the regular classpath is a common misconfiguration that can cause it to run in unexpected modules or not at all.

```
module-common   : defines MoneyConverter (compiled, in jar)
module-orders    : @Mapper(uses = MoneyConverter.class)   // requires module-common as a dependency
                   javac sees MoneyConverter from classpath -> OrderMapperImpl calls it
```

A practical pitfall: with the `spring` component model across modules, the generated impls in different jars must all be component-scanned by the application module, and a `uses` mapper from another module must itself be a Spring bean there. For incremental builds, changing a mapper interface in module A forces recompilation of downstream modules that `uses` it — so deep `uses` graphs across many modules can lengthen incremental build times, which is an argument for keeping shared converters in a small, stable, low-churn module.

#### Q34. [Theory] What is `@SubclassMapping` and the `SubclassExhaustiveStrategy`, and how does MapStruct handle polymorphic/inheritance hierarchies?

By default MapStruct maps based on the *declared* (static) type of a parameter, so if you map a `Payment` reference that actually holds a `CardPayment` or `BankTransfer`, you'd lose the subtype-specific fields — MapStruct only sees `Payment`. `@SubclassMapping` (introduced in MapStruct 1.5) solves this by letting you declare, on a mapping method, that specific source subclasses should be routed to specific target subclasses. MapStruct then generates an `instanceof`-based dispatch that picks the right sub-mapping at runtime.

```java
@Mapper(componentModel = "spring")
public interface PaymentMapper {
    @SubclassMapping(source = CardPayment.class,  target = CardPaymentDto.class)
    @SubclassMapping(source = BankTransfer.class, target = BankTransferDto.class)
    PaymentDto toDto(Payment payment);

    CardPaymentDto toDto(CardPayment p);
    BankTransferDto toDto(BankTransfer p);
}
```

The generated `toDto(Payment)` becomes roughly an `if (payment instanceof CardPayment) return toDto((CardPayment) payment); else if (...) ...`. The `subclassExhaustiveStrategy` (configurable on `@Mapper`/`@MapperConfig`) governs what happens for a subtype you *didn't* list: `COMPILE_ERROR` makes MapStruct fail the build unless every known subclass is covered (great for sealed hierarchies where you want exhaustiveness guarantees), while `RUNTIME_EXCEPTION` lets it compile and throws `IllegalArgumentException` at runtime for an unmapped subtype. With Java 17 **sealed classes**, `COMPILE_ERROR` plus the closed permits-list gives you true compile-time exhaustiveness — analogous to an exhaustive `switch`.

The trade-off versus a hand-rolled visitor or double-dispatch: `@SubclassMapping` is concise and keeps the dispatch logic generated and consistent, but it relies on runtime `instanceof`, so ordering matters (most-specific subtype first, which MapStruct handles) and it can't see types unknown at compile time. For a stable, sealed domain hierarchy it's the idiomatic choice; for an open plugin-style hierarchy you may still need a registry/visitor pattern outside MapStruct.

#### Q35. [Theory] What is `@MappingControl` / `MappingControl`, and how does it let you forbid implicit conversions or deep cloning at a fine grain?

`MappingControl` is a meta-level mechanism (stable since 1.4/1.5) that lets you *restrict which mapping strategies MapStruct is allowed to use* for a given mapping, mapper, or globally. The built-in control annotations are `@DeepClone` (force value mappings to create deep copies rather than share references), and the negative controls `@NoComplexMapping`, and the ability to compose your own by combining the underlying `MappingControl.Use` enum values (`DIRECT`, `MAPPING_METHOD`, `BUILT_IN_CONVERSION`, `COMPLEX_MAPPING`). You attach a control via the `mappingControl` attribute on `@Mapper`, `@MapperConfig`, `@BeanMapping`, or `@Mapping`.

The headline use case is **forbidding implicit type conversions**. By default MapStruct happily inserts a `Long`→`String` or `int`→`long` built-in conversion (Q23). On a strict codebase you may consider those implicit coercions a code smell that hides intent or risks precision/locale bugs. By applying a control that omits `BUILT_IN_CONVERSION`, you make MapStruct *refuse* to silently convert — forcing a compile error that you resolve by writing an explicit, reviewable converter method.

```java
@Retention(RetentionPolicy.CLASS)
@MappingControl(MappingControl.Use.MAPPING_METHOD)   // allow only explicit mapping methods
@MappingControl(MappingControl.Use.DIRECT)           // and direct assignment
public @interface NoImplicitConversions {}           // (BUILT_IN_CONVERSION deliberately omitted)

@Mapper(mappingControl = NoImplicitConversions.class)
public interface StrictMapper {
    // Long id -> String id now FAILS to compile instead of silently converting
    Dto toDto(Entity e);
}
```

The `@DeepClone` control is the other practical case: applied to a clone-style mapping it tells MapStruct to recurse and copy nested objects/collections instead of reusing references, giving you a defensive deep copy without hand-writing it. The trade-off with all `MappingControl` usage is that it makes the build stricter and can surface a flurry of compile errors when first introduced — but that is precisely the point: it converts implicit, easy-to-miss behavior into explicit decisions, which is exactly the safety posture you want on financial or PII-handling mappers.

#### Q36. [Theory] How does MapStruct decide between calling a setter and using a "target accessor as collection adder," and what is the no-setter collection pattern?

For collection-valued target properties, JPA-style entities frequently expose a getter but *no setter* (the collection is initialized in the field and managed internally — a common Hibernate pattern to keep the same persistent collection instance). MapStruct accommodates this with **adder methods** and **getter-based population**. Its accessor-resolution logic, per the `AccessorNamingStrategy`, looks for, in order: a setter (`setItems`), then an **adder** (`addItem`, singular form), then falls back to using the **getter** to obtain the existing collection and add into it.

This matters because naively requiring a setter would break the standard JPA bidirectional-association pattern. When MapStruct finds an adder, it generates a loop calling `target.addItem(element)` per element — which is exactly what you want for associations where `addItem` also sets the back-reference (`item.setParent(this)`), keeping both sides of the relationship consistent. When there's only a getter, MapStruct generates `target.getItems().clear()` (or addAll) into the existing collection instance, preserving Hibernate's tracked collection.

```java
public class Order {                 // JPA entity, no setItems()
    private final List<Item> items = new ArrayList<>();
    public List<Item> getItems() { return items; }
    public void addItem(Item i) { items.add(i); i.setOrder(this); }  // adder maintains back-ref
}

@Mapper(componentModel = "spring")
public interface OrderMapper {
    // MapStruct uses addItem() per element -> back-references stay consistent
    Order toEntity(OrderDto dto);
    Item  toItem(ItemDto dto);
}
```

The subtlety to flag: relying on the getter-population fallback mutates the *existing* target collection, which is the correct behavior for managed entities but can surprise you in an update scenario where you expected replacement. The `CollectionMappingStrategy` setting (`ACCESSOR_ONLY`, `SETTER_PREFERRED`, `ADDER_PREFERRED`, `TARGET_IMMUTABLE`) gives you explicit control over this precedence — `ADDER_PREFERRED` is the right default for rich JPA domains, while `TARGET_IMMUTABLE` is for builder/record targets where you must construct the whole collection up front.

#### Q37. [Practical] How would you verify and debug *what MapStruct actually generated*, and what compiler/processor options exist to influence generation?

The first and most direct technique is to **read the generated source**: it lands in `target/generated-sources/annotations/` (Maven) or `build/generated/sources/annotationProcessor/` (Gradle). Opening `XMapperImpl.java` answers almost every "why is this field null / why isn't my converter called" question instantly, because the resolution decisions are visible as concrete get/set calls. Set a breakpoint in it like any other class. This is the single highest-leverage debugging habit with MapStruct and a strong signal in an interview.

MapStruct exposes several **annotation-processor options** passed via `-A` (in `<compilerArgs>`/`compilerArgument` for Maven, `options.compilerArgs` for Gradle) that change generation and aid debugging:

```xml
<compilerArgs>
  <arg>-Amapstruct.verbose=true</arg>                       <!-- log mapping decisions -->
  <arg>-Amapstruct.suppressGeneratorTimestamp=true</arg>    <!-- stable diffs / reproducible builds -->
  <arg>-Amapstruct.suppressGeneratorVersionInfoComment=true</arg>
  <arg>-Amapstruct.defaultComponentModel=spring</arg>       <!-- global default w/o per-mapper attr -->
  <arg>-Amapstruct.defaultInjectionStrategy=constructor</arg>
  <arg>-Amapstruct.unmappedTargetPolicy=ERROR</arg>         <!-- enforce globally from the build -->
  <arg>-Amapstruct.disableBuilders=true</arg>               <!-- ignore builders, use setters -->
</compilerArgs>
```

`-Amapstruct.verbose=true` makes the processor emit notes about how it resolved each property — invaluable when a built-in conversion is shadowing a custom one, or when a `uses` method isn't being picked up. `suppressGeneratorTimestamp` is important for **reproducible builds** and clean diffs if you commit generated sources or use build caching, since the default timestamp comment otherwise changes every build and defeats caching. The build-level `-Amapstruct.*` policy options are how you enforce conventions fleet-wide without editing every `@Mapper`. For incremental-build correctness in Gradle, ensure the processor is on the `annotationProcessor` configuration so Gradle tracks it as an input and invalidates correctly — otherwise stale `*Impl` files cause baffling "I changed the mapper but nothing happened" symptoms.

#### Q38. [Theory] Why can't MapStruct map generic type variables or runtime-only shapes, and what does that reveal about its design boundary versus reflection mappers?

MapStruct resolves every mapping against the **static type model** available to the annotation processor at compile time, through `javax.lang.model`'s `TypeMirror`/`DeclaredType` API. A method like `<T> T map(Object src)` or a property typed as a bare type variable `T` gives the processor no concrete properties to inspect — there is no class with getters/setters to walk — so MapStruct cannot generate field-by-field code. The same is true for genuinely dynamic shapes (a `Map<String,Object>` standing in for an arbitrary JSON document, or a `JsonNode`): the *structure* isn't known until runtime, and MapStruct only emits code for structure it can see statically.

This is the precise line that separates MapStruct from reflection-based mappers, and being able to articulate it is what distinguishes a deep answer. ModelMapper/Dozer resolve property graphs *at runtime* by reflecting on actual object instances, which is exactly why they *can* handle shapes unknown at compile time — and exactly why they pay reflection cost, lose compile-time safety, and fail at runtime on mismatches. MapStruct made the opposite trade deliberately: it gives up dynamic flexibility to gain zero-overhead, statically-verified code.

```java
// Works: concrete types, statically known properties
OrderDto toDto(Order order);

// Cannot generate: no static structure to map
// <T> T map(Object source);              // type variable -> no properties
// Foo fromJson(Map<String,Object> json); // dynamic shape -> nothing to inspect at compile time
```

The pragmatic consequence for architecture: where you genuinely need runtime-dynamic mapping (a generic admin tool, a schema-driven ETL stage, untyped JSON transformation), MapStruct is the wrong tool and you should reach for Jackson `ObjectMapper`/`JsonNode` traversal, a reflection mapper, or hand-written code — and *isolate* that dynamic boundary so the rest of the system keeps MapStruct's static guarantees. Recognizing that "MapStruct can't do this *by design*, here's the right tool" is a more senior answer than trying to force generics through it.

### 🔴 Expert — extended

#### Q39. [Theory] Trace MapStruct's behavior across multiple annotation-processing *rounds* and explain how it coexists with other processors that generate types it depends on.

JSR-269 processing is **round-based**: `javac` runs an initial round over the original sources, and if any processor uses the `Filer` to generate new source files, those generated sources trigger *additional* rounds in which processors run again over the newly created types, continuing until a round produces no new files (the "final round"). MapStruct's `MappingProcessor` participates in this loop. Critically, in a given round MapStruct can only resolve mappings against types whose elements are *already available* — fully generated and on the element path.

This is the mechanistic root of the famous Lombok ordering problem. Lombok and MapStruct are *both* processors in the same `javac` invocation. MapStruct needs the getters/setters that Lombok injects, but Lombok manipulates the AST during its own processing. If MapStruct runs and resolves a mapper *before* Lombok has contributed accessors to the source types, MapStruct sees a bean with no properties and generates an empty `*Impl`. The `lombok-mapstruct-binding` artifact exists specifically to coordinate this — it signals MapStruct to defer until Lombok's accessor generation is visible, rather than relying on fragile processor-ordering luck.

```
Round 1: javac reads sources
         ├─ Lombok contributes getters/setters (AST)
         └─ MapStruct must see those accessors before resolving  ← binding coordinates this
Round 2: javac compiles MapStruct's generated *Impl.java
Round N (final): no new files generated → processing ends
```

The general principle, beyond Lombok: when MapStruct maps a target type that is itself *generated* by another processor (Immutables' `ImmutableFoo`, AutoValue's `AutoValue_Foo`, generated protobuf classes), that generated type must exist on the classpath/element path before MapStruct resolves the mapping. In multi-round scenarios this usually "just works" because the generated type appears in an earlier round, but the binding/ordering machinery is what guarantees it. The expert framing: processor *interaction* is a graph of producer/consumer dependencies resolved over rounds, and most "MapStruct generated nothing / mapped nothing" incidents are really *round-ordering* bugs, not MapStruct bugs.

#### Q40. [Theory] What changed across major MapStruct versions (1.3 → 1.4 → 1.5 → 1.6), and why does version selection matter for a modern stack?

Each minor line added capabilities that affect whether a given codebase pattern even compiles, so version awareness is a real interview signal. A condensed history:

| Version | Key additions | Why it matters |
|---------|---------------|----------------|
| **1.3** | `unmappedSourcePolicy`, constructor injection (`injectionStrategy`), presence-check / `JsonNullable` support | Enabled clean Spring constructor injection and PATCH presence semantics |
| **1.4** | Builder support hardening, `MappingControl` foundation, lifecycle improvements | Builders/immutables become first-class; fine-grained strategy control |
| **1.5** | **Records** as source/target, `@SubclassMapping` (polymorphism), `subclassExhaustiveStrategy`, conditional mapping (`@Condition`), `jakarta` component model | Java 17 records + sealed hierarchies + Jakarta EE 9 / Spring Boot 3 |
| **1.6** | JDK 21 support, refined builder/record handling, additional `@ConditionalMapping`/qualifier ergonomics, performance and toolchain updates | Required for current LTS (JDK 21) and latest Spring Boot 3.x |

The "why it matters" is concrete. If a team migrated DTOs to **Java records** on MapStruct 1.4 or earlier, mappings silently fail or fall back because record canonical-constructor mapping arrived in 1.5. If they're on **Spring Boot 3 / Jakarta EE 9+**, they need the `jakarta` component model (or `spring`, which targets Jakarta in Boot 3) and at least 1.5.x to avoid `javax`/`jakarta` import mismatches. For **JDK 21** LTS, 1.6.x is the safe floor because earlier processor releases predate JDK 21 and can emit `release`/`source` warnings or fail on newer language features.

```xml
<!-- Modern baseline: JDK 21 + Spring Boot 3 + records -->
<org.mapstruct.version>1.6.3</org.mapstruct.version>
```

The senior recommendation: pin a single MapStruct version property across all modules, keep `mapstruct` and `mapstruct-processor` versions identical (a mismatch is a classic source of subtle generation bugs), and treat a major-version bump like any dependency upgrade — read the migration notes, since defaults and strategy behavior (e.g. constructor-vs-field injection defaults, builder detection) have shifted between lines.

#### Q41. [Theory] How does `@Condition` / conditional mapping work internally, and how does it differ from `nullValueCheckStrategy` and presence checks?

`@Condition` (MapStruct 1.5+) lets you supply a custom **boolean predicate method** that MapStruct calls to decide whether a particular property (or source argument) should be mapped at all. The annotated method returns `boolean`; if it returns `false`, MapStruct skips the assignment for that target property entirely, leaving the target untouched (which, in an update scenario, preserves the existing value). It's effectively a user-defined, semantically-rich presence check that goes beyond "is it null."

The distinction from the other null mechanisms is about *expressiveness and intent*. `nullValueCheckStrategy` only ever asks "is the source null?" — a fixed, structural check. A **presence check** (`hasFoo()`/`JsonNullable.isPresent()`) asks "was this property present?" — still a property-shape concept. `@Condition` lets you encode *arbitrary business logic*: "map this address only if the postal code passes validation," "only copy the discount if it's non-negative," "skip blank strings." MapStruct generates `if (condition(source.getFoo())) target.setFoo(...);` inline, so the predicate participates in the generated control flow rather than being a post-hoc filter.

```java
@Mapper(componentModel = "spring",
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface ProfileMapper {

    void update(ProfileDto dto, @MappingTarget Profile entity);

    // Custom condition: treat blank/whitespace as "absent", not just null
    @Condition
    default boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
```

Here a `""` or `"  "` from the DTO is skipped just like a null, which `nullValuePropertyMappingStrategy = IGNORE` alone would *not* do (it only ignores actual nulls). The trade-off versus `@AfterMapping` post-processing is precision and efficiency: `@Condition` prevents the write from happening at all (cleaner for update/merge semantics and avoids transiently overwriting then fixing), whereas an `@AfterMapping` runs after the fact. The expert caution: keep `@Condition` predicates pure and cheap — they may run per element in a collection, and a side-effecting or expensive condition turns a tight generated loop into a performance problem.

#### Q42. [Theory] From a code-generation standpoint, why is `expression = "java(...)"` discouraged, and what exactly does MapStruct do (and not do) with it?

When you write `@Mapping(target = "x", expression = "java(someExpr)")`, MapStruct treats the string inside `java(...)` as an **opaque code fragment** that it pastes verbatim into the generated `*Impl` as the right-hand side of an assignment. It does *not* parse, type-check, or understand the expression during processing — it only substitutes referenced parameter names and resolves any `imports` you declared via `@Mapper(imports = ...)`. The expression is validated only later, when `javac` compiles the generated source. This is the crux of why it's discouraged.

The consequences are concrete. Because the fragment is a string, your IDE cannot refactor it (rename a method and the expression silently breaks), cannot autocomplete inside it, and cannot statically analyze it; the *only* feedback is a downstream compile error pointing at generated code rather than your mapper. It also can't participate in MapStruct's selection algorithm — MapStruct won't reuse it, optimize it, null-guard it, or invert it for `@InheritInverseConfiguration`. And it scatters business logic into stringly-typed annotations, hurting readability and testability.

```java
// Discouraged: opaque string, no IDE refactor, no reuse
@Mapping(target = "fullName",
         expression = "java(u.getFirstName() + \" \" + u.getLastName())")
UserDto toDto(User u);

// Preferred: a real, type-checked, testable, reusable method MapStruct selects normally
default String fullName(User u) { return u.getFirstName() + " " + u.getLastName(); }
@Mapping(target = "fullName", source = ".")   // or let selection pick fullName(User)
UserDto toDto2(User u);
```

The legitimate uses are narrow: a tiny constant-ish derivation, calling a static utility, or injecting a value MapStruct can't otherwise express — and even then `defaultExpression` (only when source is null) or a `@Named` `default` method is usually cleaner. The seasoned rule of thumb: if the expression is more than a trivial one-liner, promote it to a `default`/`uses` method so it becomes type-safe, unit-testable, and refactorable. Overuse of `expression` is a recognized MapStruct anti-pattern precisely because it forfeits the compile-time-safety that is the library's whole reason to exist.

#### Q43. [Practical] You need a mapper that produces an immutable target while *merging* in values from an existing instance (record-based PATCH). How do you reconcile MapStruct's `@MappingTarget` limitation with immutability?

The core constraint is that `@MappingTarget` mutates an existing instance via setters/adders, which immutable types (records, Immutables, Lombok `@Value`) don't have — so the in-place PATCH idiom from mutable entities simply doesn't apply. The reconciliation is to **construct a new immutable instance** whose fields are taken from the existing target *unless* the patch provides a value. There are two clean approaches depending on whether the target exposes a builder.

If the immutable type has a **toBuilder/builder** (Lombok `@Builder(toBuilder = true)`, Immutables `withX`), the idiomatic solution is to seed a builder from the existing value and overlay present patch fields, using `@Condition` (Q41) so absent/blank fields are skipped. If it's a bare **record**, you write a `default` method that calls the canonical constructor, choosing per component between the patch value and the current value.

```java
public record Profile(String name, String bio, String avatarUrl) {}

@Mapper(componentModel = "spring")
public abstract class ProfilePatchMapper {

    // Build a NEW record, keeping current values where the patch is absent/blank
    public Profile patch(ProfilePatchDto dto, Profile current) {
        return new Profile(
            pick(dto.name(),      current.name()),
            pick(dto.bio(),       current.bio()),
            pick(dto.avatarUrl(), current.avatarUrl())
        );
    }
    private static String pick(String incoming, String existing) {
        return (incoming != null && !incoming.isBlank()) ? incoming : existing;
    }
}
```

With a Lombok `@Builder(toBuilder = true)` target the equivalent is `current.toBuilder()` plus conditional `.field(...)` overlays, which MapStruct can largely generate when you express the merge as a normal mapping with `@Condition` guards. The trade-off versus mutable entities: every PATCH allocates a fresh object (negligible cost, and arguably *safer* — no aliasing, thread-friendly), but you lose JPA dirty-checking on a managed entity, so for a persistence layer you'd typically keep the *entity* mutable (map into it with `@MappingTarget` + `IGNORE`) and reserve immutable-merge for DTO/value-object layers. The senior insight is recognizing that "records broke our PATCH mapper" is not a MapStruct deficiency but an inherent property of immutability — the fix is a rebuild-with-fallback strategy, ideally expressed once in a shared helper, not scattered conditionals.

#### Q44. [Theory] Compare MapStruct's compile-time code generation to alternative paradigms — runtime reflection (ModelMapper), bytecode generation (cglib/ByteBuddy proxies), and macro/codegen in other ecosystems. What are the deep trade-offs?

The mapping-tool design space spans four points, and being able to situate MapStruct among them is a strong architectural answer. **Runtime reflection** (ModelMapper, legacy Dozer) resolves property graphs by introspecting live objects on each call: maximally flexible (handles shapes unknown at compile time), but pays per-call reflection cost, defers all errors to runtime, and is hard to debug. **Runtime bytecode generation** (cglib/ByteBuddy, as used by some ORMs/proxies) generates classes at startup: faster than pure reflection once warmed, but adds JVM warm-up cost, complicates stack traces, needs `--add-opens`/agent gymnastics on modern JDKs, and still resolves structure dynamically. **Compile-time codegen** (MapStruct, and analogues like Dagger for DI, Micronaut/Quarkus AOT, Rust/Scala macros, Go `go generate`) emits ordinary source/bytecode ahead of time: zero runtime overhead, full static verification, debuggable output — at the cost of build-time complexity and an inability to handle runtime-only shapes.

| Paradigm | Resolution time | Runtime cost | Error surface | Native-image / startup |
|----------|-----------------|--------------|---------------|------------------------|
| Reflection (ModelMapper) | runtime, per call | high | runtime | reflection config needed |
| Bytecode gen (cglib/ByteBuddy) | startup/runtime | medium (post-warm-up) | runtime | agent/opens friction |
| **Compile-time (MapStruct)** | compile | ~zero | **compile** | **excellent** (no reflection) |
| Hand-written | compile (you) | zero | compile | excellent |

The deep trade-off is **flexibility vs. verifiability vs. footprint**, and modern platform trends have tilted decisively toward compile-time. GraalVM native image and the Spring/Quarkus/Micronaut AOT movement penalize runtime reflection and dynamic class generation heavily (every reflective access needs explicit metadata; agents are awkward), whereas MapStruct's plain generated code "just works" in a closed-world native image with no extra configuration. That alignment — not just raw speed — is increasingly the decisive reason to choose compile-time mapping. The honest counterpoint: when the mapping shape genuinely *isn't* known until runtime (dynamic schemas, generic tooling), no amount of codegen helps and reflection is the correct paradigm. The mature position is to default to compile-time generation for the static 95% and quarantine the dynamic 5% behind a clearly-marked boundary.

#### Q45. [Theory] Why does MapStruct prefer constructor injection over field injection for the `spring` component model, and what does `injectionStrategy` actually change in the generated code?

`injectionStrategy` (on `@Mapper`/`@MapperConfig`, or globally via `-Amapstruct.defaultInjectionStrategy`) controls how the generated `*Impl` receives its collaborators — the mappers/converters pulled in via `uses`. With `InjectionStrategy.FIELD` MapStruct emits `@Autowired`-annotated private fields; with `InjectionStrategy.CONSTRUCTOR` it emits a constructor that takes those collaborators as parameters and assigns them to `private final` fields. The mapping logic is identical either way — what changes is the wiring contract of the generated class.

Constructor injection is preferred for the same reasons it's preferred everywhere in Spring, and MapStruct's generated code inherits all of them. The collaborator fields become `final`, so the object is fully initialized and immutable after construction (no half-built state, thread-safe publication). The class is instantiable in a plain unit test with `new XMapperImpl(new YMapperImpl())` — no reflection, no Spring context, no `ReflectionTestUtils` to poke private fields. Circular `uses` dependencies become a hard, visible failure at construction rather than a subtle field-injection-allows-cycles surprise. And it avoids field injection's well-known testability and encapsulation criticisms.

```java
// injectionStrategy = CONSTRUCTOR  ->  generated:
@Component
public class OrderMapperImpl implements OrderMapper {
    private final AddressMapper addressMapper;
    @Autowired public OrderMapperImpl(AddressMapper addressMapper) { this.addressMapper = addressMapper; }
    // ...
}

// injectionStrategy = FIELD  ->  generated:
@Component
public class OrderMapperImpl implements OrderMapper {
    @Autowired private AddressMapper addressMapper;   // mutable, harder to unit-test
}
```

The practical guidance is to standardize `CONSTRUCTOR` in a shared `@MapperConfig` so every generated mapper is uniformly testable and immutable. The one caveat is the genuine *circular* mapper dependency (two mappers `uses` each other), which constructor injection can't satisfy — but that's a design smell signaling you should extract the shared sub-mapping into a third mapper rather than a reason to fall back to field injection.

#### Q46. [Theory] What is `@ValueMappings` plus `MappingConstants.THROW_EXCEPTION` / `unexpectedValueMappingException`, and how does MapStruct generate exhaustive enum-to-enum mappings?

Enum-to-enum (and enum-to-String) mapping is handled by `@ValueMapping`, with `@ValueMappings` as the container for multiple. For a plain enum→enum method with no `@ValueMapping`, MapStruct maps by matching constant *names* and, crucially, **fails the build** if a source constant has no same-named target constant — this compile-time exhaustiveness is a major advantage over a hand-written `switch` that would compile and then throw (or silently fall through) at runtime when someone adds a constant. You then use `@ValueMapping` only for the exceptions (renames) and to define catch-all behavior.

The catch-all and error constants live in `MappingConstants`. `ANY_REMAINING` matches every source constant not explicitly listed (and forces you to handle the rest deliberately). `ANY_UNMAPPED` matches everything not otherwise mapped. The targets `MappingConstants.NULL` and `MappingConstants.THROW_EXCEPTION` give you the two failure postures: map an unknown value to `null`, or generate code that throws `IllegalArgumentException` at runtime for an unexpected input.

```java
@Mapper
public interface ChannelMapper {
    @ValueMappings({
        @ValueMapping(source = "RETAIL",  target = "B2C"),
        @ValueMapping(source = MappingConstants.ANY_REMAINING, target = MappingConstants.THROW_EXCEPTION)
    })
    TargetChannel toTarget(SourceChannel s);   // unknown source -> IllegalArgumentException at runtime
}
```

The design tension is **fail-fast vs. forgiving evolution**, and a strong answer weighs it. For an internal closed enum you control on both sides, omitting `ANY_REMAINING` gives you the best guarantee: adding a constant on either side breaks the build until you map it. For an enum mapped from an *external* system that may add values you don't control, `ANY_REMAINING → NULL` (or a sentinel `UNKNOWN`) prevents a third-party change from throwing in production. `THROW_EXCEPTION` is the right choice when an unexpected value genuinely indicates a bug or data-integrity problem you'd rather surface loudly than silently absorb. The expert habit is to be deliberate about which posture each enum mapping uses, and to comment *why*, because the wrong default here causes either brittle deploys or silent data loss.

#### Q47. [Theory] How does MapStruct handle the `defaultValue` / `defaultExpression` vs `constant` vs `expression` attributes? Walk through the precedence and the generated guards.

These four attributes occupy different semantic slots and generate distinctly guarded code. **`constant`** assigns a fixed literal *unconditionally* and ignores the source entirely — there's no read of the source property at all (`target.setX(<constant>)`). **`expression`** assigns the result of an opaque `java(...)` fragment, also unconditionally. **`defaultValue`** and **`defaultExpression`** are the conditional pair: they supply a fallback used *only when the mapped source value is null*, generating a null-guard (`target.setX(src.getY() != null ? convert(src.getY()) : <default>)`).

The key distinction interviewers probe is *constant/expression (always) vs. default* (only-if-null), and the fact that `defaultValue` is a String literal subject to the same built-in conversions as a normal source, while `defaultExpression` is an opaque `java(...)` fragment like `expression`. You cannot combine `source` with `constant`/`expression` (they replace the source), but you *do* combine `source` with `defaultValue`/`defaultExpression` (they augment it).

```java
@Mapping(target = "tenant",   constant = "RETAIL")                          // always "RETAIL"
@Mapping(target = "fee",      expression = "java(calc(o))")                 // always calc(o)
@Mapping(target = "currency", source = "ccy", defaultValue = "USD")        // ccy, else "USD" when null
@Mapping(target = "createdAt",source = "ts",  defaultExpression = "java(java.time.Instant.now())")
OrderDto toDto(Order o);
```

The trade-off and pitfall: `defaultValue` participates in conversion (so `defaultValue = "0"` on a numeric target is parsed for you), but `defaultExpression`/`expression` are stringly-typed and unverified until generated-code compilation (see the `expression` anti-pattern discussion). A subtle correctness trap is conflating `constant` with `defaultValue` — using `constant` when you meant "fall back to" silently throws away the source value in every case, not just when null. Reach for `defaultValue` for the common "source-or-sensible-fallback" need, `constant` only when the value is truly independent of the source, and prefer a `default` method over `defaultExpression` for anything non-trivial so it stays type-checked.

#### Q48. [Theory] What does MapStruct's `@BeanMapping` annotation control that `@Mapping` cannot, and when is `ignoreByDefault` / `ignoreUnmappedSourceProperties` the right tool?

`@Mapping` configures a *single target property*; `@BeanMapping` configures the *whole method-level* bean mapping. The things only `@BeanMapping` can express include: `resultType` (pick which concrete subtype to instantiate when a method's return type is abstract or has multiple candidate implementations), `qualifiedByName`/`qualifiedBy` at the method level (constrain which factory/lifecycle methods apply), `nullValueCheckStrategy`/`nullValuePropertyMappingStrategy`/`nullValueMappingStrategy` overrides scoped to that method, `ignoreUnmappedSourceProperties` (a list of *source* fields to exempt from `unmappedSourcePolicy` reporting), and the powerful `ignoreByDefault = true`.

`ignoreByDefault = true` inverts MapStruct's normal whitelist-by-convention behavior: instead of "map every matching name and warn about gaps," it maps **nothing** unless you explicitly opt each target in via `@Mapping(target = "...", source = "...")`. This is the right tool when a target type has many fields but a given mapping should populate only a deliberate few — e.g. a summary/projection DTO carved from a fat entity, or a security-sensitive mapping where you want an *explicit allowlist* of what gets copied rather than relying on remembering to `ignore` each sensitive field.

```java
@BeanMapping(ignoreByDefault = true)                  // nothing maps unless listed
@Mapping(target = "id",   source = "id")
@Mapping(target = "name", source = "displayName")     // only these two are populated;
                                                      // password, salt, internalNotes stay null by design
UserSummaryDto toSummary(User user);
```

The trade-off versus the default whitelist behavior: `ignoreByDefault` is more verbose for mappings that genuinely should copy most fields, but for *security-sensitive outbound mappings* it flips the failure mode from "forgot to ignore a secret → leaked" to "forgot to include a field → it's just missing." On the trust boundary (entity → external DTO), allowlisting is the safer default and aligns with the over-posting/PII discussion — you decide explicitly what leaves the system rather than what doesn't. `ignoreUnmappedSourceProperties` is the complementary tool for documenting *known* intentional drops so a strict `unmappedSourcePolicy = ERROR` stays clean.

### 🔴 Expert — extended (continued)

#### Q49. [Theory] Explain how MapStruct integrates with GraalVM native image and AOT-compiled Spring Boot, and why it's structurally well-suited to it.

GraalVM native image performs **closed-world, ahead-of-time** compilation: everything reachable must be known at build time, and any runtime reflection, dynamic proxies, or runtime class loading requires explicit metadata (reflect-config, proxy-config) or it fails in the native binary. This is precisely the environment in which reflection-based mappers struggle — ModelMapper/Dozer reflect over property graphs at runtime and would need extensive, brittle reflection metadata. MapStruct, by contrast, generated all its mapping code at *Java* compile time as ordinary method calls on your own getters/setters; there is nothing reflective, no proxy, and nothing dynamic for native image to choke on.

The structural fit is therefore near-perfect: a MapStruct `*Impl` is just bytecode that GraalVM analyzes and includes like any other class, with zero extra configuration. There's no warm-up (no runtime metadata building), which complements native image's fast-startup value proposition, and it composes cleanly with Spring Boot 3's AOT engine (which itself shifts bean wiring decisions to build time) and with Micronaut/Quarkus, whose entire philosophy is compile-time processing to enable reflection-free native binaries.

```
GraalVM closed-world analysis
   reflection mapper  ──▶ needs reflect-config.json, may still fail at runtime
   MapStruct *Impl    ──▶ plain reachable bytecode, no config, no warm-up  ✅
```

There's a nuance worth raising for credibility: the *only* places to watch are custom `expression`s or `default` methods that themselves use reflection, and the component-model wiring — with `componentModel = "spring"` the Spring AOT/native support generally registers the generated `@Component` impls automatically, but a `default`-model mapper relying on `Mappers.getMapper` reflection-loads the impl by name, which *would* need a reflection hint in native image. So the senior recommendation for native targets is to use the `spring`/`jakarta` component model (DI-wired, no reflective lookup) rather than `Mappers.getMapper`, keeping the whole mapping layer reflection-free end to end.

#### Q50. [Theory] How does MapStruct decide whether to *reuse* an existing user-defined mapping method versus *generate an inline* nested mapping, and why does that distinction matter for maintainability and cycles?

When MapStruct encounters a property whose source and target are themselves beans (e.g. `Order.customer : Customer` → `OrderDto.customer : CustomerDto`), it first searches for an existing mapping method whose signature fits (`CustomerDto map(Customer)` in this mapper, an inherited one, or one from a `uses` mapper), applying the selection algorithm from Q27. If it finds exactly one, it **reuses** it — generating a call to that method. If it finds none, MapStruct **generates an inline** nested mapping: it recursively builds the get/set code for the nested bean's properties directly inside the parent method, without a reusable method.

This reuse-vs-inline distinction has outsized practical consequences. A *reused* method is a single source of truth: configure `CustomerDto map(Customer)` once (renames, formats, ignores) and every mapping that touches a `Customer` benefits, and it's independently unit-testable. *Inline* generation duplicates the nested logic at each call site, so a rename or special-case must be repeated, divergence creeps in, and there's no method to test in isolation. Inline mappings are also where many "unmapped nested property" surprises originate, because the nested gaps are reported against the synthetic inline mapping rather than a named method.

```java
@Mapper(componentModel = "spring")
public interface OrderMapper {
    OrderDto toDto(Order o);
    CustomerDto toDto(Customer c);   // DECLARE this -> reused for the nested customer field
                                     // omit it -> MapStruct inlines Customer mapping inside toDto(Order)
}
```

The crucial connection to cycles: inline generation is exactly what causes **infinite recursion / stack overflow** on bidirectional graphs (Q15 in the base set), because the inline expansion of `parent`→`child`→`parent`→… never terminates, whereas a *reused method* combined with a `@Context CycleAvoidingMappingContext` can short-circuit via the identity cache. The seasoned practice is therefore to **always declare the nested/element mapping methods explicitly** rather than relying on inline generation — it makes generation predictable, reusable, testable, and cycle-safe, and it keeps mapping configuration in one place per type rather than smeared across every parent mapper.

#### Q51. [Theory] What incremental-compilation and build-caching considerations are specific to MapStruct, and how do you keep a large multi-module build fast and reproducible?

MapStruct's generation is deterministic given its inputs (the `@Mapper` interface, the source/target types it references, and the processor options), but build tools only get the speed benefit if they track those inputs correctly. In **Gradle**, the processor must be declared on the `annotationProcessor` configuration (not the regular `implementation` classpath) so Gradle treats it as a tracked annotation-processing input and supports incremental annotation processing; misplacing it disables incremental compile and can leave stale `*Impl` files that produce the maddening "I edited the mapper but the build behaves as before" symptom. MapStruct's processor is incremental-aware (an "isolating"/"aggregating" processor in Gradle's classification), so a change to one mapper recompiles a bounded set, not the whole module — but only if the classpath is configured right.

For **build caching and reproducibility**, the default generator timestamp and version comment that MapStruct writes into each `*Impl` change every build, which busts caches and pollutes diffs if you commit generated sources. Suppress them:

```xml
<compilerArgs>
  <arg>-Amapstruct.suppressGeneratorTimestamp=true</arg>
  <arg>-Amapstruct.suppressGeneratorVersionInfoComment=true</arg>
</compilerArgs>
```

Two cross-module realities round out the picture. First, because a downstream module that `uses` a mapper from an upstream module reads the upstream type from the *classpath*, changing an upstream mapper interface invalidates and recompiles downstream modules — so deep `uses` graphs across many modules lengthen incremental builds; the mitigation is to keep shared converters in a small, **low-churn** module. Second, `mapstruct` and `mapstruct-processor` versions must match exactly across all modules (pin via a single version property), because a version skew between the annotations and the processor is a classic cause of subtle, hard-to-diagnose generation differences. The staff-level summary: MapStruct itself is fast; build slowness and flakiness almost always trace to processor-classpath placement, unstable generator comments defeating caching, or an over-connected `uses` graph — all of which are configuration, not library, problems.

#### Q52. [Theory] How would you implement a *generic* reusable conversion (e.g. wrapping any `T` into an `ApiResponse<T>`, or `Page<E>` → `PageDto<D>`) given that MapStruct can't map bare type variables?

This question pins down the boundary established in Q38: MapStruct can't inspect a bare type variable because there's no static structure to map. The way you *do* get reuse for generic wrappers is to provide a **generic helper method with concrete element-mapping supplied by MapStruct's normal selection** — typically a hand-written generic `default`/`abstract` method that takes the already-mapped payload, or to map the *container shape* explicitly per concrete instantiation while letting MapStruct handle the element type via an existing element mapper.

For a wrapper like `ApiResponse<T>` whose only job is to carry a payload plus metadata, write a generic `default` method that's type-checked by `javac` (generics are fine in *your* code; MapStruct just won't synthesize field-by-field mapping for `T`):

```java
@Mapper(componentModel = "spring", uses = ProductMapper.class)
public interface ResponseMapper {
    // Concrete instantiation: MapStruct maps the element via ProductMapper, you wrap generically
    default ApiResponse<ProductDto> wrap(Product p, ProductMapper pm) {
        return ApiResponse.ok(pm.toDto(p));
    }
    // Reusable generic container helper (type-safe Java, no MapStruct synthesis needed)
    default <T> ApiResponse<T> ok(T body) { return new ApiResponse<>(body, "OK", Instant.now()); }
}
```

For the common Spring Data `Page<E>` → `PageDto<D>` case, the idiomatic pattern is a generic `default` method that maps the content list with a supplied element mapper (or a functional `Function<E,D>`) and copies the pagination metadata, since MapStruct can map the `List<E>`→`List<D>` content when an element method exists but can't synthesize the generic envelope:

```java
default <E, D> PageDto<D> toPageDto(Page<E> page, Function<E, D> elementMapper) {
    List<D> content = page.getContent().stream().map(elementMapper).toList();
    return new PageDto<>(content, page.getNumber(), page.getSize(), page.getTotalElements());
}
```

The deep point for an expert answer: you separate the *generic, structure-agnostic envelope* (write once by hand, fully type-safe) from the *concrete, structure-bearing element mapping* (let MapStruct generate per type). Trying to make MapStruct synthesize the whole `Wrapper<T>` is fighting its design; embracing the split gives you genuine reuse without losing compile-time safety, and it keeps the dynamic/generic seam small and explicit — the same "quarantine the dynamic part" principle that recurs throughout MapStruct architecture.

#### Q53. [Theory] What are the threading and statefulness guarantees of generated mappers, and where can shared mutable state silently break correctness?

A vanilla generated `*Impl` for a stateless `@Mapper` is **thread-safe and effectively immutable**: it holds only `final` references to its `uses` collaborators (with constructor injection) and the mapping methods operate solely on their arguments and locally-allocated targets — no shared mutable instance state. That's why mappers are correctly registered as Spring singletons and can be called concurrently from many request threads without synchronization. This is a deliberate property: the generated code is just functions over inputs.

The correctness landmines all involve introducing **shared mutable state** into that otherwise-stateless model. The canonical example is the `CycleAvoidingMappingContext` (base-set Q15): it carries an `IdentityHashMap` of already-mapped objects and is therefore *stateful per mapping invocation*. It must be created fresh for each top-level call and passed as `@Context`; if you ever made it a singleton bean or reused one instance across threads/requests, you'd get cross-request data bleed and `IdentityHashMap` concurrent-modification corruption — a severe, hard-to-reproduce bug. Similarly, an `abstract class` mapper that holds a mutable field (a counter, a cache, a builder) populated during mapping breaks the singleton-thread-safety assumption.

```java
// SAFE: fresh per call, passed via @Context, never a shared bean
CategoryDto dto = mapper.toDto(category, new CycleAvoidingMappingContext());

// DANGEROUS: a stateful context reused/shared -> cross-request bleed + ConcurrentModification
@Bean CycleAvoidingMappingContext ctx() { return new CycleAvoidingMappingContext(); } // ❌ singleton
```

The other subtle case is `@AfterMapping`/`@BeforeMapping` or `@Condition` methods (Q41) that read or mutate shared state (a static cache, an injected mutable service) — those run inside the generated method on the calling thread, so any non-thread-safe collaborator they touch becomes a concurrency hazard at mapper scope even though the mapper *itself* looks stateless. The expert rule: keep mappers and their lifecycle/condition hooks **pure**; any necessary per-operation state must travel through `@Context` as a freshly-created object, never as shared mapper or bean state. This is also why MapStruct mappers are trivial to reason about under load compared with reflection mappers that often maintain internal type-resolution caches with their own locking.

#### Q54. [Practical] An interviewer hands you a mapper that compiles but produces a target with several null fields in production. Walk through your systematic diagnosis of *why MapStruct didn't map them*.

I'd treat this as a structured root-cause hunt because "null field after mapping" has a small, enumerable set of causes in MapStruct. The single fastest move is to **open the generated `*Impl`** and look at the offending setter: either there's no `set` call for that field (MapStruct didn't map it), or there is one but it's null-guarded and the source was null. That immediately bisects the problem into "resolution failure" vs "source-was-null," and most of the remaining diagnosis follows from which branch you're in.

If there's **no setter call at all**, the candidates, in order of likelihood: (1) the field was reported as an *unmapped target* but `unmappedTargetPolicy` was the default `WARN`, so the build logged it and moved on — the fix is `ERROR` in CI and you'd have caught it at build time; (2) **Lombok ordering** — if *many* fields are unmapped, MapStruct compiled before Lombok added accessors (missing `lombok-mapstruct-binding`), so the source bean looked propertyless; (3) a **name/type mismatch** with no built-in conversion and no `@Mapping(source=...)`, so MapStruct couldn't resolve a source; (4) a missing **nested/element mapping method** so an inline mapping silently dropped sub-fields; (5) an accidental `@Mapping(target=..., ignore=true)` or `@BeanMapping(ignoreByDefault=true)` without the corresponding include.

```bash
# 1. Inspect what was actually generated (Maven)
ls target/generated-sources/annotations/   # find XMapperImpl.java, read the setter calls

# 2. Re-run with verbose processor logging to see resolution decisions
mvn -q clean compile -Dmaven.compiler.showWarnings=true \
    -Amapstruct.verbose=true -Amapstruct.unmappedTargetPolicy=ERROR
```

If the setter **is** generated but null-guarded, then the source value was null at runtime: check whether the source property is genuinely null upstream, whether a presence-check/`@Condition` skipped it, or whether `nullValuePropertyMappingStrategy = IGNORE` on an *update* mapping correctly preserved an existing (null) target. The disciplined close is to convert the finding into prevention — turn on `unmappedTargetPolicy = ERROR`, add `-Amapstruct.verbose=true` while investigating, and add a round-trip/assert-non-null test for the field — so this class of bug fails the build next time instead of surfacing in production. The meta-point I'd make to the interviewer: with MapStruct you almost never *guess* about mapping behavior, because the generated code is right there to read — that observability is a core reason to use it.

#### Q55. [Theory] What "special" parameters can MapStruct inject into lifecycle, factory, and helper methods (`@TargetType`, `@TargetPropertyName`, `@SourcePropertyName`, `@Context`), and how does it resolve them?

Beyond ordinary mapped parameters, MapStruct can pass a fixed vocabulary of *meta* parameters into `@ObjectFactory`, `@BeforeMapping`/`@AfterMapping`, `@Condition`, and qualifier helper methods. It resolves them **by their annotation and type**, not by position — so you declare only the ones you need, in any order. The key ones: `@TargetType Class<T>` receives the concrete target type being produced (essential for a generic factory that does `em.find(type, id)` or `type.getDeclaredConstructor()...`); `@Context X` threads a non-mapped object (Locale, EntityManager, cycle context) through the whole graph; `@TargetPropertyName String` and `@SourcePropertyName String` (1.5+) receive the *name* of the property currently being mapped, enabling one helper to behave differently per field; and `@MappingTarget` receives the in-progress target for post-processing.

The resolution mechanism is what makes this clean: MapStruct inspects the helper method's parameters, matches each annotated/typed slot to a value it has in scope at the call site, and generates the call with exactly those arguments — unfilled optional slots simply aren't required. This is why a single `@BeforeMapping` or `@Condition` method can be reused across many mappings: it asks for `@TargetType`/`@TargetPropertyName` and adapts, rather than being hard-wired to one mapping.

```java
@Condition
default boolean shouldMap(Object value, @TargetPropertyName String prop) {
    // one condition, behaves per-field: never overwrite an immutable audit column
    if ("createdAt".equals(prop)) return false;
    return value != null;
}

@ObjectFactory
public <T> T create(@TargetType Class<T> type, @Context EntityManager em, UserDto dto) {
    return (dto.getId() != null) ? em.find(type, dto.getId()) : type.getDeclaredConstructor().newInstance();
}
```

The trade-off and gotcha: `@TargetPropertyName`/`@SourcePropertyName` are powerful for cross-cutting per-field policy (skip audit fields, redact named columns) but make the helper's behavior *implicit* — a reader of the mapper interface can't see that `createdAt` is special without reading the helper. Use them for genuinely uniform cross-field rules; for a one-off, an explicit `@Mapping(target="createdAt", ignore=true)` is clearer. The deeper insight is that MapStruct's lifecycle hooks are *dependency-injected by meta-type*, which is why they compose so flexibly across a mapper graph without you wiring anything by hand.

#### Q56. [Theory] How does MapStruct handle `Optional<T>`, primitives/autoboxing, and the null-to-primitive hazard? Explain the generated semantics and the correctness traps.

MapStruct's handling here follows directly from its "generate plain Java" principle, and each case has a distinct trap. For **`Optional<T>`**, MapStruct (with appropriate handling/since 1.6 improvements, or via a small helper in earlier versions) treats it as a presence-bearing wrapper: mapping `Optional<String>` source ↔ plain `String` target unwraps with a presence guard, and an empty `Optional` behaves like absence. The trap is asymmetry — unwrapping is natural, but mapping *into* an `Optional` target or round-tripping needs care, and relying on implicit `Optional` handling across MapStruct versions is fragile, so many teams provide explicit `default Optional<T> wrap(T)` / `default T unwrap(Optional<T>)` helpers to make the semantics version-independent and visible.

For **autoboxing**, MapStruct inserts the same widening/boxing conversions Java would, as built-in conversions: `int`→`Integer`, `Integer`→`int`, `int`→`long`, etc. The serious correctness hazard is the **null-to-primitive** case: mapping a nullable `Integer` source into a primitive `int` target. If the source is null, unboxing throws `NullPointerException` at the assignment — and because MapStruct's *default* `nullValueCheckStrategy` is `ON_IMPLICIT_CONVERSION`, the generated code may or may not guard depending on whether it classifies the assignment as an implicit conversion. The robust postures are: set `nullValueCheckStrategy = ALWAYS` so every source is null-checked before access, or provide a `defaultValue` so a null source yields a defined primitive value rather than an NPE.

```java
public class Src { Integer count; }     // nullable wrapper
public class Dst { int count; }         // primitive

@Mapper(nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS)   // guard the unboxing
public interface CountMapper {
    @Mapping(target = "count", source = "count", defaultValue = "0") // null -> 0, never NPE
    Dst map(Src s);
}
// Generated (conceptual): dst.setCount(s.getCount() != null ? s.getCount() : 0);
```

The expert framing ties this back to design philosophy: MapStruct won't silently invent a value for a null→primitive mapping, and it won't (by default) aggressively guard everything either — it sits at a *predictable* middle (`ON_IMPLICIT_CONVERSION`) that you tune per risk. On a financial/quantity field, a silent default of `0` for a missing value can be *worse* than an NPE (it fabricates data), so the right answer is sometimes to keep the target boxed (`Integer`) and let null propagate as a genuine "unknown," or to validate presence at the edge before mapping. Knowing that the choice between "default to 0," "throw," and "keep nullable" is a *domain* decision — not a MapStruct default to accept blindly — is what separates a senior answer from "just set a default value."

## 🧩 Extended Questions — Set 2: Practical, Operational & Troubleshooting

### 🟢 Basic — extended

#### Q57. [Practical] Your colleague adds a `@Mapper` interface but at runtime gets `NoSuchBeanDefinitionException` for it. Walk through the checklist you'd run to fix it.

This is one of the most common first-week MapStruct support tickets, and it almost always comes down to "the generated `*Impl` exists but Spring never registered it as a bean." I'd work a short, ordered checklist rather than guessing. First, confirm the mapper declares `@Mapper(componentModel = "spring")` (or inherits it from a `@MapperConfig`, or has the build-level `-Amapstruct.defaultComponentModel=spring`). Without a Spring component model the generated impl has no `@Component`, so there's simply nothing for Spring to wire — this is the single most frequent cause.

Second, confirm the generation actually ran: look in `target/generated-sources/annotations/` for `XMapperImpl.java` and verify it carries `@Component`. If the file is missing entirely, the annotation processor never executed — usually the `mapstruct-processor` isn't on `annotationProcessorPaths`/`annotationProcessor`, or an IDE is compiling without running processors. Third, confirm the generated package is inside the application's `@ComponentScan` base packages; mappers generated under a package *outside* your `@SpringBootApplication` root won't be discovered.

```bash
# Is the impl generated, and does it carry @Component?
ls target/generated-sources/annotations/**/UserMapperImpl.java
grep -n "@Component" target/generated-sources/annotations/**/UserMapperImpl.java
```

The "why" I'd explain to the colleague: MapStruct generation and Spring registration are two independent steps. Generation is a `javac` concern (processor on the path); registration is a component-scan concern (`@Component` + scan range). A bean-not-found error means step one probably succeeded but step two didn't — so I check the component model and scan range before ever suspecting MapStruct itself. The defensive fix is to standardize the Spring component model in a shared `@MapperConfig` so no one forgets it per-mapper.

#### Q58. [Practical] How do you wire MapStruct in Gradle (including with Lombok), and what is the Gradle-specific gotcha that doesn't exist in Maven?

In Gradle the processor goes on the `annotationProcessor` configuration and the annotations jar on `implementation`. With Lombok you also need the `lombok-mapstruct-binding` on `annotationProcessor`, and the *order* of `annotationProcessor` declarations matters less than in raw Maven because the binding coordinates them, but the bigger Gradle-specific trap is incremental annotation processing being silently disabled if the processor is placed on the wrong configuration.

```gradle
dependencies {
    implementation 'org.mapstruct:mapstruct:1.6.3'
    annotationProcessor 'org.mapstruct:mapstruct-processor:1.6.3'

    compileOnly 'org.projectlombok:lombok:1.18.34'
    annotationProcessor 'org.projectlombok:lombok:1.18.34'
    annotationProcessor 'org.projectlombok:lombok-mapstruct-binding:0.2.0'
}

// Optional: pass processor options the Gradle way
tasks.withType(JavaCompile).configureEach {
    options.compilerArgs += [
        '-Amapstruct.defaultComponentModel=spring',
        '-Amapstruct.unmappedTargetPolicy=ERROR',
        '-Amapstruct.suppressGeneratorTimestamp=true'
    ]
}
```

The Gradle-specific gotcha I'd call out is the `mapstruct` annotations jar being placed *only* on `annotationProcessor` and not on `implementation` — then the `@Mapper` annotations aren't on the runtime/compile classpath of your own code and you get compile errors on the annotation imports, or the processor runs but your code can't reference the mapper types. The mirror mistake (processor on `implementation`) makes the heavy processor a runtime dependency and can disable Gradle's incremental processing. The rule: annotations on `implementation`, processor on `annotationProcessor`, and never cross them.

#### Q59. [Practical] A field that "obviously matches" isn't being copied and you get no warning at all. What are the everyday naming/accessor reasons, and how do you confirm?

When a field silently doesn't map *and there's no warning*, the usual culprit is that MapStruct doesn't see the property the way you think it does — and "no warning" specifically points to it not even being recognized as a target property to report on, or `unmappedTargetPolicy` being the default `WARN` that got lost in build noise. The everyday accessor reasons: a `boolean` field exposed as `isActive()`/`getActive()` mismatch, a getter without a corresponding setter on a mutable target (so there's nothing to write into), a field whose getter name doesn't follow JavaBeans convention (a fluent `name()` instead of `getName()`), or a `record` component name that differs from the source property name.

The fastest confirmation is always to **read the generated impl** and see whether a `setX(...)` line exists for the field. If the line is absent, MapStruct didn't resolve a source; if it's present but you still see null, the source was null at runtime. To surface the resolution reasoning rather than infer it, rebuild with `-Amapstruct.verbose=true` and bump `unmappedTargetPolicy` to `ERROR` so the gap becomes a hard, unmissable failure instead of a buried warning.

```java
// Trap: target has a getter but no setter -> nothing to map into, no obvious error
public class TargetDto {
    private String status;
    public String getStatus() { return status; }   // no setStatus -> 'status' is unwritable
}
```

The lesson I'd give a junior is that MapStruct maps via *accessors*, not fields — so the question is never "do the fields match?" but "does the target expose a writable accessor and does the source expose a readable one, under the configured `AccessorNamingStrategy`?" Once you internalize that, these silent no-ops stop being mysterious and you go straight to the generated code or the verbose log.

#### Q60. [Practical] How do you map a single source field to multiple target fields, or split/combine fields, in everyday DTO work — and what's the cleanest approach for each?

Day-to-day mapping constantly hits "one source, many targets" and "many sources, one target." For **one source → many targets** (e.g. a `Money` with amount and currency feeding both `amount` and `currencyCode` on the DTO), you simply write multiple `@Mapping` entries reading nested paths off the same source object; MapStruct generates independent setter calls, so there's no special construct needed. For **many sources → one target** (combining first/last into a full name), the clean options are a `default`/`uses` method or, for trivial concatenation, an `expression` — though I prefer a named method for testability.

```java
@Mapper(componentModel = "spring")
public interface PersonMapper {
    // one source object, many target fields (nested path reads)
    @Mapping(target = "amount",       source = "price.value")
    @Mapping(target = "currencyCode", source = "price.currency")
    // many fields -> one target via a reusable method (preferred over expression)
    @Mapping(target = "fullName",     source = ".")
    PersonDto toDto(Person p);

    default String fullName(Person p) { return p.getFirstName() + " " + p.getLastName(); }
}
```

The everyday subtlety is that `source = "."` passes the *whole* parameter to a helper, which is how you combine fields without an `expression`. For genuine field-splitting (one string into several targets — e.g. `"city, state"` → `city` + `state`), MapStruct has no built-in "split" so you write a small `@AfterMapping` or a `default` helper that does the parsing and sets both targets. The judgment call I'd flag: keep these helpers tiny and pure so they remain unit-testable, and resist the temptation to bury parsing logic in `expression` strings where the IDE and compiler can't help you.

### 🟡 Intermediate — extended

#### Q61. [Practical] You enabled `unmappedTargetPolicy = ERROR` across the codebase and the build is now red with dozens of errors. How do you roll it out without blocking the team?

Flipping a fleet-wide policy from `WARN` to `ERROR` in one commit is how you make the whole team hate your initiative — it'll surface every pre-existing gap at once and block unrelated work. I'd roll it out as a *staged migration* rather than a flag flip. First, run a build with the policy on but capture the failures as a worklist, not a gate: categorize them into genuine bugs (a target field that *should* be populated and is silently null) versus intentional drops (audit fields, computed-later fields).

For the intentional ones, I add explicit `@Mapping(target = "...", ignore = true)` or `@BeanMapping(ignoreByDefault = true)` with an allowlist — which documents the decision rather than hiding it. For the genuine bugs, those are exactly the latent production nulls the policy exists to catch, so I fix them. To avoid a big-bang block, I'd enable `ERROR` *per module* or *per package* as each is cleaned, keeping the rest at `WARN`, and only flip the global default once the backlog is drained.

```java
// Stage 1: keep WARN globally, opt the cleaned module into ERROR via its own config
@MapperConfig(unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface BillingMapperConfig {}   // billing module mappers reference this first

// Stage 2: once all modules are clean, promote ERROR to the shared root config
```

The operational nuance I'd add: run `ERROR` in CI but allow `WARN` for fast local iteration (set via `-Amapstruct.unmappedTargetPolicy` differently per profile), so developers aren't blocked mid-refactor while the merge gate still enforces completeness. The leadership framing is that you're converting a class of silent production bug into a build failure, but you sequence it so the team experiences it as "new gaps are caught" rather than "all historical debt blocks me today."

#### Q62. [Practical] In a code review you see a 700-line mapper with dozens of `expression = "java(...)"` blocks. What specific refactors do you recommend, and how do you sequence them safely?

A 700-line mapper stuffed with `expression` blocks is a classic anti-pattern: it has forfeited most of MapStruct's compile-time safety and become stringly-typed logic the IDE can't refactor. My review feedback would be concrete and sequenced so the refactor is safe rather than a risky rewrite. First, I'd **pin behavior with tests** — golden/round-trip tests over the current outputs — so any refactor is verifiably behavior-preserving before I touch a line.

Then I'd refactor in passes: (1) promote each non-trivial `expression = "java(...)"` to a named `default` method or a method on a `uses` converter so it becomes type-checked, unit-testable, and refactorable; (2) extract cohesive groups of mappings (money, dates, addresses) into focused `uses` mappers, shrinking the god-mapper into a composition root; (3) replace repeated rename configs between `toDto`/`toEntity` with `@InheritInverseConfiguration`; (4) move shared policy to a `@MapperConfig`.

```java
// Before: opaque, untestable
@Mapping(target = "label", expression = "java(o.getCode() + \"-\" + o.getRegion().name())")
// After: a real method MapStruct selects, with a unit test
@Mapping(target = "label", source = ".")
default String label(Order o) { return o.getCode() + "-" + o.getRegion().name(); }
```

The sequencing matters because each pass is independently shippable and reviewable, keeping the diff comprehensible. I'd resist the urge to rewrite the mapper from scratch — large mappers usually encode hard-won edge cases, and a clean-room rewrite tends to silently drop them. The trade-off I'd name in the review: a few of those expressions may be genuinely trivial one-liners where a method is overkill, so the guideline is "promote anything more than a trivial concatenation," not "ban all expressions dogmatically."

#### Q63. [Practical] A PATCH endpoint using `nullValuePropertyMappingStrategy = IGNORE` reportedly "can't clear a field to null." How do you diagnose and what are the real production fixes?

This is a *correct-by-design* behavior being reported as a bug, so the first thing I'd do is confirm the diagnosis rather than change configuration reflexively. With `IGNORE`, a null source property is skipped, so an existing target value is preserved — which is exactly what you want for "field omitted from the PATCH" but is *wrong* for "field explicitly set to null to clear it," because JSON deserialization gives you `null` in both cases. The DTO simply cannot distinguish "absent" from "present-and-null" with a plain field, so `IGNORE` conflates them.

The real fix is to make absence and explicit-null *distinguishable in the model*, then let MapStruct honor the distinction. The idiomatic approach is a presence-aware wrapper — `JsonNullable<T>` from `org.openapitools.jackson.nullable` — whose `isPresent()` MapStruct treats as a presence check; an omitted field is `undefined()` (skipped) while an explicit `null` is present-with-null (applied). Alternatively, a custom `@Condition` predicate can encode the rule.

```java
public class UserPatchDto {
    private JsonNullable<String> nickname = JsonNullable.undefined();
    public JsonNullable<String> getNickname() { return nickname; }
}
// omitted  -> undefined()        -> MapStruct skips (entity keeps old value)
// "null"   -> JsonNullable.of(null) -> MapStruct applies null (field cleared)
// "value"  -> JsonNullable.of("x")  -> MapStruct applies "x"
```

The production trade-off is real: adopting `JsonNullable` ripples through the DTO, the OpenAPI generator config, and Jackson setup, so it's not free. For many internal APIs the simpler answer is to *document* that PATCH can't null-out fields and provide a dedicated "clear" operation, reserving the `JsonNullable` machinery for public APIs that must implement true RFC 7386 JSON Merge Patch. The senior move is recognizing this as a three-valued-logic modeling decision, not a MapStruct configuration knob.

#### Q64. [Practical] Two services share DTOs via a published library, and after a MapStruct upgrade one service's mappings behave differently. How do you investigate a version/behavior regression?

A behavior change after a MapStruct bump is usually a *default* that shifted between minor versions (injection strategy, builder detection, null-check classification) rather than a bug. My investigation starts by pinning exactly what changed: diff the generated `*Impl` files before and after the upgrade. Because MapStruct output is plain source, a `git diff` of the generated sources (or a quick before/after build into two folders) shows precisely which setter calls, null guards, or constructor-vs-field wiring changed — this turns a vague "behaves differently" into a concrete delta.

```bash
# Generate with old version into one dir, new into another, diff the impls
mvn -q -Dorg.mapstruct.version=1.5.5.Final clean compile
cp -r target/generated-sources/annotations /tmp/old
mvn -q -Dorg.mapstruct.version=1.6.3 clean compile
diff -ru /tmp/old target/generated-sources/annotations
```

With the delta in hand I consult the release notes/migration guide for the versions crossed, since defaults like `injectionStrategy`, builder handling, and `Optional`/null behavior have genuinely changed across lines. A frequent root cause in a shared-library scenario is a **version skew**: the library was built against one MapStruct version and the consuming service against another, or `mapstruct` and `mapstruct-processor` versions drifted apart — both produce subtle generation differences. I'd verify a single pinned version property is used everywhere.

The preventive practice I'd institute: pin MapStruct via a BOM/version property shared across services, treat upgrades as deliberate changes gated by the round-trip/golden tests, and — for a published DTO library — keep the *generated mappers out of the shared artifact* unless you also pin the MapStruct version transitively, so each consumer regenerates against its own (matching) processor. The meta-point is that MapStruct's readable output makes regressions diagnosable by diffing artifacts, which most reflection mappers can't offer.

#### Q65. [Coding] Implement a mapper that maps and rounds monetary amounts (cents→dollars with `BigDecimal`, half-even rounding) reused across many mappers, and explain why you isolate it.

**Problem:** Across an order/billing domain many DTOs expose dollar `BigDecimal` while entities store integer cents; rounding and scale must be consistent (banker's rounding, scale 2) everywhere, and a bug here means money is wrong.

```java
@Component                                  // a Spring bean so `uses` can inject it
public class MoneyConverter {

    @Named("centsToDollars")
    public BigDecimal centsToDollars(Long cents) {
        if (cents == null) return null;      // null-safe: absent amount stays absent
        return BigDecimal.valueOf(cents)
                         .movePointLeft(2)
                         .setScale(2, RoundingMode.HALF_EVEN);   // banker's rounding
    }

    @Named("dollarsToCents")
    public Long dollarsToCents(BigDecimal dollars) {
        if (dollars == null) return null;
        return dollars.movePointRight(2)
                      .setScale(0, RoundingMode.HALF_EVEN)
                      .longValueExact();      // throws on fractional cents -> fail loud, not silent
    }
}

@Mapper(componentModel = "spring", uses = MoneyConverter.class)
public interface InvoiceMapper {
    @Mapping(target = "total", source = "totalCents", qualifiedByName = "centsToDollars")
    InvoiceDto toDto(Invoice i);

    @Mapping(target = "totalCents", source = "total", qualifiedByName = "dollarsToCents")
    Invoice toEntity(InvoiceDto dto);
}
```

- **Why qualify with `@Named`:** a bare `Long`→`BigDecimal` would collide with built-in numeric conversion (which does *not* divide by 100). Qualifying forces MapStruct to choose your converter, not the built-in (Q31's precedence trap applied to money).
- **Time/Space:** O(1) per amount; no allocation beyond the result `BigDecimal`.
- **Edge cases:** `null` propagates as null (genuine "unknown amount"); `longValueExact()` throws on a fractional cent rather than silently truncating — a deliberate fail-loud choice because silently dropping sub-cent value is the exact class of bug that corrupts ledgers.

I isolate this in one `@Component` reused via `uses` so there is a *single* place where the unit conversion, scale, and rounding mode are defined and tested. Money bugs (cents vs dollars, rounding drift) are notoriously expensive and hard to spot in review when scattered; one converter with property-based tests over the round-trip (`dollarsToCents(centsToDollars(x)) == x`) makes the contract explicit and auditable.

#### Q66. [Practical] You need different mappings for the same DTO↔entity pair depending on context (e.g. a "public" vs "admin" projection). How do you organize this cleanly?

The wrong instinct is to add runtime `if` branches inside one mapper; the clean MapStruct approach is to express each projection as a *distinct method* (or a distinct mapper) because the shape difference is static. For a single mapper, declare separate methods — `toPublicDto` and `toAdminDto` — each with its own `@Mapping`/`@BeanMapping(ignoreByDefault = true)` allowlist, so the public projection physically cannot emit fields the admin one does.

```java
@Mapper(componentModel = "spring")
public interface AccountMapper {

    @BeanMapping(ignoreByDefault = true)          // explicit allowlist for the public view
    @Mapping(target = "id",   source = "id")
    @Mapping(target = "name", source = "displayName")
    PublicAccountDto toPublicDto(Account a);       // never exposes balance, ssn, internalNotes

    @Mapping(target = "passwordHash", ignore = true)
    AdminAccountDto toAdminDto(Account a);         // richer, but secrets still explicitly dropped
}
```

The big win of separate methods over runtime branching is that the *type system enforces the projection*: `PublicAccountDto` doesn't even have a balance field, so there's no path to leak it, and `ignoreByDefault = true` makes the public mapping an explicit allowlist that fails safe. This ties directly to the security boundary discussion — on outbound mappings you want to decide what *leaves* the system, and separate target types make that decision structural rather than conditional.

If the variation is more about *enrichment* than field selection (same fields, different derived values per tenant/locale), I'd instead pass a `@Context` carrying the context object and let `@AfterMapping` adjust, keeping one mapping method. The judgment call: use distinct methods/types when the *shape* differs (the common case, and the safer one for security), and `@Context` + lifecycle hooks when only *values* differ. Avoid a single method with internal branching — it defeats compile-time guarantees and makes the projection's contract invisible.

#### Q67. [Practical] After deploying, an entity→DTO mapping intermittently throws `LazyInitializationException`. How is MapStruct involved and how do you fix it properly?

MapStruct isn't the *cause* here, but it's the trigger, and understanding that distinction is the whole answer. A MapStruct mapper for `Order` → `OrderDto` that maps a lazy `@OneToMany` `items` association generates a loop that calls `order.getItems()` and iterates it. If the mapping runs *outside* the Hibernate session/transaction (e.g. in the controller layer after the `@Transactional` service method returned), touching the lazy collection initializes it with no open session and Hibernate throws `LazyInitializationException`. It's intermittent because it only fires when that association wasn't already initialized.

The proper fixes are about *where and how* the data is loaded, not about MapStruct configuration. The cleanest is to **map inside the transactional boundary** — perform the mapping in the `@Transactional` service method while the session is open, returning the fully-populated DTO. Alternatively, fetch the needed associations eagerly for that use case via a `JOIN FETCH` query or an entity graph, so the collection is already initialized before mapping.

```java
@Transactional(readOnly = true)
public OrderDto getOrder(Long id) {
    Order order = repo.findWithItems(id);     // JOIN FETCH items -> initialized in-session
    return orderMapper.toDto(order);          // mapping touches items while session is open
}
```

The anti-patterns to avoid: do *not* reach for `@MappingTarget`/lazy hacks or `hibernate.enable_lazy_load_no_trans` to paper over it — that masks N+1 problems and unbounded loading. The senior framing: MapStruct surfaces an architectural smell (mapping a persistence entity outside its transaction), and the right fix is to map within the session or shape the fetch to the use case. This is also an argument for not exposing JPA entities directly to the mapping layer at the boundary — projecting via a query into a flat structure first sidesteps lazy traps entirely.

#### Q68. [Practical] How do you handle bidirectional JPA associations (parent↔children) in MapStruct so the back-references are set correctly when persisting?

When mapping a `OrderDto` with a list of `ItemDto` into an `Order` entity, the trap is that each mapped `Item` must have its `order` back-reference set, or JPA will persist orphaned items or fail foreign-key constraints. MapStruct's collection handling helps here through **adder methods**: if `Order` exposes `addItem(Item)` that also does `item.setOrder(this)`, MapStruct (with `CollectionMappingStrategy.ADDER_PREFERRED`) generates `order.addItem(item)` per element, so the back-reference is maintained automatically.

```java
public class Order {
    private final List<Item> items = new ArrayList<>();
    public List<Item> getItems() { return items; }
    public void addItem(Item i) { items.add(i); i.setOrder(this); }   // maintains both sides
}

@Mapper(componentModel = "spring",
        collectionMappingStrategy = CollectionMappingStrategy.ADDER_PREFERRED)
public interface OrderMapper {
    Order toEntity(OrderDto dto);
    Item  toItem(ItemDto dto);

    // After collection is built, ensure any remaining back-refs are consistent (belt-and-suspenders)
    @AfterMapping
    default void linkItems(@MappingTarget Order order) {
        order.getItems().forEach(i -> i.setOrder(order));
    }
}
```

If you *can't* add an adder (third-party entity, or the team prefers setters), the robust fallback is the `@AfterMapping` shown above: after MapStruct populates the collection, iterate and set the parent reference explicitly. This is also where `@MappingTarget` matters for *updates* — for a managed entity you typically map into the existing collection (getter-based population, Q36) so Hibernate's tracked collection instance is preserved rather than replaced, which avoids orphan-removal and dirty-checking surprises.

The production reality I'd stress: this is less about MapStruct mechanics and more about JPA association ownership. MapStruct will faithfully build whatever object graph you tell it to, but *which side owns the relationship* and *whether orphan removal/cascade is configured* determines whether the persist succeeds. I'd pair the mapper with an integration test that actually persists and re-reads, because back-reference bugs only manifest at the database boundary, not in a pure mapping unit test.

#### Q69. [Coding] Write a mapper that conditionally maps a nested object only when a flag/validity condition holds, using `@Condition`, and explain when this beats post-filtering.

**Problem:** An `Account` has an `Address`, but you should only copy it into the DTO when the address is actually valid (non-blank postal code); an invalid/placeholder address should leave the DTO's address null rather than copy garbage.

```java
@Mapper(componentModel = "spring")
public interface AccountMapper {

    AccountDto toDto(Account account);
    AddressDto toAddressDto(Address address);

    // Called by the generated code to decide whether to map the 'address' property at all
    @Condition
    default boolean hasValidAddress(Address address) {
        return address != null
            && address.getPostalCode() != null
            && !address.getPostalCode().isBlank();
    }
}
```

Generated logic is roughly `if (hasValidAddress(account.getAddress())) dto.setAddress(toAddressDto(account.getAddress()));` — the write simply doesn't happen when the condition is false.

- **Why `@Condition` beats post-filtering:** with an `@AfterMapping` post-filter you'd map the address first and then null it out, which is wasteful and — critically — *wrong in update/merge scenarios* because you'd transiently overwrite an existing valid target address with a placeholder before "fixing" it. `@Condition` prevents the write entirely, so existing target state is preserved cleanly.
- **Time/Space:** O(1) extra per property; the predicate runs once per evaluated property (per element in a collection), so it must be cheap and side-effect free.
- **Edge cases:** the condition receives `null` and must handle it (it does); for collections the condition runs per element, so an expensive validity check turns a tight loop into a performance problem — keep it pure and O(1).

The judgment call: reach for `@Condition` when "should this be mapped?" is genuine business logic beyond "is it null" (blank-as-absent, validity gating, feature flags), especially on update/PATCH mappings. For simple null-skipping, `nullValuePropertyMappingStrategy = IGNORE` is lighter weight; `@Condition` is the tool when null-vs-present isn't a rich enough question.

#### Q70. [Practical] An interviewer says "our mapper unit tests pass but production data is occasionally mangled." What gaps between unit tests and production would you investigate?

The framing tells me the *mapping logic* is probably fine in isolation but the *inputs or surrounding context* in production differ from what the tests exercise — so I'd hunt the gap between the test's synthetic inputs and real-world data. The most common gaps: tests use fully-populated, well-formed objects, while production has nulls in nested fields, blank-vs-null strings, unexpected enum values from upstream systems, locale/timezone-dependent date parsing, and numeric edge cases (negative, overflow, scale). A `dateFormat` mapping that works on `"2024-01-15"` in tests can mis-parse `"15/01/2024"` from a different locale in production.

I'd also look at *update/merge* paths specifically, because they're under-tested relative to create paths. A `nullValuePropertyMappingStrategy = IGNORE` PATCH mapper behaves correctly on the create test but "mangles" data in production when a client sends an unexpected partial payload — the test never sent a partial. And I'd check enum mappings: a `@ValueMapping(ANY_REMAINING, NULL)` silently turns an unrecognized upstream status into null, which looks fine until the upstream adds a value.

```java
// Tests that would have caught it: feed adversarial/edge inputs, not happy-path objects
@Test void nullNestedFieldDoesNotNpeAndDtoFieldIsNull() { ... }
@Test void roundTripPreservesValue() { assertEquals(x, mapper.toEntity(mapper.toDto(x))); }
@Test void unknownEnumMapsToSentinelNotSilentNull() { ... }
```

The systemic fixes I'd recommend: add round-trip and property-based tests (generate random/edge inputs), test the *update* mappers with partial payloads, and add golden-file tests over representative *production-shaped* samples. The meta-point for the interviewer: passing unit tests prove the mapping does what the test author imagined; production mangling means reality has shapes the author didn't imagine, so the remedy is adversarial inputs and pinning behavior against real-world samples, not more happy-path assertions.

### 🟠 Advanced — extended

#### Q71. [Practical] As a tech lead, what conventions and guardrails would you put in a team "MapStruct style guide" to prevent the most common production incidents?

I'd write a short, opinionated guide that encodes the failure modes I've actually seen burn teams, because MapStruct's flexibility means each team reinvents the same mistakes. The non-negotiables: (1) every mapper references a shared `@MapperConfig` with `componentModel = "spring"`, `unmappedTargetPolicy = ERROR` (in CI), and `injectionStrategy = CONSTRUCTOR`; (2) declare nested/element mapping methods explicitly — never rely on inline generation — for predictability and cycle safety; (3) no `expression = "java(...)"` beyond trivial one-liners — promote logic to named `default`/`uses` methods.

```java
// The one config every mapper must use
@MapperConfig(
    componentModel = "spring",
    unmappedTargetPolicy = ReportingPolicy.ERROR,
    injectionStrategy = InjectionStrategy.CONSTRUCTOR,
    uses = { MoneyConverter.class, DateConverter.class }
)
public interface TeamMapperConfig {}
```

For *security and correctness*: (4) outbound (entity→DTO) mappings that touch sensitive types use `@BeanMapping(ignoreByDefault = true)` allowlisting, with a test asserting secrets are absent; (5) PATCH mappers document their null semantics explicitly and use `IGNORE` or `JsonNullable` deliberately; (6) any unit-conversion (money, time) lives in a single shared, tested converter, never inline. For *operability*: (7) suppress the generator timestamp for reproducible builds; (8) pin one MapStruct version across all modules.

The guardrails that make the guide stick are *automated*: ArchUnit tests asserting every `@Mapper` uses the shared config and that DTOs don't appear in domain packages, plus CI running `unmappedTargetPolicy = ERROR`. A style guide nobody enforces is decoration; the leverage is turning each convention into a failing build. I'd keep the doc to one page with rationale per rule, because engineers follow rules they understand and ignore rules that read like edicts.

#### Q72. [Practical] You're migrating a large service from ModelMapper to MapStruct incrementally. Describe a safe migration strategy that keeps the service shippable throughout.

A big-bang swap of every mapping is the high-risk path; I'd migrate incrementally behind a stable seam so the service stays green and shippable on every commit. The enabling move is that both libraries can coexist — MapStruct's annotations and ModelMapper's runtime instance don't conflict — so I introduce MapStruct alongside ModelMapper and migrate one mapping at a time, hottest paths first (where the reflection cost and incident risk are highest).

For each mapping I migrate, I'd write a **characterization test** first: capture ModelMapper's current output on representative inputs (including edge cases), then assert the new MapStruct mapper produces equivalent output. This pins behavior so the swap is verifiably equivalent, not a hope. I'd hide the choice behind an interface (`UserMapper` with a ModelMapper-backed impl and a MapStruct-backed impl) or simply replace call sites one at a time, since MapStruct mappers are just beans.

```java
// Characterization test pins old behavior before switching the implementation
@Test void mapstructMatchesModelMapperForRepresentativeInputs() {
    for (User u : samples()) {
        assertEquals(modelMapper.map(u, UserDto.class), mapStructMapper.toDto(u));
    }
}
```

The sequencing: migrate hot/latency-sensitive mappings first to capture the performance and reliability win early, leave genuinely dynamic mappings (unknown-shape, generic admin tooling) on ModelMapper or hand code since MapStruct can't express them (Q38), and remove ModelMapper only once it has zero remaining call sites. I'd turn on `unmappedTargetPolicy = ERROR` *only* for newly-migrated mappers initially, so the compile-time safety benefit accrues without blocking on un-migrated code. The leadership angle: incremental migration with characterization tests lets me ship continuously, demonstrate value per mapping, and back out a single mapping if it regresses — far safer than a quarter-long rewrite branch.

#### Q73. [Practical] A teammate reports that adding a harmless helper method to a shared converter suddenly broke unrelated mappers with "Ambiguous mapping methods found." Diagnose and prevent this.

This is a real and frightening "spooky action at a distance" symptom, and the cause is MapStruct's *type-based* method selection. When a converter is on a `uses` list, *all* its methods become candidates for any mapping of matching types across every mapper that uses it. If the teammate added a method whose signature (`SomeType` → `OtherType`) now matches a type pair that another mapper was previously resolving unambiguously via a single candidate, there are suddenly two equally-specific candidates and MapStruct refuses to guess — it raises the compile error (Q27's mechanism, hitting an unrelated mapper).

The diagnosis is fast: the error message names the ambiguous type pair and the two candidate methods. I'd confirm that the newly-added method shares a source/target type with an existing one and is reachable through the same `uses` graph. The immediate fix is to **disambiguate by qualifying** — annotate the convertible-type helpers with `@Named` and have the affected `@Mapping`s reference `qualifiedByName`, so selection becomes explicit rather than inferred.

```java
@Component class Converters {
    @Named("toIsoDate")   String isoDate(LocalDate d)  { return d.toString(); }
    @Named("toShortDate") String shortDate(LocalDate d){ return ...; }   // the new method -> ambiguity
}
// Affected mapper must now qualify which LocalDate->String it wants:
@Mapping(target = "date", source = "createdOn", qualifiedByName = "toIsoDate")
```

To *prevent* recurrence, the team convention should be: **always `@Named`-qualify any helper whose type pair could collide** (especially common pairs like `LocalDate`→`String`, `Long`→`String`), and reference helpers via `qualifiedByName` rather than relying on there being exactly one candidate. The deeper lesson I'd teach: a shared `uses` converter is effectively global mapping state, so adding a method to it has non-local effects — treat shared converters as a public API and qualify defensively, exactly as you'd avoid overloading methods in a widely-used utility class.

#### Q74. [Practical] How would you measure whether MapStruct mapping is actually a meaningful cost on a hot path, and what would you do if it were?

I'd refuse to optimize on intuition and instead *measure*, because MapStruct generates plain get/set code that is almost never the bottleneck — but "almost never" isn't "never," and the discipline is to prove it. I'd profile the hot path with an async profiler (async-profiler / JFR) under representative load and look at where wall-clock and CPU actually go. For a focused comparison I'd write a JMH microbenchmark mapping representative object graphs at scale, comparing the mapper against hand-written mapping to quantify any delta.

```java
@Benchmark
public OrderDto mapStruct(State s) { return s.mapper.toDto(s.order); }  // vs a hand-written baseline
```

If — rarely — mapping shows up meaningfully, the causes are usually *not* MapStruct's core get/set code but things layered on it: an `expression`/`@AfterMapping`/`@Condition` doing expensive work per element, deep nested graphs mapping far more data than the caller needs, collection mappings materializing huge lists, or — most often — the mapping being a *symptom* of over-fetching (mapping an entire entity graph when the endpoint needs five fields). The fixes follow the cause: trim the DTO to only needed fields, project at the query level (Spring Data projections / `JOIN FETCH` the exact data) so you map less, make condition/lifecycle predicates O(1) and pure, and avoid `Stream` mappers that defer cost onto callers.

The honest senior take: in the overwhelming majority of services, mapping is a rounding error next to database I/O and serialization, so the win is almost always "fetch and map less data," not "make the mapper faster." If I genuinely had a mapping-bound hot path (high-throughput in-memory transformation, no I/O), MapStruct is already near hand-written speed, so the remaining lever is reducing *what* is mapped — and I'd be skeptical of any proposal to micro-optimize the mapper before the profiler points there.

#### Q75. [Practical] Your OpenAPI-generated DTOs use `JsonNullable` and the generated models change on each spec update. How do you keep MapStruct mappings robust against regenerated DTOs?

When DTOs are *generated* from an OpenAPI spec, the mapping layer sits between two moving targets — the spec-driven DTOs and your stable domain — so robustness means making the mapper *fail loudly when the contract drifts* rather than silently mismapping. The single most important guardrail is `unmappedTargetPolicy = ERROR`: when a spec update adds a field to a request DTO that should populate the entity, the build breaks until someone maps it deliberately, instead of the new field silently going nowhere.

For the `JsonNullable` mechanics, MapStruct treats it as a presence-bearing wrapper, so PATCH semantics work — but I'd standardize how it's handled in a shared config/converter so every mapper treats `undefined()` (absent) and `of(null)` (explicit clear) consistently, rather than each mapper reinventing it. I'd also keep the *generated* DTOs in their own package/module and map them to hand-owned domain types at the boundary, so a regenerated DTO can't leak its churn into the domain.

```java
@Mapper(config = ApiMapperConfig.class)   // shared: ERROR policy + JsonNullable handling
public interface CreateUserMapper {
    // spec adds a field -> build fails here until mapped (or explicitly ignored)
    User toEntity(CreateUserRequest req);
}
```

The operational practice that ties it together: regenerate the DTOs in CI and run the build so a spec change that breaks mapping is caught *in the PR that updates the spec*, not in a later integration failure. I'd pair this with round-trip tests on the stable domain side. The trade-off is that `ERROR` makes spec churn noisier — every field addition forces a mapping decision — but that noise *is* the value: it converts "the API contract changed and our mapping silently dropped a field" from a production incident into a red build with a clear owner.

#### Q76. [Practical] Describe how you'd debug "MapStruct generates an empty implementation that maps nothing" specifically in a CI environment where it builds fine locally.

A "maps nothing in CI but fine locally" split is a strong signal of an *annotation-processing environment difference*, not a logic bug, so I'd compare the two environments' processing setup rather than the mapper code. The classic cause is processor *ordering/visibility* differing between a fresh CI build and an incremental local one: locally, a previous build may have left a correct `*Impl` on disk (stale but right), masking a misconfiguration that only shows on CI's clean build. The fix begins with reproducing CI locally via a clean build (`mvn clean` / `gradle --rerun-tasks`) so I see the same empty impl.

The usual root causes, in order: (1) **Lombok ordering** — if the entities use Lombok and `lombok-mapstruct-binding` is missing or the processor order differs, MapStruct runs before accessors exist and generates an empty impl mapping nothing; this often hides locally because of stale generated sources. (2) The `mapstruct-processor` not being on CI's `annotationProcessorPaths` (e.g. a profile-specific or IDE-only configuration that CI doesn't activate). (3) A different JDK/toolchain on CI that changes processor discovery.

```bash
# Reproduce CI's clean build locally, then inspect what was actually generated
mvn -q clean compile
cat target/generated-sources/annotations/**/UserMapperImpl.java   # empty body == accessors not seen
# Add verbose processor logging to see whether MapStruct found any properties
mvn -q clean compile -Amapstruct.verbose=true
```

If the impl body is empty, MapStruct saw a propertyless bean — which almost always means Lombok accessors weren't visible at processing time. The durable fix is to make the processor configuration explicit and identical everywhere (declare all processors on `annotationProcessorPaths`/`annotationProcessor`, include the Lombok binding) and add `mvn clean` to CI so it never benefits from stale artifacts. The meta-lesson I'd emphasize: "works locally, fails in CI" for code generation is nearly always stale incremental state masking a processor-path/ordering problem — the diagnostic instinct should be "clean build + read the generated source," not "re-read the mapper."

#### Q77. [Coding] Implement a mapper that flattens a deeply nested source into a flat DTO and a reverse mapper that unflattens it, handling nulls along the path safely.

**Problem:** `Customer` has `address.geo.lat`/`lng`; the DTO is flat (`latitude`, `longitude`, `city`). Reading a nested path must not NPE if `address` or `geo` is null, and the reverse must reconstruct the nested objects.

```java
@Mapper(componentModel = "spring",
        nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS)   // guard each step of the path
public interface CustomerMapper {

    // Flatten: nested source paths -> flat targets; ALWAYS null-checks address/geo before access
    @Mapping(target = "city",      source = "address.city")
    @Mapping(target = "latitude",  source = "address.geo.lat")
    @Mapping(target = "longitude", source = "address.geo.lng")
    FlatCustomerDto toFlat(Customer c);

    // Unflatten: flat fields -> nested targets; MapStruct creates the intermediate objects
    @Mapping(target = "address.city",     source = "city")
    @Mapping(target = "address.geo.lat",  source = "latitude")
    @Mapping(target = "address.geo.lng",  source = "longitude")
    Customer toEntity(FlatCustomerDto dto);
}
```

- **Why `nullValueCheckStrategy = ALWAYS`:** the default (`ON_IMPLICIT_CONVERSION`) only guards when a type conversion happens; for a same-type nested read like `address.geo.lat`, an intermediate null `address` or `geo` would NPE. `ALWAYS` makes MapStruct emit `c.getAddress() != null && c.getAddress().getGeo() != null` guards, so a missing path yields a null target field instead of a crash.
- **Reverse construction:** for `target = "address.geo.lat"`, MapStruct instantiates `Address` and `Geo` as needed and sets the leaf — you don't write the `new Address()` plumbing.
- **Time/Space:** O(depth) per field; no extra allocation beyond the constructed nested objects on the reverse path.
- **Edge cases:** if *all* of `latitude`/`longitude`/`city` are null on the reverse, you may get an empty-but-non-null `Address`/`Geo` — decide whether that's acceptable or add an `@AfterMapping` to null out a fully-empty `address`. With records as the nested types, the reverse "unflatten" must build via constructors, so ensure the nested types support the canonical-constructor path.

The judgment call I'd flag: deep flattening is convenient but couples the DTO to the source's internal structure, so a refactor of `address.geo` ripples into the mapper. For stable shapes it's fine; for volatile ones I'd consider an explicit intermediate mapping method per nested type so the coupling is localized and the nested mapper is reusable and testable.

#### Q78. [Practical] How do you keep generated MapStruct sources out of code coverage, static analysis, and SonarQube noise without losing real coverage signal?

Generated mappers can wreck your quality metrics two ways: they either inflate "uncovered lines" (the generated `*Impl` you didn't unit-test directly) or, if naively included, dominate static-analysis findings with rule violations you can't fix because you don't own the source. The clean approach is to exclude *generated* sources from coverage and static analysis while still asserting mapper *behavior* through tests on the interface — you keep the real signal (does the mapping produce correct output?) and drop the noise (is every generated null-guard branch covered?).

For coverage (JaCoCo) and Sonar, exclude the generated path explicitly:

```xml
<!-- JaCoCo: don't count generated *Impl in coverage -->
<configuration>
  <excludes><exclude>**/*MapperImpl.*</exclude></excludes>
</configuration>
```
```properties
# sonar-project: exclude generated sources from analysis and coverage
sonar.exclusions=**/generated-sources/**,**/*MapperImpl.java
sonar.coverage.exclusions=**/*MapperImpl.java
```

A MapStruct-friendly touch: add `@Generated` to the output (MapStruct already annotates generated classes with `javax.annotation.processing.Generated` / `javax.annotation.Generated` depending on JDK), which many tools honor for automatic exclusion — so configuring the analyzer to skip `@Generated` types is a more robust, path-independent exclusion than glob patterns.

The principle I'd defend: you should still get coverage *credit and signal* for mapping logic, but via tests that exercise the mapper's public methods (the generated impl runs as a side effect), not by demanding line coverage of generated branches you can't meaningfully test. The anti-pattern is committing generated sources into the repo and then suppressing thousands of findings one by one — instead, exclude by `@Generated`/path and invest the testing effort in the *non-trivial* transformations (renames, conversions, null behavior) where bugs actually live.

#### Q79. [Practical] A mapping that uses `dateFormat` works in development but produces wrong dates in production. What are the likely causes and how do you make date/time mapping robust?

Date bugs that appear only in production almost always trace to *environment-dependent* behavior that the dev machine masked — locale, timezone, and the legacy `Date`/`SimpleDateFormat` machinery `dateFormat` can fall back to. `@Mapping(... dateFormat = "...")` for `String`↔`Date` conversions uses a `SimpleDateFormat`, which is *locale- and timezone-sensitive* and applies the JVM's default timezone if the pattern doesn't pin one. If dev runs in UTC and production in another zone (or vice versa), the same string parses to a different instant, shifting dates across day boundaries.

The robust fix is to map to **`java.time` types** (`LocalDate`, `LocalDateTime`, `Instant`, `OffsetDateTime`) rather than legacy `Date`, and to be explicit about zone. For zone-bearing conversions, prefer `OffsetDateTime`/`Instant` so the offset is carried in the data, not inferred from the JVM. When you must format, do it through a controlled converter that pins locale and zone rather than relying on `dateFormat`'s defaults.

```java
@Component class DateConverter {
    private static final DateTimeFormatter ISO =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX").withLocale(Locale.ROOT);

    @Named("toIso") String format(OffsetDateTime t) { return t == null ? null : t.format(ISO); }
    @Named("parseIso") OffsetDateTime parse(String s) { return s == null ? null : OffsetDateTime.parse(s, ISO); }
}
@Mapper(componentModel = "spring", uses = DateConverter.class)
public interface EventMapper {
    @Mapping(target = "occurredAt", source = "ts", qualifiedByName = "parseIso")
    EventDto toDto(Event e);
}
```

The operational hardening: set the JVM/container timezone explicitly (`-Duser.timezone=UTC`) and locale (`Locale.ROOT`) so dev and prod agree, store and transport timestamps as offset-aware ISO-8601, and add tests that run under a *non-UTC* default timezone to catch zone-dependence. The senior framing: `dateFormat` is convenient but quietly depends on ambient JVM state, which is exactly the kind of dependency that differs between environments and corrupts data silently — for anything beyond a trivial date-only field, route date/time through `java.time` and an explicit, locale/zone-pinned converter.

#### Q80. [Practical] You inherited mappers with `componentModel = "default"` that call `Mappers.getMapper(...)`, but the app is Spring-based and some mappers now need injected services. How do you migrate them safely?

The inherited setup works only as long as the mappers are dependency-free, because `Mappers.getMapper` instantiates the impl *outside* Spring with a no-arg constructor (Q24). The moment a mapper needs an injected service or a `uses` mapper that is itself a Spring bean, the `Mappers.getMapper` path leaves those collaborators null and you get NPEs. So the migration is forced by the new requirement, and the goal is to flip these to the Spring component model without breaking existing call sites in one shot.

The safe sequence: (1) change the mapper(s) to `componentModel = "spring"` (ideally via a shared `@MapperConfig`); (2) replace every `XMapper.INSTANCE` / `Mappers.getMapper(XMapper.class)` usage with constructor injection of the bean; (3) delete the now-misleading `INSTANCE` field so no one accidentally bypasses Spring again. I'd do this mapper-by-mapper, leaning on the compiler — removing `INSTANCE` makes every old call site a compile error, which is a *feature*: it enumerates exactly what I must migrate.

```java
// Before (default model, static lookup, can't inject)
@Mapper public interface UserMapper {
    UserMapper INSTANCE = Mappers.getMapper(UserMapper.class);  // delete this
    UserDto toDto(User u);
}
// After (spring model, injectable, can use @Autowired services / uses mappers)
@Mapper(config = TeamMapperConfig.class)   // componentModel = "spring"
public interface UserMapper { UserDto toDto(User u); }
```

The trap to avoid mid-migration is leaving `componentModel = "spring"` *and* keeping `Mappers.getMapper` call sites — that combination compiles but creates a non-Spring instance whose injected dependencies are null, the worst of both worlds (Q24). So the `INSTANCE` deletion must accompany the component-model switch, not lag behind it. For any library code that genuinely runs *without* a Spring context, I'd keep those mappers on `default` and dependency-free, drawing a clear line between "framework-wired application mappers" and "standalone library mappers" rather than mixing ownership models in one type.

### 🔴 Expert — extended

#### Q81. [Practical] Post-incident: a release silently dropped a field from an outbound API response, breaking a downstream consumer. The mapper compiled clean. Walk through root cause and the prevention you'd institutionalize.

I'd reconstruct the incident as a chain and then attack each link. The proximate cause: a refactor renamed or removed a source property feeding the DTO, and because `unmappedTargetPolicy` was the default `WARN`, MapStruct logged a warning that nobody saw in the build noise and produced a DTO with that field silently null. The contributing cause: there was no contract test asserting the outbound response shape, so nothing caught the missing field before it reached the downstream consumer. The systemic cause: the mapping layer wasn't treated as the API contract it actually is.

The immediate remediation is `unmappedTargetPolicy = ERROR` so a future unmapped target *fails the build*. But that alone wouldn't catch a field that's *mapped but null because the source went null*, so I'd add **consumer-driven contract tests** (Pact/Spring Cloud Contract) or at minimum golden-file tests over representative responses, asserting the field is present and populated. For outbound mappings I'd also adopt `@BeanMapping(ignoreByDefault = true)` allowlisting so the set of emitted fields is explicit and a removal is visible in the diff.

```java
@MapperConfig(unmappedTargetPolicy = ReportingPolicy.ERROR)   // step 1: build fails on unmapped target
public interface ResponseMapperConfig {}

// step 2: golden/contract test that fails when a field disappears or goes null
@Test void responseContainsRequiredFields() {
    var dto = mapper.toResponse(sample());
    assertNotNull(dto.getDiscountCode());      // the field that was dropped
}
```

The institutional fix is process, not just config: treat DTO/mapper changes as API changes subject to contract tests in CI, run `unmappedTargetPolicy = ERROR` on the merge gate, and add a lightweight schema/contract check between producer and consumer. The post-mortem lesson I'd write up: MapStruct *gave* us the warning and *offers* the ERROR gate — the incident was an organizational failure to treat the mapper as a contract boundary, and the durable fix is making the build and the contract tests enforce that boundary so a human forgetting can't cause silent data loss.

#### Q82. [Practical] How do you operate MapStruct across a polyglot/multi-team monorepo where mapper conventions, versions, and shared converters must stay consistent at scale?

At monorepo scale the enemy is *drift*: dozens of teams each picking their own component model, MapStruct version, null strategy, and copy-pasting converters that diverge. The operating model I'd put in place centralizes the *policy* and the *version* while letting teams own their domain mappers. A single root `@MapperConfig` (or a small set, e.g. one per layer) defines component model, `unmappedTargetPolicy`, injection strategy, and the canonical `uses` converters; teams reference it via `@Mapper(config = ...)` rather than re-specifying attributes.

Version consistency is enforced by a **dependency BOM / version catalog**: one place pins `mapstruct` and `mapstruct-processor` (which must match exactly), and the lombok binding, so no module can drift. Shared converters (money, date, common enums) live in a small, stable, low-churn module that everything depends on — deliberately low-churn because, as established earlier, downstream modules recompile when an upstream mapper interface changes, so a chatty shared module slows everyone's incremental builds.

```
//root: enforce one config + one version everywhere
platform/mapping-config   -> TeamMapperConfig (@MapperConfig), shared converters (stable, low churn)
platform/bom              -> pins mapstruct + mapstruct-processor to ONE version
team-orders/...           -> @Mapper(config = TeamMapperConfig.class)   // no per-mapper policy
team-billing/...          -> @Mapper(config = TeamMapperConfig.class)
```

The enforcement layer is automation, because conventions don't survive on documentation alone in a large org: ArchUnit/Checkstyle rules in the shared CI assert every `@Mapper` references the approved config and that generated DTOs don't leak into domain packages; the BOM blocks version drift; and `unmappedTargetPolicy = ERROR` runs on the merge gate org-wide. The trade-off is reduced per-team flexibility, which I'd manage by making the shared config genuinely sensible (so teams rarely need to deviate) and providing an escape hatch (a documented process to override an attribute with justification) rather than a hard wall. The staff-level point: consistency at scale is a *platform* problem solved with shared config, a pinned version, automated enforcement, and a stable converter module — not something you can achieve by asking each team to follow a wiki page.

#### Q83. [Practical] Production shows occasional `IllegalArgumentException` from an enum mapping after an upstream system added a new status. Root-cause it and design the mapping so this never pages you again.

The exception is the *intended* behavior of a `THROW_EXCEPTION` (or default unmatched) enum mapping meeting an input it wasn't told about — so the root cause isn't a MapStruct bug, it's a *policy mismatch*: an enum that receives values from an external system you don't control was configured to fail-fast on unknown values. When the upstream added a status, your `@ValueMapping(ANY_REMAINING, THROW_EXCEPTION)` (or an unmapped constant under default name-matching) did exactly what it was told and threw at runtime.

The design fix is to choose the *right failure posture for the enum's trust level*. For an enum sourced from an external/uncontrolled system, map unknowns to a sentinel (`UNKNOWN`) or `null` via `ANY_REMAINING`, so a new upstream value degrades gracefully instead of paging you. For an *internal* closed enum you own on both sides, you actually *want* the opposite — let it fail (or better, the build to fail) so a new constant forces a deliberate mapping. The bug here is using a closed-world posture for an open-world input.

```java
@Mapper
public interface StatusMapper {
    @ValueMappings({
        @ValueMapping(source = "IN_TRANSIT", target = "SHIPPED"),
        // external input: unknown -> sentinel, NOT an exception that pages on-call
        @ValueMapping(source = MappingConstants.ANY_REMAINING, target = "UNKNOWN")
    })
    ApiStatus toApi(UpstreamStatus s);
}
```

To ensure it never pages again, I'd pair the sentinel mapping with *observability instead of exceptions*: when an `UNKNOWN` is produced, increment a metric/log so the team learns the upstream added a value and can decide whether to map it meaningfully — graceful degradation with a signal, not silence and not a page. The expert framing I'd give: the choice between `THROW_EXCEPTION`, `NULL`, and a sentinel is a *domain trust decision* (Q46), and the incident is a reminder to align the posture with whether the enum's source is inside or outside your control. Fail loud where a surprise means a bug; degrade-with-telemetry where a surprise is just the world changing.

#### Q84. [Practical] You need zero-downtime evolution of a DTO (add/rename/remove fields) consumed by multiple clients. How do MapStruct's compile-time guarantees help, and where must you add discipline beyond MapStruct?

MapStruct's compile-time guarantees cover *your side* of the contract well but not the *wire* contract, and being precise about that boundary is the whole answer. On your side, `unmappedTargetPolicy = ERROR` ensures a newly added DTO field forces a mapping decision (no silent null), `@InheritInverseConfiguration` keeps a rename configured once across both directions, and the generated code makes the *current* mapping fully verifiable. So MapStruct guarantees your code maps what you told it to, completely, at build time.

What MapStruct *cannot* guarantee is backward/forward compatibility for clients consuming the serialized JSON — that's a schema-evolution discipline layered on top. For zero-downtime evolution: **adding** a field is safe if it's optional/nullable (old clients ignore it); **renaming** must be done as add-new-keep-old-then-deprecate (expose both via two `@Mapping`s during a transition window) rather than an in-place rename that breaks old clients; **removing** requires a deprecation period where the field is still emitted (often as null or a default) until telemetry shows no client reads it.

```java
// Rename done safely: emit BOTH the old and new field during the transition window
@Mapping(target = "emailAddress", source = "email")   // new name
@Mapping(target = "email",        source = "email")    // legacy name, still emitted for old clients
UserDto toDto(User u);
```

The disciplines beyond MapStruct: consumer-driven contract tests (Pact) so you *know* which clients read which fields before removing anything, API versioning or additive-only evolution policy, and serialization tolerance (`@JsonIgnoreProperties(ignoreUnknownProperties = true)` on the consumer side). The senior synthesis: MapStruct turns "did I map every field correctly" into a build-time guarantee, which is necessary but not sufficient — zero-downtime evolution also requires treating the *serialized shape* as a versioned contract with explicit add/deprecate/remove phases and telemetry, none of which MapStruct (a code generator, not a contract registry) can provide on its own.

#### Q85. [Practical] A mapper using `@Context` for a per-request cache works in tests but corrupts data under concurrent load in production. Diagnose the concurrency bug and prescribe the correct pattern.

The symptom — fine in single-threaded tests, corrupt under concurrent production load — is the signature of *shared mutable state* in something that's supposed to be per-invocation. The likely diagnosis: the `@Context` object (a cache, a `CycleAvoidingMappingContext`, or a request-scoped accumulator) was mistakenly made a *singleton* — instantiated once (a `@Bean`, a static, or a mapper field) and reused across threads. Tests pass because they run one request at a time; production has many threads sharing that one stateful context, so its internal `HashMap`/`IdentityHashMap` gets concurrent writes, yielding cross-request data bleed and possibly `ConcurrentModificationException` or corrupted entries.

The correct pattern is that any stateful `@Context` must be **created fresh per top-level mapping call** and threaded through, never shared. MapStruct passes the same context instance down the nested mapping graph for that one call, which is exactly the scope you want; the bug is widening that scope to the whole application.

```java
// WRONG: one shared stateful context across all threads -> corruption under load
@Bean CycleAvoidingMappingContext ctx() { return new CycleAvoidingMappingContext(); }  // ❌ singleton
service.map(node, ctx);   // every request mutates the same map

// RIGHT: fresh per call, lives only for this invocation's graph
CategoryDto dto = mapper.toDto(category, new CycleAvoidingMappingContext());  // ✅ per-request
```

To prescribe durably: keep mappers and their lifecycle/condition hooks *pure*, and treat `@Context` strictly as a carrier for per-operation state that you construct at the call site. If you genuinely need a *shared* cache (e.g. a reference-data lookup reused across requests), it must be an *immutable* or properly thread-safe structure (a `ConcurrentHashMap` populated at startup, or an actual caching abstraction), and it should be a *read* path, not a per-request accumulator masquerading as a singleton. The expert lesson, tying to MapStruct's design: generated mappers are thread-safe precisely *because* they're stateless functions over inputs; the moment you smuggle mutable state in via a shared `@Context`, you forfeit that guarantee — so the fix is architectural (scope the state to the invocation), not a lock bolted onto a shared object.

#### Q86. [Practical] Your team wants to forbid implicit lossy conversions (e.g. `Long` id silently becoming a `String`) project-wide after a precision bug. How do you enforce this operationally?

After a precision/format bug caused by an implicit built-in conversion, the goal is to make MapStruct *refuse* to silently coerce across types so every cross-type mapping becomes a deliberate, reviewed decision. The mechanism is `MappingControl`: define a control annotation that permits only `DIRECT` and `MAPPING_METHOD` (and deliberately omits `BUILT_IN_CONVERSION`), then apply it project-wide via the shared `@MapperConfig` so it's the default everywhere rather than opt-in per mapper.

```java
@Retention(RetentionPolicy.CLASS)
@MappingControl(MappingControl.Use.DIRECT)
@MappingControl(MappingControl.Use.MAPPING_METHOD)   // BUILT_IN_CONVERSION omitted on purpose
public @interface NoImplicitConversions {}

@MapperConfig(componentModel = "spring", mappingControl = NoImplicitConversions.class)
public interface StrictMapperConfig {}

@Mapper(config = StrictMapperConfig.class)
public interface AccountMapper {
    // Long id -> String id now FAILS to compile; you must write an explicit, reviewed converter
    AccountDto toDto(Account a);
}
```

Operationally, the rollout mirrors the `unmappedTargetPolicy` migration (Q61): turning this on project-wide in one commit will surface a flurry of compile errors at every existing implicit conversion. So I'd stage it — enable it module-by-module, resolving each error by either writing an explicit `@Named` converter (when the conversion is genuinely needed) or fixing the modeling (when a `Long` becoming a `String` was itself the smell). Each error is a place where the team *thought* types matched but MapStruct was quietly bridging them — exactly the population I want to audit after a precision incident.

The trade-off to acknowledge: stricter control means more boilerplate converters for conversions that were previously free, which can feel like friction. I'd defend it on the basis that the friction is the point — on financial/identifier fields, an implicit `Long`↔`String` or `int`↔`long` is precisely where precision, formatting, and overflow bugs hide, and forcing an explicit, testable converter converts a class of silent corruption into reviewed code. For purely cosmetic non-sensitive fields a team might scope the control to only the money/identity mappers rather than globally — the enforcement should match where the risk actually is.

#### Q87. [Practical] How would you set up regression protection so that any future change to a mapper that alters its output is caught automatically, beyond ordinary unit tests?

Ordinary example-based unit tests catch the cases you thought of; they don't catch a subtle, unintended *change* in output from an innocent-looking refactor. For that I'd layer in **approval/golden-file testing**: serialize the mapper's output for a curated set of representative inputs to an approved snapshot file, and fail the build whenever the output diverges from the approved version. A genuine behavior change then shows up as a *reviewable diff* that a human must explicitly re-approve, turning "did this refactor change the output?" from a hope into a gate.

```java
// Approval test (e.g. ApprovalTests / a hand-rolled golden file)
@Test void orderMappingMatchesApprovedSnapshot() {
    String actual = json.writeValueAsString(mapper.toDto(representativeOrder()));
    Approvals.verify(actual);   // diff vs approved .txt; fails until a human approves the change
}
```

I'd complement golden files with **property-based tests** for invariants that should hold for *all* inputs — most powerfully round-trip identity (`toEntity(toDto(x))` equals `x` for symmetric mappings) and "no required field is null after mapping a fully-populated source." A property-based generator throws thousands of edge-shaped inputs (nulls in nested fields, empty collections, boundary numbers) at the mapper, catching the production-shaped inputs that hand-written examples miss (Q70).

The operational glue: run these in CI on every PR, and — because MapStruct output is deterministic given inputs — also consider diffing the *generated `*Impl`* across versions as a coarse tripwire (Q64) so even a MapStruct upgrade that changes codegen is visible. The senior framing I'd give: unit tests assert *intended* behavior, golden/approval tests assert *unchanged* behavior, and property-based tests assert *invariant* behavior — together they protect against the three distinct ways a mapper regresses (wrong logic, accidental change, unhandled input). For a mapping layer that *is* an API contract, this triad plus consumer-driven contract tests is the regression net I'd consider table stakes.

#### Q88. [Practical] Diagnose: a mapping intermittently returns an object with stale/old values during an update, and it only reproduces in the running app, never in unit tests. What's the likely interaction and fix?

"Stale values on update, only in the running app" points away from the mapping logic (which unit tests exercise correctly) and toward an *interaction with the persistence/transaction layer* that unit tests don't reproduce. The most likely interaction: the update uses `@MappingTarget` to map a DTO into an entity, but the entity being mapped is a *detached or first-level-cache* instance whose state is stale relative to the database, or the mapping happens and then the transaction doesn't flush/commit as expected — so the "old values" are really the persistence context's stale view, not a mapping defect.

Another common variant: with `nullValuePropertyMappingStrategy = IGNORE` (correct for PATCH), a field the caller *intended* to change arrives null (because the client omitted it or a serialization issue dropped it), so MapStruct correctly *ignores* it and the entity keeps its old value — which the user perceives as "stale." Unit tests pass because they hand the mapper a well-formed DTO with the field set; production sends a partial payload. The fix differs by cause, so I'd first determine which by reading the generated impl (is the setter guarded/ignored?) and inspecting the actual inbound DTO and the entity's load state.

```java
@Transactional
public void update(Long id, UserPatchDto dto) {
    User entity = repo.findById(id).orElseThrow();   // managed, fresh in THIS transaction
    mapper.updateEntityFromDto(dto, entity);          // IGNORE keeps old value for null DTO fields
    // no explicit save needed: dirty checking flushes on commit -- IF entity is managed & tx commits
}
```

The durable fixes: ensure the entity is *managed within the active transaction* before mapping (load it inside the `@Transactional` method, not reuse a detached one), confirm the transaction actually commits (no swallowed exception, correct propagation), and — for the partial-payload variant — verify whether the client genuinely sent the field, since `IGNORE` is *supposed* to preserve old values for absent fields. To make this reproducible in tests, I'd add an *integration* test (Testcontainers/`@DataJpaTest`) that maps into a managed entity and asserts the persisted-then-reloaded state, because the bug lives at the MapStruct↔JPA↔transaction seam that a pure mapping unit test cannot see. The meta-lesson: when a mapping bug only reproduces in the running app, suspect the transaction/persistence interaction and the *real* inbound payload, not the mapper's pure logic.

#### Q89. [Practical] You want to standardize a "redact sensitive fields in logs/DTOs" policy across many mappers without repeating `ignore` on every one. What MapStruct mechanisms compose to achieve this, and what are the limits?

The goal is to make "sensitive fields don't leak into outbound DTOs" a *default posture* rather than a per-mapper checklist item that someone inevitably forgets. Several MapStruct mechanisms compose toward this. The strongest is `@BeanMapping(ignoreByDefault = true)` on outbound mappings, which flips to an *allowlist*: nothing is copied unless explicitly listed, so a sensitive field is excluded by default and a developer must consciously opt it in — converting the failure mode from "forgot to ignore a secret → leaked" to "forgot to include a field → merely absent." For a fleet-wide default, this pairs with a shared `@MapperConfig` so the policy and the common converters are inherited everywhere.

For *named* sensitive fields that recur (passwordHash, ssn, internalNotes), a reusable `@Condition` keyed on `@TargetPropertyName` (Q55) can centralize "never map these property names," so one predicate enforces the rule across mappers without repeating `@Mapping(ignore = true)`:

```java
@Condition
default boolean notSensitive(@TargetPropertyName String prop) {
    return !Set.of("passwordHash", "ssn", "internalNotes").contains(prop);
}
```

The *limits* are important and I'd be candid about them. First, `@Condition`/`ignoreByDefault` govern what the *mapper* writes — they do nothing about a `toString()` on the entity or DTO dumping secrets into logs, which is a separate concern solved by careful `toString`/Lombok `@ToString.Exclude`/log scrubbing, not MapStruct. Second, a property-name-based condition is *implicit* — a reader of the mapper interface can't see that `ssn` is special without finding the helper, so it trades explicitness for DRYness. Third, MapStruct can't enforce that *new* sensitive fields get added to the deny-set; that requires process (a convention plus a test).

So the robust answer composes mechanisms *and* guardrails: use `ignoreByDefault = true` allowlisting on the trust boundary as the structural default, a shared config to apply it broadly, and add **tests that assert secrets are absent** from serialized outbound DTOs (the real enforcement) plus ArchUnit rules flagging sensitive types reaching DTO packages. The senior framing: MapStruct makes secure-by-default mapping *achievable and auditable* (explicit, compile-visible field selection), but redaction is ultimately a policy enforced by allowlisting + tests, not a single annotation — and the logging surface is outside MapStruct's scope entirely.

#### Q90. [Behavioral] Tell me about a time a mapping-layer decision caused (or nearly caused) a production incident, and what you changed in how the team works afterward.

**Situation:** On a billing service, a routine refactor renamed an entity field from `discountAmount` to `discount` to match a new domain model. The DTO still had `discountAmount`, the mapper relied on name-based matching, and `unmappedTargetPolicy` was the default `WARN`. The build went green, the warning scrolled past in CI logs, and we shipped a release where every invoice response carried `discountAmount: null`.

**Task:** I owned the incident response once a downstream reconciliation job started flagging mismatches, and then owned the prevention so it couldn't recur.

**Action:** The immediate fix was a one-line `@Mapping(target = "discountAmount", source = "discount")` and a hotfix release. But I treated the *real* fix as systemic: I flipped `unmappedTargetPolicy` to `ERROR` on the merge gate (staged module-by-module so it didn't block the team, exactly as I'd describe rolling out any fleet policy), added golden-file/contract tests over representative invoice responses so a dropped or nulled field fails the build with a reviewable diff, and wrote a one-page mapping convention doc explaining *why* each rule exists. I also moved shared money handling into a single tested converter, since the mapping layer was clearly under-treated for a system that handles money.

**Result:** The class of bug — silent unmapped/null fields surviving to production — became a build failure rather than a customer-facing incident, and the contract tests caught two more would-be regressions within the next quarter. The lesson I carry and repeat to teams: the mapping layer between entities and external DTOs *is* an API contract and a trust boundary, and MapStruct gives you the tools to enforce that at compile time (`ERROR` policy, allowlisting, generated-code transparency) — but tools don't help if the organization treats mappers as throwaway boilerplate. The durable change wasn't a config flag; it was getting the team to treat mappers with the same contract discipline as the API itself.

## 🧩 Extended Questions — Set 3: Coding, Design & Expert Scenarios

### 🟢 Basic — extended

#### Q91. [Coding] Write a mapper that copies a `boolean` flag exposed via an `isX()` getter on the source and a `setX(...)`/no-arg-record component on the target, and handle a `Boolean` (nullable) source feeding a primitive `boolean` target.

**Problem:** A `User` entity exposes `isActive()` (primitive `boolean`) and `getVerified()` returning a nullable `Boolean`. The `UserDto` has primitive `boolean active` and primitive `boolean verified`. Mapping a `null` `Boolean` into a primitive throws `NullPointerException` on unboxing — make it safe.

```java
public class User {
    private boolean active;
    private Boolean verified;                 // nullable
    public boolean isActive() { return active; }
    public Boolean getVerified() { return verified; }
    // setters...
}

public record UserDto(boolean active, boolean verified) {}

@Mapper(componentModel = "spring", nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS)
public interface UserFlagMapper {
    @Mapping(target = "verified", source = "verified", defaultValue = "false") // null Boolean -> false
    UserDto toDto(User u);
}
```

MapStruct resolves `active` automatically: it recognizes the JavaBeans `isActive()` accessor for a `boolean` property (the `AccessorNamingStrategy` treats `is`-prefixed getters as valid for `boolean`/`Boolean`), and feeds it into the record's canonical constructor by component name. No `@Mapping` is needed for `active` because the names and effective types match.

The non-trivial part is `verified`. The source is a boxed `Boolean` and the target is primitive `boolean`, so the generated unboxing (`dto.verified = u.getVerified()`) would NPE when the source is null. Two defenses combine here: `nullValueCheckStrategy = ALWAYS` makes MapStruct emit a guard before reading the source, and `defaultValue = "false"` supplies a defined fallback so a missing flag becomes `false` rather than blowing up. The generated code is conceptually `boolean verified = (u.getVerified() != null) ? u.getVerified() : false;`.

- **Time/Space:** O(1), no allocation beyond the result record.
- **Edge case / design note:** defaulting a tri-state `Boolean` (true/false/unknown) down to a binary primitive *destroys information* — `null` ("we never asked") collapses into `false` ("we asked and the answer is no"). On a compliance or consent field that distinction is legally significant, so the senior call is often to keep the DTO field `Boolean` and let `null` propagate honestly rather than fabricate `false`. Choosing `defaultValue` versus keeping the wrapper is a domain decision, not a mechanical one.

#### Q92. [Coding] Implement a symmetric `toDto`/`toEntity` pair with two renamed fields using `@InheritInverseConfiguration`, then show what breaks if one direction is lossy.

**Problem:** `Product` has `manufacturerName` and `unitCount`; `ProductDto` calls them `brand` and `quantity`. Write both directions configuring the renames once, and explain where inverse inheritance stops being safe.

```java
@Mapper(componentModel = "spring")
public interface ProductMapper {

    @Mapping(target = "brand",    source = "manufacturerName")
    @Mapping(target = "quantity", source = "unitCount")
    ProductDto toDto(Product p);

    @InheritInverseConfiguration   // brand->manufacturerName, quantity->unitCount auto-reversed
    Product toEntity(ProductDto dto);
}
```

`@InheritInverseConfiguration` tells MapStruct to find the method whose source/target types are swapped relative to `toEntity` (here `toDto`) and mechanically invert each `source`/`target` pair. You declare the rename once on the forward method and the reverse direction stays in sync automatically — a single source of truth that prevents the classic bug where someone updates one direction's rename and forgets the other.

The inversion only works for *invertible* mappings. The moment a forward mapping uses `expression`, `constant`, `defaultExpression`, or a many-to-one combination (e.g. `fullName` built from first + last), MapStruct cannot reverse it — there's no algorithm to split `fullName` back into two fields — so it silently *skips* that mapping on the inverse method, leaving the corresponding target unmapped.

```java
// Forward: lossy/combining mapping
@Mapping(target = "displayName", expression = "java(p.getBrand() + \" \" + p.getModel())")
ProductDto toDto(Product p);

@InheritInverseConfiguration   // 'displayName' is NOT reversed; brand & model stay unmapped here
Product toEntity(ProductDto dto);   // -> unmapped-target warning/error on brand, model
```

The seasoned rule: use inverse inheritance for genuinely symmetric DTO↔entity pairs (1:1 renames, format conversions that have inverses), and the moment the two directions diverge — computed fields, lossy conversions, asymmetric defaults — write the reverse `@Mapping`s explicitly. Pairing this with `unmappedTargetPolicy = ERROR` is what makes the failure *loud*: a non-invertible field surfaces as a build error rather than a silent null, forcing you to handle the reverse direction deliberately.

### 🟡 Intermediate — extended

#### Q93. [Coding] Aggregate three source objects (an entity, a pricing snapshot, and a request context) into one DTO, resolving a field-name collision between two sources.

**Problem:** Build an `OrderConfirmationDto` from `Order` (has `id`, `status`), `PriceQuote` (has `total`, `currency`), and `RequestContext` (has `locale`, and *also* an `id` that is the trace id). Two sources expose `id` — you must disambiguate.

```java
@Mapper(componentModel = "spring")
public interface OrderConfirmationMapper {

    @Mapping(target = "orderId",  source = "order.id")        // qualify by parameter to resolve collision
    @Mapping(target = "traceId",  source = "ctx.id")          // the OTHER id
    @Mapping(target = "status",   source = "order.status")
    @Mapping(target = "total",    source = "quote.total")
    @Mapping(target = "currency", source = "quote.currency")
    @Mapping(target = "locale",   source = "ctx.locale")
    OrderConfirmationDto toDto(Order order, PriceQuote quote, RequestContext ctx);
}
```

With multiple source parameters, MapStruct does **not** flatten them into a single namespace — each `@Mapping(source = ...)` is qualified by the parameter name (`order.id`, `ctx.id`). This is exactly how you resolve the `id` collision: a bare `source = "id"` would be ambiguous and fail the build (`Several possible source properties`), so you must prefix with the parameter name. MapStruct generates straightforward reads off each parameter and independent setter calls on the target.

For properties that exist on only one parameter and whose names are unique across all parameters, you can omit the parameter prefix and MapStruct resolves it automatically — but in production I prefer to *always* qualify when there are multiple sources, because adding a colliding field to any parameter later would otherwise turn a previously-unambiguous mapping into a build break far from the change.

- **Time/Space:** O(1) — fixed number of field reads/writes; no allocation beyond the DTO.
- **Edge cases:** any source parameter may be null; with the default `nullValueCheckStrategy`, accessing `quote.getTotal()` when `quote` is null would NPE, so for optional parameters set `nullValueCheckStrategy = ALWAYS` (which guards each parameter access) or guarantee non-null parameters at the call site. The design lesson: multi-source mappers are the right tool for assembling a view from several aggregates, but they make the mapper depend on *all* of them — keep the parameter list small and cohesive, or you've recreated a god-mapper.

#### Q94. [Coding] Map an `Instant` stored in UTC to a formatted local date-time string in a request-supplied time zone, threading the zone via `@Context`.

**Problem:** Entities store timestamps as `Instant` (UTC). The API must render them as `yyyy-MM-dd HH:mm` in the *caller's* time zone, which is only known at request time. The zone must reach every nested mapping without becoming a mapped field.

```java
@Mapper(componentModel = "spring")
public interface EventMapper {

    EventDto toDto(Event event, @Context ZoneId zone);
    List<EventDto> toDtos(List<Event> events, @Context ZoneId zone);  // zone threads into each toDto

    // MapStruct selects this Instant->String converter; @Context is injected automatically
    default String format(Instant ts, @Context ZoneId zone) {
        if (ts == null) return null;
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(zone)
                .format(ts);
    }
}
```

`@Context` passes the `ZoneId` *through* the mapping graph without it being treated as a source to map onto the target. MapStruct threads it automatically into every method that declares a matching `@Context ZoneId` parameter — including the element mappings invoked by `toDtos` — so you set the zone once at the top-level call and it reaches the `format` helper that actually applies it. The generated `format(Instant, ZoneId)` is selected by the normal algorithm because its signature matches an `Instant`→`String` need.

The reason `@Context` is the right tool (rather than a mapped field or a thread-local) is that the zone is cross-cutting ambient data, not part of the domain object. A `ThreadLocal` would work but is invisible, hard to test, and fragile across async boundaries; threading it explicitly as `@Context` keeps the dependency visible in the signature and makes the mapper deterministic in unit tests (`mapper.toDto(event, ZoneId.of("Asia/Kolkata"))`).

- **Time/Space:** O(N) over events; the `DateTimeFormatter` is created per call here — for hot paths, cache a pre-built `DateTimeFormatter.ofPattern(...)` (immutable, thread-safe) as a static and only `.withZone(zone)` per call, since `withZone` is cheap.
- **Edge case:** never use `LocalDateTime` for the UTC storage or you lose the offset; keep storage in `Instant`/`OffsetDateTime` and convert *only* at the presentation boundary (the mapper). Formatting in the wrong layer (e.g. inside the entity) is the classic source of "works in dev, wrong in prod" timezone bugs.

#### Q95. [Coding] Map a `Set<Permission>` enum collection to and from an `int` bitmask, with a reusable converter, and explain the correctness constraints.

**Problem:** A legacy column stores permissions as a packed `int` bitmask; the domain uses `EnumSet<Permission>`. Each `Permission` has a `bit` value. Write both directions.

```java
public enum Permission {
    READ(1), WRITE(2), DELETE(4), ADMIN(8);
    private final int bit;
    Permission(int bit) { this.bit = bit; }
    public int bit() { return bit; }
}

@Component
public class BitmaskConverter {

    @Named("toMask")
    public int toMask(Set<Permission> perms) {
        if (perms == null) return 0;
        int mask = 0;
        for (Permission p : perms) mask |= p.bit();
        return mask;
    }

    @Named("fromMask")
    public Set<Permission> fromMask(int mask) {
        EnumSet<Permission> set = EnumSet.noneOf(Permission.class);
        for (Permission p : Permission.values()) {
            if ((mask & p.bit()) != 0) set.add(p);
        }
        return set;
    }
}

@Mapper(componentModel = "spring", uses = BitmaskConverter.class)
public interface RoleMapper {
    @Mapping(target = "permMask", source = "permissions", qualifiedByName = "toMask")
    RoleEntity toEntity(RoleDto dto);

    @Mapping(target = "permissions", source = "permMask", qualifiedByName = "fromMask")
    RoleDto toDto(RoleEntity entity);
}
```

This is a case where MapStruct has *no* built-in conversion (`Set<Permission>` ↔ `int` is domain-specific), so a `uses` converter is mandatory. The `@Named` qualifiers are required to tell MapStruct exactly which converter applies for each direction — without them, MapStruct can't resolve a `Set<Permission>`→`int` mapping and the build fails with "can't map property."

- **Time/Space:** O(K) where K is the number of permission constants (≤ 32 for `int`, ≤ 64 for `long`) — effectively O(1). `EnumSet` is itself a bitmask internally, so it's a near-free representation.
- **Correctness constraints worth stating in interview:** (1) bit values must be distinct powers of two and never reused/renumbered, or old persisted masks decode wrong — this is a versioning hazard if the enum evolves. (2) An `int` caps you at 32 flags; switch to `long` (64) or a `BitSet`/byte array before you run out. (3) `fromMask` silently *ignores* unknown bits set in the database (defensive — a forward-compat row from a newer service won't crash), but that also means a corrupt mask loses information silently; if integrity matters, validate that `toMask(fromMask(x)) == x` and alert on mismatch. Isolating this in one tested converter is essential because bitmask bugs are silent and security-relevant (decoding `ADMIN` wrong is a privilege bug).

#### Q96. [Coding] Map and filter a collection in one step — produce a `List<ActiveUserDto>` containing only active users, mapped from `List<User>` — and discuss why MapStruct alone can't filter.

**Problem:** Given `List<User>`, you want `List<ActiveUserDto>` that *excludes* inactive users. MapStruct's generated loop maps every element 1:1; it has no notion of dropping elements.

```java
@Mapper(componentModel = "spring")
public abstract class ActiveUserMapper {

    public List<ActiveUserDto> toActiveDtos(List<User> users) {
        if (users == null) return List.of();
        return users.stream()
                    .filter(User::isActive)        // filtering is YOUR responsibility, not MapStruct's
                    .map(this::toDto)              // per-element mapping IS MapStruct's
                    .toList();
    }

    public abstract ActiveUserDto toDto(User u); // generated by MapStruct
}
```

The key insight an interviewer is probing: **MapStruct maps, it does not filter.** Its generated collection loop applies the element mapper to *every* element and adds each result to the target collection — there is no hook to conditionally skip elements (unlike `@Condition`, which gates a single *property*, not a collection *element*). So you express the filtering yourself, and delegate only the per-element transformation to MapStruct.

The cleanest pattern is an `abstract class` mapper: MapStruct generates the abstract `toDto(User)`, and you hand-write the `toActiveDtos` orchestration as a concrete method that streams, filters, and calls the generated mapper. This keeps the boilerplate field-copy generated and tested while making the business rule (which elements survive) explicit, readable, and unit-testable in plain Java.

- **Time/Space:** O(N) time, O(M) space for the M active results. The stream is a single pass.
- **Design note:** resist the urge to push the filter into a `@Condition` or a sneaky `expression` — element selection is a *query/business* concern that belongs in readable code, not buried in mapping config. If the filter is non-trivial or shared, extract it (`UserPredicates.ACTIVE`) so the mapper orchestration stays thin. Returning `List.of()` for a null input (rather than null) spares callers a null check, matching the `RETURN_DEFAULT` ergonomic for the manual case.

#### Q97. [Coding] Flatten a nested source where one nested object needs a *custom* conversion while its siblings flatten directly, using a method-level qualifier.

**Problem:** `Customer` has a nested `Address` (flatten `street`, `city` directly) and a nested `Contact` whose `phone` must be normalized to E.164 before landing in the flat `CustomerDto.phoneE164`. Flatten the simple fields by path and route only the phone through a converter.

```java
@Mapper(componentModel = "spring", uses = PhoneNormalizer.class)
public interface CustomerMapper {

    @Mapping(target = "street",    source = "address.street")
    @Mapping(target = "city",      source = "address.city")
    @Mapping(target = "phoneE164", source = "contact.phone", qualifiedByName = "toE164")
    CustomerDto toDto(Customer c);
}

@Component
public class PhoneNormalizer {
    @Named("toE164")
    public String toE164(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String digits = raw.replaceAll("[^0-9]", "");
        return digits.startsWith("+") ? raw : "+" + digits;  // simplified
    }
}
```

This shows the everyday mix of two resolution paths in one method. The `address.*` fields use **dotted-path flattening**: MapStruct reads `c.getAddress().getStreet()` directly, no converter needed. The phone field uses a **qualified converter**: `qualifiedByName = "toE164"` forces MapStruct to route the `String`→`String` mapping through `PhoneNormalizer.toE164` rather than doing a plain assignment. Because both `phone` and `phoneE164` are `String`, MapStruct would otherwise just copy verbatim — the qualifier is what injects the normalization.

The reason to use a `@Named` converter rather than an `expression = "java(...)"` is testability and reuse: `PhoneNormalizer` is a real Spring bean you unit-test independently and reuse across every mapper that handles phone numbers, and it participates in MapStruct's selection cleanly.

- **Time/Space:** O(L) in the phone string length for the regex; O(1) otherwise.
- **Edge case:** dotted paths NPE if an intermediate (`address` or `contact`) is null under the default null-check strategy; set `nullValueCheckStrategy = ALWAYS` to guard each navigation step, which generates `c.getAddress() != null ? ... : null` along the path. This is the safe default for flattening optional nested graphs.

#### Q98. [Coding] Use an `@ObjectFactory` so the mapper instantiates the correct concrete target (or a no-arg-less type) instead of calling `new`, choosing the subtype based on a discriminator field.

**Problem:** `NotificationDto` must be mapped to one of two entity subtypes — `EmailNotification` or `SmsNotification` — based on `dto.getChannel()`. Neither has a public no-arg constructor MapStruct can use blindly; you want the factory to pick the type.

```java
@Mapper(componentModel = "spring")
public abstract class NotificationMapper {

    @ObjectFactory
    public Notification create(NotificationDto dto) {
        return switch (dto.getChannel()) {
            case EMAIL -> new EmailNotification();
            case SMS   -> new SmsNotification();
        };
    }

    @Mapping(target = "recipient", source = "to")
    @Mapping(target = "body",      source = "message")
    public abstract Notification toEntity(NotificationDto dto);  // uses the factory above
}
```

`@ObjectFactory` overrides *how* MapStruct obtains the target instance. Instead of generating `new Notification()` (impossible if `Notification` is abstract, or wrong if you need a subtype), MapStruct calls your factory first to produce the instance, then proceeds to populate it with the mapped fields via setters. The factory receives the source (and can receive `@Context` and `@TargetType`), so it can branch on a discriminator like `channel`.

This is distinct from `@SubclassMapping` (which maps *source* subtypes to *target* subtypes): here the source is a single flat DTO and the *target* type is chosen by a field value, so an object factory is the right mechanism. The generated `toEntity` does roughly `Notification n = create(dto); n.setRecipient(dto.getTo()); n.setBody(dto.getMessage()); return n;`.

- **Time/Space:** O(1).
- **Edge case / design note:** the `switch` should be exhaustive over the `Channel` enum (a Java 17 `switch` expression *forces* exhaustiveness at compile time — add a new channel and the code won't compile until you handle it, which is exactly the safety you want). If the factory can't decide (null channel), throw a clear exception rather than returning null, because a null from `@ObjectFactory` makes MapStruct produce a confusing downstream NPE. The factory is also the canonical hook for "fetch-or-create" persistence patterns (`em.find` for an existing id, else `new`) — same mechanism, different decision.

#### Q99. [Coding] Map a `Map<String, RawScore>` to a `Map<String, ScoreDto>`, transforming values while preserving keys, and handle the key-collision hazard if keys are also transformed.

**Problem:** Convert a score lookup `Map<String, RawScore>` to `Map<String, ScoreDto>` (values mapped, keys unchanged). Then a variant where keys are upper-cased — show why that's dangerous.

```java
@Mapper(componentModel = "spring")
public interface ScoreMapper {

    // Keys pass through (String->String identity); values mapped via toScoreDto
    Map<String, ScoreDto> toDtoMap(Map<String, RawScore> raw);

    ScoreDto toScoreDto(RawScore r);

    // Variant: transform the KEY too -> collision risk
    @MapMapping(keyExpression = "java(key.toUpperCase())")
    Map<String, ScoreDto> toUpperKeyMap(Map<String, RawScore> raw);
}
```

For `Map`, MapStruct maps keys and values **independently**: it finds (or generates) a key conversion and a value conversion and generates a loop that builds a new map. When keys are the same type (`String`→`String`) it's an identity copy; values route through `toScoreDto` because that element mapper exists. The default target implementation is `LinkedHashMap`, preserving the source iteration order — useful when the order is meaningful (e.g. ranked scores).

The subtle, easy-to-miss hazard is in `toUpperKeyMap`: transforming keys can **collapse distinct source keys into one target key**. If the source contains both `"math"` and `"Math"`, upper-casing produces `"MATH"` twice and one entry silently overwrites the other — and MapStruct will *not* warn you, because from its perspective it just called `put` twice. You've lost data.

- **Time/Space:** O(N) over entries; O(N) for the new map.
- **Edge cases / mitigations:** (1) never transform keys unless the transformation is provably injective over your key space, or detect collisions explicitly (build into a temp structure and assert size equality). (2) A null *key* is illegal in some map types and legal in `HashMap`/`LinkedHashMap` — be deliberate. (3) For value-only mapping prefer the simple form (no `@MapMapping`); reserve `@MapMapping` for genuine key/value formatting needs. The interview-level point: MapStruct gives you key transformation as a feature, but map-key uniqueness is a *semantic* invariant it can't enforce — that responsibility stays with you.

#### Q100. [Coding] Write a complete JUnit 5 + AssertJ test suite for a mapper, covering the cases that actually matter (renames, null/IGNORE behavior, round-trip), without a Spring context.

**Problem:** Demonstrate how you'd unit-test a `spring`-component-model mapper fast (no `@SpringBootTest`) and what assertions earn their keep.

```java
class ProductMapperTest {

    // Instantiate the generated impl directly; pass collaborators via their *Impl.
    private final ProductMapper mapper = new ProductMapperImpl();

    @Test
    void maps_renamed_fields() {
        Product p = new Product();
        p.setManufacturerName("Acme");
        p.setUnitCount(7);

        ProductDto dto = mapper.toDto(p);

        assertThat(dto.getBrand()).isEqualTo("Acme");   // assert the RENAME, not trivial copies
        assertThat(dto.getQuantity()).isEqualTo(7);
    }

    @Test
    void patch_ignores_null_fields() {                  // nullValuePropertyMappingStrategy = IGNORE
        Product existing = new Product();
        existing.setManufacturerName("Old");
        ProductDto patch = new ProductDto();            // brand == null -> must NOT overwrite
        patch.setQuantity(3);

        mapper.update(patch, existing);

        assertThat(existing.getManufacturerName()).isEqualTo("Old"); // preserved
        assertThat(existing.getUnitCount()).isEqualTo(3);            // updated
    }

    @Test
    void round_trip_is_lossless_for_symmetric_fields() {
        Product original = new Product();
        original.setManufacturerName("Acme");
        original.setUnitCount(7);

        Product back = mapper.toEntity(mapper.toDto(original));

        assertThat(back).usingRecursiveComparison()
                        .isEqualTo(original);           // catches asymmetric mappings
    }

    @Test
    void null_source_returns_null() {
        assertThat(mapper.toDto(null)).isNull();
    }
}
```

The strategy worth articulating: because the `*Impl` is ordinary generated code, you instantiate it with `new ProductMapperImpl()` (and inject collaborators by passing their own `*Impl`s, or use `Mappers.getMapper` for the `default` model) — **no Spring context needed**, so the tests run in milliseconds. Reserve `@SpringBootTest` for wiring/integration checks, not mapping logic.

Assert the things that can actually be *wrong*: renamed fields, format/unit conversions, enum value mappings, `@MappingTarget`+`IGNORE` PATCH semantics, null-argument handling, and round-trips (which catch asymmetric or lossy mappings). Do **not** waste tests asserting trivial 1:1 copies — those are the lowest-risk, and `unmappedTargetPolicy = ERROR` already protects you against forgetting a field. AssertJ's `usingRecursiveComparison()` is ideal for round-trip and whole-object equality without writing `equals`. The meta-point: the *first* test is `mvn compile` with `ERROR` policy — most mapping mistakes never reach a unit test because they fail the build.

### 🟠 Advanced — extended

#### Q101. [Practical] Design the mapping layer for a new Spring Boot 3 service from scratch: package structure, config, converters, and the conventions you'd enforce on day one. Walk through your decisions.

I'd design the mapping layer as a *deliberate architectural seam*, not an afterthought, because it sits on the trust boundary between external DTOs and the domain. The package structure separates concerns: `domain` (entities/value objects, no MapStruct annotations leaking in), `api.dto` (request/response DTOs), `api.mapper` (the `@Mapper` interfaces), and `api.mapper.converter` (shared `@Component` converters like money, phone, date). The mappers depend inward on the domain; nothing in the domain depends on a mapper or DTO — that's the dependency direction that keeps the domain pure.

```
com.acme.orders
 ├─ domain/            Order, Money, OrderStatus           (framework-free)
 ├─ api/
 │   ├─ dto/           OrderDto, CreateOrderRequest
 │   ├─ mapper/        OrderMapper, CustomerMapper          (@Mapper(config=Central...))
 │   │   └─ converter/ MoneyConverter, PhoneNormalizer      (@Component, @Named)
 │   └─ controller/
 └─ config/            CentralMapperConfig                  (@MapperConfig)
```

On day one I'd create a single `@MapperConfig` that every mapper references, encoding fleet-wide policy: `componentModel = "spring"`, `injectionStrategy = CONSTRUCTOR`, `unmappedTargetPolicy = ERROR`, sensible null strategies, and a shared `uses` list of converters. This kills configuration drift before it starts — adding a new mapper means `@Mapper(config = CentralMapperConfig.class)` and nothing else. I'd pin one MapStruct version property (1.6.x for JDK 21), keep `mapstruct` on `implementation` and `mapstruct-processor` on the processor path, and add `lombok-mapstruct-binding` if Lombok is in play.

```java
@MapperConfig(
    componentModel = "spring",
    injectionStrategy = InjectionStrategy.CONSTRUCTOR,
    unmappedTargetPolicy = ReportingPolicy.ERROR,
    nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS,
    uses = { MoneyConverter.class, PhoneNormalizer.class })
public interface CentralMapperConfig {}
```

The conventions I'd write down and enforce (via ArchUnit + code review): always declare nested/element mapping methods explicitly (predictable, cycle-safe, testable — Q50); prefer `default`/`uses` methods over `expression`; use `@BeanMapping(ignoreByDefault = true)` for outbound projections of sensitive entities (allowlist, not blocklist); and require a unit test asserting the non-trivial transformations plus a round-trip for symmetric pairs. The trade-off of this much structure is upfront ceremony, but on a service that handles real data it pays back immediately: the build enforces completeness, the domain stays clean, and a new engineer can add a mapper correctly by pattern-matching the existing ones. The senior framing: I'm designing for the *50th* mapper, not the first.

#### Q102. [Practical] Design an anti-corruption layer (ACL) between two bounded contexts where the upstream model is volatile and externally owned. How does MapStruct fit, and what would you build around it?

The goal of the ACL is that the volatile, externally-owned upstream model **never** leaks into my domain — its naming, units, null semantics, and churn stop at a translation boundary. MapStruct is the natural implementation of that boundary because it makes the translation *explicit, compile-checked, and centralized*: every field that crosses the boundary is a deliberate, reviewable `@Mapping`, and `unmappedTargetPolicy = ERROR` forces a conscious decision whenever the upstream adds a field.

I'd structure it as: upstream DTOs live in an `acl`/`infrastructure` package and are treated as untrusted input; a dedicated `*Acl` mapper translates them into my domain model; my domain is defined independently and never imports an upstream type. The mapper is the single chokepoint where renames, unit conversions (cents↔dollars, ms↔seconds), time-zone normalization, and null reconciliation happen.

```java
@Mapper(componentModel = "spring", uses = MoneyConverter.class,
        unmappedTargetPolicy = ReportingPolicy.ERROR,
        unmappedSourcePolicy = ReportingPolicy.WARN)   // notice when upstream gains a field
public interface PartnerOrderAcl {
    @Mapping(target = "amount",  source = "totalCents", qualifiedByName = "centsToDollars")
    @Mapping(target = "buyerId", source = "custNo")
    @Mapping(target = "placedAt", source = "ts", dateFormat = "yyyyMMddHHmmss")
    Order fromPartner(PartnerOrderRecord r);
}
```

Around the mapper I'd build defenses the mapper alone can't provide: (1) **schema/contract tests** against a captured sample of real upstream payloads, so an upstream shape change fails *my* CI rather than corrupting data silently; (2) **validation at the edge** (Bean Validation on the upstream DTO) *before* mapping, because the mapper should translate well-formed input, not sanitize hostile input; (3) **golden-file/property-based tests** specifically on unit conversions, since cents-vs-dollars and ms-vs-seconds bugs are the textbook silent-money-loss incident; (4) raising `unmappedSourcePolicy` to `WARN`/`ERROR` here (unlike normal projections) so a *new upstream field I should be capturing* surfaces instead of being silently dropped.

The trade-off is extra code and tests for a layer that "just copies fields" — but that's precisely the point of an ACL: the cost buys an explicit, versioned contract between contexts and isolates my domain from someone else's churn. The senior insight is treating the ACL mapper as a *published contract with tests*, not boilerplate, because the failure mode (silent data corruption from an upstream change) is exactly the kind that survives to production and costs real money.

#### Q103. [Coding] Implement polymorphic mapping over a *sealed* hierarchy with compile-time exhaustiveness using `@SubclassMapping`, and show what the generated dispatch does.

**Problem:** `sealed interface Shape permits Circle, Rectangle` must map to a `ShapeDto` hierarchy. Adding a new permitted subtype later must *fail the build* until it's mapped.

```java
public sealed interface Shape permits Circle, Rectangle {}
public record Circle(double radius) implements Shape {}
public record Rectangle(double w, double h) implements Shape {}

@Mapper(componentModel = "spring",
        subclassExhaustiveStrategy = SubclassExhaustiveStrategy.COMPILE_ERROR)
public interface ShapeMapper {

    @SubclassMapping(source = Circle.class,    target = CircleDto.class)
    @SubclassMapping(source = Rectangle.class, target = RectangleDto.class)
    ShapeDto toDto(Shape shape);

    CircleDto    toDto(Circle c);
    RectangleDto toDto(Rectangle r);
}
```

`@SubclassMapping` routes each concrete source subtype to a concrete target subtype, and MapStruct generates an `instanceof`-based dispatch in the `toDto(Shape)` method that defers to the matching sub-mapper:

```java
public ShapeDto toDto(Shape shape) {
    if (shape == null) return null;
    if (shape instanceof Circle c)    return toDto(c);
    if (shape instanceof Rectangle r) return toDto(r);
    throw new IllegalArgumentException("Won't map " + shape.getClass());
}
```

The payoff of combining a `sealed` interface with `subclassExhaustiveStrategy = COMPILE_ERROR` is **true compile-time exhaustiveness**. Because `sealed` closes the permitted set, MapStruct can verify at build time that every permitted subtype has a `@SubclassMapping`; add `Triangle implements Shape` and the build *fails* until you add its mapping — exactly like an exhaustive `switch`. This is dramatically safer than a hand-rolled `instanceof` chain or visitor that silently falls through for an unhandled subtype.

- **Time/Space:** O(K) instanceof checks where K = number of subtypes (small, ordered most-specific-first by MapStruct); O(1) space.
- **Trade-off / edge case:** dispatch is runtime `instanceof`, so it can't handle types unknown at compile time — fine for a sealed domain, wrong for an open plugin hierarchy (use a registry/visitor there). With `RUNTIME_EXCEPTION` instead of `COMPILE_ERROR`, an unmapped subtype compiles and throws at runtime — strictly worse for a sealed type where you *can* prove exhaustiveness, so prefer `COMPILE_ERROR` whenever the hierarchy is closed.

#### Q104. [Coding] Build a `@DecoratedWith` decorator that enriches every mapped DTO with data from an external service, correctly delegating to the generated mapper.

**Problem:** Every `UserDto` must carry a `gravatarUrl` derived from the user's email by an injected `AvatarService`. The base field mapping should stay generated; only the enrichment is custom and applies to single and list mappings.

```java
@Mapper(componentModel = "spring")
@DecoratedWith(UserMapperDecorator.class)
public interface UserMapper {
    UserDto toDto(User u);
    List<UserDto> toDtos(List<User> users);
}

public abstract class UserMapperDecorator implements UserMapper {

    private final UserMapper delegate;          // the GENERATED impl, qualified
    private final AvatarService avatars;

    protected UserMapperDecorator(@Qualifier("delegate") UserMapper delegate,
                                  AvatarService avatars) {
        this.delegate = delegate;
        this.avatars = avatars;
    }

    @Override
    public UserDto toDto(User u) {
        UserDto dto = delegate.toDto(u);        // generated field copy
        if (dto != null && u != null) {
            dto.setGravatarUrl(avatars.gravatar(u.getEmail()));   // enrichment
        }
        return dto;
    }

    @Override
    public List<UserDto> toDtos(List<User> users) {
        if (users == null) return null;
        return users.stream().map(this::toDto).toList();          // reuse enriched toDto
    }
}
```

`@DecoratedWith` makes MapStruct generate the real mapping into an impl annotated with `@Qualifier("delegate")` and register your decorator as the *primary* bean. The decorator implements the same interface, holds the generated delegate, calls it for the heavy field-copy, then layers on cross-cutting behavior. This is the right tool when the custom logic wraps the *whole* mapper (enrichment, auditing, post-processing) rather than a single field — for one field, an `@AfterMapping` is lighter.

The decorator is also the seam where I'd be careful about *performance*: calling `avatars.gravatar(...)` per element in `toDtos` is N service calls. In production I'd batch — fetch all avatars once and map, or make the enrichment cheap/cached — because a naive decorator can turn an O(N) mapping into N remote calls. I override `toDtos` to route through the enriched `toDto` so I never accidentally return un-enriched DTOs from the list path; forgetting this is a classic decorator bug where single mappings are enriched but list mappings aren't.

- **Time/Space:** O(N) mappings; enrichment cost dominated by `AvatarService` — batch it.
- **Trade-off:** `@DecoratedWith` adds indirection (a `delegate` bean plus the decorator), so reserve it for genuine cross-cutting needs; overusing it for single fields obscures the mapping. The `@Qualifier("delegate")` wiring is the part people get wrong — without it, Spring can't distinguish the generated impl from the decorator and you get an ambiguous-bean or self-injection cycle.

#### Q105. [Coding] Implement update-into-a-managed-JPA-entity merge using `@ObjectFactory` + `EntityManager` via `@Context`, so dirty-checking persists the changes. Explain why this beats `new` + save.

**Problem:** A PUT/PATCH must update an existing managed `User` so Hibernate dirty-checking flushes the changes within the transaction, *without* detaching/re-attaching or overwriting fields the DTO doesn't carry.

```java
@Mapper(componentModel = "spring",
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public abstract class UserMergeMapper {

    @ObjectFactory
    public User resolve(UserDto dto, @Context EntityManager em) {
        return (dto.getId() != null)
                ? em.find(User.class, dto.getId())   // returns the MANAGED instance
                : new User();                         // create path
    }

    public abstract void merge(UserDto dto, @MappingTarget User entity);  // (used after factory in 'map')

    @Mapping(target = "id", ignore = true)            // never reassign the PK
    public abstract User map(UserDto dto, @Context EntityManager em);     // factory supplies target
}
```

```java
@Service
public class UserService {
    private final UserMergeMapper mapper;
    private final EntityManager em;
    // constructor...

    @Transactional
    public void update(UserDto dto) {
        User managed = mapper.map(dto, em);   // factory does em.find -> managed entity, then maps onto it
        // no explicit save needed: dirty-checking flushes at commit
    }
}
```

The `@ObjectFactory` + `@Context EntityManager` pattern is the canonical way to merge into a *managed* entity. Instead of `new User()`, MapStruct calls `resolve(...)`, which does `em.find(User.class, id)` and returns the entity already tracked by the persistence context. MapStruct then populates that managed instance, so Hibernate's dirty-checking detects the changed fields and flushes them at transaction commit — no explicit `save`/`merge` call, no detach/reattach dance.

Why this beats `new User()` + `repository.save(...)`: a freshly-`new`'d entity with the same id is a *detached* instance; saving it issues a full overwrite (`merge`) that can clobber fields not present in the DTO and can resurrect deleted associations or lose optimistic-lock state. Loading the managed entity and applying only the DTO's non-null fields (via `nullValuePropertyMappingStrategy = IGNORE`) gives true partial-update semantics that respect `@Version` and lazy associations.

- **Edge cases / pitfalls:** (1) the whole flow must run inside `@Transactional` so the persistence context that `em.find` uses is the same one that flushes. (2) `@Mapping(target = "id", ignore = true)` protects the primary key from being reassigned by the mapping. (3) `em.find` returning null (id not found) needs explicit handling — throw a 404-style exception in the factory or service rather than silently creating a new row. (4) With `IGNORE` you can't clear a field to null via PATCH (Q63's caveat) — use a presence-aware wrapper if "explicitly null" must be distinguishable. This pattern is a frequent advanced interview scenario precisely because it ties MapStruct, JPA lifecycle, and transaction boundaries together.

#### Q106. [Coding] Map a tree (a `Category` with `children`) to DTOs with a *maximum depth limit*, threading the remaining depth through `@Context`, and explain why depth-limiting is sometimes better than full cycle-avoidance.

**Problem:** A category tree can be huge; an API wants only N levels deep. Map `Category`→`CategoryDto` but stop recursing past `maxDepth`, returning a node with `null`/empty children at the boundary.

```java
public class DepthContext {
    private int remaining;
    public DepthContext(int max) { this.remaining = max; }
    @BeforeMapping void descend() { remaining--; }   // consumed entering each level
    @AfterMapping  void ascend()  { remaining++; }    // restored leaving each level
    boolean atLimit() { return remaining < 0; }
}

@Mapper(componentModel = "spring")
public abstract class CategoryTreeMapper {

    @Mapping(target = "children", expression = "java(mapChildren(c, ctx))")
    public abstract CategoryDto toDto(Category c, @Context DepthContext ctx);

    protected List<CategoryDto> mapChildren(Category c, DepthContext ctx) {
        if (ctx.atLimit() || c.getChildren() == null) return List.of();  // stop recursing
        return c.getChildren().stream()
                .map(child -> toDto(child, ctx))     // recurse with shared depth context
                .toList();
    }
}
```

```java
CategoryDto dto = mapper.toDto(root, new DepthContext(2));   // 3 levels: root + 2
```

Here the `@Context DepthContext` carries mutable per-call state (remaining depth) that MapStruct threads into every nested `toDto`. The `@BeforeMapping`/`@AfterMapping` hooks decrement on the way down and restore on the way up so siblings each get the full budget. The `mapChildren` helper checks the limit and returns an empty list at the boundary, truncating the tree instead of recursing forever.

The reason depth-limiting is often *better* than full cycle-avoidance for tree-shaped APIs: a true DAG/cyclic graph needs the identity-cache `CycleAvoidingMappingContext` (Q15) to terminate, but for a strict tree the real problem is usually *response size and N+1 lazy loading*, not infinite recursion. Bounding depth caps the payload and the number of lazy associations Hibernate must initialize, which is the actual production concern (a 6-level category tree serialized fully can be megabytes and trigger hundreds of queries).

- **Time/Space:** O(V) up to the depth cut, where V is nodes within `maxDepth`; O(V) for the result and O(depth) recursion stack.
- **Critical edge case:** `DepthContext` is **stateful per invocation** — it must be created fresh per top-level call (`new DepthContext(2)`) and never shared as a singleton bean, or concurrent requests corrupt each other's remaining count. This is the same statefulness discipline as the cycle context (Q53). For a graph that is *both* deep and cyclic, you combine both contexts (identity cache + depth bound).

#### Q107. [Coding] Map into an immutable target built via Lombok `@Builder`, including a collection field, and explain how MapStruct detects and uses the builder.

**Problem:** `OrderDto` is immutable, constructed via Lombok `@Builder`, and has a `List<LineDto> lines`. Map from a mutable `Order` entity. Show that no setters are required.

```java
@Value @Builder
public class OrderDto {
    Long id;
    BigDecimal total;
    @Singular List<LineDto> lines;   // builder exposes addLine / lines
}

@Mapper(componentModel = "spring")
public interface OrderMapper {
    @Mapping(target = "total", source = "amount")
    OrderDto toDto(Order order);
    LineDto toLineDto(Line line);    // element mapper reused for the lines collection
}
```

MapStruct detects a builder via its `BuilderProvider` SPI: for the target `OrderDto`, it finds the Lombok-generated `OrderDto.builder()` factory and the matching `build()` method, infers the builder type, and generates code that calls `builder().id(...).total(...).lines(...).build()` instead of a constructor or setters. No setters exist on `@Value`-immutable `OrderDto`, and none are needed — that's the whole point of builder support.

```java
// Generated (conceptual):
OrderDto.OrderDtoBuilder b = OrderDto.builder();
b.id(order.getId());
b.total(order.getAmount());
b.lines(orderLineListToLineDtoList(order.getLines()));   // element-mapped via toLineDto
return b.build();
```

The collection field works because a `toLineDto(Line)` element mapper exists, so MapStruct generates a loop that maps each line and hands the resulting `List<LineDto>` to the builder. Lombok `@Singular` additionally generates `addLine`/`clearLines` on the builder, which MapStruct's `CollectionMappingStrategy` can use, but the simple "build the whole list, then set it" path is the default for builder targets (`TARGET_IMMUTABLE`-style construction).

- **Lombok gotcha (must mention):** Lombok generates the builder during *its* annotation-processing pass, so MapStruct must run after Lombok — include `lombok-mapstruct-binding` on the processor path or MapStruct sees no `builder()` and falls back to constructor/setter resolution, producing confusing "can't map" errors.
- **Version note:** builder support is solid from MapStruct 1.4+ and refined in 1.5/1.6; on older versions you'd need `@Mapper(builder = ...)` or it wouldn't detect the builder at all. You can disable builder use globally with `-Amapstruct.disableBuilders=true` if a builder is being picked up where you wanted setters.

#### Q108. [Coding] You have a homegrown fluent immutable-object framework whose accessors are `withX(...)`/`x()` instead of `setX`/`getX`. Teach MapStruct to recognize them via a custom `AccessorNamingStrategy` SPI.

**Problem:** Domain objects use a fluent style: read with `x()` (no `get`), "set" with `withX(value)` returning a new instance. MapStruct's default `AccessorNamingStrategy` won't recognize these, so it sees no properties. Register a custom strategy.

```java
package com.acme.mapstruct;

public class FluentAccessorNamingStrategy extends DefaultAccessorNamingStrategy {

    @Override
    public boolean isGetterMethod(ExecutableElement method) {
        String name = method.getSimpleName().toString();
        // fluent read: no-arg, non-void, not the usual get/is — treat 'x()' as getter for 'x'
        return method.getParameters().isEmpty()
                && method.getReturnType().getKind() != TypeKind.VOID
                && !name.startsWith("get") && !name.startsWith("is");
    }

    @Override
    public boolean isSetterMethod(ExecutableElement method) {
        return method.getSimpleName().toString().startsWith("with")
                && method.getParameters().size() == 1;
    }

    @Override
    public String getPropertyName(ExecutableElement getterOrSetterMethod) {
        String name = getterOrSetterMethod.getSimpleName().toString();
        if (name.startsWith("with")) return decapitalize(name.substring(4)); // withName -> name
        return name;                                                          // name() -> name
    }
}
```

Register it via `ServiceLoader` on the **annotation-processor classpath** (not the runtime classpath), because the strategy runs inside `javac`:

```
src/main/resources/META-INF/services/org.mapstruct.ap.spi.AccessorNamingStrategy
  └─ (single line) com.acme.mapstruct.FluentAccessorNamingStrategy
```

The `AccessorNamingStrategy` SPI is *how* MapStruct stays decoupled from any specific bean convention — it asks the strategy "is this a getter? a setter? what property does it name?" rather than hard-coding JavaBeans rules. By subclassing `DefaultAccessorNamingStrategy` and overriding `isGetterMethod`/`isSetterMethod`/`getPropertyName`, you teach MapStruct your fluent convention without touching MapStruct itself or abandoning it for hand-written mappers. This is the exact mechanism that lets MapStruct work with Immutables, protobuf, and other non-JavaBeans frameworks.

- **Key correctness point:** the strategy module/jar must be on the *processor* path (`annotationProcessorPaths` in Maven, `annotationProcessor` in Gradle) and discovered via `META-INF/services`, because it executes at compile time. Putting it only on the runtime classpath does nothing — a very common SPI registration mistake.
- **Edge cases:** the `isGetterMethod` heuristic above is deliberately broad; in practice you'd tighten it (exclude `equals`, `hashCode`, `toString`, builder methods) to avoid misclassifying `Object` methods as property getters, which would create phantom properties and spurious unmapped-target reports. The trade-off of a custom SPI is that it's processor-time machinery few engineers understand — document it heavily and keep it in a small, stable module.

#### Q109. [Coding] Implement a reusable generic mapper for Spring Data `Page<E>` → `PageDto<D>` given that MapStruct can't map bare type variables, and integrate it with a concrete element mapper.

**Problem:** Many endpoints return `Page<Entity>` and need `PageDto<Dto>` carrying content plus pagination metadata. You can't make MapStruct synthesize `PageDto<T>` for an unknown `T`, so design the reuse split.

```java
public record PageDto<D>(List<D> content, int page, int size, long totalElements, int totalPages) {}

// Generic, structure-agnostic envelope: hand-written, fully type-safe Java (MapStruct not involved)
public final class PageMapper {
    private PageMapper() {}
    public static <E, D> PageDto<D> toDto(Page<E> page, Function<E, D> elementMapper) {
        List<D> content = page.getContent().stream().map(elementMapper).toList();
        return new PageDto<>(content, page.getNumber(), page.getSize(),
                             page.getTotalElements(), page.getTotalPages());
    }
}

// Concrete element mapping: MapStruct generates this per type
@Mapper(componentModel = "spring")
public interface ProductMapper {
    ProductDto toDto(Product p);
}
```

```java
@Service
public class ProductService {
    private final ProductMapper mapper;
    private final ProductRepository repo;
    // constructor...

    public PageDto<ProductDto> list(Pageable pageable) {
        Page<Product> page = repo.findAll(pageable);
        return PageMapper.toDto(page, mapper::toDto);   // element mapping injected as a Function
    }
}
```

The design principle (and the interview point) is to **separate the generic envelope from the structure-bearing element mapping**. MapStruct can't map `Page<E>`→`PageDto<D>` because `E`/`D` are bare type variables with no static structure (Q38/Q52). But the *envelope* is trivial type-safe Java — copy `page`, `size`, `totalElements`, map `content` — and the *element* mapping (`Product`→`ProductDto`) is exactly what MapStruct excels at. So you write the envelope once by hand and pass `mapper::toDto` as a `Function<E,D>`, getting full reuse across every paged endpoint without losing compile-time safety on the element conversion.

- **Time/Space:** O(N) over page content; O(N) for the result list.
- **Why not force MapStruct:** trying to make MapStruct generate the generic wrapper means writing a non-generic mapper per `(E, D)` pair (boilerplate) or fighting type erasure — both worse than this 8-line generic helper. The mature stance is "use MapStruct for the part it's good at (concrete element mapping), use plain generics for the part it can't do (the type-variable envelope), and keep the seam tiny and explicit." This same split applies to `ApiResponse<T>`, `ResponseEntity`-style wrappers, and any `Result<T>`/`Optional<T>` envelope.

#### Q110. [Practical] Design the mapping strategy for a system that ingests protobuf messages, maps them to a domain model, then emits Avro for a downstream topic. What are the protobuf/Avro-specific MapStruct considerations?

I'd treat this as two ACL-style mapping boundaries — protobuf→domain on ingest, domain→Avro on emit — with a clean framework-agnostic domain model in the middle so neither wire format leaks into business logic. MapStruct handles both hops, but protobuf and Avro each have generated-class quirks that drive specific configuration.

For **protobuf** sources/targets: generated message classes are immutable and built via `newBuilder()`/`build()` with repeated-field `addX`/`addAllX` adders, scalar fields default to non-null primitives (a missing `int64` reads as `0`, not null), and there's no JavaBeans `setX`. MapStruct needs a builder/accessor strategy that understands `newBuilder()` and the adder convention — either via a protobuf-aware `BuilderProvider`/`AccessorNamingStrategy` SPI (Q108) or community protobuf support. The big semantic trap is protobuf's "no nulls for scalars": you cannot distinguish "field absent" from "field zero/empty" unless the schema uses `optional` (proto3 optional) or wrapper types, so PATCH/presence semantics must rely on protobuf's `hasX()` presence checks — which MapStruct *can* use as presence-check methods (Q28).

For **Avro** (typically `SpecificRecord` generated classes): fields are often nullable unions (`["null","string"]`) mapping to boxed Java types, and Avro has its own builder. Here the hazard is the reverse — domain primitives mapping into nullable Avro unions, and enum symbol mismatches between domain enums and Avro enums (handle with `@ValueMapping` and an `ANY_REMAINING` posture). I'd centralize enum and timestamp conventions (Avro logical types like `timestamp-millis` map to `Instant`/`long`) in shared converters used by both mappers.

```
protobuf OrderProto ──[ProtoOrderAcl]──▶ domain Order ──[OrderToAvro]──▶ Avro OrderRecord
   (hasX presence,            (clean, framework-free)        (nullable unions,
    scalar defaults,                                          logical types,
    addX adders)                                              Avro builder)
```

The senior considerations: (1) keep the domain model independent so a schema bump on either side is isolated to one mapper; (2) version both wire schemas and add **contract tests** with captured real messages, because schema evolution (a new proto field, an Avro union change) is where silent data loss hides — exactly the ACL discipline from Q102; (3) be explicit about the impedance mismatch between protobuf's no-null scalars and Avro's nullable unions, since round-tripping a "zero vs absent" value through the domain can lose meaning; (4) for high-throughput ingest, ensure the mappers and converters are stateless singletons (Q53) so they're safe under the stream-processing thread pool. The honest trade-off: protobuf/Avro generated classes aren't JavaBeans, so MapStruct needs SPI configuration or community add-ons — but once configured, you keep compile-time safety across both serialization boundaries, which is far better than hand-written wire mapping that drifts as schemas evolve.

#### Q111. [Coding] Create a custom `@MappingControl` annotation that forbids implicit conversions for a specific mapper, then show a mapping that now fails to compile and how you fix it correctly.

**Problem:** After a precision incident (a `Long` id silently became a `String`), you want one strict mapper where MapStruct refuses any implicit built-in conversion, forcing explicit, reviewed converters.

```java
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
@MappingControl(MappingControl.Use.DIRECT)            // allow only assignable-type direct copy
@MappingControl(MappingControl.Use.MAPPING_METHOD)    // and explicit mapping methods
public @interface NoImplicitConversions {}            // BUILT_IN_CONVERSION deliberately omitted

@Mapper(componentModel = "spring", mappingControl = NoImplicitConversions.class)
public interface AccountMapper {
    AccountDto toDto(Account a);   // Account.id is Long, AccountDto.id is String
}
```

With `NoImplicitConversions` applied, MapStruct is only permitted to use `DIRECT` assignment (same/assignable types) and explicit `MAPPING_METHOD`s — `BUILT_IN_CONVERSION` is *not* in the allowed set. So the `Long id` → `String id` mapping, which previously compiled via the silent built-in `Long`→`String` conversion, now **fails the build** with a "can't map property 'id'" error. That compile error is the feature: it surfaces every implicit coercion that was previously invisible.

The correct fix is to make the intent explicit — either change the model so types match (the right fix if the `String` id was itself the bug), or write a reviewed, named converter that documents *why* the conversion is acceptable:

```java
@Mapper(componentModel = "spring", mappingControl = NoImplicitConversions.class)
public interface AccountMapper {
    @Mapping(target = "id", source = "id", qualifiedByName = "idToString")
    AccountDto toDto(Account a);

    @Named("idToString")
    default String idToString(Long id) { return id == null ? null : id.toString(); }
}
```

`MappingControl` works by restricting *which mapping strategies* MapStruct may apply (the `Use` enum: `DIRECT`, `BUILT_IN_CONVERSION`, `MAPPING_METHOD`, `COMPLEX_MAPPING`). Composing your own control annotation that omits `BUILT_IN_CONVERSION` is the standard way to forbid implicit coercions at fine grain — attachable on `@Mapper`, `@MapperConfig`, `@BeanMapping`, or a single `@Mapping`.

- **Trade-off:** introducing this on an existing codebase surfaces a flurry of compile errors (every implicit `int`↔`long`, `Long`↔`String`, etc.) — which is exactly the point, but you roll it out per-mapper or per-package, not big-bang. The built-in `@DeepClone` control is the complementary case (force deep copies). The principle: convert implicit, precision/locale-risky behavior into explicit, reviewable decisions — the safety posture you want on financial/PII mappers.

#### Q112. [Coding] Disambiguate among several candidate converters using a *custom qualifier annotation* (type-based `qualifiedBy`) rather than `@Named` strings, and explain why this is more refactor-safe at scale.

**Problem:** You have multiple `String`→`String` transformers (trim, uppercase, mask) registered via `uses`. String-based `@Named` qualifiers are typo-prone and not refactor-safe. Use type-based qualifiers instead.

```java
// 1. Define qualifier annotations (meta-annotated with @Qualifier)
@Qualifier @Retention(RetentionPolicy.CLASS) public @interface Masking {}
@Qualifier @Retention(RetentionPolicy.CLASS) public @interface TrimAndUpper {}

// 2. Annotate the converter methods with the qualifier types
@Component
public class StringConverters {
    @Masking      public String mask(String s)  { return s == null ? null : s.replaceAll(".(?=.{4})", "*"); }
    @TrimAndUpper public String norm(String s)  { return s == null ? null : s.trim().toUpperCase(); }
}

// 3. Reference by TYPE via qualifiedBy (compile-checked, not a string)
@Mapper(componentModel = "spring", uses = StringConverters.class)
public interface CardMapper {
    @Mapping(target = "maskedPan", source = "pan",        qualifiedBy = Masking.class)
    @Mapping(target = "code",      source = "promoCode",  qualifiedBy = TrimAndUpper.class)
    CardDto toDto(Card card);
}
```

Both `mask` and `norm` are `String`→`String`, so without disambiguation MapStruct raises "Ambiguous mapping methods found." `@Named`/`qualifiedByName` solves it with strings, but `qualifiedBy = Masking.class` solves it with **types**. The qualifier annotation is meta-annotated with `org.mapstruct.Qualifier`; MapStruct matches a `@Mapping(qualifiedBy = X.class)` to the converter method annotated with `@X`.

Why type-based qualifiers are more refactor-safe at scale: a `@Named("mask")` string is invisible to the compiler and the IDE — rename the method or fat-finger the string and you get a build error far from the cause (or worse, it silently selects a different method). A `qualifiedBy = Masking.class` reference is a real type: the IDE finds usages, "rename symbol" updates every reference, deleting the qualifier breaks the build at the reference site, and you can't typo it. On a large codebase with many overlapping `String`→`String` (or `Long`→`String`) converters, this turns a class of silent/late errors into compile-time, refactor-tracked references.

- **Trade-off:** type-based qualifiers cost two extra annotation declarations versus a string, so for a one-off `@Named` is fine. But for *reused* converters that overlap on type — money, dates, masking, normalization — defining a small set of qualifier annotations is the disciplined choice, and it documents intent (`@Masking` reads better than `qualifiedByName = "mask"`). It also defends against Q73's hazard: adding a new same-type helper to a shared converter can't silently make existing mappings ambiguous if they're pinned to a specific qualifier type.

### 🔴 Expert — extended

#### Q113. [Practical] As the staff engineer for a 30-team org, design the end-to-end strategy for MapStruct: versioning, shared converters, conventions, CI enforcement, and how you'd evolve it without breaking teams.

I'd treat MapStruct usage as a *platform concern* with a small, opinionated golden path, because at 30 teams the failure mode isn't any single mapper — it's drift, version skew, and inconsistent safety postures that each cause their own incidents. The strategy has four pillars: a shared config artifact, version governance, CI enforcement, and a managed evolution process.

**Shared config + converters as a published library.** I'd publish a small, low-churn `mapping-commons` artifact containing the org-wide `@MapperConfig` (component model `spring`, constructor injection, `unmappedTargetPolicy = ERROR`, agreed null strategies) and the genuinely cross-cutting converters (money, dates/timezones, masking, common enums). Teams depend on it and write `@Mapper(config = OrgMapperConfig.class)`. Keeping this artifact *small and stable* matters because Q33/Q51 showed that changing a shared mapper/converter recompiles every downstream module — a high-churn commons artifact would lengthen everyone's builds and couple teams.

**Version governance.** One MapStruct version pinned via a parent BOM/platform, with `mapstruct` and `mapstruct-processor` always identical (a skew is a classic silent-generation bug). Upgrades go through the platform team: read migration notes, run the org test corpus, roll out behind the BOM so teams adopt by bumping one property, not editing every build file.

```
mapping-commons (BOM-pinned MapStruct 1.6.x)
  ├─ OrgMapperConfig        (@MapperConfig: spring, CONSTRUCTOR, ERROR policy)
  ├─ MoneyConverter, DateTimeConverter, MaskingConverter   (@Named/@Qualifier)
  └─ shared qualifier annotations (@Masking, @Money, ...)
teams ──depend──▶ @Mapper(config = OrgMapperConfig.class)
```

**CI enforcement + conventions.** ArchUnit rules in the shared test library: every mapper references the org config; DTOs never appear in domain packages; mappers are the only writers of DTOs at the boundary. `unmappedTargetPolicy = ERROR` is non-negotiable in the merge gate. A one-page "mapping conventions" doc (declare nested mappers explicitly, prefer methods over `expression`, allowlist sensitive outbound projections) plus a reference example repo so teams copy a correct pattern.

**Evolution without breakage.** I'd never big-bang. New policies (e.g. forbidding implicit conversions via `@MappingControl`) roll out opt-in first, then default with a deprecation window, then enforced — staged exactly like the `ERROR`-policy rollout in Q61. I'd track adoption with a simple lint/scan across repos. The staff-level framing: my job isn't to write mappers, it's to make the *correct* way the *easy* way (golden path + shared config), make the wrong way fail in CI, and make upgrades a one-line bump — so the standard survives team turnover and scales without me being in the loop on every mapper.

#### Q114. [Coding] Implement and register a custom `BuilderProvider` SPI so MapStruct uses a non-standard `create()`/`assemble()` builder convention, and explain when you'd reach for this over disabling builders.

**Problem:** A legacy immutable framework exposes builders via `Thing.create()` (not `builder()`) and finalizes via `assemble()` (not `build()`). MapStruct's default `BuilderProvider` recognizes `builder()`/`build()`, so it won't detect these. Teach it.

```java
package com.acme.mapstruct;

public class LegacyBuilderProvider extends DefaultBuilderProvider {

    @Override
    protected boolean shouldIgnore(TypeElement type) { return false; }

    @Override
    public BuilderInfo findBuilderInfo(TypeMirror type) {
        TypeElement element = asTypeElement(type);
        // Find a static no-arg 'create()' returning a builder type
        for (ExecutableElement m : ElementFilter.methodsIn(element.getEnclosedElements())) {
            if (m.getModifiers().contains(Modifier.STATIC)
                    && m.getSimpleName().contentEquals("create")
                    && m.getParameters().isEmpty()) {
                TypeMirror builderType = m.getReturnType();
                ExecutableElement assemble = findMethod(builderType, "assemble");
                return new BuilderInfo.Builder()
                        .builderCreationMethod(m)     // Thing.create()
                        .buildMethod(List.of(assemble)) // builder.assemble() -> Thing
                        .build();
            }
        }
        return super.findBuilderInfo(type);   // fall back to builder()/build()
    }
}
```

Register on the **processor classpath** via `ServiceLoader`:

```
META-INF/services/org.mapstruct.ap.spi.BuilderProvider
  └─ com.acme.mapstruct.LegacyBuilderProvider
```

The `BuilderProvider` SPI is MapStruct's hook for "what is the builder for this type, and how do I create and finalize it." The default implementation hard-codes the common `builder()`/`build()` shape; subclassing it and overriding `findBuilderInfo` lets you describe any convention — `create()`/`assemble()`, protobuf's `newBuilder()`/`build()`, or a factory-method style. MapStruct then generates `Thing.create().field(...).assemble()` for that target. This is the cleanest path to keep using MapStruct with a framework that isn't JavaBeans- or Lombok-shaped.

When to reach for this versus `-Amapstruct.disableBuilders=true`: disable builders only when you *want* MapStruct to ignore a builder and use constructors/setters instead (e.g. the builder is incidental and setters are simpler). You implement a custom `BuilderProvider` when the type is *genuinely* builder-only (no usable constructor/setters) but uses a non-standard builder naming — there's no other way to construct it, so teaching MapStruct the convention beats hand-writing every mapper for that framework.

- **Critical gotcha:** like all MapStruct SPIs, the provider must be on the *annotation-processor* path (it runs in `javac`), discovered via `META-INF/services`. Registering it only at runtime does nothing. Keep it in a small shared module and document it, since processor-time SPIs are unfamiliar territory for most engineers.

#### Q115. [Coding] Combine `@SubclassMapping` with a runtime discriminator and `subclassExhaustiveStrategy`, where one subtype must use a custom sub-mapping and an unknown subtype must throw with a clear message. Discuss the exhaustiveness trade-off for a *non-sealed* hierarchy.

**Problem:** `Animal` is **not** sealed (open hierarchy: `Dog`, `Cat`, plus possibly third-party subtypes). Map `Animal`→`AnimalDto`; `Dog` needs a custom sub-mapping (compute `bark` loudness), `Cat` flattens directly, and an unrecognized subtype must fail loudly at runtime with a diagnostic.

```java
@Mapper(componentModel = "spring",
        subclassExhaustiveStrategy = SubclassExhaustiveStrategy.RUNTIME_EXCEPTION)
public interface AnimalMapper {

    @SubclassMapping(source = Dog.class, target = DogDto.class)
    @SubclassMapping(source = Cat.class, target = CatDto.class)
    AnimalDto toDto(Animal animal);

    @Mapping(target = "loudness", expression = "java(dog.getWeightKg() * 10)")
    DogDto toDto(Dog dog);          // custom sub-mapping

    CatDto toDto(Cat cat);          // direct flatten
}
```

For a **non-sealed** hierarchy, compile-time exhaustiveness is *impossible* — the compiler (and MapStruct) cannot know the full set of subtypes, because anyone can add a `Hamster extends Animal` in another module. So `subclassExhaustiveStrategy = COMPILE_ERROR` would be meaningless/unsatisfiable here; the correct choice is `RUNTIME_EXCEPTION`, which generates a dispatch that throws `IllegalArgumentException` for any unmapped subtype:

```java
public AnimalDto toDto(Animal animal) {
    if (animal == null) return null;
    if (animal instanceof Dog d) return toDto(d);
    if (animal instanceof Cat c) return toDto(c);
    throw new IllegalArgumentException(
        "No mapping for " + animal.getClass());   // loud, diagnostic failure
}
```

This is the exact inverse of Q103's sealed case, and articulating *why* is the expert signal. With a **sealed** hierarchy you get a closed permits-list, so `COMPILE_ERROR` gives true exhaustiveness (a new subtype breaks the build until mapped) — strictly the safest posture. With an **open** hierarchy you fundamentally cannot have that guarantee, so you choose between failing loud at runtime (`RUNTIME_EXCEPTION`) and silently mapping the unknown subtype as its base type (lossy, usually worse). I'd pick `RUNTIME_EXCEPTION` plus monitoring/alerting on that exception, because a quietly base-mapped unknown subtype loses subtype fields silently — the classic "looks fine, data is wrong" failure.

- **Edge cases:** ordering matters (MapStruct emits most-specific-first, so a `Dog extends Animal` check precedes `Animal`); `expression` on `loudness` is acceptable here as a trivial derivation but I'd promote it to a `default` method if it grew. The deeper architectural point: if the hierarchy is open *and* you need exhaustiveness guarantees, the type system can't give them — you either seal the hierarchy (design change) or accept runtime enforcement plus observability. Knowing that "exhaustiveness requires a closed set" is a type-theory fact, not a MapStruct limitation, is the senior answer.

#### Q116. [Practical] Design a mapping layer that is guaranteed reflection-free for a GraalVM native-image build, and enumerate exactly what could sneak reflection back in.

The design goal is that *nothing* in the mapping path requires runtime reflection, dynamic proxies, or runtime class loading, because GraalVM's closed-world analysis will either fail or need brittle metadata for those. MapStruct is structurally ideal here — generated `*Impl`s are plain method calls on your getters/setters — so the work is mostly about *not reintroducing* reflection through the edges.

**Core design choices:** use `componentModel = "spring"` (or `jakarta`), **never** `Mappers.getMapper(...)`. The DI-wired `@Component` impls are registered by Spring Boot 3's AOT engine and reachable by GraalVM as ordinary beans, whereas `Mappers.getMapper` *reflectively loads* the impl by name (constructs the class via reflection from a derived name) — which would need an explicit reflection hint in native config and is exactly the kind of thing that fails at native runtime if missed. Standardize this in the shared `@MapperConfig` so no team can slip back to the factory model.

```
reflection-free mapping layer for native image
  ✅ @Mapper(componentModel="spring") -> @Component impl, AOT-registered, reachable
  ✅ generated get/set calls, @Named/uses converters (plain method calls)
  ❌ Mappers.getMapper(...)            -> reflective class load by name (needs hint)
  ❌ expression="java(... reflection ...)" / default method using reflection
  ❌ a converter that internally uses Jackson tree/ObjectMapper on dynamic shapes
```

**What could sneak reflection back in (the enumeration the question wants):** (1) `Mappers.getMapper` factory lookup, as above. (2) A custom `expression` or `default`/`@AfterMapping` method that itself calls `Class.forName`, `Method.invoke`, or builds objects reflectively. (3) A `uses` converter that delegates to a reflection-based library (e.g. shells out to Jackson `ObjectMapper` for a `Map<String,Object>`→bean conversion, or to ModelMapper for a "dynamic" sub-mapping) — the MapStruct part is clean but the converter drags reflection in. (4) `@ObjectFactory` that does `type.getDeclaredConstructor().newInstance()` (reflective instantiation) instead of a concrete `new`/`em.find`. (5) Bean Validation or serialization layers *adjacent* to the mapper that reflect over the DTO — not MapStruct, but they break the "reflection-free pipeline" claim if you're auditing the whole path.

The verification step I'd add: build the native image in CI and run the mapping-layer integration tests against the native binary, because reflection problems in native image manifest only at native runtime, not in JVM tests. The senior framing: MapStruct gives you a reflection-free *core* for free, but "reflection-free" is a property of the *whole path*, so the discipline is auditing the converters, factories, and expressions at the edges — and encoding "spring component model, no `Mappers.getMapper`, no reflective converters" as an enforced convention rather than hoping each team gets it right.

#### Q117. [Coding] Write an `@ObjectFactory` that needs the concrete target type at runtime (type erasure makes `new T()` impossible) using `@TargetType`, and explain the generics/erasure subtlety.

**Problem:** A reusable factory must create the *concrete* target instance for a generic mapping, but Java erases the type parameter, so the factory can't do `new T()`. Use `@TargetType Class<T>` to recover the type and instantiate (or `em.find`) correctly.

```java
@Mapper(componentModel = "spring")
public abstract class EntityFactoryMapper {

    @PersistenceContext private EntityManager em;

    // MapStruct injects the CONCRETE target Class at each call site, defeating erasure
    @ObjectFactory
    public <T> T resolve(@TargetType Class<T> type, Identifiable source) {
        if (source.getId() != null) {
            T managed = em.find(type, source.getId());   // fetch-or-create using the real type
            if (managed != null) return managed;
        }
        try {
            return type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("No no-arg constructor for " + type, e);
        }
    }

    @Mapping(target = "id", ignore = true)
    public abstract User  toUser(UserDto dto);     // resolve() supplies a User
    @Mapping(target = "id", ignore = true)
    public abstract Order toOrder(OrderDto dto);   // resolve() supplies an Order — same factory
}
```

The crux is type erasure. At runtime a generic method `<T> T resolve(...)` has no idea what `T` is — `new T()` doesn't compile and reflection alone can't recover it. MapStruct solves this by passing `@TargetType Class<T>` as a *special parameter*: at each call site it knows the concrete target statically (it's generating `toUser` vs `toOrder`), so it injects `User.class` or `Order.class` respectively. Inside the factory you now have the real `Class<T>` and can `em.find(type, id)` or `type.getDeclaredConstructor().newInstance()`.

This is what makes *one* factory reusable across many target types — `toUser` and `toOrder` both call `resolve`, each receiving its own `@TargetType`. MapStruct resolves the `@TargetType` parameter by annotation/type, not position, and fills it from the statically-known target of the mapping method being generated.

- **Edge cases / subtleties:** (1) `newInstance()` here *is* reflective — acceptable on the JVM, but a GraalVM-native target would need a reflection hint for those constructors (ties to Q116); for native, prefer a factory that branches on concrete types with literal `new`. (2) `em.find` returning null falls through to construction — be deliberate about whether a missing id should create or 404. (3) the source must expose `getId()` (here via an `Identifiable` interface) for the fetch-or-create branch to be reusable. The expert point: `@TargetType` is MapStruct's answer to "I need the runtime type that generics erased," and it's the mechanism behind generic, reusable factories — without it you'd be forced into one factory per type.

#### Q118. [Practical] Design the mapping layer for an API that must support two concurrent versions (v1 and v2 DTOs) over a single domain model, deprecating v1 gradually. How do you keep it maintainable and prevent v1/v2 drift?

I'd model versioning as *separate DTO sets and separate mappers over one shared domain*, never as conditional logic inside a single mapper, because branching on version inside a mapper recreates exactly the runtime-decision fragility MapStruct exists to eliminate. The domain model is the single source of truth; `v1` and `v2` are independent translation layers on top of it.

```
api/
 ├─ v1/  { dto/UserV1Dto,  mapper/UserV1Mapper }   ──┐
 ├─ v2/  { dto/UserV2Dto,  mapper/UserV2Mapper }   ──┤──▶  domain/User  (one model)
 └─ mapper/converter/  (shared MoneyConverter, etc.)  ┘
```

Each version's mapper uses `@BeanMapping(ignoreByDefault = true)` allowlisting so it emits *exactly* its contract — v1 physically cannot leak a v2-only field, and v2 cannot accidentally drop a field v1 promised. `unmappedTargetPolicy = ERROR` means that when the domain gains a field, *both* version mappers force a deliberate decision: expose it in v2, ignore it for v1's frozen contract. That's how MapStruct's compile-time enforcement directly prevents drift — neither version silently changes shape when the domain evolves.

```java
@Mapper(config = OrgMapperConfig.class)
public interface UserV2Mapper {
    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "id",          source = "id")
    @Mapping(target = "displayName", source = "name")     // v2 renamed 'name'
    @Mapping(target = "email",       source = "email")    // v2 adds email
    UserV2Dto toDto(User u);
}
```

**Where I add discipline beyond MapStruct:** (1) **contract/golden tests per version** — a captured representative v1 response that must not change is the real guard against accidentally altering a deprecated contract; the build fails if v1's serialized shape drifts. (2) A **deprecation runway**: v1 mappers and DTOs are marked `@Deprecated`, telemetry tracks v1 usage, and v1 stays frozen (no new fields) until consumers migrate, then it's deleted wholesale. (3) Treat the v1↔v2 *renames/restructurings* as documented in the mapper, not scattered, so the mapping layer doubles as the living spec of how versions differ.

The trade-off versus a single "smart" mapper with version branches: more classes and some duplicated field mappings. But that duplication is *intentional decoupling* — v1 and v2 must be able to evolve and be deleted independently, and a shared mapper would couple them so that touching v2 risks breaking v1. The senior insight: API versions are independent contracts, MapStruct's per-method allowlisting + `ERROR` policy enforces each contract's completeness at compile time, and the only thing it *can't* enforce — that a frozen contract's serialized shape never changes — you cover with golden tests. Compile-time guarantees handle "did we map everything"; tests handle "did we accidentally change what a client already depends on."

#### Q119. [Coding] Implement a cross-cutting "redact PII in outbound DTOs" rule using `@AfterMapping` with `@TargetPropertyName`/reflection-free field handling, and discuss why this is preferable to per-field `ignore` and where it falls short.

**Problem:** Many outbound DTOs share PII fields (`email`, `ssn`, `phone`) that must be masked consistently. Repeating `@Mapping(..., qualifiedByName="mask")` on every field of every mapper is error-prone (forget one → leak). Centralize the masking.

```java
public interface Redactable {                 // marker contract for DTOs with maskable PII
    void redact();                            // each DTO knows its own sensitive fields
}

@Mapper(componentModel = "spring")
public abstract class CustomerMapper {

    public abstract CustomerDto toDto(Customer c);

    // Runs after EVERY toDto that produces a Redactable; one rule, all mappings
    @AfterMapping
    protected void redactPii(@MappingTarget Redactable dto) {
        dto.redact();
    }
}
```

```java
public class CustomerDto implements Redactable {
    private String email, ssn;
    @Override public void redact() {
        this.email = Masking.email(email);    // a***@d***.com
        this.ssn   = Masking.tail(ssn, 4);    // ***-**-1234
    }
}
```

The design centralizes the *trigger* (an `@AfterMapping` that fires for any `@MappingTarget Redactable`) while letting each DTO declare *which* fields are sensitive (`redact()`), so the masking policy isn't scattered across dozens of `@Mapping` qualifiers. MapStruct generates a call to `redactPii(dto)` at the end of every mapping method whose target implements `Redactable`, with zero per-field annotations. A variant uses `@TargetPropertyName` in a `@Condition`/converter to mask fields *by name* uniformly, but the marker-interface approach keeps the field list type-checked and refactor-safe rather than relying on string field names.

Why this beats per-field `ignore`/`mask`: `ignore` removes the field (sometimes you need a *masked* value, not absence), and per-field `qualifiedByName="mask"` requires remembering to annotate every PII field in every mapper — the failure mode is *omission*, which silently leaks PII. A centralized post-processing rule fails *safe*: a new DTO either implements `Redactable` (and is masked) or doesn't, and you can ArchUnit-test that every outbound DTO carrying known PII field names implements `Redactable`.

**Where it falls short (must state):** (1) it runs *after* mapping, so the unmasked value briefly exists in the object — fine for masking, but if the requirement is "never construct the raw value," you need `@BeanMapping(ignoreByDefault = true)` allowlisting or input-side handling instead. (2) it's *implicit* — a reader of the mapper doesn't see masking happening; document it. (3) it can't mask fields nested inside *other* mapped objects unless those also implement `Redactable`. (4) for true never-in-memory secrets (passwords), don't map them at all (`ignore`) — masking is for "show a hint" PII, not for secrets. The mature posture combines mechanisms: `ignore` for secrets, `@BeanMapping(ignoreByDefault=true)` allowlists on the most sensitive entities, and a `Redactable`/`@AfterMapping` rule for consistently-masked PII — with ArchUnit asserting the policy so it can't be silently bypassed.

#### Q120. [Practical] Design the mapping strategy for a high-throughput streaming/ETL pipeline (millions of records/sec across many threads). What MapStruct-specific choices maximize throughput and avoid concurrency bugs?

At millions of records/sec the mapping layer must be (a) allocation-frugal, (b) genuinely thread-safe with no shared mutable state, and (c) free of per-record hidden costs like reflection or per-call object construction in hot helpers. MapStruct is the right base because generated mappers are plain method calls (no reflection, no warm-up — Q44), but the design discipline is in keeping them *stateless* and the converters *cheap*.

**Thread-safety first (the bug that pages you).** Mappers must be stateless singletons — generated `*Impl`s with only `final` collaborator references are inherently thread-safe (Q53), so they're safe to share across the entire worker pool. The cardinal rule: **never** put per-record state in a singleton mapper, and any necessary per-operation state (a cycle/depth context, a per-batch cache) must travel as a *freshly-created* `@Context` object per call, never as a shared bean. The canonical incident here is someone making a `CycleAvoidingMappingContext` a singleton `@Bean` — instant cross-thread data bleed and `ConcurrentModification` corruption under load.

**Throughput-specific choices:**
- Use `componentModel = "spring"` + constructor injection so impls are singletons reused across all records (no per-record mapper instantiation).
- Keep converters *pure and cheap*: pre-build immutable `DateTimeFormatter`s as statics (`withZone` per call is cheap), avoid per-record `new BigDecimal`-heavy work where a primitive path suffices, and ensure `@Condition`/`@AfterMapping` hooks are O(1) since they run per record (and per element in collections).
- Avoid `expression = "java(...)"` on hot fields — not for safety alone but because it can't be optimized/inlined by MapStruct's selection and tends to hide allocation; prefer a tight `default` method.
- Prefer `Stream`/array mapping forms where the pipeline is already stream-based to avoid intermediate materialization, and set `nullValueMappingStrategy = RETURN_DEFAULT` on collection methods so downstream operators never null-check.
- For records/immutables targets, builder construction allocates — acceptable, but on the very hottest path consider mutable, reusable target buffers if profiling shows allocation pressure (measure first; usually GC handles short-lived DTOs fine).

```java
@Mapper(componentModel = "spring", injectionStrategy = InjectionStrategy.CONSTRUCTOR)
public interface EventMapper {
    EventOut map(EventIn in);                       // stateless singleton, shared across all threads
    // per-batch state, if any, passes as fresh @Context — NEVER a shared field/bean
}
```

The verification discipline: I'd JMH-benchmark the mapper in isolation (Q74) to confirm it's not the bottleneck (it usually isn't — serialization/IO dominate), and load-test with the real thread pool to flush out any accidental shared state. The senior framing: MapStruct's generated code gives you near-hand-written throughput and inherent thread-safety *for free*, so at this scale the engineering is almost entirely about (1) not reintroducing reflection/allocation through converters and expressions, and (2) rigorously keeping per-operation state in fresh `@Context` objects rather than shared mapper/bean state — get those two right and the mapping layer disappears from the profile.

#### Q121. [Behavioral] Tell me about a time you had to make an architectural call about where mapping/transformation logic should live, against pushback that "it's just a mapper, put the logic there." How did you handle it?

**Situation:** On a lending platform, a junior-heavy team had accumulated business logic inside MapStruct mappers — eligibility calculations, fee derivations, and even a credit-tier decision were embedded in `expression = "java(...)"` blocks and `@AfterMapping` methods across several mappers. A new requirement (regulatory: explain why a fee was charged) surfaced that this logic was un-testable, un-auditable, and duplicated subtly differently in three mappers, producing inconsistent fees.

**Task:** As the staff engineer I had to both fix the immediate inconsistency and establish where transformation-vs-business logic belongs, against genuine pushback — the team's view was "the mapper already has the data, adding the calc there is less code."

**Action:** I reframed the disagreement around *responsibility*, not preference. I showed concretely that the `expression` blocks couldn't be unit-tested in isolation, didn't show up in code review as logic (they read as mapping config), and had drifted across the three mappers — and I tied that directly to the fee inconsistency we were being asked to explain to a regulator. I drew the line explicitly: mappers translate *shape* (rename, flatten, format, unit-convert) and may call out to a domain service, but they must not *decide* anything. I extracted the fee/eligibility logic into a tested `FeePolicy` domain service, had the mappers call it via a `uses`/injected collaborator (or compute in the service and map the result), and added property-based tests on the policy. To make the principle stick rather than relying on my say-so, I added an ArchUnit rule flagging `expression` blocks over a trivial length and wrote a half-page "what belongs in a mapper" guideline with the fee incident as the motivating example.

**Result:** The three fee calculations collapsed into one tested policy with a single, explainable result — which we could now show the regulator — and the eligibility logic became independently unit-testable. The team's pushback faded once they saw the *next* feature was easier: changing the fee rule meant editing one service with tests, not hunting `expression` strings across mappers. The lesson I repeat: "it's just a mapper" is exactly the framing that lets business logic rot into an un-testable layer; the mapper is a *boundary*, and keeping decisions out of it is what makes both the mapping and the logic verifiable. I framed it as enabling the team's velocity, not policing it, which is why it landed without becoming a turf fight.

#### Q122. [Behavioral] Describe a situation where you led the adoption or overhaul of the mapping approach across multiple teams, including how you handled migration risk and dissent.

**Situation:** After an acquisition, two large engineering groups merged: one used MapStruct with strict conventions, the other used a mix of hand-written mappers and ModelMapper with no shared standard. We were consolidating onto one platform, and the inconsistency was causing real pain — duplicated converters, divergent null-handling, and two production incidents in a quarter from runtime mapping mismatches on the ModelMapper side.

**Task:** I was asked to define and drive the unified mapping standard across roughly a dozen teams without stalling delivery and without alienating the acquired group, who were understandably wary of having a standard imposed on them.

**Action:** I deliberately led with *evidence and opt-in*, not mandate. First I quantified the problem: I pulled the two incidents' root causes (both runtime mapping failures that MapStruct would have caught at compile time) and a quick JMH comparison showing the reflection cost on a hot path. Then, rather than decree "everyone use MapStruct," I built the golden path and let it sell itself — a small `mapping-commons` library with a shared `@MapperConfig` (`spring`, constructor injection, `unmappedTargetPolicy = ERROR`), shared money/date converters, ArchUnit rules, and a worked example repo. I migrated one of the *acquired* team's services myself (with their lead, not over them), specifically choosing a service that had been bitten by a mapping bug, so the win was theirs. For migration risk I insisted on a strangler approach: introduce MapStruct alongside the existing mapper, move one DTO pair at a time behind tests and golden-file comparisons asserting byte-identical output, never a big-bang rewrite — so every step was shippable and reversible. I staged the strict `ERROR` policy module-by-module behind CI so it never blocked a team mid-sprint. I handled dissent by treating the strongest skeptic's concerns as design input — their objection that "MapStruct can't do our dynamic admin mapping" was *correct*, so the standard explicitly carved out the dynamic 5% (Q38) rather than pretending one tool fits all, which earned credibility for the other 95%.

**Result:** Within two quarters all but the genuinely-dynamic admin tooling was on the shared standard; the runtime-mapping incident class went to zero (those failures now break the build), and the duplicated converters collapsed into the shared library. The acquired team became advocates rather than holdouts, largely because the first migration was *their* win and because I'd respected the one place their tool choice was actually right. The durable lesson I carry: cross-team standardization succeeds when you make the right way the *easy* way (golden path + shared config), prove it with the skeptics' own pain points, migrate incrementally behind tests, and concede the legitimate edge cases — top-down mandates without those ingredients breed compliance theater, not adoption.

## ✅ Key Takeaways

- MapStruct generates **plain, compile-time** mapping code — performance equals hand-written, far ahead of reflection-based ModelMapper/Dozer.
- `@Mapper(componentModel = "spring")` makes mappers injectable beans; use **constructor injection** and a shared `@MapperConfig` for fleet-wide consistency.
- Collections and nested beans map automatically **when a corresponding element/nested method exists** — declare them explicitly for predictability.
- Set `unmappedTargetPolicy = ERROR` (at least in CI) to turn forgotten fields into build failures rather than production nulls.
- For PATCH/merge use `@MappingTarget` + `nullValuePropertyMappingStrategy = IGNORE`; for managed-entity merges add an `@ObjectFactory` + `EntityManager`.
- Extend with **default methods** (small), **abstract class mappers** (need deps), or **`@DecoratedWith`** (cross-cutting); break cycles with a `@Context CycleAvoidingMappingContext`.
- Mappers are a security boundary: explicitly `ignore` sensitive fields to prevent over-posting inbound and PII leakage outbound.

## ⚠️ Common Pitfalls

- **Lombok ordering:** forgetting `lombok-mapstruct-binding` → MapStruct sees no accessors and copies nothing. Order processors correctly.
- **Spring Boot 3 / Jakarta:** mixing `javax` and `jakarta`; use MapStruct ≥ 1.5.3 and the `spring`/`jakarta` component model.
- **Silent unmapped fields:** leaving `unmappedTargetPolicy = WARN` (default) lets new DTO fields go unmapped and become null in production.
- **PATCH overwriting with nulls:** not setting `nullValuePropertyMappingStrategy = IGNORE` wipes existing entity data with null DTO fields.
- **Overusing `expression = "java(...)"`:** stringly-typed logic that the IDE can't refactor and the compiler can't fully check — prefer `default`/`uses` methods.
- **Infinite recursion** on bidirectional graphs without a `CycleAvoidingMappingContext` (and sharing that stateful context across requests).
- **Records expecting in-place update:** `@MappingTarget` doesn't work on immutable records.
- **Ambiguous mapping methods:** two candidate methods with the same source/target types → "Ambiguous mapping methods" compile error; disambiguate with `@Named` + `qualifiedByName`.

## 📚 Further Reading

- **Official MapStruct Reference Guide** — https://mapstruct.org/documentation/stable/reference/html/ (authoritative, versioned).
- **MapStruct Examples repository** — https://github.com/mapstruct/mapstruct-examples (cycle avoidance, Spring, Lombok, builders, decorators).
- **Baeldung: "Quick Guide to MapStruct"** and its PATCH/null-handling articles — https://www.baeldung.com/mapstruct
- **MapStruct vs ModelMapper benchmarks** — community JMH repos and the MapStruct FAQ on performance.
- **"Domain-Driven Design" (Eric Evans)** — for the anti-corruption layer pattern that mappers implement at bounded-context boundaries.
- **"Effective Java, 3rd ed." (Joshua Bloch)** — builders, immutability, and the static-factory patterns MapStruct integrates with.
