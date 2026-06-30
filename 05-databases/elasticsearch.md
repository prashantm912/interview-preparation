# Elasticsearch & Search

[← Back to master index](../README.md)

Elasticsearch is a distributed, near-real-time search and analytics engine built on top of Apache Lucene. It stores data as JSON documents in inverted indexes, scales horizontally through shards and replicas, and powers full-text search, structured filtering, aggregations, and log analytics (the "E" in the ELK/Elastic Stack). This guide covers Elasticsearch from fundamentals through expert-level distributed internals, with JSON Query DSL and Java client examples, current through 2026 (Elasticsearch 8.x/9.x).

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

### Q1. [Theory] What is Elasticsearch and what problem does it solve?

Elasticsearch is a distributed search and analytics engine that indexes JSON documents and makes them searchable in **near real time**. It is built on **Apache Lucene** — Lucene provides the core indexing and search data structures (the inverted index, scoring, segment files), and Elasticsearch wraps Lucene with distribution (sharding, replication, a cluster coordination layer), a REST/JSON API, and a rich query language called the **Query DSL**.

The problem it solves is **fast full-text and ad-hoc search over large volumes of semi-structured data**. A relational database can do `LIKE '%term%'`, but that cannot use an index and degrades to a full table scan, and it has no notion of relevance ranking, stemming, fuzzy matching, or aggregating millions of documents in milliseconds. Elasticsearch is purpose-built for those workloads: product search, log/observability analytics, autocomplete, geo search, and increasingly vector/semantic search.

```
   JSON docs  ──▶  Analysis  ──▶  Inverted Index (Lucene segments)
                                         │
   Query DSL  ──────────────────────────┘──▶  ranked hits + aggregations
```

### Q2. [Theory] What is an inverted index and why is it central to Elasticsearch?

An **inverted index** is the core data structure that makes full-text search fast. Instead of mapping documents to the words they contain (a "forward" index), it inverts that relationship: it maps each **term** to the **list of documents** (a postings list) that contain it, often with positions and frequencies.

For example, indexing three documents:

```
doc1: "the quick brown fox"
doc2: "the lazy brown dog"
doc3: "quick brown rabbits"

Inverted index (term → postings):
  brown  → [doc1, doc2, doc3]
  quick  → [doc1, doc3]
  fox    → [doc1]
  lazy   → [doc2]
  dog    → [doc2]
```

To answer "find documents containing *quick* and *brown*", Elasticsearch intersects the two postings lists (`[doc1,doc3] ∩ [doc1,doc2,doc3] = [doc1,doc3]`) — an O(matching-docs) operation, not a scan of every document. The terms are kept in a sorted dictionary (a finite-state transducer / FST), so term lookup is fast and supports prefix and range operations. This structure is what gives search engines sub-millisecond term lookups over billions of documents.

### Q3. [Theory] What are the basic units: cluster, node, index, document, and (historically) type?

- **Cluster** — a set of one or more nodes that share the same `cluster.name` and hold the entire data set together.
- **Node** — a single running instance of Elasticsearch (a JVM process). Nodes have roles (master-eligible, data, ingest, coordinating, ML, etc.).
- **Index** — a logical namespace for a collection of documents that share roughly the same structure; the analog of a "table" or a "database", depending on how you model it. Physically an index is split into shards.
- **Document** — the basic unit of information, a JSON object with an `_id`. The analog of a "row".
- **Field** — a key-value pair inside a document; its type is governed by the mapping.
- **Type** — a legacy concept (multiple "types" per index). **Types were removed**; an index now holds a single implicit type. Modern modeling uses one index per entity, or a custom discriminator field plus `join` fields when needed.

```
Cluster
 ├── Node A ── shard 0 (primary), shard 1 (replica)
 └── Node B ── shard 1 (primary), shard 0 (replica)
        Index "products" = shards 0 + 1
           Document {_id, name, price, ...}
```

### Q4. [Practical] How do you index and retrieve a document with the REST API?

You interact with Elasticsearch over HTTP/JSON. To index (create or replace) a document with a known id, `PUT /index/_doc/id`; to let Elasticsearch assign an id, `POST /index/_doc`.

```json
PUT /products/_doc/1
{
  "name": "Wireless Headphones",
  "brand": "Acme",
  "price": 79.99,
  "tags": ["audio", "bluetooth"],
  "in_stock": true
}
```

Retrieve it by id:

```json
GET /products/_doc/1
```

Search it:

```json
GET /products/_search
{
  "query": { "match": { "name": "wireless headphones" } }
}
```

The response wraps results in a `hits` object: `hits.total.value` (count), and `hits.hits[]` where each hit has `_id`, `_score` (relevance), and `_source` (the original JSON). The raw stored JSON lives in the `_source` field, which is what you get back by default.

### Q5. [Theory] What is a mapping and what are the main field types?

A **mapping** is the schema for an index: it defines each field's data type and how it is indexed/analyzed. Elasticsearch can infer a mapping automatically (**dynamic mapping**) the first time it sees a field, but for production you usually define an **explicit mapping** to control types, analysis, and to avoid mapping mistakes (e.g., a date being detected as text).

Common field types:

- **`text`** — analyzed full-text; tokenized into terms for search. Not good for sorting/aggregating.
- **`keyword`** — exact-value strings (not analyzed); used for filtering, sorting, aggregating (e.g., status, tags, IDs).
- **Numeric** — `long`, `integer`, `short`, `byte`, `double`, `float`, `scaled_float`, `half_float`.
- **`date`** — stored internally as epoch millis; accepts configurable formats.
- **`boolean`**.
- **`object` / `nested`** — for embedded JSON; `nested` preserves array-element relationships.
- **`geo_point` / `geo_shape`** — geospatial.
- **`ip`** — IPv4/IPv6.
- **`dense_vector`** — for kNN / semantic vector search.

A very common pattern is a **multi-field**: index a string as both `text` (for search) and `keyword` (for sort/agg), using the `.keyword` sub-field.

```json
PUT /products
{
  "mappings": {
    "properties": {
      "name":   { "type": "text",
                  "fields": { "keyword": { "type": "keyword", "ignore_above": 256 } } },
      "brand":  { "type": "keyword" },
      "price":  { "type": "scaled_float", "scaling_factor": 100 },
      "created":{ "type": "date" }
    }
  }
}
```

### Q6. [Theory] What is the difference between a `text` field and a `keyword` field?

This is the single most important modeling distinction in Elasticsearch.

- A **`text`** field is **analyzed**: at index time it is run through an analyzer that lowercases, tokenizes, and may stem the input, producing multiple terms in the inverted index. `"Wireless Headphones"` becomes the terms `wireless` and `headphones`. You search it with **full-text** queries like `match`, and partial/word matches work. But because it's broken into many terms, you cannot reliably sort or aggregate on it.

- A **`keyword`** field is **not analyzed**: the entire string is stored as a single term, verbatim. `"Wireless Headphones"` is the one term `Wireless Headphones`. You search it with **exact-match** queries like `term`, and it supports sorting, aggregations, and `terms` filters.

```
text  "Wireless Headphones" → ["wireless", "headphones"]   (match query)
keyword "Wireless Headphones" → ["Wireless Headphones"]    (term query, sort, agg)
```

Rule of thumb: use `text` for human-readable content you search by words (titles, descriptions, bodies); use `keyword` for identifiers, enums, tags, statuses, and anything you filter/sort/aggregate exactly.

### Q7. [Theory] What is an analyzer, and what are its three stages (character filters, tokenizer, token filters)?

An **analyzer** is the pipeline that converts raw text into indexed terms. It runs both at **index time** (to build the inverted index) and at **query time** (to analyze the search string the same way, so they match). An analyzer has three ordered stages:

1. **Character filters** — preprocess the raw character stream before tokenization (e.g., strip HTML tags, map `&` → `and`).
2. **Tokenizer** — splits the stream into tokens. Exactly one tokenizer. The `standard` tokenizer splits on word boundaries; others include `whitespace`, `keyword` (no split), `ngram`, and `pattern`.
3. **Token filters** — transform the token stream: `lowercase`, `stop` (remove stop words), `stemmer` (reduce to root: "running" → "run"), `synonym`, `asciifolding` (café → cafe), `edge_ngram` (for autocomplete).

```
"<b>The Foxes</b>"
  ─ char filter (html_strip) ─▶ "The Foxes"
  ─ tokenizer (standard)     ─▶ ["The", "Foxes"]
  ─ token filters (lowercase, stop, stemmer)
                             ─▶ ["fox"]
```

The default `standard` analyzer = standard tokenizer + lowercase + (no stemming). Critically, the **same analyzer must apply at index and query time**, otherwise a query for "Foxes" wouldn't match the indexed term "fox".

### Q8. [Practical] How do you test what an analyzer produces?

Use the `_analyze` API. This is the fastest way to debug "why doesn't my search match?".

```json
POST /_analyze
{
  "analyzer": "standard",
  "text": "The Quick Brown-Foxes jumped!"
}
```

Output tokens: `the`, `quick`, `brown`, `foxes`, `jumped`. You can also test a custom analyzer defined on an index, or test components individually:

```json
POST /products/_analyze
{
  "field": "name",
  "text": "Wireless Headphones"
}
```

If the terms produced at index time don't match the terms produced from your query string, you've found the bug — usually a mismatched or missing analyzer, or querying a `keyword` field with a `match` expecting analysis.

### Q9. [Theory] What is the Query DSL and what is the difference between query context and filter context?

The **Query DSL** is Elasticsearch's JSON-based language for expressing searches. Every clause runs in one of two contexts:

- **Query context** — answers *"how well does this document match?"* It computes a `_score` (relevance). Used for full-text relevance ranking. More expensive.
- **Filter context** — answers a yes/no *"does this document match?"* No score is computed, results are **cacheable** (the filter/node query cache), and it is faster. Used for exact constraints: status = active, price in range, date after X.

In a `bool` query, clauses under `must` and `should` run in **query context** (contribute to score), while clauses under `filter` and `must_not` run in **filter context** (no score, cached).

```json
{
  "query": {
    "bool": {
      "must":   [ { "match": { "title": "elasticsearch" } } ],   // scored
      "filter": [ { "term":  { "status": "published" } },        // not scored, cached
                  { "range": { "price": { "lte": 100 } } } ]
    }
  }
}
```

Performance rule: put anything that's a pure yes/no constraint in `filter`, and reserve scored clauses for actual relevance.

### Q10. [Practical] Explain the match, term, range, and multi_match queries with examples.

- **`match`** — full-text query; analyzes the input and searches a `text` field. Multiple words are OR-ed by default.

```json
{ "query": { "match": { "title": "open source search" } } }
```

- **`term`** — exact-value query; does **not** analyze. Use on `keyword`, numeric, boolean, date fields. A classic bug is running `term` on a `text` field — it won't match because the field's stored terms are lowercased/tokenized.

```json
{ "query": { "term": { "status": "active" } } }
```

- **`range`** — matches numeric/date/IP ranges with `gte`, `gt`, `lte`, `lt`.

```json
{ "query": { "range": { "price": { "gte": 10, "lte": 100 } } } }
{ "query": { "range": { "created": { "gte": "now-7d/d" } } } }
```

- **`multi_match`** — a `match` across multiple fields, with strategies like `best_fields` (default), `most_fields`, `cross_fields`, and `phrase`. Per-field boosts use `^`.

```json
{
  "query": {
    "multi_match": {
      "query": "wireless headphones",
      "fields": ["name^3", "brand", "description"],
      "type": "best_fields"
    }
  }
}
```

### Q11. [Practical] How does the `bool` query work (must, should, filter, must_not)?

The `bool` query is how you compose multiple conditions. It has four occurrence types:

- **`must`** — all clauses must match; contributes to score (AND, scored).
- **`filter`** — all clauses must match; **does not** contribute to score, cacheable (AND, not scored).
- **`should`** — optional; each matching clause **boosts** the score. If there are no `must`/`filter` clauses, at least one `should` must match (controlled by `minimum_should_match`).
- **`must_not`** — clauses must not match; filter context, not scored (NOT).

```json
{
  "query": {
    "bool": {
      "must":     [ { "match": { "description": "noise cancelling" } } ],
      "filter":   [ { "term":  { "in_stock": true } },
                    { "range": { "price": { "lte": 200 } } } ],
      "should":   [ { "term":  { "brand": "Acme" } } ],
      "must_not": [ { "term":  { "discontinued": true } } ],
      "minimum_should_match": 0
    }
  }
}
```

Mental model: `filter`/`must_not` narrow the candidate set cheaply; `must`/`should` rank what survives.

### Q12. [Theory] What is relevance scoring, and what is BM25?

**Relevance scoring** is how Elasticsearch ranks matching documents by how well they match a query, producing `_score`. The default similarity since Elasticsearch 5.0 is **BM25** (Best Match 25), an improvement over the classic **TF-IDF**.

The intuition is three factors:

- **TF (term frequency)** — the more times a term appears in a document, the more relevant. BM25 applies *saturation*: the 10th occurrence adds far less than the 2nd (controlled by `k1`), so keyword stuffing has diminishing returns. TF-IDF, by contrast, grows roughly linearly.
- **IDF (inverse document frequency)** — rare terms across the whole index are more discriminating than common ones ("the" carries little signal, "neuralink" carries a lot).
- **Field-length normalization** — a term match in a short field (a title) counts more than in a long field (a 5000-word body), controlled by `b`.

```
BM25 score ≈ Σ IDF(term) · ( tf · (k1+1) ) / ( tf + k1·(1 - b + b·(docLen/avgDocLen)) )
```

BM25's key advantages over TF-IDF are the TF saturation and better length normalization, which produce more intuitive rankings. Use the `_explain` API or `"explain": true` to see the exact per-term contribution to a score.

### Q13. [Theory] What are shards and replicas?

To scale horizontally, Elasticsearch splits an index into **shards**. Each shard is a self-contained Lucene index that holds a subset of the documents and can live on any node. This is how an index can exceed the size of a single machine and how searches run in parallel across nodes.

- **Primary shards** — the number is fixed at index creation (you cannot change it without reindexing/splitting/shrinking). A document is routed to a primary by `hash(_routing) % number_of_primaries` (default `_routing = _id`).
- **Replica shards** — copies of primaries, configurable and changeable at any time. They provide **high availability** (if a node dies, a replica is promoted) and **read throughput** (searches can hit replicas).

```
Index "logs", 2 primaries, 1 replica  →  4 shards total

Node A: P0, R1
Node B: P1, R0     (a replica never sits on the same node as its primary)
```

A primary and its replica are never placed on the same node, so losing one node never loses data. Indexing goes to the primary then is replicated; searches can be served by either primaries or replicas.

### Q14. [Theory] What does "near real-time" mean in Elasticsearch?

Elasticsearch is **near real-time (NRT)**, not strictly real-time: a document you index is typically searchable about **1 second** after indexing, not instantly. The reason is the **refresh** mechanism. When you index a document it goes into an in-memory buffer (and the translog for durability), but it is not searchable until a **refresh** flushes that buffer into a new in-memory Lucene **segment** and opens a fresh searcher over it. The default `index.refresh_interval` is `1s`.

```
index doc ──▶ in-memory buffer + translog
                 │  (every refresh_interval, default 1s)
                 ▼
            new searchable segment ──▶ now visible to search
```

If you need a document visible immediately for a test, you can call `POST /index/_refresh` or index with `?refresh=true`, but doing this on every write is an anti-pattern that destroys indexing throughput. For high-ingest log workloads you often *raise* the refresh interval (e.g., `30s`) to trade search latency for indexing speed.

### Q15. [Practical] How do you paginate results, and why is deep `from`/`size` pagination a problem?

The simple way is `from` (offset) + `size` (count):

```json
GET /products/_search
{ "from": 20, "size": 10, "query": { "match_all": {} } }
```

The problem is **deep pagination**: to return `from: 10000, size: 10`, every shard must produce its top `10010` hits, ship them to the coordinating node, which then sorts `(10010 × number_of_shards)` results and discards all but 10. Cost and memory grow with `from`, which is why Elasticsearch caps `from + size` at **10,000** by default (`index.max_result_window`).

For deep, ordered pagination use **`search_after`**, which is stateless and uses the sort values of the last hit as a cursor — there is no offset to accumulate, so it stays cheap at any depth (covered in detail in the Intermediate section). For exporting an entire data set, use the **Point-in-Time (PIT) + `search_after`** combination, or the scroll API for legacy cases.

### Q16. [Theory] What is the ELK / Elastic Stack?

The **Elastic Stack** (formerly ELK) is the set of products commonly used together for log and observability analytics:

- **E — Elasticsearch** — the storage/search/analytics engine.
- **L — Logstash** — a server-side data processing pipeline that ingests, transforms (parses, enriches, filters via Grok etc.), and ships data to Elasticsearch.
- **K — Kibana** — the web UI for searching, visualizing dashboards, and managing the cluster.
- **Beats** — lightweight shippers installed on edge hosts (Filebeat for logs, Metricbeat for metrics, etc.). The "B" that turned ELK into the broader Elastic Stack.
- **Elastic Agent / Fleet** — the modern, unified agent that increasingly replaces individual Beats.

```
[App logs] → Filebeat → (Logstash | Ingest pipeline) → Elasticsearch → Kibana
```

A typical flow: Beats collect data at the edge, optionally Logstash or Elasticsearch ingest pipelines transform it, Elasticsearch indexes it, and Kibana visualizes it. This stack is the dominant open-source logging/observability platform.

### Q17. [Theory] When should you use Elasticsearch versus a relational database?

They solve different problems and are frequently used **together**, not as substitutes.

| Need | Best fit |
|------|----------|
| Full-text relevance search, fuzzy/stemmed matching | Elasticsearch |
| Fast aggregations over huge data, log analytics | Elasticsearch |
| Autocomplete, geo search, faceted navigation | Elasticsearch |
| ACID transactions, strong consistency | RDBMS |
| Complex multi-table joins, foreign keys | RDBMS |
| System of record / source of truth | RDBMS |

Elasticsearch is **not** a primary transactional store: it offers no multi-document ACID transactions, no joins in the SQL sense (you denormalize instead), and it is eventually consistent for search (NRT). The standard architecture keeps the RDBMS as the source of truth and **indexes a denormalized projection** into Elasticsearch (via CDC, an outbox, or batch sync) to serve search and analytics.

### Q18. [Practical] How do you connect to Elasticsearch from Java?

Use the official **Java API Client** (`co.elastic.clients:elasticsearch-java`), which replaced the deprecated High-Level REST Client (removed in 8.x). It is built on the low-level REST transport and uses a fluent, typed builder API.

```java
RestClient restClient = RestClient
    .builder(new HttpHost("localhost", 9200, "https"))
    .setDefaultHeaders(new Header[]{
        new BasicHeader("Authorization", "ApiKey " + apiKey)
    })
    .build();

ElasticsearchTransport transport =
    new RestClientTransport(restClient, new JacksonJsonpMapper());
ElasticsearchClient client = new ElasticsearchClient(transport);

// Index a document
Product p = new Product("1", "Wireless Headphones", 79.99);
client.index(i -> i.index("products").id(p.id()).document(p));

// Search
SearchResponse<Product> resp = client.search(s -> s
        .index("products")
        .query(q -> q.match(m -> m.field("name").query("wireless"))),
    Product.class);

resp.hits().hits().forEach(h -> System.out.println(h.source()));
```

Create one `ElasticsearchClient` per application (it is thread-safe and pools connections). For Spring applications, **Spring Data Elasticsearch** offers repository abstractions on top of this client.

### Q19. [Theory] What is a document's `_source` and can you disable or trim it?

The **`_source`** field is the original JSON document you indexed, stored verbatim. It is what search returns by default and what enables **reindexing**, **update**, **highlighting**, and the **Update By Query** API to work without re-supplying data.

