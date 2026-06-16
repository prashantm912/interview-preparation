# Design a Video Streaming Platform (YouTube / Netflix)

Design a globally-distributed video platform that ingests user-uploaded (or studio-licensed) video, transcodes it into multiple resolutions, and streams it to hundreds of millions of viewers with low startup latency and minimal rebuffering — while keeping bandwidth costs survivable.

[← Back to master index](../../README.md) · [← System Design index](../README.md)

---

## 1. Requirements

### Functional requirements
- **Upload**: a creator uploads a source video file (e.g. ProRes/H.264 master, up to multiple GB). The system durably stores it and kicks off transcoding.
- **Transcoding**: convert the master into multiple resolutions/bitrates (240p → 4K) and package for adaptive streaming.
- **Playback (VOD)**: a viewer presses play and gets smooth adaptive-bitrate (ABR) streaming with fast startup (< 2 s) and minimal rebuffering.
- **Live streaming**: ingest a live RTMP/SRT feed, transcode in near-real-time, and deliver to viewers with low glass-to-glass latency.
- **Metadata**: title, description, tags, thumbnails, channel, duration, captions.
- **Discovery**: search, recommendations ("up next", home feed), trending.
- **Engagement**: view counts, likes, comments, watch history.
- **Thumbnails**: auto-generated sprite/preview frames + creator-uploaded custom thumbnails.

### Non-functional requirements
- **Scale**: ~2 B monthly active users, ~500 M daily active users, peak ~50 M concurrent streams. ~500 hours of video uploaded per minute (YouTube-class).
- **Latency**: playback start < 2 s (p95); seek < 500 ms; live glass-to-glass 2–10 s (LL-HLS) down to sub-second (WebRTC) for interactive use cases.
- **Availability**: 99.95%+ for playback. Playback is the sacred path — uploads/transcoding can degrade gracefully, but a viewer pressing play must (almost) never fail.
- **Durability**: 11 nines for stored masters and renditions (object store with cross-region replication).
- **Consistency**: read-heavy, eventually consistent is fine for view counts, recommendations, and even comments. Strong consistency only matters for billing/ownership/monetization.
- **Cost**: egress bandwidth is the dominant cost. CDN offload ratio and codec efficiency directly move the P&L.

### Clarifying questions a candidate should ask
1. UGC (YouTube) or licensed catalog (Netflix)? This changes upload volume, moderation, and catalog size dramatically.
2. Global or single-region? Drives CDN/PoP and multi-region replication strategy.
3. Is live a first-class requirement, or VOD only? Live changes the ingest and transcode path fundamentally.
4. Read/write ratio? (Video is ~1000:1 read:write or higher — optimize hard for reads.)
5. What's the SLA on "video available after upload"? Minutes (UGC) vs. pre-published (Netflix) is a different pipeline.
6. DRM / monetization required? Affects packaging (CENC, Widevine/FairPlay/PlayReady) and metadata consistency.

---

## 2. Capacity Estimation

Assume YouTube-scale UGC. Numbers are deliberately round for back-of-envelope work.

### Upload / ingest
- 500 hours uploaded per minute = 500 × 60 = **30,000 video-seconds per second** of new content.
- Avg upload ≈ 10 min, source ≈ 1 GB (decent quality H.264 master).
- Uploads/sec = (500 h/min ÷ 10 min) ÷ 60 ≈ **5 uploads/sec average**, peak ~3–5x ≈ 25/sec.
- Raw ingest storage/day = 500 h/min × 1440 min/day × 1 GB ≈ **720 TB/day** of masters.

### Transcoding output (storage multiplier)
Each master fans out into ~6 renditions (144p, 240p, 360p, 480p, 720p, 1080p) plus 4K for some. Total encoded output is typically **~1.5–2x the master size** when you sum all ladder rungs (lower rungs are cheap; 1080p/4K dominate).
- Encoded storage/day ≈ 720 TB × 1.75 ≈ **~1.26 PB/day**.
- Annual encoded storage ≈ 1.26 PB × 365 ≈ **~460 PB/year** (before tiering cold content to cheaper storage).

