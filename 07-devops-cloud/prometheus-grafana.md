# Prometheus & Grafana

Prometheus is the de-facto open-source, pull-based metrics and alerting system for cloud-native infrastructure, and Grafana is the visualization and observability layer most teams pair with it. This guide is an exhaustive, interview-focused walkthrough of metric models, PromQL, scaling (Thanos/Cortex/Mimir), alerting, SLOs, and the operational pitfalls that separate juniors from staff engineers.

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

### Q1. [Theory] What is Prometheus and what problem does it solve?

Prometheus is an open-source monitoring system and time-series database (TSDB) built at SoundCloud in 2012 and now a graduated CNCF project. It solves the problem of collecting, storing, and querying numeric time-series data (metrics) from dynamic, ephemeral infrastructure like containers and Kubernetes pods. Its core design decisions are: a **pull model** (Prometheus scrapes targets over HTTP rather than receiving pushes), a **multidimensional data model** where every metric is identified by a name plus key/value labels, and **PromQL**, a powerful functional query language. It is purpose-built for reliability and operational simplicity — each server is standalone with local storage, so it keeps working even when other parts of the network are down. It is *not* designed for billing-grade event accuracy or long-term durable storage out of the box; it favors "good enough" metrics that are always available over perfect data that might be unavailable during an outage.

### Q2. [Theory] Explain the four core metric types.

Prometheus client libraries expose four metric types:

- **Counter** — a monotonically increasing value that only goes up (or resets to zero on restart). Examples: total HTTP requests, total errors. You almost never read a counter's raw value; you apply `rate()` to get per-second change.
- **Gauge** — a value that can go up or down. Examples: current memory usage, queue depth, temperature, number of in-flight requests.
- **Histogram** — samples observations into configurable buckets and exposes cumulative counts (`_bucket`), plus `_sum` and `_count`. Used for request latencies and response sizes. Quantiles are computed *server-side* with `histogram_quantile()`, so you can aggregate across instances.
- **Summary** — also tracks `_sum` and `_count` but calculates configurable quantiles (e.g. p50/p99) **client-side**. The downside is that summary quantiles **cannot be aggregated** across instances, which is why histograms are usually preferred in distributed systems.

```
counter:   /\/\/\/  (only rises, drops to 0 on restart)
gauge:     /\  /\/   (free to rise and fall)
histogram: [le=0.1]=80 [le=0.5]=95 [le=1]=99 [le=+Inf]=100  + _sum + _count
summary:   {quantile="0.99"}=0.84  + _sum + _count   (computed in the client)
```

### Q3. [Theory] What is the difference between the pull model and the push model, and why does Prometheus pull?

In a **pull** model, the monitoring server periodically scrapes a metrics endpoint (`/metrics`) exposed by each target. In a **push** model, applications send metrics to a central collector (StatsD, Graphite, OTLP push). Prometheus pulls because it gives you: (1) easy detection of "is this target up?" via the synthetic `up` metric — if a scrape fails, you know the target is down; (2) centralized control over scrape frequency and target lists; (3) no risk of a misbehaving app flooding the collector; and (4) the ability to run a target's `/metrics` endpoint manually in a browser for debugging. The main weakness is short-lived batch jobs that may finish before any scrape happens — for those, Prometheus provides the **Pushgateway**, a deliberately narrow exception to the pull model.

### Q4. [Practical] How do you instrument a simple service and expose metrics?

You add a client library (e.g. `prometheus-client` for Python, Micrometer for Spring Boot, `client_golang` for Go), register metrics, and expose an HTTP `/metrics` endpoint. Then you tell Prometheus to scrape it. A minimal scrape config:

```yaml
# prometheus.yml
global:
  scrape_interval: 15s        # how often to scrape every target by default
  evaluation_interval: 15s    # how often to evaluate rules

scrape_configs:
  - job_name: "my-service"
    metrics_path: /metrics    # default, shown for clarity
    static_configs:
      - targets: ["10.0.0.5:8080", "10.0.0.6:8080"]
        labels:
          env: production
          team: payments
```

In production you would replace `static_configs` with service discovery (Kubernetes, Consul, EC2) so targets are found automatically as pods come and go.

### Q5. [Theory] What are labels and why are they powerful?

Labels are key/value pairs attached to a metric that turn a single metric name into a multidimensional space. `http_requests_total{method="GET", status="200", handler="/api/users"}` and `http_requests_total{method="POST", status="500", handler="/api/orders"}` are two distinct time series sharing one metric name. Labels let you slice and aggregate flexibly in PromQL — sum errors by service, filter latency by endpoint, group by region — without predefining every combination. The trade-off, covered later, is that **every unique combination of label values creates a new time series**, so high-cardinality labels (user IDs, request IDs, timestamps) can explode memory and storage.

### Q6. [Coding] Write a PromQL query for the per-second request rate over 5 minutes, broken down by status code.

**Problem:** You have a counter `http_requests_total` with a `status` label. You want requests-per-second for each status code, averaged over a 5-minute window.

```promql
# rate() computes the per-second average increase of a counter over the window,
# automatically handling counter resets (restarts).
sum by (status) (
  rate(http_requests_total[5m])
)
```

- `rate(...[5m])` converts the raw counter into a per-second rate over a 5m sliding window.
- `sum by (status)` aggregates across all instances/handlers, keeping only the `status` dimension.

**Edge cases:** Use `rate()` (not `irate()`) for graphing smooth trends; `irate()` only uses the last two samples and is for fast-moving counters in short windows. The range `[5m]` must contain at least two samples (so it should be ≥ 2× scrape interval) or `rate()` returns nothing. **Time complexity** is O(n) over samples in the window; **space** is proportional to the number of matching series.

### Q7. [Theory] What is an exporter? Name a few common ones.

An exporter is a small process that translates metrics from a system that does not natively speak Prometheus into the Prometheus exposition format, then exposes them on `/metrics` for scraping. Exporters exist because you usually cannot recompile a database or the Linux kernel to add a client library. Common ones: **node_exporter** (host CPU, memory, disk, network), **cAdvisor** (container metrics), **blackbox_exporter** (probes HTTP/TCP/ICMP/DNS endpoints from the outside), **kube-state-metrics** (Kubernetes object state like deployment replicas), **mysqld_exporter / postgres_exporter / redis_exporter** (databases). The exporter pattern keeps Prometheus itself simple — it only ever does one thing: scrape HTTP endpoints.

---

## 🟡 Intermediate (3–7 yrs)

### Q8. [Theory] Why must you use `rate()` on counters, and what happens during a counter reset?

A raw counter value is meaningless on its own — `http_requests_total = 4823917` tells you nothing about current traffic. What you care about is the *velocity* of the counter, which is what `rate()` and `increase()` compute. Crucially, counters reset to zero when a process restarts. A naive subtraction (`last - first`) would produce a large negative number on restart. `rate()` and `increase()` detect resets: when a sample is lower than the previous one, they assume a reset occurred and treat the drop as a counter starting over, adding the post-reset value rather than subtracting. This is why you must never compute counter deltas manually — always go through `rate`, `irate`, or `increase`. `increase(x[5m])` is just `rate(x[5m]) * 300`, giving total count over the window rather than per-second.

### Q9. [Coding] Compute the 99th percentile request latency from a histogram.

**Problem:** You have a histogram `http_request_duration_seconds` with `_bucket`, `_sum`, `_count`. Compute the p99 latency per service.

```promql
# histogram_quantile reconstructs an approximate quantile from cumulative buckets.
# Always wrap the bucket rate so quantiles reflect the recent window, and
# preserve the "le" label which holds the bucket boundary.
histogram_quantile(
  0.99,
  sum by (service, le) (
    rate(http_request_duration_seconds_bucket[5m])
  )
)
```

**Why this shape:** `rate(..._bucket[5m])` gives per-second observations per bucket; `sum by (service, le)` aggregates across instances while keeping the bucket boundary `le`. `histogram_quantile(0.99, ...)` interpolates within the bucket that contains the 99th percentile.

**Brute-force alternative (summary-based):** if you used a summary metric instead, you'd query `http_request_duration_seconds{quantile="0.99"}` directly — but you **cannot aggregate** it across instances, so it's wrong for fleets.

**Edge cases & accuracy:** Histogram quantiles are only as accurate as your bucket boundaries; if all observations fall in one wide bucket, p99 is a linear guess inside that bucket. Native histograms (Prometheus 2.40+, stable in 3.x) solve this with high-resolution exponential buckets. **Complexity:** O(buckets) interpolation per series.

### Q10. [Theory] Explain label cardinality and how a cardinality explosion happens.

Cardinality is the number of unique time series, which equals the product of the number of distinct values across all label dimensions for a metric. `http_requests_total{method, status, handler}` with 4 methods × 5 statuses × 20 handlers = 400 series — fine. The explosion happens when someone adds an unbounded label: `user_id`, `request_id`, `session_id`, `email`, full URL with query params, or raw timestamps. A single `user_id` label on a service with 10 million users multiplies series count by 10 million. Each active series consumes RAM (Prometheus holds them in the head block) and disk, and high churn (series that appear and disappear constantly) is even worse because of index bloat. Symptoms: Prometheus OOMs, slow queries, ingestion lag. The fix: never put unbounded identifiers in labels — log those instead, or use exemplars/traces. Use `count({__name__=~".+"}) by (__name__)` or the TSDB status page to find offenders.

```
GOOD label set:  method × status × endpoint        ->   hundreds of series
BAD label set:   method × status × user_id          ->   millions of series  (BOOM)
                                       ^ unbounded
```

### Q11. [Practical] Walk through setting up Kubernetes service discovery.

In Kubernetes you never hardcode pod IPs because they change constantly. You use `kubernetes_sd_configs`, which queries the Kubernetes API for endpoints, pods, services, or nodes, and then `relabel_configs` to filter and shape the target list. A typical pattern is to scrape only pods that carry an annotation like `prometheus.io/scrape: "true"`:

```yaml
scrape_configs:
  - job_name: "kubernetes-pods"
    kubernetes_sd_configs:
      - role: pod
    relabel_configs:
      # keep only pods annotated for scraping
      - source_labels: [__meta_kubernetes_pod_annotation_prometheus_io_scrape]
        action: keep
        regex: "true"
      # use a custom path if the pod declares one
      - source_labels: [__meta_kubernetes_pod_annotation_prometheus_io_path]
        action: replace
        target_label: __metrics_path__
        regex: (.+)
      # rewrite the scrape address to use the annotated port
      - source_labels: [__address__, __meta_kubernetes_pod_annotation_prometheus_io_port]
        action: replace
        regex: ([^:]+)(?::\d+)?;(\d+)
        replacement: $1:$2
        target_label: __address__
      # promote the namespace to a real label
      - source_labels: [__meta_kubernetes_namespace]
        target_label: namespace
```

In modern stacks (kube-prometheus-stack) you'd instead use the **Prometheus Operator** with `ServiceMonitor`/`PodMonitor` CRDs, which generate this relabeling for you declaratively.

### Q12. [Theory] What is relabeling and why is it different from metric_relabel_configs?

Relabeling rewrites label sets through a sequence of rules (`source_labels`, `regex`, `action`, `target_label`). There are two phases: **`relabel_configs`** runs *before* the scrape and decides *which targets to scrape and how* (filter targets, set `__address__`, set the job/instance). **`metric_relabel_configs`** runs *after* the scrape and *before* ingestion, operating on individual scraped samples — this is where you drop noisy metrics or strip high-cardinality labels to control storage. The distinction matters in interviews: dropping an entire endpoint uses `relabel_configs` (you never even scrape it); dropping a specific metric you scraped but don't want to store uses `metric_relabel_configs` with `action: drop`.

### Q13. [Coding] Write a recording rule and an alerting rule for an error-rate SLO.

**Problem:** Precompute the 5xx error ratio for efficiency, then alert when it exceeds 1% for 10 minutes.

```yaml
# rules.yml
groups:
  - name: http-slo
    interval: 30s
    rules:
      # Recording rule: precompute an expensive expression once,
      # store the result as a new time series for fast dashboards/alerts.
      - record: job:http_request_errors:ratio_rate5m
        expr: |
          sum by (job) (rate(http_requests_total{status=~"5.."}[5m]))
          /
          sum by (job) (rate(http_requests_total[5m]))

      # Alerting rule built ON TOP of the recording rule.
      - alert: HighErrorRate
        expr: job:http_request_errors:ratio_rate5m > 0.01
        for: 10m                       # must stay true for 10m before firing
        labels:
          severity: page
        annotations:
          summary: "High 5xx error rate on {{ $labels.job }}"
          description: "{{ $labels.job }} error ratio is {{ $value | humanizePercentage }} (>1%)."
```

**Why recording rules:** dashboards and alerts that re-run the same heavy aggregation every refresh are slow; recording rules compute it once per `interval` and persist a tiny series. The naming convention `level:metric:operations` is the community standard.

**Edge cases:** Guard against divide-by-zero — when there's no traffic, the denominator is 0 and the ratio is `NaN`; add `and sum by (job)(rate(http_requests_total[5m])) > 0` if you only want to alert under load. The `for` clause prevents flapping on transient spikes.

### Q14. [Theory] What is Alertmanager and what does it do that Prometheus does not?

Prometheus only *evaluates* alerting rules and fires alerts; **Alertmanager** handles everything that happens after an alert fires: **deduplication** (the same alert from 3 HA Prometheus replicas becomes one notification), **grouping** (50 pods down in one deploy become one grouped notification, not 50 pages), **routing** (send database alerts to the DBA Slack, paging alerts to PagerDuty), **inhibition** (suppress a "high latency" alert if the "service down" alert for the same cluster is already firing), and **silencing** (mute alerts during a known maintenance window). A minimal config:

```yaml
# alertmanager.yml
route:
  group_by: ['alertname', 'cluster']
  group_wait: 30s          # wait to collect related alerts before first notify
  group_interval: 5m       # wait before notifying about new alerts in a group
  repeat_interval: 4h      # re-send unresolved alerts every 4h
  receiver: default-slack
  routes:
    - matchers: [severity="page"]
      receiver: pagerduty
inhibit_rules:
  - source_matchers: [severity="critical"]
    target_matchers: [severity="warning"]
    equal: ['cluster', 'service']   # silence warnings when a critical fires for same scope
receivers:
  - name: default-slack
    slack_configs:
      - channel: '#alerts'
  - name: pagerduty
    pagerduty_configs:
      - routing_key: <secret>
```

### Q15. [Practical] How do you build a reusable Grafana dashboard with variables?

Grafana **template variables** turn a static dashboard into a reusable one. You define a variable like `$service` as a *query variable* sourced from `label_values(http_requests_total, service)`, which populates a dropdown from live label values. Panels then reference `$service` in their PromQL: `rate(http_requests_total{service="$service"}[5m])`. Add a `$datasource` variable for multi-Prometheus setups, and an `$interval` variable so rate windows scale with the selected time range (`rate(metric[$__rate_interval])`). Use `$__rate_interval` (a Grafana macro) rather than a hardcoded `[5m]` — it automatically picks a window that's at least 4× the scrape interval, avoiding gaps when users zoom out. For multi-select, enable "Include All" and use the regex match `{service=~"$service"}`. This pattern lets one dashboard serve hundreds of services without duplication.

### Q16. [Theory] What are the four golden signals?

Coined in Google's SRE book, the four golden signals are the minimal set of metrics to monitor any user-facing system:

1. **Latency** — how long requests take (track success and failure latency separately; a fast 500 can hide a problem).
2. **Traffic** — demand on the system (requests/sec, transactions/sec).
3. **Errors** — rate of failed requests (explicit 5xx, plus implicit failures like wrong content).
4. **Saturation** — how "full" the system is (CPU, memory, disk I/O, queue depth) — the signal that predicts *future* failure.

They're popular because they're symptom-oriented (what the user feels) rather than cause-oriented, so a small dashboard with these four per service catches most incidents. The related **RED method** (Rate, Errors, Duration) is the request-centric subset, and the **USE method** (Utilization, Saturation, Errors) is the resource-centric counterpart.

### Q17. [Coding] Drop a high-cardinality label at scrape time.

**Problem:** A third-party exporter emits `api_calls_total{user_id="...", endpoint="..."}`. You want to keep `endpoint` but strip the exploding `user_id` label, and drop a noisy debug metric entirely.

```yaml
scrape_configs:
  - job_name: "third-party"
    static_configs:
      - targets: ["exporter:9100"]
    metric_relabel_configs:
      # 1. Remove the user_id label from every series (collapses cardinality).
      - action: labeldrop
        regex: user_id
      # 2. Drop an entire noisy metric we never query.
      - source_labels: [__name__]
        action: drop
        regex: go_gc_duration_seconds_debug.*
```

**Trade-off:** `labeldrop` merges series that differ only by `user_id`. If two series collide on all *remaining* labels, Prometheus reports a "duplicate sample" error — so make sure the dropped label wasn't the only differentiator for a counter, or you'll lose data. **Edge case:** for counters this is usually fine because you immediately `sum` them anyway; for gauges, collapsing can silently overwrite values.

### Q18. [Practical] Your Prometheus is OOMing. How do you diagnose and fix it?

Memory is dominated by the **head block** (recent, in-memory samples) and the number of active series. First, check `/tsdb-status` (or the "TSDB Status" page) for top metrics and labels by cardinality, and query `prometheus_tsdb_head_series` for total active series and `scrape_samples_scraped` per job. The usual root cause is a cardinality explosion (Q10) or too-frequent scraping of huge endpoints. Fixes in order of preference: (1) drop offending labels/metrics with `metric_relabel_configs`; (2) fix the instrumentation at the source; (3) raise `scrape_interval` for fat targets; (4) reduce retention or shard. Then size memory: a rough rule is bytes ≈ active_series × ~few KB plus query overhead. If you genuinely need millions of series, that's a signal to move to a horizontally scalable backend (Mimir/Thanos/Cortex). Don't just throw RAM at it — unbounded cardinality will always catch up.

---

## 🟠 Advanced (8–12 yrs)

### Q19. [Theory] How does Prometheus achieve high availability, and what are the consistency trade-offs?

Prometheus has no built-in clustering by design — clustering would compromise its reliability goal. HA is achieved by running **two (or more) identical Prometheus servers** scraping the same targets with the same config. Both fire alerts; **Alertmanager deduplicates** them so you don't get double-paged. For querying, you put both behind a load balancer or use Thanos/Mimir to merge and deduplicate their data. The trade-off is that the two replicas are **not perfectly consistent**: they scrape at slightly different instants, so their samples and timestamps differ subtly, and a query hitting replica A vs replica B can return marginally different graphs. This is an acceptable trade-off — Prometheus deliberately chooses availability over consistency (AP in CAP terms) because for monitoring, "approximately right and always available" beats "exactly right but down during the outage you're trying to debug."

### Q20. [Theory] Compare Thanos, Cortex, and Grafana Mimir for long-term storage and global view.

All three extend Prometheus with long-term, durable, horizontally scalable storage and a global query view across clusters. Their architectures differ:

```
THANOS (sidecar/object-store model):
  Prometheus + Sidecar --upload blocks--> Object Storage (S3/GCS)
  Querier --fan-out--> Sidecars (recent) + Store Gateway (historical)
  Compactor downsamples & compacts blocks. Deduplicates HA replicas at query time.

CORTEX / MIMIR (push/microservices model):
  Prometheus --remote_write--> Distributor -> Ingester -> Object Storage
  Query path: Query Frontend -> Querier -> Ingesters (recent) + Store Gateway (old)
  Mimir is the CNCF-incubating fork of Cortex, optimized for scale & simpler ops.
```

- **Thanos** layers onto *existing* Prometheus servers with minimal change (add a sidecar), keeps the pull model, and uplinks 2h blocks to object storage. Great when you already run many Prometheus servers and want a global view + cheap retention.
- **Cortex** uses `remote_write` to push samples into a multi-tenant, microservice cluster. Born for SaaS multi-tenancy.
- **Mimir** is Grafana Labs' evolution of Cortex — benchmarked to **1 billion+ active series**, with simpler operation, better compaction/sharding, and built-in multi-tenancy. In 2026 Mimir is the common choice for new large-scale, multi-tenant deployments; Thanos remains popular for federating many independent Prometheis without re-architecting ingestion.

The key decision axis: **Thanos = augment what you have (pull preserved); Mimir/Cortex = centralize via remote_write (push to a cluster).**

### Q21. [Coding] Configure remote_write to a Mimir/Cortex backend with sharding and relabeling.

**Problem:** Forward all metrics to a central Mimir cluster for long-term storage and a global view, but drop debug metrics and tune the queue for throughput.

```yaml
remote_write:
  - url: "https://mimir.example.com/api/v1/push"
    # multi-tenant header for Mimir/Cortex
    headers:
      X-Scope-OrgID: "team-payments"
    # drop metrics we don't want to ship/store centrally
    write_relabel_configs:
      - source_labels: [__name__]
        action: drop
        regex: "go_.*|process_.*"
    queue_config:
      capacity: 10000          # samples buffered per shard
      max_shards: 200          # max parallelism; auto-scales under backpressure
      min_shards: 1
      max_samples_per_send: 2000
      batch_send_deadline: 5s
    metadata_config:
      send: true
    # protect secrets via file, not inline
    basic_auth:
      username: payments
      password_file: /etc/prometheus/remote-write-password
```

**Trade-offs:** `remote_write` adds CPU/memory and a write-ahead-log (WAL) tail for buffering; if the remote endpoint is slow, shards multiply and the WAL grows. `write_relabel_configs` reduces egress cost and central cardinality. **Security note:** never inline credentials — use `password_file` or `authorization.credentials_file`, and always TLS the endpoint. **Edge case:** if the remote is down longer than your WAL retention, you permanently lose those samples — size the WAL and alert on `prometheus_remote_storage_samples_pending`.

### Q22. [Practical] Design an SLO + error-budget dashboard. What metrics and queries?

An SLO ("99.9% of requests succeed over 30 days") implies an **error budget** of 0.1% — about 43 minutes of full downtime per 30 days. The dashboard needs three layers. (1) **SLI**: the success ratio, ideally from recording rules at multiple windows. (2) **Error budget remaining**: `1 - (errors / total) / (1 - SLO)`, shown as a gauge that drains over the window. (3) **Burn rate**: how fast you're consuming budget right now.

```promql
# Error budget burn rate over 1h (1.0 = consuming budget exactly at the sustainable pace)
(
  sum(rate(http_requests_total{status=~"5.."}[1h]))
  /
  sum(rate(http_requests_total[1h]))
) / (1 - 0.999)
```

Google's **multi-window, multi-burn-rate** alerting fires a page when a *fast* burn (e.g. 14.4× over 1h, exhausting 2% of a 30-day budget in an hour) AND a confirming shorter window both trip — this gives fast detection with few false positives. You'd build panels for: SLI vs target line, budget-remaining gauge, 1h/6h burn rates with threshold bands, and a "time until budget exhausted" stat. This reframes alerting from "CPU is 80%" to "we are spending reliability faster than the business agreed to," which is the language leadership and SRE share.

### Q23. [Theory] Explain the Prometheus storage engine internals: head block, WAL, and compaction.

Prometheus stores data in a custom TSDB on local disk. Incoming samples land in the **head block**, an in-memory structure for the most recent ~2 hours, with every write also appended to a **Write-Ahead Log (WAL)** on disk so an unclean restart can replay un-persisted samples. Periodically the head is flushed to a **persistent block** — an immutable directory on disk containing chunked, compressed samples plus an inverted index mapping label pairs to series. A background **compactor** merges small blocks into larger ones over time and applies the retention policy by deleting blocks past `--storage.tsdb.retention.time`. Samples are compressed with Gorilla-style **delta-of-delta** timestamp encoding and XOR float compression, achieving ~1–2 bytes per sample. The implications for interviews: local storage is **not durable or replicated** (that's why you need remote_write/Thanos), restarts replay the WAL (so a huge head = slow startup), and queries over old data read immutable blocks while recent data comes from the head.

```
        scrape
          |
          v
   [ WAL (disk) ] --append-- and --in-memory--> [ HEAD block ~2h ]
                                                       |  flush every 2h
                                                       v
                                              [ persistent block ]  (immutable)
                                                       |  background
                                                       v
                                              [ compactor merges + retention delete ]
```

### Q24. [Coding] Write a multi-window, multi-burn-rate alert (the Google SRE pattern).

**Problem:** Page only on fast budget burn confirmed by two windows, to balance detection speed and false positives. Target SLO = 99.9%.

```yaml
groups:
  - name: slo-burn
    rules:
      # Recording rules give us error ratios at several windows.
      - record: job:errors:ratio5m
        expr: sum by (job)(rate(http_requests_total{status=~"5.."}[5m]))
              / sum by (job)(rate(http_requests_total[5m]))
      - record: job:errors:ratio1h
        expr: sum by (job)(rate(http_requests_total{status=~"5.."}[1h]))
              / sum by (job)(rate(http_requests_total[1h]))
      - record: job:errors:ratio6h
        expr: sum by (job)(rate(http_requests_total{status=~"5.."}[6h]))
              / sum by (job)(rate(http_requests_total[6h]))

      # FAST burn: 14.4x burn rate => burns 2% of 30d budget in 1h. Page.
      - alert: ErrorBudgetFastBurn
        expr: |
          job:errors:ratio1h > (14.4 * 0.001)
          and
          job:errors:ratio5m > (14.4 * 0.001)
        for: 2m
        labels: { severity: page }
        annotations:
          summary: "Fast error-budget burn on {{ $labels.job }}"

      # SLOW burn: 6x over 6h, confirmed by 1h window. Ticket, don't page.
      - alert: ErrorBudgetSlowBurn
        expr: |
          job:errors:ratio6h > (6 * 0.001)
          and
          job:errors:ratio1h > (6 * 0.001)
        for: 15m
        labels: { severity: ticket }
```

**Why two windows per alert:** the long window measures sustained burn (avoids paging on a 30-second blip); the short window ensures the problem is *still happening* now (so the alert auto-resolves quickly once fixed). **Complexity:** all heavy work is in recording rules evaluated once per interval, so alert evaluation is O(series) and cheap. **Edge case:** low-traffic services produce noisy ratios; add a minimum-traffic guard or use a longer window.

### Q25. [Practical] How do you scale a single Prometheus that has hit its limits? Compare functional sharding, hashmod sharding, and federation.

When one Prometheus can't keep up, you have three patterns. (1) **Functional sharding** — split by concern: one Prometheus for infra/node metrics, one for app metrics, one per major team. Simple, human-understandable, and the most common first step. (2) **Hashmod sharding** — when even one job's targets are too many, use `relabel_configs` with `action: hashmod` on `__address__` modulo N so each shard scrapes a deterministic subset of targets; you then need a query layer (Thanos/Mimir) to recombine them. (3) **Federation** — a central "global" Prometheus scrapes *aggregated* (recording-rule) metrics from many child Prometheis via `/federate`. Federation is for **aggregates and cross-cluster rollups, not raw data** — pulling all raw series through `/federate` is a classic anti-pattern that overwhelms the parent.

```yaml
# Hashmod sharding: this shard (shard 2 of 4) keeps ~1/4 of targets
relabel_configs:
  - source_labels: [__address__]
    modulus: 4
    target_label: __tmp_shard
    action: hashmod
  - source_labels: [__tmp_shard]
    regex: "2"
    action: keep
```

In production today, most teams reach for functional sharding first, then graduate straight to **Mimir/Thanos** rather than hand-rolling federation, because those give global view + HA dedup + long-term storage in one stack.

### Q26. [Theory] What are exemplars and native histograms, and why do they matter in 2026?

**Exemplars** attach a sample of trace IDs (and labels) to specific metric observations — e.g. a histogram bucket can carry "here is a trace ID of a request that landed in the >1s bucket." In Grafana, you click a latency spike and jump straight to a Tempo/Jaeger trace, bridging metrics and tracing. They're stored in a separate, size-limited in-memory store and exposed via OpenMetrics. **Native (sparse) histograms**, stable in Prometheus 3.x, replace fixed user-defined buckets with automatically-scaled exponential buckets. The win is enormous: far higher quantile accuracy, dramatically lower storage/cardinality (one series instead of dozens of `_bucket` series), and no need to guess bucket boundaries upfront. Together with **OpenTelemetry** convergence (Prometheus now ingests OTLP and there's an official OTLP receiver), these features matter because observability in 2026 is unified — metrics, logs, and traces correlated, with native histograms making high-fidelity latency SLOs cheap.

---

## 🔴 Expert (15+ yrs)

### Q27. [Theory] You're designing observability for a 200-cluster, multi-region, multi-tenant platform. What architecture do you choose and why?

I'd run a **two-tier architecture**: a Prometheus (or the Agent/`agent mode`/Grafana Alloy) per cluster doing local scraping and local alerting for low-latency, blast-radius-contained detection, and a **central Grafana Mimir** cluster as the long-term, globally-queryable, multi-tenant store fed by `remote_write`. Local Prometheus handles fast critical alerts even if the central tier is unreachable (preserving the "always available during an outage" property). Mimir provides the global view, 13-month retention for capacity planning and SLO reporting, per-tenant isolation and limits (so one noisy team can't starve others), and horizontal scale to billions of series. Grafana sits on top with Mimir as a data source, dashboards templated by `cluster`/`region`/`tenant`. Alertmanager runs in HA clustered mode regionally. I'd standardize instrumentation on **OpenTelemetry** so the metric pipeline is vendor-neutral, push **exemplars** to link to Tempo traces, and enforce cardinality budgets per tenant via Mimir limits + CI linting of metric definitions. Thanos would be the alternative if the org already had hundreds of independent Prometheus servers and wanted to avoid re-plumbing ingestion to push-based remote_write.

### Q28. [Behavioral] Tell me about a time a monitoring decision caused (or prevented) a major incident.

Strong answers use STAR and show judgment, not just tooling. Example framing: *"At a previous company our payments service started OOM-killing Prometheus weekly. The root cause was an engineer who added a `customer_id` label to a request counter for 'easier debugging,' creating 8 million series overnight (Situation/Task). I traced it via the TSDB cardinality page and `topk(10, count by (__name__)({__name__=~'.+'}))` (Action). The immediate fix was a `metric_relabel_configs` labeldrop while we corrected the instrumentation; the durable fix was a pre-merge CI check that rejected metric definitions with known-unbounded label names, plus a per-team cardinality budget enforced in Mimir (Action). Result: zero cardinality OOMs in the following year, and the CI gate caught three more attempts (Result)."* The interviewer is listening for: did you find root cause vs. symptom, did you build a systemic guardrail, and did you communicate the trade-off (debuggability vs. system stability) to the team that introduced it.

### Q29. [Theory] How do you secure a Prometheus and Grafana deployment end to end?

Prometheus historically shipped with **no authentication or authorization** on its API or scrape endpoints, so security is layered around it. Network-wise: keep Prometheus, exporters, and Alertmanager on a private network and front them with a reverse proxy or service mesh enforcing mTLS and authn (OAuth2-proxy, Envoy, Istio). Scrape security: Prometheus 2.24+ supports TLS and basic auth on its own server endpoints and supports `tls_config`/`authorization` per scrape job to talk to secured targets. Use `bearer_token_file`/`credentials_file` and Kubernetes secrets — never inline credentials in `prometheus.yml`. For `remote_write`, mandate TLS + per-tenant auth headers. **Grafana** security: enforce SSO/OIDC, scope dashboards/folders with RBAC, store data-source credentials encrypted, and disable anonymous access except for explicitly public dashboards. Also watch the **data exfiltration** angle — metrics can leak sensitive cardinality (customer names in labels) and dashboards can embed queries that reveal topology; treat metric labels as potentially PII. Finally, restrict the Alertmanager API (silences can suppress real alerts — an attacker who can create silences can hide an attack).

