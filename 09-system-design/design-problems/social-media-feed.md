# Design a Social Media Feed (Twitter / Instagram)

A worked system-design problem covering how a read-heavy social platform creates posts, generates per-user timelines at scale, and survives the celebrity hot-key problem.

[← Back to master index](../../README.md) · [← System Design index](../README.md)

---

## 1. Requirements

### Functional
- **Post creation**: a user publishes a post (text up to 280 chars, plus 0–4 images or a video, optional links).
- **Home timeline / feed**: a user opens the app and sees a reverse-chronological-ish, ranked list of posts from accounts they follow.
- **Follow graph**: users follow/unfollow other users; following is asymmetric (no mutual consent required, like Twitter).
- **Engagement**: like, reply, repost/retweet, bookmark. Engagement counts are visible on each post.
- **Media**: images/videos are uploaded, transcoded, and served from a CDN.
- **Notifications**: a user is notified of new followers, likes, replies, mentions.
- **Search** (secondary): find users and posts (we treat this as out of primary scope but mention it).

### Non-functional
- **Scale**: 500M total users, **200M daily active users (DAU)**. Read-heavy: timeline reads vastly outnumber writes (roughly **100:1**).
- **Latency**: home timeline load p99 **< 200 ms**; post creation acknowledged in **< 500 ms** (fan-out can be async).
- **Availability**: **99.99%** for reads. Timeline reads must stay up even if the write path degrades.
- **Consistency**: **eventual consistency** is acceptable for the feed. A new post appearing 2–5 seconds late in a follower's timeline is fine. Like counts may be approximate. The *author* must see their own post immediately (read-your-writes for the author).
- **Durability**: a published post must never be lost once acknowledged.

### Clarifying questions a candidate should ask
1. Reverse-chronological or ranked feed? (Ranked — drives the need for a scoring service and richer candidate generation.)
2. How big is the largest account? (Hundreds of millions of followers — this single fact dictates the hybrid fan-out design.)
3. What is the acceptable staleness for the feed? (Seconds — lets us go async and eventually consistent.)
4. Read:write ratio? (~100:1 — we optimize the read path aggressively, even at the cost of write amplification.)
5. Edit/delete posts? (Delete yes, edit treated as a new version — affects cache invalidation.)
6. Do we need strict ordering across posts? (No global total order; per-user perceived order is enough.)

---

## 2. Capacity Estimation

Assume **200M DAU**.

### Write QPS (posts)
- Average user posts **2 posts/day** → 200M × 2 = **400M posts/day**.
- 400M / 86,400 s ≈ **~4,600 posts/sec average**.
- Peak ≈ 3× average → **~14,000 posts/sec**.

### Read QPS (timeline fetches)
- Each DAU opens the app ~10 times/day, refreshing the timeline ~5 times each → ~50 timeline reads/day.
- 200M × 50 = **10B reads/day** → 10B / 86,400 ≈ **~115,000 reads/sec average**, peak ~**350,000 reads/sec**.
- This confirms the **~100:1 read:write** ratio and justifies precomputing timelines.

### Storage (posts)
- Per post metadata: id (8B) + author_id (8B) + text (~300B) + timestamps + counters + media refs ≈ **~1 KB**.
- 400M/day × 1 KB = **400 GB/day** → ~**146 TB/year** of post metadata (before replication; ×3 replicas ≈ 440 TB/yr).

### Media storage
- Assume 20% of posts carry media, avg 2 images @ ~1.5 MB (post-compression) = ~3 MB/post-with-media.
- 400M × 0.2 × 3 MB = **240 TB/day** of new media → ~**88 PB/year**. This lives in blob storage (S3/GCS) behind a CDN, NOT in the database.

### Bandwidth (read side)
- Timeline payload (metadata + thumbnails references, not full media): ~10 KB per refresh.
- 115,000 reads/sec × 10 KB ≈ **~1.15 GB/sec** of API egress at average; peak ~3.5 GB/sec.
- Media is served from CDN, not origin — CDN absorbs the bulk of bytes.

### Timeline cache memory (Redis)
- Cache the precomputed timeline (list of post IDs) for active users.
- Store ~800 post IDs per user (≈ a few hundred timeline entries + headroom). Each entry ≈ 8B id + 8B score ≈ 16B → ~12.8 KB/user.
- For 200M DAU: 200M × ~12.8 KB ≈ **~2.5 TB** of timeline cache. Sharded across a Redis cluster (e.g., 50–100 nodes). We only materialize timelines for active users, not all 500M.

