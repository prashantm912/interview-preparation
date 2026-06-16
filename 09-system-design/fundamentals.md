# System Design Fundamentals

A staff-level, interview-grade reference on the building blocks of scalable systems: scaling strategies, statelessness, load balancing, caching, CDNs, rate limiting, the CAP/PACELC theorems, consistency models, and the napkin math (estimation + latency numbers) that separates a confident designer from a hand-waver. Knowledge current through 2026.

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

### Q1. [Theory] What is the difference between vertical and horizontal scaling, and when do you choose each?

**Vertical scaling** (scaling up) means adding more resources — CPU, RAM, faster disks — to a single machine. **Horizontal scaling** (scaling out) means adding more machines and distributing the load across them.

Vertical scaling is simpler: no distributed-systems complexity, no need to make code stateless, and a single node is easier to reason about. But it has hard ceilings (the biggest cloud VM only goes so far), it is a single point of failure, and the price-per-unit grows non-linearly — the top-end machine costs far more than 2× a mid-tier one.

Horizontal scaling is effectively unbounded and gives you redundancy (lose one node, keep serving), but it forces you to confront load balancing, data partitioning, consistency, and coordination. The industry rule of thumb: **scale up first because it is cheap and fast, then scale out once you hit the ceiling or need high availability.** Stateless web tiers scale out trivially; stateful databases are where horizontal scaling gets genuinely hard.

```
Vertical (scale up)              Horizontal (scale out)
   ┌──────────┐                  ┌────┐ ┌────┐ ┌────┐
   │  8 CPU   │   ───grow──▶     │node│ │node│ │node│
   │  64 GB   │                  └────┘ └────┘ └────┘
   └──────────┘                       ▲   add more   ▲
   one big box                  many small boxes + LB
```

### Q2. [Theory] What does it mean for a service to be "stateless," and why does it matter for scaling?

A **stateless service** keeps no client-session data in its own memory between requests; every request carries (or can fetch) everything needed to process it. Session state, if any, lives in an external store (Redis, a database, or a signed JWT held by the client). The opposite — a **stateful** service — pins a user to a specific node because that node holds their session.

Statelessness matters because it makes nodes **interchangeable**: any request can go to any instance, so you can add/remove nodes freely, the load balancer can spray traffic without sticky sessions, and a crashed node loses nothing but in-flight requests. This is the foundation of horizontal scaling and of cloud auto-scaling. The trade-off is that you push state to a shared external store, which becomes a thing you now have to scale and make highly available. The mantra: **"keep the compute tier stateless, push state to the edges (client) or to dedicated state stores."**

### Q3. [Theory] What is a load balancer, and what is the difference between L4 and L7 load balancing?

A **load balancer (LB)** distributes incoming requests across multiple backend instances to spread load, provide failover, and present a single entry point.

- **L4 (transport layer)** balances on TCP/UDP — it sees IP addresses and ports but not the content. It is extremely fast and protocol-agnostic, and it simply forwards (or NATs) connections. Examples: AWS Network Load Balancer, a TCP `nginx stream`.
- **L7 (application layer)** understands HTTP/gRPC. It can route by URL path, host header, cookies, or method; terminate TLS; do header rewriting; and implement sticky sessions and content-based routing. It is more flexible but does more work per request. Examples: AWS Application Load Balancer, Envoy, nginx in HTTP mode.

```
L4: client ──TCP──▶ [LB sees IP:port] ──▶ backend
L7: client ──HTTP─▶ [LB reads /api/* , Host, cookies] ──▶ matching backend pool
```

Use L4 when you need raw throughput or non-HTTP protocols; use L7 when you need smart routing, TLS termination, or per-path backends (microservices behind one domain).

### Q4. [Theory] Name common load-balancing algorithms and when each is appropriate.

- **Round-robin**: cycle through backends in order. Simple, fair when requests and servers are uniform.
- **Weighted round-robin**: give bigger machines a larger share. Good for heterogeneous fleets.
- **Least connections**: send to the backend with the fewest active connections. Better when request durations vary (long-lived connections, slow queries).
- **Least response time**: pick the fastest-responding healthy node; reacts to real load.
- **IP hash / consistent hashing**: map a key (client IP, user ID, cache key) to a node deterministically. Used for sticky routing and cache affinity.

Round-robin is the sensible default. Reach for least-connections when request cost is uneven, and consistent hashing when you need the same key to land on the same node (caching, sharded stateful services).

### Q5. [Practical] How do health checks keep a load balancer from sending traffic to a dead server?

The LB periodically probes each backend (e.g., `GET /healthz` every few seconds). A backend is marked **unhealthy** after N consecutive failures and removed from rotation; it returns to rotation after M consecutive successes. This hysteresis prevents flapping.

There are two important kinds of probe:
- **Liveness** — "is the process up?" (cheap TCP connect or trivial HTTP 200).
- **Readiness** — "is this instance ready to serve real traffic?" (checks DB connectivity, warmed caches, downstream dependencies).

In production I make the readiness endpoint *shallow but honest*: it should fail when the instance genuinely cannot serve, but not cascade (if your DB is down, returning unhealthy on every node yanks the entire fleet out of rotation and you serve zero traffic). A common pattern is to keep serving with a degraded mode rather than fail readiness on a shared dependency outage.

### Q6. [Coding] Implement a round-robin server selector that is thread-safe.

**Problem:** Given a list of backend addresses, return the next server in round-robin order. It must be correct under concurrent access from many request threads.

**Approach 1 — naive (broken under concurrency):** a plain `int index++` races; two threads can read the same value.

**Approach 2 — `AtomicInteger` with modulo (optimal):**

```java
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class RoundRobinBalancer {
    private final List<String> servers;
    private final AtomicInteger counter = new AtomicInteger(0);

    public RoundRobinBalancer(List<String> servers) {
        if (servers == null || servers.isEmpty())
            throw new IllegalArgumentException("servers must be non-empty");
        this.servers = List.copyOf(servers); // immutable snapshot
    }

    public String next() {
        // getAndIncrement() may overflow; mask the sign bit to stay non-negative.
        int i = counter.getAndIncrement() & Integer.MAX_VALUE;
        return servers.get(i % servers.size());
    }
}
```

**Time:** O(1) per call. **Space:** O(n) for the server list.

