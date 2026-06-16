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

## 🧩 Extended Questions — Set 1: Deeper Theory & Practical Operations

### 🟢 Basic — extended

#### Q24. [Practical] What does `helm template` do, and how is it different from `helm install --dry-run`?
`helm template` renders the chart's manifests **entirely on the client** and prints them to stdout. It never contacts the API server, so it works offline, in air-gapped CI, and before a cluster even exists. Because there is no cluster, `.Capabilities` is populated from built-in defaults (not the live cluster's API versions), `.Release.IsUpgrade`/`.Release.IsInstall` reflect a synthetic install, and lookups via the `lookup` function return empty.

`helm install --dry-run` (or `--dry-run=server`) goes further: it sends the rendered manifests to the API server for validation. `--dry-run=client` does template rendering plus local checks; `--dry-run=server` additionally runs them through admission webhooks, CRD validation, and server-side schema checks — catching errors a pure client render cannot.

```bash
helm template web ./mychart -f values-prod.yaml          # offline, no cluster
helm install web ./mychart --dry-run=client              # render + local validate
helm install web ./mychart --dry-run=server              # render + API/webhook validate
```

The practical rule: use `helm template` for diffing and GitOps rendering, and `--dry-run=server` as the last gate before a real apply because it is the only one that exercises validating webhooks (OPA/Kyverno, resource quotas, mutating defaults) the way a real install will.

#### Q42. [Practical] What is the difference between `helm install` and `helm upgrade --install` (upsert), and why do CD pipelines prefer the latter?
`helm install web ./mychart` fails if a release named `web` already exists ("cannot re-use a name that is still in use"); `helm upgrade web ./mychart` fails if it does *not* exist. `helm upgrade --install web ./mychart` (often written `helm upgrade -i`) is the **idempotent upsert**: it installs if absent, upgrades if present.

```bash
# Idempotent in a pipeline — works on first deploy and every subsequent one:
helm upgrade --install web ./mychart -n prod --create-namespace \
  -f values-prod.yaml --atomic --timeout 5m
```

CD pipelines prefer it because a deploy job must be re-runnable without branching on "is this the first deploy or the hundredth?" A plain `helm install` in CI breaks the second time it runs; a plain `helm upgrade` breaks the first time. The upsert form removes that statefulness and pairs naturally with `--create-namespace`, `--atomic`, and `--wait` so the whole deploy step is one declarative command. The only caveat: `-i` masks "release already exists" situations, so if you genuinely want a clean first install to fail loudly (to avoid clobbering an unexpected pre-existing release), keep them separate.

#### Q25. [Theory] What is the difference between `helm uninstall` and `helm uninstall --keep-history`, and what survives an uninstall?
`helm uninstall web` deletes all resources Helm tracks as part of the release **and** removes the release history secrets, so `helm history web` returns "release: not found." `--keep-history` deletes the workloads but **keeps the release record** in a `uninstalled` state, which lets you `helm rollback` to bring it back later.

What does *not* automatically get deleted: resources annotated with `helm.sh/resource-policy: keep` (commonly PVCs you don't want to lose), hook resources that lack a `hook-delete-policy`, and anything created out-of-band (e.g. a PVC dynamically provisioned by a StatefulSet that Helm didn't directly own). This is why deleting a database release rarely deletes its data — the PVC survives by design.

```yaml
metadata:
  annotations:
    "helm.sh/resource-policy": keep   # Helm will NOT delete this on uninstall
```

The interview point: uninstall is about Helm's *tracked* state, not "everything the app ever created." In production you should know exactly which resources are `keep`-annotated, because orphaned PVCs and leaked hook Jobs are a common cost and quota surprise after a "clean" uninstall.

#### Q49. [Practical] What do `helm get values`, `helm get manifest`, and `helm get all` show, and how do you use them in an incident?
These commands read directly from the stored release secret, so they tell you the ground truth of what Helm believes — independent of your local chart files, which may have drifted from what's deployed.

```bash
helm get values web                 # only user-supplied overrides
helm get values web --all           # the FULL merged values tree (computed defaults too)
helm get values web --revision 7    # what was set at a specific revision
helm get manifest web               # the exact rendered YAML Helm applied
helm get hooks web                  # the hook resources for the release
helm get notes web                  # the rendered NOTES.txt
helm get all web                    # everything above in one dump
```

In an incident, the high-value moves are: `helm get values web --all` to see the *effective* configuration (someone may have forgotten an override, or a default changed between chart versions), and `helm get manifest web | kubectl diff -f -` to see whether the live cluster has drifted from what Helm last applied. `--revision N` lets you compare what changed between the last-good and the failed revision (`diff <(helm get values web --revision 6) <(helm get values web --revision 7)`). Because these come from the release secret and not your working tree, they're trustworthy even when you're not sure the local chart matches what's running — which is exactly the situation during a 3 a.m. page on a service someone else deployed.

#### Q26. [Practical] How do you debug a chart that renders invalid YAML or fails to install?
Work outward from the cheapest, most local check to the most cluster-dependent one. The single most useful flag is `--debug`, which echoes the computed values and surfaces template errors with file/line context.

```bash
helm lint ./mychart                          # static checks: Chart.yaml, schema, obvious errors
helm template web ./mychart --debug          # see rendered output + errors with line numbers
helm template web ./mychart | kubeconform -  # validate rendered YAML against k8s schemas
helm install web ./mychart --dry-run=server --debug   # catch webhook/CRD errors
helm get manifest web                        # what Helm actually applied for a live release
```

Common culprits and their tells: indentation off by N spaces usually means a missing or wrong `nindent`; a value rendering as `<no value>` means a typo'd path or a missing default; "error converting YAML to JSON" almost always points at an un-`quote`d string that YAML reinterprets (e.g. a numeric-looking version, `on`/`off`/`yes` booleans, or a leading zero). For live releases, `helm get manifest` and `helm get values --all` tell you exactly what was applied versus what you think you set.

### 🟡 Intermediate — extended

#### Q27. [Theory] Explain values precedence in Helm. If the same key is set in several places, which one wins?
Helm merges values from multiple sources into a single tree, and the order is strict last-wins. From lowest to highest precedence:

```
subchart's own values.yaml        (lowest)
   └─► parent chart values.yaml
        └─► values from -f / --values  (in the order given, later files win)
             └─► --set-file
                  └─► --set / --set-string / --set-json  (highest)
```

A few subtleties trip people up. Multiple `-f` files are merged left-to-right, so `-f base.yaml -f prod.yaml` lets `prod.yaml` override `base.yaml`. Maps are deep-merged, but **arrays are replaced wholesale, not merged** — setting `args: [--flag]` via `--set` does not append to a default `args` list, it replaces it. And a parent chart's value for a subchart (`postgresql.auth.database`) overrides that subchart's own default.

```bash
# prod.yaml wins over base.yaml; --set wins over both
helm upgrade web ./mychart -f base.yaml -f prod.yaml --set replicaCount=5
```

The production takeaway: prefer a clear, layered `-f` strategy (one base, one per environment) and reserve `--set` for one-off CI injection like an image tag, because `--set` is invisible to GitOps and easy to forget.

#### Q28. [Practical] How do you express conditional logic for optional Kubernetes resources — e.g. only create an Ingress or HPA when enabled?
Wrap the entire manifest in an `if` guarded by a values flag, and gate API-version-specific fields with `.Capabilities`. The whole file should render to nothing when disabled so it doesn't emit an empty document.

```yaml
{{- if .Values.ingress.enabled -}}
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: {{ include "mychart.fullname" . }}
  {{- with .Values.ingress.annotations }}
  annotations:
    {{- toYaml . | nindent 4 }}
  {{- end }}
spec:
  {{- if .Values.ingress.className }}
  ingressClassName: {{ .Values.ingress.className }}
  {{- end }}
  rules:
    {{- range .Values.ingress.hosts }}
    - host: {{ .host | quote }}
      http:
        paths:
          {{- range .paths }}
          - path: {{ .path }}
            pathType: {{ .pathType | default "Prefix" }}
            backend:
              service:
                name: {{ include "mychart.fullname" $ }}
                port:
                  number: {{ $.Values.service.port }}
          {{- end }}
    {{- end }}
{{- end }}
```

The key patterns: the `{{- if ... -}}` at the very top (with chomping on both sides) ensures a disabled resource produces zero output, not a stray `---`. Inside nested `range`, the root context is reached with `$` (so `$.Values.service.port`, not `.Values...`). For HPA you'd do the same with `{{- if .Values.autoscaling.enabled }}` and additionally gate the API version with `.Capabilities.APIVersions.Has "autoscaling/v2"` so the chart works across cluster versions.

#### Q29. [Theory] What is the `lookup` function, why is it discouraged, and when is it legitimately useful?
`lookup` queries the **live cluster** during rendering — `lookup "v1" "Secret" "default" "my-secret"` returns the existing object (or empty). It makes templates non-deterministic: the same chart + same values renders differently depending on cluster state, which breaks `helm template`/GitOps diffing and makes installs non-reproducible. During `helm template` and any dry-run, `lookup` returns an empty value, so logic that depends on it silently behaves differently offline versus online.

```yaml
{{- $existing := lookup "v1" "Secret" .Release.Namespace (printf "%s-credentials" .Release.Name) }}
{{- if $existing }}
# reuse the existing password so we don't rotate it every upgrade
password: {{ index $existing.data "password" }}
{{- else }}
password: {{ randAlphaNum 32 | b64enc }}
{{- end }}
```

The legitimate use case above — preserving an auto-generated password across upgrades so it isn't regenerated each time — is exactly why `lookup` exists, and most "stable random secret" patterns rely on it. But it's a code smell at scale: the better answer is an External Secrets Operator or a pre-created Secret referenced by name, so the value lives outside the render path entirely and rendering stays deterministic.

#### Q30. [Practical] How do you test a Helm chart? Cover `helm test`, unit testing, and CI integration.
Helm has three complementary layers. **`helm lint`** is static analysis of structure and `values.schema.json`. **`helm test`** runs Pods/Jobs annotated `helm.sh/hook: test` *against a live release* — typically smoke tests like "can the service answer /healthz."

```yaml
# templates/tests/connection-test.yaml
apiVersion: v1
kind: Pod
metadata:
  name: "{{ include "mychart.fullname" . }}-test"
  annotations:
    "helm.sh/hook": test
    "helm.sh/hook-delete-policy": hook-succeeded
spec:
  restartPolicy: Never
  containers:
    - name: curl
      image: curlimages/curl:8.10.1
      command: ["curl", "--fail", "http://{{ include "mychart.fullname" . }}:{{ .Values.service.port }}/healthz"]
```

```bash
helm install web ./mychart -n test --wait
helm test web -n test --logs        # runs the test pods, streams logs
```

For **unit testing** the template logic itself (without a cluster), the `helm-unittest` plugin asserts on rendered output, which is fast and runs in CI on every PR:

```yaml
# tests/deployment_test.yaml (helm-unittest)
suite: deployment
templates: [deployment.yaml]
tests:
  - it: sets replicas from values
    set: { replicaCount: 3 }
    asserts:
      - equal: { path: spec.replicas, value: 3 }
```

A solid CI pipeline runs `helm lint` → `helm-unittest` → `helm template | kubeconform` (or Kyverno policy) on every PR, then `helm install --wait` + `helm test` against an ephemeral kind/k3d cluster before promotion. The unit tests catch logic regressions cheaply; `helm test` catches "it renders but doesn't actually work" failures.

#### Q43. [Practical] What is a checksum/config annotation pattern, and why is it needed to roll pods when a ConfigMap or Secret changes?
By default, changing a ConfigMap or Secret that a Deployment mounts does **not** restart the pods — Kubernetes sees no change to the Pod template, so no new rollout happens, and pods keep using the stale config (or pick it up only on the next unrelated restart). The standard Helm fix is to inject a checksum of the config content into the Pod template annotations, so the template *does* change whenever the config changes, forcing a rolling update.

```yaml
spec:
  template:
    metadata:
      annotations:
        checksum/config: {{ include (print $.Template.BasePath "/configmap.yaml") . | sha256sum }}
        checksum/secret: {{ include (print $.Template.BasePath "/secret.yaml") . | sha256sum }}
    spec:
      containers:
        - name: app
          envFrom:
            - configMapRef: { name: {{ include "mychart.fullname" . }}-config }
```

`include (print $.Template.BasePath "/configmap.yaml") .` renders the sibling template, and `sha256sum` hashes it; any change to the rendered ConfigMap changes the hash, changes the annotation, and triggers a rollout. This is more reliable than hashing only `.Values` because it captures the *fully rendered* result (including helper logic and defaults). The trade-off: every config change now forces a pod restart — usually what you want, but be aware it couples config rollouts to workload restarts, so for hot-reloadable apps you might deliberately omit it. For Secrets, prefer the External Secrets Operator with its own reloader, or pair this pattern with a tool like Reloader for non-Helm-managed updates.

#### Q50. [Theory] Explain `tpl`, `toYaml`, `nindent`, and `default` — the four functions most responsible for correct (or broken) chart output.
These four functions appear in almost every real chart, and misusing them is the source of most "renders but is wrong" bugs. **`toYaml`** serializes an arbitrary values subtree to YAML — essential for passing through blocks like `resources`, `nodeSelector`, or `affinity` without re-templating each field. **`nindent N`** prepends a newline and indents every line by N spaces; you pair it with `toYaml` because the serialized block must be indented to the correct depth in the surrounding manifest. **`default`** supplies a fallback when a value is empty. **`tpl`** renders a string *as a template* against a context — letting users put template expressions inside their values.

