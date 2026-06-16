# GitLab CI/CD

GitLab CI/CD is GitLab's built-in continuous integration and delivery engine, configured entirely through a single `.gitlab-ci.yml` file living in your repository root. It turns every push, merge request, tag, or schedule into a pipeline of stages and jobs executed by runners, with first-class support for caching, artifacts, environments, DAGs, and built-in security scanning.

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

### Q1. [Theory] What is `.gitlab-ci.yml` and how does GitLab decide to run a pipeline?

`.gitlab-ci.yml` is a YAML file at the root of your repository that declaratively defines your CI/CD pipeline: the `stages`, the `jobs` in each stage, and the `script` each job runs. When GitLab detects a triggering event (a push, a merge request, a tag, a scheduled run, an API call, or a parent-pipeline trigger), it parses this file, validates it, and creates a *pipeline* — a collection of jobs grouped into ordered stages.

The "why" matters: configuration-as-code means your pipeline is versioned alongside your application, peer-reviewed in merge requests, and reproducible. Unlike Jenkins where pipeline logic often lives on a server (or in a Jenkinsfile but still bound to plugins/agents), GitLab keeps everything in the repo and runs jobs in disposable runner environments. A key trade-off is that a malformed `.gitlab-ci.yml` fails the whole pipeline at parse time — use the **CI Lint** tool (`/ci/lint` in the project, or `glab ci lint`) to validate before pushing.

### Q2. [Theory] What is the relationship between pipelines, stages, and jobs?

A **pipeline** is the top-level run. It contains **stages**, which execute sequentially in the order listed under the top-level `stages:` key. Within a stage, all **jobs** run in parallel (subject to runner availability). A stage only starts once every job in the prior stage succeeds.

```
pipeline
 ├── stage: build      (jobs run in parallel)
 │     ├── job: compile
 │     └── job: lint
 ├── stage: test       (starts only after build stage succeeds)
 │     ├── job: unit
 │     └── job: integration
 └── stage: deploy
       └── job: deploy_prod
```

By default GitLab provides three implicit stages — `.pre`, `build`, `test`, `deploy`, `.post` — but you almost always override `stages:` explicitly. The default sequential model is simple and safe, but it can be slow because a stage waits for its *slowest* job; DAG `needs:` (covered later) breaks that constraint.

### Q3. [Practical] Write a minimal pipeline that builds a Node app, runs tests, and echoes a deploy step.

**Problem:** A new Node service needs CI that installs deps, runs the test suite, and has a placeholder deploy that only runs on the default branch.

```yaml
stages:
  - build
  - test
  - deploy

default:
  image: node:20-alpine

build:
  stage: build
  script:
    - npm ci
    - npm run build

unit-test:
  stage: test
  script:
    - npm test

deploy-prod:
  stage: deploy
  script:
    - echo "Deploying $CI_COMMIT_SHORT_SHA to production"
  rules:
    - if: '$CI_COMMIT_BRANCH == $CI_DEFAULT_BRANCH'
```

`npm ci` (not `npm install`) is the production-correct choice: it installs exactly from `package-lock.json` and is faster and reproducible. The `rules:` block gates deploy to the default branch only. `CI_COMMIT_SHORT_SHA` is one of dozens of predefined CI variables.

### Q4. [Theory] What is a GitLab Runner and what are executors?

A **GitLab Runner** is the agent process that picks up jobs from GitLab and executes them. The Runner itself is a small Go binary; the **executor** determines *how* and *where* each job's commands run. Common executors:

- **Docker** — each job runs in a fresh container from the job's `image:`. Most popular: clean, isolated, reproducible.
- **Shell** — runs directly on the runner host's shell. Fast but stateful and insecure (no isolation between jobs).
- **Kubernetes** — spins up a pod per job; ideal for elastic, autoscaling CI on a cluster.
- **Docker Machine / Docker Autoscaler** — autoscales cloud VMs on demand.
- **VirtualBox / Parallels / SSH** — for VM-based or remote execution.

Runners can be **shared** (available to all projects on the instance), **group**, or **project-specific**. The choice of executor is a security and performance decision: Docker/Kubernetes give isolation (critical for shared runners running untrusted forks), while Shell is only acceptable on dedicated, trusted hardware.

