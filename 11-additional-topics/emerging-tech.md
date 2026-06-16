# Complementary & Emerging Technologies

A staff-engineer-level interview guide to the technologies that orbit a modern Java/JVM core stack — RPC, real-time transport, alternative runtimes, reactive programming, IAM, secrets, workflow orchestration, DB migrations, integration testing, observability, GitOps, streaming, and the AI-adjacent data layer (vector DBs, RAG, feature stores). Current through 2026.

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

### Q1. [Theory] What is gRPC and how does it differ from a traditional REST/JSON API?

gRPC is a high-performance, contract-first RPC framework built on **HTTP/2** that serializes messages using **Protocol Buffers** (a compact binary format) instead of JSON. You define services and messages in a `.proto` file, and a code generator emits strongly-typed client stubs and server skeletons in many languages.

The key differences and the "why":

- **Wire efficiency**: Protobuf binary is far smaller and faster to parse than JSON text, which matters for high-throughput, low-latency internal traffic.
- **HTTP/2 multiplexing**: Many concurrent calls share one TCP connection; gRPC also supports **streaming** (server, client, and bidirectional) which plain REST does not natively offer.
- **Contract-first & typed**: The `.proto` is the source of truth, so client/server stay in sync and breaking changes are caught at compile time.
- **Trade-offs**: gRPC is not browser-native (needs gRPC-Web + a proxy), payloads are not human-readable, and it is heavier to debug with curl. REST/JSON remains the better choice for public, browser-facing, cache-friendly APIs.

Rule of thumb: gRPC for internal service-to-service; REST/JSON for public and browser-facing edges.

### Q2. [Theory] Compare WebSockets, Server-Sent Events (SSE), and long-polling for real-time updates.

All three push data toward a client, but they sit at different points on the simplicity-vs-capability curve.

```
                 Direction      Transport         Reconnect   Browser   Best for
 Long-polling    Server→Client  Repeated HTTP     Manual      Yes       Legacy/fallback
 SSE             Server→Client  1 HTTP stream     Built-in    Yes(*)    Notifications, feeds
 WebSocket       Bi-directional Upgraded TCP      Manual      Yes       Chat, games, collab
```

- **Long-polling**: client sends a request, server holds it open until data is ready (or a timeout), then the client immediately re-requests. Simple and works everywhere, but high overhead per message and tricky ordering.
- **SSE** (`text/event-stream`): a single long-lived HTTP response that streams events server→client only. It auto-reconnects and supports `Last-Event-ID` for resume. Great for dashboards, stock tickers, and notifications. Limitation: unidirectional and (over HTTP/1.1) bound by the ~6 connections-per-domain limit — HTTP/2 fixes that.
- **WebSockets**: a full-duplex channel after an HTTP `Upgrade` handshake. Required when the client also needs to push frequently (chat, multiplayer, collaborative editing). Cost: you manage reconnection, heartbeats, and backpressure yourself.

Pick the **least powerful** option that meets the requirement: SSE before WebSockets if traffic is one-directional.

### Q3. [Theory] What problem do Flyway and Liquibase solve, and how do they differ?

Both are **database schema migration** tools that version-control your DDL so schema changes ship alongside code, are repeatable across environments, and are auditable. Without them, schema drift between dev/stage/prod is a common source of "works on my machine" failures.

- **Flyway**: migrations are plain SQL files (or Java) named like `V2__add_orders_index.sql`. It tracks applied versions in a `flyway_schema_history` table. Philosophy: SQL-first, simple, predictable.
- **Liquibase**: migrations (changesets) can be written in XML/YAML/JSON/SQL. The abstraction enables (limited) database-agnostic DDL and richer features like rollbacks and contexts.

Trade-off: Flyway is simpler and beloved by SQL-comfortable teams; Liquibase offers more abstraction and rollback support at the cost of more ceremony. Both integrate with Spring Boot, Maven/Gradle, and CI pipelines.

### Q4. [Practical] Your Spring Boot service needs a real Postgres and Kafka during integration tests, not H2 mocks. What do you use?

**Testcontainers.** It spins up throwaway Docker containers (Postgres, Kafka, Redis, etc.) for the lifetime of your test, giving you a production-fidelity dependency instead of an in-memory substitute that behaves differently (H2 silently accepts SQL that Postgres rejects).

Approach in production:

1. Add the Testcontainers JUnit 5 extension and the module (`org.testcontainers:postgresql`).
2. Start the container, point Spring's datasource at its dynamic port via `@DynamicPropertySource`.
3. Run real migrations (Flyway) against it so the schema is identical to prod.

```java
@Testcontainers
@SpringBootTest
class OrderRepositoryIT {
    @Container
    static PostgreSQLContainer<?> pg =
        new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", pg::getPassword);
    }
}
```

Trade-offs: requires a Docker-capable CI runner and adds a few seconds of startup. Mitigate with **reusable containers** and Spring Boot 3.1+ `@ServiceConnection`, which wires the container to the datasource automatically.

### Q5. [Theory] What is the ELK/EFK stack and what is each component for?

ELK/EFK is the canonical centralized-logging pipeline:

```
 App logs → [Shipper] → [Buffer/Parse] → [Store/Index] → [Visualize]
            Filebeat     Logstash         Elasticsearch    Kibana   (ELK)
            Fluentd/     Fluentd          Elasticsearch    Kibana   (EFK)
            Fluent Bit
```

