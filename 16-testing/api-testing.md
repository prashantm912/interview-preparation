# API Testing

[← Back to master index](../README.md)

API testing verifies the behavior, contracts, security, and performance of an application's HTTP/RPC surface — independent of any UI — and it is where most senior backend interviews probe a candidate's testing maturity. This guide covers the modern JVM-centric API-testing toolkit as of 2026: REST Assured for expressive HTTP assertions, WireMock for service virtualization, Postman/Newman for collection-driven and CI runs, JSON Schema validation, consumer-driven contract testing (Pact / Spring Cloud Contract), auth flows (OAuth2 / JWT), data-driven and negative/boundary testing, idempotency, and performance testing with k6/Gatling. Answers favor runnable Java over theory for its own sake.

## 🟢 Basic (0–2 yrs)

### Q1. [Theory] What is API testing, and how does it differ from unit and UI testing?

**API testing** exercises an application through its programmatic interface — typically HTTP/REST, GraphQL, or gRPC — sending requests and asserting on the responses (status code, headers, body, timing) without driving a browser or UI.

- **Unit tests** check a single class/method in isolation with collaborators mocked; no real I/O, microseconds to run.
- **API (service/integration) tests** start the application (or a slice of it) and call it over the wire or via an in-process server, exercising routing, serialization, validation, auth, and persistence wiring together.
- **UI/E2E tests** drive the rendered front end (Selenium/Playwright), which is slow and brittle.

API tests sit in the **middle of the test pyramid**: they give high confidence that the contract works end-to-end while remaining far faster and more stable than UI tests. They are language-agnostic about the client, so you can test a service the same way regardless of which front ends consume it.

```
        /\
       /UI \        few  — slow, brittle browser flows
      /------\
     /  API   \     some — fast, stable, high-confidence contract checks
    /----------\
   /   unit     \   many — pinpoint logic, microseconds
  /--------------\
```

### Q2. [Theory] What does a typical HTTP API test assert on?

A thorough API test asserts on the full response envelope, not just the body:

- **Status code** — 200/201/204 for success, 400/401/403/404/409/422 for the various failure modes.
- **Headers** — `Content-Type`, `Location` (after a POST that creates a resource), `Cache-Control`, `ETag`, rate-limit headers, security headers (`Strict-Transport-Security`, `X-Content-Type-Options`).
- **Body** — specific field values, types, presence/absence of fields, array sizes, and conformance to a JSON Schema.
- **Timing/SLA** — response time under a threshold for latency-sensitive endpoints.
- **Side effects** — e.g. a follow-up GET confirms a resource was actually created/updated.

Asserting only on the status code is a common junior mistake: an endpoint can return `200 OK` with a wrong or empty body.

### Q3. [Practical] Write a basic REST Assured test using the given/when/then DSL.

REST Assured provides a fluent **given/when/then** (Gherkin-style) DSL on top of an HTTP client, with built-in JSON/XML parsing.

```java
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

class UserApiTest {

    @BeforeAll
    static void setup() {
        RestAssured.baseURI = "https://api.example.com";
        RestAssured.basePath = "/v1";
    }

    @Test
    void getUserReturnsExpectedFields() {
        given()
            .header("Accept", "application/json")
            .pathParam("id", 42)
        .when()
            .get("/users/{id}")
        .then()
            .statusCode(200)
            .contentType("application/json")
            .body("id", equalTo(42))
            .body("email", endsWith("@example.com"))
            .body("roles", hasItem("USER"))
            .time(lessThan(800L)); // ms
    }
}
```

- **given()** configures the request (headers, params, body, auth).
- **when()** performs the HTTP verb.
- **then()** asserts on the response; matchers come from Hamcrest.

### Q4. [Practical] How do you POST a JSON body and assert on the created resource with REST Assured?

Serialize a POJO (or a string/Map) as the request body, then assert on the `201`, the `Location` header, and the returned representation.

```java
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

record CreateUser(String name, String email) {}

@Test
void createUserReturns201WithLocation() {
    CreateUser payload = new CreateUser("Ada", "ada@example.com");

    given()
        .contentType("application/json")
        .body(payload)               // POJO serialized via Jackson/GSON
    .when()
        .post("/users")
    .then()
        .statusCode(201)
        .header("Location", matchesPattern(".*/users/\\d+"))
        .body("id", notNullValue())
        .body("name", equalTo("Ada"))
        .body("email", equalTo("ada@example.com"));
}
```

REST Assured auto-detects a JSON object mapper (Jackson, Jackson2, or GSON) on the classpath to serialize the body and deserialize the response.

### Q5. [Theory] What is JsonPath, and how is REST Assured's JsonPath used in assertions?

**JsonPath** is a query language for navigating JSON, analogous to XPath for XML. REST Assured uses a Groovy-flavored JsonPath (GPath) inside `.body("...", matcher)`:

- `id` → top-level field.
- `address.city` → nested field via dot notation.
- `users[0].name` → array index.
- `users.size()` → array length.
- `users.findAll { it.age > 18 }.name` → GPath filtering/projection.
- `users.collect { it.id }` → projection of a field across an array.

```java
.body("data.items.size()", equalTo(3))
.body("data.items.name", hasItems("Pen", "Pencil"))
.body("data.items.find { it.sku == 'A1' }.price", equalTo(9.99f))
```

For extracting a value programmatically (not asserting), use `.extract().path("data.items[0].id")`.

### Q6. [Practical] How do you extract a value from one response to use in a subsequent request (request chaining)?

Use `.extract()` to pull data out of the response, then feed it into the next call. This is essential for flows like "create then fetch" or "login then call protected resource".

```java
import static io.restassured.RestAssured.given;

@Test
void createThenFetch() {
    // 1. Create and capture the generated id
    int id = given()
        .contentType("application/json")
        .body("{\"name\":\"Grace\"}")
    .when()
        .post("/users")
    .then()
        .statusCode(201)
        .extract().path("id");          // pull id from response body

    // 2. Use it in the next request
    given()
        .pathParam("id", id)
    .when()
        .get("/users/{id}")
    .then()
        .statusCode(200)
        .body("name", equalTo("Grace"));
}
```

You can also extract the whole `Response` object (`.extract().response()`) and call `.jsonPath()`, `.header(...)`, or `.statusCode()` on it.

### Q7. [Theory] What are the common HTTP status codes an API test should distinguish, and what do they mean?

| Code | Meaning | When asserted in a test |
|------|---------|-------------------------|
| 200 OK | Success with body | GET/PUT/PATCH returning a representation |
| 201 Created | Resource created | POST that creates; check `Location` |
| 202 Accepted | Async accepted | Job queued, not yet done |
| 204 No Content | Success, no body | DELETE / PUT with empty response |
| 400 Bad Request | Malformed/invalid input | Missing field, bad JSON |
| 401 Unauthorized | Missing/invalid credentials | No or expired token |
| 403 Forbidden | Authenticated but not allowed | Wrong role/scope |
| 404 Not Found | Resource doesn't exist | Bad id |
| 409 Conflict | State conflict | Duplicate key, version mismatch |
| 422 Unprocessable Entity | Semantic validation failure | Valid JSON, invalid business rule |
| 429 Too Many Requests | Rate limited | Throttling tests |
| 5xx | Server error | Should rarely be a *valid* test expectation |

A key interview point: **401 vs 403** (authentication vs authorization) and **400 vs 422** (syntactic vs semantic validity).

### Q8. [Theory] What is positive vs negative testing for an API?

- **Positive (happy-path) testing** sends valid input and confirms the expected success response and body.
- **Negative testing** sends invalid, malformed, or unauthorized input and confirms the API fails *gracefully and correctly* — the right 4xx code, a clear error body, and no leakage of stack traces or internal details.

Mature suites are heavily weighted toward negative cases: missing required fields, wrong types, out-of-range values, oversized payloads, invalid auth, malformed JSON, and unsupported content types. Negative testing is where most real bugs (and security issues) hide.

### Q9. [Practical] Write a negative test that asserts on a validation error response.

```java
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@Test
void createUserWithBlankEmailReturns400() {
    given()
        .contentType("application/json")
        .body("{\"name\":\"Ada\",\"email\":\"\"}")
    .when()
        .post("/users")
    .then()
        .statusCode(400)
        .contentType("application/json")
        .body("errors.size()", greaterThan(0))
        .body("errors.field", hasItem("email"))
        .body("errors.find { it.field == 'email' }.message",
              containsStringIgnoringCase("must not be blank"));
}
```

Assert on the **shape and content of the error body**, not just the status — that is what protects clients that parse error responses.

### Q10. [Theory] What is boundary value testing, and how does it apply to APIs?

**Boundary value analysis** tests the edges of valid input ranges, where off-by-one and validation bugs cluster. For an endpoint accepting `age` in `[18, 120]` and `pageSize` in `[1, 100]`, you test:

- Just below the lower bound (17, 0) → expect rejection.
- The lower bound exactly (18, 1) → expect acceptance.
- The upper bound exactly (120, 100) → expect acceptance.
- Just above the upper bound (121, 101) → expect rejection.
- Empty / null / zero-length where applicable.
- Maximum string length, maximum array size, maximum numeric value (Integer overflow), Unicode/emoji in text fields.

```
  invalid | valid ........................ valid | invalid
        17 [18 ............................ 120] 121
```

Boundary tests are excellent candidates for **data-driven/parameterized** execution.

### Q11. [Practical] How do you test request and response headers?

```java
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@Test
void responseHasSecurityAndCacheHeaders() {
    given()
        .header("Authorization", "Bearer " + token)
    .when()
        .get("/users/42")
    .then()
        .statusCode(200)
        .header("Content-Type", containsString("application/json"))
        .header("Cache-Control", "no-store")
        .header("X-Content-Type-Options", "nosniff")
        .header("ETag", notNullValue());
}
```

Sending a request header is just `.header("Name", "value")` in `given()`. Testing headers matters for caching (`ETag`, `Cache-Control`), content negotiation (`Accept`, `Content-Type`), security headers, and rate limiting (`X-RateLimit-Remaining`).

### Q12. [Theory] What is Postman, and what role does Newman play?

**Postman** is a GUI client for building, organizing, and running API requests grouped into **collections**, with environments (variables), pre-request scripts, and `pm.test(...)` assertions written in JavaScript.

**Newman** is Postman's command-line collection runner. It executes an exported collection (`.json`) against an environment file and produces machine-readable reports (JUnit XML, HTML), which is what makes Postman collections usable in CI.

```bash
newman run users-api.postman_collection.json \
  -e staging.postman_environment.json \
  --reporters cli,junit \
  --reporter-junit-export newman-report.xml
```

The typical division of labor: developers/QA author and explore in the Postman GUI; CI runs the same collection via Newman so the assertions are enforced on every build.

### Q13. [Practical] Write a Postman test script that validates status and a JSON field.

Postman test scripts run in a sandbox after the response arrives. As of 2026 the modern API is `pm.response` plus Chai-style `pm.expect`:

```javascript
pm.test("status is 200", function () {
    pm.response.to.have.status(200);
});

pm.test("body has the right user", function () {
    const json = pm.response.json();
    pm.expect(json.id).to.eql(42);
    pm.expect(json.email).to.be.a("string").and.to.include("@");
});

pm.test("responds under 800ms", function () {
    pm.expect(pm.response.responseTime).to.be.below(800);
});

// Save a value for the next request in the collection
pm.collectionVariables.set("userId", pm.response.json().id);
```

### Q14. [Theory] What is JSON Schema validation, and why use it in API tests?

A **JSON Schema** is a declarative description of a JSON document's structure: required fields, types, formats, enums, ranges, and nesting. Validating a response against a schema asserts the *entire contract shape* in one step, rather than dozens of per-field assertions — and it catches unexpected additions/removals when `additionalProperties: false` is set.

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "required": ["id", "email", "roles"],
  "additionalProperties": false,
  "properties": {
    "id":    { "type": "integer", "minimum": 1 },
    "email": { "type": "string", "format": "email" },
    "roles": { "type": "array", "items": { "type": "string" }, "minItems": 1 }
  }
}
```

Schema validation is a lightweight form of contract testing: it pins the response structure so accidental breaking changes fail the build.

### Q15. [Practical] How do you validate a response against a JSON Schema in REST Assured?

REST Assured ships a `json-schema-validator` module backed by the `networknt`/`everit` validators. Put the schema on the classpath and use the `matchesJsonSchemaInClasspath` matcher.

```java
import static io.restassured.RestAssured.given;
import static io.restassured.module.jsonschema.JsonSchemaValidator.matchesJsonSchemaInClasspath;

@Test
void userResponseMatchesSchema() {
    given()
        .pathParam("id", 42)
    .when()
        .get("/users/{id}")
    .then()
        .statusCode(200)
        // schema file lives in src/test/resources/schemas/user-schema.json
        .body(matchesJsonSchemaInClasspath("schemas/user-schema.json"));
}
```

`matchesJsonSchemaInClasspath(...)` resolves the schema from the test classpath; there are also overloads that take an `InputStream`, `File`, or schema `String`. Add the dependency `io.rest-assured:json-schema-validator`.

### Q16. [Theory] How do you test an endpoint that requires authentication with a bearer token?

The standard pattern: obtain a token once (via a login/token endpoint or a test fixture), then attach it as `Authorization: Bearer <token>` on each protected request.

```java
String token = given()
    .contentType("application/json")
    .body("{\"username\":\"qa\",\"password\":\"secret\"}")
.when()
    .post("/auth/login")
.then()
    .statusCode(200)
    .extract().path("accessToken");

given()
    .auth().oauth2(token)          // adds "Authorization: Bearer <token>"
.when()
    .get("/account")
.then()
    .statusCode(200);
```

You should also test the **failure paths**: no token → 401, expired token → 401, valid token but insufficient scope/role → 403.

### Q17. [Practical] How do you run the same test over multiple inputs (data-driven testing) with JUnit 5 and REST Assured?

Use JUnit 5 `@ParameterizedTest` with a source (`@CsvSource`, `@MethodSource`, `@CsvFileSource`) to drive one test body over many cases.

```java
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

class AgeValidationTest {

    @ParameterizedTest(name = "age={0} expects status {1}")
    @CsvSource({
        "17, 400",   // below lower boundary
        "18, 201",   // lower boundary
        "120, 201",  // upper boundary
        "121, 400"   // above upper boundary
    })
    void ageBoundaries(int age, int expectedStatus) {
        given()
            .contentType("application/json")
            .body("{\"name\":\"x\",\"age\":" + age + "}")
        .when()
            .post("/users")
        .then()
            .statusCode(expectedStatus);
    }
}
```

This keeps boundary/equivalence-class coverage compact and readable, and each row reports as a separate test.

## 🟡 Intermediate (3–7 yrs)

### Q18. [Theory] What is service virtualization, and why use WireMock?

**Service virtualization** replaces a real external dependency (a payment gateway, a partner API, a flaky downstream service) with a programmable stand-in that returns controlled responses. **WireMock** is a popular JVM HTTP mock server that lets you stub endpoints, match requests, inject faults/latency, and verify the calls your code made.

Reasons to use it:

- **Determinism** — no dependence on a third party's uptime, data, or rate limits.
- **Edge cases on demand** — simulate 500s, timeouts, malformed bodies, slow responses that are hard to trigger against the real service.
- **Speed & isolation** — tests run offline and in parallel.
- **Cost/safety** — avoid hitting paid or production sandboxes.

```
 [Your service under test] --HTTP--> [ WireMock ]  (stubbed downstream)
                                         ^ records & verifies the requests it received
```

### Q19. [Practical] Stub a downstream service with WireMock and verify the call was made.

```java
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import org.junit.jupiter.api.Test;
import static com.github.tomakehurst.wiremock.client.WireMock.*;

@WireMockTest
class PaymentClientTest {

    @Test
    void chargesViaDownstreamGateway(WireMockRuntimeInfo wm) {
        // 1. Stub the downstream
        stubFor(post(urlEqualTo("/charges"))
            .withHeader("Content-Type", equalTo("application/json"))
            .withRequestBody(matchingJsonPath("$.amount", equalTo("1000")))
            .willReturn(aResponse()
                .withStatus(201)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"ch_123\",\"status\":\"succeeded\"}")));

        // 2. Point the client at WireMock's base URL and exercise it
        PaymentClient client = new PaymentClient(wm.getHttpBaseUrl());
        ChargeResult result = client.charge(1000, "usd");

        // 3. Assert behavior
        assertThat(result.id()).isEqualTo("ch_123");

        // 4. Verify the outbound request
        verify(postRequestedFor(urlEqualTo("/charges"))
            .withRequestBody(matchingJsonPath("$.currency", equalTo("usd"))));
    }
}
```

### Q20. [Practical] How do you simulate latency, timeouts, and faults with WireMock?

WireMock can inject delays and connection-level faults so you can test resilience (retries, circuit breakers, timeout handling).

```java
// Fixed delay
stubFor(get("/slow").willReturn(aResponse()
    .withStatus(200)
    .withFixedDelay(3000)));        // 3s before responding

