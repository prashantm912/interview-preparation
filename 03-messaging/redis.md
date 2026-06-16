# Redis — Interview Preparation Guide

Redis (REmote DIctionary Server) is an in-memory data-structure store used as a cache, database, message broker, and streaming engine. This guide covers Redis from fundamentals through the kind of distributed-systems trade-offs a staff engineer is expected to reason about, with Java examples (Jedis / Lettuce / Spring Data Redis), current through 2026 (Redis 7.x, the Redis 8 unified server with vector sets, and the Valkey fork that emerged after the 2024 license change).

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

### Q1. [Theory] What is Redis and why is it so fast?

Redis is an in-memory key-value store where the entire dataset normally lives in RAM, so reads and writes avoid disk seeks entirely — typical operations complete in tens of microseconds. Three design choices drive its speed: (1) **everything is in memory**, so there is no page-cache miss penalty; (2) the core command execution is **single-threaded**, eliminating lock contention and context-switch overhead on the hot path; and (3) it uses an **event-driven, non-blocking I/O multiplexer** (epoll/kqueue) to handle thousands of connections on one thread. It is "data-structure aware" — unlike a plain cache that stores opaque blobs, Redis understands lists, sets, sorted sets, etc., so you can mutate a value server-side (e.g. `INCR`, `LPUSH`, `ZADD`) without a read-modify-write round trip. The trade-off is that your working set must fit in RAM (or be evicted), and memory is more expensive than disk.

### Q2. [Theory] What are the core Redis data types and when do you use each?

| Type | Description | Typical use |
|------|-------------|-------------|
| **String** | Bytes up to 512 MB; can be int/float | Cache values, counters (`INCR`), bitmaps |
| **List** | Ordered, linked-list-like | Queues, recent-activity feeds (`LPUSH`/`RPOP`) |
| **Set** | Unordered unique members | Tags, unique visitors, set algebra (`SINTER`) |
| **Sorted Set (ZSet)** | Set ordered by a score | Leaderboards, rate limiting, priority queues, time-series indexing |
| **Hash** | Field→value map under one key | Storing an object (user profile) compactly |
| **Bitmap** | Bit operations on a string | Daily active users, feature flags per user id |
| **HyperLogLog** | Probabilistic cardinality (~0.81% error, 12 KB) | Counting unique items at scale |
| **Stream** | Append-only log with consumer groups | Event sourcing, durable messaging |
| **Geo** | Geospatial index (built on ZSet) | "Find nearby" queries |

A staff-level answer adds: choose the type that lets the *server* do the work. Storing a user as a Hash instead of a JSON string lets you update one field (`HSET user:1 lastLogin ...`) without re-serializing the whole object.

### Q3. [Practical] How do you connect to Redis from Java, and Jedis vs Lettuce?

Two mainstream clients exist. **Jedis** is simple and blocking — each command holds a connection, so you pool connections. **Lettuce** is built on Netty, is thread-safe, non-blocking, and supports reactive/async APIs; a single Lettuce connection can be shared across threads. Spring Boot defaults to Lettuce since Spring Boot 2.

```java
// Lettuce — one connection shared safely across threads
RedisClient client = RedisClient.create("redis://localhost:6379");
StatefulRedisConnection<String, String> conn = client.connect();
RedisCommands<String, String> sync = conn.sync();
sync.set("user:1:name", "Ada");
String name = sync.get("user:1:name");

// Jedis — pooled, one connection per operation
JedisPool pool = new JedisPool("localhost", 6379);
try (Jedis jedis = pool.getResource()) {
    jedis.setex("session:abc", 1800, "userId=42"); // 30-min TTL
}
```

**What I'd do in production:** use Lettuce (Spring's default) for its connection multiplexing and reactive support; switch to Jedis only if a team strongly prefers the simpler blocking model. Always set connection/command timeouts — a hung Redis call without a timeout can stall an entire thread pool.

### Q4. [Theory] How does key expiration (TTL) work?

You attach a time-to-live to a key with `EXPIRE key seconds`, `PEXPIRE` (ms), or set value+TTL atomically with `SET key val EX 60` / `SETEX`. Redis uses a hybrid expiry strategy: **lazy expiration** (a key is checked and deleted when accessed) plus **active expiration** (a background cycle samples random keys with TTLs ~10×/second and evicts expired ones). This means an expired key can briefly linger in memory until sampled — relevant for memory accounting. TTL is the foundation of caching, sessions, idempotency keys, and rate-limit windows. Note: writing a new value with `SET` (without `KEEPTTL`) clears an existing TTL — a common bug.

### Q5. [Coding] Implement an atomic counter with a daily reset.

**Problem:** Count API calls per user per day; the counter must reset each day and never lose increments under concurrency.

The key insight is that `INCR` is atomic (single-threaded server), so no application-side lock is needed. We embed the date in the key and set a TTL only on first creation.

```java
public long incrementDailyCount(RedisCommands<String, String> redis, String userId) {
    String day = LocalDate.now().toString();          // e.g. 2026-06-16
    String key = "count:" + userId + ":" + day;
    long count = redis.incr(key);                      // atomic, creates key at 0->1
    if (count == 1L) {
        redis.expire(key, 60 * 60 * 26);               // ~26h TTL covers TZ skew
    }
    return count;
}
```

**Time/Space:** O(1) per call; O(active users) keys in memory, each auto-expiring.
**Edge cases:** The `INCR`-then-`EXPIRE` is two commands — if the process crashes between them, the key would live forever. For correctness use a Lua script (see Q18) to make it a single atomic unit, or use `SET key 1 EX 93600 NX` then `INCR`. Also, never use the `expire` check `count == 1` if the key might be recreated by another path without expiry.

---

## 🟡 Intermediate (3–7 yrs)

### Q6. [Theory] Explain Redis persistence: RDB vs AOF. Which would you choose?

Redis is in-memory but can persist to disk two ways. **RDB (Redis Database)** takes point-in-time snapshots by `fork()`ing the process and writing a compact binary dump; it is fast to load and great for backups, but you can lose all writes since the last snapshot (e.g. minutes of data). **AOF (Append-Only File)** logs every write command; with `appendfsync everysec` you lose at most ~1 second of data, and `always` gives the strongest durability at a heavy throughput cost. AOF files grow and are periodically rewritten (compacted). The modern recommendation is **both enabled** — RDB for fast restarts/backups and AOF for durability; on restart Redis prefers AOF as it is more complete. Redis 7 introduced **multi-part AOF** (a base RDB + incremental AOF files) which makes rewrites cheaper.

```
Write throughput / durability spectrum:
  none ──► RDB (snapshots) ──► AOF everysec ──► AOF always
  fastest, weakest                              slowest, strongest
```

**What I'd choose:** For a *cache*, often persistence off entirely (data is reconstructable). For Redis-as-a-database, AOF `everysec` + periodic RDB. Remember `fork()` during RDB/rewrite can momentarily double memory due to copy-on-write — size your host accordingly.

### Q7. [Theory] Walk through the eviction policies. What does `noeviction` do?

When memory hits `maxmemory`, Redis applies a `maxmemory-policy`:

- `noeviction` (default): reject writes with an error; reads still work.
- `allkeys-lru` / `volatile-lru`: evict least-recently-used keys (all keys / only keys with TTL).
- `allkeys-lfu` / `volatile-lfu`: least-frequently-used (better for skewed access; Redis 4+).
- `volatile-ttl`: evict keys with the nearest expiry.
- `allkeys-random` / `volatile-random`: random eviction.

Redis LRU/LFU are *approximate* — it samples N keys (`maxmemory-samples`, default 5) rather than maintaining a global ordering, trading precision for speed. **Critical interview point:** if you use Redis purely as a cache, set `allkeys-lru` (or `lfu`). If you leave it at `noeviction` and forget TTLs, you eventually get `OOM command not allowed` errors and writes start failing — a classic production incident. `volatile-*` policies are dangerous when most keys have no TTL, because there may be nothing eligible to evict and writes still fail.

### Q8. [Practical] You need to fetch 1,000 keys. How do you avoid 1,000 round trips?

Each command is a network round trip (RTT), so 1,000 sequential `GET`s on a 1 ms RTT link cost ~1 second of latency. Three tools:

1. **Pipelining** — send many commands without waiting for each reply; replies are read in a batch. Reduces N RTTs to ~1.
2. **`MGET`/`MSET`** — multi-key variants that fetch/set many strings in one command.
3. **Lua / functions** — execute server-side when there is logic between operations.

```java
// Pipelining with Lettuce (async)
StatefulRedisConnection<String,String> conn = client.connect();
RedisAsyncCommands<String,String> async = conn.async();
conn.setAutoFlushCommands(false);                 // batch
List<RedisFuture<String>> futures = new ArrayList<>();
for (String id : ids) futures.add(async.get("user:" + id));
conn.flushCommands();                             // single network write
LettuceFutures.awaitAll(5, TimeUnit.SECONDS, futures.toArray(new RedisFuture[0]));
```

**Trade-offs:** Pipelining boosts throughput but increases memory pressure (server buffers all replies) and is *not* a transaction — commands from other clients can interleave. For pure key fetches with no per-key TTL difference, `MGET` is simpler. In a Redis Cluster, `MGET`/pipelines must keep keys in the same slot (hash tags), or split per-node.

### Q9. [Theory] Pub/Sub vs Streams — when do you use each?

**Pub/Sub** is fire-and-forget broadcast: publishers send to channels, subscribers receive messages delivered *only while connected*. There is no persistence, no acknowledgment, no replay — a subscriber that is down misses messages. It is ideal for ephemeral fan-out (cache invalidation signals, live notifications). **Streams** (Redis 5+) are an append-only, persistent log with unique IDs, **consumer groups** (competing consumers with per-message ack via `XACK`), a pending-entries list for redelivery, and the ability to replay history (`XRANGE`). Streams give at-least-once delivery and backpressure-aware consumption, making them a lightweight Kafka-like primitive.

```
Pub/Sub:  publisher ─► channel ─► [only currently-connected subscribers]   (lossy)
Streams:  producer ─► XADD ─► stream log ─► consumer group ─► XREADGROUP ─► XACK  (durable)
```

**Rule of thumb:** if missing a message is acceptable (live presence, invalidation), Pub/Sub. If you need durability, acks, replay, or work distribution, Streams. For very high throughput or multi-day retention with strong ordering guarantees across partitions, reach for Kafka instead (see Q24).

### Q10. [Coding] Build a sliding-window rate limiter (e.g. 100 requests / 60 s per user).

**Problem:** Allow at most 100 requests in any rolling 60-second window. A fixed-window counter (Q5) allows a burst at window boundaries (200 in 2s); a sorted-set sliding window is precise.

```java
private static final String LUA = """
  local key = KEYS[1]
  local now = tonumber(ARGV[1])
  local window = tonumber(ARGV[2])
  local limit = tonumber(ARGV[3])
  redis.call('ZREMRANGEBYSCORE', key, 0, now - window)   -- drop old
  local count = redis.call('ZCARD', key)
  if count < limit then
      redis.call('ZADD', key, now, now .. ':' .. math.random())
      redis.call('PEXPIRE', key, window)
      return 1
  end
  return 0
  """;

public boolean allow(RedisCommands<String,String> redis, String user, long limit) {
    long now = System.currentTimeMillis();
    Long ok = redis.eval(LUA, ScriptOutputType.INTEGER,
              new String[]{ "rl:" + user },
              String.valueOf(now), "60000", String.valueOf(limit));
    return ok == 1L;
}
```

**Time/Space:** Each call is O(log N + M) where M is the number of expired entries removed; memory is O(requests in window) per user.
**Why Lua:** the read (`ZCARD`) and conditional write must be atomic — between a plain `ZCARD` and `ZADD`, a concurrent request could both see "99" and both add, exceeding the limit. Lua runs as a single atomic unit.
**Edge cases:** clock skew across app servers (use server-side `redis.call('TIME')` for the timestamp to be safe); high cardinality of members in busy windows (consider the token-bucket variant which stores only a count + timestamp for lower memory).

### Q11. [Theory] Explain Redis transactions (MULTI/EXEC) and optimistic locking with WATCH.

`MULTI` starts a transaction; subsequent commands are *queued*, and `EXEC` runs them sequentially and atomically with no interleaving from other clients. Crucially, Redis transactions are **not** like SQL transactions: there is **no rollback** — if a command fails at runtime (e.g. `INCR` on a non-numeric value), the other commands still execute. Errors detectable at queue time (bad syntax) abort the whole transaction. For atomic check-then-act, use `WATCH key` for optimistic concurrency: if any watched key changes before `EXEC`, the transaction aborts (returns nil) and you retry. This is compare-and-swap, not pessimistic locking.

```java
String balanceKey = "acct:1:balance";
redis.watch(balanceKey);
long bal = Long.parseLong(redis.get(balanceKey));
if (bal >= 100) {
    redis.multi();
    redis.decrby(balanceKey, 100);
    TransactionResult r = redis.exec();   // null if key changed -> retry loop
}
```

In practice many teams prefer a **Lua script** over `WATCH`/`MULTI`/retry because Lua is atomic by construction and avoids the retry loop entirely.

### Q12. [Practical] Explain the cache-aside, write-through, and write-behind patterns.

**Cache-aside (lazy loading):** the app checks the cache; on a miss it reads the DB, populates the cache, and returns. Simplest and most common; cache holds only requested data. Downsides: first request per key is slow, and cache can serve stale data until TTL/invalidation.

**Write-through:** the app writes to the cache, which synchronously writes to the DB. Cache and DB stay consistent and reads are always warm, but every write pays the DB latency and you may cache data never read.

**Write-behind (write-back):** the app writes to the cache, which asynchronously flushes to the DB in batches. Lowest write latency and great for write-heavy workloads, but risks **data loss** if Redis dies before flushing, and adds complexity.

```
Cache-aside read:        Write-through write:
 app ─► cache (miss)       app ─► cache ─► DB (sync)
     ─► DB                              ◄── ack
     ─► cache.set
```

```java
// Cache-aside with Spring's @Cacheable (Spring Boot 3 / Spring Cache + Redis)
@Cacheable(value = "products", key = "#id")
public Product getProduct(Long id) { return repo.findById(id).orElseThrow(); }

@CacheEvict(value = "products", key = "#p.id")
public void update(Product p) { repo.save(p); }   // invalidate on write
```

**What I'd do:** cache-aside as the default; write-through where read-after-write consistency matters (config, pricing); write-behind only with durability safeguards (e.g. also append to a stream) and idempotent DB writes.

### Q13. [Theory] What are cache stampede, penetration, and avalanche? How do you mitigate each?

These are the three classic cache failure modes:

- **Stampede / dogpile:** a hot key expires and thousands of concurrent requests all miss and hit the DB simultaneously. *Mitigations:* a per-key lock/mutex so only one request recomputes (others wait or serve stale), probabilistic early recomputation ("XFetch"), or request coalescing.
- **Penetration:** requests for keys that **don't exist** (often malicious) always miss the cache and hammer the DB. *Mitigations:* cache the negative result (a short-TTL null marker), and/or a **Bloom filter** (RedisBloom) to reject keys that definitely don't exist.
- **Avalanche:** many keys expire at the same instant (e.g. all set with TTL 3600 at startup), causing a mass miss and a DB spike. *Mitigation:* add **jitter** to TTLs (`3600 + random(0..300)`), and use a layered/clustered cache so one node failing doesn't drop the whole cache.

```java
// Stampede mitigation: single-flight recompute lock
String lock = "lock:" + key;
if (redis.set(lock, "1", SetArgs.Builder.nx().px(5000)) != null) {
    Value v = loadFromDb();                 // only the lock holder recomputes
    redis.setex(key, ttlWithJitter(), serialize(v));
    redis.del(lock);
    return v;
}
return serveStaleOrWaitBriefly(key);        // others avoid the DB
```

**Real-world:** Facebook's "leases" mechanism (memcached) and similar single-flight strategies exist precisely to prevent stampedes at scale; the same principle applies to Redis.

### Q14. [Coding] Implement a leaderboard with top-N and a player's rank.

**Problem:** Maintain a game leaderboard supporting score updates, top-10 retrieval, and a given player's rank — efficiently for millions of players.

A Sorted Set is the perfect fit: members are players, scores are points, and operations are O(log N).

```java
public void submitScore(RedisCommands<String,String> redis, String player, double score) {
    redis.zadd("leaderboard", score);                       // upsert player score
}

public List<String> topN(RedisCommands<String,String> redis, int n) {
    // ZREVRANGE: highest scores first, indices 0..n-1
    return redis.zrevrange("leaderboard", 0, n - 1);
}

public long rank(RedisCommands<String,String> redis, String player) {
    Long r = redis.zrevrank("leaderboard", player);         // 0-based, null if absent
    return r == null ? -1 : r + 1;                          // 1-based human rank
}
```

**Time/Space:** `ZADD` and `ZREVRANK` are O(log N); `ZREVRANGE` top-N is O(log N + N). Memory is O(players).
**Edge cases:** ties — players with equal scores are ordered lexicographically by member, which may surprise users; encode a tiebreaker (e.g. `score - timestamp/1e13`) into the score if "first to reach" should win. For weekly leaderboards use a key per period with a TTL; for billions of members, shard by region and merge top-Ns.

### Q15. [Practical] How do you store and serialize objects in Redis? Pitfalls?

You can store an object as a serialized **String** (JSON, Protobuf, Java serialization) or as a **Hash** (field per attribute). JSON is human-readable and language-neutral but verbose; Protobuf/MessagePack are compact and fast; Java native serialization is fragile (version-coupled, a security risk on deserialization) and should be avoided. Hashes let you update individual fields and are memory-efficient for small objects (Redis uses a compact `listpack` encoding under `hash-max-listpack-entries`). Pitfalls: **big keys** — a single huge hash/list (hundreds of MB) blocks the single thread on operations like `DEL` (use `UNLINK` for async free) and causes uneven cluster slots; **serialization drift** when the class schema changes; and storing large blobs that should live in object storage. With Spring Data Redis, prefer `GenericJackson2JsonRedisSerializer` over JDK serialization, and version your payloads.

---

## 🟠 Advanced (8–12 yrs)

### Q16. [Theory] Explain Redis Cluster and the 16384 hash-slot model.

Redis Cluster shards data across multiple primaries by partitioning the keyspace into **16,384 hash slots**. The slot for a key is `CRC16(key) mod 16384`. Each primary owns a contiguous-ish range of slots; clients learn the slot→node map and route commands directly (the cluster is "smart-client" oriented). If a client sends a command to the wrong node, it gets a `MOVED` redirect (permanent reassignment) or `ASK` (in-flight migration). Multi-key commands must touch keys in the **same slot**, which is why **hash tags** exist: `{user:1000}:profile` and `{user:1000}:settings` hash only the `{...}` portion, forcing co-location. There is **no cross-slot transaction or multi-key atomicity** across nodes — a fundamental constraint when designing for cluster mode.

```
16384 slots split across 3 primaries (each with a replica):
  P1: slots 0–5460     R1
  P2: slots 5461–10922 R2
  P3: slots 10923–16383 R3
  key "{user:42}:x"  ─► CRC16("user:42") % 16384 ─► slot 6543 ─► P2
```

Why 16384 and not more? Smaller bitmap for the gossip heartbeat that carries slot ownership, keeping cluster-bus messages compact while still allowing up to ~1000 nodes practically.

### Q17. [Theory] Redis Sentinel vs Cluster — what problem does each solve?

They solve *different* problems and are sometimes confused. **Sentinel** provides **high availability for a single, non-sharded dataset**: a set of Sentinel processes monitor a primary and its replicas, achieve quorum on failure detection, and perform **automatic failover** by promoting a replica and reconfiguring clients (via pub/sub notifications). It does *not* shard data. **Cluster** provides **horizontal scaling (sharding) plus built-in HA** — each shard has its own replicas and the cluster handles failover internally via the gossip protocol, no separate Sentinel needed.

```
Sentinel (HA only):                 Cluster (shard + HA):
  [S][S][S] watch                     gossip-connected nodes,
   P ◄─ replicate ─ R1, R2            each shard = P + replicas
   (one logical dataset)             (data partitioned by slot)
```

**Decision:** dataset fits one node and you only need failover → Sentinel (simpler). Dataset exceeds one node's RAM or you need write throughput beyond one core → Cluster. Many managed offerings (AWS ElastiCache, Redis Enterprise) abstract this, but the trade-offs still surface in failover semantics and multi-key limitations.

### Q18. [Coding] Write a safe distributed lock with fencing. What's wrong with naive `SETNX`?

**Problem:** Coordinate exclusive access to a resource across processes. A naive lock has two bugs: it may never expire (holder crashes), and a client may delete *another* client's lock after its own TTL expired.

