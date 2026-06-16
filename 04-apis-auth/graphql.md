# GraphQL

A deep, interview-focused guide to GraphQL: schema design, resolvers, the N+1 problem, pagination, security, federation, caching, and Spring for GraphQL — with Java-centric examples and production trade-offs. Knowledge current through 2026.

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

### Q1. [Theory] What is GraphQL and what problem does it solve?

GraphQL is a query language for APIs and a server-side runtime for executing those queries against a typed schema. It was created at Facebook in 2012 and open-sourced in 2015 to address the pain of REST APIs serving mobile clients: **over-fetching** (endpoints return more data than the client needs) and **under-fetching** (the client must make multiple round trips to assemble a screen).

With GraphQL the client declares exactly which fields it wants in a single request, and the server returns a JSON response with exactly that shape. The contract is a strongly typed schema, so tooling can validate queries and generate types at build time. The key "why": GraphQL moves control over response shape from the server to the client, which decouples client release cadence from backend changes. The trade-off is that the server takes on more responsibility (query planning, complexity control, batching) that REST pushes onto the URL/HTTP layer.

### Q2. [Theory] What are the three root operation types in GraphQL?

GraphQL defines three operation types:

- **Query** — read-only fetches. Conventionally side-effect free and safe to execute in parallel.
- **Mutation** — operations that change server state (create/update/delete). Top-level mutation fields execute **serially**, left to right, so order is deterministic.
- **Subscription** — a long-lived operation that streams results to the client when an event occurs (e.g., over WebSocket). Used for real-time updates like chat or live dashboards.

A schema can declare a root type for each. Only `Query` is mandatory in practice; `Mutation` and `Subscription` are optional.

### Q3. [Theory] What is the GraphQL Schema Definition Language (SDL)?

SDL is the type-system DSL used to describe a GraphQL API in a language-agnostic way. It defines object types, fields, scalars, enums, interfaces, unions, input types, and directives. It is the contract between client and server.

```graphql
type Author {
  id: ID!
  name: String!
  books: [Book!]!          # non-null list of non-null Books
}

type Book {
  id: ID!
  title: String!
  pageCount: Int
  author: Author!
}

type Query {
  bookById(id: ID!): Book
  authors: [Author!]!
}
```

The `!` suffix means non-null. `[Book!]!` is a non-null list whose elements are also non-null. Built-in scalars are `Int`, `Float`, `String`, `Boolean`, and `ID`. SDL is the recommended "schema-first" way to design an API because it reads like documentation and is reviewable by both frontend and backend teams.

### Q4. [Practical] How do you write a query that fetches nested data, and how does the response map to it?

Suppose a client building a book detail screen needs the book title plus the author's name. In REST that might be `/books/42` followed by `/authors/7`. In GraphQL it is one request:

```graphql
query BookDetail {
  bookById(id: "42") {
    title
    pageCount
    author {
      name
    }
  }
}
```

The response mirrors the query exactly:

```json
{
  "data": {
    "bookById": {
      "title": "Effective Java",
      "pageCount": 412,
      "author": { "name": "Joshua Bloch" }
    }
  }
}
```

In production you would name the operation (`BookDetail`), which helps with logging, persisted queries, and APM traces. Aliases (`b: bookById(id:"42")`) let a client fetch the same field twice with different arguments in one request.

### Q5. [Theory] What is a resolver?

A resolver is the function that produces the value for a single field in the schema. The GraphQL engine walks the query tree and calls one resolver per field. A resolver receives four conceptual inputs: the parent/source object, the field arguments, a shared context (auth, DataLoaders, request scope), and execution info.

The crucial mental model: **a query of N fields triggers up to N resolver invocations**, and the engine resolves them top-down, parent before child. If a type has no explicit resolver for a field, a default "property resolver" reads the matching property off the parent object (e.g., `book.getTitle()` in Java). This per-field model is what makes the N+1 problem (Q12) so easy to hit.

### Q6. [Coding] Write a minimal Spring for GraphQL resolver in Java.

**Problem:** expose `bookById(id)` and a computed `author` field using Spring for GraphQL (Spring Boot 3.x).

```java
// schema.graphqls is on the classpath under src/main/resources/graphql/

@Controller
public class BookController {

    private final BookRepository books;
    private final AuthorRepository authors;

    public BookController(BookRepository books, AuthorRepository authors) {
        this.books = books;
        this.authors = authors;
    }

    // Maps to Query.bookById(id: ID!): Book
    @QueryMapping
    public Book bookById(@Argument String id) {
        return books.findById(id).orElse(null);
    }

    // Field resolver: resolves Book.author from the parent Book
    @SchemaMapping(typeName = "Book", field = "author")
    public Author author(Book book) {
        return authors.findById(book.getAuthorId()).orElseThrow();
    }
}
```

**Notes:** `@QueryMapping` is shorthand for `@SchemaMapping(typeName = "Query")`. `@Argument` binds a GraphQL argument to a method parameter. Returning `null` for a nullable field is fine; returning `null` for a non-null field bubbles an error up to the nearest nullable ancestor. **Edge case:** the `author` resolver here is exactly where N+1 will bite if `bookById` becomes `books` returning a list — that is fixed with DataLoader (Q14).

