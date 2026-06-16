# MongoDB Interview Preparation Guide

MongoDB is a distributed, document-oriented NoSQL database that stores data as flexible BSON documents, scales horizontally through sharding, and provides high availability through replica sets. This guide covers MongoDB from fundamentals through expert-level distributed-systems internals, with Java (mongodb-driver-sync / Spring Data MongoDB) examples and is current through 2026 (MongoDB 8.x).

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

### Q1. [Theory] What is MongoDB and how does the document model differ from the relational model?

MongoDB is a NoSQL database that stores data as **documents** — self-describing, hierarchical key-value structures — instead of rows in fixed-schema tables. A *collection* is the rough analog of a table, and a *document* is the analog of a row, but documents within a collection need not share the same shape (schema is flexible/dynamic). The key conceptual shift is from **normalization** (splitting an entity across many tables joined at query time) to **localization** (storing data that is read together inside one document).

The "why" is locality of access: a relational order with line items might span three tables and require joins, whereas a MongoDB order document embeds its line items, so a single read fetches the entire aggregate. This favors read performance and developer velocity for object-shaped data, at the cost of relational integrity guarantees (no foreign keys, limited cross-document atomicity historically). MongoDB is best understood as an *aggregate-oriented* store: model around the unit of data you read and write together.

```
RELATIONAL                          MONGODB (document)
+-----------+   +-------------+     {
| orders    |   | line_items  |       "_id": 42,
|-----------|   |-------------|       "customer": "Ana",
| id  | cust|   | order_id|sku |       "items": [
| 42  | Ana |   | 42      |A99 |          {"sku":"A99","qty":2},
+-----------+   | 42      |B12 |          {"sku":"B12","qty":1}
   JOIN  ------>+-------------+        ]
                                     }
```

### Q2. [Theory] What is BSON and why does MongoDB use it instead of plain JSON?

BSON (Binary JSON) is the binary-encoded serialization format MongoDB uses to store documents and transmit them over the wire. It extends JSON with **additional data types** that JSON lacks — `Date`, `ObjectId`, `Decimal128` (exact decimal for money), `BinData`, 32/64-bit integers, and `Timestamp` — and it is **length-prefixed**, meaning each document and sub-element records its byte length so the engine can skip fields without parsing them character-by-character.

The advantages over text JSON are: (1) **traversal speed** — length prefixes let the engine jump directly to a field; (2) **type fidelity** — a number stored as `Decimal128` stays exact, avoiding floating-point money bugs; (3) **compactness** for many types. The trade-off is BSON is slightly larger than minified JSON for string-heavy data because of the length headers. A single BSON document is capped at **16 MB**; larger binary payloads should use GridFS or object storage.

### Q3. [Theory] What is an `_id` field and what is an ObjectId?

Every MongoDB document must have a unique `_id` field that serves as its primary key; if you don't supply one, the driver/server generates an `ObjectId`. An `ObjectId` is a 12-byte value: **4 bytes timestamp (seconds since epoch) + 5 bytes random per-process value + 3 bytes incrementing counter**. This design makes ObjectIds roughly monotonically increasing and globally unique without coordination, so clients can generate them client-side without a round-trip.

The roughly-sortable-by-time property is useful (you can extract creation time from the ID), but it also means default `_id` indexes are *right-heavy* — new inserts cluster at the high end of the B-tree, which matters for shard-key choice (see advanced section). You can override `_id` with any unique value (e.g., a natural key like an email or a UUID) when that suits your access pattern better.

### Q4. [Practical] How do you perform basic CRUD operations in Java using the MongoDB driver?

Use the official `mongodb-driver-sync`. The key types are `MongoClient`, `MongoDatabase`, and `MongoCollection<Document>`. Always create one `MongoClient` per application (it is thread-safe and manages a connection pool) — never per request.

```java
import com.mongodb.client.*;
import org.bson.Document;
import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Updates.*;

try (MongoClient client = MongoClients.create("mongodb://localhost:27017")) {
    MongoCollection<Document> users =
        client.getDatabase("app").getCollection("users");

    // CREATE
    users.insertOne(new Document("_id", "u1")
            .append("name", "Ana").append("age", 30).append("tags", java.util.List.of("admin")));

    // READ  (filter: age > 25, sorted by name)
    for (Document d : users.find(gt("age", 25)).sort(new Document("name", 1)).limit(10)) {
        System.out.println(d.toJson());
    }

    // UPDATE (atomic in-place; $set + $inc)
    users.updateOne(eq("_id", "u1"), combine(set("age", 31), inc("loginCount", 1)));

    // DELETE
    users.deleteOne(eq("_id", "u1"));
}
```

Notes: `find()` returns a lazy cursor (results stream as you iterate); `updateOne`/`updateMany` use atomic update operators (`$set`, `$inc`, `$push`); and the try-with-resources block ensures the pool is closed cleanly on shutdown.

### Q5. [Theory] What is an index in MongoDB and why is it important?

An index is an ordered B-tree data structure that stores a small portion of the collection's data (the indexed fields plus a pointer to the full document) so queries can locate matching documents without scanning every document (a **COLLSCAN**). Without an index, a query on a million-document collection reads all million documents; with a matching index, it walks the B-tree in `O(log n)`.

The default index is on `_id`. You create others explicitly. The trade-off is that every index consumes RAM and disk and **slows writes** (each insert/update must maintain all relevant indexes), so indexes are a deliberate read/write balance, not free speedups. Use `explain("executionStats")` to confirm a query uses an index (`IXSCAN`) rather than scanning (`COLLSCAN`).

```java
import com.mongodb.client.model.Indexes;
users.createIndex(Indexes.ascending("age"));        // single field
users.createIndex(Indexes.compoundIndex(
        Indexes.ascending("status"), Indexes.descending("createdAt")));  // compound
```

### Q6. [Practical] When would you choose MongoDB over a relational database, and when would you avoid it?

**Choose MongoDB when:** your data is naturally document/aggregate-shaped (product catalogs, user profiles, content management, event/log capture, IoT telemetry); your schema evolves rapidly; you need horizontal write scale-out beyond a single machine; or read patterns are dominated by fetching one rich object by key. Companies like eBay, Forbes, and Sega have used it for catalogs and content where flexible nested data and scale matter.

**Avoid (or be cautious) when:** your workload is highly relational with many-to-many joins across large tables and complex multi-entity transactions (an inventory + ledger + accounting system); you need strong, mature SQL analytics and BI tooling; or your team needs the rigid integrity guarantees of constraints and foreign keys. While MongoDB has supported multi-document ACID transactions since 4.0, heavy transactional contention is still better served by a tuned RDBMS. The honest answer in an interview: pick based on access pattern and consistency needs, not hype.

---

## 🟡 Intermediate (3–7 yrs)

### Q7. [Theory] Embedding vs. referencing: how do you decide your schema design?

This is the central MongoDB design decision. **Embed** (nest related data inside the parent document) when the data is accessed together, has a contained one-to-few or one-to-many relationship, and doesn't grow unbounded. **Reference** (store an `_id` pointing to a document in another collection) when relationships are many-to-many, the related entity is large or shared, or the child set grows without bound.

The decision rules of thumb:

- **One-to-few** (a person's few addresses): embed.
- **One-to-many** (a blog post's comments, bounded): embed an array — but watch the 16 MB cap and the *unbounded array antipattern*.
- **One-to-squillions** (a server's millions of log lines): reference from the child side (each log line stores `hostId`).
- **Many-to-many** (students ↔ courses): reference, often with arrays of IDs on one or both sides.

```
EMBED (read together, bounded)        REFERENCE (shared / unbounded / M:N)
order { items: [ {...}, {...} ] }     order { customerId: 7 }
                                      customer { _id: 7, name: "Ana" }
```

The trade-off: embedding gives single-read locality and atomic updates of the whole aggregate, but duplicates data and risks document bloat; referencing keeps documents lean and avoids duplication but requires application-side joins or `$lookup` and gives up single-document atomicity across the boundary.

### Q8. [Coding] Design a schema for an e-commerce order system and write the Java code to create it.

**Problem:** Model orders with line items, a customer reference, and a denormalized shipping snapshot. Line items are bounded (a cart rarely exceeds a few dozen items) so embed them; the customer is shared across many orders so reference it; the shipping address is *snapshotted* (copied) because it must reflect the address *at order time*, not the customer's current address.

```java
import org.bson.Document;
import java.time.Instant;
import java.util.List;

Document order = new Document("_id", "ord_1001")
    .append("customerId", "cust_42")          // REFERENCE (shared entity)
    .append("status", "PLACED")
    .append("placedAt", Instant.now())
    .append("shipTo", new Document()          // SNAPSHOT (denormalized on purpose)
        .append("name", "Ana Diaz")
        .append("street", "12 Oak St")
        .append("city", "Austin").append("zip", "73301"))
    .append("items", List.of(                 // EMBED (bounded, read together)
        new Document("sku", "BOOK-9").append("qty", 1).append("price", 24.99),
        new Document("sku", "PEN-3").append("qty", 5).append("price", 1.50)))
    .append("totalCents", 32449);             // pre-computed for fast reads

orderCollection.insertOne(order);

// Indexes supporting the dominant queries:
orderCollection.createIndex(Indexes.ascending("customerId"));         // "my orders"
orderCollection.createIndex(Indexes.compoundIndex(
        Indexes.ascending("status"), Indexes.descending("placedAt"))); // ops dashboard
```

**Why these choices:** the order is the aggregate boundary — placing an order is one atomic `insertOne`. Storing `totalCents` precomputed avoids recomputing it on every read (the *computed pattern*). Snapshotting `shipTo` protects historical correctness. **Edge cases:** if items could grow unbounded (e.g., a B2B order with 100k line items), switch to referencing line items in their own collection to respect the 16 MB limit. **Complexity:** the dominant read ("show my order") is `O(log n)` via the `_id` index plus one document fetch.

### Q9. [Theory] Explain compound indexes and the ESR (Equality, Sort, Range) rule.

A compound index covers multiple fields in a defined order; the field order is critical because a compound index can serve a query only if the query uses a **prefix** of the index fields. An index on `{a:1, b:1, c:1}` supports queries on `{a}`, `{a,b}`, and `{a,b,c}`, but *not* a query on `{b}` alone or `{c}` alone.

The **ESR rule** dictates the optimal ordering: place **Equality** fields first, then the **Sort** field, then **Range** fields last. Equality fields narrow the scan to a contiguous range; the sort field positioned next lets MongoDB return results already ordered (avoiding an in-memory SORT stage, which has a 100 MB limit and can fail); range fields go last because a range "opens up" the index and prevents subsequent fields from being used efficiently for equality.

```
Query: status == "ACTIVE"  AND  score > 50  ORDER BY createdAt
Bad index:  {score, createdAt, status}   -> range first kills efficiency
Good index: {status, createdAt, score}   -> E . . . S . . . R
            ^equality   ^sort serves ORDER BY   ^range last
```

### Q10. [Practical] You have a slow query. Walk me through diagnosing and fixing it.

In production I'd start with `explain("executionStats")` to see the actual plan and counters. The smell to look for is `COLLSCAN` (full collection scan) as the winning stage, and a large ratio of `totalDocsExamined` to `nReturned` — examining 1,000,000 docs to return 10 means the query is doing 100,000× more work than necessary.

```java
Document plan = users.find(and(eq("status","ACTIVE"), gt("age",25)))
        .sort(new Document("createdAt", -1))
        .explain(ExplainVerbosity.EXECUTION_STATS);
// inspect: winningPlan.stage, executionStats.totalDocsExamined vs nReturned
```

The fix sequence: (1) add a compound index following ESR (`{status:1, createdAt:-1, age:1}`); (2) re-run `explain` and confirm `IXSCAN` with `totalKeysExamined ≈ nReturned`; (3) consider a **covered query** — if the index contains every field the query projects, MongoDB never touches the documents (`totalDocsExamined: 0`); (4) check whether an in-memory `SORT` stage appears (it means the index didn't satisfy ordering — re-order per ESR). Beyond indexing: ensure the working set fits in RAM (the WiredTiger cache), and watch for the index not being *selective* enough (indexing a boolean with 50/50 distribution barely helps).

### Q11. [Coding] Write an aggregation pipeline (in Java) that computes total revenue per product category for the last 30 days.

**Problem:** Given orders with embedded items, group by category and sum revenue, but only for recent orders, and return the top 5 categories. The aggregation pipeline is MongoDB's data-processing framework — an array of stages where each stage transforms the document stream.

```java
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Accumulators;
import com.mongodb.client.model.Sorts;
import static com.mongodb.client.model.Filters.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.bson.Document;

Instant cutoff = Instant.now().minus(30, ChronoUnit.DAYS);

List<Document> result = orders.aggregate(List.of(
    Aggregates.match(gte("placedAt", cutoff)),          // 1. filter early (uses index!)
    Aggregates.unwind("$items"),                         // 2. one doc per line item
    Aggregates.group("$items.category",                  // 3. group + sum
        Accumulators.sum("revenue",
            new Document("$multiply", List.of("$items.qty", "$items.price"))),
        Accumulators.sum("unitsSold", "$items.qty")),
    Aggregates.sort(Sorts.descending("revenue")),        // 4. order
    Aggregates.limit(5)                                  // 5. top 5
)).into(new java.util.ArrayList<>());
```

**Key insight:** put `$match` **first** so it uses an index on `placedAt` and shrinks the stream before the expensive `$unwind`/`$group`. Stage order is a performance lever, not just semantics.

```
[match]  -->  [unwind]  -->  [group]  -->  [sort]  -->  [limit]
filter        explode        aggregate     order       top-N
(indexed)     items array    by category
```

**Complexity:** `$match` is `O(log n + m)` with an index (m = matched docs); `$group` is `O(m)` with a hash. **Edge cases:** if `items` can be missing/empty, `$unwind` drops those docs — use `{preserveNullAndEmptyArrays:true}` if you need them; for huge result sets exceeding 100 MB per stage, set `allowDiskUse(true)`.

### Q12. [Theory] What is the aggregation pipeline and how does it differ from a simple `find()`?

The aggregation pipeline processes documents through a sequence of stages (`$match`, `$group`, `$project`, `$lookup`, `$unwind`, `$sort`, `$facet`, `$bucket`, etc.), where each stage's output is the next stage's input — conceptually a Unix pipe for documents. `find()` retrieves and filters documents but cannot reshape, group, join, or compute aggregates; the pipeline can.

The pipeline is MongoDB's answer to SQL `GROUP BY`, `JOIN` (`$lookup`), window functions (`$setWindowFields`), and computed columns. Performance considerations: stages like `$match` and `$sort` placed early can leverage indexes, while `$group` and `$unwind` are blocking/streaming transformations that work in memory (each stage capped at 100 MB unless `allowDiskUse` is set). MongoDB 8 also pushes more aggregation work into optimized native operators. The pipeline runs server-side, so it minimizes data shipped to the client.

### Q13. [Theory] Explain the different index types: multikey, text, geospatial, and TTL.

MongoDB offers specialized index types beyond single/compound:

- **Multikey index**: automatically created when you index a field holding an *array*; MongoDB indexes each array element separately, enabling queries like "find docs where `tags` contains 'java'". A document can have at most one array field per compound multikey index (you can't multikey two array fields together).
- **Text index**: tokenizes string content for full-text search with stemming and stop-words; one text index per collection, queried via `$text`/`$search`. For richer search, Atlas Search (Lucene-based) is the modern choice.
- **Geospatial index** (`2dsphere`): indexes GeoJSON points/polygons for proximity (`$near`) and containment (`$geoWithin`) queries on a spherical Earth model — e.g., "restaurants within 2 km".
- **TTL index**: a single-field index on a Date with `expireAfterSeconds`; a background thread deletes expired documents automatically. Ideal for sessions, OTPs, and ephemeral caches.

```java
collection.createIndex(Indexes.text("description"));                  // text
collection.createIndex(Indexes.geo2dsphere("location"));             // geo
collection.createIndex(Indexes.ascending("createdAt"),
        new IndexOptions().expireAfter(24L, TimeUnit.HOURS));        // TTL
```

### Q14. [Practical] How do you handle schema migrations in a schemaless database?

"Schemaless" is a misnomer — there is an *application* schema, just no *enforced* database schema. The common patterns:

1. **On-read migration (lazy)**: keep a `schemaVersion` field per document; on read, if the version is old, transform it in the application and write it back. Zero downtime, no big migration job, but every reader must understand all versions.
2. **Background batch migration**: run an idempotent job that streams documents and updates them in batches. Good when you need to retire old shapes.
3. **Aggregation `$merge`/`$out`**: transform via pipeline and write back.
4. **Schema validation**: starting MongoDB 3.6+, attach a JSON-Schema validator to a collection to *enforce* shape on writes going forward (`validationLevel: moderate` to grandfather old docs).

In production I prefer on-read migration combined with a slow background sweep and a JSON-Schema validator in `moderate` mode so new writes are clean while old documents migrate opportunistically. The key principle: migrations must be **idempotent** and **backward-compatible** during the rollout window so old and new app versions coexist.

### Q15. [Theory] What are read concern, write concern, and read preference?

These three knobs tune the consistency/durability/availability trade-off per operation:

- **Write concern (`w`)** — how many nodes must acknowledge a write before it's considered successful. `w:1` (primary only — fast, can lose data on failover), `w:"majority"` (a majority of voting members — durable, survives failover; the safe default), and `j:true` (the write is persisted to the on-disk journal). Higher `w` = more durable but higher latency.
- **Read concern** — what *consistency* a read guarantees: `local` (whatever the node has, may be rolled back), `majority` (only data acknowledged by a majority, never rolled back), `linearizable` (reflects all prior majority-acked writes, primary-only, strongest), and `snapshot` (transaction isolation).
- **Read preference** — *which* node to read from: `primary` (default, strongest consistency), `primaryPreferred`, `secondary`, `secondaryPreferred`, `nearest` (lowest latency). Reading from secondaries scales reads but risks staleness (replication lag).

```java
MongoCollection<Document> c = db.getCollection("orders")
    .withWriteConcern(WriteConcern.MAJORITY.withJournal(true))
    .withReadConcern(ReadConcern.MAJORITY)
    .withReadPreference(ReadPreference.primaryPreferred());
```

The combination `w:majority` + `readConcern:majority` gives "read your writes won't be rolled back" — the typical production choice for correctness-sensitive data.

### Q16. [Coding] Write a bulk-write operation that upserts documents efficiently.

**Problem:** Ingest a batch of sensor readings; insert new sensors, update existing ones, in a single network round-trip. Looping `updateOne` per item is the antipattern (N round-trips); `bulkWrite` batches them.

```java
import com.mongodb.client.model.*;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;

List<WriteModel<Document>> ops = new ArrayList<>();
for (Reading r : batch) {
    ops.add(new UpdateOneModel<>(
        Filters.eq("_id", r.sensorId()),
        Updates.combine(
            Updates.set("lastValue", r.value()),
            Updates.set("lastSeen", r.timestamp()),
            Updates.inc("readingCount", 1)),
        new UpdateOptions().upsert(true)));   // insert if absent
}
// ordered=false lets independent ops continue past one failure & run in parallel
BulkWriteResult res = sensors.bulkWrite(ops, new BulkWriteOptions().ordered(false));
System.out.printf("matched=%d upserts=%d%n",
        res.getMatchedCount(), res.getUpserts().size());
```

**Why `ordered(false)`:** with ordered execution, the first error halts the batch; unordered lets the rest proceed and can execute in parallel, which is faster for independent upserts. **Complexity:** one round-trip for the whole batch instead of N. **Edge cases:** a duplicate-key error on one op in an unordered batch is collected into `MongoBulkWriteException` without aborting the others; keep batch sizes reasonable (the driver auto-splits, but ~1,000 ops/batch is a sane chunk).

---

## 🟠 Advanced (8–12 yrs)

### Q17. [Theory] Explain replica sets and the election process in detail.

A replica set is a group of `mongod` processes maintaining the same data set for high availability. One member is the **primary** (accepts all writes); the others are **secondaries** that asynchronously replicate the primary's operation log (**oplog**, a capped collection of idempotent write operations). Clients write to the primary; secondaries apply oplog entries to converge.

When the primary becomes unreachable, the set runs an **election** using a Raft-derived protocol (protocol version 1). A secondary that notices the primary is down (via missed heartbeats, default 2s, election timeout 10s) calls for votes; a candidate must receive votes from a **majority** of voting members to win, which is why an odd number of voting members (3, 5, 7) is required to avoid split-brain. Election factors include priority, the freshness of each member's oplog (the most up-to-date node is favored), and term numbers.

```
        writes
client ------> [ PRIMARY ] --- oplog ---> [ SECONDARY ]
                   |  heartbeats (2s)  \-> [ SECONDARY ]
                   X  primary dies
                       election: secondaries request votes,
                       majority elects most up-to-date node -> NEW PRIMARY
```

**Key implications:** a write with `w:majority` is durable across a failover because it survives on a majority; during an election (typically ~10–12s) the set has no primary and writes block; an **arbiter** can vote but holds no data (use to break ties cheaply, but avoid in production where data redundancy matters because it lowers fault tolerance for `w:majority`).

### Q18. [Theory] How does sharding work, and what makes a good shard key?

Sharding partitions a collection horizontally across multiple shards (each itself a replica set), enabling scale beyond one machine's storage/throughput. The architecture has three components: **shards** (hold data ranges), **config servers** (a replica set storing cluster metadata — the chunk map), and **`mongos`** routers (stateless query routers that consult config servers and route ops to the right shard(s)).

Data is split into **chunks** by **shard key** ranges; the balancer migrates chunks between shards to keep them even. The shard key is the single most important sharding decision because it determines write distribution, query routing, and chunk balance. A good shard key has:

1. **High cardinality** — enough distinct values to split into many chunks.
2. **Low frequency / even distribution** — no single value dominates.
3. **Non-monotonic** — monotonically increasing keys (like default `ObjectId` or timestamps) send all new writes to one chunk → **hot shard**.
4. **Query alignment** — common queries should include the shard key so `mongos` can do *targeted* routing (one shard) instead of a *scatter-gather* broadcast to all shards.

```
                   +-- mongos (router) --+
   client ---------|  consults config    |
                   +----------+-----------+
                              |
        +---------------------+---------------------+
        v                     v                     v
   [Shard A]             [Shard B]             [Shard C]
   chunks 0-99           chunks 100-199        chunks 200-299
   (replica set)         (replica set)         (replica set)
```

### Q19. [Practical] You sharded on a monotonically increasing key and now have a hot shard. How do you fix it?

This is the classic sharding mistake: sharding on `ObjectId`, an auto-increment ID, or a raw timestamp. Because these always grow, the chunk holding the current max range receives **100% of inserts**, so one shard is saturated while others idle — you get the cost of sharding with none of the write scale-out.

**Fixes, in order of preference:**

1. **Hashed shard key**: shard on a *hashed* version of the key (`sh.shardCollection(ns, {userId: "hashed"})`). The hash randomizes placement so writes spread evenly. Trade-off: range queries on that key become scatter-gather. Best when access is by exact key.
2. **Compound shard key with a high-cardinality leading field**: e.g., `{tenantId: 1, timestamp: 1}` so writes spread across tenants while still allowing per-tenant range scans. This is the multi-tenant SaaS pattern.
3. **Pre-splitting + zone sharding**: pre-create chunks and pin ranges to shards for predictable distribution.

**The hard truth:** you generally cannot change a shard key cheaply on old versions. MongoDB 5.0+ supports `reshardCollection`, which rewrites the collection under a new shard key online (at the cost of temporary double storage and significant I/O). In practice: stop the bleeding by resharding to a hashed or compound key, and pick the new key by analyzing the *actual* query and write distribution. MongoDB 8.0 made resharding faster and added the ability to reshard to the *same* key for rebalancing.

### Q20. [Coding] Implement a multi-document ACID transaction in Java (e.g., transfer between two accounts).

**Problem:** Move money between two account documents atomically — both updates commit or neither does. Since MongoDB 4.0 (replica sets) / 4.2 (sharded clusters), multi-document transactions provide snapshot isolation and all-or-nothing semantics.

```java
import com.mongodb.client.*;
import com.mongodb.*;
import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Updates.*;

void transfer(MongoClient client, String from, String to, long cents) {
    TransactionOptions txnOpts = TransactionOptions.builder()
        .readConcern(ReadConcern.SNAPSHOT)
        .writeConcern(WriteConcern.MAJORITY)
        .build();

    try (ClientSession session = client.startSession()) {
        session.withTransaction(() -> {              // auto-retries on transient errors
            MongoCollection<Document> acct =
                client.getDatabase("bank").getCollection("accounts");

            Document src = acct.find(session, eq("_id", from)).first();
            if (src == null || src.getLong("balance") < cents)
                throw new IllegalStateException("insufficient funds"); // aborts txn

            acct.updateOne(session, eq("_id", from), inc("balance", -cents));
            acct.updateOne(session, eq("_id", to),   inc("balance", cents));
            return null;
        }, txnOpts);
    }
}
```

**Key points:** every operation must pass the **same `session`** or it won't join the transaction; `withTransaction` automatically retries on `TransientTransactionError` (e.g., a write conflict) and on `UnknownTransactionCommitResult`, which is why you should make the body idempotent and side-effect-free until commit. **Trade-offs:** transactions hold locks and have a default 60-second runtime limit; they're an escape hatch, not the default — if you find yourself needing them frequently, your schema probably should embed the data into a single document so a single-document atomic update suffices. **Edge cases:** throwing inside the body aborts and rolls back; long transactions can hit `TransactionTooLargeForCache`.

### Q21. [Theory] Explain MongoDB's consistency model and the causal consistency guarantee.

By default, reads from the primary with `readConcern:majority` give **strong consistency** for that node, but reads from secondaries can be **eventually consistent** due to replication lag. MongoDB layers several guarantees on top:

- **Read-your-own-writes** is *not* guaranteed by default if you write to the primary and then read from a secondary — the secondary may not have replicated yet.
- **Causal consistency** is provided per *session*: within a `ClientSession` with `causalConsistency: true` (the default for sessions), MongoDB tracks operation/cluster times so that a read in the session reflects all writes that *causally preceded* it, even across primary/secondary. It guarantees "read your writes," "monotonic reads," "monotonic writes," and "writes follow reads."
- **Linearizable read concern** on the primary gives the strongest single-object guarantee (reflects all completed majority writes) but is slow and primary-only.

```
Without session:  write(primary) ... read(secondary) -> may miss the write
With causal session: every op carries clusterTime; read waits until the
                     secondary has applied >= the session's last write time.
```

The practical takeaway: for correctness-sensitive read-after-write across secondaries, use a causally-consistent session; for strict global ordering, use `linearizable` on the primary; accept staleness only where the use case tolerates it.

### Q22. [Practical] What are change streams and how would you use them in an event-driven architecture?

Change streams let an application **subscribe to real-time data changes** (inserts, updates, deletes, replaces) on a collection, database, or whole deployment, without polling. They are built on the oplog and `readConcern:majority`, so they only surface changes that are durable (won't be rolled back), and they are **resumable** — each event carries a `resumeToken` you can persist to restart the stream exactly where you left off after a crash.

```java
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import java.util.List;
import org.bson.Document;

orders.watch(List.of(Aggregates.match(
        Filters.in("operationType", List.of("insert", "update")))))
      .fullDocument(com.mongodb.client.model.changestream.FullDocument.UPDATE_LOOKUP)
      .forEach((ChangeStreamDocument<Document> change) -> {
          publishToKafka(change.getFullDocument());   // fan out to consumers
          saveResumeToken(change.getResumeToken());    // for crash recovery
      });
```

**Use cases:** outbox/CDC pipelines (stream changes into Kafka), cache invalidation, real-time notifications, materialized-view maintenance, and audit logging. **Why over polling:** lower latency, no missed updates between polls, and exactly-resumable. **Production notes:** `UPDATE_LOOKUP` fetches the full current document (not just the delta) — convenient but adds a read per event and may reflect a *later* state than the moment of change; the resume token must be persisted transactionally with your downstream side-effect to achieve effectively-once delivery. Change streams require a replica set (even a single-node one) because they depend on the oplog.

### Q23. [Theory] What is the WiredTiger storage engine and how does its concurrency/caching work?

WiredTiger is MongoDB's default storage engine (since 3.2). Its defining properties: **document-level concurrency control** via MVCC (multiple writers to *different* documents in the same collection proceed concurrently — a huge improvement over the old MMAPv1 collection-level locking), **compression** (snappy by default for collections, prefix compression for indexes), and a **B-tree on-disk** layout with a checkpoint + journal durability model.

The performance-critical piece is the **WiredTiger cache**, which by default is `max(50% of (RAM − 1GB), 256MB)`. This cache holds the **working set** — the indexes and documents actively touched. The golden rule of MongoDB performance: *your working set (hot data + indexes) should fit in the WiredTiger cache.* When it doesn't, the engine evicts pages and reads from disk, and latency collapses. MVCC means writers create new versions and readers see a consistent snapshot; conflicting concurrent updates to the *same* document raise a `WriteConflict` that the server retries internally. Checkpoints flush a consistent snapshot to disk every 60 seconds (or 2 GB of journal), and the journal provides durability between checkpoints.

### Q24. [Practical] Design the data model and sharding strategy for a multi-tenant SaaS analytics platform ingesting 500K events/sec.

**Scenario:** High-volume event ingestion, per-tenant isolation, time-range analytics queries. **Approach:**

- **Collection design**: use the **bucket pattern** for time-series — instead of one document per event (500K docs/sec crushes the index), bucket events into documents holding a window of measurements (or use MongoDB's native **time-series collections**, 5.0+, which do this automatically with columnar internal storage and automatic clustering on time). Time-series collections dramatically reduce storage and speed up range scans.
- **Shard key**: `{tenantId: 1, timestamp: 1}` — `tenantId` leading gives high cardinality and even write spread across tenants (avoiding the monotonic-timestamp hot shard), while keeping each tenant's data co-located for targeted range queries. For a few huge "whale" tenants that would still hot-spot, add a hashed sub-field or use zone sharding.
- **Write path**: `w:1` (or `w:majority` only for critical events) and unordered `bulkWrite` batching to maximize throughput; ingest is append-only.
- **Read path**: pre-aggregate into rollup collections via scheduled aggregation `$merge` (the *materialized view* pattern) so dashboards read small summaries, not raw events; apply TTL indexes to expire raw events after the retention window.

```
ingest --> [time-series coll, shard {tenantId, ts}] --TTL--> expire raw
              |  scheduled $merge rollups
              v
          [hourly_rollups]  <-- dashboards read these (cheap)
```

**Trade-offs:** the bucket/time-series approach optimizes for time-range scans at the cost of per-event update flexibility; pre-aggregation adds pipeline complexity but is mandatory at this scale. This mirrors how observability vendors and IoT platforms architect MongoDB-backed ingestion.

### Q25. [Coding] Write an aggregation with `$lookup` to join orders with customers, handling the "no join" reputation.

**Problem:** MongoDB *can* join via `$lookup` (a left outer join). Enrich orders with customer details from a referenced collection.

```java
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import java.util.List;
import org.bson.Document;

List<Document> enriched = orders.aggregate(List.of(
    Aggregates.match(Filters.eq("status", "PLACED")),
    Aggregates.lookup(
        "customers",      // foreign collection
        "customerId",     // local field
        "_id",            // foreign field
        "customer"),      // output array field
    Aggregates.unwind("$customer"),        // flatten the 1-element array
    Aggregates.project(new Document("_id", 1)
        .append("totalCents", 1)
        .append("customerName", "$customer.name")
        .append("customerTier", "$customer.tier"))
)).into(new java.util.ArrayList<>());
```

**Performance caveat:** `$lookup` runs an indexed lookup against the foreign collection **per input document** — ensure the foreign field (`customers._id` here, already indexed) is indexed or it becomes a per-document collection scan (catastrophic). Place a selective `$match` *before* the `$lookup` so you join fewer documents. **When to avoid:** if you find yourself doing `$lookup` on every read, that's a signal you over-normalized — embedding or denormalizing the customer name/tier into the order (accepting controlled duplication) is usually the better MongoDB design. **Complexity:** roughly `O(n_matched × log m)` where m is the foreign collection size (thanks to the foreign index).

---

## 🔴 Expert (15+ yrs)

### Q26. [Theory] Explain how MongoDB achieves majority-committed durability and how the oplog and rollbacks interact during failover.

A write with `w:"majority"` is acknowledged only after a majority of voting data-bearing members have applied it to their oplogs. Each member tracks a **majority commit point** — the oldest optime that a majority has reached — computed from heartbeats and oplog progress. Reads with `readConcern:majority` return data at or before this commit point, guaranteeing the data won't be rolled back.

A **rollback** happens when a former primary that had accepted writes *not* replicated to a majority rejoins after a new primary was elected. Its un-replicated writes diverge from the new primary's history; on rejoin it detects the common point in the oplog, **rolls back** the divergent operations (writing them to a rollback file / rollback collection), and resyncs from the new primary. This is precisely why `w:1` writes can be silently lost on failover and why financial systems must use `w:"majority"`. The protocol (PV1, Raft-like with terms) ensures a single primary per term and that the elected primary has the most complete majority-committed log among the voters, bounding what can be rolled back to only non-majority writes.

```
PRIMARY(term 5) accepts W6 (w:1, not replicated) ... crashes
SECONDARY -> elected PRIMARY(term 6), never saw W6
old primary rejoins -> finds common point at W5 -> ROLLS BACK W6 to file
```

### Q27. [Theory] How do distributed (sharded) transactions and the two-phase commit work in MongoDB, and what are their costs?

Single-shard transactions behave like replica-set transactions. **Cross-shard transactions** (4.2+) require coordination because multiple shards must commit atomically. MongoDB uses a **two-phase commit (2PC)** orchestrated by a **transaction coordinator** (one of the participating shards). In the *prepare* phase, each participant durably prepares its writes and votes; in the *commit* phase, once all vote yes, the coordinator instructs all to commit at a common **commitTimestamp**, ensuring a globally consistent snapshot across shards.

The costs are significant: prepared transactions hold resources and locks across shards, increasing contention and latency; a slow or unreachable participant stalls the whole transaction; and the global snapshot read concern requires cluster-wide time coordination (hybrid logical clocks / `$clusterTime`). The expert guidance: distributed transactions are correct but expensive, so design shard keys so that transactional units land on a **single shard** whenever possible (e.g., all of one customer's data co-located via `{customerId, ...}` shard key), turning what would be a 2PC into a cheap single-shard transaction. Avoid making cross-shard transactions a hot path.

### Q28. [Theory] How does MongoDB keep cluster time consistent, and what role do hybrid logical clocks play?

MongoDB uses **`$clusterTime`**, a logical clock based on **Hybrid Logical Clocks (HLC)**, gossiped on every message between nodes and clients. Each node maintains a clusterTime that is the max of its physical wall-clock time and the highest clusterTime it has observed, incremented per oplog write. This gives a total ordering of operations across the cluster without requiring perfectly synchronized physical clocks, while staying close to real time (unlike a pure Lamport clock).

`$clusterTime` is signed (HMAC) with a key shared by cluster members, so a malicious client can't fast-forward the cluster's logical time — a security-relevant design detail. Causal consistency, snapshot reads, and cross-shard snapshot transactions all rely on this: a session carries the clusterTime of its last operation (`operationTime`), and subsequent reads can wait until a target node's clusterTime catches up, implementing "read your writes" across nodes. This is the same family of ideas behind Google Spanner's TrueTime, but MongoDB uses HLC + gossip instead of GPS/atomic-clock-bounded uncertainty.

### Q29. [Practical] Walk me through diagnosing a production incident where p99 write latency suddenly spiked 10×.

I'd triage methodically. **First, observe** via `mongostat`, `mongotop`, Atlas/Ops Manager metrics, and `db.serverStatus()`. The usual suspects and how to confirm each:

1. **WiredTiger cache pressure / eviction**: check `wiredTiger.cache` — if "bytes currently in cache" is near the configured max and "pages evicted by application threads" is rising, the working set no longer fits in RAM and writes are stalling on eviction. Fix: add RAM, reduce indexes, or shard.
2. **Write conflicts / lock contention**: a hot document being updated by many threads causes `WriteConflict` retries — check `wiredTiger.concurrentTransactions` tickets exhausting (the 128 read/write ticket limit). Fix: spread writes, reduce contention, redesign the hot doc.
3. **Replication lag with `w:majority`**: if a secondary fell behind (slow disk, network), majority writes block waiting for acknowledgment — check `rs.printSecondaryReplicationInfo()`. Fix: repair the slow secondary; never run with only 2 data nodes where one lagging node blocks majority.
4. **Index build / balancer migration / checkpoint stalls**: a foreground index build, an active chunk migration, or a long checkpoint flush can spike latency. Check `currentOp()`.
5. **Missing index → COLLSCAN under load** introduced by a deploy: `explain` the new query.

The discipline is to correlate the spike's start time with a deploy, a balancer window, or a hardware event, form a hypothesis, and confirm with a specific metric before acting — not to randomly add indexes.

### Q30. [Coding] Implement an idempotent, exactly-once outbox processor using change streams and resume tokens.

**Problem:** Reliably propagate every order change to a downstream system exactly once, surviving consumer crashes. The pattern: persist the resume token atomically with the side-effect's idempotency record.

```java
import com.mongodb.client.*;
import com.mongodb.client.model.*;
import com.mongodb.client.model.changestream.*;
import org.bson.BsonDocument;
import org.bson.Document;

void runOutbox(MongoClient client) {
    MongoCollection<Document> orders = client.getDatabase("app").getCollection("orders");
    MongoCollection<Document> state  = client.getDatabase("app").getCollection("stream_state");

    // Resume from the last persisted token, if any.
    Document saved = state.find(Filters.eq("_id", "outbox")).first();
    ChangeStreamIterable<Document> stream = orders.watch();
    if (saved != null && saved.get("token") != null)
        stream = stream.resumeAfter(saved.get("token", Document.class).toBsonDocument());

    for (ChangeStreamDocument<Document> ev : stream) {
        BsonDocument token = ev.getResumeToken();
        String eventId = ev.getDocumentKey().toJson() + ":" + token.toJson();

        // Idempotent publish keyed by eventId; the downstream/dedupe store ignores repeats.
        boolean firstTime = publishIfNew(eventId, ev.getFullDocument());

        // Persist the token AFTER the side-effect. On crash we may reprocess the
        // last event, but publishIfNew dedupes it -> effectively-once.
        state.updateOne(Filters.eq("_id", "outbox"),
            Updates.set("token", token), new UpdateOptions().upsert(true));
    }
}
```

**Why this is effectively-once, not exactly-once:** true exactly-once is impossible across a network boundary, so we make the side-effect **idempotent** (dedupe by `eventId`) and persist the resume token *after* publishing. If we crash between publish and token-save, we reprocess one event on restart but the dedupe makes it a no-op. **Alternative for stronger guarantees:** wrap the downstream write and token update in a single MongoDB transaction when the downstream is also MongoDB. **Edge cases:** the oplog must retain enough history to resume (a long consumer outage can invalidate the token → fall back to a full resync from a checkpoint timestamp via `startAtOperationTime`).

### Q31. [Theory] When is MongoDB the wrong choice, and how do you argue against it as a staff engineer?

The intellectually honest expert recognizes MongoDB's limits. It is a poor fit when:

- **The domain is deeply relational** with many equally-important entities and many-to-many joins (an ERP, a normalized financial ledger): you'll fight `$lookup`, lose referential integrity, and reinvent constraints in application code.
- **Strong cross-entity transactional consistency is the hot path**: frequent multi-document/cross-shard transactions negate MongoDB's performance advantages; a well-tuned PostgreSQL is simpler and faster.
- **Complex ad-hoc analytical SQL and mature BI tooling** are required: a columnar warehouse (Snowflake, BigQuery, ClickHouse) crushes MongoDB for OLAP.
- **The data has no aggregate boundary** — everything joins to everything — meaning there's no natural document to embed around.

As a staff engineer, the argument isn't "NoSQL bad" — it's *access-pattern-driven*: I'd ask for the dominant read/write patterns, the consistency requirements, and the growth projection, then show that the chosen store's data model matches them. I'd also flag the operational cost (sharding expertise, working-set RAM sizing, backup/restore at scale) and resist using MongoDB as a default just because it's familiar. The right database is the one whose data model and consistency guarantees match the workload — sometimes that's Postgres + JSONB, which gives document flexibility *and* relational power.

### Q32. [Behavioral] Tell me about a time you had to migrate a large production MongoDB deployment or recover from a data-modeling mistake.

Use the **STAR** structure and emphasize judgment under risk. A strong answer: "We had a `users` collection sharded on `ObjectId` that developed a hot shard — one shard ran at 90% CPU while two idled, and write p99 had crept to 400 ms. **(Situation)** I needed to re-shard to a hashed key without downtime on a 4 TB collection serving 50K writes/sec. **(Task)** I first proved the diagnosis with `mongostat` and chunk-distribution stats so the team agreed on the root cause, then chose `reshardCollection` to a hashed `userId` after load-testing it in staging to measure the temporary 2× storage and I/O impact. **(Action)** I scheduled it in a low-traffic window, pre-provisioned storage, monitored the resharding progress and replication lag throughout, and had a rollback plan (abort resharding leaves the original intact). **(Result)** Writes redistributed evenly, p99 dropped to 35 ms, and I wrote a shard-key design checklist so the team would evaluate cardinality/monotonicity/query-alignment up front." The key signals interviewers want: data-driven diagnosis, risk management (staging, rollback, monitoring), and turning the incident into a durable process improvement.

### Q33. [Theory] What are the security considerations when running MongoDB in production?

MongoDB historically shipped with authentication *disabled* by default, which caused thousands of public data breaches when instances were exposed to the internet — a cautionary tale every senior engineer should cite. A hardened production posture requires:

- **Authentication & authorization**: enable auth (SCRAM, x.509, LDAP, or Kerberos), enforce **Role-Based Access Control** with least-privilege roles (don't hand out `root`), and use separate credentials per service.
- **Network isolation**: bind to private interfaces only, put MongoDB in a private subnet/VPC, use firewall rules / security groups, and **never** expose port 27017 publicly. Use TLS for all client and intra-cluster traffic.
- **Encryption**: TLS in transit; **encryption-at-rest** (WiredTiger native encryption or disk-level); and for the strongest guarantee, **Client-Side Field-Level Encryption (CSFLE)** or **Queryable Encryption** (4.2+/6.0+) so sensitive fields (PII, PHI) are encrypted by the driver and the server never sees plaintext — protecting against a compromised DBA or breached server.
- **Auditing**: enable the audit log for compliance (HIPAA, PCI, SOC 2) and monitor for anomalous access.
- **Injection**: MongoDB is not immune to injection — never build queries by string-concatenating untrusted input into `$where` or JSON; use parameterized driver methods and reject operator keys (`$`) in user-supplied field names.

The cheapest, highest-impact control is simply: auth on + bound to private network + TLS — that alone prevents the overwhelming majority of historical MongoDB breaches.

### Q34. [Practical] How would you design backups, disaster recovery, and a point-in-time-restore strategy for a sharded cluster?

A sharded cluster makes backups harder because a consistent snapshot must span all shards *and* the config servers at the same logical time. Options, with trade-offs:

1. **Filesystem/volume snapshots** (EBS, LVM): fast, but for a sharded cluster you must coordinate snapshots across shards at a consistent point — stop the balancer first and snapshot all shards + config servers together, otherwise chunk migrations make the snapshot inconsistent.
2. **`mongodump`/`mongorestore`**: simple and portable for smaller datasets, but slow and not point-in-time consistent across shards at scale; fine for single replica sets, painful for large clusters.
3. **Continuous backup with PITR** (Atlas Backup, Ops Manager, Percona): captures the oplog continuously so you can restore to any second within the retention window — essential for recovering from a logical error (a bad `updateMany` that wiped a field) where you need the state *just before* the mistake.

My production design: managed continuous backup (Atlas/Ops Manager) with oplog-based PITR, cross-region copies for DR, **regularly tested restores** (an untested backup is not a backup), documented RPO/RTO targets, and balancer-aware snapshot coordination if rolling my own. I'd also pair this with `w:majority` writes so the durable data set is well-defined, and rehearse the failover/restore runbook so the team isn't learning it during the incident.

### Q35. [Theory] Compare MongoDB's tunable consistency against the CAP/PACELC framework and explain where it sits.

Under **CAP**, MongoDB is fundamentally a **CP** system: during a network partition, the minority side cannot elect a primary and rejects writes (sacrificing availability) to preserve consistency and avoid split-brain. The majority side with a primary continues. However, MongoDB is *tunable*: with `readPreference:secondary` and `w:1` you trade toward availability and lower latency at the cost of consistency, so it spans a spectrum rather than sitting at one fixed point.

**PACELC** captures this better: **P**artition → **C** (it favors consistency under partition), **E**lse (in normal operation) → **L/C** is tunable — you choose latency vs. consistency per operation via read/write concern and read preference. `w:majority` + `readConcern:majority` + `readPreference:primary` is the consistency-favoring corner (higher latency); `w:1` + `readConcern:local` + `readPreference:nearest` is the latency-favoring corner (weaker consistency). The expert insight: MongoDB doesn't force one global trade-off; it pushes the CAP/PACELC decision down to the *operation* level, letting a single deployment serve a strongly-consistent ledger read and a latency-optimized analytics read with different guarantees. This per-operation tunability is the design's quiet superpower and the source of most consistency bugs when developers don't understand the knobs.

---

## 🧩 Extended Questions — Set 1: Deeper Theory & Practical Operations

### 🟢 Basic — extended

#### Q36. [Theory] What is the difference between a database, a collection, and a document, and how do namespaces work?

MongoDB's storage hierarchy has three levels: a **database** is a top-level container (a logical grouping with its own files, users, and storage); a **collection** is a grouping of documents within a database (the analog of a table); and a **document** is a single BSON record (the analog of a row). Unlike relational systems, collections are created **lazily** — the first insert (or first index creation) materializes both the database and the collection, so there's no `CREATE TABLE` step.

A **namespace** is the fully-qualified `database.collection` string (e.g., `app.users`), and it is how MongoDB internally identifies a collection. Historically the combined namespace length was capped (~120 bytes in older versions); modern WiredTiger relaxed this, but very long names are still a smell. Each database maps to its own set of WiredTiger files on disk, which is why dropping a database is fast (it removes files) and why you isolate tenants or environments at the database level when you want clean storage boundaries.

```
mongod (server)
└── database: "app"
    ├── collection: "users"      namespace = "app.users"
    │   ├── document {_id: 1, ...}
    │   └── document {_id: 2, ...}
    └── collection: "orders"     namespace = "app.orders"
```

The practical implication: because creation is lazy and schema is dynamic, you can accidentally create a collection by typo'ing its name in an insert. In production, enabling `--notablescan` is risky, but using strict naming conventions and schema validators guards against silent collection sprawl. A common interview follow-up is "how many collections is too many?" — thousands of collections inflate metadata and slow startup/recovery because each carries its own index trees and WiredTiger handles; prefer one collection with a discriminator field over thousands of near-identical collections.

#### Q37. [Practical] How do you connect to MongoDB using a connection string, and what do the key URI options mean?

The connection string (SRV or standard URI) is how every driver locates and configures its connection to a deployment. The modern `mongodb+srv://` scheme uses DNS SRV records so you specify only a hostname and the driver discovers the replica-set members automatically — this is what Atlas hands you. The classic `mongodb://` scheme lists hosts explicitly. Getting the options right matters more than people think, because most production connection problems are misconfigured URIs, not server bugs.

```
mongodb+srv://user:pass@cluster0.abcd.mongodb.net/app?retryWrites=true&w=majority&maxPoolSize=50&readPreference=secondaryPreferred
            │    │         │                          │    │              │            │             │
          scheme cred     SRV host                 default  retryable     write       pool size     read
                                                    db       writes        concern                    preference
```

```java
ConnectionString cs = new ConnectionString(
    "mongodb+srv://user:pass@cluster0.abcd.mongodb.net/?" +
    "retryWrites=true&w=majority&maxPoolSize=50&serverSelectionTimeoutMS=5000");
MongoClient client = MongoClients.create(MongoClientSettings.builder()
    .applyConnectionString(cs)
    .applyToConnectionPoolSettings(b -> b.maxSize(50).minSize(5))
    .build());
```

The high-impact options: **`retryWrites=true`** (default in modern drivers) makes single-document writes automatically retry once on a transient network/failover error so a primary step-down doesn't surface as an error to your code; **`w` and `readPreference`** set default concerns cluster-wide (overridable per collection); **`maxPoolSize`** caps connections per `mongos`/host (the default 100 is often too high — each connection is a thread/socket on the server, so 50 across many app instances can exhaust server limits); and **`serverSelectionTimeoutMS`** controls how long the driver waits to find a suitable server before throwing. The most common production mistake is embedding credentials in the URI in source control instead of injecting them from a secret manager, and the second is leaving `maxPoolSize` at default across hundreds of app pods, overwhelming the server's connection budget.

#### Q38. [Theory] What is the difference between `find()`, `findOne()`, and using a cursor, and why does cursor batching matter?

`findOne()` returns a single document (or null) and is a convenience wrapper that applies a `limit(1)` internally. `find()` returns a **cursor** — a server-side pointer to a result set that streams documents to the client in **batches** rather than all at once. This distinction is fundamental to MongoDB's memory model: a query matching ten million documents does not load ten million documents into the client or server memory; it returns the first batch (default ~101 documents or up to ~16 MB on the first batch, then ~16 MB subsequent batches) and fetches more via `getMore` commands as you iterate.

Cursor batching is what lets you iterate huge result sets with bounded memory, but it has two failure modes interviewers probe. First, **cursor timeouts**: an idle cursor is reaped by the server after 10 minutes (`cursorTimeoutMillis`), so a slow consumer that pauses between batches can get `CursorNotFound`; use `noCursorTimeout()` deliberately (and close it) or process faster. Second, **cursors are not snapshots by default** — documents inserted/modified during a long iteration may or may not appear, and a document can even be returned twice if it moves due to a size change on older engines; for a consistent point-in-time view you need a transaction with snapshot read concern.

```java
// Cursor streams in batches; tune batch size for throughput vs round-trips.
try (MongoCursor<Document> cur = users.find(gt("age", 18))
        .batchSize(500)          // 500 docs per network round-trip
        .iterator()) {
    while (cur.hasNext()) {
        process(cur.next());      // getMore fetches the next batch transparently
    }
}  // try-with-resources closes the server-side cursor promptly
```

The practical tuning lever is `batchSize`: too small means many `getMore` round-trips (latency-bound); too large means big memory spikes and longer time-to-first-result. For ETL/streaming jobs I set an explicit `batchSize`, always close cursors in a try-with-resources, and never call `.into(new ArrayList<>())` on an unbounded query — that defeats the whole streaming design and can OOM the app.

#### Q39. [Practical] How do you inspect what's happening on a running MongoDB instance using shell commands?

Day-to-day operations rely on a handful of diagnostic commands that every MongoDB engineer should know cold. The first stop is **`db.serverStatus()`** for a full snapshot (connections, opcounters, WiredTiger cache, replication), but it's verbose, so you usually project the section you need. For live throughput, **`mongostat`** (inserts/queries/updates per second, dirty cache %, faults) and **`mongotop`** (time spent reading/writing per collection) are the `top`/`vmstat` of MongoDB.

```bash
# One-second sampling of cluster-wide ops, cache pressure, and queue depth
mongostat --host rs0/host1:27017 1

# Per-collection read/write time, sampled every 2 seconds
mongotop 2

# Currently executing operations (find long-runners and lock waiters)
mongosh --eval 'db.currentOp({ "secs_running": { $gte: 5 }, "active": true })'

# Kill a runaway operation by its opid
mongosh --eval 'db.killOp(123456)'
```

Inside `mongosh`, the workhorses are **`db.currentOp()`** (what's running right now — invaluable for catching a missing-index query saturating CPU), **`db.killOp(opid)`** (terminate a runaway op), **`rs.status()`** and **`rs.printSecondaryReplicationInfo()`** (replica-set health and lag), and **`db.collection.stats()`** (size, index sizes, document count). The discipline is to look before acting: when latency spikes, `currentOp` shows you the offending query and how long it's been running, `mongostat` shows whether the cache is thrashing, and `rs.status()` shows whether a secondary fell behind — three commands that resolve the majority of "the database is slow" pages without guessing.

#### Q40. [Theory] What are capped collections and when would you use one?

A **capped collection** is a fixed-size collection that behaves like a circular buffer: once it reaches its size (or optional document-count) limit, the oldest documents are automatically overwritten by new inserts, in insertion order. They preserve insertion order on disk, guarantee that a natural-order query (`find().sort({$natural:1})`) returns documents in the order they were inserted without an index, and support **tailable cursors** that block waiting for new documents — the mechanism that the oplog itself is built on.

The trade-offs are strict: you cannot delete individual documents, you cannot grow a document beyond its original size on an update (it would shift other documents), and you cannot shard a capped collection. Those constraints are exactly what make them fast and predictable — there's no fragmentation and no need to track free space.

```javascript
// Fixed 100 MB ring buffer, at most 1,000,000 docs, for recent app logs
db.createCollection("recent_logs", {
  capped: true,
  size: 104857600,   // bytes, required
  max: 1000000       // optional doc-count cap
});
```

Use them for high-write, auto-expiring scenarios where you only care about the most recent N records: rolling log buffers, a lightweight last-N event cache, or a poor man's pub/sub via tailable cursors. In modern MongoDB, a **TTL index** is usually the better choice for time-based expiry (it expires by age, allows deletes and updates, and can be sharded), and **change streams** supersede tailable cursors for event consumption — so capped collections are increasingly a niche/legacy tool. The interview value is understanding that the oplog is a capped collection, which explains why a too-small oplog causes secondaries to fall off and require full resyncs.

### 🟡 Intermediate — extended

#### Q41. [Theory] What is a covered query and what conditions must hold for one?

A **covered query** is a query that MongoDB answers entirely from an index without ever fetching the underlying documents — `explain` shows `totalDocsExamined: 0` and the winning plan has no `FETCH` stage above the `IXSCAN`. Because the index already contains the values being returned, the engine reads only the (smaller, often fully-cached) index B-tree, making covered queries one of the highest-leverage read optimizations available.

Three conditions must all hold. (1) **Every field in the query filter and the projection must be part of a single index** (the index "covers" them). (2) **The projection must exclude `_id`** unless `_id` is itself in the index, because `_id` is returned by default and isn't in an arbitrary index. (3) **None of the indexed fields can be arrays** (a multikey index cannot cover, because the index stores individual elements, not the array). A subtle fourth point: the query must not return the whole document — you must project only the indexed fields.

```javascript
// Index that can cover a status+email lookup returning only email
db.users.createIndex({ status: 1, email: 1 });

// COVERED: filter and projection fields are both in the index; _id excluded
db.users.find(
  { status: "ACTIVE" },
  { _id: 0, email: 1 }
).explain("executionStats");
// -> winningPlan has IXSCAN with no FETCH; totalDocsExamined === 0
```

```java
users.find(eq("status", "ACTIVE"))
     .projection(fields(excludeId(), include("email")))   // _id:0, email:1
     .into(new ArrayList<>());
```

The practical use is read-heavy lookup tables and existence/count checks: a "does this user exist and what's their tier" endpoint can run thousands of times per second entirely from RAM-resident index pages. The design move is to deliberately add the projected field to the index (turning a `{status:1}` index into `{status:1, email:1}`) when you know the hot query only needs that field — trading a slightly larger index for zero document fetches.

#### Q42. [Practical] How do you create an index without blocking writes in production, and what changed in modern versions?

Index creation is one of the most dangerous routine operations because it can lock the database or saturate I/O on a live system. Historically MongoDB had a `background: true` option that built indexes more cooperatively but still had a foreground variant that took a collection-level (then database-level) lock for the entire build — running that on a billion-document collection during peak traffic would effectively take the database offline. Since **MongoDB 4.2**, all index builds use a single optimized **hybrid** build that takes locks only briefly at the start and end and yields throughout, so the old `background` flag is deprecated/ignored — but the build still consumes significant CPU, RAM (up to `maxIndexBuildMemoryUsageMegabytes`, default 200 MB), and disk I/O.

```javascript
// Modern build: createIndex is hybrid by default; still schedule off-peak.
db.orders.createIndex(
  { customerId: 1, placedAt: -1 },
  { name: "cust_placedAt" }
);

// On replica sets, builds replicate; in 4.4+ they are coordinated so a
// secondary won't serve reads against a half-built index.
```

The production playbook: (1) build during a low-traffic window even with the hybrid builder, because the I/O and cache churn can still degrade p99; (2) on a replica set, prefer a **rolling index build** for the largest collections — take one secondary out of the set, build the index on it standalone, rejoin, repeat per member, then step down the primary — so no single member is doing the heavy build while serving production traffic; (3) always name your indexes explicitly so you can drop them deterministically; (4) verify the build finished and is being used with `db.collection.getIndexes()` and an `explain`. The classic incident is someone running a foreground build on an old version (or a huge unique-index build that has to scan everything) and paging the on-call when writes stall — knowing the rolling-build technique is the senior-level answer.

#### Q43. [Theory] What is the difference between `updateOne`, `replaceOne`, and `findOneAndUpdate`, and when do you use each?

These three write operations differ in scope and in what they return, and choosing wrong causes subtle bugs. **`updateOne`** applies update *operators* (`$set`, `$inc`, `$push`, etc.) to a single matched document, modifying only the named fields and leaving the rest intact; it returns a result with match/modify counts but **not** the document. **`replaceOne`** swaps the entire matched document for a new one (except `_id`), so any field not present in the replacement is lost — it takes a plain document, not operators, and is the right tool when you have a fresh full object from your application layer. **`findOneAndUpdate`** does an `updateOne` *and* atomically returns the document, either the pre-image or post-image, in one round-trip.

```java
// updateOne: surgical field changes, no document returned
users.updateOne(eq("_id", "u1"), combine(set("tier", "GOLD"), inc("points", 50)));

// replaceOne: whole-document swap (omitted fields disappear!)
users.replaceOne(eq("_id", "u1"), new Document("_id","u1").append("name","Ana"));

// findOneAndUpdate: atomic read-modify-return; great for counters/queues
Document next = jobs.findOneAndUpdate(
    eq("status", "QUEUED"),
    combine(set("status", "RUNNING"), set("worker", workerId)),
    new FindOneAndUpdateOptions()
        .sort(Sorts.ascending("priority"))
        .returnDocument(ReturnDocument.AFTER));   // get the claimed job back
```

| Operation | Input | Returns doc? | Scope | Typical use |
|-----------|-------|--------------|-------|-------------|
| `updateOne` | operators | no | named fields | partial update, increment |
| `replaceOne` | full document | no | whole document | save full object from app |
| `findOneAndUpdate` | operators | yes (pre/post) | named fields | atomic claim, counter-then-read |

The interview-favorite use of `findOneAndUpdate` is implementing a **work queue / leasing pattern**: atomically find the highest-priority `QUEUED` job, flip it to `RUNNING`, and get it back — all in one atomic operation so two workers never grab the same job. The common bug is reaching for `replaceOne` thinking it's "just an update" and silently dropping fields the caller didn't include, or doing a separate `find` then `updateOne` (a race) instead of the atomic `findOneAndUpdate`.

#### Q44. [Coding] Implement optimistic concurrency control (no transactions) for a document update in Java.

**Problem:** Two requests read the same document, both modify it, and the second write must not blindly clobber the first ("lost update"). Multi-document transactions are overkill for a single document; the idiomatic MongoDB solution is **optimistic concurrency control** using a version field and a guarded update — the same pattern Spring Data implements with `@Version`.

```java
import com.mongodb.client.model.*;
import org.bson.Document;
import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Updates.*;

boolean updateProfile(MongoCollection<Document> users, String id, String newBio) {
    for (int attempt = 0; attempt < 3; attempt++) {
        Document current = users.find(eq("_id", id)).first();
        if (current == null) return false;
        long version = current.getLong("version");

        // Guard the update on the SAME version we read. If another writer
        // bumped it in between, matchedCount == 0 and we retry with fresh data.
        UpdateResult r = users.updateOne(
            and(eq("_id", id), eq("version", version)),
            combine(set("bio", newBio), inc("version", 1)));

        if (r.getMatchedCount() == 1) return true;   // we won, no conflict
        // else: someone else won; loop re-reads and retries
    }
    throw new IllegalStateException("too much contention on " + id);
}
```

**Why this works without locks:** the `version` field in the filter makes the update **conditional** — it only matches if no one else has written since we read. A concurrent writer increments `version`, so our filter no longer matches and `matchedCount` is 0, which we detect and retry. This is the read-compare-and-swap pattern expressed as a guarded `updateOne`, and because the update is atomic at the document level, there is no window for a lost update.

**Trade-offs and edge cases:** optimistic locking shines under **low-to-moderate contention** (the common case) because the happy path is a single round-trip with no locks held; under **high contention** on one hot document it degrades into a retry storm, at which point you redesign (shard the counter, use `$inc` directly if the update is commutative, or move the hot field out). Always cap retries to avoid an infinite loop, and prefer `$inc`-style commutative updates when possible because they don't need the read-modify-write cycle at all — `updateOne(eq("_id",id), inc("likes",1))` is inherently safe and never needs a version guard.

#### Q45. [Theory] How does the query planner choose a plan, and what is the plan cache?

When a query could use more than one index, MongoDB doesn't statically pick a winner — it runs an empirical **plan competition**. On the first execution of a new query *shape* (the structure of the filter/sort/projection, ignoring literal values), the planner generates all viable candidate plans and runs them in parallel for a trial period, racing them to see which returns results or reaches a threshold of work fastest. The winner is chosen by which plan does the least work (e.g., examines the fewest documents/keys to produce the first batch), and that winning plan is stored in the **plan cache** keyed by the query shape.

Subsequent queries of the same shape reuse the cached plan without re-competing, which is what makes repeated queries cheap. The cache is invalidated when indexes change, when the collection is dropped, on certain catalog events, or when a plan's performance degrades enough that the planner re-evaluates (the "replanning" mechanism that fires when a cached plan's actual work exceeds its expected work by a large factor). You can inspect and manage it with `db.collection.getPlanCache()`.

```javascript
// See cached plans and the winning plan per shape
db.orders.getPlanCache().list();

// Force a re-competition by clearing the cache for a collection
db.orders.getPlanCache().clear();

// Pin a plan deterministically with an index filter (overrides competition)
db.runCommand({
  planCacheSetFilter: "orders",
  query: { status: "ACTIVE", region: "EU" },
  indexes: [ { status: 1, region: 1, createdAt: -1 } ]
});
```

The practical relevance is twofold. First, **parameter sniffing-style surprises**: because the plan is chosen on a sample of values for a shape, an atypical first execution can cache a plan that's bad for the typical case — clearing the cache or using `planCacheSetFilter`/index filters pins the right plan. Second, when you add an index and a query *doesn't* start using it, the stale plan cache is a frequent culprit; clearing it forces a fresh competition. Knowing the planner is empirical (not purely cost-based like most SQL optimizers) explains a lot of otherwise-mysterious plan behavior.

#### Q46. [Practical] You're seeing "too many open connections" errors. How do you diagnose and fix connection pool problems?

This error means the server hit its connection limit (`net.maxIncomingConnections`, or the OS file-descriptor `ulimit`, whichever is lower), and it's almost always a client-side pooling misconfiguration multiplied across instances rather than genuine load. The arithmetic that bites people: each app instance opens up to `maxPoolSize` connections **per server it talks to**. With `maxPoolSize=100` (the old default), 50 app pods, and a 3-node replica set, the driver can open up to 100 × 50 = 5,000 connections to the primary alone — easily exhausting the server.

```javascript
// Confirm the symptom server-side
db.serverStatus().connections
// { current: 19873, available: 127, totalCreated: 88123, active: 412 }
// 'current' near the cap with low 'active' => pools too large / leaking
```

```java
// Fix: size the pool deliberately and bound idle time
MongoClientSettings.builder()
  .applyToConnectionPoolSettings(b -> b
      .maxSize(20)                 // per host; (pods * maxSize) must fit server cap
      .minSize(2)
      .maxConnectionIdleTime(60_000, TimeUnit.MILLISECONDS)
      .maxWaitTime(5_000, TimeUnit.MILLISECONDS))   // fail fast instead of piling up
  .build();
```

The diagnosis sequence: (1) `db.serverStatus().connections` — if `current` is near the cap but `active` is tiny, connections are pooled-but-idle (pool too large) or leaked; (2) check whether the app creates **one `MongoClient` per request** instead of one per process (the cardinal sin — each client is a full pool), or fails to close clients/cursors; (3) compute `instances × maxPoolSize × hostsTalkedTo` and verify it's well under the server cap. The fixes: a single shared `MongoClient`, a `maxPoolSize` sized so the fleet-wide total fits the server budget (often 10–50, not 100), a sane `maxConnectionIdleTime` so idle connections are reaped, and a `maxWaitTime`/`waitQueueTimeoutMS` so a connection-starved request fails fast rather than hanging. On the server side, raise the OS file-descriptor `ulimit` (the default 1024 is far too low) and `net.maxIncomingConnections` only after confirming the client side is sane — raising the cap to mask a leak just delays the outage.

#### Q47. [Theory] What is the `$facet` stage and how does it enable multi-faceted aggregation in a single query?

`$facet` runs **multiple independent aggregation sub-pipelines** over the *same input documents* in a single stage, returning a document whose fields each hold the output of one sub-pipeline. Its purpose is to compute several different views of one dataset in one server round-trip — the canonical use being an e-commerce search results page that needs, simultaneously, the paginated results, the total count, a price-range histogram, and a count-by-category sidebar, all from the same filtered set of products.

```java
import com.mongodb.client.model.*;
import java.util.List;
import org.bson.Document;

products.aggregate(List.of(
  Aggregates.match(Filters.eq("category", "laptops")),   // run ONCE, shared by all facets
  Aggregates.facet(
    new Facet("page",  Aggregates.sort(Sorts.descending("rating")),
                       Aggregates.skip(0), Aggregates.limit(20)),
    new Facet("total", Aggregates.count("count")),
    new Facet("byBrand", Aggregates.sortByCount("$brand")),
    new Facet("priceBuckets",
      Aggregates.bucket("$price", List.of(0, 500, 1000, 2000, 5000),
        new BucketOptions().defaultBucket("5000+")
                           .output(Accumulators.sum("count", 1))))
  )
)).first();
```

The key efficiency point is that the stages **before** `$facet` (the `$match` here) execute once and feed all sub-pipelines, so you filter the dataset a single time rather than running four separate queries each repeating the filter. The trade-offs to know: `$facet` is a **blocking** stage (it materializes input), each sub-pipeline cannot itself use a fresh index scan (the index was used by the upstream `$match`, but within a facet you're operating on the already-selected stream), and the whole stage is subject to the 100 MB memory limit unless `allowDiskUse(true)`. So `$facet` is ideal when the *input set is already narrowed* and you need several aggregations over it; it's the wrong tool if each "facet" actually needs to scan the whole collection independently with its own index — that's better as separate parallel queries.

### 🟠 Advanced — extended

#### Q48. [Theory] Explain the oplog in depth: its format, idempotency, sizing, and the consequences of it being too small.

The **oplog** (`local.oplog.rs`) is a capped collection on every replica-set member that records a stream of **idempotent** operations describing every data change on the primary. Secondaries tail it and re-apply entries to converge. Idempotency is the crucial property: the oplog never stores "increment x by 1" (which would be wrong if applied twice during a resync); instead the primary translates the operation into a deterministic, replay-safe form — an `$inc` becomes a `$set` to the resulting value, an insert is recorded with its full document, and a multi-document update is expanded into one oplog entry per affected document. This is why secondaries can safely re-apply overlapping ranges after a network hiccup without double-counting.

```javascript
// A simplified oplog entry: "op":"u" (update), idempotent $set form
{ "ts": Timestamp(1718500000, 3), "t": NumberLong(7), "op": "u",
  "ns": "app.accounts", "o2": { "_id": "acct1" },
  "o": { "$v": 2, "diff": { "u": { "balance": 950 } } } }   // absolute value, not delta
```

Oplog **sizing** is one of the most consequential operational settings. The oplog must retain enough history to cover the longest expected secondary downtime or replication lag — its window is `(oplog size) / (write rate)`. If a secondary is down or lagging longer than the oplog window, the entries it still needs get overwritten (the oplog is capped/circular), the member goes **`RECOVERING`** and `STALE`, and it can no longer catch up incrementally — it requires a full **initial sync** (copying the entire dataset), which is hours of I/O and risk on a large deployment. Change streams and the resume-token mechanism also depend on oplog retention: a consumer offline longer than the window gets an unresumable-token error.

The senior practices: size the oplog for your *peak* write burst times your worst-case maintenance window (default is 5% of free disk, capped at 50 GB, often too small for write-heavy clusters), monitor the **oplog window** in hours (Atlas surfaces it; otherwise compute from `db.getReplicationInfo()`), and treat a shrinking window as a leading indicator of either a write surge or a lagging secondary. In MongoDB 4.4+ you can also set a **minimum oplog retention period** in hours so the oplog won't truncate recent history even if it's under the size cap — directly protecting PITR and change-stream resumability.

#### Q49. [Practical] How do you safely add and remove members from a replica set without downtime?

Reconfiguring a replica set is routine but must respect the **majority-voting invariant**: at every step a majority of voting members must remain available, or the set loses its primary and writes stall. The operations are `rs.add()`, `rs.remove()`, and `rs.reconfig()`, but the *order* and *vote arithmetic* are what keep it safe. A common outage is changing votes such that no majority can be formed, or adding a member that triggers a heavy initial sync that saturates the primary's network/disk during peak hours.

```javascript
// Add a secondary (hidden + priority 0 first, so it can't be elected mid-sync)
rs.add({ host: "node4:27017", priority: 0, hidden: true, votes: 0 });
// ... wait for it to finish initial sync and reach SECONDARY state ...

// Promote it to a normal voting member once caught up
cfg = rs.conf();
cfg.members[3].priority = 1; cfg.members[3].hidden = false; cfg.members[3].votes = 1;
rs.reconfig(cfg);   // in 4.4+ prefer single voting-member changes per reconfig

// Remove a member cleanly
rs.remove("node2:27017");
```

The safe-change rules: (1) **change voting membership one member at a time** — MongoDB 4.4+ even enforces "safe reconfig" that won't let a single reconfig change the majority in a way that risks committed writes; (2) when **adding** a data-bearing node, bring it in as `priority:0, hidden:true, votes:0` so it performs its initial sync without being electable and without affecting the majority count, then flip it to a full voter once it's caught up; (3) when **removing**, ensure the remaining set still has an odd number of voters and a clear majority; (4) schedule initial syncs for off-peak, since they read the entire dataset from a sync source. For a planned primary maintenance, use `rs.stepDown(60)` to trigger a graceful election to another member before taking the old primary offline — combined with `retryWrites=true` on clients, a graceful step-down is nearly invisible to the application.

#### Q50. [Coding] Write an aggregation using `$setWindowFields` to compute a 7-day moving average per device.

**Problem:** Given time-series readings, compute a trailing 7-day moving average of `value` per `deviceId`, ordered by time — the kind of windowed analytics that used to require exporting to a separate analytics engine. `$setWindowFields` (MongoDB 5.0+) brings SQL-style window functions natively into the pipeline.

```java
import com.mongodb.client.model.*;
import java.util.List;
import org.bson.Document;

readings.aggregate(List.of(
  Aggregates.setWindowFields(
      "$deviceId",                                   // partitionBy (like SQL PARTITION BY)
      Sorts.ascending("ts"),                         // sortBy within each partition
      WindowOutputFields.avg("movingAvg", "$value",  // the windowed accumulator
          Windows.timeRange(-7, 0, MongoTimeUnit.DAY)),   // [now-7d, now] trailing window
      WindowOutputFields.sum("rolling30", "$value",
          Windows.documents(-29, 0))                 // alternatively: last 30 documents
  ),
  Aggregates.project(new Document("_id", 0)
      .append("deviceId", 1).append("ts", 1)
      .append("value", 1).append("movingAvg", 1))
)).into(new java.util.ArrayList<>());
```

**Why this matters:** before `$setWindowFields`, a moving average required either a costly self-`$lookup` (join each document to its neighbors) or pulling data into the application and computing windows in code — both slow and memory-hungry. The native stage partitions the stream, sorts within each partition, and slides a window (defined either by a **document count** with `Windows.documents` or by a **value/time range** with `Windows.range`/`timeRange`) computing accumulators incrementally.

**Trade-offs and correctness notes:** `$setWindowFields` is a **blocking** stage that sorts within partitions, so it benefits enormously from an index on `{deviceId:1, ts:1}` matching the partition+sort, and on large data you'll want `allowDiskUse(true)`. The **time-range** window (`timeRange(-7, 0, DAY)`) is semantically different from a **document-count** window (`documents(-6, 0)`): the former includes all readings within 7 days even if there are irregular gaps (correct for sensors that report sporadically), while the latter takes a fixed number of rows regardless of time spacing — choosing wrong gives subtly incorrect averages. Pairing this with **time-series collections** is the modern pattern for IoT/observability analytics directly in MongoDB.

#### Q51. [Theory] How does MongoDB handle the read/write concurrency tickets, and what does ticket exhaustion look like?

WiredTiger limits how many operations can be *concurrently executing inside the storage engine* using a pool of **tickets** — historically 128 read tickets and 128 write tickets (`storage.wiredTiger.concurrentTransactions`, surfaced as `wiredTiger.concurrentTransactions` in `serverStatus`). An operation must acquire a ticket to do storage-engine work; if all tickets are in use, new operations **queue** waiting for one. This is a deliberate admission-control / back-pressure mechanism: it prevents an unbounded number of operations from overwhelming the engine and thrashing the cache, trading some latency for stability under load.

```javascript
db.serverStatus().wiredTiger.concurrentTransactions
// {
//   write: { out: 128, available: 0, totalTickets: 128 },   // <- exhausted!
//   read:  { out: 96,  available: 32, totalTickets: 128 }
// }
```

Ticket **exhaustion** is a classic production signature: latency spikes across *all* operations (even fast ones), `available` tickets hit 0, and queue depth climbs, yet CPU may not be pegged because operations are *waiting*, not working. The root cause is almost always operations holding tickets too long — slow disk I/O (cache misses forcing disk reads while holding a ticket), a flood of expensive uncached queries, or write conflicts causing long retries. The misdiagnosis trap is to raise the ticket limit, which usually makes things *worse* by removing the back-pressure and letting more operations pile into an already-saturated engine, deepening cache thrash.

The correct response is to fix what makes operations slow: ensure the working set fits the WiredTiger cache (cache misses are the usual culprit), add the missing index causing collection scans to hold read tickets forever, reduce write contention on hot documents, or scale out via sharding to spread the load. MongoDB 7.0+ introduced a **dynamic/adaptive ticket algorithm** that auto-tunes the concurrency limit based on observed throughput, reducing the need to touch this manually — but understanding tickets as admission control (analogous to a connection pool or a thread pool with a bounded queue) is what separates a real diagnosis from cargo-cult tuning.

#### Q52. [Practical] Your aggregation is exceeding the 100 MB memory limit. Walk through your options.

The 100 MB per-stage memory cap protects the server from a single aggregation consuming unbounded RAM; when a blocking stage (`$group`, `$sort`, `$setWindowFields`, `$bucket`, `$facet`) exceeds it, the query fails with `QueryExceededMemoryLimitNoDiskUseAllowed`. The lazy fix is `allowDiskUse(true)`, which lets those stages spill to temporary disk files — but treating that as the default masks a design problem, because spilling is dramatically slower than in-memory and turns a fast query into an I/O-bound one. The senior approach is to reduce the data each blocking stage must hold, and only then enable disk use as a safety valve.

```java
// First, reduce the working set BEFORE the blocking stages:
orders.aggregate(List.of(
    Aggregates.match(gte("placedAt", cutoff)),        // 1. filter early (use an index)
    Aggregates.project(fields(include("category","amount"))), // 2. drop unused fields -> smaller docs
    Aggregates.group("$category",                     // 3. now $group holds far less
        Accumulators.sum("total", "$amount"))
)).allowDiskUse(true)                                  // 4. safety net, not the primary fix
  .into(new ArrayList<>());
```

The optimization checklist in priority order: (1) **`$match` first and index-backed** so the blocking stage sees far fewer documents; (2) **`$project` early to shed unused fields** so each buffered document is smaller (a `$group` over 2 fields uses a fraction of the memory of one over 50-field documents); (3) **ensure `$sort` can use an index** — an indexed sort streams in order and needs no in-memory buffer at all, completely sidestepping the limit, whereas a non-indexed sort buffers the whole input; (4) **pre-aggregate into rollups** so dashboards query small summaries instead of re-grouping raw data each time (the materialized-view pattern via `$merge` on a schedule); (5) only then **`allowDiskUse(true)`** for the occasional large analytical job. The structural signal is that if you're routinely hitting the limit on a user-facing query, the query is doing analytics work that belongs in a pre-computed rollup collection or a dedicated analytics store, not on the hot path.

#### Q53. [Theory] What is the difference between sparse, partial, and unique indexes, and how do they interact?

These three index modifiers control *which documents* an index includes and *what constraint* it enforces, and conflating them causes real bugs. A **sparse** index only contains entries for documents that **possess the indexed field** — documents missing the field are omitted entirely, shrinking the index and changing query semantics. A **partial** index (3.2+, the modern superset) only indexes documents matching a **filter expression** you specify — far more flexible than sparse, since the condition can be any predicate, not just field existence. A **unique** index enforces that the indexed value is **distinct across all indexed documents**.

```javascript
// Sparse: only documents that HAVE an "email" field are indexed
db.users.createIndex({ email: 1 }, { sparse: true });

// Partial: index only ACTIVE users (more expressive than sparse)
db.users.createIndex(
  { lastLogin: -1 },
  { partialFilterExpression: { status: "ACTIVE" } });

// Unique + partial: enforce unique email ONLY among users who have one
db.users.createIndex(
  { email: 1 },
  { unique: true, partialFilterExpression: { email: { $exists: true } } });
```

The interaction that trips people up is **unique + nulls**. A plain unique index treats every document missing the field as having a `null` value, so a *second* document missing the field collides with the first on `null` and is rejected — you can have only one document without the field. Combining **unique with sparse** (or better, **unique with a partial filter** `{field: {$exists: true}}`) fixes this: documents lacking the field are excluded from the index, so any number of them can coexist while still enforcing uniqueness among documents that *do* have the field. This is the correct way to model "email must be unique if present" for optional fields.

| Modifier | What it includes | Primary use |
|----------|------------------|-------------|
| sparse | docs that have the field | smaller index on optional fields |
| partial | docs matching any filter | index a hot subset (e.g., ACTIVE only) |
| unique | all docs (or subset) with distinct values | enforce uniqueness |

The senior guidance is to **prefer partial over sparse** in modern MongoDB (partial subsumes sparse and is more explicit), and to be deliberate about query semantics: a sparse/partial index can cause a query to *miss* documents or skip the index entirely if the planner can't prove the query only touches indexed documents. Always `explain` to confirm the partial index is actually used for your query's filter.

#### Q54. [Coding] Implement a `$lookup` with a sub-pipeline to join with filtering and projection on the foreign side.

**Problem:** The simple `$lookup` (localField/foreignField equality) can't filter or shape the joined documents — it pulls *all* matching foreign documents. The **sub-pipeline `$lookup`** (3.6+) lets you run a full pipeline against the foreign collection with bound variables, so you can join on multiple conditions, filter the foreign side, and project only needed fields — drastically reducing the joined payload.

```java
import com.mongodb.client.model.*;
import java.util.List;
import org.bson.Document;

orders.aggregate(List.of(
  Aggregates.match(Filters.eq("status", "PLACED")),
  Aggregates.lookup(
      "reviews",                                              // from collection
      List.of(new Variable<>("oid", "$_id")),                 // let: bind local _id as $$oid
      List.of(                                                 // sub-pipeline on reviews
          Aggregates.match(Filters.expr(new Document("$and", List.of(
              new Document("$eq", List.of("$orderId", "$$oid")),     // correlate
              new Document("$gte", List.of("$rating", 4)))))),       // FILTER foreign side
          Aggregates.project(fields(excludeId(), include("rating", "text"))),
          Aggregates.sort(Sorts.descending("rating")),
          Aggregates.limit(3)),                                // top 3 reviews only
      "topReviews"),                                           // output array field
  Aggregates.project(fields(include("_id", "totalCents", "topReviews")))
)).into(new java.util.ArrayList<>());
```

**Why the sub-pipeline form:** with the simple `$lookup` you'd join *every* review for the order and then filter/sort/limit in subsequent stages on the parent side — pulling potentially thousands of reviews per order across the wire only to discard most. The sub-pipeline pushes the `$match` (rating ≥ 4), `$sort`, `$limit`, and `$project` **into the foreign lookup itself**, so only the 3 best reviews per order ever leave the `reviews` collection. The `let` clause binds parent fields into `$$`-prefixed variables, and `$expr` inside the sub-pipeline's `$match` is what allows correlating on `orderId == $$oid`.

**Performance and design caveats:** the sub-pipeline still executes **once per input document**, so a selective parent `$match` first is essential, and the foreign collection needs an index supporting the sub-pipeline's `$match` (here a `{orderId:1, rating:-1}` index lets the correlated match + sort be index-backed). Without that index, each invocation is a collection scan and the join becomes catastrophically slow on large foreign collections. As always in MongoDB, if this join is on a hot read path, reconsider whether the top reviews should be **denormalized** onto the order at write time — `$lookup` is a powerful tool but a repeated join per read is still a signal to question the schema.

### 🔴 Expert — extended

#### Q55. [Theory] Explain how WiredTiger checkpoints, the journal, and crash recovery interact to provide durability.

WiredTiger provides durability through two cooperating mechanisms with different cadences. A **checkpoint** is a consistent, on-disk snapshot of the entire dataset written every **60 seconds** (or after 2 GB of journal data, whichever comes first); it is a point from which the database can recover without replaying anything. Between checkpoints, the **journal** (a write-ahead log of operations) records every write so that changes made after the last checkpoint aren't lost on a crash. With `j:true` write concern, a write is acknowledged only after its journal record is flushed to disk (by default the journal is synced every ~100 ms or on demand), bounding the window of un-journaled loss.

```
time ───────────────────────────────────────────────────────►
   [checkpoint A]      writes W1..W9 logged to journal      [checkpoint B]
        │                    │                                    │
        │◄──── recovery replays journal W1..W9 if crash here ────►│
   on-disk snapshot    not yet in a checkpoint              new on-disk snapshot
```

**Crash recovery** is therefore: on restart, WiredTiger loads the most recent durable checkpoint, then **replays the journal** entries written after that checkpoint to bring the dataset forward to the last journaled write. Because checkpoints are atomic (WiredTiger uses copy-on-write — a checkpoint isn't "live" until its root pointer is flipped), a crash *during* a checkpoint simply falls back to the previous complete checkpoint plus journal replay; there's no half-written checkpoint to corrupt the data. This is why a `mongod` can be hard-killed and come back consistent.

The expert nuances: (1) **`j:true` vs `w:majority`** address different risks — journaling protects against a *single node's* crash (local durability), while `w:majority` protects against *failover data loss* (the write survives on enough nodes to never be rolled back); correctness-critical systems want both. (2) The checkpoint interval bounds how much journal must be replayed, trading recovery time against checkpoint I/O overhead. (3) The journal also underpins the **stable timestamp** machinery used for replication rollback-via-recovery (4.0+), where a node rolls back not by undoing ops but by recovering to a stable checkpoint timestamp and re-applying from the new primary — far faster than the old rollback-file approach. Understanding this layered design (durable checkpoint + WAL journal + copy-on-write atomicity) is the foundation for reasoning about every MongoDB durability guarantee.

#### Q56. [Practical] Design a zero-downtime migration of a 10 TB collection from a self-hosted replica set to Atlas across cloud providers.

**Scenario:** Move a 10 TB, write-heavy collection from a self-managed on-prem replica set to MongoDB Atlas in a different cloud, with no maintenance window and a tested rollback. This is a real staff-level migration that hinges on **continuous replication during cutover** rather than a stop-the-world dump/restore (which at 10 TB would mean many hours of downtime and an unverified result).

The phased plan:

1. **Live mirroring with `mongosync` / Atlas Live Migration**: stand up the Atlas target cluster, then use `mongosync` (MongoDB's cluster-to-cluster sync tool) to do an initial full copy followed by **continuous oplog tailing**, so the target stays within seconds of the source while the application keeps writing to the source. This is the backbone — it turns a one-shot copy into a maintained replica you can cut over to at leisure.
2. **Validate continuously**: monitor `mongosync` lag, compare document counts and checksums on stable collections, run the application's read path against the Atlas target in shadow mode, and verify indexes were rebuilt on the target (mongosync replicates data; you confirm index parity explicitly).
3. **Dress rehearsal in staging**: rehearse the exact cutover steps and the rollback, measuring how long write-quiescing and DNS/connection-string propagation actually take.
4. **Cutover**: at low traffic, briefly stop application writes (or put the app in read-only), let `mongosync` drain the final oplog so lag hits zero, commit the sync, switch the application's connection string to Atlas (ideally via a config/secret flip and rolling restart, or a DNS/SRV change), and resume writes against Atlas.
5. **Rollback plan**: keep the source replica set intact and, if feasible, run reverse sync (Atlas → source) during a bake period so you can fail back if Atlas-side issues surface; only decommission the source after a confidence window.

```
[on-prem RS] ──initial copy──► [Atlas target]
      │  app writes              ▲
      │                          │ continuous oplog tail (mongosync), lag→0
      └──────────────────────────┘
 cutover: quiesce writes → drain lag → flip connection string → resume on Atlas
 rollback: source kept hot (optionally reverse-synced) during bake period
```

**Trade-offs and risks to call out:** cross-cloud egress for 10 TB has real cost and time implications (seed via snapshot if the tool supports it to avoid copying 10 TB over the WAN at sync start); the brief write-quiesce is the only "downtime" and is seconds-to-minutes if lag is already near zero; connection-string propagation across many app instances must be tested (a slow rollout means some pods write to the old cluster — using an SRV record or a single shared secret minimizes the skew window); and you must verify driver/server version compatibility and feature-compatibility-version before cutover. The non-negotiables a senior engineer states up front: **tested rollback, continuous validation, and a rehearsed runbook** — the migration tooling is the easy part; the operational discipline around cutover is what prevents a 10 TB outage.

#### Q57. [Theory] How do jumbo chunks form, why do they block the balancer, and how do you resolve them?

A **chunk** is the unit of data the balancer migrates between shards, bounded by `chunkSize` (default 128 MB in modern versions). A **jumbo chunk** is one that has grown beyond `chunkSize` *and cannot be split* because every document in it shares the **same shard-key value** (or the key has too few distinct values in that range) — the balancer has no split point to divide it. MongoDB flags such chunks `jumbo`, and because the balancer refuses to move chunks larger than the migration size limit, the jumbo chunk becomes **immovable**, pinning its data to one shard and defeating balancing. This is the downstream symptom of a **low-cardinality shard key**.

```javascript
// Find jumbo chunks pinning data to one shard
use config
db.chunks.find({ jumbo: true }).count();
db.chunks.find({ jumbo: true }, { ns:1, shard:1, min:1, max:1 }).limit(5);
```

The cause is always shard-key design: if you shard on, say, `{country: 1}` and 70% of documents are `country: "US"`, all those documents fall in chunks that can't be split below the `US` boundary, so they coalesce into a giant immovable jumbo chunk on one shard — a hot shard that can't be rebalanced. The same happens with any key whose values aren't granular enough to keep chunks under `chunkSize`.

Resolution, from tactical to structural: (1) **manually clear the flag and attempt a split** if there *is* a split point (`sh.splitFind()` / clearing jumbo and letting the balancer retry) — works only when distinct sub-values exist; (2) **temporarily raise `chunkSize`** so the chunk is movable, migrate it, then restore — a stopgap; (3) the real fix is to **change the shard key to a higher-cardinality compound key** (e.g., `{country: 1, userId: 1}` so chunks can split within a country), which on 5.0+ means **`reshardCollection`**, and on 8.0+ you can also **add a suffix field** to refine an existing key without a full reshard. The expert lesson is that jumbo chunks are not a balancer bug to be worked around but a **shard-key cardinality smell**; the durable answer is to design (or reshard to) a key whose value granularity keeps every chunk splittable below `chunkSize`.

#### Q58. [Coding] Implement a robust transaction-with-retry helper that distinguishes transient from permanent errors.

**Problem:** Production transaction code must correctly retry **transient** failures (write conflicts, transient network errors, unknown-commit-result) while *not* retrying **permanent** errors (a business-rule violation, a duplicate key), and it must bound retries to avoid infinite loops. The driver's `withTransaction` already implements the standard retry loop, but interviewers want to see that you understand the error-label contract underneath it and can reason about idempotency.

```java
import com.mongodb.*;
import com.mongodb.client.*;
import java.util.function.Function;

<T> T runTxn(MongoClient client, TransactionOptions opts,
             Function<ClientSession, T> body) {
    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(120).toNanos();
    try (ClientSession session = client.startSession()) {
        while (true) {
            try {
                session.startTransaction(opts);
                T result = body.apply(session);     // body must be IDEMPOTENT
                // commit loop: retry commit on UnknownTransactionCommitResult
                while (true) {
                    try { session.commitTransaction(); return result; }
                    catch (MongoException ce) {
                        if (ce.hasErrorLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL)
                                && System.nanoTime() < deadline) continue;  // retry commit
                        throw ce;
                    }
                }
            } catch (MongoException e) {
                if (session.hasActiveTransaction()) session.abortTransaction();
                // Only retry the WHOLE txn on a transient-transaction error
                if (e.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL)
                        && System.nanoTime() < deadline) continue;
                throw e;   // permanent (e.g., duplicate key, business rule) -> propagate
            }
        }
    }
}
```

**The error-label contract is the heart of this.** MongoDB attaches **error labels** to exceptions rather than relying on opaque error codes: `TransientTransactionError` means "the whole transaction can be safely retried from the top" (the most common cause is a `WriteConflict` from two transactions touching the same document under snapshot isolation); `UnknownTransactionCommitResult` means "the commit may or may not have succeeded — retry the *commit*, not the body" (typically a network blip during commit acknowledgment). Retrying the right scope for each label is what makes this correct: retrying the body on an unknown-commit-result could double-apply effects, while only retrying the commit on a transient error would loop forever because the transaction was already aborted.

**Idempotency and bounds are non-negotiable.** Because the body can run multiple times, it must be free of external side effects (don't send an email or call a payment API mid-transaction — record an intent and act after commit) and must not depend on state mutated by a prior aborted attempt. The deadline prevents a perpetual write-conflict storm from looping forever. In real code you should just use `session.withTransaction(body, opts)`, which encapsulates exactly this loop — but being able to *write* it demonstrates you understand why MongoDB transactions retry, what they retry, and why the transaction body has to be a pure, idempotent function. The deeper design point recurs: if a given transaction conflicts constantly, the schema is forcing cross-document coordination that single-document atomicity or a better aggregate boundary would eliminate.

#### Q59. [Theory] Compare MongoDB with Cassandra, DynamoDB, and PostgreSQL/JSONB along the dimensions that actually drive a decision.

A staff-level comparison avoids "X is better" and instead maps each system to the workload it was designed for. The honest framing is that these databases sit at different points on the consistency-model, data-model, and operational-ownership axes, and the right choice falls out of the access pattern and the team's operational appetite.

| Dimension | MongoDB | Cassandra | DynamoDB | PostgreSQL + JSONB |
|-----------|---------|-----------|----------|--------------------|
| Data model | Rich documents, nested, secondary indexes anywhere | Wide-column, query-driven tables | Key-value/document, key-driven | Relational + document column |
| Consistency | Tunable; CP default, strong on primary | Tunable AP (quorum); eventual default | Tunable (eventual/strong per read) | Strong ACID, serializable available |
| Write model | Single primary per shard | Masterless, multi-master writes | Managed, partition-key writes | Single primary (logical/streaming repl) |
| Query flexibility | High (rich aggregation, ad-hoc) | Low (must model per query, no ad-hoc joins) | Low (key + GSIs, no joins) | Very high (full SQL + JSON ops) |
| Horizontal scale | Sharding (operationally involved) | Excellent, linear, built-in | Excellent, fully managed | Limited (read replicas; sharding via add-ons) |
| Operational model | Self-managed or Atlas | Self-managed (heavy) or managed | Fully managed only (AWS lock-in) | Self-managed or managed; simplest single-node |

The decision logic: **Cassandra** wins when you need masterless multi-region writes with extreme, linear write scalability and can fully model your tables around fixed query patterns (time-series at massive scale, write-heavy with no ad-hoc querying) — but you pay with rigid query modeling and operational complexity. **DynamoDB** wins when you want zero operational burden inside AWS and your access is predominantly key-based with predictable patterns, accepting vendor lock-in and the discipline of single-table design. **PostgreSQL + JSONB** wins when the workload is fundamentally relational with *some* flexible/nested data — you get document flexibility *and* joins, constraints, and mature SQL/BI tooling, at the cost of harder horizontal write scaling. **MongoDB's** sweet spot is the middle: rich, evolving document models that need ad-hoc querying and aggregation *plus* horizontal scale-out, with tunable per-operation consistency — more query flexibility than Cassandra/DynamoDB, more natural document modeling and easier scale-out than Postgres.

The expert's closing point in an interview is that the comparison is **workload-driven, not feature-driven**: ask for the dominant read/write patterns, the consistency and multi-region requirements, the team's operational maturity, and the growth curve — and frequently the answer is a *combination* (e.g., Postgres as the system of record + MongoDB or a search engine for a specific document-shaped subsystem), because picking one database for an entire heterogeneous platform is itself an anti-pattern.

#### Q60. [Practical] Disk usage keeps growing even after deleting large amounts of data. Explain why and how you reclaim space.

This surprises people coming from databases that immediately shrink: in WiredTiger, **deleting documents does not return space to the operating system**. Deleted documents free space *inside* the data files for reuse by future inserts/updates in the same collection, but the files themselves stay the same size — so `df` shows no improvement after a big `deleteMany`, and a collection that briefly held a billion documents keeps its on-disk footprint even after you delete 90% of them. This is by design (avoiding constant expensive file shrinking), but it means storage planning must account for high-water-mark usage, and a one-time bulk delete leaves "holes" that only refill if the collection grows again.

```javascript
// See the gap between logical data size and on-disk file size
db.events.stats().storageSize;   // bytes the files occupy on disk
db.events.stats().size;          // logical (uncompressed) size of live documents
// large storageSize with small size after a big delete => reclaimable fragmentation
```

To actually return space to the OS, the options each carry trade-offs:

1. **`compact`** (per collection): rewrites and defragments the collection's files in place, returning freed space. In modern versions it requires far less blocking than it used to, but it still consumes I/O and can affect performance, so run it on **secondaries first** in a rolling fashion (compact a secondary, step down the primary, compact the old primary), never blindly on a busy primary.
2. **Resync the member**: remove a secondary and let it perform a fresh **initial sync**, which writes a brand-new compact set of files; rotate through all members and finally step down the primary. This is the cleanest way to reclaim space cluster-wide and also rebuilds indexes optimally, at the cost of time and temporary reduced redundancy.
3. **`db.dropDatabase()` / drop+recreate the collection**: instantly reclaims everything by deleting files — only viable when you can discard the data (e.g., dropping an expired partition collection).

The structural lesson is to **design for deletion** rather than fight reclamation: use **TTL indexes** for time-expiring data (steady-state deletes that refill space organically), and for large rolling datasets use **time-partitioned collections** (a collection per day/month) so retiring old data is a cheap `drop` of an entire collection — which *does* immediately free the files — instead of a massive `deleteMany` that leaves unreclaimable fragmentation. A senior engineer anticipates this at schema-design time, because retrofitting partitioning onto a single ever-growing collection after the disk fills is a painful migration.

#### Q61. [Theory] Explain how secondary reads, read preference tags, and maxStalenessSeconds let you build geo-distributed read scaling safely.

Scaling reads by serving them from secondaries is powerful but dangerous if done naively, because secondaries lag the primary and can return stale data. MongoDB gives three composable controls to do it *safely*. **Read preference mode** (`primary`, `primaryPreferred`, `secondary`, `secondaryPreferred`, `nearest`) chooses the class of node. **Tag sets** let you label members with arbitrary key-value tags (e.g., `{region: "eu", dc: "eu-west-1"}`) and route reads to members matching a tag — the mechanism for pinning reads to a geographically local replica. **`maxStalenessSeconds`** caps how far behind a secondary may be and still be eligible; the driver excludes any secondary whose estimated lag exceeds the bound, so you can say "read from a local secondary, but never one more than 90 seconds stale."

```java
// Read from a same-region secondary, but only if it's within 90s of the primary
TagSet eu = new TagSet(List.of(new Tag("region", "eu")));
MongoCollection<Document> c = db.getCollection("catalog")
    .withReadPreference(ReadPreference.secondaryPreferred(
        List.of(eu), 90L, TimeUnit.SECONDS));   // tag + maxStaleness
```

```
            writes
client(EU) ───────────► [PRIMARY us-east] ──oplog──► [SECONDARY eu-west {region:eu}]
   reads ─────────────────────────────────────────────────►  ▲ tag-matched,
   (secondaryPreferred, tag region=eu, maxStaleness=90s)        within 90s lag
```

The architecture this enables is **geo-local read scaling**: place tagged secondaries in each region, point each region's app at its local secondary via tags, and bound staleness so you never serve dangerously old data. The trade-offs to articulate: (1) `maxStalenessSeconds` must be at least 90s and is a *coarse* safety bound, not a freshness guarantee — it prevents catastrophically stale reads but a read can still be tens of seconds behind, so it's wrong for read-your-writes correctness (use a causally-consistent session or read from primary for that); (2) secondary reads with `readConcern: local` can return data that later rolls back, so for correctness-sensitive reads pair secondary reads with `readConcern: majority`; (3) tag-based routing can overload one tagged member if you tag too few, and a failed tagged secondary with no fallback tag makes reads fail — always provide a fallback or use `Preferred` modes. The expert framing: secondary reads are a **scaling and latency** tool for read-heavy, staleness-tolerant workloads (catalogs, analytics dashboards, search), explicitly *not* a consistency tool — and combining read preference, tags, and `maxStalenessSeconds` is how you scale reads geographically without silently shipping stale or rollback-prone data to users.

#### Q62. [Practical] A junior engineer reports that `count()` is slow and sometimes returns wrong numbers in a sharded cluster. Explain what's happening and the correct approach.

This bundles two real gotchas. First, the **accuracy** issue: the legacy `count()` / `db.collection.count()` (and the driver's deprecated count) returns a value derived partly from collection **metadata** that can be **inaccurate after an unclean shutdown** or, on a sharded cluster, can **over-count documents that are mid-migration** (a chunk being moved is briefly present on both the source and destination shard, so a metadata-based count can double-count those documents, and orphaned documents from interrupted migrations inflate it further). That's why a fast count can disagree with reality. Second, the **performance** issue: an *accurate* count of a filtered query has to actually examine matching index entries or documents, so counting "how many orders are PLACED" across millions of rows is inherently O(matching keys) — there's no free precomputed answer.

```java
// WRONG for accuracy on sharded clusters: metadata-based, can over/under-count
long fast = collection.estimatedDocumentCount();   // whole-collection estimate only

// CORRECT, accurate count of a filter: examines index/docs (slower but right)
long exact = collection.countDocuments(eq("status", "PLACED"));
```

The correct mental model and API choice: **`estimatedDocumentCount()`** is fast because it reads collection metadata, but it only counts the *entire* collection (no filter) and is an *estimate* subject to the inaccuracies above — perfect for a rough "how big is this collection" dashboard number, wrong for anything that must be exact. **`countDocuments(filter)`** runs an actual aggregation (`$match` + `$group`/`$count`) under the hood, so it is **accurate even on sharded clusters** (it filters out orphans and doesn't double-count migrating chunks) but pays the cost of examining the matching set; it's the right tool when correctness matters. So the junior is seeing the classic trap of using the fast estimate where an accurate filtered count was needed.

The senior guidance has three parts. (1) **Choose the right method for the requirement**: estimate for a cheap whole-collection gauge, `countDocuments` when the number must be correct. (2) **Make accurate counts cheap with an index**: `countDocuments(eq("status","PLACED"))` backed by an index on `status` becomes an index-only count (count the keys, no document fetch) — confirm with `explain` that it's a `COUNT_SCAN`/`IXSCAN`, not a `COLLSCAN`. (3) **For high-frequency exact counts on huge data, maintain a counter**: increment/decrement a precomputed count document (or a rollup) on writes (the *computed pattern*), so the read is O(1) instead of O(n) — the same reason you never `SELECT COUNT(*)` a billion-row table on every page load. The structural lesson is that "count is slow/wrong" is usually an API-choice and indexing problem, and at scale exact counts are something you *maintain*, not *compute on demand*.

#### Q63. [Theory] What are the trade-offs of MongoDB time-series collections versus the manual bucket pattern, and when would you still hand-roll buckets?

Both approaches solve the same problem — naively storing one document per measurement crushes index size and write throughput at high ingest rates — but they differ in who does the work. The **manual bucket pattern** is an application-managed design where you group many measurements into one "bucket" document (e.g., one document per device per hour holding an array of readings), so a thousand readings become one document with one index entry instead of a thousand. **Native time-series collections** (5.0+) automate exactly this: you declare `timeField`, `metaField`, and `granularity`, and MongoDB internally buckets writes, stores them in an optimized columnar-ish layout, clusters by time, and presents a normal one-document-per-measurement *logical* view while physically storing compact buckets — plus it integrates TTL expiry and `$setWindowFields` cleanly.

```javascript
// Native time-series: MongoDB manages bucketing internally
db.createCollection("readings", {
  timeseries: { timeField: "ts", metaField: "deviceId", granularity: "minutes" },
  expireAfterSeconds: 7776000   // built-in 90-day TTL
});
// You insert one doc per reading; MongoDB buckets under the hood.
db.readings.insertOne({ ts: new Date(), deviceId: "dev42", temp: 21.3 });
```

| Aspect | Native time-series | Manual bucket pattern |
|--------|--------------------|-----------------------|
| Write API | one doc per measurement (simple) | app must append to/create buckets |
| Storage | auto columnar compression, very compact | depends on your bucket design |
| Indexing | optimized internal bucketing | you index the bucket fields |
| Flexibility | constrained (limited updates/deletes historically) | full control over schema/shape |
| Maintenance | MongoDB-managed, integrates TTL | all on the application |

The trade-offs that decide it: native time-series wins on **simplicity, storage efficiency, and write throughput** for append-mostly telemetry, and it should be the default for new IoT/metrics/event workloads. You'd still **hand-roll buckets** when you hit native limitations: needing **frequent in-place updates or arbitrary deletes** of historical points (native time-series restricts these and is optimized for append-only), needing a **custom bucket shape** that mixes measurements with rich nested metadata or computed fields the native model doesn't accommodate, requiring **fine-grained control over the bucket boundary** for a specific query pattern, or running on a **version without time-series support** or with feature constraints. There's also a subtle scaling consideration: sharding and certain index behaviors on time-series collections have version-specific limitations, so for the very largest multi-tenant deployments some teams still manage buckets explicitly to control the shard key and chunk distribution precisely. The expert summary: prefer native time-series for the 90% case, and reach for manual buckets only when a concrete native limitation (mutability, custom shape, or sharding control) forces your hand.

#### Q64. [Behavioral] Describe a time you pushed back on a team's decision to use (or not use) MongoDB, and how you handled the disagreement.

This question probes engineering judgment, influence without authority, and intellectual honesty — interviewers want to see that you reason from requirements and data, not from tool preference or ego. Use **STAR** and make the pushback *evidence-driven and collaborative* rather than combative.

A strong answer: "A team was about to build a new double-entry **billing ledger** on MongoDB because the rest of the platform already used it and they wanted consistency of tooling. **(Situation)** I was concerned because a ledger is deeply relational and correctness-critical — every transaction must touch multiple accounts atomically with strong invariants, which is exactly the workload where MongoDB's strengths don't apply and its multi-document transactions become the hot path. **(Task)** Rather than declare 'MongoDB is wrong,' my job was to make the trade-off visible and let the team decide with full information. **(Action)** I wrote a one-page decision doc framing it by access pattern: I laid out the dominant operations (atomic multi-account postings, reconciliation queries, strict auditability), prototyped the same ledger posting in both Postgres and MongoDB, and measured that the MongoDB version required a cross-document transaction on every write and re-implemented constraints in application code, while Postgres expressed the invariants declaratively with serializable transactions. I deliberately also documented where MongoDB *would* win (the customer-facing usage-event store feeding the ledger), so it wasn't a one-sided takedown. I brought it to a design review, not a hallway argument, and invited the original proposer to poke holes in my prototype. **(Result)** The team chose Postgres for the ledger and kept MongoDB for the high-volume usage-event ingestion — a polyglot split that played to each store's strengths. Just as important, we adopted the 'decide by access pattern, prototype the risky path' approach as a lightweight standard for future datastore choices."

The signals this demonstrates: **disagreeing without making it personal** (a written, falsifiable argument others could critique), **intellectual honesty** (acknowledging where the other side was right and proposing a polyglot answer rather than a winner-take-all), **evidence over opinion** (a prototype and measurements, not 'NoSQL is bad'), and **turning a one-off disagreement into a durable process**. A weaker answer asserts MongoDB is simply the wrong tool and 'I told them so'; the strong version shows you can hold a firm technical position while keeping the team's trust and arriving at the best *system*, not the most-defended opinion. The same template works inverted (pushing *for* MongoDB against a team defaulting to Postgres for a genuinely document-shaped, schema-evolving, scale-out workload) — the throughline is requirements-first reasoning and collaborative influence.



- **Model around access patterns, not entities.** Embed data read together (bounded, one-to-few/many); reference shared, unbounded, or many-to-many data. The aggregate boundary is your design unit.
- **The 16 MB document limit and unbounded-array antipattern** are the two embedding traps to design against.
- **Index with the ESR rule** (Equality, Sort, Range) and verify with `explain` — aim for `IXSCAN`, `totalDocsExamined ≈ nReturned`, and covered queries where possible.
- **Replica sets give HA via Raft-like elections + a majority commit point.** Use `w:"majority"` for durability that survives failover; `w:1` can be silently rolled back.
- **The shard key is the most consequential decision in sharding** — high cardinality, even distribution, non-monotonic, query-aligned. Monotonic keys cause hot shards; fix with hashed or compound keys, reshard with `reshardCollection` (5.0+).
- **Consistency is tunable per operation** via read concern, write concern, and read preference; MongoDB is CP under partition (PACELC: PC/EL-or-C).
- **Transactions exist (4.0/4.2+) but are an escape hatch** — prefer single-document atomicity; design shard keys so transactional units stay on one shard to avoid expensive 2PC.
- **Change streams** enable resumable, real-time CDC/event-driven architectures built on the oplog.
- **Keep the working set in the WiredTiger cache** — this single fact governs most MongoDB performance.
- **Security: auth on, private network, TLS, RBAC least-privilege, and field-level/queryable encryption for PII.** The historical breaches came from defaults left open.

## ⚠️ Common Pitfalls

- **Sharding on a monotonic key** (`ObjectId`, timestamp, auto-increment) → all writes hit one hot shard.
- **Unbounded arrays** (e.g., embedding every comment forever) → documents grow toward 16 MB and updates rewrite the whole document.
- **Looping single `updateOne` calls** instead of `bulkWrite` → N network round-trips and crushed throughput.
- **`$lookup` on every read** → a smell that you over-normalized; consider controlled denormalization.
- **`$match`/`$sort` placed late in a pipeline** → the index can't be used and the stream is huge before filtering.
- **Reading from secondaries assuming read-your-writes** → stale reads due to replication lag; use a causally-consistent session or read from primary.
- **Running with two data-bearing nodes + arbiter and expecting `w:majority` resilience** → if one data node fails, majority writes block.
- **Creating one `MongoClient` per request** → connection-pool churn; create one per application.
- **Ignoring the WiredTiger cache size** → working set spills to disk and latency collapses.
- **Leaving authentication disabled / binding to `0.0.0.0` publicly** → the classic MongoDB internet breach.
- **Long-running multi-document transactions** → lock contention, the 60s limit, and `TransactionTooLargeForCache`.
- **Not testing restores** → discovering a broken backup during a real disaster.

## 🧩 Extended Questions — Supplemental Set: Mixed Depth

### 🟢 Basic — extended

#### Q65. [Practical] How do you project, sort, skip, and limit results, and what is the correct way to paginate large collections?

Projection, sort, skip, and limit are the four read-shaping primitives, and the way juniors combine them for pagination is the most common performance footgun in MongoDB. **Projection** (`{field:1}` to include, `{field:0}` to exclude — you generally can't mix include/exclude except for `_id`) reduces network and memory by returning only needed fields. **Sort** orders results; if no index supports the sort, MongoDB performs an in-memory sort capped at 100 MB. **`skip(n)` + `limit(m)`** is the naive pagination idiom, and it is the trap: `skip(n)` must *walk and discard* the first `n` documents on every page request, so page 10,000 of a feed re-scans a million entries each time — pagination cost grows linearly with the page number.

```java
// ANTIPATTERN: skip grows linearly — page 5000 re-scans ~100k docs every call
posts.find().sort(Sorts.descending("createdAt")).skip(pageNum * 20).limit(20);

// CORRECT: range / keyset ("seek") pagination — O(log n) per page, constant cost
// Pass the last seen sort key from the previous page as the cursor.
posts.find(lt("createdAt", lastSeenCreatedAt))     // "everything after the cursor"
     .sort(Sorts.descending("createdAt"))
     .limit(20);
```

The correct technique is **keyset (range/seek) pagination**: instead of "skip N rows," remember the sort-key value of the last document on the current page and ask for "documents whose key is less than that value." Backed by an index on the sort field, every page is an `O(log n)` index seek plus a 20-document scan — constant cost regardless of depth. The trade-offs to articulate: keyset pagination doesn't support arbitrary "jump to page 4,000" links (only next/previous), and it needs a **unique, stable, totally-ordered** cursor — if `createdAt` can tie, use a compound cursor `(createdAt, _id)` and compare lexicographically so the boundary is deterministic and you never skip or duplicate a row across pages. For UIs that genuinely need numbered pages over small result sets, `skip/limit` is fine; for infinite-scroll feeds, activity timelines, and any deep pagination, keyset is the only design that scales.

#### Q66. [Coding] Write the array update operators (`$push`, `$addToSet`, `$pull`, `$pop`) and explain when each applies.

**Problem:** Manipulating embedded arrays in place — appending, deduplicating, removing by value, and trimming — without reading the document, mutating it in the app, and writing it back (which is both a round-trip and a lost-update race). MongoDB's array update operators do all of this atomically server-side.

```java
import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Updates.*;
import com.mongodb.client.model.PushOptions;
import java.util.List;

// $push: append (allows duplicates); with $each + $slice keep only the last 10
users.updateOne(eq("_id", "u1"),
    pushEach("recentItems", List.of("sku9"), new PushOptions().slice(-10)));

// $addToSet: append ONLY if not already present (set semantics)
users.updateOne(eq("_id", "u1"), addToSet("tags", "vip"));

// $pull: remove ALL elements matching a condition (here qty <= 0)
carts.updateOne(eq("_id", "c1"), pull("items", lte("qty", 0)));

// $pop: remove first (-1) or last (1) element — a queue/stack trim
users.updateOne(eq("_id", "u1"), popFirst("eventLog"));   // FIFO drain
```

The decision matrix: **`$push`** appends unconditionally and pairs with `$each` (push many), `$slice` (cap array length — the bounded-array pattern that protects against the 16 MB limit), and `$sort` (keep the array ordered); **`$addToSet`** gives idempotent set-insert, ideal for tags/roles where duplicates are meaningless; **`$pull`** removes every element matching a query predicate (use `$pullAll` to remove exact literal values); **`$pop`** trims one end, turning an array into a bounded queue or stack.

The "why" is atomicity and concurrency: `users.updateOne(eq("_id",id), push("log", entry))` is a single atomic operation that never loses a concurrent append, whereas read-modify-write in the application is a classic lost-update bug under concurrency (two requests read the same array, each appends one element, and one append vanishes). The senior note: combine `$push` with `$slice` to keep arrays bounded (e.g., "last 50 events") so embedded arrays never grow unbounded toward the document size limit — pruning at write time is far cheaper than discovering 16 MB documents in production.

#### Q67. [Theory] How do you query and update fields inside nested documents and arrays using dot notation, and what are the pitfalls?

**Dot notation** (`"a.b.c"`) is how MongoDB reaches into embedded sub-documents and array elements for both queries and updates, and understanding its array semantics is essential because the behavior is subtle. For an embedded object, `{"address.city": "Austin"}` matches documents whose nested `address.city` equals `"Austin"`. For an array of sub-documents, `{"items.sku": "A99"}` matches if **any** array element has `sku == "A99"` — but a multi-condition query like `{"items.sku":"A99", "items.qty":{$gt:5}}` matches if `sku:"A99"` is satisfied by *one* element and `qty>5` by *possibly a different* element, which is usually not what you want.

```javascript
// Cross-element bug: matches if SOME item is "A99" and SOME (other) item has qty>5
db.orders.find({ "items.sku": "A99", "items.qty": { $gt: 5 } });

// CORRECT: $elemMatch requires BOTH conditions in the SAME array element
db.orders.find({ items: { $elemMatch: { sku: "A99", qty: { $gt: 5 } } } });

// Positional update: $ updates the FIRST matched element
db.orders.updateOne({ _id: 1, "items.sku": "A99" }, { $set: { "items.$.qty": 10 } });

// arrayFilters: update ALL elements matching a named filter
db.orders.updateOne({ _id: 1 },
  { $set: { "items.$[low].backordered": true } },
  { arrayFilters: [ { "low.qty": { $lt: 1 } } ] });

// $[] : update EVERY element in the array
db.orders.updateMany({}, { $inc: { "items.$[].version": 1 } });
```

The pitfalls interviewers probe: (1) the **cross-element matching trap** above — use `$elemMatch` whenever two-or-more conditions must hold on the *same* array element; (2) the **positional `$` operator** updates only the *first* matching element and requires the array field to appear in the query filter; (3) for updating *multiple* matching elements use **`arrayFilters` with `$[identifier]`** (3.6+), and to update *all* elements use **`$[]`**. A further subtlety is that creating a deeply nested field via dot notation in `$set` auto-creates intermediate objects, but a numeric path segment is ambiguous between "array index" and "object key named '0'", which can silently create the wrong structure. The senior guidance is to always reach for `$elemMatch` in queries over arrays-of-objects and `arrayFilters` for multi-element updates, and to verify the resulting shape — dot-notation array semantics are correct but unintuitive, and the cross-element bug ships to production constantly.

#### Q68. [Practical] How do you import and export data with `mongoimport`, `mongoexport`, `mongodump`, and `mongorestore`, and how do they differ?

These four CLI tools split along two axes that engineers routinely confuse: **logical text formats vs. native binary**, and **single-collection vs. whole-database**. `mongoexport`/`mongoimport` work in **JSON/CSV** — human-readable, portable to other systems, but **lossy** because JSON can't perfectly represent every BSON type (extended JSON mitigates this but CSV especially mangles dates, Decimal128, and binary). `mongodump`/`mongorestore` work in **BSON** — a faithful, type-preserving binary dump-and-restore, the right tool for backups and migrations between MongoDB deployments.

```bash
# EXPORT a query result to CSV for a data team (lossy, but portable)
mongoexport --uri="mongodb://localhost:27017" --db=app --collection=orders \
  --query='{"status":"PLACED"}' --fields=_id,customerId,totalCents \
  --type=csv --out=placed_orders.csv

# IMPORT CSV/JSON, upserting on a key to make re-runs idempotent
mongoimport --db=app --collection=orders --type=csv --headerline \
  --mode=upsert --upsertFields=_id --file=placed_orders.csv

# DUMP a database to BSON (type-faithful backup), gzip-compressed
mongodump --uri="mongodb://localhost:27017" --db=app --gzip --out=/backup/2026-06-16

# RESTORE that BSON dump (optionally to a different db with --nsFrom/--nsTo)
mongorestore --uri="mongodb://target:27017" --gzip --drop /backup/2026-06-16/app
```

The decision and the gotchas: use **`mongodump`/`mongorestore`** for backup/restore and MongoDB-to-MongoDB moves of modest size, because BSON round-trips every type exactly and supports `--gzip`, `--archive` (single-stream), namespace remapping (`--nsFrom`/`--nsTo`), and `--drop` to replace existing data. Use **`mongoexport`/`mongoimport`** only for interchange with non-MongoDB systems (feeding a CSV to analysts, seeding from a flat file), and always specify `--type` and, for imports, `--mode=upsert --upsertFields` so re-running the import is idempotent rather than duplicating rows. The critical production caveats: none of these is point-in-time consistent across a sharded cluster (you need continuous oplog-based backup for that — `mongodump --oplog` only helps on a single replica set), `mongodump` reads through the working set and can evict hot data from cache on a busy primary (run it against a secondary), and `mongoexport` to CSV is **not a backup** because the type loss can silently corrupt your data on re-import. The interview signal is knowing that "I'll just `mongoexport` it" is wrong for backups and that BSON tools are for fidelity while JSON/CSV tools are for portability.

### 🟡 Intermediate — extended

#### Q69. [Coding] Implement an idempotent upsert that correctly separates insert-only fields from always-updated fields using `$setOnInsert`.

**Problem:** An upsert must set some fields *only when creating* the document (a `createdAt`, an initial `status`, a generated key) and others *on every write* (a `lastSeen`, a counter), but a naive `$set` of `createdAt` overwrites the original creation time on every subsequent upsert. `$setOnInsert` applies its fields **only when the upsert actually inserts**, never on a match-and-update — exactly the semantics needed.

```java
import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Updates.*;
import com.mongodb.client.model.UpdateOptions;
import java.time.Instant;

UpdateResult r = devices.updateOne(
    eq("_id", deviceId),
    combine(
        setOnInsert("createdAt", Instant.now()),     // ONLY on first insert
        setOnInsert("status", "PROVISIONED"),         // ONLY on first insert
        set("lastSeen", Instant.now()),               // every call
        inc("heartbeatCount", 1)),                    // every call
    new UpdateOptions().upsert(true));

boolean wasInserted = r.getUpsertedId() != null;      // distinguish create vs update
```

The mechanics: with `upsert(true)`, MongoDB first tries to match the filter; on a match it applies the update operators (skipping `$setOnInsert`), and on no match it constructs a new document from **the filter's equality fields + all `$set`/`$setOnInsert`/`$inc` results**. So `eq("_id", deviceId)` seeds the `_id`, `$setOnInsert` seeds the immutable creation fields, and `$set`/`$inc` apply in both branches. This single atomic operation replaces the racy "find, branch on existence, then insert-or-update" application logic — which has a window where two concurrent requests both see "not found" and both insert.

The production concerns: (1) **upsert under concurrency can throw a duplicate-key error** even on a unique index, because two requests can race past the match check and both attempt the insert; the idiomatic fix is to **catch the duplicate-key error and retry the upsert once** (the second attempt now matches and updates) — MongoDB's own retryable-writes machinery handles many cases, but on a unique secondary index you still handle `E11000` explicitly. (2) The filter should match on the field(s) backed by the unique index so the "match" path is reliable. (3) Distinguishing insert from update via `getUpsertedId()` lets you fire "new device onboarded" side-effects exactly on creation. This pattern — `$setOnInsert` for immutable-on-create fields, `$set`/`$inc` for always-fields, plus duplicate-key retry — is the canonical idempotent-ingest building block.

#### Q70. [Theory] What is collation, and how do you implement correct case-insensitive and locale-aware queries and indexes?

**Collation** is the set of language-specific rules that determine how strings compare and sort — case sensitivity, accent/diacritic sensitivity, and locale ordering (e.g., in Swedish `ä` sorts after `z`, in German it sorts near `a`). Without collation, MongoDB compares strings by raw byte value, so `"apple" < "Banana"` is *false* (uppercase `B` = 66 sorts before lowercase `a` = 97), and accented characters sort in code-point order, both of which are wrong for user-facing text. Collation makes comparisons linguistically correct and is the *right* way to do case-insensitive matching — far better than the common `$regex`/`$toLower` hacks.

```javascript
// Case-insensitive, accent-insensitive collation: en locale, strength 1
db.users.createIndex(
  { name: 1 },
  { collation: { locale: "en", strength: 1 } });   // 1 = case+accent insensitive

// This query USES the index above because the collation matches
db.users.find({ name: "andré" })
        .collation({ locale: "en", strength: 1 });   // matches "André", "ANDRE", ...
```

```java
import com.mongodb.client.model.Collation;
import com.mongodb.client.model.CollationStrength;

Collation ci = Collation.builder()
    .locale("en").collationStrength(CollationStrength.PRIMARY).build();   // strength 1
users.find(eq("name", "andré")).collation(ci);
```

The strength levels are the key knob: **strength 1 (PRIMARY)** ignores case *and* accents, **strength 2 (SECONDARY)** is accent-sensitive but case-insensitive, **strength 3 (TERTIARY, the default)** is case- and accent-sensitive. The critical performance rule interviewers test: **an index is only used by a query if their collations match.** If you create an index with `{locale:"en", strength:2}` but query with the default collation (strength 3), the planner can't use that index and falls back to a collection scan — a subtle, common cause of "my index isn't being used." Best practice is to set the collation at the **collection level** at creation (`db.createCollection("users", {collation: {...}})`) so every index and query inherits it consistently, then you get correct, case-insensitive behavior *and* index usage automatically. The alternative anti-patterns — storing a duplicate lowercased field, or matching with `$regex: /^andre$/i` (which can't use a normal index efficiently) — are inferior to collation, which is the purpose-built feature.

#### Q71. [Coding] Build a materialized view (rollup) with `$merge` and explain how it differs from `$out`.

**Problem:** A dashboard recomputes "daily revenue per category" on every load by scanning millions of orders — expensive and slow. The fix is the **materialized-view pattern**: run the heavy aggregation on a schedule and persist the results into a small rollup collection that dashboards read cheaply. `$merge` (4.2+) writes pipeline output into a collection with *incremental* merge semantics, making it the engine for maintained rollups.

```java
import com.mongodb.client.model.*;
import java.util.List;
import org.bson.Document;

orders.aggregate(List.of(
    Aggregates.match(Filters.gte("placedAt", since)),
    Aggregates.group(
        new Document("category", "$category")
            .append("day", new Document("$dateTrunc",
                new Document("date", "$placedAt").append("unit", "day"))),
        Accumulators.sum("revenue",
            new Document("$multiply", List.of("$qty", "$price"))),
        Accumulators.sum("orders", 1)),
    // $merge: upsert into the rollup, REPLACING the matched doc per (category, day)
    Aggregates.merge("daily_category_revenue",
        new MergeOptions()
            .uniqueIdentifier(List.of("_id"))
            .whenMatched(MergeOptions.WhenMatched.REPLACE)
            .whenNotMatched(MergeOptions.WhenNotMatched.INSERT))
)).toCollection();   // terminal stage: nothing returns to the client
```

`$merge` vs `$out` is the heart of the answer. **`$out`** writes the entire pipeline result to a target collection by **replacing it wholesale** — it atomically drops and recreates the collection (or errors if it can't), so it's all-or-nothing and cannot incrementally update; it also must write to the same database and ignores any existing data. **`$merge`** is far more flexible: it can **insert, merge, replace, keep, or fail** per-document based on whether a document with the same `on` key already exists (`whenMatched`/`whenNotMatched`), can write to a **different database or even a sharded collection**, and crucially supports **incremental rollups** — you can run only over *new* data (`$match` on a watermark) and merge the deltas into an existing rollup rather than recomputing everything.

The design payoff and trade-offs: scheduling this pipeline (cron, Atlas Triggers, or an application scheduler) every N minutes turns an `O(millions)` per-dashboard-load query into an `O(small)` read of pre-aggregated rows — the same reason data warehouses maintain summary tables. The trade-offs to name: the rollup is **eventually consistent** with the source (stale by up to the refresh interval), incremental merges require a reliable **watermark** (a `lastProcessed` timestamp) and idempotent re-runs so a retried job doesn't double-count, and `whenMatched: REPLACE` vs an accumulating `merge` pipeline matters when a window can receive late-arriving data (use a merge pipeline that `$add`s deltas for additive late data, or recompute the affected window). For user-facing analytics at scale, this offline-rollup-then-cheap-read architecture is mandatory; running the raw aggregation on the hot path doesn't survive growth.

#### Q72. [Practical] How do you find and fix slow queries in production using the database profiler and slow-query log?

The **database profiler** is MongoDB's built-in mechanism for capturing detailed execution data about operations, writing them to a capped `system.profile` collection per database, while the **slow-query log** mirrors slow operations into `mongod`'s regular log file. Together they answer "which queries are actually slow on this live system, and why" without you having to reproduce the query by hand — the starting point for any real performance investigation that isn't a single known query.

```javascript
// Profiling levels: 0=off, 1=ops slower than slowms, 2=ALL ops (heavy!)
db.setProfilingLevel(1, { slowms: 100 });        // capture anything over 100ms
db.getProfilingStatus();                          // confirm level + threshold

// Query the profile collection for the worst offenders, newest first
db.system.profile.find({ millis: { $gt: 100 } })
  .sort({ ts: -1 }).limit(10)
  .projection({ op:1, ns:1, millis:1, planSummary:1, keysExamined:1, docsExamined:1, nreturned:1 });

// The smoking gun: planSummary "COLLSCAN" or docsExamined >> nreturned
```

The investigation workflow: (1) set profiling level **1** with a sensible `slowms` (the default 100 ms is reasonable; level 2 logs *everything* and is for short, targeted captures only because it adds overhead and fills the capped collection fast); (2) query `system.profile` (or grep the log for `Slow query` lines) and sort by `millis`; (3) for each offender inspect `planSummary` (a `COLLSCAN` is the prime suspect) and the ratio of `docsExamined`/`keysExamined` to `nreturned` — examining 500,000 documents to return 12 is a missing or wrong index; (4) take the offending query shape, run `explain("executionStats")` on it to confirm the plan, then add an ESR-ordered index and re-profile to verify `docsExamined ≈ nreturned`.

The senior practices and cautions: the profiler's overhead and its capped `system.profile` collection mean you **enable it surgically** (during an incident or a load test) rather than leaving level 2 on permanently; in managed environments (Atlas) the **Query Profiler / Performance Advisor** surfaces the same data with index suggestions, which is the modern first stop. The key discipline mirrors the slow-query playbook elsewhere in this guide: *measure before you index* — the profiler tells you which queries are slow and how often they run (a query that's 200 ms but runs 10,000×/sec matters far more than a 5-second report that runs nightly), so you prioritize by total time impact (`millis × frequency`), not by single-execution latency alone.

#### Q73. [Theory] How do retryable writes and retryable reads work, and why does that require idempotency on the server side?

**Retryable writes** (default-on since the 3.6+ era) let the driver automatically retry a write that failed due to a *transient* network error or a primary step-down/election, so a brief failover doesn't surface as an error your application code has to handle. The subtle problem this solves: if a write reaches the server, the server applies it, but the *acknowledgment* is lost on the network, a blind retry would apply the write **twice** (e.g., a double `$inc`, a duplicate insert). MongoDB prevents this by attaching a unique **transaction number (`txnNumber`) per session** to each retryable write; the server records the result of the first attempt keyed by `(lsid, txnNumber)`, so a retry of the *same* logical write is recognized and the stored result is returned instead of re-executing — giving **exactly-once** application of that single write despite the retry.

```java
// retryWrites=true is the default; this is what it protects against:
MongoClient client = MongoClients.create(
    "mongodb+srv://host/?retryWrites=true&retryReads=true");

// A single-document write that survives a primary election transparently:
accounts.updateOne(eq("_id","a1"), inc("balance", -100));
// If the primary steps down mid-write, the driver retries ONCE against the new
// primary; the server's txnNumber bookkeeping ensures it isn't applied twice.
```

The constraints and "why idempotency" are the depth here. Retryable writes only cover operations the server can make exactly-once safe: **single-statement writes** (`insertOne`, `updateOne`, `deleteOne`, `findOneAndUpdate`, and `bulkWrite` *without* multi-document updates) — they explicitly **do not** retry `updateMany`/`deleteMany`, because a multi-document update isn't a single atomic point the server can dedupe by `txnNumber` (a partial application followed by a retry could re-apply some documents). They also retry only **once** and only for errors labeled retryable. **Retryable reads** are simpler — reads are naturally idempotent, so the driver just re-issues the query on a transient error — but they can return *different* data on the retry if the underlying data changed, which is fine for reads but is why the same mechanism can't be naively applied to arbitrary writes.

The practical implications a senior engineer states: keep `retryWrites=true` (it makes rolling restarts, failovers, and `rs.stepDown()` invisible to clients, dramatically improving availability), but understand it is **per-operation** exactly-once, not a substitute for application-level idempotency across *multiple* operations — for a multi-step business action you still design idempotency keys (the outbox/dedupe pattern elsewhere in this guide). And know the gap: bulk multi-doc updates and your own multi-operation workflows are *not* covered, so anything that must be exactly-once across several writes needs either a transaction or an application-level idempotency token. The elegant insight is that MongoDB pushed exactly-once-for-one-write down into the protocol via per-session transaction numbers, turning the classic "did my write actually happen during the failover?" ambiguity into a solved problem at the driver level.

### 🟠 Advanced — extended

#### Q74. [Coding] Use `$graphLookup` to traverse a hierarchy (org chart / category tree) and explain its cost model.

**Problem:** Given a self-referential collection (employees pointing at a `managerId`, or categories pointing at a `parentId`), retrieve an entire subtree or ancestor chain in one query. A naive solution issues one query per level (N+1 round-trips of unknown depth); `$graphLookup` (3.4+) performs the **recursive traversal server-side** in a single aggregation stage.

```java
import com.mongodb.client.model.*;
import com.mongodb.client.model.GraphLookupOptions;
import java.util.List;
import org.bson.Document;

employees.aggregate(List.of(
  Aggregates.match(Filters.eq("_id", "ceo")),
  Aggregates.graphLookup(
      "employees",                 // from: same collection (recursive)
      "$_id",                       // startWith: value to begin from
      "managerId",                  // connectFromField: follow this...
      "_id",                        // connectToField: ...to match this
      "reports",                    // as: output array of reachable nodes
      new GraphLookupOptions()
          .maxDepth(5)              // bound recursion depth
          .depthField("level")),    // tag each result with its distance
  Aggregates.project(Projections.fields(
      Projections.include("name"),
      Projections.computed("teamSize", new Document("$size", "$reports"))))
)).into(new java.util.ArrayList<>());
```

The semantics: `$graphLookup` starts from `startWith`, then repeatedly takes the `connectFromField` of each found document and looks for documents whose `connectToField` matches, accumulating all reachable nodes into the `as` array. Swap `connectFromField`/`connectToField` direction to walk *up* (ancestors) instead of *down* (descendants). `maxDepth` bounds the recursion (essential — an unbounded or cyclic graph would otherwise run away, though `$graphLookup` does detect and avoid revisiting nodes), and `depthField` records each node's distance from the start, letting you reconstruct the tree structure.

The **cost model** is what separates a real answer from a demo. Each traversal step is effectively a lookup against `connectToField`, so that field **must be indexed** or every level degrades into a collection scan — on a deep, wide hierarchy without the index, `$graphLookup` is catastrophic. The whole result set is materialized into the `as` array in memory, subject to the 100 MB limit (use `allowDiskUse(true)` and `maxDepth` to bound it). The senior trade-off discussion: `$graphLookup` is excellent for *occasional* hierarchy queries and bounded depths, but for hierarchies read on every request, the **materialized-path** pattern (store each node's full ancestor path as an array, e.g., `path: ["root","a","b"]`, indexed) or the **nested-set** model lets you fetch a subtree with a single indexed range/array query and no recursion at all — trading write-time path maintenance for far cheaper reads. Choosing `$graphLookup` vs. materialized paths is the classic recursive-data design decision: recursion is flexible but per-query expensive; precomputed paths are cheap to read but must be maintained on moves.

#### Q75. [Theory] What is the `$expr` operator, and how does it enable comparing fields within the same document (and its index implications)?

**`$expr`** lets you use **aggregation-expression syntax inside a regular query filter** (in `find`, `$match`, `update` conditions, `$lookup` sub-pipelines, and schema validators), which unlocks something the standard query language cannot do: compare **two fields of the same document** to each other. A plain query filter compares a field to a *constant* (`{price: {$gt: 100}}`), but it has no way to express "documents where `spent` exceeds `budget`" because both operands are fields — `$expr` with `$gt: ["$spent", "$budget"]` does exactly that.

```javascript
// Field-to-field comparison: impossible without $expr
db.accounts.find({ $expr: { $gt: ["$spent", "$budget"] } });   // overspent accounts

// Conditional logic in a filter
db.orders.find({ $expr: {
  $gt: [ { $multiply: ["$qty", "$price"] }, "$discountThreshold" ] } });

// $expr in a $lookup sub-pipeline correlates parent/child fields (see Q54)
```

The mechanics and the indexing caveat, which is the real depth: `$expr` evaluates an aggregation expression per document, returning the documents where it's truthy. Historically `$expr` **could not use indexes at all**, forcing a collection scan — a notorious performance trap where people sprinkled `$expr` everywhere and wondered why every query was a `COLLSCAN`. Modern MongoDB (3.6+ and progressively improved) **can** use an index for `$expr` *only in restricted cases* — specifically when `$expr` contains a simple equality (`$eq`) on a single indexed field against a constant — but the moment you do field-to-field comparison, arithmetic, or `$gt`/`$lt` ranges, the index cannot be used because the comparison value isn't known until each document is examined. So `{$expr:{$eq:["$status","ACTIVE"]}}` *might* use an index, but `{$expr:{$gt:["$spent","$budget"]}}` cannot.

The senior guidance: use `$expr` deliberately for the things only it can do — field-to-field comparisons, computed-value predicates, and correlating fields in `$lookup` sub-pipelines and validators — but **never** use it as a substitute for ordinary indexed predicates (write `{status:"ACTIVE"}`, not `{$expr:{$eq:["$status","ACTIVE"]}}`). When you genuinely need a field-to-field filter on a hot path and it's scanning too much, the design fix is to **precompute the comparison at write time** — store a boolean `overspent` (maintained on each balance update) and index *that*, converting an unindexable `$expr` into a cheap indexed equality. This is the same computed-pattern theme that recurs throughout MongoDB design: move work to write time so reads stay index-backed.

#### Q76. [Practical] How do you force or verify index usage with `hint()`, and when is overriding the query planner justified?

`hint()` instructs MongoDB to use a **specific index** for a query, overriding the planner's empirical choice. By default you should *trust* the cost-based plan competition described in the plan-cache question — the planner is right the vast majority of the time, and hinting is a sharp tool that, used wrongly, locks in a worse plan and prevents the planner from adapting as data distribution shifts. But there are legitimate cases where you know something the planner's sample didn't capture, and `hint()` is the correct intervention.

```javascript
// Force a named index (preferred: refer by name, robust to spec changes)
db.orders.find({ status: "ACTIVE", region: "EU" }).hint("status_1_region_1");

// Or by key pattern
db.orders.find({ status: "ACTIVE" }).hint({ status: 1, region: 1 });

// Verify the hinted plan actually does less work
db.orders.find({ status: "ACTIVE", region: "EU" })
  .hint("status_1_region_1").explain("executionStats");

// Force a full scan to MEASURE the no-index baseline (diagnostic only)
db.orders.find({ status: "ACTIVE" }).hint({ $natural: 1 });
```

```java
users.find(and(eq("status","ACTIVE"), eq("region","EU")))
     .hint("status_1_region_1");   // driver: pass index name or key document
```

The justified cases: (1) **parameter-sniffing-style misselection** — the plan cache learned a plan from an atypical first query value that's bad for the common case (e.g., a query that's usually highly selective on `region` but the first run had a value matching half the collection), and a hint pins the correct index until the cache is fixed; (2) **regression after data growth** — a plan that was fine when a field was selective degrades as distribution changes, and a hint is a stopgap while you fix the underlying index or statistics; (3) **forcing a baseline measurement** — `hint({$natural:1})` deliberately forces a `COLLSCAN` so you can compare timings and prove the index helps; (4) **ensuring a partial/sparse index is used** when the planner is conservatively avoiding it.

The discipline a senior engineer brings: hinting is **treating a symptom**, so it should come with a ticket to fix the root cause (add the right index, clear/pin the plan cache with `planCacheSetFilter`, or reshape the query), and you must **`explain` the hinted query to confirm it actually does less work** — a hint that forces a worse index is a self-inflicted incident. Prefer **index filters (`planCacheSetFilter`)** over scattering `hint()` through application code when you want a *durable* planner override, because index filters live in the database and apply to a query shape centrally rather than being embedded in every call site. The honest interview answer is: hinting is a rare, deliberate override backed by `explain` evidence, not a routine performance tool — if you're hinting everywhere, your indexes or query shapes are wrong.

#### Q77. [Coding] Implement geospatial proximity and containment queries with a `2dsphere` index.

**Problem:** "Find the 10 nearest open restaurants within 2 km of the user, and list all delivery zones that contain a given point." This needs a **`2dsphere`** index (spherical-Earth geometry over GeoJSON) plus the `$near`/`$geoWithin`/`$geoIntersects` operators — MongoDB's native geospatial support, no PostGIS required.

```java
import com.mongodb.client.model.*;
import com.mongodb.client.model.geojson.*;
import java.util.List;
import org.bson.Document;

// 1) Index the GeoJSON Point field
restaurants.createIndex(Indexes.geo2dsphere("location"));

// 2) PROXIMITY: nearest restaurants within 2 km, sorted by distance ascending
Point user = new Point(new Position(-97.7431, 30.2672));    // [lng, lat] order!
List<Document> nearby = restaurants.find(
    Filters.and(
        Filters.near("location", user, 2000.0, 0.0),         // maxMeters=2000, min=0
        Filters.eq("open", true)))
    .limit(10)
    .into(new java.util.ArrayList<>());

// 3) CONTAINMENT: which delivery polygons contain this point?
List<Position> ring = List.of(
    new Position(-97.75,30.26), new Position(-97.73,30.26),
    new Position(-97.73,30.28), new Position(-97.75,30.28),
    new Position(-97.75,30.26));                              // closed ring
List<Document> zones = zonesColl.find(
    Filters.geoIntersects("area", new Point(user.getPosition())))
    .into(new java.util.ArrayList<>());
```

```javascript
// GeoJSON storage shape (note: coordinates are [longitude, latitude])
db.restaurants.insertOne({
  name: "Cafe", open: true,
  location: { type: "Point", coordinates: [-97.7431, 30.2672] } });

// Aggregation equivalent of $near with computed distance + extra filtering:
db.restaurants.aggregate([
  { $geoNear: {
      near: { type: "Point", coordinates: [-97.7431, 30.2672] },
      distanceField: "metersAway", maxDistance: 2000,
      query: { open: true }, spherical: true } },   // $geoNear MUST be the first stage
  { $limit: 10 } ]);
```

The essentials and gotchas: **GeoJSON uses `[longitude, latitude]` order**, not lat/lng — reversing them is the single most common geospatial bug and silently returns wrong results (or points in the ocean). `$near`/`$nearSphere` return results **sorted nearest-first** and *require* the `2dsphere` index; `$geoWithin`/`$geoIntersects` test containment/overlap against polygons and do **not** sort by distance. In an aggregation, **`$geoNear` must be the very first stage** (it both filters and computes the `distanceField`), and it accepts an embedded `query` to combine proximity with attribute filters (here `open:true`) efficiently in one indexed pass.

The senior considerations: `2dsphere` models the Earth as a sphere (good for real-world lat/lng) versus the legacy `2d` index for flat Euclidean planes (game maps, floor plans); proximity queries can't be sharded the same way as range queries, so for planet-scale geo at high volume you often combine geohashing or grid-cell bucketing with the shard key; and combining a `$near` with a non-selective attribute filter still has to walk geo-sorted results, so a selective compound geo+attribute design matters under load. For most "stores near me" features, a `2dsphere` index plus `$geoNear`/`$geoWithin` is the complete, performant answer — and knowing the `[lng, lat]` ordering and the "`$geoNear` must be first" rule are the markers of someone who's actually shipped geospatial MongoDB.

#### Q78. [Theory] Explain wildcard indexes and the Attribute Pattern — how do you index documents with unpredictable or sparse field names?

Some workloads have documents where the *field names themselves* are data — a product catalog where each category has dozens of category-specific attributes (`screenSize`, `lensMount`, `caffeineMg`), or a flexible metadata bag where keys are user-defined. You can't pre-create a compound index for every possible attribute combination, and indexing nothing means every attribute filter is a collection scan. **Wildcard indexes** (4.2+) and the **Attribute Pattern** are the two complementary answers.

A **wildcard index** (`{"$**": 1}` or scoped to a subtree like `{"attributes.$**": 1}`) automatically indexes **every field (and nested field) it covers**, including fields that don't exist yet, so an ad-hoc query on any attribute under `attributes.*` can use the index. The **Attribute Pattern** is a *schema* technique that often pairs better with a normal index: instead of `{screenSize:"6in", refreshRate:"120Hz"}` (unbounded distinct field names), you reshape variable attributes into an **array of key-value sub-documents** `{specs: [{k:"screenSize", v:"6in"}, {k:"refreshRate", v:"120Hz"}]}`, then a single compound index on `{"specs.k":1, "specs.v":1}` indexes *all* attributes uniformly.

```javascript
// Wildcard index: indexes every field under "attributes" automatically
db.products.createIndex({ "attributes.$**": 1 });
db.products.find({ "attributes.lensMount": "EF" });   // uses the wildcard index

// Attribute Pattern + one normal compound index covers ALL k/v attributes
db.products.insertOne({ name:"Cam",
  specs:[ {k:"lensMount", v:"EF"}, {k:"weightG", v:520} ] });
db.products.createIndex({ "specs.k": 1, "specs.v": 1 });
db.products.find({ specs: { $elemMatch: { k:"lensMount", v:"EF" } } });
```

The trade-offs that decide between them: **wildcard indexes** are zero-schema-change and great for genuinely unpredictable, ad-hoc query patterns, but they're larger (they index everything in scope), they **cannot** be compound across a wildcard and a regular field in arbitrary ways, they can't enforce a meaningful sort across heterogeneous types, and a query that filters on two different wildcard fields can't use the index for both simultaneously. The **Attribute Pattern** requires reshaping your documents and queries (`$elemMatch` on `{k,v}`), but yields a single, compact, *predictable* index that supports equality on any attribute and even compound `(k,v)` lookups, and it scales to thousands of distinct attribute names without index explosion.

The senior framing: reach for the **Attribute Pattern** when the set of possible attributes is large but each query is "find docs where attribute X = value Y" (it's the more controlled, more performant long-term design), and reserve **wildcard indexes** for truly exploratory or multi-tenant-custom-field scenarios where you can't reshape the data and queries can hit arbitrary paths. Both beat the two anti-patterns: creating dozens of single-field indexes (index bloat, slow writes) or accepting collection scans on attribute filters. This is fundamentally a "the keys are data" modeling problem, and recognizing it — then choosing reshape-and-index vs. wildcard-index by query predictability — is the advanced-level signal.

### 🔴 Expert — extended

#### Q79. [Theory] How does Queryable Encryption work, and how does it differ from CSFLE and at-rest encryption?

MongoDB offers three distinct encryption tiers that protect against *different threat models*, and conflating them is a common security mistake. **Encryption at rest** (WiredTiger native encryption or disk-level) protects data on stolen disks/backups — but the server processes plaintext in memory and the DBA/server sees everything, so it does nothing against a compromised server or a malicious insider. **Client-Side Field-Level Encryption (CSFLE)** (4.2+) encrypts specific fields *in the driver* before they ever leave the application, using keys the server never holds, so the server stores and returns ciphertext it cannot read — protecting against a breached server, a compromised DBA, and snapshots. **Queryable Encryption (QE)** (6.0 GA, 7.0+ expanded) goes further: it lets the server **run queries (equality, and range in 8.0) directly on encrypted fields without ever decrypting them**, using specialized structured-encryption indexes — closing CSFLE's biggest limitation.

```java
// Queryable Encryption: declare encrypted fields + queryable type at collection setup
import com.mongodb.client.model.*;
import org.bson.Document;

Document encryptedFields = new Document("fields", java.util.List.of(
    new Document("path", "ssn")
        .append("bsonType", "string")
        .append("queries", new Document("queryType", "equality")),   // searchable!
    new Document("path", "salary")
        .append("bsonType", "int")
        .append("queries", new Document("queryType", "range"))));     // 8.0 range queries

// With an AutoEncryptionSettings-configured client, this query runs server-side
// against ENCRYPTED data; the server never sees plaintext "123-45-6789":
patients.find(Filters.eq("ssn", "123-45-6789"));
```

The cryptographic distinction is the depth: CSFLE supports only **deterministic** encryption for equality search (same plaintext → same ciphertext, which leaks equality/frequency and enables correlation attacks) or **random** encryption (secure but *not* searchable at all). Queryable Encryption uses **structured encryption** — it builds encrypted metadata/index structures so the server can evaluate `$eq` (and `$gt`/`$lt` ranges in 8.0) over ciphertext **without** the deterministic-encryption frequency leakage, giving randomized-strength confidentiality *and* server-side queryability. The keys live in a **Key Management System** (AWS KMS, Azure Key Vault, GCP KMS, or KMIP) wrapping per-field Data Encryption Keys, so even a full database compromise plus memory dump on the server yields no plaintext.

The trade-offs a staff engineer must surface: QE has **storage and performance overhead** (encrypted indexes are larger and queries are slower than plaintext-indexed equivalents), supports a **restricted set of query types** (equality, and range — not arbitrary regex, text search, or aggregation expressions on encrypted fields), requires **driver-side configuration and KMS integration** (operational complexity and a hard dependency on key availability), and the encrypted fields **cannot be shard keys or used in `$lookup`** joins. The decision logic: use **at-rest encryption always** (cheap baseline against stolen media); add **Queryable Encryption** for the most sensitive PII/PHI (SSNs, medical records, financial identifiers) that must remain searchable while being unreadable to the server, the DBA, and anyone who breaches the database — the regulatory-grade option for HIPAA/PCI workloads where "the database operator cannot see this data" is a hard requirement. The expert insight is that QE moves the trust boundary entirely to the client+KMS, which is the strongest practical confidentiality MongoDB offers, paid for in query-type restrictions and overhead.

#### Q80. [Practical] What is Feature Compatibility Version (FCV), and how do you safely perform a major-version upgrade of a production cluster?

**Feature Compatibility Version (FCV)** is the mechanism that decouples *running a new binary* from *enabling its new on-disk/wire features*. When you upgrade `mongod` binaries from, say, 7.0 to 8.0, the FCV stays at `"7.0"` until you explicitly set it to `"8.0"` — so the new binary behaves compatibly with the old feature set, and persistent format changes or backwards-incompatible features are **not** activated. This is the linchpin of safe upgrades: it lets you run the new code and *still roll back to the old binary*, because nothing irreversible has been written. Once you bump FCV, some features write data the old binary can't read, and downgrade may require additional steps.

```javascript
// Check current FCV before doing anything
db.adminCommand({ getParameter: 1, featureCompatibilityVersion: 1 });

// AFTER all nodes run the new binary AND have baked in, enable new features:
db.adminCommand({ setFeatureCompatibilityVersion: "8.0", confirm: true });
```

The safe upgrade procedure for a replica set (the rolling-upgrade discipline): (1) **read the release notes** for the *specific* version path — MongoDB requires upgrading **one major version at a time** (you can't jump 6.0 → 8.0 directly; go 6.0 → 7.0 → 8.0), and each hop has its own compatibility notes; (2) **back up** and verify the restore; (3) confirm the current deployment is at the **prior version's FCV** so it's eligible; (4) **upgrade binaries in a rolling fashion** — secondaries first, one at a time (stop, replace binary, restart, wait for it to rejoin as `SECONDARY` and catch up), then `rs.stepDown()` the primary and upgrade it last, so there's always a primary and a majority; (5) **let the new binaries run at the old FCV for a soak period** (hours to days) under real traffic to gain confidence the upgrade is healthy and rollback is still trivial; (6) **only then `setFeatureCompatibilityVersion`** to the new version to unlock new features — knowing this is the point of no easy return.

For a **sharded cluster** the order is stricter and is a frequent interview detail: upgrade the **config server replica set first**, then the **shards** (each shard's replica set rolling-upgraded as above), then the **`mongos`** routers last, and finally bump FCV cluster-wide; **disable the balancer** during the upgrade so chunk migrations don't run against mixed-version members. The senior judgment points: never bump FCV until the binary upgrade has proven stable (it's your rollback insurance), always go one major version at a time, test the exact upgrade path in a staging clone of production first, ensure **driver compatibility** with the new server version (an old driver may not speak the new wire protocol features), and have the downgrade runbook written *before* you start. The whole design philosophy of FCV — "new code, old behavior, until you opt in" — exists precisely so a major upgrade is a reversible, low-risk, rolling operation rather than a big-bang cutover.

#### Q81. [Coding] Implement zone (tag-aware) sharding to pin data to specific regions for data-residency compliance.

**Problem:** A GDPR-style requirement says EU customers' data must physically reside on EU-located shards and US customers' on US shards, while everything stays in one logical sharded collection. **Zone sharding** (tag-aware sharding) maps **shard-key ranges to named zones**, and assigns shards to zones, so the balancer only ever places a given range on a shard in its zone — turning data residency into a declarative balancer constraint.

```javascript
// 1) Shard the collection on a key whose LEADING field carries the region
sh.shardCollection("app.customers", { region: 1, customerId: 1 });

// 2) Define zones and assign physical shards to them
sh.addShardToZone("shardEU1", "EU");
sh.addShardToZone("shardEU2", "EU");
sh.addShardToZone("shardUS1", "US");
sh.addShardToZone("shardUS2", "US");

// 3) Map shard-key RANGES to zones (MinKey/MaxKey bound the per-region range)
sh.updateZoneKeyRange("app.customers",
  { region: "EU", customerId: MinKey }, { region: "EU", customerId: MaxKey }, "EU");
sh.updateZoneKeyRange("app.customers",
  { region: "US", customerId: MinKey }, { region: "US", customerId: MaxKey }, "US");

// Now EU customer docs can ONLY live on EU shards; the balancer enforces it.
```

```java
// Java/driver-side: the shard key must be present on every insert so mongos
// routes it into the correct zone's range.
customers.insertOne(new Document("region", "EU")
    .append("customerId", "eu_8842")
    .append("name", "Lena")
    .append("email", "lena@example.eu"));
```

How it works and the design implications: the shard key's **leading field (`region`) determines the zone**, so the balancer, when it splits and migrates chunks, consults the zone-range map and refuses to place an `EU`-range chunk on a non-`EU` shard. This gives **physical data residency** within a single collection and `mongos` namespace, plus a performance bonus — queries that include `region` are **targeted** to that region's shards rather than scatter-gathered. The same mechanism serves **tiered storage** (pin "hot" recent data to NVMe shards, "cold" archival ranges to cheaper disk) and **geo-locality for latency** (route each region's traffic to nearby shards).

The expert trade-offs and pitfalls: (1) the shard key **must lead with the zoning field**, and that field must be present on every write or routing/residency breaks — you can't retrofit zoning onto a key that doesn't expose the region; (2) zones constrain the balancer, so an imbalanced customer distribution (one huge region) can leave zones unevenly loaded — you can't freely rebalance across the residency boundary, which is the *point* but also a capacity-planning constraint; (3) moving a customer between regions means moving them across the shard-key range, which (since shard keys were historically immutable) requires a delete-and-reinsert or `reshardCollection`-class operation — model region as rarely-changing; (4) cross-region operations (`$lookup`, transactions spanning EU+US) become cross-shard and lose the locality benefit, so design aggregate boundaries within a region. The senior summary: zone sharding is the *correct, supported* answer to "data must stay in region X," far better than running separate clusters per region (which fragments your operational surface), but it commits you to a region-leading shard key and the balancing constraints that residency necessarily imposes.

#### Q82. [Theory] How do snapshot isolation and the WiredTiger MVCC history store prevent dirty/non-repeatable reads, and what are the costs?

MongoDB transactions and `readConcern: snapshot` provide **snapshot isolation**: every read inside the transaction (or snapshot read) sees a single, consistent view of the data **as of a specific cluster timestamp**, regardless of writes committed by *other* transactions after that point. This means **no dirty reads** (you never see another transaction's uncommitted writes), **no non-repeatable reads** (re-reading the same document in the transaction returns the same value), and a stable view for the transaction's lifetime — the same guarantee PostgreSQL's `REPEATABLE READ` provides. The mechanism underneath is WiredTiger's **MVCC (multi-version concurrency control)**: writes don't overwrite in place; they create new versions tagged with the timestamp at which they become visible, and a reader at snapshot time `T` simply reads the version visible as of `T`.

To serve a read "as of timestamp T" when newer versions exist, WiredTiger keeps **older versions of records available** — historically in memory and, since 4.4, in a persistent on-disk **history store** (`WiredTigerHS.wt`) that holds prior versions evicted from cache. A snapshot/transactional read reconstructs the value visible at its read timestamp by consulting the current version and walking back through the history store as needed. This is also what powers point-in-time reads, the **stable timestamp** used for replication, and majority-committed reads.

```javascript
// Snapshot read concern: this read sees a consistent point-in-time view; the
// totals in step 1 and step 2 are mutually consistent even under concurrent writes.
session.startTransaction({ readConcern: { level: "snapshot" },
                           writeConcern: { w: "majority" } });
//   read A, read B  -> both observe the SAME snapshot, no torn/dirty reads
session.commitTransaction();
```

The costs and failure modes a senior engineer must articulate: (1) maintaining old versions consumes **cache and disk** — a long-running transaction or snapshot read **pins** the history (WiredTiger can't reclaim versions newer than the oldest active read timestamp), so a 10-minute analytical snapshot read forces the engine to retain every version created in that window, **bloating the history store and cache** and degrading the whole node; this is why long-running transactions are discouraged and capped (60s default) and why the oldest-active-read timestamp is a critical health metric. (2) Snapshot isolation is **not serializable** — it permits **write skew** (two transactions each read a consistent snapshot, then write based on it in a way that violates an invariant neither saw the other break); MongoDB's defense is that *write* conflicts on the same document abort one transaction with a retryable `WriteConflict`, but disjoint-document write skew is not prevented, so application invariants spanning multiple documents need careful design (or a guard document both transactions touch to force a conflict). (3) The history store adds **write amplification** and eviction pressure under heavy update-and-snapshot-read workloads.

The expert takeaway: snapshot isolation via MVCC + history store gives clean, consistent reads without read locks (readers never block writers and vice versa), which is a major scalability win — but it shifts the cost to **version retention**, so the operational rules follow directly: keep transactions short, avoid long snapshot reads on hot collections, monitor cache and history-store size and the oldest-active-transaction timestamp, and remember that snapshot isolation tolerates write skew, so multi-document invariants need an explicit conflict point rather than relying on the isolation level to be serializable.

#### Q83. [Practical] You discover orphaned documents and inconsistent counts after interrupted chunk migrations. Diagnose and remediate.

**Orphaned documents** are documents that physically exist on a shard but whose shard-key range, according to the config servers' authoritative chunk map, belongs to a *different* shard — leftovers from chunk migrations that didn't fully clean up (an interrupted migration, a crash during the delete phase, or older versions with weaker cleanup guarantees). They're a real operational issue because they (1) **inflate storage** on the wrong shard, (2) can cause **inaccurate results** for operations that read directly per-shard or for legacy metadata-based `count()` (which is exactly the count-discrepancy symptom), and (3) waste I/O. Modern MongoDB (4.4+) added **range deletions** tracked durably and the `rangeDeleterService` so migrations clean up reliably, but interrupted migrations and upgrades from older versions still leave orphans behind.

```javascript
// Diagnose: compare per-shard physical count vs. routed (orphan-filtered) count
// Routed count via mongos filters orphans correctly:
db.customers.countDocuments({});                  // accurate (mongos, orphan-aware)

// Per-shard direct count INCLUDES orphans — a large gap signals orphaning:
//   (connect directly to each shard's primary, NOT through mongos)
db.customers.find().count();                      // on shardA directly: may over-count

// Inspect pending range deletions (cleanup backlog) on a shard:
use config
db.rangeDeletions.find().pretty();                // queued orphan cleanups

// Check for chunks the balancer couldn't finish migrating
db.chunks.find({ ns: "app.customers" }, { shard:1, min:1, max:1 });
```

Diagnosis: the tell-tale sign is a discrepancy between an **orphan-aware count through `mongos`** (`countDocuments`, accurate) and a **direct-to-shard count** (includes orphans), or a growing `config.rangeDeletions` backlog. You confirm by connecting *directly* to each shard's primary (bypassing `mongos`) and comparing document counts in the shard's owned ranges against the chunk map.

Remediation, modern to legacy: (1) on **4.4+**, orphan cleanup is automatic via the range deleter — the fix is usually to **let it drain** (ensure the balancer and range-deleter are running and not blocked; a paused balancer or a stuck migration stalls cleanup) and address whatever interrupted migrations (disk full, a lagging recipient, network) so cleanup completes; (2) `cleanupOrphaned` historically was the admin command to delete orphans for a range on a shard, and `mergeChunks`/letting the balancer re-migrate also resolves range ownership; (3) for the **count inaccuracy** specifically, switch any code off legacy metadata `count()` onto **`countDocuments()`** (orphan-aware) per the count question elsewhere in this guide — that fixes the *symptom* immediately even before the orphans are physically cleaned. The senior framing: orphans are a *migration-cleanup* problem, not a data-integrity bug in your documents (the authoritative routed view is always correct); the durable fixes are keeping the balancer/range-deleter healthy, ensuring migrations don't get interrupted (adequate disk, healthy recipients), running a recent MongoDB version with reliable range deletion, and never reading **direct-to-shard** in application code (always go through `mongos`, which filters orphans). Prevention beats remediation: most chronic orphaning traces back to undersized shards (disk-full aborts) or a chronically stuck balancer, so fix the root cause rather than repeatedly running cleanup.

#### Q84. [Theory] Explain hedged reads, read distribution, and how `mongos` minimizes tail latency in a sharded cluster.

In a sharded cluster, a scatter-gather query (one that doesn't include the shard key) must contact *every* shard and wait for *all* of them — so the query's latency is the **slowest shard's** latency, not the average. This is the classic tail-latency-amplification problem: with 10 shards, even if each has a 1% chance of a slow response, a scatter-gather query has roughly a 1 − 0.99¹⁰ ≈ **10% chance** of hitting at least one slow shard, so p99 latency degrades sharply as shard count grows. **Hedged reads** (4.4+) are MongoDB's mitigation: `mongos` sends the read to **two members of each shard's replica set simultaneously** and uses **whichever responds first**, cancelling the slower one — trading a bit of extra read load for dramatically tighter tail latency.

```java
// Enable hedged reads via a read preference (non-primary modes support hedging)
import com.mongodb.*;
import com.mongodb.client.*;
import com.mongodb.connection.*;

ReadPreference hedged = ReadPreference.nearest(
    new ReadPreferenceHedgeOptions.Builder().enabled(true).build());

MongoCollection<Document> c = db.getCollection("orders").withReadPreference(hedged);
// Each per-shard read now races two replicas; first response wins.
```

The mechanism and where it helps: hedging works with non-primary read preferences (`nearest`, `secondary`, `secondaryPreferred`) because it needs ≥2 eligible members to race; `mongos` dispatches both, takes the first complete result, and abandons the laggard. It is most valuable for **latency-sensitive scatter-gather reads** where a single slow replica (GC pause, transient disk stall, a node mid-checkpoint) would otherwise dominate the response — exactly the workloads where p99 matters more than mean. The same family of `mongos` behaviors that reduce tail latency includes **targeted routing** (the bigger lever — including the shard key so only one shard is hit, eliminating scatter-gather entirely) and **`maxStalenessSeconds`/tag-aware** routing to avoid a known-lagging member.

The costs and the senior judgment: hedged reads **roughly double the read work** for hedged queries (two replicas each execute it), so on a CPU- or cache-bound cluster they can *worsen* aggregate throughput and even latency under saturation — they buy tail-latency improvement by spending spare capacity, and that capacity must exist. They also only help **reads** and don't fix the structural problem, which is scatter-gather itself; the durable answer to tail latency in sharding is **shard-key and query design that makes hot reads targeted to a single shard**, so you contact one replica set instead of all of them. The expert framing: hedged reads are a useful *tactical* tail-latency tool for unavoidable scatter-gather on a cluster with headroom, but the *strategic* fix is query-aligned shard keys (targeted routing) — and you reach for hedging only after confirming you have spare capacity and that the read pattern genuinely can't be made targeted. Knowing both the fan-out math (why p99 degrades with shard count) and that hedging spends capacity to buy tail latency is the distinguishing detail.

#### Q85. [Coding] Use `$unionWith`, `$densify`, and `$fill` to combine collections and produce a gap-free time series.

**Problem:** Build a single continuous hourly time series from two sources (live readings + an archive collection), with **no missing hours** even when a sensor reported nothing, and reasonable values filled into the gaps. This needs three modern aggregation stages: **`$unionWith`** (4.4+) to concatenate collections, **`$densify`** (5.1+) to *generate* the missing time buckets, and **`$fill`** (5.3+) to populate nulls via carry-forward or linear interpolation.

```java
import com.mongodb.client.model.*;
import com.mongodb.client.model.densify.*;
import com.mongodb.client.model.fill.*;
import java.util.List;
import org.bson.Document;

liveReadings.aggregate(List.of(
  // 1) UNION the archive collection into the same stream
  Aggregates.unionWith("archivedReadings",
      List.of(Aggregates.match(Filters.gte("ts", windowStart)))),

  Aggregates.match(Filters.eq("deviceId", "dev42")),
  Aggregates.sort(Sorts.ascending("ts")),

  // 2) DENSIFY: create a document for every missing 1-hour step in the range
  Aggregates.densify("ts",
      DensifyRange.fullRangeWithStep(1, MongoTimeUnit.HOUR)),

  // 3) FILL: carry the last known temp forward; linearly interpolate humidity
  Aggregates.fill(
      FillOptions.fillOptions().sortBy(Sorts.ascending("ts")),
      FillOutputField.value("temp", FillComputation.locf()),        // last-obs-carried-fwd
      FillOutputField.value("humidity", FillComputation.linear()))  // interpolate
)).into(new java.util.ArrayList<>());
```

What each stage contributes: **`$unionWith`** appends the results of a sub-pipeline over another collection to the current stream (MongoDB's `UNION ALL`), so you can blend hot + archived data, or combine heterogeneous sources, in one pipeline — note it's `UNION ALL` semantics (no dedup; add a `$group` if you need distinct). **`$densify`** *manufactures* documents to fill gaps in a numeric or date sequence — given a step of 1 hour over the full range, it inserts a placeholder document for every hour that has no data, so downstream stages see a regular grid instead of irregular, gappy timestamps. **`$fill`** then replaces nulls in those (and pre-existing) documents using either **`locf`** (last observation carried forward — correct for state-like values such as a thermostat setpoint) or **`linear`** interpolation (correct for continuous measurements like temperature trending between two known points).

The "why this matters" and trade-offs: before these stages, gap-filling a sparse sensor series meant exporting to pandas/Spark or hand-rolling a brutal self-join — now charting, anomaly detection, and moving-average windows (`$setWindowFields`) get a clean, regular series natively. The cautions a senior engineer raises: `$densify` can **explode document count** if the step is tiny over a huge range (an unbounded densify on seconds over a year generates ~31M docs per partition — bound the range and choose the coarsest acceptable step), all three are **blocking/streaming transformations** subject to the 100 MB limit (use `allowDiskUse`), and the **choice between `locf` and `linear` is a correctness decision**, not a style one — carrying-forward a temperature when you should interpolate (or vice versa) produces subtly wrong analytics. Pair these with **time-series collections** and `$setWindowFields` and you have a complete in-database time-series analytics stack; the expert signal is knowing these exist (so you don't ship data to an external engine for routine gap-filling) *and* knowing densify's combinatorial blow-up and the semantic difference between fill strategies.

#### Q86. [Theory] How does Atlas Search (`$search`) differ from MongoDB's native `$text` index, and when do you reach for each?

MongoDB has two fundamentally different full-text search facilities, and choosing wrong leads either to a crippled search experience or unnecessary operational complexity. The native **`$text` index** is a built-in B-tree-based text index (one per collection) offering basic tokenization, stemming, stop-word removal, and simple relevance scoring via `$text`/`$meta:"textScore"` — it lives inside the core database, needs no extra infrastructure, and is adequate for simple "does this field contain these words" matching. **Atlas Search** (`$search`) embeds **Apache Lucene** indexes alongside the data (managed by Atlas, kept in sync via change streams) and exposes the full power of a real search engine: relevance tuning (BM25), fuzzy matching, autocomplete, synonyms, faceting, phrase and wildcard queries, multi-language analyzers, highlighting, and **vector/semantic search** (`$vectorSearch`) for AI/embedding workloads.

```javascript
// Native $text: one text index per collection, basic relevance
db.articles.createIndex({ title: "text", body: "text" });
db.articles.find(
  { $text: { $search: "mongodb sharding" } },
  { score: { $meta: "textScore" } }
).sort({ score: { $meta: "textScore" } });

// Atlas Search ($search): Lucene-backed, fuzzy + autocomplete + relevance tuning
db.articles.aggregate([
  { $search: {
      index: "default",
      compound: {
        should: [
          { text: { query: "mongodb sharding", path: ["title","body"],
                    fuzzy: { maxEdits: 1 }, score: { boost: { value: 3 } } } },
          { autocomplete: { query: "mong", path: "title" } } ] } } },
  { $project: { title: 1, score: { $meta: "searchScore" } } },
  { $limit: 20 } ]);
```

The decision criteria: reach for **`$text`** only for the simplest cases — an internal tool, a quick keyword filter, a single-language exact-ish word match where you don't want to run Lucene and you're not on Atlas; it's "good enough" and zero extra infrastructure, but it's **limited to one text index per collection**, has crude scoring, no fuzzy/autocomplete/synonyms, and its relevance is not competitive with a real search engine. Reach for **Atlas Search** for any **user-facing search experience** — typo tolerance (fuzzy), search-as-you-type (autocomplete), faceted navigation, synonym expansion, relevance boosting, multi-field weighted ranking, and especially **semantic/vector search** for RAG and recommendation systems — because building those on `$text` is impossible and building them on a *separate* Elasticsearch cluster means running a second datastore and a sync pipeline you now own.

The architectural insight a staff engineer offers: Atlas Search's value proposition is **co-locating Lucene with your operational data and keeping it in sync automatically** (via the change-stream-fed mongot process), so you get Elasticsearch-class search *without* the operational burden and consistency headaches of a separate search cluster and a hand-built indexing pipeline. The trade-off is that `$search` is an **Atlas-only** feature (vendor coupling — it's not available in self-hosted Community/Enterprise the same way), it has its own index definitions and resource footprint, and the search index is **eventually consistent** with the collection (sync lag of typically seconds). So the honest framing: `$text` for trivial in-database keyword matching with no Atlas dependency; **Atlas Search** for any real search product (and the only sane native option for fuzzy/autocomplete/vector), accepting the Atlas coupling and eventual-consistency lag as the price of not operating a separate search stack. Recognizing that "we need search" almost always means Atlas Search or a dedicated engine — *not* `$text` — and being able to justify the co-location trade-off is the senior-level answer.

#### Q87. [Behavioral] Tell me about a time you led the resolution of a severe MongoDB production incident and what you changed afterward to prevent recurrence.

This question probes **incident leadership under pressure, structured diagnosis, and — most importantly — the systemic follow-through** that distinguishes senior engineers from people who merely firefight. Interviewers want evidence you can stay calm, drive a methodical investigation rather than thrash, communicate to stakeholders, and convert a painful outage into durable prevention. Use **STAR** and make the prevention work as prominent as the heroics.

A strong answer: "During a product launch, our primary's write latency spiked from 8 ms to 4+ seconds and the API started timing out — a Sev-1 with revenue impact. **(Situation)** As the on-call lead I first **declared the incident and assigned roles** (a comms owner for stakeholders, a scribe for the timeline) so I could focus on diagnosis instead of fielding questions. **(Task)** My job was to restore service fast *without* a blind change that could make it worse, then find the true root cause. **(Action)** I worked the evidence systematically rather than guessing: `mongostat` showed the WiredTiger cache pinned at 100% with rising application-thread evictions and write tickets exhausted (`available: 0`), while `db.currentOp()` revealed a flood of a single new query doing a `COLLSCAN`. Correlating the spike's start time with our deploy dashboard, I found a feature shipped 20 minutes earlier had introduced a query on an un-indexed field, which under launch traffic was scanning the whole collection, blowing the working set out of cache, and starving every other operation of tickets. I made the smallest reversible move first — **feature-flagged off the new query path** to stop the bleeding (latency recovered within minutes) — rather than reflexively adding an index under load, then built the correct ESR index on a secondary via a **rolling index build** and re-enabled the feature behind the flag once verified. **(Result)** We restored service in ~15 minutes and fully resolved within the hour with zero data loss. In the **post-incident review** (blameless) I drove three systemic changes: (1) a **pre-merge check that flags queries lacking a supporting index** by running `explain` in CI against a representative dataset, so an un-indexed hot query can't ship again; (2) **alerting on the leading indicators** I'd had to find manually — WiredTiger cache-eviction rate and ticket availability — so we'd be paged *before* user-facing latency degraded; (3) a **feature-flag-by-default policy** for new query paths so any future offender can be disabled instantly. I also wrote and rehearsed a 'MongoDB latency spike' runbook codifying the `mongostat`/`currentOp`/deploy-correlation sequence."

The signals this demonstrates: **incident command** (roles, comms, blameless review), **evidence-driven diagnosis** (correlate-then-confirm with specific metrics, not cargo-cult index-adding), **reversible-smallest-change-first** judgment (flag off before rebuilding indexes under load, rolling build to avoid a second outage), and — the part that separates staff-level answers — **turning one incident into permanent prevention** at three layers: catching the class of bug earlier (CI explain checks), detecting the failure mode sooner (leading-indicator alerts), and limiting blast radius structurally (feature flags + runbook). A weaker answer stops at "I added the missing index and it was fixed"; the strong version shows the prevention work and the leadership, which is what the question is really testing. The throughline — *diagnose with evidence, mitigate reversibly, then fix the system so it can't recur* — is the durable lesson.

#### Q88. [Practical] How do you tune and monitor the WiredTiger cache, eviction, and dirty-page behavior under a write-heavy workload?

The WiredTiger cache is the single most important performance resource in MongoDB, and under write-heavy load its **eviction** and **dirty-page** dynamics are where latency problems originate. The cache (default `max(50% of (RAM − 1GB), 256MB)`) holds clean (unmodified, disk-backed) and **dirty** (modified-but-not-yet-checkpointed) pages. Writes dirty pages; checkpoints and eviction flush them to disk. WiredTiger maintains thresholds: it triggers **background eviction** as the cache fills (default eviction target ~80% used, ~5% dirty), and if writes outpace eviction so the cache crosses hard thresholds (~95% used / ~20% dirty), it forces **application threads themselves to perform eviction** — the dreaded state where your write operations stall doing the engine's cleanup work, which is the proximate cause of most write-latency cliffs.

```javascript
// The metrics that matter, from serverStatus().wiredTiger.cache
db.serverStatus().wiredTiger.cache
// Watch:
//  "bytes currently in the cache"          vs "maximum bytes configured"   -> fill %
//  "tracked dirty bytes in the cache"                                       -> dirty %
//  "pages evicted by application threads"   (rising = BAD: writers stalling)
//  "eviction worker thread evicting pages"  (background eviction, healthy)
//  "pages read into cache" / "pages written from cache"  -> I/O pressure

// Set cache size explicitly (containers often mis-detect host RAM!)
// mongod.conf:
//   storage.wiredTiger.engineConfig.cacheSizeGB: 8
```

The monitoring discipline: the leading indicator of trouble is **"pages evicted by application threads" climbing** — it means background eviction can't keep up and writes are now blocking on eviction; pair that with **dirty bytes approaching the ~20% hard threshold** and **rising disk write I/O**. A healthy write-heavy node keeps eviction handled by the dedicated eviction worker threads, dirty% comfortably under the threshold, and the working set resident so reads don't compete for cache by faulting pages from disk. `mongostat`'s `dirty` and `used` cache columns give the same picture at a glance for live triage.

The tuning levers and their trade-offs, in priority order: (1) **size the cache correctly** — the classic production bug is running in a **container/cgroup** where WiredTiger mis-detects the *host's* RAM and sets a cache far larger than the container's memory limit, leading to OOM-kills or thrash; always set `cacheSizeGB` explicitly to fit the container, leaving headroom for connections, aggregation, and the OS page cache. (2) **Fix the workload before fiddling with internal thresholds** — the right response to eviction stalls is usually *reduce dirty-page generation and cache pressure*: ensure the working set fits (add RAM or shard), remove unnecessary indexes (every index multiplies write/dirty work), batch writes, and avoid giant in-place document rewrites (unbounded arrays). (3) **Faster storage** (NVMe) raises the rate at which eviction/checkpoints can flush dirty pages, directly widening the write-throughput ceiling. (4) Tuning eviction trigger/target thresholds or adding eviction worker threads is an **expert last resort** — like raising concurrency tickets, loosening thresholds usually masks a capacity problem and can make thrash worse. The senior framing: under write-heavy load you're managing a **producer/consumer balance** — writes produce dirty pages, eviction + checkpoint consume them — and latency cliffs happen when production outpaces consumption; the durable fixes are bigger/faster consumers (RAM, NVMe, sharding) and fewer producers (fewer indexes, smaller working set, batched writes), with internal-threshold tuning reserved for cases where you've proven the hardware and schema are already right.

## 📚 Further Reading

- *MongoDB: The Definitive Guide, 3rd Edition* — Bradshaw, Brazil, Chodorow (O'Reilly) — the canonical reference.
- *Designing Data-Intensive Applications* — Martin Kleppmann (O'Reilly) — replication, partitioning, consistency, and transactions from first principles; essential context for the advanced/expert sections.
- [MongoDB Official Documentation](https://www.mongodb.com/docs/) — current through MongoDB 8.x; authoritative on aggregation, sharding, and transactions.
- [MongoDB Data Modeling Patterns / Building with Patterns](https://www.mongodb.com/blog/post/building-with-patterns-a-summary) — bucket, computed, outlier, subset, and other schema design patterns.
- [MongoDB Java Driver Documentation](https://www.mongodb.com/docs/drivers/java/sync/current/) — `mongodb-driver-sync` API and Spring Data MongoDB integration.
- [MongoDB University](https://learn.mongodb.com/) — free hands-on courses including the Data Modeling and Sharding learning paths.