```java
// Acquire: atomic set-if-absent WITH expiry, storing a unique owner token
String token = UUID.randomUUID().toString();
boolean acquired = redis.set("lock:resource", token,
        SetArgs.Builder.nx().px(10000)) != null;   // NX + PX in ONE command

// Release: only delete if WE still own it — must be atomic (Lua)
String unlock = """
  if redis.call('get', KEYS[1]) == ARGV[1] then
      return redis.call('del', KEYS[1])
  else
      return 0
  end """;
redis.eval(unlock, ScriptOutputType.INTEGER, new String[]{"lock:resource"}, token);
```

**Why naive `SETNX` then `EXPIRE` is broken:** the two commands aren't atomic — a crash between them leaves an eternal lock. Always use `SET ... NX PX`. **Why the unique token + Lua release matters:** without it, if client A's lock expires while A is still working and client B acquires it, A's later `DEL` deletes B's lock — releasing someone else's mutual exclusion. The token check ensures you only release your own.
**Time/Space:** O(1). **Edge cases / fencing:** even a correct Redis lock cannot guarantee mutual exclusion under GC pauses or network delays — see the Redlock debate (Q19). For correctness-critical resources, the protected store must accept a monotonically increasing **fencing token** and reject stale ones.

### Q19. [Theory] What is Redlock and why is it controversial?

Redlock is the algorithm Redis proposes for distributed locking across **N independent** Redis masters (no replication): a client tries to acquire the lock on a majority (N/2+1) within a bounded time; if it succeeds on the majority before the lock validity elapses, it holds the lock. The goal is to survive individual node failures without relying on replication (which is asynchronous and can lose the lock on failover). **Martin Kleppmann's critique:** Redlock conflates two use cases. For *efficiency* (avoid duplicate work) a simple single-instance lock is fine. For *correctness* (never two holders), Redlock is unsafe because it depends on bounded clock drift and bounded pauses — a long GC pause or clock jump can cause two clients to believe they hold the lock; the only robust fix is a **fencing token** validated by the resource itself, which Redlock doesn't provide. **Antirez's rebuttal** argued the assumptions are reasonable in practice and that fencing can be layered on. **Pragmatic stance for an interview:** use Redis locks for efficiency; for correctness use a system with linearizable consensus (Zookeeper, etcd, or a DB with proper transactions) and fencing tokens. Single-instance `SET NX PX` is sufficient for most real workloads and far simpler than Redlock.

### Q20. [Practical] Production Redis is at 95% memory and latency is spiking. Walk through your diagnosis.

I'd treat it like an incident with a checklist:

1. **`INFO memory`** — check `used_memory`, `maxmemory`, `mem_fragmentation_ratio` (high → fragmentation, consider `activedefrag` or restart; >1 also includes RSS overhead).
2. **`maxmemory-policy`** — if `noeviction`, writes are about to fail; if a cache, this should be `allkeys-lru/lfu`.
3. **Find big keys** — `redis-cli --bigkeys` or `MEMORY USAGE key`; one giant hash/set often dominates and blocks the single thread.
4. **`SLOWLOG GET`** — look for O(N) commands run against big collections (`KEYS *`, `HGETALL` on huge hashes, `SMEMBERS`); `KEYS` in production is a cardinal sin — use `SCAN`.
5. **`INFO stats`** — `expired_keys` vs `evicted_keys`, `keyspace_hits/misses` (hit ratio), `instantaneous_ops_per_sec`, blocked clients.
6. **Connection/latency** — `redis-cli --latency`, check `LATENCY DOCTOR`, look for `fork` pauses from RDB/AOF rewrite (`latest_fork_usec`).

**Likely root causes & fixes:** missing TTLs filling memory (add TTLs/eviction), a hot big key (split it, use `UNLINK` not `DEL`), an O(N) command on the hot path (replace `KEYS` with `SCAN`, paginate), or fork-induced latency (tune `save`/AOF rewrite schedule, ensure enough free RAM for COW). Long-term: capacity planning, alerting on memory and hit ratio, and possibly moving to Cluster to spread load.

### Q21. [Theory] How does Redis replication work, and what consistency guarantees does it give?

Replication is **asynchronous**: a primary streams its write command buffer to replicas. On connect, a replica does a **PSYNC** — a full resync (primary forks an RDB and sends it, then streams the backlog) or a **partial resync** if it only briefly disconnected (replicating from the offset in the replication backlog). Because replication is async, Redis offers **no strong consistency**: a write acknowledged by the primary may be lost if the primary crashes before the replica receives it, and on failover that write disappears (lost-write window). `WAIT numreplicas timeout` lets a client block until N replicas ack, improving durability but not making it linearizable, and it costs latency. The `min-replicas-to-write` setting can refuse writes when too few replicas are connected, trading availability for durability. **Interview takeaway:** Redis is an AP-leaning system; never assume read-your-writes across a primary→replica read split without `WAIT` or routing reads to the primary.

### Q22. [Coding] Atomically pop a job from a queue with reliable processing (no lost jobs on crash).

**Problem:** A worker pulls a job from a queue; if the worker crashes mid-processing, the job must not be lost. A plain `LPOP` loses the job on crash.

**Approach 1 — reliable queue with `LMOVE`** (Redis 6.2+, replaces deprecated `RPOPLPUSH`): atomically move the job to a per-worker "processing" list; on success remove it, on crash a reaper requeues stale entries.

```java
// Atomically move job from queue -> this worker's processing list
String job = redis.lmove("jobs:queue", "jobs:processing:" + workerId,
                         LMoveArgs.Builder.leftRight());
try {
    handle(job);
    redis.lrem("jobs:processing:" + workerId, 1, job);   // ack: remove
} catch (Exception e) {
    // leave it in processing; a reaper LMOVEs stale items back to jobs:queue
}
```

**Approach 2 — Redis Streams consumer groups** (preferred for new systems): `XREADGROUP` delivers to a consumer and records it in the Pending Entries List; the worker `XACK`s on success. A separate process uses `XPENDING`/`XCLAIM` to reassign messages stuck longer than a threshold. This gives at-least-once delivery, observability of in-flight work, and replay — without hand-rolling the processing list.

**Time/Space:** O(1) per op for both. **Edge cases:** at-least-once means handlers must be **idempotent** (dedup by a job id); a crashed worker's processing list/PEL needs a reaper with a visibility timeout; poison messages need a dead-letter list/stream after N retries.

### Q23. [Practical] How do you achieve cache consistency between Redis and a SQL database?

Perfect consistency between a cache and a system of record is impossible without coordination, so we choose a "good enough" strategy and bound staleness. The most robust common pattern is **cache-aside with invalidation**: on write, update the DB then **delete** the cache key (not update it) — deletion is idempotent and avoids the lost-update race where two concurrent writers set the cache in the wrong order. Even delete-after-write has a narrow race (a reader repopulates between DB write and cache delete); mitigations include short TTLs as a backstop, **double-delete** (delete now, delete again after a short delay), or a **CDC pipeline** (Debezium reading the DB binlog → invalidating Redis) which makes the DB the single source of truth and removes app-level races. For read-heavy systems that can tolerate seconds of staleness, TTL-based expiry alone is often the pragmatic choice. Always answer with the explicit staleness budget the business can tolerate — that decides the design.

```
Write path (cache-aside + invalidate):
  1. UPDATE db ...           (source of truth)
  2. DEL cache:key           (next read repopulates)
  Backstop: short TTL + optional delayed second DEL to close the read race
```

---

## 🔴 Expert (15+ yrs)

### Q24. [Theory] When is Redis the *wrong* choice?

