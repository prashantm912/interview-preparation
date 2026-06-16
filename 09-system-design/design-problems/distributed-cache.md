# Design a Distributed Caching System

A worked system-design problem covering how to build a horizontally-scalable, low-latency, in-memory caching tier (think a self-hosted Redis/Memcached-style service) that fronts databases and downstream services for read-heavy workloads.

[← Back to master index](../../README.md) · [← System Design index](../README.md)

---

## 1. Requirements

A distributed cache is an in-memory key-value store, sharded across many machines, that sits between application servers and a slower system of record (SQL/NoSQL DB, object store, or another microservice). The whole point is to absorb read traffic and cut tail latency.

### Functional requirements

- `GET(key)` → value (or miss), `SET(key, value, ttl)`, `DELETE(key)`.
- Optional atomic primitives: `INCR`, `CAS` (compare-and-swap), `EXPIRE`, `MGET`/`MSET` for batching.
- Per-key **TTL** support and lazy + active expiration.
- **Eviction** when memory is full (LRU / LFU / TTL-based).
- Horizontal scaling: add/remove nodes without a full flush (graceful rebalancing).
- Replication for read scaling and fault tolerance.
- Optional pub/sub for invalidation fan-out.

### Non-functional requirements

| Attribute | Target |
|---|---|
| Latency | p50 < 0.5 ms, p99 < 2 ms for `GET` within a DC |
| Throughput | 1M+ ops/sec per cluster, scalable linearly |
| Availability | 99.99% (cache is a performance layer, not source of truth) |
| Consistency | Eventual / best-effort; cache may be stale or miss — never the authority |
| Durability | Generally **not** required; data is reconstructable from the DB |
| Scale | 10s of TB of hot data across the fleet |

### Clarifying questions a candidate should ask

1. **Read-heavy or write-heavy?** Caches shine at >10:1 read:write. If writes dominate, a cache may add invalidation cost without payoff.
2. **Consistency tolerance?** Can the app tolerate stale reads for a few seconds (TTL-bounded), or does it need strong read-your-writes? This drives the write policy.
3. **Single DC or geo-distributed?** Cross-region replication changes the CAP calculus and invalidation strategy dramatically.
4. **What is the value size distribution?** 100-byte session tokens vs. 1 MB rendered pages change memory math, network, and eviction behavior.
5. **Is durability ever needed?** (e.g. cache also used as a session store) — affects whether we add AOF/RDB persistence.
6. **Embedded near-cache allowed?** Can clients hold a small local L1 cache, or must everything be remote?
7. **Hot-key risk?** Are there celebrity keys (viral tweet, flash-sale SKU) that could melt a single shard?

The framing answer for the rest of this doc: **read-heavy (50:1), single primary DC with one DR region, eventual consistency bounded by TTL, no hard durability requirement, value sizes 100 B – 10 KB.**

---

## 2. Capacity Estimation

Let's size a realistic large-scale cache.

**Assumptions**

- 500M daily active users, each generating ~40 cache reads + ~1 cache write per active session.
- Reads/day = 500M × 40 = **20B reads/day**. Writes/day = 500M × 1 = **0.5B writes/day**.
- Seconds/day ≈ 86,400.

**QPS**

```
Avg read QPS  = 20,000,000,000 / 86,400  ≈ 231,000 reads/sec
Avg write QPS = 500,000,000   / 86,400  ≈   5,800 writes/sec
Peak factor   = 3x  →  ~700K read QPS, ~17K write QPS at peak
```

**Memory (the dominant constraint)**

- Distinct hot keys = 2B objects.
- Avg entry = key (50 B) + value (1 KB) + overhead (object header, expiry, LRU pointers ≈ 100 B) ≈ **1,150 B**.

```
Raw working set = 2,000,000,000 × 1,150 B ≈ 2.3 TB
With replication factor 2 (1 primary + 1 replica) = 4.6 TB
Plus 25% headroom (fragmentation + eviction slack) ≈ 5.75 TB
```

**Number of nodes**

- Use nodes with 64 GB usable RAM for cache data (leave RAM for OS/overhead on a 96 GB box).

