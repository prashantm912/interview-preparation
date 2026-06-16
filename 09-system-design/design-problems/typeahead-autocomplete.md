# Design Search Autocomplete / Typeahead

> A worked, interview-grade design of a search-as-you-type autocomplete service: return the top-k most relevant query suggestions for any prefix within a few milliseconds, at hundreds of thousands of keystrokes per second, while continuously learning fresh suggestions from a firehose of real searches.

[← Back to master index](../../README.md) · [← System Design index](../README.md)

---

## 1. Requirements

Autocomplete looks trivial ("it's just a prefix lookup"), but the interviewer is probing latency budgets, the read/write split, ranking, and how you keep suggestions fresh without re-indexing the world on every keystroke. Clarify scope before drawing anything.

### Functional requirements
- **Suggest**: given a prefix string (e.g. `"new y"`), return the top-k ranked completions (`"new york"`, `"new york times"`, `"new years eve"`…). Default **k = 10**.
- **Ranking by popularity**: suggestions are ordered by how often a full query has been searched (with recency weighting), not alphabetically.
- **Real-time-ish freshness**: a query that suddenly trends ("breaking news term") should start appearing as a suggestion within minutes, not days.
- **Personalization / context** (optional, often a follow-up): bias suggestions by the user's history, locale, or the current session.
- **Multi-language / Unicode**: handle non-ASCII prefixes, accents, and CJK input.
- **Safety**: filter profanity, hate terms, PII, and legally-suppressed queries out of suggestions.

### Non-functional requirements
- **Latency**: suggestion p99 **< 100 ms end-to-end**, p99 **< 10 ms server-side**. This fires on *every keystroke* — it must feel instant or the feature is worse than useless.
- **Scale**: ~**5 billion searches/day**, and because we suggest per keystroke, ~**10× the keystroke volume** drives the read QPS. Read:write is heavily read-dominated.
- **Availability**: **99.9%+**. Autocomplete is an enhancement, not the search itself — if it's down, the search box still works, so we favor availability and graceful degradation over strict correctness.
- **Consistency**: **eventual** is fine. A suggestion being a few minutes stale is invisible to users; we never need read-your-writes here.
- **Durability**: the suggestion index is **derived data** — it can always be rebuilt from the raw query logs, so the logs (the source of truth) must be durable; the serving index need not be.
- **Security/abuse**: prevent a small number of bots from poisoning suggestions by spamming a query; strip PII (emails, card numbers, SSNs) from ever surfacing.

### Clarifying questions a strong candidate asks
1. What's the latency budget, and is it per-keystroke or debounced client-side? (Drives the entire serving design.)
2. How fresh must suggestions be — daily batch, or trending within minutes? (Batch vs streaming pipeline.)
3. Top-k size? Just 10, or do we need 50 for richer UIs?
4. Do we rank purely by global popularity, or personalize per user/locale? (Adds a whole ranking + storage dimension.)
5. Do we suggest only previously-seen queries, or also synthesize from a document corpus? (Query-log-driven vs content-driven.)
6. Prefix-only, or fuzzy/typo-tolerant ("recieve" → "receive") and mid-string matches?
7. What languages and scripts? Unicode normalization and CJK segmentation change tokenization.
8. What's the abuse/safety bar — who decides what's filtered, and is it region-specific?

> The freshness question is the pivotal one. "Daily batch" lets you precompute everything offline and serve a static, blazing-fast index. "Trending in minutes" forces a streaming aggregation layer and a way to merge fresh counts into the serving structure without rebuilding it.

---

## 2. Capacity Estimation

Real numbers, shown long-hand. Assume a 5-year horizon.

### Write QPS (search events feeding the pipeline)
```
5,000,000,000 searches/day ÷ 86,400 s/day ≈ 57,870 searches/sec  (~58K WPS avg)
Peak factor ~2.5x  →  ~145,000 search-events/sec peak
```
These are the *full queries* we log and aggregate — the raw material for ranking.

### Read QPS (suggestion requests — the hot path)
A user typing a 15-char query, debounced ~every other keystroke, fires roughly 6–8 suggestion calls.
```
5B searches/day × ~6 suggestion calls/search = 30,000,000,000 suggestion reads/day
30,000,000,000 ÷ 86,400 ≈ 347,000 reads/sec  (~350K RPS avg)
Peak ~2.5x  →  ~870,000 RPS peak
```
Reads outnumber writes ~6:1 *and* each read must finish in single-digit milliseconds. The whole design optimizes the suggestion read path.

