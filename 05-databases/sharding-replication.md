# Sharding & Replication Strategies

A deep-dive interview guide on how databases scale **out** (sharding/partitioning) and stay **available & durable** (replication). Covers partitioning schemes, shard-key design, rebalancing, replication topologies, consistency trade-offs, distributed SQL, and Change Data Capture (CDC) — current through 2026.

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

### Q1. [Theory] What is the difference between replication and sharding (partitioning)?

**Replication** keeps *copies* of the same data on multiple nodes. Its goals are **availability** (survive node loss), **read scalability** (serve reads from replicas), and **durability/latency** (place copies near users). Every replica eventually holds the *same* dataset.

**Sharding** (horizontal partitioning) splits *one* logical dataset into disjoint subsets, each living on a different node, so that the total data and write throughput exceed what a single machine can hold. No single node has all the data.

They are orthogonal and almost always combined: you shard to spread the data, then replicate each shard for fault tolerance.

```
                Replication (copies)        Sharding (splits)
                +-----+   +-----+           +--------+ +--------+
   All data --> | A   |   | A'  |   Subset->| 1..1M  | | 1M..2M |
                +-----+   +-----+           +--------+ +--------+
                leader    replica            shard 1    shard 2

   Production = both:  Shard1{leader + 2 replicas}, Shard2{leader + 2 replicas}
```

### Q2. [Theory] Vertical vs horizontal partitioning — what's the difference?

**Vertical partitioning** splits a table by **columns** (or splits a schema by *feature/table*). Example: move rarely-read `bio` and `avatar_blob` columns of a `users` table into a separate `user_profiles` table, keeping the hot `id, email, status` columns in a narrow, cache-friendly table. At the service level, "vertical partitioning" often means giving each microservice its own database.

**Horizontal partitioning (sharding)** splits a table by **rows** — rows 1–1M on node A, 1M–2M on node B — keeping the same schema everywhere.

```
 Vertical (by column)                Horizontal (by row)
 +----+-------+----------+           +----+-------+
 | id | email |  bio[]   |           | id | email |  Shard A: id 1..1M
 +----+-------+----------+           +----+-------+
   |  hot  |   cold (split off)      | id | email |  Shard B: id 1M..2M
```

Vertical partitioning has a hard ceiling (you run out of columns/tables to peel off); horizontal scales effectively without bound, at the cost of cross-shard complexity.

### Q3. [Theory] What is a shard key (partition key) and why does it matter so much?

The **shard key** is the column(s) whose value decides which shard a row lives on. It is the single most consequential design decision in a sharded system because it determines:

1. **Data distribution** — a bad key creates "hot shards" holding disproportionate data/traffic.
2. **Query routing** — queries that include the shard key hit exactly one shard (fast, "shard-pruned"); queries without it must **scatter-gather** across all shards.
3. **Re-sharding pain** — changing the shard key later usually means rewriting the entire dataset.

A good key has **high cardinality**, **even access distribution**, and **aligns with your most common query's filter**. Example: for a multi-tenant SaaS, `tenant_id` is often ideal because most queries are tenant-scoped.

### Q4. [Theory] What is a read replica and when do you use one?

A read replica is a copy of the primary (leader) that serves **read-only** queries. You add read replicas when read traffic dominates write traffic (common — many systems are 90%+ reads) and you want to offload analytics, reporting, or read-heavy endpoints from the primary.

Trade-off: replicas are usually **asynchronous**, so they lag behind the primary by milliseconds to seconds. A user who writes then immediately reads from a replica may not see their own change (**read-your-writes** violation). You mitigate this by routing "just wrote" reads to the primary or by using a sufficiently fresh/synchronous replica.

### Q5. [Practical] Your single Postgres instance is at 90% CPU. Walk through the scaling steps you'd try, in order.

Cheapest/lowest-risk first:

1. **Optimize before scaling** — add missing indexes, fix N+1 queries, add `EXPLAIN ANALYZE`-driven query tuning, and enable connection pooling (PgBouncer). Often this alone buys 12+ months.
2. **Vertical scaling** — bigger instance (more vCPU/RAM). Simple, no app changes, but has a ceiling and a single point of failure.
3. **Caching** — Redis/memcached in front for hot reads.
4. **Read replicas** — offload reads; requires the app to tolerate replication lag.
5. **Vertical partitioning / service split** — peel a high-traffic table or domain into its own database.
6. **Horizontal sharding** — last resort because it adds the most operational and application complexity (cross-shard joins, transactions, re-sharding).

The interview signal is: *don't shard prematurely.* Sharding is the most expensive option and you exhaust the others first.

### Q6. [Coding] Implement a simple hash-based shard router in Java.

**Problem:** Given N shards and a key, deterministically map the key to a shard index, and route a `get`/`put` to the right shard.

