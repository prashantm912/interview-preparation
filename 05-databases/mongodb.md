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

## ✅ Key Takeaways

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

## 📚 Further Reading

- *MongoDB: The Definitive Guide, 3rd Edition* — Bradshaw, Brazil, Chodorow (O'Reilly) — the canonical reference.
- *Designing Data-Intensive Applications* — Martin Kleppmann (O'Reilly) — replication, partitioning, consistency, and transactions from first principles; essential context for the advanced/expert sections.
- [MongoDB Official Documentation](https://www.mongodb.com/docs/) — current through MongoDB 8.x; authoritative on aggregation, sharding, and transactions.
- [MongoDB Data Modeling Patterns / Building with Patterns](https://www.mongodb.com/blog/post/building-with-patterns-a-summary) — bucket, computed, outlier, subset, and other schema design patterns.
- [MongoDB Java Driver Documentation](https://www.mongodb.com/docs/drivers/java/sync/current/) — `mongodb-driver-sync` API and Spring Data MongoDB integration.
- [MongoDB University](https://learn.mongodb.com/) — free hands-on courses including the Data Modeling and Sharding learning paths.
