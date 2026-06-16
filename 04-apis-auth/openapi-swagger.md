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

## 🧩 Extended Questions — Set 1: Deeper Theory & Practical Operations

### 🟢 Basic — extended

#### Q21. [Theory] What is the difference between `paths`, `operations`, and `path items` in OpenAPI?

These three terms are often used loosely but mean precise things in the spec, and understanding the hierarchy clarifies why certain fields live where they do. The `paths` object is the top-level map whose keys are URL templates (e.g., `/users/{id}`). Each value under `paths` is a **Path Item Object**. Each Path Item then contains zero or more **Operation Objects**, keyed by HTTP method (`get`, `post`, `put`, `delete`, `patch`, `head`, `options`, `trace`).

```yaml
paths:                       # Paths Object (map of templates)
  /users/{id}:               # Path Item Object (one URL template)
    parameters:              # ← shared by ALL operations on this path
      - name: id
        in: path
        required: true
        schema: { type: integer, format: int64 }
    get:                     # Operation Object
      summary: Get user
      responses: { '200': { description: OK } }
    delete:                  # another Operation Object on the same Path Item
      summary: Delete user
      responses: { '204': { description: No Content } }
```

The practical consequence is that **path-level parameters are inherited by every operation** on that path, which lets you declare the `{id}` path parameter once instead of repeating it under `get`, `delete`, etc. Operation-level parameters can override path-level ones by matching `name` + `in`. A Path Item can also carry a `$ref` to externalize the whole item, and a `servers` array to override the base URL for just that path. Knowing this hierarchy prevents the common mistake of redeclaring shared parameters on every method.

#### Q22. [Practical] How do you configure springdoc to change the Swagger UI path, sort operations, and disable it in production?

springdoc is driven almost entirely by `application.yml`/`application.properties`, so most customization needs no code. The frequently used knobs control where the UI lives, how it sorts, and whether it ships at all in a given profile.

```yaml
# application.yml
springdoc:
  api-docs:
    path: /api-docs              # change /v3/api-docs -> /api-docs
    enabled: true
  swagger-ui:
    path: /docs                  # serve UI at /docs instead of /swagger-ui.html
    operations-sorter: method    # or 'alpha'
    tags-sorter: alpha
    try-it-out-enabled: false    # hide the live "Try it out" calls
    display-request-duration: true
  packages-to-scan: com.acme.api # limit scanning to specific packages
  paths-to-match: /api/**        # only document matching paths
```

```yaml
# application-prod.yml  — turn the whole thing off in production
springdoc:
  api-docs:
    enabled: false               # no /v3/api-docs endpoint
  swagger-ui:
    enabled: false               # no UI
```

The split-profile approach is the standard production pattern: you keep rich docs in `dev`/`staging` for developer convenience, but `application-prod.yml` disables both the JSON endpoint and the UI to avoid leaking your internal API surface. If you must keep docs reachable in production (for example for partners), put them behind Spring Security or an internal-only ingress rather than disabling them. `packages-to-scan` and `paths-to-match` are also valuable performance and hygiene controls on large apps — they stop springdoc from documenting actuator endpoints or internal controllers you never meant to publish.

#### Q23. [Theory] What are the parameter `in` locations, and how does `style`/`explode` affect serialization?

A Parameter Object's `in` field declares where the value travels: `path`, `query`, `header`, or `cookie` (the OAS 2.0 `formData` and `body` locations were replaced by the Request Body Object in 3.x). Beyond location, the `style` and `explode` fields control how complex values (arrays and objects) are serialized into the wire format — a detail that trips people up when arrays "don't work."

```yaml
parameters:
  - name: ids
    in: query
    schema:
      type: array
      items: { type: integer }
    style: form        # default for query
    explode: true      # default for form style
    # explode:true  -> ?ids=1&ids=2&ids=3
    # explode:false -> ?ids=1,2,3   (comma-joined)
```

| `in` | default `style` | default `explode` | array example |
|------|-----------------|-------------------|---------------|
| query | `form` | `true` | `?id=1&id=2` |
| path | `simple` | `false` | `/users/1,2,3` |
| header | `simple` | `false` | `X-Ids: 1,2,3` |
| query (deepObject) | `deepObject` | `true` | `?filter[a]=1&filter[b]=2` |

The reason this matters in practice is that mismatched `explode` settings are a leading cause of "my array parameter is parsed as one string" bugs between a generated client and a server. If your server framework expects repeated keys (`?id=1&id=2`) but the spec says `explode: false`, the generated client will send `?id=1,2,3` and the server will receive a single value `"1,2,3"`. When integrating a generated SDK against an existing backend, the first thing to verify is that the spec's `style`/`explode` matches the server's actual binding behavior.

### 🟡 Intermediate — extended

#### Q24. [Theory] Explain `allOf`, `oneOf`, and `anyOf`. When does each belong in a real schema?

These are JSON Schema composition keywords, and choosing the wrong one produces confusing SDKs and validation. `allOf` means the value must satisfy **all** subschemas — it is intersection, used for **inheritance/composition** (merge a base schema with extra fields). `oneOf` means **exactly one** subschema must match — it models **mutually exclusive variants** (a payment is *either* a card *or* a bank transfer). `anyOf` means **at least one** matches — it is the loosest and is used when overlapping shapes are acceptable.

```yaml
components:
  schemas:
    Animal:                       # base
      type: object
      required: [type, name]
      properties:
        type: { type: string }
        name: { type: string }
      discriminator:
        propertyName: type
        mapping:
          cat: '#/components/schemas/Cat'
          dog: '#/components/schemas/Dog'
    Cat:
      allOf:                      # inheritance: Cat IS-A Animal + extra fields
        - $ref: '#/components/schemas/Animal'
        - type: object
          properties: { huntingSkill: { type: string } }
    Pet:
      oneOf:                      # exactly one variant
        - $ref: '#/components/schemas/Cat'
        - $ref: '#/components/schemas/Dog'
      discriminator: { propertyName: type }
```

The decisive trade-off is generator behavior. `allOf` flattens cleanly into a single class (or a subclass with inheritance), so it is safe and well-supported. `oneOf`/`anyOf` **require a `discriminator`** for generators to emit a usable sealed/polymorphic type; without one they fall back to `Object` or an "any" wrapper that defeats the point of code generation. The rule I apply: use `allOf` for "is-a / has-the-fields-of" relationships, use `oneOf` + `discriminator` for genuine variants, and avoid `anyOf` unless you truly need overlapping membership — it is the hardest for both validators and humans to reason about.

#### Q25. [Practical] A partner says your `/v3/api-docs` returns a 500 or an empty/incorrect spec at runtime. How do you debug springdoc generation failures?

Runtime spec-generation failures in code-first springdoc are usually caused by something in the type graph that the scanner cannot resolve, and the stack trace at `/v3/api-docs` is the first clue. The most common culprits are recursive generics, an unresolvable `@Schema(implementation = ...)`, a controller method returning a raw `ResponseEntity` with no type information, or a conflicting bean during the `OpenAPI` build.

```bash
# 1. Hit the JSON endpoint directly and capture the error + stack trace
curl -sS http://localhost:8080/v3/api-docs | jq . || echo "non-JSON / 500"

# 2. Turn on springdoc + swagger debug logging
#    application.yml:
#      logging.level.org.springdoc: DEBUG
#      logging.level.io.swagger: DEBUG

# 3. Narrow the scan to bisect the offending controller
#      springdoc.packages-to-scan: com.acme.api.users
```

The debugging method is bisection: narrow `packages-to-scan` (or temporarily comment out controllers) until the spec generates, then reintroduce until it breaks — that isolates the offending endpoint. Once isolated, inspect its DTOs for unbounded recursion (a self-referential type without a terminating `$ref`), `Map<String, ?>` wildcard generics, or `Object`/`?` return types that the resolver can't model.

A second class of failure is **lazy generation surprises**: springdoc builds the spec on first request, so a failure only appears when someone hits `/v3/api-docs`, not at startup. Add a smoke test in CI that calls `/v3/api-docs` and asserts HTTP 200 + valid JSON, so generation failures fail the build instead of surfacing in front of a partner. Also confirm you are on a springdoc version compatible with your Spring Boot version — a mismatched 1.x (Boot 2/javax) against Boot 3 (Jakarta) silently produces an empty or broken document.

#### Q26. [Theory] What is `additionalProperties`, and how do its values (`true`/`false`/schema) change generated code and validation?

`additionalProperties` controls whether an object may carry keys beyond those listed in `properties`, and its three forms produce materially different validation and codegen. `true` (the default if omitted) allows any extra keys with any value; `false` forbids extra keys entirely (strict objects); and a **schema** value constrains the *values* of all extra keys, which is how you model a map/dictionary.

```yaml
StrictUser:
  type: object
  properties: { id: { type: integer } }
  additionalProperties: false        # extra keys -> validation error

StringMap:
  type: object
  additionalProperties:              # a map of string -> string
    type: string
  # generates Map<String, String> in Java/Kotlin generators

LooseConfig:
  type: object
  additionalProperties: true         # generates Map<String, Object> (lossy)
```

The codegen impact is the practical hook. A schema-valued `additionalProperties` becomes a typed `Map<String, T>`, which is exactly what you want for dictionaries. But `additionalProperties: true` (or omitting it on a free-form object) collapses to `Map<String, Object>` / `JsonNode`, erasing type safety in the SDK — this is one of the "spec smells" that produces useless generated types. On the validation side, `additionalProperties: false` is powerful for **rejecting unknown fields** (defense against typos and mass-assignment), but it is also a notorious **backward-compatibility hazard**: adding a new field to a response is normally non-breaking, yet if a strict consumer validates with `additionalProperties: false`, your additive change suddenly breaks them. The standard guidance is to be liberal in what you accept and strict only where you deliberately want a closed contract.

#### Q27. [Practical] How do you document file upload and download endpoints in OpenAPI 3.x?

File handling looks deceptively simple but has format-specific rules in 3.x that differ from the old 2.0 `formData`. For uploads you model the request body as `multipart/form-data` (or `application/octet-stream` for a raw binary body), using `type: string, format: binary` for the file part. For downloads you describe the response content type and again use the binary string format.

```yaml
paths:
  /avatars:
    post:                          # multipart upload (file + metadata field)
      requestBody:
        content:
          multipart/form-data:
            schema:
              type: object
              properties:
                file:
                  type: string
                  format: binary
                caption:
                  type: string
      responses: { '201': { description: Created } }
  /reports/{id}/pdf:
    get:                           # binary download
      responses:
        '200':
          description: The report PDF
          content:
            application/pdf:
              schema:
                type: string
                format: binary
```

Two practical points. First, in **OpenAPI 3.1** the `format: binary` keyword was dropped from the JSON Schema vocabulary; the spec instead recommends `contentMediaType` and `contentEncoding`, though for compatibility most tooling still accepts `format: binary` and many teams keep using it. Second, generators map `format: binary` to language-appropriate streaming types (`Resource`/`InputStreamResource` in Spring, `byte[]` or file handles in clients), so getting this right is what makes the generated upload/download methods usable rather than forcing you to hand-write the multipart plumbing. For large files, document streaming behavior in the description because the spec itself can't express chunking semantics.

#### Q28. [Theory] What are `links` and `callbacks` in OpenAPI, and what real problems do they solve?

`links` and `callbacks` are two of the most underused 3.x features, and both describe relationships the basic request/response model can't capture. A **Link** describes how the output of one operation can feed the input of another — for example, the `id` in a `POST /users` response can be used as the path parameter of `GET /users/{id}`. It is OpenAPI's lightweight answer to HATEOAS: it documents traversable relationships *without* requiring hypermedia in the payload.

```yaml
paths:
  /users:
    post:
      responses:
        '201':
          description: Created
          content:
            application/json:
              schema: { $ref: '#/components/schemas/User' }
          links:
            GetUserById:
              operationId: getUser
              parameters: { id: '$response.body#/id' }   # wire output->input
  /subscriptions:
    post:
      callbacks:                    # describe the webhook WE will call back
        onEvent:
          '{$request.body#/callbackUrl}':
            post:
              requestBody:
                content:
                  application/json:
                    schema: { $ref: '#/components/schemas/Event' }
              responses: { '200': { description: ack } }
```

A **Callback** describes an asynchronous, out-of-band request that *your API* will make back to the consumer — i.e., a webhook tied to a specific operation. The runtime-expression syntax (`$request.body#/callbackUrl`) lets you say "we will POST to whatever URL the caller registered in the original request body." This is invaluable for documenting payment webhooks, async job completion, and subscription events that are otherwise invisible in a pure request/reply spec. The trade-off is tooling maturity: Swagger UI renders both but few generators *implement* them, so they are primarily documentation and contract-testing aids rather than codegen drivers. In 3.1, top-level `webhooks` largely supersedes operation-scoped callbacks for the common "describe our outbound webhooks" case.

#### Q29. [Practical] What categories of changes are breaking vs non-breaking, and how does a tool like `oasdiff` classify them?

Treating backward compatibility rigorously is what separates a stable API from one that breaks clients on every release, and the classification follows from the *direction* of the change relative to existing consumers. A change is **breaking** if it can cause a previously valid client request to fail or a previously valid response to no longer parse.