### Index size — how many distinct queries do we actually serve?
We don't index all 5B daily searches; the long tail is noise. Suggestions come from the **popular head**.
```
Distinct queries searched per day             ≈ ~1 billion
Queries searched ≥ N times (worth suggesting)  ≈ top ~100M distinct queries
Average query length                           ≈ 20 bytes
```
```
Trie / index payload:
  100,000,000 distinct queries × 20 B            = 2 GB raw query text
  + per-node top-k cache (10 × (20B + 4B count)) overhead across ~50M trie nodes
  ≈ 50,000,000 nodes × 240 B                     ≈ 12 GB
  Total serving structure                        ≈ 15–20 GB
```
**The entire serving index fits in RAM** (~15–20 GB, replicated). That single fact is what makes <10 ms p99 achievable — no disk on the hot path.

### Raw log storage (source of truth, 5-year horizon)
```
5B events/day × (query ~20B + ts 8B + userid 8B + ctx 24B) ≈ 5B × 60 B = 300 GB/day
300 GB/day × 365 × 5 = 547,500 GB ≈ 548 TB raw logs over 5 years
Compressed ~5:1 (text)  →  ~110 TB on durable object storage (S3/GCS)
```
Logs are cheap, cold, and durable; the serving index is small, hot, and disposable.

### Bandwidth
```
Read response: ~350K RPS × (10 suggestions × ~25 B) ≈ 350K × 250 B ≈ 87 MB/s avg
               peak ≈ 870K × 250 B ≈ 217 MB/s
Write ingest:  145K events/s × 60 B ≈ 8.7 MB/s
```
As with most read-heavy systems, **request rate and per-request latency are the constraint**, not bandwidth.

### Cache / fanout sizing
With a 15–20 GB index that fits in RAM, we don't need a separate cache *tier* — the serving nodes **are** the cache. We replicate the whole index across, say, 30–50 stateless serving nodes to absorb 870K RPS (≈ 20K RPS/node), and front them with edge caching for the hottest short prefixes.

---

## 3. API Design

REST/HTTP for simplicity and CDN-friendliness; the request is tiny and idempotent (a `GET`), which lets us cache it at the edge. gRPC is a fine alternative for internal service-to-service calls.

```http
# Get suggestions for a prefix — the hot path. Public, cacheable, no auth required.
GET /api/v1/suggest?q=new+y&limit=10&lang=en&country=US
Cache-Control: public, max-age=60
→ 200 OK
{
  "prefix": "new y",
  "suggestions": [
    { "text": "new york",        "score": 0.93 },
    { "text": "new york times",  "score": 0.81 },
    { "text": "new years eve",   "score": 0.42 },
    { "text": "new yorker",      "score": 0.30 }
  ]
}
→ 200 OK { "prefix": "asdfqwer", "suggestions": [] }   // no match: empty, never an error

# (Optional) personalized suggestions — authenticated, NOT edge-cacheable
GET /api/v1/suggest/personalized?q=new+y&limit=10
Authorization: Bearer <token>
→ 200 OK { ... user-biased ordering ... }

# Ingest a completed search (internal; usually the search service emits to a log/stream,
# not a synchronous call from the client)
POST /internal/v1/events
{ "query": "new york", "user_id": "u_123", "ts": 1750000000, "lang": "en", "country": "US" }
→ 202 Accepted

# Admin: suppress / blocklist a suggestion
POST /admin/v1/blocklist   { "pattern": "<term>", "scope": "global|country:DE" }
→ 204 No Content
```

Design notes: the suggest endpoint is a **`GET` with the prefix in the query string** so browsers, CDNs, and our edge layer can cache identical prefixes. We **debounce client-side** (~50–100 ms) and **cancel in-flight requests** when the user keeps typing, so we don't fire one call per literal keystroke. An empty result is `200` with `[]`, never `404` — a missing suggestion is normal, not an error.

---

## 4. Data Model

There are two distinct stores: the **source-of-truth log** and the **derived serving structure**. They have opposite requirements.

### Serving structure — the prefix index
The access pattern is "given a prefix, return the top-k highest-scored completions" at 870K RPS in <10 ms. A **trie (prefix tree)** is the canonical fit: walking the prefix is O(prefix length), independent of corpus size.

Naively, after walking to the prefix node you'd DFS the subtree to find the top-k — too slow for a hot node like `"a"` whose subtree is enormous. The key optimization:

> **Precompute and cache the top-k completions at every trie node.** Each node stores the 10 best queries that pass through it, already sorted by score. A lookup becomes: walk to the prefix node (O(len)), read its cached top-k (O(1)). No subtree traversal at request time.

```
Trie node:
  char            : the edge character (or a compressed substring in a radix/PATRICIA trie)
  children        : map<char, node>
  top_k           : [ (query_text, score) ] × 10   ← precomputed, sorted desc
  is_terminal     : bool   (a full query ends here)
```
- **Memory vs speed trade-off**: caching top-k at every node multiplies storage (every node holds up to 10 strings) but turns request-time work from a subtree scan into a constant read. Given the index fits in ~20 GB RAM, we happily pay the memory.
- **Radix/PATRICIA compression** collapses single-child chains (`n→e→w→ →y...`) into one node, cutting node count and pointer-chasing — important for cache locality at this QPS.

### Alternative serving structures
| Structure | Prefix lookup | Top-k at node | Update cost | Notes |
|---|---|---|---|---|
| **Trie + cached top-k** | O(len) | O(1) | Rebuild affected paths | Our choice — best read latency |
| Sorted list + binary search | O(log n) | scan a range | cheap append | Prefix range = contiguous; weak for ranking/fuzzy |
| **Finite State Transducer (FST)** | O(len) | needs weights | rebuild | Lucene/Tantivy use it — extremely compact, immutable |
| Inverted index / ES `completion` | O(len) | built-in | near-real-time | Easiest to operate; less control over ranking/latency |

For a from-scratch design I present the **trie with node-level top-k**; in production I'd seriously consider **Elasticsearch's completion suggester (FST-backed)** to avoid building and operating a custom index — the FST is essentially a compressed, weighted trie.

### Source-of-truth — aggregated query counts
The streaming/batch pipeline maintains rolling popularity counts that feed index builds.
```
Table: query_stats   (key-value / wide-column, e.g. Cassandra or a KV store)
  query        STRING   PARTITION KEY
  count_total  COUNTER
  count_7d     COUNTER          -- recency-weighted window
  last_seen    TIMESTAMP
  lang,country STRING           -- for segmented indexes
```
Plus the **raw event log** in Kafka → archived to **object storage (S3/GCS)** as the immutable, replayable source of truth. The serving trie is built *from* `query_stats`, never written to directly by user traffic.

### Why this split
The serving index is **derived, immutable-between-builds, and disposable** (rebuildable from logs) → optimize purely for read latency (RAM trie). The logs are **append-only, huge, and durable** → optimize for cheap durable storage (object store) and stream processing (Kafka). Conflating them (e.g. updating a single store on every search and querying it live) would force a structure that's simultaneously write-hot and read-fast — a losing compromise.

---

## 5. High-Level Architecture

```
                         ┌─────────────────────────────┐
                         │       Clients (browsers)     │
                         │  debounce + cancel in-flight │
                         └───────────────┬─────────────┘
                                         │ GET /suggest?q=...
                         ┌───────────────▼─────────────┐
                         │   CDN / Edge cache (short    │  ← caches hot short prefixes
                         │   prefixes, 60s TTL)         │
                         └───────────────┬─────────────┘
                                         │ miss
                         ┌───────────────▼─────────────┐
                         │      Load Balancer (L7)      │
                         └───────────────┬─────────────┘
                                         │
                    ┌────────────────────▼────────────────────┐
                    │   Suggestion Serving Cluster (stateless) │
                    │   each node holds FULL in-RAM trie copy  │
                    │   walk prefix → return cached top-k       │
                    └───────────┬──────────────────▲───────────┘
                                │ load new index    │ atomic swap
                                │                   │
                    ┌───────────▼───────────────────┴───────────┐
                    │      Index Store (versioned snapshots)      │  e.g. S3 + local SSD
                    └───────────▲────────────────────────────────┘
                                │ publishes new trie snapshot
        ┌───────────────────────┴───────────────────────────────────┐
        │              Index Builder (batch + incremental)            │
        │   reads query_stats → builds trie w/ node-level top-k       │
        └───────────▲─────────────────────────────────▲──────────────┘
                    │ aggregated counts                │ trending deltas
     ┌──────────────┴──────────┐          ┌────────────┴──────────────┐
     │  Batch agg (Spark)      │          │ Stream agg (Flink/KStreams)│
     │  daily/hourly full      │          │ Count-Min / windowed counts│
     └──────────────▲──────────┘          └────────────▲──────────────┘
                    │                                   │
              ┌─────┴───────────────────────────────────┴─────┐
              │                    Kafka                        │  ← raw search events
              └──────────────────────▲──────────────────────────┘
                                      │ emit on every completed search
                         ┌────────────┴─────────────┐
                         │   Search service (emits)  │
                         └──────────────────────────┘
```

