# Design a Distributed Job Scheduler (Cron at Scale)

> A worked, interview-grade design for a horizontally-scalable job scheduler: accept millions of one-off and recurring (cron) jobs, fire each one at its scheduled time exactly once, and execute them reliably across a fleet of workers — even as machines crash, clocks drift, and the schedule grows to hundreds of millions of entries.

[← Back to master index](../../README.md) · [← System Design index](../README.md)

---

## 1. Requirements

A distributed job scheduler is "cron, but reliable, multi-tenant, and at scale." The interviewer is probing how you reason about **time-based triggering**, **exactly-once semantics under failure**, and **decoupling scheduling from execution**. The hard part is never "store a cron string" — it's "what happens when the worker that owns this job dies one millisecond before it fires." Lead by clarifying scope.

### Functional requirements
- **Schedule a one-off job**: run callback X once at absolute time T (e.g. "send this email at 2026-07-01 09:00 UTC").
- **Schedule a recurring job**: run on a cron expression / interval (e.g. `0 */15 * * * *` — every 15 minutes), with timezone awareness and DST handling.
- **Execute**: invoke the job's action — typically an HTTP webhook, a message onto a queue, or an internal RPC — at (or shortly after) the scheduled time.
- **Cancel / pause / update**: an owner can cancel a pending job, pause/resume a recurring schedule, or change its payload/schedule.
- **Retries & backoff**: a failed execution retries with configurable policy (max attempts, exponential backoff, jitter); terminal failures go to a dead-letter queue (DLQ).
- **Status & history**: query a job's state (pending / running / succeeded / failed / dead) and its recent run history.
- **Idempotency / exactly-once-ish**: each scheduled fire should trigger the action **once** under normal operation; the system must not silently *drop* a fire, and should bound duplicate fires.

### Non-functional requirements

| Dimension | Target |
|---|---|
| **Scale** | 500M active scheduled jobs; ~100K jobs *due* per second at peak (bursty — top of the minute/hour is a thundering herd) |
| **Trigger accuracy** | p99 fire within **1 s** of scheduled time; p999 within **5 s**. Late is tolerable; *dropped* is not. Sub-second precision is a separate, harder tier. |
| **Throughput** | 100K executions/sec sustained, 500K/sec burst |
| **Durability** | An accepted schedule must **never be lost**. Survive single-AZ loss with zero job loss. |
| **Availability** | 99.99% for the scheduling API; the firing path must keep working during partial outages. |
| **Delivery guarantee** | **At-least-once** by default (with idempotency keys so consumers can dedup); at-most-once available for jobs where a duplicate is worse than a miss. |
| **Consistency** | Strong consistency on job *state* (a cancelled job must not fire); eventual consistency acceptable on history/metrics. |
| **Multi-tenancy** | Per-tenant quotas and isolation so one tenant's million-job burst can't starve others. |

### Clarifying questions a strong candidate asks
1. **One-off, recurring, or both?** Recurring (cron) adds "compute the next fire time + reschedule" and DST complexity; one-off is simpler.
2. **Delivery semantics** — is at-least-once + idempotency acceptable, or do we genuinely need exactly-once? This is the single biggest design driver.
3. **Trigger precision** — minute-level (classic cron) is *vastly* easier than sub-second. What's the SLO on lateness?
4. **What does "execute" mean?** Fire a webhook? Enqueue a message? Run user code in a sandbox? We'll fire-then-delegate, not run arbitrary code in the scheduler itself.
5. **What's the time horizon?** Jobs scheduled seconds out vs. years out changes the storage/index strategy (a far-future job shouldn't sit in a hot in-memory queue).
6. **Late jobs** — if the scheduler was down when a job was due, do we fire it late ("catch-up"), skip it, or fire only the most recent? (Cron "misfire" policy.)
7. **Failure handling** — retry policy, max attempts, and what happens on terminal failure (DLQ vs drop)?
8. **Multi-region?** Single region with DR, or active-active globally (which forces a clock-and-ownership story across regions)?

