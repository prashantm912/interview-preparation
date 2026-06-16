# Design a File Storage & Sync Service (Dropbox / Google Drive)

> A worked, interview-grade design of a cloud file storage and synchronization service: store users' files durably, sync every change across all their devices in near real time, and let them share folders — all while transferring as few bytes as possible over the wire.

[← Back to master index](../../README.md) · [← System Design index](../README.md)

---

## 1. Requirements

A file-sync service looks like "upload to S3 with a nice UI," but the interesting engineering lives in the **sync protocol**: how a change made on one laptop reaches three other devices in seconds, without re-uploading whole files, without losing edits when two devices change the same file offline, and without melting the metadata database. Scope the problem before drawing anything.

### Functional requirements
- **Upload / download**: store a file from any device; retrieve the latest version on any device.
- **Sync**: a local change (create / edit / rename / move / delete) propagates to all the user's other devices in near real time; offline changes reconcile on reconnect.
- **Versioning**: keep file history; let a user restore a previous version or an accidentally deleted file (e.g. 30-day trash).
- **Sharing**: share a file or folder with other users (view / edit), or via a public link.
- **Selective sync**: a device may sync only a subset of folders (a phone won't mirror a 2 TB account).
- **Conflict handling**: two devices edit the same file while offline — resolve without silent data loss.
- **Large files**: support multi-GB files with resumable, chunked transfer.

### Non-functional requirements
- **Scale**: 600M registered users, 100M DAU; avg 50 GB stored/user; ~1B file mutations/day; read:write (download:upload) ≈ **5:1** at the byte level but mutation events are write-skewed by sync fan-out.
- **Latency**: sync propagation p99 **< 1 s** for small files once uploaded; metadata operations (list folder, stat) p99 **< 100 ms**. Upload/download throughput limited by the client's link, not us.
- **Availability**: **99.99%** for metadata + notification (the "is anything new?" path); **99.999999999% (11 nines) durability** for file bytes — losing a user's only copy of a file is unforgivable.
- **Consistency**: metadata must be **read-your-writes** per device (you should see your own change immediately) and **eventually consistent** across devices, with **monotonic** progress (a device never goes backwards in sync state). File content is immutable per version → no content consistency problem once a version exists.
- **Security**: encryption in transit (TLS) and at rest (AES-256); per-file access control; signed, expiring URLs for direct blob transfer; tenant isolation.
- **Efficiency**: minimize bytes on the wire — never re-upload an unchanged 4 GB file because one block changed.

### Clarifying questions a strong candidate asks
1. **What's the dominant file profile?** Many tiny files (source code, docs) vs. few huge files (video, datasets)? This swings the metadata-vs-bytes balance entirely.
2. **How real-time must sync be?** Sub-second "live" sync, or "within a minute" is fine? Drives the notification architecture.
3. **Do we need block-level delta sync** (re-upload only changed blocks), or is whole-file transfer acceptable? Dropbox's whole differentiator was deltas.
4. **What's the conflict model?** Last-writer-wins, keep-both-copies, or operational-transform/CRDT merge? Google Docs (collaborative editing) is a *different* problem than Dropbox (file sync).
5. **Global cross-region sharing**, or single-region accounts? Affects where metadata lives and replication strategy.
6. **Deduplication scope?** Per-user, or global cross-user dedup (saves enormous storage but raises privacy/security questions)?
7. **Web/mobile clients too, or desktop daemon only?** Web can't watch the filesystem; it polls or uses a notification channel.

> The delta-sync vs whole-file question is the crux. If the interviewer says "whole-file is fine," this collapses toward a thin layer over object storage. If they want block-level sync (the realistic ask for Dropbox/Drive), the design centers on content-addressed chunking, a chunk store, and a metadata DB that tracks which chunks make up which version.

---

## 2. Capacity Estimation

Real numbers, shown long-hand. Assume a 5-year horizon.

### Storage (the headline number)
```
600,000,000 users × 50 GB avg stored = 3.0 × 10^10 GB = 3.0 × 10^7 TB = 30 Exabytes logical
```
That's the *logical* size. Two big reducers apply before we provision raw disk:
```
Cross-user dedup (identical chunks shared, e.g. the same PDF/app installer): ~30% saving
Compression of compressible chunks:                                         ~10% saving
Effective stored after dedup+compression: 30 EB × 0.7 × 0.9 ≈ 18.9 EB
With erasure coding overhead (1.4x, see Deep Dive) instead of 3x replication:
   18.9 EB × 1.4 ≈ 26.5 EB raw on disk
```
Erasure coding vs 3x replication saves us `(3.0 − 1.4)/3.0 ≈ 53%` of raw disk versus naive triple replication — at exabyte scale that's the difference between viable and bankrupt.

### Mutation / write QPS
```
1,000,000,000 file mutations/day ÷ 86,400 s/day ≈ 11,574 mutations/sec  (~11.6K WPS avg)
Peak factor ~4x (workday clustering across time zones) → ~46,000 mutations/sec peak
```
Each mutation is a **metadata write** (cheap, small) plus possibly a **chunk upload** (only the changed chunks). Most mutations touch few chunks.

### Notification / sync QPS (the fan-out load)
Each mutation must notify the user's *other* online devices. Avg ~3 devices/user, ~1 offline:
```
~46,000 mutations/sec × ~2 other online devices ≈ 92,000 notifications/sec peak
```
Notifications are tiny ("namespace N changed, cursor C") — the heavy lifting is keeping ~100M long-lived connections open (similar to the chat-system connection tier).

### Download (read) bandwidth
```
Assume 5:1 download:upload bytes. Upload bytes ≈ chunk uploads.
If avg changed-chunk upload ≈ 46,000 mutations/sec × ~2 changed 4 MB blocks (worst case is much less,
   but bound it): in practice most edits touch <1 block. Use a blended 200 KB/mutation:
Upload bandwidth ≈ 46,000 × 200 KB ≈ 9.2 GB/s
Download bandwidth ≈ 5 × 9.2 GB/s ≈ 46 GB/s   → served from object store + CDN, not metadata tier
```
Bytes flow **directly between client and the blob store** (via pre-signed URLs / a thin transfer service), *not* through the metadata service. The metadata tier only ever handles small JSON.

### Metadata store sizing
```
File metadata rows: 600M users × ~10,000 files/user (50 GB ÷ ~5 MB avg) = 6 × 10^12 rows
Per-row metadata ≈ 500 bytes (path, size, mtime, chunk list refs, version, ACL ref)
Raw metadata: 6e12 × 500 B = 3 × 10^15 B = 3 PB  → ×3 replication ≈ 9 PB
```
3 PB of metadata is large but tractable for a sharded metadata store; it dwarfs the URL-shortener but is microscopic next to the 26 EB of bytes. **Metadata and bytes scale on completely different axes** — the central architectural fact.

### Notification connection memory
```
100M DAU, say 60% have a device connected at peak = 60M concurrent long-poll/WS connections
Per-connection state ≈ 10 KB  →  60M × 10 KB = 600 GB across the notification fleet
Connections per node ≈ 100,000 → ~600 notification servers (run ~900 for headroom)
```

### Metadata cache
```
Hot working set: active users' folder trees. 100M DAU × ~50 KB hot metadata ≈ 5 TB
Cache the top slice in Redis (~1–2 TB sharded) for sub-100ms folder listings.
```

---

## 3. API Design

REST/gRPC over HTTPS for metadata and transfer orchestration; a **long-poll or WebSocket notification channel** for "something changed"; **direct-to-blob** transfer via pre-signed URLs so bytes bypass our app servers.

```http
# ---- Metadata: list & stat ----
GET /v1/namespaces/{ns}/files?path=/Projects&cursor=<opaque>
  → 200 { entries:[{path, type, size, version_id, content_hash, modified_at, chunks:[...]}],
          cursor:"<next>", has_more:false }

GET /v1/files/{file_id}                       # stat a single file/version
  → 200 { file_id, path, version_id, size, content_hash, block_list:[h1,h2,...], modified_at }

# ---- Upload (block-level, content-addressed) ----
# 1. Client chunks the file locally, hashes each block, asks which blocks are missing
POST /v1/blocks:check
  { "block_hashes": ["sha256:ab..", "sha256:cd..", ...] }
  → 200 { "missing": ["sha256:cd.."] }        # we already have the rest (dedup!)

# 2. Upload only the missing blocks, directly to the blob store via a signed URL
POST /v1/blocks:initUpload  { "hash":"sha256:cd..", "size": 4194304 }
  → 200 { "upload_url":"https://blob.../signed?...", "expires_in":900 }
PUT  <upload_url>            (raw block bytes, client → blob store, not through us)

# 3. Commit a new file version by referencing the block list (a "manifest")
POST /v1/files:commit
  { "path":"/Projects/a.psd", "block_list":["sha256:ab..","sha256:cd.."],
    "size": 8388608, "base_version_id":"v17", "mtime": 1718500000 }
  → 201 { "file_id":"f_91", "version_id":"v18" }
  → 409 Conflict { "server_version_id":"v19", ... }   # base_version_id is stale → conflict

# ---- Download ----
GET /v1/files/{file_id}/download?version_id=v18
  → 200 { "block_list":[{ "hash":"sha256:ab..","download_url":"https://cdn.../signed?..." }, ...] }
  # client fetches each block from CDN/blob store, reassembles locally (skips blocks it already has)

# ---- Sync notification (the heartbeat of the system) ----
GET /v1/notify/longpoll?namespaces=<ns1,ns2>&cursor=<last_cursor>   # held open up to ~480s
  → 200 { "changed": ["ns1"], "cursor":"<new>" }   # returns the instant ns1 changes, else times out
# client then calls list-files with its cursor to pull the actual delta

# ---- Sharing ----
POST /v1/shares  { "file_id":"f_91", "grantee":"user:bob", "role":"editor" }
  → 201 { share_id }
POST /v1/shares:link { "file_id":"f_91", "role":"viewer", "expires_at":"2026-12-31T00:00:00Z" }
  → 201 { "public_url":"https://share.../s/AbC123" }

# ---- Versioning ----
GET  /v1/files/{file_id}/versions          → 200 { versions:[{version_id, size, modified_at}, ...] }
POST /v1/files/{file_id}/restore { "version_id":"v12" }  → 201 { version_id:"v20" }  # restore = new version
```

**Design notes:** the upload is a **3-step content-addressed handshake** — *check which blocks exist*, *upload only the missing ones*, *commit a manifest*. `commit` carries `base_version_id` for **optimistic concurrency**: if the server has moved on, it returns `409` and the client reconciles (Deep Dive 4). Bytes never traverse our app tier — clients `PUT`/`GET` blocks directly against the blob store via short-lived signed URLs.

---

## 4. Data Model

The system has **two stores with opposite shapes**: a metadata store (lots of small, structured, transactional rows that must be queried by path/namespace) and a blob store (a flat, immutable, content-addressed sea of bytes).

### Metadata store — sharded relational (or distributed SQL)
Metadata needs **transactions** (committing a version atomically updates the file row, the block-mapping, and the namespace cursor), **secondary access by path**, and **strong read-your-writes** per namespace. That points to a **relational engine, sharded by namespace** (Dropbox famously runs sharded MySQL + an internal metadata service "Edgestore"; Google uses Spanner). A distributed SQL DB (Spanner/CockroachDB) is the modern clean choice; sharded MySQL/Postgres is the battle-tested one.

```sql
-- A "namespace" = a sync root: a user's root, or a shared folder. Sharding & ACL unit.
namespaces(ns_id PK, owner_id, type /*root|shared*/, created_at)

-- Files (current state). Sharded by ns_id.
files(
  file_id        PK,
  ns_id          FK,            -- shard key
  path           TEXT,          -- "/Projects/a.psd"  (indexed: (ns_id, path))
  current_ver    BIGINT,        -- → file_versions.version_id
  is_dir         BOOL,
  is_deleted     BOOL,          -- soft delete (trash); hard-deleted after retention
  modified_at    TIMESTAMP,
  UNIQUE (ns_id, path)          -- enforces no two files at same path
)

-- Immutable version history. Each commit = one new row.
file_versions(
  version_id     PK,            -- monotonic per namespace
  file_id        FK,
  ns_id          FK,
  size           BIGINT,
  content_hash   CHAR(64),      -- hash of the whole assembled file (whole-file dedup)
  created_at     TIMESTAMP,
  created_by     user_id
)

-- The manifest: ordered blocks that make up a version. THIS is delta sync's core table.
version_blocks(
  version_id     FK,
  block_index    INT,           -- order within file
  block_hash     CHAR(64),      -- → blocks.block_hash (content address)
  PRIMARY KEY (version_id, block_index)
)

-- Global block index (content-addressed). NOT sharded by user → enables cross-user dedup.
blocks(
  block_hash     CHAR(64) PK,   -- sha256 of block contents
  size           INT,
  ref_count      BIGINT,        -- for garbage collection
  storage_locator TEXT          -- where in the blob store (e.g. volume + offset / object key)
)

-- Per-namespace monotonic change cursor — drives sync. Every mutation bumps it.
namespace_cursor(ns_id PK, cursor BIGINT)   -- "journal sequence number" for this namespace

-- Sharing / ACL
shares(share_id PK, ns_id, grantee_id, role /*viewer|editor*/, created_at)
public_links(token PK, file_id, role, expires_at)
```

### Why this split
| Concern | Metadata store | Blob store |
|---|---|---|
| Shape | Small structured rows, transactions, path queries | Huge opaque immutable byte blocks |
| Engine | Sharded SQL / Spanner / Edgestore | Object store (S3/GCS) or custom (Dropbox "Magic Pocket") |
| Access | By `(ns_id, path)` / cursor | By `block_hash` (content address) |
| Consistency | Strong, transactional | Immutable → no consistency problem |
| Scale axis | ~3 PB, query-bound | ~26 EB, durability/cost-bound |

### Why content-addressed blocks
Naming a block by `sha256(contents)` makes blocks **immutable and self-deduplicating**: if two users (or two versions) contain the same 4 MB block, it's stored once and both manifests point at the same `block_hash`. New versions only add manifest rows + the genuinely-new blocks. This is what makes delta sync and dedup *the same mechanism*.

### Blob store choice
At exabyte scale, vanilla S3 is expensive; Dropbox built **Magic Pocket** (custom, erasure-coded, on bare metal) for exactly this reason. The interview answer: **object store with erasure coding** (cheaper durability than 3x replication), `block_hash` as the key, behind a CDN for downloads.

---

## 5. High-Level Architecture

```
            ┌──────────────────────────────────────────────────────────┐
   Desktop  │  Local sync client: watches FS, chunks files, hashes,     │
   client   │  maintains a local DB of (path → block_list, version)     │
            └───────────┬───────────────────────────────┬──────────────┘
                        │ metadata (small JSON)          │ bytes (blocks)
                        ▼                                ▼
              ┌──────────────────┐            ┌─────────────────────────┐
              │  Load Balancer    │            │  Load Balancer (transfer)│
              └─────────┬────────┘            └────────────┬────────────┘
                        │                                  │ signed-URL PUT/GET
          ┌─────────────▼──────────────┐        ┌──────────▼──────────────┐
          │   Metadata Service          │        │   Block / Transfer Svc   │
          │  (stateless, sharded by ns) │        │ (issues signed URLs,     │
          │  list / commit / version    │        │  validates block hashes) │
          └───┬───────────┬─────────────┘        └──────────┬──────────────┘
              │           │ bump cursor                     │
   ┌──────────▼──┐   ┌────▼──────────────┐        ┌─────────▼──────────────┐
   │ Metadata DB │   │ namespace_cursor + │        │  Blob Store (S3 /       │
   │ (sharded    │   │ change journal      │        │  Magic Pocket),         │
   │  SQL/Spanner│   └────┬───────────────┘        │  erasure-coded,         │
   │  + Redis    │        │ "ns changed"           │  content-addressed      │
   │  cache)     │        ▼                        │  blocks  → CDN          │
   └─────────────┘   ┌─────────────────────┐       └─────────────────────────┘
                     │  Notification Service│
                     │  (long-poll / WS,    │◄─── pub/sub of namespace cursor bumps
                     │  ~60M live conns)    │
                     └─────────┬────────────┘
                               │ "namespace N changed, new cursor C"
            ┌──────────────────▼───────────────────┐
            │  Other devices of the same user / of  │
            │  shared-folder members → pull delta   │
            └───────────────────────────────────────┘
            ┌───────────────────────────────────────┐
            │  Async: GC service (ref_count→0 blocks),│
            │  versioning/trash expiry, virus scan,   │
            │  thumbnailing — all off the hot path    │
            └───────────────────────────────────────┘
```

### Component walkthrough
- **Sync client (the smart part)** — watches the local filesystem, splits files into blocks, hashes them, keeps a local DB of `path → block_list/version`. It computes the *delta* locally so it only ever asks the server about changed blocks. On startup or notification it pulls the namespace delta and applies it.
- **Metadata service** (stateless, sharded by `ns_id`) — handles `list`, `stat`, `commit`. On commit it transactionally writes the new version + manifest, bumps `namespace_cursor`, and publishes a "ns changed" event. The only authority on "what's the current state."
- **Block / transfer service** — issues short-lived signed URLs and verifies uploaded blocks match their claimed hash (so a client can't poison content addressing). It does *not* proxy bytes; clients talk to the blob store directly.
- **Blob store** — immutable, content-addressed, erasure-coded blocks; the durability workhorse. Downloads are fronted by a CDN for popular/shared content.
- **Notification service** — holds the ~60M long-poll/WebSocket connections, subscribes to namespace cursor bumps, and pings the right devices "namespace N changed." It carries *no file data* — just a nudge to go pull the delta.
- **Metadata DB + Redis** — sharded SQL/Spanner for truth; Redis caches hot folder trees and cursors for sub-100ms listings.
- **Async services** — garbage collection (delete blocks whose `ref_count` hit 0 after retention), trash/version expiry, malware scanning, thumbnail generation. All decoupled from the upload latency path.

**The request flow for an edit:** client detects a saved file → re-chunks it → `blocks:check` (most blocks already exist) → uploads only changed blocks via signed URL → `files:commit` with the new manifest and `base_version_id` → metadata service commits, bumps the cursor, publishes the change → notification service pings the user's other devices → those devices pull the delta and download just the changed blocks.

---

## 6. Deep Dives

### 6.1 Block-level (delta) sync & deduplication
The whole point of Dropbox over "zip-and-upload-to-S3" is **never moving bytes you don't have to**.

**Chunking.** Split each file into blocks (Dropbox uses up to **4 MB** blocks). Two schemes:
- **Fixed-size blocks** (every 4 MB): trivial, but a single byte *inserted* at the front shifts every subsequent block boundary → every block hash changes → you re-upload the whole file. Bad for "edit in the middle" cases.
- **Content-defined chunking (CDC, rolling hash / Rabin fingerprint)**: boundaries are chosen based on content, so inserting bytes only changes the *local* chunk; the rest keep their hashes. This is what makes "I added a paragraph to a 1 GB log" cheap. More CPU, far better delta locality.

**The dedup/delta mechanism is one and the same:**
```
1. Client chunks file, computes block hashes  H = [h1, h2, h3, h4]
2. blocks:check(H) → server replies missing = [h3]   (h1,h2,h4 already in global block index)
3. Client uploads only block h3.
4. commit(block_list = H). New version row + 4 manifest rows; only 1 new block stored.
```
- **Cross-user dedup**: because blocks are global and content-addressed, the millionth user uploading the same OS installer stores *zero* new bytes — the `blocks:check` returns "all present."
- **Privacy caveat**: global dedup leaks "does block X exist?" which can confirm a user possesses a known file (a side channel). Mitigations: dedup only *within a user/team*, or add per-user keying. State this trade-off explicitly — it's a favorite follow-up.
- **GC via ref-counting**: each manifest reference bumps `ref_count`; deleting versions decrements it; a block with `ref_count = 0` past the retention window is garbage-collected. Must be done carefully (a concurrent commit could re-reference a block mid-GC) — use a mark-and-sweep with a grace period, or a transactional ref-count.

### 6.2 The sync/notification protocol — how a change reaches other devices in < 1s
Polling 100M devices every second would be `100M req/s` of wasted load. Instead, **long-poll (or WebSocket) + a per-namespace cursor**:

```
Each namespace has a monotonic cursor (journal sequence number).
Device tells server "I'm at cursor C for namespace N" and HANGS a long-poll request.
   - If N's cursor is already > C: server returns immediately ("changed").
   - Else: server PARKS the request (up to ~8 min) and returns the instant N's cursor bumps.
On "changed", the device calls list-files(ns=N, since=C) to pull the actual delta,
   then advances its cursor.
```
- **Why a cursor, not push-the-data?** The notification is tiny and idempotent; the device pulls authoritative state itself. A device that was offline simply pulls everything since its stored cursor — **the same code path handles "live update" and "catch up after a week offline."** This is the elegant core of file sync.
- **Monotonic cursor** guarantees a device never regresses and can always resume exactly where it left off (durable resumption).
- **Notification fan-out**: a commit publishes the bumped cursor to a pub/sub keyed by `ns_id`; the notification servers holding that namespace's subscribers wake the parked long-polls. Shared folders fan out to all members' devices.
- **Long-poll vs WebSocket**: long-poll is simpler, survives hostile proxies/firewalls (it's just HTTP), and is what Dropbox historically used; WebSocket is lower-overhead at very high connection counts. Either way the *protocol* (cursor-based pull) is identical.

### 6.3 Metadata storage & sharding
Metadata is the **query-bound, transactional** half, and it's where the system breaks first under naive design.
- **Shard by `namespace`**, not by user or by file. A namespace (a user's root or a shared folder) is the natural transactional and access boundary: a folder listing, a commit, and a cursor bump all stay within one shard → no cross-shard transactions on the hot path. (Dropbox's lesson: don't shard by `user_id` if shared folders span users; shard by the sync root.)
- **Shared folders** are their own namespace, mounted into each member's tree. A change to a shared folder bumps *that namespace's* cursor once, and all members' devices (watching that ns) get notified — no per-member metadata duplication.
- **Path moves are tricky**: moving `/A` to `/B` with 100k descendants shouldn't rewrite 100k rows. Store paths via **parent-pointer (directory inode) model** so a move updates one row (the moved node's parent), not the whole subtree; reconstruct full paths by walking parents (cached).
- **Hot shard mitigation**: a hyperactive team folder concentrates writes on one shard. Mitigate by keeping commits cheap (metadata only — bytes are elsewhere), caching reads in Redis, and, for pathological mega-namespaces, sub-sharding the change journal.
- **Why not pure NoSQL?** We need atomic multi-row commits (version + manifest + cursor) and `(ns, path)` uniqueness. NoSQL can do this with effort, but distributed SQL (Spanner/CockroachDB) or sharded MySQL gives transactions natively. The *blocks* table is the one piece that's effectively a giant KV index and can live in a NoSQL/KV store.

### 6.4 Conflict resolution
Two devices edit the same file while one is offline; both commit against `base_version_id = v17`.
```
Device A: commit(path=/a.psd, base=v17) → server creates v18, cursor bumps.
Device B (was offline): commit(path=/a.psd, base=v17) → server sees current is v18, not v17 → 409 Conflict.
```
**Resolution strategies (state the model up front):**
- **File sync (Dropbox/Drive) — keep both, never silently lose data.** On `409`, the client creates a **conflicted copy**: `a (Bob's conflicted copy 2026-06-16).psd`. Both versions survive; the human decides. This is the safe default because the server can't merge arbitrary binary files (a `.psd` or `.zip` has no meaningful merge).
- **Last-writer-wins (LWW)**: simplest, but silently discards an edit — unacceptable for a storage product. Sometimes used for *metadata* like rename ordering, never for content.
- **Operational Transform / CRDT (Google Docs)**: for *collaborative editing of structured documents* you merge at the operation level (insert/delete on a sequence) so concurrent edits converge without conflicts. This is a **fundamentally different product** from file sync — call this out: Dropbox syncs opaque files (conflicted-copy), Google Docs co-edits a structured document (OT/CRDT). Mixing them up is a classic interview stumble.
- **Vector clocks / version vectors** can detect causality (was B's edit aware of A's?) more precisely than a single `base_version_id`, useful for richer multi-device reasoning.

### 6.5 Durability with erasure coding (vs replication)
11 nines of durability over 26 EB, affordably:
```
3x replication: store 3 full copies → 3.0x raw overhead, survives 2 simultaneous copy losses.
Erasure coding (e.g. Reed-Solomon 6+3): split a block into 6 data + 3 parity = 9 fragments
   spread across 9 failure domains. Any 6 of 9 reconstruct the block.
   Raw overhead = 9/6 = 1.5x  (Dropbox uses schemes around ~1.4x), tolerates 3 fragment losses.
```
- **Why EC wins at this scale**: same-or-better fault tolerance at ~1.4–1.5x overhead vs 3x — roughly **half the disk** for exabytes of cold-ish data. The cost is **CPU + network on reconstruction** (a degraded read must fetch 6 fragments and recompute), so EC suits large, infrequently-rewritten blocks (exactly our immutable content-addressed blocks) and not tiny hot metadata (which stays replicated).
- **Geo-durability**: spread fragments across racks/AZs (and across regions for the most critical data) so no single domain loss is unrecoverable. Background scrubbers continuously verify checksums and repair lost fragments before a second failure compounds.
- **Immutability is the enabler**: because blocks never change after write, EC fragments never need re-encoding on update — only on the rare deletion/GC.

---

## 7. Scaling, Bottlenecks & Failure Handling

**What breaks first?** Usually the **metadata tier and the notification fan-out**, not the bytes — the blob store and CDN scale almost linearly by adding capacity, but metadata is transactional/query-bound and notifications are connection-bound. Order of stress: (1) metadata hot shards & cross-shard ops, (2) notification connection count + fan-out for big shared folders, (3) GC/ref-count contention, (4) blob store throughput, (5) signed-URL issuance.

- **Stateless tiers scale horizontally**: metadata service, block/transfer service, and (mostly) notification logic sit behind autoscaling groups. Bytes are direct-to-blob, so app servers stay thin.
- **Metadata sharding**: by `namespace`; add shards and rebalance to add write capacity. Keep commits metadata-only so they stay tiny. Cache reads aggressively in Redis.
- **Notification fan-out for huge shared folders**: a 50,000-member team folder changing every few seconds is a fan-out storm. Mitigate with **coalescing** (a device that's behind just needs *one* "you're behind, pull" nudge regardless of how many changes happened) and per-namespace rate-limited notifications — the cursor model means we never need to send N notifications for N changes, just "current cursor is now C."
- **Replication & DR**: metadata replicated (sync within region, async cross-region); blob fragments erasure-coded across AZs/regions; continuous checksum scrubbing repairs silent corruption. Snapshots/backups of metadata with point-in-time recovery (you must be able to restore a user's namespace state).
- **Resumable transfers**: chunked uploads/downloads resume per-block, so a dropped connection on a 4 GB file re-sends only the in-flight block, not the whole file. Clients verify each block's hash on arrival.
- **Rate limiting & abuse**: per-user/per-team quotas and request limits protect shards; signed URLs expire quickly to prevent leakage; uploaded blocks are hash-verified to prevent content-address poisoning; malware scanning runs async before sharing.
- **Circuit breakers & graceful degradation**: if notification is down, sync degrades to **periodic polling** (slower, but correct — the cursor still works). If the blob store is degraded in one region, serve from a replica region / CDN. Metadata read replicas absorb load if the primary is hot; never ack a commit before the metadata write is durable.
- **Thundering herd on reconnect**: after a network blip, millions of long-polls reconnect at once. Use **jittered backoff** and stagger long-poll timeouts so reconnections spread over time.

**GC safety**: ref-count decrement and block deletion must not race a concurrent commit re-referencing the block. Use a mark-sweep with a grace window (only collect blocks whose `ref_count` has been 0 for longer than the longest in-flight commit) or a transactional decrement-and-check.

---

## 8. Trade-offs & Alternatives

- **Block-level delta sync vs whole-file upload**: deltas (content-addressed chunks) save enormous bandwidth and enable dedup, at the cost of client CPU (chunking/hashing) and a more complex commit protocol. **Chosen: block-level** — it's the product's reason to exist. Whole-file is acceptable only for a simple "backup to cloud" product.
- **Fixed vs content-defined chunking**: CDC handles inserts gracefully (only local chunks change) but costs more CPU; fixed-size is simpler but suffers boundary-shift on inserts. **Chosen: CDC** for general files; fixed-size is fine if files are mostly append-only or replaced wholesale.
- **Erasure coding vs replication**: EC ~halves raw storage at equal/better durability but costs CPU/network on reconstruction. **Chosen: EC** for the immutable block store; keep small hot metadata replicated.
- **Sharded SQL/Spanner vs NoSQL for metadata**: we need atomic multi-row commits and path uniqueness → relational/distributed-SQL. **Chosen: shard-by-namespace relational**; the `blocks` index can be KV/NoSQL.
- **Long-poll vs WebSocket vs polling**: long-poll is firewall-friendly and simple (Dropbox's historical choice); WebSocket is leaner at extreme connection counts; periodic polling is the degraded fallback. **Chosen: long-poll/WS with cursor-based pull**; the cursor makes all three interchangeable in correctness.
- **Conflict model**: conflicted-copy (keep both) for opaque file sync vs OT/CRDT for collaborative document editing. **Chosen: conflicted-copy** for file sync; OT/CRDT only if the product is co-editing structured docs.
- **Global vs per-user dedup**: global maximizes storage savings but opens a "does this block exist?" side channel. **Chosen: scope dedup to user/team by default**, accept somewhat less saving for privacy.

**At 10x scale**: more metadata shards + read replicas, regionally-partitioned notification fleets, and tiered blob storage (move cold versions to colder/cheaper EC tiers or archival storage like Glacier). Push more downloads to the CDN edge.

**At 100x scale**: this becomes a **cell-based** system — self-contained units (metadata shards + notification + blob region) each owning a slice of namespaces, with users/teams pinned to a home cell and a thin cross-cell router for sharing across cells. The custom blob store (à la Magic Pocket) becomes mandatory — S3 economics don't survive multiple exabytes. Notification coalescing and aggressive cold-tiering dominate the cost model.

---

## Interview Q&A by Level

### 🟢 Basic
**Q. [Theory] Why don't file bytes flow through your application servers?**
A: Bytes are huge (exabytes of traffic) and would make the app tier a bandwidth bottleneck and cost center. Instead the metadata/transfer service issues **short-lived signed URLs**, and clients `PUT`/`GET` blocks **directly against the blob store / CDN**. Our servers only ever handle small JSON (manifests, cursors), so they scale on request rate, not byte throughput. The blob store and CDN scale bandwidth independently.

**Q. [Theory] What's the difference between the metadata store and the blob store, and why two systems?**
A: They have opposite shapes. Metadata is small, structured, transactional rows queried by path/namespace → a sharded relational/distributed-SQL store with strong consistency. Blobs are huge, opaque, immutable byte blocks addressed by content hash → an object/erasure-coded store optimized for durability and cost. They scale on different axes (metadata ~3 PB query-bound; blobs ~26 EB durability-bound), so coupling them would force the wrong trade-off on one of them.

**Q. [Practical] How does a small edit to a 4 GB file avoid re-uploading 4 GB?**
A: The client splits the file into blocks, hashes each, and asks the server which blocks are missing (`blocks:check`). Since only the edited region's block(s) changed, the server already has the rest, so the client uploads just the changed block(s) and commits a new manifest referencing the full block list. Content-addressed, immutable blocks mean unchanged blocks are reused automatically.

### 🟡 Intermediate
**Q. [Theory] Explain the sync/notification protocol. How does a change reach another device in under a second without polling constantly?**
A: Each namespace has a monotonic **cursor**. A device tells the server its current cursor and hangs a **long-poll** (or WebSocket subscription). The server parks the request and returns the instant the namespace's cursor advances (on a commit elsewhere), or times out after ~8 minutes. On "changed," the device pulls the delta with `list-files(since=cursor)` and advances its cursor. The same code path handles live updates and catching up after being offline for a week.

**Q. [Theory] Why shard metadata by namespace instead of by user or by file?**
A: A namespace (a user's root or a shared folder) is the natural transactional and access boundary — folder listings, commits, and cursor bumps all stay within one shard, avoiding cross-shard transactions on the hot path. Sharding by user breaks down when a shared folder spans many users; sharding by file scatters a single folder listing across shards. Namespace sharding keeps the common operations single-shard.

**Q. [Theory] How does deduplication work, and what's the catch?**
A: Blocks are named by `sha256(contents)` in a global index, so identical blocks (across versions or across users) are stored once; uploaders of an existing block store zero new bytes. The catch is a **privacy side channel**: `blocks:check` reveals whether a given block already exists, which can confirm a user possesses a known file. Mitigate by scoping dedup to a user/team or adding per-user keying, trading some storage savings for privacy.

**Q. [Practical] Two devices edit the same file offline. What happens?**
A: Both commit against the same `base_version_id`. The first wins and advances the version; the second gets a `409 Conflict` because its base is now stale. The client then creates a **conflicted copy** (`a (Device's conflicted copy DATE).ext`) so both edits survive and the user resolves it. We never silently last-writer-wins, because we can't meaningfully merge arbitrary binary files.

### 🟠 Advanced
**Q. [Theory] Fixed-size vs content-defined chunking — when does it matter?**
A: Fixed-size blocks (every 4 MB) are simple but suffer **boundary shift**: inserting one byte at the front shifts every subsequent boundary, changing all block hashes and forcing a full re-upload. **Content-defined chunking** (rolling hash / Rabin fingerprint) picks boundaries from content, so an insert only changes the local chunk and the rest keep their hashes. CDC matters whenever files are edited in place (logs, documents, VM images); fixed-size is fine when files are append-only or replaced wholesale.

**Q. [Theory] Why erasure coding over 3x replication, and where would you NOT use it?**
A: Reed-Solomon (e.g. 6+3) gives equal-or-better fault tolerance at ~1.4–1.5x raw overhead versus 3.0x for triple replication — roughly half the disk over exabytes. The cost is CPU/network on reconstruction (a degraded read fetches 6 fragments and recomputes), so EC suits large, immutable, infrequently-read-degraded blocks — exactly our content-addressed block store. I would *not* EC tiny, hot, latency-critical metadata; that stays replicated for cheap fast reads.

**Q. [Coding] Write the client-side commit-with-conflict logic and the block-dedup check.**
A:
```python
def sync_local_change(file, server, local_db):
    # 1. Chunk locally with content-defined chunking; hash each block.
    blocks = content_defined_chunks(file.bytes)          # [(hash, data), ...]
    hashes = [h for (h, _) in blocks]

    # 2. Ask which blocks the server is missing (dedup: skip ones it already has).
    missing = set(server.blocks_check(hashes))            # POST /v1/blocks:check

    # 3. Upload only the missing blocks, directly to the blob store via signed URLs.
    for (h, data) in blocks:
        if h in missing:
            url = server.init_block_upload(h, len(data))  # signed URL
            http_put(url, data)                            # client → blob store

    # 4. Commit the new version, carrying our known base version (optimistic concurrency).
    base = local_db.get_version(file.path)                # e.g. "v17"
    try:
        resp = server.commit(path=file.path, block_list=hashes,
                             size=file.size, base_version_id=base)
        local_db.set_version(file.path, resp.version_id)   # advance local state
    except Conflict as c:
        # Server moved on (someone else committed). Keep both copies; never lose data.
        conflicted = make_conflicted_name(file.path)       # "a (conflicted 2026-06-16).psd"
        rename_local(file.path, conflicted)
        sync_local_change(File(conflicted, file.bytes), server, local_db)  # commit as new file
        pull_delta(server, local_db, file.path)            # also bring down their version v18+
```
The key ideas: hash-then-check makes uploads dedup-aware (only `missing` blocks move), and `base_version_id` turns commit into an optimistic-concurrency check whose failure path is "make a conflicted copy," guaranteeing no edit is silently dropped.

**Q. [Practical] A 50,000-member shared team folder is being edited constantly. How do you avoid a notification meltdown?**
A: The cursor model means we never send N notifications for N changes — a device only needs to know it's *behind*. We **coalesce**: when the namespace cursor bumps, parked long-polls for that namespace get a single "you're behind, pull" nudge, and the device pulls the accumulated delta in one shot. Notifications are rate-limited per namespace, fan-out is handled by the notification fleet subscribed to that `ns_id`, and the actual data transfer is the client pulling once — not 50,000 individual pushes per edit.

### 🔴 Expert
**Q. [Theory] How is syncing a Dropbox file fundamentally different from co-editing a Google Doc, and how does that change the design?**
A: Dropbox syncs **opaque files** — the server can't merge a `.psd` or `.zip`, so concurrency is handled with optimistic versioning and conflicted-copies (keep both, human resolves). Google Docs co-edits a **structured document**, so it merges at the *operation* level using **Operational Transform or CRDTs**, letting concurrent inserts/deletes converge automatically with no conflict. That changes everything: Docs needs an op-log, transform/merge functions, and a session-oriented real-time channel per document; Dropbox needs content-addressed blocks, manifests, and a cursor-based pull. Choosing the wrong model is a classic mistake — the interviewer is testing whether you recognize they're different products.

**Q. [Practical] Walk through garbage-collecting blocks safely without deleting one a concurrent commit is about to reference.**
A: Blocks carry a `ref_count` (incremented by manifest references, decremented when versions expire from trash/history). Naively deleting at `ref_count == 0` races a concurrent commit that re-references the same block (common with dedup). Safe approach: **mark-and-sweep with a grace window** — only collect blocks whose `ref_count` has been 0 *continuously for longer than the maximum in-flight commit duration*, and re-check the ref-count transactionally at delete time. Alternatively, make ref-count changes part of the commit transaction so a re-reference and a GC decrement can't interleave. Either way, bias toward keeping a block (storage is cheap; data loss is fatal).

**Q. [Theory] Redesign for multiple exabytes and global teams — what changes?**
A: Move to a **cell-based architecture**: self-contained cells (metadata shards + notification fleet + a regional blob store) each own a set of namespaces, with users/teams pinned to a home cell and a thin cross-cell router for cross-cell sharing — this removes any global bottleneck and caps blast radius. The blob layer becomes a **custom erasure-coded store on bare metal** (Dropbox's Magic Pocket) because S3 economics don't survive multiple exabytes; aggressively **tier cold versions** to colder EC schemes/archival. Notification stays cursor-coalesced per cell. Metadata replicates async cross-region with each namespace strongly consistent in its home cell.

**Q. [Behavioral] Tell me about a time you had to make a storage/consistency trade-off under pressure, or how you'd push back if a PM demanded "instant global sync with zero conflicts ever."**
A: I'd frame it honestly rather than over-promise. "Zero conflicts ever" for opaque files is physically impossible once devices edit offline — the best we can do is **never lose data** (conflicted copies) and make propagation fast (sub-second when online). I'd quantify the trade: true cross-region strong consistency on every commit adds cross-region latency to every save, which users would feel as a laggy editor; instead we keep writes strongly consistent in the namespace's home region and propagate elsewhere in well under a second, which meets the real user need ("my other laptop sees it almost instantly") without the latency tax. In a past project I made exactly this call — chose read-your-writes locally + fast eventual cross-region over global strong consistency — wrote down the explicit guarantee ("no acknowledged change is ever lost; cross-device convergence p99 < 1s"), and got PM and support aligned on what "conflict" means to users so we set the conflicted-copy UX expectation up front. The lesson: name the guarantee precisely, show the latency cost of the alternative with numbers, and align stakeholders on the user-visible contract rather than an impossible absolute.

---

*Key takeaway: a file storage & sync service is really two systems bolted together — a tiny, transactional, namespace-sharded metadata store and a vast, immutable, content-addressed, erasure-coded block store — tied together by a cursor-based notification protocol. The defining engineering is block-level delta sync (which is also deduplication), conflicted-copy concurrency that never loses data, and durability economics (erasure coding) that make exabytes affordable.*