---

## 🟡 Intermediate (3–7 yrs)

### Q5. [Theory] Explain artifacts vs cache. When do you use each?

Both pass files between jobs, but they have opposite intents:

| Aspect | Artifacts | Cache |
|---|---|---|
| Purpose | Pass **build outputs** downstream / store deliverables | Speed up jobs by reusing **dependencies** |
| Direction | Forward to later stages, downloadable in UI | Restored at job start, saved at end |
| Guarantee | Reliable, versioned per-pipeline | Best-effort, may be stale or missing |
| Keyed by | The producing job | A `key` you define (e.g. lockfile hash) |
| Lifecycle | `expire_in` retention | Reused across pipelines |

```yaml
build:
  stage: build
  script: npm ci && npm run build
  cache:
    key:
      files:
        - package-lock.json   # cache invalidates when lockfile changes
    paths:
      - node_modules/
    policy: pull-push
  artifacts:
    paths:
      - dist/
    expire_in: 1 week
    reports:
      junit: junit.xml         # surfaced in MR test widget
```

Rule of thumb: **never rely on cache for correctness** — treat it as a performance optimization that can vanish. Use artifacts for anything a downstream job *must* have. A common mistake is caching `node_modules` with `policy: pull-push` in every job; set `policy: pull` in jobs that only read the cache to avoid redundant uploads.

### Q6. [Theory] Compare `rules:` with the legacy `only:`/`except:`. Why is `rules:` preferred?

`only:`/`except:` were the original keywords for controlling when a job runs (by ref, branch, change, etc.). They are now considered legacy. `rules:` is the modern, far more expressive replacement and the two **cannot be mixed in the same job**.

`rules:` evaluates an ordered list of conditions; the first match wins and decides whether the job is added (`when: on_success`/`manual`/`delayed`/`always`) or skipped (`when: never`). It supports `if:` expressions, `changes:`, `exists:`, dynamic `variables:`, and `allow_failure`. This lets you express logic like "run on MRs, or on the default branch, but never on docs-only changes":

```yaml
test:
  stage: test
  script: ./run-tests.sh
  rules:
    - if: '$CI_PIPELINE_SOURCE == "merge_request_event"'
    - if: '$CI_COMMIT_BRANCH == $CI_DEFAULT_BRANCH'
      changes:
        - "src/**/*"
    - when: never
```

The "why": `only/except` couldn't easily combine conditions or set per-rule variables, and led to confusing duplicate-pipeline problems on merge requests. `rules:` gives a single, predictable evaluation model — but order matters, and forgetting a trailing `when: never` (or relying on the implicit default) is a frequent source of jobs running when you didn't expect.

### Q7. [Practical] How do you avoid duplicate pipelines on merge requests (the "double pipeline" problem)?

**Scenario:** Developers open an MR and see two pipelines — one "branch" pipeline and one "merge request" pipeline — wasting runner minutes and cluttering the UI.

**Cause:** A job has rules/triggers for *both* branch pushes and MR events. **Approach:** Adopt **workflow rules** at the top level to define exactly one pipeline type per situation. The canonical pattern:

```yaml
workflow:
  rules:
    - if: '$CI_PIPELINE_SOURCE == "merge_request_event"'
    - if: '$CI_COMMIT_BRANCH && $CI_OPEN_MERGE_REQUESTS'
      when: never            # branch has an open MR -> skip branch pipeline
    - if: '$CI_COMMIT_BRANCH'
```

This says: run MR pipelines for MR events; suppress the redundant branch pipeline when an MR is already open for that branch; otherwise run a normal branch pipeline. **In production** I standardize this `workflow:` block in a shared template (`include:`d everywhere) so every project behaves consistently and we don't burn paid CI minutes.

### Q8. [Practical] How do `include:` and templates help scale CI across many repos?

`include:` pulls external YAML into the pipeline, enabling DRY, centrally-governed CI. Four sources:

- `include:local` — another file in the same repo (split big configs).
- `include:project` + `file` + `ref` — a file from another GitLab project (the heart of a **CI templates repo**).
- `include:remote` — an arbitrary URL.
- `include:template` — GitLab-maintained templates (e.g. `Jobs/SAST.gitlab-ci.yml`).