```yaml
spec:
  replicas: {{ .Values.replicaCount | default 1 }}
  template:
    spec:
      {{- with .Values.nodeSelector }}
      nodeSelector:
        {{- toYaml . | nindent 8 }}      # serialize map, indent under nodeSelector
      {{- end }}
      containers:
        - name: app
          # let users template a value, e.g. host: "{{ .Release.Name }}.svc"
          args: ["--host={{ tpl .Values.hostTemplate . }}"]
```

The classic bugs: using `indent` instead of `nindent` (no leading newline → the first line collides with the key on the same line, producing invalid YAML); choosing the wrong N in `nindent` (off-by-two indentation breaks the document); forgetting `default`, so an unset numeric like `replicas:` renders empty and the API rejects it; and using `tpl` on untrusted user input, which is effectively code injection into your render. The mental model: `toYaml` + `nindent` is the idiom for "pass this whole block through," `default` guards optional scalars, and `tpl` is powerful but should be reserved for values you control, because it executes whatever template syntax the value contains.

#### Q31. [Theory] What do `required` and `fail` do, and how do they compare to `values.schema.json` for input validation?
`required "message" .Values.x` aborts rendering with your message if the value is empty or missing; `fail "message"` unconditionally aborts (used inside conditional branches you consider illegal). They give imperative, context-rich validation at render time.

```yaml
image:
  repository: {{ required "image.repository is required" .Values.image.repository }}
{{- if and .Values.ingress.enabled (not .Values.ingress.className) }}
{{- fail "ingress.enabled=true requires ingress.className" }}
{{- end }}
```

`values.schema.json` is declarative JSON Schema applied to the *input values* before templates render — it checks types, enums, ranges, `required` properties, and `additionalProperties: false` to reject typo'd keys outright.

| Mechanism | When it runs | Best for |
|-----------|--------------|----------|
| `values.schema.json` | before render, on the values tree | type/shape/enum/required-key validation; rejecting unknown keys |
| `required` | during render, per use site | "this specific field must be set" with a targeted message |
| `fail` | during render, in a branch | rejecting illegal *combinations* of values |

Use the schema as the broad guardrail (it also powers editor autocompletion and catches typos that `required` can't, because a typo'd key just leaves the real key empty), and reserve `required`/`fail` for cross-field rules JSON Schema can't easily express. The schema fails the *whole* install fast and uniformly; `required` only fires when a template actually references the field.

### 🟠 Advanced — extended

#### Q32. [Theory] How does `helm diff upgrade` work, and why is it considered essential for production GitOps?
`helm diff upgrade` (a plugin) renders the *new* chart+values, fetches the *current* live/stored manifests, and prints a unified diff of exactly what the upgrade would add, change, or delete — without touching the cluster. It is the Helm analogue of `terraform plan`: it converts "trust me, this upgrade is fine" into a reviewable artifact.

```bash
helm diff upgrade web ./mychart -f values-prod.yaml --detailed-exitcode
# exit 0 = no changes, 2 = changes present  → gate CI on this
```

It matters in GitOps for three reasons. First, **blast-radius review**: a one-line values change can, through a shared `_helpers.tpl` or a bumped subchart, ripple into dozens of objects — the diff makes that visible in the PR. Second, **catching destructive deletes**: removing a key the chart previously set will *delete* that field/resource on upgrade (per the three-way merge), and the diff shows the `-` lines before they hit prod. Third, **drift awareness**: it surfaces fields that were changed out-of-band and will be reverted.

The production pattern is to run `helm diff upgrade --detailed-exitcode` in the PR pipeline and post the diff as a comment, so a human approves the actual manifest delta — not just the chart version bump — before merge.

#### Q33. [Practical] You upgraded a chart and Helm reports success, but the new pods never came up and traffic is broken. Helm didn't roll back. What happened and how do you prevent it?
The almost-certain cause is that the upgrade ran **without `--wait`/`--atomic`**. By default `helm upgrade` returns success as soon as the API server *accepts* the manifests — it does not wait for the Deployment to become Available. So a bad image tag, failing readiness probe, or insufficient resources produces "successful" Helm output while the new ReplicaSet sits in `CrashLoopBackOff` or `ImagePullBackOff` and the old pods get scaled down by the rollout.

```bash
# What you should have run:
helm upgrade web ./mychart -f values-prod.yaml \
  --atomic --timeout 5m            # --atomic implies --wait and auto-rolls-back on failure

# Diagnose the current mess:
kubectl rollout status deploy/web -n prod
kubectl get rs,pods -n prod
kubectl describe pod <failing-pod> -n prod   # events: ImagePull / probe / OOM
helm history web
```

`--wait` makes Helm block until pods/PVCs/Services report ready (up to `--timeout`); `--atomic` adds automatic rollback on timeout. To recover now: `helm rollback web <last-good>` (and confirm the rollout actually reverts). To prevent recurrence: make `--atomic --timeout` mandatory in your CD tooling, ensure readiness/liveness probes are accurate (so "ready" means ready), and gate the rollout with `progressDeadlineSeconds` plus a canary (Argo Rollouts/Flagger) so a bad version is caught before full cutover.

#### Q34. [Theory] Explain `--wait` versus `--wait-for-jobs`, and the readiness semantics Helm actually checks.
`--wait` makes Helm block after applying manifests until the resources it created report ready, then return. "Ready" is type-specific: for a Deployment/StatefulSet/DaemonSet it waits for the desired number of replicas to be updated and available (and the rollout to complete), for a Pod it waits for `Ready`, for a PVC it waits for `Bound`, and for a Service of type LoadBalancer it waits for an ingress address. Crucially, plain `--wait` does **not** wait for Jobs to complete — it only waits for the Job object to exist.

`--wait-for-jobs` extends `--wait` so Helm also blocks until any Jobs in the release reach completion. This matters when a chart includes a non-hook Job (e.g. a one-shot seeding Job) whose completion the rest of the system depends on.

```bash
helm upgrade web ./mychart --wait --wait-for-jobs --timeout 10m
```

The trade-off is real: `--wait` is what turns "Helm said success" into "the workload is actually healthy," but it makes every upgrade as slow as the slowest resource and as flaky as your readiness probes. If a probe is wrong, `--wait` will time out (and `--atomic` will roll back a deployment that was actually fine). So accurate probes are a precondition for relying on `--wait`, and you should size `--timeout` to your real rollout time plus image-pull headroom.

#### Q35. [Practical] How do you migrate an existing, hand-rolled (`kubectl`-managed) application into Helm management without downtime?
The goal is to make Helm "adopt" already-running resources so the first `helm upgrade` is a no-op diff rather than a delete-and-recreate. Helm 3 will adopt an existing object if its rendered manifest matches an in-cluster object of the same name/namespace/kind and that object carries the right ownership metadata.

```bash
# 1) Build a chart whose `helm template` output matches the LIVE manifests as closely as possible.
helm template web ./mychart | kubectl diff -f -    # iterate until the diff is empty/trivial

# 2) Label/annotate the existing objects so Helm 3 will adopt rather than reject them:
kubectl label   deploy/web app.kubernetes.io/managed-by=Helm --overwrite
kubectl annotate deploy/web \
  meta.helm.sh/release-name=web \
  meta.helm.sh/release-namespace=prod --overwrite
#   (repeat for every object the chart will manage)

# 3) Install with adoption; Helm takes ownership without recreating:
helm install web ./mychart -n prod --take-ownership   # or upgrade --install on newer Helm
```

The risk is that any field mismatch between your chart's render and the live object becomes a *change* on the first upgrade — and for immutable fields (a Deployment's `spec.selector`, a Service's `clusterIP`, a Job's template) a mismatch forces a destructive replace. So the discipline is: get `helm template | kubectl diff` down to nothing first, do it on one non-critical service, verify with `helm diff upgrade` showing zero changes, and only then roll the pattern out. Tools like `helm-adopt`/`helmify` can bootstrap the chart from live manifests to shorten step 1.

#### Q51. [Practical] How do you implement a blue-green or canary rollout for a Helm-managed Deployment, and what are the limits of doing it in pure Helm?
You can do a *crude* blue-green in pure Helm by templating two Deployments (`-blue`/`-green`) and a Service whose selector points at the active color via a values flag — flipping `activeColor` and upgrading swaps traffic atomically.

```yaml
# service.yaml — selector points at whichever color is active
spec:
  selector:
    app.kubernetes.io/name: {{ include "mychart.name" . }}
    color: {{ .Values.activeColor }}          # "blue" or "green"
{{- range $color := list "blue" "green" }}
# deployment-{{ $color }}.yaml: one Deployment per color, only the active one scaled up
{{- end }}
```

The limits are real: Helm has no notion of *progressive* traffic shifting, automated metric analysis, or automatic abort-on-error. A canary in pure Helm means manually templating two Deployments at different replica counts and eyeballing dashboards — there's no feedback loop. Helm's `--atomic` only checks that pods become *ready*, not that the new version is *healthy* under traffic (error rate, latency).

For real progressive delivery you layer a controller on top of the Helm-managed object: **Argo Rollouts** replaces `kind: Deployment` with `kind: Rollout` (Helm still packages it) and drives weighted canary steps with automated analysis against Prometheus, pausing or rolling back on SLO breach; **Flagger** watches a normal Deployment and orchestrates the canary via your service mesh/ingress. The division of labor: Helm packages and versions the manifests and handles install/upgrade/rollback of the *spec*, while Rollouts/Flagger owns the *traffic and health-gated promotion*. Trying to push canary logic entirely into chart templates is an anti-pattern — it's unobservable and has no automatic rollback on bad metrics.

#### Q36. [Theory] What are the trade-offs between an umbrella (parent-with-subcharts) chart and many independent charts orchestrated by GitOps?
An **umbrella chart** packages N components as subchart dependencies under one `Chart.yaml`, deployed as a single release. Its appeal is atomic, single-command deployment and one place to set shared/global values. Its cost is coupling: all components share one release lifecycle and one revision history, a single failing manifest aborts the whole atomic upgrade, every change re-renders and re-reconciles everything, and rollback is all-or-nothing.

```
 UMBRELLA (one release)                 GITOPS-ORCHESTRATED (N releases)
 ┌───────────────────────┐             ┌──────┐ ┌──────┐ ┌──────┐
 │ parent Chart.yaml      │             │ svcA │ │ svcB │ │ svcC │  each its own
 │  ├─ subchart A         │             │ Helm │ │ Helm │ │ Helm │  HelmRelease/App
 │  ├─ subchart B         │             └──────┘ └──────┘ └──────┘
 │  └─ subchart C         │   vs        independent version, rollback,
 │  ONE revision history  │             parallel reconcile, isolated blast radius
 └───────────────────────┘
```

| Dimension | Umbrella chart | Independent charts + GitOps |
|-----------|----------------|-----------------------------|
| Blast radius | whole release fails together | per-service |
| Rollback granularity | all components | per service |
| Deploy speed | serial, slowest-component-bound | parallel |
| Shared/global values | trivial | needs convention (shared values repo) |
| Version coupling | lockstep | independent |

Choose an umbrella when components genuinely share a lifecycle and must version in lockstep (e.g. a tightly-coupled app + its sidecar config), or for a quick "install the whole stack" demo. Choose independent charts + Argo ApplicationSet/Flux for a microservice platform where teams ship independently and you want isolated failure and rollback. The hybrid — small umbrellas per bounded context, orchestrated by GitOps — is often the sweet spot.

#### Q44. [Theory] How are CRDs handled by Helm, what is the `crds/` directory, and why are CRD upgrades a notorious pain point?
Helm has two ways to ship CustomResourceDefinitions, and they behave very differently. Files in the special **`crds/`** directory are plain YAML (never templated), installed **before** any templates on `install`, and Helm explicitly refuses to touch them on `upgrade`, `rollback`, or `uninstall` — there is no `--force` for them. The alternative is putting CRDs in `templates/` (often gated by `crds.install`), which makes them full lifecycle citizens but risks ordering and re-render issues.

```
mychart/
├── crds/                 # installed first, on INSTALL only; never upgraded/deleted by Helm
│   └── widgets.yaml
└── templates/
    └── widget.yaml       # a Custom Resource that depends on the CRD existing
```

The pain points: (1) **CRDs in `crds/` are never upgraded** — if a new chart version ships a CRD with new fields, an existing cluster keeps the old CRD, and your new Custom Resources using the new fields fail validation. You must `kubectl apply` the CRD update manually or out-of-band. (2) **CRDs are cluster-scoped and shared** — two releases that both bundle the same CRD will conflict, and uninstalling one shouldn't delete the CRD the other needs (deleting a CRD cascades and destroys *all* its CRs cluster-wide). (3) **Ordering**: a chart that installs both a CRD and a CR of that kind can race on first install.

