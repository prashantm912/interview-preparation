# ML System Design & MLOps

A staff-engineer-level interview guide to designing and operating machine-learning systems in production: the training/serving split, feature stores, model registries and versioning, batch vs real-time inference, modern serving runtimes (TorchServe, Triton, vLLM, KServe, Ray Serve), safe rollout (A/B, canary, shadow), drift detection, monitoring, retraining pipelines, GPU scaling, and the latency/throughput trade-offs that dominate cost. Current through 2026, including LLM-serving and agentic-workload realities.

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

### Q1. [Theory] What is the difference between the training and serving sides of an ML system, and why are they usually split?

Training is an **offline, batch, throughput-oriented** workload: you read large historical datasets, iterate over many epochs, and the only thing that matters is producing a good model artifact — minutes or hours of latency are fine. Serving (inference) is an **online, latency-oriented** workload: a request arrives, you must produce a prediction within a tight SLA (often p99 in the tens of milliseconds), under concurrency, with high availability. These two workloads have almost opposite resource and reliability profiles, which is why they are deployed as separate systems.

The split matters because the failure modes differ. A training job that crashes can simply be retried; a serving endpoint that crashes drops live user traffic. Training scales by adding GPUs to one big job; serving scales horizontally by replicating small stateless workers behind a load balancer. Training reads from a data lake/warehouse; serving reads from a low-latency online store. Conflating them — e.g., running inference on the training cluster — couples your user-facing latency to batch-job scheduling, which is a classic outage source.

```
 OFFLINE (training)                      ONLINE (serving)
 ┌────────────────────┐                  ┌─────────────────────┐
 │ Data lake / warehouse                 │ Request → features  │
 │ Feature pipelines (batch)             │ Load model artifact │
 │ Train → evaluate → register           │ Predict within SLA  │
 │ Throughput-bound, retryable           │ Latency-bound, HA   │
 └────────────────────┘                  └─────────────────────┘
        artifact + features flow right →
```

The deep point an interviewer wants: the **boundary between the two is where most production bugs live** — training/serving skew. If features are computed one way in the training pipeline and another way at serving time, the model sees a different distribution than it was trained on and silently degrades. Feature stores and shared transformation code exist precisely to close that gap.

### Q2. [Theory] What is training/serving skew and what are its common causes?

Training/serving skew is any systematic difference between the data a model saw during training and the data it sees at inference, causing the live model to underperform its offline evaluation. Because it is silent — the model still returns predictions, just worse ones — it is one of the hardest production ML bugs to catch.

The common causes are: (1) **feature-computation skew** — the training pipeline computes a feature in Spark/SQL while the serving path recomputes it in application code, and the two implementations diverge (different rounding, time-zone handling, null defaults); (2) **time-travel / label leakage** — the training pipeline accidentally uses information that would not be available at prediction time; (3) **distribution shift between offline snapshot and live traffic** — you trained on last quarter's data but the world changed; and (4) **serving-time data freshness** — a feature is hours stale online but was point-in-time-correct offline.

The mitigation an interviewer is listening for is *share the code path*: compute features once and write them to both the offline store (for training) and the online store (for serving) from the **same transformation logic**, and log the exact feature vector served so you can compare it later. This is the central value proposition of a feature store.

### Q3. [Theory] What is a model registry and why not just store models in a folder or S3 bucket?

A model registry is a system of record for trained model **artifacts and their metadata**: versions, the training run that produced them, the dataset and code commit, evaluation metrics, and a lifecycle stage (Staging → Production → Archived). MLflow Model Registry, SageMaker Model Registry, and Vertex AI Model Registry are common implementations. A raw S3 bucket gives you bytes but none of the governance.

The reason a folder is not enough is **reproducibility and auditability**. When a model misbehaves in production you must answer: which exact artifact is serving, which data and code produced it, what were its offline metrics, who promoted it, and can I roll back to the previous one instantly? A registry makes the model a first-class, versioned, promotable entity with an audit trail — the same way a container registry treats images. For regulated domains (credit, healthcare) this lineage is a hard compliance requirement, not a nicety.

```
 Training run ──► Registry
                  ├─ model:v1  metrics, dataset hash, git sha   [Archived]
                  ├─ model:v2  ...                              [Production]
                  └─ model:v3  ...                              [Staging]
 Serving pulls "Production" by alias, not by hardcoded version.
```

The practical win: serving references a **stage alias** ("Production") rather than a hardcoded version, so promotion and rollback are a registry metadata change, not a code deploy.

### Q4. [Theory] When would you choose batch inference over real-time inference?

Batch inference precomputes predictions on a schedule and stores them for later lookup; real-time (online) inference computes a prediction per request, synchronously. Choose batch when predictions do **not depend on just-arrived input** and can tolerate staleness measured in hours — e.g., nightly product recommendations, churn scores, credit-risk segments, or marketing propensity. Choose real-time when the prediction depends on the live request context (fraud check on *this* transaction, search ranking for *this* query, dynamic pricing).

The decision is fundamentally about **freshness vs cost and complexity**. Batch is dramatically cheaper and simpler: you run a Spark/Ray job, write results to a key-value store (Redis, DynamoDB, Bigtable), and serving is a sub-millisecond lookup with no model on the hot path. Real-time requires a highly available, low-latency model server, autoscaling, and careful tail-latency engineering — far more operational surface.

| Dimension | Batch | Real-time |
|---|---|---|
| Latency at serve | µs–ms (lookup) | ms–hundreds of ms (compute) |
| Freshness | Hours/day stale | Live |
| Cost | Low (scheduled) | High (always-on, GPUs) |
| Failure blast radius | Reprocess later | Drops live traffic |
| Good for | Recsys, scoring, segments | Fraud, ranking, ads, chat |

A common hybrid is **near-real-time / streaming inference** (predict on a Kafka/Flink stream as events arrive) which sits between the two, plus **request-time feature lookup against precomputed batch features** — batch features, online model.

### Q5. [Practical] Sketch a minimal real-time model-serving endpoint and the SLA concerns it must address.

A minimal endpoint loads the model once at startup, then serves predictions over HTTP/gRPC. The thing juniors miss is everything *around* `model.predict()`: input validation, feature fetching, timeouts, health checks, and metrics.

```python
from fastapi import FastAPI
from pydantic import BaseModel
import joblib, time
from prometheus_client import Histogram, Counter

app = FastAPI()
model = joblib.load("model.pkl")          # load ONCE, not per request
LAT = Histogram("predict_latency_seconds", "inference latency")
ERR = Counter("predict_errors_total", "inference errors")

class Req(BaseModel):
    features: list[float]

@app.get("/healthz")               # liveness/readiness for the orchestrator
def health():
    return {"status": "ok"}

@app.post("/predict")
def predict(r: Req):
    start = time.perf_counter()
    try:
        score = float(model.predict([r.features])[0])
        return {"score": score, "model_version": "v2"}
    except Exception:
        ERR.inc()
        raise
    finally:
        LAT.observe(time.perf_counter() - start)
```

The SLA concerns: **(1)** load the model once (cold-loading per request destroys latency); **(2)** expose a `/healthz` so Kubernetes only routes to ready replicas and restarts stuck ones; **(3)** emit latency/error metrics so you can alert on p99 and error rate; **(4)** always include the `model_version` in the response so you can attribute behavior during a rollout; and **(5)** set client and upstream **timeouts** so a slow prediction fails fast rather than piling up.

For real throughput you would not hand-roll this — you would reach for TorchServe/Triton/KServe — but being able to articulate the concerns shows you understand that serving is a systems problem, not a `model.predict()` call.

### Q6. [Theory] What is the difference between latency and throughput in inference, and why can't you maximize both?

Latency is how long a single request takes end-to-end (p50/p95/p99); throughput is how many requests per second the system completes. They trade off because the main lever for throughput on accelerators is **batching** — grouping multiple requests so the GPU does one large matrix multiply instead of many tiny ones — and batching *adds* latency because requests must wait to be grouped.