### Q30. [Practical] A critical alert fired late during an outage. Walk through your post-incident analysis of the alerting pipeline.

I'd trace the alert's full lifecycle and measure latency at each hop, because "the alert fired late" can originate anywhere. (1) **Scrape latency**: was the target even being scraped? Check `up` and `scrape_duration_seconds`; a slow `/metrics` endpoint or a missed scrape delays detection by a full `scrape_interval`. (2) **Rule evaluation delay**: check `prometheus_rule_group_last_duration_seconds` and `evaluation_interval` — if rule groups are slow or the interval is large, the condition is evaluated late. (3) **The `for` clause**: a `for: 15m` adds 15 minutes by design; was it too conservative for a critical alert? (4) **Alertmanager timing**: `group_wait` (default 30s) + `group_interval` delay first notification; misconfigured grouping can batch a critical alert with slow-moving ones. (5) **Routing/inhibition**: was the alert inhibited or silenced incorrectly, or routed to a dead receiver? (6) **Notification delivery**: PagerDuty/Slack outage or rate-limiting. The fix usually combines lowering `for`/`group_wait` for paging-severity alerts, adding multi-burn-rate alerting (fast detection), and adding **meta-monitoring** — a dead-man's-switch alert (`Watchdog`) that *always* fires so you detect when the alerting pipeline itself is broken. The systemic lesson: **monitor the monitoring**, and tier your timing config by severity.

### Q31. [Theory] Discuss the cost and trade-offs of long-term metric retention strategies at scale.

At scale, raw 15s-resolution metrics are expensive to keep forever, so you tier. **Downsampling** (Thanos compactor, Mimir) keeps full resolution for recent data (e.g. 15s for 2 weeks), 5-minute resolution for months, and 1-hour resolution for a year+ — capacity planning and SLO trend reporting don't need second-level fidelity from 8 months ago. **Object storage** (S3/GCS) is the durable, cheap backing store; the cost driver shifts from disk to *query* compute (Store Gateways scanning blocks) and API request costs. Trade-offs: more retention and resolution = more storage and slower historical queries; aggressive downsampling = you lose the ability to investigate fine-grained historical incidents. **Cardinality is the dominant cost lever**, not retention duration — halving active series saves far more than halving retention. I'd set per-tenant limits, drop high-cardinality/low-value metrics at `remote_write`, use recording rules to precompute the handful of long-retention aggregates teams actually query historically, and apply different retention classes per metric importance. A common real-world figure: teams routinely cut their bill 50–80% just by auditing and dropping unused/high-cardinality metrics that nobody ever queries.

### Q32. [Practical] Real-world case study: how would you have caught a "metrics blind spot" outage?

A well-known failure mode (seen across many companies) is the **aggregation blind spot**: a service reports a healthy *average* latency while a subset of users experiences total failure, because averages and even p99 over a coarse dimension hide a per-shard or per-region collapse. Concretely, imagine a global API where one region's database fails: global success rate dips from 99.99% to 99.6% — under most alert thresholds — but 40% of one region's users are fully down. The catch is to **alert on the worst slice, not the global aggregate**: use `min by (region)` / `max by (region)` and per-tenant SLOs, e.g. `max by (region)(job:errors:ratio5m) > 0.05` fires even when the global ratio looks fine. The broader lessons for staff-level design: (1) always keep a meaningful dimension (region, tenant, shard) in your SLI so aggregation can't mask localized failure; (2) pair symptom-based golden-signal alerts with cause-based ones; (3) use multi-burn-rate alerts so a small-but-fast localized burn still pages; and (4) validate with chaos/game-day drills that your alerts actually fire for partial failures, not just total ones. This converts "our dashboard was green during the outage" into a designed-out class of incident.

---

## 🧩 Extended Questions — Set 1: Deep Theory & Internals

### 🟢 Basic — extended

#### Q33. [Theory] What is an instant vector versus a range vector in PromQL, and why does the distinction matter?

PromQL has four expression types, but the two that trip people up are **instant vectors** and **range vectors**. An *instant vector* is a set of time series each with exactly **one sample** at the query's evaluation timestamp — e.g. `http_requests_total` returns the latest value of every matching series. A *range vector* is a set of series each carrying a **range of samples** over a time window — e.g. `http_requests_total[5m]` returns all samples in the last 5 minutes for every series. The square-bracket duration is what turns an instant vector selector into a range vector.

The distinction is load-bearing because **functions are typed**. `rate()`, `increase()`, `avg_over_time()`, and friends take a *range vector* and return an *instant vector* — they collapse the window into a single per-series value. Aggregations like `sum()` and `topk()` take an *instant vector*. This is exactly why `rate(http_requests_total)` is a syntax error (no range) and `sum(http_requests_total[5m])` is also illegal (you can't `sum` a range vector). You must compose them: `sum(rate(http_requests_total[5m]))`.

```
http_requests_total          -> instant vector  (1 sample/series @ eval time)
http_requests_total[5m]      -> range vector    (N samples/series over 5m)
rate(  ...[5m]  )            -> range vector IN,  instant vector OUT
sum(   rate(...[5m])  )      -> instant vector IN, instant vector OUT
```

The remaining two types are **scalars** (a single number with no labels, e.g. `0.99`) and **string literals** (only used as function arguments). Knowing the type algebra lets you read any PromQL error of the form "expected type instant vector but got range vector" instantly, which is half of debugging real queries.

#### Q34. [Theory] What does the `up` metric actually represent, and what other metrics does Prometheus generate about a scrape?

`up` is a **synthetic metric Prometheus injects itself** for every scrape — it is not produced by the target. After each scrape attempt, Prometheus writes `up{job="...", instance="..."} = 1` if the scrape succeeded (HTTP 200, parseable body, within timeout) or `0` if it failed for any reason (connection refused, timeout, 500, unparseable payload). This is the backbone of "is it alive?" alerting: `up == 0` means the target is unreachable, and `absent(up{job="x"})` means the target isn't even in service discovery anymore.

Alongside `up`, every scrape generates a small family of per-scrape metrics that are invaluable for meta-monitoring: `scrape_duration_seconds` (how long the scrape took — a slow `/metrics` endpoint shows here), `scrape_samples_scraped` (how many samples the target exposed — sudden growth flags a cardinality leak at the source), `scrape_samples_post_metric_relabeling` (samples kept after your `metric_relabel_configs` ran), and `scrape_series_added` (new series in the head from this scrape, which surfaces churn).

```promql
# Targets currently down
up == 0

# Targets whose scrape is approaching the timeout (slow endpoints)
scrape_duration_seconds > 0.8 * scrape_timeout_seconds  # conceptual; timeout isn't a metric

# A target whose exposed sample count doubled — possible cardinality leak
scrape_samples_scraped > 2 * scrape_samples_scraped offset 1h
```

The key conceptual point for interviews: because `up` is generated **by the scraper, not the target**, it gives you a consistent liveness signal even for targets that expose zero application metrics, and it costs nothing to instrument. It is also why a "down" alert in Prometheus is fundamentally a *scrape-failure* alert, not an application-health alert — the two can diverge (a process can be up but serving a broken `/metrics`).

#### Q35. [Theory] What is the OpenMetrics / Prometheus exposition format, and what are its key conventions?

The exposition format is the **plain-text (or, in OpenMetrics, optionally Protobuf) wire format** a target serves on `/metrics`. Each line is either a `# HELP`/`# TYPE` comment describing a metric family, or a sample line: `metric_name{label="value",...} value [timestamp]`. Prometheus parses this line-by-line on every scrape. The format is deliberately dumb and human-readable so you can `curl` an endpoint and read it.

```
# HELP http_requests_total Total HTTP requests.
# TYPE http_requests_total counter
http_requests_total{method="GET",status="200"} 10247
http_requests_total{method="POST",status="500"} 13
# HELP http_request_duration_seconds Request latency.
# TYPE http_request_duration_seconds histogram
http_request_duration_seconds_bucket{le="0.1"} 9001
http_request_duration_seconds_bucket{le="0.5"} 9900
http_request_duration_seconds_bucket{le="+Inf"} 10000
http_request_duration_seconds_sum 842.3
http_request_duration_seconds_count 10000
```

**OpenMetrics** is the CNCF standardization of this format (RFC-style spec) and the basis of where Prometheus is heading. Its meaningful differences from the legacy Prometheus text format: the `# EOF` terminator marker (so a parser knows it got the whole payload, not a truncated one), explicit support for **exemplars** appended to a sample with `# {trace_id="..."} <value> <timestamp>`, the `_total` suffix convention being a hard requirement for counters, native `Unit` metadata, and support for `created` timestamps on counters. Content negotiation happens via the `Accept` header — Prometheus advertises which formats it understands and the target picks the best one.

The conventions matter because they are *enforced by client libraries and linters*: counters should end in `_total`, base units should be seconds and bytes (not milliseconds or megabytes), and metric names should read as `library_subsystem_unit`. Following them is what makes generic dashboards and recording-rule conventions like `level:metric:operation` work across teams.

### 🟡 Intermediate — extended

#### Q36. [Theory] Explain exactly how `rate()` computes its value, including extrapolation and why it can exceed the integer count.

`rate()` does **not** simply compute `(last - first) / window`. The real algorithm: within the range window it finds the first and last samples, computes the delta (accounting for counter resets by adding the value at each reset boundary), divides by the time **between those two samples** (not the nominal window), and then **extrapolates to the window edges**. The extrapolation exists because samples rarely land exactly on the window boundaries — there's a gap between the window start and the first sample, and between the last sample and the window end. Prometheus estimates the rate at the edges by projecting the observed rate outward, with a guard: if the first/last sample is more than 110% of the average sample interval from the edge, it clamps the extrapolation to the boundary rather than projecting into a gap it can't justify.

A direct consequence that surprises people: `increase()` (which is `rate() * window_seconds`) can return **non-integer or "impossible" values** like `7.3` even though a counter only ever increments by whole numbers. That's the extrapolation at work — it's an *estimate* of the increase over the full window, not an exact count of observed increments. This is by design; Prometheus optimizes for smooth, statistically reasonable trends over exactness.

```
window = [t0 ............................ t1]   (e.g. 5m)
samples:        s1   s2   s3   s4   s5
                |----|----|----|----|
                ^                     ^
            gap before            gap after
        rate = (s5 - s1) / (time(s5) - time(s1))   <- per-second over OBSERVED span
        then extrapolated to cover [t0,t1]
```

The practical lessons: (1) the window must contain **at least two samples** or `rate()` returns nothing (empty), which is why `[5m]` with a 60s scrape interval is far safer than `[1m]`; (2) `$__rate_interval` in Grafana exists precisely to keep the window ≥ 4× the scrape interval so you never silently get empty results when zooming; and (3) never compare `increase()` to a hand-counted integer and conclude Prometheus is "wrong" — it's extrapolating on purpose.

#### Q37. [Theory] What are staleness markers, and how did Prometheus handle staleness before they existed (2.0+)?

When a series stops being reported — a target disappears, a label value changes, or a metric is no longer exposed — Prometheus needs to know the series is "gone" rather than holding its last value forever. Since Prometheus 2.0, when a scrape no longer contains a series that was present in the **previous** scrape, Prometheus writes a special **staleness marker** (a sample with a NaN bit pattern reserved for this purpose) at the current timestamp. Any instant query evaluated after that marker returns *no value* for the series instead of carrying forward the old one.

Before staleness markers, Prometheus used a crude **5-minute lookback**: an instant query at time `t` would search backwards up to 5 minutes for the most recent sample. This meant a target that died would keep "appearing" in query results for up to 5 minutes with its last value frozen — alerts could resolve late, dashboards could show a dead instance as healthy, and `up == 0` could coexist with the app's own metrics still showing their last value. Staleness markers make a vanished series disappear at the *next scrape interval* instead of lingering for 5 minutes.

```
Old (pre-2.0):   target dies at t   ->  series still returned with last value until t+5m
New (2.0+):      target dies at t   ->  staleness marker injected at next scrape
                                         instant query after that returns NO value
```

This matters for correctness of alerting and aggregation. Consider `sum(up)` across a fleet: with staleness markers, a removed instance drops out cleanly; without them, you'd over-count for 5 minutes. The default lookback delta (5m) still exists as a fallback for series that simply have sparse samples, controlled by `--query.lookback-delta`, but the staleness marker is what makes "this series ended" semantically distinct from "this series is just sparse." An interviewer probing here wants you to articulate that Prometheus distinguishes *absent* from *stale* from *sparse*.

#### Q38. [Theory] Explain vector matching in PromQL: one-to-one, `on`/`ignoring`, and `group_left`/`group_right`.

When you combine two instant vectors with an operator (`/`, `*`, `and`, etc.), PromQL must decide **which series on the left pairs with which series on the right**. The default is **one-to-one matching**: a left series matches a right series only if they have an *identical* label set (after the operation's `__name__` is dropped). This is why `errors / total` works cleanly only when both sides carry the same labels — mismatched labels yield no result for that pair, silently dropping data.

To match on a *subset* of labels you use `on(labels)` (match only on these) or `ignoring(labels)` (match on everything except these). For example, dividing a per-pod metric by a per-node metric requires matching only on `node`, ignoring `pod`. When the cardinality differs — one node has many pods — you have a **many-to-one** relationship and must declare it with `group_left` (the "many" side is on the left) or `group_right`. The group modifier also lets you **copy extra labels** from the "one" side onto the result.

```promql
# Ratio of pod memory to its node's total, copying the node's `region` label onto each pod result
sum by (pod, node) (container_memory_usage_bytes)
  / on(node) group_left(region)
sum by (node, region) (node_memory_total_bytes)
```

The interview gotcha: **forgetting `group_left` on a many-to-one match throws an error** ("multiple matches for labels: many-to-one matching must be explicit"), and using `on()` with the wrong label key silently produces an empty result rather than an error. The mental model is a relational join — `on`/`ignoring` chooses the join key, and `group_left`/`group_right` declares the cardinality direction and which extra columns to carry through. Vector matching is the single most error-prone area of intermediate PromQL precisely because wrong joins fail *silently* (empty) rather than loudly.

#### Q39. [Theory] What do the `offset` modifier, the `@` modifier, and subqueries do, and when do you need each?

These three features manipulate the **time** at which parts of a query are evaluated. The **`offset` modifier** shifts a selector backward in time relative to the evaluation instant: `http_requests_total offset 1h` returns the value as of one hour before the query time. It's used for "compare now to an hour/day/week ago" without changing the dashboard's time range — e.g. `rate(x[5m]) / rate(x[5m] offset 1w) ` for week-over-week.

The **`@` modifier** (Prometheus 2.25+) pins a selector to an **absolute Unix timestamp** regardless of the query's evaluation time: `http_requests_total @ 1700000000` always reads that exact moment. Combined with `start()` and `end()` (which resolve to the query range's bounds), `@` lets you anchor a baseline — e.g. compute "growth since the start of the visible range" with `x - (x @ start())`. It's essential for queries whose reference point must be fixed rather than sliding with each evaluation step.

**Subqueries** let you run a range query *over the result of an instant-vector expression*, producing a range vector you can feed into another range function. Syntax: `<expr>[<range>:<resolution>]`. The classic use is computing a rate-of-a-rate or finding the max of a rate over a longer window:

```promql
# Max 5m request rate observed over the last hour, sampled every 1m
max_over_time(  rate(http_requests_total[5m])[1h:1m]  )

# Week-over-week ratio using offset
sum(rate(http_requests_total[5m])) / sum(rate(http_requests_total[5m] offset 7d))
```

The trade-off and warning: **subqueries are expensive** — they force Prometheus to evaluate the inner expression at every step of the inner range, which multiplies work, so you avoid them in recording rules that run constantly (precompute the inner `rate` as its own recording rule instead). `offset` and `@` are cheap by comparison. An interviewer asking this wants to see that you reach for a recording rule rather than a subquery in hot paths.

#### Q40. [Theory] What is the difference between `rate()` and `irate()`, mathematically and operationally, and when is each wrong?

Both operate on a counter range vector, but they use **different sample subsets**. `rate()` uses *all* samples in the window and computes an average per-second rate across the whole window (with the extrapolation described earlier). `irate()` (instant rate) uses **only the last two samples** in the window and computes the rate between just those two points; the window duration mostly serves to bound how far back it looks for that second-to-last sample.

The operational consequence: `rate()` produces **smooth** lines that average out spikes — ideal for dashboards, alerting, and any SLO math, because it's stable and won't flap. `irate()` produces **jagged, highly responsive** lines that capture brief spikes — useful only for fast-moving counters viewed at high resolution where you genuinely want to see the most recent instantaneous behavior.

```
counter samples in [5m]:  10  12  15  16  30  31
rate()  -> averages the whole slope  -> ~smooth, say 0.07/s
irate() -> only uses last two (30->31) -> 0.0X/s based on last gap only
```

Each is *wrong* in the other's domain. **`irate()` is wrong for alerting and SLOs**: because it samples only the last two points, when graphed or alerted at a step interval larger than the scrape interval it will *skip* most of the data and can entirely miss a spike that happened between the two samples it picked, or alternatively over-react to a single noisy interval. **`rate()` is "wrong" (misleading) when you specifically need to see sub-window spikes** — it will smooth a 1-second 10× burst into near-invisibility over a 5m window. The rule of thumb interviewers want: **`rate()` for alerting and most graphs; `irate()` only for high-resolution debugging of volatile counters, and never inside alerting rules.**

#### Q41. [Theory] How do `honor_labels` and `honor_timestamps` change scrape behavior, and why are they dangerous?

By default, when Prometheus scrapes a target it **attaches the configured `job` and `instance` labels and overwrites any conflicting labels** the target exposes, and it **ignores any timestamps** the target puts on its samples, stamping each sample with the scrape time instead. These defaults exist because Prometheus wants to be the authority on identity (so you can't accidentally spoof another target's `instance`) and on time (so samples are evenly spaced at the scrape interval, which is what `rate()` extrapolation assumes).

`honor_labels: true` flips the label behavior: if the scraped data contains a `job` or `instance` (or any) label that collides with Prometheus's target labels, the **target's value wins** instead of being overwritten or prefixed with `exported_`. This is required when scraping a **federation endpoint or a Pushgateway**, where the data legitimately carries the original source's identity labels and you want to preserve them, not relabel them to the federating server's identity.

```yaml
scrape_configs:
  - job_name: federate
    honor_labels: true        # keep the child Prometheus's job/instance labels
    honor_timestamps: true    # trust the timestamps embedded in the federated samples
    metrics_path: /federate
    params: { 'match[]': ['{__name__=~"job:.*"}'] }
```

The danger: `honor_timestamps: true` makes Prometheus **trust timestamps it didn't generate**. If the source clock is skewed, or samples arrive out of order, or two sources push the same series with overlapping timestamps, you get gaps, "out of order sample" rejections, or wrong rate calculations — because `rate()`'s extrapolation assumes regular spacing. And `honor_labels: true` removes Prometheus's protection against identity collisions: two targets exposing the same `instance` label will silently clobber each other's series. Both options are appropriate for federation/Pushgateway and almost nothing else; using them carelessly produces some of the hardest-to-debug "missing data" incidents.

#### Q42. [Theory] What is the Pushgateway actually for, what are its sharp edges, and why is "just push everything here" an anti-pattern?

The Pushgateway is a **caching layer** that lets short-lived jobs push their metrics to a persistent endpoint, which Prometheus then scrapes normally. Its single legitimate use case is **service-level batch jobs** that exit before any scrape could reach them — a nightly cron job, a CI pipeline step, a one-shot data migration. The job pushes its final metrics (e.g. `batch_last_success_timestamp_seconds`) on completion, and Prometheus reads them from the gateway on the next scrape.

The sharp edges that make it an anti-pattern for general use: (1) The Pushgateway **never forgets** — once a metric is pushed, it persists that value forever until explicitly deleted via the API, even after the job is long gone. So `up` semantics are lost: there's no way to tell "the job ran and reported 0" from "the job hasn't run in a week" — the last value just sits there stale. (2) It becomes a **single point of failure and a cardinality sink** if many jobs push high-cardinality data through it. (3) It **breaks the per-target liveness model**: Prometheus only sees the gateway as up, not the individual jobs.

```
Correct:                              Wrong (anti-pattern):
batch job --push--> Pushgateway       long-running service --push--> Pushgateway
                       ^                                                 ^
                       | scrape                                          | scrape
                  Prometheus                                        Prometheus
(job exits; final value cached)       (you should have let Prometheus PULL /metrics directly)
```

The conceptual rule: **the Pushgateway is for jobs whose lifetime is shorter than the scrape interval, not for converting Prometheus into a push system.** Long-running services should expose `/metrics` and be pulled — that preserves `up`, liveness, and per-instance identity. To handle the "stale forever" problem you instrument batch jobs to push a `_last_success_timestamp` and alert with `time() - batch_job_last_success_timestamp_seconds > threshold`, which detects a job that *stopped running* — something the raw cached value can't.

### 🟠 Advanced — extended

#### Q43. [Theory] Walk through the Prometheus TSDB block on disk: chunks, index, postings, symbol table, and tombstones.

A persistent block is a directory under `data/` named with a ULID (e.g. `01H8Z.../`). Inside it: a `chunks/` subdirectory holding the compressed sample data, an `index` file, a `meta.json` describing the block's time range and stats, and optionally a `tombstones` file. The **chunks** are sequences of up to 120 samples per series, compressed with **Gorilla-style delta-of-delta** timestamp encoding and XOR float compression, yielding roughly 1–2 bytes per sample — this is why Prometheus can hold so much in so little space.

The **index** is where queries actually get fast. It contains a **symbol table** (every distinct label name and value string is stored *once* and referenced by integer ID, deduplicating the enormous repetition in label sets), the **series list** (each series = a sorted set of label-symbol references plus pointers to its chunks), and the **postings lists** — an inverted index mapping each `label_name=label_value` pair to a sorted list of the series IDs that have it. A query like `{job="api", status="500"}` becomes an **intersection of two postings lists**, which is a fast merge of sorted integer arrays. This is the same data-structure idea as a search engine's inverted index, and it's why high cardinality hurts: more series = longer postings lists and a bigger symbol table.

```
block-ULID/
  meta.json        time range, sample/series counts, compaction level
  index            symbol table  +  series (label refs -> chunk refs)  +  postings (label=val -> [seriesIDs])
  chunks/000001    Gorilla-compressed sample chunks (~1-2 bytes/sample)
  tombstones       deletion markers (logical deletes; data removed at next compaction)
```

**Tombstones** implement deletions: when you call the delete-series API, Prometheus doesn't rewrite the immutable chunks — it writes a *tombstone* recording "ignore these series in this time range," and the data is physically removed only at the next compaction. The takeaways for an interviewer: blocks are **immutable** (which is what makes object-storage offload via Thanos/Mimir clean — you just upload finished blocks), the **index is the cardinality cost center**, and deletes are logical-then-physical, so disk doesn't shrink until compaction runs.

#### Q44. [Theory] Explain the head block lifecycle in detail: WAL, m-mapped head chunks, checkpoints, and head GC.

The head is the in-memory portion of the TSDB holding the most recent (~2h, until the next block cut) samples. Three on-disk structures back it for durability and memory management. First, the **Write-Ahead Log (WAL)**: every appended sample, every new series, and every staleness marker is appended to segment files in `wal/` *before* being acknowledged, so a crash can replay them. Second, when a series' in-memory chunk fills (120 samples) it is **flushed to a memory-mapped "head chunk" file** (`chunks_head/`) — the chunk leaves the Go heap and lives in an mmap'd file the OS pages in on demand, dramatically cutting heap pressure while keeping the data queryable. Only the *currently-filling* chunk per series stays on the heap.

Periodically Prometheus writes a **checkpoint**: it compacts the WAL by writing out the still-relevant series/samples and deleting WAL segments older than the checkpoint. This bounds WAL replay time on restart — without checkpoints, replay would have to scan the entire WAL history. Every ~2 hours the head is **truncated**: samples older than the cutoff are compacted into a new immutable persistent block, and **head garbage collection** removes series that no longer have any samples in the head, freeing their entries from the in-memory index.

```
sample in -> WAL append (durability) -> head series chunk (heap)
                                           | chunk full (120 samples)
                                           v
                                  m-mapped head chunk (off-heap, chunks_head/)
   every ~2h:  head truncation -> persistent block written -> head GC drops dead series
   periodically: WAL checkpoint -> old WAL segments deleted -> bounded replay time
```

Why this matters operationally: (1) a **large head = slow startup**, because restart replays the WAL and remaps head chunks — a Prometheus with millions of churning series can take many minutes to become ready, which affects your HA failover math. (2) The **m-mapped head chunks** are why Prometheus 2.19+ uses far less heap than older versions for the same data, and why monitoring RSS vs Go heap can be confusing (mmap'd memory shows as RSS but isn't on the heap). (3) **High series churn** (series constantly appearing/disappearing) is more expensive than high but stable cardinality, because each new series costs a WAL "series" record and index entry, and GC has to clean them up — this is a frequent root cause an interviewer wants you to name.

#### Q45. [Theory] Compare `remote_write` and `remote_read`: their protocols, use cases, and why `remote_read` is rarely the right answer for global query.

`remote_write` is a **push** protocol: Prometheus streams samples out of its WAL to a remote endpoint via Snappy-compressed Protobuf (or the newer Remote-Write 2.0 format with native metadata, exemplars, and reduced overhead). It's how you get data *out* of Prometheus into a long-term store like Mimir, Cortex, Thanos Receive, or a vendor SaaS. It's asynchronous and buffered: samples are read from the WAL tail by sharded queues, so a slow remote backs up the WAL rather than blocking scrapes.

`remote_read` is the inverse — a **pull-on-query** protocol where, at *query time*, Prometheus fetches raw samples *from* a remote endpoint and merges them with its local data to answer a PromQL query. In principle this gives you a "global view" by pointing one Prometheus at several remote stores. In practice it's rarely the right tool because (1) it streams **raw samples** back over the network for the entire query range and series set, which is enormous for any non-trivial query; (2) PromQL functions still execute **locally on the querying Prometheus**, so it doesn't distribute compute — the remote does only the selection; and (3) latency and failure of any remote endpoint directly slows or fails every query.

```
remote_write (push, continuous):   Prometheus WAL --queues--> [Mimir/Thanos/Cortex/SaaS]
remote_read  (pull, per-query):    Prometheus <--raw samples-- [remote store]   (merge locally)

Global query the modern way:       Grafana --> Mimir/Thanos Querier (fans out, dedups, computes)
```

The architectural takeaway: for a true global, horizontally-scaled view you use a **purpose-built query layer** (Mimir's query-frontend/querier, Thanos Querier) that pushes computation down, deduplicates HA replicas, and queries object storage efficiently — *not* `remote_read` chaining. `remote_read` survives for narrow cases: reading a small amount of historical data from an adapter (e.g. an InfluxDB or PostgreSQL backend) during a migration, or backfilling. An interviewer raising this wants you to know that "global view = remote_read fan-out" is a common but wrong instinct.

#### Q46. [Theory] What is Prometheus Agent mode (and Grafana Alloy), and what does it deliberately give up?

**Agent mode** (`--enable-feature=agent`, GA since Prometheus 2.32) runs Prometheus as a **scrape-and-forward-only** process: it discovers targets, scrapes them, writes samples to a WAL, and `remote_write`s them onward — and that's *all*. It **disables local querying, the local TSDB blocks, alerting rules, and recording rules**. The WAL exists only as a durable buffer for remote_write; it's truncated aggressively once samples are confirmed shipped, so disk footprint is tiny.

The point is the **two-tier architecture** for fleets: you run a lightweight agent in every cluster/edge/node whose only job is to reliably get metrics to a central store (Mimir/Cortex/Thanos Receive/SaaS), and you do all querying, alerting, and long-term storage centrally. This slashes per-cluster resource usage and operational surface — no per-cluster retention tuning, no per-cluster query load, no local rule evaluation to manage. **Grafana Alloy** (the successor to the Grafana Agent, which itself unified Prometheus agent + Promtail + OTel Collector) is the broader take on the same idea: one collector that handles metrics, logs, traces, and profiles with a programmable pipeline, speaking both Prometheus and OpenTelemetry.

```
Per cluster:   [ Agent / Alloy ]  scrape -> WAL buffer -> remote_write
                                                               |
Central:                          [ Mimir / Cortex / Thanos Receive ]  <- query + alert here
```

What it gives up — and why that's the trade: agent mode has **no local query and no local alerting**, so if the central tier is unreachable, that cluster *cannot* evaluate its own critical alerts. This is the direct opposite of the classic Prometheus reliability property ("each server is standalone and keeps working during an outage"). The design decision is therefore a real trade-off: you gain massive scale and operational simplicity for a large fleet, but you concentrate the alerting-availability risk in the central tier, which you must then make highly available. Many mature setups run a *hybrid*: agents for the bulk of metrics plus a small full Prometheus per cluster for a handful of locally-evaluated, life-or-death alerts that must survive a central-tier outage.

#### Q47. [Theory] How does the `for` clause work internally, and what happens to a pending alert across restarts and `keep_firing_for`?

When a Prometheus alerting rule has a `for: 10m`, the rule's expression is still evaluated every `evaluation_interval`, but an alert doesn't go to `firing` immediately on the first true evaluation. Instead it enters the **`pending`** state and Prometheus records *when the condition first became true* for each alert series (identified by its label set). The alert transitions `pending → firing` only once the condition has been **continuously true** for the full `for` duration. If the expression returns *no result* for that series at any evaluation in between, the timer **resets** — the alert goes back to inactive, and the clock starts over next time it's true.

This continuous-truth requirement is exactly why a flapping condition never fires with a `for` clause: each blip resets the pending timer. The state lives in memory, which raises the restart question: **on a Prometheus restart, pending state is lost.** A restarting Prometheus re-evaluates and an alert that was 9 minutes into its 10-minute `for` starts its timer again from zero. This is one of several reasons to run HA pairs and to keep `for` durations reasonable for critical alerts — a flapping/restarting Prometheus can delay paging.

```
eval:   T  T  T  F  T  T  T  T  T  T  T  ...
state:  pending(reset on F) ............ -> firing after `for` of continuous T
restart at any point  -> pending timer lost -> recount from next true eval

keep_firing_for: 5m  -> after condition goes false, stay FIRING for 5m more (anti-flap on resolve)
```

The complementary knob is **`keep_firing_for`** (Prometheus 2.42+), which addresses the *resolve* side: by default an alert resolves the instant its condition goes false, which can cause resolve/re-fire flapping for a borderline metric. `keep_firing_for: 5m` keeps the alert `firing` for an extra 5 minutes after the condition clears, so a metric oscillating around the threshold doesn't spam resolve/fire notifications. So `for` debounces the *onset* and `keep_firing_for` debounces the *resolution* — an interviewer asking about flapping wants both halves, plus the awareness that all of this state is in-memory and reset by restarts.

#### Q48. [Theory] Walk through Alertmanager's internal pipeline: dedup, the gossip/HA cluster, and the notification flow.

When Prometheus fires an alert it **pushes** it to Alertmanager's API (Prometheus is the active party — Alertmanager doesn't pull). Inside Alertmanager the alert flows through a pipeline. First, **deduplication**: HA Prometheus replicas all send the same alert, and Alertmanager keys alerts by their full label fingerprint, so identical alerts collapse to one. Next, **routing**: the alert walks the routing tree, matching on labels to find its receiver(s) and the route's grouping/timing config. Then **grouping**: alerts sharing the `group_by` labels are batched into a group, which is what `group_wait` and `group_interval` time. Then **inhibition** and **silencing** filter the group. Finally the **notification stage** dispatches to receivers (Slack, PagerDuty, etc.) with retries.

The subtle internal piece interviewers probe is **HA clustering**. You run multiple Alertmanagers that form a cluster over a **gossip protocol** (HashiCorp memberlist). They are *not* a leader-elected cluster and they do *not* share a database; instead they gossip two things: **silences** and **notification log (nflog) entries**. The nflog is how they avoid double-paging: when one Alertmanager sends a notification for a group, it gossips "I notified group X at time T," and the others see this and suppress their own send for that group. To make this work despite gossip latency, each Alertmanager waits a small, **position-based delay** (its index in the cluster) before sending, so peers have a chance to learn that someone already sent.

```
Prometheus replica A ----\
Prometheus replica B -----> [ Alertmanager cluster ]
                              dedup -> route -> group(group_wait/interval) -> inhibit/silence -> notify
                              gossip(memberlist): silences + nflog ("group X already notified")
                              position delay avoids duplicate sends across peers
```

The consequences to articulate: (1) because dedup happens *in Alertmanager*, running redundant Prometheis is safe — you won't get N× the pages. (2) Because the HA model is gossip-based and **eventually consistent, not strongly consistent**, a network partition between Alertmanagers can briefly cause a duplicate notification (better than a missed one — Alertmanager errs toward at-least-once). (3) A silence created on one node propagates via gossip, so it may take a moment to apply cluster-wide. This AP-leaning design mirrors Prometheus's own philosophy: never miss an alert, tolerate the occasional duplicate.

#### Q49. [Theory] What is `absent()` versus `absent_over_time()`, and why are they the canonical way to alert on "no data"?

A core asymmetry in Prometheus is that you **cannot write an alert on a series that doesn't exist** — if a target stops exposing `http_requests_total` entirely, then `http_requests_total == 0` returns *nothing* (empty vector), and an alert on an empty vector never fires. The absence of data is invisible to ordinary expressions. This is the "missing data" blind spot: the very condition you most want to catch (a metric vanished) produces no series to alert on.

`absent(v)` solves this for the *instant* case: it returns `1` (with the labels you can reconstruct from the selector) **if and only if its argument is an empty instant vector**, and returns nothing if the vector has any series. So `absent(up{job="payments"})` fires when the `payments` job has no `up` series at all — i.e. it dropped out of service discovery, not just went down. `absent_over_time(v[5m])` is the range version: it returns `1` if the series had **no samples at all over the whole window**, which is more robust against a single missed scrape than the instant `absent`.

```promql
# Page if the critical job disappears entirely from SD (no series, so up==0 can't fire)
absent(up{job="payments"})

# More robust: no samples for this metric in the last 10m (tolerates one missed scrape)
absent_over_time(http_requests_total{job="payments"}[10m])

# Pattern: alert if a metric you EXPECT is missing
alert: MetricMissing
expr: absent_over_time(my_critical_metric[10m])
for: 5m
```

The conceptual point: `up == 0` catches "target present but scrape failing"; `absent()` catches "target not even present." You need *both* to fully cover liveness, because they detect different failure modes (a broken scrape vs. a vanished target). A sharp limitation to mention: `absent()` can only reconstruct the labels you wrote *literally* as `=` matchers in the selector — it cannot enumerate which of N expected instances is missing, because there's no data to enumerate from. For per-instance "which one is gone" you need a different technique (e.g. `count by (instance)` against an expected list, or `up == 0`). This is the canonical "alert on no data" pattern, and the canonical interview trap is forgetting that `== 0` silently can't fire on a vanished series.

#### Q50. [Coding] Build a query that detects a metric that has stopped increasing (a stuck counter), and explain the reasoning.

**Problem:** A background worker exposes `jobs_processed_total`. You want to alert when the counter has *stopped advancing* — the worker is wedged — distinguishing that from the worker legitimately being idle, and from the worker having crashed (which `up`/`absent` already cover).

A naive `rate(jobs_processed_total[5m]) == 0` is fragile: it can't tell "stuck" from "idle," and during a true idle period it would false-page. The robust approach combines a rate check with `changes()` / `resets()` awareness and a confirmation that work *should* be happening (e.g. there's a backlog).

