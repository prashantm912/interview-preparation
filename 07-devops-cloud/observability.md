# Observability (Metrics, Logs, Traces)

A staff-level deep dive into modern observability: the three pillars, OpenTelemetry, distributed tracing, SLO/error-budget engineering, sampling, cardinality control, and a full end-to-end production-incident debugging playbook — with Java-first examples.

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

### Q1. [Theory] What are the "three pillars of observability" and why do they differ?

The three pillars are **metrics**, **logs**, and **traces**. They differ in *shape*, *cost*, and *the question they answer*:

- **Metrics** are numeric measurements aggregated over time (counters, gauges, histograms). They are cheap to store, fast to query, and great for *"is something wrong and how much?"* — but they are pre-aggregated, so they lose per-request detail.
- **Logs** are timestamped, often-structured records of discrete events. They answer *"what exactly happened in this code path?"* They are high-fidelity but expensive at volume and hard to aggregate.
- **Traces** follow a single request as it flows across services, capturing causal parent/child timing (spans). They answer *"where did the latency / error come from across the distributed call graph?"*

The reason all three exist is that no single signal scales across all three of the *what / where / why* questions. Metrics tell you *that* p99 latency spiked; traces tell you *which* downstream call caused it; logs tell you *why* that call failed. Mature platforms correlate the three (e.g., exemplars link a metric bucket to a trace, and a trace links to its logs via `trace_id`).

```
        WHAT is wrong?        WHERE is it?            WHY did it happen?
        ┌───────────┐         ┌───────────┐          ┌───────────┐
        │  METRICS  │ ──────► │  TRACES   │ ───────► │   LOGS    │
        │ aggregate │ exemplar│ per-req   │ trace_id │ per-event │
        │  cheap    │ link    │ causal    │ link     │ fidelity  │
        └───────────┘         └───────────┘          └───────────┘
```

### Q2. [Theory] What is the difference between monitoring and observability?

**Monitoring** is checking *known* failure modes against predefined dashboards and alerts — you decide in advance what to measure ("CPU > 80%", "5xx rate > 1%"). **Observability** is the property of a system that lets you ask *new, unanticipated* questions about its internal state from the outside, without shipping new code. The classic phrasing: monitoring handles **known-unknowns**, observability handles **unknown-unknowns**.

In practice the distinction shows up in *high-cardinality, high-dimensionality* data. A monitoring system might track "error rate per service"; an observable system lets you slice that error rate by `customer_id`, `app_version`, `region`, and `feature_flag` arbitrarily at query time. Monitoring is a subset of what an observable system enables. The trade-off is cost: arbitrary slicing needs wide, structured events and storage that supports high cardinality, which is more expensive than pre-aggregated metrics.

### Q3. [Theory] What are the Four Golden Signals?

From Google's SRE book, the four golden signals for a user-facing system are:

1. **Latency** — how long requests take. Crucially, measure success and error latency *separately*, because a fast 500 can hide a slow 200.
2. **Traffic** — demand on the system (requests/sec, transactions/sec).
3. **Errors** — rate of failed requests (explicit 5xx, implicit wrong-content, or policy failures like "too slow").
4. **Saturation** — how "full" the most constrained resource is (CPU, memory, I/O, connection pool). Saturation predicts *future* problems; the others describe *current* ones.

If you can only instrument four things on a service, instrument these. They map cleanly onto SLIs and onto the RED method (for request-driven services) and USE method (for resources).

### Q4. [Practical] You add `log.info()` everywhere and grep the files. Why is that not enough in production?

Plain unstructured logs (`log.info("user " + id + " did thing")`) break down because:

- **No aggregation** — you cannot answer "how many users hit this path in the last hour" without fragile regex.
- **No correlation** — across 30 microservice replicas you cannot stitch one request's journey together.
- **Volume/cost** — at thousands of req/sec, file-grep is impossible and disks fill.
- **No structure** — fields are buried in free text, so tools cannot index or filter them.

The production-grade approach is **structured logging** (JSON), shipped to a central **log aggregation** system (ELK/EFK/Loki), with a **correlation/trace ID** on every line so you can pivot from one log to the full request.

```java
// Bad: unstructured, uncorrelated
log.info("Order " + orderId + " failed for user " + userId);

// Good: structured + correlated (SLF4J + MDC, JSON encoder via logstash-logback-encoder)
MDC.put("trace_id", currentTraceId());
MDC.put("order_id", orderId);
MDC.put("user_id", userId);
log.error("order_failed", kv("reason", "payment_declined"));
// emits: {"ts":"...","level":"ERROR","msg":"order_failed","trace_id":"4bf9...",
//         "order_id":"o-123","user_id":"u-99","reason":"payment_declined"}
```

### Q5. [Theory] What is a correlation ID (and a trace ID), and why does every log line need one?

A **correlation ID** is a unique identifier attached to a request at its entry point and propagated through every service, queue, and log line that handles it. A **trace ID** is the tracing-system equivalent (a 128-bit ID in OpenTelemetry / W3C Trace Context). They let you reconstruct the complete story of a single request across many services and many log files: filter logs by `trace_id=4bf92f...` and you see exactly that one request everywhere it went. Without it, distributed debugging degenerates into guessing which of thousands of interleaved log lines belong together. Best practice is to put the trace ID into the logging **MDC** (Mapped Diagnostic Context) so it's automatically stamped on every line, and to return it in an error response header so support can map a customer complaint to a trace.

### Q6. [Coding] Implement a counter and a latency histogram with Micrometer in Java.

