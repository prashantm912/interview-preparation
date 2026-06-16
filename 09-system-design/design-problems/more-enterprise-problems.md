# More Enterprise-Scale Design Problems (15+ yrs)

A staff-level companion covering the senior design problems that go *beyond* the canonical top-10 (URL shortener, rate limiter, etc.): multi-channel notification fan-out, ride-sharing, file sync, feature flags/experimentation, multi-tenant SaaS, distributed schedulers, ad auctions, recommendations, metrics platforms, and real-time collaborative editing. Each problem is framed around requirements, the 2–3 hardest challenges, key trade-offs, and concrete tech choices (Java-first), current through 2026.

[← Back to master index](../../README.md)

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

### Q1. [Theory] What problem does a multi-channel notification service solve, and what are its core components?

A notification service decouples *event producers* (order shipped, password reset, marketing campaign) from *delivery channels* (push, SMS, email, in-app, webhook). Without it, every microservice would need to integrate Twilio, FCM/APNs, SendGrid, etc., duplicating logic and credentials. The core components are: an **ingestion API** that accepts a notification request, a **template/rendering** layer (Handlebars/Mustache with localization), a **preference/consent store** (per-user channel opt-ins, quiet hours), a **router** that picks channels, **per-channel adapters/workers**, and a **delivery-status tracker** (sent → delivered → opened/bounced).

```
producer → [Ingest API] → [queue] → [Router] → fan-out
                                          ├─→ Push worker  → FCM/APNs
                                          ├─→ Email worker → SES/SendGrid
                                          ├─→ SMS worker   → Twilio
                                          └─→ Webhook worker
                          [Preferences] [Templates] [Status DB]
```

The "why" of decoupling is reliability and rate isolation: a backed-up SMS provider must not block email, so each channel gets its own queue and worker pool.

### Q2. [Theory] In a ride-sharing system, what does "geo-matching" mean and why can't you just query a SQL table with a `WHERE lat/lng BETWEEN` clause?

Geo-matching means finding the *k* nearest available drivers to a rider's pickup point in near-real-time. A naive `BETWEEN` bounding-box query scans a large table, returns a square (not a circle, so corners are wrong distances), and forces an expensive Haversine sort over many rows per request — at Uber scale that is millions of QPS against constantly-moving points. Instead you use a **spatial index**: a geohash, an S2 cell, or an H3 hexagon to bucket the world into cells, so a lookup becomes "fetch drivers in my cell + neighboring cells" — an O(1)-ish key lookup in Redis rather than a full scan. The trade-off is precision vs. recall: smaller cells = fewer candidates but more edge-of-cell misses, so you query the home cell plus its ring of neighbors.

### Q3. [Practical] You need to send a "password reset" email. Walk through how you'd make it reliable.

Approach: the auth service publishes a `PasswordResetRequested` event (with a short-lived signed token) to the notification service's ingest API, which immediately returns `202 Accepted` with a notification ID. The request lands on a durable queue (Kafka/SQS). An email worker consumes it, renders the template, and calls the provider with an **idempotency key** = notification ID so a retry never sends two emails. If the provider call fails, exponential backoff retries; after N attempts it goes to a **dead-letter queue** for inspection. Because password resets are transactional and security-sensitive, they bypass marketing throttles and quiet hours.

Production reality: you must dedupe at the source too — users mash "resend" — so collapse identical requests within a short window, and expire the token in ~15 minutes to limit the security blast radius if the inbox is compromised.

### Q4. [Coding] Implement a geohash-style cell key for bucketing drivers, plus a function returning the cell and its 8 neighbors.

Problem: given a lat/lng and a grid resolution, produce a string cell key usable as a Redis key, and the set of adjacent cells to search for nearest drivers.

```java
public final class GeoCell {
    // Snap to a grid of `step` degrees. Smaller step = finer cells.
    static String cellKey(double lat, double lng, double step) {
        long latIdx = (long) Math.floor(lat / step);
        long lngIdx = (long) Math.floor(lng / step);
        return latIdx + ":" + lngIdx;
    }

    // Home cell + 8 neighbors (Moore neighborhood) to cover edge-of-cell drivers.
    static java.util.List<String> searchCells(double lat, double lng, double step) {
        long latIdx = (long) Math.floor(lat / step);
        long lngIdx = (long) Math.floor(lng / step);
        var cells = new java.util.ArrayList<String>(9);
        for (long dLat = -1; dLat <= 1; dLat++)
            for (long dLng = -1; dLng <= 1; dLng++)
                cells.add((latIdx + dLat) + ":" + (lngIdx + dLng));
        return cells;
    }
}
```

- **Time**: O(1) for the key, O(9) for neighbors. The actual nearest-driver step is O(candidates) where candidates = drivers in 9 cells, then sort/Haversine on that small set.
- **Space**: O(1).
- **Edge cases**: the antimeridian (±180° longitude) and poles wrap incorrectly with simple indices — production uses S2/H3 which handle the sphere. Choose `step` per region: dense cities need finer cells than rural areas, otherwise a single cell returns thousands of candidates.

### Q5. [Theory] What is a feature flag, and how does it differ from a config value?

