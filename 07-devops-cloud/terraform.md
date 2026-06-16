# Terraform & Infrastructure as Code (IaC)

A staff-level interview guide to Terraform and IaC: HCL, providers, state, modules, lifecycle, drift, importing, testing, OpenTofu, and the production pitfalls that separate people who *use* Terraform from people who *operate* it at scale. Current through 2026 (Terraform 1.x line, OpenTofu 1.8/1.9).

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

### Q1. [Theory] What is Infrastructure as Code, and how does declarative differ from imperative?

Infrastructure as Code (IaC) is the practice of defining and managing infrastructure — networks, VMs, load balancers, DNS, IAM — in machine-readable configuration files that are version-controlled, reviewed, and applied automatically, rather than clicking through a console or running ad-hoc scripts.

The key distinction:

- **Imperative** ("how"): you write the exact sequence of steps. A shell script that runs `aws ec2 run-instances`, then `aws ec2 create-tags`, then waits, then attaches a volume. You own the ordering, idempotency, and error handling.
- **Declarative** ("what"): you describe the *desired end state*, and the tool computes the diff between current and desired, then figures out the steps. Terraform, CloudFormation, and Kubernetes manifests are declarative.

Terraform is declarative. The "why" matters: declarative tools give you idempotency for free (applying twice yields the same result), a reviewable plan before changes, and convergence — the tool reconciles reality toward your spec. The trade-off is loss of fine-grained control over ordering and a learning curve around how the engine resolves dependencies.

### Q2. [Theory] What is HCL and what are its core building blocks?

HCL (HashiCorp Configuration Language) is the declarative DSL Terraform uses. It is a typed, JSON-compatible language designed to be human-readable. The core blocks:

```hcl
terraform {                          # settings: required version, providers, backend
  required_version = ">= 1.6.0"
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 5.0" }
  }
}

provider "aws" { region = "us-east-1" }   # configures a plugin

variable "instance_type" {           # input
  type    = string
  default = "t3.micro"
}

resource "aws_instance" "web" {      # a managed object
  ami           = data.aws_ami.al2.id
  instance_type = var.instance_type
}

data "aws_ami" "al2" {               # read-only lookup of existing infra
  most_recent = true
  owners      = ["amazon"]
}

output "public_ip" {                 # exported value
  value = aws_instance.web.public_ip
}

locals { name_prefix = "prod-web" }  # named expressions, computed once
```

`resource` *manages* an object (create/update/delete), while `data` only *reads*. `locals` reduce repetition and `output` exposes values to callers or the CLI.

### Q3. [Theory] What are providers and resources?

A **provider** is a plugin that translates Terraform's declarative config into API calls for a specific platform (AWS, Azure, GCP, Kubernetes, Datadog, GitHub, even an internal API). Providers are distributed via the Terraform Registry and pinned by version. A **resource** is a single managed object exposed by a provider — `aws_s3_bucket`, `google_compute_instance`, `kubernetes_deployment`. Each resource type has a schema of arguments (inputs) and attributes (computed outputs). Terraform tracks every resource it manages in state, so it knows what it owns and can compute diffs. A single configuration can use many providers, and you can alias the same provider for multiple regions/accounts.

### Q4. [Practical] Walk through the plan → apply → destroy lifecycle.

```
 ┌──────────┐   terraform init    ┌─────────────────────────────┐
 │  *.tf    │ ──────────────────▶ │ download providers + backend │
 └──────────┘                     └─────────────────────────────┘
       │ terraform plan
       ▼
 ┌───────────────────────────────────────────────┐
 │ refresh state ─▶ compare desired vs actual ─▶  │
 │ emit diff: + create, ~ update, - destroy,      │
 │            -/+ replace                          │
 └───────────────────────────────────────────────┘
       │ terraform apply  (reuses saved plan or re-plans)
       ▼
 ┌───────────────────────────────────────────────┐
 │ execute graph in dependency order, update state│
 └───────────────────────────────────────────────┘
       │ terraform destroy
       ▼  tears down everything in state (reverse order)
```

In production you almost always run `terraform plan -out=tfplan` then `terraform apply tfplan` so the applied changes are *exactly* what was reviewed — no race where reality shifts between plan and apply. `init` is required after changing providers, modules, or backend config. `destroy` is rare in prod and usually gated behind heavy guardrails or reserved for ephemeral environments.

### Q5. [Coding] Provision an S3 bucket with versioning and encryption.

**Problem:** Create a private S3 bucket with versioning enabled, server-side encryption (SSE-KMS), and public access blocked. Modern AWS provider (v4+) split these into separate resources.

```hcl
resource "aws_s3_bucket" "logs" {
  bucket = "acme-app-logs-prod"
  tags   = { Environment = "prod", Owner = "platform" }
}

resource "aws_s3_bucket_versioning" "logs" {
  bucket = aws_s3_bucket.logs.id
  versioning_configuration { status = "Enabled" }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "logs" {
  bucket = aws_s3_bucket.logs.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = aws_kms_key.logs.arn
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_public_access_block" "logs" {
  bucket                  = aws_s3_bucket.logs.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_kms_key" "logs" {
  description             = "KMS key for app logs bucket"
  enable_key_rotation     = true
  deletion_window_in_days = 30
}
```

**Edge cases:** S3 bucket names are globally unique — a clash fails apply. `bucket_key_enabled = true` reduces KMS request costs dramatically on high-volume buckets. Pre-v4 providers used inline `versioning {}` / `server_side_encryption_configuration {}` blocks; migrating to the split resources requires `terraform import` or a state move, not just an edit.