**Problem:** Expose two metrics for an order-creation endpoint: a counter of created orders (tagged by status) and a timer for handler latency, in a Spring Boot 3 app using Micrometer + Prometheus.

```java
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

public class OrderService {
    private final Counter created;
    private final Timer latency;

    public OrderService(MeterRegistry registry) {
        // Counter: monotonic, tagged. Keep tag VALUES bounded (status has few values).
        this.created = Counter.builder("orders.created.total")
                .description("Orders created")
                .tag("status", "ok")
                .register(registry);
        // Timer = count + sum + histogram buckets -> enables p50/p95/p99 server-side.
        this.latency = Timer.builder("orders.handler.latency")
                .publishPercentileHistogram()       // exports buckets for quantiles
                .sla(java.time.Duration.ofMillis(100), java.time.Duration.ofMillis(500))
                .register(registry);
    }

    public Order create(OrderRequest req) {
        return latency.record(() -> {              // times the lambda
            Order o = doCreate(req);
            created.increment();
            return o;
        });
    }
    private Order doCreate(OrderRequest req) { /* ... */ return new Order(); }
}
```

- **Why a Timer not a Gauge?** A gauge samples a current value (e.g., queue depth) and can miss spikes between scrapes; a timer accumulates every observation so quantiles are accurate.
- **`publishPercentileHistogram()`** ships native histogram buckets so Prometheus computes `histogram_quantile()` *server-side and aggregatable across instances* — never average client-side percentiles.
- **Time / Space:** instrumentation is O(1) per call; histogram memory is O(buckets) per series.
- **Edge cases:** ensure the lambda still records latency on exception (wrap so the timer fires in a `finally`); never put unbounded values (like `order_id`) in a tag — that explodes cardinality (see Q14).

---

## 🟡 Intermediate (3–7 yrs)

### Q7. [Theory] Explain the RED method vs the USE method. When do you use each?

**RED** (Tom Wilkie) is for **request-driven services**: **R**ate (requests/sec), **E**rrors (failed requests/sec), **D**uration (latency distribution). It's the right lens for an API or microservice — one RED dashboard per service gives you a uniform, comparable view across the fleet.

**USE** (Brendan Gregg) is for **resources**: **U**tilization (% busy), **S**aturation (queue/backlog beyond capacity), **E**rrors (device/resource errors). It's the right lens for CPUs, disks, network interfaces, memory, and connection pools.

They are complementary: RED tells you the *service* is slow; USE tells you *which resource* is the bottleneck. The golden signals are essentially RED + saturation. A common interview answer: "I put a RED dashboard on every service and a USE dashboard on every node/resource; RED finds the symptom, USE finds the cause."

```
RED (per service)                USE (per resource)
 ┌────────────┐                   ┌────────────┐
 │ Rate       │  symptom          │ Utilization│  cause
 │ Errors     │  ───────────────► │ Saturation │
 │ Duration   │                   │ Errors     │
 └────────────┘                   └────────────┘
```

### Q8. [Theory] Define SLI, SLO, SLA, and error budget. How do they relate?

- **SLI (Service Level Indicator):** a measured ratio of good events to valid events, e.g. `successful requests / total requests` or `requests under 300ms / total`.
- **SLO (Service Level Objective):** the internal target for an SLI over a window, e.g. `99.9% of requests succeed over 28 days`. This is what you engineer toward.
- **SLA (Service Level Agreement):** a *contractual* promise to customers with financial/legal consequences if breached. SLAs are looser than SLOs (you alert on the SLO long before the SLA is at risk).
- **Error budget:** `1 − SLO`. A 99.9% SLO permits 0.1% failures ≈ **43.2 minutes/month** of badness. The budget is *spendable*: as long as you're under budget, ship fast and take risks; when you exhaust it, freeze risky changes and pour effort into reliability.

The relationship: SLI is the measurement, SLO is the goal, error budget is the remaining allowance, and SLA is the external contract. Error budgets turn the dev-vs-ops "ship features vs stay stable" conflict into a shared, data-driven decision.

```
Availability  Downtime/30d   Downtime/year
99%           ~7.2 h         ~3.65 days
99.9%         ~43.2 min      ~8.76 h
99.95%        ~21.6 min      ~4.38 h
99.99%        ~4.3 min       ~52.6 min
99.999%       ~26 s          ~5.26 min
```

### Q9. [Practical] How would you implement a multi-window, multi-burn-rate SLO alert?

Naively alerting "fire when 28-day SLO is violated" is too slow (you only page after the budget is already gone). Naively alerting "fire on any single-minute error spike" is too noisy. The Google SRE answer is **multi-window, multi-burn-rate** alerting.

**Burn rate** = how fast you're consuming the error budget relative to "sustainable." Burn rate 1 means you'd exactly exhaust the budget at the window's end; burn rate 14.4 means you'd exhaust a 30-day budget in ~2 days.

- **Page (fast burn):** burn rate ≥ 14.4 over a **1h** window *AND* ≥ 14.4 over a **5m** window. The long window confirms it's real; the short window ensures the alert resets quickly once fixed.
- **Page (medium):** burn rate ≥ 6 over **6h** AND **30m**.
- **Ticket (slow burn):** burn rate ≥ 1 over **3d** AND **6h**.

In Prometheus, you precompute the SLI ratio as a recording rule, then express burn rate. This gives high precision (few false pages), fast detection for severe outages, and a low-urgency ticket for slow leaks. The trade-off is complexity — it's worth it only once you have a real SLO culture.

### Q10. [Theory] What is OpenTelemetry, and how do the API, SDK, and Collector fit together?