// Random/chunked-dribble delay
stubFor(get("/dribble").willReturn(aResponse()
    .withStatus(200)
    .withBody("hello")
    .withChunkedDribbleDelay(5, 2000)));

// Connection faults (no clean HTTP response)
stubFor(get("/broken").willReturn(aResponse()
    .withFault(Fault.CONNECTION_RESET_BY_PEER)));

stubFor(get("/garbage").willReturn(aResponse()
    .withFault(Fault.MALFORMED_RESPONSE_CHUNK)));
```

This lets you assert that your client correctly times out, retries with backoff, or opens a circuit breaker — behaviors you cannot reliably trigger against a healthy real service.

### Q21. [Theory] What is stateful behavior in WireMock (scenarios), and when do you need it?

By default a stub returns the same response every time. **Scenarios** give a mock a state machine so it can return different responses on successive calls — essential for testing flows like "first call returns 202 PENDING, subsequent polls return 200 COMPLETED", or "first call fails then a retry succeeds".

```java
stubFor(get("/job/1").inScenario("polling")
    .whenScenarioStateStarted()            // initial state = "Started"
    .willReturn(aResponse().withStatus(202).withBody("{\"status\":\"PENDING\"}"))
    .willSetStateTo("done"));

stubFor(get("/job/1").inScenario("polling")
    .whenScenarioStateIs("done")
    .willReturn(aResponse().withStatus(200).withBody("{\"status\":\"COMPLETED\"}")));
```

### Q22. [Theory] What is consumer-driven contract testing, and what problem does it solve?

In a microservices system, integration-testing every provider against every consumer is slow and flaky, and it couples deployments. **Consumer-driven contract (CDC) testing** decouples them:

1. The **consumer** writes tests against a *mock* of the provider; the framework records the request/response expectations as a **contract** (a pact).
2. The **provider** replays that contract against its real implementation to prove it still satisfies every consumer.

Each side tests in isolation, with no shared running environment, yet you get a guarantee that they remain compatible. A **broker** (Pact Broker / PactFlow) stores contracts and gates deploys via `can-i-deploy`.

```
 Consumer test --writes--> [Contract] --verified by--> Provider test
                              (broker stores & versions it)
```

Contract testing answers "can these two services talk to each other?" without spinning both up together.

### Q23. [Practical] Write a Pact consumer test in Java.

```java
import au.com.dius.pact.consumer.dsl.PactDslWithProvider;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.core.model.RequestResponsePact;
import au.com.dius.pact.core.model.annotations.Pact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static io.pactfoundation.consumer.dsl.LambdaDsl.newJsonBody;

@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "user-service")
class UserClientPactTest {

    @Pact(consumer = "order-service")
    public RequestResponsePact getUser(PactDslWithProvider builder) {
        return builder
            .given("user 42 exists")
            .uponReceiving("a request for user 42")
                .path("/users/42").method("GET")
            .willRespondWith()
                .status(200)
                .headers(java.util.Map.of("Content-Type", "application/json"))
                .body(newJsonBody(o -> {
                    o.numberType("id", 42);
                    o.stringType("email", "ada@example.com");
                }).build())
            .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "getUser")
    void getUserHonoursContract(au.com.dius.pact.consumer.MockServer mock) {
        User u = new UserClient(mock.getUrl()).fetch(42);
        org.assertj.core.api.Assertions.assertThat(u.id()).isEqualTo(42);
    }
}
```

The matcher methods (`numberType`, `stringType`) mean the contract matches on **type, not exact value**, so the provider isn't pinned to the literal sample data.

### Q24. [Theory] How does the provider side verify a Pact contract?

The provider pulls the contract(s) from the broker (or a file) and replays each interaction against the running provider, asserting the responses match. **Provider states** (`@State`) let the provider set up the data each interaction assumes (e.g. "user 42 exists").

```java
@Provider("user-service")
@PactBroker(url = "https://pacts.example.com")
class UserServiceProviderTest {

    @BeforeEach
    void setTarget(PactVerificationContext ctx) {
        ctx.setTarget(new HttpTestTarget("localhost", port));
    }

    @State("user 42 exists")
    void user42Exists() {
        repository.save(new User(42, "ada@example.com"));
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider.class)
    void verifyPacts(PactVerificationContext ctx) {
        ctx.verifyInteraction();
    }
}
```

Verification results are published back to the broker so `can-i-deploy` can gate releases.

### Q25. [Theory] Pact vs Spring Cloud Contract — when would you choose each?

Both implement consumer-driven contracts on the JVM:

- **Pact** is consumer-first and polyglot, with a mature **broker** ecosystem (versioning, `can-i-deploy`, webhooks). Contracts are generated from consumer tests in code. Best when consumers are in mixed languages or you want a strong broker-driven deploy gate.
- **Spring Cloud Contract** is provider-first: you write contracts (Groovy/YAML DSL) in the provider repo; it **generates provider verification tests** and publishes a **stub JAR** that consumers run against. Best in an all-Spring shop where the provider team owns the contract and you want auto-generated stubs from Maven/Gradle.

Rule of thumb: heterogeneous consumers and a deploy gate → Pact; homogeneous Spring estate with provider-owned contracts → Spring Cloud Contract.

### Q26. [Practical] How do you test OAuth2 client-credentials flow against a protected API?

For machine-to-machine APIs, obtain a token from the authorization server's token endpoint, then call the resource server. In tests you typically point the token endpoint at a real test IdP (Keycloak via Testcontainers) or a WireMock stub.

```java
// 1. Get a token (client_credentials grant)
String token = given()
    .auth().preemptive().basic(CLIENT_ID, CLIENT_SECRET)
    .formParam("grant_type", "client_credentials")
    .formParam("scope", "orders:read")
.when()
    .post(TOKEN_URL)
.then()
    .statusCode(200)
    .extract().path("access_token");

// 2. Call the protected resource
given()
    .auth().oauth2(token)
.when()
    .get("/orders")
.then()
    .statusCode(200);

// 3. Negative: missing scope is forbidden
String readOnly = fetchToken("orders:read");
given().auth().oauth2(readOnly)
.when().post("/orders").then().statusCode(403);
```

Spring Security Test users can also use `SecurityMockServerConfigurers.mockJwt()` / `jwt()` request post-processors to inject a JWT with given authorities without a real IdP.

### Q27. [Practical] How do you craft and assert on JWT claims in a test?

To test authorization logic you often need a JWT with specific claims (roles, scopes, expiry). Generate a signed token in-test, or use Spring's `jwt()` post-processor to bypass the IdP.

```java
// Spring MockMvc: inject a JWT with authorities, no real signing/IdP
mockMvc.perform(get("/admin/reports")
        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_admin"))
                   .jwt(j -> j.claim("sub", "user-1")
                              .claim("scope", "admin"))))
       .andExpect(status().isOk());

