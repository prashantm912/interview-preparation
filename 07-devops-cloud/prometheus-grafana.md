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