These numbers justify: **precompute timelines (fan-out), cache them in Redis, store media in blob+CDN, and shard everything by user/post id.**

---

## 3. API Design

```
# ---------- Post creation ----------
POST /v1/posts
  Headers: Authorization: Bearer <jwt>
  Body:    { "text": "hello world", "media_ids": ["m_123","m_456"], "reply_to": null }
  Resp 201: { "post_id": "p_98f2", "created_at": 1718500000, "author_id": "u_42" }

# ---------- Media upload (two-step, presigned) ----------
POST /v1/media:initiate
  Body:    { "content_type": "image/jpeg", "bytes": 1572864 }
  Resp 200: { "media_id": "m_123", "upload_url": "https://blob.../presigned", "expires_in": 600 }
  # client PUTs bytes directly to upload_url (bypasses our API servers)

# ---------- Home timeline (the hot read path) ----------
GET /v1/timeline/home?limit=30&cursor=<opaque>
  Resp 200: {
    "posts": [ { "post_id":"p_1", "author":{...}, "text":"...", "media":[...],
                 "like_count":1203, "reply_count":44, "reposted_by":[...], "score":0.97 } ],
    "next_cursor": "eyJ0cyI6MTcxODQ5..."   # opaque keyset cursor, NOT offset
  }

# ---------- User (author) timeline ----------
GET /v1/timeline/user/{user_id}?limit=30&cursor=<opaque>

# ---------- Social graph ----------
POST   /v1/follow/{target_user_id}
DELETE /v1/follow/{target_user_id}
GET    /v1/users/{user_id}/followers?cursor=...
GET    /v1/users/{user_id}/following?cursor=...

# ---------- Engagement ----------
POST   /v1/posts/{post_id}/like
DELETE /v1/posts/{post_id}/like
POST   /v1/posts/{post_id}/repost

# ---------- Notifications ----------
GET /v1/notifications?cursor=...
```

**API design notes**
- **Cursor pagination, never offset.** `OFFSET` scans grow linearly with depth and break when items are inserted at the head (which happens constantly on a feed). We use an opaque keyset cursor encoding `(score, post_id)` or `(timestamp, post_id)`.
- **Idempotency**: `POST /posts` accepts an `Idempotency-Key` header so client retries (mobile networks) don't create duplicates.
- **Two-step media upload**: clients PUT bytes straight to blob storage via a presigned URL; our API servers never proxy large media payloads.

---

## 4. Data Model

We deliberately mix storage engines — there is no single right database.

### Posts — wide-column NoSQL (Cassandra / DynamoDB)
Posts are immutable, write-heavy, and read by id or by author. No complex joins.

```
Table: posts
  PK: post_id        # Snowflake id (k-sortable by time, see deep dive)
  author_id
  text
  media_ids[]
  reply_to
  created_at
  like_count, reply_count, repost_count   # denormalized counters
```

```
Table: user_posts        # for the author's own profile timeline
  PK:  user_id
  CK:  post_id DESC      # clustering key, time-ordered via Snowflake id
```

**Why NoSQL for posts:** immutable append-heavy data, accessed by partition key, with predictable single-partition queries. Cassandra/DynamoDB give linear horizontal scale and tunable consistency (`QUORUM` writes/reads). No need for transactions or joins on the hot path.

### Social graph — sharded SQL or a graph store
```
Table: follows
  follower_id, followee_id, created_at
  index on (follower_id)   -> "who do I follow"  (used during fan-out / read aggregation)
  index on (followee_id)   -> "who follows me"   (used during fan-out on write)
```
The graph is queried in both directions, so we keep **two physical indexes** (an adjacency list per direction). Stored in a sharded relational DB (Vitess/Spanner) or DynamoDB with two GSIs. The follower lists of celebrities are themselves huge, so `followee_id -> followers` may be paged in chunks.

### Timeline — Redis (precomputed)
```
Key: timeline:home:{user_id}   -> Sorted Set (ZSET)
  member = post_id, score = ranking_score (or timestamp for chrono)
  trimmed to ~800 newest entries (ZREMRANGEBYRANK)
```
A ZSET gives O(log n) inserts and O(log n + k) range reads — perfect for "top 30 by score with a cursor."