**OpenTelemetry (OTel)** is the CNCF vendor-neutral standard for generating and exporting telemetry (traces, metrics, logs). Its key components:

- **API** — the instrumentation surface your application code (and libraries) compile against (`Tracer`, `Meter`, `Logger`). It has a no-op default, so a library can be instrumented without forcing a backend on the user.
- **SDK** — the concrete implementation you wire up at startup: it does sampling, batching, resource detection, and exporting. You swap SDK config without touching instrumentation code.
- **Collector** — a standalone process/sidecar/gateway that *receives* telemetry (OTLP), *processes* it (batch, filter, redact, tail-sample, transform), and *exports* it to one or many backends. It decouples your apps from vendor specifics: apps speak OTLP to the Collector; the Collector fans out to Prometheus, Jaeger, Tempo, Datadog, etc.

```
 App (OTel API+SDK) ──OTLP──► Collector ──► Jaeger/Tempo (traces)
                                      ├────► Prometheus      (metrics)
                                      └────► Loki/ELK        (logs)
   (instrumentation        (receive→process→export;
    decoupled from           batching, tail-sampling,
    backend)                 PII redaction, routing)
```

The big win is **separation of concerns**: change backends or add redaction centrally in the Collector with zero app redeploys. Use the **agent** Collector (sidecar/daemonset) for offload and the **gateway** Collector for centralized policy and tail sampling.

### Q11. [Theory] Explain spans, the span hierarchy, and context propagation in distributed tracing.

A **trace** is a tree (technically a DAG) of **spans**. Each span represents one operation and carries: a `trace_id` (shared by all spans in the request), a unique `span_id`, a `parent_span_id`, start/end timestamps, a status, and key-value **attributes** plus timestamped **events**. The root span is the entry request; child spans are downstream calls.

**Context propagation** is how the trace context crosses process boundaries. Within a process it travels via thread-locals / OTel `Context`. Across services it is serialized into transport headers — the **W3C Trace Context** standard uses `traceparent` (`version-traceid-spanid-flags`) and `tracestate`. The HTTP client injects these headers; the server extracts them and continues the same trace as a child span. This is what makes a single `trace_id` span 12 microservices.

```
trace_id = 4bf92f3577b34da6...
[ span A: GET /checkout            (root, service=gateway) ]
   └─[ span B: POST /orders        (service=orders)  parent=A ]
        ├─[ span C: SELECT db      (service=orders)  parent=B ]
        └─[ span D: POST /payments (service=payments)parent=B ]
                └─[ span E: SELECT db (service=payments) parent=D ]
        propagation header on each hop: traceparent: 00-4bf92f35...-00f067aa...-01
```

### Q12. [Coding] Manually instrument a cross-service call with OpenTelemetry in Java.

**Problem:** Create a child span around an outbound HTTP call, set attributes, record an exception, and ensure context propagates so the downstream service joins the same trace.

```java
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapSetter;

public class PaymentClient {
    private final Tracer tracer;
    private final OpenTelemetry otel;
    private final HttpClient http;

    PaymentClient(OpenTelemetry otel, HttpClient http) {
        this.otel = otel;
        this.tracer = otel.getTracer("payment-client", "1.0.0");
        this.http = http;
    }

    public PaymentResult charge(String orderId, long cents) {
        Span span = tracer.spanBuilder("POST /payments")
                .setSpanKind(SpanKind.CLIENT)          // CLIENT = outbound call
                .setAttribute("payment.order_id", orderId)
                .setAttribute("payment.amount_cents", cents)
                .startSpan();
        try (Scope scope = span.makeCurrent()) {        // makes span the active context
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("http://payments/charge"));
            // Inject W3C traceparent/tracestate into outbound headers:
            otel.getPropagators().getTextMapPropagator()
                .inject(Context.current(), req, (TextMapSetter<HttpRequest.Builder>)
                        (carrier, key, value) -> carrier.header(key, value));
            HttpResponse<String> resp = http.send(req.build(), BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                span.setStatus(StatusCode.ERROR, "http " + resp.statusCode());
            }
            return PaymentResult.from(resp);
        } catch (Exception e) {
            span.recordException(e);                     // attaches stack trace as a span event
            span.setStatus(StatusCode.ERROR, e.getMessage());
            throw new RuntimeException(e);
        } finally {
            span.end();                                  // MUST end, even on error
        }
    }
}
```

- **Key points:** `makeCurrent()` + try-with-resources `Scope` prevents context leaks across threads; `inject` writes `traceparent` so the downstream `extract` continues the trace; `recordException` + `setStatus(ERROR)` is what makes the span show red and searchable.
- **Edge cases:** always `end()` in `finally` (a never-ended span leaks and breaks the trace); for async/reactive code you must explicitly capture and restore `Context` because thread-locals don't follow the callback.
- **Time/Space:** O(1) overhead per span; memory proportional to attributes until the span is exported and freed.
- **Tip:** in real apps prefer the **Java agent** (`-javaagent:opentelemetry-javaagent.jar`) for zero-code auto-instrumentation of Spring, JDBC, Kafka, etc., and use manual spans only for business-specific operations.

### Q13. [Theory] Compare ELK, EFK, and Loki for log aggregation.

