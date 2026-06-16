# Kubernetes

Kubernetes (K8s) is the de-facto open-source platform for orchestrating containerized workloads, providing declarative configuration, self-healing, horizontal scaling, and service discovery across a cluster of machines. This guide covers everything from first principles to staff-level operational depth, current through Kubernetes 1.32 (2026).

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

### Q1. [Theory] What is Kubernetes and what problem does it solve?

Kubernetes is a container orchestrator: it takes a fleet of machines (nodes) and schedules containerized workloads onto them while continuously reconciling the *actual* state of the cluster against your *declared desired* state. Before orchestrators, teams manually placed containers, hand-wired networking, and wrote bespoke scripts for restarts and scaling — none of which survived a node failure gracefully.

The core value is the **reconciliation loop**: you declare "I want 5 replicas of this image," and controllers work continuously to make that true even if a node dies, a process crashes, or someone deletes a Pod. Kubernetes adds service discovery, load balancing, rolling deployments, secret/config management, storage abstraction, and autoscaling on top of this. The trade-off is significant operational complexity — for a single small app, a managed PaaS or plain Docker Compose is often cheaper to run than a cluster.

### Q2. [Theory] Explain the Kubernetes control plane and node components.

```
                     ┌──────────────────── CONTROL PLANE ────────────────────┐
                     │                                                        │
   kubectl ─────────►│  kube-apiserver  ◄──►  etcd (cluster state store)      │
   (clients)         │      ▲   ▲                                             │
                     │      │   │                                             │
                     │  scheduler  controller-manager  (cloud-controller-mgr) │
                     └──────┼───────────────────────────────────────────────-┘
                            │ (watch/assign)
        ┌───────────────────┼───────────────────────────────┐
        ▼                                                     ▼
  ┌─── WORKER NODE 1 ───┐                            ┌─── WORKER NODE 2 ───┐
  │ kubelet  kube-proxy │                            │ kubelet  kube-proxy │
  │ container runtime   │                            │ container runtime   │
  │  ┌────┐  ┌────┐      │                            │  ┌────┐             │
  │  │Pod │  │Pod │      │                            │  │Pod │             │
  │  └────┘  └────┘      │                            │  └────┘             │
  └─────────────────────┘                            └─────────────────────┘
```

- **kube-apiserver**: the only component that talks to etcd; the front door for all reads/writes (REST over HTTP). Everything else watches the API server.
- **etcd**: a distributed, strongly-consistent key-value store (Raft) holding the entire cluster state. Losing etcd = losing the cluster's brain.
- **kube-scheduler**: watches for unscheduled Pods and binds each to a node based on resource requests, affinity, taints/tolerations, and topology constraints.
- **kube-controller-manager**: runs control loops (Deployment, ReplicaSet, Node, Job, etc.) that reconcile desired vs. actual state.
- **cloud-controller-manager**: integrates with the cloud provider (load balancers, volumes, node lifecycle).
- **kubelet**: the node agent; ensures the containers described by PodSpecs are running and healthy, and reports status back.
- **kube-proxy**: programs iptables/IPVS (or hands off to a CNI's eBPF dataplane) so Service virtual IPs route to the right Pods.

### Q3. [Theory] What is a Pod, and why isn't it just a container?

A Pod is the smallest deployable unit in Kubernetes — one or more containers that share a network namespace (same IP and port space), share storage volumes, and are always co-scheduled on the same node. You almost never run a single raw container; you run a Pod wrapping it.

The multi-container pattern exists because some helpers must live *right next to* the main app: a **sidecar** (log shipper, service-mesh proxy), an **init container** (runs to completion before the main container starts, e.g., schema migration), or an **ambassador/adapter**. As of Kubernetes 1.29+, native sidecar containers (declared as init containers with `restartPolicy: Always`) get proper lifecycle ordering — they start before and terminate after the main containers, fixing long-standing job-completion races.

### Q4. [Practical] Write a minimal Deployment exposing an nginx app.

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: web
  labels: { app: web }
spec:
  replicas: 3
  selector:
    matchLabels: { app: web }       # MUST match template labels
  template:
    metadata:
      labels: { app: web }
    spec:
      containers:
        - name: nginx
          image: nginx:1.27-alpine
          ports:
            - containerPort: 80
          resources:
            requests: { cpu: "100m", memory: "64Mi" }
            limits:   { cpu: "250m", memory: "128Mi" }
---
apiVersion: v1
kind: Service
metadata:
  name: web
spec:
  selector: { app: web }            # routes to Pods with app=web
  ports:
    - port: 80
      targetPort: 80
```

A Deployment owns a ReplicaSet, which owns Pods. The Service selects Pods by label (not by Deployment), giving a stable virtual IP and DNS name (`web.<namespace>.svc.cluster.local`). **Edge case**: if `selector` doesn't match `template.labels`, the API server rejects the Deployment — a frequent beginner error.

### Q5. [Theory] What are the Service types and when do you use each?

```
ClusterIP  ──► internal only; stable VIP reachable inside the cluster (default)
NodePort   ──► opens a port (30000-32767) on every node; dev/on-prem ingress
LoadBalancer ► provisions a cloud LB pointing at NodePorts; per-service public entry
ExternalName ► CNAME alias to an external DNS name; no proxying
Headless   ──► clusterIP: None; DNS returns Pod IPs directly (StatefulSets, custom LB)
```

Use **ClusterIP** for internal microservice-to-microservice traffic. **NodePort** is mostly for development or behind an external load balancer you manage yourself. **LoadBalancer** gives each service its own cloud LB — convenient but expensive at scale (you pay per LB), which is why most production clusters front everything with a single **Ingress** or **Gateway** instead. **Headless** services skip the proxy and return individual Pod IPs, which StatefulSets and client-side load balancers rely on.

### Q6. [Practical] How do ConfigMaps and Secrets differ, and how do you consume them?

ConfigMaps hold non-confidential key-value config; Secrets hold sensitive data and are base64-encoded (NOT encrypted by default — encryption requires enabling encryption-at-rest in etcd). Both can be consumed as environment variables or mounted as files.

```yaml
apiVersion: v1
kind: ConfigMap
metadata: { name: app-config }
data:
  LOG_LEVEL: "info"
  config.yaml: |
    feature.x: true
---
apiVersion: v1
kind: Secret
metadata: { name: db-creds }
type: Opaque
stringData:                         # stringData auto-encodes; data must be base64
  username: appuser
  password: s3cr3t
---
# Pod consumption
spec:
  containers:
    - name: app
      image: myapp:1.0
      envFrom:
        - configMapRef: { name: app-config }
      env:
        - name: DB_PASSWORD
          valueFrom:
            secretKeyRef: { name: db-creds, key: password }
      volumeMounts:
        - { name: cfg, mountPath: /etc/app }
  volumes:
    - name: cfg
      configMap: { name: app-config }
```

**Security note**: prefer mounted-volume Secrets over env vars — env vars leak into crash dumps, child-process environments, and `kubectl describe`. Enable etcd encryption-at-rest and use RBAC to restrict who can read Secrets. For real secret management, integrate an external store (Vault, cloud Secrets Manager) via the Secrets Store CSI driver rather than storing long-lived credentials in etcd.

---

## 🟡 Intermediate (3–7 yrs)

### Q7. [Theory] How does a rolling update work, and how do you roll back?

A Deployment update creates a *new* ReplicaSet and gradually scales it up while scaling the old one down, governed by `maxSurge` (extra Pods allowed above desired) and `maxUnavailable` (Pods allowed to be down). This gives zero-downtime deploys *if* readiness probes are correct — the Service only routes to Pods that pass readiness, so traffic never hits a half-started Pod.

```yaml
spec:
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 25%
      maxUnavailable: 0          # safest: never drop below desired capacity
  minReadySeconds: 10            # Pod must stay ready this long before counting
```

Rollback: `kubectl rollout undo deployment/web` reverts to the previous ReplicaSet (Kubernetes keeps `revisionHistoryLimit` old ones, default 10). `kubectl rollout status` watches progress and `kubectl rollout pause/resume` enables canary-style staged rollouts. **Key insight**: a rollout only "completes" when the new ReplicaSet's Pods are ready — a broken readiness probe makes the rollout hang at `progressDeadlineSeconds`, which is exactly the safety behavior you want.

### Q8. [Theory] Explain requests, limits, and the three QoS classes.

**Requests** are what the scheduler reserves (and uses for bin-packing decisions); **limits** are hard ceilings enforced at runtime. CPU is *compressible* — exceeding the CPU limit throttles the process. Memory is *incompressible* — exceeding the memory limit gets the container **OOMKilled**.

```
QoS class     Condition                                          Eviction order
─────────────────────────────────────────────────────────────────────────────
Guaranteed    requests == limits for ALL containers (cpu & mem)  evicted LAST
Burstable     at least one request set, but not Guaranteed       evicted MIDDLE
BestEffort    no requests or limits at all                       evicted FIRST
```

QoS drives node-pressure eviction: when a node runs low on memory, the kubelet kills BestEffort Pods first, then Burstable Pods exceeding their requests, and protects Guaranteed Pods longest. **Production guidance**: always set memory requests=limits for latency-sensitive services (Guaranteed), and *avoid* CPU limits on most workloads — CPU throttling at the limit causes mysterious tail-latency spikes even when the node has spare CPU. Set CPU requests for fair scheduling and let bursting use idle capacity.

### Q9. [Coding] Write liveness, readiness, and startup probes and explain when each fires.

**Problem**: a JVM service takes up to ~2 minutes to boot, must not receive traffic until warmed, must be restarted only on a true hang, and must never be killed during its slow startup.

```yaml
spec:
  containers:
    - name: app
      image: myapp:2.1
      ports: [{ containerPort: 8080 }]
      startupProbe:                 # protects slow-starting apps from liveness
        httpGet: { path: /healthz, port: 8080 }
        failureThreshold: 30
        periodSeconds: 5            # allows up to 150s to boot
      readinessProbe:               # gates Service traffic; does NOT restart
        httpGet: { path: /ready, port: 8080 }
        periodSeconds: 5
        failureThreshold: 3
      livenessProbe:                # restarts the container on failure
        httpGet: { path: /healthz, port: 8080 }
        periodSeconds: 10
        failureThreshold: 3
        timeoutSeconds: 2
```

- **startupProbe** runs first; while it's failing, liveness/readiness are suspended. This prevents a slow-booting JVM from being killed by an aggressive liveness probe.
- **readinessProbe** controls whether the Pod's IP is in the Service endpoints. Failing it removes the Pod from rotation *without* restarting — perfect for shedding load during a transient dependency outage.
- **livenessProbe** restarts the container when it returns failure. This is the most dangerous probe to misconfigure: pointing liveness at an endpoint that checks a *downstream dependency* causes cascading restarts (the classic "liveness probe took down the whole fleet" incident). Liveness should only verify the process itself is alive, never external dependencies.

### Q10. [Practical] Design RBAC so a CI service account can deploy only in one namespace.

```yaml
apiVersion: v1
kind: ServiceAccount
metadata: { name: ci-deployer, namespace: team-a }
---
apiVersion: rbac.authorization.k8s.io/v1
kind: Role                          # namespaced; not cluster-wide
metadata: { name: deployer, namespace: team-a }
rules:
  - apiGroups: ["apps"]
    resources: ["deployments", "replicasets"]
    verbs: ["get", "list", "watch", "create", "update", "patch"]
  - apiGroups: [""]
    resources: ["pods", "services", "configmaps"]
    verbs: ["get", "list", "watch", "create", "update", "patch"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata: { name: ci-deployer-binding, namespace: team-a }
subjects:
  - kind: ServiceAccount
    name: ci-deployer
    namespace: team-a
roleRef:
  kind: Role
  name: deployer
  apiGroup: rbac.authorization.k8s.io
```

RBAC is **additive and deny-by-default**: there are no "deny" rules, only grants, and a request is allowed if *any* binding permits it. Use `Role`+`RoleBinding` for namespace scope and `ClusterRole`+`ClusterRoleBinding` for cluster-wide (or a `ClusterRole` referenced by a `RoleBinding` to reuse a definition per-namespace). **Security principle**: grant least privilege — never bind CI to `cluster-admin`, never grant `secrets: get` unless needed, and audit with `kubectl auth can-i --list --as=system:serviceaccount:team-a:ci-deployer`.

### Q11. [Theory] What is a StatefulSet and how does it differ from a Deployment?

A StatefulSet manages stateful, *identity-bearing* Pods. Unlike a Deployment (whose Pods are interchangeable cattle with random names), StatefulSet Pods get **stable, ordinal network identities** (`db-0`, `db-1`, `db-2`), are created/scaled/deleted in **order**, and each gets its **own persistent volume** via `volumeClaimTemplates` that survives rescheduling.

```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata: { name: pg }
spec:
  serviceName: pg-headless          # required: headless Service for stable DNS
  replicas: 3
  selector: { matchLabels: { app: pg } }
  template:
    metadata: { labels: { app: pg } }
    spec:
      containers:
        - name: postgres
          image: postgres:16
          volumeMounts: [{ name: data, mountPath: /var/lib/postgresql/data }]
  volumeClaimTemplates:             # one PVC per Pod, retained on rescheduling
    - metadata: { name: data }
      spec:
        accessModes: ["ReadWriteOnce"]
        storageClassName: fast-ssd
        resources: { requests: { storage: 50Gi } }
```

Each replica gets DNS `pg-0.pg-headless.<ns>.svc.cluster.local`, which is essential for clustered databases that need stable peer addresses (Postgres replicas, Kafka brokers, Zookeeper, Cassandra). The trade-off: StatefulSets are slower to scale (ordered, one at a time) and don't auto-delete PVCs on scale-down by default (a safety feature so you don't lose data accidentally).

### Q12. [Theory] Explain PV, PVC, and StorageClass — the storage triangle.

```
StorageClass ──(defines HOW to provision: provisioner, params, reclaimPolicy)
     │ dynamic provisioning
     ▼
PV (PersistentVolume) ── cluster resource: the actual disk (EBS/PD/NFS/...)
     ▲ binds 1:1
     │
PVC (PersistentVolumeClaim) ── a Pod's request: "I need 50Gi RWO" → bound to a PV
```

A **PVC** is a namespaced request for storage; a **PV** is the cluster-scoped piece of real storage. A **StorageClass** enables *dynamic provisioning*: instead of an admin pre-creating PVs, the CSI driver creates a backing disk on demand when a PVC is created. `reclaimPolicy: Retain` keeps the disk after PVC deletion (manual cleanup, safe); `Delete` removes it (convenient, risky for data). Access modes matter: `ReadWriteOnce` (RWO) = one node, the common case for block storage; `ReadWriteMany` (RWX) requires a network filesystem like NFS/EFS. The CSI (Container Storage Interface) standard replaced the old in-tree volume plugins.

### Q13. [Practical] How do you enforce that namespace A can only receive traffic from namespace B?

Network policies are **deny-by-default once any policy selects a Pod** — but only if your CNI enforces them (Calico, Cilium yes; flannel no). With no policy, all Pod-to-Pod traffic is allowed.

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: allow-from-team-b
  namespace: team-a
spec:
  podSelector: {}                   # all Pods in team-a
  policyTypes: ["Ingress"]
  ingress:
    - from:
        - namespaceSelector:
            matchLabels: { team: team-b }
      ports:
        - { protocol: TCP, port: 8080 }
```

This selects every Pod in `team-a` and permits ingress *only* from Pods in namespaces labeled `team: team-b` on TCP 8080; all other ingress is dropped. A common gotcha: namespaceSelector matches on **namespace labels**, so you must label the namespace (`kubectl label ns team-b team=team-b`). For zero-trust, start with a default-deny policy (`podSelector: {}`, empty ingress/egress) and add explicit allows. Cilium and other CNIs extend this with L7 (HTTP path/method) policies via CRDs.

### Q14. [Practical] An app needs to scale on CPU. Configure HPA and explain its limits vs VPA and Cluster Autoscaler.

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata: { name: web-hpa }
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: web
  minReplicas: 3
  maxReplicas: 20
  metrics:
    - type: Resource
      resource:
        name: cpu
        target: { type: Utilization, averageUtilization: 70 }
  behavior:                         # tune flapping (v2)
    scaleDown:
      stabilizationWindowSeconds: 300
```

```
HPA  ── changes the NUMBER of Pods (horizontal); needs metrics-server; CPU/mem/custom
VPA  ── changes the SIZE (requests/limits) of Pods (vertical); recreates Pods to apply
CA   ── changes the NUMBER of NODES; adds nodes when Pods are Pending, removes idle ones
```

The three operate at different layers and **HPA + VPA conflict on the same metric** (both reacting to CPU) — use HPA for CPU/RPS and VPA in "recommendation" mode, or VPA on memory while HPA handles CPU. HPA requires the metrics-server (resource metrics) or a custom/external metrics adapter (Prometheus Adapter, KEDA for event-driven scaling on queue depth). The Cluster Autoscaler (or Karpenter on AWS) sits below all of this: when HPA scales Pods up and they can't be scheduled (Pending due to insufficient resources), CA provisions new nodes. KEDA is increasingly the standard for scaling to/from zero based on Kafka lag, queue length, or cron schedules.

### Q15. [Theory] What are namespaces and ResourceQuotas, and why do they matter for multi-tenancy?

Namespaces are virtual clusters that partition names, RBAC scope, network policy scope, and quota — but **not** a hard security boundary (Pods in different namespaces can still reach each other without network policies, and they share the node kernel). A **ResourceQuota** caps aggregate resource consumption per namespace; a **LimitRange** sets per-Pod/container defaults and min/max.

```yaml
apiVersion: v1
kind: ResourceQuota
metadata: { name: team-a-quota, namespace: team-a }
spec:
  hard:
    requests.cpu: "10"
    requests.memory: 20Gi
    limits.cpu: "20"
    limits.memory: 40Gi
    pods: "50"
    persistentvolumeclaims: "10"
---
apiVersion: v1
kind: LimitRange
metadata: { name: defaults, namespace: team-a }
spec:
  limits:
    - type: Container
      default:        { cpu: 500m, memory: 256Mi }   # applied if not specified
      defaultRequest: { cpu: 100m, memory: 128Mi }
```

A subtle but critical rule: **once a ResourceQuota on `requests.cpu`/`memory` exists, every Pod in that namespace MUST specify requests/limits** or the API server rejects it. The LimitRange solves this by injecting defaults, which is why they're almost always deployed together. For hard tenant isolation, you need separate clusters or virtual clusters (vCluster), node isolation via taints, and strict network policies.

### Q16. [Coding] Write a CronJob that runs a backup every night and retains history.

**Problem**: schedule a job at 02:00 daily, prevent overlapping runs, time-box it, and keep a bounded history of successes/failures.

```yaml
apiVersion: batch/v1
kind: CronJob
metadata: { name: nightly-backup }
spec:
  schedule: "0 2 * * *"             # cron: min hour dom mon dow
  timeZone: "America/New_York"      # native tz support since 1.27 (stable)
  concurrencyPolicy: Forbid         # skip new run if previous still running
  startingDeadlineSeconds: 300      # if missed (e.g., controller down) skip after 5m
  successfulJobsHistoryLimit: 3
  failedJobsHistoryLimit: 3
  jobTemplate:
    spec:
      backoffLimit: 2               # retry the Job up to 2 times on failure
      activeDeadlineSeconds: 1800   # hard kill after 30 min
      template:
        spec:
          restartPolicy: Never      # Jobs require Never or OnFailure
          containers:
            - name: backup
              image: my-backup-tool:1.4
              args: ["--target", "s3://bucket/db"]
```

**Edge cases**: `concurrencyPolicy: Forbid` (skip), `Allow` (default — overlapping runs), or `Replace` (kill the old one). `startingDeadlineSeconds` matters when the controller was down — without it, a recovered controller may fire many missed jobs at once. Set `restartPolicy: Never` to make failed Pods countable against `backoffLimit` rather than restarting in place. **Complexity**: scheduling is O(1) per tick; history cleanup is bounded by the two history limits.

---

## 🟠 Advanced (8–12 yrs)

### Q17. [Theory] Walk through what happens, end to end, when you `kubectl apply` a Deployment.

```
1. kubectl   → builds request, auth (cert/token/OIDC) → sends to kube-apiserver (HTTPS)
2. apiserver → AuthN → AuthZ (RBAC) → Admission (mutating then validating webhooks,
               ResourceQuota, PodSecurity) → schema validation → writes object to etcd
3. etcd      → commits via Raft; apiserver returns 201 to kubectl
4. Deployment controller (watch) → sees new Deployment → creates a ReplicaSet
5. ReplicaSet controller (watch) → creates N Pod objects (status: Pending, no node)
6. Scheduler (watch unscheduled Pods) → filters (predicates) + scores (priorities) →
               writes a Binding (pod.spec.nodeName) back to apiserver
7. kubelet on chosen node (watch its own node's Pods) → calls CRI (containerd) to pull
               images, sets up CNI networking, mounts CSI volumes, starts containers
8. kubelet → runs probes, reports Pod status → apiserver → etcd
9. Endpoint/EndpointSlice controller → adds ready Pod IPs to the Service's EndpointSlice
10. kube-proxy / CNI dataplane on every node → programs iptables/IPVS/eBPF rules
```

The crucial mental model is the **level-triggered, watch-based, eventually-consistent** architecture: no component commands another directly. Everything watches the API server and reconciles. This is why Kubernetes is resilient — kill the scheduler and existing Pods keep running; only new scheduling stalls. Admission webhooks (step 2) are the extension point where policy engines (OPA Gatekeeper, Kyverno) and service meshes (sidecar injection) hook in.

### Q18. [Practical] Diagnose and fix a Pod stuck in CrashLoopBackOff.

`CrashLoopBackOff` means the container starts, exits/crashes, and the kubelet is backing off (exponential delay up to 5 min) before restarting. It is a *symptom*, not a root cause. Triage flow:

```bash
kubectl describe pod <p>            # Events: image pull? OOMKilled? probe failures? exit code
kubectl logs <p> --previous        # logs from the CRASHED instance (key flag!)
kubectl get pod <p> -o jsonpath='{.status.containerStatuses[0].lastState.terminated}'
```

Common root causes and fixes, by exit code / signal:
- **Exit 1 / app error**: missing env var, bad config, failed DB connection. Fix config; check `--previous` logs.
- **Exit 137 (128+9, SIGKILL) + reason OOMKilled**: container exceeded its memory limit → raise the limit or fix the leak.
- **Exit 143 (SIGTERM) on startup**: failing liveness probe killing a slow starter → add a `startupProbe`.
- **CreateContainerConfigError**: referenced ConfigMap/Secret missing.
- **Permission denied / read-only FS**: `securityContext` restrictions vs. what the app needs.

In production I'd avoid editing the live Pod: reproduce with `kubectl debug` (ephemeral container in the same namespaces) or run the image locally, fix the manifest, and roll forward. **Real case**: a JVM service crash-looping on a 512Mi limit because the JVM's default heap ergonomics ignored the cgroup — fixed by setting `-XX:MaxRAMPercentage=75` (Java 10+ is container-aware) and raising the limit; the symptom looked like an app bug but was a sizing/ergonomics mismatch.

### Q19. [Practical] Pods are stuck Pending. How do you systematically debug?

```
kubectl describe pod <p>  → read the Events / FailedScheduling message
        │
        ├─ "Insufficient cpu/memory" ───► no node has room. Check requests vs node
        │       allocatable; scale nodes (Cluster Autoscaler) or lower requests.
        ├─ "node(s) had untolerated taint" ─► add a toleration or remove the taint.
        ├─ "node(s) didn't match node affinity/selector" ─► fix nodeSelector/affinity.
        ├─ "pod has unbound immediate PersistentVolumeClaims" ─► PVC won't bind; check
        │       StorageClass exists, zone topology matches (WaitForFirstConsumer).
        ├─ "too many pods" / quota exceeded ─► ResourceQuota or maxPods per node hit.
        └─ "0/N nodes available: pod affinity rules" ─► relax anti-affinity.
```

The single most useful command is `kubectl describe pod` → the Events section literally tells you why the scheduler failed. The most common production cause is **resource requests larger than any node's allocatable capacity** (allocatable < capacity because the kubelet/system reserve a slice). For zonal PVCs, `volumeBindingMode: WaitForFirstConsumer` is essential — it defers PV creation until the Pod is scheduled, so the volume lands in the same zone as the Pod; `Immediate` binding causes Pending Pods when the volume's zone has no schedulable node.

### Q20. [Theory] What is an Operator and a CRD, and when should you build one?

A **CustomResourceDefinition (CRD)** extends the Kubernetes API with your own resource type (e.g., `kind: PostgresCluster`), stored in etcd and served by the API server like any built-in. An **Operator** is a custom controller that watches those CRs and encodes *operational domain knowledge* into a reconciliation loop — provisioning, backups, failover, version upgrades — turning a human runbook into automated software.

```yaml
apiVersion: apiextensions.k8s.io/v1
kind: CustomResourceDefinition
metadata: { name: postgresclusters.acme.io }
spec:
  group: acme.io
  scope: Namespaced
  names: { kind: PostgresCluster, plural: postgresclusters, shortNames: [pgc] }
  versions:
    - name: v1
      served: true
      storage: true
      schema:
        openAPIV3Schema:
          type: object
          properties:
            spec:
              type: object
              properties:
                replicas: { type: integer, minimum: 1, maximum: 9 }
                version:  { type: string }
              required: [replicas, version]
      subresources: { status: {} }     # enables spec/status split + /scale
```

Build an operator when operating a stateful system requires *ongoing, stateful decisions* a Deployment can't express — leader election, ordered upgrades, backup orchestration. Use frameworks like Kubebuilder/controller-runtime or the Operator SDK. **Don't** build one for simple stateless apps — a Helm chart or plain manifests is far cheaper. The pattern's power is also its risk: a buggy reconcile loop with broad RBAC can damage the cluster, so operators need careful idempotency, rate limiting, and finalizers for safe deletion.

### Q21. [Theory] Compare Ingress and the Gateway API. Why is Gateway API the future?

Ingress (the long-standing L7 HTTP routing object) has well-known limitations: it's HTTP/HTTPS-centric, vendor-specific features leak into nonportable annotations, and there's no clean separation between infra owners and app developers. The **Gateway API** (GA since 1.x, the recommended path in 2026) is its role-oriented, extensible successor.

```
INGRESS (one object, annotation soup)        GATEWAY API (role-separated)
─────────────────────────────────────        ──────────────────────────────────
 Ingress + ingress.class                       GatewayClass  (infra provider)
 nginx.ingress.kubernetes.io/* annotations        │
                                                Gateway       (cluster/infra team)
                                                   │
                                                HTTPRoute / TCPRoute / GRPCRoute
                                                   │           (app developers)
                                                Service
```

Gateway API splits responsibilities: a platform team owns the `GatewayClass`/`Gateway` (the listener, TLS, IP), while app teams own `HTTPRoute` objects (paths, headers, traffic splitting) — with RBAC enforcing the boundary. It natively supports header-based routing, weighted traffic splitting (canaries) without annotations, TCP/UDP/gRPC, and cross-namespace routing via `ReferenceGrant`. It's portable across implementations (Envoy Gateway, Istio, NGINX, cloud LBs) because behavior is in the spec, not annotations. New routing features now land in Gateway API; Ingress is in maintenance mode.

### Q22. [Coding] Implement a canary deployment with traffic splitting using Gateway API.

**Problem**: send 10% of traffic to a new version while 90% stays on stable, then ramp.

```yaml
apiVersion: gateway.networking.k8s.io/v1
kind: HTTPRoute
metadata: { name: app-canary, namespace: web }
spec:
  parentRefs:
    - { name: public-gateway }
  hostnames: ["app.example.com"]
  rules:
    - matches:
        - path: { type: PathPrefix, value: / }
      backendRefs:
        - { name: app-stable, port: 80, weight: 90 }
        - { name: app-canary, port: 80, weight: 10 }   # ramp to 50, then 100
```

Two Services (`app-stable`, `app-canary`) front two Deployments. The Gateway data plane splits requests by weight. To ramp, bump the canary weight (90/10 → 50/50 → 0/100) and watch SLOs (error rate, p99). In production you'd drive this with a progressive-delivery controller (Argo Rollouts or Flagger), which automates the weight steps, queries Prometheus for analysis, and **auto-rolls-back** if metrics breach thresholds — far safer than manual `kubectl` edits. **Edge cases**: ensure sticky sessions aren't required (or use header-based routing for deterministic cohorts), and pre-warm the canary so its first requests aren't slow-start outliers.

### Q23. [Theory] How does Pod-to-Pod and Service networking actually work under the hood?

Kubernetes mandates a flat network model: every Pod gets a routable IP, every Pod can reach every other Pod *without NAT*, and the IP a Pod sees as its own is the same others use to reach it. The **CNI** plugin implements this — overlay (VXLAN/Geneve, e.g., flannel/Calico-VXLAN) or native routing (Calico BGP, AWS VPC CNI assigning real VPC IPs, Cilium eBPF).

```
Pod A (10.244.1.5) ──► Service VIP (10.96.0.10, NOT a real interface) ──► one of:
                                                                  Pod X (10.244.2.7)
   kube-proxy/eBPF intercepts the VIP and DNATs to a backend Pod IP   Pod Y (10.244.3.9)
```

A Service ClusterIP is a *virtual* IP with no NIC behind it — `kube-proxy` programs iptables (or IPVS for O(1) lookup at scale), or a CNI like Cilium replaces kube-proxy entirely with eBPF for lower latency and better observability. The EndpointSlice API (which superseded the monolithic Endpoints object) lists ready backend Pod IPs and scales to thousands of endpoints by sharding. DNS resolution is handled by CoreDNS, which watches Services/Endpoints and answers `*.svc.cluster.local`. **Topology-aware routing** keeps traffic in-zone to cut cross-AZ cost and latency.

### Q24. [Coding] Schedule a workload across zones for HA, avoiding co-location on one node.

**Problem**: spread replicas evenly across availability zones and never put two replicas on the same node, so a node or AZ failure can't take out the service.

```yaml
spec:
  template:
    spec:
      topologySpreadConstraints:
        - maxSkew: 1                          # zones differ by at most 1 pod
          topologyKey: topology.kubernetes.io/zone
          whenUnsatisfiable: DoNotSchedule    # hard: refuse to violate spread
          labelSelector: { matchLabels: { app: web } }
        - maxSkew: 1
          topologyKey: kubernetes.io/hostname
          whenUnsatisfiable: ScheduleAnyway   # soft preference for node spread
          labelSelector: { matchLabels: { app: web } }
      affinity:
        podAntiAffinity:                       # alternative/stronger node spread
          requiredDuringSchedulingIgnoredDuringExecution:
            - topologyKey: kubernetes.io/hostname
              labelSelector: { matchLabels: { app: web } }
```

**Approaches compared**: `podAntiAffinity` with `required...` is the classic hard rule but scales poorly (O(pods²) scheduling cost) and can deadlock if replicas > nodes. `topologySpreadConstraints` (the modern, recommended approach) is more expressive and cheaper, letting you balance across multiple topology domains with `maxSkew`. **Edge cases**: with `DoNotSchedule` and `maxSkew: 1`, if a zone has no schedulable nodes you'll get Pending Pods — use `ScheduleAnyway` for soft balancing, or pair with a PodDisruptionBudget (`minAvailable: 2`) so voluntary disruptions (node drains, upgrades) never breach availability. **Complexity**: topology spread scoring is roughly O(pods × domains) per scheduling cycle, far better than anti-affinity's pairwise checks.

### Q25. [Theory] Explain PodDisruptionBudgets and how they interact with node maintenance.

A PodDisruptionBudget (PDB) limits how many Pods of an application can be *voluntarily* disrupted at once — protecting availability during node drains, cluster upgrades, and autoscaler scale-downs. It does **not** protect against *involuntary* disruptions (hardware failure, OOM kill).

```yaml
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata: { name: web-pdb }
spec:
  minAvailable: 2                   # or maxUnavailable: 1
  selector: { matchLabels: { app: web } }
```

When you `kubectl drain` a node, the eviction API respects the PDB: it won't evict a Pod if doing so would drop below `minAvailable`, blocking the drain until enough capacity exists elsewhere. This is why a cluster upgrade can stall — a misconfigured PDB (`minAvailable` equal to replica count, or a PDB on a single-replica Deployment) makes the node *undrainable*, a frequent on-call surprise. The fix is to ensure replicas > minAvailable and to size PDBs as a percentage. The Cluster Autoscaler also honors PDBs before removing nodes, which is essential for safe scale-down.

---

## 🔴 Expert (15+ yrs)

### Q26. [Theory] etcd is the cluster's source of truth. What are its failure modes and operational constraints?

etcd is a Raft-based, strongly-consistent store, so it requires a **quorum** (majority) of members to commit writes. This dictates odd-sized clusters (3, 5, 7): a 3-node cluster tolerates 1 failure, a 5-node tolerates 2. Even-sized clusters are pointless — 4 nodes still only tolerate 1 failure but cost more and have wider quorum latency. The practical constraints that bite at scale:

- **Write throughput is bounded by fsync latency** — etcd must durably persist to disk before acking. It is exquisitely sensitive to disk latency; put it on dedicated low-latency SSDs, never network storage with variable latency.
- **Total DB size** should stay under ~8 GB (default quota 2 GB, raisable). A full DB puts the cluster into a maintenance/alarm state, rejecting writes until compacted and defragmented.
- **Watch/event storms**: high churn (e.g., thousands of Pods restarting, a misbehaving controller hot-looping updates) generates massive watch traffic that can overwhelm the API server and etcd. This is a top cause of large-cluster meltdowns.
- **Backups**: take regular `etcdctl snapshot save`. Disaster recovery is *restore etcd, then everything else reconciles*. Test restores — an untested backup is a hope, not a plan.

Mitigations: compaction/defrag schedules, request rate limiting and API priority & fairness (APF) on the API server, separating events into their own etcd instance, and capping object counts (the practical ceiling is roughly 5,000 nodes / 150,000 Pods per cluster before you should shard into multiple clusters).

### Q27. [Practical] A large cluster's API server is at high latency and controllers are lagging. Diagnose.

```
Symptoms: kubectl slow, rollouts stall, HPA/scheduler lag, "etcdserver: request timed out"
Investigate (in order):
  1. apiserver metrics: apiserver_request_duration_seconds (by verb/resource),
     apiserver_current_inflight_requests, dropped requests (APF)
  2. etcd metrics: etcd_disk_wal_fsync_duration_seconds (>10ms p99 = disk problem),
     etcd_server_leader_changes_seen_total (flapping leader = network/disk),
     etcd db size & compaction lag
  3. Find the abuser: which client/controller is generating LIST/WATCH storms?
     (audit logs, apiserver_request_total by user-agent)
```

The usual culprits: a controller doing **expensive unbounded LISTs** instead of using informers/watch caches; a CRD controller hot-looping (writing status every reconcile, triggering its own watch); a monitoring agent listing all Pods cluster-wide every few seconds; or etcd on slow disks. Fixes I'd apply: enable/tune **API Priority and Fairness** to isolate and throttle the noisy client, fix controllers to use **field/label selectors and resourceVersion-based watches with paginated LISTs**, move events to a separate etcd, add request caching, and if the cluster is simply too big, **shard into multiple clusters** with a federation/fleet layer. The meta-lesson: at scale, the control plane is a shared database, and one badly-behaved controller is a noisy-neighbor DoS.

### Q28. [Theory] Design a secure-by-default multi-tenant cluster. What's your defense-in-depth stack?

```
Layer 0  Cluster topology   → hard tenants = separate clusters; soft = namespaces + vCluster
Layer 1  AuthN              → OIDC/SSO, short-lived tokens, no static kubeconfigs, no shared SAs
Layer 2  AuthZ (RBAC)       → least privilege, namespaced Roles, no wildcard verbs, audit can-i
Layer 3  Admission policy   → Pod Security Admission (restricted), Kyverno/OPA for org policy
Layer 4  Runtime isolation  → seccomp/AppArmor, drop ALL caps, runAsNonRoot, readOnlyRootFS,
                              gVisor/Kata for untrusted workloads, no hostPath/hostNetwork
Layer 5  Network            → default-deny NetworkPolicy, namespace isolation, mTLS via mesh
Layer 6  Secrets            → etcd encryption-at-rest, external secret store (Vault/CSI), rotation
Layer 7  Supply chain       → signed images (cosign/sigstore), admission verification, SBOMs,
                              scanning (Trivy), pinned digests not :latest
Layer 8  Audit & detect     → audit logging to SIEM, Falco runtime detection, drift detection
```

PodSecurityPolicy was removed in 1.25; the replacement is **Pod Security Admission** (built-in, three levels: privileged/baseline/restricted, applied via namespace labels) plus a policy engine (**Kyverno** or **OPA Gatekeeper**) for organization-specific rules admission webhooks can't express via PSA alone. The non-obvious truths: namespaces are *not* a security boundary against kernel exploits (shared kernel), so genuinely hostile multi-tenancy needs sandboxed runtimes (gVisor/Kata) or separate clusters; and the **container escape → node → cluster** path means a compromised privileged Pod with the node's kubelet credentials can pivot to the whole cluster — which is why blocking `hostPath`, `privileged`, and `hostPID/hostNetwork`, and using node-restriction admission, is non-negotiable.

### Q29. [Practical] Walk through a production incident you'd treat as a learning case: a cluster-wide outage from a "harmless" change.

**Scenario (classic real-world pattern)**: A team tightens a liveness probe `timeoutSeconds` from 5s to 1s "to fail faster." During a routine traffic spike, GC pauses push response times above 1s, liveness probes start failing fleet-wide, the kubelet restarts containers en masse, restarts trigger cold caches and slow startup, which fail *more* probes — a self-reinforcing cascade. The Service loses most endpoints; an outage ensues even though no code changed and the nodes were healthy.

Approach: (1) **Stop the bleed** — `kubectl rollout undo` / revert the probe change and, if needed, temporarily disable liveness to let Pods stabilize. (2) **Diagnose** — `describe`/events show `Liveness probe failed` + restart counts climbing; correlate with the probe-config commit. (3) **Root cause** — liveness was conflated with "is it fast enough" (an SLO concern) rather than "is the process deadlocked" (its actual job). (4) **Fix forward** — separate concerns: readiness sheds load gracefully, liveness only catches true deadlocks with generous thresholds, add a startupProbe, and set CPU requests so the scheduler doesn't oversubscribe. **Blameless lessons**: liveness probes are a loaded gun aimed at your own fleet; test config changes under load; and add guardrails (admission policy rejecting liveness `timeoutSeconds` below a floor). This mirrors well-documented postmortems where aggressive health checks amplified, rather than contained, a transient overload.

### Q30. [Behavioral] How do you drive Kubernetes adoption across many teams without becoming a bottleneck?

The failure mode is the platform team manually reviewing every manifest and approving every namespace — a human bottleneck that scales linearly with teams and burns out. The winning strategy is **platform-as-a-product with paved roads**: build golden-path templates (Helm charts / Kustomize bases / a Backstage scaffolder) that encode best practices (probes, requests/limits, PDBs, security contexts) so the easy path is the correct path. Enforce the non-negotiables with **policy-as-code** (Kyverno/Gatekeeper) in admission and CI rather than human review — the cluster says no, consistently, at 3 a.m., without you.

I'd invest in self-service (GitOps via Argo CD/Flux so teams deploy by merging PRs, with the platform owning the guardrails), strong defaults and quotas per namespace, and excellent docs/office-hours over gatekeeping. Measure adoption and toil (deploy frequency, time-to-first-deploy, incidents caused by config), and treat internal teams as customers whose friction is *your* bug. Critically, distinguish **mandatory** policy (security, cost guardrails — enforced) from **recommended** practice (nudged via templates) so you're not the team of "no." The behavioral signal interviewers want: you scale *yourself* by building leverage (tooling, policy, docs), not by being in every loop.

### Q31. [Theory] When should an organization NOT use Kubernetes, and what are the alternatives?

A staff engineer's job includes saying "not Kubernetes." K8s carries a heavy operational tax: control-plane upgrades, CNI/CSI/ingress lifecycle, security patching, capacity planning, and a steep learning curve. If you have a handful of stateless services and a small team, that tax dwarfs the benefit. Reach for simpler tools first:

- **Single app / small team** → a managed PaaS (Cloud Run, App Runner, Fly.io, Render, Heroku-style) or even a VM with systemd + a reverse proxy. Zero cluster to operate.
- **Serverless/event-driven, spiky traffic** → Functions/Lambda or container-on-demand; scale-to-zero without running nodes 24/7.
- **Batch/data pipelines** → managed orchestrators (Airflow/Dataflow) rather than hand-rolling Jobs.
- **You DO want K8s when** → many services, multiple teams needing self-service, complex networking/scaling, portability across clouds/on-prem, or an ecosystem (operators, meshes, GitOps) you'll genuinely use.

Even when you choose K8s, prefer **managed control planes** (EKS/GKE/AKS) over self-managed — running etcd and the API server yourself is rarely worth it. The mature judgment is matching tool complexity to problem complexity and team capacity, not adopting K8s because it's the default résumé item. Many "we need Kubernetes" requests are really "we need reproducible deploys and autoscaling," which simpler platforms deliver at a fraction of the cost.

---

## 🧩 Extended Questions — Set 1: Deep Theory & Internals

### 🟢 Basic — extended

#### Q32. [Theory] What is the "pause" (infra/sandbox) container and why does every Pod have one?

When you list processes on a node you'll find a tiny container per Pod running an image like `registry.k8s.io/pause`. This is the **infrastructure container** (a.k.a. sandbox container). It exists to *own and hold open the Pod's shared Linux namespaces* — primarily the network namespace (the Pod's IP), and historically the IPC and sometimes PID namespaces. The pause container's only job is to `pause()` itself: it reaps zombie processes (it's PID 1 in the Pod's PID namespace when shared) and does nothing else, consuming almost no resources.

The reason it must exist separately from your app containers is **lifecycle decoupling**. The Pod's identity — its IP, its network setup done by the CNI — must persist even when individual app containers crash and restart. If the network namespace were owned by your app container, every crash would tear down and re-establish networking (a new IP each restart). Instead, the kubelet creates the pause container first, the CRI runtime sets up the network namespace against it via CNI, and then app containers *join* that existing namespace. Your app container can `CrashLoopBackOff` a hundred times and the Pod keeps the same IP because the pause container never died.

```
Pod "web"
 ┌──────────────────────────────────────────────┐
 │  pause container  ← owns netns (IP 10.244.1.5)│
 │     ▲        ▲                                 │
 │     │ join   │ join                            │
 │  nginx     log-sidecar   (share IP, can       │
 │            (containers restart independently;  │
 │             netns survives because pause does) │
 └──────────────────────────────────────────────┘
```

This is also why "restartPolicy" applies per-container but the Pod's IP is Pod-level: the sandbox is the unit of network identity. Modern runtimes (containerd, CRI-O) manage this transparently via the CRI `RunPodSandbox`/`CreateContainer` split — `RunPodSandbox` creates the pause container and networking, and only then are the workload containers created inside it.

#### Q33. [Theory] What's the difference between CRI, CNI, and CSI, and why does Kubernetes define interfaces instead of implementations?

These three are the pluggable interface boundaries that keep the Kubernetes core decoupled from any specific vendor technology. **CRI (Container Runtime Interface)** is a gRPC API the kubelet uses to manage containers and sandboxes — it's what lets the kubelet talk to containerd, CRI-O, or others without caring which. **CNI (Container Network Interface)** is a much simpler spec (executable plugins + JSON config) the runtime invokes to attach a Pod's network namespace to the cluster network — Calico, Cilium, flannel, and cloud CNIs all implement it. **CSI (Container Storage Interface)** is a gRPC API for provisioning and mounting volumes, letting any storage vendor write a driver that works across orchestrators.

```
                kubelet
                  │ CRI (gRPC: RunPodSandbox, CreateContainer, ...)
                  ▼
           containerd / CRI-O
              │            │
         CNI (exec)    CSI (gRPC)
              ▼            ▼
     Calico/Cilium    EBS/PD/NFS driver
```

The architectural reason is the **dependency-inversion / strangler pattern applied to a platform**. Originally Kubernetes had Docker baked in ("dockershim"), in-tree volume plugins, and kubenet networking — every storage and network vendor had to get code merged into core Kubernetes, ballooning the codebase and coupling release cycles. By extracting stable interfaces, the project moved vendor code *out* of tree: in-tree volume plugins were migrated to CSI, and **dockershim was removed in Kubernetes 1.24** (Docker Engine itself is no longer a directly supported runtime; you use containerd or CRI-O, or the `cri-dockerd` shim). Now vendors ship and release drivers independently, the core stays lean, and you can swap any layer without forking Kubernetes.

A useful interview nuance: CNI is invoked *once per Pod* by the runtime at sandbox creation (it's not a long-running daemon in the data path — though plugins like Cilium *also* run a daemon for policy/eBPF), whereas CRI and CSI are persistent gRPC services. This is why a CNI misconfiguration shows up as Pods stuck in `ContainerCreating` with `NetworkPlugin cni failed to set up pod` events rather than as a crashing daemon.

#### Q34. [Theory] How do labels, selectors, and annotations differ semantically, and why does the distinction matter?

**Labels** are key-value identifying metadata meant to be *queried and selected on*; they are indexed by the API server so selectors are efficient. **Selectors** are the query language over labels — equality-based (`app=web`) or set-based (`env in (prod,staging)`). **Annotations** are also key-value but are *not* selectable, not indexed, and intended for arbitrary non-identifying metadata: build IDs, change-cause, tool configuration, checksums, last-applied-config.

The semantic line is **"is this used to group/select objects, or is it just data attached to them?"** Labels drive the core machinery: a Service finds its Pods via a label selector, a Deployment's `spec.selector` claims its ReplicaSet's Pods, a NetworkPolicy's `podSelector` chooses targets, node affinity matches node labels. Because these selectors are evaluated constantly and at scale, labels are deliberately constrained (limited length, validated syntax) and indexed. Annotations carry data that *no controller selects on* but tools or humans read — e.g., `kubectl.kubernetes.io/last-applied-configuration` (how `kubectl apply` does three-way merges), `deployment.kubernetes.io/revision`, ingress controller config, or a config-checksum annotation you bump to force a rolling restart when a ConfigMap changes.

```
                 SELECTABLE?   INDEXED?   TYPICAL USE
labels            yes           yes        grouping, Service→Pod, scheduling, policy
annotations       no            no         build metadata, tool config, checksums, hints
```

A subtle but high-impact rule: a Deployment's `spec.selector` is **immutable** after creation. Because controllers use the selector to claim ownership via `ownerReferences`, changing it would orphan existing ReplicaSets/Pods and create overlapping ownership chaos — so the API server simply forbids it. This is why "I can't change my Deployment's labels" is a FAQ: you must delete and recreate. The practical lesson is to choose label schemas carefully up front (the `app.kubernetes.io/*` recommended set helps) and use annotations for anything that will churn.

### 🟡 Intermediate — extended

#### Q35. [Theory] Explain the scheduler's two-phase Filter/Score model and how the scheduling framework works.

The kube-scheduler picks a node for each unscheduled Pod in two phases. **Filtering (predicates)** eliminates nodes that *cannot* run the Pod — insufficient allocatable CPU/memory, unsatisfied `nodeSelector`/affinity, untolerated taints, volume zone/topology conflicts, port conflicts. **Scoring (priorities)** ranks the surviving feasible nodes 0–100 across multiple plugins, sums weighted scores, and binds the Pod to the highest scorer (ties broken randomly). Default scoring favors things like spreading Pods of the same Service, balancing resource utilization (`NodeResourcesBalancedAllocation`), and image locality (a node that already has the image scores higher to save pull time).

```
unscheduled Pod
   │
   ▼  PreFilter → Filter (predicates): N nodes → feasible set F
   │       e.g. Fit, NodeAffinity, TaintToleration, VolumeBinding, PodTopologySpread
   ▼  PreScore → Score (priorities): each node in F → weighted sum
   │       e.g. ImageLocality, InterPodAffinity, NodeResourcesBalancedAllocation
   ▼  Reserve → Permit → PreBind → Bind  (writes pod.spec.nodeName)
```

Since Kubernetes 1.19 this is all implemented as the **scheduling framework**: a set of extension points (QueueSort, PreFilter, Filter, PreScore, Score, Reserve, Permit, PreBind, Bind, PostBind) where in-tree behaviors and custom plugins register. This replaced the old monolithic predicates/priorities and the awkward "scheduler extender" webhook model — plugins run in-process (fast) rather than over HTTP. You configure them with a `KubeSchedulerConfiguration` profile, can run multiple profiles (Pods pick via `schedulerName`), and can compile in custom plugins for specialized placement (e.g., gang scheduling, GPU/NUMA topology).

The mental model that matters: **the scheduler only decides placement; it does not run anything.** It writes a Binding (just sets `nodeName`) back to the API server, and the kubelet on that node does the actual work by watching for Pods assigned to it. This separation is why a Pod can be "scheduled" (has a nodeName) yet still `ContainerCreating` — those are two different actors at two different stages. It's also why the scheduler is stateless and can be killed/restarted freely: it rebuilds its view from the API server's watch cache.

#### Q36. [Theory] What is preemption, and how do PriorityClasses change scheduling and eviction?

A **PriorityClass** assigns an integer priority to Pods (via `priorityClassName`). When a high-priority Pod can't be scheduled because every node is full, the scheduler triggers **preemption**: it looks for a node where *evicting one or more lower-priority Pods* would make room, then deletes (gracefully, respecting `terminationGracePeriodSeconds`) those victims so the high-priority Pod can bind. This is distinct from node-pressure eviction (which the kubelet does under resource pressure based on QoS) — preemption is a *scheduler* action driven by priority to satisfy a pending higher-priority Pod.

```yaml
apiVersion: scheduling.k8s.io/v1
kind: PriorityClass
metadata: { name: critical-payments }
value: 1000000
preemptionPolicy: PreemptLowerPriority   # or "Never" = high prio but won't evict others
globalDefault: false
description: "Customer-facing payment services"
```

Priority also influences **scheduling order** (higher-priority Pods are dequeued first from the scheduling queue) and **node-pressure eviction ordering** (within the same QoS considerations, the kubelet evicts lower-priority Pods first). Two reserved system classes exist: `system-cluster-critical` and `system-node-critical` (the highest, protecting things like CoreDNS and the CNI agent so cluster infrastructure isn't preempted). The `preemptionPolicy: Never` option is valuable for batch/high-priority-but-polite workloads that should jump the *queue* without *evicting* running Pods.

The trade-offs interviewers probe: preemption can cause **cascading disruption** if priorities are sprawled across many tiers, and it interacts with PDBs (the scheduler tries to respect PodDisruptionBudgets during preemption but will violate them as a last resort to schedule a critical Pod — availability of the victim is sacrificed for the higher-priority Pod). Best practice is a small, deliberate set of priority bands (e.g., system-critical, customer-facing, default, batch/best-effort), not a free-for-all where every team marks itself "critical" — priority inflation defeats the entire mechanism.

#### Q37. [Theory] How do informers, the watch cache, and resourceVersion make controllers efficient and correct?

Naively, a controller could poll the API server (`LIST` everything every few seconds), but that would crush the API server and etcd at scale. Instead, controllers use **informers** (from client-go). An informer does an initial `LIST` to build a local in-memory cache (the "store"), then opens a **WATCH** that streams incremental changes (Added/Modified/Deleted) keyed by **resourceVersion** — a monotonic token (backed by etcd's MVCC revision) that marks a position in the change stream. The controller reads from its local cache for free and only reacts to deltas.

```
API server (watch cache, fed from etcd MVCC)
   │  initial LIST (resourceVersion=R)   then  WATCH from R
   ▼
client-go Reflector → DeltaFIFO → Indexer/Store (local cache)  →  workqueue → reconcile()
                                              ▲ shared by multiple controllers
```

`resourceVersion` is the linchpin of correctness. Every object carries it; a WATCH resumes "from this version forward," so no events are missed across reconnects. It also powers **optimistic concurrency**: an update sends the object's resourceVersion, and the API server rejects the write with a 409 Conflict if it changed in the meantime (someone else wrote first) — there are no locks, just compare-and-swap. This is why controllers must handle conflicts by re-reading and retrying. To survive WATCH disconnects without a full re-LIST, modern client-go and the API server support **watch bookmarks** (periodic "you're caught up to version R" events) and resilient relisting.

Two scaling refinements worth naming: the API server keeps its own **watch cache** so thousands of watchers don't each hit etcd; and **shared informers** mean many controllers in one process share a single watch/cache per resource type rather than each opening their own. The classic anti-pattern (and a top cause of control-plane overload, as in the large-cluster question earlier) is a controller doing repeated unbounded `LIST`s or not using a shared informer — it bypasses all this machinery and turns a cheap delta stream into a firehose of full reads.

#### Q38. [Theory] Explain owner references, cascading deletion, and finalizers — how does garbage collection actually work?

Kubernetes objects form ownership trees via **`metadata.ownerReferences`**: a ReplicaSet owns its Pods, a Deployment owns its ReplicaSets, a CronJob owns its Jobs. When you create a Pod through a Deployment, the controllers stamp ownerReferences down the chain. The **garbage collector** controller watches these references: when an owner is deleted, it cleans up the owned ("dependent") objects. This is **cascading deletion**, and it has three propagation policies:

```
Foreground  → owner marked deleting, dependents deleted FIRST, then owner removed
Background  → owner removed immediately, dependents cleaned up asynchronously (default)
Orphan      → owner deleted, dependents kept (ownerReferences stripped) — orphaned
```

```bash
kubectl delete deployment web                          # Background (default)
kubectl delete deployment web --cascade=orphan          # keep the Pods
kubectl delete deployment web --cascade=foreground      # block until children gone
```

**Finalizers** are the other half of the deletion machinery. A finalizer is a string key in `metadata.finalizers`; while any finalizer is present, a delete request does **not** remove the object — it only sets `metadata.deletionTimestamp` (the object enters "Terminating"). The controller that owns that finalizer is expected to do cleanup (release a cloud load balancer, deprovision a volume, deregister from an external system) and then *remove its finalizer key*, at which point the API server actually deletes the object. This is how Kubernetes does **reliable pre-deletion hooks** without distributed transactions.

The operational gotcha — and a frequent on-call mystery — is a **namespace or resource stuck in "Terminating" forever** because a finalizer's controller is gone or wedged (e.g., an uninstalled operator left a finalizer, or a CRD's controller crashed). The object can't be deleted until the finalizer is removed. The correct fix is to fix/reinstall the controller so it does its cleanup; the blunt-force escape hatch is to patch out the finalizer (`kubectl patch ... -p '{"metadata":{"finalizers":[]}}' --type=merge`), but that *skips* the cleanup the finalizer was protecting, potentially leaking external resources. Understanding this distinction — ownerReferences/GC vs. finalizers — separates people who've operated clusters from people who've only read about them.

#### Q39. [Theory] kube-proxy: iptables vs IPVS vs eBPF (kube-proxy-less). What are the trade-offs?

kube-proxy turns the abstract Service VIP into real packet forwarding by programming the node's dataplane to DNAT a Service ClusterIP to one of its backend Pod IPs. It has three modes with very different scaling characteristics.

```
                 iptables mode            IPVS mode               eBPF (Cilium/no kube-proxy)
rule structure   linear chains            hash tables (in-kernel  eBPF maps + programs at
                 per Service/endpoint     LB: rr, lc, wrr, ...)   socket/tc/XDP hooks
match cost       O(n) worst case          O(1) lookup             O(1) hash, often bypasses
                                                                  conntrack & iptables entirely
rule update      rewrites large rulesets  incremental ipset       map update, no ruleset rewrite
                 (slow with 1000s of svc) updates                 (fast at scale)
LB algorithms    random/round-robin only  many (lc, sh, dh, ...)  configurable; DSR possible
best for         small/medium clusters    large clusters,         large/perf-sensitive clusters,
                 (default, simplest)      many Services           rich observability/policy
```

**iptables mode** (the historical default) installs a chain of rules; matching a packet is roughly linear in the number of Services/endpoints, and every change rewrites large portions of the ruleset. At a few thousand Services this becomes a real problem: rule sync latency climbs into seconds, and connection setup adds latency. **IPVS mode** uses the kernel's in-kernel L4 load balancer with hash-table lookups (O(1)) and incremental updates via ipsets, plus real LB algorithms (least-conn, weighted) — markedly better at scale, at the cost of needing kernel IPVS modules.

The modern frontier is **eBPF-based, kube-proxy-less** datapaths (Cilium being the flagship). Instead of iptables/IPVS, the CNI attaches eBPF programs at the socket and traffic-control/XDP hooks that do Service load balancing in-kernel with hash maps, often performing the DNAT at the *socket* layer so you skip per-packet NAT and even conntrack for many flows. Benefits: lower latency, no large ruleset to churn, native L7-aware policy and deep observability (Hubble), and direct-server-return options. Trade-offs: requires a recent kernel, ties you to that CNI's dataplane, and is more complex to debug if you don't know eBPF. The interview-worthy summary: *the Service abstraction is identical, but the dataplane implementation determines your connection-setup latency and how the control plane behaves at thousands of Services.*

#### Q40. [Theory] How does CoreDNS resolve `svc.cluster.local`, and what do `ndots` and DNSPolicy have to do with latency?

CoreDNS is the cluster DNS server (a Deployment behind a ClusterIP Service, usually `10.96.0.10`). Its `kubernetes` plugin watches Services and EndpointSlices via the API and answers cluster-internal names: a normal Service resolves `myservice.myns.svc.cluster.local` to the ClusterIP (an A/AAAA record), while a **headless** Service returns the individual Pod IPs, and SRV records expose ports. Pods are configured (by the kubelet, per `dnsPolicy: ClusterFirst`, the default) to use CoreDNS as their resolver, with a search-domain list injected into `/etc/resolv.conf`.

```
/etc/resolv.conf inside a Pod in namespace "web":
  nameserver 10.96.0.10
  search web.svc.cluster.local svc.cluster.local cluster.local
  options ndots:5
```

Here's the latency trap. `ndots:5` means: *if a queried name has fewer than 5 dots, try it with each search domain appended FIRST before trying it as an absolute name.* So a lookup of `api.example.com` (2 dots) becomes a sequence: `api.example.com.web.svc.cluster.local` (NXDOMAIN), `api.example.com.svc.cluster.local` (NXDOMAIN), `api.example.com.cluster.local` (NXDOMAIN), and finally `api.example.com.` (success). That's **four queries (×2 for A and AAAA = up to eight)** for one external hostname — multiplied across high request rates, this hammers CoreDNS and adds tail latency.

Mitigations and the reasoning behind each: append a trailing dot to make a name **fully qualified** (`api.example.com.`) so it skips the search list entirely; lower `ndots` via `dnsConfig` for Pods that mostly talk externally; cache aggressively (the CoreDNS `cache` plugin, and especially **NodeLocal DNSCache**, a per-node DNS cache DaemonSet that eliminates cross-node DNS hops and conntrack pressure for UDP); and use the right `dnsPolicy` (`ClusterFirst` for normal Pods; `Default` to inherit the node's resolver; `None` + `dnsConfig` for full control). This question separates people who've debugged real DNS latency from those who think "Kubernetes DNS just works."

#### Q41. [Theory] What is a DaemonSet, how does its scheduling differ, and how are its Pods updated?

A **DaemonSet** ensures a copy of a Pod runs on every (matching) node — the canonical home for node-level agents: CNI pods, kube-proxy, log collectors (Fluent Bit), node exporters, CSI node plugins, security agents. Unlike a Deployment where you pick a replica count and the scheduler places them anywhere, a DaemonSet's count is *implicitly the number of matching nodes*: add a node and a Pod appears on it automatically; cordon/remove a node and its DaemonSet Pod goes away.

```yaml
apiVersion: apps/v1
kind: DaemonSet
metadata: { name: node-exporter }
spec:
  selector: { matchLabels: { app: node-exporter } }
  updateStrategy:
    type: RollingUpdate
    rollingUpdate: { maxUnavailable: 1 }   # update N nodes at a time
  template:
    metadata: { labels: { app: node-exporter } }
    spec:
      tolerations:
        - { operator: "Exists" }            # run even on tainted/control-plane nodes
      containers:
        - name: node-exporter
          image: prom/node-exporter:v1.8.0
```

Scheduling is the interesting internal detail. Historically the DaemonSet controller bypassed the scheduler and placed Pods directly; since ~1.12 it uses the **default scheduler with `NodeAffinity`** — the controller creates Pods that already have a node affinity term pinning them to a specific node, plus `nodeAffinity`/tolerations so they land on every node including tainted control-plane nodes (DaemonSets commonly tolerate everything via `operator: Exists`). This means DaemonSet Pods now respect taints, resource availability, and the scheduling framework like any other Pod — a node without enough allocatable resources will leave its DaemonSet Pod `Pending`.

Updates use `updateStrategy` (`RollingUpdate` with `maxUnavailable`/`maxSurge`, or `OnDelete` where you manually delete Pods to trigger replacement — useful for sensitive node agents you want to roll one-by-one under human control). Because these are node infrastructure, they typically carry `system-node-critical` priority so they aren't preempted, and they often need `hostNetwork`, `hostPath`, or elevated capabilities — which is exactly why your admission policy must *allowlist* DaemonSets for those privileges while denying them to normal workloads.

#### Q42. [Practical] Explain graceful Pod termination step by step, and how preStop hooks prevent dropped connections.

When a Pod is deleted (rolling update, scale-down, node drain), Kubernetes runs a precise **termination sequence**, and understanding the ordering is the difference between zero-downtime and 502s.

```
1. Pod marked Terminating (deletionTimestamp set); grace period clock starts
2. IN PARALLEL:
   a) Pod removed from Service EndpointSlices → kube-proxy/CNI stops sending NEW traffic
   b) kubelet runs preStop hook (if any), THEN sends SIGTERM to PID 1
3. App should: stop accepting new conns, drain in-flight requests, then exit
4. If still alive after terminationGracePeriodSeconds (default 30s) → SIGKILL
```

The subtle, race-prone part: step 2a (endpoint removal) and step 2b (SIGTERM) happen **concurrently and asynchronously**, propagated by *different* controllers across *different* nodes. Endpoint removal must reach every node's kube-proxy/dataplane and every DNS cache before traffic truly stops — but SIGTERM may arrive at your container first. If your app exits immediately on SIGTERM, in-flight requests and requests already in transit (sent before the dataplane updated) get connection-refused → **dropped requests during every deploy**.

The fix is a **`preStop` hook** that sleeps briefly to let endpoint removal propagate before the app starts shutting down:

```yaml
spec:
  terminationGracePeriodSeconds: 45
  containers:
    - name: app
      lifecycle:
        preStop:
          exec: { command: ["/bin/sh", "-c", "sleep 5"] }   # let endpoints drain
      # app must also handle SIGTERM: stop accepting, finish in-flight, exit
```

The full recipe for graceful shutdown: (1) `preStop` sleep (5–15s typical) so the dataplane stops routing new traffic; (2) the app traps SIGTERM and enters connection-draining mode (fail readiness, finish in-flight, close keep-alives); (3) set `terminationGracePeriodSeconds` longer than the worst-case drain + preStop. A common bug is the app being PID 1 with no signal handling (shells don't forward signals) — run with proper init/`tini` or exec-form CMD so SIGTERM actually reaches your process. Get this wrong and every rollout silently sheds a fraction of requests; get it right and deploys are invisible to users.

### 🟠 Advanced — extended

#### Q43. [Theory] Walk through the admission control chain. Why does mutating run before validating, and what risks do webhooks add?

After a request passes authentication and authorization, it enters **admission control** before being persisted to etcd. Admission has two flavors and a strict ordering: **mutating** admission runs first (it can *change* the object — inject sidecars, add default labels, set securityContext), then the object is **schema-validated**, then **validating** admission runs (it can only accept/reject, not modify). The ordering is logically necessary: validation must run on the *final* form of the object, so all mutation must complete before any binding validation decision.

```
request → AuthN → AuthZ → ┌─ MUTATING admission ──────────────┐ → schema/quota
                          │  built-in mutators, then           │   validation
                          │  MutatingWebhookConfiguration(s)    │      │
                          └────────────────────────────────────┘      ▼
                          ┌─ VALIDATING admission ─────────────┐ → write to etcd
                          │  built-in validators (PodSecurity,  │
                          │  ResourceQuota), then               │
                          │  ValidatingWebhookConfiguration(s)  │
                          └─────────────────────────────────────┘
```

The extensibility comes from **dynamic admission webhooks**: you register a `MutatingWebhookConfiguration`/`ValidatingWebhookConfiguration` that names an HTTPS endpoint the API server calls (with an `AdmissionReview` payload) for matching resources. This is how Istio injects sidecars (mutating), how cert-manager and Kyverno/OPA Gatekeeper enforce policy (both), and how many operators default fields. Within each phase, mutating webhooks may run multiple passes for reinvocation (`reinvocationPolicy`) so later mutations don't get clobbered.

The risks are precisely what make this a senior topic. A webhook sits **synchronously in the write path of the API server** — every matching API call blocks on an HTTP round-trip to your webhook. So: (1) **`failurePolicy: Fail`** means if your webhook pod is down, *all* matching API writes fail — a webhook that matches `pods` cluster-wide with `failurePolicy: Fail` can brick the cluster (you can't even create the Pods that would restore the webhook); (2) latency in the webhook directly inflates API-server latency; (3) a webhook matching its *own* dependencies can deadlock. Mitigations: scope `namespaceSelector`/`objectSelector` tightly, exclude the `kube-system` namespace and the webhook's own namespace, set sane `timeoutSeconds`, prefer `failurePolicy: Ignore` for non-critical mutators, and run webhook backends HA. For policies that don't need to call out, **ValidatingAdmissionPolicy** (CEL-based, in-tree, GA in 1.30) and the newer **MutatingAdmissionPolicy** let you express rules *without* a webhook at all — no extra pod, no network hop, no availability coupling.

#### Q44. [Theory] How does the kube-apiserver request lifecycle and the aggregation layer work? What is API Priority and Fairness?

A request to `kube-apiserver` flows through a well-defined chain of "filters" (handlers): panic recovery → request timeout → **authentication** (client certs, bearer tokens, OIDC, webhook) → **audit** → **impersonation** → **API Priority and Fairness** (flow control) → **authorization** (RBAC/Node/Webhook) → then into the resource handler, which runs admission, schema validation, and storage (encode → etcd). Reads may be served from the **watch cache** rather than etcd. Understanding this chain explains where each concern lives — e.g., why `kubectl auth can-i` exercises only the authorizer, and why audit can capture a request even if it's later rejected.

```
HTTP → panic/timeout → AuthN → Audit → Impersonation → APF (flow control)
     → AuthZ → [resource handler: admission → validation → serialization → etcd]
                                                          ▲ reads may hit watch cache
─────────────────────────────────────────────────────────────────────────────
Aggregation: /apis/metrics.k8s.io, /apis/custom.metrics.k8s.io, ... are PROXIED
             to extension API servers registered via APIService objects.
```

The **aggregation layer** lets the main API server *delegate* whole API groups to separate "extension API servers." You register an `APIService` pointing the path (e.g., `metrics.k8s.io`) at a Service; the kube-apiserver then transparently proxies those requests. This is how the **metrics-server** (resource metrics for HPA), the **custom/external metrics adapters**, and projects like `apiserver-builder` plug new APIs in *with full API-server semantics* — distinct from CRDs, which are served *by* the main API server. Rule of thumb: use a CRD for declarative resources stored in etcd; use the aggregation layer when you need custom storage, computed/non-persisted resources (like live metrics), or behaviors CRDs can't express.

**API Priority and Fairness (APF)** (GA in 1.20, replacing the old global `--max-requests-inflight` knob) protects the API server from overload and noisy neighbors. It classifies every request via **FlowSchemas** (matching by user, group, or service account, and by resource/verb) into **PriorityLevelConfigurations**, each with a share of the server's concurrency budget and fair queuing *within* a level (so one hot controller can't starve others sharing its level). Critical traffic (leader election, system controllers) gets protected priority levels; a misbehaving client gets throttled (HTTP 429 with `Retry-After`) rather than taking the whole control plane down. This is exactly the lever you reach for in the "API server is overloaded" incident: isolate the abuser into a constrained priority level. You observe it via `apiserver_flowcontrol_*` metrics (rejected requests, queue length, request wait time).

#### Q45. [Theory] etcd internals: how do Raft, MVCC, revisions, compaction, and defragmentation actually work?

etcd is a distributed key-value store built on the **Raft** consensus protocol. One member is the elected **leader**; all writes funnel through it, get appended to the Raft log, and are committed once a **quorum** (majority) of members has durably persisted the entry (fsync to the WAL). Followers apply committed entries to their state machine in the same order, giving **linearizable** consistency. Leader election uses randomized timeouts; if the leader's heartbeats stop (crash, network partition, or — commonly — *disk stalls* delaying fsync), followers time out and elect a new leader. This is why etcd is so sensitive to disk latency: a slow disk delays fsync, delays commits, and can trigger spurious leader elections (`etcd_server_leader_changes_seen_total` climbing is a red flag).

Internally etcd is **MVCC (multi-version concurrency control)**. Every write bumps a cluster-wide, monotonically increasing **revision** number and stores a new version of the key rather than overwriting — this is precisely what backs Kubernetes' `resourceVersion` and makes WATCH-from-a-revision possible (you can stream all changes since revision R). The B-tree maps keys to versions; old versions accumulate.

```
key "/registry/pods/web/p1"
   rev 1042: {...}       ← historical versions retained for MVCC / watch
   rev 1099: {...}
   rev 1187: {...}       ← current
Compaction(rev 1100) → discards versions older than 1100 (frees logical space)
Defragmentation       → returns freed pages to the filesystem (shrinks the .db file)
```

Because old revisions pile up, etcd must be **compacted**: discarding revisions older than a chosen point reclaims logical space (Kubernetes' apiserver auto-compacts every ~5 minutes by default). But compaction alone leaves the on-disk file fragmented at its high-water size — **defragmentation** is the separate operation that actually returns free pages to the filesystem and shrinks the `.db`. Crucially, **defrag is a stop-the-world, blocking operation per member** (it locks the backend), so you defrag one member at a time, off-peak, never all at once. If the DB hits its space quota (default 2 GiB, raised in production), etcd raises a `NOSPACE` alarm and *refuses writes* until you compact, defrag, and clear the alarm — a cluster-wide outage that surprises people who never set up compaction/defrag automation. Backups are `etcdctl snapshot save` (a consistent point-in-time copy), and restore + letting controllers reconcile is the canonical disaster-recovery path.

#### Q46. [Theory] What exactly happens at the cgroup/namespace level when a memory limit is exceeded vs a CPU limit? How does cgroup v2 change things?

Containers are just Linux processes constrained by **namespaces** (isolation of what a process can *see*: PID, net, mount, UTS, IPC, user) and **cgroups** (control of what it can *use*: CPU, memory, IO, pids). Kubernetes maps `requests`/`limits` onto cgroup settings, and the *kind* of resource determines the failure mode.

For **CPU**, requests become the cgroup CPU **shares/weight** (relative scheduling priority when CPU is contended — purely proportional, no hard cap) and limits become **CFS bandwidth** (`cpu.max` / `cpu.cfs_quota_us` + `cfs_period_us`): the process may use up to `quota` microseconds of CPU per 100 ms `period`, after which it is **throttled** — descheduled until the next period. CPU is *compressible*: hitting the limit just slows you down. The notorious effect is **CFS throttling causing tail latency** even on an idle node, because a bursty request that needs more than the quota *within a 100 ms window* gets paused to the next window — which is the concrete reason for the "avoid CPU limits on latency-sensitive workloads" guidance from the QoS question.

For **memory**, requests inform scheduling/eviction but aren't a hard cgroup cap; the limit sets the memory cgroup's hard limit (`memory.max` in v2 / `memory.limit_in_bytes` in v1). Memory is *incompressible* — you can't "slow down" RAM usage — so when a container's cgroup exceeds the limit, the kernel's **OOM killer** fires *within that cgroup* and kills a process (usually the main one), and the kubelet reports the container as **OOMKilled** with exit code 137 (128 + SIGKILL 9). This is a kernel action, not a Kubernetes graceful termination — no preStop, no SIGTERM.

**cgroup v2** (the unified hierarchy, now the default in modern distros and required for several features) changes the substrate in ways that matter: a single unified tree instead of v1's separate per-controller hierarchies; better, more accurate memory accounting and a smoother model via `memory.high` (a throttling/reclaim *soft* pressure point) in addition to `memory.max` (the hard kill point); **PSI (Pressure Stall Information)** for precise CPU/memory/IO pressure metrics that drive smarter eviction; and proper support for `MemoryQoS` and the kubelet feature that uses `memory.high` to reclaim before the hard OOM. The interview-grade point: "OOMKilled vs throttled" is not a Kubernetes abstraction — it's the Linux kernel's cgroup semantics, and Kubernetes is just a declarative front-end that programs `cpu.max`, `memory.max`, and friends.

#### Q47. [Theory] How does a Deployment use the pod-template-hash to manage ReplicaSets and revisions, and what makes a rollout idempotent?

A Deployment doesn't manage Pods directly — it manages **ReplicaSets**, and the mechanism that ties them together is the **`pod-template-hash`**. When you change a Deployment's Pod template (new image, new env, new resources), the Deployment controller computes a hash of the template and looks for a ReplicaSet with that hash label. If none exists, it creates a *new* ReplicaSet (with the hash baked into its name, e.g., `web-6f9c4b7d8`, and into the Pods' `pod-template-hash` label and the ReplicaSet's selector). If one already exists, it *reuses* it. This is what makes `kubectl apply` of an unchanged manifest a no-op and what makes rollbacks instant — old ReplicaSets are kept (scaled to 0) up to `revisionHistoryLimit`.

```
Deployment "web"
 ├─ ReplicaSet web-6f9c4b7d8  (hash of template v1)  replicas: 0   ← previous revision
 ├─ ReplicaSet web-7a1e2f0c9  (hash of template v2)  replicas: 0   ← older revision
 └─ ReplicaSet web-9d3b8c5a1  (hash of template v3)  replicas: 5   ← current

rollout undo → scale current to 0, scale chosen prior RS back up (no new RS created)
```

The hash is appended to the selector so each ReplicaSet only owns *its* Pods even though all Pods share the user's app labels — without the hash, two ReplicaSets with the same `app: web` selector would fight over the same Pods. This is why you'll see a label like `pod-template-hash: 9d3b8c5a1` on every Deployment-managed Pod. Revisions are tracked via the `deployment.kubernetes.io/revision` annotation, and `kubectl rollout history` reads them; `kubectl annotate deployment web kubernetes.io/change-cause="..."` records why.

**Idempotency** falls out of this design plus the reconciliation model: applying the same spec produces the same template hash → same ReplicaSet → no change. This is also why a subtle gotcha exists — if you `kubectl edit` a live Pod or ReplicaSet directly, the Deployment controller will reconcile it back (it owns them via `ownerReferences`), because the desired state lives in the Deployment, not in the children. And a famous trap: because only *template* changes create a new revision, changing a referenced ConfigMap does **not** trigger a rollout (the template didn't change) — which is why people add a `checksum/config` annotation to the Pod template that they bump when the ConfigMap changes, deliberately mutating the template hash to force a rolling restart (`kubectl rollout restart` does this for you via a timestamp annotation since 1.15).

#### Q48. [Theory] How do projected ServiceAccount tokens (bound tokens) work, and why did Kubernetes move away from the old non-expiring Secret tokens?

Every Pod runs as a ServiceAccount and, by default, gets a token mounted at `/var/run/secrets/kubernetes.io/serviceaccount/token` to authenticate to the API server. The *old* model (pre-1.22) auto-created a **Secret per ServiceAccount** holding a JWT that was **non-expiring, not audience-scoped, and not bound to a Pod**. That's a security liability: a leaked token was valid forever, usable from anywhere, against any audience, and revocable only by deleting the ServiceAccount. It also created Secret sprawl and let any Pod that could read Secrets harvest cluster credentials.

The modern model is **bound service account tokens** delivered via a **projected volume** (the `serviceAccountToken` projection, default since 1.22, old auto-Secrets removed in 1.24). The kubelet obtains the token via the TokenRequest API and refreshes it before expiry; the token is:

```yaml
volumes:
  - name: kube-api-access
    projected:
      sources:
        - serviceAccountToken:
            path: token
            expirationSeconds: 3600        # short-lived, auto-rotated by kubelet
            audience: api                   # scoped to an intended audience
        - configMap: { name: kube-root-ca.crt, items: [{ key: ca.crt, path: ca.crt }] }
        - downwardAPI: { items: [{ path: namespace, fieldRef: { fieldPath: metadata.namespace } }] }
```

The improvements are all about blast-radius reduction: tokens are **time-bound** (expire in ~1 hour and rotate), **audience-bound** (a token minted for the API server is rejected if presented to a different audience), and **object-bound** — the JWT embeds the Pod (and ServiceAccount) UID, so when the Pod is deleted the token is *invalidated*, not left valid for a deleted workload. This also enables clean federation with external systems (OIDC): the cluster can expose its OIDC discovery document and external services (cloud IAM via "IRSA"/workload identity, Vault) can validate these short-lived tokens directly, so Pods authenticate to AWS/GCP/Vault *without any long-lived static secret at all*. The takeaway interviewers want: short-lived, bound, audience-scoped tokens turn a stolen credential from "permanent cluster access" into "a token that's already expired."

### 🔴 Expert — extended

#### Q49. [Theory] How does leader election work for control-plane components and operators, and why is it lease-based rather than lock-based?

Many control-plane components run multiple replicas for availability (kube-controller-manager, kube-scheduler, and most operators), but only **one** should *act* at a time — two scheduler instances binding the same Pod, or two controllers reconciling the same object, causes duplicate work and races. Kubernetes solves this with **leader election** built on a **Lease** object (`coordination.k8s.io/v1 Lease`, which replaced the older Endpoints/ConfigMap annotation hack). It is fundamentally a **lease** (a time-bounded right that must be renewed) rather than a **lock** (an indefinitely-held mutex), and that distinction is the whole point.

```
Candidates A, B, C all try to acquire Lease "kube-scheduler" via optimistic update:
  - whoever wins writes holderIdentity=A, renewTime=now, leaseDurationSeconds=15
  - A keeps renewing (every ~2s) to extend renewTime  → A is leader, A acts
  - B,C watch the Lease; if renewTime + leaseDuration < now (A went silent),
    one of them acquires it via a conflict-checked update and becomes leader
```

Why a lease and not a lock? In a distributed system you **cannot distinguish a crashed leader from a partitioned-but-alive leader**. A held lock would deadlock forever if the holder vanished without releasing it. A lease *expires*: if the leader stops renewing (crash, partition, GC pause, node death), the lease naturally becomes claimable after `leaseDuration`, so the system self-heals without manual intervention. The renewal uses the API server's **optimistic concurrency** (resourceVersion compare-and-swap) so exactly one candidate can win the update — no separate consensus needed because etcd already provides linearizable writes.

The danger this introduces is **two leaders during a partition / clock skew** (a classic distributed-systems hazard): if old-leader A is partitioned but still believes it's leader, and B acquires the expired lease, both may act briefly. Kubernetes mitigates with conservative timings (`leaseDuration` > `renewDeadline` > `retryPeriod`) and by making controllers' actions *idempotent and conflict-checked* against the API server — so even a brief double-leader writes through optimistic concurrency and one writer simply gets a 409 and backs off. The lesson for operator authors: never assume you're the only writer; always reconcile idempotently and rely on resourceVersion conflicts, because leader election reduces but does not eliminate concurrency. Tune the lease parameters carefully — too tight and a brief GC pause causes needless leadership churn; too loose and failover is slow.

#### Q50. [Theory] Compare API extension mechanisms: CRD vs aggregated API server vs admission webhook vs built-in. When do you choose which?

Kubernetes is "the platform for building platforms," and it offers several distinct extension seams that beginners conflate. The senior skill is knowing which seam fits a given need and what each costs.

```
Mechanism             Stores data?   Custom logic?   Cost/complexity   Typical use
─────────────────────────────────────────────────────────────────────────────────
CRD (+ controller)    yes (etcd via  reconcile loop  low-medium        declarative custom
                      apiserver)     (separate ctrl)                    resources, operators
Aggregated API server yes (your own  full API server high              metrics, custom storage,
                      backend)       semantics                          non-etcd-backed APIs
Admission webhook     no (mutates/   sync in write   medium (avail.    policy, defaulting,
                      validates)     path            risk)             sidecar injection
ValidatingAdmission-  no             CEL expr in     low (no pod)      policy without a webhook
Policy (CEL, in-tree) (in apiserver) apiserver                         (GA 1.30)
Built-in API          yes            in-tree         N/A (core team)   core resources
```

**CRDs** are the default answer for "I want a new declarative resource type." They're served by the existing API server, get etcd storage, OpenAPI schema validation, RBAC, watch/informers, and `kubectl` support for free. You add behavior with a controller. The limits: storage is etcd (no custom backend), validation beyond schema needs CEL validation rules or a webhook, and you can't easily compute/serve non-persisted data. **Aggregated API servers** are the heavyweight option when CRDs aren't enough — you run your own API server (proxied via the aggregation layer) to get custom storage, protobuf, computed resources (the metrics APIs are the archetype — they serve live data that was never written to etcd), or special semantics. The cost is operating another API server (HA, certs, storage).

**Admission webhooks** don't *add* resources — they intercept writes to existing ones to mutate or validate (sidecar injection, policy enforcement, defaulting). Their cost is the availability/latency coupling discussed earlier (they sit in the API write path). The newest seam, **ValidatingAdmissionPolicy / MutatingAdmissionPolicy** (CEL-based, in-tree), gives you webhook-style policy *without* running a webhook pod — no network hop, no availability dependency — which is increasingly the right choice for org policy that can be expressed in CEL. The decision tree: *new declarative resource → CRD; custom storage or computed/live data → aggregated API server; intercept/validate/mutate existing resources → ValidatingAdmissionPolicy if CEL suffices, else admission webhook.* Reaching for an operator (CRD + controller) when a Helm chart would do, or a webhook when a CEL policy would do, is the over-engineering signal interviewers listen for.

#### Q51. [Theory] What does the kubelet actually do internally — PLEG, sync loops, cgroup management, eviction — and why is "PLEG is not healthy" a feared message?

The kubelet is the node's reconciliation agent, and internally it's a set of cooperating loops, not a single thread. It watches the API server for Pods bound to *its* node, and runs a **pod sync loop**: for each Pod it computes the desired vs actual container state and calls the CRI runtime to create/start/kill containers, invokes CNI for networking and CSI for volumes, runs probes, manages the per-Pod and per-QoS **cgroup hierarchy** (it creates cgroup slices for Guaranteed/Burstable/BestEffort to enforce the QoS hierarchy and node-allocatable reservations), and reports status back to the API server.

A central internal component is the **PLEG (Pod Lifecycle Event Generator)**. Rather than each sync loop independently polling the runtime for every container's state (expensive at scale), PLEG **relists** all containers from the CRI runtime periodically (and reacts to runtime events where supported), computes lifecycle deltas (container started/died), and feeds those events to the sync loops. PLEG must complete a relist within a health threshold; if the container runtime is slow or hung (disk pressure, a wedged containerd, too many containers), relist stalls and the kubelet's health check reports **"PLEG is not healthy"**. This is feared because it cascades: the kubelet marks the node `NotReady`, the node controller eventually evicts/reschedules all Pods (after `pod-eviction-timeout`), and a runtime hiccup becomes a mass-reschedule storm. It's a classic "the node looks fine but everything's being killed" incident, and the root cause is almost always at the runtime/disk layer, not Kubernetes itself.

```
kubelet
 ├─ syncLoop  ←── Pod updates (apiserver watch) + PLEG events + probe results + timers
 │     └─ per Pod: CRI (containers) + CNI (net) + CSI (volumes) + probes + status
 ├─ PLEG: periodic CRI relist → derive {started,died} events  (must stay "healthy")
 ├─ cgroup manager: node-allocatable + QoS cgroup tree (Guaranteed/Burstable/BestEffort)
 └─ eviction manager: watches memory/disk/pid pressure → evicts by QoS + priority
```

The **eviction manager** is another loop that watches node resource pressure (memory, ephemeral storage, inode/pid exhaustion) against configured **eviction thresholds** (hard and soft, with grace periods). Under memory pressure it reclaims first (image/container GC) and then **evicts Pods** to protect node stability *before* the kernel OOM killer would fire system-wide — using QoS and priority to choose victims (BestEffort first, then Burstable over their requests, Guaranteed last). The reason node-level eviction exists at all is that the kernel OOM killer is blunt and could kill critical system daemons (kubelet, runtime); the kubelet's proactive eviction is the controlled alternative that preserves node and cluster health. Understanding these loops explains many node-level mysteries: `NotReady` nodes (kubelet→apiserver heartbeat or PLEG), Pods evicted with `The node was low on resource: memory`, and the `node.kubernetes.io/not-ready` / `unreachable` taints the node controller applies that trigger toleration-based eviction timers.

#### Q52. [Theory] Explain conntrack and the Linux connection-tracking pitfalls that cause intermittent Service/NAT failures at scale.

When kube-proxy (iptables/IPVS mode) DNATs a Service VIP to a backend Pod IP, the kernel's **netfilter conntrack** table records the translation so reply packets are un-NATed correctly and subsequent packets of the same flow follow the same backend. This is invisible until it breaks — and at scale it breaks in two famous ways that produce *intermittent, maddening* failures.

First, **conntrack table exhaustion**. The table (`nf_conntrack_max`) has a finite size; a node handling huge connection rates (or a SYN flood, or many short-lived connections) can fill it. When full, the kernel **drops new connections** and logs `nf_conntrack: table full, dropping packet` — manifesting as random connection timeouts that don't correlate with app load in any obvious way. The fix is sizing `nf_conntrack_max`/`hashsize` appropriately (and ipset-based IPVS reduces rule pressure but not conntrack usage), or moving to an **eBPF dataplane that bypasses conntrack** for many flows (Cilium can do socket-level LB without per-packet conntrack), which is one of its biggest scale advantages.

Second, the **SNAT source-port collision / race on the `--random-fully` issue**. When Pods egress to external IPs (or hairpin through a Service), the node SNATs many Pods behind the node IP; the limited ephemeral source-port space plus a long-known kernel race in inserting conntrack entries for parallel connections to the *same destination* caused intermittent **~1s+ connection delays or drops** (DNS was the most visible victim — the classic "intermittent 5s DNS timeouts" that plagued clusters). Mitigations that became standard practice: enable `--random-fully` on masquerade rules to spread source ports, run **NodeLocal DNSCache** so DNS uses TCP/local cache and avoids the conntrack-heavy UDP cross-node path, set single-request resolver options, and prefer eBPF datapaths.

```
Pod ──► Service VIP ──(DNAT)──► backend Pod      conntrack entry: orig→reply mapping
Pod ──► external ──(SNAT to nodeIP:randomPort)──► internet   ← source-port pressure here

Failure modes:  table full → random drops;  SNAT race/port reuse → intermittent latency
```

The meta-point for a staff interview: Service networking is *stateful at the kernel level even though Kubernetes presents it as a stateless abstraction*. Your "flat, simple" Service IP is, underneath, millions of conntrack entries and a finite NAT table. Operating Kubernetes networking at scale means tuning or eliminating conntrack — which is precisely why the industry has been migrating from iptables kube-proxy to eBPF dataplanes.

#### Q53. [Theory] Why is dual-stack networking non-trivial, and how do IPFamily, IPFamilyPolicy, and headless services interact?

Dual-stack (IPv4 + IPv6 simultaneously, GA since 1.23) sounds like "just add IPv6," but it forces explicit decisions because a Service historically had *one* ClusterIP and Pods *one* IP. Dual-stack means Pods get an IPv4 *and* an IPv6 address, and Services can expose both — which the API models with **`ipFamilies`** (an ordered list, e.g., `[IPv4, IPv6]`) and **`ipFamilyPolicy`** (`SingleStack`, `PreferDualStack`, or `RequireDualStack`). The *order* of `ipFamilies` determines which family is "primary" — and the primary family is what single-stack-only clients and certain legacy code paths use, so getting the order wrong can silently route everything over the unintended family.

```yaml
apiVersion: v1
kind: Service
metadata: { name: web }
spec:
  ipFamilyPolicy: PreferDualStack    # use both if cluster supports it, else fall back
  ipFamilies: [IPv6, IPv4]           # ORDER matters: first = primary ClusterIP
  selector: { app: web }
  ports: [{ port: 80 }]
```

The non-trivial parts: (1) **the whole stack must agree** — kube-apiserver (`--service-cluster-ip-range` with both CIDRs), kube-controller-manager (both Pod CIDRs), the CNI (must assign and route both families), and kube-proxy must all be dual-stack-configured, or you get partial/broken connectivity that's hard to diagnose. (2) **A Service's `ipFamilies` is largely immutable** in the dimension that matters (you can't freely flip a SingleStack IPv4 service to IPv6-primary), because the allocated ClusterIPs and downstream EndpointSlices are family-specific. (3) **Headless services** (`clusterIP: None`) interact specially: DNS returns Pod IPs of the requested family per the `ipFamilies`, and clients must do happy-eyeballs-style selection; misconfigured `ipFamilyPolicy` on a headless service yields A-but-no-AAAA (or vice-versa) records and "works for some Pods, not others" bugs.

The deeper reason it's hard is that dual-stack reintroduces **address-family as a first-class scheduling/routing concern** across every networking component, and the failure modes are *partial* rather than total — connectivity over one family while the other silently blackholes. The pragmatic guidance: most clusters run single-stack until there's a concrete driver (IPv6 address exhaustion in large clusters, regulatory mandates, IPv6-only clients), adopt `PreferDualStack` to roll out gradually, and treat the `ipFamilies` ordering and end-to-end component agreement as the things to verify first.

#### Q54. [Theory] Server-Side Apply changes how object ownership and conflicts work. What problem does it solve over client-side apply, and what are managedFields?

Classic `kubectl apply` is **client-side**: the client computes a three-way merge (last-applied annotation vs live object vs your new manifest) and PATCHes the result. This has chronic problems: the merge logic lives in the client (versions/tools disagree), the `last-applied-configuration` annotation can balloon and drift, and there's **no concept of who owns which field** — so when two actors (a human's manifest, an HPA, a mutating webhook, an operator) each manage *different* fields of the same object, they silently stomp each other. The textbook bug: you apply a Deployment with `replicas: 3`, an HPA scales it to 10, your next `kubectl apply` resets it to 3 and fights the HPA forever.

**Server-Side Apply (SSA)** (GA since 1.22) moves the merge into the API server and introduces **field management**: the API server tracks, in `metadata.managedFields`, *which manager (field manager) owns which fields* of the object, recorded as a set of field paths per manager. When you apply, you declare your intent (`Content-Type: application/apply-patch+yaml`, with a `fieldManager` name); the server merges your fields into the object and updates ownership. If you try to set a field *another manager already owns* to a different value, you get a **conflict (HTTP 409)** — surfaced to humans as a clear error — which you resolve either by removing the field from your manifest (cede ownership) or by passing `--force-conflicts` (take ownership). Crucially, fields you *stop* specifying are removed only if you still own them, fixing the long-standing "how do I delete a field with apply" ambiguity.

```yaml
metadata:
  managedFields:
    - manager: kubectl
      operation: Apply
      fieldsV1: { f:spec: { f:template: { ... } } }     # human owns the template
    - manager: kube-controller-manager      # HPA owns replicas
      operation: Apply
      fieldsV1: { f:spec: { f:replicas: {} } }
```

This solves the multi-writer problem elegantly: the HPA owns `spec.replicas`, your manifest owns the template — they coexist without stomping, and your apply that *doesn't mention* replicas won't reset them (you don't own that field). It's foundational for GitOps and controllers: Argo CD, operators, and `kubectl apply --server-side` all use it so multiple reconcilers can co-manage one object deterministically, with conflicts made *explicit* rather than last-write-wins. The trade-offs: `managedFields` adds metadata bloat (and noise in `kubectl get -o yaml` — there's a `--show-managed-fields=false` default to hide it), and mixing client-side and server-side apply on the same object can produce confusing ownership transitions. The conceptual leap interviewers want you to articulate: SSA turns "apply" from a *client-side diff* into a *server-side, field-ownership-aware merge with explicit conflict detection* — declarative configuration with multiple cooperating authors.

#### Q55. [Practical] A Service has healthy Pods but clients intermittently get connection failures. Walk the layers you'd investigate.

Intermittent Service failures with healthy-looking Pods are a layered-system problem — the bug is almost never where you first look, so the value is a *disciplined top-to-bottom (or bottom-to-top) walk* rather than guessing. I'd reason through the request path layer by layer, because each layer can independently produce "sometimes it works."

```
client → DNS (CoreDNS) → Service VIP → kube-proxy/eBPF DNAT → EndpointSlice → Pod → app
   │         │               │              │                     │           │
   ndots/    cache miss/   stale rule/    conntrack full /     not-ready     SIGTERM
   resolver  cross-node    sync lag       SNAT race            still in ES?   race / preStop
```

1. **Endpoints/readiness**: `kubectl get endpointslices -l kubernetes.io/service-name=web` — are *all* listed IPs actually ready? A Pod failing readiness intermittently flaps in/out of the EndpointSlice; a terminating Pod still in the slice (no preStop drain) gives connection-refused for in-flight requests. This is the most common real cause and ties directly to the graceful-termination question.
2. **DNS**: is the failure on *name resolution* or *connection*? Test with the IP directly to bisect. Intermittent ~5s stalls scream the conntrack/UDP DNS race → check CoreDNS latency/errors, consider NodeLocal DNSCache, verify `ndots`.
3. **Dataplane/conntrack**: `nf_conntrack: table full` in dmesg, conntrack table near `nf_conntrack_max`, kube-proxy sync latency (`kubeproxy_sync_proxy_rules_duration_seconds`) — a node with thousands of Services may have stale/slow rules. eBPF datapath sidesteps much of this.
4. **App / backend**: one bad Pod behind the Service (a single replica with a corrupt cache, a slow GC) means ~1/N requests fail — `kubectl logs` across *all* replicas, not one; check per-Pod error rates. A too-aggressive liveness probe restarting one replica periodically also presents as intermittent.
5. **Topology/affinity**: `internalTrafficPolicy: Local` or topology-aware routing can send traffic only to *same-node/zone* endpoints — if those are unhealthy or absent, some clients fail while others succeed, which looks "intermittent" but is actually *client-location-dependent*.

The principle I'd state explicitly: **"intermittent" usually means "deterministic per some hidden dimension"** — per-backend (one bad Pod), per-client-zone (topology routing), per-flow (conntrack/SNAT), or per-timing (termination races). The debugging goal is to find which dimension, by correlating failures against backend identity, client node/zone, and timing — not to stare at one Pod that happens to look healthy. Tools: `kubectl get endpointslices`, conntrack counters, CoreDNS metrics, per-Pod logs, and a tight reproduction (curl in a loop from inside the cluster) that records *which* backend served each request.

#### Q56. [Theory] Compare Kubernetes' reconciliation model with an imperative orchestration model. Why is level-triggered superior to edge-triggered for reliability?

Kubernetes controllers are **level-triggered**: they continuously observe the *current desired and actual state* and drive actual toward desired, regardless of *how* the system arrived at the present moment. The contrast is an **edge-triggered** model, which reacts to *transitions/events* ("a Pod died → start a new one") and assumes it sees every event. The distinction — borrowed from digital electronics — is the deepest design principle in Kubernetes, and articulating *why* level wins is a hallmark of senior understanding.

The fatal weakness of edge-triggered orchestration is **missed or duplicated events**. In any distributed system, the channel delivering events can drop messages (the controller was down/partitioned/restarting), deliver them out of order, or deliver them twice. An edge-triggered controller that misses the "Pod died" event leaves the system permanently wrong; one that processes a duplicate "scale up" double-acts. You end up needing exactly-once delivery and durable event queues — hard, fragile distributed-systems machinery. A level-triggered controller sidesteps all of this: even if it misses every intermediate event, the *next* time it reconciles it observes "desired 5, actual 4" and fixes it. **Watches are an optimization for latency, not a correctness dependency** — a level-triggered controller can fall back to a full relist and still converge, which is exactly why client-go pairs watches with periodic resyncs.

```
Edge-triggered:  event("pod died") → act once.  Miss the event → wrong forever.
                 Requires reliable, exactly-once, ordered delivery (hard, brittle).

Level-triggered: observe(desired=5, actual=4) → create 1.  Re-observe next loop.
                 Missed events self-heal on the next pass.  Watches = speedup only.
```

This is why Kubernetes is so resilient to component restarts and partitions: kill the controller-manager for an hour and nothing drifts permanently — when it returns it reconciles current state and converges. It's why reconcile loops must be **idempotent** (running the same reconcile twice produces the same result, because they will run many times) and operate on *observed state* rather than remembered events. The trade-offs aren't free: level-triggered systems are **eventually consistent** (there's a reconcile-loop delay before convergence), they can be less CPU-efficient (re-evaluating state vs reacting to deltas — mitigated by informers/watch caches so you re-evaluate cheaply from cache), and they make "did my action take effect *right now*" harder to reason about than an imperative call that returns success/failure. But for an always-on infrastructure platform where partial failure is the *normal* operating condition, trading immediacy for self-healing convergence is unambiguously the right call — and it's the reason imperative orchestrators (and your own bespoke restart scripts) were so much more brittle.

#### Q57. [Theory] What changed across notable Kubernetes versions (dockershim removal, PSP→PSA, in-tree→CSI, sidecar containers, Gateway API) and why should an operator track the deprecation cadence?

Kubernetes ships ~3 minor releases a year with a ~14-month support window per minor (under the N-2 patch policy plus a year of patches), so an operator who ignores the cadence eventually hits a version where something they depend on is *gone*, not just deprecated. The discipline that matters is reading the **deprecation/removal notes** and using `apidiff`/`kubectl convert`/Pluto-style scanners before upgrading. A few landmark changes that recur in interviews because each broke real clusters:

```
1.16  many beta APIs removed (e.g., extensions/v1beta1 Deployment) → manifests rejected
1.21  PodSecurityPolicy deprecated
1.22  ServiceAccount bound tokens default; SSA GA; many *v1beta1 → v1 removals
1.24  dockershim removed (use containerd/CRI-O); auto SA Secret tokens removed
1.25  PodSecurityPolicy REMOVED → replaced by Pod Security Admission (PSA)
1.27  CronJob timeZone stable; seccomp-by-default progressing
1.29  native sidecar containers (init container w/ restartPolicy: Always) beta→on
1.30  ValidatingAdmissionPolicy (CEL) GA
1.31+ in-tree cloud/volume providers fully removed in favor of out-of-tree + CSI
```

Each of these is a *category* of breakage. **API version removals** (the 1.16 and 1.22 waves) reject manifests outright — your GitOps repo full of `extensions/v1beta1` or `policy/v1beta1 PodSecurityPolicy` objects simply fails to apply, so you must migrate manifests *before* the control-plane upgrade. **dockershim removal (1.24)** meant nodes had to switch container runtime to containerd/CRI-O; clusters that assumed "Docker" broke node provisioning and any tooling that shelled into Docker. **PSP→PSA (1.25)** removed an entire admission mechanism — clusters relying on PodSecurityPolicy lost their pod-hardening enforcement on upgrade unless they'd migrated to Pod Security Admission + a policy engine first. **In-tree → CSI / out-of-tree cloud providers** means volume and cloud-LB code moved to separately-versioned drivers you now must install and lifecycle yourself.

The operator's reasoning: Kubernetes' "deprecate for N releases, then remove" policy is a *promise*, not a courtesy — removal *will* happen on schedule, and managed providers (EKS/GKE/AKS) often *force* upgrades when a version goes end-of-life. So tracking the cadence is risk management: scan manifests for deprecated APIs every release (Pluto, `kubectl deprecations`), test upgrades in a non-prod cluster a version ahead, pin and test CSI/CNI/ingress controller compatibility against the target version, and treat the **changelog's "Urgent Upgrade Notes" / "Removed" sections** as required reading. The anti-pattern — staying many versions behind to "avoid churn" — actually *increases* risk, because you eventually face a forced jump across multiple breaking removals at once with no incremental rollback path.

#### Q58. [Practical] Explain how GitOps (Argo CD / Flux) reconciliation differs from `kubectl apply` in CI, and the drift-detection and pruning semantics that make it safe.

CI-based `kubectl apply` is a **push** model: a pipeline holds cluster credentials and pushes manifests at deploy time. It's a *point-in-time, fire-and-forget* action — once the pipeline finishes, nothing watches the cluster, so any **drift** (someone `kubectl edit`s a resource, a controller mutates it, a Pod gets manually scaled) goes undetected until the next deploy, and even then `apply` only touches the objects in *this* run (it won't notice or remove things that were deleted from the repo unless you track that yourself). GitOps tools (**Argo CD**, **Flux**) invert this into a **pull + continuous reconciliation** model: an in-cluster controller continuously compares the cluster's *actual* state against the *desired* state declared in a Git repo (the single source of truth) and converges them, just like a native Kubernetes controller — but with Git as the desired-state store.

```
CI push:   pipeline (has cluster creds) ──apply──► cluster   (one-shot, then blind)
GitOps:    Git repo (desired) ◄──watch── Argo/Flux controller ──reconcile──► cluster
                                  ▲ continuously detects drift, re-applies, can prune
```

The two semantics that make GitOps *safe* (and that interviewers probe) are **drift detection** and **pruning**. Drift detection: because the controller re-reconciles on an interval (and on Git webhook), if someone manually changes a managed resource, Argo CD marks the app `OutOfSync` and (if **auto-sync with self-heal** is enabled) reverts it to match Git — so the cluster *cannot* permanently diverge from the audited, reviewed source of truth. This turns "what's actually running?" from an open question into "whatever Git says, verifiably." **Pruning**: GitOps tracks which live objects it owns (via labels/annotations and, increasingly, Server-Side Apply field management), so deleting a manifest from Git causes the controller to *delete* the corresponding cluster object — closing the "orphaned resource" gap that plain `apply` leaves. Pruning is powerful and dangerous, which is why it's typically opt-in per-app, paired with `prune-last`/finalizer ordering and resource-level `Prune=false` annotations for things you never want auto-deleted (e.g., a PVC with data).

The deeper wins are operational, not just mechanical: credentials stay *in* the cluster (the pipeline no longer needs cluster-admin, shrinking the CI blast radius), every change is a reviewed, audited, revertible Git commit (rollback = `git revert`), and the model composes with Server-Side Apply so multiple controllers co-own objects without stomping. The trade-offs to name: reconciliation is *eventually consistent* (there's sync latency), debugging "why did my change revert?" requires understanding self-heal, secret management needs a sealed-secrets/external-secrets layer (you don't commit plaintext secrets to Git), and pruning misconfiguration can delete things you meant to keep. The conceptual through-line back to Q56: GitOps is just the **level-triggered reconciliation model extended out to Git as the desired-state store** — which is why it inherits Kubernetes' self-healing properties rather than the brittleness of imperative push pipelines.

#### Q59. [Theory] How do taints, tolerations, and node affinity differ as placement mechanisms, and why are they not redundant?

These three are easy to conflate because they all influence *which node a Pod lands on*, but they operate from opposite directions and answer different questions. **Node affinity** (and the simpler `nodeSelector`) is **Pod-driven attraction**: the Pod says "I *want* (or require) nodes with these labels." **Taints** are **node-driven repulsion**: the node says "I *repel* all Pods *unless* they explicitly tolerate me." **Tolerations** are the Pod's permission slip that lets it *ignore* a specific taint. The asymmetry is the key insight: affinity is opt-*in* by the Pod toward node properties, while taint/toleration is opt-*out* by the node, defaulting to exclusion.

```
Node affinity / nodeSelector :  Pod → "attract me TO nodes labeled X"   (Pod chooses)
Taint                        :  Node → "repel ALL pods"                  (node excludes)
Toleration                   :  Pod → "I can withstand taint Y"          (exception only)

A toleration does NOT attract — it only REMOVES the repulsion. To both repel others
AND pin a Pod, you combine: taint the node + tolerate it + node-affinity to it.
```

Why they're not redundant: consider a GPU node pool. You **taint** it (`nvidia.com/gpu=true:NoSchedule`) so ordinary Pods can't accidentally land there and waste expensive hardware — that's repulsion no affinity rule could achieve, because affinity only expresses *one* Pod's preference, not a blanket exclusion of *all others*. GPU Pods then carry a matching **toleration** so they're *allowed* there — but a toleration alone doesn't *pull* them onto GPU nodes (a tolerating Pod could still schedule elsewhere), so you *also* add **node affinity** to `accelerator=gpu` to pin them. Three mechanisms, three distinct jobs: keep others out (taint), allow these in (toleration), draw these here (affinity).

The taint **effects** add another dimension: `NoSchedule` (don't place new Pods, leave existing ones), `PreferNoSchedule` (soft), and `NoExecute` (also *evict* already-running Pods that don't tolerate it — this is how the node controller drains a `NotReady`/`unreachable` node by applying `node.kubernetes.io/unreachable:NoExecute`, and how `tolerationSeconds` controls the grace period before eviction). This is the internal connection most miss: node failure handling is *implemented* via taints and `NoExecute` tolerations, not a separate subsystem — every Pod silently carries default tolerations for the not-ready/unreachable taints with a 300s `tolerationSeconds`, which is exactly the delay before a dead node's Pods get rescheduled.

#### Q60. [Theory] What problem do init containers solve that a regular container or an entrypoint script cannot, and how do native sidecars change the model?

**Init containers** run *to completion, sequentially, before* any app container starts; the Pod won't proceed until each succeeds (with restarts on failure per `restartPolicy`). The reason this can't always be folded into the app container's entrypoint is **separation of image, ordering guarantees, and privilege**. You can run a different image for setup (a migration tool, a git-clone helper, a `wait-for-it` probe) without bloating or coupling the app image; you get a *hard ordering barrier* the kubelet enforces (app container literally does not start until init succeeds — no race, no "sleep until ready" hacks); and you can grant the init container elevated privileges (e.g., a `sysctl`-tweaking init with extra capabilities) while the app container stays unprivileged, which an entrypoint script in a single container cannot do because the whole container shares one securityContext.

```
Init phase (sequential, must complete):   init-1 → init-2 → init-3
                                                              │ all done
Main phase (parallel, long-running):        app  ‖  classic-sidecar(?)
```

The classic pain point was the **sidecar lifecycle mismatch**. A logging or proxy sidecar is conceptually a "helper that runs alongside," but before native support it was just another container in the `containers` list — with *no ordering guarantee*. Two failures resulted: (1) the app could start *before* the proxy sidecar was ready (early requests fail because the mesh sidecar isn't up), and (2) worst, in a **Job**, the app container finishes but the sidecar runs forever, so the Pod never completes — the infamous "Job stuck because the Istio sidecar won't exit" problem that spawned hacky workarounds (shared `emptyDir` flag files, `preStop` kill scripts, `curl localhost:15020/quitquitquit`).

**Native sidecar containers** (beta/on by default since 1.29) fix this elegantly by modeling a sidecar as an **init container with `restartPolicy: Always`**. That sounds odd until you see the semantics it buys: declared in `initContainers`, so it **starts before** the main containers (ordering solved — the proxy is up first); but because of `restartPolicy: Always` it **keeps running** alongside them rather than blocking the init sequence; and at termination it is **shut down *after* the main containers exit** — which is exactly what makes Jobs complete (the sidecar no longer pins the Pod open) and ensures the logging sidecar captures the app's final output during shutdown. The interview-grade summary: init containers gave us *ordered, isolated setup*; native sidecars finally gave sidecars *correct start-before / stop-after lifecycle*, retiring years of fragile workarounds.

#### Q61. [Theory] Explain the EndpointSlice API: why did it replace Endpoints, and how does it interact with topology-aware routing?

Originally, each Service had one **Endpoints** object listing *all* its backend Pod IPs and ports. That design has a brutal scaling flaw: it's a *single object*, so any change (one Pod going ready/not-ready among thousands) rewrites the *entire* Endpoints object, and **every kube-proxy on every node watches it** — so a single Pod flap in a 5,000-endpoint Service triggers a multi-megabyte object update fanned out to thousands of watchers. At scale this generated enormous etcd write amplification and API-server/network load, and the object even hit etcd's per-object size ceiling for very large Services.

**EndpointSlices** (GA since 1.21, now the default the controllers and kube-proxy consume) solve this by **sharding** a Service's endpoints across multiple smaller objects, each holding up to ~100 endpoints (configurable). A change to one endpoint now rewrites only the *slice* containing it (~100 endpoints) instead of the whole set, so watch/update traffic scales with *change rate per slice* rather than *total endpoint count* — a dramatic reduction for large Services. Slices also carry **richer per-endpoint metadata** that the flat Endpoints object couldn't: per-endpoint `conditions` (ready/serving/terminating — note *serving* and *terminating* are distinct, enabling traffic to a terminating-but-still-serving Pod during graceful shutdown), the target Pod's node name, and crucially its **zone/topology hints**.

```
Endpoints (old):  Service → 1 object  [ip1..ip5000]   ← any change rewrites ALL
EndpointSlice:    Service → slice-a [ip1..ip100]       ← change touches one slice
                          → slice-b [ip101..ip200]
                          → ...      each w/ {ready, serving, terminating, zone, nodeName}
```

The topology interaction is where the extra metadata pays off. **Topology-aware routing** (`trafficDistribution: PreferClose`, the successor to the older "topology aware hints") uses the zone hints the EndpointSlice controller computes to make kube-proxy/the dataplane **prefer in-zone endpoints**, keeping traffic within an availability zone to cut cross-AZ data-transfer cost and shave latency — falling back to cross-zone only when in-zone capacity is insufficient (the controller computes hints proportional to allocatable CPU per zone to avoid overloading a sparse zone). This couldn't work on the old Endpoints object because it had nowhere to carry per-endpoint zone hints. The takeaway: EndpointSlice wasn't just a refactor — it was the *enabling data model* for both Service scalability to tens of thousands of endpoints and for cost/latency-aware traffic routing.

#### Q62. [Theory] Compare overlay vs native-routing CNI dataplanes (VXLAN/Geneve vs BGP vs cloud-native IPAM vs eBPF). What are the trade-offs?

The CNI must satisfy Kubernetes' flat-network requirement (every Pod reaches every Pod without NAT), but *how* it moves a packet from a Pod on node A to a Pod on node B varies enormously, and the choice has real performance, cost, and operational consequences.

```
Model              How cross-node packets travel              Trade-offs
─────────────────────────────────────────────────────────────────────────────────────
Overlay            Pod packet ENCAPSULATED in VXLAN/Geneve      + works on any L3 underlay,
(flannel-vxlan,    UDP, tunneled node→node, decapsulated.        no infra cooperation needed
Calico VXLAN,                                                   − MTU overhead (~50B), encap/
Cilium VXLAN)                                                     decap CPU, harder to debug
Native routing     Pod IPs are REAL routable IPs; nodes          + no encap, near-line-rate,
(Calico BGP)       advertise Pod CIDRs via BGP to the fabric.     transparent to network tools
                                                                 − needs BGP-capable fabric /
                                                                   peering; more network setup
Cloud-native IPAM  Pods get REAL VPC IPs from the cloud           + native VPC routing, SGs,
(AWS VPC CNI,      (ENIs/alias IPs); cloud routes natively.        flow logs apply to Pods
Azure/GCP CNI)                                                   − IP exhaustion in the VPC,
                                                                   ENI/IP-per-node density limits
eBPF dataplane     eBPF programs forward/LB in-kernel; can run    + lowest latency, no kube-proxy,
(Cilium)           overlay OR native; bypasses iptables/conntrack   L7 policy + observability
                                                                 − recent-kernel dependency,
                                                                   steeper operational expertise
```

**Overlay** networks (VXLAN/Geneve) are the most portable: they encapsulate Pod traffic in UDP and tunnel it node-to-node, so they work on *any* L3 network without asking the underlying fabric to know about Pod CIDRs. The cost is **MTU overhead** (the ~50-byte header means you must lower Pod MTU or risk fragmentation/black-holing — a classic "large responses hang" bug), encap/decap CPU, and opacity (your network team's tools see node IPs, not Pod IPs). **Native routing (Calico BGP)** drops encapsulation: nodes advertise their Pod CIDRs via BGP so the physical/virtual fabric routes Pod IPs directly — near-line-rate and fully transparent, but it requires a **BGP-capable, cooperating network** (or a route reflector), which on-prem teams can do but many cloud environments restrict.

**Cloud-native IPAM** (AWS VPC CNI, Azure CNI) goes further: Pods get *real VPC IP addresses* from secondary ENIs/alias ranges, so the cloud's own routing, security groups, and flow logs apply to Pods directly with zero overlay. The trade-off is **IP/density pressure** — you can exhaust the VPC's address space, and each node can host only as many Pods as its instance type's ENI/IP limits allow (the well-known "max pods per node" cap on EKS), which complicates bin-packing. **eBPF dataplanes (Cilium)** are orthogonal to encap choice (you can run eBPF over an overlay *or* native routing) but replace iptables/kube-proxy with in-kernel eBPF for forwarding, load balancing, and policy — yielding the lowest latency, conntrack bypass, and rich L7 policy/observability, at the price of a modern-kernel requirement and deeper expertise to operate and debug. The judgment interviewers want: pick overlay for portability and simplicity, native routing or cloud IPAM for performance and network-team transparency, and eBPF when scale/latency/observability justify the operational investment — and always understand your MTU and IP-density constraints up front, because those are the two things that silently bite in production.

#### Q63. [Practical] A node goes NotReady. Trace exactly what Kubernetes does, and why Pods don't get rescheduled instantly.

A `NotReady` node triggers a deliberately *paced* sequence — Kubernetes intentionally does *not* reschedule instantly, and understanding why prevents both the "why are my Pods still on the dead node?!" panic and the far worse mistake of tuning the timers too aggressively.

```
t=0     kubelet stops posting heartbeats (Lease in kube-node-lease stops renewing)
        — cause: node crash, kubelet hang, network partition, or PLEG/runtime stall
t≈40s   node-monitor-grace-period elapses → node controller marks node NotReady,
        applies taint node.kubernetes.io/not-ready (or unreachable):NoExecute
t≈40s   Service EndpointSlices: Pods on the node lose Ready → removed from rotation
t≈40s+  NoExecute taint + each Pod's default tolerationSeconds (300s) starts the clock
t≈340s  Pods that don't tolerate longer are EVICTED (deleted) → their controllers
        (Deployment/StatefulSet/etc.) create replacements, which the scheduler places
        on healthy nodes; the dead node's Pods may linger as "Terminating" until it returns
```

The mechanism: nodes prove liveness by renewing a **Lease** object (in `kube-node-lease`) far more cheaply than full status updates. When renewals stop, the **node controller** (in kube-controller-manager) waits `node-monitor-grace-period` (~40s default) before flipping the node to `NotReady` and stamping the `node.kubernetes.io/unreachable` (or `not-ready`) taint with effect **`NoExecute`**. Two things then happen: the EndpointSlice controller pulls the node's Pods out of Service rotation almost immediately (so traffic stops within seconds — availability is protected fast), but *eviction* of the Pods is deliberately delayed by the **default `tolerationSeconds: 300`** that every Pod silently carries for these taints. Only after ~5 minutes total are the Pods evicted, prompting their owning controllers to recreate them on healthy nodes.

Why the delay rather than instant rescheduling? Because **a NotReady node is often a transient blip** — a brief network partition, a kubelet restart, a momentary control-plane hiccup. If Kubernetes evicted and rescheduled the instant a heartbeat was missed, every transient glitch would cause a *thundering herd* of unnecessary Pod churn (and for StatefulSets, dangerous double-running of stateful Pods if the "dead" node is actually alive but partitioned — the split-brain risk). The 300s default trades *recovery latency* for *stability*. Operators tune `tolerationSeconds` per-workload: latency-tolerant batch can wait, while a stateless web tier might set a shorter toleration to recover faster — but you should *never* set it near-zero cluster-wide, because that turns every network blip into mass rescheduling. There's also a safety valve: if *too many* nodes go NotReady at once (a likely network-wide event, not individual failures), the node controller **rate-limits evictions** (`--secondary-node-eviction-rate` and `--unhealthy-zone-threshold`) and can stop evicting entirely in a zone, on the theory that mass-evicting during a network partition would make things catastrophically worse rather than better.

#### Q64. [Theory] How does ephemeral storage (emptyDir, container writable layer, image cache) get accounted and enforced, and how can it evict Pods?

Beyond persistent volumes, Pods consume **node-local ephemeral storage** in several forms that share the node's root/`kubelet` filesystem, and people forget it's a *managed, evictable resource* just like memory. The categories: the **container writable layer** (everything written outside a mounted volume, on the overlay filesystem), **`emptyDir` volumes** (scratch space that lives and dies with the Pod), **logs** (the container's stdout/stderr captured by the kubelet), and the shared **image/layer cache**. The first three count against a Pod's ephemeral-storage usage; the image cache is node-shared overhead.

```yaml
spec:
  containers:
    - name: app
      image: myapp:1.0
      resources:
        requests: { ephemeral-storage: "1Gi" }   # scheduler reserves this
        limits:   { ephemeral-storage: "2Gi" }    # exceed → Pod EVICTED (not OOM)
  volumes:
    - name: scratch
      emptyDir: { sizeLimit: "500Mi" }            # emptyDir over limit → Pod evicted
```

Enforcement and eviction are the parts that surprise people. You can set `requests`/`limits` for **`ephemeral-storage`** exactly like CPU/memory: requests feed the scheduler (it won't place a Pod where node ephemeral storage is insufficient, and node `allocatable` ephemeral storage already subtracts system reserves), and exceeding the *limit* causes the kubelet's eviction manager to **evict the Pod** (with a `Pod ephemeral local storage usage exceeds the total limit of containers` event). Separately, the kubelet watches **node-level disk pressure** against `nodefs`/`imagefs` eviction thresholds; under disk pressure it first does **garbage collection** (deletes unused images and dead containers from the cache), and if that's insufficient it **evicts Pods** by QoS/priority — the storage analogue of memory-pressure eviction. An `emptyDir` with `medium: Memory` is a special case: it's a tmpfs that counts against the Pod's *memory* limit, so a runaway write there triggers an **OOM kill**, not a storage eviction.

The operational traps this creates: (1) **unbounded application logs** are the most common cause — an app logging verbosely to stdout fills `nodefs`, triggers disk-pressure eviction of *other* innocent Pods on the node, and looks like a mystery cluster-wide instability until you find the one chatty Pod (mitigate with log rotation limits via `containerLogMaxSize`/`containerLogMaxFiles` and shipping logs off-node). (2) **`emptyDir` without `sizeLimit`** lets a Pod fill the node disk. (3) Forgetting ephemeral-storage requests means the scheduler can't bin-pack disk safely, so disk-pressure evictions hit randomly. The conceptual point: ephemeral storage is a *first-class schedulable, limitable, evictable resource* — treating it as "free scratch space" is exactly how a single Pod's logs or temp files take down a node's other workloads.

#### Q65. [Theory] What are the trade-offs between one large multi-tenant cluster and many smaller clusters, and how do fleet/multi-cluster patterns address them?

This is the architecture decision that separates "I can run a cluster" from "I can run a platform," and the honest answer is *it depends on which axis you optimize* — there's no universally right size. A **single large cluster** maximizes resource efficiency (better bin-packing across all workloads, fewer idle reservations, one set of shared system components — ingress, monitoring, DNS — amortized over everything) and simplifies cross-service networking (everything's in one flat network, no cross-cluster service discovery needed). But it concentrates risk: it's a **single blast radius** (a control-plane outage, a bad admission webhook, an etcd corruption, or a noisy controller takes down *everything*), it eventually hits **scaling ceilings** (the ~5,000-node / ~150,000-Pod practical limits, etcd size/throughput, API-server load), and it forces *all* tenants onto the *same* Kubernetes version, upgrade cadence, and configuration — there's no per-tenant blast-radius isolation against kernel exploits because they share nodes/kernel.

```
ONE BIG CLUSTER                              MANY SMALL CLUSTERS
+ best bin-packing / resource efficiency     + isolated blast radius (one fails, others live)
+ flat networking, simple discovery          + independent upgrade cadence / versions
+ shared infra amortized once                + hard tenant / regulatory / region isolation
+ one thing to operate                       + no single scaling ceiling; scale by adding clusters
− single blast radius (everything at risk)   − resource fragmentation (idle capacity per cluster)
− shared version/upgrade for all tenants     − N× operational surface; cross-cluster networking
− scaling ceilings (etcd, nodes, apiserver)  − duplicated infra (ingress/monitoring per cluster)
− weak isolation (shared kernel/nodes)        − config/policy drift across the fleet
```

**Many smaller clusters** invert every trade-off: each failure is contained, you can upgrade clusters independently (canary a Kubernetes version on one before the fleet), you get genuine isolation for hostile tenants / regulatory boundaries / geographic regions, and you sidestep per-cluster scaling ceilings by simply adding clusters. The costs are **resource fragmentation** (each cluster needs headroom and its own system components, so utilization drops), **N× operational surface** (N control planes, N sets of certs/upgrades/monitoring to manage), **cross-cluster networking complexity** (service discovery and connectivity across clusters now needs explicit machinery), and **configuration/policy drift** — keeping 50 clusters consistent by hand is impossible.

The patterns that make the many-clusters model tractable are exactly what "fleet management" tooling provides: **GitOps with an app-of-apps / cluster-bootstrap model** (Argo CD ApplicationSets, Flux) so policy, add-ons, and config are *declared once and reconciled into every cluster*, eliminating drift; **multi-cluster service meshes** (Istio multi-cluster, Cilium ClusterMesh, or the upstream Multi-Cluster Services / MCS API) for cross-cluster discovery and mTLS; **fleet/management-plane tools** (Cluster API to declaratively provision clusters themselves, Karmada/Open Cluster Management/Fleet, or vendor offerings like GKE Fleet/EKS-anywhere) for placement and lifecycle across clusters; and for *soft* multi-tenancy within a cluster, **virtual clusters (vCluster)** that give tenants their own API server view atop shared nodes — a middle ground between namespaces (weak isolation) and separate clusters (heavy). The mature recommendation: start with one cluster per *environment* and per *isolation/regulatory boundary*, split further only when blast radius, scale, or version-independence demands it, and the moment you have more than a couple of clusters, invest in GitOps-driven fleet management *before* drift and operational toil compound — because the failure mode of the many-clusters model is death by a thousand inconsistencies, and that's a tooling problem you solve up front, not a heroics problem you solve at 3 a.m.

## 🧩 Extended Questions — Supplemental Set A: Practical & Theory

### 🟢 Basic — extended

#### Q66. [Practical] How do `imagePullPolicy`, image tags, and `imagePullSecrets` interact, and what causes `ImagePullBackOff`?

`ImagePullBackOff` (preceded by `ErrImagePull`) means the kubelet asked the container runtime to pull an image and it failed, so the kubelet is backing off before retrying. The three knobs that determine pull behavior are the **image reference** (registry/repo:tag or @digest), the **`imagePullPolicy`**, and the **`imagePullSecrets`** that authenticate to a private registry. The first triage step is always `kubectl describe pod` — the Events section states the exact reason: `manifest unknown` (bad tag), `unauthorized`/`401` (auth), `no such host`/timeouts (DNS/network to the registry), or `toomanyrequests` (Docker Hub rate limiting, a very common real cause).

`imagePullPolicy` defaults are subtle and bite people: if you specify a concrete tag (`:1.27`) the default policy is `IfNotPresent` (pull only if the image isn't already cached on the node); if you use `:latest` *or omit the tag*, the default flips to `Always`. This is why `:latest` is dangerous — every Pod (re)start re-pulls, so two replicas scheduled minutes apart can run *different* code if the upstream tag moved, producing non-reproducible deploys. The fix is to pin a tag and, for true immutability, pin a **digest** (`myapp@sha256:...`), which guarantees byte-identical images regardless of policy.

```yaml
spec:
  imagePullSecrets:
    - name: regcred                 # docker-registry secret for a private repo
  containers:
    - name: app
      image: registry.example.com/team/app@sha256:abc123...   # digest = immutable
      imagePullPolicy: IfNotPresent
```

```bash
kubectl create secret docker-registry regcred \
  --docker-server=registry.example.com --docker-username=ci \
  --docker-password="$TOKEN" --docker-email=ci@example.com
kubectl describe pod <p> | grep -A5 Events     # the reason is always here
```

For private registries, the `imagePullSecrets` must live in the *same namespace* as the Pod (a frequent gotcha — secrets aren't cluster-wide), and attaching them to the Pod's **ServiceAccount** (`kubectl patch serviceaccount default -p '{"imagePullSecrets":[{"name":"regcred"}]}'`) saves repeating them on every Pod. To beat Docker Hub rate limits, run a **pull-through cache / registry mirror** or push images to a private/cloud registry — at scale, unauthenticated Hub pulls will throttle a whole node's image pulls and present as widespread `ImagePullBackOff`.

#### Q67. [Practical] Compare `kubectl` imperative vs declarative workflows, and explain what `kubectl apply` does under the hood that `create`/`replace` don't.

`kubectl` supports an **imperative** style (`create`, `run`, `expose`, `scale`, `edit`, `delete` — you tell it the action) and a **declarative** style (`apply -f` against manifests — you tell it the desired state and it computes the action). Imperative commands are great for one-off experiments and learning (`kubectl run tmp --image=busybox -it --rm -- sh`), but production should be declarative: manifests in Git, applied repeatedly and idempotently, because that's reproducible, reviewable, and rollback-able.

The non-obvious depth is *how* `apply` differs from `create`/`replace`. `create` fails if the object already exists; `replace` overwrites the *entire* object (and fails if it doesn't exist, and clobbers fields set by other actors like controller-set defaults or an HPA's replica count). `apply` instead performs a **three-way merge** (client-side apply) or a **field-ownership-aware merge** (server-side apply). Client-side `apply` reads the `kubectl.kubernetes.io/last-applied-configuration` annotation (what you applied last time), compares it with your new manifest and the live object, and patches only the *intended* differences — so it won't stomp fields you never managed.

```bash
kubectl apply -f deploy.yaml                # declarative, three-way merge, idempotent
kubectl apply -f deploy.yaml --server-side  # field-ownership merge (SSA), multi-writer safe
kubectl diff -f deploy.yaml                 # preview what apply WOULD change (dry run vs live)
kubectl replace -f deploy.yaml              # full overwrite — clobbers other actors' fields
```

The practical rule: use `kubectl diff -f` before `apply` to see exactly what will change (it does a server dry-run), prefer `--server-side` when multiple controllers co-own objects (per Q54), and reserve imperative commands for debugging. A subtle trap with *client-side* apply is that if a field was added by `kubectl edit` or another tool (not present in your last-applied annotation), `apply` may not remove it — which is one of the reasons server-side apply, with explicit field ownership, is now preferred for anything multi-writer.

#### Q68. [Theory] What is the Downward API, and when do you use it instead of hardcoding values?

The **Downward API** lets a container learn things about *itself and its Pod* — metadata Kubernetes knows but the app couldn't otherwise discover — without coupling the app to the Kubernetes API server. It exposes fields either as **environment variables** (`fieldRef`/`resourceFieldRef`) or as files in a **projected/downwardAPI volume**. Exposable values include the Pod's name, namespace, UID, node name, Pod IP, service account, labels and annotations, and a container's resource **requests/limits**.

```yaml
spec:
  containers:
    - name: app
      image: myapp:1.0
      env:
        - name: POD_NAME
          valueFrom: { fieldRef: { fieldPath: metadata.name } }
        - name: POD_NAMESPACE
          valueFrom: { fieldRef: { fieldPath: metadata.namespace } }
        - name: NODE_NAME
          valueFrom: { fieldRef: { fieldPath: spec.nodeName } }
        - name: MEM_LIMIT
          valueFrom: { resourceFieldRef: { resource: limits.memory, divisor: 1Mi } }
      volumeMounts: [{ name: podinfo, mountPath: /etc/podinfo }]
  volumes:
    - name: podinfo
      downwardAPI:
        items:
          - path: labels
            fieldRef: { fieldPath: metadata.labels }    # labels can change → file updates
```

The "why" is **decoupling and correctness**. You shouldn't hardcode the Pod name (it's generated), and you can't know the node name or Pod IP at manifest-authoring time. Common real uses: tagging logs/metrics/traces with `pod`, `namespace`, and `node` for observability (so a dashboard can filter by Pod); deriving runtime sizing from limits — e.g., exposing `limits.memory` so a JVM can set `-XX:MaxRAMPercentage` or a Go service can tune `GOMEMLIMIT`/`GOMAXPROCS` to match its cgroup rather than the (much larger) node; and writing the Pod IP into config for peer discovery. A key detail: **env-var Downward API values are fixed at container start**, whereas the **volume form updates** when labels/annotations change — so if you need mutable values (labels that get patched at runtime), use the volume, not env vars.

### 🟡 Intermediate — extended

#### Q69. [Practical] Compare Helm and Kustomize. When would you reach for each, and how do they handle environment differences?

Both solve "I have a base set of manifests and need per-environment variation," but with opposite philosophies. **Helm** is a *templating + package manager*: charts are Go-templated YAML with a `values.yaml` you override per environment, plus releases, revision history, and `helm rollback`. **Kustomize** is *template-free overlays*: you keep plain, valid YAML as a `base/` and apply `overlays/` that **patch** it (strategic-merge or JSON patches) for each environment — no templating language, and it's built into `kubectl` (`kubectl apply -k`).

```
helm/                                kustomize/
  Chart.yaml                           base/
  values.yaml      (defaults)            kustomization.yaml  (lists resources)
  values-prod.yaml (overrides)           deployment.yaml     (plain, valid YAML)
  templates/                           overlays/prod/
    deployment.yaml  {{ .Values.x }}      kustomization.yaml  (patches + nameSuffix)
                                          replicas-patch.yaml
```

```bash
helm upgrade --install web ./web -f values-prod.yaml      # templated, versioned release
kubectl apply -k overlays/prod                            # overlay patches the base
helm template web ./web -f values-prod.yaml | kubectl diff -f -   # render to inspect
```

The trade-offs: Helm wins for **distributing third-party software** (its ecosystem of charts is huge — installing Prometheus, cert-manager, ingress controllers is a one-liner) and for parameterizing across *many* knobs, but Go templating YAML is famously error-prone (whitespace bugs, unreadable conditionals, "it's not YAML until it renders"). Kustomize wins for **your own first-party manifests** — overlays stay valid YAML, are easy to review in PRs, and avoid templating-language complexity — but it's weaker for heavy parameterization and has no built-in release/rollback concept. In practice many teams do both: **Helm to install vendor charts, Kustomize for in-house apps** (and Kustomize can even post-render Helm output). GitOps tools support both natively. The mature take: don't template what you can patch; reach for Helm's power (and accept its complexity) when you're packaging software for *others* to configure, and prefer Kustomize's transparency for your own services.

#### Q70. [Practical] Production debugging: a Pod is OOMKilled repeatedly. Walk through right-sizing memory, including JVM/Go runtime gotchas.

OOMKilled (exit 137, `reason: OOMKilled`) means the container's working set exceeded its memory *limit* and the kernel's cgroup OOM killer terminated it — a kernel action, not graceful. The instinct to "just raise the limit" is sometimes right (genuinely under-provisioned) and sometimes papers over a leak or a runtime that doesn't respect its cgroup. Disciplined right-sizing:

```bash
kubectl top pod <p> --containers              # live usage vs limit (needs metrics-server)
kubectl get pod <p> -o jsonpath='{.status.containerStatuses[0].lastState.terminated.reason}'
kubectl describe pod <p> | grep -i oom        # confirm OOMKilled, see restart count
# historical: query container_memory_working_set_bytes in Prometheus over days
```

First, distinguish **steady growth (a leak)** from **a stable-but-too-low limit** by looking at `container_memory_working_set_bytes` over time — a sawtooth that climbs to the limit and OOMs is a leak; a flat line pinned at the limit is under-provisioning; a periodic spike (batch load, large request) is a *p99 sizing* problem. Set the **limit to cover the realistic peak working set with headroom** (e.g., p99 + 20–30%), and set **request = typical usage** so the scheduler bin-packs accurately. For latency-sensitive services, set request == limit (Guaranteed QoS) so they're evicted last.

The runtime gotchas are the high-yield part. **JVMs before container-awareness** (or with it disabled) read the *node's* total memory for heap ergonomics, so a JVM on a 256Mi-limit Pod might try to size a multi-GB heap and OOM instantly — fix with `-XX:MaxRAMPercentage=75` (Java 10+ is cgroup-aware) rather than `-Xmx` guesswork, and remember the JVM needs *non-heap* room (metaspace, threads, direct buffers, code cache) so the limit must exceed max heap by a meaningful margin. **Go** historically ignored cgroup limits for its GC pacing, so a Go service could grow past its limit before GC ran — set **`GOMEMLIMIT`** (Go 1.19+) to a soft cap near the cgroup limit and use the Downward API (`resourceFieldRef: limits.memory`) to wire it automatically; also set `GOMAXPROCS` to match the CPU *limit* (via `automaxprocs`) or Go assumes all node cores and over-schedules. The lesson: OOMKilled is often a *mismatch between the language runtime's view of memory/CPU and the cgroup it actually lives in* — fix the runtime's awareness before inflating limits.

#### Q71. [Practical] How do you safely upgrade a Kubernetes cluster (control plane and nodes) with zero workload downtime?

A cluster upgrade is a sequenced, version-skew-constrained operation, and the cardinal rules are **upgrade the control plane before the nodes**, **one minor version at a time** (no skipping minors), and **respect the version-skew policy** (kubelet may be up to 3 minors behind the API server, never ahead; kubectl ±1). Skipping these is how people brick clusters. The high-level order:

```
1. PRE-FLIGHT: read the target version's "Urgent Upgrade Notes" + "Removed APIs".
   Scan manifests for deprecated APIs (Pluto / `kubectl deprecations`); fix them FIRST.
   Back up etcd (`etcdctl snapshot save`). Verify CNI/CSI/ingress controller compatibility.
2. CONTROL PLANE: upgrade api-server → controller-manager → scheduler → etcd as supported
   (managed: provider does this; kubeadm: `kubeadm upgrade apply vX.Y.Z`). HA = rolling.
3. NODES (one node group / one node at a time):
     kubectl cordon <node>          # stop new scheduling
     kubectl drain <node> --ignore-daemonsets --delete-emptydir-data   # evict (honors PDBs)
     <upgrade kubelet/kube-proxy/OS on the node, or replace it>
     kubectl uncordon <node>        # return to service
4. POST: upgrade add-ons (CNI, CoreDNS, metrics-server, ingress) to compatible versions.
```

The zero-downtime guarantees come from features covered elsewhere working together: **`drain` honors PodDisruptionBudgets** (Q25), so it blocks rather than violating availability — which means *your PDBs must allow at least one Pod to move* or the drain stalls forever (the classic "node won't drain" incident). **Graceful termination + preStop** (Q42) ensures the evicted Pods drain connections cleanly. **topologySpreadConstraints / anti-affinity** (Q24) ensure replicas live on *different* nodes so draining one node never removes all replicas. And **surge capacity** (extra node, or `maxSurge` on the workload) gives somewhere for evicted Pods to land.

On managed platforms (EKS/GKE/AKS) the control-plane upgrade is a button, and node upgrades use **surge node groups / managed node-group rolling updates** (create new-version nodes, cordon+drain old ones, respecting PDBs). The pattern I follow: upgrade a **non-prod cluster one version ahead first**, soak it, then prod; never let prod fall many versions behind (forced EOL upgrades across multiple breaking removals are far riskier — per Q57); and always have the etcd snapshot and a tested restore before touching the control plane, because the control-plane upgrade is the only step without an easy in-place rollback.

#### Q72. [Practical] metrics-server, `kubectl top`, and HPA aren't working. How do you debug the metrics pipeline end to end?

When `kubectl top nodes` returns `error: Metrics API not available` or an HPA shows `<unknown>` for its target, the metrics pipeline is broken somewhere along a specific chain, and you debug it layer by layer. The chain for **resource metrics** (CPU/memory) is: **kubelet (cAdvisor) → metrics-server (scrapes kubelets) → aggregation layer (`v1beta1.metrics.k8s.io` APIService) → API server → HPA controller / `kubectl top`**.

```bash
kubectl top nodes                                    # does the Metrics API answer at all?
kubectl get apiservices | grep metrics               # APIService Available=True?
kubectl -n kube-system get deploy metrics-server     # running? ready?
kubectl -n kube-system logs deploy/metrics-server    # TLS errors? can't reach kubelet?
kubectl get hpa <name> -o yaml                       # current/target metrics + conditions
kubectl describe hpa <name>                          # events: "unable to fetch metrics"
```

The usual failure points, in order of frequency: (1) **metrics-server not installed** — it's *not* in a vanilla cluster, so HPA on CPU silently never scales; install it. (2) **kubelet TLS** — metrics-server scrapes kubelets over HTTPS and by default validates the kubelet's serving cert, which is often self-signed, so it fails with x509 errors; the common (and on a trusted network, acceptable) fix is `--kubelet-insecure-tls`, or properly issuing kubelet serving certs via the CSR signer. (3) **APIService unavailable** — `kubectl get apiservices v1beta1.metrics.k8s.io` showing `Available=False` means the aggregation layer can't reach the metrics-server Service (network policy, wrong port, pod not ready). (4) **HPA on custom/external metrics** needs a *different* adapter entirely (Prometheus Adapter or KEDA) exposing `custom.metrics.k8s.io`/`external.metrics.k8s.io` — metrics-server only serves resource metrics.

The mental model that prevents flailing: **metrics-server is a short-window, in-memory source for autoscaling and `top` only — it is NOT a monitoring system** (no history, don't query it for dashboards; use Prometheus for that). So separate the questions: "is `kubectl top` working?" tests the resource-metrics pipeline; "is my HPA scaling on a queue depth?" tests a *custom/external* metrics adapter, which is a completely different component. Once you know which pipeline is involved, the APIService status plus the relevant component's logs almost always pinpoint the break.

#### Q73. [Theory] How does Kubernetes RBAC actually evaluate a request, and what are the roles of Roles, ClusterRoles, aggregation, and `system:` groups?

RBAC authorization answers a single question per request: *can this subject perform this verb on this resource (optionally this specific object) in this namespace?* It is **purely additive and default-deny** — there are no deny rules; a request is allowed if *any* RoleBinding/ClusterRoleBinding grants it, otherwise it's denied. Authorization runs *after* authentication (which establishes the subject — a user, group, or service account) and the authorizer chain may include Node, RBAC, and Webhook authorizers (allow if any authorizes).

```
Subject (user / group / serviceaccount)
   │ matched by
RoleBinding / ClusterRoleBinding   ──references──►  Role / ClusterRole (the verbs+resources)
   │                                                   ▲
   │ ClusterRole can be bound by a RoleBinding to       │ aggregationRule pulls in other
   │ grant a cluster-scoped definition in ONE namespace ClusterRoles by label
```

The four pieces and why each exists: a **Role** is namespaced (rules apply within its namespace); a **ClusterRole** is cluster-scoped and can grant access to cluster-scoped resources (nodes, PVs, namespaces themselves), non-resource URLs (`/healthz`), *and* be **reused per-namespace** by referencing it from a namespaced RoleBinding (so you define `view` once and bind it in many namespaces). **Aggregated ClusterRoles** (`aggregationRule` with label selectors) let the built-in `view`/`edit`/`admin` roles automatically absorb permissions from any ClusterRole you label `rbac.authorization.k8s.io/aggregate-to-edit: "true"` — this is how installing a CRD/operator can extend the standard roles to cover its custom resources without editing the built-ins.

The pieces that trip people up: `RoleBinding` referencing a `ClusterRole` grants only within the binding's namespace (a powerful, common pattern); a `ClusterRoleBinding` to a `ClusterRole` is *cluster-wide* and rarely what you want for tenants. The `system:` prefixed groups/users are built-in identities — `system:masters` (mapped to the `cluster-admin` superuser via certs — guard it like root), `system:authenticated`/`system:unauthenticated`, `system:serviceaccounts:<ns>` (every SA in a namespace), `system:nodes` (kubelets, gated by the **Node authorizer** which restricts each kubelet to only the objects relevant to its own node). The essential debugging tool is **`kubectl auth can-i`** (`kubectl auth can-i create deployments -n team-a --as=system:serviceaccount:team-a:ci`, or `--list` to dump everything a subject can do), which evaluates the *actual* authorizer chain rather than your mental model of it — always verify least-privilege grants this way rather than reasoning about YAML.

#### Q74. [Practical] Implement a blue-green deployment and contrast it with canary. When is each the right tool?

Blue-green keeps **two complete environments** — *blue* (current, live) and *green* (new version) — and switches **all** traffic from blue to green atomically by repointing the Service selector once green is verified. Unlike a rolling update (which gradually replaces Pods, so both versions serve simultaneously mid-rollout) or a canary (which sends a *fraction* of traffic to the new version), blue-green has a single, instantaneous cutover and an equally instantaneous rollback (flip the selector back).

```yaml
# Both Deployments run; the Service selector decides who's live.
apiVersion: apps/v1
kind: Deployment
metadata: { name: app-green, labels: { app: app, version: green } }
spec: { replicas: 5, selector: { matchLabels: { app: app, version: green } }, template: { metadata: { labels: { app: app, version: green } }, spec: { containers: [{ name: app, image: app:2.0 }] } } }
---
apiVersion: v1
kind: Service
metadata: { name: app }
spec:
  selector: { app: app, version: blue }   # flip to version: green to cut over
  ports: [{ port: 80, targetPort: 8080 }]
```

```bash
# Verify green privately (port-forward or a separate test Service), then cut over:
kubectl patch service app -p '{"spec":{"selector":{"app":"app","version":"green"}}}'
# Instant rollback:
kubectl patch service app -p '{"spec":{"selector":{"app":"app","version":"blue"}}}'
```

**Trade-offs.** Blue-green's strengths: instant cutover and rollback, full pre-cutover testing of the new version in the real cluster, and *no version mixing* (every request hits one version, which matters when v1 and v2 can't safely coexist). Its costs: you run **2× the capacity** during the transition (expensive for large services), the cutover is **all-or-nothing** (if green has a latent bug that only shows under full production load, 100% of users hit it at once — you trade gradual exposure for cleaner state), and **stateful concerns** are hard (in-flight sessions, database schema changes that both versions must tolerate, draining blue's connections).

**Canary** (Q22) instead ramps a small traffic percentage to the new version, watches SLOs, and rolls back if metrics degrade — limiting *blast radius* by exposing few users at first, at the cost of running both versions simultaneously (so they must be compatible) and needing traffic-splitting infrastructure (Gateway API weights, a mesh, or Argo Rollouts/Flagger to automate the ramp + analysis). **The decision:** use **canary** when you want to *limit blast radius and validate under real traffic gradually* and your versions can coexist (most stateless web services); use **blue-green** when you need *atomic switchover, easy full rollback, and no version mixing*, can afford double capacity, and want to fully test the new environment before any user touches it (e.g., a release where v1/v2 must never run concurrently). Production teams usually automate either with **Argo Rollouts**, which models both strategies as first-class with metric-driven promotion and automatic rollback.

#### Q75. [Theory] What is a service mesh, what problems does it solve, and what does it cost? Compare sidecar vs sidecar-less (ambient) architectures.

A service mesh adds a dedicated **infrastructure layer for service-to-service communication** — handling mTLS encryption/identity, L7 traffic management (retries, timeouts, circuit breaking, traffic splitting), and rich telemetry (golden-signal metrics, distributed traces, per-service topology) — *without* changing application code. It does this by inserting a programmable **data plane** of proxies (Envoy in Istio) in the request path, configured by a **control plane**. The core value proposition: cross-cutting concerns that would otherwise be reimplemented (inconsistently) in every service's code or libraries get pushed down into the platform.

```
                 control plane (config, certs, service discovery)
                        │ pushes config / SDS certs
   ┌──────────────┐     ▼      ┌──────────────┐
   │ Pod A        │  mTLS      │ Pod B        │
   │ app ─► proxy │◄══════════►│ proxy ─► app │   proxies do mTLS, retries,
   └──────────────┘            └──────────────┘   metrics, traffic policy
```

The problems it solves well: **zero-trust networking** (automatic mutual-TLS between every service with workload identity, so you encrypt and authenticate east-west traffic without app changes), **uniform resilience** (consistent retries/timeouts/circuit-breaking/outlier-detection policy across polyglot services), **fine-grained traffic control** (canary/blue-green weight shifting, fault injection, mirroring), and **observability** (you get RED metrics and traces for *every* call for free). These are exactly the things hard to standardize across many teams/languages.

The **costs** are real and why meshes are not free wins: the classic **sidecar** model injects an Envoy proxy into *every Pod*, adding per-Pod memory/CPU overhead, **per-hop latency** (two extra proxy traversals per request), operational complexity (proxy lifecycle, the Job/sidecar-completion problem solved by native sidecars per Q60), and a steep learning curve. This drove the **sidecar-less / ambient** architecture (Istio ambient mode, and Cilium's eBPF-based mesh): instead of a proxy per Pod, a **per-node L4 component** (Istio's *ztunnel*, or Cilium's eBPF datapath) handles mTLS and basic routing, and L7 policy is applied only by an *optional* per-namespace **waypoint** proxy when you actually need L7 features. The benefit is dramatically lower overhead (no per-Pod proxy, pay for L7 only where used) and simpler upgrades (no per-Pod proxy version churn); the trade-off is a newer, less battle-tested model and a more complex mental model of where enforcement happens. The senior judgment: a mesh earns its complexity when you have **many services across teams/languages needing uniform mTLS, traffic policy, and observability** — for a handful of services, library-level retries plus Gateway API and a metrics agent may deliver 80% of the value at a fraction of the cost, and ambient/eBPF meshes are increasingly the answer when you *do* need a mesh but want to shed the sidecar tax.

### 🟠 Advanced — extended

#### Q76. [Practical] Compare Cluster Autoscaler and Karpenter. How does node autoscaling actually decide to add/remove nodes, and what are the failure modes?

Both add nodes when Pods can't schedule and remove nodes that are underutilized, but they take opposite approaches to *node shape*. **Cluster Autoscaler (CA)** works with pre-defined **node groups** (e.g., cloud auto-scaling groups, each a fixed instance type): when Pods are `Pending` due to insufficient resources, CA simulates which node group's template would fit them and scales that group's desired count up; when a node has been underutilized below a threshold for a cooldown and its Pods can be rescheduled elsewhere (honoring PDBs), it scales the group down. **Karpenter** (AWS-origin, now broader) is **groupless** — it watches Pending Pods, computes the *optimal instance type(s)* to fit them (picking from many types/sizes/architectures/capacity-types on the fly), and provisions nodes directly via the cloud API, then **consolidates** aggressively (replacing or removing nodes to pack workloads onto fewer/cheaper instances).

```
Pending Pods (scheduler can't fit them)
        │
   ┌────┴─────────────────────────┐
   │ Cluster Autoscaler            │ Karpenter
   │ pick a NODE GROUP whose       │ compute the BEST instance type(s) for the
   │ template fits → +1 to its ASG │ exact pending pods → launch node directly
   │ scale-down: underused node    │ consolidation: bin-pack onto fewer/cheaper
   │ below threshold for cooldown  │ nodes, replace with cheaper/spot, drift
   └───────────────────────────────┘
```

The decision inputs that matter: both respect Pod **resource requests** (so wrong requests = wrong scaling — over-requesting wastes money on idle nodes, under-requesting causes OOM/throttling), **scheduling constraints** (affinity, taints, topology spread — a node that satisfies the constraints must be provisionable), and **PDBs** on scale-down. Karpenter additionally optimizes cost (choosing cheaper/spot instances, consolidating) and reacts faster (no fixed group templates), which is why it's largely displaced CA on AWS for flexibility and cost; CA remains common where node groups are mandated or for non-AWS clouds.

Failure modes to know: **Pending Pods that no node shape can satisfy** (a request bigger than the largest instance, or a constraint like a zone with no capacity) — both autoscalers will try and fail, leaving Pods Pending; watch their events/logs. **Scale-down blocked by un-evictable Pods** — a Pod with a restrictive PDB, a `kube-system` Pod without a PDB, local storage, or the `cluster-autoscaler.kubernetes.io/safe-to-evict: false` annotation pins a node alive, so the cluster never shrinks (a top cause of "why is my bill not going down?"). **Spot interruptions** — Karpenter/CA must handle node termination notices and drain gracefully (interruption handling), or workloads get killed abruptly. **Flapping** — too-tight thresholds cause add/remove churn. The right setup pairs node autoscaling with correct **requests**, **PDBs that allow movement**, **topology spread** so consolidation can't co-locate everything, and **graceful termination** so node removal doesn't drop traffic.

#### Q77. [Practical] Walk through diagnosing and surviving a control-plane certificate / kubeconfig expiry incident on a self-managed cluster.

On self-managed (e.g., kubeadm) clusters, the control plane is a web of **PKI certificates** (the cluster CA, the API server's serving cert, the API-server→kubelet client cert, etcd peer/client certs, the front-proxy CA, and the admin/component **kubeconfig** client certs). kubeadm-issued certs default to **1-year validity**, so a cluster that's been running ~12 months without an upgrade (which silently renews them) can suddenly have components reject each other — a brutal, self-inflicted outage. Symptoms: `kubectl` fails with `x509: certificate has expired or is not yet valid`, the API server can't talk to kubelets (`x509`), etcd quorum breaks if etcd certs expired, and you may be locked out entirely because *your admin kubeconfig cert also expired*.

```bash
kubeadm certs check-expiration              # table of every cert + kubeconfig and expiry
openssl x509 -in /etc/kubernetes/pki/apiserver.crt -noout -enddate   # spot-check one
# Renew everything (kubeadm regenerates from the long-lived CA):
kubeadm certs renew all                      # renews certs AND control-plane kubeconfigs
# Restart control-plane static pods to pick up new certs (move-and-restore manifests, or):
crictl ps | grep -E 'apiserver|controller|scheduler|etcd'   # then restart kubelet
systemctl restart kubelet
# Refresh YOUR admin access if its cert expired:
cp /etc/kubernetes/admin.conf ~/.kube/config
```

The diagnosis discipline: `kubeadm certs check-expiration` is the single most useful command — it lists every cert *and* the embedded kubeconfig client certs with expiry dates and whether each is externally managed. The recovery hinges on one fact: **the cluster CA is long-lived (10 years)**, so as long as the *CA* hasn't expired, you can re-issue all the leaf certs from it; if the CA itself expired you're into a far harder full re-bootstrap. After `kubeadm certs renew all`, you must **restart the control-plane components** (they're static Pods — they load certs at start, so they won't pick up renewals until restarted) and refresh your own `~/.kube/config` from the regenerated `admin.conf`.

The deeper lessons for the postmortem: (1) **kubelet client certs auto-rotate** (kubelet requests renewal via the CSR API before expiry) but **control-plane and admin certs do not auto-rotate by default** — only a `kubeadm upgrade` or explicit `certs renew` refreshes them, which is exactly why long-uptime, never-upgraded clusters die at the 1-year mark. (2) **Monitor cert expiry proactively** (alert on `apiserver_client_certificate_expiration_seconds` and node cert metrics, or a cron running `certs check-expiration`) so this is a planned renewal, never a surprise. (3) This is a strong argument for **managed control planes** (EKS/GKE/AKS), which handle control-plane PKI for you. The interview signal is recognizing that an "expired certificate" outage is rarely random bad luck — it's a missed renewal on a predictable clock, and the fix-forward (renew from the still-valid CA + restart components + refresh kubeconfig) plus the prevention (monitoring + regular upgrades) both matter.

#### Q78. [Theory] How does Kubernetes audit logging work, what are the stages and levels, and how do you design an audit policy that's useful without drowning you?

The kube-apiserver can emit an **audit log** recording *who did what to which object, when, and what the outcome was* — the authoritative record for security forensics, compliance, and debugging "who deleted the namespace?". Auditing happens *inside the apiserver request chain* (per Q44, right after authentication), so it captures requests even if they're later rejected by authorization or admission. Each request can generate events at multiple **stages** and is recorded at a configurable **level** determined by an **audit policy** that matches rules top-to-bottom.

```
Stages:   RequestReceived → ResponseStarted (for long-running: watch) → ResponseComplete → Panic
Levels:   None      → don't log this match
          Metadata  → who/verb/resource/namespace/timestamp/response code (NO bodies)
          Request   → metadata + the request body (what was sent)
          RequestResponse → metadata + request AND response bodies (most verbose)
```

```yaml
# audit-policy.yaml (apiserver: --audit-policy-file=...  --audit-log-path=...)
apiVersion: audit.k8s.io/v1
kind: Policy
omitStages: ["RequestReceived"]            # skip the noisy early stage
rules:
  - level: None                            # drop high-volume, low-value noise
    users: ["system:kube-proxy"]
    verbs: ["watch", "list"]
  - level: None
    resources: [{ group: "", resources: ["events", "endpoints", "endpointslices"] }]
  - level: RequestResponse                 # full detail for the sensitive stuff
    resources: [{ group: "", resources: ["secrets", "configmaps"] }, { group: "rbac.authorization.k8s.io", resources: ["*"] }]
  - level: Metadata                         # everything else: who/what/when, no bodies
```

The design tension is **signal vs volume**: `RequestResponse` on everything would log the body of every watch and status update and bury you (and cost a fortune to store/ship). A good policy is a *funnel* — explicitly drop the highest-volume, lowest-value traffic first (`watch`/`list` from system components, the `events`/`endpoints` churn), capture **full bodies only for security-sensitive resources** (Secrets, RBAC, admission config, serviceaccounts, certificate signing requests), and default the rest to `Metadata` so you still know *who did what* without storing payloads. Order matters because the first matching rule wins.

Operationally: audit logs go to a **backend** — a log file (then shipped by a node agent) or a **webhook** to an external SIEM (Splunk, an ELK stack, a cloud logging service) for tamper-evident, queryable retention. On **managed clusters** you typically *enable* control-plane audit logs to the cloud's logging service rather than configuring the policy file directly (the provider exposes a subset of knobs). The forensic payoff: when an incident happens ("a Secret was exfiltrated", "who scaled prod to zero?"), the audit log is the *only* reliable answer — which is why for any regulated or security-sensitive cluster, a thoughtful audit policy plus shipping to an immutable store is non-negotiable, and why you tune it deliberately rather than logging everything (cost, noise) or nothing (blind during incidents).

#### Q79. [Practical] Design an observability stack for Kubernetes: metrics, logs, and traces. What are the standard components and the common pitfalls?

Observability on Kubernetes rests on **three pillars** — metrics (aggregatable numbers over time: rates, utilization, latencies), logs (discrete event records), and traces (the path of a single request across services) — and the platform-engineering job is wiring each from every Pod/node to a queryable backend *without* coupling apps to the collector and without the telemetry pipeline itself becoming a reliability/cost problem.

```
METRICS:  app /metrics + node-exporter + kube-state-metrics + cAdvisor
             → Prometheus (scrape) / OpenTelemetry → long-term store (Thanos/Mimir/Cortex)
             → Grafana (dashboards) + Alertmanager (alerts)
LOGS:     container stdout/stderr → node agent (Fluent Bit / Vector) DaemonSet
             → Loki / Elasticsearch / cloud logs   (NEVER write logs to local files only)
TRACES:   app instrumented w/ OpenTelemetry SDK → OTel Collector → Jaeger / Tempo
CORRELATION: consistent labels (pod, namespace, node via Downward API) + trace/span IDs in logs
```

The standard components and *why* each exists: **Prometheus** scrapes `/metrics` endpoints (pull model — it discovers targets via the Kubernetes API, so new Pods are auto-scraped); **kube-state-metrics** exposes *object* state (Deployment replicas desired vs ready, Pod phase, PVC status) which cAdvisor/node metrics don't; **node-exporter** gives host metrics; **cAdvisor** (in the kubelet) gives per-container resource usage. For logs, a **DaemonSet log agent** (Fluent Bit, Vector) tails container stdout/stderr on every node and ships it — which is why apps should **log to stdout/stderr, not files** (the platform captures streams; files require sidecars and risk filling node disk, per Q64). For traces, **OpenTelemetry** is now the vendor-neutral standard: instrument once with the OTel SDK, route through an **OTel Collector** (which can also receive metrics and logs, increasingly unifying all three pillars), to a tracing backend (Jaeger/Tempo).

The pitfalls that separate a working stack from an expensive, useless one: (1) **Metric cardinality explosion** — putting unbounded values (user IDs, request paths with IDs, pod names) into Prometheus *labels* multiplies time series and can OOM Prometheus; keep labels low-cardinality and bounded. (2) **No long-term storage / HA** — vanilla Prometheus is single-node with limited retention; production needs Thanos/Mimir/Cortex for durability and global query, or a managed service. (3) **Log volume cost** — verbose logs at scale dominate storage bills and can fill node disks (evicting innocent Pods); set log levels sanely, sample, and rotate. (4) **The observability stack sharing fate with what it observes** — if Prometheus/Alertmanager run *in* the cluster they're meant to watch, a cluster outage blinds you exactly when you need visibility; run alerting/critical monitoring **out-of-cluster or in a separate cluster**. (5) **No correlation** — without consistent `pod`/`namespace`/`node` labels (inject via Downward API, Q68) and trace IDs threaded into logs, you can't pivot from a metric spike to the relevant logs to the offending trace — and that pivot *is* the entire point of observability. The mature stack is increasingly **OpenTelemetry-centric** (one instrumentation standard and collector for all three pillars) with Prometheus-compatible metrics, a scalable backing store, and monitoring deliberately isolated from the failure domain it observes.

#### Q80. [Theory] Explain the security model of running workloads: securityContext, capabilities, runAsNonRoot, readOnlyRootFilesystem, seccomp, and Pod Security Admission levels.

Container security in Kubernetes is layered Linux hardening expressed declaratively, and the goal is **minimizing what a compromised container can do** — because container isolation is *not* a security boundary as strong as a VM (shared kernel), every reduction in privilege shrinks the blast radius of an escape. The primary tool is **`securityContext`** (set per-Pod and per-container), which maps to Linux primitives:

```yaml
spec:
  securityContext:                      # Pod-level
    runAsNonRoot: true                  # refuse to start if the image runs as UID 0
    runAsUser: 10001
    fsGroup: 10001                      # group ownership for mounted volumes
    seccompProfile: { type: RuntimeDefault }   # block dangerous syscalls
  containers:
    - name: app
      securityContext:                  # container-level (overrides Pod-level)
        allowPrivilegeEscalation: false # no setuid/gaining privileges
        readOnlyRootFilesystem: true    # immutable FS; writes only to mounted volumes
        capabilities:
          drop: ["ALL"]                 # drop all Linux capabilities...
          add: ["NET_BIND_SERVICE"]     # ...add back only what's truly needed
        privileged: false               # privileged = full host access; almost never
```

What each buys: **`runAsNonRoot`/`runAsUser`** prevents running as root inside the container (root-in-container is one syscall away from root-on-node if there's an escape); **`capabilities: drop ALL` then add-back** replaces the default ~14 Linux capabilities with the minimal set the app needs (e.g., `NET_BIND_SERVICE` to bind port <1024) — most apps need *none*; **`readOnlyRootFilesystem`** makes the container FS immutable so an attacker can't write tools/persist, forcing all writes to explicit `emptyDir`/volume mounts (also catches apps that sloppily write to `/`); **`allowPrivilegeEscalation: false`** blocks `setuid` binaries and `no_new_privs`; **`seccompProfile: RuntimeDefault`** restricts the syscall surface to the runtime's curated allowlist (blocking exotic syscalls used in many escapes — `RuntimeDefault` should be the baseline everywhere). **`privileged: true`** and host namespaces (`hostNetwork/hostPID/hostIPC`) and `hostPath` mounts are the danger zone — they grant near-host access and should be denied to normal workloads and allowlisted only for node-infra DaemonSets.

Enforcing this fleet-wide is **Pod Security Admission (PSA)** — the built-in admission controller (replacing the removed PodSecurityPolicy) that applies one of three **Pod Security Standards** per namespace via labels: **privileged** (no restrictions — for trusted infra), **baseline** (blocks the obviously-dangerous: privileged, hostNetwork, hostPath, most cap adds), and **restricted** (the hardened target: enforces runAsNonRoot, drop-ALL caps, seccomp RuntimeDefault, readOnlyRootFilesystem-friendly, no privilege escalation). You set it per namespace and per mode (`enforce`/`audit`/`warn`):

```yaml
kind: Namespace
metadata:
  name: team-a
  labels:
    pod-security.kubernetes.io/enforce: restricted   # reject non-compliant Pods
    pod-security.kubernetes.io/warn: restricted       # warn on kubectl
    pod-security.kubernetes.io/audit: restricted      # record in audit log
```

PSA's limit is that it only does the *standard* checks — for organization-specific rules (require specific registries, ban `:latest`, mandate resource limits, enforce labels) you layer a **policy engine** (Kyverno or OPA Gatekeeper, or in-tree `ValidatingAdmissionPolicy` with CEL per Q43/Q50). The defense-in-depth principle (Q28): assume a container *will* be compromised, and ensure that when it is, drop-ALL-caps + non-root + read-only-FS + seccomp + no-escalation leaves the attacker with almost nothing to pivot from — and for genuinely untrusted workloads, escalate to a **sandboxed runtime** (gVisor/Kata via RuntimeClass) so even a kernel exploit is contained.

#### Q81. [Practical] You need to run untrusted/multi-tenant code. How do RuntimeClass, gVisor, and Kata Containers provide stronger isolation, and what do they cost?

Standard containers share the **host kernel** — namespaces and cgroups isolate *what they see and use*, but a kernel vulnerability exploited from inside a container can escape to the node and, via the kubelet's credentials, potentially the whole cluster. For **untrusted code** (running customer-submitted workloads, CI of arbitrary PRs, true hostile multi-tenancy) that shared kernel is an unacceptable single point of failure, and the answer is a **sandboxed runtime** selected per-Pod via **RuntimeClass**.

```yaml
apiVersion: node.k8s.io/v1
kind: RuntimeClass
metadata: { name: gvisor }
handler: runsc                # CRI runtime handler configured in containerd
---
apiVersion: v1
kind: Pod
metadata: { name: untrusted-job }
spec:
  runtimeClassName: gvisor    # this Pod runs in the gVisor sandbox, not runc
  containers: [{ name: job, image: customer/code:latest }]
```

The two leading approaches sandbox differently. **gVisor** (`runsc`) interposes a **user-space kernel** between the container and the host: it implements the Linux syscall interface itself and only forwards a tiny, audited subset to the host kernel, so a container's syscalls hit gVisor's reimplementation rather than the host kernel directly — dramatically shrinking the host kernel attack surface. **Kata Containers** takes the VM route: each Pod (or container) runs inside a **lightweight micro-VM** with its *own* guest kernel, using hardware virtualization (KVM) for a hard isolation boundary — essentially "containers with a VM around them," giving VM-grade isolation with container-like UX and density.

```
runc (default)      :  container ─► HOST kernel directly        (fast, shared kernel)
gVisor (runsc)      :  container ─► user-space kernel ─► tiny host syscall surface
Kata                :  container ─► GUEST kernel in micro-VM ─► KVM ─► host  (VM boundary)
```

The costs are why you don't use these everywhere: **performance overhead** — gVisor adds syscall-interception latency (notable for syscall-heavy or I/O-heavy workloads) and some apps hit unimplemented/edge-case syscalls; Kata adds VM boot time, memory overhead per VM, and requires **nested virtualization or bare-metal/virtualization-capable nodes** (many managed clusters restrict this). Both add **operational complexity** (installing/configuring the handler in containerd, dedicated node pools tainted for sandboxed workloads, compatibility testing). The decision framework: use the **default runtime** for first-party trusted code (the vast majority of workloads); reach for **gVisor** when you want a strong-but-lighter kernel-surface reduction for semi-trusted workloads and can tolerate syscall overhead; reach for **Kata** when you need near-VM isolation (truly hostile tenants, regulatory hard isolation) and have the virtualization-capable infrastructure. And recognize the alternative the staff engineer always weighs (Q65/Q28): for the strongest isolation, **separate clusters or separate node pools per tenant** may be simpler and safer than sandboxing within a shared cluster — sandboxed runtimes are the middle ground when you want shared-cluster economics *with* a meaningfully harder isolation boundary than plain containers.

#### Q82. [Theory] How does supply-chain security work for container images — signing (cosign/Sigstore), admission verification, SBOMs, and scanning — and where does each fit?

The container supply chain spans *build → registry → admission → runtime*, and an attacker can inject malicious code at any point: a poisoned base image, a compromised CI pipeline, a tampered image in the registry, or a typosquatted dependency. Supply-chain security is the set of controls that establish **what's in an image, that it came from a trusted source, and that only verified images run** — and each tool addresses a different question.

```
BUILD ─────────► REGISTRY ─────────► ADMISSION ─────────► RUNTIME
  │ generate SBOM   │ store signed      │ verify signature   │ runtime detection
  │ scan deps       │ image + signature │ + provenance + scan │ (Falco) for drift
  │ sign (cosign)   │ + attestations    │ results, ELSE REJECT│
```

The pieces: **SBOM (Software Bill of Materials)** — a machine-readable inventory (SPDX/CycloneDX) of every package/layer in an image, generated at build (Syft, Docker SBOM). It answers "*what's in this image?*" — essential for fast incident response ("are we affected by CVE-X?" becomes a query, not a frantic rebuild-and-inspect). **Vulnerability scanning** (Trivy, Grype, Clair) cross-references the SBOM/image against CVE databases — run it in CI (fail the build on critical CVEs) *and* continuously in the registry (newly-disclosed CVEs affect images that were clean yesterday). **Signing with cosign/Sigstore** answers "*did this image come from us, unmodified?*" — cosign signs the image digest, and Sigstore's **keyless signing** (OIDC identity + the Fulcio CA + the Rekor transparency log) lets CI sign with its workload identity without managing long-lived keys, recording the signature in a tamper-evident public log. **Attestations / provenance (SLSA)** go further — signed statements about *how* the image was built (which pipeline, which source commit), so you can require that an image was produced by *your* trusted builder, not someone's laptop.

The enforcement point that ties it together is **admission verification**: a policy controller (Kyverno, OPA Gatekeeper with its image-verification rules, or the Sigstore policy-controller) intercepts Pod creation and **rejects images that aren't signed by a trusted identity, don't have valid provenance, or fail a scan-results gate** — so the cluster *cannot run* an unverified image, regardless of how it got into the registry.

```yaml
# Kyverno: only run images signed by our CI identity (keyless verification)
apiVersion: kyverno.io/v1
kind: ClusterPolicy
metadata: { name: require-signed-images }
spec:
  validationFailureAction: Enforce
  rules:
    - name: verify-signature
      match: { any: [{ resources: { kinds: ["Pod"] } }] }
      verifyImages:
        - imageReferences: ["registry.example.com/*"]
          attestors:
            - entries:
                - keyless:
                    issuer: "https://token.actions.githubusercontent.com"   # our CI's OIDC issuer
                    subject: "https://github.com/acme/*"
```

Where each fits: **build-time** generates SBOMs and signs (provenance + signature); **registry** stores them and runs continuous rescans; **admission** is the *gate* that enforces "signed + provenanced + scan-passing, else reject"; **runtime** (Falco/eBPF detection) catches what static checks miss (a verified image behaving maliciously at runtime). The other foundational hygiene: **pin digests, never `:latest`** (so admission verifies the *exact* bytes that run, and a moved tag can't swap in unverified content), use minimal/distroless base images (smaller attack surface and SBOM), and treat the CI system itself as a high-value target (its signing identity is the root of trust). The interview-grade synthesis: supply-chain security is **defense-in-depth across the image's whole lifecycle**, and the linchpin is *admission-time verification* — without a gate that refuses unverified images, signing and scanning are advisory; with it, they're enforced.

### 🔴 Expert — extended

#### Q83. [Practical] Run a realistic etcd disaster-recovery drill: backup, restore, and recovering a cluster after etcd data loss. What are the gotchas?

etcd holds the *entire* cluster state, so its loss is the worst-case control-plane disaster — and the only reliable recovery is **restore from a snapshot, then let controllers reconcile** everything else. The drill has three phases, and the gotchas are what separate a tested plan from a hope (Q26).

```bash
# 1. BACKUP (run regularly, automated, off-node):
ETCDCTL_API=3 etcdctl snapshot save /backup/etcd-$(date +%F-%H%M).db \
  --endpoints=https://127.0.0.1:2379 \
  --cacert=/etc/kubernetes/pki/etcd/ca.crt \
  --cert=/etc/kubernetes/pki/etcd/server.crt \
  --key=/etc/kubernetes/pki/etcd/server.key
etcdctl snapshot status /backup/etcd-....db -w table   # verify it's readable + revision/size

# 2. RESTORE (into a NEW data dir — never overwrite a live one):
ETCDCTL_API=3 etcdctl snapshot restore /backup/etcd-....db \
  --data-dir=/var/lib/etcd-restored \
  --name=<member-name> --initial-cluster=<member>=https://<ip>:2380 \
  --initial-advertise-peer-urls=https://<ip>:2380

# 3. Point etcd at the restored data dir, restart etcd, then the apiserver.
```

**Phase 1 (backup)** must be automated, frequent (RPO = how much state loss you tolerate; for a busy cluster, hourly+), shipped **off the etcd node** (a backup on the failed disk is worthless), and **verified** (`snapshot status` reads it back, and ideally a periodic *test restore* into a throwaway environment — an unverified backup is the #1 DR failure). **Phase 2 (restore)** rebuilds an etcd data directory from the snapshot; the critical gotcha is you **restore into a fresh data dir** and, for a multi-member cluster, you restore one member then **re-add the others as fresh members** (you do *not* restore the same snapshot independently into each — that creates divergent clusters; you restore once and let the rest join and sync via Raft). **Phase 3** points etcd at the restored dir and restarts the control plane.

The gotchas that bite: (1) **Time travel / lost writes** — restoring a snapshot rewinds the cluster to that point, so everything created/changed *after* the snapshot is gone (Pods, Secrets, RBAC changes). Controllers reconcile *declared* state (Deployments recreate Pods), but anything that was only-in-etcd-and-newer-than-the-snapshot is lost — which is why RPO matters and why you snapshot frequently. (2) **Stale resources / orphans** — Pods that were deleted after the snapshot reappear as desired state; nodes' actual state may not match restored expectations, so expect a reconciliation storm and some manual cleanup. (3) **PKI/identity mismatch** — restoring on different nodes/IPs requires the etcd peer URLs and certs to match the new topology, or members won't form quorum. (4) **Quorum during restore** — for HA etcd you take the cluster down to restore consistently; a rolling restore risks split-brain. (5) **Managed clusters** abstract all of this — EKS/GKE/AKS back up and restore etcd for you (you can't run `etcdctl` against their control plane), which is a major argument for managed control planes. The drill's real value is *practicing* it before you need it: the first time you run a restore should never be during a live outage, and the postmortem question "what was our actual RPO and how long did recovery take?" is answerable only if you've rehearsed.

#### Q84. [Theory] How do admission webhooks, finalizers, and operators combine to create — and to *break* — clusters? Give the failure cascades and mitigations.

These three extension mechanisms (Q20, Q38, Q43) are individually powerful, but their interactions produce the most insidious cluster-level failures — *deadlocks* where the very machinery meant to manage the cluster prevents the cluster from operating or recovering. A staff engineer must reason about these failure cascades because they're not in the happy-path docs; they emerge from the *ordering and dependency coupling* between extensions.

```
CASCADE 1 — Webhook self-lock:
  ValidatingWebhook matches pods/* with failurePolicy: Fail; its backend pods crash
  → all Pod creation now fails → you can't recreate the webhook's own pods
  → cluster can't self-heal; even kube-system workloads may be blocked.

CASCADE 2 — Finalizer deadlock:
  Operator sets a finalizer on its CRs; operator is uninstalled/crashed
  → CRs stuck "Terminating" forever (nothing removes the finalizer)
  → namespace deletion hangs (a namespace won't delete while it contains stuck objects).

CASCADE 3 — Operator + webhook circular dependency:
  Operator's webhook validates the operator's own CRDs; during upgrade the webhook
  is down → operator can't reconcile → can't bring the webhook back. Chicken-and-egg.
```

**Cascade 1 (webhook self-lock)** is the most dangerous: a webhook with `failurePolicy: Fail` matching a broad resource (especially `pods` cluster-wide) becomes a hard dependency for *every* matching write. If its backend is unavailable, the API server rejects those writes — and you can't create the Pods that would restore the webhook. Mitigations: scope `namespaceSelector`/`objectSelector` tightly and **exclude `kube-system` and the webhook's own namespace** (so infra can always come up), set conservative `timeoutSeconds`, prefer `failurePolicy: Ignore` for non-critical mutators, run webhook backends **HA across nodes/zones**, and — increasingly — replace webhooks with in-tree **ValidatingAdmissionPolicy (CEL)** which has *no backend to fail* (Q50). The break-glass recovery is deleting the offending `WebhookConfiguration` (which requires that *that* write isn't itself gated — webhooks don't gate their own config object, which is the escape hatch).

**Cascade 2 (finalizer deadlock)** is the "namespace stuck in Terminating" mystery: a finalizer is a *contract* that some controller will do cleanup before deletion, so if that controller is gone, the contract can never be satisfied and the object — and any namespace containing it — hangs forever. The *correct* fix is to **reinstate the controller** so it completes cleanup and removes its finalizer; the blunt fix (patching out the finalizer) skips the cleanup it protected, potentially **leaking external resources** (a cloud load balancer, a volume, an external DB), so it's a last resort done knowingly. The prevention is operator authors making finalizer logic **idempotent and resilient**, and operators handling their own uninstall by removing finalizers from their CRs *before* the controller goes away.

**Cascade 3 (circular dependency)** arises when an operator's admission webhook validates the operator's own resources, or when bootstrapping order isn't respected — the upgrade takes the webhook down, the operator needs the webhook to reconcile, and neither can proceed. Mitigations: design operators so the **control loop doesn't hard-depend on its own webhook** for the critical path, use **leader election** and conservative rollout, and order bootstrap so foundational components (CNI, CoreDNS, the policy engine itself) are never gated by extensions that depend on *them*. The unifying principle: **every admission webhook and finalizer is a synchronous dependency injected into the cluster's control path**, and the question to always ask is "*what happens to the cluster's ability to operate and recover if this extension is down?*" — if the answer is "the cluster bricks," you've created a single point of failure that must be scoped, made HA, made `failurePolicy: Ignore`, or replaced with a webhook-free mechanism. The ones who've operated clusters know these cascades by scar tissue; the ones who haven't are surprised by them at 3 a.m.

#### Q85. [Practical] HPA is flapping (scaling up and down repeatedly) and reacting too slowly to spikes. How do you tune it, and when do you outgrow CPU-based HPA?

HPA computes desired replicas from a simple ratio — `desiredReplicas = ceil(currentReplicas × currentMetricValue / targetValue)` — sampled on an interval (default ~15s). **Flapping** (oscillating replica counts) and **sluggishness** (slow to react to spikes, slow to reclaim after) are the two failure modes, and both are tuned via the `behavior` block (autoscaling/v2) plus choosing the *right metric*.

```yaml
spec:
  metrics:
    - type: Resource
      resource: { name: cpu, target: { type: Utilization, averageUtilization: 60 } }
  behavior:
    scaleUp:
      stabilizationWindowSeconds: 0       # react to spikes immediately (no smoothing up)
      policies:
        - { type: Percent, value: 100, periodSeconds: 30 }   # at most double per 30s
        - { type: Pods,    value: 4,   periodSeconds: 30 }    # ...or +4 pods, whichever
      selectPolicy: Max                    # use the most aggressive up-policy
    scaleDown:
      stabilizationWindowSeconds: 300       # wait 5 min of low load before scaling down
      policies:
        - { type: Percent, value: 10, periodSeconds: 60 }     # shed at most 10%/min (gentle)
```

**Why it flaps:** scaling decisions change load, which changes the metric, which triggers the opposite decision — a feedback loop. The fixes: (1) a **`scaleDown.stabilizationWindowSeconds`** (default 300s) makes HPA consider the *maximum* desired-replicas over the window before scaling down, so a brief dip doesn't immediately shrink the fleet that a returning spike then needs — asymmetric tuning (**scale up fast, scale down slow**) is the standard cure for flapping. (2) Conservative **scaleDown policies** (e.g., 10%/min) prevent yanking capacity. (3) A sane **target utilization** with headroom — targeting 90% CPU leaves no room for the lag between "metric high" and "new Pods ready," guaranteeing overshoot and oscillation; 50–70% is typical. **Why it's slow to spike:** the inherent lag is *scrape interval + HPA period + Pod scheduling + image pull + app warmup (startupProbe)* — so for bursty traffic, set `scaleUp.stabilizationWindowSeconds: 0` and aggressive up-policies, pre-pull images, keep `minReplicas` high enough to absorb the warmup gap, and recognize that **HPA on CPU is inherently reactive** — by the time CPU is high, you're already behind.

**When you outgrow CPU-based HPA:** CPU utilization is a *lagging proxy* for the thing you actually care about (latency, queue depth, request rate). You outgrow it when (a) the workload is **I/O- or event-bound** (a queue consumer's CPU stays low while the backlog explodes — you should scale on **queue depth/lag**, not CPU), (b) you need **scale-to-zero** (CPU-based HPA can't, and idle Pods waste money), or (c) the **leading indicator** is something external (Kafka consumer lag, SQS depth, requests-per-second, p99 latency, a business metric). The progression: **resource-metric HPA (CPU/mem) → custom-metrics HPA via Prometheus Adapter** (scale on app metrics like RPS or in-flight requests exposed through `custom.metrics.k8s.io`) **→ KEDA** for event-driven and scale-to-zero (KEDA has dozens of *scalers* — Kafka, SQS, RabbitMQ, Prometheus, cron — and creates/manages the HPA for you, scaling from 0→1 on the first event and back to 0 when idle). The senior framing: **scale on the leading indicator of saturation for *your* workload, not the convenient default** — CPU is fine for CPU-bound web tiers, but a queue worker scaled on CPU will always be too late, and the tuning of `behavior` windows is about matching the autoscaler's reaction asymmetry to the cost of being wrong in each direction (under-provisioning = dropped requests, over-provisioning = wasted money).

#### Q86. [Theory] How do PriorityClass-based preemption, PodDisruptionBudgets, the descheduler, and node-pressure eviction interact? Reconcile the competing eviction mechanisms.

Kubernetes has *several distinct mechanisms* that can remove a running Pod, owned by *different components*, triggered by *different conditions*, and respecting *different rules* — and conflating them causes both confusion ("why did my Pod get killed?") and incidents (mechanisms working at cross-purposes). The senior skill is holding the full taxonomy and knowing which one fired.

```
Mechanism            Owner            Trigger                      Respects PDB?  Respects QoS/priority?
──────────────────────────────────────────────────────────────────────────────────────────────────
Scheduler preemption kube-scheduler   a higher-priority Pod is      tries to,     evicts LOWER priority
                                       Pending & needs room          last-resort no  first
Node-pressure        kubelet          node low on mem/disk/pids     NO (it's        evicts BestEffort →
eviction                              (involuntary, local)          involuntary)    Burstable>req → by priority
API-initiated        eviction API     drain / autoscaler / human    YES (blocks if  n/a (you choose targets)
eviction (drain)     (clients)        (voluntary)                   below minAvail) 
Descheduler          descheduler      rebalancing policy (e.g.,     YES (uses       configurable strategies
(optional add-on)    (separate)       pods violate spread, node     eviction API)
                                       too full, affinity drift)
```

The crucial distinctions: **node-pressure eviction** is the kubelet protecting *its own node* from running out of memory/disk before the kernel OOM-killer fires system-wide (Q51/Q64) — it's **involuntary**, so it does **NOT** respect PDBs (a node about to die can't wait for your availability budget; the alternative is the kernel killing critical daemons). It chooses victims by **QoS then priority** (BestEffort first, Burstable over-request next, Guaranteed last; within ties, lower priority first). **Scheduler preemption** (Q36) is a *different* eviction: the *scheduler* deletes lower-priority Pods to make room for a Pending higher-priority Pod — it tries to respect PDBs but will violate them as a last resort to schedule a critical Pod. **API-initiated eviction** (what `kubectl drain` and the Cluster Autoscaler use) is **voluntary** and fully **respects PDBs** — it *blocks* rather than violating `minAvailable`, which is exactly why a bad PDB makes a node undrainable (Q25). The **descheduler** is an optional add-on that periodically *rebalances* by evicting Pods (via the PDB-respecting eviction API) that violate current scheduling preferences — because the scheduler only decides placement *at schedule time* and never moves a Pod afterward, so over time Pods drift (a node that filled up, spread constraints violated by later scheduling, affinity that now points elsewhere); the descheduler corrects this drift, and the scheduler re-places the evicted Pods better.

The interactions that cause real incidents: a **PDB too strict** blocks voluntary eviction (drain/autoscaler scale-down) — node upgrades stall, the bill doesn't shrink. But that same PDB does **nothing** to stop node-pressure eviction (involuntary) — so a Pod can be PDB-protected and *still* get evicted under memory pressure, surprising people who think a PDB guarantees availability (it only guards *voluntary* disruptions). **Priority inflation** (everyone marks themselves critical, Q36) breaks both preemption ordering and node-pressure victim selection. The **descheduler fighting the scheduler** is a classic misconfiguration — an over-eager descheduler policy evicts Pods the scheduler then re-places identically (or worse), causing churn; tune its strategies and thresholds conservatively. The reconciliation principle: **separate the concerns by axis** — *voluntary vs involuntary* (PDB applies only to voluntary), *who owns it* (kubelet for node-pressure, scheduler for preemption, eviction-API clients for drain, descheduler for rebalancing), and *what it respects* (QoS/priority for the involuntary/preemption paths, PDBs for the voluntary path) — and set **QoS (requests=limits for critical), a small deliberate priority hierarchy, PDBs that allow movement, and descheduler policies that align with the scheduler's** so the mechanisms cooperate instead of fighting. When debugging "why was my Pod killed?", the `kubectl describe`/events + the *kind* of message (`Preempted by...`, `The node was low on resource: memory`, `Evicted` from drain) tells you *which* mechanism fired — and that determines whether a PDB, a priority change, a resource resize, or a descheduler tweak is the fix.

#### Q87. [Practical] A multi-cluster fleet has accumulated configuration drift and inconsistent policy. Design the remediation and prevention strategy.

Configuration drift across a fleet — clusters on different versions, with divergent RBAC, network policies, add-on versions, and resource quotas — is the predictable failure mode of the many-clusters model (Q65), and it manifests as "works on cluster A, breaks on cluster B," security gaps (one cluster missing a policy), and unmaintainable operational toil. The remediation has two arcs: **converge what's drifted** and **make drift structurally impossible going forward** — the second matters more, because manual convergence is a treadmill.

```
SOURCE OF TRUTH (Git)                          FLEET
  fleet/                                        ┌─ cluster-prod-us ─┐
   ├─ base/        (policies, RBAC, add-ons)    ├─ cluster-prod-eu ─┤  Argo CD ApplicationSet
   ├─ overlays/    (per-cluster/per-env deltas) ├─ cluster-staging ─┤  reconciles base→every
   └─ clusters.yaml (the fleet inventory)       └─ cluster-dev ─────┘  cluster, self-heals drift
```

**Step 1 — measure the drift.** Before changing anything, inventory it: a policy engine in **audit mode** (Kyverno/Gatekeeper) across all clusters reports which clusters violate each policy; a config-scanning tool (or simply diffing `kubectl get` exports / Git-rendered manifests against live state) surfaces version skew, missing NetworkPolicies, RBAC divergence, and add-on version mismatches. You can't remediate what you haven't quantified, and audit-mode-first avoids breaking workloads that have quietly depended on the drift.

**Step 2 — establish a single source of truth and reconcile.** Move *all* fleet config into Git and drive it with **GitOps fleet tooling**: Argo CD **ApplicationSets** (or Flux with a fleet of `Kustomization`s) using a **cluster generator** so one declarative definition fans out to every registered cluster — base policy/RBAC/add-ons shared, per-cluster differences as explicit overlays. Now the desired state is *declared once* and continuously reconciled into every cluster, and **drift self-heals**: if someone hand-edits cluster B, the controller reverts it (per Q58). This converges the fleet *and* prevents re-drift in one mechanism.

**Step 3 — enforce policy as code at admission.** Convergence via GitOps fixes the *declared* config, but you also need to stop *non-conforming workloads* at the door uniformly. Roll the policy engine from audit to **enforce** (after the audit phase confirms what would break), with the policies themselves delivered via GitOps so *every* cluster gets the *same* policy set — closing the "one cluster forgot the network policy" gap structurally. In-tree **ValidatingAdmissionPolicy** (CEL) for the core rules avoids per-cluster webhook-availability coupling.

**Step 4 — version and lifecycle consistency.** Use **Cluster API** (or the cloud's fleet manager — GKE Fleet, EKS, Azure Fleet) to declaratively manage the *clusters themselves* (version, node pools), so upgrades roll across the fleet on a controlled cadence (canary one cluster, then the rest, per Q71) instead of each cluster drifting to whatever version someone last touched. Pin and reconcile add-on (CNI/CSI/CoreDNS/ingress) versions through the same GitOps pipeline.

The prevention principle: **drift is a tooling problem you solve up front, not a discipline problem you solve with vigilance** — humans *will* hand-edit clusters under pressure, so the only durable fix is a system where (a) the desired state lives in reviewed Git, (b) controllers continuously reconcile every cluster to it and revert manual changes, and (c) admission policy uniformly rejects non-conforming workloads everywhere. The remediation order matters — *measure (audit) → converge (GitOps reconcile) → enforce (policy) → standardize lifecycle (Cluster API)* — because enforcing before measuring breaks things, and converging without enforcement lets drift creep back. The anti-pattern to call out: "we'll write a runbook and be careful" — at fleet scale, leverage (declarative reconciliation + policy as code) beats vigilance every time, which is the same platform-as-a-product philosophy as Q30 applied across clusters rather than teams.

#### Q88. [Theory] Explain how the scheduler handles volume topology, zonal constraints, and `WaitForFirstConsumer` — and why naive dynamic provisioning strands Pods.

Storage and scheduling are coupled in a way that surprises people: in a multi-zone cluster, a block volume (EBS, GCE PD, Azure Disk) is **created in a specific zone and can only be attached to a node in that same zone** (`ReadWriteOnce`, zonal). So the *placement of the volume* and the *placement of the Pod that uses it* must agree — and if they're decided independently and in the wrong order, you get a Pod that can never run. This is the core problem `volumeBindingMode` solves.

```
StorageClass volumeBindingMode:
  Immediate           → PV is provisioned AS SOON AS the PVC is created (zone chosen NOW,
                        before the Pod is scheduled) → volume may land in a zone with no
                        room for the Pod → Pod Pending forever ("volume node affinity conflict")
  WaitForFirstConsumer → PVC binding is DEFERRED until a Pod using it is being scheduled;
                        the scheduler picks a node, THEN the volume is provisioned in
                        that node's zone → Pod and volume always co-located
```

```yaml
apiVersion: storage.k8s.io/v1
kind: StorageClass
metadata: { name: fast-ssd }
provisioner: ebs.csi.aws.com
volumeBindingMode: WaitForFirstConsumer    # the critical setting for zonal storage
allowedTopologies:                          # optionally constrain to specific zones
  - matchLabelExpressions:
      - { key: topology.kubernetes.io/zone, values: ["us-east-1a", "us-east-1b"] }
```

The **naive failure** with `Immediate` binding: the moment a PVC is created (e.g., a StatefulSet's `volumeClaimTemplate` generates a PVC), the CSI provisioner picks *some* zone and creates the disk there — *before* the scheduler has decided where the Pod goes. Now the Pod has a hard constraint (it must run in the volume's zone, encoded as the PV's `nodeAffinity`), but that zone might be full, cordoned, or tainted — so the Pod sits `Pending` with `volume node affinity conflict` / `had volume node affinity conflict`, and no amount of node autoscaling in *other* zones helps because the volume pins it to the wrong one. This is a top "Pending Pod" root cause (Q19).

**`WaitForFirstConsumer`** inverts the order: it tells the provisioner "don't create the volume yet." The scheduler runs its normal Filter/Score (Q35) considering the Pod's *other* constraints (resources, affinity, spread) and node availability across all zones, **binds the Pod to a node**, and *only then* is the volume provisioned **in that node's zone**. The scheduler's `VolumeBinding` plugin participates in this — it factors unbound PVCs into scheduling so the chosen node can actually satisfy the storage. This is why `WaitForFirstConsumer` is the **correct default for any zonal block storage** and why managed clusters set it on their default StorageClass.

The deeper interactions: with StatefulSets spread across zones (Q24), `WaitForFirstConsumer` ensures each replica's volume lands in the same zone as the replica — combined with topology spread, you get one replica + its volume per zone, surviving a zonal outage. The **Cluster Autoscaler** must be zone-aware too (node groups per zone, or it scales the wrong zone and the Pod still can't bind). And on **re-scheduling**, a StatefulSet Pod with a zonal volume can *only* come back in that volume's zone — if that entire zone is down, the Pod is stuck until the zone returns (the data lives there), which is a fundamental property of zonal block storage, not a bug; cross-zone resilience for stateful data requires *replication at the application/storage layer* (e.g., database replicas in multiple zones, or regional/multi-attach storage), not just Kubernetes scheduling. The synthesis: **`WaitForFirstConsumer` makes the scheduler the single decider of placement and lets storage follow** — the moment you let storage choose a zone independently (`Immediate`), you've created a constraint the scheduler can't satisfy, and that ordering bug is exactly why naive dynamic provisioning strands Pods.

#### Q89. [Practical] Right-sizing at scale: how do you systematically set resource requests/limits across hundreds of workloads to balance cost and reliability?

At a handful of services you eyeball requests; at hundreds, ad-hoc sizing produces a bimodal disaster — half the workloads massively over-request (idle nodes, huge bill) and half under-request (OOMs, throttling, evictions). Systematic right-sizing is a *data-driven, continuous* process, and the framing is that **requests and limits answer different questions** and should be set from different statistics.

```
              SET FROM                         OPTIMIZES FOR
requests   ~ typical/p50-p90 actual usage      scheduling accuracy + cost (bin-packing)
limits     ~ realistic peak (p99) + headroom   reliability (avoid OOM) / fairness (cap)
```

**The data foundation.** You can't right-size without observed usage over a representative window (covering daily/weekly peaks, batch cycles, traffic events). Query Prometheus for `container_memory_working_set_bytes` and `rate(container_cpu_usage_seconds_total[...])` per workload, and compute percentiles. The **VPA in recommendation mode** (`updateMode: Off`) does exactly this automatically — it observes usage and emits `target`/`lowerBound`/`upperBound` recommendations *without* mutating Pods, so you get per-workload sizing guidance you can review and apply via your manifests/GitOps. Tools like Goldilocks wrap VPA to surface these recommendations in a dashboard across all workloads.

**The rules I apply per resource:**
- **Memory request = limit** for latency-sensitive services (Guaranteed QoS, evicted last, no surprise OOM from bursting past request). Size to **p99 working set + 20–30% headroom** — memory is incompressible, so under-sizing the limit means OOMKilled, and the cost of an OOM (dropped requests, restart) usually outweighs the memory saved.
- **CPU request = typical usage (p50–p90)** so the scheduler bin-packs accurately, but **avoid CPU limits** on latency-sensitive workloads (Q8/Q46 — CFS throttling causes tail-latency spikes even on idle nodes). Set CPU *requests* for fair scheduling and let bursting use idle node capacity; reserve CPU limits for noisy-neighbor isolation or hard multi-tenant fairness, accepting the throttling trade-off.
- **Runtime awareness** (Q70): wire `GOMEMLIMIT`/`GOMAXPROCS` (Go) and `MaxRAMPercentage` (JVM) to the actual limits via Downward API, or right-sizing the cgroup is undone by a runtime that ignores it.

**The process and its automation.** This is *continuous*, not one-shot — workloads change, traffic grows, so usage drifts and last quarter's right-size is this quarter's waste or risk. The progression: **VPA-recommendations + dashboards** to surface drift → **policy/admission** (Kyverno) to *require* requests/limits on every workload (no BestEffort sneaking in, ties to ResourceQuota+LimitRange per Q15) and to flag absurd ratios → optionally **VPA in `Auto`/`Recreate` mode** for stateless workloads where you trust it to resize automatically (but **never run VPA and HPA on the *same* CPU metric** — they fight; use VPA on memory + HPA on CPU, or VPA recommendation-only). At the platform level, set **LimitRange defaults** per namespace so unsized workloads get sane starting values, and track a **cost-vs-utilization metric** (requested vs actually-used resources cluster-wide — the gap *is* your waste) as a KPI.

The senior synthesis: right-sizing is an **optimization between two failure costs** — over-provisioning wastes money (visible, tolerable), under-provisioning drops requests/OOMs (sharp, customer-facing) — so the asymmetry justifies *headroom on the reliability-critical dimension* (memory limits, request floors for critical services) while aggressively trimming the rest. And because it's a continuous, hundreds-of-workloads problem, the answer is **leverage** (VPA recommendations + admission policy + cost dashboards + LimitRange defaults driven through GitOps), not heroic per-service tuning — the same platform-as-a-product principle (Q30) applied to resource economics. The metric that proves it's working is **cluster utilization climbing toward a target band (say 60–70% of requests actually used) with OOM/throttle/eviction rates staying near zero** — both numbers moving the right way at once is the signal you've balanced cost against reliability.

#### Q90. [Theory] What is the API server's storage/serialization path (encoding, versioning, conversion, protobuf vs JSON), and how do API versioning and the `storage` version work?

When you submit an object, the API server doesn't just dump your YAML into etcd — it runs a precise **encode/decode and version-conversion pipeline**, and understanding it explains how Kubernetes evolves APIs without breaking clients and why a single object can be served as `v1`, `v1beta1`, etc. The key concept is that a resource can have **multiple API versions** but exactly **one storage version**, and the server **converts** between them via an internal "hub" representation.

```
client (v1beta1 YAML) ─► decode ─► INTERNAL version (hub, in-memory canonical form)
                                        │ defaulting, admission, validation happen here
                                        ▼
                                   encode to STORAGE version ─► serialize (protobuf) ─► etcd
                                        ▲
read (any served version) ◄─ convert from storage version ◄─ deserialize ◄─ etcd
```

**The version model.** Each API group/resource declares several **versions** (`v1alpha1`, `v1beta1`, `v1`), each marked `served` (clients can use it) and exactly one marked `storage: true` (the version persisted to etcd). When a client writes using *any* served version, the server **converts** it through the **internal version** (a hub representation, so you need N converters to/from the hub, not N² pairwise converters) and then encodes it in the **storage version** for etcd. When a client reads, the stored object is decoded and converted to the version the client requested. This conversion machinery is what lets Kubernetes promote APIs (`v1beta1 → v1`) and let old and new clients coexist — a client on `v1beta1` and one on `v1` both work against the same stored object, transparently converted.

**Serialization: protobuf vs JSON.** Externally the API speaks JSON (and YAML, which is converted to JSON) and supports content negotiation. Internally and for **etcd storage and intra-cluster traffic**, Kubernetes uses **protobuf** — far more compact and faster to encode/decode than JSON, which matters enormously at scale (every watch event, every list, every status update). This is why core resources define protobuf serialization; it's a major reason the control plane can handle the watch/list volume it does. (CRDs are stored as JSON, one reason aggregated API servers with protobuf can outperform CRDs for very high-throughput custom resources — Q50.)

**Why the storage version matters operationally.** Because objects are persisted in the *storage* version, when you **promote or remove an API version**, existing etcd data may still be in an older encoding. Kubernetes handles reads by converting on the fly, but if a version is *removed*, objects stored in it become unreadable — which is why there's a **storage migration** concern: before dropping an old storage version, you must rewrite existing objects into the new storage version (the `storage-version-migrator`, or simply re-applying objects, does this). This connects to the deprecation cadence (Q57): the 1.16/1.22 "API removal" waves weren't just about rejecting *new* manifests in old versions — they also required ensuring *stored* objects had been migrated to surviving versions. For **CRDs**, you declare the same model (`versions` with one `storage: true`) and provide **conversion webhooks** (or use `None` strategy if versions are structurally compatible) so your custom resources get the same multi-version, convertible behavior as built-ins.

The interview-grade synthesis: the API server is a **versioned, convertible object store with an internal canonical representation** — clients speak any served version, the server converts through the hub, persists one storage version in compact protobuf, and converts back on read. This is the machinery behind Kubernetes' API stability promise (multiple versions coexist, deprecation is gradual, conversion is transparent) and its scale (protobuf storage/transport), and it's why "just remove the old API version" is actually a careful storage-migration operation, not a flag flip — the durable state in etcd has to be migrated, not just the served surface.

#### Q91. [Practical] Cross-cutting incident: after a CNI upgrade, some Pods have no network connectivity while others are fine. How do you systematically isolate the cause?

A *partial* network failure after a CNI change is one of the hardest incident classes because "some Pods work" rules out the obvious "CNI is totally broken" and points at a *dimension* along which connectivity differs — and the discipline (echoing Q55) is to find that hidden dimension rather than stare at one broken Pod. The systematic approach is to **characterize the failure set** first, then walk the network stack.

```
STEP 1 — characterize WHICH Pods fail (find the dimension):
  failing Pods all on the same NODE?        → node-local CNI agent / kernel / config
  all in the same NAMESPACE / zone?          → NetworkPolicy / topology / IPAM-per-zone
  all NEW Pods (existing ones fine)?          → CNI binary/config broke; only new sandbox setup affected
  all CROSS-NODE traffic (same-node fine)?    → overlay tunnel / routing / MTU
  all to a specific service / external?       → DNS, kube-proxy rules, egress/SNAT
```

**Step 1 — characterize the failure set.** This is the highest-leverage move. `kubectl get pods -o wide` (shows node + Pod IP) cross-referenced with what's failing tells you the dimension: if all failing Pods are on **certain nodes**, the CNI agent/config on those nodes didn't upgrade cleanly (a DaemonSet rollout that partially failed — `kubectl get ds -n kube-system <cni>` for desired vs ready, check the CNI Pod logs on the bad nodes). If only **new** Pods fail while existing ones are fine, the CNI's *sandbox setup* path broke (the CNI binary/config that runs at Pod creation) but already-wired Pods kept their networking — look for `ContainerCreating` with `failed to set up sandbox ... NetworkPlugin cni failed` events. If **same-node works but cross-node fails**, it's the overlay/routing layer (tunnel down, route not programmed, or — classically after a CNI change — an **MTU mismatch** where the new encapsulation overhead wasn't accounted for, so small packets pass but large ones black-hole, manifesting as "connections establish but large responses hang").

**Step 2 — walk the stack at a representative failing Pod.** Use `kubectl debug` (an ephemeral container in the Pod's namespaces, Q18) to test from inside without disturbing the Pod: can it reach its own gateway? another Pod on the same node (`ping <samenode-pod-ip>`)? a Pod on another node (`ping <othernode-pod-ip>`)? CoreDNS (`nslookup kubernetes.default`)? an external IP? Each layer that fails localizes the break: same-node-fail = CNI bridge/veth/IPAM on the node; cross-node-fail = overlay/routing/MTU; DNS-only-fail = CoreDNS/kube-proxy (Q40); external-only-fail = egress NAT/SNAT/routing.

**Step 3 — check the CNI-specific machinery.** Verify the CNI DaemonSet is fully rolled out and healthy on *every* node (a partial DaemonSet rollout is a top cause of "some nodes broken"); check **IPAM** (did the upgrade change the IP pool/CIDR, exhaust addresses, or fail to release leaked IPs? — Pods stuck `ContainerCreating` with "failed to allocate IP" point here); confirm the CNI **config file** (`/etc/cni/net.d/`) on nodes matches the new version (a leftover old config or two conflicting configs causes nondeterministic behavior); and if the CNI replaced or coexists with **kube-proxy** (eBPF datapaths, Q39), confirm the kube-proxy/eBPF state is consistent (a half-migrated kube-proxy-less setup breaks Service routing while Pod-to-Pod IP works).

**Step 4 — mitigate and root-cause.** If it's a partial DaemonSet rollout, the safe move is often to **roll back the CNI** (`kubectl rollout undo` the DaemonSet) to restore the known-good version, then reproduce in a non-prod cluster to find the real cause — never debug a CNI upgrade live in prod longer than necessary. If it's MTU, set the correct Pod MTU for the new encapsulation. If it's IPAM/CIDR, fix the pool config.

The meta-lesson: **"some Pods work" is diagnostic gold, not noise** — it means the failure is *deterministic along a dimension* (node, namespace, age, cross-node, destination), and identifying that dimension *before* deep-diving collapses the search space enormously. A CNI upgrade touches the node agent (DaemonSet), the per-Pod sandbox-setup path, IPAM, routing/overlay, and possibly kube-proxy replacement — so the failure dimension tells you *which* of those five layers regressed. And the prevention (Q71/Q57): CNI is a critical, version-coupled component — upgrade it on a **canary node pool / non-prod cluster first**, verify cross-node + DNS + external connectivity explicitly, and have the rollback rehearsed, because the CNI is exactly the kind of foundational layer where a partial failure is far more confusing (and dangerous) than a total one.

#### Q92. [Theory] How does in-place Pod resource resize work (the `resize` subresource), and why was it historically impossible? What problems does it solve and create?

Historically, **changing a running Pod's CPU/memory requests or limits required deleting and recreating the Pod** — the resources were immutable on a running Pod. The reason was architectural: requests/limits were baked into the Pod spec at admission and translated into cgroup settings at container start, and there was no API path or kubelet machinery to *mutate* a running container's cgroup limits in place. So every right-sizing change (Q89), every VPA adjustment, meant a Pod restart — disruptive for stateful or slow-starting workloads, and a hard conflict with VPA wanting to tune memory continuously without churning Pods.

**In-place Pod resize** (the `resize` subresource and `resizePolicy`, beta-progressing in recent versions) makes requests/limits **mutable on a running Pod** for resources that support it, by having the kubelet update the container's cgroup values without recreating the container.

```yaml
spec:
  containers:
    - name: app
      resources:
        requests: { cpu: "500m", memory: "512Mi" }
        limits:   { cpu: "1",    memory: "1Gi" }
      resizePolicy:
        - { resourceName: cpu,    restartPolicy: NotRequired }  # CPU: change cgroup live
        - { resourceName: memory, restartPolicy: RestartContainer }  # mem may need restart
```

```bash
# Patch the running Pod's resources via the resize subresource:
kubectl patch pod <p> --subresource resize --patch \
  '{"spec":{"containers":[{"name":"app","resources":{"requests":{"cpu":"1"},"limits":{"cpu":"2"}}}]}}'
kubectl get pod <p> -o jsonpath='{.status.containerStatuses[0].resources}'   # observed/actual
```

**Why CPU and memory differ** (and the heart of the feature's complexity): **CPU is compressible** — you can raise or lower the CFS quota/shares of a running process live with no disruption (`restartPolicy: NotRequired`). **Memory is incompressible** — you can safely *grow* a memory limit live, but *shrinking* it below current usage is dangerous (the kernel can't reclaim in-use memory, so lowering `memory.max` below the working set triggers an OOM kill); hence memory resizes may specify `RestartContainer` to apply safely. The kubelet drives the resize: the scheduler must confirm the node still has room for an *increase* (a resize can be `Deferred` if the node lacks capacity, or `Infeasible` if it can never fit), and the Pod's `status` now distinguishes *desired* resources from *allocated/actual* resources.

**Problems it solves:** VPA can finally tune resources **without recreating Pods** (especially valuable for stateful workloads, large-heap JVMs with long warmup, or anything where a restart is costly) — closing the long-standing "VPA causes disruptive Pod churn" gap. It enables responsive vertical scaling for workloads whose needs change over their lifetime.

**Problems it creates:** (1) **QoS class is now potentially mutable-adjacent** — historically QoS was fixed at Pod creation; resize interacts with this carefully (a Pod's QoS class is determined at creation and the feature constrains resizes that would change it), and reasoning about eviction order gets subtler. (2) **Scheduling consistency** — the scheduler reserved based on original requests; a resize changes the node's effective allocation, and the node must reconcile actual vs requested capacity, raising the chance of overcommit if not handled carefully. (3) **Runtime awareness lag** (Q70) — an app that read its cgroup limits at *startup* (JVM `MaxRAMPercentage`, Go `GOMEMLIMIT`/`GOMAXPROCS`) won't notice a live resize unless it re-reads them, so the runtime may keep using the old sizing even though the cgroup changed — a subtle correctness gap. (4) **Tooling/observability** must now track *desired vs actual* resources (the `status.resources` vs `spec.resources` distinction) rather than assuming they're identical. The interview-grade point: in-place resize removes a long-standing architectural limitation (immutable running-Pod resources) by teaching the kubelet to mutate cgroups live, but it surfaces the deeper truth that **CPU and memory have fundamentally different mutability semantics at the kernel level** (compressible vs incompressible, Q46) — which is why the same operation is trivial for CPU and restart-prone for memory, and why the feature is genuinely subtle rather than "just let me change a number."

#### Q93. [Practical] A namespace is stuck in `Terminating` and won't delete. Diagnose the real cause and the correct (vs dangerous) fixes.

A namespace stuck in `Terminating` is a classic on-call puzzle, and the dangerous reflex — force-removing the namespace's finalizer to "make it go away" — usually treats the symptom while *leaking* whatever the finalizer was protecting. The correct approach is to find *why* it's stuck, because a namespace can't finish deleting until **all objects inside it are gone** and its **own finalizers** are cleared, and there are two distinct stuck-causes (Q38).

```bash
kubectl get namespace <ns> -o json | jq '.status'      # conditions tell you WHAT is blocking
# Two common conditions:
#   NamespaceDeletionContentFailure  → some RESOURCE inside can't be deleted (finalizer/API)
#   NamespaceDeletionDiscoveryFailure → an APIService/aggregated API is unreachable
kubectl api-resources --verbs=list --namespaced -o name \
  | xargs -n1 kubectl get --show-kind --ignore-not-found -n <ns>   # find leftover objects
```

**The diagnosis.** The namespace object's `status.conditions` is the authoritative source — Kubernetes records *exactly why* the namespace controller can't finish. The two dominant causes: (1) **A leftover object with a finalizer that nothing removes** — e.g., a Custom Resource whose operator was uninstalled (so its finalizer is never cleared), or a resource whose controller is wedged. The namespace controller deletes namespaced objects as part of teardown, but an object with a stuck finalizer enters `Terminating` and never leaves, so the namespace waits forever. (2) **A broken aggregated API / APIService** — the namespace controller must *enumerate every resource type* in the namespace to delete them, which means querying every API group including aggregated ones (Q44); if an aggregated API server (e.g., a metrics or custom-API extension) is down, the discovery call fails, the controller can't confirm the namespace is empty, and deletion hangs with `NamespaceDeletionDiscoveryFailure`. This second cause is non-obvious and famous — a *completely unrelated* broken APIService blocks *all* namespace deletions cluster-wide.

**The correct fixes, by cause:**
- **Stuck object finalizer:** identify the object (the `api-resources | xargs get` sweep above finds leftovers), then **fix the controller that owns the finalizer** — reinstall the operator so it completes cleanup and removes its finalizer, or if the controller is truly gone and you understand the consequences, remove *that object's* finalizer (`kubectl patch <resource> <name> -n <ns> -p '{"metadata":{"finalizers":[]}}' --type=merge`). Removing the *object's* finalizer (after understanding it) is far safer than nuking the *namespace's* finalizer, because it lets the namespace tear down naturally once the object clears.
- **Broken APIService:** `kubectl get apiservices | grep -v True` finds the unavailable aggregated API; **fix or delete the broken APIService** (`kubectl delete apiservice <name>` if the extension is defunct). Once discovery succeeds, the namespace deletion proceeds on its own — no force needed.

**The dangerous fix and why to avoid it.** The widely-copied "fix" is to clear the **namespace's** `spec.finalizers` (often `kubernetes`) via the raw `finalize` API:
```bash
# DANGEROUS — skips proper teardown; orphans/leaks resources. Last resort only.
kubectl get namespace <ns> -o json | jq 'del(.spec.finalizers)' \
  | kubectl replace --raw "/api/v1/namespaces/<ns>/finalize" -f -
```
This forces the namespace gone *regardless of its contents* — but the objects inside (and their finalizers' cleanup) are **skipped**, so you can **leak external resources** (cloud load balancers, volumes, DNS records, external registrations the finalizers would have released) and orphan objects that may linger in etcd. You've made the symptom (stuck namespace) disappear while creating an invisible mess. It's justified only when the controller is permanently gone, you've confirmed what's being leaked, and you accept manual cleanup of the external resources.

The synthesis interviewers want: **"stuck Terminating" is almost always a finalizer contract that can't be satisfied (missing controller) or a discovery failure (broken aggregated API)** — and the *correct* fix addresses the specific cause (reinstate the controller / fix the APIService) so teardown completes properly, while force-clearing the namespace finalizer is a blunt last resort that trades a visible stuck-namespace for invisible resource leaks. Recognizing the **APIService-discovery cause** in particular (an unrelated broken extension blocking *all* namespace deletions) is the detail that signals real operational experience, because it's the one people waste hours on by staring at the namespace's contents when the actual culprit is a dead metrics adapter elsewhere.

#### Q94. [Theory] Compare Kubernetes Jobs, CronJobs, and external workflow/batch systems (Argo Workflows, Airflow) — when does the built-in batch API run out of road?

The built-in batch primitives — **Job** (run Pods to completion, with retries and parallelism) and **CronJob** (scheduled Jobs) — cover a surprising amount of ground, and the senior skill is knowing precisely *where* they end and a dedicated workflow engine begins, rather than reflexively reaching for Airflow on day one.

```
Job          : run N pods to completion; parallelism, completions, backoffLimit,
               activeDeadlineSeconds, indexed completion (per-index work sharding)
CronJob      : Job on a schedule; concurrencyPolicy, startingDeadline, history limits
─── built-in API ends roughly here ───
Argo Workflows : DAG / step graphs, fan-out/fan-in, artifacts between steps, conditionals,
                 retries per step, parametrization — all as Kubernetes-native CRDs
Airflow / etc. : rich scheduling, backfills, sensors, huge operator ecosystem, data-pipeline
                 lineage, cross-system orchestration — often NOT Kubernetes-native at core
```

**What Jobs/CronJobs do well.** A `Job` runs Pods until a target number of **completions** succeed, with **parallelism** (run several at once), **`backoffLimit`** (retry failures), **`activeDeadlineSeconds`** (hard timeout), and **indexed completion mode** (each Pod gets a `JOB_COMPLETION_INDEX` so you can shard work — e.g., process partition N — across parallel Pods). `CronJob` adds scheduling with `concurrencyPolicy`, `startingDeadlineSeconds`, and history limits (Q16). For **independent, parallel, retryable tasks** — a nightly backup, a batch of image conversions, a sharded data-processing run, a periodic cleanup — these are perfect, native, and need no extra infrastructure.

**Where they run out of road.** The batch API models a *single* task (possibly parallel/sharded) but has **no concept of a multi-step workflow** — it can't express "run A, then B and C in parallel, then D only if B succeeded, passing B's output to D." The moment you need any of:
- **dependencies / DAGs** between steps (step B depends on step A's completion),
- **fan-out/fan-in** (dynamically spawn N parallel sub-tasks, then aggregate),
- **passing artifacts/outputs** between steps (A produces a file B consumes),
- **conditional branching**, per-step retry/timeout policies, or **manual approval gates**,
- **complex scheduling** (backfills, catch-up runs, sensors that wait for external events, dependencies *across* schedules),

…you've outgrown the built-in API, and stitching it together with shell scripts that `kubectl create job` and poll for completion is the anti-pattern that signals you need a real engine.

**The two families of "more."** **Argo Workflows** (and similar Kubernetes-native engines like Tekton for CI) keep everything **Kubernetes-native**: workflows are CRDs, each step is a Pod, the DAG/steps/artifacts/conditionals are expressed declaratively and reconciled by a controller — so you get DAGs, fan-out/fan-in, artifact passing, and per-step control *while staying in the Kubernetes model* (RBAC, GitOps, the same observability). This is the right step-up when your orchestration logic is **about running containers on Kubernetes**. **Airflow / Dagster / Prefect** (and managed services like Cloud Composer/Dataflow) are **data-pipeline orchestrators** with rich scheduling (backfills, sensors), lineage, a vast **operator ecosystem** for external systems (databases, warehouses, cloud services), and a Python-authored DAG model — they may *run on* Kubernetes (the KubernetesExecutor/KubernetesPodOperator), but their core value is **cross-system data orchestration**, not container scheduling. Reach for them when the workflow spans many external systems and needs data-engineering features (backfills, lineage, a mature scheduler) that a container-DAG engine doesn't provide.

The decision framework: **independent/parallel/scheduled tasks → built-in Job/CronJob** (don't add infrastructure you don't need); **container-centric DAGs and pipelines on Kubernetes → Argo Workflows/Tekton** (native, GitOps-friendly); **cross-system data pipelines with rich scheduling and lineage → Airflow/Dagster/Prefect** (purpose-built data orchestration). The over-engineering signal interviewers listen for is reaching for Airflow to run a nightly cleanup (a CronJob suffices) — and the *under*-engineering signal is hand-rolling DAG logic with sleep-and-poll Bash around `kubectl create job` because you didn't recognize you'd outgrown the batch API. The mature answer names *which capability* (DAG dependencies, artifact passing, backfills, cross-system operators) forces the jump, rather than picking a tool by popularity.

#### Q95. [Theory] How do you reason about and reduce control-plane and data-plane *blast radius* — and what's the difference? Give concrete mechanisms for each.

"Blast radius" — how much breaks when one thing fails — is the lens senior engineers use to evaluate every architectural choice, and the essential distinction is **control-plane blast radius** (what happens when Kubernetes' *management* layer fails) versus **data-plane blast radius** (what happens when the *running workloads' traffic/compute* path fails). They have different failure modes, different mitigations, and crucially **different urgency**, because the control plane and data plane are *decoupled* by Kubernetes' design (Q56).

```
                 CONTROL-PLANE failure                 DATA-PLANE failure
What breaks       can't schedule/change/heal;           live traffic drops; running
                  apiserver/etcd/controllers down        Pods unreachable or gone
Running workloads KEEP RUNNING (decoupled!)             AFFECTED immediately
Urgency           degraded management, not outage        customer-facing outage NOW
                  (buys time) — unless data-plane too
Examples          etcd quorum loss, apiserver overload,  CNI failure, ingress/LB down,
                  bad admission webhook, cert expiry      node loss, DNS outage, Service misroute
```

**The decoupling insight first.** Because controllers are level-triggered and the data path doesn't route through the API server, a **control-plane outage does not, by itself, take down running workloads** — kill etcd or the API server and existing Pods keep serving traffic, kube-proxy keeps routing, the CNI keeps forwarding (Q17). What you *lose* is the ability to *change* things: no new deploys, no scaling, no self-healing (a Pod that crashes during the outage won't be recreated), no Service endpoint updates (so if a Pod *does* die, traffic still routes to it). This is why a control-plane outage is **"degraded management" rather than "down"** — it buys you time, *unless* a data-plane failure happens concurrently (a node dies during the control-plane outage and its Pods can't be rescheduled — the dangerous compound failure).

**Reducing control-plane blast radius** — mechanisms: (1) **HA control plane** (≥3 API servers behind a load balancer, etcd quorum of 3/5 across failure domains, Q26) so single-component failure is survivable. (2) **API Priority and Fairness** (Q44) to isolate noisy clients so one bad controller can't DoS the API server for everyone. (3) **Scoped, HA, `failurePolicy: Ignore` (where safe) admission webhooks** that exclude `kube-system` (Q43/Q84) so a webhook outage can't brick cluster operations. (4) **etcd protection** — dedicated low-latency disks, separate events etcd, compaction/defrag automation, tested backups (Q26/Q83). (5) **Cert-expiry monitoring** (Q77). (6) **managed control planes** (EKS/GKE/AKS) that make the cloud provider responsible for control-plane HA. (7) **Multiple clusters** (Q65) so an *entire* control plane failing affects only one cluster's worth of management.

**Reducing data-plane blast radius** — mechanisms: (1) **Spread workloads** across nodes and zones (topologySpreadConstraints / anti-affinity, Q24) and **PodDisruptionBudgets** (Q25) so node/zone loss can't take all replicas. (2) **Redundant, HA ingress/load-balancing** (multiple ingress controller replicas, multi-AZ cloud LBs) so the entry point isn't a single point of failure. (3) **NodeLocal DNSCache** and CoreDNS HA (Q40) so DNS — a data-plane dependency *everything* uses — doesn't become a single failure that looks like a total outage. (4) **Resilient CNI** (canary CNI upgrades per Q91, since the CNI *is* the data plane). (5) **Graceful termination + readiness gating** (Q42) so routine Pod churn doesn't drop traffic. (6) **Circuit breaking / retries / outlier detection** (mesh or library) so one bad backend doesn't cascade. (7) **Multi-cluster / multi-region** with global load balancing so an *entire cluster's* data plane failing fails over to another.

**The synthesis and the trade-offs.** The first move in *any* design is to ask **"if this fails, is it control-plane or data-plane, and what's the radius?"** — because the answer sets both the mitigation and the urgency. The deepest blast-radius decisions are *cluster topology* ones (Q65): one big cluster concentrates *both* control-plane and data-plane blast radius into a single failure domain (efficient but risky), while many clusters partition both (resilient but operationally heavier and fragmentation-prone). The mature framing: **blast-radius reduction is fundamentally about partitioning failure domains and removing single points of failure**, and you spend complexity/cost to shrink the radius *in proportion to the impact* — a payments cluster justifies multi-region active-active (tiny radius, huge cost), an internal tool tolerates a single cluster (large radius, low cost). The non-obvious advanced point is that **the control plane's decoupling from the data plane is itself the single most important blast-radius property of Kubernetes** — it's *why* a control-plane incident is usually survivable, and *why* the truly catastrophic incidents are the ones that breach *both* planes at once (a network partition that takes the control plane AND splits the data plane, or a CNI/DNS failure that's simultaneously a data-plane outage and prevents the control plane from healing it). Designing so that control-plane and data-plane failures stay *independent* — never letting a single dependency (DNS, the CNI, a webhook) become common to both — is the staff-level instinct that prevents a single fault from becoming a total outage.

## 🧩 Extended Questions — Supplemental Set B: Coding & Expert

### 🟢 Basic — extended

#### Q96. [Coding] Write a multi-stage Dockerfile that produces a tiny, non-root, distroless image for a Go service.

**Problem**: ship a Go web service as a minimal, secure container image — small attack surface, no shell, runs as non-root, reproducible. The same pattern is what a Kubernetes `securityContext` with `runAsNonRoot: true` and `readOnlyRootFilesystem: true` expects from the image.

```dockerfile
# ---- build stage ----
FROM golang:1.23 AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download                       # cached layer; only re-runs if deps change
COPY . .
# static binary: no libc dependency, so it runs on distroless/scratch
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/app ./cmd/server

# ---- runtime stage ----
FROM gcr.io/distroless/static:nonroot     # no shell, no package manager, UID 65532
WORKDIR /
COPY --from=build /out/app /app
USER 65532:65532                          # matches runAsNonRoot in the PodSpec
EXPOSE 8080
ENTRYPOINT ["/app"]
```

The multi-stage build keeps the ~900 MB toolchain in the build stage and copies only the binary into the runtime image — final size is typically 10–20 MB. `CGO_ENABLED=0` produces a statically linked binary so it runs on `distroless/static` (or even `scratch`), which has no shell and no libraries an attacker could exploit. `USER 65532` makes the container run as the non-root `nonroot` user baked into the distroless image, which lets the corresponding Pod satisfy a `restricted` Pod Security Admission level without extra config.

The trade-off of distroless is debuggability: there's no `sh`, `curl`, or `ps` inside, so you can't `kubectl exec` a shell. The Kubernetes-native answer is **ephemeral debug containers** (`kubectl debug -it <pod> --image=busybox --target=app`) which attach a tooling container sharing the target's namespaces without rebuilding the image. For the readiness/liveness probe, prefer `httpGet` over `exec` since there's no shell to run a command probe. **Edge case**: `-ldflags="-s -w"` strips symbol/debug info to shrink the binary, but that also removes stack-trace symbol names — keep an unstripped artifact in your registry/symbol store for crash triage.

#### Q97. [Coding] Use `kubectl` with JSONPath and `-o custom-columns` to extract Pod data programmatically.

**Problem**: in a script (CI gate, ops check) you need machine-readable answers — e.g., "list every Pod not in Running state with its node and restart count" — without parsing the human-formatted `kubectl get` table.

```bash
# Custom columns: name, node, phase, restarts of container[0]
kubectl get pods -A -o custom-columns=\
'NS:.metadata.namespace,NAME:.metadata.name,NODE:.spec.nodeName,'\
'PHASE:.status.phase,RESTARTS:.status.containerStatuses[0].restartCount'

# JSONPath with a filter: names of Pods whose phase != Running
kubectl get pods -A -o jsonpath=\
'{range .items[?(@.status.phase!="Running")]}{.metadata.namespace}/{.metadata.name}{"\n"}{end}'

# Find images in use cluster-wide (dedupe with sort -u)
kubectl get pods -A -o jsonpath='{.items[*].spec.containers[*].image}' | tr ' ' '\n' | sort -u

# go-template for conditional logic JSONPath can't express
kubectl get pods -o go-template='{{range .items}}{{if gt (index .status.containerStatuses 0).restartCount 5.0}}{{.metadata.name}}{{"\n"}}{{end}}{{end}}'
```

Use `custom-columns` when you want a table with chosen fields, `jsonpath` for flat extraction and simple filters (`?(@.field==...)`), and `go-template` when you need conditionals/arithmetic that JSONPath lacks. For anything beyond that, pipe `-o json` into `jq`, which has a far richer query language: `kubectl get pods -o json | jq -r '.items[] | select(.status.phase!="Running") | .metadata.name'`.

The reason this matters: `kubectl get` without `-o` returns a human table whose columns change between versions, so scripts that `grep`/`awk` it are brittle. JSONPath/jq read the stable API object schema directly. **Gotchas**: JSONPath array filters need the exact path (`status.containerStatuses[0]` fails on Pods with no started containers — guard with `?()` or jq's `?` operator); and quoting differs between shells — single-quote the JSONPath in bash to stop the shell from eating `$` and `{}`. For CI gates, prefer `kubectl wait` (Q104) where it fits, and reserve JSONPath for assertions `wait` can't express.

#### Q98. [Coding] Author a minimal Helm chart with values, a named template helper, and conditional resources.

**Problem**: package an app so the same chart deploys to dev (1 replica, no Ingress) and prod (5 replicas, Ingress on) by changing values only.

```
mychart/
├── Chart.yaml
├── values.yaml
└── templates/
    ├── _helpers.tpl
    ├── deployment.yaml
    └── ingress.yaml
```

```yaml
# values.yaml
replicaCount: 1
image: { repo: myapp, tag: "1.0.0" }
ingress: { enabled: false, host: app.example.com }
```

```yaml
# templates/_helpers.tpl  (reusable named templates)
{{- define "mychart.fullname" -}}
{{- printf "%s-%s" .Release.Name .Chart.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- define "mychart.labels" -}}
app.kubernetes.io/name: {{ .Chart.Name }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}
```

```yaml
# templates/deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ include "mychart.fullname" . }}
  labels: {{- include "mychart.labels" . | nindent 4 }}
spec:
  replicas: {{ .Values.replicaCount }}
  selector: { matchLabels: { app.kubernetes.io/instance: {{ .Release.Name }} } }
  template:
    metadata:
      labels: {{- include "mychart.labels" . | nindent 8 }}
    spec:
      containers:
        - name: app
          image: "{{ .Values.image.repo }}:{{ .Values.image.tag }}"
---
# templates/ingress.yaml — entire object is gated on a value
{{- if .Values.ingress.enabled }}
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata: { name: {{ include "mychart.fullname" . }} }
spec:
  rules:
    - host: {{ .Values.ingress.host | quote }}
      http: { paths: [{ path: /, pathType: Prefix, backend: { service: { name: {{ include "mychart.fullname" . }}, port: { number: 80 } } } }] }
{{- end }}
```

```bash
helm template myrel ./mychart                              # render locally to verify
helm install myrel ./mychart -f values-prod.yaml --dry-run --debug
helm upgrade --install myrel ./mychart --set replicaCount=5
```

The key Helm idioms: `{{ include "name" . }}` calls a named template (preferred over `template` because it's pipeable, e.g. into `nindent`); `nindent N` controls YAML indentation so the injected block lands correctly — the single most common source of "rendered YAML is invalid" errors. Wrapping a whole object in `{{- if }} ... {{- end }}` makes resources conditional, which is how one chart serves multiple environments. The `-` in `{{-` and `-}}` trims surrounding whitespace/newlines so the output stays clean.

**Trade-offs vs Kustomize**: Helm's templating is powerful (loops, conditionals, functions) and gives you release lifecycle/rollback (`helm rollback`), but text-templating YAML is error-prone and hides the final object until rendered. Kustomize is template-free (patches over plain YAML) and safer for simple env overrides but can't express conditionals or packaging. Validate charts with `helm lint` and `helm template | kubeconform` in CI; pin `Chart.yaml` `apiVersion: v2` and dependency versions for reproducibility.

### 🟡 Intermediate — extended

#### Q99. [Coding] Run a parallel batch Job with a fixed completion count and a work queue, and explain the parallelism knobs.

**Problem**: process 1000 work items as fast as the cluster allows, with at most 10 running at once, retrying failures, and stopping once all items are done.

```yaml
apiVersion: batch/v1
kind: Job
metadata: { name: image-resize }
spec:
  completions: 1000          # total successful Pods required (fixed-count mode)
  parallelism: 10            # at most 10 Pods running concurrently
  backoffLimit: 6            # total Pod failures tolerated before Job is "Failed"
  completionMode: Indexed    # each Pod gets JOB_COMPLETION_INDEX (0..999)
  activeDeadlineSeconds: 3600
  template:
    spec:
      restartPolicy: Never
      containers:
        - name: worker
          image: resizer:2.0
          command: ["/bin/sh","-c"]
          args:
            - 'process-item --index "$JOB_COMPLETION_INDEX"'   # static work partitioning
```

There are three Job execution modes. **Non-parallel** (omit both fields): one Pod, runs to one success. **Fixed completion count** (set `completions` + `parallelism`): runs until `completions` Pods succeed; with `completionMode: Indexed` each Pod sees a unique `JOB_COMPLETION_INDEX`, which lets workers statically partition work (Pod 7 handles item 7) with no external queue. **Work-queue** (set `parallelism`, omit `completions`): Pods pull from an external queue (Redis/SQS) and the Job completes when any Pod exits 0 signaling the queue is drained — used when item count is unknown.

`parallelism` caps concurrency (the scheduler/cluster capacity is the real upper bound); `completions` is the success target; `backoffLimit` counts *Pod* failures across the whole Job (with exponential backoff capped at 6 min), and exceeding it marks the Job `Failed`. `restartPolicy: Never` is important: it makes failed Pods countable against `backoffLimit` and visible for debugging, whereas `OnFailure` restarts the container in place and hides failures.

**Edge cases**: per-index retry uses `backoffLimitPerIndex` + `maxFailedIndexes` (stable in 1.33+) so one poison-pill item doesn't fail the whole Job. For "succeed early on a fatal error" use a **Pod failure policy** to fail-fast on specific exit codes instead of burning all retries. Indexed Jobs are the basis of K8s-native distributed compute (the JobSet API and ML training frameworks build on them). **Complexity**: throughput ≈ `parallelism × (1 / per-item time)` bounded by cluster CPU and any shared downstream (DB, queue) — over-setting `parallelism` just produces Pending Pods or thundering-herd load on the backend.

#### Q100. [Coding] Build a Kustomize base + overlays so dev and prod share config but differ where needed.

**Problem**: one base manifest set; dev gets 1 replica and a debug log level; prod gets 5 replicas, a different image tag, and a resource patch — with no copy-paste and no templating.

```
app/
├── base/
│   ├── kustomization.yaml
│   ├── deployment.yaml
│   └── service.yaml
└── overlays/
    ├── dev/kustomization.yaml
    └── prod/{kustomization.yaml,replicas-patch.yaml}
```

```yaml
# base/kustomization.yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
resources: [deployment.yaml, service.yaml]
commonLabels: { app: web }
images:
  - name: myapp
    newTag: "1.0.0"        # default tag, overridable per overlay
```

```yaml
# overlays/prod/kustomization.yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
namespace: web-prod
resources: ["../../base"]
images:
  - name: myapp
    newTag: "1.4.2"        # prod-specific image
replicas:
  - name: web
    count: 5
patches:
  - path: replicas-patch.yaml          # strategic-merge patch for fields kustomize lacks
configMapGenerator:
  - name: app-config
    literals: ["LOG_LEVEL=warn"]        # generates a hashed-name ConfigMap
```

```yaml
# overlays/prod/replicas-patch.yaml — strategic merge: only the listed fields change
apiVersion: apps/v1
kind: Deployment
metadata: { name: web }
spec:
  template:
    spec:
      containers:
        - name: app
          resources:
            requests: { cpu: 500m, memory: 512Mi }
            limits:   { memory: 512Mi }
```

```bash
kubectl kustomize overlays/prod          # render to stdout to inspect
kubectl apply -k overlays/prod           # build + apply in one step
```

Kustomize is **template-free**: overlays reference the base and apply *patches* (strategic-merge or JSON 6902) plus built-in transformers (`namespace`, `commonLabels`, `images`, `replicas`). Because the base is valid YAML you can `kubectl apply -k base` directly — there's no render step required to see real objects, which makes review and `kubectl diff` straightforward. The `configMapGenerator`/`secretGenerator` append a content hash to the resource name (`app-config-7d2f...`), and Kustomize rewrites every reference; changing a value yields a new name, which **forces a rolling update** of consuming Pods — solving the classic "ConfigMap changed but Pods didn't restart" problem (Q102) for free.

**When to choose which**: Kustomize for straightforward env overrides where you want auditable plain YAML and no logic; Helm when you need packaging/distribution, conditionals, loops, or release lifecycle (`helm rollback`). They compose — many teams template a chart with Helm and post-process with Kustomize (`helm template | kubectl apply -k -`), and `kubectl` has Kustomize built in (`-k`). **Gotcha**: strategic-merge patches need the right merge-key (containers merge by `name`); get the name wrong and you *append* a second container instead of patching the first.

#### Q101. [Coding] Ship application logs with a sidecar when the app only writes to a file, not stdout.

**Problem**: a legacy app writes logs only to `/var/log/app/app.log` and can't be changed. Node-level log collection only captures stdout/stderr. Make the logs collectable without modifying the app.

```yaml
apiVersion: apps/v1
kind: Deployment
metadata: { name: legacy-app }
spec:
  selector: { matchLabels: { app: legacy } }
  template:
    metadata: { labels: { app: legacy } }
    spec:
      volumes:
        - name: logs
          emptyDir: {}                         # shared scratch volume between containers
      containers:
        - name: app                            # the legacy app, writes to a file
          image: legacy-app:3.2
          volumeMounts:
            - { name: logs, mountPath: /var/log/app }
        # native sidecar (init container with restartPolicy: Always) — starts first,
        # stays running, and is guaranteed to terminate AFTER the main app (1.29+)
      initContainers:
        - name: log-tailer
          image: busybox:1.36
          restartPolicy: Always                # <-- makes this a sidecar, not a one-shot init
          command: ["/bin/sh","-c","tail -n+1 -F /var/log/app/app.log"]
          volumeMounts:
            - { name: logs, mountPath: /var/log/app }
```

The pattern: an `emptyDir` volume is mounted into both containers, so the app writes the file and the sidecar reads it. The sidecar `tail -F`s the file to its own **stdout**, which the node's log agent (Fluent Bit / Vector as a DaemonSet) already collects from every container's stdout via the container runtime. No app change, no shared NFS, no app-embedded shipping library.

Declaring the tailer as a **native sidecar** (an init container with `restartPolicy: Always`, GA in 1.29) is the modern fix for two long-standing bugs: (1) ordering — native sidecars start before the main containers and are terminated *after* them, so you don't lose the final log lines on shutdown; (2) Job completion — with a plain second container in a `Job`, a never-exiting log sidecar would keep the Pod "running" forever, but a native sidecar is excluded from the completion calculation, so the Job finishes when the main container exits.

**Trade-offs**: the cleanest answer is still "make the app log to stdout" (12-factor) and skip the sidecar entirely; the sidecar exists for code you can't change. Costs: the `emptyDir` consumes node ephemeral storage (size it and consider `sizeLimit`, since a runaway log file can trigger node-pressure eviction, Q64), and you double the container count. An alternative is a `DaemonSet` agent reading a hostPath log dir, but the sidecar keeps log routing per-Pod and avoids host coupling.

#### Q102. [Coding] Trigger a rolling restart automatically when a ConfigMap or Secret changes.

**Problem**: you mount config via a ConfigMap. You update the ConfigMap, but the running Pods keep the old config (or update slowly and inconsistently). Make a config change reliably and immediately roll the Deployment.

```yaml
# Approach A: checksum annotation (Helm) — changes the pod template, forcing a rollout
apiVersion: apps/v1
kind: Deployment
metadata: { name: web }
spec:
  template:
    metadata:
      annotations:
        # any change to the ConfigMap changes this hash -> new pod template -> rollout
        checksum/config: {{ include (print $.Template.BasePath "/configmap.yaml") . | sha256sum }}
    spec:
      containers:
        - name: app
          envFrom: [{ configMapRef: { name: app-config } }]
```

```bash
# Approach B: imperative, no template machinery — for plain YAML / GitOps-light setups
kubectl rollout restart deployment/web        # bumps a restartedAt annotation; safe rolling restart
```

Understanding *why* this is needed: a ConfigMap **mounted as a volume** is eventually updated in-place inside the Pod (kubelet syncs it, with a delay up to the sync period plus cache TTL — often ~1 min), but most apps read config only at startup, so the file changes and the process ignores it. A ConfigMap consumed as **`envFrom`/env vars is never updated** in a running container — env is set once at container start. Either way, you must restart the Pods to pick up new config.

The robust patterns: **(A)** put a hash of the config into the Pod template annotation (Helm `sha256sum`, or Kustomize's hashed ConfigMap names from Q100). Changing the config changes the Pod template, and the Deployment controller does a normal, safe `RollingUpdate` honoring `maxUnavailable`/PDBs. **(B)** `kubectl rollout restart` for ad-hoc restarts. **(C)** an operator like **Reloader** that watches referenced ConfigMaps/Secrets and patches the Deployment automatically — best for fleets where you don't control every chart.

**Edge cases**: never `kubectl delete pod` to "reload config" — that bypasses surge/PDB protection and can drop capacity. For apps that *can* hot-reload (Nginx, Envoy), a mounted volume plus a `SIGHUP` via a sidecar avoids restarts entirely, but most stateless apps are simplest to just roll. Immutable Secrets/ConfigMaps (`immutable: true`) improve API-server performance and prevent accidental edits, but then a "change" means creating a new object and updating the reference — which again rolls the Deployment.

#### Q103. [Coding] Write a default-deny egress NetworkPolicy that still permits DNS, and explain the DNS pitfall.

**Problem**: lock down a namespace so Pods cannot make arbitrary outbound connections (data-exfiltration / lateral-movement control), but they must still resolve DNS and reach one specific internal API.

```yaml
# 1) Default-deny ALL egress for every Pod in the namespace
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: { name: default-deny-egress, namespace: payments }
spec:
  podSelector: {}
  policyTypes: ["Egress"]
  # no egress rules => everything denied
---
# 2) Allow DNS to CoreDNS (UDP+TCP 53) — REQUIRED, or every lookup fails
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: { name: allow-dns, namespace: payments }
spec:
  podSelector: {}
  policyTypes: ["Egress"]
  egress:
    - to:
        - namespaceSelector: { matchLabels: { kubernetes.io/metadata.name: kube-system } }
          podSelector: { matchLabels: { k8s-app: kube-dns } }
      ports:
        - { protocol: UDP, port: 53 }
        - { protocol: TCP, port: 53 }
---
# 3) Allow egress to one internal service on 8443
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: { name: allow-internal-api, namespace: payments }
spec:
  podSelector: {}
  policyTypes: ["Egress"]
  egress:
    - to:
        - namespaceSelector: { matchLabels: { kubernetes.io/metadata.name: core } }
          podSelector: { matchLabels: { app: internal-api } }
      ports: [{ protocol: TCP, port: 8443 }]
```

NetworkPolicies are **additive allow-lists** that become deny-by-default *for a Pod the moment any policy with the relevant `policyType` selects it*. So policy (1) flips the namespace to default-deny egress; policies (2) and (3) punch the only two holes. The classic failure is forgetting policy (2): the instant you apply a default-deny egress, **DNS resolution breaks** because the lookup to CoreDNS (in `kube-system`) is itself egress traffic — and the symptom is misleading (apps report "connection refused"/timeouts to *names*, not a network-policy error, so engineers chase the wrong layer). The `kubernetes.io/metadata.name` label is auto-applied to every namespace by the API server, so you can target `kube-system` reliably without manually labeling it.

**Caveats and depth**: enforcement requires a policy-capable CNI (Calico, Cilium; flannel ignores policy entirely — a dangerous false sense of security). Standard NetworkPolicy matches by Pod/namespace selector and CIDR, but **cannot match DNS names** — allowing egress to "api.stripe.com" needs Cilium's `CiliumNetworkPolicy` with `toFQDNs`, because the external IP set behind a hostname is dynamic. For egress to the internet via specific CIDRs use `ipBlock` with `except`. To restrict CoreDNS itself or apply L7 rules (allow only `GET /v1/...`), you again need a CNI with L7 awareness (Cilium) or a service mesh. Always test policies with a deny-then-allow rollout in staging, since a wrong selector silently blackholes traffic.

#### Q104. [Coding] Use `kubectl wait`, `rollout status`, and probes to make a CI deployment gate that fails fast.

**Problem**: a CI/CD pipeline runs `kubectl apply` and must block until the new version is actually healthy, and fail the pipeline (with logs) if the rollout doesn't converge — instead of reporting green the instant `apply` returns.

```bash
#!/usr/bin/env bash
set -euo pipefail
NS=web; DEP=web; TIMEOUT=180s

kubectl apply -n "$NS" -f k8s/                      # apply returns immediately; not "done"

# 1) Block until the Deployment's new ReplicaSet is fully rolled out (honors readiness)
if ! kubectl rollout status -n "$NS" deployment/"$DEP" --timeout="$TIMEOUT"; then
  echo "::error:: rollout did not converge"
  kubectl describe -n "$NS" deployment/"$DEP"
  # dump logs of the newest (likely failing) pods to the CI log
  kubectl logs -n "$NS" -l app="$DEP" --tail=100 --prefix --all-containers \
    --max-log-requests=20 || true
  kubectl rollout undo -n "$NS" deployment/"$DEP"   # auto-rollback on failure
  exit 1
fi

# 2) Extra gate: wait for a specific condition (e.g., a Job migration completed)
kubectl wait -n "$NS" --for=condition=complete --timeout=120s job/db-migrate

# 3) Smoke test against the in-cluster service before declaring success
kubectl run smoke --rm -i --restart=Never --image=curlimages/curl -- \
  curl -fsS "http://$DEP.$NS.svc.cluster.local/healthz"
echo "Deployment healthy."
```

The core insight is that `kubectl apply` is **fire-and-forget** — it writes the desired state and returns; it does not wait for convergence. CI that treats `apply` exit code as "deployed" will pass even when every new Pod is CrashLooping. `kubectl rollout status` blocks until the Deployment reports the new ReplicaSet has the desired number of *ready* (not just created) replicas, which is why correct readiness probes (Q9) are load-bearing here: the gate is only as good as the probe. The `--timeout` is what makes it *fail fast* — without it, a hung rollout (broken probe never goes ready) blocks until `progressDeadlineSeconds` and then the controller marks the Deployment `Progressing=False`, which `rollout status` surfaces as a non-zero exit.

`kubectl wait --for=condition=...` generalizes this to any object/condition (`Job` complete, `Pod` Ready, a custom resource's status condition, or `--for=delete` to confirm teardown). Capturing `describe` + `logs` on failure is what turns a red pipeline into an actionable one. **Edge cases**: `rollout status` only watches the *latest* revision, so pair it with `kubectl get rs` if you suspect orphaned ReplicaSets; `kubectl wait` errors immediately if the object doesn't exist yet (race after `apply`) — add a short retry or `--for=create`; and `--all-containers`/`--max-log-requests` matter when Pods have sidecars or there are many replicas. The auto-`rollout undo` gives you a self-healing pipeline, but for production prefer a progressive-delivery controller (Argo Rollouts/Flagger) that does metric-based analysis and rollback rather than a binary readiness gate.

### 🟠 Advanced — extended

#### Q105. [Coding] Define a CRD with OpenAPI validation, a status subresource, and additional printer columns.

**Problem**: extend the API with a `Backup` resource that the API server validates structurally (so bad specs are rejected at admission, not by your controller), supports `kubectl get backups` with useful columns, and separates spec from status.

```yaml
apiVersion: apiextensions.k8s.io/v1
kind: CustomResourceDefinition
metadata: { name: backups.ops.example.com }
spec:
  group: ops.example.com
  scope: Namespaced
  names: { kind: Backup, plural: backups, singular: backup, shortNames: [bk] }
  versions:
    - name: v1
      served: true
      storage: true                       # exactly one version is the storage version
      subresources:
        status: {}                        # enables /status; spec & status updated separately
      additionalPrinterColumns:
        - { name: Phase,  type: string, jsonPath: .status.phase }
        - { name: Target, type: string, jsonPath: .spec.target }
        - { name: Age,    type: date,   jsonPath: .metadata.creationTimestamp }
      schema:
        openAPIV3Schema:
          type: object
          required: [spec]
          properties:
            spec:
              type: object
              required: [target, schedule]
              properties:
                target:   { type: string, pattern: '^s3://' }
                schedule: { type: string }
                retention:
                  type: integer
                  minimum: 1
                  maximum: 90
                  default: 7              # defaulting happens at admission
              x-kubernetes-validations:    # CEL cross-field validation (1.25+)
                - rule: "self.retention <= 30 || self.target.startsWith('s3://archive')"
                  message: "retention > 30 days requires the archive bucket"
            status:
              type: object
              properties:
                phase:          { type: string, enum: [Pending, Running, Succeeded, Failed] }
                lastBackupTime: { type: string }
```

The `openAPIV3Schema` is what makes a CRD trustworthy: the API server validates every create/update against it (types, `required`, `pattern`, `minimum/maximum`, `enum`) and applies `default`s — so your controller never has to defend against structurally invalid objects, and users get immediate, clear rejections. `x-kubernetes-validations` adds **CEL** expressions for cross-field/conditional rules the static schema can't express, evaluated in the API server with no webhook to operate (cheaper and more reliable than a validating webhook).

The **status subresource** (`subresources: { status: {} }`) is essential for a real controller: it splits the object so a `PUT /status` only mutates `status` and a normal update only mutates `spec`. This prevents the controller (writing status) and users (writing spec) from clobbering each other, and it makes the controller's status writes not bump the spec's `generation` — which is exactly how a controller detects "has the user changed the desired state since I last reconciled?" (`metadata.generation` vs `status.observedGeneration`). `additionalPrinterColumns` make `kubectl get bk` show Phase/Target/Age instead of just name/age.

**Depth and pitfalls**: pick one storage version and use **conversion webhooks** when you add `v2` so old objects keep working. Set `x-kubernetes-preserve-unknown-fields: true` only deliberately — structural schemas (the default in `apiextensions.k8s.io/v1`) reject unknown fields, which catches typos but breaks if you embed free-form data. CRDs are served from etcd like built-ins, so a CRD with thousands of large objects and frequent watches loads the API server/etcd just like a busy native type — index with label selectors, not by listing everything. Building the *controller* for this CRD (the Operator) is the next step (Q106).

#### Q106. [Coding] Sketch a controller reconcile loop in Go (controller-runtime) and explain idempotency and requeue.

**Problem**: write the heart of an Operator for the `Backup` CRD from Q105 — a `Reconcile` function that creates a Job per Backup, updates status, and is safe to call repeatedly.

```go
func (r *BackupReconciler) Reconcile(ctx context.Context, req ctrl.Request) (ctrl.Result, error) {
    log := log.FromContext(ctx)

    // 1) GET desired state. NotFound => object was deleted; nothing to do.
    var bk opsv1.Backup
    if err := r.Get(ctx, req.NamespacedName, &bk); err != nil {
        return ctrl.Result{}, client.IgnoreNotFound(err)  // don't requeue on delete
    }

    // 2) Reconcile toward desired state: ensure a Job exists for this Backup.
    job := &batchv1.Job{ObjectMeta: metav1.ObjectMeta{
        Name: bk.Name + "-job", Namespace: bk.Namespace}}
    op, err := ctrl.CreateOrUpdate(ctx, r.Client, job, func() error {
        // mutate fn: declare desired Job spec; runs whether creating or updating
        job.Spec = buildJobSpec(&bk)
        return ctrl.SetControllerReference(&bk, job, r.Scheme) // ownerRef => GC + watch
    })
    if err != nil {
        return ctrl.Result{}, err                            // transient => auto-requeue w/ backoff
    }
    log.Info("reconciled job", "op", op)

    // 3) Update STATUS from observed reality (status subresource).
    bk.Status.Phase = phaseFromJob(job)
    bk.Status.ObservedGeneration = bk.Generation
    if err := r.Status().Update(ctx, &bk); err != nil {
        return ctrl.Result{}, err
    }

    // 4) If still running, requeue to poll; if done, stop.
    if bk.Status.Phase == "Running" {
        return ctrl.Result{RequeueAfter: 30 * time.Second}, nil
    }
    return ctrl.Result{}, nil
}

func (r *BackupReconciler) SetupWithManager(mgr ctrl.Manager) error {
    return ctrl.NewControllerManagedBy(mgr).
        For(&opsv1.Backup{}).
        Owns(&batchv1.Job{}).      // watch owned Jobs; their events requeue the owning Backup
        Complete(r)
}
```

The non-negotiable property is **idempotency**: `Reconcile` is invoked with only a *key* (namespace/name), not an event payload, and may be called many times for one change, out of order, or after a controller restart that replays everything. So it must read current desired state, compare to observed reality, and converge — never "do the next step." `CreateOrUpdate` encodes exactly that: it `GET`s, runs your mutate function, and `POST`s or `PATCH`es as needed, so running it twice is a no-op the second time. This is the code-level expression of the level-triggered model (Q56): you describe the end state, not a sequence of edits.

`SetControllerReference` sets an **owner reference** from the Job to the Backup, which gives two things free: garbage collection (delete the Backup and the Job is cascade-deleted, Q38) and, via `.Owns(&batchv1.Job{})`, a watch that requeues the owning Backup whenever its Job changes — so status tracks reality without polling. Returning an `error` makes controller-runtime requeue with exponential backoff (good for transient failures like API conflicts); returning `RequeueAfter` schedules a deliberate poll (for "still running, check later"); returning empty `Result{}` with `nil` means "done, wait for the next watch event." Writing status via `r.Status().Update` (not a plain `Update`) targets the status subresource from Q105 so it doesn't fight user spec edits.

**Pitfalls that bite in production**: never block in `Reconcile` (no long sleeps/synchronous external calls that hold the worker) — return `RequeueAfter` instead, or you starve the work queue. Handle the `Conflict` error (optimistic concurrency on `resourceVersion`) by requeueing, not retrying in a tight loop. Use a `finalizer` when reconcile must do external cleanup before the object disappears (Q38), and *always* remove it or you orphan a `Terminating` object forever (Q93). Cache reads (informer-backed `Get`) can be slightly stale; if you must read-after-write, use the API reader, but understand it's a load trade-off. Leader election (Q49) ensures only one replica reconciles at a time in an HA deployment.

#### Q107. [Coding] Replace a validating webhook with a ValidatingAdmissionPolicy (CEL). Show a policy that blocks `:latest` images.

**Problem**: enforce "no container may use the `:latest` tag or an untagged image" cluster-wide. The traditional answer is a validating admission *webhook* (a service you run); the modern answer is an in-process **ValidatingAdmissionPolicy** using CEL — no webhook server to operate.

```yaml
apiVersion: admissionregistration.k8s.io/v1
kind: ValidatingAdmissionPolicy
metadata: { name: no-latest-tag }
spec:
  failurePolicy: Fail
  matchConstraints:
    resourceRules:
      - apiGroups: ["apps",""]
        apiVersions: ["v1"]
        operations: ["CREATE","UPDATE"]
        resources: ["deployments","statefulsets","daemonsets","pods"]
  validations:
    - expression: >
        object.spec.template.spec.containers.all(c,
          !c.image.endsWith(':latest') && c.image.contains(':'))
      message: "images must use an explicit, non-':latest' tag"
      reason: Invalid
---
apiVersion: admissionregistration.k8s.io/v1
kind: ValidatingAdmissionPolicyBinding
metadata: { name: no-latest-tag-binding }
spec:
  policyName: no-latest-tag
  validationActions: ["Deny"]            # or Warn / Audit for a dry-run rollout
  matchResources:
    namespaceSelector:
      matchExpressions:
        - { key: kubernetes.io/metadata.name, operator: NotIn, values: ["kube-system"] }
```

A `ValidatingAdmissionPolicy` (GA in 1.30) runs **CEL expressions inside the API server** during admission, replacing the operational burden of a webhook (TLS certs, a highly-available deployment, network hops, the `failurePolicy` dilemma where a down webhook either blocks all writes or silently lets bad ones through — see Q43/Q84). It splits into a *policy* (the rule) and a *binding* (where it applies + the action), so one policy can be enforced in some namespaces and audited in others. `validationActions: ["Deny"]` rejects; `["Warn","Audit"]` lets you roll a policy out non-destructively first and see what it *would* block in audit logs before flipping to Deny.

The CEL here uses `containers.all(c, ...)` to assert every container satisfies the predicate; `parameters` (a referenced ConfigMap/CRD) let you make policies data-driven (allowed registries per team) without editing the policy. CEL gives you `variables`, `matchConditions` (cheap pre-filters so the expensive expression only runs when relevant), and access to `object`, `oldObject`, `request`, and `params`.

**When you still need a webhook**: VAP can only *validate* (accept/reject) — it cannot **mutate** objects. Sidecar injection, defaulting, and label-stamping still require a mutating webhook (or, increasingly, a `MutatingAdmissionPolicy`, alpha). Webhooks also win when validation needs external state (call out to a service, check a license server) since CEL is sandboxed and side-effect-free by design. For most "shape of the object" rules — required labels, banned settings, resource-limit enforcement — VAP is now the right tool: cheaper, faster, and it can't take down the cluster the way a misconfigured webhook can. Compare to OPA Gatekeeper/Kyverno, which are webhook-based policy engines; VAP brings a large fraction of that capability into core with no extra moving parts.

#### Q108. [Coding] Combine multiple sources into one mount with a projected volume, and inject a bound ServiceAccount token.

**Problem**: a Pod needs a config file, a CA cert from a ConfigMap, a TLS key from a Secret, an audience-scoped, short-lived ServiceAccount token (for calling an external OIDC-verifying API), and its own Pod name — all under one directory, with no app changes beyond reading files.

```yaml
apiVersion: v1
kind: Pod
metadata: { name: api-client }
spec:
  serviceAccountName: api-client-sa
  containers:
    - name: app
      image: api-client:1.0
      volumeMounts:
        - { name: bundle, mountPath: /var/run/app, readOnly: true }
  volumes:
    - name: bundle
      projected:
        defaultMode: 0400
        sources:
          - configMap:
              name: ca-bundle
              items: [{ key: ca.crt, path: tls/ca.crt }]
          - secret:
              name: client-tls
              items: [{ key: tls.key, path: tls/tls.key }]
          - serviceAccountToken:                 # bound, audience-scoped, auto-rotated token
              audience: external-api.example.com  # token is ONLY valid for this audience
              expirationSeconds: 3600
              path: token
          - downwardAPI:
              items:
                - path: meta/pod-name
                  fieldRef: { fieldPath: metadata.name }
```

A `projected` volume merges several source types (`configMap`, `secret`, `serviceAccountToken`, `downwardAPI`, and `clusterTrustBundle`) into a single directory tree, each mapped to a chosen `path`. This is cleaner than four separate `volumeMounts` and lets you set one `defaultMode` (here `0400` so secrets aren't world-readable, important under `restricted` Pod Security where you also want `runAsNonRoot`).

The interesting source is `serviceAccountToken`: it requests a **bound, projected token** (Q48) from the API server scoped to a specific `audience` and short `expirationSeconds`. The kubelet automatically rotates it before expiry by rewriting the file in place, so the app just re-reads `/var/run/app/token` periodically. This replaced the legacy non-expiring Secret-based tokens, which were long-lived bearer credentials that, if leaked, never expired. The `audience` field means a leaked token can't be replayed against the API server or a different service — the external API verifies the `aud` claim and rejects anything not minted for it. This is the foundation of workload-identity federation (the in-cluster token is exchanged for a cloud IAM credential via OIDC, with no static cloud keys on the Pod).

**Edge cases**: projected Secret/ConfigMap updates propagate in-place (with kubelet sync delay), but token rotation is driven by `expirationSeconds` (kubelet rotates at ~80% of lifetime). `readOnly: true` and a restrictive `defaultMode` are mandatory for the `restricted` PSA level. Don't set `expirationSeconds` too low (excess token churn, API-server load) nor too high (defeats the short-lived purpose) — 1 hour is the typical default. Mounting the token doesn't auto-mount the *default* API token; set `automountServiceAccountToken: false` on the SA/Pod if the app should only have the audience-scoped token and no cluster API access at all (least privilege).

#### Q116. [Coding] Sync secrets from an external vault using the Secrets Store CSI driver, and contrast it with the External Secrets Operator.

**Problem**: stop storing long-lived credentials in etcd. The app must read a DB password from an external manager (Vault / AWS Secrets Manager) that is auto-rotated, with no static cloud keys on the Pod.

```yaml
# SecretProviderClass: declares WHICH external secrets to fetch and how to auth
apiVersion: secrets-store.csi.x-k8s.io/v1
kind: SecretProviderClass
metadata: { name: db-spc, namespace: payments }
spec:
  provider: aws
  parameters:
    objects: |
      - objectName: "prod/db/password"
        objectType: "secretsmanager"
        objectAlias: "db-password"
  secretObjects:                          # optional: ALSO mirror into a K8s Secret (for envFrom)
    - secretName: db-creds
      type: Opaque
      data: [{ objectName: db-password, key: password }]
---
apiVersion: v1
kind: Pod
metadata: { name: app, namespace: payments }
spec:
  serviceAccountName: payments-sa         # IRSA/workload-identity bound; NO static keys
  containers:
    - name: app
      image: app:1.0
      volumeMounts:
        - { name: secrets, mountPath: /mnt/secrets, readOnly: true }   # files appear here
  volumes:
    - name: secrets
      csi:
        driver: secrets-store.csi.x-k8s.io
        readOnly: true
        volumeAttributes: { secretProviderClass: "db-spc" }
```

The **Secrets Store CSI driver** mounts external secrets as files at Pod start via a CSI volume. The Pod's ServiceAccount is federated to a cloud IAM role (IRSA on EKS, Workload Identity on GKE) so the node fetches the secret using the Pod's *identity* — there are no static cloud credentials anywhere. The secret material lives only in the Pod's tmpfs mount, not in etcd, which shrinks the blast radius of an etcd compromise. The optional `secretObjects` block mirrors the value into a real K8s Secret so legacy apps that only read env vars (`envFrom`) still work — at the cost of putting the value back into etcd, so use it only when you must.

The **External Secrets Operator (ESO)** takes the opposite architecture: a controller continuously reconciles an `ExternalSecret` CR into a native K8s `Secret`, polling the external store on an interval. It's pull-into-etcd (so the value *is* in etcd, encrypted-at-rest at best), but it works with anything that consumes a normal Secret, supports templating, and decouples the app from CSI. The CSI driver is mount-time and node-local (value only on Pods that mount it, never in etcd unless you opt in); ESO is reconcile-based and cluster-stored.

**Trade-offs and depth**: CSI gives the smallest secret footprint and supports auto-rotation (the driver re-fetches and updates the mounted files; the app must re-read, or use `rotationPollInterval`), but secrets only exist while a Pod mounts them and you can't use `envFrom` without the mirror. ESO is simpler operationally and integrates with GitOps (the `ExternalSecret` CR is in Git, the value is not), but every secret round-trips through etcd. Both eliminate the real anti-pattern — committing base64 Secrets to Git or baking credentials into images. Pair either with etcd encryption-at-rest, RBAC restricting `secrets` `get`, and rotation on the source of truth.

#### Q117. [Coding] Scale a queue consumer to and from zero with KEDA, and explain why HPA alone can't do it.

**Problem**: a worker processes messages from a Kafka topic. When the topic is empty it should scale to **zero** Pods (cost), and scale up proportional to consumer lag when messages arrive — something CPU-based HPA fundamentally cannot do.

```yaml
apiVersion: keda.sh/v1alpha1
kind: ScaledObject
metadata: { name: worker-scaler, namespace: jobs }
spec:
  scaleTargetRef: { name: worker }          # the Deployment to scale
  minReplicaCount: 0                          # <-- scale to zero when idle
  maxReplicaCount: 50
  cooldownPeriod: 300                         # wait 5m of zero activity before going to 0
  pollingInterval: 15
  triggers:
    - type: kafka
      metadata:
        bootstrapServers: kafka:9092
        consumerGroup: workers
        topic: orders
        lagThreshold: "100"                   # ~1 replica per 100 messages of lag
        activationLagThreshold: "1"           # any lag >0 wakes it from zero
```

KEDA solves two things HPA can't. First, **scale-to/from-zero**: a standard HPA's floor is `minReplicas: 1` and it can't act on an external signal to wake a scaled-to-zero workload, because with zero Pods there are no resource metrics to read. KEDA runs an `activationThreshold` check independent of the metrics pipeline — when lag crosses it, KEDA scales the Deployment from 0 to 1, after which its other component takes over. Second, **event-source metrics**: KEDA ships dozens of scalers (Kafka lag, SQS/RabbitMQ queue depth, Prometheus queries, cron, cloud monitoring) so you scale on the *backlog* you actually care about, not a CPU proxy that lags the real signal.

Architecturally, KEDA is not a replacement for HPA — it's an **adapter that drives HPA**. KEDA's operator creates and manages a normal HPA under the hood (using `external` metrics it serves via its metrics adapter), so all of HPA's behavior (stabilization windows, scale policies, Q85) still applies for the 1→N range. KEDA itself only owns the 0↔1 transition. This is why you configure `minReplicaCount`/`maxReplicaCount` on the `ScaledObject` and KEDA translates them into the underlying HPA.

**Edge cases and depth**: scaling on lag risks over-scaling if consumers are slow for a reason *other* than backlog (downstream is the bottleneck), so cap `maxReplicaCount` to what the downstream can absorb. Scale-to-zero adds cold-start latency on the first message — unacceptable for synchronous request paths, ideal for async batch/stream workloads. The `cooldownPeriod` prevents flapping to zero during brief lulls. For Kafka specifically, replicas beyond the partition count are useless (idle consumers in the group), so `maxReplicaCount` should not exceed partitions. KEDA's `ScaledJob` variant scales *Jobs* instead of Deployments for run-to-completion semantics per message batch — better when each unit of work should be a fresh Pod.

#### Q118. [Coding] Use Server-Side Apply with field ownership to let a controller and a human co-manage one object without clobbering.

**Problem**: a GitOps controller manages a Deployment's image and replicas, while an autoscaler also wants to manage `replicas`, and an SRE occasionally patches an annotation by hand. With client-side `kubectl apply`, these stomp on each other. Make ownership explicit so each party only owns its fields.

```bash
# Server-Side Apply: each applier declares a field manager; the API server tracks
# WHICH manager owns WHICH fields in metadata.managedFields.
kubectl apply --server-side --field-manager=gitops -f deploy.yaml

# The autoscaler owns ONLY replicas — apply just that field as a different manager.
kubectl patch deploy/web --server-side --field-manager=autoscaler \
  --type=apply -p '{"apiVersion":"apps/v1","kind":"Deployment","spec":{"replicas":7}}'

# Inspect ownership:
kubectl get deploy web --show-managed-fields -o yaml | yq '.metadata.managedFields'
```

```yaml
# When gitops re-applies WITHOUT replicas, it must NOT reset the autoscaler's value.
# Solution: gitops omits replicas from its manifest entirely, so it never claims that field.
# If a manager DID previously own a field and now drops it, the field is removed unless
# another manager owns it — this is how SSA does pruning correctly.
```

Server-Side Apply (SSA, GA since 1.22) moves apply logic from the client into the API server and records **field-level ownership** in `metadata.managedFields`: a list of (manager, fieldset) entries. Each `--field-manager` declares intent over exactly the fields it sends. When `gitops` applies a manifest that omits `replicas`, it relinquishes (or never claims) ownership of `replicas`, so the `autoscaler`'s value stands. This is the correct multi-writer model that client-side apply lacked — client-side apply computed diffs against a `last-applied-configuration` annotation that only the *last* applier wrote, so two appliers fought and the second silently reverted the first.

A **conflict** occurs when a manager tries to set a field another manager already owns. SSA returns a `409 Conflict` listing the conflicting fields and owners, forcing an explicit decision rather than a silent overwrite. You resolve it by either changing your manifest to not touch that field, or by passing `--force-conflicts` to forcibly take ownership (appropriate when you *intend* to take over, e.g., a deliberate migration). This explicitness is the feature: silent clobbering becomes a visible, deliberate act.

**Depth and pitfalls**: the canonical real-world bug SSA fixes is "GitOps keeps resetting the HPA's replica count" — the fix is to remove `replicas` from the Git manifest so GitOps doesn't own it (Argo CD even has an `ignoreDifferences`/`managedFieldsManagers` setting built on exactly this). Lists are merged by their declared merge key (containers by `name`), and atomic lists/maps are owned wholesale — get the schema's merge strategy wrong and you can unexpectedly own or wipe a whole list. `managedFields` can bloat objects with many managers; it's hidden by default (`--show-managed-fields` to see it). SSA is also what controllers should use internally (controller-runtime's `client.Apply`) so each controller cleanly owns its slice of shared objects without read-modify-write races.

### 🔴 Expert — extended

#### Q109. [Coding] Implement lease-based leader election for a custom controller and explain split-brain prevention.

**Problem**: you run 3 replicas of a custom controller for HA, but only one may actively reconcile at a time (two controllers fighting over the same objects causes thrash and duplicate side effects). Implement active/passive election using the `coordination.k8s.io` Lease API.

```go
import (
    "k8s.io/client-go/tools/leaderelection"
    "k8s.io/client-go/tools/leaderelection/resourcelock"
)

func runWithElection(ctx context.Context, cs kubernetes.Interface, id string, run func(context.Context)) {
    lock := &resourcelock.LeaseLock{
        LeaseMeta: metav1.ObjectMeta{Name: "my-controller", Namespace: "ops"},
        Client:    cs.CoordinationV1(),
        LockConfig: resourcelock.ResourceLockConfig{Identity: id}, // unique per replica (pod name)
    }
    leaderelection.RunOrDie(ctx, leaderelection.LeaderElectionConfig{
        Lock:            lock,
        ReleaseOnCancel: true,
        LeaseDuration:   15 * time.Second, // a leader's lease is valid this long
        RenewDeadline:   10 * time.Second, // leader must renew within this or it steps down
        RetryPeriod:     2 * time.Second,  // how often candidates retry acquire/renew
        Callbacks: leaderelection.LeaderCallbacks{
            OnStartedLeading: func(ctx context.Context) { run(ctx) },        // become active
            OnStoppedLeading: func() { os.Exit(0) },                          // lost lease: STOP NOW
        },
    })
}
```

A `Lease` is a lightweight built-in object holding `holderIdentity` and a `renewTime`. Election is cooperative optimistic concurrency: every candidate tries to acquire/update the Lease using the API server's `resourceVersion` compare-and-swap, so only one write wins per round. The current leader keeps `renewTime` fresh every `RetryPeriod`; if it can't renew within `RenewDeadline` (crash, partition, GC pause), it stops leading, and after `LeaseDuration` elapses without renewal, a follower acquires the Lease and becomes leader. This is *lease*-based rather than a held lock (Q49) precisely because a held lock can't survive a crashed holder — a TTL'd lease self-heals when the holder dies and stops renewing.

**Split-brain is prevented by the timing invariant**, not by the lease alone. The dangerous window is a leader that *thinks* it's still leader (e.g., paused by a long GC or partitioned from the API server) while a new leader has taken over. The contract that makes this safe: the old leader MUST stop all work the instant it fails to renew within `RenewDeadline`, and `RenewDeadline` must be comfortably less than `LeaseDuration`. That gap (here 10s renew vs 15s lease) guarantees a stale leader steps down *before* a new one can possibly take the lease. The `OnStoppedLeading` callback doing `os.Exit(0)` is the belt-and-suspenders enforcement: rather than trust in-process code to cleanly halt, kill the process so the kubelet restarts it as a fresh follower.

**Depth**: leader election prevents *concurrent reconciliation*, not all duplicate side effects — if the leader performs a non-idempotent external action (charge a credit card) and dies mid-flight, the new leader may repeat it, so external operations still need idempotency keys. Tuning is a latency/safety trade: shorter durations fail over faster but increase API-server write load and false step-downs under transient latency; longer durations are gentler but slow recovery. This is the exact mechanism `kube-controller-manager` and `kube-scheduler` use for their own HA, and controller-runtime exposes it via `LeaderElection: true` on the Manager.

#### Q110. [Coding] Write a Pod that uses an init container for a DB migration gated by a leader, with proper ordering and failure semantics.

**Problem**: every replica of an app must wait for a one-time schema migration to finish before serving, the migration must run exactly once even with N replicas starting simultaneously, and a failed migration must block the rollout (not serve traffic against a half-migrated DB).

```yaml
apiVersion: apps/v1
kind: Deployment
metadata: { name: orders }
spec:
  replicas: 5
  selector: { matchLabels: { app: orders } }
  template:
    metadata: { labels: { app: orders } }
    spec:
      initContainers:
        - name: wait-for-db                    # ordering: runs to completion first
          image: busybox:1.36
          command: ["sh","-c","until nc -z db 5432; do echo waiting; sleep 2; done"]
        - name: migrate                        # idempotent migration tool with an advisory lock
          image: migrator:4.1
          args: ["migrate","up","--lock"]      # tool takes a Postgres advisory lock => exactly-once
          env:
            - name: DATABASE_URL
              valueFrom: { secretKeyRef: { name: db-creds, key: url } }
      containers:
        - name: app
          image: orders:2.3
          readinessProbe: { httpGet: { path: /ready, port: 8080 }, periodSeconds: 5 }
```

Init containers run **sequentially, each to successful completion, before any app container starts** — that ordering is the whole point here. `wait-for-db` blocks until the database is reachable (a Pod whose dependency isn't up shouldn't even attempt migration), then `migrate` runs. If any init container exits non-zero, the kubelet restarts it per the Pod's `restartPolicy` and the app container never starts, so the Deployment's rollout stalls at the new ReplicaSet and `kubectl rollout status` (Q104) reports failure — exactly the desired "broken migration blocks the deploy" behavior, with the old ReplicaSet still serving.

The hard part is **exactly-once across 5 simultaneously-starting Pods**. Init containers do *not* coordinate across Pods — all five `migrate` containers run at once. The correct pattern pushes the mutual exclusion to a layer that *can* serialize: a database **advisory lock** (`SELECT pg_advisory_lock(...)` inside the migration tool) so the first Pod runs migrations and the rest block, then observe migrations are already applied and exit 0. The migration tool must be **idempotent** (tracks applied versions) so the losers are no-ops. An init container alone cannot give you exactly-once — that's a common interview trap; the answer is "make the operation idempotent and serialize at the DB."

**Alternatives and depth**: for cleaner separation, run the migration as a Helm pre-install/pre-upgrade **hook Job** (or an Argo/Flux sync hook) that completes before the Deployment is updated, so migration is decoupled from Pod startup entirely and runs once by construction — generally preferred for production because the Pod startup path stays fast and a migration failure is a discrete, retriable Job rather than CrashLooping app Pods. Use init containers for *per-Pod* prerequisites (wait-for-dependency, fetch config, set sysctls) and a pre-deploy Job for *cluster-wide once* operations. Note also that a long-running helper that must coexist with the app (not run-once) is a **native sidecar** (`restartPolicy: Always`, Q101/Q60), not an init container.

#### Q111. [Coding] Write a kubectl plugin (krew-style) as a shell script and explain the discovery mechanism.

**Problem**: standardize a common ops task — "show me all Pods on a node sorted by memory request" — as a first-class `kubectl` subcommand the whole team can install.

```bash
#!/usr/bin/env bash
# File must be named kubectl-node_top and be on $PATH, executable.
# Invoked as:  kubectl node-top <node>   (kubectl maps the dash to underscore lookup)
set -euo pipefail

if [[ "${1:-}" == "--help" || -z "${1:-}" ]]; then
  echo "Usage: kubectl node-top <node-name>"; exit 0
fi
NODE="$1"

kubectl get pods -A --field-selector "spec.nodeName=${NODE}" -o json \
  | jq -r '
      .items[]
      | { ns: .metadata.namespace, name: .metadata.name,
          mem: ([.spec.containers[].resources.requests.memory // "0"] | join("+")) }
      | "\(.ns)/\(.name)\t\(.mem)"' \
  | sort -k2 -h -r \
  | column -t
```

```bash
chmod +x kubectl-node_top && mv kubectl-node_top /usr/local/bin/
kubectl plugin list           # verifies kubectl discovered it
kubectl node-top ip-10-0-1-23 # runs the plugin
```

`kubectl`'s plugin mechanism is intentionally trivial: any executable on `$PATH` named `kubectl-<name>` becomes the subcommand `kubectl <name>`. Dashes in the command map to underscores in the filename (`kubectl-node_top` → `kubectl node-top`), and `kubectl` passes the remaining args straight through. There's no SDK, registration, or compilation required — a plugin can be a shell script, a Go binary, anything executable. **Krew** is the package manager that distributes these plugins (it's itself a plugin: `kubectl-krew`) with versioning and a central index, which is how teams share tooling like `kubectl-neat`, `kubectl-tree`, or `kubectl-stern`.

The script leans on two underused server-side features. `--field-selector spec.nodeName=<node>` filters **on the API server** (only Pods on that node are returned), which matters at scale — fetching all Pods and filtering client-side would pull megabytes from a large cluster and load the API server. Field selectors are limited to specific indexed fields per resource (you can't field-select on arbitrary paths), so `jq` does the rest of the shaping client-side. This division — server filters what it can index, client shapes the rest — is the right pattern for any list-heavy tooling.

**Depth and caveats**: for anything nontrivial, write the plugin in Go using `cli-runtime`/`genericclioptions` so it honors `--kubeconfig`, `--context`, `--namespace`, and output flags exactly like core `kubectl` — shell plugins silently ignore those. Plugins inherit the caller's RBAC, so a plugin can't do more than the user can. Avoid shadowing real subcommands (a `kubectl-get` on PATH would not override the built-in `get` — built-ins win — but it's confusing). And remember plugins run client-side: they're great for read/shape/report workflows and orchestrating `kubectl` calls, but stateful or privileged logic belongs in a controller, not a plugin a user runs ad hoc.

#### Q112. [Coding] Diagnose intermittent 5xx during deploys with a script, then fix it with preStop + readiness + termination tuning.

**Problem**: during every rolling deploy, a small fraction of in-flight requests return 502/504 even though replicas are "healthy." Reproduce, prove the cause, and fix it without changing the app's request handling.

```bash
# Reproduce: hammer the service through the LB while triggering a rollout.
kubectl run loadgen --rm -i --restart=Never --image=williamyeh/hey -- \
  hey -z 60s -c 50 "http://app.example.com/" &
sleep 5
kubectl rollout restart deployment/app
wait    # hey prints a status-code histogram; nonzero 502/504 == dropped in-flight requests
```

```yaml
# Fix: graceful shutdown contract between K8s, the LB, and the app.
spec:
  strategy:
    rollingUpdate: { maxUnavailable: 0, maxSurge: 1 }   # never drop below desired capacity
  template:
    spec:
      terminationGracePeriodSeconds: 45
      containers:
        - name: app
          lifecycle:
            preStop:
              exec:
                command: ["/bin/sh","-c","sleep 10"]    # absorb in-flight + LB deprogram lag
          readinessProbe:                                  # gates endpoint removal precisely
            httpGet: { path: /ready, port: 8080 }
            periodSeconds: 2
            failureThreshold: 2
```

The root cause is a **race between endpoint removal and connection draining**, and the script proves it by correlating 5xx spikes with rollout timing. When a Pod is deleted, two things happen *concurrently and asynchronously*: (1) the kubelet sends `SIGTERM` to the container, and (2) the Pod is removed from the Service's EndpointSlice, after which kube-proxy/the cloud LB reprograms its rules. These are not ordered with respect to each other and the LB reprogramming has real latency (seconds). If the app exits on `SIGTERM` *before* every dataplane has stopped sending it new connections, those new connections hit a dead Pod → 502/504. Readiness probes don't help once termination starts, and "the Pod is healthy" is irrelevant — the problem is traffic arriving after the process is gone.

The fix is a deliberate **drain contract**. `preStop: sleep 10` runs *before* `SIGTERM` and is the canonical trick: it delays the app's shutdown long enough for the endpoint removal to propagate through every kube-proxy and external LB, so by the time the app actually gets `SIGTERM`, no new traffic is being routed to it. During that window the app keeps serving in-flight requests. `terminationGracePeriodSeconds` (45) must exceed `preStop` + the app's longest in-flight request, or the kubelet escalates to `SIGKILL` and you drop requests anyway. `maxUnavailable: 0` keeps full capacity during the roll so remaining replicas can absorb the shifted load. A fast readiness probe (`periodSeconds: 2`) tightens how quickly a Pod is removed from rotation when it *does* go unready for other reasons.

**Depth**: the better long-term fix is an app that handles `SIGTERM` by failing readiness, finishing in-flight requests, then exiting — but `preStop sleep` is the reliable, app-agnostic mitigation and is recommended even alongside graceful app shutdown because it covers the LB-deprogramming lag the app can't see. For cloud LoadBalancer Services, also ensure target-group deregistration delay and health-check intervals are tuned, and prefer `externalTrafficPolicy: Local` carefully (it preserves client IP but routes only to node-local Pods, changing the failure surface). This is one of the most common "everything looks healthy but users see errors during deploys" incidents, and it's invisible to any check that only looks at Pod health.

#### Q113. [Coding] Implement graceful, ordered shutdown of a 5-replica StatefulSet cluster and explain the ordering guarantees vs a Deployment.

**Problem**: you run a quorum-based datastore as a 5-replica StatefulSet. You must scale it down to 3 and perform a rolling config change without losing quorum or corrupting data — something a Deployment's "kill any Pod" model would do.

```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata: { name: kv }
spec:
  serviceName: kv-headless
  replicas: 5
  podManagementPolicy: OrderedReady       # default: kv-0 ready before kv-1 starts, etc.
  updateStrategy:
    type: RollingUpdate
    rollingUpdate:
      partition: 0                        # only ordinals >= partition are updated (canary knob)
      maxUnavailable: 1                    # 1.27+ alpha->beta: limit concurrent updates
  template:
    spec:
      terminationGracePeriodSeconds: 120
      containers:
        - name: kv
          image: kvstore:5.0
          lifecycle:
            preStop:                       # leave the quorum cleanly before dying
              exec: { command: ["/bin/sh","-c","kvctl leave --wait"] }
          readinessProbe:
            exec: { command: ["/bin/sh","-c","kvctl healthy --self"] }
```

```bash
# Scale down: removes the HIGHEST ordinals first (kv-4 then kv-3), one at a time,
# each fully terminating before the next — preserving quorum at every step.
kubectl scale statefulset/kv --replicas=3

# Canary a config change to ONLY kv-4 first, then ramp by lowering the partition.
kubectl patch statefulset/kv --type merge -p '{"spec":{"updateStrategy":{"rollingUpdate":{"partition":4}}}}'
# verify kv-4, then partition:3, ... partition:0 to finish the roll.
```

A StatefulSet gives **ordered, identity-stable lifecycle** that a Deployment fundamentally cannot. Scale-up creates `kv-0..kv-n` in order, each becoming Ready before the next starts (`OrderedReady`); scale-down deletes the **highest ordinals first**, one at a time, each fully terminated before the next. For a quorum system this is exactly right: shrinking 5→3 removes `kv-4` then `kv-3`, and at no point are two members removed simultaneously, so a 5-node cluster (quorum 3) stays at ≥3 healthy members throughout. The `preStop` `kvctl leave --wait` lets the departing member hand off its data/voting role cleanly before `SIGTERM`, and `terminationGracePeriodSeconds: 120` gives that handoff time before `SIGKILL`. A Deployment, by contrast, treats Pods as interchangeable and would terminate arbitrary Pods in parallel (`maxUnavailable`), which for a stateful quorum means simultaneous member loss → lost quorum → unavailability or split-brain.

The **`partition` field** is the StatefulSet's killer feature for safe upgrades: with `partition: N`, only Pods with ordinal `≥ N` are updated; lower ordinals keep the old spec. Setting `partition: 4` on a 5-replica set updates only `kv-4` — a canary on a single, named, stable member you can validate before ramping. Lowering the partition step by step (4→3→…→0) rolls the change down the cluster in reverse-ordinal order, fully under your control, and bumping it back up instantly halts the roll. This is far more surgical than a Deployment's all-or-nothing surge-based rollout, and it's why operators for Kafka, ZooKeeper, etcd, and databases are built on StatefulSets.

**Depth and gotchas**: stable network identity (`kv-0.kv-headless...` via the required headless Service) and per-Pod PVCs from `volumeClaimTemplates` mean a rescheduled `kv-2` keeps *its* identity and *its* data — essential for clustered systems that pin peers by address. PVCs are **not** deleted on scale-down by default (a safety feature; use `persistentVolumeClaimRetentionPolicy` to opt into deletion), so scaling 5→3→5 re-attaches the original volumes. `podManagementPolicy: Parallel` disables the ordering for systems that don't need it (faster startup). The real-world subtlety: `OrderedReady` can **deadlock a roll** if a middle Pod never becomes Ready (the roll won't proceed past it) — which is the safe behavior, but means your readiness probe must accurately reflect cluster membership, and you sometimes need `maxUnavailable`/manual intervention to recover a wedged cluster.

#### Q114. [Behavioral] Tell me about a severe Kubernetes production incident you led the response to. (STAR)

**Situation.** At a fintech where I was the staff engineer on the platform team, we ran a multi-tenant cluster serving ~120 services for 14 product teams. One afternoon, every team simultaneously lost the ability to deploy: `kubectl apply` hung and then failed with `Internal error occurred: failed calling webhook`. Existing workloads kept serving (the data plane was fine), but no team could ship, including the on-call teams trying to push hotfixes — and we were 30 minutes from a regulatory-reporting deadline that required a deploy. It was a Sev1 with the whole engineering org blocked.

**Task.** As incident commander I had two jobs: restore the ability to deploy *immediately*, and find the root cause without making it worse. The pressure was that the obvious fast fix (deleting things) risked widening the blast radius, and I had multiple anxious team leads pushing for me to "just force it."

**Action.** I first established the blast radius precisely: data plane healthy, control plane reachable, only *writes* to certain resource types failing — which pointed at admission. `kubectl get validatingwebhookconfigurations` showed our policy engine (a Gatekeeper-style validating webhook) configured with `failurePolicy: Fail` and no namespace exclusions. A check of its Pods showed they were CrashLooping after a bad policy bundle had been rolled out by another team's CI 20 minutes earlier — so every API write that the webhook intercepted was being rejected because the webhook itself was down. I made a deliberate, logged decision: rather than disable policy globally, I patched the webhook configuration's `namespaceSelector` to exclude `kube-system` and added the `failurePolicy` consideration, then, because the webhook backend was fully down, I temporarily set its `failurePolicy: Ignore` to unblock writes — announced explicitly in the incident channel as "we are running without policy enforcement for the next N minutes; do not merge risky changes." That restored deploys within ~4 minutes and the regulatory deploy went out with 11 minutes to spare. Then I rolled back the bad policy bundle, watched the webhook Pods go healthy, and *reverted* `failurePolicy` back to `Fail` so we weren't silently unprotected.

**Result.** We hit the deadline and had zero data-plane impact. In the blameless postmortem I drove three durable fixes: (1) admission webhooks must exclude `kube-system` and critical namespaces and run HA with a PodDisruptionBudget, so a webhook outage can never brick the cluster; (2) policy-bundle rollouts now go through a canary namespace with `validationActions: Warn`/Audit before cluster-wide `Deny`, and we began migrating shape-only rules to in-process ValidatingAdmissionPolicy (CEL) to remove the webhook as a hard dependency entirely; (3) a runbook and a break-glass `kubectl` alias for the "webhook is down" scenario, plus an alert on webhook backend availability. The broader lesson I socialized org-wide: **a `failurePolicy: Fail` webhook is a single point of failure for the entire control plane's write path**, and any cluster-wide admission control needs the same HA rigor as the API server itself. I also changed how I run incidents — narrating the trade-off of each risky action in the channel in real time kept the pressuring stakeholders aligned instead of second-guessing, which mattered more than the technical fix.

#### Q115. [Coding] Detect and safely remediate a leaking finalizer that wedges objects in Terminating, with a guard against the dangerous fix.

**Problem**: a batch of custom resources (and the namespace containing them) are stuck `Terminating` for hours. The internet's top answer ("force-remove the finalizer") is sometimes correct and sometimes catastrophic. Build a diagnosis-then-remediation flow that distinguishes the two.

```bash
# 1) Identify WHAT is stuck and WHICH finalizer is holding it.
kubectl get ns stuck-ns -o jsonpath='{.status.conditions}' | jq .   # tells you the blocking API/resources
kubectl get backups.ops.example.com -n stuck-ns \
  -o custom-columns='NAME:.metadata.name,FINALIZERS:.metadata.finalizers,DELETED:.metadata.deletionTimestamp'

# 2) Find out WHY the finalizer isn't being removed: is the controller alive?
kubectl get deploy -n ops backup-operator
kubectl logs -n ops deploy/backup-operator --tail=100   # is it erroring on finalize? crashed? gone?
```

```bash
# 3a) PREFERRED FIX: get the controller working so it completes its finalizer logic.
kubectl rollout restart -n ops deploy/backup-operator    # often the controller was just down
#     ...then watch the objects drain naturally as finalizers run their cleanup.

# 3b) LAST RESORT (only after confirming external cleanup is done or unnecessary):
kubectl patch backups.ops.example.com/old-backup -n stuck-ns \
  --type=json -p='[{"op":"remove","path":"/metadata/finalizers"}]'
```

A `deletionTimestamp` is set the moment you delete an object, but the object is **not actually removed until its `finalizers` list is empty**. A finalizer is a string that signals "some controller must run cleanup before this object can go." The controller is supposed to do its external cleanup (delete the cloud snapshot, deregister from an external system) and *then* remove its own finalizer, after which the API server garbage-collects the object. So a stuck `Terminating` object almost always means **the controller responsible for that finalizer is down, crashed, uninstalled, or erroring** — the object is correctly refusing to vanish before its cleanup ran. A `Terminating` *namespace* is the same mechanism at the namespace controller level: it can't finish until every object inside it (including finalizer-held CRs) is gone, and its `status.conditions` name exactly which API group is blocking.

This is why the popular "just `kubectl patch` the finalizer to `[]`" advice is dangerous: it tells the API server "skip the cleanup and delete now," which **orphans whatever the finalizer was protecting** — a leaked cloud disk that keeps billing, an external DB still registered to a deleted tenant, a dangling DNS record. The correct order of operations is diagnosis first: identify the finalizer, find its owning controller, and *fix the controller* (restart it, reinstall the CRD's operator, fix the RBAC that's denying its cleanup calls) so the finalizer runs as designed and the object drains cleanly. Force-removing the finalizer is only acceptable once you've confirmed the external cleanup is already done, is genuinely unnecessary, or the backing resource is gone anyway (e.g., the operator was permanently uninstalled and you're tearing down).

**Depth**: the special case people actually hit is a namespace stuck because an **aggregated API service is unavailable** (`kubectl get apiservices` shows one `False`) — the namespace controller can't list resources in that group, so it can't confirm the namespace is empty. The fix there is to restore or delete the broken `APIService`, not to force the namespace. Never use the legacy `/finalize` raw API call to nuke a namespace's finalizers unless you accept orphaning every resource inside it. The systemic prevention is to make finalizer cleanup *idempotent and bounded* (a controller that can't reach the external system should requeue, not block forever) and to monitor for objects with a `deletionTimestamp` older than a threshold as a leading indicator of a wedged controller.

#### Q119. [Coding] Debug a running Pod that ships a distroless image (no shell) using ephemeral debug containers.

**Problem**: a Pod built from a distroless image (Q96) is misbehaving in production. There's no `sh`, `curl`, `netstat`, or `ps` inside, and you can't `kubectl exec`. You must inspect it live without rebuilding the image or restarting the Pod.

```bash
# Attach an ephemeral container sharing the target container's namespaces.
kubectl debug -it mypod --image=nicolaka/netshoot \
  --target=app --share-processes -- bash
#   --target=app       : join app's PID namespace, so you can see its processes
#   --share-processes  : also enabled at Pod level so you see all containers' PIDs

# Inside netshoot you now have a full toolbox against the SAME network/PID namespace:
#   ps aux            -> see the distroless app's process and args
#   ss -tlnp          -> what ports is it actually listening on?
#   curl localhost:8080/ready  -> hit its endpoints from inside its network namespace
#   cat /proc/1/environ | tr '\0' '\n'  -> the app's real env vars

# Copy-debug: clone a CrashLooping Pod with the entrypoint replaced so it stays up.
kubectl debug mypod -it --copy-to=mypod-dbg --image=busybox \
  --container=app -- sh        # new Pod, same spec, debuggable entrypoint

# Node-level debug: a privileged Pod on the host's namespaces (no SSH needed).
kubectl debug node/ip-10-0-1-7 -it --image=ubuntu   # chroot /host to inspect the node
```

`kubectl debug` injects an **ephemeral container** into a running Pod — a container added to `pod.spec.ephemeralContainers` that the kubelet starts in the existing Pod's namespaces, without recreating the Pod or being subject to its restart/resource guarantees. With `--target=app` it joins the target container's PID namespace, so from a full-featured image like `netshoot` you can see the distroless app's process, hit its localhost ports (shared network namespace), and read `/proc/<pid>/environ` and `/proc/<pid>/root/...` to inspect the app's filesystem view — all live, in production, on the actual failing instance.

This exists precisely because the secure-image trend (distroless/scratch, `readOnlyRootFilesystem`, no shell) deliberately removes the tools an attacker *or an operator* would use. Ephemeral containers decouple "what ships" from "what I debug with": the production image stays minimal, and you bring tooling in on demand. For a Pod that crashes too fast to attach to, `--copy-to` clones it into a new Pod with a debuggable command/entrypoint so it stays up. For node problems, `kubectl debug node/...` schedules a privileged Pod in the node's host namespaces — a kubectl-native alternative to SSHing the box.

**Depth and caveats**: ephemeral containers cannot be removed once added (they persist in the Pod spec until the Pod dies) and have no resource requests/limits, so they can pressure the node — they're for debugging, not running workloads. They require the `EphemeralContainers` feature (GA since 1.25) and appropriate RBAC (`pods/ephemeralcontainers` is a distinct subresource you must grant). Under `restricted` Pod Security, the debug container must also satisfy the namespace's PSA level (no privilege escalation), which can limit what you can do — `kubectl debug --profile=...` (sysadmin/netadmin) sets the appropriate securityContext where policy allows. The security flip side: granting `pods/ephemeralcontainers` is effectively granting code execution inside any Pod, so treat it as a privileged permission.

#### Q120. [Coding] Implement topology-aware routing and a PodDisruptionBudget so zone failure and node drains don't drop traffic or cross-AZ cost.

**Problem**: a service spread across 3 AZs pays high cross-AZ data charges because traffic is routed randomly, and node drains during upgrades occasionally take too many replicas down at once. Keep traffic in-zone when safe, and bound voluntary disruption.

```yaml
apiVersion: v1
kind: Service
metadata:
  name: web
  annotations:
    service.kubernetes.io/topology-mode: Auto    # enable topology-aware routing
spec:
  selector: { app: web }
  ports: [{ port: 80, targetPort: 8080 }]
  # trafficDistribution: PreferClose            # 1.31+ GA replacement for the annotation
---
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata: { name: web-pdb }
spec:
  minAvailable: 80%                              # never let voluntary disruption drop below 80%
  selector: { matchLabels: { app: web } }
---
# Pair with spread so "80% available" is also "spread across zones", not 80% in one AZ.
spec:
  template:
    spec:
      topologySpreadConstraints:
        - maxSkew: 1
          topologyKey: topology.kubernetes.io/zone
          whenUnsatisfiable: DoNotSchedule
          labelSelector: { matchLabels: { app: web } }
```

**Topology-aware routing** (the `topology-mode: Auto` annotation, or the newer `spec.trafficDistribution: PreferClose` field GA in 1.31) makes kube-proxy/the dataplane prefer endpoints in the *consumer's own zone*. The EndpointSlice controller computes per-zone "hints" and only keeps traffic in-zone when each zone has proportional capacity (so it won't overload an under-provisioned zone). The payoff is real money: cross-AZ traffic is billed per GB on every major cloud, and a chatty internal service can spend more on cross-AZ transfer than on compute. The safety valve is that the controller *disables* hints and falls back to cluster-wide routing if zones are imbalanced, so you don't trade cost for an overloaded zone — which is also the main gotcha: with very few replicas or skewed spread, hints silently don't engage and you keep paying.

The **PodDisruptionBudget** governs *voluntary* disruptions — `kubectl drain`, node upgrades, the cluster autoscaler/descheduler evicting Pods. With `minAvailable: 80%`, the eviction API refuses to evict a Pod if doing so would drop ready replicas below the budget, so a rolling node upgrade is forced to wait for replacements rather than taking down a quorum of replicas at once. Critically, a PDB constrains *eviction* but **does not protect against involuntary disruption** (a node hard-crashing, an AZ outage) — for that you need the spread constraints, which is why the two are deployed together: PDB bounds planned churn, topology spread bounds the impact of unplanned loss.

**Depth and interactions**: a PDB that's *too* strict can wedge a node drain forever (`minAvailable: 100%` means a node can never be drained — a common self-inflicted outage during maintenance), so set it relative to replica count with headroom. PDB + `maxUnavailable: 0` on the Deployment + spread is the trifecta for "upgrade with zero capacity loss." Topology routing interacts with `externalTrafficPolicy` for external traffic and with `internalTrafficPolicy: Local` for node-local internal routing (lowest latency, but no spreading). Watch the failure mode where topology hints + a PDB + an AZ outage combine: the surviving zones must have enough capacity (and the HPA headroom) to absorb the failed zone's traffic, or in-zone preference plus a tight PDB starves replacements — capacity planning, not just config, is what makes this resilient.

#### Q121. [Coding] Right-size memory for a JVM (or Go) workload that keeps getting OOMKilled, including the cgroup-awareness fix.

**Problem**: a Java service with `memory: 512Mi` limit is repeatedly OOMKilled (exit 137) even though heap usage looks fine in the app's own metrics. Diagnose and fix it properly, then generalize the runtime-vs-cgroup mental model.

```bash
# Confirm it's the kernel OOM-killer, not an app exception:
kubectl get pod app -o jsonpath='{.status.containerStatuses[0].lastState.terminated}'
#   -> { "exitCode": 137, "reason": "OOMKilled", "signal": 9 }

# See actual RSS vs the limit at the moment of death:
kubectl top pod app --containers     # live; for historical, query container_memory_working_set_bytes
```

```yaml
spec:
  containers:
    - name: app
      image: myjava:21
      resources:
        requests: { memory: 768Mi }   # set == limit for Guaranteed QoS (evicted last, Q8)
        limits:   { memory: 768Mi }
      env:
        # Java 10+ is cgroup-aware, but you MUST cap heap as a % of the cgroup limit,
        # leaving room for off-heap: metaspace, thread stacks, JIT code cache, direct buffers.
        - { name: JAVA_TOOL_OPTIONS, value: "-XX:MaxRAMPercentage=70 -XX:+UseContainerSupport" }
```

The trap is conflating **JVM heap** with **container RSS**. The kernel OOM-killer (and Kubernetes' OOMKilled) counts the *whole* container's working set: heap **plus** off-heap — JVM metaspace, thread stacks (each thread ~512KB–1MB), the JIT code cache, direct/NIO byte buffers, GC structures, and the JVM's own native footprint. An app reporting "heap is 300MB of a 512MB limit, all fine" is ignoring 150–250MB of off-heap that pushes total RSS past the cgroup limit → SIGKILL. The fix is twofold: (1) cap heap as a *fraction* of the limit (`-XX:MaxRAMPercentage=70`) so off-heap has headroom, and (2) size the limit from observed total RSS at peak, not from heap. Older JVMs (pre-Java 8u191) read the *host's* memory, not the cgroup, and would size a multi-GB heap inside a 512MB container — `-XX:+UseContainerSupport` (default on modern JVMs) reads the cgroup limit instead.

The Go analogue: the Go runtime historically ignored cgroup limits and the GC would let the heap grow toward *host* memory, getting OOMKilled under a tight container limit while the GC "wasn't worried yet." The modern fix is `GOMEMLIMIT` (Go 1.19+) set to ~90% of the container limit, which makes the GC run more aggressively as it approaches the limit, trading CPU for staying under the cap. Without it you'd tune `GOGC`, which is a blunter instrument.

**Right-sizing method and depth**: set memory `requests == limits` for predictable, latency-sensitive services (Guaranteed QoS, last to be evicted, Q8) — unlike CPU, you generally *do* want a memory limit because memory is incompressible and an unbounded leak takes down the node. Size the value from the **working set at peak** (`container_memory_working_set_bytes` p99 over a representative window) plus a safety margin (~20–30%), not from a guess. The most common production cause of repeated OOMKills isn't a leak at all — it's a runtime that isn't cgroup-aware or a limit set from heap-only numbers; the second is an actual leak, which you confirm by RSS trending up monotonically across restarts (a sawtooth that resets cleanly on GC is healthy). VPA in recommendation mode (Q14) can suggest values at scale, but it doesn't understand JVM/Go runtime knobs, so it must be paired with the runtime flags above.

#### Q122. [Coding] Instrument a workload for Prometheus with a ServiceMonitor/PodMonitor and explain the scrape/relabel pipeline.

**Problem**: expose custom application metrics and have the Prometheus Operator discover and scrape them automatically, with correct labels — without editing Prometheus config by hand.

```yaml
# 1) The app exposes /metrics; the Service names the port so the monitor can target it.
apiVersion: v1
kind: Service
metadata:
  name: web
  labels: { app: web }            # ServiceMonitor selects on THIS label
spec:
  selector: { app: web }
  ports:
    - { name: metrics, port: 9090, targetPort: 9090 }   # named port: 'metrics'
---
# 2) ServiceMonitor: a CRD that tells the Prometheus Operator what to scrape.
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: web
  labels: { release: kube-prometheus }   # MUST match Prometheus's serviceMonitorSelector
spec:
  selector: { matchLabels: { app: web } }
  namespaceSelector: { matchNames: [default] }
  endpoints:
    - port: metrics                       # by NAME, not number (resilient to port changes)
      interval: 30s
      path: /metrics
      relabelings:                         # shape target labels BEFORE scrape
        - { sourceLabels: [__meta_kubernetes_pod_node_name], targetLabel: node }
      metricRelabelings:                   # drop noisy series AFTER scrape, BEFORE storage
        - { sourceLabels: [__name__], regex: "go_gc_.*", action: drop }
```

The Prometheus Operator turns scrape configuration into **Kubernetes-native objects**. A `ServiceMonitor` (scrape via a Service's endpoints) or `PodMonitor` (scrape Pods directly, no Service needed) is a CRD the Operator watches; it generates the corresponding Prometheus scrape config and reloads Prometheus — so app teams declare "scrape me" with a CRD next to their Deployment instead of someone hand-editing a central `prometheus.yml`. The linkage is label-based and is the #1 source of "my metrics aren't showing up": the Prometheus CR has a `serviceMonitorSelector` (commonly `release: kube-prometheus`), and your ServiceMonitor's *own labels* must match it, *and* the ServiceMonitor's `selector` must match the Service's labels, *and* the `endpoints.port` name must match the Service's named port. Break any link in that chain and the target silently never appears.

The **relabeling pipeline** is where the real power and confusion live. `relabelings` run *before* the scrape and rewrite the target's labels — promoting Kubernetes metadata (`__meta_kubernetes_pod_node_name`, namespace, pod) into stable labels, or `action: drop`ping whole targets. `metricRelabelings` run *after* scraping but *before* storage — used to drop high-cardinality or useless series (the `go_gc_*` example) to control Prometheus's memory and cost. Getting this right matters because **cardinality is the dominant cost driver**: a label with unbounded values (user ID, request ID) multiplies series count and can OOM Prometheus.

**Depth and pitfalls**: use `PodMonitor` when there's no Service (DaemonSets, headless workloads). For metrics that aren't request-counters but represent external state (queue depth), the proper exposition is a separate exporter, not stuffing it into the app's `/metrics`. RBAC: Prometheus needs cluster read on endpoints/pods/services for discovery. The modern alternatives — `ScrapeConfig` CRD for arbitrary targets, and OpenTelemetry Collector receiving OTLP and exporting to Prometheus — are increasingly used, but ServiceMonitor remains the default in the kube-prometheus-stack. Always verify with Prometheus's `/targets` page (or `up{job="web"}`) that the target is actually being scraped; a green-looking config that doesn't match the selector chain produces zero targets and no error.

#### Q123. [Coding] Detect privilege-escalation paths in RBAC and write the audit queries to find them.

**Problem**: in a security review you must find ServiceAccounts that can escalate to cluster-admin through indirect paths — not by being granted admin directly, but via permissions that *lead* to it. Build the checks.

```bash
# 1) Who can create/modify RBAC itself? (can grant themselves anything)
kubectl get clusterrolebindings,rolebindings -A -o json | jq -r '
  .items[] | select(.roleRef.name=="cluster-admin") |
  "\(.kind) \(.metadata.name) -> \(.subjects)"'

# 2) Can a given SA escalate? Use the built-in SubjectAccessReview via can-i:
kubectl auth can-i create clusterrolebindings \
  --as=system:serviceaccount:ci:deployer        # YES here = full escalation path
kubectl auth can-i '*' '*' --as=system:serviceaccount:ci:deployer
kubectl auth can-i --list --as=system:serviceaccount:ci:deployer   # full effective grant

# 3) Find the dangerous verbs anywhere they shouldn't be:
kubectl get clusterroles -o json | jq -r '
  .items[] | select(
    [.rules[]? | select(
       (.resources[]? | IN("clusterroles","clusterrolebindings","roles","rolebindings","*"))
       and (.verbs[]? | IN("create","update","patch","bind","escalate","*")))] | length > 0
  ) | .metadata.name'
```

RBAC is **deny-by-default and purely additive** (Q10/Q73) — there are no deny rules — so escalation is never "someone granted admin." It's a *path*: a permission that lets a subject acquire more permission. The classic paths an auditor hunts for: (1) **create/patch on `roles`/`clusterroles`/`*bindings`** — a subject who can write RBAC can bind itself to `cluster-admin`. Kubernetes mitigates this with two special verbs: `escalate` (you can't create a Role with rules exceeding your own *unless* you hold `escalate`) and `bind` (you can't bind to a Role more powerful than yours unless you hold `bind`) — so granting plain `create` on `rolebindings` is far safer than `create` + `bind`. (2) **`create pods`** in a namespace with a powerful ServiceAccount — you launch a Pod that *mounts that SA's token* and inherit its rights (the "Pod as a privilege-escalation primitive"). (3) **`get/list secrets`** cluster-wide — every SA token and credential is a Secret, so this is read-everything. (4) **`impersonate`** users/groups — directly assume another identity. (5) **`escalate`/`bind` themselves**, and `approve` on CSRs (mint a client cert as any user).

The authoritative tool is the API server's own authorizer via `SubjectAccessReview`, surfaced as `kubectl auth can-i ... --as=`. This is *ground truth* — it evaluates the exact same code path a real request hits, including aggregation of all bindings and `ClusterRole` aggregation labels — so it beats trying to mentally union YAML. `--as=system:serviceaccount:<ns>:<name>` impersonates the SA (you need impersonation rights to run it, itself a privileged op). `--list` dumps the SA's entire effective permission set.

**Depth and remediation**: audit for the dangerous verb×resource combinations (the jq above), then verify suspected paths with `can-i`. Real-world escalation chains are subtle: `create pods` + a namespace that has a `default` SA bound to something strong; or `patch deployments` letting you change a Deployment to mount a privileged SA; or access to a node's kubelet (`nodes/proxy`) to read any Pod's secrets. Remediations: least-privilege Roles (never `*` verbs/resources), `automountServiceAccountToken: false` by default, drop unused SA tokens, restrict `secrets`/`pods/exec`/`impersonate`/`escalate`/`bind` to a tiny set, and enforce with admission policy (Q107) that blocks risky bindings. Tools like `kubectl-who-can`, `rbac-tool`, and `KubiScan` automate the graph traversal, but the `can-i`/SubjectAccessReview check is what you trust for a definitive answer.

#### Q124. [Coding] Reproduce and fix intermittent in-cluster DNS latency (the `ndots:5` and conntrack-race problems).

**Problem**: an app sees occasional 5-second stalls on outbound calls and rare connection failures, only sometimes, only under load. Metrics on the target service are clean. Prove it's DNS and fix it without changing app code.

```bash
# Reproduce/observe: time repeated lookups of an EXTERNAL name from inside a Pod.
kubectl run dnstest --rm -it --image=nicolaka/netshoot -- \
  sh -c 'for i in $(seq 20); do time getent hosts api.stripe.com >/dev/null; done'

# Inspect what the resolver is actually doing — the smoking gun is ndots.
kubectl exec dnstest -- cat /etc/resolv.conf
#   search default.svc.cluster.local svc.cluster.local cluster.local
#   options ndots:5
```

```yaml
# Fix A: lower ndots for external-heavy workloads so FQDNs skip the search list.
spec:
  dnsConfig:
    options:
      - { name: ndots, value: "1" }     # 'api.stripe.com' (2 dots < 1? no) -> still searches;
                                         # use a trailing dot 'api.stripe.com.' to force absolute
  dnsPolicy: ClusterFirst
---
# Fix B (cluster-wide, the real fix for the conntrack race): NodeLocal DNSCache
#   deploy node-local-dns DaemonSet; pods query a node-local cache over TCP,
#   eliminating the cross-node UDP conntrack race entirely.
```

Two distinct, famous problems hide here. **(1) `ndots:5`**: Kubernetes sets `options ndots:5` in every Pod's `resolv.conf`, meaning any name with *fewer than 5 dots* is first tried against the **search domains** before being tried as absolute. So `api.stripe.com` (2 dots) is looked up as `api.stripe.com.default.svc.cluster.local`, then `...svc.cluster.local`, then `...cluster.local`, all returning NXDOMAIN, *before* the real query — turning one external lookup into 4+ round-trips. Under load against a busy CoreDNS, those wasted queries add latency and amplify load. Fixes: lower `ndots` per-Pod via `dnsConfig` (great for external-heavy services, but breaks short in-cluster names like `web` → use FQDNs internally), or append a **trailing dot** (`api.stripe.com.`) to force an absolute lookup that skips the search list entirely.

**(2) The 5-second stall** is the classic Linux DNS **conntrack race**: glibc sends the A and AAAA queries in parallel over the *same* UDP socket (same source port), and under load two concurrent UDP DNAT insertions for that 5-tuple can race in the kernel's conntrack table, dropping one packet. The resolver then waits for its **5-second timeout** before retrying — hence the oddly precise 5s stalls that correlate with load, not with the target service. This is a kernel/UDP-NAT issue, not a CoreDNS bug, which is why target metrics look clean. The robust fix is **NodeLocal DNSCache**: a per-node DNS cache that Pods reach over a link-local address, serving cache hits locally and forwarding misses to CoreDNS *over TCP* — TCP isn't subject to the same UDP conntrack race, and most queries never leave the node. Lesser mitigations: `single-request-reopen`/`single-request` resolv.conf options (separate the A/AAAA queries), or disabling IPv6 lookups if you don't use them.

**Depth**: the diagnostic discipline is to (a) reproduce from inside a Pod with `getent`/`dig +stats`, (b) read `resolv.conf` to confirm `ndots`, (c) check CoreDNS metrics (`coredns_dns_request_duration_seconds`, cache hit ratio) and its logs for SERVFAIL/throttling, and (d) correlate stall timing with load to fingerprint the conntrack race vs the ndots amplification. CoreDNS should run HA with the `cache`, `autopath` (which sidesteps the ndots search-list cost server-side), and adequate replicas/HPA. DNS is a data-plane dependency *everything* uses (Q40), so a DNS slowdown masquerades as a total application slowdown — which is exactly why it's a high-value debugging skill.

#### Q125. [Coding] Build a JSON 6902 patch and a strategic-merge patch, and explain when each is required.

**Problem**: you need to (a) remove a single env var from a container by position, (b) add an item to a list without replacing it, and (c) change a field only if it currently has a specific value. Strategic-merge can't do all of these — show both patch types and their boundaries.

```bash
# Strategic-merge patch: K8s-schema-aware; merges by patch-merge-key (containers by name).
kubectl patch deploy web --type=strategic -p '{
  "spec":{"template":{"spec":{"containers":[
    {"name":"app","image":"web:2.0"}        # merges into the container named "app"
  ]}}}}'

# JSON Merge Patch (RFC 7386): NOT schema-aware; a null DELETES, lists REPLACE wholesale.
kubectl patch deploy web --type=merge -p '{
  "spec":{"template":{"metadata":{"annotations":{"old-anno":null}}}}}'  # null removes key

# JSON Patch (RFC 6902): explicit ordered ops by JSON path — the only one with
# add/remove/replace/test and positional list edits.
kubectl patch deploy web --type=json -p '[
  {"op":"remove","path":"/spec/template/spec/containers/0/env/2"},        # delete env[2]
  {"op":"add",   "path":"/spec/template/spec/containers/0/env/-","value":{"name":"NEW","value":"x"}},
  {"op":"test",  "path":"/spec/replicas","value":3},                       # guard: only if ==3
  {"op":"replace","path":"/spec/replicas","value":5}
]'
```

Kubernetes supports three patch semantics and choosing wrong is the bug. **Strategic-merge** (`--type=strategic`, the default for `kubectl patch`/`edit`) is *schema-aware*: it knows from the API type's struct tags that `containers` is a list merged by `name` (`patchMergeKey`), so patching one container by name updates just that container and leaves the rest. This is what you want 90% of the time, but it only works on built-in types that declare merge strategies — it does **not** work on CRDs (no Go struct tags available to kubectl), where it silently degrades to merge-patch behavior on lists (replace, not merge).

**JSON Merge Patch** (`--type=merge`, RFC 7386) is simple and schema-agnostic: recursively overlay the patch, `null` deletes a key, and **any list in the patch replaces the whole list**. That last point is the trap — `{"containers":[{...}]}` as a merge patch *replaces every container* with the one you listed, wiping sidecars. Use merge-patch for scalar/map fields (annotations, labels, a single replicas value) and for CRDs where strategic isn't available, but never for partial list edits.

**JSON Patch** (`--type=json`, RFC 6902) is an ordered list of explicit operations (`add`, `remove`, `replace`, `move`, `copy`, `test`) addressing fields by JSON Pointer path, including array indices (`/env/2`) and the append token (`/env/-`). It's the only type that can remove a list element by position, insert into a list, or do a conditional **`test`** (the op fails — aborting the whole patch atomically — if the value doesn't match, giving you optimistic-concurrency-style guards without resourceVersion). The cost is fragility: positional paths break if the list reorders, and there's no schema awareness, so you must know the exact structure.

**Decision rule and depth**: strategic-merge for normal partial updates to built-ins; merge-patch for simple scalar/map changes and CRDs; JSON 6902 when you need positional list edits, deletes-by-index, or `test` guards. For declarative, multi-writer scenarios, prefer **Server-Side Apply** (Q118) over imperative patches entirely — it handles field ownership and conflicts that all three patch types ignore. Patches are what controllers and GitOps tools emit under the hood (Argo uses strategic/merge; many operators use JSON patch for surgical status updates), so understanding the semantics is essential for reading *why* a tool clobbered or preserved a field.

#### Q126. [Coding] Run a chaos/failure drill that kills a node mid-request and prove the workload survives, then capture the gaps.

**Problem**: leadership wants evidence the platform tolerates a node loss without user-visible impact. Design a repeatable drill that injects a node failure under live traffic and measures the blast radius, then turn findings into fixes.

```bash
# 0) Establish steady-state SLO baseline under load.
kubectl run loadgen --rm -i --restart=Never --image=williamyeh/hey -- \
  hey -z 120s -c 40 "http://app.example.com/" > /tmp/baseline.txt &

# 1) Pick a node hosting target Pods and HARD-fail it (simulate crash, not graceful drain).
NODE=$(kubectl get pods -l app=app -o jsonpath='{.items[0].spec.nodeName}')
#    graceful (planned) path: cordon + drain (tests PDB/graceful term, Q42)
kubectl cordon "$NODE" && kubectl drain "$NODE" --ignore-daemonsets --delete-emptydir-data
#    OR ungraceful (real crash) path: stop the kubelet / power off the VM via cloud API
#    e.g. aws ec2 stop-instances --instance-ids <id>   (tests the NotReady -> eviction timeline)

# 2) Observe the recovery timeline.
kubectl get pods -l app=app -o wide -w          # watch reschedule onto surviving nodes
kubectl get events --sort-by=.lastTimestamp | tail -30
```

```yaml
# The config that MAKES it survive (the drill validates these, doesn't replace them):
#  - replicas >= 3 spread across nodes/zones (topologySpreadConstraints, Q24)
#  - PodDisruptionBudget minAvailable so a DRAIN can't drop quorum (Q25/Q120)
#  - readiness probes + preStop drain so traffic stops before SIGTERM (Q112)
#  - resource requests sized so survivors + a new node can absorb the shifted load
```

The drill must distinguish two failure modes because they exercise different machinery. A **graceful drain** (`cordon` + `drain`) tests the *voluntary disruption* path: the eviction API respects the PodDisruptionBudget (refusing to evict if it would breach `minAvailable`), Pods get `SIGTERM` + `preStop` + grace period (Q42/Q112), and replacements come up before capacity drops — this validates your upgrade/maintenance safety. An **ungraceful crash** (kill the kubelet / power off the VM) tests the *involuntary* path and is the harsher, more honest test: the node goes `NotReady`, but Kubernetes does **not** reschedule its Pods immediately — there's a deliberate delay (`node-monitor-grace-period` ~40s before NotReady, then the taint-based eviction `tolerationSeconds`, default 300s, before Pods are deleted and rescheduled, Q63). That ~5-minute window is the gap most teams don't know about: a crashed node's Pods linger as "Running" in the API while serving nothing, and only after the eviction timeout do replacements get scheduled.

What you measure: error rate and latency from the load generator across the event (did any requests fail? for how long?), time-to-reschedule, and whether the PDB held. The instructive findings are usually: (a) traffic to the dead node's Pods kept being routed for seconds because endpoint removal lagged (fix: lower probe periods, ensure the dataplane deprograms fast); (b) the involuntary 5-minute eviction window meant a 2-replica service ran at half capacity far longer than expected (fix: ≥3 replicas, and for fast involuntary recovery, tune `tolerationSeconds` lower with care); (c) survivors got overloaded because requests weren't sized for N-1 capacity (fix: capacity headroom / HPA). 

**Depth and discipline**: run this in staging first, then as a *scheduled game day* in production with a tight blast radius and an abort plan — chaos engineering is hypothesis-driven (state the expected SLO impact, then test it), not random breakage. Tools like Chaos Mesh, LitmusChaos, or AWS FIS automate node/pod/network/IO fault injection repeatably and integrate the SLO check as the experiment's success criterion. The cultural payoff matches the technical one: a passing drill is *evidence* for stakeholders (ties directly to the blast-radius reasoning in Q95), and a failing drill surfaces the exact config gaps — PDB, spread, probes, headroom, eviction timing — before a real node failure does it for you at 3am.

## ✅ Key Takeaways

- Kubernetes is a **declarative, level-triggered reconciliation engine**: you state desired state, controllers converge actual state by watching the API server — no component commands another directly.
- The control plane is **api-server + etcd (the only stateful piece) + scheduler + controller-manager**; nodes run **kubelet + kube-proxy + a container runtime**.
- Workload hierarchy: **Deployment → ReplicaSet → Pod** for stateless; **StatefulSet** for stable identity + per-Pod storage; **Job/CronJob** for batch.
- **Requests/limits drive scheduling and QoS**; memory over-limit = OOMKilled (incompressible), CPU over-limit = throttled (compressible). Avoid CPU limits on latency-sensitive apps.
- **Probes are distinct tools**: startup (protect slow boots), readiness (gate traffic), liveness (restart on deadlock — never check downstream deps).
- Autoscaling is three layers: **HPA (Pod count) + VPA (Pod size) + Cluster Autoscaler/Karpenter (node count)**; KEDA for event-driven scale-to-zero.
- Security is **defense-in-depth**: RBAC least privilege, Pod Security Admission + Kyverno/OPA, default-deny NetworkPolicies, etcd encryption, signed images, sandboxed runtimes for hostile tenancy.
- **Gateway API** is the role-oriented, portable successor to Ingress and where new routing features land.
- **Operators + CRDs** encode operational knowledge for stateful systems — powerful but only worth the complexity for genuinely stateful workloads.
- Match complexity to need: **sometimes the right answer is not Kubernetes.**

## ⚠️ Common Pitfalls

- **Liveness probe checking a downstream dependency** → a dependency blip restarts your whole fleet (cascading outage). Liveness checks only the local process.
- **No resource requests** → BestEffort QoS, first to be evicted under pressure, and the scheduler can't bin-pack safely.
- **CPU limits set too tight** → silent throttling and tail-latency spikes even with idle node CPU.
- **`latest` image tags** → non-reproducible deploys; a Pod reschedule silently pulls a different image. Pin tags or digests.
- **Secrets as base64 mistaken for encryption** → enable etcd encryption-at-rest; prefer mounted volumes over env vars; use an external secret store.
- **PDB equal to replica count (or on a single replica)** → makes nodes undrainable and blocks upgrades.
- **ResourceQuota without a matching LimitRange** → every Pod without explicit requests/limits gets rejected.
- **NetworkPolicy with a non-enforcing CNI** (e.g., plain flannel) → policies silently do nothing; verify your CNI enforces them.
- **`kubectl logs` without `--previous`** on a crash-looping Pod → you read the *new* instance and miss the crash cause.
- **Immediate volume binding across zones** → Pods stuck Pending because the PV landed in a zone with no schedulable node; use `WaitForFirstConsumer`.
- **Treating namespaces as a security boundary** → they share the kernel; hostile multi-tenancy needs sandboxing or separate clusters.
- **Unbounded LIST/WATCH from a custom controller** → API-server/etcd overload that degrades the entire cluster.

## 📚 Further Reading

- *Kubernetes Up & Running*, 3rd ed. — Burns, Beda, Hightower, Evenson (O'Reilly) — pragmatic foundations.
- *Kubernetes Patterns*, 2nd ed. — Ibryam & Huß (O'Reilly) — reusable design patterns (sidecar, operator, init, etc.).
- *Programming Kubernetes* — Hausenblas & Schimanski (O'Reilly) — controllers, CRDs, and writing operators with client-go/controller-runtime.
- Official documentation — [kubernetes.io/docs](https://kubernetes.io/docs/) — the authoritative, version-tracked reference.
- Gateway API docs — [gateway-api.sigs.k8s.io](https://gateway-api.sigs.k8s.io/) — the modern ingress/routing standard.
- *Production Kubernetes* — Rosso, Lander, Brand, Harris (O'Reilly) — operating clusters at scale, platform engineering, and reliability.
