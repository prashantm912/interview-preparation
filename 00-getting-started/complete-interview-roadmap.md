# The Engineer's Interview Almanac — Complete Study Doc

> A single, opinionated study system for cracking software-engineering interviews, synthesized from 13 X/Twitter threads (Feb–Jul 2026) by practicing engineers — including a Principal Engineer at Atlassian — and enhanced with supplementary reference material. Covers DSA, backend, API design, system design, language depth, AI/agentic engineering, and full-stack web.
> **Goal: learn deeply enough to explain, not just recite** — that is what interviews test.
>
> **📄 A polished, interactive HTML edition of this guide ships alongside it: [`interview-prep-guide.html`](interview-prep-guide.html)** — open it in any browser for the navigable, progress-tracking version (light/dark, print-to-PDF). This markdown file is the full-text source of record.
>
> **Structure:**
> - **Part I — Prep Roadmap** (phases 0–6, 12-week schedule, question bank) — your plan and the breadth map.
> - **Part II — System Design Deep-Dive** (the full 25-day scaling series) — the depth for Part I's Phase 3; the single richest source here.
> - **Part III — Supplementary Reference** (added to complete the roadmap) — DSA pattern catalog, Big-O tables, SQL essentials, the system-design answer template + case studies, behavioral/STAR, interview-day tactics, resume, and a glossary.
>
> Whenever Part I references "the 25-day series", it means **Part II below**.

---

# PART I — PREP ROADMAP

## How to use this guide

1. **Follow the phases in order.** Each builds on the previous one.
2. **For every topic, prepare 3 layers:**
   - **What it is** (one-sentence definition)
   - **Why/when it's used** (trade-offs — this is what separates candidates)
   - **A failure story** (what breaks without it, or how it breaks in production)
3. **Build something after every phase.** Multiple thread authors repeat the same warning: tutorial-watchers stay stuck; builders get hired.
4. Interviewers rarely ask "define X." They ask "your API is timing out — what do you do?" You need the concepts wired together, not memorized as a flat list.

---

## Phase 0 — Meta: how to run your prep (2–3 days)

Source: @swapnakpanda's 20-point mentorship roadmap (student landed a US remote job in 3 months).

- **Pick one path** (backend / full-stack / AI engineering) and one small set of resources. Excessive resources = tutorial hell. Don't switch paths out of FOMO.
- **Practice > consume.** Reading/watching stores a concept temporarily; building makes it permanent.
- **Use Git from day 1** — every practice project goes on GitHub. It's your progress log and your proof of work.
- **Use AI tools within limits** — generate code, but debug, test, and rewrite it yourself. Interviews expose people who can't work without autocomplete.
- **Don't just code.** Learn: unit testing, debugging, documenting, logging, monitoring, deploying. Decent knowledge of all beats expertise in one.
- **Ship a portfolio + tight resume** (keywords: Full Stack, JavaScript, React, or your stack) and **build connections** — skills without a network reach no one.
- College tier doesn't matter. Time spent on regret is time not spent learning.

---

## Phase 1 — DSA foundation (2–3 weeks, then ongoing)

Sources: @themishra4402, @who__shivam, @swapnakpanda.

Consensus across threads: **you don't need to be a competitive programmer.** You need working fluency in the core structures and the ability to talk through operations and complexity.

### 1a. The 13 starter problems (~70% of beginner patterns)

| # | Problem | Pattern it teaches |
|---|---------|--------------------|
| 1 | Two Sum | Hash map lookup |
| 20 | Valid Parentheses | Stack |
| 53 | Maximum Subarray | Kadane's / DP |
| 121 | Best Time to Buy & Sell Stock | One-pass tracking min |
| 125 | Valid Palindrome | Two pointers |
| 141 | Linked List Cycle | Fast/slow pointers |
| 206 | Reverse Linked List | Pointer manipulation |
| 169 | Majority Element | Boyer–Moore voting |
| 217 | Contains Duplicate | Hash set |
| 242 | Valid Anagram | Frequency counting |
| 268 | Missing Number | Math / XOR |
| 283 | Move Zeroes | Two pointers, in-place |
| 704 | Binary Search | Binary search template |

Master these until you can solve them cold — a viral reply noted people with 500+ solved problems still freeze on Two Sum. **Depth on basics beats breadth.**

### 1b. Full topic map (know basics of all; deep-dive only core)

- **Basic structures:** arrays, strings, linked lists, stacks, queues — all operations + complexities.
- **Trees:** binary trees, BSTs, traversals; know AVL/B-Trees conceptually (why databases use B-Trees → connects to indexing later).
- **Graphs:** adjacency list vs matrix, BFS, DFS, Dijkstra, Bellman-Ford, topological sort, MST (Prim/Kruskal).
- **Heaps:** min/max heap, heap sort, top-K problems.
- **Hash tables:** collision handling, load factor.
- **Paradigms:** brute force, divide & conquer, greedy, DP (Fibonacci → LCS → LIS), sliding window, two pointers.
- **Sorting:** merge sort, quick sort, heap sort — complexities and when each wins.
- **Extras (awareness level):** trie, union-find (DSU), bit manipulation, KMP/Rabin-Karp, segment/Fenwick trees.

**Interview answer pattern:** state brute force → identify the bottleneck → name the pattern that removes it → code → state time/space complexity.

---

## Phase 2 — Backend engineering fundamentals (4–6 weeks)

Sources: @CodeEdison (both threads), @systemdesignone.

The 8-concept core progression: **REST API design → Auth (JWT/OAuth) → DB indexing → Caching (Redis) → Rate limiting → Logging & monitoring → Docker → System design.**

### 2a. HTTP & API design — the 20 concepts

For each, know the definition **and** the interview follow-up:

1. **Endpoint** — unique URL identifying a resource. *Follow-up: nouns not verbs; `/users/42/orders`.*
2. **HTTP methods** — GET/POST/PUT/PATCH/DELETE semantics. *Follow-up: PUT (full replace) vs PATCH (partial).*
3. **Request–response** — headers, body, status codes flow.
4. **Status codes** — 2xx/3xx/4xx/5xx; know 200/201/204/301/304/400/401/403/404/409/429/500/503 individually. *401 = who are you? 403 = I know who you are; you can't do this.*
5. **Authentication** — verifying identity.
6. **Authorization** — verifying permissions (RBAC vs ABAC).
7. **Access tokens** — short-lived credentials; refresh token rotation.
8. **OAuth 2.0** — delegated access without sharing passwords; know the authorization-code flow.
9. **Rate limiting** — cap requests per client per window; algorithms: token bucket, leaky bucket, sliding window.
10. **Throttling** — slow requests down instead of rejecting.
11. **Pagination** — offset vs cursor-based. *Follow-up: why cursor pagination survives inserts/deletes while offset breaks.*
12. **Caching** — response caching, ETags, Cache-Control.
13. **Idempotency** — same request repeated → same result, no double side effects. **Key interview nugget (from thread replies):** GET/PUT/DELETE are idempotent by spec; POST is not. To make "create charge" retry-safe, the client sends a unique `Idempotency-Key` header; the server stores the result against that key and replays it instead of executing twice. Without this, one timeout + one retry = double charge.
14. **Webhooks** — server pushes event notifications to registered URLs; contrast with polling.
15. **API versioning** — URL (`/v1/`), header, or content-negotiation; never break existing clients.
16. **OpenAPI/Swagger** — machine-readable API contracts.
17. **REST vs GraphQL** — over/under-fetching trade-off; GraphQL shifts complexity to server-side resolvers and caching gets harder.
18. **API gateway** — single entry point: routing, auth, rate limiting, TLS termination.
19. **Microservices** — small independent services communicating via APIs.
20. **Error handling** — consistent error envelope (code, message, details); never leak stack traces.

### 2b. Security & identity