A feature flag is a runtime switch that gates a code path — `if (flags.isOn("new-checkout", user))` — letting you deploy code dark and turn it on without redeploying. It differs from plain config in three ways: (1) **targeting** — a flag can be on for 5% of users, internal staff, or a specific tenant, not just globally on/off; (2) **dynamism** — flags change in seconds via a push to clients, while config often requires a restart; (3) **lifecycle** — release flags are temporary and should be cleaned up, whereas config is permanent. The trade-off is added complexity: every flag is a branch that must be tested in both states, and stale flags rot into hidden technical debt.

---

## 🟡 Intermediate (3–7 yrs)

### Q6. [Practical] Design the fan-out for a multi-channel notification service that must handle a 50M-user marketing blast without melting the email provider.

Approach: separate **transactional** (low-latency, must-deliver: OTPs, receipts) from **bulk/marketing** (high-volume, throttle-tolerant) at the queue level. The blast goes to a bulk pipeline:

```
Campaign (50M users) → [Audience resolver] → shard into batches of ~1k
        → Kafka topic (partitioned by user_id) → email workers (autoscaled)
        → [Token-bucket rate limiter per provider] → SES (e.g. 14k msg/s cap)
        → status events back to Kafka → analytics/aggregation
```

Trade-offs and production decisions:
- **Rate limiting is mandatory**: SES/SendGrid enforce per-account sending quotas; exceeding them gets you throttled or blacklisted. Use a distributed token bucket (Redis) so all workers share one budget.
- **Idempotency**: key each send by `campaignId:userId`; a worker crash + Kafka replay must not double-send.
- **Backpressure**: if the provider 429s, workers pause consuming (or reduce concurrency) rather than spinning retries that worsen the overload.
- **Preference checks at send time, not enqueue time**: a user may unsubscribe mid-blast; re-validate consent in the worker. Skipping this is both a UX failure and, under GDPR/CAN-SPAM, a legal one.
- **Observability**: track delivered/bounce/complaint rates; a spiking complaint rate must auto-pause the campaign to protect sender reputation.

### Q7. [Theory] Compare the multi-tenancy isolation models for a SaaS platform. When do you pick each?

Three canonical models, in increasing isolation:

```
1. Shared DB, shared schema   → tenant_id column on every table
2. Shared DB, schema-per-tenant → one schema/namespace per tenant
3. DB-per-tenant (silo)        → fully separate database/instance
```

- **Shared schema (pooled)**: cheapest, densest, easiest to operate at scale (one migration, one connection pool). Risk: a missing `tenant_id` predicate leaks data across tenants — enforce with row-level security (Postgres RLS) or a mandatory tenant filter in the ORM. Best for many small tenants (B2C-ish SaaS, free tiers).
- **Schema-per-tenant**: middle ground; cleaner blast-radius and per-tenant backup/restore, but thousands of schemas strain migrations and connection management.
- **DB-per-tenant (silo)**: strongest isolation, easiest to meet "your data never touches another customer's," supports per-tenant encryption keys and compliance (HIPAA, FedRAMP), and lets a noisy/large tenant be moved to its own hardware. Cost: operationally heavy — migrations and monitoring across N databases.

The mature pattern is **hybrid/tiered**: pooled for free/SMB tenants, siloed for enterprise tenants who pay for and demand isolation. Pick based on the *isolation/compliance demands of your highest tier* and the *cost ceiling of your lowest tier*.

### Q8. [Practical] Design a distributed cron / job scheduler that guarantees a job runs even if a scheduler node dies.

Requirements: schedule one-shot and recurring jobs, run each due job *at least once* (ideally exactly once), survive node failure, and scale horizontally.

Approach:
```
[API] → persist job (next_run_at, cron_expr, status) in DB
[Scheduler workers] poll "due" jobs:
   SELECT ... WHERE next_run_at <= now AND status='READY'
   FOR UPDATE SKIP LOCKED  -- claim atomically, no two workers grab it
→ enqueue to execution queue → executor runs → on success, compute next_run_at
```

Hardest parts and choices:
- **Avoiding duplicate execution under partition**: `SELECT ... FOR UPDATE SKIP LOCKED` (Postgres/MySQL 8) lets many workers safely claim disjoint jobs. Alternatively, a leader (via ZooKeeper/etcd lease) owns scheduling — simpler reasoning, but the leader is a bottleneck and single point of failure during failover.
- **Missed-run / catch-up policy**: if the system was down when a job was due, do you skip, run once, or backfill all missed runs? This must be an explicit per-job policy (`misfire = FIRE_NOW | SKIP | BACKFILL`).
- **At-least-once vs exactly-once**: true exactly-once is impossible across a network; make *job handlers idempotent* and treat the scheduler as at-least-once. Track an execution token so the executor can dedupe.
- **Tech**: Quartz (clustered) for JVM monoliths; for scale, Temporal/Cadence (durable workflows with built-in retries and timers), or a custom DB-claim model. Kubernetes CronJob handles container-level scheduling but lacks rich misfire/idempotency semantics.

### Q9. [Coding] Implement a thread-safe, distributed-friendly token-bucket rate limiter (used by the notification workers in Q6).

Problem: allow up to `capacity` tokens, refilling at `refillPerSec`; `tryAcquire()` returns true if a token was available. Show the in-process version; note the Redis variant for the distributed case.