**Edge cases:** empty list (reject in constructor); integer overflow of the counter (the `& Integer.MAX_VALUE` mask keeps the index non-negative across the wrap); adding/removing servers at runtime (here we use an immutable snapshot — for dynamic fleets you'd swap the whole list atomically via a `volatile` reference or `CopyOnWriteArrayList`).

### Q7. [Theory] What is a CDN and what problem does it solve?

A **Content Delivery Network** is a globally distributed set of edge servers that cache content close to users. When a user requests an asset, DNS/anycast routes them to the nearest edge PoP; if the edge has the object cached, it serves it directly (a **cache hit**) instead of crossing the planet to your origin.

CDNs solve three problems at once: **latency** (Tokyo users hit a Tokyo edge, not a Virginia origin), **origin offload** (the edge absorbs most read traffic, so your servers and bandwidth bill shrink), and **availability/DDoS absorption** (edges soak up traffic spikes and shield the origin). Originally for static assets (images, JS, video), modern CDNs (Cloudflare, Fastly, CloudFront) also cache API responses, do edge compute, and terminate TLS. Cache behavior is driven by `Cache-Control` headers and TTLs; the hard part is invalidation when content changes.

### Q8. [Practical] You see high latency for users in Asia hitting your US-hosted API. What are your first moves?

First, confirm it is geography, not load — check p50/p95/p99 latency segmented by region in your observability tool. If Asian users see ~200ms+ of pure network RTT, that is physics: light through fiber from Singapore to Virginia is roughly 200ms round trip.

Mitigations, in order of effort: (1) Put a **CDN** in front so cacheable GETs are served from an Asian edge. (2) Use the CDN's network as a **transit accelerator** even for dynamic requests — TLS terminates at the nearby edge and rides the CDN's optimized backbone to origin, cutting handshake round trips. (3) **Read replicas** of the database in-region so reads don't cross the ocean. (4) Long-term, deploy a **multi-region** active-active or active-passive footprint. I'd start with the CDN because it is the cheapest, fastest lever and often cuts perceived latency dramatically before any architecture change.

---

## 🟡 Intermediate (3–7 yrs)

### Q9. [Theory] Compare cache-aside, write-through, and write-behind caching strategies.

These describe how the cache and the database stay in sync.

- **Cache-aside (lazy loading):** the application reads cache first; on a miss it reads the DB, populates the cache, and returns. Writes go to the DB and *invalidate* (or update) the cache key. Pros: only requested data is cached; cache failure is survivable (you just hit the DB). Cons: first request per key is a miss; risk of stale data between DB write and cache invalidation. This is the most common pattern (e.g., Redis in front of Postgres).
- **Write-through:** writes go to the cache, which synchronously writes to the DB before acknowledging. Pros: cache is always consistent with DB; reads are warm. Cons: every write pays cache + DB latency; you cache data that may never be read.
- **Write-behind (write-back):** writes go to the cache and are flushed to the DB asynchronously in batches. Pros: very fast writes, write coalescing. Cons: risk of **data loss** if the cache dies before flush; harder consistency. Used where write throughput matters more than durability (metrics, counters).

```
Cache-aside read:                  Write-through write:
 app → cache (miss)                 app → cache → DB (sync) → ack
     → DB → fill cache → return
```

In production I default to cache-aside for general read-heavy workloads and reserve write-behind for high-volume, loss-tolerant data.

### Q10. [Theory] What is the difference between cache eviction and cache invalidation, and what eviction policies exist?

**Eviction** is removing entries because the cache is *full* — a capacity decision. **Invalidation** is removing/refreshing entries because they are *stale* — a correctness decision. They are independent concerns and a good design handles both.

Common eviction policies:
- **LRU (Least Recently Used):** evict the entry untouched longest. Great general default; matches temporal locality.
- **LFU (Least Frequently Used):** evict the least-accessed; better when popularity is stable, but it can hold onto once-hot items too long (mitigated by windowed/aging LFU).
- **FIFO:** evict oldest-inserted; simple but ignores access patterns.
- **TTL-based:** entries expire after a fixed time; the simplest invalidation mechanism.
- **W-TinyLFU** (used by Caffeine): a modern hybrid combining a frequency sketch with a recency window, achieving near-optimal hit rates.

Invalidation strategies: TTL expiry, explicit delete on write, versioned keys (`user:42:v7`), and event-driven invalidation via a change stream. Phil Karlton's quip — *"There are only two hard things in computer science: cache invalidation and naming things"* — is earned: getting invalidation wrong serves stale or wrong data silently.

### Q11. [Coding] Implement an O(1) LRU cache in Java.

**Problem:** Build a fixed-capacity cache supporting `get(key)` and `put(key, value)` in O(1), evicting the least-recently-used entry when full.

**Approach 1 — `LinkedHashMap` (concise, production-friendly):**

```java
import java.util.LinkedHashMap;
import java.util.Map;

public class LruCache<K, V> extends LinkedHashMap<K, V> {
    private final int capacity;

    public LruCache(int capacity) {
        super(capacity, 0.75f, true); // accessOrder = true → LRU ordering
        this.capacity = capacity;
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
        return size() > capacity; // evict when over capacity
    }
}
```

**Approach 2 — explicit HashMap + doubly linked list (what interviewers want you to derive):**

```java
import java.util.HashMap;
import java.util.Map;

public class LRUCache {
    private static class Node {
        int key, value;
        Node prev, next;
        Node(int k, int v) { key = k; value = v; }
    }

    private final int capacity;
    private final Map<Integer, Node> map = new HashMap<>();
    private final Node head = new Node(0, 0), tail = new Node(0, 0); // sentinels

    public LRUCache(int capacity) {
        this.capacity = capacity;
        head.next = tail;
        tail.prev = head;
    }

    public int get(int key) {
        Node n = map.get(key);
        if (n == null) return -1;
        moveToFront(n);
        return n.value;
    }

    public void put(int key, int value) {
        Node n = map.get(key);
        if (n != null) { n.value = value; moveToFront(n); return; }
        if (map.size() == capacity) {
            Node lru = tail.prev;   // least recently used
            remove(lru);
            map.remove(lru.key);
        }
        Node fresh = new Node(key, value);
        map.put(key, fresh);
        addToFront(fresh);
    }

    private void addToFront(Node n) {
        n.next = head.next; n.prev = head;
        head.next.prev = n; head.next = n;
    }
    private void remove(Node n) {
        n.prev.next = n.next; n.next.prev = n.prev;
    }
    private void moveToFront(Node n) { remove(n); addToFront(n); }
}
```

**Time:** O(1) for both `get` and `put`. **Space:** O(capacity).

**Edge cases:** capacity 0 (reject or treat as no-op); updating an existing key must refresh recency, not just the value; the sentinel head/tail nodes remove null-checks at the boundaries. For thread safety in production, prefer Caffeine over synchronizing this by hand.

### Q12. [Practical] Walk through where you'd place caches in a typical read-heavy web architecture.

Caching is layered; each layer offloads the one behind it:

```
Client (browser cache, localStorage)
   │  Cache-Control, ETag
CDN / edge cache  ──── absorbs static + cacheable GETs
   │
API gateway / reverse proxy cache (nginx, Varnish)
   │
Application in-process cache (Caffeine)  ── microsecond access, per-node
   │
Distributed cache (Redis / Memcached)    ── shared across nodes
   │
Database buffer pool + query cache
   │
Disk
```

In practice: I cache immutable assets at the **browser/CDN** with long TTLs and content-hashed filenames; cache hot, read-mostly objects in a **distributed Redis** with cache-aside; and use a small **in-process Caffeine** cache for ultra-hot keys to avoid a network hop to Redis (a two-tier "near cache"). The trade-off with in-process caches is consistency — each node can hold a slightly different value, so I only use them for data that tolerates seconds of staleness or I pair them with a pub/sub invalidation channel.

### Q13. [Theory] Explain three rate-limiting algorithms: token bucket, leaky bucket, and sliding window.

Rate limiting protects services from abuse, runaway clients, and cascading overload.

- **Token bucket:** a bucket holds up to *B* tokens, refilled at *R* tokens/sec. Each request consumes a token; if empty, reject (or queue). It **allows bursts** up to the bucket size while enforcing the long-run average rate *R*. This is the most popular choice (used by Stripe, AWS API Gateway) because real traffic is bursty.
- **Leaky bucket:** requests enter a queue (the bucket) and are processed (leak out) at a fixed rate. It **smooths** bursts into a steady stream — good for protecting a downstream that needs a constant rate. The downside is added latency from queuing and no burst allowance.
- **Sliding window:** counts requests in a rolling time window. *Fixed window* (count per minute) is simple but allows a 2× burst at the window boundary; *sliding window log* (timestamps) is exact but memory-heavy; *sliding window counter* approximates by weighting the previous window, which is the practical sweet spot.

```
Token bucket: [● ● ● ● ●]  refill +R/s, take 1 per request, empty → reject (bursty OK)
Leaky bucket: req→[ |||| ]→ out at fixed rate (smooth, queued)
```

### Q14. [Coding] Implement a thread-safe token-bucket rate limiter.

**Problem:** Allow at most `capacity` requests immediately (burst) and refill at `refillPerSec` tokens per second. `tryAcquire()` returns true if a token was available.

```java
public class TokenBucket {
    private final long capacity;
    private final double refillPerSec;
    private double tokens;
    private long lastRefillNanos;

    public TokenBucket(long capacity, double refillPerSec) {
        this.capacity = capacity;
        this.refillPerSec = refillPerSec;
        this.tokens = capacity;            // start full
        this.lastRefillNanos = System.nanoTime();
    }

    public synchronized boolean tryAcquire() {
        refill();
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }
        return false;
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

**Time:** O(1) per call. **Space:** O(1) per bucket.

**Edge cases:** clock — use `nanoTime()` (monotonic), never `currentTimeMillis()` which can jump backward on NTP correction; lazy refill avoids a background thread; for a distributed limiter shared across nodes, move state into Redis and execute refill+decrement atomically in a Lua script so the check-and-decrement is not racy across servers. Security note: rate-limit by a trustworthy key (authenticated user ID, or API key) — limiting only by client IP is easily evaded behind NAT/proxies and can punish many users sharing one IP.

### Q15. [Theory] State the CAP theorem precisely and explain why "CA" is misleading.

The **CAP theorem** says that a distributed data store can provide at most two of: **Consistency** (every read sees the most recent write — i.e., linearizability), **Availability** (every request to a non-failing node gets a non-error response), and **Partition tolerance** (the system keeps working despite network partitions dropping messages between nodes).

The crucial nuance: in any real distributed system **partitions will happen** (networks fail), so P is non-negotiable. That means the actual choice is **CP vs AP during a partition**:
- **CP:** when partitioned, refuse requests that can't guarantee consistency (return errors/time out) rather than serve stale data. Example: a system requiring quorum writes; HBase, ZooKeeper.
- **AP:** when partitioned, keep serving, accepting that nodes may diverge and reconcile later. Example: Cassandra, DynamoDB (tunable), DNS.

"CA" is misleading because a single-node database is trivially CA but isn't distributed; once you have multiple nodes communicating over a network, you cannot opt out of partitions. CAP is also only about behavior *during* a partition — it says nothing about the (common) no-partition case, which is exactly what PACELC fixes.

### Q16. [Theory] What is PACELC and why is it a more complete model than CAP?

**PACELC** extends CAP: *if there is a Partition (P), choose between Availability and Consistency (A/C); Else (E), in normal operation, choose between Latency and Consistency (L/C).*

CAP only describes the partition case, which is rare. PACELC's insight is that even when the network is healthy, replicated systems face a fundamental **latency-vs-consistency** trade-off: to be strongly consistent you must coordinate replicas (wait for quorum/leader acknowledgment), which adds latency; to be fast you can return from one replica and risk staleness. Classifications:
- **PA/EL:** Cassandra, DynamoDB — available under partition, low latency otherwise (sacrifice consistency).
- **PC/EC:** VoltDB, traditional 2-phase-commit RDBMS — consistent always, at the cost of latency/availability.
- **PA/EC:** MongoDB (default) — available under partition, consistent in normal operation.
- **PC/EL:** PNUTS — consistent under partition, low latency normally.

PACELC matters because the "else" case is where your system spends 99.9% of its life, so the L-vs-C dial drives day-to-day p99 latency.

### Q17. [Theory] Explain the spectrum of consistency models: strong, eventual, causal, and read-your-writes.

These describe what guarantees a reader gets about prior writes.

- **Strong consistency (linearizability):** reads always return the latest committed write; the system behaves as if there is one copy. Easiest to reason about, most expensive (coordination, higher latency). Needed for balances, inventory, locks.
- **Eventual consistency:** if writes stop, all replicas *eventually* converge. Reads may be stale for a while. Cheap, highly available, low latency. Fine for likes, view counts, social feeds.
- **Causal consistency:** operations that are causally related (a reply after a post) are seen by everyone in that order, but unrelated operations may be reordered. Stronger than eventual, weaker than strong; preserves "happens-before" so you never see a reply to a message you can't see.
- **Read-your-writes (a session guarantee):** a user always sees their *own* writes immediately, even if others see them later. Critical for UX — you post a comment, you must see it on refresh. Often implemented by pinning the user's reads to the primary or to a replica known to have their write (e.g., via a write timestamp/token).

```
strong  ──────────────────────────  eventual
(linearizable)  causal  read-your-writes  (loosest)
   more coordination ◀──────────▶ more availability/lower latency
```

In practice systems mix these: a DynamoDB table can be eventually consistent for cheap reads but offer strongly consistent reads per request, and you might layer read-your-writes on top via sticky routing.

### Q18. [Practical] How do you do back-of-the-envelope estimation for a system like "design a URL shortener"? Show the math.

The goal is to size storage, throughput, and bandwidth so you pick the right storage engine and node count — not to be exact, but to be within an order of magnitude.

Assume **100M new URLs/day**, read:write ratio **100:1**, store each mapping for 5 years.

```
Writes/sec  = 100M / 86,400 s/day ≈ 1,160 writes/sec  (round to ~1,200)
Reads/sec   = 1,160 × 100 ≈ 116,000 reads/sec
Storage/row ≈ short code (7B) + long URL (~500B) + metadata ≈ ~600B
Rows in 5y  = 100M × 365 × 5 ≈ 1.8e11 rows
Total store = 1.8e11 × 600B ≈ ~108 TB
Read BW     = 116,000 × 600B ≈ ~70 MB/s out (well within a single 10GbE NIC)
Key space   = 62^7 (a–z A–Z 0–9, 7 chars) ≈ 3.5e12 → plenty for 1.8e11 URLs
```

**Conclusions from the math:** ~116K reads/sec is read-dominated, so a cache (Redis) in front absorbs most reads and the DB sees mostly writes — meaning a single well-tuned primary plus replicas is plausible, but ~108TB means we must shard or use a wide-column store. The 62^7 key space confirms a 7-character base-62 code is sufficient. Always state assumptions out loud; interviewers grade the *reasoning*, not the digits.

### Q19. [Practical] What are the "latency numbers every engineer should know," and how do you use them in a design discussion?

These are Jeff Dean's order-of-magnitude numbers (rounded, modern hardware ~2020s):

```
L1 cache reference                    ~1 ns
Branch mispredict                     ~3 ns
L2 cache reference                    ~4 ns
Mutex lock/unlock                    ~17 ns
Main memory (RAM) reference         ~100 ns
Compress 1 KB (fast algo)            ~2 µs
Read 1 MB sequentially from RAM      ~3 µs
SSD random read                     ~16 µs
Read 1 MB sequentially from SSD      ~49 µs
Round trip within same datacenter   ~500 µs (0.5 ms)
Read 1 MB sequentially from disk     ~825 µs (HDD)
Disk (HDD) seek                       ~2 ms
Read 1 MB from network (1Gbps)       ~10 ms
Round trip CA ↔ Netherlands         ~150 ms
```

The headline ratios: **RAM is ~100,000× faster than a disk seek; an SSD random read is ~100× faster than an HDD seek; a cross-continent round trip dwarfs everything.** In a design, I use them to sanity-check ideas: "this endpoint makes 20 sequential cross-region calls" instantly flags a 3-second tail; "we read from disk per request at 100K QPS" says we need a cache. They turn vague intuition ("that seems slow") into defensible estimates ("that's ~10ms of network per MB, so streaming 100MB is ~1s — unacceptable, we need to chunk or compress").

### Q20. [Coding] Implement a sliding-window-log rate limiter.

**Problem:** Allow at most `maxRequests` within any rolling window of `windowMillis`. Unlike a fixed window, it must not allow a 2× burst across the boundary.

```java
import java.util.ArrayDeque;
import java.util.Deque;

public class SlidingWindowLog {
    private final int maxRequests;
    private final long windowMillis;
    private final Deque<Long> timestamps = new ArrayDeque<>();

    public SlidingWindowLog(int maxRequests, long windowMillis) {
        this.maxRequests = maxRequests;
        this.windowMillis = windowMillis;
    }

    public synchronized boolean allow(long nowMillis) {
        long windowStart = nowMillis - windowMillis;
        // Evict timestamps that fell out of the window.
        while (!timestamps.isEmpty() && timestamps.peekFirst() <= windowStart) {
            timestamps.pollFirst();
        }
        if (timestamps.size() < maxRequests) {
            timestamps.addLast(nowMillis);
            return true;
        }
        return false;
    }
}
```

**Time:** amortized O(1) per request (each timestamp is added and removed once). **Space:** O(maxRequests) — the window holds at most that many timestamps.

**Edge cases:** the log is exact but its memory grows with the limit, so for very high limits prefer the **sliding-window-counter** approximation (`prevWindowCount × overlapFraction + currentWindowCount`), which is O(1) space. For a distributed setting, store the log/counter in Redis sorted sets (`ZADD`/`ZREMRANGEBYSCORE`) executed atomically. Pass `now` in as a parameter (as above) to make the limiter unit-testable without mocking the clock.

### Q21. [Practical] A downstream service is slow and your threads are piling up waiting on it, threatening to take down your service. What patterns do you apply?

This is a **cascading failure**: a slow dependency exhausts your thread pool / connection pool, so even unrelated requests stall. The toolkit:

1. **Timeouts** — never call a dependency without an aggressive timeout. An unbounded wait is the root cause; cap it (e.g., 500ms) so threads free up.
2. **Circuit breaker** — track the downstream's error/latency rate; once it crosses a threshold, *open* the circuit and fail fast (return a fallback) for a cooldown, then *half-open* to probe recovery. Resilience4j (the modern successor to Hystrix on Spring Boot 3) implements this.
3. **Bulkheads** — isolate resources so a misbehaving dependency can only consume its own dedicated pool, not starve the whole service (like watertight ship compartments).
4. **Load shedding / backpressure** — when overloaded, reject low-priority requests early (HTTP 429/503) rather than queue them into oblivion.
5. **Retries with exponential backoff + jitter** — but only for idempotent calls, and budgeted, because naive retries amplify load on an already-struggling dependency (a retry storm).

In production I combine a tight timeout, a Resilience4j circuit breaker with a sensible fallback (cached/degraded response), and capped retries with jitter. The goal is **graceful degradation**: serve a worse-but-working answer instead of falling over.

---

## 🟠 Advanced (8–12 yrs)

### Q22. [Theory] Explain consistent hashing, why it beats `hash(key) % N`, and the role of virtual nodes.

When sharding keys across N nodes, the naive `hash(key) % N` means that adding or removing a node changes N, so **almost every key remaps** — catastrophic for a cache (mass misses) or a sharded store (mass data movement).

**Consistent hashing** maps both nodes and keys onto a circular hash ring (0 → 2³²−1). A key is owned by the first node encountered clockwise from the key's position. Adding or removing a node only reassigns the keys in the arc between that node and its predecessor — on average **K/N keys move**, not all of them.

```
        Node A
      ●─────────●
     ╱  k1       ╲ Node B
    │ k4          │
    │       k2    │
     ╲  Node C   ╱
      ●─────────●  k3
keys map clockwise to the next node on the ring
```

The catch: with few nodes the ring is uneven, causing hot spots. **Virtual nodes** fix this — each physical node is placed at many points on the ring (e.g., 100–200 virtual positions), smoothing the load distribution and making rebalancing finer-grained. This is how Cassandra, DynamoDB, and many distributed caches partition data. A modern variant, **bounded-load consistent hashing**, caps how much any node can be overloaded.

### Q23. [Theory] What is quorum-based replication, and how do R, W, and N let you tune consistency?

In a Dynamo-style system, each piece of data is replicated to **N** nodes. A write must be acknowledged by **W** replicas; a read must gather responses from **R** replicas. The key relationship:

- If **W + R > N**, the read and write quorums overlap, so any read is guaranteed to see at least one replica with the latest write → **strong-ish consistency** (read-your-writes within the quorum).
- If **W + R ≤ N**, quorums may not overlap → faster but **eventually consistent**.

```
N = 3 (replicas)
W=2, R=2 → W+R=4 > 3 → overlapping quorum (consistent)
W=1, R=1 → W+R=2 ≤ 3 → no guaranteed overlap (fast, eventual)
W=3, R=1 → write-heavy consistency; W=1, R=3 → read-heavy consistency
```

This is a *dial*: `W=N` makes writes consistent but unavailable if any replica is down; `W=1` makes writes fast and available but reads need `R=N` to be safe. Cassandra exposes this per query (`QUORUM`, `ONE`, `ALL`, `LOCAL_QUORUM`). Conflicts from concurrent writes are resolved with version vectors or last-write-wins (with the danger that LWW silently drops data on clock skew).

### Q24. [Practical] Design the caching strategy for a high-traffic product page and defend it against the thundering-herd and stale-data problems.

**Scenario:** a product page hit 50K times/sec; the underlying data changes a few times per hour. Reads massively dominate writes.

**Approach:** cache-aside in Redis with a TTL, plus a small in-process Caffeine near-cache for the very hottest SKUs. But two failure modes need explicit handling:

- **Thundering herd / cache stampede:** when a hot key expires, thousands of concurrent requests all miss simultaneously and hammer the DB. Defenses: (1) **request coalescing / single-flight** — only one request recomputes the value, others wait for it (Caffeine's `LoadingCache` does this per node); (2) **probabilistic early expiration** — refresh slightly before the TTL with a randomized jitter so keys don't all expire at once; (3) a short-lived **mutex/lock key** in Redis so only one node repopulates.
- **Cache penetration** (queries for non-existent keys bypass the cache and hit the DB): cache negative results briefly, or front the DB with a **Bloom filter** of known keys.
- **Stale data:** on write, proactively update or delete the cache key (write-through-ish) plus a short TTL as a safety net. For the near-cache, broadcast an invalidation on Redis pub/sub.

**What I'd ship:** Redis cache-aside with jittered TTLs, single-flight repopulation, negative caching, and pub/sub-driven near-cache invalidation. This holds 50K QPS off a modest DB while bounding staleness to seconds.

### Q25. [Coding] Implement consistent hashing with virtual nodes.

**Problem:** Map keys to a set of nodes such that adding/removing a node only relocates a small fraction of keys; distribute load evenly using virtual nodes.

```java
import java.util.Collection;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentSkipListMap;

public class ConsistentHashRing {
    private final int virtualNodes;
    // Ring: hash position -> physical node. Sorted for clockwise lookup.
    private final SortedMap<Long, String> ring = new ConcurrentSkipListMap<>();

    public ConsistentHashRing(int virtualNodes, Collection<String> nodes) {
        this.virtualNodes = virtualNodes;
        for (String n : nodes) addNode(n);
    }

    public void addNode(String node) {
        for (int i = 0; i < virtualNodes; i++) {
            ring.put(hash(node + "#" + i), node);
        }
    }

    public void removeNode(String node) {
        for (int i = 0; i < virtualNodes; i++) {
            ring.remove(hash(node + "#" + i));
        }
    }

    public String getNode(String key) {
        if (ring.isEmpty()) return null;
        long h = hash(key);
        // first virtual node clockwise from the key
        SortedMap<Long, String> tail = ring.tailMap(h);
        Long target = tail.isEmpty() ? ring.firstKey() : tail.firstKey();
        return ring.get(target);
    }

    // FNV-1a 64-bit hash — cheap, well-distributed. (Use Murmur3 in prod.)
    private long hash(String s) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return h & 0x7fffffffffffffffL; // keep non-negative
    }
}
```

**Time:** `getNode` is O(log V) where V = total virtual nodes (TreeMap lookup); add/remove is O(virtualNodes·log V). **Space:** O(V).

**Edge cases:** empty ring (return null); the clockwise wrap-around when the key hashes past the last node (fall back to `firstKey()`); hash collisions among virtual node positions (rare with 64-bit, otherwise probe). Using `ConcurrentSkipListMap` lets reads proceed during node membership changes. More virtual nodes → smoother distribution but more memory and slower membership changes; ~100–200 per node is typical.

### Q26. [Theory] How does a CDN handle cache invalidation when content changes, and what are the trade-offs of purge vs versioning?

CDNs cache by URL + headers, so changing content under the *same* URL is the problem. Two strategies:

1. **Purge/invalidate:** explicitly tell the CDN to drop an object (`PURGE` request, or API call). Fastest CDNs (Fastly) do **instant soft purge** in ~150ms via a versioned content store; others (older CloudFront) take minutes to propagate to every edge. Purging is necessary for content at a fixed URL (an HTML page, an API response) but is eventually-consistent across PoPs and can be rate-limited/expensive at scale.
2. **Versioned/fingerprinted URLs (cache busting):** embed a content hash in the filename (`app.9f3c2b.js`) and serve it with a near-infinite TTL (`Cache-Control: max-age=31536000, immutable`). When content changes, the URL changes, so there is *nothing to invalidate* — old and new coexist, and clients fetch the new URL because the referencing HTML (served with a short TTL) points to it.

**Trade-off:** versioning is the gold standard for static assets — no purge needed, perfectly cacheable, atomic deploys. Purging is for dynamic/HTML content you can't rename. A robust setup uses both: immutable fingerprinted assets behind a short-TTL HTML shell, and surrogate keys (cache tags) so you can purge a *group* of related objects in one call (e.g., all pages showing product 42).

### Q27. [Practical] Your single-primary PostgreSQL can't keep up with read load. Walk through how you'd scale reads, then writes.

**Reads first (the easy 80%):**
1. **Cache** the hottest reads (Redis cache-aside) — often this alone solves it.
2. **Read replicas:** route SELECTs to async replicas, writes to the primary. The catch is **replication lag** → a user may not read their own write. Mitigate with read-your-writes routing (send a user's reads to the primary for a few seconds after their write, or use a write token to pick a caught-up replica).
3. **Connection pooling** (PgBouncer) so thousands of clients don't exhaust Postgres connections.

**Writes next (the hard 20%):** a single primary is a write ceiling. Options:
- **Vertical scale the primary** (cheap, buys time).
- **Functional partitioning:** move different tables/domains to different databases (service decomposition).
- **Sharding:** partition rows across multiple primaries by a shard key (e.g., user_id). This is the real scale-out but it's costly — cross-shard joins/transactions become hard, you need a routing layer (or Citus/Vitess), and re-sharding is painful. Pick a shard key that distributes evenly and matches your dominant query pattern to avoid cross-shard fan-out.

**What I'd actually do:** cache + read replicas covers the vast majority of "read-heavy" growth. I delay sharding as long as possible because it permanently raises operational complexity; I'd reach for managed solutions (Aurora, Citus, Vitess) before hand-rolling shard logic.

### Q28. [Theory] What is the difference between latency and throughput, and why can optimizing one hurt the other?

**Latency** is the time for a single request to complete (measured at percentiles — p50/p95/p99 — never just the mean). **Throughput** is the number of requests handled per unit time (QPS). They are related but distinct: a system can have high throughput *and* high latency (a deep queue processes many items but each waits a long time).

They can trade off: **batching** improves throughput (amortize fixed costs across many items) but adds latency (items wait to fill a batch). **Buffering/queuing** smooths load and raises throughput but lengthens tail latency under burst. Conversely, optimizing for low latency (process each request immediately, no batching) can lower peak throughput.

The key interview point is **tail latency**: at scale, p99 dominates user experience because a single page often fans out to dozens of services, and the slowest one gates the response (the "tail at scale" problem). So you measure p99/p99.9, and techniques like **hedged requests** (send a duplicate to a second replica after a delay, take whichever returns first) trade a little throughput for dramatically better tail latency.

### Q29. [Practical] How would you design rate limiting that works correctly across a fleet of 50 stateless API servers?

A per-node in-memory limiter is wrong here: a 1000 req/min limit becomes effectively 50,000 because each node counts independently. You need a **shared, atomic counter**.

```
client → LB → [API node 1..50] ──┐
                                  ▼
                       Redis (atomic token bucket / window)
                                  │  Lua script: refill + check + decrement
                                  ▼
                           allow / 429