- **E**lasticsearch: distributed search/index store for logs.
- **L**ogstash or **F**luentd/Fluent Bit: collect, parse, enrich, and forward logs. Fluent Bit (the "F" in EFK) is lighter and Kubernetes-native, which is why EFK dominates in K8s.
- **K**ibana: query and dashboard UI.

The "why": in distributed systems, grepping logs on individual hosts does not scale; you need a single searchable index with structured fields (request IDs, trace IDs) to correlate events across services.

### Q6. [Theory] What is Keycloak and what does it give you out of the box?

Keycloak is an open-source **Identity and Access Management (IAM)** server. It centralizes authentication so your applications do not each reinvent login. Out of the box it provides:

- **OIDC and SAML 2.0** support (issues JWT access/ID tokens, refresh tokens).
- **Single Sign-On (SSO)** across apps in a realm.
- **Social/identity-provider federation** (Google, GitHub) and **LDAP/AD federation**.
- User management, roles, groups, MFA, and brute-force protection.

The interview point: Keycloak lets your services become **OAuth2 resource servers** that simply validate tokens, removing password handling from your codebase — a big security win.

---

## 🟡 Intermediate (3–7 yrs)

### Q7. [Theory] Compare Quarkus and Micronaut with Spring Boot. When would you reach for them?

All three are JVM application frameworks, but Quarkus and Micronaut were designed for the **cloud-native / serverless era** where fast startup and low memory matter (per-instance density, scale-to-zero, cold starts).

| Dimension | Spring Boot | Quarkus | Micronaut |
|---|---|---|---|
| DI / config resolution | Mostly **runtime** reflection | **Build-time** (annotation processing) | **Build-time** (annotation processing) |
| Startup (JVM) | ~2–4s | ~0.8–1.5s | ~0.8–1.5s |
| Native image | Spring Boot 3 + GraalVM (AOT) | First-class (Quarkus + Mandrel) | First-class |
| Ecosystem maturity | Largest | Strong (Red Hat) | Smaller |
| Programming model | Servlet + WebFlux | RESTEasy Reactive / imperative | Netty-based |

The core insight: Spring historically does heavy reflection and classpath scanning **at runtime**, which is slow to start and hard for GraalVM to compile ahead-of-time. Quarkus and Micronaut shift that work to **build time**, producing smaller, faster apps and clean native images.

When to choose: Quarkus/Micronaut for serverless functions, high-density Kubernetes deployments, or strict cold-start SLAs. Spring Boot when ecosystem breadth, hiring pool, and library availability outweigh startup time. Note: Spring Boot 3 narrowed the gap considerably with AOT processing and GraalVM native support, so the decision is now more nuanced than it was in the Spring Boot 2 era.

### Q8. [Theory] What are Reactive Streams and how does Project Reactor implement them?

**Reactive Streams** is a specification (now part of the JDK as `java.util.concurrent.Flow`) defining four interfaces — `Publisher`, `Subscriber`, `Subscription`, `Processor` — and a contract for **asynchronous, non-blocking data flow with backpressure**. Backpressure is the central idea: a slow consumer can signal `request(n)` to bound how much a fast producer sends, preventing out-of-memory failures.

**Project Reactor** (the engine under Spring WebFlux) implements this spec with two main types:

- `Mono<T>` — 0 or 1 element.
- `Flux<T>` — 0..N elements.

```java
Flux.range(1, 10)
    .map(i -> i * 2)
    .filter(i -> i % 3 == 0)
    .flatMap(i -> callDownstreamService(i)) // async, non-blocking
    .onErrorResume(ex -> Flux.empty())
    .subscribe(System.out::println);
```

The "why": with a small fixed thread pool (event loop), reactive code handles tens of thousands of concurrent connections that would each tie up a thread in the classic blocking model. Trade-off: the code is harder to write, debug, and reason about; stack traces are opaque; and **one accidental blocking call** (`block()`, JDBC) on an event-loop thread can stall everything. Java 21 **virtual threads** now offer much of the scalability benefit with a simpler imperative style, so reactive is increasingly reserved for genuine streaming/backpressure needs.

### Q9. [Practical] You need to store database passwords and API keys for many microservices. Why HashiCorp Vault over environment variables?

Environment variables and config files leak: they show up in `ps`, in crash dumps, in container inspect output, in Git history, and they are static (rotating them means redeploying everything). **Vault** solves this with:

- **Centralized, encrypted secret storage** with fine-grained ACL policies.
- **Dynamic secrets**: Vault generates short-lived DB credentials on demand (e.g., a 1-hour Postgres user), so a leaked credential expires fast.
- **Automatic rotation and leasing** with revocation.
- **Encryption-as-a-service** (Transit engine) so apps encrypt data without holding keys.
- **Audit log** of every secret access.

Production approach: services authenticate to Vault via Kubernetes service-account tokens (the `kubernetes` auth method), receive a short-lived token, and fetch secrets at startup or via the Vault Agent sidecar that auto-renews leases. The security win is **blast-radius reduction**: dynamic, short-TTL secrets mean a compromised pod cannot exfiltrate long-lived master credentials.

### Q10. [Theory] Camunda vs Temporal — what problem do workflow orchestrators solve and how do they differ?

Both manage **long-running, stateful business processes** (order fulfillment, KYC onboarding, payment sagas) where you must survive crashes, retry steps idempotently, handle timeouts, and compensate on failure. Doing this with ad-hoc cron jobs and DB status columns becomes unmaintainable.

