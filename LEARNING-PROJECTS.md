# Ground-Up Learning Projects (Java) — ~100 Builds to Truly Understand SD, DSA & Architecture
**For a 15-yr developer who used the abstractions but never built what's underneath.**

> The honest premise: after 15 years *using* `HashMap`, HikariCP, Kafka, Postgres, and Spring, you can wire systems together — but the **internals** (why resize doubles, how MVCC works, what a consensus log actually does) stay fuzzy because you never built them. These projects fix that. Each one is **"build X from scratch to finally understand Y."** Interview-readiness is a *side effect* of real understanding, not the goal.

---

## How to use this doc

- **This is NOT the 8-week interview sprint.** This is a 6–18 month ground-up journey. Go **depth-first on your weakest track**, not breadth-first.
- **Build from scratch, then compare to the real thing.** The learning is in the gap between your toy and `java.util.concurrent` / Postgres / Kafka.
- **Effort key:** 🟢 a few hours · 🟡 1–2 days · 🟠 3–5 days · 🔴 1–2 weeks (deep).
- **"Definition of understanding" per project:** you can (1) explain the core trade-off, (2) predict where it breaks at 10×, (3) name what real system uses this and why. If you can't, you copied — go break it on purpose.
- **Suggested order** (dependencies build up): Track 1 → 2 → 3 → 4/5 → 6 → 7/8 → 9 → 10/11/12. Tracks 4–6 are where most "I don't really get distributed systems" pain lives — that's your highest-value zone.

### Pick your starting point (self-diagnosis)

| If you feel weak on… | Start with track |
|---|---|
| "I use data structures but couldn't implement them" | **3** (DS from scratch), **1** (foundations) |
| "Threads/locks scare me" | **2** (concurrency) |
| "I treat the DB as a black box" | **4** (DB internals) |
| "Distributed systems are hand-wavy to me" | **6** (distributed) — the big gap for most devs |
| "I can't reason about latency/throughput" | **12** (performance), **5** (networking) |
| "I architect by copying patterns I don't fully grasp" | **9** (architecture), **8** (messaging) |

---

## Track 1 — Computer Systems Foundations (the things you `import` but never built)

**P1 · Build a HashMap** 🟢
Teaches: collisions, load factor, amortized O(1), rehashing.
Build: separate-chaining *and* open-addressing variants; resize on load factor; benchmark vs `java.util.HashMap`.
Aha: *why a bad `hashCode()` degrades to O(n), and why resize must rehash every entry.*

**P2 · Build a dynamic array (ArrayList)** 🟢
Teaches: amortized analysis, growth factor.
Build: grow by 1.5×/2×; count copies; prove amortized O(1) append.
Aha: *why doubling gives O(1) amortized but growing by +1 gives O(n).*

**P3 · Build a thread pool (ExecutorService)** 🟡
Teaches: worker threads, task queue, lifecycle, rejection policies.
Build: fixed pool over a `BlockingQueue`; add core/max/keep-alive; shutdown semantics.
Aha: *why an unbounded queue silently turns a "max threads" pool into a memory leak.*

**P4 · Build a memory allocator / object pool** 🟡
Teaches: fragmentation, free lists, allocation cost.
Build: a slab/free-list allocator over a big `byte[]`; measure fragmentation.
Aha: *why pooling helps GC pressure and why fragmentation wastes memory you "freed".*

**P5 · Build a stack-machine bytecode interpreter** 🟡
Teaches: how a VM executes instructions.
Build: a tiny instruction set (PUSH/ADD/JMP/CALL) + an interpreter loop.
Aha: *what the JVM is actually doing under your `.class` files.*

**P6 · Build a GC simulator (ref-counting + mark-sweep)** 🟠
Teaches: reachability, cycles, stop-the-world.
Build: object graph + both collectors; show ref-counting leaks cycles, mark-sweep doesn't.
Aha: *why GC pauses exist and why generational GC bets on "most objects die young."*