### Counters — Redis + async reconciliation
Like/repost counts are kept in Redis (`INCR`) for speed and periodically flushed/reconciled into the posts store. Exact counts are not safety-critical, so approximate-but-fast wins.

**SQL vs NoSQL summary**: SQL where we need relationships and transactional integrity (the follow graph, billing, user accounts); NoSQL/wide-column where we need raw write throughput and partition-key access (posts, timelines, counters).

---

## 5. High-Level Architecture

```
                                  ┌─────────────┐
   Mobile / Web ───── HTTPS ─────▶│   CDN /     │  (static + media, edge cache)
                                  │  Edge POP   │
                                  └──────┬──────┘
                                         │ API calls (cache miss / dynamic)
                                         ▼
                                  ┌─────────────┐
                                  │ API Gateway │  authN/Z, rate limit, routing
                                  │ + LB        │
                                  └──┬───────┬──┘
                  WRITE PATH         │       │        READ PATH
        ┌──────────────────────────-┘       └────────────────────────────┐
        ▼                                                                 ▼
 ┌─────────────┐    ┌──────────┐                                  ┌──────────────┐
 │ Post Service│───▶│  Posts   │                                  │  Timeline    │
 │ (write)     │    │  Store   │                                  │  Service     │
 └──────┬──────┘    │(Cassandra)│                                 │  (read)      │
        │           └──────────┘                                  └──────┬───────┘
        │ publish "post_created" event                                   │
        ▼                                                       ┌─────────┴─────────┐
 ┌─────────────┐                                                ▼                   ▼
 │   Kafka     │                                         ┌────────────┐      ┌────────────┐
 │ (event bus) │                                         │   Redis    │      │  Hydration │
 └──────┬──────┘                                         │ Timelines  │      │  (posts +  │
        │                                                │  (ZSETs)   │      │  counters) │
        ▼                                                └────────────┘      └────────────┘
 ┌─────────────┐     reads          ┌──────────────┐
 │  Fan-out    │◀───────────────────│ Social Graph │
 │  Workers    │  follower lists    │  (follows)   │
 └──────┬──────┘                    └──────────────┘
        │ push post_id into each follower's Redis ZSET
        └──────────────▶ Redis Timelines
                         │
                         └────────▶ Notification Service ──▶ Push (APNs/FCM)
```

**Component walkthrough**
- **CDN / Edge**: serves all media and static assets; absorbs the bulk of bytes and shields the origin.
- **API Gateway + LB**: TLS termination, JWT auth, per-user rate limiting, request routing. Stateless and horizontally scaled.
- **Post Service (write)**: validates, assigns a Snowflake id, durably writes to the Posts Store (`QUORUM`), then publishes a `post_created` event to **Kafka**. It returns 201 *as soon as the post is durable* — fan-out is asynchronous, keeping write latency low.
- **Kafka**: the durable backbone decoupling writes from fan-out, notifications, search indexing, and analytics. Partitioned by `author_id`. Buffers spikes; lets consumers scale independently.
- **Fan-out Workers**: consume `post_created`, look up the author's followers in the Social Graph, and push the `post_id` into each follower's Redis timeline ZSET. For celebrities, fan-out is **skipped** (see deep dive).
- **Social Graph Service**: stores follow edges; serves "followers of X" (fan-out on write) and "who X follows" (read-time aggregation).
- **Redis Timelines**: per-user sorted sets of post IDs — the precomputed home timeline. The hot read path hits only Redis.
- **Timeline Service (read)**: on a feed request, reads the user's ZSET (push-based posts), **merges** in recent posts from followed celebrities (pull-based), applies ranking, then hydrates IDs into full post objects via the Hydration service.
- **Hydration**: batch-fetches post bodies + live counters and assembles the response.
- **Notification Service**: consumes engagement/follow/mention events and delivers push notifications.

---

## 6. Deep Dives

### 6.1 Fan-out: on-write vs on-read vs hybrid

This is the central decision.

**Fan-out on write (push model).** When a user posts, immediately write the post id into every follower's timeline. Reads become trivial (one Redis ZSET read). This is great for the read path — which is 100× the write path.
- Cost: **write amplification**. A user with 1,000 followers generates 1,000 timeline writes per post.
- Disaster case: a celebrity with **100M followers** posts → 100M Redis writes for a single post. This thunders the cluster, takes minutes, and wastes effort on inactive followers.