A single inference under-utilizes a GPU (the kernel launch and memory overhead dominate the tiny compute). Batch 32 requests and the GPU runs near peak FLOPs, so cost-per-prediction plummets and throughput soars — but each request now waits up to a "max batch delay" window to be collected. This is the central tuning knob of every serving runtime: **dynamic batching** with a `max_batch_size` and `max_queue_delay`.

```
 latency ▲
         │      ╱ as batch size ↑, per-request latency ↑
         │   ╱
         │ ╱
         └──────────────► throughput (req/s) rises with batch size
 Sweet spot: largest batch whose worst-case latency still meets the SLA.
```

The interview-grade answer: you do not "maximize both"; you fix the **latency SLA** as a constraint and then maximize throughput (minimize cost) subject to it — choose the largest batch and queue delay whose p99 still fits the SLA. For latency-critical paths you batch little or not at all; for cost-sensitive bulk scoring you batch aggressively.

### Q7. [Practical] How do you containerize and deploy a model so it runs reproducibly across environments?

Pin everything and bake the artifact into an immutable image so "works on my laptop" equals "works in prod." The two failure modes are dependency drift (a different NumPy/CUDA version changes numerics or breaks loading) and artifact drift (a different model file than you tested).

```dockerfile
FROM python:3.11-slim
WORKDIR /app
# pin exact versions; a lockfile is better than range specs
COPY requirements.lock .
RUN pip install --no-cache-dir -r requirements.lock
COPY serve.py model.pkl ./           # bake the exact artifact tested
EXPOSE 8080
# one worker per GPU/core; tune to the runtime, not arbitrarily
CMD ["uvicorn", "serve:app", "--host", "0.0.0.0", "--port", "8080"]
```

```bash
# build with a content-addressable tag tied to the model version + git sha
docker build -t registry.example.com/fraud-model:v2-$(git rev-parse --short HEAD) .
docker push registry.example.com/fraud-model:v2-...
```

The practices that matter: **(1)** a lockfile, not loose version ranges, so the dependency graph is reproducible; **(2)** the model artifact baked in (or pulled by an immutable digest from the registry at startup) so the running version is unambiguous; **(3)** a non-`latest` tag that encodes the model version and git sha for traceability; and **(4)** matching the CUDA/driver versions to the GPU runtime when serving on accelerators — a CUDA mismatch is the most common GPU-serving startup failure. In Kubernetes you then set resource requests/limits, a readiness probe on `/healthz`, and a rollout strategy.

### Q8. [Theory] What basic things must you monitor for a model in production, beyond standard service metrics?

Standard service monitoring (latency, error rate, CPU/GPU utilization, saturation) is necessary but not sufficient for ML, because a model can be perfectly *healthy* as a service while being *wrong*. You add three ML-specific layers: **operational**, **data-quality**, and **model-quality**.

Operational ML metrics include prediction latency at p99, throughput, GPU memory and utilization, and model load time. Data-quality metrics watch the *inputs*: feature null rates, out-of-range values, schema violations, and the distribution of each input feature versus a training baseline (input drift). Model-quality metrics watch the *outputs and outcomes*: the prediction distribution (are we suddenly predicting "fraud" 10x more often?), and — when labels eventually arrive — accuracy/AUC/precision-recall against ground truth.

```
 Service layer:  latency p99, error %, GPU util, saturation
 Data layer:     null %, range violations, feature drift (PSI/KS)
 Model layer:    prediction dist, score calibration, AUC vs labels
```

The key insight to verbalize: **ground-truth labels are usually delayed** (you learn whether a loan defaulted months later), so you cannot rely on accuracy alone for fast alerting. You monitor input drift and prediction drift as *leading indicators* of degradation, and reconcile against true labels as they arrive for the authoritative signal.

---

## 🟡 Intermediate (3–7 yrs)

### Q9. [Theory] What is a feature store and what specific problems does it solve?

A feature store is a data system that manages ML features across their lifecycle: it ingests/computes features, stores them in both an **offline store** (for generating training data with point-in-time correctness) and an **online store** (for low-latency lookup at serving), maintains a **registry** of feature definitions, and serves features consistently to both sides. Feast, Tecton, Databricks Feature Store, and Vertex/SageMaker Feature Store are common implementations.

It solves four distinct problems. **(1) Training/serving skew** — the same transformation writes to both stores, so the feature is computed once and consumed identically offline and online. **(2) Point-in-time correctness** — when building a training set you must join each label with the feature *values as they were at that timestamp*, not their current values, or you leak future information; the offline store does this "time-travel" join correctly. **(3) Reuse and governance** — features become discoverable, versioned, shared assets instead of being re-implemented per team. **(4) Online freshness** — the online store provides single-digit-millisecond lookups (Redis/DynamoDB/Bigtable) so serving does not recompute features on the hot path.

```
 Source data ─► Feature transforms (ONE definition)
                     │                     │
              Offline store          Online store
              (warehouse,            (Redis/DynamoDB,
               point-in-time)         ms lookups)
                     │                     │
              Training set            Serving lookup
```

The trade-off worth stating: a feature store adds real operational and conceptual overhead. For a small team with one model, shared transformation code plus a Redis cache may be enough. The investment pays off when **many models and teams share features** and skew bugs become expensive.

### Q10. [Theory] Explain point-in-time correctness and why a naive feature join causes label leakage.

Point-in-time correctness means that when you assemble training data, each training row joins its label to the feature values **as they existed at or before the label's event timestamp** — never feature values from the future relative to that event. A naive join (`SELECT * FROM labels JOIN features USING (entity_id)`) attaches the *latest* feature values, which for a historical label includes information that did not exist when the prediction would have been made. The model then learns from the future and looks brilliant offline and useless online.

Consider predicting churn on 2026-01-01 for a customer. A correct feature like "30-day spend" must be computed using only transactions up to 2026-01-01. A naive join might attach the customer's *current* 30-day spend (computed in June), which already reflects post-churn behavior — pure leakage. The offline store performs an **as-of join** (a.k.a. point-in-time join) keyed on entity and event timestamp to prevent this.

```sql
-- conceptual as-of join: for each label, take the latest feature row
-- whose timestamp is <= the label's event time (and within TTL)
SELECT l.entity_id, l.label, f.feature_value
FROM labels l
LEFT JOIN features f
  ON f.entity_id = l.entity_id
 AND f.event_ts <= l.event_ts                 -- no future leakage
 AND f.event_ts >  l.event_ts - INTERVAL '7 days'  -- freshness window
QUALIFY ROW_NUMBER() OVER (
  PARTITION BY l.entity_id, l.event_ts ORDER BY f.event_ts DESC) = 1;
```

This is the single most common and most damaging data bug in applied ML. The fact that feature stores do it for you is a primary reason they exist.

### Q11. [Practical] Compare TorchServe, Triton, vLLM, KServe, and Ray Serve. When do you reach for each?

These operate at different layers, which is why the comparison confuses people. **Triton Inference Server** and **TorchServe** are *model servers* — they load model artifacts and serve them with batching/concurrency. **vLLM** is a *specialized LLM inference engine*. **KServe** and **Ray Serve** are *serving frameworks/orchestrators* that can host the others and add autoscaling, routing, and composition.

```
 Orchestration / autoscale / canary:   KServe (on K8s) | Ray Serve
 Model server (multi-framework):        Triton (TF/PyTorch/ONNX/TensorRT)
 Model server (PyTorch-centric):        TorchServe
 LLM engine (high-throughput tokens):   vLLM (PagedAttention, cont. batching)
```