```
Nodes = 5,750 GB / 64 GB ≈ 90 nodes
Round up to ~96 nodes (organize as 48 shards × 2 replicas).
```

**Per-node load**

```
Read QPS/node  = 700,000 / 48 primaries ≈ 14,600 reads/sec
```
A single Redis/Memcached node comfortably handles 50K–100K+ simple ops/sec, so we have ~5x compute headroom — **memory, not CPU, is the binding constraint** here.

**Bandwidth**

```
Read egress at peak = 700,000 ops/sec × 1 KB ≈ 700 MB/sec ≈ 5.6 Gbps
```
Spread over 48 primaries that's ~12 MB/s per node — trivial. But a single **hot key** at 700K QPS × 1 KB = 5.6 Gbps on one node would saturate a 10 GbE NIC. Hot keys are a real bottleneck (see Deep Dive 2).

**Takeaway:** ~96 nodes, ~5.75 TB RAM, memory-bound, with hot keys as the sharp edge.

---

## 3. API Design

A thin client library talks to the cluster. Logical contract:

```
# Core
GET    key                       -> {found: bool, value: bytes, ttl_remaining: int}
SET    key value [ttl=seconds] [flags] -> OK | ERR
DELETE key                       -> {deleted: bool}
EXPIRE key seconds               -> OK

# Batch (amortize RTT)
MGET   [k1, k2, ...]             -> {k1: v1, k2: MISS, ...}
MSET   {k1: v1, k2: v2}          -> OK

# Atomic
INCR   key [by=1]                -> new_value
CAS    key value expected_cas    -> OK | CAS_MISMATCH   # optimistic concurrency
ADD    key value                 -> OK | EXISTS         # set-if-absent (lock primitive)

# Invalidation fan-out (pub/sub)
PUBLISH  channel message
SUBSCRIBE channel
```

Client-side smart-routing pseudocode:

```
def get(key):
    slot   = crc16(key) % 16384          # Redis-Cluster-style hash slot
    node   = slot_to_primary[slot]       # cached cluster topology map
    try:
        return node.get(key)
    except MOVED(new_node):              # topology changed mid-rebalance
        refresh_topology(); return new_node.get(key)
    except Timeout:
        return read_from_replica(slot)   # fall back to a replica
```

Design notes: **batching** (`MGET`) is the single biggest client-side win for tail latency; the client owns the **hash-slot → node map** so routing is one network hop (no proxy in the hot path); `CAS`/`ADD` give callers building blocks for distributed locks and stampede control.

---

## 4. Data Model

The cache stores opaque key → value blobs; the schema lives in two places:

**1. The cache entry (in-memory struct on each node):**

```
struct Entry {
  key:        string         # logical key, e.g. "user:1234:profile"
  value:      bytes          # serialized payload (JSON/protobuf/msgpack)
  expire_at:  uint64         # epoch ms; 0 = no TTL
  last_access:uint64         # for LRU
  freq:       uint8          # for LFU (decayed counter)
  cas:        uint64         # version for compare-and-swap
  size:       uint32
}
```

**2. The cluster metadata / topology (small, strongly consistent):**

```
slot_map:   slot(0..16383) -> {primary_node_id, [replica_node_ids]}
nodes:      node_id -> {host, port, status, epoch}
```

**SQL vs NoSQL — and why neither for the data itself.**
The cache **is** the NoSQL store: a partitioned in-memory hash table. There's no relational schema because values are opaque and access is strictly by primary key — joins, range scans, and secondary indexes are non-goals. A key-value model gives O(1) lookups and trivial horizontal partitioning, which is exactly what a cache needs.