### Playback bandwidth (the expensive part)
- Peak concurrent streams: **50 M**.
- Avg sustained bitrate (mix of mobile 1 Mbps and TV 8 Mbps) ≈ **3 Mbps**.
- Peak egress = 50 M × 3 Mbps = **150 Tbps**.
- At, say, $0.01/GB blended CDN egress: 150 Tbps = 18.75 TB/s = 18,750 GB/s ≈ **$187/sec ≈ $16 M/day** at the edge if 100% billed. This is why **CDN offload + owning PoPs (like Netflix Open Connect / Google Edge) is existential** — you push the marginal cost toward your own hardware and peering.

### Reads (metadata QPS)
- 500 M DAU × ~20 video views/day = 10 B views/day ≈ **~115 K view-starts/sec average**, peak ~500 K/sec.
- Each view-start = several metadata reads (video doc, channel, recs, comments) → **~1–2 M metadata reads/sec** at peak. Served overwhelmingly from cache (Redis/CDN) at >95% hit rate.

### View-count writes
- 10 B view increments/day ≈ **115 K writes/sec average**, peak ~500 K/sec. Cannot hit the primary DB per view → must batch/aggregate (see Deep Dive 6.4).

### Cache memory
- Hot working set ≈ top 1–5% of videos drive ~90% of traffic. If 100 M videos are "active," top 5 M metadata docs at ~2 KB each = **~10 GB** of metadata cache — trivially fits in a Redis cluster. The expensive cache is the **CDN byte cache** (petabytes of hot segments at the edge), not metadata.

---

## 3. API Design

```http
# ---------- Upload ----------
# 1. Initiate a resumable upload, get a presigned URL / upload session
POST /v1/videos:initiateUpload
  Body: { "title": "...", "filename": "raw.mov", "sizeBytes": 1073741824,
          "contentType": "video/quicktime", "visibility": "private" }
  200:  { "videoId": "v_9aZ...", "uploadUrl": "https://upload.cdn/...session=xyz",
          "chunkSizeBytes": 8388608 }

# 2. Client PUTs chunks directly to object store (resumable, bypasses app servers)
PUT  {uploadUrl}    Header: Content-Range: bytes 0-8388607/1073741824

# 3. Finalize → triggers the transcode pipeline
POST /v1/videos/{videoId}:finalize
  200:  { "videoId": "v_9aZ...", "status": "PROCESSING" }

# ---------- Status ----------
GET  /v1/videos/{videoId}/status
  200:  { "status": "PROCESSING|READY|FAILED",
          "renditions": ["240p","480p","720p","1080p"], "progressPct": 72 }

# ---------- Playback ----------
# Returns a signed manifest URL; player then talks only to the CDN
GET  /v1/videos/{videoId}/playback
  200:  { "manifestUrl": "https://cdn/.../master.m3u8?token=...",
          "drm": { "scheme": "widevine", "licenseUrl": "..." },
          "thumbnailSprite": "https://cdn/.../sprite.vtt" }

# ABR manifest (served by CDN, not the API) — HLS example
GET  https://cdn/.../master.m3u8        # variant playlist (the ladder)
GET  https://cdn/.../1080p/index.m3u8   # media playlist (segment list)
GET  https://cdn/.../1080p/seg_0042.ts  # 4–6 s media segment

# ---------- Engagement ----------
POST /v1/videos/{videoId}/view     # fire-and-forget heartbeat, batched
POST /v1/videos/{videoId}/like
GET  /v1/videos/{videoId}/comments?cursor=...&limit=50

# ---------- Discovery ----------
GET  /v1/feed/home?cursor=...           # personalized recommendations
GET  /v1/search?q=...&cursor=...

# ---------- Live ----------
# Ingest endpoint (RTMP/SRT push from encoder)
rtmp://ingest.live/app/{streamKey}
GET  /v1/live/{channelId}/playback      # LL-HLS / DASH manifest
```

