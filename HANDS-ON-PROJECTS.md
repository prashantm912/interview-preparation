# Hands-On Build Projects (Java) — System Design, DSA & Architecture
**For a 15-yr Java/Spring/Kafka/Postgres/Redis/Azure developer.**

> Reading guides makes you *recognize* answers. Building makes you *own* them. Each project here is scoped to fit alongside the 8-week schedule, uses **your actual stack**, and maps to a guide in this repo. Pick based on your weak spots — you don't need all of them.

**Effort key:** 🟢 2–4 hrs · 🟡 1–2 days · 🟠 3–5 days (spread over a week)

---

## Track A — System Design (build the patterns, don't just sketch them)

These turn `09-system-design/` case studies into running code. The point isn't a finished product — it's *feeling* the trade-off you'll be asked about.

### A1. 🟢 Distributed Rate Limiter (Redis + Spring Boot)
**Maps to:** `design-problems/rate-limiter.md` · `caching-strategies.md`
**Build:** A Spring Boot filter that rate-limits by API key using Redis.
- Implement **token bucket** with a Lua script (atomic check-and-decrement in Redis)
- Add a second algorithm: **sliding window log** with a Redis sorted set (`ZADD`/`ZREMRANGEBYSCORE`)
- Expose `X-RateLimit-Remaining` / `Retry-After` headers
- Load-test with `hey` or `k6`; watch behavior at the boundary

**You'll internalize:** why atomicity matters (race conditions under concurrency), token bucket vs sliding window trade-offs, why distributed rate limiting needs centralized state.
**Interview payoff:** *"I implemented both with Redis Lua scripts; the token bucket allows bursts, the sliding window is smoother but stores every request timestamp."*

### A2. 🟡 Real-Time Chat Backend (WebSocket + Kafka + Postgres)
**Maps to:** `design-problems/chat-system.md` · `event-driven-architecture.md`
**Build:** A multi-room chat service.
- Spring WebSocket (STOMP) for client connections
- Kafka topic per shard for message fan-out across server instances
- Postgres for message persistence + read receipts
- Handle **ordering** (partition by room_id) and **delivery** (consumer group per instance)
- Bonus: presence tracking in Redis (TTL keys)

**You'll internalize:** WebSocket scaling needs a message broker (you can't broadcast across nodes without one), partition-key choice drives ordering guarantees, the read-receipt consistency problem.
**Interview payoff:** You can whiteboard chat *and* say "I built this — the gotcha is cross-instance fan-out."

### A3. 🟡 Notification System with Fan-Out + Retries (Kafka + Spring)
**Maps to:** `design-problems/notification-system.md`
**Build:** A notification service.
- Producer accepts notification requests → Kafka
- Consumer with **idempotency keys** (dedup via Redis SET NX)
- **Dead-letter topic** for poison messages; retry with exponential backoff
- Multi-channel dispatch (email/SMS stubs) via a strategy interface
- Track delivery state in Postgres

**You'll internalize:** at-least-once delivery means you MUST dedup, DLQ design, backoff strategies, fan-out (one event → many recipients).
**Interview payoff:** Idempotency + DLQ are the #1 things senior interviewers probe in messaging.

### A4. 🟠 Typeahead / Autocomplete Service (Trie + Redis + Spring)
**Maps to:** `design-problems/typeahead-autocomplete.md` · DSA tries
**Build:** A prefix-search autocomplete.
- In-memory **Trie** for prefix matching (this is the DSA crossover)
- Top-K ranking per prefix (min-heap), cached in Redis
- Background job updating popularity weights
- Sub-50ms p99 target; measure it

**You'll internalize:** how DSA (trie, heap) underpins a real system, the read-heavy caching pattern, precompute-vs-query trade-off.
**Interview payoff:** The rare candidate who connects "trie" (DSA round) to "autocomplete service" (design round).

### A5. 🟠 Mini URL Shortener / Object Store with Sharding (Postgres + Spring)
**Maps to:** `design-problems/object-storage.md` · `data-layer.md` · `sharding-replication.md`
**Build:** A sharded key-value service.
- Base62 ID generation (or Snowflake-style IDs)
- **Consistent hashing** to route keys across N Postgres shards
- Read replica routing (writes → primary, reads → replica)
- Demonstrate resharding pain (add a shard, watch keys move)