```yaml
include:
  - project: 'platform/ci-templates'
    ref: v3.2.0           # pin a tag — never float on the templates' default branch
    file: '/templates/docker-build.yml'
  - template: Jobs/SAST.gitlab-ci.yml

build-image:
  extends: .docker-build  # reuse a hidden job defined in the included template
```

Combine with `extends:` (multi-level job inheritance) and **hidden jobs** (prefixed with `.`, never run directly, used only as bases). The trade-off: centralization is powerful but creates coupling — a change to the templates repo can break dozens of pipelines. Always **pin `ref:` to a tag/SHA**, version your templates, and roll out changes deliberately. This is exactly how large orgs (think a platform team serving hundreds of microservices) enforce consistent build, scan, and deploy logic.

### Q9. [Coding] Write a pipeline with parallel matrix jobs and a fan-in stage. State complexity.

**Problem:** Run the same test suite across three Node versions and two databases in parallel (a 3×2 matrix), then a single `report` job that aggregates only after all matrix jobs pass.

```yaml
stages:
  - test
  - report

test-matrix:
  stage: test
  image: node:${NODE_VERSION}
  services:
    - ${DB_IMAGE}
  script:
    - npm ci
    - npm test
  artifacts:
    when: always
    paths:
      - results/${NODE_VERSION}-${DB_IMAGE}.json
  parallel:
    matrix:
      - NODE_VERSION: ["18", "20", "22"]
        DB_IMAGE: ["postgres:16", "mysql:8"]

report:
  stage: report
  script:
    - ./aggregate.sh results/
  needs: ["test-matrix"]   # waits for all 6 matrix instances
```

`parallel:matrix` expands into 6 jobs (one per combination). **Time complexity:** wall-clock is `O(1)` in the number of combinations *given enough runners* — all 6 run concurrently, so total time ≈ slowest single job, versus `O(n)` if run serially. **Space:** artifacts grow linearly, `O(n)`, with the matrix size, so set `expire_in`.

**Edge cases:** if runner concurrency is limited, jobs queue and you lose the parallelism benefit; `needs:` on a matrix job depends on *every* expansion succeeding (unless `allow_failure`); and `artifacts:when: always` ensures partial results are kept even when a matrix cell fails.

### Q10. [Theory] What are environments and deployments? How does GitLab track them?

An **environment** is a named target (e.g. `staging`, `production`, or a dynamic `review/feature-x`) that GitLab uses to track *what is deployed where*. A job becomes a **deployment** when it declares `environment:`. GitLab then records deployment history, shows the currently-deployed commit, links to the live URL, and enables operations like **manual approval gates**, **rollback** (re-deploy a prior successful deployment), and **stop** actions.

```yaml
deploy-review:
  stage: deploy
  script: ./deploy.sh review-$CI_COMMIT_REF_SLUG
  environment:
    name: review/$CI_COMMIT_REF_SLUG
    url: https://$CI_COMMIT_REF_SLUG.review.example.com
    on_stop: stop-review     # tear-down job
  rules:
    - if: '$CI_PIPELINE_SOURCE == "merge_request_event"'

stop-review:
  stage: deploy
  script: ./teardown.sh review-$CI_COMMIT_REF_SLUG
  environment:
    name: review/$CI_COMMIT_REF_SLUG
    action: stop
  when: manual
```

**Review Apps** — ephemeral environments spun up per MR — are built on this and are a flagship GitLab feature: reviewers get a live URL to click through before merging. Protected environments add deployment-approval and access-control on top, which matters for production change management and audit (SOC 2 / compliance).

---

## 🟠 Advanced (8–12 yrs)

### Q11. [Theory] Explain DAG pipelines with `needs:`. What problem do they solve and what are the constraints?

By default stages are strictly sequential — the `test` stage cannot begin until *every* `build` job finishes, even if a particular test only depends on one build artifact. **`needs:`** turns the pipeline into a **Directed Acyclic Graph**: a job starts the instant its specific dependencies complete, ignoring stage boundaries.