- Authentication vs authorization (again — it's asked constantly).
- **JWT vs sessions vs cookies:** JWT = stateless, signed, can't be revoked easily; sessions = server state, easy revocation. Know where each wins.
- Password storage: **bcrypt/Argon2** (slow, salted, adaptive) — never MD5/SHA alone. Salting (per-user random) vs peppering (server-wide secret).
- 2FA, SSO, OAuth 2.0 flows.
- **Web attacks:** CORS (browser policy, not a security control on its own), CSRF (forged cross-site requests → SameSite cookies + tokens), XSS (escape output), SQL injection (parameterized queries), SSRF (server tricked into internal requests). For each: what it is, an attack example, and the standard defense.

### 2c. Databases

- Design & normalization; when to denormalize (read-heavy paths).
- SQL vs NoSQL — actual trade-offs, not tribalism: relational integrity + joins vs flexible schema + horizontal scale.
- **Indexing** — B-Tree structure, why writes get slower as indexes grow, composite index column order, covering indexes. Multiple authors flag this as the concept that changed how they think.
- Query optimization: EXPLAIN plans, **N+1 queries** (ORM classic — 1 query becomes 101), connection pooling (opening connections is expensive; pool them).
- **ACID** — atomicity, consistency, isolation, durability; isolation levels and their anomalies (dirty read, non-repeatable read, phantom).
- **CAP theorem** — under a network partition, choose consistency or availability. Real systems: which do Postgres, Cassandra, DynamoDB pick?
- Transactions, migrations, schema versioning.
- Optimistic locking (version column, retry on conflict) vs pessimistic locking (lock rows up front) — and when each is right.

### 2d. Caching & performance

- Strategies: cache-aside (lazy), read-through, write-through, write-behind.
- **Eviction:** LRU/LFU/TTL. **Invalidation** — "one of the two hard problems"; be ready with a concrete strategy (TTL + event-driven purge).
- **Cache stampede** — hot key expires, thousands of requests hit the DB at once. Fixes: lock/single-flight, staggered TTLs, early refresh.
- Redis vs Memcached; Redis pipelining for batching.
- CDN & edge caching for static and semi-static content.

### 2e. Server concepts, concurrency, background work

- Middleware chains, centralized error handling, structured logging, APM.
- Async/await, promises, event loop; thread pools; process vs thread; garbage collection basics; memory leaks.
- Race conditions & deadlocks — define, detect, prevent (lock ordering, timeouts).
- Background jobs, cron schedulers, worker processes — move slow work off the request path via queues.

### 2f. Quality & tooling

- Testing pyramid: unit → integration → E2E; mocking/stubbing.
- API docs (Swagger/OpenAPI), Postman; Git workflows; code review habits; debugging and benchmarking.

---

## Phase 3 — System design & scalability (4–6 weeks)

Sources: @system_monarch (Principal Engineer, Atlassian), @_Shiva_iitp's production checklist, @swapnakpanda's video curriculum, @SumitM_X.

> **Deep-dive companion:** the load-balancing thread below was Day 1 of a full 25-day series (CDN, caching, invalidation, rate limiting, API gateway, CAP, sharding, replication, partitioning, queues, microservices, fault tolerance, DB scaling, service discovery, idempotency, data locality, and more). Full recovered notes are in **Part II** of this document (below). Work through it alongside this phase — it is the single richest source in this whole guide.

### 3a. Load balancing — in interview-ready depth

This is the depth level interviewers reward (full breakdown from the Atlassian thread):

**Core insight: a load balancer distributes requests; that is not the same as balancing load.** It doesn't know one request takes 2ms and the next takes 12 seconds. Real example: 4 servers, Server 3 at 95% CPU, Server 1 at 12%, balancer reports "fine."

**Algorithms and where each fails:**
- **Round-robin** — rotate in order. Works only when requests cost roughly the same and servers are identical. Zero server-state awareness.
- **Least connections** — fewest active connections wins. Better, but connection count ≠ load (2 heavy report exports vs 15 sub-ms health checks).
- **Weighted round-robin** — manual weights for heterogeneous hardware. Problem: weights are static, traffic isn't, and nobody re-tunes them.
- **IP hash** — sticky sessions without a session store. Failure mode: one corporate NAT (10,000 users behind one IP) creates a hotspot.
- **Least response time** — routes on real latency; closest to actual awareness. What production should graduate to.

**Layer 4 vs Layer 7:**
- **L4 (transport):** sees IPs and ports only; fast, cheap, content-blind. Use for raw throughput, TCP passthrough (databases, brokers).
- **L7 (application):** reads URL, headers, cookies, body. Enables path-based routing (`/api` vs `/static`), cookie-based routing to premium tiers, canary routing (5% to new version), TLS termination. NGINX, HAProxy, ALB, Envoy.
- Production pattern: **L4 in front for throughput, L7 behind for intelligent routing.**

**Health checks:**
- **Active:** balancer polls "are you alive?" Gap: 10s interval means up to ~10s of traffic to a dead server. Shorter interval = faster detection but more overhead.
- **Passive:** watch real responses; catches "alive but broken" (500s, timeouts) — but needs traffic to detect anything.
- Production: **both**, failure threshold ~3 consecutive (not 1), and — the part everyone forgets — **slow-start recovery**: ramp a recovered server up gradually or the backlog crushes it and it dies again.

**Design review checklist:** know your traffic profile → pick your layer → configure both health-check types + slow-start → monitor the balancer itself (distribution variance, per-target 5xx, added latency) → revisit config every 6 months.

### 3b. Core distributed-systems concepts

For each: definition + trade-off + failure story.

- **Scaling:** vertical (bigger box, has a ceiling) vs horizontal (more boxes, needs statelessness/coordination); autoscaling.
- **Consistent hashing** — why naive `hash % N` reshuffles everything when N changes; virtual nodes.
- **Replication** — leader-follower, replication lag, read replicas (stale-read trade-off); leader election.
- **Sharding & partitioning** — shard-key choice, hotspots, cross-shard queries.
- **CAP + eventual consistency** — what "eventual" means in user terms (you may read your own write... later).
- **Message queues & pub/sub** — decoupling, buffering, async processing; **dead letter queues** for poison messages.
- **Event-driven architecture** — events as facts; consumers react independently.
- **Distributed transactions & Saga pattern** — 2PC is fragile at scale; sagas = local transactions + compensating actions.
- **Resilience patterns:** timeouts (always set them), retries with **exponential backoff + jitter**, **circuit breakers** (stop hammering a dying dependency), **backpressure** (shed or queue load instead of collapsing), load shedding, graceful degradation.
- **Idempotency** again — it's the price of admission for safe retries in every distributed conversation.
- **Fault tolerance:** failover, backups, disaster recovery, multi-region, chaos engineering.
- **Networking:** DNS, TCP vs UDP, HTTP/2 & HTTP/3, gRPC (binary, streaming, contract-first via protobufs), WebSockets vs long polling vs server-sent events.
- **Latency:** throughput vs latency, **P99/tail latency** (averages lie; the slowest 1% defines user experience), network partitions, clock skew.
- Race conditions, distributed locks, thread safety at the systems level.

### 3c. Deploy & operate (DevOps literacy)

- **Docker** (images, containers, layers) → **Kubernetes** (pods, services, liveness/readiness probes) → **CI/CD** pipelines.
- **Deployment strategies:** blue-green, canary, rolling; rollbacks; feature flags.
- **Observability trio:** logs, metrics, traces (distributed tracing); alerting; **SLO/SLI/error budgets**.
- Infrastructure as code (Terraform, Helm), secrets management, IAM, TLS, encryption at rest/in transit, WAF, DDoS protection.
- Incidents: on-call, postmortems (blameless), health checks.

Framing from the threads: *writing code is rarely the bottleneck — scaling it, observing it, and keeping it alive when real users depend on it is where engineering begins.* That list of ~100 production concepts is exactly what separates "app works on my machine" from "app survives its first million users."

### 3d. Curated video curriculum (from @swapnakpanda)

Watch in this order; take notes in the 3-layer format:

1. System Design Basics — youtube.com/playlist?list=PL5q3E8eRUieVFeK1oLahJ8KONkAxDpqk2
2. System Design Fundamentals — youtube.com/playlist?list=PLCRMIe5FDPsd0gVs500xeOewfySTsmEjf
3. API Design — youtu.be/DQ57zYedMdQ
4. Load Balancing — youtu.be/xg7Dj2AXLyk
5. Message Queues — youtu.be/DYFocSiPOl8
6. Rate Limiting — youtu.be/MIJFyUPG4Z4
7. Caching — youtu.be/1NngTUYPdpI
8. Sharding & Partitioning — youtu.be/wXvljefXyEo
9. Replication — youtu.be/oh8GvLf45t0
10. Consistent Hashing — youtu.be/vccwdhfqIrI
11. CAP Theorem — youtu.be/RexrINtVh-M
12. Microservices — youtu.be/vTjeDWhjuUc
13. Fault Tolerance — youtu.be/3Lis4w4_bBc
14. Scalability — youtu.be/tjubQ97lxA4
15. Event-Driven Architecture — youtu.be/Fb_0UOD2X2I
16. Service Discovery — youtu.be/v4u7m2Im7ng

**System design interview method:** clarify requirements & scale (QPS, data size, read/write ratio) → API sketch → high-level boxes → deep-dive 1–2 components → address bottlenecks with the patterns above. One solution never fits everywhere — **keep asking questions**; interviewers grade the questions as much as the answers.

---

## Phase 4 — Language depth (if you interview in Java) (2–3 weeks)

Source: @SumitM_X. The bar moved: "HashMap vs Hashtable" and "what is JVM" are over. Modern Java interviews test:

- **Real microservices design:** timeouts, retries, circuit breakers, idempotency — implemented, not defined.
- **Distributed thinking:** consistency models, partial failures, message reprocessing (what happens when a consumer crashes mid-message?).
- **Spring Boot internals:** how auto-configuration actually works, AOP proxies (why `@Transactional` on a private/self-invoked method silently does nothing), startup bottlenecks.
- **DB & caching strategy:** query tuning, index design, connection-pool sizing/exhaustion, cache stampedes, eviction strategy.
- **Kafka:** partitions & ordering guarantees, consumer groups & lag, backpressure, exactly-once semantics (and why it's hard).
- **API reliability:** rate limiting, throttling, load shedding, fan-out patterns.
- **Concurrency & JVM depth:** virtual threads vs platform threads (Project Loom), atomic classes, VarHandles, false sharing, thread contention, lock elimination, escape analysis, classloader leaks, how GC pauses hit throughput/tail latency.

Interviews now filter for engineers who understand **systems, not syntax**. (Substitute the equivalent runtime depth for your language: Node event-loop internals, Python GIL/asyncio, Go scheduler, etc.)

---

## Phase 5 — AI / Agentic engineering track (optional, 3–6 months)

Sources: @suraj_sharma14 (12-stage plan), @Shawnife (course list), @asmah2107 (backend-for-AI).

Skip if you're targeting pure backend roles; increasingly relevant everywhere else.

### 5a. The 12-stage agentic roadmap

1. **Python + async:** asyncio, FastAPI, event-driven architecture, error handling, API integration.
2. **LLM fundamentals for agents:** context management, model routing, token economics, latency trade-offs, failure modes.
3. **Tool calling + structured outputs:** Pydantic validation, function-calling schemas, error recovery, dynamic tool discovery.
4. **Memory + state:** short-term buffers, long-term vector recall, context compression, cross-session sync.
5. **Single-agent workflows:** ReAct loops, plan-and-execute, self-reflection, iteration limits, graceful degradation.
6. **Multi-agent orchestration:** LangGraph/CrewAI, supervisor patterns, message passing, conflict resolution, handoffs.
7. **Human-in-the-loop:** uncertainty detection, approval gates, audit trails, resume logic.
8. **Evaluation + QA:** eval harnesses, LLM-as-a-judge, regression testing, hallucination metrics.
9. **Observability:** distributed tracing (LangSmith/Arize), cost dashboards, latency monitoring, alerting.
10. **Security + guardrails:** prompt-injection defense, output filtering, PII redaction, sandboxed execution.
11. **Production deployment:** vLLM/SGLang, Kubernetes scaling, CI/CD for agents, canary releases, rollbacks.
12. **Open source + portfolio:** ship agents publicly, write architecture docs, record demos.

**Build a small demo after every stage** (top community advice on the thread) — projects beat certificates.

### 5b. Course path (from @Shawnife)

1. Python: Harvard CS50P — pll.harvard.edu/course/cs50s-introduction-programming-python
2. AI with Python: Andrew Ng — deeplearning.ai/courses/ai-python-for-beginners
3. Understand LLMs (transformers, attention): 3Blue1Brown neural-net series — youtube.com/watch?v=aircAruvnKk
4. Build LLMs: Karpathy, Neural Networks: Zero to Hero — youtube.com/playlist?list=PLAqhIrjkxbuWI23v9cThsA9GvCAUhRvKZ
5. Agents (concepts before frameworks): Anthropic, Building Effective Agents — anthropic.com/engineering/building-effective-agents
6. Applied multi-agent: CrewAI course — coursera.org/projects/multi-ai-agent-systems-with-crewai
7. 75+ projects: github.com/patchy631/ai-engineering-hub

Principle repeated by every author: **fundamentals outlast frameworks.**

### 5c. Backend depth that powers AI systems (from @asmah2107)

gRPC & protobufs (low-latency payloads) · asyncio & event loops · connection pooling · Kafka + CDC (real-time ingestion) · backpressure (surviving inference bursts) · Redis pipelining (batched feature fetch) · vector indexing (HNSW/IVF, e.g. Qdrant) · graph traversal (Cypher) · semantic caching (saves GPU compute) · distributed locking · rate limiting (token buckets for LLM APIs) · idempotency · sharding · OpenTelemetry tracing.

Their thesis: **architecture > algorithms** — the hidden engine of scalable AI is backend engineering.

---

## Phase 6 — Web-dev stack track (optional, for full-stack roles)

Source: @swapnakpanda. Sequence: HTML/CSS → JavaScript → small projects → TypeScript (types = fewer errors + easier language switches) → React (function components) → Next.js (SSR/SSG, easy deploys) → Node.js + Express (same language backend) → MySQL **and** MongoDB basics (one SQL, one NoSQL) → complete projects hosted on Vercel/Netlify → portfolio site + resume.

The mentor's roadmap linked specific sub-guides at each stage (recovered from the thread's embedded quote-tweets):
- **Path chooser** (Front-End / Back-End / Database / Data Science / AI-ML / DevOps): x.com/swapnakpanda/status/1602649619976290304
- **HTML & CSS beginner roadmap:** x.com/swapnakpanda/status/1516044359602384896
- **JavaScript absolute-beginner roadmap:** x.com/swapnakpanda/status/1508777896315301889
- **Small web project ideas (HTML/CSS/JS, beginner→pro):** x.com/swapnakpanda/status/1562768107130998784
- **150+ full-stack project ideas (any language):** x.com/swapnakpanda/status/1551177987692695553

---

## Appendix — Curated resource links (from the threads)

Recovered from the embedded quote-tweets in @swapnakpanda's roadmap. These are the actual "follow specific resources, not too many" picks referenced in Phase 0.

**30 free YouTube course playlists** (@swapnakpanda's "30 BEST YouTube Courses for 2026" — the ones matching this guide's tracks):
- Python — youtube.com/playlist?list=PL-osiE80TeTt2d9bfVyTiXJA-UTHn6WwU
- SQL — youtube.com/playlist?list=PLNcg_FV9n7qZY_2eAtUzEUulNjTJREhQe
- Java — youtube.com/playlist?list=PLxhSr_SLdXGMuioQzjhu_Xkp-qn4ibtx6
- JavaScript — youtube.com/playlist?list=PLIJrr73KDmRw2Fwwjt6cPC_tk5vcSICCu
- TypeScript — youtube.com/playlist?list=PL0Zuz27SZ-6NS8GXt5nPrcYpust89zq_b
- HTML/CSS — youtube.com/playlist?list=PL4-IK0AVhVjOJs_UjdQeyEZ_cmEV3uJvx
- React — youtube.com/playlist?list=PLC3y8-rFHvwg9D7EOSEBabuutIdKZN5V3
- Next.js — youtube.com/playlist?list=PLC3y8-rFHvwhIEc4I4YsRz5C7GOBnxSJY
- Node.js — youtube.com/playlist?list=PLC3y8-rFHvwh8shCMHFA5kWxD9PaPwxaY
- Express.js — youtube.com/playlist?list=PL0Zuz27SZ-6P4vnjQ_PJ5iRYsqJkQhtUu
- Django — youtube.com/playlist?list=PL4cUxeGkcC9iqfAag3a_BKEX1N43uJutw
- Go — youtube.com/playlist?list=PL4cUxeGkcC9gC88BEo9czgyS72A3doDeM
- Rust — youtube.com/playlist?list=PLPoSdR46FgI412aItyJhj2bF66cudB6Qs
- **DSA** — youtube.com/playlist?list=PLgUwDviBIf0oF6QL8m22w1hIDC1vJ_BHz
- (thread also has C, C++, C#, PHP, Swift, Kotlin, Dart, Ruby, Scala, R, Julia, MATLAB, Lua, Pascal, Assembly, Bash)

**AI engineering course path** (@Shawnife — see Phase 5b): CS50P → Andrew Ng AI-Python → 3Blue1Brown neural nets → Karpathy Zero-to-Hero → Anthropic Building Effective Agents → CrewAI course → patchy631/ai-engineering-hub (75+ projects).

**System design video curriculum** (@swapnakpanda — 16 videos, full list with links in Phase 3d).

---

## 12-week default schedule (backend/system-design focus)

| Weeks | Focus | Deliverable |
|-------|-------|-------------|
| 1–2 | Phase 1a: 13 LeetCode problems + arrays/strings/linked lists/stacks/queues | All 13 solved cold; notes per pattern |
| 3 | Phase 1b: trees, graphs, heaps, hash tables + 15 more problems | Pattern cheat sheet |
| 4–5 | Phase 2a–2b: HTTP, the 20 API concepts, auth, security | Build a REST API with JWT auth, rate limiting, pagination, idempotency keys |
| 6 | Phase 2c: databases — indexing, ACID, N+1, pooling | Add Postgres w/ indexes; measure a query before/after EXPLAIN-driven fix |
| 7 | Phase 2d–2e: caching, concurrency, background jobs | Add Redis cache + a worker queue to the project |
| 8 | Phase 3a: load balancing deep-dive + Docker | Containerize; run 2 instances behind NGINX; break one and watch health checks |
| 9–10 | Phase 3b: distributed concepts + video list 5–16 | 3-layer notes per concept; one failure story each |
| 11 | Phase 3c + mock designs: URL shortener, rate limiter, chat, feed | 4 written design docs |
| 12 | Language depth (Phase 4) + mock interviews + resume/portfolio | 2+ mock interviews; portfolio live |

Daily rhythm: ~1 hr DSA maintenance + ~2 hrs current phase + build.

---

## Master interview question bank (drill these)

**Rapid-fire definitions you must nail in 2 sentences:**
- Idempotency; 401 vs 403; PUT vs PATCH; JWT vs session; authn vs authz; CAP; ACID; cursor vs offset pagination; L4 vs L7; active vs passive health checks; optimistic vs pessimistic locking; horizontal vs vertical scaling; webhook vs polling; circuit breaker; backpressure; P99 latency; cache stampede; N+1 query; saga pattern; dead letter queue; consistent hashing; exactly-once vs at-least-once delivery.

**Scenario questions (practice out loud):**
1. A payment POST times out and the client retries. How do you prevent a double charge? *(Idempotency-Key header; server stores and replays the result.)*
2. One server in your pool is at 95% CPU while others idle. Why, and what do you change? *(Round-robin blindness → least-response-time; check health-check + slow-start config.)*
3. A hot cache key expires and your DB falls over. What happened and how do you prevent it? *(Stampede → single-flight lock, staggered TTL, early refresh.)*
4. Your ORM page issues 101 queries. Diagnose and fix. *(N+1 → eager loading/joins/batching.)*
5. A downstream service is failing; your service is now failing too. What pattern is missing? *(Timeouts + circuit breaker + fallback.)*
6. Users behind one corporate IP all land on one server. Which LB algorithm caused it? *(IP hash hotspot.)*
7. A recovered server dies again seconds after rejoining the pool. Why? *(No slow-start; backlog crushed it.)*
8. Kafka consumer lag is growing. What do you look at? *(Partition count vs consumers, processing time, backpressure, poison messages → DLQ.)*
9. Why can't you just `hash(key) % N` across cache servers? *(Adding a server remaps nearly everything → consistent hashing.)*
10. Design: URL shortener / rate limiter / notification system / news feed — using the Phase 3 method.

---

## The five rules the threads keep repeating

1. **Fundamentals outlast frameworks.**
2. **Builders get hired; tutorial-watchers stay stuck.** Ship something after every phase.
3. **Depth on basics beats breadth on advanced topics** — people fail Two Sum with 500 problems solved.
4. **Code is rarely the bottleneck** — scaling, observing, and operating it is the real engineering.
5. **Trade-offs are the answer.** Nearly every interview question is secretly "tell me the trade-off and when you'd choose differently."

---

## Source threads

| Author | Topic | Link |
|--------|-------|------|
| @swapnakpanda | 20-point career roadmap | x.com/swapnakpanda/status/2077827855971721670 |
| @systemdesignone | 20 API design concepts | x.com/systemdesignone/status/2078093755484103057 |
| @suraj_sharma14 | 6-month agentic AI engineer plan | x.com/suraj_sharma14/status/2077724941198533011 |
| @_Shiva_iitp | ~100 production concepts checklist | x.com/_Shiva_iitp/status/2073080828897444294 |
| @Shawnife | AI engineering course roadmap | x.com/Shawnife/status/2070437055818006769 |
| @asmah2107 | Backend depth for ML engineers | x.com/asmah2107/status/2068700638394163473 |
| @system_monarch | 25-day scaling series, Day 1 of 25 — full series notes in **Part II** below | x.com/system_monarch/status/2064978857263014366 |
| @themishra4402 | 13 foundational LeetCode problems | x.com/themishra4402/status/2046794306904883453 |
| @CodeEdison | Backend fundamentals mega-list | x.com/CodeEdison/status/2045883780146110946 |
| @swapnakpanda | 16 system design videos | x.com/swapnakpanda/status/2044844408869396864 |
| @CodeEdison | 8 backend concepts for 2026 | x.com/CodeEdison/status/2039347494845386931 |
| @who__shivam | Complete DSA roadmap | x.com/who__shivam/status/2020543795784540609 |
| @SumitM_X | Modern Java interview expectations | x.com/SumitM_X/status/2020713443188416747 |

*Note: the @who__shivam DSA tree contained several joke entries (e.g. "ModiJi_Sort", "Apple_Trees", "British_Optimization") — those were filtered out above; only the real topics remain.*

*Cross-checked against the unrolled thread archives in `threads/`. The only content the original scrape had dropped was the set of embedded quote-tweet resource links in @swapnakpanda's 20-point roadmap (the "He followed this roadmap:" pointers) — now recovered into Phase 6 and the Curated resources appendix. The other 11 threads matched the captured notes exactly.*


---

# PART II — SYSTEM DESIGN DEEP-DIVE (the 25-day scaling series)


> Deep-dive for Part I's Phase 3 (system design).
> Source: @system_monarch (Puneet Patwari, Principal Engineer @ Atlassian), June 11 – July 13, 2026.
> The original thread shared Day 1 publicly; these notes recover the **complete series, all 25 days in full**.

**The series' core thesis (from the finale):** all 25 concepts answer just 3 questions —
1. **How do we handle more load?** (load balancing, CDN, caching, rate limiting, queues, scaling out, data locality)
2. **How do we store more data than one machine can hold?** (sharding, partitioning, replication, estimation)
3. **How do we stay correct when things fail?** (CAP, consistency models, leader election, distributed transactions/sagas, idempotency)

And one sentence to rule them all: **every architecture decision trades off speed, cost, and correctness — you cannot maximize all three.** "It depends" is the correct answer; the skill is knowing what it depends on.

---

## Day 1 — Load Balancing
[Thread](https://x.com/system_monarch/status/2064978857263014366) · fully covered in the main guide (Phase 3a): algorithms + failure modes, L4 vs L7, active/passive health checks, slow-start recovery.

## Day 2 — CDN

A CDN's real value is not speed — it's **protection**: 10,000 req/s hitting the edge with a 92% hit ratio means your origin sees ~800. Origin should only handle cache misses, writes, and truly dynamic content.

- **Pull-based** (95% of teams): edge fills on first request; first user pays a 200–300ms penalty. Fine for most apps.
- **Push-based**: pre-warm edges before big moments (game launches, live events) where the first-request penalty is a disaster.
- Routing: Anycast/GeoDNS routes users to the closest PoP **by network latency, not geography** — a Mumbai user may hit Singapore if Mumbai is overloaded.
- **Cache hit ratio** is the one metric: <80% = expensive proxy; 85–90% = solid production; 95%+ = origin basically retired. Teams plateau at ~65% because they only cache images/CSS/JS.
- Things to cache that look dynamic but aren't: same-for-everyone API responses (30s TTL turned 10K rps into 300 rps in one case), HTML fragments (header/footer), GraphQL results (cache by body hash), per-cohort data via Vary headers, DNS+TLS termination at edge (saves 100–200ms on first connection).
- Invalidation: TTL expiry (simple), purge API (targeted fixes only — bulk purge = thundering herd on origin), and **stale-while-revalidate** (`Cache-Control: max-age=60, stale-while-revalidate=30`) — serve stale instantly, refresh in background; the author's go-to that "fixes 90% of CDN cache headaches."
- Modern CDNs run code at the edge (Cloudflare Workers, Lambda@Edge): auth validation, A/B tests, geo-personalization, response transforms.
- Design rule: if read:write is near 100:1, the CDN is your most important layer. It's a day-one architecture decision, not a bolt-on.

## Day 3 — Caching: the 5 layers

Most engineers say "Redis." That's one layer of five. Performance problems are solved by caching at the **right layer**, not by adding Redis.

1. **Browser cache** — 0ms, per-user. `Cache-Control: max-age`. Cache versioned static assets (`app-v2.4.js`); never cache user-specific data on shared devices. Gotcha: 1-year TTL + deploy = users stuck on old file → version the filenames.
2. **CDN edge** — 5–20ms, per-region: one fill serves thousands.
3. **Application cache (Redis/Memcached)** — 1–5ms vs 20–100ms DB reads. Cache-aside pattern is the 90% starting point. Cache: expensive query results, sessions, hot objects, computed results (leaderboards, feeds), rate-limit counters. Don't cache: per-request data, huge rarely-read objects, staleness-costs-money data. Redis = data structures/pub-sub/scripting; Memcached = simpler, multithreaded, pure KV at high throughput.
4. **Database query cache** — MySQL removed its query cache in 8.0 (writes invalidated everything). Postgres instead has prepared statements, shared buffers, and per-connection caches — this is why connection pooling matters: long-lived connections = warm caches.
5. **OS page cache** — the OS caches disk reads in RAM automatically. A DB that "fits in memory" is fast without Redis. This is why more RAM often beats more CPU, and why production DBs run on dedicated machines (batch jobs evict your DB's pages).

Method: start at the top — a Cache-Control header may do what Redis would, with zero infra. For each datum ask: how often does it change? how many people see the same version? what does 30s of staleness cost?

## Day 4 — Cache Invalidation

The DB has the truth; the cache has a copy; after every write the cache is lying until told to stop.

**Three write strategies:**
- **Write-through** — write DB + cache together. Always consistent; but every write is two writes (slower, partial-failure handling needed, wasted memory on never-read data). Use for: must-be-fresh read-heavy data (prices, permissions, feature flags).
- **Write-behind (write-back)** — write cache first, flush to DB in background batches. Insanely fast writes, reduced DB pressure; but cache is temporarily the source of truth — Redis crash = data gone (author saw 45 min of analytics lost). Never for money or unrecreatable data.
- **Write-around + cache-aside** — write DB only; cache refills on next read miss. Simple, clean, only hot data cached; one slow read after each write. Hurts on write-and-read-constantly data (use write-through there).
- **The cheat code:** on write, **delete** the cache key, don't update it — concurrent updates can race and leave the older value in cache; delete forces next read to fetch fresh. Recommended for 80% of cases.

**Two invalidation approaches:** TTL (start here; rules of thumb — config/flags 5–10 min, listings/search 30–60s, sessions 30 min, versioned assets 1 yr) vs event-driven (CDC/queue-triggered purge; precise but more infra). Event-driven only where staleness costs: inventory, pricing, permissions, real-time features.

## Day 5 — Rate Limiting

Origin story: their own batch job sent 400K requests in 90s and folded the API. Rate limiting is not about attackers — it's enforcing your known capacity before things break.

**Algorithms:** token bucket (allows bursts, enforces average — what AWS/Stripe use; the default), sliding window (strict, no bursts — auth/payment endpoints), leaky bucket (perfectly smooth output — protect rate-sensitive downstreams).

**Enforce at ≥2 layers:** gateway global limits; per-service (a 50-rps export service must not die from one hot client and cascade); per-user/API-key (fairness + pricing-tier enforcement); per-endpoint (a 30s report query ≠ a cached search).

**The response matters:** return **429** (not 500/503) with `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `Retry-After`. Beware **retry storms** — 1,000 clients retrying in sync; fix with exponential backoff + **jitter** (the jitter is the key part). And **whitelist health checks** — the author saw rate-limited health checks pull a healthy service out of rotation.

**Counters:** Redis (atomic increments + TTL); local counters break behind a load balancer. Setting limits: measure first, set above normal peak / below breaking point, start lenient, alert if >1–2% of requests get 429. Rate limiting slows attackers; it is not security — that's WAF + auth.

## Day 6 — API Gateway

Single entry point; without it, every service reimplements auth, rate limiting, logging, CORS, validation — each slightly differently, each with its own bugs.

**Six jobs:** authn/authz (validate JWT once, at the door), rate limiting (layer 1), request routing (path-based), protocol translation (REST outside, gRPC inside), response caching, and centralized logging/observability (one place, one format, correlation IDs).

**Routing patterns at scale:** header-based (API version, device type), **weighted/canary** (5% → 25% → 100%, instant rollback — "deploying without a canary is just hoping"), **BFF/request aggregation** (1 mobile round trip instead of 3 = 200ms vs 800ms on cellular).

**Gateway vs service mesh:** gateway = **north-south** (external→in, always needed at scale); mesh (Istio/Linkerd) = **east-west** (service↔service: mTLS, retries, circuit breaking via sidecars) — overkill below ~20 services.

**Production tips:** buy don't build (Kong/Envoy/ALB); keep the gateway thin — no business logic; version APIs from day one; health-check-aware routing; monitor the gateway itself (should add 1–5ms; run clustered — it's a single point of entry).

## Day 7 — CAP Theorem

"Pick 2 of 3" is the wrong framing — the author pushes back on it in interviews. Correct statement: **when a network partition occurs, choose consistency or availability.** When the network is healthy, you get all three. And you don't choose P — partitions WILL happen.

- **CP:** a node that can't verify freshness refuses to answer. Error, but never wrong data. Banks ("transaction failed, try again"), Google Spanner, ZooKeeper/etcd.
- **AP:** every node answers with what it has — possibly stale. Cassandra, DynamoDB defaults, DNS.
- Pattern: CP where money/coordination/safety; AP where UX/uptime beat perfect accuracy.

**The interview-winning answer: CAP is a per-feature decision, not per-system.** E-commerce: catalogue AP, last-item inventory CP, reviews AP, payments CP, cart AP-with-merge. When asked "is your system CP or AP?" answer: "depends on the feature — let me walk through which parts need strong consistency."

**PACELC** completes the picture: during Partition, A vs C; Else, Latency vs Consistency (sync replication = consistent-but-slower; async = fast-but-briefly-stale).

## Day 8 — Sharding

The scaling decision you can't easily undo — reversing it is a multi-month migration (cache/replica removal is trivial by comparison).

**Sharding vs partitioning:** partitioning splits data *within one database server* (low-risk, reversible); sharding splits *across independent servers* (brings cross-shard queries, broken joins, distributed transactions, painful rebalancing). **Partition before you shard.**

**Three strategies:** range-based (fast range queries; hotspot risk — "current month" shard eats all traffic), hash-based (even spread; kills range queries; adding shards = rehash unless consistent hashing), directory-based (lookup table maps key→shard; max flexibility; the directory is a SPOF + bottleneck). Most teams: hash + consistent hashing.

**Shard key rules (the decision that matters most):** 1) shard on what you query by most; 2) high cardinality (country fails: 60% India = bottleneck with extra steps); 3) even distribution; 4) co-locate data that's joined together (orders + users both by user_id — avoids scatter-gather). By domain: SaaS→tenant_id, social→user_id, e-commerce→customer_id, chat→conversation_id, time-series→time-bucket+hash. Optimize for reads.

**Try first, in order:** optimize queries (EXPLAIN, indexes, N+1) → connection pooling → read replicas → partition tables → vertical scaling → THEN shard. Heuristic: <500GB and <10K writes/s → you probably don't need sharding.

## Day 9 — Replication

Multiple copies on different machines: survive hardware death, scale reads (5–10x), serve geography, zero-downtime maintenance. The price: **replication lag**.

- **Leader-follower flow:** writes → leader → WAL streamed to followers.
- **Sync** replication: leader waits for ≥1 follower ack — no data loss, slower writes (+50–100ms cross-region). **Async:** confirm immediately, stream later — fast, but leader death loses recent writes. Production Postgres: **semi-sync** (one sync follower, rest async).
- **Read-your-own-writes bug:** user updates profile → immediately reads a lagging replica → sees old data → "the app is broken." Fixes: route that user's reads to leader briefly; causal consistency (wait for replica to reach the write's WAL position); or leader-reads for the flows that matter. Monitor lag; alert above ~1s.
- **Failover:** followers detect missed heartbeats → election → most-up-to-date follower promoted (RDS: 30–120s; Patroni: <10s). The scary parts: **split brain** (old leader returns, both accept writes → fencing/STONITH required), **data-loss window** (async), **connection storms** (everyone reconnects at once and crushes the cold new leader → put PgBouncer in between). **Test failover quarterly — untested failover is a theory.**
- **Topologies:** single-leader (the default, 90% of cases), multi-leader (multi-region writes; buy time for conflict resolution), leaderless/Dynamo-style (Cassandra/DynamoDB; tunable quorums; extreme availability, eventual consistency).

## Day 10 — Partitioning

The unglamorous step teams skip on the way to sharding. Real story: 400M-row orders table, 3 months of sharding plans — then someone partitioned by date; 8s → 200ms; sharding shelved.

- **Range** (by date — orders_2024, orders_2025): time-series, logs, orders. **List** (by country/tenant/status): known discrete values; watch skew. **Hash** (`hash(user_id) % 4`): even spread or write-lock contention relief; kills range queries.
- **Partition pruning is the whole game:** `WHERE created_at > '2025-01-01'` scans only matching partitions (84% less data in the example). Verify with EXPLAIN. Pruning breaks when: WHERE doesn't include the partition key, type mismatch (DATE vs TIMESTAMP), or functions applied to the key (`EXTRACT(YEAR ...)` — use range comparison instead).
- Killer feature: retention. `DROP PARTITION orders_2021` = milliseconds, no locks; vs an hours-long locking DELETE on 500M rows. Also: faster index maintenance, parallel scans, hot/cold storage tiers.
- Doesn't help when: queries don't filter on the partition key, table <10M rows (overhead), cross-partition joins, or you expected it to replace indexes (you need both).
- Practical: 1M–100M rows per partition is the sweet spot; pre-create future partitions (pg_partman); Postgres requires the partition key inside the PRIMARY KEY; don't mix strategies.
- Framework: <10M rows → index properly. 10M–500M time-queried → range by month/year. Category-filtered → list. Write contention → hash. Beyond → sharding.

## Day 11 — Queues

A queue doesn't make the system faster — **it makes failure graceful**: 14K simultaneous requests become 14K buffered messages processed at your pace. Uncontrollable spike → controllable stream.

- Producer → queue (persisted buffer) → consumer (ack deletes). Gives: decoupling (add consumers with zero producer changes), load levelling (dam on a river), retry/reliability (unacked messages redeliver).
- **Delivery guarantees:** at-most-once (fast, can lose), **at-least-once (default: SQS, RabbitMQ — "sometimes twice")**, exactly-once (hard, expensive; Kafka transactions within limits). Production norm: **at-least-once + idempotent consumers.**
- **RabbitMQ** = task distributor (push, delete-on-ack, low latency; no replay). **Kafka** = event log (pull, retained, multi-consumer, replayable, millions/s; overkill for simple task queues). **SQS** = managed easy button (Lambda triggers; no replay/fan-out without SNS).
- **Production trio:** (1) **Backpressure** — monitor queue depth, autoscale consumers, and reject at source (503) when dangerously full; a sustained producer/consumer imbalance turns a spike into a permanent backlog. (2) **DLQ** — after 3–5 failures, move poison messages aside; no DLQ = one malformed message blocked processing 4 hours. (3) **Idempotent consumers** — dedupe by message ID, use upserts, prefer "set balance to X" over "add 500."
- Use queues for: deferrable work, throughput mismatches, decoupling, reliability on critical paths, spike absorption. Don't: when the user needs the answer now, for YAGNI, to hide a slow database. Pattern: **synchronous fast path** (validate, create order, confirm in 500ms) **+ async everything else** (email, analytics, warehouse, invoice).

## Day 12 — Microservices

**"Microservices solve an organisational problem, not a technical one"** — the most important sentence in the thread. Their real value: independent deployment by independent teams. Rule: one team, one service — two teams owning one service = wrong boundary; one team owning four = modules pretending to be services.

**The costs:** function calls become network calls (nanoseconds → 1–10ms + failure modes + retries + circuit breakers); ACID transactions become sagas with compensations; debugging becomes distributed tracing across 7 services; operational overhead multiplies by service count (author saw 60% of engineering time going to infra).

**You need them when:** teams block each other's deploys; wildly different scaling needs (search: 50 instances, profiles: 2); different stacks genuinely required; fault isolation is critical. **You don't when:** one team / <10 engineers; early stage (boundaries drawn today are wrong after the pivot — Segment's 140 services became a viral cautionary tale); your bottleneck is product, not engineering throughput.

**Path:** well-structured monolith → extract first service at real pain (auth, notifications, media processing are common firsts) → extract per team as org grows (Conway's Law used deliberately). Prerequisites before the first extraction: centralized logging + correlation IDs, health checks, gateway/mesh, circuit breakers, distributed tracing — otherwise "you're just splitting a monolith into a distributed mess."

## Day 13 — Monolith vs Microservices

The receipts: Amazon Prime Video merged back to a monolith (costs -90%), Segment rebuilt theirs from 140+ services, Istio re-merged components, Shopify/Stack Overflow/GitHub/Basecamp run monoliths at massive scale — deliberate choices by teams who tried the alternative.

Start monolith (5–30 engineers) because: (1) you don't know your boundaries until 6–12 months of real users — moving a boundary in a monolith is a refactor; across services it's a multi-quarter migration; (2) your bottleneck is shipping speed — monoliths do 50K rps on 4–5 boxes; (3) operational tax compounds — the math doesn't work below 50–80 engineers.

**The missing third option: the modular monolith** (Shopify's "majestic monolith"). One deployable, one repo, one DB — but service-grade internal boundaries, per-module team ownership, isolated tests. 80% of microservice benefits, none of the distributed pain. Stack Overflow: 1.3B pageviews/month on 9 web servers, one .NET monolith. When Shopify finally extracted services, the boundaries already existed — weeks, not quarters.

Extract only on real pain: independent scaling worth >30% infra savings; different reliability tiers (payments 99.99% vs internal reporting); deploy-cadence collisions; genuinely different stacks. NOT reasons: "we have 25 engineers," "Stripe does it," "the CTO read a book." Extract ONE, live with it 3–6 months. Most teams stop at 3–5 services.

## Day 14 — Fault Tolerance (the four patterns)

You can't prevent failure — you decide whether it stays a blip or cascades. Four patterns:

1. **Timeouts** — many libraries wait forever by default (Python requests, Java HttpURLConnection, Go http.Client). Without one: a slow dependency fills your 100-connection pool in 30s and the failure walks upstream — five services down, zero bugs. Set: connect 1–3s; read = p99 of dependency + buffer (measure, don't guess); total < your caller's timeout. Set at every process boundary: HTTP, DB (incl. pool wait), cache, brokers, RPC. "Cheapest fix in the resilience playbook — audit this week."
2. **Circuit breakers** — timeouts protect from one slow call, not 1,000/s doomed calls at a dead service. States: closed (track failures) → open (fail instantly in microseconds; let the dependency recover) → half-open (probe 1–3 requests). Tuning: trip at 50% failures over 10s with ≥20 requests (the floor prevents tripping on 2 failures); open 30–60s. Use a library (Resilience4j, Polly, gobreaker, pybreaker) or the mesh — hand-rolled breakers wedge.
3. **Bulkheads** — one 200-thread pool + a hanging analytics dependency = whole service down. Per-dependency pools (payments 100, analytics 10) turn a total outage into one degraded feature. Size = throughput × p99 latency + 50% headroom (50 rps × 0.5s ≈ 25 in flight → ~40 threads). Also: separate DB connection pools per workload, process isolation.
4. **Retries with backoff** — the most dangerous pattern. Retry storms amplify outages (part of what kept S3 down in 2017). Fix: `delay = min(cap, base·2^attempt) + random(0, base)` — **jitter is mandatory**, spread not waves. Cap at 3 retries. Only retry idempotent operations; otherwise you need idempotency keys.

Build order: timeouts everywhere (this sprint) → bulkheads on top-3 dependencies → circuit breakers on external calls → proper backoff on every retry loop → chaos test in staging (kill a DB mid-request, add 5s latency, drop 10% of packets; Gremlin/Chaos Monkey). "Design for failure and resilience follows. Design for success and you'll be writing postmortems."

## Day 15 — Database Scaling (the 5-step ladder)

Each step ~10x cheaper than the next; walk top to bottom; most products never reach the bottom.

1. **Indexes** — a CREATE INDEX routinely turns 4s into 4ms (full scan O(n) → index seek O(log n): a billion rows ≈ 30 reads). Don't guess: pg_stat_statements / slow query log; optimize highest *total* time (avg × frequency — a 100ms query at 10K/min beats a 5s hourly one). EXPLAIN: `Seq Scan` on a big table = your problem. The two everyone misses: **foreign-key indexes** (not auto-created!) and **composite indexes** matching common WHERE clauses (equality columns first, range last). Cost: each index slows writes; target 5–10/table; drop unused (pg_stat_user_indexes).
2. **Connection pooling** — each Postgres connection ≈ 10MB + handshake; max_connections caps concurrency. Pools let 50 connections serve 5,000 concurrent requests. Two layers: app-side (cores × 2–3, cap ~20–30/instance) + **PgBouncer in transaction mode** (5,000 app connections multiplexed onto 50 real ones). "One config file. The leverage is enormous."
3. **Read replicas** — 1 primary + 2–5 replicas ≈ N× read throughput; isolate heavy reports; geographic reads. Doesn't help write throughput or storage. Mind replication lag (Day 9).
4. **Partitioning** — (Day 10) 10B-row table → monthly 400M-row partitions; O(1) drops of old data.
5. **Sharding** — (Day 8) last resort; effectively infinite scale (Vitess: YouTube/Slack/GitHub) at permanent complexity cost.

Numbers: one well-tuned Postgres = 10K–50K qps; + replicas = 50K–200K read qps; shard ×10 = 500K+. **Start with the symptom, not the solution:** slow-query log, CPU, connection count, lag, I/O — the data tells you which step. Most problems are solved at steps 1–2.

## Day 16 — Service Discovery

Opening scenario: deploy at 2pm; new payment instances healthy, old ones dead; checkouts fail at 2:03 because hostnames moved and nothing told the callers. Service discovery answers: **where is service X right now?**

Hardcoding is fine until instances become ephemeral, deploys frequent, scaling dynamic, or you cross regions. Then:

1. **DNS-based** (Route53, CoreDNS, Consul DNS) — universal, cacheable, simple. Breaks on: TTL caching (dead IPs served until expiry), no metadata/weights, no traffic shaping. Pick when deploys are infrequent and infra is long-lived. Common combo: DNS + load balancer.
2. **Registry-based** (Consul, Eureka, etcd, ZooKeeper) — instances self-register + heartbeat; clients query for healthy instances with metadata ("v2 in us-east-1"); watches push changes. Cost: the registry is critical infrastructure needing HA + ops. Pick at 15+ services with frequent deploys and metadata routing needs.
3. **Platform-native (Kubernetes)** — Service objects + Endpoints; zero app code; integrated with rolling updates and readiness probes. On K8s, use it — don't install Consul on top "just in case."

Service mesh sits **above** discovery (traffic management, mTLS, observability, resilience) — add only for specific problems.

Decision: on Kubernetes → native, done. Static infra → DNS. Dynamic VMs → registry. Avoid: rolling your own, copying Netflix, mixing three mechanisms. "Discovery should be boring. The day you notice it is the day it broke."

## Day 17 — Consistency Models

Hook: March 12, 2013 — the Bitcoin blockchain split; for six hours two parallel realities existed, coins spent on one chain were ghosts on the other, until the network picked a winner and erased the other timeline. "Consistent" is the most overloaded word in distributed systems — a vendor saying "strongly consistent" might mean any of five things. The gap between two of these models is the difference between a working bank and a fraud incident.

Core idea: on one machine a read always sees the latest write (one reality). Add machines and you have copies talking over a slow, unreliable network. A consistency model is the system's written answer to: after I write to A, when can I read it from B? If two clients write at once, who wins? Stronger model = more promises, cheaper/faster = weaker. The scale runs from "behaves like one machine" to "we'll figure it out eventually."

**Five models, strongest to weakest:**
1. **Linearizability** — strongest practical; what most mean by "strongly consistent." Every operation appears at a single instant matching real-world order. Done via a single primary or consensus (Raft/Paxos). Costs latency (≥1 round trip; 1–5ms same-DC, 50–200ms cross-region), throughput, and availability during partitions (minority side stops accepting writes). Use for banking, inventory limits, distributed locks, leader election. Examples: etcd, ZooKeeper, Spanner, FaunaDB, CockroachDB.
2. **Sequential consistency** — each single client's operations appear in its issued order; cross-client order is free. Mostly academic; you rarely ask for it by name. Worth knowing the term.
3. **Causal consistency** — causally-related ops (happened-before: a post, then a comment on it) appear in order; unrelated ops any order. Captures the intuition humans actually have — much cheaper than linearizability while still feeling correct. Tracked via vector clocks / session tokens. Use for comments, messaging, feeds (WhatsApp ordering is close). Examples: COPS, some Riak configs.
4. **Eventual consistency** — weakest, most common at scale. "Stop writing, wait long enough, all replicas agree." No commitment on when (ms to seconds normally; minutes under load). Nearly free — each node accepts writes locally and gossips. Breaks read-your-own-write (write X=5, read back 4). Usually patched with sticky sessions. Examples: DynamoDB/Cassandra defaults, Couchbase, Riak, S3.

**CAP/PACELC:** CAP decides CP vs AP *before* the partition (there's no CA — networks partition). PACELC is more honest: during Partition choose A or C; Else (normal) choose Latency or Consistency. Most DBs are **PA/EL** (eventual by default); Spanner is **PC/EC** (always consistent, pay the latency — why it's expensive).

**Decision framework (per-operation, not per-database):** (1) what if a read is slightly stale? "who cares" → eventual; "double-charge" → strong. (2) what during a partition? keep taking orders → AP; refuse rather than double-count → CP. (3) latency budget? Real systems mix: shopping cart eventual, checkout strong, order history causal — same DB, different boundaries. Traps: "we're consistent" without saying which kind; read-your-write bugs; cross-region writes that are suspiciously fast (you have something weaker pretending); unplanned conflict resolution in AP systems. Pick the weakest model that doesn't break your product, make it explicit per operation, write down partition behavior, test it (Jepsen exists for this).

## Day 18 — Eventual Consistency in Production

Hook: Knight Capital lost $440M in 45 minutes on Aug 1, 2012 — not a hack, a deploy that reached 7 of 8 servers; the 8th ran ancient logic and the two versions disagreed about reality. Company didn't survive the week. Eventual consistency is the default at scale (DynamoDB, Cassandra, S3, DNS, every CDN) because strong consistency at scale is brutally expensive. The catch: it's a promise about the future, not the present — your code regularly sees stale data, partial updates, unpropagated writes.

**Four production failures:** (1) **read-your-own-write fails** — user renames profile, reload hits a lagging replica, sees old name, clicks save again → duplicate write. (2) **monotonic reads break** — 3 refreshes show 5→4→5 orders; time appeared to go backward. (3) **concurrent updates lose data** — Alice and Bob edit on different nodes, later timestamp silently wins (classic lost update). (4) **causality breaks** — a comment lands on a replica before the post it references.

**Four fixes:**
- **Read-your-writes** — after you write, your next read sees your write (other users may still be stale). Implement via sticky sessions, read-from-primary-for-N-seconds-after-write, or causal tokens (write returns a version; reads wait for a replica caught up to it).
- **Monotonic reads** — once you've seen a value, never see an earlier one. Stick a user to one replica across reads, or use causal tokens. Simplest: hash user ID → fixed replica. Matters for grow-only lists (order history, feeds) and monotonic counters.
- **Session consistency** — combines the two per user session: writes immediately visible to them, reads never go backward. The practical sweet spot for consumer products (Facebook/Twitter/Instagram run something like this: eventually-consistent underneath, session-consistent at the user layer).
- **Conflict resolution** — **LWW** (last-write-wins by timestamp; simple, silently loses data — only for view counts, flags). **Vector clocks** (detect true concurrency, hand both values to the app to merge — DynamoDB, Riak). **CRDTs** (data structures that always merge cleanly by construction: G-Counter, OR-Set, LWW-Register — how Google Docs/Figma/Notion do multi-user editing; can't express arbitrary business logic). **Application-defined merge** (DB hands you the conflict; you union carts, sum decrements).

**Production stack:** eventual at storage; session consistency per user (sticky routing); monotonic reads cross-user where possible; strong consistency only on critical paths (payments, inventory) paying the latency tax there only; CRDTs/app-merge for collaborative data, LWW where loss is acceptable. **Traps:** trusting the DB's default conflict resolution (DynamoDB/Cassandra default to LWW — read the docs); read-modify-write on eventual data loses money (use atomic ops / compare-and-swap / a strong path); hide latency from users with optimistic UI updates (update immediately, revert on failure — what every social network does); test for inconsistency (inject replication delay in staging, chaos-test node failures — a single-node dev env can't reproduce these bugs).

## Day 19 — Distributed Transactions & Sagas

Hook: British Airways, May 2017 — an engineer disconnected a power supply during maintenance; systems came back out of sync (booking, baggage, crew scheduling each said something different); every flight from Heathrow/Gatwick cancelled 3 days, 75K stranded, £80M. Smaller version happens constantly: user clicks buy, payment service charges the card, inventory service fails to decrement — card charged, item never reserved. In a monolith this is one BEGIN/COMMIT with ACID and ROLLBACK. The moment each microservice has its own database, there's no shared transaction and no shared ROLLBACK — and "manually undo a credit-card charge" is very different from ROLLBACK.

**Approach 1 — Two-Phase Commit (2PC).** Coordinator runs Phase 1 (Prepare: everyone votes YES/"ready") then Phase 2 (Commit if all yes, else Abort/rollback). Clean in theory. Three killers: (1) **slow** — moves at the speed of the slowest participant, kills throughput; (2) **coordinator is a SPOF** — if it dies after "prepare" before "commit," participants hold locked resources and block forever ("blocking problem," the Achilles heel); (3) **resource locking** — all involved rows locked for the whole transaction → massive contention. Verdict: almost never in modern microservices; works in tightly-coupled systems (single DB multiple schemas, mainframes). Know it because interviewers ask and because sagas exist to fix it.

**Approach 2 — Saga pattern** (what production actually uses). Break the transaction into a sequence of local transactions; if one fails midway, run **compensating transactions** (new forward actions that reverse effects, not rollbacks): charge card ✓ → decrement stock ✓ → create order ✗ → increment stock back, refund card. Two coordination styles: **choreography** (event-driven — each service publishes an event the next reacts to; loosely coupled but nobody owns the full picture, debugging is hard) vs **orchestration** (a central saga coordinator directs each step; owns the flow, easier to debug, single point of coordination). His take: orchestration for 4+ steps / money flows, choreography for simple 2–3 step flows.

**The hard parts nobody warns about:** (1) compensations aren't always simple (what if the refund fails? — retries, turtles all the way down; every step needs a defined, tested undo). (2) **idempotency is non-negotiable** — messages/compensations can fire twice; "refund ₹500" running twice refunds ₹1000; use idempotency keys. (3) **dirty reads** — between step 1 success and step 3 failure the system is inconsistent; mark inventory "reserved" not "sold" until the saga completes. (4) **timeouts and stuck sagas** — a never-responding service leaves the saga hung; need per-step timeouts + a saga state store to recover after crashes. (5) **ordering/concurrency** — two users buy the last item; both charge, both try to decrement; still need pessimistic locking or OCC on critical resources even inside a saga.

**Decision framework:** first, can you avoid the distributed transaction entirely by restructuring data boundaries so it happens in one service/DB? ("The best distributed transaction is the one you don't need" — data modelling matters more than transaction patterns.) If you truly need coordination: 2–3 services simple → choreography; 4+/money → orchestration; shared DB → plain ACID; legacy tightly-coupled → 2PC; can tolerate brief inconsistency → eventual + reconciliation. His most-used pattern: orchestrated saga + saga state store + idempotency keys on every action/compensation + 30s step timeouts + a DLQ for total failures + a reconciliation job every 5 minutes to catch what the saga missed.

## Day 20 — Leader Election

Hook: the GitHub 2018 outage — 43 seconds of network trouble, 24 hours of cleanup, because two nodes both believed they were leader. Certain tasks need exactly one node in charge: the write primary (replication, Day 9), the 2PC coordinator (Day 19), the node that runs a scheduled job (you don't want 5 servers sending the same email blast), the holder of a distributed lock (one migration at a time). The hard part isn't picking a leader — it's getting every node to agree on the same one over an unreliable network, and picking a new one fast when the leader dies without two nodes both thinking they're leader.

**Raft** (used over Paxos because it's understandable). Nodes are Leader / Follower / Candidate. Leader sends heartbeats every ~150ms; followers reset an election timer on each. Leader dies → a follower's timer expires (~300ms) → becomes candidate, increments the **term** number, votes for itself, asks others "vote for me for term N." Each node votes for the first candidate it hears in a term (one vote per term). Majority wins → new leader → heartbeats resume. **Why it works:** majority rule — in a 5-node cluster you need 3 votes; two candidates can't both get a majority, so never two leaders in the same term. Split votes resolve via **randomized timeouts** (each waits a random bit and retries; one starts first and wins) — the clever bit that makes Raft practical.

**Split-brain + fencing** (the GitHub failure). Network isolates leader Node A; B–E can't hear its heartbeats, elect Node B; but A is still alive and thinks it's leader → two leaders, both accepting writes, data diverges, brutal to reconcile on heal. Prevention: **fencing token** (each new leader gets a monotonically increasing number; storage rejects any request carrying an old token — the deposed leader's writes just stop working); **STONITH** ("Shoot The Other Node In The Head" — physically power off / network-isolate the old leader; extreme but safe with financial data); **lease-based leadership** (leader holds a time-limited lease it must renew; if partitioned and can't renew, the lease expires and it voluntarily stops accepting writes — what most cloud-native systems use; downside is a brief no-leader window). GitHub's fix: Orchestrator tooling that automates fencing — the old primary can't accept writes once a new one is promoted.

**Tools (don't build it yourself):** **ZooKeeper** (OG; ephemeral nodes — first to create a path is leader; used by older Kafka, Hadoop, HBase, Solr; but it's another distributed system to operate). **etcd** (Raft-based, lighter, better API; Kubernetes' brain — modern default). **Consul** (election + service discovery + health checks + KV; fits the HashiCorp ecosystem). **Redis + Redlock** (simple if you already have Redis, but Kleppmann criticized its guarantees — use for non-critical election like job scheduling, not where split-brain means data loss). **Database-level** (Patroni for Postgres, Orchestrator for MySQL handle primary/replica failover — let the DB do it if that's your only need).

**When you need it:** exactly-one-node tasks, write coordination, distributed locking, coordination decisions (Kafka consumer group coordinator). **When you don't:** stateless API servers behind a load balancer (any server handles any request — the beauty of statelessness), idempotent operations (just let multiple nodes run and dedupe), single-server setups (don't add ZooKeeper to a non-distributed system). Mistakes: custom election from DB locks + timestamps (works 99% of the time; the 1% is your 3am outage); not testing failover; assuming election is instant (there's always a gap where nobody is leader — queue/buffer/retry through it).

## Day 21 — Horizontal vs Vertical Scaling

Hook: Twitter, 2010 World Cup — every goal flooded the servers; the Fail Whale became more famous than the product. Their fix wasn't a bigger server ("you can't buy your way out of that kind of load") — they rebuilt how the system scaled. **Vertical (scale up)** = bigger machine (more CPU/RAM/faster disk). **Horizontal (scale out)** = more machines sharing the work. The constant mistake: teams jump to horizontal (Kubernetes, load balancers, distributed caching, service discovery) for a 5,000-user app — a month of complexity to solve what a bigger server fixes in an afternoon.

**Vertical — the boring option that works longer than you think.** No code/architecture changes, no new failure modes — the app doesn't even know. Modern hardware is absurdly powerful (rent one AWS box with 128 cores + 4TB RAM); most apps never outgrow a single large server. Stack Overflow served billions of pageviews for years on a handful of beefy machines, not thousands of containers. Two ceilings: **physical limit** (there's a biggest machine; then you're stuck) and the **cost curve** (non-linear — the jump from large to biggest is brutal, you pay exponentially near the top). Dealbreaker: a single server is a single point of failure — no amount of scaling up fixes that.

**Horizontal — more power, more problems.** Upsides: near-unlimited scaling (add machines, no ceiling — how Google/Netflix/Amazon operate), **fault tolerance for free** (one dies, the other nine serve; the load balancer stops routing to the dead one — the big one), elastic scaling (50 machines on Black Friday, 10 on Monday, pay for what you use). Complexity you sign up for: **your app must be stateless**; you need a highly-available load balancer (or it's your new SPOF); data consistency gets hard (CAP Day 7, replication Day 9); debugging spans machines (need centralized logging + distributed tracing).

**The stateless requirement** — the make-or-break concept. A stateful server stores the session in its own memory; add a second server + load balancer and the user's next request hits a machine that's never seen them → "please log in again." Same for carts, uploads, in-progress forms, cached data trapped in one machine's memory. Fix: sessions → Redis/DB (not server memory), files → S3 (not local disk), cache → shared cache. Stateless servers become interchangeable — add/remove freely, a dying machine means nothing because it held nothing important. Get this right and scaling out is easy; get it wrong and no number of machines saves you.

**Practical path:** start vertical (simpler, cheaper small-scale, buys time) → **make the app stateless early** (design as if you'll scale out someday — sessions in Redis, files in S3 — costs almost nothing now, saves a painful rewrite; highest-leverage item) → add a read replica before more servers (if the DB is the bottleneck, Day 9) → go horizontal only for a real reason: fault tolerance (justifies it even at modest traffic), hit the vertical ceiling, or elastic cost control. Mental model: best architecture for 1,000 users is usually one good machine; for 10M users it's many machines built incrementally; the mistake is building the 10M architecture at 1,000 users — all the complexity cost, none of the benefit. "Scale up until it hurts. Make it stateless along the way. Then scale out when you have an actual reason to."

## Day 22 — Back-of-the-Envelope Estimation

Hook: the #1 reason candidates fail system design interviews at Google/Amazon/Meta — designing for the wrong scale (sharding + queues for 100 requests/day, or one database for 50K writes/s). "They never did the math. Five minutes of estimation would have told them exactly what to build." Architecture is a *response* to scale; a 1,000-user system and a 100M-user system aren't the same system built bigger — they're fundamentally different designs. You need the right order of magnitude, not exact figures.

**Numbers to memorize.** Latency: RAM ~100ns; SSD ~100µs (1,000× slower); HDD ~10ms (100× slower than SSD); same-DC round trip ~500µs; cross-world round trip ~150ms. (Takeaway: memory fast, disk slow, cross-region network slowest — why caching and nearby-region serving matter.) Time: 1 day ≈ 86,400s, **round to 100,000 (10⁵)**; 1M seconds ≈ 12 days; 1B seconds ≈ 31 years. Storage: a text record (tweet, profile row) ≈ 1 KB; a compressed photo a few hundred KB–few MB; a minute of video ~10–50 MB. Throughput: one well-tuned server ≈ 1,000–10,000 simple req/s; one database ≈ 1,000s of writes/s before you must scale it.

**The method (repeatable):** (1) start with users — total and DAU; state assumptions out loud. (2) users → req/s: DAU × actions/day ÷ ~100,000s. Example: 10M DAU × 10 req = 100M/day ÷ 100,000 = **1,000 req/s** average. (3) account for peak — **peak ≈ 2–3× average** (some spike higher); design for peak, so ~3,000 req/s. (4) storage — data/action × volume × retention. Example: 100M req/day × 1 KB = 100 GB/day = **36 TB/year**. (5) bandwidth — req/s × response size (tells you if you need a CDN). (6) read the numbers → decide architecture; low numbers → one server + DB; high → justify caching, sharding, queues, replication. The estimation drives the design, not the reverse.

**Worked example — URL shortener (Bitly).** Assume 100M new URLs/month. Writes: ÷ ~2.5M s/month ≈ **40 writes/s** (one DB handles it, no write sharding). Reads: read-heavy, ~100:1 ratio → **4,000 reads/s** (caching + read replicas matter). Storage: 100M × ~500 bytes = 50 GB/month ≈ 600 GB/year ≈ 3 TB over 5 years (single large disk + replication for safety, no sharding for size). Bandwidth: low, but a few hot links get most clicks → perfect Redis caching. **Architecture the numbers point to:** simple write path (one DB + replicas), Redis in front for reads, replication not sharding, no queue, no complex distributed system. The estimation saved you from over-engineering — a candidate who skips it proposes sharding and Kafka for something that runs on one DB and a cache.

**Using it:** in interviews, estimate out loud early (right after clarifying requirements, before drawing a box) — shows you think about scale before tools; state assumptions ("assuming 10M DAU — right, or a different scale?"); round aggressively (86,400 → 100,000; powers of ten); let numbers drive design ("4,000 reads/s with a hot-key pattern, so Redis here"). In real work: estimate before adopting any new infrastructure (staggering how many teams run Kafka for what a DB queue handles); capacity planning is just estimation with real numbers — provision before launch, not at 2am. "Architecture should be a conclusion you reach from the numbers, not an assumption you start with."

## Days 23–25 — The Finale (full text captured)

[Long-form post](https://x.com/system_monarch/status/2076617189508141438).

### Concept 23: Idempotency

Story: customer taps "Pay ₹4,999"; network stutters; phone retries correctly; **naive backend charges ₹9,998 — and nobody wrote a bug.** Every component did its job. **Duplicate delivery is not an edge case; it is the default behaviour of every distributed system** — client retries, LB re-routes still-running requests, queues deliver at-least-once, users double-click.

- Definition: an operation is idempotent if doing it N times = doing it once. "Set balance to ₹5,000" is naturally idempotent; "add ₹500" is a landmine.
- **Idempotency keys** — when you can't rewrite the op: client generates one key **per operation (not per request)** — a retry carries the same key; a second purchase carries a new one. Server: key new → process + store key **with its result**; key seen → skip work, replay stored result. This is Stripe's contractual mechanism.
- Production sharp edges (author was cut by all): the key store must be **shared** (Redis or a DB table with a unique constraint — server-local memory is useless behind an LB) and fast (it's on the hot path of every write); store the **result**, not just the key (the retry caller still needs the real response); the **in-flight collision** (retry arrives while the original is mid-flight — a check-then-write has a race; the DB unique constraint catches it: second insert fails → wait for/fetch the first result); keys should **expire** (24–48h covers real retries).
- The mindset shift: stop believing anything happens exactly once — design so it doesn't matter how many times a thing arrives. "At-least-once-but-safe" thinking marks the engineer who's been burned enough to know better.

### Concept 24: Data Locality

Almost every performance problem reduces to: **how far does the data travel to reach the compute?** The latency ladder: CPU cache ~1ns → RAM ~100ns → local SSD ~100µs → same-datacenter service ~500µs → cross-region ~150ms. **Cross-region is over a million times slower than CPU cache — for the same byte.** Slow systems usually aren't computing hard; the CPU is idle, waiting for far-away data.

Three levers:
1. **Co-locate data used together** — the deep reason shard keys matter: good key = account page is one trip to one shard; bad key = scatter-gather held hostage by the slowest node. Data that lives and dies together should sit together.
2. **Move compute to data** — don't ship a billion rows to the app to keep fifty; push filtering/aggregation into the database. Big-data frameworks schedule compute on the machine holding the data block — same principle. The code is small; the data is enormous.
3. **Serve from the edge** — Mumbai→Virginia = ~200ms on the wire before any work happens; no backend optimization claws that back. CDNs + regional replicas collapse it to ~10ms. "Closeness, on the latency ladder, is speed."

Caching, sharding, CDNs, and replication are one idea at different layers: **shorten the distance between the question and the answer.**

### Concept 25: The One Idea Behind All 25

The 25 concepts aren't flashcards — they're answers to the 3 questions (the core thesis at the top of Part II). The single sentence: **every architecture decision is a trade-off between speed, cost, and correctness.** Strong consistency costs latency; low latency costs consistency; fault tolerance costs money and complexity; caching buys speed and risks staleness. "It depends" is the *correct* answer — the skill is knowing what it depends on. Junior: "what's the best database?" Senior: "what does this workload need, and what am I willing to trade to get it?" The patterns are vocabulary; judgment — built by shipping, breaking, and paying attention — is the craft.

---

## Full series index

| Day | Topic | Status | Link |
|-----|-------|--------|------|
| 1 | Load Balancing | ✅ full | [x.com](https://x.com/system_monarch/status/2064978857263014366) |
| 2 | CDN | ✅ full | [x.com](https://x.com/system_monarch/status/2065333515601039611) |
| 3 | Caching (5 layers) | ✅ full | [x.com](https://x.com/system_monarch/status/2065682623125684250) |
| 4 | Cache Invalidation | ✅ full | [x.com](https://x.com/system_monarch/status/2066122714738487723) |
| 5 | Rate Limiting | ✅ full | [x.com](https://x.com/system_monarch/status/2066523714540487134) |
| 6 | API Gateway | ✅ full | [x.com](https://x.com/system_monarch/status/2066872078763282840) |
| 7 | CAP Theorem | ✅ full | [x.com](https://x.com/system_monarch/status/2067237126027264501) |
| 8 | Sharding | ✅ full | [x.com](https://x.com/system_monarch/status/2067617707336569054) |
| 9 | Replication | ✅ full | [x.com](https://x.com/system_monarch/status/2067990359523967241) |
| 10 | Partitioning | ✅ full | [x.com](https://x.com/system_monarch/status/2068372408940302686) |
| 11 | Queues | ✅ full | [x.com](https://x.com/system_monarch/status/2068609322691743937) |
| 12 | Microservices | ✅ full | [x.com](https://x.com/system_monarch/status/2069105320245518534) |
| 13 | Monolith vs Microservices | ✅ full | [x.com](https://x.com/system_monarch/status/2069412442556543421) |
| 14 | Fault Tolerance | ✅ full | [x.com](https://x.com/system_monarch/status/2069734817256935765) |
| 15 | Database Scaling | ✅ full | [x.com](https://x.com/system_monarch/status/2070140401991004434) |
| 16 | Service Discovery | ✅ full | [x.com](https://x.com/system_monarch/status/2070395468719460626) |
| 17 | Consistency Models | ✅ full | [x.com](https://x.com/system_monarch/status/2071608406465732608) |
| 18 | Eventual Consistency | ✅ full | [x.com](https://x.com/system_monarch/status/2071998232620843438) |
| 19 | Distributed Transactions | ✅ full | [x.com](https://x.com/system_monarch/status/2072312002098377098) |
| 20 | Leader Election | ✅ full | [x.com](https://x.com/system_monarch/status/2073626230109426156) |
| 21 | Horizontal vs Vertical Scaling | ✅ full | [x.com](https://x.com/system_monarch/status/2074532999929176098) |
| 22 | Back-of-the-Envelope Estimation | ✅ full | [x.com](https://x.com/system_monarch/status/2075930028995432770) |
| 23–25 | Idempotency · Data Locality · The One Idea | ✅ full | [x.com](https://x.com/system_monarch/status/2076617189508141438) |

**All 25 days now captured in full** (17–18 recovered from the X Articles, 19–22 from the full threads, via a logged-in browser session). The author's paid guide (90+ fundamentals, $72) is at puneetpatwari.in for the remaining ~65 concepts beyond this series.

---

# PART III — SUPPLEMENTARY REFERENCE (added to complete the roadmap)

> These sections were added on top of the source threads to turn the collection into a complete, self-contained roadmap. They're standard, field-accepted interview material — not attributed to the original threads.

## DSA pattern catalog

The 13 starter problems teach patterns; here is the full catalog. Most interview questions are one of these in disguise — learn to recognize the disguise. Practice ~8–12 problems per pattern until recognition is instant, then a mixed set to practice *identifying* the pattern under pressure.

| Pattern | Signal it's this | Typical complexity | Example problems |
|---|---|---|---|
| Two pointers | Sorted array / pair-or-triplet / in-place | O(n) | Valid Palindrome, 3Sum, Container With Most Water |
| Sliding window | Contiguous subarray/substring, "longest/shortest such that" | O(n) | Longest Substring w/o Repeat, Min Window Substring |
| Fast & slow pointers | Cycle detection, middle of list | O(n) | Linked List Cycle, Happy Number |
| Hash map / set | "Have I seen this?", frequency, complement lookup | O(n) | Two Sum, Group Anagrams, Contains Duplicate |
| Binary search | Sorted, or "minimize/maximize a monotonic value" | O(log n) | Search in Rotated Array, Koko Eating Bananas |
| Stack / monotonic stack | Matching pairs, "next greater/smaller element" | O(n) | Valid Parentheses, Daily Temperatures |
| Heap / top-K | "K largest/smallest/most frequent", streaming median | O(n log k) | Kth Largest, Merge K Lists, Top K Frequent |
| Intervals | Overlaps, merges, scheduling | O(n log n) | Merge Intervals, Meeting Rooms |
| BFS | Shortest path (unweighted), level-order | O(V+E) | Level Order, Rotting Oranges, Word Ladder |
| DFS / backtracking | All paths, permutations/combinations, connected components | O(V+E) / exp. | Number of Islands, Subsets, N-Queens |
| Topological sort | Ordering with dependencies, cycle detection in a DAG | O(V+E) | Course Schedule, Alien Dictionary |
| Dynamic programming | "Count ways / min-max / can you reach", overlapping subproblems | O(n·m) | Climbing Stairs, Coin Change, LCS, Edit Distance |
| Greedy | Local optimum → global; interval/scheduling choices | O(n log n) | Jump Game, Gas Station, Task Scheduler |
| Union-Find (DSU) | Dynamic connectivity, grouping | ~O(α(n)) | Number of Provinces, Redundant Connection |
| Trie | Prefix search, autocomplete, word dictionaries | O(len) | Implement Trie, Word Search II |
| Bit manipulation | "Without extra space", XOR tricks, bitmask subsets | O(n) | Single Number, Counting Bits |

## Big-O cheat sheet

**Data-structure operations (average case):**

| Structure | Access | Search | Insert | Delete | Notes |
|---|---|---|---|---|---|
| Array | O(1) | O(n) | O(n) | O(n) | insert/delete at end O(1) amortized |
| Hash table | — | O(1) | O(1) | O(1) | O(n) worst case on collisions |
| Linked list | O(n) | O(n) | O(1) | O(1) | O(1) given the node |
| Stack / Queue | O(n) | O(n) | O(1) | O(1) | push/pop/enqueue/dequeue O(1) |
| Balanced BST | O(log n) | O(log n) | O(log n) | O(log n) | O(n) if unbalanced |
| Heap | O(1) peek | O(n) | O(log n) | O(log n) | build-heap O(n) |
| Trie | O(L) | O(L) | O(L) | O(L) | L = key length |

**Sorting:** Quick — avg O(n log n), worst O(n²), O(log n) space, unstable. Merge — O(n log n) always, O(n) space, stable. Heap — O(n log n) always, O(1) space, unstable. Insertion — O(n) best, O(n²) avg, stable. Counting/Radix — O(n+k), stable.

**Latency numbers:** RAM ~100 ns · SSD ~100 µs (1,000× slower) · HDD ~10 ms · same-datacenter round trip ~500 µs · **cross-region round trip ~150 ms** (over a million times slower than CPU cache — why caching and nearby-region serving matter).

## SQL essentials

- **Joins:** INNER (match both), LEFT (all left + matches), RIGHT, FULL OUTER, CROSS, SELF. Know exactly which rows each returns and where NULLs appear.
- **Aggregation:** `GROUP BY` + `HAVING` (filter groups) vs `WHERE` (filter rows). The classic bug is filtering an aggregate in WHERE.
- **Window functions:** `ROW_NUMBER()`, `RANK()`, `DENSE_RANK()`, `LAG/LEAD`, running totals via `SUM() OVER (...)`. The tool for "top N per group" and "compare to previous row."
- **Subqueries & CTEs:** correlated vs non-correlated; `WITH` CTEs for readability; recursive CTEs for hierarchies/graphs.
- **Indexing in practice:** B-Tree indexes, composite-index column order (equality first, range last), covering indexes; `LIKE '%x'` and functions on columns kill index use.
- **Transactions & isolation:** ACID, isolation levels (Read Committed → Serializable) and the anomalies each prevents; deadlocks and lock ordering.
- **Classic asks:** second-highest salary (window fn or correlated subquery); Nth-highest per department (`ROW_NUMBER() OVER (PARTITION BY dept ORDER BY salary DESC)`); find duplicates (`GROUP BY ... HAVING COUNT(*) > 1`); running total (SUM window).

## How to answer any system-design question (the 6-step template)

Interviewers grade your **questions and trade-offs** as much as your boxes. Never jump to "Kafka + Cassandra" before the numbers justify it.

1. **Clarify requirements & scope** — functional and non-functional (scale, latency, consistency, availability). Nail the read/write ratio.
2. **Estimate** — DAU × actions/day ÷ 86,400 → QPS; peak ≈ 2–3× average; storage = rows × size × retention. Do the math out loud; designing for the wrong scale is the #1 failure.
3. **Define the API** — a few endpoints/signatures to ground the abstract.
4. **High-level design** — client → LB → services → cache → DB → queue; draw boxes and data flow.
5. **Deep-dive 1–2 components** — the datastore choice, the hot path, the consistency boundary. Show the trade-off.
6. **Address bottlenecks & failures** — apply the patterns (caching, sharding, replication, queues, circuit breakers, idempotency). What happens when a node dies?

**Consistency is per-feature, not per-system.** An e-commerce app is AP for catalogue/reviews, CP for last-item inventory and payments — same system, different guarantees.

## Mini case studies

- **URL shortener (Bitly):** 100M URLs/mo → ~40 writes/s (one DB, no write sharding); read-heavy ~100:1 → ~4,000 reads/s → Redis + read replicas; ~3 TB over 5 years fits one disk + replication. No queue, no sharding. Base62 codes or hash+counter. The estimation is what prevents over-engineering.
- **Rate limiter:** token bucket in Redis (atomic INCR + TTL); counters must be shared, not per-instance; return 429 with `Retry-After`; clients back off with jitter; layer gateway + per-user + per-endpoint; whitelist health checks.
- **Chat / messaging:** WebSockets for realtime; a queue for fan-out & offline delivery; shard by `conversation_id`; at-least-once delivery + idempotent client dedup; presence/read-receipts via pub/sub.
- **News feed (Twitter timeline):** fan-out on write for most users vs fan-out on read for celebrities (hybrid — the celebrity problem is the whole interview); cache timelines in Redis; cursor pagination.

## Behavioral & the STAR method

Technical skill gets you the loop; behavioral answers decide the offer and the level. **STAR:** Situation (context, 1–2 sentences) → Task (your responsibility) → Action (what *you* did — "I", not "we") → Result (quantified outcome). Prepare 6–8 flexible stories:

- A project you're proud of · a conflict you resolved · a failure you caused and learned from · a decision you disagreed with (data over ego) · a hard trade-off under constraints · leading/influencing without authority · handling ambiguity · your most impactful ship (with metrics).

Quantify results ("cut p99 from 800ms to 120ms", "reduced infra cost 40%"). Own failures without blaming. Map a story to each of the company's engineering values. Always bring 2–3 sharp questions to ask them — it signals seniority.

## Interview-day tactics

- **Coding:** restate & confirm examples → clarify constraints/edge cases → brute force → optimize out loud → narrate as you code → test with a small example → state complexity. Silence is the enemy.
- **System design:** drive the 6 steps; ask questions constantly; state trade-offs explicitly; it's a conversation, not a monologue.
- **When stuck:** think out loud, solve a simpler version, ask a clarifying question, reason from a known pattern. Interviewers can only credit reasoning they can hear.
- **Communication:** headline → detail → trade-off; manage time; take hints gracefully; stay calm — it's a collaboration.

## Resume, portfolio & profile

- **Resume:** one page; impact bullets ("verb + what + measurable result"); ATS/recruiter keywords (your stack, "Full Stack", "REST", "microservices", "system design"); few fonts, no photo, no skill bars.
- **Portfolio:** your passport — who you are + 2–3 best projects with live links + GitHub, responsive and fast. Quality over quantity.
- **GitHub:** pin best repos, real READMEs, clean history — used from day one.
- **Network:** skills without connections reach no one; be active where engineers are, write about what you build; referrals beat cold applications by an order of magnitude.

## Glossary (one line each)

- **Idempotency** — doing an operation N times = doing it once.
- **CAP** — under a network partition, choose consistency or availability.
- **ACID** — Atomicity, Consistency, Isolation, Durability.
- **Consistent hashing** — ring hashing so adding a node remaps only ~1/N of keys.
- **Backpressure** — signaling upstream to slow down when a consumer can't keep up.
- **Circuit breaker** — stop calling a failing dependency so it can recover; fail fast.
- **Saga** — distributed transaction as local steps + compensating undo actions.
- **Dead-letter queue** — where poison messages go after N failed attempts.
- **P99 latency** — the slowest 1% of requests, where user pain lives.
- **CRDT** — data type that merges concurrent edits without conflicts, by construction.
- **Split-brain** — two nodes both believing they're leader, both accepting writes.
- **Fencing token** — monotonic number letting storage reject a deposed leader's writes.

**HTTP status codes:** 200/201/204 success · 301/304 redirect/cache · 400 bad input · 401 not authenticated · 403 authenticated-but-forbidden · 404 not found · 409 conflict · 429 rate-limited (send `Retry-After`) · 500 server error · 503 unavailable/overloaded.

---

*This document has a companion interactive HTML edition: [`interview-prep-guide.html`](interview-prep-guide.html).*