```
 Camunda (BPMN-centric)              Temporal (code-centric)
 ┌───────────────────────┐          ┌───────────────────────┐
 │ Visual BPMN diagram    │          │ Workflow = your code   │
 │ Process engine + DB    │          │ Durable execution via  │
 │ External task workers  │          │ event-sourced history  │
 │ Business-analyst friendly│        │ Engineer friendly      │
 └───────────────────────┘          └───────────────────────┘
```

- **Camunda**: model the process as a **BPMN diagram** that business analysts can read; the engine drives the flow and dispatches work to external workers. Strong for human-in-the-loop and audited business processes.
- **Temporal** (and predecessor Cadence): you write workflows as **ordinary code**; Temporal makes execution **durable** by replaying an event-sourced history after any crash. The programming model is powerful for engineers — retries, timers, and saga compensation become language constructs. Workflow code must be **deterministic** (no direct I/O, no `System.currentTimeMillis()`), pushing side effects into "activities."

Choose Camunda when the process is BPMN-shaped and business-owned; Temporal when engineers want durable execution expressed directly in code.

### Q11. [Coding] Implement an idempotent Temporal-style activity with safe retries in Java.

**Problem**: A payment "charge" activity may be retried by the orchestrator after a network failure. Charging twice is unacceptable. Make it idempotent using an idempotency key.

```java
public class PaymentActivityImpl implements PaymentActivity {

    private final PaymentGateway gateway;
    private final ProcessedRepo processed; // dedup store (DB/Redis)

    public PaymentActivityImpl(PaymentGateway g, ProcessedRepo p) {
        this.gateway = g;
        this.processed = p;
    }

    @Override
    public ChargeResult charge(String idempotencyKey, Money amount) {
        // 1. Fast path: already done? return the stored result.
        ChargeResult prior = processed.find(idempotencyKey);
        if (prior != null) {
            return prior; // safe to replay
        }

        // 2. Pass the key to the gateway so the *gateway* also dedups.
        ChargeResult result = gateway.charge(idempotencyKey, amount);

        // 3. Persist result keyed by idempotencyKey atomically.
        processed.saveIfAbsent(idempotencyKey, result);
        return result;
    }
}
```

**Why it works**: the same `idempotencyKey` (e.g., `orderId + "-charge"`) is generated deterministically by the workflow, so every retry presents the identical key. Both the local dedup store and the downstream gateway treat repeats as no-ops.

**Edge cases**:
- Crash *after* gateway charge but *before* `saveIfAbsent`: the gateway's own idempotency catches the replay (hence step 2 is essential — never rely on local state alone).
- Concurrent retries: `saveIfAbsent` must be atomic (DB unique constraint or `SETNX`).

**Time**: O(1) per call (one lookup + one write). **Space**: O(N) for N distinct keys; add a TTL to bound growth.

### Q12. [Coding] Define a gRPC service in Protocol Buffers and implement the server in Java.

**Problem**: Expose a `getUser` RPC and a server-streaming `listUsers` RPC.

```protobuf
syntax = "proto3";
package user.v1;
option java_multiple_files = true;
option java_package = "com.example.user.v1";

message GetUserRequest { int64 id = 1; }
message User { int64 id = 1; string name = 2; string email = 3; }
message ListUsersRequest { int32 page_size = 1; }

service UserService {
  rpc GetUser (GetUserRequest) returns (User);
  rpc ListUsers (ListUsersRequest) returns (stream User); // server streaming
}
```

```java
public class UserServiceImpl extends UserServiceGrpc.UserServiceImplBase {

    private final UserRepo repo;
    public UserServiceImpl(UserRepo repo) { this.repo = repo; }

    @Override
    public void getUser(GetUserRequest req, StreamObserver<User> obs) {
        var u = repo.findById(req.getId());
        if (u == null) {
            obs.onError(Status.NOT_FOUND
                .withDescription("user " + req.getId())
                .asRuntimeException());
            return;
        }
        obs.onNext(User.newBuilder()
            .setId(u.id()).setName(u.name()).setEmail(u.email()).build());
        obs.onCompleted();
    }

    @Override
    public void listUsers(ListUsersRequest req, StreamObserver<User> obs) {
        repo.findPage(req.getPageSize()).forEach(u ->
            obs.onNext(User.newBuilder()
                .setId(u.id()).setName(u.name()).build()));
        obs.onCompleted(); // signals end of stream
    }
}
```

**Key points**:
- **Field numbers are the contract**, not field names — never reuse or renumber them; that is what makes Protobuf forward/backward compatible.
- Map errors to gRPC `Status` codes (`NOT_FOUND`, `INVALID_ARGUMENT`), not generic exceptions.
- Server streaming calls `onNext` many times then exactly one `onCompleted`.

**Time/Space**: `getUser` O(1); `listUsers` O(pageSize) time and O(1) extra space (streamed, not buffered) — the streaming win is bounded memory regardless of result size.

### Q13. [Practical] How would you do zero-downtime schema changes with Flyway in a CI/CD pipeline?

The danger: a migration that renames or drops a column breaks the **currently running** old version of the app during a rolling deploy, because old and new code run simultaneously.

Use the **expand–contract (parallel change)** pattern across multiple deploys:

```
Deploy 1 (Expand):  Add new nullable column / new table. Old & new code both work.
Deploy 2 (Migrate): Backfill data; new code writes both old+new, reads new.
Deploy 3 (Contract): Drop the old column once no code references it.
```

