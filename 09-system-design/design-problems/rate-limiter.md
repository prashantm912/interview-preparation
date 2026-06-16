# Design a Distributed Rate Limiter

A worked system-design problem: build a service that throttles request traffic across a fleet of API servers, enforcing per-user, per-API, and global limits with low latency and predictable accuracy.

[← Back to master index](../../README.md) | [← System Design index](../README.md)

---

## 1. Requirements

A **rate limiter** sits in front of (or inside) your services and decides, for every incoming request, whether to *allow* it or *reject* it (typically HTTP `429 Too Many Requests`). It protects backends from abuse, accidental retry storms, noisy neighbors, and cost overruns (e.g., expensive LLM or payment endpoints).

### Functional requirements

- **Enforce a configurable limit** expressed as `N requests per window` (e.g., 100 req/min, 10 req/sec, 5000 req/day).
- **Multiple scopes / dimensions**, evaluated independently and combinable:
  - Per **user / API key / tenant** (most common).
  - Per **IP address** (anti-abuse, unauthenticated traffic).
  - Per **API endpoint / route** (e.g., `/login` is stricter than `/search`).
  - **Global** (protect a shared downstream like a database or third-party API).
- **Different rules per tier** — a free user gets 10 req/s, an enterprise user gets 1000 req/s.
- **Return standard signaling** to clients: `429` status, `Retry-After`, and `X-RateLimit-*` headers so well-behaved clients can self-throttle.
- **Fail open or fail closed** — configurable behavior when the limiter store is unavailable.

### Non-functional requirements

| Property | Target |
|---|---|
| **Added latency** | p99 < 1–2 ms (it is on the hot path of *every* request) |
| **Availability** | 99.99%+ — the limiter must not become a single point of failure |
| **Throughput** | Must handle the *full* request rate of the system it protects (millions of req/s aggregate) |
| **Accuracy** | "Good enough" — over-counting/under-counting by a few percent at window edges is usually acceptable; hard guarantees cost latency |
| **Consistency** | Eventual / bounded staleness is acceptable for soft limits; strong consistency only for hard quotas (billing) |

### CAP positioning

A rate limiter is a classic **AP-leaning** system. During a network partition you almost always prefer **availability** — keep serving traffic, possibly with slightly looser enforcement — over rejecting all traffic to guarantee perfect counting. The exception is **hard billing quotas** (e.g., "you paid for exactly 1M API calls"), where you may accept higher latency and CP behavior for the small subset of metered endpoints.

### Clarifying questions a candidate should ask

1. **What are we protecting** — our own backend, a paid downstream, or enforcing a billing contract? (Determines accuracy vs. availability trade-off.)
2. **Soft or hard limits?** Can we occasionally allow a few extra requests, or is over-allowance unacceptable?
3. **Where does it run** — API gateway, a sidecar, a shared service, or inside each app? (Placement drives the whole design.)
4. **Scale** — peak QPS, number of distinct keys (users/IPs), and number of distinct rules?
5. **Granularity** — per-second, per-minute, per-day? Do we need burst allowance (token bucket) or smooth rate (leaky bucket)?
6. **What feedback do clients expect** — just `429`, or full `X-RateLimit-*` headers?
7. **Multi-region?** Are limits global across regions or per-region?

---

## 2. Capacity Estimation

Let's anchor on a concrete scenario: a public API platform serving **1 million requests/second at peak**, with **50 million distinct API keys** (active over a rolling window), and rules at second/minute/day granularity.

### QPS / throughput

- Peak request rate the limiter must evaluate: **1,000,000 req/s**.
- Every request requires **at least one check** (often 2–3, one per dimension: key + endpoint + global). Assume an average of **2.5 store operations per request**.
- Store operations/sec ≈ `1,000,000 × 2.5 = 2.5M ops/s`.
- A single Redis node handles ~**100k–150k ops/s** comfortably with pipelining. So we need on the order of `2.5M / 100k ≈ 25` Redis shards just for raw throughput (round up for headroom and replicas).

### Memory / storage

Each active key needs a counter (or token-bucket state) per rule. Token-bucket state is roughly: `key string (~40 B) + tokens (8 B) + last_refill_ts (8 B) + overhead (~50 B) ≈ ~106 B`, call it **~150 bytes per (key, rule)** including Redis hash overhead.