**You'll internalize:** consistent hashing (why not modulo), the read/write split, why resharding is hard.
**Interview payoff:** Sharding is a senior-tier topic; having moved keys between shards yourself is gold.

---

## Track B — DSA (code the patterns under interview conditions)

`08-dsa/` has 19 files of problems. Reading solutions ≠ solving cold. These are *workflow* projects, not single problems.

### B1. 🟢 Personal DSA Solution Repo (with tests + timing)
**Build:** A Maven project where each solved problem is a class + JUnit test.
- Organize by pattern: `sliding_window/`, `two_pointers/`, `graphs/`, `dp/`, `heap/`, `trie/`
- For each: solution + tests + a comment block (approach, time/space complexity, edge cases)
- **Discipline:** solve cold (timer, 30–45 min), THEN check the guide
- Track a spreadsheet: problem, date, solved-unaided?, time taken

**You'll internalize:** pattern recognition through repetition, complexity analysis as a habit, recovering from a blank page under time pressure.
**Plan:** 3–5 problems/week across the 8 weeks = 24–40 problems. Quality over quantity.

### B2. 🟢 Build a Data-Structure Library from Scratch
**Build:** Implement (no library) and unit-test:
- LRU cache (HashMap + doubly-linked list) — *the* classic
- Min-heap / priority queue
- Trie (reuse in A4!)
- Union-Find with path compression
- A thread-safe bounded blocking queue (ties into Java concurrency)

**Maps to:** `08-dsa/` + `01-backend-java/java-concurrency.md`
**You'll internalize:** the internals you usually `import`. Interviewers love "implement LRU cache."

### B3. 🟡 Algorithm Visualizer (optional, if you enjoy frontend)
**Build:** A small Angular app (your stack!) that animates sorting/pathfinding (Dijkstra, BFS/DFS on a grid).
- Spring Boot backend computes steps; Angular renders them
- Crossover project: DSA + your Angular skill

**You'll internalize:** graph traversal deeply (you can't animate what you don't understand), plus a portfolio piece.

---

## Track C — Architecture & Advanced Topics (build the senior-level judgment)

This is where a 15-yr profile wins. These map to `02-microservices/`, `11-additional-topics/`, and `09-system-design/` ops files.

### C1. 🟡 Resilient Microservice Pair (Resilience4j + Spring Cloud)
**Maps to:** `02-microservices/` · `09-system-design/ops-reliability.md`
**Build:** Two Spring Boot services (order → payment) wired with:
- **Circuit breaker, retry, bulkhead, timeout** via Resilience4j
- **Idempotent** payment endpoint (idempotency key)
- Inject failure (latency, 500s) and watch the breaker open/half-open/close
- Expose Resilience4j metrics to Prometheus

**You'll internalize:** the resilience patterns by *seeing them trip*, not reading about them.
**Interview payoff:** *"I watched a circuit breaker move through half-open under partial recovery"* beats any textbook answer.

### C2. 🟡 Saga / Distributed Transaction (Kafka + Spring)
**Maps to:** `02-microservices/` · `event-driven-architecture.md`
**Build:** An order flow across 3 services (order, inventory, payment) using **choreographed saga**:
- Each step emits an event; compensating events on failure
- Force a failure mid-saga, watch the compensation roll back
- Bonus: re-implement as **orchestrated** saga and compare

**You'll internalize:** why 2PC doesn't scale, saga compensation, choreography vs orchestration trade-off.
**Interview payoff:** Distributed transactions are a top staff-level discussion.

### C3. 🟠 Full Observability Stack (Prometheus + Grafana + OpenTelemetry)
**Maps to:** `07-devops-cloud/observability.md` · `prometheus-grafana.md` · `11-additional-topics/observability-patterns.md`
**Build:** Instrument the C1 microservice pair end-to-end.
- Micrometer → **Prometheus** (RED metrics: Rate, Errors, Duration)
- **OpenTelemetry** distributed tracing across both services
- **Grafana** dashboards + alert rules (SLO burn-rate alert)
- Define an SLO, breach it deliberately, watch the alert fire

**You'll internalize:** SLI/SLO/error-budget in practice, the three pillars wired together. You already use Prometheus/Grafana — this makes you *fluent*, not just familiar.
**Interview payoff:** Senior interviewers love "define an SLO and an alert on the error budget." You'll have done it.