In production:
- Make migrations **additive and backward-compatible** within a single deploy.
- Run Flyway as a **separate pipeline step / init job**, not lazily on app startup, so 20 pods do not race to migrate. Flyway takes a lock, but a dedicated job is cleaner and lets the deploy gate on migration success.
- Avoid long-locking DDL on big tables in Postgres (use `CREATE INDEX CONCURRENTLY`, add columns without volatile defaults).
- Keep migrations **immutable** once merged — never edit an applied `V__` file; add a new one. Flyway checksums applied scripts and will fail if history is tampered with.

### Q14. [Theory] What is GitOps, and how do ArgoCD and Flux implement it?

**GitOps** makes a Git repository the single source of truth for declarative infrastructure and app config. A controller continuously **reconciles** the live cluster state toward the Git-declared desired state. Benefits: auditable history, easy rollback (`git revert`), no `kubectl apply` from laptops, and drift detection.

```
 Developer → git push (manifests) → Git repo (desired state)
                                        │  watched by
                                        ▼
                      ArgoCD / Flux controller in cluster
                                        │ reconcile loop
                                        ▼
                          Kubernetes (actual state) ── drift? ── correct
```

- **ArgoCD**: pull-based, with a strong **UI** showing sync status, diffs, and health per application. Popular for its visibility.
- **Flux**: pull-based, **CLI/CRD-centric**, lightweight, composes well with Kustomize/Helm and image-automation controllers.

The security advantage: CI no longer needs cluster credentials — the in-cluster agent pulls from Git, so the attack surface of leaked CI tokens shrinks dramatically.

### Q15. [Practical] Kafka is your current event backbone but the team is evaluating Apache Pulsar. What are the trade-offs?

Both are distributed pub/sub log systems, but their architecture differs in ways that matter operationally.

```
 Kafka                                Pulsar
 Broker = compute + storage           Broker (compute) ── separated ── BookKeeper (storage)
 Scale = add brokers + rebalance      Scale compute and storage independently
 Partitions own data                  Segment-centric; faster rebalancing
 Mature ecosystem, Connect, Streams   Built-in multi-tenancy, geo-replication, tiered storage
                                       Native queueing + streaming models
```

- **Kafka** advantages: the most mature ecosystem (Kafka Connect, Kafka Streams, ksqlDB), enormous talent pool, battle-tested at scale. KRaft mode removed the ZooKeeper dependency.
- **Pulsar** advantages: **compute/storage separation** (via Apache BookKeeper) makes scaling and rebalancing cheaper; **first-class multi-tenancy**, **geo-replication**, and **tiered storage** are built in; supports both streaming and traditional queue semantics natively.

What I'd actually do: unless you have a concrete pain Kafka cannot solve (true multi-tenancy at scale, frequent elastic scaling, mixed queue+stream workloads), **stay on Kafka** — the ecosystem and operational maturity usually dominate. Migrate to Pulsar only when those specific advantages map to real requirements, because the operational learning curve and smaller community are real costs.

### Q16. [Coding] Implement an SSE endpoint in Spring (WebFlux) that streams events with backpressure.

**Problem**: Stream price ticks to a browser without overwhelming a slow client.

```java
@RestController
public class PriceController {

    @GetMapping(value = "/prices/{symbol}",
                produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<PriceTick>> stream(@PathVariable String symbol) {
        return priceFeed(symbol)
            .map(tick -> ServerSentEvent.<PriceTick>builder()
                .id(String.valueOf(tick.seq())) // enables Last-Event-ID resume
                .event("price")
                .data(tick)
                .build())
            .onBackpressureLatest()             // drop stale ticks if client lags
            .doOnCancel(() -> log.info("client disconnected: {}", symbol));
    }

    private Flux<PriceTick> priceFeed(String symbol) {
        return Flux.interval(Duration.ofMillis(200))
                   .map(i -> new PriceTick(symbol, randomPrice(), i));
    }
}
```

**Why SSE here**: data flows one way (server→client), the browser's `EventSource` auto-reconnects, and `Last-Event-ID` lets the client resume after a drop. No need for WebSocket complexity.

**Backpressure handling**: `onBackpressureLatest()` keeps only the newest tick for a slow consumer — correct for prices, where stale data is worthless. For an audit feed you would instead **buffer with a bounded queue and error/drop** rather than silently lose events.

**Edge cases**: client disconnect (handled via `doOnCancel`), reconnect with `Last-Event-ID` header (read it to replay from `seq`), and HTTP/1.1 connection limits (serve over HTTP/2).

### Q17. [Theory] What is a vector database and how does RAG use it? Sketch the basic flow.

A **vector database** (pgvector — a Postgres extension, Pinecone, Weaviate, Milvus, Qdrant) stores high-dimensional **embeddings** and supports fast **approximate nearest-neighbor (ANN)** search (HNSW or IVF indexes) by cosine/dot/Euclidean similarity. Embeddings are numeric vectors that capture semantic meaning, so "car" and "automobile" land near each other.

**RAG (Retrieval-Augmented Generation)** grounds an LLM in your private data to reduce hallucination and avoid stuffing everything into the prompt:

```
 Ingest (offline):
   docs → chunk → embed → store vectors + metadata in vector DB

 Query (online):
   question → embed → ANN search top-k chunks → build prompt
            → LLM(prompt + retrieved context) → grounded answer
```

The "why": LLMs have a fixed knowledge cutoff and limited context windows. RAG retrieves only the *relevant* chunks at query time, keeping answers current, cheaper (fewer tokens), and **citable**. Interview nuances: chunking strategy and overlap, embedding model choice (must match query and document embedder), `top_k` and a similarity threshold to avoid feeding irrelevant context, and re-ranking for precision.

---

## 🟠 Advanced (8–12 yrs)

