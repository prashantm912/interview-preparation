# Design a Search Engine / Autocomplete

> A worked, interview-grade design for a web-scale search engine with crawling, an inverted index, BM25 ranking, and a low-latency typeahead/autocomplete service. Reason through scale, latency, freshness, and the CAP trade-offs that fall out of them.

[← Back to master index](../../README.md) · [← System Design index](../README.md)

---

## 1. Requirements

A "search engine" is a broad term. In an interview, narrow it fast. We'll design a **general web search engine** (think a scoped Google/Bing) **plus an autocomplete/typeahead service**, because the interviewer named both and they exercise complementary skills: search is read-heavy index + ranking; autocomplete is ultra-low-latency prefix matching.

### Functional requirements

- **Crawl** the web continuously: fetch pages, respect `robots.txt`, discover links, re-crawl for freshness.
- **Index** crawled content into an **inverted index** (term → list of documents).
- **Query**: a user submits a free-text query; we return a ranked list of the top *k* (say 10) results with title, URL, and snippet.
- **Autocomplete**: as the user types a prefix, return the top 5–10 most-likely query completions in < 100 ms.
- **Spell correction**: "did you mean…?" for misspelled queries.
- **Ranking**: results ordered by relevance (textual match) and quality (popularity/authority).

### Non-functional requirements

| Dimension | Target | Why |
|---|---|---|
| **Latency** | Search p99 < 300 ms; autocomplete p99 < 100 ms | Search abandonment rises sharply past ~500 ms; typeahead must feel instant per keystroke. |
| **Availability** | 99.9%+ for the query path | Search is the product; being down = no revenue/utility. The crawl/index path can tolerate brief downtime. |
| **Scale** | ~100 B documents indexed; 100 K search QPS; 10× autocomplete QPS | Web scale. Numbers chosen for a Google-lite estimate. |
| **Consistency** | **Eventual** for the index | A page indexed seconds vs. minutes late is fine. We trade strong consistency for availability + latency (AP system). |
| **Freshness** | News/trending: minutes. Static pages: days. | Differentiated by content class — not one knob. |
| **Durability** | Crawled corpus + index must survive node loss | Re-crawling 100 B pages is enormously expensive. |

### Clarifying questions a strong candidate asks

1. **Scope** — general web search, or search over a bounded corpus (e.g., a product catalog, internal docs)? This changes crawl complexity by orders of magnitude.
2. **Read vs. write ratio?** Search is overwhelmingly read-heavy (~99.9% reads); that justifies aggressive caching and read replicas.
3. **Personalization / ranking signals?** Do we have click logs, user history, geolocation? Personalization changes the ranking pipeline and cache key.
4. **Multi-lingual?** Tokenization, stemming, and stop-words are language-specific.
5. **Result freshness SLA?** Determines crawl frequency and whether we need a real-time indexing path.
6. **Do we need exact phrase / boolean operators**, or just bag-of-words relevance?

For the rest of this doc we assume: general web search, bag-of-words + phrase support, eventual consistency, English-first with i18n hooks, and we own click logs for ranking.

---

## 2. Capacity Estimation

Back-of-the-envelope math. Show the assumptions; the interviewer cares about the reasoning, not the exact digits.

### Corpus & storage

- **Documents indexed:** 100 B pages.
- **Avg page size after extraction (text only, HTML stripped):** ~10 KB.
- **Raw text corpus:** `100 B × 10 KB = 10^11 × 10^4 B = 10^15 B = 1 PB`. Store compressed (~5:1) → **~200 TB** for the document store.

**Inverted index size:**
- Avg unique terms per page after dedup/stemming: ~500.
- Total postings = `100 B × 500 = 5 × 10^13` postings.
- A posting (doc ID + term frequency + positions), delta-encoded + compressed: ~2 bytes amortized.
- Index ≈ `5 × 10^13 × 2 B = 10^14 B = 100 TB`. With replication factor 3 → **~300 TB** of index that must be served, largely from memory/SSD.

### Query traffic (QPS)