```

**Design:** store the limiter state (tokens or a sliding-window counter) in Redis, keyed by the limit subject (`ratelimit:user:42`). Execute the read-modify-write **atomically in a Lua script** so the check-and-decrement can't race across nodes. Return `429 Too Many Requests` with a `Retry-After` header on rejection.

**Trade-offs and refinements:** every request now does a Redis round trip (~0.5ms in-DC) — acceptable, but you can reduce it with a **local token allotment**: each node leases a batch of tokens from Redis and limits locally, syncing periodically (sacrificing some precision for fewer round trips). For global multi-region limits, accept eventual consistency (a small over-admit) rather than cross-region synchronous coordination, which would add 150ms to every request. Redis is a single point of failure for the limiter, so plan a **fail-open** policy (allow traffic if Redis is down — availability over strict enforcement) or fail-closed depending on whether the limit is for fairness or abuse prevention.

### Q30. [Theory] Compare CQRS and the read-replica pattern for separating reads from writes.

Both separate the read path from the write path, but at different levels.

- **Read replicas** are an *infrastructure* technique: the same schema is copied to replicas; writes go to the primary, reads to replicas. Transparent to the data model; the only new concern is replication lag. Cheap and ubiquitous.
- **CQRS (Command Query Responsibility Segregation)** is an *architectural* pattern: the **write model** (commands) and **read model** (queries) are different models, often different storage. Writes go through a normalized, validation-heavy command side; reads come from one or more **denormalized read models** (materialized views) optimized per query, kept in sync asynchronously (often via events / event sourcing).

```
Write side (commands)          Read side (queries)
  validate → domain model       denormalized views
        │  emits events              ▲ optimized per query
        └────────►  projector ───────┘ (async, eventually consistent)
