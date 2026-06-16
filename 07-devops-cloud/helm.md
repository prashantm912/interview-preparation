# Helm — The Kubernetes Package Manager

Helm packages Kubernetes manifests into versioned, parameterizable units called **charts**, letting you install, upgrade, roll back, and share complex applications as a single release. Knowledge here is current through 2026 (Helm 3.x, OCI-native registries).

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

### Q1. [Theory] What is Helm and what problem does it solve?
Helm is the de-facto package manager for Kubernetes. Raw Kubernetes deployments require maintaining many YAML manifests (Deployment, Service, ConfigMap, Ingress, HPA, etc.), and those manifests are full of duplicated, environment-specific values. Helm solves three problems: **templating** (one chart, many environments via `values.yaml`), **packaging/distribution** (a versioned, shareable artifact), and **lifecycle management** (atomic install/upgrade/rollback with revision history). Without Helm you typically end up hand-editing manifests per environment or writing bespoke scripting; Helm gives you a reproducible, auditable release process instead.

### Q2. [Theory] Describe the standard chart directory structure.
A chart is a directory whose layout Helm understands by convention:

```
mychart/
├── Chart.yaml          # name, version, appVersion, dependencies
├── values.yaml         # default configuration values
├── values.schema.json  # (optional) JSON Schema to validate values
├── charts/             # vendored subchart dependencies
├── crds/               # CRDs installed before templates, never templated
├── templates/
│   ├── deployment.yaml
│   ├── service.yaml
│   ├── _helpers.tpl    # named template partials (no manifest output)
│   ├── NOTES.txt       # post-install usage message
│   └── tests/          # `helm test` pods (helm.sh/hook: test)
└── README.md
```

`Chart.yaml` carries two distinct versions: `version` (the chart's own SemVer) and `appVersion` (the version of the app it ships, e.g. `nginx 1.27`). Files prefixed with `_` (like `_helpers.tpl`) are not rendered into manifests.

### Q3. [Theory] What is the difference between `version` and `appVersion` in `Chart.yaml`?
`version` is the chart package version and **must** follow SemVer 2; bumping it is what `helm package` and repositories track. `appVersion` is informational metadata describing the upstream application version the chart deploys. You can ship chart `1.4.2` that deploys app `appVersion: "2.3.0"`. They are decoupled deliberately: fixing a templating bug bumps `version` but not `appVersion`; upgrading the container image bumps `appVersion` (and usually `version` too).

### Q4. [Practical] How do you install a chart and override values?
You override defaults three ways, in increasing precedence: a values file, repeated `--set`, and inline `--set` last-wins.

```bash
# From a repo, with a custom values file
helm install web ./mychart -n prod --create-namespace \
  -f values-prod.yaml \
  --set image.tag=1.27.3 \
  --set replicaCount=4

# Preview the rendered manifests without touching the cluster
helm template web ./mychart -f values-prod.yaml | less

# Dry-run against the API server (validates against the live cluster)
helm install web ./mychart --dry-run=server
```

In production prefer committed `-f values-<env>.yaml` files over ad-hoc `--set`, because `--set` is invisible to GitOps and hard to audit. Use `helm template` for offline rendering and `--dry-run=server` to catch admission-webhook/CRD errors before applying.

### Q5. [Theory] What is a "release" and where does Helm store its state?
A **release** is an installed instance of a chart with a specific name in a specific namespace; you can install the same chart many times under different release names. Helm 3 stores release state as a Kubernetes **Secret** (type `helm.sh/release.v1`) in the release's namespace by default — one secret per revision, base64+gzip encoded. This is a key Helm 2 → 3 difference: Helm 2 used a cluster-wide Tiller server and ConfigMaps; Helm 3 is client-only, RBAC follows your kubeconfig, and there is no privileged in-cluster component.

---

## 🟡 Intermediate (3–7 yrs)

### Q6. [Theory] Explain Go templating, the `.` context, and the built-in objects.
Helm templates are Go `text/template` plus the Sprig function library and a few Helm-specific functions. The root context `.` exposes built-in objects: `.Values` (merged values), `.Release` (`.Name`, `.Namespace`, `.IsUpgrade`, `.Revision`), `.Chart` (from `Chart.yaml`), `.Capabilities` (cluster/API versions), `.Files` (access to non-template files), and `.Template`. Pipelines transform data left-to-right, and whitespace chomping (`{{-` / `-}}`) controls newline output.

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ include "mychart.fullname" . }}
  labels:
    {{- include "mychart.labels" . | nindent 4 }}