- **Triton**: best when you have heterogeneous models (PyTorch, TensorFlow, ONNX, TensorRT) and want one high-performance server with dynamic batching, concurrent model execution, and GPU sharing. The default choice for mixed CV/tabular fleets on NVIDIA GPUs.
- **TorchServe**: simpler, PyTorch-native; fine for PyTorch-only shops, though momentum has shifted toward Triton and vLLM.
- **vLLM**: the standard for serving LLMs. Its **PagedAttention** manages the KV cache like virtual memory (paging, no fragmentation) and **continuous batching** packs new requests into the running batch token-by-token, giving multiples of the throughput of naive serving. Reach for it for any chat/completion/embedding LLM workload; alternatives include TensorRT-LLM and SGLang.
- **KServe**: the Kubernetes-native serving standard (CRDs for `InferenceService`), giving you autoscaling (including scale-to-zero via Knative), canary rollout, and a standard inference protocol; it *hosts* Triton/TorchServe/vLLM as runtimes.
- **Ray Serve**: Python-native serving with first-class **model composition** (chain/branch multiple models and business logic in one deployment graph) and tight integration with Ray for batch + online on one cluster. Reach for it for complex multi-model pipelines and when your team lives in Python/Ray.

The staff-level framing: pick the *engine* by model type (vLLM for LLMs, Triton for the rest) and the *orchestrator* by platform (KServe if you are Kubernetes-standardized, Ray Serve if you need composition or already run Ray).

### Q12. [Theory] How does dynamic batching work in a serving runtime, and how do you tune it?

Dynamic (server-side) batching collects individual incoming requests into a batch on the server, runs one forward pass, then scatters the results back. It is invisible to clients — each still sends a single request — but the GPU processes them together for far higher throughput. The two knobs are `max_batch_size` (the cap) and `max_queue_delay`/`preferred_batch_size` (how long to wait gathering requests before firing).

The tuning is a direct latency/throughput trade. A larger `max_queue_delay` forms bigger batches (more throughput, lower cost) but adds that delay to every request's latency. You set the delay so that *queue wait + compute time* for the largest expected batch still meets your p99 SLA. Triton exposes this as the `dynamic_batching` config; for LLMs, **continuous batching** (vLLM) is a more advanced variant that adds/removes sequences from the in-flight batch at each decoding step rather than waiting for a whole batch to finish.

```text
# Triton config.pbtxt
dynamic_batching {
  preferred_batch_size: [ 8, 16, 32 ]
  max_queue_delay_microseconds: 2000   # 2 ms wait window
}
instance_group [ { count: 2, kind: KIND_GPU } ]   # 2 model instances/GPU
```

Practical guidance: start with a small `max_queue_delay` (1–5 ms) and increase it while watching p99; combine with multiple model *instances* per GPU to overlap compute and data transfer. For bursty traffic, batching is what lets you survive spikes without proportionally more GPUs.

### Q13. [Theory] Contrast canary, blue-green, A/B testing, and shadow deployment for models. What does each tell you?

These are different tools answering different questions, and conflating them is a common mistake.

| Strategy | Traffic to new model | Question it answers | Risk |
|---|---|---|---|
| **Blue-green** | 0% then 100% (instant switch) | "Does it work at all in prod?" | High (full cutover) |
| **Canary** | Small % (1→5→25→100) | "Is it operationally healthy?" | Low (gradual) |
| **A/B test** | Fixed split, randomized | "Is it *better* on business KPIs?" | Controlled |
| **Shadow** | Mirrored copy, 0% served | "How would it behave on real traffic, risk-free?" | None to users |

- **Blue-green** keeps two full environments and flips a router; great for instant rollback but it is an all-or-nothing operational switch, not a quality experiment.
- **Canary** ramps the new model to a growing fraction while you watch operational and guardrail metrics; it catches crashes, latency regressions, and gross errors before full exposure.
- **A/B testing** randomizes users into control vs treatment and measures **business outcomes** (conversion, revenue, click-through) with statistical significance. It is the only one of these that proves the new model is *better*, not merely *working*.
- **Shadow (dark launch)** sends a copy of real production requests to the new model, logs its predictions, but **discards them** — users still see the old model. This validates behavior, latency, and infra on real traffic with zero user risk. It cannot measure business lift (no outcomes are realized) but it is the safest pre-launch check, and essential when a bad prediction is costly (fraud, pricing).

A mature rollout chains them: **shadow → canary → A/B → full**.

### Q14. [Coding] Implement shadow (mirrored) inference that logs the candidate model's predictions without affecting the user response.

The contract of shadow mode is strict: the candidate must never add latency to or alter the user-facing path, and its failures must never affect the request. So you call the candidate **asynchronously, fire-and-forget**, after responding to the user (or in parallel with a hard isolation boundary).

```python
import asyncio, logging
from fastapi import FastAPI
from pydantic import BaseModel

app = FastAPI()
log = logging.getLogger("shadow")

prod_model = load_model("prod")          # serving the user
shadow_model = load_model("candidate")   # being evaluated, NOT served

class Req(BaseModel):
    request_id: str
    features: list[float]

async def shadow_call(req: Req, prod_score: float):
    """Runs out-of-band; isolated so it can never break the user path."""
    try:
        score = float(shadow_model.predict([req.features])[0])
        log.info("shadow", extra={"request_id": req.request_id,
                                  "prod": prod_score, "shadow": score})
    except Exception as e:                # swallow: shadow must not page anyone
        log.warning("shadow_failed: %s", e)

@app.post("/predict")
async def predict(req: Req):
    prod_score = float(prod_model.predict([req.features])[0])
    # schedule shadow work AFTER computing the real answer; do not await it
    asyncio.create_task(shadow_call(req, prod_score))
    return {"score": prod_score, "model_version": "prod"}   # user sees prod only
```

Two correctness details interviewers probe: **(1)** the shadow task is `create_task`'d and not awaited, so it cannot add latency or propagate exceptions to the user; **(2)** you log *both* scores keyed by `request_id` so an offline job can compute the candidate's agreement, prediction-distribution shift, and — once labels arrive — its true accuracy versus prod. In a high-volume system you would sample (e.g., shadow 5% of traffic) to bound the extra GPU cost, and run the shadow on a separate worker pool so it cannot steal capacity from production.

### Q15. [Coding] Compute Population Stability Index (PSI) to detect input/score drift, and explain the thresholds.

PSI quantifies how much a distribution has shifted between a baseline (training) and the current (live) data by binning both and summing a relative-entropy-like term over bins. It is the workhorse metric for feature and score drift because it is simple, interpretable, and works on a single feature or the model's score.

```python
import numpy as np

def psi(baseline: np.ndarray, current: np.ndarray, bins: int = 10) -> float:
    # fixed bin edges from the baseline's quantiles (so bins are comparable)
    edges = np.quantile(baseline, np.linspace(0, 1, bins + 1))
    edges[0], edges[-1] = -np.inf, np.inf          # catch out-of-range live data
    b_perc = np.histogram(baseline, edges)[0] / len(baseline)
    c_perc = np.histogram(current,  edges)[0] / len(current)
    eps = 1e-6                                       # avoid div-by-zero / log(0)
    b_perc = np.clip(b_perc, eps, None)
    c_perc = np.clip(c_perc, eps, None)
    return float(np.sum((c_perc - b_perc) * np.log(c_perc / b_perc)))

baseline = np.random.normal(0, 1, 10_000)
current  = np.random.normal(0.5, 1, 10_000)         # shifted mean
print(round(psi(baseline, current), 3))             # e.g. ~0.25 → significant
```

The conventional thresholds: **PSI < 0.1** = no significant shift; **0.1–0.25** = moderate shift, investigate; **> 0.25** = major shift, the model is likely degrading and you should consider retraining. The implementation details that matter: derive the bin edges from the *baseline* (not the current data) so comparisons are stable over time, extend the outer edges to ±∞ so live values outside the training range are counted rather than dropped, and clip zero-probability bins with a small epsilon to keep the log finite. PSI detects that the *input* distribution moved (covariate/data drift); it does **not** tell you the relationship between features and target changed — that is concept drift, which needs labels.

### Q16. [Theory] Distinguish data drift, concept drift, and label drift. How do you detect each, especially when labels are delayed?

