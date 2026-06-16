# Observability & Monitoring Design

A staff-level interview guide to designing observability for distributed systems: metrics, logs, and traces at scale, OpenTelemetry pipelines, SLI/SLO/error-budget engineering, alerting that respects human attention, and the brutal economics of telemetry. Knowledge current through 2026.

[← Back to master index](../README.md)

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

### Q1. [Theory] What is the difference between monitoring and observability?

Monitoring is the act of collecting and alerting on a *predefined* set of signals — you know in advance what to watch (CPU, error rate, queue depth) and you build dashboards and alerts for those known failure modes. Observability is a *property of a system*: the degree to which you can understand its internal state from its external outputs, including questions you did not anticipate when you built it. The practical distinction is about "unknown unknowns": monitoring answers "is the thing I expected to break, broken?" while observability lets you ask "why is *this specific* customer's checkout slow at 3am?" without shipping new code. Monitoring is necessary but insufficient; in a microservices world the number of possible failure interactions explodes combinatorially, so you cannot pre-enumerate every dashboard. Observability is achieved by emitting high-cardinality, high-dimensional telemetry (wide structured events, traces) that you can slice arbitrarily after the fact. In short: monitoring is a subset of what good observability enables.

### Q2. [Theory] What are the "three pillars" of observability, and what is each good at?

The three pillars are **metrics**, **logs**, and **traces**.

```
            What it is              Best for                  Weakness
  Metrics   Numeric time series     Trends, alerting, SLOs    No per-request detail
            (counters, gauges,      Cheap, low-cardinality    Can't answer "why"
            histograms)
  Logs      Timestamped event       Forensics, debugging      Expensive at scale,
            records (ideally        rich context              hard to aggregate
            structured JSON)
  Traces    Causal chain of spans   Latency breakdown,        Sampling loses some
            across services         service dependencies      requests; setup cost
```

Metrics are pre-aggregated and cheap, so they power alerts and SLO dashboards. Logs give you the detailed narrative of a single event. Traces stitch a single request across many services so you can see *where* the 800ms went. Modern thinking (championed by Honeycomb and the OpenTelemetry community) is moving beyond rigid pillars toward **wide structured events** — a single rich event per unit of work that can be aggregated into metrics, queried like logs, and linked into traces. The pillars are a useful mental model, not a mandate to run three disconnected silos.

### Q3. [Theory] What are the four golden signals?

The four golden signals, from Google's SRE book, are the minimum set you should measure for any user-facing service:

- **Latency** — how long requests take (always split success vs error latency; a fast 500 can hide a slow 200).
- **Traffic** — demand on the system (requests/sec, transactions/sec).
- **Errors** — rate of failed requests (explicit 5xx, implicit wrong-content, policy failures like exceeding SLA latency).
- **Saturation** — how "full" the service is (CPU, memory, queue depth, connection-pool usage); the constrained resource that will fail first.

The "why" is prioritization: if you can only build four dashboards, these give you the most diagnostic power per unit of effort. Latency, traffic, and errors are *symptoms* users feel; saturation is a *leading indicator* that predicts future symptoms, which makes it the most useful for proactive scaling.

### Q4. [Practical] What's the difference between a counter, a gauge, and a histogram in metrics?

```
  Counter    Monotonically increasing value (resets on restart).
             e.g. http_requests_total. Query with rate() to get per-sec.
  Gauge      Value that goes up and down. e.g. queue_depth, memory_bytes,
             threads_active. Read the instantaneous value.
  Histogram  Samples observations into buckets + sum + count.
             e.g. request_duration_seconds. Lets you compute p50/p95/p99
             server-side via histogram_quantile().
```

The most common rookie mistake is using a gauge for something that should be a counter (you lose the ability to compute rates correctly across restarts) or storing latency as an *average gauge* (averages hide tail latency — a service with p50=10ms and p99=5s can show a perfectly healthy 50ms average). Use histograms for anything where the distribution matters (latency, payload size). Note: classic Prometheus histograms require you to pick bucket boundaries up front; **native/exponential histograms** (Prometheus 2.40+, OpenTelemetry) auto-scale buckets and dramatically reduce that guesswork.

### Q5. [Coding] Instrument a Java method with a Micrometer Timer and a Counter.

**Problem:** Add metrics to a payment-processing method so we can see throughput, error rate, and latency distribution.

```java
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Counter;

public class PaymentService {
    private final Timer processTimer;
    private final Counter failureCounter;

    public PaymentService(MeterRegistry registry) {
        this.processTimer = Timer.builder("payment.process.duration")
                .description("Time to process a payment")
                .publishPercentileHistogram()   // emit histogram buckets -> server-side p99
                .tag("service", "payments")
                .register(registry);
        this.failureCounter = Counter.builder("payment.process.failures")
                .description("Failed payment attempts")
                .register(registry);
    }

    public PaymentResult process(PaymentRequest req) {
        // Timer.record wraps the call and records duration even on exception
        return processTimer.record(() -> {
            try {
                return doProcess(req);
            } catch (PaymentException e) {
                // increment a SEPARATE counter for errors so we can compute error ratio
                failureCounter.increment();
                throw e;
            }
        });
    }

    private PaymentResult doProcess(PaymentRequest req) { /* ... */ return new PaymentResult(); }
}
```

**Key points:** `publishPercentileHistogram()` ships buckets to the backend so percentiles are computed across all instances (computing p99 per-pod then averaging is mathematically wrong). Keep tag values *bounded* — never tag with `userId` or `paymentId` (see cardinality, Q11). **Time complexity** of each record is O(1); **space** is O(number of histogram buckets), a small constant per timer.

**Edge cases:** record latency even on failure (the `record` lambda handles this — exceptions still capture timing); don't double-count retries as separate requests unless that's your intent; ensure the registry is a singleton so series aren't duplicated.

### Q6. [Theory] What is a trace, a span, and trace context propagation?

A **trace** represents one request's journey through a distributed system; it is a tree (technically a DAG) of **spans**. Each span is a single named, timed operation (an HTTP handler, a DB query, a Kafka publish) with a start time, duration, attributes, and a parent span ID. The root span is the entry point; children represent downstream work.