spec:
  replicas: {{ .Values.replicaCount | default 1 }}
  template:
    spec:
      containers:
        - name: {{ .Chart.Name }}
          image: "{{ .Values.image.repository }}:{{ .Values.image.tag | default .Chart.AppVersion }}"
          {{- with .Values.resources }}
          resources:
            {{- toYaml . | nindent 12 }}
          {{- end }}
```

Key gotcha: inside `range`/`with` the `.` rebinds, so reach the root with `$` (e.g. `$.Release.Name`). Prefer `include` over `template` because `include` returns a string you can pipe through `nindent`/`toYaml`; `template` is an action with no return value.

### Q7. [Practical] How do you handle dependencies and subcharts?
Declare dependencies in `Chart.yaml`, then vendor them. Subchart values are namespaced under the subchart name; the parent can override them and expose **global** values to all subcharts.

```yaml
# Chart.yaml
dependencies:
  - name: postgresql
    version: "15.x.x"          # SemVer range
    repository: "oci://registry-1.docker.io/bitnamicharts"
    condition: postgresql.enabled   # toggle on/off
    alias: db                        # rename for multi-instance
    tags:
      - database
```

```bash
helm dependency update ./mychart   # resolves, writes charts/ + Chart.lock
helm dependency build ./mychart    # installs exactly what Chart.lock pins
```

```yaml
# parent values.yaml
postgresql:            # overrides the subchart's own values
  auth:
    database: orders
global:                # readable by parent AND every subchart
  imageRegistry: my.registry.io
```

In production commit `Chart.lock` so builds are reproducible, use `condition`/`tags` to make heavyweight deps optional, and avoid using a real database subchart for prod stateful data — point at a managed service instead.

### Q8. [Theory] What are Helm hooks and what are the ordering/cleanup semantics?
Hooks let you run resources at defined points in the release lifecycle by annotating them with `helm.sh/hook`. Common hooks: `pre-install`, `post-install`, `pre-upgrade`, `post-upgrade`, `pre-delete`, `post-delete`, and `test`. Within a phase, `helm.sh/hook-weight` (ascending integer) orders execution. Critically, **hook resources are not tracked as part of the release** — Helm will not delete them on uninstall unless you set a `hook-delete-policy`.

```yaml
apiVersion: batch/v1
kind: Job
metadata:
  name: {{ include "mychart.fullname" . }}-migrate
  annotations:
    "helm.sh/hook": pre-upgrade,pre-install
    "helm.sh/hook-weight": "-5"
    "helm.sh/hook-delete-policy": before-hook-creation,hook-succeeded
spec:
  template:
    spec:
      restartPolicy: Never
      containers:
        - name: migrate
          image: "{{ .Values.image.repository }}:{{ .Values.image.tag }}"
          command: ["/app/migrate", "up"]
```

`before-hook-creation` (the default) deletes a prior hook of the same name before creating a new one, avoiding immutable-Job conflicts on repeated upgrades. Hooks that fail block the release by default — use them for DB migrations, but be aware they break atomicity (a failed hook can leave the cluster half-migrated).

### Q9. [Practical] Walk through `helm upgrade` and `helm rollback`. How do revisions work?
Each successful `install`/`upgrade` creates a new **revision** stored in its release secret. `helm rollback` is itself an upgrade that re-applies a previous revision's manifests, producing yet another revision (it does not delete history).

```bash
helm upgrade web ./mychart -f values-prod.yaml --atomic --timeout 5m
helm history web                 # see all revisions + status
helm rollback web 3              # restore revision 3 (creates revision N+1)
helm get values web --revision 3 # inspect what was set
helm status web
```

`--atomic` rolls back automatically if the upgrade fails (and implies `--wait`); `--cleanup-on-fail` removes resources created during a failed upgrade. A subtle production trap: rollback restores the **chart manifests** but does *not* revert side effects from hooks (a forward DB migration won't be undone by rolling back the chart), so design migrations to be backward-compatible.

### Q10. [Coding] Write a reusable `_helpers.tpl` named template for standard labels and a safe name.
**Problem:** Kubernetes object names must be ≤63 chars and DNS-1123 compliant, and every object should carry the recommended `app.kubernetes.io/*` labels. Hardcoding this in every manifest is error-prone.

```yaml
{{/* templates/_helpers.tpl */}}