> The most important clarification is **semantics under failure**. "Exactly-once firing" is impossible in a distributed system in the strict sense (it's equivalent to the two-generals problem at the network edge). The honest target is **at-least-once delivery with idempotency**, or **at-most-once** when duplicates are unacceptable — and the candidate should say so explicitly.

---

## 2. Capacity Estimation

Real numbers, shown long-hand. Assume a 5-year horizon and the scale targets above.

### Active job count & write QPS (scheduling API)
```
Active scheduled jobs (steady state) = 500,000,000
New schedules + updates/cancels      ≈ 50,000 writes/sec avg
Peak write factor ~4x                →  ~200,000 writes/sec peak
```
Writes are dominated by *recurring* jobs re-arming themselves: every time a cron job fires, we compute its next run and persist it — so executions and "reschedule writes" move together.

### Fire / execution QPS (the hot path)
```
Average due rate     = 100,000 jobs/sec
BUT firing is bursty — cron clusters on round numbers:
  "top of the minute" (e.g. 0 * * * *) and "top of the hour" spike hard.
Worst burst: suppose 5M jobs all due at 12:00:00.
  If we must clear them within the 5 s p999 SLO →
  5,000,000 ÷ 5 s = 1,000,000 fires/sec for that window.
```
This **thundering-herd at round times** is the defining bottleneck — far more than the steady-state average. The design must smooth/shard the burst (see Deep Dive 6.4).

### Storage over 5 years
Per job record:
```
job_id            16 bytes (UUID)
tenant_id          8 bytes
schedule (cron)   ~32 bytes
next_run_at        8 bytes
payload/target   ~512 bytes (webhook URL + headers + body ref)
state/metadata   ~64 bytes
retry policy      ~32 bytes
----------------------------------
~ 672 bytes  → round to ~700 B/record
```
```
Active set: 500M × 700 B          = 350,000,000,000 B  ≈ 350 GB
With secondary index on next_run_at (~50 B/entry):
  500M × 50 B                     ≈ 25 GB index
With 3x replication + overhead:   (350 + 25) × 3 × 1.4 ≈ 1.6 TB
```
The *active* schedule is small (sub-2 TB) — it fits comfortably in a sharded DB. **Execution history** dwarfs it:
```
Executions/day = 100K/s × 86,400  ≈ 8.64 × 10^9 runs/day
History row ~200 B  →  8.64B × 200 B ≈ 1.7 TB/day
With 30-day retention × 3x repl    ≈ 155 TB
```
So: keep the **live schedule** in a fast OLTP store, and stream **run history** to a cheap append-only/columnar store with TTL (the same split as analytics in most large systems).

### Bandwidth
```
Fire path: 1,000,000 fires/sec (burst) × ~600 B (enqueue msg) ≈ 600 MB/s onto the queue
Execution: webhook calls — depends on payload; mostly small. ~600 MB/s outbound at burst.
```
Bandwidth is modest; **request rate, scheduling precision, and burst smoothing** are the constraints, not bytes.

### Hot in-memory "due soon" set (timer wheel sizing)
We don't load 500M jobs into memory — only jobs due in the **near window** (say the next 60 s) live in an in-memory timer structure:
```
Jobs due in next 60 s ≈ 100K/s × 60 s = 6,000,000 entries (steady) — but bursts spike higher.
Per timer-wheel entry: job_id + next_run + shard ptr ≈ 40 B
6M × 40 B ≈ 240 MB  (steady)   |   peak top-of-minute could hold tens of millions briefly.
```
A few hundred MB to low single-digit GB of RAM per scheduler node — trivially affordable, and refreshed continuously from the DB by a "loader" that pulls the next time-window of jobs.

---

## 3. API Design

REST/gRPC over HTTPS for the control plane (schedule/cancel/query). Authentication via API key / OAuth bearer; per-tenant rate limiting. The data plane (firing) is internal.

```http
# ---- Schedule a one-off job ----
POST /api/v1/jobs
Authorization: Bearer <token>
Idempotency-Key: 7f3a...                 # client-supplied; dedup duplicate submits
{
  "type":        "one_off",
  "run_at":      "2026-07-01T09:00:00Z",
  "target":      { "kind": "webhook", "url": "https://api.acme.com/hook",
                   "method": "POST", "headers": {...}, "body_ref": "s3://..." },
  "retry":       { "max_attempts": 5, "backoff": "exponential", "base_ms": 1000, "jitter": true },
  "timezone":    "UTC"
}
→ 201 Created { "job_id": "job_01H...", "state": "scheduled", "next_run_at": "2026-07-01T09:00:00Z" }
→ 409 Conflict        # Idempotency-Key already used
→ 429 Too Many Requests   # tenant quota

# ---- Schedule a recurring (cron) job ----
POST /api/v1/jobs
{
  "type":     "recurring",
  "cron":     "0 0 9 * * MON-FRI",         # 09:00 every weekday
  "timezone": "America/New_York",          # DST-aware
  "misfire_policy": "fire_once",           # one_of: skip | fire_once | catch_up_all
  "target":   {...}, "retry": {...}
}
→ 201 Created { "job_id": "...", "state": "scheduled", "next_run_at": "2026-06-16T13:00:00Z" }

# ---- Read / list ----
GET  /api/v1/jobs/{job_id}
→ 200 { job_id, type, cron, next_run_at, state, last_run, attempt_count }
GET  /api/v1/jobs?tenant=...&state=failed&cursor=...     # paginated

# ---- Mutate ----
PATCH  /api/v1/jobs/{job_id}      { "cron": "...", "payload": {...} }   # update schedule/payload
POST   /api/v1/jobs/{job_id}/pause     → 200 { state: "paused" }
POST   /api/v1/jobs/{job_id}/resume    → 200 { state: "scheduled" }
DELETE /api/v1/jobs/{job_id}           → 204   # cancel; MUST prevent any future/in-flight fire

# ---- Run history ----
GET /api/v1/jobs/{job_id}/runs?from=...&to=...
→ 200 { runs: [ { run_id, scheduled_for, started_at, finished_at, status, attempt, http_status } ] }
```

Design notes:
- **`Idempotency-Key` on create** dedups client retries so a network hiccup during submission doesn't create two jobs.
- **`misfire_policy`** is the cron "what if we were down when it was due" knob — `skip` (forget it), `fire_once` (fire one catch-up), or `catch_up_all` (fire every missed occurrence; dangerous for frequent jobs).
- **Timezone is stored, not converted** — DST means a wall-clock `09:00` is a *different* UTC instant in summer vs winter, so we recompute the next fire in the job's tz each time (see Deep Dive 6.5).
- **DELETE/cancel is a strongly-consistent state transition** — it must win against an in-flight fire (see Deep Dive 6.2).

---

## 4. Data Model

Two distinct stores, because the access patterns differ sharply: a strongly-consistent OLTP store for the **live schedule**, and a cheap append-only store for **run history**.

### Live schedule store (OLTP — Postgres/Cassandra/DynamoDB)
The hot query is: *"give me all jobs whose `next_run_at` ≤ now, locked to me, in time order."* That's a **range scan on a time column plus a claim/lock**, with strong consistency on per-job state.

```
Table: jobs
  job_id        UUID      PRIMARY KEY
  tenant_id     UUID      (shard key candidate)
  type          ENUM(one_off, recurring)
  cron          STRING    (null for one_off)
  timezone      STRING
  next_run_at   TIMESTAMP INDEXED            -- the firing index
  state         ENUM(scheduled, claimed, running, paused, succeeded, failed, dead)
  target        JSON      (webhook/queue/rpc descriptor)
  retry_policy  JSON
  attempt       INT
  lease_owner   STRING    (which scheduler/worker holds it)
  lease_expires TIMESTAMP (for crash recovery — see 6.2)
  version       INT       (optimistic concurrency)
  updated_at    TIMESTAMP

-- Critical index: the "what's due" query
INDEX idx_due ON jobs (shard, next_run_at) WHERE state IN ('scheduled','claimed')
```

**Why this engine choice.** The firing query is a **time-ordered range scan**, not a point lookup, and it needs **atomic claim** (compare-and-set on `state`/`lease`) to prevent two schedulers grabbing the same job. That favors a store with:
- Efficient ordered range scans on `next_run_at` (B-tree / clustering key on time).
- Atomic conditional updates (Postgres `UPDATE ... WHERE state='scheduled'`, or DynamoDB conditional write, or Cassandra LWT).

A **relational store (Postgres) sharded by tenant/job_id** is an excellent fit at this scale and is the simplest correct answer: `SELECT ... WHERE next_run_at <= now() AND state='scheduled' FOR UPDATE SKIP LOCKED LIMIT N` gives an atomic, contention-free claim out of the box. At extreme scale or for multi-region, **DynamoDB** (with a GSI on `next_run_at` bucketed by time-shard) or **Cassandra** (clustering by `(time_bucket, job_id)`, LWT for claims) trade SQL's easy locking for horizontal scale — but you then engineer the claim logic yourself. **I'd start with sharded Postgres** and move hot/huge tenants to a partition-by-time scheme; reach for Dynamo/Cassandra only when single-region OLTP can't keep up.

> Anti-pattern to call out: a naive `SELECT * WHERE next_run_at <= now()` polled by many schedulers without `SKIP LOCKED`/conditional claim causes the **classic double-fire and lock-contention storm**. The claim mechanism is the whole game.

### Run history store (append-only / columnar — Cassandra / ClickHouse / S3+Athena)
```
Table: job_runs  (partitioned by (job_id, day))
  run_id        UUID
  job_id        UUID
  scheduled_for TIMESTAMP
  started_at    TIMESTAMP
  finished_at   TIMESTAMP
  status        ENUM(succeeded, failed, retrying, dead)
  attempt       INT
  http_status   INT
  error         TEXT
  TTL 30 days
```
Append-only, write-heavy, queried by `(job_id, time range)` or aggregated by tenant — a poor fit for the OLTP store. Stream runs here via Kafka so history-writes never block the fire path.

### Time-bucketing for the index
To avoid scanning a 500M-row index, jobs are bucketed by their fire minute: `time_bucket = floor(next_run_at / 60s)`. Schedulers only ever query the **current and next few buckets**, so the working index is tiny relative to the full table.

---

## 5. High-Level Architecture

```
        ┌──────────────────────────────────────────────────────────────┐
        │                         CLIENTS / TENANTS                       │
        └───────────────┬───────────────────────────────┬───────────────┘
                        │ schedule/cancel (control)       │ query status
              ┌─────────▼──────────┐                      │
              │  Scheduling API     │  (stateless, autoscaled, rate-limited per tenant)
              │  validate · idemp.  │
              └─────────┬───────────┘
                        │ write
              ┌─────────▼──────────────────────────────────────────────┐
              │   LIVE SCHEDULE STORE  (sharded OLTP, next_run_at index) │
              │   jobs(state, next_run_at, lease_owner, lease_expires)   │
              └─────────▲───────────────────────┬───────────────────────┘
                        │ persist next_run        │  range-scan "due soon" + atomic CLAIM
                        │ (re-arm cron)            │  (SELECT ... FOR UPDATE SKIP LOCKED)
        ┌───────────────┴──────────┐    ┌─────────▼───────────────────────┐
        │  RESCHEDULER              │    │      SCHEDULER NODES (N)         │
        │  computes next cron fire  │◄───┤  per-shard owner via lease       │
        │  & writes next_run_at     │    │  in-mem TIMER WHEEL (next 60s)   │
        └───────────────────────────┘    │  fires when wall-clock ≥ run_at  │
                                          └─────────┬────────────────────────┘
                                                    │ enqueue "ready to run" msg
                                          ┌─────────▼────────────────────────┐
                                          │   EXECUTION QUEUE (Kafka/SQS)     │  ← decouples timing from work
                                          │   partitioned; smooths the burst  │     + buffers thundering herd
                                          └─────────┬────────────────────────┘
                                                    │ pull
                       ┌────────────────────────────▼─────────────────────────────┐
                       │              WORKER FLEET (M, autoscaled)                   │
                       │  invoke target (webhook/RPC/enqueue) · retry+backoff        │
                       │  write run result · ack                                     │
                       └───────┬──────────────────────────────────┬─────────────────┘
                               │ success/fail                       │ terminal failure
                     ┌─────────▼──────────┐               ┌─────────▼──────────┐
                     │  RUN HISTORY (Kafka │               │  DEAD-LETTER QUEUE  │
                     │  → ClickHouse/S3)   │               │  (manual/auto retry)│
                     └─────────────────────┘               └─────────────────────┘

         ┌───────────────────────────────┐
         │  COORDINATION (ZooKeeper/etcd) │  ← shard ownership leases, leader election,
         │  /Raft — assigns shards→nodes  │     failure detection for scheduler nodes
         └───────────────────────────────┘
```

### Component walkthrough
- **Scheduling API** (stateless, autoscaled): validates the schedule, enforces per-tenant quota, dedups on `Idempotency-Key`, computes the first `next_run_at`, and persists to the live store. Cancels/updates are conditional writes on `version`.
- **Live schedule store**: the source of truth. Strongly consistent; holds `state`, `next_run_at`, and the **lease** fields used for crash recovery.
- **Coordination service (ZooKeeper/etcd/Raft)**: partitions the job keyspace into **shards** and assigns each shard to exactly one scheduler node via a lease, so two nodes never own the same shard. Detects node death (lease expiry) and triggers reassignment.
- **Scheduler nodes**: each owns a set of shards. A **loader** continuously pulls "jobs due in the next window" for its shards from the DB into an in-memory **timer wheel**. When wall-clock time reaches a job's `next_run_at`, the node **atomically claims** it in the DB (CAS `scheduled→claimed` with a lease) and enqueues a "ready to run" message. It does *not* execute the work — that keeps the timing layer lean and fast.
- **Execution queue (Kafka/SQS)**: decouples *when* a job fires from *who does the work* and **absorbs the thundering herd** — 1M messages at 12:00:00 land in the queue and drain at the workers' sustainable rate. This is the key buffer.
- **Worker fleet** (stateless, autoscaled on queue depth): pulls ready jobs, invokes the target (webhook/RPC/enqueue), applies retry-with-backoff, writes the run result, and on terminal failure routes to the **DLQ**.
- **Rescheduler**: for recurring jobs, computes the *next* cron fire time (tz/DST-aware) and writes the new `next_run_at` back to the live store — re-arming the job. (Often folded into the scheduler/worker; shown separately for clarity.)
- **Run history**: results stream via Kafka into a cheap columnar/append store with TTL.

### Fire path, end to end
1. Loader for shard S pulls jobs with `next_run_at ∈ [now, now+60s]` into the timer wheel.
2. At `t = next_run_at`, the scheduler node atomically claims the job: `UPDATE jobs SET state='claimed', lease_owner=me, lease_expires=now+30s WHERE job_id=? AND state='scheduled' AND version=?`. If the CAS fails (someone else got it, or it was cancelled), skip.
3. Enqueue `{job_id, scheduled_for, attempt}` onto the execution queue (partitioned by `job_id`).
4. For a recurring job, immediately compute the next fire and write `next_run_at` (re-arm) so the cadence continues even if this fire's *execution* is slow.
5. A worker pulls the message, invokes the target, records the outcome, and acks the queue message. On failure it re-enqueues with backoff; after max attempts it DLQs.

---

## 6. Deep Dives

### 6.1 How does the scheduler actually fire on time? (polling vs timer wheel)

Two layers cooperate: a **durable DB index** for what's due, and an **in-memory timer** for precise firing.

- **Pure DB polling** (`SELECT ... WHERE next_run_at <= now()` every second): simple and crash-safe (state is always in the DB), but a 1 s poll caps precision at ~1 s and hammers the DB with range scans. Fine for minute-granularity cron; weak for sub-second.
- **In-memory timer wheel** (hashed timing wheel): O(1) insert and O(1) tick for firing thousands of timers, the structure Kafka/Netty use for delayed events. We don't keep 500M timers — only the **next ~60 s** of jobs, refilled continuously by the loader. This gives sub-second precision *and* low DB load.
- **The hybrid (chosen)**: DB index is the durable source of truth; the loader pulls a sliding time-window into the timer wheel; the wheel fires precisely; the DB CAS-claim makes the fire safe against duplicates and crashes. If a node dies, its in-memory wheel is lost — but **no jobs are lost** because they're still in the DB with `state='scheduled'`, and another node reloads them.

```
loop every 1s:                              # loader
  due = SELECT job_id, next_run_at FROM jobs
        WHERE shard IN my_shards
          AND next_run_at BETWEEN now() AND now()+60s
          AND state = 'scheduled'
  for j in due: timerWheel.schedule(j.next_run_at, fire(j))

fire(j):                                     # timer wheel callback at exact time
  ok = CAS(j, scheduled -> claimed, lease=me+30s)   # atomic claim in DB
  if ok:
     queue.enqueue(j)                        # hand off to workers
     if j.recurring: persist next_run_at(j)  # re-arm
```

### 6.2 Exactly-once-ish firing under failure (the leasing + CAS dance)

This is the question the interviewer actually cares about. Strict exactly-once is impossible (you can always crash in the gap between "did the work" and "recorded that I did the work"). We engineer **at-least-once with bounded duplicates + idempotency**.

- **Atomic claim via CAS / lease**: a job moves `scheduled → claimed` only via a conditional write. Two schedulers racing on the same job → exactly one CAS wins; the loser sees the version/state changed and backs off. This prevents double-claim *in the absence of crashes*.
- **Lease + crash recovery**: a claim sets `lease_expires = now + 30s`. If the owner crashes after claiming but before enqueuing/finishing, a **reaper** finds jobs where `state='claimed' AND lease_expires < now()` and resets them to `scheduled` so another node retries. This is what guarantees **no dropped fires** — the durable lease, not the in-memory timer, is the safety net.
- **The unavoidable duplicate window**: if a node enqueues the job and then crashes *before* recording success, the reaper will re-fire it → a duplicate. We **cannot** eliminate this; we **bound** it and push idempotency to the consumer: every fire carries a deterministic **idempotency key** = `hash(job_id, scheduled_for, attempt)`. The webhook/consumer dedups on it, making duplicate fires harmless.
- **Cancel vs in-flight race**: `DELETE` does a conditional write `state = 'cancelled' WHERE state IN ('scheduled','claimed')`. If it wins before the fire's CAS, the fire's CAS fails and nothing runs. If the fire already claimed it, the worker re-checks `state != cancelled` immediately before invoking the target (a cheap read), so a just-cancelled job is suppressed. There's a tiny window where a cancel arrives mid-execution; we document that "cancel is best-effort once the job has started running."

| Semantic | How we achieve it | Cost |
|---|---|---|
| **At-most-once** | Mark `running` *before* invoking, never retry on ambiguity | A crash mid-fire = a missed run |
| **At-least-once (default)** | Lease + reaper re-fires on crash | Possible duplicates → need idempotency keys |
| **"Effectively-once"** | At-least-once + consumer dedup on idempotency key | Honest, achievable answer |

### 6.3 Decoupling scheduling from execution (why a queue sits in the middle)

A naive design fires *and* runs the work in the scheduler node. That couples timing accuracy to execution duration — a slow webhook (30 s timeout) would block the timer and cascade lateness across every other job on that node.

Instead, the scheduler's only job is **"it's time → put a message on the queue."** Workers do the slow, failure-prone execution. Benefits:
- **Timing stays accurate** regardless of how slow targets are; the timer layer does O(1) work per fire.
- **The queue absorbs the burst** (6.4) — the scheduler can emit 1M messages instantly while workers drain at a sustainable rate.
- **Independent scaling**: scale scheduler nodes for *fan-out/precision*, workers for *execution throughput* (autoscale on queue depth/lag).
- **Retries live with workers**, not the timer — a failing webhook retries by re-enqueuing without touching the schedule index.

The cost: an extra hop adds a few ms of latency (irrelevant against a 1 s SLO) and the queue itself must be HA. Worth it.

### 6.4 Taming the thundering herd at round times

Cron expressions cluster pathologically: `0 * * * *` (top of every minute), `0 0 * * *` (midnight), `0 0 * * MON` (Monday midnight). Millions of jobs share the *exact same* `next_run_at`, creating a synchronized spike — the single biggest scaling hazard.

Mitigations, layered:
1. **Shard the firing work.** The keyspace is split across N scheduler nodes by `hash(job_id)`, so even 5M simultaneous jobs are spread across all nodes — no single node owns the whole spike.
2. **The execution queue is the shock absorber.** Schedulers enqueue the entire burst near-instantly; workers consume at their steady rate. Queue depth spikes, then drains within the SLO window. This converts a 1M/s instantaneous spike into a smooth 1M-over-5s drain.
3. **Deterministic jitter / spreading** (where the SLO allows): for jobs that say "roughly hourly" rather than "exactly at :00", spread their `next_run_at` with a per-job deterministic offset (e.g. `+ hash(job_id) % 60s`). This flattens the spike at the source. Many schedulers (and Kubernetes CronJobs via `startingDeadlineSeconds`, GitHub Actions) deliberately jitter. We expose this as an opt-in `spread` flag — but never silently for jobs that demand exact timing.
4. **Backpressure & quotas.** Per-tenant rate limits on the *firing* side prevent one tenant's million-job midnight batch from starving everyone; excess fires queue with priority fairness.

```
12:00:00.000  →  5,000,000 jobs due
   sharded across 200 scheduler nodes → 25,000 fires/node, all enqueued in <1s
   queue depth jumps to 5M
   workers (autoscaled) drain at 1M/s → cleared by 12:00:05  ✓ within p999 5s SLO
```

### 6.5 Cron parsing, timezones & DST (the subtle correctness bugs)

Recurring schedules are where "looks easy" hides real bugs.

- **Recompute next fire each time, in the job's timezone.** Store the cron + tz; after each fire, compute the *next* instant by advancing the cron in that tz and converting to UTC for `next_run_at`. Never store a fixed UTC offset — DST changes it.
- **DST spring-forward gap**: `0 2 30 * * *` (02:30 daily) — on the spring-forward night, 02:30 *doesn't exist* (clocks jump 02:00→03:00). Policy: fire at the next valid instant (03:00) or skip — must be defined and documented (most libraries fire once at the boundary).
- **DST fall-back overlap**: 02:30 occurs *twice*. Policy: fire **once** (the first occurrence) — firing twice is a classic duplicate bug.
- **Leap seconds / clock skew**: never trust a single machine's clock for correctness. Nodes sync via NTP (and ideally a tighter source). Crucially, **correctness does not depend on synchronized clocks** — it depends on the **DB-side CAS claim**, which serializes regardless of clock drift. Clock skew only affects *precision* (how late a fire is), not *safety* (whether it fires once). A node whose clock is 3 s fast just fires 3 s early; the CAS still prevents duplicates.
- **Misfire handling** (we were down when it was due): driven by `misfire_policy` — `skip` (drop missed), `fire_once` (one catch-up fire), or `catch_up_all` (replay every missed occurrence — dangerous for a 1-minute cron after a 1-hour outage; would emit 60 fires).

```
nextFire(cron, tz, after):
    local = toLocal(after, tz)
    candidate = cron.next(local)          # cron lib computes next wall-clock match
    if isInDSTGap(candidate, tz):  candidate = endOfGap(candidate, tz)   # spring-forward
    if isAmbiguous(candidate, tz): candidate = firstOccurrence(candidate, tz)  # fall-back
    return toUTC(candidate, tz)
```

---

## 7. Scaling, Bottlenecks & Failure Handling

### What breaks first
1. **The "due now" DB index under the thundering herd** — millions of rows claimed in the same second create lock contention and read amplification. *Fix*: shard the index by `(shard, time_bucket)`, use `SKIP LOCKED`/conditional claims to avoid lock waits, and lean on the execution queue to absorb the spike.
2. **A single scheduler node owning too many shards** — a hot shard (a tenant with millions of midnight jobs) overloads one node. *Fix*: finer shard granularity, rebalance shards across nodes via the coordination service, isolate hot tenants.
3. **Worker execution throughput / slow targets** — a flood of slow webhooks backs up the queue, growing lag toward the SLO. *Fix*: autoscale workers on queue depth, set aggressive per-target timeouts, circuit-break failing endpoints.
4. **Reschedule write amplification** — every recurring fire writes a new `next_run_at`; at 100K/s that's 100K writes/s into the OLTP store. *Fix*: batch re-arm writes, shard the store, or use a partitioned-by-time table so writes spread.
5. **DLQ growth** — a permanently-broken target accumulates failures. *Fix*: cap attempts, alert on DLQ rate, auto-pause jobs whose target has failed N times consecutively.

### Scaling each axis
- **Firing throughput** → add scheduler nodes and split shards finer (parallelism = shard count). The coordination service rebalances shard ownership.
- **Execution throughput** → add workers (stateless; autoscale on queue lag) and partition the queue.
- **Schedule size** → shard the OLTP store by tenant/job_id; partition the firing index by time bucket so the working set stays small regardless of total job count.
- **History** → already offloaded to a columnar/append store with TTL; scales independently.

### Replication & DR
- Live schedule store: **RF=3 across 3 AZs**, synchronous quorum writes → survive a full-AZ loss with **zero job loss** (an accepted schedule is durable before we ack the API). This is non-negotiable: dropping a schedule is the worst failure.
- Coordination service (etcd/ZK): odd-sized quorum (3 or 5) across AZs for split-brain-free leader election.
- **Multi-region**: active-passive with async replication of the schedule store (RPO > 0 — in-flight schedule writes may be lost on regional failover, an acknowledged trade-off) — *or* active-active where each region owns a disjoint shard range so two regions never fire the same job (avoids a cross-region clock/ownership consensus). Active-active with *shared* ownership would require cross-region consensus on every fire — too slow; we partition instead.

### Failure modes & mitigations
- **Scheduler node crash**: its in-memory timer wheel is lost, but jobs remain `state='scheduled'` in the DB (or `claimed` with an expiring lease). The coordination service detects the dead node (lease expiry), reassigns its shards, and the reaper resets any orphaned `claimed` jobs. **No job lost; at worst slightly late.**
- **Worker crash mid-execution**: the queue message was never acked → it becomes visible again (SQS visibility timeout / Kafka offset not committed) → another worker retries. Idempotency key dedups if the original actually completed.
- **Queue outage**: schedulers buffer fires locally with bounded retry; if the queue is down longer than the buffer, fires are still recoverable from the DB (the job stays `claimed` with a lease that expires, then re-fires). Prefer to fail the claim and leave jobs `scheduled` when the queue is unreachable, so nothing is "claimed but undeliverable."
- **Clock skew / NTP failure**: affects precision only; the CAS claim preserves safety. Monitor clock drift; eject nodes whose skew exceeds a threshold.
- **Poison job** (a target that always 500s): retries with backoff up to max attempts, then DLQ + alert + optional auto-pause. Never let one job's failures block a shard.
- **Thundering-herd overload**: per-tenant quotas + queue backpressure + deterministic jitter (6.4).

---

## 8. Trade-offs & Alternatives

### Explicit decisions

| Decision | Chosen | Why / alternative |
|---|---|---|
| **Delivery semantics** | At-least-once + idempotency keys | Exactly-once is impossible at the network edge; at-most-once risks dropped fires. Effectively-once via consumer dedup is the honest target. |
| **Timing mechanism** | DB index + in-memory timer wheel (hybrid) | DB-only polling caps precision and hammers the DB; in-memory-only loses jobs on crash. Hybrid = precise *and* durable. |
| **Scheduling vs execution** | Decoupled via a queue | Keeps timing accurate independent of slow targets; queue absorbs the burst; independent scaling. Extra hop is negligible vs SLO. |
| **Claim mechanism** | DB CAS / lease (`SKIP LOCKED`) | Prevents double-fire without depending on synchronized clocks; lease enables crash recovery. |
| **Storage engine** | Sharded OLTP (Postgres) for live schedule; columnar/append for history | Live = ordered range scan + atomic claim (SQL excels via `FOR UPDATE SKIP LOCKED`); history = cheap append. |
| **Consistency** | Strong on job state, eventual on history | A cancelled job must not fire (CP-ish on state); metrics can lag. |
| **Burst handling** | Shard + queue buffer + opt-in jitter | Round-time clustering is the defining hazard; layered smoothing. |

### vs. off-the-shelf options
- **Linux cron / per-host cron**: no HA (host dies → jobs missed), no central visibility, no retries, no multi-tenancy. Fine for one box; falls over at scale. Our design is "cron's semantics, but distributed, durable, and observable."
- **Quartz Scheduler (clustered)**: JVM scheduler with a DB-backed clustered mode that does almost exactly the lease/claim dance described — a great reference. Limited by its single relational store at very high scale; we shard and decouple execution.
- **Kubernetes CronJobs**: great for container workloads, but precision is minute-level, `startingDeadlineSeconds` controls misfire, and it's not built for hundreds of millions of fine-grained jobs.
- **Cloud-managed (AWS EventBridge Scheduler, Google Cloud Scheduler, Temporal/Cadence)**: EventBridge Scheduler does one-off + cron at scale with a target-and-retry model very close to this. **Temporal** reframes the problem as durable workflows with timers — excellent when the "job" is a long multi-step process, not a single fire. If I weren't building this, I'd reach for EventBridge Scheduler (simple fires) or Temporal (durable workflows).

### At 10x / 100x scale
- **10x (5B jobs, 1M/s steady)**: partition the live store aggressively by time bucket so the hot index is always tiny; push far-future jobs (months/years out) into a **cold tier** that a slow background loader promotes into the hot store only as their fire time approaches — so the hot index only ever holds near-term jobs. Multi-region active-active with disjoint shard ranges.
- **100x**: the firing index itself becomes the bottleneck. Move to a **hierarchical time-bucketed log**: a coarse "this hour's jobs" structure that fans out to per-minute then per-second buckets, with dedicated firing fleets per granularity. Far-future jobs live cheaply in object storage; only the imminent window is hot. Execution moves fully to an autoscaling serverless worker tier (Lambda-style) so the worker fleet costs nothing between bursts. The schedule store may move to a purpose-built partitioned-by-time KV (Cassandra/Dynamo) once SQL's per-shard ceiling is hit.

---

## Interview Q&A by Level

### 🟢 Basic

**Q. [Theory] What's the core difference between a job scheduler and a message queue?**
A queue stores work that's ready *now* and delivers it to consumers; a scheduler stores work to be triggered *at a future time* and decides *when* it becomes ready. They compose: our scheduler fires a job by putting a message on a queue, then a worker (queue consumer) does the actual execution. The scheduler owns *when*; the queue+workers own *how the work runs and scales*.

**Q. [Theory] Why not just run Linux `cron` on a box?**
Single-host cron has no high availability (the box dies → every job is silently missed), no retries, no central visibility into success/failure, no multi-tenancy, and no horizontal scale. It's perfect for one machine and a handful of jobs. At 500M jobs across a fleet with durability and observability requirements, you need a distributed, DB-backed system with leasing, replication, and a worker tier.

**Q. [Practical] What information do you store per job?**
The identity (`job_id`, `tenant_id`), the schedule (`cron` + `timezone`, or a one-off `run_at`), the computed `next_run_at` (the firing index), the `target` (webhook/queue/RPC descriptor + payload reference), the `retry_policy`, runtime `state`, and the **lease fields** (`lease_owner`, `lease_expires`, `version`) that make crash-safe claiming possible. Run *history* is stored separately in a cheap append-only store.

### 🟡 Intermediate

**Q. [Theory] How do you prevent two scheduler nodes from firing the same job?**
Two layers. First, the coordination service assigns each **shard** to exactly one node via a lease, so normally only one node even *looks* at a given job. Second — and this is the real safety net — firing requires an **atomic compare-and-set** in the DB: `UPDATE jobs SET state='claimed' WHERE job_id=? AND state='scheduled' AND version=?`. Even if two nodes race (e.g. during a shard handoff), exactly one CAS wins; the loser sees the changed version and backs off. Critically, this works regardless of clock skew.

**Q. [Practical] A job is scheduled for 12:00:00 but the owning node crashes at 11:59:59. What happens?**
The job is still `state='scheduled'` in the durable DB — nothing was lost. The coordination service detects the dead node via lease expiry and reassigns its shards to a healthy node. That node's loader pulls the (now slightly overdue) job into its timer wheel and fires it immediately. The job runs a bit late but is **not dropped** — late is acceptable, dropped is not. The durable DB state, not the in-memory timer, is the source of truth.

**Q. [Theory] What's your delivery guarantee, honestly?**
At-least-once with idempotency. Strict exactly-once is impossible because a node can always crash in the gap between "I did the work" and "I recorded that I did the work" — on recovery you can't tell which side of the gap it died on. So we accept *bounded duplicates* and attach a deterministic idempotency key (`hash(job_id, scheduled_for, attempt)`) to every fire, letting the consumer dedup. The result is "effectively-once" from the consumer's perspective. For jobs where a duplicate is worse than a miss, we offer at-most-once mode (mark running before invoking, never retry on ambiguity).

**Q. [Practical] How do recurring jobs re-arm themselves?**
When a recurring job fires, we immediately compute its *next* fire time — advancing the cron expression in the job's timezone and converting to UTC — and write that as the new `next_run_at` back to the live store. This re-arm happens at fire time (not after execution completes), so the cadence stays accurate even if this run's execution is slow or fails. The reschedule and the claim are part of the same logical step.

### 🟠 Advanced

**Q. [Theory] Millions of jobs are scheduled for exactly midnight. How does the system survive the spike?**
Layered. (1) The keyspace is **sharded** across all scheduler nodes by `hash(job_id)`, so even 5M simultaneous jobs spread evenly — no single node owns the whole burst. (2) Schedulers only enqueue messages onto the **execution queue**, which acts as a shock absorber: they emit the entire burst near-instantly, and the autoscaled worker fleet drains it at a sustainable rate within the p999 window. (3) For jobs that tolerate it, we apply **deterministic jitter** (`next_run_at += hash(job_id) % window`) to flatten the spike at the source. (4) Per-tenant firing quotas stop one tenant's midnight batch from starving others.

**Q. [Coding] Write the core "claim and fire" logic that's safe against double-firing and crashes.**
The key is an atomic conditional claim plus a lease for crash recovery:
```python
def fire_due_jobs(db, queue, shard_ids, now, lease_ttl=30):
    # 1. Atomically claim a batch of due jobs (SKIP LOCKED avoids contention)
    claimed = db.execute("""
        UPDATE jobs
           SET state='claimed', lease_owner=:me, lease_expires=:now + :ttl,
               version = version + 1
         WHERE job_id IN (
             SELECT job_id FROM jobs
              WHERE shard = ANY(:shards)
                AND next_run_at <= :now
                AND state = 'scheduled'
              ORDER BY next_run_at
              FOR UPDATE SKIP LOCKED          -- no two workers block on same row
              LIMIT 1000
         )
        RETURNING job_id, target, type, cron, timezone, scheduled_for, attempt
    """, me=NODE_ID, now=now, ttl=lease_ttl, shards=shard_ids)

    for job in claimed:
        idem = sha256(f"{job.job_id}:{job.scheduled_for}:{job.attempt}")
        # 2. Re-arm recurring jobs BEFORE handing off, so cadence survives slow exec
        if job.type == 'recurring':
            nxt = next_fire(job.cron, job.timezone, after=job.scheduled_for)
            db.execute("UPDATE jobs SET state='scheduled', next_run_at=:n, version=version+1 "
                       "WHERE job_id=:id", n=nxt, id=job.job_id)
        else:
            db.execute("UPDATE jobs SET state='running' WHERE job_id=:id", id=job.job_id)
        # 3. Hand off to workers via the queue (idempotency key dedups duplicates)
        queue.enqueue({"job_id": job.job_id, "target": job.target,
                       "scheduled_for": job.scheduled_for,
                       "attempt": job.attempt, "idempotency_key": idem})

def reaper(db, now):                           # crash recovery: reclaim orphaned leases
    db.execute("""UPDATE jobs SET state='scheduled', lease_owner=NULL
                   WHERE state='claimed' AND lease_expires < :now""", now=now)
```
The `SKIP LOCKED` claim guarantees no two nodes grab the same row without lock contention; the lease + reaper guarantee a crashed node's jobs get re-fired; the idempotency key bounds the resulting duplicates.

**Q. [Theory] Why decouple scheduling from execution with a queue — what breaks if you don't?**
If the scheduler node both fires *and* runs the work, timing accuracy becomes hostage to execution duration. A single slow webhook (say a 30 s timeout) blocks the timer thread, and every other job that node owns fires late — lateness cascades. Decoupling means the scheduler does O(1) work per fire (enqueue and move on), so timing stays accurate no matter how slow targets are. The queue also absorbs the thundering-herd burst and lets you scale precision (scheduler nodes) and throughput (workers) independently. The cost — one extra hop, a few ms — is irrelevant against a 1 s SLO.

**Q. [Practical] How do you handle timezones and daylight saving time correctly?**
Store the cron expression *and* the timezone, never a fixed UTC offset — because a wall-clock `09:00` maps to different UTC instants across the DST boundary. After each fire, recompute the next run by advancing the cron in the job's tz and converting to UTC. Handle the two DST edge cases explicitly: on **spring-forward**, a wall-clock time in the skipped hour doesn't exist, so fire at the boundary (or skip) per policy; on **fall-back**, a time occurs twice, so fire only the **first** occurrence to avoid a duplicate. And never depend on clock synchronization for *correctness* — the DB CAS-claim serializes fires regardless of skew, so clock drift only affects how late a fire is, not whether it fires once.

### 🔴 Expert

**Q. [Theory] Where does this system sit on CAP, and how do you reason about it?**
It's split by concern. **Job state** (scheduled/cancelled/claimed) is **CP**: we'd rather refuse a write or briefly stall than let a cancelled job fire or let two nodes disagree on ownership — so the schedule store uses quorum writes and the coordination service uses consensus (Raft) for shard ownership. The **firing/execution path** leans **AP-with-recovery**: if a region or node is partitioned, the surviving side keeps firing its owned shards, and the lease/reaper mechanism re-fires anything orphaned once the partition heals (accepting possible duplicates, which idempotency keys absorb). So: strongly consistent ownership and state, availability-favoring firing with at-least-once recovery.

**Q. [Practical] How would you support jobs scheduled years in the future without bloating the hot path?**
Tier by fire-time proximity. The in-memory timer wheel and the hot DB index only ever hold the **near-term window** (next ~minutes). Far-future jobs (days/months/years out) live in a **cold tier** — cheaper storage, no timer-wheel entry — indexed only by their coarse fire time. A slow background **promoter** scans the cold tier and migrates jobs into the hot store as their fire time approaches (e.g. when within the next hour). This keeps the hot index proportional to *imminent* load, not total job count, so 500M jobs scheduled across a decade cost the same hot-path resources as 500M scheduled for tomorrow.

**Q. [Coding] A recurring job's target has been failing for hours and the DLQ is filling. Design the auto-pause guard. Sketch it.**
Track consecutive failures and trip a per-job circuit breaker:
```python
def on_run_result(db, job_id, success):
    if success:
        db.execute("UPDATE jobs SET consecutive_failures=0 WHERE job_id=:id", id=job_id)
        return
    row = db.execute("""UPDATE jobs
                           SET consecutive_failures = consecutive_failures + 1
                         WHERE job_id=:id
                         RETURNING consecutive_failures, retry_policy""", id=job_id)
    if row.consecutive_failures >= row.retry_policy.max_attempts:
        # terminal for this occurrence → DLQ
        dlq.enqueue(job_id)
    if row.consecutive_failures >= AUTO_PAUSE_THRESHOLD:      # e.g. 50 in a row
        db.execute("UPDATE jobs SET state='paused', paused_reason='target_failing' "
                   "WHERE job_id=:id", id=job_id)
        alert(f"Auto-paused {job_id}: {row.consecutive_failures} consecutive failures")
```
This stops a permanently-broken endpoint from generating thousands of futile fires per hour and flooding the DLQ. A human (or a health-probe) resumes the job once the target recovers. The threshold and backoff are per-tenant configurable so a flaky-but-recovering target isn't paused too eagerly.

**Q. [Theory] How would you make this active-active across two regions without firing every job twice?**
The naive approach — both regions own every job and coordinate on each fire — requires cross-region consensus per fire, which is far too slow. Instead, **partition ownership by region**: split the shard space so each region exclusively owns a disjoint set of shards, and a job's shard determines which region fires it. The schedule store replicates *all* data to both regions (for DR and reads), but only the owning region fires a given shard. On a regional failure, the survivor takes over the failed region's shards via the global coordination layer (with a brief lease-expiry delay), so jobs fire late but exactly as owned — no double-fire, because ownership transfer is consensus-gated. This trades a few seconds of failover lateness for the ability to avoid per-fire cross-region coordination.

**Q. [Behavioral] You shipped a change to the cron-parsing library and overnight a subset of recurring jobs stopped firing. Walk me through how you'd handle it.**
First, **stop the bleeding**: confirm scope (which jobs, which cron patterns — likely a specific expression class the new parser mishandles) using run-history dashboards, and if the change is the suspect, **roll it back** immediately rather than debug forward in production. Second, **assess data damage**: because fires are durable and idempotent, missed fires are recoverable — query jobs whose `next_run_at` is in the past but never fired, and decide per `misfire_policy` whether to catch-up (`fire_once`) or skip; don't blindly `catch_up_all`, which could emit a flood of duplicates. Third, **communicate**: notify affected tenants with the window of impact and what we replayed. Fourth, **root cause and prevent**: write a regression test with the exact cron expressions that broke, add a canary that schedules synthetic jobs across many cron patterns and alerts if any don't fire on time, and require parser changes to pass a golden-file suite of expression→next-fire-time cases before deploy. The systemic lesson is that a scheduler's correctness is invisible until something *doesn't* happen — so the fix isn't just the bug, it's the missing observability (a "did everything that should have fired actually fire?" reconciliation check) that let it go unnoticed overnight.

---

*Key takeaway: a distributed job scheduler is a study in firing reliably under failure. The interesting engineering is the DB-CAS-plus-lease claim that makes firing crash-safe without trusting clocks, decoupling timing from execution via a queue, taming the round-time thundering herd, and being honest that "exactly-once" is really "at-least-once + idempotency." Get those four right and the rest is sharding and observability.*