```java
public final class TokenBucket {
    private final long capacity;
    private final double refillPerSec;
    private double tokens;
    private long lastRefillNanos;

    public TokenBucket(long capacity, double refillPerSec) {
        this.capacity = capacity;
        this.refillPerSec = refillPerSec;
        this.tokens = capacity;
        this.lastRefillNanos = System.nanoTime();
    }

    public synchronized boolean tryAcquire() {
        refill();
        if (tokens >= 1) { tokens -= 1; return true; }
        return false;
    }

    private void refill() {
        long now = System.nanoTime();
        double elapsedSec = (now - lastRefillNanos) / 1_000_000_000.0;
        tokens = Math.min(capacity, tokens + elapsedSec * refillPerSec);
        lastRefillNanos = now;
    }
}
```

- **Time**: O(1) per `tryAcquire`. **Space**: O(1) per bucket.
- **Distributed note**: per-process buckets don't share a global budget. For a shared provider quota, run the same logic inside a **Redis Lua script** (atomic read-modify-write of `tokens` + `last_refill` keyed by provider), so all workers across all nodes deplete one bucket. The trade-off is a Redis round-trip per request; batch by acquiring N tokens at once for hot paths.
- **Edge cases**: clock skew across nodes (use Redis server time, not local time), the first call after a long idle should not over-credit beyond `capacity` (the `Math.min` guards this), and `refillPerSec < 1` needs the fractional-token accumulation shown.

### Q10. [Theory] In a feature-flag/experimentation platform, how do you guarantee a user gets a *consistent* bucket across requests and devices?

Use **deterministic hashing** rather than random assignment. The bucket for a user in an experiment is `hash(experimentSalt + userId) % 10000`, mapped to a percentage range. Because the hash is pure, the same `userId` always lands in the same bucket on any server, any device, with no shared state lookup — critical so a user doesn't flip between control and treatment mid-session (which both ruins UX and corrupts the experiment's statistics). The per-experiment salt ensures a user isn't correlated across experiments (otherwise the same users always get treatment, biasing results). Trade-off: changing the salt or the percentage *re-buckets* users, so once an experiment is live you only ever *increase* rollout monotonically; you never shrink or reshuffle.

```
userId=42, exp="checkout_v2", salt="a7f"
bucket = murmur3("a7f:42") % 10000  → 3174
ranges: control [0,5000)  treatment [5000,10000)  → control
```

### Q11. [Coding] Implement the deterministic bucketing from Q10, including a clean rollout-percentage gate.

```java
import java.nio.charset.StandardCharsets;

public final class Experiment {
    private final String salt;            // per-experiment, stable
    private final int treatmentBps;       // basis points out of 10000

    public Experiment(String salt, int treatmentPercent) {
        this.salt = salt;
        this.treatmentBps = treatmentPercent * 100;
    }

    public boolean inTreatment(String userId) {
        int bucket = bucket(userId);
        return bucket < treatmentBps;
    }

    int bucket(String userId) {
        // 32-bit FNV-1a hash — cheap, deterministic, language-portable.
        String key = salt + ":" + userId;
        int h = 0x811c9dc5;
        for (byte b : key.getBytes(StandardCharsets.UTF_8)) {
            h ^= (b & 0xff);
            h *= 0x01000193;
        }
        return Math.floorMod(h, 10000); // 0..9999
    }
}
```

- **Time**: O(L) where L = key length. **Space**: O(1).
- **Multiple approaches**: FNV is shown for clarity; production uses **MurmurHash3** or **xxHash** for better distribution — uniformity matters because skew biases the experiment. Some platforms (e.g. an internal Optimizely/LaunchDarkly-style service) layer a "mutually exclusive groups" concept so two experiments touching the same surface never overlap.
- **Edge cases**: `Math.floorMod` (not `%`) avoids negative buckets from int overflow. For anonymous users, bucket on a stable device/cookie ID; reconcile when they log in. Increasing `treatmentPercent` keeps already-bucketed treatment users in treatment because their bucket value is unchanged — the gate just admits more.

### Q12. [Practical] Design Dropbox/Google-Drive-style file sync. What are the two hardest challenges?

Requirements: upload large files efficiently, sync across a user's devices, dedupe storage, handle offline edits and conflicts.

Core design: split files into **content-addressed chunks** (e.g. 4 MB, hashed with SHA-256). The client computes chunk hashes, asks the server which it lacks, and uploads only the missing ones — so a 2 GB file with a one-line change re-uploads ~4 MB, not 2 GB. Metadata (file → ordered list of chunk hashes) lives in a separate **metadata service**; chunks live in blob storage (S3). Dedup is automatic: identical chunks across users/files store once.

```
File → [4MB chunks] → SHA-256 each → "have these?" → upload missing → S3
Metadata DB: file_id → [chunkHash1, chunkHash2, ...], version, device
Notify other devices via long-poll / push to pull new metadata
```

Two hardest challenges:
1. **Conflict resolution for concurrent edits**: two offline devices edit the same file. Last-writer-wins silently loses data. Dropbox's pragmatic answer is to keep both: create `file (conflicted copy from Device B)` and let the user merge. Detect conflicts with version vectors per device, not wall-clock time.
2. **Efficient delta sync of *changed* content**: fixed-size chunking breaks on insertions (inserting one byte shifts every subsequent chunk boundary, defeating dedup). **Content-defined chunking** (rolling hash / Rabin fingerprint) sets boundaries based on content, so an insertion only changes the local chunk. This is the key trick behind rsync and Dropbox's block algorithm.

