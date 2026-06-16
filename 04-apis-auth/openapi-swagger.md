# OpenAPI / Swagger

OpenAPI (formerly Swagger) is the de facto standard for describing HTTP APIs in a machine-readable contract, enabling documentation, client/server code generation, mock servers, validation, and governance from a single source of truth. This guide covers the spec, the Java ecosystem (springdoc-openapi, openapi-generator), tooling (Swagger UI, Spectral), and production practices through 2026.

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

### Q1. [Theory] What is OpenAPI, and how does it relate to "Swagger"?

OpenAPI is a language-agnostic specification for describing RESTful (and HTTP) APIs in a structured document (YAML or JSON). It defines the endpoints, request/response shapes, parameters, authentication schemes, and metadata so that both humans and machines can understand the API without reading the implementation.

"Swagger" was the original name (created by Reverb/SmartBear). In 2015 the specification was donated to the **OpenAPI Initiative** under the Linux Foundation and renamed **OpenAPI Specification (OAS)**. Today "Swagger" refers to the *tooling* maintained by SmartBear — **Swagger UI** (interactive docs), **Swagger Editor**, and **Swagger Codegen** — while "OpenAPI" refers to the *spec itself*. So "a Swagger file" colloquially means an OpenAPI document. The current major versions are 3.0.x and 3.1.x (3.1 aligns with JSON Schema 2020-12).

### Q2. [Theory] What are the top-level sections of an OpenAPI 3.x document?

The root object contains a handful of well-defined keys:

```
openapi: "3.0.3"        # spec version (REQUIRED)
info:                   # title, version, description, contact, license (REQUIRED)
servers:                # base URLs (prod, staging, etc.)
paths:                  # the endpoints and operations (the core)
components:             # reusable schemas, responses, parameters, securitySchemes
security:               # global security requirements
tags:                   # grouping for documentation
externalDocs:           # link to extra documentation
```

`paths` and `components` carry most of the weight. `paths` maps a URL template to operations (`get`, `post`, etc.); `components` holds reusable definitions referenced via `$ref` to avoid duplication.

### Q3. [Practical] How do you expose OpenAPI docs and Swagger UI in a Spring Boot 3 app?

Add the **springdoc-openapi** starter (the successor to the now-defunct SpringFox). For Spring Boot 3 (Jakarta EE namespace) you need springdoc **v2.x**:

```xml
<dependency>
  <groupId>org.springdoc</groupId>
  <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
  <version>2.6.0</version>
</dependency>
```

That single dependency auto-configures everything. With the app running:

- The generated spec is served at `/v3/api-docs` (JSON) and `/v3/api-docs.yaml`.
- Swagger UI is served at `/swagger-ui.html` (which redirects to `/swagger-ui/index.html`).

springdoc scans your `@RestController` classes, Bean Validation annotations, and DTOs to build the document at runtime. Note: **SpringFox is dead** — it never supported Spring Boot 3 / Jakarta and should not be used in new projects.

### Q4. [Coding] Add OpenAPI metadata to a Spring Boot endpoint so the generated docs are accurate.

**Problem:** A bare controller produces generic docs ("OK", `string` types, no examples). Enrich it so the contract is self-documenting.

```java
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Users", description = "User management operations")
public class UserController {

    @Operation(summary = "Get a user by id",
               description = "Returns a single user or 404 if not found")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "User found",
            content = @Content(schema = @Schema(implementation = UserDto.class))),
        @ApiResponse(responseCode = "404", description = "User not found",
            content = @Content) // empty body
    })
    @GetMapping("/{id}")
    public UserDto getUser(@PathVariable long id) {
        // ...
        return new UserDto(id, "Ada Lovelace", "ada@example.com");
    }

    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping
    public UserDto create(@org.springframework.web.bind.annotation.RequestBody
                          @jakarta.validation.Valid CreateUserRequest req) {
        return new UserDto(1L, req.name(), req.email());
    }
}

// Bean Validation annotations flow into the schema (required, format, etc.)
record CreateUserRequest(
    @Schema(description = "Display name", example = "Ada Lovelace")
    @NotBlank String name,
    @Schema(example = "ada@example.com")
    @Email @NotBlank String email) {}

record UserDto(long id, String name, String email) {}
```

