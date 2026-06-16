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