### Component walkthrough
- **Client**: debounces keystrokes (~50–100 ms) and cancels superseded in-flight requests, so a 15-char query yields ~6 calls, not 15. This is the cheapest latency win and halves backend load for free.
- **CDN / Edge cache**: short, hot prefixes (`"a"`, `"the"`, `"fa"`) are requested by everyone — a 60s edge TTL makes the majority of suggest calls never reach origin. Personalized requests bypass this.
- **Serving cluster**: stateless nodes, each holding a **full copy of the in-RAM trie**. A request walks the prefix and returns the node's precomputed top-k. No DB call, no disk. Scale reads by adding identical nodes.
- **Index Store**: versioned, immutable trie snapshots. Serving nodes download the latest, build it in memory off to the side, then **atomically swap** the live pointer — zero-downtime, no partial reads.
- **Index Builder**: turns aggregated counts into a trie with node-level top-k. Runs a full **batch** rebuild (e.g. hourly/daily from Spark) and merges **incremental trending deltas** from the stream layer.
- **Kafka**: the durable buffer between the search firehose and the aggregators; also the replay log if we ever need to rebuild from scratch.
- **Batch aggregation (Spark)**: authoritative, exact popularity counts over large windows — the backbone of ranking.
- **Stream aggregation (Flink/Kafka Streams)**: approximate, low-latency counts (Count-Min Sketch + sliding windows) to surface trends within minutes.

---

## 6. Deep Dives

### 6.1 Serving fast: trie traversal + precomputed top-k
The whole game is making a prefix lookup O(prefix length) and the top-k retrieval O(1).

```
suggest(prefix):
    node = root
    for ch in prefix:                 # O(len(prefix))
        node = node.children.get(ch)
        if node is None:
            return []                 # no completions for this prefix
    return node.top_k                 # O(1) — already sorted, already trimmed
```
The expensive part is *building* `top_k`, done offline. During a build, the top-k of a node is the merge of its children's top-k plus its own terminal query, capped at k:
```
build_topk(node):                     # bottom-up, post-order
    candidates = []
    if node.is_terminal:
        candidates.append((node.query, node.score))
    for child in node.children:
        build_topk(child)
        candidates += child.top_k
    node.top_k = heap_top_k(candidates, k)   # keep best k by score
```
Because top-k merges are tiny (≤ k per child), the whole trie builds in roughly O(total query length). At request time we do *zero* ranking work. This memory-for-latency trade is the defining decision of the system.

### 6.2 Keeping suggestions fresh — λ (lambda) architecture
A pure daily batch is fast but stale; updating the live trie per search is too write-hot. The answer is a **layered (Lambda) pipeline**:

- **Batch layer** (Spark, hourly/daily): recomputes exact, recency-decayed popularity from the full logs and produces the **authoritative trie snapshot**. Slow but correct.
- **Speed layer** (Flink/Kafka Streams, seconds): maintains windowed approximate counts of *currently trending* queries and emits **delta updates** — "these N queries just spiked." The builder merges these into the latest batch trie to produce a fresher snapshot every few minutes.
- **Serving** reads whichever snapshot is newest.

```
score(query) = α · long_term_count + β · recent_window_count · decay(age)
```
This gives both stable popular suggestions *and* fast-moving trends, without ever mutating the trie under live read traffic. The classic alternative is **Kappa** (stream-only): simpler to operate, but recomputing exact long-window ranking purely in-stream is harder and costlier — most autocomplete systems keep the batch backbone.

### 6.3 Counting at firehose scale without exploding memory
We can't keep an exact integer counter for *every* distinct query in real time — the distinct set is ~1B/day. In the speed layer we use a **Count-Min Sketch (CMS)**: a small 2-D array of counters with d hash functions that estimates counts with bounded over-estimation error in fixed memory.
```
For each search event q:
   for i in 0..d:  cms[i][ hash_i(q) % w ] += 1
estimate(q) = min over i of cms[i][ hash_i(q) % w ]   # never under-counts
```
Pair it with a **heavy-hitters / top-k sketch** (e.g. a Space-Saving / `Frequent` structure) to track only the few thousand queries trending hardest — which is all the speed layer needs to feed deltas. Exact counts stay in the batch layer where memory isn't a per-second concern. This trades a little count accuracy for constant memory and constant-time updates at 145K events/s.