```
NON-BREAKING (safe, additive)        BREAKING (requires version bump / migration)
─────────────────────────────       ─────────────────────────────────────────────
+ new optional request field         - remove an endpoint or method
+ new endpoint / operation           - remove/rename a response field
+ new optional query param           - make an optional request field required
+ new enum value in a RESPONSE       - add a new enum value a client must handle (req)
+ new response (e.g. add 429)        - narrow a type (string -> integer)
+ relax a constraint (maxLength↑)    - tighten a constraint (maxLength↓, new pattern)
                                     - change a success status code (200 -> 201)
```

```bash
# Fail CI if the new spec breaks the old one
oasdiff breaking https://registry/acme/api/1.4.0/openapi.yaml ./openapi.yaml \
  --fail-on ERR
```

`oasdiff` (and `openapi-diff`) walk both documents and emit a severity-tagged report — `ERR` for breaking, `WARN` for risky-but-tolerable, `INFO` for additive — which you wire into CI to block merges that introduce breakage without an explicit version bump or override label. The subtle cases are worth memorizing: **adding an enum value is non-breaking for responses but breaking for requests** (servers may now accept values old clients never send, but old servers reject values new clients send — and clients with exhaustive switch statements may break on new response values), and **loosening** a constraint is safe while **tightening** it is breaking. Automating this classification removes the human judgment error that causes most accidental client breakage.

### 🟠 Advanced — extended

#### Q30. [Theory] How does `discriminator` actually work, and what are its constraints and failure modes?

The `discriminator` is the bridge that turns a `oneOf`/`anyOf` (or `allOf` inheritance) into a usable polymorphic type by naming a property whose *value* selects the concrete subtype. It exists because validators and code generators otherwise have to brute-force every subschema; the discriminator lets them read one field and jump straight to the right type, which is both faster and what enables clean sealed-class generation.

```yaml
Pet:
  type: object
  required: [petType]
  properties:
    petType: { type: string }
  discriminator:
    propertyName: petType        # the field that selects the subtype
    mapping:                     # value -> schema (optional; defaults to schema name)
      cat: '#/components/schemas/Cat'
      dog: '#/components/schemas/Dog'
```

There are firm constraints. The `propertyName` field **must be a required property** in the schema (otherwise the selector can be absent and resolution is undefined). When `mapping` is omitted, the discriminator value is matched against the **schema name** in `components/schemas`, so `petType: "Cat"` maps to `#/components/schemas/Cat` — meaning your wire values are coupled to your schema names unless you supply an explicit mapping. The discriminator must be used together with `oneOf`/`anyOf` or in an inheritance `allOf` chain; it does nothing on a plain object.

The classic failure modes: a discriminator value with no mapping entry (and no matching schema name) leaves the validator unable to resolve the type; a `propertyName` that isn't required causes intermittent failures when the field is missing; and **inconsistent values** (server sends `"CAT"`, mapping expects `"cat"`) silently break deserialization in the generated client. Because the spec can't enforce that the runtime actually emits the discriminator value, a contract test that round-trips each variant through the real implementation is the only reliable guard.

#### Q31. [Practical] How do you split a large OpenAPI document across multiple files and bundle it for distribution?

Beyond a few thousand lines, a single YAML becomes unmergeable (every PR conflicts) and slow for tooling, so mature specs are authored as a tree of files joined by `$ref` and **bundled** into a single artifact for publishing. The authoring tree typically separates paths, shared schemas, and parameters into their own files; the bundle step inlines or dereferences them so consumers receive one self-contained document.

```yaml
# openapi.yaml (root)
openapi: 3.1.0
info: { title: Acme API, version: 2.0.0 }
paths:
  /users:
    $ref: './paths/users.yaml#/Users'        # external path item
components:
  schemas:
    User:
      $ref: './schemas/user.yaml#/User'       # external schema
    Error:
      $ref: './schemas/common.yaml#/Error'    # shared across many specs
```

```bash
# Bundle: resolve external $refs into one file, keep internal $refs intact
redocly bundle openapi.yaml -o dist/openapi.bundled.yaml
# (alternative) swagger-cli bundle openapi.yaml -o dist/openapi.bundled.yaml -t yaml

# Validate the BUNDLED artifact, not the source tree, in CI
redocly lint dist/openapi.bundled.yaml
```

The key architectural decisions are *what* to externalize and *whether to dereference or bundle*. **Bundling** (`redocly bundle`) keeps internal `$ref`s so the SDK still gets reusable named types — this is what you want for distribution. **Dereferencing** fully inlines everything, producing a huge but ref-free document, which you only want for tools that can't follow `$ref`. The workflow that scales: author modular files, validate/lint the *bundled* output (so you test what consumers actually consume), publish the bundle to a registry, and keep the source tree for human editing. Shared modules like `common.yaml#/Error` can live in a separate repo pulled in as a package or submodule so dozens of specs reference one canonical error shape.

#### Q32. [Theory] What are vendor extensions (`x-` fields), and what legitimate and risky uses do they have?

Any field name beginning with `x-` is a **specification extension** — OpenAPI explicitly permits them and tools are required to ignore ones they don't understand, which makes them the sanctioned escape hatch for metadata the core spec doesn't model. They live anywhere in the document and carry arbitrary JSON. The legitimate uses cluster around tooling integration and governance.

```yaml
paths:
  /users:
    get:
      x-internal: true                  # hide from public docs (Redoc honors this)
      x-rate-limit: { tier: gold, rpm: 1000 }
      x-codegen-request-body-name: body # nudge a generator
      responses: { '200': { description: OK } }
components:
  schemas:
    User:
      x-go-type: 'models.User'          # generator-specific type mapping
      x-faker: 'name.firstName'         # mock-server data hints (Prism)
```

Common real-world extensions include `x-internal` (Redoc/SwaggerHub hide these from published docs), `x-faker`/`x-examples` (drive realistic mock data in Prism/Microcks), `x-amazon-apigateway-*` (AWS API Gateway integration config), `x-google-*` (Cloud Endpoints), and generator hints like `x-go-type`. The governance angle is that you can encode org-specific metadata — ownership, SLA tier, data-classification — as `x-` fields and then *lint on them* with Spectral, making them enforceable.

The risk is **lock-in and semantic drift**. Heavy reliance on a single vendor's `x-` fields couples your contract to that vendor; a partner consuming the spec may silently ignore behavior you assumed was guaranteed (since extensions are "ignore if unknown," critical behavior must never depend on an extension being honored). The discipline is to treat `x-` fields as *advisory metadata and tooling glue*, never as a place to smuggle in load-bearing contract semantics that a standards-compliant consumer would miss.

#### Q33. [Practical] How do you implement consumer-driven contract testing, and how does it differ from validating against an OpenAPI spec?

There are two distinct flavors of "contract testing," and conflating them causes teams to think they have coverage they don't. **Provider-contract testing** (Schemathesis, Dredd, Prism proxy) verifies that a running implementation conforms to *its own published OpenAPI spec* — it answers "does the server obey the contract it advertises?" **Consumer-driven contract testing** (Pact) inverts ownership: each *consumer* records the exact requests it makes and responses it expects, and those expectations become the contract the provider must satisfy — it answers "does the server still satisfy what its actual clients depend on?"

```bash
# Provider conformance: fuzz the live server FROM the spec
schemathesis run http://localhost:8080/v3/api-docs --checks all

# Provider verification of consumer expectations (Pact broker)
#   1. consumers publish pacts to the broker
#   2. provider CI verifies against them:
pact-provider-verifier --provider-base-url=http://localhost:8080 \
  --pact-broker-base-url=https://pact.acme.com --provider=user-service
```

The crucial difference is *what each catches*. OpenAPI conformance testing (Schemathesis) is broad and spec-derived — it fuzzes every documented operation and flags any response that violates the schema, catching server bugs and undocumented behavior. But it cannot tell you whether a change that is *spec-valid* will still break a real consumer who depended on a field you removed and updated the spec to match. Consumer-driven contracts catch exactly that: if a consumer's pact expects field `email` and the provider drops it, provider verification fails even though the new spec is internally consistent.

In a mature platform you use both: OpenAPI + Schemathesis to keep the implementation honest to its published contract, and Pact (or `oasdiff` against the version consumers are pinned to) to keep the provider honest to its *consumers*. The OpenAPI spec is the design-time source of truth; consumer contracts are the runtime reality check. Relying on only one leaves a gap — spec-conformance without consumer contracts ships "valid" breaking changes; consumer contracts without spec-conformance lets undocumented behavior creep in.

#### Q34. [Theory] What is `readOnly`/`writeOnly`, and how do they interact with required fields and a single shared schema?

`readOnly` and `writeOnly` solve the very common problem that one logical resource has fields that appear only in responses (server-assigned `id`, `createdAt`) or only in requests (a `password` you never echo back). Rather than maintaining two near-duplicate schemas, you mark such fields and let a single schema describe both directions, with the keywords telling tooling which fields are valid in which context.

```yaml
User:
  type: object
  required: [id, email, password]   # see the subtlety below
  properties:
    id:        { type: integer, readOnly: true }    # in responses, not requests
    email:     { type: string }
    password:  { type: string, writeOnly: true }    # in requests, not responses
    createdAt: { type: string, format: date-time, readOnly: true }
```

The subtle and frequently-missed rule is how these interact with `required`. Per JSON Schema/OAS semantics, a `readOnly` property that is also `required` is **only required in responses**, and a `writeOnly required` property is **only required in requests** — the validator is supposed to relax `required` for the direction where the field shouldn't appear. So in the example, a client `POST` is *not* required to send `id`/`createdAt` (they're response-only), and a response is *not* required to include `password`.

The trade-off is tooling support and clarity. Strict validators and good generators honor the read/write split and may even emit separate request/response classes; weaker ones ignore `readOnly`/`writeOnly` and treat everything as both-directional, which can let a client send a `readOnly` field that the server then silently accepts (a mass-assignment risk). Because support is uneven, many teams that need ironclad separation still split into explicit `UserRequest`/`UserResponse` schemas. Use the keywords for clean single-schema modeling when your toolchain honors them, but verify behavior — and never *rely* on `readOnly` alone to prevent over-posting; enforce it server-side.

#### Q35. [Practical] How do you wire OpenAPI into an API gateway (Kong/AWS API Gateway/Apigee) as a positive security model and for request validation?

