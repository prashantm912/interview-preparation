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
