# Design an Object Storage Service (S3-like Blob Store)

> A worked, interview-grade design of an internet-scale object store: PUT/GET arbitrary blobs by key, survive disk and datacenter failure with 11-nines durability, and scale to exabytes and trillions of objects — while keeping the metadata plane and the data plane independently scalable.

[← Back to master index](../../README.md) · [← System Design index](../README.md)

---

## 1. Requirements

Object storage looks like "a giant hash map of key → bytes," but the interview is really probing how you reason about **durability** (data must survive disks dying every day), the **split between a metadata plane and a data plane**, **erasure coding vs replication**, and **consistency under concurrent writes**. Establish scope before drawing.

### Functional requirements
- **PUT object**: store an arbitrary blob (1 byte to 5 TB) under a `bucket/key`, return a version + ETag.
- **GET object**: retrieve the bytes for a `bucket/key` (optionally a specific version, optionally a byte range).
- **DELETE object**: remove an object (or insert a delete-marker if versioning is on).
- **LIST**: enumerate keys in a bucket by prefix, paginated, lexicographically ordered.
- **Multipart upload**: upload large objects in parts (each part independently retryable), then complete/abort.
- **Buckets**: create/delete buckets; per-bucket config (versioning, lifecycle/TTL, region, access policy).
- **Metadata**: store user-defined key→value tags and system metadata (content-type, size, checksum).
- **Access control & presigned URLs**: IAM-style policies; time-limited signed URLs for direct client access.

### Non-functional requirements — with concrete numbers
- **Scale**: 1 trillion objects, **100 PB → 1 EB** stored; **100K GET/s and 20K PUT/s** average, ~3x peak.
- **Latency**: small-object GET **p99 < 100 ms** (first byte); PUT **p99 < 200 ms** for objects ≤ 1 MB. Throughput, not latency, dominates for large objects (saturate the NIC, target **multi-GB/s** per stream).
- **Availability**: **99.99%** for the request path (read availability is the priority — reads must survive partial outages).
- **Durability**: **99.999999999% (11 nines)** — at 1 trillion objects that's an expected loss of ~0.01 objects/year. This is the hardest and most important number.
- **Consistency**: **read-after-write** for new object PUTs (a GET right after a successful PUT must return the new data); **strong consistency** on overwrites and deletes (no stale reads after the write is acked). Modern S3 is strongly consistent.
- **Security**: encryption at rest (per-object data keys) and in transit (TLS); per-bucket/per-object access policies; signed-URL auth; tamper-evident checksums.

### Clarifying questions a strong candidate asks
1. What is the **object-size distribution**? Many tiny objects (thumbnails) and few huge ones (videos/backups) need different handling. (Drives small-object packing vs large-object chunking.)
2. **Read:write ratio** and access skew — is there a hot working set, or uniform random access? (Drives caching and tiering.)
3. **Durability vs cost target** — 11 nines via erasure coding, or cheaper with fewer nines? Any cold/archive tier?
4. **Consistency expectation** — is eventual consistency on LIST acceptable while object GET is strong?
5. **Single region or multi-region / cross-region replication**? (Changes the whole DR + latency story.)
6. **Largest object** and whether multipart/resumable uploads are required.
7. **Throughput vs latency priority** — are we serving CDN origins (throughput) or interactive apps (latency)?
8. **Compliance** — WORM/object-lock, retention, legal hold, audit logging?

> The single most important framing: **separate the metadata plane from the data plane.** Metadata is small, numerous, and needs strong-consistency lookups; data is huge, sequential, and needs raw throughput + durability. Conflating them is the classic junior mistake.

---

## 2. Capacity Estimation

Real numbers, shown long-hand. Assume a 5-year horizon and an average object size of **100 KB** (a blend of many tiny objects and fewer large ones).

### Request QPS
```
GET:  100,000 req/s avg  →  peak ~3x  →  ~300,000 GET/s
PUT:   20,000 req/s avg  →  peak ~3x  →   ~60,000 PUT/s
Read:write ratio ≈ 5:1   (reads dominate, but writes are far heavier than a shortener)
```

### Object & metadata count
```
1 trillion objects = 1 × 10^12
Each needs a metadata record (key, version, size, location pointers, checksum, ...)
Metadata record ≈ 1 KB
Metadata total: 1 × 10^12 × 1 KB = 1 × 10^15 B = 1 PB of metadata
```
A petabyte of **metadata** alone is why metadata gets its own horizontally-sharded store — it does not fit on one box, and it carries the strong-consistency lookups.

### Raw stored data (5-year horizon)
```
20,000 PUT/s × 100 KB/object = 2,000,000 KB/s = 2 GB/s ingested
2 GB/s × 86,400 s/day = 172,800 GB/day ≈ 173 TB/day  (gross, before deletes)
Over 5 years: 173 TB/day × 365 × 5 ≈ 316,000 TB ≈ 316 PB net-ish
Round the design target to ~300 PB → 1 EB to leave headroom.
```