An OpenAPI spec is not just documentation — gateways can import it to **reject any request that violates the schema before it reaches your service**, turning the contract into a positive security filter (allow only what's described, deny everything else). This shrinks the attack surface and offloads validation from the application.

```yaml
# AWS API Gateway: import the spec and enable request validation
x-amazon-apigateway-request-validators:
  full:
    validateRequestBody: true
    validateRequestParameters: true
paths:
  /users:
    post:
      x-amazon-apigateway-request-validator: full
      x-amazon-apigateway-integration:        # backend wiring lives in x- fields
        type: http_proxy
        httpMethod: POST
        uri: http://users.internal/users
```

```bash
# Kong: validate incoming requests against the OpenAPI contract
#   the oas-validation plugin loads the spec and rejects non-conforming requests
curl -X POST http://kong:8001/services/users/plugins \
  --data name=oas-validation \
  --data config.api_spec="$(cat openapi.bundled.yaml)" \
  --data config.validate_request_body=true
```

The pattern differs per platform but the principle is identical. AWS API Gateway consumes the spec (with `x-amazon-apigateway-*` extensions) to build routes *and* request validators in one import — invalid bodies/params are rejected with a 400 at the edge. Kong's `oas-validation` plugin and Apigee's OpenAPI-driven policies do the same. The big win is consistency: the same artifact that documents the API enforces it, so docs and validation can't drift.

The operational caveats matter. Gateway validation should *complement*, not replace, application-level validation — defense in depth, because someone may bypass the gateway internally. Keep the spec the gateway uses **in lockstep with the deployed code** (publish the bundle on merge and reload the gateway), or you get false rejections when the gateway enforces an older contract than the service implements. And remember the spec only validates *shape*, not business rules or authorization — those still live in the service. Used well, this is the "schema as WAF input" idea: the OpenAPI document becomes an executable allowlist at the perimeter.

### 🔴 Expert — extended

#### Q36. [Theory] Compare runtime spec generation (springdoc) vs build-time generation vs hand-authored contract — performance, drift, and operational trade-offs.

There are three fundamentally different points at which an OpenAPI document can come into existence, and the choice shapes performance, drift risk, and how the spec is governed. Understanding all three lets you pick deliberately rather than by default.

```
                 RUNTIME (springdoc)   BUILD-TIME GEN        HAND-AUTHORED (contract-first)
source of truth  Java code             Java code             the YAML itself
when built       first request / boot  during `mvn package`  authored directly
drift risk       low (always matches    low (matches code     reversed: code must
                 code) but spec is       at build) ; spec      conform to spec; caught
                 unreviewable artifact   committable           by contract tests
startup cost     scan + build per app   none at runtime       none
governance       lint the OUTPUT        lint the OUTPUT       lint the SOURCE (PR-reviewable)
best for         internal/fast-moving   want a committed      public/partner, multi-team
```

**Runtime generation** (springdoc) is the most convenient — the spec always reflects the code because it's derived from it — but the document is a *byproduct* you can't review in a PR, it adds a scan/build cost (mitigated by caching), and the lazy build means generation failures surface at request time. **Build-time generation** (e.g., a Maven goal that emits the spec during `package`) keeps the code-as-source-of-truth benefit while producing a committable artifact you can diff, lint, and breaking-change-check in CI — a strong middle ground. **Hand-authored contract-first** makes the YAML the reviewable source of truth, which is the right call for public/partner APIs and parallel teams, at the cost of needing contract tests to ensure the implementation actually conforms.

The operational decision hinges on *audience and drift direction*. For an internal service, runtime springdoc plus a CI snapshot + lint is pragmatic. For an SDK-publishing or partner-facing API, the spec must be a deliberate, reviewed product, so I move toward build-time-generated-and-committed or fully hand-authored. The anti-pattern is shipping a public API whose contract no human ever reviewed because it's silently derived from Jackson serialization at runtime.

#### Q37. [Practical] You inherited a Swagger 2.0 codebase that must move to OpenAPI 3.1. Plan and execute the migration safely.

A 2.0-to-3.1 migration is two jumps in one (2.0 → 3.0 structural changes, then 3.0 → 3.1 JSON Schema alignment), and doing it blindly breaks generated SDKs and validators. The safe approach is incremental, tool-assisted, and gated by diffs at each step rather than a big-bang rewrite.

```bash
# Step 1: mechanical 2.0 -> 3.0 conversion
swagger2openapi swagger.json -o openapi.3.0.yaml      # or api-spec-converter

# Step 2: validate + lint the 3.0 output before going further
redocly lint openapi.3.0.yaml

# Step 3: 3.0 -> 3.1 (mostly hand/semi-automated; fix nullable, examples, etc.)
#   then re-validate
redocly lint openapi.3.1.yaml

# Step 4: regenerate the SDK and DIFF against the old one
oasdiff breaking openapi.3.0.yaml openapi.3.1.yaml --fail-on ERR
```

The structural changes from **2.0 → 3.0** are the big mechanical ones: `host`+`basePath`+`schemes` collapse into the `servers` array; `definitions` move under `components/schemas`; body parameters (`in: body`) become the Request Body Object; `securityDefinitions` becomes `components/securitySchemes` and `oauth2` flow shapes change; `produces`/`consumes` are replaced by per-response/request `content` media types. `swagger2openapi` handles most of this automatically.

The **3.0 → 3.1** changes are subtler and often manual: replace every `nullable: true` with `type: [<t>, "null"]`; convert boolean `exclusiveMinimum`/`exclusiveMaximum` into numeric forms; migrate singular `example` toward `examples`; and reconsider `format: binary` for file fields. The migration discipline is to **change the spec without changing the API**: run `oasdiff` after each conversion step to prove the wire contract didn't shift, regenerate the SDK and run the existing test suite against it, and keep the old 2.0 doc published until consumers have migrated. Verify your whole toolchain (generator, gateway import, linter) actually supports 3.1 *before* committing — that compatibility check is the single most common thing teams skip and regret.

#### Q38. [Theory] How do you model and govern pagination, filtering, and partial responses consistently across hundreds of endpoints?

Pagination and filtering are where API inconsistency becomes most painful — every team inventing its own `page`/`offset`/`cursor` scheme produces SDKs that feel like different products — so the expert move is to standardize the *patterns* and enforce them with reusable components plus governance rules. The first decision is offset vs cursor pagination, which is a genuine trade-off, not a style choice.

```yaml
components:
  parameters:
    Cursor:
      name: cursor
      in: query
      schema: { type: string }
      description: Opaque cursor from the previous page's `next` link
    PageSize:
      name: limit
      in: query
      schema: { type: integer, default: 50, maximum: 200 }
  schemas:
    PageMeta:
      type: object
      properties:
        next: { type: string, nullable: true }   # opaque cursor or null at end
        total: { type: integer }
# every list endpoint $ref's the SAME parameters + envelope
paths:
  /users:
    get:
      parameters:
        - $ref: '#/components/parameters/Cursor'
        - $ref: '#/components/parameters/PageSize'
```

**Offset/limit** is simple and lets clients jump to arbitrary pages, but it's slow on large tables (deep offsets scan-and-discard) and unstable under concurrent writes (rows shift between pages). **Cursor (keyset) pagination** is stable and fast at scale because it seeks from a known key, but it forbids random page access — it's the right default for large, frequently-mutated collections, which is why Stripe, Slack, and GitHub all use it. Filtering should standardize on a documented convention (e.g., `?status=active&created_after=...`, or a structured `filter[field]=value` deep-object style) rather than ad-hoc query params per endpoint; partial responses standardize on a `fields=` sparse-fieldset parameter.

The governance mechanism is what makes consistency real at scale: publish the cursor/limit parameters and the page-envelope schema as **shared `components`** that every list endpoint must `$ref`, then write a **Spectral rule** that flags any operation returning an array-like collection that doesn't reference the standard pagination parameters. That converts "please paginate consistently" from a code-review plea into an automated merge gate. The same applies to the response envelope — one canonical `PageMeta`/`Error` shape referenced everywhere, so all generated SDKs expose pagination identically.

#### Q39. [Practical] Production incident: clients are intermittently failing deserialization after a deploy, but the OpenAPI spec "didn't change." Walk through the investigation.

This is the canonical *implementation-drifted-from-contract* incident, and the misleading framing ("spec didn't change") is itself the clue: the published contract is unchanged, but the *runtime behavior* diverged from it, so clients generated against the old-but-still-current spec now receive payloads the contract forbids. The investigation works backward from the failing payload to the code change that produced it.

```bash
# 1. Capture an actual failing response and validate it against the LIVE spec
curl -sS https://api.acme.com/orders/42 -H 'Accept: application/json' > resp.json
# validate the real response against the published schema
openapi-validate-response openapi.yaml --operation getOrder resp.json
#   -> reveals e.g. "field 'total' was string, schema says number"
#      or "unexpected null for non-nullable 'shippedAt'"

# 2. Bisect what shipped: diff the deploy
git log --oneline <last-good-sha>..HEAD -- src/main/java
```

The usual root causes, in rough order of frequency: (1) a serialization change — someone switched a Jackson config, changed a field type (`BigDecimal` → `String`), or a new Jackson version altered date/number formatting — so the body no longer matches the schema; (2) a field that became `null` in a new code path while the schema says non-nullable (in OAS 3.0, a missing `nullable: true`); (3) a new enum value the server now emits that old generated clients with exhaustive switches reject; (4) an added field that a strict consumer validating `additionalProperties: false` rejects. The "intermittent" nature usually points to a data-dependent path (only certain orders have the null/new-enum), which is why it passed smoke tests.

The fix and the prevention are separate. **Immediate:** roll back or hotfix the serialization so the runtime conforms to the published contract again — the contract is the promise, and you restore the promise. **Prevention:** add a **contract-conformance gate** (Schemathesis against the live build, or response-validation middleware in staging) so any future drift between code and spec fails CI instead of paging on-call. This incident is precisely the gap that runtime contract testing closes: a breaking-change diff on the *spec* can't catch it because the spec never changed — only the implementation did. The lesson I'd write in the postmortem: "the spec is only the source of truth if something continuously proves the implementation still obeys it."

#### Q40. [Theory] Where does OpenAPI sit in an AI/LLM tool-calling world, and what changes when specs become machine-consumed by agents?

OpenAPI has quietly become one of the primary ways software exposes capabilities to LLM agents, because a well-formed spec is exactly what a model needs to call an API: typed parameters, descriptions, and response shapes that map almost directly onto function/tool definitions. ChatGPT plugins were defined by an OpenAPI document; frameworks like LangChain and the broader tool-calling ecosystem convert OpenAPI operations into callable tools; and MCP (Model Context Protocol) servers frequently wrap existing OpenAPI-described APIs. The spec is shifting from a human/SDK artifact to a **machine-consumed capability manifest**.

```yaml
paths:
  /flights/search:
    get:
      operationId: searchFlights          # becomes the tool/function NAME
      summary: Find flights between two cities on a date
      description: >                       # the model reads THIS to decide when to call
        Use when the user wants to find available flights. Returns
        flights sorted by price. Does not book — call bookFlight to reserve.
      parameters:
        - { name: from, in: query, required: true,
            schema: { type: string }, description: IATA origin code, e.g. SFO }
      responses:
        '200': { description: matching flights, content: { application/json:
                 { schema: { $ref: '#/components/schemas/FlightList' } } } }
```

What changes is that **prose quality and precision become first-class engineering concerns**, not documentation niceties. The `operationId` becomes the function name the model reasons about; the `description` and `summary` are what the model reads to decide *whether and how* to call the endpoint; parameter descriptions and enums constrain the arguments the model generates. Vague or missing descriptions that a human would tolerate cause an agent to call the wrong endpoint or hallucinate parameters. So the discipline tightens: every operation needs a crisp, action-oriented description, enums must be exhaustive (so the model doesn't invent values), and side-effecting operations should say so explicitly.

The deeper trade-offs are **token budget and safety**. A large spec can't be dumped wholesale into a context window, so agent platforms select or summarize relevant operations — which rewards modular, well-tagged specs and good `operationId`/description hygiene. On safety, an agent that can *execute* operations turns documentation accuracy into an action-correctness problem: a mislabeled destructive endpoint is no longer a doc bug, it's an agent that deletes the wrong thing. This is also why design-time governance (Spectral rules mandating descriptions, examples, and explicit side-effect annotations) and runtime guardrails (auth scopes, human-in-the-loop on destructive ops) matter more in an agent world. OpenAPI's role expands from "describe the API for developers" to "be the safe, precise contract a machine acts on" — which raises the bar on exactly the quality practices this guide has emphasized throughout.

## 🧩 Extended Questions — Set 2: Coding, Design & Expert Scenarios

### 🟢 Basic — extended

#### Q41. [Coding] Write a minimal but complete OpenAPI 3.1 document for a single "create todo" endpoint, by hand.

A surprising number of candidates can annotate a controller but cannot author a valid spec from a blank file, which matters the moment you go contract-first. The goal here is a self-contained document that validates, generates a usable SDK, and renders correctly in Swagger UI — exercising the root object, a path, a request body, a typed response, and a reusable schema.

```yaml
openapi: 3.1.0
info:
  title: Todo API
  version: 1.0.0
  description: A minimal todo service.
servers:
  - url: https://api.acme.com/v1
paths:
  /todos:
    post:
      operationId: createTodo
      summary: Create a todo item
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/NewTodo' }
      responses:
        '201':
          description: Created
          content:
            application/json:
              schema: { $ref: '#/components/schemas/Todo' }
        '400':
          description: Validation failed
          content:
            application/json:
              schema: { $ref: '#/components/schemas/Error' }
components:
  schemas:
    NewTodo:
      type: object
      required: [title]
      properties:
        title: { type: string, minLength: 1, maxLength: 200 }
        done:  { type: boolean, default: false }
    Todo:
      type: object
      required: [id, title, done]
      properties:
        id:    { type: integer, format: int64, readOnly: true }
        title: { type: string }
        done:  { type: boolean }
    Error:
      type: object
      required: [code, message]
      properties:
        code:    { type: integer }
        message: { type: string }
```

The deliberate choices here are the ones that separate a sloppy spec from a good one even at this size. The request and response use **separate schemas** (`NewTodo` vs `Todo`) so the server-assigned `id` is `readOnly` and never expected in the request body — avoiding the mass-assignment trap of reusing one entity. Every operation has an `operationId` (the SDK method name and, increasingly, the LLM tool name), `format: int64` is specified so large IDs don't overflow `int32`, and the error path references a shared `Error` schema rather than an inline shape. Validate it with `redocly lint todo.yaml` or `swagger-cli validate todo.yaml` before committing — a spec that doesn't validate is worse than no spec because tooling fails in confusing ways downstream.

#### Q42. [Coding] Given a JSON example payload, reverse-engineer a sensible OpenAPI schema for it.

A common day-one task is being handed a real JSON response and asked to write its schema — for example to onboard a third-party API into your contract. The skill is translating concrete values into appropriate types, formats, and required/optional decisions rather than blindly producing `type: object` with everything as `string`.

Given this payload:

```json
{
  "id": 4815,
  "email": "ada@example.com",
  "balance": 100.50,
  "tags": ["vip", "beta"],
  "createdAt": "2026-06-16T09:30:00Z",
  "address": { "city": "London", "zip": null }
}
```

A faithful schema:

```yaml
Account:
  type: object
  required: [id, email, balance, createdAt]   # tags/address inferable as optional
  properties:
    id:      { type: integer, format: int64 }
    email:   { type: string, format: email }
    balance: { type: number, format: double }       # money: see note below
    tags:
      type: array
      items: { type: string }
    createdAt: { type: string, format: date-time }   # ISO-8601 -> date-time
    address:
      type: object
      properties:
        city: { type: string }
        zip:  { type: [string, "null"] }             # observed null -> nullable
```

The judgment calls are what an interviewer probes. `100.50` becomes `number/double`, but for **money** the experienced answer is to flag it: floating-point doubles lose precision on currency, so you'd ideally model it as `type: string` with a `pattern` (or a documented minor-unit integer) rather than `double` — the JSON happens to be a double but the *correct* contract may differ. The `Z`-suffixed timestamp maps to `format: date-time`; the explicit `null` on `zip` forces a nullable type (`type: [string, "null"]` in 3.1, or `nullable: true` in 3.0). Fields present in one sample aren't necessarily required — you mark `required` only for fields you're confident always appear, and ideally confirm against multiple samples or the provider's docs rather than over-constraining from a single example.

#### Q43. [Coding] Write a small bash/CI snippet that validates and lints an OpenAPI file and fails the build on errors.

The first automation any contract-first repo needs is a gate that rejects a malformed or non-conforming spec before it ever reaches a consumer. This is a few lines, but the details — exit codes, fail-severity, and ordering — are what make it actually block bad merges instead of just printing warnings.

```bash
#!/usr/bin/env bash
set -euo pipefail                       # fail fast; any non-zero exits the script

SPEC="openapi/api.yaml"

echo "==> 1. Structural validation"
npx @redocly/cli@latest lint "$SPEC" || { echo "Spec is invalid"; exit 1; }

echo "==> 2. Governance lint (style guide)"
npx @stoplight/spectral-cli lint "$SPEC" \
    --ruleset .spectral.yaml \
    --fail-severity=error            # warnings don't fail; errors do

echo "==> 3. Breaking-change check vs the published version"
npx oasdiff breaking \
    "https://registry.acme.com/api/latest/openapi.yaml" "$SPEC" \
    --fail-on ERR

echo "All contract checks passed."
```

The load-bearing details: `set -euo pipefail` ensures a failing tool actually aborts the pipeline (without it, a failed `lint` would print an error and the script would happily continue to "success"). `--fail-severity=error` on Spectral is what lets you ship `warn`-level rules during a governance rollout without blocking teams, then promote them to `error` later. The `oasdiff breaking ... --fail-on ERR` step compares the PR's spec against the currently published version and fails only on genuinely breaking changes, so additive changes sail through while removals/narrowings are caught. Ordering matters — validate structure first (a malformed file can't be meaningfully linted or diffed), then govern, then check compatibility — so failures are reported at the most useful layer.

#### Q61. [Practical] In Swagger UI, the "Try it out" button works but every call returns 401. What are the likely causes and fixes?

This is one of the most common first-week support questions, and the failure is almost always that the *documented* auth and the *actually-sent* auth diverge — Swagger UI is sending no credential, the wrong header, or a credential the server rejects, even though the endpoint genuinely requires one. Working through the layers quickly resolves it.

```
"Try it out" -> 401 — diagnostic checklist
────────────────────────────────────────────────────────────────────
1. Did you click "Authorize" and paste a token?  (UI sends nothing otherwise)
2. Is a securityScheme actually DEFINED + referenced by the operation?
   - no scheme -> no Authorize button -> no Authorization header sent
3. bearerAuth: are you pasting the raw JWT (UI adds "Bearer "), or
   double-prefixing it as "Bearer Bearer eyJ..."?
4. apiKey: is `in`/`name` correct (header X-API-Key vs query)?
5. CORS / wrong server: UI calling a `servers[]` URL that rejects the origin.
```

The single most frequent cause is simply not having clicked **Authorize** and entered a credential — Swagger UI only attaches an `Authorization` header (or API-key header) if a `securityScheme` is defined, referenced by the operation (globally or per-op), *and* the user has authorized. If the scheme is missing from the spec entirely, there's no Authorize button and the UI sends an unauthenticated request that correctly 401s. The fix is to define the scheme (e.g., `bearerAuth`) and apply it, as in the security questions earlier.

The subtler causes are formatting and target mismatches. For `http`/`bearer`, Swagger UI prepends `Bearer ` itself, so pasting `Bearer eyJ...` yields a double-prefixed, rejected header — paste the raw token only. For `apiKey`, the `in` and `name` must match what the server reads (`header` `X-API-Key` vs a query param). And a `servers[]` entry pointing at a different origin than where the UI is hosted triggers CORS or hits a backend that rejects the token. The general debugging move is to open the browser network tab, inspect the exact request Swagger UI sent, and compare its `Authorization`/key header against what the gateway expects — the discrepancy is always visible there. This reinforces the recurring theme: the spec *describes* auth, but whether a call succeeds depends on the runtime sending exactly what the enforcement layer wants.

#### Q62. [Practical] How do you keep the OpenAPI version, the build version, and the published docs version in sync automatically?

A subtle hygiene problem is three "versions" drifting apart: `info.version` in the spec, the artifact/build version (Maven/Gradle), and the version label on the published docs site. When they disagree, consumers can't tell which contract a given deployment actually serves, which undermines the whole "spec is the source of truth" claim. The fix is to derive them from one source rather than hand-editing each.

```xml
<!-- Maven: make the build version flow INTO the generated spec -->
<properties>
  <project.version>2.3.1</project.version>   <!-- single source -->
</properties>
```

```yaml
# springdoc reads it from a property you bind to the build version
springdoc:
  # populate info.version from the Maven/Gradle project version at build time
  # (e.g., via resource filtering of application.yml: @project.version@)
springdoc.api-docs.version: openapi_3_1   # spec dialect, NOT the API version
```

```java
// or set it programmatically from the injected build property
@Bean
OpenAPI api(@Value("${app.version}") String version) {
  return new OpenAPI().info(new Info().title("Acme API").version(version));
}
```

The principle is **single source, derived everywhere**. The build tool's project version is the canonical number; you inject it into `info.version` (via resource filtering of `application.yml` with `@project.version@`, or a `@Value`-bound bean) so the spec automatically reflects the artifact, and the publish step tags the docs site with the same value pulled from the same place. Now bumping the Maven version updates the spec and the docs label in lockstep, and there's no manual step to forget.

Two clarifications worth stating because they're routinely confused. `info.version` (your API's semantic version, e.g., `2.3.1`) is entirely distinct from the `openapi` field (the *spec dialect*, e.g., `3.1.0`) — they change for different reasons and should never be conflated. And the API version need not equal the *artifact* version in every shop: some teams keep `info.version` as the public contract version (bumped only on contract changes) while the build version increments on every release. If you decouple them, the rule is to still derive each from a declared source and document the relationship, so a consumer reading the spec always knows which contract a deployment serves. The anti-pattern is a developer hand-editing `info.version` and forgetting, leaving the published docs claiming `2.1.0` while the service serves `2.3.0` behavior.

