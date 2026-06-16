# Design a Real-Time Gaming Leaderboard

> A worked, interview-grade design of a real-time leaderboard: ingest a firehose of score updates, keep millions of players ranked, and answer "what's my rank?" and "show me the top 100" in single-digit milliseconds — where the hard part is computing **rank**, not storing scores.

[← Back to master index](../../README.md) · [← System Design index](../README.md)

---

## 1. Requirements

A leaderboard sounds trivial ("sort by score"), but the interviewer is probing the one operation that doesn't scale by sorting: **rank**. Computing "you are #4,832,109 out of 50 million" on every score change is what separates a toy from a production system. Clarify scope before drawing anything.

### Functional requirements
- **Submit score**: a player completes a match/level and submits a score (absolute, or a delta to add).
- **Get top-N**: return the top N players globally (e.g. top 100) with score and rank.
- **Get player rank**: return a specific player's current rank and score — the "where am I?" query.
- **Get rank window (relative leaderboard)**: return the K players immediately above and below a given player (the "around me" view that dominates mobile UIs).
- **Multiple leaderboards**: per-game, per-region, per-mode, and **time-windowed** (daily / weekly / all-time / seasonal).
- **Real-time updates**: the board reflects a score change within ~1–2 seconds; optionally push live rank changes to spectators.
- **Tie-breaking**: deterministic ordering when scores are equal (e.g. earlier-achiever ranks higher).

