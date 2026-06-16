# Reliability, Resilience & Operations

A deep, interview-focused guide to building, operating, and recovering distributed systems that stay up. Covers availability math, failure-handling patterns, deployment strategies, multi-region architecture, disaster recovery, chaos engineering, capacity planning, and incident response — with Java examples and ASCII diagrams.

[← Back to master index](../README.md)

## Table of Contents

- [🟢 Basic (0–2 yrs)](#-basic-02-yrs)
- [🟡 Intermediate (3–7 yrs)](#-intermediate-37-yrs)
- [🟠 Advanced (8–12 yrs)](#-advanced-812-yrs)
- [🔴 Expert (15+ yrs)](#-expert-15-yrs)
- [✅ Key Takeaways](#-key-takeaways)
- [⚠️ Common Pitfalls](#-common-pitfalls)
- [📚 Further Reading](#-further-reading)

---

## 🟢 Basic (0–2 yrs)

### Q1. [Theory] What do "nines" of availability mean, and how do you compute allowed downtime?

Availability is the fraction of time a system successfully serves requests, usually expressed as a percentage and informally as "nines." Each additional nine reduces tolerated downtime by an order of magnitude. The formula is `Availability = uptime / (uptime + downtime)`, often re-expressed as `MTBF / (MTBF + MTTR)`. The "why" matters: each nine is exponentially harder and more expensive, so you should target the level the business actually needs rather than blindly chasing five nines.

```
Availability   Downtime/year   Downtime/month   Downtime/day
99%      (2 nines)   3.65 days       7.2 hours        14.4 min
99.9%    (3 nines)   8.77 hours      43.8 min         1.44 min
99.95%               4.38 hours      21.9 min         43 sec
99.99%   (4 nines)   52.6 min        4.38 min         8.6 sec
99.999%  (5 nines)   5.26 min        26.3 sec         0.86 sec
```

A subtle trade-off: a system composed of N independent series components multiplies their availabilities. If a request must traverse 5 services each at 99.9%, the end-to-end availability is `0.999^5 ≈ 99.5%`. Redundancy (parallel paths) is how you claw nines back.

### Q2. [Theory] Define MTBF, MTTR, MTTD, and MTTF. Why does MTTR often matter more than MTBF?

- **MTBF** (Mean Time Between Failures): average operating time between two failures of a repairable system.
- **MTTF** (Mean Time To Failure): expected lifetime of a non-repairable component (e.g., a disk).
- **MTTD** (Mean Time To Detect): how long until you notice a failure.
- **MTTR** (Mean Time To Recovery/Repair): how long to restore service after detection.

Because `Availability = MTBF / (MTBF + MTTR)`, you improve uptime either by failing less (raise MTBF) or recovering faster (lower MTTR). In modern distributed systems failures are inevitable, so the highest-leverage investment is usually reducing MTTR — fast detection, automated rollback, and self-healing — rather than trying to make every component perfect. Halving MTTR has the same availability effect as doubling MTBF, but is typically cheaper and more achievable.

### Q3. [Theory] What is the difference between reliability, availability, and resilience?

**Reliability** is the probability the system performs correctly over a time interval (correctness over time). **Availability** is the probability it is operational at a given moment. **Resilience** is the ability to absorb faults and degrade gracefully rather than fail catastrophically, then recover. A system can be highly available but unreliable (it's up but returns wrong answers), or reliable but unavailable (correct when up, but frequently down). Resilience is the engineering discipline — circuit breakers, retries, bulkheads — that turns inevitable component failures into bounded, survivable incidents instead of cascading outages.

### Q4. [Practical] What is a health check, and what's the difference between a liveness and a readiness probe?

A health check is an endpoint (e.g., `/healthz`) that an orchestrator or load balancer polls to decide whether to route traffic to or restart an instance.

- **Liveness**: "Is the process wedged?" If it fails, the orchestrator *restarts* the pod. Keep it cheap and dependency-free — never check the database in a liveness probe, or a DB blip will trigger a restart storm.
- **Readiness**: "Can this instance serve traffic *right now*?" If it fails, the instance is pulled from the load balancer rotation but **not** killed. This is where you check downstream dependencies, warm caches, and connection pools.

```
        ┌─────────────┐  liveness fail → RESTART pod
LB/k8s →│  /livez      │
        │  /readyz     │  readiness fail → REMOVE from rotation (no kill)
        └─────────────┘
```

Spring Boot 2.3+ exposes these out of the box via `actuator/health/liveness` and `actuator/health/readiness`. A startup probe (Kubernetes) protects slow-booting apps from being killed before they finish initializing.

### Q5. [Coding] Implement a thread-safe timeout wrapper around a slow call in Java.

**Problem:** Wrap a potentially slow downstream call so it fails fast after a deadline instead of blocking a request thread indefinitely. Unbounded waits are the #1 cause of thread-pool exhaustion and cascading failure.

```java
import java.util.concurrent.*;

public class TimeoutExecutor {
    // Bounded pool so we never spawn unbounded threads under load.
    private static final ExecutorService POOL =
        Executors.newFixedThreadPool(20);

    public static <T> T callWithTimeout(Callable<T> task, long timeoutMs)
            throws Exception {
        Future<T> future = POOL.submit(task);
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);        // interrupt the worker thread
            throw new TimeoutException("Call exceeded " + timeoutMs + "ms");
        } catch (ExecutionException e) {
            throw (Exception) e.getCause();   // unwrap real failure
        }
    }
}
```

**Java 21+ alternative — virtual threads + structured concurrency** make this far cleaner and avoid the orphaned-task problem (`future.cancel(true)` only sets an interrupt flag; blocking I/O on platform threads may ignore it):

```java
// Java 21 (preview) / 25 structured concurrency
try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
    var task = scope.fork(() -> slowDownstreamCall());
    scope.joinUntil(Instant.now().plusMillis(500));  // deadline
    scope.throwIfFailed();
    return task.get();
}   // scope auto-cancels remaining subtasks on close
```

- **Time:** O(1) overhead per call. **Space:** O(active concurrent calls).
- **Edge cases:** task that ignores interrupts (use HTTP-client-level read timeouts too — never rely on thread interruption alone); pool saturation (reject fast rather than queue unboundedly); always set a *connect* timeout in addition to a *read* timeout.

### Q6. [Practical] Why should every network call have a timeout, and what happens without one?

Without an explicit timeout, a call inherits the OS default — often tens of seconds to minutes. When a downstream slows down (not even fails), every caller thread blocks waiting. The thread pool fills, new requests queue, queues overflow, and the *caller* goes down even though it was healthy — classic cascading failure. The rule: every I/O boundary needs both a **connect timeout** and a **read/response timeout**, and timeouts should be budgeted (the sum of downstream timeouts must be less than the upstream timeout, leaving room for retries). In `RestTemplate`/`WebClient`/`HttpClient`, set them explicitly; defaults are usually unsafe for production.

---

## 🟡 Intermediate (3–7 yrs)

### Q7. [Theory] Explain the Circuit Breaker pattern and its three states.

A circuit breaker prevents a client from hammering a failing dependency, giving it time to recover and protecting caller resources. It wraps calls and tracks failure rates.

```
        success rate OK
   ┌──────────────────────────┐
   │                          │
   ▼          failures > threshold
[CLOSED] ─────────────────────────► [OPEN]
   ▲                                   │ wait (cooldown / sleepWindow)
   │ trial succeeds                    ▼
   └────────────────────────── [HALF-OPEN]
              trial fails ──────────┘ (back to OPEN)
```

- **Closed:** calls pass through; failures are counted in a sliding window.
- **Open:** the threshold (e.g., 50% failures over the last N calls) is exceeded; calls fail fast immediately (or run a fallback) without touching the dependency.
- **Half-Open:** after a cooldown, a limited number of trial calls are allowed; success closes the circuit, failure re-opens it.

The "why": failing fast frees caller threads and sheds load from a struggling dependency so it can recover, instead of being overwhelmed by retries. Trade-off: a breaker that's too sensitive flaps; one that's too lax doesn't protect. Resilience4j (the modern successor to Netflix Hystrix, which is in maintenance mode) supports both count-based and time-based sliding windows.

### Q8. [Coding] Implement a circuit breaker using Resilience4j in Java.

**Problem:** Protect a payment-gateway call so a gateway outage doesn't take down the order service.

```java
import io.github.resilience4j.circuitbreaker.*;
import java.time.Duration;
import java.util.function.Supplier;

public class PaymentClient {

    private final CircuitBreaker breaker;

    public PaymentClient() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(20)            // last 20 calls
            .failureRateThreshold(50)         // open at 50% failures
            .slowCallRateThreshold(80)        // treat slow as failure
            .slowCallDurationThreshold(Duration.ofSeconds(2))
            .waitDurationInOpenState(Duration.ofSeconds(10))
            .permittedNumberOfCallsInHalfOpenState(3)
            .build();
        this.breaker = CircuitBreaker.of("payment", config);
    }

    public String charge(String orderId) {
        Supplier<String> decorated = CircuitBreaker
            .decorateSupplier(breaker, () -> callGateway(orderId));
        // Fallback when breaker is OPEN or call fails.
        return io.vavr.control.Try.ofSupplier(decorated)
            .recover(throwable -> "QUEUED_FOR_RETRY")   // graceful degradation
            .get();
    }

    private String callGateway(String orderId) {
        // real HTTP call with its own timeout
        return "CHARGED:" + orderId;
    }
}
```

- **Time:** O(1) state transition per call (ring buffer). **Space:** O(slidingWindowSize).
- **Edge cases:** distinguish *expected* business failures (insufficient funds → should NOT trip the breaker) from infrastructure failures (timeouts, 5xx → should). Use `recordException`/`ignoreException` predicates. Always pair a breaker with a fallback; an open breaker with no fallback just converts slow failure into fast failure.

### Q9. [Theory] What is the Bulkhead pattern and how does it differ from a circuit breaker?

A bulkhead isolates resources (thread pools, connection pools, or semaphore permits) so that exhaustion in one component cannot starve others — named after watertight compartments in a ship's hull. If service A and service B share a single 200-thread pool and B hangs, B will consume all 200 threads and take A down with it. With separate bulkheads (e.g., 100 threads each), B's failure is contained to B's quota.

```
Without bulkhead:        With bulkhead:
   ┌──────────────┐        ┌────────┬────────┐
   │  one pool     │        │ pool A │ pool B │
   │  (200)        │        │ (100)  │ (100)  │
   └──────────────┘        └────────┴────────┘
 B hangs → A starves      B hangs → A unaffected
```

The difference: a **circuit breaker** stops calling a *failing* dependency (failure detection over time), while a **bulkhead** caps *concurrency* to a dependency so it can never monopolize shared resources (resource isolation). They're complementary — you typically use both. Resilience4j offers `SemaphoreBulkhead` (lightweight, caps in-flight calls) and `ThreadPoolBulkhead` (full isolation with a dedicated pool).

### Q10. [Practical] How do you make retries safe? Discuss idempotency, jitter, and budgets.

Naive retries are dangerous: they amplify load on an already-struggling system (a "retry storm") and can cause duplicate side effects. Production-safe retries require:

1. **Idempotency** — only retry operations that are safe to repeat. For non-idempotent writes (charge a card), use an **idempotency key** so the server deduplicates. GET/PUT/DELETE are naturally idempotent; POST usually is not.
2. **Exponential backoff with jitter** — don't retry immediately or in lockstep, or all clients retry at the same instant (thundering herd). Use full jitter: `sleep = random(0, min(cap, base * 2^attempt))`.
3. **Retry budgets / circuit breaking** — cap retries as a percentage of total traffic (e.g., max 10% extra). When the budget is exhausted, stop retrying.
4. **Retry only retryable errors** — 503/timeout yes; 400/401/422 no (retrying a bad request just wastes resources).

```java
// AWS-style full jitter backoff
long base = 100, cap = 10_000;
long delay = ThreadLocalRandom.current()
        .nextLong(0, Math.min(cap, base * (1L << attempt)));
```

What I'd do in production: retries at the *edge* of a hop (not nested across many layers — nesting multiplies attempts exponentially), budget-limited, with jitter, gated behind a circuit breaker, and only for idempotent or idempotency-keyed operations.

### Q11. [Theory] What is backpressure and how do you implement it?

Backpressure is a flow-control mechanism where a consumer signals to a producer that it cannot keep up, so the producer slows down rather than letting unbounded data accumulate and exhaust memory. Without it, a fast producer + slow consumer leads to growing queues, GC pressure, OOM, and latency collapse. Mechanisms include: bounded queues that block or reject when full, the Reactive Streams `request(n)` demand signal (Project Reactor / RxJava), TCP's sliding window at the network layer, and Kafka consumer `max.poll.records` / pause-resume. The key trade-off is what to do when the buffer is full: **block** (apply backpressure upstream), **drop** (shed load — newest or oldest), or **error**. Backpressure pushes the decision to the right place instead of letting an intermediate buffer silently grow without bound.

### Q12. [Theory] Compare graceful degradation and load shedding.

**Graceful degradation** means reducing functionality to preserve the core experience when a dependency is impaired — e.g., if the recommendation service is down, show a generic best-sellers list instead of failing the whole product page. **Load shedding** means deliberately rejecting a fraction of requests when the system is overloaded, to protect the requests it *can* serve and prevent total collapse. Degradation trades *features* for availability; shedding trades *some requests* for the survival of the rest. Both follow the principle "a partial response beats no response." Effective shedding is *priority-aware*: drop low-value traffic (background jobs, non-paying users, retries) before high-value traffic (checkout). The counterintuitive insight: under overload, accepting fewer requests increases total successful throughput, because goodput collapses when every request is too slow to be useful (the "congestion collapse" cliff).

### Q13. [Coding] Implement a token-bucket rate limiter for load shedding in Java.

**Problem:** Cap a service at N requests/second, shedding excess to protect downstream capacity.

```java
import java.util.concurrent.atomic.AtomicLong;

public class TokenBucket {
    private final long capacity;
    private final double refillPerNano;   // tokens per nanosecond
    private final AtomicLong tokens;       // fixed-point: tokens * 1000
    private final AtomicLong lastRefill;

    public TokenBucket(long ratePerSec, long burstCapacity) {
        this.capacity = burstCapacity * 1000;
        this.refillPerNano = (ratePerSec * 1000.0) / 1_000_000_000.0;
        this.tokens = new AtomicLong(this.capacity);
        this.lastRefill = new AtomicLong(System.nanoTime());
    }

    public boolean tryAcquire() {
        refill();
        long current;
        do {
            current = tokens.get();
            if (current < 1000) return false;   // shed: no token
        } while (!tokens.compareAndSet(current, current - 1000));
        return true;
    }

    private void refill() {
        long now = System.nanoTime();
        long last = lastRefill.get();
        long add = (long) ((now - last) * refillPerNano);
        if (add > 0 && lastRefill.compareAndSet(last, now)) {
            long updated = Math.min(capacity, tokens.get() + add);
            tokens.set(updated);
        }
    }
}
```

- **Time:** O(1) per request (lock-free CAS). **Space:** O(1).
- **Edge cases:** clock skew (use a monotonic clock — `nanoTime`, never `currentTimeMillis`); burst handling (capacity > rate allows short bursts); distributed limiting needs a shared store (Redis with a Lua script for atomicity) instead of per-instance buckets. Compare with the **leaky bucket** (smooths output to a constant rate) — token bucket allows bursts, leaky bucket does not.

### Q14. [Theory] Explain blue-green, canary, and rolling deployments. When would you choose each?

```
Rolling:   v1 v1 v1 v1  →  v2 v1 v1 v1  →  v2 v2 v1 v1  → ... → v2 v2 v2 v2
           (replace instances batch by batch; no extra capacity)

Blue-Green:  [BLUE v1] ◄ 100% traffic     [BLUE v1]  ◄ 0%
             [GREEN v2]  (idle, warming)   [GREEN v2] ◄ 100%  (flip router)

Canary:    [v1] ◄ 95%      gradually      [v1] ◄ 50%      then  [v2] ◄ 100%
           [v2] ◄  5%   ───────────────►  [v2] ◄ 50%   ──────►
```

- **Rolling:** replace instances in batches. No extra infra cost, but v1 and v2 run simultaneously (must be backward compatible), and rollback is slow (roll back batch by batch). Kubernetes default.
- **Blue-green:** stand up a full parallel environment (green), test it, then flip the router atomically. Instant rollback (flip back), but doubles infrastructure cost during the cutover and needs DB schema compatibility across both.
- **Canary:** route a small slice of real traffic to v2 while watching metrics (error rate, latency, business KPIs); promote gradually or auto-rollback on regression. Best risk control, but most complex (needs strong observability and automated analysis, e.g., Argo Rollouts / Flagger / Spinnaker).

What I'd choose: canary for high-blast-radius user-facing services, blue-green when I need instant rollback and can afford double capacity, rolling for stateless internal services where cost matters and risk is low.

### Q15. [Practical] How do you implement graceful shutdown so in-flight requests aren't dropped?

Graceful shutdown means: stop accepting new work, finish in-flight work within a grace period, then exit. The sequence in Kubernetes:

```
1. k8s sends SIGTERM + removes pod from Service endpoints
2. App: fail readiness probe → LB stops sending new requests
3. App: stop accepting new connections, drain in-flight requests
4. App: close DB pools, flush buffers, deregister from discovery
5. After terminationGracePeriodSeconds, k8s sends SIGKILL (force)
```

The classic race: endpoints removal is *eventually consistent*, so traffic may still arrive for a few seconds after SIGTERM. Mitigate with a `preStop` sleep (e.g., 5–10s) before shutdown so the LB fully drains first. In Spring Boot 2.3+, set `server.shutdown=graceful` and `spring.lifecycle.timeout-per-shutdown-phase=30s`. Always handle SIGTERM (not just SIGKILL, which can't be caught) and make grace period > your longest expected request.

```java
Runtime.getRuntime().addShutdownHook(new Thread(() -> {
    readiness.markDown();                 // fail readiness first
    server.stopAcceptingNewRequests();
    inFlight.awaitCompletion(30, SECONDS);
    pool.close();
}));
```

### Q16. [Practical] You're seeing intermittent 504s under peak load. Walk through diagnosis.

Approach: 504 = gateway timeout, so something downstream is too slow, not necessarily erroring. I'd work the request path methodically:

1. **Scope it:** Which route/service? Is it correlated with traffic spikes, a specific instance, or a deploy? Check the four golden signals (latency, traffic, errors, saturation).
2. **Find the slow hop:** Use distributed tracing (OpenTelemetry/Jaeger) to find which span blows the latency budget — usually a DB query, a downstream call, or lock contention.
3. **Check saturation:** Thread pool / connection pool exhaustion (queue depth growing), CPU throttling (k8s CPU limits), GC pauses (long STW), or DB connection pool maxed (`HikariCP` pending threads).
4. **Common root causes:** missing index causing full scans under load; N+1 queries; an undersized connection pool; a slow dependency without a timeout causing pile-up; no backpressure.

What I'd do: add/verify timeouts and a circuit breaker on the slow hop, right-size the connection pool (often *smaller* is better — fewer DB connections reduce contention), add load shedding at the edge, and fix the underlying query. Then load-test to confirm the knee of the curve moved.

---

## 🟠 Advanced (8–12 yrs)

### Q17. [Theory] Compare active-active and active-passive multi-region architectures, including RTO/RPO implications.

```
Active-Passive:                    Active-Active:
  Region A (primary)  ──serves      Region A ──serves──┐
       │ async replication            │  bi-directional│ replication
       ▼                              ▼  (conflict res) ▼
  Region B (standby)  idle         Region B ──serves──┘
  failover = promote B             both regions live, traffic split
```

**Active-passive:** one region serves; the other is a warm/cold standby kept in sync via replication. Failover requires detecting the outage and promoting the standby (DNS/Global LB switch, DB promotion). Simpler, cheaper, no conflict resolution — but the standby capacity sits idle and **RTO** (time to recover) is non-zero (minutes), with potential data loss equal to replication lag (**RPO** > 0 for async).

**Active-active:** both regions serve traffic simultaneously, so a region loss means just routing away the failed region — near-zero RTO and full capacity utilization. But it's far harder: you need conflict resolution for concurrent writes (last-writer-wins, CRDTs, or a single-writer-per-key partitioning scheme), low write latency despite cross-region replication, and you must run at <50% utilization per region so the survivor can absorb full load.

The core trade-off is **consistency vs. availability vs. cost** (CAP/PACELC). Active-active with strong global consistency forces every write to cross regions (high latency); most systems instead use regional sharding (each user "homed" to a region) or eventual consistency to keep writes local.

### Q18. [Theory] Define RTO and RPO precisely and explain the disaster-recovery strategy tiers.

- **RPO (Recovery Point Objective):** the maximum acceptable *data loss*, measured in time. RPO of 5 minutes means you can lose up to 5 minutes of data → drives backup/replication frequency.
- **RTO (Recovery Time Objective):** the maximum acceptable *downtime* to restore service → drives your failover automation and standby readiness.

```
        ◄──── RPO ────►│ disaster │◄──────── RTO ────────►
   last good backup    │   here   │   service restored
   (data loss window)             │   (downtime window)
```

DR strategy tiers (AWS taxonomy), cheaper/slower → costlier/faster:

| Strategy        | RPO        | RTO        | Cost   | Description |
|-----------------|-----------|-----------|--------|-------------|
| Backup & Restore| hours     | hours+    | $      | Restore from backups in DR region |
| Pilot Light     | minutes   | ~10s min  | $$     | Core data replicated, minimal infra "lit" |
| Warm Standby    | seconds   | minutes   | $$$    | Scaled-down full stack running, scale up on failover |
| Active-Active   | ~zero     | ~zero     | $$$$   | Full multi-region live |

The discipline that distinguishes seniors: **a backup you haven't restored is not a backup.** You must regularly run restore drills and DR game-days, because untested backups silently fail (wrong encryption keys, missing schema, corrupted dumps, expired credentials).

### Q19. [Coding] Implement an adaptive concurrency limiter (additive-increase/multiplicative-decrease) for load shedding.

**Problem:** Static rate limits are brittle — the safe limit changes with downstream health. Implement an AIMD limiter (the algorithm behind Netflix `concurrency-limits` and TCP congestion control) that probes for capacity and backs off on signs of overload (latency rise / rejections).

```java
import java.util.concurrent.atomic.*;

public class AdaptiveLimiter {
    private volatile double limit = 10;          // current concurrency limit
    private final AtomicInteger inFlight = new AtomicInteger();
    private final double maxLimit = 200, minLimit = 1;
    private volatile long rttBaselineNanos = Long.MAX_VALUE;

    public boolean tryAcquire() {
        if (inFlight.get() >= (int) limit) return false;   // shed
        inFlight.incrementAndGet();
        return true;
    }

    /** Call on each completion with measured RTT and success flag. */
    public void record(long rttNanos, boolean success) {
        inFlight.decrementAndGet();
        rttBaselineNanos = Math.min(rttBaselineNanos, rttNanos);

        if (!success || rttNanos > rttBaselineNanos * 2) {
            // overload signal → multiplicative decrease
            limit = Math.max(minLimit, limit * 0.8);
        } else if (inFlight.get() * 2 >= limit) {
            // operating near limit & healthy → additive increase
            limit = Math.min(maxLimit, limit + 1);
        }
    }
}
```

- **Time:** O(1) per request. **Space:** O(1).
- **Edge cases:** cold start (RTT baseline unknown — seed conservatively); flapping limit (smooth with EWMA on RTT); coordinated omission in latency measurement (measure from request *arrival*, not service start). The advantage over static limits: it self-tunes as downstream capacity changes (deploys, autoscaling, partial degradation) without manual reconfiguration.

### Q20. [Theory] What is chaos engineering, and how do you run it safely in production?

Chaos engineering is the practice of deliberately injecting failures into a system to validate that its resilience mechanisms actually work *before* a real incident does the testing for you. The principle: you don't know your system tolerates a failure until you've caused that failure and observed recovery. Netflix pioneered it with Chaos Monkey (randomly killing instances) and later the Simian Army / ChAP.

The disciplined method:
1. **Define steady state** — a measurable healthy metric (e.g., orders/sec within normal band).
2. **Form a hypothesis** — "if region B's database fails over, orders/sec stays within 5% of baseline."
3. **Minimize blast radius** — start in staging, then a tiny % of production traffic; have an automated abort (kill switch) that halts the experiment if the steady-state metric degrades.
4. **Inject the fault** — kill instances, add latency, drop packets, fill disks, partition the network (tools: Gremlin, Chaos Mesh, AWS FIS, LitmusChaos).
5. **Measure, learn, fix** — every surprise is a resilience bug to fix and an alert to add.

Run game-days (announced, team-wide exercises) before unannounced experiments. The cultural payoff is as important as the technical: chaos engineering builds confidence to deploy and operate, and trains incident response.

### Q21. [Practical] How do you do capacity planning for a service expecting 10x growth?

Approach — model first, then validate empirically:

1. **Establish the unit cost.** Load-test to find how much one instance handles before the *knee* (latency degrades non-linearly) — e.g., 500 RPS/instance at p99 < 200ms. Plan headroom to ~60–70% of that, never 100%.
2. **Find the bottleneck resource.** It's rarely CPU uniformly — could be DB IOPS, connection pool, network, a downstream quota, or a hot partition. Capacity is gated by the *first* resource to saturate (Little's Law: `concurrency = arrival_rate × latency`).
3. **Account for non-linearity.** 10x traffic ≠ 10x of everything: DB write contention, lock hotspots, cache hit-ratio cliffs, and cross-AZ bandwidth often scale worse than linearly. Connection counts and coordination overhead can scale super-linearly.
4. **Plan for peak, not average.** Size for peak (Black Friday, time-zone overlap) plus burst, plus the loss of one AZ/region (`N+1`/`2N` redundancy). If active-active across 2 regions, each must hold ~full load.
5. **Add autoscaling with guardrails.** HPA on the right signal (often a custom metric like queue depth, not just CPU), with max limits to bound cost and avoid scaling into a downstream that can't keep up (which just moves the bottleneck).

What I'd actually do: build a simple capacity model spreadsheet keyed on the bottleneck resource, validate with a load test at 2x and extrapolate cautiously, pre-provision stateful tiers (DBs don't autoscale instantly), and run a load test at projected peak before the event.

### Q22. [Practical] Design a load and performance testing strategy. What's the difference between load, stress, soak, and spike tests?

A complete strategy uses several test types, each answering a different question:

- **Load test:** behavior at expected peak load. Validates SLOs (p99 latency, error rate) hold at target throughput.
- **Stress test:** push beyond capacity until it breaks, to find the breaking point and verify it *degrades gracefully* (sheds load) rather than collapsing.
- **Spike test:** sudden 10x surge then drop, to test autoscaling reaction time and that the system recovers (no retry storm, no cache stampede).
- **Soak/endurance test:** sustained moderate load for hours/days to surface memory leaks, connection leaks, log-disk fill, and resource exhaustion that only appear over time.

```
RPS │      ╱╲ spike        ────── soak (hours) ──────
    │     ╱  ╲      load → ███████████████████
    │ ___╱    ╲___        stress → ▲ push to break ▲
    └──────────────────────────────────────────► time
```

What I'd do in production: test against a production-like environment (same instance types, data volume — performance is non-linear in data size), use realistic traffic mixes (recorded/replayed production traffic beats synthetic), include think-time and realistic concurrency, avoid coordinated omission in the harness (use `wrk2`, Gatling, k6, or Vegeta which keep a constant request rate), and tie pass/fail to SLOs. Run a perf test in CI on critical paths to catch regressions early.

### Q23. [Theory] What causes cascading failures and the "thundering herd" / "retry storm," and how do you prevent them?

A cascading failure occurs when the failure of one component overloads others, which fail in turn, until the whole system collapses. Common triggers:

- **Retry storms:** a slow dependency causes clients to retry, multiplying load (3 retries = 3–4x traffic) onto an already-struggling service, deepening the outage.
- **Thundering herd:** many clients react simultaneously to the same event — e.g., a cache key expires and 10,000 requests stampede the DB to rebuild it, or all clients reconnect at once after a blip.
- **Resource exhaustion ripple:** thread/connection pool fills on service A, so A's callers time out and pile up, propagating upstream.

Prevention toolkit: timeouts everywhere (bound the wait), circuit breakers (stop hammering the dead), bulkheads (isolate the blast radius), backoff *with jitter* (de-synchronize), retry budgets (cap amplification), **request coalescing / single-flight** (one DB call rebuilds the cache while others wait), **load shedding** (protect goodput), and **autoscaling that doesn't react to the symptom** (scaling on latency caused by a downstream just adds load). The deepest lesson: most large outages are not the original fault but the *amplification* by well-intentioned recovery mechanisms.

### Q24. [Coding] Implement single-flight request coalescing to prevent a cache-stampede thundering herd.

**Problem:** When a hot cache key expires, thousands of concurrent requests all miss and stampede the database. Coalesce them so exactly one rebuilds the value while the rest wait and share the result.

```java
import java.util.concurrent.*;

public class SingleFlightCache<K, V> {
    private final ConcurrentHashMap<K, CompletableFuture<V>> inFlight =
        new ConcurrentHashMap<>();

    /** Only one loader runs per key concurrently; others share its result. */
    public V get(K key, Callable<V> loader) throws Exception {
        CompletableFuture<V> future = inFlight.computeIfAbsent(key, k -> {
            CompletableFuture<V> f = new CompletableFuture<>();
            // Load asynchronously; first caller triggers it.
            ForkJoinPool.commonPool().execute(() -> {
                try {
                    f.complete(loader.call());
                } catch (Exception e) {
                    f.completeExceptionally(e);
                } finally {
                    inFlight.remove(k);   // allow next refresh after completion
                }
            });
            return f;
        });
        try {
            return future.get(2, TimeUnit.SECONDS);  // bound the wait
        } catch (ExecutionException e) {
            throw (Exception) e.getCause();
        }
    }
}
```

- **Time:** O(1) map ops; loader runs once per key per refresh window regardless of concurrent callers. **Space:** O(distinct in-flight keys).
- **Edge cases:** the loader throwing should remove the entry so the next request retries (don't cache the failure permanently); add a timeout on `future.get` so a hung loader doesn't block all waiters forever; for distributed stampedes you also need a *distributed* lock (Redis `SETNX` with TTL) since this only coalesces within one JVM. A complementary tactic is **probabilistic early expiration** (refresh slightly before TTL to avoid the synchronized miss entirely).

---

## 🔴 Expert (15+ yrs)

### Q25. [Theory] How do SLI, SLO, SLA, and error budgets drive reliability engineering decisions?

- **SLI** (Service Level Indicator): a measured signal of behavior, e.g., the fraction of requests served under 300ms or without 5xx.
- **SLO** (Service Level Objective): the internal target for an SLI, e.g., 99.9% of requests succeed over 28 days. This is the number you actually engineer to.
- **SLA** (Service Level Agreement): the *contractual* commitment to customers, with financial penalties — always set looser than the SLO so you have margin before breaching the contract.
- **Error budget:** `1 − SLO`. At 99.9%, you're allowed 0.1% failures ≈ 43 minutes/month of "badness."

The error budget is the central management tool of SRE: it converts reliability from an absolute ("never go down") into a *budget to spend*. If the budget is healthy, teams can ship fast and take risks; if it's exhausted, a **policy** kicks in — freeze feature launches and redirect engineering to reliability until the budget recovers. This aligns dev (wants velocity) and ops (wants stability) on one shared, quantified number, ending the perpetual feature-vs-stability tug-of-war. The "why" for leadership: 100% is the wrong target — it's infinitely expensive and users can't perceive the difference above their own network's reliability. The right reliability is the *minimum* that keeps users happy, freeing the rest of the budget for velocity.

### Q26. [Practical] Walk through your incident-response process from page to postmortem.

A mature incident process (PagerDuty / Google SRE model):

```
DETECT → TRIAGE → MITIGATE → RESOLVE → LEARN
  │        │         │          │         │
 alert  declare    stop the   fix root  blameless
 fires  severity   bleeding   cause     postmortem
        + roles    (rollback,
                    failover,
                    shed load)
```

1. **Detect** — alert on *symptoms users feel* (SLO burn rate), not every internal metric, to avoid alert fatigue. Use multi-window burn-rate alerts (fast burn = page now; slow burn = ticket).
2. **Declare & assign roles** — for anything beyond trivial, declare an incident with a clear **Incident Commander** (coordinates, doesn't fix), **Ops/Subject lead** (hands on keyboard), and **Communications lead** (status page, stakeholders). Separating roles prevents the classic failure where the only engineer who knows the system is also fielding VP questions.
3. **Mitigate before diagnose** — the first job is to **stop user pain**, not find root cause. Roll back the recent deploy, fail over, shed load, or flip a feature flag *first*; investigate the "why" after the bleeding stops.
4. **Communicate** — regular cadence updates internally and on the public status page; honesty preserves trust.
5. **Resolve & verify** — confirm SLIs recovered, not just that the obvious symptom cleared.
6. **Blameless postmortem** — document timeline, contributing factors, what went well/poorly, and concrete action items with owners and due dates. Track them to completion.

The single biggest lever is a good **runbook** for each alert and a culture that pages a human only when a human must act.

### Q27. [Behavioral] Tell me about a time you led the response to a major production incident.

(Use STAR — the interviewer is assessing judgment, calm, and learning, not heroics.)

**Situation:** A payment service began returning elevated 5xx during a peak sale; checkout success dropped ~30% and revenue impact was accruing by the minute.

**Task:** As on-call lead, I had to restore service fast while coordinating a cross-team response under executive pressure.

**Action:** I declared a SEV-1 and explicitly took the Incident Commander role, assigning a comms lead so I could stay focused on the technical bridge. We resisted the urge to debug first — telemetry showed the regression aligned with a deploy 20 minutes earlier, so we **rolled back immediately** (mitigate before diagnose), which restored checkout within ~6 minutes. Only then did we investigate: the deploy had shrunk a connection pool, causing saturation under peak load. We added load shedding and a circuit breaker on the payment hop as a stopgap.

**Result:** Total customer impact was ~10 minutes. In the blameless postmortem we identified three systemic gaps — no automated canary analysis on that service, a connection-pool change with no load test, and an alert that fired on the symptom too late. We added burn-rate alerting, made canary deploys mandatory for the payment path, and added the pool sizing to load-test CI. No recurrence in the following year.

**Reflection:** The lesson I emphasize when mentoring: optimize for *time-to-mitigate*, separate command from keyboard, and treat the postmortem as the most valuable deliverable — the incident is wasted if it doesn't change the system.

### Q28. [Theory] Why is 100% availability the wrong target, and how do you decide the right SLO?

Targeting 100% is an engineering and business error for several reasons. First, cost scales super-linearly with each nine; the marginal dollar buys vanishingly little perceived value. Second, the *user's* experienced reliability is capped by everything between them and you — their device, Wi-Fi, ISP, DNS, and CDN. If a user's own connection is 99% reliable, your improvement from 99.9% to 99.99% is imperceptible to them. Third, chasing 100% removes all error budget, which means zero room to ship changes — yet change is where value comes from, and paradoxically *velocity freezes* make systems *less* reliable over time (big-bang releases, stale dependencies, atrophied deploy muscles).

The right SLO is derived from user happiness, not aspiration: measure where users actually notice and abandon, look at the reliability of dependencies you don't control, consider the cost of each nine versus revenue/risk it protects, and set the SLO at the *minimum* level that keeps users satisfied. Then deliberately *spend* the remaining budget on innovation. Critical paths (checkout, auth) justify more nines than secondary features (recommendations), so SLOs should be tiered per user journey, not a single global number.

### Q29. [Practical] How do you architect a globally distributed, highly available data tier? Discuss consistency trade-offs.

The hard truth (PACELC, extending CAP): even when there's no partition, you trade latency for consistency. Strong global consistency means every write achieves quorum across regions — adding cross-region RTT (100ms+) to every write. Approaches, from strongest to most available:

```
Strong global (Spanner/CockroachDB, TrueTime/Raft):
   linearizable, but writes pay cross-region quorum latency
Regional sharding ("home region" per entity):
   writes local & fast; cross-region reads may be stale; clean ownership
Multi-leader / eventual (Dynamo, Cassandra):
   writes local everywhere; concurrent-write conflicts → resolution needed
```

What I'd architect for most products: **partition by entity ownership** — pin each user/tenant to a home region so their writes stay local and strongly consistent, replicate asynchronously to other regions for reads and DR, and only reach for full active-active multi-master where a single entity is genuinely written from multiple regions (rare). For inherently multi-writer data, use **CRDTs** (conflict-free replicated data types) or carefully chosen LWW with vector clocks, accepting eventual consistency. Critically, expose the consistency model to product owners: "this counter may be a few seconds stale across regions" is a *product* decision, not just an infra one. Pair it with idempotent writes, monotonic reads where users need them (read-your-writes via session stickiness or read-from-leader), and a clear story for the split-brain case (fencing tokens, a single source of truth for promotion).

### Q30. [Theory] How do you build organizational and architectural resilience against correlated and gray failures?

Most reliability frameworks assume *independent* failures, but the outages that cause real damage are **correlated** (one cause hits many components) and **gray** (partial, ambiguous — the system is "mostly up" so automated failover doesn't trigger, but users suffer).

**Correlated failure** sources to design against: a shared dependency (one config service, one DNS, one certificate authority — expiring certs and bad config pushes are top outage causes industry-wide); a single deployment pushing a bug to all regions at once; a shared cell/AZ; a control-plane dependency in your data-plane recovery path (don't make recovery depend on the thing that's down). Mitigations: **cell-based / shuffle-sharding architecture** (partition customers into independent cells so a bad cell affects a bounded fraction — AWS's approach), **staggered/regional config and deploy rollouts** with bake time so a bad change can't hit everything simultaneously, and ensuring your **failover path has no dependency on the failed component** (static stability — the system keeps working using only pre-provisioned, already-loaded state, not needing to call a control plane mid-incident).

**Gray failure** requires *differential observability*: measure the system from the *user's* perspective (synthetic probes, real-user monitoring) and compare with the system's *self*-assessment; when they diverge ("we think we're healthy, users see errors"), that gap is the gray failure. Build health checks that reflect end-to-end success, not just process liveness.

**Organizationally:** rotate on-call sustainably (no hero culture — burnout is a reliability risk), invest in blameless postmortems and tracked action items, run regular DR game-days and chaos experiments so muscle memory exists, codify runbooks, and treat reliability as a first-class product feature with staffing and an error-budget policy that executives actually honor. The most resilient organizations make reliability everyone's job through shared SLOs, not a separate team's problem.

---

## ✅ Key Takeaways

- **Availability is multiplicative in series, additive-via-redundancy in parallel.** Five 99.9% services in a chain yield ~99.5%; design redundancy to claw nines back.
- **Lowering MTTR usually beats raising MTBF** — failures are inevitable, so optimize for fast detection, automated rollback, and self-healing.
- **Resilience patterns compose:** timeouts bound waits, circuit breakers stop hammering the dead, bulkheads isolate blast radius, backpressure/load shedding protect goodput, retries (with jitter + idempotency + budgets) recover safely.
- **Most large outages are amplification, not the original fault** — retry storms, thundering herds, and recovery mechanisms that add load are the real killers.
- **Deployment strategy is a risk/cost trade:** rolling (cheap), blue-green (instant rollback, 2x cost), canary (best risk control, needs observability).
- **DR is defined by RTO/RPO**, and an untested backup is not a backup — run restore drills and game-days.
- **Error budgets turn reliability into a managed resource**, aligning velocity and stability; 100% is the wrong target.
- **Mitigate before diagnose** in incidents; separate Incident Commander from hands-on-keyboard; the blameless postmortem is the real deliverable.
- **Correlated and gray failures cause the worst outages** — design cell-based isolation, staggered rollouts, static stability, and user-perspective observability.

## ⚠️ Common Pitfalls

- **No timeout (or only a read timeout, no connect timeout)** on network calls — the root of most cascading failures.
- **Retrying non-idempotent operations** or **nesting retries across layers**, multiplying load exponentially during an outage.
- **Liveness probes that check downstream dependencies** — a DB blip triggers a pod-restart storm. Keep liveness cheap; check dependencies only in readiness.
- **Circuit breaker with no fallback** — converts slow failure into fast failure without preserving any user value.
- **Oversized connection pools** — more DB connections often means *more* contention and *worse* throughput; size pools to the bottleneck.
- **Autoscaling on the wrong signal** — scaling on latency caused by a slow downstream just piles more load onto the bottleneck.
- **Treating backups as DR** without ever running a restore — silent failures (bad keys, schema drift, corruption) surface only when you can least afford it.
- **Active-active running at >50% per-region utilization** — the survivor can't absorb full load when a region fails.
- **Alerting on causes instead of symptoms**, producing alert fatigue and missing the failures users actually feel.
- **Skipping graceful shutdown** (no `preStop` drain, grace period shorter than longest request) — drops in-flight requests on every deploy.

## 📚 Further Reading

- *Site Reliability Engineering* and *The Site Reliability Workbook* — Google (free online at sre.google/books) — the canonical texts on SLOs, error budgets, and operations.
- *Release It!* (2nd ed.) — Michael Nygard — stability patterns (circuit breaker, bulkhead, timeouts) and antipatterns, with war stories.
- *Designing Data-Intensive Applications* — Martin Kleppmann — replication, consistency, partitioning, and fault tolerance fundamentals.
- *Chaos Engineering: System Resiliency in Practice* — Rosenthal & Jones (O'Reilly) — principles and case studies from Netflix and beyond.
- AWS Well-Architected Framework — Reliability Pillar, and the Builders' Library articles on timeouts, retries with jitter, static stability, and shuffle sharding.
- Resilience4j documentation (resilience4j.readme.io) — modern Java implementation of circuit breakers, bulkheads, rate limiters, and retry.