**Key idea:** `@NotBlank` becomes `required`, `@Email` hints `format: email`, and `@Schema(example=...)` populates the example shown in Swagger UI. **Edge case:** `@Schema` from `io.swagger.v3` must not be confused with Spring's `@RequestBody` vs Swagger's `@io.swagger...RequestBody` — mixing them is a common compile-time gotcha.

---

## 🟡 Intermediate (3–7 yrs)

### Q5. [Theory] Contract-first vs code-first: what's the difference and when do you pick each?

**Code-first** means you write the implementation (annotated controllers) and *generate* the OpenAPI document from it (e.g., springdoc at runtime). **Contract-first** means you author the OpenAPI YAML by hand *first*, then generate server stubs and client SDKs from it.

```
CODE-FIRST                          CONTRACT-FIRST
  Java code ──► OpenAPI doc           OpenAPI YAML ──► Java interfaces + DTOs
  (annotations)   (derived)           (source of truth)   (generated)

  fast to start                       API is a deliberate, reviewable artifact
  drift risk (docs lag code)          drift risk reversed (code must implement spec)
  good for internal/small APIs        good for public/partner APIs, parallel teams
```

**Trade-offs:** Code-first is faster and keeps a single language source, but the contract is an afterthought and can leak implementation details. Contract-first treats the API as a product: frontend, mobile, and backend teams can work in parallel against a frozen contract, the spec is reviewed in PRs, and breaking changes are visible in the diff. For **public APIs, multi-team orgs, and microservices** I default to contract-first. For a small internal service moving fast, code-first with springdoc is pragmatic.

### Q6. [Practical] Walk through generating a Java server stub and client from an OpenAPI file with openapi-generator.

Use the **openapi-generator** Maven/Gradle plugin (the actively maintained fork of swagger-codegen). Contract-first flow:

```xml
<plugin>
  <groupId>org.openapitools</groupId>
  <artifactId>openapi-generator-maven-plugin</artifactId>
  <version>7.8.0</version>
  <executions>
    <execution>
      <goals><goal>generate</goal></goals>
      <configuration>
        <inputSpec>${project.basedir}/src/main/resources/openapi/api.yaml</inputSpec>
        <generatorName>spring</generatorName>
        <library>spring-boot</library>
        <configOptions>
          <interfaceOnly>true</interfaceOnly>   <!-- generate API interfaces only -->
          <useSpringBoot3>true</useSpringBoot3>  <!-- Jakarta namespace -->
          <useTags>true</useTags>
          <useJakartaEe>true</useJakartaEe>
        </configOptions>
        <apiPackage>com.acme.api</apiPackage>
        <modelPackage>com.acme.model</modelPackage>
      </configuration>
    </execution>
  </executions>
</plugin>
```