### Durability overhead — replication vs erasure coding
```
3x replication:        300 PB × 3   = 900 PB physical  (200% overhead)
Reed–Solomon (10,4):   300 PB × 1.4 = 420 PB physical  (40% overhead)
```
Erasure coding saves ~480 PB of physical media at this scale — at ~$15/TB that is **~$7M of disk avoided**, which is exactly why large object stores use EC for warm/cold data (see Deep Dive 1).

### Bandwidth
```
Ingest:  60,000 PUT/s × 100 KB  ≈ 6 GB/s  write
Egress: 300,000 GET/s × 100 KB  ≈ 30 GB/s read
Egress is the constraint: 30 GB/s = 240 Gb/s — spread across many storage nodes
and front-end fleets, plus a CDN for the hot read set.
```
Unlike a URL shortener (rate-limited), an object store is **bandwidth-bound** — the design spreads bytes across thousands of disks/NICs and pushes hot reads to a CDN.

### Metadata cache (hot set)
```
Assume top 1% of keys serve ~50% of GETs (skewed access).
Hot keys ≈ 1% of 1T = 10 billion … too large to fully cache.
Cache the *active daily* hot set instead: say 200M hot keys/day.
200M × 1 KB metadata ≈ 200 GB cache → sharded Redis/memcached cluster.
```
A **~200 GB distributed metadata cache** absorbs the lookup load so the metadata DB isn't hit on every GET. Object *data* is too big to cache wholesale — that's the CDN's job for hot blobs.

---

## 3. API Design

REST over HTTPS, S3-compatible semantics. Auth via SigV4-style request signing or presigned URLs. The **data plane streams bytes**; the **metadata/control plane is JSON**.

```http
# --- Bucket operations (control plane) ---
PUT  /{bucket}                         # create bucket
DELETE /{bucket}                       # delete (must be empty)
PUT  /{bucket}?versioning              # enable versioning
PUT  /{bucket}?lifecycle               # set TTL / tiering rules

# --- Simple object PUT (data plane) ---
PUT /{bucket}/{key}
Authorization: AWS4-HMAC-SHA256 ...
Content-Length: 1048576
Content-Type: image/jpeg
x-amz-content-sha256: <client checksum>
<binary body streamed>
→ 200 OK
   ETag: "9b2cf535f27731c974343645a3985328"   # MD5 / content hash
   x-amz-version-id: 3HL4kqtJlcpXroDTDmjVBH40Nrjfkd

# --- Object GET (data plane), with optional byte range ---
GET /{bucket}/{key}
Range: bytes=0-1048575                 # optional partial read
→ 200 OK | 206 Partial Content
   Content-Length, ETag, x-amz-version-id, Last-Modified
   <binary body streamed>
→ 404 Not Found
→ 412 Precondition Failed              # If-Match/If-None-Match conditional GET

# --- DELETE ---
DELETE /{bucket}/{key}                 # inserts delete-marker if versioned
→ 204 No Content

# --- Multipart upload (for large / resumable objects) ---
POST   /{bucket}/{key}?uploads         → { uploadId }      # initiate
PUT    /{bucket}/{key}?partNumber=N&uploadId=...           # upload part N, returns part ETag
POST   /{bucket}/{key}?uploadId=...    { parts:[{N,ETag}] } # complete (assembles object)
DELETE /{bucket}/{key}?uploadId=...                         # abort (GC the parts)

# --- LIST (paginated, prefix + delimiter) ---
GET /{bucket}?list-type=2&prefix=photos/2026/&delimiter=/&max-keys=1000&continuation-token=...
→ 200 { Contents:[{Key,Size,ETag,LastModified}], CommonPrefixes:[...], NextContinuationToken }

# --- Presigned URL (issued by control plane, used directly by client) ---
GET /{bucket}/{key}?X-Amz-Expires=900&X-Amz-Signature=...
```
Design notes: large objects **must** use multipart so a failed 5 TB upload doesn't restart from zero and so parts upload in parallel. LIST returns keys in **lexicographic order** with a `continuation-token` for pagination (no offset/limit — that doesn't scale across a trillion keys). The `ETag`/content-SHA lets the server verify integrity end-to-end.

---

## 4. Data Model

The system has two stores because it has two completely different workloads.

### Metadata store (the index)
Maps a logical `bucket/key/version` to the **physical location** of the bytes, plus all small attributes. Access pattern: point lookup by key (GET) and ordered range scan by prefix (LIST), at strong consistency, across a petabyte of metadata.