```promql
# Core signal: the counter has not increased at all over a meaningful window.
# changes() counts how many times the value changed in the window; 0 == stuck.
- alert: WorkerStuck
  expr: |
    changes(jobs_processed_total[15m]) == 0
    and
    on(instance) queue_depth > 0          # only stuck if there's work waiting
  for: 5m
  labels: { severity: page }
  annotations:
    summary: "Worker {{ $labels.instance }} processed 0 jobs in 15m while queue_depth > 0"
```

**Why `changes()` and not `rate()==0`:** `rate()` extrapolates and can return a tiny non-zero or floating value near boundaries, so `== 0` comparisons on `rate()` are brittle. `changes(v[15m])` counts the literal number of times the sample value changed in the window — for a healthy counter under load this is several, for a wedged worker it's exactly `0`. The `and on(instance) queue_depth > 0` guard is the crucial part: it encodes the business meaning of "stuck" — *zero progress while work is pending* — which separates a genuine hang from a quiet period when there's simply nothing to do.

**Edge cases & complexity:** if the worker also crashed, `jobs_processed_total` may go stale (staleness marker) and `changes()` over the window could itself return empty — so you pair this with `absent_over_time(jobs_processed_total[15m])` and `up == 0` for full coverage of the failure space (stuck / vanished / down). Watch counter resets: `changes()` *does* count a reset (restart from 0) as a change, which is desirable here because a restart means the worker isn't wedged. Complexity is O(samples-in-window) per series — cheap; the `on(instance)` join is the only place to verify your label sets actually match, or the guard silently drops everything.

### 🔴 Expert — extended

#### Q51. [Theory] Explain the PromQL evaluation model end to end: query frontend, range query stepping, and where the engine spends time.

A PromQL **range query** (what a dashboard panel issues) is not evaluated once — the engine evaluates the expression as an **instant query at every `step` across the time range**. A 6-hour panel at a 30-second step is ~720 independent instant evaluations, each of which selects the relevant samples, applies range functions over their windows, runs aggregations, and emits one point per series. This stepping model explains why "the same query" can be cheap at a 1h range and brutal at a 30d range: cost scales with `(range / step) × series-touched × samples-per-window`.

Each instant evaluation has phases. **Selection**: the engine consults the index postings to find matching series, then reads the relevant chunks (from head, m-mapped head chunks, or persistent blocks) for the lookback/range window. **Function/operator evaluation**: range functions like `rate()` run per series over their window; binary operators perform vector matching (the join logic); aggregations group and reduce. The engine spends the bulk of its time in two places: **chunk decoding** (decompressing Gorilla-encoded samples) when the series count or window is large, and **series resolution** when cardinality/postings are huge.

```
Range query [t0..t1] step=S:
   for ts in t0, t0+S, t0+2S, ... t1:
       instant_eval(expr @ ts):  select(postings -> chunks) -> range fns -> ops/joins -> aggregate
Cost ~ (#steps) x (#series matched) x (samples per window)
```

This is why production deployments put a **query frontend** (Mimir, Thanos) in front: it **splits** a long range query into smaller per-day sub-queries that can run in parallel and be cached, **shards** by series for the heavy aggregations, and **caches** results (and even step-aligns queries so cache hits are reusable). It also enforces `max_samples` limits so one pathological query can't OOM the querier. The staff-level point: query performance is a function of the *evaluation model*, not just "the query," and the levers are recording rules (precompute to shrink series/samples touched), step alignment, query splitting/caching, and cardinality control — in that order of impact.

#### Q52. [Theory] How do Thanos and Mimir deduplicate samples from HA Prometheus replicas at query time, and what is the "penalty" deduplication algorithm?

Running two identical Prometheus replicas means every series exists **twice** in long-term storage, scraped at slightly different instants. If you naively merged both, graphs would show doubled rates and jittery duplicate points. The query layer must **deduplicate**, but it can't just "pick one replica," because either replica may have *gaps* (a missed scrape, a restart) that the other covers — you want the union's completeness without the duplication's noise.

Both systems tag each replica's series with an external **replica label** (e.g. `replica="a"` / `replica="b"`, configured as an `external_labels` / HA-tracker label). At query time, the deduplicating merge **ignores the replica label when grouping series** so the two replicas' versions of a logical series are recognized as the same, then interleaves their samples. Thanos uses a **"penalty"-based algorithm**: it walks the merged sample stream preferring one replica, but when that replica has a gap larger than expected, it switches to the other replica's samples to fill the hole, applying a penalty/heuristic on the expected scrape interval so it doesn't flip back and forth on every point (which would reintroduce jitter and corrupt `rate()`).

```
replica a:  x  x  .  x  x  x   (. = missed scrape / gap)
replica b:  x  x  x  x  .  x
dedup:      x  x  x  x  x  x   (penalty algo fills a's gap from b, then settles back)
group key:  series WITHOUT the replica label  -> both recognized as one logical series
```

The trade-offs to articulate: deduplication is a **best-effort reconstruction**, not exact — at the moment it switches replicas you can get a tiny discontinuity, which is why `rate()` over a dedup'd series is statistically fine but a raw sample-by-sample comparison across the boundary may look slightly odd. Mimir handles the same problem with its **HA tracker**, which can instead elect a single "leader" replica per cluster *at ingestion time* (dropping the non-leader's samples up front) to avoid storing duplicates at all — trading a brief gap on leader failover for halved storage. So there are two philosophies: **dedup at query time** (store both, merge on read — more storage, no failover gap) vs. **dedup at ingest time** (store one, accept a small gap when the leader changes). Knowing both, and the penalty algorithm's role, is the expert-level answer.

#### Q53. [Theory] Compare native histograms to classic histograms in depth: storage model, accuracy, aggregation, and migration caveats.

A **classic histogram** is really a *family of separate counter series*: one `_bucket` series per `le` boundary you defined, plus `_sum` and `_count`. A histogram with 20 buckets across 3 services × 5 endpoints is 20 × 15 = 300 cumulative bucket series, and your quantile accuracy is permanently capped by where you guessed the boundaries — observations in a wide bucket are linearly interpolated, so a p99 inside a `[1s, 10s]` bucket is a coarse guess.

A **native (sparse) histogram** (experimental from 2.40, maturing through 3.x) collapses that entire family into **one series** whose value is a complex sample encoding **exponentially-spaced buckets** generated automatically by a `schema` (resolution) parameter. Buckets only exist where there are observations (sparse), and resolution is high (e.g. schema 8 gives ~0.3% relative bucket width). The storage win is dramatic — roughly one series instead of dozens, with far lower index/postings pressure — and accuracy is high *everywhere* in the value range without anyone choosing boundaries. PromQL gains `histogram_quantile()` working directly on the native sample, plus functions like `histogram_count`, `histogram_sum`, and `histogram_fraction`.

| Aspect | Classic histogram | Native histogram |
|---|---|---|
| Series per histogram | N buckets + sum + count | 1 |
| Bucket boundaries | manual, fixed `le` | automatic exponential by `schema` |
| Accuracy | capped by chosen buckets | high, uniform across range |
| Cardinality cost | high (multiplies by `le`) | low |
| Aggregation | `sum by (le)` then quantile | merges natively, resolution-aware |
| Maturity | universal, stable | newer; needs protobuf/CT, client + storage support |

Migration caveats an expert must raise: native histograms require the **Protobuf exposition path** (the text format can't carry them efficiently) and **client-library support**, so older exporters can't emit them; ingestion, remote_write, and the long-term store (Mimir/Thanos) all need versions that understand them; and Grafana/queries must use the native-aware functions. Aggregating native histograms of *different schemas* requires down-converting to the coarser resolution. Many shops run a transition period emitting **both** classic and native for the same metric behind a feature flag, validating dashboards against native before dropping the classic buckets. The headline reason it matters: native histograms make **high-fidelity latency SLOs essentially free** on cardinality, which was previously the main cost barrier to good percentile monitoring at scale.

#### Q54. [Theory] What are the semantics and pitfalls of `predict_linear`, `deriv`, and `holt_winters`/`double_exponential_smoothing` for forecasting?

Prometheus ships a few functions for **forward-looking** alerting, the most used being `predict_linear(v[range], seconds)`. It fits a **simple linear regression** (least-squares line) to the samples in the range window and extrapolates that line `seconds` into the future. The canonical use is disk-fill prediction: `predict_linear(node_filesystem_avail_bytes[6h], 4*3600) < 0` fires when the linear trend says the volume will hit zero within 4 hours — letting you page *before* the disk is actually full, which symptom-on-empty alerting can't do.

```promql
# Page if, at the current 6h trend, the disk runs out within 4 hours
predict_linear(node_filesystem_avail_bytes{mountpoint="/"}[6h], 4 * 3600) < 0

# Instantaneous per-second derivative of a gauge (slope), for trend dashboards
deriv(node_filesystem_avail_bytes[1h])
```

The pitfalls are all about the **linearity assumption**. `predict_linear` is blind to non-linear behavior: a disk that fills slowly then suddenly spikes (log burst) will be predicted *late*; a sawtooth pattern (fills then gets cleaned nightly) will produce wildly swinging predictions depending on where in the cycle the window sits — it may predict imminent doom right before the nightly cleanup. `deriv()` is its sibling that returns just the per-second slope (also via regression) and has the same linear-fit caveat. The right mitigation is choosing a window long enough to smooth the cycle but short enough to react, and combining the prediction with a current-headroom guard (`and node_filesystem_avail_bytes < threshold`) so you don't page on a confident prediction that's still days away.

`holt_winters` — **renamed to `double_exponential_smoothing` in PromQL** to correct a long-standing misnomer (it never did the *seasonal* triple-exponential Holt-Winters, only trend+level double smoothing) — applies exponential smoothing with `sf` (smoothing/level factor) and `tf` (trend factor) parameters to produce a smoothed value that follows trend without seasonal awareness. The expert nuance: despite the name, **PromQL has no true seasonal forecasting**, so don't reach for it expecting day-of-week or business-hours seasonality detection — that belongs in a dedicated anomaly-detection system or Grafana ML, not core PromQL. The broad lesson: Prometheus forecasting is intentionally simple (linear / single-trend), excellent for monotone resource-exhaustion prediction, and unsuitable for anything seasonal or non-linear.

#### Q55. [Theory] Why is Prometheus AP rather than CP, and where does that choice leak into observable behavior?

Framed in CAP terms, Prometheus deliberately chooses **availability and partition-tolerance over strong consistency**. The design axiom (from Brazil/SoundCloud) is that a monitoring system is *most needed precisely when the infrastructure is broken* — during a partition or outage — so it must keep functioning standalone even when it can't talk to peers, its remote store, or half the network. A CP design (refuse to serve unless a quorum agrees) would mean your monitoring goes dark exactly when you're trying to debug an outage, which is unacceptable. So each Prometheus is a self-contained island: local scrape, local storage, local rule evaluation, local alerting.

This AP choice **leaks into observable behavior** in concrete ways an expert should enumerate. (1) **HA replicas disagree slightly**: two servers scrape at different instants, so their samples and `rate()` outputs differ marginally — a graph from replica A won't be byte-identical to replica B, and that's expected, not a bug. (2) **Alertmanager is at-least-once**: under a partition between Alertmanager peers you may get a *duplicate* page rather than a missed one — the gossip dedup is eventually consistent and errs toward over-notifying. (3) **No global "correct" value exists** for a series at an instant across the fleet; deduplication in Thanos/Mimir is a *reconstruction* (the penalty algorithm), not a consensus. (4) **remote_write is asynchronous and lossy under prolonged outage**: if the remote is unreachable longer than WAL retention, those samples are gone — the local server prioritized staying up and scraping over guaranteeing durable delivery.

```
CP system:   refuse/block on partition  -> consistent but UNAVAILABLE during outage (when you need it most)
Prometheus:  serve local truth always   -> AVAILABLE during outage, mildly inconsistent across replicas
Leaks: replica graphs differ | at-least-once alerts | dedup is reconstruction | remote_write lossy if WAL overflows
```

The maturity signal is connecting the philosophy to operational practice: you don't try to make Prometheus consistent — you *embrace* the AP model by running redundant replicas (dedup'd by Alertmanager/Mimir), treating minor cross-replica graph differences as normal, sizing the WAL for your worst expected remote-store outage, and adding a **dead-man's-switch (`Watchdog`) alert** that always fires so you can detect when the *availability* you depend on has itself failed. Prometheus optimizes for "approximately right and always up" — every quirk above is a direct, intentional consequence of that.

#### Q56. [Theory] Compare Prometheus's metric model with OpenTelemetry metrics, and explain the friction points when bridging them.

Prometheus and OpenTelemetry (OTel) metrics overlap heavily but were designed from different ends. Prometheus is **pull-first, dimensional via labels, and counter-as-cumulative**: a counter monotonically rises and you take `rate()`. OTel is **push-first (via OTLP), with a richer instrument set** (Counter, UpDownCounter, Gauge, Histogram, plus async observers) and crucially supports **two temporalities**: *cumulative* (like Prometheus) and *delta* (each export reports the change since the last export). OTel also separates *resource attributes* (about the producing entity) from *metric attributes* (the dimensions), where Prometheus flattens everything into one label space.

The friction points when bridging — Prometheus now has an **OTLP receiver** and exporters push OTel → Prometheus — are several. (1) **Delta vs cumulative**: Prometheus's storage and `rate()` assume cumulative counters. Delta-temporality OTel data must be converted to cumulative on ingest, which requires *stateful* aggregation (remembering the running total per series) — a meaningful complexity and memory cost, and a correctness hazard across restarts. (2) **Naming and units**: OTel uses dotted names (`http.server.request.duration`) and explicit unit metadata; Prometheus uses `_`-delimited names with unit *suffixes* (`_seconds`, `_bytes`, `_total`). The bridge mangles dots to underscores and appends unit/`_total` suffixes, which can surprise people comparing dashboards across systems. (3) **Attribute cardinality**: OTel resource attributes can carry high-cardinality identifiers that, flattened into Prometheus labels, become a cardinality bomb if not curated.

```
OTel:  instruments (Counter/UpDownCounter/Gauge/Histogram + async)  |  delta OR cumulative  |  resource attrs + metric attrs  |  dotted names + units
Prom:  counter/gauge/histogram/summary                              |  cumulative only       |  one flat label space          |  snake_case + unit suffixes
Bridge frictions:  delta->cumulative (stateful)  |  dot->underscore + _total/_unit suffixes  |  resource attrs -> label cardinality
```

The 2026 reality to convey: convergence is happening — Prometheus added native OTLP ingestion, OpenMetrics aligns the formats, and exemplars link metrics to OTel traces — so the practical recommendation is to **standardize instrumentation on OTel for vendor-neutrality** but be deliberate about *where* the cumulative conversion and attribute curation happen (usually in a collector/Alloy before Prometheus), and to enforce naming/unit and cardinality conventions at that boundary. An expert answer names delta-vs-cumulative as the single deepest semantic mismatch, because it's the one that silently breaks `rate()` if mishandled.

#### Q57. [Practical] How does Grafana actually execute a panel query, and where do query-time transformations, `$__rate_interval`, and "max data points" fit?

When a Grafana time-series panel renders, it issues a **range query** to its data source with three computed parameters the user rarely sees: the **time range** (from the picker), the **`step`/interval**, and **`maxDataPoints`**. Grafana derives the step from the panel's pixel width and `maxDataPoints` — there's no point returning 100,000 points to draw on an 800-pixel-wide panel, so it asks the data source to return roughly one point per pixel. That computed step is exposed to PromQL as the `$__interval` variable, and it's why the *same* dashboard query returns coarser data when you zoom out: the step grows, so each point covers more time.

`$__rate_interval` exists to fix a specific bug class: if you write `rate(metric[$__interval])` and zoom in until `$__interval` becomes *smaller than the scrape interval*, the range window contains fewer than two samples and `rate()` returns **empty** — your graph mysteriously goes blank. `$__rate_interval` is a Grafana macro that computes `max($__interval + scrape_interval, 4 × scrape_interval)`, guaranteeing the rate window always spans enough samples regardless of zoom. This is why the standing advice is "**always `rate(x[$__rate_interval])`, never a hardcoded `[5m]` and never bare `[$__interval]`**."

```
panel width (px) + maxDataPoints  ->  step ($__interval)  ->  range query(from, to, step)
zoom out  -> step grows  -> $__interval grows  -> fewer, coarser points
$__rate_interval = max($__interval + scrape, 4*scrape)   -> rate() never goes empty on zoom-in
```

The other half is **query-time transformations**, which run **in Grafana, after** the data source returns data — they are *not* PromQL. Transformations join two queries by a field, compute new fields, do organize/rename/filter, reduce a series to a single stat, or merge frames. The practical and architectural distinction an interviewer wants: do **aggregation and heavy math in PromQL/recording rules** (pushed down to Prometheus, cached, cheap to repeat) and reserve **transformations for presentation-layer reshaping** that PromQL can't express or that joins across *different data sources* (e.g. correlating a Prometheus series with a SQL lookup table). Overusing transformations for math that belongs in PromQL pushes load onto the browser/Grafana backend and breaks alerting (alert rules evaluate the query, not the transforms). Knowing that the panel's step, `maxDataPoints`, `$__rate_interval`, and transformations are all *Grafana-side* concepts layered on top of the data source's range query is the staff-level mental model.

#### Q58. [Theory] What exactly does the `/federate` endpoint return, and why is pulling raw series through it an anti-pattern?

`/federate` is a special Prometheus endpoint that, given one or more `match[]` selectors, returns the **current value of every matching series** in the standard exposition format — so another Prometheus can *scrape* it like any target. Critically, it returns the **most recent sample per series at scrape time** (an instant snapshot), with the original series' labels preserved (which is exactly why federation scrape jobs set `honor_labels: true`). It is not a streaming or historical export — each federation scrape grabs the latest point of each selected series.

```yaml
scrape_configs:
  - job_name: 'federate-global-aggregates'
    honor_labels: true
    metrics_path: '/federate'
    params:
      'match[]':
        - '{__name__=~"job:.*"}'        # ONLY pre-aggregated recording-rule series
        - 'up'
    static_configs:
      - targets: ['child-prometheus-eu:9090', 'child-prometheus-us:9090']
```

Federation is designed for **cross-cluster rollups of *pre-aggregated* data**: each child Prometheus uses recording rules to compute a small set of `job:`-prefixed aggregates, and a parent federates *only those*, giving a global dashboard without copying raw data. The anti-pattern is using a broad matcher like `{__name__=~".+"}` to pull *all raw series* from children into the parent. This fails for several compounding reasons: (1) the parent inherits the **sum of all children's cardinality**, so it OOMs faster than any single child; (2) every federation scrape transfers and reparses a huge payload, making scrapes slow and prone to timeout (and a timed-out federation scrape produces a `down` + data gap); (3) you've now built a fragile, lossy, lower-resolution **copy** of all your data with none of the benefits of a real long-term store; and (4) instant-snapshot semantics mean you *lose* samples between federation scrapes — the parent only sees one point per child-scrape, so rates computed on federated raw counters are degraded.

The expert framing: federation answers "give me a few aggregate numbers from many clusters," not "give me all the data from many clusters." For the latter you use `remote_write` to a horizontally-scalable store (Mimir/Thanos), which streams *every* sample, deduplicates HA replicas, retains long-term, and distributes query compute. The interview tell is whether you instinctively scope `match[]` to recording-rule aggregates and reach for remote_write the moment someone says "central copy of everything."

#### Q59. [Theory] Explain how chunk encoding (delta-of-delta + XOR) achieves ~1–2 bytes per sample, and what breaks that compression.

A Prometheus sample is a (timestamp int64, value float64) pair — 16 bytes raw. Stored naively that's enormous at millions of samples/second. The TSDB achieves **~1.3 bytes/sample on typical data** using the **Gorilla** compression scheme (from Facebook's Gorilla TSDB paper), applied separately to timestamps and values. For **timestamps**, it uses **delta-of-delta**: scrapes happen at a near-constant interval, so the delta between consecutive timestamps is nearly constant, which means the *delta of those deltas* is almost always **zero**. A zero delta-of-delta is encoded in a **single bit**. Only when the scrape interval jitters does it spend more bits, and the encoding uses variable-length buckets so small jitters cost few bits.

For **values**, it uses **XOR compression**: it XORs each float with the previous one. For metrics that change slowly or hold steady (a gauge hovering, a counter ticking up by similar amounts), consecutive floats share most of their bits, so the XOR has many leading and trailing zero bits; the encoder stores only the meaningful middle run plus a couple of control bits. A value that doesn't change at all XORs to zero and costs a single bit.

```
timestamps:  t increases by ~scrape_interval each time
             delta:           +15.0s +15.0s +15.0s +15.1s
             delta-of-delta:    0      0     +0.1    -> mostly ZERO -> 1 bit each
values:      XOR with previous float -> slow-changing => many shared bits => few stored bits
Result: ~1-2 bytes/sample for typical, regular, slow-moving series
```

What **breaks** this compression — and an expert should name these because they explain real-world disk blowups: (1) **irregular scrape timing** (jittery intervals, `honor_timestamps` with skewed clocks, sparse/missed scrapes) ruins the delta-of-delta zeros, inflating timestamp cost. (2) **High-entropy values** — metrics that swing randomly every sample (e.g. a raw nanosecond timestamp stored as a value, or a noisy gauge with full float precision) have XOR results with no shared bits, approaching the uncompressed 8 bytes/value. (3) **High churn / short-lived series** waste the fixed per-chunk and per-series overhead because chunks never fill their 120-sample target before the series dies. The practical consequences: prefer regular scrape intervals, avoid storing high-precision noisy values (round where sensible), and remember that **cardinality and churn dominate storage far more than the per-sample bytes** — but when per-sample bytes *do* blow up, it's almost always one of these three compression-breakers, and `index` size (cardinality) versus `chunks` size (samples) in the block tells you which problem you have.

#### Q60. [Practical] Design a query-cost / cardinality governance system for a large multi-tenant Prometheus/Mimir platform. What do you measure and enforce?