**P7 · Build a regex engine (Thompson NFA)** 🟠
Teaches: finite automata, the theory behind `Pattern`.
Build: parse → NFA → simulate; support `* + ? | ()`.
Aha: *why naive backtracking regex can blow up to exponential time (ReDoS).*

**P8 · Build a JSON parser (recursive descent)** 🟢
Teaches: tokenizing + parsing.
Build: lexer + recursive-descent parser to a tree; handle escapes/numbers.
Aha: *parsing is a solved, mechanical pattern — not magic.*

---

## Track 2 — Concurrency & Parallelism (you use it shallowly; go deep)

**P9 · Producer-consumer, three ways** 🟡
Build: `wait/notify` → `BlockingQueue` → lock-free ring buffer. Same problem, three abstraction levels; benchmark all.
Aha: *what `BlockingQueue` hides, and the cost it pays for safety.*

**P10 · Build a ReentrantLock with CAS** 🟡
Teaches: AQS concepts, spinning vs parking.
Build: a lock from `AtomicInteger` + a wait queue.
Aha: *what `synchronized` and `ReentrantLock` actually do at the bytecode/CAS level.*

**P11 · Build a read-write lock** 🟡
Build: reader count + writer flag; then add write-preference to avoid writer starvation.
Aha: *the reader-vs-writer fairness trade-off you inherit from `ReentrantReadWriteLock`.*

**P12 · Lock-free stack & queue (Treiber / Michael-Scott)** 🟠
Teaches: CAS, the ABA problem.
Build: both with `AtomicReference`; reproduce ABA, fix with version stamps.
Aha: *why lock-free ≠ wait-free, and why ABA is a real footgun.*

**P13 · Striped / sharded lock cache** 🟢
Build: thread-safe map with N lock stripes; compare throughput vs one global lock.
Aha: *why `ConcurrentHashMap` shards locks — contention is the enemy, not locking.*

**P14 · Implement the actor model** 🟠
Build: actors with mailboxes + a dispatcher thread pool; no shared mutable state.
Aha: *how "share nothing, message everything" sidesteps locks entirely (Akka/Erlang).*

**P15 · False sharing demo + fix** 🟢
Build: two counters on the same cache line; measure; fix with padding/`@Contended`; remeasure with JMH.
Aha: *the CPU cache line (64B) is a real performance boundary you can't see in source.*

**P16 · Fork-join parallel merge sort** 🟡
Build: `RecursiveTask` merge sort; measure speedup vs core count; find the sequential-cutoff sweet spot.
Aha: *Amdahl's law in practice — why 8 cores ≠ 8× speedup.*

**P17 · Scatter-gather with CompletableFuture** 🟢
Build: fan out N async calls, combine results, add timeout + fallback.
Aha: *how to compose async without callback hell, and why timeouts are mandatory.*

**P18 · Semaphore-based rate limiter & bulkhead** 🟢
Build: bound concurrency with a `Semaphore`; isolate two workloads into separate permit pools.
Aha: *bulkheading — one slow dependency shouldn't sink the whole service.*

---

## Track 3 — Data Structures From Scratch (the ones behind real systems)