```
Stage model (slow):           DAG model (fast):
build ─┐                       build-a ──> test-a ──> deploy
       ├─ (wait for all) ─>            \
build-b┘                                └> build-b ──> test-b
```

```yaml
test-a:
  stage: test
  needs: ["build-a"]        # starts as soon as build-a is done
deploy:
  stage: deploy
  needs:
    - job: test-a
      artifacts: true       # pull only test-a's artifacts
```

The win is shorter critical paths and faster feedback on large pipelines. Constraints: the graph must be **acyclic**; a job can `needs:` at most ~50 jobs (configurable limit); `needs:` jobs must appear earlier in the DAG (you can't depend on a later stage); and by default a `needs:` job also pulls the named job's artifacts (set `artifacts: false` to skip). An empty `needs: []` makes a job start immediately at pipeline creation, ignoring stages entirely — useful for a fast smoke check.

### Q12. [Practical] A pipeline is slow and flaky on a shared Kubernetes runner fleet. How do you diagnose and fix it?

**Approach — measure first.** Use the pipeline's job-timing view and the DAG visualization to find the critical path. Common culprits and fixes:

1. **Sequential stages stalling on one slow job** → introduce `needs:` to parallelize the independent branches.
2. **No dependency caching** → add a `cache:` keyed on the lockfile so `npm ci`/`mvn`/`pip` don't re-download every run; consider a pull-through registry mirror.
3. **Cold container pulls** → pre-pull base images onto nodes or use a registry mirror; pin small images (`-alpine`/`-slim`).
4. **Flakiness from shared state** → ensure Docker/Kubernetes executor (not Shell) so each job is isolated; pin `services:` versions; add `retry:` with `when: [runner_system_failure, stuck_or_timeout_failure]` (not blanket retries that mask real bugs).
5. **Runner saturation** → tune `concurrent` and Kubernetes pod resource requests/limits; add autoscaling.

**What I'd actually do in production:** split into a fast "lint + unit" DAG branch that gives feedback in <2 minutes, gate the expensive integration/e2e suite behind `needs:` and `rules:` (only on MRs touching relevant paths), and add `interruptible: true` so superseded pipelines auto-cancel when a new commit lands — this alone reclaims a huge amount of wasted compute on active branches.

### Q13. [Theory] Walk through GitLab's built-in security scanning (SAST, DAST, dependency, container, secret detection).

GitLab ships security scanners as includable CI templates that run as ordinary jobs and emit standardized JSON **reports** consumed by the MR security widget and the Vulnerability Report dashboard:

- **SAST** (`Jobs/SAST.gitlab-ci.yml`) — static analysis of source for code-level flaws; auto-detects language and runs the right analyzers (Semgrep-based in current versions).
- **Dependency Scanning** — finds known CVEs in your declared dependencies (lockfiles).
- **Container Scanning** — scans built images for vulnerable OS packages (Trivy-based).
- **DAST** — dynamic analysis: actually attacks a *running* deployed app (ZAP-based), so it needs a live environment.
- **Secret Detection** — scans the repo/history for committed credentials.
- **License Compliance** — flags disallowed OSS licenses.

```yaml
include:
  - template: Jobs/SAST.gitlab-ci.yml
  - template: Jobs/Secret-Detection.gitlab-ci.yml
  - template: Jobs/Dependency-Scanning.gitlab-ci.yml
  - template: Security/Container-Scanning.gitlab-ci.yml
```

Reports surface as `artifacts:reports:sast` etc. The strategic value is **shift-left security**: findings appear directly in the MR diff before merge, and security policies (Scan Execution / Scan Result policies) can *enforce* that a vulnerable MR is blocked from merging. Edition note: SAST/Secret Detection basics are available broadly, but rich features (the dashboard, MR diff of new-vs-existing findings, DAST, merge-request approval policies) require **Ultimate** tier — a frequent gotcha when teams expect everything on Free.

### Q14. [Theory] What is Auto DevOps and when is it the right (or wrong) choice?

**Auto DevOps** is GitLab's zero-config, opinionated pipeline that auto-detects your stack (via Heroku-style buildpacks / Cloud Native Buildpacks), then automatically builds, tests, scans (SAST/DAST/dependency/container), packages into a container, and deploys to Kubernetes using a managed Helm chart — including review apps, canary/incremental rollouts, and monitoring. You enable it with a toggle (or an empty `.gitlab-ci.yml`).

**When it's right:** greenfield projects, prototypes, teams without CI expertise, or standardizing many simple services fast — you get a production-grade pipeline "for free." **When it's wrong:** complex monorepos, bespoke build/deploy needs, or anything where the opinionated defaults fight your architecture. The good news is Auto DevOps is just a set of `include:`d templates, so you can **adopt it incrementally** — enable it, then override individual jobs (e.g. `extends:` and customize the `build` or `production` job) rather than going all-or-nothing. In practice mature orgs outgrow full Auto DevOps but borrow its templates as a baseline.

### Q15. [Coding] Build a multi-project (parent-child / cross-project) trigger pipeline. State trade-offs.

**Problem:** A monorepo's parent pipeline should dynamically generate and trigger a child pipeline for only the changed service, and also trigger a downstream deploy pipeline in a *separate* project after build.

```yaml
stages:
  - generate
  - triggers

# 1) Child pipeline generated dynamically as an artifact
generate-config:
  stage: generate
  script:
    - ./gen-pipeline.sh > generated-child.yml
  artifacts:
    paths:
      - generated-child.yml

run-child:
  stage: triggers
  needs: ["generate-config"]
  trigger:
    include:
      - artifact: generated-child.yml
        job: generate-config
    strategy: depend          # parent waits on & inherits child status

# 2) Cross-project downstream trigger
deploy-downstream:
  stage: triggers
  trigger:
    project: 'ops/deployment-pipeline'
    branch: main
    strategy: depend
```

**Complexity:** dynamic child pipelines keep config `O(changed-services)` instead of one giant static file — the parent generates only the YAML it needs, so pipeline size scales with *changes*, not total repo size. **Trade-offs:** `strategy: depend` couples statuses (parent fails if child fails) which is usually what you want for gating, but it lengthens the critical path; omitting it makes the trigger fire-and-forget. **Edge cases:** generated YAML must itself be valid (lint it in `generate-config`); cross-project triggers need a token/permissions and can create cascading failures across team boundaries — add clear ownership and avoid trigger cycles (GitLab detects but you shouldn't design them).

---

## 🔴 Expert (15+ yrs)

### Q16. [Theory] Compare GitLab CI vs GitHub Actions vs Jenkins across architecture, extensibility, and operational cost.

```
                GitLab CI/CD          GitHub Actions        Jenkins
Config          .gitlab-ci.yml        .github/workflows/*   Jenkinsfile (Groovy)
                (single, YAML)         (multi, YAML)         + plugin sprawl
Execution unit  jobs/stages on        jobs/steps using      stages on agents,
                runners (executors)    marketplace actions   plugin-driven
Reuse model     include/extends/      reusable workflows +  shared libraries +
                templates             composite actions     plugins
Built-in        SAST/DAST/dep/        via marketplace       via plugins
security        container (native)    actions               (varies)
Hosting         SaaS or self-managed  SaaS (+ self-hosted    self-hosted (you
                (same product)         runners)              operate everything)
Ecosystem       integrated DevOps     huge marketplace      largest plugin
                platform              of actions            ecosystem (aging)
Ops burden      low (esp. SaaS)       low (SaaS)            high (you patch,
                                                            scale, secure it)
```

The strategic read: **GitLab** wins on *integration* — SCM, CI/CD, registry, security, and environments are one product with one permission model, reducing tool sprawl and giving native security scanning. **GitHub Actions** wins on *ecosystem velocity* — the marketplace means there's an action for everything, ideal where the org already lives in GitHub. **Jenkins** wins on *flexibility and incumbency* — it can do literally anything via plugins and runs anywhere, but you carry the full operational, security-patching, and plugin-compatibility burden, and Groovy pipelines are harder to govern at scale. For a new platform team in 2026 I'd default to GitLab or GitHub Actions; I'd keep Jenkins only where legacy plugins or air-gapped/regulatory constraints demand it.

### Q17. [Theory] How would you architect secrets management and supply-chain security for GitLab CI at enterprise scale?

Layer defenses:

- **No long-lived secrets in CI variables.** Prefer **OIDC ID tokens** (`id_tokens:`) so jobs exchange a short-lived GitLab-signed JWT for cloud credentials (AWS/GCP/Azure/Vault) — no static cloud keys stored anywhere.
- **HashiCorp Vault integration** (`secrets:` keyword) for dynamic, leased secrets scoped per job.
- **Protected + masked variables**, scoped to **protected branches/tags** only, so MRs from forks can never read production secrets.
- **Runner isolation:** untrusted/fork pipelines run on a separate, network-restricted runner fleet (Docker/Kubernetes executor, never Shell) so a malicious `.gitlab-ci.yml` can't reach prod credentials or the internal network.
- **Supply chain:** pin `include: ref:` to tags/SHAs; generate and store **SBOMs**; sign artifacts/images (Cosign); enforce **Scan Result Policies** that block merges with new critical vulns; require **CODEOWNERS** review on `.gitlab-ci.yml` itself (it is executable code).
- **Provenance/audit:** protected environments with approval gates, immutable audit events, and pipeline-level compliance frameworks.

The core principle: treat the pipeline as a privileged, internet-exposed execution surface. The classic breach vector is a poisoned dependency or a malicious fork MR exfiltrating credentials — mitigations above (ephemeral creds, fork isolation, signed/pinned includes) directly target it.

### Q18. [Behavioral] Tell me about a time you led a CI/CD migration or major pipeline overhaul. How did you manage risk and the team?

Use a STAR structure. **Situation/Task:** e.g., "We had 200+ services on aging Jenkins with snowflake jobs, 40-minute average pipelines, and recurring plugin-CVE fire drills; mandate was to migrate to GitLab CI in two quarters without freezing delivery." **Action — what good leadership looks like:**

- Built a **golden-path templates repo** (versioned `include:`s for build/test/scan/deploy) so teams adopted a paved road instead of copy-pasting.
- Ran a **strangler-fig migration**: piloted 3 volunteer teams, measured pipeline time and failure rate, iterated, then onboarded in waves — never a big-bang cutover.
- Made the move *attractive*, not mandated: faster pipelines (DAG + caching), free security scanning, review apps — so teams *wanted* to migrate.
- Invested in **enablement**: office hours, a migration runbook, and a CI-lint pre-merge check.

**Result:** quantify — "median pipeline dropped from 40 to 11 minutes, security findings shifted left (caught in MR not prod), and Jenkins decommissioned a quarter early." The behavioral signal interviewers want: you balanced *technical strategy* (templates, DAG, security) with *change management* (incremental, opt-in-then-default, enablement) and measured outcomes — not that you personally wrote all the YAML.

### Q19. [Practical] Production incident: a green pipeline deployed a broken release. How do you respond and prevent recurrence?

**Immediate response:** use GitLab's **environment rollback** to redeploy the last known-good deployment (one click / `environment` history), or trigger the rollback job. Communicate via incident channel; capture the failing pipeline ID and deployed SHA for the postmortem.

**Root cause patterns:** the pipeline was green because (a) tests didn't cover the broken path, (b) a flaky test was `allow_failure: true` and masked a real failure, (c) the deploy job didn't run a real **health/smoke check** before declaring success, or (d) caching served a stale artifact.

**Prevention I'd put in place:**
- A post-deploy **smoke-test job** with `needs:` on deploy that fails the pipeline (and auto-rolls-back) if health checks fail.
- **Progressive delivery**: canary/incremental rollout via `environment` + feature flags so a bad release hits 5% before 100%.
- Remove `allow_failure: true` on anything load-bearing; quarantine flaky tests explicitly rather than silently ignoring.
- Make production deploy a **protected environment with manual approval** for high-risk services, and require the smoke check to pass before the manual gate unlocks.

The meta-point at this level: "green" must mean "verified in the target environment," not "scripts exited 0." Pipelines should encode operational guarantees, not just build success.

### Q20. [Theory] How do `interruptible`, `resource_group`, and `rules:` interact to control concurrency and prevent race conditions in deploys?

These three keywords together solve real production concurrency hazards:

- **`interruptible: true`** lets GitLab auto-cancel a running job/pipeline when a newer commit supersedes it (with **Auto-cancel redundant pipelines** enabled). Great for *build/test* on active branches — saves compute and gives faster feedback. Never mark a *deploy* job interruptible if cancelling mid-deploy would leave a bad state.
- **`resource_group: production`** enforces that only **one** job in that named group runs at a time across all pipelines — serializing deployments so two pipelines can't deploy to prod simultaneously and clobber each other. You can tune `process_mode` (`unordered`, `oldest_first`, `newest_first`).
- **`rules:`** decides *whether* the deploy job exists at all (branch, manual gate, path changes).

```yaml
deploy-prod:
  stage: deploy
  resource_group: production    # serialize: no concurrent prod deploys
  interruptible: false          # never cancel a half-done deploy
  environment:
    name: production
  script: ./deploy.sh
  rules:
    - if: '$CI_COMMIT_BRANCH == $CI_DEFAULT_BRANCH'
      when: manual
```

The interaction matters because without `resource_group`, two quick merges can race two concurrent prod deploys; without correct `interruptible` settings you either waste compute (build) or corrupt state (deploy). Designing this correctly is the difference between a pipeline that *looks* fine under low load and one that survives a busy release day.

---

## ✅ Key Takeaways

- `.gitlab-ci.yml` is configuration-as-code: versioned, reviewed, reproducible; lint it before pushing.
- Stages run sequentially, jobs within a stage run in parallel; **`needs:` (DAG)** breaks stage boundaries to shorten the critical path.
- **Artifacts = correctness** (reliable, forward-passed deliverables); **cache = performance** (best-effort, may be stale).
- Prefer **`rules:`** over legacy `only/except`; use a top-level **`workflow:`** block to avoid duplicate MR pipelines.
- **`include:` + `extends:` + hidden jobs + templates repo** is how you DRY and govern CI at scale — always pin `ref:` to a tag/SHA.
- **Environments/deployments** give history, rollback, review apps, and protected-environment approval gates.
- GitLab ships **native security scanning** (SAST/DAST/dependency/container/secret) as includable templates that surface in MRs — shift-left by default (rich features need Ultimate).
- For prod safety, combine **`resource_group`** (serialize deploys), **`interruptible`** (auto-cancel stale runs), OIDC/Vault secrets, and post-deploy smoke checks with rollback.

## ⚠️ Common Pitfalls

- Mixing `rules:` with `only/except` in the same job — not allowed; pick one (use `rules:`).
- Relying on **cache** for files a downstream job *must* have — use artifacts; cache can silently vanish.
- Forgetting `workflow:` rules → duplicate branch+MR pipelines burning runner minutes.
- Floating `include: ref:` on a branch → an upstream template change silently breaks many pipelines.
- Using the **Shell executor on shared runners** → no isolation; fork MRs can read secrets / poison the host.
- `allow_failure: true` on load-bearing jobs masking real failures → "green" pipelines that ship bugs.
- Storing long-lived cloud keys in CI variables → use **OIDC `id_tokens:`** or Vault; mask + protect + scope variables.
- No `resource_group` on prod deploys → concurrent deploys race and corrupt state.
- Treating "scripts exited 0" as "release verified" → add post-deploy smoke/health checks before declaring success.

## 📚 Further Reading

- GitLab Docs — *CI/CD YAML syntax reference* (`docs.gitlab.com/ee/ci/yaml/`) — the authoritative keyword reference.
- GitLab Docs — *Pipeline architecture, `needs` / DAG, parent-child pipelines* (`docs.gitlab.com/ee/ci/pipelines/`).
- GitLab Docs — *Application security: SAST, DAST, Dependency & Container Scanning, Secret Detection* (`docs.gitlab.com/ee/user/application_security/`).
- GitLab Docs — *Auto DevOps* (`docs.gitlab.com/ee/topics/autodevops/`).
- *The DevOps Handbook* (2nd ed.) — Kim, Humble, Debois, Willis — CI/CD principles, flow, and security as a first-class concern.
- *Continuous Delivery* — Jez Humble & David Farley — foundational deployment-pipeline and release-safety patterns.