```
Table: object_metadata
  bucket          STRING   ─┐ partition / sort key:  (bucket, key, version)
  key             STRING    │   → point GET hits one partition
  version_id      STRING   ─┘   → LIST is a sorted range scan on key within a bucket
  size            INT64
  content_type    STRING
  etag / checksum STRING            # content hash for integrity
  storage_class   ENUM              # standard / infrequent / archive
  is_delete_marker BOOL
  created_at      TIMESTAMP
  -- physical placement:
  chunk_ids       LIST<UUID>        # the data chunks that make up this object, in order
  placement       LIST<{node, volume, ec_shard}>   # where each chunk's shards live
```

**Storage engine choice for metadata.** This needs ordered keys (for prefix LIST), point lookups, strong consistency, and horizontal scale to 1 PB. The right family is a **distributed ordered KV / NewSQL store** — e.g. an **LSM-tree-based KV like a sharded RocksDB front-ended by a consensus layer**, or a system like **Spanner / FoundationDB / TiKV / DynamoDB**. Why LSM-tree (RocksDB): metadata writes are high-volume and an LSM gives fast sequential writes and sorted iteration (perfect for LIST). Why a consensus layer (Raft/Paxos) per shard: that's what buys **strong, read-after-write consistency** — the write isn't acked until a quorum of metadata replicas has it. Real systems do exactly this: Ceph's RADOS uses a Paxos-backed monitor cluster; HDFS uses a quorum-journaled NameNode; S3 re-architected its index to a strongly-consistent store in 2020.

### Data store (the bytes)
Object bytes are split into **fixed-size chunks** (e.g. 64 MB) and stored as immutable blobs on storage nodes' local disks. The data plane is **append-only and immutable** — you never edit a chunk in place; an overwrite writes new chunks and repoints metadata. Immutability is what makes caching, replication, and integrity verification tractable.

```
Storage node local layout:
  /volume/{volume_id}/{chunk_id}     # a chunk = up to 64 MB of object data (or one EC shard)
  Each chunk: [ header | data | CRC32C trailer ]   # checksum stored with the data
```
- **Small objects** (< 1 MB, the majority by count) are **packed** many-to-one into large append-only "blob files" to avoid one-tiny-file-per-object filesystem overhead (inode pressure). Metadata records the (blob_file, offset, length). This is the classic Haystack/needle approach Facebook used for photos.
- **Large objects** are split into many 64 MB chunks distributed across many nodes for parallel read/write throughput.

### Why two stores, not one
| Dimension | Metadata plane | Data plane |
|---|---|---|
| Record size | ~1 KB | KB → TB |
| Count | 1 trillion records | trillions of chunks |
| Access | point + range, **strong consistency** | sequential bytes, **immutable** |
| Engine | ordered KV + consensus (RocksDB/Spanner) | local-disk blob store + EC/replication |
| Scaling lever | shard by bucket/key hash | spread chunks across disks |
Putting bytes in the metadata DB would blow it up; putting the index on the storage nodes would make LIST/strong-consistency impossible. **Separation of planes is the core design decision.**

---

## 5. High-Level Architecture

```
                        ┌─────────────────────────────┐
                        │     Clients / SDKs / CDN     │
                        └───────────────┬──────────────┘
                                        │ HTTPS (SigV4 / presigned)
                        ┌───────────────▼──────────────┐
                        │   Load Balancer (L7, GeoDNS)  │
                        └───────────────┬──────────────┘
                        ┌───────────────▼──────────────┐
                        │   API / Front-End Gateway      │  stateless, autoscaled
                        │   - authn/authz (IAM, signing) │
                        │   - request routing            │
                        │   - chunking / EC encode-decode│
                        └───┬───────────────────────┬───┘
            metadata lookup │                       │ data path (bytes)
                ┌───────────▼─────────┐   ┌──────────▼─────────────────┐
                │  Metadata Cache      │   │  Placement / Allocator     │  picks nodes,
                │  (Redis, ~200 GB)    │   │  (cluster map, CRUSH-like) │  balances load
                └───────────┬─────────┘   └──────────┬─────────────────┘
                            │ miss                    │
                ┌───────────▼─────────┐               │
                │  Metadata Store      │               │
                │  ordered KV + Raft   │               │
                │  (sharded RocksDB /  │               │
                │   Spanner-like)      │               │
                └─────────────────────┘               │
                                                       ▼
                       ┌───────────────────────────────────────────────────┐
                       │              Storage Node Fleet                     │
                       │  ┌────────┐  ┌────────┐  ┌────────┐  ┌────────┐     │
                       │  │ node 1 │  │ node 2 │  │ node 3 │  │ node N │ ...  │
                       │  │ disks  │  │ disks  │  │ disks  │  │ disks  │      │
                       │  └────────┘  └────────┘  └────────┘  └────────┘     │
                       │  chunks replicated 3x  OR  Reed–Solomon (k+m shards)│
                       └───────────────────────────────────────────────────┘
                                          ▲
                                          │ background plane (async)
                       ┌──────────────────┴────────────────────────────────┐
                       │  Repair/Scrubber · Rebalancer · GC · Lifecycle/Tier │
                       │  · Cross-region replication · Compaction            │
                       └────────────────────────────────────────────────────┘
```