```java
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class HashShardRouter {
    private final List<Map<String, String>> shards; // one map per shard (stand-in for a DB)
    private final int n;

    public HashShardRouter(int numShards) {
        this.n = numShards;
        this.shards = new ArrayList<>(numShards);
        for (int i = 0; i < numShards; i++) shards.add(new HashMap<>());
    }

    // Stable hash: String.hashCode() is NOT stable across JVMs/versions for
    // persisted routing, so use a digest for anything durable.
    int shardFor(String key) {
        try {
            byte[] d = MessageDigest.getInstance("MD5")
                    .digest(key.getBytes(StandardCharsets.UTF_8));
            int h = ((d[0] & 0xff) << 24) | ((d[1] & 0xff) << 16)
                  | ((d[2] & 0xff) << 8) | (d[3] & 0xff);
            return Math.floorMod(h, n); // floorMod handles negatives correctly
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public void put(String key, String value) { shards.get(shardFor(key)).put(key, value); }
    public String get(String key)             { return shards.get(shardFor(key)).get(key); }
}
```

**Why `floorMod` and a digest?** `key.hashCode() % n` can return a negative index for negative hashes, and `String.hashCode()` is only stable within a process — never base *persisted* placement on it. Use a cryptographic/consistent digest.

- **Time:** O(1) per lookup (digest of a small key). **Space:** O(total keys).
- **Edge cases:** `n == 0` (guard it), null keys, and — the big one — **changing `n` re-maps almost every key** (see Q12 on consistent hashing).

---

## 🟡 Intermediate (3–7 yrs)

### Q7. [Theory] Compare range, hash, directory (lookup), and geo sharding. When does each win?

| Strategy | How it maps | Pros | Cons |
|---|---|---|---|
| **Range** | Contiguous key ranges (`A–F`, `G–M`) | Efficient range scans; ordered data | Hotspots on sequential keys (timestamps, auto-inc IDs) |
| **Hash** | `hash(key) % N` or hash ring | Even distribution; no hot ranges | Kills range scans; resharding re-maps keys |
| **Directory/Lookup** | Explicit key→shard table | Max flexibility; easy rebalancing | Lookup service is a SPOF/bottleneck; extra hop |
| **Geo** | By region/locality | Data residency (GDPR); low latency | Skew if one region dominates; cross-region queries slow |

```
 Range:   [a-f]->S1  [g-m]->S2  [n-z]->S3   (scan "g..k" hits only S2)
 Hash:    h(k1)=S2  h(k2)=S1  h(k3)=S3      (scan "g..k" hits ALL shards)
 Directory:  key -> [lookup table] -> shard  (can move any key anytime)
 Geo:     EU users->Frankfurt   US users->Virginia
```

**Practical pick:** Range when you do ordered/time-window scans (and you can mitigate hotspots with key salting/bucketing). Hash for uniform point lookups. Directory when you must move tenants individually or rebalance gracefully. Geo when regulation or latency dictates locality.

### Q8. [Theory] What is a hotspot (hot shard / celebrity problem) and how do you fix it?

A **hotspot** is a shard receiving disproportionate load. Causes: low-cardinality keys (e.g., sharding by `country` with 90% US traffic), monotonically increasing keys (timestamps → all writes hit the newest shard), or a "celebrity" entity (one user/tweet with millions of followers).

Fixes:
- **Better key**: higher cardinality, or composite key (`tenant_id + entity_id`).
- **Salting / bucketing**: prefix the key with `hash(key) % M` to spread a hot key across M sub-partitions, then fan-out reads. (DynamoDB write-sharding pattern.)
- **Key reversal**: reverse sequential IDs so high-order bits vary (used historically in HBase/Bigtable).
- **Split the hot shard** dynamically (CockroachDB/Spanner auto-split a hot range).
- **Caching / read replicas** dedicated to the celebrity entity.

### Q9. [Theory] Single-leader, multi-leader, and leaderless replication — explain the trade-offs.

```
 Single-leader        Multi-leader              Leaderless (Dynamo-style)
   W                     W      W                  W -> [N1 N2 N3]
   v                     v      v                  client writes to W nodes,
 [Leader]            [Leader]-[Leader]             reads from R nodes,
   | async/sync         |  conflict   |            success when quorum acks
   v                    v   resolution v
 [Replicas]         [Replicas]   [Replicas]
```

- **Single-leader** (Postgres, MySQL, MongoDB): all writes go to one leader, replicate to followers. Simple, no write conflicts, strong-ish consistency. Cons: leader is a write bottleneck and failover SPOF.
- **Multi-leader** (multiple writable nodes, often one per region; e.g., active-active MySQL, CouchDB): writes accepted anywhere → low write latency across regions and survives a region outage. Cons: **write conflicts** require resolution (last-write-wins, CRDTs, app logic).
- **Leaderless** (Dynamo, Cassandra, Riak): client writes to many nodes, reads from many; **quorum** (W+R>N) provides consistency. No failover step (any node serves). Cons: app must handle conflict resolution and read repair; tunable but tricky consistency.

### Q10. [Theory] Synchronous vs asynchronous replication, and what is replication lag?

In **synchronous** replication the leader waits for the replica(s) to acknowledge a write before confirming to the client. This guarantees the replica is up to date (no data loss on failover) but **couples availability and latency to the slowest replica** — if a sync replica stalls, writes block.