- **Search QPS:** 100 K average. Peak ≈ 2× = **200 K QPS**.
- **Autocomplete QPS:** every keystroke fires a request. A 20-char query ≈ 20 requests, but debounced to ~5. So `5 × search QPS = 500 K average`, peak **~1 M QPS**. Autocomplete is the higher-throughput service by far.

### Bandwidth

- Search response (10 results + snippets) ≈ 50 KB. `200 K × 50 KB ≈ 10 GB/s` egress at peak. CDN + compression mandatory.
- Autocomplete response (10 short strings) ≈ 1 KB. `1 M × 1 KB = 1 GB/s`.

### Memory / cache

- **Query result cache:** Zipf distribution — the top ~20% of queries account for ~80% of traffic. Caching the top 1 M unique queries × 50 KB = **50 GB** of cache absorbs the majority of read load. Distribute across a Redis cluster.
- **Autocomplete trie:** ~100 M historical query prefixes, top-k pre-computed per node. A serialized trie with top-k cached ≈ **tens of GB**, fully in-memory and shardable.

### Crawl throughput

- To (re)crawl 100 B pages on a 30-day cycle: `100 B / (30 × 86400 s) ≈ 38 K pages/sec` sustained. With politeness limits and bursty hot content, provision **~50 K fetches/sec**. Hot/news content gets a separate fast lane.

**Takeaways that drive the design:** (1) index is too big for one machine → **shard it**; (2) reads dominate → **cache hard**; (3) autocomplete out-QPS's search → **separate service, separate datastore**; (4) re-crawl cost is huge → **durability + dedup matter**.

---

## 3. API Design

```http
# --- Search ---
GET /v1/search?q=distributed+systems&page=0&size=10&lang=en
Authorization: Bearer <token>          # rate-limiting / quotas

200 OK
{
  "query": "distributed systems",
  "corrected_query": null,             # set if spell-correction fired
  "total_estimated": 4830000,
  "took_ms": 142,
  "results": [
    {
      "doc_id": "8a3f...e91",
      "title": "Distributed Systems for Fun and Profit",
      "url": "https://book.mixu.net/distsys/",
      "snippet": "...a short introduction to <em>distributed systems</em>...",
      "score": 14.7
    }
  ],
  "page": 0, "size": 10
}

# --- Autocomplete (typeahead) ---
GET /v1/suggest?p=distr&limit=10&lang=en

200 OK
{
  "prefix": "distr",
  "suggestions": [
    "distributed systems",
    "distrokid",
    "district court",
    "distribution",
    "distributed tracing"
  ],
  "took_ms": 11
}

# --- Internal: ingest a crawled+parsed doc into the indexing pipeline ---
POST /internal/v1/documents          # producer = parser, consumer = indexer
{
  "url": "...", "content_hash": "sha256:...",
  "title": "...", "text": "...", "outlinks": ["..."],
  "fetched_at": "2026-06-16T09:00:00Z", "lang": "en"
}
202 Accepted
```

Design notes:
- Search is **`GET`** (cacheable, idempotent, CDN-friendly). Pagination is offset-based here for simplicity; at depth, switch to a `search_after` cursor token to avoid deep-pagination cost.
- Autocomplete is a separate path with its own SLA and rate limits; it never touches the inverted index.
- Ingestion is **async** (`202`): the crawler/parser publishes to a queue; indexers consume. This decouples write spikes from the serving path.

---

## 4. Data Model

We use **multiple specialized stores**, not one database. "SQL vs NoSQL" is the wrong framing for search — the right framing is "purpose-built engine per workload."

### a) Document store (NoSQL — wide-column / KV)

Holds the canonical crawled page. Accessed by `doc_id`. Append-heavy, huge, no relational joins.

```
Table: documents  (Cassandra / HBase / S3+metadata)
  doc_id          (PK, e.g. hash of canonical URL)
  url             text
  content_hash    text           # dedup near-duplicates
  title           text
  text            blob (compressed)
  outlinks        list<text>
  lang            text
  fetched_at      timestamp
  page_rank       float          # precomputed authority
```
**Why NoSQL:** 100 B rows, write-heavy ingestion, key-based access, horizontal scale. Cassandra (tunable consistency, multi-DC) or S3 for blobs + a metadata table.

