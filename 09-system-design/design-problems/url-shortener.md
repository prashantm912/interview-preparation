# Design a URL Shortener (TinyURL / bit.ly)

> A worked, interview-grade design of a URL shortening service: turn long URLs into short, shareable codes, redirect at scale, and capture analytics — all while staying read-heavy, low-latency, and highly available.

[← Back to master index](../../README.md) · [← System Design index](../README.md)

---

## 1. Requirements

A URL shortener looks deceptively simple ("it's just a hash map"), but the interviewer is probing how you reason about scale, uniqueness, caching, and consistency. Lead by clarifying scope before drawing anything.

### Functional requirements
- **Shorten**: given a long URL, return a short URL on our domain (e.g. `https://sho.rt/aB3kZ9`).
- **Redirect**: given a short code, redirect the browser to the original long URL.
- **Custom alias**: allow users to request a vanity code (e.g. `sho.rt/black-friday`).
- **Expiry / TTL**: links may expire at a user-specified time (or default never).
- **Analytics**: track clicks per link — count, geography, referrer, device, time-series.
- **Deletion / management**: an authenticated owner can delete or disable a link.

### Non-functional requirements
- **Scale**: 100M new URLs/day, read:write ratio roughly **100:1** (redirects dominate).
- **Latency**: redirect p99 **< 50 ms** server-side (this is the user-facing hot path).
- **Availability**: **99.99%** — a dead redirect breaks every link ever shared. Reads must stay up even during partial outages.
- **Durability**: a generated short link must *never* be lost or silently re-pointed.
- **Consistency**: short→long mapping is effectively **immutable** once created, so eventual consistency on the read path is acceptable; analytics can be eventually consistent too.
- **Security**: prevent open-redirect abuse, malware/phishing links, and code enumeration scraping.

### Clarifying questions a strong candidate asks
1. What is the expected write volume and the read:write ratio? (Drives capacity + cache sizing.)
2. How long should links live — forever, or with TTL? (Drives storage growth + cleanup.)
3. Do we need custom aliases and user accounts, or anonymous shortening only?
4. What length / character set for codes? (Drives the keyspace math.)
5. How rich must analytics be — a click counter, or full event stream with dimensions?
6. Single region or global? Global changes the latency story dramatically (CDN/edge).
7. Are we okay returning the *same* short code for a repeated long URL, or always a new one?

> The last question matters: dedup (same long URL → same code) saves storage but requires a reverse lookup/index on every write and breaks per-link analytics ownership. Most production shorteners (bit.ly) **do not dedup** — each shorten is a distinct link with its own stats.

---

## 2. Capacity Estimation

Real numbers, shown long-hand. Assume a 5-year horizon.

### Write QPS
```
100,000,000 writes/day ÷ 86,400 s/day ≈ 1,157 writes/sec  (call it ~1.2K WPS avg)
Peak factor ~3x  →  ~3,500 WPS peak
```

### Read QPS (redirects)
```
Read:write = 100:1  →  100M × 100 = 10,000,000,000 reads/day
10,000,000,000 ÷ 86,400 ≈ 115,740 reads/sec  (~116K RPS avg)
Peak ~3x  →  ~350K RPS peak
```
Redirects are the dominant load by two orders of magnitude — the entire design optimizes the read path.

### Total objects over 5 years
```
100M/day × 365 × 5 = 182,500,000,000  ≈ 1.8 × 10^11  (~182 billion URLs)
```

### Keyspace — how long must a code be?
Base62 = `[a-z A-Z 0-9]` = 62 symbols.
```
62^6 = 56,800,235,584          ≈ 5.7 × 10^10   (too small — < 182B)
62^7 = 3,521,614,606,208       ≈ 3.5 × 10^12   (plenty — 19x headroom over 182B)
```
**Choose 7-character codes.** 7 base62 chars gives ~3.5 trillion combinations, comfortably above the 182B projected and leaving room for sparse/random allocation without high collision risk.