In **asynchronous** replication the leader confirms immediately and ships changes to replicas in the background. Fast and available, but a leader crash can **lose** un-replicated writes, and replicas serve stale data.

**Replication lag** is the time/offset by which a replica trails the leader. It causes anomalies: read-your-writes violations, monotonic-read violations (reading an *older* value after a newer one), and causal anomalies. Most production systems use **semi-synchronous**: one synchronous replica for durability, the rest async for performance. PostgreSQL supports `synchronous_commit` levels and `synchronous_standby_names` with quorum (`ANY 1 (...)`).

### Q11. [Practical] Users complain that right after saving their profile, the page shows the old data. Diagnose and fix.

This is a classic **read-your-writes** failure caused by reading from an async read replica before the write has propagated.

Diagnosis: the write hit the primary; the subsequent read load-balanced to a lagging replica.

Production fixes (in order of preference):
1. **Read-from-leader window**: after a user writes, route their reads to the primary for a short window (e.g., N seconds keyed by user/session). Cheap and effective.
2. **Track write position (LSN/GTID)**: client remembers the log position of its write; route reads to a replica only if it has caught up to that position, else to the primary ("monotonic reads via LSN").
3. **Session/sticky consistency**: pin a session to a replica that is guaranteed as-fresh-as-its-last-write (Vitess `@replica` with bounded staleness, DynamoDB strongly-consistent reads).
4. **Synchronous replica** for the critical path (heavier).

I'd ship option 1 first (an afternoon of work), then add option 2 if lag is large.

### Q12. [Coding] Implement consistent hashing with virtual nodes to minimize re-mapping on resharding.

**Problem:** Plain `hash % N` remaps ~all keys when N changes. Consistent hashing remaps only ~`K/N` keys when a node is added/removed. Virtual nodes (vnodes) smooth out distribution.

```java
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class ConsistentHashRing {
    private final TreeMap<Long, String> ring = new TreeMap<>(); // hash -> physical node
    private final int vnodesPerNode;

    public ConsistentHashRing(int vnodesPerNode) { this.vnodesPerNode = vnodesPerNode; }

    private long hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("MD5")
                    .digest(s.getBytes(StandardCharsets.UTF_8));
            long h = 0;
            for (int i = 0; i < 8; i++) h = (h << 8) | (d[i] & 0xff);
            return h;
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public void addNode(String node) {
        for (int i = 0; i < vnodesPerNode; i++) ring.put(hash(node + "#" + i), node);
    }

    public void removeNode(String node) {
        for (int i = 0; i < vnodesPerNode; i++) ring.remove(hash(node + "#" + i));
    }

    /** Walk clockwise to the first vnode at/after the key's hash. */
    public String getNode(String key) {
        if (ring.isEmpty()) return null;
        Long k = ring.ceilingKey(hash(key));
        if (k == null) k = ring.firstKey(); // wrap around
        return ring.get(k);
    }
}
```

- **Time:** O(log V) per lookup (TreeMap), V = total vnodes. **Space:** O(V).
- **Why vnodes:** with one point per node, distribution is lumpy and removing a node dumps all its load on one neighbor. With ~100–200 vnodes/node, load and the redistribution on add/remove spread evenly.
- **Edge cases:** empty ring, wrap-around past the largest hash, hash collisions (rare), and **weighted nodes** (give bigger machines more vnodes). Used by Cassandra, DynamoDB, and many caches.

### Q13. [Coding] Implement a quorum check for leaderless reads/writes (W + R > N).

**Problem:** In a Dynamo-style store with N replicas, a write needs W acks and a read needs R responses. Decide whether an operation succeeded and whether the config guarantees overlap (strong consistency).

```java
import java.util.*;

public class Quorum {
    final int n; // replication factor
    final int w; // write quorum
    final int r; // read quorum

    public Quorum(int n, int w, int r) {
        if (w < 1 || r < 1 || w > n || r > n)
            throw new IllegalArgumentException("w,r must be in [1,n]");
        this.n = n; this.w = w; this.r = r;
    }

    /** Strong consistency requires the read set to overlap the latest write set. */
    public boolean guaranteesStrongConsistency() { return (w + r) > n; }

    /** Tolerated simultaneous node failures while still able to read AND write. */
    public int faultTolerance() { return Math.min(n - w, n - r); }

    public boolean writeSucceeded(int acks) { return acks >= w; }
    public boolean readSucceeded(int responses) { return responses >= r; }

    /** Resolve concurrent versions by highest logical timestamp (LWW). */
    public String resolveLatest(Map<String, Long> valueToVersion) {
        return valueToVersion.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }
}
```

- **Time:** O(replicas) to count acks / O(versions) to resolve. **Space:** O(versions).
- **Key insight:** `W + R > N` forces the read set and the most-recent write set to share at least one node, so a read always sees the latest write. Common configs: `N=3,W=2,R=2` (balanced), `W=3,R=1` (fast reads), `W=1,R=3` (fast writes, weak durability).
- **Edge cases:** `W+R<=N` gives eventual consistency only; LWW silently drops concurrent writes (use version vectors/CRDTs to avoid lost updates); "sloppy quorum" with hinted handoff can ack on the wrong nodes during partitions.