**Fan-out on read (pull model).** Store nothing per-follower. At read time, gather the posts of everyone the user follows, merge, and rank.
- Cost: expensive reads. A user following 2,000 accounts triggers a scatter-gather over 2,000 author timelines on every refresh — at 115K reads/sec this is untenable.

**Hybrid (what we choose).**
- **Normal users** (followers below a threshold, e.g. **< 10,000**): **fan-out on write**. Their posts are pushed into followers' Redis timelines.
- **Celebrities / hot accounts** (followers above the threshold): **fan-out on read**. We do NOT push their posts. Instead, at read time the Timeline Service fetches each followed celebrity's recent posts (their own author-timeline, itself cached) and **merges** them with the pushed timeline before ranking.
- A user's final feed = `merge(pushed_ZSET, pull(recent posts of followed celebrities))`, re-ranked, top-K returned.

```
 author posts ─▶ followers < 10k ?
                   │ yes                         │ no (celebrity)
                   ▼                             ▼
             push to all followers'        store only in author's
             Redis timelines               own timeline (no fan-out)
                                                 │
 reader opens feed ─▶ read own ZSET  +  pull recent posts of followed celebs  ─▶ merge + rank
```

This bounds write amplification (no 100M-write storms) and bounds read cost (a user follows only a handful of celebrities, so the pull side is small). Threshold tuning trades write load against read load.

### 6.2 Unique, time-sortable post IDs (Snowflake)

We need globally unique IDs that are also roughly **time-ordered** so the posts store and timeline ZSETs can sort by id without a separate timestamp. UUIDv4 is random (kills locality and range scans); a single DB auto-increment is a SPOF and a bottleneck.

**Snowflake** 64-bit layout:
```
 0 | 41-bit timestamp (ms since epoch) | 10-bit machine id | 12-bit sequence
   └ sign                              └ 1024 nodes         └ 4096 ids/ms/node
```
- ~69 years of timestamps, 4096 ids/ms/node → millions of ids/sec, no coordination, **k-sortable** by creation time. Each generator only needs its own machine id; clock-skew is handled by waiting if the clock moves backward.

### 6.3 The celebrity / hot-key problem (caching)

A celebrity's post and profile timeline are read by millions simultaneously. If that post lives on one Redis/Cassandra shard, that shard melts — a classic **hot key**.