### Non-functional requirements
- **Scale**: 50M monthly players, 10M DAU, a popular title peaking at **1M concurrent players**. Score writes peak at **~100K writes/sec**; rank/board reads peak at **~500K reads/sec** (every client polls "my rank" + "top 100").
- **Latency**: read p99 **< 50 ms** server-side for top-N, get-rank, and rank-window (this is the user-facing hot path); write p99 **< 100 ms** including rank-index update.
- **Availability**: **99.9%** for reads (a frozen board is annoying, not catastrophic); writes can degrade to async/queued during incidents.
- **Durability**: a submitted, acknowledged score must **never** be silently lost — it feeds anti-cheat, rewards, and progression, so the authoritative score record is durable even if the *ranking index* is a rebuildable cache.
- **Consistency**: rank can be **eventually consistent** within ~1–2 s (a player seeing rank 4,011 vs 4,009 for a second is fine); the *score* itself should be read-your-writes for the submitting player.
- **Security / integrity**: scores must be server-validated and anti-cheat-gated — a leaderboard is the single most attacked surface in a game (every cheater wants #1).

### Clarifying questions a strong candidate asks
1. **Absolute score or delta?** "Set best-ever score" (idempotent, max-wins) vs "add 50 points" (accumulating) change the write semantics and dedup story.
2. **How many distinct leaderboards?** One global board, or millions (per-region × per-mode × per-day)? This dominates the memory/sharding math far more than player count.
3. **What's the read mix?** Is it mostly "top 100" (cacheable, one answer for everyone) or "my rank" (50M distinct answers)? They have wildly different scaling profiles.
4. **Time windows?** Daily/weekly/seasonal boards that reset — drives a rollover/archival strategy and TTLs.
5. **Exact rank or approximate?** Does the UI need "#4,832,109" exactly, or is "Top 5%" / "~4.8M" acceptable? Approximate rank unlocks vastly cheaper designs at the long tail.
6. **Ties — do they matter?** Money/rewards on the line means tie-breaking must be deterministic and defensible.
7. **Anti-cheat in scope?** If scores aren't trusted, the leaderboard is just a cheater scoreboard — clarify whether validation is our problem or upstream.

> The pivotal question is #5. **Exact rank for 50M players on every read is the expensive promise.** Top players need exact rank (rewards, bragging rights); the long tail is fine with approximate. A great answer exploits this asymmetry rather than computing exact rank for everyone.

---

## 2. Capacity Estimation

Real numbers, shown long-hand. Assume a 5-year horizon and one flagship title.

### Write QPS (score submissions)
```
10,000,000 DAU × ~20 score-affecting events/day = 200,000,000 writes/day
200,000,000 ÷ 86,400 s/day ≈ 2,315 writes/sec   (avg)
Peak factor ~4x (evening + event launches) → ~9,300 writes/sec sustained
Esports/launch spike (1M concurrent finishing matches) → ~100,000 writes/sec burst
```
Design target: **~100K WPS peak** into the ranking layer.

### Read QPS (rank + top-N)
```
Each active client polls: "my rank" + "rank window" + "top 100" every ~5s while a board is open.
1,000,000 concurrent × 3 reads ÷ 5 s ≈ 600,000 reads/sec at peak
```
Round to **~500K–600K RPS peak**. Reads dominate writes ~5–6:1, and the top-N read is the *same answer for everyone* → extremely cacheable. The "my rank" read is the one that's hard to cache.

### Memory for the ranking index (the real constraint)
The ranking index lives in RAM (a sorted structure — see §4). Per-entry cost in a Redis Sorted Set:
```
member (player_id, ~16–24 B) + score (8 B double) + skiplist/hash overhead (~64 B)
≈ ~90–100 bytes per player per leaderboard
```
For one global all-time board of 50M players:
```
50,000,000 × 100 B ≈ 5 GB   (fits comfortably in one Redis shard's RAM)
```
But the cost is **per leaderboard**. The killer is *cardinality of boards*:
```
1 game × 10 regions × 5 modes × (daily=1 + weekly=1 + alltime=1) boards
But "daily" alone = 365 boards/yr. Suppose we keep 90 days hot:
10 regions × 5 modes × 90 daily boards = 4,500 hot daily boards
If each has ~100K active players: 4,500 × 100K × 100 B ≈ 45 GB
Plus 50 weekly + a few all-time (50M each):
all-time: 10 × 5 × 50M × 100B = 250 GB  ← dominates
```
**Takeaway: leaderboard *count* and all-time board *size*, not player count, drive memory.** A few hundred GB of hot ranking index → a sharded Redis Cluster (10–20 nodes), with cold/archived boards spilled to disk-backed storage.

### Storage (durable score history, 5-year)
```
Authoritative score event: player_id(8) + board_id(16) + score(8) + ts(8) + meta(~40) ≈ 80 B
200M events/day × 80 B ≈ 16 GB/day
16 GB × 365 × 5 ≈ 29 TB raw
× 3 replication + indexes (~1.5x) ≈ 130 TB
```
This durable history is large but cheap (it's append-only, lives in Cassandra/object storage), and crucially it lets us **rebuild the ranking index** if Redis is lost.

### Bandwidth
```
Writes: 100K WPS × ~120 B ≈ 12 MB/s   (trivial)
Reads:  top-100 response ≈ 100 × 80 B = 8 KB; my-rank ≈ 80 B
        Top-N is cached/fanned-out; assume 500K RPS × ~1 KB avg ≈ 500 MB/s
```
Bandwidth is modest; **request rate, rank computation, and RAM** are the constraints we engineer around.

---

## 3. API Design

REST/gRPC over HTTPS for queries; an optional WebSocket channel for live rank streaming. Writes are authenticated and anti-cheat-gated; reads of public boards are open (rate-limited).

```http
# Submit a score (server-validated). Idempotent on (player_id, match_id).
POST /v1/leaderboards/{board_id}/scores
Authorization: Bearer <token>
{
  "player_id": "p_9f3",
  "match_id":  "m_8821",          // idempotency key — dedups retries
  "score":     48200,
  "mode":      "ADD"              // ADD (accumulate) | MAX (keep best) | SET
}
→ 202 Accepted
{ "player_id":"p_9f3", "score":48200, "rank":4811, "board_id":"daily:us:ranked:20260616" }
→ 409 Conflict          // duplicate match_id already applied (returns prior result)
→ 422 Unprocessable     // failed anti-cheat / validation

# Top-N (the cacheable hot read — same answer for everyone)
GET /v1/leaderboards/{board_id}/top?limit=100
→ 200 { "entries":[ {"rank":1,"player_id":"p_1","score":99120}, ... ] }

# A single player's rank + score ("where am I?")
GET /v1/leaderboards/{board_id}/players/{player_id}
→ 200 { "player_id":"p_9f3", "score":48200, "rank":4811, "percentile":0.91 }
→ 404 Not Found          // player not on this board

# Rank window — the "around me" view (K above + K below)
GET /v1/leaderboards/{board_id}/players/{player_id}/window?range=5
→ 200 { "entries":[ {rank:4806..4816} ], "me":4811 }

# Live stream (optional) — push rank/score deltas to spectators
WS /v1/leaderboards/{board_id}/subscribe
  → server pushes { "type":"RANK_UPDATE", "player_id":"p_1", "rank":2, "score":99050 }
```
Design notes: writes return **202 Accepted** because the durable write and the rank-index update may be decoupled (the rank returned is best-effort-current). The `match_id` makes submissions **idempotent**, killing the double-submit/retry duplication problem. Top-N is a separate endpoint precisely because it's the one query we can cache once and serve to millions.

---

## 4. Data Model

Three stores, each chosen for a different job: a **sorted in-memory index** for ranking, a **durable event log** for the authoritative record, and a **relational/KV store** for board metadata.

### (1) Ranking index — Redis Sorted Set (the core)
The defining operation is "rank of a score among N scores," which a balanced structure answers in **O(log N)** — exactly what a Redis **Sorted Set (ZSET)** gives. It's a skip list + hash map: the hash maps member→score, the skip list keeps members score-ordered with rank queries.

```
Key:   lb:{board_id}            e.g. lb:daily:us:ranked:20260616
Member: player_id
Score:  packed_score (see tie-breaking below)

Operations & cost:
  ZADD   lb:{board} <score> <player>     → O(log N)   submit/update
  ZREVRANK lb:{board} <player>           → O(log N)   get rank (0-based, desc)
  ZREVRANGE lb:{board} 0 99 WITHSCORES   → O(log N + 100)  top-100
  ZREVRANGE lb:{board} <rank-5> <rank+5>  → O(log N + K)    rank window
  ZCARD / ZCOUNT                          → O(1) / O(log N)  size / percentile
```
**Why ZSET and not just sort-on-read:** sorting 50M rows per query is O(N log N) and impossible at 500K RPS. The ZSET maintains the order incrementally on write (O(log N)) so *every* read is O(log N) — that single property is the whole reason this design works.

**Tie-breaking via score packing.** Equal scores need deterministic order. Pack score and an inverse timestamp into one 64-bit double/int so the sort key is unique and "earlier wins":
```
packed = score * 1e13 + (MAX_TS - achieved_at_ms)
# higher score wins; on tie, smaller achieved_at (earlier) ranks higher
```
(Redis ZSET scores are IEEE-754 doubles — exact only to 2^53. For larger ranges, use a lexicographic member encoding `score|inv_ts|player` with `ZRANGEBYLEX`, or store an integer in a Lua-scripted variant.)

### (2) Authoritative score store — Cassandra (durable, rebuildable source of truth)
Redis is a *cache of rank*; the durable truth lives here. Append-only, write-heavy, no joins → wide-column NoSQL.
```sql
CREATE TABLE score_events (
  board_id    text,
  player_id   text,
  match_id    text,          -- idempotency key
  score       bigint,
  achieved_at timestamp,
  PRIMARY KEY ((board_id), player_id, match_id)
);
-- Current best/total per player per board (for MAX/ADD semantics & rebuild)
CREATE TABLE player_scores (
  board_id  text, player_id text, score bigint, updated_at timestamp,
  PRIMARY KEY ((board_id), player_id)
);
```
This is what we **replay to rebuild a Redis shard** after failure, and what anti-cheat/rewards audit against.

### (3) Board metadata — relational / DynamoDB
Small, read-heavy, cacheable: `boards(board_id PK, game, region, mode, window_type, opens_at, closes_at, status)`. Drives rollover and which shard owns a board.

### Why this split
| Concern | Store | Why |
|---|---|---|
| Rank computation | Redis ZSET | O(log N) incremental rank; the only structure that makes 500K RPS reads viable |
| Durability / audit | Cassandra | Append-only, 130 TB, survives Redis loss, source of truth for rebuild |
| Board catalog | SQL/DynamoDB | Tiny, relational, needs queries by region/mode/window |

The key insight: **the ranking index is a derived, rebuildable cache.** Treating Redis as authoritative would be a durability mistake; treating Cassandra as the rank engine would be a latency disaster.

---

## 5. High-Level Architecture

```
                         ┌────────────────────────────┐
                         │   Game Clients / Servers    │
                         └─────────────┬──────────────┘
                                       │ HTTPS / WS
                         ┌─────────────▼──────────────┐
                         │   API Gateway + L7 LB        │  rate-limit, auth
                         └──────┬───────────────┬──────┘
              WRITE path        │               │        READ path (hot)
        ┌───────────────────────▼──┐      ┌─────▼────────────────────────┐
        │  Score Ingest Service     │      │  Query Service (stateless)    │
        │  (validate + anti-cheat)  │      │  top-N / my-rank / window     │
        └───────┬───────────┬───────┘      └───┬───────────────────┬──────┘
                │           │                   │ top-N (cached)    │ my-rank/window
                │ durable   │ async event       ▼                   ▼
        ┌───────▼──────┐ ┌──▼────────┐   ┌──────────────┐   ┌───────────────────┐
        │  Cassandra    │ │  Kafka    │   │  CDN / Edge   │   │  Redis Cluster     │
        │ (score_events │ │ (score    │   │  cache of     │   │  (ZSET per board,  │
        │  player_scores│ │  stream)  │   │  top-N JSON   │   │  sharded by        │
        │  source of    │ └────┬──────┘   │  ~1s TTL)     │   │  board_id)         │
        │  truth)       │      │          └──────────────┘   └─────────▲─────────┘
        └───────────────┘      │ consume                                │ ZADD
                               ▼                                        │
                   ┌────────────────────────┐                          │
                   │  Rank Updater Workers   │──────────────────────────┘
                   │  (apply MAX/ADD/SET,    │   ZADD lb:{board} score player
                   │   pack tie-break key)   │
                   └───────────┬─────────────┘
                               │ rank-change events
                               ▼
                   ┌────────────────────────┐
                   │  Live Push Service (WS) │  → spectators / live UI
                   └────────────────────────┘
```

### Component walkthrough
- **API Gateway + LB**: terminates TLS, authenticates, applies per-player/per-IP rate limits (a key anti-cheat and anti-DoS lever), and splits the write fleet from the read fleet so a score storm can't starve reads.
- **Score Ingest Service** (stateless): validates the score, runs synchronous anti-cheat checks (plausibility, rate, server-authoritative replay), dedups on `match_id`, then does a **durable write to Cassandra** and publishes the event to **Kafka**. It returns `202` immediately — it does *not* block on the Redis rank update.
- **Kafka**: decouples ingestion from ranking. Partitioned **by `board_id`** so all updates to one board are ordered and land on one consumer, preserving correct rank application and absorbing 100K-WPS bursts.
- **Rank Updater Workers**: consume Kafka, apply the score semantics (MAX/ADD/SET), compute the packed tie-break key, and `ZADD` into the right Redis shard. They emit rank-change events for the live stream.
- **Redis Cluster**: the sorted ranking index, sharded by `board_id` (an entire board lives on one shard so rank queries stay single-node O(log N)).
- **Query Service** (stateless, the hot path): serves `top-N` from CDN/edge cache (same answer for all), and `my-rank`/`window` from Redis (`ZREVRANK`/`ZREVRANGE`). Near-zero work per request.
- **CDN/Edge**: caches the top-N JSON with a ~1 s TTL — one board's top-100 served to a million clients from the edge, never touching Redis.
- **Live Push Service**: optional WebSocket fan-out of rank changes to spectators/streamers.

The crucial flow choice: **the write path is durable-then-async-rank.** Cassandra write is synchronous (durability), Redis rank update is asynchronous via Kafka (eventual rank). This is what hits both the durability SLO and the 100K-WPS write target.

---

## 6. Deep Dives

### 6.1 Computing rank without sorting on every read
This is the heart of the problem and where most candidates stumble.

**Naive (wrong):** store scores in SQL, `SELECT COUNT(*) WHERE score > x` per rank query. That's an O(N) scan or index range over 50M rows — at 500K RPS it melts the DB.

**Right:** maintain order *incrementally* on write with a sorted structure (Redis ZSET / skip list / balanced BST). Each write is O(log N); each rank read is O(log N). The cost moves from read time (where we have 500K RPS) to write time (100K WPS) and is logarithmic either way.
```
50M players → log2(50e6) ≈ 26 comparisons per op. ~26 pointer hops in RAM ≈ microseconds.
```
**Why not a plain heap?** A heap gives you the top-K cheaply but *cannot* answer "rank of an arbitrary player" — it's not order-statistics-capable. A **skip list / order-statistics tree** (what ZSET uses) supports `rank(member)`, `range`, and `top-K` all in O(log N). That's the right data structure.

### 6.2 Exact rank for 50M is expensive — approximate the long tail
`ZREVRANK` is O(log N) and cheap *per call*, but exact rank has a subtler cost: it pins the **entire board to one shard** (you can't shard a single sorted set across nodes without losing global order). One 50M-player all-time board on one shard caps your write throughput at that node's ceiling.

Exploit the asymmetry from requirement #5:
- **Top of the board (say top 10K): exact rank.** These players care, rewards depend on it, and 10K fits trivially in a precise ZSET.
- **Long tail (everyone else): approximate rank via histogram.** Maintain a **score-bucket histogram** (e.g. 10K buckets) with a count per bucket. A player's approximate rank = sum of counts in higher buckets + interpolation within their bucket. This is O(1)/O(buckets), can be sharded and merged, and is accurate to a fraction of a percent — perfectly fine for "you're in the top 8.2%, ~#4.1M."
```
approx_rank(score) = Σ count[bucket] for bucket.score > score
                   + (fraction of own bucket above score)
percentile = approx_rank / total_count
```
Histograms are **mergeable across shards**, so the long-tail board *can* be sharded. You serve exact rank where it's cheap and matters, and cheap approximate rank everywhere else. This is the single biggest scalability unlock.

### 6.3 Sharding leaderboards across a Redis Cluster
A single sorted set can't span shards (rank is global within the set), so we shard at the **board granularity**:
- **Shard by `board_id`** via consistent hashing. `daily:us:ranked:20260616` lives entirely on one shard; `daily:eu:...` on another. Most boards are small/medium and distribute evenly.
- **Hot board problem:** a single mega-board (global all-time, 50M players, esports finals) is a hot shard. Mitigations:
  1. **Read replicas** of that shard fan out the read load (rank reads are read-only).
  2. **Top-N at the CDN** removes the dominant read entirely.
  3. **Split exact (top-K, one shard) from approximate (histogram, sharded)** per §6.2 so the write load distributes.
  4. **Regional sub-boards** that merge into a global view asynchronously, instead of one global ZSET (see §6.4).
- **Hot-key on the top entry:** the #1 spot changing 100×/s during finals — coalesce updates and publish at a capped rate (e.g. 10 Hz) to the live stream rather than per-change.

### 6.4 Time-windowed boards & rollover (daily / weekly / seasonal)
Time windows are mostly a **key-naming + TTL + archival** problem, not new algorithms.
- **Key includes the window:** `lb:weekly:us:ranked:2026-W24`. A new window = a new key; the old one stops receiving writes naturally at the boundary. No "reset" operation, no race.
- **TTL for hot retention:** set a Redis TTL (e.g. daily boards expire from RAM after 90 days). The durable history stays in Cassandra; an expired board is reconstructable on demand.
- **Rollover at the boundary:** at midnight, ingest simply starts writing the new day's key. To avoid clock-skew double-writes near the boundary, the Ingest Service stamps each event with the canonical window derived from `achieved_at` server-side, not client time.
- **Archival & "final" boards:** when a season closes, snapshot the final ZSET to Cassandra/object storage as an immutable result (for rewards, history pages), then let the hot key expire.
- **All-time board** never rolls over but grows unbounded → it's the one that needs the approximate-tail treatment (§6.2) most.

### 6.5 Durability vs the async rank gap
We write to Cassandra synchronously but update Redis asynchronously (via Kafka). That creates a window where the durable score exists but the rank index hasn't caught up.
- **Read-your-writes for the submitter:** the `202` response can return the player's *projected* rank computed optimistically, and the submitting client's subsequent `my-rank` read can be served from a short-lived write-through entry, so the player who just scored never sees a stale "old rank."
- **Ordering correctness:** because Kafka is partitioned by `board_id`, all updates for a board are applied in order by a single consumer — no lost-update race between two ADDs to the same player.
- **Rebuild after Redis loss:** if a Redis shard dies, we don't lose data — we **replay `player_scores` from Cassandra** to repopulate the ZSET. Rebuild of a 50M board is a bulk `ZADD` pipeline (millions/sec), so RTO is minutes, and during rebuild reads can fall back to the durable store with degraded latency.
- **Idempotency under retries:** `match_id` dedup at ingest + per-board ordered Kafka means a retried submit is applied exactly once even though the transport is at-least-once.

### 6.6 Anti-cheat & integrity (the leaderboard's existential threat)
A leaderboard with no integrity is a list of cheaters. Defenses, layered:
- **Server-authoritative scoring:** never trust a client-submitted raw score for competitive boards — validate against server-side match state / replay, or recompute from authoritative inputs.
- **Plausibility & rate checks at ingest:** reject physically-impossible scores (above the theoretical max), implausible deltas, or superhuman submission rates (token-bucket per player).
- **Idempotency + audit log:** every score event is durably logged with `match_id`, so flagged scores can be reverted and the board rebuilt from the clean history.
- **Shadow review & rollback:** suspected cheats are placed on a quarantined/shadow board pending review; confirmed cheats are removed and the index rebuilt (cheap, because the index is derived).
- **Segregation:** verified vs unverified boards, so anti-cheat lag never lets a cheater sit visibly at #1 on the rewarded board.

---

## 7. Scaling, Bottlenecks & Failure Handling

**What breaks first?** Usually a **hot board's single Redis shard**, because a sorted set can't be split across nodes and the most-popular board concentrates both writes and rank-reads on one node. Order of stress: (1) hot-board Redis shard, (2) rank-updater consumer lag during write bursts, (3) Redis cluster RAM (board cardinality), (4) Cassandra write throughput, (5) live-push fan-out.

- **Stateless tiers scale horizontally:** Query and Ingest services sit behind autoscaling groups; add nodes for throughput. No per-request state.
- **Hot board:** combine read replicas (read fan-out), CDN top-N caching (removes the dominant read), exact/approximate split (§6.2), and regional sub-boards merged asynchronously. The CDN top-N cache is the biggest single lever for read load.
- **Write bursts (100K WPS):** Kafka is the shock absorber — ingestion can outrun the rank updaters and the backlog drains in seconds. Per-board partitioning keeps ordering correct while parallelizing across boards. Batch `ZADD`s (pipeline) to amortize round trips.
- **RAM / board cardinality:** shard boards across the cluster; TTL-expire cold daily boards; spill archived boards to disk-backed tiers (Redis on flash / KeyDB / a sorted structure in RocksDB). Add shards to add capacity.
- **Replication & failover:** Redis Cluster with replicas per shard → automatic failover on node loss; durability backstopped by Cassandra rebuild. Cassandra RF=3 across AZs, `QUORUM` writes survive an AZ loss. Kafka RF=3, `min.insync.replicas=2`.
- **Graceful degradation:** if the rank updaters lag, reads serve a slightly-stale board (acceptable — eventual consistency SLO is 1–2 s). If Redis is fully down, fall back to Cassandra-backed approximate rank with relaxed latency and a "ranks updating" banner. If anti-cheat is down, **fail closed** for rewarded boards (queue, don't publish) — integrity outranks freshness here.
- **Rate limiting:** per-player token buckets at the gateway cap submission spam (anti-cheat + DoS protection); read endpoints get coarse IP limits.
- **Thundering herd on cold board / TTL expiry:** single-flight the rebuild (one consumer repopulates from Cassandra; concurrent readers wait or get approximate), and jitter board TTLs so many boards don't expire simultaneously.

---

## 8. Trade-offs & Alternatives

- **Exact vs approximate rank.** Exact is what users expect for the top; approximate (histogram) is the only thing that scales for 50M and lets the long tail shard. **Chosen: hybrid** — exact top-K, approximate tail. Different constraints (small player counts, lots of money on the line) might justify exact-everywhere on one shard.
- **Redis ZSET vs a custom skip-list service vs SQL.** ZSET gives O(log N) rank out of the box, battle-tested, replicated — chosen. A bespoke in-memory ranking service buys control but costs months and reinvents Redis. SQL with `COUNT(*) WHERE score >` is fine at small scale (thousands of players) and gives free durability + transactions, but its O(N) rank query dies at our read rate. **Chosen: Redis ZSET + Cassandra for durability.**
- **Sync vs async rank update.** Synchronous (write Cassandra *and* Redis before acking) gives read-your-writes everywhere but caps write throughput and couples failure domains. Async (Kafka) hits 100K WPS and decouples failures at the cost of a 1–2 s rank lag. **Chosen: async**, with a write-through optimization so the *submitter* still gets read-your-writes.
- **CAP / consistency.** Reads choose **AP** — serve a slightly-stale board rather than fail (a frozen-but-up board beats an error). The durable score write chooses **CP-ish** — a `QUORUM` Cassandra write before ack so an acknowledged score is never lost. Rank is explicitly eventual.
- **Per-board sharding vs one global structure.** Per-board sharding distributes load but can't split a single hot board; a global structure can't be sharded at all. **Chosen: per-board sharding + hot-board mitigations** (replicas, CDN, exact/approx split).

**At 10x scale (~5M WPS, dozens of titles):** push top-N entirely to the edge, default the long tail to approximate everywhere, regionalize every mega-board (regional ZSETs merged into a global histogram), and move the rank updaters to per-board-cell consumers so no global component is a bottleneck.

**At 100x scale:** this becomes a **cell-based** system — each region/title is a self-contained cell (Ingest + Kafka + Redis + Cassandra) with users pinned to a home cell, and only periodic histogram merges produce the global view. Exact global rank below the top-K is abandoned in favor of merged-histogram percentiles, which are mergeable and cheap.

---

## Interview Q&A by Level

### 🟢 Basic
**Q. [Theory] Why can't you just sort the players by score on every request?**
A: Sorting N players is O(N log N); at 50M players and 500K reads/sec that's astronomically expensive and would re-do identical work constantly. Instead you maintain order *incrementally* on each write with a sorted structure (Redis ZSET / skip list), so writes are O(log N) and every read — top-N, my-rank, window — is also O(log N). The cost moves to write time and stays logarithmic.

**Q. [Theory] Why is a Redis Sorted Set the natural fit for a leaderboard?**
A: A ZSET is a skip list plus a hash map. The hash maps player→score; the skip list keeps players score-ordered and supports rank queries. That gives `ZADD` (update) and `ZREVRANK` (get rank) in O(log N), and `ZREVRANGE` (top-N / window) in O(log N + K) — exactly the operations a leaderboard needs, with no sort-on-read.

**Q. [Practical] How do you return the "top 100" to a million concurrent players cheaply?**
A: The top-100 is the *same answer for everyone*, so cache it. Compute it once with `ZREVRANGE 0 99`, cache the JSON at the CDN/edge with a ~1 s TTL, and serve a million clients from the edge without touching Redis. The "my rank" query is the hard one because it has 50M distinct answers — that one hits Redis.

### 🟡 Intermediate
**Q. [Practical] How do you keep the durable record safe while still hitting 100K writes/sec into the rank index?**
A: Split the write path: synchronously write the authoritative score to Cassandra (durability), then publish to Kafka and update Redis asynchronously via rank-updater workers. The durable write is the slow/safe part; the rank update is eventual (1–2 s). Kafka absorbs 100K-WPS bursts and, partitioned by `board_id`, keeps per-board updates ordered. An acknowledged score is never lost even if Redis is mid-rebuild.

**Q. [Practical] How do you handle daily/weekly boards that reset?**
A: Encode the window in the key (`lb:weekly:us:2026-W24`). A new window is just a new key, so there's no reset operation and no race — ingest starts writing the new key at the boundary, stamping events with the server-derived window from `achieved_at` to avoid client clock skew. Old keys get a TTL to expire from RAM; the durable history stays in Cassandra, and final boards are snapshotted to object storage for rewards/history.

**Q. [Theory] How do you break ties deterministically when two players have the same score?**
A: Make the sort key unique. Pack the score with an inverse timestamp into one value: `packed = score·1e13 + (MAX_TS − achieved_at)`. Higher score wins; on a tie, the earlier achiever ranks higher. Because Redis ZSET scores are 64-bit doubles (exact to 2^53), for large ranges use a lexicographic member encoding (`score|inv_ts|player`) with `ZRANGEBYLEX` instead.

**Q. [Theory] What's your consistency model in CAP terms?**
A: Mixed. The rank read path is **AP** — serve a slightly-stale board (1–2 s old) rather than fail, since a frozen-but-up leaderboard beats an error. The durable score write is **CP-leaning** — a Cassandra `QUORUM` write before we ack, so an acknowledged score is never lost. Rank is explicitly eventually consistent, with a write-through shortcut so the submitting player still gets read-your-writes on their own score.

### 🟠 Advanced
**Q. [Coding] Write the core logic to submit a score and fetch a player's rank and "around me" window using Redis. Handle MAX-wins and tie-breaking.**
A: Using Redis ZSET commands (pseudo-code / redis-py style). We pack score + inverse timestamp so ties resolve by earliest, and apply MAX semantics with a Lua script for atomicity.

```python
# Pack so higher score wins, earlier achiever wins ties.
MAX_TS = 10**13
def pack(score, achieved_ms):
    return score * MAX_TS + (MAX_TS - achieved_ms)   # one comparable number

# MAX-wins submit: only update if the new packed value beats the stored one.
SUBMIT_LUA = """
local cur = redis.call('ZSCORE', KEYS[1], ARGV[2])
if (not cur) or (tonumber(ARGV[1]) > tonumber(cur)) then
  redis.call('ZADD', KEYS[1], ARGV[1], ARGV[2])
  return 1
end
return 0
"""
def submit(r, board, player, score, achieved_ms):
    packed = pack(score, achieved_ms)
    r.eval(SUBMIT_LUA, 1, f"lb:{board}", packed, player)

def get_rank(r, board, player):
    # ZREVRANK is 0-based, descending → rank = revrank + 1
    rr = r.zrevrank(f"lb:{board}", player)
    return None if rr is None else rr + 1

def window(r, board, player, k=5):
    rr = r.zrevrank(f"lb:{board}", player)
    if rr is None:
        return []
    lo, hi = max(0, rr - k), rr + k
    rows = r.zrevrange(f"lb:{board}", lo, hi, withscores=True)
    return [{"rank": lo + i + 1, "player": m, "score": s // MAX_TS}
            for i, (m, s) in enumerate(rows)]
```
`submit` is O(log N) and atomic (Lua) so concurrent MAX submits can't lose an update; `get_rank` and `window` are O(log N) and O(log N + K). To recover the *display* score we divide out the pack factor. In production the `submit` runs in the rank-updater worker off a Kafka partition keyed by board, so per-board ordering is guaranteed.

**Q. [Practical] A 50M-player all-time board is a single hot Redis shard. How do you scale it?**
A: A sorted set can't be split across shards (rank is global within the set), so attack it from multiple sides: (1) serve top-N from the CDN so the dominant read never hits Redis; (2) add read replicas of that shard to fan out the remaining rank reads; (3) split exact rank (top-K on one precise ZSET) from approximate rank (a mergeable, shardable score-bucket histogram for the long tail); (4) regionalize into per-region ZSETs merged asynchronously into a global histogram. The exact/approximate split (4.2) is what lets the long tail actually shard.

**Q. [Theory] How do you serve "you're ranked ~#4.8M of 50M" without an exact, single-shard rank query for everyone?**
A: Maintain a score-bucket histogram (e.g. 10K buckets, count per bucket). Approximate rank = sum of counts in all higher buckets plus interpolation within the player's own bucket; percentile = that over the total count. It's O(buckets), accurate to a fraction of a percent, and — crucially — **mergeable across shards**, so unlike an exact ZSET it can be partitioned. You reserve exact rank for the top-K where it's cheap and where rewards demand precision.

**Q. [Practical] A Redis shard holding several boards crashes. What's the impact and recovery?**
A: No data loss — Redis here is a derived cache. The authoritative scores live in Cassandra (`player_scores`/`score_events`). The shard's replica takes over via Cluster failover in seconds; if both are gone, we rebuild by replaying `player_scores` for the affected boards as a pipelined bulk `ZADD` (millions/sec), so RTO is minutes. During rebuild, reads degrade to Cassandra-backed approximate rank behind a "ranks updating" banner. Single-flight the rebuild so concurrent readers don't trigger a thundering herd.

### 🔴 Expert
**Q. [Practical] Redesign for 100x scale — hundreds of titles, ~5M writes/sec — with global rankings.**
A: Go cell-based. Each region/title is a self-contained cell (Ingest + Kafka + Redis + Cassandra), with players pinned to a home cell, so there's no global write bottleneck and blast radius is capped. Each cell keeps exact rank only for its top-K and a local score-bucket histogram for everyone else. A periodic merge job combines per-cell histograms into a **global percentile/approximate-rank view** (histograms merge trivially). Exact *global* rank below the top-K is abandoned — at 100x it's neither affordable nor meaningful, and merged-histogram percentiles satisfy the real product need. Top-N per board stays edge-cached.

**Q. [Behavioral] You shipped the leaderboard, and a week later a streamer publicly shows an obviously cheated #1 score on the rewarded board. How do you respond?**
A: First, contain: I'd immediately quarantine the flagged score (move it to a shadow board) so it's off the public rewarded board within minutes — our index is derived and rebuildable, so removing one player and re-ranking is cheap and safe. Then communicate: acknowledge publicly that we're investigating, without accusing, because integrity perception is the product. In parallel, use the durable `score_events` audit log to confirm the cheat (impossible delta / failed server-side validation) and check for a class of similar exploits, not just the one account. Then fix the root cause — tighten server-authoritative validation or the plausibility check that let it through — and add a regression detection rule. Finally, a blameless retro: the gap was that anti-cheat ran async and lagged the rewarded board; the durable lesson is to **fail closed** on rewarded boards (queue suspicious scores rather than publish optimistically). I'd own the miss rather than blame the cheater — our job was to make it not matter.

**Q. [Theory] When would you NOT use Redis ZSETs, and what would you reach for instead?**
A: Three cases. (1) **Tiny scale** (a few thousand players, low QPS): a single SQL table with `SELECT COUNT(*) WHERE score > ?` is simpler, gives free durability and transactions, and the O(N) rank query is irrelevant at that size — don't add Redis. (2) **The board exceeds one node's RAM and needs a *single global exact* ordering**: ZSETs can't shard, so you'd build/borrow a sharded order-statistics structure or accept approximate (histogram) rank. (3) **You only ever need top-K, never arbitrary rank**: a bounded heap or a `ZSET` capped via `ZREMRANGEBYRANK` is cheaper, and you avoid storing the long tail in RAM at all. The decision hinges on player count, whether you need *arbitrary* rank vs just top-K, and whether exact global order is a hard requirement.

**Q. [Practical] How do you guarantee a retried score submission isn't double-counted, especially with ADD (accumulate) semantics?**
A: Idempotency keyed on `match_id`. At ingest, a conditional insert into Cassandra keyed by `(board_id, player_id, match_id)` rejects a duplicate and returns the prior result (`409`). For the rank index, Kafka is partitioned by `board_id`, so a single ordered consumer applies each board's updates exactly once in order — a retried event with the same `match_id` is dropped at the consumer's dedup check before the `ZADD`/ADD is applied. This matters most for ADD semantics, where a double-apply would silently inflate a total; MAX and SET are naturally idempotent, but we dedup uniformly so the behavior is consistent.

---

*Key takeaway: a real-time leaderboard is a masterclass in the rank operation — the interesting engineering is maintaining order incrementally with O(log N) sorted structures, exploiting the exact-top / approximate-tail asymmetry to shard, treating the rank index as a rebuildable cache over a durable score log, and defending integrity because the leaderboard is the single most attacked surface in a game.*