### Q14. [Practical] Design the shard key for a multi-tenant SaaS app (thousands of tenants, some huge, most tiny).

Approach: shard by **`tenant_id`** (hash-based) so each tenant's data is co-located — the vast majority of queries are tenant-scoped, giving single-shard reads/writes and cheap intra-tenant transactions/joins.

Problem: tenant sizes are wildly skewed (the "noisy/whale tenant"). A single huge tenant can overwhelm one shard.

Production design:
- Use a **directory/lookup layer** so you can relocate or **dedicate a shard** to a whale tenant without re-mapping everyone else.
- For a whale that exceeds one shard, add a **composite key** `(tenant_id, entity_id)` so that tenant's data can itself be split.
- Keep small tenants packed many-to-a-shard for efficiency.
- Reserve a path to "**isolate on request**" (enterprise customers paying for dedicated infra).

This is essentially the Vitess/Citus multi-tenant pattern and how Salesforce, Shopify (Pods), and Slack historically organized tenants.

### Q15. [Practical] How do you reshard a live, growing system from 4 shards to 8 with zero downtime?

```
Phase 1  Add 8-shard mapping (logical), keep 4-shard live  (dual config)
Phase 2  Backfill: copy/replicate data to new shards using CDC stream
Phase 3  Catch-up: new shards tail the change log to near-zero lag
Phase 4  Cutover per-shard-range: briefly fence writes, flip routing, unfence
Phase 5  Verify (checksums), then drop old data
```

Concrete approach:
1. Prefer **doubling** so each old shard splits cleanly into two — minimizes data movement and is friendly to hash routing.
2. Stand up new shards; **backfill** historical data while a **CDC stream** keeps them current.
3. **Cut over one key-range at a time** to limit blast radius: pause writes to that range for milliseconds, confirm the new shard is caught up, atomically flip the routing entry, resume.
4. Run **dual-read verification** (read old+new, compare) before trusting the new shard.
5. Keep the old data read-only as a rollback for a grace period.

Tools that automate this: **Vitess Reshard/MoveTables**, **Citus rebalancer**, **CockroachDB/Spanner** (automatic — ranges split/move transparently). The interview point: never "stop the world"; move incrementally with a verification + rollback path.

### Q16. [Theory] What is failover, and what is split-brain? How do you prevent it?

**Failover** is promoting a replica to leader when the current leader fails. Steps: detect failure (timeout/heartbeat), elect/choose a new leader (often the most up-to-date replica), reconfigure clients/routing.

**Split-brain** is when two nodes both believe they are the leader — typically because a network partition isolated the old leader, a new one was promoted, and then the old one came back still accepting writes. Result: **divergent, conflicting data**.

Prevention:
- **Quorum-based election** (majority must agree) so a minority partition can't elect a leader — the basis of **Raft** and **Paxos**.
- **Fencing tokens**: each leadership term gets a monotonically increasing token; storage rejects writes carrying a stale token, so a zombie old leader is locked out.
- **STONITH** ("shoot the other node in the head") — forcibly power-off/isolate the old leader.
- Avoid even-node clusters; require an odd quorum (or a witness/arbiter) to prevent ties.

### Q17. [Theory] Why are cross-shard JOINs and transactions hard, and how do you cope?

Once data spans shards, a JOIN or transaction must coordinate multiple independent nodes. JOINs require **scatter-gather** (query every shard, merge results) — slow, and fan-out latency is bounded by the slowest shard. Transactions need **two-phase commit (2PC)** or a distributed-consensus commit, which adds latency and blocks if the coordinator fails.

Coping strategies:
- **Co-locate** related data via a shared shard key (e.g., everything for a tenant on one shard → JOINs stay local). This is the single biggest lever.
- **Denormalize** so the read doesn't need a JOIN.
- **Reference/dimension tables** replicated to every shard (Citus "reference tables") so they can be joined locally.
- Avoid cross-shard transactions; use the **Saga pattern** (compensating transactions) for multi-step workflows.
- Use a **distributed SQL** engine (Spanner/CockroachDB) that does consistent cross-shard transactions for you — at a latency cost.

---

## 🟠 Advanced (8–12 yrs)

### Q18. [Theory] Explain how distributed SQL databases (Spanner, CockroachDB, Vitess) achieve horizontal scale with strong consistency.

These systems give you a SQL interface and ACID transactions while sharding under the hood, but via different philosophies:

- **Google Spanner**: data is split into **ranges/splits** auto-distributed across nodes; each split is replicated via **Paxos**. Strong consistency + external (linearizable) consistency comes from **TrueTime** — GPS+atomic-clock-backed APIs that bound clock uncertainty, letting Spanner assign globally meaningful commit timestamps and wait out the uncertainty window (`commit-wait`).
- **CockroachDB**: open-source, Spanner-inspired. Data in ~512MB **ranges** replicated by **Raft**; uses **hybrid logical clocks (HLC)** instead of special hardware, with a configurable `max-offset`. Auto-splits hot ranges and rebalances. Serializable isolation by default.
- **Vitess** (powers YouTube, Slack, GitHub): a **sharding middleware on top of MySQL**. VTGate routes queries; **keyspaces** are split by a vindex (shard key function). It does *not* invent a new storage engine — it scales MySQL horizontally with online resharding, while cross-shard transactions are best-effort (2PC optional).