- 50M keys × 3 rules (sec/min/day) × 150 B ≈ `50M × 3 × 150 = 22.5 GB`.
- With replication factor 2 and headroom: **~50–60 GB** of RAM across the cluster. Each of our ~25–30 shards holds ~2 GB — trivial for modern Redis nodes (16–64 GB).
- TTLs are critical: every counter expires when its window elapses (e.g., a per-second counter has a 1–2 s TTL), so memory is naturally bounded and dominated by *active* keys, not the full 50M registered keys.

### Bandwidth

- Each check is tiny: request key (~50 B) + response (~20 B) ≈ 70 B over the wire to Redis.
- `2.5M ops/s × 70 B × 2 (req+resp) ≈ 350 MB/s ≈ 2.8 Gbps` of internal traffic. Spread across 25–30 shards that's ~100 Mbps per shard — comfortable.

### Takeaway

The system is **CPU/throughput-bound, not storage-bound**. The interesting engineering is in *minimizing latency and round trips* (local caching, pipelining, atomic Lua), and in *sharding the counter space*, not in storing data.

---

## 3. API Design

The limiter exposes both an **internal check API** (called by gateways/services) and a **management API** (for configuring rules).

```http
# --- Internal: the hot-path check ---
POST /v1/ratelimit/check
Content-Type: application/json

{
  "keys": [
    { "scope": "api_key", "id": "ak_8f3...", "rule": "default_tier_free" },
    { "scope": "endpoint", "id": "POST:/v1/search",   "rule": "search_endpoint" },
    { "scope": "global",   "id": "downstream_db",      "rule": "db_global" }
  ],
  "cost": 1            // weighted cost; e.g. an expensive query may cost 5 tokens
}

# Response when allowed
HTTP/1.1 200 OK
{ "allowed": true, "limiting_scope": null }
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 87
X-RateLimit-Reset: 1718539200      # epoch seconds when window resets

# Response when throttled
HTTP/1.1 429 Too Many Requests
Retry-After: 7                     # seconds; honor this, clients!
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 0
X-RateLimit-Reset: 1718539207
{ "allowed": false, "limiting_scope": "api_key" }
```

In practice the check is rarely a separate HTTP call (that would add a network hop). It is an **in-process library call** on the gateway that talks directly to Redis, or an **Envoy/NGINX rate-limit filter**. The contract above is the logical interface.

```http
# --- Management: configure rules (control plane) ---
PUT /v1/admin/rules/{rule_id}
{
  "algorithm": "token_bucket",
  "capacity": 100,          // bucket size = max burst
  "refill_rate": 100,       // tokens added per refill_period
  "refill_period_ms": 60000,// → 100 tokens/minute
  "scope": "api_key"
}

GET /v1/admin/rules/{rule_id}
DELETE /v1/admin/rules/{rule_id}
```

**Idempotency / semantics note:** the check is *not* idempotent — each call consumes budget. Gateways must call it exactly once per request and treat store errors per the fail-open/closed policy, never blindly retrying a check (a retry would double-count).

---

## 4. Data Model

The hot-path state is intentionally minimal and lives in an **in-memory store (Redis)**, not a relational DB.

### Counter / bucket state (Redis)

For **token bucket**, store a hash per (key, rule):

```
KEY:   rl:{scope}:{id}:{rule}          e.g. rl:api_key:ak_8f3:default_tier_free
TYPE:  HASH
FIELDS:
  tokens        -> 87.0     (current available tokens, float)
  last_refill   -> 1718539193456  (epoch ms of last refill)
TTL:   set to a small multiple of the window (e.g. 2× refill_period) so idle keys self-evict
```

For **sliding window counter**, store two integer counters (current + previous window) with TTLs, or a `ZSET` of timestamps for the exact **sliding window log**.

### Rules / configuration (SQL or a config store)

Rules change rarely and are read constantly, so they live in a small **relational table** (Postgres) as the source of truth, pushed to every gateway via a config service and cached in memory. SQL is justified here because rules are low-volume, relational (tiers → rules → overrides), need transactional edits, and benefit from constraints.