### Q13. [Theory] For a metrics/monitoring platform, why are time-series databases preferred over a generic RDBMS, and what does cardinality have to do with cost?

Metrics are append-heavy, timestamp-ordered, rarely updated, and queried as aggregations over time windows — a workload a generic B-tree RDBMS handles poorly. Time-series DBs (Prometheus, InfluxDB, TimescaleDB, M3, VictoriaMetrics) optimize for it: columnar/delta-of-delta compression shrinks storage ~10x, and time-partitioned storage makes "last 1h of CPU" a contiguous scan. **Cardinality** is the number of unique time series = the cross-product of all label/tag values (e.g. `http_requests{method, status, endpoint, pod}`). It's the dominant cost driver: a high-cardinality label like `user_id` or `request_id` explodes series count into the millions, blowing up memory and index size — this is the classic way teams accidentally take down Prometheus. The discipline is to keep labels bounded and push high-cardinality data into logs/traces instead.

### Q14. [Practical] A recommendation system needs to serve "you may also like" in <100ms at the top of a high-traffic page. How do you architect it?

Approach: split into **offline candidate generation** and **online ranking** (the standard two-stage retrieval/ranking pattern).

```
Offline (batch/streaming):
  user/item events → feature store + embedding training (matrix factorization,
  two-tower model) → precompute candidate sets / ANN index (FAISS, ScaNN)

Online (<100ms):
  request → fetch user embedding + context → ANN search top-N candidates
          → lightweight ranker (gradient-boosted trees / small NN) reorders
          → business rules (dedupe, diversity, in-stock) → return top-K
```

Trade-offs and production decisions:
- **Two-stage is non-negotiable at scale**: you cannot score millions of items per request in 100ms. Retrieval narrows to ~hundreds (fast, approximate); ranking is precise on that small set.
- **Freshness vs. latency**: fully real-time personalization is expensive; most systems precompute heavy embeddings offline and only blend in real-time signals (last few clicks) at request time.
- **Cold start**: new users/items have no history — fall back to popularity, content-based features, or contextual bandits that explore.
- **Feedback loops**: recommending only what's already popular starves the long tail; inject exploration and measure with online A/B tests, not just offline AUC, because offline metrics often don't predict live engagement.

---

## 🟠 Advanced (8–12 yrs)

### Q15. [Theory] Explain CRDTs vs. OT for a real-time collaborative editor. Why did Figma and modern systems lean toward CRDTs?

Both solve concurrent editing convergence: every client must reach the same final document regardless of operation order. **Operational Transformation (OT)** — used by Google Docs — transforms incoming operations against concurrent ones (insert-at-position-5 must shift if a concurrent insert happened earlier). It's bandwidth-efficient but the transformation functions are notoriously hard to get right, and most OT systems require a central server to order operations. **CRDTs (Conflict-free Replicated Data Types)** assign each character/element a globally-unique, totally-ordered ID (e.g. fractional indices or a tree of positions), so concurrent operations are *commutative by construction* — merge in any order and converge with no transformation logic and no central authority required.