### Q7. [Theory] How does error handling differ from REST?

Unlike REST, a GraphQL response almost always returns **HTTP 200** even when individual fields fail (HTTP-level codes are used only for transport/parse failures). Errors are reported in a top-level `errors` array alongside whatever `data` could still be resolved — so partial success is a first-class concept.

```json
{
  "data": { "bookById": { "title": "Effective Java", "author": null } },
  "errors": [
    {
      "message": "Author service unavailable",
      "path": ["bookById", "author"],
      "extensions": { "code": "DOWNSTREAM_UNAVAILABLE" }
    }
  ]
}
```

The `path` pinpoints the failing field; the `extensions` object carries machine-readable metadata (error codes, validation details). A field that throws bubbles its `null` up to the nearest nullable parent — this null-propagation rule is why over-using `!` on fields can turn a small failure into a whole-subtree wipeout.

---

## 🟡 Intermediate (3–7 yrs)

### Q8. [Theory] Schema-first vs code-first — when do you choose each?

**Schema-first** starts from an SDL `.graphqls` file as the source of truth; resolvers are wired to it (Spring for GraphQL, Apollo Server with SDL). **Code-first** generates the SDL from typed code/annotations (Netflix DGS Java, graphql-java with builders, gqlgen in Go, Strawberry in Python).

```
Schema-first                         Code-first
-----------                          ----------
SDL file  ── source of truth         Java/TS code ── source of truth
   │                                     │
   ▼ wire resolvers                      ▼ generate SDL
runtime schema                        runtime schema + emitted .graphqls
```

Schema-first is excellent for cross-team contract review and for letting frontend developers see the API as documentation, but it risks drift between SDL and resolver implementations if not checked. Code-first guarantees the schema and code never drift (the schema *is* the code) and gives full IDE/type safety, but the SDL becomes a generated artifact that is harder to review in a PR. In Java teams, **Spring for GraphQL** leans schema-first while **Netflix DGS** is code-first; many large orgs standardized on DGS for type-safety guarantees. Choose schema-first when contract negotiation between teams dominates; code-first when a single team owns the service and wants compiler-enforced correctness.

### Q9. [Theory] Explain non-null semantics and error null-propagation.

A field typed `String` may resolve to null; a field typed `String!` may not. If a non-null field resolves to null (or its resolver throws), GraphQL cannot return null there, so it propagates the null **upward** to the nearest nullable parent, nulling that entire branch and recording an error in `errors`.

```
type User { profile: Profile! }       # profile is non-null
type Profile { avatarUrl: String! }   # avatarUrl is non-null

If avatarUrl errors → Profile cannot be null (it's !) →
the null bubbles to User.profile → if User itself is non-null,
it bubbles further up until it hits a nullable field (often the root).
```

The design lesson: be conservative with `!`. Mark a field non-null only when you can genuinely guarantee a value; otherwise a single flaky downstream can blank out a large response subtree. Lists have nuance too: `[Book!]` allows a null list but no null elements, while `[Book]!` requires the list but allows null elements.

### Q10. [Practical] How do you design pagination, and why are Relay cursor connections preferred over offset/limit?

Offset/limit (`books(limit: 20, offset: 40)`) is simple but breaks on mutating data: if a row is inserted before your offset, page 3 shows a row you already saw on page 2, and deletes cause skips. Offset also degrades on large tables (`OFFSET 100000` scans and discards rows).

**Relay cursor connections** encode a stable pointer ("cursor") per item, so you page relative to a known position rather than a numeric offset:

```graphql
type BookConnection {
  edges: [BookEdge!]!
  pageInfo: PageInfo!
  totalCount: Int
}
type BookEdge { node: Book!  cursor: String! }
type PageInfo {
  hasNextPage: Boolean!
  hasPreviousPage: Boolean!
  startCursor: String
  endCursor: String
}
type Query {
  books(first: Int, after: String, last: Int, before: String): BookConnection!
}
```

Query: `books(first: 20, after: "Y3Vyc29yOjQw")`. The cursor is typically a base64-encoded sort key (e.g., `(createdAt, id)`), and the server translates it to a keyset/seek query: `WHERE (created_at, id) > (?, ?) ORDER BY created_at, id LIMIT 21` (fetch one extra to compute `hasNextPage`). This is **stable under concurrent writes** and **performant at depth**. In production you'd: keep cursors opaque (clients must not parse them), include the sort field inside the cursor, and validate that `first`/`last` are bounded (e.g., max 100) to prevent abuse. The cost is more boilerplate and a less intuitive API than page numbers.

### Q11. [Coding] Implement keyset pagination producing a Relay connection in Java.

**Problem:** return the first N books after a cursor, with correct `hasNextPage`.