### Q18. [Practical] Design a production RAG pipeline for an enterprise knowledge base. What are the failure modes?

Architecture:

```
            ┌──────────── Ingestion (batch/stream) ────────────┐
 Sources →  │ Loader → Cleaner → Chunker → Embedder → Upsert    │ → Vector DB (pgvector/Pinecone)
            └───────────────────────────────────────────────────┘            │ metadata: source, acl, version
 User → Auth → Query rewrite → Embed → ANN search (filtered by ACL)
            → Re-rank → Prompt assembly (with citations) → LLM → Guardrails → Answer + sources
```

Key production decisions:
- **Chunking**: ~300–800 tokens with overlap; respect document structure (headings) — bad chunking is the #1 cause of poor retrieval.
- **Metadata filtering / ACLs**: never return a chunk the user is not authorized to see. Store `tenant_id`/`acl` in metadata and filter at query time. This is a **critical security control** — RAG can otherwise leak confidential documents into answers.
- **Freshness**: incremental re-embedding on document change; track `version` and soft-delete stale vectors.
- **Re-ranking**: a cross-encoder over the top-50 ANN hits, then take top-5, improves precision.
- **Evaluation**: faithfulness, answer relevance, context precision/recall (RAGAS-style).

Failure modes: stale index after source edits; embedding-model mismatch between ingest and query; retrieval returning plausible-but-wrong chunks (hallucinated grounding); prompt-injection in retrieved documents ("ignore previous instructions"); cost/latency blowups from large `top_k`. Mitigate with strict ACL filtering, output guardrails, citation enforcement, and regression eval sets.

### Q19. [Theory] What is a feature store and what problem does it solve in ML systems?

A **feature store** (Feast, Tecton, Databricks Feature Store) is a centralized system for defining, computing, storing, and serving ML **features** consistently for both training and inference.

The core problem it solves is **training/serving skew**: if a feature like `avg_purchase_last_30d` is computed one way in a batch training pipeline (Spark over a warehouse) and another way in the live request path (a microservice), the model sees different distributions in production than in training and silently degrades.

```
                    ┌─────────────────────────┐
 Raw data → ETL →   │  Feature Store          │
                    │  Offline store (warehouse)│ → training (point-in-time correct)
                    │  Online store (Redis/DDB) │ → low-latency inference
                    │  Registry (definitions)   │ → discoverability/reuse
                    └─────────────────────────┘
```

Key capabilities: **point-in-time-correct joins** (no label leakage from the future), a single feature definition reused across teams (governance + reuse), low-latency online serving, and lineage. Interview point: the offline/online split with a shared definition is what guarantees the same logic produces training and serving features.

### Q20. [Practical] A reactive WebFlux service is mysteriously slow under load. How do you diagnose it?

Reactive performance bugs are usually **a blocking call on the event loop** or **misconfigured schedulers**. Methodically:

1. **Find blocking calls**: add **BlockHound** in tests/staging — it throws when blocking I/O (JDBC, `File`, `Thread.sleep`, `.block()`) runs on a non-blocking thread. This is the single most common WebFlux footgun: one synchronous JDBC call stalls the whole event loop and tail latency explodes.
2. **Check scheduler usage**: CPU-bound work belongs on `Schedulers.parallel()`; unavoidable blocking work must be isolated on `Schedulers.boundedElastic()`. Mixing them starves the loop.
3. **Inspect backpressure**: an unbounded buffer can OOM; `flatMap` with default concurrency (256) can swamp a downstream — set `concurrency` explicitly.
4. **Tracing & metrics**: Reactor's `Hooks.onOperatorDebug()` (dev only — expensive) or `checkpoint()` for readable stack traces; Micrometer + a tracer (OpenTelemetry) to see where time goes.
5. **Connection pools**: a too-small reactive DB (R2DBC) pool or HTTP client pool serializes requests.

What I'd actually do in production: ship Micrometer metrics + distributed tracing from day one, run BlockHound in CI, and treat any `.block()` in request paths as a code-review blocker. Increasingly I'd also ask whether **virtual threads (Java 21+)** would let us drop reactive entirely for this service and regain debuggability.

### Q21. [Coding] Implement HNSW-style top-k cosine similarity search (brute-force baseline → optimization).

**Problem**: Given a query embedding and N stored embeddings, return the k most similar by cosine similarity.

**Approach 1 — brute force (correct baseline, what a small pgvector flat scan does):**

```java
record Hit(String id, double score) {}

List<Hit> topKBruteForce(float[] query, Map<String, float[]> store, int k) {
    PriorityQueue<Hit> heap =
        new PriorityQueue<>(Comparator.comparingDouble(Hit::score)); // min-heap
    for (var e : store.entrySet()) {
        double s = cosine(query, e.getValue());
        if (heap.size() < k) {
            heap.offer(new Hit(e.getKey(), s));
        } else if (s > heap.peek().score()) {
            heap.poll();
            heap.offer(new Hit(e.getKey(), s));
        }
    }
    List<Hit> out = new ArrayList<>(heap);
    out.sort(Comparator.comparingDouble(Hit::score).reversed());
    return out;
}

double cosine(float[] a, float[] b) {
    double dot = 0, na = 0, nb = 0;
    for (int i = 0; i < a.length; i++) {
        dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i];
    }
    return dot / (Math.sqrt(na) * Math.sqrt(nb) + 1e-12);
}
```

**Time**: O(N·d) to score + O(N·log k) for the heap, where d = dimension. **Space**: O(k). Exact but linear in N — fine for thousands, not millions.