**P19 · Skip list** 🟡 — probabilistic balancing. *Used in Redis sorted sets.* Aha: *randomized O(log n) without rebalancing rotations.*
**P20 · B-tree / B+tree** 🟠 — disk-friendly fan-out. *The structure behind every SQL index.* Aha: *why databases use B+trees, not binary trees — disk pages, not RAM nodes.*
**P21 · LSM tree + SSTables** 🟠 — write-optimized storage. *RocksDB/Cassandra.* Aha: *trade reads for writes; compaction is the hidden cost.*
**P22 · Trie + radix/Patricia tree** 🟡 — prefix search, IP routing tables. Aha: *why autocomplete and routers love tries.*
**P23 · Bloom filter + counting Bloom** 🟢 — probabilistic membership. Aha: *"definitely not / maybe yes" — and how false-positive rate trades off with bits.*
**P24 · HyperLogLog** 🟡 — cardinality estimation. Aha: *the math that lets `COUNT DISTINCT` over billions use ~1.5KB.*
**P25 · Count-Min Sketch** 🟡 — stream frequency estimation. Aha: *approximate heavy-hitters in fixed memory.*
**P26 · Fenwick + segment tree** 🟡 — range queries/updates in O(log n). Aha: *prefix-sum tricks that turn O(n) scans into O(log n).*
**P27 · Red-black or AVL tree** 🟠 — self-balancing. *Behind `TreeMap`.* Aha: *what rotations buy you and why RB trees beat AVL for write-heavy.*
**P28 · Binary + Fibonacci heap** 🟡 — priority queues; powers Dijkstra. Aha: *why decrease-key matters for graph algorithms.*
**P29 · Union-Find with path compression** 🟢 — near-O(1) connectivity. Aha: *the almost-constant inverse-Ackermann magic.*
**P30 · Merkle tree** 🟡 — *git, blockchains, Dynamo anti-entropy.* Aha: *how you diff two huge datasets by exchanging a few hashes.*
**P31 · Consistent hashing ring + virtual nodes** 🟡 — *Dynamo, every sharded cache.* Aha: *why modulo-N hashing is catastrophic when N changes, and how vnodes balance load.*
**P32 · Rope (text-editor structure)** 🟡 — O(log n) insert in huge strings. Aha: *how editors handle million-line files without copying.*

---

## Track 4 — Database Internals (stop treating the DB as a black box)

**P33 · KV store with a Write-Ahead Log** 🟡
Build: append to WAL before applying; crash mid-write; replay WAL on restart.
Aha: *durability = "write the intent before the action"; this is how every DB survives a crash.*

**P34 · On-disk B+tree index** 🟠
Build: fixed-size pages on disk, node split/merge, point + range lookup.
Aha: *why index height stays ~3–4 even for billions of rows.*

**P35 · LSM-based KV store** 🔴
Build: memtable (skip list) → flush to SSTable → background compaction; add a Bloom filter per SSTable.
Aha: *the read-amplification problem LSMs trade for write speed — and how Bloom filters rescue reads.*

**P36 · MVCC + snapshot isolation** 🟠
Build: row versions with txn timestamps; readers never block writers.
Aha: *how Postgres gives you consistent reads without read locks — and where bloat comes from.*

**P37 · SQL parser + executor** 🟠
Build: parse `SELECT … WHERE … JOIN`; execute with nested-loop + hash join.
Aha: *a query is just a tree of operators pulling rows.*

**P38 · Cost-based query planner** 🟠
Build: estimate cardinality; choose index-scan vs seq-scan vs join order by cost.
Aha: *why `EXPLAIN ANALYZE` sometimes picks a seq scan on purpose.*

**P39 · Buffer pool / page cache** 🟡
Build: fixed-size frame pool with LRU/clock eviction + dirty-page flush.
Aha: *the DB is mostly a cache manager; this is what `shared_buffers` is.*

**P40 · Two-phase locking + deadlock detection** 🟡
Build: lock manager + wait-for graph cycle detection; victim selection.
Aha: *how isolation is enforced and why deadlocks are inevitable, not bugs.*

**P41 · Primary-replica log shipping** 🟡
Build: stream the WAL to a replica; apply in order; measure replication lag.
Aha: *async replication = the read-your-writes consistency gap you keep hitting.*

**P42 · Columnar store + RLE/dictionary encoding** 🟡
Build: store columns separately; compress; run an aggregate scan; compare to row store.
Aha: *why OLAP (analytics) wants columns and OLTP wants rows.*

---

## Track 5 — Networking & Protocols (what's under HTTP and Netty)