Design notes: clients upload **directly to object storage** via presigned URLs (app servers never proxy GBs of video). Playback returns a **signed, short-TTL manifest URL**; all heavy traffic then flows client↔CDN, keeping origin and app tiers thin.

---

## 4. Data Model

### Choice of stores (polyglot persistence)
| Data | Store | Why |
|---|---|---|
| Video metadata (title, owner, status, rendition list) | **Sharded SQL (Vitess/Spanner) or DynamoDB** | Point lookups by `videoId`; some relational integrity (owner→channel). Read-heavy, cache in front. |
| Segments, manifests, masters, thumbnails | **Object store (S3/GCS)** + lifecycle tiering | Immutable blobs, 11-nines durability, cheap cold tiers. |
| View counts / likes / watch-time | **Cassandra/DynamoDB (counter or LWW), pre-aggregated via Kafka+Flink** | Massive write volume, eventual consistency acceptable. |
| Comments | **Cassandra/DynamoDB** keyed by `videoId` | High write fan-in, tree structure, eventual consistency fine. |
| Watch history / user profile | **Cassandra/DynamoDB** keyed by `userId` | Per-user partition, time-ordered. |
| Search index | **Elasticsearch / OpenSearch** | Full-text + relevance. |
| Recommendations features | **Feature store + vector DB (e.g. embeddings)** | Candidate generation + ranking. |
| Sessions / hot metadata / rate limits | **Redis** | Sub-ms reads. |

### Representative schemas

```sql
-- Video metadata (sharded by videoId hash)
CREATE TABLE videos (
  video_id      VARCHAR(20) PRIMARY KEY,   -- Snowflake-style 64-bit id, base62
  channel_id    BIGINT NOT NULL,
  title         VARCHAR(200),
  description    TEXT,
  duration_s    INT,
  status        ENUM('uploading','processing','ready','failed','blocked'),
  visibility    ENUM('public','unlisted','private'),
  master_uri    VARCHAR(512),              -- s3://masters/v_9aZ.../raw.mov
  manifest_uri  VARCHAR(512),              -- s3://hls/v_9aZ.../master.m3u8
  created_at    TIMESTAMP,
  INDEX (channel_id, created_at)
);

-- Renditions (one row per ladder rung) — sharded with the parent video
CREATE TABLE renditions (
  video_id   VARCHAR(20),
  rung       VARCHAR(8),      -- '1080p'
  codec      VARCHAR(16),     -- 'h264','av1','hevc'
  bitrate_bps INT,
  segment_count INT,
  uri        VARCHAR(512),
  PRIMARY KEY (video_id, rung, codec)
);
```

```text
# Cassandra: per-video view aggregate (wide row, eventually consistent)
view_counts_by_video:
  partition key = video_id
  total_views  counter
  -- hot daily rollups land here from a Flink job, not from per-view writes

# Cassandra: comments
comments_by_video:
  partition key = video_id
  clustering    = (comment_id DESC)   -- newest first, cursor-paginated
  user_id, body, parent_id, created_at
```

**SQL vs NoSQL justification.** Core video metadata is a small, relational, point-lookup-heavy dataset — sharded SQL (Vitess) or a globally-consistent SQL (Spanner) gives clean ownership/billing semantics and is easy to cache. The truly huge, write-skewed datasets (views, comments, history) are partition-friendly and tolerate eventual consistency, so wide-column NoSQL (Cassandra/DynamoDB) wins on write throughput and horizontal scale. Blobs never go in a DB — they go in object storage with the DB only holding the URI.

---

## 5. High-Level Architecture