The **topology metadata**, however, must be consistent (every client must agree on which node owns a slot). That belongs in a strongly-consistent store — a Raft/Paxos group (etcd, ZooKeeper, or Redis Cluster's gossip + epoch protocol). This is a classic split: **AP for the data plane, CP for the control plane.** Getting a stale value is fine; two nodes both believing they own slot 7 (split-brain) is not.

---

## 5. High-Level Architecture

```
                         ┌──────────────────────────────────────┐
                         │            Application Tier            │
                         │  (each app server has a smart client   │
                         │   library + optional L1 near-cache)    │
                         └───────────────┬──────────────────────┘
                                         │ slot = crc16(key) % 16384
                                         │ (client routes directly)
        ┌────────────────────────────────┼────────────────────────────────┐
        │                                 │                                 │
   ┌────▼─────┐                      ┌────▼─────┐                      ┌────▼─────┐
   │ Shard A  │                      │ Shard B  │                      │ Shard C  │  ...48 shards
   │ slots    │                      │ slots    │                      │ slots    │
   │ 0–5460   │                      │ 5461–.. │                      │ ..–16383 │
   │ ┌──────┐ │                      │ ┌──────┐ │                      │ ┌──────┐ │
   │ │PRIMARY│ │   async replicate    │ │PRIMARY│ │                      │ │PRIMARY│ │
   │ └──┬───┘ │ ───────────────────► │ └──┬───┘ │                      │ └──┬───┘ │
   │ ┌──▼───┐ │                      │ ┌──▼───┐ │                      │ ┌──▼───┐ │
   │ │REPLICA│ │                      │ │REPLICA│ │                      │ │REPLICA│ │
   │ └──────┘ │                      │ └──────┘ │                      │ └──────┘ │
   └──────────┘                      └──────────┘                      └──────────┘
        ▲                                                                   ▲
        │ gossip (heartbeat, epoch, failure detection)                      │
        └───────────────────────────────────────────────────────────────────┘
                                         │
                         ┌───────────────▼──────────────┐
                         │  Control Plane (CP, Raft)     │
                         │  topology, slot ownership,    │
                         │  failover orchestration       │
                         └───────────────────────────────┘

   On miss:  app  ── cache miss ──►  Database (system of record)
                                       │
             app  ◄── populate cache ──┘  (cache-aside)
```

**Component walkthrough**

- **Smart client library.** Hashes the key to a slot, looks up the owning primary in a locally-cached slot map, and sends the request in one hop. Handles `MOVED`/`ASK` redirections during rebalancing, retries, and replica fallback. No proxy in the hot path keeps p99 low.
- **Shards (primary + replicas).** Each shard owns a contiguous range of the 16,384 hash slots. The primary serves writes and (usually) reads; replicas serve read scaling and stand by for failover. Replication is **asynchronous** by default for latency.
- **Gossip layer.** Nodes exchange heartbeats and a monotonically increasing config **epoch** to detect failures and agree on topology changes without a central bottleneck (Redis Cluster's model).
- **Control plane.** A small Raft group is the source of truth for slot ownership and orchestrates failover (promote replica → primary, bump epoch). Strongly consistent to prevent split-brain.
- **Database fallback.** The cache is *aside*: on a miss, the app reads the DB and populates the cache. The cache is never the system of record.

---

## 6. Deep Dives

### 6.1 Consistent Hashing & Rebalancing

The naive scheme `node = hash(key) % N` is catastrophic: changing `N` (add/remove a node) remaps ~all keys, causing a mass miss storm that stampedes the DB.

**Consistent hashing** maps both nodes and keys onto a fixed ring (e.g. 0..2³²). A key is owned by the next node clockwise. Adding/removing a node only remaps keys between two adjacent points — on average **K/N keys move** instead of all of them.

```
        key1
         ●
   N4 ───────── N1
   /             \
  ●               ●  ← key2 owned by N1 (next node clockwise)
   \             /
   N3 ───────── N2
         ●
        key3
```

**Virtual nodes (vnodes).** A physical node placed at one ring point creates lumpy load. Instead each physical node owns 100–200 *virtual* points scattered around the ring. This (a) smooths load distribution, and (b) means when a node leaves, its share is redistributed across *many* nodes rather than dumped on one neighbor.

**Redis Cluster's variant:** rather than a continuous ring, it uses **16,384 fixed hash slots** (`crc16(key) % 16384`). Slots are explicitly assigned to nodes. Rebalancing = migrating slots (and their keys) between nodes; during migration the source replies `ASK`/`MOVED` so clients chase keys to the right place. This makes ownership *explicit and inspectable* (easier ops than a pure ring).

**Rebalancing trade-off:** migrate slots gradually in the background, rate-limited, to avoid a thundering-herd of misses and to cap the bandwidth spent copying. Use **hash tags** (`{user123}:profile`, `{user123}:settings`) to co-locate related keys in the same slot for multi-key ops.

### 6.2 Hot Keys

A single "celebrity" key (viral post, flash-sale inventory counter) routes all its traffic to one shard. At 700K QPS that one node saturates CPU/NIC while 47 others idle — sharding doesn't help because it's *one key*.

Mitigations, in order of escalation:

1. **Client-side near-cache (L1).** Each app server caches hot values locally for a very short TTL (e.g. 1–5 s) or with versioned invalidation. 1000 app servers each hitting their own L1 collapses 700K remote QPS into ~1000 QPS. The cost is bounded staleness (see Near-Cache deep dive).
2. **Key splitting / replication of the value.** Store the hot value under N replicas `hotkey#0 … hotkey#N`; clients pick a random suffix on read, spreading load across N shards. Writes fan out to all N copies.
3. **Read from replicas.** Direct hot-key reads to the shard's read replicas, multiplying read capacity by replica count.
4. **Detection.** Sample request streams (count-min sketch / heavy-hitter algorithm) per node to auto-detect hot keys and promote them to L1 dynamically.

### 6.3 Cache Stampede, Penetration & Avalanche

These are the three classic failure modes that turn a cache into a DB-killing amplifier.

**Cache stampede (dogpile).** A hot key expires; thousands of concurrent requests miss simultaneously and all hit the DB to recompute the same value.
- *Locking / single-flight:* the first miss acquires a lock (`ADD lock:key`); others wait briefly and re-read. Only one DB query runs.
- *Probabilistic early expiration (XFetch):* recompute the value slightly *before* TTL with a probability that rises as expiry nears, so one request refreshes it while the old value still serves everyone else.
- *Stale-while-revalidate:* serve the stale value and refresh asynchronously in the background.

**Cache penetration.** Requests for keys that **don't exist** (often malicious or scanning) always miss the cache and hammer the DB.
- *Negative caching:* cache the "not found" result with a short TTL.
- *Bloom filter:* keep a Bloom filter of all valid keys in front of the cache; a definite-negative short-circuits before touching the DB.

**Cache avalanche.** A large set of keys with the *same* TTL all expire at once (or a whole cache node dies), shifting massive load to the DB simultaneously.
- *TTL jitter:* add random ± spread to every TTL (`ttl = base + rand(0, base*0.1)`) so expirations scatter.
- *Multi-level caching + replicas:* a node death is absorbed by replicas and L1 caches, not the DB.
- *Request coalescing + circuit breaker:* cap concurrent DB calls; shed/queue beyond a threshold.

### 6.4 Write Policies & Cache Coherence

The write path determines the consistency/latency/durability triangle.

| Policy | How it works | Pros | Cons |
|---|---|---|---|
| **Cache-aside** (lazy) | App reads cache, on miss reads DB and populates; on write, writes DB then **deletes** (not updates) the cache key | Simple, resilient (cache failure ≠ data loss), only caches what's used | First read after write is a miss; brief stale window if invalidation races a populate |
| **Write-through** | App writes cache; cache synchronously writes DB | Cache always fresh, read-your-writes | Higher write latency; caches data that may never be read |
| **Write-behind** (write-back) | Write to cache, async-flush to DB later (batched) | Lowest write latency, absorbs write bursts | Data-loss risk if node dies before flush; complex; needs durability |
| **Read-through** | Cache itself loads from DB on miss (library/sidecar owns the fetch) | App code is simpler | Cache must know how to query the DB |

**Why delete-on-write, not update-on-write, for cache-aside?** Updating risks a classic race: two writers' updates interleave with reads and the cache ends up holding a value that never existed in the DB. **Deleting** the key forces the next read to repopulate from the DB's authoritative value. The remaining hazard (read populates a stale value *after* a delete) is mitigated by short TTLs and, in strict setups, **delayed double-delete** (delete, write DB, wait a beat, delete again).

**Cross-region coherence:** propagate invalidations via a log (Kafka topic of `DELETE key` events) or DB CDC (Debezium → Kafka). Each region's cache subscribes and evicts. This is eventually consistent — bounded by replication lag + TTL.

**CAP positioning:** the data plane is **AP** — under a partition we keep serving (possibly stale) reads rather than block, because a cache that returns *probably-correct fast* beats one that returns *nothing*. TTLs cap how stale a value can get. The control plane (slot ownership) is **CP** to avoid split-brain.

### 6.5 Eviction Policies

When a node hits its memory limit it must evict. Choice depends on access pattern:

- **LRU (Least Recently Used):** evict the entry untouched longest. Great for temporal locality. Implemented with a hash map + doubly-linked list (O(1)), or sampled/approximate LRU (Redis samples N keys and evicts the oldest of the sample — avoids the list overhead at scale).
- **LFU (Least Frequently Used):** evict the least-accessed entry. Better when popularity is stable and some keys are durably hot. Needs frequency counters with **decay** (Redis uses a logarithmic, probabilistic counter) so old-but-once-popular keys don't stick forever.
- **TTL / volatile policies:** evict the entry closest to expiry, or only evict keys that have a TTL set (`volatile-lru`, `volatile-ttl`), protecting persistent keys.
- **FIFO / random:** cheap, used when access has no locality or for simplicity.
- **Modern (W-TinyLFU):** admission filter (count-min sketch) + segmented LRU; near-optimal hit ratios, used by Caffeine. Worth mentioning as the state of the art for near-caches.

**Expiration mechanics:** combine **lazy expiration** (check `expire_at` on access) with **active expiration** (a background sampler that proactively reaps expired keys) so dead keys don't squat on memory indefinitely.

---

## 7. Scaling, Bottlenecks & Failure Handling

**What breaks first?**

1. **Memory on a shard** (the binding constraint from §2). *Fix:* add shards and rebalance slots; enforce per-node maxmemory + eviction so a node degrades gracefully instead of OOM-killing.
2. **A hot key** saturating one node's NIC/CPU. *Fix:* L1 near-cache, key replication, replica reads (§6.2).
3. **The database during a miss storm** (cold start, avalanche, stampede). *Fix:* the §6.3 defenses + DB connection limits / circuit breakers so the DB never receives more than it can serve.

**Replication & failover.** Each shard has 1 primary + ≥1 async replica. The control plane's failure detector (gossip + Raft) promotes a replica on primary death and bumps the config epoch; clients learn the new owner via `MOVED`. Async replication means a tiny window of recently-written, un-replicated data can be lost on failover — acceptable for a cache (re-fetchable from the DB). For session-store use cases needing durability, enable AOF/RDB persistence or `WAIT` for synchronous acks at a latency cost.

**Cold start / warming.** A freshly added or restarted node is empty → every request misses → DB pressure spikes. Mitigations: (1) **snapshot warming** — load a recent RDB/dump on startup; (2) **shadow/dark traffic** — mirror reads to the cold node so it warms before taking live traffic; (3) **gradual slot migration** that copies existing keys rather than starting empty; (4) **request coalescing** during warmup so concurrent misses don't multiply on the DB.

**Circuit breakers & bulkheads.** Between cache and DB, a circuit breaker trips when DB latency/errors spike, shedding load (serve stale or fail fast) to protect the DB from a death spiral. Bulkhead connection pools isolate one bad dependency from starving others.

**Multi-region / DR.** Run an independent cluster per region (caches are regional — replicating cache data cross-region is rarely worth the bandwidth/staleness). Coherence via a global invalidation log (Kafka / CDC). On regional failure, traffic fails over to another region whose cache is cold-ish but functional; warm it via snapshot replication of the *DB*, not the cache. RPO for the cache is effectively 0 (rebuildable); RTO is dominated by DB failover.

**Observability:** track hit ratio (the north-star metric — a dropping hit ratio is the leading indicator of trouble), p99 latency, evictions/sec, memory fragmentation ratio, hot-key heat map, and replication lag.

---

## 8. Trade-offs & Alternatives

**Redis (Cluster) vs Memcached — the canonical interview comparison.**

| Dimension | Redis Cluster | Memcached |
|---|---|---|
| Data types | Rich (strings, hashes, sets, sorted sets, streams) | Strings/blobs only |
| Threading | Mostly single-threaded core (I/O threads added later) | Natively multi-threaded |
| Sharding | Built-in (16,384 slots, gossip) | Client-side only (consistent hashing) |
| Replication / failover | Built-in primary-replica + auto failover | None (just a cache pool) |
| Persistence | RDB/AOF optional | None (pure cache) |
| Memory efficiency | More per-key overhead | Leaner, slab allocator, less fragmentation |
| Best for | Feature-rich cache, leaderboards, pub/sub, when you need failover | Dead-simple, multi-core, pure ephemeral cache at max RAM efficiency |

**Choice:** for this design (failover, TTL, atomic ops, pub/sub invalidation) **Redis Cluster** is the better fit. Memcached wins only if the workload is "huge pool of simple blobs, max RAM/$ , no failover needed."

**Other explicit decisions:**
- **Client-side routing vs proxy (Envoy/Twemproxy/mcrouter).** Client-side = one hop, lowest latency, but fat clients in every language. A proxy centralizes routing/topology logic and connection pooling at the cost of an extra hop. We chose client-side for latency; a proxy is the right call when client diversity is high.
- **Async vs sync replication.** Async chosen for latency; sync (`WAIT`) only where durability matters.
- **AP data plane** accepted because stale-fast > consistent-slow for a cache.

**At 10x:** memory dominates — add shards, push harder on L1 near-caches to cut remote QPS, and adopt W-TinyLFU admission to lift hit ratios. Sharper hot-key auto-detection.

**At 100x (global, exabyte-adjacent reads):** introduce a **tiered cache** (L1 in-process → L2 regional Redis → L3 SSD-backed cache like a flash tier for warm-but-not-hot data) to escape the pure-RAM cost wall; lean heavily on CDN for cacheable HTTP responses at the edge; consider DynamoDB DAX / Memcached-on-NVMe patterns; and treat the global invalidation bus (Kafka) as a first-class, multi-region-replicated system in its own right. Accept that perfect coherence is impossible at this scale — design the product around TTL-bounded staleness.

---

## Interview Q&A by Level

### 🟢 Basic

**Q: What problem does a cache solve, and where does it sit?**
It stores frequently-accessed data in fast memory between the app and a slower datastore, cutting read latency and offloading the DB. It sits as a *side* layer (cache-aside) or inline (read/write-through). Effective when reads ≫ writes and there's access locality.

**Q: What is TTL and why is it important?**
Time-to-live auto-expires an entry after N seconds. It bounds staleness (the value can be at most TTL old), reclaims memory, and is the simplest coherence mechanism — without it, evicting stale data relies entirely on explicit invalidation.

**Q: Cache hit vs miss — and what's a good hit ratio?**
A hit serves from cache; a miss falls through to the DB and (usually) populates the cache. Hit ratio = hits / (hits + misses). For a well-tuned read cache you want 90%+; the number is workload-specific, but a *falling* hit ratio is the canary for trouble.

### 🟡 Intermediate

**Q: Explain cache-aside vs write-through vs write-behind.**
Cache-aside: app manages the cache, populating lazily on miss and invalidating on write — simple and failure-tolerant. Write-through: writes go through the cache to the DB synchronously, keeping the cache fresh at higher write latency. Write-behind: write to cache, flush to DB asynchronously — lowest latency but risks data loss on crash. (See §6.4.)

**Q: Why delete the cache key on write instead of updating it?**
Updating risks an interleaving where concurrent writes/reads leave a value in the cache that never existed in the DB. Deleting forces the next read to repopulate from the authoritative DB value. The residual stale-populate race is handled with short TTLs or delayed double-delete.

**Q: How does consistent hashing reduce rebalancing pain?**
Mapping nodes and keys onto a ring means adding/removing a node only remaps the ~K/N keys between adjacent ring points, not all keys (as `hash % N` would). Virtual nodes smooth load and spread a departing node's keys across many survivors.

**Q: How do you prevent a cache stampede?**
Single-flight locking so only one request recomputes, probabilistic early recomputation (XFetch), or stale-while-revalidate to serve the old value while refreshing in the background. (§6.3.)

### 🟠 Advanced

**Q: A single key gets 700K QPS and melts one shard. What do you do?**
Sharding can't help one key. Escalate: per-app-server L1 near-cache with a short TTL to collapse remote QPS by the number of app servers; replicate the value under N suffixed keys to spread across shards; serve from read replicas; and auto-detect the hot key with a heavy-hitter sketch to promote it dynamically. (§6.2.)

**Q: Walk through node failure and recovery without serving wrong data.**
Gossip detects the dead primary; the control plane's Raft group promotes a replica and bumps the config epoch (the epoch prevents the old primary from re-asserting ownership — no split-brain). Clients hit the old node, get `MOVED`, refresh topology, and retarget. Async-replicated un-acked writes may be lost, which is fine because they're re-fetchable from the DB. (§7.)

**Q: How do you keep caches coherent across regions?**
Run an independent regional cluster (don't replicate cache data cross-region) and broadcast invalidations over a global log — Kafka events or DB CDC (Debezium). Each region evicts on the event. It's eventually consistent, bounded by replication lag + TTL; design the product to tolerate that window.

**Q: Where does this system sit on CAP, and why?**
The **data plane is AP**: under a partition we keep serving possibly-stale reads rather than blocking, because for a cache "probably-correct and fast" beats "nothing," and TTLs bound staleness. The **control plane is CP** (Raft for slot ownership) because split-brain on ownership would corrupt routing. Splitting the two is the key insight.

### 🔴 Expert

**Q: Design the eviction subsystem for a node with 64 GB and a mixed hot/warm workload. Defend your policy.**
Use **W-TinyLFU**: a count-min-sketch admission filter decides whether a newly-loaded key is worth keeping vs. the eviction candidate, fronting a segmented LRU. This beats plain LRU (which a single scan can pollute) and plain LFU (which over-retains stale-but-once-popular keys, unless decayed). Pair lazy expiration (check on access) with an active sampler (reap a random sample each tick) so expired keys don't squat memory. Enforce per-node `maxmemory` with `volatile-lru` to protect TTL-less keys, and watch the fragmentation ratio to trigger defrag. Trade-off: W-TinyLFU's sketch costs a few bytes/key and added complexity, justified at this scale by the hit-ratio lift.

**Q: You must offer read-your-writes for a subset of users without making the whole cache strongly consistent. How?**
Scope strong consistency narrowly. On a user's write, write-through that user's keys (or delete + synchronously repopulate) and pin a short "freshness token" / version in a fast strongly-consistent store keyed by user; that user's subsequent reads check the token and bypass-or-validate the cache until the version matches. Everyone else stays on the cheap AP path. Alternatively route a user's reads to the primary (not replicas) for a short sticky window after their write. This buys per-user RYW at the cost of a little extra latency only for recently-writing users — you don't pay global consistency cost.

**Q: At 100x scale the pure-RAM cache is cost-prohibitive. Re-architect.**
Move to a **tiered cache**: L1 in-process (W-TinyLFU, microsecond, absorbs hot keys), L2 regional RAM Redis (hot working set), L3 NVMe/SSD-backed cache (warm set at ~10x lower $/GB, sub-ms but slower than RAM), with the DB/object store as L4. Promote/demote between tiers by access frequency. Push cacheable HTTP at the CDN edge to shed traffic before it reaches L2. The global invalidation bus (Kafka, multi-region-replicated) becomes a first-class system. Accept TTL-bounded staleness as a product constraint — perfect global coherence is infeasible. The dominant trade-off shifts from "RAM cost" to "tier-management complexity + the staleness budget you spend to save money."

**Q: How do you safely rebalance slots on a live cluster under load?**
Migrate slots incrementally and rate-limited. During migration the source node serves keys it still holds and replies `ASK` for keys already moved, so clients chase individual keys to the target without a topology-wide flush. Bump the config epoch only when a slot fully transfers, so ownership is unambiguous. Cap concurrent migrating slots and the copy bandwidth so you don't (a) starve live traffic of NIC/CPU or (b) create a miss storm. Co-locate related keys via hash tags so multi-key ops survive migration. The trade-off is slower rebalancing for zero downtime and bounded blast radius.

---

*This document is part of an interview-prep guide. Treat the numbers as illustrative back-of-envelope estimates — in a real interview, state your assumptions out loud and adjust as the interviewer pushes.*
