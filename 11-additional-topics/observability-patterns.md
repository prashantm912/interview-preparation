# Advanced Observability Patterns

A staff-level guide to modern observability: OpenTelemetry internals, trace-context propagation, sampling economics, eBPF, continuous profiling, and SLO-driven alerting — with Java-centric examples and trade-offs you can defend in an interview.

[← Back to master index](../README.md)

## Table of Contents
- [🟢 Basic (0–2 yrs)](#-basic-02-yrs)
- [🟡 Intermediate (3–7 yrs)](#-intermediate-37-yrs)
- [🟠 Advanced (8–12 yrs)](#-advanced-812-yrs)
- [🔴 Expert (15+ yrs)](#-expert-15-yrs)

---

## 🟢 Basic (0–2 yrs)

### Q1. [Theory] What are the "three pillars" of observability, and why is the framing now considered incomplete?
The classic three pillars are **metrics** (aggregated numeric time series — counters, gauges, histograms), **logs** (discrete timestamped events), and **traces** (causally linked spans showing a request's path across services). The framing is useful for beginners but increasingly criticized because it describes *data types* rather than *capability*. Real observability is the ability to ask **arbitrary new questions** about your system without shipping new code. Three siloed pipelines that can't be correlated give you three dashboards and no answers. Modern thinking (Charity Majors et al.) favors **wide, high-cardinality structured events** that can be sliced into metrics, traces, or logs as needed. The practical upgrade is **correlation**: a metric spike should link to exemplar traces, and a trace should link to the logs emitted within its spans.

### Q2. [Theory] Distinguish a span, a trace, and a trace context.
A **span** is a single unit of work — a name, start/end timestamps, attributes (key/value tags), events, and a status. A **trace** is a tree (DAG, really) of spans sharing one trace ID, representing one end-to-end operation. **Trace context** is the small bundle of identifiers — trace ID, parent span ID, and sampling flags — that must travel between services so child spans attach to the right parent.

```
Trace (traceId=abc123)
└─ Span: HTTP GET /checkout        [root, 240ms]
   ├─ Span: validateCart           [12ms]
   ├─ Span: charge-payment (RPC)   [180ms]   ← child service, same traceId
   │   └─ Span: stripe.api.call    [160ms]
   └─ Span: publish OrderPlaced    [8ms]
```

### Q3. [Practical] Your service emits logs but no traces. What is the cheapest first step toward real observability?
Adopt **structured logging** with a consistent JSON schema and inject the **trace and span IDs** into the MDC (Mapped Diagnostic Context) so every log line is correlatable. In Spring Boot 3, Micrometer Tracing does this automatically — `traceId` and `spanId` appear in the log pattern. Then add OpenTelemetry auto-instrumentation via the Java agent (`-javaagent:opentelemetry-javaagent.jar`) — zero code changes, and you get HTTP, JDBC, and messaging spans for free. The trade-off: auto-instrumentation produces generic span names and can over-instrument noisy libraries, but it's the fastest path from "blind" to "correlatable" and you can refine with manual spans later.

### Q4. [Coding] Create a manual span around a business operation using the OpenTelemetry Java API.
**Problem:** Auto-instrumentation captures HTTP and DB calls but not your domain logic. Wrap a `reserveInventory` call in a span with attributes and proper error/status handling.

```java
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.context.Scope;

public class InventoryService {
    private final Tracer tracer =
        GlobalOpenTelemetry.getTracer("com.shop.inventory", "1.0.0");

    public Reservation reserve(String sku, int qty) {
        Span span = tracer.spanBuilder("reserveInventory")
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute("inventory.sku", sku)
            .setAttribute("inventory.qty", qty)
            .startSpan();
        // try-with-resources makes this span "current" so child spans nest correctly
        try (Scope scope = span.makeCurrent()) {
            Reservation r = doReserve(sku, qty);   // child spans auto-attach
            span.setStatus(StatusCode.OK);
            return r;
        } catch (OutOfStockException e) {
            span.setStatus(StatusCode.ERROR, "out of stock");
            span.recordException(e);
            throw e;
        } finally {
            span.end();   // MUST end in finally, else span leaks
        }
    }
}
```
**Time/Space:** O(1) overhead per span; memory bounded by attribute count. **Edge cases:** never forget `span.end()` (leaks unfinished spans and corrupts the active-context stack); `makeCurrent()` must be closed on the *same thread* — for thread pools you must propagate context explicitly (see Q9).

---

## 🟡 Intermediate (3–7 yrs)

### Q5. [Theory] Explain W3C `traceparent` propagation. What does each field mean and why standardize it?
`traceparent` is an HTTP header standardizing how trace context crosses service boundaries, replacing vendor-specific headers (Zipkin's `X-B3-*`, Jaeger's `uber-trace-id`). Format:

```
traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
             │  └────────── trace-id (16B) ──────┘ └─ parent-id ─┘ └ flags
             version                                  (span 8B)     (sampled bit)
```
- **version** `00` — current spec version.
- **trace-id** — 16 bytes, globally unique per trace.
- **parent-id** — the calling span's ID; becomes the child's parent.
- **trace-flags** — bitfield; bit 0 is the **sampled** flag, telling downstream whether this trace is being recorded.

A companion `tracestate` header carries vendor-specific key/values. Standardization matters because in a polyglot mesh (Java + Go + Node) every hop must agree on the wire format, or the trace fragments into disconnected pieces. The sampled flag enables **head-based sampling decisions to propagate**, so either the whole trace is kept or none of it is.

### Q6. [Theory] Head-based vs tail-based sampling — when do you choose each?
**Head-based** decides at the trace's *start* (the root) whether to keep it, then propagates that decision via the sampled flag. It's cheap and stateless (each service just honors the flag) but **blind**: you commit before knowing if the trace contained an error or was slow, so you statistically drop the interesting 0.1%. **Tail-based** buffers *all* spans of a trace (typically in the OTel Collector) until the trace completes, then applies policies — keep if `error=true`, keep if `latency > 2s`, keep 1% of the rest. It captures every anomaly but requires the collector to hold spans in memory long enough to assemble the full trace, demands that all spans for a trace route to the **same collector instance** (consistent-hash load balancing on trace ID), and adds latency/cost.

```
HEAD-BASED                          TAIL-BASED
root decides → keep/drop            all spans → [collector buffer] → policy
   ↓ flag propagated                          ↓ wait for trace complete
cheap, blind to errors             ↓ keep errors + slow + 1% baseline
                                   expensive, anomaly-complete
```
Rule of thumb: start head-based for cost simplicity; move to tail-based once you're losing visibility into rare failures and can afford collector statefulness.

### Q7. [Practical] A metric shows p99 latency spiked, but you can't find a matching trace. How do exemplars solve this?
An **exemplar** is a sample trace ID attached to a specific histogram bucket at scrape time. When Prometheus (or an OTLP metric) records that a request fell into the "1s–2.5s" latency bucket, it also stores *one example trace ID* that landed there. In Grafana you click the spike on the p99 graph and jump directly to a trace that *caused* it — closing the metrics↔traces gap. Without exemplars you see "something was slow at 14:32" but must hunt through thousands of traces by timestamp. In Java with Micrometer, enable exemplars by using a `Tracer`-aware `MeterRegistry`; the registry samples the current span's trace ID into the bucket.

```
p99 latency ▁▁▁█▁▁  ← click the spike
                │
                └─ exemplar traceId=abc → opens the actual slow trace
```
Trade-off: exemplars need the metrics backend (Prometheus 2.26+ with the OpenMetrics exemplar format, or an OTLP-native store) to support them, and they store only one sample per bucket per scrape — representative, not exhaustive.

### Q8. [Theory] Walk through the OpenTelemetry Collector architecture: receivers, processors, exporters.
The Collector is a vendor-neutral pipeline that decouples your apps from your backend. A **receiver** ingests data (OTLP gRPC/HTTP, Prometheus scrape, Jaeger, Zipkin, Kafka). A **processor** transforms it in-flight — `batch` (group for efficient export), `memory_limiter` (drop/throttle to avoid OOM), `tail_sampling`, `attributes` (redact PII, add `k8s` metadata), `resourcedetection`. An **exporter** ships to a backend (OTLP to a SaaS, Prometheus remote-write, S3). Pipelines wire these per signal type.

```yaml
receivers:  { otlp: { protocols: { grpc: {}, http: {} } } }
processors:
  memory_limiter: { limit_mib: 1500 }
  batch:          { timeout: 5s }
  attributes:     { actions: [ { key: user.email, action: delete } ] }  # PII scrub
exporters:  { otlphttp: { endpoint: https://backend:4318 } }
service:
  pipelines:
    traces: { receivers: [otlp], processors: [memory_limiter, attributes, batch], exporters: [otlphttp] }
```
**Why it matters:** the Collector lets you switch vendors, sample, and scrub PII centrally without redeploying every microservice. **Deployment patterns:** *agent* (DaemonSet/sidecar per node for collection) feeding a *gateway* (horizontally scaled, does tail-sampling and egress). Always put `memory_limiter` first and `batch` last among processors.

### Q9. [Coding] Propagate trace context across an async boundary (thread pool / message queue).
**Problem:** Submitting work to an `ExecutorService` runs it on a different thread; OTel's context is thread-local, so the child span detaches from the parent. Fix it for both threads and a Kafka producer/consumer.

```java
import io.opentelemetry.context.Context;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.context.propagation.*;
import java.util.*;

// 1) ASYNC THREAD POOL: capture current context, re-attach in the worker.
ExecutorService pool = Executors.newFixedThreadPool(8);
Context parent = Context.current();                 // captured on caller thread
pool.submit(() -> {
    try (var scope = parent.makeCurrent()) {        // re-attach on worker thread
        process();                                  // spans now nest under parent
    }
});
// Even simpler: wrap the executor — OTel auto-instrumentation often does this,
// but explicit Context.taskWrapping(pool) is the safe manual form:
ExecutorService traced = Context.taskWrapping(pool);

// 2) MESSAGE QUEUE: inject context into headers on produce, extract on consume.
TextMapPropagator prop = GlobalOpenTelemetry.getPropagators().getTextMapPropagator();

// Producer side — inject into Kafka record headers
Map<String, String> carrier = new HashMap<>();
prop.inject(Context.current(), carrier, (c, k, v) -> c.put(k, v));
carrier.forEach((k, v) -> record.headers().add(k, v.getBytes()));

// Consumer side — extract to continue the SAME trace
Context extracted = prop.extract(Context.current(), record.headers(),
    new TextMapGetter<>() {
        public String get(org.apache.kafka.common.header.Headers h, String key) {
            var hdr = h.lastHeader(key);
            return hdr == null ? null : new String(hdr.value());
        }
        public Iterable<String> keys(org.apache.kafka.common.header.Headers h) {
            var ks = new ArrayList<String>();
            h.forEach(x -> ks.add(x.key()));
            return ks;
        }
    });
try (var scope = extracted.makeCurrent()) {
    handle(record);   // consumer span links back to the producer's trace
}
```
**Time/Space:** O(1) context copy; carrier holds ~2 small headers. **Edge cases:** for queues with **fan-out / batching**, a single consumer span may have *multiple* parents — model this with **span links** rather than a single parent (see Q13). Always strip context headers from carriers you log to avoid leaking IDs into untrusted sinks.

### Q10. [Practical] What is high cardinality, why does it break metrics systems, and how do you tame it?
**Cardinality** = the number of unique label combinations on a metric. A counter `http_requests_total{method, status, endpoint}` has manageable cardinality; add `{user_id}` or `{trace_id}` and you create a separate time series *per user* — millions of series, exploding memory and cost in Prometheus (each series is a chunk in RAM). The fix isn't to ban detail; it's to **put high-cardinality data where it belongs**:
- Keep metrics **low-cardinality** (bounded label sets: status class `2xx/4xx/5xx`, not raw status; route template `/users/{id}`, not the rendered path).
- Push high-cardinality dimensions (user ID, request ID, SKU) into **traces and wide events**, which are stored differently (columnar, sampled) and designed for it.
- Use **recording rules** to pre-aggregate, and **metric relabeling** in the Collector/scrape config to drop offending labels.

```
GOOD metric:   http_req{route="/o/{id}", status_class="5xx"}   ← bounded
BAD metric:    http_req{path="/o/9f3...", user="u_8821"}        ← unbounded
High-card data → traces/events, queried in a columnar store (e.g. Honeycomb)
```
Production move: alert on a `prometheus_tsdb_head_series` ceiling, and reject new high-cardinality labels in CI by linting metric definitions.

### Q11. [Theory] How does the RED method differ from USE, and when do you apply each?
**RED** (Rate, Errors, Duration) is for **request-driven services** — measure request rate, error rate, and latency distribution per endpoint. **USE** (Utilization, Saturation, Errors) is for **resources** — CPU, memory, disk, queues — measure how busy it is, how much work is queued/waiting, and error counts. They're complementary: RED tells you the *user-visible symptom* ("checkout is erroring at 3%"), USE tells you the *resource cause* ("the DB connection pool is saturated"). A mature dashboard pairs them: a RED panel per service plus USE panels for its dependencies, so on-call can pivot from symptom to cause in seconds. The "**Four Golden Signals**" (Google SRE) — latency, traffic, errors, saturation — is essentially RED + saturation and is the most quoted variant.

---

## 🟠 Advanced (8–12 yrs)

### Q12. [Theory] Explain eBPF-based observability (Pixie, Cilium). What does it give you that SDK instrumentation cannot?
**eBPF** lets you run sandboxed programs in the Linux kernel, attached to syscalls, network events, and userspace probes (uprobes), without modifying or recompiling the kernel. Tools like **Pixie** and **Cilium/Hubble** use it to capture HTTP/gRPC/DNS/SQL traffic, latency, and flow data **automatically, with zero code changes and no language SDK**. The win: you instrument the *entire node* — including third-party binaries, legacy services, and languages you can't easily add an SDK to — and you see the real wire-level behavior. Overhead is low because work happens in-kernel without copying every packet to userspace. **Limits:** eBPF sees syscalls and network bytes, not your *business* context (it can't know `order_id` lives in a field) — so it gives breadth and L7 protocol decoding but not deep domain semantics. It also can't follow context across async hops the way SDK propagation can, and TLS payloads require uprobe hooks into the TLS library (`SSL_write`). Best practice: use eBPF for baseline coverage and service maps, OTel SDK for business-critical spans. **Security note:** loading eBPF needs `CAP_BPF`/privileged access — a powerful kernel surface that must be tightly controlled, and decrypted L7 payloads can expose PII.

### Q13. [Practical] Design observability for an event-driven system (Kafka + async consumers). What breaks and how do you fix it?
The core problem: **traces assume synchronous parent→child causality**, but events are fire-and-forget, fan out to many consumers, and are processed minutes later. A naive setup shows the producer span ending and the consumer span as an *orphan*.

```
Producer  ──publish OrderPlaced──▶  [Kafka topic]
                                        │  (decoupled in time)
                       ┌────────────────┼────────────────┐
                   Consumer A       Consumer B        Consumer C
                  (email)          (inventory)       (analytics)
```
Approach:
1. **Inject W3C context into message headers** on produce (Q9), extract on consume so each consumer continues the trace.
2. Model the consume side with **span links**, not a strict parent — one event can trigger many independent consumer traces, and one consumer batch can pull *many* messages (multiple "parents"). Links express "caused by" without forcing a single tree.
3. Add **queue-specific metrics**: consumer lag (the single most important EDA health metric), processing duration, retry/DLQ counts.
4. Use a **correlation/business ID** (e.g. `order_id`) as a span attribute *and* log field so you can reconstruct a saga across disconnected traces even when context propagation is imperfect.
What I'd do in production: OTel auto-instrumentation for Kafka clients (it injects/extracts headers automatically in recent versions), plus a dashboard keyed on consumer-group lag with an SLO, and tail-sampling that always keeps DLQ traces.

### Q14. [Theory] What is continuous profiling and how does it complement tracing? Mention Java specifics.
**Continuous profiling** samples stack traces across the whole fleet, continuously and at low overhead (~1–2%), producing flame graphs of where CPU, memory allocation, lock contention, or wall-clock time actually go — in *production*, not a lab. It complements tracing because a trace tells you *which span* was slow (180ms in `charge-payment`), but profiling tells you *why inside that span* (90% of time in JSON serialization, or a lock). The pairing is powerful: pivot from a slow trace directly to the flame graph for that time window. In the Java world, **JDK Flight Recorder (JFR)** is the near-zero-overhead built-in event recorder (GA and free since JDK 11), and async-profiler / Datadog / Grafana Pyroscope / Parca consume it or sample independently. OpenTelemetry added a **profiling signal** (OTLP profiles, stabilizing through 2025) so profiles share resource attributes with traces and metrics. **Trade-off:** profiling data is high-volume; you store aggregated/sampled flame graphs, not every stack, and symbolization of native frames needs debug symbols.

### Q15. [Practical] Your observability bill (a SaaS vendor) tripled. Give a concrete cost-control strategy.
Observability cost is driven by **data volume ingested and retention × cardinality**. A staged plan:
1. **Measure first** — find the top talkers: which services, which span names, which log levels, which metrics have runaway cardinality. Usually 80% of cost is 5% of sources (debug logs, health-check spans, a per-request-ID label).
2. **Sample traces** — tail-based, keeping 100% of errors/slow traces and ~1–5% baseline. Most successful, fast traces are redundant.
3. **Drop noise at the Collector** — filter health-check/readiness spans, drop `DEBUG` logs in prod (sample them at 1%), and use `metricstransform`/relabel to delete high-cardinality labels.
4. **Tier storage / retention** — keep raw data 7–15 days hot, roll up to aggregates for long-term; archive raw to cheap object storage (S3 + Parquet) for compliance.
5. **Convert logs to metrics/spans** — if you log a line just to count it, emit a counter instead; logs are the most expensive signal per insight.

```
Ingest 100% ──▶ [Collector] ──▶ keep: errors+slow+1% sample, drop DEBUG, scrub labels
                                 │
                       hot store (15d) ──roll-up──▶ aggregates (1y)
                                 └──raw archive──▶ S3/Parquet (cheap, queryable)
```
What I'd actually do: enforce a **cost SLO per team** with chargeback dashboards so the people generating the data see the bill — the strongest incentive against telemetry sprawl.

### Q16. [Coding] Implement a tail-sampling policy decision (the logic the Collector runs per completed trace).
**Problem:** Given a buffered trace (its spans), decide keep/drop: keep if any span errored, keep if total duration > 2s, otherwise keep with 5% probability — deterministically per trace ID so the decision is reproducible.

```java
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

record SpanInfo(String traceId, long durationMs, boolean error) {}

public class TailSampler {
    private static final long SLOW_MS = 2_000;
    private static final double BASE_RATE = 0.05;   // 5%

    public boolean shouldKeep(List<SpanInfo> trace) {
        if (trace.isEmpty()) return false;

        // Policy 1: keep any errored trace
        boolean anyError = trace.stream().anyMatch(SpanInfo::error);
        if (anyError) return true;

        // Policy 2: keep slow traces (root duration = max span duration here)
        long total = trace.stream().mapToLong(SpanInfo::durationMs).max().orElse(0);
        if (total > SLOW_MS) return true;

        // Policy 3: probabilistic, but DETERMINISTIC per traceId so all
        // collector replicas / re-evaluations agree (no flapping).
        String traceId = trace.get(0).traceId();
        long hash = Long.parseUnsignedLong(traceId.substring(0, 15), 16); // top bits
        double bucket = (double) (hash % 10_000) / 10_000.0;
        return bucket < BASE_RATE;
    }
}
```
**Time/Space:** O(n) over spans in the trace, O(1) extra space. **Edge cases:** the decision must be **deterministic on trace ID** (never `Math.random()`), otherwise scaled-out collectors disagree and you keep partial traces; the buffer needs a **decision timeout** (e.g. 30s) so a never-completing trace doesn't pin memory; very long traces need a max-span cap to bound buffer size; clock skew across services means you can't trust per-span timestamps for total duration — prefer the root span's own duration when available.

### Q17. [Theory] What is the difference between a correlation ID and a trace ID, and do you still need correlation IDs?
A **trace ID** is generated and managed by the tracing system to stitch spans into one trace; it's opaque, often dropped by sampling, and tied to the tracing backend's lifecycle. A **correlation ID** (a.k.a. request ID / business ID) is an application-level identifier — sometimes the order ID, sometimes a UUID minted at the edge — that you deliberately stamp on every log line and persist in your own data. You still want both: the trace ID gives you the *system-level* causal graph (sampled), while the correlation/business ID survives sampling, survives async gaps, and lets a support engineer search by a customer's order number across services and *non-traced* logs. Best practice: at the API gateway, mint a request ID if absent, put it in MDC, and also set it as a span attribute so the two worlds are joinable.

---

## 🔴 Expert (15+ yrs)

### Q18. [Theory] Define SLI/SLO/error budgets and explain how they should drive alerting instead of threshold alerts.
An **SLI** (Service Level Indicator) is a measured ratio of good events to valid events — e.g. `fraction of requests served < 300ms with 2xx/3xx`. An **SLO** (Objective) is the target — e.g. 99.9% over a rolling 28 days. The **error budget** is `1 − SLO` (0.1% ≈ 43 minutes/month of allowed badness) — a *quantified license to fail* that aligns reliability with feature velocity. The shift this forces: stop alerting on causes ("CPU > 80%") and start alerting on **symptoms via budget burn rate**. **Multi-window, multi-burn-rate** alerting is the standard: a *fast burn* (e.g. 14.4× budget consumption over 1h, confirmed by a 5m window) pages immediately because you'd exhaust a month's budget in ~2 days; a *slow burn* (3× over 6h) raises a ticket. This kills alert fatigue — you only page when users are actually being hurt at a rate that threatens the SLO — and it gives product and SRE a shared, dispassionate decision rule: budget healthy → ship features; budget exhausted → freeze and fix reliability.

```
burn rate = (observed error rate) / (1 - SLO)
 page  if 14.4x over 1h  AND  >14.4x over 5m   (confirm, avoid flapping)
 ticket if   3x over 6h  AND    >3x over 30m
budget left → feature work ; budget gone → reliability freeze
```

### Q19. [Practical] You're asked to introduce observability across 200 microservices owned by 40 teams. What's your rollout strategy and what cultural traps do you anticipate?
Technically: standardize on **OpenTelemetry** as the single API/SDK and ship a **Collector gateway** so teams emit OTLP and never touch vendor specifics — this is the decoupling that lets you renegotiate the vendor later. Provide a **paved-path SDK/starter** (a Spring Boot starter with sane defaults: resource attributes, propagators, sampling, PII scrubbers) so the default path is the correct path and adoption is `add one dependency`. Mandate a **semantic-convention attribute schema** (consistent `service.name`, `deployment.environment`, business IDs) — inconsistency is what makes 200 services un-joinable. Roll out by **landing zones**: instrument a few high-value request paths end-to-end first to prove value, publish the trace, then expand. Cultural traps: (1) teams instrument for *output volume* not *insight*, exploding cost — counter with chargeback; (2) every team invents its own attribute names — counter with a linter in CI and a schema registry; (3) dashboards become write-only — counter by tying on-call alerts to SLOs the team owns; (4) "we'll add tracing later" never happens — counter by making the paved path lower-effort than not using it. The real deliverable is a **platform team** owning the Collector, conventions, and golden dashboards, treating observability as a product with internal customers.

### Q20. [Behavioral] Tell me about a time observability data contradicted a senior leader's hypothesis during an incident. How did you handle it?
Use a STAR structure. **Situation:** a high-severity latency incident where leadership was convinced a recent deploy was the cause and pushing for an immediate rollback. **Task:** as the incident commander / staff engineer I owned getting to truth fast without letting hierarchy override evidence. **Action:** I pulled the RED dashboard and showed the latency rise *predated* the deploy by 20 minutes, then used an exemplar to jump from the p99 spike to a representative slow trace, which pointed at a saturated downstream DB connection pool (USE saturation), corroborated by the continuous-profiling flame graph showing time stuck in connection acquisition. I framed it neutrally — "the data shows X, here's the trace, let's verify the rollback hypothesis against it" — rather than contradicting the leader directly, and proposed a quick reversible test (raise pool size) before the more disruptive rollback. **Result:** we mitigated in minutes by fixing the pool, avoided an unnecessary rollback that would have masked the real bug, and I wrote a blameless postmortem that turned the saturation signal into a new SLO alert. The lesson I emphasize: observability's organizational value is that it replaces opinion-and-rank with evidence — but you have to present it as shared discovery, not as proving someone wrong.

### Q21. [Theory] Discuss the privacy, security, and compliance dimensions of a large observability deployment.
Telemetry is a **secondary data store of your most sensitive data** and is routinely under-governed. Spans, logs, and high-cardinality events leak PII (emails, tokens, full URLs with query params, request/response bodies), so you must **scrub at the source and again at the Collector** (`attributes`/`redaction` processors, deny-lists for headers like `Authorization` and `Cookie`). Trace IDs and correlation IDs can themselves be sensitive if they encode user identity. Compliance angles: **data residency** (GDPR may require EU telemetry to stay in-region — relevant when piping to a US SaaS), **retention limits** (don't keep PII-bearing traces for a year), and **right-to-erasure** (hard if a user's data is smeared across immutable trace storage — prefer pseudonymous IDs). The pipeline itself is an attack surface: the OTLP endpoint must be **authenticated and TLS-encrypted** (an open Collector receiver is an exfiltration and DoS vector), eBPF agents run with kernel privileges and can read decrypted payloads, and dashboards often have weaker access controls than the production data they mirror. Best practice: treat the observability platform as **in-scope for your security review and DPA**, classify telemetry by sensitivity, default to dropping bodies, and audit access. The version-relevant note (2024–2026): OpenTelemetry's semantic conventions increasingly mark attributes for sensitivity, and Collector redaction processors matured — lean on them rather than hand-rolling regexes.

### Q22. [Practical] How would you observe and debug a system where most logic now runs through LLM/agent calls? What's new about it?
LLM/agentic workloads break classic assumptions, so observability must adapt. Latency is dominated by **time-to-first-token and inter-token timing**, not a single duration — so spans need streaming-aware events, not just start/end. Cost is **per-token**, so you must capture prompt/completion token counts as span attributes and turn them into a cost metric and SLO. Quality is non-deterministic, so you instrument **eval signals** (groundedness, refusal rate, tool-call success) alongside latency. Traces become essential because an agent makes a **tree of tool calls and sub-LLM calls** — a single user request is inherently a multi-span trace, and without it you cannot see *why* the agent looped or hallucinated. OpenTelemetry's **GenAI semantic conventions** (stabilizing through 2025–2026) standardize attributes like `gen_ai.request.model`, token counts, and tool spans, so adopt those rather than inventing your own. Concretely I'd: emit a span per model call and per tool call with token + cost + latency attributes, sample-keep all traces with errors/refusals/high cost, log prompts/responses with **strict PII scrubbing and short retention** (prompts often contain user data), and alert on a **cost burn-rate SLO** and a quality-eval SLO — the two failure modes (runaway spend, degraded answers) that threshold CPU alerts will never catch. The genuinely new property is that the *content* of the telemetry (the prompts/outputs) is part of debugging, which collides hard with the privacy concerns in Q21.

---

## ✅ Key Takeaways
- Correlation beats pillars: wire trace IDs into logs, exemplars from metrics into traces, and a durable business/correlation ID through everything.
- Standardize on **OpenTelemetry + a Collector gateway** — it decouples apps from vendors and is where you centralize sampling, batching, and PII scrubbing.
- Keep **metrics low-cardinality**; push high-cardinality detail (user/request/SKU) into traces and wide events.
- **Tail-based sampling** captures rare errors/slow traces head-based sampling drops — at the cost of stateful, consistent-hashed collectors.
- Alert on **SLO error-budget burn rate** (multi-window, multi-burn), not on cause thresholds like CPU.
- **eBPF** gives zero-code fleet-wide breadth; **continuous profiling (JFR)** explains the *why* inside a slow span; SDK spans carry business semantics. Use all three.
- Treat the observability platform as a **product** (paved-path SDK, schema linting, chargeback) and as **in-scope for security/privacy review**.

## ⚠️ Common Pitfalls
- Forgetting `span.end()` in a `finally` block, or calling `makeCurrent()` on one thread and closing on another — both corrupt context.
- Losing trace context across thread pools and message queues because context is thread-local — inject/extract explicitly.
- Putting unbounded labels (`user_id`, `trace_id`, raw URL) on Prometheus metrics and melting the TSDB.
- Using `Math.random()` for sampling decisions instead of a deterministic trace-ID hash, so scaled-out collectors disagree and keep partial traces.
- Alerting on causes (CPU, memory) instead of symptoms (SLO burn), producing alert fatigue and missed user-facing outages.
- Shipping prompts, request bodies, and `Authorization` headers into traces/logs with long retention — a compliance and breach landmine.
- Treating observability cost as fixed: no sampling, no DEBUG filtering, full retention — the bill grows superlinearly with traffic.

## 📚 Further Reading
- *Observability Engineering* — Charity Majors, Liz Fong-Jones, George Miranda (O'Reilly).
- *Site Reliability Engineering* and *The SRE Workbook* — Google (free online); see the chapters on SLOs and multi-burn-rate alerting.
- *Distributed Tracing in Practice* — Austin Parker et al. (O'Reilly).
- OpenTelemetry official documentation — Collector, propagators, semantic conventions, and GenAI conventions: <https://opentelemetry.io/docs/>.
- W3C Trace Context specification: <https://www.w3.org/TR/trace-context/>.
- Grafana Pyroscope / Parca docs for continuous profiling, and Pixie/Cilium-Hubble docs for eBPF observability.