### b) Inverted index (specialized — Lucene segments)

The core. Conceptually `term → posting list`:

```
"distributed" → [(doc_42, tf=5, pos=[3,40,...]),
                 (doc_91, tf=2, pos=[7,12]), ...]   # sorted by doc_id
"systems"     → [(doc_42, tf=3, pos=[4,41]), ...]
```
Stored as **immutable Lucene segments** (the engine behind Elasticsearch/OpenSearch). Each segment is a mini-index; segments merge in the background. Postings are delta-encoded + bit-packed for compression. Term dictionary is an FST (finite-state transducer) for O(term length) lookup.

### c) Autocomplete store (Trie + KV)

```
Trie node "distr" → top_k: ["distributed systems"(9.9M), "distrokid"(4.1M), ...]
```
Top-k completions are **pre-computed and cached at each prefix node**, so a lookup is "walk to node, return cached list" — no sort at query time. Backed by a frequency table in Redis/NoSQL that a batch job aggregates from query logs.

### d) Query log / click store (analytics)

Append-only event stream (Kafka → data lake / ClickHouse). Feeds ranking signals (CTR), autocomplete frequencies, and spell-correction models.

| Workload | Store | Consistency |
|---|---|---|
| Canonical docs | Cassandra / S3 | Eventual |
| Inverted index | Lucene/ES shards | Eventual (near-real-time refresh) |
| Autocomplete | In-mem Trie + Redis | Eventual (batch-rebuilt) |
| Cache | Redis cluster | Best-effort |
| Logs/signals | Kafka + ClickHouse | At-least-once |

---

## 5. High-Level Architecture

```
                          ┌──────────────────────────────────────────────┐
                          │             OFFLINE / WRITE PATH               │
                          │                                                │
  ┌─────────┐   URLs   ┌──┴───────┐  pages  ┌──────────┐ docs  ┌──────────┐│
  │ Seed/   │─────────▶│ Crawler  │────────▶│  Parser/ │──────▶│  Kafka   ││
  │ Sitemaps│          │ (fetch + │         │ Extractor│       │ (ingest  ││
  └─────────┘          │ robots)  │◀────────│ + dedup  │       │  topic)  ││
        ▲              └────┬─────┘ outlinks└──────────┘       └────┬─────┘│
        │ frontier          │ store raw                              │      │
        │ (URL queue)       ▼                                        ▼      │
   ┌────┴─────┐      ┌─────────────┐                          ┌───────────┐│
   │ URL      │      │  Document   │                          │  Indexers │�
   │ Frontier │      │   Store     │◀─────── fetch text ──────│ (build    ││
   │ (Kafka)  │      │ (Cassandra) │                          │  segments)││
   └──────────┘      └─────────────┘                          └─────┬─────┘│
                          │ PageRank job (offline) ──▶ page_rank           │ │
                          └─────────────────────────────────────────┐     │ │
                          └──────────────────────────────────────────┼─────┘
                                                                      │ write shards
══════════════════════════════════════════════════════════════════ ▼ ═══════════
                          ┌──────────────────────────────────────────────┐
                          │              ONLINE / READ PATH                │
   ┌──────┐               │                                                │
   │ User │──── GET /search ──▶ ┌─────┐   ┌──────────────┐                 │
   └──────┘                     │ CDN │──▶│ API Gateway  │                 │
       │                        └─────┘   │ (rate-limit, │                 │
       │                                  │  auth)       │                 │
       │                                  └──────┬───────┘                 │
       │                                         ▼                         │
       │                        ┌────────────────────────────┐            │
       │  cache hit ◀───────────│   Query Service / Broker    │            │
       │                        │  (parse, spell-correct,     │            │
       │                        │   cache check, scatter)     │            │
       │                        └──────┬──────────┬───────────┘            │
       │                    Redis      │ scatter  │                        │
       │                  ┌─────────┐  │ /gather  │                        │
       │                  │ Result  │◀─┘          ▼                        │
       │                  │ Cache   │     ┌───────────────────────┐        │
       │                  └─────────┘     │  Index Shard 1..N      │        │
       │                                  │ (Lucene/ES data nodes, │        │
       │                                  │  each replicated ×3)   │        │
       │                                  │  local top-k ──┐       │        │
       │                                  └────────────────┼───────┘        │
       │                                       merge top-k ▼               │
       └──── GET /suggest ──▶ ┌──────────────────┐   ┌──────────┐          │
                              │ Autocomplete Svc │   │ Snippet/ │          │
                              │ (in-mem Trie,    │   │ Hydrate  │          │
                              │  top-k per node) │   │ from doc │          │
                              └──────────────────┘   │  store   │          │
                                                      └──────────┘          │
                          └──────────────────────────────────────────────┘
```