```java
public BookConnection books(Integer first, String after) {
    int limit = Math.min(first == null ? 20 : first, 100); // cap page size
    Cursor c = (after == null) ? null : Cursor.decode(after); // base64 -> (createdAt,id)

    // Fetch limit+1 to detect a next page without a COUNT(*)
    List<Book> rows = (c == null)
        ? repo.findFirstPage(limit + 1)
        : repo.findAfter(c.createdAt(), c.id(), limit + 1); // keyset/seek

    boolean hasNext = rows.size() > limit;
    if (hasNext) rows = rows.subList(0, limit);

    List<BookEdge> edges = rows.stream()
        .map(b -> new BookEdge(b, Cursor.encode(b.getCreatedAt(), b.getId())))
        .toList();

    String endCursor = edges.isEmpty() ? null
        : edges.get(edges.size() - 1).cursor();

    PageInfo info = new PageInfo(hasNext, after != null, /*start*/
        edges.isEmpty() ? null : edges.get(0).cursor(), endCursor);
    return new BookConnection(edges, info);
}
```

```sql
-- repo.findAfter — keyset/seek, index on (created_at, id)
SELECT * FROM book
WHERE (created_at, id) > (:createdAt, :id)
ORDER BY created_at, id
LIMIT :limit;
```

**Time:** O(log n + page) per page via the composite index (no offset scan). **Space:** O(page). **Edge cases:** empty result (null cursors, `hasNextPage=false`); duplicate `created_at` values disambiguated by `id` in the tuple; never expose the raw cursor contents — encode and treat as opaque so clients can't inject sort values.

### Q12. [Theory] What is the N+1 problem in GraphQL and why is it so common?

Because each field has its own resolver and resolvers fire per object, a list query naturally triggers a cascade. Resolving `books { author { name } }` fetches N books with one query, then calls the `author` resolver once **per book** — N additional queries — hence "N+1".

```
Query: books { title author { name } }

books resolver ............... 1 query  -> [b1,b2,...,bN]
  b1.author resolver ......... 1 query
  b2.author resolver ......... 1 query
  ...                          ...
  bN.author resolver ......... 1 query
                               -----------------
Total: 1 + N queries  (N+1)
```

It is common precisely because the field-resolution model hides the cost: the developer writes a clean `author(Book)` resolver, but the engine multiplies it across the list. The fix is **batching + caching per request**, which is what DataLoader provides (Q14). This is the single most-asked GraphQL performance question in interviews.

### Q13. [Theory] How does DataLoader solve N+1?

DataLoader is a per-request utility that **batches** individual load calls made within a single tick of the event loop into one batch call, and **caches** by key for the duration of the request. Instead of N `findById` calls, the loader collects the N author IDs and issues one `findAllById([...])`, then distributes results back to each waiting resolver.

```
Without DataLoader            With DataLoader
------------------            ----------------
author(b1) -> SELECT ... 7    load(7) ┐
author(b2) -> SELECT ... 7    load(7) ├─ collected this tick
author(b3) -> SELECT ... 9    load(9) ┘
                              -> SELECT ... IN (7,9)  (1 query, dedup)
```

Two properties matter: **batching** turns N+1 into 2 queries (1 for books + 1 for authors), and **caching** dedupes repeated keys (the same author requested by many books loads once). The cache is scoped to the request to avoid stale cross-request data and cross-user data leaks. In Spring for GraphQL the loader is registered on the `BatchLoaderRegistry`; in DGS you use `@DgsDataLoader`.

### Q14. [Coding] Wire a batched DataLoader for `Book.author` in Spring for GraphQL.

**Problem:** eliminate N+1 on `books { author { name } }`.

```java
@Configuration
public class DataLoaderConfig {

    // Register a batch loader keyed by authorId
    public DataLoaderConfig(BatchLoaderRegistry registry, AuthorRepository authors) {
        registry.forTypePair(String.class, Author.class)
                .registerMappedBatchLoader((authorIds, env) -> {
                    // ONE query for all requested ids
                    Map<String, Author> byId = authors.findAllById(authorIds).stream()
                        .collect(Collectors.toMap(Author::getId, a -> a));
                    return Mono.just(byId); // mapped loader returns key->value
                });
    }
}

@Controller
class BookController {
    @QueryMapping
    public List<Book> books() { return repo.findAll(); }

    // The framework injects the registered loader by type pair
    @SchemaMapping(typeName = "Book", field = "author")
    public CompletableFuture<Author> author(Book book, DataLoader<String, Author> loader) {
        return loader.load(book.getAuthorId()); // batched + cached per request
    }
}
```

**Why it works:** every `author` resolver call enqueues a key; the framework dispatches one batch after the current resolution level, so the SQL collapses to `... WHERE id IN (...)`. **Time:** from O(N) round trips to O(1) batches per association. **Space:** O(distinct keys) for the request-scoped cache. **Edge cases:** a `registerMappedBatchLoader` must return a *map* (missing keys → null, which is correct for a not-found author); a list-based `registerBatchLoader` must return results **in the same order** as the input keys or you'll mismatch data — a classic, dangerous bug.

### Q15. [Practical] A mobile team reports a screen that's slow only in production. How do you diagnose a GraphQL perf issue?

First, capture the exact operation (operation name + variables) from APM or access logs — GraphQL hides this behind a single endpoint, so per-resolver tracing (Apollo tracing / Micrometer Observation in Spring for GraphQL) is essential. Look for resolver-level timing: a wide gap between total time and the sum of "interesting" resolvers usually means **N+1** (lots of tiny identical queries) or a missing index behind a list resolver.