#### Q63. [Coding] Document a "search users" GET endpoint with multiple optional filters, pagination, and a typed list response — by hand.

A bread-and-butter contract-first task is a collection endpoint with optional query filters and pagination, and doing it well exercises optional parameters, enums, defaults, constraints, and a properly typed array response. The trap is making everything a loose `string` or forgetting to type the list envelope.

```yaml
paths:
  /users:
    get:
      operationId: searchUsers
      summary: Search users with optional filters
      parameters:
        - { name: q,      in: query, required: false,
            schema: { type: string, minLength: 1 },
            description: Free-text match on name or email }
        - { name: status, in: query, required: false,
            schema: { type: string, enum: [active, suspended, deleted] } }
        - { name: createdAfter, in: query, required: false,
            schema: { type: string, format: date-time } }
        - { name: limit,  in: query, required: false,
            schema: { type: integer, default: 50, minimum: 1, maximum: 200 } }
        - { name: cursor, in: query, required: false,
            schema: { type: string },
            description: Opaque cursor from the previous page's `next` field }
      responses:
        '200':
          description: A page of matching users
          content:
            application/json:
              schema: { $ref: '#/components/schemas/UserPage' }
components:
  schemas:
    UserPage:
      type: object
      required: [data, next]
      properties:
        data:
          type: array
          items: { $ref: '#/components/schemas/User' }
        next: { type: [string, "null"], description: Cursor for the next page, null at end }
    User:
      type: object
      required: [id, email, status]
      properties:
        id:     { type: integer, format: int64 }
        email:  { type: string, format: email }
        status: { type: string, enum: [active, suspended, deleted] }
```

The deliberate choices: every filter is `required: false` (optional), `status` is an `enum` so the SDK gets a typed enum and bad values are rejected, `createdAfter` uses `format: date-time`, and `limit` has a `default`, `minimum`, and `maximum` so the contract documents the bound rather than leaving consumers to guess. Pagination is **cursor-based** (`cursor` in, `next` out) rather than offset, which is the scalable default for large mutable collections, and the `next` field is nullable to signal the last page.

The detail that separates a good answer from a mediocre one is **typing the list envelope**. A common mistake is returning a bare array (`type: array, items: User`) directly, which leaves no room for pagination metadata and forces a breaking change later to add it. Wrapping the array in a `UserPage` object with `data` plus `next` (and optionally `total`) means pagination is first-class from day one and additive metadata fits without breaking clients. Promoting both `User` and the page envelope to `components/schemas` gives the generated SDK reusable named types and lets a Spectral governance rule assert that every collection endpoint references the standard page envelope — the consistency mechanism from the pagination design question.

### 🟡 Intermediate — extended

#### Q44. [Coding] Model a polymorphic "Notification" payload (email / SMS / push) so the generated SDK produces a usable sealed type.

This is the canonical `oneOf` + `discriminator` design exercise, and getting it wrong is the single most common cause of "the SDK gave me `Object` instead of real types." The design must let a client construct or receive exactly one variant, with a field that unambiguously selects which.

```yaml
components:
  schemas:
    Notification:
      oneOf:
        - $ref: '#/components/schemas/EmailNotification'
        - $ref: '#/components/schemas/SmsNotification'
        - $ref: '#/components/schemas/PushNotification'
      discriminator:
        propertyName: channel          # MUST be required in every variant
        mapping:
          email: '#/components/schemas/EmailNotification'
          sms:   '#/components/schemas/SmsNotification'
          push:  '#/components/schemas/PushNotification'
    BaseNotification:
      type: object
      required: [channel]
      properties:
        channel: { type: string, enum: [email, sms, push] }
    EmailNotification:
      allOf:
        - $ref: '#/components/schemas/BaseNotification'
        - type: object
          required: [to, subject]
          properties:
            to:      { type: string, format: email }
            subject: { type: string }
            html:    { type: string }
    SmsNotification:
      allOf:
        - $ref: '#/components/schemas/BaseNotification'
        - type: object
          required: [phone]
          properties:
            phone: { type: string, pattern: '^\+[1-9]\d{6,14}$' }  # E.164
    PushNotification:
      allOf:
        - $ref: '#/components/schemas/BaseNotification'
        - type: object
          required: [deviceToken]
          properties:
            deviceToken: { type: string }
```

The architecture is a `BaseNotification` carrying the shared, required `channel` field, each variant composing it via `allOf` and adding its own fields, and the `Notification` wrapper using `oneOf` + `discriminator` to declare the variants. The `discriminator.propertyName` (`channel`) must be `required` in every variant — otherwise resolution is undefined when it's missing. The `enum` on `channel` constrains it to the three legal values, and the explicit `mapping` decouples the wire value (`email`) from the schema name (`EmailNotification`), which is good practice so renaming a schema doesn't change the API contract.

With this structure, openapi-generator emits a sealed/abstract `Notification` base with three concrete subclasses and deserializes by reading `channel` — exactly the ergonomic, type-safe SDK you want. Drop the discriminator and the same generator falls back to an `Object`/`anyType` wrapper, forcing consumers to cast manually. The only reliable guard that the runtime actually emits a correct `channel` value for each variant is a contract test that round-trips each one, since the spec can describe the discriminator but cannot enforce that your serializer populates it.

#### Q45. [Coding] Add a Spring `@RestControllerAdvice` so validation failures produce an RFC 9457 Problem Details response, and document it in OpenAPI.

A frequent gap is that DTOs have rich Bean Validation but the *error* responses are undocumented and inconsistent. The modern standard is **RFC 9457 Problem Details** (the renamed RFC 7807), and Spring Boot 3 has first-class support via `ProblemDetail`. The task is to wire a global handler and reflect its shape in the contract.

```java
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail onValidation(MethodArgumentNotValidException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST, "One or more fields failed validation");
        pd.setType(URI.create("https://errors.acme.com/validation"));
        pd.setTitle("Validation Failed");
        // attach a machine-readable list of field violations
        List<String> errors = ex.getBindingResult().getFieldErrors().stream()
            .map(f -> f.getField() + ": " + f.getDefaultMessage())
            .toList();
        pd.setProperty("errors", errors);       // custom extension member
        return pd;
    }
}
```

The corresponding contract uses the standard `application/problem+json` media type so consumers and tooling recognize it:

```yaml
components:
  schemas:
    Problem:
      type: object
      properties:
        type:     { type: string, format: uri, default: about:blank }
        title:    { type: string }
        status:   { type: integer }
        detail:   { type: string }
        instance: { type: string, format: uri }
        errors:                                 # our extension member
          type: array
          items: { type: string }
# referenced by every 4xx/5xx response:
#   content: { application/problem+json: { schema: { $ref: '#/components/schemas/Problem' } } }
```

Returning `ProblemDetail` makes Spring serialize the standard members (`type`, `title`, `status`, `detail`, `instance`) plus your custom `errors` array under the `application/problem+json` content type automatically. Documenting it once as a shared `Problem` schema and referencing it from every error response means all SDKs deserialize errors into one consistent type — a huge usability win over each endpoint inventing its own error JSON. The subtlety worth calling out: RFC 9457 explicitly allows **extension members** (here, `errors`), so attaching structured per-field detail is standards-compliant, not a hack — but you should document those extensions in the schema so consumers can rely on them.

#### Q46. [Coding] Configure springdoc to inject a global JWT security scheme and a server URL programmatically via an `OpenAPI` bean.

Annotations alone can't express document-level concerns like servers, global security, or contact info cleanly, so springdoc lets you supply an `OpenAPI` bean that it merges with the scanned operations. The frequent task is registering a bearer scheme once and applying it globally without annotating every controller.