// Negative: a token without the scope is forbidden
mockMvc.perform(get("/admin/reports")
        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_user"))))
       .andExpect(status().isForbidden());
```

Test the claims that drive decisions: `exp` (expired → 401), `iss`/`aud` (wrong → 401), `scope`/roles (insufficient → 403), and a tampered signature (→ 401).

### Q28. [Theory] What is idempotency in an API, and how do you test it?

An operation is **idempotent** if performing it multiple times has the same effect as performing it once. By HTTP semantics, GET, PUT, and DELETE are idempotent; POST is not. To make POST safe to retry, APIs accept an **`Idempotency-Key`** header: the server stores the result per key and returns the same response on replays.

To test it:

1. Send a POST with an `Idempotency-Key` → expect `201` and capture the created id.
2. Send the **identical** request with the **same** key → expect the **same** response (often `200`/`201`) and **no second resource created**.
3. Send the same key with a **different body** → expect `409 Conflict` (key reused for different request).
4. Verify via a follow-up GET/count that exactly **one** resource exists.

### Q29. [Practical] Write an idempotency test for a payment-creation endpoint.

```java
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@Test
void postIsIdempotentForSameKey() {
    String key = java.util.UUID.randomUUID().toString();
    String body = "{\"amount\":1000,\"currency\":\"usd\"}";

    // First call creates the payment
    String firstId = given()
        .header("Idempotency-Key", key)
        .contentType("application/json").body(body)
    .when().post("/payments")
    .then().statusCode(201)
        .extract().path("id");

    // Replay with same key + same body returns the SAME resource
    given()
        .header("Idempotency-Key", key)
        .contentType("application/json").body(body)
    .when().post("/payments")
    .then().statusCode(anyOf(equalTo(200), equalTo(201)))
        .body("id", equalTo(firstId));

    // Exactly one payment was created
    given().when().get("/payments?key=" + key)
        .then().body("size()", equalTo(1));

    // Same key, different body → conflict
    given()
        .header("Idempotency-Key", key)
        .contentType("application/json").body("{\"amount\":9999,\"currency\":\"usd\"}")
    .when().post("/payments")
    .then().statusCode(409);
}
```

### Q30. [Practical] How do you test pagination, filtering, and sorting endpoints?

Treat the query parameters as the input space and assert on counts, ordering, and link/meta fields.

```java
@Test
void paginationReturnsCorrectPageAndLinks() {
    given()
        .queryParam("page", 1)
        .queryParam("size", 2)
        .queryParam("sort", "name,asc")
    .when()
        .get("/users")
    .then()
        .statusCode(200)
        .body("content.size()", equalTo(2))
        .body("page.number", equalTo(1))
        .body("content.name", equalTo(List.of("Ada", "Grace"))) // sorted asc
        .header("Link", containsString("rel=\"next\""));
}
```

Cover: first page, middle page, last page, page beyond range (empty content, not an error), invalid `size` (0 or huge → 400 or clamped), and stable ordering with a tiebreaker so results are deterministic.

### Q31. [Theory] How do you keep API tests independent and avoid test data pollution?

Shared, mutable test data is the top cause of flaky, order-dependent API suites. Strategies:

- **Create-your-own-data**: each test creates the resources it needs (via the API or a setup hook) and cleans them up, rather than relying on pre-seeded rows.
- **Unique values**: use UUIDs/timestamps for emails, keys, names to avoid collisions when tests run in parallel.
- **Transactional rollback** for in-process integration tests (`@Transactional` on the test) where applicable.
- **Testcontainers** to spin up a fresh DB/broker per run (or per class) so state never leaks between CI runs.
- **Idempotent teardown** in `@AfterEach`/`@AfterAll` that tolerates already-deleted resources.

Independence (the "I" in F.I.R.S.T.) is what lets the suite run in parallel and in any order.

### Q32. [Practical] How do you configure REST Assured against a Spring Boot app on a random port?

For integration tests, start the app with `webEnvironment = RANDOM_PORT` and point REST Assured at it.

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UserApiIT {

    @LocalServerPort int port;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        RestAssured.baseURI = "http://localhost";
    }

    @Test
    void healthEndpointIsUp() {
        given().when().get("/actuator/health")
            .then().statusCode(200).body("status", equalTo("UP"));
    }
}
```

Alternatively, `RestAssuredMockMvc` runs against the `MockMvc`/`WebTestClient` layer in-process — faster, no real socket — but it does not exercise the full servlet/connector stack.

### Q33. [Theory] What is the difference between testing with MockMvc/WebTestClient and a real HTTP client?

- **`MockMvc` / `WebTestClient` (bound)** call the controller layer in-process without a real network socket. They are fast and great for `@WebMvcTest` slices, but bypass the actual servlet container, connectors, real serialization over the wire, and some filters/timeouts.
- **A real HTTP client (REST Assured against a running server, or `WebTestClient.bindToServer()`)** sends real bytes over a socket to a started app. Slower, but exercises the full stack: connector, filters, content negotiation, compression, real status lines and headers.

Use bound MockMvc for fast controller-slice coverage; use a real client for a small number of full integration/smoke tests where wire fidelity matters.

### Q34. [Practical] How do you assert on an error response body's structure (e.g. RFC 9457 Problem Details)?

Modern APIs return machine-readable errors via **RFC 9457 (Problem Details for HTTP APIs)** with `Content-Type: application/problem+json`. Spring 6+ produces these via `ProblemDetail`.

```java
@Test
void notFoundReturnsProblemDetails() {
    given()
        .pathParam("id", 999999)
    .when()
        .get("/users/{id}")
    .then()
        .statusCode(404)
        .contentType("application/problem+json")
        .body("type", notNullValue())
        .body("title", equalTo("Not Found"))
        .body("status", equalTo(404))
        .body("detail", containsStringIgnoringCase("user"))
        .body("instance", startsWith("/users/"));
}
```

Asserting on the problem fields (`type`, `title`, `status`, `detail`, `instance`) ensures clients can reliably parse failures.

## 🟠 Advanced (8–12 yrs)

### Q35. [Theory] How do you structure a test pyramid specifically for an API/microservice?

For a backend service, the pyramid is reshaped around the *kinds of confidence* you need:

```
        /\
       /  \      E2E / smoke         — a few cross-service journeys in a real env
      /----\
     /      \    Contract tests      — pact/SCC: cheap cross-service compatibility
    /--------\
   /          \  Component/API tests — service started, downstreams virtualized (WireMock)
  /------------\
 /              \ Unit tests          — domain logic, mappers, validators (many)
/----------------\
```

The key senior insight: **contract tests replace most cross-service integration tests**, and **component tests (the whole service with virtualized downstreams)** replace most full end-to-end tests. You keep only a handful of true E2E smoke tests because they are slow, environment-dependent, and the hardest to debug. This keeps CI fast and failures localized.

### Q36. [Theory] How do you performance-test an API, and what metrics matter?

Performance testing an API measures behavior under load, distinct from functional correctness. Test types:

- **Load test** — expected peak traffic; verify SLAs hold.
- **Stress test** — beyond capacity, to find the breaking point and failure mode.
- **Soak/endurance** — sustained load for hours to surface memory leaks and resource exhaustion.
- **Spike test** — sudden surge; verify autoscaling/backpressure.

Metrics that matter:

- **Latency percentiles** — p50, **p95, p99** (never just the mean; tail latency is what users feel).
- **Throughput** — requests/sec sustained.
- **Error rate** — % of non-2xx / timeouts under load.
- **Saturation** — CPU, memory, connection-pool, GC pauses on the service.

A common interview point: report **percentiles, not averages**, because averages hide tail latency that dominates user experience.

### Q37. [Practical] Write a k6 script to load-test an API with latency thresholds.

**k6** (JavaScript-scripted, Go-powered) is a popular 2026 load tool with built-in thresholds that make performance budgets a pass/fail CI gate.

```javascript
import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  stages: [
    { duration: '30s', target: 50 },   // ramp up to 50 VUs
    { duration: '1m',  target: 50 },   // hold
    { duration: '30s', target: 0 },    // ramp down
  ],
  thresholds: {
    http_req_duration: ['p(95)<400', 'p(99)<800'], // ms
    http_req_failed:   ['rate<0.01'],               // <1% errors
  },
};

export default function () {
  const res = http.get('https://api.example.com/v1/users/42', {
    headers: { Authorization: `Bearer ${__ENV.TOKEN}` },
  });
  check(res, {
    'status is 200': (r) => r.status === 200,
    'has id':        (r) => r.json('id') === 42,
  });
  sleep(1);
}
```

If a threshold is breached, k6 exits non-zero — failing the CI job. Gatling (Scala/Java DSL) and JMeter are the JVM-native alternatives.

### Q38. [Practical] How do you integrate API tests into a CI pipeline?

The goal is that every push runs functional API tests, contract verification, and (on a schedule or pre-release) performance tests, with results visible.

```yaml
# .github/workflows/api-tests.yml (illustrative)
jobs:
  api-tests:
    runs-on: ubuntu-latest
    services:
      postgres: { image: postgres:16, ports: ['5432:5432'] }
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '21' }
      - name: REST Assured + contract tests
        run: ./mvnw -q verify        # surefire/failsafe -> JUnit XML
      - name: Newman (Postman collection)
        run: |
          npx newman run postman/users.json -e postman/ci.json \
            --reporters cli,junit --reporter-junit-export newman.xml
      - name: Publish results
        if: always()
        uses: dorny/test-reporter@v1
        with: { path: '**/surefire-reports/*.xml,newman.xml', reporter: java-junit }
```

Best practices: pin tool/image versions, run downstreams via Testcontainers or service containers, fail the build on contract or threshold violations, publish JUnit XML so failures are visible, and keep heavy performance/soak tests on a nightly schedule rather than per-commit.

### Q39. [Theory] How is testing a GraphQL API different from REST?

GraphQL exposes a single endpoint (`POST /graphql`) where the **query/mutation in the body** determines the shape of the response, so test design shifts:

- **No per-resource URLs/status codes** — almost everything returns `200`, even for logical errors. You must assert on the **`errors` array** in the body, not the HTTP status.
- **Selection sets** — you test that requesting specific fields returns exactly those fields (over-/under-fetching is the point of GraphQL).
- **Schema is the contract** — validate against the SDL; introspection tests catch breaking schema changes.
- **Resolvers & N+1** — test that batching/dataloaders prevent N+1 queries under load.
- **Partial results** — a query can return `data` *and* `errors` simultaneously (one field failed); tests must handle both.

```java
String query = "{ user(id: 42) { id email } }";
given()
    .contentType("application/json")
    .body("{\"query\":\"" + query.replace("\"","\\\"") + "\"}")
.when().post("/graphql")
.then()
    .statusCode(200)
    .body("data.user.id", equalTo("42"))
    .body("errors", nullValue());
```

### Q40. [Theory] How do you test a gRPC API?

gRPC uses HTTP/2 with Protobuf binary payloads and a generated typed stub, so plain HTTP tools don't apply directly. Approaches:

- **Generated stub in-test** — call the service via the generated blocking/async stub against an in-process server (`InProcessServerBuilder`) or a real channel.
- **Assert on status codes** — gRPC has its own `Status` codes (`OK`, `INVALID_ARGUMENT`, `NOT_FOUND`, `UNAUTHENTICATED`, `DEADLINE_EXCEEDED`), tested via the thrown `StatusRuntimeException`.
- **Streaming** — test client-, server-, and bidirectional-streaming with `StreamObserver`.
- **Tooling** — `grpcurl` for ad-hoc calls, Ghz for load testing, and `grpc-testing` utilities.

```java
ManagedChannel channel = InProcessChannelBuilder.forName("test").directExecutor().build();
UserServiceGrpc.UserServiceBlockingStub stub = UserServiceGrpc.newBlockingStub(channel);

// happy path
UserReply reply = stub.getUser(UserRequest.newBuilder().setId(42).build());
assertThat(reply.getEmail()).isEqualTo("ada@example.com");

// error path: missing id -> INVALID_ARGUMENT
var ex = assertThrows(StatusRuntimeException.class,
    () -> stub.getUser(UserRequest.newBuilder().build()));
assertThat(ex.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
```

### Q41. [Practical] How do you reset and verify WireMock state between tests in a large suite?

State leaking between tests (left-over stubs, request journal) causes order-dependent flakiness. Reset between tests, and prefer per-test stubs.

```java
@AfterEach
void resetWireMock() {
    WireMock.reset();                 // clear stubs + request journal
}

// Verify exact call counts, and assert no unexpected calls were made
verify(exactly(1), postRequestedFor(urlEqualTo("/charges")));
verify(exactly(0), getRequestedFor(urlEqualTo("/refunds")));

// Fail the test if any request did not match a stub
List<LoggedRequest> unmatched = findAllUnmatchedRequests();
assertThat(unmatched).isEmpty();
```

`findAllUnmatchedRequests()` is a powerful guard: it surfaces requests your code made that you didn't anticipate (a sign of a real bug or a missing stub).

### Q42. [Practical] How do you parameterize API tests from an external data file (CSV/JSON)?

For large input matrices maintained by QA, drive tests from files with `@CsvFileSource` or a `@MethodSource` reading JSON.

```java
@ParameterizedTest
@CsvFileSource(resources = "/cases/login-cases.csv", numLinesToSkip = 1)
void loginScenarios(String username, String password, int expectedStatus, String expectedCode) {
    given()
        .contentType("application/json")
        .body(Map.of("username", username, "password", password))
    .when()
        .post("/auth/login")
    .then()
        .statusCode(expectedStatus)
        .body("code", equalTo(expectedCode));
}
```

```csv
# login-cases.csv
username,password,expectedStatus,expectedCode
qa,secret,200,OK
qa,wrong,401,BAD_CREDENTIALS
,secret,400,VALIDATION_ERROR
```

This separates test *data* (owned by QA/analysts) from test *logic* (owned by engineers) and keeps coverage easy to extend.

### Q43. [Behavioral] Tell me about a time you found a critical bug through API testing that other tests missed.

Use STAR and emphasize the **layer** the bug lived at — something unit tests structurally could not catch.

- **Situation/Task** — "Our checkout service had 90% unit coverage and green builds, but a partner reported duplicate charges intermittently."
- **Action** — "I wrote a component test that started the service with WireMock virtualizing the payment gateway and injected a `CONNECTION_RESET` after the charge but before our response was committed. The client retried, and because our `Idempotency-Key` was generated per-attempt rather than per-logical-request, the retry created a second charge. Unit tests passed because they never exercised the retry-after-partial-failure path over a real client."
- **Result** — "I added idempotency-key and fault-injection tests to the suite, we moved key generation to the request boundary, and duplicate charges dropped to zero. I also added a contract test so the gateway's retry semantics were pinned."

The point interviewers listen for: you understood *why* the test level mattered, reproduced it deterministically, and left behind a regression guard.

### Q44. [Theory] How do you test rate limiting and throttling on an API?

Rate limiting is stateful and time-sensitive, so tests must control or tolerate timing:

- **Header assertions** — confirm `X-RateLimit-Limit`, `X-RateLimit-Remaining` decrement correctly and `Retry-After` appears on `429`.
- **Burst test** — fire N+1 requests within the window and assert the (N+1)th returns `429`.
- **Window reset** — after the window elapses (use Awaitility or a controllable `Clock`, not `Thread.sleep`), the next request succeeds again.
- **Per-key isolation** — two different API keys/users have independent buckets.
- **Determinism** — inject a fake clock or make the window short/configurable in test profiles, so the test isn't slow or flaky.

```java
for (int i = 0; i < limit; i++) {
    given().auth().oauth2(token).when().get("/data").then().statusCode(200);
}
// the next one is throttled
given().auth().oauth2(token).when().get("/data")
    .then().statusCode(429).header("Retry-After", notNullValue());
```

### Q45. [Practical] How do you test API versioning and backward compatibility?

You must prove that old clients keep working when you ship a new version. Strategies and tests:

- **Run the old contract against the new build** — keep the previous version's Pact/JSON-Schema and verify the new provider still satisfies it.
- **Test both routes** — for URI versioning (`/v1/...`, `/v2/...`) or header/media-type versioning (`Accept: application/vnd.api.v2+json`), assert each version returns its expected shape.
- **Additive-change check** — new optional fields must not break v1 consumers (schema with `additionalProperties` tolerance on the consumer side).
- **Deprecation signals** — assert deprecated endpoints return a `Deprecation`/`Sunset` header.

```java
// v1 still returns the legacy field
given().header("Accept", "application/vnd.api.v1+json")
    .when().get("/users/42")
    .then().statusCode(200).body("fullName", notNullValue());

// v2 splits the field and signals deprecation of v1
given().header("Accept", "application/vnd.api.v2+json")
    .when().get("/users/42")
    .then().statusCode(200)
        .body("firstName", notNullValue())
        .body("lastName", notNullValue());
```

### Q46. [Theory] How do you decide what to mock vs. use real in an API integration test?

The trade-off is fidelity vs. speed/determinism:

- **Use real** for things you *own and control* and whose behavior is integral to the test: your own database (via Testcontainers — Postgres, not H2), your message broker, your service's full HTTP stack.
- **Virtualize (WireMock)** for **third-party / external** dependencies: partner APIs, payment gateways, anything you don't control, anything with cost, rate limits, or non-deterministic data, and anything whose failure modes you need to simulate.
- **Mock (Mockito)** only at the *unit* level for in-process collaborators; avoid mocking what you can cheaply run for real.

A useful heuristic: mock at the **trust/ownership boundary**. Inside the boundary, run it real; outside, virtualize it. Over-mocking your own components produces tests that pass while production breaks.

## 🔴 Expert (15+ yrs)

### Q47. [Theory] How would you design an API testing strategy for a large platform with hundreds of services?

A platform-scale strategy is about *governance and leverage*, not just writing tests:

- **Standardize the pyramid per service**: unit + component (service with virtualized downstreams) + contract tests owned by each team; a thin shared E2E smoke suite owned by a platform team.
- **Contract testing as the integration backbone**: a central **Pact Broker / PactFlow** with `can-i-deploy` gates, so teams deploy independently without a shared staging bottleneck.
- **Shared schema registry** (OpenAPI / Protobuf / GraphQL SDL) with automated **breaking-change detection** in CI (e.g. `oasdiff`, Buf for protobuf).
- **Golden test utilities / a paved road**: shared libraries for auth-token minting, WireMock harnesses, and Testcontainers modules so every team tests consistently.
- **Observability-driven testing in prod**: synthetic canaries and contract-derived monitors continuously exercising critical journeys.
- **Performance budgets as code** wired into pipelines per service, with platform-wide SLOs.

The senior signal: you reduce the **N×M integration problem** to N contract verifications, and you make the *right thing the easy thing* via shared tooling and CI gates.

### Q48. [Theory] How do you approach security testing of APIs as part of the testing strategy?

Functional API testing should be complemented by security testing aligned to the **OWASP API Security Top 10 (2023)**, which is still the reference in 2026:

- **Broken Object-Level Authorization (BOLA/IDOR)** — the #1 API risk: test that user A cannot read/modify user B's resource by changing the id. This is *authorization* testing, easy to automate per endpoint.
- **Broken authentication** — expired/forged/`alg=none` JWTs, missing token, token from another tenant.
- **Broken function-level authorization** — a regular user calling admin endpoints.
- **Excessive data exposure / mass assignment** — response leaks fields it shouldn't; client can set protected fields (e.g. `isAdmin`) via the request body.
- **Injection / unsafe input** — SQL/NoSQL/command injection through API params.
- **Resource exhaustion** — missing pagination/rate limits.

Integrate **DAST** (OWASP ZAP), dependency scanning (SCA), and SAST into CI, but author **explicit authorization tests** for BOLA in your functional suite because scanners miss business-logic authz.

### Q49. [Behavioral] Describe a time you had to convince a team to change their API testing approach.

Frame it around **measured pain → proposal → adoption → outcome**, showing influence without authority.

- **Situation** — "Three teams shared a brittle end-to-end suite in a common staging environment; it was ~40% flaky and gated all releases, so people learned to ignore red builds."
- **Action** — "I quantified the cost (hours/week of investigation, releases delayed) and ran a pilot: I replaced the two flakiest cross-service E2E tests with Pact contract tests plus per-service component tests using WireMock. I paired with each team to migrate one flow, and set up a Pact Broker with `can-i-deploy`."
- **Result** — "Flakiness on those flows went to near zero, CI dropped from ~50 minutes to under 15, and teams could deploy independently. I documented the pattern and made the WireMock/Pact harness a shared library so adoption spread without me pushing it."

The interviewer is listening for data-driven persuasion, a small reversible pilot, and leaving behind reusable tooling — not just being technically right.

### Q50. [Theory] How do you test asynchronous and event-driven API flows (webhooks, async jobs, messaging)?

Async flows break the synchronous request/response assumption, so tests must bridge HTTP and the eventual side effect:

- **Async job (202 + poll)** — POST returns `202 Accepted` with a status URL; poll it with **Awaitility** (`await().atMost(...).until(...)`), never `Thread.sleep`, until it reaches a terminal state.
- **Webhook/callback** — stand up a **WireMock** (or local) endpoint to *receive* the provider's callback, then `verify(...)` it arrived with the expected payload; or use a callback-capture tool. WireMock can both send and record callbacks.
- **Message-driven** — produce a command, then consume from the output topic with a real broker (Testcontainers Kafka) and assert the event was published with the right key/headers/payload.
- **Idempotency & ordering** — assert duplicate events don't double-process and out-of-order delivery is handled.

```java
// Trigger async work, then await the eventual state
String jobUrl = given().contentType("application/json").body(payload)
    .when().post("/reports").then().statusCode(202)
    .extract().header("Location");

await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200))
    .untilAsserted(() ->
        given().when().get(jobUrl)
            .then().statusCode(200).body("status", equalTo("COMPLETED")));
```

### Q51. [Practical] How do you generate API tests (or test data) from an OpenAPI specification?

The OpenAPI spec is a machine-readable contract you can leverage so tests stay aligned with the API definition:

- **Schema-driven validation** — derive JSON Schemas from the spec and assert every response conforms; tools like **atlassian/swagger-request-validator** plug into REST Assured/MockMvc to validate requests *and* responses against the OpenAPI doc automatically.
- **Contract/spec linting** — `spectral` lint and `oasdiff` breaking-change detection in CI.
- **Property/fuzz testing** — **Schemathesis** reads the OpenAPI spec and auto-generates negative/boundary/fuzz cases (e.g. it found servers returning 500 on valid-by-spec input). Great for catching whole classes of input-handling bugs.
- **Stub generation** — generate WireMock/Prism mocks from the spec so consumers can test before the provider exists.

```java
// Validate every interaction against the OpenAPI doc, automatically
OpenApiValidationFilter validation =
    new OpenApiValidationFilter("openapi.yaml");

given().filter(validation)
    .when().get("/users/42")
    .then().statusCode(200);   // also fails if response violates the spec schema
```

The expert insight: treat the spec as the **single source of truth** and *generate* validation, fuzzing, and stubs from it rather than hand-maintaining all three in parallel.

### Q52. [Theory] How do you make a large API test suite fast, parallel, and reliable at scale?

At hundreds-to-thousands of tests, suite health is an engineering problem in itself:

- **Parallelize** — JUnit 5 parallel execution + stateless, self-isolating tests (unique data, no shared mutable fixtures). Parallelism only works if independence is real.
- **Right level for each check** — push assertions down to the cheapest level that gives confidence (unit/component over E2E); the pyramid keeps wall-clock time down.
- **Reuse expensive setup** — share Testcontainers across the suite (singleton container / `@ServiceConnection`), reuse Spring contexts (avoid `@DirtiesContext`), mint auth tokens once.
- **Kill flakiness at the source** — inject `Clock`, await conditions, reset WireMock, deterministic ordering; quarantine-with-ticket rather than blanket retries (retries hide real bugs).
- **Tier the pipeline** — fast unit+component on every commit; contract verification on PR; heavy E2E/performance/soak nightly.
- **Observe the suite** — track flaky-test rate, p95 suite duration, and per-test timing as first-class metrics; treat a flaky test as a defect with an owner.

The expert framing: **a slow or flaky suite is a product reliability problem** — teams stop trusting red, so investment in suite speed and determinism directly protects delivery.

## 🧩 Extended Questions — Set 1: Deeper theory & internals

### 🟢 — extended

#### Q53. [Theory] What actually happens inside REST Assured's `given()` — what object is being built, and when is the request sent?

`given()` is a static factory on `io.restassured.RestAssured` that returns a fresh `RequestSpecification` (an instance of `RequestSpecificationImpl`). It is **not** the HTTP call — it is a *mutable builder* accumulating request state.

- Every `.header(...)`, `.body(...)`, `.queryParam(...)`, `.auth()` call mutates that `RequestSpecificationImpl` and returns `this` (fluent style).
- `.when()` is a no-op readability separator that returns the same spec (it exists purely so the DSL reads like Gherkin).
- The **HTTP request is actually fired** when you call the verb method — `.get(...)`, `.post(...)`, etc. Under the hood REST Assured delegates to an HTTP client (historically Apache HttpClient 4; REST Assured 5.x can target the JDK `HttpClient`), builds the URI from `baseURI`/`basePath`/`port`/path params, serializes the body via the detected `ObjectMapper`, and executes.
- The verb returns a `Response`, and `.then()` wraps it in a `ValidatableResponse` against which Hamcrest matchers run.

So the mental model is: `given()` → builder, verb → execute, `then()` → assert. Nothing leaves the JVM until the verb method runs.

#### Q54. [Theory] REST Assured uses GPath, not the Jayway JsonPath syntax. Why does this distinction matter in practice?

There are two completely different "JsonPath" dialects, and conflating them causes silent test failures:

- **GPath** (Groovy's object-graph navigation) is what REST Assured's `.body("...", matcher)` uses. Syntax: `store.book[0].title`, `users.findAll { it.age > 18 }`, `users.collect { it.id }`, `users.size()`. It uses **Groovy closures** and Groovy collection methods.
- **Jayway JsonPath** (the `$`-prefixed dialect) is what WireMock's `matchingJsonPath("$.amount", ...)`, Pact, and many other tools use. Syntax: `$.store.book[0].title`, `$.users[?(@.age > 18)]`.

In REST Assured, writing `.body("$.id", equalTo(1))` will not work the way you expect because `$` is not GPath. Inside REST Assured you write `.body("id", ...)`. The confusion is worsened by the fact that REST Assured *also* ships `io.restassured.path.json.JsonPath`, which is GPath-based, **not** Jayway. The interview signal: knowing which dialect a given tool expects, and that `$.` syntax belongs to Jayway/WireMock, not to REST Assured's body matchers.

#### Q55. [Practical] How does REST Assured decide which object mapper to use, and how do you override it per-request?

REST Assured performs **classpath detection** at runtime to choose a serializer/deserializer. The lookup order (roughly): Jackson 2 (`ObjectMapper` from `jackson-databind`), then Jackson 1, then Gson, then JAXB/Jackson for XML. The first one found on the classpath wins for `application/json`. This is why adding `jackson-databind` to a project silently changes serialization behavior.

You can override it globally or per-request:

```java
import io.restassured.mapper.ObjectMapperType;
import io.restassured.config.ObjectMapperConfig;
import static io.restassured.config.RestAssuredConfig.config;

// Per-request: force Gson for this body only
given()
    .config(config().objectMapperConfig(
        new ObjectMapperConfig(ObjectMapperType.GSON)))
    .contentType("application/json")
    .body(new CreateUser("Ada", "ada@example.com"))
.when().post("/users").then().statusCode(201);

// Or supply a fully custom, pre-configured Jackson mapper
com.fasterxml.jackson.databind.ObjectMapper jackson =
    new com.fasterxml.jackson.databind.ObjectMapper()
        .findAndRegisterModules()           // e.g. JavaTimeModule for Instant
        .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

given()
    .config(config().objectMapperConfig(
        new ObjectMapperConfig().jackson2ObjectMapperFactory((type, charset) -> jackson)))
    .body(payload)
.when().post("/events").then().statusCode(201);
```

The common real-world bug this fixes: `java.time` types (`Instant`, `LocalDate`) serialize as epoch arrays unless the `JavaTimeModule` is registered, which REST Assured's auto-created mapper does *not* do by default.

#### Q56. [Theory] What does `equalTo(9.99f)` vs `equalTo(9.99)` reveal about how JSON numbers are parsed in a response?

JSON has a single numeric type, but the parser must map it onto Java types, and the chosen type must match the Hamcrest matcher *exactly* or the assertion fails. In REST Assured's GPath, a JSON number like `9.99` is deserialized to a **`Float`** by default (not `Double`), and integers to `Integer` (or `BigInteger`/`Long` for large values). So:

- `.body("price", equalTo(9.99f))` ✅ matches a `Float`.
- `.body("price", equalTo(9.99))` ❌ — `9.99` is a Java `double`/`Double`, which `equalTo` compares by type and value; `Float(9.99) != Double(9.99)`.

This is a classic gotcha. Robust alternatives that avoid type-pinning:

```java
.body("price", is(closeTo(9.99, 0.001)))          // Hamcrest closeTo, type-tolerant on doubles
.body("price", comparesEqualTo(new java.math.BigDecimal("9.99")))
// or parse explicitly:
float p = given().when().get("/items/1").then().extract().path("price");
```

You can also configure REST Assured to deserialize all JSON numbers as `BigDecimal` via `JsonConfig` for exact financial comparisons:

```java
given().config(config().jsonConfig(
    io.restassured.config.JsonConfig.jsonConfig()
        .numberReturnType(io.restassured.path.json.config.JsonPathConfig.NumberReturnType.BIG_DECIMAL)));
```

#### Q57. [Theory] What is the structural difference between a test that uses `.extract()` and one that uses only `.then()...body(...)`?

`.then()` returns a `ValidatableResponse` and runs assertions *eagerly* as a side effect of each matcher call — the response is consumed for validation, and the chain's purpose is to **fail the test if an expectation is violated**. `.extract()` switches to an `ExtractableResponse`, which **does not assert** — it hands you data (a path value, header, status, cookie, or the raw `Response`) to use programmatically afterward.

The key internal point: you can do both, and order matters — assertions before `.extract()` still run.

```java
int id = given().body(payload).when().post("/users")
    .then()
        .statusCode(201)                 // asserted eagerly
        .body("name", equalTo("Ada"))    // asserted eagerly
    .extract().path("id");               // then pull the value out
```

This means `.extract()` is the bridge from declarative assertion to imperative request-chaining (Q6). A subtle gotcha: the `Response` body is an `InputStream` that can be consumed once; REST Assured buffers it so repeated `.path(...)`/`.jsonPath()` calls work, but holding the raw stream and reading it twice manually will not.

#### Q58. [Practical] How do you log only the request/response when a test fails, rather than always?

Always-on logging (`.log().all()`) floods CI output. REST Assured provides conditional logging via `LogDetail` and the `failure`/`ifValidationFails` filters, which only emit on assertion failure.

```java
import static io.restassured.RestAssured.given;

@Test
void logsOnlyOnFailure() {
    given()
        .log().ifValidationFails()        // request logged only if the test fails
    .when()
        .get("/users/42")
    .then()
        .log().ifValidationFails()        // response logged only if an assertion fails
        .statusCode(200);
}
```

You can set it globally so every test inherits the behavior:

```java
import io.restassured.RestAssured;
import io.restassured.config.LogConfig;
import static io.restassured.config.RestAssuredConfig.config;

@BeforeAll
static void enableFailureLogging() {
    RestAssured.config = config().logConfig(
        LogConfig.logConfig().enableLoggingOfRequestAndResponseIfValidationFails());
}
```

This keeps green builds quiet while giving full request/response forensics exactly when something breaks — the right default for shared pipelines.

### 🟡 — extended

#### Q59. [Theory] How does WireMock decide which stub to serve when multiple stubs match a request?

WireMock maintains an ordered list of stub mappings and, for each incoming request, finds all mappings whose request matchers all pass, then selects a winner by **priority**. The rules:

- Each `StubMapping` has a `priority` integer; **lower number = higher priority** (priority 1 wins over priority 5). Default priority is 5.
- Among matching stubs of equal priority, the **most recently added** stub wins (last-registered-wins / LIFO for equal priority).
- A stub with **more specific matchers does not automatically win** — specificity is not the tiebreaker; priority and insertion order are.

This is why the canonical pattern is a **broad low-priority catch-all plus narrow high-priority overrides**:

```java
// Catch-all default (low priority => high number)
stubFor(any(anyUrl()).atPriority(10)
    .willReturn(aResponse().withStatus(404).withBody("{\"error\":\"not stubbed\"}")));

// Specific override (high priority => low number)
stubFor(get(urlEqualTo("/users/42")).atPriority(1)
    .willReturn(okJson("{\"id\":42}")));
```

Without explicit priorities, two equally-matching stubs would resolve by registration order, which makes tests order-dependent — the kind of internal detail that separates someone who has actually debugged WireMock from someone who has only read the README.

#### Q60. [Practical] How does WireMock's request journal work, and what's the memory/performance implication at scale?

WireMock records **every** request it receives into an in-memory **request journal** (a list of `LoggedRequest`/`ServeEvent` objects), which is what powers `verify(...)`, `findAll(...)`, and `findAllUnmatchedRequests()`. The implications:

- In a long-running suite or load test pointed at WireMock, the journal grows unbounded and can cause memory pressure and slow `verify` scans (linear over the journal).
- You control it with `maxRequestJournalEntries` (cap the size) or `disableRequestJournal()` when you only need stubbing and not verification.

```java
WireMockServer wm = new WireMockServer(WireMockConfiguration.options()
    .dynamicPort()
    .maxRequestJournalEntries(1000));     // bound journal growth

// or, for pure load-test stubbing with no verification:
WireMockConfiguration.options().disableRequestJournal();
```

Also note `WireMock.reset()` clears stubs **and** the journal, whereas `resetRequests()` clears only the journal and `resetMappings()` only the stubs — choosing the right reset is the difference between a fast targeted cleanup and accidentally wiping setup you needed.

#### Q61. [Theory] What is request-matcher ordering and "near misses" in WireMock, and how do they help debugging?

When a request matches **no** stub, WireMock doesn't just fail blankly — it computes **near misses**: it ranks the registered stubs by how *close* they came to matching (a distance score across URL, method, headers, body matchers) and can report the closest one. This is gold for debugging "why didn't my stub match?" failures.

```java
// After a verify failure, ask WireMock what almost matched:
List<NearMiss> nearMisses = WireMock.findNearMissesForAllUnmatchedRequests();
nearMisses.forEach(nm -> System.out.println(nm.getDiff()));   // shows the exact mismatched part
```

The `getDiff()` output highlights, field by field, where the actual request diverged from the stub (e.g. the body matched but a header was `application/json; charset=utf-8` vs the expected `application/json`). The senior insight: most "WireMock isn't working" problems are a slightly-too-strict matcher (an extra header, a charset suffix, a JSON field ordering assumption), and near-miss diffs pinpoint exactly that without trial-and-error.

#### Q62. [Theory] In Pact, what is the difference between matching by example and matching by type, and why does it matter for contract robustness?

A Pact interaction body contains both **example values** (the literal sample) and **matching rules** (how the provider's actual response is compared). The distinction is the crux of contract robustness:

- **Match by example (exact)** — the default if you specify a literal value: the provider must return *that exact value*. This is brittle: it pins the provider to the consumer's sample data, so any data drift breaks verification even though the *shape* is fine.
- **Match by type** — `stringType("email", "ada@example.com")`, `numberType("id", 42)`: the example is only illustrative; the rule says "must be a string"/"must be a number". The provider passes as long as the **type** matches, regardless of value.

Other matchers extend this: `datetime(...)` (format match), `regex(...)` (pattern), `eachLike(...)` (array of N elements all matching a template, decoupling the consumer from array length), and `arrayContaining`. The principle: a contract should encode the **structural expectations** the consumer actually depends on, not the incidental sample values — otherwise contract testing devolves into brittle golden-file testing across service boundaries.

#### Q63. [Practical] How do you test that your client correctly handles HTTP/2 or chunked transfer encoding, and what can WireMock simulate here?

Transport-level behavior (chunking, trailers, connection reuse) is often where subtle client bugs live. WireMock can simulate several wire-level conditions:

- **Chunked dribble** — `withChunkedDribbleDelay(numberOfChunks, totalDurationMs)` streams the body in N chunks over a duration, exercising clients that read incrementally and timeout handling on slow streams.
- **Connection faults** — `Fault.CONNECTION_RESET_BY_PEER`, `Fault.EMPTY_RESPONSE`, `Fault.MALFORMED_RESPONSE_CHUNK`, `Fault.RANDOM_DATA_THEN_CLOSE` — these produce *no valid HTTP response*, so you assert your client throws the right `IOException`/timeout rather than hanging.

```java
stubFor(get("/stream").willReturn(aResponse()
    .withStatus(200)
    .withHeader("Transfer-Encoding", "chunked")
    .withBody("{\"events\":[...]}")
    .withChunkedDribbleDelay(8, 4000)));   // 8 chunks across 4s

// Assert the client enforces a read timeout instead of blocking forever
assertThrows(SocketTimeoutException.class, () -> client.streamEvents());
```

Note WireMock's default Jetty server speaks HTTP/1.1; true HTTP/2 multiplexing testing is limited, so for HTTP/2-specific behavior you typically pair a real server (Testcontainers) with this fault-injection approach for the failure paths.

#### Q64. [Theory] Why is `additionalProperties: false` in a consumer-side JSON Schema a double-edged sword?

`additionalProperties: false` makes a schema **closed**: the response must contain *only* the declared properties, and any extra field fails validation. The tension:

- **As a provider-side / golden-contract check** it is valuable — it catches accidental field removals *and* unexpected leakage of new fields (a security and stability win).
- **As a consumer-side expectation it violates the robustness principle** ("be conservative in what you send, liberal in what you accept"). A well-behaved consumer should tolerate the provider *adding* new optional fields — that is a backward-compatible change. If the consumer's schema is closed, a purely additive provider change breaks the consumer's tests for no real incompatibility.

The nuanced answer: use **closed schemas on the provider's own response tests** (to police its output) and **open schemas (or type-based Pact matchers) on the consumer side** (to validate only the fields the consumer reads). Conflating the two is why naive schema-validation suites flag false breakages on every additive release. This directly parallels Q45's additive-change check.

#### Q65. [Practical] How do you make REST Assured tests deterministic when the response contains timestamps, generated IDs, or ordering?

Non-deterministic fields are the top cause of "passes locally, flakes in CI" for assertion-heavy tests. Strategies, in order of preference:

```java
// 1. Assert on shape/type/format, not exact value
.body("createdAt", matchesPattern("\\d{4}-\\d{2}-\\d{2}T.*Z"))   // ISO-8601, any instant
.body("id", matchesPattern("[0-9a-f-]{36}"))                     // a UUID, any value

// 2. Assert relationships, not literals
.body("updatedAt", greaterThanOrEqualTo(/* captured */ createdAt))

// 3. Normalize ordering before comparing collections
.body("tags", containsInAnyOrder("a", "b", "c"))                 // order-independent

// 4. Inject a fixed Clock on the server side (test profile) so timestamps are reproducible
```

The deeper principle: separate the *deterministic contract* (this field is an ISO-8601 instant; this id is a UUID; these tags are a set) from the *incidental data* (the literal instant/id). Assert the former, never the latter. For collections, decide explicitly whether ordering is part of the contract — if the API doesn't guarantee order, asserting a specific order is a self-inflicted flaky test. This is the same discipline that makes Pact type-matchers robust (Q62).

### 🟠 — extended

#### Q66. [Theory] Explain the F.I.R.S.T. principles and how each one maps to a concrete API-testing technique.

F.I.R.S.T. is the canonical acronym for clean tests; mapping each letter to API testing:

- **F — Fast**: push assertions to the cheapest level (component over E2E), reuse Spring contexts and singleton Testcontainers, use bound `MockMvc` for slices. Slow API suites get skipped or run only nightly, eroding their value.
- **I — Isolated/Independent**: each test creates its own data with unique values (UUID emails), resets WireMock between tests, and assumes nothing about execution order — the precondition for parallelism.
- **R — Repeatable**: deterministic across environments — fixed `Clock`, Awaitility instead of `Thread.sleep`, Testcontainers instead of a shared DB, type/format assertions instead of literal timestamps (Q65).
- **S — Self-validating**: the test asserts pass/fail automatically (status, schema, body matchers) — no human reading logs to judge correctness.
- **T — Timely (or Thorough)**: written alongside the code (TDD/contract-first), and covering negative/boundary/authorization cases, not just the happy path.

The interview-grade point: most API-suite pathologies (flakiness, slowness, order-dependence) are a violation of one specific FIRST letter, and naming which letter is broken is how you diagnose a sick suite.

#### Q67. [Theory] What is the test-data lifecycle problem in API testing, and what are the four canonical strategies with their trade-offs?

The problem: API tests need data to exist (or not exist) in a backing store they don't directly control, and that state must be correct, isolated, and cleaned up. The four canonical strategies:

1. **Pre-seeded/reference data** — a fixed dataset loaded before the suite. *Pro*: simple, fast. *Con*: brittle (tests couple to magic ids), pollutes over time, breaks under parallelism if mutated.
2. **Create-your-own (self-provisioning)** — each test creates exactly what it needs via the API and tears it down. *Pro*: isolated, parallel-safe, realistic. *Con*: slower, requires working create/delete endpoints, teardown must be idempotent.
3. **Transactional rollback** — wrap each in-process test in a transaction rolled back at the end (`@Transactional`). *Pro*: instant cleanup, perfect isolation. *Con*: only works for in-process integration tests against the same datasource; can hide commit-time behavior (constraints, triggers, flush timing) and doesn't work across a real HTTP boundary where the server has its own transactions.
4. **Ephemeral environment per run** — fresh DB/broker via Testcontainers per run/class. *Pro*: total isolation, real engine (Postgres not H2). *Con*: container startup cost, resource heavy.

The senior framing: most robust suites combine **(2) create-your-own over (4) ephemeral containers**, reserving (3) for fast controller-slice tests and avoiding (1) except for truly static reference lookups.

#### Q68. [Practical] How do you share a single expensive resource (Testcontainer, auth token) across an entire test suite efficiently, and what are the pitfalls?

The naïve approach — `@Container` on each class — starts a new container per class, which is slow. The patterns:

```java
// Singleton container: started once, reused by the whole JVM, never stopped explicitly
// (Ryuk/the JVM shutdown reaps it). Do NOT annotate with @Container.
abstract class AbstractIT {
    static final PostgreSQLContainer<?> PG =
        new PostgreSQLContainer<>("postgres:16").withReuse(true);
    static { PG.start(); }                       // static init => once per JVM

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
    }
}
```

Pitfalls:

- **State leakage**: because the container is shared and never reset, tests must self-isolate their data (unique keys, cleanup) — the container being shared makes test independence *more* important, not less.
- **Auth token caching**: mint the token once (static/`@BeforeAll`) but watch expiry — a long parallel suite can outlive a short-lived token; cache with a refresh-on-expiry wrapper, not a single static string.
- **`withReuse(true)`** requires opt-in via `~/.testcontainers.properties` and only helps locally; CI typically gets a clean container anyway.
- **`@DirtiesContext`** silently defeats Spring-context reuse and multiplies suite time — treat its presence as a code smell to justify.

#### Q69. [Theory] How do you correctly test eventual consistency, and why is polling-with-timeout the right primitive rather than a fixed sleep?

In an eventually-consistent flow (async job, CQRS read model, replicated store, event-driven side effect), the side effect appears *after* the synchronous response, at a non-deterministic delay. The wrong primitive is `Thread.sleep(n)`: too short → flaky failures, too long → a slow suite, and it encodes a guess about timing into the test.

The right primitive is **poll-until-condition with a timeout and interval** (Awaitility):

```java
await()
    .atMost(Duration.ofSeconds(10))      // upper bound — fail if it never converges
    .pollInterval(Duration.ofMillis(200))// how often to re-check
    .pollDelay(Duration.ZERO)            // start checking immediately
    .ignoreExceptions()                  // a 404 mid-flight is expected, keep polling
    .untilAsserted(() ->
        given().when().get("/read-model/42")
            .then().statusCode(200).body("status", equalTo("READY")));
```

Why it is correct: it **returns as soon as the condition is true** (fast in the common case), **fails fast with a clear message** if the system never converges (real bug surfaced), and **decouples the test from timing assumptions**. The deeper point: testing eventual consistency means asserting *convergence within a bound*, not asserting an exact moment — the bound is the SLA, and the test encodes that SLA.

#### Q70. [Practical] How would you write a property-based / fuzz API test, and what classes of bug does it catch that example-based tests miss?

Example-based tests check the inputs *you thought of*. Property-based and fuzz testing generate inputs you didn't, asserting **invariants** that should hold for *all* valid inputs. For APIs, Schemathesis (driven by the OpenAPI spec) is the standard 2026 tool; jqwik is the JVM property library.

```java
import net.jqwik.api.*;

class CreateUserProperties {

    @Property(tries = 500)
    void anyValidEmailIsAccepted(@ForAll("validEmails") String email) {
        given().contentType("application/json")
            .body(Map.of("name", "x", "email", email))
        .when().post("/users")
        .then().statusCode(anyOf(is(201), is(409)));   // never 500
    }

    @Provide
    Arbitrary<String> validEmails() {
        return Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(20)
            .map(s -> s + "@example.com");
    }
}
```

The invariant being asserted is "**no valid-by-spec input ever yields a 5xx**" — a property, not an example. Bug classes this catches that examples miss: integer/length overflow on unexpected sizes, Unicode/emoji handling, null vs empty-string edge cases, injection via odd characters, and unhandled exceptions surfacing as 500s. Schemathesis additionally checks **response-conforms-to-schema** and **status-code-is-documented** for every generated case, surfacing spec/implementation drift automatically.

#### Q71. [Theory] What is the difference between a stub, a mock, a spy, a fake, and a dummy in the context of API/service testing?

These five (Gerard Meszaros's "test doubles") are routinely conflated; precise definitions:

- **Dummy** — passed to satisfy a signature but never used (e.g. a `null` or empty config the code path doesn't touch).
- **Stub** — returns canned responses to calls made during the test; **state verification** (you assert on the system's output). WireMock used purely to return `200 {...}` is a stub.
- **Mock** — pre-programmed with expectations about *which calls it should receive*; **behavior verification** (the test fails if the expected interaction didn't happen). WireMock with `verify(postRequestedFor(...))` is acting as a mock.
- **Spy** — a real object that also records how it was called, so you can assert on interactions *after the fact* without pre-setting expectations. WireMock's request journal makes it spy-capable.
- **Fake** — a working but simplified implementation (in-memory repository, embedded broker) — real logic, not canned answers.

The subtle point relevant to WireMock: the *same tool* is a stub or a mock depending on whether you **verify interactions**. "Service virtualization" spans stub + mock + spy. Knowing the taxonomy lets you say precisely what fidelity a given double provides and where it can mislead (e.g. a stub can't catch a wrong call sequence; you need mock-style verification for that).

#### Q72. [Theory] How does contract testing reduce the N×M integration problem, and what does it *not* prove?

In a system of *N* providers and *M* consumers, full pairwise integration testing is O(N×M) combinations, each needing both sides running — combinatorially explosive and operationally a shared-environment bottleneck. Contract testing collapses this:

- Each **consumer** independently records what it needs (a pact) → O(edges), not O(N×M) live combinations.
- Each **provider** verifies all its consumer contracts independently → the integration guarantee becomes *N + M* isolated verifications plus a broker, run without any shared running environment.
- `can-i-deploy` then answers, per service, "is every consumer/provider I interact with compatible with the version I'm about to ship?" — turning integration safety into a deploy-time gate.

What contract testing **does not** prove:

- **Functional correctness** — it pins the *shape and protocol* of the interaction, not that the business logic is right. A provider can satisfy a contract and still compute the wrong answer.
- **End-to-end behavior** across a multi-hop journey (A→B→C semantics, transactions, timing, ordering).
- **Non-functional properties** — latency, throughput, security.
- **Anything no consumer asked for** — fields/endpoints not in any pact are unverified.

Hence the pyramid still keeps a thin layer of true E2E smoke tests; contract testing replaces *most*, not all, integration testing.

### 🔴 — extended

#### Q73. [Theory] At platform scale, contract testing introduces a "pending pacts" and "WIP pacts" problem — what is it and how is it solved?

Naive consumer-driven contracts create a deployment deadlock at scale: a consumer publishes a *new* expectation (a new pact version), and the provider's CI immediately starts failing because the provider hasn't implemented it yet — even though nothing in production is broken. This blocks the provider's unrelated deploys on a consumer's not-yet-released change.

Pact/PactFlow solves this with two mechanisms:

- **Pending pacts** — a pact that has never been successfully verified by the provider is marked *pending*; the provider's verification *runs* it (so the team sees it) but a failure on a pending pact **does not fail the provider's build**. Once the provider successfully verifies it, it graduates to non-pending, and from then on a regression *does* fail the build.
- **WIP (work-in-progress) pacts** — automatically includes new/unverified pacts from feature branches in the provider's verification as informational, so providers get early feedback without being gated.

The combination decouples the *publish* of a new expectation from the *obligation* to satisfy it, letting both teams keep deploying. This — plus **branch/environment-aware `can-i-deploy`** and **pact version selectors** (verify only pacts deployed to prod + main + your own branch) — is what makes contract testing survive at hundreds of services. The senior signal: knowing that naive CDC *deadlocks* at scale and naming the exact mechanisms that break the deadlock.

#### Q74. [Theory] How do you design API tests to detect breaking changes automatically across schema, behavior, and contract dimensions, and where does each method's blind spot lie?

Breaking-change detection is layered because each layer catches what the others miss:

- **Schema/structural diff** (`oasdiff` for OpenAPI, **Buf** for Protobuf, GraphQL Inspector for SDL) — diffs two spec versions and classifies changes as breaking/non-breaking (removed field, narrowed type, new required request field). *Blind spot*: it only sees what's *declared* in the spec; behavioral changes within the same shape are invisible, and a spec that doesn't match the implementation gives false confidence.
- **Contract verification** (Pact replay of prior contracts against the new build) — proves the new provider still satisfies real consumer expectations. *Blind spot*: only covers fields consumers actually asserted on; an unused-but-public field can break unknown clients.
- **Replay/golden tests** (run last release's recorded requests against the new build, diff responses) — catches behavioral drift the schema can't express (a status-code change, a default value change, a sort-order change). *Blind spot*: only as good as the recorded corpus; novel inputs aren't covered.
- **Consumer-side tolerant reads** — consumers that ignore unknown fields and assert only what they use convert many "breaking" additive changes into non-breaking ones.

The expert framing: **no single mechanism is sufficient** — you wire spec-diff (cheap, runs on every PR) + contract verification (cross-service) + a small replay corpus (behavioral) into CI, and you treat the *spec-vs-implementation* gap as its own risk (close it by generating validation from the spec, per Q51).

#### Q75. [Practical] How do you robustly test multi-tenant data isolation (a class of BOLA) at the API layer, and why must this live in the functional suite rather than only in a scanner?

Multi-tenant isolation failures (tenant A reading tenant B's data) are **Broken Object-Level Authorization** — OWASP API #1 — and they are *business-logic* authorization, which DAST scanners structurally cannot infer because they don't know which object belongs to which tenant. So you author explicit cross-tenant tests:

```java
@Test
void tenantCannotAccessAnotherTenantsResource() {
    String tokenA = tokenFor("tenant-A");
    String tokenB = tokenFor("tenant-B");

    // Tenant A creates a resource and captures its id
    String id = given().auth().oauth2(tokenA)
        .contentType("application/json").body("{\"name\":\"secret\"}")
    .when().post("/documents").then().statusCode(201)
        .extract().path("id");

    // Tenant B must NOT be able to read it by guessing/knowing the id
    given().auth().oauth2(tokenB).pathParam("id", id)
    .when().get("/documents/{id}")
    .then().statusCode(anyOf(is(404), is(403)));   // 404 preferred: don't leak existence

    // ...and must not be able to mutate or delete it
    given().auth().oauth2(tokenB).pathParam("id", id)
    .when().delete("/documents/{id}").then().statusCode(anyOf(is(404), is(403)));
}
```

Why functional-suite, not scanner-only: the scanner can fuzz ids but cannot assert "this id belongs to tenant A, so tenant B getting 200 is a *vulnerability*" — that judgement requires domain knowledge of ownership. The pattern is **enumerate every object-returning endpoint and assert cross-subject denial**, ideally generated parametrically from the route table so new endpoints are covered automatically. Note the **404-vs-403** subtlety: returning 404 (not 403) avoids leaking that the resource *exists*, which itself is an information-disclosure consideration.

#### Q76. [Theory] How do you think about flakiness statistically, and what is the right policy toward retries versus quarantine?

Flakiness is a **probabilistic** property: a test with per-run failure probability *p* (independent of code correctness) compounds across a suite — a suite of *N* tests each with even small *p* has overall pass probability ≈ (1−p)^N, so at scale a 0.1% per-test flake rate makes large suites red most of the time. This is why flakiness is a *suite-reliability* problem, not a per-test nuisance.

Policy:

- **Retries are an analgesic, not a cure.** Auto-retrying a flaky test (e.g. 3 attempts) masks both genuine flakes *and* real intermittent bugs (a race condition that fails 1-in-10 is a production bug, and retry-to-green hides it). Blanket retry erodes signal.
- **Quarantine-with-ownership** is the disciplined alternative: a flaky test is removed from the gating set (so it stops blocking deploys) **but tracked as a defect with an owner and a ticket**, and continues running in a non-gating lane to gather data. It must be fixed or deleted within an SLA — not left quarantined forever.
- **Measure it**: track per-test flake rate as a first-class metric; rank by flakiness; fix the worst offenders. Determinism techniques (inject `Clock`, Awaitility, reset WireMock, unique data, no shared mutable state) attack root causes.

The expert framing: **treat a flaky test as a defect, not as noise to retry past** — because the same nondeterminism that flakes the test often reflects a real race in the system under test.

#### Q77. [Practical] How do you implement testing-in-production safely for APIs (synthetic monitoring, canary, traffic shadowing), and how does it relate to your pre-prod suite?

Pre-prod tests cannot reproduce real traffic shapes, data distributions, and dependency behavior; production testing closes that gap *without* risking users:

- **Synthetic monitoring / canaries** — run a small set of contract-derived, read-mostly (or sandboxed-write) journeys continuously against prod, alerting on SLA/SLO breach. These are your *contract tests, promoted to live probes*.
- **Traffic shadowing (dark traffic)** — mirror a copy of real production requests to a new version and **discard its responses**, comparing them (diff) against the current version's. Catches behavioral regressions on *real* inputs with **zero user impact** because the shadow's output never reaches the client.
- **Canary releases** — route a small % of real traffic to the new version, gated on error-rate/latency SLOs with automatic rollback.
- **Feature flags + guardrails** — ship dark, enable progressively, kill instantly.

Safety mechanisms that make writes safe in prod: **test-tenant/synthetic accounts** isolated from real data, **idempotency keys** so retried probes don't double-act, and **data tagging** so synthetic data is excluded from analytics/billing. The relationship to pre-prod: production testing is **not a replacement** for the pyramid — it covers the residual risk (real load, real data, real dependencies) that is *impossible* to fully simulate pre-prod, and its probes are often the *same contracts* you already verified offline, now running live.

#### Q78. [Theory] How do you architect a shared API-testing platform (a "paved road") so hundreds of teams test consistently, and what governance keeps it from rotting?

A platform-scale testing capability is a *product* the platform team builds for stream-aligned teams. Architecture:

- **Shared libraries / harnesses** — auth-token minting, WireMock setup, Testcontainers modules, REST Assured base specs, OpenAPI-validation filters — so a team writes a test in minutes, not a day, and every team's tests look alike.
- **Spec/contract registry** — a central OpenAPI/Protobuf/SDL registry plus a **Pact Broker** as the integration backbone, with `can-i-deploy` wired into every pipeline.
- **CI templates** — reusable pipeline jobs (lint spec → unit → component → contract → publish results) teams inherit rather than reinvent, with the *right thing as the default* (the easy path is the correct path).
- **Golden defaults & policy-as-code** — breaking-change detection, coverage/flakiness thresholds, and security checks enforced centrally but overridable with justification.

Governance that prevents rot:

- **Treat the harness as a versioned product** with a changelog, deprecation policy, and SLAs — not a copy-pasted snippet that forks per team.
- **Metrics as feedback** — track adoption, suite p95 duration, flake rate, and contract-coverage across the estate; the platform team optimizes the worst.
- **Conway's Law awareness** — the test architecture mirrors team boundaries; contracts live at the seams *between* teams, owned jointly via the broker.
- **Avoid the central-bottleneck anti-pattern** — the platform provides paved roads and gates, but each team *owns* its own tests; a central QA team writing everyone's tests does not scale and recreates the shared-environment bottleneck in human form.

The expert signal: you make the **right thing the easy thing** (leverage), enforce a few **non-negotiable gates** (contracts, breaking-change, security), and **measure suite health as a product metric** — turning testing from per-team craft into a governed platform capability.

#### Q79. [Theory] How do `RestAssured.baseURI`/`basePath`/`port` compose into the final URL, and why prefer a reusable `RequestSpecification` over these statics?

REST Assured builds the request URL by composing several **static** fields plus the per-request path:

- `RestAssured.baseURI` — scheme + host, e.g. `http://localhost`.
- `RestAssured.port` — the port appended to the host (defaults to 8080 against `localhost` if unset).
- `RestAssured.basePath` — a path prefix prepended to every request path, e.g. `/v1`.
- The **path argument** to the verb — `.get("/users/{id}")` — appended after `basePath`.

So `baseURI=http://localhost`, `port=8081`, `basePath=/v1`, `.get("/users/42")` resolves to `http://localhost:8081/v1/users/42`. The internal hazard: these statics are **global mutable JVM state**, so a value set in one test leaks into the next unless reset — a classic source of order-dependent flakiness under parallel execution. The robust pattern is an immutable, reusable `RequestSpecification` built once and passed with `.spec(...)`:

```java
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.specification.RequestSpecification;

RequestSpecification apiSpec = new RequestSpecBuilder()
    .setBaseUri("http://localhost").setPort(8081).setBasePath("/v1")
    .addHeader("Accept", "application/json")
    .build();

given().spec(apiSpec).when().get("/users/42").then().statusCode(200);
```

A `RequestSpecification` bundles base URI, headers, auth, filters, and body defaults into one composable, thread-safe-to-share object — far safer than mutating globals, and the recommended way to share config across a suite.

#### Q80. [Practical] How do REST Assured filters work internally, and what cross-cutting concerns would you implement with one?

A `Filter` is an interceptor in REST Assured's request/response chain — analogous to a servlet filter. It receives the mutable `FilterableRequestSpecification`, the response spec, and a `FilterContext`, and **must call `ctx.next(req, resp)`** to continue the chain and return the resulting `Response`. Filters compose: each wraps the next, and the verb execution sits at the bottom.

```java
import io.restassured.filter.Filter;
import io.restassured.filter.FilterContext;
import io.restassured.response.Response;
import io.restassured.specification.FilterableRequestSpecification;
import io.restassured.specification.FilterableResponseSpecification;

class CorrelationFilter implements Filter {
    public Response filter(FilterableRequestSpecification req,
                           FilterableResponseSpecification res,
                           FilterContext ctx) {
        req.header("X-Correlation-Id", java.util.UUID.randomUUID().toString());
        long start = System.nanoTime();
        Response response = ctx.next(req, res);          // proceed down the chain
        long ms = (System.nanoTime() - start) / 1_000_000;
        System.out.println(req.getMethod() + " " + req.getURI()
            + " -> " + response.statusCode() + " in " + ms + "ms");
        return response;
    }
}

given().filter(new CorrelationFilter()).when().get("/users/42").then().statusCode(200);
```

The built-in `.log()` calls, the `OpenApiValidationFilter` (Q51), and Allure reporting are all implemented as filters via exactly this mechanism. Reach for a custom filter for cross-cutting concerns — auth/token injection, correlation ids, HMAC request signing, metrics — so individual tests stay clean and the concern lives in one place.

#### Q81. [Theory] What is the "ice cream cone" anti-pattern, how does it arise organically, and what is the migration path back to a healthy pyramid?

The **ice cream cone** is the inverted test pyramid: many slow E2E/UI tests on top, few unit tests at the base — the shape you get when a team tests primarily through the UI or a shared end-to-end environment. It arises organically, not by decision:

- E2E tests are *easy to start* with (record-and-playback, "just test it like a user") and give visceral confidence, so early teams over-invest there.
- Unit/component tests require designing for testability (dependency injection, seams), which feels like extra upfront work.
- Each new feature adds one more E2E flow because that's the established pattern; the cone grows top-heavy.

The cost compounds: E2E suites are slow (wall-clock minutes-to-hours), flaky (timing, environment, data coupling), and produce **non-localized failures** (a red E2E test could be any of a dozen services). Teams learn to ignore red, which destroys the suite's value.

Migration path (incremental, not big-bang):

1. **Stop the bleeding** — for each *new* feature, write coverage at the lowest sufficient level; ban net-new E2E-by-default.
2. **Identify the flakiest/slowest E2E tests** (data-driven) and replace each with a **contract test + component test** pair that gives equivalent confidence faster.
3. **Push assertions down** — a behavior verifiable in a component test (service + virtualized downstreams) shouldn't be re-verified E2E.
4. **Keep a thin E2E smoke layer** for a handful of critical cross-service journeys, and treat it as a *smoke signal*, not comprehensive coverage.

The senior signal: recognizing the cone is an *emergent* structure with a *gradual* remedy — you reshape it test-by-test using flakiness/duration data, you don't rewrite the suite in one sprint.

#### Q82. [Practical] How do you test observability of an API — that it emits the correct traces, metrics, and structured logs — and why is this part of API testing in 2026?

In a 2026 microservices estate, observability *is* part of the API's contract: downstream alerting, SLO dashboards, and distributed tracing depend on the service emitting correct telemetry. Untested telemetry silently rots (a renamed metric breaks a dashboard; a dropped trace-context header breaks end-to-end tracing). You test it explicitly:

- **Trace context propagation** — assert the service **reads** an incoming W3C `traceparent` header and **propagates** it to downstream calls (verifiable via WireMock: `verify(getRequestedFor(...).withHeader("traceparent", matching(".+")))`). This proves distributed traces won't break at this hop.
- **Metrics** — assert counters/timers exist and move. With Micrometer + Spring, hit the endpoint then read the registry:

```java
@Autowired io.micrometer.core.instrument.MeterRegistry registry;

@Test
void requestIncrementsHttpServerMetric() {
    given().when().get("/users/42").then().statusCode(200);

    double count = registry.get("http.server.requests")
        .tag("uri", "/users/{id}").tag("status", "200")
        .timer().count();
    org.assertj.core.api.Assertions.assertThat(count).isGreaterThanOrEqualTo(1);
}
```

- **Spans** — with the OpenTelemetry test SDK (`InMemorySpanExporter`), assert the request produced a server span with the expected name/attributes and the right parent.
- **Structured logs** — capture logs (e.g. a Logback `ListAppender`) and assert key fields are present (correlation id, no PII leakage, error logs on 5xx).

Why it belongs in the API suite: telemetry has no UI and no user who notices when it breaks until an incident — so a regression is invisible until exactly the moment you most need observability. Testing it converts "we hope it's emitting traces" into an enforced, regression-guarded contract.

## 🧩 Extended Questions — Set 2: Practical scenarios, troubleshooting & coding

### 🟢 — extended

#### Q83. [Practical] Your REST Assured `.body("price", equalTo(9.99))` test fails with "Expected: <9.99> but: was <9.99>". What is wrong and how do you fix it?

This is the single most common REST Assured surprise: the *values* print identically but the *types* differ. JSON `9.99` is deserialized by REST Assured's GPath into a Java `Float`, while the literal `9.99` you passed to `equalTo(...)` is a Java `double`/`Double`. Hamcrest's `equalTo` compares with `.equals()`, and `Float(9.99).equals(Double(9.99))` is `false` — so the matcher fails even though both `toString()` to "9.99".

Fixes, in order of robustness:

```java
// 1. Match the float type explicitly
.body("price", equalTo(9.99f))

// 2. Use a tolerant numeric matcher (type-agnostic on doubles)
.body("price", is(closeTo(9.99, 0.0001)))

// 3. For money, configure JSON numbers to BigDecimal and compare exactly
import io.restassured.config.JsonConfig;
import io.restassured.path.json.config.JsonPathConfig.NumberReturnType;
given().config(config().jsonConfig(
        JsonConfig.jsonConfig().numberReturnType(NumberReturnType.BIG_DECIMAL)))
    .when().get("/items/1")
    .then().body("price", comparesEqualTo(new java.math.BigDecimal("9.99")));
```

The lesson for troubleshooting any "looks-equal-but-fails" matcher: print the runtime class — `Object p = resp.path("price"); System.out.println(p.getClass());` — before assuming the values differ.

#### Q84. [Practical] A teammate's test intermittently sends the request body of the *previous* test. What's the likely cause and how do you make REST Assured tests isolation-safe?

The symptom points at **shared global mutable state** on `io.restassured.RestAssured` (the static `requestSpecification`, `baseURI`, `port`, `config`, `filters`). If one test does `RestAssured.requestSpecification = ...` or `RestAssured.filters(...)` and never resets, that spec/body default leaks into later tests — and under JUnit 5 parallel execution the leakage becomes non-deterministic.

Fixes:

```java
@AfterEach
void resetGlobalState() {
    RestAssured.reset();   // clears baseURI, port, basePath, specs, filters, config
}
```

Better: stop using the statics entirely. Build an immutable `RequestSpecification` once and pass it per call so nothing is shared mutably:

```java
RequestSpecification spec = new RequestSpecBuilder()
    .setBaseUri("http://localhost").setPort(port)
    .setContentType("application/json").build();

given().spec(spec).body(payload).when().post("/users").then().statusCode(201);
```

The troubleshooting heuristic: intermittent, order-dependent failures that disappear when run singly almost always mean leaked global state or shared test data — not a bug in the code under test.

#### Q85. [Coding] Write a JUnit 5 + REST Assured test that creates a user, then deletes it in teardown so the test leaves no residue, tolerating an already-deleted resource.

```java
import io.restassured.RestAssured;
import org.junit.jupiter.api.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

class SelfCleaningUserTest {

    private String createdId;

    @BeforeAll
    static void config() {
        RestAssured.baseURI = "http://localhost";
        RestAssured.port = 8080;
    }

    @Test
    void createUserPersistsAndIsFetchable() {
        String email = "u-" + java.util.UUID.randomUUID() + "@example.com";

        createdId = given()
            .contentType("application/json")
            .body("{\"name\":\"Ada\",\"email\":\"" + email + "\"}")
        .when()
            .post("/users")
        .then()
            .statusCode(201)
            .body("email", equalTo(email))
            .extract().path("id");

        given().pathParam("id", createdId)
        .when().get("/users/{id}")
        .then().statusCode(200).body("email", equalTo(email));
    }

    @AfterEach
    void cleanUp() {
        if (createdId != null) {
            // 204 if deleted now, 404 if the test already deleted it — both acceptable
            given().pathParam("id", createdId)
            .when().delete("/users/{id}")
            .then().statusCode(anyOf(equalTo(204), equalTo(404)));
        }
    }
}
```

The unique email avoids collisions under parallel runs, and the idempotent teardown (accepting 404) means a test that deletes mid-body, or a re-run after a crash, still cleans up without failing.

#### Q86. [Practical] You get `java.lang.IllegalStateException: Cannot serialize because no JSON serializer found in classpath`. What does it mean and how do you resolve it?

REST Assured serializes a POJO/Map body by detecting a JSON mapper on the classpath. This error means you passed an object body but **no supported mapper** (Jackson 2 `jackson-databind`, Jackson 1, or Gson) is present, so REST Assured can't turn the object into JSON.

Resolution:

```xml
<dependency>
  <groupId>com.fasterxml.jackson.core</groupId>
  <artifactId>jackson-databind</artifactId>
  <scope>test</scope>
</dependency>
```

A frequent variant: the body serializes but `Instant`/`LocalDate` fields come out as numeric arrays — that means `jackson-databind` is present but the `JavaTimeModule` isn't registered on the auto-created mapper. Either add `jackson-datatype-jsr310` and supply a custom mapper, or pass the body as a pre-serialized JSON `String`. Quick workaround when you just need the test to pass: build the JSON yourself (`.body("{\"name\":\"Ada\"}")`), which needs no serializer at all.

#### Q87. [Coding] Write a parameterized REST Assured test that verifies an endpoint rejects every flavor of malformed payload with a 400.

```java
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;
import org.junit.jupiter.params.provider.Arguments;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class MalformedPayloadTest {

    static Stream<Arguments> badBodies() {
        return Stream.of(
            arguments("empty body",        ""),
            arguments("not json",          "name=Ada"),
            arguments("truncated json",    "{\"name\":\"Ada\""),
            arguments("wrong type",        "{\"name\":123}"),
            arguments("null required",     "{\"name\":null}"),
            arguments("extra trailing",    "{\"name\":\"Ada\"},"),
            arguments("array not object",  "[{\"name\":\"Ada\"}]")
        );
    }

    @ParameterizedTest(name = "{0} -> 400")
    @MethodSource("badBodies")
    void malformedPayloadsAreRejected(String label, String body) {
        given()
            .contentType("application/json")
            .body(body)
        .when()
            .post("/users")
        .then()
            .statusCode(400)
            // never a 500: malformed input must be a client error, not a server crash
            .body("status", org.hamcrest.Matchers.not(org.hamcrest.Matchers.equalTo(500)));
    }
}
```

The key assertion beyond the 400 is that **none of these produce a 500** — a malformed body escaping into an unhandled exception (NPE, parse error surfacing as 500) is a real, shippable bug this test catches.

#### Q88. [Practical] A test asserts `Content-Type` equals `application/json` and fails because the server returns `application/json;charset=UTF-8`. How do you write robust header assertions?

Exact-equality on headers is brittle because servers legitimately append parameters (`;charset=UTF-8`), and header *names* are case-insensitive while some matchers are not. Robust patterns:

```java
// Substring instead of exact equality for content type
.contentType(org.hamcrest.Matchers.containsString("application/json"))
// or REST Assured's content-type-aware matcher (parses media type, ignores params)
.contentType(io.restassured.http.ContentType.JSON)

// For header values that may carry parameters or vary in case
.header("Content-Type", containsStringIgnoringCase("application/json"))

// Presence + pattern rather than literal value
.header("ETag", matchesPattern("(W/)?\"[^\"]+\""))
.header("Cache-Control", anyOf(containsString("no-store"), containsString("max-age")))
```

The general troubleshooting rule: assert the **semantic** part of a header (the media type, the directive that matters) using `containsString`/pattern matchers, not the full literal string, which couples the test to incidental formatting the spec permits to vary.

#### Q89. [Coding] Write a REST Assured test that confirms a `204 No Content` DELETE truly returns an empty body and the resource is then gone.

```java
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

class DeleteSemanticsTest {

    @Test
    void deleteReturns204EmptyBodyAndResourceIsGone() {
        // Arrange: create something to delete
        String id = given()
            .contentType("application/json").body("{\"name\":\"temp\"}")
        .when().post("/users")
        .then().statusCode(201).extract().path("id");

        // Act + assert: DELETE -> 204, empty body, no content-type for a body
        given().pathParam("id", id)
        .when().delete("/users/{id}")
        .then()
            .statusCode(204)
            .body(emptyOrNullString());      // no body on 204

        // Assert: subsequent GET is 404
        given().pathParam("id", id)
        .when().get("/users/{id}")
        .then().statusCode(404);

        // Assert: a second DELETE is idempotent (404, not 500)
        given().pathParam("id", id)
        .when().delete("/users/{id}")
        .then().statusCode(anyOf(equalTo(204), equalTo(404)));
    }
}
```

`emptyOrNullString()` guards the common bug where a `204` is sent but the framework still serializes a body (violating the spec). The second DELETE confirms DELETE's idempotency — a repeated DELETE must never throw a 5xx.

### 🟡 — extended

#### Q90. [Practical] Your WireMock-backed test passes locally but fails in CI with "Request was not matched". How do you debug it methodically?

"Not matched" means the incoming request matched none of your stubs. Debug in this order rather than guessing:

1. **Print the near-miss diff** — ask WireMock what *almost* matched:

```java
WireMock.findNearMissesForAllUnmatchedRequests()
    .forEach(nm -> System.out.println(nm.getDiff()));
```

The diff shows field-by-field where the actual request diverged (a header with `;charset=utf-8`, a JSON field your matcher pinned exactly, a path with a trailing slash).

2. **Dump the unmatched requests** — `findAllUnmatchedRequests()` shows exactly what your code sent (often a different URL/header than you assumed because CI config differs).
3. **Loosen over-strict matchers** — switch `equalToJson(body, true, true)` (ignore order/extra) or `matchingJsonPath` instead of `withRequestBody(equalTo(...))`; relax a header matcher to `containing`/`matching`.
4. **Confirm the client points at WireMock** — in CI the base URL is often injected differently; assert `wm.getHttpBaseUrl()` is what the client actually used.

The usual root cause is environment-driven request differences (a CI-injected header, a proxy adding `Accept-Encoding`, a different charset) combined with a matcher that was stricter than necessary. Near-miss diffs turn this from trial-and-error into a one-line diagnosis.

#### Q91. [Coding] Write a WireMock scenario test for a poll-then-complete flow where the first two polls return PENDING and the third returns COMPLETED.

```java
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import org.junit.jupiter.api.Test;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

@WireMockTest
class PollingScenarioTest {

    @Test
    void pollsUntilComplete(WireMockRuntimeInfo wm) {
        String s = "polling";
        // PENDING (start) -> PENDING (poll-2) -> COMPLETED (done)
        stubFor(get("/jobs/1").inScenario(s)
            .whenScenarioStateStarted()
            .willReturn(okJson("{\"status\":\"PENDING\"}"))
            .willSetStateTo("poll-2"));

        stubFor(get("/jobs/1").inScenario(s)
            .whenScenarioStateIs("poll-2")
            .willReturn(okJson("{\"status\":\"PENDING\"}"))
            .willSetStateTo("done"));

        stubFor(get("/jobs/1").inScenario(s)
            .whenScenarioStateIs("done")
            .willReturn(okJson("{\"status\":\"COMPLETED\"}")));

        JobClient client = new JobClient(wm.getHttpBaseUrl());
        String finalStatus = client.pollUntilTerminal("/jobs/1"); // loops on PENDING

        assertThat(finalStatus).isEqualTo("COMPLETED");
        verify(exactly(3), getRequestedFor(urlEqualTo("/jobs/1")));
    }
}
```

The `verify(exactly(3), ...)` is the real assertion of *behavior*: it proves the client polled the right number of times and stopped on the terminal state rather than looping forever or giving up early.

#### Q92. [Practical] A `@SpringBootTest(webEnvironment = RANDOM_PORT)` test gets `Connection refused`. Walk through the likely causes.

`Connection refused` means nothing is listening at the host:port the client used. The candidate causes, in order of frequency:

- **Port not injected** — REST Assured still points at the default 8080 because you didn't wire `@LocalServerPort` into `RestAssured.port`. Fix: `@LocalServerPort int port; RestAssured.port = port;` in `@BeforeEach`.
- **Wrong web environment** — the test used `webEnvironment = MOCK` (the default) so no real connector started; `MOCK` needs `MockMvc`/`WebTestClient`, not a socket. Use `RANDOM_PORT` (or `DEFINED_PORT`) for a real client.
- **`baseURI` wrong** — left at `https://` or a stale host, so the client never reaches `localhost`.
- **App failed to start** but the test proceeded — check for a context-load failure earlier in the log (a missing datasource, a Testcontainer not started).
- **Timing under parallelism** — for non-Spring-managed servers, the client ran before the server bound the port.

Diagnostic one-liner: log `port` and hit `/actuator/health` first; if health is `Connection refused` too, it's a server/port wiring problem, not your endpoint.

#### Q93. [Coding] Write a test that obtains an OAuth2 token, caches it, and refreshes only when expired — to avoid minting a token per test.

```java
import io.restassured.RestAssured;
import java.time.Instant;
import static io.restassured.RestAssured.given;

class TokenProvider {
    private String token;
    private Instant expiresAt = Instant.EPOCH;
    private final String tokenUrl, clientId, clientSecret;

    TokenProvider(String tokenUrl, String clientId, String clientSecret) {
        this.tokenUrl = tokenUrl; this.clientId = clientId; this.clientSecret = clientSecret;
    }

    synchronized String token() {
        // refresh 30s before actual expiry to avoid races under a long parallel suite
        if (Instant.now().isAfter(expiresAt.minusSeconds(30))) {
            io.restassured.response.Response r = given()
                .auth().preemptive().basic(clientId, clientSecret)
                .formParam("grant_type", "client_credentials")
                .formParam("scope", "orders:read")
            .when().post(tokenUrl)
            .then().statusCode(200).extract().response();

            token = r.path("access_token");
            long ttl = ((Number) r.path("expires_in")).longValue();
            expiresAt = Instant.now().plusSeconds(ttl);
        }
        return token;
    }
}

// Usage in a base test class
static final TokenProvider TOKENS =
    new TokenProvider("http://idp/token", "ci-client", "secret");

@Test
void protectedCallUsesCachedToken() {
    given().auth().oauth2(TOKENS.token())
    .when().get("/orders")
    .then().statusCode(200);
}
```

`synchronized` makes it parallel-safe; the 30-second skew prevents the classic flake where the cached token expires *mid-suite* and a handful of late tests get spurious 401s.

#### Q94. [Practical] Two tests pass individually but fail when run together. How do you find and fix the order dependency?

Order dependence ("passes alone, fails in suite") is a flakiness archetype. Systematic diagnosis:

1. **Reproduce deterministically** — run the suite with a fixed method order (JUnit 5 `@TestMethodOrder(MethodOrderer.MethodName.class)` or a fixed random seed) so the failure is repeatable, then run the suspected pair in isolation in both orders.
2. **Bisect** — run halves of the suite to localize which earlier test poisons the later one.
3. **Inspect shared state** — the culprit is almost always one of: shared mutable test data (a row one test creates and another assumes absent), leaked global config (`RestAssured.*` statics, system properties), un-reset mocks (WireMock stubs/journal), or a shared Spring context mutated by one test (`@DirtiesContext` missing or, worse, present and reordering).

Fixes: make each test self-provision unique data, `RestAssured.reset()`/`WireMock.reset()` in teardown, avoid static mutable fields, and never assert on global counts ("there are 3 users") that other tests perturb. The principle is the **I in FIRST** — a correctly isolated suite is order-independent *by construction*, which is also the precondition for safe parallelism.

#### Q95. [Coding] Write a test proving BOLA protection: user A cannot read user B's resource by guessing its id.

```java
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

class BolaTest {

    @Test
    void userCannotReadAnotherUsersResource() {
        String tokenA = login("alice", "pw");
        String tokenB = login("bob", "pw");

        // Alice creates a private note
        String noteId = given().auth().oauth2(tokenA)
            .contentType("application/json").body("{\"text\":\"alice secret\"}")
        .when().post("/notes")
        .then().statusCode(201).extract().path("id");

        // Bob, authenticated but not the owner, must be denied
        given().auth().oauth2(tokenB).pathParam("id", noteId)
        .when().get("/notes/{id}")
        .then().statusCode(anyOf(is(404), is(403)));   // 404 avoids leaking existence

        // Bob also cannot update or delete it
        given().auth().oauth2(tokenB).pathParam("id", noteId)
            .contentType("application/json").body("{\"text\":\"hacked\"}")
        .when().put("/notes/{id}")
        .then().statusCode(anyOf(is(404), is(403)));

        // Alice can still read her own note unchanged
        given().auth().oauth2(tokenA).pathParam("id", noteId)
        .when().get("/notes/{id}")
        .then().statusCode(200).body("text", equalTo("alice secret"));
    }

    private String login(String u, String p) {
        return given().contentType("application/json")
            .body("{\"username\":\"" + u + "\",\"password\":\"" + p + "\"}")
        .when().post("/auth/login")
        .then().statusCode(200).extract().path("accessToken");
    }
}
```

Preferring `404` over `403` for the denial avoids leaking that the resource exists (an information-disclosure subtlety). The final positive assertion proves the denial didn't also break the legitimate owner's access — a guard against an over-broad fix.

#### Q96. [Practical] A flaky test fails ~1 in 20 runs because of an async side effect. Show how to convert it from `Thread.sleep` to a robust wait.

A 1-in-20 flake on an async assertion is the signature of a fixed `Thread.sleep` that is usually-but-not-always long enough. The fix is to poll for the condition with an upper bound:

```java
// BEFORE — guesses the timing, flakes when the system is slow
Thread.sleep(500);
given().when().get("/orders/" + id).then().body("status", equalTo("CONFIRMED"));

// AFTER — Awaitility: returns as soon as true, fails fast with a clear message if never
import static org.awaitility.Awaitility.await;
import java.time.Duration;

await()
    .atMost(Duration.ofSeconds(5))
    .pollInterval(Duration.ofMillis(100))
    .ignoreExceptions()        // a transient 404 before the read model catches up is fine
    .untilAsserted(() ->
        given().pathParam("id", id)
        .when().get("/orders/{id}")
        .then().statusCode(200).body("status", equalTo("CONFIRMED")));
```

Awaitility makes the test **faster in the common case** (it stops the instant the condition holds, not after a fixed 500 ms) *and* **non-flaky** (the 5-second bound absorbs a slow CI box). If even 5 seconds is sometimes too short, that's not a test problem — it's signal that the system's convergence SLA is being violated, which the test now surfaces explicitly.

### 🟠 — extended

#### Q97. [Practical] Production has intermittent duplicate orders; functional tests are green. Design the API tests that would have caught it.

Green functional tests with duplicate-write bugs in prod almost always means the **retry-after-partial-failure** path was never exercised. Design tests that reproduce it deterministically:

1. **Fault-inject a response-phase failure** — with WireMock virtualizing the downstream (or a proxy), accept the write but drop the *response* (`Fault.CONNECTION_RESET_BY_PEER` or a delay past the client timeout). The client's retry now fires against a write that already succeeded.
2. **Assert exactly-once effect** — after the retried call, query and assert exactly one order exists; assert the second attempt returned the *same* resource (idempotent replay), not a new one.
3. **Idempotency-key contract** — assert the key is generated at the **logical-request boundary**, not per HTTP attempt (the bug in Q43): same logical request + retry ⇒ same key ⇒ same order; different request + same key ⇒ 409.
4. **Concurrent duplicates** — fire two identical requests with the same idempotency key in parallel and assert only one resource is created (tests the server-side dedupe under a race, not just sequential replay).

```java
@Test
void retryAfterDroppedResponseDoesNotDoubleCreate() {
    String key = java.util.UUID.randomUUID().toString();
    // First attempt: gateway accepts but response is lost -> client retries internally
    OrderResult r = client.placeOrderWithRetry(payload, key);   // client retries on timeout
    assertThat(r.status()).isEqualTo("CONFIRMED");

    int count = given().queryParam("idempotencyKey", key)
        .when().get("/orders").then().statusCode(200).extract().path("size()");
    assertThat(count).isEqualTo(1);   // exactly once, despite the retry
}
```

The senior point: the bug lives at a **layer unit tests cannot reach** (a real client retrying over a real failure), so the regression guard must be a component test with deliberate fault injection plus a concurrency case.

#### Q98. [Coding] Write a test that fires two concurrent identical POSTs with the same Idempotency-Key and asserts only one resource is created.

```java
import java.util.concurrent.*;
import java.util.List;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

class ConcurrentIdempotencyTest {

    @Test
    void concurrentSameKeyCreatesExactlyOne() throws Exception {
        String key = java.util.UUID.randomUUID().toString();
        String body = "{\"amount\":1000,\"currency\":\"usd\"}";
        int threads = 8;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);

        List<Callable<String>> tasks = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                start.await();                 // release all threads simultaneously
                return given()
                    .header("Idempotency-Key", key)
                    .contentType("application/json").body(body)
                .when().post("/payments")
                .then().statusCode(org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.equalTo(200),
                        org.hamcrest.Matchers.equalTo(201)))
                    .extract().path("id");
            });
        }

        List<Future<String>> futures = new java.util.ArrayList<>();
        for (Callable<String> t : tasks) futures.add(pool.submit(t));
        start.countDown();                     // fire the burst

        java.util.Set<String> ids = new java.util.HashSet<>();
        for (Future<String> f : futures) ids.add(f.get(10, TimeUnit.SECONDS));
        pool.shutdown();

        // All concurrent calls must resolve to the SAME single resource
        assertThat(ids).hasSize(1);

        int count = given().queryParam("key", key)
            .when().get("/payments").then().extract().path("size()");
        assertThat(count).isEqualTo(1);
    }
}
```

The `CountDownLatch` synchronizes the burst so the requests actually race (rather than trickling sequentially), which is what exercises the server's dedupe-under-contention logic. Asserting `ids` collapses to size 1 proves every concurrent caller saw the *same* created resource.

#### Q99. [Practical] How do you load-test an endpoint and gate the build on p99 latency, and how do you distinguish a real regression from environment noise?

Run a controlled load profile with thresholds as pass/fail gates (k6), and separate *signal* from *noise* with baselining and warm-up:

```javascript
import http from 'k6/http';
import { check } from 'k6';

export const options = {
  scenarios: {
    steady: { executor: 'constant-arrival-rate', rate: 200, timeUnit: '1s',
              duration: '3m', preAllocatedVUs: 50, maxVUs: 200 },
  },
  thresholds: {
    http_req_duration: ['p(95)<300', 'p(99)<800'],
    http_req_failed: ['rate<0.005'],
  },
};

export default function () {
  const res = http.get(`${__ENV.BASE}/v1/products?limit=20`,
    { headers: { Authorization: `Bearer ${__ENV.TOKEN}` } });
  check(res, { 'status 200': (r) => r.status === 200 });
}
```

Distinguishing regression from noise:

- **Closed vs open model** — use a `constant-arrival-rate` (open model) so the test holds *throughput* steady; a VU-based (closed) model lets a slow server *reduce* its own load and hide the regression.
- **Warm-up / discard ramp** — exclude the JIT/connection-pool warm-up window from the measured percentiles, or the first run always looks slow.
- **Baseline + delta gate** — compare against a stored baseline percentile with a tolerance band, not an absolute number, on an isolated, pinned runner; flag only sustained deltas beyond the band.
- **Co-locate and pin** — same instance type, same dataset size, dedicated network, multiple runs to compute variance — a one-off spike on a noisy shared runner is not a regression.

The senior framing: a perf gate is only trustworthy if the harness controls the variables (arrival rate, warm-up, environment); otherwise it produces flaky red builds that teams learn to override.

#### Q100. [Practical] A response sometimes includes an extra field and your strict JSON-Schema test fails on an additive, backward-compatible change. How do you fix the test strategy?

The failure comes from `additionalProperties: false` on a **consumer-side** schema treating a purely additive provider change as breaking. That violates the robustness principle: a tolerant consumer should ignore unknown fields. The fix is to align the *closedness* of the schema with *who owns it and what it's policing*:

- **Consumer side** — validate **only the fields the consumer reads**. Either drop `additionalProperties: false`, or use Pact **type matchers** that assert just the consumed fields' shape, so the provider can add fields freely.
- **Provider side** — keep `additionalProperties: false` on the provider's *own* response tests, so the provider polices its output and catches accidental leakage/removal. There, an unexpected new field *should* fail until intentionally added to the schema.

```java
// Consumer-side: tolerant — assert only what we consume
.body("id", notNullValue())
.body("email", matchesPattern(".+@.+"))    // ignores any extra fields the provider added

// Provider-side response test: strict closed schema to police output
.body(matchesJsonSchemaInClasspath("schemas/user-response.closed.json"))
```

The principle (parallel to Q64): **closed schemas police output (provider-owned); open/type-based expectations validate input (consumer-owned).** Conflating them is exactly why naive schema suites flag false breakages on every additive release.

#### Q101. [Coding] Write a contract-style test using REST Assured + a closed JSON Schema for the provider, and show the schema.

```java
import static io.restassured.RestAssured.given;
import static io.restassured.module.jsonschema.JsonSchemaValidator.matchesJsonSchemaInClasspath;

class ProviderResponseContractTest {

    @Test
    void userResponseHonoursClosedSchema() {
        given().pathParam("id", 42)
        .when().get("/users/{id}")
        .then()
            .statusCode(200)
            .body(matchesJsonSchemaInClasspath("schemas/user-response.closed.json"));
    }
}
```

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "additionalProperties": false,
  "required": ["id", "email", "roles", "createdAt"],
  "properties": {
    "id":        { "type": "integer", "minimum": 1 },
    "email":     { "type": "string", "format": "email" },
    "roles":     { "type": "array", "items": { "type": "string" }, "minItems": 1 },
    "createdAt": { "type": "string", "format": "date-time" }
  }
}
```

Because `additionalProperties` is `false`, this *provider* test fails the build the moment the response gains an undeclared field — forcing a deliberate schema update (and a conscious decision about backward compatibility) rather than letting fields leak silently. This is the strict half of the strategy from Q100; consumers would use a tolerant variant.

#### Q102. [Practical] Your contract (Pact) provider verification fails only in CI with "state handler not found". What's happening and how do you fix provider states?

The provider verifier, before replaying each interaction, invokes the `@State("...")` handler whose description **string-matches** the consumer's `given(...)`. "State handler not found" means no provider method is registered for a state the contract references — almost always a **string mismatch** between the consumer's `given("user 42 exists")` and the provider's `@State("user exists")`, or a handler that exists locally but isn't on the CI classpath / not in the verified test class.

Fixes and prevention:

- **Exact-match the state strings** — they are an implicit shared vocabulary; a typo or wording drift breaks verification. Extract them into shared constants if both repos can import them.
- **Ensure the handler is discovered** — the `@State` method must live in the class wired to `PactVerificationContext`, and the verification test must be picked up by CI (right module, right source set).
- **Parameterized states** — if the consumer used `given("user exists", Map.of("id", 42))`, the provider handler must accept the params (`@State` method with a `Map` argument) and set up *that* id.
- **Fail loudly on unknown states** — configure the verifier so a missing state handler is an error, not silently skipped, so the gap surfaces in PR not prod.

The root insight: provider states are a **contract within the contract** — a brittle string coupling that must be governed (shared constants, lint) exactly because it isn't type-checked across repos.

#### Q103. [Coding] Write an Awaitility-based test for a webhook: trigger an action, then assert your WireMock callback endpoint received the expected payload within a timeout.

```java
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;

@WireMockTest
class WebhookDeliveryTest {

    @Test
    void serviceDeliversWebhookWithExpectedPayload(WireMockRuntimeInfo wm) {
        // 1. Stand up the callback receiver
        stubFor(post(urlEqualTo("/callbacks/order-created"))
            .willReturn(aResponse().withStatus(200)));

        // 2. Register the callback URL, then trigger the action that fires it
        String callbackUrl = wm.getHttpBaseUrl() + "/callbacks/order-created";
        given().contentType("application/json")
            .body("{\"item\":\"book\",\"callbackUrl\":\"" + callbackUrl + "\"}")
        .when().post("/orders")
        .then().statusCode(202);

        // 3. Await the asynchronous webhook delivery and assert its payload
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(250))
            .untilAsserted(() ->
                verify(postRequestedFor(urlEqualTo("/callbacks/order-created"))
                    .withHeader("Content-Type", containing("application/json"))
                    .withRequestBody(matchingJsonPath("$.eventType", equalTo("ORDER_CREATED")))
                    .withRequestBody(matchingJsonPath("$.orderId", matching(".+")))));
    }
}
```

WireMock plays a **dual role** here: it *receives* the outbound webhook (acting as the consumer's endpoint) and lets you `verify(...)` the delivery. Wrapping `verify` in `await().untilAsserted` handles the asynchrony — the webhook fires after the `202`, so a bare `verify` would race and flake.

#### Q104. [Practical] How do you test that secrets/PII don't leak in API responses or error bodies, and make it a regression-guarded check?

PII/secret leakage is a high-severity, easily-regressed defect (a stack trace exposing a SQL string, a serialized entity exposing a password hash, a debug field). Make it an explicit, repeatable test rather than hoping reviewers catch it:

- **Forbidden-field assertions on representations** — assert sensitive fields are absent from response bodies:

```java
given().pathParam("id", 42).when().get("/users/{id}")
.then().statusCode(200)
    .body("password", nullValue())
    .body("passwordHash", nullValue())
    .body("ssn", nullValue())
    .body("$", not(hasKey("internalNotes")));   // GPath: top-level key absent
```

- **Error-body hygiene** — force errors (malformed input, forced 500 in a test profile) and assert the body is **RFC 9457 Problem Details with no stack trace, SQL, class names, or file paths**:

```java
given().contentType("application/json").body("{bad json")
.when().post("/users")
.then().statusCode(400)
    .body(not(containsStringIgnoringCase("Exception")))
    .body(not(containsStringIgnoringCase("at com.")))   // no stack frames
    .body(not(containsStringIgnoringCase("SQL")));
```

- **Log assertions** — capture logs (Logback `ListAppender`) and assert PII (emails, card numbers) is masked.
- **Centralize the deny-list** — maintain the forbidden field/pattern list in one place and apply it across every representation test, so a new endpoint is covered by policy, not by remembering.

The framing: leakage is a *security contract* of the API; encoding it as assertions turns "we hope nothing leaks" into a build-failing guard that survives refactors.

### 🔴 — extended

#### Q105. [Practical] A nightly E2E suite is 35% flaky and blocks releases. As the senior owner, what is your concrete remediation plan?

Treat suite flakiness as a reliability incident with a data-driven plan, not a clean-up chore:

1. **Quantify and triage** — instrument per-test flake rate and duration; rank tests by `flakes × gating-impact`. A 35% suite flake rate usually traces to a small set of offenders (Pareto) — fix those first for outsized gain.
2. **Stop the gate-bleed immediately** — move the flaky suite out of the *release-gating* path into a non-gating lane so it stops blocking deploys, **but** keep it running and tracked (quarantine-with-ownership, each flake a ticket with an SLA) — not silently disabled.
3. **Attack root causes by category** — the buckets are almost always: timing (`Thread.sleep` → Awaitility/fixed `Clock`), shared mutable data (→ self-provisioned unique data), un-reset doubles (→ WireMock reset), and environment coupling (→ Testcontainers for owned deps). Fix by category, not test-by-test.
4. **Push coverage down the pyramid** — for each flaky cross-service E2E, replace it with a **contract test + component test** pair that gives equivalent confidence deterministically; keep only a thin smoke layer.
5. **Ban blanket retries** — they mask real intermittent bugs (a 1-in-10 race is a production defect). Allow retries only with a tracking annotation that feeds the flake metric.
6. **Make health a tracked metric** — flake rate and p95 suite duration become dashboards with targets; a flaky test is a defect with an owner, reviewed like any other reliability metric.

The expert signal: you reframe flakiness from "annoying tests" to a **delivery-reliability problem** (teams ignoring red is the real damage), apply a reversible incremental plan, and leave behind measurement so it doesn't regress.

#### Q106. [Practical] You're standing up API testing for a brand-new microservice with no tests. What do you build first, and in what order, to maximize confidence per unit of effort?

Sequence by **confidence-per-effort**, building the foundations that make later tests cheap:

1. **Component test harness first** — start the service with downstreams virtualized (WireMock) and the real DB via Testcontainers, behind a reusable base spec / `RequestSpecification`. This harness is the highest-leverage investment: every later functional test rides on it.
2. **Happy-path + envelope smoke** — for each core endpoint, assert status + headers + a closed JSON-Schema on the body. This pins the response contract cheaply and catches the most embarrassing regressions.
3. **Negative/boundary/authorization** — the high-bug-density layer: malformed bodies (never 500), 401/403/404/409/422 paths, and at least one BOLA cross-subject test per object-returning endpoint.
4. **Contract tests at the seams** — generate consumer pacts (or provider contracts) for each cross-service edge and wire `can-i-deploy` so the service can deploy independently from day one.
5. **Spec-as-source-of-truth** — add OpenAPI request/response validation (swagger-request-validator filter) and breaking-change detection (`oasdiff`) in CI so the spec and implementation can't drift.
6. **A thin perf gate + a smoke E2E** — one k6 budget on the hottest endpoint and one cross-service smoke journey — deliberately *thin*.

The ordering rationale: invest in the **harness** (reusable infra) before volume of tests, pin **contracts** early so independence is possible, weight toward **negative/authz** where bugs cluster, and keep slow E2E/perf intentionally minimal. This builds a healthy pyramid from the start instead of an ice-cream cone you later have to unwind.

#### Q107. [Coding] Write a reusable JUnit 5 extension (or base class) that mints an auth token once and injects a configured REST Assured spec, so every test in a large suite is consistent.

```java
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import static io.restassured.RestAssured.given;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class ApiTestBase {

    @LocalServerPort int port;
    protected static String authToken;          // minted once per JVM
    protected RequestSpecification api;          // per-instance, port-bound

    @BeforeAll
    static void mintTokenOnce() {
        if (authToken == null) {
            authToken = given()
                .contentType("application/json")
                .body("{\"username\":\"ci\",\"password\":\"secret\"}")
            .when().post("http://localhost:" + System.getProperty("idp.port", "8080") + "/auth/login")
            .then().statusCode(200).extract().path("accessToken");
        }
    }

    protected RequestSpecification api() {
        if (api == null) {
            api = new RequestSpecBuilder()
                .setBaseUri("http://localhost").setPort(port).setBasePath("/v1")
                .addHeader("Authorization", "Bearer " + authToken)
                .setContentType("application/json")
                .build();
        }
        return api;
    }
}

// Concrete test inherits a consistent, authenticated, port-bound spec
class OrderApiTest extends ApiTestBase {
    @org.junit.jupiter.api.Test
    void listsOrders() {
        given().spec(api())
        .when().get("/orders")
        .then().statusCode(200);
    }
}
```

Centralizing the spec in a base class (or a `BeforeAllCallback` extension) means base URI, auth, content type, and filters are configured in **one** place: every team's tests look alike, the token is minted once rather than per test, and there's a single seam to add cross-cutting concerns (correlation-id filter, OpenAPI validation). This is the "paved road" pattern from Q78 expressed in code.

#### Q108. [Practical] How do you safely run a subset of API tests against a production-like or production environment, and what guardrails are mandatory?

Running tests against prod-like environments closes the fidelity gap pre-prod can't, but requires guardrails so probes never harm real users or data:

- **Read-mostly, sandboxed writes** — promote contract-derived, read-only journeys to **synthetic monitors**; for write paths, use **dedicated synthetic/test tenants** isolated from real customers, with their data tagged so it's excluded from analytics, billing, and ML training.
- **Idempotency on every probe** — synthetic writes carry idempotency keys so a retried or duplicated probe never double-acts (a retried "create order" must not create two).
- **Blast-radius limits** — feature flags and kill switches to disable probes instantly; canary on a small traffic % with automated rollback gated on error-rate/latency SLOs.
- **Traffic shadowing for behavioral diffing** — mirror real requests to the new version and **discard its responses**, diffing against current — zero user impact because the shadow output never returns to clients.
- **Data hygiene** — never use real PII in test fixtures against prod; generate synthetic data; ensure teardown or TTL so synthetic data doesn't accumulate.
- **Clear ownership and alerting** — a failed synthetic monitor pages someone; a broken probe must not itself become noise.

The relationship to the pyramid: production testing is **additive, not a replacement** — it covers the residual risk (real load, real data distributions, real dependency behavior) that is *impossible* to simulate offline, and its probes are frequently the *same contracts* already verified pre-prod, now running as live guardrails.

#### Q109. [Coding] Write a test that asserts trace-context propagation: the service forwards an incoming `traceparent` header to its downstream call.

```java
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import org.junit.jupiter.api.Test;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.restassured.RestAssured.given;

@WireMockTest
class TraceContextPropagationTest {

    @Test
    void incomingTraceparentIsPropagatedDownstream(WireMockRuntimeInfo wm) {
        // Downstream that our service-under-test will call
        stubFor(get(urlEqualTo("/inventory/book"))
            .willReturn(okJson("{\"available\":true}")));

        String traceparent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";

        // Call our service WITH a trace context, as a tracing proxy/gateway would
        given()
            .header("traceparent", traceparent)
            .pathParam("sku", "book")
        .when()
            .get("/products/{sku}/availability")
        .then()
            .statusCode(200);

        // Assert the downstream call carried a traceparent in the SAME trace
        // (same trace-id prefix; the span-id segment will differ on a new child span)
        verify(getRequestedFor(urlEqualTo("/inventory/book"))
            .withHeader("traceparent",
                matching("00-0af7651916cd43dd8448eb211c80319c-[0-9a-f]{16}-0[01]")));
    }
}
```

The matcher pins the **trace-id** (the second W3C `traceparent` segment, which must be preserved across the hop) while allowing the **span-id** segment to change (each hop creates a new child span). A broken instrumentation that drops or regenerates the trace-id would fail this — and would silently break distributed tracing in prod, where no user notices until you're mid-incident trying to follow a trace that dead-ends.

#### Q110. [Practical] An OpenAPI-driven fuzz run (Schemathesis) reports the API returns 500 on some valid-by-spec inputs. How do you triage, reproduce, and prevent recurrence?

A spec-valid input yielding 500 is a genuine input-handling defect (the contract promises it's acceptable, the implementation crashes). Workflow:

1. **Reproduce deterministically** — Schemathesis prints the exact failing case; pin it as a regression test (or use its `--hypothesis-seed` / generated `pytest` reproducer) so it's repeatable, then minimize the input to the smallest payload that still 500s.
2. **Classify the root cause** — the usual culprits: numeric/length overflow on an unexpected-but-valid size, null vs empty-string handling, unicode/emoji in a field the code assumes ASCII, an optional field the code dereferences, or an unhandled parse path. The 500's stack trace (in a test profile) localizes it.
3. **Fix at the boundary** — add input validation that converts the bad-but-spec-allowed input into a proper **4xx with Problem Details**, or tighten the **OpenAPI spec** if the input genuinely shouldn't be allowed (the spec was too permissive). Decide consciously which: loosen the code or tighten the contract.
4. **Add the case to the example-based suite** — convert the fuzz finding into a named negative test so it's guarded going forward; fuzzing finds *classes*, the regression test pins the *specific* one.
5. **Wire fuzzing into CI** — run Schemathesis (with the property "no valid input yields 5xx" and "every response conforms to schema / status is documented") on a schedule or per-PR on changed endpoints, so new endpoints get fuzzed automatically.

The expert framing: a 5xx on spec-valid input is the API **violating its own contract**; the durable fix is to close the spec↔implementation gap (validate or tighten the spec) and convert the property-test finding into a regression-guarded example so the class of bug can't silently return.

#### Q111. [Theory] How do you test compatibility of an event-driven API (Kafka/Avro topics) the way you'd contract-test an HTTP API, and what's different?

Event-driven services have a contract too — the **message schema on a topic** — but the producer and consumer are decoupled in *time* and don't do request/response, so the testing model shifts:

- **Schema registry + compatibility modes** — the canonical guard is a schema registry (Confluent/Apicurio) enforcing a compatibility policy (`BACKWARD`, `FORWARD`, `FULL`). `BACKWARD` (new schema can read old data) lets consumers upgrade first; `FORWARD` (old schema can read new data) lets producers upgrade first. CI rejects an incompatible schema change before it's published — this is the event-world equivalent of `oasdiff`/breaking-change detection.
- **Message contract testing** — Pact supports **message pacts**: the consumer asserts it can deserialize and process a message of a given shape; the producer verifies it emits messages satisfying that shape — without a running broker. This catches producer/consumer drift the same way HTTP pacts do, minus the synchronous interaction.
- **Round-trip / Testcontainers Kafka** — for integration confidence, produce with the real serializer against a Testcontainers Kafka + registry, consume, and assert key/headers/payload and that the registry accepted the schema.
- **What's different** — no status codes (assert on *deserialization success* and *processing side effect*, not HTTP); **ordering and idempotency** are first-class (duplicate/out-of-order delivery must be handled and tested); and the contract is the **schema + compatibility mode**, enforced at publish time rather than at call time.

The senior insight: for events you move the breaking-change gate to **schema-registry compatibility checks in CI** and use **message-pact** for cross-team drift, because there's no synchronous call to assert against — the contract lives in the serialized message and the registry's compatibility policy.

#### Q112. [Practical] Leadership asks you to prove the ROI of the API testing investment. What metrics do you present, and how do you avoid vanity metrics?

Frame ROI as **risk reduced and delivery accelerated per unit of testing cost**, using outcome metrics, not activity metrics:

- **Escaped-defect rate** — bugs found in production vs caught pre-prod, trended over time; the headline ROI number is "production incidents attributable to API-layer defects, down X%."
- **Change failure rate & MTTR** (DORA) — the % of deploys causing a failure and time to recover; good API/contract tests move both down and are directly tied to business impact.
- **Lead time / deploy frequency** (DORA) — contract testing + `can-i-deploy` removing the shared-staging bottleneck shows up as faster, more frequent independent deploys.
- **Suite health** — p95 suite duration and flake rate, because a fast, trustworthy suite is what *enables* the DORA gains; a slow/flaky suite is a cost, not an asset.
- **Coverage that means something** — contract coverage of cross-service edges and negative/authz coverage of endpoints, *not* raw line coverage (the classic vanity metric — 90% line coverage with only happy-path assertions proves little).

Avoiding vanity metrics:

- **Reject line/assertion *count* as the goal** — they reward volume, not confidence; a thousand redundant happy-path assertions are worse than ten targeted negative/authz tests.
- **Tie every metric to an outcome** — each testing metric should map to escaped defects, delivery speed, or incident reduction; if it doesn't, it's activity, not value.
- **Show a counterfactual** — "the idempotency/BOLA tests we added would have caught incidents A and B, which cost N hours and $M" makes ROI concrete.

The expert framing: measure **outcomes (escaped defects, DORA metrics, incident cost avoided)** enabled by a **healthy suite (fast, low-flake, contract/authz coverage)** — and explicitly disown coverage-percentage and test-count as proxies, because optimizing them produces a large, slow, low-value suite that *looks* productive while shipping bugs.

## ✅ Key Takeaways

- API tests sit in the middle of the pyramid: far faster and more stable than UI/E2E, and high-confidence because they exercise routing, serialization, auth, and persistence together.
- **REST Assured**'s given/when/then DSL with JsonPath, header, status, schema, and timing assertions is the JVM workhorse; **Postman + Newman** brings the same collections into CI.
- Always assert the **full envelope** — status, headers, and body shape (via **JSON Schema**) — not just the status code.
- **WireMock** virtualizes external dependencies so you can test edge cases, faults, latency, and stateful flows deterministically and offline.
- **Consumer-driven contract testing** (Pact / Spring Cloud Contract) replaces most cross-service integration tests and enables independent deployment via a broker and `can-i-deploy`.
- Weight suites toward **negative, boundary, and authorization** cases; test **auth** (OAuth2/JWT, 401 vs 403) and **idempotency** explicitly.
- Make performance budgets executable with **k6/Gatling/JMeter** thresholds wired into CI, and report **p95/p99**, not averages.
- Generate validation, fuzzing, and stubs from the **OpenAPI/Protobuf/SDL** spec so tests stay aligned with the contract.

## ⚠️ Common Pitfalls

- Asserting only on the status code while the body is wrong, empty, or leaking unexpected fields.
- Skipping negative/boundary/authorization tests — the happy path passes while real bugs (and BOLA/IDOR) ship.
- Using real third-party services in tests, making the suite slow, flaky, costly, and dependent on someone else's uptime.
- `Thread.sleep` for async/rate-limit coordination instead of Awaitility or a controllable `Clock`; relying on wall-clock time.
- Over-mocking your own components (mocking what you could cheaply run for real), so tests pass while production breaks.
- Shared mutable test data and non-unique values causing order-dependent, parallel-unsafe flakiness.
- Treating GraphQL like REST — expecting non-200 status for errors instead of asserting on the `errors` array.
- Letting WireMock stubs/journal leak between tests; not resetting state or checking `findAllUnmatchedRequests()`.
- An inverted pyramid of slow cross-service E2E tests in a shared environment, producing flaky pipelines teams stop trusting.
- Hand-maintaining schemas, stubs, and validation separately from the OpenAPI spec until they drift apart.

## 📚 Further Reading

- *REST Assured* documentation and wiki — rest-assured.io (given/when/then, JsonPath/GPath, schema validation, Spring MockMvc module).
- *WireMock* docs — wiremock.org (stubbing, request matching, scenarios, fault/latency injection, verification).
- *Postman Learning Center* and *Newman* CLI docs (collections, `pm.test`, CI reporters).
- *Pact* — docs.pact.io and *PactFlow*/Pact Broker (`can-i-deploy`, provider states); *Spring Cloud Contract* reference.
- *JSON Schema* — json-schema.org (Draft 2020-12); *atlassian/swagger-request-validator* for OpenAPI-driven validation.
- *OWASP API Security Top 10 (2023)* — owasp.org; *OWASP ZAP* for DAST.
- *k6* (grafana.com/docs/k6), *Gatling*, and *Apache JMeter* documentation for API performance testing; *Ghz* for gRPC load.
- *Schemathesis* and *Prism* for OpenAPI-driven fuzzing and mocking; *Buf*/`oasdiff` for breaking-change detection.
- *Testcontainers for Java* — for real Postgres/Kafka in integration tests.
- *Building Microservices* — Sam Newman (contract testing, consumer-driven contracts, testing in production).