### 6.4 Atomic, zero-downtime index swaps & sharding
Serving nodes hold the whole trie in RAM, so deploying a new index can't block reads.
- **Atomic swap**: a node downloads the new snapshot, builds the new trie in a *separate* object, then flips an `AtomicReference`/pointer from old → new in one instruction. In-flight reads finish on the old trie; new reads hit the new one. Old trie is GC'd once drained. No locks on the read path.
- **Rolling rollout**: swap a few nodes at a time and watch error/latency; a bad index version is caught before it hits the whole fleet, and rollback is just pointing back at the previous snapshot.
- **Sharding (only if the index outgrows RAM)**: at ~20 GB it fits comfortably, so we **replicate, not shard**. If it ever didn't fit, shard the trie **by first character / prefix range** (`a–f`, `g–m`, …) and route requests by their first character. The downside is uneven shards (`s` and `t` are huge; `x`,`z` tiny) — rebalance by splitting hot ranges. Replication-not-sharding is preferred precisely because it keeps every lookup single-hop.

### 6.5 Fuzzy / typo tolerance and Unicode
Strict prefix matching breaks on typos (`"recieve"`) and on languages where "prefix" is fuzzy.
- **Normalization first**: lowercase, Unicode NFKC-normalize, strip/standardize accents (`café`→`cafe`) so `"cafe"` and `"café"` collide in the trie. For CJK, segment with a language-aware tokenizer.
- **Typo tolerance**: build the trie tolerant to small edit distance using a **Levenshtein automaton** intersected with the trie/FST (Lucene does exactly this) — it finds all completions within edit distance 1–2 of the prefix efficiently. Cheaper approximations: store common misspelling→correction maps, or generate deletion-variants (SymSpell) at build time.
- **Trade-off**: every bit of fuzziness widens the candidate set and costs latency, so we usually allow edit distance 0 for short prefixes (≤3 chars, where errors are ambiguous) and 1 for longer ones, and we never let fuzzy matching blow the <10 ms budget — it's bounded by automaton intersection, not a full scan.

### 6.6 Safety, abuse, and personalization
- **Abuse / poisoning**: a bot spamming `"buy my thing"` shouldn't make it trend. Defend by **counting distinct users, not raw events** (or de-duping per user/session), rate-limiting per IP/user, and using the heavy-hitters sketch's robustness to drop single-source spikes. Anything new gets a brief review window before it can appear.
- **Safety filtering**: maintain global and region-specific **blocklists** (profanity, hate, legally-suppressed terms — e.g. right-to-be-forgotten in the EU) applied at *build time* (strip from the trie) and re-checked at *serve time* (a fast bloom-filter/regex pass) so a hotfix blocklist takes effect without a full rebuild. **Never surface PII** — a build-time classifier drops queries that look like emails, card numbers, SSNs, etc.
- **Personalization**: blend the global top-k with a per-user/per-session signal. Keep a small per-user recent-query list (Redis) and re-rank the global candidates client-side or in a thin personalization service. This bypasses the edge cache, so we only personalize for signed-in users and keep the anonymous path fully cacheable.

---

## 7. Scaling, Bottlenecks & Failure Handling

**What breaks first?** The **read path** — 870K RPS of latency-critical lookups dwarfs everything else. Order of stress: (1) serving-node CPU / memory bandwidth, (2) edge cache hit rate, (3) index build/distribution time as the corpus grows, (4) stream aggregator lag, (5) Kafka ingestion.