**Data (covariate) drift** is a change in the input distribution P(X) — e.g., a new user segment, a sensor recalibration, a seasonal shift. **Concept drift** is a change in the relationship P(Y|X) — the *meaning* of the features changed, so the same inputs now map to different outcomes (fraud tactics evolve, a pandemic changes buying behavior). **Label drift** (prior probability shift) is a change in P(Y) — the base rate of the target itself moved (fraud rate triples). These are distinct: you can have data drift with no accuracy loss, and concept drift with no visible input drift.

Detection differs by type and by label availability. Data drift is detected **without labels** by comparing live feature distributions to the baseline (PSI, KS test, KL divergence, or a "domain classifier" that tries to tell training from live data). Concept drift requires **labels**: you monitor model error/accuracy over time and trigger when it degrades; sequential detectors like DDM, EDDM, ADWIN, or Page-Hinkley flag error-rate changes online.

```
 Type           What moved   Needs labels?   Primary detector
 Data drift     P(X)         No              PSI / KS / domain classifier
 Label drift    P(Y)         Labels (or proxy)  prior-rate monitor
 Concept drift  P(Y|X)       Yes             error-rate (DDM/ADWIN/PH)
```

The hard real-world case is **delayed labels** (loans default months later, ad conversions arrive over days). You cannot wait for ground truth to react, so you use **unsupervised drift on inputs and predictions as leading indicators**, plus any fast proxy labels you have, and you reconcile against true labels as they trickle in. The interview-grade nuance: input-drift alarms are *necessary but not sufficient* — they may fire when the model is fine, and stay silent during pure concept drift — so you combine them with delayed-label evaluation rather than trusting either alone.

### Q17. [Practical] Design an automated retraining pipeline. What triggers it, and how do you keep it safe?

A retraining pipeline turns "someone notices the model is stale and manually retrains" into a governed, automatic process. The triggers are typically: **scheduled** (cadence like weekly/daily), **performance-based** (a monitored metric — drift score, or accuracy on arriving labels — crosses a threshold), and **data-based** (a volume of new labeled data accumulates). Choose triggers by how fast your data shifts; high-velocity domains (fraud, ads) lean on performance/drift triggers, slow domains on schedules.

The pipeline stages — usually orchestrated by Airflow, Kubeflow Pipelines, Vertex Pipelines, or Metaflow — are: ingest/validate new data → recompute features (same definitions as serving) → train → **evaluate against the current production model on a held-out set** → register the candidate → gate → deploy via shadow/canary. The safety lives in the gate.

```
 trigger (schedule | drift | new-labels)
    └─► validate data (schema, ranges, volume)   ──fail──► alert, stop
        └─► train candidate
            └─► evaluate vs PROD champion on frozen eval set
                 ├─ candidate worse / regresses guardrail ─► reject, alert
                 └─ candidate better & passes guardrails
                      └─► register → shadow → canary → promote
```

What keeps it safe: **(1)** automatic retraining must never auto-promote a blindly — always **champion/challenger** evaluation where the new model must beat the incumbent on the offline metric *and* pass guardrails (no slice of users gets dramatically worse, fairness metrics hold); **(2)** data validation up front (Great Expectations / TFDV) so a bad upstream data drop does not poison the model — "garbage in, automatically deployed garbage out" is the nightmare scenario; **(3)** the same feature definitions as serving to avoid skew; and **(4)** human approval or a slow canary for high-stakes models. The maturity signal is that retraining is *reproducible and gated*, not just *automatic*.

### Q18. [Theory] How do you do horizontal autoscaling for model serving, and why is GPU autoscaling harder than CPU?

Horizontal autoscaling adds/removes serving replicas based on load. For CPU services you scale on CPU utilization via the Kubernetes HPA, which is well understood. For model serving — especially on GPUs — utilization is a poor and laggy signal, GPUs are scarce and expensive, and pods take long to become ready (large model downloads, CUDA init), so you need richer signals and careful headroom.

GPU autoscaling is harder for several reasons. **(1) GPU utilization is misleading** — a GPU can show 100% "utilization" while massively under-batched, so you scale on *queue depth*, *requests-in-flight*, or *p99 latency* (custom metrics via Prometheus Adapter / KEDA) rather than GPU%. **(2) Cold starts are long** — pulling a multi-GB image/model and initializing CUDA can take minutes, so reactive scaling lags demand; you pre-warm, keep a minimum replica floor, and over-provision headroom. **(3) GPUs are expensive and capacity-constrained** — you cannot just spin up 100; you batch aggressively, use GPU sharing (MIG / Triton multi-instance / time-slicing), and consider scale-to-zero (KServe+Knative) only where cold-start latency is acceptable.

```
 Signal choice for GPU serving:
   CPU%               ✗  uncorrelated with GPU work
   GPU utilization    ~  noisy, hides batching slack
   queue depth / RPS  ✓  leading indicator of saturation
   p99 latency        ✓  directly tied to the SLA
```

The pattern that works: scale on **queue depth or concurrency** with KEDA/HPA-custom-metrics, keep a warm minimum, set generous scale-up and conservative scale-down (to avoid flapping on bursty token-generation workloads), and lean on batching so each replica absorbs more load before you add another expensive GPU.

---

## 🟠 Advanced (8–12 yrs)

### Q19. [Theory] LLM serving differs from classic ML serving. Explain KV cache, PagedAttention, and continuous batching.

LLM inference is **autoregressive**: it generates one token at a time, each conditioned on all previous tokens, so a single request becomes a sequence of forward passes (one per output token). This breaks the classic "one request = one forward pass" batching model and creates two phases with opposite characteristics: **prefill** (process the whole prompt at once — compute-bound, highly parallel) and **decode** (generate tokens one by one — memory-bandwidth-bound, hard to batch). Throughput optimization is dominated by managing the **KV cache** and keeping the GPU busy during decode.

The **KV cache** stores the key/value attention tensors for every token already processed, so each new token does not recompute attention over the whole sequence. It is large (grows with sequence length × layers × heads) and its size is what limits how many requests fit on a GPU. **PagedAttention** (introduced by vLLM) manages this cache like OS virtual memory: it splits it into fixed-size **blocks** allocated on demand, eliminating the internal fragmentation and over-reservation of naive contiguous allocation, which roughly doubles the number of concurrent sequences a GPU can hold. **Continuous (in-flight) batching** then exploits that headroom: instead of waiting for an entire batch to finish, the scheduler inserts new requests and evicts completed ones at *every decoding step*, so the GPU never idles waiting for the slowest sequence in a batch.

```
 Naive static batching:   [====req A====][wait][====req B====]  GPU idles
 Continuous batching:     A B C  → A B C D → B D E → ...        always full
                          (requests join/leave the batch per token step)
```

Together these are why vLLM/TensorRT-LLM/SGLang deliver many times the throughput of running a Transformer with HuggingFace `generate()`. The metrics also change: you track **TTFT** (time to first token, governed by prefill) and **TPOT/ITL** (time per output token, governed by decode), not just end-to-end latency — a chat UX cares about TTFT, a batch summarizer cares about total tokens/sec.

### Q20. [Theory] Compare the latency/throughput/cost levers for LLM serving: quantization, tensor/pipeline parallelism, speculative decoding, and prefix caching.

Each lever attacks a different bottleneck, so you stack them. The bottlenecks are: GPU memory (does the model + KV cache fit?), compute (prefill), and memory bandwidth (decode).

| Lever | Attacks | Effect | Cost/Risk |
|---|---|---|---|
| **Quantization** (FP8/INT8/INT4, GPTQ/AWQ) | Memory + bandwidth | Fits on fewer/smaller GPUs, faster decode | Small accuracy loss; needs calibration/eval |
| **Tensor parallelism** | Memory + compute | Splits each layer across GPUs (intra-layer) | Heavy inter-GPU comms; needs fast interconnect (NVLink) |
| **Pipeline parallelism** | Memory | Splits layers across GPUs (inter-layer) | Pipeline bubbles; better across nodes |
| **Speculative decoding** | Decode latency | Small draft model proposes tokens, big model verifies in one pass | Extra draft compute; gains depend on acceptance rate |
| **Prefix/prompt caching** | Prefill compute | Reuse KV cache for shared prompt prefixes (system prompts, RAG context) | Cache memory; only helps with shared prefixes |