At platform scale the failure mode isn't "one bad query" — it's **dozens of teams independently creating cardinality and expensive queries** until the shared system degrades for everyone. Governance needs three pillars: **visibility, limits, and feedback loops**. For **visibility**, I'd expose per-tenant cardinality and cost dashboards built from the platform's own meta-metrics: active series per tenant (`cortex_ingester_memory_series` / Mimir's per-tenant series), ingestion rate, top metrics by series count, churn rate, and query stats (samples touched, duration, frontend cache hit ratio). The single most useful query is "top metrics by cardinality per tenant," which turns an abstract limit into a name-and-shame list of specific offending metrics.

```promql
# Top 10 metric names by series count (run per tenant)
topk(10, count by (__name__)({__name__=~".+"}))

# Per-tenant active series approaching their limit (Mimir)
cortex_ingester_memory_series_per_user / on(user) ingester_limit_series_per_user > 0.8

# Expensive queries: those touching the most samples
topk(10, cortex_query_frontend_queries_total)   # plus query-stats logs for samples scanned
```

For **enforcement**, Mimir/Cortex provide **per-tenant limits**: `max_global_series_per_user` (hard cap on active series — protects the cluster from one tenant's explosion), `max_samples_per_query` and `max_fetched_chunk_bytes` (kill pathological queries before they OOM the querier), `ingestion_rate` limits, and query timeouts. The key design principle is **isolation**: a hard per-tenant series limit means a cardinality bomb in team A's metrics gets *that team* rate-limited/rejected at ingestion rather than taking down the shared ingesters for everyone. I'd set these as defaults with an exception process, and drop high-cardinality/low-value metrics at `write_relabel_configs` before they ever reach the central store.

The **feedback loop** is what makes governance stick rather than being a one-time cleanup: (1) a **CI/CD lint** on metric definitions that rejects known-unbounded label names (`user_id`, `request_id`, `email`, raw URLs) and enforces naming/unit conventions before code merges — catching the problem at the source, cheapest possible point; (2) **cost attribution** so each tenant sees their series/query cost (and ideally a chargeback figure), which aligns incentives — teams self-optimize when they own the bill; (3) **alerts on the meta-metrics** (a tenant crossing 80% of its series limit, a sudden churn spike) so platform engineers engage *before* a limit is hit and ingestion starts rejecting. The staff-level insight to articulate: cardinality governance is a **socio-technical** problem — limits and dashboards are necessary but the durable fix is shifting the cost left into CI and making teams feel the cost of their own metrics, exactly as you'd govern any shared resource.

#### Q61. [Theory] What is the difference between `count_over_time`, `changes`, `resets`, and `delta` on a range vector, and when does each apply?

These four functions all take a *range vector* and reduce each series' window to a number, but they answer very different questions, and mixing them up produces subtly wrong alerts. **`count_over_time(v[w])`** returns the **number of samples** present in the window — it counts data points, not values, so it's used to detect sparse/missing data (e.g. `count_over_time(up[10m]) < 5` flags a target that should have ~40 samples but has few). It says nothing about the values themselves.

**`changes(v[w])`** counts **how many times the value changed** between consecutive samples in the window. For a steady gauge it's 0; for a counter under load it's roughly the number of samples (since each scrape increments it). It's ideal for "is this thing moving at all?" detection — a stuck counter (`changes == 0`) or a flapping gauge (`changes` high). **`resets(v[w])`** counts how many times the value **decreased** (a counter reset / restart) — `resets(process_start_time_seconds[1h])` or `resets(some_counter[1h])` reveals how often a process restarted, which is invaluable for crash-loop detection that `up` alone misses.

```
samples:   5  5  7  7  3  9
count_over_time -> 6      (six samples present)
changes        -> 3      (5->7, 7->3, 3->9 are changes; 5->5 and 7->7 are not)
resets         -> 1      (only 7->3 is a decrease)
delta          -> 9 - 5 = 4   (first-to-last, FOR GAUGES, extrapolated; ignores resets!)
```

**`delta(v[w])`** computes the difference between the **first and last sample** of a *gauge* (it's the gauge analog of `increase` for counters) and, like `rate`, **extrapolates to the window edges**. The critical trap: **`delta` is for gauges only and does NOT account for counter resets** — applying it to a counter that restarted gives a meaningless negative number, which is precisely the bug `increase()`/`rate()` were built to avoid. So the decision rule: monotonic counter → `rate`/`increase`/`irate` (reset-aware); gauge net change → `delta`/`deriv`; "is data present" → `count_over_time`; "how many value transitions" → `changes`; "how many restarts/resets" → `resets`. An interviewer asking this is checking whether you understand that the right reducer encodes the *semantics* of the metric type, and that `delta`-on-a-counter is a classic silent bug.

#### Q62. [Theory] How does Alertmanager's grouping, `group_wait`, `group_interval`, and `repeat_interval` interact, and how would you tune them by severity?

These three timers control *when* notifications go out for a group of alerts and are the most misconfigured part of alerting. A **group** is the set of alerts sharing the labels in `group_by`. **`group_wait`** is how long Alertmanager waits *after the first alert in a new group fires* before sending the initial notification — its purpose is to **collect related alerts** so a deploy that takes down 30 pods produces *one* notification listing 30, not 30 separate pages, at the cost of delaying the first page by `group_wait` (default 30s). **`group_interval`** is the minimum time between notifications *for the same group when its contents change* (new alerts join, or some resolve) — default 5m — so a churning group doesn't spam you every few seconds. **`repeat_interval`** is how often an *unchanged, still-firing* group is **re-sent as a reminder** (default 4h), ensuring an ongoing incident isn't forgotten.

```
new group fires --[group_wait 30s]--> first notification (batched)
group contents change --[>= group_interval 5m]--> updated notification
group unchanged but still firing --[every repeat_interval 4h]--> reminder
```

The interaction subtlety: a too-large `group_by` (e.g. grouping everything by just `alertname`) batches *unrelated* incidents together and applies one set of timers to all of them, so a critical alert can be delayed waiting for `group_wait` alongside slow-moving warnings, or buried in a noisy group. A too-small `group_by` (grouping by `instance`) defeats the purpose and floods you per-instance. The right key groups alerts that an on-call would genuinely want to see together — typically `['alertname', 'cluster', 'service']` or `['alertname', 'namespace']`.

Tuning by severity is the expert move: route **`severity=page`** alerts to a sub-route with **short timers** — `group_wait: 0s`–`10s`, `group_interval: 1m`, `repeat_interval: 1h` — so critical pages go out almost immediately and remind frequently; route **`severity=warning`/`ticket`** to a sub-route with **relaxed timers** — `group_wait: 1m`, `group_interval: 10m`, `repeat_interval: 12h` — to reduce noise. This tiering is why you build a routing tree with per-severity child routes rather than one flat config. The connection to Q30 (late alert post-mortem): every one of these timers *adds latency by design*, so when a critical alert "fired late," the post-mortem must inspect whether `group_wait` + `group_interval` for that route were tuned for paging or left at the noisy defaults.

#### Q63. [Theory] Explain how high series churn differs from high cardinality, why churn is often worse, and how to detect it.

People conflate the two, but they're distinct failure modes. **Cardinality** is the count of *active* (currently-reported) series at a point in time — it drives steady-state memory (head index, postings) and disk. **Churn** is the *rate at which series are created and destroyed* over time — old series going stale and new ones appearing. A service with 100k stable series has high cardinality but zero churn. A service that creates a *new* series every deploy because it embeds a `pod_name` or `container_id` or `version_hash` label, and those pods are constantly recycled, may have only 100k *active* series at any instant but create *millions over a day*.

Churn is often **worse than equivalent steady cardinality** for several internal reasons rooted in the storage engine (Q43/Q44). Every new series costs a **WAL "series" record** and an **index/postings insertion**; every dead series must be **garbage-collected** from the head and leaves behind index entries in already-written blocks that can't be reclaimed until compaction. The **block index** accumulates *all* series that were ever active during the block's 2h window — so a churny workload produces blocks with bloated indexes (huge postings, huge symbol table) even though active count is modest, slowing every query that scans those blocks. Head GC and WAL checkpointing also do more work. The result: queries over historical periods get slow, blocks are large, and startup (WAL replay) lengthens — symptoms that pure cardinality-counting (`prometheus_tsdb_head_series`) won't reveal because the active count looks fine.

```
High cardinality, no churn:   |==== 100k series, all alive the whole time ====|   (index stable)
High churn, modest active:    series appear/die constantly
   block 2h window must index EVERY series that lived during it -> index BLOAT
   detect: scrape_series_added rate, prometheus_tsdb_head_series_created_total
```

To **detect** churn specifically: watch `scrape_series_added` (new series added per scrape — high and sustained means churn at the source), `prometheus_tsdb_head_series_created_total` and `..._removed_total` rates (creation/removal velocity), and compare a block's **index size to its active series count** — a large index relative to active series is the churn fingerprint. The fix targets the *labels causing identity to change*: drop or stabilize the offending label (`pod_name`, `instance` with random suffixes, `version`, `container_id`) via `metric_relabel_configs`, or aggregate it away. The staff-level point an interviewer wants: "active series count looks fine but the system is slow and blocks are huge" is the signature of churn, and you diagnose it with the *_created/_removed* and `scrape_series_added` metrics, not the head-series gauge.

#### Q64. [Theory] What changed in Prometheus 3.0, and what are the migration considerations from 2.x?

Prometheus 3.0 (released late 2024) was the first major version in ~7 years and is mostly an **evolution that promotes features to stable and modernizes defaults** rather than a rewrite. The headline items: **native histograms** matured toward stable/default-capable; a **brand-new UI** (rewritten, React/PromLens-influenced) replacing the classic web UI; **OTLP ingestion** became a first-class, supported ingestion path (Prometheus as an OTLP receiver), cementing the OpenTelemetry convergence; and **UTF-8 metric and label names** are now allowed, relaxing the historical `[a-zA-Z_][a-zA-Z0-9_]*` restriction — which matters specifically for OTel interop where dotted names like `http.server.duration` can now be represented more faithfully (with a quoting syntax in PromQL: `{"http.server.duration"}`).

Migration considerations an expert should flag, because 3.0 *did* make breaking changes: (1) **Removed long-deprecated flags and the old `PromQL` behaviors** — range selectors and some lookback/staleness defaults were tightened, and a few legacy config options were dropped, so you must read the migration guide rather than upgrade blindly. (2) **Default behavior changes** around things like the agent mode being a flag, the new UI being default (operators used to the old UI need to adjust, and any scraping/automation that parsed the old UI's pages breaks). (3) **UTF-8 names** mean tooling, dashboards, and downstream systems (recording-rule naming conventions, alerting, exporters) must tolerate the new quoting syntax — if a downstream store or Grafana version doesn't understand it, you get friction. (4) **Native histograms over remote_write/long-term storage** require that your *entire pipeline* (Mimir/Thanos/clients) is on versions that support them.

| Area | Prometheus 2.x | Prometheus 3.x |
|---|---|---|
| Native histograms | experimental | matured / stable-capable |
| Web UI | classic | rewritten React UI (default) |
| OTLP ingestion | experimental | first-class supported receiver |
| Metric/label names | ASCII-restricted | UTF-8 allowed (quoted syntax) |
| Deprecated flags/behaviors | present | several removed (breaking) |

The pragmatic migration play: read the official 3.0 migration guide, **test in staging** with your real config (it will flag removed flags and behavior changes), verify your **downstream stack** (Grafana, Mimir/Thanos, alerting) understands UTF-8 names and native histograms before relying on them, and treat the upgrade as a **breaking major** despite the largely-compatible feel — the danger is the *defaults and removed options*, not the daily query experience. The interview signal is knowing 3.0 is "promotion + modernization + OTel convergence + UTF-8," and that the real risk in upgrading is removed flags and pipeline-wide native-histogram/UTF-8 support, not a rewritten query engine.

#### Q65. [Practical] How do you reliably backfill historical data into Prometheus, and what are the constraints?

The constraint that makes backfilling hard is fundamental to the TSDB: Prometheus's head block and WAL are optimized for **append-mostly, roughly-in-order, recent** writes. The head only accepts samples within a bounded window of "now," and it **rejects out-of-order samples** by default (older than the most recent sample for that series) — so you cannot simply replay old data through the normal ingestion path. This is why "import last month's data" is not a simple write.

There are two supported mechanisms. (1) **`promtool tsdb create-blocks-from`** can generate **persistent blocks directly** from OpenMetrics-format data or from rules, *bypassing the head/WAL entirely* — you produce immutable blocks and drop them into the data directory, where the next compaction/load picks them up. This is the canonical way to import a historical dataset (e.g. migrating from another system, or recreating recording-rule series for a past period):

```bash
# Backfill raw samples from an OpenMetrics dump into native TSDB blocks
promtool tsdb create-blocks-from openmetrics historical_data.om /var/lib/prometheus/data

# Backfill recording-rule series for a past time range (recompute rules over old data)
promtool tsdb create-blocks-from rules \
  --start 2026-05-01T00:00:00Z --end 2026-05-15T00:00:00Z \
  --url http://localhost:9090 \
  recording_rules.yml
```

(2) For *near-real-time* out-of-order tolerance (not bulk backfill, but late-arriving samples), Prometheus 2.39+ added the **`out_of_order_time_window`** TSDB option, which accepts samples up to a configured age behind the head — useful for sources with mild clock skew or buffering, but it costs extra memory (a separate out-of-order head) and is *not* a substitute for block-level backfill of large historical ranges.

The constraints/caveats to state: backfilled blocks must **not overlap** the time range Prometheus is actively writing (you can't backfill into "now"), the blocks must use compatible formats and be placed before Prometheus loads them (or trigger a reload), and **recording-rule backfill recomputes from raw data that must still exist** — you can't recreate an aggregate for a period whose underlying raw series were already deleted by retention. At platform scale, the same logic applies to **Mimir/Thanos**, which have their own block-upload/backfill tooling against object storage. The expert framing: backfill goes through the **block layer, not the ingestion layer**, precisely because the head/WAL are deliberately append-recent — and you size `out_of_order_time_window` only for *late* data, never as a bulk-import path.

#### Q66. [Practical] Design alerting and dashboards for Prometheus monitoring *itself* (meta-monitoring). What signals matter and why?

The deepest operational lesson in this space is **"who watches the watcher?"** — if your monitoring stack degrades silently, every other alert becomes untrustworthy, and you discover the gap during an incident when it's too late. Meta-monitoring must therefore be **independent** of the thing it monitors: ideally a *separate, simpler* Prometheus (or a managed/external check) watches the primary stack, so a failure of the primary doesn't also disable its own alerting. The cornerstone is a **dead-man's-switch (`Watchdog`)**: a rule that **always evaluates true** and fires a constant alert into Alertmanager, routed to a receiver that *expects* to see it regularly (e.g. a Healthchecks.io/Cronitor ping). If the constant alert ever *stops* arriving, the external system pages — proving the entire pipeline (scrape → rule eval → Alertmanager → notification) is alive end to end.

```yaml
groups:
  - name: meta
    rules:
      - alert: Watchdog          # always firing; its ABSENCE downstream means the pipeline is broken
        expr: vector(1)
        labels: { severity: none }
        annotations: { summary: "Alerting pipeline is alive (dead-man's switch)." }
```

Beyond the Watchdog, the signals that matter map to each internal subsystem (using Q34/Q43/Q44 knowledge): **ingestion health** — `prometheus_tsdb_head_series` (cardinality trend), `scrape_samples_scraped` and `scrape_series_added` (cardinality/churn), `up` for self-scrape; **rule engine** — `prometheus_rule_group_last_duration_seconds` vs `evaluation_interval` and `prometheus_rule_evaluation_failures_total` (rules taking longer than their interval means alerts evaluate late — directly the Q30 failure); **storage** — WAL corruption/replay (`prometheus_tsdb_wal_corruptions_total`), compaction failures, disk headroom via `predict_linear` on free space; **remote_write** (if used) — `prometheus_remote_storage_samples_pending`/`_failed_total` and the `highest_sent` vs `highest_timestamp` lag, since a stalled remote_write silently loses data once the WAL overflows; **Alertmanager** — `alertmanager_notifications_failed_total`, cluster peer count (`alertmanager_cluster_members`), and config-reload success.

```promql
# Rules evaluating slower than their interval -> alerts/recording will lag
prometheus_rule_group_last_duration_seconds > prometheus_rule_group_interval_seconds

# remote_write falling behind (data loss risk if WAL overflows)
prometheus_remote_storage_highest_timestamp_in_seconds
  - ignoring(remote_name,url) prometheus_remote_storage_queue_highest_sent_timestamp_seconds > 120

# Notification delivery failing
rate(alertmanager_notifications_failed_total[5m]) > 0
```

The design principles to articulate: (1) **independence** — meta-monitoring lives outside the monitored stack, or you have a blind spot exactly when it matters; (2) **the dead-man's switch is non-negotiable**, because it's the only alert that detects *total* pipeline failure (every other alert assumes the pipeline works); (3) **monitor the rule-eval and remote_write lag**, since those are the silent latency/data-loss sources behind "the alert fired late" and "we lost a window of data" post-mortems; and (4) **validate with game-days** — deliberately kill a Prometheus, stall remote_write, and break an Alertmanager peer to confirm the meta-alerts actually fire. This closes the loop on the recurring theme of this guide: Prometheus optimizes for being *available during outages*, so the one thing you must prove continuously is that your monitoring itself is among the things still standing.

## 🧩 Extended Questions — Supplemental Set A: Practical & Theory

### 🟢 Basic — extended

#### Q67. [Practical] How do you reload Prometheus configuration without restarting, and what gets reloaded versus what doesn't?

Prometheus supports a **hot reload** of its configuration so you don't have to restart the process (which would trigger a costly WAL replay and a gap in scraping). You trigger it by sending a `SIGHUP` to the process, or — if you started Prometheus with `--web.enable-lifecycle` — by POSTing to the `/-/reload` HTTP endpoint. The lifecycle endpoint is the production-friendly path because config-management tooling (a ConfigMap reloader sidecar in Kubernetes, Ansible, etc.) can hit it over HTTP without process signals.

```bash
# Option 1: signal (works always)
kill -HUP $(pgrep prometheus)

# Option 2: HTTP, requires --web.enable-lifecycle at startup
curl -X POST http://localhost:9090/-/reload

# Validate BEFORE reloading so you don't apply a broken config
promtool check config /etc/prometheus/prometheus.yml
promtool check rules /etc/prometheus/rules/*.yml
```

What reloads cleanly: `scrape_configs` (targets, relabeling, intervals), `rule_files` (recording and alerting rules), `alerting`/Alertmanager targets, `remote_write`/`remote_read`, and service-discovery configs. What does **not** reload: command-line flags (`--storage.tsdb.retention.time`, `--web.listen-address`, feature flags, the storage path) — those are process-startup-only and require a real restart. If the new config is invalid, Prometheus **rejects the reload and keeps running the old config**, which is why you always `promtool check config` first; a failed reload increments `prometheus_config_last_reload_successful` to 0, and alerting on that gauge (`prometheus_config_last_reload_successful == 0`) is a standard meta-monitoring rule. The practical pitfall interviewers probe: people change a flag, hit `/-/reload`, see nothing happen, and conclude reload is broken — when in fact flags simply aren't reloadable.

#### Q68. [Practical] What do `scrape_interval` and `scrape_timeout` actually control, and how do you tune them safely?

`scrape_interval` is how often Prometheus scrapes a target; `scrape_timeout` is how long it waits for a single scrape's HTTP response before giving up and recording the scrape as failed (`up=0`). The hard constraint is **`scrape_timeout` must be ≤ `scrape_interval`** — Prometheus rejects a config where a scrape could still be in flight when the next one is due. Defaults are 15s/10s globally, overridable per job. These two numbers set the *floor* on detection latency: a target that dies is detected no faster than one `scrape_interval`, which then ripples into rule evaluation and the `for` clause (see Q30).

```yaml
scrape_configs:
  - job_name: fast-critical          # low-latency detection for a key service
    scrape_interval: 5s
    scrape_timeout: 4s
    static_configs: [{ targets: ["api:8080"] }]
  - job_name: fat-exporter           # huge /metrics payload, scrape less often
    scrape_interval: 60s
    scrape_timeout: 30s
    static_configs: [{ targets: ["kube-state-metrics:8080"] }]
```

Tuning trade-offs: **shorter intervals** mean faster detection and finer graphs but more CPU, more samples stored (cost scales linearly), and more load on the target's `/metrics` endpoint. **Longer intervals** save resources but blur `rate()` (you need `[range]` ≥ 2× the interval to get any value, and `$__rate_interval` to keep graphs from going empty). The right move is **per-job tiering**: 5–15s for user-facing services you page on, 30–60s for fat exporters (kube-state-metrics, big databases) whose `/metrics` is slow or enormous. Watch `scrape_duration_seconds` — if it creeps toward `scrape_timeout`, you're about to start dropping scrapes, and the fix is either a longer timeout, a longer interval, or reducing what the target exposes (via `metric_relabel_configs` or fixing the exporter). Never set a global 1s interval "to be safe" — that multiplies your entire storage bill 15× for marginal detection gain on metrics nobody graphs at that resolution.

#### Q69. [Practical] How do you debug a target that shows up as DOWN in the Prometheus Targets page?

The Targets page (`/targets`, or `/classic/targets` / the new UI's targets view) lists every target with its health, last scrape time, last scrape duration, and — critically — the **error string** for failed scrapes. That error is your first and best clue, and it falls into a few buckets. I work through them top-down.

```bash
# 1. Reproduce the scrape exactly as Prometheus does it, from the Prometheus host/pod
curl -v http://target:8080/metrics
# connection refused -> process down or wrong port
# 404 -> wrong metrics_path
# 401/403 -> auth missing (need bearer_token_file / basic_auth / tls_config)
# timeout / hang -> endpoint too slow (raise scrape_timeout or fix the exporter)
# context deadline exceeded -> same: scrape exceeded scrape_timeout

# 2. Confirm DNS / network path from Prometheus's vantage point, not yours
nslookup target
# 3. Check what Prometheus thinks the address/labels are (relabeling may have rewritten them)
#    -> Targets page shows the FINAL __address__ after relabel_configs
```

The common root causes and their tells: **"connection refused"** = nothing listening (wrong port, crashed process, NetworkPolicy blocking); **"context deadline exceeded"** = the scrape took longer than `scrape_timeout` (slow endpoint or huge payload); **"server returned HTTP status 401/403"** = the target needs auth Prometheus isn't supplying; **"invalid metric type / text format parsing error"** = the endpoint returns 200 but the body isn't valid exposition format (e.g. an HTML error page, or a JSON API mistaken for `/metrics`). A subtle one in Kubernetes: the target *looks* right but `relabel_configs` rewrote `__address__` to an unreachable port — the Targets page shows the *final* address it actually scrapes, so always read that, not your assumed value. If the target isn't on the page **at all**, the problem is upstream in service discovery or a `relabel_configs` `keep`/`drop` rule filtered it out — check the Service Discovery page (`/service-discovery`) which shows targets *before and after* relabeling with the dropped reason.

#### Q70. [Practical] How do you provision Grafana dashboards, data sources, and alerts as code instead of clicking in the UI?

Clicking dashboards together in the UI doesn't survive a pod restart, can't be code-reviewed, and drifts between environments. Grafana solves this with **provisioning**: YAML files in `/etc/grafana/provisioning/` that Grafana reads on startup to declaratively create data sources, dashboards, alert rules, and notification policies. The dashboards themselves are JSON models loaded from a folder; the provisioning YAML just points Grafana at that folder.

```yaml
# /etc/grafana/provisioning/datasources/prometheus.yml
apiVersion: 1
datasources:
  - name: Prometheus
    type: prometheus
    access: proxy                      # Grafana backend proxies queries (not the browser)
    url: http://prometheus:9090
    isDefault: true
    jsonData:
      httpMethod: POST                 # POST avoids URL-length limits on big queries
      timeInterval: 15s                # tells Grafana the scrape interval for $__rate_interval
---
# /etc/grafana/provisioning/dashboards/main.yml
apiVersion: 1
providers:
  - name: 'team-dashboards'
    folder: 'Platform'
    type: file
    options:
      path: /var/lib/grafana/dashboards   # JSON dashboard files live here
      foldersFromFilesStructure: true
```

The mature pattern layers on **Jsonnet + Grizzly/grafonnet** or the **Grafana Terraform provider** so dashboards are generated programmatically (one template renders per-service dashboards) and applied in CI. For dashboards specifically, set them to **read-only / non-editable** when provisioned so engineers can't make UI edits that silently diverge from the source of truth (provisioned dashboards get overwritten on the next reconcile, so a UI edit is lost anyway — making it read-only avoids the confusion). The kube-prometheus-stack takes this further: dashboards live in ConfigMaps labeled `grafana_dashboard=1`, and a sidecar auto-loads them, so adding a dashboard is just `kubectl apply` of a ConfigMap. The interview point: **dashboards-as-code** gives you review, versioning, environment parity, and disaster recovery — a wiped Grafana rebuilds itself from Git.

### 🟡 Intermediate — extended

#### Q71. [Practical] What is the difference between Prometheus alerting and Grafana-managed alerting, and when would you use each?

There are two distinct alerting engines and conflating them causes real confusion. **Prometheus alerting** evaluates `alert:` rules inside Prometheus itself on `evaluation_interval`, then *pushes* firing alerts to Alertmanager, which does dedup/group/route/notify (Q14, Q48). **Grafana Unified Alerting** (default since Grafana 9) is a separate engine living in Grafana: it can evaluate alert rules against *any* data source (Prometheus, Loki, a SQL database, CloudWatch), and it has its own built-in Alertmanager (or can forward to an external one) with its own notification policies and contact points.

The decision axis is about **where the source of truth lives and what you're alerting on**. Prometheus-native alerting is the right default for pure-Prometheus infrastructure SLOs: the rules live in version-controlled `rules.yml` next to your recording rules, they evaluate even if Grafana is down (Grafana is a viewer, not on the critical path), and they reuse your recording rules cheaply. Grafana-managed alerting shines when you need to **alert across heterogeneous data sources** (e.g. "page if this Prometheus metric is high AND this Loki log pattern appears"), when non-Prometheus teams own the alerts, or when you want alert authoring in the UI with the same query builder used for panels.

```
Prometheus alerting:   rules.yml -> Prometheus eval -> push -> Alertmanager -> notify
                       (survives Grafana outage; Prometheus-only; GitOps-friendly)
Grafana alerting:      Grafana eval (any datasource) -> Grafana AM / external AM -> notify
                       (multi-datasource; UI authoring; depends on Grafana being up)
```

The trap to avoid: **don't run the same alert in both engines** — you'll double-page or get confused about which one is authoritative. A common mature setup is Prometheus-native alerting for infrastructure/SLO rules (GitOps, reliable, close to the data) plus Grafana alerting reserved for genuinely cross-source or business alerts. Also note Grafana can *import* Prometheus rules and even act purely as the Alertmanager UI, so the line blurs — but the key interview answer is that they are **two engines**, and Prometheus-native alerting does not depend on Grafana being alive while Grafana-managed alerting does.

#### Q72. [Practical] Configure the blackbox_exporter to probe an HTTPS endpoint and alert on certificate expiry and probe failure.

The blackbox_exporter probes endpoints *from the outside* (HTTP/HTTPS/TCP/ICMP/DNS) and exposes the result as metrics — it answers "can a user actually reach this?" rather than "is the process up?". The key architectural quirk: you don't scrape the *target* directly; you scrape the *blackbox_exporter* and pass the target as a `__param_target`, then relabel so the metric carries the real target as its `instance`. This indirection trips people up constantly.

```yaml
# blackbox.yml (the exporter's own config) defines reusable "modules"
modules:
  http_2xx:
    prober: http
    timeout: 5s
    http:
      valid_status_codes: [200, 301, 302]
      method: GET
      fail_if_ssl: false
      preferred_ip_protocol: ip4
---
# prometheus.yml: scrape the EXPORTER, passing targets as a parameter
scrape_configs:
  - job_name: blackbox-http
    metrics_path: /probe
    params: { module: [http_2xx] }
    static_configs:
      - targets: ["https://api.example.com", "https://app.example.com/health"]
    relabel_configs:
      - source_labels: [__address__]
        target_label: __param_target          # the URL to probe
      - source_labels: [__param_target]
        target_label: instance                 # show the real URL as instance
      - target_label: __address__
        replacement: blackbox-exporter:9115     # actually scrape the exporter here
```

Now you alert on the metrics the exporter emits — `probe_success` (1/0) and `probe_ssl_earliest_cert_expiry` (Unix timestamp of the soonest-expiring cert in the chain):

```yaml
- alert: EndpointDown
  expr: probe_success == 0
  for: 2m
  labels: { severity: page }
- alert: CertExpiringSoon
  expr: (probe_ssl_earliest_cert_expiry - time()) / 86400 < 21   # < 21 days
  for: 1h
  labels: { severity: ticket }
  annotations:
    summary: "TLS cert for {{ $labels.instance }} expires in {{ $value | humanize }} days"
```

The cert-expiry alert is one of the highest-value, lowest-effort alerts a team can add — expired TLS certs cause embarrassing, entirely preventable outages, and `probe_ssl_earliest_cert_expiry - time()` gives you weeks of warning. The blackbox probe also catches failures **synthetic from the user's perspective** that internal `up` can't see: a load balancer misroute, a firewall change, DNS breakage, or a cert problem — the app is "up" but unreachable. Probing from multiple regions (one blackbox_exporter per region) additionally distinguishes "the service is down" from "one region's network path is broken."

#### Q73. [Theory] What are `sample_limit`, `target_limit`, and `label_limit`, and how do they protect a Prometheus server?

These are **defensive scrape-time guardrails** that cap what a single target or job can inflict on the server, turning a silent cardinality disaster into a loud, contained failure. **`sample_limit`** caps the number of samples a single scrape may return; if the target exposes more, the *entire scrape is rejected* (treated as failed, `up` stays based on the HTTP fetch but the samples are dropped) and `scrape_samples_scraped` shows the overflow. **`target_limit`** caps how many targets a single scrape config (after service discovery + relabeling) may produce — protecting against a misconfigured SD that suddenly returns thousands of targets. **`label_limit`**, **`label_name_length_limit`**, and **`label_value_length_limit`** cap the number and size of labels per series.

```yaml
scrape_configs:
  - job_name: untrusted-third-party
    sample_limit: 5000          # reject the scrape if the target emits > 5000 samples
    label_limit: 30             # reject series with > 30 labels
    label_value_length_limit: 256
    target_limit: 200           # SD must not balloon past 200 targets for this job
    static_configs: [{ targets: ["thirdparty:9100"] }]
```

The value of these is *blast-radius containment* and *fail-fast*. Without `sample_limit`, an engineer who accidentally adds a `user_id` label (Q10) silently grows the server until it OOMs hours later, taking down monitoring for *everything*. With `sample_limit`, that one job's scrapes start failing immediately and visibly — you lose that job's metrics (bad) instead of the whole server (catastrophic), and the failure points straight at the culprit. The trade-off is that a too-tight `sample_limit` causes flapping data loss as legitimate growth bumps the ceiling, so you set it with headroom above the target's normal sample count and alert on rejections (`scrape_samples_scraped >= sample_limit` proximity). The interview framing: these limits encode the principle that in a shared monitoring system, **one team's mistake must not be allowed to silently consume the whole server** — it's the scrape-time complement to Mimir's per-tenant limits (Q60).

#### Q74. [Coding] Use `label_replace` and `label_join` to reshape labels in a query. Give a concrete use case.

`label_replace` and `label_join` are the two PromQL functions for **manipulating labels at query time** (as opposed to relabeling at scrape time, which is the preferred place — but sometimes you can't change the scrape config). `label_replace(v, dst, replacement, src, regex)` runs `regex` against the `src` label, and if it matches, sets `dst` to `replacement` (which can reference capture groups with `$1`). `label_join(v, dst, separator, src1, src2, ...)` concatenates several source labels into a new `dst` label.

**Problem:** A metric carries `instance="10.0.4.12:9100"` and you want a clean `host` label without the port for a cleaner legend and for joining against another metric that only has the IP.

```promql
# Extract just the IP (strip :port) into a new `host` label
label_replace(
  node_load1,
  "host",            # destination label
  "$1",              # replacement, using capture group 1
  "instance",        # source label
  "([^:]+):.*"       # regex: capture everything before the colon
)

# Build a composite "service identity" label from namespace + pod
label_join(kube_pod_status_phase, "id", "/", "namespace", "pod")
# -> id="payments/checkout-7d9f-abc"
```

A classic real use case is **joining metrics that don't share a label cleanly**. Suppose `node_exporter` metrics have `instance="ip:9100"` and `kube-state-metrics` has `node="ip"`; a `group_left` join (Q38) fails because the join keys differ in format. You `label_replace` the node_exporter `instance` down to a bare IP under a new `node` label, then the join matches. The important caveat interviewers want: **prefer relabeling at scrape time** (`relabel_configs`) to fix label hygiene once, server-side, rather than `label_replace` in every query — query-time label surgery is repeated on every evaluation (cost), easy to get subtly wrong (a regex that doesn't match silently leaves `dst` empty, breaking the join again), and scatters the logic across dozens of dashboards. `label_replace`/`label_join` are the *escape hatch* for metrics you can't re-instrument or re-scrape, not the primary tool.

#### Q75. [Practical] Your Grafana dashboard is slow to load. How do you diagnose and fix it?

A slow dashboard is almost always **too many expensive queries** rather than a Grafana rendering problem, so I diagnose from the query side. First, open the panel's **Query Inspector** (panel menu → Inspect → Query) which shows the exact PromQL sent, the request duration, and the number of series/points returned. Grafana's built-in **dashboard performance**: a dashboard with 40 panels each running a heavy `histogram_quantile` over a 30-day range with no recording rule will hammer Prometheus with 40 expensive range queries on every load and every refresh.

```
Diagnosis order:
1. Query Inspector -> which panel(s) are slow? how long? how many series returned?
2. Run the slow PromQL directly in Prometheus with `stats` -> samples touched, exec time
3. Count panels & their refresh interval -> 40 panels @ 5s refresh = 480 queries/min
4. Check if queries re-aggregate raw data that a recording rule should precompute
```

The fixes, in order of impact: (1) **Recording rules** — move heavy aggregations (`histogram_quantile`, multi-level `sum by`) into recording rules so panels query a tiny precomputed series instead of crunching millions of samples per refresh (Q13). This is usually a 10–100× win. (2) **Reduce returned series** — a panel that returns 5,000 series is unreadable *and* slow; aggregate (`sum by (...)`) or use `topk` so you draw tens of lines, not thousands. (3) **Fix the rate window** — use `$__rate_interval`, not a tiny hardcoded range that forces fine-grained evaluation. (4) **Tune `maxDataPoints`** down for panels that don't need pixel-perfect resolution. (5) **Set a sane refresh interval** — auto-refresh every 5s on a 40-panel dashboard is self-inflicted load; 30s–1m is plenty for most. (6) **Enable query caching** in Grafana Enterprise / the Mimir query-frontend so repeated identical queries hit cache. (7) **Split mega-dashboards** — a 60-panel "everything" dashboard is both slow and useless; break it by concern. The systemic point: dashboard latency is a **query-cost** problem, and the highest-leverage fix is precomputation via recording rules, exactly the same lever as server-side query cost (Q51).

### 🟠 Advanced — extended

#### Q76. [Theory] How does recording-rule ordering and grouping work, and why can a rule depend on another rule's output?

Rules are organized into **groups**, and the execution semantics within versus across groups differ in a way that's load-bearing for correctness. **Within a single group, rules execute sequentially, in file order, on every `interval`.** This means a later rule in the same group can reference the *freshly-computed* output of an earlier rule in that same evaluation cycle — the earlier rule's result is already written before the later rule runs. **Across different groups, evaluation is independent and concurrent** (groups don't see each other's *current-cycle* results; they see whatever was last written, which may be one interval stale).

```yaml
groups:
  - name: slo-chain                 # one group => sequential, dependency-safe
    interval: 30s
    rules:
      - record: job:errors:rate5m   # step 1
        expr: sum by (job)(rate(http_requests_total{status=~"5.."}[5m]))
      - record: job:total:rate5m    # step 2 (independent)
        expr: sum by (job)(rate(http_requests_total[5m]))
      - record: job:error_ratio:5m  # step 3 DEPENDS on steps 1 & 2 — safe, same group, runs after
        expr: job:errors:rate5m / job:total:rate5m
```

This ordering rule is why **dependent recording rules must live in the same group**: if `job:error_ratio:5m` were in a *different* group from its inputs, it would compute from the inputs' *previous-interval* values, introducing a one-cycle lag and, worse, a transient wrong value right after a config change when the input doesn't exist yet. The corollary trade-off: because a group runs sequentially, a group with many slow rules can exceed its own `interval` (visible as `prometheus_rule_group_last_duration_seconds > interval` — the Q66 meta-alert), and *all* rules in that group then evaluate late. So you balance two forces: keep dependent rules together (correctness), but don't pile so many heavy rules into one group that it blows its interval (timeliness). The standard practice is to split independent rule chains into separate groups so they parallelize, while keeping each dependency chain within its own group. Prometheus also supports `limit` per group (cap series produced) and rule-level `keep_firing_for`/`for` only on alerting rules.

#### Q77. [Theory] Explain how query sharding works in Mimir/Thanos and why some queries shard and others can't.

Query sharding is how a query-frontend turns one heavy query into N parallel sub-queries that run on different queriers and recombine — the horizontal-scale answer to "this aggregation touches a billion series." The frontend rewrites the query so each shard processes a **deterministic subset of the series** (typically by hashing series labels into N buckets), computes a *partial* result, and the frontend merges the partials. The key insight is that this only works for queries whose aggregation is **decomposable / associative**: `sum`, `count`, `min`, `max`, `avg` (as sum+count), and `rate` followed by `sum` can be computed on disjoint series subsets and then combined, because `sum(a ∪ b) = sum(a) + sum(b)`.

```
Unsharded:   querier scans ALL series for sum(rate(...))  -> one giant job
Sharded(4):  shard0: sum over series hash%4==0  \
             shard1: sum over series hash%4==1   } merge partials -> final sum
             shard2: sum over series hash%4==2  /   (works: sum is associative)
             shard3: sum over series hash%4==3
```

What **can't** shard, and why an expert must name it: operations that need to see *all* series together at once. **`topk`/`bottomk`** can't be naively sharded because the global top-K isn't recoverable from per-shard top-Ks without overscanning (though some engines approximate it). **`quantile` over a set of series** (the aggregation `quantile(0.9, ...)`, not `histogram_quantile`) and **`count_values`** are non-decomposable. **Binary operations with vector matching** across the full series space, and anything that joins shards, force the frontend to fall back to non-sharded execution for that portion. The practical consequence: rewriting a dashboard query from `quantile()` over raw series to a **histogram + `histogram_quantile`** (which *is* shardable because the bucket sums are associative) can be the difference between a query that scales and one that doesn't. This connects directly to native histograms (Q53): they make high-fidelity percentiles both cheap on cardinality *and* shardable, which is why they matter so much at platform scale. The interview tell is articulating that **shardability is a property of the aggregation's algebra**, and that designing metrics/queries for decomposability is a deliberate scaling choice.

#### Q78. [Practical] Walk through diagnosing and fixing "out of order sample" rejections.

"Out of order sample" (and its cousins "duplicate sample for timestamp" and "too old sample") are TSDB ingestion rejections that mean Prometheus received a sample for a series whose timestamp is **older than (or equal to) the latest timestamp it already stored for that series**. Because the head block is append-mostly and expects monotonically increasing timestamps per series, it rejects these by default and increments `prometheus_target_scrapes_sample_out_of_order_total` (and similar per-cause counters). The data is silently lost, which makes this a sneaky, partial data-loss bug.

```bash
# Confirm and quantify the rejections
# (these counters tell you which failure mode is happening)
rate(prometheus_target_scrapes_sample_out_of_order_total[5m]) > 0
rate(prometheus_target_scrapes_sample_duplicate_timestamp_total[5m]) > 0
```

The root causes form a short list. (1) **Two targets exposing the same series** — most often because `relabel_configs` collapsed two distinct instances to identical labels, or `honor_labels: true` (Q41) let two sources claim the same `instance`. Both servers' samples land in one series with interleaving timestamps → out of order. (2) **`honor_timestamps: true` with skewed clocks** — the target stamps its own timestamps and its clock drifts, so samples arrive non-monotonically. (3) **A federation or Pushgateway misconfiguration** pushing overlapping timestamped data. (4) **A flapping target whose two replicas behind a single DNS name** alternate answers. The fix is almost always to **restore unique series identity**: ensure relabeling produces a distinct `instance`/identity per real source, turn `honor_labels`/`honor_timestamps` back off unless you genuinely have a federation/Pushgateway case, and fix clock skew (NTP). For legitimate late-arriving data (buffered edge collectors, mild skew you can't eliminate), enable the **`out_of_order_time_window`** TSDB option (Q65), which accepts samples up to a configured age into a separate out-of-order head — at the cost of extra memory. The diagnostic discipline: the *cause counter* tells you whether it's out-of-order, duplicate, or too-old, and each points at a different culprit, so always read the specific counter before guessing.

#### Q79. [Theory] How does Grafana's "explore" correlation between metrics, logs (Loki), and traces (Tempo) actually work, and what plumbing does it require?

The promise of unified observability is clicking from a metric spike to the exact logs and traces behind it, and Grafana implements this through **data source correlations** built on shared identifiers — it is not magic, it's deliberate label and ID plumbing. Three mechanisms cooperate. (1) **Exemplars** (Q26): your histogram metric carries a sampled `trace_id` on specific observations; Grafana renders these as clickable dots on a latency panel, and a configured **"exemplar → Tempo" link** in the Prometheus data source jumps to that trace. (2) **Derived fields in Loki**: the Loki data source is configured with a regex that extracts a `trace_id` from log lines and turns it into a link to Tempo — so a log line containing `traceID=abc123` becomes a clickable trace link. (3) **Trace-to-logs / trace-to-metrics** config in the Tempo data source: a span carries resource attributes (e.g. `service.name`, `namespace`), and Grafana builds a Loki/Prometheus query from those attributes to find the related logs/metrics for the time window of the span.

```yaml
# Prometheus datasource: link exemplar trace_id to the Tempo datasource
jsonData:
  exemplarTraceIdDestinations:
    - name: trace_id
      datasourceUid: tempo-uid
# Loki datasource: extract trace_id from log lines -> link to Tempo
  derivedFields:
    - name: TraceID
      matcherRegex: 'traceID=(\w+)'
      url: '$${__value.raw}'
      datasourceUid: tempo-uid
# Tempo datasource: from a span, build a Loki query using shared labels
  tracesToLogsV2:
    datasourceUid: loki-uid
    tags: ['service.name', 'namespace']
```

The load-bearing requirement is **consistent identity across the three pillars**: the same `service.name`/`namespace`/`pod` labels on metrics, logs, and traces, and a `trace_id` propagated end to end (which is what OpenTelemetry context propagation provides). If your logs use `service` but metrics use `app` and traces use `service.name`, the correlations silently produce empty results — the join keys must align. This is the practical reason teams standardize on **OpenTelemetry semantic conventions**: it's not bureaucracy, it's what makes the metric→log→trace pivot actually resolve. The expert framing: correlation is a *naming-discipline* problem dressed up as a tooling feature; the tooling (exemplars, derived fields, trace-to-logs) is trivial to configure once the identifiers are consistent, and impossible to make work when they aren't.

#### Q80. [Practical] How do you template a useful Alertmanager notification, and what context should every page include?

A notification that just says "HighErrorRate firing" forces the on-call to go spelunking; a good one is **self-contained enough to start triage from the phone**. Alertmanager uses Go templating over the alert's labels and annotations, and the annotations are authored back in the *Prometheus* alerting rule (so the rule owns the human-readable content). The discipline is: **labels are for routing/grouping (low cardinality, machine-facing); annotations are for humans (the message body).**

```yaml
# In the Prometheus alerting rule — annotations carry the human context
- alert: HighErrorRate
  expr: job:http_request_errors:ratio_rate5m > 0.05
  for: 5m
  labels:
    severity: page
    team: payments
  annotations:
    summary: "{{ $labels.job }}: 5xx error ratio {{ $value | humanizePercentage }} (>5%)"
    description: >-
      {{ $labels.job }} in {{ $labels.cluster }} is serving
      {{ $value | humanizePercentage }} 5xx for 5m.
    runbook_url: "https://runbooks.example.com/HighErrorRate"
    dashboard_url: "https://grafana.example.com/d/abc/{{ $labels.job }}"
```

```
# Alertmanager Slack template (alertmanager.yml) — render the group cleanly
slack_configs:
  - channel: '#payments-alerts'
    title: '{{ .CommonLabels.alertname }} ({{ .Alerts.Firing | len }} firing)'
    text: >-
      {{ range .Alerts }}*{{ .Annotations.summary }}*
      <{{ .Annotations.runbook_url }}|Runbook> · <{{ .Annotations.dashboard_url }}|Dashboard>
      {{ end }}
```

The checklist of what every page must include, and why: **what** (the symptom in plain language with the actual value — `humanizePercentage`/`humanize` make `0.0531` read as `5.31%`), **where** (cluster/service/region labels so you know the scope without opening a dashboard), **a runbook link** (so any on-call, not just the author, can act — this single field is the difference between a 2-minute and a 30-minute response), and a **dashboard/explore deep-link** pre-filtered to the affected service. Use `.Alerts.Firing` / `.Alerts.Resolved` to render grouped alerts as a digest rather than spamming, and template the *grouped* view so "30 pods down" is one readable message. The anti-patterns interviewers listen for: putting high-cardinality data in *labels* (breaks grouping), writing annotations with no runbook (every page becomes a research project), and not using the humanize functions (raw `0.0531` and `1717f00000` Unix timestamps are unreadable under pressure). A page is a UX problem: optimize it for a stressed human at 3am.

#### Q81. [Theory] What is the `kube-state-metrics` vs `cAdvisor` vs `node_exporter` division of labor, and why do you need all three in Kubernetes?

These three exporters cover **non-overlapping layers** of a Kubernetes system, and conflating them leads to "why can't I find pod restart counts in node_exporter?" confusion. **`node_exporter`** runs on each host and reports **OS/hardware-level** metrics of the *node itself*: CPU, memory, disk, filesystem, network at the kernel level, irrespective of containers. **`cAdvisor`** (built into the kubelet) reports **per-container resource *usage*** as observed by the container runtime / cgroups: a container's actual CPU seconds (`container_cpu_usage_seconds_total`), memory working set (`container_memory_working_set_bytes`), network, and filesystem — the *runtime reality* of what each container consumes. **`kube-state-metrics` (KSM)** reports the **Kubernetes API object *state*** — it listens to the API server and emits the *desired and status* of objects: deployment replica counts (`kube_deployment_status_replicas`), pod phase (`kube_pod_status_phase`), restart counts (`kube_pod_container_status_restarts_total`), node conditions, resource *requests/limits* declared in specs. KSM measures *intent and declared state*, not consumption.

```
node_exporter   -> the NODE's OS/hardware       (is the machine healthy?)
cAdvisor        -> per-CONTAINER actual usage    (what is each container consuming?)
kube-state-metrics -> Kubernetes OBJECT state    (what does the cluster WANT / what's its status?)
```

You need all three because real questions span the layers. "Is this pod being CPU-throttled?" compares **cAdvisor** usage (`container_cpu_cfs_throttled_seconds_total`) against the **KSM**-reported limit (`kube_pod_container_resource_limits`). "Is the deployment fully rolled out?" is pure **KSM** (`kube_deployment_status_replicas_available` vs `..._spec_replicas`). "Is the node under memory pressure?" is **node_exporter** plus the KSM node condition. A classic computed alert — pods near their memory limit — *joins* cAdvisor usage to the KSM limit:

```promql
# Containers using > 90% of their declared memory limit (join cAdvisor usage to KSM limit)
container_memory_working_set_bytes
  / on(namespace,pod,container)
kube_pod_container_resource_limits{resource="memory"} > 0.9
```

The interview point: cAdvisor is **runtime usage**, KSM is **declared/desired state**, node_exporter is **the host** — they answer different questions, and the most useful Kubernetes alerts *join across* them (usage vs. limit, status vs. spec), which also explains why getting their shared labels (`namespace`, `pod`, `container`) consistent (via relabeling) is essential.

### 🔴 Expert — extended

#### Q82. [Theory] Compare the `/api/v1/query` (instant) and `/api/v1/query_range` (range) HTTP APIs, and explain why using the wrong one wrecks performance or correctness.

Prometheus exposes two distinct query endpoints with different semantics and cost profiles, and choosing wrong is a common source of both slow dashboards and *incorrect alerting tools*. **`/api/v1/query`** runs a single **instant query** at one `time` (defaulting to now) — it returns one value per series. **`/api/v1/query_range`** runs the query as a series of instant evaluations stepped across `[start, end]` at a `step` (Q51) — it returns a time series of values, which is what a graph needs. The cost difference is enormous: a range query is `(end-start)/step` instant evaluations, so a 30-day range at 1m step is ~43,000 evaluations of the same expression.

```bash
# Instant: one evaluation, current value(s) — for "what is it right now" / tables / single stats
curl 'http://prom:9090/api/v1/query?query=up==0'

# Range: stepped evaluation for a graph — note step is REQUIRED and dominates cost
curl 'http://prom:9090/api/v1/query_range?query=sum(rate(http_requests_total[5m]))&start=...&end=...&step=60'
```

The performance wreck: people build a tool or alert-checker that wants "the current value" but call `query_range` over a wide window with a tiny step, generating thousands of needless evaluations — or, conversely, they try to graph by hammering `query` once per point from a script, which loses Prometheus's internal step-alignment and caching. The **correctness** wreck is subtler and worth the expert points: **range-vector functions inside a range query are evaluated independently at each step**, so a `rate(x[5m])` in `query_range` is recomputed for every step's own trailing 5m window — correct, but means the `[5m]` and the `step` interact (if `step > 5m` you *undersample* the rate and can miss spikes between steps; this is exactly the `irate` vs `rate` and `$__rate_interval` story at the API layer). For one-shot checks (CI gates, `up==0` probes, a synthetic-monitor assertion) use **instant** `query`; for anything time-series/graph-shaped use **`query_range`** with a step matched to your resolution need; and for "max over the last hour" prefer an instant `query` of `max_over_time(...[1h])` rather than a range query you then post-process. The staff-level insight: the API choice is the same `(range/step) × series` cost model as the internal engine (Q51), surfaced at the HTTP boundary — and the wrong endpoint either multiplies cost or silently changes what you're measuring.

#### Q83. [Practical] You need zero-data-loss during a Prometheus version upgrade and config migration. Design the rollout.

The constraints are that a single Prometheus is **not replicated** (local storage, Q23/Q55) and a restart causes a scrape gap plus a potentially long WAL replay (Q44). So "zero data loss" requires that *another replica is always scraping* during any restart, and that the upgrade is validated before it touches the surviving replica. I'd lean on the HA pair you should already be running (Q19) and treat the upgrade as a rolling, validated, reversible operation.

```
Pre-flight (no traffic impact):
  1. promtool check config / check rules against the NEW binary in CI
  2. Read the version's MIGRATION guide (esp. 3.0 — removed flags, Q64); diff defaults
  3. Spin a CANARY: new binary, real config, scraping a copy of targets in staging;
     compare its metrics/recording-rule outputs to prod for a soak period

Rolling upgrade of the HA pair (replicas A and B scrape the same targets):
  4. Drain/upgrade replica B; A keeps scraping -> no detection/alerting gap
  5. Let B replay its WAL and become Ready (watch prometheus_tsdb_head_series, /-/ready)
  6. Verify B's data matches A (dedup'd in Thanos/Mimir; or spot-check key series)
  7. Repeat for A. Alertmanager dedup means no double/zero paging throughout.
```

The data-durability layer matters as much as the rolling restart: if you `remote_write` to Mimir/Thanos, the *long-term* copy is unaffected by either replica restarting, so even the brief per-replica gap is backfilled-covered by the *other* replica's stream — the central store sees a continuous, deduplicated series. Key safeguards: (1) **never upgrade both replicas simultaneously** (that *is* a data gap and an alerting blackout); (2) gate readiness on **`/-/ready`** and the head-series count stabilizing, not just process-up, because WAL replay can take minutes on a large head — flipping load balancer/query traffic to a still-replaying instance returns partial data; (3) keep the **old binary one keystroke away** for rollback, and because new blocks are forward-compatible but config/flags may not be, validate that the *old* binary can still read any blocks the new one wrote (usually yes within a major, the risk is across majors); (4) for a **breaking major (3.0)**, do the canary soak long enough to catch behavior changes in recording-rule outputs and staleness/lookback semantics, not just "it started." The expert framing: zero-data-loss isn't a feature of Prometheus — it's an *operational property you construct* from HA replicas + remote_write + readiness-gated rolling restarts + a tested rollback, and the single most common failure is restarting both replicas at once because someone forgot they're independent islands.

#### Q84. [Theory] What are Remote-Write 2.0 and the metadata/created-timestamp improvements, and why do they matter for the OTel and native-histogram era?

Remote-Write 1.0 streamed samples as Snappy-compressed Protobuf but had real gaps: **metadata** (`HELP`/`TYPE`/`UNIT`) traveled on a *separate, best-effort* channel decoupled from the samples, so the receiving store often had samples without knowing their type or unit; **exemplars** and **native histograms** were bolted on; and counter **created timestamps** (the moment a counter series was born) weren't transmitted, which causes the well-known "first scrape after a counter (re)start undercounts because `rate` can't tell new-counter from reset" problem. **Remote-Write 2.0** (stabilizing through Prometheus 2.5x/3.x) redesigns the wire format to carry **metadata inline with each series**, **exemplars**, **native histograms**, and **created timestamps (CT)** in one coherent message, using a string-interning symbol table in the payload to keep it compact.

```
RW 1.0:  samples ----> store     (metadata on a SEPARATE, lossy side channel)
         exemplars/native-histos: limited/awkward
         created-timestamp: not sent -> first-rate-after-reset undercount

RW 2.0:  [symbols table] + per-series {labels, metadata(type/unit/help), CT, samples,
          exemplars, native histograms}  ---->  store    (one coherent, interned message)
```

Why this matters specifically in 2026: the **OTel convergence** (Q56) depends on faithful metadata and unit transport — OTel is metadata-rich (explicit units, descriptions, temporality), and a remote-write path that drops or decouples metadata loses exactly the information that makes OTel data self-describing in the store. **Native histograms** (Q53) *require* a transport that understands their structured sample, which RW 2.0 provides first-class rather than as an extension. And **created timestamps** fix a long-standing accuracy bug that becomes more visible as workloads get more ephemeral (frequent restarts in autoscaled/spot environments mean more counter births, so more first-scrape undercounting without CT). The migration caveat an expert flags: RW 2.0 is **negotiated** — sender and receiver advertise supported versions via content-type, and you only get the benefits when *both ends* (Prometheus/Alloy on the send side, Mimir/Thanos/vendor on the receive side) speak it; a mixed fleet falls back to 1.0 for those links. The headline: RW 2.0 is the plumbing that makes "metrics with full metadata, native histograms, exemplars, and correct counter starts flow end-to-end into the long-term store" actually true, which is the precondition for the unified, vendor-neutral, high-fidelity observability the rest of this guide describes.

#### Q85. [Practical] Design a runbook-and-dashboard strategy so that any on-call engineer can act on an alert without tribal knowledge. What does "good" look like?

The failure mode this prevents is the **"only Dave knows how to fix this"** outage, where an alert fires at 3am, the on-call has never seen it, and resolution waits for an expert to wake up. "Good" means every paging alert is **self-describing and actionable by the least-experienced person who could be on call**, and that the path from page → understanding → action is short and pre-built. This is an organizational design problem expressed through three coupled artifacts: the alert, the runbook, and the dashboard.

The alert (Q80) must carry a `runbook_url` and a deep-linked `dashboard_url`, and its `summary` must state the *symptom in business terms*, not the raw expression. The **runbook** — versioned in Git next to the rules, not in a wiki that rots — follows a fixed template: *what this alert means* (the user-facing symptom), *how to assess severity/blast radius* (which dashboard panels, which queries), *the most common causes ranked by likelihood*, *concrete remediation steps* (commands, with the safe/reversible ones first), *escalation* (who/when to escalate if steps don't resolve it), and *how to confirm resolution*. The **dashboard** the runbook links to is purpose-built for *that* alert: it leads with the SLI the alert watches, then the golden-signal context (Q16), then the most likely causal signals the runbook references — so the on-call sees the exact panels the runbook talks about.

```
Page fires ──> summary tells you WHAT (plain language, with the value)
          ──> runbook_url ──> Git-versioned runbook: meaning, blast-radius queries,
                              ranked causes, ordered remediation, escalation, verify-fixed
          ──> dashboard_url ──> alert-specific dashboard: SLI first, then causal signals
Quality bar: a first-week on-call can triage & take the first safe action UNAIDED.
```

What separates "good" from theater: (1) **runbooks are tested in game-days** — you fire the alert deliberately and have someone who *didn't* write it follow the runbook; gaps found there are the real value (this also validates the alert actually fires, closing the Q66/Q32 loop). (2) **Ranked causes from real history** — the runbook's "common causes" come from past post-mortems, so it encodes institutional memory rather than guesses. (3) **Safe-first remediation** — reversible mitigations (scale up, fail over, roll back) before destructive ones, because a stressed on-call should reach for the low-risk action first. (4) **Every page maps to exactly one runbook**, and an alert *without* a runbook is treated as a bug (some teams CI-fail a rule whose annotations lack `runbook_url`). The staff-level framing: alerting quality is measured not by detection but by **time-to-mitigation for a non-expert**, and that is an artifact-and-process design — alert annotations, Git-versioned tested runbooks, and alert-specific dashboards — not a Prometheus configuration setting.

#### Q86. [Theory] How do counter resets interact with `increase()`, `rate()`, and the `_total` semantics across pod restarts in Kubernetes, and what subtle errors arise?

In Kubernetes, pods restart constantly (deploys, OOMs, evictions, node drains, autoscaling), and each restart **zeroes the process's counters** — a new process starts every counter at 0. Prometheus's reset-aware functions (Q8) handle a *single* counter going `...→ 5000 → 0 → 30` by treating the drop as a reset and adding the post-reset value. The subtle errors arise from the interaction of **resets + series identity churn + aggregation**, and they're a favorite advanced interview probe because the naive mental model breaks.

The first subtlety: **`rate()`/`increase()` reset handling assumes the *same series* spans the reset**. But in Kubernetes the restarted pod often gets a **new `pod` name** (and certainly a new `instance`/IP), so from Prometheus's view the old series goes *stale* and a *brand-new series* appears at 0 — it's not a reset within one series, it's two different series. If your query keeps `pod` in the grouping, you get one series ending and another starting, and the *aggregate* `sum(rate(...))` is mostly fine because you sum the rates of both. But if you ever try to `increase()` a counter *and rely on the pod label*, you under/over-count around the boundary because no single series spans it. The mitigation is to **aggregate away the ephemeral identity early**: `sum without (pod, instance) (rate(...))` so the rate is computed per-stable-series then summed — but note even that computes `rate` per (pod) series *first*, which is correct, then sums.

```
Single series with reset (handled by rate/increase):
   x: 4990 4998 [restart] 7 15 23   -> rate adds the post-reset slope. OK.

K8s reality (NEW pod name on restart -> identity churn, NOT one series):
   pod-A: 4990 4998 <stale>          (old pod dies, series goes stale)
   pod-B:               7 15 23      (new pod, new series, starts at 0)
   sum(rate(x)) by aggregating away pod -> OK
   increase(x) keeping pod -> boundary under/overcount; also CHURN cost (Q63)
```

The second subtlety: **`increase()` extrapolation across a restart** (Q36) can produce a value *larger* than the real increment, because the function extrapolates the post-reset slope to the window edge while also having added the pre-reset portion — for short windows around a restart, `increase()` is an *estimate* that can look "impossible." And the third, often-missed one: **counter resets are also how you *detect* restarts** — `resets(process_cpu_seconds_total[1h])` or watching `changes(process_start_time_seconds[1h])` reveals crash-loops that pure `rate` smooths away. The expert synthesis: in Kubernetes, prefer **rate-then-sum aggregating away `pod`/`instance`** for throughput (correct across churn), use **`process_start_time_seconds`/`resets`** explicitly to surface restart frequency (which `rate` deliberately hides), remember **`increase()` is an extrapolated estimate** especially near restarts, and recognize that the new-pod-name-on-restart pattern is *also* a churn cost (Q63), not just a counting subtlety — so the same instinct (drop the ephemeral label) fixes both the correctness and the cardinality problem.

#### Q87. [Theory] What is the difference between `avg_over_time`, `avg`, and a recording rule's averaging, and why is "average of averages" a trap?

These three "averages" operate on different axes and conflating them produces statistically wrong dashboards. **`avg_over_time(v[w])`** is a *range* function: for each series independently, it averages that series' samples *over time* within the window — it collapses the **time axis**. **`avg(v)`** (the aggregation) is an *instant* operator: at one instant, it averages *across series* — it collapses the **series axis**. A **recording rule** is just a stored expression that can do either; the trap is *what you average and in what order*.

```
avg_over_time(cpu[5m])   -> per series, mean over TIME (one value per series)
avg(cpu)                 -> per instant, mean across SERIES (one value per instant)
avg(avg_over_time(...))  -> mean across series of each series' time-mean  (usually FINE for a gauge)
avg(rate(errors)) / ...  -> AVERAGE OF RATIOS  <- the classic trap
```

The headline trap is **averaging ratios / "average of averages" without weighting**. Suppose each instance reports an error *ratio* and you want the fleet error ratio. `avg(instance_error_ratio)` computes the *unweighted* mean of per-instance ratios — but an instance serving 1 million requests at 0.1% and one serving 10 requests at 50% should *not* contribute equally; the unweighted average wildly overstates the real fleet ratio because the tiny-traffic instance dominates. The statistically correct fleet ratio re-derives it from the underlying counts: **sum the errors and sum the totals first, then divide** — `sum(rate(errors[5m])) / sum(rate(total[5m]))` — which is a *traffic-weighted* ratio, the only correct one. The same trap hits latency: averaging per-instance p99s does not give the fleet p99 (percentiles don't average); you must aggregate the *histogram buckets* (`sum by (le)`) and *then* `histogram_quantile` (Q9). The expert rule to state: **never average a pre-computed average or ratio across series** — push the aggregation down to the additive raw components (counts, bucket sums) and compute the average/ratio/quantile *once, at the top*, because sums are associative and weight correctly while means-of-means silently lose the weighting. This is the same decomposability principle that governs query sharding (Q77): operate on the additive primitives, derive the statistic last.

#### Q88. [Practical] Design metric naming, labeling, and instrumentation standards for a 300-engineer organization. What do you enforce and how?

At 300 engineers the problem isn't any single metric — it's **drift**: forty teams each invent `requests`, `request_count`, `http_reqs`, `api_calls_total` for the same concept, in milliseconds here and seconds there, with `svc` vs `service` vs `app` labels, so no cross-team dashboard or recording rule generalizes and every cardinality bomb (Q10) is rediscovered the hard way. The solution is a **standard plus automated enforcement plus a paved path**, because documentation alone never holds at that scale.

The *standard* codifies the Prometheus/OpenMetrics conventions as organizational law: metric names are `namespace_subsystem_name_unit` snake_case; counters end in `_total`; base units only (`_seconds` not `_milliseconds`, `_bytes` not `_megabytes`); a fixed vocabulary for common labels (`service`, `namespace`, `cluster`, `region`, `env` — *one* spelling each); and an explicit **banned-label list** of known-unbounded identifiers (`user_id`, `request_id`, `session_id`, `email`, `trace_id`-as-label, raw URLs/paths-with-IDs, timestamps). It also defines the **RED/golden-signal metrics every service must expose** (Q16) with exact names, so any service plugs into the standard dashboards and SLO recording rules for free.

```yaml
# CI lint (e.g. a promtool/regex/OPA check) run on metric definitions & rules:
deny_label_names: [user_id, request_id, session_id, email, url, path, timestamp, uuid]
require_counter_suffix: "_total"
forbid_units: ["_ms", "_milliseconds", "_kb", "_mb", "_bytes_total"]   # use base units
require_label_vocab: { service: required, env: required }
max_labels_per_metric: 15
# Plus scrape-time backstops (Q73): sample_limit / label_limit on every job,
# and per-tenant series limits in Mimir (Q60).
```

Enforcement is layered, cheapest-point-first: (1) a **shared instrumentation library / OTel SDK wrapper** that bakes in the standard labels and naming — the *paved path* makes the right thing the easy thing, so most teams comply without thinking; (2) **CI linting** of metric/rule definitions (reject banned labels, wrong units, missing `_total`) so violations never merge — shifting the cost left exactly as in Q60; (3) **scrape-time guardrails** (`sample_limit`, `label_limit`, Q73) and **Mimir per-tenant limits** as the runtime backstop so a lint bypass still can't take down the shared system; (4) **cost attribution dashboards** so each team sees its own series count and (ideally) bill, aligning incentives. The governance body is a small **observability platform team** owning the library, the lint rules, the standard dashboards/recording rules, and an exception process — not a committee that reviews every metric. The staff-level insight to articulate: metric standards are a **socio-technical** problem (Q60) — you win by making the compliant path the path of least resistance (shared library + standard dashboards teams *want*), backed by CI gates and runtime limits so non-compliance is caught early and contained, rather than by writing a 40-page wiki nobody reads.

#### Q89. [Theory] Explain how Prometheus handles staleness, lookback-delta, and "instant query at time T" together, and the edge cases that cause phantom or missing points.

Three mechanisms jointly decide *what value (if any)* an instant query returns for a series at evaluation time `T`, and their interaction is the root of a whole class of "the graph has a gap" / "a dead instance still shows up" bugs. (1) **Lookback-delta** (default 5m, `--query.lookback-delta`): an instant evaluation at `T` searches *backward* up to the lookback window for the most recent sample; if the latest sample is within `[T-5m, T]`, that value is returned (carried forward), otherwise the series is treated as absent at `T`. This is why a series scraped every 15s still resolves between scrapes — the last sample is reused for up to 5m. (2) **Staleness markers** (Q37): when a series present in the *previous* scrape vanishes from the *current* one, Prometheus injects a special NaN staleness marker; an instant query that finds a staleness marker as the most recent sample returns **no value**, overriding the lookback-carry-forward. (3) The **scrape interval** determines sample spacing, and the relationship between it and lookback-delta governs gap behavior.

```
T-5m .............................. T (eval)
samples:   s s s s s            (last sample within 5m) -> value carried forward
samples:   s s          [gap >5m]                       -> beyond lookback -> NO value (gap)
samples:   s s [STALE marker]                           -> staleness -> NO value (clean end)
```

The edge cases an expert enumerates: (a) **scrape interval ≥ lookback-delta** → *phantom gaps*: if you scrape every 6m but lookback is 5m, every evaluation between scrapes finds *no* sample in the 5m window and the graph flickers to empty — the fix is `lookback-delta > scrape_interval` (a reason very long scrape intervals are dangerous). (b) **A single missed scrape** with default settings is *invisible* (lookback carries the prior value for 5m), which is usually desirable but means a brief outage can be masked — `absent_over_time` (Q49) or `count_over_time(up[w])` detects it explicitly. (c) **A genuinely dead target** disappears cleanly via staleness markers at the *next* interval rather than lingering 5m — but only if it was present the previous scrape; if the target was *never* scraped (new SD entry that immediately fails), there's no prior sample, so no staleness marker, just absence. (d) **`@`/`offset`-shifted queries** apply lookback at the *shifted* time, which surprises people comparing now vs. a week ago when the historical region was sparse. (e) Setting lookback-delta *too high* makes dead instances and ended series **linger as phantom points** (the pre-2.0 behavior, Q37), inflating aggregates like `sum(up)`. The synthesis: Prometheus distinguishes **absent** (no sample in lookback, or staleness marker) from **stale-but-present** (carried forward within lookback) from **sparse** (legitimately infrequent samples relying on lookback) — and almost every "mystery gap" or "zombie instance" graph traces to the lookback-delta-vs-scrape-interval relationship or a missing/extra staleness marker. Tuning `--query.lookback-delta` is therefore a *correctness* knob, not just performance, and it must stay comfortably larger than your largest `scrape_interval`.

#### Q90. [Practical] How would you load-test and capacity-plan a Prometheus/Mimir deployment before it's in production? What do you measure and extrapolate?

Capacity planning Prometheus is mostly about predicting **active series, ingestion rate (samples/sec), and query load**, because those — not "traffic" in the web sense — are what consume CPU, memory, and disk. The wrong approach is to guess RAM and react to OOMs; the right approach is to *measure the unit economics* on a representative load and extrapolate. I'd run a staged load test that synthesizes realistic series at known scale and watch the resource curves.

```
Inputs to model:
  active_series  = Σ over jobs ( targets × series_per_target )    # dominates everything
  samples/sec    = active_series / scrape_interval
  churn rate     = new series/sec (deploys × pods × ephemeral labels)  # Q63
  query load     = #dashboards × panels × refresh-rate + alert/recording eval

Measure on a representative run (avgtool / synthetic exporters at target scale):
  prometheus_tsdb_head_series                 -> RAM scales ~linearly with this
  process_resident_memory_bytes               -> bytes per active series (the unit cost)
  rate(prometheus_tsdb_head_samples_appended_total[5m])  -> ingest throughput
  prometheus_tsdb_compaction_*                -> can it keep up with block writes
  rule_group_last_duration vs interval         -> eval headroom (Q66)
  disk growth/day = samples/sec × ~1.5 bytes × 86400   -> retention sizing (Q59)
```

The method: stand up the candidate config against **synthetic targets** (a fake exporter emitting N series, or `avalanche`/load generators) at, say, 25%, 50%, 100%, 150% of projected series, and plot RSS, CPU, ingest throughput, compaction lag, and query latency at each level. From the slope you derive **bytes-per-active-series** (your local unit cost) and **disk-per-day = samples/sec × ~1.5 bytes × seconds/day** (Q59), then add headroom for WAL replay, query spikes, and churn. Crucial extrapolation rules: memory scales with **active series** (and *churn* inflates it beyond steady-state — test churn explicitly by recycling synthetic series, Q63); disk scales with **samples/sec × retention × bytes-per-sample**; and **query cost is non-linear in range and cardinality** (Q51), so load-test the *actual* dashboards/alerts, not just ingestion. For **Mimir**, the planning shifts to per-component (distributor/ingester/querier/store-gateway/compactor) and per-tenant limits — you size *ingesters* by active series (they hold the recent series in memory, like a head), *store-gateways* by historical query volume against object storage, and you set per-tenant series caps (Q60) from the per-tenant slope. The expert framing: **never capacity-plan by guessing RAM** — measure bytes-per-series and samples/sec on a representative synthetic load, extrapolate the linear ingestion costs and the non-linear query costs separately, build in headroom for WAL replay and churn, and re-measure after launch because the model's biggest error is always *unanticipated cardinality/churn*, which is exactly why the governance loop (Q60/Q88) exists alongside the capacity model.

#### Q91. [Theory] What are the tradeoffs of high-resolution scraping versus downsampling for long-term storage, expressed as a cost/fidelity model?

This question forces you to reason about observability as an explicit **cost-versus-fidelity optimization over time**, which is the staff-level framing. Raw high-resolution data (e.g. 15s) is maximally faithful but its cost grows as `samples/sec × retention × bytes-per-sample × cardinality` — keeping 15s resolution for a year across millions of series is wildly expensive and, crucially, *almost never queried at that fidelity for old data*. Downsampling (Thanos compactor, Mimir) addresses the mismatch between **how fidelity is needed** (high for recent incident debugging, low for historical trend/capacity work) and **what it costs to keep**.

```
Fidelity needed over age:        Cost of keeping it:
  recent (hours-days):  HIGH       raw 15s:   expensive, fidelity wasted on old data
  weeks:                MEDIUM      5m down:   ~20x fewer points than 15s
  months-years:         LOW         1h down:   ~240x fewer points than 15s
Tiered retention matches the two curves:
  15s raw   -> 14 days    (incident-grade fidelity when you actually debug)
  5m        -> 90 days    (week-over-week, capacity trends)
  1h        -> 1-2 years  (SLO history, YoY planning)
```

The tradeoffs to articulate precisely: (1) **Downsampling is lossy by construction** — once you collapse 15s into 5m aggregates (min/max/sum/count are typically retained so quantiles/rates stay reconstructable), you *cannot* later investigate a 30-second sub-minute spike that happened 6 months ago; you've traded the ability to debug fine-grained historical incidents for storage. The mitigation is keeping enough aggregates (min/max alongside avg/sum/count) that the *kinds* of historical questions you actually ask (was there a spike? what was the daily peak?) remain answerable. (2) **Cardinality dominates the cost far more than resolution or retention** (Q31/Q59) — halving active series saves more than dropping a whole resolution tier — so the first lever is always dropping unused/high-cardinality metrics, *then* resolution tiering. (3) **Query cost shifts**: downsampled tiers make historical queries *faster* (fewer points to scan) — a year-long capacity query over 1h data is cheap, the same query over raw 15s would be brutal — so downsampling improves both storage *and* historical query performance, which is why it's usually a clear win. (4) **Recording rules are a complementary, sharper tool**: rather than keep *all* metrics downsampled, precompute the handful of long-retention aggregates teams *actually* query historically (SLI ratios, key golden signals) as recording-rule series and keep *those* at low resolution for years, while letting raw high-cardinality metrics expire quickly. The model to state: choose retention/resolution **per metric importance**, match resolution to the fidelity-vs-age curve, retain min/max/count so historical questions stay answerable, attack cardinality before resolution, and use recording rules to keep only the truly-queried aggregates long-term — turning "keep everything forever" (impossible at scale) into a deliberate, tiered cost/fidelity tradeoff.

#### Q92. [Practical] A spike in a dashboard "disappears" when you zoom out but is clearly there when you zoom in. Explain the cause and the fix.

This is a textbook **resolution/aliasing artifact** and a favorite practical question because it reveals whether you understand the step/`maxDataPoints`/range-function interaction (Q51, Q57, Q82) rather than thinking the data itself changed. Nothing is wrong with the stored data — the spike exists at full resolution. What changes is *how Grafana samples and how the range function averages* at different zoom levels.

When you zoom out, Grafana keeps roughly one point per pixel, so the **step grows** (`$__interval` increases). For a counter graphed with `rate(x[$__rate_interval])`, two things conspire: (1) `$__rate_interval` grows with the step, so the rate is averaged over a *wider window*, smoothing a brief spike toward invisibility; and (2) the wider step means each rendered point summarizes more time, so a 30-second spike that was one prominent point at 15s step becomes a tiny contribution to a 10-minute-wide point when zoomed out — it's *averaged into the noise*. With `irate` it's worse: `irate` only uses the last two samples in the window, so at a coarse step it can **skip the spike's samples entirely** and the spike vanishes completely rather than just shrinking.

```
Zoomed in:  step=15s, rate over ~1m  -> spike is a sharp, visible point
Zoomed out: step=10m, rate over ~40m -> spike averaged across 40m -> flattened to ~baseline
irate zoomed out: last-2-samples may not even include the spike -> spike GONE entirely
```

The fixes depend on what you actually want to see. If you want **brief spikes to remain visible regardless of zoom**, don't graph the *average* rate — graph the **max over the step** using a subquery or `max_over_time`: `max_over_time(rate(http_requests_total[1m])[$__interval:])` so each rendered point shows the *peak* sub-interval rate rather than the average, preserving spikes. Alternatively, for latency, switch from average to a **high percentile** (`histogram_quantile(0.99, ...)`) which surfaces tail spikes that the mean hides. If the spike *should* be smoothed for trend viewing but you also need spike *detection*, that belongs in **alerting** (which evaluates at a fixed short interval, immune to dashboard zoom) and in a dedicated "max rate" panel — not in expecting one averaged panel to serve both purposes. The conceptual lesson interviewers want: **dashboards show a resolution-dependent *summary*, not raw truth** — the average over a step is intentionally lossy, so spikes are a casualty of zooming out; you preserve them by aggregating with `max_over_time`/percentiles instead of mean rate, and you never rely on a zoomable dashboard panel for spike *detection* (that's alerting's job at a fixed window).

#### Q93. [Theory] How do exemplars work end to end — storage, exposition, query, and limits — and why are they sampled rather than complete?

Exemplars are the metrics→traces bridge (Q26), and the expert version traces the whole pipeline plus the deliberate design constraint that they are *sampled, not exhaustive*. An exemplar is a structured annotation attached to a *specific metric observation* — typically a histogram bucket increment — carrying a set of labels (most importantly a `trace_id`), a value, and a timestamp: "this particular observation that landed in the `le=1.0` bucket came from a request whose trace is `abc123`." It lets you click a latency spike and jump to a representative slow trace.

```
# OpenMetrics exposition: exemplar appended after the sample with `#`
http_request_duration_seconds_bucket{le="1.0"} 327 # {trace_id="abc123"} 0.92 1717f00000
                                                  ^^^ exemplar: labels, value, timestamp
```

End to end: (1) **Instrumentation** — the client library, given a trace context (via OpenTelemetry propagation), records an exemplar when it observes into a histogram. (2) **Exposition** — exemplars require the **OpenMetrics format** (the legacy text format can't carry them) and are negotiated via the `Accept` header. (3) **Storage** — Prometheus keeps exemplars in a **separate, fixed-size in-memory circular buffer** (enabled by `--enable-feature=exemplar-storage`, sized by `storage.exemplars.exemplars-limit`), *not* in the TSDB blocks — they are explicitly **not durable** and the oldest are evicted as new ones arrive. (4) **Query** — a dedicated `/api/v1/query_exemplars` endpoint (and Grafana's exemplar rendering) fetches exemplars for a series over a time range, and remote-write 2.0 (Q84) carries them to long-term stores that support exemplar storage. (5) **Linking** — Grafana maps the exemplar's `trace_id` to a Tempo/Jaeger data source (Q79).

Why **sampled, not complete**: storing an exemplar for *every* observation would reintroduce exactly the high-cardinality, high-volume problem that metrics were designed to *avoid* — metrics are cheap precisely because they're aggregates, and attaching a full trace reference to every increment would balloon storage to trace-like volumes, defeating the point. So exemplars are deliberately a **thin, lossy, recent sample** — a handful of representative pointers per series, held briefly in memory, enough to give you *an example* trace for a spike without trying to be a complete index from metrics to traces. The design philosophy to articulate: exemplars trade completeness for near-zero cost, accepting "here is *a* slow request you can investigate" rather than "here are *all* slow requests" — the latter is the tracing backend's job (sampled itself), and the exemplar is just the cheap hyperlink between the aggregate (metric) and the detail (trace). The limit and in-memory-only nature are the cost ceiling that keeps the feature from undermining the very efficiency that makes metrics valuable.

#### Q94. [Practical] Write the queries and rules to monitor Prometheus's own remote_write health, and explain what each failure mode looks like.

`remote_write` (Q21, Q45) is a silent-data-loss risk: if the remote backs up and the WAL overflows, samples are permanently lost, and the *only* sign is in Prometheus's own meta-metrics — the dashboards built *on* the remote store can't show data they never received. So you monitor remote_write from the *sending* Prometheus's perspective, watching the queue, the lag, and the failure counters. The single most important signal is **how far behind the remote is**, expressed as the gap between the newest sample Prometheus has and the newest it has successfully sent.

```promql
# 1. LAG: newest local sample timestamp minus newest successfully-sent timestamp.
#    Growing lag = the queue is falling behind; if it exceeds WAL retention -> data loss.
(
  prometheus_remote_storage_highest_timestamp_in_seconds
  - ignoring(remote_name, url)
  prometheus_remote_storage_queue_highest_sent_timestamp_seconds
) > 120

# 2. SHARDS pinned at max = backpressure; Prometheus has scaled to max_shards and still can't keep up
prometheus_remote_storage_shards == prometheus_remote_storage_shards_max

# 3. FAILED samples: the remote is rejecting writes (4xx schema/auth, or 5xx overload)
rate(prometheus_remote_storage_samples_failed_total[5m]) > 0

# 4. RETRIES / dropped: transient errors causing retries, or samples dropped after exhausting retries
rate(prometheus_remote_storage_samples_dropped_total[5m]) > 0

# 5. PENDING: samples buffered waiting to send (a healthy small number; sustained growth is bad)
prometheus_remote_storage_samples_pending
```

```yaml
# Alerting rules wrapping the above
- alert: RemoteWriteLagging
  expr: (prometheus_remote_storage_highest_timestamp_in_seconds
         - ignoring(remote_name,url) prometheus_remote_storage_queue_highest_sent_timestamp_seconds) > 120
  for: 5m
  labels: { severity: page }       # lag near WAL-retention duration = imminent data loss
  annotations: { summary: "remote_write to {{ $labels.url }} is {{ $value | humanizeDuration }} behind" }
- alert: RemoteWriteFailing
  expr: rate(prometheus_remote_storage_samples_failed_total[5m]) > 0
  for: 10m
  labels: { severity: ticket }
```

The failure modes each have a distinct fingerprint. **Remote is slow/overloaded**: lag grows, `shards == shards_max` (Prometheus auto-scaled shards to the ceiling), `samples_pending` climbs — the fix is raising `max_shards`/`max_samples_per_send`, or scaling the remote. **Remote is rejecting writes**: `samples_failed_total` rises with 4xx (bad auth header / wrong `X-Scope-OrgID` / schema mismatch / out-of-order at the remote) or 5xx (remote overloaded) — read the error, it distinguishes a *config* problem from a *capacity* problem. **WAL about to overflow**: lag approaching your WAL retention (`--storage.tsdb.wal-segment-size` × segments / `--storage.tsdb.retention`) is the **data-loss alarm** — once the WAL truncates past the unsent point, those samples are gone forever, so this is the page-now condition. The expert point (tying to Q66): remote_write health is a **leading indicator of data loss that is invisible downstream** — you must alert on the *sender's* lag and failure counters, because by the time the gap shows up as missing data in Grafana, it's already unrecoverable; the lag-vs-WAL-retention race is the specific thing that turns "remote_write is a bit behind" into "we permanently lost an hour of metrics."

#### Q95. [Theory] Compare summaries and histograms in full depth, including φ-quantile error, aggregation impossibility, and when a summary is actually the right choice.

This revisits the basic distinction (Q2) at expert depth, because the "always use histograms" advice has real exceptions and the *reasons* reveal deep understanding. Both track `_sum` and `_count`; the difference is **where and how quantiles are computed**. A **summary** computes φ-quantiles (e.g. p50, p99) **client-side, in the instrumented process**, using a streaming algorithm (typically the Cormode-Korn-Muthukrishnan or a similar bounded-error streaming quantile estimator) that maintains a compressed sketch with a *configured error bound* φ ± ε. A **histogram** ships **bucket counts** and computes quantiles **server-side** with `histogram_quantile()` interpolating within buckets.

The decisive property is **aggregatability**, and the math is worth stating: **quantiles are not linearly combinable** — `p99(A ∪ B) ≠ f(p99(A), p99(B))` for any function `f` of just the two p99s; you fundamentally cannot reconstruct a combined percentile from per-instance percentiles without the underlying distributions. This is *why* summary quantiles can't be aggregated across instances: each instance's pre-computed p99 is a dead end — averaging them (Q87) is statistically meaningless, and there's no correct alternative because the information needed (the distributions) was discarded client-side. Histograms sidestep this entirely: **bucket counts ARE additive** (`sum by (le)` is a valid associative aggregation, Q77), so you sum buckets across the fleet and *then* compute one correct quantile from the combined distribution. This additivity is also what makes histograms shardable and `rate()`-able.

```
Summary:   process computes p99 locally (φ±ε streaming sketch) -> ships the NUMBER 0.84s
           -> cannot aggregate across instances (p99s don't combine) ; accurate per-instance ; cheap to query
Histogram: process ships BUCKET COUNTS -> server sums buckets (additive) -> ONE fleet-wide quantile
           -> aggregatable/shardable ; accuracy limited by bucket boundaries ; quantile cost at query time
```

Each has an error model. The **summary's** error is the streaming estimator's configured ε (e.g. p99 ± 1%) — *accurate at the exact φ you configured*, but you must pick those quantiles upfront (you can't ask a summary for p95 if it only tracks p50/p99), and the sketch's CPU/memory cost lives in the hot path of every request. The **histogram's** error is **bucket-boundary-bound** — a p99 falling inside a wide bucket is a linear interpolation guess (Q9), so accuracy depends on having buckets where the interesting percentiles land; but you can compute *any* quantile at query time and aggregate freely. **Native histograms** (Q53) largely dissolve the histogram's downside by auto-scaling exponential buckets for uniform accuracy. So when is a summary **actually right**? (1) A **single-instance** component (a sidecar, a singleton, a CLI/batch job) where cross-instance aggregation is irrelevant and you want exact per-process quantiles without choosing buckets. (2) When you need a **precise quantile at a specific φ** and can't guarantee bucket placement, and the metric is genuinely local. (3) Legacy or constrained environments where native histograms aren't supported and bucket tuning is impractical. The expert synthesis: "prefer histograms" is correct *because aggregatability is usually the dominant requirement in distributed systems* and percentiles are non-combinable — but a summary is the right tool for **single-instance, fixed-φ, no-aggregation** cases, and understanding *why* (the non-linearity of quantiles) is what separates rote advice from real comprehension.

#### Q96. [Practical] Production incident: alert latency p99 jumped but CPU, error rate, and traffic all look normal. Walk through your investigation using Prometheus and Grafana.

This is a realistic "the obvious signals are clean but something's wrong" scenario, and the value is in a disciplined investigation that uses the data model rather than guessing. The fact that **latency rose while errors, CPU, and traffic are flat** immediately narrows the hypothesis space: it's not load (traffic flat), not crashing (errors flat), not local compute saturation (CPU flat) — so the latency is being spent *waiting* on something (a dependency, a lock, a queue, GC pauses, or a specific slow slice masked by aggregation).

```promql
# 1. Is it ALL traffic or a SLICE? Break the p99 down by every dimension you have.
#    A global p99 spike that's actually one endpoint/region/tenant is the most common cause.
histogram_quantile(0.99, sum by (le, route)   (rate(http_request_duration_seconds_bucket[5m])))
histogram_quantile(0.99, sum by (le, region)  (rate(http_request_duration_seconds_bucket[5m])))
histogram_quantile(0.99, sum by (le, pod)     (rate(http_request_duration_seconds_bucket[5m])))

# 2. Tail vs whole distribution: did p50 move too, or ONLY p99? (p99-only => a slow minority)
histogram_quantile(0.50, sum by (le)(rate(http_request_duration_seconds_bucket[5m])))

# 3. Where is the time going? Dependency latency (DB/cache/downstream RPC) histograms.
histogram_quantile(0.99, sum by (le, dependency)(rate(downstream_request_duration_seconds_bucket[5m])))

# 4. Saturation that isn't CPU: GC pauses, thread-pool/connection-pool exhaustion, queue depth.
rate(go_gc_duration_seconds_sum[5m])          # GC time
db_connection_pool_in_use / db_connection_pool_max   # pool saturation (a wait, not CPU)
```

The investigation flow: **first decompose the aggregate** (step 1) because the single most common explanation for "p99 up, everything else fine" is the **aggregation blind spot** (Q32) — one route, one region, one tenant, or a few slow pods are dragging the p99 while the *average* and the other dimensions stay clean. Then **check p50 vs p99** (step 2): if only p99 moved, a *minority* of requests got slow (a specific slow path, a cache-miss tail, one bad replica), whereas if p50 moved too, the *whole* distribution shifted (a systemic dependency slowdown). Then **follow the latency to its source** (step 3): latency that's spent *waiting* shows up in *dependency* histograms — a slow database, a degraded cache hit ratio, a downstream service's own p99 rising — and **exemplars** (Q93) are the power move here: click the latency spike on the Grafana panel and jump straight to a trace of a slow request, which often *immediately* shows the offending span (a 900ms DB call) without further query archaeology. Finally **check non-CPU saturation** (step 4): CPU being flat doesn't rule out saturation — **GC pauses**, **connection/thread-pool exhaustion**, **lock contention**, or a **full work queue** all add latency as *waiting* with flat CPU, and these are classic "everything looks fine but it's slow" causes. The likely culprits this pattern points to, ranked: a **slow dependency** (DB/cache/downstream — the most common), a **single bad slice** masked by aggregation, **resource saturation that isn't CPU** (pool/GC/queue), or a **noisy-neighbor/infra** issue (a degraded node, network latency). The staff-level discipline to convey: when the obvious signals are clean, you **decompose the aggregate, separate tail from bulk, follow latency to where it's spent (dependencies, via exemplars/traces), and check the saturation types that don't show as CPU** — and you note that this incident is also a *monitoring gap lesson*: if a single-region or single-tenant slowdown could spike global p99 without a targeted alert, you add per-slice SLO alerting (Q32) so next time it pages with the offending dimension already identified.

## 🧩 Extended Questions — Supplemental Set B: Coding & Expert

### 🟢 Basic — extended

#### Q97. [Coding] Instrument an HTTP handler in Go with a counter and a histogram, and expose `/metrics`.

**Problem:** Add request-rate and latency instrumentation to a minimal Go HTTP server using the official `client_golang` library, with `method`, `path`, and `status` labels, then expose the metrics for scraping.

The two load-bearing decisions are (1) using a **histogram** (not a summary) for latency so quantiles are aggregatable across replicas, and (2) keeping the label set bounded — `path` here is a fixed route template (`/api/users`), never the raw URL with IDs, which would explode cardinality (Q10).

```go
package main

import (
	"net/http"
	"strconv"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promauto"
	"github.com/prometheus/client_golang/prometheus/promhttp"
)

var (
	reqTotal = promauto.NewCounterVec(prometheus.CounterOpts{
		Name: "http_requests_total",
		Help: "Total HTTP requests.",
	}, []string{"method", "path", "status"})

	reqDuration = promauto.NewHistogramVec(prometheus.HistogramOpts{
		Name:    "http_request_duration_seconds",
		Help:    "HTTP request latency.",
		Buckets: prometheus.DefBuckets, // .005 .. 10s; tune to your SLO
	}, []string{"method", "path"})
)

// instrument wraps a handler, capturing status and latency.
func instrument(path string, h http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		rec := &statusRecorder{ResponseWriter: w, status: 200}
		h(rec, r)
		dur := time.Since(start).Seconds()
		reqTotal.WithLabelValues(r.Method, path, strconv.Itoa(rec.status)).Inc()
		reqDuration.WithLabelValues(r.Method, path).Observe(dur)
	}
}

type statusRecorder struct {
	http.ResponseWriter
	status int
}

func (s *statusRecorder) WriteHeader(code int) {
	s.status = code
	s.ResponseWriter.WriteHeader(code)
}

func main() {
	http.HandleFunc("/api/users", instrument("/api/users", func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte("ok"))
	}))
	http.Handle("/metrics", promhttp.Handler())
	http.ListenAndServe(":8080", nil)
}
```

**Why this shape:** `promauto` auto-registers the metrics with the default registry that `promhttp.Handler()` serves, so there is no separate `MustRegister` step. The `statusRecorder` is necessary because Go's `http.ResponseWriter` does not expose the status code after the fact — you must capture it as it is written. **Edge case:** if a handler never calls `WriteHeader`, Go implicitly sends 200, which is why the recorder defaults `status: 200`. Verify with `curl localhost:8080/metrics | grep http_request` and you should see one `_bucket` series per `le` boundary plus `_sum` and `_count`.

#### Q98. [Coding] Instrument a Python Flask app and expose metrics, including a gauge for in-flight requests.

**Problem:** Add a counter, a histogram, and an **in-flight gauge** to a Flask app using `prometheus_client`, and expose `/metrics` correctly under a multi-process WSGI server.

```python
from flask import Flask, request, Response
from prometheus_client import Counter, Histogram, Gauge, generate_latest, CONTENT_TYPE_LATEST
import time

app = Flask(__name__)

REQS = Counter("http_requests_total", "Total requests",
               ["method", "endpoint", "status"])
LAT = Histogram("http_request_duration_seconds", "Latency",
                ["method", "endpoint"],
                buckets=(0.01, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5))
INFLIGHT = Gauge("http_requests_in_flight", "Requests currently being served")

@app.before_request
def _start():
    request._t0 = time.perf_counter()
    INFLIGHT.inc()

@app.after_request
def _record(resp):
    INFLIGHT.dec()
    ep = request.endpoint or "unknown"
    LAT.labels(request.method, ep).observe(time.perf_counter() - request._t0)
    REQS.labels(request.method, ep, resp.status_code).inc()
    return resp

@app.route("/api/users")
def users():
    return "ok"

@app.route("/metrics")
def metrics():
    return Response(generate_latest(), mimetype=CONTENT_TYPE_LATEST)
```

**The multiprocess gotcha** is the real interview content: under Gunicorn/uWSGI with multiple worker processes, each worker has its *own* in-memory registry, so a scrape hits a random worker and you see flapping, partial values. The fix is the **multiprocess mode**: set `PROMETHEUS_MULTIPROC_DIR=/tmp/prommp` (a shared, writable, *cleared-on-restart* directory), and build the exposition from a `MultiProcessCollector`:

```python
# In multiprocess mode, /metrics must aggregate across workers:
from prometheus_client import multiprocess, CollectorRegistry, generate_latest

def metrics():
    reg = CollectorRegistry()
    multiprocess.MultiProcessCollector(reg)   # reads per-PID files from the dir
    return Response(generate_latest(reg), mimetype=CONTENT_TYPE_LATEST)
```

**Edge case:** gauges in multiprocess mode need a `multiprocess_mode` (`all`/`liveall`/`min`/`max`/`sum`) because there's no single "current value" across workers — `liveall` (sum across *live* workers) is usually what you want for an in-flight gauge. Forgetting to clear `PROMETHEUS_MULTIPROC_DIR` on restart leaves stale per-PID files and double-counts. This is the single most common Python-Prometheus production bug.

#### Q99. [Coding] Write a `promtool` unit test for an alerting rule so a regression can't ship.

**Problem:** You have a `HighErrorRate` alert. Write a `promtool test rules` file that feeds synthetic series in and asserts the alert fires (and does not fire) at the right times — runnable in CI.

```yaml
# tests/alert_test.yml  ->  run with: promtool test rules tests/alert_test.yml
rule_files:
  - ../rules.yml          # the file under test (contains job:..ratio_rate5m + HighErrorRate)

evaluation_interval: 1m

tests:
  - interval: 1m
    input_series:
      # 5xx counter climbs ~2/s while total climbs ~10/s  => 20% error ratio
      - series: 'http_requests_total{job="api", status="500"}'
        values: '0+120x30'          # +120 per minute = 2/s
      - series: 'http_requests_total{job="api", status="200"}'
        values: '0+480x30'          # +480 per minute = 8/s  (total => 10/s)
    alert_rule_test:
      # After the 10m `for`, the alert must be firing.
      - eval_time: 15m
        alertname: HighErrorRate
        exp_alerts:
          - exp_labels:
              severity: page
              job: api
            exp_annotations:
              summary: "High 5xx error rate on api"
      # Before the `for` window elapses, it must NOT yet be firing.
      - eval_time: 3m
        alertname: HighErrorRate
        exp_alerts: []
```

**Why this matters:** alerting rules are code that only runs in production at 3 a.m., so they rot silently — a refactor of a label name, a flipped comparison, or a typo'd `for` ships undetected until an incident. `promtool test rules` gives you deterministic, hermetic tests with the same evaluation engine Prometheus uses. The `'0+120x30'` syntax means "start at 0, add 120 each step, 30 steps." **Edge cases:** test both the positive case (fires) *and* the negative case (does not fire before `for`, and does not fire when traffic is zero so the ratio is `NaN`) — the negative tests catch divide-by-zero and over-eager alerts. Wire this into CI alongside `promtool check rules` (syntax) so both syntax and semantics are gated.

### 🟡 Intermediate — extended

#### Q100. [Coding] Write a custom exporter from scratch (Python) that scrapes an external API and exposes it as metrics.

**Problem:** A legacy billing API exposes JSON at `/stats` but speaks no Prometheus. Write a small exporter that polls it and serves the Prometheus format, using a **custom collector** so values are always fresh at scrape time rather than cached on a timer.

```python
from prometheus_client import start_http_server
from prometheus_client.core import GaugeMetricFamily, CounterMetricFamily, REGISTRY
import requests, time

class BillingCollector:
    """Collect is called ON EACH SCRAPE -> values reflect the moment of scrape."""
    def __init__(self, url):
        self.url = url

    def collect(self):
        try:
            data = requests.get(self.url, timeout=5).json()
        except Exception:
            # Expose a health gauge so failures are visible, not silent.
            up = GaugeMetricFamily("billing_scrape_up", "1 if API reachable")
            up.add_metric([], 0.0)
            yield up
            return

        up = GaugeMetricFamily("billing_scrape_up", "1 if API reachable")
        up.add_metric([], 1.0)
        yield up

        # A gauge with a label dimension (per-plan active subscriptions).
        subs = GaugeMetricFamily("billing_active_subscriptions",
                                 "Active subs by plan", labels=["plan"])
        for plan, n in data["subscriptions_by_plan"].items():
            subs.add_metric([plan], float(n))
        yield subs

        # A counter (monotonic) for total invoices issued.
        inv = CounterMetricFamily("billing_invoices_issued_total",
                                  "Invoices issued")
        inv.add_metric([], float(data["invoices_total"]))
        yield inv

if __name__ == "__main__":
    REGISTRY.register(BillingCollector("http://billing.internal/stats"))
    start_http_server(9821)        # serves /metrics
    while True:
        time.sleep(3600)
```

**Why a custom collector instead of module-level `Gauge.set()` on a timer:** with the collector pattern, `collect()` runs synchronously during the scrape, so the values are computed *at scrape time* and there is no risk of Prometheus reading a half-updated value or a value from a stale background loop. The cost is that a slow upstream API now blocks the scrape — so you set a hard `timeout` on the request and you **always emit a `billing_scrape_up` health gauge** so a failing upstream is observable rather than appearing as a frozen/absent metric. **Edge case:** never emit a counter that can decrease (e.g. a value the API resets); if the upstream resets, expose it as a gauge or recompute a monotonic total yourself, otherwise `rate()` will misbehave.

#### Q101. [Coding] Write a PromQL query for week-over-week traffic comparison using the `offset` modifier.

**Problem:** Plot current request rate against the same time last week to visualize anomalies (the classic "is today weird?" panel).

```promql
# Current 5m rate
sum(rate(http_requests_total[5m]))

# Same metric, shifted back exactly one week (use a SEPARATE query / series).
sum(rate(http_requests_total[5m] offset 1w))

# Percentage deviation from last week, in one expression:
100 * (
  sum(rate(http_requests_total[5m]))
  /
  sum(rate(http_requests_total[5m] offset 1w))
  - 1
)
```

The `offset` modifier shifts the *evaluation time* of its vector backward, so `offset 1w` makes the query read data as it was one week earlier while keeping the graph's x-axis aligned to *now*. This is far cleaner than time-shifting in Grafana, and it composes: you can wrap the whole thing in a deviation formula so a panel shows "+38% vs last week" directly.

**Edge cases:** `offset` only reaches back as far as your **retention** allows — `offset 30d` against a 15-day retention returns nothing. The `@` modifier is the cousin you reach for when you need an *absolute* anchor instead of a relative shift (`metric @ 1609459200` pins evaluation to a fixed Unix timestamp, useful for "compare against the value at the last deploy"). Beware comparing across a **DST boundary**: `offset 1w` is exactly 168 hours, which is one hour off from "same wall-clock time last week" on the weekend the clocks change. For long seasonal baselines, a recording rule that snapshots the baseline is more robust than ever-deeper `offset`.

#### Q102. [Coding] Detect a memory leak with `predict_linear` and alert before the host runs out of disk/RAM.

**Problem:** Page *before* `/` fills up, not after. Use `predict_linear` to forecast when free disk will hit zero based on the recent trend.

```yaml
groups:
  - name: capacity-forecast
    rules:
      - alert: DiskWillFillIn4h
        # Take 1h of trend, extrapolate 4h (14400s) into the future.
        # Fire if the predicted free bytes is below zero (i.e. it will fill).
        expr: |
          predict_linear(node_filesystem_avail_bytes{mountpoint="/"}[1h], 4 * 3600) < 0
          and
          node_filesystem_avail_bytes{mountpoint="/"} > 0
        for: 10m
        labels: { severity: page }
        annotations:
          summary: "Disk {{ $labels.mountpoint }} on {{ $labels.instance }} predicted full within 4h"
```

`predict_linear(v[1h], 14400)` fits a simple least-squares **linear regression** over the last hour of samples and projects the value 14400 seconds (4h) forward. Forecasting on free space (rather than alerting on a static "90% full" threshold) is superior because a disk filling at 10 GB/min on a 10 TB volume needs action *now* even at 60% full, while a stable 95%-full volume that never grows doesn't need a page at all. It turns a level alert into a *rate-of-change* alert.

**Edge cases & traps:** linear prediction assumes the trend continues linearly — it badly mispredicts on sawtooth patterns (log rotation, cache eviction), so the `for: 10m` guards against a momentary slope, and you'd typically use a longer fit window (`[6h]`) for noisy signals. The `and node_filesystem_avail_bytes > 0` clause avoids alerting on a volume that is *already* full (where the prediction is meaningless). The same pattern detects an application heap leak: `predict_linear(process_resident_memory_bytes[2h], 6*3600) > node_memory_limit_bytes` warns you before the OOM-killer does.

#### Q103. [Coding] Reshape topology with `label_replace` to join a metric's `instance` to a human-readable node name.

**Problem:** `node_cpu_seconds_total` is labeled by `instance="10.0.4.7:9100"` but your team thinks in node names. Without re-instrumenting, derive a `node` label from the IP using a mapping metric, or extract a clean hostname from the instance string.

```promql
# Case A: strip the :port and keep just the IP/host as a new `node` label.
label_replace(
  sum by (instance) (rate(node_cpu_seconds_total{mode!="idle"}[5m])),
  "node",            # new label
  "$1",              # replacement (capture group 1)
  "instance",        # source label
  "([^:]+):.*"       # regex: capture everything before the colon
)

# Case B: enrich CPU usage with role/team via a join on an info metric
# (kube-state-metrics style `*_info` series that carry metadata as labels).
sum by (instance) (rate(node_cpu_seconds_total{mode!="idle"}[5m]))
  * on (instance) group_left(role, team)
  node_meta_info
```

`label_replace(v, dst, replacement, src, regex)` runs `regex` against the `src` label of every series and, on a match, writes `replacement` (with `$1`/`$2` back-references) into `dst`. Case A is the common "make the graph legend readable" move. Case B is the more powerful pattern: a **info-metric join** (`group_left`) attaches metadata (`role`, `team`, `version`) carried on a separate `_info` series onto your real metric, which is how you enrich metrics with dimensions the instrumented code never knew about. **Edge case:** `label_replace` leaves the series unchanged if the regex doesn't match (it does not drop it), and the join in Case B requires the join labels (`instance`) to match exactly one `_info` series, or you get a "many-to-many matching" error — which is why `_info` metrics are designed to have value `1` and one series per entity.

### 🟠 Advanced — extended

#### Q104. [Coding] Write a `recording rule` hierarchy that aggregates RED metrics up cleanly, and explain why you can't just `sum` percentiles.

**Problem:** Build a tiered set of recording rules so dashboards query cheap pre-aggregates at job, service, and global level — and do it correctly for latency, where naive aggregation is mathematically wrong.

```yaml
groups:
  - name: red-aggregation
    interval: 30s
    rules:
      # --- Rate & Errors: counters, which ARE linearly aggregatable ---
      - record: instance:http_requests:rate5m
        expr: sum by (instance, job, status) (rate(http_requests_total[5m]))

      - record: job:http_requests:rate5m
        expr: sum without (instance) (instance:http_requests:rate5m)

      # --- Latency: aggregate the BUCKETS, never the quantiles ---
      # WRONG: avg(p99_per_instance) -> meaningless. RIGHT: re-aggregate buckets.
      - record: job:http_request_duration_seconds_bucket:rate5m
        expr: sum by (job, le) (rate(http_request_duration_seconds_bucket[5m]))

      # The dashboard then computes the quantile FROM the aggregated buckets:
      # histogram_quantile(0.99, job:http_request_duration_seconds_bucket:rate5m)
```

The deep point an interviewer is testing: **percentiles do not commute with aggregation.** The average of per-instance p99s is not the fleet p99 — if one instance does 1 request and another does 1M, an unweighted average of their p99s is nonsense, and even a weighted one is only an approximation. The mathematically correct approach is to aggregate the **raw histogram buckets** (which *are* additive, because a count of observations in a bucket is just a counter) and only then apply `histogram_quantile()` to the summed buckets. That is exactly why histograms beat summaries for fleets (Q9/Q95): you can't re-aggregate a client-computed summary quantile, but you can always re-sum buckets.

**Why the tiering:** `job:...rate5m` is built *on top of* `instance:...rate5m` (rule dependency within a group, Q76), so the expensive `rate()` over raw series runs once and everything downstream reads a tiny pre-aggregated series. **Edge case:** keep the `le` label through every latency aggregation — drop it and `histogram_quantile` has nothing to interpolate over and returns `NaN`. Preserve `le` with `sum by (..., le)` or `sum without (instance)` (which keeps `le` by not naming it).

#### Q105. [Coding] Build a Grafana dashboard as code (JSON model) with a templated variable and a threshold-colored stat panel.

**Problem:** Define a minimal but real Grafana dashboard JSON that has a `$service` query variable and one stat panel showing the error ratio with green/red thresholds — the kind of artifact you check into Git and provision (Q70).

```json
{
  "title": "Service SLO Overview",
  "uid": "svc-slo-overview",
  "schemaVersion": 39,
  "templating": {
    "list": [
      {
        "name": "service",
        "type": "query",
        "datasource": { "type": "prometheus", "uid": "${DS_PROM}" },
        "query": "label_values(http_requests_total, service)",
        "refresh": 2,
        "includeAll": false
      }
    ]
  },
  "panels": [
    {
      "type": "stat",
      "title": "5xx error ratio — $service",
      "gridPos": { "h": 6, "w": 8, "x": 0, "y": 0 },
      "datasource": { "type": "prometheus", "uid": "${DS_PROM}" },
      "targets": [
        {
          "refId": "A",
          "expr": "sum(rate(http_requests_total{service=\"$service\",status=~\"5..\"}[$__rate_interval])) / sum(rate(http_requests_total{service=\"$service\"}[$__rate_interval]))"
        }
      ],
      "fieldConfig": {
        "defaults": {
          "unit": "percentunit",
          "thresholds": {
            "mode": "absolute",
            "steps": [
              { "color": "green", "value": null },
              { "color": "orange", "value": 0.005 },
              { "color": "red", "value": 0.01 }
            ]
          }
        }
      }
    }
  ]
}
```

**Why dashboard-as-code:** clicking dashboards in the UI creates snowflakes that drift, can't be code-reviewed, and vanish if the Grafana DB is lost. Checking the JSON model into Git and provisioning it (via the provisioning `dashboards` provider or Grafana's HTTP API / Terraform / Grizzly) makes dashboards reproducible, reviewable, and environment-portable. The two non-obvious bits: `refresh: 2` means the variable re-queries on time-range change (so a newly deployed service appears), and `$__rate_interval` (not a hardcoded `[5m]`) keeps the rate window correct as users zoom (Q57, Q92). **Edge cases:** the `${DS_PROM}` datasource is a templated input so the same JSON works across prod/staging; and the `value: null` first threshold step is required — it is the "base" color below the first real threshold.

#### Q106. [Coding] Write a PromQL query to find the top-N highest-cardinality metrics, and a query to measure series churn.

**Problem:** Cardinality is the #1 cost driver (Q10, Q60). Write the queries you'd actually run to hunt offenders during an incident or audit.

```promql
# 1. Top 10 metric NAMES by number of active series (the usual offender list).
topk(10, count by (__name__)({__name__=~".+"}))

# 2. For a suspect metric, which LABEL is exploding it?
#    Count distinct values of `user_id` -> if huge, that's your culprit.
count(count by (user_id)(suspect_metric))

# 3. Series CHURN: new series created per second (a leak even if total looks stable).
sum(rate(prometheus_tsdb_head_series_created_total[5m]))

# 4. Per-job ingestion pressure: samples scraped per target (sudden growth = leak at source).
topk(10, scrape_samples_scraped)

# 5. Head series total vs your mental budget.
prometheus_tsdb_head_series
```

Query 1 is the bread-and-butter "what is eating my memory" query — `count by (__name__)` collapses every series under each metric name into a count, and `topk(10, ...)` surfaces the worst ten. Query 2 isolates *which label* drives a specific metric's cardinality by counting distinct values. The subtle and frequently-missed one is **churn** (Query 3, Q63): a metric can hold a *constant* number of active series while constantly creating and retiring them (e.g. a label keyed on pod name in a cluster that's churning pods), which bloats the index and slows compaction even though `head_series` looks flat. `prometheus_tsdb_head_series_created_total` is a counter, so `rate()` reveals the creation velocity. **Edge case:** Query 1 is itself expensive on a giant TSDB (it touches every series) — prefer the built-in TSDB Status page or run it sparingly, and on Mimir use the per-tenant cardinality API rather than hammering the query path.

#### Q107. [Coding] Write an Alertmanager routing tree and inhibition rules for a tiered, team-based on-call, and test the routing with `amtool`.

**Problem:** Route alerts by severity and team, mute downstream noise during a cluster-wide outage, and prove the routing is correct before relying on it.

```yaml
# alertmanager.yml
route:
  receiver: fallback
  group_by: ['alertname', 'cluster', 'service']
  group_wait: 30s
  group_interval: 5m
  repeat_interval: 4h
  routes:
    - matchers: ['team="payments"']
      receiver: payments-slack
      routes:                                  # nested: page-severity overrides channel
        - matchers: ['severity="page"']
          receiver: payments-pagerduty
          group_wait: 0s                        # page-severity: notify immediately
    - matchers: ['team="search"']
      receiver: search-slack
      continue: false

inhibit_rules:
  # If a whole cluster is declared down, mute per-service alerts in that cluster.
  - source_matchers: ['alertname="ClusterDown"']
    target_matchers: ['severity=~"page|warning"']
    equal: ['cluster']

receivers:
  - name: fallback
    slack_configs: [{ channel: '#alerts-unrouted' }]
  - name: payments-slack
    slack_configs: [{ channel: '#payments-alerts' }]
  - name: payments-pagerduty
    pagerduty_configs: [{ routing_key: '<secret>' }]
  - name: search-slack
    slack_configs: [{ channel: '#search-alerts' }]
```

```bash
# Validate config syntax + the routing tree structure
amtool check-config alertmanager.yml

# CRITICAL: simulate where a labeled alert WOULD route, without sending anything.
amtool config routes test \
  --config.file=alertmanager.yml \
  team=payments severity=page
# expected output: payments-pagerduty
```

The design points: a **nested route** lets a payments `page` alert escalate to PagerDuty while everything else for payments goes to Slack, and `group_wait: 0s` on the page branch removes the default 30s batching delay for the most urgent class. The **inhibition rule** is the noise-control workhorse — when `ClusterDown` fires, the 200 per-service alerts from that same `cluster` are suppressed so on-call sees one actionable alert, not a storm. The often-skipped step that separates seniors from staff is `amtool config routes test`: routing trees are easy to get subtly wrong (matcher precedence, a missing `continue`), and this command tells you *exactly* which receiver a given label set lands in **without firing a real alert** — make it a CI gate. **Edge case:** `continue: false` (the default) stops at the first matching top-level route; set `continue: true` when an alert should reach multiple receivers (e.g. a Slack mirror *and* PagerDuty).

#### Q108. [Coding] Write a `kubectl`/PromQL workflow to compute Kubernetes pod CPU throttling and right-size requests/limits.

**Problem:** Pods are slow but CPU usage looks low — classic CFS throttling. Quantify throttling and derive a data-driven CPU request from actual usage.

```promql
# 1. CPU THROTTLING ratio: fraction of CFS periods where the pod was throttled.
#    High value (>0.25) => the limit is too low; the pod is being capped.
sum by (namespace, pod) (rate(container_cpu_cfs_throttled_periods_total[5m]))
/
sum by (namespace, pod) (rate(container_cpu_cfs_periods_total[5m]))

# 2. Actual CPU usage (cores) vs the configured REQUEST (from kube-state-metrics).
sum by (namespace, pod) (rate(container_cpu_usage_seconds_total[5m]))

# 3. Right-sizing: p95 of usage over a week => a sane request value.
quantile_over_time(0.95,
  sum by (namespace, pod) (rate(container_cpu_usage_seconds_total[5m]))[7d:5m]
)

# 4. Requested vs limit, to see headroom:
kube_pod_container_resource_requests{resource="cpu"}
kube_pod_container_resource_limits{resource="cpu"}
```

```bash
# Cross-check the live throttling counter straight from cgroup metrics on the node
kubectl get --raw "/api/v1/nodes/$NODE/proxy/metrics/cadvisor" \
  | grep container_cpu_cfs_throttled_periods_total | head
```

The insight is that **CPU limits cause throttling, not just capping** — the Linux CFS scheduler enforces a limit by pausing the container for the rest of a 100ms period once it has used its quota, so a pod with a 1-core limit that briefly wants 2 cores gets *stalled* mid-request even when the node has idle CPU. Query 1 quantifies this directly; a throttled ratio above ~25% is a strong signal the limit is hurting latency. Query 3 uses a **subquery** (`[7d:5m]`) to compute the p95 of the 5m rate over a week, which is the defensible "request" value — you size requests at real p95 usage so the scheduler packs nodes efficiently without throttling. **Edge case:** the subquery `[7d:5m]` is expensive (it evaluates the inner expression at 5m steps across 7 days) — run it in a recording rule or against a downsampled long-term store, not interactively on a busy Prometheus. The broader staff-level guidance: many teams remove CPU *limits* entirely (keeping requests) to eliminate throttling, relying on requests for scheduling fairness — and they only learn this is needed by looking at Query 1.

#### Q109. [Theory] Explain how scrape interval, `rate()` window, `$__rate_interval`, and Grafana "max data points" interact to cause or hide spikes, with the exact arithmetic.

This is the question that ties together several "why did my graph lie" mysteries. There are three independent time parameters and they must satisfy a relationship. (1) The **scrape interval** (say 15s) is the spacing of raw samples. (2) The **`rate()` window** (`[5m]`) must contain at least two samples to produce output; the community floor is that the window should be **≥ 4× the scrape interval** so a missed scrape doesn't leave the window with too few points and produce gaps. (3) Grafana's **step** is derived from the panel's time range divided by **max data points** (`maxDataPoints`, ~the panel pixel width), and `$__interval` equals that step.

The trap: if you hardcode `rate(x[1m])` but Grafana, on a 7-day view, computes a step of 10 minutes, then Grafana asks Prometheus for one data point every 10 minutes — but each of those points is a `rate` over only a 1-minute window. You are sampling 1-minute snapshots every 10 minutes, so **90% of the data is never looked at**, and any spike that lived in the 9 unobserved minutes vanishes (Q92). Conversely, on a zoomed-in view the step might be 15s while `[1m]` averages over 4 samples, oversmoothing.

```
scrape_interval = 15s
panel range     = 6h,  maxDataPoints = 700  ->  step = 6h/700 ≈ 31s
$__rate_interval = max(4 × scrape_interval, step + scrape_interval) ≈ 60s

rate(x[$__rate_interval])  ->  window grows/shrinks WITH the step,
                               so every raw sample is covered, no blind gaps.
```

`$__rate_interval` exists precisely to enforce the invariant **window ≥ step** (specifically `max(4×scrape, step+scrape)`), so the rate window always spans at least the gap between the points Grafana plots, guaranteeing full coverage with no double-counting. The practical rule for interviews: **always use `$__rate_interval` in Grafana, never a fixed `[5m]`**, and understand that the spike "disappearing on zoom-out" is an aliasing artifact of step > window, not lost data — the raw samples are still in the TSDB.

#### Q110. [Theory] Walk through diagnosing and fixing slow PromQL queries: what makes a query expensive, and the levers to fix it.

Query cost in Prometheus is dominated by two factors: **the number of series touched** and **the number of samples examined** (series × samples-per-series-in-range). A query like `rate(http_requests_total[5m])` with no matchers on a metric that has 2M series must select and process all 2M, even if you then `sum` them down to one line — the work happens *before* the aggregation. The engine flow is: parse → select series via the inverted index (postings lists for each label matcher, intersected) → fetch chunks for the time range → decode samples → apply functions → aggregate. The expensive steps are postings intersection (for low-selectivity matchers) and chunk decode (for wide time ranges or many series).

The diagnostic levers, in order: (1) check `--query.timeout` and the **query log** (`--query.log-file`) or the `/api/v1/query` stats (`stats=all` returns `samplesProcessed` and timings); (2) make matchers **more selective** — `{job="api"}` uses the index, whereas a leading-wildcard regex `{__name__=~".*latency.*"}` cannot and forces a full scan; (3) **narrow the range** — `[1h]` decodes 4× the samples of `[15m]`; (4) **precompute with recording rules** so dashboards read a 1-series pre-aggregate instead of re-deriving from millions of raw series every refresh; (5) **avoid high-cardinality `by`/`without` groupings** that produce huge intermediate result sets.

```
COST ≈ (series matched)  ×  (samples per series in range)  +  (groups produced)

Cheap : rate(http_requests_total{job="api"}[5m])      -> selective matcher, small range
Costly: rate({__name__=~".+"}[24h])                    -> every series, huge range
Fix   : job:http_requests:rate5m                        -> recording rule, 1 series read
```

**Edge cases:** a regex matcher that is *anchored and literal-prefixed* (`=~"api_.*"`) can still use the index for the prefix; a fully unanchored one cannot. On Mimir/Thanos, **query sharding** (Q77) parallelizes by splitting the series space, but only for shardable expressions (associative aggregations like `sum`, `count`) — a `histogram_quantile` over the whole fleet or a `topk` doesn't shard the same way. The staff-level summary: **selectivity + range + recording rules** are 90% of query performance, and the single highest-leverage fix is moving repeated heavy aggregations into recording rules.

### 🔴 Expert — extended

#### Q111. [Coding] Implement a synthetic "dead man's switch" (Watchdog) end to end so you detect when the alerting pipeline itself is broken.

**Problem:** All the alerts in the world are useless if Prometheus, Alertmanager, or the notifier is dead — you'd see *silence*, which looks identical to "all healthy." Build an alert that **always fires** and an external system that pages when it *stops* arriving.

```yaml
# 1. Prometheus rule: an alert that is ALWAYS firing by construction.
groups:
  - name: watchdog
    rules:
      - alert: Watchdog
        expr: vector(1)          # always 1 -> always firing
        labels: { severity: watchdog }
        annotations:
          summary: "Alerting pipeline is alive. If this STOPS, the pipeline is broken."
```

```yaml
# 2. Alertmanager: route the Watchdog to a heartbeat receiver (Dead Man's Snitch,
#    PagerDuty heartbeat, healthchecks.io) on a tight repeat_interval.
route:
  routes:
    - matchers: ['severity="watchdog"']
      receiver: deadmansswitch
      group_wait: 0s
      group_interval: 1m
      repeat_interval: 1m         # send a heartbeat every minute
receivers:
  - name: deadmansswitch
    webhook_configs:
      - url: 'https://nosnch.in/<token>'   # external service that pages on MISSING beat
        send_resolved: false
```

The entire design is an **inversion of normal alerting**: instead of "page when a condition becomes true," it is "page when a heartbeat *stops*." `vector(1)` is a constant instant vector that is always present, so the `Watchdog` alert is perpetually firing; Alertmanager forwards it as a heartbeat every minute to an **external** service (Dead Man's Snitch, healthchecks.io, a PagerDuty heartbeat integration). That external system is configured to alarm if it does *not* receive the beat within, say, 5 minutes. Because the watchdog flows through the *complete* pipeline — scrape engine → rule evaluation → Alertmanager → notifier — its absence proves *something* in that chain is broken, even if you can't tell exactly what yet.

**Why external:** the check must live *outside* your monitoring stack, otherwise the same outage that kills Prometheus kills the thing watching Prometheus. **Edge cases:** keep `repeat_interval` short (1m) so the external timeout can be tight; ensure the watchdog route does **not** get caught by an inhibition or silence (a global silence that accidentally mutes the watchdog blinds you); and pair it with the standard internal meta-alerts (`up == 0` on Alertmanager, `prometheus_notifications_dropped_total`) so when the watchdog *does* go quiet, you have corroborating signals to localize the failure.

#### Q112. [Coding] Write the queries and an SLO burn-rate rule using native histograms, and contrast the PromQL with classic histograms.

**Problem:** You've migrated `http_request_duration_seconds` to a **native histogram**. Show the quantile and SLO queries, and explain what changes versus classic `_bucket` series.

```promql
# CLASSIC histogram quantile (fixed _bucket series, must keep `le`):
histogram_quantile(0.99,
  sum by (le) (rate(http_request_duration_seconds_bucket[5m])))

# NATIVE histogram quantile — note: NO `le`, the series IS the histogram.
histogram_quantile(0.99,
  sum(rate(http_request_duration_seconds[5m])))

# Fraction of requests faster than the 300ms SLO threshold (native):
histogram_fraction(0, 0.3,
  sum(rate(http_request_duration_seconds[5m])))

# That fraction is the SLI; the error budget burn rule built on it:
1 - histogram_fraction(0, 0.3, sum(rate(http_request_duration_seconds[5m]))) > (14.4 * 0.001)
```

The headline difference: a native histogram is stored as a **single series** carrying a sparse, exponentially-bucketed sketch, so there is no `le` label and no fan-out into dozens of `_bucket` series. That means `sum(rate(native[5m]))` aggregates whole histograms directly (the bucket schema is self-describing and mergeable), and you drop the awkward `sum by (le)` ritual. `histogram_fraction(lower, upper, h)` is a native-only function that answers "what fraction of observations fell in this range" — perfect for latency SLOs ("what fraction was under 300ms") without manually picking a bucket boundary, which is impossible to do precisely with classic fixed buckets unless 0.3 happened to be a boundary.

**Migration caveats (the expert content):** classic and native versions of the *same* metric can coexist during migration (scrape with `scrape_classic_histograms` / the dual-exposition setting), but PromQL functions differ — `histogram_quantile` auto-detects which it's given, while `*_bucket`-based recording rules and dashboards must be rewritten. Native histograms need a compatible storage/remote-write path (Remote-Write 2.0 carries them natively; older remote stores may reject or drop them, Q84). Accuracy is governed by a **schema/resolution factor** rather than your bucket guesses, so p99.9 is meaningfully more accurate at far lower storage cost. **Edge case:** federation and some downsamplers historically had partial native-histogram support — verify your whole pipeline (Grafana version, Mimir/Thanos version) supports them before migrating SLO-critical metrics.

#### Q113. [Coding] Write a bash/PromQL pre-deploy canary gate: query Prometheus to decide whether to promote or roll back a deployment.

**Problem:** In a progressive-delivery pipeline, after shifting 10% of traffic to a canary, programmatically query Prometheus and fail the pipeline (auto-rollback) if the canary's error rate or latency is worse than the baseline.

```bash
#!/usr/bin/env bash
set -euo pipefail
PROM="http://prometheus.internal:9090"

# Helper: run an instant query, return the scalar result (or 0 if empty).
promq() {
  curl -sg --data-urlencode "query=$1" "$PROM/api/v1/query" \
    | jq -r '.data.result[0].value[1] // "0"'
}

# Canary error ratio vs baseline error ratio over the last 5 minutes.
CANARY_ERR=$(promq 'sum(rate(http_requests_total{deploy="canary",status=~"5.."}[5m])) / clamp_min(sum(rate(http_requests_total{deploy="canary"}[5m])), 1)')
BASE_ERR=$(promq   'sum(rate(http_requests_total{deploy="stable",status=~"5.."}[5m])) / clamp_min(sum(rate(http_requests_total{deploy="stable"}[5m])), 1)')

# Canary p99 latency (native histogram) vs baseline.
CANARY_P99=$(promq 'histogram_quantile(0.99, sum(rate(http_request_duration_seconds{deploy="canary"}[5m])))')
BASE_P99=$(promq   'histogram_quantile(0.99, sum(rate(http_request_duration_seconds{deploy="stable"}[5m])))')

echo "canary_err=$CANARY_ERR base_err=$BASE_ERR canary_p99=$CANARY_P99 base_p99=$BASE_P99"

# Fail (exit 1 -> CI rolls back) if canary is >50% worse on either signal.
fail=0
awk -v c="$CANARY_ERR" -v b="$BASE_ERR" 'BEGIN{exit !(c > b*1.5 + 0.001)}' && { echo "ERROR RATE regressed"; fail=1; }
awk -v c="$CANARY_P99" -v b="$BASE_P99" 'BEGIN{exit !(c > b*1.5)}'         && { echo "P99 LATENCY regressed"; fail=1; }
exit $fail
```

The design pattern is **metrics-driven progressive delivery** — the same idea Argo Rollouts / Flagger automate. You label every series with `deploy="canary"|"stable"` (via a relabeling rule on the pod's deployment label), let traffic flow for a soak window, then compare the canary's golden signals against the stable baseline *that is serving the same kind of traffic right now*. Comparing canary-vs-current-stable (rather than canary-vs-a-static-threshold) controls for time-of-day and organic traffic shifts. `clamp_min(..., 1)` is the divide-by-zero guard (a canary with almost no traffic would otherwise produce `NaN`).

**Edge cases & rigor:** a canary with tiny traffic has noisy ratios — gate on a **minimum request count** before trusting the comparison, or extend the soak window, otherwise you'll roll back on statistical noise. Use a **multiplicative tolerance** (`> base * 1.5`) plus a small absolute floor so a baseline of literally 0 errors doesn't make any single canary error a 100% "regression". For production-grade gating, Flagger/Argo do exactly this with configurable metric templates, step weights, and automatic rollback — this script is the conceptual core they implement.

#### Q114. [Behavioral] Tell me about a time you had to push back on adding a metric or alert that a senior stakeholder wanted. (STAR)

Strong answers show you can hold a technical line *and* preserve the relationship, framing the trade-off in terms the stakeholder cares about. Example using STAR:

**Situation:** "A VP, after a customer-visible incident, mandated 'an alert for every error in the system' and wanted a new per-customer error metric so support could see exactly which customer hit which error. The intent was good — better incident response — but both asks were technically dangerous: a per-customer label on an error counter meant unbounded cardinality (Q10), and 'alert on every error' guarantees alert fatigue that *causes* missed real pages."

**Task:** "I owned the monitoring platform's reliability and cost. My job was to deliver the *outcome* the VP wanted — faster, customer-aware incident response — without introducing a cardinality bomb that would OOM Prometheus, and without flooding on-call with non-actionable pages."

**Action:** "I didn't say 'no.' I reframed it around the underlying need. For the per-customer visibility, I showed that customer identity belongs in **logs and traces, not metric labels**, and stood up an exemplar-linked Grafana panel so support could jump from an error-rate spike straight to a trace carrying the customer ID — same outcome, zero cardinality cost. For 'alert on everything,' I walked the VP through our own data: I pulled the last quarter's pages and showed that we already had a high non-actionable rate, and that more alerts statistically *lower* mean-time-to-acknowledge for the ones that matter. I proposed **symptom-based, SLO-burn-rate alerting** (Q24) instead — alert on user-facing impact, not every internal error — and committed to a weekly alert-quality review so the VP could see actionability trending up."

**Result:** "We shipped the exemplar workflow in a sprint; support adoption was immediate and they stopped filing 'add a metric' tickets. Page volume dropped ~40% while we measurably *caught* incidents faster via burn-rate alerts. The VP became an advocate for the SLO approach in other orgs. The lasting lesson I carry: stakeholders ask for *mechanisms* ('a metric', 'an alert') when what they actually want is an *outcome* ('see which customer is affected', 'never miss an incident') — your job as the expert is to satisfy the outcome with the right mechanism and bring data, not dogma, to the disagreement."

What the interviewer listens for: did you separate the stated request from the real need, did you use **data** to argue rather than authority, did you offer a concrete alternative that delivered the outcome, and did the relationship survive — staff engineers influence without a "no."

#### Q115. [Theory] Design a multi-tenant Prometheus/Mimir platform's tenant isolation, limits, and fairness. What breaks without it, and what knobs enforce it?

In a shared metrics platform, the failure mode that dominates is the **noisy neighbor**: one team's cardinality explosion or query storm degrades or OOMs the platform for everyone. Without isolation, a single `remote_write` from a misbehaving Prometheus pushing 10M new series, or one analyst running `rate({__name__=~".+"}[30d])`, can saturate ingesters or queriers and cause a platform-wide incident. Multi-tenancy via the `X-Scope-OrgID` header (Q21) is necessary but not sufficient — it separates *data*, not *resource consumption*.

The enforcement knobs fall into three planes. **Ingest limits** (per tenant): `max_global_series_per_user` (the hard cardinality cap — the single most important limit), `ingestion_rate` and `ingestion_burst_size` (samples/sec, token-bucket), `max_label_names_per_series`, `max_series_per_metric`. These are what stop a cardinality bomb at the door rather than after it has already consumed RAM. **Query limits**: `max_fetched_series_per_query`, `max_fetched_chunk_bytes_per_query`, `max_query_lookback`, `max_query_parallelism`, and per-tenant query-frontend queue priorities so one tenant's expensive range query can't starve others. **Storage/retention limits**: per-tenant `compactor_blocks_retention_period` so tenants can have different retention classes.

```
                 X-Scope-OrgID: team-payments
remote_write ----------------------------------> [ Distributor ]
                                                       | enforces ingestion_rate,
                                                       | max_global_series_per_user
                                                       v
                                                  [ Ingesters ]   <- per-tenant series cap
query (OrgID) -> [ Query Frontend ] -- queue, per-tenant parallelism, fairness -->
                                                  [ Queriers ] <- max_fetched_series
```

The fairness layer is the subtle expert point: limits alone create *hard* failures (tenant gets rejected), but you also want *soft* fairness so a tenant using spare capacity isn't throttled when no one else needs it. Mimir's query-frontend implements **per-tenant queuing with shuffle-sharding**, which limits the blast radius of a noisy tenant to a subset of ingesters/queriers rather than the whole fleet — so a single bad tenant degrades a few neighbors, not everyone. The governance wrapper (Q60) makes these limits *visible*: dashboards of each tenant's series count vs. their cap, alerts at 80% of the cap, and a chargeback/showback report so cost is attributed to the team that creates it. Without limits you get correlated platform outages; with limits but no fairness you get unnecessary rejections; with both plus showback you get a self-regulating platform where teams police their own cardinality because they see (and are billed for) it.

#### Q116. [Practical] Production incident: `remote_write` to Mimir is lagging and the WAL is growing on disk. Walk through diagnosis and remediation.

A growing WAL with lagging `remote_write` means Prometheus is ingesting samples faster than it can ship them — the WAL is the buffer, and if the remote stays slow longer than the WAL can hold, you **permanently lose** samples (Q21). The clock is ticking, so the investigation must be fast and ordered. First confirm the symptom and its direction with the remote-write health metrics (Q94): `prometheus_remote_storage_samples_pending` (backlog), `prometheus_remote_storage_highest_timestamp_in_seconds - prometheus_remote_storage_queue_highest_sent_timestamp_seconds` (the **lag in seconds** — the number that matters), `prometheus_remote_storage_shards` vs `prometheus_remote_storage_shards_max` (is it already maxed out?), and `prometheus_remote_storage_samples_failed_total` / `prometheus_remote_storage_samples_dropped_total`.

The lag points to one of three root causes. (1) **The remote is slow or erroring** — check `prometheus_remote_storage_samples_failed_total` rate and the HTTP response codes; a Mimir distributor that's overloaded, rate-limiting (429s) the tenant, or rejecting on a per-tenant series limit (Q115) will back everything up. Here the fix is on the *Mimir* side: raise the tenant's `ingestion_rate`, scale distributors/ingesters, or fix the limit rejection. (2) **The queue is misconfigured** — `max_shards` too low caps parallelism; raise it (and `capacity`, `max_samples_per_send`) so Prometheus can fan out more concurrent requests. (3) **A traffic/cardinality surge** at the source overwhelmed a correctly-sized pipeline — the real fix is dropping the offending series with `write_relabel_configs` (Q21) to shed load.

```promql
# The single most important number: how far behind is remote_write, in seconds?
(
  prometheus_remote_storage_highest_timestamp_in_seconds
  - on(remote_name, url)
  prometheus_remote_storage_queue_highest_sent_timestamp_seconds
)

# Are we shard-saturated (can't parallelize more)?
prometheus_remote_storage_shards == prometheus_remote_storage_shards_max

# Is the remote actively rejecting (vs just slow)?
rate(prometheus_remote_storage_samples_failed_total[5m]) > 0
```

**Triage order under pressure:** (a) if it's *rejection* (429/4xx), it's a limits/capacity problem on Mimir — fix there, because raising shards just sends more rejected requests; (b) if it's *slowness* with shards maxed, scale the receiver and/or raise queue limits; (c) if the WAL is about to exceed retention and you're losing data imminently, the emergency lever is to **shed load** via `write_relabel_configs drop` on low-value metrics to get under the throughput ceiling, then restore them once the backlog clears. **The systemic fix** afterward: alert on the lag-in-seconds metric (not just on failures) so you catch creeping lag before the WAL fills, and capacity-plan `remote_write` throughput against peak ingest, not average.

#### Q117. [Coding] Write a query and rule to alert on certificate expiry and on clock skew across the fleet — two "silent until catastrophic" failures.

**Problem:** TLS certs expiring and NTP clock drift both cause sudden, total, surprising outages. Write the PromQL to alert on both, ahead of time.

```yaml
groups:
  - name: silent-killers
    rules:
      # 1. TLS cert expiry — blackbox_exporter exposes the not-after timestamp.
      #    Alert 14 days out (warning) and 3 days out (page).
      - alert: CertExpiringSoon
        expr: (probe_ssl_earliest_cert_expiry - time()) / 86400 < 14
        for: 1h
        labels: { severity: warning }
        annotations:
          summary: "Cert for {{ $labels.instance }} expires in {{ $value | humanize }} days"

      - alert: CertExpiringCritical
        expr: (probe_ssl_earliest_cert_expiry - time()) / 86400 < 3
        for: 10m
        labels: { severity: page }

      # 2. Clock skew — node_exporter timex / ntp metrics. Skew breaks TLS,
      #    auth tokens (JWT exp/nbf), and makes PromQL timestamps lie.
      - alert: ClockSkewDetected
        expr: abs(node_timex_offset_seconds) > 0.05
        for: 5m
        labels: { severity: warning }
        annotations:
          summary: "Clock skew {{ $value }}s on {{ $labels.instance }}"

      # 2b. NTP not synchronized at all (status bit) — worse than mere offset.
      - alert: NTPUnsynchronized
        expr: node_timex_sync_status == 0
        for: 10m
        labels: { severity: warning }
```

Both classes share a profile interviewers love: **the metric is boring and green right up until the moment it causes a hard, total outage**, so you must alert on the *trend toward* the cliff, not the cliff itself. For certs, `probe_ssl_earliest_cert_expiry` (from blackbox_exporter, Q72) is an absolute Unix timestamp; `(expiry - time()) / 86400` converts to days remaining, and you tier the alert (14d warning to open a ticket, 3d page because automation has clearly failed). For time, `node_timex_offset_seconds` is the estimated offset from the reference clock — skew above ~50ms starts breaking mutual TLS (cert validity windows), JWT/OAuth token `nbf`/`exp` checks, distributed-lock leases, and even makes your *own* Prometheus timestamps unreliable, which corrupts every other alert.

**Edge cases:** for certs, `probe_ssl_earliest_cert_expiry` reports the *earliest* expiry in the chain, so an expiring intermediate is caught too; make sure the blackbox probe targets the real public endpoint (SNI matters) and not just the origin. For clock skew, `node_timex_sync_status == 0` (NTP daemon reports unsynchronized) is a stronger signal than offset alone — a host can show a small offset while having silently stopped syncing, so it'll drift unbounded later; alert on both the offset *and* the sync status. The meta-lesson: maintain a catalogue of "silent-killer" expiries (certs, tokens, license keys, credit-card-on-file for cloud accounts, domain registration) and put each on a forecast/threshold alert, because none of them generate error traffic before they fail.

#### Q118. [Theory] You must reduce your observability bill by 50% without losing the ability to debug incidents. Present a prioritized, defensible plan.

The framing that signals seniority: **cost is dominated by cardinality (active series) and, secondarily, by retention/resolution and query compute — not by "having too many dashboards."** So the plan attacks the dominant lever first and protects debuggability by cutting *unused* and *low-value* data, never the data you actually reach for during incidents. I'd present it as a ranked, measured program, each step with a "how we know it's safe" guardrail.

**1. Audit and drop unused metrics (biggest, safest win).** Most platforms ship 30–50% of series that *no dashboard or alert ever queries*. Use Grafana's datasource usage / Mimir's `cardinality` and "active series since last queried" data to find metrics with zero query hits over 30–90 days, and drop them at `write_relabel_configs` (Q21) or `metric_relabel_configs`. This is the safest cut because by definition nobody is using it; teams routinely see 30–50% reduction here alone, and it's reversible (re-enable the relabel rule).

**2. Kill cardinality bombs, not whole metrics.** Find high-cardinality *labels* (Q106) and drop the offending label rather than the metric — strip a `pod`/`instance` label on a metric you only ever view aggregated, drop debug labels, bucket high-cardinality string labels. Cutting one bad label can collapse a metric from millions of series to thousands without losing any dimension anyone queries.

**3. Tier retention and downsample.** Full 15s resolution for ~14 days covers incident debugging; 5m resolution for 90 days and 1h for 13 months covers capacity planning and SLO trend reporting (Q31, Q91). Nobody debugs a live incident with 8-month-old second-level data, so downsampling old data is debuggability-neutral. Drive this with per-metric retention classes.

**4. Reduce scrape frequency on fat, slow-moving targets.** A 60s scrape instead of 15s on infrastructure metrics that barely move quarters the sample volume for those targets with negligible fidelity loss; keep 15s only where latency SLOs need it.

**5. Cut query compute.** Move repeated heavy dashboard aggregations into recording rules (one cheap series read instead of millions re-scanned per refresh), and cap runaway queries with per-tenant limits (Q115).

```
Cost lever          | Typical share | Safety of cutting
--------------------+---------------+--------------------------------
Unused metrics      |   high        | very safe (nobody queries them)
High-card labels    |   high        | safe (keep dims you actually use)
Retention/resolution|   medium      | safe if tiered (old != hi-res)
Scrape frequency    |   medium      | safe on slow-moving targets
Query compute       |   medium      | safe (recording rules + limits)
```

The discipline I'd commit to: **measure before and after each step** (series count, bytes ingested, query cost) so the 50% is provable, attribute cost back to teams via showback so the reductions stick, and add a CI cardinality gate (Q88) so the bill doesn't silently re-inflate. The one thing I would *not* do is blanket-shorten retention or rip out dashboards to hit the number — that trades a real debugging capability for savings that the cardinality audit would have delivered more safely. Framing it this way shows the interviewer you optimize cost as an engineering problem with guardrails, not as an across-the-board austerity cut that bites you during the next incident.

#### Q119. [Coding] Expose a metric for a short-lived batch job correctly via the Pushgateway, and clean it up.

**Problem:** A nightly cron job computes a backup and finishes in seconds — far faster than any scrape (Q3). Record its success and duration so you can alert if it stops running, using the Pushgateway, the one sanctioned exception to the pull model.

```bash
#!/usr/bin/env bash
set -euo pipefail
PGW="http://pushgateway:9091"
JOB="nightly_backup"
INSTANCE="$(hostname)"

start=$(date +%s)
# ... do the backup work ...
if ./run_backup.sh; then success=1; else success=0; fi
end=$(date +%s)

# Push as a grouping keyed by job+instance. Note the Prometheus text format on stdin.
cat <<EOF | curl -s --data-binary @- "$PGW/metrics/job/$JOB/instance/$INSTANCE"
# TYPE backup_last_success_timestamp_seconds gauge
backup_last_success_timestamp_seconds $end
# TYPE backup_duration_seconds gauge
backup_duration_seconds $((end - start))
# TYPE backup_last_success gauge
backup_last_success $success
EOF
```

```yaml
# Alert: the backup hasn't succeeded in over 26 hours (cron is daily).
- alert: BackupStale
  expr: time() - backup_last_success_timestamp_seconds{job="nightly_backup"} > 26 * 3600
  for: 10m
  labels: { severity: page }
```

The decisive design choice is **what to push**: never push a "backup succeeded" *counter* you increment, because the Pushgateway is a dumb cache that holds the last value forever — push the **timestamp of last success** as a gauge, then alert on `time() - that_timestamp` exceeding the schedule period. This pattern detects *both* failure (the success bit) *and* the job silently not running at all (the timestamp goes stale), which a plain counter cannot. Crucially, you must disable `honor_timestamps`/scrape staleness assumptions are irrelevant here because the Pushgateway re-exposes whatever you last pushed.

**The sharp edges (Q42):** the Pushgateway does **not** expire metrics — if a job is decommissioned, its series lingers forever and will keep satisfying (or falsely clearing) alerts, so you must `DELETE` the group when retiring a job: `curl -X DELETE "$PGW/metrics/job/$JOB/instance/$INSTANCE"`. Also, the `up` metric for the *gateway* tells you the gateway is alive, not whether your job ran — that's exactly why you alert on the freshness of the pushed timestamp. Never use the Pushgateway for service-level metrics (it becomes a single point of failure and breaks per-instance health); it is only for ephemeral batch/cron jobs.

#### Q120. [Coding] Write a PromQL query to detect an anomaly using z-score against a rolling baseline, without external ML.

**Problem:** Flag when current traffic deviates sharply from its own recent norm, using only PromQL — a lightweight statistical anomaly detector for a metric with no fixed threshold.

```promql
# z-score = (current - rolling_mean) / rolling_stddev, over a 1h baseline.
(
  sum(rate(http_requests_total[5m]))
  -
  avg_over_time( sum(rate(http_requests_total[5m]))[1h:1m] )
)
/
clamp_min(
  stddev_over_time( sum(rate(http_requests_total[5m]))[1h:1m] ),
  1                                  # floor stddev to avoid divide-by-zero on flat signals
)
```

```yaml
# Alert when traffic is more than 3 standard deviations from its 1h baseline.
- alert: TrafficAnomaly
  expr: abs( <the z-score expression above> ) > 3
  for: 5m
  labels: { severity: warning }
```

This computes a **z-score** entirely in PromQL using a **subquery**: `[1h:1m]` evaluates the inner `sum(rate(...))` at 1-minute steps across the last hour, producing a range vector that `avg_over_time` and `stddev_over_time` reduce to a rolling mean and standard deviation. Subtracting the mean and dividing by the stddev expresses "how many standard deviations is *right now* away from the recent normal" — a threshold-free detector that adapts to whatever traffic level the service naturally runs at, so it works for a service doing 10 req/s and one doing 100k req/s without retuning.

**Edge cases and honest limits:** z-score assumes a roughly stationary, normal-ish distribution over the baseline window, so it produces false positives across **regime changes** (a deploy, a daily ramp, a seasonal boundary) — the baseline lags reality and flags the transition itself. The `clamp_min(stddev, 1)` guard prevents a division blow-up when the signal has been perfectly flat (stddev 0). Subqueries are expensive (Q108), so back this with a recording rule for the inner `sum(rate(...))` and keep the baseline window modest. For production-grade seasonality you'd move to `holt_winters`/`double_exponential_smoothing` (Q54) or Grafana ML / Mimir's metamonitoring, but the z-score trick is the right answer when someone asks for "anomaly detection without standing up an ML stack."

#### Q121. [Coding] Write a query to calculate Apdex score, and explain why a business might prefer it over a raw percentile.

**Problem:** Compute an **Apdex** (Application Performance Index) from a latency histogram, with a satisfied threshold of 300ms and a tolerated threshold of 1.2s (4× T).

```promql
# Apdex = (satisfied + tolerated/2) / total
# satisfied  = requests <= T (300ms)              -> the le="0.3" cumulative bucket
# tolerated  = requests <= 4T (1.2s)              -> the le="1.2" cumulative bucket
(
  sum(rate(http_request_duration_seconds_bucket{le="0.3"}[5m]))
  +
  sum(rate(http_request_duration_seconds_bucket{le="1.2"}[5m]))
) / 2
/
sum(rate(http_request_duration_seconds_count[5m]))
```

Apdex collapses a whole latency distribution into a single 0–1 score by classifying every request as **satisfied** (≤ T), **tolerating** (≤ 4T), or **frustrated** (> 4T), then scoring satisfied as 1, tolerating as 0.5, and frustrated as 0. The PromQL exploits the fact that histogram `_bucket` series are **cumulative**: `le="0.3"` already counts all requests ≤ 300ms (the satisfied set), and `le="1.2"` counts all ≤ 1.2s, which *includes* the satisfied ones — so `(satisfied + tolerated)/2` with the cumulative buckets exactly yields `satisfied*1 + (tolerated_only)*0.5` after the algebra, matching the Apdex definition. The denominator `_count` is the total observation count.

**Why a business might prefer Apdex to p99:** a percentile answers "how slow is the slow tail" but a stakeholder often wants "what fraction of users are having a good vs. bad experience," and Apdex maps directly to that on a single, comparable 0–1 axis where you can set an org-wide target (e.g. "Apdex ≥ 0.9"). It also degrades gracefully — a few frustrated requests nudge the score rather than abruptly flipping a percentile threshold. **Edge cases:** the bucket boundaries you chose at instrumentation time must include exactly T and 4T, or you can only approximate Apdex (you'd interpolate, losing precision) — this is a strong argument for **native histograms** where `histogram_fraction` (Q112) computes the satisfied/tolerated fractions at any threshold without pre-chosen buckets. Apdex is also blind to *which* users are frustrated, so pair it with per-slice SLIs (Q32) so a localized collapse isn't averaged away.

#### Q122. [Theory] Explain how Prometheus `relabel_configs` actions differ (`replace`, `keep`, `drop`, `labelmap`, `labeldrop`, `labelkeep`, `hashmod`, `keepequal`/`dropequal`) with a concrete use for each.

Relabeling (Q12) is a small rule engine, and the `action` field selects the operator. Interviewers probe whether you know the *full* set, not just `replace` and `keep`, because the right action makes a config one line instead of ten.

- **`replace`** (default): if `regex` matches the concatenated `source_labels`, write `replacement` (with `$1` back-refs) into `target_label`. The workhorse — e.g. build `__address__` from a discovered IP and annotated port (Q11).
- **`keep`**: drop the *target/series* unless `regex` matches `source_labels`. Use it to scrape only annotated pods (`keep` where `prometheus_io_scrape="true"`).
- **`drop`**: the inverse — discard targets/samples that match. Use it to skip a namespace or drop a noisy metric in `metric_relabel_configs`.
- **`labelmap`**: copy labels whose *names* match `regex` into new names via `replacement`. The canonical use is promoting all Kubernetes pod labels (`__meta_kubernetes_pod_label_(.+)`) into real metric labels in one rule instead of one `replace` per label.
- **`labeldrop` / `labelkeep`**: drop (or keep only) labels whose *names* match `regex`. `labeldrop` is the cardinality-control tool (strip `user_id`, Q17); `labelkeep` whitelists an allowed label set.
- **`hashmod`**: set `target_label` to `hash(source_labels) % modulus` — the basis of horizontal **sharding** (Q25): compute a stable shard number then `keep` only this shard's value.
- **`keepequal` / `dropequal`** (newer): keep/drop targets where `source_labels` equals `target_label` — handy for comparing two meta-labels (e.g. keep only where the discovered port equals an annotated expected port) without crafting a regex.

```yaml
# labelmap: promote ALL pod labels at once (one rule, not N replace rules)
- action: labelmap
  regex: __meta_kubernetes_pod_label_(.+)
# labeldrop: collapse a cardinality bomb
- action: labeldrop
  regex: (user_id|request_id|session_id)
# hashmod: deterministic sharding across 4 Prometheis
- source_labels: [__address__]
  modulus: 4
  target_label: __tmp_shard
  action: hashmod
```

The mental model that ties them together: `replace`/`labelmap` *write* labels, `keep`/`drop` *filter series/targets on label values*, `labelkeep`/`labeldrop` *filter labels by name*, `hashmod` *computes a deterministic bucket*, and `keepequal`/`dropequal` *compare two labels*. **Edge cases:** rules run **in order** and each sees the result of the previous, so a `replace` that creates a label can be filtered by a later `keep`; an empty `separator` and the default `regex: (.*)` matter when concatenating multiple `source_labels`; and meta-labels prefixed `__` are dropped after `relabel_configs` finishes unless you copied them into a real label, which is why you must `replace` them into named labels before the scrape.

#### Q123. [Coding] Write a Grafana provisioning + alerting-as-code setup (datasource, dashboard provider, contact point, notification policy) as YAML files.

**Problem:** Stand up Grafana fully from config so nothing is clicked in the UI (Q70, Q105): a Prometheus datasource, a dashboard provider that loads JSON from disk, and a Grafana-managed alert routed to Slack.

```yaml
# /etc/grafana/provisioning/datasources/prometheus.yaml
apiVersion: 1
datasources:
  - name: Prometheus
    type: prometheus
    uid: DS_PROM                 # referenced by dashboards as ${DS_PROM}
    access: proxy
    url: http://prometheus:9090
    isDefault: true
    jsonData:
      httpMethod: POST           # POST avoids URL-length limits on big queries
      exemplarTraceIdDestinations:
        - name: traceID
          datasourceUid: tempo   # wires metric exemplars -> Tempo traces (Q93)
```

```yaml
# /etc/grafana/provisioning/dashboards/provider.yaml
apiVersion: 1
providers:
  - name: 'git-dashboards'
    type: file
    allowUiUpdates: false        # UI edits won't silently override Git (no snowflakes)
    updateIntervalSeconds: 30
    options:
      path: /var/lib/grafana/dashboards   # mount your JSON models here (Q105)
      foldersFromFilesStructure: true
```

```yaml
# /etc/grafana/provisioning/alerting/contactpoints.yaml
apiVersion: 1
contactPoints:
  - orgId: 1
    name: slack-oncall
    receivers:
      - uid: cp-slack-oncall
        type: slack
        settings:
          url: ${SLACK_WEBHOOK_URL}     # env-substituted, never hardcoded
---
# /etc/grafana/provisioning/alerting/policies.yaml
apiVersion: 1
policies:
  - orgId: 1
    receiver: slack-oncall
    group_by: ['alertname', 'service']
    routes:
      - receiver: slack-oncall
        object_matchers: [['severity', '=', 'page']]
        group_wait: 0s
```

The architectural point is that **everything in Grafana that matters can be a file under `/etc/grafana/provisioning/`** — datasources, dashboards, contact points, notification policies, and alert rules — so the whole observability surface is version-controlled, code-reviewed, and reproducible across environments. `allowUiUpdates: false` is the anti-snowflake guard: it prevents an engineer's quick UI tweak from diverging from Git (the file is the source of truth and overwrites on the next reload). The datasource's `exemplarTraceIdDestinations` is the small but high-value wiring that turns a latency spike into a one-click jump to a Tempo trace (Q79, Q93).

**Edge cases:** provisioned dashboards get a "provisioned" badge and can't be deleted from the UI (only by removing the file), which is intentional; secrets like `${SLACK_WEBHOOK_URL}` should come from environment variables or a secret store, not be committed; and Grafana-managed alerting (this YAML) is distinct from Prometheus-rule alerting (Q71) — use Grafana-managed when you want alerts that span multiple datasources or need Grafana's richer routing UI, and Prometheus rules when you want alerting that survives Grafana being down. The `uid` fields are load-bearing: they let dashboards and policies reference resources stably across rebuilds.

#### Q124. [Practical] A Grafana panel shows "No data" but the same query returns results in the Prometheus UI. Walk through every cause.

This is a classic "the data exists but Grafana can't see it" puzzle, and a systematic walk shows you understand the full query path (Q57), not just PromQL. The causes cluster into a few layers, and I'd check them roughly in this order.

**Time range and step.** Grafana queries `query_range` over the panel's window with a computed step (Q109), while the Prometheus UI usually runs an *instant* query at "now." If your data is older than the panel's time range, or the panel range is so wide that the step skips the only samples that exist, you get "No data" in Grafana while the instant query in the UI finds the latest point fine. Narrow the range, or check that the series has samples *within* the visible window.

**Variable interpolation.** The panel's PromQL almost certainly contains a template variable (`$service`, `$instance`). If the variable is empty, unselected, "All" expanding to a regex that matches nothing, or interpolated with the wrong format (`{instance="$instance"}` when `$instance` is multi-value and needs `=~`), the rendered query has no matches. Open the panel's **Query Inspector** and read the *actual* interpolated query Grafana sent — this single step resolves most of these incidents because the query you wrote is not the query that ran.

**Datasource mismatch.** The panel may point at a *different* Prometheus/datasource UID than the one you tested in (a templated `${DS_PROM}` resolving to staging, or a dashboard imported with the wrong datasource). Verify the panel's datasource and run the inspected query against *that* exact endpoint.

```
Panel "No data"  ->  open Query Inspector  ->  read the real request:
  - time range / step:  &start=...&end=...&step=...   (samples in window?)
  - interpolated expr:  was $service substituted to something that matches?
  - datasource URL:     hitting the Prometheus you think it is?
```

**Other causes:** a transformation that filters everything out (a "Filter by value" or a field-name mismatch after a rename), an authentication/proxy error (Grafana's request 401/403s where your browser session in the Prometheus UI was authenticated), a panel "instant" toggle vs range mismatch, or unit/field-config hiding the series. **The fix discipline:** always start from the **Query Inspector's actual request**, because "No data in Grafana but data in Prometheus" almost always means the two are sending *different queries* — different time semantics, different interpolated labels, or a different datasource — and the inspector makes that difference visible instead of guessed.

#### Q125. [Theory] Design observability for a serverless / ephemeral workload (Lambda, Cloud Run, short-lived functions) where pull-based scraping fundamentally doesn't fit.

The core conflict: Prometheus's pull model (Q3) assumes a long-lived target with a stable `/metrics` endpoint that exists long enough to be scraped, but a Lambda may live 200ms and a Cloud Run instance may scale to zero — there is often *nothing to scrape* by the time Prometheus comes knocking. So the architecture must invert to **push** at the edge while keeping Prometheus/Mimir as the query and alerting brain. There are three viable patterns, in increasing maturity.

**Pattern 1 — Pushgateway for true batch invocations (Q119):** acceptable only for scheduled/batch-style functions, and it carries the Pushgateway's caveats (no expiry, single point of failure, must DELETE retired groups). It does *not* scale to high-frequency, high-concurrency function invocations — you'd get write contention and stale aggregates. Use it for a nightly serverless ETL, not a per-request API.

**Pattern 2 — OpenTelemetry push to a collector (the right default in 2026):** instrument functions with the OTel SDK and **push** metrics (OTLP) to an **OpenTelemetry Collector** (or Grafana Alloy) running as a long-lived gateway, which aggregates and then `remote_write`s to Prometheus/Mimir (Q56, Q84). This fits the ephemeral model perfectly: the function emits and dies, the collector persists and exposes/pushes to the durable store. It also unifies metrics, traces, and logs from the same SDK, which matters because for short-lived functions a **trace** is often more informative than a metric.

**Pattern 3 — Provider-native metrics bridged in:** cloud providers already emit Lambda/Cloud Run metrics (invocations, duration, errors, concurrency, cold starts) to CloudWatch / Cloud Monitoring. A `cloudwatch_exporter` / `stackdriver_exporter` or the OTel cloud receivers pull those into Prometheus, giving you platform-level signals without instrumenting code — best combined with Pattern 2 for application-level custom metrics.

```
Ephemeral function --emit OTLP--> [ OTel Collector / Alloy ] --remote_write--> [ Mimir ]
   (lives 200ms)                     (long-lived gateway,                       (durable,
                                       aggregates + buffers)                     queryable)
Provider metrics  --CloudWatch--> [ cloudwatch_exporter ] --scrape--> Prometheus
```

The expert nuances: **cardinality is even more dangerous here** because a naive instance/invocation-id label explodes instantly across millions of short invocations — aggregate at the collector and never label per-invocation. **Cold starts** are a serverless-specific golden signal worth a dedicated histogram (init duration), since they dominate tail latency. **Aggregation must happen before the function dies** — you can't compute a fleet rate from instances that no longer exist, so the collector (or provider metrics) owns aggregation, not the function. And **delta vs cumulative temporality**: OTel functions often emit *delta* metrics (this invocation's count) whereas Prometheus expects *cumulative* counters, so the collector must convert delta→cumulative (the `deltatocumulative` processor) or you'll break `rate()`. The summary I'd give: keep Prometheus/Mimir as the durable query/alert layer, but move the *collection* edge to OTel push through a long-lived collector, treat traces as first-class for short-lived units, and lean on provider-native metrics for the platform signals you can't instrument.

#### Q126. [Coding] Write a PromQL query and recording rules to compute availability over a rolling 30-day window for an SLO report, handling no-traffic gaps correctly.

**Problem:** Leadership wants a monthly "we were 99.95% available" number per service, computed correctly so that periods of *no traffic* neither count as 100% available nor as an outage.

```yaml
groups:
  - name: slo-availability
    interval: 1m
    rules:
      # Numerator: good requests (non-5xx) per service, as a rate (req/s).
      - record: service:requests_good:rate5m
        expr: sum by (service) (rate(http_requests_total{status!~"5.."}[5m]))
      # Denominator: total requests per service.
      - record: service:requests_total:rate5m
        expr: sum by (service) (rate(http_requests_total[5m]))
```

```promql
# 30-day availability = total GOOD events / total events over the window.
# Using the rates' integrals (avg_over_time * window cancels), or more directly,
# the ratio of summed good to summed total over 30d:
sum_over_time(service:requests_good:rate5m[30d])
/
sum_over_time(service:requests_total:rate5m[30d])

# Error budget remaining for a 99.95% SLO (fraction of budget left):
1 - (
  (1 - (
     sum_over_time(service:requests_good:rate5m[30d])
     / sum_over_time(service:requests_total:rate5m[30d])
  ))
  / (1 - 0.9995)
)
```

The correctness subtlety that separates a real SLO report from a naive one is **how you treat windows with no traffic**. If you average per-scrape availability ratios (`avg_over_time(good/total)`), a 5-minute window with zero requests produces `0/0 = NaN`, and depending on how you patch it you either drop the window (silently shrinking your measurement period) or inject a misleading 100%. The **event-ratio** formulation above — total good events divided by total events over the whole 30 days — is correct because a no-traffic window contributes **0 to both numerator and denominator**, so it simply doesn't affect the ratio rather than distorting it. This matches how users actually experience availability: a minute with no requests is neither a success nor a failure, it's a non-event.

**Why recording rules and not a raw 30-day query:** `sum_over_time(...[30d])` over raw `rate()` of a high-cardinality counter is brutally expensive and may time out (Q110); precomputing the per-service `good` and `total` rates at 1-minute resolution turns the 30-day report into a sum over a tiny pre-aggregated series. **Edge cases:** events-based SLIs (this) measure *what fraction of requests succeeded*, while time-based SLIs measure *what fraction of minutes were healthy* — pick deliberately and state which the 99.95% refers to, because they diverge sharply for bursty traffic. For a multi-window burn-rate *alert* you'd still use the shorter-window rules (Q24); this 30-day query is the **reporting** view, and the two should derive from the *same* recording-rule SLIs so the dashboard, the alert, and the leadership report can never disagree about the number.

## ✅ Key Takeaways

- Prometheus is **pull-based**, **label-multidimensional**, and **standalone-reliable**; it favors availability over perfect consistency.
- Learn the metric types cold: **counter → always `rate()`**, **gauge → read directly**, **histogram → aggregatable server-side quantiles**, **summary → client-side quantiles you cannot aggregate**.
- **Cardinality is the #1 operational risk and the #1 cost lever.** Never put unbounded identifiers (user_id, request_id) in labels.
- Master core PromQL: `rate`/`irate`/`increase`, `sum by`, `histogram_quantile`, and the `level:metric:operation` recording-rule convention.
- **Recording rules** precompute expensive expressions; **alerting rules + `for`** prevent flapping; **Alertmanager** dedupes, groups, routes, inhibits, and silences.
- Build dashboards with **template variables** and `$__rate_interval`; monitor the **four golden signals** (latency, traffic, errors, saturation).
- Scale path: functional sharding → hashmod sharding → **Mimir/Cortex (push) or Thanos (augment)** for HA, global view, and long-term storage.
- Adopt **SLOs with multi-window, multi-burn-rate alerting** to align engineering with business reliability targets and reduce alert noise.
- In 2026, embrace **native histograms**, **exemplars**, and **OpenTelemetry** convergence for unified, high-fidelity, vendor-neutral observability.

## ⚠️ Common Pitfalls

- Using raw counter values or computing counter deltas manually instead of `rate()`/`increase()` (breaks on resets).
- Putting high-cardinality labels (IDs, emails, full URLs, timestamps) into metrics, causing OOMs and slow queries.
- Using **summaries** then trying to aggregate quantiles across instances — mathematically invalid; use **histograms**.
- Hardcoding `[5m]` ranges instead of `$__rate_interval`, causing gaps when users zoom out or scrape interval changes.
- Treating **federation** as a way to copy *all* raw series to a central server — it's for aggregates only.
- Forgetting a divide-by-zero guard in ratio-based alerts (no traffic → `NaN`).
- Confusing `relabel_configs` (pre-scrape, target selection) with `metric_relabel_configs` (post-scrape, sample filtering).
- Setting overly aggressive `for`/`group_wait` on critical alerts (late paging) or too little (flapping); not tiering timing by severity.
- No **dead-man's-switch / Watchdog** alert, so a broken alerting pipeline goes unnoticed.
- Alerting only on **global aggregates**, masking localized per-region/per-tenant failures.
- Inlining credentials in config files and exposing Prometheus/Alertmanager/Grafana without authn — they ship insecure by default.

## 📚 Further Reading

- *Prometheus: Up & Running* (2nd ed.), Brian Brazil & Julien Pivotto — the definitive book, updated for native histograms and OTLP.
- *Site Reliability Engineering* and *The Site Reliability Workbook*, Google — golden signals, SLOs, error budgets, and multi-burn-rate alerting.
- Official Prometheus documentation — [https://prometheus.io/docs/](https://prometheus.io/docs/) (PromQL, storage, configuration, best practices on naming & cardinality).
- Grafana Mimir & Grafana documentation — [https://grafana.com/docs/](https://grafana.com/docs/) (Mimir architecture, dashboards, variables, Alloy).
- Thanos documentation — [https://thanos.io/](https://thanos.io/) (sidecar, store gateway, compactor, deduplication).
- *Observability Engineering*, Charity Majors, Liz Fong-Jones & George Miranda (O'Reilly) — metrics in the broader metrics/logs/traces context.
