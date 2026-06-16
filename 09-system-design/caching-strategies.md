# Caching Strategies & CDN Design

A staff-level, interview-grade reference on caching at every layer of a system: the read/write patterns (cache-aside, write-through, write-behind), eviction and invalidation policies, the failure modes that bite at scale (thundering herd, hot keys, cache penetration), multi-tier and near-cache architectures, CDN and edge caching, and the consistency trade-offs that make caching the second-hardest problem in computer science. Knowledge current through 2026.

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

### Q1. [Theory] What is a cache, and why is caching one of the highest-leverage optimizations in system design?

A **cache** is a smaller, faster store that holds a copy of data that is expensive to fetch or compute, so that future requests for the same data are served from the fast store instead of re-doing the expensive work. "Expensive" can mean a slow disk read, a database query that joins five tables, a call across the ocean to an origin server, or a CPU-heavy computation.

Caching is high-leverage because of two facts about real workloads. First, **access is skewed** — a small fraction of keys gets the overwhelming majority of traffic (the Pareto / Zipf distribution). Caching that hot subset offloads most of the load from the slow tier. Second, **the latency gap between tiers is enormous**: RAM is ~100 ns, an SSD random read ~16 µs, a same-datacenter round trip ~0.5 ms, and a cross-continent round trip ~150 ms. Moving a hot item one tier up the hierarchy can be a 100×–1000× speedup.

The mental model is a hierarchy where each layer absorbs load from the one behind it:

```
CPU registers  →  L1/L2/L3  →  RAM  →  local SSD  →  distributed cache (Redis)  →  database  →  disk/origin
   ~1 ns           ~1–10 ns     ~100 ns   ~16 µs        ~0.5 ms                     ~1–10 ms     ~10 ms+
```

The reason caching is also *hard* — and why it dominates interviews — is that a cache is a second copy of the truth, and keeping two copies in agreement (invalidation) under concurrency, failures, and skew is genuinely difficult. The win is latency and cost; the price is consistency and complexity.

### Q2. [Theory] Define cache hit, cache miss, and hit ratio. Why does a small drop in hit ratio matter so much?

A **cache hit** is when a requested item is found in the cache and served directly. A **cache miss** is when it is absent, forcing a fetch from the slower backing store (and usually a write into the cache for next time). The **hit ratio** is `hits / (hits + misses)` — the fraction of requests served by the cache.

A small drop in hit ratio matters disproportionately because the *misses* are what hit your expensive tier, and that tier is sized for the miss rate, not the request rate. Consider 100,000 requests/sec against a database that can serve 10,000 queries/sec:

```
Hit ratio 99%  → misses = 1,000/s   → DB comfortably handles it
Hit ratio 95%  → misses = 5,000/s   → DB at half capacity
Hit ratio 90%  → misses = 10,000/s  → DB at 100% — no headroom, latency spikes
Hit ratio 80%  → misses = 20,000/s  → DB overloaded — cascading failure
```

Dropping from 99% to 90% — sounds like "only 9%" — actually **10×'d the load on the database**. This is why hit ratio is the headline metric for any cache, why warming and eviction tuning matter, and why a cache failure (effective hit ratio → 0%) is so catastrophic: the backing store suddenly sees the full, un-absorbed traffic it was never provisioned for. Always reason about misses in absolute QPS against backing-store capacity, not about the hit-ratio percentage in isolation.

### Q3. [Theory] What is the difference between cache eviction and cache invalidation?

These are two independent reasons an entry leaves the cache, and conflating them is a classic mistake.

**Eviction** is a *capacity* decision: the cache is full and must make room for a new entry, so it discards some existing entry according to an eviction policy (LRU, LFU, etc.). The evicted data is *not necessarily stale* — it was simply the least valuable to keep. Eviction is about managing a finite resource.

**Invalidation** is a *correctness* decision: an entry no longer reflects the source of truth because the underlying data changed, so it must be removed or refreshed regardless of how much space is free. If you fail to invalidate, you serve **stale data** silently — the cache happily returns a wrong-but-fast answer.

```
Eviction:      "I'm out of room"          → drop the least useful entry (still correct, just gone)
Invalidation:  "this entry is now wrong"  → drop/refresh the changed entry (correctness)
```

A well-designed cache handles both: an eviction policy (often LRU) bounds memory, and an invalidation strategy (TTL, explicit delete on write, versioned keys, event-driven) bounds staleness. TTL is interesting because it serves double duty — it is a crude invalidation mechanism (data is assumed stale after N seconds) and a soft eviction signal.

### Q4. [Theory] Explain the common eviction policies: LRU, LFU, FIFO, and TTL. When is each appropriate?

Eviction policies decide *which* entry to discard when the cache is full. The goal is to keep the entries most likely to be requested again.

| Policy | Evicts | Strength | Weakness |
|--------|--------|----------|----------|
| **LRU** (Least Recently Used) | The entry untouched the longest | Matches temporal locality; great general default | A one-time scan of many items can flush hot data ("cache pollution") |
| **LFU** (Least Frequently Used) | The least-accessed entry | Excellent when popularity is stable | Holds onto formerly-hot items; cold-start bias toward new items unless aged |
| **FIFO** (First In First Out) | The oldest-inserted entry | Trivial to implement | Ignores access patterns entirely; can evict a hot item |
| **TTL** (Time To Live) | Any entry older than its expiry | Bounds staleness; simple | Not load-aware; expiry storms if many keys share a TTL |
| **Random** | A random entry | O(1), no metadata, surprisingly decent | No locality awareness |

**LRU is the sensible default** because most workloads exhibit temporal locality (recently used → likely used again). **LFU** wins when popularity is durable (a catalog where the same top products are hot for weeks) but needs *aging* (windowed/decaying LFU) to avoid clinging to yesterday's stars. **TTL** is essential whenever data has a natural freshness bound or you can't easily invalidate on write. **FIFO/Random** are for simplicity-first or metadata-constrained environments. Production caches like Caffeine and modern Redis use hybrids: Caffeine's **W-TinyLFU** combines a frequency sketch with a recency window to beat plain LRU on hit ratio, and Redis offers `allkeys-lru`, `allkeys-lfu`, `volatile-ttl`, and `allkeys-random` so you can match the policy to the data.

### Q5. [Theory] Compare the read-through cache to the cache-aside pattern.

Both serve reads from a cache backed by a database; they differ in *who owns the read-and-populate logic*.

In **cache-aside (lazy loading)**, the application owns the logic. On a read, the app checks the cache; on a miss it queries the DB, writes the result into the cache, and returns it. The cache is a passive key-value store that knows nothing about the database.

In **read-through**, the cache itself owns the logic. The application asks *only the cache*; on a miss, the cache (via a loader/provider you configure) fetches from the DB, stores the value, and returns it. The application never talks to the DB for reads.

```
Cache-aside (app-managed):              Read-through (cache-managed):
  app → cache (miss)                      app → cache (miss)
  app → DB                                        └→ loader → DB
  app → cache.put                                 └→ cache.put (internal)
  app ← value                             app ← value
```

The trade-off is about coupling and consistency. **Cache-aside** is the most common pattern (Redis in front of Postgres) because it is simple, the app survives a cache outage (just hit the DB), and you cache only what's requested. Its downsides: the population logic is duplicated at every call site, and there's a window for stale data. **Read-through** centralizes the loading logic (less duplication, consistent behavior) and is what library caches like Caffeine's `LoadingCache` give you, but it couples the cache to your data-access layer and the cache becomes a hard dependency on the read path. In interviews, "cache-aside" is the safe default answer; mention read-through as the cleaner abstraction when an in-process or provider-backed cache is available.

### Q6. [Theory] What is the difference between write-through and write-behind (write-back) caching?

Both are write strategies that keep the cache populated on writes (so subsequent reads are warm), differing in *when* the database is updated.

**Write-through:** every write goes to the cache *and synchronously* to the database before the write is acknowledged. The cache and DB are always in agreement on committed data, and reads are warm. The cost is write latency — every write pays both the cache and the DB round trip — and you may cache data that is never read again.

**Write-behind (write-back):** the write goes to the cache and is acknowledged immediately; the cache asynchronously flushes to the DB later, often batching and coalescing multiple updates to the same key. Writes are extremely fast and the DB sees far fewer operations. The danger is **durability**: if the cache node dies before flushing, those writes are lost; and reading the DB directly can show stale data until the flush lands.

```
Write-through:  app → cache → DB (sync) → ack            (durable, slower writes)
Write-behind:   app → cache → ack ;  cache ⇢ DB (async)  (fast, risk of loss)
```

Use **write-through** when you need the cache and DB consistent and can tolerate the write latency (configuration data, reference data). Use **write-behind** when write throughput dominates and some loss is acceptable — metrics, counters, view counts, "last seen" timestamps — ideally backed by a replicated/persistent cache (Redis with AOF, or a write-ahead log) to bound the loss window. Many real systems combine write-through-on-the-cache with cache-aside-on-reads.

### Q7. [Practical] You add a Redis cache in front of your database and latency barely improves. What do you check?