### Q6. [Theory] What is Terraform state and why does it exist?

State is a JSON file (`terraform.tfstate`) that maps your configuration's resources to real-world objects (resource ID, attributes, dependencies, metadata). It exists because Terraform must answer: "Of everything that exists, which objects do *I* manage, and what are their current attributes?" Without state, Terraform would have to query and reconcile the entire cloud account on every run — slow, ambiguous, and impossible for resources with no natural way to tag ownership.

State serves three jobs: (1) **mapping** config to real IDs, (2) **performance** (caching attributes so plan doesn't re-read everything), and (3) **dependency tracking** for correct create/destroy ordering. Because it can contain secrets (DB passwords, private keys) in plaintext, state must be treated as sensitive and stored in an encrypted, access-controlled backend — never committed to Git.

### Q7. [Practical] How do you reference one resource's output in another?

You use interpolation referencing the resource's address: `<type>.<name>.<attribute>`. This also creates an *implicit dependency* — Terraform sees the reference and orders creation correctly.

```hcl
resource "aws_vpc" "main" { cidr_block = "10.0.0.0/16" }

resource "aws_subnet" "app" {
  vpc_id            = aws_vpc.main.id          # implicit dependency on the VPC
  cidr_block        = "10.0.1.0/24"
  availability_zone = "us-east-1a"
}
```

Prefer implicit dependencies over `depends_on`. Reach for explicit `depends_on` only when there is a real ordering requirement Terraform can't infer from data flow — e.g. an IAM policy must exist before an app that uses it boots, but the resource attributes don't reference each other.

---

## 🟡 Intermediate (3–7 yrs)

### Q8. [Theory] count vs for_each — when do you use each, and why does it matter?

Both create multiple instances of a resource, but they index differently and that index lives in state:

- **`count`** produces a list indexed by integer: `aws_instance.web[0]`, `web[1]`. Use it for *identical, order-independent* copies or as a conditional toggle (`count = var.enabled ? 1 : 0`).
- **`for_each`** produces a map (or set) indexed by string key: `aws_instance.web["api"]`, `web["worker"]`. Use it when instances have *stable identities*.

The "why" is the killer detail in interviews: with `count`, if you remove an element from the *middle* of the list, every element after it shifts index. Terraform sees this as "destroy and recreate everything after the removed item" because identity is positional. With `for_each`, each instance is keyed by a stable string, so removing one key only destroys that one. **Rule of thumb: use `for_each` for anything where membership changes over time; reserve `count` for on/off toggles and truly homogeneous fleets.**

```hcl
# Fragile: removing "b" reindexes "c"
variable "names" { default = ["a", "b", "c"] }
resource "aws_iam_user" "u" {
  count = length(var.names)
  name  = var.names[count.index]
}

# Stable: each user keyed by its own name
resource "aws_iam_user" "u" {
  for_each = toset(var.names)
  name     = each.value
}
```

### Q9. [Theory] Explain remote backends, state locking, and how they prevent corruption.

A **backend** determines where state is stored and how operations execute. The default `local` backend writes `terraform.tfstate` to disk — fine for a solo demo, disastrous for a team. A **remote backend** (S3, GCS, Azure Blob, Terraform Cloud/HCP, or an OpenTofu-compatible store) centralizes state so the whole team and CI share one source of truth, encrypted at rest with versioned history for rollback.

**State locking** prevents two concurrent `apply`s from corrupting state via a last-writer-wins race. The backend acquires an exclusive lock before writing.

```
 Engineer A: terraform apply ──▶ acquire lock ──▶ [holds lock] ──▶ write ──▶ release
 Engineer B: terraform apply ──▶ acquire lock ──▶ BLOCKED until A releases
```

```hcl
terraform {
  backend "s3" {
    bucket       = "acme-tfstate"
    key          = "prod/network/terraform.tfstate"
    region       = "us-east-1"
    encrypt      = true
    use_lockfile = true            # native S3 locking (Terraform 1.10+ / OpenTofu)
    # dynamodb_table = "tf-locks"  # legacy locking, pre-1.10
  }
}
```

Historically S3 needed a separate DynamoDB table for locks; since Terraform 1.10 native S3 conditional-write locking (`use_lockfile`) removed that dependency. If a process dies mid-apply, the lock can be left dangling — `terraform force-unlock <ID>` clears it, but only after you confirm no apply is actually running.

### Q10. [Practical] How do you keep secrets out of state and config?

You can't fully keep secrets *out* of state — if a resource attribute is a secret (RDS password, generated TLS key), Terraform stores it in plaintext in state. So the strategy is layered:

1. **Never hardcode** secrets in `.tf` or `.tfvars` committed to Git. Mark variables `sensitive = true` so they're redacted in plan/apply output (not in state).
2. **Source secrets at runtime** from a secret manager — `data "aws_secretsmanager_secret_version"`, Vault provider, or environment variables (`TF_VAR_db_password`). Even better, have the *resource itself* manage rotation (e.g. let RDS manage the master password via `manage_master_user_password = true` so the secret never transits Terraform).
3. **Encrypt state at rest** (SSE-KMS on S3) and lock down read access via IAM — anyone who can read state can read every secret in it.
4. Consider **OpenTofu state encryption** (client-side, see Q23) so the state blob is encrypted before it ever leaves the machine.

The interview-grade point: state is a credential store. Treat read access to the state bucket with the same rigor as access to the secrets themselves.

### Q11. [Coding] Build a reusable VPC module and consume it.

**Problem:** Encapsulate a VPC + public subnets across AZs into a module, parameterized by CIDR and AZ count, then call it twice for dev and prod.

```hcl
# modules/vpc/variables.tf
variable "cidr_block" { type = string }
variable "az_count"   { type = number, default = 2 }
variable "name"       { type = string }

# modules/vpc/main.tf
data "aws_availability_zones" "available" { state = "available" }

resource "aws_vpc" "this" {
  cidr_block           = var.cidr_block
  enable_dns_hostnames = true
  tags                 = { Name = var.name }
}

resource "aws_subnet" "public" {
  for_each          = { for i in range(var.az_count) : i => i }
  vpc_id            = aws_vpc.this.id
  cidr_block        = cidrsubnet(var.cidr_block, 8, each.value)
  availability_zone = data.aws_availability_zones.available.names[each.value]
  tags              = { Name = "${var.name}-public-${each.value}" }
}

# modules/vpc/outputs.tf
output "vpc_id"     { value = aws_vpc.this.id }
output "subnet_ids" { value = [for s in aws_subnet.public : s.id] }
```

```hcl
# environments/prod/main.tf  — consuming the module
module "vpc" {
  source     = "../../modules/vpc"
  name       = "prod"
  cidr_block = "10.0.0.0/16"
  az_count   = 3
}

module "vpc_dev" {
  source     = "../../modules/vpc"
  name       = "dev"
  cidr_block = "10.1.0.0/16"
  az_count   = 2
}
```

**Why `cidrsubnet`:** it carves child CIDRs deterministically from the parent, avoiding hand-computed overlaps. **Edge cases:** requesting more AZs than the region offers throws an index-out-of-range error — validate `az_count` against `length(data.aws_availability_zones.available.names)` or use a `validation` block. Module versioning matters: for shared modules pull from a registry or Git tag (`source = "git::https://...//modules/vpc?ref=v1.4.0"`) so callers pin a version.

### Q12. [Theory] variables vs locals vs outputs — clarify the roles.

- **`variable`** = an *input* to a module/root config, set by the caller (CLI `-var`, `.tfvars`, env `TF_VAR_*`, or module arguments). Supports types, defaults, `sensitive`, and `validation` blocks. Think function parameter.
- **`local`** = a *named intermediate value* computed inside the config, not settable from outside. Use to DRY up repeated expressions or build composite values (`local.common_tags`). Think local variable.
- **`output`** = an *exported value* from a module to its caller, or surfaced on the CLI after apply. Outputs of one module become inputs to another — this is how modules compose.

The trade-off interviewers probe: over-parameterizing with variables makes modules hard to use (dozens of knobs); under-parameterizing makes them inflexible. Good modules expose a small, opinionated set of variables and compute the rest with locals.

### Q13. [Practical] Your `terraform plan` shows changes nobody made. What is drift and how do you handle it?

**Drift** is divergence between Terraform state and real infrastructure — someone changed a security group rule in the console, an auto-scaler resized something, or another tool mutated a tag. On the next `plan`, Terraform refreshes state, sees reality differs from config, and proposes to *revert* it back to config.

How I handle it in production:

1. **Detect proactively** — run `terraform plan -detailed-exitcode` on a schedule in CI (exit code `2` = drift). Surface it to a dashboard/Slack rather than discovering it during an unrelated change.
2. **Triage the source.** Was the manual change legitimate (emergency hotfix) or unauthorized? If legitimate, codify it into `.tf` so apply becomes a no-op. If unauthorized, let apply revert it and tighten IAM so humans can't mutate Terraform-managed resources.
3. **Use `ignore_changes`** in `lifecycle` for attributes intentionally managed elsewhere (e.g. `desired_count` managed by an autoscaler, or tags injected by a separate compliance tool).

```hcl
resource "aws_autoscaling_group" "app" {
  # ...
  lifecycle {
    ignore_changes = [desired_capacity]   # autoscaler owns this; don't fight it
  }
}
```

The cultural fix matters most: drift is usually a symptom that humans have write access they shouldn't. The long-term answer is to make the console read-only for managed resources and route all changes through the pipeline.

### Q14. [Coding] Conditionally create a resource and a dynamic block.

**Problem:** Create a CloudWatch alarm only when monitoring is enabled, and build a variable number of ingress rules from a list using a `dynamic` block.

```hcl
variable "enable_monitoring" { type = bool, default = true }
variable "ingress_ports"     { type = list(number), default = [80, 443] }

# Conditional creation via count
resource "aws_cloudwatch_metric_alarm" "cpu" {
  count               = var.enable_monitoring ? 1 : 0
  alarm_name          = "high-cpu"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  metric_name         = "CPUUtilization"
  namespace           = "AWS/EC2"
  period              = 300
  statistic           = "Average"
  threshold           = 80
}

resource "aws_security_group" "web" {
  name   = "web-sg"
  vpc_id = var.vpc_id

  dynamic "ingress" {                     # generate N ingress blocks
    for_each = toset(var.ingress_ports)
    content {
      from_port   = ingress.value
      to_port     = ingress.value
      protocol    = "tcp"
      cidr_blocks = ["0.0.0.0/0"]
    }
  }
}
```

**Edge case / gotcha:** because the alarm uses `count`, you must reference it as `aws_cloudwatch_metric_alarm.cpu[0]` — and that reference is only valid when the count is 1. To safely reference a possibly-absent resource elsewhere, use `one(aws_cloudwatch_metric_alarm.cpu[*].arn)` which returns the single element or `null`. **Security note:** `0.0.0.0/0` ingress is shown for brevity; in production restrict to known CIDRs or a load-balancer SG.

### Q15. [Theory] How does Terraform build and use its dependency graph?

Terraform parses all config, then constructs a **directed acyclic graph (DAG)** where nodes are resources/data sources/modules and edges are dependencies. Edges come from (a) implicit references (`aws_subnet.app.vpc_id` referencing `aws_vpc.main.id`), (b) explicit `depends_on`, and (c) provider/module relationships. It then walks the graph: independent nodes are created/updated **in parallel** (default 10 concurrent operations, tunable with `-parallelism=N`), while dependent nodes wait for their predecessors.

```
        aws_vpc.main
         /        \
 aws_subnet.a   aws_subnet.b      <- created in parallel
        \          /
       aws_instance.web           <- waits for both subnets
```

For `destroy`, Terraform walks the graph in **reverse** so dependents are torn down before their dependencies. A cycle (A depends on B depends on A) is a hard error — `terraform graph | dot -Tsvg` visualizes it. Understanding the DAG explains why removing a reference can reorder operations and why `depends_on` should be minimal: every artificial edge reduces parallelism.

### Q16. [Practical] Compare strategies for managing multiple environments (dev/staging/prod).

Four common patterns, with the trade-offs I weigh:

1. **Workspaces** (`terraform workspace new prod`) — one config, multiple state files keyed by workspace name. *Pro:* DRY, no duplicated code. *Con:* a single set of `.tf` files for all envs means a risky change is one `terraform workspace select prod` away from prod; no per-env provider/backend differences. Best for *minor* variations of identical infra.
2. **Directory per environment** (`environments/{dev,staging,prod}/`) each with its own backend and `.tfvars`, calling shared modules. *Pro:* strong blast-radius isolation, per-env backends/accounts, explicit. *Con:* some duplication of root config. **This is the most common production choice.**
3. **Terragrunt** — a wrapper that keeps backends/providers DRY across directories while preserving isolation. Popular at scale to eliminate the boilerplate of pattern 2.
4. **Separate repos/state per env + CI promotion** — maximum isolation, used in regulated/large orgs.

My default recommendation: **directory-per-environment calling versioned modules**, because the most expensive mistakes come from accidentally applying dev changes to prod, and physical separation of state + accounts makes that mistake structurally hard. Workspaces are a trap when teams use them as a poor man's environment separation across accounts.

### Q17. [Theory] What does `terraform import` do and when do you use it?

`terraform import` brings *pre-existing* infrastructure (created manually or by another tool) under Terraform management by writing its current state into the state file. You still must author matching `.tf` config — import only populates state, it does not generate config (classically). Workflow: write the resource block, run `terraform import aws_instance.web i-0abc123`, then `terraform plan` until the diff is empty (meaning your config faithfully describes reality).

Since **Terraform 1.5**, there's a declarative `import` block plus `terraform plan -generate-config-out=gen.tf`, which auto-generates a starting config for imported resources — a huge improvement for bulk brownfield adoption:

```hcl
import {
  to = aws_instance.web
  id = "i-0abc123"
}
```

Use it when adopting a legacy estate into IaC, recovering after someone created resources out-of-band, or consolidating tooling. The risk: importing without carefully matching config causes the first apply to destroy/modify production resources — always plan to a clean diff first.

---

## 🟠 Advanced (8–12 yrs)

### Q18. [Practical] State got corrupted / a resource needs surgery. Walk through state manipulation safely.

State surgery is sometimes unavoidable: a resource was renamed, moved into a module, deleted out-of-band, or duplicated. The toolbox:

- **`terraform state mv`** — rename a resource address or move it into/out of a module *without* destroying it. Refactoring `aws_instance.web` → `module.compute.aws_instance.web` would otherwise be a destroy+create; `state mv` preserves the real object.
- **`terraform state rm`** — forget a resource (Terraform stops managing it; the real object survives). Used before re-importing or when handing ownership to another config.
- **`terraform import`** — adopt an existing object (Q17).
- **`moved {}` blocks** (1.1+) — the *declarative, reviewable, version-controlled* replacement for `state mv`. Prefer these because they live in code and apply consistently across every environment and teammate.
- **`removed {}` blocks** (1.7+) — declaratively drop a resource from state without destroying it.

```hcl
moved {
  from = aws_instance.web
  to   = module.compute.aws_instance.web
}
```

**Production discipline:** always `terraform state pull > backup.tfstate` first; remote backends keep versioned history (S3 versioning) so you can roll back. Never hand-edit the JSON unless every other option is exhausted — a malformed state breaks every future run. Acquire the lock and ensure no CI run is mid-flight. The biggest real-world incident class here is two people doing state surgery simultaneously, so coordinate and lock.

### Q19. [Coding] Test a module with Terratest (Go) and native `terraform test`.

**Problem:** Verify a VPC module actually creates a VPC with the expected CIDR. Show both the Go/Terratest approach (integration, real cloud) and the native HCL test framework (1.6+).

```go
// test/vpc_test.go — Terratest: applies real infra, asserts, then destroys
package test

import (
	"testing"
	"github.com/gruntwork-io/terratest/modules/terraform"
	"github.com/stretchr/testify/assert"
)

func TestVpcModule(t *testing.T) {
	opts := &terraform.Options{
		TerraformDir: "../modules/vpc",
		Vars: map[string]interface{}{
			"name": "test", "cidr_block": "10.42.0.0/16", "az_count": 2,
		},
	}
	defer terraform.Destroy(t, opts)        // teardown even on failure
	terraform.InitAndApply(t, opts)

	subnetIDs := terraform.OutputList(t, opts, "subnet_ids")
	assert.Equal(t, 2, len(subnetIDs))      // assert real outputs
	assert.NotEmpty(t, terraform.Output(t, opts, "vpc_id"))
}
```

```hcl
# tests/vpc.tftest.hcl — native framework, runs with `terraform test`
variables {
  name       = "test"
  cidr_block = "10.42.0.0/16"
  az_count   = 2
}

run "creates_expected_subnets" {
  command = plan                          # plan-only: fast, no real resources

  assert {
    condition     = length(aws_subnet.public) == 2
    error_message = "Expected 2 public subnets"
  }
  assert {
    condition     = aws_vpc.this.cidr_block == "10.42.0.0/16"
    error_message = "VPC CIDR mismatch"
  }
}
```

**Trade-offs / complexity:** Terratest gives *high-fidelity* integration testing — it actually provisions in a sandbox account and validates behavior — but each test costs minutes and real money, and you need a Go toolchain. The native `terraform test` framework needs no extra language, runs `plan`-mode assertions in seconds (or `apply` for true integration), and ships with Terraform. **Strategy:** native tests for fast unit/plan validation in every PR; Terratest (or `apply`-mode native tests) nightly against a sandbox for true end-to-end coverage. **Edge case:** always `defer Destroy` and namespace test resources (random suffix) so parallel test runs and leaked resources don't collide.

### Q20. [Theory] Explain provider versioning, the dependency lock file, and upgrade strategy.

Terraform resolves provider versions from `required_providers` constraints, then records the *exact* resolved versions and hashes in `.terraform.lock.hcl` (introduced 1.0). This lock file is the equivalent of `package-lock.json` — **commit it** so every developer and CI run uses identical provider builds, making plans reproducible and protecting against a malicious or breaking upstream release.

Version constraint operators:
- `= 5.31.0` pin exact
- `~> 5.31` allows 5.31.x but not 5.32 (pessimistic, patch-only)
- `~> 5.0` allows 5.x but not 6.0
- `>= 5.0, < 6.0` explicit range

Upgrade strategy I use: pin with `~>` on the minor in shared modules; bump deliberately via `terraform init -upgrade`, regenerate the lock, run the full plan/test suite, and read the provider changelog for breaking changes (major version bumps like AWS 4→5 often relocate arguments and require state migrations). The lock file also stores multi-platform hashes (`terraform providers lock -platform=...`) so a lock generated on macOS still validates in a Linux CI runner.

### Q21. [Practical] Design a CI/CD pipeline and access model for Terraform at org scale.

```
 PR opened
   │
   ▼
 [CI] fmt ─▶ validate ─▶ tflint ─▶ tfsec/checkov ─▶ terraform test ─▶ plan
   │                                                                   │
   │                                          post plan as PR comment ─┘
   ▼
 Human review + required approvals (CODEOWNERS on prod paths)
   │
   ▼ merge to main
 [CD] terraform apply (saved plan)  via OIDC short-lived creds, in a locked run
   │
   ▼ drift-detection job (scheduled) ─▶ alert on exit code 2
```

Key decisions and the "why":

- **Plan on PR, apply on merge.** Reviewers see exactly what will change; nothing applies without approval. Use the *saved plan artifact* so apply == reviewed plan.
- **Static analysis gates:** `terraform fmt -check`, `validate`, `tflint`, and a security scanner (`checkov`, `tfsec`/Trivy) catch open security groups, unencrypted buckets, public RDS before they merge — policy-as-code (Sentinel/OPA) for hard org rules.
- **No long-lived cloud keys in CI.** Use **OIDC federation** (GitHub Actions / GitLab → AWS IAM role assumption) so the pipeline gets short-lived, scoped credentials. This is the single biggest security win — leaked static keys are the most common Terraform breach vector.
- **State isolation per service/env** keeps blast radius small and applies fast; a monolithic state file becomes a 30-minute plan and a single point of failure.
- **Least-privilege apply role**, separate read-only plan role, and mandatory locking. **Real-world case:** the 2019 misconfiguration class (and many since) traces back to over-privileged CI credentials plus public-by-default storage; the OIDC + scanner combo closes both.

### Q22. [Coding] Write a complex `for` expression and transform with functions.

**Problem:** Given a map of teams to lists of usernames, produce (a) a flattened map of `"team/user" => {team, user}` for `for_each`, and (b) only the users in teams tagged `admin`.

```hcl
variable "teams" {
  type = map(object({
    role  = string
    users = list(string)
  }))
  default = {
    platform = { role = "admin",  users = ["ana", "ben"] }
    qa       = { role = "viewer", users = ["cleo"] }
  }
}

locals {
  # (a) flatten nested structure into a single map keyed for_each-safely
  team_users = merge([
    for team, cfg in var.teams : {
      for user in cfg.users :
      "${team}/${user}" => { team = team, user = user, role = cfg.role }
    }
  ]...)                                   # ... spreads list-of-maps into merge()

  # (b) filter: only users whose team role is "admin"
  admin_users = {
    for k, v in local.team_users : k => v if v.role == "admin"
  }
}

resource "aws_iam_user" "all" {
  for_each = local.team_users
  name     = each.value.user
  tags     = { Team = each.value.team, Role = each.value.role }
}
```

**Walkthrough:** the outer `for` builds a *list of single-key maps*, and `merge(...)` with the `...` spread operator collapses them into one map — the idiomatic flatten pattern, since you can't directly `for_each` over nested loops. The filter uses the `if` clause inside a `for` expression. **Time/space:** O(T·U) over teams×users for both the build and filter — linear in total user count; space is O(N) for N total users. **Edge cases:** duplicate `"team/user"` keys would silently collide in `merge` (last wins) — if users can belong to multiple teams the composite key already disambiguates; if a username could repeat *within* one team, dedupe with `toset` first. Empty `users` lists simply contribute nothing.

### Q23. [Theory] What is OpenTofu, why did it fork, and what are the migration implications?

**OpenTofu** is the Linux Foundation–governed, open-source fork of Terraform created in 2023 after HashiCorp relicensed Terraform from MPL 2.0 to the **Business Source License (BSL)**, which restricts using the code to compete with HashiCorp. The community/vendors who depended on the old open license forked the last MPL version into OpenTofu (originally OpenTF). It's a near drop-in replacement: same HCL, same provider/registry ecosystem, `tofu` CLI mirroring `terraform`.

Why it matters in 2026 and divergence to know:
- **State encryption** — OpenTofu shipped *client-side state and plan encryption* (encrypt the blob before it leaves the machine), a feature Terraform lacked at the time; major differentiator for compliance-heavy orgs.
- **Early variable/provider evaluation**, `for_each` in provider blocks, and other features have diverged.
- **Licensing** — OpenTofu stays MPL/open; Terraform is BSL. For some companies (especially those building products on top), BSL is a legal blocker, making OpenTofu the only option.

Migration is typically straightforward (`terraform` → `tofu`, same state), but the right answer in an interview is nuanced: pick based on *licensing risk tolerance*, the specific features you need (state encryption vs Terraform Stacks/HCP integration), and whether your CI/tooling vendors support one or both. They will continue to diverge, so it's a strategic bet, not a cosmetic swap.

### Q24. [Practical] A `terraform apply` failed halfway. What state is your infrastructure in and how do you recover?

A partial apply leaves you in a **partially-applied state**: resources that completed are in state; resources that failed are not (or are tainted). Terraform is *not* transactional — there's no automatic rollback across an apply. Recovery:

1. **Read the error precisely.** Terraform reports which resources succeeded, which failed, and (since it writes state incrementally) state already reflects the successes — so don't panic that state is "lost."
2. **Check the lock.** If the process was killed, release a dangling lock with `force-unlock` only after confirming nothing is still running.
3. **Re-run `terraform plan`.** Terraform re-reconciles: it sees what now exists and proposes to finish the remaining work. Often a simple re-`apply` completes it (e.g. the failure was a transient API throttle).
4. **Handle tainted/half-created resources.** A resource created but not fully provisioned may be marked tainted (or you mark it: `terraform taint` / the modern `-replace` flag) so the next apply recreates it cleanly.
5. **If a resource was created in the cloud but failed *before* state recorded it** (rare, e.g. crash between API success and state write), you get an "already exists" error on retry — `terraform import` the orphan or delete it manually.

The deeper lesson: keep applies small and idempotent, run them through CI with locking, and design resources so a half-built one fails closed (e.g. health checks) rather than silently serving traffic.

---

## 🔴 Expert (15+ yrs)

### Q25. [Theory] How do you architect Terraform for a 500-engineer org without it becoming a bottleneck?

The failure mode at scale is a **monolithic state**: one giant config that takes 40 minutes to plan, locks out everyone during any apply, and where a typo can nuke prod. The architecture I drive:

- **Decompose by ownership and blast radius.** Each team owns small, independently-applied state files (per service × per environment), wired together by **remote state data sources** or a service catalog. A networking team owns VPC state; app teams consume its outputs read-only.
- **Layered stacks**: foundation (accounts, org, IAM, networking) → platform (clusters, shared data stores) → application. Lower layers change rarely and gate higher ones.
- **Modules as the API.** A platform team publishes versioned, opinionated modules (with built-in security defaults, tagging, naming) to an internal registry; product teams compose them. This is how you enforce standards without reviewing every line — the module *is* the guardrail.
- **Policy-as-code** (Sentinel/OPA/Conftest) for org-wide rules: "no public S3," "all resources tagged with cost-center," "prod requires two approvals."
- **Platform-as-product**: a self-service layer (Terraform Cloud/HCP, Atlantis, Spacelift, or an internal portal) so teams ship infra via PRs without ticket queues.

The trade-off is coordination cost across many state files (cross-stack dependencies, version skew in shared modules). You manage it with clear ownership, semantic versioning of modules, and automated drift/version reporting. The north star: **make the paved road the easy road**, so doing the right thing is less work than doing the wrong thing.

### Q26. [Behavioral] Tell me about a time IaC caused a production incident and what you changed.

Structure the answer with STAR and own the systemic fix, not just the hotfix. A strong narrative:

> *Situation:* An engineer ran `terraform apply` locally against prod (we still allowed it) while CI was mid-apply; the local run used a stale `count`-indexed list, and the plan proposed destroying and recreating the primary RDS instance. The apply partially executed before someone caught it.
>
> *Task:* Stop the bleeding, restore service, and make this class of incident impossible.
>
> *Action:* We restored from the automated snapshot, then root-caused two failures: (1) no locking enforcement for local applies, and (2) `count` used where `for_each` belonged, so a list reorder cascaded into a destroy. Fixes: removed all human apply permissions to prod (OIDC-scoped CI-only role), enforced remote state with locking, converted the fragile `count` resources to `for_each`, added a `prevent_destroy` lifecycle guard on stateful resources, and a CI check that fails any plan proposing to destroy a database.
>
> *Result:* Zero recurrence; the `prevent_destroy` + destroy-detection gate caught two further mistakes harmlessly in the following quarter.

The meta-point interviewers want: you turned a painful incident into structural guardrails (`prevent_destroy`, locking, least privilege, lint rules) rather than a "be more careful" Slack message. Blameless post-mortem, systemic fix.

### Q27. [Theory] Discuss `create_before_destroy`, lifecycle meta-arguments, and zero-downtime replacement.

When a change forces resource replacement (e.g. changing an EC2 `ami` or a launch template that can't be updated in place), the default is **destroy-then-create**, which causes downtime. The `lifecycle` block controls this:

```hcl
resource "aws_instance" "web" {
  # ...
  lifecycle {
    create_before_destroy = true            # stand up the new one first
    prevent_destroy       = true            # refuse any plan that would destroy this
    ignore_changes        = [tags["LastScanned"]]
    replace_triggered_by  = [aws_launch_template.web.latest_version]
  }
}
```

- **`create_before_destroy`** flips the order: provision the replacement, cut traffic over, then destroy the old — the foundation of zero-downtime replacement. The catch: it requires resources that can coexist (unique names, no fixed EIP conflict), so you often must interpolate a random suffix or use `name_prefix` to avoid "already exists" collisions during the overlap.
- **`prevent_destroy`** is a safety latch on stateful resources (databases, state buckets) — any plan that would destroy it errors out, forcing a deliberate config change to override.
- **`ignore_changes`** suppresses drift on externally-managed attributes (Q13).
- **`replace_triggered_by`** (1.2+) forces replacement when a referenced resource/attribute changes — useful to roll instances when a launch template version bumps.

At scale, true zero-downtime usually combines `create_before_destroy` with health-checked load balancers, connection draining, and instance-refresh / rolling strategies on ASGs — Terraform orchestrates the replacement order, but the application-layer cutover (health checks, draining) is what actually prevents dropped requests.

### Q28. [Coding] Implement a safe, validated, multi-region module with provider aliasing and input validation.

**Problem:** A module must deploy a primary resource in one region and a replica in another, with strict input validation and guardrails against invalid configurations.

```hcl
# variables.tf
variable "environment" {
  type = string
  validation {
    condition     = contains(["dev", "staging", "prod"], var.environment)
    error_message = "environment must be one of: dev, staging, prod."
  }
}

variable "replica_region" {
  type = string
  validation {
    condition     = can(regex("^[a-z]{2}-[a-z]+-\\d$", var.replica_region))
    error_message = "replica_region must look like 'us-west-2'."
  }
}

# providers.tf — caller passes both providers in
terraform {
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 5.0" }
  }
}

# main.tf — primary + cross-region replica via aliased provider
resource "aws_s3_bucket" "primary" {
  bucket = "acme-${var.environment}-data"
}

resource "aws_s3_bucket" "replica" {
  provider = aws.replica                  # aliased provider, different region
  bucket   = "acme-${var.environment}-data-replica"
}

# lifecycle guard on prod data
resource "aws_dynamodb_table" "sessions" {
  name         = "sessions-${var.environment}"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "id"
  attribute { name = "id", type = "S" }

  lifecycle {
    prevent_destroy = true                # never silently drop prod table
  }
}
```

```hcl
# Caller wires the aliased provider:
provider "aws"          { region = "us-east-1" }
provider "aws" {
  alias  = "replica"
  region = "us-west-2"
}

module "data" {
  source         = "./modules/data"
  environment    = "prod"
  replica_region = "us-west-2"
  providers = {
    aws         = aws
    aws.replica = aws.replica
  }
}
```

**Why this matters / complexity:** validation blocks fail fast at plan time (O(1) checks) instead of after a half-built apply; `can(regex(...))` returns false instead of erroring so the message is friendly. Provider aliasing is *the* mechanism for multi-region/multi-account in one module — without it you'd need separate root configs. **Edge cases:** `prevent_destroy` will block `terraform destroy` of the whole stack too (you must remove the guard or the resource from the destroy scope deliberately) — that friction is the point. A subtle gotcha: a module that *declares its own* aliased provider config can't be removed cleanly later; best practice is to pass providers in from the root (as shown) rather than configure them inside the module.

### Q29. [Practical] How would you migrate a 6-year-old, fully click-ops AWS account into Terraform with zero downtime?

This is a brownfield-adoption marathon. My playbook:

1. **Inventory & freeze.** Use AWS Config / Resource Explorer / `terraformer` to enumerate everything. Announce a soft change-freeze on manual edits to in-scope resources so reality stops moving.
2. **Slice by blast radius, lowest-risk first.** Start with stateless, easily-recreated resources (IAM, security groups, DNS) before touching databases and stateful stores. Never start with prod RDS.
3. **Import incrementally** using `import {}` blocks + `-generate-config-out` (1.5+) to bootstrap config, then hand-refine until `plan` shows **zero changes** — that empty diff is the proof your code matches reality and apply is safe.
4. **Wrap, don't rewrite.** The goal of phase one is *management parity*, not refactoring. Resist the urge to "clean up" naming during import — that turns a safe no-op into a destroy/recreate.
5. **Gate with `prevent_destroy`** on every stateful resource the moment it's imported, so a config mistake can't delete data.
6. **Move to modules later.** Once everything is imported and stable, refactor into modules using `moved {}` blocks (no resource churn).
7. **Cut over change control.** Switch IAM so manual console changes are denied for managed resources; all changes now flow through the pipeline. Run scheduled drift detection to catch stragglers.

**Trade-offs:** a clean greenfield rebuild is tempting and produces nicer code, but for a live revenue system the import path's zero-downtime guarantee almost always wins. The realistic timeline is months, done service-by-service, with the empty-diff plan as the safety gate at every step. Real-world parallel: most enterprise "cloud governance" programs are exactly this migration, and the ones that fail are the ones that tried a big-bang rewrite.

### Q30. [Theory] Terraform Stacks, ephemeral values, and where the ecosystem is heading in 2026.

The frontier topics a 15+ engineer should speak to:

- **Terraform Stacks (HCP)** — HashiCorp's answer to the multi-state-file orchestration problem (Q25). Stacks let you define components and *deployments* declaratively so you can roll the same stack across many accounts/regions with managed dependencies and a single plan/apply across components — replacing a lot of Terragrunt/CI glue. Trade-off: it's HCP-tied (BSL/commercial), so it deepens vendor lock-in versus the open OpenTofu path.
- **Ephemeral values & write-only arguments** (Terraform 1.10+) — values that are used during an operation but *never persisted to state or plan files*, closing the long-standing "secrets leak into state" hole for things like passing a fetched secret to a resource. This is a genuine security advance and the modern answer to Q10.
- **`terraform test` maturation** — native testing (Q19) is now first-class, shifting IaC toward real TDD.
- **The OpenTofu vs Terraform divergence** (Q23) is the strategic fork in the road: state encryption and open licensing on one side, Stacks/HCP/ecosystem gravity on the other.
- **Policy-as-code and provider-defined functions** continue to push guardrails left.

The senior judgment is recognizing these aren't just features to memorize — each is a response to a structural pain (state secrets, orchestration sprawl, untested infra) you've felt in production, and choosing among them is a licensing + lock-in + capability bet, not a checkbox.

---

## ✅ Key Takeaways

- **State is the heart of Terraform** — it's a credential store and the source of truth. Remote backend + locking + encryption + least-privilege access are non-negotiable in any team setting.
- **Prefer `for_each` over `count`** for anything with changing membership; positional indices in `count` cause destructive reindexing.
- **Declarative + plan-before-apply** is the core value: review the diff, apply the *saved* plan, never let reality drift between the two.
- **Modules are your API and your guardrail.** Versioned, opinionated modules let a platform team enforce security and standards without reviewing every line.
- **Decompose state by ownership and blast radius** at scale; a monolithic state is a 40-minute plan and a single point of failure.
- **Use lifecycle meta-arguments deliberately:** `prevent_destroy` on stateful resources, `create_before_destroy` for zero-downtime replacement, `ignore_changes` for externally-managed drift.
- **Shift left with policy-as-code, security scanners, native `terraform test`, and OIDC short-lived credentials** in CI — no long-lived cloud keys.
- **Know OpenTofu vs Terraform**: licensing (MPL vs BSL), state encryption, and Stacks are the strategic 2026 differentiators.
- **Adopt brownfield infra via import to an empty-diff plan**, gate stateful resources immediately, refactor later with `moved {}`.

## ⚠️ Common Pitfalls

- **Committing `terraform.tfstate` to Git** — leaks secrets and invites concurrent-write corruption. Use a remote, encrypted, locked backend.
- **Forgetting to commit `.terraform.lock.hcl`** — non-reproducible plans and exposure to breaking provider releases.
- **Using `count` for collections with stable identity** — removing a middle element reindexes and destroys/recreates everything after it.
- **Hardcoding secrets in `.tf`/`.tfvars`** — and assuming `sensitive = true` keeps them out of state (it doesn't; it only redacts CLI output).
- **Treating workspaces as environment isolation across accounts** — same code path to prod, no per-env backend; prefer directory/account separation for serious isolation.
- **Running `apply` from laptops against prod** — no locking guarantees, stale plans; route everything through locked CI with OIDC.
- **Big-bang console-to-Terraform rewrites** — import incrementally to a zero-diff plan instead; mismatched config destroys live resources.
- **Editing state JSON by hand** — corrupts every future run; use `state mv`/`moved`/`import` and always back state up first.
- **Over-using `depends_on`** — artificial edges serialize the graph and slow applies; rely on implicit references.
- **No `prevent_destroy` on databases and state buckets** — one bad plan can irreversibly delete data.
- **Provider major-version bumps without reading the changelog** (e.g. AWS 4→5) — relocated arguments and required state migrations break applies.

## 📚 Further Reading

- *Terraform: Up & Running* (3rd ed.) by Yevgeniy Brikman — the definitive practitioner's book, covering modules, testing, and team workflows.
- [Official Terraform Documentation](https://developer.hashicorp.com/terraform/docs) — language reference, backends, CLI, and the `terraform test` framework.
- [OpenTofu Documentation](https://opentofu.org/docs/) — fork specifics including client-side state encryption.
- [Terraform Best Practices](https://www.terraform-best-practices.com/) (Anton Babenko) — community-driven structure, naming, and module conventions.
- [Gruntwork Terratest](https://terratest.gruntwork.io/) — Go library and patterns for integration-testing infrastructure code.
- [HashiCorp Well-Architected / Validated Patterns](https://developer.hashicorp.com/well-architected-framework) — reference architectures for scaling Terraform across an organization.
