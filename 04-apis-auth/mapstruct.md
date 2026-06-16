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