`interfaceOnly=true` generates a `UsersApi` interface plus DTOs; you implement the interface in your own `@RestController`, so regenerating the contract never overwrites your business logic. For a client, set `<generatorName>java</generatorName>` with a library like `resttemplate`, `webclient`, or `feign`. **Trade-off in production:** commit the *spec* and let the build regenerate code (don't commit generated sources), so the contract stays the single source of truth and the generated code can't drift.

### Q7. [Theory] What goes in `components`, and why use `$ref` heavily?

`components` is the reusable-definitions container. Sub-objects include `schemas`, `responses`, `parameters`, `requestBodies`, `headers`, `examples`, `links`, `callbacks`, and `securitySchemes`. You reference them with `$ref: "#/components/schemas/User"`.

```yaml
components:
  schemas:
    Error:
      type: object
      required: [code, message]
      properties:
        code: { type: integer, format: int32 }
        message: { type: string }
    User:
      type: object
      properties:
        id: { type: integer, format: int64 }
        manager: { $ref: '#/components/schemas/User' }   # recursion is allowed
  responses:
    NotFound:
      description: Resource not found
      content:
        application/json:
          schema: { $ref: '#/components/schemas/Error' }
```

Using `$ref` keeps the document DRY, makes the generated SDK produce one reusable type instead of many anonymous ones, and lets you reference shared definitions across files (`$ref: './common.yaml#/components/schemas/Error'`). It also gives Swagger UI stable model names. The downside is over-nesting can hurt readability, so keep refs purposeful.

### Q8. [Coding] Define common security schemes (JWT bearer + API key) and apply them.

**Problem:** Document that most endpoints need a JWT, one endpoint uses an API key, and one is public.

```yaml
components:
  securitySchemes:
    bearerAuth:                 # JWT
      type: http
      scheme: bearer
      bearerFormat: JWT
    apiKeyAuth:
      type: apiKey
      in: header
      name: X-API-Key
    oauth2:                     # for completeness
      type: oauth2
      flows:
        authorizationCode:
          authorizationUrl: https://auth.acme.com/oauth/authorize
          tokenUrl: https://auth.acme.com/oauth/token
          scopes:
            read:users: Read user data
            write:users: Modify user data

security:                       # GLOBAL default: every operation needs JWT
  - bearerAuth: []

paths:
  /health:
    get:
      summary: Liveness probe
      security: []              # OVERRIDE: public, no auth
      responses: { '200': { description: OK } }
  /admin/keys:
    post:
      summary: Rotate API key
      security:
        - apiKeyAuth: []        # OVERRIDE: API key instead of JWT
      responses: { '201': { description: Created } }
```

**Why this matters:** Declaring schemes lets Swagger UI render an "Authorize" button so testers can paste a token. **Security note:** the spec only *describes* auth — it does **not** enforce it. Enforcement is your gateway/Spring Security filter chain. Never assume documenting `security` protects anything. **Edge case:** an empty `security: []` at the operation level means "explicitly public" and overrides the global requirement.

### Q9. [Practical] How do you handle API versioning in OpenAPI, and what are the strategies?

OpenAPI itself has an `info.version` field (your API's semantic version, e.g., `2.3.0`), distinct from the `openapi` field (spec version). For exposing multiple versions:

```
1. URI path versioning   /api/v1/users, /api/v2/users   (most common, cache-friendly, visible)
2. Header versioning      Accept: application/vnd.acme.v2+json   (clean URLs, harder to test)
3. Query param            /users?version=2                       (discouraged)
```

In springdoc you can group multiple specs with `GroupedOpenApi` beans (one per version), each producing its own `/v3/api-docs/{group}` document and Swagger UI dropdown. **Production approach:** I prefer URI versioning for major/breaking changes only, keep backward-compatible additions (new optional fields, new endpoints) within the same version, and publish a deprecation policy. Use the `deprecated: true` flag on operations and the `Deprecation`/`Sunset` HTTP headers (RFC 8594) to signal phase-out timelines. Avoid bumping the major version for additive changes — that forces clients to migrate needlessly.

### Q10. [Theory] OpenAPI 3.0 vs 3.1 — what changed and why does it matter?

The headline change is **JSON Schema alignment**. OAS 3.0 used a *subset/superset* of JSON Schema Draft 4 with its own quirks; OAS **3.1 is a strict superset of JSON Schema 2020-12**, so any valid JSON Schema is valid in 3.1. Practical differences:

- **`nullable`** is removed in 3.1; instead you use a type array: `type: [string, "null"]`.
- `exclusiveMinimum`/`exclusiveMaximum` become numbers (not booleans).
- 3.1 adds top-level `webhooks` (first-class incoming webhook descriptions) and allows `$ref` siblings (description alongside a ref).
- `example` (singular) is deprecated in favor of `examples`.

It matters because tooling support lagged for years — some generators/linters only fully support 3.0. As of 2026, springdoc, openapi-generator, Spectral, and Swagger UI all support 3.1, but when integrating with an older partner system, confirm 3.1 support before adopting it.

---

## 🟠 Advanced (8–12 yrs)

### Q11. [Theory] How do you enforce API governance and consistency at scale with Spectral?

**Spectral** (Stoplight) is the dominant open-source **linter** for OpenAPI/JSON/YAML. It applies rulesets — declarative rules with JSONPath/JSONPath-Plus targeting and built-in functions (`pattern`, `casing`, `truthy`, `length`, plus custom JS functions) — to flag governance violations before merge.

```yaml
# .spectral.yaml
extends: ["spectral:oas"]   # built-in OpenAPI best-practice rules
rules:
  operation-tag-defined: error
  operation-operationId: error          # every op must have operationId
  paths-kebab-case:
    description: Paths must be kebab-case
    severity: error
    given: $.paths[*]~                   # ~ targets the key, not the value
    then: { function: pattern, functionOptions: { match: "^(/[a-z0-9-{}]+)+$" } }
  no-http-basic:
    description: Basic auth is forbidden
    given: $.components.securitySchemes[*]
    then: { field: scheme, function: pattern, functionOptions: { notMatch: "basic" } }
```

Run `spectral lint api.yaml` in CI to fail the build on violations. At scale, organizations publish a **central ruleset** (versioned, shared via `extends: https://...`) encoding naming conventions, mandatory error schemas, security requirements, and pagination patterns — turning subjective review into automated, consistent enforcement. This is the core of an **API style guide / governance program**, often paired with a developer portal (Backstage, Stoplight, SwaggerHub).

### Q12. [Practical] Design a CI pipeline that treats the OpenAPI contract as a first-class citizen.

```
            ┌─────────────────────────────────────────────────────┐
            │                     CI PIPELINE                       │
            └─────────────────────────────────────────────────────┘
  PR opened
     │
     ├─► 1. VALIDATE     swagger-cli validate / redocly lint  (well-formed?)
     │
     ├─► 2. LINT/GOVERN  spectral lint --fail-severity=error  (style guide)
     │
     ├─► 3. BREAKING-CHANGE DIFF
     │        oasdiff breaking old.yaml new.yaml  (or openapi-diff)
     │        → fail PR if a breaking change lacks a version bump / label
     │
     ├─► 4. GENERATE     openapi-generator (server stubs + client SDK)
     │        → compile to prove the spec is implementable
     │
     ├─► 5. CONTRACT TEST  schemathesis / Dredd run the live impl vs spec
     │
     └─► 6. PUBLISH (on merge to main)
              → push spec to SwaggerHub/registry, regenerate & release SDKs,
                deploy Redoc/Swagger UI docs site
```

**Real-world rationale:** The two highest-value gates are **#3 (breaking-change detection)** and **#5 (contract testing)**. `oasdiff` mechanically detects removed endpoints, narrowed types, new required fields, etc., and can block the merge — this prevents the classic "we shipped a breaking change and didn't realize" incident. Contract tests (Schemathesis generates fuzz inputs from the schema) catch *implementation drift* where the code returns something the contract forbids. In production at an API-first company, I'd make the spec the merge gate: code can't merge if it violates its own published contract.

### Q13. [Theory] What is a mock server and how does it fit contract-first development?

A **mock server** serves fake-but-schema-valid responses generated from the OpenAPI document, so consumers can develop against the API *before* it's implemented. Tools: **Prism** (Stoplight), **Microcks**, **WireMock** (with OpenAPI import), and **imposter**.

```
  Frontend / mobile team                Backend team
        │                                     │
        ▼                                     ▼
   Prism mock ◄───── api.yaml ─────► real implementation
   (returns examples /              (generated stubs +
    schema-derived data)             business logic)
        │                                     │
        └──────► both validated against the SAME contract ◄──┘
```

Prism has two modes: **static** (returns the `examples` declared in the spec or `x-faker`-driven values) and **dynamic** (generates random schema-valid payloads). It can also run in **validation proxy** mode, forwarding to the real backend and flagging request/response deviations from the contract. The payoff is decoupling: teams iterate in parallel, integration risk drops, and the mock doubles as a contract-conformance check. Microcks adds CI-friendly assertions and multi-protocol support (REST, gRPC, GraphQL, AsyncAPI).

### Q14. [Coding] Write a Spectral custom rule that enforces every error response references a shared Error schema.

**Problem:** Governance wants every 4xx/5xx response body to use `#/components/schemas/Error`, not an ad-hoc inline shape.

```yaml
# .spectral.yaml
extends: ["spectral:oas"]
functions: [errorSchemaRef]      # custom function in ./functions/errorSchemaRef.js
rules:
  errors-use-standard-schema:
    description: 4xx/5xx responses must reference components.schemas.Error
    message: "{{error}}"
    severity: error
    given: >-
      $.paths[*][*].responses[?(@property.match(/^[45]\d\d$/))]
        .content['application/json'].schema
    then:
      function: errorSchemaRef
```

```javascript
// functions/errorSchemaRef.js
export default function errorSchemaRef(schema, _opts, context) {
  const EXPECTED = '#/components/schemas/Error';
  if (!schema || schema.$ref !== EXPECTED) {
    return [{
      message: `Error response schema must be $ref '${EXPECTED}' (found: ${
        schema && schema.$ref ? schema.$ref : 'inline schema'})`,
      path: context.path,
    }];
  }
  return [];   // empty array = pass
}
```

**Time/Space complexity:** Linting is O(N) in the number of schema nodes matched by the JSONPath (N = matched response schemas); the function itself is O(1) per node. Memory is O(1) extra beyond the parsed document. **Edge cases handled:** missing `content`/`schema` (the JSONPath simply won't match, so those slip through — pair with a separate `responses must have content` rule), and inline schemas (reported with a clear message). This pattern — JSONPath `given` + custom JS `then` — is how teams encode arbitrary org-specific policy.

### Q15. [Practical] A consumer reports the generated SDK has wrong/ambiguous types. How do you debug a "spec smell"?

I treat it as a contract-quality bug and work backward from the generated artifact:

1. **Reproduce on the spec, not the SDK.** Validate with `redocly lint` / `swagger-cli validate`. Ambiguous types usually trace to a schema lacking `type`, using `oneOf`/`anyOf` without a `discriminator`, or free-form `additionalProperties: true` collapsing to `Object`/`Map<String,Object>`.
2. **Check polymorphism.** `oneOf` without a `discriminator: { propertyName, mapping }` forces generators to emit `Object`. Add the discriminator so the generator emits a proper sealed hierarchy.
3. **Check number formats.** `type: integer` without `format` defaults to `int32` in many generators — a problem for IDs that exceed 2^31. Specify `format: int64`.
4. **Check enums and nullability.** Inline enums generate anonymous types; promote them to `components/schemas`. For 3.0, missing `nullable: true` makes the SDK reject legitimate `null`s.
5. **Regenerate and diff.** Fix the spec, regenerate, confirm the type, add a CI lint rule (e.g., "every `oneOf` requires a `discriminator`") so it can't regress.

The lesson: most SDK problems are *spec* problems. Code-first specs are especially prone to this because Jackson/inheritance produce sloppy schemas — which is another argument for linting the generated spec even in code-first projects.

---

## 🔴 Expert (15+ yrs)

### Q16. [Theory] How do OpenAPI, AsyncAPI, JSON Schema, and gRPC/Protobuf coexist in a modern platform's API strategy?

No single IDL covers everything, so a mature platform layers them:

```
  Synchronous request/reply over HTTP   ──►  OpenAPI 3.1
  Event-driven / messaging (Kafka, MQTT) ──►  AsyncAPI 3.x (sibling spec)
  Internal high-perf RPC                 ──►  gRPC + Protobuf
  Shared data contracts / validation     ──►  JSON Schema 2020-12 (now == OAS 3.1 schemas)
  Schema registry (Kafka payloads)       ──►  Avro / Protobuf + Confluent registry
```

OAS 3.1's alignment with JSON Schema 2020-12 means HTTP-API schemas and standalone validation schemas finally share one dialect, reducing duplication. AsyncAPI deliberately mirrors OpenAPI's structure for event-driven systems. The strategic call is *governance unification*: one developer portal, one style guide, and tooling (Microcks, Backstage, Spectral with AsyncAPI rulesets) that handles both. The anti-pattern is forcing every interaction through one format — describing a Kafka topic in OpenAPI or a synchronous CRUD API in Protobuf both produce friction. Pick the IDL that matches the interaction model.

### Q17. [Behavioral] You're asked to roll out an API governance program across 40 teams who currently each do their own thing. How do you lead it?

I'd anchor on *enablement over enforcement*, sequenced to build trust:

1. **Discover, don't dictate.** Inventory existing specs and survey teams' pain (inconsistent errors, broken SDKs, no docs). Let the data justify the program.
2. **Co-author the style guide.** Form a cross-team API guild and draft the ruleset *with* practitioners, not in an ivory tower. Encode it as a versioned Spectral ruleset — rules are now executable and unambiguous, not a 40-page PDF nobody reads.
3. **Make the paved road the easy path.** Ship a Spring Boot archetype/starter, a CI template with lint + breaking-change gates, and a shared docs portal. Adoption follows convenience.
4. **Roll out in waves with warnings before errors.** Start rules at `severity: warn`, publish dashboards, give teams a quarter to comply, then flip to `error`. Grandfather legacy specs with explicit exceptions.
5. **Measure and report.** Track coverage (% APIs linted), violation trends, and SDK-related incident reduction. Tie wins to business outcomes (faster partner onboarding).

The behavioral core: governance fails when it's policing. It succeeds when teams experience it as tooling that makes their own lives easier. I'd explicitly own the "tax" — if a rule slows teams without clear value, I kill it.

### Q18. [Practical] At very large scale (thousands of endpoints, hundreds of specs), what breaks and how do you architect around it?

```
  PROBLEM                         MITIGATION
  ───────────────────────────────────────────────────────────────────
  Monolithic 50k-line YAML        Split via external $ref + bundling
                                  (redocly bundle / swagger-cli bundle)
  Swagger UI chokes on huge spec  Use Redoc (virtualized) or split by domain
  $ref resolution slow in CI      Bundle once, cache, validate the bundle
  Spec/code drift across services Central registry (SwaggerHub/Backstage),
                                  automated publish on merge
  Inconsistent shared models      Publish a 'common' spec module; teams
                                  $ref it cross-repo (Git submodule / pkg)
  Breaking changes undetected     Mandatory oasdiff gate + semantic versioning
  Discoverability                 Developer portal w/ search + ownership metadata
```

The architectural keystone is treating specs as **distributed, composable, versioned artifacts**, not one file. You author modular specs (per bounded context), publish them to a registry with ownership and lifecycle metadata, and bundle for distribution. CI runs lint + breaking-change diff per spec; a portal aggregates them for consumers. **Real case study:** Stripe and Twilio both maintain a single, heavily governed, contract-first OpenAPI spec from which they generate every SDK (Python, Java, Node, Go, etc.) and their docs site — the spec *is* the product. That discipline (one source of truth, automated SDK release, strict backward-compat policy) is why their SDKs feel consistent and their APIs rarely break clients. Replicating it requires the registry + governance + breaking-change-gate machinery above.

### Q19. [Theory] What are the security pitfalls specific to OpenAPI documents and Swagger UI in production?

Several distinct risks, often overlooked:

- **Spec ≠ enforcement.** The `security` block is documentation only. The classic incident is assuming a documented `bearerAuth` protects an endpoint that the gateway never actually checks. Enforce in the filter chain; treat the spec as the *test oracle* for an authz contract test.
- **Information disclosure.** A publicly exposed `/v3/api-docs` reveals internal endpoints, parameter names, and data models — a reconnaissance gift to attackers. In production, either disable Swagger UI (`springdoc.swagger-ui.enabled=false`) or gate it behind auth/internal network. Never ship the live "try it out" UI to the public internet for an internal API.
- **Mass-assignment / over-posting.** If a request schema includes fields like `role` or `isAdmin` that the server blindly binds, attackers exploit it. Model request and response DTOs separately; never reuse the entity.
- **Swagger UI XSS / SSRF history.** Older Swagger UI versions had XSS and DOM issues; the "try it out" feature and remote spec loading can enable SSRF. Keep Swagger UI patched and disable remote-spec URL loading.
- **Schema as a WAF input.** Conversely, the spec is a *positive security model* — API gateways (Kong, Apigee, AWS API Gateway) can import it to reject any request that violates the schema, shrinking the attack surface. Use it offensively for defense.

### Q20. [Practical] How do you keep a code-first springdoc spec from drifting, and when would you flip to contract-first?

In code-first, the spec is a *byproduct*, so it silently degrades. My controls:

1. **Snapshot the generated spec in CI.** Hit `/v3/api-docs.yaml` during an integration test, commit it as a golden file, and fail the build when it changes unexpectedly — turning silent drift into a reviewed diff (effectively `oasdiff` against the committed snapshot).
2. **Lint the generated spec.** Run Spectral on the produced document so code-first APIs still meet the org style guide (missing `operationId`, untyped responses, etc.).
3. **Add explicit annotations.** Don't rely on Jackson inference; declare `@Schema`, examples, and error responses so the contract is intentional.
4. **Breaking-change gate** on the snapshot, same as contract-first.

**When to flip to contract-first:** once the API has external consumers, multiple teams depend on it, or you need parallel frontend/backend development, the cost of accidental breakage exceeds the convenience of code-first. At that inflection I move the YAML into the repo as the source of truth, generate interfaces with openapi-generator (`interfaceOnly`), and have springdoc *serve* the hand-written spec rather than derive one. The migration is incremental: snapshot today's generated spec, clean it up, then make it authoritative.

---

## ✅ Key Takeaways

- **OpenAPI is the spec; Swagger is the tooling** (UI, Editor, Codegen). Current versions: OAS 3.0.x and 3.1.x (3.1 = JSON Schema 2020-12).
- In Spring Boot 3, use **springdoc-openapi v2.x** — SpringFox is dead and never supported Jakarta.
- **Contract-first** treats the API as a reviewable product and enables parallel teams; **code-first** is faster but drift-prone. Choose by audience and team topology.
- **openapi-generator** turns a spec into server interfaces and client SDKs; generate at build time, commit the *spec* not the generated code.
- `components` + `$ref` keep specs DRY and produce clean, reusable SDK types; add `discriminator` for polymorphism and `format: int64` for large IDs.
- **Governance = automation**: Spectral rulesets, breaking-change diffs (`oasdiff`), and contract tests (Schemathesis/Prism) belong in CI as merge gates.
- **Mock servers** (Prism, Microcks) decouple consumers from implementation and double as conformance checks.
- The spec **documents** security but does **not enforce** it; never expose internal API docs publicly without protection.

## ⚠️ Common Pitfalls

- Using **SpringFox** on Spring Boot 3 (broken — wrong namespace). Use springdoc-openapi 2.x.
- Confusing `info.version` (your API version) with the `openapi` field (spec version).
- Assuming the `security` block enforces anything — it's documentation; enforcement is in code/gateway.
- Exposing `/v3/api-docs` and the "try it out" Swagger UI on a public endpoint for internal services (recon risk).
- Reusing one entity DTO for request and response, enabling **mass-assignment** of fields like `role`/`isAdmin`.
- `oneOf`/`anyOf` without a `discriminator`, or `integer` without `format`, producing useless `Object`/overflowing `int32` types in generated SDKs.
- Bumping the **major version** for additive, backward-compatible changes — needless client churn.
- Committing generated code instead of the spec, letting the two drift apart.
- In OAS 3.1, still using removed `nullable: true` — use `type: [string, "null"]`.
- One giant monolithic YAML that chokes Swagger UI and slows CI — modularize with external `$ref` + bundling.

## 📚 Further Reading

- **OpenAPI Specification (latest, 3.1.x)** — spec.openapis.org/oas/latest.html (the authoritative source).
- **springdoc-openapi documentation** — springdoc.org (Spring Boot 2/3 integration, config properties, migration from SpringFox).
- **OpenAPI Generator** — openapi-generator.tech (generators, config options, supported libraries).
- **Spectral docs (Stoplight)** — meta.stoplight.io/docs/spectral (custom rulesets and functions for governance).
- *Designing Web APIs* — Brenda Jin, Saurabh Sahni, Amir Shevat (O'Reilly) — API design and lifecycle practices.
- *Continuous API Management* (2nd ed.) — Medjaoui, Wilde, Mitra, Amundsen (O'Reilly) — governance at scale, the "API as a product" mindset.