- **Read scaling = replication**: serving nodes are stateless and each holds the full trie, so adding nodes linearly adds read capacity. At ~20K RPS/node, peak 870K RPS needs ~45 nodes plus headroom — trivial to autoscale, and the edge cache shaves a large fraction before it ever reaches origin.
- **Edge offload**: short prefixes are requested by nearly everyone; a 30–60s edge TTL on the anonymous path can absorb well over half of total volume. Personalized traffic intentionally skips the cache and is a small fraction.
- **Index distribution**: a 20 GB snapshot pushed to 45+ nodes can saturate the network if done naively. Distribute via the CDN/object store with **staggered, throttled downloads** and build-off-to-the-side + atomic swap so pulling a new index never stalls serving.
- **Pipeline scaling**: Kafka partitions and Flink/Spark workers scale horizontally; the speed layer's sketches are fixed-memory by design, so they don't grow with traffic.
- **Failure modes & mitigations**:
  - *A serving node dies* → LB removes it; replicas cover it. Stateless + full-replica means zero data loss and instant failover.
  - *Index build pipeline fails / produces a bad index* → serving nodes **keep serving the last-known-good snapshot** indefinitely. Staleness degrades gracefully (suggestions a bit out of date) rather than an outage. Bad snapshots are caught by canary rollout and rolled back by repointing.
  - *Kafka / aggregators down* → trending freshness pauses, but the batch trie still serves. We lose new-trend latency, not the feature.
  - *Whole autocomplete service down* → the search box still works; the client just shows no suggestions. We **fail open**: a suggestion error returns empty, never blocks the user's ability to search.
  - *Thundering herd on a cold edge node* → request coalescing / single-flight per prefix, plus jittered TTLs.
- **DR**: the raw logs in object storage are the durable source of truth; the entire serving index can be rebuilt from them. RPO for the index is effectively "whatever the last batch build captured," which is acceptable because the index is derived, not authoritative.

**The key resilience property**: serving is decoupled from building. The pipeline can be completely broken and users still get (stale) suggestions — the system degrades, it doesn't fall over.

---

## 8. Trade-offs & Alternatives

- **Trie with node-level top-k vs. live ranking**: precomputing top-k at every node trades memory (10 strings per node) for O(1) request-time ranking. **Chosen: precompute** — at <10 ms p99 we can't afford any subtree scan, and 20 GB of RAM is cheap.
- **Replicate vs. shard the index**: replication keeps every lookup single-hop and simplifies ops; sharding by first character is only needed if the index outgrows RAM and brings skew headaches. **Chosen: replicate** at this scale.
- **Batch vs. streaming freshness (Lambda vs Kappa)**: Lambda (batch backbone + speed layer) gives exact long-term ranking *and* minute-fresh trends at the cost of running two pipelines; Kappa (stream-only) is simpler to operate but harder to get exact long-window ranking from. **Chosen: Lambda** because ranking quality matters and the batch layer is the cheapest place to be exact.
- **Custom trie vs. Elasticsearch completion suggester**: a custom FST/trie gives maximal control over latency and ranking; ES gives near-real-time updates and far less to operate. **In an interview I design the trie; in production I'd default to ES's FST-backed suggester** unless its latency/ranking ceiling proved insufficient.
- **Exact vs. approximate counts**: exact counts everywhere is infeasible in the speed layer; Count-Min + heavy-hitters sketches give bounded-error counts in fixed memory. **Chosen: approximate in the speed layer, exact in batch.**
- **301-style heavy edge caching vs. personalization**: edge caching the anonymous path is the single biggest scaling lever, but it's incompatible with per-user results. **Chosen: cache the anonymous path hard, personalize only for signed-in users** on a separate, uncached path.
- **CAP**: we choose **AP** throughout — autocomplete is an enhancement, so we always prefer serving a possibly-stale suggestion (or empty) over failing or blocking. There is no scenario where we'd reject a search because suggestions are unavailable.

**At 10× scale (~8.7M RPS reads)**: push almost everything to the edge — replicate read-only trie shards into regional PoPs / edge KV, debounce more aggressively client-side, and regionalize the index (locale-specific tries) so each region serves a smaller, hotter structure.

**At 100× scale**: it becomes an **edge-resolved** system — compact FST snapshots distributed to every PoP, lookups answered at the edge with no origin round-trip, and the central pipeline only publishing new immutable snapshots. The batch/speed pipeline grows by partitioning the firehose per-region and merging trends asynchronously.

---

## Interview Q&A by Level

### 🟢 Basic

**Q. [Theory] Why is a trie the natural data structure for autocomplete, and what's its lookup cost?**
A: Autocomplete is fundamentally "find everything that starts with this prefix." A trie stores strings by shared prefix, so walking down it spells out the prefix character by character — the cost is **O(length of the prefix)**, completely independent of how many queries are in the corpus. From the prefix node, all completions live in its subtree. That prefix-sharing also compresses storage, since `"new york"` and `"new years eve"` share the `"new y"` path.