The trade-off: classic CRDTs carry per-element metadata (tombstones for deletes, position IDs) that can bloat the document, though modern designs (Yjs, Automerge, Figma's approach) compress this heavily. The industry leaned CRDT because it's far easier to reason about, supports offline/P2P editing, and avoids the central-server ordering requirement — Figma uses a CRDT-inspired model with a server as the source of truth for last-writer-wins on individual properties, which is simpler than a full OT engine.

```
CRDT insert: each char = (value, uniqueId with fractional position)
  "A_id1.0"  insert "B" between → "B_id1.5"  → order by id, deterministic merge
OT insert:   op(insert 'B' @ pos1) must be transformed against concurrent op(insert @ pos0)
```

### Q16. [Practical] Design an ad-serving auction system that must pick a winning ad in <50ms per impression at millions of QPS. Walk the request path and the hardest constraint.

Request path (real-time bidding, RTB):
```
impression → [Ad Exchange] sends bid request to N Demand-Side Platforms (DSPs)
   each DSP (in <~30ms): targeting filter → predict CTR/CVR → bid = pCTR × value
   → exchange runs auction (2nd-price / generalized 2nd-price) → winner renders
   → log impression → async: attribution, billing, budget decrement
```

Hardest constraints:
- **The latency budget is brutal and external**: the whole auction including the network round-trip to bidders must finish in ~50–100ms or the impression is lost. Bidders that don't respond in time are dropped — so everything (model inference, feature lookup) is precomputed or cached; you cannot do a DB join on the hot path.
- **Budget pacing without overspend**: a campaign with a \$10k/day budget must not blow it in the first hour, yet checking a global counter per bid at millions of QPS is impossible synchronously. Solution: **probabilistic/throttled pacing** — each bidder node holds a local budget slice, decrements optimistically, and reconciles asynchronously; you accept slight overspend (a fraction of a percent) in exchange for speed. Hard caps use a faster eventually-consistent counter (Redis) with a safety margin.
- **Auction integrity & fraud**: second-price auctions assume truthful bidding; you must detect click fraud and invalid traffic, and ensure billing reconciles with logged impressions (money is involved, so the offline ledger is the source of truth, not the hot path).

### Q17. [Theory] In the distributed scheduler (Q8), how do you handle a job that takes longer than its interval, and how do you prevent "thundering herd" at the top of the hour?

Two classic operational hazards:

**Overlapping runs**: a job scheduled every 5 min that takes 7 min will overlap itself. Define a per-job concurrency policy: `FORBID` (skip the new run if the previous still holds a lease — the safe default), `ALLOW` (let them overlap, for idempotent jobs), or `REPLACE` (kill the old one). Implement with a per-job lock (lease in Redis/etcd) that the executor holds for the run's duration; the scheduler skips claiming a job whose lock is held.

**Thundering herd**: thousands of jobs all configured `0 * * * *` fire at the exact same instant, spiking load and DB contention. Mitigate by **jittering** the actual fire time within a window (add a deterministic offset derived from `hash(jobId) % windowSeconds`), and by sharding job ownership across scheduler nodes so no single node processes all of them. This trades a few seconds of timing precision for smooth, predictable load — almost always the right call for batch work.

### Q18. [Coding] Implement an in-memory version vector to detect concurrent edits in the file-sync system (Q12) — distinguishing "newer" from "conflict."

Problem: given two devices' version vectors, decide if one *descends from* the other (safe fast-forward) or if they *diverged* (conflict needing merge).

```java
import java.util.*;

public final class VersionVector {
    private final Map<String, Long> clock = new HashMap<>();

    public void increment(String deviceId) {
        clock.merge(deviceId, 1L, Long::sum);
    }

    public long get(String deviceId) { return clock.getOrDefault(deviceId, 0L); }

    public enum Relation { EQUAL, ANCESTOR, DESCENDANT, CONCURRENT }

    public Relation compare(VersionVector other) {
        boolean selfGreater = false, otherGreater = false;
        Set<String> ids = new HashSet<>(clock.keySet());
        ids.addAll(other.clock.keySet());
        for (String id : ids) {
            long a = this.get(id), b = other.get(id);
            if (a > b) selfGreater = true;
            else if (a < b) otherGreater = true;
        }
        if (selfGreater && otherGreater) return Relation.CONCURRENT; // conflict!
        if (selfGreater) return Relation.DESCENDANT;
        if (otherGreater) return Relation.ANCESTOR;
        return Relation.EQUAL;
    }
}
```

- **Time**: O(D) where D = number of distinct devices. **Space**: O(D).
- **Why this beats timestamps**: wall-clock comparison declares a winner even when edits were truly concurrent, silently losing data; the version vector *detects* concurrency (`CONCURRENT`) so the system can fork a "conflicted copy" instead of overwriting.
- **Edge cases**: a device that hasn't been seen reads as 0 (handled by `getOrDefault`); vectors grow unbounded with device count — production prunes retired devices and may cap vector size, accepting rare false-conflicts beyond the cap.

### Q19. [Practical] Your feature-flag service is on the hot path of every request. How do you make flag evaluation fast and resilient to the flag service being down?

Approach: **never** make a network call to evaluate a flag on the request path. Use an **SDK + local cache** model (LaunchDarkly/Unleash/OpenFeature pattern):
```
Flag service ──(streaming/SSE or 30s poll)──▶ SDK in-process cache (RAM)
request → flags.isOn("x", ctx)  → pure in-memory eval, microseconds, no I/O
```
- **Resilience**: the SDK evaluates against its last-known-good cache. If the flag service is down, flags keep working at their last values; new clients use a **bootstrap file / default values** baked into the deploy. Every `isOn` call must take a code-supplied default for the worst case (service unreachable on cold start).
- **Consistency**: changes propagate via a streaming connection (Server-Sent Events) so updates land in ~1s, with polling as fallback. Targeting rules (segments, percentages) are evaluated client-side, so the SDK ships the *ruleset*, not per-user answers — this is what keeps it both fast and consistent across servers.
- **Production reality**: instrument flag *evaluations* so you can find stale flags (always-true for 6 months → delete it) and audit who changed what; in regulated environments, flag changes are change-controlled because flipping one can alter system behavior as much as a deploy. Treat a kill-switch flag (disable feature instantly during an incident) as a first-class reliability tool.

### Q20. [Theory] For the metrics platform (Q13), contrast push vs. pull collection and explain how you'd ingest millions of series without losing data during a spike.

**Pull** (Prometheus): the server scrapes targets on an interval. Pros — the server controls load, target health is observable (a failed scrape is itself a signal), and there's no client-side buffering to lose. Cons — hard across NAT/firewalls, awkward for short-lived batch jobs (they may die before being scraped, needing a push-gateway). **Push** (StatsD, OpenTelemetry, InfluxDB): clients send metrics out. Pros — works for ephemeral jobs and serverless, firewall-friendly. Cons — the server can be overwhelmed by a flood, and you need client-side batching/backpressure.

To survive ingestion spikes without data loss: put a **buffer/queue (Kafka)** in front of the storage layer so producers never block on slow storage, and the storage tier consumes at its own pace. Apply **sharding by series hash** so load spreads across ingester nodes, and **remote-write with WAL + retry** on the agent side so a brief storage outage replays rather than drops. Finally, **downsample and tier**: keep 10s-resolution data for hours, roll up to 5-min for weeks, and expire raw data — full-resolution retention forever is the bill that kills monitoring platforms.

### Q21. [Coding] Implement a fixed-window-into-sliding rate counter used to enforce per-tenant API quotas in the multi-tenant SaaS (Q7), avoiding the boundary-spike problem of naive fixed windows.

Problem: naive fixed windows allow 2x the limit across a boundary (full quota at 0:59 + full quota at 1:00). Implement a **sliding-window-counter** approximation that's cheap enough for Redis.

```java
public final class SlidingWindowCounter {
    private final long limit;
    private final long windowMs;
    // For one key; in prod these two counters live in Redis hashed by tenantId.
    private long currWindowStart;
    private long currCount;
    private long prevCount;

    public SlidingWindowCounter(long limit, long windowMs) {
        this.limit = limit; this.windowMs = windowMs;
    }

    public synchronized boolean allow(long nowMs) {
        long windowStart = (nowMs / windowMs) * windowMs;
        if (windowStart != currWindowStart) {
            // advanced into a new window: shift current → previous
            prevCount = (windowStart - currWindowStart == windowMs) ? currCount : 0;
            currCount = 0;
            currWindowStart = windowStart;
        }
        double elapsedFraction = (nowMs - currWindowStart) / (double) windowMs;
        double weightedPrev = prevCount * (1.0 - elapsedFraction);
        if (weightedPrev + currCount + 1 <= limit) {
            currCount++;
            return true;
        }
        return false;
    }
}
```

- **Time**: O(1). **Space**: O(1) per key (two counters), vs. O(N) for a true log-of-timestamps sliding window — this is the key cost win.
- **Why this approach**: a true sliding-window log is exact but stores every request timestamp (memory blows up under load); the weighted-counter estimate is within a few percent and uses two integers — Cloudflare popularized exactly this for edge rate limiting.
- **Edge cases**: if more than one full window has elapsed, `prevCount` resets to 0 (the gap check handles it); under heavy concurrency the Redis version must do the read-shift-increment atomically in a Lua script, or two requests race the window roll-over.

### Q22. [Practical] In multi-tenant SaaS, how do you stop one "noisy neighbor" tenant from degrading everyone else?

This is the central operational risk of a pooled model. Defenses, layered:
- **Per-tenant rate limits & quotas** (see Q21) at the API gateway — a tenant that floods the API is throttled, not allowed to consume the whole pool.
- **Resource governance in shared stores**: per-tenant connection-pool caps, query timeouts, and limits on result-set size so one tenant's runaway query can't pin the database. In Postgres, statement timeouts and per-tenant work-mem limits help.
- **Workload isolation by tier**: route the heaviest enterprise tenants to dedicated shards/cells (the "cell-based architecture" / bulkhead pattern) so a problem in one cell can't cascade. AWS and Salesforce both use cell/pod architectures precisely so a failure or hot tenant is contained to a fraction of customers.
- **Fairness scheduling**: for async/batch work, use weighted fair queueing so a tenant submitting 1M jobs doesn't starve a tenant submitting 10.
- **Observability per tenant**: attribute CPU, queries, and latency *by tenant_id* so you can detect and act on the noisy neighbor before customers complain. What you can't measure per-tenant, you can't isolate.

### Q23. [Theory] How would you make the notification service deliver "exactly once" from the user's perspective across retries, crashes, and provider duplicates?

True exactly-once delivery is unachievable end-to-end (the network can always drop the ack), so you engineer **effectively-once** via idempotency and dedup at every hop. (1) The ingest API assigns a stable `notificationId`; producers may also send their own idempotency key so retried *requests* collapse to one notification. (2) The queue is at-least-once (Kafka/SQS redeliver on consumer crash), so the worker must dedupe — it records `notificationId` in a dedup store (Redis with TTL, or a unique constraint in the DB) before/after the provider call. (3) The provider call passes the `notificationId` as the provider's idempotency key where supported (SES/Twilio support this), so a worker retry doesn't physically send twice. (4) Status callbacks (delivered/bounced) are themselves deduped because providers redeliver webhooks. The honest framing in an interview: "at-least-once transport + idempotent processing keyed on a stable ID = effectively-once," and you call out where genuine duplicates can still leak (e.g., provider-side double send) and how monitoring catches them.

---

## 🔴 Expert (15+ yrs)

### Q24. [Theory] Across all these systems, articulate the recurring architectural patterns a staff engineer should name explicitly in a design interview.

A handful of patterns recur and signal seniority when named deliberately:
- **Decouple write from delivery via a durable log** (Kafka) — notification fan-out, metrics ingestion, ad event logging, scheduler execution all use a queue so producers never block on slow consumers and replay gives at-least-once.
- **Two-stage retrieve-then-rank** — recommendations and ad selection both narrow a huge candidate space cheaply, then score a small set precisely; brute-force scoring is infeasible in the latency budget.
- **Content addressing for dedup** — file sync chunks by hash; the same idea underlies Git and container layers.
- **Deterministic hashing for stateless consistency** — experiment bucketing and consistent-hash sharding both avoid a stateful "who got what" lookup.
- **Cell/bulkhead isolation** — multi-tenant SaaS, ad budget pacing, and notification channel separation all bound blast radius so one failure/hot-spot is contained.
- **Idempotency + at-least-once over exactly-once** — scheduler, notifications, and ad billing all accept at-least-once transport and push correctness into idempotent handlers.

Naming the pattern and *why it fits this problem* is what separates a staff answer from a senior one.

### Q25. [Practical] You're asked to build a 99.99% available notification platform spanning three cloud regions. Walk through the trade-offs you'd present to leadership.

I'd frame it as availability vs. cost vs. consistency, with explicit numbers. 99.99% = ~52 min downtime/year, which forces active-active multi-region (active-passive failover alone usually can't hit it because failover itself takes minutes and is risky).