### Storage
Per-record estimate:
```
short_code      7 bytes
long_url        ~500 bytes (worst case; avg ~100, use 500 for safety)
created_at      8 bytes
expiry_at       8 bytes
owner_id        8 bytes
metadata        ~20 bytes
-------------------------------
~ 550 bytes/record  → round to ~500 B/record
```
```
182.5 × 10^9 records × 500 B ≈ 9.1 × 10^13 bytes ≈ 91 TB raw
With replication (3x) + indexes (~1.5x): 91 × 3 × 1.5 ≈ 410 TB
```
Mapping data is large but not enormous — a sharded cluster of commodity nodes handles it. Analytics (click events) dwarfs this and is treated separately.

### Bandwidth
```
Write: 3,500 WPS × 550 B ≈ 1.9 MB/s  (trivial)
Read:  350K RPS × ~60 B response (an HTTP 301/302 header) ≈ 21 MB/s  (also modest)
```
Bandwidth is not the constraint — **request rate and latency** are. The 350K RPS read path is what we engineer around.

### Cache memory (hot set)
Following the 80/20 rule, assume **20% of links generate 80% of reads** on any given day. Cache the daily hot set:
```
Daily reads ≈ 10B. Distinct hot links (say top 20% of a day's active links) ≈
   suppose 100M distinct links touched/day, 20% hot = 20M entries
20M × (7 B code + 500 B url + overhead ~100 B) ≈ 20M × 600 B ≈ 12 GB
```
A **~15–20 GB Redis cache (sharded/replicated)** holds the hot working set — easily fits in RAM across a small cluster, serving the vast majority of redirects without touching the database.

---

## 3. API Design

REST over HTTPS. Authentication via API key / OAuth bearer token for write/management; redirects are public.

```http
# Create a short URL
POST /api/v1/urls
Authorization: Bearer <token>
Content-Type: application/json
{
  "long_url":    "https://example.com/very/long/path?with=query",
  "custom_alias":"black-friday",        // optional
  "expiry_at":   "2026-12-31T23:59:59Z" // optional, null = never
}
→ 201 Created
{
  "short_url":  "https://sho.rt/aB3kZ9",
  "short_code": "aB3kZ9",
  "long_url":   "https://example.com/very/long/path?with=query",
  "expiry_at":  null,
  "created_at": "2026-06-16T10:00:00Z"
}
→ 409 Conflict        // custom_alias already taken
→ 400 Bad Request     // malformed/unsafe URL
→ 429 Too Many Requests

# Redirect (the hot path) — public, no auth
GET /{short_code}
→ 301 Moved Permanently      // or 302, see Deep Dive 2
   Location: https://example.com/very/long/path?with=query
→ 404 Not Found              // unknown code
→ 410 Gone                   // expired or disabled

# Fetch metadata (no redirect)
GET /api/v1/urls/{short_code}
→ 200 { short_code, long_url, created_at, expiry_at, click_count }

# Analytics
GET /api/v1/urls/{short_code}/analytics?from=2026-01-01&to=2026-06-01&granularity=day
→ 200 { total_clicks, by_day:[...], by_country:[...], by_referrer:[...], by_device:[...] }

# Delete / disable
DELETE /api/v1/urls/{short_code}
Authorization: Bearer <token>
→ 204 No Content
```
Design notes: the redirect lives at the **root path** `/{code}` (not under `/api`) to keep short URLs short. Creation is **idempotent on custom_alias** but generates a fresh code otherwise. Rate limiting returns `429` with a `Retry-After` header.

---

## 4. Data Model

### Primary mapping store
The access pattern is a **point lookup by primary key** (`short_code → long_url`) at massive read scale, with no joins and an append-mostly write pattern. That profile screams **wide-column / key-value NoSQL** (Cassandra or DynamoDB) over a single relational box.

**URL mapping table** (Cassandra/DynamoDB):
```
Table: urls
  short_code   STRING   PARTITION KEY   -- e.g. "aB3kZ9"
  long_url     STRING
  owner_id     STRING
  created_at   TIMESTAMP
  expiry_at    TIMESTAMP (nullable)     -- DynamoDB TTL attribute / Cassandra TTL
  is_disabled  BOOLEAN
```
- **Partition key = short_code** → reads and writes hash-distribute evenly; no hot partition by design (codes are pseudo-random/sequential-shuffled).
- **TTL**: DynamoDB TTL or Cassandra per-row TTL auto-expires rows — no manual sweep job needed.
- Lookups are single-partition, single-key → predictable single-digit-ms reads, trivially shardable.