**Q. [Theory] Why is this system read-heavy, and why does that shape the design?**
A: Suggestions fire on (almost) every keystroke, so reads outnumber the actual searches several times over — roughly 350K RPS average, ~870K peak, versus ~58K search events/s. And every read has a brutal <10 ms server-side budget. So the whole design optimizes reads: a small in-RAM index, precomputed top-k, stateless replicated serving nodes, and heavy edge caching. Write/ingest is comparatively relaxed and handled asynchronously.

**Q. [Practical] If autocomplete goes down, what should the user experience be?**
A: Nothing should break. Autocomplete is an enhancement on top of search, not search itself. We **fail open**: a suggestion error returns an empty list, the dropdown just doesn't appear, and the user can still type their full query and hit enter. That's why we target 99.9% (not five-nines) and choose availability over correctness everywhere.

### 🟡 Intermediate

**Q. [Theory] A naive trie still has to scan a huge subtree to rank completions. How do you make ranking fast?**
A: You **precompute the top-k completions at every node** during the offline build and store them sorted. Then a request walks to the prefix node (O(prefix length)) and reads its cached top-k in O(1) — no subtree traversal at request time. It costs memory (up to k strings per node), but since the whole index fits in ~20 GB RAM, paying memory to eliminate request-time ranking is exactly the right trade for a <10 ms budget.

**Q. [Practical] How do you keep suggestions fresh — surfacing a query that's trending right now?**
A: A Lambda-style pipeline. A **batch layer** (Spark) recomputes exact, recency-decayed popularity over the full logs and publishes an authoritative trie snapshot hourly/daily. A **speed layer** (Flink/Kafka Streams) tracks windowed approximate counts and emits "these queries just spiked" deltas every few minutes, which the builder merges into the latest snapshot. We never mutate the live trie under read traffic — we build a new snapshot and atomically swap it in.

**Q. [Practical] How do you deploy a new index to live serving nodes with zero downtime?**
A: Each node downloads the new snapshot, builds the new trie in a *separate* in-memory object, then flips a single atomic pointer from the old trie to the new one. In-flight reads finish on the old trie; new reads hit the new one; the old one is garbage-collected once drained. We roll this out node-by-node as a canary, watch latency/error rates, and roll back by simply repointing to the previous snapshot if the new index looks bad.

**Q. [Theory] Where's your source of truth, and why isn't it the trie?**
A: The source of truth is the **raw query log** in Kafka, archived to object storage. The trie is **derived data** — it can always be rebuilt from the logs. That separation lets each store specialize: logs optimize for cheap, durable, append-only storage; the trie optimizes purely for read latency (RAM, immutable between builds). If a trie node is corrupted or a build is bad, we just rebuild — no data is ever lost because the index isn't authoritative.

### 🟠 Advanced

**Q. [Theory] At 145K search events/s you can't keep an exact counter per distinct query in the speed layer. What do you do?**
A: Use probabilistic counting. A **Count-Min Sketch** estimates query frequencies in fixed memory with bounded over-estimation (it never under-counts), and a **heavy-hitters sketch** (Space-Saving) tracks only the few thousand hardest-trending queries — which is all the speed layer needs to emit deltas. Exact counts live in the batch layer where per-second memory isn't a constraint. We trade a little count accuracy in the real-time path for constant memory and constant-time updates.

**Q. [Practical] One bot spams a query 10 million times. How do you stop it from polluting suggestions?**
A: Rank on **distinct users, not raw event count** — de-dupe per user/session so 10M hits from one source count as ~1. Layer on per-IP/per-user rate limiting, and rely on the heavy-hitters sketch being robust to single-source spikes. New or sharply spiking queries also get a short review/aging window before they're eligible to appear, and a build-time + serve-time blocklist (with PII and profanity classifiers) catches anything that slips through.

**Q. [Coding] Write the core suggest lookup and the offline top-k build for the trie.**
A:
```python
class Node:
    __slots__ = ("children", "top_k", "query", "score", "is_terminal")
    def __init__(self):
        self.children = {}          # char -> Node
        self.top_k = []             # [(query, score)] sorted desc, len <= K
        self.query = None
        self.score = 0.0
        self.is_terminal = False

K = 10

# ---- hot path: O(len(prefix)) walk, O(1) top-k read ----
def suggest(root, prefix):
    node = root
    for ch in prefix:
        node = node.children.get(ch)
        if node is None:
            return []               # no completions; return empty, never error
    return node.top_k               # already sorted & trimmed at build time

# ---- offline build: post-order merge of children's top-k ----
import heapq
def build_topk(node):
    cands = []
    if node.is_terminal:
        cands.append((node.score, node.query))
    for child in node.children.values():
        build_topk(child)
        cands.extend((s, q) for (q, s) in child.top_k)
    # keep the K highest-scoring; heapq.nlargest is fine since cands is small (<= K*fanout)
    best = heapq.nlargest(K, cands)
    node.top_k = [(q, s) for (s, q) in best]
```
The lookup does zero ranking work; all the cost is pushed into the offline `build_topk`, which runs bottom-up and merges only ≤K items per child, so the whole build is roughly linear in total query length.