| | ELK | EFK | Loki |
|---|---|---|---|
| Shipper | Logstash / Beats | **Fluentd / Fluent Bit** | Promtail / Alloy / OTel |
| Store | Elasticsearch | Elasticsearch | Loki (object storage) |
| Index model | **Full-text index of contents** | Same | **Indexes only labels**, not body |
| Cost | High (indexes everything) | High | **Low** (cheap object store) |
| Query power | Very rich (Lucene/KQL) | Very rich | LogQL; grep-after-label-filter |
| Best for | Powerful ad-hoc search | K8s-native pipelines | Cost-efficient, Grafana-native |

**ELK** = Elasticsearch + Logstash + Kibana. **EFK** swaps Logstash for **Fluentd/Fluent Bit** (lighter, the de-facto Kubernetes standard). Both index the full log body, giving powerful search but high storage cost and operational heaviness (shard management, JVM tuning).

**Loki** (Grafana) takes the opposite bet: it indexes only a small set of **labels** (like Prometheus) and stores the compressed log body in cheap object storage (S3/GCS). Queries filter by label first, then grep the matched chunks. This is dramatically cheaper at scale, integrates natively with Grafana and Prometheus exemplars, but is weaker at arbitrary full-text search. **Decision rule:** choose Loki when cost and Prometheus/Grafana integration dominate and you query mostly by service/label; choose ELK/EFK when rich full-text search across the whole corpus is a hard requirement.

### Q14. [Theory] What is cardinality, and why is high cardinality dangerous for metrics?

**Cardinality** is the number of unique time series produced by a metric, which is the product of the number of unique values across all its label/tag combinations. A metric `http_requests_total{method, status, endpoint}` with 5 methods × 10 statuses × 50 endpoints = 2,500 series. Add a `user_id` label with 1,000,000 values and you get **billions** of series — a "cardinality explosion."

It's dangerous because dimensional metrics stores (Prometheus, etc.) hold an in-memory index per active series; each series consumes memory, slows ingestion, bloats queries, and can OOM the TSDB. The cardinal sin (pun intended) is putting **unbounded identifiers** — `user_id`, `order_id`, `request_id`, raw URLs with IDs, email addresses — into metric labels.

**Where high cardinality belongs:** traces and wide structured events/logs, which are *not* indexed by every dimension. Rule of thumb: metrics labels must have bounded, low-cardinality values (status, region, instance, route *template*). Put the high-cardinality detail in spans/logs and link them to the metric via **exemplars**.

### Q15. [Practical] You see p99 latency on a metric is 800ms but average is 40ms. How do you investigate?

This is the classic "average lies" scenario — a long tail. My approach:

1. **Trust percentiles, not averages.** The gap means a small fraction of requests are very slow; SLOs and user pain live in the tail.
2. **Slice the histogram by dimension** (route, region, instance, customer tier, version) to localize *which* subset is slow. If it's one instance → node/GC/saturation issue (USE). If it's one route → code path or a downstream dependency.
3. **Pivot to exemplars/traces.** Modern setups attach trace exemplars to histogram buckets, so I jump straight from the slow `>500ms` bucket to an actual slow trace.
4. **Read the slow trace's span breakdown** — is the time in DB, an external API, lock contention, or queueing? A flat gap between spans usually means GC pause or thread-pool/connection-pool saturation.
5. **Correlate with saturation metrics** (CPU, GC pause time, pool active/queued) at the same timestamp.

**What I'd actually do:** confirm with a trace, fix the bottleneck (e.g., add an index, raise pool size, add a timeout+circuit breaker on the slow dependency), and add an SLO burn-rate alert so the tail is caught next time without manual percentile staring.

### Q16. [Coding] Compute p50/p95/p99 from a list of latencies (interview percentile question).

**Problem:** Given latency samples, return p50/p95/p99. Show the simple sort-based approach and discuss the streaming approach used by real metric systems.

```java
import java.util.*;

public class Percentiles {
    // Approach 1: exact, sort-based. O(n log n) time, O(1) extra (in place).
    static double percentile(long[] samples, double p) {
        if (samples.length == 0) throw new IllegalArgumentException("empty");
        long[] copy = samples.clone();
        Arrays.sort(copy);
        // "nearest-rank" method
        int rank = (int) Math.ceil(p / 100.0 * copy.length);
        int idx = Math.min(Math.max(rank - 1, 0), copy.length - 1);
        return copy[idx];
    }

    public static void main(String[] args) {
        long[] s = {10, 12, 11, 9, 500, 13, 10, 14, 600, 11}; // ms, note 2 tail spikes
        System.out.printf("p50=%.0f p95=%.0f p99=%.0f%n",
                percentile(s, 50), percentile(s, 95), percentile(s, 99));
    }
}
```

- **Approach 1 (above):** exact but needs all data in memory and is O(n log n). Fine for a batch/offline computation, impossible at high throughput across a fleet.
- **Approach 2 (production):** **histogram bucketing / sketches**. Systems like Prometheus pre-bucket observations (`le="0.1"`, `le="0.5"`, ...) and apply `histogram_quantile()` (linear interpolation within a bucket) — O(1) memory, *mergeable across instances*. Algorithms like **t-digest** and **DDSketch** (used by Datadog) give bounded-error quantiles in streaming, mergeable form.
- **Critical caveat:** **never average percentiles across instances.** `avg(p99_a, p99_b)` is mathematically meaningless. Aggregate the *histograms*, then compute the quantile once. This is the single most common metrics bug in interviews and in production.
- **Edge cases:** empty input (throw/return NaN), single element (every percentile equals it), and the interpolation choice (nearest-rank vs linear) — state your method.

### Q17. [Practical] How do you safely roll out a logging change that adds a new high-volume field?

Adding a field that fires on every request can 2–3× log volume overnight, blow up storage cost, and trip ingestion rate limits. Production-safe rollout:

1. **Estimate volume first:** field size × request rate × retention. A 200-byte field at 10k req/s ≈ 170 GB/day raw.
2. **Gate behind a flag / sampling:** emit the field for a small percentage or only at `DEBUG`/error level initially.
3. **Watch ingestion + cost dashboards** (and your index's shard/heap pressure for ES) during rollout.
4. **Prefer structured key-value over free text** so it's parseable and you can later sample/drop it in the Collector without code changes.
5. **Set retention/lifecycle** (hot/warm/cold, ILM) appropriate to the field's value.

**What I'd actually do:** ship it behind a config flag at 1% sampling, confirm cost and ingestion impact in staging-mirrored prod traffic, then ramp — and put the drop/redact rule in the OTel Collector so I control volume centrally.

---

## 🟠 Advanced (8–12 yrs)

### Q18. [Theory] Compare head-based vs tail-based sampling. What are the trade-offs and where does each run?

Sampling decides which traces to keep, because storing 100% of traces at scale is prohibitively expensive.

- **Head-based sampling:** the keep/drop decision is made at the trace's *start* (root), encoded in the `traceparent` sampled flag, and propagated so all services in the trace agree. It's cheap, stateless, and consistent (no broken/partial traces). The fatal weakness: you decide *before* you know if the trace is interesting — so you'll randomly drop the rare error or the slow outlier you most wanted.
- **Tail-based sampling:** the Collector *buffers all spans of a trace* until it completes, then decides based on the whole trace — keep all errors, keep anything over 1s, keep a % of normal traffic. You keep exactly the interesting traces, at the cost of: buffering memory, latency, and the need for all spans of a trace to reach the *same* collector instance (requires `trace_id`-aware load balancing via a `loadbalancing` exporter in a two-tier Collector setup).

```
HEAD: decide at root ──► flag propagated ──► all hops obey (cheap, may drop errors)
TAIL: every span ──► buffer by trace_id in Collector ──► trace complete?
                                      └─► policy: keep errors + slow + 5% baseline
```

**Real-world pattern:** combine them — light head sampling to cut obvious noise, then tail sampling in a gateway Collector to guarantee 100% of errors and slow traces are kept. This is what most mature platforms (and Datadog/Honeycomb-style backends) do.

### Q19. [Practical] Walk through debugging a production incident end-to-end using all three pillars.

**Scenario:** PagerDuty fires — checkout p99 latency SLO burn-rate alert; some users see 500s.

```
1. DETECT (metrics / SLO)
   - Alert: error-budget fast burn (>14.4x, 1h & 5m windows) on checkout.
   - Golden-signals dashboard: error rate up to 4%, p99 5s, traffic normal,
     saturation on payments-svc DB connection pool = 100% in-use.

2. SCOPE (metrics slicing)
   - Slice errors by: region (all), version (spiked after v2.3.1 deploy 12 min ago),
     endpoint (/checkout only), customer tier (all).  -> Strong signal: recent deploy.

3. LOCATE (traces)
   - Jump from the >2s histogram bucket via EXEMPLAR to a slow trace.
   - Span waterfall: gateway 50ms -> orders 80ms -> payments span = 4.8s,
     mostly "waiting to acquire DB connection" (queue time), not query time.
   - => payments-svc is exhausting its connection pool under v2.3.1.

4. ROOT CAUSE (logs, correlated by trace_id)
   - Filter logs: trace_id=4bf9... -> "connection not returned, leaked from
     PaymentRetry path"; v2.3.1 added a retry that opens a new connection but
     never closes it on a specific error branch.

5. MITIGATE
   - Roll back v2.3.1 (fastest, error budget is burning) OR feature-flag off the
     new retry path. Error rate recovers within minutes; pool drains.

6. VERIFY & PREVENT
   - Watch SLO burn rate return to <1x; confirm p99 normal.
   - Add: alert on pool saturation (USE), a leak test, and a span attribute
     for pool-wait time so this is one click next time. Blameless postmortem.
```

The point of the answer: **metrics detect and scope, traces locate, logs explain** — and the trace_id is the thread that ties them together. The fastest mitigation (rollback/flag) precedes the perfect fix.

### Q20. [Theory] How do exemplars connect metrics to traces, and why are they powerful?

An **exemplar** is a sample data point attached to a metric observation (typically a histogram bucket) that carries a `trace_id` (and optional labels) of a representative request that fell into that bucket. So when you look at the `>1s` latency bucket in Prometheus/Grafana, each exemplar is a clickable link straight to an actual slow trace.

This solves the classic metrics weakness — metrics tell you *that* the p99 is bad but are aggregated, so historically you'd then go hunting for an example. Exemplars give you the bridge with O(1) cost (you store one or a few trace IDs per bucket per scrape, not per request). They preserve the cheapness of metrics while restoring per-request drill-down, and they require head/tail sampling to be consistent so that the linked trace was actually kept. Micrometer + Prometheus (with the OpenMetrics format) and OTel both support exemplars natively. It's a defining feature of "correlated" observability platforms.

### Q21. [Practical] Your Prometheus is OOMing and queries are slow. Diagnose and fix.

Prometheus memory is dominated by the number of **active series** (cardinality). Diagnosis and remediation:

1. **Find the offenders:** `topk(20, count by (__name__)({__name__=~".+"}))` and inspect `prometheus_tsdb_head_series`. Look at `/status/tsdb` for the highest-cardinality metrics and labels.
2. **Identify the bad label** — almost always an unbounded value snuck into a label (`user_id`, `path` with IDs, `pod` churn from frequent restarts, dynamic error messages).
3. **Fix at the source:** remove the high-cardinality label; use route *templates* (`/users/{id}` not `/users/12345`); move per-entity detail to traces/logs.
4. **Fix in the pipeline:** add `metric_relabel_configs` to drop/aggregate offending labels at scrape time.
5. **Architecture:** if legitimately high volume, shard with **functional sharding** or move to a horizontally scalable backend — **Thanos**, **Cortex/Mimir**, or VictoriaMetrics — and downsample old data. Add recording rules to precompute expensive aggregations so dashboards don't recompute on every load.

**Trade-off note:** the cheap, correct fix is almost always cardinality reduction at the source, not throwing more RAM at it — RAM only delays the next explosion.

### Q22. [Coding] Build a thread-safe rate-aware sampler (consistent head sampling with always-keep-errors hook).

**Problem:** Implement a head sampler that keeps a fixed fraction of traces *deterministically by trace ID* (so all services make the same decision) and never drops a trace explicitly marked important.

```java
public final class TraceIdRatioSampler {
    private final long threshold; // keep if hashed trace id < threshold

    public TraceIdRatioSampler(double ratio) {
        if (ratio < 0 || ratio > 1) throw new IllegalArgumentException("ratio 0..1");
        // Map ratio onto the unsigned long space.
        this.threshold = (ratio >= 1.0) ? Long.MAX_VALUE
                : (long) (ratio * (double) Long.MAX_VALUE);
    }

    /** Deterministic on traceId => every service in the trace agrees. Thread-safe (no state). */
    public boolean shouldSample(String traceIdHex, boolean forceKeep) {
        if (forceKeep) return true;                 // e.g., debug header / known-bad path
        // Use the low 64 bits of the 128-bit trace id; take absolute, compare to threshold.
        long lower = Long.parseUnsignedLong(traceIdHex.substring(16), 16);
        long positive = lower & Long.MAX_VALUE;     // clear sign bit -> [0, MAX]
        return positive < threshold;
    }

    public static void main(String[] args) {
        TraceIdRatioSampler s = new TraceIdRatioSampler(0.10); // keep 10%
        String tid = "4bf92f3577b34da6a3ce929d0e0e4736";
        System.out.println(s.shouldSample(tid, false));
        System.out.println(s.shouldSample(tid, true)); // forced -> always true
    }
}
```

- **Why hash the trace ID, not call `random()`?** Because the decision must be *consistent across services* — a per-service coin flip would keep a span in service A but drop its child in service B, producing broken traces. Hashing the shared trace ID makes the answer identical everywhere with zero coordination. This mirrors OTel's `TraceIdRatioBasedSampler`.
- **`forceKeep`** is the hook for "always keep this trace" (debug header, error path, VIP customer) — the basis of combining head sampling with guaranteed-keep policies.
- **Thread-safety:** the sampler is stateless and immutable, so it's safe to share across all request threads with no locks.
- **Time/Space:** O(1) per decision, O(1) memory.
- **Edge cases:** validate `traceIdHex` length (32 hex chars); `ratio=0` keeps nothing, `ratio=1` keeps all; sign-bit clearing avoids a negative modulo bug.

### Q23. [Theory] What is the OpenTelemetry Collector's role in PII redaction and data governance? Note the security implications.

Telemetry is a notorious **PII leak vector**: developers log full request bodies, headers (`Authorization`, cookies, session tokens), emails, card numbers, and query strings — and that data then lands in a third-party observability vendor, often outside your compliance boundary, with long retention.

The **Collector** is the right enforcement point because it sits between apps and backends and can apply policy *centrally* without app redeploys:

- **`redaction` / `transform` / `attributes` processors** to hash, mask, or drop sensitive attributes (e.g., strip `http.request.header.authorization`, mask `user.email`).
- **`filter` processor** to drop entire spans/logs that match sensitive patterns.
- **Allow-list, not deny-list** for attributes when possible — safer default.
- Enforce **TLS** on OTLP, authentication on receivers, and ensure the Collector itself doesn't log payloads.

**Security implications:** treat the telemetry pipeline as in-scope for GDPR/PCI/HIPAA. Tokens captured in traces are credentials that can be replayed. Centralizing redaction in the Collector means one audited control point instead of trusting every service and every library to behave. Also scope/secure access to dashboards — traces and logs can expose sensitive business data and become an attacker recon goldmine.

### Q24. [Practical] How do you measure SLOs for an asynchronous/event-driven pipeline (no simple request/response)?

Request/response SLIs (success ratio, latency) don't map cleanly to async systems (queues, streaming, batch). I'd define SLIs around the properties that actually matter to consumers:

- **Freshness/latency:** age of the newest successfully processed event (`now − event_processed_time`), e.g., "99% of events processed within 5 min of arrival." This is the dominant SLI for pipelines.
- **Correctness/completeness:** fraction of events processed without error / not dead-lettered.
- **Coverage:** fraction of expected partitions/shards making progress.
- **Throughput vs backlog (saturation):** consumer lag (e.g., Kafka consumer group lag) is the leading indicator — rising lag predicts a freshness SLO breach before it happens.

**Tracing async** requires explicit context propagation through the broker: inject `traceparent` into message headers on produce, extract on consume, and use **span links** (not parent-child) to connect a batch consumer span to the many producer spans it drains. **What I'd actually do:** alert on consumer-lag burn rate as the early signal, define the customer-facing SLO on event freshness, and propagate trace context through message headers so a stuck event is traceable end-to-end.

---

## 🔴 Expert (15+ yrs)

### Q25. [Theory] Make the architectural case: when do you choose pre-aggregated metrics vs wide structured events ("observability 2.0")?

The deep trade-off is **aggregate-on-write vs aggregate-on-read**.

- **Pre-aggregated metrics (Prometheus model):** you decide dimensions up front and aggregate at write time. Cheap storage, fast known queries, but you *cannot* ask a question you didn't pre-plan, and high cardinality is fatal. Great for SLOs, alerting, capacity, and dashboards.
- **Wide structured events (Honeycomb-style "observability 2.0"):** emit one rich event per unit of work with *hundreds* of high-cardinality fields, store raw, and aggregate at query time. You can slice by `customer_id`, `build_id`, `feature_flag` arbitrarily to debug unknown-unknowns. Cost shifts to storage + query compute; columnar stores and sampling make it viable.

The senior answer is **not "pick one."** Drive **alerting and SLOs from cheap metrics** (deterministic, low-latency, low-cost), and drive **deep debugging from wide events/traces** (flexible, high-cardinality). Many orgs are converging on a model where structured event/trace data *derives* metrics, reducing duplicated instrumentation. The decision hinges on your debugging culture, cardinality needs, and budget — and on whether your teams actually do exploratory analysis or just watch dashboards.

### Q26. [Behavioral] You're asked to cut the observability bill by 40% without going blind. How do you lead it?

I'd frame it as an **optimization, not amputation**, and lead it data-driven and cross-team:

1. **Measure what we pay for:** attribute cost to teams/signals (metrics series, log GB, trace volume). Usually a Pareto distribution — a few metrics/log sources drive most spend.
2. **Attack the biggest, lowest-value first:** drop debug logs in prod, kill duplicate/unused dashboards and metrics (query the metric-usage stats), cut retention on cold data, and slash high-cardinality labels nobody queries.
3. **Sample intelligently, not blindly:** move to tail-based sampling that keeps 100% of errors and slow traces while dropping routine successes — preserving debuggability where it matters.
4. **Move logs to tiered/cheaper storage** (Loki-style label index + object store; warm/cold tiers).
5. **Protect SLO and incident signals** — those are non-negotiable; the cut comes from redundancy and noise.

**Leadership angle:** publish a cost-per-team scorecard to create ownership, set guardrails (cardinality budgets, default retention) so cost doesn't creep back, and get explicit sign-off from each team on *their* trade-offs. I'd commit to a metric: 40% cost cut with **no regression in MTTR or SLO coverage**, measured over the next two quarters.

### Q27. [Theory] How do you design observability for a system where the observability system itself can fail (the "monitoring the monitor" problem)?

If your telemetry pipeline shares fate with production, an outage blinds you exactly when you need sight most. Principles:

- **Independent failure domains:** the monitoring stack runs in separate infrastructure/accounts/regions from the workloads it watches. Don't run Prometheus on the same cluster it's the only monitor for.
- **Dead-man's switch / heartbeat alert:** an alert that fires when telemetry *stops arriving* ("if I haven't heard a heartbeat in 5 min, page"). Absence of data is itself a signal — many outages first manifest as a silent pipeline.
- **Meta-monitoring:** a small, independent monitor watches the main monitoring system's health (Prometheus scrape failures, Collector queue saturation, ingestion lag), ideally via a different vendor/path (e.g., an external synthetic check + a third-party uptime service).
- **Local buffering + backpressure:** Collectors buffer to disk and apply backpressure so a backend blip doesn't drop data or crash apps; apps must degrade gracefully if the Collector is down (never block the request path on telemetry export).
- **Synthetic / black-box probes** from outside your infra to confirm user-facing reality independent of internal signals.

The mantra: **the alert you most need is the one that fires when everything goes quiet.**

### Q28. [Practical] A vendor lock-in / migration decision: leadership wants to switch observability backends. How do you de-risk it with OpenTelemetry?

This is exactly the problem **OpenTelemetry** was created to solve, and I'd use it as the insulation layer:

1. **Instrument against the OTel API, export OTLP** — never code against a vendor SDK. Then the backend is a config change in the Collector, not a fleet-wide reinstrumentation.
2. **Run a gateway Collector** as the single egress point; switching/dual-shipping backends becomes a Collector pipeline edit (fan-out exporters to old *and* new during migration).
3. **Dual-write and compare:** ship to both backends for a validation window; compare dashboards, alert parity, and query coverage before cutover. Migrate alerts and SLOs deliberately — they're the highest-risk artifacts.
4. **Inventory lock-in surfaces:** proprietary query languages, dashboards, and alert definitions are the real switching cost (not the data path). Budget for rebuilding those; keep dashboards-as-code (Grafana/Terraform) to ease portability.
5. **Cost & cardinality model the new vendor** before committing — pricing models (per-host vs per-GB vs per-series) can flip the economics.

**What I'd actually do:** put OTel + a gateway Collector in place *first* (decoupling), run dual-export for a quarter, achieve alert/dashboard parity as code, then cut over with a rollback path. The Collector turns a terrifying migration into a routed config change.

### Q29. [Theory] Discuss the performance and correctness pitfalls of instrumenting async, reactive, and virtual-thread (Project Loom) Java code for tracing.

Tracing relies on a *current context* (the active span) carried in thread-local storage. That assumption breaks the moment work hops threads:

- **Reactive / CompletableFuture / executors:** the span set on thread A isn't visible on thread B running the continuation, so child spans attach to the wrong parent or to none, producing orphaned/flat traces. Fix: capture `Context` at submission and re-`makeCurrent()` in the task (OTel provides `Context.taskWrapping(executor)` and Reactor/RxJava instrumentation hooks). Never rely on thread-locals surviving a `flatMap`.
- **Virtual threads (Loom, Java 21+):** context propagation via `ThreadLocal` *does* work per virtual thread, but pinning, the sheer *volume* of threads, and span lifecycle become concerns. With millions of cheap virtual threads you can generate far more spans than before — pushing sampling and cardinality limits. `ScopedValue` (preview) is the Loom-friendly successor to `ThreadLocal` and is what context propagation is migrating toward.
- **Performance:** span creation, attribute boxing, and exporter batching add CPU and allocation pressure; on hot paths, prefer sampling, avoid expensive attributes (no full payloads), and use the batch span processor. Synchronous/blocking exporters on the request path are a latency landmine — always batch and export off-thread.
- **Correctness:** unended spans (forgotten `end()` in async callbacks) leak memory and corrupt traces; this is far easier to get wrong in async code than in straight-line code.

The expert takeaway: **automatic instrumentation handles the common frameworks, but any custom async boundary needs explicit context propagation**, and Loom shifts the bottleneck from thread cost to telemetry volume.

### Q30. [Behavioral] Tell me how you'd build an observability *culture*, not just tooling, across many teams.

Tooling without culture produces expensive dashboards nobody reads. My playbook, drawn from leading platform orgs:

- **Make it a paved road:** ship a golden-path library/agent so a new service gets RED metrics, trace propagation, structured logs, and a default dashboard *for free*. If observability is opt-in and manual, it won't happen consistently.
- **SLOs owned by product teams, not a central SRE silo** — each team defines user-centric SLIs/SLOs and owns its error budget; central platform provides the framework and guardrails (cardinality budgets, cost scorecards).
- **Blameless postmortems** that explicitly ask "what observability gap delayed detection or diagnosis?" — every incident should *improve* instrumentation. Track MTTD/MTTR as the outcome metric.
- **Game days / chaos drills** to validate that alerts fire and traces are usable *before* a real incident.
- **Cost and cardinality as first-class citizens** with budgets, so observability stays sustainable.

The behavioral signal an interviewer wants: I treat observability as a *product* with users (on-call engineers), measure its effectiveness by detection/diagnosis time, and embed it into the SDLC and incident process rather than bolting it on. A real example: at scale, mandating trace context propagation in the shared HTTP/Kafka client (so no team could forget it) cut cross-service debugging time dramatically — culture enforced by the paved road, not by memos.

---

## ✅ Key Takeaways

- **Three pillars are complementary, not interchangeable:** metrics detect & scope (*what*), traces localize (*where*), logs explain (*why*); the `trace_id` is the thread that ties them together.
- **Monitoring = known-unknowns; observability = unknown-unknowns.** The dividing line is arbitrary high-cardinality slicing at query time.
- **Use the frameworks:** Four Golden Signals (Latency/Traffic/Errors/Saturation), RED for services, USE for resources.
- **SLO + error budget** turns reliability into a shared, data-driven decision; alert on **multi-window multi-burn-rate**, not raw thresholds.
- **OpenTelemetry (API/SDK/Collector)** decouples instrumentation from backends — the antidote to vendor lock-in; the Collector is also your central point for tail sampling and PII redaction.
- **Cardinality is the cost & stability lever:** keep metric labels bounded; push high-cardinality detail to traces/wide events and link via **exemplars**.
- **Never average percentiles across instances** — aggregate histograms, then compute the quantile.
- **Sampling:** head is cheap & consistent but blind to rarity; tail keeps the interesting traces at buffering cost — combine them.

## ⚠️ Common Pitfalls

- Putting unbounded IDs (`user_id`, `order_id`, raw URLs) into metric labels → cardinality explosion and TSDB OOM.
- Trusting averages instead of percentiles; or averaging p99 across hosts (mathematically meaningless).
- Unstructured `log.info` without a `trace_id`/correlation ID → impossible distributed debugging.
- Forgetting to propagate context across async/reactive/thread-pool boundaries → orphaned, flat traces; forgetting `span.end()` → leaks.
- Head-sampling everything, then being unable to find the error/slow trace you needed.
- Logging secrets (auth headers, tokens, PII) into traces/logs that ship to a third-party vendor — a compliance and credential-replay risk.
- Running the monitoring stack in the same failure domain as production, with no dead-man's-switch for "telemetry went silent."
- Synchronous/blocking telemetry export on the request path adding latency; always batch and export off-thread.
- Treating SLAs and SLOs as the same thing — alert on the tighter SLO long before the contractual SLA is at risk.

## 📚 Further Reading

- *Site Reliability Engineering* and *The Site Reliability Workbook* — Google (SLI/SLO/error budgets, golden signals, multi-burn-rate alerting). Free at sre.google.
- *Observability Engineering* — Majors, Fong-Jones, Miranda (O'Reilly) — wide structured events, "observability 2.0", high cardinality.
- *Distributed Systems Observability* — Cindy Sridharan (free O'Reilly report) — the three pillars and their limits.
- **OpenTelemetry documentation** — opentelemetry.io (API/SDK/Collector, semantic conventions, W3C Trace Context).
- **Prometheus documentation** — prometheus.io (histograms, recording rules, relabeling, exemplars) and Grafana Loki/Tempo docs.
- Brendan Gregg, "The USE Method" (brendangregg.com) and Tom Wilkie, "The RED Method" (Grafana/Weaveworks).