**P43 · HTTP/1.1 server from raw TCP sockets** 🟡 — parse request line/headers, keep-alive, chunked. Aha: *HTTP is just text over TCP; frameworks hide a simple protocol.*
**P44 · Reactor server with Java NIO Selector** 🟠 — one thread, many connections. Aha: *how Netty/Redis serve 100k connections without 100k threads.*
**P45 · WebSocket server from scratch** 🟡 — handshake (upgrade) + frame parsing/masking. Aha: *what `@ServerEndpoint` does under the hood.*
**P46 · Length-prefixed binary RPC framework** 🟠 — framing + serialization + request/response correlation. Aha: *why message framing exists (TCP is a byte stream, not messages).*
**P47 · Single-threaded event loop** 🟡 — task queue + timers + I/O readiness. Aha: *the Node.js/Redis model and why "don't block the event loop."*
**P48 · Connection pool from scratch** 🟡 — borrow/return, max size, validation, leak detection. Aha: *exactly what HikariCP manages and why pool exhaustion cascades.*
**P49 · Varint + protobuf-style wire format** 🟢 — variable-length integers + tag-length-value. Aha: *how Protobuf/Kafka pack bytes efficiently.*
**P50 · Gossip / epidemic membership protocol** 🟠 — nodes randomly exchange state. Aha: *how clusters track membership without a central registry (Cassandra/Serf).*
**P51 · Phi-accrual failure detector** 🟡 — adaptive "is it dead?" from heartbeat history. Aha: *why fixed timeouts are wrong and how Cassandra/Akka decide a node is down.*

---

## Track 6 — Distributed Systems (the biggest gap for most 15-yr devs)

**P52 · Lamport + vector clocks** 🟡
Build: track causality across simulated nodes; detect concurrent vs causal events.
Aha: *"there is no global now" — ordering in distributed systems is logical, not wall-clock.*

**P53 · Leader election: Bully → Raft-style** 🟡
Build: Bully algorithm, then term-based election with timeouts.
Aha: *split votes, randomized timeouts, why exactly one leader per term.*

**P54 · Raft consensus (log replication + elections)** 🔴
Build: the full thing — leader, log replication, commit index, safety. The capstone.
Aha: *how a cluster agrees on an ordered log despite crashes — the foundation of etcd/Consul/Kafka-Raft.*

**P55 · Distributed lock with fencing tokens** 🟡
Build: lock via a store + monotonic fencing token; show why a lock without fencing is unsafe.
Aha: *the famous "GC pause breaks your distributed lock" problem and the fix.*

**P56 · Two-phase commit (and feel it block)** 🟡
Build: coordinator + participants; kill the coordinator after prepare; watch participants hang.
Aha: *why 2PC is correct but not available — the motivation for sagas.*

**P57 · Quorum reads/writes (Dynamo R+W>N)** 🟡
Build: N replicas, tunable R/W; show stale reads when R+W≤N.
Aha: *consistency is a dial (R/W), not a switch.*

**P58 · CRDTs: G-Counter, PN-Counter, OR-Set** 🟠
Build: replicas that merge concurrent updates with no conflicts.
Aha: *how collaborative apps (and Riak/Redis CRDTs) merge offline edits deterministically.*

**P59 · Anti-entropy with Merkle trees** 🟡
Build: two diverged replicas reconcile by exchanging Merkle hashes.
Aha: *how Cassandra repairs replicas cheaply (reuses P30).*

**P60 · Eventually consistent KV store (vector-clock conflict detection)** 🟠
Build: combine P31 (hashing) + P52 (clocks) + P57 (quorum) into a mini-Dynamo.
Aha: *integrating the pieces is the real lesson — this is a Dynamo paper in miniature.*

**P61 · Chandy-Lamport snapshot** 🟡
Build: capture a consistent global state of a running distributed computation.
Aha: *how Flink checkpoints / distributed debuggers get a coherent snapshot without stopping the world.*