**Q. [Theory] How would you add typo tolerance without blowing the latency budget?**
A: Intersect a **Levenshtein automaton** (which accepts all strings within edit distance d of the prefix) with the trie/FST — Lucene does exactly this. It enumerates all completions within edit distance 1–2 efficiently, bounded by the automaton, not a full scan. To protect latency I'd allow edit distance 0 for very short prefixes (≤3 chars, where corrections are ambiguous anyway) and 1 for longer ones, and cap candidate expansion. Cheaper approximations are precomputed misspelling→correction maps or SymSpell-style deletion variants generated at build time.

### 🔴 Expert

**Q. [Practical] Redesign for 100× scale and true global low latency.**
A: It becomes an **edge-resolved** system. Build compact, immutable **FST snapshots** and distribute them to every PoP (edge KV / regional read replicas), so a suggest call is answered at the edge with no origin round-trip. **Regionalize** the index by locale/language so each PoP serves a smaller, hotter trie. The central pipeline partitions the search firehose per region, runs batch + speed layers per region, and merges global trends asynchronously. The origin's only job becomes publishing new snapshots; reads never touch it. Personalization stays as a thin, uncached overlay for signed-in users layered on top of the cached anonymous results.

**Q. [Theory] How do you reconcile exact long-term ranking with minute-fresh trends mathematically, and what's the failure behavior if the speed layer dies?**
A: Score is a blend: `score(q) = α·long_term_decayed_count + β·recent_window_count·decay(age)`. The batch layer owns the exact `long_term_decayed_count`; the speed layer owns the approximate `recent_window_count`. If the speed layer dies, β-driven freshness simply stops updating — the batch trie keeps serving, so we lose *new-trend latency* but not the feature or the bulk of ranking quality. That graceful degradation is intentional: the two layers are independent, and the authoritative one is the slow, robust one.

**Q. [Practical] You see a p99 latency regression on suggestions, but only for short prefixes (1–2 chars). How do you debug it?**
A: Short prefixes hit the hottest nodes and the most candidates, so I'd suspect either the edge cache hit-rate dropped (forcing more short-prefix traffic to origin) or a new index version made the top-roots' top-k computation/serialization heavier. I'd check: edge cache hit-rate by prefix length, per-node CPU and GC pauses on serving boxes (a 20 GB trie swap can trigger GC), index version correlation (did p99 jump exactly at a snapshot rollout?), and whether fuzzy matching is being applied to short prefixes when it shouldn't be. Most likely culprits: a regressed index, GC during atomic swap, or accidental edit-distance expansion on short prefixes — confirm with per-stage tracing and roll back the index as the fast mitigation.

**Q. [Behavioral] Product wants per-keystroke personalized suggestions for everyone; it would cut your edge cache hit-rate to near zero and roughly 5× serving cost. How do you handle that conversation?**
A: I'd reframe it around data and trade-offs rather than just saying no. First, quantify it: personalization breaks edge caching (every result is user-specific), so we lose the single biggest scaling lever and serving cost multiplies — I'd bring the projected infra cost and latency impact. Then I'd propose a middle path that captures most of the value for a fraction of the cost: keep the anonymous, fully-cached global suggestions as the base, and apply a **lightweight personalization re-rank only for signed-in users** (and maybe only on the first few characters where personal history is most predictive), ideally client-side using a small recent-query list so we don't pay a server round-trip. I'd suggest an A/B test measuring suggestion click-through lift from full personalization vs. the cheap re-rank — if full personalization doesn't move the metric enough to justify 5× cost, the data makes the decision for us. The goal is to align on the user outcome (better suggestions) and let the experiment, not opinion, settle the cost trade-off.

---

*Key takeaway: autocomplete is a masterclass in read-optimized derived-data design — the real engineering is a precomputed-top-k trie served entirely from RAM, a Lambda pipeline that keeps it fresh from a search firehose using probabilistic counting, and atomic index swaps plus fail-open behavior so the feature degrades gracefully instead of ever blocking the user's search.*