The reasoning: **quantization** is usually the first and biggest win — FP8/INT8 halves or quarters memory with minimal quality loss, letting you serve a model on cheaper hardware or fit a bigger batch. **Tensor parallelism** is how you serve a model too big for one GPU, but it demands high-bandwidth interconnect (NVLink/NVSwitch) because it communicates within every layer; across slow links it falls apart, which is when **pipeline parallelism** (less chatty, layer-granular) is preferred across nodes. **Speculative decoding** specifically reduces *decode* latency by having a cheap draft model guess several tokens that the large model verifies in a single forward pass — net win when the draft's tokens are frequently accepted. **Prefix caching** is a huge lever for RAG and agent workloads where every request shares a long system prompt or retrieved context: cache that prefix's KV once and skip re-prefilling it.

The staff-level synthesis: you do not pick one — you quantize to fit, parallelize only as much as the model size forces (parallelism adds comms overhead and reduces per-GPU efficiency), and layer speculative decoding and prefix caching on top for the specific traffic patterns (interactive chat, shared-prompt RAG) where they pay off.

### Q21. [Coding] Implement a champion/challenger evaluation gate that decides whether to promote a newly trained model.

The gate must enforce that a candidate is promoted only if it is *significantly* better than the incumbent on the primary metric **and** does not regress any guardrail (latency, a protected-slice metric, calibration). Encoding this as code makes promotion auditable and removes "the model looked good, ship it" judgment calls.

```python
from dataclasses import dataclass
from scipy import stats

@dataclass
class Eval:
    auc: float
    auc_samples: list          # bootstrap/per-fold AUCs for significance
    p99_latency_ms: float
    worst_slice_auc: float      # min AUC across protected segments

def should_promote(champion: Eval, challenger: Eval,
                   min_gain: float = 0.005,
                   latency_budget_ms: float = 50.0,
                   slice_floor: float = 0.70) -> tuple[bool, str]:
    # 1) primary metric must improve by a meaningful margin
    if challenger.auc - champion.auc < min_gain:
        return False, f"AUC gain {challenger.auc - champion.auc:.4f} < {min_gain}"
    # 2) improvement must be statistically significant, not noise
    _, p = stats.ttest_ind(challenger.auc_samples, champion.auc_samples)
    if p >= 0.05:
        return False, f"gain not significant (p={p:.3f})"
    # 3) guardrails: never ship a latency or fairness regression
    if challenger.p99_latency_ms > latency_budget_ms:
        return False, f"p99 {challenger.p99_latency_ms}ms > budget"
    if challenger.worst_slice_auc < slice_floor:
        return False, f"worst-slice AUC {challenger.worst_slice_auc} < floor"
    return True, "promote: significant gain, guardrails pass"

# champion = current PROD model's eval; challenger = freshly trained candidate
ok, reason = should_promote(champion, challenger)
```

The design choices that signal seniority: **(1)** require a *minimum effect size* (`min_gain`), not just any improvement, because tiny gains are not worth the rollout risk; **(2)** test for **statistical significance** so you do not promote on evaluation noise; **(3)** treat **guardrails as hard gates** — a model that is 1% more accurate overall but tanks a protected user slice or blows the latency budget must be rejected; and **(4)** return a human-readable reason so the decision is logged and auditable. In production this runs inside the retraining pipeline and writes its verdict to the model registry as a promotion record.

### Q22. [Theory] How do you guarantee feature freshness and consistency at low latency in the online store, and what are the failure modes?

The online store must serve the *current* feature values within a few milliseconds, which forces trade-offs around how features are materialized into it. There are two patterns: **push/streaming** (a Flink/Kafka Streams job computes features from events and writes them to the online store within seconds) and **pull/batch materialization** (a scheduled job recomputes features and bulk-loads them, accepting hours of staleness). Real-time features (e.g., "transactions in the last 5 minutes") require streaming; slow-moving features (e.g., "customer tenure") are fine as batch.

The consistency challenge is **point-in-time correctness on the online side too**: the features served at request time must be reproducible later for debugging and for generating training data that matches what production actually saw. The standard mitigation is to **log the exact feature vector served** alongside the prediction, so training data can be built from *logged-and-served* features rather than recomputed ones — closing the skew loop definitively.

```
 Streaming path:  events ─► Flink ─► online store (seconds-fresh)
 Batch path:      warehouse ─► nightly job ─► online store (hours-fresh)
 Always:          serving logs the served feature vector + prediction
                  (for replay, debugging, and skew-free training data)
```

The failure modes to name: **(1) staleness bugs** — a streaming job lags or dies and the online store quietly serves hour-old "real-time" features; you need freshness SLAs and lag alerts on the materialization jobs. **(2) Hot-key and capacity** — popular entities (a viral product) hammer the online store; mitigate with caching and read replicas. **(3) Partial reads** — some features present, some missing; you must define explicit default/imputation behavior, because silently passing nulls to the model is itself a skew source. **(4) TTL mismatches** — features expire in the online store at a different cadence than the model expects.

### Q23. [Practical] A model's offline AUC is excellent but online business metrics dropped after launch. Walk through how you debug it.

This is the canonical "the model is fine but the system is broken" scenario, and a structured debugging approach separates senior from junior responses. I work outward from the most common and cheapest-to-check causes: skew, then feature freshness, then the experiment itself, then the metric.

First, **rule out training/serving skew** — the prime suspect. I compare the feature vectors *logged at serving time* against the features the offline pipeline would have computed for the same entities and timestamps. A mismatch (different nulls, different scaling, a feature computed with the wrong window) explains a great-offline/bad-online gap immediately. If I am not logging served features, that itself is the bug to fix first, because you cannot debug what you did not record. Second, **check feature freshness and availability online** — are real-time features actually fresh, or is the streaming job lagging so the model gets stale/default inputs it never saw in training?

```
 Debug order (cheap → expensive):
 1. Skew:        served features  vs  offline-recomputed features
 2. Freshness:   online store lag, default/imputed rates at serve
 3. Experiment:  randomization correct? metric attributed to right arm?
 4. Metric:      offline metric (AUC) vs business metric mismatch?
 5. Population:   training data distribution vs live traffic (drift)
```

Third, **audit the A/B test itself** — a surprising number of "the model is worse" results are experiment bugs: broken randomization (assignment correlated with a confounder), latency added by the new model hurting conversion independent of prediction quality, or guardrail metrics attributed to the wrong arm. Fourth, **question the offline metric** — AUC measures ranking, but the business cares about decisions at a specific threshold; the model may rank well yet be **miscalibrated**, so the operating threshold is wrong online. Finally, **check population mismatch** — the offline eval set may not represent live traffic (selection bias, a stale snapshot), so excellent offline AUC was measured on the wrong distribution. The meta-point: offline metrics are a *proxy*, and the gap to business metrics is exactly where skew, freshness, calibration, and experiment-design bugs hide.

### Q24. [Coding] Write a Kubernetes/KServe InferenceService manifest with a canary split, autoscaling, and GPU resources, and explain the key fields.

KServe expresses serving as a declarative CRD, so a canary rollout is a `canaryTrafficPercent` field rather than imperative scripting. This manifest serves an LLM via the vLLM runtime, ramps a new model version to 10% of traffic, autoscales on concurrency, and requests a GPU.