Reaching for Redis reflexively is a common architectural mistake. It's the wrong tool when: (1) **the working set vastly exceeds affordable RAM** — Redis on disk (the old Redis-on-Flash / RDB-backed tiering aside) is far less cost-effective than a disk-native store; (2) **you need rich queries / secondary indexes / joins / ad-hoc analytics** — that's a SQL or document database (Redis modules like RediSearch help but it's not a general query engine); (3) **strong consistency / linearizability / multi-key ACID across shards** — Redis replication is async and Cluster has no cross-slot transactions, so use a database with real transactions or a consensus store; (4) **durable, high-throughput, long-retention event streaming with strong partition ordering and replay across consumer groups for days** — Kafka or Pulsar fit better than Redis Streams beyond a point; (5) **as the only system of record for critical financial data** — even with AOF `always` you can lose the in-flight window on failover. Redis excels at *fast, ephemeral or reconstructable state*: caching, sessions, rate limiting, leaderboards, queues, real-time counters, ephemeral pub/sub. The senior judgment is matching the durability/consistency/query needs to the right store rather than overloading Redis.

### Q25. [Theory] The single-threaded model — when does it bite, and what changed in Redis 6+?

Redis's command execution is single-threaded, which is a feature: atomic operations with no locks. But it means **one slow command stalls everyone** — an `O(N)` `KEYS *`, a `FLUSHALL` on a huge DB, a big-key `DEL`, or a heavy Lua script blocks the event loop and spikes p99 latency for all clients. The thread is also a **single-core ceiling** for command throughput; you scale CPU by sharding (Cluster) or running multiple instances. Redis 6 added **threaded I/O** — reading requests and writing replies are offloaded to I/O threads (`io-threads`), but *command execution remains single-threaded*, so it helps network-bound throughput, not CPU-bound command cost. Mitigations for the blocking trap: use `SCAN` not `KEYS`, `UNLINK` not `DEL` (async reclaim), `lazyfree-lazy-*` settings, avoid unbounded collection ops, and cap Lua execution time (`lua-time-limit` / `busy-reply-threshold`). Note the 2024 license change spawned **Valkey** (Linux Foundation fork), which is pursuing more aggressive multi-threading; the single-threaded command model is the historical baseline you must reason about.

### Q26. [Practical] Design a globally distributed caching layer for a read-heavy service across 3 regions.

I'd layer caches and be explicit about consistency boundaries:

```
                  ┌──────────── per-region ────────────┐
 client ─► CDN/edge ─► app L1 (in-proc Caffeine) ─► Redis L2 (regional cluster) ─► DB (global)
                                   │                         │
                                   └── on write: invalidate ──┘ (pub/sub or CDC fan-out)
```

- **L1 in-process cache (Caffeine)** absorbs the hottest keys with microsecond latency and shields Redis; bound it and use short TTLs to limit staleness across nodes.
- **L2 regional Redis Cluster** per region for shared cache; reads stay local (low latency), avoiding cross-region RTT on the hot path.
- **Writes** go to a global primary DB (or per-region with conflict resolution if multi-master). Invalidation propagates across regions via a **stream/Kafka topic or CDC**, and within a region via Pub/Sub to drop L1 entries.
- **Consistency:** accept eventual consistency with a defined staleness budget (e.g. <5s). Use versioned keys for cache-busting and jittered TTLs to prevent avalanche.
- **Failure isolation:** a region's Redis outage degrades to direct DB reads with a circuit breaker, not a global outage. Active-active Redis (CRDT-based, Redis Enterprise) is an option if you truly need low-latency local writes, but its convergence semantics must be acceptable to the domain.

The hard part isn't Redis — it's the invalidation fan-out and being honest about the staleness the product can tolerate per data class (a price vs a "last seen" timestamp have very different budgets).

### Q27. [Theory] Security hardening for a production Redis deployment.

Redis was historically designed to run in a trusted network, so defaults are unsafe and several real breaches stem from exposed instances. The hardening checklist: (1) **never expose Redis to the public internet** — bind to private interfaces, use security groups/firewalls; an open 6379 with no auth has been mass-exploited (attackers write SSH keys or cron jobs via `CONFIG SET dir`). (2) **Enable authentication** — `requirepass`, and in Redis 6+ use **ACLs** for least-privilege per-user command/key restrictions instead of one shared password. (3) **TLS** for in-transit encryption (Redis 6+ native TLS). (4) **Rename or disable dangerous commands** (`FLUSHALL`, `CONFIG`, `KEYS`, `DEBUG`) via ACL or `rename-command`. (5) **Run as non-root**, enable **protected-mode**. (6) Beware **Lua sandbox** and **module** risks; vet third-party modules. (7) For multi-tenant, isolate by ACL + key namespacing, not just DB numbers (which Cluster doesn't support anyway). Encryption-at-rest is handled at the volume/managed-service layer since RDB/AOF are plaintext.

### Q28. [Behavioral] Tell me about a time you introduced (or removed) Redis and the trade-offs you weighed.

Use a STAR structure. **Situation/Task:** e.g. "Our product API p99 was 800ms because every request recomputed a personalization payload from three SQL joins; traffic was read-heavy and tolerant of ~30s staleness." **Action:** "I introduced cache-aside in Redis keyed by user+segment with jittered 60s TTLs, added single-flight locking to prevent stampede on cache misses during deploys, and instrumented hit ratio and p99. I deliberately *didn't* make Redis a system of record — the DB stayed authoritative — and I set `allkeys-lru` with capacity headroom plus alerting on eviction rate." **Result:** "p99 dropped to ~90ms, DB CPU fell 60%, and a deploy-time stampede incident was prevented by the lock. The cost was a new staleness contract I socialized with product, and added operational surface (a Redis cluster to monitor and fail over)." A strong answer also includes a *removal* story: "Elsewhere we'd cached data with strict consistency needs and chased invalidation bugs for a quarter; we removed Redis there and used a Postgres materialized view, accepting slightly higher latency for correctness." The signal interviewers want: you optimize for the *system's* constraints, name the trade-offs explicitly, and don't treat Redis as a default.

### Q29. [Practical] How would you migrate a 200 GB single-instance Redis to Cluster with near-zero downtime?

I'd plan it as a phased, reversible migration. **Pre-work:** audit for multi-key operations and Lua scripts that assume single-slot atomicity — these break in Cluster unless keys share a hash tag, so refactor them first (this is usually the biggest effort). Confirm no `SELECT`/multi-DB usage (Cluster supports only DB 0). **Provision** the target cluster (e.g. 6 nodes: 3 primaries + 3 replicas) sized for 200 GB plus headroom and fork COW. **Migrate data** with one of: (a) `redis-cli --cluster import` / slot migration tooling, (b) a managed-service online migration (ElastiCache/Redis Enterprise replication-based), or (c) dual-write from the app to old+new while backfilling via `MIGRATE`/`DUMP`/`RESTORE`, then cut reads over. **Cutover:** use a feature flag to flip clients to the cluster endpoint, monitor hit ratio and error rate, and keep the old instance as a rollback for a bake period. **Validation:** compare key counts and spot-check; watch for `CROSSSLOT` errors indicating an un-refactored multi-key op. **Risks:** the refactor of multi-key logic is where projects stall; hash-tag design must be done carefully to avoid hot slots (e.g. tagging everything `{global}` re-centralizes load). I'd rehearse the cutover and rollback in staging with production-shaped data before touching prod.

### Q30. [Theory] Compare Redis Streams with Kafka for an event-driven architecture.

Both are append-only logs with consumer groups, but they sit at different scale/operational points. **Redis Streams**: low operational overhead (often you already run Redis), microsecond-to-millisecond latency, per-message acks via PEL, capped retention (`MAXLEN`) since data lives in RAM, ordering within a single stream, and scaling by sharding streams across keys/cluster. It shines for moderate-throughput, low-latency, short-retention work queues and real-time pipelines. **Kafka**: built for very high sustained throughput, long/durable retention on disk (days–months), strong per-partition ordering, log compaction, mature ecosystem (Connect, Streams, Schema Registry), and replayability as a first-class design goal; the cost is operational complexity and higher baseline latency. **Heuristic:** if your events fit in memory, you want simplicity, and retention is short → Streams. If you need durable, high-volume, replayable event backbone with many independent consumers and ordering guarantees across a partitioned topic → Kafka. A common architecture uses Redis Streams as a fast intra-service buffer/queue and Kafka as the cross-service durable backbone.

```
Redis Streams                         Kafka
  in-memory log (+AOF)                on-disk segmented log
  RAM-bound retention                 long retention, compaction
  µs–ms latency, simple ops           high throughput, richer ecosystem
  great as a queue/buffer             great as durable event backbone
```

---

## 🧩 Extended Questions — Set 1: Deeper Theory & Practical Operations

### 🟢 Basic — extended

#### Q31. [Theory] What is the RESP protocol and why does it matter?

RESP (REdis Serialization Protocol) is the wire format clients and servers use to talk over TCP. It is intentionally simple and human-readable: the first byte of every reply indicates the type — `+` for simple strings, `-` for errors, `:` for integers, `$` for bulk strings (length-prefixed binary-safe data), and `*` for arrays. A command like `SET foo bar` is sent as an array of bulk strings. RESP2 was the long-standing version; **RESP3** (Redis 6+) adds richer types — maps, sets, doubles, booleans, big numbers, and **push messages** (out-of-band data used for client-side caching invalidation and pub/sub) — letting the server return typed structures instead of flat arrays the client must interpret.

```
Client sends "SET foo bar":         Server replies:
  *3\r\n                              +OK\r\n
  $3\r\nSET\r\n
  $3\r\nfoo\r\n
  $3\r\nbar\r\n
```

Why it matters in interviews: RESP's length-prefixed, binary-safe framing is part of why Redis parsing is so cheap (no escaping, no ambiguous delimiters), and understanding it explains how **pipelining** works — you simply write multiple command frames back-to-back and read the replies in order. It also explains binary safety: keys and values are byte strings, so you can store images or serialized protobuf without encoding tricks. When debugging, `redis-cli` translates RESP for you, but a raw `nc`/`telnet` session speaking RESP is a classic way to verify what a client library is actually sending.

#### Q32. [Practical] How do you safely iterate over all keys in production without blocking?

The wrong answer is `KEYS pattern`, which scans the entire keyspace in one synchronous O(N) sweep and blocks the single-threaded server — on a multi-million-key instance this freezes every other client for seconds. The correct tool is **`SCAN`**, a cursor-based iterator that returns a small batch of keys plus a cursor to resume from; you loop until the cursor returns to `0`. `SCAN` guarantees that keys present for the entire iteration are returned at least once, but it may return duplicates and does not snapshot — concurrent additions/deletions may or may not appear. There are type-specific variants: `HSCAN`, `SSCAN`, `ZSCAN` for iterating large hashes/sets/sorted-sets without `HGETALL`/`SMEMBERS` (which are themselves O(N) blocking traps).

```bash
# Cursor-based, non-blocking iteration (COUNT is a hint, not a hard limit)
redis-cli --scan --pattern 'session:*' --count 500 | while read key; do
  ttl=$(redis-cli ttl "$key")
  echo "$key ttl=$ttl"
done
```

In code you keep calling `SCAN <cursor> MATCH <pattern> COUNT <n>` and stop when the returned cursor is `0`. The `COUNT` parameter tunes work-per-call (bigger = fewer round trips but longer per-call pauses). Because `MATCH` filters *after* fetching the batch, a highly selective pattern over a huge keyspace still iterates everything — for genuinely frequent lookups, maintain a secondary index (a Set or Sorted Set of relevant keys) rather than scanning. The senior takeaway: `KEYS`/`HGETALL`/`SMEMBERS`/`SMEMBERS` on large collections belong only in one-off offline scripts on a replica, never on the hot path.

#### Q33. [Theory] What is the difference between `DEL` and `UNLINK`, and `FLUSHALL ASYNC`?

`DEL` removes keys and reclaims their memory **synchronously**, on the single command-execution thread. For a small key this is trivially fast, but deleting a key holding a huge collection (a list/set/hash with millions of elements) means freeing millions of allocations inline — that can block the server for tens or hundreds of milliseconds, spiking p99 for every other client. `UNLINK` (Redis 4+) instead unlinks the key from the keyspace immediately (so it's invisible to clients at once) and hands the actual memory reclamation to a **background thread**, keeping the main thread responsive.

```bash
redis-cli UNLINK big:set         # O(1) on main thread; frees memory in background
redis-cli FLUSHALL ASYNC         # clears all DBs without blocking on free
redis-cli CONFIG SET lazyfree-lazy-user-del yes   # make plain DEL behave like UNLINK
```

The same principle generalizes: `FLUSHALL ASYNC` / `FLUSHDB ASYNC` clear data without a synchronous free, and the `lazyfree-lazy-*` family of config flags (`lazyfree-lazy-eviction`, `lazyfree-lazy-expire`, `lazyfree-lazy-server-del`, `lazyfree-lazy-user-del`) make eviction, expiry, side-effect deletes, and user `DEL`s lazy as well. The trade-off is that lazy freeing defers — but does not eliminate — the CPU work, and memory is reclaimed slightly later, so under extreme churn the background queue can lag. For interactive production systems the default recommendation is to enable lazy-free and prefer `UNLINK`, because predictable latency almost always beats marginally faster memory reclamation.

### 🟡 Intermediate — extended

#### Q34. [Theory] Explain Redis's internal encodings (listpack, intset, skiplist) and why they matter.

Redis transparently switches the *internal representation* of a value based on its size and contents, trading memory for speed. A small hash, list, or sorted set is stored as a **listpack** (formerly ziplist) — a single compact, cache-friendly contiguous blob — while a larger one is "upgraded" to a full structure (hashtable, quicklist, or skiplist+hashtable). A set of only integers uses a sorted **intset**; add a non-integer member or exceed the threshold and it converts to a hashtable (or listpack for small string sets). You can inspect the current encoding with `OBJECT ENCODING key`.

```bash
redis-cli RPUSH mylist a b c
redis-cli OBJECT ENCODING mylist        # "listpack" (small)
redis-cli CONFIG GET list-max-listpack-size
redis-cli CONFIG GET hash-max-listpack-entries hash-max-listpack-value
redis-cli CONFIG GET zset-max-listpack-entries set-max-intset-entries
```

The thresholds (`hash-max-listpack-entries`/`-value`, `zset-max-listpack-entries`/`-value`, `set-max-intset-entries`, `list-max-listpack-size`) decide the cut-over point. This matters because memory footprint can differ by an order of magnitude: a hash with 100 small fields stored as a listpack uses far less RAM than 100 separate keys, which is the basis of the classic "store many small objects under one hash to save memory" optimization. The trade-off: listpack operations are O(N) within the structure (you scan the blob), so once a collection grows large you *want* the conversion to the O(1)/O(log N) structure. Tuning these thresholds upward saves memory but can degrade latency if collections stay large in listpack form — measure before changing the defaults.

#### Q35. [Practical] How do you find and fix a "hot key" problem?

A hot key is a single key receiving a disproportionate share of traffic — a celebrity user's profile, a global config blob, a trending product. Because Redis serves it from one thread on one shard, a hot key can saturate a single core or a single Cluster node while the rest sit idle, and no amount of sharding helps if everything maps to one slot. Detection: `redis-cli --hotkeys` (uses LFU sampling and requires an LFU maxmemory-policy), the `MONITOR` command for short bursts (expensive — never leave it running), `OBJECT FREQ key` under LFU, or external sampling via `redis-cli --stat` and per-key metrics from your client library.

```bash
redis-cli CONFIG SET maxmemory-policy allkeys-lfu
redis-cli --hotkeys                      # samples and reports the busiest keys
redis-cli OBJECT FREQ product:trending   # access-frequency counter (LFU only)
```

Mitigations depend on the read/write mix. For a read-hot key: add an **L1 in-process cache** (Caffeine) in front of Redis so most reads never reach it; or **replicate the value across N suffixed keys** (`config:0`..`config:9`) and have clients pick one at random, spreading load across slots/cores. For a write-hot counter, **shard the counter** into N parts and sum on read (`INCR counter:{shard}` then aggregate), avoiding contention on one key. In Cluster, beware hash-tag design that forces unrelated hot keys into one slot. The judgment call: client-side caching is the cheapest fix for read-hot immutable-ish data, while counter sharding is the standard answer for write-hot aggregates.

#### Q36. [Theory] What are keyspace notifications and what are they good for?

Keyspace notifications let clients subscribe (via Pub/Sub) to events about changes to keys — for example "key X was set", "key Y expired", "a member was added to set Z". They are disabled by default and enabled with the `notify-keyspace-events` config, whose value is a set of flags selecting which event classes to emit (e.g. `K` keyspace events, `E` keyevent events, `x` expired, `g` generic, `$` string commands, `A` all). Events are published on channels like `__keyspace@0__:<key>` (what happened to this key) and `__keyevent@0__:expired` (which key fired this event).

```bash
redis-cli CONFIG SET notify-keyspace-events Ex     # expired key events
redis-cli PSUBSCRIBE '__keyevent@0__:expired'      # receive expirations
# A separate client: SET temp foo EX 1  -> after ~1s you get "temp"
```

A common pattern is the **expired-key callback**: set a key with a TTL, and when it expires you receive a notification and react (e.g. close an idle session, fire a delayed job). The critical caveats: notifications are delivered over **Pub/Sub**, so they inherit its fire-and-forget weakness — if no subscriber is connected when the event fires, it is lost (no replay), and a slow subscriber drops messages. Also, the "expired" event fires when Redis actually *removes* the key (lazily on access or via the active cycle), not exactly at the TTL boundary, so timing is approximate. For reliable delayed-job semantics you should use a Sorted Set of due-timestamps polled by a worker, or Streams — keyspace notifications are a convenience signal, not a durable event source.

#### Q37. [Practical] Walk through tuning a `redis.conf` for a cache vs a datastore.

The configuration diverges sharply by role. For a **pure cache** you optimize for "lose data freely, never block writes": disable or minimize persistence, set a `maxmemory` ceiling, and pick an eviction policy so the instance self-trims instead of erroring.

```bash
# --- Cache profile ---
maxmemory 8gb
maxmemory-policy allkeys-lru          # or allkeys-lfu for skewed access
save ""                                # disable RDB snapshots
appendonly no                          # no AOF; data is reconstructable
lazyfree-lazy-eviction yes             # don't block on freeing evicted keys
```

For a **datastore** you optimize for durability and bounded data loss: enable AOF (and usually RDB too), choose `noeviction` so you never silently drop committed data, and tune the rewrite/fsync cadence.

```bash
# --- Datastore profile ---
appendonly yes
appendfsync everysec                   # <=1s data loss window
auto-aof-rewrite-percentage 100        # rewrite when AOF doubles
save 900 1 / 300 10 / 60 10000         # RDB snapshot triggers
maxmemory-policy noeviction            # never silently drop data
maxmemory 0                            # or a ceiling with alerting, not eviction
```

Cross-cutting settings matter for both: `tcp-keepalive` and `timeout` to reap dead connections, `maxmemory-samples` to trade eviction precision for CPU, `io-threads` for network-bound throughput on multi-core hosts, `requirepass`/ACLs and `bind`/`protected-mode` for security, and `lua-time-limit`/`busy-reply-threshold` to bound runaway scripts. The biggest mistake I see is running a *datastore* with cache-style eviction (`allkeys-lru`), which silently deletes committed data, or running a *cache* with `noeviction` and no TTLs, which causes `OOM` write failures. Decide the role first; the config follows from it.

#### Q38. [Coding] Implement a token-bucket rate limiter with low memory footprint.

**Problem:** The sliding-window sorted-set limiter (Q10) stores one member per request, which is memory-heavy for high-rate users. A token bucket stores only two numbers per user — the current token count and the last-refill timestamp — and supports smooth bursting up to the bucket capacity.

The bucket refills at a steady rate; each request consumes a token if available. All logic must be atomic, so it lives in a Lua script that reads both fields, computes the refill since the last access, and conditionally decrements.

```lua
-- KEYS[1]=bucket key  ARGV: now_ms, rate_per_sec, capacity, requested
local data = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(data[1])
local ts     = tonumber(data[2])
local now    = tonumber(ARGV[1])
local rate   = tonumber(ARGV[2])
local cap    = tonumber(ARGV[3])
local need   = tonumber(ARGV[4])
if tokens == nil then tokens = cap; ts = now end
-- refill based on elapsed time
local elapsed = math.max(0, now - ts) / 1000.0
tokens = math.min(cap, tokens + elapsed * rate)
local allowed = 0
if tokens >= need then tokens = tokens - need; allowed = 1 end
redis.call('HSET', KEYS[1], 'tokens', tokens, 'ts', now)
redis.call('PEXPIRE', KEYS[1], math.ceil(cap / rate * 1000))
return allowed
```

```java
Long ok = redis.eval(BUCKET_LUA, ScriptOutputType.INTEGER,
        new String[]{ "rl:" + user },
        String.valueOf(System.currentTimeMillis()), "10", "100", "1");
boolean allowed = ok == 1L;
```

**Time/Space:** O(1) time and **O(1) memory per user** (two fields), versus O(requests-in-window) for the sorted-set approach — a major win at high request rates. **Edge cases:** use a single source of time to avoid skew (pass `now` from the server via `redis.call('TIME')` inside the script for multi-app-server safety); set the key TTL to the full-refill duration so idle users' buckets self-clean; and pick capacity vs rate to allow the burst size the API contract permits. The trade-off vs sliding-window: token bucket allows short bursts up to `capacity`, which is usually desirable but is *not* a strict "N per rolling window" guarantee.

#### Q39. [Theory] How does `fork()` and copy-on-write affect Redis memory during persistence?

When Redis takes an RDB snapshot or rewrites the AOF, it calls `fork()` to create a child process that writes the dataset to disk while the parent keeps serving traffic. Thanks to **copy-on-write (COW)**, the child initially shares all memory pages with the parent — no data is copied at fork time, which is why fork is fast even for large datasets. But every time the parent *modifies* a page (because clients keep writing), the OS duplicates that page so parent and child diverge. Under a heavy write load during the snapshot, many pages get copied, and memory usage can balloon toward 2× the dataset size in the worst case.

```
fork():   parent ──shared pages──► child (writes RDB)
          parent writes key  ──►  OS copies that page (COW)
          more writes during snapshot ──► more copied pages ──► higher RSS
```

This drives several operational rules. First, **size hosts with headroom** — never run Redis at 90%+ of RAM if you persist, or the COW expansion plus the snapshot can trigger the OOM killer. Second, watch `latest_fork_usec` in `INFO` — on huge datasets the fork itself (copying page tables) can take hundreds of milliseconds and is a visible latency spike. Third, **disable transparent huge pages (THP)** at the OS level; THP makes COW copy 2 MB pages instead of 4 KB, dramatically amplifying copy amplification and latency (Redis logs a warning at startup if THP is enabled). Fourth, `maxmemory` accounts only for dataset memory, not the fork's COW overhead — a frequent surprise when an instance configured at the RAM limit OOMs during a background save. Managed services tune these for you, but on self-managed Redis, THP and host headroom are the two most common causes of fork-related incidents.

### 🟠 Advanced — extended

#### Q40. [Theory] Explain client-side caching (the tracking/invalidation feature) in Redis 6+.

Client-side caching keeps a copy of frequently-read values in the *application's* memory, eliminating the network round trip entirely for cache hits — but the hard problem is knowing when the local copy is stale. Redis 6 solves this with **server-assisted invalidation (the `CLIENT TRACKING` feature)**: the server remembers which keys each connection has read and sends an **invalidation push message** (over RESP3, or a separate Pub/Sub connection in RESP2) when any of those keys changes, so the client evicts its local copy. This turns the app into a coherent L1 cache without polling or guessing TTLs.

```bash
redis-cli CLIENT TRACKING ON                  # default mode: track read keys
# Broadcasting mode: subscribe to key prefixes instead of per-key tracking
redis-cli CLIENT TRACKING ON BCAST PREFIX user:
```

There are two modes with different memory/precision trade-offs. **Default mode** tracks the exact keys each client read, in a server-side table (the *invalidation table*); it is precise but costs server memory proportional to tracked keys, and uses an **OPTIN/OPTOUT** caveat to limit what's tracked. **Broadcasting mode (`BCAST`)** doesn't track per-key — clients register interest in key *prefixes* and the server broadcasts any change under those prefixes; it uses no per-key server memory but sends more invalidations (false positives for keys the client never cached). Lettuce and other clients expose this as a near-drop-in local cache layer. The senior framing: client-side caching gives you the latency of an in-process cache with bounded staleness (you're invalidated, not TTL-guessing), at the cost of server-side tracking state and extra invalidation traffic — ideal for read-heavy, low-write-rate keys, and a poor fit for write-churny data where you'd be invalidating constantly.

#### Q41. [Practical] How do you do a zero-downtime backup and restore of a Redis instance?

For a self-managed instance, the canonical backup is the **RDB file**. You trigger a non-blocking background snapshot with `BGSAVE` (it forks, as in Q39, so the server keeps serving), then copy the resulting `dump.rdb` from the configured `dir`. `BGSAVE` is point-in-time consistent because the child process serializes the dataset as it existed at fork time. Avoid `SAVE` (no "BG") in production — it snapshots synchronously and blocks the server for the entire dump.

```bash
redis-cli BGSAVE
# poll until done:
redis-cli INFO persistence | grep rdb_bgsave_in_progress   # 0 when finished
redis-cli LASTSAVE                                          # unix ts of last save
cp /var/lib/redis/dump.rdb /backups/dump-$(date +%F).rdb    # then ship offsite
```

Restore is "load an RDB on startup": stop Redis, place the `dump.rdb` in `dir`, and start — Redis loads it (or the AOF if `appendonly yes`, since AOF takes precedence). For migrating a running dataset without downtime, alternatives are **replication** (point a new instance at the source with `REPLICAOF`, let it sync, then promote) or per-key **`DUMP`/`RESTORE`** combined with `MIGRATE` for selective moves. Operational must-dos: take backups from a **replica**, not the primary, so the fork overhead doesn't hit production traffic; verify backups by actually restoring them into a scratch instance (an untested backup is not a backup); and remember the RDB is a **plaintext binary** — encrypt it at rest and in transit. On managed services (ElastiCache, Redis Enterprise) automated snapshots and point-in-time restore handle most of this, but the underlying mechanism is the same RDB fork.

#### Q42. [Theory] What is the difference between Lua scripting (EVAL) and Redis Functions (FUNCTION)?

Both run user code server-side, atomically, on the single thread. **`EVAL`/`EVALSHA`** (Redis 2.6+) executes an ad-hoc Lua script the client ships with each call; `SCRIPT LOAD` caches it by SHA so subsequent calls send only the hash (`EVALSHA`). Scripts are **ephemeral** — they live in a per-server script cache that is not replicated as code and is lost on restart/`SCRIPT FLUSH`, so the client must always be able to re-send the source, and every service that needs the logic must embed the script text. This leads to scripts scattered across application code with no first-class management.

```bash
# Redis Functions: register a named library, then call it by function name
redis-cli FUNCTION LOAD "#!lua name=mylib
redis.register_function('myfunc', function(keys, args)
  return redis.call('INCRBY', keys[1], args[1])
end)"
redis-cli FCALL myfunc 1 counter 5      # call the registered function
redis-cli FUNCTION LIST                  # enumerate libraries/functions
```

**Redis Functions** (Redis 7+) make server-side logic a **first-class, persistent, named** artifact: you `FUNCTION LOAD` a *library* of named functions once; the library is persisted in RDB/AOF and **replicated to replicas**, survives restarts, and is invoked with `FCALL`. This separates the deployment of logic (admin loads the library) from its invocation (apps just call `FCALL myfunc`), enabling versioning, shared libraries across services, and cleaner upgrades. Both share the same execution constraints: they block the server while running (keep them fast, respect `lua-time-limit`/`busy-reply-threshold`), must declare key access for Cluster correctness, and should be deterministic for safe replication. The practical recommendation for new systems is Functions for any reusable server-side logic, reserving `EVAL` for one-off or dynamically-generated scripts.

#### Q43. [Practical] Your Redis latency p99 spiked but throughput is normal. How do you find the cause?

A p99 spike with normal throughput points to *intermittent* stalls rather than overload, and the single-threaded model means anything that briefly monopolizes the thread shows up as tail latency for everyone. I start with Redis's built-in latency tooling, which records the worst spikes per event source.

```bash
redis-cli LATENCY RESET
redis-cli --latency-history -i 5         # rolling min/avg/max from the client side
redis-cli LATENCY LATEST                 # worst spike per event (fork, expire, ...)
redis-cli LATENCY DOCTOR                 # human-readable diagnosis + advice
redis-cli SLOWLOG GET 20                 # commands exceeding slowlog-log-slower-than
redis-cli INFO persistence | grep -E 'fork|aof_rewrite|rdb_last'
```

The usual suspects, in rough order: (1) **fork pauses** from RDB `BGSAVE`/AOF rewrite — correlate spikes with `latest_fork_usec` and rewrite timestamps; fix by scheduling saves off-peak, ensuring host headroom, and disabling THP (Q39). (2) **Big-key operations** — a periodic `DEL`/`HGETALL`/`SMEMBERS` on a large collection; `SLOWLOG` names the command, fix with `UNLINK`/`SCAN`/pagination. (3) **Synchronous AOF fsync** — `appendfsync always` or disk stalls; check `aof_delayed_fsync` and disk I/O. (4) **Expiration storms** — many keys expiring at once make the active-expire cycle do bursts of deletes; `LATENCY LATEST` flags an `expire-cycle` event; jitter TTLs. (5) **Network/client** — `--intrinsic-latency` on the host isolates whether the spike is even Redis or the OS scheduler/VM steal. The mental model: throughput-normal-but-tail-spiking almost always means a recurring O(N) or blocking event injected into the single-threaded loop — find which event source `LATENCY LATEST` blames and trace it back to its trigger.

#### Q44. [Coding] Implement an idempotency / exactly-once API key for safe request retries.

**Problem:** Clients retry requests on timeouts; a payment or order-creation endpoint must not execute twice for the same logical request. The standard solution is an **idempotency key** supplied by the client and recorded in Redis so a replay returns the stored result instead of re-executing.

The critical atomic step is "claim the key if and only if it's new." `SET key value NX` does exactly that in one round trip — the first request wins the claim and proceeds; concurrent retries see the key already set.

```java
public Response handle(String idemKey, Request req) {
    String k = "idem:" + idemKey;
    // Atomically claim: succeeds only if not present, with a TTL backstop
    boolean firstTime = redis.set(k, "IN_PROGRESS",
            SetArgs.Builder.nx().ex(86400)) != null;
    if (!firstTime) {
        String stored = redis.get(k);
        if ("IN_PROGRESS".equals(stored)) {
            throw new ConflictException("retry in flight, try again shortly");
        }
        return deserialize(stored);          // replay: return the prior result
    }
    Response resp = doWork(req);             // execute the real side effect ONCE
    redis.set(k, serialize(resp), SetArgs.Builder.keepttl());  // store result
    return resp;
}
```

**Time/Space:** O(1) per request; one key per distinct idempotency key, auto-expiring after the retention window. **Edge cases:** the `IN_PROGRESS` marker handles the window between claiming the key and finishing the work — a concurrent retry gets a clear "in flight" signal rather than executing again or seeing an empty result. If `doWork` can crash *after* the side effect but *before* storing the result, you need the side effect itself to be idempotent (e.g. the payment gateway also keyed by the same id) — Redis alone can't make a non-idempotent downstream exactly-once. Choose the TTL to exceed the client's maximum retry horizon, and store enough to faithfully replay the original response (status + body), since the client expects the same answer.

### 🔴 Expert — extended

#### Q45. [Theory] How would you decide between scaling Redis with read replicas vs Cluster sharding?

These solve different bottlenecks, and the right choice follows from *which* resource you're exhausting. **Read replicas** (one primary, N replicas) scale **read throughput** and provide HA: you fan reads out to replicas and keep writes on the primary. They help when you are read-bound and your dataset fits comfortably in one node's RAM. They do **not** scale writes (all writes still funnel through one primary core), do **not** increase total capacity (each replica holds the full dataset), and introduce **replication lag** — reads from a replica can be stale, so they're unsafe for read-your-writes without routing to the primary or using `WAIT`.

```
Read replicas (scale reads):        Cluster (scale writes + capacity):
   writes ─► P ─► R1, R2, R3            data split across P1,P2,P3
   reads  ─► R1/R2/R3 (may lag)         each shard: own primary core + replicas
   dataset duplicated on every node     dataset partitioned by hash slot
```

**Cluster sharding** scales **write throughput and total memory** by partitioning the keyspace across multiple primaries, each on its own core. You move to Cluster when (a) the dataset exceeds one node's affordable RAM, or (b) write volume saturates a single core. The cost is the constraints covered earlier: no cross-slot atomicity/transactions, multi-key ops need hash tags, smart-client routing, and resharding complexity. In practice the decision tree is: read-bound + fits in RAM → add replicas (simplest); write-bound or too big for one node → Cluster; both → Cluster with replicas per shard. A frequent anti-pattern is adding replicas to "scale" a write-bound workload — it does nothing for the actual bottleneck and adds replication-lag staleness on top.

#### Q46. [Practical] How do you set up Redis ACLs for a least-privilege multi-service deployment?

Before Redis 6 everyone shared a single `requirepass`, meaning every service had full admin rights and one leaked password compromised everything. **ACLs** (Redis 6+) introduce named users with fine-grained permissions over which commands and key patterns each may touch, plus Pub/Sub channel restrictions. The model is: each user has rules built from `+`/`-` command grants, `~pattern` key grants, `&pattern` channel grants, and a password. The default `default` user should be locked down or disabled in production.

```bash
# A cache service: read/write only keys under its namespace, no admin commands
redis-cli ACL SETUSER cachesvc on '>S3cret!' \
  '~app:cache:*' '+@read' '+@write' '-@dangerous' '-flushall' '-flushdb'

# A read-only analytics consumer: read keys, run SCAN, nothing else
redis-cli ACL SETUSER analytics on '>An0ther!' \
  '~app:*' '+@read' '+scan' '-@write' '-@admin'

redis-cli ACL WHOAMI                 # who am I authenticated as
redis-cli ACL LIST                   # current rules
redis-cli ACL GETUSER cachesvc       # inspect one user's grants
redis-cli ACL CAT                    # list command categories (@read, @write, ...)
```

The big wins: **command categories** (`@read`, `@write`, `@dangerous`, `@admin`, `@keyspace`) let you grant by intent rather than enumerating commands, and **key/channel patterns** enforce namespace isolation so a compromised service can't read another's data — far stronger than separate DB numbers, which Cluster doesn't support anyway. Operationally, define ACLs in an external `aclfile` (loaded with `aclfile /etc/redis/users.acl` and reloadable via `ACL LOAD`) so they're version-controlled rather than ephemeral runtime state, layer TLS for in-transit protection, and rotate passwords via `ACL SETUSER`. The senior point: ACLs turn Redis from an all-or-nothing trust boundary into a least-privilege one, which is essential for multi-tenant or multi-service clusters and increasingly an audit/compliance requirement.

#### Q47. [Practical] How do you upgrade a production Redis cluster with no downtime, and how do you approach a Valkey migration?

A Redis major-version upgrade on a replicated/clustered topology uses **rolling replacement** to keep the service available. The core technique exploits replication and failover: upgrade replicas first, then promote an upgraded replica and upgrade the old primary.

```
1. Verify RDB/AOF compatibility between old and new versions (read release notes).
2. Upgrade a REPLICA: stop it, install new binary, restart, let it resync.
3. Repeat for all replicas; confirm they're healthy and caught up.
4. Trigger a manual failover (CLUSTER FAILOVER on a replica / Sentinel failover)
   so an UPGRADED replica becomes primary.
5. Upgrade the now-demoted old primary; it rejoins as a replica.
6. Repeat per shard. Roll back by failing back if metrics degrade.
```

The pre-flight checks matter most: confirm RDB/AOF file format compatibility (Redis generally reads older formats, but cross-check the release notes), validate that no removed/changed commands or config keys are in use, and rehearse in staging with production-shaped data. Watch replication offset, `cluster_state`, error rates, and client reconnection behavior during each failover.

For a **Valkey migration** (the Linux Foundation fork created after the 2024 license change), the good news is Valkey 7.2 is a drop-in fork of Redis 7.2 — same RESP protocol, same RDB/AOF formats, same commands and client libraries — so the migration is mechanically similar to a version upgrade: stand up Valkey nodes, replicate from the existing Redis (Valkey can act as a replica of a compatible Redis primary), then fail over. The real work is non-technical: validating that any **Redis-specific modules** you depend on (RediSearch, RedisJSON, RedisBloom, Redis vector sets) have equivalents — Valkey's module ecosystem diverges and some Redis Stack modules are not BSD-licensed, so you may need Valkey-native alternatives or to stay on Redis for those features. Decide based on licensing requirements, the modules you actually use, and which roadmap (Valkey's multi-threading vs Redis's feature direction) aligns with your needs; the data-plane migration itself is low-risk because of format compatibility.

#### Q48. [Theory] How do RedisBloom, RediSearch, and Redis vector sets extend Redis beyond key-value?

Plain Redis is a data-structure store, but modules turn it into a multi-model platform. **RedisBloom** adds **probabilistic data structures**: Bloom and Cuckoo filters (membership tests with a tiny memory footprint and a tunable false-positive rate, no false negatives), Count-Min Sketch (approximate frequency counts), Top-K, and t-digest (quantile estimation). These trade exactness for orders-of-magnitude memory savings — a Bloom filter answering "have we seen this key?" in a few bits per element is the canonical defense against cache *penetration* (Q13), rejecting nonexistent keys before they hit the database.

```bash
redis-cli BF.RESERVE seen 0.001 1000000     # 0.1% FP rate, 1M capacity
redis-cli BF.ADD seen user:42
redis-cli BF.EXISTS seen user:99             # "definitely not" or "probably yes"
```

**RediSearch** adds a secondary-indexing and **full-text/query engine** over Redis hashes and JSON documents: you define an index schema (`FT.CREATE`) and run rich queries — text search with stemming, numeric/geo/tag filters, aggregations, and sorting (`FT.SEARCH`, `FT.AGGREGATE`) — addressing exactly the "Redis can't do rich queries" limitation from Q24, though it's an inverted index in RAM, not a replacement for a full RDBMS. **Vector sets / vector search** (a major focus of the Redis 8 era) add **approximate nearest-neighbor (ANN)** search over embedding vectors, making Redis a low-latency vector database for semantic search, recommendations, and **RAG (retrieval-augmented generation)** pipelines — you store embeddings alongside metadata and query by cosine/L2 similarity with HNSW indexing.

The architectural significance: these modules let teams consolidate a cache, a search index, a vector store, and a probabilistic-counting layer onto one low-latency in-memory platform instead of operating four systems — at the cost of holding it all in RAM and accepting module-specific operational and licensing considerations (notably that several of these modules are part of Redis Stack under the post-2024 source-available license, which is precisely why Valkey users must source alternatives). The senior judgment is to use modules where the latency and consolidation win is real, while staying clear-eyed that a RAM-resident search/vector index has very different cost and scale characteristics from a disk-native engine.

#### Q49. [Practical] How do you benchmark Redis correctly, and what numbers should you expect?

Naive benchmarking produces misleading numbers, so the discipline is to measure the *workload you actually run*, on representative hardware, with the network in the path. The built-in `redis-benchmark` is the starting point, but its defaults (tiny values, a handful of keys, heavy pipelining) flatter the server and don't resemble production.

```bash
# Realistic: many keys, your value size, your pipeline depth, your commands
redis-benchmark -h prod-redis -p 6379 \
  -t get,set -n 1000000 -r 1000000 \
  -d 256 -c 50 -P 1 --threads 4
# -r randomizes keys (avoids one hot key), -d value size, -P pipeline depth,
# -c clients, -n total requests. Add -q for a one-line summary per command.
redis-cli --latency-history -i 5         # measure tail latency separately
```

Read the results critically. With pipelining (`-P 16`) a single instance can report **hundreds of thousands to over a million ops/sec**, but `-P 1` (one command per round trip) reflects real request/reply latency — typically **tens to low-hundreds of thousands ops/sec** bounded by RTT, with sub-millisecond p50 on a local network. The pitfalls that invalidate benchmarks: (1) **client-side bottleneck** — `redis-benchmark` on one core can be the limit, not Redis; use `--threads` and multiple client hosts. (2) **Unrealistic key distribution** — default few-keys benchmarks live entirely in CPU cache; use `-r` to spread keys. (3) **Ignoring tail latency** — average ops/sec hides the p99 spikes that actually hurt users; always report percentiles. (4) **Wrong command mix** — an O(N) `LRANGE`/`HGETALL` benchmarks completely differently from `GET`. (5) **Not testing through your real client library/pool**, which has its own overhead. The senior framing: a benchmark's job is to predict production behavior and find the breaking point (where p99 degrades), not to produce a big throughput headline — measure latency percentiles under your real command mix and value sizes, and remember that single-threaded command execution means per-shard throughput is ultimately CPU-bound, which is why scaling means sharding, not bigger numbers from one node.

#### Q50. [Theory] How does Redis handle expiration in a replicated/clustered setup, and why can a replica serve a "stale" expired key?

Expiration semantics interact subtly with replication. To keep replicas byte-for-byte consistent with the primary, **replicas do not independently expire keys** — only the **primary** decides when a key expires (via lazy access or the active-expire cycle), and it then propagates an explicit `DEL`/`UNLINK` to replicas in the replication stream. This is deliberate: if replicas expired keys on their own clocks, two replicas could diverge from the primary and from each other, breaking consistency. The consequence is a visible window where a key has logically expired but the primary hasn't yet removed it (it wasn't accessed and the active cycle hasn't sampled it), so the replica still holds it.

```
Primary clock: key TTL hits 0
   ─► primary lazily/actively expires it ─► sends DEL to replicas
Between TTL-zero and the DEL, a replica still has the key in memory.
Read path on replica: returns "expired" data as empty (logically), avoiding stale value.
```

To prevent a replica from *returning* a value that has logically expired but not yet been deleted, the read path on a replica checks the logical TTL and treats a logically-expired key as absent (returns nil) even though the bytes are still resident — so clients don't see stale data, but memory accounting on the replica can briefly overstate usage. In **Cluster**, each shard's primary expires its own slots' keys independently, and the same primary-authoritative rule applies per shard. The practical implications: (1) memory on replicas can lag the primary's logical size during expiry-heavy workloads; (2) `DBSIZE` on a replica may include not-yet-deleted expired keys; and (3) you must never rely on a replica to *fire* expiration side effects (keyspace notifications for `expired` events originate from the primary). The interview-grade point is that this is a consistency-over-promptness trade-off: Redis accepts a brief memory/accounting fuzz to guarantee replicas never diverge from the primary's view of the data.

#### Q51. [Practical] What are the most dangerous Redis anti-patterns you'd flag in a code review?

Several recurring anti-patterns cause the bulk of real Redis incidents, and a senior reviewer should reflexively flag them. **Unbounded collections**: an `LPUSH`/`SADD`/`ZADD` with no cap or TTL grows until it's a multi-GB big key that blocks the thread on every full-collection op and creates a hot Cluster slot — always bound with `LTRIM`, `MAXLEN`, or a reaper. **`KEYS` / `HGETALL` / `SMEMBERS` on the hot path**: synchronous O(N) blocking; replace with `SCAN`/`HSCAN`/`SSCAN` or a maintained index. **Missing TTLs on cache keys with `noeviction`**: a slow-motion OOM that turns into write failures. **Using Redis as the sole system of record for critical data**: the async-replication lost-write window means committed data can vanish on failover.

```java
// ANTI-PATTERN: non-atomic read-modify-write race
int n = Integer.parseInt(redis.get("counter"));
redis.set("counter", String.valueOf(n + 1));   // lost updates under concurrency!
// FIX: server-side atomic op
redis.incr("counter");

// ANTI-PATTERN: SET clears the TTL silently
redis.setex("session", 1800, data);
redis.set("session", newData);                  // TTL gone -> session never expires
// FIX:
redis.set("session", newData, SetArgs.Builder.keepttl());
```

Others worth flagging: **no connection/command timeout** (a stalled Redis call ties up an app thread and can cascade into a thread-pool exhaustion outage); **assuming MULTI/EXEC rolls back** (it doesn't — runtime errors don't abort the batch); **per-key locks held across slow I/O** (a DB call inside a held distributed lock multiplies lock-contention and risks lock expiry mid-work); **storing huge blobs** that belong in object storage; and **fan-out via Pub/Sub for anything that must not be lost** (no persistence/ack). The meta-skill the interviewer is probing: do you recognize that almost every Redis production failure traces back to either *blocking the single thread* or *treating an AP cache as a durable/consistent store*? Most of the review checklist is one of those two root causes wearing a different hat.

#### Q52. [Theory] Explain the consistency/durability guarantees during a Sentinel or Cluster failover, including the lost-write window.

Failover is where Redis's AP nature becomes concrete, and being precise about it separates a strong answer from a hand-wave. Because replication is **asynchronous**, the primary acknowledges a write to the client *before* the replica has received it. If the primary crashes in that gap, the unreplicated writes are gone — and when a replica is promoted (by Sentinel quorum or Cluster gossip+vote), it has *no knowledge* those writes ever existed. This is the **lost-write window**: any write acknowledged but not yet propagated at the moment of failure is silently lost.

```
client ──WRITE──► PRIMARY ──"OK"──► client      (acked)
                     │ (async, not yet sent)
                     ✗ primary crashes
        replica promoted WITHOUT that write  ──►  write lost forever
```

A subtler failure mode is **split-brain / dual primaries**: during a network partition, the old primary may keep accepting writes from clients on its side while a replica is promoted on the other side — two primaries briefly accept divergent writes, and when the partition heals the old primary is demoted and its divergent writes are discarded. Mitigations bound but cannot eliminate the risk: `min-replicas-to-write N` / `min-replicas-max-lag` makes the primary **refuse writes** unless enough replicas are connected and current (trading availability for durability — it sacrifices the partition-minority side to limit data loss); `WAIT numreplicas timeout` lets a client block until N replicas ack a specific write, shrinking the window for that write at a latency cost (but it's still not linearizable and doesn't survive all failure orderings). Sentinel/Cluster quorum sizing and proper `down-after-milliseconds`/failover timeouts reduce spurious failovers. The honest interview conclusion: Redis failover is fast and highly available but **not lossless or linearizable**; if your data cannot tolerate the lost-write window or split-brain divergence, Redis is the wrong system of record — use a consensus-backed store, and reserve Redis for caching/ephemeral state where the window is acceptable.

#### Q53. [Practical] How would you debug "Redis is up but my application is timing out"?

When Redis health checks pass but the app reports timeouts, the fault is usually *not* a dead server — it's something between the app and Redis, or a per-request stall, and the debugging must separate those layers. First, confirm Redis itself is responsive and look at its own view of clients and slow commands.

```bash
redis-cli -h prod ping                       # PONG = server alive
redis-cli INFO clients                        # connected_clients, blocked_clients
redis-cli CLIENT LIST | wc -l                 # near maxclients?
redis-cli CONFIG GET maxclients
redis-cli SLOWLOG GET 20                       # a slow command stalling the loop
redis-cli INFO stats | grep -E 'rejected|instantaneous'   # rejected_connections
```

The common culprits, by layer: (1) **Connection-pool exhaustion in the app** — too few pooled connections for the concurrency, or connections leaked/not returned, so requests queue waiting for a connection and time out before reaching Redis at all; check pool metrics (active vs idle vs pending) before blaming Redis. (2) **`maxclients` reached on the server** — new connections are rejected (`rejected_connections` climbs); often caused by a client that opens a connection per request instead of pooling, or by replicas/Sentinels consuming slots. (3) **A single blocking command** stalling the single thread so *everyone's* requests slow down at once — `SLOWLOG`/`LATENCY LATEST` localizes it. (4) **Network/DNS/load-balancer** issues — TCP retransmits, a flapping endpoint, or a managed-service failover causing reconnect storms; `redis-cli --latency` from the *app host* distinguishes network latency from server latency. (5) **Misconfigured client timeouts** — too aggressive a command timeout turns a normal pipelined batch or a slightly slow command into spurious failures; too lax a timeout lets a real stall tie up threads. The systematic move is to ask "did the request reach Redis at all?" — if the connection never left the pool, fix pooling/timeouts; if it reached a server that's blocked, fix the slow command; if it's intermittent and correlates with reconnects, suspect failover/network. The frequent root cause in practice is the boring one: an undersized or misused connection pool, not Redis.

#### Q54. [Theory] What changed with threaded I/O (`io-threads`) in Redis 6+, and what does it NOT solve?

A persistent misconception is that Redis 6 "became multi-threaded." What it actually added is **threaded I/O**: the work of *reading bytes off sockets and parsing them*, and *encoding and writing replies back*, can be parallelized across a configurable pool of I/O threads (`io-threads`, with `io-threads-do-reads yes` to also parallelize reads). The **command execution itself remains single-threaded** — the dispatcher still runs every command one at a time on the main thread, preserving the lock-free atomicity guarantees that make Redis simple to reason about.

```bash
redis-cli CONFIG GET io-threads            # default 1 (off)
# In redis.conf, on a multi-core host with heavy network load:
#   io-threads 4
#   io-threads-do-reads yes
```

What this *does* solve: workloads that are **network/syscall-bound** — many connections, large values, high reply volume — where the main thread was spending a large fraction of its time in `read()`/`write()` syscalls and protocol serialization rather than in command logic. Offloading that I/O to helper threads lets the main thread spend more cycles executing commands, improving throughput on such workloads (often a meaningful boost for big-payload or high-connection scenarios). What it does **not** solve: **CPU-bound command cost**. A `KEYS *`, a heavy Lua script, a big-key `ZRANGEBYSCORE`, or sorting a large set still runs on the single execution thread and still blocks everyone — threaded I/O does nothing for that. Nor does it raise the per-instance ceiling for command-execution throughput; that's still one core, which is why **sharding (Cluster) remains the answer for scaling command throughput**. The nuanced trade-off: enabling `io-threads` on a CPU-light/network-heavy instance helps, but on a small or already-CPU-bound instance it adds thread-coordination overhead for no gain — and Valkey is pursuing more genuine multi-threading of execution precisely because this remains Redis's structural ceiling. The interview-grade summary: Redis 6 parallelized the *plumbing*, not the *logic*.

#### Q55. [Practical] Design the monitoring and alerting you'd put on a production Redis fleet.

Effective Redis monitoring tracks the few signals that predict the failure modes we've discussed, rather than drowning in metrics. I group alerts into saturation, latency, durability, and correctness. **Memory/saturation**: alert on `used_memory` / `maxmemory` ratio crossing ~80% (head off OOM/eviction), on `evicted_keys` rate climbing (cache thrashing or undersized), on `mem_fragmentation_ratio` well above ~1.5 (consider `activedefrag`), and on `connected_clients` approaching `maxclients`.

```bash
# Key INFO fields to scrape (via exporter / agent), with the failure they predict:
used_memory / maxmemory          # -> OOM / eviction storms
evicted_keys, expired_keys       # -> capacity pressure vs healthy TTL churn
keyspace_hits / keyspace_misses  # -> cache hit ratio (effectiveness)
instantaneous_ops_per_sec        # -> load / anomaly detection
blocked_clients, rejected_connections   # -> pool/maxclients trouble
master_link_status, master_repl_offset vs slave offset  # -> replication lag
latest_fork_usec                 # -> persistence-induced latency spikes
rdb_last_bgsave_status, aof_last_write_status            # -> backup/durability health
```

**Latency**: track client-observed p50/p99 (RED metrics from the app side) and server-side `LATENCY LATEST`/slowlog growth; alert on p99 regressions, not averages. **Durability/replication**: alert on `master_link_status:down`, replication offset lag beyond a threshold (a lagging replica is a bigger lost-write window and stale reads), failed `BGSAVE`/AOF writes (`rdb_last_bgsave_status:err`), and Sentinel/Cluster state transitions (every failover should page or at least notify). **Correctness/hit-ratio**: a sudden hit-ratio drop signals an invalidation bug, a TTL change, or an avalanche; a spike in `CROSSSLOT`/`MOVED` errors signals a Cluster topology or hash-tag problem. Standard tooling is the **Redis/Valkey Prometheus exporter** feeding Grafana with alertmanager rules, plus the managed-service equivalents (CloudWatch for ElastiCache). The senior framing: monitor the *leading indicators* of the known failure modes — memory ratio (OOM), fork time and replication lag (latency/durability), hit ratio and slot errors (correctness) — so you alert *before* the incident, and make every failover and persistence failure visible because those are exactly the moments data is at risk.

#### Q56. [Theory] Redis vs Memcached — when would you still pick Memcached?

Memcached and Redis overlap as in-memory caches, but they made different design bets, and a senior answer resists the reflexive "Redis is strictly better." **Memcached** is a deliberately minimal, multi-threaded, slab-allocated key→blob cache: it stores opaque bytes, has no persistence, no data structures, no replication, and a simple LRU. Its multi-threaded core means a single Memcached node can saturate many cores for raw `get`/`set` throughput on large multi-core machines — whereas Redis command execution is single-threaded per instance. **Redis** trades that for data-structure awareness (lists, sets, sorted sets, hashes, streams), optional persistence, replication, Cluster, Pub/Sub, scripting, and modules — a far broader toolbox.

| Dimension | Memcached | Redis |
|-----------|-----------|-------|
| Data model | Opaque blobs only | Rich data structures |
| Threading | Multi-threaded | Single-threaded execution (+ I/O threads) |
| Persistence | None | RDB / AOF |
| Replication / HA | None (client-side sharding) | Replicas, Sentinel, Cluster |
| Eviction | LRU (slab-based) | Multiple policies (LRU/LFU/TTL/random) |
| Memory model | Slab allocator (predictable, some waste) | jemalloc; can fragment |

You'd still pick Memcached when the workload is **purely a simple, ephemeral, string/blob cache at very high throughput**, you want the operational simplicity of a stateless cache with no persistence/replication to manage, and you can benefit from its multi-threaded per-node scaling and predictable slab memory behavior — for example a large read-through cache fronting a database where every value is an opaque serialized object and you never need server-side mutation. The moment you want counters, leaderboards, queues, atomic server-side operations, pub/sub, persistence, or built-in HA, Redis is the clear choice. In practice the industry has largely consolidated on Redis because the richer model wins for most use cases, but the honest interview answer names Memcached's genuine niche (multi-threaded simple-cache throughput and operational minimalism) rather than dismissing it.

#### Q57. [Theory] Explain the internals of Streams consumer groups: the PEL, XCLAIM/XAUTOCLAIM, and trimming.

Redis Streams give Kafka-like semantics in a single data structure, and the machinery that makes at-least-once delivery work is the **Pending Entries List (PEL)**. When a consumer reads via `XREADGROUP`, each delivered message ID is recorded in two PELs — a per-consumer PEL and the group's PEL — along with the delivery time and a delivery counter. The message stays "pending" until the consumer calls `XACK`, which removes it from the PEL. This is what gives durability: if a consumer crashes after reading but before acking, the message is still in the PEL and can be reclaimed, rather than silently lost like a plain `LPOP`.

```bash
redis-cli XADD orders '*' id 42 amount 100        # producer appends, auto ID
redis-cli XGROUP CREATE orders workers '$' MKSTREAM
redis-cli XREADGROUP GROUP workers c1 COUNT 10 STREAMS orders '>'   # new msgs
redis-cli XACK orders workers 1718000000000-0     # ack -> drop from PEL
redis-cli XPENDING orders workers                  # summary of un-acked work
redis-cli XAUTOCLAIM orders workers c2 60000 0     # steal msgs idle > 60s to c2
```

Reclaiming stuck work is the job of **`XCLAIM`** (explicitly reassign specific message IDs that have been idle beyond a min-idle-time to another consumer — used to recover a dead consumer's backlog) and the newer **`XAUTOCLAIM`** (Redis 6.2+), which scans the PEL and claims idle messages in one call, replacing hand-rolled `XPENDING`+`XCLAIM` loops. The delivery counter lets you implement **dead-lettering**: after N deliveries a message is probably a poison pill, so route it to a separate stream. Because streams live in RAM (with AOF), unbounded growth is a real risk, so you **trim** with `XADD ... MAXLEN ~ 100000` or `XTRIM` (the `~` makes trimming approximate and cheap by only trimming whole macro-nodes). The senior nuances: trimming can discard messages that are still *pending* in a PEL (trimming is by stream length, not ack state), so size retention generously and monitor PEL depth via `XPENDING`; the radix-tree storage means range queries (`XRANGE`) and ID-based access are efficient; and consumer-group state (groups, consumers, PELs, last-delivered ID) is itself part of the stream and replicated/persisted, so it survives failover.

#### Q58. [Theory] How do HyperLogLog and GEO commands work under the hood, and what are their accuracy/cost trade-offs?

These two "exotic" types are both clever encodings on top of simpler primitives. **HyperLogLog (HLL)** answers "how many *distinct* items?" using probabilistic counting in a fixed ~12 KB regardless of cardinality — versus a Set, which would need memory proportional to the number of unique members (gigabytes for billions of items). The trick: it hashes each element and tracks, across many buckets (registers), the maximum number of leading zeros seen; long runs of leading zeros are rare, so the longest run observed estimates the cardinality via a harmonic-mean formula. The cost is a **standard error of ~0.81%** — you trade exactness for a constant tiny footprint, which is the right trade for "unique visitors per day" but wrong when you need the exact set or membership tests.

```bash
redis-cli PFADD visitors:2026-06-16 user:1 user:2 user:3   # add elements
redis-cli PFCOUNT visitors:2026-06-16                       # ~count, ±0.81%
redis-cli PFMERGE visitors:week v:mon v:tue v:wed           # union of HLLs
redis-cli GEOADD places -122.41 37.77 "sf"                  # lon lat member
redis-cli GEOSEARCH places FROMMEMBER sf BYRADIUS 50 km ASC # nearby, sorted
```

**GEO** commands are syntactic sugar over a **Sorted Set**: each location's longitude/latitude is encoded into a single 52-bit **geohash** (an interleaved Z-order curve value), stored as the ZSet score. Because nearby points produce numerically-close geohashes, a radius query becomes a set of ZSet range scans over geohash boxes covering the search area, then a precise distance filter — which is why `GEOSEARCH`/`GEORADIUS` are roughly `O(N + log M)` and why the underlying member is just a normal ZSet member you can also manipulate with `ZRANGE`. The trade-offs: GEO has tiny precision error from the geohash quantization (sub-meter, irrelevant for "find nearby"), and large radius queries near box boundaries scan more candidates. The interview point for both types is the same theme as the rest of Redis: choose the encoding that lets the server answer the *actual question* cheaply — approximate cardinality in constant memory (HLL) or proximity via a geohash-ordered ZSet (GEO) — and know the accuracy budget you're accepting in exchange.

#### Q59. [Practical] How do you design key naming, namespacing, and multi-tenancy in a shared Redis?

Key design is unglamorous but is where shared-Redis deployments succeed or rot. The conventions that matter: use a **consistent, colon-delimited hierarchy** (`tenant:service:entity:id:field`, e.g. `acme:orders:order:42:status`) so keys are self-describing, greppable, and `SCAN`-able by prefix; **embed the schema version** where payloads evolve (`v2:user:42`) so a format change doesn't corrupt readers; and keep keys short enough to not waste memory at scale (millions of keys × a long prefix is real RAM) while staying readable. Avoid encoding mutable attributes into keys (you can't rename atomically), and never build keys from unescaped user input (key injection / accidental collisions).

```
acme:sess:abc123              -> tenant "acme", sessions namespace
acme:cache:product:42         -> cache namespace, evictable
acme:rl:user:99               -> rate-limit namespace
{acme:user:42}:profile        -> hash-tag co-locates a tenant's keys in one slot
```

For **multi-tenancy** the critical truth is that **Redis logical DBs (`SELECT 0..15`) are NOT isolation** — they share the same instance, memory, CPU, and config; they're unavailable in Cluster; and they offer no security boundary. Real tenant isolation comes from (1) **key-prefix namespacing** for logical separation, (2) **ACLs with `~tenant:*` key patterns** so a compromised or buggy tenant credential can't touch another tenant's keys (Q46), and (3) for strong isolation or noisy-neighbor protection, **separate instances/clusters per tenant** (or per tenant tier) accepting the operational cost. Cluster adds a subtlety: putting a tenant's keys behind a common hash tag (`{tenantId}`) co-locates them for multi-key ops but risks a **hot slot** if one tenant is huge — so reserve hash tags for keys that genuinely need atomic multi-key operations, not as a blanket per-tenant grouping. The senior framing: namespacing gives you operability (debugging, `SCAN`, selective invalidation, per-prefix memory analysis via `redis-cli --memkeys`), ACLs give you security isolation, and only separate instances give you true resource isolation — pick the level your tenancy model and blast-radius requirements demand.

#### Q60. [Practical] How do you right-size memory and diagnose where Redis memory is actually going?

Capacity planning for Redis is fundamentally a memory exercise, and "how much RAM do I need?" decomposes into more than just the raw data. Total memory is dataset + per-key overhead (each key has dictionary-entry, expire-table, and object-header overhead — often ~50–100 bytes before your value) + replication backlog + client output buffers + Lua/function caches + fragmentation + (if you persist) COW headroom for fork (Q39). A frequent under-estimate is the **per-key overhead** dominating when you store millions of tiny keys — which is exactly why the "pack small objects into hashes" optimization (Q34) saves so much: one hash with listpack encoding amortizes the overhead across many fields.

```bash
redis-cli INFO memory                       # used_memory, used_memory_rss, peak, frag ratio
redis-cli MEMORY DOCTOR                       # human-readable memory health advice
redis-cli MEMORY USAGE user:42 SAMPLES 0      # bytes for one key incl. overhead
redis-cli --bigkeys                           # largest key per type (one pass)
redis-cli --memkeys                           # keys consuming the most memory
redis-cli MEMORY STATS                        # detailed breakdown by subsystem
```

The diagnostic workflow when memory is higher than expected: start with `MEMORY STATS` / `MEMORY DOCTOR` to split *dataset* from *overhead* (replication buffers, client buffers, fragmentation). A high `mem_fragmentation_ratio` (RSS much larger than `used_memory`) means the allocator is holding freed-but-unreturned memory — enable `activedefrag yes` to compact in the background, or as a last resort restart on a replica and fail over. Use `--bigkeys`/`--memkeys` to find whether a few large keys dominate (split them) and `MEMORY USAGE` to validate per-key cost assumptions. For right-sizing, the rule of thumb is: provision for **peak dataset × (1 + fragmentation headroom ~20–30%) + fork COW headroom (if persisting, budget toward 2× during saves under heavy write) + buffers**, then add alerting at ~80% so eviction or OOM never surprises you (Q55). The senior point: don't size to `used_memory` at a quiet moment — size to peak working set plus the structural overheads (fork, fragmentation, buffers) that are invisible until exactly the wrong moment, and prefer measuring with `MEMORY` tooling over guessing.

## 🧩 Extended Questions — Set 2: Coding, Design & Expert Scenarios

### 🟢 Basic — extended

#### Q61. [Coding] Implement a simple session store with sliding-expiration (refresh TTL on access).

**Problem:** Store web sessions in Redis so that each access *extends* the session's lifetime by 30 minutes (sliding window), but an idle session expires. The naive bug is to read the session and forget to refresh, or to refresh non-atomically.

The clean approach stores the session as a Hash (so individual fields update cheaply) and uses the `GETEX` family — but since `GETEX` works on strings, for a hash we issue the read plus an `EXPIRE` refresh. Doing both in one Lua call keeps it atomic and avoids a window where a concurrent logout could delete the key between the read and the refresh.

```java
private static final String TOUCH = """
  if redis.call('EXISTS', KEYS[1]) == 0 then return false end
  redis.call('EXPIRE', KEYS[1], ARGV[1])      -- slide the window
  return redis.call('HGETALL', KEYS[1])
  """;

public Map<String,String> getAndRefresh(RedisCommands<String,String> redis,
                                        String sid, int idleSeconds) {
    Object raw = redis.eval(TOUCH, ScriptOutputType.MULTI,
            new String[]{ "sess:" + sid }, String.valueOf(idleSeconds));
    return toMap(raw);                          // null/empty -> session gone
}

public void createSession(RedisCommands<String,String> redis, String sid,
                          Map<String,String> data, int idleSeconds) {
    redis.hset("sess:" + sid, data);
    redis.expire("sess:" + sid, idleSeconds);
}
```

**Time/Space:** O(N) in the number of session fields for `HGETALL` (small and bounded), O(1) for the `EXPIRE`; one key per active session, all self-expiring. **Edge cases:** sliding expiration means a busy user *never* logs out by timeout — pair it with an absolute max-lifetime (a second key, or store a `createdAt` field and reject sessions older than, say, 12h). Spring Session's Redis backend implements exactly this pattern; if you use it, configure `flushMode` and `saveMode` so the sliding refresh actually happens, and remember that `SET`-style writes to the session key without `KEEPTTL` would clobber the sliding TTL (the recurring TTL footgun from Q4).

#### Q62. [Coding] Write code to atomically increment several counters and read them back in one round trip.

**Problem:** A dashboard needs to bump `views`, `clicks`, and `conversions` for a campaign on each event and occasionally read all three consistently. Doing three separate `INCR`s plus three `GET`s is six round trips and gives no point-in-time consistency for the read.

A Hash with `HINCRBY` collapses all counters under one key, and `HGETALL` reads them in one shot. Because Redis executes each command atomically on its single thread, `HINCRBY` needs no application lock; for the multi-field bump-and-read use a pipeline or `MULTI` so the snapshot is coherent.

```java
// Bump three counters atomically (each HINCRBY is atomic; group for one RTT)
public void record(RedisCommands<String,String> redis, String campaign,
                   long views, long clicks, long conv) {
    String k = "camp:" + campaign + ":stats";
    redis.multi();
    redis.hincrby(k, "views",       views);
    redis.hincrby(k, "clicks",      clicks);
    redis.hincrby(k, "conversions", conv);
    redis.exec();                                  // all-or-nothing, no interleave
}

public Map<String,String> snapshot(RedisCommands<String,String> redis, String campaign) {
    return redis.hgetall("camp:" + campaign + ":stats");   // coherent read
}
```

**Time/Space:** O(1) per field bump, O(fields) for the snapshot; one compact hash per campaign (listpack-encoded while small, per Q34). **Edge cases:** `HGETALL` on a hash that has grown huge is an O(N) blocking trap — keep counter hashes to a bounded, known set of fields (don't let users inject arbitrary field names). If you need the read to reflect *exactly* the state after your own writes and nothing in between, note that `MULTI/EXEC` guarantees no interleaving for the writes, but a separate later `HGETALL` can still see other clients' increments — that's usually fine for a dashboard. For Cluster, all fields share one key/slot automatically, so no hash-tag gymnastics are needed.

#### Q63. [Theory] What is the difference between `SET` options NX, XX, GET, EX, PX, EXAT, KEEPTTL — and why were they consolidated?

Modern Redis folded a family of older commands (`SETNX`, `SETEX`, `PSETEX`, `GETSET`) into flags on the single `SET` command, which matters because combining behaviors used to require multiple non-atomic commands. **`NX`** sets only if the key does *not* exist (the basis of locks and idempotency claims, Q18/Q44); **`XX`** sets only if it *already* exists (useful for "update but don't create"). **`EX seconds` / `PX milliseconds`** attach a relative TTL atomically with the write — replacing the broken `SET` then `EXPIRE` two-step. **`EXAT` / `PXAT`** set an *absolute* expiry as a Unix timestamp, which is handy when many keys should all expire at the same wall-clock moment (e.g. end of a billing day) regardless of when they were written.

```bash
redis-cli SET lock:job tok123 NX PX 5000          # acquire lock if free, 5s TTL
redis-cli SET config:flag on XX                    # update only if it exists
redis-cli SET session newval KEEPTTL               # change value, keep current TTL
redis-cli SET counter 0 EXAT 1735689600            # expire at an absolute instant
redis-cli SET key newval GET                        # set and return the OLD value
```

**`KEEPTTL`** preserves an existing TTL when overwriting a value — the fix for the classic footgun where a plain `SET` silently clears expiry (Q4). **`GET`** (Redis 6.2+) makes `SET` return the *previous* value atomically, replacing `GETSET` and enabling atomic read-and-replace patterns (e.g. swap a token and learn what it was) in one round trip. The consolidation matters for a deeper reason than convenience: each combined operation is a **single atomic command** on the single-threaded server, so "set-with-TTL-if-absent-and-return-old" carries no race window. The interview point is to recognize these flags exist and prefer them over the legacy multi-command sequences, both for atomicity and for fewer round trips.

### 🟡 Intermediate — extended

#### Q64. [Coding] Build a delayed-job / scheduled-task queue using a Sorted Set.

**Problem:** Schedule jobs to run at a future time ("send reminder in 2 hours", "retry payment at 14:00") and have workers pick up only jobs whose time has arrived — without busy-polling every job. Keyspace notifications on expiry (Q36) are lossy, so we build a durable poller on a ZSet scored by due-timestamp.

Jobs go into a ZSet with the due time (epoch ms) as the score; workers atomically pop the *due* ones with a Lua script that combines `ZRANGEBYSCORE` (find due) and `ZREM` (claim) so two workers never grab the same job.

```java
private static final String POLL = """
  local due = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1], 'LIMIT', 0, ARGV[2])
  if #due > 0 then redis.call('ZREM', KEYS[1], unpack(due)) end
  return due
  """;

public void schedule(RedisCommands<String,String> redis, String jobId, long runAtMs) {
    redis.zadd("jobs:scheduled", runAtMs, jobId);          // score = due time
}

public List<String> claimDue(RedisCommands<String,String> redis, int batch) {
    long now = System.currentTimeMillis();
    Object due = redis.eval(POLL, ScriptOutputType.MULTI,
            new String[]{ "jobs:scheduled" }, String.valueOf(now), String.valueOf(batch));
    return toList(due);                                    // claimed, removed atomically
}
```

**Time/Space:** `ZADD`/`ZRANGEBYSCORE` are O(log N + M); memory O(scheduled jobs). **Edge cases:** the worker loop sleeps a short interval (e.g. 1s) between polls — short enough for acceptable scheduling latency, long enough to avoid hammering Redis; tune by polling more aggressively when the next due time is near (`ZRANGE WITHSCORES LIMIT 0 1` tells you the soonest job). The `ZREM`-on-claim means a crash *after* claiming but before executing loses the job — for reliability move the claimed job into a "processing" ZSet (scored by a visibility deadline) and have a reaper requeue ones that exceed it, mirroring the reliable-queue idea from Q22. For Cluster, keep the ZSet on one slot (single key) — a single scheduler key rarely needs sharding unless job volume is enormous, in which case shard by `jobs:scheduled:{bucket}`.

#### Q65. [Coding] Implement set-algebra for a "users who did A but not B" analytics query.

**Problem:** Marketing wants the cohort of users who **added to cart** but did **not purchase** in a period, and the count of users in **both** an "active" and "premium" segment. This is textbook set algebra, and pushing it to Redis avoids pulling millions of ids to the app.

Sets store membership; `SDIFF` (difference), `SINTER` (intersection), and `SUNION` do the algebra server-side. For repeated/large queries, store the result with `SDIFFSTORE`/`SINTERSTORE` so you compute once and reuse, and so you can attach a TTL to the materialized cohort.

```java
// Users who added to cart but did NOT purchase
public long abandonedCart(RedisCommands<String,String> redis, String day) {
    String dest = "cohort:abandoned:" + day;
    redis.sdiffstore(dest, "ev:cart:" + day, "ev:purchase:" + day);  // A \ B
    redis.expire(dest, 3600);                                        // cache 1h
    return redis.scard(dest);                                        // size of cohort
}

// Count of active AND premium users
public long activePremium(RedisCommands<String,String> redis) {
    return redis.sintercard(List.of("seg:active", "seg:premium"));   // 7.0+: count only
}
```

**Time/Space:** `SINTER`/`SDIFF` are O(N×M) in the worst case (proportional to total members); memory for stored cohorts is O(result size). **Edge cases:** these are **O(N) blocking** operations on the single thread — a `SUNIONSTORE` over multi-million-member sets can stall the server, so run heavy analytics on a **replica** or off-peak, and prefer `SINTERCARD` (Redis 7.0+) when you only need the *count*, since it can short-circuit with a `LIMIT` and avoids materializing the whole result. In **Cluster**, multi-set commands require all sets in the same slot (hash-tag the segment keys, e.g. `{analytics}:seg:active`) or the command returns `CROSSSLOT`. For very high cardinality where exactness isn't required, approximate with HyperLogLog (`PFCOUNT`/`PFMERGE`, Q58) instead of real sets to save orders of magnitude of memory — at the cost of ~0.81% error and losing the ability to enumerate members.

#### Q66. [Practical] Design a feature-flag / configuration service backed by Redis. What are the trade-offs?

A feature-flag service needs low-latency reads on every request, occasional writes by operators, and fast propagation of changes. I'd store each flag as a small Hash (`flag:newCheckout` → `{enabled: true, rollout: 25, segments: "premium,beta"}`) so individual attributes update without rewriting the whole flag, and use a single `HGETALL` (or `HMGET` for specific fields) per read. The decisive design choice is **not** to hit Redis on every flag evaluation in the hot path — that adds a network RTT to every request — but to keep an **in-process cache** (Caffeine) of all flags, refreshed either on a short interval or, better, via Redis **server-assisted client-side caching** (`CLIENT TRACKING`, Q40) so a flag change invalidates the local copy within milliseconds.

```
operator ─► HSET flag:x enabled true        (write)
                     │
   Redis ──CLIENT TRACKING invalidation──►  app L1 cache evicts "flag:x"
   app request ─► read from L1 (sub-µs), fall back to Redis on miss
```

**Trade-offs to name:** (1) **Staleness vs latency** — pure in-process caching with a 10s refresh means a flag flip takes up to 10s to reach all nodes; tracking-based invalidation cuts that to near-real-time at the cost of a tracking connection. (2) **Consistency across a fleet** — different app nodes can briefly see different flag states during propagation, so flag logic must be safe under disagreement (don't let a half-rolled-out flag corrupt data). (3) **Durability** — flags are config, so enable persistence (RDB at least) or treat a config DB/git as the source of truth and Redis as a fast read replica of it, repopulating on restart. (4) **Auditability** — Redis isn't an audit log; pair writes with an append to a Stream or an external audit store so you can answer "who flipped this and when." The senior framing: Redis is the *fast distribution layer*, not the source of truth — back it with a durable config store and push changes through it, and use client-side caching to get in-memory read latency without sacrificing timely invalidation.

#### Q67. [Theory] Explain `WAIT`, `WAITAOF`, and how to tighten durability on a per-write basis.

By default a write is acknowledged the instant the primary applies it in memory — before any replica has it (Q21) and before it's fsynced to disk. Two commands let a client *opt into stronger guarantees for a specific write* without changing the whole server's durability posture. **`WAIT numreplicas timeout`** blocks the calling client until at least `numreplicas` replicas have acknowledged *all the writes issued so far by this connection*, or the timeout (ms) elapses; it returns the number of replicas that acked. This shrinks the lost-write window (Q52) for critical writes — e.g. after recording a payment, `WAIT 1 200` ensures at least one replica has it before you tell the user "done."

```bash
redis-cli SET payment:42 captured
redis-cli WAIT 1 200            # block until >=1 replica acks, or 200ms; returns acked count
# Redis 7.2+: also wait for local AOF fsync on primary and replicas
redis-cli WAITAOF 1 1 500       # numlocal=1 (primary fsynced), numreplicas=1, timeout 500ms
```

**`WAITAOF`** (Redis 7.2+) goes further: it blocks until the write is fsynced to the **AOF** on the local primary *and/or* on N replicas, addressing the gap that `WAIT` only confirms *replication*, not *disk durability* — a write could be on a replica's RAM yet lost if both crash before fsync. The crucial caveat for both: **neither makes Redis linearizable**. `WAIT` confirms replicas *received* the write, but a failover can still choose a replica that didn't, depending on timing and which replica is promoted; it reduces but doesn't eliminate the window, and it adds latency equal to the slowest acking replica. The senior framing: use `WAIT`/`WAITAOF` surgically on the handful of writes whose loss is genuinely costly, accept the latency hit there, and understand that for true linearizable durability you still need a consensus-backed store — these commands buy you *bounded, best-effort* extra safety, not a different consistency model.

#### Q68. [Coding] Implement a "unique daily active users" counter and a "did this user act today" check efficiently.

**Problem:** Count distinct daily active users (could be tens of millions) cheaply, and also answer "was user 12345 active today?" — two different questions with two different optimal structures.

For the **count of distinct users**, a HyperLogLog is ideal: ~12 KB regardless of cardinality (Q58). For the **per-user membership check** where ids are dense integers, a **Bitmap** uses one bit per user id — 10M users is ~1.25 MB — and supports O(1) `SETBIT`/`GETBIT` plus fast `BITCOUNT` for totals.

```java
// Approximate distinct count (memory-constant, ~0.81% error)
public void markActiveHLL(RedisCommands<String,String> redis, String day, String userId) {
    redis.pfadd("dau:hll:" + day, userId);
}
public long distinctActive(RedisCommands<String,String> redis, String day) {
    return redis.pfcount("dau:hll:" + day);
}

// Exact membership + exact count when ids are dense integers
public void markActiveBitmap(RedisCommands<String,String> redis, String day, long userId) {
    redis.setbit("dau:bm:" + day, userId, true);          // 1 bit per user id
}
public boolean wasActive(RedisCommands<String,String> redis, String day, long userId) {
    return redis.getbit("dau:bm:" + day, userId) == 1L;
}
public long exactActiveCount(RedisCommands<String,String> redis, String day) {
    return redis.bitcount("dau:bm:" + day);
}
```

**Time/Space:** HLL is O(1) per add and ~12 KB total; Bitmap is O(1) per bit and O(max-user-id / 8) bytes. **Trade-offs and edge cases:** HLL gives *count only* (you cannot ask "was user X active?" or enumerate members) and carries ~0.81% error — perfect for "DAU" dashboards, wrong for billing or per-user logic. Bitmaps give *exact* membership and exact counts but only work well when ids are **dense small integers** (a sparse 64-bit user id would create a multi-petabyte bitmap — map to a dense sequence first, or hash into a fixed range accepting collisions). Bitmaps also enable powerful cross-day analytics via `BITOP AND/OR` — e.g. "users active both Monday and Tuesday" is `BITOP AND` of two day-bitmaps then `BITCOUNT` — which HLL's `PFMERGE` can only do for unions, not intersections. Choose HLL for cardinality-only at any scale; choose Bitmap for exactness and set-operation analytics on dense-integer populations.

### 🟠 Advanced — extended

#### Q69. [Coding] Implement an atomic "transfer N points between two users" with insufficient-balance rejection.

**Problem:** Move points from user A to user B atomically: debit A, credit B, but only if A has enough — and never lose or duplicate points under concurrency. Doing `GET A`, check, `DECRBY A`, `INCRBY B` from the app has a race (two transfers both see enough balance) and isn't atomic across the two keys.

A Lua script runs as a single atomic unit on the single thread, so the read-check-debit-credit happens with no interleaving. In Cluster, both balance keys must be in the same slot, so we hash-tag them under the same tag.

```java
private static final String XFER = """
  local from = tonumber(redis.call('GET', KEYS[1]) or '0')
  local amt  = tonumber(ARGV[1])
  if from < amt then return -1 end                 -- insufficient funds
  redis.call('DECRBY', KEYS[1], amt)
  redis.call('INCRBY', KEYS[2], amt)
  return redis.call('GET', KEYS[1])                 -- new balance of sender
  """;

public long transfer(RedisCommands<String,String> redis,
                     String from, String to, long amount) {
    // hash-tag co-locates both keys in one Cluster slot
    String kf = "{acct:" + from + "}:bal";
    String kt = "{acct:" + to   + "}:bal";   // NOTE: see edge case on tagging
    Long res = redis.eval(XFER, ScriptOutputType.INTEGER,
            new String[]{ kf, kt }, String.valueOf(amount));
    if (res == -1L) throw new InsufficientFundsException();
    return res;
}
```

**Time/Space:** O(1); atomic by construction. **Edge cases — and a real Cluster trap:** the two keys must share a slot for a multi-key Lua script, but a *per-account* hash tag (`{acct:A}` vs `{acct:B}`) puts them in **different** slots, so the script throws `CROSSSLOT`. You either (a) route all transferable balances under one common tag (`{ledger}:acct:A`), which co-locates them but creates a hot slot and defeats sharding for balances, or (b) accept that cross-account atomic transfers don't fit Cluster's model and instead use a single non-clustered instance for the ledger, or a real transactional database. This is the concrete face of "no cross-slot atomicity" (Q16): atomic multi-key money movement is exactly the workload where Redis Cluster's partitioning fights you, and recognizing that constraint up front is the senior signal. Also note Redis isn't a ledger of record — for real money you want a durable, auditable transactional system; this pattern fits *game points / virtual currency* where the lost-write window is tolerable.

#### Q70. [Theory] Why must Lua scripts and Functions be deterministic, and how does Redis enforce it for replication?

Redis replicates and persists writes, and historically it replicated **the script itself** (verbatim/effects depended on the version), meaning a replica re-executed the same Lua. If a script used a non-deterministic source — `math.random()` without a seed, the system clock, or iterating a hash/set whose order isn't guaranteed and then writing based on that order — the primary and replica could compute *different* results, diverging the dataset and breaking the core invariant that replicas mirror the primary. The same risk applies to AOF replay on restart.

```bash
# Non-deterministic write inside a script -> primary/replica divergence risk
#   redis.call('SET', KEYS[1], math.random())          -- BAD
#   redis.call('SET', KEYS[1], redis.call('TIME')[1])  -- TIME differs per node
# Deterministic: derive randomness/time from ARGV passed by the client
#   EVAL "redis.call('SET', KEYS[1], ARGV[1])" 1 key  <seeded-value-from-client>
```

Redis addressed this two ways across versions. Older Redis (≤4) **verbatim-replicated** scripts and therefore *restricted* non-deterministic commands inside scripts (it would error if you called a random/non-deterministic command before a write, or required `redis.replicate_commands()`). Modern Redis (5+) defaults to **effects replication**: instead of shipping the script, the primary replicates the *concrete write commands the script actually executed* (the resulting `SET`/`INCR`/etc.), so replicas apply identical effects regardless of how the primary computed them — this is safer and is the default for both Lua and Functions. The practical rules that survive: **pass any randomness or timestamp in as an argument** from the client (or derive it deterministically) rather than reading it inside the script; avoid writes whose values depend on unordered iteration; and declare your key accesses correctly so Cluster can route. The deep point is that determinism is a *replication-correctness* requirement, and effects replication is Redis's way of making the common case safe while you still must avoid letting non-determinism leak into the values you persist.

#### Q71. [Coding] Implement optimistic-locking inventory decrement with a bounded retry loop using WATCH.

**Problem:** Decrement product stock on purchase, never overselling, using `WATCH`/`MULTI`/`EXEC` optimistic concurrency (contrast with the Lua approach so you can speak to both). The transaction must abort and retry if another buyer changed the stock between your read and your write.

`WATCH` marks the key; if it changes before `EXEC`, `EXEC` returns null and we retry. We bound retries to avoid an unbounded spin under high contention.

```java
public boolean purchase(StatefulRedisConnection<String,String> conn, String sku, int qty) {
    RedisCommands<String,String> redis = conn.sync();
    String key = "stock:" + sku;
    for (int attempt = 0; attempt < 5; attempt++) {
        redis.watch(key);                                  // optimistic lock
        long stock = Long.parseLong(redis.get(key));
        if (stock < qty) { redis.unwatch(); return false; } // out of stock, no retry
        redis.multi();
        redis.decrby(key, qty);
        TransactionResult r = redis.exec();                // null => key changed
        if (r != null && !r.wasDiscarded()) return true;   // success
        // else: someone else changed stock; loop and retry
    }
    throw new RetryExhaustedException("too much contention on " + sku);
}
```

**Time/Space:** O(1) per attempt; expected attempts rise with contention. **Edge cases and the key trade-off:** `WATCH` is **compare-and-swap**, so under heavy contention on a hot SKU (a flash sale) many transactions abort and retry, wasting round trips — this is exactly when a **Lua script** wins, because it does the check-and-decrement in one atomic pass with *zero* retries (the Q18/Q69 pattern). Use `WATCH` when the contended path is rare and you want to stay client-side; use Lua when contention is high or the logic is hot. Other gotchas: `WATCH` must be on the same connection as `MULTI`/`EXEC` (Lettuce shares connections, so use a dedicated connection or `setAutoFlushCommands` carefully); `EXEC` returning null is *not* an error, it's the abort signal — handle it explicitly; and a value that fails to parse (corrupt/missing) needs its own branch rather than throwing inside the loop forever. Finally, `WATCH` doesn't work across slots in Cluster, same constraint as everything multi-key.

#### Q72. [Practical] Design a real-time notification/presence system (who's online) for a chat app on Redis.

Presence and notifications combine several Redis primitives, and the design hinges on the fact that "online" is *ephemeral* — it should decay automatically if a client disappears. I'd model presence as a per-user key with a short TTL that the client **heartbeats**: `SET presence:userId 1 EX 30` every ~10 seconds. If heartbeats stop (crash, network loss), the key expires and the user is implicitly offline — no explicit "logout" cleanup needed. For "who's online in a room," maintain a Sorted Set per room scored by last-heartbeat timestamp; a periodic sweep (or a read-time filter) treats members with stale scores as offline, giving both the set and the freshness in one structure.

```
heartbeat:  SET presence:42 1 EX 30           (renews every 10s)
room set:   ZADD room:7:online <now> 42       (score = last seen)
online?:    EXISTS presence:42  (or ZSCORE room:7:online 42 within window)
fan-out:    PUBLISH room:7 "{from:42, text:...}"   (live, lossy)
offline inbox: XADD inbox:99 * from 42 text ...    (durable, replayable)
```

**The key design split is live vs durable delivery.** For *live* messages to currently-connected users, **Pub/Sub** (`PUBLISH room:7 ...`) is perfect — low latency, fire-and-forget, and it's fine that an offline user misses it because they weren't there. But notifications that must survive being offline (mentions, DMs, missed messages) need a **durable per-user inbox**, which is a **Stream** (`XADD inbox:userId`) the client drains and acks on reconnect (Q57), or a capped List. The senior nuances: (1) Pub/Sub doesn't scale across a Cluster trivially — messages publish to all nodes (sharded Pub/Sub, `SPUBLISH`/`SSUBSCRIBE` in Redis 7, keeps a channel on one shard for efficiency); (2) presence TTL length trades freshness against heartbeat traffic (30s TTL with 10s heartbeat tolerates one missed beat); (3) at large scale, fan-out to millions of subscribers belongs on a dedicated message bus, with Redis handling presence and recent-message buffers. The architecture I'd present: TTL-keyed presence + ZSet room rosters + Pub/Sub for live fan-out + Streams for durable inboxes — each primitive doing what it's best at.

#### Q73. [Coding] Implement a circuit breaker whose state is shared across all app instances via Redis.

**Problem:** A local (per-process) circuit breaker lets each instance learn failures independently; a *shared* breaker means once any instances observe a downstream failing, all instances trip together, protecting the dependency faster. We need atomic failure counting and atomic state transitions.

State (`CLOSED`/`OPEN`/`HALF_OPEN`), a rolling failure count, and the open-until timestamp live in a Hash; transitions go through a Lua script so concurrent requests can't corrupt the state machine.

```java
private static final String CHECK = """
  local state = redis.call('HGET', KEYS[1], 'state') or 'CLOSED'
  local now   = tonumber(ARGV[1])
  if state == 'OPEN' then
    local until_ = tonumber(redis.call('HGET', KEYS[1], 'openUntil') or '0')
    if now < until_ then return 'OPEN' end          -- still tripped, reject fast
    redis.call('HSET', KEYS[1], 'state', 'HALF_OPEN')-- allow a trial request
    return 'HALF_OPEN'
  end
  return state
  """;

private static final String RECORD = """
  local ok = ARGV[1]; local now = tonumber(ARGV[2])
  local threshold = tonumber(ARGV[3]); local cooldown = tonumber(ARGV[4])
  if ok == '1' then
    redis.call('HSET', KEYS[1], 'state', 'CLOSED'); redis.call('HSET', KEYS[1], 'fails', 0)
  else
    local f = redis.call('HINCRBY', KEYS[1], 'fails', 1)
    if f >= threshold then
      redis.call('HSET', KEYS[1], 'state', 'OPEN')
      redis.call('HSET', KEYS[1], 'openUntil', now + cooldown)
    end
  end
  return redis.call('HGET', KEYS[1], 'state')
  """;
```

**Time/Space:** O(1) per check/record; one small hash per protected dependency. **Edge cases and trade-offs:** a shared breaker adds a Redis round trip to *every* protected call — if Redis itself is the dependency or is slow, you've coupled availability, so always combine it with a **local fallback breaker** (use the shared state when Redis answers within a tight timeout, fall back to per-process logic otherwise) and never let the breaker's own Redis call block on a missing timeout. `HALF_OPEN` must admit only a *limited* number of trial requests (use an additional `INCR` guarded counter) or a thundering herd of trials hammers the recovering downstream. Clock skew across instances affects `openUntil` — prefer `redis.call('TIME')` inside the script (passed deterministically per Q70) over each app server's clock. The senior framing: sharing breaker state trades a little latency and a Redis dependency for fleet-wide fast-failing, which is valuable when the protected downstream is fragile, but it must degrade gracefully when Redis is unavailable rather than turning a cache outage into a total outage.

#### Q74. [Theory] How does `OBJECT ENCODING` reveal performance characteristics, and how do you avoid accidental encoding upgrades?

Every Redis value has both a logical type (`hash`, `zset`, ...) and a physical **encoding** that Redis picks automatically (Q34), and `OBJECT ENCODING key` exposes it — which is one of the most useful and underused debugging commands because the encoding determines both memory cost *and* operation complexity. A small hash/zset/list in `listpack` is memory-tiny but O(N) to operate on internally; once it crosses a configured threshold it converts to the "big" structure (`hashtable`, `skiplist`, `quicklist`) which is larger per element but O(1)/O(log N). The trap is an **accidental upgrade**: a single oversized element or one too many members silently converts the whole structure, and it **never converts back** even if you shrink it again — so a brief spike can permanently inflate a key's memory.

```bash
redis-cli RPUSH small a b c;          redis-cli OBJECT ENCODING small   # listpack
redis-cli SADD ints 1 2 3;            redis-cli OBJECT ENCODING ints    # intset
redis-cli SADD ints hello;            redis-cli OBJECT ENCODING ints    # listpack/hashtable
redis-cli SET n 12345;                redis-cli OBJECT ENCODING n        # int (shared/encoded)
redis-cli SET s "a long-ish string";  redis-cli OBJECT ENCODING s        # embstr / raw
```

The actionable knowledge: an integer string is stored as an `int` encoding (8 bytes, sometimes a shared object) — far cheaper than the same number as `raw` text; short strings use `embstr` (single allocation, cache-friendly) while strings over 44 bytes become `raw` (separate allocation). For collections, keep elements under the `*-max-listpack-value` byte threshold and counts under `*-max-listpack-entries` if you *want* the compact encoding, and conversely raise those thresholds only if you've measured that the listpack's O(N) scans aren't hurting latency. To **avoid accidental upgrades**: bound collection growth (a stray giant member upgrades a hash you intended to keep compact), don't store one oversized field in an otherwise-small hash, and remember the conversion is one-way — if a key got upgraded by a transient spike and is now wastefully large, you must rewrite it (e.g. `DUMP`/`RESTORE` into a fresh key, or delete and rebuild) to reclaim the compact form. Using `OBJECT ENCODING` in code review and incident analysis catches the "why is this hash using 10× the memory I expected" mystery instantly.

#### Q75. [Coding] Write a Lua script that implements compare-and-set (CAS) for a versioned cache entry.

**Problem:** Multiple writers update a cached object; we want to avoid the lost-update problem where a slow writer overwrites a newer value. Classic solution: store a version number with the value and only accept a write whose expected version matches the current one — optimistic concurrency, done server-side atomically.

The value and version live in a Hash; a Lua script compares the caller's expected version to the stored one and only updates (incrementing the version) on a match, returning success/failure plus the current version so the caller can refetch and retry.

```java
private static final String CAS = """
  local cur = tonumber(redis.call('HGET', KEYS[1], 'ver') or '0')
  local expected = tonumber(ARGV[1])
  if cur ~= expected then
    return {0, cur}                       -- conflict: caller's version is stale
  end
  redis.call('HSET', KEYS[1], 'val', ARGV[2], 'ver', cur + 1)
  redis.call('EXPIRE', KEYS[1], ARGV[3])
  return {1, cur + 1}                      -- success, new version
  """;

public CasResult put(RedisCommands<String,String> redis, String key,
                     long expectedVer, String value, int ttl) {
    List<Object> r = redis.eval(CAS, ScriptOutputType.MULTI,
            new String[]{ "obj:" + key },
            String.valueOf(expectedVer), value, String.valueOf(ttl));
    long ok = (Long) r.get(0);
    long ver = (Long) r.get(1);
    return new CasResult(ok == 1, ver);    // if !ok, refetch ver and retry
}
```

**Time/Space:** O(1), atomic, single round trip — no `WATCH`/retry round trips. **Why this beats WATCH (Q71):** `WATCH`-based CAS needs a read round trip, then the queued transaction, then a possible retry round trip on abort; the Lua CAS does the compare-and-set in one atomic server-side call, so under contention it's strictly fewer round trips and the conflict is reported synchronously with the current version (so the client can immediately refetch and retry without a second probe). **Edge cases:** a brand-new key has no `ver` (treated as 0 here) — decide whether a create requires `expectedVer == 0` (strict, prevents accidental overwrite of a key created concurrently) or whether any first-writer wins. The version is monotonic per key; if you also expose it to clients (e.g. as an HTTP `ETag`), this becomes a full optimistic-concurrency layer for a REST cache. For very hot keys this serializes writers on one slot (single key, single thread) which is the *point* — but it caps write throughput at one core, so for extreme write rates you'd shard the logical object or rethink the data model.

### 🔴 Expert — extended

#### Q76. [Practical] Design a multi-region active-active Redis architecture and reason about conflict resolution (CRDTs).

Active-active means clients in every region write to a *local* Redis and changes asynchronously replicate to the others, giving low local read **and write** latency — at the price of the hardest problem in distributed systems: concurrent conflicting writes to the same key in different regions. The mechanism that makes this tractable is **CRDTs (Conflict-free Replicated Data Types)**, used by Redis Enterprise's active-active (CRDB) and conceptually by other geo-replicated systems: each Redis data type is implemented as a CRDT whose merge function is **commutative, associative, and idempotent**, so regions converge to the same state regardless of the order replication messages arrive, with no coordination on the write path.

```
 us-east Redis  ◄── bidirectional async geo-replication ──►  eu-west Redis
      ▲                                                            ▲
   local writes (low latency)                              local writes (low latency)
   conflicts resolved by per-type CRDT merge (LWW string, OR-set, PN-counter, ...)
```

**The crucial part is knowing how each type resolves conflicts, because the merge semantics must match your domain.** A **counter** uses a PN-counter (each region tracks its own increments/decrements; the merged value is the sum) — so two regions both doing `INCRBY 1` concurrently correctly yields +2, *not* a lost update, which is why CRDT counters are a headline feature. A **string** typically uses **last-write-wins (LWW)** by timestamp — concurrent sets to the same string *lose* one write, acceptable for a cache value, dangerous for anything where both writes mattered. **Sets** use observed-remove (OR-set) semantics so concurrent add/remove converge predictably. The trade-offs to name: (1) you get AP with eventual convergence — never linearizability — so read-your-writes holds locally but cross-region a write takes replication-lag time to appear; (2) LWW on strings silently drops data, so model conflict-prone state as counters/sets where possible, or as separate per-region keys merged at read time; (3) clock skew affects LWW, so trustworthy timestamps matter; (4) it's a Redis Enterprise (commercial) capability, not open-source Redis/Valkey, which is itself a decision input. The senior framing: active-active buys local write latency and regional failure independence, but you must choose data types whose CRDT merge semantics are *correct for the business meaning of each key*, and explicitly accept that some conflicts resolve by discarding a write.

#### Q77. [Theory] Walk through exactly what happens on the wire and in the server during a `BLPOP` (blocking command), and how does it interact with the single thread?

Blocking commands (`BLPOP`, `BRPOP`, `BLMOVE`, `BZPOPMIN`, `XREAD BLOCK`, `WAIT`) seem paradoxical on a single-threaded server — how can a client "block" without freezing everyone? The answer reveals how Redis's event loop really works. When a client issues `BLPOP queue 5` and the list is empty, Redis does **not** spin or sleep the thread; instead it registers the client as **blocked on that key** in an internal data structure, returns control to the event loop, and goes on serving other clients. The blocked client's socket simply isn't read from again until it's unblocked — the thread is never parked.

```
client A: BLPOP q 5   (q empty)  ─► server adds A to "clients blocked on q"
                                    ─► event loop continues serving B, C, ...
client B: LPUSH q job            ─► server sees q has a blocked client
                                    ─► serves the value to A, removes A from blocked set
(or 5s passes)                    ─► timeout cron unblocks A with a nil reply
```

The unblock is push-driven: when another client does `LPUSH`/`RPUSH` (or `LMOVE` into) that key, the command handler checks the "ready keys" list and, *as part of that same command's execution*, delivers the element to the longest-waiting blocked client and wakes it (writes its reply). A separate timer handles the timeout — if no push arrives within the timeout, a server cron unblocks the client with a nil reply. This is why blocking commands don't violate the single-thread model: blocking is *bookkeeping in the event loop*, not a thread-level wait. The senior nuances: (1) FIFO fairness — multiple clients blocked on the same key are served in arrival order, so `BLPOP` gives a fair work-queue without polling; (2) inside a `MULTI`/`EXEC` or a Lua script, blocking commands **do not block** (they behave as if the timeout were zero / return immediately), because blocking mid-transaction would deadlock the single thread — a subtle gotcha if you wrap `BLPOP` in a transaction expecting it to wait; (3) in Cluster the blocking key must be on the node you're connected to, and a slot migration can interrupt a blocked client; (4) blocked clients still count toward `maxclients` and `blocked_clients` in `INFO`. Understanding this mechanism explains how Redis offers efficient producer/consumer queues with zero busy-polling on a single thread.

#### Q78. [Behavioral] Tell me about a time a Redis-related decision or incident went wrong, and what you changed afterward.

Use STAR and choose a story where *you owned a mistake or a hard call*, because the signal is ownership and the systems-thinking that came out of it. **Situation:** "We used a single Redis instance as both a cache and a lightweight job queue for an order-processing service. During a traffic spike, an engineer shipped a feature that wrote large per-order payloads into a list with no `MAXLEN` cap. Over a few hours that list grew to several gigabytes." **Task:** "I was on call when p99 latency for *every* Redis operation spiked and the order service started timing out — the symptom looked like a total cache outage, and we were losing orders." **Action:** "I diagnosed it with `redis-cli --bigkeys` and `SLOWLOG`, which pointed at the giant list and a periodic `LRANGE` over it that was blocking the single thread (the root cause was a blocking O(N) op on a big key, Q20/Q43). I stopped the bleeding by trimming the list with `LTRIM` and switching the read path off the blocking range scan; then I split the cache and the queue onto separate instances so a queue problem could never again degrade the cache. Afterward I added `MAXLEN` caps and TTLs on all collections, an alert on `--bigkeys` growth and on p99, and a code-review checklist item for unbounded collections." **Result:** "We recovered in about 20 minutes, backfilled the dropped orders from the upstream log, and had zero recurrences. More importantly, the post-incident changes — isolation of cache vs queue, bounded collections, and big-key alerting — became standard for every Redis deployment on the team." The reflective close interviewers want: "The deeper lesson was that I'd violated a principle I now hold firmly — *don't co-locate a cache and a durable workload on one single-threaded instance*, and *every collection needs a bound* — and that the failure was predictable in hindsight, which is exactly why the guardrails mattered more than the heroics."

#### Q79. [Practical] How would you migrate from `EVAL` scripts scattered across services to centralized Redis Functions with zero downtime?

This is a real modernization task once a codebase accumulates dozens of inline Lua scripts (Q42), and the migration must preserve behavior while moving logic from "shipped by every client" to "loaded once, called by name." The phased approach treats the function library as a deployable artifact decoupled from app releases. **Phase 1 — inventory and parity:** catalog every `EVAL`/`EVALSHA` across services, group identical/near-identical scripts, and rewrite each as a named function in a versioned Lua *library* (`#!lua name=app_v1`), preserving exact key/arg contracts and verifying each function produces byte-identical effects to the script it replaces (diff outputs against a replica replaying production-shaped inputs).

```
1. FUNCTION LOAD a versioned library:  #!lua name=app_v1  (register_function 'rateLimit', ...)
2. Deploy the library to all Redis nodes (it persists in RDB/AOF and replicates).
3. Dual-path apps: feature-flag each call site to use FCALL app_v1 'rateLimit' ... ,
   falling back to the old EVALSHA if the flag is off.
4. Flip flags per call site, monitor for errors / behavior diffs.
5. Remove the dead EVAL code; bump to app_v2 for future changes (load v2, repoint, drop v1).
```

**The sequencing matters because functions and scripts coexist cleanly:** you `FUNCTION LOAD` the library to every node first (it's persisted and replicated, surviving restarts and failover, unlike the per-server script cache), then repoint call sites behind flags so you can roll back instantly by flipping the flag back to `EVALSHA`. Edge cases and pitfalls: (1) **key declaration** — Functions still require correct `KEYS` passing for Cluster routing, so a script that implicitly assumed single-instance access must have its keys made explicit; (2) **library naming/versioning** — load `app_v2` alongside `app_v1` during transition rather than `REPLACE`-ing in place, so in-flight `FCALL`s to v1 don't break, then retire v1 after the cutover; (3) **deployment ordering** — the library must exist on a node *before* any app instance calls it, so library load is an infra/migration step gated ahead of the app deploy, and you must handle a node that joins/resyncs (Functions replicate, but verify after topology changes with `FUNCTION LIST`); (4) **CI** — add a step that loads the library into a test Redis and runs the function contract tests, making the library a first-class tested artifact. The senior framing: the win is operational — logic becomes centrally managed, versioned, replicated, and testable instead of duplicated across services — and zero-downtime comes from the fact that Functions and EVAL can run side by side, letting you migrate call site by call site behind flags with instant rollback.

#### Q80. [Theory] Explain how Redis Cluster handles resharding and the `ASK` vs `MOVED` redirection during slot migration.

Resharding moves hash slots (and their keys) between primaries — to add a node, rebalance, or drain a node — and it happens **online** while clients keep reading and writing, which forces a precise protocol so no command is misrouted or lost mid-migration. A slot being migrated is, for a window, *partly* on the source node and *partly* on the destination: keys are moved one batch at a time via `MIGRATE` (a `DUMP` on source + `RESTORE` on destination + `DEL` on source, atomic per key). During that window the cluster must answer "which node has *this specific key* right now?" differently from "which node owns this slot permanently?" — which is exactly the `ASK`/`MOVED` distinction.

```
MOVED 3999 10.0.0.5:6379   -> "slot 3999 PERMANENTLY belongs to that node; update your map"
ASK   3999 10.0.0.6:6379   -> "this slot is migrating; for THIS request only, go ask that node
                               (and prefix the command with ASKING). Do NOT update your map."
```

**`MOVED`** is the steady-state redirect: the client hit the wrong node for a slot it doesn't own, so the client updates its cached slot→node map and won't make that mistake again. **`ASK`** is the *transient* migration redirect: the source node still owns the slot but *this particular key* has already been migrated to the destination, so it tells the client to retry this one command on the destination, **prefixed with `ASKING`** (which tells the destination "yes, serve this key from a slot you're importing even though you don't fully own it yet"). The client must **not** update its map on `ASK`, because the slot's ownership hasn't actually changed yet — only after the migration completes does the cluster issue a `SETSLOT ... NODE` that flips ownership and subsequent requests get `MOVED`. The subtle correctness points: (1) a multi-key command touching keys that straddle the migration boundary returns `TRYAGAIN` and must be retried; (2) keys are migrated individually, so during migration a `GET` for an already-moved key gets `ASK` while a `GET` for a not-yet-moved key in the same slot is served locally; (3) smart clients implement both redirects plus the `ASKING` handshake, which is why you should use a cluster-aware client and never assume a static topology. The senior framing: `MOVED` = permanent map update, `ASK` = one-shot detour during in-flight migration — this two-tier redirection is what lets Redis Cluster reshard live without a stop-the-world pause, and understanding it explains the occasional `ASK`/`TRYAGAIN`/`CROSSSLOT` errors you see in logs during rebalancing.

#### Q81. [Coding] Implement a sharded counter to defeat write contention on a single hot key.

**Problem:** A globally trending item's view counter receives tens of thousands of `INCR`s per second. Even though `INCR` is O(1), every write serializes on one key → one slot → one CPU core (Q35), capping throughput and creating a Cluster hot slot. The fix is to spread writes across N physical counters and sum them on read.

Writers pick a random shard `0..N-1` and `INCR` that shard's key; readers sum all N shards. To spread across Cluster *slots* (not just keys), the shard index must be *outside* any common hash tag so CRC16 sends shards to different nodes.

```java
private static final int SHARDS = 16;

public void increment(RedisCommands<String,String> redis, String item) {
    int shard = ThreadLocalRandom.current().nextInt(SHARDS);
    // NO shared hash tag -> shards hash to different slots/nodes
    redis.incr("views:" + item + ":" + shard);
}

public long total(RedisCommands<String,String> redis, String item) {
    long sum = 0;
    for (int i = 0; i < SHARDS; i++) {
        String v = redis.get("views:" + item + ":" + i);
        if (v != null) sum += Long.parseLong(v);
    }
    return sum;                                   // exact total across shards
}
```

**Time/Space:** writes O(1) and now parallelizable across N cores/nodes; reads O(N) round trips (or O(1) with an `MGET`/pipeline if shards share a slot — but that re-centralizes them, so accept the N reads or read less often). Memory is N small keys per item. **Edge cases and trade-offs:** the core tension is **write scalability vs read cost** — more shards spread writes further but make reads do more work, so size N to your write hotness (16–256 is typical) and cache the summed total in a separate key with a short TTL if reads are also frequent. The shard count is effectively fixed once chosen (changing N orphans old shards' counts unless you migrate), so pick generously. For reads, in Cluster you can't `MGET` across slots, so either issue N parallel `GET`s (pipelined per-node) or accept sequential reads; alternatively keep all shards in *one* slot via a hash tag to enable `MGET`, trading the cross-node write spread for cheap reads — the right choice depends on whether the bottleneck is write contention (spread across nodes) or read latency (co-locate). This is the canonical answer to "how do you scale a counter past one core," and recognizing that a single atomic key is itself a scalability ceiling is the senior insight.

#### Q82. [Theory] What are the failure and consistency implications of reading from replicas, and how do you make replica reads safe?

Routing reads to replicas (`READONLY` mode in Cluster, or replica endpoints with Sentinel) is the standard way to scale read throughput (Q45), but it trades consistency for capacity in ways that bite if you're not explicit. Because replication is **asynchronous**, a replica is always some amount behind the primary — its `master_repl_offset` lags — so a read from a replica can return a **stale value** or even **miss a key** that the primary already has. The classic failure is **read-your-own-writes violation**: a client writes to the primary, immediately reads from a replica, and gets the *old* value (or nothing) because the write hasn't propagated yet.

```
client ─WRITE k=v2─► PRIMARY (now k=v2)
client ─READ  k───► REPLICA (still k=v1, lag not yet caught up)   -> stale!
```

Several techniques make replica reads safe *for the cases that need it* while still offloading the rest: (1) **route reads that must be fresh to the primary** and only send tolerant reads (analytics, "good enough" lookups) to replicas — the simplest and most robust rule; (2) **session/monotonic routing** — pin a user's session to the primary for a short window after they write, so their own subsequent reads are consistent (read-your-writes for that user); (3) **`WAIT numreplicas timeout` after a critical write** (Q67) so you only read from a replica once you've confirmed it has the write — at a latency cost; (4) **stale-read budgeting** — accept and *bound* staleness by alerting on replication lag (`master_repl_offset` minus the replica's offset) and treating a replica that lags beyond a threshold as unhealthy and removing it from the read pool. Additional failure modes: during a **failover**, in-flight reads to a now-demoted node may error or read a node that's resyncing; a replica doing a full resync (`PSYNC` full) can serve from an old dataset or briefly refuse reads. The senior framing: replica reads are a *throughput* tool with an *eventual-consistency* tax — never blanket-route all reads to replicas, classify each read by its staleness tolerance, keep must-be-fresh reads (and read-after-write paths) on the primary or gate them with `WAIT`, and monitor replication lag as a first-class signal because a lagging replica silently widens the staleness window and the failover lost-write window simultaneously.

#### Q83. [Practical] Design a rate-limiting service that supports multiple algorithms and is itself horizontally scalable and resilient.

A production rate-limiting *service* (think an API gateway component) is more than the single-algorithm snippets (Q10/Q38) — it must support several policies, be correct under a distributed fleet of gateway nodes, and fail in a defined way when Redis is degraded. The architecture centers on **Redis as the shared state store** (because limits must be enforced *across* all gateway nodes, not per-node) with the decision logic in **atomic Lua/Functions** so concurrent requests across nodes can't oversubscribe a limit.

```
   gateway node 1 ┐
   gateway node 2 ┼─► FCALL ratelimit <policyKey> <now> <args>  ─► Redis (shared state)
   gateway node 3 ┘        returns: allowed | retry-after
   policies: fixed-window | sliding-log (ZSet) | sliding-counter | token-bucket | leaky-bucket
   degraded mode: Redis slow/down -> local fallback (fail-open or fail-closed per policy)
```

**Key design decisions to articulate:** (1) **Algorithm selection per policy** — store each limit policy's type and parameters (e.g. in a config hash) and dispatch to the matching Lua function; offer token-bucket for smooth bursts (Q38, O(1) memory), sliding-log ZSet for precise rolling windows (Q10, higher memory), and the cheaper **sliding-window-counter** (two fixed-window counters interpolated) as a memory/precision compromise. (2) **Atomicity across the fleet** — every check-and-decrement is one server-side atomic call so two gateway nodes hitting the same key simultaneously can't both pass the 100th request; this is non-negotiable and is why the logic lives in Redis, not the gateway. (3) **Key design and sharding** — keys like `rl:{apiKey}:route` distribute across Cluster slots so the limiter itself scales horizontally; beware a single mega-tenant becoming a hot key (apply Q81 sharding for an extremely hot limit, summing approximately). (4) **Degraded behavior is a product decision** — if Redis is slow or unreachable, do you **fail-open** (allow traffic, prioritizing availability — typical for soft limits) or **fail-closed** (reject, prioritizing protection — for abuse/DDoS limits)? Make it configurable per policy, use a tight Redis timeout, and keep a per-node local limiter as a coarse backstop so a Redis outage doesn't either let infinite traffic through or block everything. (5) **Observability** — return `X-RateLimit-Remaining`/`Retry-After`, and emit metrics on allow/deny rates and Redis latency. The senior framing: the hard parts aren't the algorithms (those are small Lua scripts) but the *systemic* ones — enforcing limits atomically across a distributed gateway fleet, scaling the limiter's own keys without hot spots, and deciding the fail-open vs fail-closed posture per policy so the limiter degrades predictably when its backing store does.

#### Q84. [Theory] How do you reason about and prevent data loss specifically during AOF rewrite, RDB save, and an unexpected restart?

Durability gaps appear at the exact moments Redis is doing persistence work, so a precise mental model of each path matters. **During normal operation with `appendonly yes` + `appendfsync everysec`:** writes are appended to an in-memory AOF buffer and fsynced to disk roughly once per second, so an OS/process crash loses *up to ~1 second* of writes — the well-known bounded window. `appendfsync always` fsyncs every write (durable but throughput-crushing); `no` lets the OS decide (largest window). **During AOF rewrite:** Redis forks (Q39); the child writes a compact new AOF from the dataset snapshot while the parent keeps appending new writes to *both* the old AOF and an in-memory **rewrite buffer**; on completion the buffer is appended to the new file and it atomically replaces the old — so a crash mid-rewrite simply falls back to the old AOF, losing nothing beyond the normal fsync window. **During RDB `BGSAVE`:** the child writes a point-in-time snapshot; a crash mid-save leaves the *previous* completed RDB intact (the new one is written to a temp file and renamed atomically) — but any writes since the last *completed* snapshot are absent from RDB (that's why RDB-only is lossy and you pair it with AOF).

```
appendfsync everysec timeline:
  write ─► AOF in-memory buffer ─► (fsync every ~1s) ─► disk
  crash here ──────────────────────────────────────┘ loses up to ~1s of writes
Multi-part AOF (Redis 7): base RDB + incremental AOF; rewrite = new base, cheaper, same safety
```

**The restart and failover interactions are where people get surprised:** (1) on restart Redis loads the **AOF if `appendonly yes`** (more complete) else the RDB — so if you run RDB-only thinking you're durable, you lose everything since the last snapshot on a crash; (2) a write acknowledged in memory but not yet fsynced *and* not yet replicated is lost on a primary crash even with AOF (the fsync window) — `WAITAOF` (Q67) is how you force a specific write to disk before acking the user; (3) `no-appendfsync-on-rewrite yes` (a tuning option) tells Redis to *skip* fsync while a rewrite/save is in progress to avoid disk contention — which trades a larger loss window during rewrites for lower latency, a trade you must make consciously; (4) a full disk or an I/O stall makes AOF appends fail — monitor `aof_last_write_status` and `aof_delayed_fsync` (Q55), because a silently failing AOF means you *think* you're durable but aren't. The senior framing: the rewrite and save paths are designed to be *crash-safe* (atomic rename, fallback to the prior file), so they don't *widen* the loss window beyond the normal fsync window — the real data-loss risks are (a) the inherent fsync window of your `appendfsync` setting, (b) running RDB-only and mistaking it for durability, (c) the unreplicated-and-unfsynced window on failover, and (d) silent AOF write failures from disk problems. Bound each one explicitly, alert on the persistence status fields, and use `WAITAOF` for the few writes that truly cannot be lost.

#### Q85. [Coding] Implement a "fetch-or-compute with single-flight + stale-while-revalidate" cache wrapper.

**Problem:** Combine three production cache behaviors into one wrapper: serve cached values fast, recompute only *once* across the whole fleet when a value is missing/expired (single-flight, defeating stampede from Q13), and **serve a slightly stale value while a background refresh runs** (stale-while-revalidate) so users never wait on a recompute. The naive cache-aside lacks all three.

We store the value with a *logical* expiry timestamp embedded alongside it (so we can detect "stale but usable" vs "absent"), use `SET NX` on a short-lived lock so only one caller recomputes, and let others return the stale value immediately.

```java
public String getOrCompute(RedisCommands<String,String> redis, String key,
                           Supplier<String> compute, long freshMs, long staleMs) {
    String vk = "cache:" + key, lk = "lock:" + key;
    String raw = redis.get(vk);                          // "expiresAt|payload"
    long now = System.currentTimeMillis();

    if (raw != null) {
        long expiresAt = Long.parseLong(raw.substring(0, raw.indexOf('|')));
        String payload = raw.substring(raw.indexOf('|') + 1);
        if (now < expiresAt) return payload;             // fresh: return immediately
        // STALE: try to become the single refresher; others get stale value now
        boolean iRefresh = redis.set(lk, "1", SetArgs.Builder.nx().px(5000)) != null;
        if (iRefresh) {
            CompletableFuture.runAsync(() -> {           // refresh in background
                try { store(redis, vk, compute.get(), freshMs, staleMs); }
                finally { redis.del(lk); }
            });
        }
        return payload;                                  // serve stale while revalidating
    }
    // MISS (no value at all): single-flight synchronous fill
    if (redis.set(lk, "1", SetArgs.Builder.nx().px(5000)) != null) {
        try { String v = compute.get(); store(redis, vk, v, freshMs, staleMs); return v; }
        finally { redis.del(lk); }
    }
    // someone else is computing the first value; brief wait then re-read
    return waitAndReread(redis, vk);
}
private void store(RedisCommands<String,String> r, String vk, String v,
                   long freshMs, long staleMs) {
    long expiresAt = System.currentTimeMillis() + freshMs;
    r.psetex(vk, freshMs + staleMs, expiresAt + "|" + v);   // hard TTL > logical fresh window
}
```

**Time/Space:** O(1) per call; one value key plus a transient lock key per recomputed entry. **Why each piece matters:** the **logical `expiresAt` inside the value** (with a *longer* physical TTL) is what enables stale-while-revalidate — Redis still holds the value past its freshness so we can serve it during the refresh, and the physical TTL is the hard backstop. The **`SET NX` lock** is single-flight: only the lock winner recomputes, so a hot key expiring doesn't trigger a thundering herd to the database (Q13). Serving the **stale payload to everyone else** means latency stays flat during refreshes — users never block on a recompute. **Edge cases:** the lock TTL must exceed the expected compute time, or a second refresher could start (acceptable, just slightly wasteful) — and the lock holder crashing mid-refresh is bounded by the lock's own TTL, after which another caller refreshes. On a true *miss* (no stale value to serve), non-winners must briefly wait and re-read rather than all hitting the DB. Add TTL **jitter** to `freshMs` to avoid synchronized expiry (avalanche, Q13). This wrapper encodes the three patterns most production caches eventually need; presenting it shows you understand that "cache-aside" alone is insufficient under real load.

#### Q86. [Theory] How does the Lua/Functions sandbox restrict scripts, and what are the security and correctness reasons?

Server-side scripts run *inside the Redis process* on the single execution thread, so an unrestricted scripting environment would be both a security hole and a correctness hazard — Redis therefore runs Lua in a **deliberately stripped sandbox**. Dangerous standard libraries are removed or restricted: there is **no file I/O** (`io`, `os` are absent/neutered), **no network access**, no loading of arbitrary Lua modules (`require` is blocked), and no access to the host environment — a script cannot read `/etc/passwd`, open a socket, or shell out, which matters because a compromised client that can run `EVAL` would otherwise have code execution inside your data store. Scripts interact with the world *only* through `redis.call`/`redis.pcall` (issuing Redis commands) and their `KEYS`/`ARGV` inputs and return value.

```
Allowed inside a script:  redis.call/pcall, KEYS[], ARGV[], cjson, cmsgpack,
                          redis.sha1hex, redis.status_reply/error_reply, bit ops, struct
Blocked/absent:           io.*, os.execute, file access, require/module loading,
                          network, and (historically) nondeterministic calls before writes
```

The restrictions serve three goals. **Security:** the sandbox prevents script-based RCE, data exfiltration, and host compromise from anyone who can issue `EVAL` — which is also why `EVAL`/`FUNCTION` should be gated by ACLs (`@scripting`, Q46) so untrusted users can't run arbitrary server-side code. **Correctness/determinism (Q70):** by removing access to the clock, filesystem, and network, the sandbox steers scripts toward determinism so replication and AOF replay stay consistent; the provided `cjson`/`cmsgpack` give serialization without external calls, and randomness/time must be passed in rather than read. **Single-thread safety:** because the script holds the one execution thread for its entire duration, the sandbox can't offer anything that blocks (no sleeps, no I/O waits), and `lua-time-limit`/`busy-reply-threshold` bound runaway scripts — after the limit, Redis replies `BUSY` to other clients and you can `SCRIPT KILL` (if no writes happened) or must `SHUTDOWN NOSAVE` (if writes happened, since killing mid-write would corrupt state). The senior framing: the sandbox is the reason it's *safe* to let clients ship code into the database at all, and its restrictions (no I/O, no network, no nondeterminism, bounded time) map directly onto the three things Redis must protect — the host's security, replication's determinism, and the single thread's responsiveness; respecting those constraints (keep scripts fast, deterministic, pure-Redis, and ACL-gated) is what keeps scripting from becoming a foot-gun.

#### Q87. [Practical] Your team wants to use Redis as a primary database for a new service. How do you evaluate and de-risk that decision?

This comes up often and the senior move is neither a reflexive "no" nor a rubber-stamp — it's a structured evaluation against Redis's actual guarantees. I'd frame it around the data's requirements versus what Redis provides. **Durability:** Redis's strongest setting (`appendfsync always` + `WAITAOF` + replication) still has a failover lost-write window and is not linearizable (Q52/Q84) — so the first question is "can this data tolerate losing the last few writes on a failover?" If it's session state, leaderboards, real-time counters, or a derived/reconstructable view → yes, Redis-as-primary is reasonable. If it's payments, ledgers, or anything where a single lost write is a correctness/compliance failure → no, and I'd say so plainly. **Working-set size and cost:** the entire dataset (plus overhead, Q60) must fit affordably in RAM across the cluster; if it's hundreds of GB to TB of cold-ish data, a disk-native store is far cheaper.

```
De-risking checklist before "yes":
  [ ] Loss tolerance: is the failover lost-write window acceptable for THIS data?
  [ ] Size: working set + overhead fits in RAM at acceptable cost? growth runway?
  [ ] Queries: only key/structure access, or do we need joins/ad-hoc/secondary indexes?
  [ ] Consistency: any need for multi-key/cross-shard ACID? (Cluster can't)
  [ ] Persistence: AOF everysec + RDB + tested restore + offsite backups in place?
  [ ] Ops: HA (Sentinel/Cluster), monitoring (Q55), capacity alerts, runbooks?
  [ ] Schema evolution: payload versioning strategy (Q15) since there's no migration tool?
```

**Queries and access patterns:** Redis has no general query engine — if the service needs ad-hoc queries, joins, secondary indexes, or reporting, you either bolt on RediSearch (RAM-resident inverted index, its own cost/license, Q48) or you've chosen the wrong store. **The de-risking plan if we proceed:** enable AOF (`everysec`) + RDB, prove backups by *restoring* them into a scratch instance, deploy HA (Sentinel or Cluster with replicas), set up the monitoring/alerting from Q55 (memory ratio, replication lag, persistence status, fork time), define the payload-versioning scheme since there's no schema-migration tooling (Q15), and load-test at projected peak including failover drills to *measure* the actual lost-write window and recovery time. I'd also pilot it on a **bounded, non-critical** dataset first to build operational muscle. The senior framing: "Redis as a primary database is legitimate for the right data — fast, ephemeral-or-reconstructable, RAM-fitting, key/structure-accessed state — and a serious mistake for durable, consistency-critical, query-rich, or large-cold data. The evaluation is matching this *specific* data's durability/consistency/size/query needs to Redis's *actual* guarantees, and de-risking means proving durability and recovery empirically (tested backups, failover drills, measured loss window) rather than trusting that 'we turned on AOF.'"

#### Q88. [Coding] Implement an exactly-once event-processing consumer over Redis Streams with idempotent handling and dead-lettering.

**Problem:** Build a robust Streams consumer (Q57) that processes each event at-least-once from the transport but achieves *effectively exactly-once* business outcomes via idempotency, reclaims work from crashed consumers, and dead-letters poison messages after N attempts.

The consumer reads new messages with `XREADGROUP`, processes idempotently (dedup by event id), `XACK`s on success, and a periodic reclaim loop uses `XAUTOCLAIM` to recover stuck messages — checking the delivery count to dead-letter repeated failures.

```java
public void runOnce(RedisCommands<String,String> redis, String stream,
                    String group, String consumer) {
    // 1) New messages for this consumer
    List<StreamMessage<String,String>> msgs = redis.xreadgroup(
        Consumer.from(group, consumer),
        XReadArgs.Builder.count(10),
        XReadArgs.StreamOffset.lastConsumed(stream));     // ">" : never-delivered
    for (StreamMessage<String,String> m : msgs) processWithIdempotency(redis, stream, group, m);

    // 2) Reclaim messages idle > 60s from dead/slow consumers, claim up to 10
    var claimed = redis.xautoclaim(stream,
        XAutoClaimArgs.Builder.xautoclaim(Consumer.from(group, consumer), 60_000, "0").count(10));
    for (StreamMessage<String,String> m : claimed.getMessages())
        processWithIdempotency(redis, stream, group, m);
}

private void processWithIdempotency(RedisCommands<String,String> redis, String stream,
                                    String group, StreamMessage<String,String> m) {
    String dedupKey = "done:" + stream + ":" + m.getId();
    // Claim the side-effect exactly once (Q44 pattern)
    if (redis.set(dedupKey, "1", SetArgs.Builder.nx().ex(86400)) == null) {
        redis.xack(stream, group, m.getId());             // already handled -> just ack
        return;
    }
    try {
        long deliveries = deliveryCount(redis, stream, group, m.getId());  // via XPENDING
        if (deliveries > 5) {                              // poison: dead-letter it
            redis.xadd(stream + ":dlq", m.getBody());
            redis.xack(stream, group, m.getId());
            return;
        }
        handle(m.getBody());                               // the real side effect
        redis.xack(stream, group, m.getId());              // ack only after success
    } catch (Exception e) {
        redis.del(dedupKey);                               // allow retry; stays in PEL
        // not acked -> remains pending, reclaimed later
    }
}
```

**Time/Space:** O(1) per message for the hot path; PEL grows with un-acked work. **Why each guard exists:** Streams give *at-least-once* transport (a crash between processing and `XACK` causes redelivery), so true exactly-once requires **idempotent handling** — the `SET NX` dedup key ensures the side effect runs once even across redeliveries and across consumers that reclaim the message. The order matters: ack **only after** the side effect succeeds, and on failure **delete the dedup key** so the retry can re-run (otherwise a transient failure would permanently skip the message). **`XAUTOCLAIM`** (Q57) recovers messages a crashed consumer left in its PEL by stealing those idle beyond a timeout. The **delivery counter** (from `XPENDING`) drives **dead-lettering** — a message that keeps failing is routed to a DLQ stream and acked so it stops blocking the group. **Edge cases:** the dedup-key TTL must exceed the maximum redelivery horizon (longer than your reclaim window × max retries) or a late reclaim could double-process; if the side effect *itself* must be idempotent downstream (e.g. a payment), key *that* by the same event id too, since Redis can only dedup what it sees; and trim the stream (`MAXLEN`, Q57) while ensuring you don't trim messages still pending in the PEL. The senior framing: "exactly-once" is achieved by composing at-least-once delivery (Streams + PEL + reclaim) with idempotent effects (dedup key), plus a dead-letter escape hatch for poison messages — there is no magic exactly-once transport, only at-least-once plus idempotency, and this consumer makes that composition explicit and crash-safe.

#### Q89. [Theory] Compare the consistency models of Redis (standalone, Sentinel, Cluster) and explain where each sits on CAP/PACELC.

A precise CAP/PACELC placement separates a staff-level answer from hand-waving, and the key fact is that **Redis is fundamentally an AP system** across all its topologies because replication is asynchronous — but the nuances differ per deployment. **Standalone (single instance):** trivially *consistent* in the sense that there's one copy and one thread serializing all operations (so individual operations are linearizable *as long as the node is up*), but it offers **no availability** under node failure — it's a CP-degenerate case where "P" doesn't really apply (no partition between replicas because there are none). The single thread gives you a total order of operations, which is why atomic primitives work, but a crash loses everything since the last persist.

```
              CAP under partition        PACELC (else-latency-vs-consistency)
Standalone    no replicas (N/A)          EL — optimized for latency, single copy
Sentinel      AP (favors availability)   PA/EL — async replicate; failover may lose writes
Cluster       AP per shard               PA/EL — async; CROSSSLOT removes cross-shard ACID
```

**Sentinel (HA, one logical dataset):** under a partition it chooses **availability over consistency (AP)** — it will promote a replica to keep serving writes, accepting the lost-write window and possible split-brain (Q52) rather than blocking. In **PACELC** terms it's **PA/EL**: under Partition it favors Availability, and Else (normal operation) it favors Latency (acking before replication) over Consistency. You can *shift toward C* with `min-replicas-to-write` (refuse writes without enough replicas — sacrificing availability on the minority side) and `WAIT`/`WAITAOF` (per-write stronger durability), but you never reach linearizability. **Cluster (sharded):** each shard behaves like a Sentinel-managed dataset, so it's **AP per shard** with the same PA/EL character — *plus* a structural consistency limitation orthogonal to CAP: **no cross-slot atomicity or transactions** (Q16), so even *within* a single moment with no partition, you cannot get a consistent multi-key view across shards. The senior framing to deliver: "Redis is AP/PA-EL by default in every replicated form — it acknowledges writes before replicating (favoring latency) and stays available under partition by failing over (favoring availability), at the cost of a lost-write window and no linearizability. You can *dial toward* consistency/durability with `min-replicas-to-write`, `WAIT`/`WAITAOF`, and primary-only reads, but you cannot reach a CP/linearizable model — and Cluster additionally forfeits cross-shard atomicity entirely. So if a workload genuinely needs linearizable, durable, multi-key-consistent semantics, Redis is structurally the wrong tool regardless of tuning; use a consensus-backed store. Redis's design *chose* AP because its target workloads (cache, ephemeral state) value latency and availability over the guarantees a system of record needs."

#### Q90. [Practical] You discover Redis memory grows unbounded in production despite TTLs being set. Walk through diagnosing and fixing it.

Unbounded growth *despite* TTLs is a specific, common production puzzle, and the senior move is a systematic hunt because "TTLs are set" can be true yet ineffective for several distinct reasons. I'd start by confirming whether keys actually *have* TTLs and whether they're being honored, then localize the growth.

```bash
redis-cli INFO keyspace          # db0:keys=N,expires=M  -> are M (with-TTL) << N?
redis-cli DBSIZE                  # total keys; compare growth over time
redis-cli --scan | head           # sample keys; redis-cli TTL <key> on samples -> -1 = no TTL!
redis-cli --bigkeys               # is growth in ONE big collection, not key count?
redis-cli INFO stats | grep -E 'expired_keys|evicted_keys'   # are expirations happening?
redis-cli MEMORY STATS            # dataset vs overhead (buffers, repl backlog, fragmentation)
```

**The likely root causes, each with a distinct fix:** (1) **TTL silently cleared by a plain `SET`** (Q4/Q63) — code writes the key initially with a TTL, then an update path does `SET key newval` *without* `KEEPTTL`, removing the expiry; `TTL key` returning `-1` on keys you expected to expire is the tell. *Fix:* use `KEEPTTL` or re-set the TTL on every write. (2) **Growth is inside a *collection*, not in key count** — a list/set/hash/ZSet/stream with a TTL on the *key* still grows unboundedly *within* its TTL window if you keep appending and never trim; `--bigkeys` shows one key ballooning while `DBSIZE` is flat. *Fix:* `LTRIM`/`MAXLEN`/`ZREMRANGEBYRANK` to bound the collection (the unbounded-collection anti-pattern, Q51). (3) **A code path creates keys *without* TTL** — `INFO keyspace` showing `keys` far exceeding `expires` means many keys never got an expiry (e.g. the `INCR`-then-`EXPIRE` race from Q5 where the `EXPIRE` was skipped, or a new write path that forgot it). *Fix:* set TTL atomically (`SET ... EX`) and add a defensive `allkeys-lru`/`volatile-ttl` eviction backstop. (4) **Non-dataset memory** — `MEMORY STATS` reveals growth in the **replication backlog**, **client output buffers** (a slow consumer / Pub/Sub subscriber buffering unbounded — check `client-output-buffer-limit`), or **fragmentation** (high `mem_fragmentation_ratio` → `activedefrag`), none of which TTLs touch. (5) **Expiration not keeping up** — under extreme key volume the active-expire cycle samples lazily, so expired-but-not-yet-deleted keys accumulate (Q4); `expired_keys` lagging key creation rate is the signal, and the fix is reducing churn or accepting eviction. The systematic framing I'd give: "First prove the assumption — `INFO keyspace` (`expires` vs `keys`) and spot-check `TTL` on real keys — because 'TTLs are set' is usually false for *some* write path (a `SET` clobbering TTL, or a forgotten `EXPIRE`). If TTLs *are* present, the growth is either *inside* a collection (needs trimming, not key expiry) or *outside* the dataset (buffers/backlog/fragmentation via `MEMORY STATS`). Each cause has a different fix, so the diagnosis order — verify TTLs, then key-count vs collection-size, then dataset vs overhead — is what turns a vague 'memory leak' into a specific, fixable defect, and a `maxmemory` + eviction policy is the safety net that converts a silent OOM outage into bounded, observable eviction while you fix the root cause."

## ✅ Key Takeaways

- Redis is fast because it's **in-memory, single-threaded for commands, and data-structure aware** — pick the data type that lets the server do the work (`INCR`, `ZADD`, Hash field updates).
- **Persistence is a spectrum:** RDB (fast snapshots, lossy) ↔ AOF (`everysec` durable). For a cache, you may need neither; for a datastore, run both.
- Set an explicit **`maxmemory-policy`** (`allkeys-lru`/`lfu` for caches); leaving `noeviction` without TTLs is a classic OOM outage.
- **Pipelining/MGET** collapse round trips; **Lua scripts** give atomic read-modify-write better than `WATCH/MULTI` retries.
- **Sentinel = HA for one dataset; Cluster = sharding (16384 slots) + HA.** Cluster has no cross-slot atomicity — use hash tags to co-locate keys.
- For caching, master **cache-aside + invalidation** and defend against **stampede, penetration, avalanche** (single-flight lock, negative caching/Bloom filter, TTL jitter).
- **Distributed locks:** `SET NX PX` + unique token + Lua release for *efficiency*; for *correctness* use fencing tokens / a consensus store — Redlock is debated.
- Know **when Redis is wrong**: huge datasets, rich queries, strong cross-shard consistency, or sole system-of-record for critical data.

## ⚠️ Common Pitfalls

- Running `KEYS *` in production (O(N), blocks the single thread) instead of `SCAN`; likewise `DEL` on a big key instead of `UNLINK`.
- `SETNX` then a separate `EXPIRE` — non-atomic; a crash between them leaves an eternal lock/key. Use `SET ... NX PX`.
- Forgetting that a plain `SET` clears an existing TTL (use `KEEPTTL`).
- Assuming MULTI/EXEC rolls back on error — it does **not**; runtime command errors don't abort the transaction.
- All keys expiring at the same instant → avalanche; always jitter TTLs.
- Assuming read-your-writes across a primary/replica split — replication is async; use `WAIT` or read from the primary.
- Storing giant collections in one key, creating hot slots in Cluster and blocking operations.
- Exposing Redis to the internet with no auth — a well-known mass-exploitation vector; bind privately, use ACLs + TLS.
- Treating Redis as durable for critical data — failover has a lost-write window even with AOF.
- Using JDK serialization (slow, version-fragile, deserialization security risk) — prefer JSON/Protobuf.

## 📚 Further Reading

- **Redis official documentation** — https://redis.io/docs/ (data types, persistence, cluster, ACL, scripting).
- **"Redis in Action"** — Josiah Carlson (Manning) — practical patterns, queues, locks, and structures.
- **"Designing Data-Intensive Applications"** — Martin Kleppmann (O'Reilly) — replication, consistency, and the systems context for caching/locking.
- **"How to do distributed locking"** — Martin Kleppmann, and **antirez's rebuttal** — the canonical Redlock debate.
- **Redis Cluster Specification** — https://redis.io/docs/latest/operate/oss_and_stack/reference/cluster-spec/ — hash slots, gossip, resharding.
- **Valkey project docs** — https://valkey.io/ — the Linux Foundation fork and its multi-threading roadmap (post-2024 license change).
