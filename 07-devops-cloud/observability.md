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

## 🧩 Extended Questions — Set 1: Deeper Theory & Practical Operations

### 🟢 Basic — extended

#### Q31. [Theory] Compare counter, gauge, histogram, and summary metric types. When do you use each?

These are the four fundamental metric *instrument types*, and choosing the wrong one is one of the most common instrumentation mistakes:

- **Counter** — a monotonically increasing cumulative value that only goes up (or resets to zero on restart). Use it for *things you count over time*: requests served, errors, bytes sent. You almost never look at the raw counter; you look at its `rate()` over a window. Trying to decrement a counter is a design smell — that's a gauge.
- **Gauge** — a value that can go up *and* down, sampled at a moment in time: current memory used, queue depth, active connections, temperature. The risk: a gauge can miss a spike that happens between scrapes, so it's the wrong choice for anything where the *distribution* or *peak* matters.
- **Histogram** — bucketed observations (count + sum + per-bucket counters). Use it for latencies and sizes where you need percentiles. Buckets are pre-chosen, and crucially they are *aggregatable* across instances, so you compute `histogram_quantile()` server-side.
- **Summary** — like a histogram but computes *client-side* quantiles (φ-quantiles) directly on the instance. The fatal limitation: summary quantiles **cannot be aggregated** across instances (you can't average p99s), so they're falling out of favor versus histograms.

```
Counter  ─ monotonic ↑        rate(http_requests_total[5m])
Gauge    ─ up/down  ↕         node_memory_used_bytes
Histogram─ buckets  ▁▂▅▇      histogram_quantile(0.99, rate(...bucket[5m]))
Summary  ─ client quantiles   http_latency{quantile="0.99"}  (NOT aggregatable)
```

**Decision rule:** count → counter; current level → gauge; distribution/percentiles you'll aggregate across a fleet → histogram; only use summary when you genuinely need a precise per-instance quantile and never aggregate it.

#### Q32. [Practical] What log levels exist, and how should you actually use them in production?

The standard ladder is `TRACE < DEBUG < INFO < WARN < ERROR (< FATAL)`. The practical problem is that teams wildly misuse them, producing either noise or silence. My working definitions:

- **ERROR** — something failed that needs human attention or breaks a user request. Every ERROR should ideally be alertable or at least reviewed. If you log ERROR for a routine, handled, expected condition, you train on-call to ignore errors.
- **WARN** — something recovered or is degraded but the request succeeded (retry succeeded, fell back to cache, deprecated path hit). Useful as a leading indicator.
- **INFO** — significant business/lifecycle events at a sane rate (service started, config loaded, order placed). This is the default production level.
- **DEBUG / TRACE** — developer detail, off in production by default because of volume and cost.

```yaml
# logback-spring.xml — production default, with per-package override
logging:
  level:
    root: INFO
    com.acme.payments: INFO
    org.hibernate.SQL: WARN        # noisy library pinned down
```

**The operational trick** is *dynamic log level control*: Spring Boot Actuator exposes `/actuator/loggers` so you can bump `com.acme.payments` to DEBUG at runtime during an incident, then drop it back — no redeploy. Pair that with **sampling of DEBUG** so even when you flip it on, you only keep a fraction. The anti-pattern is shipping at DEBUG everywhere "just in case": it buries the signal and burns your budget.

#### Q33. [Theory] What is the difference between push and pull metric collection? Why did Prometheus choose pull?

In a **pull** model the monitoring system periodically scrapes an HTTP endpoint each target exposes (`/metrics`). In a **push** model each application sends its metrics to a collector/gateway (StatsD, OTLP push, CloudWatch, Datadog agent).

Prometheus famously chose **pull**, and the reasoning is instructive:

- **Target discovery doubles as health check** — if a scrape fails, you immediately know the instance is down (`up == 0`), no separate liveness needed.
- **No client-side fan-out or backpressure** — the server controls scrape rate, so a misbehaving app can't flood the collector.
- **Easy local testing** — you can curl `/metrics` by hand to see exactly what an instance exposes.
- **Centralized control of cardinality/relabeling** at scrape time.

```
PULL (Prometheus)                    PUSH (StatsD / OTLP / CloudWatch)
 Prometheus ──GET /metrics──► app     app ──send──► collector/gateway
   server decides cadence              app decides cadence
   scrape fail ⇒ instance down         needs separate liveness
```

Pull's weakness is **short-lived / batch jobs** that finish before any scrape, and **targets behind NAT/firewalls** the server can't reach. Prometheus handles those with the **Pushgateway** (a buffer that batch jobs push to and Prometheus then scrapes). OpenTelemetry deliberately supports both — OTLP is push by default, but you can scrape too. The pragmatic answer: pull for long-lived services, push for ephemeral jobs and serverless where there's nothing to scrape.

### 🟡 Intermediate — extended

#### Q34. [Theory] Explain how a Prometheus histogram actually works on the wire, and why `histogram_quantile` is an approximation.

A classic Prometheus histogram is not one series — it's a *family*. For a metric `http_latency_seconds` with buckets, the client exposes:

- `http_latency_seconds_bucket{le="0.1"}` — count of observations ≤ 0.1s (cumulative)
- `http_latency_seconds_bucket{le="0.5"}`, `{le="1"}`, ... `{le="+Inf"}`
- `http_latency_seconds_sum` — sum of all observed values
- `http_latency_seconds_count` — total count

The buckets are **cumulative** ("less than or equal"), which is what makes them aggregatable: to merge two instances you just add corresponding bucket counts.

```
histogram_quantile(0.99, sum by (le) (rate(http_latency_seconds_bucket[5m])))
```

`histogram_quantile()` finds the bucket where the target rank falls, then does **linear interpolation within that bucket**. This is where accuracy comes from — or doesn't:

- If your p99 lands in a bucket spanning `[1s, 10s]`, interpolation assumes a uniform distribution across that huge range, so the reported value can be badly wrong.
- The fix is **well-chosen bucket boundaries** clustered where your SLO lives (e.g., dense buckets around 100–500ms if that's your target), not the defaults.
- Everything above the largest finite bucket lands in `+Inf` and is effectively unbounded — you can't distinguish 11s from 110s.

The modern answer is **native/exponential histograms** (Prometheus native histograms, OTel exponential histograms): buckets are generated dynamically with a fixed *relative* error, giving good accuracy across many orders of magnitude with far fewer series. They're the recommended path for new instrumentation.

#### Q35. [Practical] Write Prometheus recording and alerting rules for an SLO with burn-rate alerts.

Recording rules precompute expensive expressions so dashboards and alerts evaluate cheaply and consistently. Here's a realistic SLO setup for a 99.9% availability target:

```yaml
groups:
- name: slo_checkout_availability
  interval: 30s
  rules:
  # 1) Precompute the error ratio over multiple windows as recording rules.
  - record: job:slo_errors:ratio_rate5m
    expr: |
      sum(rate(http_requests_total{job="checkout",code=~"5.."}[5m]))
      /
      sum(rate(http_requests_total{job="checkout"}[5m]))
  - record: job:slo_errors:ratio_rate1h
    expr: |
      sum(rate(http_requests_total{job="checkout",code=~"5.."}[1h]))
      /
      sum(rate(http_requests_total{job="checkout"}[1h]))

- name: slo_checkout_alerts
  rules:
  # 2) Fast-burn page: 14.4x burn over BOTH 1h and 5m (multi-window).
  - alert: CheckoutErrorBudgetFastBurn
    expr: |
      job:slo_errors:ratio_rate1h > (14.4 * 0.001)
      and
      job:slo_errors:ratio_rate5m > (14.4 * 0.001)
    for: 2m
    labels: { severity: page }
    annotations:
      summary: "Checkout burning error budget 14.4x (fast)"
      description: "1h & 5m error ratio exceed 14.4x of the 0.1% budget."
```

- `0.001` is the budget (`1 − 0.999`); `14.4 *` is the burn-rate multiplier from the Google SRE tables for the 1h/5m pair.
- The **`and`** of two windows is the whole point: the 1h window confirms the burn is sustained (precision), while the 5m window makes the alert *resolve quickly* once you fix it (so it doesn't stay firing for an hour after recovery).
- `for: 2m` adds a small debounce against single-scrape blips. Use recording rules for the ratios so the alert expression stays readable and both the alert and the dashboard read the *same* precomputed number — avoiding the bug where the alert and the graph disagree.

#### Q36. [Theory] What are span attributes, span events, and span links — and when do you use each instead of the others?

All three attach extra information to a trace, but they model different things, and mixing them up produces unsearchable or misleading traces:

- **Attributes** are key-value pairs describing the span as a whole: `http.method=POST`, `db.statement`, `user.tier=gold`. They're for *dimensions you'll filter/group by*. Following **semantic conventions** (OTel's standard attribute names) is what lets a backend auto-render DB/HTTP/messaging panels.
- **Span events** are timestamped points *within* a span — "cache miss at t+3ms", "retry attempt 2 at t+50ms", or an exception (`span.recordException` is literally a special event with a stack trace). Use an event when *when-it-happened-inside-the-span* matters but it doesn't deserve its own child span.
- **Span links** connect a span to *other* spans in a *different* trace (or a different part of the same trace) without a parent-child relationship. The canonical use is **batching/fan-in**: one Kafka consumer span processes 100 messages produced by 100 different traces — you can't have 100 parents, so you attach 100 links. Also used to connect a re-driven/retried trace to the original.

```
Trace A ─┐
Trace B ─┼─[link]─► [ batch-consumer span ]   (fan-in, no single parent)
Trace C ─┘
   within that span:  • event: "polled 100 msgs"   • event: "flush at t+40ms"
   attributes on it:  messaging.system=kafka, batch.size=100
```

**Rule of thumb:** filterable dimension → attribute; moment-in-time inside one operation → event; relationship to a *different* trace/causal chain → link.

#### Q37. [Practical] Your alerts are too noisy and on-call is suffering fatigue. How do you fix it systematically?

Alert fatigue is dangerous because it trains responders to ignore pages, so the real outage gets missed. I'd attack it as a measurable engineering problem, not by ad-hoc muting:

1. **Measure the noise:** pull alert volume per rule and the *actionability rate* (how often a page led to a real action vs. self-resolved/ack-and-ignore). The Pareto principle holds — a handful of rules generate most of the noise.
2. **Alert on symptoms, not causes.** Page on user-facing SLO burn ("checkout failing"), not on every underlying cause ("CPU 81%", "one pod restarted"). Cause-based metrics belong on dashboards for diagnosis, not on the pager.
3. **Kill non-actionable pages.** If a human can't do anything about it right now, it's a ticket or a dashboard, never a page. "Disk 70% full" is a ticket; "disk full in 4h at current rate" is a page.
4. **Add the right hysteresis:** `for:` durations and multi-window burn rates so a 30-second blip doesn't page. Group/inhibit related alerts in Alertmanager so one root cause = one page, not forty.
5. **Route by severity and ownership** so each page reaches the team that can act, and downstream-dependency failures are *inhibited* when the upstream cause is already firing.

```yaml
# Alertmanager: dedupe + inhibit downstream noise when upstream is firing
route:
  group_by: ['alertname', 'service']
  group_wait: 30s
  group_interval: 5m
inhibit_rules:
- source_matchers: [ severity="page", alertname="DatabaseDown" ]
  target_matchers: [ severity="page" ]
  equal: ['cluster']     # suppress dependent app pages while DB is down
```

**What I'd actually do:** institute a weekly alert review in the on-call handoff, delete or downgrade any rule with a low actionability rate, and require every new page to specify a runbook link and an action — no runbook, no page.

#### Q38. [Theory] What is OTLP, and what do "cumulative vs delta temporality" mean for metrics?

**OTLP** (OpenTelemetry Protocol) is the wire protocol that OTel SDKs and Collectors speak: a Protobuf schema carried over **gRPC** (default, port 4317) or **HTTP/protobuf** (port 4318). It's a single unified protocol for traces, metrics, and logs, which is why the Collector can be a universal receiver. It supports compression, batching, and retry, and it's the lingua franca that decouples instrumentation from any specific backend.

The subtle part is **temporality** for counter-like (sum) metrics:

- **Cumulative temporality** — each export reports the running total since process start (Prometheus's native model). A reset (process restart) shows the value dropping to a low number, and `rate()` is designed to detect and correct for that reset.
- **Delta temporality** — each export reports only the *change since the last export* (StatsD/Datadog-style). The collector or backend sums deltas to reconstruct totals.

```
Cumulative:  t1=100  t2=130  t3=175   (rate = diff / interval, reset-aware)
Delta:       t1=100  t2=30   t3=45    (already the per-interval change)
```

This matters in practice: **Prometheus expects cumulative**, so if you push delta-temporality OTLP metrics into a Prometheus backend you need the Collector's `cumulativetodelta`/`deltatocumulative` processor (depending on direction), or your rates will be nonsense. Delta is friendlier for short-lived/serverless functions (no long-running process to hold a cumulative total). Picking the right temporality for your backend, and converting in the Collector when they mismatch, is a real production gotcha.

#### Q39. [Practical] How do you propagate the trace ID into logs across thread pools and async boundaries in Java?

The goal is that every log line carries `trace_id`/`span_id` so you can pivot from a metric/trace to the exact logs. The mechanism is **MDC** (Mapped Diagnostic Context) — a thread-local map the logging encoder reads. With the OTel Java agent, MDC is auto-populated, but the moment work hops threads, the thread-local doesn't follow it.

```java
// 1) Logback pattern / JSON encoder includes the IDs the agent injects into MDC:
//    <pattern>%d %-5level [trace_id=%X{trace_id} span_id=%X{span_id}] %msg%n</pattern>

// 2) Manual MDC if not using the agent:
import io.opentelemetry.api.trace.Span;
var ctx = Span.current().getSpanContext();
MDC.put("trace_id", ctx.getTraceId());
MDC.put("span_id", ctx.getSpanId());
try {
    log.info("order_created");      // line now carries the IDs
} finally {
    MDC.clear();                    // CRITICAL: clear so a pooled thread doesn't leak IDs
}

// 3) Crossing a thread pool: capture and restore BOTH OTel Context and MDC.
ExecutorService pool = Context.taskWrapping(Executors.newFixedThreadPool(8)); // OTel ctx
Map<String,String> mdc = MDC.getCopyOfContextMap();                           // MDC snapshot
pool.submit(() -> {
    var prev = MDC.getCopyOfContextMap();
    if (mdc != null) MDC.setContextMap(mdc); else MDC.clear();
    try { doWork(); } finally {
        if (prev != null) MDC.setContextMap(prev); else MDC.clear();
    }
});
```

The two failure modes to call out: (1) **leakage** — a thread in a pool keeps the MDC of a *previous* request because nobody cleared it, so logs are mis-attributed to the wrong trace; always `clear()`/restore in a `finally`. (2) **loss** — the worker thread has *no* MDC because the submitting thread's thread-local didn't propagate, so logs lose their `trace_id`. `Context.taskWrapping` handles the OTel context; MDC needs its own snapshot/restore (or a decorator like `MdcTaskDecorator` in Spring). Reactive code (`Reactor`) needs the context written into the Reactor `Context` and bridged, because there's no stable carrier thread at all.

### 🟠 Advanced — extended

#### Q40. [Theory] How do you run Prometheus in high availability, and how is duplicate data deduplicated?

A single Prometheus is a single point of failure and is limited by one machine's memory/disk. The standard HA pattern is to run **two (or more) identical Prometheus replicas** scraping the same targets independently. They don't coordinate, so each has a slightly different, near-complete copy — which immediately raises *"now I have two copies, how do I query without double-counting?"*

- **Alertmanager handles alert dedup natively:** both replicas send the same alerts; Alertmanager deduplicates by the alert's label set, so you get one page, not two.
- **Query dedup needs a layer on top.** **Thanos** runs a sidecar next to each Prometheus and a **Querier** that fans out to all replicas and **deduplicates by an external `replica` label** at query time (picking the most complete series). **Cortex/Mimir** and **VictoriaMetrics** instead *ingest* from both replicas and dedupe on write.

```
target ──► Prometheus-A (replica=a) ─┐
       └─► Prometheus-B (replica=b) ─┤
                                     ├─► Thanos Querier  ──► dedup by replica label
                                     │      (fan-out + merge gaps)
                                     └─► long-term: object store via Thanos Store
```

Thanos/Mimir also solve the *second* HA problem — **durable long-term storage and global query** — by uploading TSDB blocks to object storage (S3/GCS) and querying them through a Store Gateway, with **downsampling** (5m and 1h resolutions) so multi-month queries stay fast. The trade-off is operational complexity: you've gone from one binary to a distributed system (sidecar, querier, store, compactor, object store), so you only adopt it when single-node limits or long retention genuinely demand it.

#### Q41. [Practical] How do you enforce a cardinality budget so a team can't accidentally OOM the shared TSDB?

Relying on goodwill doesn't scale — one team adding `user_id` as a label can take down everyone's monitoring. I enforce cardinality with layered guardrails, from soft to hard:

1. **Limits at the scrape config (hard cap):** Prometheus supports per-target sample and label limits that drop a target whose metrics explode, protecting the whole TSDB.

```yaml
scrape_configs:
- job_name: 'app'
  sample_limit: 50000          # drop the target if it exposes > 50k series
  label_limit: 30              # max labels per metric
  label_value_length_limit: 200
  metric_relabel_configs:
  - source_labels: [__name__]   # drop a known-bad high-cardinality metric outright
    regex: 'app_request_by_user_id.*'
    action: drop
```

2. **Relabeling to strip offenders** at ingest (drop `pod_template_hash`, normalize `path` to a route template, collapse churny labels) so the bad dimension never enters the index.
3. **Monitor cardinality itself:** alert on `prometheus_tsdb_head_series` growth rate and use `count by (__name__)(...)` / the `/status/tsdb` top-cardinality view to catch creep early.
4. **Shift-left in CI:** lint metric definitions in code review for unbounded labels, and provide a paved-road library that makes it hard to add a high-cardinality label by accident.
5. **Per-tenant limits** if you run a multi-tenant backend (Mimir/Cortex `max_global_series_per_user`), so one tenant's explosion is isolated.

**The principle:** make the cheap, correct behavior the default and the dangerous behavior *fail fast and loud* — a dropped target with a clear reason is far better than a silently OOMing TSDB that blinds everyone.

#### Q42. [Theory] Compare auto-instrumentation approaches: language agents, eBPF, and service mesh. What are the trade-offs?

There are three layers at which you can get telemetry "for free" without changing app code, and they see different things:

| Approach | Where it runs | Sees | Strengths | Limits |
|---|---|---|---|---|
| **Language agent** (OTel Java agent) | In-process (bytecode/monkeypatch) | App-level spans, DB/HTTP client calls, method timing | Rich, app-context-aware spans; full trace propagation | Per-language; runtime overhead; can't see what it doesn't hook |
| **eBPF** (Pixie, Beyla, Cilium) | Kernel | Syscalls, network flows, L7 protocols, no code change | Language-agnostic, zero app changes, low overhead | Limited app context; trace propagation across hops is hard; needs kernel access |
| **Service mesh** (Istio/Linkerd sidecar) | Network proxy (Envoy) | Service-to-service HTTP/gRPC, RED metrics | Uniform mesh-wide metrics + some tracing, no app changes | Only sees mesh traffic; can't see in-process detail; sidecar cost |

```
   in-process            network proxy           kernel
   ┌──────────┐          ┌──────────┐          ┌──────────┐
   │ JVM agent│ deep app │  Envoy   │ edge RED │   eBPF   │ syscalls/L7
   │  spans   │  context │ sidecar  │ metrics  │  probes  │ no code change
   └──────────┘          └──────────┘          └──────────┘
```

The expert nuance: **eBPF and mesh give you breadth (every service, no code) but shallow context** — they can tell you service A called B and it was slow, but not *which business operation* or propagate a coherent trace through async hops, because they can't inject/read context the app controls. **Language agents give depth** but are per-language and add JVM overhead. In practice you **layer them**: eBPF/mesh for baseline coverage and the long tail of un-instrumented services, language agents + manual spans where you need business-level detail and reliable propagation. eBPF is also increasingly used to *generate* RED metrics cheaply while letting you reserve sampled tracing for the interesting paths.

#### Q43. [Practical] A trace shows a 2-second gap between two spans with nothing in between. What are the likely causes and how do you confirm each?

A "white space" gap — time inside a parent span not accounted for by any child span — is one of the most common real tracing puzzles. The key insight is that *something happened that wasn't instrumented*. Candidate causes and how to confirm:

1. **GC pause / stop-the-world.** Confirm by overlaying JVM GC metrics (`jvm_gc_pause_seconds`) at the gap's timestamp; a 2s gap aligned with a full GC is the smoking gun. Fix: heap/GC tuning, reduce allocation on the hot path.
2. **Thread-pool / connection-pool queueing.** The work was *ready* but waiting for a thread or a DB connection. Confirm with pool saturation metrics (`active == max`, `pending > 0`) at that time. This is invisible to tracing unless you add a span/event around "acquire connection." Fix: size the pool, add a wait-time span attribute so it's visible next time.
3. **Uninstrumented blocking call** — an external call, disk I/O, a `sleep`, lock contention, or a library the agent doesn't auto-instrument. Confirm by adding a manual span around suspicious code or checking thread dumps / continuous profiling for where the thread was parked.
4. **Clock skew between hosts** can *fabricate* apparent gaps or overlaps if spans come from different machines with unsynchronized clocks. Confirm by checking NTP sync; the tell is gaps that don't reproduce when both spans are on the same host.

```
[ parent span: 0ms ──────────────────────────────── 2100ms ]
   [child A: 0–50ms]                         [child B: 2050–2100ms]
                    └──── 2000ms GAP ────┘   ← what happened here?
   confirm: GC pause? pool wait? uninstrumented I/O? clock skew?
```

**What I'd actually do:** correlate the gap window with GC and pool-saturation metrics first (cheapest), turn to **continuous profiling** (async-profiler/Pyroscope) to see exactly where the thread spent the 2s if metrics are inconclusive, then add a span or attribute so the cause is explicit in future traces rather than a mystery gap.

#### Q44. [Theory] What is "baggage" in OpenTelemetry, how does it differ from span attributes, and what's the risk?

**Baggage** is a set of key-value pairs that propagates *across the entire trace and across service boundaries* via the `baggage` header (alongside `traceparent`). Unlike a span attribute — which lives only on the span where you set it — baggage rides along the whole request so a *downstream* service can read a value set *upstream* without re-passing it through every API.

The canonical use: set `baggage: user.tier=enterprise` (or `tenant_id`, `is_synthetic=true`) at the edge gateway, and a service five hops deep can read it to make a decision (e.g., prioritize the request) or stamp it onto *its own* spans/metrics for slicing — without the intermediate services having to know or forward it.

```
edge sets baggage(user.tier=enterprise)
  └─► svc A ─► svc B ─► svc C   (C can read user.tier from baggage header)
     header on every hop:  baggage: user.tier=enterprise,tenant=acme
```

The differences and the **risks** are the whole point of the question:

- **Baggage is not automatically added to spans.** Setting baggage does *not* make it a searchable attribute — you must explicitly copy a baggage value onto a span if you want to filter by it. People conflate the two and wonder why their baggage key isn't queryable.
- **It costs bytes on every hop.** Baggage is serialized into headers on *every* outbound request in the trace, so large or numerous baggage entries add real network overhead and can blow past header size limits. Keep it tiny.
- **Security:** baggage propagates outward, potentially to third parties / external services that receive your `traceparent`/`baggage` headers. **Never put PII, secrets, or tokens in baggage** — it leaks across trust boundaries. Many setups strip baggage at the egress edge for exactly this reason.

#### Q45. [Practical] Design the metric naming and labeling conventions for a large multi-team platform. Why does consistency matter so much?

Inconsistent naming is a silent productivity tax: if team A emits `http_requests_total` and team B emits `requestCount`, you can't write one dashboard or alert across services, and cross-team aggregation is impossible. I'd standardize and enforce conventions, largely following Prometheus/OTel semantic conventions:

```
# Convention
<namespace>_<subsystem>_<name>_<unit>_<suffix>
http_server_request_duration_seconds   (histogram -> _bucket/_sum/_count)
http_server_requests_total             (counter -> _total suffix)
process_resident_memory_bytes          (gauge, base unit)

# Rules
- Base units only: seconds (not ms), bytes (not MB). Backends scale for display.
- _total suffix for counters; _seconds/_bytes unit suffix; no plurals chaos.
- Labels: low-cardinality, bounded values only (method, code, route TEMPLATE).
- Shared label keys mean the SAME thing everywhere: `service`, `env`, `region`.
- NEVER: user_id, order_id, full URL, email, error message as a label.
```

Why it matters beyond aesthetics:

1. **Portability of dashboards and alerts** — a single RED dashboard template works for every service because the metric/label names are identical. New services light up automatically.
2. **Correlation across signals** — if `service` and `region` label keys are consistent across metrics, logs (as fields), and trace attributes, you can pivot between pillars seamlessly. Inconsistent keys break that join.
3. **Cardinality governance** — a convention that forbids unbounded label keys is your first line of defense against cardinality explosions, and it's lintable in CI.
4. **Onboarding and tooling** — engineers and automated tooling can predict metric names, and recording/alerting rules can be templated.

**What I'd actually do:** publish the convention as a linted spec, ship it baked into the paved-road instrumentation library (so the default counter is *already* named correctly), and run a periodic audit that flags metrics violating the schema — making the convention the path of least resistance rather than a document nobody reads.

### 🔴 Expert — extended

#### Q46. [Theory] Make the case for continuous profiling as a "fourth pillar." How does it complement traces, and what does it add?

Metrics/logs/traces tell you *that* a request was slow and *which span* consumed the time, but often not *why within the process* — a 2s span "doing CPU work" doesn't say which function burned the cycles. **Continuous profiling** fills that gap: it samples stack traces across the whole fleet continuously (via async-profiler, eBPF, or `pprof`), aggregating into flame graphs that show where CPU, memory allocation, lock contention, or off-CPU time actually goes — in production, all the time, at ~1–2% overhead.

```
Trace:    [ checkout span 2.0s ]  ← WHERE (which span)
Profile:    └─ flamegraph: 70% in JSON serialization, 20% in regex compile
                                  ← WHY (which code, line-level)
```

The powerful integration is **trace-to-profile linking**: modern stacks (Grafana Pyroscope/Tempo, Datadog, Polar Signals) attach a profiling context so you can click a slow span and see the *flame graph for exactly that span's execution* — closing the loop from "this span is slow" to "this line of code is the bottleneck." This is the missing rung between trace-level and code-level diagnosis.

What it adds that the other three can't:

- **Code-level attribution without pre-instrumentation** — you didn't have to add a span around the hot function; the profiler found it. It's the answer to "uninstrumented gap" problems (see the white-space gap question).
- **Whole-fleet always-on**, so you can profile a regression *after the fact* by comparing flame graphs across deploys, rather than reproducing it.
- **Resource efficiency / cost** insights — find the function burning the most CPU across the fleet and you've found money. The expert framing: continuous profiling turns "we think it's CPU-bound" into a ranked, line-level list of where to spend optimization effort, and it's becoming a first-class signal in OTel (the profiling signal) precisely because the other three leave this blind spot.

#### Q47. [Practical] Telemetry collection is adding measurable latency and CPU to a hot-path service. How do you reduce observability overhead without going blind?

Observability is not free, and on a hot path the instrumentation itself can become the bottleneck. I'd profile the overhead first (ironically, with profiling) to find the actual cost, then attack it in order of impact:

1. **Export off the request path, always.** A synchronous/blocking exporter on the hot path is a latency landmine. Use the **batch span/log processor** so export happens on a background thread with bounded queues; if the queue fills, drop with a counter rather than block the request. Same for metrics — scrape/push asynchronously.
2. **Sample at the source.** Don't create spans you'll throw away. Head-sample at the SDK so hot paths generate a fraction of spans, and reserve full fidelity (tail sampling) for errors/slow. Span *creation + attribute boxing* is the cost, so not creating the span is the biggest win.
3. **Trim attributes.** Avoid expensive or large attributes on hot spans — no full request/response bodies, no eager string concatenation, no reflection. Compute attributes lazily and only when the span is actually sampled.
4. **Reduce log volume on the hot path.** Drop or sample DEBUG/INFO, prefer counters over per-event logs for things you only need aggregates of ("logged 1M times to count" → one counter).
5. **Push processing to the Collector.** Do enrichment, redaction, and tail-sampling in the Collector (off-box) rather than in-process, so the app does the minimum.

```
Hot path budget: keep telemetry CPU < ~2-3%, zero blocking on export.
 create span ─► (sampled? yes) ─► cheap attrs ─► batch queue ──async──► Collector
                (sampled? no)  ─► no-op, ~0 cost                         (heavy work here)
```

**The key trade-off to articulate:** you trade *completeness* for *low overhead and cost* — but done right you lose almost nothing that matters, because sampling preserves errors and slow traces (the interesting ones), and aggregates (metrics) are exact regardless of sampling. The failure mode to avoid is overreacting and ripping out instrumentation wholesale, which makes the next incident undebuggable; the discipline is *surgical* reduction guided by measured overhead.

#### Q48. [Theory] How do `staleness`, `absent()`, and missing-data handling work in Prometheus, and why do they cause subtle alerting bugs?

A whole class of production incidents is *invisible* because the problem is **absence of data**, not bad data — and naive alerting rules only check data that exists. Prometheus has specific mechanics here:

- **Staleness:** when a target disappears or stops exposing a series, Prometheus marks it stale and the series stops returning values after a staleness window (~5 minutes / a few scrape intervals). This is good — it prevents a dead instance's last value from lingering forever — but it means a series can simply *vanish* from query results.
- **The subtle bug:** an alert like `rate(http_errors_total[5m]) / rate(http_requests_total[5m]) > 0.05` **stops evaluating to anything** if the service dies and emits *no* requests — the denominator goes stale, the expression returns *empty*, and the alert silently **resolves** exactly when the service is hardest down. You alerted on a high *ratio*, but a dead service has *no ratio at all*.

```
Service healthy:  errors/requests = 0.01   (alert OK)
Service dies:     no series at all -> expression empty -> alert RESOLVES (!)
   You think it's fine. It's actually 100% down.
```

The fixes, which a senior engineer should name:

- **`absent()` / `absent_over_time()`** to alert specifically on *missing* series: `absent(up{job="checkout"} == 1)` fires when the target stops reporting — the **dead-man's switch**.
- **Alert on `up == 0`** for target-down, independent of the ratio alert.
- **Use `or vector(0)`** to backfill a default when a series may be absent, so ratios don't silently disappear.
- **`for:` interacts with staleness** — if a series goes stale mid-`for`-window, the pending alert can reset; account for it.

The deeper lesson, tying back to "monitoring the monitor": **the most dangerous failure is silence**, and a robust alerting strategy explicitly tests for *absence* of expected data, not just bad values — every critical pipeline needs a heartbeat/dead-man's-switch alert that fires when the data stops.

#### Q49. [Theory] Contrast synthetic monitoring, RUM, and black-box vs white-box monitoring. Where does each belong in an SLO strategy?

These answer "are we measuring what the *user* experiences, or what the *system internally* reports?" — and conflating them produces SLOs that look green while users suffer:

- **White-box monitoring** — signals from *inside* the system: app metrics, traces, logs, JVM internals. Rich and causal (tells you *why*), but it only sees what the system itself reports, and a system can be "healthy" internally while the user can't reach it (DNS, LB, CDN, TLS failures are invisible to white-box).
- **Black-box monitoring** — probing the system *from the outside* as a user would, with no internal knowledge. Tells you *symptoms* the user feels, catches the whole-path failures white-box misses, but doesn't tell you *why*.
- **Synthetic monitoring** — scripted black-box probes running on a schedule from multiple locations ("every minute, log in and complete a checkout from 5 regions"). Gives you *consistent, comparable, baseline* availability/latency even at 3am with no real traffic, and catches regressions before users do. The catch: synthetic traffic isn't real load and can't cover every real-world combination.
- **RUM (Real User Monitoring)** — instrumentation in the *actual user's browser/app* (page load, Core Web Vitals, JS errors, real geographic/device distribution). It's the ground truth of real experience including the last mile (their network, their device), but only exists *when users are present* and is noisy/variable.

```
            inside ◄───────────────────────────────► outside
   white-box (metrics/traces)        black-box (probes)
        │  why / causal                   │  symptom / user-felt
        │                          ┌──────┴───────┐
        │                       synthetic        RUM
        │                    (scheduled, 3am    (real users,
        │                     baseline, pre-     real last mile,
        └── no last mile       regression)        only w/ traffic)
```

**Where each belongs in SLOs:** Define the *customer-facing* SLO on what the user feels — ideally **RUM or synthetic** at the edge (e.g., "99.9% of real checkouts complete < 2s"), because that's what the SLA protects. Use **synthetic** for the always-on baseline and to keep the SLO meaningful during low traffic and to test the full external path (DNS/CDN/TLS). Use **white-box** signals to *diagnose and to drive internal SLIs and burn-rate alerts*. The expert point: an SLO measured *only* white-box (server-side success rate) can be 100% green while RUM shows users failing at the CDN — so the **primary user SLO should be measured as close to the user as possible**, with white-box as the diagnostic layer beneath it.

#### Q50. [Practical] You're consolidating three teams onto a shared observability platform and they disagree on metrics vs structured-events. How do you architect it to satisfy both and avoid double instrumentation?

This is the real-world version of the "metrics vs wide events" debate, with the added constraint of *not paying to instrument everything twice*. The architecture I'd push for is **emit rich data once, derive cheap signals from it** — using OTel as the single instrumentation surface and the Collector as the transformation hub:

1. **Single instrumentation surface (OTel):** every team instruments once against the OTel API — spans with rich attributes (the "wide event" the events camp wants). No team writes both a span *and* a separate hand-rolled metric for the same operation.
2. **Derive metrics from spans in the Collector** with the **`spanmetrics`/`connector`** processor: it generates RED metrics (rate/error/duration histograms) automatically from the span stream. The metrics camp gets cheap, low-cardinality, aggregatable metrics for SLOs and alerting *for free*, with no extra app code.
3. **Route by purpose:** the Collector fans out — low-cardinality derived metrics → Prometheus/Mimir (alerting, SLOs, dashboards, long retention cheap); full high-cardinality spans/wide events → a columnar trace/event store (Tempo/Honeycomb/ClickHouse) with **tail sampling** so you keep 100% of errors and a baseline of successes for exploratory analysis.

```
            ┌──────────────► Prometheus/Mimir  (SLOs, alerts, dashboards)
 OTel spans │  spanmetrics      low-cardinality, cheap, exact aggregates
 (rich,  ───┤  connector
 wide)      │  tail sample
            └──────────────► Tempo/ClickHouse  (debugging, arbitrary slicing)
                               high-cardinality, sampled, raw events
   ONE instrumentation ──► TWO consumption models, no double-instrument
```

This dissolves the disagreement because it's **not either/or**: alerting/SLOs are driven from cheap deterministic metrics (the metrics camp is right that you must *not* alert off sampled high-cardinality data), while debugging unknown-unknowns is driven from wide events (the events camp is right that pre-aggregated metrics can't answer novel questions). The trade-offs to be explicit about: derived span-metrics depend on sampling being *before* the metric derivation point (derive metrics from the *unsampled* stream, sample *after*, or your rates are wrong); cardinality of derived metrics must still be governed (don't let span attributes leak into metric labels); and you need governance on which span attributes are promoted to metric dimensions.

**What I'd actually do:** mandate OTel + a gateway Collector, turn on the spanmetrics connector for RED on every service, derive metrics from the *full* span stream and tail-sample *downstream*, and give each team the consumption model they prefer on top of one shared pipeline — ending the religious debate with an architecture rather than a vote.

#### Q51. [Theory] What is the Prometheus exposition format / OpenMetrics, and what does a `/metrics` endpoint actually return?

Prometheus's pull model means each target exposes a plain-text endpoint (`/metrics`) in the **Prometheus exposition format**, standardized and extended as **OpenMetrics** (a CNCF standard). Knowing what it looks like demystifies a lot of debugging:

```
# HELP http_requests_total Total HTTP requests.
# TYPE http_requests_total counter
http_requests_total{method="GET",code="200"} 10247
http_requests_total{method="GET",code="500"} 13
# HELP http_latency_seconds Request latency.
# TYPE http_latency_seconds histogram
http_latency_seconds_bucket{le="0.1"} 9800
http_latency_seconds_bucket{le="0.5"} 10230
http_latency_seconds_bucket{le="+Inf"} 10260
http_latency_seconds_sum 612.4
http_latency_seconds_count 10260
```

Each line is `metric_name{labels} value [timestamp]`. The `# TYPE` and `# HELP` comments give the metric kind and documentation. A histogram, as discussed, is *expanded* into many `_bucket`/`_sum`/`_count` lines — which is why a single histogram with bad bucket choices and a high-cardinality label can produce thousands of lines.

OpenMetrics adds a few important things over the original format: a formal spec, **exemplars** (a trailing `# {trace_id="..."} <value> <timestamp>` appended to a bucket line — the literal mechanism behind metric-to-trace links), explicit `_total` counter suffixes, and a `# EOF` terminator. Practically: you debug instrumentation by `curl`-ing `/metrics` directly to see exactly what an instance exposes; you spot cardinality problems by counting lines (`curl -s :8080/metrics | wc -l`); and you confirm exemplar support by looking for the `# {...}` suffix on bucket lines. The exposition format being human-readable plain text is a deliberate design choice that makes the whole pull model debuggable by hand.

#### Q52. [Practical] How do you design a dashboard that's actually useful during an incident (not a wall of 80 graphs)?

A dashboard crammed with every metric is useless under pressure — the responder can't find signal. I design for the **inverted pyramid / drill-down** model that matches how you actually debug:

```
TOP:    SLO + Golden Signals  (Is something wrong? How bad? Burning budget?)
          └─ Rate | Errors | Duration(p50/p95/p99) | Saturation   — 4-6 panels
MIDDLE: Scoping rows  (Where? slice by route / region / version / dependency)
          └─ errors by version, latency by route, dependency health
BOTTOM: Diagnosis  (links out to traces via exemplars, logs by trace_id, USE per node)
```

Concrete principles:

1. **Top row answers "should I care?"** — the SLO/error-budget status and the four golden signals, nothing else. A responder should know within 5 seconds if there's a problem and roughly where.
2. **Method-driven layout:** one **RED** row per service, **USE** rows per resource. Consistency across dashboards means muscle memory — every service dashboard looks the same.
3. **Annotate deploys and incidents** on the time axis (deploy markers), so "it broke right after a deploy" is visible instantly — the single highest-value correlation in practice.
4. **Make it drillable, not exhaustive:** use template variables (`$service`, `$region`) instead of duplicating dashboards, and link panels out to traces (exemplars) and logs (`trace_id`) so the dashboard is a *launchpad*, not the final destination.
5. **Percentiles not averages** on latency panels; **show the SLO threshold as a line** so "are we breaching?" is visual.

**What I'd actually do:** keep dashboards as code (Grafana JSON in Git / Terraform / Grafonnet) so they're reviewed, versioned, and templated from a standard library — the paved-road dashboard ships with every new service. The anti-patterns I explicitly avoid: averages hiding tails, raw counters instead of `rate()`, per-instance graphs that don't aggregate, and "vanity" panels nobody looks at during an incident but that slow the page load and the eye.

#### Q53. [Theory] What is `ParentBased` sampling in OpenTelemetry, and why is it the recommended default over a raw ratio sampler?

A naive ratio sampler (like the one in Q22) decides independently per span. Used directly on *every* service, that re-rolls the dice at each hop, which can keep a span but drop its parent or child — producing **broken, partial traces**. OTel's solution is the **`ParentBased`** sampler, a wrapper that says: *respect the decision already made upstream, and only roll the dice at the root.*

```
ParentBased(root = TraceIdRatioBased(0.10)):
  - if there IS a parent context (incoming traceparent):
        sampled flag in traceparent? -> KEEP all children
        not sampled?                 -> DROP all children
  - if there is NO parent (this is the root span):
        run TraceIdRatioBased(0.10) to make the ONE decision for the whole trace
```

So the *root* service makes a single decision (deterministically, by hashing the trace ID), encodes it in the `traceparent` sampled flag, and every downstream service simply obeys via `ParentBased`. The result is **complete traces** — either the whole trace is kept or the whole trace is dropped — with the decision made once and propagated, exactly the consistency property head sampling needs.

The trade-offs and gotchas: it means a downstream service *cannot* unilaterally decide to sample more (it's bound by the root's flag), which is usually what you want but surprises people who set a high ratio on a deep service and see nothing. It also means the *edge/root* service's sampler config is what actually governs volume — configuring sampling on internal services has little effect. And it's still **head-based**, so it shares head sampling's blind spot (you may drop the error you wanted); that's why mature setups pair `ParentBased` head sampling at the SDK with **tail sampling in the Collector** to guarantee errors/slow traces survive regardless of the head decision. `ParentBased(AlwaysOn)` for a debug/forced path is the common override hook.

#### Q54. [Practical] At very high log volume, how do you keep logs useful and affordable — sampling, dedup, aggregation?

Beyond a certain volume, "keep every log line" is neither affordable nor useful — the signal drowns. The strategy is to reduce volume *intelligently* so you keep what aids debugging and drop what's redundant:

1. **Convert high-frequency logs to metrics.** If you only ever count or rate a log event ("request received"), that's a counter, not a log line. One counter replaces millions of lines at a fraction of the cost. Reserve logs for events you need the *detail* of.
2. **Sample success, keep failures.** Like tail sampling for traces: keep 100% of ERROR/WARN, sample INFO at, say, 1–10%. Crucially, sample **by trace_id** so that if you keep a request you keep *all* its log lines (a coherent story), not random disconnected lines. Consistent sampling across signals means a kept trace has kept logs.
3. **Deduplicate / aggregate repetitive lines.** A tight loop logging the same error 10,000 times should collapse to "this error occurred 10,000 times in 1m" with one exemplar — done in the logging library (rate-limited/dedup appenders) or in the Collector/Fluent Bit. This also protects you from a log-storm DoS-ing your own pipeline.
4. **Tier storage by value and age.** Hot/searchable for recent (7–14d), warm/cold object storage for older, with shorter retention on low-value sources. Loki-style "index labels, store body cheaply" makes this dramatically cheaper than full-text-indexing everything.
5. **Drop/redact centrally in the Collector** so volume and PII policy are controlled in one audited place, not re-litigated in every service.

```
firehose ─► [ to metrics? ] ─► counter (cheapest)
            [ ERROR/WARN  ] ─► keep 100%
            [ INFO/DEBUG  ] ─► sample by trace_id (coherent)
            [ repeated     ] ─► dedup: "x10000 + 1 exemplar"
            [ old/low-value] ─► tiered/cheap store, short retention
```

**The trade-off to state explicitly:** you sacrifice *exhaustive* per-event history for affordability — acceptable because consistent trace-id sampling preserves whole-request stories where it matters, errors are kept in full, and aggregates are exact. The danger is *blind* sampling that drops the one line explaining an incident; the discipline is *biased* sampling (keep anomalies, sample the boring) plus dedup so cost scales sub-linearly with traffic.

#### Q55. [Theory] How do OpenTelemetry logs differ from traditional logging, and what is the "log-trace correlation" the log signal enables?

Traditionally, logs evolved in a silo: an app writes to a file/stdout, a shipper (Fluentd/Logstash) parses and forwards, and logs live in a separate system (ELK/Loki) with no inherent connection to traces or metrics. OTel's **logs signal** reframes logs as *first-class telemetry* in the same pipeline as traces and metrics, with a shared data model and shared context.

The key differences:

- **Structured by design, not by parsing.** OTel log records are structured records with severity, body, and attributes — you don't reverse-engineer fields from free text with brittle regex at ingest time.
- **Automatic trace context.** Because logs flow through the same SDK/Context, each log record can be **automatically stamped with the active `trace_id` and `span_id`** — no manual MDC plumbing. This is the correlation payoff: in the backend you click a span and see *exactly* the log lines emitted during that span's execution, and vice versa.
- **Unified pipeline and processing.** Logs go through the **same Collector** as traces/metrics, so the same batching, tail-sampling-aware routing, redaction, and backend-routing policies apply. You can even *correlate sampling decisions* — keep the logs for traces you kept.
- **Bridge, don't rewrite.** OTel doesn't ask you to throw out SLF4J/Logback/Log4j; it provides **appenders/bridges** so your existing logging API emits OTel log records under the hood. Existing code keeps working; the records gain context and flow through OTLP.

```
Traditional:  app ─► file ─► Fluentd(parse) ─► Elasticsearch   (siloed, no trace link)
OTel logs:    app(SLF4J→OTel bridge) ─► SDK(adds trace_id/span_id) ─► OTLP ─► Collector
                                                                          ├─► log backend
              click span ◄──── trace_id ────► its exact log lines        └─► routing/redact
```

The expert point: the value isn't "another log format," it's **automatic, reliable correlation across all three pillars through shared context** — the historically hardest part (stitching logs to the right request across services) becomes free, which is the entire premise of "correlated observability." The trade-off is migration effort and that not all backends fully consume the OTel log model yet, so many shops adopt it incrementally via the bridge while keeping their existing log store.

#### Q56. [Practical] How do you measure and drive down MTTD and MTTR, and what role does observability play in each?

**MTTD** (Mean Time To Detect) and **MTTR** (Mean Time To Resolve/Recover/Repair — be precise which) are the outcome metrics that tell you whether your observability *investment actually works*. Dashboards nobody uses don't move them; I treat them as the KPIs of the observability program.

```
 incident timeline:
 |── fault begins ──|── DETECT ──|── DIAGNOSE ──|── MITIGATE ──|── RESOLVE ──|
        MTTD = fault→detect          MTTR (recover) = detect→mitigate
                                     MTTR (repair)  = detect→full resolution
```

**Driving down MTTD (detection):**

- **Symptom-based SLO burn-rate alerts** detect user-impacting problems fast and precisely; multi-window catches both fast and slow burns.
- **Dead-man's-switch / `absent()` alerts** so *silence* is detected (the failure mode naive alerting misses).
- **Synthetic probes** detect issues during low-traffic periods before real users do.
- Measure MTTD honestly as "fault start → first accurate page," and review every incident where users noticed before the alert did — that's an instrumentation gap.

**Driving down MTTR (diagnosis + mitigation):**

- The **metrics→traces→logs correlation chain** (exemplars, trace_id-stamped logs, trace-to-profile links) is the single biggest MTTR lever — it turns "hours of grep" into clicks.
- **Deploy annotations** and version-sliced metrics make "what changed?" instant — most incidents are change-induced, so fast rollback dominates.
- **Runbooks linked from alerts** and **paved-road dashboards** mean even a junior on-call follows the diagnosis path.
- **Game days** validate the path works before a real incident.

**What I'd actually do:** track MTTD/MTTR per incident in the postmortem, attribute each incident's time to *detect / diagnose / mitigate* phases, and target the phase that dominates — if diagnosis dominates, invest in correlation and tracing coverage; if detection dominates, fix alerting gaps. Every blameless postmortem asks "what observability gap added time here?" and produces a concrete instrumentation/alert action item, so the program compounds. The honest caveat: MTTR is a noisy, long-tailed metric (one giant incident skews the mean), so I track the *distribution* and the per-phase breakdown, not just the headline mean.

#### Q57. [Theory] How do scrape interval, evaluation interval, and `rate()` window interact, and what bugs come from getting them wrong?

These three timing knobs interact in ways that silently corrupt graphs and alerts if misconfigured:

- **Scrape interval** — how often Prometheus pulls `/metrics` (commonly 15–60s). This is the *resolution* of your data; you can't see anything finer-grained than this.
- **Evaluation interval** — how often recording/alerting rules run (often equal to scrape interval).
- **`rate()`/`increase()` range window** — the lookback over which you compute per-second rates (e.g., `rate(...[5m])`).

The governing rule is **the range window must be at least ~4× the scrape interval** (Prometheus needs ≥2 samples, and the rule of thumb is 4× for stability). Violations cause specific, well-known bugs:

```
scrape = 60s,  rate(...[1m])  ->  often only 1 sample in window  ->  GAPS / empty
scrape = 15s,  rate(...[5m])  ->  healthy (≈20 samples)          ->  smooth
```

1. **Window too short relative to scrape → gaps and flapping.** With one sample in the window, `rate()` returns nothing intermittently, so graphs have holes and alerts flap on/off.
2. **Window too long → sluggish, smoothed-over signal.** A `rate(...[1h])` smears a spike across an hour, so a sharp 2-minute error burst barely moves the line and your alert detects it slowly (this is also why burn-rate alerts use *paired* short+long windows).
3. **Aliasing / missed spikes.** A 60s scrape can completely miss a 10-second saturation spike — for fast-moving signals (queue depth, GC) you need a finer scrape interval or histograms that *accumulate* between scrapes rather than gauges that *sample*.
4. **`increase()` extrapolation surprises** — Prometheus extrapolates to window edges, so `increase()` over a short window on a slow counter can report non-integer or surprising values; understand it's an estimate.

**The senior framing:** choose scrape interval to match the *fastest signal you must catch* (balanced against cardinality/storage cost — finer scrape = more samples = more storage), keep `rate()` windows ≥4× that, and use **paired multi-window** logic for alerts so you get both fast detection and stable precision. Mismatched intervals are a top cause of "why is my graph empty / why did my alert flap" tickets.

#### Q58. [Practical] How would you debug "traces are broken/incomplete" — spans missing, disconnected, or with the wrong parent?

Broken traces are a common, frustrating failure. I work through the propagation chain systematically because the cause is almost always a *context break* at one specific hop:

1. **Is the trace context even on the wire?** Inspect the actual `traceparent` header on the inter-service request (curl/proxy logs / a captured request). If it's absent or malformed, propagation isn't happening — the client isn't *injecting*. Confirm both sides agree on the propagator (W3C `tracecontext` vs legacy B3/Jaeger) — a propagator **mismatch** (one service emits B3, the next expects W3C) silently breaks the chain.
2. **Async boundary dropping context** (the most common cause): work hopped to a thread pool / reactive callback / message queue and the `Context` wasn't captured and restored, so the child span has no parent or a wrong one. Confirm by checking whether breakage correlates with a known async hop; fix with `Context.taskWrapping` / explicit propagation / message-header injection (see Q39, Q24).
3. **Sampling inconsistency** producing *partial* traces — one service kept a span, another dropped it. Confirm you're using `ParentBased` sampling everywhere (Q53); a raw ratio sampler on internal services is the classic culprit for "some spans missing from otherwise complete traces."
4. **Unended or never-exported spans** — a span that never calls `end()` (forgotten in an async callback or on an exception path without `finally`) never exports, leaving a hole; check for "span leaked" warnings and verify all `end()`s are in `finally`.
5. **Clock skew** making spans appear in the wrong order / negative durations / impossible overlaps — confirm NTP across hosts.
6. **Collector/export issues** — spans dropped due to a full batch queue, exporter backpressure, or tail-sampling dropping part of a trace because not all spans reached the *same* collector (need trace-id-aware load balancing, Q18). Check Collector queue/refused metrics.

```
Inject? ──► header present & valid (W3C vs B3 match)?
   └─► Extract? ──► async hop preserved Context? (taskWrapping / msg headers)
          └─► Sampling consistent? (ParentBased) 
                 └─► All spans end()ed & exported? (no leaks, queue not full)
                        └─► Clocks NTP-synced? Tail-sampler got all spans? (LB by trace_id)
```

**What I'd actually do:** start by capturing one real broken trace and one real `traceparent` header (cheapest, most diagnostic), localize *which hop* drops it, then map that hop to the categories above — almost always it's an async boundary or a propagator/sampler mismatch. The prevention is the paved road: trace propagation baked into the shared HTTP/gRPC/Kafka client so no team can forget it.

#### Q59. [Theory] How does distributed tracing work over gRPC and streaming RPCs, and why is streaming trickier than unary HTTP?

For **unary** calls (one request → one response), tracing maps cleanly: a CLIENT span wraps the call, context is injected into gRPC **metadata** (gRPC's header mechanism — the OTel propagator writes `traceparent` into metadata), the server extracts it and creates a SERVER span as a child. gRPC's auto-instrumentation handles this, and the semantic conventions (`rpc.system=grpc`, `rpc.service`, `rpc.method`, status codes) make backends render it natively. So far it's just like HTTP.

**Streaming** RPCs break the clean "one span = one operation" model because a single RPC carries *many* messages over a long-lived stream:

```
Unary:   [CLIENT span] ──req──► [SERVER span] ──resp──►   (1:1, easy)

Streaming (server/bidi):
  [CLIENT span: lifetime of stream ─────────────────────────────]
      │ context injected ONCE at stream open (in initial metadata)
      ▼
  [SERVER span: stream lifetime ────────────────────────────────]
      • event: msg1   • event: msg5   • event: msg5000   ...
      (each message is NOT its own child of the original parent)
```

The trickiness, and what a senior should articulate:

- **Context propagates once, at stream initiation** (in the initial metadata), not per message. So all messages share the one stream's span context — you can't naturally give each message a parent-child link back to a *per-message* originating trace.
- **The span lifetime is the whole stream**, which may be minutes or hours. A span that long is awkward: it holds memory, exports late, and a single duration is meaningless for a long-lived stream. You typically model per-message work as **span events** within the stream span, or create **short child spans per message** linked via **span links** (because the true causal origin of message N may be a *different* trace than the stream-open).
- **Fan-in/fan-out and bidi** mean one stream span relates to many logical operations — again **span links**, not parent-child, are the right model (same reasoning as Kafka batch consumers in Q36).
- **Cardinality/volume:** a high-throughput stream emitting a span or event per message can explode telemetry volume, so per-message instrumentation must be sampled.

The takeaway: unary RPC tracing is a solved, automatic problem; **streaming requires you to decide the unit of work** (the stream vs each message), use **events or linked child spans** rather than forcing everything under one parent, and **sample per-message** instrumentation — because the request/response assumption that distributed tracing was built around no longer holds.

#### Q60. [Practical] Finance asks "which team/feature is driving our observability cost?" — how do you build cost attribution and accountability?

You can't control or fairly allocate a cost you can't attribute, and a shared observability bill with no ownership inevitably creeps (tragedy of the commons). I'd build **cost attribution as a first-class telemetry use case**:

1. **Tag everything with an ownership dimension.** Enforce a mandatory low-cardinality `team`/`service`/`cost_center` label/attribute on metrics, logs, and traces (via the paved-road library and Collector enrichment, so it can't be omitted). This is the join key for all cost reporting.
2. **Measure consumption per signal per owner.** Metrics: active series per team (`count by (team)(...)` / TSDB stats). Logs: ingested GB per service (most backends expose ingestion volume by label). Traces: spans/GB ingested and *sampled-kept* per service. Many vendors expose a usage/metering API; if self-hosted, the Collector can count bytes/spans per `team` attribute.
3. **Map consumption to dollars.** Apply the backend's pricing model (per-series, per-GB, per-host, per-span) to each team's consumption to produce a **cost-per-team scorecard**. Pricing model matters enormously — a per-host model rewards different behavior than per-GB, so model it accurately.

```
 telemetry ──(team=payments)──► Collector counts bytes/series/spans by team
        │
        ▼
  usage by team ──× pricing model──►  cost scorecard
   payments  42% series, 18% log GB  ->  $X/mo   (←high-cardinality offender?)
   search    11% ...                  ->  $Y/mo
```

4. **Create accountability and feedback.** Publish the scorecard to teams (showback), and where the org supports it, **chargeback** so cost hits the owning team's budget — this is what actually changes behavior. Set **guardrails**: per-team cardinality budgets, default retention, and alerts when a team's consumption spikes (which also catches accidental cardinality explosions early — a cost spike and a stability risk are the same event).
5. **Find the Pareto offenders and act:** the scorecard almost always shows a few teams/signals driving most spend (an unused high-cardinality metric, debug logs left on in prod). Drive those down with the owning team rather than imposing blanket cuts.

**What I'd actually do:** make `team` a mandatory enforced dimension, stand up a usage→cost pipeline that produces a monthly per-team scorecard, move to showback (then chargeback if culturally feasible), and pair it with cardinality/retention guardrails so cost stays attributed *and* bounded. The leadership framing: this turns "the observability bill is too high" from a central platform headache into distributed ownership where the teams who create cost can see and control it — and it doubles as an early-warning system for cardinality incidents.

## 🧩 Extended Questions — Set 2: Coding, Design & Expert Scenarios

### 🟢 Basic — extended

#### Q61. [Coding] Bootstrap the OpenTelemetry SDK in Java with a `BatchSpanProcessor`, OTLP exporter, and a `Resource`. Why each piece?

The single most common "first OTel program" mistake is wiring an exporter directly to a `SimpleSpanProcessor` (synchronous, exports on every `span.end()`) on a real service — it blocks the request thread on network I/O. The production-correct bootstrap uses a **`BatchSpanProcessor`** (background thread, bounded queue) and a properly populated **`Resource`** so every span is tagged with `service.name`/`service.version`/`deployment.environment` — without `service.name`, your spans are unattributable in the backend.

```java
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import java.time.Duration;

public final class Otel {
    public static OpenTelemetry init() {
        Resource resource = Resource.getDefault().merge(Resource.create(Attributes.builder()
                .put("service.name", "orders")              // REQUIRED for attribution
                .put("service.version", "2.3.1")
                .put("deployment.environment", "prod")
                .build()));

        OtlpGrpcSpanExporter exporter = OtlpGrpcSpanExporter.builder()
                .setEndpoint("http://otel-collector:4317")   // ship to Collector, not vendor
                .setTimeout(Duration.ofSeconds(10))
                .build();

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .setResource(resource)
                .addSpanProcessor(BatchSpanProcessor.builder(exporter)
                        .setMaxQueueSize(2048)               // drop (count it) rather than block
                        .setMaxExportBatchSize(512)
                        .setScheduleDelay(Duration.ofSeconds(5))
                        .build())
                .build();

        return OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                // W3C so traceparent interops with everyone; add B3 only for legacy peers.
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .buildAndRegisterGlobal();   // registers GlobalOpenTelemetry for libraries
    }
}
```

- **Why `BatchSpanProcessor`?** It decouples span export from the request path: spans queue and a background thread exports in batches. The bounded `maxQueueSize` means under overload you *drop* spans (incrementing a dropped-span counter) instead of blocking business logic — exactly the "telemetry must never take down the app" principle.
- **Why ship to a Collector, not the vendor directly?** Decoupling (Q10/Q28) — backend changes, redaction, and tail sampling become a Collector config edit, not an app redeploy.
- **Edge cases:** call `tracerProvider.close()`/`shutdown()` on JVM shutdown so the final batch flushes (otherwise you lose the last few seconds of spans); set the propagator explicitly — relying on the default has burned people when a peer expected B3. In real apps prefer the **Java agent** or `OpenTelemetrySdkAutoConfiguration` (env-var driven) over hand-wiring this.

#### Q62. [Coding] Write a structured JSON logger in Java that auto-stamps `trace_id`/`span_id` on every line. Show the Logback config.

Plain text logs can't be aggregated and lose the trace correlation that makes distributed debugging possible (Q4/Q5). The fix is a JSON encoder plus pulling the active trace context into the **MDC** so it appears on *every* line without each call site remembering to add it.

```xml
<!-- logback-spring.xml : JSON output via logstash-logback-encoder -->
<configuration>
  <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
    <encoder class="net.logstash.logback.encoder.LogstashEncoder">
      <!-- MDC keys (trace_id, span_id, plus any business fields) are emitted as JSON fields -->
      <includeMdcKeyName>trace_id</includeMdcKeyName>
      <includeMdcKeyName>span_id</includeMdcKeyName>
      <customFields>{"service":"orders","env":"prod"}</customFields>
    </encoder>
  </appender>
  <root level="INFO"><appender-ref ref="JSON"/></root>
</configuration>
```

```java
// A tiny helper that stamps the current OTel span context into MDC, then clears it.
import io.opentelemetry.api.trace.Span;
import org.slf4j.MDC;

public final class TraceLogging {
    public static void run(Runnable body) {
        var ctx = Span.current().getSpanContext();
        if (ctx.isValid()) {
            MDC.put("trace_id", ctx.getTraceId());
            MDC.put("span_id", ctx.getSpanId());
        }
        try { body.run(); }
        finally { MDC.remove("trace_id"); MDC.remove("span_id"); } // avoid pooled-thread leak
    }
}
// Usage: TraceLogging.run(() -> log.info("order_created", kv("order_id", id)));
// emits: {"@timestamp":"...","level":"INFO","message":"order_created",
//         "service":"orders","env":"prod","trace_id":"4bf9...","span_id":"00f0...","order_id":"o-1"}
```

- **Why MDC and not string concatenation?** MDC is a thread-local map the encoder reads automatically, so the IDs land on every line in that request — including lines deep in libraries you don't control. With the OTel Java agent this MDC injection is automatic (`logging.mdc` instrumentation), and you'd skip the helper entirely.
- **Why JSON?** Structured fields are indexable and filterable (`trace_id:"4bf9..."` pivots to the whole request); free text needs brittle regex at ingest.
- **Edge cases:** always `remove`/`clear` in `finally` — a pooled thread that keeps a previous request's `trace_id` mis-attributes logs (Q39). Keep `customFields` low-cardinality; don't dump request bodies (PII + cost). Use UTC timestamps to avoid cross-host comparison pain.

#### Q63. [Theory] What is the difference between a span's `duration`, `service time`, and "self time"? Why does a backend show all three?

A naive reading of a trace assumes a span's wall-clock **duration** equals the work that span did — but in a tree of nested spans that's almost never true, and conflating these three numbers leads to blaming the wrong service.

- **Duration (wall clock):** `end − start` of the span. For a parent that calls three children, this includes all the children's time, queueing, and any uninstrumented gaps.
- **Service time:** the time attributable to *this service*'s span(s) for the request, used in service-level breakdowns ("checkout spent 80ms in orders").
- **Self time (a.k.a. exclusive time):** duration **minus** the time spent inside child spans — i.e., the work this span did *itself* that wasn't delegated to a measured child. This is the number that tells you where the *actual* CPU/wait went.

```
[ parent: 0────────────────────────────100ms ]   duration = 100ms
   [child A 10–40ms]   [child B 50–90ms]          children sum  = 60ms
   self time of parent = 100 − 60 = 40ms          (gaps + parent's own work)
```

Backends show all three because **the slowest *duration* is not the culprit** — the root span always has the largest duration (it contains everything). To find the bottleneck you sort by **self time**: the span with the most *exclusive* time is where the time is genuinely being spent. A parent with 2s duration but 50ms self time is innocent; its slow child (or an uninstrumented gap inflating self time — see Q43) is the suspect. This is also why flame-graph-style trace views exist: they visualize self time so the hot span pops out, rather than making you mentally subtract children.

### 🟡 Intermediate — extended

#### Q64. [Coding] Configure an OpenTelemetry Collector pipeline: OTLP receiver, batch + memory_limiter + redaction processors, fan-out exporters. Explain processor order.

The Collector config is where most teams get **processor ordering** wrong, and order is semantically significant. The two rules that matter: `memory_limiter` must be **first** (so it can shed load before other processors allocate), and `batch` should be **near-last** (so everything upstream operates on individual items and batching is the final efficiency step before export).

```yaml
receivers:
  otlp:
    protocols:
      grpc: { endpoint: 0.0.0.0:4317 }
      http: { endpoint: 0.0.0.0:4318 }

processors:
  memory_limiter:                 # FIRST: backpressure before anything allocates
    check_interval: 1s
    limit_percentage: 80
    spike_limit_percentage: 25
  redaction:                      # strip/hash PII BEFORE it can reach a backend
    allow_all_keys: false
    allowed_keys: [http.method, http.route, http.status_code, service.name, rpc.method]
    blocked_values: ['4[0-9]{12}(?:[0-9]{3})?']   # mask card-like numbers
  resourcedetection:              # add k8s/cloud resource attributes
    detectors: [env, system]
  batch:                          # NEAR-LAST: batch for export efficiency
    send_batch_size: 512
    timeout: 5s

exporters:
  otlp/tempo:    { endpoint: tempo:4317, tls: { insecure: true } }
  prometheus:    { endpoint: 0.0.0.0:8889 }   # scrape target for metrics
  otlphttp/logs: { endpoint: http://loki-otlp:3100/otlp }

service:
  pipelines:
    traces:
      receivers: [otlp]
      processors: [memory_limiter, resourcedetection, redaction, batch]
      exporters: [otlp/tempo]
    metrics:
      receivers: [otlp]
      processors: [memory_limiter, batch]
      exporters: [prometheus]
    logs:
      receivers: [otlp]
      processors: [memory_limiter, redaction, batch]
      exporters: [otlphttp/logs]
```

- **Why `memory_limiter` first?** It's the OOM guard for the Collector itself. When memory crosses the soft limit it forces GC; past the hard limit it *refuses* data (returns retryable errors to senders) so the Collector stays alive rather than crashing and dropping *everything*. Putting it after `batch` would let batches accumulate before the limiter can react.
- **Why `redaction` before `batch` and before export?** PII must be scrubbed before it can leave the Collector. An allow-list (`allow_all_keys: false`) is safer than a deny-list — a new sensitive attribute is dropped by default rather than leaked (Q23).
- **Fan-out:** separate pipelines per signal route to the right backend (Tempo/Prometheus/Loki) from one receiver — the decoupling payoff.
- **Edge case:** order within `processors` *is* the execution order; `batch` after `memory_limiter` but `memory_limiter` can't see batched memory, so size batches conservatively. For tail sampling you'd add a `tail_sampling` processor and a two-tier (load-balancing) deployment so all spans of a trace reach the same instance (Q18).

#### Q65. [Coding] Write a Spring Boot/Servlet filter that propagates a trace context and falls back to generating one if absent at the edge.

At the system's edge (the first service a request hits), there's usually **no** incoming `traceparent`, so you must *start* a trace; for internal services you must *continue* the one already on the wire. A correct filter does extract-or-create, makes the span current, and always ends it.

```java
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import jakarta.servlet.*;
import jakarta.servlet.http.*;

public class TracingFilter implements Filter {
    private final OpenTelemetry otel;
    private final Tracer tracer;
    TracingFilter(OpenTelemetry otel) { this.otel = otel; this.tracer = otel.getTracer("http-edge"); }

    private static final TextMapGetter<HttpServletRequest> GETTER = new TextMapGetter<>() {
        public Iterable<String> keys(HttpServletRequest r) { return java.util.Collections.list(r.getHeaderNames()); }
        public String get(HttpServletRequest r, String key) { return r == null ? null : r.getHeader(key); }
    };

    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws java.io.IOException, ServletException {
        HttpServletRequest http = (HttpServletRequest) req;
        // Extract upstream context if present; otherwise this yields Context.root() -> new trace.
        Context extracted = otel.getPropagators().getTextMapPropagator()
                .extract(Context.current(), http, GETTER);

        Span span = tracer.spanBuilder(http.getMethod() + " " + routeTemplate(http))
                .setParent(extracted)                  // continue upstream trace, or start fresh
                .setSpanKind(SpanKind.SERVER)
                .setAttribute("http.request.method", http.getMethod())
                .setAttribute("http.route", routeTemplate(http))  // TEMPLATE, not raw path!
                .startSpan();
        try (Scope s = span.makeCurrent()) {
            chain.doFilter(req, res);
            span.setAttribute("http.response.status_code", ((HttpServletResponse) res).getStatus());
            if (((HttpServletResponse) res).getStatus() >= 500) span.setStatus(StatusCode.ERROR);
            // Return trace id so support can map a complaint to a trace.
            ((HttpServletResponse) res).setHeader("X-Trace-Id", span.getSpanContext().getTraceId());
        } catch (Exception e) {
            span.recordException(e); span.setStatus(StatusCode.ERROR, e.getMessage()); throw e;
        } finally {
            span.end();
        }
    }
    private static String routeTemplate(HttpServletRequest r) { /* /users/{id} not /users/123 */ return "/checkout"; }
}
```

- **Why `extract` then `setParent`?** `extract` reads `traceparent`/`tracestate`; if absent it returns the root context, so `setParent` + `startSpan` *creates* a new trace at the edge. One code path handles both edge and internal.
- **Why `http.route` as a *template*?** Raw paths (`/users/12345`) as span/metric dimensions are unbounded cardinality (Q14); the route template `/users/{id}` is bounded.
- **Edge cases:** `end()` in `finally` always; returning `X-Trace-Id` is the cheapest support-to-engineering bridge; in real Spring Boot the agent or `spring-boot-starter` does all this — you write this only when you need custom behavior. For async dispatch (`AsyncContext`), the span must be ended on async completion, not when `doFilter` returns.

#### Q66. [Theory] Design the alert routing/severity tree for a 30-service platform. What dimensions do you route on and why?

Alert *generation* (Q9, Q35) is only half the problem; **routing** decides whether the right human is woken at the right urgency, and a bad tree either pages everyone for everything (fatigue, Q37) or silently drops a critical page. The routing tree (Alertmanager-style) is a hierarchical match on label dimensions, most-specific-first, with fall-through defaults.

```
route (default receiver: platform-slack)
├─ match severity=page ─────────────► receiver: pagerduty   (wakes humans)
│    ├─ match team=payments ────────► pagerduty/payments-oncall
│    ├─ match team=search ──────────► pagerduty/search-oncall
│    └─ (group_by [alertname, service]; group_wait 30s; repeat 4h)
├─ match severity=ticket ───────────► receiver: jira        (no paging)
└─ match severity=info ─────────────► receiver: slack-firehose
inhibit: if cluster-wide "RegionDown" page fires, suppress per-service pages in that region
```

The dimensions you route on, and *why each*:

| Dimension | Routes to | Why |
|---|---|---|
| `severity` (page/ticket/info) | paging vs ticket vs Slack | Separates "wake a human now" from "look at it Monday" — the core anti-fatigue control |
| `team`/`service` ownership | the owning on-call | The person paged must be able to *act*; paging the wrong team adds MTTR |
| `env` (prod/staging) | prod pages, staging tickets | A staging blip should never wake anyone |
| `region`/`cluster` | regional escalation + inhibition | Lets one regional root cause inhibit the storm of dependent pages |

The design principles: **group** related alerts (`group_by`) so one incident is one notification, not forty; **inhibit** downstream symptoms when the upstream cause is already paging (DB down → suppress the dependent app pages, Q37); **route by ownership** so accountability is unambiguous; and keep `severity` strictly symptom-tiered — page only on user-facing SLO burn, ticket on capacity/saturation trends, info to a firehose channel for awareness. The senior nuance: routing config should be **as-code and reviewed**, with a mandatory runbook link annotation on every paging route, and a *catch-all default receiver* so a mis-labeled alert is never silently lost — an unrouted critical alert is the worst failure mode of all.

#### Q67. [Coding] Implement a DDSketch-style relative-error quantile sketch and explain why it's mergeable (unlike a naive summary).

Q16 showed exact percentiles need all data in memory and that summaries can't be aggregated across instances. **DDSketch** (used by Datadog) solves both: it gives a *relative-error* guarantee (e.g., the reported p99 is within 1% of the true p99) using buckets whose boundaries grow *geometrically*, and because every instance uses the *same* bucket boundaries, you merge two sketches by simply adding bucket counts — the property summaries lack.

```java
import java.util.HashMap;import java.util.Map;

/** Minimal DDSketch: relative error alpha; bucket index i covers [gamma^i, gamma^(i+1)). */
public final class DDSketch {
    private final double gamma, logGamma;
    private final Map<Integer, Long> buckets = new HashMap<>();
    private long count = 0;

    public DDSketch(double alpha) {                 // alpha = relative accuracy, e.g. 0.01
        this.gamma = (1 + alpha) / (1 - alpha);
        this.logGamma = Math.log(gamma);
    }
    private int index(double v) { return (int) Math.ceil(Math.log(v) / logGamma); }

    public void accept(double value) {              // value > 0 (handle <=0 separately)
        buckets.merge(index(value), 1L, Long::sum);
        count++;
    }
    /** Merge is just adding bucket counts — identical boundaries make it exact. */
    public void mergeFrom(DDSketch other) {
        other.buckets.forEach((k, v) -> buckets.merge(k, v, Long::sum));
        count += other.count;
    }
    public double quantile(double q) {              // q in [0,1]
        if (count == 0) return Double.NaN;
        long rank = (long) Math.ceil(q * count), cum = 0;
        for (int i = minKey(); i <= maxKey(); i++) {
            Long c = buckets.get(i);
            if (c == null) continue;
            cum += c;
            if (cum >= rank) return Math.pow(gamma, i);   // bucket lower-ish bound
        }
        return Math.pow(gamma, maxKey());
    }
    private int minKey() { return buckets.keySet().stream().mapToInt(Integer::intValue).min().orElse(0); }
    private int maxKey() { return buckets.keySet().stream().mapToInt(Integer::intValue).max().orElse(0); }
}
```

- **Why mergeable?** Bucket boundaries are a *global* function of `gamma` only — they don't depend on the data seen. So instance A's bucket `i` and instance B's bucket `i` cover the *exact same value range*, and merging is `count_A[i] + count_B[i]`. A summary computes a quantile locally with no shared structure, so `avg(p99_A, p99_B)` is meaningless (Q16). DDSketch defers the quantile to *after* the merge — aggregate-then-quantile, the correct order.
- **Why geometric buckets?** Latency spans many orders of magnitude (1ms to 30s). Linear buckets would need millions to keep 1% error at both ends; geometric buckets give *constant relative* error with `O(log(range)/log(gamma))` buckets — tiny memory.
- **Edge cases:** values ≤ 0 need a separate zero-count / negative-store (latencies are positive, so usually fine); choose `alpha` for your SLO precision; this is exactly the model behind OTel **exponential histograms** (Q34). Time: O(1) `accept`, O(buckets) `quantile`; space: O(buckets), independent of `count`.

#### Q68. [Theory] Compare W3C Trace Context, B3, and Jaeger propagation formats. What breaks when two services disagree?

Context propagation (Q11) only works if both ends of a hop agree on **how** the context is serialized into headers. There are three common formats, and a mismatch silently severs traces (a top cause of "broken traces", Q58).

| Format | Header(s) | Trace ID | Notes |
|---|---|---|---|
| **W3C Trace Context** | `traceparent`, `tracestate` | 128-bit | The standard; OTel default; vendor-neutral `tracestate` for vendor data |
| **B3** (Zipkin) | `b3` (single) or `X-B3-TraceId`/`X-B3-SpanId`/`X-B3-Sampled` (multi) | 64 or 128-bit | Very widespread (Istio/Envoy historically default B3) |
| **Jaeger** | `uber-trace-id` (`traceid:spanid:parentid:flags`) | 128-bit | Legacy Jaeger clients |

```
traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
             ^v ^------------ trace-id ----------^ ^--span-id--^  ^flags(sampled)
b3:          4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-1
uber-trace-id: 4bf92f...:00f067aa...:0:1
```

**What breaks on disagreement:** if service A injects only `traceparent` (W3C) and service B's SDK is configured to extract only `b3`, B sees *no* incoming context, so it **starts a brand-new trace** — the trace splits into two disconnected halves at that hop, and you can never join the request end-to-end. The same happens with sampling flags: if the sampled bit doesn't round-trip, downstream may drop spans the upstream kept, producing partial traces.

The fix and the senior move: configure a **composite propagator** that injects *and* extracts multiple formats during a migration (`tracecontext,b3`), so a W3C-only and a B3-only peer can both be understood — then standardize on W3C and retire the others. Istio/Envoy and many mesh defaults historically used B3, so a common real-world bug is OTel apps (W3C) behind a B3 mesh; the mesh either needs W3C enabled or the apps need the B3 propagator added. Always verify by capturing the actual header on the wire (Q58) — the format mismatch is invisible until you look at the bytes.

#### Q69. [Coding] Wire Micrometer exemplars so a histogram bucket links to a trace, and show what the `/metrics` line looks like.

Exemplars (Q20) are the bridge from a slow metric bucket to a real trace, but they don't appear by magic — you need an exemplar sampler that reads the *current* trace context and the OpenMetrics exposition format enabled. Here's the wiring in a Spring Boot + Micrometer + OTel app.

```java
import io.micrometer.core.instrument.*;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.micrometer.tracing.exporter.*;   // SpanContextSupplier wiring varies by version

// 1) Register a Prometheus registry with exemplars enabled, fed an exemplar sampler that
//    pulls the active trace id from OTel context for each observation.
PrometheusMeterRegistry registry = new PrometheusMeterRegistry(
        PrometheusConfig.DEFAULT,
        io.prometheus.metrics.model.registry.PrometheusRegistry.defaultRegistry,
        io.micrometer.core.instrument.Clock.SYSTEM,
        () -> {                                  // exemplar sampler: current trace/span id
            var ctx = io.opentelemetry.api.trace.Span.current().getSpanContext();
            return ctx.isSampled()
                ? new io.prometheus.metrics.model.snapshots.Exemplar.Builder()
                      .labels(io.prometheus.metrics.model.snapshots.Labels.of(
                          "trace_id", ctx.getTraceId(), "span_id", ctx.getSpanId()))
                      .build()
                : null;
        });

Timer latency = Timer.builder("http.server.requests")
        .publishPercentileHistogram()            // bucketed histogram -> exemplars attach per bucket
        .register(registry);
// Inside a request (with an active sampled span) just record normally:
latency.record(() -> handle());                 // the bucket it lands in gets a trace_id exemplar
```

```
# /actuator/prometheus  (OpenMetrics format)  — note the trailing "# {trace_id=...}" exemplar
http_server_requests_seconds_bucket{le="1.0"} 10230 # {trace_id="4bf92f35...",span_id="00f067aa..."} 0.94 1718539200.123
http_server_requests_seconds_bucket{le="2.5"} 10248 # {trace_id="9a1b2c3d...",span_id="0e0e4736..."} 1.83 1718539201.456
```

- **Why only when `isSampled()`?** The exemplar's trace must actually have been *kept* — linking to a trace that was dropped gives a dead link. Sampling consistency (`ParentBased`, Q53) is a prerequisite for exemplars to be reliable.
- **Why the OpenMetrics format matters:** exemplars are encoded as the `# {labels} value timestamp` suffix on a bucket line (Q51) — they only serialize in OpenMetrics/`application/openmetrics-text`, so Prometheus must scrape that content type (`scrape_configs: ... honor_exemplars`-style support and `--enable-feature=exemplar-storage`).
- **Edge cases:** exemplars are stored at low cardinality (one or a few per bucket per scrape), so they preserve metric cheapness; if your span isn't sampled or context isn't current on the recording thread (async), no exemplar attaches — another reason context propagation (Q39) matters end-to-end.

### 🟠 Advanced — extended

#### Q70. [Coding] Write a `tail_sampling` Collector config that keeps 100% of errors and slow traces and 5% of the rest. What deployment topology does it require?

Tail sampling (Q18) decides per-*completed*-trace, which requires the Collector to buffer all spans of a trace and — critically — for **all spans of a given trace to arrive at the same Collector instance**. That dictates a two-tier topology: a load-balancing tier that routes by `trace_id`, feeding a sampling tier.

```yaml
# --- Tier 2: the SAMPLING collector (receives complete traces, decides) ---
processors:
  tail_sampling:
    decision_wait: 10s            # buffer this long for a trace to "complete"
    num_traces: 100000            # max traces held in memory (cardinality of buffer)
    expected_new_traces_per_sec: 2000
    policies:
      - name: keep-errors
        type: status_code
        status_code: { status_codes: [ERROR] }      # 100% of error traces
      - name: keep-slow
        type: latency
        latency: { threshold_ms: 1000 }              # 100% of traces > 1s
      - name: baseline-sample
        type: probabilistic
        probabilistic: { sampling_percentage: 5 }    # 5% of everything else
```

```yaml
# --- Tier 1: the LOAD-BALANCING collector (routes whole traces to one tier-2 instance) ---
exporters:
  loadbalancing:
    routing_key: traceID          # MUST route by trace id, not round-robin
    protocol: { otlp: { tls: { insecure: true } } }
    resolver:
      dns: { hostname: otel-sampler-headless, port: 4317 }   # the tier-2 pods
service:
  pipelines:
    traces:
      receivers: [otlp]
      exporters: [loadbalancing]
```

```
apps ──OTLP──► [ Tier 1: load-balancing collectors ]  route by trace_id
                         │  (all spans of trace T -> same tier-2 pod)
                         ▼
               [ Tier 2: tail_sampling collectors ]  buffer 10s, apply policies
                         ▼  keep errors + slow + 5% baseline
                    Tempo / backend
```

- **Why two tiers?** A single load-balanced fleet would scatter a trace's spans across pods, so no pod sees the whole trace and the latency/error policies can't evaluate correctly. The `loadbalancing` exporter hashes `trace_id` so every span of a trace lands on the same tier-2 pod.
- **Cost of tail sampling:** memory to buffer `num_traces` for `decision_wait`, and added latency before export (you don't see a trace until ~10s after it completes). `decision_wait` must exceed your slowest trace's span-arrival spread, or you'll decide before late spans arrive and drop them.
- **Policy order/combination:** policies are OR-ed — a trace kept by *any* policy is kept. The classic recipe: status_code(ERROR) ∪ latency(>1s) ∪ probabilistic(5%) guarantees you never lose an error or slow trace while cutting routine volume ~95%. Edge case: `num_traces` too small under a traffic spike silently evicts buffered traces (dropping them) — size it to `expected_new_traces_per_sec × decision_wait` with headroom.

#### Q71. [Coding] Use OTTL (the OpenTelemetry Transform Language) in a `transform` processor to redact PII, drop noisy spans, and derive an attribute. Why OTTL over bespoke processors?

For non-trivial in-pipeline editing, hand-configuring `attributes`/`filter`/`redaction` processors gets unwieldy; **OTTL** is the Collector's small expression language for *statement-based* transforms across all signals, applied in the `transform` and `filter` processors. It's powerful because one declarative grammar covers redact/drop/derive/route logic that previously needed five different processors.

```yaml
processors:
  transform:
    error_mode: ignore            # a bad statement skips, doesn't crash the pipeline
    trace_statements:
      - context: span
        statements:
          # 1) Redact: hash the user email attribute in place (SHA-256).
          - set(attributes["user.email"], SHA256(attributes["user.email"])) where attributes["user.email"] != nil
          # 2) Strip credentials that should NEVER reach a backend.
          - delete_key(attributes, "http.request.header.authorization")
          # 3) Normalize a raw URL path down to a route template to control cardinality.
          - replace_pattern(attributes["url.path"], "/users/[0-9]+", "/users/{id}")
          # 4) Derive a boolean dimension for easy filtering.
          - set(attributes["is_slow"], true) where (end_time_unix_nano - start_time_unix_nano) > 1000000000
  filter:
    error_mode: ignore
    traces:
      span:
        # 5) Drop health-check spans entirely — pure noise, high volume.
        - 'attributes["http.route"] == "/healthz"'
        - 'attributes["http.route"] == "/readyz"'

service:
  pipelines:
    traces:
      receivers: [otlp]
      processors: [memory_limiter, transform, filter, batch]
      exporters: [otlp/tempo]
```

- **Why OTTL over bespoke processors?** One readable grammar (`set`/`delete_key`/`replace_pattern`/`keep_keys`/conditions via `where`) replaces a stack of single-purpose processors, and it's *consistent* across traces/metrics/logs (`metric_statements`, `log_statements`). It also expresses things the simple processors can't — derived attributes, conditional logic, cross-field computation.
- **Why redact/drop here, centrally?** Same governance argument as Q23/Q64: one audited control point, no app redeploys, allow-list-able. Dropping `/healthz` spans at the Collector can cut span volume dramatically (health checks are often the highest-frequency, lowest-value spans).
- **Edge cases:** `error_mode: ignore` prevents a malformed statement from killing the pipeline (vs `propagate` which fails the batch); `transform` runs *before* `filter` here so derived `is_slow` could feed a later filter; mind ordering relative to `tail_sampling` — redact before sampling so you don't sample on PII and don't ship PII even for kept traces. Hashing (not deleting) `user.email` preserves the ability to *group by* a user without storing the raw value.

#### Q72. [Theory] Design a capacity model for a Prometheus/TSDB: estimate memory, disk, and ingestion from first principles.

A frequent senior task is "how big does our metrics backend need to be?" — and answering "throw RAM at it" fails interviews and production. The model is driven by **active series** (cardinality) and **samples per second**, from first principles.

```
Active series  = Σ over metrics of (unique label-value combinations)
Samples/sec    = active_series / scrape_interval
Ingested samples/day = samples_sec × 86400

Memory (head)  ≈ active_series × ~3–4 KB   (in-memory index + chunk buffers per series)
Disk/day       ≈ ingested_samples_per_day × bytes_per_sample (~1.3–2 B after compression)
Query memory   ∝ series_touched × samples_in_range (a wide query can dwarf head memory)
```

Worked example: 500 targets × 2,000 series each = **1,000,000 active series**. At a 15s scrape that's `1e6 / 15 ≈ 66,700 samples/sec ≈ 5.76e9 samples/day`. Head memory ≈ `1e6 × 3.5 KB ≈ 3.5 GB` *just for the head block*, before query and WAL overhead — so you'd provision ~8–12 GB to be safe. Disk ≈ `5.76e9 × 1.5 B ≈ 8.6 GB/day` → ~260 GB for 30-day retention (Prometheus compresses extremely well via Gorilla/XOR encoding, ~1.3–2 bytes/sample).

The senior insights this model surfaces:

1. **Cardinality dominates everything** — memory scales with *series*, not sample volume, which is why one unbounded label (Q14) is catastrophic: it multiplies series, not just bytes. Halving scrape frequency halves *disk* but barely touches *memory*.
2. **Query memory is the silent OOM** — a dashboard that selects a million series over 30 days can transiently allocate more than the head block; this is why recording rules (precompute) and query limits matter.
3. **Retention vs resolution trade-off** — long retention demands downsampling (Thanos/Mimir 5m/1h rollups, Q40) or disk explodes; you keep raw data short and downsampled data long.
4. **Provision for headroom and churn** — pod restarts create *new* series (new `instance`/`pod` label values) that linger for the staleness window, transiently inflating active series; size for peak + churn, not steady-state. The deliverable is a sizing spreadsheet keyed on series count, scrape interval, and retention — and a cardinality budget (Q41) so the inputs stay bounded.

#### Q73. [Coding] Implement context propagation across a Kafka producer/consumer boundary in Java, using injection on produce and span links on consume.

Async/broker boundaries are where tracing most often breaks (Q24, Q58) because there's no synchronous call to carry context — you must explicitly serialize the context into the *message* and reconstruct it on the other side. And because a batch consumer drains messages from *many* producers, the correct model on consume is **span links**, not a single parent.

```java
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.header.Headers;

class KafkaTracing {
    final OpenTelemetry otel; final Tracer tracer;
    KafkaTracing(OpenTelemetry o){ otel=o; tracer=o.getTracer("kafka"); }

    // PRODUCE: inject current context into Kafka record headers.
    static final TextMapSetter<Headers> SETTER =
        (h, k, v) -> h.add(k, v.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    static final TextMapGetter<Headers> GETTER = new TextMapGetter<>() {
        public Iterable<String> keys(Headers h){ var l=new java.util.ArrayList<String>(); h.forEach(x->l.add(x.key())); return l; }
        public String get(Headers h, String k){ var hdr=h.lastHeader(k); return hdr==null?null:new String(hdr.value()); }
    };

    void send(Producer<String,String> p, String topic, String key, String val) {
        Span span = tracer.spanBuilder(topic+" send").setSpanKind(SpanKind.PRODUCER).startSpan();
        try (var s = span.makeCurrent()) {
            var rec = new ProducerRecord<>(topic, key, val);
            otel.getPropagators().getTextMapPropagator().inject(Context.current(), rec.headers(), SETTER);
            p.send(rec);
        } finally { span.end(); }
    }

    // CONSUME (batch): one span for the batch, LINK each message's producer context.
    void consumeBatch(ConsumerRecords<String,String> records) {
        SpanBuilder b = tracer.spanBuilder("kafka receive").setSpanKind(SpanKind.CONSUMER);
        for (var rec : records) {
            Context producerCtx = otel.getPropagators().getTextMapPropagator()
                    .extract(Context.current(), rec.headers(), GETTER);
            SpanContext linked = Span.fromContext(producerCtx).getSpanContext();
            if (linked.isValid()) b.addLink(linked);     // link, not parent: many producers -> one batch
        }
        Span span = b.startSpan();
        try (var s = span.makeCurrent()) { processAll(records); }
        finally { span.end(); }
    }
    void processAll(ConsumerRecords<String,String> r){ /* ... */ }
}
```

- **Why inject into headers?** The broker doesn't carry thread-local context across the network/disk hop; the only durable carrier is the message itself. Inject on produce, extract on consume — same `traceparent`, different transport (Kafka headers instead of HTTP headers).
- **Why links, not parent, on batch consume?** A poll returns N messages from N different traces. A span can have exactly one parent but *many* links, so each producer's `SpanContext` becomes a link (Q36). If you process messages individually (not batched), you can instead make each a child of its own producer context.
- **Edge cases:** if you set the batch span's parent to the *first* message's context, you'd falsely attribute the other 99 messages to that one trace — a classic bug. Per-message child spans on a high-throughput topic explode volume, so sample them (Q24). Always `end()` the consumer span even if processing throws, or the span leaks and the trace is incomplete.

#### Q74. [Theory] What is a "metrics blind spot" created by sampling, and why are metrics derived from sampled spans dangerous?

A subtle but critical correctness trap (touched on in Q50) deserves its own treatment because it silently corrupts dashboards. **Metrics are supposed to be exact** — a request counter counts *every* request. **Traces are sampled** — you deliberately keep a fraction. The danger arises when you *derive* metrics (via the `spanmetrics` connector) from a span stream that has *already been sampled*: your "request rate" now reflects only the kept 5%, so every rate, error count, and throughput number is off by the (variable, policy-dependent) sampling factor — and tail sampling's factor *isn't even constant* (it keeps 100% of errors but 5% of successes), so you can't even correct it with a multiplier.

```
WRONG:  spans ─► tail_sample (keep 5% + all errors) ─► spanmetrics ─► "rate"
        => error RATIO looks like ~100% (all errors kept, few successes kept). NONSENSE.

RIGHT:  spans ─► spanmetrics (derive RED from FULL stream) ─► metrics  (exact)
              └► tail_sample (keep interesting) ─► trace store        (sampled, for debug)
```

The rule: **derive metrics from the *unsampled* span stream, then sample downstream for trace storage.** In a Collector pipeline that means the `spanmetrics`/`servicegraph` connector must sit *before* the `tail_sampling` processor (or on a branch that bypasses it). If you must sample at the SDK (head sampling) for cost reasons, then you cannot derive trustworthy counts from spans at all — you need *separate, unsampled* metric instruments (a real counter) for rates and SLOs, and use spans only for the *shape* of latency on the sampled subset.

This is why mature platforms keep alerting/SLOs on **dedicated, unsampled metrics** and never compute SLI ratios from sampled trace data (the metrics camp's core objection in Q50/Q25). The interview signal: recognizing that *sampling and exactness are in tension*, and that the pipeline order (derive-then-sample) is what reconciles them — get the order wrong and your "error rate" graph lies during exactly the incident you're trying to debug.

### 🔴 Expert — extended

#### Q75. [Coding] Implement a custom OpenTelemetry `Sampler` that always keeps traces for a tenant on a debug allow-list, force-keeps on a debug header, and ratio-samples the rest. Why implement it as a `Sampler` and not a filter?

Real platforms need *policy* head sampling — "always trace tenant `acme` while we debug their issue, honor an `X-Debug-Trace` header, ratio-sample everyone else" — implemented as a proper OTel `Sampler` so the decision is made at span creation, encoded in the sampled flag, and *propagated consistently* (Q53). Doing it as a downstream filter is wrong: by then you've already paid to create/process spans and you've lost the propagation guarantee.

```java
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.samplers.*;
import java.util.*;

public final class PolicySampler implements Sampler {
    private final Set<String> debugTenants;
    private final Sampler ratio;           // delegate for the "everyone else" case
    private final Sampler delegate;        // wrapped in ParentBased for consistency

    public PolicySampler(Set<String> debugTenants, double baseRatio) {
        this.debugTenants = debugTenants;
        this.ratio = Sampler.traceIdRatioBased(baseRatio);
        // ParentBased so non-root spans obey the already-made upstream decision.
        this.delegate = Sampler.parentBased(this);
    }

    @Override
    public SamplingResult shouldSample(Context parentCtx, String traceId, String name,
            SpanKind kind, Attributes attrs, List<LinkData> links) {
        // Force-keep: explicit debug intent on this request.
        if ("true".equals(attrs.get(io.opentelemetry.api.common.AttributeKey.stringKey("debug.trace")))) {
            return SamplingResult.create(SamplingDecision.RECORD_AND_SAMPLE);
        }
        // Always-keep for tenants we're actively debugging.
        String tenant = attrs.get(io.opentelemetry.api.common.AttributeKey.stringKey("tenant.id"));
        if (tenant != null && debugTenants.contains(tenant)) {
            return SamplingResult.create(SamplingDecision.RECORD_AND_SAMPLE);
        }
        // Default: deterministic ratio by trace id (consistent across services).
        return ratio.shouldSample(parentCtx, traceId, name, kind, attrs, links);
    }
    @Override public String getDescription() { return "PolicySampler{debugTenants,ratio}"; }
}
// Wire: SdkTracerProvider.builder().setSampler(Sampler.parentBased(new PolicySampler(tenants, 0.05)))
```

- **Why a `Sampler`, not a filter?** The `Sampler` runs at `startSpan()`, *before* the span exists, so a dropped trace costs ~nothing (no attributes boxed, no export). It also sets the `traceparent` sampled flag, so wrapping it in `ParentBased` makes every downstream hop honor the *root's* decision — you get whole, consistent traces (Q53). A post-hoc filter can't propagate a decision backward and can't avoid the creation cost.
- **`RECORD_AND_SAMPLE` vs `RECORD_ONLY` vs `DROP`:** `RECORD_AND_SAMPLE` records *and* sets the sampled flag (exported + propagated); `RECORD_ONLY` records locally (e.g., for live in-process debugging or local metrics) but doesn't set the flag; `DROP` does nothing. The debug header/tenant cases use `RECORD_AND_SAMPLE` so the *whole* trace downstream is kept.
- **Edge cases:** the attribute-based decision only works if `tenant.id`/`debug.trace` are set on the *root* span at creation (e.g., by the edge filter, Q65) — a sampler can't see attributes added *after* the decision. Combine this head policy with Collector **tail sampling** to also guarantee errors/slow are kept regardless of the head ratio. Keep `debugTenants` swappable at runtime (atomic reference) so you can enable debugging without redeploying.

#### Q76. [Theory] Reactive code (Project Reactor) loses trace context across `flatMap`. Explain the root cause and the correct fix, with the failure mode if you get it wrong.

This is the deepest of the async-propagation problems (foreshadowed in Q29/Q39) because Reactor has **no stable carrier thread** — operators may run on different schedulers, and there's *no thread-local that survives* a `flatMap`/`publishOn` hop. Relying on OTel's thread-local `Context` (which works for straight-line and even `taskWrapping` executors) silently fails here.

```java
// WRONG: thread-local context is gone by the time the lambda runs on another scheduler.
Mono<Order> wrong = repo.find(id)
    .publishOn(Schedulers.boundedElastic())   // hops thread -> OTel Context.current() is root!
    .flatMap(o -> { Span.current().setAttribute("found", true);  // attaches to WRONG/no span
                    return charge(o); });

// RIGHT: store the OTel Context in the Reactor Context, and make it current INSIDE the operator.
Mono<Order> right = Mono.deferContextual(reactorCtx -> {
        io.opentelemetry.context.Context otelCtx =
            reactorCtx.get(io.opentelemetry.context.Context.class);
        try (var scope = otelCtx.makeCurrent()) {      // restore on THIS thread, for THIS op
            return repo.find(id).flatMap(o -> charge(o));
        }
    })
    .contextWrite(rc -> rc.put(io.opentelemetry.context.Context.class,
                               io.opentelemetry.context.Context.current()));  // capture at subscribe
// Better: enable Reactor's automatic propagation hook (Micrometer Context Propagation library):
//   Hooks.enableAutomaticContextPropagation();   // bridges ThreadLocal <-> Reactor Context
```

**Root cause:** Reactor assembles a pipeline once but *executes* it across many threads chosen by schedulers; the active span is in a `ThreadLocal`, and a `ThreadLocal` set on the subscribing thread is not visible on the scheduler thread that runs the continuation. So `Span.current()` inside a `flatMap` returns the *root* (no span) or, worse on a pooled scheduler, a *leftover* span from a previous request.

**The correct fix** is to carry the context in Reactor's own propagation mechanism — the `Context` (a subscription-scoped immutable map that *does* flow through operators) — and re-`makeCurrent()` it inside each operator that needs it. The modern, ergonomic version is the **Micrometer Context Propagation** library plus `Hooks.enableAutomaticContextPropagation()`, which transparently bridges registered `ThreadLocal`s (including OTel's) to/from the Reactor Context at every operator boundary, so instrumentation "just works" without manual `deferContextual`.

**Failure mode if you get it wrong:** the visible symptom is **flat or orphaned traces** — child spans created inside `flatMap` either have no parent (new root, disconnected) or, on a shared scheduler, attach to *another request's* span (cross-request contamination — the worst kind, because it silently mixes two users' traces). Logs lose their `trace_id` the same way (Q39). The expert tell is recognizing that this isn't an OTel bug but a fundamental consequence of decoupling assembly from execution, and that the *only* robust carrier across reactive boundaries is the subscription-scoped Reactor Context, not any thread-local.

#### Q77. [Behavioral] (STAR) Tell me about a time you led an observability effort that turned a recurring, hard-to-diagnose production problem into something the team could resolve quickly. What did you do and what was the outcome?

**Situation:** At a previous role, our checkout service had a recurring "phantom latency" incident — roughly weekly, p99 would spike to 5–8s for 10–20 minutes, customers abandoned carts, and on-call would thrash for an hour before it self-resolved. We had dashboards showing the spike but *no idea why*; postmortems kept concluding "transient, couldn't reproduce," which is a euphemism for "we're blind." It was eroding both revenue and on-call morale.

**Task:** As the senior engineer owning reliability for that domain, I took responsibility for making this *diagnosable* — not just patching a symptom. The explicit goal I set with my manager was to cut MTTR for this class of incident from ~60 minutes to under 10, and ideally prevent recurrence, within a quarter.

**Action:** I treated it as an observability *gap*, not a code mystery. First, I instrumented the suspected blind spot: the spans showed a long unaccounted gap (a classic "white space" gap, Q43) inside the payment call, so I added a span and a `db.pool.wait_ms` attribute around connection acquisition — which immediately revealed the gap was *connection-pool queueing*, not query time. Second, I wired **exemplars** on the latency histogram so on-call could jump from the spike straight to a real slow trace in one click, and ensured `trace_id` was stamped on every log line (MDC) so the trace pivoted to the exact logs. Third, I added a **USE-style saturation alert** on pool utilization and a deploy-annotation overlay on the dashboard. The data then showed the spikes correlated with a specific batch job that periodically hammered the same DB, exhausting the shared pool. I drove the fix (a dedicated pool + a circuit breaker with a bounded wait), and — importantly — I ran a **blameless postmortem template change** so every incident now asks "what observability gap delayed diagnosis?" and produces an instrumentation action item.

**Result:** The next occurrence was diagnosed in **under 5 minutes** (on-call clicked the exemplar, saw pool-wait time, recognized the pattern from the runbook I wrote), and after the pool/circuit-breaker fix the incident stopped recurring entirely. MTTR for pool-saturation-class incidents dropped from ~60 min to ~6 min, and the *pattern* I established — instrument the gap, link metrics→traces→logs, add the saturation alert, capture the gap in the postmortem — got adopted by two other teams. The lasting lesson I carry: **most "unreproducible transient" incidents are actually instrumentation gaps**, and the highest-leverage reliability work is often making the invisible visible rather than writing more code. I also learned to frame it for leadership in MTTR/revenue terms, which is what got me the time to do it properly.

#### Q78. [Theory] How do you instrument and reason about observability under clock skew and out-of-order/late-arriving spans in a distributed trace? What can and cannot be trusted?

Distributed tracing stitches spans from *different machines*, each with its own clock, and the entire span waterfall depends on comparing those timestamps — so **clock skew** can fabricate phenomena that look like real bugs (Q43, Q58). A child span can appear to *start before its parent* or *end after it*, durations can go negative, and "gaps" or "overlaps" can be pure artifacts of two hosts disagreeing by tens of milliseconds. NTP keeps typical skew to single-digit milliseconds, but VM pauses, live migration, and leap-second handling can blow that out transiently.

```
True causality:  parent 100–300ms; child (other host) 150–250ms  (nested, correct)
With +40ms skew on child's host:
  child appears 190–290ms  -> still nested (ok-ish)
With −80ms skew on child's host:
  child appears 70–170ms   -> "starts before parent" -> impossible -> skew artifact!
```

What you **can** trust vs **cannot**:

- **Trustworthy:** *durations measured entirely within one process* (start and end stamped by the *same* clock) — a single span's own duration is reliable regardless of cross-host skew, because the error cancels. Causal *ordering* derived from the parent/child relationship (the trace structure) is trustworthy even when timestamps disagree — `parent_span_id` is ground truth; the timestamps are not.
- **Untrustworthy:** *cross-host time arithmetic* — the apparent gap between a client span's send and the server span's receive (network time vs skew are entangled), and any "this happened before that" inference across hosts based purely on wall-clock timestamps.

The engineering responses a senior should name: (1) **rely on span structure, not timestamps, for causality** — backends reconstruct the tree from IDs and only use timestamps for layout, often *clamping* a child to its parent's bounds to hide impossible overlaps; (2) **monitor clock skew itself** (NTP offset metrics) and treat large offsets as an incident, because they silently corrupt *all* traces from that host; (3) for **late-arriving spans**, the backend must hold a trace open for a window (and tail samplers must `decision_wait` long enough, Q70) or late spans are dropped, producing apparently-incomplete traces; (4) prefer **monotonic clocks** for *duration* measurement within a process (immune to wall-clock adjustments) while using wall-clock only for absolute timestamps. The deep point: **a trace's topology is reliable; its cross-host timing is an approximation**, and confusing the two leads to chasing skew artifacts as if they were latency bugs.

#### Q79. [Theory] Design observability for a multi-tenant SaaS where one tenant must never see another's telemetry and one noisy tenant must not blind the rest. What are the isolation dimensions?

Multi-tenancy turns observability into a **security and fairness** problem on top of a technical one: telemetry contains business-sensitive data (Q23), so cross-tenant leakage is a breach, and a single tenant's volume spike can OOM a shared backend and blind *every* tenant (a noisy-neighbor availability problem). I'd design isolation across several dimensions, choosing the strength per regulatory need.

```
Isolation dimension      Mechanism                              Protects against
-----------------------  -------------------------------------  ----------------------------
Data access (read)       per-tenant label + auth at query layer  one tenant reading another's
Storage (write)          per-tenant streams / namespaces         data commingling, deletion scope
Resource fairness        per-tenant ingest/series/rate limits    noisy neighbor OOM-ing shared TSDB
Cost attribution         mandatory tenant label (Q60)            unfair shared bill
PII / compliance         tenant-scoped redaction + residency     GDPR/region, contractual
```

The architecture choices and their trade-offs:

1. **Mandatory `tenant_id` as a low-cardinality dimension** on every signal, enforced by the paved-road library and Collector enrichment so it can't be omitted — this is the join/filter/authorization key for everything else. (It's *low* cardinality only if tenants number in the thousands, not millions; at high tenant counts, `tenant_id` as a metric label itself becomes a cardinality problem and you push it to logs/traces or shard backends.)
2. **Per-tenant rate/series limits** (Mimir/Cortex `max_global_series_per_user`, per-tenant log ingest caps) so one tenant hitting a cardinality explosion or log storm is throttled and *isolated* rather than taking down the shared store — the fairness control. The alternative, full per-tenant backends, gives the strongest isolation but multiplies operational cost; reserve it for enterprise/regulated tenants.
3. **Query-layer authorization**: the dashboards/query API must inject a `tenant_id` filter derived from the *authenticated* principal, never from user input — otherwise a crafted query reads another tenant's data. This is the read-isolation linchpin and the most common multi-tenant observability vulnerability.
4. **Tenant-scoped redaction and data residency**: redaction rules and storage region may need to differ per tenant (EU tenant data stays in EU); the Collector can route by `tenant_id` to region-specific backends.

The senior framing: there's a **spectrum from "soft" isolation (shared backend, label-based separation + limits) to "hard" isolation (separate backends/clusters per tenant)**, and you place each tenant on it by their compliance and contractual requirements — soft for the long tail of small tenants (cost-efficient), hard for regulated/enterprise tenants (strong guarantees). The two failure modes to design against explicitly are **leakage** (a security incident — defend with query-layer auth and tenant-scoped storage) and **noisy-neighbor blinding** (an availability incident — defend with per-tenant limits and a cardinality budget per tenant), and a mature system has both controls, not just one.

#### Q80. [Coding] Write a Prometheus alerting rule set that detects the *absence* of telemetry (dead-man's switch) and avoids the "dead service silently resolves the alert" trap. Show the trap and the fix.

The most dangerous failure is silence (Q48, Q27): a ratio alert evaluates to *empty* when the service dies and emits no data, so it **resolves exactly when the outage is total**. The fix is to explicitly alert on *absence* and to make the ratio alert robust to a missing denominator.

```yaml
groups:
- name: deadmans_switch
  rules:
  # 1) THE TRAP (do NOT rely on this alone): if checkout dies, no series ->
  #    expression is empty -> alert resolves -> you think it's healthy.
  - alert: CheckoutErrorRatioHigh_FRAGILE
    expr: |
      sum(rate(http_requests_total{job="checkout",code=~"5.."}[5m]))
      / sum(rate(http_requests_total{job="checkout"}[5m])) > 0.05
    for: 5m
    labels: { severity: page }

  # 2) FIX A — dead-man's switch: fire when the target stops reporting at all.
  - alert: CheckoutNoData
    expr: absent(up{job="checkout"} == 1)      # true (fires) when NO target is up
    for: 2m
    labels: { severity: page }
    annotations: { summary: "Checkout is reporting NO data — likely fully down" }

  # 3) FIX B — make the ratio robust so a missing denominator doesn't vanish.
  - alert: CheckoutErrorRatioHigh
    expr: |
      sum(rate(http_requests_total{job="checkout",code=~"5.."}[5m]))
      /
      (sum(rate(http_requests_total{job="checkout"}[5m])) > 0)   # avoid 0/0
      > 0.05
    for: 5m
    labels: { severity: page }

  # 4) FIX C — a Watchdog that is ALWAYS firing on purpose. If the WATCHDOG
  #    page ever STOPS arriving, the whole alerting pipeline is broken.
  - alert: Watchdog
    expr: vector(1)                            # always 1 -> always firing
    labels: { severity: none }
    annotations: { summary: "Alerting pipeline heartbeat — alert if this STOPS" }
```

- **The trap explained:** `errors/requests > 0.05` needs both series to exist. A fully-down service produces *no* `http_requests_total` samples, so after the staleness window the numerator and denominator both go stale, the expression returns the empty vector, and Prometheus considers the alert *not firing* — it silently resolves at the moment of total outage.
- **`absent(up{job="checkout"} == 1)`** is the dead-man's switch: it fires precisely when *no* checkout target is reporting `up==1`. Pair it with the ratio alert so you're covered for both "high errors" and "no data."
- **The `Watchdog`/`vector(1)`** is a deliberately always-firing alert routed to a receiver that *expects* it (e.g., a heartbeat monitor or Dead Man's Snitch). The monitoring of the *monitor*: if the Watchdog page stops arriving, your *entire* alerting pipeline (Prometheus → Alertmanager → pager) is broken, and an *external* system catches it (Q27).
- **Edge cases:** `for:` interacts with staleness — a series going stale mid-`for` window can reset a pending alert; `absent()` needs the job to be a known scrape target (it can't detect a target that was never configured). `or vector(0)` is the alternative to the `> 0` guard for backfilling a default. The principle: **never alert only on the presence of bad data; always also alert on the absence of expected good data.**

#### Q81. [Theory] An OTLP exporter's queue is full and the backend is rejecting data. Walk through the SDK/Collector backpressure, retry, and persistence options — and the correctness/cost trade-offs of each.

When the telemetry backend slows or rejects (rate limit, outage, network blip), the data has to go *somewhere* — and the choices form a spectrum from "drop it" to "never lose it," each with a different cost and a different risk to the *application*. The cardinal rule (Q47, Q61) frames everything: **telemetry must never block or crash the business request path**, so under sustained backpressure you eventually *drop*, and the design question is how gracefully and how much you buffer first.

```
                   App SDK (BatchSpanProcessor)            Collector
                   ┌───────────────────────────┐           ┌──────────────────────────┐
 span.end() ─────► │ bounded queue (maxQueue)   │ ──OTLP──► │ recv ─► [processors] ─►   │
                   │  full? -> DROP (++counter) │  retry w/ │  sending_queue (mem/disk) │
                   │  NEVER blocks the request  │  backoff  │  full? -> refuse (429) or │
                   └───────────────────────────┘           │  drop; memory_limiter shed│
                                                            └──────────────────────────┘
```

The layered options and their trade-offs:

1. **SDK bounded queue + drop (default, safest for the app):** the `BatchSpanProcessor`'s `maxQueueSize` caps memory; when full it **drops** new spans and increments a dropped counter. *Trade-off:* you lose telemetry under overload, but the app is *never* blocked or OOM'd. This is correct for the app's sake — the alternative (blocking) turns a telemetry hiccup into a customer-facing outage. **You must alert on the dropped-spans counter**, or you lose data silently.
2. **Retry with exponential backoff (transient errors):** OTLP exporters retry on retryable codes (`UNAVAILABLE`, 429, 503) with backoff + jitter, distinguishing *retryable* (transient) from *non-retryable* (a 400 bad-request — retrying is pointless and amplifies load). *Trade-off:* smooths over blips, but unbounded retry against a down backend just fills the queue, so retry has a deadline after which it drops.
3. **Collector `sending_queue` + `retry_on_failure`:** the Collector buffers in memory and re-sends. *Trade-off:* memory grows during a backend outage; `memory_limiter` (Q64) sheds load (refusing upstream with retryable errors) before OOM — pushing backpressure *back to the SDK*, which then drops at its own bounded queue. Backpressure propagates upstream, dropping at the cheapest edge.
4. **Persistent queue (`file_storage` extension): buffer to disk** so a backend outage doesn't lose data even across Collector restarts. *Trade-off:* survives outages and restarts (durability), at the cost of disk I/O, disk space limits (still bounded — a long outage eventually fills disk and drops), and added latency. Use it where telemetry loss is genuinely expensive (audit/billing-grade events), not for routine spans where sampling already accepts loss.

The senior synthesis: it's a **spectrum from drop → retry → memory-buffer → disk-buffer**, trading *durability* against *resource cost and app risk*. The non-negotiables are (a) **bounded everything** (every queue has a max; unbounded buffering just relocates the OOM), (b) **never block the request path**, and (c) **observe the observability** — a dropped-data counter and queue-saturation metric must themselves be monitored, because a silently-dropping pipeline is the "monitoring the monitor" blind spot (Q27). The right point on the spectrum depends on the data's value: sampled spans tolerate drop; SLO metrics and audit logs justify disk persistence. And critically, **metrics survive better than spans** under loss because they're aggregates — a dropped scrape loses one data point, while dropped spans lose whole requests — which is another reason to drive alerting from metrics, not from trace-derived data (Q74).

#### Q82. [Coding] Instrument a short-lived batch/cron job to report metrics via the Prometheus Pushgateway. Why can't you just scrape it, and what's the deletion gotcha?

The pull model (Q33) assumes a long-lived target with a stable `/metrics` endpoint; a batch job that runs for 40 seconds and exits is gone before Prometheus's next scrape, so its result is *never* observed. The **Pushgateway** is the buffer: the job *pushes* its final metrics to it, the Pushgateway holds them, and Prometheus scrapes the Pushgateway as a normal long-lived target.

```java
import io.prometheus.metrics.core.metrics.Gauge;
import io.prometheus.metrics.exporter.pushgateway.PushGateway;
import io.prometheus.metrics.model.registry.PrometheusRegistry;

public class NightlyReconcile {
    public static void main(String[] args) throws Exception {
        PrometheusRegistry reg = new PrometheusRegistry();
        Gauge lastSuccess = Gauge.builder().name("batch_last_success_unixtime")
                .help("Timestamp of last successful run").register(reg);
        Gauge processed = Gauge.builder().name("batch_records_processed")
                .help("Records processed this run").register(reg);

        PushGateway pg = PushGateway.builder().registry(reg)
                .address("pushgateway:9091")
                .job("nightly_reconcile")     // becomes the grouping key {job="nightly_reconcile"}
                .build();
        try {
            long n = doWork();                // ... the actual batch ...
            processed.set(n);
            lastSuccess.setToCurrentTime();
            pg.push();                        // PUT: replaces this job's metrics atomically
        } catch (Exception e) {
            // Deliberately do NOT push lastSuccess on failure -> alert on staleness catches it.
            throw e;
        }
    }
    static long doWork() { return 12345; }
}
```

```promql
# Alert: batch hasn't succeeded in > 26h (it should run nightly). Detects a job that
# silently STOPPED running — the push model's version of a dead-man's switch.
time() - batch_last_success_unixtime{job="nightly_reconcile"} > 26 * 3600
```

- **Why not scrape directly?** There's nothing to scrape — the process exited. Pushgateway converts an ephemeral producer into a scrapable, persistent surface.
- **The deletion gotcha:** the Pushgateway **persists pushed metrics forever** until explicitly deleted or overwritten. A job that runs with a *dynamic* grouping label (e.g., `instance=<random-pod>`) leaves a growing pile of stale series that Prometheus keeps scraping — a cardinality leak. Use a *stable* grouping key per logical job and `push()` (PUT, which replaces that job's group) rather than `pushAdd()`; delete the group when a job is decommissioned. Also, Pushgateway metrics **don't expire on their own** and carry the *push* timestamp behavior, so `up` won't tell you the job died — you must alert on *staleness of the metric itself* (above), not on target-down.
- **The anti-pattern:** using Pushgateway for long-lived services "because it's easier" — you lose the scrape-as-health-check and reintroduce client-side fan-out. Reserve it strictly for service-level batch/cron jobs.

#### Q83. [Coding] Implement a custom `TextMapPropagator` (e.g., to carry a legacy `X-Request-ID`) and compose it with W3C. When is a custom propagator justified?

Sometimes you must interoperate with a legacy system that carries correlation in a non-standard header (`X-Request-ID`, a homegrown trace header) that you can't change. Rather than hack it into business code, implement a `TextMapPropagator` and *compose* it with W3C so both travel on every hop — the clean, centralized way to bridge formats (Q68).

```java
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.*;
import java.util.*;

/** Propagates a legacy correlation id stored in Baggage/Context under a known key. */
public final class RequestIdPropagator implements TextMapPropagator {
    static final String HEADER = "x-request-id";
    static final ContextKey<String> KEY = ContextKey.named("legacy.request_id");

    @Override public Collection<String> fields() { return List.of(HEADER); }

    @Override public <C> void inject(Context ctx, C carrier, TextMapSetter<C> setter) {
        String rid = ctx.get(KEY);
        if (rid != null && carrier != null) setter.set(carrier, HEADER, rid);
    }
    @Override public <C> Context extract(Context ctx, C carrier, TextMapGetter<C> getter) {
        if (carrier == null) return ctx;
        String rid = getter.get(carrier, HEADER);
        return rid == null ? ctx : ctx.with(KEY, rid);
    }
}
```

```java
// Compose: W3C trace context + baggage + the legacy request id, all injected/extracted together.
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;

var propagators = ContextPropagators.create(TextMapPropagator.composite(
        W3CTraceContextPropagator.getInstance(),   // traceparent/tracestate
        W3CBaggagePropagator.getInstance(),         // baggage
        new RequestIdPropagator()));                // x-request-id (legacy bridge)
// OpenTelemetrySdk.builder().setPropagators(propagators)...
```

- **Why a propagator and not ad-hoc header copying?** A `TextMapPropagator` plugs into the *same* inject/extract machinery the SDK and all auto-instrumentation already call at every HTTP/gRPC boundary — so the legacy id flows automatically on *every* hop without touching call sites. `composite(...)` runs each propagator in turn, so W3C trace context, baggage, *and* your custom header all round-trip together.
- **When is custom justified?** Bridging a legacy/partner system's correlation header during a migration; carrying a non-trace cross-cutting id (request id, idempotency key) you want on every hop; or supporting a vendor format OTel doesn't ship. It is *not* justified for putting business data on the wire (use baggage, carefully — Q44) or for anything W3C/B3 already covers.
- **Edge cases:** `fields()` must list every header you set (used to clear/avoid leaking on re-injection); never put secrets/PII in a propagated header (it crosses trust boundaries, Q44); keep it tiny (every byte rides every request). Order in `composite` doesn't change correctness here but later propagators can overwrite earlier-set keys, so don't collide on header names.

#### Q84. [Practical] Design observability for a Kubernetes platform: what runs as a DaemonSet vs Deployment vs sidecar, and how do you avoid the per-pod-label cardinality trap?

Observability *deployment topology* on Kubernetes is itself a design question, and getting it wrong produces either gaps (some pods uninstrumented) or cost blowups (per-pod cardinality). The standard layered architecture maps each concern to the right Kubernetes primitive.

```
┌─ Node (every node) ───────────────────────────────────────────────┐
│  node-exporter        (DaemonSet)  -> host CPU/mem/disk (USE)       │
│  OTel Collector agent (DaemonSet)  -> receive local pods' OTLP,     │
│        log tailing (/var/log)        offload, add k8s resource attrs │
└────────────────────────────────────────────────────────────────────┘
        │ OTLP (agent -> gateway)
        ▼
┌─ Gateway (Deployment, N replicas) ────────────────────────────────┐
│  OTel Collector gateway -> tail sampling, redaction, fan-out export │
│  (two-tier load-balancing for tail sampling, Q70)                   │
└────────────────────────────────────────────────────────────────────┘
  kube-state-metrics (Deployment, 1) -> object state (deploys, pods)
  Prometheus/Agent (StatefulSet)      -> scrape, store/forward
  app pods: OTel SDK or auto-instrument; mesh sidecar (optional) for RED
```

The placement reasoning:

- **DaemonSet** for anything that must run *once per node*: `node-exporter` (host USE metrics), the **OTel Collector agent** (a local offload point so apps export to `localhost` with no network hop and the agent adds node/pod resource attributes), and log collection (tailing each node's container logs). One per node, scales with the cluster automatically.
- **Deployment/StatefulSet** for *cluster-scoped singletons or pools*: `kube-state-metrics` (one instance translating the K8s API into metrics about objects), and the **gateway Collector** (a horizontally scaled pool that does centralized policy — tail sampling, redaction, routing). Prometheus is typically a StatefulSet (it has persistent local TSDB).
- **Sidecar** (per-pod) only when you need *per-pod isolation or in-pod access*: a service-mesh proxy (Envoy) for uniform RED metrics and mTLS, or a sidecar Collector for a pod with special redaction needs. Sidecars cost a container per pod, so prefer the node-level DaemonSet agent unless you specifically need per-pod scope.

**The per-pod-label cardinality trap** is the killer mistake here: Kubernetes pods are *ephemeral* — every deploy/rollout/restart creates pods with *new* names (`orders-7f9c-abcde`), and if `pod` (or `pod_template_hash`, `replicaset`) becomes a metric label, your series count churns and grows without bound (Q14, Q41). Each rollout multiplies series, and stale ones linger for the staleness window. The fixes: aggregate away the `pod` label at scrape time with `metric_relabel_configs` (keep `service`/`namespace`, drop `pod`/`pod_template_hash`) for *metrics*, and keep the high-cardinality pod identity in *logs/traces resource attributes* (where it's not indexed per-dimension) so you can still find a specific pod when debugging. The senior framing: instrument by **stable identity** (service, namespace, deployment) for metrics and reserve **ephemeral identity** (pod, container, node) for the trace/log resource context — matching cardinality to the signal that can afford it.

#### Q85. [Coding] Keep metrics-as-code honest: write a unit test / CI lint that fails the build if a metric uses an unbounded label or violates naming conventions. Why shift this left?

Cardinality explosions (Q14) and naming drift (Q45) are far cheaper to stop in code review than in a 3 a.m. TSDB OOM. The defense is to make "you added a dangerous label" a *failing test*, so the paved road is enforced by CI, not by hope. Here's a JUnit-style test that scrapes the app's own registry and asserts policy.

```java
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MetricsConventionTest {
    // Labels that are NEVER allowed as metric tag KEYS (unbounded identifiers).
    static final Set<String> FORBIDDEN_TAG_KEYS = Set.of(
        "user_id","order_id","request_id","trace_id","email","session_id","path","url");

    @Test
    void metrics_obey_naming_and_cardinality_policy() {
        MeterRegistry reg = new SimpleMeterRegistry();
        new OrderService(reg);                 // exercise the code that registers meters
        new PaymentClient(reg);                // ... all instrumented components ...

        List<String> violations = new ArrayList<>();
        for (Meter m : reg.getMeters()) {
            String name = m.getId().getName();
            // 1) Naming: snake/dot case, no camelCase, counters end in .total/_total.
            if (!name.matches("[a-z][a-z0-9_.]*"))
                violations.add("bad name: " + name);
            if (m.getId().getType() == Meter.Type.COUNTER && !name.endsWith("total"))
                violations.add("counter not *_total: " + name);
            // 2) Cardinality: no forbidden tag keys.
            for (Tag t : m.getId().getTags()) {
                if (FORBIDDEN_TAG_KEYS.contains(t.getKey().toLowerCase()))
                    violations.add("forbidden label '" + t.getKey() + "' on " + name);
                // 3) Heuristic: a tag VALUE that looks like a raw id/path is suspicious.
                if (t.getValue().matches(".*\\d{4,}.*") || t.getValue().contains("/"))
                    violations.add("high-cardinality value '" + t.getValue() + "' on " + name);
            }
        }
        assertTrue(violations.isEmpty(), "Metric policy violations:\n" + String.join("\n", violations));
    }
}
```

- **Why shift left?** A high-cardinality label that reaches production can OOM a *shared* TSDB and blind every team (Q41); catching it in a PR's CI run is orders of magnitude cheaper and blameless. The test turns an operational landmine into a code-review comment.
- **What it catches:** forbidden tag *keys* (the unbounded-identifier class), naming-convention drift (camelCase, missing `_total`), and — via a value heuristic — a *constant-keyed* tag whose *values* are actually unbounded (e.g., `tag("path", req.getPath())` where the value is a raw URL). The value heuristic is necessarily fuzzy, so make it a warning or allow an explicit annotation-based opt-out for legitimate cases.
- **Edge cases / limits:** a unit test only sees meters registered in the code paths it exercises, so pair it with a *runtime* guard (Prometheus `sample_limit`/`label_limit`, Q41) for defense in depth — the test catches the known, the scrape limit catches the unknown. Keep the forbidden list and conventions in one shared module so every service's test imports the same policy (consistency, Q45). You can extend the same idea to a Collector-side check or a periodic `/status/tsdb` audit that flags the top-cardinality metrics.

#### Q86. [Practical] How would you use eBPF auto-instrumentation (Grafana Beyla / Pixie) to add observability to a service you cannot or will not modify? What do you get, what's missing, and how do you bridge the gap?

A recurring real-world constraint: a legacy service, a third-party binary, or a polyglot fleet where adding SDK instrumentation to every language is infeasible. **eBPF** (Q42) attaches probes in the *kernel* to observe syscalls and network/L7 traffic with zero code changes — Grafana **Beyla**, **Pixie**, and Cilium use it to auto-generate RED metrics and basic spans for HTTP/gRPC/SQL by watching the sockets.

```yaml
# Grafana Beyla as a sidecar/daemon, instrumenting a process by executable name,
# exporting OTLP to your existing Collector — no app code change.
apiVersion: apps/v1
kind: DaemonSet     # one Beyla per node, watches local processes
metadata: { name: beyla }
spec:
  template:
    spec:
      hostPID: true                 # needs to see host processes
      containers:
      - name: beyla
        image: grafana/beyla:latest
        securityContext:
          privileged: true          # eBPF needs elevated capabilities (CAP_BPF/SYS_ADMIN)
        env:
        - { name: BEYLA_OPEN_PORT, value: "8080" }          # which port/proc to watch
        - { name: OTEL_EXPORTER_OTLP_ENDPOINT, value: "http://otel-collector:4317" }
        - { name: BEYLA_TRACE_PRINTER, value: "disabled" }
```

**What you get for free:** RED metrics (rate/errors/duration) per endpoint, basic server spans for HTTP/gRPC, and protocol-level visibility (status codes, routes) — language-agnostic, at low overhead, with *no* redeploy of the target. For "I need *some* observability on this opaque service *today*," it's transformative.

**What's missing — and this is the senior part:** eBPF sees the *wire and syscalls*, not the *application's intent or context*. So you lose: (1) **in-process detail** — it can tell you a request took 2s but not *which business operation* or which function (no self-time breakdown, Q63); (2) **reliable end-to-end trace propagation** — eBPF can't easily *inject/read* the app-controlled `traceparent` across async hops, so traces tend to be per-hop fragments rather than a stitched distributed trace (some tools do limited header reading, but custom async boundaries defeat it); (3) **custom business attributes** (tenant, order value) you'd want for slicing; (4) **encrypted payload internals** without extra uprobe tricks.

**How I'd bridge the gap:** use eBPF as the *baseline coverage* layer — every service, including the un-instrumentable ones, gets RED metrics and edge spans immediately — and then *layer* targeted SDK/agent instrumentation (Q61) on the high-value services where I need business context and reliable propagation. Critically, I'd **export eBPF telemetry as OTLP into the same Collector** (as above) so it joins the same pipeline, sampling, and backends — eBPF and SDK spans coexist and correlate by `trace_id` where propagation works. The framing for an interview: eBPF gives **breadth without consent** (cheap, universal, shallow); SDK gives **depth with consent** (rich, per-language, propagating); a mature platform uses eBPF to eliminate blind spots and SDK to get deep where it matters — not one or the other.

#### Q87. [Coding] Implement RED metrics with the *OpenTelemetry Metrics API* directly (not Micrometer), including an observable gauge. Contrast the push (OTLP) and pull (Prometheus) export paths.

It's worth knowing the *native* OTel Metrics API (distinct from Micrometer, Q6), because OTel is the vendor-neutral surface and because its **observable** (callback) instruments and **views** behave differently from Micrometer's. Here's RED — a request counter, an error counter, and a latency histogram — plus an observable gauge for a live value, on the OTel API.

```java
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.*;

public class RedMetrics {
    private final LongCounter requests;
    private final DoubleHistogram latency;

    public RedMetrics(OpenTelemetry otel, Queue<?> workQueue) {
        Meter meter = otel.getMeter("orders");
        // Rate + Errors: a counter dimensioned by outcome (BOUNDED label values only).
        this.requests = meter.counterBuilder("http.server.requests")
                .setDescription("HTTP requests").setUnit("{request}").build();
        // Duration: an explicit-bucket or exponential histogram.
        this.latency = meter.histogramBuilder("http.server.duration")
                .setUnit("s").build();
        // Saturation as an OBSERVABLE (async) gauge: a callback the SDK invokes at export time.
        meter.gaugeBuilder("work.queue.depth").ofLongs()
                .buildWithCallback(obs -> obs.record(workQueue.size(),
                        Attributes.builder().put("queue", "orders").build()));
    }

    public void record(String route, int status, double seconds) {
        Attributes attrs = Attributes.builder()
                .put("http.route", route)                 // template, bounded
                .put("http.response.status_code", status)
                .build();
        requests.add(1, attrs);
        latency.record(seconds, attrs);                   // R, E (via status), D in two instruments
    }
}
```

- **Synchronous vs observable instruments:** `counterBuilder`/`histogramBuilder` are *synchronous* — you call `add`/`record` at the event. `gaugeBuilder(...).buildWithCallback(...)` is *observable* — the SDK invokes your callback at each *collection*, so a gauge reflects the value *at export time* (perfect for "current queue depth"), avoiding the "gauge missed a spike" issue only if the thing is genuinely a level, not a distribution (Q31). Observables are the OTel-idiomatic way to read an existing live value without manually polling.
- **Push (OTLP) vs pull (Prometheus) export — the same instruments, two delivery paths:**

```
OTLP push:  SDK PeriodicMetricReader ──every 60s──► OTLP ─► Collector ─► backend
            (delta or cumulative temporality, Q38; SDK decides cadence)
Prometheus pull: SDK PrometheusHttpServer exposes /metrics ◄──scrape── Prometheus
            (always CUMULATIVE; scraper decides cadence; histogram -> _bucket lines)
```

- The *instrument code is identical*; only the **reader** differs. A `PeriodicMetricReader` + OTLP exporter *pushes* on a timer (and can emit *delta* temporality, friendly to serverless, Q38). A `PrometheusHttpServer` reader exposes `/metrics` for Prometheus to *pull* (always cumulative, Q38). This is the OTel decoupling: change the export model without touching instrumentation.
- **Edge cases:** keep attribute *keys and values* bounded (same cardinality discipline, Q14) — OTel won't stop you adding `user_id`; use **Views** to drop/rename attributes or change a histogram's bucket boundaries centrally in the SDK config; observable gauge callbacks must be cheap and non-blocking (they run on the collection thread) and must be idempotent reads, not mutations.

#### Q88. [Coding] Keep dashboards-as-code: write a Grafana panel/alert definition in JSON and explain why dashboards-as-code beats click-ops. What's the testable contract?

Click-built dashboards rot: they drift, aren't reviewed, can't be templated across services, and vanish when someone deletes them. **Dashboards-as-code** (Grafana JSON in Git, generated via Grafonnet/Jsonnet or Terraform's `grafana` provider) makes them versioned, reviewed, and *templated from a standard library* so every new service gets the paved-road dashboard (Q52) automatically.

```json
{
  "title": "checkout — RED",
  "templating": { "list": [
    { "name": "service", "type": "query", "query": "label_values(http_server_requests_total, service)" },
    { "name": "region",  "type": "query", "query": "label_values(http_server_requests_total, region)" }
  ]},
  "panels": [
    {
      "title": "Error ratio (with SLO threshold)",
      "type": "timeseries",
      "targets": [{
        "expr": "sum(rate(http_server_requests_total{service=\"$service\",code=~\"5..\"}[5m])) / sum(rate(http_server_requests_total{service=\"$service\"}[5m]))",
        "exemplar": true
      }],
      "thresholds": { "steps": [ {"value": null, "color": "green"}, {"value": 0.001, "color": "red"} ] },
      "fieldConfig": { "defaults": { "unit": "percentunit" } }
    },
    {
      "title": "Latency p50/p95/p99",
      "type": "timeseries",
      "targets": [
        { "expr": "histogram_quantile(0.50, sum by (le)(rate(http_server_duration_seconds_bucket{service=\"$service\"}[5m])))", "legendFormat": "p50" },
        { "expr": "histogram_quantile(0.95, sum by (le)(rate(http_server_duration_seconds_bucket{service=\"$service\"}[5m])))", "legendFormat": "p95" },
        { "expr": "histogram_quantile(0.99, sum by (le)(rate(http_server_duration_seconds_bucket{service=\"$service\"}[5m])))", "legendFormat": "p99" }
      ]
    }
  ],
  "annotations": { "list": [
    { "name": "deploys", "datasource": "Prometheus",
      "expr": "changes(kube_deployment_status_observed_generation{deployment=\"$service\"}[5m]) > 0" }
  ]}
}
```

- **Why as-code beats click-ops:** it's **reviewable** (a PR diff shows exactly what changed and why), **versioned/rollback-able** (a bad dashboard edit is `git revert`), **templated** (`$service`/`$region` variables mean one definition serves the whole fleet — no copy-paste-and-drift), and **consistent** (every service's RED dashboard is identical, so on-call has muscle memory, Q52). Generated via Grafonnet you can *parameterize* a function `redDashboard(service)` and emit one per service from a list.
- **The testable contract:** because it's code, you can **lint and test** it in CI — assert every panel's PromQL is valid (run it against a Prometheus test instance or `promtool`), assert latency panels use `histogram_quantile` not `avg` (anti-pattern guard, Q52), assert the error panel has the SLO threshold line, assert deploy annotations exist. The dashboard becomes a *checked artifact*, not a snowflake.
- **Edge cases:** keep the *data source* a variable so the same JSON works across environments; `exemplar: true` on the target wires the metric→trace drill-down (Q69) — only works if your backend stores exemplars; deploy annotations (the single highest-value correlation, Q52) come from a metric like `changes(...generation...)`. The discipline: dashboards live in the service's repo next to the code they observe, shipped by the paved road, reviewed like any other change.

#### Q89. [Theory] Explain trace-to-profile and trace-to-logs *correlation* end to end: what identifiers and configuration make "click a slow span → see its flame graph and its logs" actually work?

The headline promise of correlated observability (Q20, Q46, Q55) is one-click pivoting between pillars, but it only works if specific *identifiers* are present and *consistently propagated* through every signal. Knowing the exact join keys is what separates "we have all three pillars" from "they're actually linked."

```
                     metric bucket (slow)
                          │  exemplar carries trace_id  (Q20, Q69)
                          ▼
        ┌──────────── TRACE (trace_id, span_id) ─────────────┐
 logs ◄─┤  join key: log line carries trace_id + span_id     ├─► profile
 (Q55)  │  (auto via OTel log signal / MDC, Q39/Q62)         │  (Q46)
        └────────────────────────────────────────────────────┘
                          ▲                         ▲
            log backend datasource link    profiling labels include
            on trace_id field              span_id + service.name
```

The required identifiers and config, link by link:

- **Metric → trace:** the metric must carry **exemplars** (Q69) — a `trace_id` attached to the histogram bucket — and the backend (Prometheus exemplar storage + Grafana) must surface them as clickable. Without exemplars, you can see the p99 is bad but have no jump-off point.
- **Trace ↔ logs:** every log line must carry the **`trace_id`** (and ideally `span_id`), stamped via MDC or the OTel log signal (Q39, Q55, Q62), *and* the trace backend must be configured with a **derived field / data link** that knows how to query the log backend by `trace_id` (Grafana Tempo's `tracesToLogs`, Loki's trace-id label). The identifier is necessary but not sufficient — the *backend wiring* (which datasource, which query) is the other half.
- **Trace → profile:** the profiler must label each profile sample with **`span_id`** (or at least `service.name` + a time window) so the backend can extract the flame graph for *exactly that span's execution* (Grafana Pyroscope's `tracesToProfiles`, Datadog's code hotspots). This requires the profiler and tracer to share context — the profiler reads the active `span_id` from the same OTel context (Q46).

The end-to-end discipline a senior should articulate: correlation is a **chain of shared identifiers plus backend link configuration**, and it breaks at the weakest link. If async context propagation drops the `trace_id` (Q39/Q76), logs lose their join key. If sampling is inconsistent (Q53), the exemplar points to a *dropped* trace (dead link). If the profiler doesn't tag `span_id`, you get a fleet-wide flame graph instead of *this span's*. So the prerequisites are: **consistent context propagation everywhere** (the `trace_id`/`span_id` must survive every hop and thread boundary), **sampling consistency** (so linked traces actually exist), and **backend data-link configuration** (the queries that turn an id into a pivot). When all three hold, MTTR collapses (Q56): metric spike → exemplar → trace → self-time span → its flame graph and its exact logs, in clicks instead of hours.

#### Q90. [Practical] You're handed a service with zero observability and a "make it observable" mandate. What's your prioritized, pragmatic rollout order — and why that order?

A blank-slate service is a chance to show *judgment about sequencing*: you can't do everything at once, and the wrong order wastes effort (e.g., perfect tracing with no alerting means you find out about outages from customers). I'd roll out in the order that maximizes *incident-readiness per unit of effort*, roughly fastest-payoff first.

```
1. Health + RED metrics + a SLO   ─► "is it up? is it broken? how broken?"  (detect)
2. Structured logs with trace_id   ─► "what happened?"                        (explain)
3. Distributed tracing (propagate)  ─► "where across services?"               (localize)
4. Dashboards-as-code + alerts      ─► turn signals into action               (respond)
5. Exemplars/correlation + profiling─► one-click pivot, code-level            (accelerate)
6. Cost/cardinality guardrails      ─► keep it sustainable                    (sustain)
```

The reasoning for *this* order:

1. **Golden-signals / RED metrics + a `/healthz` + one SLO first.** Cheapest to add (a metrics library + a few lines), and it answers the only question that matters at 3 a.m. — *is it healthy?* An SLO + a burn-rate alert (Q9/Q35) means you find out *before* customers do. Detection capability has the highest payoff per line of code, so it goes first.
2. **Structured logs with `trace_id` in MDC** next (Q62). Cheap, and the moment you have an incident you need the *why*; structured + correlated logs are useless to add *during* the fire, so front-load them. This alone makes the service debuggable in isolation.
3. **Distributed tracing with context propagation** (Q61/Q65) — more effort (propagation across hops, the paved-road client), and it pays off specifically for *cross-service* latency/error localization. Worth doing early but after the basics, because a single service can be debugged with metrics+logs; tracing earns its keep once you're chasing latency *across* services.
4. **Dashboards-as-code and the alert routing** (Q66/Q88) — now that signals exist, make them *actionable*: a paved-road RED dashboard, symptom-based alerts, runbook links. Signals without dashboards/alerts are latent; this step activates them.
5. **Correlation (exemplars) and profiling** (Q69/Q46/Q89) — the MTTR accelerators. They multiply the value of everything above (one-click metric→trace→logs→flame graph) but presuppose the lower layers exist, so they come after.
6. **Cost and cardinality guardrails** (Q41/Q85) — woven in throughout (bounded labels from day one) but formalized last as budgets/limits/CI lints, so the system stays affordable and doesn't OOM the shared backend as it grows.

The pragmatic judgment to voice: I'd lean on the **paved-road library/agent** so steps 1–3 arrive *together and for free* (auto-instrumentation gives RED + trace propagation + log correlation in one dependency), collapsing the early stages — and I'd *resist* the temptation to start with elaborate custom tracing or pretty dashboards before there's a single SLO and a working page. The ordering principle is **detect → explain → localize → respond → accelerate → sustain**: each layer is only useful once the one before it exists, and the earliest layers buy the most incident-readiness for the least work.

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
