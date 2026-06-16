# REST API Design

A deep, interview-focused reference on designing RESTful HTTP APIs — covering REST constraints, resource modeling, HTTP semantics, versioning, pagination, caching, error design (RFC 7807/9457), idempotency, rate limiting, and the anti-patterns that separate a "JSON-over-HTTP" service from a genuinely RESTful one. Examples use Java (Spring Boot 3 / JAX-RS) and are current through 2026.

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

### Q1. [Theory] What is REST, and what are its core architectural constraints?

REST (REpresentational State Transfer) is an architectural style defined by Roy Fielding in his 2000 PhD dissertation. It is not a protocol or a standard — it is a set of constraints that, when applied to a distributed system, induce desirable properties like scalability, evolvability, and visibility. The six constraints are:

1. **Client–Server** — separation of concerns; UI/client evolves independently of data storage/server.
2. **Stateless** — each request carries all information needed to process it; the server keeps no client session state between requests. This enables horizontal scaling and resilience.
3. **Cacheable** — responses must declare themselves cacheable or not, so intermediaries and clients can reuse them.
4. **Uniform Interface** — the defining constraint: resources identified by URIs, manipulated through representations, self-descriptive messages, and HATEOAS (hypermedia as the engine of application state).
5. **Layered System** — clients can't tell whether they're talking to the origin server or an intermediary (proxy, gateway, CDN).
6. **Code-on-Demand** (optional) — servers can extend client functionality by sending executable code (e.g., JavaScript).

The "why" matters: statelessness trades server memory for slightly larger requests but buys you trivially horizontal scaling. Most APIs that call themselves "REST" actually only satisfy a subset (typically not HATEOAS), which is fine in practice but worth knowing in an interview.

### Q2. [Theory] What does it mean for an HTTP method to be "safe" and "idempotent"? Classify the common methods.

A **safe** method has no observable side effects on the server state — it is read-only from the client's intent (GET, HEAD, OPTIONS). A safe method must always be idempotent. An **idempotent** method produces the same server state when called once or N times with the same payload; the *response* may differ, but the resulting state does not.

```
Method   | Safe | Idempotent | Typical use
---------|------|------------|----------------------------------
GET      | yes  | yes        | Read a resource / collection
HEAD     | yes  | yes        | Like GET, headers only
OPTIONS  | yes  | yes        | Discover allowed methods / CORS
PUT      | no   | yes        | Replace resource at known URI
DELETE   | no   | yes        | Remove resource
POST     | no   | NO         | Create / non-idempotent actions
PATCH    | no   | NO*        | Partial update (*can be made idempotent)
```

Idempotency is the foundation of safe retries. Networks fail, timeouts happen, and clients retry. If `DELETE /orders/42` is called twice, the second call should still leave the order deleted (returning 404 or 204), not error catastrophically. `POST` is not idempotent because two identical `POST /orders` calls create two orders — which is why idempotency keys exist (covered later).

### Q3. [Theory] How do you choose the right HTTP status code? Give the families and key codes.

Status codes are grouped into five classes; the first digit signals the category, letting clients branch on `status / 100` without knowing every code.

```
1xx Informational  100 Continue, 101 Switching Protocols
2xx Success        200 OK, 201 Created, 202 Accepted, 204 No Content
3xx Redirection    301 Moved Permanently, 304 Not Modified, 307/308
4xx Client error   400 Bad Request, 401 Unauthorized, 403 Forbidden,
                   404 Not Found, 405 Method Not Allowed, 409 Conflict,
                   422 Unprocessable Content, 429 Too Many Requests
5xx Server error   500 Internal Server Error, 502 Bad Gateway,
                   503 Service Unavailable, 504 Gateway Timeout
```

Key distinctions interviewers probe: **201 Created** should include a `Location` header pointing to the new resource. **202 Accepted** means "queued for async processing, not done yet." **401 vs 403**: 401 = "I don't know who you are" (authentication missing/invalid), 403 = "I know who you are, but you can't do this" (authorization). **400 vs 422**: 400 = malformed syntax (bad JSON); 422 = syntactically valid but semantically invalid (e.g., `age: -5`). **409 Conflict** is for state conflicts like optimistic-lock failures or duplicate creation.

### Q4. [Practical] Design the resource URIs for a simple e-commerce "orders" API. What are the conventions?

Resources are **nouns**, not verbs; HTTP methods supply the verb. Collections are plural. Hierarchy expresses containment.

```
GET    /orders                 # list orders (filter/paginate via query)
POST   /orders                 # create an order
GET    /orders/{id}            # fetch one order
PUT    /orders/{id}            # full replace
PATCH  /orders/{id}            # partial update
DELETE /orders/{id}            # cancel/remove
GET    /orders/{id}/items      # sub-collection: items in an order
POST   /orders/{id}/items      # add an item
GET    /orders/{id}/items/{itemId}
```

Conventions: lowercase, hyphen-separated paths (`/purchase-orders`, not `/purchaseOrders` or `/purchase_orders`); no trailing slash; no file extensions in URIs (`.json`) — use content negotiation instead; query parameters for filtering/sorting/pagination, not for identifying resources. Avoid verbs in paths like `/getOrders` or `/createOrder` — those are the classic "REST as RPC" smell. For genuinely action-oriented operations that don't map to CRUD (e.g., `POST /orders/{id}/cancel`), a controller sub-resource is an accepted pragmatic exception.

### Q5. [Coding] Write a Spring Boot 3 REST controller for the `Order` resource with proper status codes.

**Problem:** Implement create, read, and delete endpoints returning correct status codes and a `Location` header on create.

```java
@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderService service;

    public OrderController(OrderService service) {
        this.service = service;
    }

    // POST /orders -> 201 Created + Location header
    @PostMapping
    public ResponseEntity<OrderResponse> create(@Valid @RequestBody CreateOrderRequest req) {
        Order created = service.create(req);
        URI location = ServletUriComponentsBuilder
                .fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.getId())
                .toUri();
        return ResponseEntity.created(location).body(OrderResponse.from(created));
    }

    // GET /orders/{id} -> 200 or 404 (via exception handler)
    @GetMapping("/{id}")
    public OrderResponse getOne(@PathVariable String id) {
        return OrderResponse.from(service.findById(id)); // throws NotFoundException -> 404
    }

    // DELETE /orders/{id} -> 204 No Content (idempotent)
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id); // idempotent: deleting an already-gone order is a no-op
        return ResponseEntity.noContent().build();
    }
}
```

**Edge cases:** invalid body → `@Valid` triggers `MethodArgumentNotValidException` → map to 400/422; unknown id on GET → 404; DELETE of nonexistent → still return 204 (idempotent) or 404 (both defensible — pick one and document it). **Complexity** is dominated by the service/DB call, typically O(1) for a keyed lookup.

### Q6. [Theory] What is content negotiation, and how does it work over HTTP?

Content negotiation lets a single resource have multiple representations (JSON, XML, CSV, different languages) and lets the client and server agree on which one to exchange. **Server-driven (proactive)** negotiation uses request headers: `Accept` (media type), `Accept-Language`, `Accept-Encoding`, `Accept-Charset`. The server picks the best match and echoes it via `Content-Type` and `Content-Language`, plus a `Vary` header so caches key correctly.

```
Request:  GET /orders/42
          Accept: application/json, application/xml;q=0.8
Response: 200 OK
          Content-Type: application/json
          Vary: Accept
```

The `q` values are relative quality weights (0–1). **Agent-driven** negotiation instead returns 300 Multiple Choices and lets the client pick from a list — rarely used. A subtle but important point: forgetting `Vary: Accept` causes a shared cache to serve a JSON response to a client that asked for XML. In practice most APIs commit to JSON-only and skip the complexity.

---

## 🟡 Intermediate (3–7 yrs)

### Q7. [Theory] Compare API versioning strategies. Which would you choose and why?

Four common strategies, each with trade-offs:

```
1. URI path        /v1/orders, /v2/orders
   + Trivially visible, cache-friendly, easy routing
   - "Versions the whole API"; pollutes URIs; technically a new
     resource identity for the same conceptual resource

2. Query param     /orders?version=2
   + Easy to default; same base URI
   - Easy to forget; messy caching; less common

3. Custom header   X-API-Version: 2
   + Clean URIs
   - Invisible in browser/logs; harder to test with curl;
     not cacheable without Vary

4. Media type      Accept: application/vnd.acme.order.v2+json
   (content negotiation / "true REST")
   + Versions per-representation, most HTTP-aligned
   - Complex; poor tooling/discoverability
```

In production I default to **URI path versioning** (`/v1`, `/v2`) for public APIs because it is the most operationally pragmatic — visible in logs, easy for API gateways to route, trivial for consumers to understand, and cache-friendly. Media-type versioning is "more correct" but the discoverability and tooling costs rarely pay off. The deeper principle: **prefer non-breaking, additive evolution over versioning entirely.** Add fields, never remove or repurpose them; make new fields optional; never change the meaning of an existing field. A new major version should be a last resort, and you should plan a deprecation window (e.g., 12 months) with `Deprecation` and `Sunset` headers (RFC 8594).

### Q8. [Theory] Offset-based vs cursor-based pagination — explain the trade-offs.

```
Offset:  GET /orders?limit=20&offset=40   (page 3)
Cursor:  GET /orders?limit=20&cursor=eyJpZCI6MTIzfQ==
```

**Offset/limit** is simple, supports random access ("jump to page 7"), and shows total counts — but it has two serious problems at scale. First, **performance**: `OFFSET 1000000 LIMIT 20` forces the database to scan and discard a million rows; cost grows linearly with offset. Second, **consistency**: if rows are inserted/deleted while a user pages, items shift — they see duplicates or skip records.

**Cursor (keyset) pagination** encodes a pointer to the last-seen item (typically the sort key + tiebreaker, e.g., `(created_at, id)`), and the query becomes `WHERE (created_at, id) < (?, ?) ORDER BY created_at DESC, id DESC LIMIT 20`. This uses the index directly — constant cost regardless of depth — and is stable under concurrent writes. The cost: no random access (no "page 7"), and cursors must be opaque/encoded so clients don't construct them. **Rule of thumb:** offset for small admin-style lists and UIs that need page numbers; cursor for large, high-throughput, or infinite-scroll feeds (Twitter/X, Stripe, Slack all use cursors).

### Q9. [Coding] Implement cursor-based pagination in Java with an opaque, Base64-encoded cursor.

**Problem:** Page a list of orders sorted by `(createdAt desc, id desc)` using a keyset cursor that is opaque to clients.

```java
public record Page<T>(List<T> items, String nextCursor, boolean hasMore) {}

record Cursor(Instant createdAt, String id) {
    String encode() {
        String raw = createdAt.toEpochMilli() + "|" + id;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
    static Cursor decode(String token) {
        String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
        String[] parts = raw.split("\\|", 2);
        if (parts.length != 2) throw new BadCursorException(token);
        return new Cursor(Instant.ofEpochMilli(Long.parseLong(parts[0])), parts[1]);
    }
}

public Page<OrderResponse> listOrders(String cursorToken, int limit) {
    int capped = Math.min(Math.max(limit, 1), 100); // clamp page size
    Cursor cursor = (cursorToken == null) ? null : Cursor.decode(cursorToken);

    // Fetch limit+1 to detect whether another page exists, without COUNT(*)
    List<Order> rows = repo.findKeyset(cursor, capped + 1);

    boolean hasMore = rows.size() > capped;
    List<Order> pageRows = hasMore ? rows.subList(0, capped) : rows;

    String next = null;
    if (hasMore) {
        Order last = pageRows.get(pageRows.size() - 1);
        next = new Cursor(last.getCreatedAt(), last.getId()).encode();
    }
    return new Page<>(pageRows.stream().map(OrderResponse::from).toList(), next, hasMore);
}
```

The repository query (JPA-style):

```sql
SELECT * FROM orders
WHERE (:cursorCreatedAt IS NULL)
   OR (created_at, id) < (:cursorCreatedAt, :cursorId)
ORDER BY created_at DESC, id DESC
LIMIT :limitPlusOne
```

**Why `limit+1`?** It avoids a separate `COUNT(*)` to determine `hasMore`. **Edge cases:** malformed cursor → 400; the `(created_at, id)` tiebreaker is essential because timestamps collide; clamp `limit` to prevent denial-of-service via `limit=1000000`. **Time complexity:** O(log n + page) using the composite index — independent of how deep you page, versus offset's O(offset + page).

### Q10. [Practical] How do you design filtering and sorting for a collection endpoint?

Filtering and sorting live in the query string. A clean, conventional design:

```
GET /orders?status=SHIPPED&minTotal=100&createdAfter=2026-01-01
           &sort=-createdAt,total          # '-' prefix = descending
           &fields=id,status,total          # sparse fieldset projection
```

Approaches range in power and complexity. Simplest is **field equality** (`status=SHIPPED`). For ranges, use suffixed params (`minTotal`, `createdAfter`) or operator syntax (`total[gte]=100`). For very rich querying, some APIs adopt **RSQL/FIQL** (`filter=status==SHIPPED;total=gt=100`) or expose **OData** — powerful but a steep learning curve and a security minefield.

In production I'd do this:
- **Whitelist** sortable and filterable fields server-side. Never pass raw query params into SQL — that is both an injection risk and lets clients sort on unindexed columns, causing table scans.
- Validate and clamp values; reject unknown filter keys with 400.
- Make `sort` support multi-field with a `-` descending convention.
- Document `fields=` sparse fieldsets to let clients reduce payload size.

The trade-off is expressiveness vs. attack surface and DB load. A generic query language pushes load and risk onto your database; a curated set of filters keeps you in control. I lean toward curated filters backed by indexes, escalating to RSQL only when consumers demonstrably need ad-hoc querying.

### Q11. [Coding] Build a safe sort parser that whitelists allowed fields.

**Problem:** Parse `sort=-createdAt,total` into a Spring Data `Sort`, rejecting any field not in an allow-list (prevents injection and unindexed scans).

```java
public final class SortParser {
    private final Set<String> allowed;

    public SortParser(Set<String> allowed) {
        this.allowed = Set.copyOf(allowed);
    }

    public Sort parse(String sortParam) {
        if (sortParam == null || sortParam.isBlank()) {
            return Sort.by(Sort.Direction.DESC, "createdAt"); // sensible default
        }
        List<Sort.Order> orders = new ArrayList<>();
        for (String token : sortParam.split(",")) {
            token = token.trim();
            if (token.isEmpty()) continue;
            boolean desc = token.startsWith("-");
            String field = desc ? token.substring(1) : token;
            if (!allowed.contains(field)) {
                throw new BadRequestException("Unsortable field: " + field);
            }
            orders.add(new Sort.Order(
                    desc ? Sort.Direction.DESC : Sort.Direction.ASC, field));
        }
        return Sort.by(orders);
    }
}
// Usage: new SortParser(Set.of("createdAt", "total", "status")).parse(sortParam);
```

**Edge cases:** empty/blank → default sort; duplicate fields → keep first or 400; unknown field → 400 with a clear message; a leading `+` should be tolerated as ascending. **Why the allow-list matters (security):** without it, a malicious `sort=(SELECT...)` or sorting by a huge unindexed text column becomes a cheap DoS or injection vector. **Complexity:** O(k) in the number of sort tokens.

### Q12. [Theory] Explain RFC 7807 / RFC 9457 "problem+json". Why standardize error responses?

RFC 7807 (obsoleted and updated by **RFC 9457** in 2023) defines a standard machine-readable error format: `application/problem+json`. Instead of every API inventing its own error shape, you return a predictable structure:

```json
HTTP/1.1 422 Unprocessable Content
Content-Type: application/problem+json

{
  "type": "https://api.acme.com/problems/insufficient-funds",
  "title": "Insufficient funds",
  "status": 422,
  "detail": "Your balance is 30 but the order total is 50.",
  "instance": "/orders/42",
  "balance": 30,
  "orderTotal": 50,
  "traceId": "abc-123"
}
```

Fields: `type` (a URI identifying the problem class — the primary key for clients to branch on; defaults to `about:blank`), `title` (human, stable summary), `status` (mirrors the HTTP code), `detail` (human, instance-specific), `instance` (URI of the specific occurrence). You can add **extension members** (`balance`, `traceId`). Why standardize? Clients can program against a single, documented shape; you can attach a correlation/`traceId` for support; and intermediaries understand the media type. Crucially, branch logic should key off `type`, **not** the human-readable `detail`, which may be localized or reworded. Spring Boot 3 supports this natively via `ProblemDetail` and `@ExceptionHandler`.

### Q13. [Coding] Implement a global RFC 9457 exception handler in Spring Boot 3.

**Problem:** Map domain exceptions to `application/problem+json` with the standard fields plus a trace id.

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail handleNotFound(NotFoundException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setType(URI.create("https://api.acme.com/problems/not-found"));
        pd.setTitle("Resource not found");
        pd.setProperty("traceId", MDC.get("traceId"));
        return pd;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, "Validation failed");
        pd.setType(URI.create("https://api.acme.com/problems/validation"));
        pd.setTitle("Validation error");
        // RFC 9457 extension member: a list of field-level errors
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> Map.of("field", fe.getField(),
                                  "message", Objects.toString(fe.getDefaultMessage(), "invalid")))
                .toList();
        pd.setProperty("errors", errors);
        pd.setProperty("traceId", MDC.get("traceId"));
        return pd;
    }
}
```

`ProblemDetail` (since Spring 6 / Boot 3) auto-serializes to `application/problem+json`. **Edge cases:** never leak stack traces or SQL in `detail` for 5xx — return a generic message plus the `traceId` so support can correlate logs (security: information disclosure). Use 422 for semantic validation, 400 for unparseable bodies. **Complexity** is negligible — this is presentation logic.

### Q14. [Theory] What is the Richardson Maturity Model, and where does HATEOAS fit?

Leonard Richardson's model grades API "RESTfulness" in four levels:

```
Level 0: The Swamp of POX  — single URI, single verb (POST), RPC tunneled
         over HTTP. (SOAP, old XML-RPC.)
Level 1: Resources         — many URIs, still mostly one verb.
         /orders/42 exists, but you POST everything.
Level 2: HTTP Verbs        — proper use of GET/POST/PUT/DELETE + status
         codes. *Most "REST" APIs live here.*
Level 3: Hypermedia (HATEOAS) — responses include links describing what
         actions are available next.
```

**HATEOAS** (Hypermedia As The Engine Of Application State) is Fielding's litmus test for "true REST": the client navigates the API by following links the server provides, rather than hardcoding URI templates.

```json
{
  "id": "42", "status": "PENDING", "total": 50,
  "_links": {
    "self":   { "href": "/orders/42" },
    "cancel": { "href": "/orders/42/cancel", "method": "POST" },
    "pay":    { "href": "/orders/42/payment", "method": "POST" }
  }
}
```

The promise is decoupling — the server can move URIs and change available actions (no `cancel` link once shipped) without breaking clients. The reality: HATEOAS adoption is low because most clients are written by humans who read docs and hardcode paths anyway, and the link-following machinery adds complexity for limited payoff. Fielding insisted Level 2 isn't truly REST, but pragmatically the industry treats Level 2 + OpenAPI as "good enough." Worth knowing both the ideal and why teams stop at Level 2.

### Q15. [Practical] A client reports they're double-charging customers on payment retries. How do you fix it with idempotency keys?

**Scenario:** A mobile client POSTs `/payments`. The network times out *after* the server charged the card but *before* the response arrives. The client retries → second charge. `POST` is not idempotent, so HTTP alone won't save you.

**Approach — idempotency keys (the Stripe model):**

```
POST /payments
Idempotency-Key: 7f3c9a2e-... (client-generated UUID, stable across retries)
{ "amount": 50, "currency": "USD", "source": "card_x" }
```

Server-side algorithm:

```
1. Read the Idempotency-Key header. Reject if missing on mutating endpoints
   that require it (400).
2. Look up the key in an idempotency store (keyed by key + endpoint + caller).
3a. Not seen  -> acquire a lock on the key, process the charge, store
    (key -> status, response body, status code) with a TTL (e.g., 24h),
    release lock, return the response.
3b. Seen + completed -> return the STORED response verbatim. No re-charge.
3c. Seen + in-progress -> return 409 Conflict (or 425 Too Early); client
    should retry later.
```

**Trade-offs / production details:** store the key with a TTL so it doesn't grow unbounded; hash and persist the *request fingerprint* so reusing a key with a *different* body returns 422 (the client made a mistake); scope keys per-account to prevent cross-tenant collisions; use a transactional or atomic store (a DB row with a unique constraint, or Redis `SET NX`). This is exactly what Stripe, Adyen, and PayPal do. The key principle: **make POST safely retryable by making it conditionally idempotent at the application layer.**

### Q16. [Coding] Implement an idempotency-key filter using a unique constraint.

**Problem:** Ensure a `POST` with a repeated `Idempotency-Key` returns the original response instead of re-executing.

```java
@Transactional
public PaymentResponse charge(String idempotencyKey, ChargeRequest req, String accountId) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
        throw new BadRequestException("Idempotency-Key header required");
    }
    String fingerprint = sha256(accountId + "|" + req.canonicalJson());

    Optional<IdempotencyRecord> existing =
            repo.findByKeyAndAccount(idempotencyKey, accountId);
    if (existing.isPresent()) {
        IdempotencyRecord rec = existing.get();
        if (!rec.getFingerprint().equals(fingerprint)) {
            // Same key, different payload = client bug
            throw new UnprocessableException("Idempotency-Key reused with a different request");
        }
        return deserialize(rec.getResponseBody()); // replay stored result
    }

    try {
        // Unique constraint on (key, account) makes this the concurrency gate
        repo.insertPending(idempotencyKey, accountId, fingerprint);
    } catch (DataIntegrityViolationException race) {
        // Concurrent in-flight request won the insert
        throw new ConflictException("Request already in progress");
    }

    PaymentResponse resp = paymentGateway.charge(req); // the real side effect
    repo.complete(idempotencyKey, accountId, serialize(resp));
    return resp;
}
```

**Edge cases:** concurrent duplicates race on the unique constraint — the loser gets 409; a crash after charging but before `complete()` requires a reconciliation job (or do the gateway call idempotently too, passing the same key downstream); set a TTL eviction. **Complexity:** O(1) lookups/inserts on the indexed key. **Security:** keys must be scoped per account so one tenant cannot probe or hijack another's request.

---

## 🟠 Advanced (8–12 yrs)

### Q17. [Theory] Explain HTTP caching with ETags and Cache-Control. How do conditional requests work?

Caching is a first-class REST constraint, and getting it right reduces both latency and load dramatically. Two complementary mechanisms:

**`Cache-Control`** governs *freshness* — how long a response may be reused without revalidation:

```
Cache-Control: public, max-age=300, s-maxage=600, stale-while-revalidate=60
Cache-Control: private, no-cache          # must revalidate every time
Cache-Control: no-store                   # never cache (sensitive data)
```

`max-age` is for client caches, `s-maxage` for shared caches/CDNs; `private` forbids shared caches; `no-store` is for sensitive payloads.

**Validators (`ETag` / `Last-Modified`)** govern *revalidation* — checking whether a stale copy is still good without re-downloading the body:

```
1. Server: 200 OK, ETag: "v7", body
2. Client caches it. Later, conditional GET:
       GET /orders/42
       If-None-Match: "v7"
3a. Unchanged -> 304 Not Modified (no body) -> client reuses cache. Cheap.
3b. Changed   -> 200 OK, ETag: "v8", new body.
```

ETags also enable **optimistic concurrency** for writes via `If-Match`:

```
PUT /orders/42
If-Match: "v7"
   -> 200 if current ETag is "v7"
   -> 412 Precondition Failed if it changed (someone else updated first)
```

This prevents lost updates without database locks. **Strong vs weak ETags:** strong (`"v7"`) means byte-identical; weak (`W/"v7"`) means semantically equivalent. Use weak ETags when responses differ only in formatting/compression.

### Q18. [Coding] Implement ETag-based optimistic concurrency for updates.

**Problem:** Reject a `PUT` when the resource changed since the client last read it, using `If-Match`.

```java
@PutMapping("/orders/{id}")
public ResponseEntity<OrderResponse> update(
        @PathVariable String id,
        @RequestHeader(value = "If-Match", required = false) String ifMatch,
        @Valid @RequestBody UpdateOrderRequest req) {

    Order current = service.findById(id);            // 404 if missing
    String currentEtag = "\"" + current.getVersion() + "\""; // version from @Version column

    if (ifMatch == null) {
        // Require the precondition on mutating writes to avoid lost updates
        throw new PreconditionRequiredException("If-Match header required"); // 428
    }
    if (!ifMatch.equals(currentEtag)) {
        throw new PreconditionFailedException("Resource was modified");       // 412
    }

    Order saved = service.update(id, req);            // @Version bump -> new etag
    String newEtag = "\"" + saved.getVersion() + "\"";
    return ResponseEntity.ok().eTag(newEtag).body(OrderResponse.from(saved));
}

// Read sets the ETag so the client can send it back later
@GetMapping("/orders/{id}")
public ResponseEntity<OrderResponse> get(@PathVariable String id) {
    Order o = service.findById(id);
    return ResponseEntity.ok()
            .eTag("\"" + o.getVersion() + "\"")
            .cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePrivate())
            .body(OrderResponse.from(o));
}
```

Pair this with a JPA `@Version` column so the DB also enforces optimistic locking (`OptimisticLockException` → 409/412). **Edge cases:** missing `If-Match` → 428 Precondition Required; stale ETag → 412; concurrent updates → DB version conflict. **Complexity:** O(1). This is the canonical pattern for "last write wins is unacceptable" scenarios — inventory, account balances, document editing.

### Q19. [Theory] How do you design rate limiting for a public API? Compare algorithms.

Rate limiting protects against abuse, ensures fair multi-tenant use, and caps cost. Four classic algorithms:

```
Algorithm        | Burst handling | Memory | Notes
-----------------|----------------|--------|----------------------------------
Fixed window     | poor (edges)   | tiny   | 2x burst at window boundary
Sliding window   | good           | low    | log or weighted-counter variants
Token bucket     | allows bursts  | tiny   | refill rate + capacity; most common
Leaky bucket     | smooths output | tiny   | constant drain; queue semantics
```

**Token bucket** is the most widely used (AWS, Stripe, GitHub): tokens refill at a steady rate up to a capacity; each request consumes one; empty bucket → reject. It permits short bursts (good UX) while bounding sustained throughput. The **fixed-window** counter is simplest but allows a 2x burst across the boundary (100 requests at 00:59 and 100 at 01:00 = 200 in two seconds). **Sliding-window log** is accurate but memory-heavy; the **sliding-window counter** approximates it cheaply.

Implementation realities: enforce limits at the **API gateway** (or a Redis-backed counter for distributed correctness), key by API key/user/IP/tenant, and **communicate limits to clients** via headers:

```
429 Too Many Requests
Retry-After: 30
RateLimit-Limit: 100
RateLimit-Remaining: 0
RateLimit-Reset: 30          # per the IETF RateLimit-Headers draft
```

Tier limits per plan; consider separate buckets for read vs write; and combine with **concurrency limits** and **load shedding** (503 + `Retry-After`) for overload protection. Always return `429` (not 503) for quota and include `Retry-After` so well-behaved clients back off deterministically.

### Q20. [Coding] Implement a token-bucket rate limiter (thread-safe, in-memory).

**Problem:** Allow up to `capacity` requests with a steady refill of `refillPerSec` tokens; reject when empty.

```java
public final class TokenBucket {
    private final long capacity;
    private final double refillPerSec;
    private double tokens;
    private long lastRefillNanos;

    public TokenBucket(long capacity, double refillPerSec) {
        this.capacity = capacity;
        this.refillPerSec = refillPerSec;
        this.tokens = capacity;
        this.lastRefillNanos = System.nanoTime();
    }

    public synchronized boolean tryConsume() {
        refill();
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;          // allowed
        }
        return false;             // rate limited -> caller returns 429
    }

    private void refill() {
        long now = System.nanoTime();
        double elapsedSec = (now - lastRefillNanos) / 1_000_000_000.0;
        if (elapsedSec > 0) {
            tokens = Math.min(capacity, tokens + elapsedSec * refillPerSec);
            lastRefillNanos = now;
        }
    }
}
```

For a distributed system, replace the in-memory state with a Redis Lua script (atomic read-modify-write of `tokens` + `lastRefill`) keyed by the client id, so all gateway instances share one bucket. **Edge cases:** clock skew (use a monotonic clock — `nanoTime`, not `currentTimeMillis`); thundering herd at refill; per-key buckets must be evicted (LRU/TTL) to bound memory. **Time/Space:** O(1) per check, O(1) state per key. **In production**, prefer a battle-tested library (Bucket4j) or the gateway's native limiter over hand-rolling.

### Q21. [Practical] How do you design bulk/batch operations RESTfully without breaking REST semantics?

**Scenario:** A client needs to update 10,000 orders. Doing 10,000 individual `PUT`s is chatty and slow; one giant transaction risks all-or-nothing failures and timeouts.

**Options and trade-offs:**

```
1. Batch endpoint (sync, partial success)
   POST /orders/bulk
   { "operations": [ {"method":"PATCH","id":"1","body":{...}}, ... ] }
   -> 207 Multi-Status with a per-item result array

2. Async job resource (best for large/long jobs)
   POST /bulk-jobs   -> 202 Accepted, Location: /bulk-jobs/{jobId}
   GET  /bulk-jobs/{jobId} -> { status: RUNNING|DONE, processed, failed, errors[] }

3. Collection-level operations with filters
   PATCH /orders?status=PENDING  { "status": "CANCELLED" }   (use with care)
```

What I'd do in production: for **small batches** (tens to low hundreds), a synchronous `POST /orders/bulk` returning **207 Multi-Status** with per-item status codes — this gives partial success, which is usually what callers want (don't fail all 200 because one was invalid). For **large jobs**, the **async job pattern** — return `202 Accepted` with a job resource the client polls (or gets a webhook). Critical decisions: **partial vs atomic** semantics (document which, and consider an `atomic: true` flag); bound batch size (reject >N with 413/400); make the batch itself idempotent via an idempotency key; and report errors per item with stable indices. The anti-pattern is overloading a single `POST /orders` to accept either one or many — keep the bulk endpoint distinct and explicit.

### Q22. [Theory] What is the difference between PUT and PATCH, and how do you do PATCH correctly (JSON Patch vs JSON Merge Patch)?

`PUT` replaces the *entire* resource — semantically "here is the complete new state." Omitting a field means "set it to null/absent." `PUT` is idempotent. `PATCH` applies a *partial* modification, and the request body is a **description of changes**, not a partial resource (a subtle but tested point). Two standardized PATCH formats:

**JSON Merge Patch (RFC 7396)** — `application/merge-patch+json`. Intuitive: present keys are set, `null` means delete, absent means leave unchanged.

```
PATCH /orders/42
Content-Type: application/merge-patch+json
{ "status": "SHIPPED", "couponCode": null }   # set status, remove coupon
```

Limitation: you can't set a field *to* null (null always means delete), and arrays can only be replaced wholesale, not edited element-wise.

**JSON Patch (RFC 6902)** — `application/json-patch+json`. A sequence of explicit operations; more powerful, supports array index ops and `test` for optimistic concurrency, but verbose and harder to write.

```
PATCH /orders/42
Content-Type: application/json-patch+json
[
  { "op": "test",    "path": "/version", "value": 7 },
  { "op": "replace", "path": "/status",  "value": "SHIPPED" },
  { "op": "remove",  "path": "/couponCode" }
]
```

**Idempotency caveat:** PATCH is *not* inherently idempotent (a JSON Patch `add` to an array appends each time), but a JSON Merge Patch typically *is*. For most CRUD APIs, JSON Merge Patch is the pragmatic choice; reach for JSON Patch when you need precise array edits or built-in `test`-based concurrency.

### Q23. [Practical] A team's "REST" API is really RPC over HTTP (`/api/doStuff`). What concrete refactor do you propose and how do you migrate safely?

**Diagnosis (the smells):** verbs in URIs (`/getUserOrders`, `/cancelOrder`); everything is `POST`; 200 OK returned even for errors with `{"success": false}` in the body; no use of status codes, caching, or content negotiation; one mega-endpoint. This is Richardson Level 0–1.

**Target design:**

```
Before                          After
POST /getUserOrders        ->   GET    /users/{id}/orders
POST /createOrder          ->   POST   /orders            (201 + Location)
POST /cancelOrder          ->   POST   /orders/{id}/cancel  (controller resource)
POST /updateOrderStatus    ->   PATCH  /orders/{id}
200 {success:false}        ->   4xx/5xx + problem+json
```

**Migration strategy (safe, incremental):**
1. **Don't break existing clients.** Stand up the new RESTful endpoints alongside the old RPC ones (parallel run). The old API keeps working.
2. **Strangler-fig pattern** behind an API gateway: route new paths to new handlers, leave old paths untouched. Share the service layer so business logic isn't duplicated.
3. Publish an **OpenAPI 3.1 spec** for the new API; generate client SDKs.
4. **Instrument** old-endpoint usage; identify and migrate top consumers.
5. Mark old endpoints deprecated with `Deprecation`/`Sunset` headers and a documented end-of-life date.
6. Decommission only after usage hits zero (or your contractual deprecation window expires).

**Trade-offs to call out:** there's real cost and risk in a rewrite, and "RPC over HTTP" works fine functionally — so justify the change by concrete pain (no caching, opaque errors, poor client ergonomics, inability to evolve). Don't refactor for purity alone. The behavioral signal interviewers want: pragmatism plus a safe, measured rollout, not a big-bang rewrite.

---

## 🔴 Expert (15+ yrs)

### Q24. [Theory] When should you NOT use REST? Compare REST vs GraphQL vs gRPC and justify a choice.

A staff engineer's job is choosing the right tool, not defending REST dogmatically.

```
                | REST/HTTP        | GraphQL            | gRPC
----------------|------------------|--------------------|--------------------
Transport       | HTTP/1.1, HTTP/2 | HTTP (single POST) | HTTP/2 (binary)
Payload         | JSON (usually)   | JSON               | Protobuf (binary)
Schema/contract | OpenAPI (opt.)   | SDL (strong)       | .proto (strong)
Over/under-fetch| common           | client picks fields| fixed messages
Caching         | excellent (HTTP) | hard (POST)        | none at HTTP layer
Streaming       | SSE/limited      | subscriptions      | first-class bidi
Browser-native  | yes              | yes                | needs grpc-web proxy
Best for        | public APIs,     | aggregating many   | internal low-latency
                | CRUD, caching    | sources, mobile BFF| service-to-service
```

**Decision heuristics:** Use **REST** for public-facing APIs (broad client compatibility, HTTP caching, simplicity, longevity) and resource-oriented CRUD. Use **GraphQL** when diverse clients (mobile vs web) need different field sets and you're aggregating many backends — it solves over/under-fetching but sacrifices HTTP caching, complicates rate limiting (query cost analysis), and exposes N+1 risk. Use **gRPC** for internal, high-throughput, low-latency service-to-service communication where you control both ends — Protobuf is compact and fast, but it's poor for browsers and human debugging. A mature architecture often uses all three: gRPC east-west between services, REST/GraphQL north-south to clients. The trap is treating this as ideology; tie the decision to client diversity, caching needs, latency budgets, and team familiarity.

### Q25. [Practical] You own a public API used by thousands of integrators. How do you evolve the contract without breaking them — and what governance do you put in place?

This is an organizational problem as much as a technical one. Principles and machinery:

**Compatibility discipline (the core rule):**
- **Additive, non-breaking changes by default** — new optional fields, new endpoints, new optional params. Existing consumers must keep working when you add to a response.
- **Tolerant Reader / Postel's Law** — encourage clients to ignore unknown fields; you enforce the reverse by never removing or repurposing fields.
- A breaking change forces a new **major version**, which is expensive — avoid it.

**Governance and tooling:**

```
- Spec-first: OpenAPI 3.1 is the source of truth; review API changes in PRs.
- Automated contract diffing in CI (e.g., openapi-diff / oasdiff) that FAILS
  the build on a breaking change to a published version.
- Consumer-driven contract tests (Pact) so provider changes can't silently
  break known consumers.
- Backward-compat test suite frozen per version.
- Deprecation policy: Deprecation + Sunset headers (RFC 8594), a documented
  window (e.g., 12 months), changelog, and proactive outreach to top callers.
- Versioning + canary: roll new behavior behind a version or feature flag,
  dogfood, then GA.
```

**The lived reality:** Stripe is the canonical case study — it versions by *date* (`Stripe-Version: 2024-XX-XX`), pins each account to the version it integrated against, and maintains compatibility shims that transform new internal models back into old response shapes per account. This let them ship hundreds of changes over a decade while almost never breaking integrators. The lesson: backward compatibility is a *product feature* with real engineering investment, not an afterthought — and trust, once broken by a surprise breaking change, is extremely costly to rebuild.

### Q26. [Behavioral] Tell me about a time you had to push back on an API design decision. How did you handle the disagreement?

Use a STAR structure and make the trade-off reasoning visible. A strong answer pattern:

- **Situation:** A team wanted to ship a "convenience" endpoint that returned a deeply nested, denormalized payload combining orders, customers, and payments in one call to save the mobile team round-trips.
- **Task:** As the API owner I was concerned about coupling, cache-ability, and the fact that this hard-wired one client's screen layout into the public contract.
- **Action:** Rather than blocking it outright, I quantified the cost: the payload couldn't be cached per-resource, it would force a fan-out join on every call (latency + DB load), and it would make every future order-schema change a breaking change for that endpoint. I proposed two alternatives — sparse fieldsets/expansion params (`?expand=customer,payments`) on existing resources, or a dedicated mobile BFF (GraphQL) owned by the mobile team — and prototyped the expansion approach to show it met their latency goal.
- **Result:** We adopted resource expansion, the mobile team got their single round-trip, and the core API stayed cacheable and evolvable.

The meta-point interviewers look for: you disagreed with *data and alternatives*, not authority; you separated the legitimate need (fewer round-trips) from the proposed-but-flawed solution; and you let the prototype, not seniority, settle it. Strong opinions, loosely held — and never letting "REST purity" override a real user need without offering a workable path.

### Q27. [Theory] What are the deep security considerations specific to REST API design?

Beyond generic web security, REST APIs have a characteristic threat surface that maps closely to the OWASP API Security Top 10 (2023):

```
1. BOLA / IDOR (API1) — /orders/42 where you authorize the user is logged in
   but NOT that they OWN order 42. The #1 API vuln. Enforce object-level
   authZ on EVERY resource access, server-side, never trust client-supplied ids.
2. Broken authentication (API2) — weak/expired tokens, JWT alg=none, no rotation.
3. Object property authZ / mass assignment (API3) — PATCH/PUT binding straight
   to the entity lets a caller set isAdmin=true or balance=999. Use explicit
   DTOs / field allow-lists, never bind to the persistence entity.
4. Unrestricted resource consumption (API4) — no pagination caps, no rate
   limits, expensive filters -> DoS / cost-amplification.
5. Function-level authZ (API5) — admin endpoints reachable by non-admins.
6. SSRF (API7) — endpoints that fetch a client-supplied URL.
```

Additional REST-specific controls: **never put secrets or PII in URIs** (they land in logs, proxies, browser history, `Referer` headers) — use headers/body; enforce **TLS everywhere** and HSTS; validate `Content-Type` to prevent content-type confusion; cap request body size; set `Cache-Control: no-store` on sensitive responses so they aren't cached by intermediaries; and avoid leaking internal detail in error bodies (return `traceId`, not stack traces). The defining REST-specific failure is **BOLA/IDOR**: predictable resource ids plus missing per-object authorization. The fix is mandatory, centralized object-level authorization checks — and where ids must not be guessable, use UUIDs/opaque ids (defense in depth, not a substitute for authZ).

### Q28. [Practical] How would you design a REST API for very high read throughput (millions of RPS), e.g., a product catalog at a large retailer?

**Scenario:** Read-dominant, latency-sensitive, global audience — think a product detail API behind a retailer's storefront on a peak shopping day.

**Layered strategy (cache as close to the client as possible):**

```
Client ── CDN (edge) ── API Gateway ── Service ── Cache (Redis) ── DB
   |          |             |              |            |
 max-age   cache GETs   rate-limit/    ETag/304    read replicas
 + SWR     by ETag      authz/route   revalidate   + materialized views
```

Concrete techniques and trade-offs:
- **HTTP caching is the highest-leverage lever.** Set `Cache-Control: public, max-age=…, stale-while-revalidate=…` on cacheable GETs and let a CDN absorb the vast majority of reads. Catalog data tolerates seconds-to-minutes of staleness, so cache aggressively. Use **ETags** so revalidation is a cheap 304.
- **Cache key hygiene:** set `Vary` correctly; strip irrelevant query params; avoid per-user variation on shared resources (keep personalization on a separate endpoint).
- **Read replicas + materialized read models** (CQRS): serve reads from denormalized projections, accept eventual consistency, and isolate write load.
- **Pagination must be cursor-based** to stay O(1) at depth; **cap page sizes**.
- **Graceful degradation:** load shedding (503 + `Retry-After`), serve stale on origin failure (`stale-if-error`), and circuit breakers to dependencies.
- **Compression** (gzip/br), HTTP/2 or HTTP/3 for multiplexing, and connection reuse.

**The key insight:** for read-heavy REST, the database is rarely the constraint if you exploit HTTP's caching model fully — a properly cached catalog serves 95%+ of traffic from the edge, and origin only sees cache fills and revalidations. The hard part is **cache invalidation**: use short TTLs plus event-driven purges (publish a purge to the CDN/Redis when a product changes) rather than long TTLs you can't bust. This is exactly how large retailers and media sites survive traffic spikes — REST's cacheability constraint is the feature, not an afterthought.

### Q29. [Theory] What does "self-descriptive messages" mean in REST, and why does it matter for system evolvability over a 10-year horizon?

Self-descriptive messaging is one half of the uniform-interface constraint: every message carries enough metadata (standard HTTP methods, status codes, media types, caching directives, content negotiation headers) for any recipient — client *or* intermediary — to understand and process it **without out-of-band knowledge**. A proxy can cache a `GET` it has never seen because `Cache-Control` and `ETag` tell it how; a gateway can retry a `PUT` because the method's idempotency is standardized; a client can branch on `429` without bespoke logic.

Why it matters long-term: it is what enables the **layered system** to work — you can insert CDNs, caches, gateways, WAFs, and load balancers transparently, because they reason about standard HTTP semantics rather than your specific payload. APIs that violate this (tunneling everything through `POST`, returning 200 with error bodies, ignoring status codes) forfeit the entire HTTP intermediary ecosystem; every cache, retry, and circuit-breaker decision must then be reimplemented in application code. Over a decade, the self-descriptive API accrues compounding leverage — infrastructure evolves around it for free — while the RPC-over-HTTP API ossifies. This is the practical payoff of Fielding's constraints: not academic purity, but the ability to evolve both the system *and* the infrastructure around it independently. It is also why "use HTTP correctly" beats "invent a clever framework" almost every time.

---

## 🧩 Extended Questions — Set 1: Deep Theory & Internals

### 🟢 Basic — extended

#### Q30. [Theory] What is the difference between a "resource" and a "representation" in REST, and why does the distinction matter?

A **resource** is the abstract concept — the thing the URI identifies. A **representation** is a concrete serialization of that resource's state at a point in time, in a specific media type. Fielding's wording is precise: a URI names a resource, and what flows over the wire is *a representation of that resource*, never the resource itself. `GET /orders/42` might return JSON today and XML tomorrow (via content negotiation), or a full vs. summary view — same resource, different representations.

This distinction is not academic; it has concrete design consequences. First, it justifies **content negotiation**: one URI, many representations, chosen by `Accept`. Second, it explains why **caching with `Vary`** is necessary — caches key on the representation-determining headers, because a single URI may map to several cached bodies. Third, it underpins **HATEOAS**: the representation carries links and state, and the client transitions application state by acting on representations the server hands it.

```
Resource (abstract)            Representations (concrete)
   "order 42"        ──┬──>  application/json   { "id":"42", ... }
   URI: /orders/42     ├──>  application/xml    <order id="42">...</order>
                       └──>  text/csv           id,status,total\n42,...
```

The practical upshot: never conflate "the database row" with "the resource." A resource can be a computed view, an aggregate, or a process (a `cancel` controller resource). And a representation is a *projection* — which is why returning your JPA entity directly is a smell: you are leaking one specific persistence-shaped representation and coupling the public contract to your storage schema. Map through DTOs so the representation is a deliberate design choice, not an accident of your ORM.

#### Q31. [Theory] Is REST tied to HTTP? Explain the relationship between REST, HTTP, and the URI/media-type specs.

No — REST is an architectural *style*, defined independently of any protocol. Fielding derived it by adding constraints to the "null style," and HTTP is merely the most prominent system that *implements* the style. In principle you could build a RESTful system over another protocol that supports the same uniform-interface semantics. In practice, REST and HTTP are co-evolved: Fielding was a principal author of both the HTTP/1.1 spec and the URI spec, so HTTP's method semantics, status codes, caching model, and headers map almost one-to-one onto REST's constraints.

The cleaner way to frame it for an interview is in layers:

```
REST            = the architectural style (constraints)
HTTP            = an application protocol that realizes the style
URI (RFC 3986)  = the identifier scheme for resources
Media types     = the typing system for representations (IANA registry)
```

This matters because it explains *why HTTP "works" for REST and why misusing HTTP breaks REST*. When people tunnel everything through `POST` and return `200` with `{"error": ...}`, they keep the HTTP protocol but discard the REST style — they get none of the style's benefits (caching, idempotent retries, intermediary visibility) even though they are nominally "using HTTP." Conversely, modern specs like **RFC 9110 (HTTP Semantics)** deliberately separate semantics from the wire format (HTTP/1.1, HTTP/2, HTTP/3 all share the same semantics), which is itself a REST-flavored idea: the meaning of `GET`, `404`, and `ETag` is invariant across transport versions. So REST is the style, HTTP is the dominant realization, and the URI and media-type specs are the supporting type systems.

#### Q32. [Theory] What is the semantic difference between 404 Not Found, 410 Gone, and 204 No Content?

These three are frequently confused, and an interviewer asks to test whether you understand HTTP semantics rather than just "success vs. error." They answer different questions about a resource's existence and the response body.

```
204 No Content  -> "Request succeeded; there is intentionally no body."
404 Not Found   -> "No resource at this URI right now (cause unspecified)."
410 Gone        -> "There WAS a resource here; it is permanently removed."
```

**204** is a success code (2xx). It signals the operation worked but there is nothing to return — classic for a `DELETE`, a `PUT` that returns no body, or a `PATCH` where the client doesn't need the updated representation. A 204 response *must not* include a message body; sending one is a protocol violation that some clients will choke on. **404** is the generic "I can't find it" — deliberately vague, because it doesn't commit to whether the resource never existed, is hidden for authorization reasons, or simply isn't there. That vagueness is sometimes used intentionally as a security measure (returning 404 instead of 403 to avoid confirming a resource exists to an unauthorized caller).

**410 Gone** is the precise, less-used cousin of 404: it asserts the resource *existed and is intentionally, permanently gone*, and clients (and search engines) should stop requesting it and purge links. Use it for deliberately retired endpoints or deleted-and-never-coming-back resources. The trade-off: 410 leaks the fact that something used to exist, and it requires you to *remember* what was deleted (tombstones), so most APIs default to 404 and reserve 410 for documented sunsets. The principle: pick the code that conveys the most accurate machine-readable meaning your security posture allows.

### 🟡 Intermediate — extended

#### Q33. [Theory] Why is statelessness a REST constraint, and what exactly counts as "state" — is server-side caching or a database a violation?

Statelessness means each request from client to server must contain all information needed to understand and process it; the server does not store **client session state** between requests. The "why" is the payoff: any server instance can handle any request, so you scale horizontally by adding boxes behind a load balancer, you fail over without losing sessions, and intermediaries can reason about each request in isolation. The cost is that each request is fatter (it re-sends auth tokens, context) and you can't lean on server memory for conversational context.

The subtle interview trap is *what counts as state*. The constraint forbids **per-client session state on the server** — e.g., "the user is on step 3 of the wizard" stored in server RAM keyed by a session cookie. It does **not** forbid:

```
Resource state (the database)     -> ALLOWED. This is the shared application
                                     state every request operates on.
Caches (Redis, CDN, ETag stores)  -> ALLOWED. A performance optimization,
                                     not per-client conversational state.
Auth tokens in the request        -> REQUIRED by statelessness: the client
                                     carries identity each time (JWT/bearer).
```

So a database is not a violation — the *resource* state lives there and is the whole point. The line is: state about **the resource** is fine and expected; state about **the conversation/client between requests** must travel with the request. This is why token-based auth (a self-contained JWT, re-sent each call) is "more RESTful" than a server-side `HttpSession` keyed by a cookie. The classic violation is a server that stores `currentPage` or a shopping-cart-in-session in memory: now a specific server "remembers" you, sticky sessions are required, and horizontal scaling and failover break. Push that state to the client (carry it in the request) or to shared resource storage (persist the cart as a `/carts/{id}` resource), and you restore statelessness.

#### Q34. [Theory] Explain how the `Vary` header works internally and what goes wrong when it is missing or wrong.

A shared cache stores responses keyed primarily by the request URI (and method). But many responses depend on *request headers* too — `Accept`, `Accept-Encoding`, `Accept-Language`, sometimes `Authorization`. The `Vary` response header tells the cache: "this stored entry is only valid for requests whose listed headers match the ones on the request that produced it." Internally, the cache extends its key from `(method, URI)` to `(method, URI, normalized values of the Vary-listed headers)`.

```
Request 1: GET /orders/42   Accept: application/json
Response:  200, Content-Type: application/json, Vary: Accept
Cache key: GET /orders/42 | Accept=application/json   -> stores JSON

Request 2: GET /orders/42   Accept: application/xml
Cache:     key Accept=application/xml is a MISS -> goes to origin
Response:  200, Content-Type: application/xml, Vary: Accept
           -> stores a SECOND entry under the XML key
```

When `Vary` is **missing**, the cache assumes the response is the same for all requests to that URI regardless of headers. So if the first caller asked for JSON, an XML-requesting caller may get the cached JSON — a silent content-type confusion bug. When `Vary` is **too broad** (e.g., `Vary: User-Agent` or `Vary: *`), the cache fragments into near-uncacheable shards because almost every request has a unique header value, destroying hit rate.

The dangerous case is `Vary: Cookie` (or forgetting it on personalized responses): if a per-user response is cached on a *shared* key, the cache can serve user A's data to user B — a security incident, not just a correctness one. Best practice: mark personalized responses `Cache-Control: private` (or `no-store`) so shared caches don't store them at all, vary only on the headers that genuinely change the body, and normalize header values (e.g., collapse `Accept-Encoding` to `gzip`/`br`/identity) before they become part of the cache key.

#### Q35. [Theory] What is the difference between strong and weak ETag comparison, and how do 304 vs 412 use them differently?

An ETag is an opaque validator a server attaches to a representation. There are two flavors: a **strong** ETag (`"abc"`) guarantees byte-for-byte identity — two representations with the same strong ETag are octet-equivalent — while a **weak** ETag (`W/"abc"`) only guarantees *semantic* equivalence, allowing trivial differences like a changed timestamp comment, different whitespace, or a different compression. The `W/` prefix is the marker.

The spec defines two comparison functions and uses them in different places:

```
Strong comparison: both must be strong AND octet-equal.   Used by If-Match.
Weak   comparison: opaque-tags equal, weakness ignored.   Used by If-None-Match.
```

This is why **conditional GET (`If-None-Match` → 304)** uses *weak* comparison: for caching/revalidation you only care "is my cached copy still good enough to reuse?", and a semantically-equivalent representation is good enough — so a weak ETag match yields `304 Not Modified` and saves the body transfer. By contrast, **conditional write (`If-Match` → 412)** uses *strong* comparison, because for optimistic concurrency you must be certain the resource is in *exactly* the state you read; a weak match would let a subtly-different version through and reintroduce the lost-update risk you were trying to prevent.

```
Revalidation (read):  If-None-Match: W/"v7"  weak compare  -> 304 if match
Concurrency (write):  If-Match: "v7"         strong compare -> 412 if no match
```

The practical consequence: you generally should **not** use weak ETags for resources you protect with `If-Match`, because a weak validator is not valid for strong comparison and the precondition will simply fail (or, per spec, a weak tag must never satisfy `If-Match`). Use strong ETags (e.g., a version counter or content hash) when the same ETag must serve both revalidation and concurrency control; reserve weak ETags for cases where you cannot cheaply produce a byte-stable tag — for instance, dynamically compressed content where only the formatting varies.

#### Q36. [Theory] What is the difference between 301, 302, 307, and 308 redirects, and why were 307/308 introduced?

The redirect codes encode two orthogonal questions: **is the move permanent?** and **may the method change on the redirected request?** The original `301`/`302` were ambiguous about the second question, and that ambiguity caused real bugs — so `307`/`308` were added to make method-preservation explicit.

```
       | Permanent? | Method preserved on follow?
-------|------------|------------------------------------------
301    | yes        | historically/often changed POST->GET (ambiguous)
302    | no         | historically/often changed POST->GET (ambiguous)
307    | no         | YES — method and body MUST be preserved
308    | yes        | YES — method and body MUST be preserved
```

The historical problem: the HTTP/1.0 and 1.1 specs said `301`/`302` should preserve the method, but virtually every browser implemented them by silently rewriting a `POST` into a `GET` on the follow-up request (dropping the body). Apps came to *depend* on that behavior, so the spec couldn't simply fix `301`/`302` without breaking the web. The fix was to introduce **307 Temporary Redirect** and **308 Permanent Redirect**, which the spec mandates *must not* change the method or drop the body. So `307` is the unambiguous temporary redirect and `308` the unambiguous permanent one.

For API design the guidance is: if you are redirecting a `POST`/`PUT`/`PATCH`/`DELETE` and you need the same method and body replayed against the new URI, use **307** (temporary) or **308** (permanent) — never `301`/`302`, or you risk a client turning your mutating request into a `GET`. Use `301`/`302` only for `GET`-style navigation where method rewriting is harmless or even desired. There is also `303 See Other`, which deliberately *does* force a `GET` — useful in the POST-Redirect-GET pattern, where after a `POST` you 303 the client to a `GET` of the newly created resource. Knowing 303's "always GET" semantics rounds out the set.

#### Q37. [Theory] Why is POST not idempotent while PUT is, at the protocol-semantics level — and can a POST ever be idempotent?

The difference is rooted in **who chooses the resource URI**. `PUT` targets a *specific, client-known URI* and means "make the resource at this URI equal to this representation." Replaying the same `PUT` to the same URI with the same body converges to the same state — set the resource to X, set it to X again, still X. That convergence is exactly idempotency. `POST`, by contrast, classically means "process this representation according to the resource's own semantics," most commonly "create a new subordinate resource under this collection." Each `POST /orders` mints a *new* URI (a new order), so replaying it creates a second order — different state, hence not idempotent.

```
PUT /orders/42  {state X}   -> order 42 = X
PUT /orders/42  {state X}   -> order 42 = X   (same -> idempotent)

POST /orders    {an order}  -> creates /orders/42
POST /orders    {an order}  -> creates /orders/43  (new -> NOT idempotent)
```

The spec is careful to say `POST` is *not guaranteed* idempotent — it doesn't forbid an idempotent `POST`, it just doesn't promise one. So yes, a `POST` **can** be made idempotent by application design: the most common technique is the **idempotency key**, where the server records the key and replays the stored result on retries, so N identical `POST`s produce one order. Another case is a `POST` that is naturally convergent — e.g., "ensure a subscription exists for this user" implemented as upsert-by-natural-key returns the same existing resource each time. A `PATCH` is the mirror image: not guaranteed idempotent (a JSON Patch `add` to an array appends every time), but often *is* idempotent (a JSON Merge Patch that sets fields to fixed values).

The deeper point interviewers want: idempotency is a property of the *effect on server state*, not of the response. Two identical idempotent requests may return different responses (the first `DELETE` returns 204, the second 404) yet still be idempotent because the *resulting state* — "the resource is gone" — is unchanged. This is why idempotency, not safety, is what makes automatic retries safe.

#### Q38. [Theory] Explain HTTP/1.1 vs HTTP/2 vs HTTP/3 and how each affects REST API performance. Does REST change?

REST's *semantics* are identical across all three — `GET`, `404`, `ETag`, and `Cache-Control` mean the same thing regardless of wire version (RFC 9110 deliberately factors semantics out from transport). What changes is the **transport efficiency**, which materially affects API latency and throughput patterns.

```
            | Transport        | Multiplexing | Header compr. | Head-of-line blocking
------------|------------------|--------------|---------------|----------------------
HTTP/1.1    | TCP, text        | no (1 req/   | none          | yes (per connection)
            |                  | conn; pipelining broken in practice)
HTTP/2      | TCP, binary frames| yes (streams)| HPACK        | TCP-level HOL blocking
HTTP/3      | QUIC over UDP    | yes (streams)| QPACK         | none (per-stream)
```

**HTTP/1.1** allows one in-flight request per connection (pipelining is effectively dead), so browsers open 6+ parallel connections per host, and chatty REST APIs suffer connection overhead and head-of-line blocking. This is part of why "too many small resources" hurts on 1.1 and why people resorted to response bundling and domain sharding. **HTTP/2** multiplexes many concurrent streams over a single TCP connection with binary framing and HPACK header compression — so a client can fire many small REST calls in parallel cheaply, which actually makes *fine-grained, well-factored REST resources* perform well again and undercuts the "we must bundle everything into one mega-response" argument. Its weakness: because it rides on a single TCP connection, a lost packet stalls *all* streams (TCP-level head-of-line blocking).

**HTTP/3** moves to QUIC over UDP, where each stream has independent loss recovery, eliminating cross-stream head-of-line blocking, and it folds the TLS handshake into the transport for faster (often 0-RTT) connection setup — a real win on lossy mobile networks. The REST design implications: on modern HTTP, don't over-bundle to dodge round-trips that multiplexing has made cheap; keep resources cleanly factored; and lean harder on HTTP caching, which still works identically. The key interview point is the layering insight from RFC 9110 — your REST contract (methods, status codes, validators) is a stable abstraction *above* the transport, so you can adopt HTTP/3 for performance without touching your API design at all.

#### Q39. [Practical] What does the OPTIONS method do, and how do CORS preflight requests work under the hood?

`OPTIONS` is the HTTP method for asking "what can I do with this resource?" — it's safe and idempotent and returns communication options (e.g., an `Allow: GET, POST, PUT` header) without acting on the resource. Its most important real-world use is the **CORS preflight**: before a browser sends certain cross-origin requests, it automatically fires an `OPTIONS` request to check whether the server permits the actual call. This is a browser security mechanism (the Same-Origin Policy / CORS), not something your client code issues explicitly.

A request triggers a preflight when it is "non-simple" — i.e., it uses a method other than GET/POST/HEAD, sets a non-safelisted header (like `Authorization` or a custom `X-` header), or uses a `Content-Type` other than the form/text trio (notably `application/json` triggers preflight). The exchange looks like this:

```
Preflight (browser -> server, automatic):
  OPTIONS /orders
  Origin: https://app.acme.com
  Access-Control-Request-Method: POST
  Access-Control-Request-Headers: authorization, content-type

Preflight response (server):
  204 No Content
  Access-Control-Allow-Origin: https://app.acme.com
  Access-Control-Allow-Methods: GET, POST, PUT, DELETE
  Access-Control-Allow-Headers: authorization, content-type
  Access-Control-Max-Age: 600          # cache the preflight result 10 min

Actual request (only if preflight allowed it):
  POST /orders
  Origin: https://app.acme.com
  Authorization: Bearer ...
```

The performance lever is `Access-Control-Max-Age`: the browser caches a successful preflight per (origin, method, headers) for that many seconds, so you don't pay an extra round-trip on every call. The security points interviewers probe: `Access-Control-Allow-Origin` must echo a *specific* trusted origin (or a vetted allow-list), never `*` together with `Access-Control-Allow-Credentials: true` (the spec forbids that combination, and `*` with cookies would be a serious leak); and CORS is enforced *by the browser*, not the server — it does not protect against non-browser clients (curl ignores it), so it is not an authorization mechanism. CORS controls which *web origins'* JavaScript may read your responses; real authorization still has to happen server-side.

#### Q40. [Theory] Compare cookie/session authentication with token-based (JWT/Bearer) authentication through a REST lens. Which is "more RESTful" and why?

Through the REST lens the decisive criterion is the **statelessness** constraint. Classic cookie-session auth issues an opaque session id stored in a cookie; the server keeps the actual session (user identity, roles, maybe a shopping cart) in server-side storage and looks it up each request. That means the server holds **per-client state between requests**, which is precisely what statelessness discourages — it pushes you toward sticky sessions or a shared session store and complicates horizontal scaling and failover. Token-based auth (a signed, self-contained JWT or similar bearer token) instead carries the identity claims *in the request itself*; the server validates the signature and reads the claims without looking anything up, so any instance can serve any request. That is why bearer-token auth is generally considered "more RESTful."

```
                | Cookie + server session     | JWT / Bearer token
----------------|------------------------------|-----------------------------
State location  | server (per-client)          | the token (self-contained)
Scales horiz.   | needs shared/sticky session  | trivially (stateless verify)
Revocation      | easy (delete session)        | hard (token valid until expiry)
CSRF exposure   | yes (cookies auto-sent)      | no, if sent via Authorization
                |                              | header (not auto-sent)
Where it lives  | Cookie header (auto)         | Authorization: Bearer <jwt>
```

But "more RESTful" is not the same as "always better," and a strong answer names the trade-offs. The Achilles' heel of stateless tokens is **revocation**: because the server doesn't track sessions, you can't instantly invalidate a leaked JWT — it stays valid until it expires. The mitigations (short-lived access tokens plus refresh tokens, a token-version/`jti` denylist checked against a cache, or rotating signing keys) reintroduce *some* server-side state, which is the honest tension: pure statelessness trades away easy revocation. Cookies, conversely, are convenient and revocable but auto-attach to every request to the origin, creating **CSRF** risk that you must counter (SameSite cookies, CSRF tokens) — whereas a token sent in an explicit `Authorization` header is not auto-sent by the browser and so isn't CSRF-prone.

In practice: for a browser SPA talking to its own backend, a secure `HttpOnly`, `SameSite` cookie is often the *safer* default (it keeps the token out of JavaScript, dodging XSS token theft) even though it's less "pure." For public APIs, mobile apps, and service-to-service calls, bearer tokens win on statelessness and cross-client uniformity. The interview-grade answer: identify statelessness as the REST principle that favors tokens, then refuse to be dogmatic about it because revocation and XSS/CSRF trade-offs decide the real choice.

#### Q41. [Theory] What does "uniform interface" actually consist of, and why does Fielding call it the central trade-off of REST?

The uniform interface is the constraint Fielding singles out as the feature that most distinguishes REST from other network architectures, and it is composed of **four sub-constraints**, not one vague idea:

```
1. Identification of resources          -> resources are named by URIs.
2. Manipulation through representations  -> clients act on resources by
   sending/receiving representations (+ enough metadata to modify them).
3. Self-descriptive messages             -> each message carries everything
   needed to process it (method, media type, cache directives, status).
4. Hypermedia as the engine of           -> the client drives state transitions
   application state (HATEOAS)              by following links the server provides.
```

The crucial intellectual honesty in Fielding's framing is that the uniform interface is **a deliberate trade-off, not a free win**. Standardizing the interface "degrades efficiency, since information is transferred in a standardized form rather than one specific to an application's needs" — i.e., a bespoke binary RPC tuned to one client will always be more wire-efficient than generic JSON-over-HTTP with standard verbs and status codes. You *pay* in efficiency.

What you *buy* with that payment is **visibility, generality, and independent evolvability**: because every component speaks the same standardized interface, intermediaries (caches, proxies, gateways, WAFs) can understand and act on messages, components developed independently interoperate, and clients and servers evolve separately. This is exactly why the trade-off is usually worth it for systems that must live a long time and integrate broadly — the efficiency you lose is small and local, while the evolvability and ecosystem leverage you gain compound over years. The interview-grade insight is to *name it as a trade-off*: REST is not "the most efficient" design; it is the design that optimizes for longevity, scale, and the ability to slot in standard infrastructure — and the uniform interface is the lever that makes that possible.

### 🟠 Advanced — extended

#### Q42. [Theory] Walk through the HTTP caching freshness model: how does a cache decide an entry is fresh vs. stale, and what do max-age, Age, Expires, and Date contribute?

A cache decides whether a stored response can be served without contacting the origin by comparing the response's **age** to its **freshness lifetime**. If `age < freshness_lifetime`, the entry is *fresh* and served directly; otherwise it is *stale* and must be revalidated (or, if allowed, served stale). The inputs come from several headers that interact in a defined precedence.

```
freshness_lifetime =
    s-maxage           (shared caches only)         -- highest precedence
  else max-age         (Cache-Control)
  else Expires - Date  (HTTP/1.0 fallback)
  else a heuristic     (e.g., 10% of (Date - Last-Modified))

current_age ≈ (now - response_Date) + time-in-this-cache + upstream Age
```

`Date` is the origin's timestamp for when the response was generated; `Age` is how many seconds the response has already been sitting in caches upstream of you — each cache increments it. So a response that traveled through a CDN already carries an `Age`, and your downstream cache adds its own dwell time. `max-age` (and `s-maxage` for shared caches specifically) directly state the lifetime; `Expires` is the older absolute-time mechanism that `max-age` overrides when both are present. When *no* explicit freshness is given, a cache may apply a **heuristic** (commonly a fraction of the time since `Last-Modified`), which is a frequent source of "why is my response being cached when I didn't say it could?" surprises — the cure is to send explicit `Cache-Control` (e.g., `no-store` or `no-cache`) rather than rely on defaults.

Once stale, the entry isn't necessarily useless: `stale-while-revalidate=N` lets the cache serve the stale copy immediately *and* refresh it in the background (hiding latency), while `stale-if-error=N` lets it serve stale if the origin is down (graceful degradation). Revalidation itself uses validators — `If-None-Match`/`ETag` or `If-Modified-Since`/`Last-Modified` — and a `304 Not Modified` simply *resets* the freshness clock without resending the body. The design lesson: control freshness explicitly per resource (short `max-age` for volatile data, longer for static), expose validators so revalidation is a cheap 304, and reach for `stale-while-revalidate`/`stale-if-error` to decouple user-perceived latency and availability from origin health.

#### Q43. [Theory] What is HATEOAS at the wire level — compare HAL, JSON:API, and Siren, and explain why adoption is low.

HATEOAS says the server embeds, in each representation, the **links and available actions** that drive the client's next state transition, so the client navigates by following server-provided links rather than hardcoding URI templates. At the wire level this requires a **hypermedia format** — a convention for where links live and how affordances are described. Three common ones:

```
HAL (application/hal+json)   -- minimalist: _links + _embedded. Links only,
                                no method/affordance info. Most popular; used
                                by Spring HATEOAS.
  { "id":"42", "_links": { "self": {"href":"/orders/42"},
                           "cancel": {"href":"/orders/42/cancel"} } }

JSON:API (application/vnd.api+json) -- opinionated full spec: data, included,
                                relationships, links, pagination, sparse
                                fieldsets. Solves more than just links.

Siren (application/vnd.siren+json) -- richest affordances: entities, links,
                                AND "actions" with method, href, and expected
                                fields -> closest to a self-describing form.
  { "actions": [ { "name":"cancel", "method":"POST",
                   "href":"/orders/42/cancel" } ] }
```

The key distinction is *how much of the affordance* each format expresses. HAL gives you links but not how to invoke them (no method, no input schema), so a client still needs out-of-band knowledge to know `cancel` is a `POST`. Siren goes furthest, describing actions with their method and expected fields — the closest JSON gets to HTML's `<form>`, which was Fielding's original inspiration (HTML is the canonical hypermedia format). JSON:API is less about pure HATEOAS and more a complete envelope standard that *also* carries links and relationships.

Adoption is low for structural reasons, not ignorance. First, **clients are written by humans** who read API docs and hardcode the two or three paths they need; the theoretical decoupling of "the server can move URIs freely" rarely pays off because clients also hardcode the *shape* of responses anyway. Second, **JSON is a weak hypermedia medium** — unlike HTML, browsers and tools don't natively *do* anything with `_links`, so the link-following machinery is custom code with little ecosystem payoff. Third, **OpenAPI won the contract war**: teams get discoverability, codegen, and docs from a static spec, which captures most of HATEOAS's practical benefits without runtime link-following. So the industry largely sits at Richardson Level 2 + OpenAPI. The honest interview position: understand the ideal and its formats, acknowledge it's "more correct," and explain *why* the cost/benefit rarely justifies it — while noting that HATEOAS genuinely shines in narrow cases like long-lived workflow/state-machine APIs where available actions change with resource state and you want the server, not the client, to own that logic.

#### Q44. [Theory] Explain optimistic vs pessimistic concurrency control for REST writes, and why optimistic concurrency fits REST better.

Concurrency control prevents the **lost update** problem: two clients read a resource at version V, both modify it, and the second write silently clobbers the first. **Pessimistic** control prevents conflict by *locking* — a client acquires an exclusive lock on the resource, holds it across the read-modify-write, and releases it, so no one else can touch it meanwhile. **Optimistic** control assumes conflicts are rare: clients don't lock; instead each write is *conditional* on the resource not having changed since it was read, and the rare conflict is detected and rejected at write time.

```
Pessimistic:  LOCK order 42 -> read -> modify -> write -> UNLOCK
              (others block/wait the whole time)

Optimistic:   read order 42 (ETag "v7") -> modify ->
              PUT If-Match: "v7"
                 -> 200 if still "v7"        (apply, bump to "v8")
                 -> 412 Precondition Failed  (someone wrote first; client
                                              re-reads "v8", merges, retries)
```

Optimistic concurrency fits REST naturally because it is **stateless and uses the standard HTTP conditional-request machinery**: the "lock" is just an `ETag` the client read earlier and echoes back via `If-Match`, and the conflict is a standard `412 Precondition Failed`. No server-side lock state is held between requests — so it preserves statelessness, scales horizontally, and works through caches and gateways that already understand conditional requests. Pessimistic locking, by contrast, requires the server (or a coordinator) to hold lock state *between* a client's read and its write — that is per-client state spanning requests, which collides with statelessness, and a client that reads-and-vanishes leaves a dangling lock that needs timeouts and lease management.

The trade-off is about **conflict frequency**. Optimistic is ideal for low-contention, read-heavy resources (most REST APIs): in the common case there's zero locking overhead, and the occasional 412 just costs a retry. It degrades under *high* contention — many clients fighting over the same hot resource means lots of 412s and retry storms, where pessimistic locking (or serializing through a queue) may be better. For typical web APIs, optimistic concurrency via `ETag`/`If-Match` (often backed by a JPA `@Version` column so the DB enforces it too) is the canonical, REST-aligned choice; reserve pessimistic locking for genuinely high-contention, must-not-retry scenarios.

#### Q45. [Theory] How would you do long-running asynchronous operations RESTfully? Compare 202 + polling, webhooks, and SSE/streaming.

When an operation can't complete within a reasonable request timeout (a big export, a video transcode, a bulk job), you must *decouple acceptance from completion* while staying within REST semantics. The foundational pattern is **202 Accepted plus a status resource**: the server accepts the work, returns `202` with a `Location` (and/or `Content-Location`) pointing at a job resource, and the client tracks progress by polling that resource.

```
POST /exports               -> 202 Accepted
                               Location: /exports/9f3
                               Retry-After: 5            # poll hint

GET /exports/9f3            -> 200 { "status":"RUNNING", "progress":0.4 }
                               Retry-After: 5
...
GET /exports/9f3            -> 200 { "status":"DONE",
                                     "result": { "href":"/files/abc.csv" } }
            (or 303 See Other -> Location: /files/abc.csv  to fetch the result)
```

This is clean REST: the **job is itself a resource** with its own URI and lifecycle, it's pollable and cacheable, and `Retry-After` tells the client how often to poll so you avoid hammering. The downside is polling latency and wasted requests. Alternatives trade those off:

```
Mechanism      | Direction        | Best when
---------------|------------------|------------------------------------------
202 + polling  | client pulls     | simple, firewalls/proxies friendly, no
               |                  | client endpoint needed; cost = poll traffic
Webhooks       | server pushes    | many/long jobs; client CAN host an HTTP
(callback URL) | (POST to client) | endpoint; needs retries, signing, idempotent
               |                  | receivers (deliveries can duplicate)
SSE / streaming| server streams   | live progress to a browser over one GET;
(text/event-   | over one conn    | unidirectional, auto-reconnect; not for
 stream)       |                  | machine-to-machine fan-out
```

**Webhooks** invert control — the server `POST`s to a client-supplied callback URL on completion, eliminating polling, but they push real complexity onto the design: the client must run a reachable, authenticated endpoint; you must **sign payloads** (HMAC) so receivers can verify origin; and because delivery is at-least-once, receivers must be **idempotent** and you need retry/backoff and a dead-letter path. **SSE** (`text/event-stream`) keeps a single long-lived `GET` open and streams progress events to a browser with built-in reconnection — great for live UI progress, but it's unidirectional and connection-bound, so it's not a substitute for webhooks in machine-to-machine integrations. A robust production design often *combines* them: `202` + a job resource as the canonical, always-available source of truth, **plus** an optional webhook for push efficiency — the webhook is a latency optimization, and the pollable resource is the durable fallback when a webhook delivery is missed.

#### Q46. [Practical] What is the difference between PUT-for-create and POST-for-create, and when is a client-specified URI (PUT to create) appropriate?

Both can create a resource, but they answer the question "**who chooses the URI?**" differently, and that drives idempotency and design. With **POST-to-create**, the client sends the new resource to a *collection* URI (`POST /orders`) and the *server* assigns the identity, returning `201 Created` with a `Location` header pointing at the new URI. With **PUT-to-create**, the client *already knows the target URI* and says "make a resource exist at exactly this URI" (`PUT /orders/{client-chosen-id}`); if nothing is there, the server creates it (`201`), and if something is, it replaces it (`200`/`204`).

```
POST /orders            { ... }   -> 201 Created, Location: /orders/42
                                     (server picks id "42"; NOT idempotent:
                                      repeat -> /orders/43)

PUT  /orders/my-key-99  { ... }   -> 201 if absent, 200/204 if it replaced
                                     (client owns the id; idempotent: repeat
                                      -> same resource, same state)
```

The decisive property is **idempotency**, and it follows directly from who owns the URI. `PUT`-to-create is idempotent because the target URI is fixed by the client: replaying it converges on one resource in one state. `POST`-to-create is not idempotent because each call mints a fresh server-assigned URI — which is exactly why `POST` creation often needs an `Idempotency-Key` to be safely retryable.

`PUT`-to-create is appropriate precisely when **the client controls a meaningful, unique identifier**: the resource has a natural key (a username, an SKU, a device serial, a file path/object key like in S3-style object stores), or the client generates an opaque id (a UUID) up front. In those cases PUT gives you idempotent, retry-safe creation for free and reads as "ensure this resource exists with this state" — an upsert. Conversely, prefer `POST`-to-create when the **server owns identity** (auto-increment ids, server-side allocation, or when the id must not be guessable/predictable for security), when creation triggers server-side side effects you don't want replayed blindly, or when the resource genuinely has no client-meaningful natural key. A subtle pitfall with PUT-create: because PUT is a *full replace*, a client that omits fields can unintentionally wipe an existing resource on what they thought was a create — so document the replace semantics, and use `If-None-Match: *` (create-only, 412 if it already exists) or `If-Match` to make intent explicit and collision-safe.

#### Q47. [Theory] Why must request body size, pagination, and query complexity all be bounded — what is "unrestricted resource consumption" and how does it manifest?

"Unrestricted resource consumption" is OWASP API4:2023 — the class of vulnerabilities where an API lets a caller consume *unbounded* CPU, memory, bandwidth, storage, or downstream cost because some input dimension isn't capped. It is dangerous precisely because it doesn't require breaking authentication or authorization; a *legitimate* caller (or a single malicious one) can degrade or take down the service, or amplify your cloud bill, simply by sending large or expensive-but-valid requests. The defense is to treat **every unbounded dimension** as an attack surface and put a ceiling on it.

```
Unbounded dimension        | How it manifests                         | Cap
---------------------------|------------------------------------------|--------------------
Request body size          | 2 GB JSON -> OOM / parser blowup         | max body size -> 413
Page size (limit param)    | ?limit=10000000 -> giant query+payload   | clamp to e.g. 100
Pagination depth (offset)  | OFFSET 50000000 -> full scan each page    | cursor pagination
Filter/sort complexity     | sort on unindexed col -> table scan       | allow-list fields
Array/collection inputs    | batch of 1,000,000 items -> long txn      | cap batch size
Response fan-out / expand  | ?expand=everything -> N+1 join storm      | limit expansion depth
Request rate / concurrency | flood -> exhausts threads/connections     | rate + concurrency limits
File uploads / downloads   | huge multipart -> disk/bandwidth          | size limit + streaming
```

The reason **all** of these need bounding, not just rate limiting, is that the cost of a request is multi-dimensional: rate limiting caps *how many* requests, but a single request can still be ruinously expensive if its *size or complexity* is unbounded. A `?limit=10000000` is one request — it sails past a rate limiter — yet it forces a massive DB read and serialization. An `OFFSET 50000000` is one request that scans tens of millions of rows. A `sort` on an unindexed text column is one request that table-scans. Each is "valid" input that an unbounded API will dutifully try to satisfy.

So the production posture is layered and explicit: cap request body size (return `413 Content Too Large`), clamp `limit` server-side (don't trust the client), prefer cursor pagination so depth can't be weaponized, **allow-list** sortable/filterable fields so callers can't force scans on unindexed columns, bound batch sizes and expansion depth, and combine per-caller **rate limits with concurrency limits** plus overload **load-shedding** (`503` + `Retry-After`). The mindset shift the interviewer is testing: don't reason about "the average request" — reason about the *most expensive request a caller can construct from valid inputs*, and make sure that worst case is bounded.

#### Q48. [Theory] How do gzip/Brotli content encoding and the Content-Encoding/Transfer-Encoding/Accept-Encoding headers actually work, and what are the pitfalls?

Compression reduces payload size on the wire, which for JSON-heavy REST APIs is often a huge win (JSON is verbose and highly compressible). The negotiation is via `Accept-Encoding` (request) and `Content-Encoding` (response): the client advertises what it can decode, the server compresses with one of those and labels the result. `Content-Encoding` is an **end-to-end** property of the *representation* — the body is genuinely encoded and stays encoded until the client decodes it.

```
Request:  GET /orders/42
          Accept-Encoding: br, gzip, deflate
Response: 200 OK
          Content-Encoding: br          # body is Brotli-compressed
          Vary: Accept-Encoding         # so caches store per-encoding
```

The three common codings: **gzip** (DEFLATE-based, universal, fast, good ratio), **Brotli (`br`)** (newer, typically 15–25% smaller than gzip for text, slightly more CPU — now broadly supported and preferred for HTTPS responses), and **`deflate`** (best avoided due to historical raw-vs-zlib ambiguity bugs). A frequently-tested distinction is **`Content-Encoding` vs `Transfer-Encoding`**: `Content-Encoding` describes the representation itself (end-to-end, survives caching), whereas `Transfer-Encoding` (e.g., `chunked`) is a **hop-by-hop** property of how the message is framed on a single connection and is *not* part of the resource — it's stripped/re-applied per hop and must not be cached as part of the body.

Pitfalls the interviewer is probing:

```
- Missing Vary: Accept-Encoding -> a shared cache may hand a gzip body to a
  client that can't decode it (or vice versa). Always Vary on it.
- Compressing already-compressed payloads (JPEG/PNG/zip) -> wasted CPU, no
  gain (sometimes larger). Skip by content type / min-size threshold.
- BREACH/CRIME attacks: compressing a response that mixes a secret (e.g., a
  CSRF token) with attacker-influenced reflected input can leak the secret via
  size side-channels over TLS. Mitigate by not compressing sensitive/secret-
  bearing responses, or by masking/randomizing tokens.
- Small bodies: compression overhead can exceed savings -> set a minimum size
  (e.g., only compress > ~1 KB).
- Double compression at multiple layers (app + gateway + CDN) wastes CPU.
```

The design takeaway: enable Brotli (falling back to gzip) for text/JSON responses above a size threshold, always send `Vary: Accept-Encoding`, exclude already-compressed and security-sensitive payloads, and decide *one* layer (usually the gateway/CDN or the app, not both) owns compression. Done right, it's one of the cheapest latency/bandwidth wins available; done carelessly it produces undecodable cached responses or, in the BREACH case, an actual information-disclosure vulnerability.

### 🔴 Expert — extended

#### Q49. [Theory] Contrast the "tolerant reader" / robustness principle with "be strict in what you accept" for API evolution. Where does each apply?

Postel's robustness principle — "be conservative in what you send, be liberal in what you accept" — is the philosophical backbone of API *evolvability*. Applied to REST it produces the **tolerant reader**: a client should ignore fields it doesn't recognize, tolerate added enum values where possible, and not break when the response grows. If every consumer is a tolerant reader, the provider can make **additive changes** (new optional fields, new endpoints, new response properties) without breaking anyone — which is the single most important mechanism for evolving an API without versioning. The provider holds up its side by being conservative: never remove or repurpose a field, never tighten output, keep new fields optional.

But there is a well-documented critique: applied *too* liberally, robustness causes long-term harm. If servers silently accept malformed or ambiguous input, clients ship bugs that "work," those bugs become de-facto contract, and years later you cannot tighten anything without breaking the ecosystem — the exact `301`/`302` method-rewriting trap, frozen forever. So modern guidance splits the principle by direction:

```
Direction        | Posture                         | Why
-----------------|---------------------------------|-----------------------------
Reading responses| LIBERAL (tolerant reader):      | lets the provider add fields
(client side)    | ignore unknown fields, don't    | without breaking you;
                 | over-validate the body shape    | core of additive evolution
Accepting requests| STRICT: reject unknown fields,  | prevents mass-assignment,
(server side)    | validate types/ranges, fail     | stops latent client bugs
                 | fast with 400/422               | from becoming de-facto contract
Sending          | CONSERVATIVE / canonical:       | predictable, minimal surface
(both)           | only documented fields, stable  | for consumers to depend on
                 | shapes                          |
```

The resolution interviewers want: be a **tolerant reader on the way in to your client** (ignore extras in responses you consume) but a **strict validator on the way in to your server** (reject unexpected request fields, enforce schemas) — because strictness on inputs is exactly what defends against mass assignment (a caller sneaking `isAdmin=true`) and prevents accidental contract calcification. The asymmetry isn't a contradiction; it's the recognition that *tolerance on outputs you don't control buys evolvability, while strictness on inputs you do control buys security and the freedom to fix mistakes later.* "Liberal in, strict out" is a misreading — the durable rule is *tolerant when reading others' responses, strict when accepting requests, conservative when producing output.*

#### Q50. [Theory] Explain the lost-update problem in depth and enumerate every HTTP-level mechanism for preventing it, including the "lost update on create" case.

The lost-update problem is a read-modify-write race: client A reads version V, client B reads version V, A writes (now V+1), B writes based on its stale V and overwrites A's change — A's update is silently lost. It is the canonical correctness hazard for any mutable shared resource (inventory counts, account balances, document edits, configuration). HTTP provides a precise, stateless toolkit to detect and prevent it, all built on **conditional requests** (RFC 9110 preconditions).

```
Mechanism                  | Header sent     | Failure code | Use
---------------------------|-----------------|--------------|--------------------------
ETag + If-Match            | If-Match: "v7"  | 412          | Update IFF unchanged since
                           |                 |              | read (strong validator)
Last-Modified + If-        | If-Unmodified-  | 412          | Same idea with timestamps
  Unmodified-Since         |   Since: <date> |              | (1-second granularity!)
Require precondition       | (none sent)     | 428          | Server demands If-Match so
  (RFC 6585)               |                 |              | clients can't skip the check
Create-only guard          | If-None-Match: *| 412          | PUT-create that must NOT
                           |                 |              | overwrite an existing resource
```

The primary mechanism is **`ETag` + `If-Match`**: the server emits a strong `ETag` on read; the client echoes it via `If-Match` on write; the server applies the change only if the current ETag still matches, else returns **412 Precondition Failed** and the client must re-read, merge, and retry. The timestamp variant (`Last-Modified` + `If-Unmodified-Since`) works the same way but is weaker — one-second resolution means two writes within the same second can both "pass," so prefer ETags for true safety. To stop a careless client from *omitting* the precondition entirely (and thus doing a blind last-write-wins overwrite), the server can mandate it and return **428 Precondition Required** when `If-Match` is absent — closing the loophole.

The often-missed case is the **lost update on create**: two clients both try to `PUT /users/alice` to create the same resource, and the second blindly overwrites the first's freshly-created data. The guard is `If-Match: *`... no — specifically **`If-None-Match: *`**, which means "only succeed if no representation currently exists," so the *second* create fails with `412` instead of clobbering. (`If-Match: *` is the inverse: "only if the resource exists.") Backing all of this, you typically pair the HTTP-level checks with a database-level guarantee — a JPA `@Version` column or a `WHERE version = ?` conditional `UPDATE` — so even if a precondition is somehow bypassed (or two requests slip through concurrently), the storage layer still detects the conflict and you map its `OptimisticLockException` to a `412`/`409`. The interview-grade completeness: detect with ETags, force the check with 428, prevent create-races with `If-None-Match: *`, and backstop everything with optimistic locking in the datastore — all of it stateless and conditional-request-based, which is why it composes with caches and gateways.

#### Q51. [Theory] What is the difference between API versioning and API evolution, and why is Stripe's date-based versioning architecturally different from /v1, /v2?

These are often conflated but represent two different philosophies of change management. **API versioning** is the explicit, discontinuous approach: you publish v1, and when a breaking change is unavoidable you publish v2 as a parallel, separately-maintained surface; consumers must *migrate* from one to the other. **API evolution** is the continuous approach: you change a *single* living API in only backward-compatible ways (additive fields, new endpoints, widened inputs), so consumers never have to migrate at all — versioning is the failure mode you resort to only when evolution can't express the change. The mature stance is "evolve by default, version as a last resort," because every major version you publish is a long-term maintenance liability (parallel code paths, duplicated docs, consumer migration projects).

The standard `/v1`, `/v2` URI scheme is **coarse-grained, global, and migration-based**: a new version re-versions the *entire* API, every consumer on v1 must eventually rewrite to v2, and you maintain two full stacks during the overlap. It's operationally simple and visible, but expensive at scale and disruptive to integrators — a single breaking change to one endpoint forces a whole-API version bump.

```
/v1, /v2 (URI versioning)              Stripe date-based versioning
---------------------------            -----------------------------------------
- versions the WHOLE API               - versions are fine-grained CHANGE SETS,
- consumer must migrate stacks           pinned PER ACCOUNT at first integration
- you run v1 AND v2 in parallel        - ONE current codebase; old behavior is
- breaking change = new major            reconstructed by request/response
                                         TRANSFORMERS chained newest->oldest
- discoverable, gateway-routable       - header: Stripe-Version: 2024-XX-XX
- coarse, disruptive at scale          - account auto-pinned; opt-in to upgrade
```

Stripe's date-based model (`Stripe-Version: 2024-06-20`, pinned per account at signup, header-overridable per request) is architecturally different because the codebase is **not branched per version**. Internally Stripe maintains *one* current implementation plus a chain of small, composable **version-change transformers**; when a request arrives pinned to an older date, the platform runs the request and then applies the relevant transformers in sequence to fold the modern internal response *back* into the older shape that account expects (and the inverse on the way in). A "version" is therefore a tiny, well-described delta (e.g., "renamed field X to Y," "this enum gained a value"), not a separate API. This lets Stripe ship hundreds of changes over a decade while old integrations keep working untouched and customers upgrade *deliberately* by bumping their pinned date and testing.

The architectural lessons for an interview: (1) treat backward compatibility as a *product feature* with real engineering investment (the transformer pipeline), not an afterthought; (2) decouple "the change" from "the version surface" so changes are granular and additive internally; (3) pin consumers to a known-good version so *you* control when they're exposed to new behavior, rather than forcing synchronized migrations. The trade-off is real cost: the transformer chain is complex to build and test, and it only pays off at Stripe-scale integrator counts — for a small internal API, plain `/v1` plus disciplined additive evolution is the pragmatic choice. The point is knowing *why* date-based-with-transformers is a fundamentally different machine than `/v1`→`/v2`, not just a different header.

#### Q52. [Theory] In a layered system with multiple intermediaries, what semantic guarantees must hold for caches, proxies, and gateways to behave correctly — and what API mistakes break them?

The **layered system** constraint says a client cannot tell whether it's talking to the origin or an intermediary, and intermediaries (forward proxies, reverse proxies, gateways, CDNs, WAFs, shared caches) can be inserted transparently. For that to be safe, the API must uphold the semantic *contracts* that those intermediaries rely on — they make decisions purely from standard HTTP semantics, not from your application's private knowledge. Break a contract, and an intermediary makes a *correct-per-spec* decision that is *wrong for your app*.

```
Intermediary acts on...        Guarantee the API must uphold
-------------------------------|-----------------------------------------------
method safety/idempotency      | GET/HEAD must be side-effect-free; PUT/DELETE
                               | safely retryable -> intermediaries may retry,
                               | prefetch, or cache them
cacheability declarations      | Cache-Control / ETag / Vary must be ACCURATE;
                               | a cache trusts them literally
status code semantics          | 2xx=success, 4xx=client, 5xx=server -> retry
                               | logic, circuit breakers, error pages depend on it
hop-by-hop vs end-to-end       | Connection, Transfer-Encoding, TE, Upgrade are
  header classification        | hop-by-hop; must not be cached/forwarded as body
Content-Length / framing       | accurate length & framing -> no smuggling
```

The classic breakages, each mapping to a real incident class:

```
- GET with side effects: a prefetching proxy, link-scanner, or cache warmer
  issues GETs -> your "GET /orders/42/delete" silently deletes data.
- Returning 200 OK with {"error":...}: intermediaries see success -> no retry,
  no circuit-breaker trip, error pages never shown, monitoring under-reports.
- Lying about cacheability (no Vary, wrong max-age, caching personalized data
  on a shared key): a shared cache serves user A's response to user B.
- 500 for a client mistake (or 4xx for a transient server fault): breaks
  client/gateway retry and backoff logic (clients don't retry 4xx, do retry 5xx).
- Request smuggling: ambiguous Content-Length vs Transfer-Encoding between a
  front proxy and origin -> attacker desyncs the two and injects requests.
- Treating end-to-end headers as hop-by-hop (or vice versa): e.g., a proxy
  stripping/forwarding the wrong headers corrupts auth or caching.
```

The unifying principle is **self-descriptive messages**: because every component reasons from standardized semantics, an API that "uses HTTP correctly" gets the entire intermediary ecosystem — caching, retries, circuit breakers, load shedding, observability — *for free*, while an API that tunnels meaning past those semantics must reimplement all of it in application code and still fights the infrastructure that interprets the wire faithfully. So the staff-level answer isn't a list of rules; it's the realization that **HTTP correctness is what makes the layered system composable**, and the most consequential violations (GET side effects, 200-with-error-bodies, inaccurate caching metadata, framing ambiguities) are dangerous precisely *because* well-behaved intermediaries trust your declarations and act on them.

#### Q53. [Theory] Compare media-type (content-negotiation) versioning, hypermedia-driven evolution, and explicit versioning as strategies for never breaking clients. Why does true REST claim to need no versioning at all?

There's a provocative claim in REST circles — attributed to Fielding — that a *truly* RESTful API "should not need versioning at all." Understanding why exposes the deep relationship between the uniform interface and evolvability. The argument: if clients are driven by **hypermedia** (HATEOAS) and **negotiate representations** by media type, then the server can change URIs, add affordances, remove actions, and introduce new representation formats *without* breaking clients, because clients never hardcoded URIs or response shapes — they follow links and accept the media types they understand. Evolution becomes continuous and the discontinuous "v2" event disappears.

```
Strategy                      | What the client binds to        | How it evolves
------------------------------|---------------------------------|-------------------------
Explicit versioning (/v1,     | a frozen URI + response shape   | publish new version;
  header, query param)        |                                 | migrate consumers
Media-type versioning         | a media type:                   | add a NEW media type;
  (content negotiation)       | application/vnd.acme.order.v2+  | old clients keep asking
                              | json                            | for v1 type -> served v1
Hypermedia-driven (HATEOAS    | link relations + media types,   | move URIs, add/remove
  + self-descriptive msgs)    | NOT URIs or response schemas    | actions freely; clients
                              |                                 | adapt by following links
```

**Media-type versioning** is the most "REST-aligned" *explicit* strategy: each representation has its own version (`Accept: application/vnd.acme.order.v2+json`), so you version per-representation rather than per-API, old clients keep requesting the v1 media type and get served the v1 shape, and it rides the existing content-negotiation machinery (with correct `Vary: Accept`). It's strictly more granular than `/v1` URI versioning. **Hypermedia-driven evolution** goes further — it aims to remove the need for explicit versions by making the *interaction* discoverable at runtime: the client asks "what can I do with this order now?" and the server tells it via links/affordances, so adding a `refund` action or relocating `cancel` requires no client change.

So why doesn't everyone do it, if it "needs no versioning"? Because the claim holds only under **strong assumptions that rarely obtain in practice**: clients must be genuine tolerant readers that never hardcode URIs or response schemas, must follow links at runtime, and must handle representations they don't fully understand — but real clients are written by humans who read docs and hardcode the three endpoints they need and the exact JSON fields they parse. The moment a client hardcodes `orders[0].total` or `/orders/{id}/cancel`, your "version-free" hypermedia API can still break it. Media-type versioning, meanwhile, is correct but suffers poor tooling and discoverability, so most teams find `/v1` URI versioning + OpenAPI + disciplined additive evolution captures 90% of the benefit at 10% of the cost.

The expert synthesis: "true REST needs no versioning" is *true in theory and conditional in practice* — it's true to the extent your clients honor the uniform interface (hypermedia + self-descriptive messages + tolerant reading), and the reason real APIs still version is that the human-written clients on the other end violate those assumptions. The useful takeaway is the spectrum: maximize **additive evolution** (no version event), use **media-type versioning** when a representation genuinely must change shape, fall back to **explicit `/v1` versioning** only for true breaking changes — and recognize that the more your clients behave like hypermedia clients, the less you ever need the last option.

#### Q54. [Practical] How do you design idempotency and exactly-once semantics across a chain of REST services where each call can be retried independently?

The honest starting point is that **exactly-once delivery does not exist** over an unreliable network — you cannot guarantee a message is delivered precisely once. What you *can* engineer is **effectively-once processing**: at-least-once delivery (retries) combined with idempotent receivers, so that no matter how many times a message arrives, the *effect* happens once. In a chain `Client -> Gateway -> Order svc -> Payment svc -> Ledger`, every hop can time out and retry independently, so an idempotency strategy must hold *end to end*, not just at the edge.

The core technique is to **propagate an idempotency key down the chain** and have each service deduplicate against it, so a retry at any layer collapses to the stored result rather than re-executing the side effect.

```
Client ──Idempotency-Key: K──> Order svc
   (retry K)                       │  store K -> {status,result}; on replay, return stored
                                   ├──Idempotency-Key: K'(derived from K)──> Payment svc
                                   │     store K'; charge once; replay returns same charge
                                   └──Idempotency-Key: K''──────────────────> Ledger
                                         unique constraint on K'' -> insert-once

Rules:
- Each service has its OWN idempotency store (DB row w/ unique key, or Redis SET NX).
- Derive downstream keys deterministically from the upstream key (K' = hash(K|"payment"))
  so a retry produces the SAME downstream key -> downstream dedupes too.
- Persist the request FINGERPRINT with the key; same key + different body -> 422.
- Store the RESPONSE so replays return it verbatim (status code + body).
```

The hard parts are the **partial-failure and crash windows**, and this is where weaker designs fall apart. If the Order service charged Payment but crashed before recording completion, a naive retry could double-charge — so the charge call itself must be idempotent (which is why you pass `K'` down, not a fresh key each attempt), and you need a **reconciliation/outbox** mechanism: write the intent and the downstream call in the same transaction via the **transactional outbox pattern**, then a relay publishes/calls downstream at-least-once; the downstream's idempotency makes the retries safe. For long chains you also need to decide **compensation** semantics — if the ledger write ultimately fails after the charge succeeded, you need a SAGA with a compensating `refund`, because you can't atomically roll back across service boundaries.

The complete design, then, layers three ideas: **at-least-once retries** (with backoff and jitter) for liveness; **idempotent receivers keyed by a propagated, deterministically-derived key** (with a request fingerprint and stored response) so retries are effect-free; and **outbox + saga** for crash-consistency and cross-service rollback. The interview-grade nuance is naming the impossibility up front ("exactly-once delivery is a myth; we engineer effectively-once via at-least-once + idempotency"), then showing that idempotency must be *propagated and enforced at every hop* — an idempotency key honored only at the gateway protects nothing if the Order→Payment retry can fire independently.

#### Q55. [Practical] Why is mass assignment a REST-specific design hazard, and what are all the defenses at the API design level (not just framework config)?

Mass assignment (OWASP API3:2023, "Broken Object Property Level Authorization") is REST-specific because REST's whole ergonomic appeal — bind a JSON body straight onto an object and persist it — is exactly the mechanism that creates the hole. When a framework auto-maps every field in the request body onto your domain/persistence entity, a caller can set fields you never intended to expose: `isAdmin`, `accountBalance`, `ownerId`, `verified`, `role`, `priceOverride`. The endpoint "works" for the happy path, and the vulnerability is invisible until someone sends the extra field. It's the dark side of the convenience that makes REST + ORM so productive.

```
DTO:  CreateUserRequest { name, email, password }
Bad:  @PostMapping void create(@RequestBody User entity) { repo.save(entity); }
      Attacker sends: { "name":"x", "email":"...", "isAdmin": true, "balance": 99999 }
      -> entity.isAdmin = true persisted. Privilege escalation, no auth bug needed.
```

The defenses, layered from most to least fundamental (an interviewer wants more than "use DTOs"):

```
1. Explicit input DTOs / contracts  -> bind to a request type that contains ONLY
   the client-settable fields; map deliberately to the entity. The entity is never
   the bind target. (Most important; design-level, framework-agnostic.)
2. Field allow-listing, not block-listing -> enumerate what MAY be set; never try
   to blacklist dangerous fields (you'll forget one, and new fields default to open).
3. Reject unknown properties (strict deserialization) -> fail with 400/422 on
   unexpected fields (e.g., Jackson FAIL_ON_UNKNOWN_PROPERTIES) so probing is loud.
4. Separate read and write models -> the response DTO and the request DTO differ;
   server-controlled fields (id, createdAt, status, ownerId) live only in the
   read model and are assigned server-side, never accepted from the client.
5. Server-authoritative fields -> identity, ownership, timestamps, computed/
   derived, and security-relevant fields are set from the authenticated principal
   and server state, NOT from the body, even if present.
6. Object-property-level authZ -> some fields are settable only by some roles
   (a support agent may set `status`, a customer may not); enforce per-field,
   per-role, server-side. This is the "property-level authorization" the OWASP
   name emphasizes.
7. Schema validation at the edge -> validate the body against an OpenAPI/JSON
   Schema contract at the gateway so undocumented fields are rejected before they
   reach business logic (defense in depth).
```

The architectural insight is that mass assignment is a **trust-boundary** failure: the request body is *untrusted input*, and binding it directly to a trusted persistence object erases the boundary. The durable fix is to make the boundary explicit — a hand-mapped DTO is not boilerplate, it *is* the security control that decides, field by field, what a client is allowed to influence. Allow-listing beats block-listing because it fails *closed* (a newly-added entity field is not settable until you opt it in), whereas a block-list fails *open* (forget to add the new sensitive field and it's exposed). And note these defenses are independent of any framework toggle: even with auto-binding turned off, you still need separate read/write models, server-authoritative fields, and per-field authorization for roles — which is why the answer is "design the contract so untrusted input can only ever touch fields it's permitted to," not merely "configure Jackson."

#### Q56. [Theory] What is the difference between OpenAPI, JSON Schema, and a media type, and how do they relate in a contract-first REST workflow?

These three are different *layers* of the contract and are constantly confused. A **media type** (e.g., `application/json`, `application/problem+json`, `application/vnd.acme.order.v2+json`) is the wire-level *type label* registered with IANA that tells a recipient how to parse a representation — it answers "what format is this byte stream?" **JSON Schema** is a *vocabulary for validating the structure of a JSON document* — types, required fields, ranges, patterns, enums — answering "is this particular JSON body valid?" **OpenAPI** is a *full API description language* — it describes paths, methods, parameters, responses, status codes, security schemes, and the request/response *bodies* (which it describes using a JSON-Schema dialect) — answering "what is the entire shape and behavior of this API?"

```
Layer        | Scope                          | Answers
-------------|--------------------------------|----------------------------------
Media type   | one representation's format    | "how do I parse these bytes?"
JSON Schema  | one document's structure       | "is this body well-formed/valid?"
OpenAPI      | the whole API surface          | "what endpoints, params, responses,
             | (uses JSON Schema for bodies)  |  status codes, and auth exist?"
```

The relationship matters because of an important historical detail: OpenAPI 3.0 used a JSON-Schema *subset/superset* that was *not* fully compatible with standard JSON Schema (it had its own `nullable`, lacked some keywords, added others). **OpenAPI 3.1 (released 2021) fixed this by aligning with JSON Schema Draft 2020-12** — so an OpenAPI 3.1 schema object *is* a valid JSON Schema, letting you reuse the same schemas for both API description and standalone validation. That's a real, frequently-tested version fact.

In a **contract-first** (spec-first) workflow these layers compose: you author the OpenAPI 3.1 document as the single source of truth; its body schemas (JSON Schema) drive request validation at the gateway and code generation for server stubs and client SDKs; the media types in the spec tell both sides how to serialize. CI runs an OpenAPI **diff** tool (e.g., `oasdiff`/`openapi-diff`) to fail the build on breaking changes, and consumer-driven contract tests (Pact) verify real consumers still pass. The payoff over code-first is that the contract is reviewable in PRs *before* implementation, clients can be generated in parallel with the server, and validation/codegen/docs all derive from one artifact — which is why most mature public-API teams are spec-first on OpenAPI 3.1.

#### Q57. [Theory] Why is JWT-based authorization more subtle than it looks? Explain alg=none, signature verification, and why short-lived tokens plus refresh tokens reintroduce state.

A JWT is three Base64URL parts — `header.payload.signature` — and the entire security model rests on **verifying the signature with a key you trust before believing a single claim**. The subtlety is that the *header is attacker-controllable*, including the `alg` field, which historically created two devastating vulnerability classes. The first is **`alg: none`**: some libraries, reading `alg` from the untrusted header, would accept a token with `alg=none` and *no signature* as valid — an attacker forges arbitrary claims (`sub`, `role: admin`) and the server trusts them. The defense is to never let the token dictate the algorithm: pin the expected algorithm server-side and reject anything else.

```
header:  { "alg": "none" }            <- attacker sets this; library "verifies" nothing
payload: { "sub":"victim","role":"admin" }
signature: (empty)
   -> vulnerable lib accepts it -> full impersonation.

Defense: server enforces alg = RS256 (configured), ignores token's alg claim.
```

The second classic is the **RS256 → HS256 key-confusion** attack: if the server verifies with "the public key," an attacker switches `alg` to HS256 (symmetric HMAC) and signs the forged token using the *public* key (which is, by definition, public) as the HMAC secret; a naive verifier that picks the HMAC code path based on the header's `alg` and feeds it the public-key bytes will validate the forgery. Again the fix is to bind the verification algorithm to your key type server-side, not to the header.

Beyond forgery, the architectural subtlety is **revocation versus statelessness**. A self-contained JWT is attractive precisely because it's stateless — the server validates the signature and reads claims with no lookup, which is "more RESTful" and scales trivially. But that same property means **you cannot un-issue a leaked or compromised token**; it's valid until `exp`. The standard mitigation — **short-lived access tokens (minutes) plus a long-lived refresh token** — narrows the exposure window, but it quietly *reintroduces server-side state*: refresh tokens must be stored and revocable, you often maintain a `jti` denylist or a per-user token-version counter checked on each request, and rotating signing keys (`kid`) requires a key store. That is the honest tension to articulate in an interview: pure JWT trades away easy revocation for statelessness, and every practical revocation mechanism (denylist, refresh rotation, token versioning) buys back security by adding back some of the very state REST's statelessness wanted to eliminate. The mature posture is short-lived signed access tokens + rotating refresh tokens + a small revocation cache, accepting that "stateless auth" is really "mostly stateless with a thin, fast revocation layer."

#### Q58. [Theory] Explain the at-most-once / at-least-once / exactly-once spectrum for REST mutations and how HTTP method semantics map onto it.

Delivery/processing guarantees form a spectrum defined by how duplicates and losses are traded off, and a strong REST answer maps HTTP's method semantics directly onto it. **At-most-once** means a mutation is applied zero or one times — never duplicated, but possibly *lost* (no retries, or retries that don't re-apply). **At-least-once** means it's applied one or more times — never lost, but possibly *duplicated* (retries that re-execute). **Exactly-once (effectively-once)** means applied precisely once as observed in the resulting state — the ideal, achievable for *processing* (not delivery) via at-least-once retries plus idempotent handling.

```
Guarantee         | Duplicates? | Losses? | How to achieve over HTTP
------------------|-------------|---------|--------------------------------------
At-most-once      | no          | yes     | fire-and-forget; don't retry on timeout
At-least-once     | yes         | no      | retry on timeout/5xx with backoff
Exactly-once      | no          | no      | at-least-once + IDEMPOTENT handling
(effectively-once)|             |         | (idempotency key / natural convergence)
```

The mapping to HTTP methods is the key insight. Because **idempotent methods** (`PUT`, `DELETE`, and safe `GET`/`HEAD`) converge to the same state no matter how many times they're applied, you can safely run them **at-least-once** — retry on timeout — and *get effectively-once for free*, since the duplicates are harmless. That's why an HTTP-aware gateway or client is willing to auto-retry an idempotent request but *not* a `POST`. **Non-idempotent methods** (`POST`, often `PATCH`) are the hard case: retrying at-least-once risks duplicates (a second order, a second charge), so you either accept **at-most-once** (don't retry, risk loss — unacceptable for payments) or you *manufacture* effectively-once by adding an **idempotency key** that makes the `POST` conditionally idempotent at the application layer, after which at-least-once retries are safe again.

The deep takeaway interviewers want: **idempotency is the bridge that turns cheap, lossless at-least-once retries into effectively-once outcomes** — and HTTP method semantics already tell you which operations get that bridge for free (`PUT`/`DELETE`) versus which ones you must build it for (`POST`/`PATCH`, via idempotency keys). Exactly-once *delivery* is impossible over an unreliable network (the FLP/Two-Generals reality), so the engineering goal is always effectively-once *processing*, and the lever is idempotency, not magic in the transport.

#### Q59. [Practical] How do you decide between embedding (sub-resource expansion) and linking (references) when modeling related resources, and what are the performance/coupling trade-offs?

When an `Order` relates to a `Customer` and `LineItems`, you must decide whether a `GET /orders/42` returns those related resources **embedded** inline or merely **linked/referenced** (an id or URI the client fetches separately). This is a foundational modeling trade-off between *round-trips* and *coupling/cacheability*, and the best APIs make it **client-controlled** rather than hardcoding one choice.

```
Linking (references):                  Embedding (expansion):
{ "id":"42",                           { "id":"42",
  "customerId":"c7",                     "customer": { "id":"c7","name":"..." },
  "_links": {                            "items": [ {...}, {...} ] }
    "customer": {"href":"/customers/c7"},
    "items":    {"href":"/orders/42/items"} }}

Best practice: let the client opt in ->  GET /orders/42?expand=customer,items
```

**Linking** keeps each resource small, independently cacheable (the `Customer` has its own `ETag`/`Cache-Control` and may already be in cache from another call), and loosely coupled — changing the customer schema doesn't change the order representation. The cost is **round-trips**: a screen needing order + customer + items makes 3 calls (the N+1 problem at the API level), which hurts on high-latency mobile networks. **Embedding** collapses that to one round-trip and is convenient for a specific client view, but it bloats the payload, hard-wires one client's screen layout into the shared contract, makes the combined response *uncacheable per-resource* (you can't reuse a cached customer), and turns any change in a related resource's schema into a change in this representation — a coupling and evolvability tax.

The production resolution is **opt-in expansion via a query parameter** (`?expand=customer,items`, or sparse fieldsets `?fields=`): default to linking (small, cacheable, decoupled), and let the client request embedding only where it genuinely saves round-trips, with a **bounded expansion depth** to prevent a `?expand=everything` fan-out from triggering an N+1 join storm or an OWASP-API4 resource-consumption blowup. This is exactly Stripe's model (most objects can be `expand`ed). The interview-grade reasoning: don't pick embed-vs-link globally — recognize it's a per-call trade-off between latency and cacheability/coupling, push the choice to the client with explicit, depth-limited expansion, and note that on HTTP/2+ where many small requests are cheap, the pressure to embed for performance is lower than it was on HTTP/1.1.

#### Q60. [Theory] Why must query strings be used for non-identifying parameters and the path for identity — and what are the caching, idempotency, and bookmarking consequences of getting this wrong?

The URI is split into a **path** (the hierarchical identity of a resource) and a **query string** (parameters that filter, sort, paginate, or project a *collection* or otherwise modify a representation of an already-identified resource). The principle: the path answers "*which* resource," the query answers "*which view/subset* of it." `GET /orders/42` identifies one order; `GET /orders?status=SHIPPED&sort=-createdAt` identifies the *orders collection* and selects a view of it. Putting identity in the query (`/orders?id=42`) or filters in the path (`/orders/shipped/recent`) confuses the two and breaks several HTTP behaviors that key on the *full URI*.

```
Correct:   GET /orders/42                        (identity in path)
           GET /orders?status=SHIPPED&limit=20    (filter/view in query)
Wrong:     GET /orders/get?id=42                  (identity smuggled into query + verb)
           GET /orders/shipped/page/3             (filter + pagination baked into path)
```

The consequences of getting it wrong are concrete. **Caching:** HTTP caches key on the entire URI *including* the query string, which is correct for `?status=SHIPPED` (different filter = genuinely different representation worth a distinct cache entry). But baking volatile filters into the *path* fragments your URI space unpredictably and makes invalidation harder, while smuggling identity into a query can cause two URIs (`/orders/42` and `/orders?id=42`) to identify the same resource with separate cache entries that drift out of sync. **Idempotency/safety:** filtering and pagination belong on a *safe* `GET`; a design that makes you `POST` a filter (because the query model is wrong) forfeits caching and GET's safe-retry semantics. **Bookmarking/sharing & logs:** because the query is part of the URI, a filtered view is bookmarkable and shareable as-is — but anything in the URI (path *or* query) lands in access logs, browser history, proxies, and `Referer` headers, which is exactly why **secrets and PII must never go in the query string** (use headers/body); a token in `?token=...` leaks into every intermediary's logs.

The subtle expert point is the limits of GET-with-query: query strings have practical length caps (servers/proxies often reject URIs beyond ~8 KB), so for *very* large or sensitive filter payloads people resort to a `POST`-based search (`POST /orders/search`) — but that's a deliberate, documented trade-off that *gives up* GET's cacheability and safe-retry in exchange for body size and privacy. So the rule stands with a caveat: identity in the path, views/filters in the query on a safe cacheable GET, never secrets in either part of the URI, and only fall back to POST-search when the query genuinely can't fit or must stay out of logs.

#### Q61. [Theory] What is the N+1 problem at the API layer versus the database layer, and how do REST design choices (chatty resources, expansion, batch endpoints) each address or worsen it?

"N+1" names two related but distinct pathologies, and conflating them is a common interview slip. The **database N+1** is internal to one request: your handler runs 1 query to fetch N orders, then loops and runs N more queries (one per order) to fetch each order's customer — 1+N round-trips to the DB for a single API call. The **API-layer N+1** is across requests over the network: a client fetches a collection of N order *links*, then issues N follow-up HTTP calls to dereference each one — 1+N round-trips between client and server. Same shape, different blast radius: the DB N+1 wastes your server's time; the API N+1 multiplies *network* latency, which is far more punishing on mobile/high-latency links.

```
DB N+1   (inside one API call):     SELECT orders; for each -> SELECT customer  (1+N DB hits)
   fix:  JOIN / IN-clause / batch fetch / entity graph / DataLoader-style batching

API N+1  (client makes 1+N calls):  GET /orders -> then GET /customers/{id} x N  (1+N HTTP hits)
   fix:  expansion (?expand=customer), embedding, or a batch/multi-get endpoint
```

REST design choices push on the *API-layer* N+1 in different directions. **Highly granular ("chatty") resources** with pure linking *worsen* it: maximally decoupled and cacheable, but a screen needing related data pays 1+N network round-trips. **Opt-in expansion / embedding** (`?expand=customer`) *addresses* it by letting the server resolve relations server-side and return them in one response — collapsing 1+N to 1 — though you must then guard against the *server* turning that into a database N+1 (resolve expansions with a batched/JOIN fetch, not a per-item loop) and bound expansion depth so `?expand=everything` doesn't become an OWASP-API4 fan-out. **Batch / multi-get endpoints** (`GET /customers?ids=c1,c2,c3` or `POST /customers/batch`) *address* it by letting the client dereference N references in *one* call instead of N — turning the client-side N+1 into a single round-trip, at the cost of weaker per-resource caching for the batched response.

The architectural through-line: API-layer N+1 is the natural tax of REST's resource granularity, and the three levers trade it against caching and coupling — chatty+linked maximizes cacheability/decoupling but worst-case round-trips; expansion and batch endpoints buy round-trip efficiency but reduce per-resource cacheability and risk pushing the problem *down* into a DB N+1 if the server resolves naively. The senior move is to (1) keep resources clean and linked by default, (2) offer bounded, opt-in expansion and batch reads for the hot screens that need them, and (3) ensure the server resolves any expansion/batch with set-based DB access (JOINs, `IN`, or a DataLoader-style batcher) so you never trade an API N+1 for a database N+1 — and to remember that on HTTP/2+ multiplexing, the cost of the client-side N+1 is meaningfully lower than it was on HTTP/1.1, which changes how aggressively you need these mitigations.

#### Q62. [Theory] Explain what makes a GET request "safe," why prefetchers/crawlers rely on it, and the real-world failures when GET has side effects.

A method is **safe** when it is purely *informational* — the client requests a representation with no expectation of, and the server makes no semantically significant change to, resource state. `GET`, `HEAD`, and `OPTIONS` are safe; the *intent* is read-only. Safety is a stronger guarantee than idempotency in one respect — it promises *no meaningful side effects at all*, not merely "the same effect on repeats" — and it's the contract that an enormous amount of internet infrastructure quietly depends on. Note "safe" doesn't forbid *all* side effects (a `GET` may legitimately increment a hit counter or write a log line); it forbids the client being held *responsible* for any state change — the user "did not request" it, so automated agents may issue GETs freely.

That "automated agents may issue GETs freely" is the load-bearing consequence. **Search-engine crawlers** follow every link they find with `GET`. **Browser/link prefetchers** and `<link rel=prefetch>`/speculative-prefetch fire `GET`s for links the user might click. **Antivirus/email link scanners** and chat-app **link unfurlers** (Slack, iMessage, WhatsApp generating a preview card) issue a `GET` the instant a URL is *pasted* — before any human clicks. **Caches and CDNs** prefetch and revalidate with `GET`. All of them assume safety: that fetching a URL cannot change anything.

```
Anti-pattern:  GET /orders/42/delete       (or GET /unsubscribe?id=42, GET /toggle?...)

What breaks it for you, automatically, with no user click:
  - Google crawls the link  -> order deleted / user unsubscribed
  - Slack/iMessage unfurls a pasted link -> action fires on paste
  - Browser prefetch / antivirus scan -> destructive GET executes
  - A shared cache "warms" the URL -> side effect repeats
```

The real-world failures are not hypothetical — the canonical case is an early-2000s web app that used `GET /admin/deletePage?id=...` links, then deployed a browser accelerator/prefetcher that dutifully *followed every link on the page*, including all the delete links, and wiped the site. The same class recurs constantly: one-click unsubscribe links implemented as bare `GET`s get triggered by mail scanners, and "approve/reject" links in emails fire when the inbox generates previews. The fix is semantic, not cosmetic: **state-changing operations must use an unsafe method** (`POST`/`PUT`/`PATCH`/`DELETE`), never a `GET` — and when you genuinely need a *clickable link* to trigger an action (email confirmations), the link should land on a page that then performs the change via a `POST` (often behind a one-time signed token), so no automated `GET` can execute the side effect. The deeper lesson connects to self-descriptive messages and the layered system: safety is a *promise to intermediaries*, and breaking it means well-behaved infrastructure — doing exactly what the spec entitles it to do — will damage your application.

#### Q63. [Theory] Compare REST with the OpenAPI-described "RPC-style HTTP" of gRPC-Gateway/Connect and with GraphQL on the dimensions of caching, contract strength, and evolvability. When does each dimension actually decide the choice?

Senior candidates are expected to reason about API *paradigms* on orthogonal axes rather than picking a favorite. Take three contenders that all ride HTTP: **resource-oriented REST**, **RPC-over-HTTP** (procedure-call style — gRPC/Connect, or any `POST /service.Method` JSON-RPC-ish design, often with an IDL like `.proto`), and **GraphQL** (a single endpoint with a typed query language). Evaluate them on caching, contract strength, and evolvability — the three dimensions that most often *decide* an architecture.

```
Dimension        | REST (resource)        | RPC-over-HTTP (.proto/IDL) | GraphQL
-----------------|------------------------|----------------------------|------------------------
HTTP caching     | excellent (GET + ETag, | poor (POST procedures;     | poor (single POST;
                 | Cache-Control, CDN)    | not resource/GET-shaped)   | persisted-queries help)
Contract strength| optional (OpenAPI);    | strong + codegen (.proto / | strong (SDL schema,
                 | weaker if undocumented | Connect schema)            | introspectable)
Evolvability     | additive fields + URI/ | field numbers/reserved tags| add fields freely; deprecate
                 | media-type versioning  | give safe wire evolution   | fields; no version event,
                 |                        | (proto is great here)      | but breaking = schema change
Over/under-fetch | per-resource (expand)  | fixed messages             | client picks exact fields
Best decided by  | public reach + caching | internal perf + strict     | diverse clients + aggregation
                 |                        | typed contract             | / BFF, mobile field control
```

The point is that *each dimension decides in different situations*. **Caching decides** when you have read-heavy, cacheable, public traffic (a catalog, content, reference data): REST wins almost by default because GET + `ETag` + `Cache-Control` lets CDNs and shared caches absorb the load, whereas GraphQL and RPC tunnel through `POST` and forfeit HTTP caching (GraphQL's mitigations — persisted queries sent as cacheable GETs, or `@cacheControl`/edge caching — are real but bolt-on, not free). **Contract strength decides** when many teams or external partners integrate and drift is expensive: a strong IDL (gRPC's `.proto`, GraphQL's SDL) with codegen and introspection beats an optional, possibly-stale OpenAPI doc — though OpenAPI 3.1 + CI diffing closes much of that gap for REST. **Evolvability decides** for long-lived contracts with many independent consumers: Protobuf's field-number/`reserved` discipline gives near-effortless backward-compatible wire evolution; GraphQL evolves by adding fields and deprecating (no version event) but a removed/retyped field is still breaking; REST evolves additively and falls back to URI/media-type versioning for true breaks.

So the decision framework is: **lead with the dimension that dominates your context.** Public, cache-sensitive, broad-compatibility surface → REST (caching + reach win). Internal service-to-service where latency and a strict typed contract dominate and you control both ends → RPC/gRPC (contract + perf win), accepting no HTTP caching and poor browser/debug ergonomics. Many heterogeneous clients (web/mobile) needing different field sets while aggregating multiple backends → GraphQL (field-level fetching + single schema win), accepting caching complexity, query-cost/rate-limiting difficulty, and N+1 risk. The mature architectures use all three — gRPC east-west, REST/GraphQL north-south — and the *anti-pattern* is choosing by familiarity or ideology rather than asking which of caching, contract strength, or evolvability is the binding constraint for *this* surface.

## 🧩 Extended Questions — Set 2: Practical, Operational & Troubleshooting

### 🟢 Basic — extended

#### Q64. [Practical] Your API returns the right data in Postman but the browser SPA gets "blocked by CORS policy" with no response body. How do you diagnose and fix it?

This is the single most common "it works in Postman but not in the browser" symptom, and the root cause is that **Postman is not a browser** — it ignores the Same-Origin Policy entirely, so CORS issues never surface there. The browser, on the other hand, makes the cross-origin request, the server responds, but the browser *refuses to expose the response to your JavaScript* because the CORS response headers are missing or wrong. The data really did come back (you'll see it in the Network tab), but `fetch()`/`XHR` rejects it. The first diagnostic step is to open DevTools → Network, find the request, and check two things: (1) is there a preceding `OPTIONS` (preflight) request, and (2) what `Access-Control-Allow-*` headers came back on it.

```bash
# Reproduce the preflight exactly as the browser does:
curl -i -X OPTIONS https://api.acme.com/orders \
  -H "Origin: https://app.acme.com" \
  -H "Access-Control-Request-Method: POST" \
  -H "Access-Control-Request-Headers: authorization, content-type"

# A correct response includes:
#   Access-Control-Allow-Origin: https://app.acme.com
#   Access-Control-Allow-Methods: GET, POST, PUT, DELETE
#   Access-Control-Allow-Headers: authorization, content-type
```

The usual culprits, in order of frequency: the server doesn't echo the caller's `Origin` in `Access-Control-Allow-Origin`; a custom header (`Authorization`, `X-Trace-Id`) is sent by the client but not listed in `Access-Control-Allow-Headers`; the preflight `OPTIONS` is being rejected by auth middleware (a security filter demands a token on the `OPTIONS` request, which the browser sends *without* credentials, so it 401s before CORS headers are added); or credentials mode is on (`fetch(..., {credentials:'include'})`) while the server returns `Access-Control-Allow-Origin: *`, which is illegal with credentials.

```java
@Configuration
public class CorsConfig {
    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override public void addCorsMappings(CorsRegistry r) {
                r.addMapping("/**")
                 .allowedOrigins("https://app.acme.com")   // specific, not "*"
                 .allowedMethods("GET","POST","PUT","PATCH","DELETE","OPTIONS")
                 .allowedHeaders("Authorization","Content-Type","X-Trace-Id")
                 .allowCredentials(true)                    // then origin MUST be specific
                 .maxAge(600);                              // cache preflight 10 min
            }
        };
    }
}
```

The fix that ties it together: ensure CORS runs *before* and *outside* your auth filter (so preflight `OPTIONS` is answered without a token), echo a specific allowed origin (never `*` with credentials), list every custom request header in `allowedHeaders`, and set `maxAge` so you're not paying a preflight on every call. The meta-lesson for an interview: CORS is a *browser* enforcement mechanism — it is not server authorization and not a security boundary against non-browser clients — so debugging it means thinking about what the browser sends automatically (the preflight) and what it requires back, not about your business logic.

#### Q65. [Practical] A client complains your API "randomly returns 415 Unsupported Media Type" on POST. Walk through how you'd debug it.

`415 Unsupported Media Type` means the server received a body whose `Content-Type` it isn't configured to consume on that endpoint — it's almost always a header mismatch, not a payload-content problem (that would be `400`/`422`). The "random" framing is the tell: it's not random, it correlates with *something* the client does inconsistently. The fastest diagnosis is to capture the exact request headers for a failing call and compare them to a passing one — DevTools, an access log that records `Content-Type`, or `curl -v`.

```bash
# Works: explicit JSON content type
curl -i -X POST https://api.acme.com/orders \
  -H "Content-Type: application/json" \
  -d '{"item":"x"}'

# 415: client sent form encoding (default for many HTTP libs / <form>)
curl -i -X POST https://api.acme.com/orders \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d '{"item":"x"}'
```

The common real-world causes: (1) the client library defaults to `application/x-www-form-urlencoded` or omits `Content-Type` entirely when it isn't told otherwise, so the server's JSON `@RequestBody` handler has nothing registered to parse it; (2) a charset suffix mismatch — the server is registered for `application/json` but the client sends `application/json; charset=UTF-8` and an overly strict matcher rejects the parameterized form (Spring handles this, but some hand-rolled matchers don't); (3) the endpoint declares `consumes = "application/vnd.acme.v2+json"` (media-type versioning) and the client sends plain `application/json`; (4) a proxy/gateway rewrites or strips the `Content-Type`. The "random" appearance usually comes from the client switching code paths — e.g., a retry path or a different SDK version that builds the request differently.

The fixes are: make the client send the correct `Content-Type` explicitly; on the server, be liberal about charset parameters; and return a *helpful* `415` body (problem+json) that states which media types are accepted so the client can self-correct: `"detail": "Expected application/json; received application/x-www-form-urlencoded"`. The principle is that `415` is a *negotiation* failure on the request body type — distinct from `406 Not Acceptable` (the server can't produce a representation matching the client's `Accept`) — so when you see 415, look at `Content-Type`; when you see 406, look at `Accept`.

#### Q66. [Practical] How do you use curl to debug a misbehaving REST endpoint? Show the flags you actually reach for.

`curl` is the universal REST debugging tool because it does *exactly* what you tell it and shows you the raw wire exchange — no SDK abstraction hiding the real headers. The flags I reach for constantly, and what each one buys:

```bash
# -i  include response headers in output (status code, ETag, Cache-Control...)
curl -i https://api.acme.com/orders/42

# -v  verbose: show request line + request headers + TLS handshake + response.
#     The single most useful flag for "why is this failing" — you see what
#     curl ACTUALLY sent vs what you think it sent.
curl -v https://api.acme.com/orders/42

# -X METHOD, -H header, -d body  -- construct an exact request
curl -X POST https://api.acme.com/orders \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"item":"x","qty":2}'

# -w  write-out: extract timing/metrics. Pinpoints WHERE latency lives.
curl -s -o /dev/null -w \
  "dns=%{time_namelookup} connect=%{time_connect} tls=%{time_appconnect} ttfb=%{time_starttransfer} total=%{time_total}\n" \
  https://api.acme.com/orders

# Conditional requests (test caching / concurrency):
curl -i https://api.acme.com/orders/42 -H 'If-None-Match: "v7"'   # expect 304
curl -i -X PUT https://api.acme.com/orders/42 -H 'If-Match: "v7"' -d '{...}'  # 412 if stale
```

A few power moves that turn curl from "make a request" into a real diagnostic. `--resolve api.acme.com:443:10.0.0.5` lets you hit a *specific* backend instance behind a load balancer (bypassing DNS) to prove whether one bad node is the problem. `--http1.1` / `--http2` forces a protocol version to isolate HTTP/2-specific bugs. `--compressed` advertises `Accept-Encoding` and auto-decodes, so you can confirm compression works. And the `-w` timing breakdown is gold during incidents: if `time_namelookup` is high it's DNS, high `time_connect` is TCP/network, high `time_appconnect` is the TLS handshake, and a high `time_starttransfer` (TTFB) with everything else fast points squarely at the server/application being slow rather than the network.

The reason curl beats Postman/Insomnia for *debugging* (not exploration) is reproducibility and honesty: a curl command is copy-pasteable into a ticket, runnable from any box (including the server itself, to rule out network/DNS), and `-v` shows the unvarnished bytes — which is exactly what you need when the bug is "the client and server disagree about what was actually sent."

### 🟡 Intermediate — extended

#### Q67. [Practical] Production p50 latency is fine but p99 is terrible and spiky. How do you investigate, and what are the usual REST-API culprits?

The first move is to refuse to reason about averages — a healthy p50 with a bad p99 means *most* requests are fine but a meaningful tail is suffering, and averages hide tails. So I look at the **distribution and its breakdown**: p50/p90/p99/p99.9 over time, segmented by endpoint, by instance, and by dependency. The goal is to localize *where* in the request path the tail time is spent before guessing at causes. Distributed tracing (OpenTelemetry spans for inbound request → DB calls → downstream HTTP calls) is the highest-leverage tool here because it shows you, for a slow request specifically, which span ate the time.

```
Slow trace shows time in...      Likely culprit
---------------------------------|------------------------------------------
DB query span                    | missing index / table scan on deep page
                                 | (offset pagination!), lock contention, N+1
"waiting for connection" span    | exhausted connection pool (HikariCP/HTTP
                                 | client pool) -> requests queue for a conn
downstream HTTP call span        | a slow dependency with no/too-long timeout
GC pause (no span, periodic)     | stop-the-world GC; correlate spikes w/ GC log
serialization span               | huge/unbounded response (no page cap, ?expand)
TLS handshake / connect          | no connection reuse -> new TLS each call
```

The classic REST-specific tail causes, in rough order of how often they bite: **connection pool exhaustion** — the DB pool or the downstream HTTP client pool has N connections, and under load request N+1 *waits* for one to free up; p50 is fine because there's usually a free connection, but under bursts a tail queues. **Periodic GC pauses** — spikes that recur on a fixed cadence and hit all endpoints simultaneously are the signature; correlate with GC logs. **A slow dependency without a tight timeout** — one downstream call occasionally takes 5s and you set no timeout, so your p99 inherits its tail; the fix is aggressive timeouts plus a circuit breaker. **Deep offset pagination** — `OFFSET 1000000` is fine until a user pages deep, then that one request scans a million rows.

```yaml
# Bounding the tail: timeouts + small pools that fail fast beat unbounded waits
spring:
  datasource:
    hikari:
      maximum-pool-size: 20
      connection-timeout: 2000   # fail fast (SQLTimeout) rather than hang the request
resilience4j:
  timelimiter:
    instances:
      paymentSvc: { timeoutDuration: 800ms }   # cap downstream tail
```

The investigation discipline that separates senior answers: form a hypothesis from the *trace breakdown*, then confirm with a targeted metric (pool wait time, GC pause histogram, dependency latency), rather than randomly tuning. And the structural fix for tails is usually **bounding things** — timeouts, pool sizes that fail fast, page-size caps, circuit breakers — because an unbounded resource (a wait, a query, a payload) is precisely what produces a long, spiky tail. Reducing the *mean* often does nothing for p99; you have to attack the specific tail contributor the trace reveals.

#### Q68. [Practical] You set timeouts and retries on your REST client and now a brief downstream blip turns into a full outage (retry storm). What went wrong and how do you configure retries safely?

This is the classic **retry amplification / metastable failure** trap. A downstream service slows down or briefly errors; every caller's retry logic kicks in; the retries *multiply* the load on the already-struggling dependency (1 original + 3 retries = 4x traffic); that extra load keeps it pinned down; callers keep retrying; the system locks into a degraded state that persists even after the original trigger is gone. The well-intentioned retry config converted a 30-second blip into a sustained outage. Three specific misconfigurations usually combine to cause it: **retrying non-idempotent or non-retryable failures**, **fixed-interval retries with no jitter** (so all clients retry in lockstep, creating synchronized load spikes), and **no upper bound / no circuit breaker** so retries continue against a dead dependency.

```java
// SAFE retry config (Resilience4j): bounded, backoff + JITTER, selective, + breaker
RetryConfig retry = RetryConfig.custom()
    .maxAttempts(3)                                   // small, bounded
    .intervalFunction(IntervalFunction
        .ofExponentialRandomBackoff(200, 2.0, 0.5))   // 200ms, x2, +/-50% JITTER
    .retryOnException(e -> e instanceof IOException    // only transient/network
        || e instanceof TimeoutException)
    .retryExceptions(/* 502,503,504 mapped */ )
    .ignoreExceptions(NonRetryableException.class)     // never retry 4xx
    .build();

CircuitBreakerConfig cb = CircuitBreakerConfig.custom()
    .failureRateThreshold(50)        // open if >50% fail
    .slowCallRateThreshold(80)
    .waitDurationInOpenState(Duration.ofSeconds(10))   // stop hammering when open
    .build();
```

The rules that make retries safe rather than dangerous: **only retry idempotent operations** (`GET`/`PUT`/`DELETE`, or a `POST` made idempotent with an `Idempotency-Key`) — retrying a non-idempotent `POST` without a key risks duplicate side effects on top of the storm; **only retry transient, retryable status codes** (`502`/`503`/`504`, connection failures, timeouts) — *never* retry `4xx`, those are your fault and will fail identically; **always use exponential backoff with jitter** so retries spread out instead of synchronizing; **bound the attempts** (2–3, not "until it works"); and **put a circuit breaker in front** so that once the dependency is clearly down, you stop sending retries at all and fail fast, giving it room to recover. Honor `Retry-After` when the server sends it.

The deeper principle is that **retries trade a small reduction in transient errors for a large increase in load at exactly the worst moment** — so they must be designed to *back off* under stress, not lean in. The complementary server-side defenses are **load shedding** (return `503` + `Retry-After` quickly instead of queuing), and ideally a **retry budget / token bucket on retries** so the total retry volume is capped as a fraction of base traffic. The interview signal is recognizing that timeouts and retries are not "set and forget" reliability features — misconfigured, they're an outage *cause*, and the cure is selectivity, jittered backoff, bounded attempts, and circuit breaking.

#### Q69. [Practical] How do you set timeouts correctly across a multi-hop REST call chain (gateway → service A → service B → DB)? What goes wrong if you don't?

The cardinal rule is that **timeouts must decrease as you go deeper** into the call chain — an outer timeout must be longer than the inner work it waits on, but each inner hop should give up *before* its caller does, so failures are detected at the right layer rather than the whole chain hanging until the outermost timeout fires. If you set the same (or larger) timeout everywhere, an inner stall propagates upward and ties up resources (threads, connections) at every layer for the full duration, which is how one slow DB query exhausts thread pools across the entire stack.

```
Layer            | Total budget | Why
-----------------|--------------|---------------------------------------------
Client/gateway   | 10s          | user-facing patience ceiling
  Service A      |  8s          | < gateway, leaves headroom for A's own work
    Service B    |  3s          | A calls B; B must fail before A's budget
      DB (B)     |  2s          | B's query gives up before B's own timeout
  Retries        | counted IN   | A's 8s must cover B-call + retries + backoff,
                 | the budget   | NOT be added on top, or you blow the ceiling
```

Two things go wrong when timeouts aren't laid out this way. First, **the missing-timeout hang**: a hop with *no* timeout (the default for many HTTP clients and JDBC drivers is "wait forever") will hold a thread and a connection indefinitely when the downstream stalls; under load these pile up and exhaust the pool, so requests that have nothing to do with the slow dependency start failing too — a localized problem becomes a total outage. Second, **inverted/equal timeouts**: if service B's timeout is *longer* than service A's, then when B is slow, A times out and returns an error to the gateway *while B is still working* — A has abandoned the call but B keeps consuming resources doing work nobody will read (wasted capacity), and the user got an error even though B might have succeeded a moment later.

The correct design also accounts for **retries inside the budget**, not on top of it: if A retries B up to 3 times with backoff, the *sum* of those attempts plus backoff must fit inside A's own deadline, otherwise A blows past the gateway's ceiling. The modern, robust pattern is **deadline propagation** — the gateway computes a deadline (`now + 10s`), passes the *remaining* budget downstream (e.g., a `grpc-timeout`-style header or a custom `X-Request-Deadline`), and each hop sizes its own timeout from "time left until the deadline," so the whole chain shares one coherent budget and inner hops never do work that's already too late to matter. Pair tight timeouts with **circuit breakers** (stop calling a dependency that's reliably timing out) and **load shedding** (return `503` fast when over budget). The interview-grade summary: timeouts strictly decrease inward, every hop must have *some* timeout (never infinite), retries live inside the parent budget, and ideally propagate a shared deadline so the chain fails fast at the right layer instead of hanging end-to-end.

#### Q70. [Practical] A `PUT` endpoint intermittently returns 409/500 under concurrent load with "could not execute statement; constraint violation" or optimistic-lock errors. How do you diagnose and fix it?

The symptom — intermittent, *only under concurrency*, with constraint-violation or `OptimisticLockException` in the logs — is the fingerprint of a **read-modify-write race** colliding with a database-level guard. Two clients (or two threads) read the same row, both compute an update, and both try to write; the database's unique constraint or `@Version`/optimistic-lock check catches the second writer and throws. The first diagnostic step is to read the actual exception: a `DataIntegrityViolationException` on a unique index means two requests tried to create/modify toward the same key; an `ObjectOptimisticLockingFailureException` means a `@Version`-guarded update found the version had moved since the entity was loaded. Both confirm "concurrent writes to the same logical resource."

```java
// The problem: load -> mutate -> save, with a @Version column.
// Under concurrency, two threads load version=7, both save -> one gets
// ObjectOptimisticLockingFailureException.

@PutMapping("/orders/{id}")
public ResponseEntity<OrderResponse> update(@PathVariable String id,
        @RequestHeader(value="If-Match", required=false) String ifMatch,
        @Valid @RequestBody UpdateOrderRequest req) {
    try {
        Order saved = service.update(id, req, ifMatch);   // @Version bump inside tx
        return ResponseEntity.ok().eTag('"'+saved.getVersion()+'"').body(...);
    } catch (ObjectOptimisticLockingFailureException e) {
        // DON'T let this surface as 500. It's a concurrency conflict:
        throw new ConflictException("Order was modified concurrently; re-read and retry"); // 409/412
    }
}
```

The fix has two parts. First, **map the conflict to the right status code, not a 500.** An optimistic-lock failure is *expected* under concurrency — it is the system working correctly — so it must surface as `409 Conflict` (or `412 Precondition Failed` if you're using `If-Match`), with a `problem+json` body telling the client to re-read and retry. Returning `500` is a bug because it tells the client (and your monitoring) that the server malfunctioned, when in fact it correctly prevented a lost update. Second, **decide the resolution policy**: for idempotent updates the client can simply re-`GET` (getting the new `ETag`), re-apply its change, and `PUT` again — a bounded client-side retry on `409`/`412`. For high-contention hot rows where conflicts are frequent, optimistic retry storms hurt, and you may serialize through a queue or use a short pessimistic lock (`SELECT ... FOR UPDATE`) instead.

The structural lesson: this is *not* a bug to "make go away" by removing the constraint — the constraint is what's protecting you from the silent lost-update corruption that would otherwise happen. The right posture is to keep the guard (optimistic `@Version` and/or unique constraint), expose conflicts honestly as `409`/`412`, give clients a deterministic retry path (re-read → merge → retry with the new `ETag`), and only escalate to pessimistic locking if measured contention makes the optimistic retry rate unacceptable. The interview red flag is a candidate who "fixes" the 500 by catching and swallowing the exception or by widening the transaction's isolation blindly; the correct instinct is "this is concurrency control firing as designed — surface it correctly and give the client a retry contract."

#### Q71. [Practical] How would you instrument a REST API for observability? What specific metrics, logs, and traces matter, and what's the RED method?

Observability for a REST API rests on three pillars — **metrics, logs, traces** — and the discipline is to instrument deliberately rather than log everything and hope. The canonical metrics framework for request-driven services is **RED**: **R**ate (requests/sec), **E**rrors (failed requests/sec, or error %), and **D**uration (latency distribution). RED is the right lens for an API because it captures exactly what a consumer experiences — how much traffic, how much of it fails, and how long it takes — and it's the complement to the **USE** method (Utilization, Saturation, Errors) which is for *resources* (CPU, pools, queues). I'd emit RED per endpoint and per status-code class so I can see, for example, that `POST /orders` 5xx rate just jumped.

```
Metrics (RED), tagged by route + method + status class:
  http_server_requests_total{route="/orders/{id}",method="GET",status="2xx"}   # Rate
  http_server_requests_total{...,status="5xx"}                                  # Errors
  http_server_request_duration_seconds (histogram -> p50/p90/p99/p99.9)         # Duration
Plus saturation (USE): db pool active/idle/pending, http-client pool, queue depth, GC.
```

For **logs**, structured (JSON) not free-text, one line per request with a consistent schema: timestamp, method, route *template* (`/orders/{id}` not `/orders/42` — so you can aggregate and so you don't leak ids/PII into log indexes), status, latency, caller/tenant id, and — critically — a **correlation/trace id** that ties the log line to the distributed trace and to any error returned to the client. That `traceId` is the same one you put in the `problem+json` error body (Q13), so when a customer files a ticket quoting the id, support can pull the exact request's logs and trace instantly. Never log request/response *bodies* by default (PII, secrets, volume); log them only behind a sampling/redaction flag for debugging.

For **traces**, use OpenTelemetry to create a span per inbound request and child spans for each DB query and downstream HTTP call, propagating context via `traceparent` (W3C Trace Context) across service boundaries. This is what lets you answer "*for this slow request specifically*, where did the time go?" (Q67) rather than guessing from aggregates. Sample intelligently — head-based sampling at a low rate for normal traffic plus tail-based sampling that *always keeps* slow or errored traces, so you capture the interesting ones without drowning in volume.

The connective tissue is the **correlation id flowing through all three pillars and out to the client**: generated (or accepted from an inbound `X-Request-Id`/`traceparent`) at the edge, stamped into the logging MDC, attached to spans, and echoed in error responses and ideally a response header. That single thread is what turns "the API is slow/erroring" into "this request, from this tenant, spent 4.2s waiting on the payment service's DB pool" in under a minute. The interview-grade points: RED for the request surface plus USE for resources; route *templates* not raw URIs as metric/log dimensions (cardinality and PII); structured logs keyed by trace id; OTel traces with context propagation and tail-sampling of slow/error traces; and dashboards/alerts built on the *distribution* (p99, error rate) rather than averages.

#### Q72. [Practical] A consumer says "your API started returning 502/504 errors intermittently" but your service logs show all 200s. What's happening and how do you find it?

The contradiction — the client sees `502 Bad Gateway`/`504 Gateway Timeout` but your application logs only `200`s — is the giveaway that the error is being generated **by an intermediary** (load balancer, reverse proxy, API gateway, or CDN) *between* the client and your app, not by your application code. A `502`/`504` is, by definition, a *gateway's* report that *it* couldn't get a good/timely response from upstream — so the failing component is the hop in front of your service, and your app may genuinely never see the failed requests (or sees them as successful but too slow). This immediately tells you to stop looking in app logs and start looking at the proxy/LB layer.

```
Client ──> CDN ──> LoadBalancer ──> API Gateway ──> Your Service ──> DB
                        ▲                  ▲
              504 here = LB's upstream     502 here = gateway got a
              (your svc) too slow OR       bad/closed connection from
              gateway timed out            your svc (crash, conn reset,
                                           pool exhausted, keep-alive race)
```

The distinct causes by code: **504 Gateway Timeout** means the proxy gave up waiting for your service — so either your service had a latency *tail* (the request that timed out is exactly the slow one that *did* eventually return 200 in your logs, after the gateway already bailed), or the gateway's upstream timeout is shorter than your real p99. The fix is to align timeouts (the gateway's upstream-read timeout must exceed your legitimate worst-case latency, *and* you should shave your tail per Q67). **502 Bad Gateway** means the proxy got a *broken* response — a common subtle cause is a **keep-alive idle-timeout race**: your service closes an idle connection at, say, 60s while the load balancer thinks it's still usable, sends a request on it, and gets a connection reset → 502. The fix is to make the **server's keep-alive timeout longer than the LB's idle timeout** so the LB always closes first. Other 502 causes: the service crashed/OOM'd mid-request, the upstream pool was exhausted so the gateway couldn't get a connection, or a response exceeded a proxy buffer limit.

The way to *find* it is to correlate across layers using a request id: ensure the LB/gateway stamps an `X-Request-Id` (or you propagate `traceparent`) and logs it alongside the upstream status and timing, then join the gateway's access logs (which *will* show the 502/504 with upstream timing) to your app logs by that id. The gateway access log is the key artifact — it records, per request, the upstream response time and upstream status, so you can see "gateway waited 30.0s, hit its timeout, returned 504, upstream eventually closed." Reproduce with `curl -w` timing (Q66) against the service directly vs. through the gateway to isolate which hop adds the failure. The interview-grade reasoning: a `5xx` whose first digit is "gateway" (`502`/`504`) plus clean app logs ⇒ the fault is the intermediary or the latency tail the intermediary can't tolerate; the fixes are aligning timeouts (gateway > app p99), ordering keep-alive timeouts (app > LB) to dodge connection-reuse races, and joining gateway-and-app logs by a shared request id to confirm.

#### Q73. [Coding] Implement a request-correlation-id filter that accepts an inbound id or generates one, puts it in the logging MDC, and echoes it on the response.

**Problem:** Every request must carry a correlation id that flows into logs and back to the client, so a single id ties together the client's error, your structured logs, and the distributed trace.

```java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)   // run before everything so all logs have the id
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        // Accept a client/gateway-supplied id if present and sane; else mint one.
        String incoming = req.getHeader(HEADER);
        String id = (incoming != null && isValid(incoming)) ? incoming
                                                            : UUID.randomUUID().toString();
        MDC.put(MDC_KEY, id);                 // every log line in this thread now carries it
        res.setHeader(HEADER, id);            // echo so the client can quote it in tickets
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.remove(MDC_KEY);              // CRITICAL: clear, or thread-pool reuse leaks ids
        }
    }

    // Don't trust arbitrary inbound strings: bound length, restrict charset
    // (prevents log injection / forging another request's id).
    private boolean isValid(String s) {
        return s.length() <= 64 && s.matches("[A-Za-z0-9._-]+");
    }
}
```

The non-obvious correctness points an interviewer probes. **Clearing the MDC in `finally` is mandatory**: servlet containers reuse worker threads, so if you don't remove the key, the *next* request handled by that thread inherits the previous request's id — a subtle, dangerous bug where logs attribute one request's lines to another. **Validating the inbound id** matters because it's untrusted input: an unbounded or special-character value lets a caller inject newlines into your logs (log forging) or spoof another trace; bound the length and restrict the charset. **Accepting an upstream id** (rather than always generating) is what makes the id work *across services* — the gateway generates it once and every downstream service propagates the same one, so the whole chain shares an id; you only mint a fresh one at the true entry point.

In a real system I'd align this with the W3C **Trace Context** standard (`traceparent`/`tracestate`) so it interoperates with OpenTelemetry tracing rather than a bespoke header — the OTel SDK manages the MDC/baggage propagation for you, and `X-Request-Id` becomes a human-friendly alias of the trace id. The payoff (tying back to Q71/Q72): this one id is the join key across logs, traces, the `problem+json` error body, and the response header, which is what collapses "production is acting up" into "this exact request, here's its full story" in seconds. **Complexity** is O(1) per request and negligible overhead.

#### Q74. [Practical] How do you safely roll out a change to a live REST API — versioning aside — using deployment strategies, feature flags, and contract checks?

"Versioning aside" reframes this as a *deployment safety* problem: even a backward-compatible change can carry a latent bug, so the goal is to expose the change to a small blast radius first, verify with real traffic, and be able to undo it instantly. The layered toolkit is **contract checks in CI → progressive deployment → feature flags → fast rollback**, and the art is using the cheapest mechanism that catches each class of problem before it reaches all users.

```
CI gate           Deploy strategy        Runtime control       Safety net
---------------   --------------------   -------------------   ----------------
OpenAPI diff      Canary (1% -> 5% ->    Feature flag wraps    Instant rollback
(oasdiff) FAILS   25% -> 100%) OR         new behavior; kill-  (canary: shift
on breaking       Blue/Green (flip all    switch toggles it    traffic back;
change.           traffic, flip back)     off w/o redeploy.     B/G: re-point).
Pact contract     watching error/latency
tests vs known    metrics at each step.
consumers.
```

**Contract checks first (shift left):** before anything deploys, CI runs an OpenAPI diff (e.g., `oasdiff`) that *fails the build* on a breaking change to a published surface, and consumer-driven contract tests (Pact) verify that known consumers still pass against the new provider. This catches "you accidentally removed/renamed a field" at PR time, which is the cheapest possible place to catch it. **Progressive delivery next:** a **canary** routes a small slice of live traffic (1%) to the new version while watching RED metrics (error rate, p99) and business KPIs; if the canary's error rate or latency regresses against the baseline, you halt and roll back having affected ~1% of users. **Blue/green** is the alternative — stand up the new version fully (green) alongside the old (blue), smoke-test green, flip the LB to green, and keep blue warm so rollback is an instant re-point — it trades canary's gradual exposure for a clean, instant cutover and rollback.

**Feature flags** decouple *deploy* from *release*: the new code path ships dark (behind a flag, off), so you can deploy safely at any time and then *enable* the behavior independently — ramp it per-tenant or per-percentage, and if it misbehaves, **flip the flag off without a redeploy** (a kill switch), which is far faster than a rollback. This is especially valuable for changes you can't fully test in staging (new query plans, cache strategies) — you turn them on for 1% of production and watch. The combination is what production teams actually do: contract diffing prevents the *breaking* class of change, canary/blue-green limit the blast radius of the *buggy* class, and flags give you a sub-second off-switch. The interview-grade synthesis: make every layer answer "how do I find out it's bad while only a few users are affected, and how do I undo it in seconds?" — CI contract checks (prevent breaking), canary/blue-green (limit exposure + fast rollback), feature flags (decouple release, instant kill switch), all gated on watching RED metrics at each ramp step.

### 🟠 Advanced — extended

#### Q75. [Practical] How do you load-test a REST API meaningfully, and what's the difference between throughput, latency, and the "coordinated omission" trap?

A meaningful load test answers a specific question — "what is the latency distribution at the throughput we expect at peak, and where does it break?" — not "how many requests can it do." The setup that matters: drive a **realistic traffic mix** (the actual ratio of read vs write endpoints, realistic payload sizes, realistic auth, realistic key distribution so caches behave like production), ramp load in steps while recording the **full latency distribution** (p50/p90/p99/p99.9) and **error rate** at each step, and push until you find the **knee** — the throughput at which latency degrades nonlinearly or errors appear. That knee is your real capacity; the peak RPS number alone is meaningless without the latency it came with.

```
Throughput  = requests successfully completed per second (capacity).
Latency     = time per request; report the DISTRIBUTION, never just the mean.
The relationship: as you push throughput toward saturation, latency rises
  sharply (queueing). The useful output is the curve, and the KNEE:

  latency
    |                         ___ p99 blows up here = the knee
    |                       /
    |__________________----'      <- usable region (latency flat)
    +------------------------------ throughput (RPS)
                              ^ capacity at acceptable p99
```

The subtle, frequently-tested pitfall is **coordinated omission**. Naive load tools send a request, *wait* for the response, then send the next — a "closed-loop" model. When the server stalls, the tool stops sending requests, so it *fails to record* the requests it *would have* sent during the stall — it omits exactly the samples that should have the worst latencies, making your p99 look far better than reality. Worse, a single 10-second stall that *should* count as thousands of slow requests gets recorded as one slow request. The fix is a load model that sends requests at a **fixed schedule (open-loop / constant arrival rate)** regardless of whether prior responses came back, so the latency of a stalled period is attributed to *all* the requests that piled up during it. Modern tools account for this: `wrk2` was built specifically to correct coordinated omission, and tools like k6/Gatling/Vegeta support a constant-arrival-rate (open) model — use those, and be suspicious of any p99 from a closed-loop test against a saturated server.

Beyond the methodology, the production-grade points: test against an environment that mirrors prod *including the intermediaries* (LB, gateway, real connection-reuse and TLS) because connection setup and pool limits often dominate; warm caches/JIT before measuring (a cold JVM lies); separate **soak tests** (sustained load for hours to surface leaks, GC creep, connection-pool drift) from **spike tests** (sudden surge to test load-shedding and autoscaling) from **stress tests** (push past the knee to see *how* it fails — does it shed load gracefully with 503s or fall over?). The interview-grade answer ties it together: report distributions not means, find the knee at acceptable p99, use an open-loop tool to avoid coordinated omission, include real intermediaries and warm state, and treat "how it degrades past capacity" (graceful 503 load-shedding vs. cascading collapse) as a first-class result — because production *will* eventually exceed capacity, and the failure mode is what determines whether that's a blip or an outage.

#### Q76. [Practical] Your API gateway is configured but requests behave unexpectedly — wrong backend, stripped headers, double-prefixed paths. How do you systematically debug gateway routing?

Gateway misbehavior is almost always a **configuration ordering or path-rewrite** problem, and the systematic approach is to make the gateway's *decisions* observable rather than guessing at the config. The first principle: a gateway evaluates routes by **predicates** (host, path, header, method) and applies **filters** (rewrite path, add/strip headers, strip prefix) — and most surprises come from (a) route *ordering* (a broad route matching before a specific one), (b) path rewriting (strip-prefix applied twice, or not at all), or (c) filters dropping headers. So I debug by answering, in order: *which* route matched, *what path* was forwarded, and *which headers* survived.

```
Symptom                     | Usual cause                         | Where to look
----------------------------|-------------------------------------|----------------------
Wrong backend / 404         | broad route ordered before specific | route order + predicates
                            | one; predicate too greedy           | ("/**" catching first)
Double-prefixed path        | client sends /api/orders, gateway   | StripPrefix / RewritePath
(/api/api/orders)           | forwards /api/orders to a svc that  | filter count vs. backend
                            | ALSO mounts under /api              | context-path
Missing /api prefix at svc  | StripPrefix=2 stripped too much     | StripPrefix arg
Auth header missing at svc  | gateway not forwarding Authorization| header filters / allowlist
                            | or stripping "sensitive" headers    |
CORS broken only via gw     | gateway intercepts OPTIONS itself   | global CORS vs route CORS
```

The concrete debugging moves. **Turn on the gateway's access/trace logging** so each request logs the matched route id, the rewritten upstream URI, and the upstream chosen — this single artifact answers "which route, what path, which backend" definitively. In Spring Cloud Gateway, enable `logging.level.org.springframework.cloud.gateway=TRACE` and the actuator `/actuator/gateway/routes` endpoint to dump the *effective* route table (predicates + filters as actually loaded), which catches config that didn't apply the way you thought. **Reproduce the rewrite isolation** with curl: hit the gateway with `-v` and compare the path/headers you sent to what the backend received (add a debug endpoint or check the backend's access log) — this instantly reveals double-prefixing or stripped headers.

```yaml
# Spring Cloud Gateway: order matters; specific routes first, StripPrefix tuned
spring:
  cloud:
    gateway:
      routes:
        - id: orders-v2                # specific route FIRST
          uri: lb://orders-service
          predicates: [ Path=/api/v2/orders/** ]
          filters: [ StripPrefix=2 ]   # strip "/api/v2" -> backend sees /orders/**
        - id: catchall                 # broad route LAST
          uri: lb://legacy
          predicates: [ Path=/** ]
```

The systematic discipline: never debug a gateway by reading the config and reasoning — *make it tell you what it decided* (effective route table + per-request trace of matched-route/rewritten-path/forwarded-headers), then bisect: does the request behave correctly when sent **directly to the backend** (proving the backend is fine and the gateway is the culprit)? Is the route table ordered specific-before-broad? Is the prefix-strip count correct for both the gateway mount and the backend's context-path? Are sensitive headers (`Authorization`, custom `X-*`) in the forward allowlist? The recurring root causes are route *ordering* (catch-all routes shadowing specific ones), *prefix arithmetic* (strip too much/too little, or the backend also adds the prefix → double prefix), and *header filtering* (the gateway "helpfully" strips headers it considers internal). Get observability on the decision first; the fix is then usually a one-line ordering or `StripPrefix` change.

#### Q77. [Practical] A REST endpoint that calls a downstream service is leaking/exhausting connections under load ("connection pool timeout", "too many open files", sockets in CLOSE_WAIT). Diagnose and fix.

These three symptoms are the classic signatures of **connection mismanagement**, and each points at a slightly different root cause, but they share a theme: connections aren't being *reused* or *released* properly. `connection pool timeout` (e.g., HikariCP's "Connection is not available, request timed out") means callers are *waiting* for a pooled connection that never frees up — the pool is too small for the load *or* connections are being held too long (slow downstream/DB, or leaked-and-not-returned). `Too many open files` means you've blown the OS file-descriptor limit because you're opening sockets faster than you close them — almost always **not reusing connections** (creating a new HTTP client / new connection per request) or leaking them. **Sockets stuck in `CLOSE_WAIT`** is the most diagnostic: `CLOSE_WAIT` means *the remote closed the connection but your application never called close()* — a definitive "you're leaking connections" signal, because the socket is waiting for *your* code to close its side.

```
Symptom               | Root cause                          | Fix
----------------------|-------------------------------------|-------------------------
pool timeout          | pool too small OR conns held too    | size pool to (latency x
(waiting for a conn)  | long (slow downstream, no timeout)  | RPS); add downstream
                      |                                     | timeout; fail fast
too many open files   | new client/conn per request         | reuse ONE pooled client
(FD exhaustion)       | (no connection reuse / keep-alive)  | (singleton), raise ulimit
CLOSE_WAIT pileup     | responses/clients not closed ->     | close response bodies in
                      | your side never closes the socket   | finally / try-w-resources
```

The diagnosis sequence: `ss -tan state close-wait` (or `netstat`) to count `CLOSE_WAIT` sockets — a growing count proves a leak; `lsof -p <pid> | wc -l` to watch file-descriptor growth over time (steadily climbing = leak; flat-but-high = undersized reuse); and the pool's own metrics (HikariCP exposes active/idle/pending; Apache HttpClient/OkHttp pool stats) to see whether `pending` (waiters) is nonzero under load. The **fix for leaks** is to guarantee release on every path — with most HTTP clients you must consume/close the response body even on errors, so use try-with-resources or a `finally`, and prefer high-level clients (`WebClient`, `RestClient`, OkHttp) that manage this for you over hand-rolled `HttpURLConnection` where it's easy to forget.

```java
// LEAK: a new client per call (no reuse) -> FD exhaustion; body not closed -> CLOSE_WAIT
// FIX: one shared, pooled, connection-reusing client + timeouts; framework manages release.
@Bean
public RestClient downstreamClient() {
    HttpComponentsClientHttpRequestFactory f = new HttpComponentsClientHttpRequestFactory(
        HttpClients.custom()
            .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(100).setMaxConnPerRoute(50)   // bounded, reused
                .build())
            .evictIdleConnections(TimeValue.ofSeconds(30))     // reap idle to dodge keep-alive races
            .build());
    f.setConnectTimeout(Duration.ofSeconds(2));
    f.setReadTimeout(Duration.ofSeconds(3));                   // never infinite
    return RestClient.builder().requestFactory(f).build();     // singleton, reused everywhere
}
```

The structural lessons. **Reuse one pooled client** (a singleton) so connections are kept alive and recycled instead of opened per request — creating a fresh `HttpClient`/`WebClient` per call is the most common cause of FD exhaustion. **Always set connect and read timeouts** — an infinite read timeout means a slow downstream holds a pooled connection forever, draining the pool (this is *why* a downstream slowdown shows up as *your* pool exhaustion). **Size the pool from Little's Law**: required connections ≈ throughput × average hold-time; if a downstream call takes 100ms and you do 500 RPS, you need ~50 in-flight, so a pool of 50 fully utilized and 0 headroom will queue — provision for peak with margin. **Add idle eviction** so connections that the remote will silently drop (keep-alive idle timeout) get reaped before you try to reuse a dead one (which also causes intermittent failures). And pair the pool with a **circuit breaker/timeout** so a sick downstream sheds load rather than backing up your pool. The interview-grade synthesis: `CLOSE_WAIT` ⇒ you're not closing (leak — fix release paths); `too many open files` ⇒ you're not reusing (per-request clients — use one pooled client); `pool timeout` ⇒ pool too small or held too long (size via Little's Law and add downstream timeouts so slow dependencies fail fast instead of draining the pool).

#### Q78. [Practical] How would you migrate consumers off a deprecated API endpoint that thousands of clients still call, when you can't contact most of them?

The constraint — you can't reach most callers — flips this from a communication problem into an **instrumentation and incremental-pressure** problem: you must *discover* who's calling, give them every passive signal to migrate, measure the decline, and only then sunset, ideally never doing a hard cutoff you can't reverse. The first and non-negotiable step is **measure before you touch anything**: instrument the deprecated endpoint to emit per-caller usage (by API key/client id, user agent, IP) so you know *who*, *how much*, and *which exact features* they use. You cannot plan a migration against unknown usage, and "I think nobody uses it" is how you cause an outage.

```
Phase            | Action                                          | Signal to clients
-----------------|-------------------------------------------------|--------------------------
1. Instrument    | log/metric per caller (key, UA, IP) on the old  | (none yet)
                 | endpoint; identify top N consumers              |
2. Announce      | Deprecation + Sunset headers on EVERY response   | RFC 8594 headers,
                 | of the old endpoint; changelog; docs; emails to  | Link: rel="successor-
                 | the callers you CAN reach (the top N matter most)| version", Warning
3. Pressure      | proactive outreach to top consumers (they're     | dashboards show decline
                 | most of the volume); track usage decline         |
4. Brownouts     | scheduled short outages of the old endpoint      | brief 410/error windows
                 | (e.g., 5 min) to surface clients that ignore     | force latent callers to
                 | headers/emails -> their on-call notices          | notice and reach out
5. Sunset        | hard cutoff only after usage ~0 OR contractual   | 410 Gone / 404
                 | window expired; keep a documented rollback       |
```

The machine-readable signals do the heavy lifting when you can't talk to people. Attach **`Deprecation`** (the date/flag it became deprecated) and **`Sunset`** (RFC 8594 — the date it will stop working) headers to *every* response from the old endpoint, plus a `Link: <...>; rel="successor-version"` pointing at the replacement and a `Warning` header — well-behaved clients and SDKs log or surface these, and savvy integrators notice them in their own logs. Publish the same in the changelog and docs. For the callers you *can* identify from instrumentation, reach out directly — and the key leverage point is that **traffic is almost always Pareto-distributed**: the top handful of consumers are 80–90% of the volume, so migrating the few you *can* contact eliminates most of the risk, leaving a long tail of low-volume callers.

For that stubborn long tail that ignores headers and emails, the production technique is **brownouts** (a.k.a. scheduled "dark launches" of the deprecation): deliberately make the old endpoint fail for short, pre-announced windows (e.g., 5 minutes, escalating in frequency/duration over weeks). A brownout is the only signal that reliably reaches a client whose developers have left — *something breaks briefly*, their on-call gets paged, and *they* contact *you*, which is exactly the contact you couldn't initiate. You watch usage fall after each brownout. Only when usage approaches zero (or your published/contractual deprecation window has formally expired) do you do the hard sunset, returning `410 Gone` (precise: "this existed and is intentionally, permanently removed" — Q32) with a `problem+json` body pointing to the replacement, and you keep the ability to *roll back* the sunset in case a critical caller surfaces. The interview-grade reasoning: you can't migrate who you can't see, so instrument first; you can't break who you can't warn, so use `Deprecation`/`Sunset` headers as the passive broadcast channel; you can't reach everyone individually, so exploit the Pareto distribution (contact the heavy hitters) and use brownouts to make the silent tail self-identify; and you sunset behind a reversible switch only after the data says usage is gone — never a surprise hard cutoff against unknown callers.

#### Q79. [Practical] How do you decide and configure HTTP caching policy per endpoint for a real API — what Cache-Control directives go on a user profile vs. a public catalog vs. a bank balance?

The decision is driven by three questions per endpoint: **is the response shared or per-user (cacheable by a shared cache or not)? how stale can it tolerably be? and is it sensitive?** Those answers map directly onto `Cache-Control` directives. Getting this wrong is not a micro-optimization — under-caching wastes capacity and money, while over-caching either serves stale data or, in the worst case, serves one user's data to another. So I reason about each endpoint's *semantics*, not a blanket default.

```
Endpoint                | Shared? | Staleness OK?  | Sensitive? | Cache-Control
------------------------|---------|----------------|-----------|----------------------------
Public catalog / product| yes     | seconds-mins   | no        | public, max-age=60,
(same for everyone)     |         |                |           | s-maxage=300,
                        |         |                |           | stale-while-revalidate=30
User profile / dashboard| no      | a little (own  | mildly    | private, max-age=30
(per-user)              |         | data)          |           | (NEVER shared cache)
Bank balance / payment  | no      | NO             | YES       | no-store
List w/ frequent change | maybe   | must revalidate| varies    | no-cache + ETag
                        |         |                |           | (cache but ALWAYS revalidate)
Static asset (versioned | yes     | immutable      | no        | public, max-age=31536000,
URL, hashed filename)   |         |                |           | immutable
```

Walking the examples: a **public catalog** is identical for every user and tolerates short staleness, so it's the highest-leverage cache — `public` (shared caches/CDNs may store it), a short `max-age` for browsers, a longer `s-maxage` so the CDN holds it even longer, and `stale-while-revalidate` so the CDN can serve the slightly-stale copy *instantly* while refreshing in the background (hiding origin latency from users). This is what lets a CDN absorb 95%+ of catalog reads (Q28's high-throughput pattern). A **user profile** is per-user, so it must be `private` — *never* stored by a shared cache, or the CDN could serve user A's profile to user B — with a small `max-age` so the user's own browser caches it briefly; pair with an `ETag` so a refresh is a cheap `304`. A **bank balance** is both per-user *and* sensitive *and* must never be stale, so it's `no-store`: no cache, anywhere, ever — you don't want it in the browser's disk cache, a corporate proxy, or back-button history.

Two distinctions interviewers test. **`no-cache` ≠ `no-store`**: `no-store` means "never write this down anywhere"; `no-cache` means "you *may* store it, but you must *revalidate* with the origin (via `ETag`/`If-None-Match`) before every reuse" — so `no-cache` + `ETag` is the right choice for data that changes often but where a `304` is much cheaper than re-sending the body. **`private` vs `public`**: `private` permits the end-user's *own* browser cache but forbids *shared* caches (CDN/proxy), which is the critical control for per-user data; forgetting it (or relying on a default) is how personalized responses leak across users in a shared cache. Always send `Vary` for anything negotiated (Q34), and always pair cacheable responses with validators (`ETag`/`Last-Modified`) so even after `max-age` expires the revalidation is a cheap `304` rather than a full transfer. The interview-grade synthesis: classify each endpoint by shared-vs-per-user, staleness tolerance, and sensitivity; `public + s-maxage + stale-while-revalidate` for shared cacheable data (let the CDN do the work), `private + small max-age + ETag` for per-user data, `no-store` for sensitive data, and `no-cache + ETag` for volatile-but-cacheable data — never a one-size default, because the failure modes (stale data, leaked data, wasted capacity) are all endpoint-specific.

#### Q80. [Practical] A pull request adds a field to a response and removes another "unused" one. Walk through reviewing this for backward compatibility — what's safe, what's a breaking change, and how do you catch it automatically?

The instinct an interviewer wants is to treat *every* change to a published response or request shape as a compatibility question, and to know the asymmetry: **adding** to a response is almost always safe; **removing, renaming, retyping, or tightening** is almost always breaking. The PR described does one of each — adding a field (safe) and removing a field someone *believes* is unused (the dangerous one, because "unused" is an assumption about consumers you can't see). My review starts by separating the two and applying the compatibility rules, then insisting the "unused" claim be *proven*, not assumed.

```
Change to RESPONSE                         | Compatible? | Why
-------------------------------------------|-------------|---------------------------
Add a new optional field                   | SAFE        | tolerant readers ignore it
Remove a field                             | BREAKING    | consumers parsing it break
Rename a field                             | BREAKING    | = remove + add
Change a field's type (int -> string)      | BREAKING    | deserialization fails
Change semantics/units (cents -> dollars)  | BREAKING    | silent data corruption (worst!)
Add an enum value                          | RISKY       | strict consumers may reject
Make a previously-always-present field null| BREAKING    | NPEs in consumers
Tighten a value range / shorten max length | BREAKING    | for REQUESTs esp.

Change to REQUEST                          |
-------------------------------------------|-------------|---------------------------
Add a new OPTIONAL request param/field     | SAFE        | old clients omit it
Add a new REQUIRED request param           | BREAKING    | old clients don't send it
Remove a request field                     | usually safe| old clients send ignored data
Loosen validation (accept more)            | SAFE        | superset of old
Tighten validation (accept less)           | BREAKING    | old valid requests now rejected
```

So for this PR: the **added field** is fine *provided* it's optional and additive (and you're not, say, adding a required field to a *request*). The **removed field** is a **breaking change to the published contract** unless you can demonstrate zero consumers depend on it — and "the team thinks it's unused" is not evidence. The correct move is to *measure*: instrument and log reads/serialization of that field (or analyze access patterns / consumer contracts) over a meaningful window before removing it; if you can't prove zero usage, you don't remove it — you **deprecate** it (mark it deprecated in the OpenAPI/docs, keep returning it, announce a sunset), and remove it only after a deprecation window with usage at zero. Removing a field is also a one-way door that, if wrong, breaks integrators silently — so the bar for "prove it's unused" is high. The especially insidious case I'd flag in review is any *semantic* change (units, meaning) disguised as non-breaking — changing `total` from cents to dollars keeps the field and type but silently corrupts every consumer; those are worse than removals because nothing errors.

The point of the answer is that this should not rely on a human catching it. **Automate the gate**: a spec-first workflow keeps the OpenAPI document as source of truth, and CI runs an **OpenAPI diff tool** (`oasdiff`, `openapi-diff`) that **fails the build on a breaking change** to a published version — field removal, type change, new required request param, tightened validation all get flagged mechanically. Layer **consumer-driven contract tests (Pact)** so the provider's CI verifies it still satisfies the recorded expectations of known consumers — that catches "this field that you removed is in consumer X's contract." Together these turn backward-compatibility from a reviewer's vigilance (fallible) into a CI gate (reliable). The interview-grade synthesis: additive-to-response and optional-to-request are safe, everything that removes/renames/retypes/tightens/changes-meaning is breaking, "unused" must be *proven by instrumentation* not assumed, the safe path for removal is deprecate-with-sunset-then-remove-after-usage-hits-zero, and the whole thing should be enforced by `oasdiff` breaking-change gates plus Pact contract tests in CI rather than left to a human reading the diff.

#### Q81. [Practical] Clients on slow/mobile networks report timeouts and large payloads. What concrete REST-side techniques reduce payload size and round-trips, and how do you measure the win?

Mobile/high-latency networks punish two things specifically — **large payloads** (bandwidth-bound, and radio energy) and **many round-trips** (latency-bound, and each round-trip pays RTT + possibly TLS) — so the techniques split into "send fewer bytes" and "make fewer/cheaper round-trips," and you measure the win on the *client-perceived* metrics (time-to-data over a throttled link), not server-side latency. The first move is to *measure where the cost is*: is the response 2 MB (payload-bound) or is the screen making 8 sequential calls (round-trip-bound)? They have different fixes.

```
Goal: fewer BYTES                         Goal: fewer / cheaper ROUND-TRIPS
-----------------------------------       --------------------------------------
- Compression (Brotli/gzip) on JSON       - Opt-in expansion (?expand=customer)
  (often 70-90% smaller; JSON is          - Batch / multi-get (GET /x?ids=a,b,c)
  highly compressible)                     - HTTP/2 or HTTP/3 multiplexing (many
- Sparse fieldsets (?fields=id,total)       small calls cheap over 1 connection)
  so the client fetches only what it       - Conditional GET + ETag -> 304 (no body
  renders                                    re-transfer on unchanged data)
- Pagination with sane page caps          - Caching (Cache-Control) so repeat
- Drop nulls / verbose envelopes            views skip the network entirely
- Right-size: don't return the whole       - 0-RTT / connection reuse (keep-alive)
  object graph by default                    to avoid repeated TLS handshakes
```

Concrete, high-leverage REST techniques: **enable Brotli (gzip fallback) for text/JSON above a size threshold** with `Vary: Accept-Encoding` — JSON is verbose and highly compressible, so this is often a 70–90% byte reduction for near-zero design cost (Q48). **Sparse fieldsets** (`?fields=id,status,total`) let a list screen fetch three fields instead of the whole object, cutting both serialization and transfer. **Sane page caps** prevent a list endpoint from dumping thousands of rows to a phone. On the round-trip side, **opt-in expansion** (`?expand=customer,items`) collapses an API-level N+1 (Q61) into one response when a screen genuinely needs related data, and **batch/multi-get** endpoints turn N reference-dereferences into one call. **Conditional GET with `ETag`** is a huge mobile win: a revalidation that returns `304 Not Modified` transfers *no body*, so a phone re-opening a list it already cached pays only headers. And on modern transports, **HTTP/2/3 multiplexing** makes several small parallel calls cheap over one connection, while **connection reuse / 0-RTT** (HTTP/3/QUIC) avoids re-paying the TLS handshake on every call — a real saving on lossy mobile links.

Crucially, **measure the win the way the user experiences it**, not on a fast office network: throttle to a realistic mobile profile (e.g., DevTools "Slow 3G/4G" — added RTT + capped bandwidth) and measure **time-to-first-byte and time-to-full-render of the screen's data**, payload bytes on the wire (compressed), and number of round-trips before content appears. `curl -w` (Q66) gives per-request timing breakdown to confirm compression and connection reuse are actually happening; WebPageTest/Lighthouse under throttling give the end-to-end screen metric. The discipline is to (1) classify the problem as payload- vs round-trip-bound, (2) apply the matching lever (compression + sparse fields + page caps for bytes; expansion + batch + HTTP/2/3 + conditional GET + caching for round-trips), and (3) prove the improvement on a *throttled, high-latency* profile measuring client-perceived time-to-data and compressed wire bytes — because a "win" measured on gigabit office wifi tells you nothing about the user on a train. The interview-grade nuance: don't over-bundle reflexively — on HTTP/2+ many small cacheable calls can beat one giant uncacheable mega-response, so the right answer depends on what the *measurement* says is the bottleneck.

#### Q82. [Practical] How do you handle and design pagination so that adding/deleting items mid-pagination doesn't cause clients to skip or duplicate records in production?

The stability-under-concurrent-writes problem is precisely where **offset pagination quietly corrupts results** and where cursor (keyset) pagination earns its keep — and in production this manifests as users reporting "I saw the same row twice" or "a record I know exists never appeared." The mechanism: with `OFFSET 40 LIMIT 20` (page 3), the offset is computed against the *current* state of the table at the moment each page is fetched. If 5 rows are inserted *before* the user's current position between fetching page 2 and page 3, every row shifts down by 5 — so page 3 re-shows rows the user already saw on page 2 (**duplicates**); if 5 rows are *deleted* before their position, rows shift up and 5 records get **skipped** entirely, never shown.

```
Offset pagination, items inserted between page fetches:
  t0: page 1 = rows [A B C D E], offset moves to 5
  t1: 2 new rows X,Y inserted at the top -> table = [X Y A B C D E ...]
  t2: page 2 = OFFSET 5 = [B C D E F]   <- B,C,D,E DUPLICATED (already saw them)

Cursor pagination, same insert:
  t0: page 1 = [A B C D E], nextCursor = encode(E)
  t1: X,Y inserted at top
  t2: page 2 = WHERE (sortkey) < cursor(E) = [F G H I J]  <- STABLE, no dup/skip
      (the cursor anchors to E's position, immune to inserts elsewhere)
```

The robust fix is **cursor/keyset pagination** (Q9): the "next page" request carries an opaque cursor encoding the *last-seen item's sort key + tiebreaker* (e.g., `(created_at, id)`), and the next query is `WHERE (created_at, id) < (?, ?) ORDER BY created_at DESC, id DESC LIMIT n`. Because the cursor anchors to a specific *row position in the sort order* rather than a numeric offset, inserts and deletes elsewhere in the table don't shift the window — you always continue from "after the last row I actually saw," so you can't skip or duplicate. The `(sortkey, id)` *tiebreaker is mandatory*: if you cursor on `created_at` alone and two rows share a timestamp, you can lose or duplicate them at the page boundary — the unique `id` tiebreaker guarantees a total, stable order. This is exactly why Stripe, Slack, and X use cursors for high-throughput feeds.

For UIs that genuinely need page *numbers* or total counts (admin tables), where cursor pagination's lack of random access is a problem, the production techniques are: **snapshot/point-in-time consistency** — capture a stable view (a snapshot timestamp the client passes back, e.g. `?asOf=<ts>` filtering to rows as of that instant, or a DB MVCC snapshot/`REPEATABLE READ` for the duration) so all pages reflect one consistent moment; or accept the inconsistency for low-churn admin data where the cost is cosmetic. The decision framework: **cursor pagination is the default for any high-write, user-facing, or infinite-scroll list** because it's stable under concurrent mutation *and* O(1) at depth; **offset only for small, low-churn, admin-style lists that need page numbers**, ideally with a snapshot to bound the inconsistency. The interview-grade synthesis: duplicates/skips under concurrent writes are an inherent offset-pagination defect (offset is recomputed against a moving table), the cure is keyset cursors anchored to a stable `(sortkey, unique-id)` position with a mandatory tiebreaker, and when page numbers are non-negotiable you bolt on point-in-time snapshot consistency rather than accepting silent data loss.

### 🔴 Expert — extended

#### Q83. [Practical] Design the operational playbook for a sudden 10x traffic spike to a REST API (flash sale, viral event). What load-shedding, prioritization, and graceful-degradation mechanisms do you put in place ahead of time?

The premise of the answer is that you cannot *add* resilience during the spike — the playbook must be **pre-installed defenses that activate automatically** plus a small set of human levers. The governing principle is **protect the core, shed the rest**: when demand exceeds capacity, the worst outcome is a total collapse where *everyone* gets errors; the goal is graceful degradation where the system stays up serving its most important traffic and *deliberately* rejects or downgrades the rest. So the design is layered from the edge inward, each layer absorbing as much as it can before load reaches the fragile origin.

```
Edge (CDN)          Gateway              Service                 Data
-----------------   ------------------   ---------------------   ----------------
- cache GETs hard   - rate limit per     - load shed: return     - read replicas
  (catalog) so 95%   caller/tenant        503 + Retry-After       absorb read spike
  never hits origin - admission control    FAST when over cap    - circuit breakers
- stale-while-       (queue bound; reject - priority tiers:        to non-critical
  revalidate +       excess at the door)   serve checkout/pay,     deps; serve
  stale-if-error    - concurrency limit    shed nice-to-haves      cached/default
  (serve stale if   - separate read/write - graceful degrade:      on dep failure
  origin is dying)    buckets              feature-flag off
                                           expensive features
```

The mechanisms, in order of leverage. **Cache at the edge aggressively** — for a flash sale the product/catalog pages are identical across users and tolerate seconds of staleness, so a CDN with a short `s-maxage` + `stale-while-revalidate` absorbs the overwhelming majority of reads, and `stale-if-error` keeps serving the last-good copy even if the origin starts failing (Q79). This alone often turns a 10x spike into a manageable origin load. **Admission control / load shedding at the gateway**: rather than accepting unlimited requests and letting them queue until everything times out (the metastable-collapse failure mode), bound the inbound queue and **reject excess requests immediately with `503` + `Retry-After`** — fast rejection is *kinder* than slow timeout because it keeps the accepted requests fast and tells clients to back off deterministically. Pair with **per-tenant rate limits** so one customer can't starve others, and **concurrency limits** so a fixed number of in-flight requests is enforced regardless of arrival rate.

**Prioritization / request hedging by importance**: not all endpoints are equal during a sale — *checkout and payment must succeed* while *recommendations, reviews, and analytics are sacrificial*. Pre-classify endpoints into priority tiers and shed low-priority traffic first (or route them to a separate pool that can be starved without touching the critical path), so when capacity is scarce the revenue-critical flow is protected. **Graceful degradation via feature flags** lets you pre-wire kill switches for expensive features (personalization, heavy aggregations, `?expand=` fan-outs) that you flip *off* during the spike to cut per-request cost, returning a simpler/cached response instead of failing. **Circuit breakers** to non-critical dependencies mean a struggling recommendation service trips its breaker and the page renders without recommendations rather than hanging. Underneath, **read replicas and a CQRS read model** absorb the read spike without touching the write path, and writes (orders) get their own protected capacity.

The operational layer ties it together: **autoscaling** helps but is too slow for a sudden spike (instances take minutes to warm), so the edge cache + load-shedding must hold the line during the scale-up window — pre-warm/pre-scale ahead of a *known* event (a scheduled sale). Have a **runbook with human levers** (raise/lower rate limits, flip degradation flags, drain a bad instance) and **dashboards on RED + saturation** so on-call sees the knee coming. And critically, **load-test the failure mode beforehand** (Q75 stress test) to confirm the system sheds load gracefully — returning fast 503s and protecting checkout — rather than collapsing, because the spike is the wrong time to discover your load-shedding doesn't work. The interview-grade synthesis: pre-install layered defenses (edge caching with stale-serving → gateway admission control/rate/concurrency limits with fast `503`+`Retry-After` → service-level priority tiers, feature-flag degradation, and circuit breakers → replica/CQRS reads), because resilience is built before the spike; the philosophy is *protect the critical path and shed the rest fast* (graceful degradation beats total collapse), autoscaling backstops but can't react in time, and you must have *tested the degraded mode* so it behaves as designed under real overload.

#### Q84. [Practical] A pen-test flags that `/orders/{id}` lets an authenticated user read another user's order by changing the id (BOLA/IDOR). How did this happen, how do you fix it across the codebase, and how do you prevent regressions?

BOLA/IDOR (Broken Object Level Authorization — OWASP API1, the #1 API vulnerability) happened because the code conflated **authentication** ("you have a valid token, so you're logged in") with **object-level authorization** ("*this specific* logged-in user is allowed to access *this specific* object"). The endpoint checked the first and silently skipped the second — it took the `{id}` from the URL, fetched that order, and returned it without ever verifying the caller *owns* order `{id}`. Because ids are often sequential or guessable, an attacker just increments the id and reads everyone's orders. The insidious part is that the endpoint *works perfectly* for legitimate use (you only ever request your own ids in the UI), so the hole is invisible until someone tampers with the id — which is exactly what a pen-test does.

```java
// VULNERABLE: authenticated, but no per-object authorization
@GetMapping("/orders/{id}")
public OrderResponse get(@PathVariable String id) {      // any logged-in user...
    return OrderResponse.from(repo.findById(id));        // ...reads ANY order
}

// FIXED: authorization scoped to the authenticated principal
@GetMapping("/orders/{id}")
public OrderResponse get(@PathVariable String id, @AuthenticationPrincipal User caller) {
    Order order = repo.findById(id).orElseThrow(NotFoundException::new);
    if (!order.getOwnerId().equals(caller.getId())) {    // OBJECT-LEVEL authZ
        throw new NotFoundException();   // 404, not 403: don't confirm the order exists
    }
    return OrderResponse.from(order);
}

// BETTER (defense in depth): scope the QUERY so you can't even load others' data
//   repo.findByIdAndOwnerId(id, caller.getId()).orElseThrow(NotFoundException::new);
```

The fix has a per-endpoint part and a systemic part. Per-endpoint: **every** access to an object identified by a client-supplied id must verify the authenticated principal is authorized for *that object* — not just authenticated. The stronger pattern is to **scope the data-access query itself to the owner** (`findByIdAndOwnerId`, or a tenant-scoped repository / row-level security in the DB), so it's *impossible* to load another user's row even if a later refactor forgets the explicit check — the authorization is enforced at the data layer, not bolted on in the controller. Note the deliberate choice to return **404, not 403**, on an ownership mismatch: returning 403 confirms "this order exists but isn't yours," leaking the existence of other users' resources (an enumeration oracle); 404 reveals nothing (Q32).

Fixing it *across the codebase* is the senior part of the answer, because BOLA is rarely one endpoint — it's a *pattern* of missing checks. So: **audit every endpoint that takes a resource id** (or any client-supplied object reference, including ids in the body, in filters, in `expand` params, in batch operations) and confirm each enforces object-level authZ; the systematic prevention is to **centralize authorization** so it can't be forgotten — a policy layer / `@PreAuthorize` with an ownership check, an authorization filter, tenant-scoped repositories, or database row-level security, so "fetch object X" *cannot* succeed without the ownership predicate. Defense in depth adds **non-guessable ids** (UUIDs/opaque ids instead of sequential integers) so enumeration is impractical — but this is *not a fix on its own*; it's a speed bump, and the real control is the authorization check, because "security through unguessable ids" fails the moment an id leaks (logs, referrals, shared links).

Preventing regressions is what an interviewer is really listening for: **make BOLA un-reintroducible by default and tested.** Add **automated authorization tests** to the suite — for every object endpoint, a test where user B requests user A's resource and *must* get 404/403 (this is cheap and catches the entire class on every PR). Bake object-level authZ into the **standard access pattern** (the repository/service layer always takes the principal and scopes the query) so a developer can't accidentally write an unscoped fetch. Add **SAST/DAST** rules and include IDOR probes in CI security scanning. And treat the pen-test finding as a prompt to **threat-model the whole API surface** for the BOLA pattern rather than patching the single reported endpoint. The interview-grade synthesis: the root cause is authentication-without-object-authorization; the fix is per-object ownership checks ideally enforced by *scoping the query to the principal* (and returning 404 not 403 to avoid an existence oracle); the systemic remediation is auditing every id-taking endpoint and centralizing authZ so it's enforced by default (tenant-scoped repos / row-level security); and regression prevention is negative authorization tests in CI plus making the secure access pattern the only easy one — with unguessable ids as defense-in-depth, never as the primary control.

#### Q85. [Practical] You're adopting an API gateway in front of services that previously talked directly. What cross-cutting concerns move to the gateway, what must stay in the service, and what new failure modes appear?

The gateway's value proposition is consolidating **cross-cutting, edge-appropriate concerns** in one place so individual services don't each re-implement them — but the discipline is knowing what genuinely belongs at the edge versus what must stay in the service because it requires business context the gateway doesn't have. Putting the wrong thing at the gateway either creates a bottleneck/single-point-of-failure or, worse, creates a *false* sense of security by moving a check to a layer that can be bypassed.

```
MOVE to the gateway (edge cross-cutting)   KEEP in the service (needs business context)
----------------------------------------   --------------------------------------------
- TLS termination                          - Object-level authorization (BOLA: "does
- Authentication (validate token/JWT sig,    THIS user own THIS order?" — gateway can't
  reject obviously-bad tokens)               know ownership) — Q84
- Coarse rate limiting / quota per caller  - Business validation (422 semantics)
- Request routing / path rewrite           - Domain logic, transactions, idempotency
- Coarse-grained authZ (scope/role gates)  - Per-field / property-level authZ
- Request/response logging, tracing,       - Data shaping specific to the resource
  metrics, correlation-id injection        - Anything requiring the data model
- CORS, compression, request size limits   - Fine-grained input validation tied to
- Caching of cacheable GETs                  domain rules
- WAF, IP allow/deny, bot mitigation
```

What **moves** to the gateway: TLS termination, **authentication** (verify the token signature/expiry and reject garbage at the edge so bad traffic never reaches services), **coarse authorization** (scope/role checks — "this token has the `orders:read` scope"), **rate limiting and quotas** per API key/tenant, **routing and path rewriting**, **observability** (access logs, distributed-trace context propagation, correlation-id stamping — Q73), CORS, compression, request-size caps, and edge **caching**. These are exactly the concerns that are identical across services and benefit from a single chokepoint. What **must stay** in the service: **object-level and property-level authorization** (the gateway cannot know whether user X owns order 42 — that requires the data model, so BOLA defense lives in the service — Q84), **business/semantic validation** (422-class rules), **transactions, idempotency, and domain logic**, and any data-shaping tied to the resource. The cardinal error is assuming "the gateway does auth" means services can trust their input blindly — the gateway does *coarse* auth; the service still owns *fine-grained, context-dependent* authZ, and services must enforce **defense in depth** (never assume the gateway is the only entry point — internal callers, service mesh, or a misconfigured route can reach services directly).

The **new failure modes** are the part experienced engineers emphasize, because the gateway is now a shared, critical component: (1) **single point of failure / bottleneck** — every request flows through it, so it must be HA, horizontally scaled, and itself rate-limited; a gateway outage is a *total* outage. (2) **New latency hop** — an extra network hop and processing adds latency to every call (mitigate with connection reuse, keep timeouts aligned per Q69). (3) **Timeout/keep-alive mismatches** between gateway and services causing intermittent `502`/`504` (Q72 — the keep-alive race and timeout-ordering problems). (4) **Config-as-the-new-bug-surface** — routing/rewrite/header-filter misconfiguration silently sends traffic to the wrong place or strips headers (Q76), and a bad config deploy can break everything at once. (5) **Header trust boundary** — services may now trust gateway-injected headers (e.g., `X-Authenticated-User`), which is dangerous if a client can reach the service directly and *spoof* that header; you must strip client-supplied copies of those headers at the gateway and ideally network-isolate services so the gateway is the only ingress. (6) **Observability gap** — the gateway and services log separately, so you need shared correlation ids to join them (Q72/Q73), or incidents become un-debuggable.

The interview-grade synthesis: move *edge, context-free* cross-cutting concerns to the gateway (TLS, authN, coarse authZ/rate-limiting, routing, observability, CORS, caching, WAF) to stop every service reinventing them; *keep* anything needing the data model or business context in the service (object/property-level authZ — BOLA can't be solved at the edge — domain validation, transactions, idempotency), enforcing defense in depth rather than trusting the gateway as the sole guard; and plan for the new failure modes the gateway introduces — it's a shared SPOF/bottleneck adding a latency hop, a new timeout/keep-alive mismatch surface, a config-error blast radius, and a header trust boundary that must be locked down (strip spoofable gateway headers, network-isolate services) with shared correlation ids to keep the now-multi-hop path debuggable.

#### Q86. [Practical] How do you design, secure, and operate webhooks (your API calling consumers' endpoints) for production reliability — signing, retries, idempotency, ordering, and the "thundering consumer" problem?

Webhooks invert the usual direction — *your* API becomes an HTTP *client* calling the consumer's endpoint — which inherits every reliability and security problem of a distributed call *plus* the fact that you don't control or trust the receiver. A production webhook system is therefore a small, opinionated delivery platform, not a `POST` in a loop, and the design has to answer: how does the receiver trust the payload, what happens when delivery fails, how do you avoid duplicate/out-of-order side effects on the receiver, and how do you avoid taking *yourself* (or the receiver) down. The non-negotiable foundation is **at-least-once delivery with retries**, because the network is unreliable and the receiver may be briefly down — which immediately implies receivers must be **idempotent** (Q15/Q54), since they *will* occasionally get the same event twice.

```
Delivery pipeline (decoupled from the triggering request):
  event happens -> write to OUTBOX (same tx as the state change) -> queue ->
  delivery worker -> POST to consumer URL with:
     X-Webhook-Id: evt_123              (stable id -> receiver dedupes = idempotency)
     X-Webhook-Timestamp: 1718500000    (in the signed payload -> replay protection)
     X-Webhook-Signature: sha256=<HMAC(secret, timestamp + "." + body)>
  on 2xx -> mark delivered
  on failure/timeout -> exponential backoff + jitter, bounded attempts ->
     dead-letter after N -> expose a manual "resend" + a delivery log to the consumer
```

**Security (signing):** the receiver must verify the request genuinely came from you and wasn't tampered with or replayed. The standard is an **HMAC signature** over the raw body (plus a timestamp) using a per-consumer shared secret, sent in a header (`X-Webhook-Signature: sha256=...`); the receiver recomputes the HMAC and compares with a **constant-time** comparison. Include and sign a **timestamp** and have receivers reject signatures older than a few minutes to defeat **replay attacks** (an attacker resending a captured valid request). Provide secret **rotation** (support two valid secrets during a rollover). Also defend *the receiver's network*: webhooks are a classic **SSRF** vector — a malicious consumer could register `http://169.254.169.254/...` (cloud metadata) or an internal IP as their callback, so you must validate/allow-list callback URLs, block private/link-local ranges, and require HTTPS.

**Retries, idempotency, ordering:** retries use **exponential backoff with jitter and a bounded attempt count** (e.g., escalating over hours, then **dead-letter**), exactly to avoid the retry-storm/amplification problem (Q68) — and you must distinguish retryable failures (timeouts, 5xx, connection errors) from permanent ones (a 4xx from the receiver, or an unreachable host after long backoff) so you stop hammering a receiver that will never accept the event. Because retries make duplicates inevitable, **every event carries a stable unique id** (`X-Webhook-Id`) and the contract tells receivers to **dedupe on it** — that's how at-least-once delivery becomes effectively-once *processing* on the receiver. **Ordering is the hard one**: webhooks are fundamentally *not* ordered (retries and parallel delivery reorder events), so the robust design either (a) makes events self-contained and order-independent, (b) includes a sequence number / version / timestamp so the receiver can ignore stale events ("apply only if newer than what I have"), or (c) for cases needing strict order, serializes delivery per-entity (a per-resource key/partition) — but you should *document* that ordering isn't guaranteed and design events to tolerate reordering rather than promising what you can't deliver.

**Operating it (the "thundering consumer" and self-protection):** a slow or down consumer must not back up *your* system — so delivery is **decoupled from the triggering request** via a **transactional outbox + queue** (write the event in the same DB transaction as the state change so you never lose or phantom-send an event, then a worker delivers asynchronously), with **per-consumer concurrency/rate limits** so one slow receiver doesn't starve delivery to others, **circuit breakers** that pause delivery to a consistently-failing endpoint (and auto-disable + alert the consumer after prolonged failure), and tight **timeouts** on the outbound call. Give consumers operational tooling: a **delivery log** they can inspect, **manual resend**, and the ability to rotate secrets — because debugging "I didn't get the webhook" is otherwise impossible for them. And complement webhooks with a **pollable resource** (Q45): the webhook is a latency optimization, but the authoritative, always-available state should also be fetchable (`GET /events` or the resource itself) so a missed delivery is recoverable without you having to guarantee delivery perfectly. The interview-grade synthesis: webhooks are an at-least-once delivery platform calling untrusted receivers, so you (1) **sign** payloads with HMAC + timestamp (tamper + replay protection, constant-time compare, rotatable secrets) and guard against **SSRF** in callback URLs; (2) **retry** with bounded jittered backoff → dead-letter, distinguishing retryable from permanent failures; (3) make duplicates safe via a **stable event id receivers dedupe on** (effectively-once), and treat **ordering as not guaranteed** (sequence numbers / version checks / tolerate reordering); (4) **decouple delivery** with a transactional outbox + queue, per-consumer rate/concurrency limits, and circuit breakers so a thundering or dead consumer can't harm your system; and (5) back the whole thing with a **pollable source of truth** plus consumer-facing delivery logs and resend, because perfect delivery is impossible and recoverability is the real reliability guarantee.

#### Q87. [Practical] After a deploy, a subset of API requests started failing with deserialization errors / 400s, but only from older mobile app versions you can't force-update. What likely happened and how do you remediate without breaking new clients?

The pattern — failures isolated to *older, un-updatable clients* immediately after a deploy, manifesting as deserialization errors or 400s — is the signature of an **unintended breaking change to the contract** that new clients tolerate but old, frozen clients don't. The deploy almost certainly tightened or altered something on the request/response shape: a field was renamed or removed, a type changed (a number became a string, or a field that was always present became nullable), a previously-optional request field became required, validation was tightened (a format or length the old app sends is now rejected), or an enum gained a value the old app's strict parser rejects. New clients were updated in lockstep with the server so they don't notice; the *old* binaries — which you can't force-update, the crucial constraint — are still sending/expecting the old shape and now fail. This is exactly the backward-compatibility class from Q80, surfacing as a production incident because the change shipped without a contract-diff gate catching it.

```
Likely culprit (introduced by the deploy)        | Why only OLD clients fail
--------------------------------------------------|--------------------------------
Response field removed/renamed                    | old app parses it -> NPE/deser error
Field type changed (int -> string, scalar -> obj) | old app's strict deser fails
Always-present field became nullable/absent       | old app assumes non-null -> crash
Request field became REQUIRED                      | old app doesn't send it -> 400/422
Validation tightened (regex/length/range)          | old app's valid input now rejected
New enum value returned                            | old app's exhaustive parse rejects it
Stricter deserialization (FAIL_ON_UNKNOWN flipped)| old app sends extra field -> 400
```

**Immediate remediation: roll back or feature-flag off the breaking change to stop the bleeding** — old clients are failing in production *right now*, so the first action is to restore compatibility, not to debug elegantly. If the change is behind a flag, flip it; if not, roll back the deploy (this is why deploys should be reversible — Q74). Then *re-introduce the change compatibly* rather than reshipping the same break. The remediation must serve **both** populations from one server, since you can neither remove the old behavior (old clients need it) nor withhold the new behavior (new clients/features need it). The techniques, in preference order: **make the change additive instead of mutating** — if you renamed `customer_id` to `customerId`, return *both* fields for a deprecation window; if you needed a new shape, *add* a new field and keep populating the old one; if you needed a new required request param, make it optional with a server-side default so old clients (which omit it) still work. **Be a tolerant reader / lenient on input** for the old clients' requests (accept the old field names/formats and normalize internally) while accepting the new ones too — *liberal in what you accept* on the request side, even though Q49 cautions against over-doing it, here you're deliberately widening to not break frozen clients.

When additive isn't enough — the shapes genuinely diverge — **version the divergent surface** so old and new clients get different representations: media-type or URI versioning for the affected endpoint, or **client-version-aware responses** keyed off the app version (e.g., an `X-Client-Version` header or the user-agent) where the server applies a *transformer* to fold the new internal model back into the old shape for old clients — exactly the Stripe per-version-transformer pattern (Q51) applied tactically to one endpoint. This lets the modern server keep one internal model while emitting the legacy shape to legacy binaries. Critically, set `Jackson`/deserializer config thoughtfully: don't flip `FAIL_ON_UNKNOWN_PROPERTIES` on for a public API consumed by clients that may send extra fields, and keep response serialization stable.

The prevention angle the interviewer is really probing: this incident should have been impossible to ship. Add the **OpenAPI breaking-change diff gate** (`oasdiff`) and **consumer-driven contract tests** to CI (Q80) so a field removal/type change/new-required-param fails the build; maintain a **frozen backward-compat test suite** that exercises old-client request/response shapes against every new build; and, because mobile clients can't be force-updated, treat the *oldest supported app version* as a first-class contract consumer with its own contract test. Operationally, **never deploy a contract change without a canary** watching the error rate *segmented by client version* (Q74) — a canary would have shown old-version 400s spiking at 1% rollout instead of after full deploy. The interview-grade synthesis: old-clients-only failures after a deploy = an accidental breaking contract change that new clients mask; remediate by *immediately* rolling back or flag-disabling to restore old clients, then re-introduce compatibly (additive fields, lenient input parsing, populate both old+new fields) or via a version-aware transformer that emits the legacy shape to legacy binaries from one modern server; and prevent recurrence with CI breaking-change diffing + consumer contract tests treating the oldest un-updatable app version as a pinned contract, plus client-version-segmented canary metrics so the break is caught at 1% rollout, never after full deploy — because with un-updatable clients, *you* permanently own backward compatibility.

#### Q88. [Practical] How do you implement distributed rate limiting correctly across many API gateway instances, and what are the consistency/performance pitfalls (race conditions, hot keys, fail-open vs fail-closed)?

The core problem is that an in-memory token bucket (Q20) works per-instance, but with N gateway instances each enforcing its own limit, a "100 req/min" limit becomes "100×N req/min" — the limit is N times too loose. Correct distributed rate limiting requires **shared, atomic counter state** that all instances consult, and the canonical implementation is a **Redis-backed limiter where the read-modify-write is atomic** (a Lua script, since `GET` then `SET` from the application is a race — two instances both read 99, both increment, both allow, and you've exceeded the limit). The atomicity is the whole game: the decrement-and-check must be a single uninterruptible operation in the shared store.

```lua
-- Atomic token-bucket check in Redis (Lua runs atomically server-side).
-- KEYS[1]=bucket key; ARGV: capacity, refill_per_sec, now, requested
local data = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(data[1]) or tonumber(ARGV[1])      -- start full
local ts     = tonumber(data[2]) or tonumber(ARGV[3])
local elapsed = math.max(0, tonumber(ARGV[3]) - ts)
tokens = math.min(tonumber(ARGV[1]), tokens + elapsed * tonumber(ARGV[2]))  -- refill
local allowed = 0
if tokens >= tonumber(ARGV[4]) then
  tokens = tokens - tonumber(ARGV[4]); allowed = 1          -- consume
end
redis.call('HMSET', KEYS[1], 'tokens', tokens, 'ts', ARGV[3])
redis.call('PEXPIRE', KEYS[1], 60000)                       -- TTL: evict idle keys
return allowed
```

The pitfalls, each of which separates a working design from a broken one. **(1) Race conditions** — application-level `GET`/increment/`SET` is non-atomic and *will* over-admit under concurrency; the fix is server-side atomicity (a Lua script, or `INCR`+`EXPIRE` for a fixed window, or Redis's native rate-limit modules). **(2) Hot keys** — a single high-traffic tenant's rate-limit key becomes a contention/throughput bottleneck on one Redis shard (everyone hits the same key). Mitigations: shard the key (`tenant:bucket:{0..k}` with the limit divided across shards), or use *local* token buckets that periodically reconcile with the central store (each instance pre-fetches a batch of tokens from Redis and serves locally — trades exactness for throughput). **(3) The central store is a new dependency and SPOF** — every request now consults Redis, adding latency and a failure mode; if Redis is down, you must decide **fail-open vs fail-closed**. *Fail-open* (allow requests when the limiter is unavailable) preserves availability but removes protection exactly when you might need it (a flood could coincide with Redis trouble); *fail-closed* (reject when you can't check) protects the backend but turns a limiter outage into an API outage. The usual pragmatic choice is **fail-open with a conservative local fallback** (each instance falls back to a stricter in-memory limit) so you degrade to *approximate* limiting rather than *no* limiting or *no service*.

**(4) Approximation vs exactness trade-off**: perfectly exact distributed limiting requires a round-trip to shared state on every request (latency + the SPOF), so high-throughput systems deliberately accept *approximate* enforcement — local token buckets with periodic central reconciliation, or sliding-window-counter approximations — because being off by a few percent under burst is fine, but adding 1ms+ of Redis latency to every request and a hard dependency is often not. **(5) Where to enforce** — do it at the gateway (one chokepoint, before requests fan out to services) keyed by the right dimension (API key/tenant/user, not just IP — IP is shared behind NAT/proxies and spoofable). **(6) Client communication** — return `429` + `Retry-After` + `RateLimit-Limit/Remaining/Reset` headers (Q19) so well-behaved clients back off deterministically rather than retry-storming. And **don't hand-roll it**: battle-tested libraries (Bucket4j with a distributed backend) and gateway-native limiters already solve the atomicity and clock-skew issues. The interview-grade synthesis: per-instance limiting multiplies the limit by instance count, so you need *shared atomic* state (Redis + Lua, not app-level GET/SET which races); the real-world tensions are hot-key contention (shard keys or use local-with-reconciliation buckets), the limiter becoming a latency-adding SPOF (decide fail-open-with-local-fallback vs fail-closed deliberately), and exact-vs-approximate enforcement (accept approximation for throughput); enforce at the gateway keyed by tenant/API-key, communicate limits via `429`+`Retry-After`+`RateLimit-*` headers, and prefer a proven library over rolling your own.

#### Q89. [Practical] An incident review finds your service hung because a downstream dependency degraded but never returned errors (it got slow, then stopped responding). What patterns prevent one slow dependency from taking down the whole service?

This is the **cascading failure via resource exhaustion** scenario, and the key insight from the incident is that the dependency *didn't error* — it got **slow, then unresponsive** — which is far more dangerous than clean failures, because slowness silently *holds resources*. Each request waiting on the slow dependency occupies a thread and a connection for the entire wait; with no timeout, "slow then unresponsive" means those resources are held *forever*; under sustained traffic the thread pool and connection pool fill with stuck requests, and then the service can't process *any* requests — including ones that don't even touch the bad dependency. One slow dependency thus takes down the whole service. The defenses are a layered set of **resilience patterns** that bound resource consumption and isolate failure.

```
Pattern          | What it does                          | Prevents
-----------------|---------------------------------------|---------------------------
Timeout          | cap how long ANY call can wait        | infinite resource hold
                 | (never infinite)                      | (the root cause here)
Circuit breaker  | after N failures/slow-calls, OPEN ->  | hammering a dead dependency;
                 | fail fast for a cooldown, then probe  | gives it room to recover
Bulkhead         | isolate resources per dependency      | one slow dep exhausting the
                 | (separate thread/conn pool each)      | pool shared by all deps
Fallback /       | return cached/default/degraded result | hard failure when dep is down
graceful degrade | instead of erroring                   | (serve stale, omit feature)
Load shedding    | reject excess fast (503 + Retry-After)| queue buildup -> total stall
Retry+backoff    | retry TRANSIENT failures (idempotent  | (but bounded — retries can
(careful!)       | only) with jitter                     | amplify; Q68)
```

The two patterns that directly address this incident: **timeouts** are the root-cause fix — every outbound call must have a *finite* read timeout (and connect timeout), because "wait forever for a slow dependency" is precisely what hung the service. A tight timeout converts "held forever" into "fails in 2 seconds," which frees the thread/connection to serve other work. But timeouts alone aren't enough — if the dependency is reliably slow/down, every request still *tries*, waits the timeout, then fails, which both wastes the timeout duration on every request *and* keeps hammering a struggling dependency. So you add a **circuit breaker**: after a threshold of failures or slow calls, the breaker **opens** and requests fail *immediately* (no call attempted) for a cooldown period, then **half-opens** to probe whether the dependency recovered. This does two things — it stops your service from wasting resources on a dependency that's clearly down, and it stops you from piling load onto a dependency that needs breathing room to recover (which is often *why* it degraded). The slow-call detection (not just error-rate) is essential here precisely because this dependency degraded by *getting slow*, not by erroring — a breaker configured only on error-rate would never trip.

The **bulkhead** pattern provides isolation: give each downstream dependency its *own* bounded thread/connection pool (named for the ship compartments that stop one flooded section from sinking the vessel), so that even if dependency A's pool fills with stuck requests, dependencies B and C — and endpoints that don't use A at all — still have their own resources and keep working. Without bulkheads, all dependencies share one pool and one bad actor drowns everyone (exactly what happened). **Fallbacks / graceful degradation** complete the picture: when the breaker is open or the call fails, return a *degraded* result — a cached value, a sensible default, or the response with that feature omitted — rather than failing the whole request (e.g., render the product page without the slow "recommendations" section instead of erroring the page). And **load shedding** (fast `503` + `Retry-After`) at the front prevents the inbound queue from building up into a stall when you're over capacity.

These compose into the standard resilience stack (Resilience4j / Istio / Envoy provide all of them as config): **timeout** (bound the wait — fixes the root cause) → **circuit breaker** with slow-call detection (stop calling a down/slow dependency, let it recover) → **bulkhead** (isolate per-dependency resources so one can't exhaust all) → **fallback** (degrade gracefully instead of failing) → **load shedding** (reject excess fast). The interview-grade synthesis: slow-but-not-erroring dependencies are the dangerous case because slowness silently holds threads/connections until the pool exhausts and the *whole* service stalls — even for unrelated requests; the fix is bounding and isolating resource consumption: *always* set finite timeouts (root cause), use circuit breakers that trip on **slow calls** (not just errors) to fail fast and let the dependency recover, **bulkhead** per-dependency pools so one bad dependency can't starve the rest, and provide **fallbacks/graceful degradation** so a dependency failure degrades a feature rather than failing the request — turning "one slow dependency takes everything down" into "one feature degrades while everything else stays up."

#### Q90. [Practical] How would you instrument and run a canary deployment for an API change that you suspect might subtly change behavior (e.g., a query rewrite or cache change) where errors won't be obvious?

The hard part of this scenario is "**subtle** behavior change where errors won't be obvious" — a query rewrite or cache change typically *doesn't throw*; it returns a `200` with *wrong or different* data, or the same data with different performance characteristics. So a canary that only watches error rate and latency (the usual RED signals) will see green and happily promote a change that's silently returning incorrect results. The instrumentation must therefore go beyond "did it error" to "**is the new behavior equivalent to the old**," which calls for correctness-oriented techniques layered on top of the standard canary mechanics.

```
Standard canary (necessary, insufficient here):
  route 1% -> new version; compare RED metrics (errors, p50/p99 latency) +
  business KPIs vs the baseline (old version on the other 99%).
  -> catches crashes, exceptions, latency regressions. Does NOT catch
     "returns 200 with different/wrong data."

For SUBTLE behavior changes, add CORRECTNESS comparison:
  - Shadow / mirror traffic: send a copy of real requests to BOTH old and
    new, compare responses offline. New version's response is NOT returned
    to the user (zero blast radius) -> safe diffing on real traffic.
  - Diff-testing (GitHub "scientist" pattern): run old (control) + new
    (candidate) in-process, return control's result, log mismatches.
  - Golden-dataset / contract tests: known inputs -> known outputs, run pre-prod.
  - Result-equivalence metrics: count/rate of response-diffs as a first-class
    canary signal, not just errors.
```

The technique that *directly* solves "errors won't be obvious" is **shadow (mirror) traffic with response diffing**: mirror a fraction of real production requests to the new version *in addition to* the old, but **discard the new version's response** (the user always gets the old, trusted result), and compare the two responses asynchronously. This lets you exercise the query rewrite / cache change against *real, representative traffic* (which synthetic tests can't fully reproduce — real data has the edge cases) with **zero user blast radius**, and surface "the new query returns 1,203 rows where the old returned 1,204" or "the rewrite reorders results" — exactly the silent correctness drifts a normal canary misses. The closely related **diff-testing / "scientist" pattern** (popularized by GitHub for risky refactors) runs both the control (old) and candidate (new) code paths, returns the control's result to the user, and records every mismatch with context — ideal for an *in-process* rewrite where shadowing whole requests is impractical. You make the **response-diff rate a first-class canary metric**: the canary doesn't promote unless not only error/latency are flat *but the diff rate is within an expected tolerance* (some diffs are acceptable — e.g., a new field — so you compare on the *relevant* subset and classify diffs).

The mechanics that make this safe and informative: **define equivalence carefully** — for a query rewrite you compare result *sets* (sorted/normalized) not byte-identical responses, ignoring intentionally-changed fields and tolerating known-acceptable differences (a reordering that doesn't matter, a new optional field); for a cache change you also watch **cache hit rate and staleness** as explicit metrics, since the *behavior* might be correct but the *performance/freshness* changed. **Segment the comparison** (by tenant, by query shape, by data volume) so you can tell whether mismatches cluster around a specific input class (e.g., only deep-pagination requests, or only a particular filter) — that localizes the bug. **Ramp gradually** (shadow → 1% live → 5% → 25% → 100%) only as both the standard signals *and* the diff rate stay clean, and keep an instant rollback / feature-flag kill switch (Q74). For cache changes specifically, also verify **invalidation correctness** (does the new cache serve stale data after a write?) with targeted write-then-read probes, because a cache bug shows up as "correct data that's a few seconds out of date" — invisible to error metrics and even to a snapshot diff.

The interview-grade synthesis: a standard canary watches errors and latency, which catches crashes and regressions but is *blind to "200 with subtly-wrong data,"* which is exactly the failure mode of query rewrites and cache changes. So you augment the canary with **correctness comparison against the trusted baseline**: **shadow/mirror real traffic to old+new and diff the responses with the new result discarded** (real-traffic coverage, zero blast radius), or **in-process diff-testing (the "scientist" pattern)** for code-path rewrites, making the **response-diff rate a first-class promotion gate** alongside RED metrics; define equivalence as *normalized result-set* comparison (ignoring intentional changes), segment diffs to localize bugs, add cache-specific signals (hit rate, staleness, invalidation-correctness probes), ramp only while both standard and diff metrics stay clean, and keep an instant flag-based kill switch — because for silent behavior changes, "it didn't error" is not evidence it's correct, and only comparing outputs against the known-good baseline is.

#### Q91. [Practical] Your team disagrees on whether to use 400 vs 422, return errors as a list vs single, and whether to use 404 vs 403 for forbidden resources. How do you drive a consistent error-handling convention across many services and teams?

This question is really about **API governance** — turning a per-developer judgment call into an organization-wide convention — because inconsistent error handling across services is a real cost: client developers integrating with five of your services have to learn five different error shapes and five different interpretations of the same status code, which is exactly the friction that erodes API quality and integrator trust. The technical disagreements (400 vs 422, list vs single, 404 vs 403) each have a *defensible* answer, but the meta-point is that **consistency matters more than picking the theoretically-perfect option** — a uniform, documented, enforced convention beats a slightly-better-but-inconsistent one. So I'd drive it as: decide once with rationale, encode it in a standard, and *enforce* it mechanically so it can't drift.

```
Disagreement        | Convention I'd standardize on            | Rationale
--------------------|------------------------------------------|----------------------
400 vs 422          | 400 = malformed/unparseable (bad JSON,   | clean split: syntax
                    |   wrong type, missing required field at  | vs semantics; clients
                    |   the syntax level)                      | branch on the boundary
                    | 422 = syntactically valid but semantic   | (note: some orgs use
                    |   business-rule violation (age=-5,       | only 400 for simplicity
                    |   insufficient funds)                    | — also fine IF uniform)
Single vs list      | ALWAYS a list of errors (problem+json    | one shape; validation
                    | with an "errors":[{field,code,message}]) | naturally has many; a
                    |                                          | single error is a 1-list
404 vs 403          | 404 when revealing existence is itself   | avoid existence oracle
forbidden resource  | a leak (BOLA-protected objects you don't | (Q32/Q84); 403 only
                    | own); 403 when existence is non-secret   | when "you may not" is
                    | and "you lack permission" is the honest  | safe to disclose
                    | message (e.g., admin endpoint)           |
```

The decisions, briefly justified so the team buys in rather than complies grudgingly: **400 vs 422** — the cleanest convention is 400 for *syntax/parse* failures (malformed JSON, wrong types, a required field missing at the structural level) and 422 for *semantic/business-rule* failures (valid JSON but `age: -5`, insufficient funds) — this gives clients a meaningful boundary to branch on; *but* an equally valid org choice is "we only use 400 for all client input errors" for simplicity, and that's fine **as long as it's uniform** — the failure mode is some services using 422 and others 400 for the identical situation. **Single vs list** — always return a *list* (`problem+json` with an `errors[]` extension member), because validation inherently produces multiple errors (you want to tell the client *all* the bad fields in one round-trip, not make them fix-and-resubmit one at a time), and a single error is just a one-element list — one shape, no special-casing. **404 vs 403** — default to **404 for resources the caller isn't authorized to even know exist** (BOLA-protected objects — returning 403 confirms the resource exists, an enumeration oracle — Q32/Q84), and reserve **403** for cases where the resource's existence isn't secret and "you don't have permission" is the honest, safe message (e.g., a non-admin hitting a known admin endpoint).

But the decisions are the easy part; **driving consistency across many teams** is the real answer. The machinery: (1) **Publish an organizational API style guide** (the way Zalando, Google, Microsoft, PayPal publish theirs) that mandates `problem+json` (RFC 9457), the status-code conventions above, the error-list shape, and a registry of `type` URIs — a single normative document teams point to. (2) **Provide a shared library / starter** that implements the convention (a common `GlobalExceptionHandler`, problem+json serializer, error-list builder) so the *easy path is the compliant path* — teams adopt the dependency instead of hand-rolling error handling, which is how you get consistency without policing every PR. (3) **Enforce in CI**: lint the OpenAPI spec against the style guide (Spectral rules), and add contract tests asserting error responses conform to the standard shape — so a service returning a non-conformant error fails its build. (4) **API review / governance forum**: a lightweight cross-team review (or an API guild) that reviews new services against the guide, plus a documented decision record (ADR) capturing *why* each convention was chosen so it's not relitigated in every team. (5) **Lead with the principle** in the disagreement: frame it to the team as "the client developer integrating five of our services is the customer; inconsistency taxes *them*" — which usually dissolves the bikeshedding because everyone agrees consistency serves the consumer.

The interview-grade synthesis: each technical question has a defensible answer (400=syntax/422=semantics with uniform application either way; always an error *list* in problem+json; 404 to avoid existence-oracle leaks on BOLA-protected resources, 403 only when existence is non-secret), **but consistency across services matters more than the marginal "best" choice** because inconsistency taxes every integrator. So you drive it through *governance, not debate*: a published org-wide API style guide mandating RFC 9457 + the conventions, a **shared library/starter that makes the compliant path the easy path**, CI enforcement (Spectral spec-linting + contract tests on error shapes), and a lightweight API-review forum with ADRs recording the rationale — turning a recurring per-team judgment call into a standard that's documented, encoded in shared code, and mechanically enforced so it can't drift.

#### Q92. [Practical] How do you debug "it works locally but fails in production" for a REST service when the failure is environment-specific (TLS, DNS, proxies, headers, timeouts)?

"Works locally, fails in production" is almost by definition an **environment delta** problem — the *code* is identical (you deployed the same artifact), so the failure must live in what's *different* between the two environments: the network path, TLS configuration, DNS resolution, intermediaries (load balancers, proxies, service mesh), header handling, timeouts, resource limits, or config/secrets. The debugging discipline is to **systematically enumerate and test each environmental difference** rather than re-reading code that you already know works locally. The single most powerful move is to **run the diagnostic *from inside* the production environment** (exec into a pod/host or a debug sidecar) so you observe the failure from where it actually happens, not from your laptop which has a different network and trust store.

```
Environment delta        | Local                | Production           | How to test from inside prod
-------------------------|----------------------|----------------------|------------------------------
TLS / cert trust         | no TLS or trusted    | mutual TLS, custom   | curl -v https://dep  (watch
                         | self-signed          | CA, expired cert     | the handshake + cert chain)
DNS resolution           | /etc/hosts, localhost| service discovery,   | nslookup/dig dep; curl
                         |                      | internal DNS         | --resolve to bypass DNS
Proxy / mesh / LB        | direct connection    | sidecar, egress proxy| check HTTP_PROXY env, hit
                         |                      | NO_PROXY rules       | dep direct vs via proxy
Headers (added/stripped) | client = you, full   | gateway strips/adds   | echo endpoint: what headers
                         | control              | headers, mesh injects | does the SERVICE receive?
Timeouts                 | generous / none      | tight LB/mesh timeouts| curl -w timing; compare to
                         |                      |                       | configured timeouts
Config / secrets / env   | .env, defaults       | injected secrets,     | print effective config
                         |                      | feature flags differ  | (redacted) at startup
Egress / firewall        | open                 | locked-down egress;   | nc -vz host port; can prod
                         |                      | dep not reachable     | even reach the dependency?
```

Walking the high-frequency culprits. **TLS/certificate issues** are the classic prod-only failure: locally you hit `http://` or a dev endpoint with relaxed verification, but production uses real TLS, possibly **mutual TLS** (the service must present a client cert it doesn't have or that expired), a **custom/internal CA** not in the container's trust store, or a downstream cert that's expired or has a hostname mismatch — `curl -v` from inside the pod shows the full handshake and cert chain and names the exact failure. **DNS** differs because locally you use `localhost`/`/etc/hosts` while production uses service discovery / internal DNS; a service name that doesn't resolve, resolves to the wrong thing, or has stale records fails only in prod — test with `dig`/`nslookup` from inside and use `curl --resolve` to bypass DNS and prove whether the problem is *resolution* vs *connectivity*. **Proxies / service mesh / egress rules**: production traffic often goes through an egress proxy or a sidecar (Envoy/Istio) that local doesn't have — a missing `NO_PROXY` entry, a mesh policy blocking the call, or a locked-down egress firewall means the dependency is *unreachable from prod* even though it's reachable from your laptop (test raw connectivity with `nc -vz host port`). **Header handling**: the production gateway/mesh strips, rewrites, or injects headers (Q76) — so the service receives a *different* request than your local client sends; the diagnostic is an echo endpoint or request-logging that shows *exactly what headers the service actually received* in prod. **Timeouts**: local has generous or no timeouts while production LB/mesh enforce tight ones, so a call that's "slow but fine" locally gets killed in prod (Q69/Q72). **Config/secrets/feature-flags differ** between environments — print the *effective* (redacted) config at startup so you can confirm prod is actually configured the way you think.

The method that makes this efficient rather than a fishing expedition: **bisect the environment differences with a shared correlation id and from-inside-prod tooling.** Establish *where* the failure occurs first — does the request even reach the service (check the LB/gateway access logs for the request id, Q72), or does it reach the service and fail talking to a *downstream* (check the service's own outbound call)? Then reproduce the failing hop **from inside production** with `curl -v`/`dig`/`nc` so you're testing the real network path, real DNS, and real trust store — most "works locally" bugs are immediately obvious once you stop testing from your laptop. Crucially, **minimize the environment delta in the first place**: run the same container image locally as in prod (Docker/Compose mirroring prod's network and TLS), use staging that genuinely mirrors prod's intermediaries, and inject prod-like config — the fewer the differences, the smaller the search space when something is prod-only. The interview-grade synthesis: identical code + different behavior ⇒ the bug is an *environment delta*, so don't re-read code — enumerate and test the differences (TLS/cert trust and mTLS, DNS/service discovery, proxy/mesh/egress reachability, header strip/inject, timeouts, config/secrets), reproduce the failing hop **from inside the production environment** with `curl -v`/`dig`/`nc` (real network, real trust store) using a shared correlation id to localize which hop fails first, and structurally reduce future occurrences by shrinking the local-vs-prod gap (same image, prod-mirroring staging, prod-like config) so "works locally" actually predicts "works in prod."

#### Q93. [Practical] How do you design API tests at the right levels (unit, contract, integration, end-to-end) to catch REST-specific regressions, and what does each level actually catch that the others miss?

The principle is the **test pyramid applied to APIs**: many fast, isolated tests at the bottom, fewer slow, broad tests at the top — and the discipline is putting each *kind* of regression at the cheapest level that can catch it, because a contract break caught by a unit test costs seconds while the same break caught by an end-to-end test (or by a customer) costs hours. For a REST API specifically, the levels map to distinct *classes* of regression, and the senior insight is knowing what each level **uniquely** catches that no other level does.

```
Level          | Scope                         | Catches (uniquely)              | Speed
---------------|-------------------------------|---------------------------------|-------
Unit           | one class/method, no I/O      | business-logic bugs, edge cases,| fast
               | (mock deps)                   | validation rules                | (ms)
Web-layer /    | controller + (de)serialization| status codes, JSON mapping,     | fast
slice test     | + validation, mocked service  | header handling, problem+json,  |
(@WebMvcTest)  |                               | routing, content negotiation    |
Contract       | provider vs consumer          | BREAKING CHANGES to the         | fast
(Pact/OpenAPI  | expectations (no full stack)  | published contract (field       |
 diff)         |                               | removed/retyped, new required)  |
Integration    | service + real DB / real      | SQL/query bugs, transactions,   | medium
               | dependency (Testcontainers)   | migrations, real serialization, |
               |                               | connection/pool behavior        |
End-to-end     | full deployed system through  | wiring/config, gateway routing, | slow
               | gateway/LB                    | auth, CORS, TLS, env-specific   | (s-min)
```

What each level uniquely catches: **Unit tests** verify business logic and validation rules in isolation with mocked dependencies — they catch "the discount calculation is wrong" or "negative quantity should be rejected," fast and pinpoint, but they tell you *nothing* about whether the HTTP layer maps that to the right status code. **Web-layer slice tests** (`@WebMvcTest` in Spring) are the REST-specific workhorse: they exercise the controller, request/response (de)serialization, validation-to-status-code mapping, header handling, content negotiation, and your `problem+json` error shape *without* a full app — so they catch "we return 200 instead of 201 on create," "the `Location` header is missing," "validation errors aren't formatted as problem+json," "the wrong field name serializes" — exactly the REST-contract details that unit tests miss and that are too important to defer to slow E2E. **Contract tests** (consumer-driven Pact, and OpenAPI breaking-change diffing — Q80) uniquely catch **backward-compatibility breaks**: a field removed/renamed/retyped, a new required request param — these are invisible to unit and slice tests (your own tests pass because you updated them in lockstep) but break *consumers*; the contract test encodes the consumer's expectations (or diffs the published spec) so the *provider's* CI fails when it would break a known consumer. This is the level most often missing and the one that prevents the Q80/Q87 incidents.

**Integration tests** run the service against a **real database and real dependencies** (Testcontainers spins up a real Postgres/Redis in a container per test run) — they uniquely catch what mocks hide: actual SQL/query correctness (the query rewrite from Q90, the cursor-pagination keyset query, the optimistic-lock `@Version` behavior from Q70), transaction boundaries and rollback, **database migrations** applying cleanly, real JSON serialization round-trips, and connection-pool behavior — all of which mocked unit tests fundamentally cannot verify because the mock *is* the assumption being tested. **End-to-end tests** run against the **fully deployed system through the gateway/LB** and uniquely catch *wiring and environment* problems: gateway routing/rewrite (Q76), auth integration, CORS (Q64), TLS, and the "works locally fails in prod" config deltas (Q92) — the integration *between* components that no single-component test sees. E2E is powerful but slow, flaky, and expensive, so you keep it *thin*: a handful of critical-path smoke tests (can a user create and read an order through the real stack?), pushing everything that *can* be caught lower down to be caught lower down.

The synthesis an interviewer wants: build the pyramid so each regression class is caught at the cheapest sufficient level — **unit** for logic/validation, **web-slice tests** for the REST surface (status codes, headers, serialization, problem+json, content negotiation — the REST-specific details), **contract tests (Pact + OpenAPI diff)** for backward-compatibility breaks that pass your own tests but break consumers (the uniquely-API-evolution level, and the most commonly missing one), **integration tests with Testcontainers** for real DB/query/transaction/migration/pool behavior that mocks hide, and a *thin* layer of **end-to-end** smoke tests for wiring/gateway/auth/CORS/TLS/env integration. Add **negative authorization tests** (BOLA — user B can't read user A's resource, Q84) as a first-class category since it's the #1 vuln, and run **OpenAPI-spec linting** in CI. The anti-patterns to call out: an inverted pyramid (mostly slow flaky E2E, which is expensive and gives slow feedback), and *no contract tests* (so breaking changes ship because every internal test was updated in lockstep) — the two failure modes that most often let REST regressions reach production.

#### Q94. [Practical] A REST endpoint occasionally returns stale or wrong data and you suspect a caching bug. Walk through diagnosing whether it's a CDN, reverse-proxy, client, or application-cache problem, and the fix for each.

"Occasionally stale or wrong data" with a caching system is a layered-cache diagnosis problem, because a response can be cached at **multiple independent layers** — the browser/client cache, a CDN, a reverse proxy, and an application-level cache (Redis/in-memory) — and the fix is completely different depending on *which* layer is serving the stale copy. The systematic approach is to **localize the layer first** (don't guess), using cache-diagnostic headers and by bypassing layers one at a time, then apply the layer-specific fix. The fastest localization technique is to make a request that *bypasses* progressively more layers and see at which point the data becomes correct — the layer you just bypassed is the culprit.

```
Layer            | How to detect it's the culprit                 | Tell-tale signal
-----------------|------------------------------------------------|----------------------
Client/browser   | data is correct via curl but stale in browser; | from disk cache;
cache            | hard-refresh / incognito fixes it              | Age header absent
CDN              | curl shows Age/X-Cache: HIT/CF-Cache-Status;   | X-Cache: HIT, high Age,
                 | hitting origin directly returns fresh data     | CDN POP varies by region
Reverse proxy    | bypass CDN, hit proxy: still stale; hit app    | Via header, proxy's own
(nginx/varnish)  | directly (behind proxy): fresh                 | cache HIT indicator
Application cache| hit app directly (no CDN/proxy): STILL stale;  | stale even with all
(Redis/in-mem)   | restart/flush app cache -> fresh               | HTTP caches bypassed
```

The localization procedure, peeling layers from outside in. **(1) Check the response's cache headers** — `curl -i` and look at `Age` (how long it's been in caches — a high Age on supposedly-fresh data is a smoking gun), `X-Cache`/`CF-Cache-Status`/`X-Served-By` (CDN HIT/MISS), and `Via` (proxies in the path). **(2) Bypass the client cache**: reproduce with `curl` (no browser cache) or a hard-refresh/incognito — if `curl` is fresh but the browser is stale, it's the *client cache* (the response has too-long a `max-age` or is missing `no-cache`/validators). **(3) Bypass the CDN**: hit the origin/proxy directly (`curl --resolve` to the origin IP, or the origin's direct hostname) — if direct-to-origin is fresh but through-the-CDN is stale, the **CDN** is serving a cached copy that wasn't invalidated. **(4) Bypass the reverse proxy**: hit the app directly (behind the proxy) — if the app is fresh but through-the-proxy is stale, the **reverse proxy** (nginx/Varnish) cache is the culprit. **(5) If the app *itself* returns stale data even with all HTTP caches bypassed**, it's an **application-level cache** (Redis/in-memory) with a bad invalidation. This staircase deterministically isolates the layer.

The fixes by layer. **Client/browser cache stale** → the response's `Cache-Control` is too aggressive: shorten `max-age`, or use `no-cache` + `ETag` so the browser *revalidates* (cheap 304) instead of blindly reusing, and ensure mutating responses include proper cache directives (Q79). **CDN stale** → the data changed but the CDN's TTL hasn't expired and *no purge fired*: the fix is **event-driven cache invalidation** — emit a purge to the CDN when the underlying resource changes (Q28's "short TTL + purge on write"), rather than relying on long TTLs you can't bust; also verify `Vary` is correct (a missing/over-broad `Vary` causes the CDN to serve the wrong variant — Q34) and that you're not caching *personalized* data on a shared key (which is both a staleness *and* a data-leak bug). **Reverse-proxy stale** → same class as CDN: fix the proxy's cache key (`Vary`, query-param handling), its TTL, and wire up purging; a common bug is the proxy caching `Set-Cookie`/personalized responses. **Application-cache stale** → the bug is **cache invalidation logic**: the code updated the DB but didn't evict/update the cache entry (or evicted the wrong key), or there's a **read-through race** (two requests miss, both load, one writes a stale value after the other wrote fresh). Fixes: invalidate (or write-through) the cache entry *in the same transaction/flow* as the write, get the **cache key derivation** right (a subtle key mismatch means writes evict a different key than reads populate), use short TTLs as a backstop so even a missed invalidation self-heals, and for the race, use versioned keys or write-through with proper ordering.

The cross-cutting lessons: **"wrong" (not just stale) data — one user seeing another's** — is a more serious variant that almost always means *personalized data cached on a shared key* (missing `Cache-Control: private`/`no-store`, or a cache key that doesn't include the user — Q34/Q79); that's a security incident, not just a freshness bug, and the fix is to never let shared caches store per-user responses. And the famous truth — "there are only two hard things in computer science: cache invalidation and naming things" — is exactly why the *default* posture should be **short TTLs + explicit purge-on-change** rather than long TTLs, because a short TTL bounds the blast radius of any invalidation bug to seconds. The interview-grade synthesis: stale/wrong data in a multi-layer cache system is a *localization-first* problem — peel layers with `curl` (check `Age`/`X-Cache`/`Via`, bypass client → CDN → proxy → app in turn) to find *which* cache serves the stale copy, then apply the layer-specific fix (client: shorten `max-age` or `no-cache`+`ETag` to force revalidation; CDN/proxy: event-driven purge-on-write instead of long TTLs, correct `Vary`/cache-key; app cache: fix invalidation logic and key derivation, write-through/evict in the write path, short backstop TTL); treat *cross-user "wrong data"* as a shared-cache-of-personalized-data security bug (mark `private`/`no-store` or key by user); and default to short TTL + explicit invalidation so any invalidation bug self-heals quickly.

#### Q95. [Practical] How do you rotate an API key, JWT signing key, or HMAC webhook secret in production without breaking live traffic? What's the operational procedure and the failure mode of doing it naively?

The naive approach — replace the old secret with the new one in one step — causes an immediate outage, because there's always **in-flight work and cached credentials signed with the old secret**: requests already in transit carry tokens signed by the old key, clients still hold the old API key, and webhook receivers are validating against the old HMAC secret. Swap atomically and every one of those fails the instant you cut over. The correct procedure for *any* shared-secret rotation is the same shape: **support old and new simultaneously for an overlap window, migrate, then retire the old** — never an atomic swap. The mechanism that makes this possible is **accepting multiple valid secrets during the transition**, which requires you to have *designed for rotation* (a key set, a `kid`, a versioned secret) before you ever need it.

```
Rotation = OVERLAP, never atomic swap. Generic procedure:
  1. Introduce the NEW secret ALONGSIDE the old (both valid for VERIFICATION).
  2. Start SIGNING/issuing with the new one.
  3. Let old-signed artifacts age out / migrate clients to the new one.
  4. Once nothing uses the old secret (verified by metrics), RETIRE it.

JWT signing key (RS256):     publish new public key in the JWKS *first*;
  header carries `kid` -> verifier picks the right key. Old + new both in
  the JWKS until all old-`kid` tokens expire. THEN sign with new key. THEN
  drop the old key from the JWKS after max token lifetime has passed.

API key (per-consumer):      let a consumer have TWO active keys; issue the
  new one, consumer updates at their pace, both work; revoke the old after
  the consumer confirms (or usage of the old key hits zero).

HMAC webhook secret:         sign with the NEW secret but include BOTH the
  old- and new-signed signatures (or let the receiver accept either) during
  overlap; receiver migrates; drop the old.
```

The key-specific mechanics. For **JWT signing keys**, the enabler is the `kid` (key id) in the JWT header plus a **JWKS** (JSON Web Key Set) endpoint the verifier fetches: you publish the *new* public key to the JWKS **before** you start signing with it, so verifiers have already cached it by the time the first new-`kid` token arrives; you keep *both* keys in the JWKS so old, still-valid tokens (up to their `exp`) continue to verify; only after the maximum token lifetime has elapsed (so no old-signed token can still be live) do you remove the old key. The catastrophic naive failure is removing the old public key the moment you start signing with the new one — every unexpired token signed with the old key instantly fails verification, a 401-storm for all currently-logged-in users. For **per-consumer API keys**, the model is dual-active keys: the consumer can have two valid keys at once, you issue the new one, the consumer rolls it into their config on *their* schedule (you can't force a synchronized cutover across thousands of integrators — Q78), both work meanwhile, and you revoke the old only after confirming it's unused. For **HMAC webhook secrets** (Q86), during overlap you either send two signature headers (old-signed and new-signed) or have the receiver accept either secret, the receiver migrates, then you drop the old.

The operational discipline around it: **instrument which secret/key is actually being used** (per-`kid` verification counts, per-API-key usage) so you can *prove* the old one has hit zero usage before retiring it — retiring on a guess is how you break the one client that didn't migrate. **Store secrets in a secrets manager** (Vault, AWS/GCP Secrets Manager) with versioning so rotation is a managed operation, not an env-var edit-and-redeploy. **Automate and rehearse rotation** *before* an emergency — the time to discover your system can't hold two keys is not during an active key-compromise incident, when you must rotate *immediately* and the overlap window collapses (a compromised key may need instant revocation, accepting some breakage, which is exactly why you want short-lived tokens so the blast radius of "revoke now" is small). And tie rotation to **short token lifetimes**: short-lived access tokens (Q57) mean the overlap window for signing-key rotation is minutes-to-hours, not days, because old tokens expire fast. The interview-grade synthesis: secret rotation is always *overlap-then-retire*, never an atomic swap, because in-flight tokens and client-held credentials signed with the old secret will fail the instant you cut over; the enabling design is *accepting multiple valid secrets simultaneously* (JWKS + `kid` for JWT keys, dual-active API keys per consumer, accept-either-secret for HMAC) which must be built in *before* you need it; you publish/accept the new secret before signing with it, prove the old one is unused via per-key usage metrics before retiring it, store secrets in a versioned secrets manager, and rehearse rotation ahead of time — with short token lifetimes keeping the overlap window (and the blast radius of an emergency revoke) small.

#### Q96. [Practical] A security review finds that your API access logs and error responses contain secrets and PII (tokens in URLs, full request bodies, stack traces with data). How did this happen and how do you fix logging across the service?

This is a **data-exposure-via-observability** incident, and it happens precisely *because* of well-intentioned logging: a team adds "log the full request/response for debugging," or logs the URL (which contains a `?token=...` query param), or a 5xx handler dumps the exception with the offending data and a stack trace into the response — each individually reasonable, collectively a compliance and security failure, because logs are widely accessible (to operators, in log-aggregation tools, in backups, often retained for months) and error responses go to the *client*. The two distinct problems are **secrets/PII landing in logs** (internal over-exposure, GDPR/PII-retention violation, secrets harvestable by anyone with log access) and **internal detail leaking in error responses** (information disclosure to attackers — Q13/Q27). They share a root cause: untrusted/sensitive data flowing into a sink (log file or response body) without redaction.

```
How it leaks                                | Fix
--------------------------------------------|----------------------------------------
Secrets/tokens in the URL (?token=,         | NEVER put secrets/PII in URLs (they land
?ssn=, /users/<email>) -> access logs       | in logs, proxies, Referer, history) — use
                                            | headers/body (Q60/Q27); redact in log config
Logging full request/response bodies        | don't log bodies by default; if needed for
(card numbers, passwords, PII)              | debug, redact via allow-list + sampling
Stack trace / SQL / internal hostnames in   | 5xx returns GENERIC message + traceId only;
the ERROR RESPONSE body                     | log the detail server-side, never to client
Authorization header / cookies in logs      | strip/redact sensitive headers in log filter
PII as log/metric DIMENSIONS (user email    | use stable opaque IDs, route TEMPLATES
as a tag, raw URI as a metric label)        | (/users/{id} not /users/<email>) — Q71
```

The fixes, layered. **Eliminate the source where possible**: secrets and PII must *never* be in URLs (Q60) — a token in `?token=` or an email in the path lands in every access log, proxy log, browser history, and `Referer` header, so the fix is architectural (move them to headers/body), not just redaction. **Don't log request/response bodies by default** — they're high-volume and the most likely place for card numbers, passwords, and PII; if body logging is genuinely needed for debugging, gate it behind a sampled, *redacting* logger that masks known-sensitive fields by an allow-list (log only fields you've explicitly deemed safe) rather than a block-list (which fails open when a new sensitive field is added — same fail-open lesson as Q55/Q11). **Redact at the logging layer** with field maskers / pattern-based scrubbers (mask anything matching token/card/SSN patterns, mask `Authorization`/`Cookie` headers) so even an accidental sensitive value is caught before it's written — defense in depth, since you can't audit every log statement. **For error responses, separate what the client sees from what you log**: a 5xx returns a *generic* `problem+json` (`"detail":"Internal error"`) plus only a `traceId`; the full exception, stack trace, and context are logged *server-side* keyed by that same trace id (Q13/Q73), so support can correlate without exposing internals to the caller — never return a stack trace, SQL, or internal hostname to a client (an attacker uses those for reconnaissance). And **route templates, not raw values, as log/metric dimensions** (`/users/{id}` not `/users/jane@x.com`) so PII doesn't become a searchable index key and metric cardinality stays bounded (Q71).

The systemic remediation (the senior part): one-off fixes don't prevent recurrence, so make safe logging the *default and enforced*. **Centralize logging** through a shared library/config with redaction built in so individual developers can't accidentally log raw bodies/headers — the easy path is the safe path. **Standardize structured logging** with a defined, reviewed schema (no free-text "log whatever") so what gets logged is deliberate. Add **automated scanning**: a CI lint/SAST rule flagging `log.*(request.getBody())`, logging of `Authorization`, etc., and a periodic scan of *actual* log output for secret/PII patterns (you'll be surprised what leaks). Define and enforce **log retention and access controls** (PII in logs is subject to the same retention/right-to-erasure rules as PII anywhere — a compliance, not just security, requirement). Rotate any secrets that *were* exposed (Q95 — assume a logged secret is compromised). The interview-grade synthesis: secrets/PII in logs and internals in error bodies is a data-exposure incident born of well-meant "log everything for debugging" plus secrets-in-URLs plus stack-traces-to-clients; the fixes are *eliminate the source* (no secrets/PII in URLs — architectural), *don't log bodies/sensitive headers by default* (sampled + allow-list redaction if needed, since logs are broadly accessible and long-retained), *separate client-facing errors (generic + traceId) from server-side detail (full trace, logged, correlated by id)*, and *use opaque IDs/route templates as dimensions, not PII*; prevent recurrence by centralizing logging through a redacting shared library (safe path = default path), enforcing a structured-log schema, CI/SAST scanning for sensitive logging, and PII-grade retention/access controls — and rotate anything that was already exposed because a logged secret is a compromised secret.

#### Q97. [Practical] Right after midnight UTC (or a deploy), a flood of valid clients suddenly get 401s. Walk through diagnosing an auth/token incident where nothing in the auth code changed.

A *sudden* 401-storm hitting *previously-valid* clients, correlated with a *time boundary* (midnight UTC) or a *deploy* — with no change to auth logic — is the signature of a **token/key validity discontinuity**, not a code bug. Tokens that were verifying fine a minute ago stop verifying *en masse*, which narrows the cause sharply: something that all those tokens *depend on* changed state at that instant. The candidates are a small, enumerable set, and the time-correlation is the key clue that tells you *which*. The diagnostic discipline is to read what the 401s actually say (a good auth layer logs *why* a token was rejected — expired, bad signature, wrong issuer, clock-skew, key-not-found) and correlate the onset time with known boundaries.

```
Trigger / onset clue          | Likely cause                          | Fix
------------------------------|---------------------------------------|--------------------
At a date boundary (midnight, | a CERTIFICATE or SIGNING KEY EXPIRED   | renew/rotate; alert
1st of month, a specific date)| (TLS cert, JWT signing cert, mTLS     | on expiry BEFORE it
                              | client cert) — they expire on a date  | happens (Q95)
Right after a deploy          | signing-key rotation done naively —   | overlap old+new keys
                              | old key dropped from JWKS while old   | in JWKS (Q95); never
                              | tokens still live -> "key not found"  | atomic swap
                              | OR issuer/audience/clock config changed|
All tokens at once, "expired" | CLOCK SKEW — a node's clock jumped     | NTP; allow small
                              | (or NTP corrected a drift) so `exp`/  | leeway on exp/nbf
                              | `nbf`/`iat` checks fail               |
"signature invalid" suddenly  | JWKS fetch failing (auth server down/  | cache JWKS w/ fallback;
                              | DNS/network) -> verifier can't get the| don't fail-closed on a
                              | public key -> rejects everything      | transient JWKS miss
"issuer/audience mismatch"    | a config/env change (deploy) altered   | fix config; validate
                              | expected issuer/audience/JWKS URL     | in pre-prod
```

Walking the most common causes by clue. **Onset exactly at a date boundary** ⇒ **something expired**: a TLS certificate, a JWT signing certificate, or an mTLS client certificate that was valid until that date and lapsed — certificates expire on a *wall-clock date*, which is exactly why incidents cluster at midnight or month boundaries, and why "nothing changed in the code" is true (the *cert* changed state by the passage of time). The fix is to renew/rotate, and the *prevention* is monitoring certificate expiry and alerting *days before* (a cert-expiry dashboard / automated renewal like cert-manager/ACME), because this is one of the most common and most embarrassing self-inflicted outages. **Onset right after a deploy** ⇒ a **config or key-rotation change**: the deploy may have rotated the JWT signing key *naively* — dropping the old public key from the JWKS while tokens signed with the old key are still live, so verification fails with "key not found" / unknown `kid` (exactly the Q95 anti-pattern: retire-before-expiry); or the deploy changed the expected **issuer/audience** or the **JWKS URL**, so valid tokens now fail issuer/audience validation. **All tokens failing as "expired" simultaneously** ⇒ **clock skew**: a server's clock drifted and NTP corrected it with a jump, or a node's clock is wrong, so `exp`/`nbf`/`iat` comparisons misfire — the fix is reliable NTP and configuring a small clock-skew *leeway* (a few minutes) on expiry/not-before checks so minor drift doesn't reject valid tokens. **"Signature invalid" suddenly, no deploy** ⇒ the verifier **can't fetch the JWKS** (the auth server is down, or DNS/network to it broke), so it has no public key and rejects everything — the fix is to *cache the JWKS with a stale fallback* and not fail-closed on a transient fetch failure (a JWKS outage shouldn't 401 every request if you have a recently-cached key).

The diagnostic method that ties it together: **the 401 reason code plus the onset time localizes the cause in one step** — which is why an auth layer must log *why* it rejected (not just "401"), and why you should have **alerting on auth-failure rate** so a 401-storm pages you immediately rather than being discovered via customer complaints. Confirm by decoding a *currently-failing* token (`exp` in the past? unexpected `iss`/`aud`? a `kid` not in the current JWKS?) and checking it against the verifier's current config and the system clock. **Remediate fast** by restoring the missing validity: renew the expired cert, re-add the old key to the JWKS for the overlap (Q95), fix NTP/leeway, or restore JWKS connectivity. **Prevent recurrence** with the operational hygiene that makes these impossible: automated cert renewal + expiry alerting (kills the date-boundary class), rotation that always overlaps old+new keys (kills the deploy-key-drop class — Q95), enforced NTP + clock-skew leeway (kills the clock class), cached-JWKS-with-fallback (kills the JWKS-outage class), and issuer/audience/JWKS config validated in pre-prod (kills the config-change class). The interview-grade synthesis: a sudden 401-storm against previously-valid clients with no auth-code change is a *validity discontinuity*, and the **onset time is the diagnostic key** — a date boundary screams *expired certificate/key* (the #1 cause: certs expire on a date, so "nothing changed but time passed"), a deploy screams *naive key rotation (old key dropped while old tokens live) or issuer/audience/JWKS config change*, "all expired at once" screams *clock skew*, and "signature invalid with no deploy" screams *JWKS fetch failure*; you localize it in one step by logging the *reason* for each 401 and correlating onset with boundaries, remediate by restoring the missing validity (renew cert / re-overlap the key / fix NTP / restore JWKS), and prevent recurrence with cert-expiry monitoring + automated renewal, overlap-based key rotation, NTP + skew leeway, and cached-JWKS-with-fallback — because every one of these classes is a known, monitorable, preventable failure mode.

## 🧩 Extended Questions — Set 3: Coding, Design & Expert Scenarios

### 🟢 Basic — extended

#### Q98. [Coding] Implement a conditional GET that returns `304 Not Modified` using both `ETag`/`If-None-Match` and `Last-Modified`/`If-Modified-Since`.

**Problem:** Save bandwidth by letting a client revalidate a cached representation cheaply. The server must compute a validator, compare it against the client's conditional headers, and short-circuit to `304` (empty body) when the resource is unchanged. Earlier coding questions (Q18) used `If-Match`/`412` for *writes*; this is the *read* side — `If-None-Match`/`304`.

```java
@GetMapping("/orders/{id}")
public ResponseEntity<OrderResponse> get(@PathVariable String id, WebRequest req) {
    Order order = repo.findById(id).orElseThrow(() -> new NotFoundException(id));

    // A strong validator derived from the resource's mutable state. version is a
    // monotonic counter (@Version in JPA); quoting is required by RFC 9110.
    String etag = "\"" + order.getId() + "-" + order.getVersion() + "\"";
    long lastModifiedMillis = order.getUpdatedAt().toEpochMilli();

    // checkNotModified does the heavy lifting: it reads If-None-Match AND
    // If-Modified-Since from the request and, if EITHER matches, returns true and
    // pre-populates the response with 304 + the validators (no body).
    if (req.checkNotModified(etag, lastModifiedMillis)) {
        return null;   // Spring sends 304 with ETag/Last-Modified, empty body
    }

    return ResponseEntity.ok()
            .eTag(etag)
            .lastModified(lastModifiedMillis)
            .cacheControl(CacheControl.maxAge(Duration.ofSeconds(30)).cachePrivate())
            .body(toResponse(order));
}
```

The subtleties an interviewer probes. **`ETag` is the precise validator and wins over `Last-Modified`** when both are present, because `Last-Modified` has only one-second resolution — two updates within the same second are indistinguishable by date but distinguishable by a version-derived ETag. That is why I derive the ETag from a `@Version` counter, not a hash of the body (hashing the whole payload on every read is wasteful and a *weak* validator unless you guarantee byte-stability of serialization). **`If-None-Match: *`** is a special case meaning "304 if the resource exists at all," used by clients that just want to know existence cheaply.

The bandwidth math is the whole point: a `304` is a few hundred bytes of headers versus a multi-kilobyte body, and the client keeps serving its cached copy. Combined with `Cache-Control: max-age`, the flow is "use the cached copy without asking for `max-age` seconds, then *revalidate* with a conditional GET that usually returns `304`." A common bug is returning `200` with a body even when nothing changed because the handler recomputes and re-serializes unconditionally — always compute the validator *first* and short-circuit. Note `cachePrivate()` here because an order is user-specific; a public catalog would use `cachePublic()` so shared caches/CDNs can store it (tying back to Q79).

#### Q99. [Coding] Implement JSON Merge Patch (RFC 7396) correctly, including `null`-means-delete semantics and why naive deserialization breaks it.

**Problem:** A `PATCH /orders/{id}` with `Content-Type: application/merge-patch+json` must apply a partial update where a present key sets the field, an *explicit* `null` deletes/clears it, and an *absent* key leaves it untouched. The classic trap (and the reason Q22 flags this) is that ordinary POJO deserialization cannot distinguish "field absent" from "field present and null" — both arrive as a Java `null`.

```java
@PatchMapping(value = "/orders/{id}", consumes = "application/merge-patch+json")
public ResponseEntity<OrderResponse> patch(@PathVariable String id,
                                            @RequestBody JsonNode patch) {  // raw tree, NOT a DTO
    Order order = repo.findById(id).orElseThrow(() -> new NotFoundException(id));

    // Allow-list the fields a client may touch (mass-assignment defense, Q55).
    if (patch.has("note")) {
        JsonNode v = patch.get("note");
        order.setNote(v.isNull() ? null : v.asText());     // explicit null -> clear
    }
    if (patch.has("priority")) {
        JsonNode v = patch.get("priority");
        if (v.isNull()) throw new UnprocessableException("priority is required, cannot be null");
        order.setPriority(Priority.valueOf(v.asText()));
    }
    // 'status', 'total', 'ownerId' are intentionally NOT in the allow-list:
    // server-authoritative, never patchable from the body.

    order.setUpdatedAt(Instant.now());
    return ResponseEntity.ok(toResponse(repo.save(order)));
}
```

The crux is binding to a `JsonNode` (a parsed tree) rather than a POJO, because only the tree preserves the three-way distinction: `patch.has("note")` is true only when the key is present, and `v.isNull()` distinguishes explicit JSON `null` from a string. If you bind to `OrderPatchDto { String note; }`, both `{}` and `{"note": null}` deserialize to `note == null`, so you can no longer tell "leave it alone" from "clear it" — you'd either never delete or always overwrite. (The library-grade alternative is `Optional<T>` fields with a Jackson module, or `JsonNullable<T>` from the `jackson-databind-nullable` lib that the OpenAPI generator uses — same idea, type-safer.)

The second non-obvious rule from RFC 7396 is **recursion**: merge patch merges objects deeply (a nested object in the patch merges into the nested object in the target), but **arrays are replaced wholesale, not merged element-wise** — there is no way to append to an array with merge patch, which is exactly the case where you reach for JSON Patch (RFC 6902) instead. Finally, merge patch is *idempotent* by construction (applying the same patch twice yields the same state), which is a genuine advantage over JSON Patch's `add` op — worth stating because it lets clients safely retry a `PATCH` after a timeout, recovering some of the retry-safety that `PATCH` otherwise lacks (Q2).

#### Q100. [Coding] Build a HATEOAS response with HAL (`application/hal+json`) using Spring HATEOAS, and explain what the links buy a client.

**Problem:** Return an order as a HAL document so the client discovers its next valid transitions (cancel, view items, pay) by following server-provided links instead of hardcoding URI templates — Richardson Level 3 (Q14, Q43), but shown as runnable code rather than discussed abstractly.

```java
@GetMapping(value = "/orders/{id}", produces = "application/hal+json")
public EntityModel<OrderResponse> get(@PathVariable String id) {
    Order order = repo.findById(id).orElseThrow(() -> new NotFoundException(id));
    OrderResponse body = toResponse(order);

    EntityModel<OrderResponse> model = EntityModel.of(body,
        linkTo(methodOn(OrderController.class).get(id)).withSelfRel(),
        linkTo(methodOn(ItemController.class).list(id)).withRel("items"));

    // Affordances are STATE-DEPENDENT: only expose 'cancel' when it's legal.
    if (order.getStatus() == Status.PENDING) {
        model.add(linkTo(methodOn(OrderController.class).cancel(id)).withRel("cancel"));
        model.add(linkTo(methodOn(PaymentController.class).pay(id)).withRel("pay"));
    }
    return model;
}
```
```json
{
  "id": "ord_123", "status": "PENDING", "total": 4200,
  "_links": {
    "self":   { "href": "/orders/ord_123" },
    "items":  { "href": "/orders/ord_123/items" },
    "cancel": { "href": "/orders/ord_123/cancel" },
    "pay":    { "href": "/orders/ord_123/pay" }
  }
}
```

The architecturally important point is **`linkTo(methodOn(...))` builds the URL from the actual controller mapping**, so links can never drift out of sync with the routes — if you change `@RequestMapping`, the emitted hrefs change with it, which is the practical payoff of generating links instead of string-concatenating them. The deeper value is the **state machine made explicit**: because `cancel` and `pay` only appear when `status == PENDING`, the client doesn't need to encode the business rule "you can only cancel a pending order" — it just renders whatever links it received and the server stays the single source of truth for what's legal next.

The honest interview caveat (matching Q43's "why adoption is low") is that few clients actually *follow* links — most hardcode `/orders/{id}/cancel` anyway — so HATEOAS's decoupling benefit is real but underused. Where it genuinely pays off is long-lived public APIs and workflow-heavy domains (approvals, multi-step checkout) where the set of valid next actions is dynamic; there, shipping affordances means you can change the workflow server-side without a client release. I'd reach for it deliberately, not reflexively, and I'd pick HAL over Siren/JSON:API for read-mostly APIs because it's the simplest and best-tooled (Spring HATEOAS, the `Link` header).

### 🟡 Intermediate — extended

#### Q101. [Coding] Implement HMAC-SHA256 webhook signature verification with a constant-time comparison and a timestamp anti-replay check.

**Problem:** Your service receives webhooks from a partner (or you're verifying your own outbound signatures from Q86). The receiver must confirm the payload was signed with the shared secret and wasn't tampered with or replayed. Get the comparison and the signed-content definition right, or the whole scheme is theater.

```java
public boolean verify(String payload, String sigHeader, String tsHeader, byte[] secret) {
    // 1) Anti-replay: reject signatures older than the tolerance window.
    long ts = Long.parseLong(tsHeader);
    if (Math.abs(Instant.now().getEpochSecond() - ts) > 300) return false;   // 5 min

    // 2) Sign timestamp + "." + body, NOT just the body. Binding the timestamp
    //    into the signed content stops an attacker from replaying an old valid
    //    body with a fresh timestamp.
    String signedContent = ts + "." + payload;
    byte[] expected = hmacSha256(secret, signedContent.getBytes(StandardCharsets.UTF_8));

    byte[] provided = HexFormat.of().parseHex(sigHeader);   // e.g. "a1b2c3..."

    // 3) CONSTANT-TIME compare. A normal equals() / Arrays.equals() returns early
    //    on the first mismatched byte, leaking how many leading bytes were right
    //    via timing -> an attacker can forge a signature byte-by-byte.
    return MessageDigest.isEqual(expected, provided);
}

private byte[] hmacSha256(byte[] key, byte[] data) {
    try {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    } catch (GeneralSecurityException e) {
        throw new IllegalStateException(e);
    }
}
```

Three things must be exactly right. **Constant-time comparison** (`MessageDigest.isEqual`, not `Arrays.equals` or `String.equals`) is non-negotiable: a short-circuiting compare leaks, through response timing, how many leading bytes matched, turning signature forgery into a tractable byte-at-a-time attack. **Signing the timestamp together with the body** (Stripe's `t=...,v1=...` scheme) is what makes the timestamp tamper-proof — if you signed only the body and checked the timestamp separately, an attacker could replay a captured valid body with an updated timestamp. **The replay window** (here 5 minutes) bounds how long a captured request stays usable; pair it with idempotency (store processed event ids) so even an in-window replay is a no-op.

A subtle but critical detail is **what bytes you sign**: you must HMAC the *raw request body exactly as received*, before any JSON parse/re-serialize, because re-serialization can reorder keys or change whitespace and produce a different byte string than the sender signed. In Spring that means capturing the body with a `ContentCachingRequestRequest`/`HttpMessageConverter` that hands you the original bytes, not a deserialized object. And the secret must be compared per-endpoint with support for **two active secrets during rotation** (Q95) — accept a signature valid under either the old or new secret while you roll keys.

#### Q102. [Coding] Implement a resilient REST client call with timeout, bounded retries, exponential backoff with full jitter, and respect for `Retry-After`.

**Problem:** Q68 explained *why* naive retries cause storms; here is the correct client. It must retry only on retryable failures, cap attempts, back off with jitter, honor a server's `Retry-After`, and never retry a non-idempotent request blindly.

```java
public HttpResponse<String> callWithRetry(HttpRequest req, boolean idempotent)
        throws InterruptedException {
    int maxAttempts = 4;
    long baseMillis = 200, capMillis = 5_000;

    for (int attempt = 1; ; attempt++) {
        try {
            HttpResponse<String> res = client.send(req, BodyHandlers.ofString());
            int sc = res.statusCode();

            // Retry only transient server-side conditions (and 429), and only if
            // the operation is safe to repeat.
            boolean retryable = (sc == 429 || sc == 502 || sc == 503 || sc == 504);
            if (!retryable || !idempotent || attempt == maxAttempts) return res;

            // Honor server's explicit backoff if present (seconds or HTTP-date).
            long wait = res.headers().firstValue("Retry-After")
                    .map(h -> Long.parseLong(h) * 1000)
                    .orElseGet(() -> backoffWithJitter(attempt, baseMillis, capMillis));
            Thread.sleep(wait);

        } catch (IOException e) {   // connect/read timeout, connection reset
            if (!idempotent || attempt == maxAttempts) throw new RuntimeException(e);
            Thread.sleep(backoffWithJitter(attempt, baseMillis, capMillis));
        }
    }
}

// Full jitter (AWS Architecture Blog): sleep is uniform random in [0, capped backoff].
private long backoffWithJitter(int attempt, long base, long cap) {
    long exp = Math.min(cap, base * (1L << (attempt - 1)));   // 200,400,800,...
    return ThreadLocalRandom.current().nextLong(0, exp + 1);
}
```

The decisions that separate this from a naive loop. **Full jitter** — picking a random delay in `[0, exponential]` rather than `exponential + small_random` — is what actually prevents the thundering herd: without jitter, every client that failed at the same instant retries at the same instant, re-synchronizing the spike; full jitter spreads them uniformly and (per AWS's analysis) minimizes total contention. **Retrying only idempotent operations** is the safety gate: blindly retrying a `POST /charge` after a read timeout can double-charge, because the request may have *succeeded* on the server before the response was lost — for non-idempotent calls you must pair retries with an idempotency key (Q16, Q54), not just toggle a boolean.

**Honoring `Retry-After`** is a courtesy that becomes self-interest: when a server returns `429`/`503` with `Retry-After`, it's telling you precisely when to come back, and ignoring it both gets you rate-limited harder and prolongs the outage. The missing piece in this snippet, which I'd add in production, is a **circuit breaker around the whole thing** (Q104): retries handle a transient blip, but if the dependency is *down*, you want to stop sending requests entirely after a failure threshold rather than retry every call — retries and circuit breakers are complementary, not alternatives. Bounding `maxAttempts` and the `cap` keeps total added latency predictable so retries don't blow the caller's own timeout budget (Q69).

#### Q103. [Coding] Implement sparse fieldsets / partial responses (`?fields=id,total,status`) safely, and explain the trade-offs versus GraphQL.

**Problem:** Mobile clients on slow networks (Q81) want to fetch only the fields they need to shrink payloads. Implement a `fields` query parameter that projects the response, while guarding against the obvious abuse vectors.

```java
@GetMapping("/orders/{id}")
public MappingJacksonValue get(@PathVariable String id,
                               @RequestParam(required = false) String fields) {
    OrderResponse full = toResponse(repo.findById(id).orElseThrow());
    MappingJacksonValue wrapper = new MappingJacksonValue(full);

    if (fields != null) {
        Set<String> requested = Arrays.stream(fields.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(toSet());

        // ALLOW-LIST: never reflect arbitrary client strings onto serialization.
        // Unknown field -> 400, so typos/probing fail loud rather than silently.
        Set<String> allowed = Set.of("id", "total", "status", "createdAt", "items");
        if (!allowed.containsAll(requested))
            throw new BadRequestException("Unknown field(s): " +
                    Sets.difference(requested, allowed));

        wrapper.setFilters(new SimpleFilterProvider().addFilter("orderFilter",
                SimpleBeanPropertyFilter.filterOutAllExcept(requested)));
    }
    return wrapper;   // @JsonFilter("orderFilter") on OrderResponse
}
```

The non-negotiable guard is **allow-listing the requested fields**: echoing arbitrary client strings into the serializer risks leaking fields that exist on the DTO but shouldn't be selectable, and silent acceptance of unknown fields hides client bugs — fail with `400` so a typo (`?fields=totl`) is loud. The second concern is **caching**: `fields` changes the representation, so it becomes part of the cache key (and you should `Vary` appropriately or include it in the cache-key derivation), otherwise a cache can serve a `fields=id` response to a client that asked for everything.

The honest trade-off framing (the part interviewers want) is *why not just use GraphQL*. Sparse fieldsets give you the single biggest GraphQL win — clients fetch only what they need, cutting payload — without adopting a new runtime, a new query language, or losing HTTP caching, which is exactly the dimension Q63/Q24 say decides the choice. What you *don't* get is GraphQL's nested selection across the graph (`order { customer { addresses { city } } }`); the REST `fields` param is one level deep, and deep needs are better served by deliberate `expand=customer,items` sub-resource expansion (Q59) or, past a threshold of client-field diversity, by actually adopting GraphQL. The rule of thumb: sparse fieldsets and `expand` cover the 80% case cheaply; reach for GraphQL only when you have many heterogeneous clients each wanting different deep slices.

### 🟠 Advanced — extended

#### Q104. [Coding] Implement a circuit breaker around a downstream REST dependency (state machine: CLOSED → OPEN → HALF_OPEN). Explain each state.

**Problem:** Q89 explained the pattern; implement it. When a downstream degrades, the breaker must stop hammering it (fail fast), then probe for recovery before fully reopening traffic. Show the state transitions and the failure accounting.

```java
public class CircuitBreaker {
    enum State { CLOSED, OPEN, HALF_OPEN }

    private volatile State state = State.CLOSED;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile long openedAtMillis = 0;
    private final int failureThreshold = 5;
    private final long openDurationMillis = 10_000;   // stay OPEN 10s before probing

    public <T> T call(Supplier<T> action, Supplier<T> fallback) {
        if (state == State.OPEN) {
            if (System.currentTimeMillis() - openedAtMillis < openDurationMillis)
                return fallback.get();                 // FAIL FAST, don't touch downstream
            state = State.HALF_OPEN;                    // time to probe
        }
        try {
            T result = action.get();                    // the actual REST call
            onSuccess();
            return result;
        } catch (Exception e) {
            onFailure();
            return fallback.get();
        }
    }

    private void onSuccess() {
        consecutiveFailures.set(0);
        state = State.CLOSED;                           // a HALF_OPEN probe succeeded
    }
    private void onFailure() {
        if (state == State.HALF_OPEN                    // probe failed -> reopen
            || consecutiveFailures.incrementAndGet() >= failureThreshold) {
            state = State.OPEN;
            openedAtMillis = System.currentTimeMillis();
        }
    }
}
```

```
   failures >= threshold            openDuration elapsed
        ┌──────────────┐  ────────────────────────────────►  ┌───────────┐
        │    CLOSED     │                                      │   OPEN    │
        │ (calls pass)  │  ◄──── probe succeeds ──────────┐    │(fail fast)│
        └──────────────┘                                  │    └───────────┘
                ▲                                          │          │
                │                                   ┌─────────────┐   │ after timeout
                └─── probe succeeds ─── allow 1 ───►│  HALF_OPEN  │◄──┘ allow 1 probe
                                          probe     │ (one trial) │
                              probe fails ──────────┴─────────────┘──► back to OPEN
```

The three states map to three operating modes. **CLOSED** is normal: calls pass through and failures are counted; when consecutive failures cross the threshold the breaker *trips* to OPEN. **OPEN** is the protective state: every call returns the fallback immediately *without touching the downstream*, which is the entire point — it stops a slow/dead dependency from consuming your threads and cascading the failure (Q89's "one slow dependency takes down the service"). After a cooldown, the breaker moves to **HALF_OPEN** and lets a *single* probe through; if it succeeds the breaker closes (recovery confirmed), if it fails it snaps back to OPEN for another cooldown — this prevents slamming a still-recovering downstream with full traffic.

The production-grade refinements I'd mention: use a **rolling-window failure rate** (e.g., trip if >50% of the last 20 calls failed) rather than raw consecutive count, so a high-throughput service trips on proportion not absolute count; combine the breaker with a **bulkhead** (separate thread pool / semaphore per dependency) so one downstream's saturation can't exhaust shared threads; and define a *meaningful* fallback (cached/last-known value, a degraded default, or a fast `503` to the caller) rather than just rethrowing. In real systems I'd use Resilience4j (`@CircuitBreaker`, `@Bulkhead`, `@Retry` as composable decorators) rather than hand-rolling, but being able to write the state machine from scratch is what proves you understand *why* HALF_OPEN exists and why OPEN must not touch the dependency.

#### Q105. [Coding] Implement a JSON Patch (RFC 6902) applier with `test`-based optimistic concurrency, and contrast it with merge patch.

**Problem:** Some clients need precise edits — insert at an array index, move a value, or assert a precondition — which merge patch (Q99) cannot express. Apply a JSON Patch document and use its `test` op to do optimistic concurrency *inside the patch* (an alternative to `If-Match`/ETags, Q18).

```java
@PatchMapping(value = "/orders/{id}", consumes = "application/json-patch+json")
public ResponseEntity<OrderResponse> patch(@PathVariable String id,
                                            @RequestBody JsonNode patchJson) throws IOException {
    Order order = repo.findById(id).orElseThrow(() -> new NotFoundException(id));
    JsonNode target = mapper.valueToTree(toResponse(order));

    JsonPatch patch = JsonPatch.fromJson(patchJson);   // com.github.fge / zjsonpatch
    JsonNode patched;
    try {
        patched = patch.apply(target);   // throws if a 'test' op fails or path is bad
    } catch (JsonPatchException e) {
        // A failed 'test' means the client's precondition didn't hold -> conflict.
        throw new ConflictException("Patch precondition failed: " + e.getMessage());
    }

    OrderUpdate update = mapper.treeToValue(patched, OrderUpdate.class);  // re-validate
    applyAllowedFields(order, update);   // still allow-list! patch can target any path
    return ResponseEntity.ok(toResponse(repo.save(order)));
}
```

```json
[
  { "op": "test",    "path": "/version",      "value": 7 },
  { "op": "replace", "path": "/note",         "value": "rush" },
  { "op": "add",     "path": "/items/-",      "value": { "sku": "X1", "qty": 2 } },
  { "op": "remove",  "path": "/discountCode" }
]
```

The standout capability is the leading **`test` op**: it asserts `version == 7` and the *entire patch is rejected atomically* if that fails, giving you optimistic concurrency expressed in the document itself — equivalent in effect to `If-Match: "...-7"` but carried in the body. JSON Patch also expresses things merge patch cannot: `add` to `/items/-` appends to an array, `move`/`copy` relocate values, and paths use JSON Pointer so you can target deep locations precisely. The cost is that it's verbose, harder for humans to write, and — critically — **not idempotent**: replaying that `add` op appends the item *again*, so a retried `PATCH` after a timeout double-inserts. That non-idempotence is exactly why merge patch is the default for simple CRUD and JSON Patch is the specialist tool.

```
                 | JSON Merge Patch (7396)      | JSON Patch (6902)
-----------------|------------------------------|------------------------------
Content-Type     | application/merge-patch+json | application/json-patch+json
Shape            | partial document             | array of ops
Delete a field   | set key to null              | {"op":"remove",...}
Array edit       | replace whole array          | per-index add/remove/move
Preconditions    | none (use If-Match)          | "test" op, atomic
Idempotent?      | yes                          | no (add appends each time)
Human-writable   | easy                         | verbose
```

The non-obvious security note carries over from merge patch: even though the patch *can* target any JSON Pointer path, you must still **re-validate the result and allow-list mutable fields** — never apply the patched tree straight back to the entity, or a client can `replace /status` or `replace /ownerId` and escalate (mass assignment, Q55). The right mental model is: JSON Patch decides *how* to transform the representation; your server still decides *which* transformations are permitted.

#### Q106. [Coding] Implement an asynchronous long-running operation correctly: `202 Accepted` + `Location`, a status resource, and polling semantics.

**Problem:** A request kicks off work that takes minutes (a report export, a bulk import). The API must accept it without blocking, hand the client a way to track progress, and expose the eventual result — the canonical `202 Accepted` pattern (Q3) shown end-to-end.

```java
// 1) Kick off: accept, enqueue, return 202 with a pollable status URL.
@PostMapping("/reports")
public ResponseEntity<Void> create(@RequestBody ReportRequest body) {
    String jobId = jobs.enqueue(body);     // persists job as PENDING, returns id
    URI statusUrl = URI.create("/reports/" + jobId);
    return ResponseEntity.accepted()                 // 202
            .location(statusUrl)                     // where to poll
            .header("Retry-After", "5")              // hint: poll in ~5s
            .build();
}

// 2) Poll the status resource. 200 with current state; 303 to result when done.
@GetMapping("/reports/{jobId}")
public ResponseEntity<?> status(@PathVariable String jobId) {
    Job job = jobs.find(jobId).orElseThrow(() -> new NotFoundException(jobId));
    switch (job.getState()) {
        case PENDING, RUNNING:
            return ResponseEntity.ok()
                    .header("Retry-After", "5")
                    .body(new JobStatus(jobId, job.getState(), job.getPercent()));
        case FAILED:
            return ResponseEntity.ok(new JobStatus(jobId, FAILED, job.getError()));
        case SUCCEEDED:
            // Point at the finished artifact; client GETs the result resource.
            return ResponseEntity.status(303)        // See Other
                    .location(URI.create("/reports/" + jobId + "/result"))
                    .build();
        default: throw new IllegalStateException();
    }
}
```

The design choices that matter. **Return `202` with a `Location` pointing at a *status* resource, not the eventual result**, because the result doesn't exist yet — the status resource is a first-class thing the client polls. **`Retry-After` on both the 202 and the in-progress status** tells clients how often to poll, which is your defense against a thousand clients hammering the status endpoint every 100ms (a self-inflicted DoS). When the job finishes, returning **`303 See Other` to a separate result resource** keeps the status resource (process state) cleanly separated from the result resource (the artifact) — the client follows the redirect with a `GET` and can cache that result independently.

The robustness concerns an interviewer will push on: the kickoff must be **idempotent** (Q16) so a client that retries the `POST` after a timeout doesn't enqueue the job twice — key the job by an `Idempotency-Key` and return the *same* `Location` on replay. The status resource should be **cacheable for a short TTL** while RUNNING (it changes) and become **immutable/long-cacheable** once SUCCEEDED. And you should offer **cancellation** (`DELETE /reports/{jobId}`) and decide retention (how long the result lives). For lower-latency UX, this polling model can be complemented by webhooks (Q86) or SSE so the client is *pushed* the completion event instead of polling — but polling is the robust baseline that works through any proxy and needs no persistent connection.

#### Q107. [Coding] Implement content negotiation that serves the same resource as JSON or CSV based on `Accept`, returning `406` correctly and setting `Vary`.

**Problem:** A reporting endpoint should return JSON to apps and CSV to spreadsheet users from the *same URI* (Q6), driven by the `Accept` header — not by a `.csv` extension or a `?format=` param. Implement it with correct `406` and `Vary` behavior.

```java
@GetMapping(value = "/orders", produces = { MediaType.APPLICATION_JSON_VALUE, "text/csv" })
public ResponseEntity<?> list(OrderQuery q) {
    List<OrderResponse> orders = service.find(q);
    return ResponseEntity.ok()
            .header(HttpHeaders.VARY, HttpHeaders.ACCEPT)   // caches key on Accept
            .body(orders);   // Spring picks the converter matching the client's Accept
}

// Register a CSV HttpMessageConverter so Spring can produce text/csv.
@Component
public class CsvOrderConverter extends AbstractHttpMessageConverter<List<OrderResponse>> {
    public CsvOrderConverter() { super(new MediaType("text", "csv")); }

    @Override protected boolean supports(Class<?> clazz) { return List.class.isAssignableFrom(clazz); }

    @Override
    protected void writeInternal(List<OrderResponse> orders, HttpOutputMessage out) throws IOException {
        try (var w = new OutputStreamWriter(out.getBody(), StandardCharsets.UTF_8)) {
            w.write("id,total,status\n");
            for (OrderResponse o : orders)
                w.write("%s,%d,%s\n".formatted(o.id(), o.total(), o.status()));
        }
    }
    @Override protected List<OrderResponse> readInternal(Class<?> c, HttpInputMessage in) {
        throw new UnsupportedOperationException();   // write-only converter
    }
}
```

Two correctness points dominate. **`Vary: Accept` is mandatory** the moment a single URI can return different representations, because a shared cache (CDN, reverse proxy) keys on the URL by default — without `Vary`, it can cache the JSON response and then serve those JSON bytes to a client that asked for CSV (with the wrong `Content-Type`), or vice versa. This is one of the most common content-negotiation bugs and ties directly to the caching pitfall called out in the file's Common Pitfalls section. **Returning `406 Not Acceptable`** when the client asks for a type the server can't produce (`Accept: application/xml`) is the correct, spec-mandated behavior — Spring does this automatically when no `produces` type matches, and you should *not* silently fall back to JSON, because that hides the mismatch from the client.

The design rationale for header-driven negotiation over `?format=csv` or `/orders.csv` is purity-versus-pragmatism (Q6 covered the spectrum): `Accept`-driven negotiation keeps one canonical URI per resource (good for caching, bookmarking, and HATEOAS link stability), while a query/extension override is more *debuggable* (you can paste a CSV link in a browser that always sends `Accept: text/html`). In practice many production APIs support *both* — header as the principled default, a `?format=` escape hatch for humans — and that's a defensible answer as long as you keep the cache key correct for whichever knob you expose. The streaming detail worth flagging: for large CSV exports, write directly to the output stream (as above) rather than building the whole string in memory, and consider the `202`-async pattern from Q106 if the export is huge.

#### Q108. [Coding] Write a contract test that fails CI on a breaking OpenAPI change, and explain what counts as breaking.

**Problem:** Q74 and Q80 said "diff the OpenAPI spec in CI"; show the actual gate. The test must compare the proposed spec against the published baseline and fail the build on a backward-incompatible change, so a breaking change can't merge silently.

```bash
#!/usr/bin/env bash
# ci/check-api-compat.sh — fails (exit 1) on any breaking change vs the baseline.
set -euo pipefail

BASELINE="origin/main:openapi.yaml"   # the currently-published contract
PROPOSED="openapi.yaml"               # the spec in this PR

# oasdiff: purpose-built OpenAPI breaking-change detector.
# 'breaking' exits non-zero if it finds an incompatible change.
git show "$BASELINE" > /tmp/baseline.yaml
oasdiff breaking /tmp/baseline.yaml "$PROPOSED" --fail-on ERR
```

```yaml
# .github/workflows/api-compat.yml
name: API Compatibility
on: pull_request
jobs:
  breaking-change:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with: { fetch-depth: 0 }            # need history to diff against main
      - uses: oasdiff/oasdiff-action/breaking@main
        with:
          base: openapi.yaml                # baseline ref resolved by the action
          revision: openapi.yaml
          fail-on-diff: true
```

The substance is **knowing what "breaking" means**, because the tool only encodes rules you should be able to recite. For a **response**, removing or renaming a field, narrowing a type, making an optional field required-to-be-present-differently, or removing an enum value the client might receive are breaking — *adding* a field is safe (tolerant readers ignore it, Q49). For a **request**, the asymmetry flips: *adding a required parameter or body field* is breaking (old clients don't send it), *removing* an enum value the client may send is breaking, while *adding an optional* request field or *relaxing* a constraint is safe. Removing an endpoint, removing a supported method, changing a path, or tightening auth/scopes are all breaking. This request/response asymmetry — additive-safe on responses, additive-*dangerous* on required requests — is the single most important thing to articulate.

The CI design rationale (tying to Q80): a human reviewer cannot reliably eyeball a 3,000-line spec diff for these rules, so you encode them as an automated gate that *fails the merge*, making breaking changes a deliberate, visible act (you bump the major version or override with justification) rather than an accident. I'd pair this with **consumer-driven contract tests** (Pact) for the cases the spec can't capture — actual known consumers' expectations — and treat the OpenAPI file as the **source of truth generated from or validated against the code**, so the spec can't drift from the implementation. The combination (oasdiff for the spec rules, Pact for real consumer expectations) is what lets a team evolve an API confidently without manually tracking who depends on what.

### 🔴 Expert — extended

#### Q109. [Coding] Implement a distributed token-bucket rate limiter in Redis using an atomic Lua script, and explain why the script (not GET/SET) is required.

**Problem:** Q20 built an *in-memory* limiter and Q88 discussed the *distributed* pitfalls; now write the correct distributed version. Many gateway instances share one limit per API key, so the check-and-decrement must be atomic across instances, and the bucket must refill over time.

```lua
-- token_bucket.lua  — KEYS[1]=bucket key  ARGV: rate, capacity, now_ms, requested
local key        = KEYS[1]
local rate       = tonumber(ARGV[1])   -- tokens per second
local capacity   = tonumber(ARGV[2])   -- max burst
local now        = tonumber(ARGV[3])   -- caller-supplied clock (ms)
local requested  = tonumber(ARGV[4])

local b = redis.call('HMGET', key, 'tokens', 'ts')
local tokens = tonumber(b[1]); local ts = tonumber(b[2])
if tokens == nil then tokens = capacity; ts = now end

-- Refill based on elapsed time since last touch (lazy refill, no background job).
local delta = math.max(0, now - ts) / 1000.0
tokens = math.min(capacity, tokens + delta * rate)

local allowed = tokens >= requested
if allowed then tokens = tokens - requested end

redis.call('HMSET', key, 'tokens', tokens, 'ts', now)
redis.call('PEXPIRE', key, math.ceil(capacity / rate * 1000))   -- idle keys self-evict
-- return allowed flag + remaining (for RateLimit-* headers) + retry hint
local retry_after = allowed and 0 or math.ceil((requested - tokens) / rate)
return { allowed and 1 or 0, math.floor(tokens), retry_after }
```

```java
List<Long> r = redis.execute(bucketScript, List.of("rl:" + apiKey),
        "10", "20", String.valueOf(System.currentTimeMillis()), "1");  // rate=10/s, burst=20
boolean allowed = r.get(0) == 1L;
if (!allowed) throw new TooManyRequestsException(/*Retry-After=*/ r.get(2));
```

The reason a **Lua script is mandatory** (not `GET` tokens → compute → `SET`) is atomicity under concurrency: Redis executes a script as a single, uninterruptible operation, so the read-refill-decrement-write happens with no interleaving. A naive `GET`/`SET` from two gateway instances races — both read `tokens=1`, both decide "allowed," both write `tokens=0`, and you've served two requests against a one-token budget. This is exactly the race-condition pitfall Q88 warned about, and the script eliminates it without a distributed lock (which would be slower and a new failure mode). **Lazy, timestamp-based refill** (computing elapsed time on each call) avoids a background refill job entirely and is precise.

The expert nuances. **Clock source**: passing `now` from the caller is convenient but means client clock skew across gateway instances can distort refill — using Redis's own `TIME` inside the script removes that ambiguity at the cost of a server-clock dependency; for multi-node Redis you accept a single authoritative clock. **Hot keys**: a single wildly popular API key funnels all its traffic to one Redis shard (the key's slot), so a celebrity tenant can hot-spot a node — mitigations are sharding the limit into N sub-buckets per key and summing, or a local-then-global two-tier limiter. **Fail-open vs fail-closed**: if Redis is unreachable, do you allow traffic (fail-open, prioritizing availability — usual choice for rate limiting since the limiter isn't a security control) or block it (fail-closed)? State the choice explicitly; for rate limiting fail-open is normal, but for a *quota* tied to billing you might fail-closed. Finally, return `remaining` and `retry_after` so the gateway can emit `RateLimit-*` and `Retry-After` headers (Q109 ties back to the file's rate-limit takeaway).

#### Q110. [Coding] Implement a transactional outbox so a state change and its "order.created" event publish atomically. Why is a dual-write to DB + broker a bug?

**Problem:** Q54 named the outbox pattern; implement it. When `POST /orders` must both persist the order and publish an event, doing two separate writes (DB then Kafka) is not atomic — a crash between them loses the event or emits a phantom. Show the fix.

```java
@Transactional   // ONE local transaction covers BOTH inserts — atomic together.
public Order create(CreateOrderRequest req, String idempotencyKey) {
    Order order = orderRepo.save(Order.from(req));

    // Write the event into an OUTBOX table in the SAME transaction as the order.
    OutboxEvent evt = new OutboxEvent(
            UUID.randomUUID().toString(),       // event id (consumer dedupes on this)
            "order.created",
            "Order", order.getId(),
            mapper.writeValueAsString(OrderCreated.from(order)),
            Instant.now(), /*published=*/ false);
    outboxRepo.save(evt);

    return order;   // commit: either BOTH rows persist, or NEITHER. No phantom events.
}
```

```java
// Separate relay polls unpublished rows and pushes to the broker AT-LEAST-ONCE.
@Scheduled(fixedDelay = 500)
public void relay() {
    for (OutboxEvent e : outboxRepo.findTop100ByPublishedFalseOrderByCreatedAt()) {
        broker.send("orders", e.getAggregateId(), e.getPayload());  // may send twice on crash
        e.markPublished();
        outboxRepo.save(e);   // if we crash after send, before this -> re-sent later (dup)
    }
}
```

The bug in a **dual write** is that the database and the message broker are two independent systems with no shared transaction: `orderRepo.save()` commits, then `broker.send()` is attempted. If the process crashes (or the broker is briefly unreachable) *between* them, you've persisted an order with **no event** — a downstream service never learns about it (lost event). If you reverse the order (publish then commit) and the commit fails, you've emitted an event for an order that **doesn't exist** (phantom event). There is no ordering of two non-transactional writes that's safe; that impossibility is the whole reason the pattern exists.

The outbox fix collapses the two writes into **one local ACID transaction**: the order row and the outbox row commit together or not at all, so the event's existence is exactly consistent with the order's existence. A separate **relay** (poller, or better, a CDC tool like Debezium tailing the DB's write-ahead log) then publishes outbox rows to the broker. Crucially the relay is **at-least-once** — if it crashes after `broker.send()` but before marking the row published, it re-sends on restart, producing a duplicate. That's acceptable *only because* consumers dedupe on the event id (which is why I generate and store one), making the end-to-end delivery **effectively-once** (Q54's framing). CDC-based relay is preferable to polling because it adds no query load and captures every committed change with low latency, but the polling version is simpler to reason about in an interview. The pattern's cost — an extra table, a relay process, and consumer-side idempotency — is the price of not having distributed transactions, which is the right trade-off in a microservice world where 2PC across a DB and Kafka is impractical.

#### Q111. [Practical] Design a public REST API for a multi-tenant SaaS billing/subscriptions system. Walk through resources, idempotency, versioning, pagination, auth, webhooks, and rate limits.

This is a synthesis exercise — the interviewer wants to see you compose the whole toolkit coherently, not recite isolated facts. I'd open by stating the **non-functional drivers** that shape every decision: it's *public* (so backward compatibility is sacred and the contract is a product), *multi-tenant* (so tenant isolation is the dominant security concern), and *money-moving* (so idempotency and auditability are non-negotiable). Then I design top-down.

```
Resources (nouns, plural, tenant-scoped by the authenticated principal):
  /v1/customers            /v1/customers/{id}
  /v1/subscriptions        /v1/subscriptions/{id}      (controller sub-resources for
  /v1/invoices             /v1/invoices/{id}            non-CRUD actions:)
  /v1/payment_methods        POST /v1/subscriptions/{id}/cancel
  /v1/prices  /v1/products   POST /v1/invoices/{id}/pay
Listing:  GET /v1/invoices?status=open&limit=50&starting_after=inv_123  (cursor)
```

**Identity and isolation** come first because they're the highest-risk area. Auth is OAuth2 bearer tokens (or signed API keys for server-to-server), and *every* object fetch enforces `where tenant_id = principal.tenant` server-side — never trust a tenant id from the path or body (BOLA/IDOR is API risk #1, Q84). Tenancy is derived from the token, so `/v1/customers/{id}` returns `404` (not `403`) for another tenant's id to avoid even confirming existence. **Idempotency**: all mutating `POST`s accept an `Idempotency-Key` header, stored per-tenant with the request fingerprint and the stored response, so a client that retries `POST /v1/subscriptions` after a timeout never double-charges (Q16/Q54) — this is the Stripe model and for a billing API it's mandatory, not optional.

**Versioning, pagination, errors.** I'd use Stripe-style **date-based versioning** pinned per account (`Stripe-Version: 2026-06-16`) rather than `/v2` in the path, because it lets us ship behavior changes while every existing integration stays frozen on the version it was built against — the architecturally cleaner choice for a long-lived public API (Q51), though `/v1` in the path is the simpler, defensible alternative for most teams. **Pagination is cursor-based** (`starting_after`/`ending_before` + `limit`, opaque cursors) because offset pagination breaks on large, concurrently-mutated collections (Q9/Q82). **Errors are RFC 9457 `problem+json`** with stable machine-readable `type` URIs (`/errors/card_declined`), a `traceId`, and never a raw stack trace — clients branch on `type`, not prose (Q13).

**Webhooks and limits** close the loop. Async lifecycle events (`invoice.paid`, `subscription.canceled`) are delivered as **signed webhooks** (HMAC-SHA256 with timestamp, Q101) to tenant-registered endpoints, with at-least-once retries plus exponential backoff and an event id so consumers dedupe (Q86) — because polling for state changes doesn't scale and money events must be reliably delivered. **Rate limits** are per-tenant token buckets at the gateway (distributed via Redis, Q109), returning `429` + `Retry-After` + `RateLimit-*` headers, with separate, tighter buckets on expensive endpoints (search, exports) and a generous burst for normal CRUD. The synthesis I'd voice at the end: for a billing API the spine is **idempotency + tenant isolation + reliable signed webhooks + an immutable, versioned contract** — get those four right and the rest (pagination, error format, caching) is standard hygiene. I'd also call out an **audit log** of every state-changing call (who, what, when, idempotency key) because financial systems require traceability that ordinary CRUD APIs don't.

#### Q112. [Behavioral] Tell me about a time you had to evolve or break a widely-used API. How did you manage the migration and the stakeholders? (STAR)

**Situation.** At a previous company I owned a public-facing REST API consumed by roughly 400 integrators and several internal teams. We discovered that our `/v1/transactions` endpoint returned monetary `amount` as a floating-point number — a latent correctness bug, because float can't represent currency exactly (`0.1 + 0.2 != 0.3`), and a few partners had already filed rounding discrepancies. The correct representation was an integer count of minor units (cents), which is unavoidably a **breaking change** to the field's type.

**Task.** I had to fix the correctness bug *without* breaking 400 integrators we mostly couldn't contact directly, on a timeline driven by a finance-compliance deadline, while keeping the on-call burden sane. The explicit constraint I set was "zero silent breakage" — no integrator should get wrong data *or* a surprise 4xx because of our change.

**Action.** I refused the tempting in-place fix (just change the type and announce it) because it would have shattered every client parsing `amount` as a float. Instead I made it **additive and opt-in**: I introduced a new field `amount_minor` (integer cents) *alongside* the existing `amount`, so old clients were untouched (tolerant-reader principle, Q49) and new clients could adopt the correct field. I instrumented per-field, per-consumer usage telemetry so I could *see* who still read the old field, published a deprecation notice with a `Sunset` header (RFC 8594) and a dated timeline, and added the breaking-change CI gate (oasdiff, Q108) so no one could regress the contract. For the long-tail of silent clients I drove migration by **dashboarded usage decay** rather than guesswork — we only scheduled removal of the float field once telemetry showed near-zero reads, and we reached out individually to the top consumers still using it. I also wrote a clear migration guide and offered a sandbox.

**Result.** Over about two quarters the float field's usage dropped to under 0.3% of reads; we contacted the remaining heavy users directly, and removed the field only after explicit confirmation, with no production incident and no integrator data-corruption complaints. The reusable lessons I now apply: (1) **expand-and-contract** (add the new shape, migrate readers, then remove the old) turns almost any breaking change into a sequence of safe ones; (2) **you can't migrate what you can't measure** — per-field usage telemetry is what converts "we think no one uses this" into a defensible removal decision; and (3) **backward compatibility is a product feature**, so the social work (clear comms, `Sunset` headers, generous windows, direct outreach to whales) matters as much as the technical work. The hardest part wasn't the code — it was the discipline to wait for the usage curve instead of forcing a flag-day cutover.

#### Q113. [Behavioral] Describe a production incident caused by an API design decision, what you did during the incident, and what you changed afterward. (STAR)

**Situation.** A high-traffic `GET /v1/search` endpoint we owned started timing out and cascading: its thread pool saturated, then unrelated endpoints on the same service began returning 503s, and our error rate alerts fired across the board during a peak shopping window. The proximate trigger was a downstream search cluster that had gotten *slow* (not down) — exactly the silent-degradation failure mode (Q89).

**Task.** As incident commander I had two jobs on different clocks: **stop the bleeding now** (restore the unrelated endpoints and shed the search load) and, afterward, **fix the design flaw** that let one slow dependency take down the whole service. I had to do the first without making things worse and the second without hand-waving.

**Action.** During the incident I followed our runbook-style triage. First I confirmed scope with the correlation-id-joined logs and traces (Q73) — the trace showed every hung request blocked on the search call, with threads stuck in CLOSE_WAIT against the search cluster (Q77). The immediate mitigations: I **shed load** by flipping a feature flag that returned a fast, cached "popular results" fallback for search (degrade, don't fail, Q83), which freed the thread pool and instantly recovered the co-located endpoints; then we scaled the search cluster and let it drain. I deliberately did *not* roll back app code, because the app hadn't changed — the dependency had — and a rollback would have wasted minutes and changed nothing.

**Action (root cause).** The post-incident review surfaced the real design defects: the search call had **no timeout** (so a slow downstream parked threads indefinitely), **no circuit breaker** (so we kept sending requests into the tar pit), and **no bulkhead** (so search shared a thread pool with everything else, letting its saturation starve unrelated endpoints). These are precisely the patterns from Q89/Q104.

**Result.** I drove three durable fixes: a strict client timeout on the search call sized to our own latency budget (Q69); a **circuit breaker** so the service fails fast to the cached fallback when search degrades, with HALF_OPEN probing for recovery (Q104); and a **bulkhead** giving search its own bounded thread pool so its saturation can never again starve other endpoints. We also added a synthetic "slow dependency" chaos test to CI/staging so the failure mode is exercised continuously, and an SLO-based alert on the search call's p99 specifically (not just aggregate error rate) so we'd catch *slow* before it became *down*. The meta-lesson I emphasize in reviews: **availability is mostly about how you fail, not whether you fail** — the design sin wasn't that a dependency degraded (that's inevitable), it was that we had no bounded, isolating, fast-failing response to it. Naming that distinction shifted our team's design defaults so new downstream calls now ship with timeout + breaker + bulkhead by default.

### 🟠 Advanced — extended (continued)

#### Q114. [Coding] Implement an opaque, tamper-resistant pagination cursor that survives schema changes and rejects forged or stale cursors.

**Problem:** Q9 built a Base64 cursor; here we harden it. A cursor must be *opaque* (clients can't construct or paginate-around it), *self-describing* (it encodes the sort key and direction so the server can resume), and *tamper-evident* (a client can't edit it to page into data it shouldn't, or break the query). Show a signed cursor.

```java
public final class Cursor {
    // Encodes the last-seen sort keys + a version tag, then signs with HMAC.
    public static String encode(Instant createdAt, String id, byte[] key) {
        String payload = "v1|" + createdAt.toEpochMilli() + "|" + id;
        String sig = base64Url(hmacSha256(key, payload.getBytes(UTF_8)));
        return base64Url((payload + "|" + sig).getBytes(UTF_8));   // opaque blob
    }

    public static Decoded decode(String cursor, byte[] key) {
        String raw = new String(base64UrlDecode(cursor), UTF_8);
        int lastSep = raw.lastIndexOf('|');
        String payload = raw.substring(0, lastSep);
        String providedSig = raw.substring(lastSep + 1);

        // Verify signature BEFORE trusting any field (constant-time).
        String expected = base64Url(hmacSha256(key, payload.getBytes(UTF_8)));
        if (!MessageDigest.isEqual(expected.getBytes(UTF_8), providedSig.getBytes(UTF_8)))
            throw new BadRequestException("Invalid or tampered cursor");

        String[] p = payload.split("\\|");
        if (!"v1".equals(p[0])) throw new BadRequestException("Unsupported cursor version");
        return new Decoded(Instant.ofEpochMilli(Long.parseLong(p[1])), p[2]);
    }
    record Decoded(Instant createdAt, String id) {}
}
```

```sql
-- Keyset query resumes strictly AFTER the cursor's (createdAt, id) tuple.
SELECT * FROM orders
WHERE (created_at, id) < (:cursorCreatedAt, :cursorId)   -- composite tiebreak
ORDER BY created_at DESC, id DESC
LIMIT :limit + 1;                                        -- +1 to detect "has next"
```

The three hardening decisions. **Signing the payload** (HMAC) makes the cursor tamper-evident: without it, a client who realizes the cursor decodes to `createdAt|id` could edit those values to jump arbitrarily through the dataset or, worse, inject values that break the keyset predicate — signing means any edit fails verification and yields a clean `400`. **The version tag (`v1`)** is what lets the cursor survive schema/sort changes: when you change the sort key, you bump to `v2`, and old `v1` cursors are rejected with a clear error rather than silently producing wrong results — never let a stale cursor resume against a query whose ordering changed. **The composite `(created_at, id)` tiebreak** is essential correctness, not decoration: paginating on a non-unique `created_at` alone skips or duplicates rows when timestamps collide; adding the unique `id` as a tiebreak makes the keyset total-ordered.

The "why opaque" rationale ties to API evolvability (Q49/Q51): an opaque cursor is an implementation detail the client must treat as a token to echo back, which means you can change the *internal* encoding (add fields, switch sort columns, migrate storage) without breaking clients, exactly because they never parsed it. A common production refinement is to also embed the **filter fingerprint** in the signed payload so a client can't reuse a cursor minted under `?status=open` against a request with `?status=closed` (which would produce nonsensical keyset boundaries) — verify the cursor's filter matches the request's filter, or reject. The trade-off is a few dozen extra bytes per cursor and an HMAC per page; both are negligible against the correctness and security they buy.

#### Q115. [Coding] Implement a Servlet filter that enforces a maximum request body size and rejects oversized uploads with `413` *before* buffering the whole body.

**Problem:** Q47 explained unrestricted resource consumption; implement the guard. An attacker (or a buggy client) sends a multi-gigabyte body to exhaust memory. The fix must reject early using `Content-Length` *and* defend against a lying/absent `Content-Length` by capping the actual bytes read.

```java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class BodySizeLimitFilter extends OncePerRequestFilter {
    private static final long MAX_BYTES = 1_048_576;   // 1 MiB

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        // 1) Cheap pre-check: trust Content-Length to reject the obvious case early,
        //    before reading a single byte of body.
        long declared = req.getContentLengthLong();
        if (declared > MAX_BYTES) { reject(res, declared); return; }

        // 2) Defense in depth: Content-Length can be absent (chunked) or LIE.
        //    Wrap the stream so reads are counted and abort past the cap.
        HttpServletRequest capped = new HttpServletRequestWrapper(req) {
            @Override public ServletInputStream getInputStream() throws IOException {
                return new CountingServletInputStream(super.getInputStream(), MAX_BYTES);
            }
        };
        chain.doFilter(capped, res);
    }

    private void reject(HttpServletResponse res, long size) throws IOException {
        res.setStatus(413);                              // Payload Too Large
        res.setContentType("application/problem+json");
        res.getWriter().write("""
            {"type":"/errors/payload-too-large","title":"Request body too large",
             "status":413,"detail":"Max %d bytes","max":%d}""".formatted(MAX_BYTES, MAX_BYTES));
    }
}
// CountingServletInputStream throws on exceeding the cap mid-read:
//   if (totalRead > max) throw new PayloadTooLargeException();
```

The crucial insight is the **two-layer defense**, and an interviewer will probe whether you understand why the cheap `Content-Length` check is *insufficient alone*. `Content-Length` is client-supplied and may be absent entirely (HTTP chunked transfer encoding sends no length) or deliberately falsified — an attacker can declare `Content-Length: 100` and then stream gigabytes. So the header check is an *optimization* (reject the honest oversized case for free), but the authoritative limit must be enforced by **counting bytes as you actually read them** and aborting once you cross the cap. Enforcing only the header is a real, common vulnerability.

Position and layering matter too. This belongs at a high filter order so it runs before any body-buffering happens (Jackson deserialization, multipart parsing) — if a downstream component buffers the whole body before your check fires, the OOM already happened. In practice you also set the container/framework limits (`server.tomcat.max-http-form-post-size`, `spring.servlet.multipart.max-request-size`, and gateway/proxy `client_max_body_size` in nginx) as additional layers, because the earliest layer to reject wins and keeps the byte count smallest. Returning `413` with a `problem+json` body (Q13) tells the client precisely what the limit is so it can chunk or compress. Different endpoints warrant different caps — a file-upload endpoint legitimately needs a larger limit than a JSON `POST` — so make `MAX_BYTES` per-route configurable rather than one global value.

#### Q116. [Coding] Stream a large collection as newline-delimited JSON (NDJSON) instead of buffering a giant array, and explain the back-pressure and error-handling trade-offs.

**Problem:** A `GET /orders/export` may return millions of rows. Materializing a `List<Order>` and serializing one giant JSON array OOMs the server and makes the client wait for the entire payload before it can process anything. Stream it row-by-row.

```java
@GetMapping(value = "/orders/export", produces = "application/x-ndjson")
public ResponseEntity<StreamingResponseBody> export(OrderQuery q) {
    StreamingResponseBody body = out -> {
        try (Stream<Order> rows = repo.streamAll(q);     // DB cursor, NOT findAll()
             JsonGenerator gen = mapper.getFactory().createGenerator(out)) {
            Iterator<Order> it = rows.iterator();
            while (it.hasNext()) {
                mapper.writeValue(gen, toResponse(it.next()));   // one object...
                out.write('\n');                                  // ...then a newline
                // out.flush() periodically so bytes leave the server as we go
            }
        }
    };
    return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, "application/x-ndjson")
            .header(HttpHeaders.TRANSFER_ENCODING, "chunked")   // no Content-Length
            .body(body);
}
```

```
NDJSON wire format (one self-contained JSON value per line):
  {"id":"ord_1","total":4200,"status":"PAID"}
  {"id":"ord_2","total":900,"status":"OPEN"}
  ...
Client can parse-and-process each line as it arrives — constant memory both ends.
```

The architectural win is **constant memory and incremental processing on both ends**. The server reads from a database *cursor* (a lazy `Stream`, not `findAll()` which loads everything) and writes each object straight to the socket, so server heap stays flat regardless of result size; the client reads line-by-line and can process or persist each record immediately rather than waiting for and holding a giant array. Because the total length is unknown up front, the response uses `Transfer-Encoding: chunked` and emits no `Content-Length` — the connection close (or a sentinel) signals completion. NDJSON beats a streamed JSON array here because each line is an independent, fully-parseable value, so a client can resume/skip on a bad line and doesn't need a streaming JSON parser that tracks array nesting.

The hard trade-off — and the part that distinguishes a senior answer — is **error handling after the status line has been sent**. Once you've written `200 OK` and started streaming, you *cannot* change your mind to a `500` if row 800,000 fails; the headers are already on the wire. The mitigations: write a final sentinel/trailer line indicating success vs partial failure, use HTTP trailers, or design the client to treat an abruptly-closed stream (no terminating record) as an error and retry — and crucially, make the export *resumable* via a cursor/checkpoint so a retry continues rather than restarts. Back-pressure is handled for you by TCP flow control (a slow client slows your writes, which slows your DB cursor reads), but you must keep the DB transaction/cursor open for the stream's duration, so set generous statement timeouts and, for truly huge exports, prefer the async `202` + job pattern (Q106) that produces a downloadable file rather than holding a connection and a DB cursor open for minutes.

#### Q117. [Coding] Write integration tests for a REST controller using `MockMvc` and Testcontainers, and explain what each catches that a unit test cannot.

**Problem:** Q93 discussed test levels; show the code. Demonstrate a slice/integration test that exercises real HTTP semantics (status codes, headers, content negotiation) and a full-stack test against a real database, and articulate the distinct bug classes each catches.

```java
@WebMvcTest(OrderController.class)        // loads ONLY the web layer; service is mocked
class OrderControllerWebTest {
    @Autowired MockMvc mvc;
    @MockBean OrderService service;

    @Test void create_returns201_withLocation() throws Exception {
        when(service.create(any())).thenReturn(new Order("ord_1"));
        mvc.perform(post("/orders").contentType(APPLICATION_JSON)
                        .content("""{"sku":"X1","qty":2}"""))
           .andExpect(status().isCreated())
           .andExpect(header().string("Location", "/orders/ord_1"))   // HTTP semantics!
           .andExpect(jsonPath("$.id").value("ord_1"));
    }

    @Test void unknownField_isRejected() throws Exception {
        mvc.perform(post("/orders").contentType(APPLICATION_JSON)
                        .content("""{"sku":"X1","isAdmin":true}"""))   // mass-assignment probe
           .andExpect(status().isBadRequest());
    }
}

@SpringBootTest @AutoConfigureMockMvc
@Testcontainers
class OrderControllerIT {
    @Container static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", db::getJdbcUrl);
        r.add("spring.datasource.username", db::getUsername);
        r.add("spring.datasource.password", db::getPassword);
    }
    @Autowired MockMvc mvc;

    @Test void optimisticLockConflict_returns409() throws Exception {
        // real DB, real @Version: concurrent updates surface a genuine 409 (Q70).
        // ... seed row, simulate stale If-Match, expect 412/409 ...
    }
}
```

The division of labor is the point. The `@WebMvcTest` **slice** loads only the controller, the JSON (de)serialization, validation, and exception-handler wiring, with the service mocked — so it's fast and it catches the *HTTP-contract* bugs that unit tests on the service can't see: wrong status code, missing `Location` header, content negotiation, that `isAdmin` is rejected (mass assignment, Q55), that a `problem+json` body is shaped correctly. A plain unit test calling `service.create()` directly verifies business logic but is *blind to the wire contract* — it never exercises the serializer, the status mapping, or the headers, which is precisely where REST APIs break for clients.

The Testcontainers **integration test** spins up a *real* PostgreSQL in Docker and runs the full stack against it, which catches the class of bug that mocks structurally cannot: actual SQL correctness, transaction boundaries, `@Version` optimistic-locking producing a real `409`/`412` under a stale `If-Match` (Q18/Q70), unique-constraint-backed idempotency (Q16), and JPA mapping mistakes. The reason to use Testcontainers over an in-memory H2 is fidelity — H2 doesn't behave like Postgres for upserts, JSON columns, isolation levels, or constraint error codes, so an H2 test can pass while production fails. The pyramid in practice: many fast unit tests for logic, a layer of `@WebMvcTest` slices for the HTTP contract, fewer Testcontainers integration tests for persistence/concurrency truths, and a thin top layer of consumer-driven contract tests (Pact) and end-to-end smoke tests — each level justified by a bug class the cheaper levels physically cannot detect.

### 🔴 Expert — extended (continued)

#### Q118. [Theory] Explain how HTTP/2 multiplexing and HTTP/3 (QUIC) change REST API design assumptions that were baked in during the HTTP/1.1 era. What advice becomes obsolete?

Several pieces of long-standing REST/HTTP advice were really workarounds for **HTTP/1.1's head-of-line blocking and connection limits**, and HTTP/2 multiplexing quietly invalidates them. Under HTTP/1.1, a browser opens ~6 connections per origin and each connection processes one request at a time in order, so many small requests are slow (connection setup + serialized round-trips). That constraint birthed advice like "bundle resources," "use domain sharding," "inline/embed aggressively to cut request count," and influenced API patterns toward chatty-avoidance and coarse endpoints. HTTP/2 multiplexes many concurrent streams over a *single* TCP connection with header compression (HPACK), so the per-request overhead that made chattiness expensive largely evaporates.

```
HTTP/1.1: [conn1: req A ... resp A][then req B ...]   serialized; 6 conns max
          one lost packet stalls everything behind it (TCP HOL blocking)

HTTP/2:   [conn1: streams A,B,C,D... interleaved frames]   concurrent
          BUT still one TCP conn -> a lost packet stalls ALL streams (TCP HOL)

HTTP/3:   [QUIC over UDP: independent streams]            concurrent
          a lost packet stalls ONLY its own stream (no cross-stream HOL)
```

The concrete design consequences. **Domain sharding becomes an anti-pattern** under HTTP/2 — splitting assets across hostnames now *hurts* because it forces multiple connections and defeats multiplexing and a single congestion-control context. **The N+1 / chatty-API penalty shrinks** (Q61): fetching 50 small resources over one multiplexed connection is far cheaper than it was, which weakens (though doesn't eliminate) the case for heavy embedding/expansion purely for round-trip reduction — server-side work and payload size now dominate over request *count*. **`Server Push` looked promising** for proactively sending sub-resources but was effectively abandoned (removed from Chrome) because it's hard to use without wasting bandwidth on already-cached resources; don't design around it.

HTTP/3 removes the remaining catch: HTTP/2 still rides one TCP connection, so a single lost packet causes **TCP head-of-line blocking** that stalls *all* multiplexed streams. QUIC (HTTP/3) runs over UDP with independent per-stream delivery, so a lost packet only stalls its own stream — a real win on lossy/mobile networks. The mature framing for an interview: HTTP/2+3 mean you should **stop micro-optimizing request count and connection topology** (no sharding, no aggressive bundling-for-its-own-sake) and instead optimize payload size, cacheability, and server latency — but they change *transport economics*, not REST *semantics*: status codes, idempotency, caching headers, and resource modeling are identical. The one durable caveat is that multiplexing makes it *easier* for clients to flood you with concurrent requests, so server-side concurrency limits and rate limiting matter more, not less.

#### Q119. [Theory] What is the difference between `401` with `WWW-Authenticate`, `403`, and the OAuth2 `insufficient_scope`/`invalid_token` errors? Design correct auth responses.

These are frequently conflated, and getting them right is both a spec-compliance and a client-ergonomics issue. **`401 Unauthorized`** means "you are not authenticated — I don't know who you are" and per RFC 9110 it **must** carry a `WWW-Authenticate` header telling the client *how* to authenticate (the challenge). **`403 Forbidden`** means "I know who you are, but you are not allowed to do this" — authentication succeeded, authorization failed; a `403` does not (and should not) carry `WWW-Authenticate` because re-authenticating won't help. The litmus test: would presenting (better) credentials change the outcome? If yes → `401`; if no → `403`.

```
GET /orders/42  (no token)
  -> 401 Unauthorized
     WWW-Authenticate: Bearer realm="api"

GET /orders/42  (expired/garbage token)
  -> 401 Unauthorized
     WWW-Authenticate: Bearer error="invalid_token",
                       error_description="The access token expired"

GET /orders/42  (valid token, but scope=read:profile only)
  -> 403 Forbidden
     WWW-Authenticate: Bearer error="insufficient_scope", scope="read:orders"

DELETE /orders/42 (valid token, valid scope, but it's another tenant's order)
  -> 404 Not Found   (BOLA defense: don't reveal existence; Q84)
```

The OAuth2 Bearer token spec (RFC 6750) refines this with `error` codes carried *inside* `WWW-Authenticate`. **`invalid_token`** (expired, malformed, revoked) pairs with `401` — the token itself is the problem, so a refresh/re-auth can fix it, and a smart client sees `error="invalid_token"` and triggers its refresh-token flow automatically rather than logging the user out. **`insufficient_scope`** pairs with `403` and crucially advertises the *required* `scope`, so the client knows which scope to request on the next authorization — this is what lets a client recover gracefully ("I need `read:orders`, let me step up consent") instead of failing opaquely. Conflating these (e.g., returning a bare `403` for an expired token) breaks the client's refresh logic and forces unnecessary re-logins.

The expert subtlety is the **`403`-vs-`404` decision for object-level authorization** (BOLA/IDOR, Q84). When an authenticated, sufficiently-scoped user requests an object that belongs to someone else, returning `403` *confirms the object exists*, which leaks information (an attacker can enumerate valid ids by distinguishing `403` from `404`). For resources where existence itself is sensitive, return `404` to deny even the knowledge that the object exists — trading strict semantic honesty for security. The synthesis: `401` = who-are-you (always with a `WWW-Authenticate` challenge); `403` = you-can't (sometimes downgraded to `404` to avoid leaking existence); and the OAuth `invalid_token`/`insufficient_scope` codes are the machine-readable details that let clients *recover automatically* rather than just fail.

#### Q120. [Practical] Design API versioning and deprecation as a complete lifecycle: signaling, the `Sunset`/`Deprecation` headers, brownout testing, and the contract you owe consumers.

Versioning is usually discussed as "URI vs header vs media-type" (covered in Q51/Q53); the senior question is the *operational lifecycle* — how a version is born, deprecated, and killed without breaking trust. The contract you owe consumers is **predictability**: a documented support window, advance warning through machine-readable signals, and no silent breakage. I'd design the whole arc, not just the URL scheme.

```
Lifecycle:   ACTIVE ──announce──► DEPRECATED ──window (e.g. 6–12mo)──► SUNSET ──► REMOVED
Signals on every response of a deprecated version:
  Deprecation: Sat, 01 Nov 2025 00:00:00 GMT   (RFC 9745 — when it became deprecated)
  Sunset:      Wed, 01 Jul 2026 00:00:00 GMT    (RFC 8594 — when it will stop working)
  Link: <https://api.acme.com/deprecation/v1>; rel="sunset"   (human migration guide)
Plus: changelog, email to known integrators, dashboards of per-consumer usage.
```

The **machine-readable signals** are the backbone. The `Deprecation` header (now RFC 9745) marks *when* an endpoint/version became deprecated, the `Sunset` header (RFC 8594) declares the date it will *stop working*, and a `Link; rel="sunset"` points to the migration guide. Emitting these on every deprecated response means a diligent client can *detect deprecation programmatically* and even alert its own developers — far better than burying the notice in a changelog nobody reads. This is the difference between "we told them" (an email) and "the API tells them on every call" (headers), and mature platforms do both.

**Brownout testing** is the technique that separates a thoughtful sunset from a flag-day disaster. Before fully removing a deprecated version, you deliberately return errors (or add latency) for *short, scheduled windows* — e.g., the deprecated endpoint returns `410 Gone` for 5 minutes at announced times in the weeks before removal. This surfaces the consumers who *still* depend on it (their integration breaks briefly and loudly during a window you control and can roll back), giving them a visceral nudge to migrate while you still have an escape hatch — rather than discovering the long tail only at the irreversible final cutover. Pair it with **per-consumer usage telemetry** so removal is a data-driven decision (you remove when usage approaches zero, Q112's lesson), not a calendar guess.

The full lifecycle, then: ship the new version additively and run old + new in parallel; **announce deprecation** with `Deprecation`/`Sunset` headers, changelog, and direct outreach; honor a generous, documented **support window**; run **brownouts** to flush out and pressure the long tail; and only **remove** (returning `410 Gone`, not `404`, so the status itself says "this existed and is intentionally gone") once telemetry confirms negligible use. The interview-grade framing is that versioning's hard part isn't the URL — it's treating **backward compatibility and a predictable deprecation process as a product feature**, because integrator trust is the actual asset, and a single surprise breakage costs more goodwill than years of careful signaling earned.

#### Q121. [Theory] Why is `PUT` for resource *creation* subtle? Compare `PUT` (client-chosen id) vs `POST` (server-chosen id) for create, including idempotency, conflicts, and `Location`.

Most people learn "`POST` creates, `PUT` updates," but `PUT` *can* create — and understanding when reveals a deeper grasp of HTTP semantics. The deciding factor is **who chooses the resource's identifier**. `POST /orders` creates a resource at a *server-assigned* URI (the server mints the id and returns it via `Location` + `201`); `PUT /orders/{id}` says "make the resource at *this exact URI* have this representation" — so if the client already knows/chooses the id (a UUID, a natural key like a username, an external reference), `PUT` to that URI both creates (if absent) and replaces (if present).

```
POST /orders                     PUT /orders/ord_clientUuid
  body: {...}                       body: {full representation}
  -> 201 Created                    -> 201 Created (if new) / 200 (if replaced)
     Location: /orders/ord_42          (no Location needed: client knows the URI)
  Server picks id.                  Client picks id.
  NOT idempotent (2 calls =         IDEMPOTENT (2 calls = same resource,
    2 orders).                        same final state).
  Use when: server owns identity,   Use when: client owns/knows identity,
    sequential ids, "I don't care     natural keys, offline-generated UUIDs,
    what the id is."                  upserts, retry-safety matters.
```

The **idempotency contrast** is the crux and the practical motivation. `POST` create is not idempotent — a client that times out and retries `POST /orders` creates a *second* order, which is exactly the problem idempotency keys solve (Q16). `PUT` create *is* idempotent by definition: `PUT /orders/ord_abc` twice yields one resource in the same final state, so a retry is naturally safe with no idempotency-key machinery. That makes client-chosen-id `PUT` a clean way to get retry-safe creation: the client generates a UUID, `PUT`s to that URI, and can retry freely. This is why some APIs (and most key-value/object stores like S3) use `PUT`-with-client-id as the create primitive.

The subtleties that trip people up. **Conditional create** uses `If-None-Match: *` on the `PUT` to mean "create only if it doesn't already exist" — if it exists, the server returns `412 Precondition Failed`, preventing an accidental overwrite (the "lost update on create" case). **`PUT` must carry the *full* representation** (it replaces), so it's wrong for partial updates — that's `PATCH`. **Security**: client-chosen ids can be guessable/enumerable, so don't let id choice leak information or enable collisions across tenants — namespace ids per tenant and validate. And `PUT` create shifts id-uniqueness responsibility to a space the client controls, so you need a uniqueness constraint and a defined behavior on collision. The synthesis: choose `POST` when the *server* owns identity and you accept non-idempotent create (with idempotency keys for retry-safety); choose `PUT` when the *client* legitimately owns or can generate the identifier and you want creation to be idempotent for free.

#### Q122. [Coding] Implement field-level (property) authorization so different roles can read/write different fields of the same resource, and explain why endpoint-level authz is insufficient.

**Problem:** A `customer` and a `support_agent` both `GET`/`PATCH` `/orders/{id}`, but the agent may see and set fields (internal notes, `status`, risk score) the customer must not. Endpoint-level authorization (can this role call this route?) is too coarse — this is OWASP API3 "Broken Object Property Level Authorization" (Q55), the *property*-level cousin of BOLA.

```java
// Read side: project the response per role.
public OrderResponse toResponse(Order o, Principal p) {
    var b = OrderResponse.builder()
            .id(o.getId()).total(o.getTotal()).status(o.getStatus());   // public fields
    if (p.hasRole("support_agent") || p.hasRole("admin")) {
        b.internalNotes(o.getInternalNotes())                           // restricted fields
         .riskScore(o.getRiskScore());
    }
    return b.build();   // customer literally never receives the restricted fields
}

// Write side: allow-list settable fields PER ROLE, server-side.
private static final Map<String, Set<String>> WRITABLE = Map.of(
    "customer",      Set.of("shippingAddress", "note"),
    "support_agent", Set.of("shippingAddress", "note", "status", "internalNotes"));

public void applyPatch(Order o, JsonNode patch, Principal p) {
    Set<String> allowed = p.getRoles().stream()
            .flatMap(r -> WRITABLE.getOrDefault(r, Set.of()).stream()).collect(toSet());
    patch.fieldNames().forEachRemaining(field -> {
        if (!allowed.contains(field))
            throw new ForbiddenException("Field not writable for your role: " + field);
        setField(o, field, patch.get(field));   // apply only permitted fields
    });
}
```

Endpoint-level authz answers "may this principal invoke `PATCH /orders/{id}`?" — but once inside the handler, *every* field in the body is treated equally, so a customer who's allowed to PATCH their order can also set `status=REFUNDED` or `riskScore=0` unless you authorize **per field, per role, server-side**. That's the gap: the route check passed, the object check (BOLA) passed (it's their own order), and yet a property they shouldn't control was writable. Fixing it requires the two halves above — **read projection** (restricted fields are never serialized into a customer's response, so they can't even be observed) and a **write allow-list keyed by role** (the request can only ever touch the intersection of "fields that exist" and "fields this role may set").

The design rationale and pitfalls. **Allow-list, never block-list** (same reasoning as Q55): enumerate what each role *may* set, so a newly-added sensitive field defaults to *not writable* until you opt it in — a block-list fails open when someone adds a field and forgets to blacklist it. **Read and write authz are separate concerns**: a field can be readable-but-not-writable (a customer sees `status` but can't change it), so you need projection on the way out *and* an allow-list on the way in; don't assume "if you can see it you can set it." **Reject, don't silently drop**, when a role sends a forbidden field — silently ignoring it hides client bugs and can mask probing; a `403`/`422` makes the boundary explicit. The architectural point an interviewer wants: authorization in REST is *three-dimensional* — route (can you call it), object (is it yours, BOLA), and property (which fields, BOPLA) — and a system that enforces only the first two has a real, exploitable hole that no amount of route guarding closes.

#### Q123. [Practical] How do you design a REST API that must be backward- and forward-compatible at the *data* level across independently-deployed producers and consumers? Apply schema-evolution rules.

This is the distributed-systems heart of API evolution: producers and consumers deploy independently and at different times, so at any moment an *old* consumer may read a *new* producer's payload (forward compatibility) and a *new* consumer may read an *old* producer's payload (backward compatibility). You can't coordinate a flag day across thousands of clients, so the contract must tolerate version skew in *both* directions. The discipline is a small set of **schema-evolution rules** plus the **tolerant-reader** principle (Q49).

```
                  Producer change          Backward compat?   Forward compat?
                  -----------------------   (new producer,     (old producer,
                                             old consumer)      new consumer)
Add OPTIONAL field    safe                      yes               yes (consumer
                                                                   defaults it)
Add REQUIRED field    BREAKS old consumers      no*               -    (*if they
                      that must produce it                              validate strictly)
Remove a field        BREAKS consumers          no                -     that read it
Rename a field        = remove + add            no                no
Change a field type   BREAKS                    no                no
Widen an enum (add    risky                     consumer must      yes
  a new value)                                  handle unknown
Add a new endpoint    safe                      yes                yes
```

The rules reduce to a few imperatives. **Only make additive, optional changes** to a published surface; never remove, rename, or retype a field that consumers read. **Readers must be tolerant**: ignore unknown fields (don't fail deserialization on them — disable strict "fail on unknown" for *external* inputs even though you keep it strict for your own request bodies as a mass-assignment guard, Q55), and supply defaults for absent fields. **Writers must be conservative**: emit exactly the documented shape, don't reorder-dependent, don't send fields whose meaning isn't agreed. The classic forward-compat trap is **enum evolution**: a producer adds a new `status: "DISPUTED"` value, and an old consumer that `switch`es exhaustively over the enum either crashes or mishandles it — so consumers must treat enums as open and have a default/unknown branch, and producers should introduce new enum values cautiously and document them as a possibility from day one.

The expert framing distinguishes **wire-format help from discipline**. Schema technologies like Protobuf and Avro *encode* some of these rules — Protobuf field numbers make rename-safe and unknown-field-preserving behavior automatic, Avro's reader/writer schema resolution formalizes backward/forward/full compatibility and a schema registry can *enforce* it in CI. JSON over REST gives you none of that for free, so you enforce the rules by convention, contract tests, and the OpenAPI breaking-change gate (Q108). The deeper point: "compatible at the data level" is a *property of the change*, not of a version number — bumping `/v1`→`/v2` is what you do when you *can't* make a change compatibly, so the goal is to make as many changes as possible *additively* and reserve version bumps for the genuinely breaking ones. And "full compatibility" (both directions) is the strictest and the one you want for independently-deployed services, because you control neither deploy order nor timing.

#### Q124. [Theory] Explain the security and caching pitfalls of putting state-changing or sensitive operations behind `GET`, and the precise reason GET must stay safe. Give real-world failure cases.

`GET` (and `HEAD`/`OPTIONS`) being **safe** — no observable side effects — is not pedantry; an entire ecosystem of intermediaries and agents *relies* on it, and violating it produces spectacular, recurring failures. The contract is that anything in the chain may issue a `GET` *speculatively, repeatedly, and without user intent*: browsers prefetch links, search-engine crawlers follow every link they find, antivirus and link-preview bots (Slack, iMessage, email scanners) fetch URLs, and caches/proxies may replay `GET`s. If a `GET` mutates state, every one of these triggers the mutation unbidden.

```
Anti-pattern:  GET /accounts/42/delete        GET /orders/9/approve?token=...
               GET /emails/unsubscribe?id=...  (sensitive token in URL)

Real failures:
- 2006 "Google Web Accelerator" prefetched links -> apps using GET for delete/
  edit had records silently wiped as the prefetcher walked every link.
- Antivirus / link-scanner bots follow one-click email-action GET links ->
  unsubscribes, confirmations, and "approve" links fire without the human.
- A crawler indexes an admin page full of GET action links -> mass mutation.
```

The **caching** failures are the second half. A `GET` is presumptively cacheable, so a state-changing `GET` can be served from a cache and *never reach your server* on a retry (the "mutation" silently no-ops the second time), or a CDN/proxy can serve one user a cached response that was a side effect of *another* user's request. Worse for *sensitive* `GET`s: putting a token, password-reset id, or session token **in the URL/query string** leaks it into places designed to retain `GET` URLs — browser history, server access logs, proxy logs, the `Referer` header sent to third-party sites, and CDN cache keys (Q96's logging-PII problem). A bearer credential in a query string is a credential written to a dozen logs.

The precise reason `GET` *must* stay safe, then, is that **safety is the contract intermediaries depend on to act autonomously** — prefetchers, crawlers, scanners, and caches are *allowed* to issue and replay `GET`s precisely because the spec promises no side effects, so the moment you break that promise you've armed the entire internet's automation to fire your mutations. The fixes are mechanical once you internalize this: use the method that matches intent (`POST`/`PUT`/`DELETE`/`PATCH` for anything that changes state), put sensitive tokens in the request *body* or an `Authorization` header (never the URL), mark genuinely uncacheable reads `Cache-Control: no-store`, and for one-click email actions either require a `POST` (an interstitial page that POSTs) or treat the `GET` link as merely *navigational* to a confirmation page that performs the actual mutation via `POST`. This single discipline — methods match intent, GET stays safe, secrets stay out of URLs — closes a whole family of CSRF, cache-poisoning, and credential-leak bugs at once.

#### Q125. [Practical] You must expose a "search" capability that's far richer than simple filters (full-text, ranges, boolean logic). How do you design it RESTfully without reinventing a query language, and when do you break REST?

This is a genuine tension: REST models *resources*, but search is fundamentally a *query*, and rich search (full-text relevance, geo, faceting, boolean combinations, ranges) doesn't map cleanly onto "filter a collection by field equality." The design space runs from "stay pure with query params" to "accept that search is RPC-shaped," and the senior answer is knowing *where on that spectrum* a given requirement lands and justifying the break from purity deliberately.

```
Option                         Shape                              When
-----------------------------  ---------------------------------  --------------------
1. Query params (filters)      GET /orders?status=open&           Simple, cacheable,
                                 min_total=100&created_after=...    bookmarkable. Best
                                                                    for modest filtering.
2. Structured query in params  GET /products?q=red+shoes&         Full-text + a few
                                 facets=brand,size&sort=relevance   facets; still GET.
3. Search as a sub-resource    POST /products/search              Complex body: nested
   (query in the BODY)           { "query": {...boolean...},        boolean logic, ranges,
                                   "facets":[...], "page":{...} }    long queries. Pragmatic.
4. Dedicated query language    GraphQL / OData / Elasticsearch    Arbitrary client-defined
                                 DSL passthrough                    queries; huge power +
                                                                    coupling/abuse surface.
```

For modest needs, **stay with `GET` and query parameters** (whitelisted filter/sort fields, Q11) because it preserves everything REST gives you: the search is a cacheable, bookmarkable, idempotent, link-shareable URL, and a CDN can cache popular searches. Push this as far as it reasonably goes — `?q=`, ranges (`min_total`/`created_after`), a bounded `facets` list, `sort`. The moment the query outgrows a URL — deeply nested boolean logic, polygons, dozens of facets, or a query string long enough to hit the ~2–8 KB URL-length limits proxies enforce — you hit the wall, and that's the *legitimate* trigger to break REST.

The pragmatic break is **`POST /resources/search` with the query in the request body** (option 3). This is technically a violation of `GET`-for-reads purity — the operation is safe and idempotent but uses `POST` — yet it's a widely accepted, defensible pattern (Elasticsearch, GitHub, Stripe all do it) because a body has no length limit, carries structured JSON naturally, and avoids leaking complex queries into logs/URLs. You consciously trade away GET's caching and bookmarkability for expressiveness; mitigate by allowing clients to also store/replay a search via a "saved search" resource (`POST` creates it, `GET /searches/{id}/results` is cacheable). Only escalate to a **full query language** (GraphQL, OData) when clients genuinely need *arbitrary* self-defined queries across many shapes — and do so eyes-open about the costs the file's pitfalls call out: query-complexity DoS (unbounded queries are an attack surface — you *must* bound depth, cost, and result size), caching loss, and the operational burden few teams actually need.

So the rule I'd state: keep search RESTful and cacheable with whitelisted `GET` params for as long as it fits a URL; graduate to `POST .../search` with a body when expressiveness demands it (a deliberate, documented purity break, not an accident); and reserve a real query language for the rare case of arbitrary client-defined queries — always with hard limits on query cost, because the richer the search, the easier it is to turn into a denial-of-service or a cost-amplification attack (Q47).

#### Q126. [Coding] Implement request validation that aggregates ALL field errors into one RFC 9457 response (with an `errors[]` extension) instead of failing on the first error.

**Problem:** A client submitting a form wants *every* invalid field reported in one round-trip, not a frustrating fail-first-field-then-resubmit loop. Bean Validation collects all violations; the job is to surface them in a single `problem+json` (Q13) with a structured `errors` array so clients can map errors to form fields.

```java
public record CreateOrderRequest(
    @NotBlank String sku,
    @Min(1) @Max(999) int qty,
    @Email String contactEmail,
    @NotNull @PastOrPresent Instant requestedAt) {}

@RestControllerAdvice
public class ValidationAdvice {

    // Thrown when @Valid @RequestBody fails — carries ALL violations, not just one.
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail onInvalid(MethodArgumentNotValidException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY); // 422
        pd.setType(URI.create("/errors/validation"));
        pd.setTitle("Request validation failed");

        // Aggregate every field error into a stable, machine-readable array.
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> Map.of(
                "field",  fe.getField(),
                "code",   fe.getCode(),                 // e.g. "Min" — stable, branchable
                "detail", fe.getDefaultMessage()))      // human text
            .toList();
        pd.setProperty("errors", errors);               // RFC 9457 extension member
        pd.setProperty("traceId", MDC.get("traceId"));  // tie to logs (Q73)
        return pd;
    }
}
```

```json
{
  "type": "/errors/validation", "title": "Request validation failed", "status": 422,
  "traceId": "a1b2c3",
  "errors": [
    { "field": "qty",          "code": "Max",      "detail": "must be <= 999" },
    { "field": "contactEmail", "code": "Email",    "detail": "must be a well-formed email" },
    { "field": "requestedAt",  "code": "NotNull",  "detail": "must not be null" }
  ]
}
```

The design choices that matter. **Aggregating all violations** is the UX win: Bean Validation already evaluates every constraint and `MethodArgumentNotValidException` carries the full set, so failing-fast on the first one throws that information away and forces the client into a tedious resubmit cycle — collecting them is nearly free and far kinder. **Using `422 Unprocessable Content`, not `400`**, is the correct status here because the JSON parsed fine (syntactically valid) but is *semantically* invalid (`qty` out of range) — `400` is for malformed syntax (broken JSON), a distinction the file establishes in Q3 and that clients legitimately branch on. **An RFC 9457 *extension member* (`errors`)** is the right place for the array: `problem+json` explicitly allows additional members beyond the standard ones, so you stay spec-compliant while adding structure.

The non-obvious quality points an interviewer probes. Each error carries a **stable `code`** (`Min`, `Email`, `NotNull`) *separate from* the human `detail`, so clients branch on the code and don't parse English (the same "branch on `type`/`code`, not prose" principle the takeaways stress) — and you can localize `detail` without breaking client logic. The `field` uses a path (`items[0].sku` for nested/collection violations) so a client can map each error to a specific form input. Attaching `traceId` ties the client's error to your logs and trace (Q73). One caveat worth voicing: don't over-share — validation messages should describe the *constraint*, never echo back sensitive submitted values or reveal internal field names that leak schema details (Q96). The result is the ergonomic ideal: one request, every problem reported, machine-readable and localizable, mapped to fields, and joined to your observability.

#### Q127. [Theory] Compare cookie-based sessions, bearer JWTs, and opaque tokens for REST authentication. How does each interact with statelessness, caching, CSRF, revocation, and horizontal scaling?

These three auth mechanisms embody different trade-offs against REST's constraints, and the "right" choice depends on which property you most need. **Cookie-based sessions** store a session id in a cookie; the server keeps session state (in memory/Redis/DB). **Bearer JWTs** are self-contained signed tokens carried in `Authorization: Bearer`; the server validates the signature and trusts the claims without a lookup. **Opaque tokens** are random strings carried as bearer tokens but meaningless without a server-side lookup (or token-introspection call) that resolves them to identity/scopes.

```
                  | Cookie session     | Bearer JWT          | Opaque token
------------------|--------------------|---------------------|--------------------
State location    | server (store)     | in the token        | server / authz svc
REST-stateless?   | NO (server state)  | YES (self-contained)| partial (lookup)
Sent automatically| YES (browser)      | NO (explicit header)| NO (explicit header)
CSRF risk         | YES (ambient cred) | no (not auto-sent)  | no (not auto-sent)
Revocation        | easy (delete row)  | HARD (valid till exp)| easy (delete/introspect)
Horizontal scale  | needs shared store | trivial (no store)  | needs store/introspect
Caching impact    | Vary/no-store user | same                | same
```

The central tension is **statelessness vs revocation**, and it's where JWTs are most misunderstood (Q57). A JWT is the only one of the three that's *truly* stateless — any server can validate it with just the signing key, so horizontal scaling is trivial (no shared session store, no sticky sessions), which is exactly REST's statelessness constraint paying off. But that same self-containment makes **revocation hard**: a stolen/compromised JWT is valid until it expires, because there's no server-side record to delete. The industry workaround — short-lived access tokens plus a long-lived refresh token — *reintroduces server state* (the refresh token must be tracked/revocable), so you've partly given back the statelessness you bought; an honest answer names this irony. Cookie sessions and opaque tokens revoke instantly (delete the server record) but require a shared, available session store, which becomes a scaling dependency and a single point of failure you must make HA.

The other axes decide the rest. **CSRF**: cookies are sent *automatically* by the browser on every request to the origin, which is what enables Cross-Site Request Forgery — so cookie auth *requires* CSRF defenses (`SameSite` cookies, anti-CSRF tokens). Bearer tokens in an `Authorization` header are *not* auto-sent, so they're immune to classic CSRF — but they're readable by JS if stored in `localStorage`, exposing them to XSS, so it's a trade of one attack surface for another (the common compromise is an `HttpOnly`, `Secure`, `SameSite` cookie *for the token* to get the best of both). **Caching**: all three make responses user-specific, so authenticated responses need `Cache-Control: private`/`no-store` and correct `Vary` to keep shared caches from leaking one user's data to another (Q79/Q96). The synthesis: pick **opaque tokens (or sessions) when instant revocation and a security-grade audit trail matter** (banking, anything where a leaked token must die immediately) and you can run a HA token store; pick **JWTs when statelessness and frictionless horizontal scale dominate** and you can live with short token lifetimes as your revocation story — and recognize that "stateless JWT" is mostly a myth once you add refresh tokens, so the real question is *where* you're willing to keep state, not whether.

#### Q128. [Coding] Implement an idempotent webhook *receiver* that handles at-least-once delivery, out-of-order events, and duplicate delivery correctly.

**Problem:** The flip side of Q86/Q101 — you *consume* a partner's webhooks. They deliver at-least-once (so duplicates happen), may deliver out of order, and expect a fast `2xx` or they retry. A naive handler double-processes events or applies a stale update over a newer one. Build the receiver correctly.

```java
@PostMapping("/webhooks/payments")
public ResponseEntity<Void> receive(@RequestBody byte[] raw,
                                     @RequestHeader("X-Signature") String sig,
                                     @RequestHeader("X-Timestamp") String ts,
                                     @RequestHeader("X-Event-Id") String eventId) {
    if (!verifier.verify(new String(raw, UTF_8), sig, ts, secret))   // Q101
        return ResponseEntity.status(401).build();

    PaymentEvent evt = mapper.readValue(raw, PaymentEvent.class);

    // 1) DEDUPE on the provider's event id (at-least-once -> duplicates are normal).
    //    Unique constraint on event_id makes the insert the dedupe primitive.
    try {
        processedRepo.insert(new Processed(eventId, Instant.now()));
    } catch (DuplicateKeyException dup) {
        return ResponseEntity.ok().build();   // already handled -> ack, do NOT re-apply
    }

    // 2) ORDER guard: ignore an event older than the state we already have.
    Payment p = paymentRepo.find(evt.paymentId()).orElseGet(() -> Payment.empty(evt.paymentId()));
    if (evt.sequence() <= p.getLastAppliedSequence()) {
        return ResponseEntity.ok().build();   // stale/out-of-order -> ack and drop
    }
    p.apply(evt);                              // monotonic: only forward transitions
    p.setLastAppliedSequence(evt.sequence());
    paymentRepo.save(p);

    // 3) Fast ack. Heavy work (emails, downstream calls) goes on a queue, NOT inline,
    //    so we respond quickly and the sender doesn't time out and retry.
    asyncWork.enqueue(eventId);
    return ResponseEntity.ok().build();        // 2xx = "received", stops retries
}
```

The three correctness pillars map to the three realities of webhook delivery. **Deduplication keyed on the provider's event id** handles at-least-once delivery: a unique constraint turns "have I seen this event?" into an atomic insert that either succeeds (first time, process it) or throws a duplicate-key error (already seen, just ack) — this is the same insert-once idempotency primitive as Q16, applied to inbound events. Critically, on a duplicate you still return `2xx`, because a non-2xx makes the sender retry forever; "I already handled this" and "I just handled this" both mean *ack*. **The ordering guard** handles out-of-order delivery: webhooks are rarely globally ordered, so an event must carry a monotonic `sequence` (or version/timestamp), and you ignore any event not strictly newer than the last applied — otherwise a delayed "payment.created" arriving after "payment.captured" would clobber the newer state.

The performance pillar is the **fast ack**: senders typically time out in a few seconds and treat slowness as failure (triggering a retry, then the "thundering consumer" amplification of Q86), so you must do the minimum synchronously — verify, dedupe, record, apply the state transition — and push side effects (emails, downstream API calls, analytics) onto a queue processed asynchronously. Doing heavy work inline is the single most common webhook-receiver bug: it turns one slow dependency into a retry storm from the sender. The combination — **verify signature, dedupe on event id, drop stale by sequence, ack fast, defer heavy work** — is what makes an at-least-once, possibly-reordered, retry-prone delivery stream produce effectively-once, order-correct processing. The same idempotency-receiver discipline from the producer side (Q54) applies here from the consumer side, which is the unifying insight: *you cannot control delivery semantics, so you engineer the receiver to be safe under the worst delivery the network can produce.*

#### Q129. [Practical] An interviewer hands you a poorly-designed legacy API and asks you to critique it and propose a remediation path that doesn't break existing clients. Walk through your method.

This is a senior signal question — they're testing diagnostic structure and pragmatism, not whether you can list rules. I'd resist the urge to immediately rewrite and instead apply a **systematic critique framework**, then a **non-breaking remediation strategy**, because the constraint "don't break existing clients" is the whole difficulty: a greenfield redesign is easy; evolving a live, depended-upon API is the actual skill.

```
Given (typical legacy smells):
  POST /api/getUserOrders        (verb in path, POST for a read)
  -> 200 OK  { "success": false, "error": "not found" }   (lies about status)
  GET  /api/orders?page=5000     (offset pagination, no caps)
  Mixed: some camelCase, some snake_case; secrets in query string;
  no versioning; stack traces in 500 bodies; binds request body to entity.

Critique axes (score each):
  Semantics   verbs-as-nouns? correct status codes? safe/idempotent honored?
  Contract    consistent naming/casing? errors machine-readable? documented?
  Scale       pagination bounded? rate limited? N+1? query caps?
  Security    BOLA/mass-assignment? secrets in URLs? info leakage in errors?
  Evolvability versioned? additive-change discipline? deprecation path?
```

The **critique** walks those axes against what good looks like (every item maps to an earlier question in this file): `POST /api/getUserOrders` is RPC-over-HTTP (Q23) — should be `GET /users/{id}/orders`; `200 OK` with `{"success": false}` defeats every client and intermediary that branches on status (Q3) — errors must use real `4xx`/`5xx` and `problem+json` (Q13); unbounded offset pagination is both a performance cliff and a DoS vector (Q9/Q47); binding the body to an entity is mass assignment (Q55); secrets in the query string leak to logs and `Referer` (Q124/Q96); stack traces in `500`s are information disclosure. I'd prioritize findings by **risk × blast radius** — security holes (BOLA, mass assignment, leaked secrets) first, then correctness (status codes), then ergonomics (naming) — rather than listing everything as equally urgent, because a senior engineer triages.

The **remediation path** is where "without breaking clients" forces discipline, and it's the expand-and-contract / strangler approach (Q112/Q120). You *cannot* fix `getUserOrders` in place — clients call it — so you **introduce the corrected surface alongside** the old one: stand up `GET /v1/users/{id}/orders` with proper status codes, cursor pagination, DTO-mapped inputs, and `problem+json`, while the legacy endpoint keeps working. A **facade/strangler layer** can route legacy calls to the new implementation (translating the old request/response shape) so you fix the *internals* — the security holes, the entity binding, the leaked stack traces — *immediately and invisibly* even while the ugly external shape persists. Security fixes that *can* be made transparently (stop leaking stack traces, enforce object-level authz, cap page size with a sane default, move secrets out of logs) you ship right away because they don't change the contract's success-path shape; shape changes (naming, status codes, pagination model) go behind the new versioned surface.

Then you **migrate and sunset** on data, not hope: instrument per-consumer usage, publish the new surface with a migration guide, emit `Deprecation`/`Sunset` headers on the legacy endpoints (Q120), run brownouts to flush out the long tail, and remove only when telemetry shows negligible use. The meta-answer I'd give the interviewer: a good critique is **structured** (semantics → contract → scale → security → evolvability) and **prioritized by risk**, and a good remediation **separates invisible internal fixes (do now) from contract changes (do behind a new version)** — because the legacy API's real value is the clients depending on it, so the goal is to *strangle* the bad design gradually while preserving that trust, not to satisfy an aesthetic urge to rewrite it in one breaking swing.

## ✅ Key Takeaways

- **REST is constraints, not a protocol.** Statelessness, uniform interface, and cacheability are what buy you scalability and evolvability — most "REST" APIs stop at Richardson Level 2 (proper verbs + status codes) and that's usually fine.
- **Use HTTP correctly:** nouns for resources, methods for verbs, accurate status codes, `Location` on 201, 401 vs 403, 400 vs 422, 409 for conflicts.
- **Safe + idempotent** classification underpins safe retries; make `POST` retry-safe with **idempotency keys** (the Stripe/Adyen model).
- **Cursor pagination** beats offset at scale (O(1) vs O(offset)); whitelist filter/sort fields to protect the DB.
- **Standardize errors** with RFC 9457 `problem+json` and branch on `type`, not human text; attach a `traceId`, never a stack trace.
- **Caching is a first-class lever:** `Cache-Control` for freshness, `ETag`/`If-None-Match` for cheap revalidation (304), `If-Match` for optimistic concurrency (412).
- **Rate limit at the gateway** (token bucket is the default), return `429` + `Retry-After` + `RateLimit-*` headers.
- **Evolve via additive, backward-compatible changes**; version (URI path for public APIs) only as a last resort; treat backward compatibility as a product feature.
- **Security:** BOLA/IDOR is the #1 API vulnerability — enforce per-object authorization everywhere; avoid mass assignment with explicit DTOs; never put secrets/PII in URIs.
- Know **when not to use REST**: GraphQL for diverse client field needs, gRPC for internal low-latency RPC.

## ⚠️ Common Pitfalls

- **RPC over HTTP** disguised as REST: verbs in URIs (`/getOrders`), everything `POST`, `200 OK` with `{"success": false}` error bodies.
- Returning **200 for errors**, or `500` for client mistakes that should be `4xx` — breaks client error handling and intermediaries.
- **Offset pagination on huge tables** → slow deep pages and inconsistent results under concurrent writes.
- **Mass assignment**: binding request bodies directly to JPA entities, letting callers set `isAdmin`/`balance`. Always map through explicit DTOs.
- Treating `PATCH` as idempotent by default (JSON Patch `add` is not) or sending a partial *resource* instead of a *change description*.
- Forgetting `Vary: Accept` (or per-user data on shared cache keys) → caches serving the wrong representation to the wrong client.
- **Breaking changes without a version bump or deprecation window** — silently removing/renaming fields shatters integrator trust.
- No pagination caps, no rate limits, unbounded filters → trivial DoS / cost amplification (OWASP API4).
- Leaking stack traces, SQL, or internal hostnames in error responses (information disclosure).
- Over-aggressive HATEOAS or generic query languages (OData) added for purity, paid for in complexity nobody uses.

## 📚 Further Reading

- **Roy Fielding**, *Architectural Styles and the Design of Network-based Software Architectures* (2000 dissertation, Ch. 5) — the original REST definition.
- **RFC 9110** (HTTP Semantics), **RFC 9111** (HTTP Caching), and **RFC 9457** (Problem Details for HTTP APIs) — the authoritative, current specs.
- **Mark Masse**, *REST API Design Rulebook* (O'Reilly) — concise conventions for URIs, methods, and metadata.
- **Mike Amundsen**, *RESTful Web API Patterns and Practices Cookbook* (O'Reilly, 2022) — hypermedia, evolvability, and real-world patterns.
- **Stripe API documentation & Engineering blog** — idempotency keys, date-based versioning, and backward-compatibility-as-a-feature (canonical industry case study).
- **OWASP API Security Top 10 (2023)** and the **Microsoft / Google / Zalando REST API Guidelines** (public style guides) — practical, opinionated production standards.