```sql
CREATE TABLE rate_limit_rules (
  rule_id        TEXT PRIMARY KEY,
  scope          TEXT NOT NULL,        -- api_key | ip | endpoint | global
  algorithm      TEXT NOT NULL,        -- token_bucket | sliding_window | ...
  capacity       INT  NOT NULL,        -- burst / window max
  refill_rate    INT  NOT NULL,
  refill_period_ms BIGINT NOT NULL,
  fail_mode      TEXT NOT NULL DEFAULT 'open',  -- open | closed
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE key_rule_overrides (   -- e.g. enterprise customer gets a custom limit
  key_id   TEXT NOT NULL,
  rule_id  TEXT NOT NULL REFERENCES rate_limit_rules(rule_id),
  PRIMARY KEY (key_id, rule_id)
);
```

**Why NoSQL/in-memory for counters but SQL for rules?** Counters are high-churn, ephemeral, key-value, and latency-critical — a perfect fit for Redis with TTLs. Rules are the opposite: tiny, durable, relational, edited transactionally — a perfect fit for Postgres. Using the right store for each axis is itself an interview-worthy decision.

---

## 5. High-Level Architecture

The single most important architectural choice is **placement**: put the limiter at the **API gateway / edge**, as close to the client as possible, so rejected traffic never touches expensive backends.

```
                         Clients (millions)
                               │
                ┌──────────────┼──────────────┐
                ▼              ▼               ▼
        ┌───────────┐  ┌───────────┐   ┌───────────┐
        │ API GW #1 │  │ API GW #2 │ … │ API GW #N │   (Envoy / NGINX / custom)
        │  ┌──────┐ │  │  ┌──────┐ │   │  ┌──────┐ │
        │  │Limiter│ │  │  │Limiter│ │   │  │Limiter│ │   in-process check + L1 cache
        │  │ +L1   │ │  │  │ +L1   │ │   │  │ +L1   │ │
        │  └──┬───┘ │  │  └──┬───┘ │   │  └──┬───┘ │
        └─────┼─────┘  └─────┼─────┘   └─────┼─────┘
              │              │               │
              └──────────────┼───────────────┘
                             ▼
                ┌────────────────────────────┐
                │   Redis Cluster (sharded)   │   atomic Lua INCR/bucket
                │  shard0  shard1 … shardK    │   counters with TTL
                │  (each w/ replica)          │
                └────────────┬───────────────┘
                             │ async, sampled
                             ▼
                ┌────────────────────────────┐
                │  Config Service + Postgres  │  rules (control plane)
                │  Metrics → Prometheus/Kafka │  observability
                └────────────────────────────┘
```

### Component walkthrough

1. **API Gateway with embedded limiter.** Each gateway instance runs the limiter as an in-process filter. On each request it resolves the applicable rules (from its in-memory rule cache) and performs the check. Embedding it here means a throttled request costs almost nothing — no backend, no business logic.

2. **L1 local cache (per gateway).** To avoid a Redis round trip on *every* request, each gateway keeps a small local view of recent counters. This is the core latency optimization and the source of the hardest consistency trade-offs (see Deep Dive 3).

3. **Redis Cluster (shared state).** The source of truth for counters. Sharded by key so that all checks for a given `(scope,id,rule)` hash to the same shard, allowing **atomic** read-modify-write via Lua. Each shard has a replica for failover.

4. **Config service + Postgres.** The control plane. Rules are authored here, validated, and pushed to gateways (via long-poll / pub-sub) so rule changes propagate in seconds without a deploy.

5. **Observability pipeline.** Allow/deny decisions, top throttled keys, and 429 rates stream asynchronously to Prometheus/Kafka. This must never be on the synchronous path.

---

## 6. Deep Dives

### 6.1 Choosing the algorithm

The algorithm determines accuracy, burst behavior, and memory. The classic four:

```
TOKEN BUCKET            Bucket holds up to C tokens, refilled at rate R.
  capacity C ─┐         Each request removes `cost` tokens; if none, reject.
   tokens ████│         ✔ Allows controlled bursts up to C
              │         ✔ O(1) memory (tokens + timestamp)
   refill R ──┘         ✔ Smooth long-run rate. Industry default (AWS, Stripe).

LEAKY BUCKET            Requests enter a FIFO queue that "leaks" at fixed rate.
   in ▸ [■■■  ] ▸ out   ✔ Perfectly smooth output, good for shaping downstream load
                        ✘ No bursts; queueing adds latency / needs a real queue.

FIXED WINDOW COUNTER    Count requests per discrete window (e.g. 12:00:00–12:00:59).
  [12:00] ███           ✔ Trivial (one INCR + EXPIRE), O(1)
  [12:01] █             ✘ BOUNDARY BURST: up to 2× limit across a window edge
                          (100 at 12:00:59 + 100 at 12:01:00 = 200 in 2s).

SLIDING WINDOW LOG      Store timestamp of every request in a sorted set; count
  •  •  • • •           those within the last window.
  └── window ──┘        ✔ Exact, no boundary problem
                        ✘ O(N) memory per key (one entry per request) — expensive.

SLIDING WINDOW COUNTER  Approximate: weight current + previous fixed window.
  est = cur + prev × (overlap fraction of window)
                        ✔ O(1) memory, smooths the boundary burst, ~99% accurate
                        ✔ Best accuracy/cost trade-off — Cloudflare's choice.
```

**Decision:** default to **token bucket** for general API limiting (burst tolerance + O(1) + simple). Use **sliding window counter** when boundary bursts are unacceptable but you can't afford the log. Reserve **sliding window log** for low-volume, high-value limits (e.g., `/login` brute-force protection) where exactness matters and request counts are small. Use **leaky bucket** when you must *shape* a smooth stream into a fragile downstream.

### 6.2 Atomicity: avoiding races with Lua scripts

The naive `GET tokens → compute → SET tokens` is a **read-modify-write race**: two concurrent gateways read 1 remaining token, both decide "allowed," both write 0, and you've allowed 2 with a limit of 1. `INCR` is atomic but can't express token-bucket refill logic.

The fix is a **Redis Lua script**, which Redis executes **atomically** on the shard that owns the key (single-threaded, no interleaving):

```lua
-- KEYS[1] = bucket key ; ARGV = now_ms, capacity, refill_rate, period_ms, cost
local b   = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tok = tonumber(b[1]); local ts = tonumber(b[2])
local now = tonumber(ARGV[1]); local cap = tonumber(ARGV[2])
local rate= tonumber(ARGV[3]); local per = tonumber(ARGV[4])
local cost= tonumber(ARGV[5])

if tok == nil then tok = cap; ts = now end
-- refill based on elapsed time
local refill = (now - ts) * rate / per
tok = math.min(cap, tok + refill)
ts  = now

local allowed = 0
if tok >= cost then tok = tok - cost; allowed = 1 end

redis.call('HMSET', KEYS[1], 'tokens', tok, 'ts', ts)
redis.call('PEXPIRE', KEYS[1], per * 2)
return { allowed, math.floor(tok) }   -- allowed flag + remaining
```

Because the entire read-refill-decide-write sequence runs as one atomic unit on the owning shard, **there is no race** regardless of how many gateways call it concurrently. This is the canonical answer to "how do you make a distributed rate limiter correct," and `EVALSHA` (script caching) keeps it fast.

### 6.3 Clock skew and time handling

Token bucket and sliding windows are time-based, so *whose clock* matters.

- **Use the Redis server's time, not the gateway's.** Gateways' clocks drift independently; if each passes its own `now_ms`, refill math becomes inconsistent. Inside Lua you can call `redis.call('TIME')` to use the shard's monotonic-ish clock, making all gateways agree on a single time source per key.
- **NTP-sync every node** regardless, and keep windows coarse relative to expected skew — a 1 ms skew is irrelevant to a 1-minute window but meaningful to a 1-second window.
- **Monotonic refill, not wall-clock windows:** token bucket's "elapsed × rate" formulation tolerates small clock jumps far better than fixed-window bucketing keyed on wall-clock boundaries.

### 6.4 Local caching vs. accuracy (sync vs. async)

A Redis round trip per request (even ~0.3 ms) at 1M req/s is a lot of load and latency. Two strategies reduce it, trading accuracy for performance:

- **Synchronous (strict):** every request hits Redis via the Lua script. Most accurate, highest latency/load. Use for hard limits.
- **Local-bucket / async (approximate):** each gateway is granted a *slice* of the budget locally and only periodically reconciles with Redis. Two common forms:
  - **Pre-allocation / lease batching:** a gateway requests a *batch* of N tokens from Redis at once and serves them locally; when exhausted it requests more. Cuts Redis ops by ~N× at the cost of letting each gateway over- or under-shoot by up to its lease size.
  - **Local count + async flush:** count locally, push deltas to Redis every few hundred ms. Cheapest, least accurate.