Approach: (1) enable resolver tracing to see which field dominates; (2) check DB query logs for repeated identical statements (N+1 signature); (3) introduce or verify a DataLoader; (4) bound list arguments and add complexity limits if the client is requesting deep/wide trees; (5) consider field-level caching for hot, rarely-changing data. In production I'd add **persisted queries** so I know the finite set of operations clients actually send and can index/optimize the DB for them, plus alerting on p99 resolver latency per field. The trade-off to communicate to the mobile team: GraphQL flexibility means a single bad query shape can be expensive, so we constrain it server-side rather than removing flexibility entirely.

### Q16. [Theory] What are GraphQL fragments and why use them?

A fragment is a reusable selection set. It DRYs up repeated field selections and is central to client tooling like Apollo's normalized cache and Relay's data-masking (each component declares the exact fields it needs).

```graphql
fragment BookSummary on Book {
  id
  title
  author { name }
}

query {
  newReleases { ...BookSummary }
  bestSellers { ...BookSummary }
}
```

Inline fragments (`... on Premium { perk }`) are used to select type-specific fields on **interfaces** and **unions**. Fragments also enable `@include(if:)` / `@skip(if:)` directives for conditional fetching. The "why": fragments let UI components co-locate their data requirements with the component, which is what makes GraphQL composable on the frontend.

### Q17. [Practical] How do mutations differ in practice, and what's the recommended input/payload pattern?

Mutations change state and their **top-level fields run serially**, so two top-level mutation fields in one document won't race. The community convention is the **input object + payload** pattern: take a single `input` argument and return a structured payload (not just the entity) so you can evolve the response (add `userErrors`, return affected related objects) without breaking the field signature.

```graphql
input CreateBookInput { title: String!  authorId: ID!  pageCount: Int }
type CreateBookPayload {
  book: Book
  userErrors: [UserError!]!   # business errors, not transport errors
}
type Mutation { createBook(input: CreateBookInput!): CreateBookPayload! }
```

```java
@MutationMapping
public CreateBookPayload createBook(@Argument CreateBookInput input) {
    var errors = validate(input);
    if (!errors.isEmpty()) return new CreateBookPayload(null, errors);
    Book saved = service.create(input);
    return new CreateBookPayload(saved, List.of());
}
```

Putting recoverable, user-facing validation errors in `userErrors` (rather than the top-level `errors` array) is a deliberate choice: the top-level array is for exceptional/system failures, while `userErrors` are expected outcomes the client UI handles (e.g., "title already taken"). This keeps clients from treating a validation message as a system crash.

### Q18. [Theory] How do subscriptions work and what are the operational gotchas?

A subscription opens a persistent stream (historically `subscriptions-transport-ws`, now the `graphql-ws` protocol over WebSocket; SSE is also used). The server holds the connection and pushes a result each time a domain event fires.

```graphql
type Subscription { bookAdded(authorId: ID!): Book! }
```

```java
@SubscriptionMapping
public Flux<Book> bookAdded(@Argument String authorId) {
    return bookEvents.flux().filter(b -> b.getAuthorId().equals(authorId));
}
```

Operational gotchas: WebSockets are **stateful**, which complicates horizontal scaling (you need a shared pub/sub like Redis/Kafka to fan events to the node holding each connection), load balancing (sticky sessions or connection-aware routing), and authentication (validate the token on connect *and* re-check on long-lived connections). Subscriptions also resist HTTP caching entirely. Many teams prefer SSE or even polling for "live-ish" data because subscriptions' infra cost is high; reserve true subscriptions for genuinely real-time features.

---

## 🟠 Advanced (8–12 yrs)

### Q19. [Theory] How do you defend a GraphQL endpoint against expensive/malicious queries?

A single endpoint accepting arbitrary nested queries is a denial-of-service surface. Defenses, layered:

```
Incoming query
   │
   ▼ [1] Disable/limit introspection in prod
   ▼ [2] Max query depth   (reject depth > 10)
   ▼ [3] Query complexity / cost analysis (assign weights, cap total)
   ▼ [4] Amount limiting (cap `first`, paginated args)
   ▼ [5] Persisted queries (allowlist of known operations)
   ▼ [6] Rate limiting / timeouts per operation
   ▼ Execute
```

- **Depth limiting** rejects pathologically nested queries (`a{b{c{d...}}}`) that could recurse through cyclic relations (author→books→author→...).
- **Complexity/cost analysis** assigns a cost to each field (list fields multiply by requested count) and rejects queries above a budget — more precise than raw depth because a shallow-but-wide query can be just as expensive.
- **Persisted queries / allowlisting** is the strongest control for first-party clients: only pre-registered operation hashes are accepted, so attackers cannot craft new shapes at all.
- **Introspection** should be restricted in production (Q21) to avoid handing attackers your full schema map.

In Java, graphql-java ships `MaxQueryDepthInstrumentation` and `MaxQueryComplexityInstrumentation`; Spring for GraphQL exposes these via `Instrumentation` beans.

### Q20. [Coding] Implement query depth and complexity limiting in graphql-java / Spring for GraphQL.

**Problem:** reject queries deeper than 10 levels or costing more than 1000 complexity points.