**Custom alias table** (needs uniqueness enforcement):
```
Table: aliases
  alias        STRING   PARTITION KEY
  short_code   STRING                  -- points into urls, or stores long_url directly
```
Use a **conditional write** (`PutItem ... attribute_not_exists(alias)` / Cassandra `INSERT ... IF NOT EXISTS` (lightweight transaction)) to guarantee alias uniqueness atomically.

### Why NoSQL over SQL here
| Dimension | SQL (Postgres/MySQL) | NoSQL (Cassandra/DynamoDB) |
|---|---|---|
| Access pattern | Point lookup by PK — fine | Native sweet spot |
| Scale to 182B rows | Needs manual sharding, painful | Linear horizontal scale built-in |
| 350K RPS reads | Read replicas + heavy caching | Designed for it |
| Multi-region writes | Hard | DynamoDB Global Tables / Cassandra multi-DC native |
| Transactions / joins | Strong — but we don't need them | Limited — but we don't need them |

A single SQL instance can absolutely host a *smaller* shortener; the moment we commit to billions of rows + global multi-region + 350K RPS, the operational cost of sharding SQL exceeds picking a natively-distributed store. **I'd choose DynamoDB on AWS** (managed, autoscaling, Global Tables, built-in TTL) or **Cassandra** if self-hosted/multi-cloud.

### Analytics store (separate)
Click events are high-volume, append-only, and queried by aggregation — a poor fit for the mapping store. Stream them into a **columnar/OLAP store** (ClickHouse, BigQuery, or Druid) fed by **Kafka**. Keep a fast approximate `click_count` (Redis counter) for the UI, and the durable detailed events in the OLAP system.

---

## 5. High-Level Architecture

```
                                   ┌──────────────────────────┐
                                   │   Clients / Browsers      │
                                   └────────────┬─────────────┘
                                                │  HTTPS
                              ┌─────────────────▼──────────────────┐
                              │     CDN / Anycast Edge (CloudFront) │  ← caches 301 redirects at edge
                              └─────────────────┬──────────────────┘
                                                │ cache miss
                              ┌─────────────────▼──────────────────┐
                              │   Global Load Balancer (L7, GeoDNS) │
                              └───────┬─────────────────────┬───────┘
                                      │                     │
                        WRITE path    │                     │   READ path (hot)
                  ┌───────────────────▼──┐         ┌────────▼─────────────────┐
                  │  Write API service   │         │  Redirect service        │
                  │  (shorten/create)    │         │  (GET /{code})           │
                  └──┬─────────────┬─────┘         └───┬───────────────┬──────┘
                     │             │                    │ 1. cache get │
       ┌─────────────▼──┐   ┌──────▼───────┐     ┌──────▼──────┐       │
       │ Key Generation │   │ Alias check  │     │   Redis     │  miss │
       │ Service (KGS)  │   │ (cond write) │     │  (hot set)  │───────┤
       └─────────────┬──┘   └──────┬───────┘     └─────────────┘       │
                     │             │                                    │ 2. db read
                     └──────┬──────┘                            ┌───────▼────────┐
                            │                                   │  URL Mapping DB │
                            ▼                                   │  (DynamoDB /    │
                  ┌──────────────────┐                          │   Cassandra)    │
                  │  URL Mapping DB   │◄─────────────────────────┤   sharded by    │
                  │  (sharded)        │   write-through          │   short_code    │
                  └──────────────────┘                          └────────┬────────┘
                                                                          │ click event
                                                                 ┌────────▼────────┐
                                                                 │      Kafka       │
                                                                 └────────┬────────┘
                                                                          ▼
                                                         ┌────────────────────────────┐
                                                         │ Analytics consumers →        │
                                                         │ ClickHouse/BigQuery + Redis  │
                                                         │ counter                      │
                                                         └────────────────────────────┘
```