**P62 · Sharded counter with hotspot mitigation** 🟢
Build: split a hot counter into N sub-counters; sum on read.
Aha: *write-hotspot sharding — the trick behind high-write counters/likes.*

---

## Track 7 — Caching & Storage Systems

**P63 · LRU vs LFU vs ARC vs 2Q** 🟡 — implement all four; replay a real access trace; compare hit rates. Aha: *no single eviction policy wins; ARC adapts (used in ZFS).* 
**P64 · Write-through / write-back / write-around** 🟢 — implement + measure read/write latency and consistency. Aha: *the durability-vs-speed knob behind every cache.*
**P65 · Cache stampede protection (singleflight)** 🟢 — coalesce concurrent misses into one backend call. Aha: *how one expired hot key can take down your DB, and the fix.*
**P66 · Edge cache with stale-while-revalidate** 🟡 — TTL + serve-stale + async refresh. Aha: *the CDN trick that hides origin latency.*
**P67 · Content-addressable store (dedup by hash)** 🟡 — store blobs keyed by content hash. Aha: *how git/Dropbox/Docker layers dedup identical content for free.*
**P68 · Object store with chunking + erasure coding basics** 🟠 — split, replicate vs Reed-Solomon. Aha: *why S3 uses erasure coding (durability without 3× storage cost).*

---

## Track 8 — Messaging & Streaming

**P69 · Mini message broker (Kafka-lite)** 🔴
Build: topics, partitions, append-only segment logs, offsets, consumer groups, rebalancing.
Aha: *Kafka is "a distributed append-only log" — once you build it, Kafka stops being magic.*

**P70 · Exactly-once via idempotent producer + dedup** 🟡
Build: producer sequence numbers + broker dedup; show at-least-once → effectively-once.
Aha: *"exactly once" is really "at-least-once delivery + idempotent processing."*

**P71 · Stream processor with windows** 🟠
Build: tumbling/sliding/session windows over an event stream; handle late events with watermarks.
Aha: *event-time vs processing-time — the core hard problem in streaming.*

**P72 · Change Data Capture (CDC) from a WAL** 🟡
Build: tail your P33 WAL, emit change events.
Aha: *how Debezium turns DB changes into a stream — the outbox alternative.*

**P73 · Backpressure-aware pipeline** 🟡
Build: bounded buffers + demand signaling (Reactive Streams semantics) end to end.
Aha: *why a fast producer + slow consumer = OOM without backpressure.*

**P74 · DLQ + retry with exponential backoff + jitter** 🟢
Build: retry topic, max attempts, dead-letter, jittered backoff.
Aha: *why jitter prevents synchronized retry storms (thundering herd).*

**P75 · Transactional outbox** 🟡
Build: write event + business data in one DB txn; relay polls outbox → broker.
Aha: *the standard fix for "DB committed but the event was lost" dual-write problem.*

---

## Track 9 — Architecture & Design Patterns (build the judgment, not the buzzwords)

**P76 · Event sourcing + CQRS ledger** 🟠 — append events, project read model, rebuild state by replay. Aha: *state is a fold over an event log; the audit trail is free.*
**P77 · Saga: choreography vs orchestration** 🟠 — order/inventory/payment with compensations; build both. Aha: *the two ways to coordinate without 2PC, and their failure modes.*
**P78 · Hexagonal / ports-and-adapters refactor** 🟡 — take a service, isolate domain from I/O. Aha: *why testable cores have no framework imports.*
**P79 · API gateway** 🟠 — routing, auth, rate limit, request aggregation, response caching. Aha: *the cross-cutting concerns that don't belong in services.*
**P80 · Sidecar proxy (mesh data-plane basics)** 🟠 — transparent retries/mTLS/metrics beside a service. Aha: *what Istio/Envoy's sidecar actually does for you.*
**P81 · Plugin architecture (SPI + classloaders)** 🟡 — load implementations at runtime. Aha: *how extensible platforms (IDEs, Kafka Connect) isolate plugins.*
**P82 · Feature-flag system with targeting + gradual rollout** 🟡 — % rollouts, user targeting, kill switch. Aha: *decouple deploy from release; the basis of safe continuous delivery.*
**P83 · Workflow / state-machine engine** 🟠 — durable, resumable multi-step processes. Aha: *how Temporal/Camunda survive crashes mid-workflow.*
**P84 · Multi-tenancy: row → schema → DB-per-tenant** 🟡 — implement all three isolation levels. Aha: *the isolation-vs-cost spectrum of SaaS data architecture.*
**P85 · Strangler-fig monolith migration** 🟡 — route a slice to a new service behind a façade. Aha: *how to migrate a monolith without a big-bang rewrite.*

