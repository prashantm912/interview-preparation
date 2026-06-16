# Data Layer Design

The data layer is where most systems live or die at scale. This guide covers how to choose, model, replicate, partition, and operate datastores under real production constraints — with Java examples, ASCII diagrams, and interview-grade answers across four experience tiers.

[← Back to master index](../README.md)

## Table of Contents

- [🟢 Basic (0–2 yrs)](#-basic-02-yrs)
- [🟡 Intermediate (3–7 yrs)](#-intermediate-37-yrs)
- [🟠 Advanced (8–12 yrs)](#-advanced-812-yrs)
- [🔴 Expert (15+ yrs)](#-expert-15-yrs)

---

## 🟢 Basic (0–2 yrs)

### Q1. [Theory] What is the difference between SQL and NoSQL databases?

SQL (relational) databases store data in tables with a fixed schema, enforce relationships via foreign keys, and support ACID transactions and rich joins through SQL. They are the right default when your data is highly relational, when you need strong consistency, and when access patterns are not fully known up front (the relational model lets you query data many ways via ad-hoc joins).

NoSQL is an umbrella term for non-relational stores, each optimized for a shape of data and access pattern:

- **Document** (MongoDB, Couchbase): semi-structured JSON-like documents; flexible schema; good for hierarchical, denormalized aggregates.
- **Key-Value** (Redis, DynamoDB, Riak): a hash map at scale; O(1) lookups by key; ideal for caching, sessions, feature flags.
- **Column-family / wide-column** (Cassandra, HBase, ScyllaDB): rows keyed by a partition key with sparse, wide columns; built for massive write throughput and time-series.
- **Graph** (Neo4j, Amazon Neptune): nodes and edges; excels at relationship traversal (social graphs, fraud rings, recommendations).

The core trade-off: SQL gives you flexibility of querying and strong consistency at the cost of harder horizontal scaling; NoSQL gives you horizontal scale and schema flexibility but forces you to model data around known queries and often relaxes consistency.

```
            ┌─────────────────────────────────────────────┐
            │                Datastore types               │
            ├──────────┬──────────┬──────────┬─────────────┤
            │ Relational│ Document │  Wide-   │   Graph     │
            │  (rows/   │ (JSON)   │  column  │ (nodes/     │
            │  tables)  │          │          │  edges)     │
            ├──────────┼──────────┼──────────┼─────────────┤
            │ Postgres │ MongoDB  │ Cassandra│   Neo4j     │
            │ MySQL    │ DynamoDB*│ HBase    │   Neptune   │
            └──────────┴──────────┴──────────┴─────────────┘
              * DynamoDB is KV/document hybrid
```

### Q2. [Theory] What does ACID stand for, and why does it matter?

ACID describes the guarantees a transaction provides:

- **Atomicity** — all statements in a transaction succeed or none do; no partial writes survive a crash.
- **Consistency** — a transaction moves the database from one valid state to another, respecting constraints (uniqueness, foreign keys, checks).
- **Isolation** — concurrent transactions do not corrupt each other; the result is as if they ran in some serial order (depending on the isolation level).
- **Durability** — once committed, data survives crashes (typically via a write-ahead log / WAL flushed to disk).

It matters because correctness in money, inventory, and bookings depends on it. Without atomicity, a bank transfer could debit one account and never credit the other. The cost is that strong ACID guarantees are harder to provide across many machines, which is why distributed systems often relax isolation or consistency.

### Q3. [Theory] What is a database index and what is the trade-off of adding one?

An index is an auxiliary data structure (usually a **B+ tree** for range queries, or a **hash index** for equality) that maps column values to row locations so the database can find rows without a full table scan. A query on an indexed column goes from O(n) to roughly O(log n).

The trade-off: indexes speed up reads but slow down writes (every INSERT/UPDATE/DELETE must also update the index) and consume storage and memory. Over-indexing a write-heavy table is a classic mistake. You also pay for index maintenance during bulk loads. The rule of thumb: index columns used in `WHERE`, `JOIN`, and `ORDER BY`, measure with `EXPLAIN`, and drop unused indexes.

```
Without index: SELECT * FROM users WHERE email = ?  → scan all N rows
With B+ index on email:
        [g]
       /   \
    [c]     [m]
   /  \     /  \
 [a][e]  [h][r]   ← leaves point to row locations, O(log N)
```

### Q4. [Theory] What is the difference between a primary key and a foreign key?

A **primary key** uniquely identifies each row in a table and cannot be null; the database automatically builds a unique index on it. A **foreign key** is a column (or set) that references the primary key of another table, enforcing referential integrity — you cannot insert an order for a customer that does not exist, and (with `ON DELETE` rules) deleting a parent can cascade or be blocked.

Foreign keys protect data integrity but add write-time overhead (the DB must verify the reference) and can complicate sharding, because a referenced row may live on a different shard. Many large-scale NoSQL and sharded systems drop foreign-key enforcement and validate relationships in application code instead.

### Q5. [Practical] You have a `users` table that is slow on `SELECT ... WHERE email = ?`. How do you diagnose and fix it?

**Approach:** Run the query through the planner first.

```sql
EXPLAIN ANALYZE SELECT * FROM users WHERE email = 'a@b.com';
```

If you see `Seq Scan` (Postgres) or `type: ALL` (MySQL), the database is scanning every row. Add an index:

```sql
CREATE INDEX CONCURRENTLY idx_users_email ON users(email);
```

**Trade-offs:** Use `CONCURRENTLY` in Postgres so the build does not lock writes on a live table. If `email` must be unique, use a `UNIQUE` index, which doubles as the constraint. If the query returns only a few columns, a **covering index** (`INCLUDE (id, name)`) lets the DB answer entirely from the index without touching the heap.

**What I'd do in production:** Add the index off-peak, verify with `EXPLAIN ANALYZE` that the plan switched to an index scan, monitor write latency afterward, and confirm via slow-query logs that the regression is gone.

### Q6. [Coding] Detect duplicate emails in a user dataset (case-insensitive).

**Problem:** Given a list of users, return the set of email addresses that appear more than once, treating `Foo@x.com` and `foo@x.com` as the same.

**Brute force — O(n²):** compare every pair. Avoid for large inputs.

**Optimal — O(n) time, O(n) space:** count normalized emails in a hash map.

```java
import java.util.*;

public class DuplicateEmails {

    public static Set<String> findDuplicates(List<String> emails) {
        if (emails == null || emails.isEmpty()) {
            return Collections.emptySet();
        }
        Map<String, Integer> counts = new HashMap<>();
        for (String email : emails) {
            if (email == null || email.isBlank()) continue;       // edge case
            String key = email.trim().toLowerCase(Locale.ROOT);    // normalize
            counts.merge(key, 1, Integer::sum);
        }
        Set<String> dups = new HashSet<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > 1) dups.add(e.getKey());
        }
        return dups;
    }
}
```

**Time:** O(n). **Space:** O(n).
**Edge cases:** null/blank emails, leading/trailing whitespace, mixed case, Unicode (use `Locale.ROOT` to avoid the Turkish-İ bug). In the database itself, prevent duplicates at the source with a functional unique index: `CREATE UNIQUE INDEX ON users (lower(email));`.

---

## 🟡 Intermediate (3–7 yrs)

### Q7. [Theory] Explain ACID vs BASE and when you'd accept BASE.

**ACID** prioritizes correctness: transactions are atomic, isolated, and immediately consistent. **BASE** (Basically Available, Soft state, Eventually consistent) prioritizes availability and partition tolerance: the system always accepts reads/writes, replicas may temporarily disagree, and they converge over time.

You accept BASE when availability and scale matter more than reading the very latest value: a shopping cart, a social feed, a "likes" counter, product catalogs, or a session store. A user seeing a like count that is 200ms stale is fine. You do **not** accept BASE for money movement, inventory decrement at checkout, or anything where a stale read causes a correctness bug (e.g., overselling the last item).

In practice modern systems are hybrids: DynamoDB offers eventually-consistent reads by default but strongly-consistent reads on demand; Postgres async replicas are eventually consistent for reads while the primary is ACID. The interview answer is to map consistency requirements **per access pattern**, not per database.

### Q8. [Theory] State the CAP theorem precisely and explain why "CA" is usually a myth.

CAP says that in the presence of a **network Partition (P)**, a distributed system must choose between **Consistency (C — every read sees the latest write)** and **Availability (A — every request gets a non-error response)**. Since network partitions are inevitable in real distributed systems, you are really always choosing between **CP** and **AP** during a partition.

"CA" (consistency + availability, no partition tolerance) describes a single-node database or a system that simply stops working when the network splits — it's not a meaningful design point for a distributed system. The more useful modern refinement is **PACELC**: if there is a Partition, choose A or C; Else (normal operation), choose between Latency and Consistency. This captures that even with no partition, synchronous replication for consistency costs latency.

```
            Partition occurs
                  │
        ┌─────────┴─────────┐
       CP                   AP
   reject some          serve possibly
   requests to          stale data to
   stay correct         stay up
 (HBase, etcd,        (Cassandra, Dynamo
  Spanner*)            default, Riak)
```

### Q9. [Theory] Normalization vs denormalization — when do you choose each?

**Normalization** (3NF/BCNF) removes redundancy by splitting data into related tables so each fact is stored once. Benefits: no update anomalies, smaller storage, integrity. Cost: reads require joins, which get expensive at scale.

**Denormalization** duplicates data (e.g., embedding the author's name in every post) to avoid joins and serve a read in one lookup. Benefits: fast reads, scales horizontally (no cross-shard joins). Cost: write amplification (update the author name in many places) and risk of inconsistency.

Decision rule: **normalize for write-heavy, integrity-critical OLTP; denormalize for read-heavy, latency-sensitive access patterns**, especially in NoSQL where you model around queries. A common middle ground in SQL is to keep a normalized source of truth and maintain denormalized **materialized views** or read models updated via triggers or CDC.

### Q10. [Practical] Walk through choosing a shard key for a multi-tenant SaaS analytics product.

**Scenario:** Each tenant generates events; some tenants are 10,000× larger than others; queries are almost always scoped to one tenant.

**Candidate keys:**

1. `event_id` (random/UUID) — perfectly uniform distribution, **but** a per-tenant query must scatter-gather across all shards. Bad for our access pattern.
2. `tenant_id` — co-locates a tenant's data, so per-tenant queries hit one shard. **But** a whale tenant creates a **hot shard** that one node cannot hold or serve.
3. **Composite `tenant_id + bucket`** — hash large tenants across N sub-buckets (`tenant_id, hash(event_id) % N`). Spreads whales while keeping small tenants on one shard.

**What I'd do in production:** Use a composite key. Detect whales (top 1% by volume) and apply a higher bucket count to them. Keep a routing table so the application knows how many buckets a tenant uses. This is essentially how Instagram and Notion handle skewed multi-tenant data — co-locate by tenant, then sub-partition the heavy tenants.

```
tenant_id only:            composite (tenant + bucket):
 ┌─────┐ ┌─────┐            ┌─────┐ ┌─────┐ ┌─────┐
 │ T1  │ │ T2  │            │T1.b0│ │T1.b1│ │T1.b2│  ← whale spread
 │HUGE │ │tiny │            │T2   │ │T3   │ │T4   │  ← small tenants
 └─────┘ └─────┘            └─────┘ └─────┘ └─────┘
   HOT!                       balanced load
```

### Q11. [Theory] Compare leader-follower, multi-leader, and leaderless replication.

```
Leader-Follower        Multi-Leader              Leaderless (quorum)
   ┌────────┐         ┌────┐    ┌────┐          ┌──┐ ┌──┐ ┌──┐
   │ Leader │         │ L1 │◄──►│ L2 │          │N1│ │N2│ │N3│
   └───┬────┘         └─┬──┘    └──┬─┘          └──┘ └──┘ └──┘
   ┌───┴───┬───┐        │          │           client writes to W,
   ▼       ▼   ▼     followers   followers     reads from R nodes
 [F1]    [F2] [F3]                              (W + R > N)
```

- **Leader-follower (single-leader):** all writes go to one leader, which replicates to read-only followers. Simple, no write conflicts, easy to reason about. Limits: write throughput is capped by one node, and leader failover causes brief unavailability. Used by Postgres, MySQL, MongoDB replica sets.
- **Multi-leader:** multiple nodes accept writes (e.g., one per datacenter or for offline clients). Improves write availability and locality, **but** introduces **write conflicts** that need resolution (last-write-wins, CRDTs, or app logic). Used in multi-DC active-active setups.
- **Leaderless / quorum (Dynamo-style):** any node accepts writes; clients write to W nodes and read from R nodes. With `W + R > N` you get quorum consistency; tunable per request. Handles node failures gracefully but needs **read repair** and **anti-entropy** to converge, and is vulnerable to clock-skew with last-write-wins. Used by Cassandra and DynamoDB.

### Q12. [Practical] Your read replicas are serving stale data and users see their own writes disappear. How do you fix it?

This is the **read-your-own-writes** consistency problem caused by replication lag: the user writes to the leader, then a subsequent read hits a lagging follower.

**Approaches, cheapest first:**

1. **Read from leader for a short window** after a user's write (e.g., 5s) — simplest, gives session consistency for that user.
2. **Sticky routing by timestamp** — track the write's log position (LSN); route the read to a replica only if its applied LSN ≥ the write LSN, otherwise fall back to the leader.
3. **Reduce lag** — faster network, smaller transactions, semi-synchronous replication for the most recent write.

```java
// Route reads to a replica only if it has caught up to the user's last write
public DataSource pickReadSource(long requiredLsn) {
    for (Replica r : replicas) {
        if (r.appliedLsn() >= requiredLsn && r.isHealthy()) {
            return r.dataSource();
        }
    }
    return leader.dataSource();   // fall back to leader if no replica is current
}
```

**What I'd do in production:** Implement option 1 immediately (a per-session "read from primary until T" flag in the cache), then add LSN-aware routing for correctness. Alert on replication lag (`pg_stat_replication`) and cap it.

### Q13. [Theory] What is a hot partition and how do you prevent it?

A **hot partition** (or hot shard / hot key) occurs when a disproportionate share of traffic targets one partition, overwhelming a single node while others sit idle. Causes: a low-cardinality shard key (e.g., `status = 'active'`), a celebrity user, a monotonic key (timestamp or auto-increment) that always writes to the "latest" partition, or a viral item.

Prevention techniques:

- **Choose a high-cardinality, uniformly accessed shard key** (hash of user_id rather than country).
- **Salt / bucket hot keys**: prefix the key with a random bucket (`bucket#celebrityId`) and scatter-gather on read.
- **Add a cache layer** in front of read-hot keys (Redis) so the partition isn't hit directly.
- **Avoid monotonic keys** for the partition dimension; if you need time ordering, combine a hashed prefix with the timestamp.
- **Adaptive capacity**: DynamoDB automatically isolates hot keys; Cassandra requires good key design up front.

A real example: Twitter/X timelines fan out celebrity tweets through a hybrid push-pull model precisely because a single celebrity write would hot-spot if pushed to all followers' partitions synchronously.

### Q14. [Coding] Implement a consistent-hashing ring for shard routing.

**Problem:** Map keys to N nodes such that adding/removing a node remaps only ~1/N of keys (not all of them), and distribute load with virtual nodes.

```java
import java.util.*;

public class ConsistentHashRing {
    private final SortedMap<Long, String> ring = new TreeMap<>();
    private final int vnodes;

    public ConsistentHashRing(int vnodesPerNode) {
        this.vnodes = vnodesPerNode;
    }

    public void addNode(String node) {
        for (int i = 0; i < vnodes; i++) {
            ring.put(hash(node + "#" + i), node);
        }
    }

    public void removeNode(String node) {
        for (int i = 0; i < vnodes; i++) {
            ring.remove(hash(node + "#" + i));
        }
    }

    /** Find the node owning a key: the first vnode clockwise from the key's hash. */
    public String getNode(String key) {
        if (ring.isEmpty()) return null;
        long h = hash(key);
        SortedMap<Long, String> tail = ring.tailMap(h);
        Long target = tail.isEmpty() ? ring.firstKey() : tail.firstKey();
        return ring.get(target);
    }

    private long hash(String s) {
        // FNV-1a 64-bit: cheap, well-distributed. Use a real hash (Murmur3) in prod.
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return h & 0x7fffffffffffffffL;
    }
}
```

**Time:** `getNode` is O(log V) where V = total virtual nodes (TreeMap lookup). **Space:** O(V).
**Why virtual nodes:** without them, three physical nodes would split the ring unevenly; 100–256 vnodes per node smooths the distribution to within a few percent.
**Edge cases:** empty ring returns null; removing the last node; hash collisions (rare with 64-bit). This is the routing layer behind Cassandra, Dynamo, and many sharded caches.

### Q15. [Coding] Implement an LRU cache to sit in front of the database.

**Problem:** Build a fixed-capacity cache with O(1) `get` and `put` that evicts the least-recently-used entry. This is the canonical read-through cache used to protect a hot data layer.

```java
import java.util.*;

public class LRUCache<K, V> {
    private final int capacity;
    private final LinkedHashMap<K, V> map;

    public LRUCache(int capacity) {
        this.capacity = capacity;
        // accessOrder=true makes get() move entries to the most-recent end
        this.map = new LinkedHashMap<>(capacity, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > LRUCache.this.capacity;
            }
        };
    }

    public synchronized V get(K key) {                 // synchronized = thread-safe
        return map.get(key);
    }

    public synchronized void put(K key, V value) {
        map.put(key, value);
    }
}
```

**Time:** O(1) for `get` and `put` (hash map + intrusive doubly-linked list inside `LinkedHashMap`). **Space:** O(capacity).
**Production note:** for concurrent workloads prefer **Caffeine** (W-TinyLFU eviction, far better hit rates than LRU) over a hand-rolled synchronized map. **Edge cases:** capacity ≤ 0, null keys, and the **thundering-herd / cache-stampede** problem on a miss for a hot key — mitigate with per-key locking or request coalescing so only one thread reloads from the DB.

### Q16. [Practical] When would you reach for polyglot persistence, and what's the cost?

**Polyglot persistence** means using different datastores for different jobs in one system, because no single database is optimal for everything. Example for an e-commerce platform:

```
Orders/payments  → PostgreSQL  (ACID, transactions)
Product catalog  → MongoDB     (flexible schema, varied attributes)
Search           → Elasticsearch (full-text, faceting)
Sessions/cart    → Redis       (low-latency KV, TTL)
Recommendations  → Neo4j       (graph traversal)
Event stream     → Kafka       (durable log, replay)
Analytics        → ClickHouse  (columnar OLAP)
```

**The cost is operational and consistency complexity:** more systems to operate, monitor, back up, and secure; cross-store consistency must be managed (you can't do a single ACID transaction across Postgres and Elasticsearch — you need CDC or the outbox pattern); more failure modes; and more expertise required. In an interview, justify each store by an access pattern and acknowledge you'd consolidate where a single DB is "good enough" rather than chasing best-of-breed everywhere.

### Q17. [Theory] Read replicas vs write scaling — why doesn't adding replicas scale writes?

Read replicas scale **reads** because each replica can serve queries independently. They do **not** scale writes, because every write must still be applied to the single leader and then replicated to every follower — the leader's write capacity is the ceiling, and more replicas actually add replication load.

To scale writes you must **partition (shard)** so different writes go to different leaders/nodes. Other write-relief techniques: batching, write-behind caching, moving high-frequency counters to a specialized store (Redis `INCR`), using append-only/LSM-tree stores (Cassandra) that absorb writes faster than B-tree stores, and offloading derived data to async pipelines. The clean mental model: **replicas for read scale, sharding for write scale.**

---

## 🟠 Advanced (8–12 yrs)

### Q18. [Theory] Explain the LSM-tree vs B-tree storage engine trade-off and where each wins.

```
B-Tree (Postgres, MySQL/InnoDB)     LSM-Tree (Cassandra, RocksDB)
 - update in place                   - append to in-memory memtable
 - read: O(log N), one location      - flush to immutable SSTables
 - write: random I/O, must find page  - sequential writes (fast)
 - read amplification: low            - read may check many SSTables
 - write amplification: moderate      - write amp from compaction
```

**B-trees** update pages in place, giving fast, predictable reads and good range scans, at the cost of random write I/O. **LSM-trees** buffer writes in memory (memtable), flush them as immutable sorted files (SSTables), and merge them later via **compaction**. This makes writes sequential and very fast, but reads may have to consult multiple SSTables (mitigated by bloom filters) and background compaction adds CPU/IO and write amplification.

**Where each wins:** B-trees for read-heavy, transactional OLTP with range queries. LSM-trees for write-heavy, high-ingest workloads (time-series, logging, IoT, event stores). The decision affects everything downstream — choosing Cassandra for a write-heavy feed and Postgres for transactional orders is a storage-engine choice as much as a data-model choice.

### Q19. [Practical] Design the data layer for a global e-commerce checkout that must never oversell inventory.

**Requirements:** strong consistency on stock decrement, global low-latency reads of product/catalog, high availability.

**Approach — split by consistency need:**

```
                ┌────────────────────────────────────────────┐
  Browse/catalog│  Read replicas + CDN/Redis (eventual OK)    │
                └────────────────────────────────────────────┘
                ┌────────────────────────────────────────────┐
  Add-to-cart   │  Redis (TTL, soft reservation)              │
                └────────────────────────────────────────────┘
                ┌────────────────────────────────────────────┐
  Checkout      │  CP store: row-level lock / atomic UPDATE   │
   (inventory)  │  UPDATE stock SET qty=qty-1                 │
                │  WHERE sku=? AND qty>0  → rowsAffected==1    │
                └────────────────────────────────────────────┘
```

```java
// Conditional decrement: the WHERE clause is the guard against overselling
@Transactional
public boolean reserve(String sku, int n) {
    int rows = jdbc.update(
        "UPDATE inventory SET qty = qty - ? " +
        "WHERE sku = ? AND qty >= ?", n, sku, n);
    return rows == 1;   // false ⇒ insufficient stock, fail the checkout
}
```

**Trade-offs:** The conditional `UPDATE` (compare-and-set) avoids a separate read-then-write race and works under any isolation level because the row lock serializes concurrent decrements. For extreme contention on a single hot SKU (flash sale), shard the stock into N sub-counters and decrement a random one, summing for display — this trades a tiny chance of false "out of stock" for throughput. Catalog reads tolerate eventual consistency; only the decrement is CP.

**What I'd do in production:** Keep inventory in a CP store (Postgres/Spanner), use the conditional update, add an idempotency key on the checkout request to make retries safe, and reconcile soft cart reservations with a TTL sweeper.

### Q20. [Theory] What is Change Data Capture (CDC), and how does the outbox pattern use it?

**CDC** streams a database's row-level changes (inserts/updates/deletes) as an ordered event log, typically by tailing the write-ahead log / binlog (Debezium reads Postgres WAL or MySQL binlog) rather than polling. This lets you propagate changes to other systems — search indexes, caches, data warehouses, downstream services — without dual-writes.

The **dual-write problem**: if a service writes to its DB and then publishes to Kafka in two steps, a crash between them leaves them inconsistent. The **transactional outbox** solves this: within the same DB transaction that updates business tables, insert an event row into an `outbox` table. CDC then tails the outbox and publishes to Kafka. Because the business write and the outbox write are one atomic transaction, the event is published **if and only if** the business change committed — exactly-once semantics at the source, at-least-once to consumers (who must be idempotent).

```
 Service ──BEGIN──┐
   UPDATE orders  │  (one atomic transaction)
   INSERT outbox  │
 ──COMMIT─────────┘
       │
   WAL / binlog ──► Debezium (CDC) ──► Kafka ──► search index / cache / warehouse
```

### Q21. [Practical] Design a CDC-based data pipeline to keep Elasticsearch in sync with Postgres.

**Scenario:** Postgres is the system of record; Elasticsearch powers search and must reflect changes within a couple seconds.

**Pipeline:**

```
Postgres (WAL) → Debezium connector → Kafka topic(s) → consumer → ES bulk API
                                          │
                                     (replayable log)
```

**Design decisions and trade-offs:**

- **Schema mapping:** the consumer transforms normalized Postgres rows into denormalized ES documents (joining product + category at index time). Joins happen in the pipeline, not in ES.
- **Ordering & idempotency:** key Kafka by entity ID so updates to the same document are ordered on one partition; use the document version (or `_seq_no`) for optimistic concurrency so out-of-order/replayed events don't overwrite newer data.
- **Backfill:** Debezium's initial snapshot loads existing rows, then switches to streaming — no separate ETL.
- **Failure handling:** if ES is down, Kafka retains the log; the consumer resumes from its committed offset. This is the core advantage of a log over direct dual-writes.
- **Lag monitoring:** alert on consumer lag and end-to-end propagation latency.

**What I'd do in production:** Debezium + Kafka + an idempotent bulk-indexing consumer with a dead-letter topic for poison messages, plus a periodic full-reconciliation job to catch silent drift.

### Q22. [Theory] How do distributed transactions work, and why is two-phase commit (2PC) often avoided?

**2PC** coordinates a transaction across multiple resources via a coordinator: in the **prepare** phase each participant locks resources and votes commit/abort; in the **commit** phase, if all voted commit, the coordinator tells everyone to commit. It provides atomicity across nodes.

It's avoided at scale because it is a **blocking protocol with a single point of failure**: if the coordinator crashes after participants prepared, they hold locks indefinitely (in-doubt transactions), reducing availability — it's a CP choice that hurts A. It also adds latency (two round trips) and scales poorly.

The common alternative is the **Saga pattern**: break the distributed transaction into a sequence of local transactions, each with a **compensating action** to undo it if a later step fails. Sagas give you availability and loose coupling at the cost of only eventual consistency and the burden of writing compensations.

```
2PC (atomic, blocking)          Saga (eventual, non-blocking)
 prepare → vote → commit         T1 → T2 → T3
   coordinator can block          if T3 fails: C2 → C1 (compensate)
```

### Q23. [Practical] A 2 TB Postgres table is causing slow queries and painful migrations. Walk through your options.

**Diagnose first:** is the pain reads (slow queries), writes (lock contention), or maintenance (vacuum, migrations)? Check bloat, index usage, and the worst plans.

**Options, escalating in cost:**

1. **Indexing & query tuning** — add covering/partial indexes; use `CREATE INDEX CONCURRENTLY`; rewrite N+1 queries. Cheapest, do this first.
2. **Partitioning** — declarative range/list partitioning (e.g., by month) so queries prune to one partition, and old partitions can be dropped instantly instead of `DELETE` (which bloats). Migrations and vacuums operate per-partition.
3. **Archive cold data** — move rows older than N months to cheaper storage (or a separate table/warehouse), shrinking the hot set.
4. **Read replicas** — offload reporting/read traffic.
5. **Shard** — only when a single primary can't hold the write working set. This is the most invasive; defer until partitioning + archiving are exhausted.

```
Before:                      After partitioning by month:
 ┌──────────────┐            ┌────┐┌────┐┌────┐┌────┐
 │  one 2 TB    │            │2026│ │2026│ │2026│ │... │
 │   table      │   ───►     │ 01 │ │ 02 │ │ 03 │ │    │
 └──────────────┘            └────┘└────┘└────┘└────┘
  scan everything             prune to one partition
```

**What I'd do in production:** Add the missing indexes immediately, then introduce monthly range partitioning with a rolling retention policy (drop old partitions), and archive cold data. Sharding stays in my back pocket until write volume genuinely exceeds one node.

### Q24. [Coding] Implement optimistic concurrency control to prevent lost updates.

**Problem:** Two users read the same record and both update it; the second write must not silently overwrite the first. Use a version column instead of locking.

```java
// Schema: account(id BIGINT PK, balance BIGINT, version INT)
@Transactional
public boolean updateBalance(long id, long newBalance, int expectedVersion) {
    int rows = jdbc.update(
        "UPDATE account SET balance = ?, version = version + 1 " +
        "WHERE id = ? AND version = ?",       // CAS on version
        newBalance, id, expectedVersion);

    if (rows == 0) {
        // Someone else updated it first; version no longer matches.
        throw new OptimisticLockException(
            "Stale write for account " + id + "; reload and retry");
    }
    return true;
}
```

**Time:** O(1) per update (indexed PK). **Space:** O(1) plus one `version` column.
**Why optimistic over pessimistic:** no locks held across user think-time, so it scales for low-contention workloads. Under high contention, retries thrash and you may prefer pessimistic `SELECT ... FOR UPDATE`. **Edge cases:** the caller must catch the conflict, reload, and retry (bounded retries to avoid livelock); JPA/Hibernate provides this for free via `@Version`. This is exactly how "lost update" is prevented without serializable isolation.

### Q25. [Theory] How do you choose isolation levels, and what anomalies does each prevent?

```
Level             Dirty Read  Non-repeatable  Phantom  Lost Update
READ UNCOMMITTED   possible     possible       possible  possible
READ COMMITTED     prevented    possible       possible  possible
REPEATABLE READ    prevented    prevented      possible* prevented*
SERIALIZABLE       prevented    prevented      prevented prevented
                                   (*MVCC engines like Postgres differ from the ANSI table)
```

- **Read Committed** (Postgres default): you only see committed data, but two reads in one transaction can differ. Fine for most OLTP.
- **Repeatable Read / Snapshot:** a transaction sees a consistent snapshot; in Postgres this also blocks phantoms via MVCC, though ANSI allows them.
- **Serializable:** transactions behave as if run one at a time. Postgres uses Serializable Snapshot Isolation (SSI), which aborts conflicting transactions rather than locking — correct but with retry overhead.

Choose the **lowest level that prevents the anomalies your business logic cannot tolerate**. Money and inventory often need `SERIALIZABLE` or explicit `SELECT FOR UPDATE`; analytics and feeds are happy at `READ COMMITTED`. Higher levels cost throughput (more aborts/locks).

---

## 🔴 Expert (15+ yrs)

### Q26. [Theory] How does Google Spanner provide global strong consistency, and what's the catch?

Spanner offers externally-consistent, globally-distributed ACID transactions — effectively beating the usual "you can't have CP + low global latency" intuition. It does this with **TrueTime**: GPS and atomic clocks in every datacenter give each node a clock with a bounded uncertainty interval `[earliest, latest]`. To commit, Spanner picks a timestamp and **waits out the uncertainty** (commit-wait, typically a few ms) so that no transaction can observe timestamps out of order. Combined with Paxos groups per shard and 2PC across shards, this yields linearizable global transactions.

**The catch:** it requires Google's specialized hardware (or cloud equivalent like AWS, where TrueTime-like services exist), commit-wait adds latency to writes, and cross-region transactions still pay round-trip cost. The lesson for system design is that strong global consistency is achievable but the price is paid in **write latency and infrastructure**, validating PACELC: even without partitions, consistency costs latency. CockroachDB and YugabyteDB approximate this without atomic clocks by using hybrid logical clocks (HLC) and wider uncertainty windows.

### Q27. [Practical] You're migrating a monolith's single Postgres to per-service databases. How do you do it safely with zero downtime?

**Context:** Database-per-service is core to microservices, but a big-bang split risks data loss and outages. I'd use the **Strangler Fig + dual-read/dual-write + CDC** approach.

```
Phase 1: New service reads from monolith DB (shared, temporary)
Phase 2: Replicate the service's tables to new DB via CDC (backfill + stream)
Phase 3: Dual-write (write both), read from new DB, verify parity
Phase 4: Cut writes over to new DB; monolith reads via API/CDC if needed
Phase 5: Remove old tables; break the shared FK dependencies
```

**Key decisions & trade-offs:**

- **Break foreign keys across the service boundary first** — replace cross-service joins with API calls or local denormalized copies kept current via events. This is the hardest and most important step.
- **CDC for backfill + ongoing sync** (Debezium) so the new DB catches up without downtime.
- **Dual-write with reconciliation** during the overlap, then a verification job comparing old vs new before flipping reads.
- **Outbox pattern** for the new service so it publishes domain events atomically.

**What I'd do in production:** Move one bounded context at a time, never split everything at once, keep a rollback path (don't drop old tables until the new path has soaked in production for weeks), and instrument drift detection. The risk is data divergence during dual-write — the reconciliation job is non-negotiable.

### Q28. [Theory] Discuss data residency, encryption, and how regulation shapes the data layer.

Regulation (GDPR, CCPA, HIPAA, PCI-DSS, India's DPDP, schemes requiring EU/in-country storage) directly constrains data-layer architecture:

- **Data residency / sovereignty:** certain data must physically stay in a region. This forces **geo-partitioning** — shard by region so EU users' rows live only in EU datacenters (Spanner and CockroachDB support row-level geo-partitioning by a region column).
- **Encryption:** at rest (TDE / disk encryption, KMS-managed keys) and in transit (TLS). Field-level encryption or tokenization for PII so even a DB dump doesn't leak SSNs/cards; PCI scope shrinks when card data is tokenized and never stored.
- **Right to erasure (GDPR Art. 17):** you must be able to delete a user's data — hard with immutable logs/event sourcing and backups. Patterns: **crypto-shredding** (encrypt each user's data with a per-user key, then delete the key to render it unrecoverable) and tombstone events.
- **Audit & access control:** least-privilege DB roles, row-level security, audit logging of access to sensitive tables; separate keys per tenant.

The system-design implication: residency and erasure requirements often **dictate your shard key and storage engine** before performance does. Designing these in late is far costlier than up front.

### Q29. [Practical] Design the storage layer for a write-heavy IoT/time-series platform ingesting 5M events/sec.

**Requirements:** massive write throughput, time-range queries, downsampled rollups, cost-efficient retention.

```
Devices → Kafka (buffer, backpressure) → stream processor (Flink)
              │                                │
              ▼                                ▼
   Raw hot store (Cassandra/                Rollups (1m/1h/1d)
   ScyllaDB or TimescaleDB)            → ClickHouse / Druid (OLAP)
              │
        TTL / tiering → object storage (S3, Parquet) for cold data
```

**Decisions & trade-offs:**

- **Storage engine:** LSM-based (Cassandra/Scylla) or a purpose-built TSDB — sequential writes absorb 5M/sec far better than a B-tree.
- **Partition key:** `(deviceId, timeBucket)` — co-locate a device's data while bucketing time so a single device-day doesn't grow unbounded and writes spread across partitions, avoiding the **monotonic-timestamp hot partition**.
- **Pre-aggregation:** compute rollups in the stream layer so dashboards query small aggregate tables, not raw points.
- **Tiering & retention:** raw data TTLs to object storage after days; queries over old data hit columnar Parquet. This controls cost — keeping 5M/sec of raw points hot forever is economically impossible.
- **Backpressure:** Kafka decouples ingest spikes from storage write capacity.

**What I'd do in production:** Scylla/Cassandra for hot raw + Flink for rollups + ClickHouse for analytical queries + S3 for cold tier. This is essentially the architecture behind monitoring systems and IoT platforms at hyperscale.

### Q30. [Behavioral] Tell me about a time a data-layer decision went wrong. What did you learn?

**Use STAR.** Example structure:

- **Situation:** We chose a single shared Postgres with `tenant_id` as the only shard dimension for a multi-tenant product, expecting tenants to be roughly equal.
- **Task:** As the senior engineer I owned the data model and signed off on it.
- **Action (what went wrong):** A handful of enterprise tenants grew 100× larger than median, creating hot shards and runaway table bloat; reporting queries on those tenants degraded the shared cluster for everyone (noisy neighbor). We had no per-tenant isolation and migrations on the giant tables took hours.
- **Resolution:** We introduced composite bucketing for whale tenants, moved the largest few to dedicated clusters, and added partitioning + per-tenant connection pool limits. We also added load-distribution monitoring we'd lacked.
- **What I learned:** (1) Model for the **distribution** of your data, not the average — assume skew. (2) Build hot-partition observability **before** you need it. (3) Reversibility matters: I now favor decisions that are cheap to change (start with logical separation that can become physical) and write down the assumptions that, if violated, should trigger a re-architecture.

The key behavioral signal is **ownership without blame**, concrete metrics, and a generalizable lesson — not just "we fixed it."

### Q31. [Theory] How would you evaluate adopting a new database for a 5-year bet at company scale?

A staff-level evaluation goes beyond benchmarks:

1. **Workload fit:** prove it against *your* access patterns and data distribution with a representative load test, not vendor TPC numbers. Include the worst-case queries and the skew.
2. **Consistency & correctness model:** what exactly does it guarantee under partition, failover, and clock skew? Read the Jepsen analyses — many databases have failed them.
3. **Operational maturity:** backup/restore (and *tested* restore), online schema changes, observability, upgrade story, failover behavior, and on-call burden. A DB you can't operate is a liability regardless of speed.
4. **Failure & recovery modes:** what happens on disk-full, split-brain, a corrupted node, a bad deploy? RPO/RTO.
5. **Ecosystem & lock-in:** drivers, ORM/CDC support, hiring pool, managed-service availability, and the cost/feasibility of migrating *off* it later (exit strategy).
6. **Cost at scale:** licensing, storage amplification, cross-AZ traffic, and the people cost of operating it.
7. **Organizational reality:** does the team have the skills? A technically superior DB the org can't run is the wrong choice.

The meta-point: a 5-year database bet is mostly a bet on **operability and consistency guarantees**, not peak throughput. I'd run a time-boxed proof of concept on a non-critical but representative workload, demand a tested disaster-recovery drill, and keep an exit path before committing the company.

### Q32. [Practical] How do you keep a denormalized read model consistent with its normalized source of truth at scale?

This is the **CQRS** read-model consistency problem. The write side keeps a normalized, ACID source of truth; the read side serves denormalized, query-optimized views — but they can drift.

**Approach:**

- **Event-driven projection:** the write side emits domain events (via the outbox pattern so they're atomic with the write); projectors consume them and update read models. Embrace **eventual consistency** and surface it in the UX (e.g., "your change is being applied").
- **Idempotent, ordered projections:** key events by aggregate ID so they're ordered per entity; track a processed-offset/version on the read model so replays and out-of-order delivery don't corrupt it.
- **Reconciliation/repair job:** periodically recompute read models from the source of truth (or compare checksums) to catch silent divergence from bugs or dropped events — assume drift *will* happen.
- **Rebuildability:** read models should be fully rebuildable by replaying the event log; treat them as disposable caches, not sources of truth.

```
Command → Write model (normalized, ACID) ──outbox──► event log
                                                        │
                              ┌─────────────────────────┤
                              ▼                         ▼
                   Read model A (search)      Read model B (dashboard)
                   (idempotent projector, versioned, rebuildable)
                              ▲
                   periodic reconciliation vs source of truth
```

**What I'd do in production:** outbox + log + idempotent projectors + a nightly reconciliation/repair job, with read models declared rebuildable from the log. The trade-off accepted is eventual consistency on reads in exchange for independent scaling and query optimization of each view.

---

## ✅ Key Takeaways

- **Map consistency requirements per access pattern, not per database** — most real systems are polyglot/hybrid, mixing CP and AP stores.
- **Model NoSQL around your queries; normalize SQL for integrity and denormalize for read speed.** Know which way you're trading.
- **Replicas scale reads; sharding scales writes.** They solve different problems.
- **Shard-key choice is the highest-leverage data-layer decision** — optimize for distribution and access locality, and design against hot partitions and monotonic keys.
- **Prefer the outbox pattern + CDC over dual-writes** to keep multiple stores consistent; prefer Sagas over 2PC for distributed workflows.
- **Choose the lowest isolation level that prevents the anomalies your business can't tolerate**; use optimistic concurrency (versioning) for low-contention lost-update protection.
- **Regulation (residency, erasure, encryption) can dictate your shard key and engine before performance does** — design it in early.
- **At staff level, a database choice is a bet on operability and consistency guarantees, not peak throughput.**

## ⚠️ Common Pitfalls

- Using a monotonic key (timestamp, auto-increment) as the partition dimension → all writes hit the newest partition (hot shard).
- Choosing the average data distribution instead of the actual skewed distribution → whale tenants/celebrity users blow up one shard.
- Dual-writing to DB and message broker in two steps → inconsistency on crash; use the transactional outbox instead.
- Over-indexing write-heavy tables, or adding indexes without `CONCURRENTLY` and locking a live table.
- Assuming read replicas give read-your-own-writes consistency — replication lag breaks it.
- Reaching for distributed 2PC and inheriting its blocking failure mode when a Saga would do.
- Sharding prematurely before exhausting partitioning, archiving, and replicas — sharding is the most expensive and least reversible step.
- Forgetting GDPR right-to-erasure when using immutable event logs/backups (use crypto-shredding).
- Cache stampede on a hot key after eviction — coalesce requests or use per-key locks.
- Treating "CA" in CAP as a real design choice — under partition you're choosing CP or AP.

## 📚 Further Reading

- *Designing Data-Intensive Applications* — Martin Kleppmann (the definitive text on replication, partitioning, consistency).
- *Database Internals* — Alex Petrov (storage engines, B-trees vs LSM-trees, distributed systems internals).
- Google's *Spanner: Google's Globally-Distributed Database* (OSDI 2012) and the *TrueTime* paper.
- Amazon's *Dynamo: Amazon's Highly Available Key-value Store* (SOSP 2007) — quorum/leaderless design origins.
- [Jepsen analyses](https://jepsen.io/analyses) — rigorous correctness testing of real databases under partition.
- [Debezium documentation](https://debezium.io/documentation/) and [Microservices.io patterns](https://microservices.io/patterns/data/) — CDC, outbox, saga, CQRS in practice.