### Component walkthrough
- **CDN / Edge**: terminates TLS near the user and can cache `301` responses, so popular links never even reach the origin. Cuts global latency to single-digit ms.
- **Load balancer (L7 + GeoDNS)**: routes by geography to the nearest region; splits read vs write fleets so a write spike can't starve redirects.
- **Redirect service** (stateless, horizontally scaled): the hot path. Reads Redis → on miss reads DB → caches result → returns `301/302` and asynchronously emits a click event. Must do near-zero work.
- **Write/Shorten service**: validates and safety-checks the URL, obtains a unique code from the **KGS**, persists to the mapping DB (write-through to cache optional), enforces alias uniqueness via conditional writes.
- **Key Generation Service (KGS)**: hands out guaranteed-unique short codes (see Deep Dive 1).
- **Redis cache**: holds the hot mapping set (~15–20 GB), the analytics counters, and rate-limit token buckets.
- **Kafka + Analytics**: decouples click logging from the redirect latency path so analytics never slows a redirect.

---

## 6. Deep Dives

### 6.1 Unique short-code generation — counter vs hash vs KGS
This is the heart of the problem. Three approaches:

**(a) Hash the long URL (MD5/SHA, take first 7 base62 chars).**
- Pros: stateless, deterministic, natural dedup (same URL → same code).
- Cons: **collisions** — truncating a 128-bit hash to ~42 bits guarantees birthday-paradox collisions at scale. Must check the DB on every write and re-hash with a salt on collision (extra reads). Random codes are also enumerable/leak nothing but make analytics-by-owner messy if you *do* dedup.

**(b) Auto-increment counter → base62 encode.**
- A monotonically increasing 64-bit counter, base62-encoded, is collision-free by construction (`id=1` → "1", `id=1,000,000` → "4c92"). 
- Cons: a **single counter is a bottleneck/SPOF** and leaks volume (sequential codes reveal growth + enable scraping). Solve the SPOF with a distributed counter or ranges.

**(c) Key Generation Service (KGS) — the production answer.**
Pre-generate keys offline and hand them out:
```
1. A batch job pre-computes random-but-unique 7-char codes into a `keys` table,
   each marked `unused`.
2. KGS servers each check out a RANGE/block of keys (e.g. 10,000 at a time)
   into local memory and mark that block `used` in the DB (one write per block).
3. On each shorten request, the KGS pops a key from its in-memory block — O(1),
   no per-request DB hit, no collision check ever needed.
4. When a block runs low, fetch the next block.
```
- **Pros**: collision-free *by construction* (uniqueness pushed to generation time, not request time); no read-before-write; codes look random (no enumeration leak); each request is a single in-memory pop.
- **Cons**: extra component to operate; a server crash "wastes" its in-memory block (acceptable — keyspace is 3.5 trillion). Need to handle KGS HA (replicate the keys table, run ≥2 KGS instances each owning disjoint blocks).
- **Distributed-counter alternative**: range-based ID allocation (each app server leases a numeric range, e.g. 1–1M, 1M–2M) or **Snowflake-style IDs** (timestamp + machine + sequence) base62-encoded. Snowflake gives roughly time-sortable, globally-unique IDs without coordination, at the cost of longer codes and exposing rough creation time.

**Decision**: KGS for clean, random, collision-free codes; range-leased counters if we want to avoid a separate service and accept slight volume leakage.

### 6.2 The redirect hot path — caching & hot keys
350K RPS of reads means the DB must be shielded:
- **Cache-aside (lazy) with write-through option**: redirect service checks Redis first; on miss, read DB, populate cache with a TTL. New links can be written through to cache at creation so the first click is also a hit.
- **Mapping is immutable** → cache entries effectively never go stale; we only evict on LRU or expiry. This is what makes caching so effective here.
- **Hot keys** (a viral link doing 100K RPS to one Redis shard): mitigate with (1) **CDN edge caching of the 301** so most hits never reach Redis at all; (2) **local in-process LRU** on each redirect node (L1) in front of Redis (L2); (3) **key replication** of hot entries across multiple Redis shards. A 301 cached at the CDN is the single biggest lever for viral links.
- **Negative caching**: cache `404`s briefly so a flood of requests for a non-existent/scraped code doesn't hammer the DB.