With **G gateways** and a global limit **L**, the worst-case over-allowance from independent local buckets is bounded by roughly `G × (local_slice)` extra requests across the fleet at window boundaries — acceptable for soft limits, not for billing. The interview point: **you choose where you sit on the accuracy/performance curve per rule**, not globally.

### 6.5 Sharding and the hot-key problem

Counters are sharded across the Redis cluster by hashing the counter key, so load spreads evenly *across keys*. The danger is a **hot key**: a single global limit (`global:downstream_db`) or one whale customer whose key receives a huge fraction of all traffic — every request mutates the *same* key on *one* shard, which becomes a bottleneck (and a single point of contention).

Mitigations:

- **Key splitting / sharded counters:** split a hot global counter into `M` sub-counters `global:db:{0..M-1}`, each holding `L/M` of the budget; route requests to a sub-counter by `hash(request) % M`. Aggregate is approximate but spreads write load across shards.
- **Local pre-allocation** (6.4) is especially effective for hot keys: gateways lease big slices so the hot key is touched rarely.
- **Two-tier limiting:** cheap local per-gateway check first; only requests that pass go on to consult the expensive shared counter.

---

## 7. Scaling, Bottlenecks & Failure Handling

### What breaks first

1. **Redis throughput / a hot shard.** At 2.5M ops/s the first thing to saturate is a single shard, especially under a hot key. Mitigate with pipelining, `EVALSHA`, key splitting (6.5), and local pre-allocation to cut op count.
2. **Network round trips.** Even cheap, 1M+ synchronous round trips/s add latency and connection pressure. L1 caching / leasing is the primary lever.
3. **Rule fan-out / config storms.** A bad rule push could disable limiting or reject everything. Stage rule changes, validate, and roll out gradually.

### Scaling levers

- **Horizontal Redis sharding** (Redis Cluster) for throughput and memory — already sized at ~25–30 shards above; add shards linearly with traffic.
- **Read replicas** per shard for failover, *not* for counter reads (reads must hit the primary for correctness — replicas lag).
- **Region-local clusters.** For multi-region, run a Redis cluster per region. Most limits are enforced **per region** (cheap, no cross-region latency). For a few truly global hard limits, either (a) accept per-region sub-limits summing to the global cap, or (b) use a CRDT-style/aggregating layer with eventual reconciliation — accepting bounded over-allowance rather than paying cross-region round-trip latency on the hot path.

### Failure handling

- **Fail-open vs. fail-closed.** If Redis is unreachable, you must decide per rule: **fail open** (allow traffic — preserves availability, risks overload) is the default for protecting *your own* backend; **fail closed** (reject — protects a fragile/expensive downstream or enforces a paid quota) for critical resources. Make this a per-rule config (`fail_mode` in the schema).
- **Circuit breaker around Redis.** If the limiter store starts timing out, trip a breaker and switch to a degraded local-only mode (per-gateway approximate limits) rather than adding the Redis timeout to every request's latency.
- **Replica failover.** Redis Sentinel / Cluster promotes a replica on primary failure. Counters lost in the gap are bounded by the window and TTL — at worst a brief, small over-allowance.
- **DR / region loss.** Because each region is independent, losing a region's limiter cluster degrades only that region; traffic shifted to other regions is limited by their local clusters. The control-plane Postgres is backed up and cross-region replicated, but it's off the hot path so its loss is not user-visible immediately (gateways keep their cached rules).

---

## 8. Trade-offs & Alternatives

| Decision | Chosen | Alternative | Why |
|---|---|---|---|
| **Placement** | API gateway (edge) | Sidecar / inside each service | Reject early, before expensive backends; centralized policy |
| **Store** | Redis Cluster | Per-node in-memory only | Need shared state for distributed correctness; per-node alone over-allows by ×(node count) |
| **Algorithm** | Token bucket (default) | Sliding window log | O(1) memory + burst tolerance; log is exact but O(N) and pricey |
| **Atomicity** | Lua script (EVALSHA) | Optimistic WATCH/MULTI retry | Single atomic op vs. retry loops under contention |
| **Consistency** | AP / bounded staleness | Strong (CP) | Availability + latency matter more than perfect counts for soft limits |
| **Latency optimization** | Local lease/cache for soft limits | Always-synchronous Redis | Cuts Redis load ~N×; accept small over-allowance |