```java
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String SCHEME = "bearerAuth";

    @Bean
    public OpenAPI apiInfo() {
        return new OpenAPI()
            .info(new Info()
                .title("Acme API").version("2.0.0")
                .contact(new Contact().name("Platform Team").email("api@acme.com")))
            .addServersItem(new Server().url("https://api.acme.com/v2"))
            .components(new Components().addSecuritySchemes(SCHEME,
                new SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")))
            // global requirement: every operation needs the JWT unless overridden
            .addSecurityItem(new SecurityRequirement().addList(SCHEME));
    }
}
```

The mental model is that springdoc builds a document from scanning controllers and then **merges** your bean's contributions on top, so document-level metadata (info, servers, components, global security) belongs in the bean while operation-level detail (summaries, responses, examples) stays in `@Operation`/`@ApiResponse` annotations on the methods. Defining `bearerAuth` once in `Components` and adding it as a top-level `SecurityRequirement` makes Swagger UI render the "Authorize" button and marks every operation as requiring the token in the generated spec.

To make a specific endpoint public despite the global requirement, you annotate the method with `@SecurityRequirements` (empty) — the equivalent of `security: []` at the operation level. The reason to prefer the bean over scattering `@SecurityScheme` annotations is single-source-of-truth: servers and auth change rarely and globally, so centralizing them avoids the drift where one controller declares a different scheme name and Swagger UI shows two conflicting "Authorize" entries.

#### Q47. [Coding] Write a Spectral rule that requires every schema property to have a `description`, and explain how to scope it to avoid noise.

Description coverage is the difference between a spec that generates helpful docs (and good LLM tool definitions) and one that's a wall of untyped names. Writing the rule is easy; scoping it so it doesn't fire on `$ref`s, enums-of-enums, or composition keywords is the part that demonstrates real Spectral fluency.

```yaml
# .spectral.yaml
extends: ["spectral:oas"]
rules:
  schema-properties-described:
    description: Every schema property must have a description
    message: "Property '{{property}}' is missing a description"
    severity: warn          # start at warn during rollout, promote to error later
    given: >-
      $.components.schemas[*].properties[*]
    then:
      field: description
      function: truthy
  # avoid noise: don't demand descriptions on pure $ref properties
  no-description-on-ref-only:
    description: A property that is only a $ref inherits the referenced description
    severity: off           # documents intent; the rule above must skip $ref-only nodes
    given: $.components.schemas[*].properties[?(@.$ref)]
    then:
      field: description
      function: undefined
```

The first rule targets `properties[*]` and uses the built-in `truthy` function on the `description` field, so any property whose description is missing or empty is flagged; `{{property}}` in the message interpolates the offending key for an actionable error. Starting at `severity: warn` is deliberate — flipping a coverage rule straight to `error` on a large existing spec drowns teams in failures, so you warn, publish a dashboard of the gap, and promote to `error` once coverage is high.

The scoping nuance is where this gets interesting. A property that is *only* a `$ref` (`{ $ref: '#/.../User' }`) legitimately has no description of its own — it inherits the referenced schema's — so a naive rule produces false positives on every reference. In practice you either refine the JSONPath with a filter that excludes `$ref`-only nodes (`$..properties[?(!@.$ref)]`) or accept that 3.1's `$ref`-with-siblings lets you add a description alongside the ref. The broader lesson is that governance rules must be *precise*: an overly broad rule that cries wolf gets disabled by frustrated teams, which is worse than no rule at all.

#### Q48. [Coding] Show how to define and reference reusable `examples` (named multi-examples) for a request body, and why this beats inline `example`.

Swagger UI's "Try it out" and generated docs are dramatically more useful when an operation ships several named, realistic examples (a happy path, an edge case, an error trigger) rather than one inline blob. OpenAPI 3.x supports a plural `examples` map with named entries, which is also the 3.1-preferred form since singular `example` is being phased out.

```yaml
paths:
  /payments:
    post:
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/PaymentRequest' }
            examples:                       # plural map of NAMED examples
              card:
                summary: Credit card payment
                value: { amount: 4999, currency: USD, method: card,
                         card: { number: "4242424242424242", exp: "12/29" } }
              bank:
                summary: Bank transfer (slower settlement)
                value: { amount: 150000, currency: EUR, method: bank,
                         iban: "DE89370400440532013000" }
              zeroAmount:
                summary: Edge case that triggers a 400
                value: { amount: 0, currency: USD, method: card }
components:
  examples:                                  # reusable across operations
    DeclinedCard:
      summary: A card that the processor declines
      value: { amount: 100, currency: USD, method: card,
               card: { number: "4000000000000002" } }
```

The structural points: `examples` (plural) is a *map* of names to **Example Objects**, each with `summary`, optional `description`, and `value` (or `externalValue` to point at a file). Swagger UI renders these as a dropdown so a tester can pick "card" vs "bank" vs the failing "zeroAmount" case, which makes interactive docs far more instructive than one canonical body. Examples can also be promoted to `components/examples` and `$ref`'d, so a `DeclinedCard` example reused across several endpoints stays consistent.

The reason this beats the singular `example` keyword is partly future-proofing — OAS 3.1 deprecates `example` in favor of `examples` — and partly expressiveness: you cannot show a happy path *and* an edge case with a single inline `example`. The one gotcha is that `example` and `examples` are mutually exclusive on the same media type object; mixing them is invalid, and some tooling silently ignores one, so pick `examples` and commit to it. Well-chosen examples also feed mock servers (Prism returns them) and serve as lightweight contract-test fixtures.

#### Q64. [Coding] Configure openapi-generator to map a spec `format: date` / `date-time` to `java.time` types and a custom money type.

Out-of-the-box type mappings often don't match a team's conventions — generators may map `date-time` to `OffsetDateTime` but you want `Instant`, or a `string` money field to `String` when you want a domain `Money` type. openapi-generator exposes `typeMappings` and `importMappings` precisely for this, and knowing them is what makes generated code fit your codebase instead of fighting it.

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
        <configOptions>
          <useSpringBoot3>true</useSpringBoot3>
          <dateLibrary>java8</dateLibrary>   <!-- java.time, not legacy Date -->
        </configOptions>
        <!-- override default type for date-time -->
        <typeMappings>
          <typeMapping>OffsetDateTime=Instant</typeMapping>
          <typeMapping>BigDecimal=Money</typeMapping>
        </typeMappings>
        <!-- tell the generator where to import the overridden types from -->
        <importMappings>
          <importMapping>Instant=java.time.Instant</importMapping>
          <importMapping>Money=com.acme.common.Money</importMapping>
        </importMappings>
      </configuration>
    </execution>
  </executions>
</plugin>
```

The mechanism is two cooperating maps. `typeMappings` rewrites the *generator's internal type name* (e.g., it would emit `OffsetDateTime` for `format: date-time`, or `BigDecimal` for a `number` you've tagged) to your preferred type; `importMappings` then supplies the fully-qualified import for that type so the generated file compiles. Both are needed — remap the type without the import and you get a class referencing `Money` with no `import`. The `dateLibrary=java8` option is the broad switch that moves the whole spec off legacy `java.util.Date` onto `java.time`.

The reason this matters in practice is that a generated SDK that uses the wrong temporal or numeric types forces consumers to write conversion boilerplate at every call site, which erodes the value of generation. Mapping money to a domain `Money` type (or at minimum `BigDecimal`, never `double`) also closes the precision footgun from earlier. The caveat: type mappings are *generator-specific* and bypass the generator's safety assumptions, so you verify the output compiles and round-trips (a `Money` that Jackson can't deserialize from the wire `number` defeats the purpose) — pair the mapping with a serialization test. Keep these overrides in the build config, not hand-edited into generated sources, so regeneration stays safe.

#### Q65. [Theory] What is the difference between `nullable`, an absent field, and a field explicitly set to `null` — and how do clients distinguish them?

This trips up even experienced engineers because JSON conflates "absent," "present-but-null," and "present-with-value" in ways the schema must disambiguate, and the distinction has real consequences for PATCH semantics and partial updates. A schema must say both whether a field *may be omitted* (via `required`) and whether its value *may be null* (via nullability), and these are independent axes.

```
            required?     nullable?     legal states for the field
─────────────────────────────────────────────────────────────────────
A  required, non-null     yes / no      must be present, must have a value
B  required, nullable     yes           must be present; value may be null
C  optional, non-null     no            may be absent; if present, has a value
D  optional, nullable     yes           absent  |  present:null  |  present:value
                                        (THREE distinguishable states)
```

```yaml
# 3.1: nullability via type array; 3.0: via nullable: true
PatchUser:
  type: object
  properties:
    nickname: { type: [string, "null"] }   # absent=leave, null=clear, "x"=set
    # NOT in `required` -> may be omitted
```

The four-way table is the core: `required` controls *presence*, nullability controls *whether the value may be `null`*, and they combine. The interesting case is **D (optional + nullable)**, which yields three semantically distinct client-observable states — field absent, field present as `null`, field present with a value — and this is exactly what a correct **JSON Merge Patch** (`PATCH`) needs: absent means "don't touch this field," `null` means "clear it," and a value means "set it." A schema that's only nullable-or-only-optional cannot express all three, so a PATCH endpoint that wants "clear vs leave-unchanged" semantics *requires* the optional+nullable combination.

In OAS 3.0 you express nullability with `nullable: true`; in 3.1 (which removed `nullable`) you use a type array `type: [string, "null"]`. The practical pitfall is on the *consumer* side: most generated SDKs and JSON libraries cannot natively distinguish "absent" from "present-null" because they deserialize both into a language `null`/`None` — so a client doing a merge-patch may be unable to send "absent" vs "null" without raw map manipulation or an `Optional`-wrapping library. The senior guidance: if your API relies on the absent-vs-null distinction (merge-patch, sparse updates), document it explicitly, prefer JSON Merge Patch (RFC 7396) or JSON Patch (RFC 6902) with a clear contract, and verify your SDK/serializer actually preserves the distinction rather than assuming the schema alone guarantees it.

#### Q66. [Coding] Write a request/response logging-and-validation filter (Spring) that validates live traffic against the OpenAPI spec in staging.

A powerful way to catch implementation-vs-contract drift early is to validate *actual* responses against the published schema in a non-prod environment, flagging any deviation before it reaches consumers. You can do this with a Spring filter (or `OncePerRequestFilter`) backed by a JSON Schema validator, turning the spec into a live conformance check on real traffic.

```java
import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.SimpleRequest;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;

// Uses the swagger-request-validator library (atlassian/swagger-request-validator)
public class SpecConformanceFilter extends OncePerRequestFilter {

    private final OpenApiInteractionValidator validator =
        OpenApiInteractionValidator.createForspecificationUrl("classpath:openapi/api.yaml").build();

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        var wrapped = new ContentCachingResponseWrapper(res);
        chain.doFilter(req, wrapped);

        String body = new String(wrapped.getContentAsByteArray());
        ValidationReport report = validator.validateResponse(
            req.getRequestURI(),
            com.atlassian.oai.validator.model.Request.Method.valueOf(req.getMethod()),
            SimpleResponse.Builder.status(wrapped.getStatus())
                .withContentType(wrapped.getContentType())
                .withBody(body).build());

        if (report.hasErrors()) {
            // STAGING: log loudly / increment a metric / fail a synthetic test.
            // Do NOT block the response in prod — observe, don't enforce here.
            logger.error("SPEC DRIFT on " + req.getMethod() + " " + req.getRequestURI()
                         + ": " + report.getMessages());
        }
        wrapped.copyBodyToResponse();   // must copy back or the client gets an empty body
    }
}
```

The design uses the `swagger-request-validator` library, which loads the OpenAPI document and validates a captured request/response pair against it, returning a `ValidationReport` of any schema violations. Wrapping the response in `ContentCachingResponseWrapper` is essential because a servlet response body is a write-once stream — you cache it to read for validation, then `copyBodyToResponse()` so the real client still receives it (forgetting that last line returns an empty body, a classic filter bug).

The crucial operational decision is **observe, don't enforce**, and *where* to run it. In staging you log/metric every deviation so spec drift surfaces as an alert or a failing synthetic test — exactly the gate that catches the "intermittent deserialization failure after deploy" incident before it reaches production. You deliberately do **not** block responses in production based on this, because (a) the validation adds latency and a failure mode, and (b) the spec might lag a legitimately-deployed behavior; an outbound validator that 500s real traffic because the schema is slightly stale is worse than the drift it's catching. So the pattern is: validate-and-alert in staging (or behind a sampling flag in prod), feed violations into CI/observability, and fix either the code or the spec — making the implementation continuously prove it still obeys its published contract, which is the only thing that makes "the spec is the source of truth" actually true at runtime.

### 🟠 Advanced — extended

#### Q49. [Coding] Implement a build-time check that snapshots springdoc's generated spec and fails CI when it drifts unexpectedly.

In code-first projects the spec is a byproduct, so the highest-leverage guard is a "golden file" test: generate the spec during an integration test, diff it against a committed snapshot, and surface any change as a reviewable diff rather than silent drift. This is the concrete mechanism behind the "snapshot the generated spec" advice.

```java
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@SpringBootTest
@AutoConfigureMockMvc
class OpenApiSnapshotTest {

    @Autowired MockMvc mvc;