### 6.3 Redirect status code — 301 vs 302
| | 301 Moved Permanently | 302 Found (temporary) |
|---|---|---|
| Browser behavior | Caches aggressively; future hits may skip our server | Re-requests our server each time |
| Latency / load | Lowest — browser & CDN cache it | Higher — every click hits us |
| Analytics | **Lossy** — cached hits never reach us | **Accurate** — every click counted |
| Changing target later | Hard — stale browser cache | Easy |

**Trade-off**: `301` minimizes load and latency but **breaks click analytics** (cached redirects bypass us). `302` guarantees every click is counted at the cost of load. Most analytics-driven shorteners (bit.ly) use **302** precisely so they can count and so they can re-point/disable links. If analytics didn't matter and we wanted max performance, `301` + CDN would win. **Choose 302** given analytics is a hard requirement — and lean on Redis/CDN to absorb the resulting load.

### 6.4 Sharding & data distribution
- **Shard the mapping DB by `short_code`** (the partition key). Codes are pseudo-random → uniform hash distribution → no hot shard, and the read path always knows the exact key, so it routes to exactly one shard. This is far better than sharding by `created_at` (hot recent shard) or `owner_id` (skewed by power users).
- With DynamoDB/Cassandra, sharding is automatic (consistent hashing of the partition key); resharding/rebalancing is managed.
- The **aliases** and **counter/keys** tables shard independently.
- Analytics is partitioned by `(short_code, time-bucket)` in the OLAP store for efficient range scans.

### 6.5 Analytics pipeline without slowing redirects
The redirect must not block on analytics. On each redirect:
```
1. Return the 302 immediately.
2. Fire-and-forget a click event (code, ts, ip→geo, referrer, user-agent→device)
   onto Kafka (async, non-blocking).
3. Stream consumers aggregate into ClickHouse (durable, queryable by dimension)
   and increment an approximate Redis counter for the live UI number.
```
- **Decoupling via Kafka** means an analytics outage never affects redirects; events buffer and replay.
- For *huge* counters, use **probabilistic structures** — HyperLogLog for unique-visitor counts, which trade exactness for tiny memory.

---

## 7. Scaling, Bottlenecks & Failure Handling

**What breaks first?** Almost always the **read path / cache layer**, because it carries 100x the write load. Order of stress: (1) cache capacity & hot keys, (2) redirect-service CPU, (3) DB read throughput, (4) KGS availability, (5) analytics ingestion.

- **Stateless services scale horizontally**: redirect and write services sit behind autoscaling groups; add nodes to add throughput. No session state to coordinate.
- **Cache scaling**: Redis Cluster shards the keyspace; add replicas for read fan-out and failover. CDN edge caching offloads the cache itself for viral links.
- **DB scaling**: DynamoDB autoscaling / Cassandra add-nodes give linear read/write capacity. Reads are single-key, so they scale cleanly with shard count.
- **Replication**: 3x replication in the mapping store (quorum reads/writes in Cassandra; DynamoDB handles internally). **Multi-region** via DynamoDB Global Tables / Cassandra multi-DC for global low-latency reads and regional failover.
- **Rate limiting**: per-API-key **token bucket** in Redis on the write path stops abuse and protects the KGS/DB. Redirects get coarse IP-based limiting to deter scrapers/enumeration.
- **Circuit breakers & graceful degradation**: if analytics/Kafka is down, **shed it** — drop events, keep redirecting (analytics is best-effort). If Redis is down, the redirect service falls back to the DB with a breaker to avoid a thundering herd; if the DB is degraded, serve from the local L1 cache and return cached 404s.
- **KGS failure**: run ≥2 KGS instances owning disjoint key blocks; a crashed instance loses only its in-memory block (negligible vs 3.5T keyspace). The keys table is replicated.
- **DR**: cross-region replicas, regular snapshots/backups of the mapping and keys tables, and the ability to fail GeoDNS over to a healthy region. RPO near-zero for mappings (must never lose a link); analytics RPO can be looser.