I'd work through a short diagnostic checklist, because "added a cache, no improvement" almost always traces to one of a handful of causes.

First, **measure the hit ratio** (`redis-cli INFO stats` → `keyspace_hits` / `keyspace_misses`). If it's low, the cache isn't doing its job. Common reasons: TTL too short (entries expire before re-use), keys are too unique (caching per-request data that's never requested twice — e.g., a cache key that includes a timestamp or a request ID), or the working set is larger than the cache so entries are evicted before re-use (check `evicted_keys` and `used_memory` vs `maxmemory`).

Second, **check what fraction of latency the cache could even remove.** If the endpoint's latency is dominated by something *other* than the DB read — a slow downstream call, serialization, an N+1 query the cache doesn't cover, or TLS/handshake overhead — then caching the DB read won't move the needle. Profile where the time actually goes.

```
Symptom                          Likely cause                         Fix
low hit ratio + high evictions   cache too small / TTL too short      grow cache, raise TTL, fix key design
low hit ratio + few evictions    keys too unique / not reused         normalize cache keys, cache higher up
high hit ratio, no speedup       Redis round trip ≈ DB latency        add in-process near-cache; pipeline
high hit ratio, no speedup       latency is elsewhere (not the read)  profile; cache the real bottleneck
```

Third, **is the Redis round trip itself the floor?** If your DB read is ~1 ms in-datacenter and your Redis hop is also ~0.5–1 ms, the cache can't help much. The fix is an **in-process (near) cache** like Caffeine for the hottest keys, eliminating the network hop entirely — turning ~1 ms into ~1 µs. Finally, confirm you're not serializing/deserializing a huge object on every hit; a big JSON blob can make the "fast" path slow.

### Q8. [Practical] Walk through where you'd place caches in a typical read-heavy web architecture.

Caching is layered, and each layer absorbs load from the one behind it. The art is choosing what to cache where, with what TTL and what consistency guarantee.

```
Client (browser cache, localStorage, service worker)
   │   Cache-Control, ETag, max-age          ← zero network for repeat assets
CDN / edge cache (Cloudflare, CloudFront, Fastly)
   │   absorbs static assets + cacheable GETs ← serves from a PoP near the user
Reverse proxy / API gateway cache (nginx, Varnish)
   │   shared edge in front of origin
Application in-process cache (Caffeine, Guava)
   │   microsecond access, per-node, no network hop
Distributed cache (Redis / Memcached)
   │   shared across all app nodes
Database buffer pool + materialized views
   │
Disk
```

In practice I cache **immutable assets at the browser/CDN** with long TTLs and content-hashed filenames (`app.9f3c.js`, `max-age=31536000, immutable`). I cache **hot, read-mostly objects in a distributed Redis** with cache-aside and a TTL tuned to the data's tolerance for staleness. For the very hottest keys I add a small **in-process Caffeine near-cache** to kill the Redis network hop. The key trade-off rises as you go *up* the stack: higher layers are faster and offload more, but they are harder to invalidate (you can delete a Redis key instantly, but a browser cache will hold a stale asset for its full TTL). That's why higher layers cache more *immutable* or *staleness-tolerant* data, and why fingerprinted URLs are the standard for the CDN/browser tiers — they sidestep invalidation entirely.

### Q9. [Theory] What is TTL, and how do you choose a good TTL value?

**TTL (Time To Live)** is the maximum age of a cache entry; once it expires, the next read treats the key as a miss and refetches. TTL is the simplest invalidation mechanism — you accept up to TTL seconds of staleness in exchange for never having to actively invalidate.

Choosing a TTL is a direct trade-off between **freshness and load**. A short TTL means fresher data but more misses (more load on the backing store, lower hit ratio). A long TTL means a higher hit ratio and less load but more staleness. The right value comes from the *business* tolerance for staleness, not a default:

```
Data type                     Tolerable staleness      TTL
Live stock price / inventory  seconds                  1–5 s (or no cache + push invalidation)
User profile / settings       seconds to a minute      30–60 s
Product catalog description   minutes                  5–15 min
Reference data (country list) hours to days            hours, or version-keyed
Static asset (fingerprinted)  effectively forever      1 year (immutable)
```

Two refinements matter at scale. First, **add jitter** — if thousands of keys are written at the same instant with the same TTL, they all expire together and cause a synchronized miss storm (expiry stampede). Randomizing TTL by ±10% spreads expirations out. Second, consider **TTL + active invalidation together**: use a generous TTL for the hit-ratio benefit, but also delete/update the key on write so you usually get fresh data, with the TTL acting only as a safety net against missed invalidations.

### Q10. [Coding] Implement an LRU cache with O(1) get and put.

**Problem:** Build a fixed-capacity cache supporting `get(key)` and `put(key, value)` in O(1) time, evicting the least-recently-used entry when full. This is the canonical caching coding question.

**Approach 1 — `LinkedHashMap` (concise, production-friendly):** Java's `LinkedHashMap` with access-order does exactly this.

```java
import java.util.LinkedHashMap;
import java.util.Map;

public class LruCache<K, V> extends LinkedHashMap<K, V> {
    private final int capacity;

    public LruCache(int capacity) {
        super(capacity, 0.75f, true); // accessOrder = true → reorders on access
        this.capacity = capacity;
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
        return size() > capacity; // auto-evicts the LRU entry when over capacity
    }
}
```

**Approach 2 — explicit HashMap + doubly linked list (what interviewers want you to derive):** A hash map gives O(1) lookup; a doubly linked list maintains recency order so the head is most-recently-used and the tail is the eviction victim. Sentinel head/tail nodes remove null-checks at the boundaries.

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
        head.next = tail; tail.prev = head;
    }

    public int get(int key) {
        Node n = map.get(key);
        if (n == null) return -1;
        moveToFront(n);            // mark most-recently-used
        return n.value;
    }

    public void put(int key, int value) {
        Node n = map.get(key);
        if (n != null) { n.value = value; moveToFront(n); return; }
        if (map.size() == capacity) {
            Node lru = tail.prev;  // least-recently-used victim
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
    private void remove(Node n) { n.prev.next = n.next; n.next.prev = n.prev; }
    private void moveToFront(Node n) { remove(n); addToFront(n); }
}
```

**Time:** O(1) for both `get` and `put`. **Space:** O(capacity).

**Edge cases:** capacity 0 (reject or treat as no-op); updating an existing key must refresh recency *and* the value; the sentinels eliminate boundary null-checks. For thread safety in production, do **not** hand-synchronize this — reach for Caffeine, which gives you concurrent access, W-TinyLFU eviction, TTL, and single-flight loading for free.

### Q11. [Practical] What kinds of data should you NOT cache?

Caching is not free — every cached item adds a consistency liability and consumes memory — so the decision to cache is a judgment call, not a reflex. I'd avoid caching several categories.

**Highly dynamic, write-heavy data with low re-read.** If a value changes more often than it's read, the cache is almost always serving stale data or being invalidated immediately — you pay the cache's complexity for no hit-ratio benefit. **Per-request unique data** (search results with a free-text query, anything keyed by a request ID or precise timestamp) has near-zero reuse, so it pollutes the cache and lowers the hit ratio for genuinely hot keys.

**Sensitive data without careful handling.** Caching auth tokens, PII, or payment data in a shared cache widens the blast radius of a breach and complicates compliance (GDPR right-to-erasure means you must be able to purge it). If you must cache it, encrypt it, scope TTLs tightly, and never cache it at a shared CDN edge. **Data where staleness causes correctness bugs** — account balances used for authorization decisions, inventory counts at the point of sale, permission/revocation state — should either not be cached or cached only with aggressive invalidation and a clear understanding that the source of truth is consulted for the actual decision.

The framing I use: cache data that is **read far more than written, tolerant of bounded staleness, and shared across requests**. If a candidate item fails any of those three tests, I question whether caching it is worth the consistency cost. The worst outcome is a cache that adds complexity and a stale-data risk while barely improving the hit ratio.

---

## 🟡 Intermediate (3–7 yrs)

### Q12. [Theory] Compare cache-aside, write-through, and write-behind across consistency, latency, and durability.

These three patterns are the core vocabulary of caching, and an interviewer wants to hear you reason about the trade-offs, not just recite definitions.

| Dimension | Cache-aside | Write-through | Write-behind |
|-----------|-------------|---------------|--------------|
| **Read path** | App checks cache, on miss loads DB + populates | Reads warm (writes pre-populate) | Reads warm |
| **Write path** | Write DB, then invalidate/update cache | Write cache + DB synchronously | Write cache, flush DB async |
| **Write latency** | DB latency only | Cache + DB (highest) | Cache only (lowest) |
| **Consistency** | Window between DB write and invalidation | Strong (cache == DB after ack) | Weak until flush |
| **Durability on cache loss** | Safe (DB is source of truth) | Safe | **Data loss risk** if unflushed |
| **Survives cache outage** | Yes (fall through to DB) | Reads degrade | Reads + recent writes at risk |
| **Caches unread data?** | No (lazy) | Yes (eager) | Yes (eager) |

The decision logic: **cache-aside is the default** for general read-heavy workloads because it's resilient (DB stays the source of truth, cache failure is survivable) and lazy (caches only what's used). Its weakness is the read-after-write race — between writing the DB and updating the cache, a concurrent read can repopulate the cache with the *old* value. **Write-through** trades write latency for read warmth and tighter consistency, good for data that's read soon after write. **Write-behind** maximizes write throughput at the cost of durability, reserved for loss-tolerant high-volume data.

```
The cache-aside stale-write race:
  T1: writer updates DB (new value)
  T2: reader misses, reads DB... but gets OLD value (before T1 commits / due to replica lag)
  T3: writer deletes cache key
  T4: reader writes its OLD value into cache  ← stale, and it sticks until TTL
Mitigation: short TTL + delete-on-write, or "delete cache, then write DB, then delete again" (double-delete), or versioned keys.
```

### Q13. [Theory] Explain the thundering herd / cache stampede problem and three ways to mitigate it.

A **thundering herd (cache stampede)** happens when a hot key expires (or is evicted) and many concurrent requests all miss at the same instant, so they *all* fall through to the expensive backing store simultaneously. A key serving 50,000 req/s that expires can dump 50,000 simultaneous queries onto a database sized for the cached load — instant overload, possibly a cascading failure.

```
Before expiry:  50k req/s → cache (hit) → DB sees ~0
At expiry:      50k req/s → cache (MISS) → 50k req/s → DB  💥 (sized for ~hundreds)
```

Three mitigations, often combined:

1. **Request coalescing / single-flight:** ensure only *one* request recomputes the value per node; the rest wait for that single computation and share its result. Caffeine's `LoadingCache` does this automatically per node; for a distributed setting, use a short-lived Redis **mutex/lock key** (`SET lock:key NX EX 5`) so only the lock-winner repopulates and others briefly back off or serve stale.

2. **Probabilistic early recomputation (XFetch):** rather than waiting for hard expiry, each read computes a probability of *proactively* refreshing that rises as the entry nears its TTL. One lucky request refreshes the value *before* it expires, so the key never actually goes cold for the herd. The classic formula recomputes when `now − delta · beta · ln(rand()) ≥ expiry`.

3. **Jittered TTLs + stale-while-revalidate:** add randomness to TTLs so keys don't expire in lockstep, and serve the *stale* value to readers while a single background task refreshes it (the entry is never simultaneously absent *and* in demand). This is the same `stale-while-revalidate` idea HTTP caching uses.

In production I default to single-flight (Caffeine near-cache or Redis lock) plus jittered TTLs, and add stale-while-revalidate for keys whose recomputation is genuinely expensive.

### Q14. [Coding] Implement single-flight request coalescing so concurrent misses trigger only one load.

**Problem:** When many threads request the same missing key concurrently, only one should execute the expensive loader; the others must wait for and share that single result. This is the core defense against the thundering herd.

```java
import java.util.concurrent.*;

public class SingleFlightCache<K, V> {
    private final ConcurrentMap<K, V> cache = new ConcurrentHashMap<>();
    // Tracks in-flight loads so duplicate requests join the existing one.
    private final ConcurrentMap<K, CompletableFuture<V>> inFlight = new ConcurrentHashMap<>();

    public V get(K key, Callable<V> loader) throws Exception {
        V cached = cache.get(key);
        if (cached != null) return cached;            // fast path: hit

        // computeIfAbsent guarantees exactly one future is created per key.
        CompletableFuture<V> future = inFlight.computeIfAbsent(key, k -> {
            CompletableFuture<V> f = new CompletableFuture<>();
            // The thread that created the future is the one that loads.
            CompletableFuture.runAsync(() -> {
                try {
                    V value = loader.call();
                    cache.put(k, value);
                    f.complete(value);
                } catch (Exception e) {
                    f.completeExceptionally(e);
                } finally {
                    inFlight.remove(k);               // allow future reloads
                }
            });
            return f;
        });
        return future.get();                          // all callers await the single load
    }
}
```

**Time:** O(1) bookkeeping; the loader runs exactly once per concurrent burst. **Space:** O(distinct in-flight keys).

**Edge cases:** if the loader throws, all waiters receive the exception (don't cache the failure permanently — consider brief negative caching with a short TTL instead). The `inFlight.remove` in `finally` is essential so a transient failure doesn't permanently wedge the key. In a *distributed* fleet, single-flight per node still lets up to N nodes each load once — to coalesce *globally* you need a shared Redis lock so only one node across the cluster recomputes. Note `computeIfAbsent`'s mapping function must be non-blocking; here it only *schedules* the async load, it doesn't run the loader inside the map's lock.

### Q15. [Theory] What is cache penetration, and how is it different from a stampede? How do you defend against it?

**Cache penetration** is when requests are for keys that **do not exist in the backing store at all**, so they always miss the cache *and* always miss the database — the cache provides zero protection. This is distinct from a stampede (many requests for a key that *does* exist but just expired). Penetration is often malicious: an attacker requests random non-existent IDs (`/user/9999999999`) to drive load straight through to the database.

```
Stampede:    key EXISTS, expired → herd reloads the real value (transient)
Penetration: key DOES NOT EXIST → every request misses cache AND DB (persistent, often an attack)
```

Two standard defenses:

1. **Negative caching:** cache the "not found" result itself, with a *short* TTL (e.g., 30–60 s). The first lookup for a missing key hits the DB, learns it doesn't exist, and caches a tombstone/null marker so subsequent lookups are absorbed by the cache. The short TTL bounds the window in which a key that later gets created still returns "not found." The risk is an attacker enumerating a huge space of distinct missing keys filling the cache with tombstones — bound it with eviction.

2. **Bloom filter (or cuckoo filter) of existing keys:** maintain a probabilistic set membership filter of all keys that *do* exist. Before hitting the DB, check the filter; if it says "definitely not present," reject immediately. A Bloom filter has no false negatives (it never wrongly says a present key is absent) but allows false positives (occasionally lets a non-existent key through, which then hits the DB — acceptable). This is extremely memory-efficient (~10 bits/key for ~1% false-positive rate) and is how systems shield databases from enumeration attacks.

In practice I combine both: a Bloom filter to reject the bulk of bogus keys cheaply, plus negative caching for the false-positive trickle that gets through.

### Q16. [Practical] Your cache and database disagree — users report seeing stale data. How do you debug and design against this?

First I'd characterize the staleness: *how* stale, *which* keys, and *after what event*. The usual culprit is the **read-after-write race** in cache-aside — a writer updates the DB and deletes the cache key, but a concurrent reader that missed *just before* the delete repopulates the cache with the pre-update value, which then sticks until TTL. Reproducing it usually means inducing concurrency around a write.

The ordering of operations matters enormously. The common mistake is **update cache, then update DB** (or *write* the cache on update instead of deleting it), which races badly. The more robust patterns:

```
Recommended on write:           DELETE the cache key, then write the DB
                                (next read repopulates fresh; never write a value you computed pre-commit)

Stronger (double-delete):       delete cache → write DB → delay a few hundred ms → delete cache again
                                (the second delete clears any stale value a racing reader repopulated)

Strongest (versioned keys):     write DB with version v8 → cache key becomes `user:42:v8`
                                (old `:v7` entries are simply never read again; no race window)
```

Design-level defenses I'd put in place: **delete (don't update) the cache on write** so a stale value can't be written from a pre-commit read; keep a **short TTL** as a backstop so any missed invalidation self-heals; for the strict cases, move to **versioned/generation keys** so a stale entry is structurally unreachable; and for multi-tier setups, propagate invalidations over **pub/sub** to the in-process near-caches (which can't be invalidated by deleting a Redis key). If the DB itself has read-replica lag and the cache loads from a replica, that lag *is* the staleness source — load cache fills from the primary or a read-your-writes-safe path. Finally, I'd add **observability**: log cache writes with the source value's version/timestamp so I can prove which path served stale data.

### Q17. [Coding] Implement cache-aside read and write with a TTL and a jittered expiry.

**Problem:** Implement the cache-aside pattern with Redis-style operations: read checks the cache then loads on miss; write updates the DB and invalidates the cache; TTLs are jittered to avoid synchronized expiry.

```java
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

public class CacheAsideRepository {
    private final RedisClient cache;   // get / setEx / del
    private final Database db;          // load / save
    private final Duration baseTtl;

    public CacheAsideRepository(RedisClient cache, Database db, Duration baseTtl) {
        this.cache = cache; this.db = db; this.baseTtl = baseTtl;
    }

    public String read(String key) {
        String cached = cache.get(key);
        if (cached != null) return cached;              // hit

        String value = db.load(key);                    // miss → load source of truth
        if (value == null) {
            // negative caching: short TTL tombstone to absorb penetration
            cache.setEx(key, "__NULL__", jitter(Duration.ofSeconds(30)));
            return null;
        }
        cache.setEx(key, value, jitter(baseTtl));        // populate with jittered TTL
        return value;
    }

    public void write(String key, String value) {
        db.save(key, value);    // 1) write source of truth FIRST
        cache.del(key);         // 2) invalidate (delete, don't update) → next read refreshes
    }

    // ±10% jitter so a batch of keys written together don't all expire at the same instant.
    private Duration jitter(Duration base) {
        long ms = base.toMillis();
        long delta = (long) (ms * 0.10);
        return Duration.ofMillis(ms + ThreadLocalRandom.current().nextLong(-delta, delta + 1));
    }
}
```

**Time:** O(1) per operation plus the DB/cache round trips. **Space:** O(1) per key.

**Edge cases:** writing must **delete** the cache key, not set it to the new value — setting it re-opens the read-after-write race and can persist a stale value if a concurrent slow reader repopulates afterward. The `__NULL__` tombstone handles cache penetration but you must check for it on read (omitted for brevity) and bound its blast radius. Jitter is small but essential at scale. For strict ordering guarantees, layer a versioned key or the double-delete pattern on top.

### Q18. [Theory] What are hot keys (and hot shards), and what makes them so dangerous in a distributed cache?

A **hot key** is a single key that receives a disproportionate share of traffic — a celebrity's profile, a flash-sale product, a trending post. The danger is that **a single key lives on a single shard/node** in a partitioned cache (Redis Cluster, Memcached with consistent hashing). So no matter how many cache nodes you have, all traffic for that one key funnels to one node, which becomes a hot spot — saturating its CPU and network while the rest of the fleet idles. This breaks the core assumption of horizontal scaling: you can't shard your way out of a single-key bottleneck.

```
Consistent hashing spreads keys across shards... but one HOT key still lands on ONE shard:

  shard A  [ normal keys ]        ← 5k req/s
  shard B  [ HOT KEY    ]         ← 200k req/s  💥 (saturated; can't be split)
  shard C  [ normal keys ]        ← 5k req/s
```

Hot keys are dangerous beyond raw load: a hot key's node becomes a latency outlier that gates fan-out requests, its eviction can stampede, and during a resharding it's the hardest piece to move without disruption. Detection is itself a challenge — you need per-key request sampling (Redis `--hotkeys`, or sampling at the client) because aggregate node metrics may look fine while one key dominates.

Mitigations (covered in the next question) center on *replicating* the hot key so it's no longer served from a single place: client-side near-caching, key replication/splitting across nodes, or promoting it to an in-process cache on every app server. The meta-lesson: skew, not average load, is what kills distributed caches.

### Q19. [Practical] How do you detect and mitigate a hot key in a Redis cluster?

**Detection first.** Aggregate cluster metrics hide hot keys, so I'd look per-key: Redis `redis-cli --hotkeys` (uses `OBJECT FREQ`, requires an LFU maxmemory-policy), `MONITOR` on a brief sample (careful — it's expensive), or client-side sampling that counts key access frequency. A single node showing far higher CPU/network/ops than its peers is the tell. I'd also watch for a key whose latency p99 spikes while the cluster average is flat.

**Mitigations**, roughly in order of how much they help:

1. **Local (in-process) near-cache for hot keys.** Each app node caches the hot value in Caffeine for a short TTL, so 99%+ of reads never reach Redis at all. This is the single most effective fix — it turns one Redis node's problem into a fanned-out, per-app-server lookup. The cost is consistency (each node may be a second or two stale), handled with a short TTL or pub/sub invalidation.

2. **Key replication / splitting.** Store the hot value under N suffixed keys (`product:42#0` … `product:42#7`) spread across shards, and have clients read a random replica. This multiplies the serving capacity by N at the cost of N× the write/invalidation work (you must update all replicas).

```
read:  key = "product:42#" + random(0..N-1)   → spreads load across N shards
write: invalidate product:42#0 .. product:42#N-1  (fan-out write cost)
```

3. **Read replicas of the shard.** Add Redis replicas for the hot shard and serve reads from them (accepting replica lag).

4. **Promote to a CDN/edge** if the hot object is cacheable over HTTP — let the edge absorb it entirely.

In an incident I'd reach for the in-process near-cache immediately (fast, no data migration), then add key-splitting if writes are infrequent enough to tolerate the fan-out. The wrong move is trying to vertically scale the one hot node — it doesn't address the structural single-point funnel.

### Q20. [Theory] How does HTTP caching work — explain Cache-Control, ETag, and the difference between max-age and s-maxage?

HTTP caching lets browsers, proxies, and CDNs cache responses using response headers, so repeat requests are served without contacting the origin. The primary header is **`Cache-Control`**.

- **`max-age=N`** — the response is *fresh* for N seconds; within that window a cache serves it without revalidating. Applies to all caches.
- **`s-maxage=N`** — overrides `max-age` for *shared* caches (CDN, proxy) only; lets you cache longer at the CDN than in the browser (e.g., `max-age=0, s-maxage=600` → browser always revalidates, CDN holds for 10 min).
- **`no-cache`** — may store the response but *must revalidate* with the origin before each use (not "don't cache"!).
- **`no-store`** — never store at all (sensitive data).
- **`private` vs `public`** — `private` means only the browser may cache (not shared caches), for per-user data; `public` allows CDN/proxy caching.
- **`immutable`** — promises the content never changes, so the browser won't even revalidate on reload (pairs with fingerprinted URLs).

**Validators** enable *conditional revalidation* so a stale cache can cheaply confirm freshness without re-downloading:

```
First response:   ETag: "v8abc"   (a content hash/version)
Later request:    If-None-Match: "v8abc"
  unchanged →     304 Not Modified  (no body — cheap, just confirms freshness)
  changed   →     200 OK + new body + new ETag
```

`Last-Modified` / `If-Modified-Since` is the timestamp-based equivalent of `ETag` / `If-None-Match`. The standard production pattern: serve **fingerprinted static assets** with `max-age=31536000, immutable` (cache forever, URL changes on content change), and serve **HTML/API responses** with a short or zero `max-age` plus an `ETag` so caches revalidate cheaply. `stale-while-revalidate=N` lets a cache serve a stale response immediately while refreshing in the background — eliminating the revalidation latency hit.

### Q21. [Practical] A user updates their profile photo but still sees the old one for hours. The image is served via CDN. What's happening and how do you fix it?

This is the classic CDN invalidation problem. The image was served from a URL like `/avatars/user42.jpg` with a long `Cache-Control: max-age` (or default CDN caching), so when the user uploads a new photo, the CDN edge — and the user's browser — still hold the *old* bytes for that *same URL* until the TTL expires. The origin has the new image, but nothing told the caches the URL's content changed.

There are two fixes, and the difference between them is the heart of CDN design:

**Quick fix — purge:** issue a CDN purge/invalidation for `/avatars/user42.jpg` (Cloudflare/Fastly/CloudFront API). This forces edges to drop the cached object so the next request re-fetches from origin. Fastly does this in ~150 ms; older CloudFront can take minutes to propagate to every PoP. Purging works but is eventually consistent across PoPs, can be rate-limited, and doesn't fix the *browser* cache (which still has its own copy until its max-age expires).

**Proper fix — versioned/fingerprinted URLs:** never serve mutable content under a stable URL. Include a content hash or version in the URL: `/avatars/user42-9f3c2b.jpg` (or `?v=9f3c2b`). When the photo changes, the URL changes, so:

```
Old:  /avatars/user42.jpg   (max-age=1y)  → change content → must PURGE every cache (slow, partial)
New:  /avatars/user42-{hash}.jpg (immutable) → change content → new URL → nothing to invalidate
```

With versioned URLs there is *nothing to purge* — old and new coexist, the referencing page (served with a short TTL) points at the new URL, and both CDN and browser fetch fresh automatically. This is why every production frontend fingerprints assets. For this incident I'd purge to fix it *now*, then change the avatar pipeline to emit versioned URLs so it never recurs. For grouping (e.g., purge all of a user's assets at once), I'd use **surrogate keys / cache tags** so one purge call clears a related set.

---

## 🟠 Advanced (8–12 yrs)

### Q22. [Theory] Design a two-tier (near-cache + remote) caching architecture. What are the consistency challenges?

A **two-tier cache** puts a small **L1 in-process cache** (Caffeine) on every application node in front of a large shared **L2 distributed cache** (Redis), which itself sits in front of the database. A read checks L1 (microseconds, no network), then L2 (~0.5 ms, one network hop), then the DB. This is the standard "near-cache" pattern and it's how you serve hot keys without funneling all traffic to a single Redis node.

```
app node 1: [L1 Caffeine] ┐
app node 2: [L1 Caffeine] ┼─► [L2 Redis shared] ─► Database
app node N: [L1 Caffeine] ┘
   ~1 µs                      ~0.5 ms              ~1–10 ms
```

The win is huge: L1 absorbs the hottest keys with zero network cost and per-node fan-out (solving the hot-key funnel from Q18), while L2 provides a shared, larger cache so a cold node can warm from Redis instead of the DB.

The **consistency challenge** is that L1 is replicated across N nodes with no coordination — when the underlying data changes, you can delete the L2 Redis key, but **each node's L1 copy is invisible to that delete** and will keep serving stale data until its own TTL expires. Three approaches:

1. **Short L1 TTL** (e.g., 1–5 s) — bound staleness by time; simplest, no coordination, but every node still serves up to TTL seconds stale and re-fetches frequently.
2. **Pub/sub invalidation** — on write, publish an invalidation message on a Redis channel; every app node subscribes and evicts the key from its L1. Near-real-time, but it's best-effort (a node that missed the message, or just started, is stale) and adds a fan-out message per write.
3. **Versioned keys** — bake a generation/version into the key so a stale L1 entry is simply never looked up again.

In production I use a short L1 TTL *plus* pub/sub invalidation: the pub/sub gives fast convergence, and the TTL is the safety net for missed messages. I keep L1 small (only the hottest keys) so the staleness surface is minimal, and I never put data in L1 that can't tolerate a few seconds of staleness.

### Q23. [Coding] Implement a two-tier cache with pub/sub invalidation of the local tier.

**Problem:** Reads check an in-process L1 (Caffeine), then a shared L2 (Redis), then load. On a write, invalidate both tiers and broadcast so *every* node evicts its L1 copy.

```java
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;

public class TwoTierCache {
    private final Cache<String, String> l1 =
        Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofSeconds(5))  // safety-net TTL for missed pub/sub
                .build();
    private final RedisClient l2;          // get / setEx / del / publish / subscribe
    private final Database db;
    private static final String INVALIDATION_CHANNEL = "cache:invalidate";

    public TwoTierCache(RedisClient l2, Database db) {
        this.l2 = l2; this.db = db;
        // Every node subscribes; on a message it drops the key from its OWN L1.
        l2.subscribe(INVALIDATION_CHANNEL, key -> l1.invalidate(key));
    }

    public String read(String key) {
        String v = l1.getIfPresent(key);
        if (v != null) return v;                       // L1 hit (µs)

        v = l2.get(key);
        if (v != null) { l1.put(key, v); return v; }   // L2 hit → fill L1

        v = db.load(key);                              // miss → load source of truth
        if (v != null) {
            l2.setEx(key, v, Duration.ofMinutes(10));  // fill L2 (longer TTL)
            l1.put(key, v);                            // fill L1 (short TTL)
        }
        return v;
    }

    public void write(String key, String value) {
        db.save(key, value);                           // 1) source of truth
        l2.del(key);                                   // 2) invalidate shared tier
        l2.publish(INVALIDATION_CHANNEL, key);         // 3) tell ALL nodes to drop L1
        l1.invalidate(key);                            // 4) drop our own L1 immediately
    }
}
```

**Time:** O(1) plus tier round trips; L1 hit avoids the network entirely. **Space:** O(L1 size) per node + O(working set) in L2.

**Edge cases:** the publishing node should also evict its own L1 directly (step 4) because it may not receive its own broadcast in time. Pub/sub is best-effort and at-least-once-ish, so the L1 TTL is mandatory as a backstop — a node that was disconnected during the broadcast, or one that just started, must still converge. Note the asymmetric TTLs (L1 short, L2 longer): L1 trades a little staleness for the network-free read, L2 is the larger shared truth. For stronger guarantees, version the keys so a missed invalidation can't serve stale data at all.

### Q24. [Theory] Explain how CDN cache invalidation works at scale, and the trade-offs of purge vs versioning vs surrogate keys.

A CDN caches objects keyed by URL (plus a `Vary` on certain headers) across hundreds of edge PoPs worldwide. The invalidation problem is that changing content under the *same* URL must somehow reach every PoP. Three strategies, used together in mature setups:

1. **Purge / invalidate:** explicitly tell the CDN to drop an object. The challenge is propagation — a purge must reach every edge. Modern CDNs (Fastly) implement **instant soft purge** in ~150 ms by versioning content in a distributed store and marking the old version stale; older designs (early CloudFront) propagate over minutes. Purge is the only option for content at a fixed URL (HTML pages, API responses), but it's eventually consistent across PoPs, often rate-limited, and can be costly at high volume.

2. **Versioned / fingerprinted URLs:** embed a content hash in the URL (`app.9f3c2b.js`) and serve with `Cache-Control: max-age=31536000, immutable`. Content change → URL change → *nothing to invalidate*. Old and new coexist; clients fetch the new URL because the short-TTL HTML shell references it. This is the gold standard for static assets — perfectly cacheable, atomic deploys, no purge machinery.

3. **Surrogate keys / cache tags:** tag each cached response with one or more keys (e.g., a product page is tagged `product:42` and `category:shoes`). A single purge-by-tag call invalidates *all* objects bearing that tag — purge every page showing product 42 in one operation. This solves the "I changed one entity that appears on thousands of URLs" problem that per-URL purge can't.

```
Strategy        Invalidation cost     Best for                     Consistency across PoPs
Purge-by-URL    1 call per URL        single dynamic page/API      eventual (ms to minutes)
Versioned URL   zero (URL changes)    static/immutable assets      N/A — old & new coexist
Surrogate key   1 call per tag/group  entity on many pages         eventual (depends on CDN)
```

The robust production architecture combines all three: **immutable fingerprinted assets** (no invalidation ever), a **short-TTL HTML/API shell** that references them, and **surrogate-key purging** for the dynamic content that must change at a stable URL. The interview insight is that *the best invalidation is no invalidation* — design URLs so content change implies URL change wherever possible, and reserve purging for the genuinely-mutable-at-a-stable-URL minority.

### Q25. [Coding] Implement probabilistic early expiration (XFetch) to prevent stampedes.

**Problem:** Prevent a hot key from causing a stampede at hard expiry by having one request *probabilistically* refresh the value slightly *before* it expires, scaled by how expensive the recomputation is.

The XFetch algorithm (Vattani, Chierichetti, Lowenstein) stores, alongside the value, the time `delta` the last recomputation took. On each read it recomputes early if `now − delta · beta · ln(rand()) ≥ expiryTime`. As the entry nears expiry, the probability of an early refresh rises; expensive recomputations (large `delta`) refresh earlier.

```java
import java.util.concurrent.ThreadLocalRandom;

public class XFetchCache {
    private final RedisClient cache;     // stores value + delta + expiry (e.g., a hash)
    private final Database db;
    private final double beta;           // >1 favors earlier refresh; 1.0 is the default

    public XFetchCache(RedisClient cache, Database db, double beta) {
        this.cache = cache; this.db = db; this.beta = beta;
    }

    public String read(String key, long ttlMillis) {
        CacheEntry e = cache.getEntry(key);   // {value, delta, expiryEpochMs}
        long now = System.currentTimeMillis();

        if (e != null && !shouldRecompute(e, now)) {
            return e.value;                    // serve cached; not yet time to refresh
        }
        // Either a miss, or this request "won" the probabilistic early refresh.
        long start = System.currentTimeMillis();
        String value = db.load(key);
        long delta = System.currentTimeMillis() - start;   // measured recompute cost
        cache.setEntry(key, value, delta, now + ttlMillis, ttlMillis);
        return value;
    }

    // XFetch: recompute when now - delta*beta*ln(rand) >= expiry  (rand in (0,1])
    private boolean shouldRecompute(CacheEntry e, long now) {
        double rand = ThreadLocalRandom.current().nextDouble(); // (0,1)
        double xfetch = e.delta * beta * -Math.log(rand);
        return now + xfetch >= e.expiryEpochMs;
    }
}
```

**Time:** O(1) per read. **Space:** O(1) extra per key (store `delta` and `expiry`).

**Edge cases:** only *one* request typically crosses the threshold, so the herd never all-misses at once — but to be safe, combine XFetch with a single-flight lock so even the rare double-trigger doesn't double-load. Tuning `beta`: >1 refreshes earlier (more proactive, slightly more recomputes); <1 refreshes later. The measured `delta` is what makes this adaptive — keys that are cheap to recompute refresh lazily, expensive ones refresh well ahead of expiry. This is strictly better than fixed jitter for high-value hot keys because the value is never simultaneously absent and in demand.

### Q26. [Theory] What consistency guarantees can a cache realistically provide, and how do you reason about staleness as an SLO?

A cache fundamentally introduces a *second copy* of data, so absent special machinery it provides **eventual consistency** — reads may return a value that is stale by up to the invalidation latency (TTL, or the time for an invalidation event to propagate). It's important to be honest that a cache-aside design does **not** give linearizability; even "delete on write" has race windows. So the right framing is not "is the cache consistent?" but "**what is the bound on staleness, and is that bound acceptable for this data?**"

Treating staleness as an **SLO** makes this concrete and measurable:

```
Staleness SLO = max acceptable age of served data.
  Bounded by:  min(TTL, invalidation_propagation_time)
  Measure it:  tag cached values with a write timestamp/version; on read, compute age = now - value.writtenAt
               emit a "staleness" histogram → alert if p99 staleness exceeds the SLO
```

Different layers give different bounds: a versioned-key scheme gives *zero* staleness for found keys (a stale entry is structurally unreachable); active delete-on-write gives near-zero with a TTL backstop; a pure-TTL near-cache gives up-to-TTL staleness; a CDN gives up-to-TTL-or-purge-propagation. You can also offer **stronger guarantees selectively**: **read-your-writes** by having a user's own writes update their view directly (or bypass the cache for a few seconds after their write), and **monotonic reads** by pinning a session to a tier that won't go backward.

The expert move is to *quantify and monitor* staleness rather than hand-wave "it's eventually consistent." I instrument the cache to emit the age of served values, set an explicit staleness SLO per data class (sub-second for inventory, minutes for catalog), and choose the invalidation mechanism that meets that SLO at the lowest cost. When a piece of data demands true linearizability (authorization, balances), I don't cache it for the decision path — I treat that as a signal that caching is the wrong tool there.

### Q27. [Practical] Design the full caching strategy for a high-traffic product page (50K req/s, data changes a few times/hour).

**Workload:** reads massively dominate writes (50K req/s reads, a handful of writes/hour per product), and the page aggregates product details, price, inventory, and reviews — each with different freshness needs. That heterogeneity drives the design: I'd cache each fragment with its own TTL and consistency policy rather than one monolithic cached page.

**Topology — multi-tier:**

```
Browser/CDN: page shell + images (fingerprinted, immutable) and a cached HTML/JSON fragment
   │  (s-maxage ~60s for the product fragment, surrogate-key "product:42" for purge)
Edge/CDN absorbs the bulk of reads for popular products
   │
App L1 (Caffeine near-cache): hottest SKUs, TTL ~2–5s, pub/sub invalidation   ← kills hot-key funnel
   │
L2 Redis (cache-aside): all product objects, TTL ~5 min, jittered, single-flight on miss
   │
Database (source of truth)
```

**Failure-mode defenses (the part that earns the senior grade):**

- **Thundering herd:** single-flight repopulation (Caffeine `LoadingCache` per node + a Redis lock for cross-node coalescing) and **jittered TTLs** so hot keys don't expire in lockstep. For the very hottest SKUs, XFetch probabilistic early refresh.
- **Hot keys (flash sale):** the Caffeine L1 fans the hot SKU out across all app nodes so no single Redis node is funneled; if writes are rare enough, also key-split in Redis. Promote the product fragment to the CDN so the edge absorbs it entirely.
- **Cache penetration** (bots probing fake SKUs): negative-cache "not found" briefly + a Bloom filter of valid SKU IDs.
- **Stale data on write:** on a price/inventory change, write the DB, delete the Redis key, publish a pub/sub invalidation to drop L1 across all nodes, and purge the CDN by surrogate key `product:42`. TTLs are the safety net.
- **Inventory correctness:** the *displayed* count is cached (staleness-tolerant), but the *purchase decision* reads the authoritative inventory transactionally — never cache the value used for the oversell-preventing check.

**What I'd ship:** CDN for static + fragment caching, Redis cache-aside with jittered TTL + single-flight, a Caffeine near-cache with pub/sub invalidation for hot SKUs, negative caching + Bloom filter for penetration, and surrogate-key CDN purging on write. This holds 50K req/s off a modest database while bounding staleness to a few seconds and keeping the oversell-critical path uncached.

### Q28. [Theory] When should you choose Redis vs Memcached, and what does Redis offer beyond a simple key-value cache?

Both are in-memory key-value caches with sub-millisecond latency; the choice comes down to data model, persistence, and topology needs.

**Memcached** is deliberately minimal: a multithreaded, string/blob key-value store with LRU eviction, slab allocation, and trivial horizontal scaling via client-side consistent hashing. Its strengths are simplicity, predictable performance, and excellent multi-core throughput for a pure "cache strings by key" workload. It has no persistence, no replication, and no rich data types — if a node dies, its data is gone and the client just routes around it.

**Redis** is far richer: it's (historically) single-threaded for the data path (Redis 6+ adds multithreaded I/O), with **rich data structures** (strings, hashes, lists, sets, sorted sets, streams, bitmaps, HyperLogLog, geospatial), **persistence** (RDB snapshots + AOF log), **replication and failover** (Redis Sentinel, Redis Cluster), **pub/sub** (for cache invalidation broadcasts), **atomic Lua scripting** (for distributed rate limiters and locks), and **TTL/eviction policies** per key.

```
Need                                          Choose
Pure string/blob cache, max simple throughput  Memcached
Rich types (sorted sets for leaderboards,
  hashes for objects, streams)                 Redis
Persistence / survive restart                  Redis
Replication, HA, automatic failover            Redis
Pub/sub cache invalidation                     Redis
Atomic multi-step ops (rate limiter, lock)     Redis (Lua / transactions)
```

In practice **Redis is the default** for almost all new systems because its richer feature set (especially pub/sub for invalidation, Lua for atomic ops, and persistence/replication for HA) is worth far more than Memcached's marginal multi-core edge — and Redis is plenty fast. I'd reach for Memcached specifically when I want a dead-simple, large, ephemeral string cache and value its multithreaded simplicity, or when an existing ecosystem standardizes on it. The honest senior take: the feature gap has made Redis (and Redis-compatible stores like Valkey/KeyDB/DragonflyDB) the conventional choice, and "Memcached vs Redis" today is mostly "do I need anything beyond a string cache?"

### Q29. [Practical] How do you warm a cold cache after a deploy or failover without melting the database?

A **cold cache** — empty after a restart, deploy, scale-up, or failover — is dangerous because the *first* wave of traffic all misses and slams the database with the full, un-absorbed load it hasn't seen since the cache went cold. This is how a routine deploy turns into an outage. The defenses:

**Avoid going cold in the first place.** Use a cache with **persistence** (Redis RDB/AOF) so a restart reloads the working set instead of starting empty. For Redis Cluster, ensure failover promotes a *replica* (which already has the data) rather than starting fresh. Rolling deploys of *app* nodes don't cold the shared Redis at all — which is a strong argument for a shared L2 tier over purely in-process caches.

**Warm proactively.** Pre-load the known-hot keys before sending production traffic: a warm-up job that replays the top-N keys (from access logs / analytics) into the cache, or a "shadow traffic" phase where a new node serves mirrored read traffic to populate its near-cache before it's added to the load balancer's rotation.

**Protect the DB during the warm-up window.** This is the critical piece even with warming:

```
Cold start protections:
  • Single-flight / request coalescing   → only one load per key, not 50k
  • Concurrency limit toward the DB      → a semaphore caps in-flight DB loads (shed/queue the rest)
  • Gradual traffic ramp (slow-start LB)  → admit 5% → 25% → 100% so misses trickle in
  • Negative caching + Bloom filter       → bogus keys don't reach the DB
```

In practice I combine a persistent/replica-backed Redis (so failover isn't cold), a warm-up job for the hottest keys, a load-balancer slow-start so a freshly added node ramps gradually, and a hard concurrency cap on DB loads so even a worst-case cold start degrades latency rather than collapsing the database. The mantra: **never let the cache fail open into an unprotected database.**

### Q30. [Theory] Explain negative caching and Bloom filters as defenses, including their failure modes.

Both defend the database from lookups for **non-existent keys** (cache penetration), but they trade off differently.

**Negative caching** stores the *result* of a "not found" lookup — a tombstone/null marker — with a short TTL. The first request for a missing key pays the DB miss, then subsequent requests are absorbed by the cached tombstone. Its failure modes: (1) if the key is later *created*, requests still return "not found" until the tombstone's TTL expires — so the TTL must be short, or the create path must explicitly delete the tombstone; (2) an attacker enumerating a vast space of distinct missing keys fills the cache with tombstones, evicting real hot data — bound this with eviction and per-client rate limits.

**Bloom filters** are a probabilistic set: a bit array with k hash functions. Adding a key sets k bits; checking a key tests those k bits. Crucially, a Bloom filter has **no false negatives** (if it says "not present," the key definitely doesn't exist → reject immediately, never touch the DB) but allows **false positives** (occasionally says "maybe present" for a key that isn't → falls through to the DB, which correctly returns not-found). ~10 bits/key gives ~1% false positives.

```
Bloom check before DB:
  filter says "definitely NOT present"  → reject (0 DB load) ✅ the win
  filter says "possibly present"        → check DB (real hit, or 1% false-positive miss)

Failure modes:
  • Cannot delete from a plain Bloom filter (use a Counting or Cuckoo filter to support deletes)
  • Fills up over time → false-positive rate climbs → must size for expected cardinality, or rebuild
  • Stale filter after new keys added → false NEGATIVE risk unless the filter is updated on insert
```

The subtle danger with Bloom filters: if a key is *added* to the DB but not to the filter, the filter wrongly says "not present" and you reject a legitimate request (a false negative *introduced by staleness*, even though the structure itself has none). So the filter must be updated on every insert, or rebuilt periodically with a safety margin. In practice I combine them: a Bloom filter rejects the bulk of bogus traffic cheaply, and negative caching mops up the false-positive trickle. Both are about converting "every bad request hits the DB" into "almost no bad request hits the DB."

### Q31. [Practical] You're seeing periodic latency spikes every few minutes that correlate with cache misses. Diagnose it.

Periodic, clustered latency spikes that line up with miss bursts almost always point to **synchronized cache expiry** — a population of keys was written at roughly the same time with the same TTL (e.g., a batch job warms 100K keys at midnight, or all keys got a flat `setEx(key, v, 300)`), so they all expire *together*, dumping a synchronized miss storm onto the database every TTL interval.

My diagnostic path: correlate the spike period with the configured TTL (a 5-minute TTL → spikes every ~5 minutes is the smoking gun). Confirm with cache metrics — `evicted_keys`/`expired_keys` will show a sawtooth, and DB QPS will show synchronized spikes aligned to the period. Check whether the keys share a common write event (a batch warm, a deploy, a scheduled refresh).

```
Symptom: DB QPS    ▁▁▁▁█▁▁▁▁█▁▁▁▁█    every ~TTL seconds
         miss rate ▁▁▁▁█▁▁▁▁█▁▁▁▁█
Cause:   all keys written together → expire together → synchronized stampede
```

**Fixes:** add **jitter to TTLs** (`base ± 10%`) so expirations spread out — the single most important fix. Use **probabilistic early expiration (XFetch)** or **stale-while-revalidate** so hot keys refresh ahead of expiry, one at a time, rather than all going cold simultaneously. Add **single-flight** so even within a synchronized burst only one load per key reaches the DB. If a batch job is the cause, stagger the writes or set per-key randomized TTLs at write time. Other possibilities I'd rule out: a periodic **eviction storm** because the cache is undersized (the working set doesn't fit, so a churn cycle evicts hot keys every few minutes — check `evicted_keys` and `used_memory`), or a periodic **background job** (RDB snapshot fork, AOF rewrite, or a cron) that briefly stalls Redis. But the classic answer to "spikes every TTL interval" is synchronized expiry, and the fix is jitter.

---

## 🔴 Expert (15+ yrs)

### Q32. [Theory] How does caching interact with read replicas and replication lag, and how do you avoid a cache that amplifies staleness?

This is a subtle, high-impact interaction. If your cache-aside *populates from a read replica* rather than the primary, you've **composed two sources of staleness**: the replica is behind the primary by the replication lag, and then the cache freezes that already-stale value for its entire TTL. A user can write to the primary, the replica hasn't caught up, a cache miss loads the *old* value from the lagging replica, and now that stale value is pinned in the cache for, say, 5 minutes — long after the replica caught up. The cache turned a transient sub-second replica lag into minutes of staleness, and broke read-your-writes.

```
write → PRIMARY (new value)
                ⇣ replication lag ~200ms
cache miss → reads REPLICA (still OLD) → caches OLD for TTL=5min  💥
result: stale for 5 minutes, not 200ms — the cache AMPLIFIED the lag
```

Defenses, depending on the guarantee needed:

- **Populate the cache from the primary** for keys that demand freshness, accepting more primary read load (often fine because cache fills are rare relative to hits). This avoids composing the two staleness sources.
- **Read-your-writes routing:** after a user's write, route their reads to the primary (or a replica known to have caught up via a write LSN/token) and *also* update their cache entry directly from the just-written value, so they never see their own write disappear.
- **Causal/version tokens:** carry the write's replication position; on read, only accept a replica/cache value at or beyond that position, otherwise fall back to the primary.
- **Bound TTL by expected lag tolerance:** if you must fill from replicas, keep the TTL short for lag-sensitive data so the amplification window is small.

The expert framing: a cache and a replica are *both* staleness sources, and naively chaining them multiplies staleness in a way that surprises teams. I make the cache-fill path's freshness explicit — fill from a source whose staleness is ≤ the data's SLO — and I handle read-your-writes at the application layer rather than hoping the cache happens to be fresh.

### Q33. [Practical] Design a globally distributed, multi-region cache. How do you handle invalidation and consistency across regions?

The fundamental tension: a global cache wants data near users (low read latency in every region) but invalidation and writes now cross regions where a round trip is ~100–150 ms. You cannot have synchronous strong consistency *and* low latency across regions (PACELC's EL-vs-EC dial at planetary scale), so the design must make the trade-off explicit per data class.

**Topology:** a **per-region cache cluster** (Redis) co-located with per-region app tiers, each fronting a regional read path. Writes go to a **primary region** (or a per-key home region for geo-partitioned data) which owns the source of truth; reads are served locally.

```
        Region US                Region EU                Region APAC
   [app + Redis-US]         [app + Redis-EU]         [app + Redis-APAC]
         │  local reads (low latency)                       │
         └──────── write/invalidation propagation ──────────┘
                   (async fan-out, ~100–150ms cross-region)
   Source of truth: globally-replicated DB (e.g., Spanner/Aurora Global/Cosmos)
```

**Invalidation across regions** — the hard part. Options, by consistency strength:

1. **TTL-only (simplest):** each region caches with a TTL and accepts up-to-TTL cross-region staleness. No cross-region invalidation traffic. Fine for catalog/profile data with a minutes-scale SLO.
2. **Async invalidation fan-out:** a write publishes an invalidation event to a global stream (Kafka MirrorMaker, SNS cross-region, or the DB's change stream) that every region consumes and applies to its local cache. Near-real-time but **best-effort and eventually consistent** — a region may briefly serve stale data, and out-of-order delivery must be handled (use versions/timestamps so an older invalidation can't undo a newer write).
3. **Versioned keys + global truth:** the cache key carries a version derived from the globally-replicated DB; a stale regional entry is simply never read because the new version key differs. This sidesteps cross-region invalidation entirely for found keys.

**Consistency posture:** I'd make the periphery **eventually consistent** (reads local, async invalidation, version tags to resolve ordering) and keep any **linearizable kernel** (e.g., a balance or inventory decision) pinned to a single home region's authoritative store — never served from a remote cache for the decision path. For write-heavy globally-shared mutable data I'd consider **CRDTs** (counters, sets) so regions can update independently and merge without coordination. I'd also **geo-partition by entity home region** so most writes are local and only cross-region *reads* pay the propagation cost.

The expert judgment: don't try to make the whole global cache strongly consistent — that imposes 150 ms on every write and an availability cliff during a partition. Instead, classify data by staleness SLO, use async versioned invalidation for the bulk, pin the small consistency-critical kernel to a home region, and reach for CRDTs where independent regional writes must merge.

### Q34. [Behavioral] Tell me about a time you had to push back on a team that wanted to cache something they shouldn't have (or remove a cache that was masking a real problem).

A strong answer follows **STAR** and shows that caching is a judgment call with consequences, not a reflexive optimization — and that senior engineers sometimes argue *against* a cache.

*Situation/Task:* A team was hitting latency targets only because they'd put a 30-second cache in front of an authorization check — "is this user still entitled to this resource?" It made the endpoint fast, but it meant a revoked permission stayed effective for up to 30 seconds. A security review flagged it, and the team's instinct was to keep the cache and "just lower the TTL," because removing it would blow their latency SLO.

*Action:* Rather than veto it outright, I reframed the problem. I separated the two things the cache was conflating: *fetching the permission data* (safe to cache — the user's role assignments change rarely) versus *making the authorization decision* (must be fresh — a revocation must take effect immediately). I proposed caching the underlying role/policy data with a normal TTL but evaluating the decision live against it, and crucially adding an **active invalidation** on the revocation path (publish an event that purges the affected user's cached policy the instant a permission is revoked). To address the latency fear with data, I prototyped it and showed the decision evaluation was microseconds — the latency came from the data fetch, which we *kept* cached. I also pushed for a **staleness SLO** on security-sensitive cached data measured in single-digit seconds with monitoring, so we'd know if invalidation ever lagged.

*Result:* We kept the latency win (the expensive fetch stayed cached) while closing the security gap (revocations took effect in <1 s via event-driven invalidation, not 30 s). The team adopted a durable principle: **never cache the decision on a security-critical path; cache the inputs and invalidate them actively.**

The meta-points an interviewer is listening for: I didn't just say "no" — I diagnosed *what* was safe to cache vs. not, I brought data to counter the latency objection, I gave the team a path that preserved their win, and I left behind a reusable principle plus monitoring. Pushing back effectively means offering a better design, not just an objection.

### Q35. [Practical] At scale, a cache becomes a critical dependency. How do you make the cache itself highly available and prevent it from being a single point of failure?

The irony of a successful cache is that the system grows *dependent* on it — the database is now sized assuming the cache absorbs 95%+ of reads, so a cache outage means the DB instantly faces traffic it cannot survive. The cache has become a **critical, load-bearing dependency**, and it needs the same HA rigor as the database. Several layers of defense:

**Make the cache tier itself resilient.** Run Redis in **Cluster mode** (sharded, so one node's loss only affects its slots) with **replicas per shard** and automatic failover (Sentinel/Cluster). Enable **persistence** (AOF) so a restart isn't cold. Spread shards/replicas across availability zones so an AZ loss doesn't take the whole cache. This turns "the cache is down" into "a fraction of keys briefly failover."

**Decide fail-open vs fail-closed deliberately.** On a cache error, does the app fall through to the DB (**fail-open** — preserves correctness but risks overloading the DB) or reject/serve-degraded (**fail-closed**)? Fail-open is usually right for correctness but is exactly what melts the database during a full cache outage — so it must be paired with DB protection.

**Protect the backing store for when the cache *does* fail:**

```
  • Concurrency limiter / bulkhead toward the DB  → cap in-flight loads; shed the excess (429/503)
  • Circuit breaker on the cache client            → if cache is down, fail fast, don't pile up threads
  • Load shedding by request priority              → serve checkout, drop recommendations
  • A small in-process L1                          → even if Redis is down, hot keys still served locally
  • Request coalescing                             → cache-down still means one DB load per key, not N
```

**Avoid correlated failure of the cache and its protections.** A classic outage: the cache fails, every node fails-open to the DB simultaneously (a correlated stampede), the DB falls over, and now *nothing* works. The fix is that the DB-protection layer (concurrency cap + shedding) must hold even when the cache is *fully* down — verified with **chaos testing** (kill the cache in a game day and confirm the system degrades to "slow" rather than "down").

The expert framing: once a cache carries 95% of your reads, treat it as a tier-0 dependency — replicate it across AZs, give it failover and persistence, and *independently* protect the database so that a total cache loss degrades latency gracefully instead of cascading into a full outage. Provision the database (or a load-shedding fallback) for some realistic fraction of cache-miss traffic, not for the steady-state miss rate alone.

### Q36. [Theory] Caching is famously one of the "two hard things in computer science." From a staff-engineer's vantage, what is the deepest reason cache invalidation is hard, and how does that shape your architectural philosophy?

The surface answer is "you have to remember to invalidate." The deeper answer is that **a cache creates a second, independent copy of state, and the moment you have two copies of mutable data you have a distributed-consistency problem** — with all the attendant impossibilities. Invalidation is hard because it's not really about caching; it's the CAP/PACELC trade-off wearing a different hat. Keeping the cached copy in agreement with the source of truth, under concurrency, partial failure, message loss, reordering, and clock skew, is the *same* fundamental problem as replicating a database — and we know from first principles you cannot have strong consistency, low latency, and availability simultaneously.

This reframing is liberating because it tells you *where* the hard cases live: anywhere the copies can diverge and the divergence is observable and harmful. Concretely, invalidation is hard precisely where (1) writes and reads are concurrent (the read-after-write race), (2) the cache spans nodes/regions with no coordination (near-cache, multi-region), (3) invalidation is best-effort (pub/sub, CDN purge propagation), and (4) the *consequences* of staleness are correctness bugs, not cosmetic. It's *easy* where staleness is bounded and harmless.

So my architectural philosophy is built on a few principles:

1. **Make invalidation unnecessary wherever possible.** Immutable, content-addressed data (fingerprinted URLs, versioned keys, append-only event logs) has *nothing to invalidate* — the URL/key changes when the content changes. This is the single most powerful technique: design state so that "change" means "new identity," and the stale copy becomes structurally unreachable rather than something you must hunt down and delete.

2. **Bound staleness explicitly and measure it.** Where you must cache mutable data, set a staleness SLO, choose the cheapest mechanism that meets it (TTL → active delete → versioned key), and instrument the actual staleness so it's a monitored property, not a hope.

3. **Shrink the consistency-critical surface.** Identify the few invariants that truly demand freshness (money, inventory at point-of-sale, authorization decisions) and *don't cache the decision* — cache the inputs, decide live, invalidate actively. Let the vast eventually-consistent majority be cheap and fast.

4. **Treat the cache as a distributed system, not a hash map.** Apply the same discipline you'd apply to replication: define ordering (versions to resolve out-of-order invalidations), handle failure (fail-open with DB protection), and test it under chaos.

The staff-level insight is that "cache invalidation is hard" is not a quirky aphorism — it's a restatement of the consistency-vs-availability-vs-latency impossibility. Once you see caching as replication, you stop trying to make caches magically consistent and instead *design state to minimize what must be invalidated, bound and measure the staleness of what remains, and reserve true consistency for the smallest possible kernel.*

---

## ✅ Key Takeaways

- **Reason about misses in absolute QPS, not hit-ratio percentage.** A drop from 99% to 90% hit ratio 10×'s the load on your backing store; the backing tier is sized for the miss rate, so a cache failure (hit ratio → 0) is catastrophic.
- **Eviction (capacity) and invalidation (correctness) are different problems.** Handle both: an eviction policy (usually LRU, or W-TinyLFU in Caffeine) bounds memory; TTL + active invalidation bounds staleness.
- **Cache-aside is the default** (resilient, lazy, DB stays source of truth). Write-through trades write latency for consistency; write-behind trades durability for write throughput. On writes, **delete the cache key, don't update it**, to avoid the read-after-write race.
- **The scale-killers are skew and synchronization:** hot keys funnel to one shard (fix with near-caches and key-splitting), and synchronized TTLs cause expiry stampedes (fix with jitter, single-flight, and XFetch/stale-while-revalidate).
- **Defend the database actively:** single-flight against thundering herd, negative caching + Bloom filters against penetration, and a concurrency cap + circuit breaker so a cold or failed cache degrades latency instead of cascading into an outage.
- **CDN: prefer no invalidation at all.** Fingerprinted, immutable URLs sidestep invalidation; reserve purge (and surrogate keys for grouping) for genuinely mutable-at-a-stable-URL content. Use `s-maxage`/`ETag`/`stale-while-revalidate` deliberately.
- **A cache and a replica are both staleness sources** — chaining them (filling a cache from a lagging replica) *amplifies* staleness. Fill freshness-sensitive keys from the primary and handle read-your-writes at the app layer.
- **Caching is replication.** Invalidation is hard because two copies of mutable state is a distributed-consistency problem. Minimize what must be invalidated (immutable/versioned data), bound and *measure* staleness as an SLO, and never cache the decision on a consistency-critical path.

## ⚠️ Common Pitfalls

- **Caching per-request-unique data** (keys with timestamps, request IDs, free-text queries) — near-zero reuse, pollutes the cache, and lowers the hit ratio for genuinely hot keys.
- **Updating the cache on write instead of deleting it**, re-opening the read-after-write race so a concurrent slow reader can pin a stale value until TTL.
- **Flat, un-jittered TTLs** on a batch of keys written together → synchronized expiry → periodic stampede every TTL interval.
- **Ignoring hot keys** — assuming consistent hashing spreads load, when a single hot key still funnels all its traffic to one shard you can't scale past.
- **No thundering-herd protection** — a hot key expires and thousands of concurrent misses hammer the DB; missing single-flight and jitter.
- **Fail-open into an unprotected database** — on cache outage the DB sees the full un-absorbed traffic and collapses; missing a concurrency cap / circuit breaker / load shedding.
- **Filling the cache from a read replica**, composing replica lag with cache TTL and amplifying transient lag into minutes of staleness (and breaking read-your-writes).
- **Caching the authorization decision** (not just its inputs), so a revoked permission stays effective for the full TTL — a security bug.
- **Treating CDN purge as instant and global** — propagation is eventual across PoPs, browser caches still hold their own copies; not fingerprinting mutable assets.
- **Forgetting the cache is now a tier-0 dependency** — not replicating it across AZs, no persistence, no failover, so a single cache node loss cascades into a database outage.
- **Not measuring staleness** — treating "eventually consistent" as a vague hope rather than an instrumented, monitored SLO with a known bound.

## 📚 Further Reading

- **Martin Kleppmann, *Designing Data-Intensive Applications* (2nd ed., 2024/2026 update)** — replication, consistency, and why caching is fundamentally a distributed-state problem.
- **Alex Xu, *System Design Interview* Vol. 1 & 2** — caching tiers, CDN design, and component walkthroughs with estimation.
- **Caffeine wiki (github.com/ben-manes/caffeine)** — W-TinyLFU eviction, `LoadingCache` single-flight, and near-cache patterns explained by the author.
- **Vattani, Chierichetti & Lowenstein, "Optimal Probabilistic Cache Stampede Prevention" (VLDB 2015)** — the XFetch algorithm for probabilistic early expiration.
- **Redis documentation — Eviction Policies, Keyspace Notifications, Pub/Sub, and Cluster** (redis.io/docs) — for eviction tuning, invalidation broadcasts, and HA topology.
- **MDN & RFC 9111 (HTTP Caching)** — `Cache-Control`, `ETag`, conditional requests, and `stale-while-revalidate` semantics.
- **Fastly & Cloudflare engineering blogs** — instant purge, surrogate keys / cache tags, and edge invalidation at scale.
- **AWS Builders' Library (aws.amazon.com/builders-library)** — "Caching challenges and strategies," plus essays on load shedding, timeouts, and avoiding cascading failures.
- **Facebook/Meta, "Scaling Memcache at Facebook" (NSDI 2013)** — the canonical paper on caching at massive scale: leases (single-flight), the thundering herd, and regional invalidation.