```text
                          ┌──────────────────────────────────────────┐
   CREATOR                 │            UPLOAD / TRANSCODE             │
  ┌────────┐  presigned    │                                          │
  │ Client │──PUT chunks──▶ │  ┌─────────┐   finalize   ┌──────────┐  │
  └────────┘   (resumable) │  │ Object  │─────event────▶│  Kafka   │  │
       │                   │  │ Store   │   (s3 event)  │ (ingest  │  │
       │  initiate/finalize│  │(masters)│               │  topic)  │  │
       ▼                   │  └─────────┘               └────┬─────┘  │
  ┌──────────┐  REST       │                                 │        │
  │ API / BFF│◀────────────┘             ┌───────────────────▼──────┐ │
  └────┬─────┘                           │  Transcode Orchestrator   │ │
       │  metadata                       │ (splits master into       │ │
       ▼                                 │  chunks, schedules jobs)  │ │
  ┌──────────┐   ┌──────────┐            └──────┬────────────┬───────┘ │
  │ Metadata │   │  Redis   │                   ▼            ▼         │
  │  SQL     │   │  cache   │           ┌──────────────┐ ┌──────────┐ │
  │ (Vitess) │   └──────────┘           │ Transcode    │ │ Thumbnail│ │
  └──────────┘                          │ worker fleet │ │ + sprite │ │
                                        │ (FFmpeg/GPU, │ │ extractor│ │
                                        │ AV1/HEVC/H264│ └────┬─────┘ │
                                        └──────┬───────┘      │       │
                                               ▼              ▼       │
                                        ┌────────────────────────────┐│
                                        │  Object Store (renditions, ││
                                        │  HLS/DASH manifests, sprites)│
                                        └─────────────┬──────────────┘│
                                        ───────────────────────────────
                                                      │ origin pull
   VIEWER                                             ▼
  ┌────────┐   1. GET /playback   ┌──────────┐   ┌────────────────┐
  │ Player │─────────────────────▶│ API / BFF│   │   Origin /     │
  │ (ABR)  │◀── signed manifest ──└──────────┘   │  Shield cache  │
  └───┬────┘                                      └───────┬────────┘
      │ 2. GET manifest + segments                        │ fill-on-miss
      ▼                                                   ▼
  ┌────────────────────────────────────────────────────────────────┐
  │  GLOBAL CDN / EDGE PoPs  (Open-Connect-style boxes inside ISPs)  │
  │  caches hot segments; ABR logic lives in the client player       │
  └────────────────────────────────────────────────────────────────┘

  ENGAGEMENT/ANALYTICS:  Player ──view/like events──▶ Kafka ──▶ Flink ──▶ Cassandra (counts) + Data Lake
  RECOMMENDATIONS:       Batch (candidate gen) + online ranker  reads feature store/vector DB ──▶ /feed/home
```

### Component walkthrough
- **API / BFF (backend-for-frontend)**: thin, stateless, autoscaled. Handles auth, metadata reads/writes, issues presigned upload URLs and signed playback manifests. Never touches video bytes.
- **Object store**: source masters + all encoded renditions + manifests + thumbnails. Lifecycle policies tier cold content to cheaper storage; cross-region replication for durability.
- **Kafka**: the backbone for asynchronous work — upload-finalized events, transcode job results, and the firehose of view/engagement events.
- **Transcode orchestrator + worker fleet**: splits the master into independent chunks (GOP-aligned), fans them out to a worker fleet (FFmpeg, often GPU/ASIC like Argos VCUs for scale), produces every ladder rung and codec, then packages into HLS/DASH and writes manifests.
- **CDN / edge**: where 99%+ of bytes are served. Players fetch the manifest and segments from the nearest PoP; origin is only hit on cache miss (protected by a shield/origin-shield layer to collapse misses).
- **Engagement/analytics pipeline**: events → Kafka → Flink/Spark → aggregated counters + data lake for recs and analytics.
- **Recommendations**: offline candidate generation (collaborative filtering / two-tower embeddings) + an online ranker that personalizes the home feed and "up next."

---

## 6. Deep Dives

### 6.1 The transcoding pipeline (chunked, parallel, GOP-aligned)
A 2-hour 4K master would take far too long to encode serially. The trick is **chunked parallel transcoding**:

1. **Probe & split**: analyze the master, then split it at **GOP/keyframe boundaries** into independent segments (e.g. 4–10 s each). GOP alignment is essential — each chunk must start with a keyframe so it can be decoded and re-encoded independently.
2. **Fan-out**: each chunk × each ladder rung × each codec becomes an independent job placed on a queue. A 2-hour video at 10 s chunks = 720 chunks; × 6 rungs × 2 codecs = ~8,640 jobs that run in parallel across the worker fleet. End-to-end wall-clock drops from hours to minutes.
3. **Encode**: workers run FFmpeg (CPU) or hardware encoders (GPU/ASIC). AV1/HEVC for high-value content (better compression, lower egress), H.264 for universal compatibility.
4. **Stitch & package**: concatenate per-rung chunks, generate HLS (`.m3u8` + `.ts`/`fMP4`) and DASH (`.mpd`) manifests, compute per-title encoding bitrate ladders.
5. **Validate & publish**: QC pass (corruption, A/V sync), DRM packaging (CENC), then flip metadata `status = ready`.

**Trade-off — chunk size.** Small chunks = more parallelism + faster turnaround but more orchestration overhead and slightly worse compression (each chunk re-primes the encoder). 4–10 s is the sweet spot, matching the streaming segment size so you can often reuse encode boundaries as segment boundaries.

**Per-title encoding.** Instead of a fixed bitrate ladder, analyze each title's complexity — a static talking-head needs far fewer bits than fast action. Netflix's per-title/per-shot encoding cut bitrate ~20% at equal quality, which is a direct egress-cost win at this scale.

### 6.2 Adaptive bitrate streaming (HLS/DASH) and where the ABR logic lives
The platform encodes a **ladder** of renditions and exposes a **manifest** listing them. The **client player owns the ABR decision** — it measures throughput and buffer level and picks the next segment's rung:

- Startup: begin low (fast first frame), then ramp up as bandwidth is measured → low startup latency.
- Mid-stream: if buffer drains or throughput drops (e.g. moving from WiFi to cellular), step down a rung to avoid a rebuffer; step up when headroom returns.
- Algorithms: throughput-based, buffer-based (BOLA), or hybrid. Modern players use buffer-occupancy-aware hybrids to balance quality vs. stall risk.

**HLS vs DASH.** HLS (Apple) is mandatory for iOS/Safari/tvOS; DASH is codec-agnostic and common elsewhere. In practice you package **CMAF/fMP4 segments once** and serve both HLS and DASH manifests over the same media files — avoiding double storage. This is a key cost decision: one set of bytes, two manifest flavors.

### 6.3 CDN, edge caching, and the bandwidth economics
At 150 Tbps peak, you cannot serve from origin. Strategy:

- **Multi-tier caching**: edge PoP → regional shield → origin. The shield collapses many edge misses into one origin fetch, protecting origin and cutting cross-region transfer.
- **Own the edge**: Netflix Open Connect / Google's edge nodes physically sit **inside ISP networks**. Bytes never traverse expensive transit links; you trade capex for near-zero marginal egress and better latency. This is the single biggest cost lever.
- **Cache-friendly URLs**: segments are immutable and content-addressed, so they cache forever (`Cache-Control: immutable`). Only manifests have short TTLs (needed for live).
- **Predictive/pre-positioning fill**: for known-popular content (new Netflix release, viral video), **push** segments to edges before peak (off-peak fill) instead of pulling on first miss — turns a thundering-herd cold start into a warm cache. Netflix pre-positions catalog overnight.
- **Hot-key protection**: a viral video can hammer one origin object. Request coalescing at the shield + tiered caching prevents origin meltdown (see also 6.4 for the metadata equivalent).

### 6.4 View counts at scale (the write-amplification problem)
500 K view-increments/sec cannot hit a primary DB row — and a single viral video creates a **hot partition** (millions of writes to one `video_id`). Solution: never write per-view to the source of truth.