**Approach 2 — why production uses ANN (HNSW)**: A flat scan is O(N) per query. **HNSW** (Hierarchical Navigable Small World) builds a multi-layer proximity graph and greedily navigates it, giving ~**O(log N)** query time at the cost of an approximate result and higher memory/build time. That is exactly what pgvector's `hnsw` index, Pinecone, and Qdrant use under the hood. Tuning knobs: `m` (graph degree), `ef_construction` (build quality), `ef_search` (recall vs latency). **Trade-off**: HNSW trades exactness and RAM for sub-linear latency — the right choice above ~1M vectors.

**Edge cases**: zero vectors (the `1e-12` guards divide-by-zero), `k > N` (return all), and **normalize once** if you store unit vectors so cosine reduces to a dot product.

### Q22. [Practical] How do you secure a fleet of microservices with Keycloak and Vault together?

These solve orthogonal problems: Keycloak handles **who is calling** (identity/authZ for users and services); Vault handles **what secrets the service may use** (DB creds, keys). A layered design:

```
 User → Keycloak (OIDC) → JWT ──┐
                                 ▼
                        API Gateway (validates JWT, checks scopes/roles)
                                 │ propagates token
                                 ▼
                        Service A ── needs DB ── Vault (k8s auth) → dynamic short-TTL creds
                                 │ service-to-service
                                 ▼ (mTLS, or service-account JWT)
                        Service B (resource server: validates token, audience-checks)
```

Production specifics:
- Each service is an **OAuth2 resource server**: validate the JWT signature against Keycloak's JWKS, check `aud`, `iss`, expiry, and required roles/scopes. Cache JWKS and rotate keys gracefully.
- **Service-to-service**: client-credentials grant (service accounts) or mTLS via a service mesh; never share user tokens blindly downstream — use token exchange to scope down.
- **Secrets**: services authenticate to Vault using their Kubernetes service-account token (Vault `kubernetes` auth), get short-TTL dynamic DB creds, and a Vault Agent sidecar renews leases. No secret ever sits in env vars or images.
- **Defense in depth**: short token TTLs + refresh, least-privilege Vault policies, audit logs on both, and network policies. The win is that compromising one pod yields only short-lived, narrowly-scoped credentials.

### Q23. [Theory] When should you choose virtual threads (Java 21) over Project Reactor for high concurrency?

Both target the same problem: serving many concurrent requests without a thread-per-request blowup. They get there differently.

- **Virtual threads** (Project Loom, GA in Java 21): the JVM schedules millions of lightweight threads onto a few OS carrier threads, **unmounting** a virtual thread when it blocks on I/O. You write **simple, imperative, blocking-style code** that scales — and you get readable stack traces and normal debugging. Caveat: blocking inside a `synchronized` block can **pin** the carrier thread (largely addressed in later JDKs that make more blocking ops cooperative).
- **Project Reactor**: gives you true **backpressure** and rich **stream composition** (windowing, merging, rate-limiting) that virtual threads do not provide. But the code is harder and stack traces are painful.

Decision: for the common case of "make a lot of blocking calls concurrently" (REST → DB → REST), **prefer virtual threads** in greenfield Java 21+ services — far simpler. Reach for **Reactor** when you genuinely need streaming semantics and backpressure (event processing, SSE fan-out, rate-limited pipelines). This is a notable shift from the Spring Boot 2 / Java 8–11 era when reactive was the only scalable option.

### Q24. [Practical] Your Testcontainers integration tests take 8 minutes in CI and flake intermittently. How do you fix both?

**Speed:**
- **Reuse containers** across test classes: a static singleton container (or Testcontainers `withReuse(true)` + `testcontainers.reuse.enable=true`) rather than per-class spin-up.
- **Share one container per JVM** and reset state between tests (truncate tables) instead of restarting.
- Use **slim images** (`postgres:16-alpine`), pre-pull images in CI, and cache the Docker layer.
- Parallelize test classes that use independent schemas/databases.

**Flakiness:**
- Replace fixed `Thread.sleep` with proper **wait strategies** (`Wait.forLogMessage`, `Wait.forHealthcheck`, `forListeningPort`) — flaky tests almost always come from "container not ready yet."
- Ensure **dynamic ports** (`@DynamicPropertySource`) — hardcoded ports collide under parallel CI.
- Pin image tags (never `latest`) so the dependency does not change under you.
- Give the CI runner enough CPU/RAM; container OOM/throttling manifests as random timeouts.

What I'd actually do: a singleton Postgres + Kafka started once per build, schema-per-test isolation, explicit wait strategies, pinned alpine images, and a Docker layer cache. That typically cuts an 8-minute suite to ~2 minutes and removes the readiness-race flakiness.

---

## 🔴 Expert (15+ yrs)

### Q25. [Behavioral] Your org runs Spring Boot but a team wants to adopt Quarkus + GraalVM native for serverless. How do you lead this decision?

I treat it as a **reversible-vs-irreversible-door** decision and de-risk with data, not opinion. Steps:

1. **Anchor on the requirement**: is the driver a real SLA (cold-start latency, per-instance cost at scale) or résumé-driven development? I ask for the metric we are trying to move.
2. **Spike, don't debate**: time-box a proof of concept — one real service ported to Quarkus native — and measure cold start, memory, build time, and throughput against the Spring Boot baseline (which, in Boot 3 with AOT, may already be close enough).
3. **Surface the hidden costs**: GraalVM native limits reflection and dynamic proxies (needs reachability metadata), longer/heavier native builds, harder debugging, and a smaller library ecosystem. Quantify the operational burden, not just the runtime win.
4. **Contain blast radius**: pilot on one non-critical, high-scale workload; keep the rest on Spring. Avoid a big-bang migration.
5. **Decide and document**: write an ADR with the trade-offs so the choice is auditable and future engineers understand the "why."