```yaml
apiVersion: serving.kserve.io/v1beta1
kind: InferenceService
metadata:
  name: chat-llm
spec:
  predictor:
    minReplicas: 2            # warm floor: avoid cold starts on an LLM
    maxReplicas: 12
    scaleTarget: 8            # target concurrent requests per replica
    scaleMetric: concurrency  # scale on in-flight requests, NOT cpu/gpu%
    canaryTrafficPercent: 10  # send 10% to the new revision; 90% stays on last-good
    containers:
      - name: kserve-container
        image: vllm/vllm-openai:latest
        args: ["--model", "/mnt/models", "--max-model-len", "8192"]
        resources:
          limits:
            nvidia.com/gpu: "1"      # whole GPU; use MIG slices for sharing
            memory: 24Gi
          requests:
            nvidia.com/gpu: "1"
            memory: 24Gi
        readinessProbe:               # only route once the model is loaded
          httpGet: { path: /health, port: 8080 }
          initialDelaySeconds: 60     # model load is slow; don't probe too early
```

The load-bearing fields: **`canaryTrafficPercent: 10`** — KServe keeps the previous good revision serving 90% and routes 10% to the new one; you watch metrics, then bump to 100% or roll back by deleting the new revision (instant, because the old one never left). **`scaleMetric: concurrency`** with **`scaleTarget`** — scaling on in-flight requests, not GPU%, because GPU utilization is a poor signal for batched LLM serving. **`minReplicas: 2`** — a warm floor so you never serve from a cold start (an LLM cold start is tens of seconds to minutes). **`readinessProbe` + `initialDelaySeconds`** — critical for GPU models because the container is "running" long before the model finishes loading; without a correct readiness probe, traffic hits a not-ready pod and errors. For autoscaling to zero you would use Knative-backed serverless mode, accepting the cold-start cost for spiky/dev workloads.

### Q25. [Theory] What is an inference graph / model pipeline, and what are the trade-offs of composing models in-process vs as separate services?