### Component walkthrough

**Write path (offline):**
- **URL Frontier** — a prioritized queue (Kafka + a scheduler) of URLs to crawl. Priority = freshness need × authority. De-dupes URLs and enforces per-host politeness (rate per domain).
- **Crawler** — distributed fetchers. Respect `robots.txt`, set crawl delays, handle redirects/4xx/5xx, dedupe via `content_hash`. Store raw + extract outlinks (which feed back to the frontier).
- **Parser/Extractor** — strips boilerplate, tokenizes, stems, removes stop-words, detects language, computes `content_hash` for near-dup detection (SimHash/MinHash). Publishes a clean doc to **Kafka**.
- **Indexers** — consume from Kafka, build Lucene segments, and write to the correct **index shard** (by `doc_id` hash). New segments become searchable after a periodic *refresh* (near-real-time, ~1 s in ES).
- **PageRank / authority job** — periodic batch (Spark) over the link graph; writes `page_rank` back, used as a ranking signal.

**Read path (online):**
- **CDN + API Gateway** — caches static result pages, terminates TLS, authenticates, rate-limits.
- **Query Service (Broker/Coordinator)** — the brain of search. Parses the query, runs spell-correction, checks the **result cache**, then does **scatter-gather**: fan the query to all index shards, each returns its **local top-k**, the broker **merges** into a global top-k. Then **hydrates** snippets from the document store and returns.
- **Index Shards** — each holds a slice of the inverted index, replicated ×3. They do the heavy lifting: term lookup, scoring (BM25), local top-k.
- **Autocomplete Service** — completely separate; serves from an in-memory trie. Never touches the inverted index.

---

## 6. Deep Dives

### 6.1 Indexing: building & serving the inverted index

The inverted index maps each term to a **posting list** of documents containing it, sorted by `doc_id`. To answer `"distributed systems"` we intersect the posting lists of both terms (an AND), scoring survivors.

**Construction at scale** is a MapReduce-style job: map each doc to `(term, doc_id, tf, positions)`; reduce by term to produce sorted posting lists. Online, we use the **Lucene segment** model: documents are buffered, flushed to a new **immutable segment**, and segments merge in the background (log-structured). Immutability means lock-free reads and trivial caching, at the cost of background merge I/O and a refresh delay before new docs are searchable.

**Compression** is essential (100 TB → must fit on SSD/RAM): posting lists are **delta-encoded** (store gaps between sorted doc IDs, not absolute IDs) then **bit-packed / Frame-of-Reference / PForDelta** encoded. The term dictionary uses an **FST** for compact O(term-length) lookups.

**Trade-off:** immutable segments give fast reads + simple replication but make deletes "soft" (tombstones, reclaimed on merge) and add write amplification from merging. The alternative — mutable in-place index — kills read concurrency. Lucene's choice (immutable + merge) is the right default and what we adopt.

### 6.2 Ranking: TF-IDF → BM25 → learning-to-rank

After we find candidate documents, we must **order** them. Three layers:

**TF-IDF** scores a term in a doc as `tf × idf`, where `tf` rewards term frequency in the doc and `idf = log(N / df)` rewards rare terms (a match on "distributed" matters more than on "the"). Problem: raw `tf` grows unbounded — a doc spamming a word ranks too high.

**BM25** (our default) fixes this with **saturation** and **length normalization**:

```
score(D,Q) = Σ_term  IDF(term) · ( f(term,D) · (k1+1) )
                                  ─────────────────────────────────────────
                                   f(term,D) + k1·(1 − b + b·|D|/avgdl)

  k1 ≈ 1.2  (term-frequency saturation; higher = tf matters more, up to a limit)
  b  ≈ 0.75 (length normalization; penalizes long docs that match by sheer size)
```
BM25 is the industry standard (default in Lucene/Elasticsearch since ES 5.0) because tf saturates (the 10th occurrence adds little) and long documents don't win automatically.

**Two-phase ranking** at scale: each shard cheaply scores with BM25 and returns its **local top-k (e.g. 100)**; the broker merges to a global candidate set, then a **re-ranking** stage applies expensive signals — **PageRank/authority, click-through rate, freshness, personalization** — often via a **learning-to-rank** model (gradient-boosted trees / a neural ranker). Cheap-recall-then-expensive-precision keeps p99 low.

**Trade-off:** more ranking signals = better relevance but higher latency and infra cost. We bound it: BM25 on every shard (cheap), ML re-rank only on the ~100–1000 merged candidates (bounded work).

### 6.3 Sharding the index & scatter-gather

100 TB can't live on one node, so we partition. Two strategies:

| Strategy | How | Pros | Cons |
|---|---|---|---|
| **Document sharding** (our choice) | Each shard holds a *subset of documents*, full term coverage for those docs. Route by `hash(doc_id) % N`. | Even load; add shards to scale; each query hits all shards but each does little. | Every query is a **scatter to all shards** (fan-out cost). |
| **Term sharding** | Each shard holds a *subset of terms*, full posting lists. | A single-term query hits one shard. | Hot terms create hotspots; multi-term queries need cross-shard joins of huge lists; uneven load. |

Document sharding wins for general search: load balances naturally, scales by adding shards, and most queries are multi-term (term sharding would shuffle giant posting lists). The cost is **scatter-gather**: the broker fans out to all N shards, waits for local top-k, merges. To bound tail latency, use **request hedging** (send a duplicate to a replica if a shard is slow) and a **per-shard timeout** (return partial results rather than block on a straggler — graceful degradation). Each shard is **replicated ×3** for availability and read throughput.

### 6.4 Autocomplete: trie + precomputed top-k

Autocomplete must answer "given prefix `distr`, what are the most likely full queries?" in < 100 ms at ~1 M QPS — so we cannot scan or sort at request time.

**Data structure:** a **trie** of historical queries. The key trick: **store the top-k completions at every node**, precomputed from query frequencies. A lookup is "walk the prefix to its node, return the cached top-k" — O(prefix length), no sorting.

```
            (root)
             │
             d
             │
             i ── s ── t ── r        node "distr".top5:
                          │            [distributed systems(9.9M),
                          ...           distrokid(4.1M),
                                        district court(3.0M), ...]
```

**Building it:** a batch job aggregates query frequencies from the **query log** (Kafka → ClickHouse), updates frequencies, and rebuilds/patches the trie nightly (with a faster incremental path for trending terms). Trending/breaking queries (e.g. a sudden news spike) get a **real-time boost** layer so "did the candidate handle freshness?" is answered yes.

**Scale:** the trie is huge but shardable by **first 1–2 characters** (prefix sharding) across an in-memory cluster; replicate hot shards. Because suggestions are read-mostly and tolerate staleness, this is a clean AP design.

**Trade-offs:** precomputed top-k per node trades **memory + rebuild cost** for **read latency** — exactly the right trade given the QPS asymmetry. Personalization complicates the cache key (per-user top-k blows up memory), so we typically blend a small personal layer over the global trie rather than per-user tries.

### 6.5 Caching popular queries & spell correction

**Caching:** query traffic is **Zipfian** — a small set of queries dominates. A distributed **Redis result cache** keyed by `(normalized_query, page, lang)` absorbs ~80% of reads at ~50 GB. Normalize aggressively (lowercase, trim, sort independent filters, stable filter order) to raise hit rate. TTL is content-class-aware: trending queries get short TTLs (seconds–minutes) so results stay fresh; evergreen queries get long TTLs. Guard against the **thundering herd** on cache miss for a hot key with **request coalescing** (single-flight) so only one backend recompute fires per key.