### Request-flow walkthrough

**PUT (small object):**
1. Front-end authenticates the SigV4 signature, checks the bucket policy.
2. Allocator picks placement (which storage nodes / volumes), based on the cluster map, capacity, and failure-domain spread.
3. Front-end streams the body to the chosen storage nodes — writing **N replicas** or computing **k+m erasure shards** — and verifies the content checksum.
4. Only after a **durability quorum** of nodes acks (e.g. 2 of 3 replicas, or k+1 shards) does the front-end commit the **metadata record** (location pointers, size, ETag) to the metadata store via its consensus write.
5. The metadata commit is the **commit point** — once it's acked, the object is durable and read-after-write consistent. Return `200` + ETag.

**GET (small object):**
1. Authenticate. Look up metadata: cache first, then metadata store on miss.
2. Metadata returns the chunk IDs + placement (which nodes hold the data/shards).
3. Front-end fetches the chunk(s) from a storage node (or reconstructs from k EC shards if a node is down), verifies CRC, streams bytes to the client. Hot objects are served from the **CDN** without reaching origin.

**Large object:** multipart — each part is an independent PUT of chunks; `complete` writes the final metadata record that orders the parts. The data was already durable as it streamed, so completion is just a metadata operation.

### Components
- **Front-end gateway** (stateless): auth, routing, chunking, EC encode/decode, integrity checks. Scales horizontally behind the LB.
- **Allocator / placement service**: decides *where* chunks go using a deterministic algorithm (CRUSH-style) so reads can locate data and so failure domains (rack/host/AZ) are spread.
- **Metadata store + cache**: the strongly-consistent index from `bucket/key` → physical location.
- **Storage node fleet**: thousands of dense disk servers holding immutable chunks; the actual bytes and the bulk of the durability machinery.
- **Background plane**: scrubber (detect bit-rot), repair (rebuild lost shards), rebalancer (even out disks), GC (reclaim deleted/aborted-multipart space), lifecycle/tiering (move cold data to archive), cross-region replication. All async — never on the request hot path.

---

## 6. Deep Dives

### 6.1 Durability — erasure coding vs replication (how you actually get 11 nines)
Disks fail constantly: at 100K disks with a ~2%/year AFR, that's ~2,000 disk failures/year, several every day. Durability is an *engineering* property, not a hope.

**Replication (3x):** store 3 full copies on 3 nodes in 3 failure domains. Simple, fast recovery (copy one good replica), great for hot/small data. Cost: 200% overhead.

**Erasure coding (Reed–Solomon `k+m`, e.g. 10+4):** split data into `k=10` data shards, compute `m=4` parity shards, store all 14 across 14 failure domains. Any **10 of 14** shards reconstruct the object — survives **4 simultaneous losses**. Cost: only 40% overhead.
```
RS(10,4):  data ───split──▶ [d1 d2 ... d10]  ──encode──▶ [p1 p2 p3 p4]
           store d1..d10,p1..p4 on 14 nodes across racks/AZs
           lose any ≤4 → reconstruct by solving the linear system over GF(2^8)
```
**Durability math intuition:** with independent failures, surviving requires that no more than `m` of the `k+m` shards are lost before repair completes. Because repair runs continuously and in parallel (rebuild a lost shard from the other 13 in minutes), the probability of losing `m+1` shards within one repair window is astronomically small — that's how you reach 11 nines.

| | 3x Replication | RS(10,4) Erasure Coding |
|---|---|---|
| Storage overhead | 200% | 40% |
| Failures tolerated | 2 | 4 |
| Read latency (small obj) | low (read 1 copy) | higher (gather k shards) — or "fast path" reads 1 systematic shard |
| Repair cost | copy 1 shard | read k shards, recompute (network-heavy) |
| Best for | hot, small, latency-sensitive | warm/cold, large, cost-sensitive |

**Decision:** **replication for the hot tier and tiny objects** (latency + cheap repair); **erasure coding for warm/cold and large objects** (massive cost savings). Spread every shard/replica across **distinct failure domains** (different hosts, racks, and AZs) so one rack or AZ loss never drops more than `m` shards. This failure-domain spreading is what turns raw EC into real-world durability.

### 6.2 The metadata plane — sharding, LIST, and strong consistency
The metadata store carries the hardest correctness requirements.

- **Sharding:** partition by hash of `bucket` (or `bucket+key-prefix`) so load spreads evenly and a hot bucket can be split further. Each shard is a Raft group with 3–5 replicas; the leader serves strong reads/writes.
- **LIST is the hard one:** users expect keys returned in **sorted order by prefix**, paginated, across potentially billions of keys in one bucket. This needs an **ordered** index (LSM/B-tree), not a pure hash store — hence RocksDB-style ordered KV. LIST becomes a range scan `[prefix, prefix+1)` with a continuation token = the last key seen. The `delimiter` (`/`) rolls up "subdirectories" into `CommonPrefixes` so a flat keyspace *looks* hierarchical.
  - Tension: hash-sharding scatters a prefix across shards (good for write balance, bad for LIST locality); range-sharding keeps a prefix together (good LIST, but a sequential-key workload — e.g. timestamp prefixes — creates a **hot shard**). S3's classic guidance to randomize key prefixes existed precisely to avoid this; modern S3 auto-splits hot partitions.