**Thundering herd on a cold/expired hot key**: use single-flight / request coalescing (only one DB fetch per key on miss, others wait) plus jittered cache TTLs.

---

## 8. Trade-offs & Alternatives

- **Dedup vs no-dedup**: dedup (same long URL → same code) saves storage but needs a reverse index lookup on every write and breaks per-link ownership/analytics. **Chosen: no dedup** — simpler writes, clean per-link analytics; storage is cheap.
- **KGS vs hashing vs counter**: KGS gives collision-free random codes with no read-before-write; hashing is stateless but collision-prone; counters are simple but leak volume / centralize. **Chosen: KGS** (or range-leased counters as a lighter alternative).
- **301 vs 302**: 301 is faster/cheaper but kills analytics and re-pointing. **Chosen: 302** because analytics + link management are required.
- **SQL vs NoSQL**: SQL is fine at modest scale and gives easy transactions; NoSQL wins at 182B rows + 350K RPS + multi-region. **Chosen: NoSQL** for the target scale.
- **Strong vs eventual consistency**: mappings are immutable, so eventual consistency on reads is safe and faster; custom aliases need a strongly-consistent conditional write to guarantee uniqueness. **CAP**: on the redirect read path we choose **AP** (serve a possibly-slightly-stale-but-immutable mapping over failing); on alias creation we choose **CP** (reject rather than allow a duplicate alias).

**At 10x scale (~1.16M RPS reads)**: push far more aggressively to the CDN edge (cache even 302s with short TTLs where analytics tolerance allows), add Redis shards + L1 local caches everywhere, and pre-warm caches for predicted-viral links.

**At 100x scale**: this becomes a fundamentally **edge-served** system — codes resolved at PoPs via replicated edge KV stores (Cloudflare Workers KV / DynamoDB Global Tables read replicas in every region), with the origin handling only writes and cold misses. Analytics moves to sampled + probabilistic aggregation to keep ingestion sane.

---

## Interview Q&A by Level

### 🟢 Basic
**Q: Why are short codes 7 characters?**
A: Base62 (a–z, A–Z, 0–9) with 7 chars yields 62⁷ ≈ 3.5 trillion combinations. Our 5-year projection is ~182 billion URLs, so 7 chars gives ~19x headroom; 6 chars (≈57 billion) would run out.

**Q: Why is this system read-heavy, and why does that matter?**
A: Each link is created once but clicked many times — a ~100:1 read:write ratio (~350K RPS reads vs ~3.5K WPS at peak). It means the whole design optimizes the redirect path: caching, CDN, and stateless redirect services matter far more than write throughput.

**Q: What HTTP status code does the redirect return?**
A: `302 Found` (temporary) so every click reaches our servers and is counted for analytics, and so we can re-point or disable links. `301` would be cached by browsers/CDNs and is faster but would lose analytics.

### 🟡 Intermediate
**Q: How do you guarantee short codes are unique?**
A: A Key Generation Service pre-generates random unique 7-char codes into a `keys` table; each KGS instance checks out a block (e.g. 10K keys), marks it used in one write, and pops keys from memory per request. Uniqueness is enforced at generation time, so there's no per-request collision check and no read-before-write.

**Q: SQL or NoSQL, and why?**
A: NoSQL (DynamoDB or Cassandra). The access pattern is a point lookup by primary key with no joins, at 182B rows and 350K RPS across multiple regions. NoSQL gives native horizontal scaling, consistent-hash sharding by `short_code`, built-in TTL, and multi-region replication — all of which would require painful manual sharding in SQL.

**Q: How do you handle custom aliases and the chance two users want the same one?**
A: A separate `aliases` table with the alias as the partition key, written via a conditional write (`attribute_not_exists` / `INSERT ... IF NOT EXISTS`). The first writer wins atomically; the second gets a `409 Conflict`. This is the one place we accept strong consistency (CP) over availability.

