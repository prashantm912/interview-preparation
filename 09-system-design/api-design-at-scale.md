# API Design at Scale

A staff-engineer's interview guide to designing APIs that survive growth, mobile clients, partner integrations, and decade-long lifecycles: idempotency keys, pagination strategies, versioning, rate limiting, backward compatibility, the Backend-for-Frontend pattern, error contracts, bulk endpoints, and long-running operations. The recurring theme is that an API is a *contract* with humans and machines you will never meet — and at scale, every careless default becomes a migration you can't unship. Knowledge current through 2026.

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

### Q1. [Theory] What does it mean for an HTTP method to be idempotent, and which methods are idempotent vs safe?

**Idempotent** means that making the same request N times has the same effect on server state as making it once. **Safe** is stronger in one direction: a safe method does not modify state at all (it's read-only). The two properties are independent axes, and conflating them is a classic mistake.

Per the HTTP spec: `GET`, `HEAD`, and `OPTIONS` are **safe and idempotent** (they don't mutate, so repeating is trivially harmless). `PUT` and `DELETE` are **idempotent but not safe** — `PUT /users/42` sets the resource to a known state, so doing it twice lands you in the same place; `DELETE /users/42` leaves the resource gone whether you call it once or five times (the *response* may differ — 200 then 404 — but the *state* is identical). `POST` is **neither** by default: `POST /orders` twice typically creates two orders.

```
Method   | Safe | Idempotent | Typical use
---------|------|------------|----------------------------
GET      |  ✓   |     ✓      | read a resource
HEAD     |  ✓   |     ✓      | read headers only
PUT      |  ✗   |     ✓      | replace/create at known URI
DELETE   |  ✗   |     ✓      | remove a resource
POST     |  ✗   |     ✗      | create / non-idempotent action
PATCH    |  ✗   |  not nec.  | partial update (depends on body)
```

This matters because the *infrastructure* between client and server — proxies, load balancers, client retry libraries — is allowed to assume these semantics. A proxy may safely retry a failed `GET` but must not blindly retry a `POST`. Respecting the contract lets the entire ecosystem retry correctly, which is the whole point at scale.

### Q2. [Theory] Why are stable, machine-readable error responses as important as the data responses?

When something goes wrong, the error response *is* the API for that request. If your success path returns clean JSON but your error path returns an HTML stack trace, a plain-text string, or an inconsistent shape, every client has to special-case failure, and they will get it wrong. At scale you have thousands of integrations, and an inconsistent error contract multiplies their support burden into yours.

A good error contract is **stable, structured, and actionable**. It has a machine-readable code clients can branch on (don't ask them to string-match the human message — you'll break them when you reword it), a human message for logs and developers, and ideally a correlation/trace ID so a support ticket can be tied to a specific request. RFC 9457 *Problem Details for HTTP APIs* (which superseded RFC 7807) standardizes this:

```json
{
  "type": "https://api.example.com/errors/insufficient-funds",
  "title": "Insufficient funds",
  "status": 402,
  "detail": "Account ac_123 has balance 500, needs 1500.",
  "instance": "/accounts/ac_123/transfers",
  "code": "INSUFFICIENT_FUNDS",
  "traceId": "abc-123-def"
}
```

The HTTP status code carries the coarse category (4xx client error vs 5xx server error) so generic tooling works, and the body carries the precise, app-specific detail. The discipline is to treat error codes as a versioned part of your contract: adding a new code is fine, but changing what an existing code means is a breaking change.

### Q3. [Practical] When should you return 400 vs 401 vs 403 vs 404 vs 409 vs 422 vs 429? Give a quick decision guide.

These are the workhorse 4xx codes, and choosing the wrong one sends clients down the wrong recovery path (e.g., retrying something that will never succeed, or re-authenticating when the credentials were fine).

```
400 Bad Request          → malformed syntax (bad JSON, missing required field)
401 Unauthorized         → no/invalid credentials. "Who are you?" → re-auth
403 Forbidden            → authenticated but not allowed. "I know you; no." → don't retry
404 Not Found            → resource doesn't exist (or you hide 403 as 404 to avoid leaking existence)
409 Conflict             → state conflict: duplicate, version mismatch, concurrent edit
422 Unprocessable Entity → syntactically valid but semantically wrong (e.g., end date before start date)
429 Too Many Requests    → rate limited; include Retry-After
```

The subtle ones: **401 vs 403** — 401 means *authenticate* (the client should retry with credentials); 403 means *you are authenticated but lack permission* (retrying with the same identity is pointless). **400 vs 422** — 400 is for requests the parser rejects (broken JSON, wrong type); 422 is for requests that parse fine but violate business rules. Some teams skip 422 and use 400 for both, which is acceptable as long as the machine-readable `code` field disambiguates. **404 as a security tool** — for resources a user shouldn't even know exist, returning 404 instead of 403 avoids leaking that the resource is real.

A practical rule I follow: **4xx means "don't retry the same request unchanged" (except 429, where you retry after a delay); 5xx means "this might be transient, retry with backoff."** Clients build their retry logic on that distinction, so getting the class right matters more than the exact code.

### Q4. [Theory] What is API versioning and what are the common strategies?

**Versioning** lets you evolve an API without breaking existing clients, because at scale you can never force everyone to upgrade at once — there are mobile apps installed on phones you'll never reach and partner integrations maintained by no one. Versioning buys you the ability to ship breaking changes behind a new version while the old one keeps serving.

The common strategies, each with trade-offs:

```
URI path:      GET /v2/users/42          ← visible, cache-friendly, easy to route; "ugly", couples version to URL
Query param:   GET /users/42?version=2   ← easy to default; clutters URLs, easy to forget
Header:        GET /users/42             ← clean URLs, content-negotiation-pure
               Accept: application/vnd.example.v2+json   but invisible, harder to test in a browser
Date-based:    Stripe-Version: 2024-10-01 ← pins behavior to a snapshot; great for gradual rollout
```

**URI path versioning** (`/v1/`, `/v2/`) is the most common because it's explicit, trivially routable, and you can see it in logs and browser bars. **Header versioning** is the most "RESTfully pure" (the URL identifies the resource, not its representation) but it's invisible and harder to debug. **Date-based versioning** (Stripe's approach) is the gold standard for large platforms: a customer pins to the version active when they integrated, and you transform old responses to that shape internally — so you can ship changes continuously without bumping a big `v3`.

The meta-point: versioning is a tax you pay for every version you keep alive (more code paths, more tests, more docs), so the best strategy is the one that lets you make *most* changes without a new version at all — which is backward-compatible evolution (covered later).

### Q5. [Practical] You have an endpoint `GET /users` that returns all users. Why is that dangerous at scale, and what do you do instead?

Returning *all* users works fine with 50 users in a demo and falls over catastrophically at 50 million: the query scans the whole table, the response is hundreds of megabytes, you blow memory on both server and client, the request times out, and one such call can knock over the database for everyone. Unbounded result sets are one of the most common ways a young API becomes an outage.

The fix is **mandatory pagination**: never return an unbounded collection. Cap the page size server-side (e.g., default 20, max 100), and if a client asks for more, clamp it rather than honoring it. Return only a page plus the means to get the next one.

```bash
# Bad: unbounded
GET /users               → [ ...50 million objects... ]

# Good: paginated, with a sane cap
GET /users?limit=20
→ {
    "data": [ ...20 users... ],
    "next_cursor": "eyJpZCI6MTIzfQ",
    "has_more": true
  }
```

Beyond pagination, scale-friendly collection endpoints also support **field selection** (`?fields=id,name` so clients don't pull columns they don't need), **filtering** (`?status=active`), and **sorting** with bounded options. The principle is that the client should never be able to ask for an arbitrarily expensive operation; every collection access has a built-in ceiling.

### Q6. [Coding] Implement a request handler that enforces idempotency using an idempotency key.

**Problem:** A client sends `POST /payments` with an `Idempotency-Key` header. If the client retries (network blip, timeout) with the same key, the server must return the *original* result instead of charging twice.

**Approach:** Store the key's outcome keyed by `Idempotency-Key`. On first request, acquire the key atomically, do the work, and persist the response. On a retry with the same key, return the stored response. The atomic "insert if absent" is what prevents two concurrent requests from both executing.

```java
public class IdempotentPaymentHandler {
    private final KeyValueStore store;   // e.g., Redis with TTL
    private final PaymentService payments;

    public Response handle(String idempotencyKey, PaymentRequest req) {
        if (idempotencyKey == null || idempotencyKey.isBlank())
            return Response.badRequest("Idempotency-Key header required");

        // Atomic claim: only one caller wins the "PROCESSING" slot.
        boolean claimed = store.setIfAbsent(
            idempotencyKey, "PROCESSING", Duration.ofHours(24));

        if (!claimed) {
            String existing = store.get(idempotencyKey);
            if ("PROCESSING".equals(existing)) {
                // Original is still in flight: tell client to retry shortly.
                return Response.status(409).body("Request in progress, retry later");
            }
            // Completed: replay the stored response verbatim.
            return Response.fromJson(existing);
        }

        try {
            Response result = payments.charge(req);     // the real, non-idempotent work
            store.set(idempotencyKey, result.toJson(), Duration.ofHours(24));
            return result;
        } catch (Exception e) {
            store.delete(idempotencyKey);  // allow a genuine retry on failure
            throw e;
        }
    }
}
```

**Time:** O(1) per request plus the cost of the work itself. **Space:** O(1) per stored key, expired by TTL.

**Edge cases:** the concurrent-duplicate case (two retries arrive simultaneously) is handled by `setIfAbsent` so only one does the work; the second gets a 409 and retries to find the cached result. You should also **bind the key to the request fingerprint** — hash the request body and reject (422) if the same key arrives with a *different* body, because that means a client bug, not a retry. Choose the TTL to comfortably exceed the client's retry window (24h is common). Finally, the key store must be durable enough that a crash between "charge" and "store response" doesn't lose the record — for money, persist the idempotency record in the same transaction as the charge.

### Q7. [Theory] What is the difference between PUT and PATCH, and why does it matter for clients?

`PUT` **replaces** the entire resource with the representation in the body — anything you omit is treated as removed/reset. `PATCH` applies a **partial modification** — you send only the fields you want to change, and the rest are left alone. The distinction matters because using the wrong one silently destroys data.

Consider a user with `{name, email, phone}`. If a client wants to update only the phone and sends `PUT /users/42 {"phone": "555-1234"}`, a spec-correct server wipes `name` and `email` because they were absent from the full representation. With `PATCH /users/42 {"phone": "555-1234"}`, only the phone changes.

```
PUT  (replace):   send the WHOLE resource. Omitted field → cleared. Idempotent.
PATCH (modify):   send ONLY changed fields. Omitted field → untouched. Often not idempotent.
```

PATCH has its own subtlety: how do you express "set this field to null" vs "don't touch this field"? Two standards address this — **JSON Merge Patch** (RFC 7386), where `null` means "delete the field," and **JSON Patch** (RFC 6902), an explicit operation list (`[{"op":"replace","path":"/phone","value":"555-1234"}]`) that's more powerful but verbose. For most APIs, JSON Merge Patch is the pragmatic choice. The practical advice: document clearly which one you support, because clients cannot guess your null semantics, and getting it wrong corrupts their data.

### Q8. [Practical] What belongs in a well-designed paginated list response, beyond just the data array?

A list response that returns only `[...]` forces clients to guess everything else: is there more? how do I get it? how many total? A good envelope answers those explicitly so clients don't build fragile heuristics.

```json
{
  "data": [ /* the page of items */ ],
  "pagination": {
    "next_cursor": "eyJpZCI6OTl9",
    "has_more": true,
    "limit": 20
  }
}
```

The non-obvious decision is what to *omit*. A `total_count` is tempting but at scale it's expensive — counting all matching rows on every page request can be as costly as the query itself, so many large APIs (GitHub, Stripe) deliberately don't return exact totals, offering only `has_more`. If you must show "about 10,000 results," compute it approximately or asynchronously. Also avoid leaking internal IDs in cursors; encode an opaque, tamper-resistant token (covered in the cursor question) so clients treat it as a black box and you keep freedom to change the cursor's internals.

Wrapping the page in an envelope (rather than returning a bare array) has a second benefit: it's **forward-compatible**. You can add fields like `pagination`, `warnings`, or `links` later without changing the top-level type, whereas a bare array leaves you nowhere to attach metadata without a breaking change.

### Q9. [Practical] What are the basics of good resource naming and URL design in a REST API?

URLs are the most visible, longest-lived part of your API — they end up hard-coded in client source, bookmarked, logged, and cached — so naming conventions you set early are extremely hard to change later. The conventions exist to make the API *predictable*: a developer who has used one of your endpoints should be able to guess the others.

The widely-accepted rules: use **nouns for resources, not verbs** (`GET /orders`, not `GET /getOrders` — the HTTP method already supplies the verb); use **plural collection names** (`/users`, `/users/42`) consistently; express **hierarchy through nesting** (`/users/42/orders` for a user's orders) but don't nest more than a level or two deep (deep nesting like `/users/42/orders/9/items/3/discounts` becomes brittle — prefer top-level resources with filters once relationships get complex); use **hyphens, not underscores or camelCase, in path segments** (`/shipping-addresses`); and keep query parameters for filtering/sorting/pagination (`?status=active&sort=-created_at`), not for identifying resources.

```
Good                              Avoid
--------------------------------  --------------------------------
GET    /orders                    GET /getAllOrders
GET    /orders/42                 GET /order?id=42
POST   /orders                    POST /createOrder
DELETE /orders/42                 POST /orders/42/delete
GET    /users/42/orders           GET /getOrdersForUser?u=42
```

The deeper point is **consistency over personal preference**: it matters less whether you chose plural or singular than that you chose *one* and applied it everywhere, because every inconsistency is a thing each client must learn and remember. Map URLs to the *domain resources clients think in*, not to your database tables — leaking your schema into your URLs couples consumers to your storage and makes refactoring a breaking change. And remember non-CRUD actions exist: when an operation genuinely isn't a resource mutation (e.g., "cancel this order"), it's acceptable to model it as a sub-resource or action (`POST /orders/42/cancel`) rather than contort it into pure REST.

---

## 🟡 Intermediate (3–7 yrs)

### Q10. [Theory] Compare offset/limit pagination with cursor (keyset) pagination. When does offset break down?

**Offset pagination** (`?offset=40&limit=20`, or `?page=3`) tells the database "skip 40 rows, return 20." It's trivial to implement and lets you jump to an arbitrary page. **Cursor (keyset) pagination** (`?after=<cursor>&limit=20`) says "give me 20 rows after this position," where the cursor encodes the sort key of the last item seen.

Offset breaks down in two ways at scale. First, **performance**: `OFFSET 1000000` forces the database to scan and discard a million rows before returning your 20 — cost grows linearly with how deep you page, so page 50,000 is a table scan. Cursor pagination uses an indexed `WHERE id > ?`, which is O(log n) regardless of depth. Second, **correctness under concurrent writes**: if a row is inserted or deleted while a user pages, offsets shift, so they see an item twice or skip one entirely. Keyset pagination anchored on a stable sort key doesn't drift.

```sql
-- Offset: scans and throws away the skipped rows. Slow at depth.
SELECT * FROM events ORDER BY created_at, id LIMIT 20 OFFSET 1000000;

-- Keyset: jumps straight to position via the index. Fast at any depth.
SELECT * FROM events
WHERE (created_at, id) > ('2026-06-01T10:00:00Z', 8842)
ORDER BY created_at, id
LIMIT 20;
```

The trade-off: offset lets you say "go to page 47"; cursor pagination only supports next/previous, not random page access. So offset is fine for small, admin-style data sets and human UIs that page shallowly; cursor pagination is mandatory for large, deep, or high-write collections (feeds, event logs, API exports). Note the cursor must be on a **unique, monotonic** key (or a composite like `(created_at, id)`) — paging on a non-unique column alone skips or duplicates rows that share a value.

### Q11. [Coding] Implement opaque cursor encoding/decoding for keyset pagination.

**Problem:** Clients should treat the cursor as an opaque token, not parse or fabricate it. Encode the sort position (here, a `(createdAt, id)` pair) into a base64url string and decode it back, so you can change the internals later without breaking clients.

```java
import java.util.Base64;
import java.nio.charset.StandardCharsets;

public class Cursor {
    public final long createdAtEpochMs;
    public final long id;

    public Cursor(long createdAtEpochMs, long id) {
        this.createdAtEpochMs = createdAtEpochMs;
        this.id = id;
    }

    // Encode position into an opaque, URL-safe token.
    public String encode() {
        String payload = createdAtEpochMs + ":" + id;
        return Base64.getUrlEncoder().withoutPadding()
                 .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    public static Cursor decode(String token) {
        try {
            byte[] raw = Base64.getUrlDecoder().decode(token);
            String[] parts = new String(raw, StandardCharsets.UTF_8).split(":");
            if (parts.length != 2) throw new IllegalArgumentException("bad cursor");
            return new Cursor(Long.parseLong(parts[0]), Long.parseLong(parts[1]));
        } catch (Exception e) {
            // Never 500 on a malformed cursor — it's client input.
            throw new BadRequestException("Invalid cursor token");
        }
    }
}
```

**Time:** O(1) encode/decode. **Space:** O(1).

**Edge cases:** treat the cursor as untrusted input — a malformed or hostile token must yield a 400, never a 500 or an unhandled exception (and never let a client inject SQL through it). For tamper resistance on sensitive data, **sign** the cursor (HMAC) so clients can't fabricate positions to probe other rows; for confidentiality you can encrypt it. Keep the encoded payload small. And critically, the decoded `(createdAt, id)` must feed a parameterized query — the opacity is a contract convenience, not a security boundary by itself.

### Q12. [Theory] What does "backward compatible" mean for an API, and which changes are safe vs breaking?

A change is **backward compatible** if every existing client continues to work without modification. The mental model that scales: **be liberal in what you accept and conservative in what you remove.** Adding is usually safe; removing, renaming, or tightening is usually breaking.

```
SAFE (backward compatible)              BREAKING (needs a version / migration)
--------------------------------------  ----------------------------------------
add a new optional request field        add a new *required* request field
add a new field to a response           remove or rename a response field
add a new endpoint                      remove or rename an endpoint
add a new optional query param          change a field's type (string→int)
add a new enum value (with care!)       change semantics of an existing field
loosen a validation rule                tighten a validation rule
add a new error code                    change HTTP status for an existing case
```

The tricky ones reveal experience. **Adding an enum value** is technically additive but breaks clients that exhaustively `switch` on the enum and crash on unknown values — so you must document "clients must tolerate unknown enum values" up front, or treat new enum values as breaking. **Adding a response field** is safe *only if* clients ignore unknown fields (the "tolerant reader" pattern); a strict-schema client that rejects unknown fields will break, which is why you publish a compatibility policy telling clients to be tolerant readers. **Making an optional field required** is breaking even though it "feels" like just validation. The discipline is to evaluate every change against "would an old client, byte-for-byte unchanged, still work?" — and if not, it's a versioned change.

### Q13. [Practical] How do you design rate limiting for a public API, and what should the response tell the client?

Rate limiting protects your service from abuse and noisy neighbors, and it enforces fair use across tenants. The design has two halves: the **policy** (who is limited, by how much, on what dimension) and the **contract** (how the client learns its limits and what to do when throttled).

For policy, limit on a **trustworthy key** — the authenticated API key or user ID, not raw client IP (IPs are shared behind NAT and trivially rotated). Often you layer limits: per-API-key, per-endpoint (writes cheaper than reads), and a global safety cap. A token bucket is the usual algorithm because real traffic is bursty and you want to allow short bursts while bounding the long-run rate.

For the contract, expose the limit state in headers and use 429 with `Retry-After` so well-behaved clients self-throttle instead of hammering you:

```bash
HTTP/1.1 200 OK
RateLimit-Limit: 1000           # ceiling for the window
RateLimit-Remaining: 994        # how many left
RateLimit-Reset: 30             # seconds until the window resets

# When exceeded:
HTTP/1.1 429 Too Many Requests
Retry-After: 30
{ "code": "RATE_LIMITED", "message": "Rate limit exceeded. Retry after 30s." }
```

The `RateLimit-*` headers (now an IETF draft standard) let a good client pace itself *before* getting a 429, which is the real win — you've turned a confrontational "stop" into cooperative backpressure. Also decide your failure mode: if the rate-limit store (Redis) is down, do you **fail open** (allow traffic, prioritizing availability) or **fail closed** (reject, prioritizing protection)? For a quota that's about fairness, fail open; for one guarding a fragile downstream or preventing abuse, fail closed. Finally, document the limits publicly so integrators design around them rather than discovering them through 429s in production.

### Q14. [Theory] What is the Backend-for-Frontend (BFF) pattern and what problem does it solve?

**Backend-for-Frontend** is the pattern of building a dedicated API layer for each distinct frontend (web, iOS, Android, partner) instead of forcing all of them through a single general-purpose API. Each BFF is owned by the team that owns that frontend and is shaped exactly to that client's needs.

The problem it solves is the tension between a **general-purpose API** (one size, serves no one perfectly) and **client-specific needs**. A mobile app on a flaky cellular connection wants a single call that returns a fully-composed screen with minimal fields to save bytes and battery; a desktop web app can afford several chattier calls and richer payloads; a partner wants a stable, sanitized subset. Without BFFs, you either bloat one API with every client's concerns (and couple all clients to each other's changes) or push aggregation/transformation logic into each client.

```
            ┌── Web BFF ──┐
Browser ───▶│ (composes,  │──┐
            │  trims for  │  │
            │  web)       │  │
            └─────────────┘  │      ┌─ Users service
                             ├─────▶├─ Orders service   (downstream microservices)
            ┌── Mobile BFF ─┐│      └─ Catalog service
iOS/Android▶│ (1 call/screen│┘
            │  tiny payload)│
            └───────────────┘
```

The BFF aggregates calls to downstream microservices, transforms and trims their responses for its specific client, and absorbs client-specific concerns (auth token exchange, response shaping, screen composition). The trade-off is more services to operate and a risk of duplicated logic across BFFs, so you keep genuinely shared logic in the downstream services and let each BFF own only its client-specific shaping. GraphQL is sometimes pitched as an alternative (let the client ask for exactly what it wants), and the choice between "a BFF per client" and "one flexible GraphQL gateway" is a real architectural fork.

### Q15. [Coding] Implement a Redis-backed distributed rate limiter using a Lua script (token bucket).

**Problem:** Across a fleet of stateless API servers, enforce a per-key token-bucket limit. The check-and-decrement must be atomic so concurrent requests on different servers can't both consume the last token.

```lua
-- token_bucket.lua  — KEYS[1] = bucket key
-- ARGV: capacity, refillPerSec, nowMs, requested
local capacity     = tonumber(ARGV[1])
local refillPerSec = tonumber(ARGV[2])
local nowMs        = tonumber(ARGV[3])
local requested    = tonumber(ARGV[4])

local state  = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(state[1])
local ts     = tonumber(state[2])

if tokens == nil then            -- first time: start full
  tokens = capacity
  ts = nowMs
end

-- Refill based on elapsed time.
local elapsed = math.max(0, nowMs - ts) / 1000.0
tokens = math.min(capacity, tokens + elapsed * refillPerSec)

local allowed = 0
if tokens >= requested then
  tokens = tokens - requested
  allowed = 1
end

redis.call('HMSET', KEYS[1], 'tokens', tokens, 'ts', nowMs)
-- Expire idle buckets so we don't leak memory for one-off keys.
redis.call('PEXPIRE', KEYS[1], math.ceil(capacity / refillPerSec * 1000) + 1000)

return { allowed, math.floor(tokens) }   -- {allowed?, tokensRemaining}
```

**Time:** O(1) per call (one round trip, atomic on the Redis single thread). **Space:** O(1) per active key, reclaimed by `PEXPIRE`.

**Edge cases:** running the whole read-modify-write *inside* the script is what makes it race-free — Redis executes a script atomically, so two servers calling simultaneously serialize. Pass `nowMs` from the script side via `redis.call('TIME')` rather than the app server's clock if your fleet's clocks skew, to keep refill consistent. The `PEXPIRE` prevents memory leaking from millions of one-shot keys (e.g., per-IP buckets). For very high throughput you can reduce Redis round trips with **local token leasing** — each node leases a batch of tokens and limits locally — at the cost of some precision near the boundary. Decide fail-open vs fail-closed for when Redis is unreachable.

### Q16. [Practical] Design a bulk/batch endpoint. What are the key decisions around partial failure?

A bulk endpoint (`POST /messages/batch` accepting an array) exists because making 1,000 individual HTTP calls is wasteful — connection overhead, round trips, and rate-limit pressure. But the moment you accept many items in one request, you face the question that defines the whole design: **what happens when item 437 fails but the rest succeed?**

There are two philosophies. **All-or-nothing (transactional)**: the whole batch succeeds or the whole batch rolls back. Simple to reason about, but one bad item poisons 999 good ones, and clients must resubmit everything. **Partial success (per-item results)**: each item gets its own status, and the response is a parallel array of results. This is almost always the right choice for bulk APIs because it lets clients retry only the failures.

```json
POST /messages/batch
{ "items": [ {"to": "a@x.com", ...}, {"to": "BAD", ...}, {"to": "c@x.com", ...} ] }

HTTP/1.1 207 Multi-Status
{
  "results": [
    { "index": 0, "status": 201, "id": "msg_1" },
    { "index": 1, "status": 422, "code": "INVALID_RECIPIENT" },
    { "index": 2, "status": 201, "id": "msg_3" }
  ]
}
```

Key decisions: (1) **HTTP status for the envelope** — use `207 Multi-Status` or `200` with per-item statuses, *not* a single 4xx/5xx that hides the mix. (2) **Correlate results to inputs** by index or a client-supplied ID, so clients know exactly which item failed. (3) **Cap the batch size** (e.g., max 500 items) so one request can't be unbounded — same lesson as pagination. (4) **Idempotency** — a batch retry shouldn't duplicate the successful items, so combine per-item idempotency keys with the partial-success model. (5) **Ordering and atomicity guarantees** — state clearly whether items are processed independently or as a unit, because clients will assume one and be burned by the other.

### Q17. [Theory] How do you model a long-running operation in a synchronous request/response API?

Some operations can't finish within a reasonable HTTP timeout — generating a report, transcoding a video, provisioning infrastructure, a bulk import of a million rows. Holding the connection open for minutes is fragile (proxies and load balancers kill long connections, clients time out, a retry re-triggers the whole job). The answer is the **asynchronous request-reply pattern**: accept the work, return immediately with a handle, and let the client poll or get notified.

```bash
# 1. Client kicks off the job; server returns 202 + a status URL, not the result.
POST /reports
HTTP/1.1 202 Accepted
Location: /operations/op_789
{ "id": "op_789", "status": "PENDING" }

# 2. Client polls the operation resource.
GET /operations/op_789
{ "id": "op_789", "status": "RUNNING", "progress": 60 }

# 3. Eventually it completes; the operation resource points to the result.
GET /operations/op_789
{ "id": "op_789", "status": "SUCCEEDED", "result_url": "/reports/rep_456" }
```

The key elements: **202 Accepted** signals "I took the work but haven't done it" (vs 200/201 which mean "done"); a **`Location` header / operation resource** gives the client something to track; the operation resource carries `status` (PENDING/RUNNING/SUCCEEDED/FAILED), optional `progress`, and on completion a pointer to the result and on failure a structured error. This is exactly how cloud APIs (Google's AIP-151 LROs, Azure's async operations) model the problem.

To avoid relentless polling, you can offer **webhooks** (server calls the client back when done) or set sensible `Retry-After` hints on the status endpoint. The operation should also be **idempotent to create** (a client idempotency key so a retried `POST /reports` returns the same operation, not a second job), and the operation resource should have a TTL so completed operations are eventually garbage-collected.

### Q18. [Practical] A mobile team complains your API forces them to make 6 calls to render one screen and the payloads are huge. How do you respond?

This is the classic **chatty API** problem (too many round trips, deadly on high-latency mobile networks) compounded by **over-fetching** (payloads carrying fields the screen doesn't use, burning bytes and battery). I'd treat it as a real product issue, not push back — six serial round trips on a 200ms cellular RTT is over a second of latency before any rendering.

The options, roughly in order of investment:

1. **Add a composition/aggregation endpoint** tailored to that screen — a `GET /home-screen` that the server fans out to the six downstreams in parallel and returns one trimmed payload. This is the **BFF** pattern: give mobile its own backend that composes and shapes responses for it specifically.
2. **Support field selection / sparse fieldsets** (`?fields=...`) so the client can drop the fields it doesn't render, cutting payload size without a new endpoint.
3. **Adopt GraphQL** for that surface, letting the client specify exactly the graph it needs in one query — powerful, but a real investment (schema, N+1 risks, caching complexity) that I'd only recommend if many screens have this shape.

I'd start with the BFF/composition endpoint because it directly attacks both problems (one round trip, server-shaped payload) and keeps the optimization on the server where we can change it without shipping a new app build. The deeper lesson I'd share with the team: a single general-purpose REST API rarely serves a latency-sensitive mobile client well, and that mismatch is exactly why BFFs exist. I'd also instrument the screen's actual data needs so we shape the endpoint from real usage, not guesses.

### Q19. [Theory] Why must clients always be coded as "tolerant readers," and how does that shape your API evolution policy?

The **tolerant reader** principle (Postel's Law applied to consumers) says a client should ignore fields it doesn't recognize and not break when the response contains more than it expected. This is the single most important contract that makes backward-compatible evolution possible, because if clients are *intolerant* — they reject responses with unknown fields, or crash on unknown enum values — then even *adding* a field becomes a breaking change, and you can't evolve the API at all without a version bump.

Concretely, a tolerant reader: ignores unknown JSON fields rather than failing schema validation; treats unknown enum values as a default/"other" bucket rather than throwing; doesn't depend on field ordering or on the absence of fields; and codes against documented fields, not on reverse-engineered internals. When clients follow these rules, the server gains enormous freedom — it can add fields, add enum values, add endpoints, and enrich responses continuously without a single breaking change.

This shapes your evolution policy in two directions. As the **API provider**, you publish a compatibility contract that explicitly tells consumers "you must tolerate unknown fields and enum values" and you hold the additive-only line so tolerant readers never break. As an **API consumer**, you build your deserialization to be lenient (most JSON libraries ignore unknown fields by default — don't turn on "fail on unknown" unless you have a reason). The hard reality at scale is that *some* clients will be intolerant no matter what you document, so for very large platforms you still gate even additive changes behind version pinning (Stripe's dated versions) to protect the lowest-common-denominator integrator. But for internal APIs, a strong tolerant-reader culture is what lets you evolve weekly instead of quarterly.

### Q20. [Practical] How should clients retry failed requests safely, and what is the server's responsibility in making retries work?

Retries are unavoidable — networks drop, servers restart, timeouts fire — but naive retries are one of the top causes of self-inflicted outages (retry storms that turn a brief blip into a sustained overload). Safe retrying is a shared contract: the client retries responsibly, and the server makes retrying *correct*.

The client's responsibilities: (1) **Only retry idempotent operations** — `GET`/`PUT`/`DELETE` are safe to retry; a `POST` is *only* safe to retry if it carries an idempotency key (Q6/Q21). (2) **Retry only retryable failures** — 5xx and network errors are transient (retry with backoff); most 4xx are not (a 400/403/422 will fail identically forever, so retrying is pure waste), with 429 being the special case where you retry *after* the `Retry-After` delay. (3) **Use exponential backoff with full jitter** so a thousand clients that failed at the same instant don't all retry at the same instant — jitter is what de-synchronizes the herd. (4) **Cap total attempts and honor a retry budget** so retries stay a small fraction of traffic.

```
attempt 1 → fail → wait random(0, 1s)
attempt 2 → fail → wait random(0, 2s)
attempt 3 → fail → wait random(0, 4s)   # exponential base, full jitter
attempt 4 → give up → surface error / circuit-break
# NEVER: retry immediately in a tight loop, or retry a non-idempotent POST without a key
```

The server's responsibilities make those client retries *correct and survivable*: **support idempotency keys** so a retried write doesn't duplicate; **return the right status class** (5xx for transient, 4xx for permanent) so clients can distinguish "try again" from "stop"; **send `Retry-After`** on 429 and 503 so clients pace themselves instead of guessing; and **shed load / fail fast** under overload rather than queueing requests into oblivion, so a struggling server doesn't amplify the retry pressure it's already drowning in. The expert framing: retries are a *system* property — a well-behaved client and a well-behaved server together produce resilience, but a naive client against a fragile server produces a cascading failure. Designing the API contract (idempotency keys, status semantics, `Retry-After`) is how the server makes the client's retries safe by construction.

---

## 🟠 Advanced (8–12 yrs)

### Q21. [Theory] Walk through the full mechanics of an idempotency-key system for a payments API, including storage, fingerprinting, and concurrency.

A production idempotency system (Stripe's is the reference design) turns the impossible "exactly-once" into the achievable "at-least-once delivery + idempotent processing = effectively-once." It has four moving parts: the **key**, the **fingerprint**, the **state machine**, and the **storage lifetime**.

The **key** is client-generated (a UUID) and sent in the `Idempotency-Key` header, scoped per endpoint and per account (so two accounts can't collide, and the same key on a different endpoint is independent). The **fingerprint** is a hash of the request parameters stored alongside the key; on a retry you compare the incoming request's fingerprint to the stored one — if they differ, you reject with 422, because the same key with a different body means a client bug, not a legitimate retry. The **state machine** guards concurrency:

```
        ┌─────────────────────────────────────────────┐
new key │ INSERT (key, fingerprint, state=STARTED)     │  atomic; loser of the
─────── │   ON CONFLICT → row exists                   │  race sees existing row
        └───────────────┬─────────────────────────────┘
                        │ row already exists?
        ┌───────────────┴───────────────┐
        │ state=STARTED → 409 "in progress, retry"     │
        │ state=DONE    → replay stored response       │
        │ fingerprint ≠ → 422 "key reused with new body"│
        └───────────────────────────────┘
do work → persist response + state=DONE in SAME txn as the side effect
```

The **critical correctness rule**: persist the idempotency record's final state **in the same transaction as the side effect** (the charge). If you charge and then crash before recording "DONE," a retry would double-charge; if you record "DONE" then crash before charging, a retry would skip a charge the client thinks succeeded. Coupling them transactionally (or using a recovery process that replays based on the durable record) is what closes the window. For external side effects you can't transact with (calling a card network), you record an intermediate "recovery point" so a crashed request can be safely resumed rather than restarted.

Finally, **storage lifetime**: keys are kept long enough to cover any sane retry window (Stripe keeps them ~24h) and then expire, because keeping them forever is both a storage cost and a correctness trap (a client reusing a UUID a year later shouldn't get a year-old response). The whole system is a small but exacting piece of engineering, and getting the transaction boundary wrong is the difference between "safe retries" and "double-charged customers."

### Q22. [Practical] You need to deprecate an API version that thousands of customers still use. Design the full deprecation lifecycle.

You cannot just turn off a version that thousands depend on — you'd cause a mass outage and torch trust. Deprecation at scale is a **months-to-years program** with clear communication, measurement, and incentives, not a flag flip.

The lifecycle I run:

```
1. ANNOUNCE   → publish deprecation + sunset date (often 12+ months out).
                Add `Deprecation: true` and `Sunset: <date>` response headers
                so even clients who don't read emails see it in their logs.
2. MEASURE    → instrument per-customer usage of the old version. You cannot
                deprecate what you can't see. Build a dashboard of who's left.
3. MIGRATE    → ship the new version + a migration guide + ideally a compat
                shim so most clients can move with minimal code change.
                Reach out directly to the top users by volume.
4. NUDGE      → escalate: in-product warnings, brownouts (briefly disable the
                old version for minutes during low traffic so silent clients
                get a visible error and a heads-up), rising friction.
5. SUNSET     → on the date, return 410 Gone (not 404) for the old version,
                with a body pointing to the migration guide. Keep a break-glass
                extension path for a few critical stragglers.
```

The non-negotiables: **a long, fixed timeline communicated early and repeatedly** (the `Sunset` HTTP header, RFC 8594, is the machine-readable signal); **per-customer telemetry** so you can target outreach and know when it's actually safe to pull the plug (e.g., "99.5% migrated, the remaining 0.5% are three accounts we've called"); and **brownouts** as a humane forcing function — a planned, brief, announced failure is far kinder than a surprise permanent one because it surfaces the dependency while there's still time to fix it. The behavioral reality is that a meaningful fraction of customers will not move until something breaks, so the program must combine generous runway with real consequences. And design the *next* version to be evolvable (tolerant readers, additive changes) so you rarely have to do this painful exercise again.

### Q23. [Coding] Implement optimistic concurrency control for a PUT using ETags / If-Match.

**Problem:** Two clients fetch the same resource and both try to update it. Without protection, the second write silently clobbers the first ("lost update"). Use ETags so a conditional `PUT` fails if the resource changed since the client read it.

```java
// GET returns the current version as an ETag.
public Response getUser(String id) {
    User u = repo.find(id);
    if (u == null) return Response.status(404).build();
    String etag = "\"" + u.getVersion() + "\"";          // weak/strong validator
    return Response.ok(u).header("ETag", etag).build();
}

// PUT requires the client to echo the version it last saw.
public Response updateUser(String id, String ifMatch, User incoming) {
    if (ifMatch == null)
        return Response.status(428)   // Precondition Required
            .entity("If-Match header required").build();

    User current = repo.find(id);
    if (current == null) return Response.status(404).build();

    String currentEtag = "\"" + current.getVersion() + "\"";
    if (!ifMatch.equals(currentEtag)) {
        // Someone else updated it since the client's GET.
        return Response.status(412)   // Precondition Failed
            .entity("Resource was modified; re-fetch and retry").build();
    }

    incoming.setVersion(current.getVersion() + 1);        // bump version
    repo.save(incoming);                                  // atomic CAS in the DB
    return Response.ok(incoming)
        .header("ETag", "\"" + incoming.getVersion() + "\"").build();
}
```

**Time:** O(1) plus storage I/O. **Space:** O(1).

**Edge cases:** the version bump and save must be a **compare-and-swap at the storage layer** (`UPDATE ... WHERE id=? AND version=?` and check rows-affected), otherwise two requests that both pass the ETag check in the app layer can still race — the app-level check is an optimization, the DB-level CAS is the real guarantee. Return **412 Precondition Failed** when the ETag doesn't match (the client should re-fetch and merge), and **428 Precondition Required** if you want to *force* clients to use `If-Match` rather than allowing blind overwrites. This is **optimistic** concurrency — it assumes conflicts are rare and only pays a cost when one happens, which beats pessimistic locking (holding a DB lock across the user's think-time) for typical web workloads where a human might sit on an edit form for minutes.

### Q24. [Theory] How do you do API versioning at the scale of Stripe — date-based versions with transformation layers? What are the trade-offs vs big-bang `/v2`?

Big-bang versioning (`/v1` → `/v2`) forces a hard fork: you maintain two entire codebases (or a forked code path), every customer must do a large, risky migration, and you accumulate a graveyard of versions you can never fully retire. It works for occasional major redesigns but is brutal for a platform that wants to evolve *continuously*.

The **date-based + transformation** approach (Stripe's) inverts this. Internally there is exactly **one** current version of the code and data model. Each customer is pinned to the API version that was current when they integrated (e.g., `Stripe-Version: 2024-10-01`). Every breaking change is implemented as a small, named, ordered **version-change transformer** that adapts the *current* response back to *that older* shape (and adapts the older request shape forward). On each request, the server applies the chain of transformers between the customer's pinned version and "now."

```
          current internal model
                  │
   request ──▶  [transform: 2026-03 change] ──▶ [2025-11 change] ──▶ ... 
                  │  (apply only the changes newer than the caller's pinned version)
                  ▼
        response shaped exactly as the caller's pinned version expects
```

The trade-offs: the **upside** is enormous — engineers ship breaking changes whenever needed without a version explosion, customers never have to migrate until they want new features, and each change is a small reviewable unit rather than a monolithic v2. The **cost** is real complexity: you must keep every transformer correct forever (or until you sunset old versions), each transformer must be a pure, well-tested function, and reasoning about "what does account X actually see" requires composing the chain. You also need rigorous testing (run the test suite against every supported version) and tooling to let customers upgrade their pin deliberately. For a small API this machinery is overkill — URI `/v1` versioning is fine. For a platform with thousands of long-lived integrations and a need to evolve constantly, the transformation layer is the pattern that makes continuous evolution survivable.

### Q25. [Practical] Design the error contract for a large public API used by hundreds of integrators. What makes it robust over years?

The error contract is a *long-lived promise*, and integrators will write code branching on it for years, so robustness comes from stability, structure, and forward-compatibility rather than richness. I'd build on RFC 9457 Problem Details and add the fields integrators actually need.

```json
{
  "type": "https://api.example.com/errors/validation",   // stable, documented URI
  "title": "Validation failed",                            // human, may be reworded
  "status": 422,                                           // matches HTTP status
  "code": "VALIDATION_FAILED",                             // stable machine code — NEVER reworded
  "detail": "2 fields are invalid.",
  "trace_id": "req_8f2c...",                               // for support correlation
  "errors": [                                              // field-level detail
    { "field": "email", "code": "INVALID_FORMAT", "message": "Not a valid email." },
    { "field": "age",   "code": "OUT_OF_RANGE",   "message": "Must be 0–150." }
  ],
  "doc_url": "https://docs.example.com/errors/VALIDATION_FAILED"
}
```

What makes it robust over years: (1) a **stable, enumerated `code`** that is part of the contract and is *never* repurposed — clients branch on it, so changing its meaning is a breaking change; you only ever *add* codes. (2) **Separation of machine and human concerns** — `code` is for code, `title`/`detail`/`message` are for humans and may be localized or reworded freely. (3) A **trace/correlation ID** in every error so a customer's "request X failed" maps to your logs instantly — this single field saves enormous support time. (4) **Field-level errors** as a structured array (not a concatenated string) so form UIs can highlight specific inputs. (5) **A documentation URL or `type` URI** so an integrator can self-serve the meaning. (6) **Forward-compatibility**: the envelope is an object (extensible), and clients are told to tolerate unknown fields, so you can add `retry_after`, `remediation`, or new error subtypes without breaking anyone.

The discipline I'd enforce in code review: error codes are reviewed like API surface (you can't merge a renamed code), every 5xx carries a trace ID, and we never leak internal details (stack traces, SQL, internal hostnames) into `detail` because that's both an information-disclosure risk and a stability trap (clients start parsing it). The error contract is tested and documented as rigorously as the success contract, because for the unhappy path, it *is* the API.

### Q26. [Theory] Compare REST, GraphQL, and gRPC for an API platform. When does each win, and how does each handle versioning and over-fetching?

These three dominate modern API design and the choice shapes everything downstream — versioning, caching, client ergonomics, and over/under-fetching.

```
            REST/JSON               GraphQL                  gRPC/Protobuf
---------   ----------------------  -----------------------  -----------------------
Shape       resources + verbs       single graph endpoint    services + methods (RPC)
Fetching    fixed per endpoint      client picks fields      fixed per method
            (over/under-fetch risk) (solves over-fetch)      (compact binary)
Transport   HTTP/1.1+, text         HTTP, usually POST       HTTP/2, binary, streaming
Caching     excellent (HTTP/CDN)    hard (POST, opaque)      none at HTTP layer
Versioning  URI/header/date         additive + @deprecated   field numbers, never reuse
Best for    public APIs, CRUD,      flexible clients,        internal service-to-service,
            broad reach             mobile, many screens     low latency, streaming
```

**REST** wins for public, broadly-consumed APIs: it leans on HTTP semantics (status codes, caching, CDNs), is universally understood, and is easy to debug with curl. Its weakness is over/under-fetching — fixed endpoint shapes force clients to either pull too much or make multiple calls — which you patch with field selection and BFFs. Versioning is explicit (URI/header/date).

**GraphQL** wins when clients are diverse and screen-driven (mobile especially): the client requests exactly the fields it needs in one round trip, killing over-fetching and chattiness. Versioning is typically *versionless* — you add fields and mark old ones `@deprecated`, never removing the graph's backward compatibility — which is elegant but shifts complexity into schema governance, N+1 resolver problems, and notoriously hard HTTP caching (everything is a POST to one endpoint, so you need persisted queries or a specialized cache).

**gRPC** wins for internal service-to-service calls where you control both ends and want low latency, compact binary payloads (Protobuf), code-generated clients, and streaming. Its versioning model is field-number-based: you add fields with new numbers and **never reuse or renumber**, which gives strong wire compatibility, but it's a poor fit for public browser-facing APIs (binary, needs gRPC-Web/proxies, no human-readable debugging).

The mature answer is **it's not either/or**: many platforms use gRPC between internal services, expose REST or GraphQL at the edge, and put a GraphQL/BFF layer where client flexibility matters. The selection criterion is *who consumes it and what they need* — broad public reach and cacheability → REST; flexible/heterogeneous clients → GraphQL; internal high-performance plumbing → gRPC.

### Q27. [Practical] Design pagination for an API that exports millions of records to data-pipeline consumers, where consistency over the full scan matters.

This is a different beast from UI pagination. The consumer (an ETL job, a data warehouse sync) wants to read the *entire* dataset reliably, possibly over hours, and must not miss or duplicate records even as the source mutates underneath. UI pagination patterns (offset, or even simple keyset) aren't enough on their own.

The core technique is **keyset pagination on an immutable, monotonic key** combined with an explicit **snapshot or watermark** so the export is consistent:

```bash
# Cursor on (created_at, id) — stable ordering, indexed, no offset scan.
GET /events?after=eyJ0cyI6MTcxOH0&limit=1000
→ { "data": [...1000...], "next_cursor": "...", "has_more": true }
```

Design choices that make a full export robust:

1. **Keyset, never offset** — at millions of rows, `OFFSET 5000000` is a death sentence; keyset stays O(log n) per page at any depth.
2. **Stable sort key** — order by an immutable, unique key (an insertion-ordered `id` or `(created_at, id)`); never sort by a mutable field like `updated_at` for a full scan, or rows shift between pages and you skip/duplicate.
3. **Snapshot semantics for a point-in-time export** — for "give me everything as of now," anchor the scan to a snapshot: either a DB snapshot/MVCC read, or a `WHERE id <= max_id_at_start` watermark captured when the export began, so rows inserted *during* the export don't bleed in inconsistently.
4. **Resumable cursors** — the consumer must be able to crash and resume from its last cursor without restarting the whole export; opaque cursors that encode exact position make this trivial.
5. **For change-data sync, switch models** — if the consumer wants *ongoing* updates (not a one-time snapshot), offer a **cursor over a change log / CDC stream** keyed by a monotonic sequence number or `updated_at >= watermark` with at-least-once + idempotent application on their side, rather than re-scanning the table.

The deeper point I'd make: "export millions of records consistently" is really two different requirements — a **consistent point-in-time snapshot** (use MVCC snapshot + keyset) versus **continuous incremental sync** (use a change stream + watermark) — and the right API differs. Conflating them produces an export that's either inconsistent or impossibly expensive. I'd also rate-limit and chunk exports so one consumer's full scan doesn't starve interactive traffic, and consider an **async bulk-export job** (the long-running-operation pattern) that produces a downloadable file for truly large datasets rather than thousands of synchronous page requests.

### Q28. [Theory] What is the difference between idempotency and exactly-once semantics, and why is "exactly-once delivery" considered a fallacy?

These get conflated constantly, and the confusion causes real bugs. **Exactly-once *delivery*** would mean a message/request crosses an unreliable network and is delivered to the receiver precisely one time, no more, no less. **Exactly-once *processing*** means the *effect* of the message happens precisely once, regardless of how many times it was delivered. Idempotency is the tool that gets you the second without needing the first.

Exactly-once delivery is a fallacy because of the **Two Generals' problem**: over an unreliable network, the sender can never be certain the receiver got the message, so it must either retry (risking duplicate delivery → at-least-once) or not retry (risking lost delivery → at-most-once). You cannot have both "never lost" and "never duplicated" at the delivery layer when acknowledgments can themselves be lost. Any system claiming "exactly-once delivery" is either lying or quietly redefining it as exactly-once *processing*.

```
At-most-once  : send, don't retry      → may LOSE messages, never duplicates
At-least-once : send, retry on doubt   → never loses, may DUPLICATE
Exactly-once  : impossible at delivery layer
               achievable for *processing* = at-least-once + idempotent/dedup
```

The engineering resolution is therefore: **choose at-least-once delivery (retry on uncertainty) and make processing idempotent** (via idempotency keys, dedup tables, or commutative/CRDT operations) so duplicates are harmless. This is exactly why payment APIs require idempotency keys, why Kafka's "exactly-once" is really "at-least-once + transactional dedup at the processing layer," and why every retry-capable client must assume its retried request might have already succeeded. The mature framing in an interview: "I don't try to guarantee exactly-once delivery — that's impossible — I guarantee at-least-once delivery and design the receiver to be idempotent so the *outcome* is effectively-once."

### Q29. [Coding] Implement a bulk endpoint handler that returns per-item partial-success results.

**Problem:** Accept a batch of items, process each independently, and return a `207 Multi-Status`-style response correlating each result to its input so the client can retry only the failures (see Q16 for the design rationale).

```java
public class BatchHandler {
    private static final int MAX_BATCH = 500;
    private final ItemService service;

    public BatchResponse handleBatch(List<ItemRequest> items) {
        if (items == null || items.isEmpty())
            throw new BadRequestException("batch must contain at least one item");
        if (items.size() > MAX_BATCH)
            throw new BadRequestException("batch exceeds max size of " + MAX_BATCH);

        List<ItemResult> results = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            ItemRequest item = items.get(i);
            try {
                // Per-item idempotency: a client clientRef makes batch retries safe.
                String id = service.process(item, item.clientRef());
                results.add(ItemResult.success(i, item.clientRef(), 201, id));
            } catch (ValidationException ve) {
                // One bad item does NOT abort the rest — record and continue.
                results.add(ItemResult.failure(i, item.clientRef(), 422, ve.code(), ve.getMessage()));
            } catch (Exception e) {
                results.add(ItemResult.failure(i, item.clientRef(), 500, "INTERNAL", "processing failed"));
            }
        }
        // 207 because the batch is a MIX of outcomes; per-item status is in the body.
        return new BatchResponse(207, results);
    }
}
```

**Time:** O(n) over the batch (each item processed once); parallelizable across items if they're independent. **Space:** O(n) for the results array.

**Edge cases:** the **size cap** prevents an unbounded, expensive request (same lesson as pagination); **correlate every result to its input** by index *and* the client-supplied `clientRef` so the client maps results back unambiguously even if order isn't preserved; **per-item idempotency** (the `clientRef`) means a retry of the whole batch re-submits the already-succeeded items harmlessly rather than duplicating them; and the **envelope status is 207** (or 200) — never a single 4xx/5xx that hides the mix and forces the client to guess which items failed. If you process items in parallel, ensure the results array preserves the input index so correlation survives reordering.

---

## 🔴 Expert (15+ yrs)

### Q30. [Theory] How do you govern API evolution across hundreds of microservices owned by dozens of teams without descending into chaos?

At this scale the problem stops being "how do I version one API" and becomes "how do I prevent hundreds of teams from each inventing incompatible conventions, breaking each other, and accumulating a maintenance swamp." The answer is **governance as a platform**, not governance as a committee that approves every change.

The pillars I'd put in place: (1) **A design standard, codified and automated.** Publish API guidelines (naming, pagination shape, error contract, versioning policy, idempotency rules) — Google's AIPs and Microsoft's REST guidelines are the public exemplars — and enforce them with **linting in CI** (Spectral for OpenAPI, Buf for Protobuf) so the standard is checked by machines, not argued in reviews. A rule that isn't automated is a rule that erodes. (2) **Schema-first with a registry.** Every API has a machine-readable contract (OpenAPI/Protobuf) registered centrally, enabling discovery, generated clients, and — crucially — **automated breaking-change detection** (Buf breaking, openapi-diff) that fails the build if a change violates compatibility. (3) **Contract testing** (Pact / consumer-driven contracts) so a provider can't ship a change that breaks a known consumer without the test catching it before deploy. (4) **A compatibility policy as law**: additive-only by default, tolerant readers required, breaking changes only behind explicit versioning with a published deprecation/sunset process. (5) **A federated ownership model**: a small platform/API-council team owns the *standards and tooling*; individual teams own their *APIs* within those guardrails — central enough for consistency, decentralized enough to scale.

The expert insight is that **consistency is a feature**: when every API in the company paginates the same way, returns the same error shape, and signals deprecation the same way, the cognitive load on every consumer drops, generated tooling works everywhere, and onboarding collapses. The failure mode I'd actively fight is governance-by-meeting, which doesn't scale and breeds resentment — the leverage is in *automated guardrails* (linters, breaking-change gates, contract tests, code-generated clients/servers from the spec) that make the right thing the easy thing and make the wrong thing fail in CI. You also need an explicit **deprecation discipline** (telemetry per consumer, sunset headers, brownouts) so the estate doesn't ossify under the weight of versions nobody dares remove.

### Q31. [Practical] Design the idempotency, retry, and consistency story for a globally distributed write API spanning multiple regions.

This stacks every hard problem at once: clients retry across regions, the same logical request might land in different regions, and you need correctness without paying cross-region consensus latency on every call. The design is about **placing each concern at the right layer and shrinking the globally-coordinated surface to near zero.**

```
client (idempotency-key) 
   │  retry may hit a DIFFERENT region than the original
   ▼
[ region A edge ]        [ region B edge ]
   │                        │
   ▼                        ▼
route to the request's HOME shard (by entity, e.g. account_id)
   │  idempotency record + write live in ONE authoritative region per entity
   ▼
[ home region for account_X ]  ←─ single-writer per entity, linearizable locally
   │  async replicate outcome to other regions (read-only views)
```

The layered design: (1) **Client idempotency keys** make retries safe regardless of which region receives them — but the key's *record* must be authoritative in one place, or two regions could both think they're the first to process it. (2) **Entity-home / single-writer-per-entity routing**: partition by the entity that owns the invariant (account, order) and route all writes for that entity to one **home region**, where its idempotency record and its data live together and writes are linearizable *locally* (no cross-region consensus on the hot path). A retry that lands in region B is *forwarded* to the entity's home region, where the idempotency check sees the original. (3) **Asynchronous replication** of outcomes to other regions as read-only views — reads can be served locally with bounded staleness, writes go home. (4) **For cross-entity or cross-region transactions**, use **sagas with compensating actions** rather than global 2PC/consensus, accepting a pending state during partitions. (5) **Conflict handling for the rare case** where home assignment changes (region failover): use fencing tokens / epochs so a stale region can't accept writes after failover, and reconcile via the durable idempotency log.

The expert framing: you do **not** make the whole API globally strongly consistent — that would mean cross-region consensus (100ms+) on every write and a global outage on any partition. Instead you **localize the strong-consistency requirement to a single home region per entity**, make everything idempotent so the inevitable cross-region retries are safe, and let the rest be asynchronously replicated and eventually consistent. CAP/PACELC are resolved *per entity, per region*, not globally. The hardest operational piece is failover (moving an entity's home region) without violating idempotency or losing the dedup record — which is why the idempotency log must be durable and replicated, and writes must be fenced by epoch so a partitioned old-home can't keep accepting them.

### Q32. [Theory] What is Hyrum's Law and how does it change how you think about API contracts and "breaking changes"?

**Hyrum's Law** states: *"With a sufficient number of users of an API, it does not matter what you promise in the contract; all observable behaviors of your system will be depended on by somebody."* Coined by Hyrum Wright at Google, it's the empirical reality that the *de facto* contract is everything observable, not just what you documented.

This is sobering at scale because it means **your contract is larger than you think and you don't fully control it.** Someone depends on the *order* of your JSON fields even though JSON is unordered. Someone parses your human-readable error *message* even though you said to use the `code`. Someone relies on a response time being under 50ms, on an undocumented field that leaked, on the exact text of an error, on the fact that your pagination happens to return results in insertion order, or on a bug that they've coded a workaround for. The moment you "fix" any of these, some integration breaks even though you violated no *documented* promise.

```
Documented contract        ⊂   Observable behavior (the REAL contract)
- field names/types            - field ORDER, whitespace, null vs absent
- status codes                 - exact error message text
- pagination semantics         - latency characteristics, default ordering
- the `code` field             - leaked internal fields, timing, even bugs
```

How it changes my thinking: (1) **Minimize the observable surface deliberately** — randomize/normalize things you don't want depended on (e.g., GraphQL deliberately randomizes some behaviors; some APIs shuffle response field order in non-prod to break implicit dependencies early; Stripe adds a small amount of jitter so clients don't depend on exact timing). The defensive move is to make the surface *intentionally* unstable where you don't want a contract. (2) **Treat "breaking" as empirical, not theoretical** — before changing even an "undocumented" behavior at scale, I instrument and look at what consumers actually do, because the docs are a lower bound on the real contract. (3) **Invest in the things that shrink implicit coupling**: tolerant-reader guidance, opaque tokens (so cursors/IDs can't be parsed), stable machine codes paired with freely-changeable human messages, and version pinning so I can change behavior for new pins while preserving old ones. (4) **It strengthens the case for automated contract/breaking-change detection and for keeping the public surface as small as possible** — every field, header, and behavior you expose is a promise you may be forced to keep forever. The mature posture is humility: at scale you can't reason about all your consumers, so you build mechanisms (telemetry, version pinning, deliberate non-determinism, deprecation tooling) that let you evolve *despite* not knowing exactly who depends on what.

### Q33. [Practical] You're designing the public API for a new platform from scratch. What foundational decisions will be the hardest to reverse, and how do you get them right?

The expensive mistakes are the ones baked into the contract that thousands of integrators encode against, because reversing them means a breaking change and a multi-year migration (per the deprecation lifecycle). I'd spend disproportionate care on the decisions that are **load-bearing and irreversible**, and treat the rest as evolvable.

The hard-to-reverse foundations, with how I'd get each right:

1. **Versioning strategy** — pick it *before launch*, because retrofitting versioning onto an unversioned API is brutal. For a platform meant to evolve continuously, I'd lean toward date-based pinning with a transformation layer; at minimum, every response carries a version and clients pin. Reversing this later is nearly impossible.
2. **The error contract** — stable machine codes, RFC 9457 shape, trace IDs, field-level errors, from day one. Once integrators branch on your error format, you can never change its shape, only extend it.
3. **Pagination model** — cursor-based and opaque from the start. Launching with offset pagination and switching to cursors later breaks everyone's paging loops. Make the cursor opaque so its internals stay yours.
4. **Identifier scheme** — opaque, prefixed, string IDs (`cus_123`, `ord_456`) rather than exposing raw auto-increment integers. Exposing sequential integer PKs leaks volume, invites enumeration attacks, and locks you out of resharding or changing your storage; opaque prefixed IDs (Stripe-style) are self-describing, non-enumerable, and storage-independent. This is almost impossible to change post-launch.
5. **Idempotency and concurrency model** — bake `Idempotency-Key` support into write endpoints and ETag/`If-Match` into mutations from the start; adding safe-retry semantics later means clients have already written unsafe retry logic against you.
6. **Auth model and tenancy/scoping** — how resources are scoped to accounts and how permissions/scopes work is deeply woven into every URL and response; getting tenancy isolation and the permission model right early prevents both security holes and a total redesign.
7. **Granularity and resource model** — overly chatty or overly coarse resources are painful to fix; I'd design around real client journeys (and anticipate a BFF/aggregation layer) rather than mirroring my database tables in the URL structure.

The meta-strategy: **for irreversible decisions, copy the proven patterns** (Stripe/Google/GitHub solved these publicly — opaque prefixed IDs, dated versions, RFC 9457 errors, cursor pagination, idempotency keys are battle-tested defaults), run a private beta with real integrators to surface implicit-contract issues *before* the surface ossifies, and write down a compatibility/deprecation policy as a public promise so both you and consumers know the rules. Everything reversible (new endpoints, new optional fields, new enum values) I'd treat lightly and evolve fast; everything irreversible I'd treat as a one-way door and get a second set of senior eyes on. The single highest-leverage principle: **design so that most future changes are additive and backward-compatible**, because the cheapest breaking change is the one you never have to make.

### Q34. [Behavioral] Tell me about a time you had to make a hard call between shipping a breaking API change quickly and protecting existing consumers. How did you decide and drive it?

A strong answer uses a **STAR** structure and shows judgment that balances business pressure against long-term contract integrity — the essence of senior API stewardship.

*Situation/Task:* We discovered a field in a widely-used public endpoint that returned monetary amounts as floating-point dollars (`amount: 19.99`). This was causing rounding bugs in integrations and we needed to move to integer minor units (`amount: 1999`, cents). Product wanted it fixed fast because a partner had just been bitten by a rounding error; but the field was consumed by hundreds of integrations, and silently changing its type or meaning would break every one of them — a textbook breaking change to a load-bearing field.

*Action:* I refused the tempting-but-wrong option (change the existing field's semantics with a "heads up" email) because Hyrum's Law guaranteed silent breakage at scale, and instead drove an **additive migration**. I added a *new* field (`amount_minor`, integer cents) alongside the existing `amount`, marked the old field deprecated in the docs and via a `Deprecation` header, and shipped both simultaneously so existing clients kept working untouched while new and migrating clients adopted the correct field. I instrumented per-consumer usage of the old field so we'd know who still depended on it, published a migration guide, and set a sunset date over a year out. For the *specific* partner who was bleeding money, we fast-tracked them onto the new field directly. To protect new integrators from the trap entirely, I made the new field the documented default and the old one clearly marked legacy.

*Result:* Zero consumers broke. The acute partner pain was resolved within days via the new field, and the broad migration ran on a humane timeline with telemetry showing usage of the deprecated field decay toward zero before we sunset it. The lasting organizational change was the bigger win: this incident became the case study for our **API compatibility policy** — additive-over-mutative, deprecate-then-sunset with telemetry, and a review gate that flags any change to an existing field's type or semantics as breaking.

The meta-points interviewers look for: I separated the *urgent* (one partner's rounding bug) from the *broad* (the systemic fix) and solved them on different timelines; I resisted a fast path that would have traded a year of consumer trust for a week of speed; I led with the principle (additive change + deprecation lifecycle) and backed it with data (per-consumer telemetry); and I turned a one-off into a durable policy so the next engineer wouldn't face the same dilemma unarmed. The hard call wasn't technical — the additive pattern is well known — it was holding the line on consumer protection under product pressure, and making that defensible with a concrete, faster-feeling alternative rather than just saying "no."

### Q35. [Theory] How do you handle long-running operations and webhooks reliably at scale, including ordering, retries, and the thundering-herd of callbacks?

Async/long-running operations and the webhooks that notify clients of their completion are where API design meets distributed-systems reality, and the naive version (fire a POST at the customer's URL when done, hope it works) falls apart at scale. A robust design treats webhook delivery as its own at-least-once messaging system with the customer as an unreliable consumer.

The pieces: (1) **Long-running operation as a resource** (202 + operation status endpoint, as in Q17) so a client can always *poll* the truth even if a webhook is missed — webhooks are an optimization, not the source of truth; the operation resource is. (2) **Delivery is at-least-once, so customers must be idempotent** — every webhook carries a unique event ID and you tell consumers to dedup on it, because you *will* redeliver on uncertain acks (Two Generals again). (3) **Retries with exponential backoff and a dead-letter** — if the customer's endpoint is down, retry with backoff over hours, then park undeliverable events in a dead-letter store the customer can replay, rather than retrying forever or dropping silently. (4) **Ordering is not guaranteed across events**, so either include enough state in each event that order doesn't matter (carry the full new state, not just a delta), or include a sequence number / version so the consumer can detect and discard out-of-order/stale events. Promising strict ordering on webhooks is a trap — it forces head-of-line blocking and couples you to one slow consumer.

```
job done → emit event(id, type, version, payload) → delivery queue
   │  per-destination worker, at-least-once
   ▼
POST customer_url  ──(2xx)──▶ done
   │  (timeout / 5xx / no ack)
   ▼  retry: 10s, 1m, 10m, 1h ... (capped, jittered)
   └─▶ exhausted → dead-letter (customer replays via API)
```

The thundering-herd dimension: a popular event type (e.g., a daily settlement that fires for all accounts at midnight) can blast a synchronized flood of callbacks, and a fan-out broadcast can overwhelm both your delivery fleet and the customers' endpoints. Mitigations: **spread/jitter** scheduled fan-outs over a window rather than firing all at once; **per-destination rate limiting and concurrency caps** so one slow customer can't back up the whole delivery system (bulkhead the delivery workers per destination); **circuit-break** destinations that are persistently failing so you stop wasting capacity hammering a dead endpoint and back off until it recovers. The same hazard appears on the *polling* side — if you tell clients "poll the operation status," a million clients polling in a tight loop is a self-inflicted DDoS, so you return `Retry-After` hints, jittered, and ideally push completion via webhook so polling is a fallback, not the norm.

The expert synthesis: model the operation as a durable, pollable resource (the source of truth), deliver completion events at-least-once with idempotent consumers and dead-lettering, never promise strict ordering (carry state or versions instead), and defend both your delivery fleet and your customers' endpoints from synchronized fan-out with jitter, per-destination bulkheads, and circuit breakers. This is the same toolkit as cascading-failure prevention, applied to the callback edge of the API.

---

## ✅ Key Takeaways

- **Respect HTTP semantics.** Safe vs idempotent methods, the right status code, and conditional requests aren't pedantry — the entire ecosystem (proxies, LBs, retry libraries) makes correctness decisions based on them.
- **Idempotency keys turn the impossible into the achievable.** Exactly-once *delivery* is a fallacy; at-least-once + idempotent processing = effectively-once. Bind keys to a request fingerprint and persist the outcome in the same transaction as the side effect.
- **Cursor (keyset) pagination beats offset at scale** — O(log n) at any depth and stable under concurrent writes. Offset only suits shallow, low-write, admin data. Keep cursors opaque so their internals stay yours.
- **Design for backward compatibility first; version only when you must.** Additive-only changes + tolerant readers let you evolve continuously. Date-based versioning with transformers (Stripe-style) is the pattern that makes continuous evolution survivable at platform scale.
- **The error contract is part of the API.** Stable machine codes (never reworded), RFC 9457 shape, trace IDs, and field-level detail. Clients branch on `code`, not on human messages.
- **Rate limiting needs shared atomic state and a cooperative contract** — Redis + Lua for the fleet, `RateLimit-*` headers and `Retry-After` so good clients self-throttle before getting 429. Decide fail-open vs fail-closed deliberately.
- **BFFs exist because one general-purpose API rarely serves a latency-sensitive mobile client well.** Aggregate and shape per client; keep genuinely shared logic downstream.
- **Bulk endpoints live or die on partial-failure handling** — per-item results (207), correlate by index/ID, cap the size, and combine with idempotency.
- **Model long-running work as a durable, pollable resource** (202 + operation status), deliver completion at-least-once with idempotent consumers, never promise strict webhook ordering, and defend against synchronized fan-out.
- **Hyrum's Law: your real contract is every observable behavior.** Minimize and deliberately destabilize the surface you don't want depended on; instrument before you change anything at scale.

## ⚠️ Common Pitfalls

- **Unbounded collection endpoints** (`GET /things` returning everything) — always paginate with a server-enforced cap.
- **Offset pagination on large or high-write data** — slow at depth and drifts under concurrent writes; use keyset.
- **Treating `POST` retries as safe** — without idempotency keys, a retried `POST /orders` double-creates. Make writes idempotent.
- **Persisting the idempotency record separately from the side effect** — a crash in the gap double-charges or skips. Couple them transactionally.
- **Changing the meaning of an existing field, status code, or error code** — a breaking change even when the type is unchanged. Add a new field/code instead.
- **Making an optional request field required, or tightening validation** — backward-incompatible despite "just being validation."
- **Branching clients on human-readable error messages** instead of stable codes — reword the message and you break them.
- **Exposing raw auto-increment integer IDs** — leaks volume, enables enumeration, and locks you into your storage. Use opaque prefixed IDs.
- **Per-node rate limiters** that multiply the limit by the fleet size — use shared atomic state (Redis + Lua).
- **Promising exactly-once delivery or strict webhook ordering** — both are traps; choose at-least-once + idempotent consumers, carry state or version numbers.
- **Bulk endpoints that fail the whole batch on one bad item** — return per-item results so clients retry only failures.
- **Holding an HTTP connection open for a minutes-long job** — use 202 + an operation resource (async request-reply) instead.
- **Sunsetting a version with 404 and no runway** — use a long, telemetry-driven deprecation with `Sunset` headers, brownouts, and 410 Gone at the end.
- **Intolerant readers** (failing on unknown fields/enums) — they turn even additive changes into breakage; code defensively and require tolerance in your policy.

## 📚 Further Reading

- **RFC 9457 — Problem Details for HTTP APIs** (supersedes RFC 7807) — the standard structured error format; pair with RFC 8594 (`Sunset` header) for deprecation.
- **Google AIPs (aip.dev)** — API Improvement Proposals: resource design, long-running operations (AIP-151), pagination, versioning, and error models, codified and battle-tested.
- **Stripe API design blog & docs** — the reference for idempotency keys, date-based versioning with transformers, opaque prefixed IDs, and pragmatic public-API evolution.
- **Microsoft REST API Guidelines & Zalando RESTful API Guidelines** — comprehensive, opinionated, lintable standards for large-org API consistency.
- **Sam Newman, *Building Microservices* (2nd ed.)** — BFF pattern, API evolution, contract testing, and service decomposition trade-offs.
- **Martin Fowler — "Tolerant Reader," "Consumer-Driven Contracts"** (martinfowler.com) — the foundational patterns for evolving APIs without breaking consumers.
- **Hyrum's Law (hyrumslaw.com) & *Software Engineering at Google* (the "Hyrum Wright" chapters)** — why your real contract is every observable behavior, and how Google governs API change at scale.
- **IETF "RateLimit header fields for HTTP" draft** — the emerging standard for communicating rate-limit state cooperatively to clients.