**Context propagation** is how the trace stays connected across process and network boundaries: service A injects the trace ID and span ID into outgoing request headers, and service B extracts them to continue the same trace. The industry standard is the **W3C Trace Context** header `traceparent` (format `00-{trace-id}-{parent-id}-{flags}`), with `tracestate` for vendor data. Before W3C standardization, formats like B3 (Zipkin) and Jaeger headers were common; you still encounter them in legacy systems, so OpenTelemetry supports multiple propagators. Without propagation, you get disconnected single-service spans and lose the entire value of tracing.

---

## 🟡 Intermediate (3–7 yrs)

### Q7. [Theory] Explain SLI, SLO, SLA, and error budgets. How do they relate?

```
  SLI  Service Level Indicator   A measurement.  "% of requests < 300ms"
  SLO  Service Level Objective   An internal target. "99.9% over 28 days"
  SLA  Service Level Agreement   A contract w/ customers + penalties.
                                  Usually LOOSER than the SLO.
  Error Budget = 100% - SLO      The allowable failure. 99.9% -> 0.1% budget
                                  = ~43 min/month of badness you may "spend".
```

An **SLI** is a quantitative measure of service health — ideally a *ratio of good events to total events* (e.g., good = served in under 300ms with a 2xx, total = all requests). An **SLO** is the target for that SLI over a window (usually 28–30 rolling days). An **SLA** is the externally promised, legally/financially binding version — you set the SLA looser than the SLO so you have internal headroom before you owe refunds. The **error budget** is the inverse of the SLO: if you target 99.9%, you have 0.1% of failures to "spend." The power of this framing is cultural: it turns "should we ship this risky feature?" from an argument into arithmetic. If the budget is healthy, ship fast; if it's exhausted, freeze risky changes and pour effort into reliability. It also stops the impossible chase for 100% (which is infinitely expensive and which users can't even perceive past their own network's reliability).

### Q8. [Practical] How do you choose a good SLI? Give a concrete example.

Good SLIs measure what the *user actually experiences*, expressed as a good-events / valid-events ratio, measured as close to the user as feasible. Bad SLIs measure things you can easily collect but that don't correlate with happiness (e.g., CPU utilization is a saturation signal, not an SLI).

**Scenario:** an image-upload API. Approach:

1. **Identify the critical user journey:** "user uploads a photo and it appears in their gallery."
2. **Pick SLI types:** *Availability* = `(successful uploads) / (valid upload attempts)`; *Latency* = `(uploads completing < 2s) / (all uploads)`. Use a threshold-based latency SLI, not an average.
3. **Define "valid":** exclude 4xx caused by the client (a malformed request isn't *our* failure) but include 429s if we caused throttling.
4. **Choose measurement point:** ideally the load balancer / API gateway, or RUM (real user monitoring) in the client, because server-side metrics miss network and edge failures.

**Trade-offs / production reality:** measuring at the LB is cheap and reliable but blind to client-side and CDN issues; RUM captures true experience but is noisy and harder to attribute. In practice you run an LB-based SLO for engineering control and a RUM-based one for product truth, and reconcile the gap. Set the *target* from historical data plus business need — don't pull 99.99% out of the air; each extra nine roughly 10x's the cost.

### Q9. [Theory] What are RED and USE methods, and when do you use each?

**RED** (Tom Wilkie) is for **request-driven services**: **R**ate (requests/sec), **E**rrors (failed requests/sec), **D**uration (latency distribution). It's how you instrument every microservice consistently — three dashboards per service, every service the same shape.

**USE** (Brendan Gregg) is for **resources**: **U**tilization (% busy), **S**aturation (queued work waiting), **E**rrors (error events). You apply USE to CPUs, disks, network interfaces, memory, connection pools.

```
  RED  -> services / endpoints  (the "request" view, golden-signals-aligned)
  USE  -> resources / hardware  (the "is this component the bottleneck" view)
```

They're complementary: RED tells you a service is slow (symptom); USE on its underlying resources tells you *why* (the disk is saturated). The golden signals are essentially RED + saturation, so think of RED as the application-facing slice and USE as the infrastructure-facing slice. A mature setup auto-generates RED dashboards from a service-mesh or OTel instrumentation and USE dashboards from node exporters.

### Q10. [Practical] Walk through designing an OpenTelemetry pipeline for a 50-service Java platform.

**Goal:** vendor-neutral collection of metrics, logs, and traces with correlation, sampling, and cost control.

```
  +-----------------+      OTLP       +------------------+
  |  Java services  | --------------> | OTel Collector   |  (agent: DaemonSet
  |  (OTel SDK +    |  gRPC/HTTP      | "agent" tier     |   per node)
  |   auto-instr.)  |                 +--------+---------+
  +-----------------+                          | OTLP
                                               v
                                      +------------------+
                                      | OTel Collector   |  (gateway: central
                                      | "gateway" tier   |   deployment, does
                                      | - tail sampling  |   tail sampling,
                                      | - batching       |   redaction, routing)
                                      | - PII redaction  |
                                      +---+----+-----+---+
                                          |    |     |
                          metrics --------+    |     +------- logs
                                          v    v             v
                                   Prometheus  Tempo/Jaeger   Loki/Elastic
                                   (or Mimir)  (traces)       (logs)
                                          \    |             /
                                           v   v            v
                                        Grafana (unified dashboards + correlation)
```

**Decisions and trade-offs:**

- **Auto-instrumentation first:** the OTel Java agent (`-javaagent:opentelemetry-javaagent.jar`) instruments Spring, JDBC, Kafka, gRPC with zero code change. Add *manual* spans only for business-critical logic the agent can't see. This gets you 80% coverage in a week.
- **Two-tier collector:** node-local **agents** keep network hops cheap and add host metadata; a central **gateway** is where you do *tail-based sampling* (you must see all spans of a trace to decide, which only works at an aggregation point), PII redaction, and fan-out to backends.
- **OTLP everywhere** decouples you from any single vendor — you can swap Jaeger for a SaaS without touching app code.
- **Correlation:** inject `trace_id` into structured logs (via MDC) and exemplars into metrics, so one click in Grafana jumps metric → trace → log.

**What I'd actually do in production:** start with auto-instrumentation + head sampling at 10%, get the gateway running, *then* migrate to tail sampling once volume/cost justify it. Resist building this all at once.

### Q11. [Theory] What is cardinality, and why is it the silent killer of metrics systems?

Cardinality is the number of *unique time series* produced, which equals the product of all label/tag value combinations on a metric. A metric `http_requests_total{method, status, endpoint}` with 5 methods × 10 statuses × 20 endpoints = 1,000 series — fine. But add `{userId}` with a million users and you get a million series *per other-combination* — a "cardinality explosion." Time-series databases store each unique series separately (its own index entry, its own memory-resident head block in Prometheus), so cardinality drives memory, storage, and query cost roughly *linearly to super-linearly*. This is the number-one cause of OOM-killed Prometheus servers and surprise SaaS bills (most vendors bill on active series or DPM). The rule: **labels must be bounded and low-cardinality.** Never put user IDs, request IDs, full URLs (with IDs), emails, or timestamps in metric labels. Those high-cardinality dimensions belong in **traces and structured logs**, where the storage model handles them, not in metrics. This is exactly why "wide events" platforms exist — to give you arbitrary high-cardinality slicing without melting a TSDB.

### Q12. [Coding] Propagate trace context and inject trace ID into logs in Spring Boot 3.

**Problem:** A request crosses two Spring Boot services; we need the trace to stay connected and every log line to carry the trace ID so we can correlate logs with traces.

Spring Boot 3 uses **Micrometer Tracing** (replacing Spring Cloud Sleuth from Boot 2). Dependencies bridge to OTel and propagate W3C context automatically.

```java
// build.gradle (conceptual)
//   implementation 'org.springframework.boot:spring-boot-starter-actuator'
//   implementation 'io.micrometer:micrometer-tracing-bridge-otel'
//   implementation 'io.opentelemetry:opentelemetry-exporter-otlp'

@RestController
class OrderController {
    private static final Logger log = LoggerFactory.getLogger(OrderController.class);
    private final WebClient webClient; // auto-instrumented: injects traceparent

    OrderController(WebClient.Builder builder) {
        this.webClient = builder.baseUrl("http://inventory-svc").build();
    }

    @GetMapping("/orders/{id}")
    public Mono<Order> getOrder(@PathVariable String id) {
        // trace_id is already in the MDC, so this log line is correlated automatically
        log.info("Fetching order {}", id);
        return webClient.get().uri("/stock/{id}", id)   // traceparent header auto-added
                        .retrieve()
                        .bodyToMono(Stock.class)
                        .map(stock -> new Order(id, stock));
    }
}
```

```
# application.yml — put trace/span IDs in every log line
logging:
  pattern:
    level: "%5p [${spring.application.name:},%X{traceId:-},%X{spanId:-}]"
management:
  tracing:
    sampling:
      probability: 0.1          # head sampling: 10% of traces
  otlp:
    tracing:
      endpoint: http://otel-collector:4318/v1/traces
```

**Why this works:** Micrometer Tracing populates `traceId`/`spanId` into SLF4J's **MDC** (`%X{traceId}`), and the instrumented `WebClient` injects the `traceparent` header outbound. The downstream service's filter extracts it and continues the trace. **Edge case:** in reactive/`WebFlux` or `@Async` code, context can be lost across thread hops — Micrometer's context-propagation library and `ContextSnapshot` handle this, but manual `ExecutorService` usage needs `ContextExecutorService` wrapping or the trace ID silently disappears from logs.

### Q13. [Practical] How do you do distributed-tracing sampling without losing the traces that matter? Head vs tail.

**Head sampling** decides at the *root* span whether to keep a trace, before you know anything about it (e.g., "keep 10% randomly"). It's cheap, stateless, and the decision propagates via the trace flags so the whole trace is consistently kept or dropped. The fatal weakness: it's random, so you drop 90% of your errors and slow requests too — exactly the ones you needed.

**Tail sampling** buffers all spans of a trace at the collector, waits until the trace completes, then decides based on the *whole* trace: keep it if it errored, if latency > p99, or if it touched a high-value endpoint; sample the boring fast-200s at 1%.

```
  HEAD (in the app)                 TAIL (in the gateway collector)
  decide at root, random            decide at end, content-aware
  cheap, scales trivially           must buffer trace in memory (cost, latency)
  drops errors/slow too             keeps 100% of errors + slow, 1% of normal
  no extra infra                    requires routing all spans of a trace to
                                    the SAME collector instance (load-balancing
                                    by trace_id) -> stateful
```

**Production approach:** combine them. Use a modest head sample to cap absolute volume from chatty paths, but rely on tail sampling in the OTel Collector with policies like `status_code == ERROR`, `latency > 1s`, plus a small `probabilistic` baseline so you retain a representative sample of healthy traffic for baselining. The catch: tail sampling needs all spans of a trace on one collector, so you front the gateway tier with a **trace-ID-aware load balancer** (the `loadbalancing` exporter). It also adds memory cost and a few seconds of decision latency. For most teams, tail sampling pays for itself by cutting trace storage 90%+ while keeping every interesting trace.

### Q14. [Practical] Design an alerting strategy that avoids alert fatigue. Symptom vs cause alerting.

Alert fatigue is when on-call gets so many low-value pages that they start ignoring or auto-acking them — and then miss the real incident. The defenses:

1. **Alert on symptoms, not causes.** Page on "checkout error rate > 1% for 5 min" (a user-facing symptom) — *not* on "CPU > 80%" (a cause that may be totally benign). High CPU during a batch job is fine; you don't want to wake someone for it. Symptom alerts also catch failure modes you never predicted. Causes go into *dashboards and tickets*, not pages.
2. **Tie pages to SLOs / error budgets.** Page only when you are *burning* budget fast enough to threaten the SLO. This is the most effective single fix.
3. **Use multi-window, multi-burn-rate alerts** (Q22) to balance fast detection of severe incidents against not paging for slow, recoverable degradation.
4. **Three severity tiers:** *page* (human must act now), *ticket* (look within a day), *log/dashboard* (informational). Most things people page on should be tickets.
5. **Every alert must be actionable and have a runbook.** If the responder's only action is "ack and wait," delete the alert.
6. **Require a clear-condition and dedup/grouping** so one outage = one page, not 400 pages from 400 pods.

```
  Symptom (PAGE):  "p99 latency > SLO threshold, burning budget"  -> user pain
  Cause   (TICKET): "GC pause time rising"                        -> investigate
  Cause   (DASH):   "CPU 85%"                                     -> just watch
```

**What I'd do:** audit existing alerts, delete every one that hasn't led to action in 90 days, convert cause-alerts to dashboards, and rebuild paging around 2–3 SLO burn-rate alerts per service. Teams routinely cut page volume 70–90% this way.

### Q15. [Theory] Why are p99/p99.9 latencies more important than averages, and what's the "tail at scale" problem?

Averages are dominated by the bulk of fast requests and *mathematically erase* the slow tail; a service can show a 40ms average while 1% of users wait 3 seconds. Since you have many users and each makes many requests, that "rare" tail is hit constantly in aggregate — and it disproportionately affects your heaviest, most valuable users. Hence percentiles: p99 means "99% of requests are at least this fast," directly expressing what your worst-served 1% experience.

The **tail-at-scale** problem (Dean & Barroso, "The Tail at Scale," 2013) is that when a single user request fans out to many backend services (a search query hitting 100 shards), the *overall* latency is governed by the **slowest** component, not the average. If each backend has a 1% chance of being slow, a fan-out to 100 of them means the request has a `1 - 0.99^100 ≈ 63%` chance of hitting at least one slow node. So tail latencies that look negligible per-service compound into routine slowness at the request level. Mitigations: hedged requests, tied requests, and request reissue. The observability implication: you *must* measure and alert on high percentiles, and you must compute them from histograms aggregated server-side — you cannot average per-instance percentiles.

### Q16. [Practical] How do you control logging cost at scale? Sampling and tiering strategies.

Logs are usually the largest and fastest-growing line item in an observability bill because volume scales with traffic and verbosity. Strategy:

1. **Structure everything as JSON** (one event = one object). Unstructured text logs force expensive parsing and full-text indexing; structured logs let you index a few fields and store the rest cheaply.
2. **Index selectively.** Backends like Loki index only labels and keep the body compressed; this slashes cost vs. Elasticsearch indexing every token. Choose what's queryable up front.
3. **Sample high-volume, low-value logs.** Keep 100% of `ERROR`/`WARN`, sample `INFO`/`DEBUG`. Better: *consistent tail-based / trace-aware sampling* — if a trace is sampled-in (because it errored), keep all its logs; if it's a boring success, drop its debug logs. This keeps logs and traces aligned.
4. **Tier storage.** Hot (last 3–7 days, fast query) → warm → cold/archival in object storage (S3) with cheap-but-slow query. Most queries hit the last 24h.
5. **Drop at the edge.** Filter health-check spam, redundant framework logs, and known-noise *in the OTel Collector* before they ever hit storage — this is the highest-leverage cost lever.
6. **Set retention per data class** (security/audit logs may legally need 1 year; debug logs need 3 days).

```
  Volume   Value     Action
  HIGH     LOW       drop or 1% sample at collector (health checks, debug)
  HIGH     HIGH      keep but tier hot->cold (request logs)
  LOW      HIGH      keep 100%, longer retention (errors, audit, security)
```

**Reality:** a single well-placed collector filter dropping Kubernetes liveness-probe logs can cut log volume 30–50% with zero diagnostic loss.

### Q17. [Coding] Implement consistent (deterministic) trace-based sampling.

**Problem:** We want exactly X% of *traces* sampled, and the decision must be **consistent** across all services — every service in a given trace must make the same keep/drop decision without coordinating, so we never get a half-sampled trace. Random per-service decisions break trace completeness.

**Approach: hash the trace ID and compare to a threshold.** Because all services share the same trace ID, hashing it deterministically yields the same decision everywhere.

```java
import java.nio.charset.StandardCharsets;

public final class ConsistentTraceSampler {
    private final long threshold;   // keep if hash < threshold

    /** @param sampleRate fraction in [0.0, 1.0], e.g. 0.1 for 10% */
    public ConsistentTraceSampler(double sampleRate) {
        if (sampleRate < 0.0 || sampleRate > 1.0)
            throw new IllegalArgumentException("rate must be in [0,1]");
        // map fraction onto the full unsigned 64-bit space
        this.threshold = (long) (sampleRate * Math.pow(2, 64)) + Long.MIN_VALUE;
    }

    public boolean shouldSample(String traceId) {
        if (traceId == null || traceId.isEmpty()) return false;
        long h = fnv1a64(traceId);
        return h < threshold;   // deterministic: same traceId -> same answer everywhere
    }

    // FNV-1a: fast, well-distributed, dependency-free
    private static long fnv1a64(String s) {
        long hash = 0xcbf29ce484222325L;          // FNV offset basis
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            hash ^= (b & 0xff);
            hash *= 0x100000001b3L;               // FNV prime
        }
        return hash;
    }
}
```

**Brute-force alternative (wrong at scale):** `Math.random() < rate` per service — simple but each service decides independently, so a trace is kept by service A and dropped by B, leaving broken partial traces. Never do this for distributed traces.

**Complexity:** `shouldSample` is **O(L)** time where L = trace-ID length (a fixed 32 hex chars in practice, so effectively O(1)), **O(1)** space. **Edge cases:** rate 0.0 → keep nothing, 1.0 → keep all (guard the threshold math against overflow at the extremes); null/empty trace ID → fail closed (don't sample); use the *same hash and rate config* across all services or consistency breaks. This is essentially how OTel's `TraceIdRatioBased` sampler works.

### Q18. [Theory] How do you correlate metrics, logs, and traces? What are exemplars?

Correlation is what turns three data silos into actual observability — the ability to pivot from "a metric spiked" to "here's the exact trace and logs of a failing request." Mechanisms:

- **Shared trace ID as the join key.** Inject `trace_id` (and `span_id`) into every structured log line (via MDC) and attach it to spans. Then a log query and a trace query share a key, and your UI can link them.
- **Exemplars** connect metrics to traces. An exemplar is a sample trace ID attached to a specific metric observation — e.g., a histogram bucket records "one of the requests in this 'slow' bucket had trace_id abc123." So when you see a latency spike on a Prometheus graph, you click the exemplar dot and jump straight to an example slow trace. OpenMetrics and Prometheus native histograms support exemplars; Micrometer emits them when tracing is enabled.
- **Consistent resource attributes.** Every signal should carry the same `service.name`, `service.version`, `deployment.environment`, `k8s.pod.name` (OTel semantic conventions) so you can filter all three by the same dimensions.

```
  Grafana flow:  Metric spike --[exemplar trace_id]--> Trace
                       Trace --[trace_id in logs]--> correlated Logs
                                  (one ID stitches all three)
```

The design rule: pick `trace_id` as your universal correlation key early and enforce it everywhere via shared instrumentation, or you'll spend incidents copy-pasting timestamps between tools.

---

## 🟠 Advanced (8–12 yrs)

### Q19. [Practical] Design observability for a 500-microservice platform handling 1M req/s. What are the architectural pressure points?

```
  Edge/RUM ---> API GW ---> [ 500 services w/ OTel SDK + auto-instr ]
                                  | OTLP (metrics/logs/traces)
                                  v
                       OTel Collector AGENTS (DaemonSet, per node)
                       - host enrichment, batching, head sample floor
                                  | OTLP, load-balanced by trace_id
                                  v
                       OTel Collector GATEWAY (autoscaled fleet)
                       - tail sampling, PII redaction, cardinality guard
                       /          |             \
            Mimir/Cortex      Tempo (traces,    Loki (logs, object-store
            (metrics, S3       object-store      backed, label-indexed)
            backed, sharded)   backed)
                       \          |             /
                              Grafana + alerting (Alertmanager)
                              SLO engine (Sloth/Pyrra/OpenSLO)
```

**Pressure points and how I'd handle them:**

- **Metrics cardinality** is the first thing to blow up. Enforce a label-allowlist in the collector, run cardinality-limit processors, and use **recording rules** to pre-aggregate hot queries. Move to a horizontally-sharded TSDB (Mimir/Cortex/Thanos) with object storage so it scales past one node's RAM.
- **Trace volume:** 1M req/s × even 5 spans = 5M spans/s — you cannot store all of it. Tail sampling at the gateway, keeping ~100% errors/slow and ~0.1–1% baseline.
- **Collector becomes a distributed system itself.** It needs autoscaling, back-pressure handling, and a persistent queue (or you drop telemetry during spikes — and telemetry spikes *correlate with incidents*, the worst time to be blind).
- **The observability system must be more reliable than what it watches**, and **independently failable** — run it in a separate failure domain/account, or an outage takes out both prod and your ability to see prod.
- **Cost governance:** chargeback per team, dashboards on telemetry volume by service, and a "you produce it, you pay for it" model to align incentives.

### Q20. [Theory] What is "observability-driven development," and how does high-cardinality wide-event tooling change debugging?

Observability-driven development (ODD) treats instrumentation as a first-class deliverable, not an afterthought: you ask "how will I know if this works in prod?" *during* design, instrument the new code path before/with shipping it, and watch it in production behind feature flags as part of the definition of done. It complements TDD — tests prove correctness in known conditions; observability proves behavior in the messy unknown of production.

The deeper shift is from **dashboards (pre-aggregated, you must guess the question in advance)** to **wide structured events with arbitrary high-cardinality querying** (Honeycomb-style). Instead of pre-deciding which labels matter, you emit one rich event per unit of work with dozens of dimensions (user tier, region, feature-flag state, build SHA, db-shard, cache-hit). Then in an incident you *explore*: "group p99 latency by build SHA AND customer tier" — a query you never anticipated. This directly attacks "unknown unknowns," which is exactly where pre-built dashboards fail. The cost model differs too: this needs a columnar/event store, not a TSDB, because the value *is* the high cardinality that would destroy a metrics database. The trade-off is higher per-event cost and a learning curve away from the comfort of static dashboards.

### Q21. [Coding] Build a sliding-window SLO error-budget tracker.

**Problem:** Track an SLO over a rolling time window: given a stream of (timestamp, good/bad) events, report the current success ratio and remaining error budget efficiently, evicting events older than the window.

**Approach:** a bucketed sliding window. Don't store every event (memory blows up at high traffic); aggregate into fixed time buckets (e.g., 1-minute granularity) and sum across buckets in the window. This is O(1) amortized per event and O(buckets) to query.

```java
import java.util.concurrent.atomic.LongAdder;

public class SloBudgetTracker {
    private final long bucketMillis;
    private final int numBuckets;          // window = bucketMillis * numBuckets
    private final LongAdder[] good;
    private final LongAdder[] bad;
    private final long[] bucketStart;      // start-time owning each slot
    private final double objective;        // e.g. 0.999

    public SloBudgetTracker(long windowMillis, int numBuckets, double objective) {
        this.numBuckets = numBuckets;
        this.bucketMillis = windowMillis / numBuckets;
        this.objective = objective;
        this.good = new LongAdder[numBuckets];
        this.bad = new LongAdder[numBuckets];
        this.bucketStart = new long[numBuckets];
        for (int i = 0; i < numBuckets; i++) { good[i] = new LongAdder(); bad[i] = new LongAdder(); }
    }

    public void record(long nowMillis, boolean isGood) {
        int idx = (int) ((nowMillis / bucketMillis) % numBuckets);
        long thisBucketStart = (nowMillis / bucketMillis) * bucketMillis;
        // if the slot belongs to an older window cycle, reset it (lazy eviction)
        synchronized (good[idx]) {
            if (bucketStart[idx] != thisBucketStart) {
                good[idx].reset(); bad[idx].reset();
                bucketStart[idx] = thisBucketStart;
            }
        }
        (isGood ? good[idx] : bad[idx]).increment();
    }

    /** Fraction of error budget remaining: 1.0 = full, 0.0 = exhausted, <0 = breached. */
    public double budgetRemaining(long nowMillis) {
        long g = 0, b = 0;
        long windowStart = nowMillis - (bucketMillis * numBuckets);
        for (int i = 0; i < numBuckets; i++) {
            if (bucketStart[i] >= windowStart) { g += good[i].sum(); b += bad[i].sum(); }
        }
        long total = g + b;
        if (total == 0) return 1.0;                  // no traffic -> full budget
        double allowedBad = total * (1.0 - objective);
        if (allowedBad == 0) return b == 0 ? 1.0 : -1.0;
        return (allowedBad - b) / allowedBad;        // >0 ok, <0 breached
    }
}
```

**Complexity:** `record` is **O(1)** amortized; `budgetRemaining` is **O(numBuckets)** (a small constant like 1440 for a 1-day window at 1-min granularity). **Space** is **O(numBuckets)** regardless of traffic — the key win. **Edge cases:** zero traffic returns full budget (don't divide by zero); concurrency handled via `LongAdder` (low-contention) plus a tiny synchronized reset; an objective of 100% (`allowedBad == 0`) is degenerate — flag any failure as a breach; clock skew across instances means you should aggregate server-side, not trust per-host clocks blindly.

### Q22. [Theory] Explain multi-window, multi-burn-rate alerting. Why is single-threshold SLO alerting flawed?

**Burn rate** is how fast you're consuming error budget relative to "even" consumption. A burn rate of 1 means you'll exactly exhaust the 30-day budget in 30 days; a burn rate of 14.4 means you'll exhaust it in ~2 days.

A naive SLO alert ("page if 30-day success < 99.9%") is flawed two ways: it's *too slow* (a total outage takes hours to move a 30-day average enough to fire) and it *never resets* (once you dip below, it pages forever even after you've recovered). Conversely, a short-window alert ("error rate > 0.1% over 5 min") is *too twitchy* — it pages on every transient blip.

**Multi-window, multi-burn-rate** (Google SRE Workbook) fixes both by combining:

```
  Severity   Burn rate   Long window   Short window   Budget burned   Action
  Critical   14.4x       1 hour      + 5 min          ~2% in 1h       PAGE now
  High        6x         6 hours     + 30 min         ~5% in 6h       PAGE
  Low         1x         3 days      + 6 hours        ~10% in 3d      TICKET
```

The **long window** decides *significance* (are we really burning budget?), and the **short window** ensures the problem is *still happening right now* (so the alert auto-resolves on recovery and you don't page on already-over blips). Requiring *both* windows to exceed the burn rate gives fast detection of severe incidents, slow/quiet handling of minor ones, and automatic reset — the core of fatigue-resistant SLO alerting.

### Q23. [Practical] How do you handle telemetry for serverless/Lambda and ephemeral workloads, where the classic agent model breaks?

The pull-based, long-lived-agent model assumes a stable host you can scrape and a process that lives long enough to flush buffers — both false for serverless.

**Problems:** functions are frozen between invocations (a background export thread won't run), cold starts make in-process collector startup costly, there's no node to run a DaemonSet, and Prometheus can't *pull* from something that vanished after 200ms.

**Approaches:**

- **Push, not pull.** Use OTLP export to a collector or an OTel **Lambda layer/extension** that runs as a sidecar in the execution environment and flushes during the invocation lifecycle, including a final flush before freeze.
- **Flush synchronously at invocation end** (or use the extension's `INVOKE`/`SHUTDOWN` hooks) so you don't lose the last data when the runtime freezes.
- **Use a gateway collector** as the stable aggregation/sampling point; functions just fire-and-forget OTLP to it.
- **Treat the platform metrics (CloudWatch) as a baseline** but enrich with OTel for cross-service traces — cold-start duration, init vs. handler time, and concurrency throttles are serverless-specific SLIs to track.
- **Cardinality caution:** auto-scaling can spin thousands of short-lived instances; do *not* put an instance ID in metric labels or you'll explode cardinality with every scale event.

**What I'd do:** adopt the OTel Lambda extension for tracing + a gateway collector for sampling/routing, keep platform metrics for infra-level SLIs, and accept slightly higher per-invocation overhead as the price of cross-service visibility.

### Q24. [Practical] A latency spike hits production. Walk through using observability to find root cause.

A structured workflow that uses all three signals top-down (symptom → narrow → confirm):

1. **Confirm the symptom & blast radius (metrics/SLO).** Check the SLO dashboard and golden signals: is p99 up across all endpoints or one? All regions or one? When did it start, and what changed at that time (deploys, config, traffic)? *Correlate the spike start with the deploy timeline first — most incidents are self-inflicted by a change.*
2. **Localize the service (traces / RED + service map).** Use the service dependency map and per-service RED to find *which* hop the latency lives in. Tail-sampled traces guarantee you have examples of the slow requests.
3. **Break down the slow trace (traces).** Open exemplar-linked slow traces: is the time in a DB span? A downstream call? A queue wait? Lock contention shows as a gap between spans.
4. **Confirm the resource cause (USE / logs).** Once localized to service X, check USE metrics on its resources (connection-pool saturation, GC pauses, disk I/O) and pivot to that service's logs *filtered by the trace IDs of slow requests*.
5. **Validate the hypothesis & mitigate.** e.g., "DB connection pool saturated because a deploy doubled query count per request." Mitigate first (rollback/scale/feature-flag off), root-cause fully after.

```
  SLO breach  ->  which service?  ->  which span?  ->  which resource?  ->  why?
  (metrics)       (service map +     (trace          (USE metrics +       (logs +
                   tail traces)       waterfall)       trace-id logs)       diff)
```

**Real example:** a classic pattern — p99 climbs after a deploy, traces show DB spans ballooning, USE shows the connection pool at 100% saturation with requests queueing, logs show an N+1 query introduced by the new code. Mitigation: rollback; fix: batch the query. Total time-to-localize with good observability: minutes, vs. hours of guessing without traces.

### Q25. [Theory] What are the security and privacy implications of observability, and how do you design for them?

Telemetry is a prime exfiltration and compliance risk because it tends to capture *everything*: logs accidentally include passwords, tokens, full request bodies, PII (emails, SSNs), and PANs; trace attributes capture SQL with literal values; metrics labels can leak tenant identifiers. Design defenses:

- **Redact/scrub at the collector** (and ideally at the SDK) before storage — never rely on remembering to scrub at the call site. The OTel Collector's transform/redaction processors strip configured fields, and you should pattern-match for secrets/PII as a backstop.
- **Never log secrets;** enforce with linters/log-scanners in CI and runtime detectors. Tokens and credentials in logs are a top breach vector.
- **PCI/PII compliance:** PCI-DSS forbids storing full PANs even in logs; GDPR/CCPA require you to delete user data on request — including from logs/traces — so you need retention limits and the ability to purge by user/tenant, plus data-residency-aware routing (EU data stays in EU backends).
- **Access control & audit:** observability tools see production data, so RBAC, SSO, and audit logging of *who queried what* are mandatory; an over-permissive Grafana is a data-leak path.
- **Tamper-resistant audit/security logs:** keep security-relevant logs in append-only, longer-retention, separately-access-controlled storage so an attacker can't cover tracks.
- **Transport security:** TLS/mTLS on all OTLP, authenticated collectors — telemetry pipelines are an attack surface and an injection vector if unauthenticated.

The governing principle: treat telemetry data with the same classification as the production data it can contain, because it *is* that data.

---

## 🔴 Expert (15+ yrs)

### Q26. [Theory] How do you justify and govern the cost of observability when it rivals infrastructure cost?

At scale, observability commonly runs 10–30% of total infrastructure spend and occasionally *exceeds* the cost of the system it observes — the classic "the bill for watching the database is bigger than the database." The expert framing is **observability as a portfolio of investments with measured ROI**, not a sunk cost:

- **Tie spend to value:** the metric that matters is MTTD/MTTR reduction and incidents prevented, not gigabytes ingested. A 90% trace-sampling cut that doesn't raise MTTR is pure profit.
- **Govern the three cost drivers:** metrics **cardinality** (active series), logs **volume × indexing**, traces **span volume × retention**. Attack each with allowlists, sampling, tiering, and retention policy.
- **Chargeback/showback** per team so the people generating telemetry feel the cost — this single incentive change drives more savings than any technical lever because it stops the "log everything just in case" reflex.
- **Watch the SaaS pricing-model trap:** vendors bill on dimensions (DPM, active series, GB, host, user) that can grow super-linearly with cardinality; a high-cardinality label can 100x a bill overnight. Model the *unit economics* (cost per service, per million requests) and alert on telemetry cost like any other metric.
- **Build/buy calculus:** self-hosting the LGTM/OTel stack on object storage trades a large eng-ops burden for predictable cost; SaaS trades money for not running a distributed TSDB at 3am. The decision flips with scale — SaaS is cheaper until you're big enough that engineers running it is cheaper than the bill.

The trap to avoid: cost-cutting that creates blind spots during incidents (sampling away the data you need exactly when you need it). Govern with policy and tiering, not blanket reduction.

### Q27. [Theory] Critique the "three pillars" model. Where is the industry heading by 2026?

The three-pillars model is increasingly seen as an *implementation artifact masquerading as architecture* — it describes three storage backends, not three kinds of insight. Its weaknesses: (1) **silos** — three disconnected tools mean engineers manually correlate by copy-pasting timestamps during incidents, the worst possible time; (2) **triple cost** — the same fact (a slow request) is paid for three times across three stores; (3) **it doesn't match how you debug** — you don't think "let me consult the logs pillar," you ask a question and want whatever signal answers it.

Where things are heading:
- **Wide structured events as the substrate** (Honeycomb's thesis, increasingly mainstream): emit one canonical high-cardinality event per unit of work; derive metrics, logs-like queries, and traces from it. Pillars become *views*, not silos.
- **OpenTelemetry as the universal, vendor-neutral data plane** — by 2026 it's the de-facto standard, with mature logs (the last pillar to stabilize), profiling as a *fourth signal*, and rich semantic conventions enabling cross-vendor correlation.
- **Continuous profiling** (eBPF-based, Parca/Pyroscope/Grafana) added as a signal to answer "*which line of code* burned the CPU," closing the gap traces leave.
- **eBPF auto-instrumentation** giving zero-code visibility at the kernel level.
- **AI/LLM-assisted analysis** for anomaly detection and incident summarization — useful but not yet trustworthy enough to remove the human, and it *needs* the high-cardinality data to be good.

The honest staff-level take: keep the pillars vocabulary for communication, but architect around a unified, correlated, OTel-based event model and treat the pillars as queryable projections.

### Q28. [Behavioral] You inherit a team drowning in alerts with a 6-month-burned-out on-call rotation. How do you turn it around?

This is a socio-technical problem; a purely technical fix fails. My approach:

1. **Measure the pain first.** Pull alert volume, page-per-shift, pages-outside-hours, and *actionability* (how many led to action). Burnout claims need data to get leadership buy-in for the work.
2. **Declare an alert bankruptcy / freeze.** Stop adding alerts; aggressively delete any that haven't driven action in 90 days. It's psychologically critical to show fast relief.
3. **Reclassify ruthlessly** into page / ticket / dashboard (Q14). Most "alerts" become tickets. Convert cause-alerts to symptom/SLO burn-rate alerts.
4. **Mandate runbooks + actionability.** No runbook → not a page. This forces the team to articulate "what do I actually do," which surfaces alerts that are genuinely useless.
5. **Fix the humane process:** sustainable rotation size (≥6 people so it's ~1 week in 6+), compensated on-call, a hard rule that *every* page generates a follow-up to prevent recurrence, and an error-budget policy that lets the team *stop feature work* to fix reliability when budget is blown — that's the lever that prevents the underlying churn.
6. **Blameless postmortems** so people surface problems instead of hiding them, and track MTTR/page-volume as team health metrics reviewed with leadership.

The behavioral core: I'd protect the team publicly, make the cost of the status quo *visible to leadership*, and frame reliability work as the way to *earn back* engineering velocity — not as overhead. Within a quarter the goal is 70%+ page reduction and a rotation people don't dread.

### Q29. [Practical] Design a multi-tenant observability platform for an org with 200 teams. What governance and isolation do you need?

```
              Teams (200) --OTLP--> Tenant-aware Gateway Collectors
                                    | (inject tenant_id, enforce quotas,
                                    |  redact, sample per-tenant policy)
                                    v
              +------------------------------------------------+
              | Multi-tenant backends (tenant_id partitioned)  |
              |  Mimir (per-tenant limits) | Tempo | Loki      |
              +------------------------------------------------+
                                    |
              RBAC + per-tenant dashboards/alerts (Grafana orgs)
              + central platform team owning the substrate
```

**Design pillars:**

- **Tenancy isolation:** stamp a `tenant_id` at ingestion and partition every backend by it (Mimir/Loki/Tempo all support native multi-tenancy via the `X-Scope-OrgID` header). One team's cardinality explosion must not OOM another's — enforce **per-tenant ingestion limits, series limits, and query limits**.
- **Self-service with guardrails (golden paths):** teams get auto-generated RED dashboards and SLO scaffolding from shared instrumentation libraries, so they don't reinvent (or mis-build) observability. The platform team owns the substrate; product teams own their signals.
- **Quotas + chargeback** per tenant to control cost and align incentives (Q26); noisy tenants pay or get throttled, not silently subsidized.
- **Policy as code:** alerting rules, SLO definitions (OpenSLO/Sloth), and redaction rules in version control, reviewed and templated.
- **Federated governance:** central standards (semantic conventions, naming, required resource attributes, retention classes) enforced in the pipeline; local autonomy above that line.
- **The platform must be more reliable than tenants' systems** and in an isolated failure domain.

**Trade-off:** strict isolation (separate stacks per tenant) maximizes blast-radius safety but multiplies cost and ops; shared-multi-tenant maximizes efficiency but needs rigorous quota enforcement. At 200 teams I'd run shared multi-tenant backends with hard per-tenant limits — the operational economics of 200 separate stacks are untenable.

### Q30. [Theory] How do you make the observability system itself observable and resilient? (Meta-monitoring)

The observability stack is production-critical infrastructure: if it fails *during* an incident — exactly when load and error telemetry spike — you're blind when it matters most. Design principles:

- **Independent failure domain.** Run observability in a separate account/cluster/region from the workloads it watches. If a region dies, you must still be able to observe it from outside. Never let prod and its monitoring share a single point of failure.
- **Meta-monitoring (watch the watchers).** A separate, dead-simple monitor (often an external SaaS like a synthetic checker or a cheap independent Prometheus) confirms the main pipeline is ingesting, the collectors are up, and dashboards are live. The first thing to verify in an incident is "do I trust this data?"
- **Dead-man's switch.** An alert that fires when *no data arrives* — because "no alerts" can mean "all healthy" or "the pipeline is down," and those are catastrophically different. A continuously-firing heartbeat that pages when it goes *silent* distinguishes them.
- **Back-pressure & queuing, fail open for app.** Collectors need persistent queues and back-pressure so a telemetry surge doesn't drop data or, worse, slow the *application* (instrumentation must degrade gracefully — never let the inability to export telemetry block business traffic).
- **Graceful degradation tiers.** Under overload, shed low-value telemetry (debug logs, baseline traces) first; protect error traces, SLO metrics, and audit logs last.
- **Synthetic monitoring & SLOs on the platform itself** — the observability platform has its own SLOs (ingest latency, query availability) and on-call.

The principle in one line: **your monitoring must be more reliable, and independently failable, than the systems it monitors** — otherwise the first casualty of a major incident is your ability to understand it.

---

## ✅ Key Takeaways

- Monitoring (known failure modes) is a subset of observability (answering unanticipated questions from external outputs); design for **unknown unknowns**.
- The **four golden signals** (latency, traffic, errors, saturation) plus **RED** (services) and **USE** (resources) are your instrumentation defaults.
- **SLI → SLO → error budget** converts reliability arguments into arithmetic; alert on **SLO burn rate**, not raw thresholds, using **multi-window multi-burn-rate** alerts.
- **Alert on symptoms (user pain), not causes (CPU);** every page must be actionable with a runbook, or it becomes fatigue.
- **Cardinality is the silent killer of metrics** — keep labels bounded; high-cardinality dimensions belong in traces/wide events, not metric labels.
- **OpenTelemetry** is the vendor-neutral data plane; use a two-tier collector (agent + gateway) and **tail sampling** to keep 100% of errors/slow traces while cutting volume 90%+.
- **Correlate via a shared `trace_id`** in logs (MDC) and **exemplars** in metrics; one ID stitches metrics → traces → logs.
- **Observability has real cost** (often 10–30% of infra); govern with sampling, tiering, retention, and chargeback — without creating incident-time blind spots.
- **The observability system must be more reliable and independently failable** than what it watches; meta-monitor it and use a dead-man's switch.
- Treat **telemetry data with the same security classification as production data** — redact PII/secrets at the collector, enforce RBAC and TLS.

## ⚠️ Common Pitfalls

- Putting **user IDs, request IDs, or full URLs in metric labels** → cardinality explosion → OOM'd TSDB and runaway bills.
- Using **averages for latency** instead of histograms/percentiles, hiding the tail that users actually feel.
- **Head sampling only** → dropping 90% of the error and slow traces you needed most.
- **Alerting on causes** (high CPU, GC) → paging on benign conditions → alert fatigue → ignored real incidents.
- **Computing percentiles per-instance and averaging them** — mathematically meaningless; aggregate histograms server-side.
- **Single-threshold SLO alerts** that fire too slowly on outages and never auto-reset.
- **Unstructured text logs** that force expensive full-text indexing and can't be aggregated or sampled by trace.
- **No correlation key** — three disconnected tools, forcing timestamp copy-paste during incidents.
- **Losing trace context across `@Async`/thread pools/reactive boundaries**, breaking traces and dropping `traceId` from logs.
- **Running observability in the same failure domain as prod**, going blind exactly when an incident hits.
- **Logging secrets/PII** and ignoring GDPR/PCI retention and purge requirements in telemetry stores.
- **"Log everything just in case"** with no chargeback — telemetry cost quietly overtaking infra cost.

## 📚 Further Reading

- *Site Reliability Engineering* and *The Site Reliability Workbook* — Google (free online; the canonical source for SLO, error budgets, and multi-window burn-rate alerting).
- *Observability Engineering* — Charity Majors, Liz Fong-Jones, George Miranda (O'Reilly) — wide events, high-cardinality, observability-driven development.
- *Distributed Systems Observability* — Cindy Sridharan (O'Reilly, free) — concise foundations of the three pillars and their limits.
- "The Tail at Scale" — Jeffrey Dean & Luiz André Barroso, *Communications of the ACM*, 2013 — the definitive paper on tail latency at fan-out scale.
- [OpenTelemetry documentation](https://opentelemetry.io/docs/) — specs, semantic conventions, collector, language SDKs (Java).
- [Google SRE — Service Level Objectives chapter](https://sre.google/sre-book/service-level-objectives/) and the [Alerting on SLOs workbook chapter](https://sre.google/workbook/alerting-on-slos/).
