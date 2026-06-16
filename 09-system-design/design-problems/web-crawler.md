# Design a Distributed Web Crawler

> A worked, interview-grade design of a web-scale crawler: fetch billions of pages politely and at high throughput, discover and prioritize links, detect duplicates, avoid traps, and feed a clean document stream downstream — all while being fault-tolerant, restartable, and a good citizen of the web.

[← Back to master index](../../README.md) · [← System Design index](../README.md)

---

## 1. Requirements

A crawler sounds like "just fetch URLs in a loop," but the interviewer is probing distributed coordination, politeness, dedup, prioritization, and how you keep an unbounded, adversarial workload from melting your fleet or getting you banned. Pin down scope before drawing anything.

### Functional requirements
- **Seed & discover**: start from a seed set, fetch each page, extract outlinks, and feed new URLs back into the system (the crawl expands itself).
- **Fetch**: download page content over HTTP(S), following redirects, handling 4xx/5xx, timeouts, and content-type filtering (HTML first; configurable for PDFs/images).
- **Politeness**: obey `robots.txt`, per-host crawl-delay, and `Crawl-delay`/`Retry-After`; never hammer a single host.
- **Deduplication**: don't re-fetch the same URL needlessly (URL-level) and don't store the same content twice (content-level near-dup detection).
- **Prioritization / freshness**: crawl high-value and fast-changing pages more often; re-crawl on a schedule driven by observed change rate.
- **Output**: emit parsed, deduped documents (URL, content, outlinks, metadata) to a downstream pipeline (indexer, ML, archive).
- **Trap avoidance**: detect and escape infinite spaces (calendars, faceted-search permutations, session-id URLs).

### Non-functional requirements
| Dimension | Target | Why |
|---|---|---|
| **Scale** | Crawl **~10 B pages** on a ~30-day refresh cycle | Web-scale, Google-lite. Drives the sustained fetch rate below. |
| **Throughput** | **~5,000 pages/sec** sustained, headroom to ~15K peak | `10B / (30 × 86,400s) ≈ 3,860/s`; provision ~5K for re-crawls + retries. |
| **Politeness latency** | ≥ 1 req/host default, configurable; respect `Crawl-delay` | A polite crawler is a *slow-per-host, wide-across-hosts* system. This is the central tension. |
| **Availability** | **99.9%** for the crawl pipeline | A few minutes down is fine — the frontier is durable and we resume. The crawl is a background process, not user-facing. |
| **Durability** | Frontier + "seen" state + crawled corpus must survive node loss | Re-deriving 10 B URLs or re-fetching a crawled corpus is enormously expensive and rude to hosts. |
| **Consistency** | **Eventual** everywhere | A URL crawled minutes late, or seen twice under a race, is acceptable. We never trade availability/throughput for strong consistency here. |
| **Security / etiquette** | Honor robots, identify via `User-Agent`, rate-limit, avoid login walls and infinite traps | Getting IP-banned or legally complained-about defeats the whole system. |