The leadership point: I align the team on the **goal**, let evidence settle the **tool**, and keep the change reversible. I have seen teams chase native images for cold starts they did not actually have — and I have seen native genuinely cut serverless bills by half. Data decides.

### Q26. [Theory] Design durable, exactly-once-effective workflows across Temporal, Kafka, and external APIs. What guarantees are actually achievable?

True end-to-end "exactly once" is impossible across independent systems; what you engineer is **effectively-once via idempotency + durable state**. Layered design:

```
 Kafka (at-least-once delivery) → Consumer (Temporal worker) → Workflow (durable, event-sourced)
        │                              │ dedup by event key       │ deterministic; replays safely
        ▼                              ▼                           ▼
   offsets committed             Activity (idempotency key) → External API (idempotent endpoint)
   after workflow ack            outbox/transactional log
```

Guarantees by layer:
- **Kafka** gives at-least-once (or, with the transactional producer + read-process-write, exactly-once *within Kafka* — but that does not extend to external side effects).
- **Temporal** provides **durable execution**: a crash mid-workflow resumes from event history. Workflow code must be deterministic; all I/O lives in **activities**, which Temporal retries — so activities must be **idempotent**.
- **External APIs**: pass an **idempotency key** so retries are no-ops; if the API is not idempotent, wrap it with a dedup table (the transactional **outbox** pattern) to avoid dual-write inconsistency.

The expert framing: name the dual-write problem, push side effects to idempotent activities, use the outbox to make "update DB + emit event" atomic, and accept that "exactly once" is a property of the *effect*, not the *delivery*.

### Q27. [Practical] Define a platform observability strategy unifying logs (EFK), metrics, and traces. What's the modern direction?

A mature strategy treats the **three pillars** as one correlated system, not three silos:

```
 Apps (OpenTelemetry SDK) ─┬─ logs   → EFK / Loki
                           ├─ metrics→ Prometheus → Grafana
                           └─ traces → Tempo / Jaeger
        all carry a shared trace_id ──► click a slow trace → jump to its logs & metrics
 Collected via OpenTelemetry Collector (vendor-neutral pipeline)
```

Principles I'd enforce:
- **OpenTelemetry as the standard** for instrumentation and wire format — it decouples apps from any single backend (avoids vendor lock-in) and is the clear industry convergence point as of 2026.
- **Correlation by trace_id** injected into logs (MDC), metrics exemplars, and spans, so an engineer pivots from a latency spike to the exact request's logs in one click.
- **Structured logging** (JSON) with consistent fields; logs are the most expensive pillar, so sample/retain by tier.
- **Cardinality discipline** on metrics — unbounded labels (user IDs) destroy Prometheus. Use exemplars to bridge to traces instead.
- **SLOs and error budgets** over raw dashboards; alert on symptoms (latency, error rate) not causes.

The modern direction: OpenTelemetry everywhere, traces as the backbone, and managed/columnar backends (Loki, Tempo, Grafana, or a unified observability vendor) replacing hand-rolled ELK clusters for cost and operability.

### Q28. [Behavioral] You inherit a system with six different "emerging tech" choices (Quarkus, Temporal, Pulsar, Pinecone, ArgoCD, Vault) and a small team. How do you handle the complexity?

This is a **cognitive-load and operational-maturity** problem more than a technology problem. My approach:

1. **Inventory and justify**: for each technology, ask "what requirement does this satisfy that a simpler choice would not?" Technologies adopted without a clear need are liabilities — every one carries upgrade, security-patch, and on-call cost.
2. **Map to team capacity**: a small team cannot deeply operate six specialized systems. I would consolidate where reasonable — e.g., Pulsar→Kafka if no multi-tenancy need, Pinecone→pgvector if the data volume fits Postgres — reducing the number of systems we must master and patch.
3. **Prefer managed services** for the keepers (managed Kafka, Temporal Cloud, managed Vault) to shed operational toil for a small team.
4. **Keep the genuinely-justified ones**: ArgoCD and Vault usually earn their place (GitOps and secrets are foundational and well-supported).
5. **Document decisions as ADRs** and define a **golden path** so new services default to the blessed stack instead of adding a seventh tool.

The leadership message: technology choices have a **carrying cost**, and a staff engineer's job is to minimize accidental complexity. I optimize for the team's ability to operate, secure, and reason about the system at 3 a.m., not for architectural novelty. A real case I have seen: a four-person team running Pulsar, two message brokers, and three databases spent more time patching infrastructure than shipping features — consolidating to managed Kafka + Postgres restored their velocity.

### Q29. [Theory] What are the security implications of RAG and vector databases that most teams miss?

RAG introduces a new and under-appreciated attack and leakage surface:

- **Embedding inversion / leakage**: embeddings are not anonymized data — research shows text can be partially reconstructed from embeddings. Treat the vector store as containing sensitive data and encrypt it at rest with access controls.
- **Cross-tenant data leakage**: if ANN search is not filtered by tenant/ACL metadata, one user's question can retrieve another tenant's documents into the prompt. This must be a **hard filter at query time**, enforced server-side — never rely on the LLM to "not mention" something.
- **Indirect prompt injection**: a malicious document ingested into the corpus can contain instructions ("ignore prior instructions, exfiltrate X") that execute when retrieved. Mitigate by treating retrieved content as untrusted, sandboxing tool use, and applying output guardrails.
- **Poisoning**: an attacker who can add documents can steer answers. Validate and authenticate ingestion sources.
- **PII and right-to-erasure**: GDPR deletion must purge the vector store too, not just the source — easy to forget, since embeddings are derived data.