{{/* Expand the chart name, allowing override via .Values.nameOverride */}}
{{- define "mychart.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/* Fully qualified app name, truncated to satisfy the 63-char limit */}}
{{- define "mychart.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}

{{/* Selector labels: immutable, used in matchLabels (must NOT change) */}}
{{- define "mychart.selectorLabels" -}}
app.kubernetes.io/name: {{ include "mychart.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{/* Full label set: selector labels + version/managed-by metadata */}}
{{- define "mychart.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{ include "mychart.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}
```

**Why this matters:** `selectorLabels` are kept separate because a Deployment's `spec.selector.matchLabels` is **immutable** — if you fold the version into the selector, every `appVersion` bump breaks the upgrade. **Complexity:** rendering is O(objects × labels), negligible. **Edge cases:** `trunc 63 | trimSuffix "-"` handles long release names and avoids a trailing hyphen that would fail DNS-1123 validation; `replace "+" "_"` keeps SemVer build metadata label-safe.

### Q11. [Practical] How do you manage secrets with Helm?
Plain values in `values.yaml` are stored unencrypted in the release secret and (worse) often committed to Git. Production options, by maturity:
1. **External secret operators** (preferred): use the **External Secrets Operator** or **Secrets Store CSI Driver** to pull from AWS Secrets Manager / Vault / GCP Secret Manager at runtime; the chart only references the secret name. Rotation is handled out-of-band.
2. **`helm-secrets` plugin + SOPS** to encrypt values files with KMS/age and decrypt during `helm install`. Encrypted files are safe to commit.
3. **Sealed Secrets** (Bitnami) for GitOps: encrypt with the cluster's public key; only the in-cluster controller can decrypt.

Avoid `--set password=...` (it lands in shell history and the release secret) and never bake secrets into the image. Restrict RBAC on the namespace's secrets, since anyone who can read Helm release secrets can read the rendered values.

### Q12. [Theory] What are library charts and when do you use them?
A **library chart** (`type: library` in `Chart.yaml`) ships only reusable named templates — no installable resources — and cannot be deployed on its own. You add it as a dependency and `include` its templates from your application charts. This is the right tool when many internal charts share boilerplate (standard labels, a common deployment skeleton, security-context defaults): define it once, version it, and every consuming chart pulls the same logic. It is the chart-level equivalent of a shared code library and prevents copy-paste drift across dozens of microservice charts.

---

## 🟠 Advanced (8–12 yrs)

### Q13. [Theory] Compare Helm vs Kustomize. When would you choose each, or both?
Both turn a base into environment variants, but the mechanism differs fundamentally.

```
 HELM (templating)                  KUSTOMIZE (overlay/patch)
 ┌─────────────┐                    ┌──────────────┐
 │ template +  │ values.yaml        │  base/       │ plain valid YAML
 │ Go logic    │ ──────────────►    │   ▼ patch    │
 │ {{ .Val }}  │  rendered YAML     │ overlays/    │  patched YAML
 └─────────────┘                    └──────────────┘
 packaging + lifecycle + rollback   no packaging, no release state
```

Helm gives **packaging, distribution (repos), conditionals/loops, and stateful release lifecycle** (history, rollback, hooks). Kustomize is **template-free** — bases are real, valid Kubernetes YAML patched by strategic merges — so it's easier to read and reason about, and it's built into `kubectl apply -k`. Choose Helm for redistributable third-party apps and when you need rollback/hooks; choose Kustomize for in-house manifests you fully control and want kept lint-able. A common production pattern is **both**: `helm template | kustomize` (or Helm's `post-renderer` flag) — render the upstream chart, then apply targeted Kustomize patches you can't express through the chart's values. Argo CD and Flux support all three modes.

### Q14. [Practical] A `helm upgrade` is stuck in `pending-upgrade` and won't proceed. How do you diagnose and recover?
This usually means a previous `helm` process was killed (CI timeout, lost connection) before it could finalize, leaving the release in a transient state.

**Diagnose:** `helm history web` shows the last revision as `pending-upgrade`/`pending-install`. `helm status web` confirms. Check whether the actual workloads in the cluster updated or not (`kubectl get deploy,rs -n prod`).

**Recover (least to most invasive):**
1. If the underlying resources are actually healthy, `helm rollback web <last-good-revision>` often clears it.
2. Use `helm upgrade ... --history-max` hygiene and the `mapkubeapis` plugin if the deprecated-API was the cause.
3. As a last resort, delete the stuck release secret for that revision:
   ```bash
   kubectl get secret -n prod -l owner=helm,name=web
   kubectl delete secret -n prod sh.helm.release.v1.web.v8   # the pending one
   ```
   then re-run the upgrade. Deleting the *latest* secret reverts Helm's view to the prior revision without touching live workloads.

**Prevent:** always run upgrades with `--atomic --timeout`, set generous CI timeouts, and use `--wait` so partial states don't get committed. Avoid two concurrent pipelines targeting the same release.

### Q15. [Theory] How do OCI registries change chart distribution versus classic HTTP repos?
Classic Helm repos are a static `index.yaml` plus `.tgz` files served over HTTP (e.g. via `helm repo add`). Since Helm 3.8, OCI support is GA: charts are pushed as **OCI artifacts** into any OCI-compliant registry (ECR, GAR, ACR, Harbor, GHCR), reusing the same auth, RBAC, replication, and vulnerability-scanning infrastructure you already use for container images.

```bash
helm registry login registry-1.docker.io
helm package ./mychart                       # → mychart-1.4.2.tgz
helm push mychart-1.4.2.tgz oci://my.registry.io/charts
helm install web oci://my.registry.io/charts/mychart --version 1.4.2
```

There is no `index.yaml` to maintain and no separate ChartMuseum to run; discovery is by tag. Trade-off: OCI has weaker server-side *search/listing* than `helm search repo`, and tooling differs by registry. With OCI you can also **sign** charts (cosign) and enforce verification in admission control, closing a supply-chain gap.

### Q16. [Coding] Write a template that loops over a map of environment ConfigMaps and renders them conditionally.
**Problem:** You need to generate one `ConfigMap` per entry in a `values.yaml` map, only when feature-flagged, with proper indentation and a stable name.

```yaml
# values.yaml
configMaps:
  app-settings:
    enabled: true
    data:
      LOG_LEVEL: info
      FEATURE_X: "true"
  cache-tuning:
    enabled: false
    data:
      TTL_SECONDS: "300"
```

```yaml
# templates/configmaps.yaml
{{- range $name, $cfg := .Values.configMaps }}
{{- if $cfg.enabled }}
---
apiVersion: v1
kind: ConfigMap
metadata:
  name: {{ printf "%s-%s" (include "mychart.fullname" $) $name | trunc 63 | trimSuffix "-" }}
  labels:
    {{- include "mychart.labels" $ | nindent 4 }}
data:
  {{- range $key, $val := $cfg.data }}
  {{ $key }}: {{ $val | quote }}
  {{- end }}
{{- end }}
{{- end }}
```

**Approaches:** the brute-force alternative is one static `configmaps.yaml` per environment (duplication, drift); the optimal solution above is data-driven so adding a config means adding a values entry, not a template. **Key details:** `range $name, $cfg := ...` rebinds `.`, so the root is reached with `$` (e.g. `include "mychart.labels" $`); the leading `---` separates documents in a single rendered file. **Time/Space:** O(maps × keys) render time, output proportional to data size. **Edge cases:** numeric/boolean values are forced to strings with `quote` (ConfigMap data must be strings); disabled entries render nothing, not an empty document.

### Q17. [Practical] How do you safely roll out chart changes across many clusters/teams, and prevent drift?
The production pattern is GitOps-driven Helm: Argo CD or Flux watches a Git repo, and the desired state (chart version + values) is declarative. This gives you PR-based review, automatic drift detection/correction, and per-environment promotion. Layer in:
- **`values.schema.json`** so bad values fail `helm install`/CI before reaching a cluster.
- **`helm lint`** + `helm template | kubeconform`/`kyverno` policy checks in CI.
- **`helm diff upgrade`** (plugin) in the PR to show the exact manifest delta a merge will produce.
- **Pinned chart versions** (never `latest`) and a signed OCI registry.
- **Progressive delivery** (Argo Rollouts / Flagger) for canary or blue-green on top of the Helm-managed Deployment.

For multi-tenancy, a **library chart** enforces org-wide defaults (security contexts, resource limits) so 200 microservice charts can't individually opt out of policy.

### Q18. [Theory] How does Helm decide what to change on upgrade, and what is the "3-way merge"?
On upgrade Helm performs a **three-way strategic merge** between (1) the *old rendered manifest* (last applied by Helm, stored in the release secret), (2) the *new rendered manifest*, and (3) the *live state* in the cluster. The old manifest is the base so Helm knows which fields *it* previously owned. This means fields a chart never set are left alone, but it also creates the classic gotcha: if someone `kubectl edit`s a field Helm doesn't manage, Helm leaves it — but if you then *remove* a field from the chart that Helm previously set, Helm will delete it from the live object. Helm 3 added live-state into the merge (Helm 2 only did a two-way merge), which dramatically reduced "Helm clobbered my manual change" surprises but did not eliminate them — `helm.sh/resource-policy: keep` is still needed to stop Helm from deleting resources like PVCs on uninstall.

---

## 🔴 Expert (15+ yrs)

### Q19. [Theory] What are the failure modes of Helm hooks at scale, and how do you design around them?
Hooks are powerful but sit outside Helm's atomicity and three-way merge, which makes them the most common source of production incidents. Failure modes: (1) a `pre-upgrade` migration Job succeeds but the subsequent rollout fails — `helm rollback` restores manifests but **not** the forward migration, leaving schema ahead of code; (2) hook Jobs accumulate because no `hook-delete-policy` is set, eventually hitting namespace quotas; (3) immutable-Job name collisions on retry; (4) a long-running hook that holds up the whole release because hooks block by default. Design around them by making all migrations **backward- and forward-compatible** (expand/contract pattern), setting `hook-delete-policy: before-hook-creation,hook-succeeded`, giving migration Jobs explicit timeouts and `backoffLimit`, and — for genuinely critical ordering — moving migration orchestration out of Helm hooks into a dedicated operator or an Argo CD sync-wave/PreSync hook that has richer ordering and health semantics.

### Q20. [Practical] You inherited a 4,000-line "god chart" deploying 30 microservices via one umbrella chart. It takes 20 minutes to upgrade and one bad subchart fails the whole release. How do you refactor? (Industry case study)
This is a real anti-pattern many platform teams hit (Bitnami, Adobe, and others have written about it). The umbrella chart couples unrelated lifecycles: a one-line change to service A re-renders and re-reconciles all 30, and any single failing manifest aborts the atomic upgrade.

**Approach:**
1. **Extract a library chart** for the shared label/template/security boilerplate so each service stops duplicating it.
2. **Split the umbrella into per-service charts**, each independently versioned and deployable, so blast radius is one service.
3. **Move orchestration to GitOps** (Argo CD ApplicationSet, or Flux Kustomization-of-HelmReleases) where each service is its own `HelmRelease`/`Application`. Now upgrades are parallel and isolated, and a failing service doesn't block the other 29.
4. **Keep truly shared infra** (cert-manager, ingress) in a separate platform chart with its own cadence.
5. **Pin versions and add `helm diff` gates** so promotion is reviewable.

**Trade-off:** more charts to manage and a slightly higher cognitive surface, but you gain independent rollback, parallel deploys, and a 20-minute monolith becomes ~30 independent 1-minute reconciles. I'd stage the migration service-by-service behind feature flags rather than a big-bang rewrite, validating each extraction with `helm diff` against the live god-chart output to prove no manifest regressions.

### Q21. [Theory] What are the supply-chain and RBAC security considerations for Helm in a regulated environment?
Helm's threat surface spans authoring, distribution, and execution. Authoring: a chart's templates execute arbitrary Sprig logic and can request any RBAC the installer has — `values.schema.json`, `helm lint`, and policy admission (Kyverno/OPA Gatekeeper) on the *rendered* output are mandatory controls. Distribution: serve charts from a private **OCI registry with cosign signatures** and verify signatures in admission control; pin exact versions and dependency digests in `Chart.lock` to defeat tag-mutation attacks. Execution: Helm 3 runs with the *caller's* kubeconfig and RBAC (no privileged Tiller), so scope service accounts tightly per namespace/CI pipeline — a CI runner that can install charts cluster-wide is effectively cluster-admin. Secrets: the release secret holds fully-rendered values, so anyone with `get secret` in the namespace can read injected credentials; combine least-privilege RBAC with external secret managers so the rendered values reference, not embed, sensitive data. Finally, scan chart images with the same SBOM/CVE tooling as your registry, since `appVersion` bumps pull new images.

### Q22. [Behavioral] Your team is split between standardizing on Helm and moving to Kustomize. How do you drive the decision?
I'd frame it as a problem-fit decision, not a tooling-preference fight. First I'd surface the real requirements: do we redistribute charts to other teams/customers (favors Helm), do we need rollback/hooks/release history (Helm), or do we mostly own flat in-house manifests and value readability and `kubectl`-native flows (Kustomize)? I'd run a time-boxed spike converting one representative service both ways and have the on-call engineers — not just the loudest voice — review which is easier to debug at 3 a.m. I'd also weigh the ecosystem: most third-party software ships as Helm charts, so a pure-Kustomize shop still ends up running `helm template`. My recommendation in most orgs is the pragmatic hybrid — Helm for packaged/third-party and lifecycle needs, Kustomize overlays (or Helm post-renderers) for last-mile customization — and I'd document the decision in an ADR so the rationale outlives the debate. The behavioral key is making the team feel heard while anchoring the final call to requirements and a small evidence-based experiment rather than seniority.

### Q23. [Practical] How do you keep Helm release history from bloating etcd, and manage deprecated-API upgrades across Kubernetes versions?
Each revision is a Secret in etcd; long-lived releases with frequent CI upgrades can accumulate hundreds, pressuring etcd and slowing `helm list`. Cap history with `helm upgrade --history-max 10` (or set it cluster-wide in your CD tooling) and periodically prune superseded revisions. For Kubernetes version upgrades, charts may reference APIs removed in the target version (e.g. old `networking.k8s.io/v1beta1` Ingress, `policy/v1beta1` PSP) — but the *stored* old manifest in the release secret still contains the dead API, so a plain upgrade fails with "no matches for kind." The fix is the official **`helm mapkubeapis`** plugin, which rewrites the stored release metadata to the new API versions before you upgrade Kubernetes. Gate this with `.Capabilities.APIVersions.Has` in templates so the chart self-adjusts to the cluster's available APIs, and test upgrades against the target version in CI using a kind/k3d cluster pinned to that version.

---

## ✅ Key Takeaways
- Helm = templating + packaging + lifecycle (install/upgrade/rollback/history); Helm 3 is client-only with release state stored as namespaced Secrets.
- Prefer committed `values-<env>.yaml` over ad-hoc `--set`; use `helm template` and `--dry-run=server` to validate before applying.
- Keep `selectorLabels` separate and immutable; centralize boilerplate in a **library chart** to prevent drift.
- Use `--atomic --timeout` for safe upgrades; design hooks and migrations to be backward-compatible because rollback does not undo hook side effects.
- Distribute via **OCI registries** with cosign signatures and pinned versions; never use `latest`.
- Manage secrets with External Secrets Operator / SOPS / Sealed Secrets — not plaintext values committed to Git.
- Helm vs Kustomize is a fit decision; the hybrid `helm template` + post-renderer/Kustomize is a legitimate production pattern.

## ⚠️ Common Pitfalls
- Putting a mutable/version label into `spec.selector.matchLabels`, breaking every Deployment upgrade.
- Forgetting `$` inside `range`/`with`, so `.Release`/`.Values` resolve to the wrong context.
- Hooks with no `hook-delete-policy`, leaking Jobs until quotas are hit; or relying on rollback to undo a forward DB migration.
- Committing secrets to `values.yaml` (they end up in Git *and* the release secret in etcd).
- One giant umbrella "god chart" — couples unrelated lifecycles and makes a single failure abort everything.
- Using `latest`/unpinned dependency versions, making builds non-reproducible; not committing `Chart.lock`.
- Upgrading Kubernetes without running `helm mapkubeapis`, hitting "no matches for kind" on removed APIs.
- Letting release history grow unbounded and pressuring etcd (set `--history-max`).

## 📚 Further Reading
- Helm official documentation — Charts, Template Guide, Best Practices (helm.sh/docs).
- Kubernetes Up & Running, 3rd ed. — Hightower, Burns, Beda (chapters on Helm & packaging).
- "Managing Kubernetes Resources Using Helm," 2nd ed. — Andrew Block & Austin Dewey (Packt).
- Helm Chart Best Practices Guide (helm.sh/docs/chart_best_practices).
- Argo CD / Flux documentation on Helm integration and GitOps promotion.
- CNCF blog & SLSA/cosign docs on chart signing and supply-chain security for OCI artifacts.