```
 Spanner/CockroachDB:  SQL -> ranges -> Paxos/Raft groups (auto split & move)
 Vitess:               SQL -> VTGate -> vindex -> MySQL shards (you run MySQL)
```

The trade-off: Spanner/CockroachDB give transparent strong consistency but pay consensus latency on writes; Vitess keeps MySQL's familiarity and per-shard speed but pushes cross-shard concerns to the app.

### Q19. [Theory] What is Change Data Capture (CDC) and what are its uses and pitfalls?

**CDC** captures row-level changes (insert/update/delete) from a database's transaction log (Postgres WAL / logical decoding, MySQL binlog, MongoDB oplog) and streams them to consumers — typically via **Debezium → Kafka**.

```
 [Postgres WAL] -> [Debezium connector] -> [Kafka topic] -> consumers
                                                          -> search index (Elasticsearch)
                                                          -> cache invalidation
                                                          -> data warehouse / lake
                                                          -> other microservices
```

Uses: keeping caches/search indexes/derived stores in sync, the **Outbox pattern** (atomic DB write + reliable event publish), zero-downtime migrations, and as the backfill/catch-up mechanism during **resharding** (see Q15).

Pitfalls:
- **Ordering** is guaranteed only per-partition; choose Kafka keys (often the PK) so updates to one row stay ordered.
- **At-least-once** delivery → consumers must be **idempotent**.
- **Schema evolution** breaks naive consumers; use a schema registry.
- **Initial snapshot** of a huge table can be heavy.
- **Security/PII**: the change stream carries raw row data — encrypt, mask, and restrict topic access.

### Q20. [Practical] You need a globally distributed app serving EU and US users with low write latency in both regions and GDPR data residency. Design the replication topology.

Requirements pull in two directions: low write latency in *both* regions argues for **multi-leader / active-active**, while GDPR data residency requires **EU personal data to physically stay in the EU**.

Design:
- **Partition by region (geo-sharding)**: EU users' rows live on EU shards, US users' on US shards. Each region is the **single leader** for its own users → local low-latency writes and residency compliance.
- Within each region, replicate (single-leader + replicas) for HA.
- For genuinely shared data that both regions write (rare), use **multi-leader with conflict resolution** (CRDTs or app-defined merge), accepting eventual consistency on that slice.
- For cross-region *reads* of the other region's data, use async cross-region replicas with bounded staleness, or route to the owning region.

```
   EU region (leader for EU users)        US region (leader for US users)
   [EU leader] -> [EU replicas]           [US leader] -> [US replicas]
        ^  residency boundary (PII stays)        ^
        +------ async cross-region read replicas / shared CRDT data ------+
```

This is the classic pattern behind Spanner placement policies, CockroachDB **multi-region tables** (`REGIONAL BY ROW`), and AWS Aurora Global / DynamoDB Global Tables with region-pinning. The interview signal: recognize the **CAP/residency tension** and resolve it by *partitioning ownership by region* rather than trying to make one global writable copy.

### Q21. [Coding] Implement a per-shard 2PC coordinator skeleton (prepare/commit/abort) in Java.

**Problem:** Atomically apply a transaction across multiple shard participants using two-phase commit, aborting if any participant can't prepare.

```java
import java.util.*;

public class TwoPhaseCommit {

    interface Participant {
        boolean prepare(String txId);   // vote yes/no, durably stage changes
        void commit(String txId);       // make staged changes durable & visible
        void abort(String txId);        // discard staged changes
    }

    private final List<Participant> participants;
    public TwoPhaseCommit(List<Participant> participants) { this.participants = participants; }

    /** Returns true if committed, false if aborted. */
    public boolean execute(String txId) {
        List<Participant> prepared = new ArrayList<>();
        // Phase 1: PREPARE (collect votes)
        for (Participant p : participants) {
            boolean vote;
            try { vote = p.prepare(txId); }
            catch (RuntimeException e) { vote = false; }   // treat error as NO
            if (!vote) { rollback(prepared, txId); return false; }
            prepared.add(p);
        }
        // Phase 2: COMMIT (all voted yes) — must retry until each acks (durable decision)
        for (Participant p : prepared) commitWithRetry(p, txId);
        return true;
    }

    private void rollback(List<Participant> prepared, String txId) {
        for (Participant p : prepared) {
            try { p.abort(txId); } catch (RuntimeException ignore) { /* retry/log */ }
        }
    }

    private void commitWithRetry(Participant p, String txId) {
        for (int attempt = 0; attempt < 5; attempt++) {
            try { p.commit(txId); return; } catch (RuntimeException e) { /* backoff */ }
        }
        // escalate: the decision is COMMIT and is logged; a recovery process must finish it
    }
}
```