---

## Track 10 — Observability & Reliability (you run Prometheus; now understand it)

**P86 · Metrics library (counter/gauge/histogram + t-digest percentiles)** 🟡 — compute p50/p99 in streaming fashion. Aha: *why averaging latencies lies, and how percentiles are actually computed cheaply.*
**P87 · Distributed tracing from scratch** 🟠 — trace/span IDs, context propagation across services. Aha: *what OpenTelemetry injects into headers and why sampling matters.*
**P88 · Structured logging + correlation IDs** 🟢 — propagate a request ID through async hops. Aha: *how you actually debug one request across 6 services.*
**P89 · Circuit breaker + bulkhead + retry from scratch** 🟡 — then diff against Resilience4j. Aha: *the state machine (closed/open/half-open) you usually configure blindly.*
**P90 · Adaptive load shedding / concurrency limits** 🟠 — AIMD or Little's-law-based limits (Netflix concurrency-limits). Aha: *how to stay up under overload by rejecting early instead of dying slowly.*
**P91 · Chaos-injection tool + game day** 🟡 — inject latency/faults; run a failure drill. Aha: *resilience you didn't test doesn't exist.*
**P92 · SLO + error-budget burn-rate alerting** 🟢 — define SLI/SLO, compute burn rate, alert. Aha: *the difference between "alert on every error" and "alert when the budget is burning."*

---

## Track 11 — Security Engineering (understand the bytes, not just the library)

**P93 · JWT sign/verify (HS256 + RS256) from scratch** 🟡 — base64url, HMAC, RSA signatures. Aha: *a JWT is just `header.payload.signature` — and `alg:none` is why naive verifiers get owned.*
**P94 · OAuth2 auth-code + PKCE flow end to end** 🟠 — auth server + client + resource server. Aha: *what actually happens in "Login with Google," and why PKCE exists for public clients.*
**P95 · Password storage done right** 🟢 — bcrypt/argon2, per-user salt, pepper, work factor. Aha: *why salting beats fast hashes and why you never roll your own crypto primitives.*
**P96 · TLS/mTLS handshake + cert-chain validation** 🟡 — establish mTLS between two services; validate the chain. Aha: *what a CA, cert chain, and "verify=true" really enforce.*
**P97 · AES-GCM at rest + envelope encryption (KMS pattern)** 🟡 — data key encrypted by a master key. Aha: *how cloud KMS encrypts terabytes without ever exposing the master key.*
**P98 · HMAC request signing (SigV4-style)** 🟢 — sign requests, verify, prevent replay with timestamps/nonces. Aha: *how AWS authenticates API calls without sending the secret.*

---

## Track 12 — Performance Engineering (measure, don't guess)