    @Test
    void specMatchesCommittedSnapshot() throws Exception {
        String actual = mvc.perform(get("/v3/api-docs.yaml"))
            .andReturn().getResponse().getContentAsString();

        Path snapshot = Path.of("src/test/resources/openapi-snapshot.yaml");

        if (Boolean.getBoolean("updateSnapshot")) {       // -DupdateSnapshot=true
            Files.writeString(snapshot, actual);
            return;                                       // regenerate intentionally
        }
        String expected = Files.readString(snapshot);
        assertThat(actual)
            .as("OpenAPI spec drifted. Run with -DupdateSnapshot=true to accept, "
              + "then review the diff in your PR.")
            .isEqualTo(expected);
    }
}
```

The design centers on the `updateSnapshot` escape hatch. Without it, an intentional API change would fail the test forever; with it, a developer who changed the contract on purpose runs `-DupdateSnapshot=true`, the snapshot is rewritten, and the **diff shows up in the PR** for review — exactly converting silent drift into a reviewed artifact. The assertion message tells the developer how to accept the change, so the failure is self-explaining rather than mysterious.

Two refinements make this production-grade. First, raw string equality is brittle (key ordering, whitespace), so a better version parses both to a normalized structure or runs `oasdiff` between the live spec and the snapshot, failing only on *semantic* changes and classifying breaking ones. Second, pair this with a Spectral lint of the generated spec in the same CI stage, so a code-first API still meets the org style guide. The combination — snapshot diff plus lint plus breaking-change classification — gives code-first projects most of the governance guarantees that contract-first gets for free, which is the pragmatic way to keep a fast-moving service honest.

#### Q50. [Coding] Write a script that bundles a multi-file spec, then runs a breaking-change gate against the previously published bundle.

At scale the spec is a tree of files, so the CI flow must bundle first (resolve external `$ref`s into one artifact) and then run validation and breaking-change detection against the *bundle* — because that's what consumers actually receive. Doing the diff against the source tree instead of the bundle is a classic mistake that misses cross-file changes.

```bash
#!/usr/bin/env bash
set -euo pipefail

ROOT="openapi/openapi.yaml"
DIST="dist/openapi.bundled.yaml"
PUBLISHED="https://registry.acme.com/api/latest/openapi.bundled.yaml"

mkdir -p dist

echo "==> Bundle the multi-file source into one artifact"
npx @redocly/cli@latest bundle "$ROOT" -o "$DIST"

echo "==> Validate and lint the BUNDLE (what consumers consume)"
npx @redocly/cli@latest lint "$DIST"
npx @stoplight/spectral-cli lint "$DIST" --fail-severity=error

echo "==> Breaking-change gate: published bundle vs new bundle"
if npx oasdiff breaking "$PUBLISHED" "$DIST" --fail-on ERR; then
  echo "No breaking changes."
else
  # allow an explicit override via a PR label exported as an env var
  if [[ "${ALLOW_BREAKING:-false}" == "true" ]]; then
    echo "Breaking changes present but ALLOW_BREAKING=true (major version bump). Continuing."
  else
    echo "Breaking changes detected without a major-version override. Failing."
    exit 1
  fi
fi

echo "==> (on merge) publish the bundle as the new latest"
# curl -T "$DIST" "$PUBLISHED"   # or push to SwaggerHub / artifact registry
```

The crucial ordering is bundle → lint → diff → publish. **Bundling before diffing** ensures the comparison sees the fully-resolved contract, so a change buried in an external `schemas/user.yaml` is caught even though the root `openapi.yaml` line didn't change — the exact gap that catches teams who diff the source files. Linting the bundle (not the tree) tests what consumers actually consume, since a `$ref` that resolves fine locally might dangle once bundled.

The `ALLOW_BREAKING` override is the governance subtlety: breaking changes aren't *forbidden*, they're *gated*. A genuine major-version release legitimately breaks compatibility, so the pipeline blocks breaking changes **by default** but allows an explicit, auditable override (a PR label or env var that signals an intentional major bump). This encodes the policy "you may break, but only on purpose and visibly," which is exactly what semantic versioning is supposed to enforce. On merge, the validated bundle becomes the new `latest`, so the next PR diffs against it — the registry is the moving baseline that keeps every future change honest.

#### Q51. [Coding] Design a schema using JSON Schema `if/then/else` (conditional required fields) and discuss tooling limits.

Sometimes a field is required only when another field has a particular value — e.g., `shippingAddress` is mandatory only when `fulfillment` is `ship`, not `pickup`. OAS 3.1's full JSON Schema 2020-12 support means you can express this with `if/then/else`, a capability 3.0 lacked. The exercise tests whether you know the feature exists and, more importantly, whether you know its tooling caveats.

```yaml
# OpenAPI 3.1 only (requires JSON Schema 2020-12 conditional keywords)
Order:
  type: object
  required: [fulfillment, items]
  properties:
    fulfillment: { type: string, enum: [ship, pickup] }
    items:       { type: array, items: { $ref: '#/components/schemas/Item' } }
    shippingAddress: { $ref: '#/components/schemas/Address' }
    pickupStoreId:   { type: string }
  if:
    properties: { fulfillment: { const: ship } }
  then:
    required: [shippingAddress]      # ship => address mandatory
  else:
    required: [pickupStoreId]        # pickup => store id mandatory
```

Semantically this says: validate the base object, and *additionally*, if `fulfillment` equals `ship`, require `shippingAddress`; otherwise require `pickupStoreId`. The `const` keyword pins the condition to an exact value, and `if/then/else` compose so you can chain conditions. This is genuinely powerful for expressing business rules that previously had to live only in code or in prose, and a compliant validator (Ajv, the Java `networknt/json-schema-validator`) will enforce it correctly at runtime.

The hard-won caveat is **code-generator support**, which is far behind validators. Most generators (openapi-generator included) **ignore** `if/then/else` and emit a flat class where both `shippingAddress` and `pickupStoreId` are simply optional — the conditional requirement evaporates in the SDK. So you get correct *validation* but not correct *generated types*, which can lull a team into thinking the SDK enforces the rule. The pragmatic guidance: use conditional schemas when your runtime validation (gateway or app) is the enforcement point and the spec serves documentation/validation, but don't expect the generated client to model it — and consider whether splitting into two `oneOf` variants (`ShipOrder` / `PickupOrder` with a discriminator) would produce a cleaner, generator-friendly contract for the same intent.

#### Q52. [Coding] Generate a TypeScript client and a Java server from one spec in CI, and prove they're mutually consistent.

A core promise of contract-first is that a frontend and backend built from the *same* spec interoperate by construction. The exercise is to wire both generations into CI and add a check that proves consistency rather than just hoping the single source guarantees it.

```bash
#!/usr/bin/env bash
set -euo pipefail
SPEC="dist/openapi.bundled.yaml"

echo "==> Generate Java server interfaces (Spring, interface-only)"
npx @openapitools/openapi-generator-cli generate \
  -i "$SPEC" -g spring \
  --additional-properties=interfaceOnly=true,useSpringBoot3=true,useJakartaEe=true \
  -o build/server

echo "==> Generate TypeScript client (fetch)"
npx @openapitools/openapi-generator-cli generate \
  -i "$SPEC" -g typescript-fetch \
  -o build/client

echo "==> Compile both to PROVE the spec is implementable in each target"
(cd build/server && mvn -q -DskipTests compile)
(cd build/client && npm ci && npx tsc --noEmit)

echo "==> Contract test: run the server, fuzz it from the SAME spec"
# start the server (implemented against the generated interfaces) in the background,
# then verify the live implementation conforms to the spec it was generated from:
npx schemathesis run http://localhost:8080/v3/api-docs --checks all
```

The first proof is simply that **both generations compile**. If the spec contains something un-modelable in a target language (an unsupported composition, a name collision, a missing discriminator that yields an `any`), the generate-then-compile step fails loudly in CI — the spec is rejected before it ships. Compiling the TypeScript with `tsc --noEmit` and the Java with `mvn compile` turns "the spec generates code" into "the spec generates *valid* code in both ecosystems," which is a much stronger guarantee.

The deeper consistency proof is the contract test. Since the Java server implements the generated interfaces and the TS client is generated from the same document, they share types by construction — but that only guarantees they agree with the *spec*, not that the *running server* obeys it. Schemathesis closes that loop by fuzzing the live server from the same spec and flagging any response that violates the schema. Together, this is the chain that makes "one contract, many consumers" real: the spec is the single source, both sides are generated from it (so client and server types can't disagree), both compile (so the spec is implementable everywhere), and the runtime is fuzz-tested against it (so the implementation can't quietly drift). That is the entire value proposition of contract-first, demonstrated mechanically rather than asserted.

#### Q53. [Coding] Write an operation that returns different schemas per content type and per status code, and explain when this is appropriate.

Real APIs sometimes negotiate content (JSON vs CSV) and return structurally different bodies per status (a resource on 200, a Problem on 4xx, a partial on 206). OpenAPI's `content` map (keyed by media type) under each response, plus distinct response entries per status code, models this precisely. The exercise tests whether you can express it without collapsing everything into one vague schema.

```yaml
paths:
  /reports/{id}:
    get:
      operationId: getReport
      parameters:
        - { name: id, in: path, required: true, schema: { type: string } }
      responses:
        '200':
          description: Report found
          content:
            application/json:                     # structured data
              schema: { $ref: '#/components/schemas/Report' }
            text/csv:                             # same data, flat export
              schema: { type: string }
              example: |
                id,name,total
                1,Q1,4999
        '202':
          description: Report still generating; poll the Location header
          headers:
            Location: { schema: { type: string, format: uri } }
        '404':
          description: No such report
          content:
            application/problem+json:
              schema: { $ref: '#/components/schemas/Problem' }
```

The structure models two orthogonal dimensions: **status code** (each top-level key under `responses`) and **content type** (each key under a response's `content`). The 200 offers the same logical report as either structured `application/json` or flat `text/csv`, letting a client choose via the `Accept` header — appropriate when a resource has a genuinely useful alternate representation (export, rendering). The 202 carries no body but a `Location` header for async polling, and the 404 returns a Problem under `application/problem+json` — different *shape* for a different *outcome*.

When this is appropriate versus over-engineering is the judgment the interviewer wants. Multiple content types are justified when consumers really need them (a CSV export, an image rendering, a protobuf for high-throughput clients); they're a smell when you're papering over an undecided format. Per-status distinct schemas are almost always correct — success and error bodies *should* differ — and modeling them explicitly is what lets the generated SDK throw a typed `NotFoundException` carrying a `Problem` instead of a generic blob. The anti-pattern is one `200` with a giant union schema that "might contain" the report or an error; splitting by status and media type produces SDKs that are pleasant to use and docs that are honest about what each path actually returns.

#### Q54. [Theory] How do server-side and generated-client validation differ, and where can a spec-valid request still be rejected?

A subtle source of production bugs is the assumption that "passes the schema" equals "the server will accept it." There are several layers of validation, and the spec only governs one of them, so a request can be perfectly spec-conformant and still fail. Understanding the layering prevents blaming the wrong component.

```
REQUEST LIFECYCLE — where validation can reject a "spec-valid" request
──────────────────────────────────────────────────────────────────────
client SDK   ──►  gateway (OAS import)  ──►  framework binding  ──►  app logic
   │                  │                          │                     │
 schema check     schema check               type coercion        business rules
 (shape only)     (shape only)               + Bean Validation     (the spec can't
                                             (@Size, @Pattern...)    express these)
```

The spec describes **shape**: types, formats, required fields, enums, lengths, patterns. A generated client validates against that shape before sending; a gateway that imported the spec re-validates the same shape at the edge. But three categories of rejection live *beyond* the spec. First, **business rules**: "end date must be after start date," "this SKU is out of stock," "you've exceeded your quota" — none are expressible in JSON Schema, so a shape-valid request is rejected by app logic with a 400/409/422. Second, **stricter server-side Bean Validation** that the spec didn't fully capture (a cross-field `@AssertTrue`, a custom validator), or formats the spec marks loosely (`format: email` is a *hint* many validators don't enforce, so the server's `@Email` may reject what the schema accepted). Third, **authorization and state**: a request can be well-formed but forbidden (403) or invalid for the resource's current state (409 on a closed order).

The practical implication is that contract conformance is necessary but not sufficient, and you should document the non-shape failure modes explicitly. That means declaring 409/422 responses with a `Problem` schema and a `detail` explaining the business-rule class, and writing operation `description`s that state preconditions ("start must precede end"). It also means a `format` like `email` or `uuid` should be treated as documentation unless you've confirmed both the gateway and the framework enforce it — otherwise a client trusts the format, the server enforces it harder, and you get mismatched expectations. The mature framing: the spec is a *positive* shape filter, but correctness is layered, and good APIs make the non-shape layers visible in the contract through error responses and prose rather than letting them surprise the consumer.

#### Q67. [Practical] Two teams own overlapping schemas (`User` defined twice with subtle differences). How do you converge them without breaking either consumer?

Schema duplication across teams is a real organizational smell: `team-a/openapi.yaml` and `team-b/openapi.yaml` both define `User`, but one has `phone` required and the other has an extra `tier` field, and consumers have been generated against each. Converging them is part schema design and part change-management, because a naive "merge into one canonical `User`" silently breaks whichever consumer relied on the differences.

```
CONVERGENCE STRATEGY
─────────────────────────────────────────────────────────────────────
1. DIFF the two schemas mechanically (oasdiff / json-schema-diff)
      -> enumerate every field/required/type difference