- **Strong consistency / read-after-write:** the metadata write (via Raft quorum) is the **single commit point**. Because the data was made durable *before* the metadata commit, any GET that finds the metadata is guaranteed to find the bytes. Overwrites swap the metadata pointer atomically (new version), so a reader sees either the old or new object, never a torn mix.
- **Conditional writes / optimistic concurrency:** `If-Match`/`If-None-Match` on ETag and compare-and-swap on the version let clients do safe concurrent updates without a lock.

### 6.3 Concurrent writes to the same key — last-writer-wins vs versioning
Two clients PUT `bucket/photo.jpg` simultaneously. What happens?
- **Without versioning (LWW):** both write their own chunks; both attempt the metadata commit. The metadata store serializes them through the shard's Raft leader — **one commit wins, becomes current**; the other's chunks become orphaned and are reclaimed by GC. There is no corruption because the bytes are immutable and the *pointer* swap is the only mutation, and that swap is linearizable.
- **With versioning on:** every PUT creates a **new version** (`version_id`), and both survive — the latest-committed becomes "current," older versions remain retrievable. DELETE inserts a **delete-marker** (a tombstone version) rather than erasing data, so deletes are reversible and also strongly consistent.
- **Multipart + concurrency:** parts for an `uploadId` are isolated; only `complete` publishes the object. An abort or a never-completed upload leaves orphan parts that the GC sweeps after a lifecycle timeout.

The principle: **mutate only the metadata pointer, never the bytes.** That reduces every concurrency question to "who wins the linearizable pointer swap," which the consensus layer answers cleanly.

### 6.4 Data integrity & background repair (bit-rot, the silent killer)
Disks don't just die — they silently corrupt bits (latent sector errors). At exabyte scale this is a certainty, so integrity is continuous, not one-shot:
```
Write path:   store CRC32C with every chunk; the client's content-SHA is verified end-to-end.
Read path:    recompute CRC on read; on mismatch, treat the shard as lost and
              reconstruct from replicas/EC, then return the good bytes.
Scrubber:     background task continuously re-reads every chunk, recomputes the CRC,
              and flags corruption — proactively, before a user ever requests it.
Repair:       when a shard/replica is lost or corrupt, rebuild it from the surviving
              replicas (copy) or EC shards (read k, recompute). Throttle repair traffic
              so it doesn't saturate the network and hurt live reads.
```
**Repair speed *is* durability:** the faster you detect and rebuild, the smaller the window in which a second/third failure can cause loss. This is why repair is parallel (many nodes each rebuild a slice) and why you spread shards widely (more nodes share the rebuild load). A node that's slow to repair is a durability risk, not just a performance one.