### C4. 🟠 Event-Driven CQRS + Event Sourcing slice
**Maps to:** `09-system-design/` · `modern-architecture-2024-2026.md`
**Build:** A small account-ledger service.
- Commands append events to Kafka (event store)
- Materialized read model in Postgres (projection consumer)
- Rebuild the read model by replaying events
- Show eventual consistency between write and read sides

**You'll internalize:** event sourcing, CQRS, projection rebuild, the consistency lag.
**Interview payoff:** This is advanced architecture — having built even a slice signals depth.

### C5. 🟡 Deploy to Azure with Bicep + GitHub Actions (your full DevOps stack)
**Maps to:** `BICEP-INTERVIEW-GUIDE.md` · `07-devops-cloud/azure.md` · `github-actions.md`
**Build:** Take ANY service above and ship it.
- **Bicep** templates: App Service / Container App + Postgres + Redis + Key Vault
- **GitHub Actions** pipeline: build → test → `az deployment group create`
- Secrets from Key Vault, not hardcoded
- Multi-environment (dev/prod) via parameter files

**You'll internalize:** your IaC + CI/CD stack on a real deploy, closing the Bicep gap with hands-on reps.
**Interview payoff:** End-to-end ownership: "I designed it, built it, and shipped it to Azure via Bicep + Actions."

---

## How to Fit These Into the 8-Week Schedule

You don't do all 15. Pick ~5–6 weighted to your gaps. Suggested integration:

| Week | Schedule focus | Add this project |
|---|---|---|
| 1–2 | SD fundamentals | **B1** (start DSA repo, ongoing) + **A1** rate limiter (🟢 weekend) |
| 3 | SD case studies | **A2** chat backend (🟡, aligns with chat-system.md) |
| 4 | SD case studies + reliability | **C1** resilient microservices (🟡) |
| 5 | AI/ML | **RAG-MINI-PROJECT-JAVA** (the LLM build) |
| 6 | AI/ML + patterns | **C3** observability (🟠, uses your Prometheus skills) |
| 7 | Additional topics | **C5** Azure/Bicep deploy (🟡) — ship something from earlier |
| 8 | Polish + mocks | **B2** finish data-structure library; mock interviews |

**Ongoing all 8 weeks:** B1 (3–5 DSA problems/week, cold-solve discipline).

---

## Picking Projects by Goal

| Your situation | Prioritize |
|---|---|
| Targeting product companies (Amazon/Google/Atlassian) | B1 + B2 (DSA), A1, A4 |
| Targeting GCC / enterprise architect roles | C1, C2, C3, C4 |
| Want to prove "current on GenAI" | RAG-MINI-PROJECT-JAVA, then C3 |
| Want one impressive end-to-end story | A2 → C1 → C3 → C5 (build, harden, observe, ship the same system) |
| Shortest path to depth | A1 (🟢), C1 (🟡), RAG-Java — three projects, three rounds covered |

**The power move:** build A2 (chat), harden it with C1 (resilience), instrument it with C3 (observability), ship it with C5 (Azure/Bicep). That's ONE system carried through design → resilience → ops → deploy — a complete senior narrative you can tell in any round.

---

## Repo Layout Suggestion

```
hands-on/
├── dsa/                      # B1, B2 — Maven, JUnit per pattern
├── rate-limiter/             # A1
├── chat-backend/             # A2  ─┐
├── resilient-services/       # C1   ├─ the "one system" narrative
├── observability/            # C3   ┘
├── notification-system/      # A3
├── saga-orders/              # C2
├── cqrs-ledger/              # C4
├── rag-chatbot/              # RAG-MINI-PROJECT-JAVA
└── infra/
    ├── bicep/                # C5
    └── .github/workflows/    # C5
```

Keep these OUT of the interview-prep content repo (or in a separate `hands-on/` folder / separate repo) so they don't pollute the 129-doc structure.

---

## Definition of Done (per project)

A project is "interview-ready" when you can:
1. **Run it** and demo the core behavior in under 5 min
2. **Explain one trade-off** you felt while building (e.g., "token bucket allows bursts")
3. **Name one thing that broke** and how you diagnosed it
4. **Say how you'd scale it 10x** and what would break first

If you can't do all 4, you read more than you built. Go back and break it on purpose.

---

**Build > read. Pick your 5–6, slot them into the 8 weeks, and you'll walk in with stories, not just answers.**