Mitigations, layered:
1. **Don't fan out celebrity posts** (6.1) — avoids the write storm entirely.
2. **Replicate the hot key**: keep the celebrity's recent-posts list on multiple replicas / multiple cache keys (`celeb:{id}:posts:{0..N}`) and have readers pick a random replica → spreads read load.
3. **Local in-process cache** on Timeline Service instances with a short TTL (1–2 s) for ultra-hot posts. At 350K reads/sec, even a 1 s local cache collapses millions of backend hits into one per server per second.
4. **CDN/edge cache** the public profile and the post object itself (it's immutable) so most celebrity reads never reach the origin.
5. **Request coalescing / single-flight**: on a cache miss, only one request fetches from the backend; concurrent requests wait and share the result, preventing a **cache stampede**.

### 6.4 Ranking the feed

A pure reverse-chronological feed is simple but engagement-poor. A ranked feed scores candidate posts:
```
score = w1·affinity(viewer, author)         # interaction history
      + w2·recency_decay(age)               # exponential time decay
      + w3·predicted_engagement(post)       # ML model: P(like|click)
      + w4·media/type boosts
      − penalties (spam, already-seen, muted)
```
- **Candidate generation** = pushed timeline ∪ followed-celebrity posts ∪ (optionally) "out-of-network" recommendations.
- A lightweight scorer ranks a few hundred candidates per request; heavy ML features are precomputed offline and looked up from a feature store. We store the ZSET score so re-reads are cheap, and recompute ranking periodically rather than on every keystroke.
- **Read-your-writes for the author**: the author's own new post is injected at the top of *their* timeline synchronously so they never think the post failed, even before fan-out completes.

### 6.5 Sharding & consistency

- **Posts store**: shard/partition by `post_id` (Snowflake) → even distribution, no hotspots from monotonic keys. Author-timeline table partitioned by `author_id`.
- **Timeline cache**: shard Redis by `user_id` (consistent hashing) so a user's whole timeline is on one shard — a single read fetches it.
- **Consistency model**: writes to the posts store use `QUORUM` (durable, read-your-writes for direct id fetch). Fan-out and follower timelines are **eventually consistent** — a post may land in followers' feeds seconds later. This is the deliberate CAP choice: on a partition we favor **AP** (availability) for the feed; the system keeps serving slightly-stale timelines rather than erroring.

---

## 7. Scaling, Bottlenecks & Failure Handling

**What breaks first, and the fix:**

| Bottleneck | Symptom | Mitigation |
|---|---|---|
| Celebrity fan-out | One post → 100M writes, Redis saturates | Hybrid model: skip fan-out for celebrities, pull at read time |
| Hot key on a single post/shard | One shard at 100% CPU | Key replication, local + CDN cache, request coalescing |
| Redis timeline memory | Cache cost balloons | Materialize only active users; trim ZSETs to ~800; evict inactive (LRU) |
| Fan-out worker lag | Posts appear minutes late | Scale Kafka partitions + consumer group; prioritize active followers; backpressure |
| Posts store write QPS | Write timeouts at peak | Horizontal shard by post_id; tune to `QUORUM`; absorb spikes in Kafka |
| Read fan-in (pull side) | Slow feed for users following many celebs | Cache each celebrity's recent-posts list; cap pull breadth |

**Replication & partitioning**
- Posts store: 3 replicas, `QUORUM` read/write, cross-AZ. Multi-region with async replication and a designated home region per user.
- Redis: primary/replica per shard with automatic failover (Sentinel/Cluster). A cache loss is *recoverable* — timelines can be rebuilt from the posts store + follow graph (cache is an optimization, not the source of truth).

**Failure handling**
- **Circuit breakers** between services: if the ranking/ML scorer is down, fall back to a reverse-chronological feed (graceful degradation) rather than failing the request.
- **Bulkheads / isolation**: celebrity-read traffic uses a separate connection pool so it can't starve normal reads.
- **Dead-letter queue** for fan-out events that repeatedly fail; retried out of band.
- **Backpressure**: Kafka decouples producers from consumers; if fan-out lags, posts are still durable and feeds simply update with delay.
- **DR**: blob/media in multi-region buckets behind a multi-POP CDN. Posts replicated cross-region. RPO near-zero for posts (durable writes + Kafka log), RTO minutes via region failover. Active-active read serving with eventual cross-region convergence.

---

## 8. Trade-offs & Alternatives

- **Push vs pull vs hybrid**: pure push dies on celebrities; pure pull dies on the 100:1 read ratio. **Hybrid** is more complex (merge logic at read time) but is the only design that bounds both write amplification and read cost. We accept the complexity.
- **Eventual consistency**: we trade strict freshness for availability and latency. Acceptable for a feed; unacceptable for, say, a bank ledger. We special-case author read-your-writes.
- **Denormalized counters**: fast and cheap but approximate; we reconcile asynchronously. Exactness isn't worth a synchronous write on every like at 350K reads/sec.
- **NoSQL posts**: gives scale and simple access patterns at the cost of no joins/transactions — fine because posts are immutable and key-accessed.
- **Ranked vs chronological**: ranking lifts engagement but adds an ML/feature-store dependency and a fallback path; the circuit breaker to chrono protects availability.

**At 10× scale (2B DAU-equivalent):** more aggressive multi-region sharding by user home-region; per-region Redis and posts shards; lower the celebrity threshold and add a "warm" tier (partial fan-out to *active* followers only). Move ranking features to a dedicated feature store with online serving.

**At 100× scale:** geo-partition the entire graph; introduce edge compute for timeline assembly close to users; tiered storage (hot posts in SSD/Cassandra, cold posts to cheaper object storage); dedicated hot-account infrastructure (a separate "VIP" pipeline). Consider a custom timeline storage engine (à la Twitter's Manhattan/Haplo) over generic Redis once Redis memory cost dominates.

**What I'd reconsider:** if the product became mostly chronological (no ranking) and accounts stayed small, pure fan-out-on-write with no pull path would be simpler and I'd drop the merge complexity.

---

## Interview Q&A by Level

### 🟢 Basic
**Q: Why is this system read-heavy and why does that matter?**
A: Users read timelines ~50×/day but post ~2×/day — roughly a 100:1 ratio. So we optimize the read path: precompute and cache timelines (fan-out on write) so a feed load is a single fast Redis read rather than an expensive on-the-fly aggregation.

**Q: Why use a CDN?**
A: Media (images/video) is the bulk of the bytes and is immutable. Serving it from edge POPs cuts latency for users worldwide and offloads enormous bandwidth from the origin, which only handles dynamic API responses.

**Q: SQL or NoSQL for posts?**
A: NoSQL/wide-column (Cassandra/DynamoDB). Posts are immutable, append-heavy, accessed by id or author — no joins or transactions needed. NoSQL gives linear horizontal scale and tunable consistency. We keep SQL for the relational follow graph and accounts.

### 🟡 Intermediate
**Q: Walk me through what happens when a normal user posts.**
A: Post Service validates, assigns a Snowflake id, writes durably to the posts store at QUORUM, returns 201, and publishes a `post_created` event to Kafka. Fan-out workers consume it, look up the author's followers, and push the post id into each follower's Redis timeline ZSET. The author's own timeline is updated synchronously for read-your-writes.

**Q: Why cursor pagination instead of offset?**
A: Offset scans grow with depth (slow deep pages) and break when new items are inserted at the head — which is constant on a live feed, causing duplicates/skips. A keyset cursor encoding `(score, post_id)` is stable and O(log n) on the ZSET.

**Q: Why Kafka in the middle?**
A: It decouples the fast write path from slow asynchronous work (fan-out, notifications, search indexing, analytics), durably buffers traffic spikes, and lets each consumer scale independently. If fan-out lags, posts are still durable and feeds just update a bit later.

### 🟠 Advanced
**Q: A celebrity with 100M followers posts. What happens, and how do you avoid melting Redis?**
A: We detect the account is above the celebrity threshold and **skip fan-out** — no 100M writes. The post is stored only in the celebrity's own (cached, replicated) timeline. At read time, each follower's Timeline Service pulls the celebrity's recent posts and merges them into the feed. Combined with hot-key replication, local + CDN caching, and request coalescing, the read load is spread instead of hammering one shard.

**Q: How do you keep timeline memory under control?**
A: Materialize timelines only for active users (200M DAU, not 500M total), trim each ZSET to ~800 newest entries via `ZREMRANGEBYRANK`, and LRU-evict inactive users — their timeline is rebuilt from the posts store + follow graph on next login since the cache is an optimization, not the source of truth.

**Q: What's your consistency model and CAP stance?**
A: Eventual consistency for the feed. Posts are written at QUORUM (durable, read-your-writes by id). Follower timelines converge within seconds via async fan-out. On a network partition we choose **AP** — keep serving slightly stale timelines rather than erroring. The author always sees their own post immediately via synchronous self-timeline injection.

### 🔴 Expert
**Q: How do you prevent a cache stampede when a hot post's cache entry expires?**
A: Request coalescing / single-flight: on a miss, only the first request fetches from the backend while concurrent requests wait and share the result. Combined with slightly randomized TTLs (jitter) to avoid synchronized expiry, short-lived local in-process caches, and serve-stale-while-revalidate so readers get the old value during refresh.

**Q: How would you bound staleness and worker lag in fan-out, and prioritize who gets updated first?**
A: Scale Kafka partitions (keyed by author_id) and the consumer group horizontally; monitor consumer lag as the SLO. Prioritize fan-out to *active/online* followers first (warm tier), defer inactive ones (they pull on next login anyway). Apply backpressure so producers never overrun consumers, and route persistently failing events to a DLQ for out-of-band retry. This keeps perceived staleness within the few-seconds budget for the users who are actually looking.

**Q: At 100× scale, what changes structurally?**
A: Geo-partition the graph and posts by user home-region; per-region Redis/posts shards with async cross-region replication; edge/near-user timeline assembly; tiered storage (hot posts on SSD, cold to object storage); a dedicated VIP pipeline for hot accounts; and likely a purpose-built timeline storage engine once generic Redis memory cost dominates. Lower the celebrity threshold and add partial/warm fan-out so the push and pull tiers stay balanced.

**Q: How do you handle deletes and edits given everything is cached and fanned out?**
A: A delete publishes a tombstone event; fan-out-style cleanup removes the id from timelines lazily, and hydration filters out tombstoned/deleted ids at read time (defense in depth — we don't rely on perfect cache removal). Edits are modeled as a new version of the immutable post object with the same id; caches and the CDN are invalidated by version, and hydration always serves the latest version. Because hydration is the final gate, a deleted or edited post never surfaces stale even if a timeline ZSET still references the id.