### At 10× scale (10M req/s)

- The Lua-per-request model strains even a large cluster. **Lean harder on local pre-allocation/leasing** to push the synchronous-Redis fraction down. Move the limiter into the L7 proxy data plane (Envoy's native rate-limit filter with a custom backend) to shave per-request overhead.
- **Add a cheap probabilistic first stage** (per-gateway approximate counter) so the shared store only sees traffic that's near a limit.

### At 100× scale (100M req/s, global)

- Single global counters become untenable on the hot path. Adopt **hierarchical limiting**: strict local limits per gateway, looser regional aggregates, and only *eventually-consistent* global reconciliation for hard quotas — explicitly trading exactness for latency and availability.
- Consider **gossip/CRDT counters** between gateways for fleet-wide approximate counts without a central store, and reserve the central store for billing-grade metering done **asynchronously** off the request path.

### What I'd reconsider

For most companies, a **single managed Redis with a Lua token bucket and a thin gateway library** is the right 90% answer — it is correct, simple, and cheap. The elaborate multi-tier/CRDT machinery only earns its complexity at hyperscale; introducing it early is over-engineering.

---

## Interview Q&A by Level

### 🟢 Basic

**Q: What is a rate limiter and why do we need one?**
A: It caps how many requests a client (user, IP, API key) can make in a time window, returning `429 Too Many Requests` when exceeded. It protects backends from overload, abuse, DoS, accidental retry storms, and runaway costs, and it enforces fairness so one noisy client can't starve others.

**Q: What HTTP status code and headers should a rate limiter return?**
A: Status `429 Too Many Requests`. Headers: `Retry-After` (seconds to wait), and the `X-RateLimit-Limit`, `X-RateLimit-Remaining`, and `X-RateLimit-Reset` family so well-behaved clients can self-throttle before being blocked.

**Q: Name the common rate-limiting algorithms.**
A: Token bucket, leaky bucket, fixed window counter, sliding window log, and sliding window counter. Token bucket allows bursts and is the common default; fixed window is simplest but has the boundary-burst flaw.

**Q: Where should the rate limiter live?**
A: As close to the client as possible — typically the API gateway or load balancer — so rejected requests never reach (and waste) backend resources. It can also be a sidecar or middleware, but edge placement rejects the most traffic the cheapest.

### 🟡 Intermediate

**Q: Explain the boundary-burst problem with fixed windows and how sliding window fixes it.**
A: With a fixed 1-minute window and a 100-req limit, a client can send 100 requests at 12:00:59 and another 100 at 12:01:00 — 200 requests in ~2 seconds, double the intended rate, because the counter resets at the boundary. A **sliding window counter** fixes this by estimating the count as `current_window_count + previous_window_count × (fraction of previous window still inside the sliding period)`, smoothing the edge with O(1) memory and ~99% accuracy. A **sliding window log** fixes it exactly by storing per-request timestamps, but at O(N) memory.

**Q: Why can't you just keep counters in each server's memory?**
A: With G servers behind a load balancer, each independently allows up to the full limit, so the effective limit is roughly G× the intended one, and it varies with load-balancing. You need **shared state** (e.g., Redis) so all servers consult the same counter — or a coordinated lease scheme if you want to keep state local.

**Q: How do you implement token bucket in Redis correctly?**
A: Store `tokens` and `last_refill_ts` per key. On each request, atomically: refill `tokens += elapsed × rate` (capped at capacity), then if `tokens ≥ cost` subtract and allow, else reject. Do the whole read-modify-write in a **Lua script** so it executes atomically on the owning shard — otherwise concurrent requests race on the read-then-write.

**Q: Token bucket vs. leaky bucket — when to use which?**
A: Token bucket permits short bursts up to the bucket capacity while bounding the long-run average — good for user-facing APIs where occasional bursts are fine. Leaky bucket emits a perfectly smooth, constant-rate stream (FIFO queue leaking at fixed rate) — good when you must *shape* traffic to protect a fragile downstream that can't tolerate bursts, at the cost of queueing latency and no burst allowance.

### 🟠 Advanced

**Q: How do Lua scripts guarantee atomicity, and what are the alternatives?**
A: Redis is single-threaded for command execution and runs a Lua script to completion as one atomic unit — no other command interleaves, so the read-refill-decide-write sequence is race-free on the shard owning the key. Alternatives: `MULTI/EXEC` with `WATCH` (optimistic concurrency, but retries under contention and can't cleanly express refill math), or `INCR`+`EXPIRE` for fixed windows (atomic but limited to simple counting). Lua + `EVALSHA` is the standard because it's both correct and fast. Caveat: with Redis Cluster all keys touched by one script must hash to the same slot (use hash tags).

**Q: How do you reduce the Redis round trip on every request without losing too much accuracy?**
A: Two approaches. (1) **Lease/pre-allocation:** a gateway atomically claims a batch of N tokens from Redis and serves them locally, refetching when exhausted — cuts Redis ops ~N× at the cost of up to ~N over/under-shoot per gateway. (2) **Local count + async flush:** count locally and reconcile deltas every few hundred ms. Both trade exactness for latency/throughput; the worst-case fleet-wide over-allowance is bounded by `(#gateways × local slice)`. Apply them only to soft limits; keep hard limits synchronous.

**Q: How do you handle clock skew across nodes?**
A: Use a single time source per key — call `redis.call('TIME')` inside the Lua script so all gateways agree on the shard's clock instead of passing their own drifting `now`. Keep nodes NTP-synced, prefer token bucket's elapsed-time refill (tolerant of small jumps) over wall-clock fixed windows, and keep windows coarse relative to expected skew.

**Q: What's your fail-open vs. fail-closed policy when Redis is down?**
A: Per-rule decision. **Fail open** (allow) when the limiter protects your own scalable backend — availability beats perfect enforcement, and you'd rather serve traffic than 429 everyone on an infra blip. **Fail closed** (reject) when protecting a fragile/expensive downstream or enforcing a paid quota where over-allowance is worse than rejection. Wrap Redis in a **circuit breaker** so timeouts trip to a degraded local-only mode instead of adding the timeout to every request.

### 🔴 Expert

**Q: How do you enforce a single *global* limit across multiple regions without paying cross-region latency on every request?**
A: You can't have both perfect global accuracy and low latency under a partition (CAP). Practical options: (1) **Partition the global budget** into per-region sub-limits that sum to the cap — fully local, but a region can be throttled while another has spare budget. (2) **Local enforcement + asynchronous global reconciliation** — each region enforces locally and a background aggregator reconciles toward the global cap with bounded staleness, accepting small over-allowance. (3) **CRDT counters** gossiped between regions for eventually-consistent global counts. For billing-grade hard quotas, do the strict accounting **asynchronously and offline** (metering pipeline) rather than synchronously on the hot path, and treat the live limiter as an approximate guardrail.

**Q: A single global counter (or one whale customer) is melting one Redis shard. How do you fix the hot key?**
A: Split the counter into `M` sub-keys (`...:{0..M-1}`), each granted `1/M` of the budget, and route requests by `hash(request) % M` so writes spread across shards — aggregate enforcement becomes slightly approximate. Combine with **local pre-allocation** so the hot key is touched rarely (gateways lease large slices), and a **two-tier check** (cheap local gate first, shared counter only for traffic near the limit). These trade exactness for write distribution.

**Q: How would you support weighted / cost-based limits (e.g., an expensive query counts as 5)?**
A: Make the bucket consume a variable `cost` rather than always 1 — the Lua script already subtracts `cost` tokens and rejects if insufficient. This naturally models heterogeneous endpoints (a cheap `GET` costs 1, an LLM call costs 50) against a shared budget, which is more meaningful than a flat request count for protecting capacity or cost.

**Q: How do you test and roll out a rate-limiter change safely at scale?**
A: Run rules in **shadow/dry-run mode** first — evaluate and log what *would* be throttled without actually rejecting, to validate thresholds against real traffic. Roll new rules out **gradually** (percentage of traffic / canary gateways) via the config service, with instant rollback. Emit rich metrics (429 rate, top throttled keys, allow/deny ratios) asynchronously, and alert on anomalies like a sudden spike in 429s (mis-set rule) or a drop to zero (limiter disabled). Never push rules in a way that can fail-closed the whole fleet at once.

---

[← Back to master index](../../README.md) | [← System Design index](../README.md)