```

CQRS shines when read and write workloads are wildly different (complex writes, many varied read shapes — e.g., an e-commerce order that's written once but read by search, recommendations, and analytics in different shapes). Its costs: eventual consistency between sides, more moving parts, and complexity that is *not* justified for simple CRUD. Read replicas are the right answer for "the same data, just more read capacity"; CQRS is for "fundamentally different read and write concerns."

---

## 🔴 Expert (15+ yrs)

### Q31. [Theory] When does eventual consistency become a business-correctness problem rather than just a UX nuance, and how do you architect around it?

Eventual consistency is fine for cosmetic data (like counts, feed ordering) but becomes a *correctness* hazard wherever a decision is made on possibly-stale data with real-world consequences: double-spending in payments, overselling inventory, granting access on a revoked permission, or distributed counters used for billing.

The architectural moves: **isolate the strongly-consistent core.** Most systems are 95% eventually-consistent with a small kernel that demands linearizability — put that kernel behind a consensus-backed store (a single-shard transaction, a Raft/Paxos group, or a serializable DB) and keep it small so the cost is contained. Use **idempotency keys** so retries don't double-apply. Use **compensating transactions / Sagas** for cross-service workflows where you can't hold a global transaction — accept temporary inconsistency, then reconcile or roll back via compensating actions. Use **invariant-preserving designs** like reserving inventory atomically before confirming an order. The expert judgment is knowing *which* invariants are sacred (money, inventory, auth) and paying for consistency only there, while letting the long tail of the system be cheap and available.

### Q32. [Theory] Explain the difference between consensus (Paxos/Raft) and quorum replication, and where each belongs in an architecture.

**Quorum replication** (Dynamo-style W/R/N) is about *durability and read freshness* across replicas of a value; it does **not** by itself give you a total order of operations or leader election — concurrent writes can conflict and need reconciliation (vector clocks, LWW, CRDTs). **Consensus** (Paxos, Raft, Zab) gives a set of nodes **agreement on a single ordered log of operations** despite failures: a replicated state machine where every node applies the same commands in the same order. Consensus provides linearizability and is the basis for leader election, distributed locks, and configuration metadata.

```
Quorum repl:  N copies, W+R>N → fresh reads, but no global order (reconcile conflicts)
Consensus:    agree on ONE ordered log → linearizable, leader-based (Raft), survives minority loss
```

Where each belongs: use **consensus for the control plane** — cluster membership, leader election, config, distributed locks, the metadata that *must* be globally agreed (etcd/ZooKeeper/Consul use Raft/Zab). Use **quorum replication for the data plane** — bulk user data where you want tunable availability/latency and can tolerate or resolve conflicts (Cassandra/Dynamo). Consensus is more expensive (a round of voting per decision, needs a majority alive, throughput-limited by the leader), so you keep it off the hot data path. Many production systems combine them: consensus for metadata/sharding decisions, quorum or async replication for the data itself.

### Q33. [Practical] You're the architect for a global payments platform that must be both highly available and never double-charge. How do you reconcile that with CAP/PACELC?

This is the canonical "you can't have it all, so design the seams" problem. Never-double-charge is a **strong-consistency** requirement (CP); global availability pushes toward AP. The resolution is to **decompose by invariant and by geography rather than treating the whole system as one CAP choice.**

- **Idempotency at the edge:** every charge carries a client-generated idempotency key; the system records the outcome of each key so retries (inevitable under partitions and timeouts) are safe and never double-apply. This converts "exactly once" (impossible) into "at-least-once + idempotent = effectively once."
- **Strongly-consistent ledger core:** the money-moving ledger is CP — it lives in a consensus-backed, serializable store, sharded by account so each account's writes are linearizable within a single shard (avoiding global coordination). During a partition the *affected* shard chooses consistency (reject/queue), not the whole platform.
- **Asynchronous, available periphery:** notifications, receipts, fraud scoring, analytics are AP/eventually consistent — they can lag without risking correctness.
- **Sagas for cross-service flows:** a payment touching multiple services uses a saga with compensating transactions instead of a global 2PC, so a partition degrades gracefully (pending state) rather than locking funds.
- **Geo-partitioning:** keep an account's authoritative shard in one region to avoid cross-region consensus on the hot path; replicate read-only views elsewhere.

The architectural philosophy: shrink the CP surface to the smallest possible kernel (the ledger), make everything around it idempotent and eventually consistent, and let *individual shards* make the CAP choice locally so a regional partition never takes down the world.

### Q34. [Behavioral] Tell me about a time you had to convince a team to accept eventual consistency (or reject it) against their instinct. How did you drive the decision?

A strong answer uses a structure like **STAR** and demonstrates technical leadership, not just technical knowledge.

*Situation/Task:* A team building a notification-count feature insisted on reading the exact unread count from the primary on every page load, which was projected to add ~30K QPS of point reads to an already-strained primary.

*Action:* Rather than mandate, I quantified the trade-off: I ran the back-of-the-envelope numbers showing the read load and the p99 latency hit, then framed the consistency question in *product* terms — "does a user being shown 5 unread instead of 6 for two seconds cause harm?" The product owner agreed it didn't. I proposed an eventually-consistent counter cached in Redis with a 2-second TTL and a read-your-writes carve-out (when *you* mark something read, you see it instantly via a local update). I de-risked it with a feature flag and a dashboard tracking staleness.

*Result:* Primary read load dropped ~90%, p99 improved, and zero user complaints about count accuracy. The lasting lesson the team internalized: *consistency is a product decision with an engineering cost, not a default* — pay for it where invariants are real, and make staleness a measured, bounded SLO elsewhere.

The meta-point interviewers look for: you led with data, translated the technical trade-off into business risk, gave the skeptics a safety net (flag + observability), and the decision was *reversible*. Driving consensus is about reducing the perceived risk of the unfamiliar choice.

### Q35. [Practical] How do you design for graceful degradation and avoid correlated failures in a large microservice estate?

The enemy at scale is the **correlated failure**: a single root cause (a shared dependency, a config push, a retry storm) takes out many services at once, defeating redundancy.

Principles I architect around:
- **Identify and minimize shared fate:** a config service, an auth service, or a single database that everyone depends on is a correlated-failure amplifier. Cache its results locally with stale-while-revalidate so its outage degrades rather than kills.
- **Criticality tiers and load shedding:** classify endpoints (checkout = critical, recommendations = optional). Under stress, shed the optional first. Serve a static fallback for the recommendation widget instead of erroring the whole page.
- **Bulkheads + circuit breakers everywhere on the call graph**, so one slow dependency can't exhaust shared thread/connection pools and cascade.
- **Retry budgets and jitter:** cap retries as a *fraction* of traffic, with full jitter, to prevent retry storms from converting a brief blip into a sustained overload. A blind exponential-backoff retry on every client *synchronizes* the herd unless jittered.
- **Cell-based / shuffle-sharding architecture:** partition the fleet into independent cells so a poison-pill request or a bad tenant only blast-radii one cell. Shuffle sharding (AWS) assigns each tenant a unique combination of nodes so no two tenants fully overlap — a single bad tenant degrades a tiny fraction of others.
- **Test it:** chaos engineering (fault injection, latency injection, dependency kill) and **game days** verify the degradation paths actually work before a real incident does the testing for you.

The expert framing: redundancy protects against *independent* failures; the real engineering is hunting down and severing *correlation* — shared dependencies, synchronized retries, and unbounded blast radius.

### Q36. [Theory] What are CRDTs and when would you choose them over consensus or last-write-wins?

**CRDTs (Conflict-free Replicated Data Types)** are data structures designed so that concurrent updates on different replicas can be merged automatically into a consistent result **without coordination** — the merge function is commutative, associative, and idempotent, so replicas converge regardless of message order or duplication. Examples: G-Counters and PN-Counters (counters), OR-Sets (add/remove sets), and RGA/LSEQ sequences (collaborative text).

You choose CRDTs when you need **high availability and low latency under partition** (AP) *but* also need automatic, correct conflict resolution rather than the data loss of last-write-wins (LWW). LWW silently discards a concurrent update based on a timestamp — dangerous under clock skew. CRDTs instead *preserve both intents* per the type's semantics (e.g., a PN-Counter sums all increments/decrements; an OR-Set keeps an add unless explicitly removed).

The trade-offs: CRDTs carry metadata overhead (tombstones, version vectors) that can grow, the available semantics are limited (you can express counters and sets cleanly, but not arbitrary invariants like "balance ≥ 0"), and they give you *strong eventual consistency*, not linearizability. So: use CRDTs for collaborative editing (Figma, Google Docs-style), shopping carts, presence, and distributed counters where availability trumps a global order; use **consensus** when you need a true linearizable order or invariants CRDTs can't express; and avoid bare LWW whenever silently losing a concurrent write would be a correctness bug.

---

## ✅ Key Takeaways

- **Scale up first, out second.** Vertical scaling is simpler; go horizontal when you hit the ceiling or need redundancy — and that demands stateless compute with state pushed to dedicated stores.
- **Statelessness is the enabler.** Interchangeable nodes make load balancing, auto-scaling, and failover trivial. Keep session state in Redis or signed tokens, not in process memory.
- **Cache in layers** (client → CDN → app/near-cache → distributed → DB). Cache-aside is the default; defend hot keys against stampedes (single-flight, jittered TTL) and handle invalidation deliberately.
- **CAP is about behavior during a partition (CP vs AP); PACELC adds the latency-vs-consistency dial for normal operation** — which is where systems spend 99.9% of their time.
- **Consistency is a spectrum and a product decision.** Shrink the strongly-consistent kernel (money, inventory, auth) to the minimum; make the rest eventually consistent, idempotent, and observable.
- **Know your numbers.** Back-of-the-envelope estimation and the latency table (RAM ≈ 100ns, in-DC RTT ≈ 0.5ms, cross-continent ≈ 150ms) turn intuition into defensible design decisions.
- **Consistent hashing + virtual nodes** is the standard for partitioning caches and stateful stores with minimal rebalancing.
- **Design for tail latency and correlated failure**, not just the average and not just independent faults — timeouts, circuit breakers, bulkheads, jittered retry budgets, and cell-based isolation.

## ⚠️ Common Pitfalls

- **Forgetting partitions are mandatory** — debating "CA" as if you could choose it. In a distributed system, P is given; you only choose CP vs AP.
- **No timeouts on downstream calls**, leading to thread-pool exhaustion and cascading failure when one dependency slows down.
- **Per-node rate limiters** that multiply the intended limit by the fleet size; rate limits need shared atomic state (Redis + Lua).
- **Naive `hash(key) % N` sharding** that remaps nearly every key when node count changes — use consistent hashing instead.
- **Cache stampede** (thundering herd) when a hot key expires and thousands of requests miss at once — mitigate with single-flight and jittered expiry.
- **Trusting last-write-wins** under clock skew, silently dropping concurrent writes — use version vectors or CRDTs when correctness matters.
- **Reporting only mean latency.** At fan-out scale, p99/p99.9 dominate user experience; the slowest dependency gates the whole response.
- **Read replicas without read-your-writes handling**, so users don't see their own just-submitted data due to replication lag.
- **Retry storms** from un-jittered exponential backoff that synchronize the herd and turn a blip into an outage.
- **Sharding too early.** It permanently raises operational complexity; exhaust caching and read replicas first.

## 📚 Further Reading

- **Martin Kleppmann, *Designing Data-Intensive Applications* (2nd ed., 2024/2026 update)** — the definitive treatment of replication, partitioning, consistency, and consensus.
- **Alex Xu, *System Design Interview* Vol. 1 & 2** — practical, interview-focused walkthroughs with estimation and component design.
- **Brendan Burns, *Designing Distributed Systems* (2nd ed.)** — reusable patterns (sidecars, bulkheads, sharding) for distributed architectures.
- **Google SRE Book & SRE Workbook (sre.google/books)** — load shedding, cascading failures, SLOs, and "Addressing Cascading Failures."
- **Daniel Abadi, "Consistency Tradeoffs in Modern Distributed Database System Design" (PACELC paper)** — the original PACELC formulation.
- **AWS Builders' Library (aws.amazon.com/builders-library)** — production essays on timeouts, retries with jitter, shuffle sharding, and health checks.
