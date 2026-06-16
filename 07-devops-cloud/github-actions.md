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