Approach and trade-offs:
- **Active-active across 3 regions**: ingest accepts writes in any region into a region-local queue; this maximizes availability and locality but creates **duplicate-suppression challenges** — a user might be addressable from two regions, so the dedup store must be either globally consistent (slower, e.g. a CRDT counter or a quorum store) or you accept rare cross-region duplicates and monitor. I'd recommend partitioning users to a "home region" for dedup ownership, falling over only on regional loss — bounded duplicates beat global-consistency latency on the hot path.
- **Provider redundancy**: a single email/SMS provider is a SPOF independent of your cloud. Multi-provider with health-based failover is required for four-nines — but it complicates idempotency keys and reputation management.
- **Cost honesty**: active-active roughly doubles infra and adds cross-region egress; I'd quantify it and offer a tiered SLA — four-nines for transactional/OTP traffic, three-nines for marketing — so we don't pay four-nines cost for traffic that doesn't need it.
- **The behavioral point I'd make to leadership**: define the SLO from the *user's* perspective (OTP delivered within 10s) and an error budget, so the team can trade reliability work against features rationally rather than chasing nines for their own sake.

### Q26. [Behavioral] You inherit a system where 400+ feature flags have accumulated, many years old, and engineers fear deleting any. How do you lead the cleanup?

I treat this as a socio-technical debt problem, not just a code chore. First, **make the cost visible**: instrument flag evaluations and surface "flags not toggled in 90 days" and "flags at 100%/0% for 6 months" on a dashboard — data, not opinion, drives buy-in. Second, **establish a lifecycle policy going forward** so the problem stops growing: every release flag gets an owner and an expiry/cleanup ticket created *when the flag is created*, enforced in code review and via a linter that flags flags older than N days. Third, **de-risk deletion**: for each candidate, confirm via telemetry it's been fully on/off, search for references, ship the code path unconditionally, deploy, then remove the flag definition a release later — small, reversible steps, not a big-bang purge. Fourth, **distribute the work**: assign clusters of flags to owning teams with a deadline rather than one heroic engineer. The leadership lesson I'd articulate: the fear is rational because the flags lack ownership and observability — fix the *system* (lifecycle + telemetry) and the cleanup becomes routine instead of scary.