### Clarifying questions a strong candidate asks
1. **Scope** — the open web, or a bounded corpus (one company's sites, a vertical like job postings)? Bounded crawls are orders of magnitude simpler (no trap defense, known politeness budget).
2. **Refresh model** — one-shot snapshot, or continuous re-crawl for freshness? Continuous changes the frontier from a queue to a *priority + schedule* problem.
3. **What content types** — HTML only, or also PDFs/images/JS-rendered SPAs? JS rendering (headless browser) is 10–50× more expensive per page.
4. **Freshness SLA per content class** — news in minutes vs. static pages in weeks? Determines whether we need a fast-lane.
5. **Politeness budget** — default crawl-delay, and do we have allow-lists/partnerships that let us crawl certain hosts faster?
6. **Output contract** — who consumes the docs (indexer, ML training, archive) and in what format/throughput?
7. **Compliance** — do we honor `noindex`/`nofollow`, GDPR right-to-be-forgotten, and per-host opt-out?

> The most important early decision is *continuous re-crawl vs. one-shot*. The rest of this doc assumes a **continuous, web-scale crawl** with per-content-class freshness, because that's the version that exercises the hard sub-problems.

---

## 2. Capacity Estimation

Back-of-the-envelope, shown long-hand. The assumptions matter more than the digits.

### Fetch throughput
```
Target corpus:        10,000,000,000 pages
Refresh cycle:        30 days = 30 × 86,400 = 2,592,000 s
Sustained rate:       10e9 / 2.592e6 ≈ 3,858 pages/sec  (call it ~3.9K/s avg)
Add re-crawls + retries + redirects (~30% overhead) → provision ~5,000/s
Peak / fast-lane bursts: ~15,000/s
```

### Bandwidth (ingress — the dominant cost)
```
Avg raw page over the wire (HTML, gzipped):  ~100 KB
Download bandwidth: 5,000 pages/s × 100 KB ≈ 500 MB/s ≈ 4 Gbps sustained
Peak:               15,000 × 100 KB ≈ 1.5 GB/s ≈ 12 Gbps
```
Bandwidth — not CPU — is usually the first wall for a fetcher fleet. JS-rendered pages would 10× the CPU/RAM per page (headless Chrome), changing the math entirely.

### Storage over a 5-year horizon
**Raw page store** (we keep raw HTML for re-parsing):
```
Distinct pages (5 yr, with growth): ~30 B unique pages
Raw HTML compressed ~5:1 from ~100 KB → ~20 KB stored
30e9 × 20 KB = 6e14 B = 600 TB raw
With 3× replication: ~1.8 PB
```
**Parsed/extracted text** (smaller, what downstream usually reads):
```
~10 KB text/page → 30e9 × 10 KB = 3e14 B = 300 TB  (×3 repl ≈ 900 TB)
```
**"Seen URLs" set** (the dedup membership structure — this is the sneaky-big one):
```
Distinct URLs discovered (≫ pages crawled, ~3–5× due to dead/dup/filtered links):
   ~50 B URLs
Naive: 50e9 × ~100 B/URL (normalized) = 5e12 B = 5 TB just for the strings + index
Better: a Bloom filter at ~10 bits/element for ~1% FP:
   50e9 × 10 bits = 5e11 bits = 62.5 GB  (fits in RAM, sharded)
```
The **seen-URL set is the memory-critical structure**, and a Bloom/Cuckoo filter is what makes it fit in RAM. More in Deep Dive 6.3.

### Frontier sizing (URLs waiting to be crawled)
```
Outlinks per page: ~50 raw, ~10 new/unseen after dedup
Backlog can balloon: even at steady state, frontier holds 10s of millions of URLs.
Estimate: ~100 M URLs in-flight × ~200 B (URL + priority + metadata) = 20 GB
Persist on disk/Kafka/DB; keep only the hot head in memory per host queue.
```

### Compute / fetcher fleet
```
A single fetcher node, async I/O (epoll/Netty/async Python), can hold
~10,000 concurrent connections, completing ~1,000–2,000 fetches/sec
(I/O-bound, mostly waiting on the network).
For 15K/s peak: ~10–15 fetcher nodes for fetching alone.
Parsing is CPU-heavy: budget a comparable parser fleet.
JS rendering, if required, needs ~50–100× more nodes — a separate, scaled-down fleet.
```

**Takeaways that drive the design:** (1) bandwidth + politeness, not CPU, bound the fetcher; (2) the seen-set must be a probabilistic structure to fit in RAM; (3) the frontier is a *prioritized, per-host-rate-limited* queue, not a plain FIFO; (4) everything must be durable + restartable because re-crawling is expensive and rude.

---

## 3. API Design

A crawler is mostly an internal pipeline, not a public REST service. The "API" is the **operator/control plane** plus the **internal queue contracts** between stages.

```http
# --- Control plane (operators / other systems submit work) ---

# Submit seed URLs or an on-demand crawl request
POST /v1/crawl/seed
Authorization: Bearer <token>
{
  "urls": ["https://example.com/", "https://news.example.org/"],
  "priority": "high",            // high | normal | low
  "max_depth": 5,
  "respect_robots": true,
  "content_types": ["text/html"]
}
→ 202 Accepted { "job_id": "j-9f3a", "accepted": 2, "rejected": 0 }

# Inspect crawl status / stats
GET /v1/crawl/jobs/{job_id}
→ 200 { "job_id":"j-9f3a", "state":"running",
        "fetched": 184203, "queued": 51120, "errors": 312, "blocked_by_robots": 88 }

# Per-host policy override (allow-list partner to crawl faster, or block a host)
PUT /v1/policy/hosts/example.com
{ "crawl_delay_ms": 200, "max_concurrency": 4, "enabled": true }
→ 204 No Content

# Force re-crawl of a URL (e.g. content changed, manual flush)
POST /v1/crawl/recrawl  { "url": "https://example.com/page" }
→ 202 Accepted

# --- Internal stage contracts (message-queue payloads, not HTTP) ---

# Frontier → Fetcher  (a unit of fetchable work, already politeness-cleared)
FetchTask {
  url, host, priority, scheduled_at, retry_count, etag, last_modified
}

# Fetcher → Parser  (raw fetched page)
RawPage {
  url, final_url, status, headers, content_hash, body_ref (blob store),
  fetched_at, from_cache (304?)
}

# Parser → Frontier (new outlinks) + → Output (clean doc)
ParsedDoc {
  url, title, text_ref, outlinks[], lang, content_hash, sim_hash,
  links_normalized[], discovered_at
}
```

Design notes:
- The control plane is small and **async** (`202`): submitting seeds enqueues work; nothing blocks on the crawl itself.
- The real "API" is the **typed messages on the queues** between Frontier → Fetcher → Parser → Output. Each stage is independently scalable and restartable, communicating through durable topics (Kafka).
- `etag`/`last_modified` ride along so the fetcher can send `If-None-Match` / `If-Modified-Since` and get cheap `304 Not Modified` responses on re-crawl.

---

## 4. Data Model

A crawler is not one database — it's several purpose-built stores, each tuned to a very different access pattern.

### a) URL Frontier (the prioritized work queue)
Conceptually a set of **per-host FIFO queues** behind a **priority selector**, made durable so a crash doesn't lose the backlog.
```
Frontier entry:
  url            STRING   (normalized, canonical)
  host           STRING   (shard/queue key)
  priority       INT      (0=highest; derived from authority × freshness need)
  scheduled_at   TIMESTAMP (earliest time we're allowed to fetch — politeness)
  depth          INT
  retry_count    INT
  etag           STRING (nullable)
  last_modified  STRING (nullable)
```
- **Storage engine:** a durable log/queue (**Kafka**) for the firehose of discovered URLs, plus a **per-host queue layer** (often Redis sorted sets keyed by `scheduled_at`, or RocksDB) that enforces ordering + politeness timing. Why not a plain SQL queue? At 100M+ entries with constant enqueue/dequeue and per-host rate gates, a log + in-memory priority structure massively outperforms row-level locking in an RDBMS.

### b) "Seen URLs" set (dedup membership)
```
Logical: set membership test  "have we ever queued/crawled this URL?"
Physical: a sharded Bloom (or Cuckoo) filter, ~62 GB for 50B URLs @ ~1% FP,
          backed by an authoritative KV store (RocksDB / Cassandra) of url_hash → metadata
          for cases where a false positive must be resolved exactly.
```
- **Why a filter, not a table:** an exact 50 B-row set is multi-TB and turns every link-discovery into a random read. A Bloom filter answers "definitely-new vs. probably-seen" in RAM at O(1); the rare false positive just means we *skip* a URL we hadn't actually seen — acceptable. (Cuckoo filters add deletion, useful for expiring stale URLs.)

### c) Crawl metadata store (per-URL history & scheduling)
```
Table: pages  (Cassandra / wide-column)
  url_hash       PARTITION KEY
  url            STRING
  last_crawled   TIMESTAMP
  last_status    INT
  content_hash   STRING        -- detect change since last crawl
  sim_hash       BIGINT        -- near-duplicate detection
  change_rate    FLOAT         -- observed; drives re-crawl interval
  next_crawl_at  TIMESTAMP
  page_rank      FLOAT         -- authority signal for prioritization
```
- **Why wide-column NoSQL:** tens of billions of rows, key-based access by `url_hash`, write-heavy (every crawl updates a row), no joins. Cassandra/HBase give linear horizontal scale + multi-DC replication.

### d) Raw + parsed content store
```
Raw HTML:   object store (S3/GCS) keyed by content_hash, compressed.
Parsed text: same, or a columnar store if downstream queries it.
```
- **Why object storage:** cheapest durable bytes at PB scale; content-addressed by `content_hash` gives **free content-level dedup** (identical bodies collapse to one object).

### e) robots.txt cache
```
host → { rules, crawl_delay, fetched_at, ttl }   in Redis, TTL ~24h
```

| Workload | Store | Consistency |
|---|---|---|
| URL firehose | Kafka | At-least-once |
| Per-host queues + timing | Redis (sorted sets) / RocksDB | Best-effort |
| Seen set | Sharded Bloom/Cuckoo filter | Eventual (FP-tolerant) |
| Per-URL metadata | Cassandra | Eventual |
| Raw/parsed content | S3/GCS (content-addressed) | Strong (immutable objects) |
| robots.txt | Redis (TTL) | Eventual |

---

## 5. High-Level Architecture

```
                ┌─────────────┐
   seeds ─────▶ │  Control     │  POST /seed, policy, recrawl
                │  Plane / API │
                └──────┬───────┘
                       │ enqueue
                       ▼
        ┌───────────────────────────────────────────────────────────┐
        │                     URL  FRONTIER                            │
        │                                                              │
        │   ┌────────────┐    front queues (PRIORITY)                  │
        │   │ Prioritizer│──▶  [P0][P1]...[Pn]   (authority×freshness)  │
        │   └────────────┘            │                                │
        │                             ▼                                │
        │   ┌──────────────────────────────────────────────┐          │
        │   │ Per-HOST back queues  (one FIFO per host)      │          │
        │   │  host_a:[u,u,u]  host_b:[u,u]  ...             │          │
        │   │  + heap keyed by scheduled_at (politeness)     │          │
        │   └──────────────────────────────────────────────┘          │
        └───────────────────────────┬──────────────────────────────────┘
                                     │ FetchTask (host ready, delay elapsed)
                                     ▼
   ┌──────────┐   robots?   ┌────────────────┐   RawPage   ┌──────────────┐
   │ robots.txt│◀──────────│   FETCHER FLEET  │────────────▶│   PARSER     │
   │  cache    │──rules──▶  │ (async I/O,      │             │  FLEET       │
   │ (Redis)   │            │  DNS cache,      │             │ extract text,│
   └──────────┘            │  conn pool,      │             │ outlinks,    │
                            │  If-None-Match)  │             │ sim/content  │
                            └───────┬──────────┘             │ hash, lang   │
                                    │ store raw              └──┬────────┬──┘
                                    ▼                           │        │ outlinks
                           ┌─────────────────┐                  │        ▼
                           │ Raw Content Store│                  │   ┌─────────────┐
                           │ (S3, by content_ │                  │   │ URL Filter + │
                           │  hash → dedup)   │                  │   │ Normalizer   │
                           └─────────────────┘                  │   │ + SEEN set   │
                                                                 │   │ (Bloom)      │
        ┌──────────────────┐                                     │   └──────┬──────┘
        │ Crawl Metadata DB │◀──── update last_crawled, ─────────┘          │ new URLs
        │ (Cassandra):      │      content_hash, change_rate                 │
        │ next_crawl_at,    │                                                ▼
        │ change_rate,      │                                    (back to FRONTIER)
        │ page_rank         │
        └────────┬─────────┘
                 │ scheduler emits due re-crawls
                 ▼
          (re-enqueue to FRONTIER)        ParsedDoc ──▶  OUTPUT (Kafka) ──▶ Indexer / ML / Archive
```

### Component walkthrough (the crawl loop)
1. **Control plane** seeds URLs and accepts policy overrides / on-demand re-crawls. It just enqueues into the frontier; it never blocks.
2. **URL Frontier** is the heart. A **prioritizer** assigns each URL to a priority band (authority × freshness need). URLs then land in **per-host back queues** so we can enforce *one fetch at a time per host* with the required delay; a min-heap keyed by `scheduled_at` decides which host is next eligible. (See Deep Dive 6.1.)
3. **Fetcher fleet** pulls FetchTasks for hosts whose politeness window has elapsed. Each fetcher checks the **robots.txt cache** (fetching/parsing robots on a cache miss), resolves DNS (with a local DNS cache — DNS is a real bottleneck), opens a pooled HTTP(S) connection, sends conditional headers (`If-None-Match`/`If-Modified-Since`), and downloads. It writes the raw body to the **content store** (keyed by `content_hash`) and emits a `RawPage`.
4. **Parser fleet** extracts text, title, language, and **outlinks**; computes `content_hash` (exact dup) and `sim_hash` (near-dup); and emits a clean `ParsedDoc` to the **output topic**.
5. **URL filter + normalizer + seen-set**: outlinks are canonicalized (normalize scheme/case/sort query params, strip session IDs), filtered (content-type, robots, trap heuristics), then checked against the **Bloom seen-set**. Genuinely new URLs are fed **back into the frontier**, closing the loop.
6. **Crawl metadata DB** records `last_crawled`, `content_hash`, and an updated **change_rate**; a **scheduler** periodically scans for `next_crawl_at <= now` and re-enqueues due pages — this is the freshness/re-crawl engine.

The whole pipeline is a set of **stateless, independently-scalable stages connected by durable queues**, so any stage can crash and resume from the last committed offset.

---

## 6. Deep Dives

### 6.1 The URL Frontier: priority + politeness without a hot host

The frontier must satisfy two *competing* goals simultaneously:
- **Priority**: crawl important/fresh pages first.
- **Politeness**: never exceed one host's rate limit, even if that host has thousands of high-priority URLs.

A single global priority queue fails politeness (it would drain one big site as fast as possible). A single per-host FIFO fails priority. The classic answer (Mercator-style) is a **two-level queue**:

```
  Discovered URL
        │  assign priority band by f(page_rank, change_rate, depth)
        ▼
  ┌──────────────── FRONT QUEUES (priority) ─────────────────┐
  │  P0  P1  P2 ... Pn      (weighted random / strict pick)   │
  └──────────────────────────┬───────────────────────────────┘
                              │ router maps URL → its host's back queue
                              ▼
  ┌──────────────── BACK QUEUES (one per host) ──────────────┐
  │  host_a:[…]   host_b:[…]   host_c:[…]   (FIFO each)       │
  └──────────────────────────┬───────────────────────────────┘
                              │
                   ┌──────────▼──────────┐
                   │  Heap by next_ok_at │  ← min-heap of (host, earliest fetch time)
                   └──────────┬──────────┘
                              ▼
                   Fetcher pops the host whose next_ok_at ≤ now,
                   takes its front URL, then sets
                   next_ok_at = now + crawl_delay(host)
```

- **Front queues** encode priority; **back queues** (one per host) plus the **time heap** encode politeness. A worker only ever pulls from a host whose delay has elapsed, so a single popular host can't be hammered no matter how many high-priority URLs it has.
- **Distributed frontier**: shard by **`hash(host)`** so all URLs of a host live on one frontier shard — this lets that shard own the host's politeness state with no cross-shard coordination. Adding shards scales the backlog linearly.
- **Starvation guard**: weighted (not strict) priority selection so low-priority URLs still drain eventually.
- **Durability**: the firehose persists to Kafka; the per-host queues + heap are reconstructable from it on restart. We checkpoint offsets so a crash resumes, not restarts.

**Trade-off:** per-host back queues bound throughput-per-host but waste capacity if you have few huge hosts and idle fetchers. Mitigate by allow-listing partner hosts to higher concurrency and by ensuring host *breadth* (crawl 50K hosts in parallel, slowly each).

### 6.2 Politeness, robots.txt, and not getting banned

Politeness is the difference between a crawler and a DDoS.
- **robots.txt**: on first contact with a host, fetch `/robots.txt`, parse `Disallow`/`Allow`/`Crawl-delay`/`Sitemap`, cache it (TTL ~24h). Every URL is checked against cached rules *before* it ever enters a back queue. Honor `Retry-After` on 429/503.
- **Per-host rate limiting**: default ≥ 1s between requests to the same host; obey a longer `Crawl-delay` if present. Rate-limit by **registrable domain + IP** — many hosts share an IP (shared hosting/CDN), so politeness must consider the *server*, not just the hostname, to avoid overwhelming one physical box.
- **Identify yourself**: a descriptive `User-Agent` with a contact URL, so admins can reach you instead of blocking blindly.
- **Adaptive backoff**: if a host starts returning 5xx or its latency climbs, *back off automatically* — treat it as a signal the host is struggling. Exponential backoff + jitter.
- **DNS politeness & caching**: cache DNS aggressively (TTL-aware); uncached DNS lookups at 5K/s will hammer resolvers and become your bottleneck. Run a local caching resolver fleet.

**Trade-off:** strict politeness caps per-host throughput, so total throughput comes from **host breadth, not host depth**. A crawler is fundamentally "fetch a little from a huge number of hosts concurrently."

### 6.3 URL dedup at scale: Bloom filters & normalization

We must answer "have we seen this URL?" ~50 B times with low memory.

**Normalization first** (so trivially-different URLs collapse to one):
```
- lowercase scheme + host
- remove default ports (:80/:443), default pages, fragments (#...)
- sort query params; strip known tracking/session params (utm_*, sessionid, phpsessid)
- resolve relative → absolute; collapse /./ and /../
- decode unnecessary %-encoding
http://Example.com:80/a/../b?b=2&a=1&utm_source=x#frag
  → https://example.com/b?a=1&b=2
```

**Membership test with a Bloom filter:**
```
~50 B URLs, ~10 bits/element, k≈7 hashes → ~62 GB, ~1% false-positive rate.
add(url):     set k bits of hash(url)
seen?(url):   all k bits set → "probably seen"; any bit clear → "definitely new"
```
- A **false positive** means we wrongly skip a genuinely-new URL — tolerable (we lose one page among billions). A **false negative is impossible**, so we never double-crawl due to the filter itself.
- **Sharded** by `hash(url)` across nodes so it fits in RAM and scales; an authoritative KV (`url_hash → metadata`) backs it for cases needing exactness (e.g. re-crawl scheduling).
- **Cuckoo filter** alternative when we need **deletion** (expire dead/stale URLs from the set) — Bloom can't delete.

**Content-level dedup** (different URLs, same/near content): the parser computes `content_hash` (exact) and **SimHash/MinHash** (near-dup). Content-addressed storage collapses exact dups for free; SimHash + LSH buckets catch near-dups (mirror sites, printable versions) so we don't index 100 copies of the same article.

### 6.4 Crawl traps & malicious/infinite spaces

The open web actively tries to trap crawlers, accidentally or maliciously:
- **Infinite calendars** (`/cal?date=...&next=...` forever), **faceted search** (every filter combo is a URL), **session-id explosions** (a new URL per visit).
- **Spider traps**: pages that link to themselves with ever-growing paths (`/a/a/a/...`), or generate infinite unique links.
- **Soft 404s** (return 200 for missing pages), **redirect loops**, **decompression bombs** (gzip that expands to GBs).

Defenses:
- **Max depth & max URLs per host**: hard caps so no single site can consume the crawl.
- **URL pattern detection**: flag hosts emitting huge numbers of URLs differing only by a numeric/date parameter; demote or sample them.
- **Path-repetition heuristic**: detect `/a/a/a/` style self-similar paths and cut them.
- **Content/size limits**: cap download size, decompressed size, and redirect chain length (e.g. ≤ 5 hops); abort decompression bombs.
- **Budget per host**: each host gets a crawl budget proportional to its authority — a low-value host can't burn the frontier.
- **Quarantine + manual review** for hosts that trip multiple heuristics.

**Trade-off:** aggressive trap defense risks missing legitimate deep content; conservative defense risks getting stuck. We tune caps by host authority and monitor "URLs discovered per page crawled" — a spiking ratio is the canonical trap signature.

### 6.5 Freshness & re-crawl scheduling

Crawling once is easy; keeping 10 B pages *fresh* economically is the real problem. We can't re-crawl everything constantly.

- **Estimate change rate per page**: each crawl compares the new `content_hash` to the stored one. Changed → shorten the re-crawl interval; unchanged repeatedly → lengthen it. This is an adaptive estimator (often modeled with a **Poisson change process**; classic result: optimal re-crawl frequency is *non-linear* in change rate — you don't gain by crawling a never-changing page more, nor by crawling a chaotic page you can never keep fresh).
- **Per-content-class policy**: news/homepages → minutes-to-hours; product pages → days; static reference pages → weeks. Sitemaps' `<lastmod>` and `changefreq` are hints (trust-but-verify).
- **Priority = authority × staleness × change_rate**: a high-PageRank page that changes often and is overdue gets crawled first.
- **Scheduler**: a job scans the metadata store for `next_crawl_at <= now`, re-enqueues those URLs into the frontier at the computed priority. A **fast-lane** (separate small frontier) handles real-time needs (news, sitemap pings, RSS) without waiting behind the bulk crawl.

```
on crawl complete:
  if content_hash unchanged:  interval = min(interval × 2, max_interval)
  else:                       interval = max(interval / 2, min_interval)
  next_crawl_at = now + interval × jitter
```

**Trade-off:** more freshness = more fetches = more bandwidth/politeness pressure. The change-rate estimator is what lets us spend the limited fetch budget where it actually buys freshness.

---

## 7. Scaling, Bottlenecks & Failure Handling

**What breaks first?** In rough order as you scale: (1) **DNS + per-host politeness** cap per-host throughput (you literally cannot fetch one host faster); (2) **bandwidth** on the fetcher fleet; (3) **seen-set memory** as discovered URLs balloon into the tens of billions; (4) **frontier hot shards** when a few mega-hosts dominate; (5) **parser CPU**, especially if JS rendering is on.

**Scaling levers:**
- **Throughput** comes from **host breadth**: shard the frontier by `hash(host)`, add fetcher nodes, and crawl tens of thousands of hosts concurrently — never by hitting one host harder.
- **Seen-set**: shard the Bloom filter by `hash(url)`; size bits/element for the target FP rate; use Cuckoo filters to expire dead URLs and bound growth.
- **Fetcher fleet**: stateless async I/O nodes behind the frontier; autoscale on queue depth + bandwidth. Geo-distribute fetchers near target hosts to cut latency and spread egress.
- **Parser fleet**: scale independently of fetchers (different resource profile); buffer between them with Kafka so a parser slowdown doesn't stall fetching.
- **Content store**: object storage scales effectively infinitely; content-addressing dedups bytes.

**Replication & partitioning:** frontier firehose on Kafka (replicated, retained); metadata DB Cassandra RF=3 multi-DC; content store with 3× durability; Bloom shards replicated for read availability (they're reconstructable from the metadata DB if lost).

**Failure handling:**
- **Restartability**: every stage commits offsets to Kafka; a crashed fetcher/parser resumes from its last offset. At-least-once + idempotent writes (content-addressed storage, upsert-by-`url_hash`) make replays safe — re-processing a page is harmless.
- **Frontier durability**: the backlog is persisted; losing an in-memory per-host queue just means rebuilding it from the durable log, not losing URLs.
- **Poison pages**: a page that crashes the parser (malformed/huge) is caught, dead-lettered, and skipped after N retries — it must not stall the queue.
- **Host-level circuit breakers**: repeated 5xx/timeouts on a host trip a breaker that backs that host off (protecting both us and the host), rather than burning retries.
- **Backpressure**: if downstream (indexer) can't keep up, Kafka buffers and the fetchers slow — no data loss, just graceful slowdown.
- **DR**: multi-region fetcher fleets; the frontier + metadata + content replicate cross-region; failover promotes a standby region. RPO is minutes (we can re-fetch), RTO minimized by warm standbys. Losing the seen-set worst case re-crawls some pages — wasteful but not corrupting.

**Stragglers / tail latency**: per-fetch timeouts and connection caps so one slow host doesn't tie up a worker; hedged DNS lookups; bounded retry with backoff + jitter to avoid synchronized retry storms.

---

## 8. Trade-offs & Alternatives

| Decision | We chose | Alternative | Why / when to switch |
|---|---|---|---|
| Frontier design | **Two-level: priority front + per-host back queues** | Single global priority queue | Single queue violates politeness; two-level satisfies priority *and* per-host rate limits. |
| Dedup structure | **Sharded Bloom/Cuckoo filter** | Exact KV set of all URLs | Exact set is multi-TB + random reads; filter fits in RAM, FPs are tolerable (skip a rare page). Use Cuckoo when deletion matters. |
| Consistency | **Eventual everywhere (AP)** | Strong consistency | A late or twice-seen URL is harmless; strong consistency would throttle throughput for zero benefit. |
| Content storage | **Object store, content-addressed** | RDBMS blobs | Cheapest PB-scale durable bytes + free exact-dup collapse via `content_hash`. |
| Rendering | **Raw HTTP fetch (HTML)** | Headless-browser render every page | JS rendering is 10–50× the cost; only render the subset that *needs* it (detect SPA/empty-body), in a separate small fleet. |
| Politeness model | **Breadth (many hosts, slow each)** | Depth (drain a host fast) | The web is rate-limited per host; throughput must come from concurrency across hosts. |
| Re-crawl | **Adaptive change-rate scheduling** | Fixed-interval re-crawl | Fixed interval wastes fetches on static pages and starves volatile ones; adaptive spends budget where it buys freshness. |

**What changes at 10×?** DNS and politeness dominate engineering effort; you geo-distribute fetcher fleets, run dedicated caching resolvers, and shard the frontier and seen-set far more finely. A real-time **freshness fast-lane** becomes mandatory, separate from the bulk crawl.

**What changes at 100×?** This becomes a globally-distributed, multi-region crawl with per-region fetcher fleets crawling local hosts; **tiered re-crawl** (hot/warm/cold by authority + change rate); heavy investment in the **link graph / PageRank** pipeline to prioritize the limited fetch budget; and ML-driven trap/spam detection. Politeness, dedup, and prioritization — not raw fetching — are where the hard problems live.

---

## Interview Q&A by Level

### 🟢 Basic

**Q. [Theory] What is a URL frontier and why isn't it just a FIFO queue?**
A: The frontier is the set of URLs waiting to be crawled, plus the logic that decides *what to fetch next*. It can't be a plain FIFO because it must satisfy two competing constraints: **priority** (crawl important/fresh pages first) and **politeness** (never exceed a single host's rate limit). The standard solution is a two-level structure — priority "front" queues feeding per-host "back" queues, with a time-ordered heap that releases a host's next URL only after its crawl-delay has elapsed.

**Q. [Theory] Why must a crawler respect robots.txt and per-host rate limits?**
A: Politeness is what separates a crawler from a DDoS attack. Ignoring `robots.txt` or hammering a host gets your IP/User-Agent banned, can legally expose you, and degrades the very sites you depend on. We fetch and cache `robots.txt` per host, obey `Crawl-delay`/`Retry-After`, default to ≥1 request/second/host, identify ourselves via `User-Agent`, and back off automatically when a host shows stress (rising latency or 5xx).

**Q. [Practical] Roughly how do you size the fetcher fleet for 5,000 pages/sec, and what's the binding constraint?**
A: Fetching is I/O-bound — a single async node holding ~10K concurrent connections does ~1–2K fetches/sec, so ~3–5 fetcher nodes cover 5K/s (more for peak/retries). But the binding constraint usually isn't node count; it's **bandwidth** (5K × ~100KB ≈ 4 Gbps) and **per-host politeness** (you can't speed up one host). So throughput comes from crawling many hosts in parallel, not from adding raw compute.

### 🟡 Intermediate

**Q. [Theory] How do you deduplicate URLs across tens of billions of links without running out of memory?**
A: First **normalize** (lowercase scheme/host, strip fragments and tracking/session params, sort query params, resolve relative paths) so equivalent URLs collapse. Then test membership with a **sharded Bloom filter** — ~10 bits/element gives ~1% false positives at ~62 GB for 50 B URLs, versus multiple TB for an exact set. A false positive merely skips one genuinely-new URL (harmless); false negatives are impossible, so we never double-crawl due to the filter. Use a **Cuckoo filter** instead when we need to *delete* (expire dead URLs).

**Q. [Practical] How does the system avoid re-fetching content that hasn't changed?**
A: Two mechanisms. (1) **Conditional requests**: store each page's `ETag`/`Last-Modified` and send `If-None-Match`/`If-Modified-Since` on re-crawl; an unchanged page returns a tiny `304 Not Modified` — no body transferred. (2) **Content hashing**: compare the new `content_hash` to the stored one; if identical, we skip re-parsing/re-indexing and *lengthen* that page's re-crawl interval. Content-addressed storage also collapses identical bodies from different URLs automatically.

**Q. [Theory] What is a crawl trap and how do you detect one?**
A: A trap is an effectively-infinite URL space — calendars with endless `next` links, faceted search emitting a URL per filter combination, session-IDs creating a new URL every visit, or self-similar paths like `/a/a/a/...`. The canonical signature is a **spiking ratio of URLs-discovered-to-pages-crawled** for a host. Defenses: per-host max depth and URL budgets, detection of URLs differing only by a numeric/date param, path-repetition heuristics, redirect-chain and download-size caps, and quarantining hosts that trip multiple heuristics.

**Q. [Practical] How do you keep a popular host from being overwhelmed when it has thousands of high-priority URLs?**
A: Per-host back queues plus a politeness heap. No matter how many high-priority URLs a host has, a fetcher can only pull from that host once its `next_ok_at` (set to `now + crawl_delay`) has elapsed. Priority is honored *across* hosts, but per-host throughput is hard-capped. We also rate-limit by registrable domain + server IP, since many hosts share one physical server behind a CDN.

### 🟠 Advanced

**Q. [Theory] How do you decide how often to re-crawl each page, given a fixed fetch budget?**
A: Estimate each page's **change rate** by comparing content hashes across crawls and adapt the interval — double it when unchanged, halve it when changed (bounded by min/max). The optimal re-crawl frequency is *non-linear* in change rate (modeled as a Poisson change process): you gain nothing crawling a never-changing page more, and you can't keep a chaotically-changing page fresh, so the budget is best spent on pages where extra crawls actually reduce staleness. Combine with authority and current staleness to set frontier priority, and run a separate fast-lane for news/RSS that needs minute-level freshness.

**Q. [Coding] Sketch the politeness-aware "select next host to fetch" logic for the frontier.**
A: Maintain per-host FIFO queues and a min-heap keyed by each host's earliest allowed fetch time:
```python
import heapq, time

# heap entries: (next_ok_at, host)
ready_heap = []                      # min-heap by time
host_queues = {}                     # host -> deque[url]
host_delay  = {}                     # host -> crawl_delay seconds

def add_url(host, url, delay):
    if host not in host_queues:
        host_queues[host] = deque()
        host_delay[host] = delay
        heapq.heappush(ready_heap, (time.time(), host))  # eligible now
    host_queues[host].append(url)

def next_fetch():
    while ready_heap:
        next_ok_at, host = ready_heap[0]
        if next_ok_at > time.time():
            return None              # nothing eligible yet; caller sleeps/polls
        heapq.heappop(ready_heap)
        q = host_queues[host]
        if not q:
            continue                 # host drained; drop it from rotation
        url = q.popleft()
        # re-arm this host after its crawl delay
        heapq.heappush(ready_heap, (time.time() + host_delay[host], host))
        return host, url
    return None
```
The heap guarantees we only ever fetch from a host whose delay has elapsed; priority is layered in by choosing *which* URL enters each host queue (front-queue selection), keeping politeness and priority orthogonal. In production this is sharded by `hash(host)` and the heap/queues are reconstructable from a durable log.

**Q. [Theory] What's the consistency model, and why is eventual consistency fine here?**
A: Eventual consistency throughout — it's an **AP** system. A URL crawled a few minutes late, a metadata row that lags, or even the same URL processed twice under a race causes no correctness problem (writes are idempotent: content-addressed storage and upsert-by-`url_hash`). Trading throughput/availability for strong consistency would buy nothing, since there's no user reading a "current" value that must be exact. The one near-exact need — not losing the frontier backlog — is handled by durable logs, not by synchronous consistency.

**Q. [Practical] DNS becomes your bottleneck at 5K fetches/sec. What do you do?**
A: Uncached DNS at that rate hammers resolvers and dominates latency. Mitigations: run a **local caching resolver fleet** close to the fetchers; **cache DNS results TTL-aware** in-process and shared; **pre-resolve** hosts when their URLs enter the frontier so the IP is ready by fetch time; spread lookups across multiple upstream resolvers; and reuse connections (HTTP keep-alive + connection pooling) so we resolve once and fetch many. Because we shard the frontier by host, a host's DNS result is naturally reused across all its queued URLs on one shard.

### 🔴 Expert

**Q. [Theory] How do you detect and handle near-duplicate pages (mirrors, printable versions) so downstream isn't flooded with clones?**
A: During parsing, compute a **SimHash** (or MinHash for set-similarity) fingerprint per document; near-duplicates land within a small Hamming distance. Use **LSH bucketing** so duplicate detection is sublinear instead of all-pairs across billions of docs. On a near-dup hit, **canonicalize** to one representative (highest authority / canonical URL / earliest crawl) and emit the others as aliases or drop them. This keeps the corpus smaller, prevents duplicate results downstream, and concentrates ranking signals (links, clicks) on the canonical version. Exact duplicates are collapsed even more cheaply by content-addressed storage on `content_hash`.

**Q. [Practical] You need to crawl JavaScript-rendered SPAs that return an empty HTML body. How do you handle them without 50×-ing your whole fleet?**
A: Don't render everything — **detect and route**. The cheap HTTP fetch runs on all pages; a heuristic (empty/near-empty body, known SPA frameworks, `<noscript>` content, telltale bundlers) flags pages that need rendering. Those go to a **separate, much smaller headless-browser fleet** (Chromium/Puppeteer) with strict resource caps (CPU, memory, render timeout, blocked ad/tracker domains) and a render budget per host. Cache rendered DOM by `content_hash`. This keeps the expensive path bounded to the minority of pages that actually need JS, instead of paying 50× across the whole 10 B corpus.

**Q. [Coding] How would you make the seen-set scale across many machines while staying cheap to query?**
A: Shard the Bloom filter by a hash of the *normalized* URL, so each node owns a disjoint slice and a membership check routes to exactly one shard:
```python
def shard_for(url_norm, num_shards):
    return mmh3.hash(url_norm, signed=False) % num_shards

def seen_or_add(url):
    u = normalize(url)                      # canonicalize first!
    shard = shards[shard_for(u, len(shards))]
    if shard.bloom.contains(u):             # "probably seen" → skip (tolerate rare FP)
        return True
    shard.bloom.add(u)                      # mark seen, then enqueue as new
    return False
```
Each shard sizes its filter for its share of the 50 B keyspace (~10 bits/element). The filters are reconstructable from the authoritative metadata DB, so losing a shard's RAM costs at most some re-crawling, not correctness. Swap Bloom for **Cuckoo** if we must expire dead URLs (deletion support). Normalization *before* hashing is essential — otherwise `Example.com/?utm=x` and `example.com/` hash to different shards and both get crawled.

**Q. [Behavioral] You're running the crawler and a major site emails complaining your bot is overloading their servers and they're threatening to block your whole IP range. Walk me through how you respond.**
A: First, **stop the harm immediately** — push an emergency per-host policy override to throttle or pause that host (the control plane supports this without a redeploy), and confirm via metrics that requests to them dropped. Then **acknowledge and communicate**: reply to the admin quickly, explain who we are (this is exactly why our `User-Agent` carries a contact URL), and apologize. Next, **diagnose**: was it a bug (ignored `Crawl-delay`, a trap inflating their URL count, shared-IP politeness counting hostnames instead of the server, a retry storm)? Pull the host's crawl logs to find root cause. **Fix and verify**: correct the policy, add the host to a stricter allow-list if needed, and add a regression guard/alert so we catch per-host request spikes automatically in future. Finally, **follow up** with the admin to confirm it's resolved and offer an opt-out or a partnership (sitemap-driven, scheduled) crawl. The throughline: protect the relationship and the open web first, fix the systemic cause second, and make it impossible to silently recur.

---

*Related: [Designing a search engine / autocomplete](search-engine.md) · [Message queues & event streaming](message-queue.md) · [Distributed caching](distributed-cache.md) · [Rate limiter](rate-limiter.md)*