1. Player emits a **view event** (validated/de-duped to fight bot inflation) → Kafka.
2. A **Flink/Spark streaming job** windows and aggregates per `video_id` (e.g. 10-second tumbling windows), counting deduplicated views.
3. Aggregates are **incrementally merged** into Cassandra/DynamoDB counters — turning millions of writes into a few per video per window.
4. Reads serve a **cached approximate count** from Redis/CDN; exact counts are reconciled in batch.

**Hot-partition mitigation**: shard a viral video's counter across N sub-keys (`video_id#0..N`) and sum on read — spreading writes across partitions. Counts are deliberately **eventually consistent** ("approximate" labels on big numbers are fine; nobody needs view #4,012,887 to be exact in real time). This is a textbook CAP choice: we pick **AP** for view counts.

### 6.5 Live vs VOD
VOD is "encode once, serve forever." Live flips every assumption:

- **Ingest**: encoder pushes RTMP/SRT to an ingest server. No master file — it's a continuous stream.
- **Real-time transcode**: a low-latency transcoder produces the ladder on the fly; you can't fan out across a fleet the way VOD does because chunks arrive sequentially. Latency budget is tight.
- **Packaging**: **LL-HLS / LL-DASH** with short segments + chunked transfer / partial segments to get glass-to-glass latency to ~2–5 s. For truly interactive (auctions, betting, sub-second) you drop to **WebRTC**, trading CDN cacheability for latency.
- **Manifest TTL**: live manifests update constantly (short TTL, rolling window of segments), unlike VOD's immutable manifest.
- **DVR & VOD conversion**: keep a rolling buffer for "rewind live," and after the stream ends, persist segments as a normal VOD asset.

**Trade-off**: LL-HLS rides the existing CDN (cheap, scales to millions) at 2–5 s latency; WebRTC gives sub-second but is harder to scale and doesn't cache. Choose per use case (concert = LL-HLS, live auction = WebRTC/SFU).

---

## 7. Scaling, Bottlenecks & Failure Handling

**What breaks first?** Almost never metadata — it's small and cached. The pressure points, in order:

1. **Egress bandwidth / CDN capacity** — the real ceiling. Scale via more PoPs, embedded ISP caches, better codecs (AV1 cuts ~30% bytes vs H.264), and per-title encoding. Pre-position popular content to avoid origin thundering herds.
2. **Transcode fleet** — bursty and CPU/GPU-bound. Decouple via Kafka so a backlog just delays "ready," never drops uploads. Autoscale workers on queue depth; use spot/preemptible instances for cost; ASIC encoders (Argos-class) at extreme scale.
3. **Origin on a viral cold start** — shield/origin-shield tier + request coalescing collapses N edge misses into 1 origin fetch. Pre-warm on predicted virality.
4. **View-count hot partitions** — Kafka + Flink aggregation + sub-key sharding (6.4).
5. **Metadata read hot keys** — a viral video's metadata doc: serve from Redis/CDN, add jittered TTLs to avoid synchronized expiry stampedes, use request coalescing.

**Replication & partitioning**
- Object store: cross-region replication, erasure coding for cost-efficient durability, lifecycle tiering (hot SSD → cold/archive) by age and popularity.
- Metadata SQL: shard by `video_id` (Vitess) or globally-consistent Spanner; read replicas per region.
- NoSQL (views/comments/history): partition by `video_id` or `user_id`; tunable consistency (write quorum for durability, read-one for speed).

**Failure handling**
- **Playback is sacred**: if recs, comments, or counts are down, the video still plays (graceful degradation — render the page with stale/placeholder side data). Circuit breakers around non-critical dependencies.
- **Transcode failures**: idempotent, retryable jobs keyed by `(video_id, chunk, rung, codec)`; DLQ for poison inputs; partial-ladder publish (serve 480p while 4K still encodes).
- **CDN PoP failure**: anycast/GeoDNS reroutes to the next-nearest PoP; client retries the next CDN in a multi-CDN setup.
- **Region failure (DR)**: active-active across regions for playback; metadata and objects replicated; DNS failover. RPO≈seconds for events (Kafka replicated), RTO minutes for control plane.
- **Backpressure**: Kafka absorbs spikes; consumers scale horizontally; upload throttling/rate limits at the BFF protect the pipeline.