You can trim what's returned per request with source filtering:

```json
GET /products/_search
{ "_source": ["name", "price"], "query": { "match_all": {} } }
```

You *can* disable `_source` storage entirely (`"_source": {"enabled": false}`) to save disk, but it's usually a bad idea: you lose the ability to reindex, update, and see the full hit. Save space instead with `_source` includes/excludes, compression, or by not storing fields you can recompute.

### Q20. [Practical] How do you index many documents efficiently?

Never index documents one HTTP request at a time in a loop — use the **Bulk API**, which batches many operations into a single request with a newline-delimited (NDJSON) body.

```json
POST /_bulk
{ "index": { "_index": "products", "_id": "1" } }
{ "name": "Headphones", "price": 79.99 }
{ "index": { "_index": "products", "_id": "2" } }
{ "name": "Speaker", "price": 119.00 }
```

In Java, use the `BulkRequest` builder or the higher-level **BulkIngester** helper, which auto-flushes by count, size, or time:

```java
BulkRequest.Builder br = new BulkRequest.Builder();
for (Product p : products) {
    br.operations(op -> op.index(idx -> idx.index("products").id(p.id()).document(p)));
}
BulkResponse result = client.bulk(br.build());
if (result.errors()) { /* inspect per-item errors */ }
```

Tuning tips: pick a batch size by trial (often 1,000–5,000 docs or ~5–15 MB per bulk), parallelize with several client threads, raise `refresh_interval` and set replicas to 0 during a big initial load, and **always check `result.errors()`** because a bulk request can partially fail (HTTP 200 overall, individual items failed).

---

## 🟡 Intermediate (3–7 yrs)

### Q21. [Theory] How does a search request execute across shards (query-then-fetch)?

A distributed search runs in two phases, **query then fetch**:

1. **Query phase** — the coordinating node forwards the search to one copy (primary or replica) of every shard. Each shard executes the query locally, builds a priority queue of its top `from + size` document **ids and scores**, and returns just those (no `_source`). The coordinator merges all shard queues into a single globally sorted list of `size` results.
2. **Fetch phase** — the coordinator asks the relevant shards for the actual `_source`/fields of only the final documents it needs, then assembles the response.

```
client → coordinating node
            ├─▶ shard0: top-k ids+scores ─┐
            ├─▶ shard1: top-k ids+scores ─┤ merge → global top-k
            └─▶ shard2: top-k ids+scores ─┘
                       ↓ fetch _source for final k
                    response
```

This two-phase design avoids shipping full documents for every candidate. It also explains deep-pagination cost: each shard must produce `from + size` results regardless of how few you ultimately keep.

### Q22. [Theory] What are aggregations, and what is the difference between metric and bucket aggregations?

**Aggregations** compute analytics over the documents that match a query — counts, sums, averages, histograms, top values — in the same request. They're how Elasticsearch powers dashboards and faceted search. The two main families:

- **Metric aggregations** compute a numeric value over a set of documents: `avg`, `sum`, `min`, `max`, `stats`, `cardinality` (approx distinct count via HyperLogLog++), `percentiles`.
- **Bucket aggregations** group documents into buckets by some criterion, each bucket holding a document set you can sub-aggregate: `terms` (group by field value), `range`, `date_histogram`, `histogram`, `filters`, `nested`.

The power is **nesting**: put metric aggs inside buckets to get "average price per brand", or nest buckets for "monthly sales per category".

```json
GET /sales/_search
{
  "size": 0,
  "aggs": {
    "by_brand": {
      "terms": { "field": "brand", "size": 10 },
      "aggs": { "avg_price": { "avg": { "field": "price" } } }
    }
  }
}
```

`"size": 0` returns no hits, only aggregation results — efficient when you only need analytics.

### Q23. [Practical] Write a date_histogram aggregation with a nested metric.

A `date_histogram` buckets documents by a time interval; nest a metric to summarize each bucket. This is the canonical "time series" aggregation behind most Kibana charts.

```json
GET /orders/_search
{
  "size": 0,
  "query": { "range": { "created": { "gte": "now-30d/d" } } },
  "aggs": {
    "orders_per_day": {
      "date_histogram": {
        "field": "created",
        "calendar_interval": "day",
        "time_zone": "America/New_York"
      },
      "aggs": {
        "daily_revenue": { "sum": { "field": "amount" } }
      }
    }
  }
}
```

This returns one bucket per day, each with `doc_count` and `daily_revenue`. Use `calendar_interval` (day, week, month — DST/calendar aware) versus `fixed_interval` (exact durations like `90m`) deliberately; mixing them up is a common subtle bug around month/DST boundaries.

### Q24. [Theory] What is `search_after` and how does it solve deep pagination?

`search_after` is a **cursor-based** pagination method. Instead of an offset, you sort on a deterministic key (which **must include a tiebreaker like `_id` or `_shard_doc`** to be total) and pass the sort values of the last hit from the previous page to get the next page.

```json
// page 1
GET /products/_search
{ "size": 10, "sort": [ { "price": "asc" }, { "_id": "asc" } ],
  "query": { "match_all": {} } }

// page 2: feed the previous page's last sort values
GET /products/_search
{ "size": 10, "sort": [ { "price": "asc" }, { "_id": "asc" } ],
  "search_after": [ 79.99, "product-42" ],
  "query": { "match_all": {} } }
```

Because there's no offset, each shard only ever produces `size` hits past the cursor — cost stays constant at any depth, unlike `from`/`size`. The trade-offs: you can only go forward (no jump to page N), and for a **consistent snapshot** across pages despite ongoing indexing, combine it with a **Point-in-Time (PIT)** so all pages see the same set of segments.

### Q25. [Theory] Compare `search_after`, `from`/`size`, and the scroll API.

| Method | Use case | Random access | Snapshot | Cost at depth |
|--------|----------|---------------|----------|---------------|
| `from`/`size` | Shallow UI paging (first few pages) | Yes (jump to page N) | No | O(from) — capped at 10k |
| `search_after` | Deep, ordered, live pagination | No (forward only) | With PIT | Constant |
| Scroll | Legacy full export of a snapshot | No | Yes (frozen) | Constant, but holds resources |

- **`from`/`size`** — fine for "show page 1–5", breaks down past `max_result_window`.
- **`search_after` (+ PIT)** — the modern recommendation for deep pagination and exports. Stateless cursor, consistent with a PIT.
- **Scroll** — older approach that freezes a search context (a snapshot of segments) and pages through it; effective for one-off exports but holds shard resources open and is discouraged in favor of PIT + `search_after` for new code.

### Q26. [Theory] What are refresh, flush, and the translog?

These three mechanisms govern visibility and durability:

- **Translog (transaction log)** — an append-only, per-shard durability log. Every indexing operation is written to the translog and (by default) `fsync`-ed on each request (`index.translog.durability: request`). It is the recovery log: if a node crashes before changes are committed to disk segments, the translog is **replayed** on restart so no acknowledged write is lost.
- **Refresh** — moves in-memory buffered documents into a new searchable Lucene segment (initially in the OS filesystem cache), making them **visible to search**. Cheap and frequent (default every 1s). Refresh does **not** guarantee durability.
- **Flush** — a Lucene **commit**: it fsyncs the in-memory segments to disk durably and then **truncates the translog** (those operations are now safely persisted in segments). Triggered automatically by translog size/age; rarely invoked manually.

```
index → translog (durable, fsync per request)
      → buffer ──refresh(1s)──▶ searchable segment (in FS cache)
                              ──flush──▶ committed to disk, translog truncated
```

Mnemonic: **refresh = visibility**, **flush = durability**, **translog = the safety net between them**.

### Q27. [Theory] What are segments and why does segment merging happen?

A Lucene shard is physically a set of **immutable segments**. Each refresh creates a new small segment. Because segments are immutable, a "delete" only marks a document as deleted in a `.del`/liveDocs structure, and an "update" is a delete + reindex into a new segment. Over time you accumulate many small segments plus tombstones.

**Segment merging** is the background process that combines smaller segments into larger ones, during which deleted documents are physically purged. Benefits:

- Fewer segments means **faster searches** (each query touches every segment, so search cost scales with segment count).
- Reclaims space from deleted/updated docs.
- Keeps the file count manageable.

```
[s1][s2][s3][s4][s5]  ──merge──▶  [   S1   ][s5]
small segments + tombstones        larger segment, deletes purged
```

Merging is I/O- and CPU-intensive and is throttled to avoid starving indexing/search. For a read-only index you can run a one-time **force merge** down to a single segment (`_forcemerge?max_num_segments=1`) for optimal search speed — but never force-merge an index still being written to.

### Q28. [Practical] How do you update a mapping, and which changes require a reindex?

Mappings are mostly **append-only**. You can **add new fields** to an existing index, but you generally **cannot change the type of an existing field**, change an analyzer on an existing field, or remove a field — those require building a new index and **reindexing**.

Add a field:

```json
PUT /products/_mapping
{ "properties": { "rating": { "type": "float" } } }
```

To change a field's type/analyzer, create a new index with the corrected mapping and copy data with the Reindex API:

```json
POST /_reindex
{
  "source": { "index": "products_v1" },
  "dest":   { "index": "products_v2" }
}
```

The zero-downtime pattern is **index aliases**: applications read/write through an alias (`products`), you reindex into `products_v2`, then atomically swap the alias from `v1` to `v2`. Because of this, production indexes are usually created with versioned names behind an alias from day one.

### Q29. [Practical] How do you implement autocomplete / search-as-you-type?

There are three common approaches, in increasing order of sophistication:

1. **`edge_ngram` analyzer** — index prefixes of each term at index time so a query like "head" matches "headphones". Define a custom analyzer with an `edge_ngram` token filter; analyze at index time but use a plain analyzer at search time (so the query isn't itself ngrammed).

```json
PUT /products
{
  "settings": {
    "analysis": {
      "filter": { "edge": { "type": "edge_ngram", "min_gram": 2, "max_gram": 15 } },
      "analyzer": {
        "autocomplete": { "tokenizer": "standard", "filter": ["lowercase", "edge"] }
      }
    }
  },
  "mappings": {
    "properties": {
      "name": { "type": "text", "analyzer": "autocomplete", "search_analyzer": "standard" }
    }
  }
}
```

2. **`search_as_you_type` field type** — a built-in type that auto-creates the sub-fields needed for prefix and infix matching, queried with `multi_match` of type `bool_prefix`.
3. **Completion suggester** — an FST-backed, in-memory structure optimized for fast prefix suggestions; extremely fast but less flexible (no infix, separate indexing).

Choose `edge_ngram`/`search_as_you_type` for flexible full-text-ish completion, completion suggester for raw speed on a curated suggestion list.

### Q30. [Practical] How do you handle arrays of objects correctly with the `nested` type?

A plain `object` field with an array **flattens** the sub-fields, losing the association between values within a single array element. Given:

```json
{ "user": [ { "first": "Alice", "last": "Smith" },
            { "first": "Bob",   "last": "Jones" } ] }
```

With a default `object` mapping, Elasticsearch internally stores `user.first: [Alice, Bob]` and `user.last: [Smith, Jones]` as flat lists, so a query for `first=Alice AND last=Jones` **wrongly matches**. The `nested` type indexes each array element as a hidden separate Lucene document, preserving the relationship.

```json
PUT /people
{ "mappings": { "properties": { "user": { "type": "nested" } } } }

GET /people/_search
{
  "query": {
    "nested": {
      "path": "user",
      "query": {
        "bool": { "must": [
          { "match": { "user.first": "Alice" } },
          { "match": { "user.last":  "Jones" } }
        ] }
      }
    }
  }
}
```

The cost: nested docs bloat segment counts and queries must use the `nested` query/agg. Use it only when within-element correctness matters; otherwise prefer denormalization.

### Q31. [Theory] What is denormalization in Elasticsearch and why is it preferred over joins?

Elasticsearch has **no efficient cross-document joins** like SQL. To keep search fast and distributed, you **denormalize**: flatten related data into each document so a single document holds everything a query needs.

For example, instead of a `blog_posts` index and an `authors` index joined at query time, embed the author's name/bio directly into each post document. The data is duplicated, but every search hits one document with no join.

Trade-offs and the alternatives Elasticsearch *does* offer:

- **Denormalization** — fastest reads, but updates to shared data (an author renames) require updating many documents (often via Update By Query).
- **`nested`** — for arrays of objects where within-element relationships matter, same index, but heavier.
- **`join` (parent/child)** — true parent-child relationships within one index/shard; lets you update the child independently, but queries (`has_child`/`has_parent`) are slower and require co-location on the same shard.
- **Application-side join** — query twice and join in code; flexible but adds round-trips.

The default and idiomatic choice is **denormalization**: model around the query, accept duplication, and handle update fan-out explicitly.

### Q32. [Theory] How do you choose the number of shards for an index?

This is one of the most consequential and most-asked design questions. Guidelines current to recent versions:

- Target shard size roughly **10–50 GB** each (logs can go to ~50 GB; search-heavy indexes often smaller). Too-small shards waste overhead; too-large shards slow recovery/rebalancing.
- **Over-sharding is the common mistake**: each shard is a full Lucene index with fixed memory/file-handle overhead, and the cluster state tracks every shard. Thousands of tiny shards bloat the master and waste heap. A rule of thumb is **≤ ~20 shards per GB of JVM heap** per node.
- Primary count is **fixed at creation**; you can only `_split` (multiply) or `_shrink` (divide) into a new index, or reindex. So plan for growth.
- For **time-series/log data**, don't pick a huge static shard count — use **rolling indices** with ILM and the **rollover** API so each time slice is appropriately sized, and use data tiers.

A practical method: estimate total data size and growth, divide by target shard size, and validate by benchmarking on a single shard to find your "max comfortable shard size", then scale out.

### Q33. [Theory] What is routing and how does custom routing help (and hurt)?

**Routing** determines which shard a document lands on: `shard = hash(routing) % number_of_primary_shards`, with `routing` defaulting to `_id`. By supplying a **custom routing value** (e.g., `customer_id`), you can force all of a customer's documents onto the **same shard**.

The benefit: a search filtered to that customer can be sent to **one shard** instead of fanning out to all shards, dramatically cutting work for multi-tenant systems.

```json
PUT /orders/_doc/1?routing=customer-42 { ...}
GET /orders/_search?routing=customer-42 { "query": {...} }
```

The risk is **hotspotting / skew**: if one routing value (one big customer) has far more documents than others, that shard becomes oversized and a bottleneck. Custom routing is powerful for even, multi-tenant workloads but dangerous when key distribution is skewed; you sometimes mitigate with `index.routing_partition_size` to spread a routing value across several shards.

### Q34. [Practical] How do you debug a slow or wrong query?

A systematic toolkit:

1. **`_analyze`** — confirm index-time and query-time terms match (Q8). Most "no results" bugs are analysis mismatches.
2. **`"explain": true`** or **`_explain`** — see the per-clause score breakdown to understand ranking.
3. **`profile`** — `"profile": true` returns a detailed timing tree of how each query component executed per shard, exposing expensive clauses.

```json
GET /products/_search
{ "profile": true, "query": { "match": { "name": "headphones" } } }
```

4. **Slow log** — enable `index.search.slowlog.threshold.query.warn` to capture slow queries in production.
5. **`_validate/query?explain`** — check a query is well-formed and see the Lucene rewrite.
6. Check **filter cache** usage and whether yes/no clauses are in `filter` context, not `must`.

For ranking problems specifically, the answer is almost always `_explain`; for latency problems it's `profile` + slow log.

### Q35. [Practical] How do you keep Elasticsearch in sync with a primary database?

Elasticsearch is a secondary index, so you need a reliable pipeline from the system of record. Common patterns:

- **Change Data Capture (CDC)** — stream the database's commit log (e.g., Debezium reading MySQL binlog/Postgres WAL) into Kafka, then a consumer indexes into Elasticsearch. Near-real-time and decoupled; the gold standard.
- **Transactional outbox** — the app writes domain changes and an "outbox" row in the same DB transaction; a relay reads the outbox and indexes to Elasticsearch. Guarantees no lost events without dual-write races.
- **Dual write** — the app writes to DB and Elasticsearch directly. Simple but unsafe: a crash between the two writes leaves them inconsistent. Avoid for critical data.
- **Periodic batch / `_reindex` from a view** — a scheduled job rebuilds or syncs. Simple, but stale between runs.

Key concerns: **idempotency** (use the DB id as `_id` so replays overwrite, not duplicate), **ordering** (use document versioning/`version_type=external` so an older event can't overwrite a newer one), and **backfill** (the ability to rebuild the index from scratch).

### Q36. [Theory] What are index aliases and how do they enable zero-downtime reindexing?

An **alias** is a virtual pointer to one or more indices. Applications talk to the alias name; you repoint it behind the scenes without changing client code.

```json
POST /_aliases
{
  "actions": [
    { "remove": { "index": "products_v1", "alias": "products" } },
    { "add":    { "index": "products_v2", "alias": "products" } }
  ]
}
```

Because the swap is a **single atomic action**, clients never see a moment where `products` points to nothing. The zero-downtime reindex flow:

1. App reads/writes via alias `products` → `products_v1`.
2. Create `products_v2` with the new mapping; reindex `v1` → `v2`.
3. Catch up any writes that happened during reindex (e.g., by a timestamp filter or by writing to both).
4. Atomically swap the alias to `v2`, then delete `v1`.

Aliases also support **filtered aliases** (a built-in query, e.g., a per-tenant view) and **write aliases** (`is_write_index`) used by rollover.

### Q37. [Theory] What is multi-tenancy in Elasticsearch and what are the strategies?

Three common models, trading isolation against shard overhead:

1. **Index per tenant** — each tenant gets its own index. Strong isolation, easy per-tenant deletion/backup, but **explodes shard count** with many small tenants (the over-sharding trap). Good for few, large tenants.
2. **Shared index + filter (+ custom routing)** — all tenants in one index with a `tenant_id` field; every query filters by tenant, and custom routing on `tenant_id` localizes a tenant to one shard. Scales to many tenants; risk is **noisy neighbor** and a query that forgets the tenant filter leaking data. Filtered aliases per tenant reduce that risk.
3. **Index per tenant group / data stream** — hybrid: bucket tenants by size tier.

Most large SaaS systems use the **shared index + routing + filtered alias** approach for the long tail of small tenants and dedicated indices for whales.

### Q38. [Practical] How do you compute a faceted-search response (filters + counts) in one request?

Faceted/guided navigation needs both the filtered result set and the available facet counts. You combine a `bool` query with `terms`/`range` aggregations, and use **post_filter** or the `filters` agg so facet counts reflect the right scope.

```json
GET /products/_search
{
  "query": {
    "bool": { "filter": [ { "term": { "category": "audio" } } ] }
  },
  "aggs": {
    "brands": { "terms": { "field": "brand", "size": 20 } },
    "price_ranges": {
      "range": { "field": "price",
        "ranges": [ { "to": 50 }, { "from": 50, "to": 150 }, { "from": 150 } ] }
    }
  }
}
```

The subtlety: if a user selects a brand, you usually want the **brand facet to still show counts for unselected brands** (so they can switch). The standard trick is to move the selected-brand constraint into a **`post_filter`** (which applies to hits but not aggregations) or to compute each facet in a `filters` aggregation that excludes its own selection. This is the classic faceting interview detail.

### Q39. [Theory] What consistency guarantees does Elasticsearch provide for writes and reads?

Elasticsearch favors availability and search throughput over strict consistency:

- **Writes** go to the primary, are validated, then replicated to in-sync replicas; the write is acknowledged based on `wait_for_active_shards` (default: primary only must be active to *start*). The write returns once the required replicas ack. Each doc carries a `_seq_no`/`_primary_term` for **optimistic concurrency control** — you can do compare-and-set updates with `if_seq_no`/`if_primary_term` to avoid lost updates.
- **Reads/search** are **near-real-time and eventually consistent**: a just-indexed doc isn't searchable until refresh, and a search may hit a replica that hasn't yet caught up, so two searches can briefly disagree.
- **Get by id** is real-time (it can read from the translog), unlike search.

So: per-document writes are safe with versioning, but Elasticsearch is **not** a transactional store — there are no multi-document transactions and no read-your-writes guarantee for search without forcing a refresh. Model accordingly.

### Q40. [Practical] How do you delete or update documents matching a query?

Use **Update By Query** and **Delete By Query**, which run a search and apply the operation to every match in bulk (with conflict handling and throttling).

```json
POST /products/_update_by_query
{
  "script": { "source": "ctx._source.price *= 1.1", "lang": "painless" },
  "query":  { "term": { "brand": "Acme" } }
}

POST /products/_delete_by_query
{ "query": { "range": { "created": { "lt": "now-1y" } } } }
```

Important properties: these operate on a **snapshot** taken at start and use internal versioning, so concurrent changes can cause version conflicts (`"conflicts": "proceed"` to skip them). They are **not transactional** — a partial failure leaves partial changes. For large jobs, set `slices: "auto"` to parallelize, throttle with `requests_per_second`, and check the returned `updated`/`deleted`/`version_conflicts` counts. For purging time-series data, dropping/rolling whole indices via ILM is far cheaper than delete-by-query.

---

## 🟠 Advanced (8–12 yrs)

### Q41. [Theory] How does cluster coordination and master election work?

Elasticsearch separates **cluster state management** from data. **Master-eligible** nodes elect a single active master that owns the **cluster state** (index metadata, mappings, shard routing table, settings). Since 7.x, coordination uses a built-in consensus protocol based on a Raft-like algorithm with a **voting configuration** and **quorum** requirement.

- The cluster needs a **majority (quorum)** of master-eligible nodes to elect a master and commit state changes. With 3 master-eligible nodes, quorum is 2; this is why you run an **odd number** (typically 3) of master-eligible nodes to avoid split brain.
- `cluster.initial_master_nodes` bootstraps the very first election; afterward the voting configuration is managed automatically.
- The master publishes cluster-state updates to all nodes and waits for acknowledgment from a quorum before committing.

```
3 master-eligible nodes, quorum = 2
  [m1*][m2][m3]   ← m1 is elected master
  Lose m1 → m2/m3 (quorum 2) elect a new master, no split brain
  Lose 2 → no quorum → cluster blocks state changes (CP choice)
```

This design is a deliberate **CP** choice for the control plane: under partition, the minority side stops accepting state changes rather than risk divergence.

### Q42. [Theory] Walk through what happens to data plane when a data node fails.

When a data node leaves (crash or network partition):

1. The master detects the departure (failed pings / fault detection) and removes it from the cluster.
2. For every **primary** that was on the lost node, the master **promotes an in-sync replica** to primary on another node. Search and indexing for those shards resume quickly.
3. The cluster goes **yellow** (primaries assigned, some replicas missing). The master then **allocates new replica copies** by copying data to other nodes to restore the configured replica count, returning to **green**.
4. A brief delay (`index.unassigned.node_left.delayed_timeout`, default 1m) avoids reallocating replicas immediately in case the node returns quickly (e.g., a restart), which would waste a full shard copy.

```
green  ──node dies──▶  promote replicas → primaries (search OK)
       ──▶  yellow (replicas missing)
       ──▶  rebuild replicas on remaining nodes
       ──▶  green
```

Data loss only occurs if **both** a primary and all its replicas are lost simultaneously, which proper replica count and shard-allocation awareness (rack/zone) are designed to prevent.

### Q43. [Theory] What is Index Lifecycle Management (ILM) and the hot-warm-cold-frozen architecture?

**ILM** automates the lifecycle of time-series indices through **phases**, moving data across **data tiers** of progressively cheaper hardware as it ages:

- **Hot** — actively written and frequently queried; fastest hardware (NVMe), uses rollover to cap shard size.
- **Warm** — no longer written, still queried; can force-merge, reduce replicas, move to cheaper nodes.
- **Cold** — rarely queried; further cost reduction, often **searchable snapshots** (data lives in object storage, only partially cached).
- **Frozen** — almost never queried; fully backed by object storage via searchable snapshots, minimal local footprint, slower queries.
- **Delete** — removed after retention expires.

```
[Hot: write+query] → [Warm: query] → [Cold: rare] → [Frozen: archival] → [Delete]
   NVMe, replicas      cheaper disk    searchable      object storage
                                       snapshots
```

ILM works with **data streams** and the **rollover** action (roll to a new index when a shard hits a size/age/doc threshold), so each index stays optimally sized and old data ages out cheaply. This is the backbone of cost-effective log/observability storage.

### Q44. [Theory] What is a data stream and how does it differ from a regular index?

A **data stream** is an abstraction for append-only, time-series data (logs, metrics, traces). It presents a single name to clients but is backed by a hidden sequence of **auto-rolled backing indices** (`.ds-<name>-<date>-<gen>`).

- You **write** to the data stream name; writes always go to the **latest** backing index. Documents are append-only and must contain an `@timestamp` field.
- You **read/search** the data stream name; it fans out across all backing indices.
- **Rollover** (manual or via ILM) creates a new backing index when thresholds are hit; older backing indices age through ILM phases and are eventually deleted whole.

Versus a plain index: a data stream gives you automatic rollover, ILM integration, and cheap retention (drop whole backing indices) — ideal for time-series. Regular indices are for mutable, non-time-series data (a product catalog, a user table) where you update documents in place.

### Q45. [Theory] How does vector / semantic search work in Elasticsearch, and how does it compare to lexical (BM25) search?

Beyond lexical search, Elasticsearch supports **dense vector search (kNN)** for semantic/neural retrieval. You store an embedding (from a model) in a `dense_vector` field and query for nearest neighbors by cosine/dot-product/L2 distance, using an **HNSW** (Hierarchical Navigable Small World) graph index for approximate nearest neighbor search.

```json
PUT /docs
{ "mappings": { "properties": {
  "embedding": { "type": "dense_vector", "dims": 768, "index": true, "similarity": "cosine" }
}}}

GET /docs/_search
{
  "knn": { "field": "embedding", "query_vector": [/* 768 floats */],
           "k": 10, "num_candidates": 100 }
}
```

| | Lexical (BM25) | Vector (kNN) |
|--|----------------|--------------|
| Matches | Exact terms / morphology | Semantic meaning |
| "car" vs "automobile" | Misses (different terms) | Matches (close vectors) |
| Out-of-vocabulary / typos | Weak | Robust |
| Explainability | High (term scores) | Low |
| Cost | Cheap | Embedding + ANN index cost |

The strongest results come from **hybrid search**: run BM25 and kNN together and fuse the rankings, commonly with **Reciprocal Rank Fusion (RRF)** or learned weights, getting lexical precision plus semantic recall. Elastic also offers ELSER, a sparse learned model, as a middle ground.

### Q46. [Practical] How do you implement hybrid search combining BM25 and kNN?

Use the `retriever` framework with **RRF** to fuse a lexical retriever and a kNN retriever into one ranked list, without manually tuning score scales (BM25 and vector scores aren't directly comparable).

```json
GET /docs/_search
{
  "retriever": {
    "rrf": {
      "retrievers": [
        { "standard": { "query": { "match": { "content": "electric vehicle range" } } } },
        { "knn": { "field": "embedding", "query_vector": [/* ... */],
                   "k": 50, "num_candidates": 200 } }
      ],
      "rank_window_size": 100,
      "rank_constant": 60
    }
  }
}
```

RRF scores each document by `Σ 1/(rank_constant + rank_i)` across the retrievers, so a document ranked highly by either method floats up, and the two incompatible score scales never need normalizing. This is the standard modern recipe for combining keyword and semantic relevance.

### Q47. [Practical] How do you tune indexing throughput for a heavy ingest workload?

A layered checklist, ordered by impact:

1. **Bulk + parallelism** — large bulk requests (~5–15 MB) from multiple concurrent threads, sized to saturate but not overload nodes.
2. **Increase `refresh_interval`** (e.g., `30s` or `-1` during bulk load) so fewer tiny segments form; refresh visibility isn't needed mid-load.
3. **Drop replicas to 0 during initial load**, then restore — avoids replicating every write while bulk-loading, then builds replicas once.
4. **Let Elasticsearch auto-generate `_id`** when possible — supplying your own `_id` forces a "does this id already exist?" lookup; auto ids skip it (append-only).
5. **Right-size and avoid over-sharding** so merge pressure is balanced.
6. **Disable unneeded features** for the load: turn off `_source` only if you truly never reindex (rarely worth it), skip indexing fields you only store (`"index": false`), and avoid heavy analyzers where not needed.
7. **Use the OS filesystem cache** — give Elasticsearch ~50% of RAM as heap and leave the rest for the FS cache that holds hot segments; use fast SSDs.

Then benchmark with a tool like Rally and watch the **bulk rejection / thread-pool queue** metrics for backpressure.

### Q48. [Practical] How do you tune search latency for a read-heavy workload?

1. **Filter context + caching** — push yes/no constraints into `filter`; the node query cache memoizes them. Reuse the same filter shapes.
2. **Fewer, larger segments** — `force_merge` read-only indices; segment count directly drives per-shard query cost.
3. **Right shard count** — too many shards means too many parallel tasks and merge overhead; too few limits parallelism. Aim for shards that finish their slice quickly.
4. **Add replicas** to spread read load across more nodes/cores.
5. **Limit aggregation cardinality**, use `eager_global_ordinals` for high-cardinality `terms` aggs on `keyword` fields, and prefer `composite`/sampler aggs for huge cardinalities.
6. **Avoid expensive features in hot paths** — wildcard/leading-wildcard, regexp, deep `nested`, scripts in queries; precompute instead (e.g., index a derived field).
7. **Use `_source` filtering and `docvalue_fields`/`fields`** to fetch only what you render.
8. **Warm the FS cache** and ensure the working set fits in RAM — the single biggest factor.

Measure with `profile`, the search slow log, and node-level `indices.search` stats.

### Q49. [Theory] What is the role of doc values and field data, and why does sorting/aggregating on text fail?

Inverted indexes are great for *term → docs* but terrible for *doc → values* (which sorting and aggregating need). **Doc values** are a **columnar, on-disk** structure (in the OS cache) built at index time that maps documents to their field values — the read-optimized inverse of the inverted index. They power sorting, aggregations, and scripted access efficiently without heap pressure.

- `keyword`, numeric, date, boolean, geo fields have doc values **by default**.
- **`text` fields do not have doc values.** Sorting/aggregating on a `text` field instead requires loading **fielddata** into the **JVM heap** — expensive and OOM-prone — and is **disabled by default** (you'll get an error telling you to use the `.keyword` sub-field or enable `fielddata`).

```
inverted index:  term → [docs]        (search)
doc values:      doc  → value(s)      (sort / aggregate, columnar, off-heap)
fielddata:       doc  → value(s)      (text only, ON HEAP, avoid)
```

So the correct fix for "can't sort on field X" is almost always to sort/aggregate on the `keyword` multi-field, not to enable fielddata.

### Q50. [Theory] What is the trade-off space between indexing speed, search speed, and storage?

Most Elasticsearch tuning is choosing a point in a three-way tension:

```
        Indexing speed
            /\
           /  \
          /    \
  Search ──────── Storage/cost
```

Examples of the trade-offs:

- **Refresh interval ↑** → faster indexing, slower visibility.
- **Replicas ↑** → faster/safer search and HA, but more storage and slower indexing.
- **More indexed fields / sub-fields / ngrams** → richer/faster queries, but bigger index and slower indexing.
- **`_source` + doc values stored** → enables reindex/sort/agg, but more disk.
- **Force merge** → faster search, but a heavy one-time I/O cost; harmful on live indices.
- **`best_compression` codec** → smaller storage, slightly slower retrieval.
- **Vector index (HNSW)** → semantic recall, but extra memory and indexing cost.

The senior skill is reasoning explicitly about which axis the workload cares about (a log system optimizes ingest+storage; a product search optimizes query latency+relevance) and configuring accordingly rather than chasing one knob.

### Q51. [Behavioral] Describe a time you had to scale or stabilize an Elasticsearch cluster under pressure. How did you approach it?

A strong answer follows a structured incident narrative: **symptom → diagnosis → action → prevention.**

- **Symptom** — e.g., search latency spiking and bulk rejections during a traffic surge; cluster going yellow; JVM heap pressure with long GC pauses.
- **Diagnosis** — explain how you used data, not guesses: `_cat/nodes?v` for heap/CPU, `_nodes/stats` for thread-pool queues and rejections, hot threads API (`_nodes/hot_threads`) to find what the CPU was doing, and identifying that, say, **over-sharding** (10k tiny shards) was bloating the master and cluster state.
- **Action** — concrete fixes: consolidated indices via reindex/ILM rollover to right-size shards, moved yes/no clauses to filter context, added replicas/nodes for read capacity, raised refresh interval for the ingest path, and added circuit-breaker/queue monitoring.
- **Prevention** — capacity planning, shard-count governance, dashboards/alerts on heap, queue depth, and shard counts, and load testing with Rally before launches.

The interviewer is assessing whether you debug with metrics and understand cluster internals (shards, heap, thread pools), not whether you have a heroic story. Emphasize the measurement-driven loop and the durable safeguards you left behind.

### Q52. [Theory] How do circuit breakers protect the cluster, and what do common breaker trips mean?

**Circuit breakers** are guardrails that prevent operations from consuming so much memory that the JVM OOMs and the node dies. Each breaker tracks estimated memory for a category and rejects requests that would exceed its limit, throwing a `CircuitBreakingException` instead of crashing.

Key breakers:

- **Parent breaker** — overall limit (default ~95% of heap) across all child breakers.
- **Request breaker** — memory for a single request's data structures (e.g., large aggregations).
- **Fielddata breaker** — memory for loading `text` fielddata (a frequent culprit — see Q49).
- **In-flight requests breaker** — size of incoming request bodies.

A breaker trip is a **symptom**: a fielddata trip means you're aggregating/sorting on a `text` field (use `keyword`); a request-breaker trip on aggregations means cardinality is too high (use `composite` agg, reduce `size`, or pre-aggregate). Raising breaker limits is rarely the fix — it just trades a controlled rejection for an uncontrolled OOM. The real fix is changing the query/mapping that demands the memory.

### Q53. [Theory] How do you design relevance tuning beyond default BM25?

Production relevance is iterative engineering, not a single setting:

- **Field boosting** — weight important fields (`title^3`) in `multi_match`.
- **`function_score` / `script_score`** — blend signals like recency, popularity, rating, or geo distance into the score (e.g., decay functions: `gauss`/`exp` on date or location).
- **BM25 parameter tuning** — adjust `k1`/`b` per field for length-sensitive content.
- **Analyzers and synonyms** — stemming, synonym graphs, multilingual analyzers improve recall.
- **Hybrid / vector + RRF** — add semantic recall (Q45–46).
- **Learning to Rank (LTR)** — rerank the top-N with an ML model trained on judgment/click data (the `learning-to-rank` plugin / Elastic's rescore).
- **Rescore** — apply an expensive, more accurate scoring pass only to the top-N from a cheap first pass.

Crucially, drive it with **offline evaluation** (the Ranking Evaluation API with judgment lists, metrics like nDCG/MRR) and **online A/B tests with click data**, so changes are measured, not vibes. Senior relevance work is a measurement discipline.

---

## 🔴 Expert (15+ yrs)

### Q54. [Theory] Explain Elasticsearch's position in CAP/PACELC terms.

Elasticsearch makes **different consistency choices for its control plane and data plane**, which is the nuanced expert answer.

- **Control plane (cluster state, coordination)** is **CP**: master election and state changes require a **quorum** of master-eligible nodes (Raft-like). Under a partition, the minority side **stops accepting cluster-state changes** rather than diverge — it sacrifices availability for consistency. This is why losing quorum blocks the cluster.
- **Data plane (search and the document store)** leans **AP-ish / eventually consistent**: search is near-real-time, replicas can serve slightly stale results, and there are no cross-document transactions. Per-document writes do offer **optimistic concurrency** via `_seq_no`/`_primary_term`, and `wait_for_active_shards` lets you trade availability for write safety.

In **PACELC** terms: under a **P**artition, the coordination layer chooses **C** (consistency); **E**lse (normal operation) the data plane chooses **L**atency (NRT, replica reads) over strict consistency. So Elasticsearch is roughly **PC/EL** for the control plane and effectively favors latency/availability for reads. The headline: **it is not a transactional system of record** — design with that boundary in mind.

### Q55. [Theory] How would you design a multi-region / cross-cluster Elasticsearch architecture?

Two primary mechanisms, used together for global search and DR:

- **Cross-Cluster Search (CCS)** — a coordinating cluster issues searches that fan out to **remote clusters** and merges results, without copying data. Good for querying data that lives in different regions/clusters from one place. Latency depends on the slowest remote; you can mark clusters `skip_unavailable`.
- **Cross-Cluster Replication (CCR)** — asynchronously replicates indices from a **leader** cluster to **follower** clusters in other regions (follower indices are read-only, pulling changes from the leader's operations). Used for **disaster recovery**, **data locality** (serve reads near users), and centralizing data.

```
Region A (leader)  ──CCR async──▶  Region B (follower, read-only)
        ▲                                  │
        └──────── CCS query fan-out ◀───────┘  (one search, many clusters)
```

Design considerations: a single stretched cluster across high-latency regions is an **anti-pattern** (coordination needs low-latency quorum) — instead run **independent clusters per region** linked by CCR/CCS. Decide consistency tolerance (CCR is async, so followers lag), failover/promotion runbooks (promote a follower to leader during a region outage), and which writes are regional vs. global.

### Q56. [Theory] How do searchable snapshots and the frozen tier change the storage model?

Traditionally every searchable shard had a full local copy on fast disk. **Searchable snapshots** decouple compute from storage: an index's data lives in a **snapshot in object storage (S3/GCS/Azure)**, and Elasticsearch can search it **without a full local copy**, fetching and caching only the blocks a query touches.

- **Cold tier** uses **fully-mounted** searchable snapshots: a local copy exists for speed, but the snapshot is the source of truth, so you can **drop replicas** (the snapshot is the redundancy) — roughly halving local storage.
- **Frozen tier** uses **partially-mounted** searchable snapshots with a **shared local cache**: only recently-accessed blocks are cached locally, so a node can address **far more data than its disk** (e.g., petabytes of archival logs on modest local SSD). Queries are slower (object-store fetches on cache miss) but cost is a fraction.

This turns Elasticsearch into a **tiered system** where retention is bounded by cheap object storage, not local disk, fundamentally improving the cost curve for long-retention observability and security analytics. The trade-off is query latency and dependence on object-store availability for cold/frozen data.

### Q57. [Theory] What are the deep performance and correctness implications of segment-level concurrency and the "immutable segment" design?

The immutability of Lucene segments is the source of many of Elasticsearch's properties:

- **Lock-free reads** — because segments never change, a searcher can read them without locking, enabling high read concurrency; new data simply appears as new segments at refresh.
- **Point-in-time consistency** — a search executes against a fixed set of segments, so PIT and scroll can "freeze" a view by pinning segments (preventing their deletion during merges).
- **Write amplification** — updates and deletes don't mutate in place; an update is delete-tombstone + new doc, so heavily-updated indices accumulate tombstones and **merge debt**, inflating disk and CPU. A workload with high update churn (e.g., per-doc counters) is an anti-pattern; model it differently (append events, aggregate) or accept merge cost.
- **Deleted-doc overhead** — until a merge runs, deleted docs still occupy space and are scanned (then filtered), so query cost includes tombstones. Force-merging `expunge_deletes` reclaims them on read-only indices.
- **Refresh cost** — each refresh opens a new segment/searcher; over-frequent refresh on high write rates creates tiny segments and merge pressure (the reason to raise `refresh_interval` under heavy ingest).

The expert insight: many tuning decisions (refresh interval, force merge, avoiding update-heavy modeling, PIT semantics) are direct consequences of segment immutability and the merge lifecycle.

### Q58. [Practical] How would you architect search for a system that must scale to billions of documents with sub-second latency?

A reference architecture, reasoning from the constraints:

1. **Model for the query** — denormalize aggressively so each search hits one document; precompute derived/sortable fields at index time; choose `keyword` vs `text` deliberately; avoid `nested`/parent-child on hot paths.
2. **Shard strategy** — size shards (~20–40 GB), use **custom routing** for tenant/entity locality so most queries hit one or few shards, and **over-allocate primaries only as growth demands**, not preemptively.
3. **Time partitioning** — for time-series, use **data streams + ILM rollover** and **data tiers** (hot/warm/cold/frozen + searchable snapshots) so only hot data sits on expensive hardware while old data stays queryable cheaply.
4. **Read scaling** — replicas across nodes/zones for parallelism and HA; dedicated **coordinating-only nodes** to offload merge/aggregation from data nodes; cache filters.
5. **Relevance** — BM25 + boosting, optional hybrid/vector with RRF and rescore on top-N to keep cost bounded.
6. **Ingest** — CDC/outbox pipeline (Q35) with idempotent external versioning; bulk + backpressure; backfill/reindex via aliases.
7. **Operations** — separate dedicated master nodes (odd count), zone-aware allocation, circuit-breaker and queue monitoring, capacity tested with Rally.
8. **Cross-region** — independent regional clusters linked with CCR (DR/locality) + CCS (global queries), never one stretched cluster.

The throughline: keep the **working set in RAM/FS cache**, keep most queries **shard-local**, bound expensive work to **top-N**, and let **tiers + ILM** absorb scale cheaply.

### Q59. [Behavioral] How do you decide whether Elasticsearch is the right tool, and how do you push back when it's being misused?

Senior judgment is as much about saying *no* as configuring *yes*. A good answer covers:

- **Clarify the real requirement** — distinguish full-text/relevance/analytics needs (Elasticsearch shines) from transactional, strongly-consistent, join-heavy needs (RDBMS) or simple key-value lookups (a KV store/cache) or pure analytics on cold data (a data warehouse / OLAP engine).
- **Name the anti-patterns you push back on** — using Elasticsearch as a primary system of record (no transactions, eventual consistency, data-loss-by-misconfiguration risk); as a relational store needing real joins; for tiny data where the operational overhead isn't justified; or for high-churn per-document counters (write amplification).
- **Propose the right boundary** — usually "RDBMS/event store is the source of truth; Elasticsearch is a denormalized, rebuildable read model fed by CDC", which preserves the ability to drop and rebuild the index without data loss.
- **Back it with trade-offs and a migration/exit path** — quantify cost (cluster ops, RAM for working set), reliability implications, and whether a managed offering vs self-hosted fits the team.

The interviewer wants to see that you weigh operational cost and consistency guarantees, communicate trade-offs to stakeholders, and protect the source of truth — not that you reach for Elasticsearch reflexively.

## 🧩 Extended Questions — Set 1: Deeper theory & internals

### 🟢 — extended

#### Q60. [Theory] What physical files make up a Lucene segment, and what does each store?

A segment is a self-contained mini-index on disk, written once and never modified. Each segment is a set of files sharing a base name (e.g., `_3.cfs` or, when not compound, `_3.*`), and each file holds one logical structure:

- **`.tim` / `.tip`** — the **term dictionary** (`.tim`) and its **term index** (`.tip`, an in-memory FST that points into the dictionary). This is how a term is located quickly.
- **`.doc`** — the **postings list**: for each term, the document ids that contain it (plus term frequencies).
- **`.pos` / `.pay`** — **positions** and **payloads/offsets**, needed for phrase queries, proximity, and highlighting.
- **`.fdt` / `.fdx`** — **stored fields** (this is where `_source` lives) and their index.
- **`.dvd` / `.dvm`** — **doc values** (the columnar doc→value structure used for sort/aggregate).
- **`.nvd` / `.nvm`** — **norms** (field-length normalization factors used by BM25).
- **`.liv`** — **live docs** bitset; marks which docs are deleted (tombstones) without rewriting the segment.
- **`.kdd` / `.kdi` / `.kdm`** — BKD tree for **points** (numeric, date, IP, geo range queries).
- **`segments_N`** — the **commit point**: the list of segments that make up the shard at the last flush.

A `text` query touches `.tip`→`.tim`→`.doc`(/`.pos`); a sort/aggregation touches `.dvd`; fetching a hit reads `.fdt`. Knowing this map is what makes "why is this query slow / this index big" answerable concretely.

#### Q61. [Theory] What exactly is a finite-state transducer (FST) and why does Lucene use one for the term dictionary?

An **FST** is a compressed automaton that maps strings to values, like a Map<String, Long> but stored as a graph of shared edges. Lucene uses it for the **term index** (`.tip`): it maps a term (or term prefix) to the byte offset of that term's block in the on-disk term dictionary (`.tim`).

Two properties make it ideal:

1. **Prefix and suffix sharing** — because terms in a sorted dictionary share long common prefixes (`search`, `searchable`, `searcher`), the FST stores each shared prefix once. This shrinks the in-memory footprint enough that the term index for billions of terms fits in RAM.
2. **Ordered traversal** — the automaton is built over sorted terms, so you can walk it to do prefix queries, range queries, fuzzy/`wildcard` matching (by intersecting the query automaton with the term FST), and `terms_enum` seeks efficiently.

```
Terms: "cat", "cats", "dog"
FST shares the "cat" path:  c → a → t →(value)→ s →(value)
                                              dog as a separate path
```

The practical upshot: term lookups and prefix scans are fast and memory-cheap because the FST keeps only the term index in heap, while the bulk term data stays on disk in `.tim`.

#### Q62. [Theory] What is a `_seq_no` and `_primary_term`, and how do they together provide optimistic concurrency?

Every write to a shard is stamped with two numbers that, as a pair, uniquely order operations even across primary failovers:

- **`_seq_no`** (sequence number) — a per-shard, monotonically increasing counter assigned by the **primary** to each operation. It establishes a total order of operations on that shard and is what replicas use to detect gaps and what recovery/CCR use to replay from a point.
- **`_primary_term`** — a counter incremented **each time a new primary is elected** for the shard. It disambiguates operations from an old (possibly partitioned) primary versus the current one: a higher primary term always wins.

For optimistic concurrency, you read a document (getting its current `_seq_no` and `_primary_term`), then write back with `if_seq_no` and `if_primary_term`; the write succeeds only if those still match, otherwise you get a `409` version conflict and must retry on fresh data.

```json
POST /products/_doc/1?if_seq_no=362&if_primary_term=2
{ "name": "Headphones", "price": 69.99 }
```

This replaced the old `_version`-based CAS and is more correct under primary failover, because `_version` alone could not distinguish a stale primary's writes from the new primary's.

#### Q63. [Theory] When you index a document, what is the exact sequence of internal steps before it is acknowledged?

The write path through a primary shard is precise and worth knowing end to end:

1. **Routing** — the coordinating node computes `hash(_routing) % num_primaries` to find the target primary and forwards the request there.
2. **Mapping/parse** — the primary parses the JSON, applies dynamic mapping if a new field appears (this updates cluster state via the master), and runs analyzers to produce terms.
3. **Assign `_seq_no` / `_primary_term`** and apply the operation to the **in-memory Lucene index buffer**.
4. **Append to the translog** and (default `durability: request`) **fsync** it — at this point the write is durable even though it is not yet in a segment or searchable.
5. **Replicate** — the primary forwards the operation in parallel to all in-sync replica shards, which repeat the buffer+translog steps.
6. **Acknowledge** — once the required replicas (per `wait_for_active_shards`) respond, the primary returns success to the coordinator, which returns to the client.

Note what is **not** in this path: a refresh. The document is durable and replicated but **not searchable** until the next refresh turns the buffer into a segment (Q14/Q26). That separation of durability from visibility is the crux of NRT.

#### Q64. [Practical] How do you read your own write immediately after indexing, and what are the options' costs?

Search is NRT, so a freshly indexed doc may not appear in a search for up to `refresh_interval`. Options, cheapest to most expensive:

1. **GET by id** — real-time, reads from the translog if not yet refreshed. Free and correct if you only need that one document by id.

```json
GET /products/_doc/1
```

2. **`?refresh=wait_for`** on the write — the write call returns only once a refresh has made it visible, without forcing an extra refresh (it piggybacks on the next scheduled one). Adds up to `refresh_interval` of latency to that one call but does **not** create tiny segments.

```json
PUT /products/_doc/1?refresh=wait_for
{ "name": "Headphones" }
```

3. **`?refresh=true`** — forces an immediate refresh, creating a new segment now. Correct but the most damaging at scale: per-write refreshes cause a tiny-segment explosion and merge pressure.

The interview-correct answer is: use **GET by id** when you can, **`wait_for`** when you need search visibility, and **never** `refresh=true` on a hot write path.

#### Q65. [Theory] What is the difference between `index: false`, `doc_values: false`, and `enabled: false` on a field?

These three knobs disable different capabilities and save different costs — confusing them causes "why can't I query/sort this?" bugs:

- **`"index": false`** — the field is **not added to the inverted index**, so you **cannot search/filter** on it, but it is still stored in `_source` (returned in hits) and still gets doc values (so you can sort/aggregate). Use for fields you only display or aggregate, never query.
- **`"doc_values": false`** — **no columnar doc values** are built, so you **cannot sort, aggregate, or script** on it, but you can still search/filter (inverted index intact). Use to save disk on a field you only ever filter, never sort/agg.
- **`"enabled": false`** (object/`object` fields only, or whole mapping) — Elasticsearch **stores the JSON in `_source` but does not parse or index it at all**. No searching, no aggregating — it's an opaque blob you can retrieve. Use for arbitrary metadata you only want round-tripped.

```json
"properties": {
  "raw_blob":   { "type": "object", "enabled": false },
  "display_id": { "type": "keyword", "index": false },
  "filter_tag": { "type": "keyword", "doc_values": false }
}
```

The mnemonic: **`index`** controls search, **`doc_values`** controls sort/aggregate, **`enabled`** controls whether ES looks at the field at all.

#### Q66. [Theory] What are norms, and why might you disable them?

**Norms** store the **field-length normalization factor** (and index-time boosts) per field per document — the part of BM25 that makes a match in a short title count more than in a long body (the `b` parameter; Q12). They live in the `.nvd`/`.nvm` files and are loaded as doc-value-like structures at search time.

You might disable norms (`"norms": false`) when a field is used **only for filtering, not for relevance scoring** — for example a `keyword`-ish `text` field, or a status/tag field you only `term`-match. Disabling saves roughly **1 byte per field per document** of storage and the memory to hold norms, which matters at billions of docs.

```json
"properties": {
  "body":   { "type": "text" },                    // keep norms, length matters
  "status": { "type": "text", "norms": false }     // filtered only, no scoring
}
```

The catch: disabling norms is effectively irreversible for existing data (re-enabling only affects new segments), and once off, length normalization no longer contributes to that field's score — so only disable it where you are certain relevance length-weighting is unwanted.

#### Q86. [Theory] What is the difference between `_source` and stored fields (`"store": true`)?

These are two independent ways Elasticsearch can return field values, and confusing them leads to wasted storage:

- **`_source`** is the **single, whole original JSON** document, stored once as a blob (in `.fdt`). By default every field you return comes out of `_source` — Elasticsearch parses the blob and extracts the requested fields. This is what powers reindex, update, and highlighting.
- **`"store": true`** on a field stores that **individual field's value separately** on disk, retrievable without parsing `_source`. Historically useful when `_source` was disabled or when a single tiny field was needed out of a huge document, so you didn't pay to decompress the whole `_source`.

```json
"properties": {
  "title": { "type": "text", "store": true }
}
```

In practice, because `_source` is compressed and field extraction from it is fast, **`store: true` is rarely worth it** today — the common, idiomatic pattern is to keep `_source` and use `_source` filtering (`"_source": ["title"]`) to limit what's returned. Stored fields still matter for fields **computed at index time that aren't in `_source`** (e.g., a script-generated value) or specialized cases; otherwise prefer `_source`.

#### Q87. [Theory] How is a date stored and queried internally, and why do `calendar_interval` and `fixed_interval` differ?

A `date` field is parsed from its input format into a **single `long` of epoch milliseconds** (UTC) and indexed as a numeric **point** (BKD tree, for range queries) plus doc values (for sort/agg). The display format is just for input/output; internally there is only the epoch number, which is why range queries and date math (`now-7d/d`) are fast numeric operations.

The interval distinction matters because calendar units are **irregular**:

- **`fixed_interval`** — an exact multiple of a fixed unit (`90m`, `2h`, `7d`). Every bucket is the same number of milliseconds. Safe and unambiguous, but a `"1d"` fixed interval is exactly 86,400,000 ms, which is **wrong across a DST boundary** (a real day can be 23 or 25 hours).
- **`calendar_interval`** — a single calendar unit (`day`, `week`, `month`, `quarter`, `year`) that is **DST- and calendar-aware** in the configured `time_zone`. A month bucket spans the actual month length; a day bucket spans the actual local day even when DST shifts it.

```
fixed_interval: 1d = 86_400_000 ms  → off by an hour on DST change days
calendar_interval: day → real local day (23/24/25h) in the given time_zone
```

The rule: use `calendar_interval` for human-calendar buckets (daily/monthly dashboards in a timezone) and `fixed_interval` for true fixed durations (every 5 minutes). A classic subtle bug is using `fixed_interval: 1d` and seeing data leak across midnight on DST days.

### 🟡 — extended

#### Q67. [Theory] How does Lucene's tiered merge policy decide which segments to merge?

Elasticsearch uses Lucene's **`TieredMergePolicy`**, which groups segments into size tiers and merges within a tier rather than always merging the smallest. Key behaviors and the settings that control them:

- It tries to keep the number of segments below a budget, merging when a tier accumulates more than `segments_per_tier` (default ~10) eligible segments of similar size.
- A single merge combines up to `max_merge_at_once` segments (default 10) and won't produce a segment larger than `max_merged_segment` (default 5 GB) during normal indexing — this caps the cost of any one merge and avoids gigantic segments that are expensive to merge again.
- Segments with many **deleted documents** are prioritized for merging (reclaiming space); `deletes_pct_allowed` bounds how many deletes can accumulate.

```
Tier 0: [s][s][s][s][s][s][s][s][s][s][s]  → 11 > 10, merge ten → one Tier-1 segment
Tier 1: [  S  ][  S  ]                      → wait until enough accumulate
```

Because `max_merged_segment` caps natural merges at ~5 GB, large read-only indices that you want as a single segment require an explicit **force merge** (which ignores that cap). The expert point: merge policy is a continuous background trade-off between segment count (search speed) and merge I/O (indexing cost), tuned by these knobs.

#### Q68. [Theory] How do global ordinals work, and why do they make the first `terms` aggregation slow?

For a `keyword` field, each segment stores its terms as **segment-local ordinals** (term #0, #1, ... within that segment's sorted dictionary). Aggregations and high-cardinality operations work on integers, not strings, for speed — but a value's ordinal differs across segments. **Global ordinals** are a per-shard mapping that unifies all segment-local ordinals into one shard-wide numbering, so a `terms` agg can count across the whole shard using integers.

The cost: global ordinals are **built lazily on first use** and **rebuilt whenever a new segment appears** (i.e., after a refresh). On a high-cardinality field this build is expensive, which is why the **first aggregation after a refresh is slow** and subsequent ones are fast (cached until the next refresh).

```json
"properties": {
  "tenant_id": { "type": "keyword", "eager_global_ordinals": true }
}
```

Setting **`eager_global_ordinals: true`** moves that build into the refresh itself, so it's paid by indexing rather than by the first query — a good trade for fields you always aggregate/group on (and a bad one for rarely-aggregated, frequently-refreshed fields). This is the concrete mechanism behind Q48's tuning tip.

#### Q69. [Practical] What is a Point-in-Time (PIT), how do you use it, and how does it differ from scroll?

A **PIT** is a lightweight, named handle that pins a consistent view of the shards' segments at a moment in time, so a sequence of searches all see the **same data** despite ongoing indexing/merging. It is the modern replacement for scroll for consistent deep pagination and export.

```json
// 1. open a PIT (keep_alive controls how long it lives)
POST /products/_pit?keep_alive=2m
// → { "id": "46To...==" }

// 2. search with the PIT + search_after; no index name in the URL
GET /_search
{
  "size": 1000,
  "pit": { "id": "46To...==", "keep_alive": "2m" },
  "query": { "match_all": {} },
  "sort": [ { "_shard_doc": "asc" } ],
  "search_after": [ 12345 ]
}

// 3. close it when done
DELETE /_pit
{ "id": "46To...==" }
```

Differences from scroll: a PIT is **decoupled from a single query** — you can run many different queries against the same frozen view, combine it with `search_after` (forward cursoring), and even run them concurrently. Scroll bundles the snapshot and the cursor into one stateful context that you must page sequentially. Both pin segments (so they delay segment reclamation and cost resources until released/expired), so always close the PIT or set a sane `keep_alive`. `_shard_doc` is a built-in, cheap tiebreaker sort designed for PIT pagination.

#### Q70. [Theory] What is the request cache, the query (node) cache, and the fielddata cache — and what does each cache?

Three distinct caches, often confused, each keyed and scoped differently:

- **Shard request cache** — caches the **whole-shard response of a search request**, but **only the parts that don't depend on hits**: primarily **aggregation results** and `hits.total`. Keyed by the request body per shard. It's why repeating the same dashboard query is near-instant. Only caches requests with `size: 0` by default (so it caches aggs, not hit lists). Invalidated on refresh.
- **Query cache (node-level)** — caches the **set of documents matching a single filter clause** (a bitset of doc ids), per segment, in **filter context**. This is why moving yes/no clauses into `filter` (Q9) pays off: the bitset for `status: active` is reused across queries. Uses an LRU and only caches filters deemed worth it (frequently used, on large-enough segments).
- **Fielddata cache** — holds **on-heap fielddata for `text` fields** when you sort/aggregate on them (the OOM-prone case from Q49). For `keyword`/numeric, doc values are used instead (off-heap), so this cache mostly matters as a hazard to avoid.

```
request cache : request body  → agg results / total      (per shard, size:0)
query  cache  : filter clause → matching-doc bitset       (per segment, filter ctx)
fielddata     : text field    → doc→value (ON HEAP)        (avoid)
```

Knowing which cache a tuning change hits — `filter` context → query cache; `size:0` dashboards → request cache — is what separates a guess from a reasoned optimization.

#### Q71. [Practical] How do `function_score` and `script_score` blend non-textual signals into relevance, with an example?

`function_score` (and the newer, cleaner `script_score`) modify the base query `_score` with functions of document fields — recency, popularity, rating, geo distance — so ranking reflects business signals, not just text match.

```json
GET /products/_search
{
  "query": {
    "function_score": {
      "query": { "match": { "name": "headphones" } },
      "functions": [
        { "gauss": { "created": { "origin": "now", "scale": "30d", "decay": 0.5 } } },
        { "field_value_factor": { "field": "popularity", "modifier": "log1p", "factor": 1.2 } }
      ],
      "score_mode": "sum",
      "boost_mode": "multiply"
    }
  }
}
```

- **`score_mode`** combines the multiple functions with each other (`sum`, `multiply`, `avg`, `max`...).
- **`boost_mode`** combines that result with the original query score (`multiply` is typical: relevance × business signal).
- **Decay functions** (`gauss`/`exp`/`linear`) implement "newer/closer is better" smoothly via `origin`, `scale`, `decay`.

The newer `script_score` query is more efficient and explicit for simple cases:

```json
{ "script_score": {
    "query": { "match": { "name": "headphones" } },
    "script": { "source": "_score * Math.log(2 + doc['popularity'].value)" }
}}
```

The senior caution: these run per matching document, so keep the query selective and prefer `rescore` (Q53) to apply expensive scoring only to the top-N rather than every hit.

#### Q72. [Theory] How does the `nested` type work physically, and what is its cost model?

A `nested` field is not stored as part of its parent's Lucene document — each nested object is indexed as a **separate, hidden Lucene document** stored adjacent to the parent in the same segment, with the parent stored last and a bitset marking which docs are parents (roots). A `nested` query matches the child docs, then maps the hits back up to their root via the parent bitset (a "join" within the segment, which is why it's cheap relative to cross-document joins but not free).

Cost implications:

- **Document count explosion** — a parent with 50 nested objects becomes 51 Lucene docs. `index.mapping.nested_objects.limit` (default 10,000 per doc) guards against runaway expansion, and segment sizes/merge costs grow accordingly.
- **Update amplification** — updating any nested element re-indexes the **entire** parent document (delete + reindex of all children), so high-churn nested arrays are expensive.
- **Query/agg must be nested-aware** — you must use the `nested` query and `nested`/`reverse_nested` aggregations; a normal query silently won't address the inner docs.

The decision rule from Q30 sharpens: use `nested` only when **within-element correctness** is required and the array is small and not churned; otherwise denormalize or use `join` (cross-shard parent/child) deliberately.

#### Q73. [Practical] How do runtime fields work, and what is the schema-on-read vs schema-on-write trade-off?

**Runtime fields** are fields defined by a Painless script that are **evaluated at query time** rather than indexed at write time (schema-on-read). They appear in queries, aggregations, and the `fields` response as if they were real fields, but cost nothing at index time and store nothing on disk.

```json
PUT /logs/_mapping
{
  "runtime": {
    "url_path": {
      "type": "keyword",
      "script": { "source": "emit(grok('%{URIPATH:p}', doc['url'].value).p)" }
    }
  }
}
```

Trade-offs:

- **Schema-on-read (runtime field)** — zero index cost and instant schema change (add a field to old data without reindexing), but **slow at query time** because the script runs per matching document; no doc values, so large aggregations on it are expensive.
- **Schema-on-write (indexed field)** — fast queries/aggs (doc values, inverted index) but costs index time, disk, and requires a reindex to add to existing data.

The idiomatic pattern: **prototype with a runtime field** (explore a new derived field cheaply on existing data), and once it proves valuable and is queried often, **promote it to an indexed field** for performance — or index it at ingest while keeping it as a runtime fallback for historical data. This is Elasticsearch's answer to schema evolution without forced reindexing.

#### Q74. [Theory] How are `dense_vector` fields indexed with HNSW, and what do `m`, `ef_construction`, `k`, and `num_candidates` control?

A `dense_vector` with `index: true` builds an **HNSW** (Hierarchical Navigable Small World) graph per segment — a multi-layer proximity graph where search starts at a sparse top layer and descends, greedily hopping to closer neighbors, giving approximate-nearest-neighbor results in roughly logarithmic hops instead of scanning every vector.

Build-time parameters (in `index_options`):

- **`m`** — max connections per node per layer (default 16). Higher `m` → denser graph → better recall and faster search, but more memory and slower indexing.
- **`ef_construction`** — size of the candidate list while building (default 100). Higher → higher-quality graph (better recall) but slower indexing.

Search-time parameters (in the `knn` clause):

- **`k`** — how many nearest neighbors to return.
- **`num_candidates`** — how many candidates each shard explores before returning its top `k`. Higher → better recall, more CPU. Must be `>= k`.

```json
"embedding": {
  "type": "dense_vector", "dims": 768, "index": true,
  "similarity": "cosine",
  "index_options": { "type": "hnsw", "m": 16, "ef_construction": 100 }
}
```

The core trade-off is **recall vs latency/memory**: ANN is approximate, so you tune `num_candidates`/`m`/`ef_construction` upward until recall is acceptable, accepting more cost. For memory pressure, `int8`/`int4`/`bbq` (quantized) vector types trade a little recall for a large memory reduction — increasingly the default at scale in 2026.

### 🟠 — extended

#### Q75. [Theory] How does sequence-number-based recovery and the "primary/replica in-sync" model work?

Replicas stay consistent with their primary via **sequence numbers** (Q62) and two markers per shard:

- **local checkpoint** — the highest `_seq_no` below which a shard has **all** operations (no gaps) processed locally.
- **global checkpoint** — the highest `_seq_no` that the primary knows **all in-sync replicas** have reached. Operations at or below it are safe everywhere.

When a replica reconnects after a brief outage, recovery first tries an **operations-based (sequence-number) recovery**: the primary replays only the translog operations **after the replica's local checkpoint** from its retention lease, avoiding a full file copy. If the needed operations have already been merged away (translog retention exceeded), it falls back to a **file-based recovery** copying the missing segment files.

```
primary  ops: ........[gcp]....[lcp]....→
replica reconnects at seq 1040; primary replays 1041.. from translog → fast
   else (translog gone) → copy segment files (slow)
```

The global checkpoint also bounds what PIT/CCR/recovery can rely on and what the primary can advance. **Retention leases** keep translog operations around long enough for expected replica/follower lag so the cheap path is usable. This machinery is why a brief node restart heals quickly (delayed allocation + ops-based recovery) while a long absence forces a full copy.

#### Q76. [Theory] What is adaptive replica selection, and how does the coordinating node choose which shard copy to query?

When a shard has multiple copies (primary + replicas), the coordinating node must pick one per shard for each search. **Adaptive Replica Selection (ARS)**, on by default, chooses the copy expected to respond fastest rather than round-robining blindly.

It scores each candidate copy using a formula (a variant of Cubic's "Cˆ3") that blends:

- the **node's recent response times** for searches,
- the node's **search thread-pool queue length** (load), and
- the number of **outstanding requests** the coordinator has sent it.

The node with the best (lowest) score gets the request, so a slow or overloaded node is automatically avoided, and load self-balances toward healthy nodes. This matters in heterogeneous clusters (a GC-pausing node, a hot node) where naive round-robin would keep sending work to a struggling copy and inflate tail latency.

```
shard0 copies: nodeA (fast, idle)  nodeB (GC pause, deep queue)
ARS score → pick nodeA; nodeB recovers, scores improve, traffic returns
```

The practical consequence: you usually leave ARS on; it's a key reason adding replicas improves tail latency, not just throughput, because the coordinator can route around momentary slowness.

#### Q77. [Theory] How does distributed scoring stay accurate across shards (the IDF problem and DFS query-then-fetch)?

BM25's IDF depends on **document frequency** — how many documents contain a term — which is a **global** statistic. But in plain query-then-fetch each shard scores using only **its own local** document frequencies. If a term is distributed unevenly across shards, the same document could score differently depending on which shard holds it, producing slightly inconsistent ranking, especially with few documents or skewed routing.

The fix is **DFS query-then-fetch** (`search_type=dfs_query_then_fetch`): a preliminary **DFS (Distributed Frequency Search)** phase gathers term and document frequencies from all shards, computes **global** statistics, and distributes them so every shard scores with the same IDF.

```
plain QTF : each shard uses local df → ranking can wobble on small/skewed data
dfs QTF   : gather global df first → consistent scoring, one extra round trip
```

The cost is an extra round trip, so it's not the default. In practice, with enough documents per shard the local frequencies approximate the global ones well and plain query-then-fetch is fine; you reach for `dfs_query_then_fetch` when relevance must be exact (small indices, A/B relevance tests, or visibly inconsistent top results across runs).

#### Q78. [Practical] How would you design an alias-based blue/green index with zero-downtime reindex and dual-write catch-up? Give the steps.

The goal is to change a mapping/analyzer (which requires a new index) without dropping writes or reads. Pattern using a **read alias** and a **write alias**:

```json
// state: read alias "products" → v1 ; write alias "products_write" → v1

// 1. create v2 with the new mapping, then start writing to BOTH (dual write)
POST /_aliases
{ "actions": [ { "add": { "index": "products_v2", "alias": "products_write" } } ] }
// app now writes to "products_write" → fans out to v1 AND v2

// 2. backfill historical data v1 → v2 with reindex (snapshot of the past)
POST /_reindex?wait_for_completion=false
{ "source": { "index": "products_v1",
              "query": { "range": { "updated": { "lt": "<reindex_start_ts>" } } } },
  "dest":   { "index": "products_v2", "op_type": "create" } }
// op_type:create + dual-write means a concurrently-updated doc isn't clobbered by stale backfill

// 3. when reindex is done and v2 has caught up, flip the read alias atomically
POST /_aliases
{ "actions": [
    { "remove": { "index": "products_v1", "alias": "products" } },
    { "add":    { "index": "products_v2", "alias": "products" } } ] }

// 4. stop dual-writing to v1, remove it from the write alias, verify, then delete v1
POST /_aliases
{ "actions": [ { "remove": { "index": "products_v1", "alias": "products_write" } } ] }
```

The two correctness tricks: **dual write before backfill** so no live write is missed, and **`op_type: create` on the reindex** so the backfill never overwrites a newer live write with stale data. External versioning (`version_type=external`) is the alternative to `op_type:create` when you carry your own version. Reads flip atomically via the single alias swap, so clients never see an empty or half-built index.

#### Q79. [Theory] How does the cluster state get published, and why is a large cluster state a scaling problem?

The **cluster state** is the master's authoritative snapshot of everything structural: all index metadata and mappings, the routing table (where every shard lives), templates, ILM policies, pipelines, and settings. Every change (a new index, a mapping update, a shard moving) produces a new version that the master **publishes** to every node and commits only after a quorum of master-eligible nodes acknowledges (the Raft-like flow from Q41).

It's a scaling problem because:

- The state is held **in memory on every node** and (the metadata portion) persisted on master-eligible nodes. Tens of thousands of shards/indices/fields make it large.
- Each change requires **serializing and shipping** the diff (and sometimes the full state) cluster-wide and reaching consensus. With a huge state, frequent changes (e.g., dynamic-mapping field explosions, mapping updates per write) make the master a bottleneck and slow every cluster operation.
- **Mapping explosion** — unbounded dynamic fields (e.g., user-supplied keys becoming fields) bloats mappings; `index.mapping.total_fields.limit` and `mapping.depth.limit` guard against this.

```
change → master builds new cluster-state version → publish diff to all nodes
       → quorum ack → commit. Big state ⇒ slow publish ⇒ master pressure.
```

This is the deep reason behind two famous rules: **don't over-shard** (Q32) and **don't allow unbounded dynamic mappings** — both inflate cluster state and turn the master into the limiting resource.

#### Q80. [Theory] What is the `flattened` field type, and when does it rescue you from mapping explosion?

A **`flattened`** field maps an **entire JSON object and all its nested sub-keys as a single field**, indexing every leaf value as a keyword under that one mapping entry — without creating a separate mapping field per key. This sidesteps **mapping explosion** when an object has many or unpredictable keys (user-defined metadata, arbitrary labels, dynamic tags).

```json
"properties": { "labels": { "type": "flattened" } }
```

```json
PUT /events/_doc/1
{ "labels": { "env": "prod", "team": "search", "region": "us-east" } }

GET /events/_search
{ "query": { "term": { "labels.env": "prod" } } }   // query a sub-key
```

Trade-offs that define when to use it:

- **Wins** — one mapping field regardless of how many keys appear, so cluster state stays small (ties back to Q79); good for high-cardinality, schema-less key spaces.
- **Limits** — every value is treated as a `keyword` (no `text` analysis, no numeric ranges, no per-field analyzers); you can `term`/`prefix`/`exists` but not full-text `match` or numeric `range` on sub-keys; no doc values for sub-keys aggregations are limited.

So `flattened` is the right tool for arbitrary metadata bags you mostly filter on, and the wrong tool when you need analyzed text search or numeric ranges on those keys — there you'd model explicit fields or accept the field count.

#### Q81. [Practical] How do ingest pipelines work, and how do they compare to Logstash for transformation?

An **ingest pipeline** is a sequence of **processors** that transform a document **inside Elasticsearch**, on an ingest-role node, before it is indexed. You define it once and attach it to an index, data stream, or per-request.

```json
PUT /_ingest/pipeline/weblogs
{
  "processors": [
    { "grok": { "field": "message",
                "patterns": ["%{IP:client} %{WORD:method} %{URIPATHPARAM:path}"] } },
    { "geoip": { "field": "client", "target_field": "geo" } },
    { "set": { "field": "ingested_at", "value": "{{_ingest.timestamp}}" } },
    { "remove": { "field": "message" } }
  ]
}

PUT /weblogs/_doc/1?pipeline=weblogs
{ "message": "8.8.8.8 GET /search?q=es" }
```

Versus **Logstash**:

- **Ingest pipelines** run in the cluster, need no extra process, scale with ingest nodes, and cover most enrichment (grok, geoip, set/rename/convert, script, enrich lookups). Best when you want a lightweight, in-cluster transform and are already shipping via Beats/Elastic Agent.
- **Logstash** is a separate, heavier service with a far larger plugin ecosystem (many inputs/outputs, queuing/buffering, aggregate filter, JDBC, multi-destination fan-out). Best for complex pipelines, persistent queue durability, protocol/format breadth, or when Elasticsearch isn't the only sink.

The modern default is **ingest pipelines (often via Elastic Agent integrations)** for typical log enrichment, reserving Logstash for heavy transformation, buffering, or multi-output topologies. The **enrich processor** (a precomputed lookup index) covers the "join a small reference dataset at ingest" case that people used to reach to Logstash or app code for.

#### Q88. [Theory] How does the `terms` aggregation produce approximate counts on sharded data, and what are `size`, `shard_size`, and `doc_count_error_upper_bound`?

A `terms` aggregation finding the "top N terms by count" across a sharded index is **approximate**, because each shard independently returns only its own top terms and the coordinator merges them — a term that is globally in the top N might be just below the cutoff on several shards and get under-counted or missed entirely.

The controls and diagnostics:

- **`size`** — how many buckets you ultimately want back (the final top-N).
- **`shard_size`** — how many candidate terms **each shard** returns before merging (defaults to roughly `size * 1.5 + 10`). Raising it improves accuracy at the cost of more memory/network, because borderline terms are more likely to be considered.
- **`doc_count_error_upper_bound`** — the **maximum possible undercount** for the returned counts: the sum, across shards, of the count of the last term each shard returned. A non-zero value tells you the result might be off by up to that much.
- **`sum_other_doc_count`** — how many documents fell into terms not shown.

```json
GET /events/_search
{ "size": 0,
  "aggs": { "top_tags": { "terms": { "field": "tag", "size": 10, "shard_size": 100,
                                     "show_term_doc_count_error": true } } } }
```

For **exact** counts you can use a `composite` aggregation (paginates over all terms deterministically, no top-N approximation) or raise `shard_size` toward the field cardinality (expensive). The senior insight: top-terms accuracy is a shard-locality artifact, and the right lever is `shard_size` (accuracy/cost trade) while `doc_count_error_upper_bound` is how you *know* whether you can trust the numbers.

### 🔴 — extended

#### Q82. [Theory] Explain the global checkpoint's role in cross-cluster replication and how CCR stays consistent despite being asynchronous.

**Cross-Cluster Replication (CCR)** makes a **follower index** pull operations from a **leader index** by sequence number. The follower issues a "read changes" request asking for operations after its last applied `_seq_no`; the leader returns the batch from its translog/Lucene history, and the follower applies them **in order**, preserving the leader's `_seq_no`/`_primary_term` stamps so the histories match exactly.

Consistency properties:

- It is **asynchronous** — the follower lags the leader by the network round trip plus apply time, so reads on the follower are eventually consistent (a known, bounded staleness).
- It is **ordered and idempotent** — because operations carry their original sequence numbers, replays after a follower restart resume from the follower's checkpoint without duplication or reordering.
- The leader's **soft-deletes + retention leases** keep the operation history available long enough for the follower to catch up; if the follower lags beyond the retention, CCR falls back to copying files (like Q75's recovery, but cross-cluster).

```
leader ops 1..N (soft-deletes retained) ──read-changes──▶ follower applies 1..N in order
follower lag = network RTT + apply; promote-to-leader on region failover
```

For DR, you **promote** a follower to a regular (writable) index during a leader-region outage; because of the bounded lag, you accept losing at most the un-replicated tail. The expert nuance: CCR's correctness rests on the same sequence-number/soft-delete machinery as local recovery — it's local replication generalized across clusters with retention-lease-bounded history.

#### Q83. [Theory] What are soft deletes and retention leases, and why were they introduced?

**Soft deletes** mean that when a document is deleted or updated, Lucene retains a record of the operation (rather than only marking a liveDocs bit) for a configurable window, so the **operation history** of a shard can be replayed by sequence number. This is what enables **operations-based recovery** (Q75) and **CCR** (Q82) to fetch "all changes after seq N" instead of doing a full file copy.

**Retention leases** are claims, registered on the primary, that say "keep operations above sequence number X around because some consumer (a replica recovering, a CCR follower, a PIT) still needs them." The primary keeps soft-deleted operation history as long as a lease requires it, and prunes once all leases have advanced past it.

```
primary history: [..pruned..][ retained by leases ][ live ]
                              ▲lease(replicaB=1040)  ▲lease(follower=1032)
```

Why they were introduced (7.x): the old approach relied on keeping a large **translog** around for recovery, which was costly and bounded by translog size/age. Soft deletes + leases moved history retention into Lucene with precise, consumer-driven control, making **fast operations-based recovery and CCR** robust — the primary keeps exactly as much history as outstanding consumers need, no more. The trade-off is some extra storage for retained deletes (tuned by `index.soft_deletes.retention_lease.period`), and merge can't fully reclaim those tombstones until leases release them.

#### Q84. [Theory] How does the search thread pool and its bounded queue create (and reveal) backpressure, and how do you reason about `EsRejectedExecutionException`?

Each node has fixed-size **thread pools** per operation class; the **`search`** pool defaults to roughly `int((#allocated_processors * 3) / 2) + 1` threads with a bounded **queue** (default 1000). A search task that can't get a thread waits in the queue; if the queue is full, the task is **rejected** with `EsRejectedExecutionException` (HTTP 429 / `search_phase_execution_exception` with rejection).

This is **deliberate backpressure**, not a bug: a bounded queue prevents unbounded memory growth and cascading collapse under overload — the node sheds load instead of OOMing.

How to reason about rejections:

- **Rejections = demand exceeds capacity.** Check `_cat/thread_pool/search?v=id,active,queue,rejected` and `_nodes/stats`. Persistent `rejected` growth means the node can't keep up.
- **The fix is rarely a bigger queue.** Enlarging the queue just delays rejection and hides the real problem (and risks heap). Instead reduce per-query cost (fewer shards touched via routing, filter caching, lighter aggs), add capacity (nodes/replicas, coordinating nodes), or smooth bursty clients.
- **Distinguish search vs `write` pool rejections** — write rejections during bulk indicate the indexing path is saturated (raise refresh interval, batch better, add nodes), a different remedy than search rejections.

```
requests ──▶ [search threads: busy] ──▶ [queue: 1000] ──full──▶ REJECT (429)
fix: cut per-query work / add capacity — NOT just grow the queue
```

The senior framing: thread-pool rejection is the cluster telling you, safely, that you've hit a capacity wall; treat it as a capacity/efficiency signal and resist the urge to mask it by inflating queues or breaker limits (which converts a graceful 429 into an outage).

#### Q85. [Theory] How does a `rescore` work, and why is "retrieve cheap, rerank expensive on top-N" the canonical relevance scaling pattern?

A **rescore** runs a second, more expensive scoring pass over **only the top-N documents** returned by the cheap first-pass query on each shard, rather than over every match. It bounds the cost of expensive scoring to a constant N instead of the (potentially huge) match count.

```json
GET /docs/_search
{
  "query": { "match": { "content": "electric vehicle range" } },
  "rescore": {
    "window_size": 100,
    "query": {
      "rescore_query": {
        "match_phrase": { "content": { "query": "electric vehicle range", "slop": 2 } }
      },
      "query_weight": 0.7, "rescore_query_weight": 1.3
    }
  }
}
```

Why it's the canonical pattern:

- The first pass is a **cheap, high-recall retriever** (BM25 term match, or ANN kNN) that quickly narrows millions of docs to a few hundred candidates.
- The second pass applies an **expensive, high-precision scorer** — phrase/proximity, `script_score` with many signals, a cross-encoder/LTR model — to just those `window_size` candidates per shard.
- Total cost is `O(matches)` for retrieval + `O(N)` for reranking, independent of how many documents matched, which is exactly what lets relevance-heavy scoring scale to billions of documents.

This generalizes to modern stacks: ANN/BM25 retrieve, **LTR or a learned reranker rescores the top-N**, and RRF (Q46) fuses multiple cheap retrievers before an optional rerank. The deep point is an architectural one — **separate recall from precision and pay for precision only on the survivors** — and it's why rescore, rather than putting all signals in the main query, is the way to keep expensive relevance affordable.

#### Q89. [Theory] Why is a heavily-updated counter (or per-document mutable state) an anti-pattern, and how do segment immutability and version conflicts make it worse at scale?

A document that is updated frequently (a view counter, a live score, a hot inventory level) collides with two fundamentals of Elasticsearch:

- **Segment immutability (Q57)** — every update is a **delete-tombstone + reindex** of the full document into a new segment. A counter updated 1,000 times leaves 999 tombstones plus 1,000 segment entries to be merged away, producing **write amplification** and **merge debt** wildly out of proportion to the data. The whole `_source` is rewritten each time, even to change one number.
- **Optimistic concurrency (Q62)** — concurrent updaters racing on the same hot document repeatedly hit `_seq_no`/`_primary_term` **version conflicts**, forcing read-modify-write retries that don't scale and serialize on that one document.

```
update counter ×1000  →  1000 reindexes + 999 tombstones  →  merge storm
concurrent writers    →  version conflicts → retry loop → contention on 1 doc
```

The architectural fixes:

- **Append events, aggregate at read** — write one immutable event per increment and compute the count with a `sum`/`value_count` aggregation (or a `date_histogram`), turning a mutable counter into append-only data Elasticsearch loves.
- **Keep the mutable state in the right store** — a counter belongs in Redis/a database; project periodic snapshots into Elasticsearch for search, not the live counter.
- If you must update in place, **batch and slow it down** (debounce, fewer writes), raise refresh interval, and accept the merge cost knowingly.

The expert framing: this isn't a tuning problem, it's a **data-modeling** one — Elasticsearch is optimized for append-mostly, read-heavy data, and high per-document mutation fights its storage engine at every level.

#### Q90. [Theory] How do shard allocation awareness, forced awareness, and allocation filtering keep a cluster surviving a zone failure, and how do they interact with replica placement?

To survive losing a whole rack/availability zone, you must guarantee that a primary and **all** its replicas never live in the same failure domain. Three mechanisms govern this:

- **Allocation awareness** — you tag nodes with an attribute (e.g., `node.attr.zone: a|b|c`) and set `cluster.routing.allocation.awareness.attributes: zone`. The allocator then **spreads copies of each shard across zones**, so a replica is placed in a different zone than its primary. On losing a zone, every shard still has a copy elsewhere.
- **Forced awareness** — `cluster.routing.allocation.awareness.force.zone.values: a,b,c` tells the cluster the full set of zones up front, so when a zone is **down it does not over-replicate** the missing copies onto the surviving zones (which would double their load and then thrash when the zone returns). It waits for the zone to come back instead.
- **Allocation filtering** (`cluster.routing.allocation.include/exclude/require.*`) — pins or bans shards from specific nodes/attributes, used for tier movement (hot→warm), decommissioning a node gracefully (drain by excluding it), or keeping certain indices on certain hardware.

```
zones a,b,c, 1 replica:  P0(a) R0(b)   P1(c) R1(a)  ...
lose zone a → copies in b/c serve; forced awareness avoids piling rebuilds onto b,c
```

Interaction with replica count: awareness can only spread as many copies as you have zones — with **2 zones and `number_of_replicas: 1`** you get one copy per zone (survives one zone); surviving the loss of any one of **3 zones** while keeping full redundancy may need `number_of_replicas: 2`. The senior design point: zone resilience is a joint choice of **replica count, awareness attributes, and forced awareness**, and getting forced awareness wrong is what turns a single-zone outage into a cluster-wide overload cascade.

## 🧩 Extended Questions — Set 2: Practical scenarios, troubleshooting & coding

### 🟢 — extended

#### Q91. [Practical] Your cluster health is `yellow`. What does that mean and how do you diagnose why?

`yellow` means **all primary shards are allocated, but at least one replica is unassigned**. Data is fully searchable and writable — you have not lost data — but you have lost redundancy (a node failure now could cause data loss or downtime). This is distinct from `red` (a primary is unassigned, so part of your data is missing).

Diagnose with the **cluster allocation explain** API, which tells you the exact reason a specific shard cannot be allocated:

```json
GET /_cluster/allocation/explain
{ "index": "products", "shard": 0, "primary": false }
```

Common causes and fixes:

- **Single-node cluster** — a replica can never sit on the same node as its primary, so on one node every replica is unassigned. This is the most common cause in dev. Fix: add a node, or set `number_of_replicas: 0`.
- **`number_of_replicas` exceeds available nodes** — e.g., 2 replicas but only 2 nodes. Reduce replicas or add nodes.
- **Disk watermark hit** — a node above the high watermark (default 90%) won't accept new shards. Free disk or adjust `cluster.routing.allocation.disk.watermark.*`.
- **Allocation filtering / awareness rules** preventing placement.

```json
PUT /products/_settings
{ "index": { "number_of_replicas": 0 } }
```

The workflow is always the same: `GET _cluster/health?level=indices` to find the affected index, then `_cluster/allocation/explain` to get the machine-readable reason — never guess.

#### Q92. [Practical] A `term` query for a status value returns zero hits even though you know the documents exist. How do you debug it?

This is the single most common "no results" bug, and the cause is almost always an **analysis mismatch**: you ran a `term` (exact, non-analyzed) query against a field that was indexed as analyzed `text`, which lowercased and tokenized the value.

Suppose you indexed `"status": "Active"` and the field is dynamically mapped as `text`. The inverted index stores the term `active` (lowercased), but `term` does **not** analyze your input, so it searches for the literal `Active` and finds nothing.

Debugging steps:

1. **Check the mapping** — `GET /orders/_mapping`. If `status` is `text` (or `text` with a `.keyword` sub-field), that's the smell.
2. **Check the stored terms** with `_analyze`:

```json
POST /orders/_analyze
{ "field": "status", "text": "Active" }
```

If it returns `active`, the stored term is lowercased.

3. **Fix options:**
   - Query the `keyword` sub-field instead: `{ "term": { "status.keyword": "Active" } }`.
   - Or use a `match` query (which analyzes): `{ "match": { "status": "Active" } }`.
   - Better long-term: remodel `status` as a pure `keyword` field, since it's an enum you filter/aggregate on, not free text.

```json
{ "query": { "term": { "status.keyword": "Active" } } }
```

The general rule: `term` for `keyword`/numeric/date, `match` for `text`. When in doubt, `_analyze` reveals the truth.

#### Q93. [Practical] Indexing fails with `cluster_block_exception ... index read-only / allow delete (api)`. What happened and how do you recover?

The cluster ran out of disk and tripped the **flood-stage watermark** (default 95% of disk). To protect itself, Elasticsearch automatically set the affected indices to **read-only** (`index.blocks.read_only_allow_delete: true`), so writes are rejected even after you free space — the block does **not** clear automatically in older versions.

Recovery procedure:

1. **Free disk** on the affected node(s) — delete old indices, expand the volume, or move shards off the node.
2. **Manually clear the block** on the affected indices (this is the step people miss):

```json
PUT /_all/_settings
{ "index.blocks.read_only_allow_delete": null }
```

3. Prevent recurrence: implement **ILM** to roll over and delete old time-series indices, monitor disk, and consider raising node count. The three watermarks to know:

```json
PUT /_cluster/settings
{
  "transient": {
    "cluster.routing.allocation.disk.watermark.low":  "85%",
    "cluster.routing.allocation.disk.watermark.high": "90%",
    "cluster.routing.allocation.disk.watermark.flood_stage": "95%"
  }
}
```

`low` stops allocating new shards to the node, `high` starts moving shards off it, `flood_stage` makes indices read-only. Newer versions auto-release the block once disk drops below the high watermark, but always verify and have disk monitoring so you never reach flood stage.

#### Q94. [Coding] Write a Java snippet using the Java API Client that performs a bulk index of a list of documents and inspects per-item errors.

A bulk request can return HTTP 200 overall while individual items failed (e.g., a mapping conflict on one doc). You must iterate the response items, never just check the HTTP status.

```java
List<Product> products = loadProducts();

BulkRequest.Builder br = new BulkRequest.Builder();
for (Product p : products) {
    br.operations(op -> op
        .index(idx -> idx
            .index("products")
            .id(p.id())
            .document(p)));
}

BulkResponse response = client.bulk(br.build());

if (response.errors()) {
    for (BulkResponseItem item : response.items()) {
        if (item.error() != null) {
            System.err.printf("Failed id=%s reason=%s%n",
                item.id(), item.error().reason());
            // route to a dead-letter queue / retry with backoff
        }
    }
} else {
    System.out.printf("Indexed %d docs in %dms%n",
        products.size(), response.took());
}
```

For continuous ingest, prefer the higher-level **`BulkIngester`** helper, which buffers operations and auto-flushes by count, byte size, or interval, and lets you attach a listener for the same error inspection:

```java
BulkIngester<Void> ingester = BulkIngester.of(b -> b
    .client(client)
    .maxOperations(2000)        // flush every 2000 ops
    .maxSize(5_000_000)         // ...or every 5 MB
    .flushInterval(2, TimeUnit.SECONDS));

for (Product p : products) {
    ingester.add(op -> op.index(idx -> idx
        .index("products").id(p.id()).document(p)));
}
ingester.close();   // flushes remaining
```

#### Q95. [Coding] Write the Query DSL for "active products under $100, matching 'wireless headphones', boosting the brand Acme, sorted by price."

This exercises the `bool` composition: scored full-text in `must`, cheap cacheable constraints in `filter`, an optional boost in `should`, plus an explicit sort.

```json
GET /products/_search
{
  "size": 20,
  "query": {
    "bool": {
      "must": [
        { "match": { "name": "wireless headphones" } }
      ],
      "filter": [
        { "term":  { "status": "active" } },
        { "range": { "price": { "lt": 100 } } }
      ],
      "should": [
        { "term": { "brand": { "value": "Acme", "boost": 2.0 } } }
      ]
    }
  },
  "sort": [
    { "price": "asc" },
    "_score"
  ]
}
```

Note the subtlety: once you add an explicit `sort` on `price`, results are ordered by price, **not** relevance — `_score` becomes a tiebreaker only. If you want relevance-primary ordering with price as a secondary signal, drop the price sort and instead fold price into scoring (a `function_score` decay), because mixing a hard sort with a `should` boost gives the boost no effect on ordering. This trade-off (sort vs. score) is a frequent interview clarification.

#### Q96. [Practical] You indexed a document but `GET /index/_search` doesn't return it, yet `GET /index/_doc/id` does. Why?

Because **get-by-id is real-time but search is near-real-time**. When you index a document it lands in the in-memory buffer and the translog immediately; `GET _doc/id` can read it straight from the translog, so it's visible at once. Search, however, only sees documents that have been **refreshed** into a searchable Lucene segment, which happens on the `refresh_interval` (default 1 second) — so for up to ~1s after indexing, the doc is gettable but not searchable.

To make it searchable immediately (e.g., in a test), force a refresh:

```json
POST /products/_doc/1?refresh=true
{ "name": "Wireless Headphones", "price": 79.99 }
```

`?refresh=true` refreshes the shard before returning; `?refresh=wait_for` blocks until the next scheduled refresh (cheaper under load). Both are fine for tests or rare must-be-visible-now writes, but doing `refresh=true` on every write **destroys indexing throughput** by creating a tiny segment per document and triggering constant merging. In production, accept the ~1s delay or use `wait_for` sparingly.

#### Q97. [Practical] How do you find out why a query is slow, step by step?

Use a layered toolkit, cheapest first:

1. **`profile: true`** — returns a per-shard timing tree showing how long each query component (`build_scorer`, `next_doc`, `score`, plus aggregation phases) took. This pinpoints which clause or agg is expensive.

```json
GET /products/_search
{
  "profile": true,
  "query": { "bool": { "must": [ { "match": { "description": "noise cancelling" } } ] } }
}
```

2. **Search slow log** — capture real production slow queries with their source:

```json
PUT /products/_settings
{ "index.search.slowlog.threshold.query.warn": "500ms",
  "index.search.slowlog.threshold.fetch.warn": "200ms" }
```

3. **Check the cheap structural wins:** are yes/no constraints in `filter` (cached) rather than `must`? Are you sorting/aggregating on a `keyword`/numeric (doc values) not a `text` field? Is `from` deep (use `search_after`)? Are there too many shards causing fan-out overhead, or too few causing hot shards?

4. **`_nodes/hot_threads`** — if the whole cluster is slow, sample what the CPUs are actually doing.

The mental model: `profile` for "which part of *this* query is slow," slow log for "which queries are slow in production," hot_threads for "what is the cluster burning CPU on."

#### Q98. [Practical] A bulk indexing job is failing intermittently with `429 Too Many Requests` / `EsRejectedExecutionException`. What's happening and how do you fix it?

You are saturating the **write thread pool**, whose bounded queue has filled up; Elasticsearch sheds load by rejecting requests with HTTP 429 rather than running out of memory. This is **backpressure**, not a bug — the cluster is telling you it can't keep up at this rate.

Fixes, from the client side first:

- **Respect the backpressure: retry rejected items with exponential backoff and jitter.** A 429 means "slow down and retry," not "fail." Re-submit only the rejected items from the bulk response, not the whole batch.
- **Reduce concurrency / batch size.** Fewer parallel bulk threads or smaller batches lower the queue pressure. There's a throughput sweet spot — more threads past it just cause rejections.
- **During a big initial load**, set `number_of_replicas: 0` (replicate after) and raise `refresh_interval` to `30s` or `-1`, then restore them. Replication and frequent refresh both compete for the same resources.

Server side, only if the hardware genuinely supports more: the write queue and pool are bounded for a reason; **raising `thread_pool.write.queue_size` mostly hides the problem and risks OOM** — prefer scaling out (more data nodes / shards) so writes spread across more primaries. The correct production posture is a client that backs off on 429 plus right-sized batching.

### 🟡 — extended

#### Q99. [Coding] Write an Update By Query that conditionally modifies documents using a Painless script, and explain the conflict handling.

Update By Query runs a search then applies a script to every match. It operates on a **snapshot** with internal versioning, so a concurrent change to a matched doc causes a version conflict.

```json
POST /products/_update_by_query?conflicts=proceed&slices=auto
{
  "query": {
    "bool": {
      "filter": [
        { "term":  { "brand": "Acme" } },
        { "range": { "price": { "lt": 100 } } }
      ]
    }
  },
  "script": {
    "lang": "painless",
    "source": """
      if (ctx._source.discount == null) {
        ctx._source.discount = 0;
      }
      ctx._source.price = Math.round(ctx._source.price * (1 - params.pct) * 100) / 100.0;
      ctx._source.discount = params.pct;
    """,
    "params": { "pct": 0.10 }
  }
}
```

Key points:

- `conflicts=proceed` makes it **skip** docs that changed since the snapshot (instead of aborting); without it the whole job stops on the first conflict. The response reports `version_conflicts` so you know how many were skipped — re-run if needed.
- `slices=auto` parallelizes the job across shards for throughput; throttle a heavy job with `requests_per_second` to avoid starving live traffic.
- It is **not transactional**: a partial failure leaves partial updates. For idempotency, write scripts that are safe to re-apply (the `discount == null` guard above prevents double-discounting on a re-run).

#### Q100. [Coding] Write a nested aggregation: top 5 brands by document count, and within each, the average and 95th-percentile price.

This nests a metric agg (and a percentiles agg) inside a `terms` bucket agg.

```json
GET /products/_search
{
  "size": 0,
  "query": { "term": { "in_stock": true } },
  "aggs": {
    "top_brands": {
      "terms": { "field": "brand", "size": 5, "order": { "_count": "desc" } },
      "aggs": {
        "avg_price": { "avg": { "field": "price" } },
        "p95_price": { "percentiles": { "field": "price", "percents": [95] } }
      }
    }
  }
}
```

`"size": 0` suppresses hits so you pay only for the aggregation. Watch two correctness details: (1) `terms` counts are **approximate on a sharded index** — each shard returns its local top buckets, so a globally top-5 brand could be missed if it's not top-5 on enough shards; raise `shard_size` (the per-shard candidate count, default `size * 1.5 + 10`) to tighten accuracy, and check `doc_count_error_upper_bound`. (2) `percentiles` uses the TDigest approximation, so the 95th percentile is an estimate, not exact — acceptable for dashboards, not for billing.

#### Q101. [Practical] You need to reindex a 500M-document index into a new mapping with zero downtime. Walk through the full procedure.

The zero-downtime pattern is **alias swap + reindex + dual-write catch-up**:

1. **Read/write through an alias.** Production should already do this; clients use alias `products`, currently pointing at `products_v1`.
2. **Create `products_v2`** with the corrected mapping. For the bulk reindex, optimize the destination: `number_of_replicas: 0` and `refresh_interval: -1` so you're not paying for replication and refresh during the copy.
3. **Reindex with throttling and slicing** so you don't crush live traffic:

```json
POST /_reindex?wait_for_completion=false&slices=auto
{
  "source": { "index": "products_v1", "size": 5000 },
  "dest":   { "index": "products_v2" }
}
```

This returns a **task id**; monitor it via `GET /_tasks/<id>`.

4. **Catch up writes** that occurred during the (long) reindex. Either dual-write to both indices during the migration window, or re-run reindex with a range filter on an `updated_at` field to copy only the deltas since the reindex started.
5. **Restore** `products_v2` settings: set replicas back to the target count and `refresh_interval` back to `1s`; optionally `force_merge` if it's now read-mostly.
6. **Atomically swap the alias** in a single request so clients never see a gap:

```json
POST /_aliases
{ "actions": [
  { "remove": { "index": "products_v1", "alias": "products" } },
  { "add":    { "index": "products_v2", "alias": "products" } }
] }
```

7. **Verify** (doc counts, spot-check queries), then delete `products_v1`. Keep it around for a rollback window first.

#### Q102. [Coding] Implement deep export of an entire index in Java using PIT + `search_after`.

For exporting more than 10k documents consistently, the modern approach is **Point-in-Time (PIT) + `search_after`**: the PIT freezes a snapshot of segments so paging is consistent despite ongoing indexing, and `search_after` pages with constant cost at any depth.

```java
// 1. Open a PIT
OpenPointInTimeResponse pit = client.openPointInTime(p -> p
    .index("products")
    .keepAlive(t -> t.time("2m")));
String pitId = pit.id();

List<FieldValue> searchAfter = null;
try {
    while (true) {
        final List<FieldValue> after = searchAfter;
        SearchResponse<Product> resp = client.search(s -> {
            s.size(1000)
             .pit(p -> p.id(pitId).keepAlive(t -> t.time("2m")))
             .sort(so -> so.field(f -> f.field("_shard_doc").order(SortOrder.Asc)))
             .query(q -> q.matchAll(m -> m));
            if (after != null) s.searchAfter(after);
            return s;
        }, Product.class);

        var hits = resp.hits().hits();
        if (hits.isEmpty()) break;

        hits.forEach(h -> process(h.source()));
        searchAfter = hits.get(hits.size() - 1).sort();   // cursor for next page
    }
} finally {
    // 2. Always close the PIT to free resources
    client.closePointInTime(c -> c.id(pitId));
}
```

Notes: sorting on `_shard_doc` is the cheapest total tiebreaker for pure export (no scoring); always include a tiebreaker so the sort is total; and **always close the PIT in a `finally`** — leaked PITs hold open segments and prevent merges from reclaiming disk.

#### Q103. [Practical] Search results suddenly seem "stale" — users report newly created documents don't appear for a while. What are the possible causes?

"Stale search" almost always traces to the **refresh pipeline** or a **replica lag**:

1. **Refresh interval was raised.** Someone set `index.refresh_interval` to `30s` (or `-1`) — common during a bulk load — and forgot to reset it. New docs aren't searchable until the next refresh. Check `GET /index/_settings` and restore `1s` (or `null` for default).
2. **Heavy indexing starving refresh / merges falling behind** — under extreme write load refreshes can be delayed. Look at indexing rate, merge throttling, and thread pool rejections.
3. **Replica lag** — a search served by a replica that hasn't yet replicated the latest writes returns slightly older data than one served by the primary. Two consecutive searches can disagree (eventual consistency for search). For a specific doc you need now, route the read with `?preference=_primary` or get it by id (real-time).
4. **A frozen / searchable-snapshot tier** index won't reflect new writes at all — it's read-only by design.

Diagnosis order: check `refresh_interval` first (the usual culprit), then indexing pressure / thread pool rejections, then whether reads might be hitting lagging replicas.

#### Q104. [Coding] Write a `date_histogram` over the last 90 days bucketed weekly, with a moving average pipeline aggregation to smooth a metric.

Pipeline aggregations consume the output of other aggregations. A `moving_fn` (the modern moving-average) runs over the ordered buckets of a `date_histogram`.

```json
GET /orders/_search
{
  "size": 0,
  "query": { "range": { "created": { "gte": "now-90d/d" } } },
  "aggs": {
    "weekly": {
      "date_histogram": {
        "field": "created",
        "calendar_interval": "week",
        "min_doc_count": 0
      },
      "aggs": {
        "revenue": { "sum": { "field": "amount" } },
        "revenue_smoothed": {
          "moving_fn": {
            "buckets_path": "revenue",
            "window": 4,
            "script": "MovingFunctions.unweightedAvg(values)"
          }
        }
      }
    }
  }
}
```

Details that matter: `calendar_interval: week` is **calendar/DST aware** (use `fixed_interval` only for exact durations like `90m`); `min_doc_count: 0` fills empty weeks with zero buckets so the moving average isn't distorted by gaps; `buckets_path: "revenue"` wires the pipeline agg to the sibling metric; and `window: 4` gives a 4-week trailing average. Pipeline aggs run on the coordinating node *after* the histogram, so they're cheap relative to the underlying scan.

#### Q105. [Practical] How do you implement search that tolerates typos (fuzzy matching) without wrecking performance or precision?

Use **fuzziness**, but scope it deliberately. The `match` query supports a `fuzziness` parameter based on Levenshtein edit distance:

```json
{
  "query": {
    "match": {
      "name": {
        "query": "hedphones",
        "fuzziness": "AUTO",
        "prefix_length": 1,
        "max_expansions": 50
      }
    }
  }
}
```

The tuning levers, and why each matters for performance/precision:

- **`fuzziness: AUTO`** scales allowed edits by term length (0 edits for 1–2 chars, 1 for 3–5, 2 for 6+). This avoids letting tiny words match anything, which raw `fuzziness: 2` would do.
- **`prefix_length`** requires the first N characters to match exactly. This is the biggest performance lever — fuzzy matching is expensive because it expands a term into all terms within edit distance, and anchoring the prefix slashes that expansion and improves precision (you rarely want the first letter to be a typo).
- **`max_expansions`** caps how many terms a fuzzy term expands into, bounding cost on high-cardinality fields.

For best relevance, combine an exact-match clause (boosted) with a fuzzy fallback in a `bool` `should`, so perfect matches rank above corrected ones. For autocomplete-grade typo tolerance, `search_as_you_type` or an `edge_ngram` field plus fuzziness is the usual pairing. Avoid fuzziness on huge `text` bodies — it's designed for short fields like names and titles.

#### Q106. [Practical] You're seeing high heap usage and circuit breaker `parent` trips. How do you investigate and remediate?

A `circuit_breaking_exception` on the **parent** breaker means total tracked memory crossed ~95% of heap and Elasticsearch aborted the request to avoid an OOM crash. The fix is to find what's consuming heap, not to raise the breaker limit (that just trades a clean rejection for a fatal OOM).

Investigation:

1. **`GET /_nodes/stats/breaker`** — see which breaker is highest (`fielddata`, `request`, `parent`).
2. **`GET /_cat/fielddata?v`** — fielddata is a classic culprit: sorting/aggregating on a **`text`** field forces field data into heap. The fix is to never agg/sort on `text` — use a `keyword` sub-field (which uses on-disk doc values, not heap).
3. **`GET /_nodes/stats/indices/segments`** and shard count — too many shards/segments inflate baseline heap (each shard/segment has fixed overhead). Over-sharding is a frequent root cause.
4. **Large aggregations** — a high-cardinality `terms` agg (e.g., group by a unique id) or huge `size` builds large structures in `request` memory.

Remediation: remap to use `keyword`/doc values, reduce shard count (over-sharded clusters), bound aggregation cardinality (`size`, `composite` agg for paging through buckets), add nodes/heap, and clear fielddata if needed. The principle: **doc values (on disk) over fielddata (on heap)**, and treat breaker trips as a signal to reduce memory pressure, not raise the ceiling.

#### Q107. [Coding] Write a `multi_match` query with `cross_fields` for a person search across first/last name fields, and explain when to use it over `best_fields`.

```json
GET /people/_search
{
  "query": {
    "multi_match": {
      "query": "alice smith",
      "type": "cross_fields",
      "fields": ["first_name", "last_name"],
      "operator": "and"
    }
  }
}
```

`cross_fields` treats the listed fields as **one combined field** for term matching, so the query "alice smith" can match a document where `first_name=Alice` and `last_name=Smith` even though no single field contains both terms — and with `operator: and`, every term must be found *somewhere* across the fields. This is exactly right for names, addresses, or any entity whose tokens are spread across structured sub-fields.

Contrast with the default **`best_fields`**, which scores each field independently and takes the single best-matching field's score — great for "find the one field that best matches the whole query" (like a title or body), but it would *not* naturally reward a doc that has "alice" in one field and "smith" in another. Use `best_fields` for "most relevant single field" (descriptions), `most_fields` for the same text analyzed multiple ways (e.g., stemmed + exact), and `cross_fields` for entities split across fields. A caveat: `cross_fields` works best when the fields share an analyzer (so term statistics blend cleanly).

### 🟠 — extended

#### Q108. [Practical] A single shard has become a hotspot — one node is at high CPU while others idle. How do you diagnose and fix it?

A hotspot means traffic or data is skewed onto one shard/node instead of spreading evenly. Diagnose:

1. **`GET /_cat/shards?v&s=docs:desc`** — is one shard far larger than its peers? That points to **custom routing skew**: if you route by `customer_id` and one customer has 10x the data, all their docs (and their queries) hit one shard.
2. **`GET /_nodes/hot_threads`** on the busy node — see whether it's burning CPU on search, merge, or a specific query.
3. **Check for a "fat" routing key** or a single index with too few primaries relative to its query volume (every query hits the same one shard).

Fixes depend on the cause:

- **Routing skew:** use `index.routing_partition_size` to spread a single routing value across several shards (it changes routing to `hash(routing) % partition_size` offset within a window), or stop custom-routing the whale tenants and give them dedicated indices.
- **Too few primaries for the load:** `_split` the index into more primaries (requires the original to have been created with a multiple, or use the split factor rules) so queries fan out and parallelize.
- **A single hot index** (today's log index taking all writes): that's expected for time-series; ensure ILM rollover and that the write index has enough primaries and replicas to spread read load.

The senior point: even data distribution is a *design-time* choice (shard count, routing strategy), so a hotspot usually means the routing/sharding model doesn't match the access pattern — fixing it often means reindexing with a better key.

#### Q109. [Practical] Relevance is poor: irrelevant documents rank above good ones for an important query. Walk through how you'd systematically improve ranking.

Treat relevance tuning as an empirical, measured process, not guesswork:

1. **Explain the bad result.** Run the query with `"explain": true` (or `_explain` on the specific doc) to see the exact BM25 term contributions. Often you'll find a long document scoring high due to a single repeated term, or a field-length normalization quirk.
2. **Fix the obvious modeling issues** — wrong analyzer, missing `keyword` sub-field, querying the wrong fields, or `text` vs `keyword` mismatch.
3. **Apply field boosts** (`name^3`) so matches in important fields outweigh matches in the body.
4. **Add structured signals with `function_score`/`script_score`** — recency decay, popularity/sales, in-stock — so business signals blend with text relevance:

```json
{
  "query": {
    "function_score": {
      "query": { "multi_match": { "query": "wireless headphones", "fields": ["name^3", "description"] } },
      "functions": [
        { "gauss": { "created": { "origin": "now", "scale": "30d", "decay": 0.5 } } },
        { "field_value_factor": { "field": "sales", "modifier": "log1p", "factor": 1.2 } }
      ],
      "score_mode": "sum",
      "boost_mode": "multiply"
    }
  }
}
```

5. **Consider hybrid search** — combine BM25 with kNN vector retrieval and fuse with RRF for semantic recall on hard queries.
6. **Measure, don't eyeball.** Build a judgment set and use the **Ranking Evaluation API** (`_rank_eval`) to compute precision@k / nDCG before and after each change, so you know you actually improved and didn't regress other queries. A two-stage **`rescore`** (cheap retrieval, expensive rerank on top-N — e.g., a learned model or cross-encoder) is the canonical way to apply costly ranking only where it matters.

The discipline — explain, model, boost, blend signals, then **evaluate with metrics** — is what separates real relevance engineering from query whack-a-mole.

#### Q110. [Practical] During a rolling upgrade / node restart, recovery is taking hours and saturating the network. How do you make it faster and safer?

Slow recovery during restarts is usually because Elasticsearch is **copying full shards** when it could be doing a fast, incremental recovery — or because recovery throttling is too conservative for your hardware.

Make restarts safe and fast:

1. **Disable allocation before a planned restart** so the cluster doesn't immediately start rebuilding replicas for the node you're about to bring right back:

```json
PUT /_cluster/settings
{ "persistent": { "cluster.routing.allocation.enable": "primaries" } }
```

Restart the node, then re-enable `"all"`. This avoids a needless full-shard rebuild that the `node_left.delayed_timeout` (default 1m) is also designed to prevent.

2. **Sync/flush before restart** (legacy: `_flush/synced`; modern versions use sequence-number-based peer recovery automatically) so unchanged shards recover from local data plus a small operations replay from the translog/Lucene, instead of a full copy.

3. **Tune recovery throughput** if network/disk has headroom:

```json
PUT /_cluster/settings
{ "transient": {
  "indices.recovery.max_bytes_per_sec": "200mb",
  "cluster.routing.allocation.node_concurrent_recoveries": 4
} }
```

But raise these carefully — too aggressive starves live search.

4. **Use the right tier strategy**: searchable-snapshot (frozen/cold) indices recover from object storage and don't need full peer recovery.

The key insight is **sequence-number-based recovery**: when a node returns quickly, Elasticsearch compares global/local checkpoints and replays only the missing operations rather than re-copying the whole shard — so the biggest win is simply not triggering full rebuilds (disable allocation, delayed timeout) for transient restarts.

#### Q111. [Practical] Your time-series logging cluster's disk fills up every few weeks and queries on old data are slow. Design a sustainable lifecycle.

This is the textbook case for **ILM (Index Lifecycle Management) with a hot-warm-cold-frozen architecture and data streams**:

- **Data stream + rollover:** write to a data stream backed by rolling indices; ILM **rolls over** to a new backing index when it hits a size/age/doc-count threshold (e.g., 50 GB or 1 day), so each index is right-sized and the write index is always small and fast.
- **Hot tier:** newest, most-queried data on fast SSD nodes with replicas for HA and read throughput.
- **Warm tier:** after N days, move to cheaper nodes; often `force_merge` to one segment (read-only now) and reduce replicas, cutting heap and disk.
- **Cold / frozen tier:** older data moves to **searchable snapshots** in object storage (S3/GCS); the frozen tier keeps almost nothing on local disk and fetches from the snapshot on demand — slow but extremely cheap, ideal for rarely-queried compliance data.
- **Delete phase:** ILM deletes indices past retention automatically.

```json
PUT /_ilm/policy/logs-policy
{
  "policy": { "phases": {
    "hot":   { "actions": { "rollover": { "max_primary_shard_size": "50gb", "max_age": "1d" } } },
    "warm":  { "min_age": "7d",  "actions": { "forcemerge": { "max_num_segments": 1 }, "set_priority": { "priority": 50 } } },
    "cold":  { "min_age": "30d", "actions": { "searchable_snapshot": { "snapshot_repository": "s3-repo" } } },
    "delete":{ "min_age": "90d", "actions": { "delete": {} } }
  } }
}
```

This solves both problems at once: rollover + delete keeps disk bounded automatically (far cheaper than `delete_by_query`), and tiering puts cold data on cheap storage while keeping hot data fast. **Never purge time-series data with delete-by-query when you can drop/roll whole indices** — deleting whole indices is an O(1) metadata op, while delete-by-query rewrites segments.

#### Q112. [Coding] Implement an idempotent, ordering-safe sync from a primary DB into Elasticsearch using external versioning.

When syncing from a source of truth (via CDC/outbox), out-of-order or replayed events can overwrite newer data. Use the DB's monotonically increasing version (or a timestamp/LSN) as an **external version** so Elasticsearch rejects older writes.

```java
long dbVersion = event.getRowVersion();   // monotonically increasing per document

try {
    client.index(i -> i
        .index("products")
        .id(event.getId())                       // DB id as _id → idempotent overwrite
        .version(dbVersion)
        .versionType(VersionType.External)       // ES accepts only if dbVersion > stored
        .document(event.toDocument()));
} catch (ElasticsearchException ex) {
    if (ex.status() == 409) {
        // version_conflict: a newer version already indexed — safe to ignore
        log.debug("Skipping stale event id={} v={}", event.getId(), dbVersion);
    } else {
        throw ex;   // real error: retry / dead-letter
    }
}
```

Why this is correct and robust:

- **Idempotency:** using the DB id as `_id` means a replayed event overwrites the same doc instead of creating a duplicate.
- **Ordering safety:** `versionType=External` makes the write succeed only if `dbVersion > current stored version`, so a delayed/out-of-order event with an older version is rejected with **409** — which you treat as a benign skip, not a failure.
- **Deletes:** the same applies to deletes (`version` + `External`) so a stale delete can't resurrect or clobber a newer create.

This pattern (id as `_id`, external version from the source, 409-as-skip) is the foundation of any reliable DB→Elasticsearch projection, and it's what makes a full backfill safe to run concurrently with live CDC.

#### Q113. [Practical] How do you safely run a `force_merge`, and when does it backfire?

`force_merge` merges a shard down to a target number of segments (typically 1), which makes search faster (fewer segments to visit) and reclaims space from deleted docs. But it's a heavy, blocking-ish I/O operation with sharp edges:

```json
POST /logs-2026.06.29/_forcemerge?max_num_segments=1
```

Rules for using it safely:

- **Only force-merge read-only / no-longer-written indices.** This is the cardinal rule. If you force-merge an index still receiving writes, you create one giant segment that subsequent writes will never naturally merge again — and a single huge segment with accumulating deletes can't be efficiently cleaned, hurting performance long-term. ILM does this correctly in the **warm phase**, after rollover makes the index read-only.
- **It's expensive:** it rewrites the entire shard's data, consuming I/O and CPU; run it during off-peak and not across all indices simultaneously.
- **`max_num_segments=1` is the read-optimized target** for an archived index; you don't need to specify it for general merging — let the background tiered merge policy handle live indices.

It backfires when applied to a hot/written index (giant un-mergeable segment), when run cluster-wide at peak (I/O storm), or as a "fix" for a problem that's really over-sharding or fielddata. The right home for force-merge is an automated ILM warm-phase action on rolled-over, read-only time-series indices.

#### Q114. [Practical] A `nested` field aggregation is returning wrong/unexpected counts. What's the likely cause and fix?

The usual cause is **mixing nested and non-nested context** — either aggregating a nested field without entering its nested scope, or interpreting `doc_count` at the wrong level. Because `nested` documents are stored as **hidden separate Lucene documents**, counts there reflect *nested objects*, not parent documents, and you must explicitly cross the boundary with `nested`/`reverse_nested` aggregations.

For example, "how many products have at least one review with rating 5" is different from "how many 5-star reviews exist." To count distinct parents you must use `reverse_nested`:

```json
GET /products/_search
{
  "size": 0,
  "aggs": {
    "reviews": {
      "nested": { "path": "reviews" },
      "aggs": {
        "five_star": {
          "filter": { "term": { "reviews.rating": 5 } },
          "aggs": {
            "products_with_5star": {
              "reverse_nested": {}    // back to parent scope
            }
          }
        }
      }
    }
  }
}
```

Here `five_star.doc_count` is the number of 5-star **reviews** (nested docs), while `products_with_5star.doc_count` is the number of distinct **products** (parents) that have one. Forgetting `reverse_nested` is the classic bug — you report review counts where you meant product counts. The other subtlety: a `nested` query/agg only matches *within a single nested object*, so "a review by Alice AND rating 5" must be expressed inside one nested query to require both conditions hold for the *same* review.

#### Q115. [Coding] Write a kNN vector search request combined with a metadata filter, and a hybrid (kNN + BM25) version using RRF.

Pure kNN with a pre-filter restricts the approximate nearest-neighbor search to documents matching the filter (the filter is applied during the HNSW graph traversal):

```json
POST /products/_search
{
  "knn": {
    "field": "description_vector",
    "query_vector": [0.12, -0.04, 0.88, "...384 dims..."],
    "k": 10,
    "num_candidates": 100,
    "filter": { "term": { "in_stock": true } }
  }
}
```

`k` is how many neighbors to return; `num_candidates` is how many to explore per shard before picking the top `k` (higher = better recall, slower). The hybrid version retrieves both lexically (BM25) and semantically (kNN) and fuses the two ranked lists with **Reciprocal Rank Fusion**, which combines by rank position rather than incomparable score scales:

```json
POST /products/_search
{
  "retriever": {
    "rrf": {
      "retrievers": [
        { "standard": { "query": { "match": { "description": "noise cancelling headphones" } } } },
        { "knn": { "field": "description_vector",
                   "query_vector": [0.12, -0.04, 0.88, "..."],
                   "k": 50, "num_candidates": 200 } }
      ],
      "rank_window_size": 100,
      "rank_constant": 60
    }
  }
}
```

RRF is the recommended default for hybrid because it needs no score normalization between BM25 (unbounded) and cosine similarity (bounded), and it robustly surfaces documents that *either* method ranks highly — giving you BM25's exact-term precision plus vector search's semantic recall. For top relevance you'd then add a `rescore`/reranker over the fused top-N.

### 🔴 — extended

#### Q116. [Practical] Indexing throughput collapsed and `GET _nodes/hot_threads` shows threads stuck in merge. Diagnose the merge-throttling problem and fix it.

When write rate exceeds the cluster's ability to merge segments, Elasticsearch applies **indexing back-pressure / merge throttling**: it deliberately slows incoming indexing so the merge process can catch up, preventing an unbounded explosion of small segments (which would make search progressively slower). Seeing merge threads dominate `hot_threads` plus falling indexing rate is the signature.

Root-cause analysis and fixes:

1. **You're creating too many small segments** — usually from `refresh=true`/tiny refresh interval per write, or many small bulk requests. Raise `refresh_interval` (e.g., `30s`) and use larger bulk batches so each refresh produces a bigger segment, reducing merge pressure.
2. **Disk I/O is the bottleneck.** Merges are I/O-bound; on slow disks the merge throttle (`indices.store.throttle` / store-level rate limiting historically; now governed by the merge scheduler) can't keep up. The real fix is faster storage (NVMe SSD) — spinning disks cannot sustain heavy ES indexing.
3. **Merge scheduler concurrency** — on fast SSDs you can raise `index.merge.scheduler.max_thread_count`; on a single spinning disk you *lower* it to 1 to avoid seek thrashing. The default auto-tunes from detected hardware, so this matters mostly on misconfigured storage.
4. **Too many replicas during bulk load** double the write+merge work; set replicas to 0 during the initial load.

The deep point: merge throttling is **self-protection, not a bug** — the cluster is trading write latency to keep the segment count (and thus search latency) sane. The durable fix is to reduce segment churn (bigger refreshes/bulks) and ensure storage can sustain merge I/O; tuning scheduler threads is secondary.

#### Q117. [Practical] You must execute a query that hits hundreds of shards (cross-index search over a year of daily indices) and it's slow or trips `too_many_clauses`/`search_phase_execution_exception`. How do you scale it?

Searching across hundreds of shards has a fan-out cost: the coordinating node must contact every shard, gather and merge top-k from each, and the per-shard fixed overhead dominates even when most shards have no matching data.

Scaling strategies:

1. **Prune shards before they're searched.** If queries filter on the time field, the coordinator can **skip shards** whose min/max for that field can't match the range (`can_match` pre-filter phase, automatic when `pre_filter_shard_size` is exceeded). Ensuring queries always carry a time-range filter is the single biggest win — it turns "search 365 indices" into "search the 7 that overlap the range."
2. **Use ILM-sized indices, not thousands of tiny daily shards.** Over-sharding makes every cross-index query pay hundreds of fixed costs. Roll over by size so you have fewer, fuller shards; consider weekly/monthly indices for old data.
3. **`async_search`** for genuinely long-running analytical queries so the client isn't blocked and partial results stream in.
4. **Raise `indices.query.bool.max_clause_count`** only if a query legitimately expands to many clauses (e.g., a huge `terms` set) — but prefer a `terms` lookup or restructuring, since giant boolean expansions are a smell.
5. **Cross-cluster search** to spread the load across clusters, and route analytical traffic to dedicated coordinating-only nodes so it doesn't starve the data nodes.

The architectural lesson: at the billions-of-docs / hundreds-of-shards scale, **performance is dominated by how many shards a query touches**, so the design goal is to make each query touch as few shards as possible via time-based routing/pruning and right-sized indices — not to throw hardware at a fan-out you could have avoided.

#### Q118. [Practical] Cross-cluster replication (CCR) followers are falling behind the leader. How do you diagnose and what consistency guarantees still hold?

CCR is **asynchronous**: a follower index pulls operations from the leader using the leader's **sequence numbers** and **global checkpoint**, and applies them in order. Falling behind means the follower's replay can't keep up with the leader's write rate, or the network/leader is the bottleneck.

Diagnose:

1. **`GET /<follower>/_ccr/stats`** — look at `leader_global_checkpoint` vs `follower_global_checkpoint`; the gap is the lag. Also check `operations_read`, `operations_written`, and any `read_exceptions`.
2. **Network throughput** between clusters and the follower's indexing capacity (the follower must *re-index* every operation — it's doing real write work, not a file copy).
3. **Retention lease** health — the follower holds a **retention lease** on the leader so the leader keeps the soft-deleted operations the follower still needs; if the follower lags past the lease/retention, the leader may discard operations and the follower must **re-bootstrap** from a full remote recovery.

Tuning: raise `max_read_request_operation_count`, `max_outstanding_read_requests`, and `max_write_request_operation_count` so more operations are in flight; ensure the leader's `index.soft_deletes.retention_lease.period` is long enough to tolerate expected lag.

Consistency guarantees that **still hold despite the lag**: CCR is **eventually consistent** but **order-preserving** — the follower applies operations in leader sequence-number order, so it always represents a *consistent prefix* of the leader's history (never a torn/out-of-order state). It just represents an *older* point in time. That makes it safe for disaster-recovery failover and read scaling in another region, but **not** for read-your-writes across regions. The global checkpoint is what makes this prefix-consistency guarantee precise.

#### Q119. [Practical] After a network partition heals, you discover two documents with the same `_id` had divergent updates. How does Elasticsearch prevent split-brain data corruption, and what could still go wrong at the application layer?

At the **cluster level**, Elasticsearch prevents split-brain via **quorum-based coordination**: only the side of a partition with a **majority of master-eligible nodes** can elect a master and accept cluster-state changes; the minority side blocks writes. So you cannot have two masters independently accepting conflicting shard allocations — the control plane is **CP**.

At the **document level**, each write carries `_seq_no` + `_primary_term`. A replica only accepts an operation consistent with the primary's sequence; if a stale primary (one that was isolated) tries to act after a new primary was elected with a higher `_primary_term`, its operations are **rejected** because the term is out of date. This sequence-number + primary-term scheme is what guarantees a healed cluster converges on one authoritative history per shard rather than silently keeping divergent copies.

What can *still* go wrong is at the **application layer**, not Elasticsearch's:

- **Lost updates from read-modify-write races** if the app doesn't use optimistic concurrency. Two clients both read v5, both write v6 — the second overwrites the first. The fix is to pass `if_seq_no`/`if_primary_term` from the read so the write is a compare-and-set and the loser gets a 409 to retry:

```json
PUT /products/_doc/1?if_seq_no=362&if_primary_term=2
{ "name": "Headphones", "price": 89.99 }
```

- **Dual-write divergence** if the app writes to both a DB and ES non-atomically and a partition interrupts it — only fixable with an outbox/CDC pattern and external versioning, not by Elasticsearch.

So: Elasticsearch guarantees no split-brain at the cluster level and a single converged history per shard via primary terms; the application must still use optimistic concurrency (`if_seq_no`/`if_primary_term`) and a safe sync pattern to avoid logical lost updates.

#### Q120. [Coding] Demonstrate optimistic concurrency control end-to-end in Java: read a document, modify it, and conditionally write it back, handling the conflict.

```java
// 1. Read the document and capture its seq_no / primary_term
GetResponse<Product> get = client.get(g -> g
    .index("products").id("1"), Product.class);

Product product = get.source();
long seqNo = get.seqNo();
long primaryTerm = get.primaryTerm();

// 2. Modify in application code
product.setPrice(product.getPrice() * 1.10);

// 3. Conditional write: only succeeds if the doc hasn't changed since the read
try {
    client.index(i -> i
        .index("products")
        .id("1")
        .ifSeqNo(seqNo)
        .ifPrimaryTerm(primaryTerm)
        .document(product));
} catch (ElasticsearchException ex) {
    if (ex.status() == 409) {
        // Another writer changed the doc between our read and write.
        // Re-read and retry (a bounded retry loop), or surface a conflict to the caller.
        log.warn("Optimistic lock conflict on id=1, retrying");
        // ... loop: re-get fresh seq_no/primary_term, re-apply, re-write
    } else {
        throw ex;
    }
}
```

This is the canonical pattern for safe read-modify-write without a distributed lock. The `_seq_no`/`_primary_term` pair captured at read time acts as a version token; `ifSeqNo`/`ifPrimaryTerm` make the write a **compare-and-set** — if any other writer touched the document in between, its `_seq_no` advanced and Elasticsearch rejects ours with **409 `version_conflict_engine_exception`**. The correct response is a **bounded retry** (re-read, re-apply the change, re-write), because blindly retrying forever under high contention can livelock. For high-contention counters this pattern is itself an anti-pattern (segment churn, constant conflicts) — prefer modeling the increments as append-only events and aggregating, rather than mutating one hot document.

#### Q121. [Practical] How would you load-test and capacity-plan an Elasticsearch cluster before a major launch, and what metrics tell you it's at its limit?

Capacity planning is empirical — you benchmark with **realistic data and realistic queries**, then read the saturation signals.

Method:

1. **Model the workload:** real document size/shape, the actual query mix (full-text, aggregations, kNN), the read:write ratio, and peak QPS/indexing rate. Use **Rally** (Elastic's official benchmarking tool) with a custom track that mirrors production, or replay captured production traffic.
2. **Single-shard benchmark first** to find your "max comfortable shard size" for *your* data and queries (e.g., the point where p99 query latency crosses your SLO at a given shard size), then derive shard/node counts by dividing total data and required QPS.
3. **Ramp load** until latency degrades, and watch where it breaks.

Saturation metrics that signal the limit:

- **Thread pool rejections** (`GET /_cat/thread_pool?v` — `search`/`write` `rejected` climbing) — the clearest sign you've exceeded capacity; queues are full and requests are being shed.
- **p99/p999 latency** crossing SLO (averages hide tail pain).
- **JVM heap** — sustained old-gen above ~75% and frequent/long GC pauses mean memory pressure; combined with breaker trips it means you're over capacity or over-sharded.
- **CPU saturation** and **merge/refresh falling behind** on the write path.
- **Disk I/O utilization** near 100% (merges and queries are I/O-bound).

The discipline: never size from rules of thumb alone — benchmark with representative load, set explicit latency/throughput SLOs, and provision headroom (commonly target ~50–60% peak utilization) so a node failure or a traffic spike doesn't cascade. The first metric to alert on is thread-pool rejections, because that's the cluster explicitly telling you it's out of capacity.

#### Q122. [Practical] A mapping explosion ("too many fields" / `Limit of total fields [1000] has been exceeded`) is breaking indexing. What causes it and how do you fix it without losing data?

Mapping explosion happens when **dynamic mapping** keeps inventing new fields — typically because documents contain arbitrary, user-controlled, or high-cardinality keys (e.g., a `metrics` object keyed by metric name, or per-tenant custom attributes). Every distinct key becomes a mapped field; the field count grows unboundedly, bloating the cluster state and eventually hitting `index.mapping.total_fields.limit` (default 1000) and degrading the whole cluster (large cluster state is published to every node).

Diagnose: `GET /index/_mapping` and count fields; look for an object where keys are *values* rather than a fixed schema.

Fixes, best first:

1. **Use the `flattened` field type** for the offending object. A `flattened` field maps the *entire* JSON object as **one field**, indexing all nested keys/values as keyword-like terms without creating a mapping per key. You lose full-text analysis and per-subfield types, but you can still do `term`/`range`-style queries on the leaf paths — and the mapping stays a single field regardless of how many keys appear:

```json
PUT /events
{ "mappings": { "properties": { "attributes": { "type": "flattened" } } } }
```

2. **Disable dynamic mapping** on the volatile subtree (`"dynamic": false` to ignore new fields, or `"strict"` to reject them) so unknown keys don't silently mint fields.
3. **Remodel key-value data** into an array of `{ "key": ..., "value": ... }` objects (a fixed two-field schema) instead of dynamic keys — this caps the field count by design, at the cost of slightly more complex queries.

Recovery without data loss requires a **reindex** into a corrected mapping (you can't retype an existing field), typically behind an alias for zero downtime. The lesson: never let user-controlled keys drive your mapping — `flattened`, a fixed key/value model, or `dynamic: strict` are the guardrails.

#### Q123. [Coding] Write a `composite` aggregation to paginate through *all* buckets of a high-cardinality grouping, and explain why a plain `terms` agg can't do this.

A plain `terms` aggregation returns only the top `size` buckets in one shot and cannot paginate — asking for a huge `size` to "get them all" builds enormous structures in memory and can trip circuit breakers. The **`composite` aggregation** is purpose-built to **stream every bucket** in sorted order, page by page, using an `after` cursor (the bucket-aggregation analog of `search_after`):

```json
GET /events/_search
{
  "size": 0,
  "aggs": {
    "by_user_day": {
      "composite": {
        "size": 1000,
        "sources": [
          { "user": { "terms": { "field": "user_id" } } },
          { "day":  { "date_histogram": { "field": "ts", "calendar_interval": "day" } } }
        ]
      },
      "aggs": { "events": { "sum": { "field": "count" } } }
    }
  }
}
```

The response includes `after_key`; feed it back to get the next page:

```json
"composite": {
  "size": 1000,
  "after": { "user": "u-99421", "day": 1719619200000 },
  "sources": [ /* same sources */ ]
}
```

Repeat until the response returns fewer than `size` buckets. Why `composite` and not `terms`: `terms` is **approximate and bounded** (top-N per shard merged, with `doc_count_error_upper_bound`), suitable for "top 10 brands" but unable to enumerate millions of groups; `composite` walks all combinations in deterministic sort order with constant memory per page, making it the right tool for **exhaustive, exact enumeration** of high-cardinality groupings — e.g., exporting a per-user-per-day rollup. The trade-offs: it's forward-only (no random page jump) and runs sequentially, so it's for batch/export, not interactive faceting.

#### Q124. [Behavioral] You inherited a poorly-performing Elasticsearch cluster in production. Walk through how you'd stabilize it and earn the team's trust.

I'd treat it as incident triage first, then systematic improvement, communicating openly throughout.

**Stabilize (hours/days):**

1. **Assess current state without changing anything:** `_cluster/health`, `_cat/shards`, `_cat/thread_pool`, `_nodes/stats` (heap, GC, breakers), and the slow log. I want the facts — red/yellow status, shard count, heap pressure, rejection rates — before touching settings.
2. **Address the most acute risk first.** If disk is near flood stage, free space and clear read-only blocks; if a breaker is tripping, find the cause (usually fielddata on `text` or over-sharding) rather than raising the limit; if there are unassigned primaries (red), `allocation/explain` and restore them. I'd make one change at a time and verify its effect, so I never compound the problem.

**Diagnose root causes (days/weeks):**

3. The usual suspects: **over-sharding** (thousands of tiny shards bloating heap), **aggregating/sorting on `text`** (fielddata), **deep `from`/`size`**, **no ILM** (disk filling), and **queries not using filter context**. I'd quantify each with profiling and metrics rather than guess.

**Improve sustainably:**

4. Fix modeling (`keyword` sub-fields, right shard sizing, ILM with rollover/tiering), add capacity where benchmarks justify it, and put **monitoring and alerting** on the leading indicators (heap, rejections, disk watermarks) so we're never surprised again.

**Earn trust:**

5. I communicate continuously — a short written timeline of what I observed, what I changed, and the measured result, so the team sees cause and effect. I make reversible changes, document the runbook, and pair with the team so the knowledge transfers rather than living in my head. The goal isn't just a green cluster; it's a team that understands *why* it's green and can keep it that way. Showing my reasoning and being honest about what I don't yet know is what actually builds credibility under pressure.

## ✅ Key Takeaways

- Elasticsearch is a distributed search/analytics engine on Lucene; its core is the **inverted index** (term → postings) for fast full-text search.
- The **`text` vs `keyword`** distinction is fundamental: `text` is analyzed for full-text `match`; `keyword` is exact for `term`/sort/aggregate. Use multi-fields to get both.
- **Analyzers** (char filters → tokenizer → token filters) must be consistent at index and query time; `_analyze` is your first debugging tool.
- The **Query DSL** separates **query context** (scored) from **filter context** (cached, not scored) — push yes/no constraints into `filter`.
- Default relevance is **BM25** (TF saturation + IDF + length normalization), better than linear TF-IDF; tune with boosting, `function_score`, hybrid/vector + RRF, and offline evaluation.
- **Shards** scale data horizontally (fixed primary count); **replicas** give HA and read throughput. Avoid **over-sharding** — aim for ~10–50 GB shards.
- Elasticsearch is **near-real-time**: **refresh = visibility (~1s)**, **flush = durability**, **translog = the safety net**; segments are immutable and **merged** in the background.
- Use **`search_after` (+ PIT)** for deep pagination, not deep `from`/`size` (capped at 10k).
- Model with **denormalization** (or `nested`/`join` when relationships matter); keep a separate **system of record** and feed Elasticsearch via CDC/outbox.
- Operate with **ILM + data streams + data tiers** (hot/warm/cold/frozen + searchable snapshots) for cost-effective time-series at scale, and **CCR/CCS** for multi-region.

## ⚠️ Common Pitfalls

- **Running `term` on a `text` field** (or `match` on a `keyword`) → no/unexpected matches because analysis differs. Check with `_analyze`.
- **Sorting or aggregating on a `text` field** → fielddata loads onto the JVM heap and can OOM; use the `.keyword` sub-field (doc values).
- **Deep `from`/`size` pagination** → every shard builds `from+size` hits; hits the 10k window and is slow. Use `search_after`/PIT.
- **Over-sharding** (thousands of tiny shards) → bloated cluster state, master pressure, wasted heap. Right-size shards and use rollover/ILM.
- **`refresh=true` or tiny refresh intervals on every write** → tiny-segment explosion and crushed indexing throughput.
- **Treating Elasticsearch as the system of record** → no transactions, eventual consistency, and misconfig data-loss risk; keep a real source of truth.
- **Plain `object` arrays expecting per-element matching** → cross-matching bug; use `nested` when within-element relationships matter.
- **Force-merging a live, still-written index** → massive merge debt and I/O storms; only force-merge read-only indices.
- **Ignoring `errors` in a Bulk response** → silent partial failures behind an HTTP 200.
- **Putting yes/no constraints in `must` instead of `filter`** → unnecessary scoring and lost filter-cache benefit.
- **Stretching one cluster across high-latency regions** → coordination quorum suffers; use independent clusters + CCR/CCS.
- **Raising circuit-breaker limits to "fix" trips** → trades a safe rejection for an OOM; fix the query/mapping causing the memory demand.

## 📚 Further Reading

- Elasticsearch: The Definitive Guide (concepts still foundational) and the official Elasticsearch Reference (current version).
- Lucene in Action / Lucene documentation — inverted index, segments, scoring internals.
- "Relevant Search" (Turnbull & Berryman) — practical relevance engineering with Elasticsearch/Solr.
- Elastic blog: BM25 vs TF-IDF, kNN/HNSW vector search, RRF hybrid search, and data tiers / searchable snapshots.
- Elastic docs: Query DSL, Aggregations, Mapping, ILM & data streams, Cross-Cluster Replication/Search, and the Java API Client.
- Designing Data-Intensive Applications (Kleppmann) — Ch. 3 (storage/retrieval, LSM/segments) and replication/consistency chapters for the distributed-systems framing.