**Q: How does expiry work without a giant cleanup job?**
A: Store `expiry_at` as the DB's native TTL attribute (DynamoDB TTL / Cassandra row TTL). The store auto-deletes expired rows in the background. The redirect service also checks `expiry_at` at read time and returns `410 Gone` for anything past expiry even before physical deletion.

### 🟠 Advanced
**Q: A single link goes viral — 200K RPS to one code. How do you survive?**
A: Layered defense. (1) The `302` for a hot code is cached at the **CDN edge**, so most hits resolve at PoPs and never reach origin. (2) Each redirect node keeps an **L1 in-process LRU** in front of Redis. (3) The hot entry is **replicated across Redis shards** to avoid a single-shard hotspot. (4) **Request coalescing** ensures only one DB fetch on a cold miss. The CDN is the biggest lever — one cached redirect absorbs the storm.

**Q: How do you keep analytics from slowing redirects?**
A: Fully decouple it. The redirect returns the `302` immediately, then fire-and-forgets a click event onto **Kafka**. Consumers aggregate into ClickHouse/BigQuery for dimensional queries and bump an approximate Redis counter for the live UI. If Kafka/analytics is down, we shed those events and keep redirecting — analytics is best-effort, redirects are not.

**Q: How do you shard, and how do you avoid hot shards?**
A: Shard by `short_code` (the partition key). Because codes are pseudo-random, hashing them distributes load uniformly with no temporal or per-user skew, and every read knows its exact key so it hits exactly one shard. Sharding by `created_at` would hot-spot the newest shard; by `owner_id` would skew toward power users.

**Q: What's your consistency model, in CAP terms?**
A: Mixed. Mappings are immutable once created, so the redirect read path chooses **AP** — serve a possibly-stale-but-correct mapping rather than fail. Alias creation chooses **CP** — a strongly-consistent conditional write that rejects duplicates rather than risk two owners on one alias. Analytics is fully eventually consistent.

### 🔴 Expert
**Q: Redesign for 100x scale (~1M+ RPS reads) and true global low latency.**
A: It becomes an edge-resolved system. Replicate the mapping into **edge KV stores** (Cloudflare Workers KV / DynamoDB Global Tables read replicas) in every region so codes resolve at the PoP without crossing oceans. The origin handles only writes and cold misses. Codes still come from a KGS, but key blocks are leased per-region. Analytics shifts to **sampling + probabilistic aggregation** (HyperLogLog for uniques) to keep ingestion tractable, and counters are merged across regions asynchronously.

**Q: How do you defend against enumeration/scraping and malicious links?**
A: Use random (not sequential) codes via KGS so the keyspace can't be walked. Apply IP-based rate limiting and `404` negative caching to blunt scrapers. On the write path, run submitted URLs through a **safe-browsing/malware check** and block known-bad domains, reject non-http(s) schemes to prevent open-redirect/`javascript:` abuse, and optionally interstitial-warn on flagged links. Per-API-key token-bucket limits cap abusive creation.

**Q: A KGS instance crashes mid-block. What happens, and is it safe?**
A: It loses the unused keys still in its in-memory block. That's acceptable: those keys are simply never used (they were already marked `used` in the DB when the block was checked out, so no other instance reuses them — guaranteeing no collision). With a 3.5-trillion keyspace, leaking a few thousand keys per crash is negligible. We run ≥2 KGS instances on disjoint blocks for HA, and replicate the keys table.

**Q: How would you support editable destinations (re-pointing a short link) safely?**
A: This is exactly why we chose `302` over `301` — browsers don't permanently cache the target, so an update propagates on the next click. Store the mapping as mutable, version it (`long_url`, `version`, `updated_at`), and on update invalidate/overwrite the cache entry (write-through) and purge the CDN edge for that code. Use a short edge TTL so stale targets self-heal quickly. Owners-only, via authenticated `PUT`, with an audit trail.

---

*Key takeaway: a URL shortener is a masterclass in read-heavy design — the interesting engineering is collision-free key generation, shielding the database behind layered caching/CDN, and choosing 302 + eventual consistency so analytics and link management work at billions-of-URLs scale.*