```java
@Configuration
public class SecurityInstrumentation {

    @Bean
    public Instrumentation maxDepthInstrumentation() {
        return new MaxQueryDepthInstrumentation(10);
    }

    @Bean
    public Instrumentation complexityInstrumentation() {
        // Field cost = 1 + (childComplexity * multiplier).
        // List fields use the requested 'first' arg as the multiplier.
        FieldComplexityCalculator calc = (env, childComplexity) -> {
            int multiplier = env.getArguments().containsKey("first")
                ? ((Number) env.getArguments().get("first")).intValue()
                : 1;
            return 1 + childComplexity * multiplier;
        };
        return new MaxQueryComplexityInstrumentation(1000, calc);
    }
}
```

A query like `users(first: 100) { posts(first: 100) { comments(first: 100) { id } } }` computes to roughly `100 * 100 * 100 = 1,000,000` cost and is rejected before execution. **Time/space:** analysis is O(query nodes) and runs on the parsed AST before any data fetch — so it costs nothing in DB load. **Edge cases:** complexity must be evaluated **before** execution (it's a static-analysis instrumentation, not a runtime counter); cap default page sizes so a missing `first` argument doesn't default to "unlimited"; combine depth + complexity because each catches attacks the other misses.

### Q21. [Theory] Should introspection be enabled in production? What are the security trade-offs?

Introspection (`__schema`, `__type`) lets clients query the schema itself — it powers GraphiQL, codegen, and the Apollo Studio explorer. The risk is that it hands an attacker a complete map of every type, field, and argument, dramatically lowering the cost of reconnaissance and enabling tools like Clairvoyance to reconstruct schemas even when introspection is partially disabled.

The pragmatic stance for 2026: disable or gate introspection on **public/internet-facing** production endpoints (behind auth, an internal network, or feature flag), while keeping it on in dev/staging where tooling needs it. Note that disabling introspection is *defense in depth*, not real security — fields are still callable if guessed, so it must be paired with proper field-level authorization, persisted queries, and complexity limits. Treat hiding the schema as raising the bar, never as the control that protects sensitive operations.

### Q22. [Theory] Compare Apollo Federation and schema stitching for combining multiple GraphQL services.

Both compose multiple backend GraphQL services into one unified graph, but the ownership model differs fundamentally.

```
            Federation (v2)                       Stitching
            ----------------                       ---------
Client ─► Router/Gateway                Client ─► Gateway
            │ query plan                          │ delegates + merges
   ┌────────┼────────┐                   ┌────────┼────────┐
 Users    Products  Reviews            Users   Products  Reviews
 (owns    (owns      (extends           (gateway holds the merge logic
  User)    Product)   Product w/         and type mappings centrally)
                       reviews)
```

- **Federation** is *subgraph-driven*: each service annotates its schema (`@key`, `@external`, `@requires`, `@shares`) to declare which types it owns and how it extends others. A separate **composition** step builds a supergraph, and a **router** computes a query plan that fetches each part from the owning subgraph and stitches via entity references. Ownership lives with the team that owns the data — ideal for large orgs (Netflix, Apollo customers) with many autonomous teams.
- **Schema stitching** centralizes the merge logic in the gateway: it introspects subschemas and you define type merging/delegation in gateway config. It's more flexible for gluing schemas you don't control, but the gateway becomes a bottleneck of knowledge and a deployment coupling point.

Modern guidance: prefer **Federation v2** for org-scale federated graphs because ownership is distributed and the contract is declarative; use stitching for ad hoc composition of third-party or legacy schemas you can't modify. In Java, **Netflix DGS** has first-class federation support and is a common choice.

### Q23. [Coding] Define a federated subgraph entity and its reference resolver (Java / DGS-style).

**Problem:** `Products` owns `Product`; `Reviews` extends `Product` with `reviews`.

```graphql
# Products subgraph
type Product @key(fields: "id") {
  id: ID!
  name: String!
  price: Int!
}

# Reviews subgraph — extends the entity it does NOT own
type Product @key(fields: "id") {
  id: ID!                 # the key, marked @external implicitly under fed v2
  reviews: [Review!]!
}
type Review { id: ID!  rating: Int!  body: String! }
```

```java
// Reviews subgraph: resolve the Product *entity reference* from its key
@DgsEntityFetcher(name = "Product")
public Product product(Map<String, Object> values) {
    // The router sends only the @key fields; we hydrate what THIS subgraph owns.
    String id = (String) values.get("id");
    return new Product(id); // reviews resolved by the field fetcher below
}

@DgsData(parentType = "Product", field = "reviews")
public CompletableFuture<List<Review>> reviews(DgsDataFetchingEnvironment env) {
    Product p = env.getSource();
    return reviewLoader.load(p.getId()); // batched per request
}
```

**Key idea:** the router resolves `Product` from `Products`, then calls the Reviews subgraph's **entity fetcher** with just the `@key` (`{ id }`) to attach `reviews`. **Edge cases:** the entity fetcher receives only key fields, never the full object — don't assume `name`/`price` are present; batch the `reviews` field with a DataLoader or you re-introduce N+1 across the federation boundary; keep `@key` fields immutable and unique.

### Q24. [Theory] Why is HTTP caching hard with GraphQL, and what strategies work?

REST gets HTTP caching nearly for free: distinct URLs + `GET` + `Cache-Control`/`ETag` let CDNs and browsers cache responses by URL. GraphQL typically POSTs to a single `/graphql` URL with the query in the body, so URL-based caches see one opaque endpoint and can't cache by content.

Strategies:

- **Automatic Persisted Queries (APQ):** the client sends a SHA-256 hash of the query; if the server knows it, the full text isn't sent. Crucially this enables **`GET` requests with the hash in the query string**, which makes responses CDN-cacheable again.
- **Client-side normalized cache:** Apollo Client / Relay store objects by `__typename` + `id` in a flat cache, so a `Book` fetched in one query is reused everywhere — the cache key is the entity, not the request. This requires globally unique IDs.
- **Server-side field/resolver caching:** cache hot resolver results (e.g., Caffeine/Redis) keyed by arguments, with explicit invalidation.
- **`@cacheControl` hints + a caching gateway** (Apollo Router, Stellate/GraphCDN) that computes a response TTL as the minimum of the hints across the selected fields.

The "why it's hard": cache correctness depends on entity identity and field-level freshness, not URLs, so you push caching into the client store and a GraphQL-aware edge rather than relying on generic HTTP layers.

### Q25. [Practical] You're migrating a public REST API to GraphQL incrementally. What's your strategy?

I would not big-bang rewrite. Approach: (1) stand up a GraphQL layer **in front of** existing REST/services, with resolvers that call the REST endpoints (a "GraphQL-over-REST" facade) — this lets clients adopt GraphQL without rewriting backends. (2) Wrap each REST call in a DataLoader so the facade doesn't fan out N+1 HTTP calls. (3) Keep REST live in parallel; route new client features to GraphQL and migrate screens one at a time. (4) Instrument usage to find which REST endpoints are now fully behind GraphQL and can be retired.

Trade-offs to flag: the facade adds a network hop and its own failure mode (downstream REST latency now surfaces as resolver latency and partial errors); you must map REST status codes to GraphQL errors thoughtfully; caching changes from URL-based to the strategies in Q24. In production I'd also add complexity limits and persisted queries from day one on the new endpoint, since exposing a flexible query layer over existing services is exactly where DoS risk appears. The real-world precedent: GitHub runs a public GraphQL API (v4) alongside its REST API (v3) for years rather than forcing migration — coexistence is a feature, not a failure.

### Q26. [Theory] What is over-fetching/under-fetching, and when is REST still the better choice?

Over-fetching is receiving fields you don't need (REST endpoints return fixed payloads); under-fetching is needing multiple requests to assemble one view (the classic REST "waterfall"). GraphQL eliminates both by letting the client specify the exact field set in one request.

But GraphQL is not universally better. REST remains preferable when: the API is **resource-oriented with simple, stable access patterns** (CRUD over a few resources); you need **trivial HTTP caching/CDN** behavior; you serve **file uploads/downloads or binary streams**; you want the **lowest operational complexity** (no resolver/complexity/federation machinery); or consumers are third parties who expect REST conventions and rate limiting per endpoint. GraphQL shines when many heterogeneous clients (web, iOS, Android, partners) need different data shapes from a rich, interconnected graph and you can invest in the server-side controls. The mature answer in 2026 is "use both": GraphQL for client-facing aggregation, REST/gRPC for internal service-to-service and simple resource APIs.

### Q27. [Practical] How do you implement field-level authorization correctly?

Authorization in GraphQL must be **per field**, not per endpoint, because one request can touch many objects across many types. Centralizing auth at a single "before the query" gate fails: the same `User.email` field might be visible to the owner but not to other users in the same response.

Approach: enforce at the **resolver/data layer** using the security context (`@PreAuthorize` in Spring Security integrates with Spring for GraphQL). Prefer authorizing the **business operation** (can this principal read this specific object instance?) rather than only the type, because instance-level rules (ownership, tenancy) are common. Avoid putting auth solely in the gateway when federated — each subgraph must enforce its own field rules since the router can call it directly.

```java
@SchemaMapping(typeName = "User", field = "email")
@PreAuthorize("#user.id == authentication.name or hasRole('ADMIN')")
public String email(User user) { return user.getEmail(); }
```

Production notes: failed authorization on a field should null that field (with an `errors` entry), not abort the whole query, so unrelated public fields still return; never leak existence via differing error messages; and combine with complexity limits so an attacker can't probe authorization by brute-forcing huge queries.

---

## 🔴 Expert (15+ yrs)

### Q28. [Theory] How would you design GraphQL governance and schema evolution across 100+ services and many teams?

At org scale the schema is a shared, long-lived contract, so governance matters more than any single feature. I'd put in place: (1) a **federated supergraph** (Federation v2) so each team owns its subgraph and types, with a central schema registry as the source of truth; (2) **schema checks in CI** that run composition + operation checks against recorded production traffic, failing a PR that would break a live client; (3) **additive evolution by default** — never remove or retype a field in place; add new fields/types and use the `@deprecated` directive with a reason and a sunset date; (4) **field usage analytics** from the registry so you know when a deprecated field has zero traffic and can be safely removed; (5) **naming/lint standards** (consistent connection types, input/payload conventions, nullability rules) enforced automatically.

The hard part is organizational, not technical: you're running an internal API platform, so you need a schema working group, clear ownership boundaries (one type, one owning subgraph), and a deprecation policy with teeth. Versioning is deliberately *not* done via `/v2` URLs — GraphQL evolves the single graph continuously, which only works if removal is disciplined and data-driven.

### Q29. [Theory] Discuss persisted queries deeply — trusted documents, APQ, and their security/operational value.

There are two related mechanisms. **Automatic Persisted Queries (APQ)** are a performance optimization: the client sends a query hash; on a miss the server asks for the full text and registers it; subsequent calls send only the hash. This shrinks request payloads and (via `GET`) restores CDN caching. APQ is *open* — any query can be registered on demand.

**Trusted documents / persisted operations (allowlisting)** are a security control: at build time the client's queries are extracted, hashed, and published to the server as the *only* operations it will accept. At runtime the server executes by hash and **rejects any free-form query**. This collapses the attack surface to a finite, reviewed set — depth/complexity attacks, introspection-driven probing, and arbitrary field access become impossible for first-party clients. The trade-off is operational: you need a build-time extraction step and a publish pipeline coupling client releases to a server-side manifest, and it doesn't fit truly open public APIs where third parties write their own queries. For first-party web/mobile, trusted documents are the single highest-leverage hardening you can apply, and I'd combine them with a small allowance for introspection in dev only.

### Q30. [Coding] Design a request-scoped, multi-key DataLoader that also handles authorization and partial failures.

**Problem:** batch-load `User` by id, enforce that fields are only batched for authorized principals, and return a per-key result (found / forbidden / missing) without failing the whole batch.

```java
public DataLoader<String, Try<User>> userLoader(SecurityContext ctx) {
    BatchLoader<String, Try<User>> batch = ids -> CompletableFuture.supplyAsync(() -> {
        // ONE query for all ids
        Map<String, User> found = repo.findAllById(ids).stream()
            .collect(Collectors.toMap(User::getId, u -> u));

        // Preserve input order; never throw for one bad key
        return ids.stream().map(id -> {
            User u = found.get(id);
            if (u == null) return Try.<User>missing();          // not found -> null field
            if (!ctx.canRead(u)) return Try.<User>forbidden();  // authz fail -> field error
            return Try.found(u);
        }).toList();
    });
    return DataLoaderFactory.newDataLoader(batch,
        DataLoaderOptions.newOptions().setCachingEnabled(true)); // per-request cache
}
```

```java
// Resolver translates the Try into GraphQL semantics
@SchemaMapping(typeName = "Post", field = "author")
public CompletableFuture<User> author(Post post, DataLoader<String, Try<User>> loader) {
    return loader.load(post.getAuthorId()).thenApply(t -> switch (t.kind()) {
        case FOUND     -> t.value();
        case MISSING   -> null;                          // nullable field -> null
        case FORBIDDEN -> throw new AccessDeniedException("author"); // -> errors[]
    });
}
```

**Why this design:** list-based batch loaders must return results **positionally aligned** with input keys, so we map over `ids` rather than over `found`. Wrapping each result in a `Try`/result type means one forbidden or missing key never poisons the batch — exactly the partial-success model GraphQL expects. **Time:** one DB round trip per batch regardless of N. **Space:** O(distinct keys), request-scoped to prevent cross-user cache leakage. **Edge cases:** duplicate ids dedupe via the cache; authorization is evaluated per instance (not per type) so tenant isolation holds; the cache must be per-request, never a shared singleton, or you risk serving one user's data to another.

### Q31. [Behavioral] Two senior engineers disagree: one wants Federation, the other a single monolithic GraphQL schema. How do you drive the decision?

I'd reframe it from "which technology" to "what does our org and traffic actually require," and make the trade-offs explicit rather than letting it become a preference war. A **monolith** schema is simpler operationally (one deploy, no router, no composition step, easier local dev) and is the right call when one team owns the graph and the data is cohesive. **Federation** pays off when multiple autonomous teams need to own their slices independently and ship on different cadences — the cost is a router, a composition/CI pipeline, and cross-subgraph latency.

Concretely I'd: (1) gather data — how many teams will own schema, deploy frequency, current coupling pain; (2) prototype both for one realistic feature to measure the actual router overhead and developer ergonomics; (3) define reversible-decision criteria (we can start monolith and extract subgraphs later, since federation is somewhat additive); (4) have both engineers co-author the decision record so the disagreement becomes shared ownership of trade-offs. The behavioral key is depersonalizing it: the answer is "start simpler (monolith or a modular monolith) until team-ownership pain is real, then federate" — premature federation is a common, expensive mistake, and I'd lead with that data, not authority.

### Q32. [Theory] What advanced execution and resilience patterns matter at scale (defer/stream, batching across services, timeouts)?

Several patterns become important once a graph is large and federated:

- **`@defer` / `@stream`** (incremental delivery): the server returns the fast part of a response immediately and streams slow fields later in the same request. This improves perceived latency for screens with one slow widget — but requires client support and a multipart/SSE transport, and complicates error handling.
- **Per-resolver timeouts and bulkheads**: because one query touches many backends, a slow downstream must not stall the whole response. I'd apply timeouts per data fetch and return a field error (partial response) rather than hanging — GraphQL's partial-success model is a resilience asset here.
- **Cross-service batching and caching at the router**: federation can re-introduce N+1 *across services*; the router/entity fetchers must batch entity lookups, and APQ/`@cacheControl` should drive an edge cache.
- **Backpressure on subscriptions**: reactive streams (`Flux`) with bounded buffers prevent a slow client from exhausting memory.

The unifying theme: at scale GraphQL's flexibility is a liability unless you pair it with static analysis (complexity/depth), incremental delivery for UX, and per-field resilience so the blast radius of any one slow or failing backend is a single null field, not a 500. This is the difference between a demo-grade and a production-grade GraphQL platform.

### Q33. [Practical] Describe a real-world GraphQL architecture you'd defend in a staff-level design review.

Consider an e-commerce platform with web, iOS, Android, and partner clients (the Netflix/Shopify-style case). I'd defend: a **federated supergraph** with subgraphs owned by Catalog, Pricing, Inventory, Reviews, and Identity teams, fronted by an **Apollo Router** (or DGS gateway in a Java shop). Clients use **trusted documents** (persisted operations) so only reviewed queries run; the router enforces **depth + complexity limits** as defense in depth. Each subgraph uses **DataLoaders** internally and the router batches **entity** lookups across subgraphs. Hot, low-churn data (catalog, pricing tiers) carries `@cacheControl` hints and is cached at a GraphQL-aware edge; the client uses Apollo's normalized cache keyed by global IDs. Real-time inventory uses a small set of **subscriptions** backed by a Kafka→WebSocket fan-out with sticky routing, while less-critical "live-ish" data uses polling to keep infra simple.

Observability: per-resolver Micrometer/OpenTelemetry traces, per-operation p99 alerts, and schema-usage analytics from the registry to drive deprecations. The review questions I'd expect — and have answers for — are: how do you stop a malicious query (trusted docs + complexity), how do you cache (edge + normalized client cache + entity batching), how do you evolve safely (registry + CI schema checks + `@deprecated` with usage data), and how do you fail gracefully (per-field timeouts + partial responses). This ties together every prior answer into one coherent, defensible system.

---

## ✅ Key Takeaways

- GraphQL hands response-shape control to the client via a typed schema, eliminating over/under-fetching — at the cost of more server-side responsibility (complexity control, batching, caching).
- The per-field resolver model makes the **N+1 problem** the default failure mode; **DataLoader** batching + per-request caching is the standard fix and a guaranteed interview topic.
- Prefer **Relay cursor (keyset) pagination** over offset/limit for stability under writes and performance at depth.
- GraphQL returns HTTP 200 with a top-level `errors` array; partial success and **non-null null-propagation** are core semantics — use `!` conservatively.
- Security is layered: **depth + complexity limits**, **bounded pagination args**, restricted **introspection** in prod, and **trusted documents/persisted queries** as the strongest control for first-party clients.
- Caching moves off URLs into **APQ + CDN**, **client-side normalized caches** (entity identity), and **GraphQL-aware edge** caches with `@cacheControl`.
- **Federation v2** distributes schema ownership for org-scale graphs; start with a monolith/modular schema and federate only when team-ownership pain is real.
- In Java, **Spring for GraphQL** (schema-first) and **Netflix DGS** (code-first, strong federation) are the dominant choices; both integrate DataLoaders and Spring Security.

## ⚠️ Common Pitfalls

- Writing clean per-association resolvers and shipping N+1 to production because no DataLoader was wired.
- Returning a list-based batch loader's results in the **wrong order** relative to input keys (silent data corruption).
- Marking fields `!` everywhere, so one flaky downstream nulls out a large response subtree via propagation.
- Using offset pagination on mutating data, causing duplicated/skipped rows across pages.
- Leaving introspection open on a public endpoint and treating "hiding the schema" as actual authorization.
- No complexity/depth limits, letting a single deeply nested query become a DoS vector.
- Centralizing authorization at a gateway/before-query gate instead of enforcing it **per field/instance** — especially dangerous under federation.
- Sharing a DataLoader cache across requests, leaking one user's data into another's response.
- Reaching for subscriptions (and their stateful WebSocket infra) where polling or SSE would do.
- Removing or retyping fields in place instead of adding fields and using `@deprecated` with usage analytics.

## 📚 Further Reading

- Marc-André Giroux, *Production Ready GraphQL* — the definitive practitioner book on schema design, pagination, security, and federation.
- Official GraphQL Specification and Learn guides — https://spec.graphql.org and https://graphql.org/learn/
- Spring for GraphQL Reference Documentation — https://docs.spring.io/spring-graphql/reference/
- Apollo Federation docs and the Apollo Router — https://www.apollographql.com/docs/federation/
- Netflix DGS Framework documentation — https://netflix.github.io/dgs/
- DataLoader (graphql/dataloader) and the GraphQL Cursor Connections (Relay) spec — https://relay.dev/graphql/connections.htm