2. Define the SUPERSET-compatible canonical schema in a shared module:
      - union of fields; required = INTERSECTION of the two `required` sets
        (so neither consumer's previously-optional field becomes required)
      - widen types to the more permissive where they differ
3. Publish canonical `User` in common.yaml; both specs $ref it
4. Run oasdiff: canonical-vs-each-old  ->  prove NON-breaking for both
5. Migrate consumers, then retire the duplicates
```

The schema-design core is computing a **backward-compatible superset**. The canonical `User` takes the *union* of all properties (so no consumer loses a field), but its `required` array is the *intersection* of the two original required sets — because promoting a field to required that one consumer treated as optional is a breaking change for that consumer's requests. Where types differ, you widen to the more permissive (e.g., if one had `tier: string` and the other an enum, the canonical uses the enum only if both already constrained it). Then you publish the canonical schema in a shared `common.yaml` module that both specs `$ref`, and you *prove* the convergence is safe by running `oasdiff` between the canonical schema and each original — if either reports a breaking change, the superset isn't actually compatible and you iterate.

The change-management half is what makes it real without an incident. You don't flip both teams at once; you publish the canonical schema, migrate one consumer at a time behind the `$ref`, verify with contract tests that each still works, and only retire the duplicate definitions after all consumers reference the shared one. Governance prevents recurrence: a Spectral rule (or a registry check) that flags a locally-defined `User`-like schema when a canonical one exists, plus a shared-module ownership model so the common schema has a clear owner and a breaking-change gate of its own. The deeper lesson is that schema duplication is usually a *Conway's-law* artifact — two teams modeling the same domain concept independently — so the durable fix is organizational (a shared data-contract module with clear ownership) as much as technical, and the mechanical superset-plus-oasdiff procedure is what lets you converge safely rather than declaring a flag day that breaks half your consumers.

#### Q68. [Coding] Write an `oasdiff`-driven CI step that posts a human-readable changelog of API changes onto the pull request.

Beyond gating breaking changes, mature teams *surface* every API change on the PR so reviewers see the contract delta in plain language without diffing raw YAML. `oasdiff` can emit a structured changelog (markdown) classifying changes by severity, which you post as a PR comment — turning the contract into a reviewable artifact at every change.

```bash
#!/usr/bin/env bash
set -euo pipefail
BASE="dist/openapi.base.yaml"     # spec from the target branch (e.g. main)
HEAD="dist/openapi.bundled.yaml"  # spec from this PR

# 1. Human-readable changelog (markdown), all severities
oasdiff changelog "$BASE" "$HEAD" --format markdown > changelog.md

# 2. Machine-readable breaking summary for the gate
if oasdiff breaking "$BASE" "$HEAD" --format json > breaking.json; then
  STATUS="✅ No breaking changes"
else
  STATUS="⛔ Breaking changes detected — requires major version bump + approval"
fi

# 3. Post (or update) a single sticky comment on the PR
{
  echo "## OpenAPI contract changes"
  echo
  echo "$STATUS"
  echo
  cat changelog.md
} > pr-comment.md

gh pr comment "${PR_NUMBER}" --body-file pr-comment.md --edit-last || \
gh pr comment "${PR_NUMBER}" --body-file pr-comment.md
```

The flow separates the two concerns that `oasdiff` serves. `oasdiff changelog ... --format markdown` produces the *informational* delta — every added endpoint, every new optional field, every removal — categorized so a reviewer instantly understands "this PR adds two endpoints and deprecates one" without parsing a YAML diff. Separately, `oasdiff breaking ... --format json` is the *gate*, whose exit code drives whether the PR is allowed to merge without a major-bump approval. Posting both as a single **sticky** comment (`gh pr comment --edit-last` updates the same comment on each push rather than spamming new ones) keeps the PR readable as the branch evolves.

The value is making contract change a first-class, visible part of code review rather than something reviewers reconstruct from implementation diffs. A reviewer who sees "⛔ removed field `User.legacyId`" in plain English will catch an accidental breaking change that they'd easily miss scanning a 400-line YAML diff. This is the same philosophy as the snapshot test — convert silent contract changes into reviewed, human-legible diffs — but applied at the PR-conversation layer where the human decision actually happens. Wiring `oasdiff` to *both* enforce (the gate) and *inform* (the changelog comment) is what makes a breaking-change policy feel like a helpful teammate instead of an opaque CI failure, which is the difference between a governance program teams embrace and one they route around.

### 🔴 Expert — extended

#### Q55. [Coding] Implement a custom Spectral function (with options) that enforces semantic-versioning rules on `info.version` and operation deprecation.

Off-the-shelf rules cover casing and presence; org-specific lifecycle policy needs custom functions with configurable options. The exercise: enforce that `info.version` is valid semver and that any `deprecated: true` operation also carries a `Sunset` date extension — encoding a deprecation policy as executable governance.

```javascript
// functions/semverAndSunset.js
export default function semverAndSunset(targetVal, opts, context) {
  const results = [];
  const semver = /^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/;

  // context.document.data is the whole resolved spec
  const doc = context.document.data;

  // Rule A: info.version must be semver
  if (doc.info && !semver.test(String(doc.info.version))) {
    results.push({
      message: `info.version '${doc.info.version}' is not valid semver (x.y.z)`,
      path: ['info', 'version'],
    });
  }

  // Rule B: deprecated operations must declare x-sunset (and it must be future)
  const minDays = (opts && opts.minNoticeDays) || 90;
  for (const [p, item] of Object.entries(doc.paths || {})) {
    for (const m of ['get','post','put','patch','delete']) {
      const op = item[m];
      if (op && op.deprecated === true) {
        const sunset = op['x-sunset'];
        if (!sunset) {
          results.push({ message: `Deprecated ${m.toUpperCase()} ${p} must set x-sunset`,
                         path: ['paths', p, m] });
        } else {
          const days = (new Date(sunset) - Date.now()) / 86400000;
          if (days < minDays) {
            results.push({
              message: `x-sunset for ${m.toUpperCase()} ${p} must be >= ${minDays} days out`,
              path: ['paths', p, m, 'x-sunset'] });
          }
        }
      }
    }
  }
  return results;
}
```

```yaml
# .spectral.yaml
functions: [semverAndSunset]
rules:
  versioning-and-deprecation-policy:
    description: Enforce semver + deprecation sunset policy
    severity: error
    given: $                          # whole document
    then:
      function: semverAndSunset
      functionOptions: { minNoticeDays: 90 }   # configurable notice window
```

The technique on display is a function that (a) reads the whole document via `context.document.data` rather than a single matched node, letting it cross-reference `info.version` and every operation in one pass, and (b) accepts `functionOptions` (`minNoticeDays`) so the *policy parameters* live in the ruleset, not hard-coded in JS — the same function enforces a 90-day window for one org and 30 for another. Returning an array of `{ message, path }` objects gives Spectral precise locations to report, so the failure points at the exact operation.

This is how a deprecation policy stops being a wiki page and becomes a merge gate: you can't mark an operation `deprecated` without committing to a concrete, sufficiently-distant `Sunset` date, which the pipeline verifies. Pairing the lint with the runtime side — emitting RFC 8594 `Deprecation` and `Sunset` HTTP headers from the deprecated handlers — closes the loop between what the contract promises and what clients observe. The broader expert point is that Spectral's custom-function escape hatch lets you encode essentially any policy your org can express as code, which is what turns "API governance" from aspiration into something CI enforces uniformly across hundreds of specs.

#### Q56. [Coding] Model long-running async operations (job submit + poll + callback) in OpenAPI, including the polling and webhook paths.

Many real APIs can't respond synchronously (report generation, video transcode, bulk import), and the contract must describe the *whole* async protocol: submit returns 202 with a job handle, the client polls a status resource, and optionally a webhook fires on completion. Expressing this fully is an advanced design exercise because it spans `202`, `Location`/`Retry-After` headers, a status sub-resource, and a callback.

```yaml
paths:
  /imports:
    post:
      operationId: submitImport
      summary: Submit a bulk import (async)
      requestBody:
        content: { application/json: { schema: { $ref: '#/components/schemas/ImportRequest' } } }
      callbacks:
        onComplete:                                   # webhook we POST on finish
          '{$request.body#/callbackUrl}':
            post:
              requestBody:
                content: { application/json:
                  { schema: { $ref: '#/components/schemas/JobStatus' } } }
              responses: { '200': { description: ack } }
      responses:
        '202':
          description: Accepted; poll the status URL
          headers:
            Location:    { schema: { type: string, format: uri },
                           description: URL of the job status resource }
            Retry-After: { schema: { type: integer }, description: seconds to wait }
          content:
            application/json:
              schema: { $ref: '#/components/schemas/JobStatus' }
  /imports/{jobId}:
    get:
      operationId: getImportStatus
      parameters: [ { name: jobId, in: path, required: true, schema: { type: string } } ]
      responses:
        '200':
          description: Current job status
          content: { application/json: { schema: { $ref: '#/components/schemas/JobStatus' } } }
components:
  schemas:
    JobStatus:
      type: object
      required: [jobId, state]
      properties:
        jobId:  { type: string }
        state:  { type: string, enum: [queued, running, succeeded, failed] }
        progress: { type: integer, minimum: 0, maximum: 100 }
        resultUrl: { type: [string, "null"], format: uri }   # set when succeeded
        error:     { type: [string, "null"] }
```

The protocol has three documented surfaces. The **submit** returns `202 Accepted` (not `200`/`201`) carrying a `Location` header pointing at the status resource and a `Retry-After` hint so clients poll politely; its body is the initial `JobStatus`. The **poll** endpoint (`GET /imports/{jobId}`) returns the evolving `JobStatus`, whose `state` enum drives the client's loop and whose `resultUrl` becomes non-null on success. The **callback** block documents the webhook the API will POST to the caller-supplied `callbackUrl` when the job finishes, using the runtime expression `{$request.body#/callbackUrl}` — so the contract captures the push path too, not just the pull path.

The design judgment is offering *both* polling and webhooks, because consumers differ: a browser SPA polls, a server-to-server integration prefers the webhook to avoid busy-waiting. Modeling `state` as an explicit enum (rather than a free string) lets generated clients switch on it exhaustively, and making `resultUrl`/`error` nullable encodes that they're only populated in terminal states. The honest caveat, as with all callbacks, is that few generators *implement* the webhook side, so the callback block is primarily documentation and a contract-testing target; in 3.1 you might also surface the same outbound shape under top-level `webhooks`. The reason to express all of this rather than just documenting `202` is that async semantics are exactly where undocumented APIs cause the worst integration pain — clients that don't know to poll, or that hammer the status endpoint because no `Retry-After` was advertised.

#### Q57. [Behavioral] Tell me about a time you had to ship a breaking API change that affected external consumers. How did you handle it? (STAR)

**Situation.** At a payments platform, our public `/v2/charges` API returned a `status` field as a free-form string, and several enterprise integrators had built logic that string-matched on values like `"succeeded"`. We discovered the underlying processor was about to introduce intermediate states (`"authorized"`, `"capturing"`) that our API would surface, and a few of those clients had exhaustive switch statements that would throw on any unrecognized value. Emitting the new states was, by our own `oasdiff` gate, a **breaking change for requests-as-consumed**: clients would receive enum values they couldn't handle. We couldn't simply suppress the new states because they were financially meaningful (an "authorized but not captured" charge is not "succeeded").

**Task.** As the API owner I had to roll out the richer status model without breaking the integrators who'd be most damaged by it — and do it under a hard deadline tied to the processor migration, with a public, contract-bound API where I couldn't just coordinate a synchronized client deploy the way I could internally.

**Action.** I refused the false binary of "break them now" vs "never improve the API." First, I quantified the blast radius: I pulled access logs and the published consumer contracts (we had Pact files from our top integrators) to find exactly which clients parsed `status` strictly — it was 6 of ~200. Second, I designed for additive rollout: the new enum values were gated behind an opt-in `Accept` header / `version` parameter, so existing clients kept seeing the old collapsed values (`authorized`/`capturing` mapped to `pending` for them) while opted-in clients got the granular set. I documented both in the spec as distinct media-type variants and added a Spectral rule asserting any new enum value carried a `description`. Third, I drove the comms: a deprecation notice with a concrete `Sunset` date 6 months out, `Deprecation`/`Sunset` headers (RFC 8594) on the legacy behavior, a migration guide, and direct outreach to the 6 strict integrators with a sandbox emitting the new values so they could test before the cutover. I made the breaking-change gate in CI require an explicit, reviewed "major-bump" label so the change couldn't merge silently.

**Result.** All 6 at-risk integrators migrated before the sunset; we had zero production incidents from the new states, and the granular statuses unblocked a feature (auth-then-capture flows) that two large merchants had been asking for. The reusable win was process, not just this change: the opt-in-header pattern plus the mandatory major-bump label and the Pact-derived blast-radius analysis became our standard playbook for public breaking changes. The lesson I carry is that a breaking change to a public API is a *coordination* problem more than a technical one — the spec, the version gate, and the deprecation headers are the mechanics, but the real work is making the change opt-in, measuring exactly who's affected, and giving them a tested migration path with enough runway. I'd rather pay the cost of dual-serving two enum sets for six months than break a partner's payment reconciliation, even once.

#### Q58. [Theory] How do you design a deprecation and lifecycle strategy for an API surface, expressed in the spec and enforced operationally?

A lifecycle strategy answers "how does a field, operation, or whole version come into the world, get marked for removal, and actually disappear without breaking anyone" — and the expert move is to make every stage *expressible in the contract and observable at runtime*, not just a tribal convention. The spec gives you `deprecated: true` on operations and parameters (and `deprecated: true` on schema properties in 3.1), but those flags are inert unless wired into governance and HTTP behavior.

```yaml
paths:
  /v2/legacy-search:
    get:
      operationId: legacySearch
      deprecated: true                      # renders struck-through in Swagger UI
      summary: "[DEPRECATED] Use /v2/search instead"
      description: >
        Deprecated 2026-01-01, removed after 2026-09-30. Migrate to /v2/search,
        which supports cursor pagination. See migration guide: https://...
      x-sunset: "2026-09-30"                # machine-readable removal date
      x-replaced-by: "/v2/search"
      responses:
        '200': { description: OK, content: { application/json:
                 { schema: { $ref: '#/components/schemas/SearchResult' } } } }
```

The four-stage model I drive is: **active → deprecated → sunset → removed**, with explicit dates and a minimum notice window (e.g., 6 months for public, shorter for internal). The contract carries the intent — `deprecated: true` plus `x-sunset`/`x-replaced-by` extensions and a `description` that links the migration guide — and a **Spectral rule** (like Q55's) enforces that nothing is marked deprecated without a future sunset date and a replacement pointer, so the policy is mechanically guaranteed. At runtime the deprecated handlers emit RFC 8594 `Deprecation: true` and `Sunset: <date>` headers, so well-behaved clients and monitoring see the lifecycle without reading docs.

The operational half is what makes removal *safe* rather than scary. You instrument the deprecated path — log/metric every call with the calling client identity — so before the sunset date you know precisely who's still using it and can reach them; you never remove based on a calendar alone but on "calendar reached *and* traffic from real consumers is zero (or only known stragglers you've contacted)." Tie it to your breaking-change gate: removal is a breaking change, so it requires the explicit major-bump override and a check that the sunset date has passed. The anti-patterns this avoids are the two failure modes of deprecation — removing something people still depend on (because you tracked dates but not traffic), and never removing anything (because there was no forcing function), leaving the surface to accrete dead, confusing endpoints forever. A good lifecycle strategy makes both the promise and the proof visible: the contract says when, the headers announce it, the metrics confirm safety, and CI refuses to let the removal happen sloppily.

#### Q59. [Coding] Detect and prevent secret/PII leakage in examples and descriptions across a large spec corpus, in CI.

A real and embarrassing failure mode is real tokens, customer emails, or internal hostnames ending up in `example` values or descriptions, then getting published to a docs site or pushed to an SDK repo. At corpus scale you can't eyeball this, so you encode detection as governance — partly Spectral pattern rules, partly a secret-scanner over the rendered docs.

```yaml
# .spectral.yaml — flag obvious leakage patterns in examples/descriptions
extends: ["spectral:oas"]
rules:
  no-secrets-in-examples:
    description: Examples must not contain tokens, keys, or live credentials
    severity: error
    given: $..[?(@property === 'example' || @property === 'value')]
    then:
      function: pattern
      functionOptions:
        notMatch: "(?i)(bearer\\s+[a-z0-9._-]{20,}|sk_live_[0-9a-z]+|AKIA[0-9A-Z]{16}|-----BEGIN)"
  no-internal-hosts:
    description: Descriptions/servers must not leak internal hostnames
    severity: error
    given: $..[?(@property === 'description' || @property === 'url')]
    then:
      function: pattern
      functionOptions:
        notMatch: "(?i)(\\.internal\\b|\\.corp\\b|10\\.\\d+\\.\\d+\\.\\d+|localhost:\\d+)"
```

```bash
# Defense in depth: scan the BUNDLED spec with a dedicated secret scanner too,
# because regex rules miss novel formats. Run in CI on the rendered artifact.
npx @redocly/cli@latest bundle openapi/openapi.yaml -o dist/openapi.bundled.yaml
gitleaks detect --no-git --source dist/openapi.bundled.yaml --redact || {
  echo "Potential secret detected in spec corpus"; exit 1; }
```

The Spectral half targets the JSON nodes most likely to carry leakage — `example`/`value` (example payloads) and `description`/`url` (prose and server URLs) — and applies `pattern` with `notMatch` against signatures of common secrets (Stripe `sk_live_`, AWS `AKIA…`, PEM blocks, long bearer tokens) and internal hostnames (`.internal`, RFC 1918 IPs, `localhost:port`). Because regexes only catch known shapes, the second layer runs a purpose-built scanner (`gitleaks`/`trufflehog`) over the *bundled* artifact — the bundle, because a secret could hide in an external `$ref`'d file that the root never shows. Running on the bundle is the same "test what consumers consume" principle from the bundling questions.

The design judgment is *defense in depth plus shift-left*. Pattern rules give fast, specific, actionable feedback at lint time ("remove the `sk_live_` token in this example"), while the secret scanner provides breadth against formats you didn't anticipate; neither alone is sufficient. Crucially this runs **before publish** — on the PR, on the bundle, before the docs site or SDK repo is updated — because once a real spec with a live key is pushed to a public docs CDN, rotation, not redaction, is your only recourse. The broader expert framing: a spec is increasingly a *published artifact* (docs sites, SDK repos, LLM tool manifests), so it deserves the same secret-hygiene CI gates you'd put on application code, and the cheap, generic mitigation — fake-but-realistic example values generated by `x-faker` or a fixtures library — removes the temptation to paste real data in the first place.

#### Q60. [Theory] Critique the limits of OpenAPI as a contract: what can it NOT express, and how do you compensate?

The mark of expertise is knowing where your primary tool stops, and OpenAPI — for all its strengths at describing HTTP shape — has hard expressive limits that, if unacknowledged, produce a false sense of completeness. The honest critique is that OpenAPI describes the **static shape of individual request/response messages well, and almost nothing about behavior, semantics, or cross-request invariants**.

```
WHAT OPENAPI EXPRESSES WELL        WHAT IT CANNOT EXPRESS (compensate elsewhere)
──────────────────────────────    ─────────────────────────────────────────────
endpoints, methods, params         business rules / cross-field invariants
request/response shape & types     statefulness & operation ordering (saga/workflow)
status codes, media types          idempotency / retry / concurrency semantics
auth SCHEMES (not enforcement)      rate limits & quotas (only via x- hints)
shape-level validation             actual authorization logic (who can do what)
examples                           latency/SLA, consistency, side-effects
discriminated unions               eventual consistency / read-after-write timing
```

Concretely: OpenAPI can't say "you must call `POST /orders` before `POST /orders/{id}/pay`" (operation ordering / state machine), can't express "this `PUT` is idempotent but this `POST` is not," can't encode "deleting a user cascades to their sessions," can't state rate limits beyond advisory `x-` extensions, and famously only *documents* security schemes without enforcing authorization. Conditional `if/then/else` (3.1) reaches a little into cross-field rules but generators ignore it, and even then it can't express temporal or stateful constraints. These gaps are why two APIs with identical OpenAPI documents can behave completely differently.

The way you compensate is a **layered contract**, and naming the layers is the senior answer. Behavioral and ordering semantics go into operation `description`s and, for genuine workflows, a separate state-machine doc or an AsyncAPI/workflow description; idempotency and concurrency are documented in prose plus conventions (`Idempotency-Key` header, ETags/`If-Match` for optimistic concurrency, both *describable* as parameters but whose *semantics* live in docs); business invariants are enforced server-side and surfaced as documented 409/422 `Problem` responses; authorization is enforced in the gateway/filter chain with the spec's `security` block serving only as the *test oracle* for an authz contract test; rate limits live in gateway config and are *hinted* via `x-rate-limit` plus `429` + `Retry-After`. Consumer-driven contracts (Pact) capture behavioral expectations the spec can't. The discipline is to treat OpenAPI as the **shape layer of a multi-layer contract**, be explicit about what it doesn't cover, and put each missing concern in the right complementary mechanism — rather than pretending a green `redocly lint` means the contract is complete. Acknowledging the limits is what prevents the most expensive class of integration bug: the one where both sides conform to the spec and the system still doesn't work.

#### Q69. [Theory] How would you architect a single OpenAPI source of truth that produces docs, SDKs in 6 languages, a mock server, gateway config, and LLM tool definitions — like Stripe/Twilio?

The "spec is the product" model that Stripe and Twilio operate is not a tool but an *architecture*, and designing it tests whether you understand the spec as the hub of a fan-out pipeline where every downstream artifact is generated, never hand-maintained. The keystone decision is that the spec is the single authored input and everything else — docs, every SDK, mocks, gateway rules, agent tools — is a build output, so they can never drift from each other.

```
                       ┌────────────────────────────┐
                       │   AUTHORED SOURCE OF TRUTH  │
                       │  modular OpenAPI 3.1 spec    │
                       │  (governed, reviewed in PRs) │
                       └──────────────┬───────────────┘
                                      │ bundle + validate + lint + breaking-gate
                                      ▼
        ┌───────────┬───────────┬─────┴─────┬───────────┬─────────────┐
        ▼           ▼           ▼           ▼           ▼             ▼
   Docs site    SDKs ×6     Mock server  Gateway     Contract      LLM tool
   (Redoc)    (openapi-     (Prism /     config      tests         manifest
              generator)    Microcks)   (Kong/AWS)  (Schemathesis) (MCP/functions)
        └───────────┴───────────┴───────────┴───────────┴─────────────┘
                 all REGENERATED on merge — none hand-edited
```

The architecture has four load-bearing pieces. First, **modular authoring with governance**: the spec is a tree of files (per bounded context) bundled into one artifact, gated in CI by validation, Spectral lint, and `oasdiff` breaking-change detection — because if the source can drift or break compatibility, every downstream artifact inherits the damage. Second, **a fan-out generation pipeline**: each language SDK is an openapi-generator target with team-specific `typeMappings`/templates, docs are Redoc-rendered, the mock is Prism/Microcks driven by the spec's examples, gateway config is the spec plus `x-amazon-apigateway-*`/Kong extensions, and the LLM tool manifest is derived from `operationId`+`description`+parameter schemas. All run from the *same bundled spec* on every merge. Third, **independent versioning and release of artifacts**: SDKs get semver-tagged releases per language, published automatically, so a contract change produces coordinated SDK releases rather than manual per-language edits. Fourth, **a registry/portal** that publishes the spec with ownership and lifecycle metadata so consumers discover it.

The reason this is the gold standard is *consistency by construction*: because all six SDKs come from one generator pipeline over one contract, they expose the same types, pagination, and error shapes — which is why Stripe's Python and Go SDKs feel like siblings. The hard parts are the ones beginners underestimate: template customization per language (to make generated code idiomatic, not just correct), a strict backward-compatibility policy enforced by the breaking-change gate (so automated SDK releases never break clients), and treating the spec's *prose* as a first-class deliverable (descriptions feed docs *and* LLM tool selection). The anti-pattern that kills this is letting any artifact be hand-edited — the moment someone patches the generated Java SDK directly, the source-of-truth invariant is broken and drift returns. The whole architecture is, fundamentally, the discipline of "author once, generate everything, regenerate on every change, and never edit the outputs."

#### Q70. [Behavioral] You inherit a critical service whose only "documentation" is an outdated, hand-maintained OpenAPI file that everyone distrusts. How do you turn it into a trusted contract? (STAR)

**Situation.** I took over a payments-adjacent service that ~15 internal teams integrated with. Its OpenAPI file was hand-edited, months stale, and known to be wrong — teams had stopped trusting it and instead reverse-engineered behavior from production traffic or pinged the previous owner on Slack. The file described endpoints that no longer existed and omitted fields the service actually returned, so it was actively harmful: new integrators coded against fiction and hit surprises in production.

**Task.** My goal wasn't "update the file" — a one-time fix would rot again. It was to make the contract *trustworthy and self-sustaining*, so teams could rely on it without verifying against production, and so it couldn't silently go stale again. I had to do this on a live, critical service without a freeze and without a big-bang rewrite that risked behavior changes.

**Action.** I attacked trust at its root cause: the spec wasn't *connected to reality*. First, I established ground truth — I added a contract-conformance test (Schemathesis against a staging deploy, plus a `swagger-request-validator` filter logging response deviations) that compared the *actual* service behavior to the hand-written spec. That immediately produced an objective list of every lie in the document: phantom endpoints, missing fields, wrong types, undocumented error shapes. Second, rather than trust my own edits, I reconciled the spec to observed reality field by field, using the conformance report as the checklist, and for each discrepancy decided whether the *spec* was wrong (fix the doc) or the *service* was wrong (file a bug). Third — the durable part — I wired the conformance test into CI so the spec and implementation are now continuously proven consistent: a deploy that diverges from the spec fails the build, which is what converts a distrusted file into a guaranteed contract. I added a Spectral lint and an `oasdiff` breaking-change gate so future changes are governed and visible. Finally, I did the social half: I published the now-verified spec to our portal, demoed the "the CI gate proves this is accurate" guarantee to the integrating teams, and explicitly told them to stop reverse-engineering from prod and to file a bug if they ever caught the spec lying (they couldn't, because CI would have caught it first).

**Result.** Within a quarter the spec went from "everyone's first instinct is to distrust it" to the actual integration reference; the Slack-the-owner traffic dried up, and two new integrations onboarded purely from the spec + mock with no hand-holding. The conformance gate caught two real drift bugs before release that would previously have shipped. The reusable insight I carry: **documentation rots because nothing forces it to stay true — trust comes not from accuracy at a point in time but from a mechanism that continuously proves accuracy.** I didn't ask anyone to trust the file; I made the file impossible to falsify by tying it to a CI gate, and trust followed the guarantee. The behavioral lesson is that fixing distrust is an engineering problem (build the forcing function) plus a communication problem (show people the guarantee), not a willpower problem of "everyone please keep the doc updated" — which never works.

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