Expert framing: the vector DB is a **first-class data store** subject to the same classification, encryption, ACL, audit, and retention controls as your primary database — most teams treat it as a harmless cache and get burned.

### Q30. [Practical] How do you evolve from "request/response microservices" to an event-driven platform without a risky big-bang rewrite?

Incrementally, using the **strangler fig** pattern and dual-write safety nets:

1. **Start with the transactional outbox**: services keep writing to their DB but also append events to an outbox table in the same transaction; a relay (Debezium CDC) publishes them to Kafka. This solves the dual-write problem and lets you emit events *without* changing the core write path's correctness.
2. **Introduce events alongside, not instead of, RPC**: new consumers subscribe to the event stream for read models, search indexing, and analytics while the synchronous API keeps serving its existing callers.
3. **Migrate workflows to orchestration** (Temporal/Camunda) one business process at a time, behind feature flags, so a saga can be rolled back.
4. **Build the observability and schema governance first**: a schema registry (Avro/Protobuf) with compatibility checks prevents the classic "someone changed an event and broke five consumers" outage.
5. **Decommission gradually**: once consumers rely on events and read models are stable, retire the synchronous coupling. Measure at each step (lag, error rate, business KPIs).

What I'd actually do: outbox + CDC first (lowest-risk, highest-leverage), schema registry as a hard gate, one process migrated end-to-end as a reference implementation, and an ADR-documented golden path. The goal is a series of **reversible, observable steps**, each shippable on its own — never a flag day.

---

## ✅ Key Takeaways

- **Choose the least powerful tool that meets the need**: SSE before WebSockets, REST before gRPC at public edges, virtual threads before reactive for plain concurrency, Postgres/pgvector before a dedicated vector DB until scale demands otherwise.
- **gRPC + Protobuf** win for internal, high-throughput, contract-first traffic; field numbers — not names — are the compatibility contract.
- **Quarkus/Micronaut** shift DI/config to build time for fast startup and clean native images; Spring Boot 3 narrowed the gap with AOT + GraalVM.
- **Reactive Streams** are about non-blocking flow *with backpressure*; Java 21 virtual threads now cover most "scale blocking I/O" cases more simply.
- **Keycloak (who you are) and Vault (what secrets you may use)** are orthogonal; together they shrink blast radius via short-lived tokens and dynamic secrets.
- **Temporal vs Camunda**: code-centric durable execution vs BPMN-modeled business processes. "Exactly once" across systems is achieved as *effectively once* via idempotency + outbox.
- **Flyway/Liquibase + expand–contract** enable zero-downtime schema changes; **Testcontainers** gives production-fidelity integration tests.
- **GitOps (ArgoCD/Flux)** makes Git the source of truth and removes cluster creds from CI.
- **Pulsar** beats Kafka only for specific needs (multi-tenancy, elastic scale, geo-replication); otherwise Kafka's ecosystem dominates.
- **RAG** grounds LLMs via vector retrieval; treat the vector DB as a sensitive, ACL-controlled data store. **Feature stores** kill training/serving skew.
- **OpenTelemetry** is the convergence point unifying logs/metrics/traces; correlate everything by `trace_id`.

## ⚠️ Common Pitfalls

- Reusing or renumbering Protobuf field numbers — silently breaks wire compatibility.
- A single blocking call (JDBC, `.block()`) on a WebFlux event loop, tanking tail latency; not running **BlockHound** to catch it.
- Editing an already-applied Flyway migration instead of adding a new one (checksum failure), or running migrations lazily on every pod's startup instead of a dedicated job.
- Dropping/renaming columns in one deploy instead of expand–contract — breaks the still-running old version during a rolling release.
- Storing secrets in env vars/images instead of Vault dynamic secrets; long-lived static credentials with no rotation.
- Putting non-deterministic code or direct I/O inside a Temporal workflow (must live in idempotent activities).
- RAG without server-side ACL/tenant filtering on ANN search — cross-tenant data leakage; trusting the LLM to "not reveal" instead of filtering.
- Treating the vector DB as a harmless cache (ignoring encryption, PII erasure, prompt-injection from poisoned documents).
- Unbounded metric cardinality (user IDs as labels) crushing Prometheus.
- Adopting Pulsar/Quarkus/native/Temporal for novelty rather than a measured requirement, multiplying the small team's operational and patching load.
- Testcontainers flakiness from fixed `sleep` instead of proper wait strategies, and slowness from per-class container startup instead of reuse.

## 📚 Further Reading

- *gRPC: Up and Running* — Kasun Indrasiri & Danesh Kuruppu (O'Reilly).
- *Designing Data-Intensive Applications* — Martin Kleppmann (event logs, consistency, dual-write, exactly-once nuances).
- *Cloud Native Patterns* and the official **Quarkus** / **Micronaut** guides; Spring Framework reference for **WebFlux & Reactor**.
- Temporal documentation (durable execution, determinism) and Camunda 8 BPMN docs.
- HashiCorp **Vault** docs (dynamic secrets, Kubernetes auth) and **Keycloak** server admin guide.
- **OpenTelemetry** documentation, the **pgvector** README, and the **RAGAS** evaluation framework for RAG quality.