**Spell correction:** before/alongside the index lookup, run the query through a corrector. Classic approach: an **edit-distance** model (Levenshtein automaton over the term dictionary) combined with a **language model** (n-gram probabilities of word sequences) — pick the candidate maximizing `P(correction) × P(typo | correction)`. We also mine **query reformulations** from logs ("users who typed X then typed Y") which captures real-world corrections better than pure edit distance. If confidence is high we auto-correct and show "Showing results for…"; if medium, we show "Did you mean…?".

**Trade-off:** auto-correcting too aggressively frustrates users searching for genuinely rare strings (product codes, names). Confidence thresholds + a one-click "search instead for [original]" escape hatch balance this.

---

## 7. Scaling, Bottlenecks & Failure Handling

**What breaks first?** As traffic grows, the failure order is usually: (1) **hot keys** overwhelm a single cache/shard; (2) **scatter-gather tail latency** as shard count grows (p99 = slowest of N shards); (3) **index write/merge I/O** during re-index storms; (4) **crawl politeness** bottlenecks (you can't fetch a single host faster than it allows).

**Scaling levers:**
- **Reads:** more shard **replicas** + bigger **result cache** + **CDN**. Reads scale near-linearly via replication.
- **Index size:** add **document shards** (re-shard with consistent hashing to limit data movement).
- **Tail latency:** **request hedging** (tied requests to replicas), **per-shard timeouts** with partial results, and keeping shards small enough that local query work is bounded.
- **Hotspots:** detect hot keys and **replicate just those shards/keys**; for autocomplete, replicate hot prefix shards.

**Replication & partitioning:** index shards replicate ×3 (1 primary + 2 replicas) across racks/AZs; reads hit any replica, writes go to primary then async to replicas (eventual consistency on the read path — fine for search). The document store (Cassandra) uses RF=3 with `LOCAL_QUORUM` reads/writes for a sane balance.

**Failure handling:**
- **Circuit breakers** on the broker → shard calls: if a shard is failing, trip the breaker, serve partial results, and shed load instead of cascading.
- **Bulkheads / load shedding:** separate thread pools per dependency; drop low-priority traffic (deep pagination, expensive re-ranking) under overload before dropping core queries.
- **Graceful degradation:** if the ML re-ranker is down, fall back to BM25-only ordering. If a shard is down, return results from the rest (slightly lower recall) rather than erroring.
- **Idempotent, replayable ingestion:** Kafka retains crawled docs; if an indexer dies, another replays from the last committed offset. At-least-once + `content_hash` dedup makes re-processing safe.

**Disaster recovery:** multi-region active-active for the read path (each region has full shard replicas + cache, fronted by GeoDNS). The crawl/index pipeline is single-write-region with async replication of segments + the document store to a standby region; failover promotes the standby. RPO measured in minutes (acceptable — eventual freshness), RTO minimized by keeping warm replicas.

---

## 8. Trade-offs & Alternatives

| Decision | We chose | Alternative | Why / when to switch |
|---|---|---|---|
| Consistency | **Eventual (AP)** | Strong (CP) | Search tolerates staleness; AP buys availability + latency. Strong consistency would cripple write throughput and add latency for zero user benefit. |
| Index sharding | **Document sharding** | Term sharding | Doc sharding load-balances and scales cleanly; term sharding hotspots on common terms. |
| Ranking | **BM25 + LTR re-rank** | Pure TF-IDF / pure vector search | BM25 fixes TF-IDF's saturation/length flaws; LTR adds behavioral signals. **At 10×/quality push, add a hybrid lexical + semantic (vector/embedding) retrieval** for synonym/intent matching. |
| Autocomplete | **Precomputed top-k trie** | Query-time aggregation | Read-latency wins given the QPS asymmetry; precompute cost is acceptable offline. |
| Index engine | **Elasticsearch/OpenSearch (Lucene)** | Roll-your-own / Solr / Vespa | ES gives sharding, replication, near-real-time refresh, BM25, and ops tooling out of the box. **Vespa** is the strong alternative when you need first-class ML ranking + vector search at scale. |
| Datastore for docs | **Cassandra / S3** | SQL (Postgres) | 100 B rows, write-heavy, key access — relational guarantees aren't needed and don't scale here. |

**What changes at 10×?** Cache and replica counts grow; shards multiply; tail-latency engineering (hedging, timeouts) becomes mandatory rather than nice-to-have. Introduce a **freshness fast-lane**: a small, frequently-refreshed real-time index for news/trending merged with the bulk index at query time.

**What changes at 100×?** Multi-region everything; tiered indexes (hot/warm/cold by query frequency — keep the long tail on cheaper storage, the head in RAM); **semantic retrieval** (embeddings + ANN search like HNSW) blended with lexical BM25; per-region crawl fleets; and heavy investment in the link-graph/PageRank pipeline. Crawl politeness and dedup dominate engineering effort more than raw indexing.

---

## Interview Q&A by Level

### 🟢 Basic

**Q: What is an inverted index and why use it instead of scanning documents?**
A: It maps each *term* to the list of documents containing it (`term → [doc1, doc7, ...]`), the inverse of a document→terms mapping. Scanning 100 B docs per query is impossible; with an inverted index a query is a few term lookups + posting-list intersections, turning an O(corpus) scan into O(matching docs).

**Q: Why is autocomplete a separate service from search?**
A: Different SLA and load profile. Autocomplete fires per keystroke (~5–10× the QPS of search) and needs < 100 ms, but only does prefix matching — it never touches the inverted index. Coupling them would let typeahead traffic starve search and force one datastore to serve two very different access patterns.

**Q: Roughly how big is the index and why can't it live on one machine?**
A: ~100 B docs × ~500 terms × ~2 B/posting ≈ 100 TB before replication, ~300 TB with RF=3. No single machine has that much RAM/SSD with the throughput needed, so we shard across many nodes and serve via scatter-gather.

### 🟡 Intermediate

**Q: Document sharding vs. term sharding — which and why?**
A: Document sharding (each shard = subset of docs, all terms for them). It load-balances evenly and scales by adding shards. Term sharding hotspots on common terms ("the"), and multi-term queries require shuffling huge posting lists across shards. The cost of doc sharding — every query scatters to all shards — is mitigated by small shards, replicas, and hedging.

**Q: How do you keep search latency low when a query must hit all shards (scatter-gather)?**
A: (1) Each shard returns only its **local top-k** so merge work is bounded. (2) **Per-shard timeouts** return partial results instead of waiting on a straggler. (3) **Request hedging** — send a tied request to a replica if a shard is slow, take the first response. (4) Aggressive **result caching** so the hottest queries never scatter at all.

**Q: Walk through how autocomplete returns top suggestions instantly.**
A: A trie of historical queries with the **top-k completions precomputed and cached at every node**. A request walks the prefix to its node and returns the cached list — O(prefix length), no sort. A batch job rebuilds frequencies from query logs; a real-time layer boosts trending terms. The trie is sharded by leading characters across an in-memory cluster.

**Q: How does caching exploit query patterns?**
A: Query frequency is **Zipfian** — ~20% of queries drive ~80% of traffic. A Redis result cache keyed on normalized query absorbs that majority at modest memory (~50 GB). Content-class TTLs keep trending results fresh; single-flight coalescing prevents thundering herds on hot-key misses.

### 🟠 Advanced

**Q: Explain BM25 and why it beats raw TF-IDF.**
A: BM25 scores each term as `IDF · (tf·(k1+1)) / (tf + k1·(1−b+b·|D|/avgdl))`. Two fixes over TF-IDF: **term-frequency saturation** (controlled by `k1` — the 10th occurrence of a word adds far less than the 2nd, so keyword spam doesn't win) and **length normalization** (controlled by `b` — long documents don't rank highly just by containing more words). IDF still rewards rare, discriminating terms.

**Q: How do you balance freshness against scale when re-crawling 100 B pages is expensive?**
A: Differentiate by content class. A bulk index re-crawls/re-indexes on a days-to-weeks cycle (most of the web rarely changes). A separate **freshness fast-lane** — a small, frequently-refreshed real-time index fed by sitemaps, RSS, and change-detection — handles news/trending and is merged with bulk results at query time. Crawl scheduling prioritizes URLs by change rate × authority, so high-value volatile pages get crawled often and static pages rarely.

**Q: What consistency model does the system use and what's the CAP trade-off?**
A: An **AP** system with **eventual consistency** on the index. During a partition we favor availability — serving slightly stale results is acceptable for search, while being unavailable is not. New documents become searchable after a refresh delay (~seconds), replicas converge asynchronously. We'd never trade availability for the strong consistency search doesn't need.

**Q: How do you handle a sudden hot query (breaking news) without melting a shard?**
A: Detect the hot key (frequency monitoring), then (1) serve it from cache with a short TTL via single-flight so only one recompute fires; (2) replicate the involved index/cache shard so reads spread; (3) for autocomplete, the trending fast-lane surfaces it; (4) circuit breakers + load shedding protect the rest of the system if it still spikes.

### 🔴 Expert

**Q: Design the spell-correction subsystem and discuss its failure modes.**
A: Combine a **noisy-channel model** — pick the correction `c` maximizing `P(c) · P(typo|c)` where `P(c)` is an n-gram language model and `P(typo|c)` an edit-distance/keyboard-proximity error model — with **log-mined reformulations** (sequences where users typed X then immediately Y). Use a **Levenshtein automaton** over the term FST for efficient candidate generation. Confidence thresholds gate behavior: high → auto-correct ("Showing results for…"), medium → "Did you mean…?", low → leave it. Failure modes: over-correcting rare-but-valid strings (product SKUs, names) — mitigated by always offering "search instead for [original]"; and bias toward popular terms drowning legitimate niche queries — mitigated by per-domain/personal dictionaries.

**Q: How would you add semantic (vector) search without throwing away the lexical index?**
A: **Hybrid retrieval.** Keep BM25 for precise lexical matching, and add an embedding-based ANN index (HNSW/IVF) for semantic/intent matching ("how to fix a flat" ↔ "repair bicycle tire"). At query time retrieve candidates from **both** retrievers, normalize their scores, and fuse (e.g. **Reciprocal Rank Fusion** or a learned blend), then re-rank the union with the LTR/neural model. This catches synonyms and intent that BM25 misses while preserving exact-match strength. Cost: a second index + embedding inference at index and query time, so we bound ANN to a top-N candidate stage, not the whole corpus.

**Q: A new index version ranks better offline but you can't risk regressions on 200K QPS. How do you roll it out?**
A: (1) **Shadow/dark traffic** — replay live queries against the new index/ranker offline and diff results, with no user impact. (2) **Interleaving experiments** — for a query, interleave results from old and new rankers and measure which the user clicks; far more sensitive than A/B for ranking. (3) **Gradual A/B rollout** (1% → 5% → 50%) gated on guardrail metrics (CTR, abandonment, latency p99, "no-result" rate). (4) **Instant rollback** via a config flag / blue-green index alias so reverting is one switch, not a redeploy. (5) Hold-out **canary regions** and automated rollback if guardrails breach.

**Q: How do you detect and handle near-duplicate pages so the index isn't full of clones?**
A: Compute a **SimHash** (or MinHash for set-similarity) fingerprint per document during parsing; near-duplicates produce fingerprints within a small Hamming distance. Bucket fingerprints (LSH) so duplicate detection is sublinear rather than all-pairs. On a hit, **canonicalize** to one representative doc (prefer higher authority / canonical URL / earliest crawl), and either drop the clone or store it as an alias pointing to the canonical doc_id. This keeps the index smaller, prevents duplicate results crowding the top-k, and concentrates ranking signal (links, clicks) on the canonical version.

---

*Related: [Distributed caching](../README.md) · [Designing a rate limiter](../README.md) · [Kafka & event streaming](../README.md)*