- **Time:** O(participants) round trips per phase. **Space:** O(participants) for the prepared set.
- **The fatal flaw of 2PC:** it is a **blocking** protocol. If the coordinator crashes *after* prepare but *before* broadcasting the decision, participants hold locks indefinitely ("in-doubt" transactions). Production systems log the decision durably and run a recovery/resolution process; better systems replace the coordinator with a **consensus group (Raft/Paxos)** so the decision survives coordinator failure — this is what Spanner/CockroachDB do.
- **Edge cases:** participant timeout (vote NO), duplicate commit calls (must be idempotent), coordinator crash recovery, and partial commit (never allowed — that's the whole point).

### Q22. [Practical] How would you detect, measure, and alert on replication lag in production?

**Measure** the right thing — not just "seconds behind" but **log position delta**:
- Postgres: compare `pg_current_wal_lsn()` (primary) to `pg_last_wal_replay_lsn()` (replica); expose `pg_stat_replication.replay_lag`.
- MySQL: `Seconds_Behind_Master` (coarse) plus GTID gap (`gtid_executed` delta) for accuracy.
- A robust app-level method: a **heartbeat table** the primary updates every second with a timestamp; the replica reads it and computes `now - heartbeat_ts` = true end-to-end lag (catches stalled replication that byte-offsets miss).

**Alert** on: lag exceeding SLA (e.g., > 5s), lag *trend* (monotonically increasing = replica falling behind permanently), and replica disconnects.

**Act**: when lag spikes, automatically **fence stale replicas out of the read pool** (route those reads to the primary) so users don't see stale data; investigate root cause (long-running transaction on primary holding WAL, replica I/O saturation, network). The signal: measure lag in *both* bytes and *time*, and degrade gracefully (read-from-primary) rather than serve stale data.

### Q23. [Theory] PACELC — how does it extend CAP for replicated systems, and where do real databases land?

**CAP** says that during a network **P**artition you must choose **C**onsistency or **A**vailability. **PACELC** adds the more practically important everyday case: **E**lse (no partition), you still trade **L**atency vs **C**onsistency.

So a system is described as `PA/EL`, `PC/EC`, etc.:
- **PC/EC**: chooses consistency in both cases — Spanner, CockroachDB, traditional single-leader RDBMS (pay latency for strong reads).
- **PA/EL**: chooses availability/latency — Cassandra, DynamoDB (default eventual consistency, very low latency).
- **PA/EC**: rare/tunable.

The insight PACELC captures: even with zero partitions, **synchronous replication for strong consistency costs latency every single request**. Most teams over-index on the partition scenario (rare) and under-think the latency-vs-consistency trade they pay constantly. Dynamo-style stores let you *tune* it per-operation via N/W/R.

### Q24. [Theory] Compare leader-based consensus (Raft) with leaderless quorum (Dynamo) for replicating a shard.

**Raft (leader-based, used by CockroachDB/etcd/Spanner-style):** one leader per replica group orders all writes; a majority must persist each entry before commit. Gives **linearizable** writes, clean failover (new leader elected by majority — no split-brain), and a single authoritative log. Cost: writes funnel through the leader; cross-region leaders add latency.

**Dynamo-style leaderless (Cassandra/Riak):** no leader; clients write to W and read from R nodes, `W+R>N` for overlap. Gives **availability during partitions** (any live node serves) and no failover step, but allows **concurrent conflicting writes** that need version vectors/CRDTs/LWW and **read repair**/anti-entropy to converge.

```
 Raft group (per range)              Dynamo quorum (per key)
   client -> [Leader]                  client -> {N1,N2,N3}
              |  replicate to majority           write W, read R
        [F1]  [F2]  (Raft log)         conflicts resolved by VV / read-repair
```

Rule of thumb: choose **Raft/consensus** when you need linearizable correctness (financial ledgers, metadata, config); choose **leaderless quorum** when availability and write throughput matter more than per-key strict ordering (high-volume telemetry, carts, sessions).

### Q25. [Practical] An e-commerce platform double-charges customers during a regional failover. Root-cause it from a replication standpoint.

Likely cause: **async replication + failover data loss + non-idempotent payment processing**.

Scenario: the primary recorded "payment captured" but the write hadn't replicated when it crashed. A replica missing that write was promoted; the retry logic re-submitted the capture → double charge. Or: **split-brain** — old primary and new primary both accepted a capture.

Fixes:
1. **Idempotency keys** on payment operations — the payment processor dedups retries regardless of replication state. (This is the real fix; payments must be idempotent end-to-end.)
2. **Synchronous replication for the payments shard** so a write isn't acked until durably replicated → no failover data loss for money.
3. **Fencing tokens / quorum election** to make split-brain impossible.
4. **Exactly-once-ish via outbox + CDC** so the "charge intent" is recorded atomically with business state and processed once.

Interview signal: money requires **sync replication or consensus on the critical path** *and* **application-level idempotency** — never rely on async replication for financial correctness. This mirrors real incidents at payment-heavy companies; idempotency keys (à la Stripe) are the industry standard.

---

## 🔴 Expert (15+ yrs)

### Q26. [Theory] Explain Spanner's TrueTime and why bounded clock uncertainty enables external consistency at global scale.

Spanner provides **external consistency** (a.k.a. linearizability across the whole database): if transaction T1 commits before T2 starts, T1's timestamp < T2's, *everywhere on Earth*. Achieving this without a single global clock is the hard part.

**TrueTime** exposes time as an **interval** `[earliest, latest]` rather than a point, backed by GPS receivers and atomic clocks in every datacenter, with the API guaranteeing the true time lies within that interval (typically a few ms wide). On commit, Spanner picks `commit_ts = TT.now().latest` and then performs **commit-wait**: it waits until `TT.now().earliest > commit_ts` before releasing locks/acking. This guarantees that by the time anyone can observe the commit, the chosen timestamp is unambiguously in the past — so timestamp order matches real-time order.

The deep insight: you can't eliminate clock uncertainty, but if you can **bound** it and *wait it out*, you convert a distributed-ordering problem into a small latency cost. CockroachDB approximates this with **HLCs** and a configurable max-offset, trading hardware for occasional restarts/uncertainty retries.

### Q27. [Theory] How do version vectors / vector clocks and CRDTs let multi-leader and leaderless systems converge without a leader?

**Version vectors** track, per replica, how many updates each has seen (`{A:3, B:1}`). Comparing two versions tells you whether one **dominates** (causally after) the other or whether they are **concurrent** (conflict). This is more precise than last-write-wins, which silently drops concurrent updates.

**CRDTs (Conflict-free Replicated Data Types)** are data structures whose merge operation is **commutative, associative, and idempotent**, so replicas applying updates in any order converge to the same state automatically — no coordination, no central conflict resolver. Examples: G-Counter (grow-only counter), OR-Set (observed-remove set), and the LWW-Register. They underpin Riak, Redis CRDTs (Active-Active), Azure Cosmos DB multi-write, and collaborative apps (Automerge/Yjs in Figma/Google-Docs-style editors).

Trade-off: CRDTs guarantee convergence but the *semantics* may surprise users (e.g., "add wins over remove"), and some app logic (uniqueness constraints, balances that can't go negative) can't be expressed as a CRDT and still needs consensus.

### Q28. [Practical] Lead the migration of a 50 TB monolithic MySQL database to a sharded architecture with near-zero downtime. Lay out the program.

```
 1. Discovery   -> pick shard key (query analysis, tenant model), pick N shards
 2. Foundation  -> routing layer (Vitess/ProxySQL) in front, app reads through it
 3. Dual-write  -> writes go to monolith AND new shards (or via CDC), monolith authoritative
 4. Backfill    -> snapshot + CDC catch-up populate shards historically
 5. Verify      -> continuous checksum / dual-read comparison, reconcile drift
 6. Flip reads  -> shift read traffic shard-by-shard, monitor error budget
 7. Flip writes -> make shards authoritative, monolith becomes shadow
 8. Decommission-> remove dual-write, retire monolith after grace period
```

Key program decisions:
- **Shard key first** — driven by analyzing the real query workload; getting this wrong is a multi-quarter setback.
- Introduce a **routing/abstraction layer early** so the app stops issuing direct connection strings (this is reversible and de-risks everything after).
- **CDC (Debezium) is the backbone** of backfill + keep-in-sync.
- **Verification gates** between phases; never flip without checksum parity.
- **Rollback at every step** (monolith stays authoritative until the very end).
- Org-wise: a **feature freeze on schema changes** during cutover windows, a dedicated migration squad, runbooks, and game-day rehearsals.

Real precedents: this is essentially how **Vitess** was built for YouTube, how **Slack** and **GitHub** moved to Vitess, and how **Notion** sharded Postgres (their well-documented 2021/2023 efforts). The expert signal is sequencing, reversibility, verification, and treating it as a *program* with org change management — not a one-shot DB task.

### Q29. [Behavioral] Describe a time you made a consistency/availability trade-off that later caused an incident. What did you change?

Structure with STAR and own the trade-off honestly:

- **Situation:** "We ran read replicas with async replication to scale a read-heavy product API. We accepted eventual consistency to hit p99 latency targets."
- **Task:** "I owned the data tier and signed off on routing all GET traffic to replicas."
- **Action / what went wrong:** "During a replica lag spike (a long migration on the primary held the WAL), users saw stale balances and some retried writes, creating support load and one duplicate operation. I led the incident."
- **Resolution & change:** "We added (1) lag-aware routing that fences stale replicas out of the read pool, (2) read-your-writes via LSN tracking for the account-balance path, (3) idempotency keys on the mutating endpoints, and (4) an SLO+alert on replica lag. We kept eventual consistency where it's safe (catalog) and moved the *correctness-critical* paths to read-from-primary."

The signal interviewers want: you can articulate *why* the trade-off was reasonable, recognize that "eventual consistency" is a per-endpoint decision not a global one, and that you instrumented and degraded gracefully afterward rather than over-rotating to "make everything strongly consistent."

### Q30. [Theory] Design a multi-region sharded system that is resilient to a full region outage with an explicit RPO/RTO target. What are the levers?

The architecture is dictated by **RPO** (max acceptable data loss) and **RTO** (max acceptable downtime):

- **RPO = 0** (no data loss) requires **synchronous cross-region replication** or **consensus quorum spanning regions** (e.g., a Raft/Paxos group with members in 3 regions, commit needs a majority). Cost: every write pays inter-region round-trip latency (~tens of ms). Spanner/CockroachDB multi-region do exactly this.
- **RPO > 0** (some loss tolerable) permits **async cross-region replication** — fast local writes, but a region loss drops the un-shipped tail. Pair with promotion automation for low RTO.
- **RTO** is driven by **automated failover**: health detection, quorum-safe promotion (no split-brain), DNS/Anycast or service-mesh re-routing, and **pre-warmed** standby capacity.

```
 RPO=0:  3-region Raft quorum  (write needs 2 of 3 regions ack) -> survives 1 region loss, 0 loss
 RPO>0:  region A (leader) --async--> region B (standby) -> failover promotes B, loses tail
```

Levers/considerations: **odd number of regions** (3) so a quorum survives one loss without split-brain; **shard placement policies** to keep a shard's quorum out of correlated-failure zones; **data residency** may forbid the simplest 3-region quorum (GDPR) forcing regional-leader designs (Q20); and **cost** — synchronous tri-region is expensive, so apply it selectively to the correctness-critical shards while less critical data uses cheaper async DR. The expert move is mapping each dataset to its *own* RPO/RTO rather than one blanket policy, and proving it with **regular failover game-days** (chaos drills) instead of trusting the runbook.

---

## ✅ Key Takeaways

- **Replicate for availability, shard for scale** — they are orthogonal and you almost always do both (replicate each shard).
- **Don't shard prematurely.** Optimize, cache, scale vertically, and add read replicas first; sharding is the costliest lever.
- The **shard key** is the highest-stakes decision: aim for high cardinality, even access, and alignment with your dominant query. Co-locate related data to keep JOINs/transactions on one shard.
- Choose the partitioning scheme to match access: **range** for scans, **hash** for uniform point lookups, **directory** for flexible rebalancing, **geo** for residency/latency.
- Pick the replication topology for your write pattern: **single-leader** (simple, no conflicts), **multi-leader** (active-active, conflicts), **leaderless quorum** (`W+R>N`, tunable, no failover step).
- **Sync vs async** is a durability-vs-latency dial; most systems use **semi-sync**. Always measure **replication lag** in both bytes and time, and route correctness-critical reads to the primary.
- Prevent **split-brain** with quorum election + **fencing tokens**; this is why Raft/Paxos and odd-node quorums exist.
- **Distributed SQL** (Spanner/CockroachDB/Vitess) hides sharding behind SQL; understand the consistency mechanism (TrueTime, HLC, MySQL middleware) and its latency cost.
- **CDC** (Debezium/Kafka) is the backbone of cache/search sync, outbox events, and zero-downtime resharding/migrations — design consumers to be idempotent.
- **Money and correctness-critical paths** need synchronous replication/consensus *and* application-level idempotency keys — never rely on async replication for them.

## ⚠️ Common Pitfalls

- Choosing a **monotonically increasing shard key** (timestamp/auto-inc) → all writes hammer one shard. Salt, bucket, or reverse the key.
- Using `key.hashCode() % N` for **persisted** placement — `String.hashCode()` isn't stable across JVMs and plain mod re-maps everything on resharding. Use consistent hashing.
- Treating "eventual consistency" as a global switch instead of a **per-endpoint** decision — leads to read-your-writes bugs on critical paths.
- Forgetting that **async replication loses the un-replicated tail on failover** — fatal for payments/ledgers.
- Allowing **even-node clusters** or naive failover → split-brain and divergent data.
- Relying on **2PC without coordinator recovery** → in-doubt transactions hold locks forever; use consensus-backed commit.
- Assuming **CDC delivers exactly-once and in global order** — it's at-least-once and per-partition ordered; make consumers idempotent and key correctly.
- Designing for the rare **partition** (CAP) while ignoring the constant **latency-vs-consistency** tax (PACELC's "Else" case).
- Doing a **big-bang reshard/migration** with no incremental cutover, verification, or rollback path.
- Leaking **PII through change streams / cross-region replicas** without encryption, masking, or residency controls (GDPR/compliance risk).

## 📚 Further Reading

- **Martin Kleppmann — *Designing Data-Intensive Applications*** (Ch. 5 Replication, Ch. 6 Partitioning, Ch. 9 Consistency & Consensus). The definitive single source for this topic.
- **Google — *Spanner: Google's Globally-Distributed Database*** (OSDI 2012) and the **TrueTime** discussion.
- **DeCandia et al. — *Dynamo: Amazon's Highly Available Key-value Store*** (SOSP 2007) — origin of leaderless quorum + consistent hashing.
- **CockroachDB docs** — Architecture, multi-region configurations, and the Raft/HLC design.
- **Vitess documentation** — sharding, VTGate routing, online Reshard/MoveTables (the production playbook used by YouTube/Slack/GitHub).
- **Debezium documentation** + Confluent's CDC patterns — log-based change capture, the Outbox pattern, and connector operations.