Real inference is rarely one model: a request may run a feature transformer → a candidate-generation model → a ranking model → a business-rules/calibration step → a guardrail/safety check. An **inference graph** (KServe's `InferenceGraph`, Ray Serve's deployment graph, Triton's ensemble/BLS) declares this DAG of steps and routes data through it. The design choice is whether the steps run **in one process/container** or as **separate networked services**.

In-process composition (Ray Serve graph, Triton ensemble) keeps the steps colocated, so data passes via memory or local calls — **low latency, no network hops, simpler tracing**, and you can pin GPU tensors without serializing across the wire. The cost is **coupled scaling and deployment**: the candidate-gen and ranking models scale together and ship together even if one is far heavier, and a memory leak in one step can take down the whole graph. Separate services give **independent scaling, deployment, and language/runtime choice** per model (scale the expensive ranker to 20 replicas, leave the light transformer at 2), at the cost of **network latency, serialization overhead, and more operational surface** (more endpoints, more failure points, distributed tracing required).

```
 In-process (Ray Serve / Triton ensemble):
   [transform → candgen → rank → calibrate]  one deployment, mem-passing
   + low latency, simple   − coupled scaling/deploy

 Service-per-model:
   transform-svc → candgen-svc → rank-svc → calibrate-svc
   + independent scale/deploy/runtime   − network + serialization + ops
```

The staff-level heuristic: compose **in-process by default** for latency and simplicity, and break a step out into its own service only when it has a **materially different scaling profile, hardware need (GPU vs CPU), release cadence, or ownership team**. Premature decomposition multiplies tail latency (each hop adds p99) and operational cost for no benefit.

### Q26. [Practical] How do you control cost while meeting latency SLAs across a fleet of models, including expensive LLMs?

Cost control in ML serving is a portfolio problem: a few models (especially LLMs on GPUs) dominate spend, so you apply different levers by tier rather than uniformly. The framework is to **right-size hardware per model, maximize utilization, and route intelligently**.

The levers, roughly in order of impact for an LLM-heavy fleet: **(1) Maximize GPU utilization** via batching/continuous batching and GPU sharing (MIG, time-slicing, multiple model instances) so you are not paying for idle accelerators — under-batched GPUs are the biggest waste. **(2) Quantize** to fit models on cheaper/fewer GPUs (FP8/INT8) after validating accuracy. **(3) Cascade / route by difficulty** — try a small cheap model first and escalate to the large model only on hard inputs (router or confidence threshold); for LLMs, route easy queries to a small model and reserve the frontier model for hard ones. **(4) Cache** — prefix/prompt caching for shared LLM context, and full-response caching for repeated queries (semantic caching for LLMs). **(5) Autoscale and scale-to-zero** non-critical/dev endpoints; use **spot/preemptible** capacity for batch and shadow workloads (not for the live SLA path). **(6) Batch where freshness allows** — move anything that does not need live computation to cheap offline batch scoring.

```
 Per-request cost levers (LLM example):
   semantic cache hit ───────────────► ~free
   small/router model handles it ────► cheap
   prefix cache reuses system prompt ► cheaper prefill
   large model, quantized, batched ──► baseline
   large model, unbatched, FP16 ─────► most expensive (avoid)
```

The judgment an interviewer wants: you do not chase cost uniformly — you **profile spend, find the few endpoints that dominate, and apply the SLA-respecting levers there**, while keeping a hard rule that cost optimizations (spot, scale-to-zero, aggressive batching) never push the live path past its latency SLA. Cost and latency are co-optimized, not traded blindly.

---

## 🔴 Expert (15+ yrs)

### Q27. [Theory] Design the end-to-end MLOps platform for an organization running hundreds of models. What are the architectural pillars and the hardest cross-cutting concerns?

At hundreds of models the bottleneck stops being any single model and becomes **the platform's ability to let many teams ship and operate models safely without each reinventing the stack**. The pillars are a layered platform with paved paths: a **data/feature layer** (feature store with offline+online stores, point-in-time correctness, shared definitions), a **training layer** (orchestrated, reproducible pipelines with experiment tracking and lineage), a **registry layer** (versioned artifacts, stage promotion, lineage to data+code), a **serving layer** (a small set of blessed runtimes — vLLM for LLMs, Triton for the rest — behind KServe/Ray Serve with standard autoscaling and rollout), and an **observability layer** (operational + data-quality + model-quality monitoring, unified by trace/request IDs).

```
 ┌──────────────────── Governance / lineage / access control ──────────────────┐
 │ Observability:  ops metrics │ data-quality │ drift │ model-quality │ cost    │
 │ Serving:        KServe/Ray Serve  ·  vLLM/Triton  ·  canary/shadow/A-B       │
 │ Registry:       versioned artifacts · stage promotion · model cards         │
 │ Training:       orchestrated pipelines · experiment tracking · reproducible  │
 │ Feature/Data:   feature store (offline+online) · PIT correctness · validation│
 └──────────────────────────────────────────────────────────────────────────────┘
                 Paved "golden path"; teams opt out only with justification
```

The hardest cross-cutting concerns — the ones that actually decide whether the platform survives: **(1) Skew elimination as a platform guarantee**, not a per-team discipline — the feature store and served-feature logging must make skew structurally hard. **(2) Lineage and reproducibility** end-to-end (data version → code sha → model version → deployment → prediction) for debugging and compliance. **(3) Multi-tenancy and isolation** — fair GPU sharing, quota, and blast-radius limits so one team's runaway job does not starve others. **(4) Governance** — model cards, approval workflows, fairness/bias gates, and access control, increasingly mandated by regulation (EU AI Act tiers, model risk management). **(5) Standardization vs autonomy** — a *golden path* that is genuinely the easiest option (so teams adopt it) while allowing justified opt-outs. The platform's success metric is **time-to-production for a new model and mean-time-to-detect/recover for a degraded one**, not the sophistication of any single component.

### Q28. [Behavioral] Tell me about a time you led the response to a production ML degradation that ordinary service monitoring missed. (STAR)

**Situation.** At a fintech I led the ML platform team. Our fraud-scoring model — on the synchronous payment-authorization path — was operationally green for nine days: p99 latency healthy, zero errors, GPUs nominal. Yet the fraud-operations team reported a creeping rise in chargebacks. Nothing in our service dashboards explained it, and because confirmed-fraud labels lag by weeks, our accuracy dashboards still looked fine.

**Task.** As the senior engineer owning the platform, I had to determine whether the model was actually degrading (and why) while losing real money daily, and decide whether to roll back, retrain, or escalate — under pressure from both fraud-ops and the payments org that any change to the auth path is high-risk.

**Action.** I ran the structured debug I described earlier, starting with the cheapest leading indicators because labels were too slow to wait for. The operational metrics were a red herring by design — a model can be perfectly healthy as a *service* and wrong as a *model*. I pulled the logged served-feature vectors and computed PSI against the training baseline per feature: most were stable, but one feature — "merchant-category risk score," sourced from a third-party enrichment — had a PSI of 0.4 and a spike in default/imputed values. The vendor had silently changed an encoding two weeks earlier, so a feature the model leaned on heavily was now effectively garbage, served as a default the model had never trained on. Critically, this was **concept-relevant data drift that no error-rate alarm caught yet** because labels had not matured. I immediately had us pin that feature to its last-good imputation and shadow-tested a retrained candidate that down-weighted the unstable source; once the candidate matched expected fraud-catch rates in shadow, we canaried it on the auth path at 5% with a hard latency guardrail, then ramped.

**Result.** Chargebacks returned to baseline within the week, and we recovered an estimated mid-six-figures of annualized fraud loss. More durably, I drove three platform changes: **(1)** served-feature logging became mandatory for every model (you cannot debug what you did not record); **(2)** we added per-feature PSI/null-rate monitoring with alerts as *leading indicators*, decoupled from delayed labels; and **(3)** we put data-quality contracts and validation on every third-party feature source. The lesson I now teach: **operational health and model correctness are independent failure domains**, delayed labels mean you must monitor inputs and predictions as leading indicators, and the single highest-leverage investment is logging exactly what the model was served so any future degradation is debuggable in minutes, not days.

### Q29. [Theory] What does it take to make a production ML system reproducible and auditable end-to-end, and why is "the model weights" not enough?

Reproducibility means you can regenerate a given model and explain any past prediction; auditability means you can *prove* the lineage to a regulator or a postmortem. The model weights are nowhere near sufficient because a prediction is the output of a long chain — raw data → feature definitions → feature values at a timestamp → training code → hyperparameters → environment → artifact → serving config → the exact input served. A break anywhere in that chain means "we cannot reproduce this" or "we cannot explain why this customer was denied."

The required artifacts and their versioning: **data versioning** (immutable snapshots or a versioned table format like Delta/Iceberg, plus dataset hashes — DVC/LakeFS) so "trained on the data as of date X" is exact; **feature lineage** (which feature definitions and versions, computed how) from the feature store; **code + config provenance** (git sha, hyperparameters, random seeds) captured by experiment tracking (MLflow/W&B); **environment pinning** (container digest, library lockfile, CUDA version) because numerics differ across versions; **registry lineage** linking the artifact back to all of the above; and **prediction logging** (input feature vector, model version, output, timestamp) so any individual decision is explainable after the fact.

```
 data snapshot (hash) ─┐
 feature defs (version)─┼─► training run (code sha, seeds, env digest)
 hyperparams ──────────┘        └─► registered artifact (model:vN)
                                       └─► deployment (serving config, canary %)
                                             └─► prediction log (input, output, ts)
 Audit query: "why was decision D made?" → walk this chain backward.
```

Why it matters beyond compliance: this lineage is what lets you **debug** (reproduce the exact training set to diagnose a bad model), **roll back** with confidence (you know exactly what the previous good state was), and satisfy **right-to-explanation / model-risk-management** requirements (EU AI Act high-risk tiers, financial model governance) where you must justify automated decisions. The expert framing: reproducibility is not a research nicety — it is the foundation of incident response, rollback safety, and regulatory survival, and it must be enforced by the *platform* (captured automatically) rather than left to engineer discipline.

### Q30. [Practical] How do you migrate a large organization from ad-hoc model deployment to a governed MLOps platform without halting delivery?

You treat it as a platform-adoption problem, not a big-bang rewrite, because dozens of teams have models in production that cannot stop shipping. The strategy is **strangler-fig migration behind a genuinely better golden path**, sequenced so each step delivers value on its own and adoption is pulled (teams want it) rather than pushed (mandated and resented).

The sequence I would run: **(1) Instrument first, change nothing** — add a thin monitoring/feature-logging layer to existing deployments so you get observability and skew-detection across the fleet without forcing migration; this immediately surfaces which models are actually risky and builds the case. **(2) Build the registry + serving golden path** as the easiest way to deploy a *new* model — if the paved path is faster and safer than the team's bespoke setup, new models adopt it voluntarily. **(3) Migrate the highest-risk/highest-value models next** (the ones on critical paths or facing regulation), since they gain the most from governance, and use them as reference implementations. **(4) Introduce the feature store** where skew bugs are biting, migrating feature definitions incrementally. **(5) Add governance gates** (champion/challenger, fairness, approval) once the plumbing is in place — gates without paved paths just block delivery and breed shadow IT.

```
 Phase 1: observe existing fleet (logging, drift)        ── no migration, fast wins
 Phase 2: golden path for NEW models (registry+serving)  ── pull adoption
 Phase 3: migrate critical/regulated models              ── reference impls
 Phase 4: feature store where skew hurts                 ── incremental
 Phase 5: governance gates on the paved path             ── safety, not blocking
 Throughout: ADRs, golden-path docs, platform team as enablers not gatekeepers
```

The leadership judgment: success is measured by **adoption and reduced incident rate**, not by how much you mandated. The two failure modes I actively guard against are (a) **mandating governance before the paved path exists** — teams route around it and you get shadow deployments that are *less* visible than before, and (b) **a big-bang migration** that freezes delivery and burns political capital. I make the platform team an **enabler** (the easy path, great docs, white-glove migration of the first few teams) rather than a gatekeeper, document decisions as ADRs, and keep every step independently shippable and reversible. The metric that proves it worked is time-to-production dropping while mean-time-to-detect degradation improves across the org.

### Q31. [Theory] Serving an agentic / multi-step LLM workload (tool calls, RAG, multi-turn) has different infra characteristics than single-shot inference. What changes?

A single-shot LLM request is one prefill + N decode steps. An **agentic** request is a *loop*: the model emits a tool call, your system executes it (a search, a DB query, code), feeds the result back, the model reasons again, possibly calls another tool, and so on for many turns before a final answer. This changes the infra profile fundamentally: a single user "task" becomes many LLM calls interleaved with external I/O of highly variable latency, and the request is **long-lived and stateful** (the growing conversation + tool results form an ever-larger context).

The consequences: **(1) Context grows every turn**, so the KV cache and prefill cost balloon — the same long prefix (system prompt, prior turns, retrieved docs) is reprocessed unless you use **prefix/KV caching**, which becomes essential rather than optional for agent economics. **(2) Latency is dominated by the loop, not one inference** — total task latency = Σ(LLM calls) + Σ(tool latencies), so tail latency comes from slow tools and the number of iterations; you need per-step timeouts, max-iteration caps, and parallel tool execution. **(3) Statefulness breaks stateless-autoscaling assumptions** — a request occupies a slot for seconds-to-minutes, so concurrency and memory planning differ from short requests; orphaned/abandoned agent loops must be reaped. **(4) Cost is unpredictable per request** — one task may make 3 LLM calls or 30; you need per-task token/iteration budgets and circuit breakers. **(5) Reliability and idempotency of tool calls** — tools have side effects, retries can double-execute, so tool execution needs idempotency and the agent loop needs durable, replayable state (Temporal-style durable execution is increasingly used to make agent runs crash-recoverable).

```
 Single-shot:   prefill → decode×N → done            (ms–seconds, stateless)
 Agentic loop:  prefill → decode → TOOL → prefill' → decode → TOOL → ... → answer
                (seconds–minutes, growing context, external I/O, side effects)
 Levers:        prefix/KV cache · per-step timeouts · max iterations ·
                token/cost budget · parallel tools · durable replayable state
```

The expert synthesis as of 2026: serving agents well means treating a task as a **durable, observable workflow** (with budgets, timeouts, and replay) layered over an LLM engine tuned for **prefix reuse and continuous batching of bursty, variable-length calls** — it is as much a distributed-systems/orchestration problem (state, retries, idempotency, observability per step) as it is an inference-engine problem. Routing (small model for routine steps, frontier model for hard reasoning) and aggressive prompt/prefix caching are what keep the economics viable.

### Q32. [Theory] How do you detect and respond to silent failures unique to LLM systems — hallucination, prompt injection, and quality regression after a model/prompt change?

LLM systems fail silently in ways classic ML does not: the output is fluent and well-formed but *wrong* (hallucination), *manipulated* (prompt injection from untrusted content), or *subtly worse* after a model/prompt update with no error or latency signal. Because there is rarely an immediate ground-truth label, you build **layered, mostly-online evaluation** rather than relying on a single accuracy number.

For **hallucination/quality**, the toolkit is: an **offline eval suite** (a curated set of inputs with rubric-graded expected behavior, run on every model/prompt change as a regression gate), **LLM-as-judge** scoring of online samples for groundedness/faithfulness (does the answer follow from the retrieved context?), **automatic groundedness checks** in RAG (verify claims are supported by retrieved passages, flag uncited assertions), and **online feedback signals** (thumbs up/down, user edits, task-completion/abandonment rates) as a delayed quality proxy. For **prompt injection**, you treat all retrieved/tool/user content as untrusted: input/output guardrail classifiers, instruction-data separation, sandboxed and least-privilege tool execution (so an injected "delete everything" cannot), and output filters for exfiltration patterns. For **regression after a change**, the discipline is the same as any model rollout — **shadow/canary the new model or prompt**, compare eval-suite scores and online judge/feedback metrics against the incumbent, and gate promotion on no regression.

```
 Layer            Signal                         Catches
 ───────────────  ─────────────────────────────  ──────────────────────
 Offline eval     rubric/judge on fixed set      regression on known cases
 Online judge     groundedness/faithfulness      hallucination, drift
 RAG grounding    claim-vs-source check          unsupported assertions
 Guardrails       in/out classifiers, sandbox    prompt injection, exfil
 User feedback    👍/👎, edits, abandonment       real-world quality (lagged)
 Rollout gate     shadow→canary vs champion      regressions from changes
```

The expert point: because there is no single online accuracy metric, **a "prompt change" or "model bump" is a deployment that must go through the same shadow/canary/eval-gate rigor as a code change** — teams that edit the production system prompt by hand with no eval gate ship silent regressions constantly. And prompt injection is a *security* problem, not a quality problem: the correct mental model is that the LLM will sometimes follow instructions embedded in data, so you contain blast radius with least-privilege tooling and output filtering rather than hoping the model "won't fall for it." This is the LLM-era extension of the same principle that runs through the whole discipline — never trust a fluent output, monitor leading indicators, and gate every change.

---

## ✅ Key Takeaways

- **Training and serving are opposite workloads** (throughput vs latency, retryable vs live); the boundary between them is where training/serving skew — the most damaging silent ML bug — lives.
- **Feature stores** exist to kill skew and enforce **point-in-time correctness**; the same transformation writes the offline and online stores, and you must **log the exact features served** to debug and to build skew-free training data.
- **Batch vs real-time** is a freshness-vs-cost decision; precompute and look up whenever predictions do not depend on just-arrived input.
- **Latency and throughput trade off through batching**; fix the latency SLA as a constraint and maximize throughput (minimize cost) under it. Dynamic/continuous batching is the central knob.
- **Pick the engine by model type** (vLLM for LLMs — PagedAttention + continuous batching; Triton for the rest) and the **orchestrator by platform** (KServe if Kubernetes-standardized, Ray Serve for composition).
- **Rollout is layered**: shadow (risk-free behavior check) → canary (operational health) → A/B (proves business lift) → full. Only A/B proves *better*; shadow is the safest pre-launch gate.
- **Drift has three kinds** — data P(X), label P(Y), concept P(Y|X). With **delayed labels**, monitor input/prediction drift (PSI, KS) as *leading indicators* and reconcile against true labels later.
- **Retraining must be gated, not just automatic**: champion/challenger with significance + guardrails, fronted by data validation so bad data is not auto-deployed.
- **GPU autoscaling** scales on **queue depth/concurrency/p99**, not GPU%, with a warm replica floor because cold starts are long; maximize utilization via batching and GPU sharing.
- **Operational health and model correctness are independent failure domains** — a model can be green as a service and wrong as a model; monitor ops + data-quality + model-quality layers.
- **Reproducibility is platform-enforced lineage** (data hash → code sha → env digest → artifact → deployment → prediction), not just saved weights — it underpins debugging, rollback, and regulatory audit.
- **LLM/agent serving** is a distributed-systems problem too: prefix/KV caching, per-step timeouts, token/iteration budgets, durable replayable state, and treating prompt injection as a least-privilege security boundary.

## ⚠️ Common Pitfalls

- Computing features differently in training vs serving (skew), and **not logging the served feature vector** so the skew is undebuggable after the fact.
- Naive feature joins that attach current/future feature values to historical labels — **label leakage** that makes offline metrics fraudulently good.
- Loading the model per request instead of once at startup; missing readiness probes so traffic hits not-yet-loaded GPU pods.
- Trusting offline AUC as the business signal: a well-ranked but **miscalibrated** model sets the wrong operating threshold online.
- Autoscaling GPU serving on **CPU% or GPU utilization** instead of queue depth/concurrency; no warm floor, so cold starts cause SLA breaches under bursts.
- Treating drift alarms as sufficient: input drift can fire when the model is fine and stay silent during pure **concept** drift — combine with delayed-label evaluation.
- **Auto-promoting** retrained models without champion/challenger significance tests and guardrails; no upstream data validation, so a bad data drop ships a poisoned model.
- Under-batched GPUs and FP16 everywhere — the biggest serving cost waste; not quantizing or using prefix caching for shared LLM prompts.
- Decomposing an inference graph into microservices prematurely, multiplying tail latency and ops surface with no scaling benefit.
- Editing a production system prompt or bumping an LLM with **no eval gate / shadow / canary** — shipping silent quality regressions.
- Treating prompt injection as a model-quality issue rather than a **security boundary**; running agent tools with broad privileges so an injected instruction has real blast radius.
- Conflating blue-green/canary/A-B/shadow — using a canary to "prove the model is better" (it only proves it is healthy) or a shadow to measure lift (it realizes no outcomes).

## 📚 Further Reading

- *Designing Machine Learning Systems* — Chip Huyen (training/serving split, feature engineering, monitoring, the full lifecycle).
- *Machine Learning Design Patterns* — Lakshmanan, Robinson & Munn (serving, batch vs online, continuous evaluation patterns).
- *Reliable Machine Learning* (Google SRE-style) and the **Hidden Technical Debt in Machine Learning Systems** paper (Sculley et al.) — why ML systems rot.
- **vLLM** docs and the *PagedAttention* paper; **NVIDIA Triton** and **TensorRT-LLM** docs for high-throughput serving.
- **KServe**, **Ray Serve**, and **Kubeflow Pipelines** documentation; **Feast** and **Tecton** docs for feature stores.
- **MLflow** (tracking + model registry), **Evidently** and **NannyML** (drift/monitoring), and **Great Expectations / TFDV** for data validation.
- The **EU AI Act** high-risk model obligations and model-risk-management (SR 11-7) material for governance/audit context.
- **OpenTelemetry** for unifying ML serving traces/metrics, and the **RAGAS** framework plus LLM-as-judge literature for LLM/RAG evaluation.