---

## 8. Trade-offs & Alternatives

| Decision | Chosen | Alternative | Why / when to switch |
|---|---|---|---|
| View counts | Async aggregate (Kafka+Flink), eventually consistent | Synchronous DB increment | Sync can't survive 500 K/s or hot partitions; only viable for tiny scale. |
| Streaming protocol | CMAF packaged once, HLS+DASH manifests | Separate HLS and DASH media | Single media set halves storage; switch only if a platform forces unique containers. |
| Codec | H.264 baseline + AV1/HEVC for popular content | All H.264 | AV1 cuts egress ~30% but costs more CPU to encode — apply selectively to high-view content where egress savings dominate. |
| Edge | Own PoPs / embedded ISP caches | 100% third-party CDN | Owning the edge is the dominant cost lever at scale; small platforms should start with Cloudflare/CloudFront/Fastly and only build PoPs once egress bills justify capex. |
| Metadata store | Sharded/Spanner SQL | Pure NoSQL | SQL gives clean ownership/billing/relational integrity for a small dataset; NoSQL where write-skew is huge. |
| Live latency | LL-HLS (CDN-scalable) | WebRTC sub-second | Use WebRTC only for interactive use cases; it sacrifices CDN cacheability. |

**At 10x scale**: lean harder on per-title/per-shot encoding and AV1 to bend the egress curve; add more embedded ISP caches; shard transcode by region to keep masters near compute.

**At 100x scale**: custom transcode ASICs become mandatory (FFmpeg-on-CPU is too expensive); ML-driven predictive pre-positioning of content to edges; ML-tuned per-network ABR. The bottleneck stays physics — bytes over fiber — so codec efficiency and edge ownership are the long-term wins.

---

## Interview Q&A by Level

### 🟢 Basic
**Q: Why do we transcode into multiple resolutions instead of serving the original file?**
Viewers have wildly different devices and networks. Serving one high-bitrate file would stall mobile users and waste bandwidth on small screens. Multiple renditions let the player pick the right quality (ABR), giving fast startup and minimal rebuffering across all conditions.

**Q: What is adaptive bitrate streaming?**
The video is encoded at several bitrates; the client player continuously measures bandwidth and buffer level and switches to the rung that streams smoothly — starting low for a fast first frame, then ramping up. The decision lives in the player, driven by a manifest (HLS `.m3u8` / DASH `.mpd`).

**Q: Why store video in object storage instead of a database?**
Videos are large immutable blobs. Object stores (S3/GCS) give 11-nines durability, cheap tiering, and serve as CDN origin. The database holds only metadata and the object URI — putting blobs in a DB would wreck its performance and cost.

**Q: Why is a CDN essential here?**
99%+ of traffic is bytes streamed to viewers. A CDN caches segments at edge locations near users, cutting latency and offloading origin. Without it, origin bandwidth and latency would be untenable at any real scale.

### 🟡 Intermediate
**Q: How do you make uploads scale without app servers choking on GB files?**
The client gets a **presigned URL** and uploads chunks **directly to object storage** (resumable, so a dropped connection resumes mid-file). App servers only issue the URL and handle finalize — they never proxy bytes. Finalize emits a Kafka event that triggers transcoding.

**Q: How do you count views at 500 K/sec including viral videos?**
Don't write per view to the DB. Events flow to Kafka, a Flink job aggregates them in time windows (deduping bots), and merged counts land in Cassandra/DynamoDB. For a viral video's hot partition, shard the counter across sub-keys and sum on read. Counts are eventually consistent — fine for this domain.

**Q: HLS vs DASH — what do you ship?**
Package media once as **CMAF/fMP4** and expose both an HLS manifest (required for Apple devices) and a DASH manifest (codec-agnostic, used elsewhere) over the same segments. One set of bytes, two manifest flavors — avoids doubling storage.