**P99 · JMH microbenchmark suite** 🟢 — benchmark a few of your earlier builds correctly. Aha: *why naive `System.nanoTime()` loops lie (JIT, warmup, dead-code elimination).*
**P100 · Allocation elimination** 🟡 — object pooling + primitive collections; measure GC impact. Aha: *allocation rate, not heap size, drives GC pauses.*
**P101 · Off-heap storage (ByteBuffer / Foreign Memory API)** 🟡 — store data outside the heap. Aha: *how caches like Chronicle/Ehcache avoid GC on huge datasets.*
**P102 · Zero-copy file transfer (`transferTo`/sendfile)** 🟢 — serve a file with and without zero-copy; measure. Aha: *how Kafka achieves its throughput by avoiding user-space copies.*
**P103 · Batching & pipelining** 🟢 — batch N requests into one round trip; measure latency/throughput trade-off. Aha: *why batching trades latency for throughput, and where the knee is.*
**P104 · Profile + fix a slow service** 🟡 — async-profiler flame graphs on an earlier project; find and fix the hot path. Aha: *your intuition about "what's slow" is usually wrong; the profiler isn't.*
**P105 · GC tuning experiment (G1 vs ZGC)** 🟡 — run a load test under both; compare pause distributions. Aha: *the throughput-vs-pause-time trade-off behind GC choice.*

---

## Track 13 — AI/ML Systems (extends your RAG project)

**P106 · Vector similarity search from scratch** 🟡 — brute-force cosine, then HNSW graph basics. Aha: *what a vector DB does internally and why ANN beats exact search at scale.*
**P107 · Embedding cache + semantic dedup** 🟢 — cache embeddings; collapse near-duplicate queries. Aha: *the cheapest way to cut LLM cost and latency.*
**P108 · Semantic router / classifier over embeddings** 🟢 — route queries by nearest centroid. Aha: *how to pick a tool/prompt without another LLM call.*
**P109 · LLM cost-control gateway** 🟡 — token budgets, rate limits, response cache, fallback model. Aha: *productionizing LLMs is mostly the same reliability/cost engineering as any API.*
**P110 · Mini feature store (online/offline parity)** 🟡 — same features for training and serving. Aha: *training/serving skew — the #1 silent ML-system bug.*

---

## The "one system, carried all the way" capstone

The deepest learning comes from carrying **one system through many tracks** instead of 110 disconnected toys. Recommended spine:

1. **P69** Build Kafka-lite (messaging) →
2. **P54** put its metadata under Raft (consensus) →
3. **P33/P35** back it with a WAL + LSM store (DB internals) →
4. **P44** serve it over an NIO reactor (networking) →
5. **P89/P90** add circuit breaking + load shedding (reliability) →
6. **P86/P87** instrument with metrics + tracing (observability) →
7. **P79** front it with an API gateway (architecture) →
8. ship it to **Azure via Bicep + GitHub Actions** (see `BICEP-INTERVIEW-GUIDE.md`).

When you've done that, you won't be "a developer who used Kafka" — you'll be an engineer who **rebuilt Kafka's core ideas** and can reason about any distributed system from first principles. That is the gap you described, closed.

---

## Suggested cadence (realistic for a working professional)

| Pace | Per week | Finish ~core 40 projects | Finish all 110 |
|---|---|---|---|
| Casual | 1 🟢/🟡 project | ~9 months | ~2 years |
| Committed | 1 🟡 + small reps | ~6 months | ~14 months |
| Intense | 1 🟠 or 2 🟡 | ~4 months | ~9 months |

**Don't aim for 110.** Pick **~40 weighted to your weak tracks** (use the self-diagnosis table) and do the capstone. Depth on 40 beats shallow on 110.

---

## Definition of understanding (apply to every project)

Before you call one "done," you must be able to:
1. **Explain the core trade-off** in one sentence (e.g., "LSM trades read speed for write speed").
2. **Predict the failure at 10×** (what breaks first under load/scale?).
3. **Name the real system** that uses this and *why* (e.g., "Redis sorted sets use skip lists because…").
4. **Break it on purpose** and explain what you saw.

If you can do all four, it's yours for life — and any interview question on it is trivial. If you can't, you read; you didn't build. Go back.

---

**Read less, build more. These 110 projects are the 15 years of "internals" you skipped — pick your tracks and start.**