### Q27. [Theory] For the collaborative editor, how do you reconcile a CRDT's eventual consistency with features that need a strong global invariant (e.g., a unique title, or access-control changes)?

CRDTs guarantee convergence, not invariant preservation — they'll happily converge to a state that violates a business rule (two docs with the same "unique" title, or an edit that should have been blocked by a permission revoked concurrently). The mature answer is **hybrid consistency**: use the CRDT for the high-frequency, conflict-tolerant content (text, cursor positions) where availability and offline editing matter, but route invariant-critical operations through a **strongly-consistent control plane** (a linearizable store / consensus group) that serializes them. Title uniqueness and ACL changes go to a Raft-backed metadata service that can reject; document body edits go to the CRDT. This is exactly Figma's split: a CRDT-like model for the document tree, with the server as authoritative arbiter for properties needing last-writer-wins and for permissions. The trade-off you must state: you're paying latency/availability for the operations that genuinely need it, and accepting that most operations don't — getting that boundary right is the design judgment being tested.

### Q28. [Practical] Design the budget/billing reconciliation for the ad system (Q16) so the company neither overspends advertiser budgets nor under-bills. What's the consistency model?

This is a money problem, so the design centers on an **authoritative offline ledger** with an eventually-consistent fast path:
```
Hot path (per bid, <50ms): local pacing slice decremented optimistically (Redis/in-mem)
Stream:  every won impression → Kafka → exactly-once-ish consumer →
         append to immutable billing ledger (the source of truth)
Reconcile: periodic job compares fast-path spend vs. ledger; corrects pacing;
           flags invalid traffic before it's billed
```
Key decisions and trade-offs:
- **The hot path is eventually consistent and may overspend by a bounded margin**; you cannot do a globally-consistent budget check per bid at millions of QPS. You set the local slices conservatively (e.g., stop bidding at 98% to leave headroom) and accept sub-percent overspend, which is cheaper than the latency/throughput cost of synchronous global counters.
- **The ledger is the truth, the hot path is an estimate** — billing reconciles against the ledger, never the in-memory counters, and invalid-traffic detection runs *before* finalizing charges so advertisers aren't billed for fraud.
- **Idempotency on impression logging**: an impression replayed from Kafka must not double-bill — dedupe on a unique impression ID at the ledger writer.
- **Auditability/security**: the ledger is append-only and immutable for dispute resolution and compliance; advertisers and finance both reconcile against it. I'd explicitly call out that the "fast path can be approximate, the ledger must be exact" split is the heart of every large-scale metering/billing system.

### Q29. [Theory] How do you evolve the schema of events flowing through Kafka (used by notifications, metrics, and ad logging) across years without breaking dozens of consumers?