**Q: How does transcoding finish quickly for a 2-hour 4K video?**
Split the master at keyframe (GOP) boundaries into independent chunks, fan them out across a worker fleet (chunk × rung × codec = thousands of parallel jobs), encode in parallel, then stitch and package. Wall-clock drops from hours to minutes.

### 🟠 Advanced
**Q: A new release goes viral and the origin gets hammered on cold cache. How do you survive it?**
Multi-tier caching with an **origin shield** that coalesces many edge misses into a single origin fetch. For predictable virality (a Netflix premiere), **pre-position** segments to edge caches during off-peak so peak hits a warm cache. Immutable, content-addressed segment URLs make this safe to cache aggressively.

**Q: How do you keep bandwidth costs from exploding at 150 Tbps peak?**
Three levers: (1) **own the edge** — embed caches inside ISP networks (Open-Connect-style) so bytes skip expensive transit; (2) **better codecs** — AV1/HEVC cut ~30% bytes for high-view content; (3) **per-title/per-shot encoding** — allocate bits by content complexity, ~20% savings at equal quality. Egress is the P&L, so these compound.

**Q: How does live streaming differ architecturally from VOD, and what's the latency trade-off?**
Live has no master file — a continuous RTMP/SRT feed is transcoded in real time and packaged with **LL-HLS** (short/partial segments, chunked transfer) for ~2–5 s latency over the existing CDN. Manifests have short TTLs and a rolling window. For sub-second interactivity you switch to **WebRTC**, sacrificing CDN cacheability and scale. Choose per use case.

**Q: What's your consistency model across the system, and where does CAP bite?**
Read-heavy and overwhelmingly **AP/eventually consistent** — view counts, recommendations, comments, history all tolerate staleness. **Playback availability is non-negotiable**, so non-critical dependencies sit behind circuit breakers and degrade gracefully. Strong consistency is reserved for **ownership, billing, and monetization**, where a Spanner-class store gives correctness at the cost of some latency.

### 🔴 Expert
**Q: Design the recommendation system at a high level and explain its scaling shape.**
Two stages. **Candidate generation** (offline/batch): collaborative filtering or a two-tower embedding model produces hundreds of candidates per user from a corpus of billions — cheap, retrieved via ANN over a vector index. **Ranking** (online): a heavier model scores those candidates with real-time features (recent watch, context, freshness) from a feature store, returning the ordered feed. It scales because expensive recall is precomputed and only the small candidate set is ranked online; the feed is cached and refreshed asynchronously. Watch events feed back through Kafka to retrain.

**Q: How do you guarantee durability without paying for 3x full replication of petabytes?**
Use **erasure coding** (e.g. Reed-Solomon 10+4) in the object store: survive multiple disk/node failures at ~1.4x overhead instead of 3x, for 11-nines durability. Cross-region replication for the hot/critical copy, and **lifecycle tiering** moves cold renditions to archival storage by age and popularity. Masters can even be re-derivable in part — though re-transcoding cost usually argues for keeping them.

**Q: How would you fight bot/fraudulent view inflation while still counting at 500 K/sec?**
De-dup and validate at the streaming layer: a view counts only after meaningful watch time (e.g. a heartbeat threshold), per-user/per-IP rate limiting, device fingerprinting, and anomaly detection on the Flink stream (sudden spikes from narrow IP ranges). Suspect events are quarantined into a separate stream and reconciled in batch, so the real-time count stays approximately right and the canonical count is later corrected. This is why counts are explicitly eventual — it gives room to retroactively scrub fraud.

**Q: At 100x today's scale, what fundamentally changes in this design?**
The control plane (metadata, APIs) barely changes — it's already cache-fronted and shardable. What changes is the **physics of bytes and encode cost**: FFmpeg-on-CPU becomes uneconomical, so you move to **custom transcode ASICs**; egress forces **deeper ISP-embedded edge ownership** and aggressive **AV1/per-shot encoding**; and content placement becomes an **ML prediction problem** (pre-position the right content at the right PoP before demand). The architecture's shape holds; the cost-optimization layers get far more specialized.