### 6.5 Tiering, lifecycle & garbage collection at scale
- **Lifecycle rules** move objects between **storage classes** as they age: Standard (replicated, hot) → Infrequent-Access (EC, fewer nines of *availability* but same durability) → Archive/Glacier (offline-ish, EC, retrieval latency minutes–hours, cheapest media). A background job reads lifecycle config and rewrites placement, updating metadata.
- **Garbage collection** is non-trivial because deletes/overwrites/aborted-multipart leave orphan chunks. Use **reference-counting or mark-and-sweep**: periodically reconcile "chunks referenced by live metadata" against "chunks on disk," and reclaim the unreferenced ones after a grace period (so an in-flight read isn't pulled out from under). GC must be conservative — deleting a still-referenced chunk is data loss.
- **Compaction** of the small-object blob files: when packed objects inside a blob file are deleted, the file becomes sparse; compaction rewrites live needles into a fresh file and frees the old one (like LSM compaction).

---

## 7. Scaling, Bottlenecks & Failure Handling

**What breaks first?** Usually the **metadata plane**, then **network egress on hot objects**. The data plane scales almost linearly by adding disks; the index and the bandwidth are the squeeze points.

- **Metadata hot shard:** a bucket with sequential keys (e.g. log timestamps) or one viral prefix overloads a shard. Mitigate by auto-splitting hot partitions, hash-prefixing keys, and fronting reads with the metadata cache. This is the classic S3 "use random prefixes" issue, now largely automatic.
- **Hot object (one viral file):** push it to the **CDN** so origin sees almost nothing; cache the metadata; for huge sustained reads, replicate the object's chunks to extra nodes ("read replicas" of the data) to spread NIC load.
- **Storage node failure:** the placement service marks it down; reads transparently fall back to other replicas or reconstruct from EC; repair rebuilds the lost shards onto healthy nodes in the background. Because shards are spread across failure domains, a single node loss never blocks reads.
- **Rack / AZ failure:** failure-domain-aware placement guarantees no more than `m` shards (EC) or all-but-one replicas live in one AZ, so an AZ outage is survivable for both durability and read availability. Front-ends and metadata leaders fail over to surviving AZs.
- **Capacity growth / rebalancing:** adding nodes triggers the rebalancer to migrate a fraction of chunks onto them (CRUSH-style placement minimizes data movement on topology change — only the affected mappings move). Throttled to protect live traffic.
- **Thundering herd on a cold popular object:** request coalescing/single-flight at the front-end so one origin fetch serves the swarm; warm the CDN.
- **Multi-region DR:** asynchronous **cross-region replication** copies objects to a second region for geo-redundancy and low-latency local reads. It's async (eventually consistent across regions) to keep PUT latency low; a region loss has a small RPO for the most recent writes unless you opt into synchronous replication (at a latency cost).
- **Backpressure & isolation:** per-account rate limits and request prioritization so one tenant's burst can't starve others; the background plane (repair/GC/rebalance) is throttled and de-prioritized vs live requests.

---

## 8. Trade-offs & Alternatives

- **Replication vs erasure coding:** replication is fast and cheap to repair but 200% overhead; EC is 40% overhead and tolerates more failures but costs read/repair latency and network. **Chosen: hybrid** — replicate hot/small, EC warm/cold/large. Under a *latency-above-all* constraint, replicate everything; under a *cost-above-all* archival workload, EC everything with a wide stripe (e.g. 16+4).
- **Strong vs eventual consistency:** strong consistency (read-after-write) is now the expectation and we get it by making the metadata commit the single linearizable commit point. The cost is that a metadata write waits for quorum. If we needed even lower write latency and could tolerate stale reads, we'd relax to eventual consistency on overwrites — but that re-introduces the confusing "I just wrote it and GET 404s" behavior, so **chosen: strong**.
- **Metadata store: NewSQL/Spanner vs sharded RocksDB+Raft vs DynamoDB:** managed (DynamoDB/Spanner) reduces ops burden and gives global tables; self-built (RocksDB+Raft) gives control and cost efficiency at extreme scale. **Chosen: a sharded ordered-KV + consensus** design (build or buy depending on whether you're a cloud provider or a tenant). The key requirement either way is *ordered keys + strong consistency*.
- **Small-object packing vs one-file-per-object:** packing (Haystack-style) avoids filesystem inode/metadata overhead for billions of tiny files at the cost of compaction complexity. **Chosen: pack small objects, chunk large ones.**
- **Immutable data plane:** we never edit bytes in place; overwrites write new chunks and repoint metadata. This simplifies caching, integrity, and concurrency enormously, at the cost of GC complexity. **Chosen: immutability** — the simplification is worth the GC machinery.
- **CAP:** the request path favors **availability for reads** (serve from any replica/region, reconstruct from EC) while the metadata commit favors **consistency** (quorum write). So: **CP on the write/commit, AP-leaning on reads.**

**At 10x scale:** the metadata plane is the bottleneck — split into more, smaller shards; add a stronger caching tier; consider a hierarchical metadata design (bucket-level shards routing to key-level shards). Push more hot reads to the CDN/edge.

**At 100x (multi-exabyte, global):** it becomes a federated, multi-region system: metadata partitioned and replicated per region with a global routing layer; data plane is region-local with policy-driven cross-region replication; aggressive EC (wide stripes) for cost; tiering to tape/cold media for archives. The architecture (separate planes, EC, background repair) doesn't change — you scale each plane independently, which is exactly why the separation mattered from day one.

---

## Interview Q&A by Level

### 🟢 Basic
**Q. [Theory] Why split the system into a metadata plane and a data plane?**
A. They have opposite workloads. Metadata is tiny (~1 KB), enormously numerous (1 trillion records), and needs **strongly-consistent point + range lookups** (GET and LIST). Object data is huge (KB to TB), sequential, and needs raw **throughput and durability**. A single store can't optimize for both — so we use an ordered KV + consensus store for the index and a local-disk immutable blob store for the bytes. Separating them lets each scale independently: add disks for capacity, add metadata shards for index load.

**Q. [Theory] Why is the data plane immutable, and what does that buy you?**
A. We never edit a chunk in place — an overwrite writes new chunks and repoints the metadata. Immutability makes caching trivially safe (a chunk's content never changes, so a cached copy never goes stale), makes integrity checks simple (a chunk's checksum is fixed for life), and reduces every concurrency question to "who wins the linearizable metadata pointer swap." The cost is garbage collection of orphaned chunks, which we accept.

**Q. [Practical] How does a small-object PUT become durable, step by step?**
A. (1) Authenticate the signed request and check the bucket policy. (2) The allocator picks placement across failure domains. (3) The front-end streams the bytes to N replicas (or k+m EC shards) and verifies the content checksum. (4) Once a durability quorum acks, it commits the **metadata record** via a consensus write — that commit is the durability point. (5) Return `200` + ETag. Because data is durable *before* metadata is committed, any later GET that finds the metadata is guaranteed to find the bytes (read-after-write consistency).

### 🟡 Intermediate
**Q. [Theory] Erasure coding vs replication — when do you use each and why?**
A. Replication (3x) is simple, has low read latency (read one copy), and cheap repair (copy one shard) but costs 200% storage. Erasure coding, e.g. Reed–Solomon RS(10,4), costs only 40% overhead and survives 4 simultaneous failures, but reads must gather `k` shards and repair is network-heavy. So: **replicate hot/small/latency-sensitive data; erasure-code warm/cold/large data** to save cost. At 300 PB, EC instead of 3x saves roughly 480 PB of physical media.

**Q. [Practical] How do you implement LIST with a flat keyspace of billions of keys, sorted and paginated?**
A. The metadata store must keep keys **ordered** (LSM/B-tree, not a pure hash), so LIST is a range scan `[prefix, prefix+1)`. Pagination uses a **continuation token** = the last key returned (not offset/limit, which doesn't scale). A `delimiter` like `/` rolls keys up into `CommonPrefixes` so a flat namespace presents as folders. The trade-off: range-sharding keeps a prefix on one shard (good LIST locality, but sequential keys create a hot shard), while hash-sharding balances writes but scatters a prefix; modern systems auto-split hot partitions to get both.

**Q. [Practical] Two clients PUT the same key at the same time. What happens?**
A. Both write their own immutable chunks, then both attempt the metadata commit, which is serialized through the shard's consensus leader. **One commit wins and becomes current; the loser's chunks become orphans** reclaimed by GC. No corruption, because the only mutation is the linearizable pointer swap. With versioning enabled, both survive as distinct versions and the last-committed becomes "current."

**Q. [Theory] Why are large uploads done as multipart, and what consistency does completion give?**
A. A 5 TB single PUT that fails near the end would have to restart from zero, and you can't parallelize one stream well. Multipart uploads each part independently (parallel, individually retryable), and only the final `complete` call — a pure **metadata operation** that orders the parts — publishes the object atomically. The parts were already durable as they streamed, so completion is fast and the object appears all-or-nothing.

### 🟠 Advanced
**Q. [Theory] How do you actually achieve 11 nines of durability — what's the mechanism, not the marketing?**
A. Three things compound: (1) **Erasure coding / replication across distinct failure domains** so no single host/rack/AZ failure drops more than `m` shards. (2) **Continuous scrubbing** that re-reads every chunk, recomputes its CRC, and detects silent bit-rot proactively. (3) **Fast parallel repair** that rebuilds a lost shard in minutes by reading the survivors. Durability is `P(more than m failures within one repair window)`, and because the repair window is tiny and failures are spread across independent domains, that probability is astronomically small. **Repair speed is durability** — slow repair widens the window and erodes the nines.

**Q. [Practical] A single object goes viral — millions of GETs/s. How do you survive it?**
A. Layered. (1) The object is immutable, so it's perfectly cacheable — push it to the **CDN**, which absorbs essentially all traffic at the edge. (2) Cache its **metadata** so the index isn't hit per request. (3) For sustained origin load, **replicate its chunks onto additional storage nodes** to spread NIC/disk bandwidth (the object becomes wide). (4) **Request coalescing** on a cold miss so one origin fetch serves the herd. The CDN is the dominant lever — one cached blob absorbs the storm.

**Q. [Coding] Write pseudocode for the front-end GET path that tolerates a down storage node using erasure coding.**
A.
```python
def get_object(bucket, key, version=None):
    meta = metadata_cache.get((bucket, key, version)) \
           or metadata_store.read((bucket, key, version))   # strong read
    if meta is None or meta.is_delete_marker:
        return HTTP_404
    metadata_cache.put((bucket, key, version), meta)

    chunks = []
    for chunk in meta.chunk_ids:                       # objects are ordered chunks
        shards = read_available_shards(chunk.placement) # fetch shards in parallel
        for s in shards:
            if crc32c(s.data) != s.crc:                 # detect silent corruption
                s.mark_bad(); schedule_repair(s)        # background rebuild
        good = [s for s in shards if s.is_valid()]
        if len(good) >= chunk.k:                        # enough to reconstruct?
            data = (good[:chunk.k] if is_systematic(good)   # fast path: data shards present
                    else reed_solomon_decode(good, chunk.k, chunk.m))
            chunks.append(data)
        else:
            raise ServiceUnavailable("insufficient shards")  # extremely rare; alert
    return stream(concat(chunks), etag=meta.etag)
```
Key points: a strong metadata read, parallel shard fetch, **CRC verification on read** (treat a corrupt shard as missing and trigger repair), and **reconstruct from any `k` of `k+m`** shards so a down node is invisible to the caller. The "systematic fast path" reads the plain data shards directly and only decodes when one is missing.

**Q. [Theory] How does garbage collection work, and why is it dangerous?**
A. Deletes, overwrites, and aborted multipart uploads leave **orphan chunks** that no live metadata references. GC reconciles "chunks referenced by live metadata" vs "chunks on disk" (mark-and-sweep or reference counting) and reclaims unreferenced ones **after a grace period**. It's dangerous because reclaiming a chunk that's actually still referenced (or being read in-flight) is permanent **data loss** — so GC is conservative, grace-period-gated, and idempotent. This is the price of an immutable data plane.

### 🔴 Expert
**Q. [Theory] Redesign for true multi-region with low-latency local reads and disaster recovery. What are the consistency trade-offs?**
A. Each region runs a full stack (front-ends, metadata shards, storage). **Cross-region replication is asynchronous** by default to keep PUT latency low — meaning the second region is eventually consistent and a region loss has a small RPO for the most recent un-replicated writes. For strict zero-RPO you can offer **synchronous replication** on chosen buckets, paying cross-region write latency. A global routing layer directs clients to the nearest healthy region; metadata is partitioned and replicated per region with global ownership rules (e.g. a bucket has a home region for authoritative writes). The trade-off is the classic one: async = fast + small RPO risk; sync = safe + slow. Offer both per-bucket.

**Q. [Behavioral] Your team must hit a Q3 deadline for a new object store, but the durability/repair subsystem is the riskiest, least-finished piece. As the staff engineer, how do you handle it?**
A. I'd be explicit that **durability is non-negotiable** — shipping a blob store that can lose customer data is worse than shipping late, and that's the one corner we don't cut. So I'd de-risk scope, not durability: launch with **3x replication only** (simpler, well-understood, faster repair) and defer erasure coding to a fast-follow, since EC is a cost optimization, not a correctness one. I'd cut optional features (lifecycle tiering, cross-region) before touching repair/scrubbing. I'd communicate the trade explicitly to stakeholders: "We ship on time with higher storage cost; EC lands next quarter and pays it back." Then I'd put the strongest engineers on the scrubber/repair path, add chaos testing (kill nodes/disks in staging and verify zero data loss) as a launch gate, and write the durability runbook. The principle: protect the invariant that defines the product, flex everything else, and make the trade-off visible rather than silent.

**Q. [Theory] Why did S3 move from eventual to strong consistency, and what makes it hard at this scale?**
A. Eventual consistency forced confusing workarounds — a GET right after a PUT could 404, and LIST could omit a just-written key, so users built retry/poll hacks and external consistency layers. Strong consistency removes that entire class of bugs. It's hard because the index spans a petabyte across thousands of shards: every read must reflect the latest committed write **without** adding latency or sacrificing availability. The solution is to make the **metadata commit the single linearizable commit point** (consensus-backed), make data durable *before* that commit, and use careful caching that's invalidated/versioned so the cache can't serve a value older than a committed write. Doing this while keeping p99 < 100 ms across a trillion objects is the engineering feat.

**Q. [Coding] Sketch the placement function that maps an object to storage nodes across failure domains, minimizing data movement when the cluster changes.**
A.
```python
# CRUSH-style deterministic placement: no central lookup table, recomputable anywhere.
def place(object_id, cluster_map, replicas=3, ec=None):
    # cluster_map is a weighted hierarchy: region -> az -> rack -> host -> disk
    n = ec.k + ec.m if ec else replicas
    chosen, used_domains = [], set()
    for i in range(n):
        # rendezvous (HRW) hashing: pick the highest-scoring disk for this (object, i)
        candidates = sorted(
            cluster_map.disks,
            key=lambda d: hash((object_id, i, d.id)) * d.weight,
            reverse=True,
        )
        for d in candidates:
            # enforce failure-domain spread: don't reuse a rack/az already chosen
            if d.failure_domain not in used_domains and d.has_capacity():
                chosen.append(d)
                used_domains.add(d.failure_domain)
                break
    return chosen
```
Why this shape: **rendezvous/CRUSH hashing is deterministic** (any node recomputes placement from the cluster map — no giant lookup table, no SPOF), it spreads shards across **distinct failure domains** (so a rack/AZ loss can't take out more than `m` shards), and when a disk is added/removed only the objects that hashed to it move — minimizing rebalance traffic. Weights let you bias toward emptier/larger disks for even fill.

---

*Key takeaway: an object store is a masterclass in durability engineering and plane separation — split the strongly-consistent metadata index from the immutable, throughput-oriented data plane; reach 11 nines with erasure coding + failure-domain spreading + continuous scrub-and-repair; and make every overwrite a linearizable metadata pointer swap so concurrency and read-after-write consistency fall out cleanly.*