Long-lived event pipelines die from schema rigidity, so you design for evolution up front. Use a **schema registry** (Confluent Schema Registry) with **Avro or Protobuf** rather than ad-hoc JSON, and enforce a **compatibility policy** — typically *backward* compatibility (new schema can read old data) so consumers can upgrade before producers, or *full* compatibility for the strictest guarantees. The rules that keep you safe: only add **optional fields with defaults**, never remove or repurpose a field, never change a field's type or semantics — to "remove" a field you deprecate it and stop populating it, leaving it readable. For breaking changes you publish to a **new topic/version** and run dual-write/dual-read during migration rather than mutating in place. The staff-level point: the registry *enforces* compatibility at produce time (a bad schema is rejected before it poisons the topic), turning a coordination nightmare across dozens of teams into an automated contract — and you version the *contract*, treating event schemas with the same rigor as a public API.

### Q30. [Practical] Security review: across these systems, where are the most-missed security and privacy risks a staff engineer should proactively raise?

The ones juniors miss and staff must surface:
- **Multi-tenant data leakage** (Q7): a single missing `tenant_id` predicate cross-exposes customer data — the worst SaaS breach class. Defense-in-depth with Postgres RLS so the database, not just app code, enforces isolation; pen-test tenant isolation explicitly.
- **Notification content as a data-exfiltration / PII surface**: OTPs and reset links in SMS/email are phishing and interception targets — keep tokens short-lived, never log message bodies, and scrub PII from logs and the metrics platform (high-cardinality `user_id` labels can leak identities into dashboards).
- **Feature flags as an attack/abuse vector**: a flag that gates security controls (e.g., disables auth checks) must be change-controlled and audited; an attacker or insider flipping a flag is as dangerous as a deploy.
- **Ad/recommendation feedback loops & model abuse**: training on unfiltered user input invites poisoning; auction systems attract click fraud — both need anomaly detection and the immutable ledger (Q28) as ground truth.
- **File sync content addressing**: chunk hashes are content-derived, so a hash collision or a server trusting client-claimed hashes without verification could let one user reference another's chunks ("dedup side-channel") — verify ownership, encrypt chunks per-tenant for sensitive data, and never let "I already have this hash" leak existence of another user's content.

---

## ✅ Key Takeaways

- **Pick the right consistency per operation, not per system**: the hot path (ad bids, budget pacing, CRDT text edits) can be eventually consistent and approximate; money, ACLs, and uniqueness need a strongly-consistent control plane. The skill is drawing that boundary.
- **At-least-once transport + idempotent, ID-keyed handlers = effectively-once.** True exactly-once is a myth; design for it honestly (notifications, scheduler, ad billing).
- **Two-stage retrieve-then-rank** is the universal answer to "score millions of things in <100ms" (recommendations, ads).
- **Deterministic hashing** gives stateless, cross-server consistency for experiment bucketing and sharding — no lookup required.
- **Content-addressed chunking + content-defined boundaries** is what makes file sync (and Git) efficient; fixed-size chunking breaks on insertions.
- **Cardinality is the cost driver** in metrics platforms; bounded labels keep TSDBs alive.
- **Cell/bulkhead isolation** and **per-tenant quotas** are how you tame noisy neighbors in multi-tenant SaaS.
- **Schema registry + backward-compatible Avro/Protobuf** is how event pipelines survive years of consumer churn.

## ⚠️ Common Pitfalls

- Using **wall-clock timestamps for conflict resolution** (file sync, collab editor) instead of version vectors/CRDTs — silently loses concurrent edits.
- **Synchronous global counters on a million-QPS hot path** (ad budgets, rate limits) — kills latency; use local slices + async reconciliation.
- Putting **high-cardinality labels** (`user_id`, `request_id`) on metrics — explodes the TSDB and leaks PII into dashboards.
- **No flag lifecycle** — flags accumulate into hundreds of untestable branches; create the cleanup ticket when you create the flag.
- **Single notification/SMS provider** assumed reliable — it's a SPOF independent of your cloud; multi-provider failover is required for high SLAs.
- **Fixed-size chunking** for file sync — a one-byte insert defeats dedup; use rolling-hash content-defined chunking.
- **Forgetting backpressure** in fan-out — retries against a throttled provider amplify the overload instead of relieving it.
- **Trusting client-claimed content hashes** in dedup systems — enables a cross-user existence side-channel; verify ownership.

## 📚 Further Reading

- *Designing Data-Intensive Applications* — Martin Kleppmann (the canonical text on consistency, replication, stream processing, and the trade-offs behind every system here).
- *Database Internals* — Alex Petrov (storage engines and distributed systems internals; useful for the TSDB and scheduler claims).
- Google SRE Book & SRE Workbook — `sre.google/books` (SLOs, error budgets, the four-nines discussion in Q25).
- Martin Kleppmann's CRDT research & the Automerge/Yjs docs — for the collaborative-editor section (`automerge.org`, `docs.yjs.dev`).
- Confluent's Schema Registry & "Schema Evolution and Compatibility" docs — for the event-evolution answer (Q29).
- Building Multi-tenant SaaS on AWS (AWS SaaS Factory) and Salesforce/Shopify "pod/cell architecture" engineering write-ups — for tenancy and isolation (Q7, Q22).
