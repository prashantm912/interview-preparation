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