The production guidance: keep CRDs in `crds/` for safety (Helm won't accidentally delete them), but treat **CRD lifecycle as a separate, deliberate operation** — manage them in their own release or via the operator's own installer, version them carefully, and never rely on `helm upgrade` to evolve a CRD schema. For operators, install the operator (which owns its CRDs) separately from the workloads that consume them.

### 🔴 Expert — extended

#### Q37. [Theory] Helm uses `Sprig`'s `randAlphaNum`/`genCA` etc. Why is generating secrets directly in templates an anti-pattern, and what is the idempotency problem?
Sprig exposes generators like `randAlphaNum`, `genPrivateKey`, and `genCA`, and it is tempting to write `password: {{ randAlphaNum 32 | b64enc }}`. The problem is that templates are **re-rendered on every upgrade**, so a naive generator produces a *new* value each time — every `helm upgrade` would rotate the password/cert, breaking running pods that mounted the old value and any clients holding it. The render is also non-reproducible, so `helm template` in CI and `helm diff` always show a phantom change.

The common "fix" is to wrap generation in a `lookup` so an existing value is reused (see Q29), but that reintroduces non-determinism: `helm template` (no cluster) and dry-run always take the *generate* branch, so offline rendering disagrees with online, and a first install on a fresh namespace still generates. It also means the secret's lifetime is silently coupled to the object existing in etcd — delete it once and everything depending on it rotates.

```yaml
# Anti-pattern: rotates on every upgrade
password: {{ randAlphaNum 32 | b64enc }}

# Better: generate ONCE, then own it externally
#   - External Secrets Operator pulls from Vault/ASM; chart references the Secret name
#   - or a bootstrap Job/`helm-secrets`+SOPS creates it; chart never generates
```

The expert answer: secret *material* should live outside the render path — External Secrets Operator, Sealed Secrets, or SOPS-encrypted values — so rendering stays deterministic and idempotent, rotation is an explicit operation, and `helm diff` tells the truth. In-template generation is acceptable only for genuinely ephemeral, regenerate-safe values, and even then `lookup`-based stability is a smell rather than a design.

#### Q38. [Practical] A production `helm upgrade` failed mid-way: some resources updated, some didn't, the release is `failed`, and `--atomic` was not used. Walk through full incident recovery.
First, stabilize before acting — understand the live state, because blind rollback can make a half-applied change worse.

```bash
helm history web                      # confirm: last revision = failed; identify last good (N)
helm status web
kubectl get deploy,rs,sts,svc -n prod # which objects actually changed?
kubectl get events -n prod --sort-by=.lastTimestamp | tail -40
helm get manifest web --revision <failed>   # what Helm tried to apply
```

Decide direction. If the *new* version is the desired end-state and the failure was transient (quota, image-pull, a flaky webhook), the cleanest path is often to **fix the root cause and re-run the upgrade** so Helm's three-way merge converges everything to the intended state:

```bash
helm upgrade web ./mychart -f values-prod.yaml --atomic --timeout 10m
```

If you need to get back to the known-good version, roll back to the last good revision (this creates a new revision, it doesn't erase history) and verify the rollout actually reverts:

```bash
helm rollback web <N> --wait --timeout 10m
kubectl rollout status deploy/web -n prod
```

Watch for the traps: a `failed` upgrade can leave the release such that the next upgrade complains; `--cleanup-on-fail` on the *retry* removes resources the failed attempt created. If a forward DB migration ran via a hook, rollback will **not** undo it (Q19) — so confirm schema/code compatibility before reverting code. If the release secret is wedged in `pending-*`, see Q14 (delete the latest pending secret to reset Helm's view without touching workloads). Post-incident: make `--atomic --timeout --cleanup-on-fail` mandatory in CD, add a `helm diff` gate, and ensure migrations follow expand/contract so any rollback is safe.

#### Q39. [Theory] How does `post-renderer` work, and when is it the right escape hatch versus forking a chart?
A **post-renderer** is an executable Helm pipes the fully-rendered manifests into (on stdin) right before applying; whatever the renderer prints to stdout is what Helm installs. It lets you mutate a third-party chart's output without forking it — the canonical use is wrapping the chart with Kustomize for patches the chart's `values.yaml` doesn't expose.

```bash
# kustomization.yaml uses the piped manifests as a resource via `helm template` flow,
# typically through a wrapper script:
helm upgrade web upstream/their-chart --post-renderer ./kustomize-wrapper.sh
```

```bash
#!/usr/bin/env sh
# kustomize-wrapper.sh — receives rendered YAML on stdin
cat > /tmp/all.yaml
# a local kustomization patches /tmp/all.yaml (add labels, sidecars, nodeSelectors…)
kustomize build ./post 2>/dev/null
```

Use a post-renderer when: you depend on an upstream chart you don't control, the change is small/structural (inject a sidecar, add org-wide labels, tweak a field with no values knob), and you want to keep pulling upstream updates cleanly. The trade-off is that the patch lives *outside* the chart, so it's invisible to anyone reading `values.yaml`, and a future upstream change can silently shift the manifest your patch targets (Kustomize strategic-merge is name/kind-anchored, so a renamed resource breaks the patch). Fork the chart instead when the changes are pervasive, you need to change templating logic (not just output), or you must diverge long-term — but a fork means you own merge conflicts forever, which is exactly the maintenance burden the post-renderer avoids for small deltas.

#### Q40. [Practical] How do you architect Helm for true multi-tenancy and multi-environment promotion at scale, and enforce org-wide policy?
The architecture has four layers, separating *what an app is* from *how it's configured per place* from *who can do what*. (1) **Chart authoring**: one versioned chart per service, all built on a shared **library chart** that bakes in non-negotiable defaults (security contexts, resource requests, `seccompProfile`, topology spread). Because the library chart provides the skeleton, no individual service can quietly drop a policy field. (2) **Configuration**: per-environment values files (`values-dev/staging/prod.yaml`) layered over a base, with `values.schema.json` rejecting unknown keys and out-of-range values *before* anything reaches a cluster.

(3) **Promotion**: GitOps with Argo CD `ApplicationSet` (or Flux) generating one Application per (service × environment), with promotion as a Git PR that bumps the pinned chart version from staging to prod. Pin exact chart versions and dependency digests in `Chart.lock`; serve charts from a private **OCI registry with cosign signatures**.

```
 OCI registry (signed charts)        Git repo (desired state)
        │                                   │
        ├── svc-a 1.4.2 ───────────►  values-prod.yaml (pins 1.4.2)
        └── library 2.1.0                   │
                                            ▼
                          Argo ApplicationSet → N Applications
                          ├─ tenant-a/prod   (RBAC-scoped Project)
                          └─ tenant-b/prod   (RBAC-scoped Project)
                                            │
                          Kyverno/OPA admission validates RENDERED output
```

(4) **Enforcement**: admission policy (Kyverno/OPA Gatekeeper) validates the *rendered* manifests at apply time, so even a chart that bypasses the library is caught at the cluster boundary; Argo CD Projects scope which repos/clusters/namespaces a tenant can target; and CI runs `helm lint` → `helm-unittest` → `helm template | kubeconform`/policy → `helm diff` on every PR. The defense-in-depth principle is that the library chart makes the right thing the *default*, the schema fails bad input *early*, and admission control is the *backstop* that doesn't trust the chart at all — three independent layers, because in a regulated multi-tenant environment no single control should be load-bearing.

#### Q52. [Behavioral] A senior engineer insists on keeping a 4,000-line god chart because "it works and rewriting it is risky." You believe it's causing incidents. How do you handle the disagreement?
I'd start by separating the valid part of their concern from the disagreement: they're right that a big-bang rewrite *is* risky, and acknowledging that openly lowers the temperature and signals I'm not dismissing their experience. The real question isn't "rewrite vs not" — it's whether the *current* design is causing measurable harm, so I'd bring data rather than opinion: incident postmortems where one subchart failure aborted the whole release, the 20-minute upgrade time, the times rollback was all-or-nothing. Facts about *our* system are harder to argue with than general best-practice appeals.

Then I'd reframe the migration as low-risk and incremental, because their fear is of the rewrite, not the outcome. I'd propose extracting *one* non-critical service into its own chart, gated behind `helm diff` proving zero manifest change against the god chart's output, fully reversible. That turns an abstract architecture debate into a concrete, bounded experiment we can evaluate together — and if the first extraction is painful or risky, that's real evidence to slow down, which respects their position.

I'd also invite them to define the success and safety criteria, so they have ownership of the guardrails rather than feeling something is being done *to* their system. If we still disagree after the spike, I'd escalate to an ADR documenting both positions and let the team/tech-lead decide with the evidence in front of them — disagree-and-commit either way. The behavioral core is: validate the legitimate risk concern, convert the argument into a small reversible experiment with shared success metrics, and anchor the decision to our own incident data instead of to seniority or to "best practices say so."

#### Q41. [Theory] Describe the Helm release-secret storage format in detail, its limits, and how you'd operate around the 1 MB Secret size cap.
Each Helm 3 revision is stored as a Kubernetes Secret named `sh.helm.release.v1.<release>.v<revision>`, type `helm.sh/release.v1`, labeled `owner=helm,name=<release>,version=<rev>,status=<status>`. The payload (the `release` key) is the release object — chart metadata, *all* rendered manifests, supplied values, and notes — serialized to JSON, then **gzip-compressed, then base64-encoded** (Helm 2 stored an analogous blob in a ConfigMap via Tiller).

```bash
kubectl get secret sh.helm.release.v1.web.v8 -n prod \
  -o jsonpath='{.data.release}' | base64 -d | base64 -d | gzip -d | jq .
# (double base64: the k8s Secret encodes the already-base64+gzip release blob)
```

The operational limit is etcd's per-object cap, surfaced as Kubernetes' ~1 MiB Secret size limit. A very large chart (hundreds of objects, big embedded files via `.Files`, fat CRDs) can render to a release blob that, even gzipped, exceeds 1 MiB — the upgrade then fails with "request entity too large" / "rpc error: ... exceeds the limit." Mitigations: stop embedding large blobs in templates (move big files out of `.Files`/ConfigMaps, reference external storage), **split the god-chart into smaller releases** (Q36) so each release's manifest set is smaller, prune history with `--history-max` so old revisions don't pile up, and for charts that ship CRDs put CRDs in `crds/` (installed once, not re-stored per revision). You can also switch the storage backend (`HELM_DRIVER`) — but `secret`/`configmap` both hit the same etcd cap, and SQL backends are rarely worth the operational cost. The cleaner architectural answer is almost always "the release is too big — decompose it," because a single release approaching 1 MiB is also one that's slow to render, diff, and reconcile.

#### Q45. [Theory] How does Argo CD (or Flux) actually use Helm, and why does "Helm-managed by Argo" behave differently from `helm install`?
This trips up many teams: Argo CD does **not** run `helm install`. By default it runs `helm template` to render manifests, then applies them itself and tracks them in its own application state — so there is **no Helm release secret**, `helm list` shows nothing, and `helm rollback`/`helm history` don't apply. Reconciliation, drift detection, and rollback are Argo's, not Helm's. Flux's `HelmController`, by contrast, *does* drive real `helm upgrade --install` under the hood (so it keeps release secrets and history), which is a meaningful behavioral difference between the two.

```
 Argo CD (default)                        Flux HelmRelease
 chart ──helm template──► manifests       chart ──helm upgrade --install──►
        Argo applies + tracks                    real Helm release secret
        NO helm release secret                   helm history works
        Argo handles rollback/drift              Helm hooks run
```

The consequences for Argo's templating mode: **Helm hooks don't run as Helm hooks** — Argo translates a subset into its own *sync waves/hooks* (`argocd.argoproj.io/hook`), so a chart that relies on `pre-install` Job ordering may behave differently or not at all. `lookup` returns empty (no live query during `helm template`). And anything that depended on `helm rollback` must instead use Argo's rollback to a previous Git revision. The expert point: when you say "we use Helm with GitOps," you must know *which* engine and *which* mode, because the lifecycle semantics (hooks, history, rollback, secret storage) you learned from CLI Helm may not hold. Pick Flux's HelmRelease when you specifically need real Helm release semantics; use Argo's templating mode when you want Git to be the single source of truth and Argo to own reconciliation.

#### Q46. [Practical] Your CI runs `helm dependency update` on every build and pulls subcharts over the network, causing flaky, slow, non-reproducible builds. How do you fix the dependency workflow?
The root issue is conflating `helm dependency update` (which *resolves* ranges and rewrites `Chart.lock` + downloads into `charts/`) with `helm dependency build` (which installs *exactly* what `Chart.lock` already pins). Running `update` in CI re-resolves SemVer ranges every build, so a new upstream patch silently changes your build, and a registry outage breaks it.

```yaml
# Chart.yaml — ranges are for the human running `update`, not for CI
dependencies:
  - name: postgresql
    version: "15.5.x"
    repository: "oci://registry-1.docker.io/bitnamicharts"
```

```bash
# Developer, intentionally, when bumping deps — commits the new lock:
helm dependency update ./mychart       # re-resolves ranges → updates Chart.lock

# CI / release — reproducible, pins to the committed lock:
helm dependency build ./mychart        # installs exactly Chart.lock's digests
```

The fixes, in order: (1) **commit `Chart.lock`** and use `helm dependency build` (not `update`) in CI so builds are reproducible and pinned to digests. (2) **Vendor subcharts** by committing the `charts/*.tgz` files (or mirror them into your private OCI registry) so builds don't reach the public internet at all — this also removes the supply-chain risk of pulling from upstream every build. (3) Pull from a **private OCI registry** mirror rather than Docker Hub to avoid rate limits and outages. (4) Bump dependencies as a deliberate, reviewed PR (run `update`, inspect the `Chart.lock` diff, run `helm diff`) rather than implicitly on every build. The principle mirrors `package-lock.json`/`go.sum`: resolve occasionally and deliberately, build from the lock always.

#### Q47. [Theory] Explain how Helm's `Capabilities` object enables a single chart to support multiple Kubernetes versions, and the gotchas with `helm template`.
`.Capabilities` exposes the target cluster's API surface to templates: `.Capabilities.KubeVersion` (and `.Major`/`.Minor`), `.Capabilities.APIVersions` (the set of available `group/version` strings), and `.Capabilities.APIVersions.Has "autoscaling/v2"`. This lets one chart emit the correct API version for a resource depending on the cluster it's installed into, so you don't need a separate chart per Kubernetes version.

```yaml
{{- if .Capabilities.APIVersions.Has "autoscaling/v2" }}
apiVersion: autoscaling/v2
{{- else }}
apiVersion: autoscaling/v2beta2
{{- end }}
kind: HorizontalPodAutoscaler
# ...
{{- if semverCompare ">=1.25-0" .Capabilities.KubeVersion.Version }}
# use a field only valid on 1.25+
{{- end }}
```

The major gotcha: during **`helm template` and dry-run there is no cluster**, so `.Capabilities` is filled from Helm's *built-in defaults*, not your real cluster. That means offline rendering can pick a different branch than a real install — a CI `helm template | kubeconform` might validate the `v2beta2` branch while production actually installs `v2`. You can pass `--kube-version` and `--api-versions` to `helm template` to simulate a specific target, and you *should* in CI to render the branch that will really apply.

```bash
helm template web ./mychart --kube-version 1.29 \
  --api-versions autoscaling/v2 | kubeconform -
```

The expert nuance: `.Capabilities.APIVersions.Has` is reliable for "is this API present," but it reflects what's *installed* (including CRDs) on a live cluster — so a chart gated on a CRD-provided API renders nothing offline. Combine `.Capabilities` gating with CI rendering at the exact target version, and test upgrades against a kind/k3d cluster pinned to that version, so the branch you ship is the branch you validated.

#### Q53. [Theory] Where do Helm plugins fit, and which production-critical ones should a serious Helm user know? What are the supply-chain implications?
Helm plugins are external executables Helm discovers under `$HELM_PLUGINS` and exposes as subcommands; they extend the CLI without changing Helm core. The ones that show up in real production workflows: **`helm-diff`** (preview the exact upgrade delta, the `terraform plan` of Helm — Q32); **`helm-secrets`** (SOPS-based encryption of values files so secrets are safe to commit — Q11); **`helm-unittest`** (cluster-free template unit tests in CI — Q30); **`mapkubeapis`** (rewrites stored release metadata across removed Kubernetes APIs so upgrades don't hit "no matches for kind" — Q23); and **`helm-git`**/**`helm-s3`/`helm-gcs`** for alternative chart sources/repos.

```bash
helm plugin install https://github.com/databus23/helm-diff
helm plugin install https://github.com/jkroepke/helm-secrets
helm plugin list                       # audit what's installed and from where
helm diff upgrade web ./mychart -f values-prod.yaml   # provided by the plugin
```

The supply-chain implication is the catch: a plugin is **arbitrary code that runs with your kubeconfig and local privileges** every time it's invoked — installing one from a random URL is equivalent to running an untrusted binary against your clusters. So treat plugins like any dependency: install only from vetted sources, **pin to a specific version/commit** rather than tracking `main`, vendor or mirror them internally for CI rather than `helm plugin install`-ing from the public internet on every run, and review `plugin.yaml` (which can declare hooks that execute on install). In a regulated environment, the plugin set should be part of your golden CI image and change-controlled, not something individual engineers add ad hoc — the same discipline you apply to charts and images applies to the tooling that deploys them.

#### Q48. [Practical] What are the most damaging Helm anti-patterns you watch for in code review, and how do you remediate each?
After enough incidents, a short list of recurring anti-patterns covers most of the damage:

| Anti-pattern | Why it hurts | Remediation |
|--------------|--------------|-------------|
| Mutable label in `selector.matchLabels` | selector is immutable → every `appVersion` bump fails the upgrade | keep `selectorLabels` separate from full labels (Q10) |
| Secrets in plaintext `values.yaml` | lands in Git *and* the release secret in etcd | External Secrets / SOPS / Sealed Secrets (Q11) |
| No `--atomic`/`--wait` in CD | "success" while pods crashloop, old pods gone | mandate `--atomic --timeout` (Q33) |
| Generating secrets with `randAlphaNum` | rotates every upgrade, breaks pods | generate once, own externally (Q37) |
| Hooks with no `hook-delete-policy` | Job leak until quota; relying on rollback to undo migration | `before-hook-creation,hook-succeeded`; expand/contract migrations (Q19) |
| Unpinned deps / `latest` images | non-reproducible builds, surprise upgrades | pin versions, commit `Chart.lock`, build (not update) in CI (Q46) |
| 4,000-line god chart | one failure aborts everything; slow; 1 MiB cap | decompose + library chart + GitOps orchestration (Q20, Q36) |
| `kubectl edit` on Helm-managed objects | three-way merge clobbers or fights manual edits | make all changes via chart+values; gate with `helm diff` (Q18, Q32) |

In review I treat these as blocking, not stylistic. The remediation theme is consistent: make the safe thing the default (library charts, schema, `--atomic` baked into CD), move volatile material out of the render path (secrets, generated values), and make every change reviewable as a manifest diff. The most expensive ones in practice are the silent ones — no `--wait` and in-template secret generation — because they "work" in the demo and only fail under real rollout or real upgrade frequency.

## 🧩 Extended Questions — Set 2: Coding, Design & Expert Scenarios

### 🟢 Basic — extended

#### Q54. [Coding] Write a `values.schema.json` that validates a chart's core inputs (replica count, image, optional ingress) and rejects unknown keys.
**Problem:** Catch bad input *before* templates render — wrong types, out-of-range values, and typo'd keys that would otherwise silently leave the real key at its default. JSON Schema is declarative, runs first, and also powers editor autocompletion.

```json
{
  "$schema": "https://json-schema.org/draft-07/schema#",
  "type": "object",
  "additionalProperties": false,
  "required": ["image"],
  "properties": {
    "replicaCount": { "type": "integer", "minimum": 1, "maximum": 50 },
    "image": {
      "type": "object",
      "additionalProperties": false,
      "required": ["repository"],
      "properties": {
        "repository": { "type": "string", "minLength": 1 },
        "tag":        { "type": "string" },
        "pullPolicy": { "type": "string", "enum": ["Always", "IfNotPresent", "Never"] }
      }
    },
    "ingress": {
      "type": "object",
      "additionalProperties": false,
      "properties": {
        "enabled":   { "type": "boolean" },
        "className": { "type": "string" }
      }
    }
  }
}
```

**Why each clause earns its place:** `additionalProperties: false` is the single most valuable line — it converts a typo like `replicaCnt: 5` from "silently ignored, runs with default 1" into a hard install failure. `enum` constrains `pullPolicy` to legal values so a typo'd `"ifnotpresent"` is rejected rather than passed to the API server. `minimum`/`maximum` on `replicaCount` stops a fat-fingered `replicaCount: 500` from exhausting a namespace quota.

**Edge cases and trade-offs:** the schema validates the *merged* values tree (defaults + overrides), so your own `values.yaml` defaults must also satisfy it or every install fails. Be deliberate with `additionalProperties: false` on objects that subcharts extend — a global passthrough block legitimately carries arbitrary keys, so scope the strictness to the parts you own. The schema can't express cross-field rules ("if ingress.enabled then className required") — those belong in `required`/`fail` at render time (see Q31).

#### Q55. [Coding] Write a minimal `Chart.yaml`, `values.yaml`, and `templates/deployment.yaml` for a chart that renders correctly with zero overrides.
**Problem:** A chart should install cleanly with `helm install web ./mychart` and no `-f`/`--set` at all — sensible defaults baked into `values.yaml`, and every templated field guarded so nothing renders empty.

```yaml
# Chart.yaml
apiVersion: v2
name: mychart
description: A minimal but production-shaped chart
type: application
version: 0.1.0
appVersion: "1.27.3"
```

```yaml
# values.yaml
replicaCount: 1
image:
  repository: nginx
  tag: ""               # empty → falls back to .Chart.AppVersion
  pullPolicy: IfNotPresent
resources: {}
```

```yaml
# templates/deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ include "mychart.fullname" . }}
  labels:
    {{- include "mychart.labels" . | nindent 4 }}
spec:
  replicas: {{ .Values.replicaCount | default 1 }}
  selector:
    matchLabels:
      {{- include "mychart.selectorLabels" . | nindent 6 }}
  template:
    metadata:
      labels:
        {{- include "mychart.selectorLabels" . | nindent 8 }}
    spec:
      containers:
        - name: {{ .Chart.Name }}
          image: "{{ .Values.image.repository }}:{{ .Values.image.tag | default .Chart.AppVersion }}"
          imagePullPolicy: {{ .Values.image.pullPolicy }}
          {{- with .Values.resources }}
          resources:
            {{- toYaml . | nindent 12 }}
          {{- end }}
```

**The design intent:** `tag: ""` deliberately defaults the image tag to `.Chart.AppVersion`, so bumping `appVersion` in `Chart.yaml` ships the new image without touching `values.yaml` — and a deployer can still pin an explicit tag when needed. `resources: {}` plus `{{- with }}` means the `resources:` block is omitted entirely when empty rather than rendering `resources:` with no children (which is valid but noisy). The `selector.matchLabels` uses `selectorLabels` (not the full label set), keeping the immutable selector free of the version label so `appVersion` bumps don't break the upgrade. This is the scaffold `helm create` generates, stripped to its load-bearing parts — verify with `helm template web ./mychart` producing valid YAML before adding complexity.

#### Q56. [Practical] How would you design the values.yaml layering for a chart deployed to dev, staging, and prod — and why not put everything in one file?
The design principle is **one base of safe defaults plus thin per-environment overlays**, merged left-to-right by repeated `-f`. Each environment file should contain only what *differs* from base, so a reviewer sees the environment delta at a glance and base changes propagate everywhere automatically.

```
values.yaml          # safe, prod-leaning defaults: 1 replica, modest limits, ingress off
values-dev.yaml      # debug logging, no resource limits, ephemeral DB
values-staging.yaml  # prod-like sizing, real ingress host, smaller replica count
values-prod.yaml     # HA replicas, strict limits, PDB on, prod ingress + TLS
```

```bash
helm upgrade --install web ./mychart -n prod \
  -f values.yaml -f values-prod.yaml \
  --set image.tag=$GIT_SHA --atomic
```

The reason against a single giant file (or one fully-duplicated file per env) is drift: duplicated files diverge silently — someone fixes a probe path in prod and forgets dev, and the bug only surfaces on the next promotion. With layering, the base owns shared truth and each overlay owns only its differences, so the diff between staging and prod *is* the file. Two design nuances matter: make `values.yaml` default to the *safest* posture (prod-leaning), because a forgotten overlay then fails closed rather than open; and remember **arrays replace, they don't merge** (Q27), so a list like `extraEnv` set in base is wholly replaced — not appended to — by an overlay, which is a common surprise. Reserve `--set` strictly for the one volatile value (the image tag/SHA) injected by CI, keeping everything else committed and auditable.

### 🟡 Intermediate — extended

#### Q57. [Coding] Write a helper that renders a normalized image reference supporting a global registry, a digest, or a tag — with the digest taking precedence.
**Problem:** Across an org you want every chart to honor a `global.imageRegistry` mirror, prefer an immutable `@sha256:` digest when supplied (for supply-chain pinning), and otherwise fall back to a tag, then `appVersion`. Hardcoding `repo:tag` in each manifest can't express this.

```yaml
{{/* templates/_helpers.tpl */}}
{{- define "mychart.image" -}}
{{- $reg := .Values.image.registry | default .Values.global.imageRegistry | default "" -}}
{{- $repo := .Values.image.repository -}}
{{- $base := $repo -}}
{{- if $reg -}}
{{-   $base = printf "%s/%s" $reg $repo -}}
{{- end -}}
{{- if .Values.image.digest -}}
{{-   printf "%s@%s" $base .Values.image.digest -}}
{{- else -}}
{{-   $tag := .Values.image.tag | default .Chart.AppVersion -}}
{{-   printf "%s:%s" $base $tag -}}
{{- end -}}
{{- end -}}
```

```yaml
# usage in deployment.yaml
image: "{{ include "mychart.image" . }}"
```

**Design reasoning:** the precedence (`digest` > `tag` > `appVersion`) encodes intent — a digest is immutable and is the right choice for production supply-chain guarantees, so when present it must win and the tag is ignored entirely (mixing `:tag@sha256:` is invalid). The `global.imageRegistry` fallback lets a platform team repoint every chart at an internal mirror with one global value, which is essential in air-gapped or rate-limited environments. **Edge cases:** when no registry is set, `$base` is just the repository (so Docker Hub's implicit default still works); `default` chains short-circuit cleanly on empty strings. **Why a helper, not inline:** centralizing this in one named template means a future change (say, adding a `global.imagePullSecrets` correlation) happens in one place across every manifest that shows an image.

#### Q58. [Coding] Write a template snippet that merges a chart-level default map with a user-supplied override map (deep merge), e.g. for pod annotations.
**Problem:** You want chart-shipped default annotations (Prometheus scrape config, say) to always be present, while still letting users *add* their own — without the user override wholesale-replacing the defaults the way a plain values assignment would.

```yaml
spec:
  template:
    metadata:
      annotations:
        {{- $defaults := dict
              "prometheus.io/scrape" "true"
              "prometheus.io/port"   (.Values.service.port | toString) }}
        {{- $merged := merge (deepCopy .Values.podAnnotations) $defaults }}
        {{- toYaml $merged | nindent 8 }}
```

```yaml
# values.yaml
podAnnotations:
  team: payments          # user adds this; defaults still survive
```

**Why `merge` + `deepCopy`, and the ordering trap:** Sprig's `merge dst src` merges `src` *into* `dst`, and — critically — **`dst` wins on key conflicts** while `merge` also **mutates `dst` in place**. Putting user annotations as `dst` means a user can override a default (e.g. disable scraping) but unspecified defaults remain; wrapping with `deepCopy` prevents `merge` from mutating the original `.Values` map, which would otherwise cause subtle bugs if the same values are read again later in the render. If you instead want the chart defaults to *always* win (non-negotiable policy annotations), swap the arguments so the defaults are `dst`. **Edge case:** values like the port must be `toString`'d because annotation values must be strings; a raw integer would render unquoted and fail. This deep-merge idiom is the building block for "opinionated defaults that users can extend but a library chart can force."

#### Q59. [Coding] Write a `helm-unittest` test suite that verifies replica defaulting, image tag fallback to appVersion, and that the ingress is absent when disabled.
**Problem:** Template logic regressions (a broken `default`, a flipped conditional) should fail in CI in milliseconds without a cluster. `helm-unittest` renders templates and asserts on the output.

```yaml
# tests/deployment_test.yaml
suite: deployment and ingress behavior
templates:
  - deployment.yaml
  - ingress.yaml
tests:
  - it: defaults replicas to 1 when unset
    template: deployment.yaml
    asserts:
      - equal:
          path: spec.replicas
          value: 1

  - it: falls back to chart appVersion when image.tag is empty
    template: deployment.yaml
    set:
      image.tag: ""
    asserts:
      - matchRegex:
          path: spec.template.spec.containers[0].image
          pattern: ":1\\.27\\.3$"      # appVersion from Chart.yaml

  - it: renders no Ingress object when disabled
    template: ingress.yaml
    set:
      ingress.enabled: false
    asserts:
      - hasDocuments:
          count: 0

  - it: sets the ingress host when enabled
    template: ingress.yaml
    set:
      ingress.enabled: true
      ingress.hosts[0].host: app.example.com
      ingress.hosts[0].paths[0].path: /
    asserts:
      - equal:
          path: spec.rules[0].host
          value: app.example.com
```

```bash
helm plugin install https://github.com/helm-unittest/helm-unittest
helm unittest ./mychart       # runs all suites, exits non-zero on failure
```

**What each assertion protects:** the replica test guards the `| default 1` idiom; the regex test proves the `tag | default .Chart.AppVersion` fallback wires `Chart.yaml`'s `appVersion` into the image — a regression here ships the wrong image silently. The `hasDocuments: count: 0` assertion is the most valuable one for conditional resources: it proves a disabled Ingress emits **zero documents**, not a stray `---` empty document (a classic chomping bug from Q28). **Trade-off:** unit tests assert on *rendered structure*, so they're fast and cluster-free but can't catch "renders fine, doesn't actually serve traffic" — that's what `helm test` against a live release covers (Q30). Run both: unit tests on every PR, `helm test` before promotion.

#### Q60. [Practical] Design the chart structure for a microservice that needs a Deployment, HPA, PDB, ServiceAccount, NetworkPolicy, and ConfigMap — what goes where and what's toggled?
The design goal is **one template file per resource kind, each independently toggleable**, with cross-cutting concerns (labels, names, image) centralized in `_helpers.tpl`. Everything that isn't universally required is gated by an `enabled` flag so the same chart fits a bare dev deploy and a hardened prod deploy.

```
mychart/
├── Chart.yaml
├── values.yaml          # one stanza per toggle: autoscaling.enabled, pdb.enabled, networkPolicy.enabled
├── values.schema.json   # rejects typos, enforces ranges
├── templates/
│   ├── _helpers.tpl     # name, fullname, selectorLabels, labels, image, serviceAccountName
│   ├── serviceaccount.yaml   # {{- if .Values.serviceAccount.create }}
│   ├── configmap.yaml
│   ├── deployment.yaml       # references SA, mounts ConfigMap, checksum/config annotation
│   ├── service.yaml
│   ├── hpa.yaml              # {{- if .Values.autoscaling.enabled }} + .Capabilities gate
│   ├── pdb.yaml              # {{- if .Values.pdb.enabled }}
│   ├── networkpolicy.yaml    # {{- if .Values.networkPolicy.enabled }}
│   └── tests/connection_test.yaml
```

**Key design decisions and the interactions that bite:** (1) the **HPA and `replicas` conflict** — if `autoscaling.enabled`, the Deployment must *omit* `spec.replicas` (`{{- if not .Values.autoscaling.enabled }}replicas: ...{{- end }}`), otherwise every Helm upgrade resets replicas and fights the HPA in a thrash loop. (2) The **PDB must reference the same `selectorLabels`** as the Deployment, or it protects nothing; centralizing selectors in a helper guarantees they match. (3) The **ServiceAccount name** is computed by a helper so the Deployment's `serviceAccountName` and the SA's `metadata.name` can't drift, and `create: false` lets you reference a pre-existing SA. (4) The Deployment carries the `checksum/config` annotation (Q43) so ConfigMap changes trigger a rollout. The overarching principle: each resource is opt-in via a flag, helpers prevent name/label drift between coupled resources, and the schema makes the toggles type-safe.

### 🟠 Advanced — extended

#### Q61. [Coding] Write a `pre-upgrade` migration hook Job that is idempotent, self-cleaning, time-bounded, and gates the release on success.
**Problem:** Run a database schema migration before the new code rolls out, exactly once per upgrade, without leaking Jobs, without hanging the release forever, and failing the upgrade if the migration fails.

```yaml
# templates/migrate-job.yaml
apiVersion: batch/v1
kind: Job
metadata:
  name: {{ include "mychart.fullname" . }}-migrate-{{ .Release.Revision }}
  labels:
    {{- include "mychart.labels" . | nindent 4 }}
  annotations:
    "helm.sh/hook": pre-upgrade
    "helm.sh/hook-weight": "-5"
    "helm.sh/hook-delete-policy": before-hook-creation,hook-succeeded
spec:
  activeDeadlineSeconds: 600          # hard wall-clock cap → Job fails, release aborts
  backoffLimit: 2                     # retry transient failures, then give up
  ttlSecondsAfterFinished: 300        # belt-and-suspenders cleanup
  template:
    metadata:
      labels:
        {{- include "mychart.selectorLabels" . | nindent 8 }}
    spec:
      restartPolicy: Never
      containers:
        - name: migrate
          image: "{{ include "mychart.image" . }}"
          command: ["/app/migrate", "up"]
          env:
            - name: DATABASE_URL
              valueFrom:
                secretKeyRef:
                  name: {{ include "mychart.fullname" . }}-db
                  key: url
```

**Why each field is load-bearing:** the name includes `{{ .Release.Revision }}` so each upgrade gets a *fresh, unique* Job name — Job pod templates are immutable, so reusing a static name across upgrades causes "field is immutable" errors; `before-hook-creation` also deletes the prior same-named hook, but the revision suffix makes intent explicit and avoids racing. `activeDeadlineSeconds` is critical because hooks block the release by default — without it, a wedged migration hangs the upgrade indefinitely; with it, the Job fails and the upgrade aborts cleanly. `backoffLimit: 2` distinguishes a transient DB blip from a real failure. **The non-coding caveat:** the migration command itself must be idempotent and follow expand/contract, because `helm rollback` will **not** undo a forward migration (Q19) — the hook gates the release, but rollback safety is the migration's own responsibility, not Helm's.

#### Q62. [Coding] Implement a `tpl`-based pattern that lets users inject templated values (e.g. an ingress host built from release name) safely, and explain the injection risk.
**Problem:** Let a chart consumer write a value like `host: "{{ .Release.Name }}.{{ .Values.domain }}"` in their values file and have the chart evaluate it — so the host adapts to release name and environment without the user hardcoding it.

```yaml
# values.yaml
domain: example.com
ingress:
  hostTemplate: "{{ .Release.Name }}.{{ .Values.domain }}"
```

```yaml
# templates/ingress.yaml
spec:
  rules:
    - host: {{ tpl .Values.ingress.hostTemplate . | quote }}
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: {{ include "mychart.fullname" . }}
                port:
                  number: {{ .Values.service.port }}
```

For a release named `payments` in `example.com`, `tpl` evaluates the string against the root context `.` and renders `host: "payments.example.com"`.

**The design value and the danger:** `tpl` is the only way to defer template evaluation to a *value* rather than the template file, which is powerful for library charts and shared ingress conventions where the template can't know the host ahead of time. The danger is that `tpl` executes **whatever template syntax the value contains** with full access to `.Values`, `.Files`, and Sprig — so if `hostTemplate` ever comes from untrusted input (a multi-tenant self-service portal writing values), a malicious value like `{{ .Files.Get "secret.txt" }}` or a resource-exhausting loop is code injection into your render. The rule: use `tpl` only on values you or your platform team control; never `tpl` end-user-supplied free-form strings. Also beware accidental double-rendering — if a value already contains literal `{{ }}` you didn't intend as template syntax, `tpl` will try to evaluate it and error.

#### Q63. [Coding] Write a NOTES.txt that prints the correct access URL whether the Service is ClusterIP, NodePort, or LoadBalancer, plus the next steps.
**Problem:** After install, the user should get accurate, copy-pasteable instructions for *their* service type — a `kubectl port-forward` for ClusterIP, a node IP:port for NodePort, and the external IP for LoadBalancer.

```yaml
{{/* templates/NOTES.txt */}}
Your release "{{ .Release.Name }}" is deployed in namespace "{{ .Release.Namespace }}".

{{- if .Values.ingress.enabled }}
Access it via your Ingress host(s):
{{- range .Values.ingress.hosts }}
  https://{{ .host }}
{{- end }}
{{- else if contains "LoadBalancer" .Values.service.type }}
Waiting for the LoadBalancer IP (this can take a minute):

  export SVC_IP=$(kubectl get svc -n {{ .Release.Namespace }} {{ include "mychart.fullname" . }} \
    -o jsonpath='{.status.loadBalancer.ingress[0].ip}')
  echo "http://$SVC_IP:{{ .Values.service.port }}"
{{- else if contains "NodePort" .Values.service.type }}
  export NODE_PORT=$(kubectl get svc -n {{ .Release.Namespace }} {{ include "mychart.fullname" . }} \
    -o jsonpath='{.spec.ports[0].nodePort}')
  export NODE_IP=$(kubectl get nodes -o jsonpath='{.items[0].status.addresses[0].address}')
  echo "http://$NODE_IP:$NODE_PORT"
{{- else }}
ClusterIP service — reach it from your laptop with a port-forward:

  kubectl port-forward -n {{ .Release.Namespace }} svc/{{ include "mychart.fullname" . }} 8080:{{ .Values.service.port }}
  open http://127.0.0.1:8080
{{- end }}

Run "helm test {{ .Release.Name }}" to verify the deployment is healthy.
```

**Design reasoning:** NOTES.txt is itself a template rendered against the release context, so it can branch on the *actual* service type the user chose — turning a generic "it's installed" into actionable, correct instructions and cutting the most common post-install support question ("how do I reach it?"). The ordering matters: ingress is checked first (it supersedes the Service-type instructions), then the most external service types down to ClusterIP as the fallback. **Edge case:** the LoadBalancer branch acknowledges the IP may not be ready yet and gives a command to fetch it rather than printing a stale/empty value at install time — because NOTES.txt renders before the cloud provisions the LB. Good NOTES.txt is a small thing that disproportionately improves a chart's UX and is a frequent code-review ask for internal charts.

#### Q64. [Practical] Design a chart that must deploy a StatefulSet with per-replica persistent storage, ordered startup, and a headless Service. What are the Helm-specific design constraints?
The design centers on a StatefulSet plus a **headless Service** (`clusterIP: None`) for stable network identities, with storage driven by `volumeClaimTemplates` rather than a templated PVC — and the Helm-specific constraints come from how Helm's three-way merge and upgrade semantics interact with StatefulSet immutability.

```yaml
# templates/statefulset.yaml (key fields)
spec:
  serviceName: {{ include "mychart.fullname" . }}-headless
  replicas: {{ .Values.replicaCount }}
  podManagementPolicy: {{ .Values.podManagementPolicy | default "OrderedReady" }}
  selector:
    matchLabels:
      {{- include "mychart.selectorLabels" . | nindent 6 }}
  volumeClaimTemplates:
    - metadata:
        name: data
      spec:
        accessModes: ["ReadWriteOnce"]
        storageClassName: {{ .Values.persistence.storageClass | quote }}
        resources:
          requests:
            storage: {{ .Values.persistence.size | quote }}
```

**The Helm-specific traps you design around:** (1) **`volumeClaimTemplates` is immutable** after creation — you cannot grow the requested storage via `helm upgrade`; a values change to `persistence.size` will make the upgrade fail with a "forbidden: updates to statefulset spec ... are forbidden" error. So size is effectively install-time, and resizing is an out-of-band PVC operation. (2) **PVCs created by the StatefulSet are not owned by Helm**, so `helm uninstall` deletes the StatefulSet but **leaves the PVCs** (by design — you don't want to lose data) — document this, because it's a quota/cost surprise. Conversely, if you *want* them kept across an intentional delete, the StatefulSet's own retention policy governs it, not Helm. (3) Add `helm.sh/resource-policy: keep` to any standalone PVC the chart creates directly so Helm never deletes it. (4) The headless Service name must exactly match `serviceName`, so compute both from the same helper. The interview point: a StatefulSet chart is mostly about respecting Kubernetes immutability through Helm's upgrade path — the chart's job is to make install-time choices explicit and keep Helm's hands off stateful data.

#### Q65. [Practical] Two teams' charts both bundle the same CRD and the same cluster-scoped ClusterRole, and installs conflict. Design a resolution.
This is a cluster-scoped-resource ownership collision: CRDs and ClusterRoles are global, but Helm releases are namespaced, so two releases each claiming ownership of the same global object will fight — the second install errors with "exists and cannot be imported into the current release," or one uninstall deletes an object the other still needs.

```
 PROBLEM                                   DESIGN
 release-A (ns team-a) ─┐                   ┌─ platform release (cluster-scoped)
                        ├─► same CRD  ──►   │    CRDs, ClusterRoles  (installed ONCE)
 release-B (ns team-b) ─┘   ClusterRole     └─ app releases reference them by name,
                            CONFLICT              create only namespaced objects
```

**The design fix, in order of preference:** (1) **Factor the shared cluster-scoped objects into a single platform/operator release** installed once by the platform team; the app charts then only create *namespaced* objects (Deployment, RoleBinding to the existing ClusterRole) and reference the CRD/ClusterRole **by name** without bundling them. This removes the contention entirely — there's exactly one owner. (2) For CRDs specifically, move them to the `crds/` directory (Q44): Helm installs them once on first install and refuses to touch them on upgrade/uninstall, so a second release bundling the same CRD in `crds/` won't conflict on ownership the way a `templates/` CRD does — though it still can't *evolve* the schema. (3) If the objects must stay in app charts short-term, scope them: make the ClusterRole name release-specific (`{{ .Release.Name }}-reader`) so they're distinct objects, accepting the duplication. The principle: **cluster-scoped resources need a single, deliberate owner** — bundling shared global objects into per-team namespaced releases is the root anti-pattern, and the durable fix is a platform-owned release plus by-name references, mirroring how operators ship their CRDs separately from the workloads that consume them.

#### Q66. [Coding] Write a template that builds a container's `env` list from three sources — literal values, ConfigMap refs, and Secret refs — defined in values.
**Problem:** Let users declare environment variables in three shapes (inline literal, from a ConfigMap key, from a Secret key) in one values structure, and render a single correct `env:` list.

```yaml
# values.yaml
env:
  literal:
    LOG_LEVEL: info
    REGION: us-east-1
  fromConfigMap:
    FEATURE_FLAGS: { name: app-config, key: flags }
  fromSecret:
    DB_PASSWORD: { name: app-db, key: password }
```

```yaml
# templates/deployment.yaml (container snippet)
          env:
          {{- range $k, $v := .Values.env.literal }}
            - name: {{ $k }}
              value: {{ $v | quote }}
          {{- end }}
          {{- range $k, $ref := .Values.env.fromConfigMap }}
            - name: {{ $k }}
              valueFrom:
                configMapKeyRef:
                  name: {{ $ref.name }}
                  key: {{ $ref.key }}
          {{- end }}
          {{- range $k, $ref := .Values.env.fromSecret }}
            - name: {{ $k }}
              valueFrom:
                secretKeyRef:
                  name: {{ $ref.name }}
                  key: {{ $ref.key }}
          {{- end }}
```

**Design reasoning:** separating the three sources into distinct maps keeps the values schema self-documenting and lets each render path stay simple, rather than one polymorphic list where each entry needs a discriminator field. `quote` on the literal values is non-negotiable — an unquoted `LOG_LEVEL: on` or a numeric-looking region would be misinterpreted by YAML (the `on`/`off`/`yes`/`no` boolean trap). **Edge cases:** Go template map iteration order is **not guaranteed stable** across renders — for `env` this is cosmetically harmless, but if you fed it into something order-sensitive (or wanted clean `helm diff` output) you'd sort keys with `range $k := keys .Values.env.literal | sortAlpha`. **Trade-off:** this is more verbose than `envFrom: [configMapRef: ...]` which injects *all* keys of a ConfigMap; use `envFrom` when you want the whole map and this explicit form when you need per-variable naming or to mix sources.

### 🔴 Expert — extended

#### Q67. [Coding] Write a library chart's common Deployment template and show how a consuming application chart uses it via `include` and a merge override.
**Problem:** Eliminate per-service Deployment boilerplate org-wide. The library chart defines the skeleton (labels, security context, probes, the immutable-selector discipline); each app chart supplies only its specifics and can override any field.

```yaml
# common library chart: templates/_deployment.tpl  (Chart.yaml: type: library)
{{- define "common.deployment" -}}
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ include "common.fullname" . }}
  labels: {{- include "common.labels" . | nindent 4 }}
spec:
  replicas: {{ .Values.replicaCount | default 1 }}
  selector:
    matchLabels: {{- include "common.selectorLabels" . | nindent 6 }}
  template:
    metadata:
      labels: {{- include "common.selectorLabels" . | nindent 8 }}
    spec:
      securityContext:
        runAsNonRoot: true
        seccompProfile: { type: RuntimeDefault }
      containers:
        - name: {{ .Chart.Name }}
          image: {{ include "common.image" . }}
          {{- with .Values.containerExtra }}
          {{- toYaml . | nindent 10 }}
          {{- end }}
{{- end -}}
```

```yaml
# app chart: Chart.yaml
dependencies:
  - name: common
    version: "2.1.0"
    repository: "oci://my.registry.io/charts"
```

```yaml
# app chart: templates/deployment.yaml — one line, plus its own knobs in containerExtra
{{- include "common.deployment" . -}}
```

**Why this is the org-scale pattern:** the library chart bakes in *non-negotiable* defaults (`runAsNonRoot`, `seccompProfile`, the separate selector labels), so 200 microservice charts cannot individually drop a security field — the safe thing is the default. The `{{- with .Values.containerExtra }}{{ toYaml . | nindent 10 }}` seam is the override mechanism: app charts inject ports, probes, env, and resources through values without forking the template. **The expert nuances:** a library chart is `type: library` and ships *only* `_*.tpl` definitions (no renderable manifests), so it can never be installed standalone; the consuming chart must `include` (not `template`) so the output is a string Helm renders; and versioning the library with SemVer in the OCI registry means a security-policy change is a single dependency bump propagated by PR across every service. The risk to manage is coupling — a breaking change to `common.deployment` breaks every consumer at once, so library changes need the same `helm diff` gating and staged rollout as any shared dependency.

#### Q68. [Practical] Design a zero-downtime strategy for upgrading a chart whose Deployment selector labels must change (e.g. migrating to `app.kubernetes.io/*` labels).
This is one of the genuinely hard Helm upgrades because **`spec.selector.matchLabels` is immutable** — you cannot change it on an existing Deployment via `helm upgrade`; the API server rejects it, and Helm's three-way merge can't help. A naive label migration therefore *requires* replacing the Deployment, which risks downtime if done carelessly.

```
 PHASE 1            PHASE 2                       PHASE 3
 old Deploy         old Deploy (scaled down)      old Deploy (deleted)
 (old labels) ──►   new Deploy (new labels) ──►   new Deploy only
 Service selects    Service selects BOTH          Service selects new
 old pods           label sets (transition)       labels
```

**The design, executed as a controlled sequence rather than one `helm upgrade`:** (1) **Introduce the new Deployment alongside the old** under a different name (templated by color/version), both fronted by a Service whose selector is temporarily broadened to match pods of *either* label set — so traffic is served throughout. (2) Scale the new Deployment up and the old down, watching readiness, so capacity is continuous. (3) Once the new pods serve all traffic, narrow the Service selector to the new labels and remove the old Deployment. Each phase is a separate, reviewable `helm upgrade` driven by a values flag (`migration.phase`).

The blunter alternative — let Helm **delete and recreate** the Deployment (it will, on an immutable-selector change, if you force it) — is acceptable *only* if a brief gap is tolerable or a higher-level controller (a Service mesh, multiple replicas behind a PDB with surge) masks it, but for true zero-downtime the parallel-Deployment dance is required. The expert framing: immutable fields turn an "upgrade" into a "migration," and the chart's job is to express the intermediate states as explicit, flag-gated phases so each step is a clean `helm diff` and the Service never points at zero ready pods. This is also exactly the class of problem Argo Rollouts solves more elegantly by owning the traffic cutover, which is why label/selector migrations are a strong argument for a progressive-delivery controller on top of Helm.

#### Q69. [Coding] Write a `post-renderer` (Kustomize-based) that injects an Istio sidecar annotation and a common label into every object of an unmodifiable upstream chart.
**Problem:** You depend on a third-party chart you can't fork, and you must add `sidecar.istio.io/inject: "true"` plus an org cost-center label to *every* rendered object — something the chart exposes no values for.

```bash
#!/usr/bin/env bash
# post-render.sh — Helm pipes rendered manifests to stdin
set -euo pipefail
TMP="$(mktemp -d)"
cat > "$TMP/all.yaml"
cat > "$TMP/kustomization.yaml" <<'EOF'
resources:
  - all.yaml
labels:
  - pairs:
      cost-center: payments
    includeSelectors: false      # add as metadata label, NOT into selectors
commonAnnotations:
  sidecar.istio.io/inject: "true"
EOF
kustomize build "$TMP"
rm -rf "$TMP"
```

```bash
helm upgrade --install web upstream/their-chart -f values-prod.yaml \
  --post-renderer ./post-render.sh
```

**Why a post-renderer instead of forking:** Helm runs the executable with the full rendered manifest stream on stdin and applies whatever it prints — so you mutate output without touching the chart, and you keep pulling clean upstream updates. The `includeSelectors: false` flag is the critical correctness detail: adding a label that lands in `spec.selector.matchLabels` would break the immutable selector on the next upgrade (the recurring theme), so the patch must add labels to metadata only. **The fragility to acknowledge:** the patch lives outside the chart and is invisible to anyone reading `values.yaml`; an upstream change that renames or restructures a resource can silently shift what the Kustomize patch targets, and a `commonAnnotations` collision with a value the chart already sets will be overwritten. So post-renderers are the right escape hatch for *small, structural, cross-cutting* edits (sidecar injection, org labels, a nodeSelector); for pervasive or logic-level changes, forking — and owning the merge burden — is more honest (Q39). Always gate the post-rendered result with `helm diff upgrade` and `kubeconform` because you've inserted a non-Helm transformation into the apply path.

#### Q70. [Practical] You must deploy the same chart to 60 clusters across 5 regions with per-region and per-cluster config, and a bad rollout must not hit all 60 at once. Design the system.
The design separates *the chart* (one versioned artifact) from *the fan-out and config layering* (GitOps with a generator) from *the safety gating* (progressive, region-staged rollout), so a single chart serves 60 targets while blast radius stays bounded.

```
 OCI registry: svc 1.8.0 (one signed chart)
        │
 Git (desired state, layered values)
   base.yaml  +  region/<r>.yaml  +  cluster/<c>.yaml
        │
 Argo CD ApplicationSet (matrix/cluster generator) → 60 Applications
        │
   wave 1: 1 canary cluster ─► verify ─► wave 2: rest of region A ─► … ─► region E
```

**The layered components:** (1) **One chart**, pinned by exact version + digest, in a signed OCI registry — never per-cluster chart forks. (2) **Three-level values layering** merged by the GitOps tool: a global base, a per-region overlay (region endpoints, replica sizing), and a thin per-cluster overlay (cluster name, specific overrides) — same principle as Q56 scaled to a fleet. (3) **An Argo CD `ApplicationSet`** with a cluster/matrix generator produces the 60 Applications declaratively from cluster labels, so adding a cluster is a label, not 60 hand-written manifests. (4) **Staged rollout for blast-radius control**: progressive sync waves or sync windows so the new version lands on one canary cluster first, is verified (health + SLOs), then one region, then the rest — never all 60 simultaneously. Argo's `RollingSync` (in ApplicationSet) or Flux dependency ordering enforces this.

The safety design is the heart of the answer: pin the version so all clusters converge on the *same* tested artifact (no drift), but **decouple "what version is desired" from "how fast it propagates"** so a regression is caught on the canary cluster and the wave halts automatically. Layer in per-region/per-cluster `helm diff` in CI, `values.schema.json` to fail bad config before any cluster, and admission policy (Kyverno) as the cluster-boundary backstop. The principle: at fleet scale, the chart is uniform and boring; the intelligence lives in the *promotion topology* — staged waves with automated verification gates so "bad rollout" means "one canary cluster degraded," not "60 clusters down."

#### Q71. [Coding] Write a helper that converts a values map of `resources` profiles (small/medium/large) into a concrete resources block, defaulting safely.
**Problem:** Instead of forcing every consumer to write full `requests`/`limits`, offer named t-shirt-size profiles in values and let the chart expand the chosen profile — while still allowing a raw override.

```yaml
# values.yaml
resourceProfile: small        # small | medium | large
resourcesOverride: {}         # if set, wins over the profile

resourceProfiles:
  small:  { requests: { cpu: 100m, memory: 128Mi }, limits: { cpu: 250m, memory: 256Mi } }
  medium: { requests: { cpu: 250m, memory: 256Mi }, limits: { cpu: 500m, memory: 512Mi } }
  large:  { requests: { cpu: 500m, memory: 512Mi }, limits: { cpu: "1",  memory: 1Gi   } }
```

```yaml
{{/* _helpers.tpl */}}
{{- define "mychart.resources" -}}
{{- if .Values.resourcesOverride -}}
{{- toYaml .Values.resourcesOverride -}}
{{- else -}}
{{- $profile := .Values.resourceProfile | default "small" -}}
{{- $chosen := index .Values.resourceProfiles $profile -}}
{{- if not $chosen -}}
{{- fail (printf "unknown resourceProfile %q; valid: small, medium, large" $profile) -}}
{{- end -}}
{{- toYaml $chosen -}}
{{- end -}}
{{- end -}}
```

```yaml
# deployment.yaml usage
          resources:
            {{- include "mychart.resources" . | nindent 12 }}
```

**Design reasoning:** named profiles raise the abstraction so app teams pick a t-shirt size instead of guessing CPU millicores, while the platform team owns what each size means in one place — change the `large` profile and every chart using it shifts on the next deploy. The precedence (`resourcesOverride` > profile) provides an escape hatch for the rare workload that doesn't fit a size, without abandoning the profile system. **Why `fail` matters here:** `index` on a missing key returns nil, which would `toYaml` to `null` and silently produce a container with *no* resources — so the explicit `fail` with a helpful message converts a typo (`resourceProfile: smal`) into a clear build error rather than an unbounded pod. **Edge case:** `cpu: "1"` and `memory: 1Gi` are quoted/expressed carefully because `toYaml` preserves them, but a bare `cpu: 1` would serialize as an integer — Kubernetes accepts it, but quoting avoids ambiguity. Pair this helper with a `values.schema.json` `enum` on `resourceProfile` so invalid sizes fail even earlier than the `fail`.

#### Q72. [Behavioral] (STAR) Tell me about a time you led a high-stakes Helm/Kubernetes change under pressure where the blast radius was large.
**Situation:** At a previous company we ran ~40 services on a shared umbrella chart, and a routine upgrade to bump one service's image silently changed a shared `_helpers.tpl` label, which — because the umbrella re-renders everything — would have re-applied a modified selector across multiple Deployments. We caught it in staging when a single service's upgrade unexpectedly cycled unrelated pods. With a customer launch 48 hours out, I owned getting us to a safe production upgrade without a freeze that would block the launch.

**Task:** I needed to (a) prove exactly what the production upgrade would change before running it, (b) prevent the shared-helper change from triggering immutable-selector failures across the fleet, and (c) do it without a big-bang refactor we had no time to test.

**Action:** First I made the change *visible*: I ran `helm diff upgrade` against the live release and posted the full manifest delta in the incident channel, which showed the label leaking into `selector.matchLabels` on four Deployments — the immutable field. Rather than push the risky umbrella upgrade, I reverted the helper change to keep selectors stable and moved the label addition to *metadata-only* labels, re-running `helm diff` until the diff showed zero selector changes. I then upgraded with `--atomic --timeout 8m` against one canary service first, verified rollout and SLOs, and only then proceeded. In parallel I opened an ADR proposing we decompose the umbrella into per-service charts after the launch, because the root cause was the coupling, not the one change.

**Result:** The production upgrade went clean with no unexpected pod cycling, the launch shipped on time, and the `helm diff` gate I added to the PR pipeline became mandatory for that repo. Over the next quarter we executed the decomposition incrementally (each extraction proven by a zero-change `helm diff` against the umbrella's output), which eliminated the entire class of "one change re-applies to everything" incident. The lasting lesson I carry: under pressure, the highest-leverage move is making the change *reviewable as a manifest diff* before applying — `helm diff` converted a scary unknown into a bounded, verifiable decision, and that's what let us move fast safely.

#### Q73. [Theory] Explain the precise semantics of `helm rollback` on a release that has hooks, deleted resources, and changed values — what exactly is and isn't restored?
`helm rollback web N` is implemented as a *forward* operation: Helm reads the stored release object for revision `N`, re-applies its manifests via the same three-way merge an upgrade uses, and records the result as a **new** revision `N+1` (history is never rewritten). So "rolling back to 3" really means "make the cluster match revision 3's manifests and call it revision N+1." This has several precise consequences that trip up even experienced operators.

```
 revisions:  v6 (good) ── v7 (bad upgrade) ── rollback to v6
 result:     v8 created, manifests == v6, status "deployed"
             v7 still in history (status "superseded"), NOT deleted
```

**What IS restored:** the rendered Kubernetes manifests and the values that produced revision `N` (so `helm get values web --revision <new>` matches revision N's values). Resources that revision `N` defined but that the bad upgrade deleted get re-created; fields the bad upgrade changed get reverted via the merge.

**What is NOT restored:** (1) **Hook side effects** — a `pre-upgrade` migration Job that already ran is not un-run; rollback re-applies manifests but does not invoke "reverse hooks," so a forward DB migration stays applied (this is *the* classic trap and why migrations must be expand/contract). (2) **Out-of-band and `resource-policy: keep` resources** — PVCs, externally-created objects, anything Helm doesn't track in the release manifest is untouched. (3) **Data** — rollback reverts *spec*, never the contents of a PV or external store. (4) **Mutations made by other controllers** that aren't in Helm's last-applied set may be left as-is or fought, depending on the three-way merge. The expert summary: rollback is a *declarative re-apply of a prior spec*, not a transactional time machine — it restores the desired-state document, while side effects, persistent data, and untracked resources live outside its scope by design. Safe rollback is therefore a *design* property of the chart (idempotent, backward-compatible migrations; `keep` policies understood), not something the `rollback` command can guarantee on its own.

#### Q74. [Coding] Write the `_helpers.tpl` and Deployment wiring for a configurable ServiceAccount that supports create-or-reference, plus IRSA/Workload-Identity annotations.
**Problem:** A chart should create a ServiceAccount by default, but in environments where SAs are pre-provisioned it should reference an existing one — and on EKS/GKE it must carry the cloud IAM annotation (IRSA role ARN / GCP service account) for pod-level cloud access.

```yaml
{{/* _helpers.tpl */}}
{{- define "mychart.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "mychart.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}
```

```yaml
# templates/serviceaccount.yaml
{{- if .Values.serviceAccount.create -}}
apiVersion: v1
kind: ServiceAccount
metadata:
  name: {{ include "mychart.serviceAccountName" . }}
  labels: {{- include "mychart.labels" . | nindent 4 }}
  {{- with .Values.serviceAccount.annotations }}
  annotations:
    {{- toYaml . | nindent 4 }}
  {{- end }}
{{- end -}}
```

```yaml
# values.yaml
serviceAccount:
  create: true
  name: ""        # "" → derived from fullname
  annotations:
    eks.amazonaws.com/role-arn: arn:aws:iam::123456789012:role/web-app
```

```yaml
# deployment.yaml — always references the helper, regardless of create
    spec:
      serviceAccountName: {{ include "mychart.serviceAccountName" . }}
```

**Design reasoning:** the helper is the single source of truth for the SA name, so the Deployment's `serviceAccountName` and the SA's `metadata.name` can never drift — and crucially it resolves correctly in **both** branches (`create: true` derives from fullname; `create: false` returns the user-named or `default` SA). This is the exact pattern `helm create` ships, and the subtle correctness point is that the Deployment must call the helper unconditionally rather than hardcoding a name, because the whole point is letting an operator flip `create: false` to plug in a pre-existing SA without editing templates. **Why annotations belong on the SA, not the pod:** IRSA (AWS) and Workload Identity (GCP) bind cloud IAM to the *ServiceAccount*; the admission webhook then projects the token into pods that use it. Putting the role ARN here means rotating the IAM role is a values change, and `serviceAccount.create: false` lets a security team own SA+IAM provisioning entirely outside the chart while the workload just references it by name.

#### Q75. [Practical] How would you design and operate Helm chart releases to be fully auditable and reproducible for a compliance audit (SOC 2 / FedRAMP)? 
The design goal is that for any deployed release, an auditor can answer "what exactly is running, who approved it, from what source, and can we rebuild it bit-for-bit" — which requires removing every source of non-determinism and recording provenance at each stage.

```
 SOURCE                BUILD               DISTRIBUTE            DEPLOY              RUNTIME
 chart in Git    ─►  pinned Chart.lock ─► signed OCI artifact ─► GitOps PR (approval) ─► admission policy
 (commit SHA)        (digests, no ranges)   (cosign + SBOM)       (Argo/Flux, audit log)   (Kyverno/OPA backstop)
```

**The reproducibility controls:** (1) **No non-determinism in render** — ban in-template secret generation and `lookup`-driven branches (Q37/Q29), pin exact image digests not tags, commit `Chart.lock` with dependency digests, and build with `helm dependency build` (never `update`) so a rebuild from the same commit yields byte-identical manifests. (2) **Provenance** — charts are pushed to a private OCI registry, **cosign-signed**, with an attached SBOM; admission control verifies the signature so an unsigned or tampered chart cannot deploy. (3) **Change control** — desired state lives in Git; every production change is a PR with required reviewers and a posted `helm diff` showing the manifest delta, giving you an immutable, attributable audit trail of *who approved what change*. The GitOps controller's sync history records *when* it was applied.

**The operational controls for the audit:** retain release history (`helm get manifest --revision N` reconstructs exactly what was applied at any point; cap with `--history-max` but archive pruned revisions), scope RBAC tightly so a CI runner cannot deploy cluster-wide, store rendered-secret references (not secret material) so the release secret in etcd carries no plaintext credentials, and run Kyverno/OPA as the cluster-boundary backstop that doesn't trust the chart. The compliance framing: reproducibility comes from *pinning and determinism*, auditability comes from *Git-as-source-of-truth plus signed artifacts*, and defense-in-depth (schema fails early, library chart enforces defaults, admission control is the backstop) means no single control is load-bearing — exactly what an auditor wants to see documented in an ADR and demonstrated end-to-end on one service.

#### Q76. [Coding] Write a template that emits a PodDisruptionBudget only on clusters that support `policy/v1`, falling back to `policy/v1beta1`, and skips it on tiny clusters.
**Problem:** Ship a single chart across Kubernetes versions where the PDB API graduated (`policy/v1beta1` → `policy/v1` at 1.21+) and avoid creating a PDB that would block all disruptions when `replicas` is too low.

```yaml
# templates/pdb.yaml
{{- if and .Values.pdb.enabled (gt (int .Values.replicaCount) 1) }}
{{- if .Capabilities.APIVersions.Has "policy/v1" }}
apiVersion: policy/v1
{{- else }}
apiVersion: policy/v1beta1
{{- end }}
kind: PodDisruptionBudget
metadata:
  name: {{ include "mychart.fullname" . }}
  labels: {{- include "mychart.labels" . | nindent 4 }}
spec:
  {{- if .Values.pdb.minAvailable }}
  minAvailable: {{ .Values.pdb.minAvailable }}
  {{- else }}
  maxUnavailable: {{ .Values.pdb.maxUnavailable | default 1 }}
  {{- end }}
  selector:
    matchLabels: {{- include "mychart.selectorLabels" . | nindent 6 }}
{{- end }}
```

**Design reasoning:** `.Capabilities.APIVersions.Has "policy/v1"` lets one chart self-adjust to the cluster's API surface so you don't fork per Kubernetes version (Q47). The `gt (int .Values.replicaCount) 1` guard is the operationally important one: a PDB with `minAvailable: 1` on a single-replica Deployment **blocks every voluntary disruption** — node drains, cluster upgrades, autoscaler scale-down all hang — so the chart refuses to emit a PDB that would do more harm than good. `minAvailable` vs `maxUnavailable` is mutually exclusive (you cannot set both), so the template branches rather than rendering both. **The `.Capabilities` gotcha to flag:** during `helm template`/dry-run there's no live cluster, so `.Has` reflects Helm's built-in defaults — CI must render the branch that will actually apply via `helm template --kube-version 1.29 --api-versions policy/v1` (Q47), or your validation tests the wrong API version. The selector again reuses `selectorLabels` so the PDB protects exactly the pods the Deployment manages — a mismatched selector silently protects nothing.

#### Q77. [Theory] A chart works with `helm install` but breaks under Argo CD's templating mode. Enumerate the behavioral differences that cause this and how you'd make the chart portable.
The root cause is that Argo CD (in its default mode) does **not** run `helm install` — it runs `helm template` to render manifests, then applies and reconciles them itself, with no Helm release secret, no `helm history`, and no Helm-driven lifecycle (Q45). Several chart features that depend on the real Helm engine silently behave differently or break.

| Feature | Under `helm install` | Under Argo CD templating mode |
|---------|----------------------|-------------------------------|
| Helm hooks (`pre-install`, weights) | run in lifecycle phases | **not run as Helm hooks**; only a subset map to Argo sync waves |
| `lookup` function | queries live cluster | returns **empty** (no cluster during `template`) |
| `.Release.IsUpgrade` / `.Revision` | reflect real state | synthetic — always look like a fresh template render |
| `helm rollback` / history | available | N/A — rollback is to a prior **Git** revision |
| Release secret in etcd | created | none exists |
| CRDs in `crds/` dir | installed before templates | Argo may not apply them the same way; often need separate handling |

**How to make the chart portable:** (1) **Don't rely on Helm hooks for ordering** — express ordering with Argo `argocd.argoproj.io/sync-wave` annotations *in addition to* `helm.sh/hook`, or move migration orchestration to a mechanism both engines honor; for charts that must run hooks, document that Argo translates only a subset. (2) **Eliminate `lookup`** — anything that branches on `lookup` will take the empty branch under Argo, so move that state (e.g. existing-secret reuse) to an External Secrets Operator reference instead. (3) **Don't depend on `.Release.IsUpgrade`** for behavior, since it's always synthetic under templating. (4) **Handle CRDs explicitly** — manage them in a separate Application/release rather than the `crds/` dir, because Argo's handling differs. The expert framing: a portable chart minimizes its dependence on Helm's *stateful runtime* (hooks, lookup, release introspection) and leans on *declarative* constructs that survive a pure `helm template`. If the chart genuinely needs real Helm semantics (hooks, history, rollback), use **Flux's HelmRelease** (which drives actual `helm upgrade --install`) rather than Argo's templating mode — choosing the GitOps engine to match the chart's lifecycle assumptions is itself part of the design.

#### Q78. [Coding] Write a `helm test` Pod plus the CI commands to install ephemerally, run the test, capture logs, and clean up — as a complete CI gate.
**Problem:** A PR pipeline should prove the chart not only renders but actually *works* — install it on a throwaway cluster, run a smoke test against the live release, surface logs on failure, and tear everything down deterministically.

```yaml
# templates/tests/smoke_test.yaml
apiVersion: v1
kind: Pod
metadata:
  name: "{{ include "mychart.fullname" . }}-smoke"
  labels: {{- include "mychart.labels" . | nindent 4 }}
  annotations:
    "helm.sh/hook": test
    "helm.sh/hook-delete-policy": before-hook-creation,hook-succeeded
spec:
  restartPolicy: Never
  containers:
    - name: smoke
      image: curlimages/curl:8.10.1
      command:
        - sh
        - -c
        - |
          set -e
          URL="http://{{ include "mychart.fullname" . }}:{{ .Values.service.port }}/healthz"
          for i in $(seq 1 10); do
            curl -fsS "$URL" && exit 0
            echo "attempt $i failed, retrying"; sleep 3
          done
          echo "service never became healthy"; exit 1
```

```bash
#!/usr/bin/env bash
# ci-chart-gate.sh
set -euo pipefail
NS="ci-$RANDOM"
cleanup() { helm uninstall web -n "$NS" || true; kubectl delete ns "$NS" || true; }
trap cleanup EXIT                         # tear down no matter how we exit

kind create cluster --name chart-ci || true
kubectl create namespace "$NS"
helm install web ./mychart -n "$NS" --wait --timeout 5m
if ! helm test web -n "$NS" --logs; then  # --logs streams the test pod output
  echo "::error:: helm test failed"
  kubectl get pods,events -n "$NS"
  exit 1
fi
echo "chart gate passed"
```

**Design reasoning:** the test Pod retries with a backoff loop rather than a single `curl`, because even after `--wait` reports ready there can be a brief window before the service answers — a single-shot test is flaky and erodes trust in the gate. `--hook-delete-policy: before-hook-creation,hook-succeeded` keeps a failed test Pod *around* for log inspection (it's only deleted on success or before the next run), which is exactly what you want when debugging a CI failure. **The CI discipline:** `--wait --timeout` ensures `helm test` runs against a genuinely ready release; `helm test --logs` streams the smoke output so a failure shows *why* in the CI log; and the `trap cleanup EXIT` guarantees the namespace and release are removed even on failure or interruption, so ephemeral clusters don't leak resources across thousands of CI runs. This `lint → unittest → install --wait → test → teardown` sequence is the standard chart-quality gate (Q30 covers the earlier static layers); this question is the *runtime* half made operational.

#### Q79. [Practical] Design how you'd safely roll out a breaking change to a widely-consumed internal library chart used by 150 application charts.
A library chart is shared infrastructure with a fan-out blast radius — a breaking change to `common.deployment` can break all 150 consumers on their next deploy — so the design treats it exactly like a breaking change to a shared code library: SemVer discipline, additive-first changes, and a staged migration rather than a flag day.

```
 v2.3.0 (current) ── v3.0.0 (breaking)
   │                    │
   ├─ additive path: ship change behind a feature flag in v2.x (default off)
   ├─ early adopters opt in, validate with helm diff (zero unintended change)
   ├─ v3.0.0 flips default; consumers bump dependency in their own PRs, staged
   └─ deprecation window: v2.x supported until N% migrate; CI warns on old usage
```

**The rollout design:** (1) **Make it additive first.** Wherever possible, introduce the new behavior behind a values flag in a *minor* (`v2.x`) release with the old behavior as default, so existing consumers are untouched and early adopters opt in. A truly breaking change (renamed template, changed output structure) is a **major** bump (`v3.0.0`) per SemVer, signaling consumers must act. (2) **Prove no unintended change** — for each consumer, run `helm diff` (or `helm template` diff) between the old and new library version; the goal is that intended consumers see *only* the intended delta. Automate this across all 150 charts as a migration dashboard. (3) **Stage the migration** — consumers bump the dependency version in their *own* PRs on their own cadence (because the library is pinned per chart via `Chart.lock`), so there's no synchronized flag day; the platform team supports both `v2.x` and `v3.x` during a deprecation window. (4) **Communicate and gate** — changelog with migration notes, a CI lint that warns when a chart still depends on the deprecated major, and a sunset date.

The expert insight is that **pinned per-chart dependency versions are the safety mechanism, not an obstacle**: because each app chart pins the library version in its `Chart.lock`, a new library release does *not* auto-apply anywhere — consumers migrate deliberately. That's the opposite of a globally-imported shared template, and it's why versioned library charts in an OCI registry are the right primitive for org-wide standards: you get one source of truth *and* independent, staged adoption. The failure mode to avoid is shipping a breaking change as a minor version or expecting all 150 teams to migrate at once — both turn a controlled migration into a fleet-wide incident.

#### Q80. [Coding] Write a template using `.Files.Get` and `.Files.Glob` to bake configuration files from the chart into a ConfigMap, with `tpl` rendering for dynamic files.
**Problem:** Ship static config files (an nginx.conf, dashboards, a fluentd config) inside the chart and mount them via a ConfigMap — some verbatim, some with template substitution applied.

```
mychart/
└── files/
    ├── nginx.conf.tpl        # contains {{ .Values.upstream }} placeholders
    └── dashboards/
        ├── app.json
        └── jvm.json
```

```yaml
# templates/configmap.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: {{ include "mychart.fullname" . }}-files
  labels: {{- include "mychart.labels" . | nindent 4 }}
data:
  # single file rendered AS a template (substitute .Values into it)
  nginx.conf: |-
    {{- tpl (.Files.Get "files/nginx.conf.tpl") . | nindent 4 }}
  {{- range $path, $bytes := .Files.Glob "files/dashboards/*.json" }}
  # key = just the filename (base), value = verbatim file contents
  {{ base $path }}: |-
    {{- $.Files.Get $path | nindent 4 }}
  {{- end }}
binaryData:
  {{- ($.Files.Glob "files/*.png").AsSecrets | nindent 2 }}
```

**Design reasoning:** `.Files.Get` reads one file's contents as a string; `.Files.Glob` returns a map of path→bytes for pattern matching, which is how you bake *all* dashboards without naming each. The key distinction is **verbatim vs templated**: the dashboards are emitted with `.Files.Get` directly (their JSON contains literal `{{ }}`-free content), while `nginx.conf.tpl` is passed through `tpl` so `{{ .Values.upstream }}` inside the file is substituted at render time — `tpl` is what turns a static file into a templated one. `base $path` strips the directory so the ConfigMap key is just `app.json`, not the full chart-relative path. **Edge cases and limits:** `.Files` cannot read files outside the chart or in `templates/` (those are reserved), and `.helmignore` excludes files from being packaged — so a file present locally but ignored will be missing after `helm package`, a common "works on my machine" surprise. The ConfigMap 1 MiB limit applies (it lands in the release secret too), so large/binary assets belong in object storage or an init-container fetch, not baked into the chart — `.Files` is for small, chart-owned config, not data.

#### Q81. [Theory] Deep-dive: how does Helm's three-way strategic merge decide to patch, replace, or delete a field on upgrade, and where does it produce surprising behavior?
Helm computes the upgrade patch from **three** inputs: the *old manifest* (what Helm rendered and applied last revision, stored in the release secret), the *new manifest* (what the new chart+values render to), and the *live object* (current cluster state, fetched at upgrade time). The old manifest is the crucial "what did Helm previously own" reference that distinguishes Helm 3 from Helm 2's two-way merge.

```
 OLD (last applied by Helm)  ┐
 NEW (about to apply)        ├──► strategic merge ──► patch sent to API server
 LIVE (current cluster)      ┘
   field in OLD & NEW, changed  → patch to NEW value
   field in OLD, absent in NEW  → DELETE from live (Helm owned it, now removed)
   field in LIVE only (not OLD) → LEFT ALONE (Helm never owned it)
```

**The decision logic, field by field:** if a field is present in both old and new with different values, Helm patches it to the new value. If a field was in the old manifest but is *absent* from the new one, Helm concludes it previously owned that field and now wants it gone, so it **deletes** it from the live object — this is the source of the "I removed a key from values and it deleted my resource/field" surprise. If a field exists only in the live object and Helm never set it (added by another controller or `kubectl edit`), Helm leaves it alone because it's not in the old reference. For lists, *strategic* merge uses the field's patch-merge-key (e.g. container `name`) to merge by key rather than replacing the whole list — but only for types with strategic-merge metadata; for arbitrary lists (and for Helm values arrays, separately) it's wholesale replacement.

**Where it surprises:** (1) removing a managed field deletes it live, even if you only meant to stop *defaulting* it. (2) `kubectl edit` on a Helm-managed field is *not* always preserved — if the field is in Helm's old manifest, the next upgrade's three-way merge can revert your manual edit (the live value loses to new). (3) Server-side defaulting and mutating webhooks inject fields into live that aren't in old/new, and most are correctly left alone — but a chart that *also* sets a webhook-mutated field can thrash. (4) Resources you want to survive removal need `helm.sh/resource-policy: keep`, because absent-from-new otherwise means delete. The expert takeaway: Helm 3's three-way merge dramatically reduced "Helm clobbered my change" by adding live state, but it did not make Helm hands-off — it makes Helm *authoritative over the fields it has ever set*, so the durable discipline is "make all changes through the chart, and use `helm diff` to see exactly which fields the merge will patch or delete before you apply."

#### Q82. [Practical] Design a Helm-based disaster-recovery runbook: a cluster is gone, you have the Git repo and chart versions — how do you rebuild deterministically, and what's missing from Helm alone?
The design premise is that Helm + GitOps can reconstruct the *desired-state spec* of every workload deterministically, but Helm manages specs, not data — so a credible DR plan pairs deterministic redeploy with a separate data-restore path, and the runbook makes the seam explicit.

```
 RECOVER (spec)                          RESTORE (state — NOT Helm)
 Git repo (pinned chart vers + values)   Velero / volume snapshots / DB backups
        │                                        │
 provision new cluster (IaC)             restore PVCs/PV data, external DB
        │                                        │
 install platform release (CRDs, mesh)   re-point charts at restored storage
        │                                        ▼
 GitOps reconciles N HelmReleases ──────► workloads come up on restored data
```

**The deterministic-rebuild steps:** (1) provision a fresh cluster from infrastructure-as-code. (2) Install the **platform/operator release first** (CRDs, ingress, cert-manager, External Secrets Operator) because workloads depend on those cluster-scoped resources — install order matters and is part of the runbook. (3) Point the GitOps controller at the repo; because chart versions are pinned by digest in `Chart.lock` and images are pinned by digest, the reconcile produces byte-identical manifests to what was running — *that* is the reproducibility payoff of the pinning discipline (Q75). (4) Secrets are re-materialized by the External Secrets Operator pulling from Vault/ASM, *not* from the dead cluster's release secrets — which is exactly why secret material must live outside the render path.

**What Helm alone does NOT recover, and must be designed separately:** (1) **Persistent data** — Helm recreates the PVC *spec* but not the bytes; you need Velero, CSI volume snapshots, or database backups restored independently, then the workloads re-attached. (2) **Hook side effects** — a fresh install re-runs `pre-install`/migration hooks against an *empty* schema, which is correct for a clean cluster but wrong if you're restoring an existing database — the runbook must sequence "restore DB, then deploy with migrations set to no-op/verify" to avoid double-migrating. (3) **The release secrets themselves** — if you used CLI Helm (not Argo templating), `helm history` is gone with the old cluster; reproducibility comes from Git + pinned versions, not from recovering etcd. (4) **Cluster-scoped state** outside charts (custom webhooks, externally-created CRs). The expert framing: Helm makes the *spec* layer of DR deterministic and fast *if* you've removed non-determinism (pinned digests, externalized secrets, committed `Chart.lock`); the runbook's hard parts are the *stateful* seams — data restore ordering, migration idempotency, and platform-release install order — none of which Helm owns. A DR plan that says "just `helm install` everything" is incomplete precisely because it conflates spec recovery with state recovery.

#### Q83. [Behavioral] (STAR) Describe a time you had to make a judgment call between the "correct" Helm architecture and shipping on a deadline, and how you handled the resulting technical debt.
**Situation:** We were onboarding a major customer with a hard contractual go-live, and their environment needed a new service deployed alongside our existing umbrella chart. The architecturally correct move was to extract the new service into its own chart with a shared library chart — but doing that properly meant refactoring the umbrella's shared helpers, which carried real regression risk across the other ~25 services with only nine days of runway.

**Task:** As the engineer owning the deployment, I had to decide whether to do the right long-term architecture under deadline pressure (risking the go-live) or ship pragmatically and manage the debt deliberately. The judgment call was mine, and I'd be on call for whatever I shipped.

**Action:** I chose the pragmatic path but made the debt *explicit and bounded* rather than silent. I added the new service into the existing umbrella as a subchart with a `condition` flag so it was isolated enough to disable instantly if it misbehaved, and I gated the upgrade with `helm diff` to prove the change touched only the new service's objects and nothing else in the umbrella. I documented the shortcut in an ADR — "added to umbrella for go-live, scheduled for extraction in Q3" — with the specific risks called out (shared-helper coupling, all-or-nothing rollback) and a tracked ticket, so it wasn't debt that quietly rotted. I also wrote the go-live runbook so on-call had a one-flag disable path. After go-live, I led the extraction in the next quarter, validating each step with a zero-change `helm diff` against the umbrella output.

**Result:** We hit the contractual go-live with no incidents, the `condition` flag was never needed but gave us a safety valve, and the documented debt was paid down on schedule rather than forgotten. The reflection I'd offer: the senior skill isn't always choosing the textbook architecture — it's making the trade-off *consciously*, bounding the blast radius (the condition flag, the `helm diff` gate), and converting "we took a shortcut" into a tracked, visible commitment with a date. Undocumented shortcuts under deadline are how god charts are born; a documented, bounded one with a paydown plan is just engineering. What I'd do differently is socialize the ADR with the team earlier, so the decision was collectively owned rather than mine alone under pressure.

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
